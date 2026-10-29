package com.eratw.port.erbcc.compile

import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.MappedErbc
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.loader.StmtOffsetSidecar
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 产物新鲜度问题分类（每条都对应一种「产物与语料/侧车不是一对」的具体形态）。 */
enum class FreshnessIssueKind {
    /** 缺 `manifest.txt`：无从判断产物属于哪一版语料。 */
    MANIFEST_MISSING,

    /** `manifest.txt` 读不出来。 */
    MANIFEST_UNREADABLE,

    /** 清单登记了产物，磁盘上没有。 */
    ARTIFACT_MISSING,

    /** `program.erbc` 字节数与清单不符（写一半 / 被截断 / 被替换）。 */
    ARTIFACT_SIZE,

    /** `program.erbc` 摘要与清单不符（内容被改过）。 */
    ARTIFACT_SHA,

    /** 容器头畸形：魔数/版本/段表槽数/段偏移越界（`MappedErbc.open` 的判据）。 */
    CONTAINER_INVALID,

    /** 清单里的源文件在语料里已不存在。 */
    SOURCE_MISSING,

    /** 源文件内容与清单记录的 SHA-256 不符 —— **改了源码但没重编**。 */
    SOURCE_CHANGED,

    /**
     * 清单的 `compiler-digest` 与**当前编译器源码树**重算值不符 ——
     * **产物由旧编译器产出，必须重编**。
     *
     * 三种形态共用本类：清单没有 `compiler-digest` 行（旧格式/旧编译器产物）、digest 与当前
     * 源码树不一致、以及源码树定位不到（无法重算）。三者都意味着「无法证明产物出自当前
     * 编译器」，语义等价于「旧产物」，不能放行。
     */
    COMPILER_STALE,

    /** 语料里有清单没登记的 ERB/ERH（新增文件没重编）。 */
    SOURCE_ADDED,

    /** 正式产物必须带 `program.meta`（装载期元数据），缺了就只能退回旧装载链。 */
    META_MISSING,

    /** `program.meta` 格式坏。 */
    META_UNREADABLE,

    /** `program.meta` 头部的 `program-sha256` 与磁盘容器不符 —— 侧车与容器不是一对。 */
    META_STALE,

    /** 容器有语句池但缺 `program.stmt.idx`（装载期要回落逐条扫描）。 */
    SIDECAR_MISSING,

    /** `program.stmt.idx` 结构坏（魔数/版本/尺寸），装载侧同样会忽略它。 */
    SIDECAR_UNREADABLE,

    /** 段 3 侧车的语句条数与容器段 3 声明不符 —— 半新半旧的一对。 */
    SIDECAR_STALE,
}

/** 一条新鲜度问题。 */
data class FreshnessIssue(val kind: FreshnessIssueKind, val detail: String) {
    override fun toString(): String = "$kind: $detail"
}

/**
 * 一次新鲜度检查的结果。[ok] = 无问题。
 *
 * [compilerDigest] = 本次检查时**当前编译器源码树**重算出的指纹（定位不到源码树时为 null，
 * 此时必有 [FreshnessIssueKind.COMPILER_STALE]）；[summary] 把它一并上屏，便于人工比对清单。
 */
class FreshnessReport(
    val outDir: Path,
    val issues: List<FreshnessIssue>,
    val compilerDigest: String? = null,
) {
    val ok: Boolean get() = issues.isEmpty()

    /** 单行摘要（CLI 直接打印）。 */
    fun summary(): String =
        if (ok) {
            "$outDir: 产物与语料/编译器一致（容器/侧车/源文件摘要/编译器指纹全对齐" +
                (compilerDigest?.let { "，compiler-digest $it" } ?: "") + "）"
        } else {
            "$outDir: ${issues.size} 条新鲜度问题\n" + issues.joinToString("\n") { "  $it" }
        }
}

/**
 * 产物新鲜度检查（可执行判据，不是注释）：**清单登记的源文件摘要 × 磁盘现状**。
 *
 * ## 为什么需要它
 *
 * 既有机制只保证「产物内部自洽」，不保证「产物属于当前语料」：
 * - `manifest.txt` 记了每个源文件的 `相对路径 字节数 SHA-256`（[ErbcArtifacts.manifestText]），
 *   但**没有任何读侧代码拿它跟磁盘对账** —— 语料改动与产物之间没有任何判据；
 * - `program.meta` 的 `program-sha256` 只在**打包安装侧**核对（`GameAssetInstaller.kt:102-118`
 *   的配对事务），构建目录本身没人查；
 * - `program.stmt.idx` 的读侧只做结构校验（单调递增 + 落在记录区内 + 末条记录正好收在段尾，
 *   `ErbcStream.kt:942-971`）—— 它能挡住「表与产物错位」，但挡不住「产物本身过期」。
 *
 * 于是「改了源码没重编」在过去是**不可检出**的：产物照旧能装、能跑，只是跑的是旧逻辑。
 * 本对象把这条判据变成一次顺序读：清单 → 容器（字节数/摘要/段表）→ 每个源文件 → 两个侧车。
 *
 * ## 判据（逐条对应 [FreshnessIssueKind]）
 *
 * 1. 容器：字节数 + SHA-256 必须等于清单的 `artifact` 条目；再用 [MappedErbc.open] 解一次头
 *    （魔数/版本/段表槽数/段偏移越界，`MappedErbc.kt:115-175`），挡住「半截容器」。
 * 2. 源文件：逐条重算 SHA-256；缺失 / 改动 / 新增都报。语料文件集与编译输入同源
 *    （[CorpusCompile.listCorpusFiles]，含 `GOLDEN_*` 排除与二进制文件口径），不另立规则。
 * 3. `program.meta`：`program-sha256` 必须等于容器摘要（`ErbcArtifactManifestTest` 锁死的配对口径）。
 * 4. `program.stmt.idx`：语句条数必须等于容器段 3 声明的条数（段 3 布局 = `u32 nodeCount,
 *    u32 语句数, ...`，`ErbcWriter.kt:313-343`）。结构坏则报 `SIDECAR_UNREADABLE`。
 * 5. 编译器指纹：清单 `compiler-digest` 必须等于**当前源码树**的重算值（[ErbcCompilerDigest]）。
 *    这是「语料没改、产物自洽，但编译器换了」的唯一可检出判据：真机红 J 的循环变量回写
 *    死循环（`1c1c3e0d` 修 `NEXT`/`FOR STEP`）就是被旧产物带进包的。
 *
 * 不做的：不重编、不比对指令流、不校验 `manifest.txt` 自身摘要（它没有自引用字段）。
 */
object ArtifactFreshness {

    /** 产物目录名（冻结的跨模块路径常量，见 [ErbcArtifacts] KDoc）。 */
    const val ARTIFACT_DIR_NAME: String = "erbc.v${ErbcFormat.VERSION_1}"

    /** 清单文件名（[ErbcArtifacts.write] 落盘名）。 */
    const val MANIFEST_NAME: String = "manifest.txt"

    /**
     * 检查 `<outDir>/erbc.v1` 下的产物是否对应当前 [corpusRoot] **与当前编译器源码树**。
     *
     * @param outDir `erbcc compile --out` 传的那个目录（不是它下面的 `erbc.v1`）
     * @param corpusRoot 语料根（与编译时同一路径；清单里的相对路径相对它）
     * @param compilerRepoRoot 仓库根（算 `compiler-digest` 用）；null = 从 [outDir] /
     *   工作目录 / 本类 class 位置往上找（[ErbcCompilerDigest.locateRepoRoot]）。
     *
     *   为什么用「可选形参 + 自动上溯」而不是把仓库根变成必填：`check` 的两个既有入参
     *   （`outDir`、`corpusRoot`）都**不保证在仓库里**（产物目录可以在任意 `--out`、语料根
     *   可以是共享盘上的游戏目录），而编译器源码恒在**仓库内**。既有调用点
     *   （`Main.cmdCheck` 与各模块测试）都在仓库内运行，自动上溯对它们是零改动；测试要
     *   模拟「另一份编译器源码树」时才显式传本形参。
     */
    fun check(outDir: Path, corpusRoot: Path, compilerRepoRoot: Path? = null): FreshnessReport {
        val root = outDir.toAbsolutePath().normalize()
        val corpus = corpusRoot.toAbsolutePath().normalize()
        val artifactDir = root.resolve(ARTIFACT_DIR_NAME)
        val manifestPath = artifactDir.resolve(MANIFEST_NAME)
        val issues = ArrayList<FreshnessIssue>()

        if (!Files.isRegularFile(manifestPath)) {
            issues += FreshnessIssue(
                FreshnessIssueKind.MANIFEST_MISSING,
                "缺 $manifestPath —— 没有清单就无从判断产物属于哪一版语料",
            )
            return FreshnessReport(root, issues)
        }
        val text = try {
            String(Files.readAllBytes(manifestPath), Charsets.UTF_8)
        } catch (e: Exception) {
            issues += FreshnessIssue(
                FreshnessIssueKind.MANIFEST_UNREADABLE,
                "$manifestPath 读取失败: ${e::class.simpleName}: ${e.message}",
            )
            return FreshnessReport(root, issues)
        }
        val manifest = parse(text)
        val artifactEntry = manifest.artifact
        if (artifactEntry == null) {
            issues += FreshnessIssue(
                FreshnessIssueKind.MANIFEST_UNREADABLE,
                "$manifestPath 没有可解析的 artifact 行（清单被截断或格式不符）",
            )
            return FreshnessReport(root, issues)
        }

        // ---- 编译器指纹（先查：语料摘要对得上但编译器换了，是最容易被放行的形态）----
        val compiler = checkCompiler(manifest, root, compilerRepoRoot, issues)

        // ---- 1. 容器字节 + 段表 ----
        val artifactPath = root.resolve(artifactEntry.relPath)
        if (!Files.isRegularFile(artifactPath)) {
            issues += FreshnessIssue(FreshnessIssueKind.ARTIFACT_MISSING, "清单登记的 ${artifactEntry.relPath} 不存在")
        } else {
            val size = Files.size(artifactPath)
            if (size != artifactEntry.bytes) {
                issues += FreshnessIssue(
                    FreshnessIssueKind.ARTIFACT_SIZE,
                    "${artifactEntry.relPath} 实际 $size 字节，清单登记 ${artifactEntry.bytes} 字节（写一半或被截断）",
                )
            } else {
                val sha = sha256Hex(artifactPath)
                if (sha != artifactEntry.sha256) {
                    issues += FreshnessIssue(
                        FreshnessIssueKind.ARTIFACT_SHA,
                        "${artifactEntry.relPath} 摘要 $sha，清单登记 ${artifactEntry.sha256}",
                    )
                }
            }
            val mapped = try {
                MappedErbc.open(artifactPath)
            } catch (e: Exception) {
                issues += FreshnessIssue(
                    FreshnessIssueKind.CONTAINER_INVALID,
                    "${artifactEntry.relPath} 段表不可信: ${e::class.simpleName}: ${e.message}",
                )
                null
            }
            if (mapped != null) {
                try {
                    checkSidecars(artifactDir, artifactPath, artifactEntry, mapped, issues)
                } finally {
                    mapped.close()
                }
            }
        }

        // ---- 2. 源文件 ----
        val recorded = HashSet<String>(manifest.sources.size)
        for (s in manifest.sources) {
            recorded += s.relPath
            val path = corpus.resolve(s.relPath)
            if (!Files.isRegularFile(path)) {
                issues += FreshnessIssue(FreshnessIssueKind.SOURCE_MISSING, "清单登记的源文件已不存在: ${s.relPath}")
                continue
            }
            val bytes = try {
                Files.readAllBytes(path)
            } catch (e: Exception) {
                issues += FreshnessIssue(
                    FreshnessIssueKind.SOURCE_CHANGED,
                    "源文件读不出来（无法确认与产物同版）: ${s.relPath} ${e::class.simpleName}",
                )
                continue
            }
            if (bytes.size.toLong() != s.bytes || ErbcArtifacts.sha256Hex(bytes) != s.sha256) {
                issues += FreshnessIssue(
                    FreshnessIssueKind.SOURCE_CHANGED,
                    "源文件已改动但产物未重编: ${s.relPath}（清单 ${s.bytes} 字节 / 磁盘 ${bytes.size} 字节）",
                )
            }
        }
        for (path in CorpusCompile.listCorpusFiles(corpus)) {
            val rel = corpus.relativize(path).toString().replace('\\', '/')
            if (rel !in recorded) {
                issues += FreshnessIssue(FreshnessIssueKind.SOURCE_ADDED, "语料新增文件未编进产物: $rel")
            }
        }

        return FreshnessReport(root, issues, compiler?.digest)
    }

    /**
     * 编译器指纹判据：清单 `compiler-digest` × 当前源码树重算值。
     *
     * 返回当前源码树的指纹（定位不到时 null，同时已记一条
     * [FreshnessIssueKind.COMPILER_STALE]）。三种形态都报同一个 kind（语义都是「产物不是
     * 当前编译器编的」），detail 里区分，便于人直接定位：
     * - 清单没有 `compiler-digest` 行 → 旧格式/旧编译器产物；
     * - digest 不等 → 编译器源码改过而产物没重编；
     * - 定位不到源码树 → 无法证明产物出自当前编译器（不放行）。
     */
    private fun checkCompiler(
        manifest: ParsedManifest,
        outDir: Path,
        compilerRepoRoot: Path?,
        issues: MutableList<FreshnessIssue>,
    ): ErbcCompilerDigest.Result? {
        val repoRoot = compilerRepoRoot?.toAbsolutePath()?.normalize()
            ?: ErbcCompilerDigest.locateRepoRoot(outDir)
        val current = try {
            repoRoot?.let { ErbcCompilerDigest.compute(it) }
        } catch (e: Exception) {
            issues += FreshnessIssue(
                FreshnessIssueKind.COMPILER_STALE,
                "无法重算编译器的 compiler-digest（$repoRoot：${e::class.simpleName}: ${e.message}）→ " +
                    "不能证明产物出自当前编译器，必须重编：./gradlew :tools:erbcc:compileCorpus",
            )
            return null
        }
        if (current == null) {
            issues += FreshnessIssue(
                FreshnessIssueKind.COMPILER_STALE,
                "找不到编译器源码树（从 $outDir / 工作目录 / 本类 class 位置往上都没有 " +
                    ErbcCompilerDigest.MARKER_REL + "）→ 不能证明产物出自当前编译器，" +
                    "必须重编：./gradlew :tools:erbcc:compileCorpus",
            )
            return null
        }
        val declared = manifest.compilerDigest
        if (declared == null) {
            issues += FreshnessIssue(
                FreshnessIssueKind.COMPILER_STALE,
                "产物由旧编译器产出（清单没有 compiler-digest 行，是旧格式/旧编译器产物），必须重编：" +
                    "当前源码树 ${current.digest} 共 ${current.fileCount} 个源文件 → ./gradlew :tools:erbcc:compileCorpus",
            )
        } else if (!declared.equals(current.digest, ignoreCase = true)) {
            issues += FreshnessIssue(
                FreshnessIssueKind.COMPILER_STALE,
                "产物由旧编译器产出，必须重编：清单 compiler-digest $declared" +
                    "（compiler-sources ${manifest.compilerSources ?: "(缺)"} 个），当前源码树 ${current.digest}" +
                    "（${current.fileCount} 个）→ ./gradlew :tools:erbcc:compileCorpus",
            )
        }
        return current
    }

    /**
     * 两个侧车的配对校验（容器已确认可解头）。
     *
     * `program.meta`：`program-sha256` 行必须等于清单登记的容器摘要（写侧由
     * `BootMetaWriter.write(path, metadata, artifact.sha256)` 落，见 [ErbcArtifacts.write]）。
     * `program.stmt.idx`：条数必须等于容器段 3 声明的语句数。
     */
    private fun checkSidecars(
        artifactDir: Path,
        artifactPath: Path,
        artifactEntry: HashEntry,
        mapped: MappedErbc,
        issues: MutableList<FreshnessIssue>,
    ) {
        val metaPath = artifactDir.resolve(BootMetaWriter.FILE_NAME)
        if (!Files.isRegularFile(metaPath)) {
            issues += FreshnessIssue(
                FreshnessIssueKind.META_MISSING,
                "缺 ${BootMetaWriter.FILE_NAME}（装载期元数据：函数表 / #DIM / 标签 / 数据节）",
            )
        } else {
            try {
                val meta = BootMetadata.read(metaPath)
                if (meta.programSha256 != artifactEntry.sha256) {
                    issues += FreshnessIssue(
                        FreshnessIssueKind.META_STALE,
                        "${BootMetaWriter.FILE_NAME} 记 ${meta.programSha256 ?: "-"}，容器摘要 ${artifactEntry.sha256}（侧车与容器不是一对）",
                    )
                }
            } catch (e: Exception) {
                issues += FreshnessIssue(
                    FreshnessIssueKind.META_UNREADABLE,
                    "${BootMetaWriter.FILE_NAME} 读取失败: ${e::class.simpleName}: ${e.message}",
                )
            }
        }

        val idxPath = artifactDir.resolve(StmtOffsetSidecar.FILE_NAME)
        val stmtCount = containerStatementCount(mapped)
        if (!Files.isRegularFile(idxPath)) {
            if (stmtCount > 0) {
                issues += FreshnessIssue(
                    FreshnessIssueKind.SIDECAR_MISSING,
                    "缺 ${StmtOffsetSidecar.FILE_NAME}（装载期会回落逐条扫描段 3，慢但正确）",
                )
            }
            return
        }
        val offsets = StmtOffsetSidecar.read(artifactPath)
        if (offsets == null) {
            issues += FreshnessIssue(
                FreshnessIssueKind.SIDECAR_UNREADABLE,
                "${StmtOffsetSidecar.FILE_NAME} 魔数/版本/尺寸不符（装载侧同样会忽略它）",
            )
            return
        }
        if (stmtCount >= 0 && offsets.size != stmtCount) {
            issues += FreshnessIssue(
                FreshnessIssueKind.SIDECAR_STALE,
                "${StmtOffsetSidecar.FILE_NAME} 声明 ${offsets.size} 条，容器段 3 声明 $stmtCount 条",
            )
        }
    }

    /** 容器段 3 声明的语句条数（段 3 布局 `u32 nodeCount, u32 语句数, …`，`ErbcWriter.kt:313-343`）。 */
    private fun containerStatementCount(mapped: MappedErbc): Int {
        if (!mapped.isPresent(ErbcFormat.SECTION_STATEMENTS)) return 0
        return try {
            mapped.u32At(mapped.offsetOf(ErbcFormat.SECTION_STATEMENTS) + 4)
        } catch (_: Exception) {
            -1
        }
    }

    /** 清单解析结果（只取新鲜度检查需要的字段）。 */
    private class ParsedManifest(
        val artifact: HashEntry?,
        val artifacts: List<HashEntry>,
        val sources: List<HashEntry>,
        /** `compiler-digest <hex>`；null = 旧格式/旧编译器清单（没有这一行）。 */
        val compilerDigest: String?,
        /** `compiler-sources <N>`；只用于问题文案（判据只认 digest）。 */
        val compilerSources: Int?,
    )

    /**
     * 解析 [ErbcArtifacts.manifestText] 的文本：`artifact` 多条（容器 + 侧车）、`source` 多条。
     *
     * 相对路径可能含空格（语料目录名就有），所以从**右**往左切：末两段是字节数与摘要，
     * 其余整段是相对路径。不认识的行忽略（清单允许追加字段）。
     *
     * CMP-74 起 `artifact` 行**不止一条**（容器 + `program.meta` + `program.stmt.idx`）：
     * 本闸判的「容器」= 以 [ProgramLoader.ARTIFACT_SUFFIX] 结尾的那条。旧写法取最后一条，
     * 合流后会把侧车当容器来对账（六个用例一起红）。找不到 `.erbc` 条目就返回 null，
     * 让上层报 [FreshnessIssueKind.MANIFEST_UNREADABLE] —— 宁可报红，不误判成「新鲜」。
     */
    private fun parse(text: String): ParsedManifest {
        val artifacts = ArrayList<HashEntry>()
        val sources = ArrayList<HashEntry>()
        var compilerDigest: String? = null
        var compilerSources: Int? = null
        for (line in text.lineSequence()) {
            if (line.startsWith("artifact ")) {
                entry(line.removePrefix("artifact "))?.let { artifacts += it }
            } else if (line.startsWith("source ")) {
                entry(line.removePrefix("source "))?.let { sources += it }
            } else if (line.startsWith("compiler-digest ")) {
                line.removePrefix("compiler-digest ").trim().takeIf { it.isNotEmpty() }?.let { compilerDigest = it }
            } else if (line.startsWith("compiler-sources ")) {
                compilerSources = line.removePrefix("compiler-sources ").trim().toIntOrNull()
            }
        }
        val container = artifacts.firstOrNull { it.relPath.endsWith(ProgramLoader.ARTIFACT_SUFFIX) }
        return ParsedManifest(container, artifacts, sources, compilerDigest, compilerSources)
    }

    private fun entry(rest: String): HashEntry? {
        val shaSep = rest.lastIndexOf(' ')
        if (shaSep <= 0) return null
        val sizeSep = rest.lastIndexOf(' ', shaSep - 1)
        if (sizeSep <= 0) return null
        val bytes = rest.substring(sizeSep + 1, shaSep).toLongOrNull() ?: return null
        val sha = rest.substring(shaSep + 1)
        val rel = rest.substring(0, sizeSep)
        if (rel.isEmpty() || sha.isEmpty()) return null
        return HashEntry(rel, bytes, sha)
    }

    /** 流式 SHA-256（容器可达数百 MB，不做整文件读入）。 */
    private fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }
}
