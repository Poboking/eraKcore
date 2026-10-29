package com.eratw.port.erbcc.audit

import com.eratw.port.core.code.Program
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.NoSaveSystemOps
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.erbcc.compile.CompileResult
import com.eratw.port.erbcc.compile.CorpusCompile
import com.eratw.port.erbcc.compile.DynamicNameUse
import com.eratw.port.erbcc.compile.ErbcArtifacts
import com.eratw.port.erbcc.compile.KeywordUse
import com.eratw.port.erbcc.compile.SymbolUseWalker
import com.eratw.port.erbcc.compile.SymbolUses
import com.eratw.port.erbcc.compile.VarUse
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

/** 审计发现的类别。`RED_*` 是运行期必定抛 CodeEE 的硬差异，其余是装载期告警。 */
enum class FindingKind(val blocking: Boolean) {
    /** 解析期按变量引用，运行期命名空间里没有这个名字 ⇒「未定义的变量 X」。 */
    VAR_UNBOUND(true),
    /** 名字在，但静态类型与解析期相反 ⇒ 运行到读/写点报「是字符串变量/是整数变量」。 */
    VAR_TYPE_MISMATCH(true),
    /** 名字在，但下标个数与运行期维度不符 ⇒ 越界/缺参。 */
    VAR_DIM_MISMATCH(true),
    /** 字符串关键字下标在运行期 CSV / 角色模板表里查不到。 */
    KEYWORD_UNBOUND(true),
    /** 用户 `#DIM` 变量用了字符串下标，但 `<变量名>.csv` / `<变量名>@维.erd` 表不存在。 */
    KEYWORD_USER_VAR(true),
    /** 侧车里烤的私有 `#DIM` 在运行期注册被拒（装配期非致命，使用点才红）。 */
    PRIVATE_DIM_REJECTED(true),
    /** 全局 `#DIM` 在运行期安装被拒（level 2，非致命）。 */
    GLOBAL_DIM_REJECTED(true),
    /** 编译期 `#DIM` 解析告警（level 2，原版同样只 Warn；单列不参与红判）。 */
    COMPILE_DIM_WARNING(false),
}

/** 一条审计发现（已按使用点去重；[occurrences] 是同点的出现次数）。 */
class Finding(
    val kind: FindingKind,
    val file: String,
    val functionKey: String,
    val line: Int,
    val name: String,
    val detail: String,
    val occurrences: Int,
) {
    override fun toString(): String =
        "${kind.name}\t$file\t$functionKey\t$line\t$name\t${detail.replace('\t', ' ')}"
}

/** 全量 link audit 结果。 */
class SymbolAuditReport(
    val result: CompileResult,
    val findings: List<Finding>,
    val varUseSites: Int,
    val varRefsSeen: Long,
    val keywordUseSites: Int,
    val keywordRefsSeen: Long,
    val dynamicKeywordSites: Int,
    /** 名字字符串入口（GETVAR 等七族）里首参是**常量串**、已二次解析的使用点数（CMP-77）。 */
    val dynamicNameUseSites: Int = 0,
    /** 名字字符串入口里首参**不是**常量串（变量/FORM 串/缺参）的调用点数 ⇒ 构建期无从断言。 */
    val dynamicVarNameSites: Long = 0,
    /** 首参是常量串但解析不出变量引用的入口数（`GETVAR("1+2")` 这类）。 */
    val nameEntryParseFailures: Long = 0,
    val statementsWalked: Long,
    val runtimeGlobals: Int,
    val runtimePrivateVars: Int,
    val runtimeSystemVars: Int,
    val elapsedMs: Long,
) {
    /** 硬差异（运行期必红）。 */
    val blocking: List<Finding> get() = findings.filter { it.kind.blocking }

    fun count(kind: FindingKind): Int = findings.count { it.kind == kind }

    /** 写 TSV（kind/file/function/line/name/detail/occurrences），父目录不存在则创建。 */
    fun writeTsv(out: Path) {
        out.toAbsolutePath().normalize().parent?.let { Files.createDirectories(it) }
        val sb = StringBuilder("kind\tfile\tfunction\tline\tname\tdetail\tcount\n")
        for (f in findings) {
            sb.append(f.kind.name).append('\t')
                .append(clean(f.file)).append('\t')
                .append(clean(f.functionKey)).append('\t')
                .append(f.line).append('\t')
                .append(clean(f.name)).append('\t')
                .append(clean(f.detail)).append('\t')
                .append(f.occurrences).append('\n')
        }
        Files.writeString(out, sb.toString())
    }

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}

/**
 * 「落盘后再装载」对账的一条读数（CMP-79）。
 *
 * [inMemory] / [reloaded] 是同一判据在两条路上的**可读读数**（条数、哈希前缀、失败清单…），
 * 判等由 [equal] 单源表达 —— 大表（labels/files/functions）不做字符串化比较，
 * 只在 [detail] 里指出首差位置。
 */
class RoundTripCheck(
    val name: String,
    val inMemory: String,
    val reloaded: String,
    val equal: Boolean,
    val detail: String = "",
) {
    override fun toString(): String {
        val head = if (equal) "OK  " else "DIFF"
        return "$head\t$name\t内存=$inMemory\t装载=$reloaded" + if (detail.isEmpty()) "" else "（$detail）"
    }
}

/**
 * 一次「编译 → 落盘 → 装载链读回」对账结果（CMP-79）。
 *
 * 绿的判据：全部 [checks] 相等 —— 侧车模型逐字段、运行期命名空间三读数
 * （`runtimeGlobals` / `privateVars` / `systemVars`）、使用点名字集合哈希、
 * 以及容器语句/指令条数。
 */
class RoundTripReport(
    val artifactPath: Path,
    val metaPath: Path,
    val checks: List<RoundTripCheck>,
    val nameSetSize: Int,
    val nameSetHash: String,
    val elapsedMs: Long,
) {
    /** 不等的读数（空 = 绿）。 */
    val mismatches: List<RoundTripCheck> get() = checks.filterNot { it.equal }

    val ok: Boolean get() = mismatches.isEmpty()

    /** 写明细 TSV（`status/check/in_memory/reloaded/detail`），父目录不存在则创建。 */
    fun writeTsv(out: Path) {
        out.toAbsolutePath().normalize().parent?.let { Files.createDirectories(it) }
        val sb = StringBuilder("status\tcheck\tin_memory\treloaded\tdetail\n")
        for (c in checks) {
            sb.append(if (c.equal) "OK" else "DIFF").append('\t')
                .append(clean(c.name)).append('\t')
                .append(clean(c.inMemory)).append('\t')
                .append(clean(c.reloaded)).append('\t')
                .append(clean(c.detail)).append('\n')
        }
        Files.writeString(out, sb.toString())
    }

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}

/**
 * 装载期符号链接审计：**一次编译**收集全量 `VarRef` / `KeywordIndexArg` 使用点，
 * 再按运行期真实命名空间逐个查绑定。
 *
 * 为什么用 [GameSession] 而不是复刻装配逻辑：运行期命名空间由三部分组成 ——
 * CSV 装载后的系统变量表、`emuera.config` 驱动下的全局 `#DIM` 安装（含 SAVEDATA 闸）、
 * 侧车 `privateDims` 的逐条注册。三者任何一处与编译期 `CorpusScan`/`CompileResolver`
 * 分叉，都会表现为「解析过了、运行期未定义」。这里直接把真会话装出来，查的就是真表。
 *
 * 本审计**只读**：`NoSaveSystemOps` 不碰盘；不执行任何 ERB 语句；不改 `.erbc` 字节。
 */
object SymbolLinkAudit {

    /** 审计需要语料根（`<gameRoot>/ERB`）与游戏根（含 `CSV/` 与 `emuera.config`）。 */
    fun audit(
        corpusRoot: Path,
        gameRoot: Path,
        fileLimit: Int? = null,
    ): SymbolAuditReport {
        val t0 = System.currentTimeMillis()
        val root = corpusRoot.toAbsolutePath().normalize()
        val game = gameRoot.toAbsolutePath().normalize()

        val uses = SymbolUses()
        val result = CorpusCompile.compile(root, fileLimit, uses)
        val program = result.program
        if (program == null || !result.ok) {
            // 编译失败时没有可查的运行期命名空间；把编译失败原样抛给调用方（CLI 已打印）。
            return SymbolAuditReport(
                result = result,
                findings = emptyList(),
                varUseSites = uses.varUses.size,
                varRefsSeen = uses.varRefsSeen,
                keywordUseSites = uses.keywordUses.size,
                keywordRefsSeen = uses.keywordRefsSeen,
                dynamicKeywordSites = uses.keywordUses.values.count { it.key.keyword == null },
                dynamicNameUseSites = uses.dynamicNameUses.size,
                dynamicVarNameSites = uses.dynamicVarNameSites,
                nameEntryParseFailures = uses.nameEntryParseFailures,
                statementsWalked = uses.statementsWalked,
                runtimeGlobals = 0,
                runtimePrivateVars = 0,
                runtimeSystemVars = 0,
                elapsedMs = System.currentTimeMillis() - t0,
            )
        }

        val session = GameSession(
            gameRoot = game,
            program = program,
            metadata = result.bootMetadata,
            // 与 erbcc 侧车同一把尺：全局 #DIM 的 GETLINESTR 折叠发生在运行期
            // （元数据只存原文），宽度不一致会让审计报出假的 GLOBAL_DIM_REJECTED。
            consoleWidth = CorpusCompile.FOLD_CONSOLE_WIDTH_CELLS,
            systemOps = NoSaveSystemOps,
            randSeed = 0L,
        )
        val store = session.store
        val findings = ArrayList<Finding>()

        // 1) VarRef → 运行期名字/类型/维度
        for (u in uses.varUses.values) {
            checkVar(store, u)?.let { findings.add(it) }
        }

        // 1b) 名字字符串入口（CMP-77）：常量首参已二次解析成 VarRef 的，按真命名空间查绑定。
        //     只查绑定（名字在不在），不查类型/维度：名字串的静态类型与维度在构建期不可知
        //     （入口族里 GETVAR/GETVARS 才限定类型，SETVAR/VARSETEX 按目标类型分支）。
        for (u in uses.dynamicNameUses.values) {
            checkDynamicName(store, u)?.let { findings.add(it) }
        }

        // 2) KeywordIndexArg（静态字面量）→ 运行期关键字表 / 角色模板 / 用户变量拒绝
        var dynamicKeywordSites = 0
        for (k in uses.keywordUses.values) {
            val keyword = k.key.keyword
            if (keyword == null) {
                dynamicKeywordSites++
                continue
            }
            checkKeyword(store, k)?.let { findings.add(it) }
        }

        // 3) 运行期装配期失败（侧车私有 #DIM / 全局 #DIM）
        for (failure in session.privateDimFailures) {
            val slash = failure.indexOf('/')
            val colon = failure.indexOf(": ", slash + 1)
            val func = if (slash >= 0) failure.substring(0, slash) else ""
            val name = when {
                slash < 0 -> failure
                colon > slash -> failure.substring(slash + 1, colon)
                else -> failure.substring(slash + 1)
            }
            val detail = if (colon >= 0) failure.substring(colon + 2) else failure
            findings.add(Finding(FindingKind.PRIVATE_DIM_REJECTED, "", func, -1, name, detail, 1))
        }
        for (f in session.globalDimInstall.failures) {
            findings.add(
                Finding(
                    FindingKind.GLOBAL_DIM_REJECTED, f.file, "", f.line,
                    rawDimName(f.raw), f.message, 1,
                ),
            )
        }

        // 4) 编译期 level 2 告警（非红，单列）。告警原文形如
        //    `#DIM 解析失败（level 2）: … | #DIM X, 10 = …`，取竖线后的声明原文提名字。
        for (w in result.dimWarnings) {
            val raw = w.message.substringAfterLast('|').trim()
            findings.add(
                Finding(
                    FindingKind.COMPILE_DIM_WARNING, w.file, "", w.line,
                    rawDimName(raw), w.message, 1,
                ),
            )
        }

        findings.sortWith(compareBy({ it.kind.ordinal }, { it.file }, { it.line }, { it.name }, { it.detail }))

        return SymbolAuditReport(
            result = result,
            findings = findings,
            varUseSites = uses.varUses.size,
            varRefsSeen = uses.varRefsSeen,
            keywordUseSites = uses.keywordUses.size,
            keywordRefsSeen = uses.keywordRefsSeen,
            dynamicKeywordSites = dynamicKeywordSites,
            dynamicNameUseSites = uses.dynamicNameUses.size,
            dynamicVarNameSites = uses.dynamicVarNameSites,
            nameEntryParseFailures = uses.nameEntryParseFailures,
            statementsWalked = uses.statementsWalked,
            runtimeGlobals = store.allUserVars().size,
            runtimePrivateVars = session.privateDimDeclared,
            runtimeSystemVars = store.registry.all().size,
            elapsedMs = System.currentTimeMillis() - t0,
        )
    }

    /**
     * 真 `VarRef` 的绑定/类型/维度检查。
     *
     * CMP-78：`subKey`（`变量@函数`）以前只进 detail、不参与绑定。现在把 [VarUse.subKey] 传给
     * [VariableStore.resolveVarInfo]：私有/全局变量带 `@` 是原版解析期 CodeEE
     * （`IdentifierDictionary.cs:440-443` UsedAtForPrivVar / `:484-487` UsedAtForGlobalVar），
     * 解析成 null ⇒ 这里按硬差异报出；LOCAL/ARG 族的 `LOCAL@函数` 是合法的
     * （`IdentifierDictionary.cs:448-473`，按 subKey 建独立 token），照旧绑定。
     *
     * 注意 kind 仍是 `VAR_UNBOUND`：真正的类别（非法 `@` 用法）要新增 `FindingKind`，
     * 而 `Main.kt` 的 `kindLabel` 是全枚举穷举，属跨路接线面。
     */
    private fun checkVar(store: VariableStore, u: VarUse): Finding? {
        val info = store.resolveVarInfo(u.displayName, u.key.functionKey, u.subKey)
        if (info == null) {
            val subKey = u.subKey
            // 同一个名字**不带 subKey**能解析 ⇒ 差异来自非法 `@`，不是名字不存在。
            val withoutSubKey = if (subKey != null) store.resolveVarInfo(u.displayName, u.key.functionKey) else null
            val detail = if (subKey != null && withoutSubKey != null) {
                "带 @$subKey 的变量用法非法（私有/全局变量不得带 @；" +
                    "IdentifierDictionary.cs:440-443 / :484-487，原版解析期抛 CodeEE）" +
                    " operand=${u.operandType} indices=${u.indexCount} noArg=${u.isNoArg} const=${u.isConst}"
            } else {
                "operand=${u.operandType} indices=${u.indexCount} noArg=${u.isNoArg} const=${u.isConst}" +
                    (subKey?.let { " subKey=$it" } ?: "")
            }
            return Finding(
                FindingKind.VAR_UNBOUND, u.key.file, u.key.functionKey, u.key.line,
                u.displayName, detail,
                u.occurrences,
            )
        }
        if (info.operandType != u.operandType) {
            return Finding(
                FindingKind.VAR_TYPE_MISMATCH, u.key.file, u.key.functionKey, u.key.line,
                u.displayName,
                "解析期=${u.operandType} 运行期=${info.operandType}" +
                    "（运行期维度=${info.dimension} chara=${info.isCharacterData}）",
                u.occurrences,
            )
        }
        if (!u.isNoArg) {
            val expected = info.dimension + if (info.isCharacterData) 1 else 0
            if (u.indexCount != expected) {
                return Finding(
                    FindingKind.VAR_DIM_MISMATCH, u.key.file, u.key.functionKey, u.key.line,
                    u.displayName,
                    "解析期下标=${u.indexCount} 运行期维度=$expected" +
                        "（元素维=${info.dimension} chara=${info.isCharacterData}）",
                    u.occurrences,
                )
            }
        }
        return null
    }

    /**
     * 名字字符串入口（CMP-77）的绑定检查：只看名字在不在运行期命名空间里。
     *
     * 不做类型/维度断言 —— 名字串的类型在构建期不可知（`SETVAR`/`VARSETEX` 按目标类型
     * 分支、`GETVAR`/`GETVARS` 才限定类型），维度则是名字串里的下标个数推出来的，
     * 拿它跟真维度比会把 `CFLAG:TARGET:0` 这类角色变量误判成维度不符。
     *
     * detail 里带入口名与常量串原文：TSV/控制台都能直接回搜源码。
     */
    private fun checkDynamicName(store: VariableStore, u: DynamicNameUse): Finding? {
        val info = store.resolveVarInfo(u.displayName, u.key.functionKey, u.subKey)
        if (info != null) return null
        val subKey = u.subKey
        val withoutSubKey = if (subKey != null) store.resolveVarInfo(u.displayName, u.key.functionKey) else null
        val detail = if (subKey != null && withoutSubKey != null) {
            "${u.fnName}(\"${u.rawText}\")：带 @$subKey 的变量用法非法" +
                "（IdentifierDictionary.cs:440-443 / :484-487，原版解析期抛 CodeEE）"
        } else {
            "${u.fnName}(\"${u.rawText}\") 的名字在运行期命名空间里不存在" +
                "（运行期现解析，indices=${u.indexCount} noArg=${u.isNoArg}）"
        }
        return Finding(
            FindingKind.VAR_UNBOUND, u.key.file, u.key.functionKey, u.key.line,
            u.displayName, detail, u.occurrences,
        )
    }

    private fun checkKeyword(store: VariableStore, k: KeywordUse): Finding? {
        val keyword = k.key.keyword ?: return null
        val varName = k.key.varNameUpper
        if (varName == "RELATION") {
            if (k.key.dim != 1) {
                return Finding(
                    FindingKind.KEYWORD_UNBOUND, k.key.file, k.key.functionKey, k.key.line,
                    "$varName:$keyword",
                    "RELATION 的第 ${k.key.dim + 1} 个参数不能用字符串指定",
                    k.occurrences,
                )
            }
            if (store.templateByName(keyword) == null) {
                return Finding(
                    FindingKind.KEYWORD_UNBOUND, k.key.file, k.key.functionKey, k.key.line,
                    "$varName:$keyword",
                    "未定义角色名 \"$keyword\"（chara*.csv 中不存在）",
                    k.occurrences,
                )
            }
            return null
        }
        if (store.userVar(varName, k.key.functionKey) != null) {
            val table = store.userKeywordTable(varName, k.key.dim)
                ?: return Finding(
                    FindingKind.KEYWORD_USER_VAR, k.key.file, k.key.functionKey, k.key.line,
                    "$varName:$keyword",
                    "用户自定义变量 ${k.displayVarName} 没有 ERD 表" +
                        "（未找到 ${varName}.csv 或 ${varName}@${k.key.dim + 1}.csv）",
                    k.occurrences,
                )
            if (table[keyword] == null) {
                return Finding(
                    FindingKind.KEYWORD_UNBOUND, k.key.file, k.key.functionKey, k.key.line,
                    "$varName:$keyword",
                    "${varName}.csv 中未定义关键字 \"$keyword\"",
                    k.occurrences,
                )
            }
            return null
        }
        return try {
            store.registry.keywordIndex(varName, k.key.dim, keyword)
            null
        } catch (e: CodeEE) {
            Finding(
                FindingKind.KEYWORD_UNBOUND, k.key.file, k.key.functionKey, k.key.line,
                "$varName:$keyword",
                e.message ?: "关键字未绑定",
                k.occurrences,
            )
        }
    }

    // ------------------------------------------------------------------
    // 落盘后再装载对账（CMP-79）
    // ------------------------------------------------------------------

    /**
     * 编译 → 落盘（`program.erbc` + `program.meta`）→ 用**装载链**读回 → 与内存产物对账。
     *
     * 为什么需要这条路（CMP-79）：[audit] 的命名空间来自编译机内存里的
     * `result.bootMetadata` / `result.program`，全程不碰磁盘产物 —— 侧车编解码、
     * `ErbcReader` 解码、`ProgramLoader.internStatementArgs` 任何一处丢/折错，
     * 审计都看不见（2026-10-22 真机死循环正落在这段）。本函数把同一份语料落盘后再装载，
     * 拿**真装载结果**与内存产物逐字段对拍：
     *
     * 1. 侧车模型逐字段（labels / files / dataSections / functions / globalDims /
     *    privateDims / dimParseConfig）；
     * 2. 运行期命名空间三读数：`runtimeGlobals`（`store.allUserVars().size`）、
     *    `privateVars`（`GameSession.privateDimDeclared`）、`systemVars`
     *    （`store.registry.all().size`）；
     * 3. **使用点名字集合哈希**：两边各自用 [SymbolUseWalker] 走自己的**语句池**
     *    （不是编译期 AST，也不是 `SymbolUses` 的编译产物），收集变量名 / 关键字下标
     *    对 / 名字串入口名，排序后取 SHA-256。侧车少一条私有 `#DIM`、容器折掉一个实参、
     *    `internStatementArgs` 误并，都会在这条读数或三读数上露出来。
     * 4. `program.erbc` 的语句/指令条数与侧车头部 `program-sha256` 的配对。
     *
     * @param artifactDir 产物落盘目录；null = 临时目录（绿了自动删，红了保留路径供复现）
     * @param fileLimit 只编译前 N 个文件（调试/小语料档；正式产物必须全量）
     * @throws IllegalStateException 编译失败（没有可对账的内存产物）
     */
    fun roundTrip(
        corpusRoot: Path,
        gameRoot: Path,
        artifactDir: Path? = null,
        fileLimit: Int? = null,
    ): RoundTripReport {
        val t0 = System.currentTimeMillis()
        val root = corpusRoot.toAbsolutePath().normalize()
        val game = gameRoot.toAbsolutePath().normalize()
        val result = CorpusCompile.compile(root, fileLimit)
        val program = result.program
        if (program == null || !result.ok) {
            throw IllegalStateException(
                "编译失败（${result.failures.size} 条），没有可落盘的内存产物: " +
                    result.failures.take(3).joinToString(" | "),
            )
        }
        val temporary = artifactDir == null
        val dir = artifactDir ?: Files.createTempDirectory("erbcc-roundtrip-")
        val report = try {
            val write = ErbcArtifacts.write(
                dir, program, root, result.sourceFiles, result.stats, result.bootMetadata,
            )
            val metaPath = write.metaPath
                ?: throw IllegalStateException("落盘没有写出侧车（bootMetadata 为空）")
            compareProduct(
                memoryProgram = program,
                memoryMeta = result.bootMetadata,
                loadedProgram = ProgramLoader.loadFile(write.artifactPath),
                loadedMeta = BootMetadata.read(metaPath),
                game = game,
                artifactPath = write.artifactPath,
                metaPath = metaPath,
                artifactSha256 = write.artifact.sha256,
                t0 = t0,
            )
        } catch (e: Throwable) {
            // 异常路径也清临时目录：坏产物不该把临时盘留满
            if (temporary) deleteRecursively(dir)
            throw e
        }
        if (temporary && report.ok) deleteRecursively(dir)
        return report
    }

    /**
     * 对**已经落盘**的产物做同一套对账（红夹具/复现用）。
     *
     * 内存侧仍是「同一份语料重新编译一遍」（编译是确定性的，两次产物逐字节相同 —— 由
     * `BootMetaRoundTripTest` 钉住），装载侧读调用方给的路径。这样测试可以先落盘、
     * **手改侧车**、再跑对账，验证篡改一定红。
     *
     * @param artifactPath `program.erbc` 的真实路径
     * @param metaPath `program.meta` 的真实路径
     */
    fun verifyArtifacts(
        corpusRoot: Path,
        gameRoot: Path,
        artifactPath: Path,
        metaPath: Path,
        fileLimit: Int? = null,
    ): RoundTripReport {
        val t0 = System.currentTimeMillis()
        val root = corpusRoot.toAbsolutePath().normalize()
        val result = CorpusCompile.compile(root, fileLimit)
        val program = result.program
        if (program == null || !result.ok) {
            throw IllegalStateException(
                "编译失败（${result.failures.size} 条），没有可对账的内存产物: ${result.failures.take(3)}",
            )
        }
        val memoryMeta = result.bootMetadata
        val artifact = artifactPath.toAbsolutePath().normalize()
        val meta = metaPath.toAbsolutePath().normalize()
        return compareProduct(
            memoryProgram = program,
            memoryMeta = memoryMeta,
            loadedProgram = ProgramLoader.loadFile(artifact),
            loadedMeta = BootMetadata.read(meta),
            game = gameRoot.toAbsolutePath().normalize(),
            artifactPath = artifact,
            metaPath = meta,
            artifactSha256 = sha256HexOfFile(artifact),
            t0 = t0,
        )
    }

    // ------------------------------------------------------------------
    // 对账的内部实现
    // ------------------------------------------------------------------

    /**
     * 两条路的逐字段对拍（[roundTrip] 与 [verifyArtifacts] 共用的唯一实现）。
     *
     * 装载侧的任何异常（侧车格式坏、`GameSession` 的装配 require 不过）都收成一条不等的
     * 读数，不让 `RuntimeException` 冒泡成「命令崩了」—— 对账的语义是「红」，不是「炸」。
     */
    private fun compareProduct(
        memoryProgram: Program,
        memoryMeta: BootMetadata,
        loadedProgram: Program,
        loadedMeta: BootMetadata,
        game: Path,
        artifactPath: Path,
        metaPath: Path,
        artifactSha256: String,
        t0: Long,
    ): RoundTripReport {
        val checks = ArrayList<RoundTripCheck>()

        // 1) 容器面：语句池与指令流条数必须与原 Program 一致
        checks += RoundTripCheck(
            "program.statements",
            memoryProgram.statementCount.toString(),
            loadedProgram.statementCount.toString(),
            memoryProgram.statementCount == loadedProgram.statementCount,
        )
        checks += RoundTripCheck(
            "program.instructions",
            memoryProgram.instructionCount.toString(),
            loadedProgram.instructionCount.toString(),
            memoryProgram.instructionCount == loadedProgram.instructionCount,
        )

        // 2) 侧车模型逐字段
        checks += metaFieldChecks(memoryMeta, loadedMeta, artifactSha256)

        // 3) 运行期命名空间三读数 + 使用点名字集合
        val memory = readNamespace(memoryProgram, memoryMeta, game)
        val loaded = runCatching { readNamespace(loadedProgram, loadedMeta, game) }
        val loadedReadings = loaded.getOrNull()
        if (loadedReadings == null) {
            val e = loaded.exceptionOrNull()!!
            checks += RoundTripCheck(
                "runtime.session", "装配成功",
                "装配失败: ${e::class.simpleName}: ${e.message}", false,
            )
        } else {
            checks += intCheck("runtime.globals", memory.globals, loadedReadings.globals)
            checks += intCheck("runtime.private-vars", memory.privateVars, loadedReadings.privateVars)
            checks += intCheck("runtime.system-vars", memory.systemVars, loadedReadings.systemVars)
            checks += failureCheck(
                "runtime.private-dim-failures",
                memory.privateDimFailures, loadedReadings.privateDimFailures,
            )
            checks += failureCheck(
                "runtime.global-dim-failures",
                memory.globalDimFailures, loadedReadings.globalDimFailures,
            )
            checks += nameSetCheck(memory.names, loadedReadings.names)
        }

        return RoundTripReport(
            artifactPath = artifactPath,
            metaPath = metaPath,
            checks = checks,
            nameSetSize = memory.names.size,
            nameSetHash = memory.nameHash,
            elapsedMs = System.currentTimeMillis() - t0,
        )
    }

    /**
     * 侧车模型逐字段对拍。
     *
     * `engine` / `program-sha256` 两个头部字段**不与内存模型比**：它们的值由落盘端
     * （`BootMetaWriter`）在写的时候才确定（模型里 `programSha256` 恒 null、`engineTag`
     * 是引擎默认标签），所以这里改成「装载值必须等于产物摘要」的配对判据。
     */
    private fun metaFieldChecks(
        memory: BootMetadata,
        loaded: BootMetadata,
        artifactSha256: String,
    ): List<RoundTripCheck> {
        val out = ArrayList<RoundTripCheck>(9)

        out += RoundTripCheck(
            "meta.functions",
            "${memory.functions.size} 条",
            "${loaded.functions.size} 条",
            memory.functions == loaded.functions,
            firstFunctionDiff(memory.functions, loaded.functions),
        )

        out += RoundTripCheck(
            "meta.labels",
            "${memory.labels.size} 函数 / ${memory.labels.values.sumOf { it.size }} 标签",
            "${loaded.labels.size} 函数 / ${loaded.labels.values.sumOf { it.size }} 标签",
            memory.labels == loaded.labels,
            firstMapDiff(memory.labels, loaded.labels),
        )

        out += RoundTripCheck(
            "meta.files",
            "${memory.fileByKey.size} 条",
            "${loaded.fileByKey.size} 条",
            memory.fileByKey == loaded.fileByKey,
            firstMapDiff(memory.fileByKey, loaded.fileByKey),
        )

        out += RoundTripCheck(
            "meta.data-sections",
            "${memory.dataSections.size} 节",
            "${loaded.dataSections.size} 节",
            memory.dataSections == loaded.dataSections,
            firstMapDiff(memory.dataSections, loaded.dataSections),
        )

        out += RoundTripCheck(
            "meta.global-dims",
            "${memory.globalDims.size} 条",
            "${loaded.globalDims.size} 条",
            memory.globalDims == loaded.globalDims,
            firstListDiff(memory.globalDims, loaded.globalDims),
        )

        // `DimSpec` 是普通 class（含 IntArray，data class 的 equals 也不可比数组内容）⇒ 逐字段比
        val memPriv = memory.privateDims.map(::privateDimKey)
        val loadedPriv = loaded.privateDims.map(::privateDimKey)
        out += RoundTripCheck(
            "meta.private-dims",
            "${memPriv.size} 条",
            "${loadedPriv.size} 条",
            memPriv == loadedPriv,
            firstListDiff(memPriv, loadedPriv),
        )

        // `DimParseConfig` 带 `functionFolder` 回调（不可序列化、两侧恒不同）⇒ 只比四个数据字段
        val mc = memory.dimParseConfig
        val lc = loaded.dimParseConfig
        val configEqual = mc.systemSaveInBinary == lc.systemSaveInBinary &&
            mc.macros == lc.macros &&
            mc.intConstants == lc.intConstants &&
            mc.strConstants == lc.strConstants
        out += RoundTripCheck(
            "meta.dim-config",
            "bin=${mc.systemSaveInBinary} macro=${mc.macros.size} const=${mc.intConstants.size} " +
                "strConst=${mc.strConstants.size}",
            "bin=${lc.systemSaveInBinary} macro=${lc.macros.size} const=${lc.intConstants.size} " +
                "strConst=${lc.strConstants.size}",
            configEqual,
            if (configEqual) "" else "宏/常量/字符串常量三表之一不等",
        )

        // 配对判据：侧车头部摘要必须等于这份 .erbc 的 SHA-256
        out += RoundTripCheck(
            "meta.program-sha256",
            artifactSha256,
            loaded.programSha256 ?: "null",
            loaded.programSha256 == artifactSha256,
            "侧车与容器必须成对（头部摘要 = program.erbc 的 SHA-256）",
        )
        return out
    }

    /** 一次运行期命名空间的读数（三计数 + 使用点名字集合 + 装配失败清单）。 */
    private class NamespaceReadings(
        val globals: Int,
        val privateVars: Int,
        val systemVars: Int,
        val names: Set<String>,
        val privateDimFailures: List<String>,
        val globalDimFailures: List<String>,
    ) {
        val nameHash: String = sha256OfNames(names)
    }

    /**
     * 装一次真会话并读命名空间。
     *
     * 使用点名字集合**从 `program` 的语句池走起**（[SymbolUseWalker]），而不是从编译期
     * `SymbolUses`：要测的正是「容器 + `internStatementArgs` 之后还剩下什么」，拿编译期 AST
     * 就成了自己跟自己比。
     */
    private fun readNamespace(program: Program, metadata: BootMetadata, game: Path): NamespaceReadings {
        val session = GameSession(
            gameRoot = game,
            program = program,
            metadata = metadata,
            // 与 erbcc 侧车同一把尺（见 [audit] 的注释）
            consoleWidth = CorpusCompile.FOLD_CONSOLE_WIDTH_CELLS,
            systemOps = NoSaveSystemOps,
            randSeed = 0L,
        )
        try {
            val uses = SymbolUses()
            SymbolUseWalker.walk("", program.statements(), "", uses)
            val names = LinkedHashSet<String>(
                uses.varUses.size + uses.keywordUses.size + uses.dynamicNameUses.size,
            )
            for (k in uses.varUses.keys) names.add(k.nameUpper)
            for (k in uses.keywordUses.keys) {
                names.add(k.varNameUpper + ":" + (k.keyword?.uppercase(Locale.ROOT) ?: "*"))
            }
            for (k in uses.dynamicNameUses.keys) names.add(k.nameUpper)
            return NamespaceReadings(
                globals = session.store.allUserVars().size,
                privateVars = session.privateDimDeclared,
                systemVars = session.store.registry.all().size,
                names = names,
                privateDimFailures = session.privateDimFailures.sorted(),
                globalDimFailures = session.globalDimInstall.failures
                    .map { "${it.file}:${it.line} ${it.message}" }
                    .sorted(),
            )
        } finally {
            session.close()
        }
    }

    private fun intCheck(name: String, a: Int, b: Int): RoundTripCheck =
        RoundTripCheck(name, a.toString(), b.toString(), a == b)

    private fun failureCheck(name: String, a: List<String>, b: List<String>): RoundTripCheck =
        RoundTripCheck(
            name,
            "${a.size} 条" + (if (a.isEmpty()) "" else "（${a.first().take(80)}）"),
            "${b.size} 条" + (if (b.isEmpty()) "" else "（${b.first().take(80)}）"),
            a == b,
            if (a == b) "" else "缺 ${(a - b.toSet()).take(3)} 多 ${(b - a.toSet()).take(3)}",
        )

    /** 使用点名字集合：条数 + 排序后的 SHA-256，另带两侧差集样例。 */
    private fun nameSetCheck(a: Set<String>, b: Set<String>): RoundTripCheck {
        val ha = sha256OfNames(a)
        val hb = sha256OfNames(b)
        val onlyA = (a - b).sorted()
        val onlyB = (b - a).sorted()
        val detail = if (onlyA.isEmpty() && onlyB.isEmpty()) {
            ""
        } else {
            "只在内存: ${onlyA.take(5)}；只在装载: ${onlyB.take(5)}"
        }
        return RoundTripCheck(
            "usages.names",
            "${a.size} 个名字 sha256=${ha.take(16)}",
            "${b.size} 个名字 sha256=${hb.take(16)}",
            ha == hb,
            detail,
        )
    }

    private fun privateDimKey(p: BootMetadata.PrivateDim): String {
        val s = p.spec
        return "${p.functionKey}/${s.name}|str=${s.isStr}|const=${s.isConst}|ref=${s.isRef}" +
            "|global=${s.isGlobal}|savedata=${s.isSavedata}|chara=${s.isCharaData}" +
            "|static=${s.isStatic}|private=${s.isPrivate}|dims=${s.dims.joinToString(",")}" +
            "|defInt=${s.defaultsInt?.joinToString(",")}|defStr=${s.defaultsStr?.joinToString("\u0001")}"
    }

    private fun firstFunctionDiff(a: List<FuncMeta>, b: List<FuncMeta>): String {
        if (a == b) return ""
        val i = (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] }
        return when {
            i != null -> "首差 #$i: 内存=${a[i]} 装载=${b[i]}"
            else -> "条数不同：内存 ${a.size} / 装载 ${b.size}"
        }
    }

    private fun firstMapDiff(a: Map<*, *>, b: Map<*, *>): String {
        if (a == b) return ""
        val missing = a.keys - b.keys
        val extra = b.keys - a.keys
        val changed = a.keys.firstOrNull { it in b && a[it] != b[it] }
        return buildString {
            if (missing.isNotEmpty()) append("缺 ${missing.take(3)} ")
            if (extra.isNotEmpty()) append("多 ${extra.take(3)} ")
            if (changed != null) append("改 $changed: ${a[changed]} → ${b[changed]}")
        }.trim()
    }

    private fun <T> firstListDiff(a: List<T>, b: List<T>): String {
        if (a == b) return ""
        val i = (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] }
        return when {
            i != null -> "首差 #$i: 内存=${a[i]} 装载=${b[i]}"
            else -> "条数不同：内存 ${a.size} / 装载 ${b.size}"
        }
    }

    /** 流式算文件 SHA-256（小写十六进制）：全量产物 330 MB，不落第二份字节。 */
    private fun sha256HexOfFile(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 删一棵树（只用于本函数自建的临时产物目录）。 */
    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    runCatching { Files.deleteIfExists(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.deleteIfExists(dir) }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /** `#DIM ...` 原文 → 变量名（只用于报告可读性；解析失败时尽量给出可搜的片段）。 */
    private fun rawDimName(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("#")) s = s.substring(1).trim()
        val kw = s.takeWhile { !it.isWhitespace() }.uppercase(Locale.ROOT)
        if (kw == "DIM" || kw == "DIMS") s = s.substring(kw.length).trim()
        // 修饰符可叠加（`#DIM CONST SAVEDATA X`），逐轮剥到不动点；单轮会把
        // `SAVEDATA X` 当名字报出去，报告里搜不到源码。
        var changed = true
        while (changed) {
            changed = false
            for (mod in DIM_MODIFIERS) {
                if (s.length > mod.length && s.startsWith(mod) && s[mod.length].isWhitespace()) {
                    s = s.substring(mod.length).trim()
                    changed = true
                }
            }
        }
        return s.takeWhile { it != ',' && it != '=' && it != ';' && !it.isWhitespace() }
    }

    private val DIM_MODIFIERS = listOf("CONST", "REF", "DYNAMIC", "STATIC", "GLOBAL", "SAVEDATA", "CHARADATA")
}

/**
 * 使用点名字集合的哈希：排序后按 LF 连接取 SHA-256。
 *
 * 集合口径 = 变量名（大写）+ 关键字下标对 `变量名:关键字`（动态关键字记 `*`）+ 名字串入口
 * 的变量名。排序保证与收集顺序/实例身份无关，只对「名字集合」本身敏感。
 */
private fun sha256OfNames(names: Set<String>): String =
    ErbcArtifacts.sha256Hex(names.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
