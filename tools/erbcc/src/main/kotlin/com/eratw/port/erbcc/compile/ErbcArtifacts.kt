package com.eratw.port.erbcc.compile

import com.eratw.port.core.Version
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Program
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.StmtOffsetSidecar
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 一条清单条目（源文件或产物；内容寻址）。 */
data class HashEntry(val relPath: String, val bytes: Long, val sha256: String)

/** 一次落盘的结果。 */
class WriteReport(
    val artifactDir: Path,
    val artifactPath: Path,
    val manifestPath: Path,
    val artifact: HashEntry,
    val sources: List<HashEntry>,
    /** 元数据侧车路径（`program.meta`）；null = 本次落盘没给元数据（旧调用点）。 */
    val metaPath: Path? = null,
    /** 侧车的内容寻址条目（相对路径 + 字节数 + SHA-256）；null = 未写。 */
    val meta: HashEntry? = null,
    /** 段 3 语句记录偏移表侧车路径（`program.stmt.idx`）；null = 没有语句池、未写。 */
    val stmtIdxPath: Path? = null,
    /** 偏移侧车的内容寻址条目；null = 未写。 */
    val stmtIdx: HashEntry? = null,
    /**
     * 清单 `artifacts` 表的全部条目 = 产物目录里除 `manifest.txt` 自身之外的全部文件
     * （CMP-74：随包文件集不再是「只有 program.erbc」）。
     */
    val artifacts: List<HashEntry> = listOf(artifact),
)

/**
 * `.erbc` 落盘：全量语料的单一 [Program] → 确定性目录结构 + 确定性 manifest。
 *
 * ## 目录结构（确定，版本化）
 *
 * ```
 * <out>/erbc.v1/program.erbc     容器本体（ErbcWriter.encode，单文件容器）
 * <out>/erbc.v1/program.meta     装载期元数据侧车（BootMetaWriter；UTF-8 + LF）
 * <out>/erbc.v1/program.stmt.idx 段 3 语句记录偏移表（可选加速件；无语句池时不写）
 * <out>/erbc.v1/manifest.txt     构建清单（纯文本，LF 换行，无时间戳）
 * ```
 *
 * 目录名里的版本号 `erbc.v1` 是**冻结的跨模块路径常量**：app 打包与设备侧装载链
 * （`GameHost.ARTIFACT_DIR` / `GameAssetInstaller.ARTIFACT_DIR` / `GameStorage.ARTIFACT_MARKER`
 * + `app/build.gradle.kts`）都以字面量 `erbc.v1` 取产物，与容器版本无关，本波不改（改目录名要
 * 三侧同步，属组长接线面）。容器的**真实版本**由 manifest 的 `container-version` 字段单源表达
 * （见下），且必须等于 [ErbcFormat.LATEST_VERSION] —— 规约 §8.7：派生工具的 manifest 版本号
 * 不得写死 [ErbcFormat.VERSION_1]（specs/erbc-format.md:490）。
 *
 * ## 清单 = 随包文件集的权威来源（CMP-74）
 *
 * `artifacts N` + N 行 `artifact <相对路径> <字节数> <SHA-256>` 登记**产物目录里除
 * `manifest.txt` 自身之外的全部文件**（`program.erbc` / `program.meta` / `program.stmt.idx`）。
 * 打包期若切片，再追加 `package-artifact` 表（`app/build.gradle.kts` 的 `stageGameAssets`，
 * 见规约 §7）：建站面的清单描述「erbcc 写了什么」，随包面的清单描述「APK 里实际有什么」，
 * `tools/apkpack/inspect_apk.py` 两边对账。`package.report` 只做体积/压缩口径自述，不是
 * 文件清单 —— 这是 CMP-74 之前两者字段集不相交的根因。
 * 侧车与容器**成对**：`program.meta` 头部带 `program-sha256`（`program.erbc` 的字节摘要），
 * 装载侧可据此验配对。
 *
 * ## 确定性（同一语料两次编译逐字节相同）
 *
 * - `program.erbc`：[ErbcWriter] 只用追加序，同一 Program 两次编码逐字节相同
 *   （core-vm `ErbcDeterminismTest` 锁死）；Program 本身由 [CorpusCompile] 的
 *   确定性装载序/编译序产出。
 * - `manifest.txt`：源文件按相对路径排序、产物条目按固定序（erbc → meta → stmt.idx）；
 *   不含时间戳/主机名；每条目 = 相对路径 + 字节数 + SHA-256。同一语料（同路径）两次编译
 *   ⇒ 清单逐字节相同。编译器指纹（`compiler-digest` / `compiler-sources`，见
 *   [ErbcCompilerDigest]）只由编译器源码树决定，不进时间/环境量 —— 同一源码树两次编译相同。
 *
 * ## 落盘原子性
 *
 * 四个文件（容器 / 段 3 侧车 / 元数据侧车 / 清单）一律「写 `<name>.tmp` → 原子改名」
 * （[AtomicWrite]）：进程被杀或磁盘满只会留下 `.tmp`，目标路径永远是「上一版完整文件」，
 * 不会出现「有合法魔数但长度不全」的半截容器。清单最后落 —— 它登记容器摘要，是这一版
 * 产物齐备的提交记录（代价：落盘期间容器占约 2× 磁盘，旧产物 + 临时文件）。
 *
 * ## 分片
 *
 * 分片**不在本模块**：`app/build.gradle.kts` 的 `stageGameAssets` 按 `-PeratwErbcChunkMb`
 * 把整容器字节流切成 `program.partNNN.erbc`（裸字节切片，不是独立容器；靠 `program.parts`
 * 行序拼回）。本波不写切片（规约 §7 已按实产口径改正，旧的「续片是合法容器」协议已退役）。
 */
object ErbcArtifacts {

    /**
     * 清单格式版本（与容器版本解耦）。
     *
     * - **1（旧，无字段）**：没有 `manifest-format` 行；`artifacts 1` 只登记 `program.erbc`，
     *   侧车不在表里 ⇒ 清单与随包文件集无法对账（CMP-74）。
     * - **2**：首部显式 `manifest-format 2`；`artifact` 表覆盖产物目录全部文件。
     *
     * 读侧判据：缺 `manifest-format` = 旧格式（按单产物理解，**不得**据旧清单断言随包完整）；
     * 大于本常量 = 未来格式，拒绝而不是猜（同容器版本规则）。
     */
    const val MANIFEST_FORMAT = 2

    private val HEX = "0123456789abcdef".toCharArray()

    /** SHA-256 小写十六进制（内容寻址；manifest 与对拍都用它）。 */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    /**
     * 写产物 + manifest（+ 元数据侧车）。
     *
     * @param corpusRoot 语料根（manifest 原样登记；排序后的 [sourceFiles] 逐条进清单）
     * @param stats 编译规模读数（manifest 的 program 段；可为 null = 不写）
     * @param bootMetadata 装载期元数据；null = 不写侧车（只为旧调用点保留缺省，
     *   正式产物必须传 —— `Main.cmdCompile` 已传 `CompileResult.bootMetadata`）
     * @param repoRoot 仓库根（用于算 `compiler-digest`，见 [ErbcCompilerDigest]）；null =
     *   从 [outDir] / 工作目录 / 本类 class 位置往上找。**找不到直接抛**，不写一份缺指纹的
     *   清单 —— 缺指纹的清单等于把「编译器改了没重编」的判据关掉。
     */
    fun write(
        outDir: Path,
        program: Program,
        corpusRoot: Path,
        sourceFiles: List<HashEntry>,
        stats: CompileStats? = null,
        bootMetadata: BootMetadata? = null,
        repoRoot: Path? = null,
    ): WriteReport {
        // 目录名版本号：冻结的跨模块路径常量（见 KDoc），非容器版本；容器真实版本走 manifest。
        val artifactDirVersion = ErbcFormat.VERSION_1
        val artifactDir = outDir.toAbsolutePath().normalize().resolve("erbc.v$artifactDirVersion")
        Files.createDirectories(artifactDir)

        // ErbcWriter.encodeIndexed 用默认选项 ⇒ 容器恒为 LATEST_VERSION（v2），与目录名的 v1 无关；
        // 容器字节与 encode(program) **逐字节相同**，额外带回段 3 语句记录偏移表。
        val encoded = ErbcWriter.encodeIndexed(program)
        val body = encoded.bytes
        val artifactPath = artifactDir.resolve("program.erbc")
        // 原子落盘：进程被杀/磁盘满只会留下 program.erbc.tmp，目标路径永远是「上一版完整产物」
        // （见 [AtomicWrite]）。容器是本目录里最大的文件，半截容器最容易被误当合法产物。
        AtomicWrite.write(artifactPath, body)
        val artifact = HashEntry("erbc.v$artifactDirVersion/program.erbc", body.size.toLong(), sha256Hex(body))

        // 段 3 语句记录偏移表侧车（2026-10-23 装载提速，可选件）：
        // 装载期直接读它就不必把 171 万条语句逐条反序列化一遍（真机实测 9.5 s → 百毫秒级）。
        // 与容器同目录，装载器按 `program.stmt.idx` 名字找；缺它自动回落扫描。
        //
        // 配对凭证（CMP-01）由 `StmtOffsetSidecar.write` 自己从同目录 `program.erbc` 取：
        // 头 v2 带 `recordBytes` + 段 3 记录区 CRC32（`StmtOffsetSidecar.kt:79-88/185-210`），
        // 所以这里不需要再传容器摘要；本波只把它的字节数/SHA-256 登记进 manifest。
        var stmtIdxPath: Path? = null
        var stmtIdx: HashEntry? = null
        encoded.statementRecordOffsets?.let { offsets ->
            val path = artifactDir.resolve(StmtOffsetSidecar.FILE_NAME)
            StmtOffsetSidecar.write(path, offsets, encoded.statementRecordBytes)
            val bytes = Files.readAllBytes(path)
            stmtIdxPath = path
            stmtIdx = HashEntry("erbc.v$artifactDirVersion/${StmtOffsetSidecar.FILE_NAME}", bytes.size.toLong(), sha256Hex(bytes))
        }

        // 侧车：与容器同目录、同基名、不同后缀；头部记容器摘要用于配对校验。
        var metaPath: Path? = null
        var meta: HashEntry? = null
        if (bootMetadata != null) {
            val path = artifactDir.resolve(BootMetaWriter.FILE_NAME)
            val report = BootMetaWriter.write(path, bootMetadata, artifact.sha256)
            metaPath = path
            meta = HashEntry("erbc.v$artifactDirVersion/${BootMetaWriter.FILE_NAME}", report.size.toLong(), report.sha256)
        }

        // 清单**最后**落：它登记容器的摘要，是「这一版产物齐了」的提交记录。前面任何一步失败
        // 都让清单保持旧值，于是「清单新 + 容器旧」这种半新半旧组合不会出现（反向由
        // [ArtifactFreshness] 兜：清单旧 + 容器新同样会被摘要对账检出）。
        // CMP-74：`artifacts` 表必须是产物目录里「除 manifest.txt 自身之外」的全部文件，
        // 所以侧车先落盘、再据此出清单。
        // 落盘走 [AtomicWrite]（M11-E erbcc-audit）：清单本身也要防半截 —— 它是新鲜度对账的
        // 基准，写一半的清单会让下一次 check 把整目录判成「已过期」。
        val sources = sourceFiles.sortedBy { it.relPath }
        val artifacts = listOfNotNull(artifact, meta, stmtIdx)
        // 编译器指纹：清单里唯一「语料之外」的判据 —— 编译器源码改了而产物没重编时，
        // 语料摘要全部照旧对得上，只有这一行会变（见 [ErbcCompilerDigest]）。
        val compilerRoot = repoRoot
            ?: ErbcCompilerDigest.locateRepoRoot(outDir)
            ?: throw IllegalStateException(
                "找不到 core-vm 编译器源码树（从 $outDir / 工作目录 / 本类 class 位置往上都没找到 " +
                    ErbcCompilerDigest.MARKER_REL + "）：manifest 的 compiler-digest 算不出来；" +
                    "请在仓库内运行 erbcc，或用 repoRoot 形参显式指定仓库根",
            )
        val compiler = ErbcCompilerDigest.compute(compilerRoot)
        val manifest = manifestText(corpusRoot, artifacts, sources, compiler.digest, compiler.fileCount, stats)
        val manifestPath = artifactDir.resolve("manifest.txt")
        AtomicWrite.write(manifestPath, manifest.toByteArray(Charsets.UTF_8))
        return WriteReport(
            artifactDir, artifactPath, manifestPath, artifact, sources,
            metaPath, meta, stmtIdxPath, stmtIdx, artifacts,
        )
    }

    /**
     * 确定性 manifest 文本（LF 换行；无时间戳、无主机名）。
     *
     * 字段：清单格式版本 + 容器版本（= 磁盘上 `program.erbc` 的真实版本）+ 引擎版本 +
     * **编译器指纹**（`compiler-digest` + `compiler-sources`）+ 构建档位 + 语料根 + 源文件数 +
     * 每条「相对路径 字节数 SHA-256」+ 产物条目表 + Program 规模读数。行序完全由输入决定
     * （源条目按相对路径排序、产物条目按给定序），不含任何运行期状态。
     *
     * 编译器指纹两行（[ErbcCompilerDigest] 口径，固定排在 `erbcc` 之后）：
     * - `compiler-digest <64 位小写十六进制>`：`core-vm/src/main/kotlin` 整树 `.kt` 的
     *   确定性 SHA-256；同一源码树两次编译相同，编译器改一个字节就变；
     * - `compiler-sources <N>`：参与计算的文件数（只为人读，判据只认 digest）。
     * 这两行是「编译器改了没重编」的唯一可检出判据：语料摘要与产物摘要在这种情形下全都一致。
     *
     * CMP-74 起产物表是**随包文件集的权威来源**：
     * - 首部 `manifest-format 2`（[MANIFEST_FORMAT]）区分新旧格式；
     * - `artifacts N` + N 行 `artifact <相对路径> <字节数> <SHA-256>`，覆盖产物目录里
     *   **除 `manifest.txt` 自身之外**的全部文件（`program.erbc` / `program.meta` /
     *   `program.stmt.idx`）。旧格式的 `artifacts 1` 只登记 program.erbc，是 CMP-74 的缺陷面。
     *
     * CMP-65 新增两行（都在 `erbcc` 之后，措辞固定）：
     * - `build-mode full` / `build-mode limited`（`stats` 缺失的旧调用点写 `unknown`）——
     *   受限档（`--limit N`）**必须**能与全量档区分，否则受限产物会被当全量打包；
     * - `file-limit N`（仅受限档）；`binary-skipped N`（CMP-64：被当二进制跳过的源文件数，
     *   这些文件仍在 `source` 行里但没进产物）。
     *
     * `format-version` / `container-version` 恒取 [ErbcFormat.LATEST_VERSION]：[write] 用
     * `ErbcWriter.encode(program)`（默认选项即 LATEST_VERSION），所以容器实字节就是这个版本。
     * 规约 §8.7 明令派生工具的 manifest 版本号不得写死 [ErbcFormat.VERSION_1]
     * （specs/erbc-format.md:490）；目录名 `erbc.v1` 是另一回事（冻结的跨模块路径常量）。
     * 清单**自身**的格式版本走 [MANIFEST_FORMAT] 的 `manifest-format`，两个版本号解耦：
     * 清单加字段不等于容器加段，反之亦然。
     */
    fun manifestText(
        corpusRoot: Path,
        artifacts: List<HashEntry>,
        sources: List<HashEntry>,
        compilerDigest: String,
        compilerSources: Int,
        stats: CompileStats? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("# erbc compile manifest —— 确定性构建清单（同一语料两次编译逐字节相同）\n")
        sb.append("manifest-format ").append(MANIFEST_FORMAT).append('\n')
        sb.append("format-version ").append(ErbcFormat.LATEST_VERSION).append('\n')
        sb.append("container-magic ").append(ErbcFormat.MAGIC_TEXT).append('\n')
        sb.append("container-version ").append(ErbcFormat.LATEST_VERSION).append('\n')
        sb.append("erbcc ").append(Version.VERSION).append('\n')
        // 编译器指纹（[ErbcCompilerDigest]）：语料与产物都自洽、只有编译器换了时，这两行是
        // 唯一会变的字段。放在 `erbcc` 之后、构建档位之前，位置固定（确定性）。
        sb.append("compiler-digest ").append(compilerDigest).append('\n')
        sb.append("compiler-sources ").append(compilerSources).append('\n')
        // CMP-65：受限档（`--limit N`）必须与全量档在清单里可区分 —— 否则受限产物可被
        // 当全量打包（`source-files` 是按全量语料登记的口径，两档读数一样）。缺失
        // `stats` 的旧调用点写 `unknown`（不谎报 full）。
        sb.append("build-mode ").append(if (stats == null) "unknown" else if (stats.fileLimit == null) "full" else "limited").append('\n')
        stats?.fileLimit?.let { sb.append("file-limit ").append(it).append('\n') }
        sb.append("corpus-root ").append(corpusRoot.toAbsolutePath().normalize()).append('\n')
        sb.append("source-files ").append(sources.size).append('\n')
        // CMP-64：被当二进制跳过（前 8KB 含 NUL）的源文件数；这些文件仍在上面的 source 行里，
        // 但一条指令都没进产物。出包侧据此判断「产物缺了语料里的某些文件」是否已知。
        stats?.let { sb.append("binary-skipped ").append(it.binarySkipped).append('\n') }
        for (s in sources) {
            sb.append("source ").append(s.relPath).append(' ')
                .append(s.bytes).append(' ').append(s.sha256).append('\n')
        }
        sb.append("artifacts ").append(artifacts.size).append('\n')
        for (a in artifacts) {
            sb.append("artifact ").append(a.relPath).append(' ')
                .append(a.bytes).append(' ').append(a.sha256).append('\n')
        }
        if (stats != null) {
            sb.append("program instructions ").append(stats.instructions).append('\n')
            sb.append("program statements ").append(stats.statements).append('\n')
            sb.append("program functions ").append(stats.functions).append('\n')
            sb.append("program strings ").append(stats.strings).append('\n')
            sb.append("program constants ").append(stats.constants).append('\n')
            sb.append("program expressions ").append(stats.expressions).append('\n')
            sb.append("program global-slots ").append(stats.globalSlots).append('\n')
            sb.append("program entry-function ").append(stats.entryFunction).append('\n')
        }
        return sb.toString()
    }
}
