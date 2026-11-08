package com.eratw.port.erbcc.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.compile.CallDefaultsCollector
import com.eratw.port.core.compile.CompileException
import com.eratw.port.core.compile.DimSpecLayout
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.compile.GlobalNameLayout
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.err.erbDisplayLine
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LangKey
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.stmt.DataListStmt
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.EndBlockStmt
import com.eratw.port.core.stmt.CallStmt
import com.eratw.port.core.stmt.JumpStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.ParamDefault
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.vars.DimFoldSnapshot
import com.eratw.port.core.vars.DimFunctionFolders
import com.eratw.port.core.vars.DimVarSizes
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.vars.withPureStaticDimFolders
import com.eratw.port.erbcc.LabelDef
import com.eratw.port.erbcc.LoadWarning
import com.eratw.port.erbcc.LabelTable
import com.eratw.port.erbcc.Symbols
import com.erbcc.bridge.DimDrop
import com.erbcc.bridge.buildExprCallTable
import com.erbcc.bridge.buildFuncDims
import com.erbcc.bridge.sizeConsts
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/**
 * 第 3 层（双路 E5-2696v4 出包加速）：文件级并行预取逻辑行。
 *
 * [LineParser] 与 [CompileResolver] 都带跨文件可变状态（warnings / labelParamRefs /
 * curVars 私有表 / fileAlias 缓存），A13 口径也明确要求「整轮共用一个」，所以**不能**
 * 给每个文件发一个解析器。这里只把「读盘 + [LogicalLineReader] 词法切逻辑行」放进
 * 线程池；产出的 [LineEvent] 序列按原文件序、原事件序交回串行解析，语义与单线程
 * 逐事件等价（词法错误抛错后继续读下一行，不是终止文件）。
 *
 * 验收硬指标：`program.erbc` 必须与并行前的 golden 逐字节相同
 * （00d70bfefec8db6b… / 385008562 B），否则关掉 `eratw.erbcc.parallel` 回退。
 */
private sealed interface LineEvent {
    data class Ok(val line: LogicalLine) : LineEvent
    data class Err(val error: LexError) : LineEvent
}

private data class FilePrefetch(
    val rel: String,
    val events: List<LineEvent>,
    val bytesSize: Int,
    /** 前 8KB 内 NUL 的偏移；-1 = 正常文件。 */
    val nulAt: Int,
    val readError: String?,
)

/** 按原事件序回放预取结果；词法错误仍以抛 [LexError] 的形式交回调用方的 catch。 */
private class PrefetchedLineReader(private val events: List<LineEvent>) {
    private var idx = 0

    fun readLogicalLine(): LogicalLine? {
        while (idx < events.size) {
            when (val ev = events[idx++]) {
                is LineEvent.Ok -> return ev.line
                is LineEvent.Err -> throw ev.error
            }
        }
        return null
    }

    /** 预处理未闭合区间的检查已在并行阶段随 reader.fileEnd() 做过，此处无需重做。 */
    fun fileEnd() = Unit
}

private fun prefetchFile(
    path: Path,
    root: Path,
    lexerConfig: LexerConfig,
    renameDic: Map<String, String>?,
    macroNames: Set<String>,
): FilePrefetch {
    val rel = runCatching { root.relativize(path).toString().replace('\\', '/') }
        .getOrElse { path.toString() }
    val bytes = try {
        Files.readAllBytes(path)
    } catch (e: Exception) {
        return FilePrefetch(rel, emptyList(), 0, -1, "读取失败: ${e::class.simpleName}: ${e.message}")
    }
    val nulAt = bytes.indexOf(0.toByte())
    if (nulAt in 0 until minOf(bytes.size, 8192)) {
        return FilePrefetch(rel, emptyList(), bytes.size, nulAt, null)
    }
    val reader = LogicalLineReader(
        LogicalLineReader.readAllLines(bytes),
        rel,
        config = lexerConfig,
        renameDic = renameDic,
        macroNames = macroNames,
    )
    val events = ArrayList<LineEvent>()
    while (true) {
        val line: LogicalLine?
        try {
            line = reader.readLogicalLine()
        } catch (e: LexError) {
            events.add(LineEvent.Err(e))
            continue
        }
        if (line == null) break
        events.add(LineEvent.Ok(line))
    }
    try { reader.fileEnd() } catch (_: Exception) {}
    return FilePrefetch(rel, events, bytes.size, -1, null)
}

/**
 * 一条编译/解析失败（定位到 `文件:行号`，CLI 退出码与容错清单判据用）。
 *
 * **行号坐标系**：[line] = **内部 0 基**行下标（`CompileException.line` / `LexError.lineNo` /
 * `Statement.line` / 切片头行 `LabelDef.lineNo` 同基）。它是**键面**不是文案 ——
 * `Main.kt` 的容错清单按 `"文件:行"` 比对（`devtools/erbc-degraded-allowlist.tsv` 的表头也写明
 * 「0 基，与 CompileFailure 口径一致」），所以这一列的数值**不许**跟着展示口径改；
 * 给人看的 1 基走 [displayLine]（唯一出口 [erbDisplayLine]，与 `core/err/CodeEE.kt` 同一条链）。
 *
 * [lineSource] 是 A14（lane compreadout，2026-11-05）加的来源标注：`-1` 与「函数级错误回退成
 * `@定义` 行」与「异常自带的语句行」在数值面上此前完全不可分辨，读表人会把「函数重名」这类
 * 函数级失败当成定义行本身出了错。数值保持原样（键面不动），来源单独成列。
 */
class CompileFailure(
    val file: String,
    val line: Int,
    val message: String,
    /** 行号来源（见 [FailLineSource]）；空串 = 该构造点尚未标注（旧调用兼容）。 */
    val lineSource: String = FailLineSource.UNSET,
) {
    /** 1 基展示行号（[line] < 0 时原样返回负数）；不参与键面比对，只给人读。 */
    val displayLine: Int get() = erbDisplayLine(line)

    override fun toString(): String = "$file:$line $message"
}

/** [CompileFailure.lineSource] 的取值集（字符串而不是枚举：容错清单与 manifest 的字节口径不动）。 */
object FailLineSource {
    /** 尚未标注的旧构造点。 */
    const val UNSET = ""

    /** `CompileException.line >= 0`：0 基语句行，归因到抛异常那条语句。 */
    const val COMPILE_EXCEPTION = "CompileException.line(0基)"

    /** `CompileException.line == -1`（函数级错误，如重名）⇒ [CompileFailure.line] 回退成 `@定义` 行，**不是**出错的那一行。 */
    const val FUNCTION_LEVEL_FALLBACK = "函数级(-1)→@定义行回退(0基)"

    /** 非 CompileException 的编译内部错误：异常不带位置 ⇒ 行号是 `@定义` 行，纯上下文。 */
    const val CRASH_NO_STRUCTURED_LINE = "无结构化行号→@定义行(0基)"

    /** `LexError.lineNo`（0 基）。 */
    const val LEX_ERROR = "LexError.lineNo(0基)"

    /** `LogicalLine.lineNo`（0 基；解析异常没带位置时的回落）。 */
    const val LOGICAL_LINE = "当前逻辑行(0基)"

    /** 切片级毒化记录：行号是 `@函数` 头行，粒度到切片不到行。 */
    const val SLICE_HEAD = "切片头行(0基)"

    /** 文件读取失败：IO 异常没有行概念。 */
    const val FILE_READ = "读取失败(无源行)"

    /** 事件入口合成 / Program 冻结失败：合成体没有源行，-1 是口径不是漏读。 */
    const val NO_SOURCE = "无源行(合成/冻结面)"

    /** `Statement.line`（0 基；`#DIM` 告警一族的行号来源）。 */
    const val STATEMENT = "Statement.line(0基)"

    /** `StmtParseException.line` / `ExprParseException.line`（0 基，异常自带）。 */
    const val PARSE_EXCEPTION = "解析异常.line(0基)"

    /**
     * 解析臂的行号来源判定：异常自带 0 基位置字段就标 [PARSE_EXCEPTION] / [LEX_ERROR]，
     * 否则是「回落成当前逻辑行的行号」（[LOGICAL_LINE]）——两者数值常常相同（同一行的异常），
     * 但**可信度不同**：前者是异常声明的，后者是调用方替它猜的。
     *
     * 负行号口径与 core 侧 `CorpusCompileValidator.structuredLineOf` 同条（lane compreadout 收口）：
     * `StmtParseException` / `ExprParseException` 的 `line < 0` 是「异常没带位置」，不是
     * 「有结构化行号且值为 -1」—— 否则读表人会把回落行当成本行。调用点 :505-509 据此翻转成
     * [LOGICAL_LINE]。
     */
    fun of(e: Throwable): String = when (e) {
        is LexError -> if (e.lineNo >= 0) LEX_ERROR else NO_SOURCE
        is com.eratw.port.core.stmt.StmtParseException -> if (e.line >= 0) PARSE_EXCEPTION else NO_SOURCE
        is com.eratw.port.core.expr.ExprParseException -> if (e.line >= 0) PARSE_EXCEPTION else NO_SOURCE
        is CodeEE -> if (e.line >= 0) "CodeEE.line(0基)" else NO_SOURCE
        is CompileException -> if (e.line >= 0) COMPILE_EXCEPTION else FUNCTION_LEVEL_FALLBACK
        else -> CRASH_NO_STRUCTURED_LINE
    }
}

/** 编译规模读数（写 manifest 的 program 段）。 */
class CompileStats(
    val corpusFiles: Int,
    val slices: Int,
    val compiled: Int,
    val compileFailures: Int,
    val parseFailures: Int,
    val eventEntries: Int,
    val instructions: Int,
    val statements: Int,
    val functions: Int,
    val strings: Int,
    val constants: Int,
    val expressions: Int,
    val globalSlots: Int,
    val entryFunction: Int,
    /**
     * 生效的文件上限：`--limit N` 且 N 真的小于语料文件数时非 null，全量档为 null。
     *
     * CMP-65：`--limit` 产物原先与全量产物在 manifest 里**不可区分**（`source-files` 仍按
     * 全量登记）⇒ 受限产物可被当全量打包。manifest 的 `build-mode` / `file-limit` 两行
     * 由这个字段单源派生（`ErbcArtifacts.manifestText`）。
     */
    val fileLimit: Int? = null,
    /**
     * 前 8KB 含 NUL、被当二进制跳过的源文件数（CMP-64）。
     *
     * 这些文件**仍进** `sourceFiles`（清单口径 = 登记但不解析，同 `CompileErbcTest`），
     * 但不产生任何切片；计入这里只为让「产物里缺了它」这件事有读数（旧实现静默
     * `continue`，既不留痕也不计数）。
     */
    val binarySkipped: Int = 0,
)

/** 全量语料编译产物。 */
class CompileResult(
    val program: Program?,
    val symbolTable: LabelTable,
    /** 语料源文件清单（相对路径 + 字节数 + SHA-256；manifest 登记用，按路径排序）。 */
    val sourceFiles: List<HashEntry>,
    val failures: List<CompileFailure>,
    val notes: List<String>,
    /**
     * `#DIM`/`#DIMS` 解析失败的 level 2 告警（不进 [failures]，不影响 [ok] 与退出码）。
     *
     * 对齐原版装载口径：全局 `#DIM` 解析失败 `ParserMediator.Warn(…, 2)` 且**不中断装载**
     * （spec: `ErhLoader.cs:328-331`；常量重试穷尽的行同 level，`:324-325`）；函数内私有
     * `#DIM` 解析异常同样 `Warn(…, 2)`（spec: `LogicalLineParser.cs:303-307`，重名
     * `:285-288`）。此前这些失败被 `runCatching { … }.getOrNull()` 静默吞掉、不计数不告警
     * ——坏声明悄悄消失，症状是「某常量/某用户变量在 `.erbc` 装载路径下失踪」。
     * 只进 CLI stderr 与本清单，不写 manifest / 侧车（产物字节保持不变）。
     */
    val dimWarnings: List<CompileFailure>,
    val stats: CompileStats,
    /**
     * 装载期元数据侧车（`.erbc` v1 装不下的那部分：函数元数据 / `#DIM` 原文与解析结果 /
     * 标签表 / 数据节 / 宏与常量表 / 函数键→源文件）。
     *
     * **只由编译期的只读产物派生**：收集它不改变任何指令、任何池、任何统计读数
     * （[stats] 与不收集时逐字段相同；`CompileErbcTest` 的对拍用例锁死 `program.erbc`
     * 与 `manifest.txt` 的字节）。
     */
    val bootMetadata: BootMetadata,
    /**
     * 装载/解析期告警（原版 `ParserMediator.Warn` 族的移植落点）。
     *
     * **不进 [failures]、不影响 [ok] 与 CLI 退出码**——原版这些告警只打控制台
     * （`ParserMediator.Warn(…, 1)` 不置 `noError`，ErbLoader.cs:441-451），装载照样继续。
     * 但移植侧此前一条通道都没有，装载看起来「全成功」；本清单把它们逐条透出。
     *
     * 目前接入的三段（段序固定，段内各自保序）：
     * 1. [LabelTable.duplicateFunctionWarnings] —— 同名非事件函数重复定义
     *    （spec: ErbLoader.cs:441-451）；
     * 2. [CorpusScan.warnings] —— 预扫描自身的降级留痕（预处理区间未闭合等）；
     * 3. 语句层 [LineParser.warnings] —— 参数个数超限等（spec: ArgumentBuilder.cs:30-40，
     *    触发点如 ArgumentParser.cs:1157）。
     *
     * **与原版的段内交错差异**：原版是「逐文件装载（重复定义告警与预处理告警随文件序交错）
     * → `ParseScript` 统一建参数（ErbLoader.cs:786-800）」，本仓是「符号表 → 预扫描 →
     * 语句」三遍，故跨段/跨文件的相对先后可能不同（TODO(verify)：要逐字对拍全量告警流
     * 需把三遍合并成一遍装载）。
     *
     * **M11-E warnmeta**：本清单经 [bootMetadata] 的 `load-warnings` 段随 `program.meta`
     * 落盘（逐条直存 file/line/message，保序、不去重、不重排），设备侧装载链可用
     * `BootMetadata.loadWarnings` / `loadWarningsOrEmpty()` 读回。[dimWarnings]
     * （level 2 的 `#DIM` 解析失败）是**另一条通道**，不在本清单、不进侧车——口径不变。
     */
    val warnings: List<LoadWarning> = emptyList(),
    /**
     * 本次编译实际喂给 [ErbProgramCompiler] 的**生产布局实例**（只读审计面）。
     *
     * 布局不参与发射（契约 C1：具名变量只进 `VariableStore`，编译器只读
     * `refParamNamesOf`），所以它不出现在 `.erbc` / manifest 里；保留实例只为让
     * 「`#DIM` 尺寸在布局面折成了什么」可被对拍（[com.eratw.port.core.compile.DimSpecLayout]
     * 的 `beginFunction` + `currentSlots` 即折叠后的局部槽表）。
     * 内存代价 = 一份 `LabelDef.dimSpecs` 的映射（与 [bootMetadata] 的 `privateDims`
     * 同量级），不复制任何 Program 数据。
     */
    val dimLayout: com.eratw.port.core.compile.DimSpecLayout? = null,
) {
    val ok: Boolean get() = program != null && failures.isEmpty()
}

/**
 * 全量语料 → 单一 [Program] 的编译管线（CLI 侧；**不落盘**，落盘归 `ErbcArtifacts`）。
 *
 * 链路与 core-vm 的两个测试装置同口径，差异只在**产出物**：
 * - 符号表装载序 / 标签规则：erbcc `LabelTable.build`（spec 见 specs/loader.md 附录 D）；
 * - 语句切片与作用域：`CorpusScan` + [CompileResolver] + `LineParser`
 *   （口径 = 测试装置 `CorpusValidator.prescan` / `ScopedResolver`，A13 起两侧同一拓扑）；
 * - 编译：core-vm `ErbProgramCompiler`（契约 C1/C3：控制流留字节码、数据面进语句池）；
 * - 事件入口：同名多定义合成「依序 _CALL」入口（spec: CalledFunction.cs:228-243）；
 * - `build()` 撞未定义 CALL 目标：补 0 参空体桩后重试（原版仅执行到该行才报错，
 *   见 core-vm 测试装置 RealCorpusCompile 的同名逻辑）。
 *
 * 确定性：文件清单排序 + 符号表装载序（ja-JP Collator，ADR-006）+ 编译单元按 defIndex 排序
 * ⇒ 同一语料两次编译的 Program 逐字节相同（再经 ErbcWriter 即产物逐字节相同）。
 */
object CorpusCompile {

    /**
     * `GETLINESTR` 在装载期折叠用的控制台宽度（半角格）。
     *
     * 取 PC 基准的 `Config.DrawableWidth`：1797px / 9px 半角步进 = 199 格
     * （`pc-golden-root.txt:689`，`drawable/ConsoleScreen.GOLDEN_DRAWABLE_WIDTH` 同值）。
     * **不能用 `GameSession.DEFAULT_CONSOLE_WIDTH`**：那是像素口径的 1800（基准窗口宽），
     * 直接当格数会把 `行文字数` 折成 900 字符（语料 `カラム機能(div).ERB:21`），
     * 设备上 199 格左右的真实宽度装不下。
     *
     * 侧车里的私有 `#DIM` 初值在编译期固定，设备实际宽度不同时该布局变量不跟随 ——
     * 这是 `.erbc` 预编译的既定取舍（`SymbolLinkAudit` 的装配点也传同一个值，
     * 保证审计与产物同尺）。
     */
    const val FOLD_CONSOLE_WIDTH_CELLS = 199

    /** 混进语料根的金标采集副本（`fixtures/golden/` 同源，非原版语料文件）。 */
    private fun isCollectionCopy(path: Path): Boolean = path.name.startsWith("GOLDEN_", ignoreCase = true)

    /**
     * 语料 ERB/ERH 清单：递归、按规范化绝对路径字符串排序、排除金标采集副本。
     * 口径 = 测试装置 `CorpusValidator.erbFiles`（排除 GOLDEN_* 的理由见该处注释）；
     * 排序键改用路径字符串（跨平台确定，文件系统遍历顺序不参与）。
     */
    fun listCorpusFiles(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { s ->
            // 注意：java.util.stream.Stream 只有 filter/map/sorted(compareBy)，没有
            // Kotlin 的 filterNot/sortedBy（那是 Sequence/Iterable 扩展）——先 toList
            // 再走 Kotlin 惯用法，避免误连 Stream API。
            s.filter { Files.isRegularFile(it) }
                .filter { it.name.endsWith(".erb", ignoreCase = true) || it.name.endsWith(".erh", ignoreCase = true) }
                .filter { !isCollectionCopy(it) }
                .map { it.toAbsolutePath().normalize() }
                .toList()
                .sortedBy { it.toString() }
        }
    }

    private class Slice(
        val rel: String,
        val keyUpper: String,
        val line: Int,
        val params: List<String>,
        /**
         * 形参项原文（含 `:n` 下标）：`@F(ARG, ARG:1)` → [ARG, ARG:1]
         * （`FuncLabelStmt.paramRefs`）。[params] 是剥掉下标的名字，只够算 arity/类型桶；
         * 逐位绑定要的是「哪个变量元素的引用」，裸名字区分不了 `ARG` 与 `ARG:1`
         * （spec: ProcessState.cs:435-452 逐位写 `TopLabel.Arg[i]`）。
         */
        val paramRefs: List<String>,
        /**
         * 逐位缺省实参默认值（`@F(A, B = 2)` 的 B 位）—— 编译期补位用。
         *
         * 只在编译期消费：`.erbc` 不落盘（与 `paramRefs` 同一条取舍），
         * 因为默认值已经在**字节码的实参表**里被补成具体常量了。
         */
        val paramDefaults: List<ParamDefault?> = emptyList(),
        val stmts: List<Statement>,
        /** 本切片内的 `#DIM/#DIMS/#DIMF` 原文（函数内声明 → 私有用户变量）。 */
        val dimLines: List<String>,
        /** 本切片内有解析失败行 → 结构不可信，不进正常编译（strict 空体 / compat 入口 THROW）。 */
        val poisoned: Boolean,
    )

    private class Unit(val sigKey: String, val def: LabelDef, val slice: Slice?)

    /**
     * 编译整个语料目录。
     *
     * @param fileLimit 只切片前 N 个文件（调试用；报告会注明，正式产物必须全量）。
     * @param uses 非空时顺带采集全量符号使用点（[SymbolUseWalker]）。只读遍历解析结果，
     *   不改任何指令 / 池 / 统计读数；装载期 link audit 用它和运行期命名空间做差。
     * @param degradedStub **兼容编译档**：解析失败的函数切片降级成「入口 THROW」空桩
     *   （原版是「装载期标错误行、执行到才抛」，这里提前到函数入口，调用时立刻可见、
     *   可被 TRYC 捕获）。缺省 false = 严格档，失败切片只留空体桩且调用方必须拒落盘
     *   （`cmdCompile` 的严格档判据）。
     */
    fun compile(
        corpusRoot: Path,
        fileLimit: Int? = null,
        uses: SymbolUses? = null,
        degradedStub: Boolean = false,
    ): CompileResult {
        val notes = ArrayList<String>()
        val failures = ArrayList<CompileFailure>()
        val parseFailures = ArrayList<CompileFailure>()
        /** 函数外（全局）`#DIM/#DIMS` 原文：装载期交 SystemVariableBootstrap.install。 */
        val globalDimDecls = ArrayList<BootMetadata.RawDim>()

        val root = corpusRoot.toAbsolutePath().normalize()
        val allFiles = listCorpusFiles(root)
        // 生效的受限档：`--limit N` 且 N 真的小于语料文件数（N≥总数时等于全量，不算受限）。
        // CMP-65：这个读数单源给 manifest 的 `build-mode` / `file-limit` 两行，让受限产物
        // 与全量产物在清单里可区分（旧实现两档清单除了 slices 读数外无法分辨，
        // `source-files` 仍按全量登记 ⇒ 受限产物可被当全量打包）。
        val effectiveLimit = if (fileLimit != null && fileLimit in 1 until allFiles.size) fileLimit else null
        val files = if (effectiveLimit != null) {
            notes.add("降级模式：只切片前 $effectiveLimit/${allFiles.size} 个文件")
            allFiles.take(effectiveLimit)
        } else {
            allFiles
        }

        // 1) 全量符号表（装载序 = LabelTable.loadOrder；含 ERH 宏/常量）
        //    `#DIM CONST` 求值留在本模块：注入 `::sizeConsts`（core 侧 `build` 无缺省值，ADR-010）
        val symbolTable = LabelTable.build(root, sizeConsts = ::sizeConsts)
        val allDefs = symbolTable.nameKeys.flatMap { symbolTable.defsOf(it) }.sortedBy { it.defIndex }

        // 装载期告警（非致命；段序见 [CompileResult.warnings]）
        val loadWarnings = ArrayList<LoadWarning>()
        // 1a) 同名非事件函数重复定义：原版在 `AddLabel` 之后立刻打（spec: ErbLoader.cs:441-451），
        //     属「装载第一遍」，故排在语句参数告警之前。
        loadWarnings.addAll(symbolTable.duplicateFunctionWarnings())

        // 2) 预扫描：私有变量作用域 + 函数返回类型 + 宏/全局变量兜底（口径 = CorpusValidator.prescan）
        val scan = CorpusScan.scan(allFiles)
        // CMP-65 / M11-D save-15a 并轨：预扫描自留的降级痕迹（预处理区间未闭合 / 读失败 /
        // 二进制跳过）此前被整个丢弃（`CorpusScan.warnings` 有生产无消费）。本侧走装载告警
        // 通道（`CompileResult.loadWarnings`），按语料相对路径 + 1-based 行号折成 golden 口径。
        for (w in scan.warnings) {
            val rel = runCatching { root.relativize(Path.of(w.file)).toString().replace('\\', '/') }
                .getOrElse { w.file }
            loadWarnings.add(LoadWarning(rel, w.line + 1, w.message))
            notes.add("预扫描告警: ${w.file}:${w.line} ${w.message}")
        }
        val methodNames = scan.funcTypes.keys.mapTo(HashSet()) { it.uppercase(Locale.ROOT) }
        val exprCalls = buildExprCallTable(allDefs, scan.funcTypes)
        // CMP-36 接线（L06 交付项）：编译期词法开关必须吃本游戏的 `emuera.config`，
        // 不能停在 [LexerConfig] 的出厂默认（原版 `LexicalAnalyzer.cs:748-752`/`:681`
        // 的 SystemAllowFullSpace / SystemIgnoreTripleSymbol 都取配置值；本作两键为 NO）。
        // 语料代码位当前全角空格 0 命中 ⇒ 产物字节预期不变（组长复跑 corpusSmoke 与重编读数确认）。
        // CONFIG-WIRE U7：配置链前移，`_Rename.csv` 装载按 `useRenameFile` 门控（spec:
        // Process.cs:261-274 —— 开关开且文件存在才 `LoadEraExRenameFile`；开关开而文件缺席
        // 原版 `PrintError(MissingRename)`，这里折成装载告警；ErbLoader.cs:370 逐文件再按
        // `UseRenameFile && RenameDic != null` 决定应用）。出厂两版语料均 `:YES` 且文件在，
        // 产物字节不变。
        val compileConfig = ConfigStore().also {
            EmueraConfigParser.loadChainFromDisk(root.parent ?: root, it)
        }
        val useRenameFile = EffectiveConfig.of(compileConfig).useRenameFile
        val renameDic = if (useRenameFile) loadRenameDic(root) else null
        if (useRenameFile && renameDic == null) {
            loadWarnings.add(
                LoadWarning(
                    "CSV/_Rename.csv",
                    -1,
                    "USE _RENAME.CSV FILE:YES 但 CSV/_Rename.csv 不存在（原版 PrintError MissingRename，spec: Process.cs:270-273）",
                ),
            )
            notes.add("MissingRename: 开关开而 _Rename.csv 缺席，[[置換名]] 不展开（spec: Process.cs:261-274）")
        }

        // 3) 语句切片：整轮共用一个 LineParser + 一个 CompileResolver（A13 口径）
        //    #DEFINE 宏表必须喂进词法器：PC 在 token 流上展开宏（LexicalAnalyzer.cs:942-1006），
        //    `天候` / `DEF_DT_CID` 一类名字不能落到变量解析（否则运行期「未定义的变量」）。
        val slices = ArrayList<Slice>()
        // U8 警告旗标接线（CONFIG-WIRE U8 × erbcc 编译警告面）：三个消费键的读数在此一次取定
        // （compile() 单遍单线程，配置中途不变），闸体在收尾段（bootMetadata 组装前）：
        // DisplayWarningLevel / WarnBackCompatibility / FunctionNotFoundWarning，判定与锚点见收尾段。
        val displayWarningLevel = run {
            val v = compileConfig.itemOf(ConfigKey.DisplayWarningLevel).value
            (v as com.eratw.port.core.config.ConfigValue.IntValue).value
        }
        val warnBackCompatibility = run {
            val v = compileConfig.itemOf(ConfigKey.WarnBackCompatibility).value
            (v as com.eratw.port.core.config.ConfigValue.Bool).value
        }
        // DisplayWarningFlag 旗标读数：constant 可能是常量名也可能是十进制数字串
        // （ConfigValue.kt:80-86 的 Enum.TryParse 口径），统一折成底层序数再比
        // （IGNORE=0/LATER=1/ONCE=2/DISPLAY=3，DisplayWarningFlag.cs:3-9）。
        val fnfFlagOrdinal = displayFlagOrdinal(
            compileConfig.itemOf(ConfigKey.FunctionNotFoundWarning).enumConstant(),
        )
        val fncFlagOrdinal = displayFlagOrdinal(
            compileConfig.itemOf(ConfigKey.FunctionNotCalledWarning).enumConstant(),
        )
        // 未定义静态 CALL 目标（步骤 7 的空体桩面）→ 首个撞点（文件, 行号），键控唯一。
        val fnfSites = LinkedHashMap<String, Pair<String, Int>>()
        // A1（2026-10-26 fixcore 波）：同一份 LexerConfig 同时喂下方 LineParser 与
        // LogicalLineReader —— reader 此前漏接 config，SystemAllowFullSpace=NO 时
        // 行首全角空格的口径会与 LineParser 分叉。
        val lexerConfig = LexerConfig.fromConfigStore(
            compileConfig,
            macros = symbolTable.config.macros,
            macroIgnoreCase = symbolTable.config.ignoreCase,
        )
        val parser = LineParser(
            CompileResolver(scan),
            lexerConfig = lexerConfig,
            // T5（挖虫波第三批）：`#LOCALSIZE 常量名` 形态的折叠表 —— 与 LabelTable
            // evalSharpInt（符号层 #LOCALSIZE→FuncMeta.localSize）吃同一份 constValues，
            // 两侧不许分叉。不注入则语句层把语料 2 处 `#LOCALSIZE 人物数量上限`
            // （fromEN/MISC.ERB:1246、M_KOJO_K63_独自関数.ERB:46）静默折成 0。
            dimConsts = symbolTable.config.constValues,
        )
        var binarySkipped = 0
        // ---- 第 3 层：文件级并行预取（双路 E5-2696v4 / 44 物理核）----
        // 解析侧必须整轮共用 LineParser + CompileResolver 且严格按原文件序串行；
        // 这里只把「读盘 + LogicalLineReader 词法切逻辑行」放进线程池，分块取回后
        // 串行回放。分块是为了不把 4324 个文件的逻辑行一次性驻留 6g 堆。
        // 逃生开关：-Deratw.erbcc.parallel=false；线程/块大小也可单独调。
        val sliceStarted = System.nanoTime()
        val parallelParse = System.getProperty("eratw.erbcc.parallel", "true") != "false"
        val threads = System.getProperty("eratw.erbcc.parallel.threads")?.toIntOrNull()
            ?: minOf(44, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        val chunkSize = (System.getProperty("eratw.erbcc.parallel.chunk")?.toIntOrNull() ?: 256)
            .coerceAtLeast(1)
        val pool = if (parallelParse && threads > 1) Executors.newFixedThreadPool(threads) else null
        val chunks: List<List<Path>> = if (pool != null) files.chunked(chunkSize) else listOf(files)
        System.err.println(
            "[erbcc-parallel] enabled=$parallelParse files=${files.size} " +
                "threads=${if (pool != null) threads else 1} chunk=$chunkSize",
        )
        try {
            for (chunk in chunks) {
                val batch: List<FilePrefetch> = if (pool != null) {
                    val futures = chunk.map { p ->
                        pool.submit(Callable<FilePrefetch> {
                            try {
                                prefetchFile(p, root, lexerConfig, renameDic, scan.macroNames)
                            } catch (e: Exception) {
                                // 只把 LexError 转成 LineEvent；其余异常仍要终止整轮编译
                                // （绝不能伪装成「读取失败」跳过文件 —— 那会静默少产物）。
                                // 但在池里会被包成 ExecutionException 丢掉文件名，补上再抛。
                                throw IllegalStateException("预取词法行失败：$p", e)
                            }
                        })
                    }
                    futures.map { it.get() }
                } else {
                    chunk.map { p -> prefetchFile(p, root, lexerConfig, renameDic, scan.macroNames) }
                }
                for (pf in batch) {
            val rel = pf.rel
            if (pf.readError != null) {
                parseFailures.add(
                    CompileFailure(rel, -1, pf.readError, FailLineSource.FILE_READ),
                )
                continue
            }
            // 与语料门同口径：前 8KB 出现 NUL 视为二进制，不解析。
            // CMP-64：这里原先静默 `continue` —— 不留痕、不计数，`stats.corpusFiles` 又取
            // `allFiles.size`（跳过发生在计数之后）⇒ 产物整文件缺失仍全绿。现在按预扫侧
            // `CorpusScan.readForScan` 的口径留痕（原版对读不开的文件是 CodeEE
            // FailedOpenFile，spec: ErhLoader.cs:81-84），并进独立读数 `stats.binarySkipped`。
            // 清单口径不变：文件仍由 [sourceFiles]（走 `allFiles`）登记 —— 登记但不解析。
            if (pf.nulAt >= 0) {
                binarySkipped++
                notes.add(
                    "二进制跳过（前 8KB 出现 NUL，偏移 ${pf.nulAt}）：$rel —— ${pf.bytesSize} 字节，" +
                        "不产生任何切片，产物里没有它的指令（源清单仍登记）",
                )
                continue
            }

            // 逻辑行已在并行阶段切好（含 reader.fileEnd() 的预处理收尾）；这里只按原序
            // 回放，词法错误仍以抛 LexError 的形式交回下面的 catch，语义与单线程一致。
            val reader = PrefetchedLineReader(pf.events)
            // 语句层告警按文件归属：LineParser 整轮共用、其 warnings 只追加，
            // 故用「本文件起点」切出增量（原版逐文件装载时也是逐文件打印）。
            val warnMark = parser.warnings.size
            val body = ArrayList<Statement>()
            val dimLines = ArrayList<String>()
            var curKey: String? = null
            var curParams: List<String> = emptyList()
            var curParamRefs: List<String> = emptyList()
            var curParamDefaults: List<ParamDefault?> = emptyList()
            var curLine = -1
            var poisoned = false

            fun flush() {
                val key = curKey
                if (key == null) {
                    body.clear(); dimLines.clear(); poisoned = false
                    return
                }
                if (!poisoned) {
                    slices.add(
                        Slice(
                            rel, key, curLine, curParams, curParamRefs, curParamDefaults,
                            body.toList(), dimLines.toList(), false,
                        ),
                    )
                } else {
                    parseFailures.add(
                        CompileFailure(rel, curLine, "@$key 切片含解析失败行（函数不可用）", FailLineSource.SLICE_HEAD),
                    )
                }
                body.clear(); dimLines.clear(); curKey = null; poisoned = false
                curParamDefaults = emptyList()
            }

            while (true) {
                val line = try {
                    reader.readLogicalLine() ?: break
                } catch (e: LexError) {
                    parseFailures.add(
                        CompileFailure(rel, e.lineNo, "词法错误 ${e.message}", FailLineSource.of(e)),
                    )
                    poisoned = true
                    continue
                }
                val stmt = try {
                    parser.parseLogicalLine(line)
                } catch (e: Exception) {
                    parseFailures.add(
                        CompileFailure(
                            rel,
                            line.lineNo,
                            "${e.message ?: "解析失败"} | ${line.content.take(120)}",
                            // 异常自带 0 基位置字段时按字段标；没有字段才是「回落当前逻辑行」。
                            if (FailLineSource.of(e) == FailLineSource.PARSE_EXCEPTION)
                                FailLineSource.PARSE_EXCEPTION
                            else
                                FailLineSource.LOGICAL_LINE,
                        ),
                    )
                    poisoned = true
                    continue
                } ?: continue
                if (stmt is SharpStmt) {
                    // # 行不产生指令（#FUNCTION 已在预扫描）；但 #DIM/#DIMS/#DIMF 声明是装载期
                    // 元数据，必须随侧车带走：函数外的进全局原文（交 SystemVariableBootstrap.install），
                    // 函数内的进本切片（私有用户变量）。`#DIMF` 是 4.981 fork 的浮点声明
                    // （spec: variables.yaml:105-111），与 `#DIM` 同一侧车通道。
                    val kw = stmt.keyword.uppercase(Locale.ROOT).removePrefix("#")
                    if (kw == "DIM" || kw == "DIMS" || kw == "DIMF") {
                        val raw = stmt.raw.trim()
                        if (curKey == null) {
                            // CMP-89（L08 交付项）：ERB 函数外的 `#DIM` 原版一律 InvalidSharp 丢弃
                            // （ErbLoader.cs:413-423，warn level 1；全局用户变量只从 ERH 装载，
                            // Process.cs:309/:313）。此前本侧把它收进全局声明 —— 命名空间成原版超集
                            // 且可能进存档面。现在丢弃并留痕（notes + loadWarnings 同一可见面）。
                            if (SystemVariableBootstrap.isDiscardedErbDimSource(rel)) {
                                val why = SystemVariableBootstrap.ERB_FUNCTION_DIM_REASON +
                                    "：${raw}（${rel}:${stmt.line}）"
                                notes.add(why)
                                loadWarnings.add(LoadWarning(rel, stmt.line, why))
                            } else {
                                globalDimDecls.add(BootMetadata.RawDim(raw, rel, stmt.line))
                            }
                        } else {
                            dimLines.add(raw)
                        }
                    }
                    continue
                }
                if (stmt is FuncLabelStmt) {
                    flush()
                    curKey = stmt.name.uppercase(Locale.ROOT)
                    curParams = stmt.paramNames
                    curParamRefs = stmt.paramRefs
                    curParamDefaults = stmt.paramDefaults
                    curLine = stmt.line
                    continue
                }
                if (curKey == null) continue
                // ENGFIX2 B4：成对变参组约束在**装载期**上屏（原版 FunctionMethod.cs:186-193
                // MatchVariadicGroup → CheckArgumentTypeEx，经 ErbLoader.cs:481-490 收成
                // ParserMediator.Warn）。判据只读 Builtins 注册表的 VariadicGroup 声明，
                // 与运行期 MapDataTableMethods 的 CodeEE 兜底同源、相位补齐。
                pairedArgLoadWarning(stmt, rel, methodNames)?.let { loadWarnings.add(it) }
                body.add(stmt)
            }
            flush()
            try { reader.fileEnd() } catch (_: Exception) {}
            // 3a) 本文件新增的语句层告警（原版 level 0/1 的非致命诊断；`StmtWarning` 无 level 位，
            //     这里一律按 [LoadWarning] 的 level-1 语义收，见该类 KDoc）。
            //     行号 +1：`LogicalLine.lineNo` 是 0 基（LogicalLineReader.kt 的 curNo 口径），
            //     而原版 `ScriptPosition.LineNo = srcLineNo + 1`（ScriptPosition.cs:10-16）、
            //     告警基准也是 1 基 —— 不换算会整列错一行。
            for (i in warnMark until parser.warnings.size) {
                val w = parser.warnings[i]
                loadWarnings.add(LoadWarning(rel, w.line + 1, w.message))
            }
                }   // for (pf in batch)
            }       // for (chunk in chunks)
        } finally {
            // 正常路径所有 future 已取回、队列是空的，shutdownNow 等价于 shutdown；
            // 异常路径则立刻打断剩余预取，避免白等满 1 分钟、也避免残留线程拖住 JVM 退出。
            pool?.shutdownNow()
            pool?.awaitTermination(1, TimeUnit.MINUTES)
        }
        System.err.println(
            "[erbcc-parallel] 切片段耗时 ${(System.nanoTime() - sliceStarted) / 1_000_000}ms " +
                "→ ${slices.size} 切片 / ${parseFailures.size} 解析失败 / $binarySkipped 二进制跳过",
        )
        notes.add("切片完成：${files.size} 文件 → ${slices.size} 个 @函数切片；解析失败切片 ${parseFailures.size} 个")

        // 4) 切片 ↔ 符号定义对齐（(文件,键) 内出现序；行号口径漂移不影响）
        val queues = LinkedHashMap<Pair<String, String>, ArrayDeque<LabelDef>>()
        for (d in allDefs) queues.getOrPut(d.file to d.key) { ArrayDeque() }.addLast(d)

        val expected = LinkedHashMap<String, LabelDef>()
        fun sigKeyOf(d: LabelDef): String {
            val group = symbolTable.defsOf(d.key)
            val winner = group.lastOrNull()
            return when {
                d.isEvent -> "${d.key}#${d.defIndex}"
                group.size > 1 && winner != null && d.defIndex != winner.defIndex -> "${d.key}#${d.defIndex}"
                else -> d.key
            }
        }
        for (d in allDefs) expected.putIfAbsent(sigKeyOf(d), d)

        var syntheticSeq = 10_000_000
        val units = ArrayList<Unit>()
        val covered = HashSet<String>()
        for (s in slices) {
            val def = queues[s.rel to s.keyUpper]?.removeFirstOrNull() ?: run {
                val d = LabelDef(
                    name = s.keyUpper, key = s.keyUpper, file = s.rel, lineNo = s.line,
                    fileIndex = 1, defIndex = syntheticSeq++,
                    isEvent = Symbols.isEventLabelName(s.keyUpper), isSystem = false,
                    isMethod = s.keyUpper in methodNames,
                    isOnly = false, isPri = false, isLater = false, isSingle = false,
                )
                notes.add("符号表未命中，合成定义: ${s.rel}:${s.line} @${s.keyUpper}")
                d
            }
            var sigKey = sigKeyOf(def)
            if (sigKey in covered) {
                sigKey = "$sigKey#dup${def.defIndex}"
                notes.add("同一 sigKey 重复切片，改键: ${def.key}#${def.defIndex} → $sigKey")
            }
            covered.add(sigKey)
            expected.putIfAbsent(sigKey, def)
            units.add(Unit(sigKey, def, s))
        }
        // 符号表里有、但切片阶段缺失（读取失败/毒化后无切片）的定义：空体桩兜底
        for ((key, def) in expected) {
            if (key !in covered) units.add(Unit(key, def, null))
        }
        units.sortBy { it.def.defIndex }

        // 4b) 装载期符号使用点（可选；只读遍历解析结果，不参与任何发射）
        if (uses != null) {
            for (u in units) {
                val s = u.slice ?: continue
                if (s.poisoned) continue
                // 被遮蔽的非事件重名定义不会被 CALL 到、其私有 #DIM 也不注册
                // （[collectPrivateDims] 同口径）⇒ 不把它的语句算进命名空间差集。
                if (!u.def.isEvent && u.sigKey != u.def.key) continue
                SymbolUseWalker.walk(s.rel, s.stmts, u.def.key, uses)
            }
        }

        // 5) 编译：全函数进同一个 Program（布局不参与发射，契约 C1）
        val eventKeys = symbolTable.nameKeys.filter { k ->
            val ds = symbolTable.defsOf(k)
            ds.size > 1 && ds.first().isEvent
        }
        val known = HashSet<String>(expected.keys)
        known.addAll(eventKeys)

        // U8 FunctionNotFoundWarning 撞点预收集（spec: ErbLoader.cs:1590-1632 装载期常量名
        // CALL/JUMP 解析 → printFunctionNotFoundWarning ErbLoader.cs:956-993）。镜像编译器的
        // 静态目标缺失判定面（core-vm ErbProgramCompiler.kt:2740-2764 —— 常量名、非 TRY 族、
        // 目标不在 known ⇒ NotDefinedFunc 哨兵）。TRY/TRYC 族（C# ErbLoader.cs:3577-3588 静默）、
        // CALLFORM/动态名（C# FunctionIdentifier.cs:3524-3528 置 useCallForm，整语料静默）、
        // GOTO 函数内标签（STATIC_LABEL_CODES 分支，另一文案）均不在检测面。
        // 集合键控唯一目标 × 首撞点；C# 按调用点逐个计数，计数口径差登记在交付报告。
        for (u in units) {
            val s = u.slice ?: continue
            if (s.poisoned) continue
            for (st in s.stmts) {
                val label = when (st) {
                    is CallStmt -> if (st.code == FunctionCode.CALL) st.label else null
                    is JumpStmt -> if (st.code == FunctionCode.JUMP) st.label else null
                    else -> null
                } ?: continue
                val target = label.trimStart('@').uppercase(Locale.ROOT)
                if (target in known) continue
                fnfSites.computeIfAbsent(target) { s.rel to st.line }
            }
        }

        // 缺省实参默认值（spec: FunctionLabelLine.cs:49 / CalledFunction.cs:167）：
        // 键 = 可调用名（`CALL` 目标口径）；重名被遮蔽的定义不会被 CALL 命中，只收胜者。
        // 只在编译期补实参，`.erbc` 容器不新增任何字段。
        val callDefaults = CallDefaultsCollector()
        for (u in units) {
            val s = u.slice ?: continue
            if (s.poisoned) continue
            if (u.sigKey != u.def.key) continue
            callDefaults.add(u.def.key, s.paramDefaults)
        }
        if (callDefaults.functionsWithDefaults > 0 || callDefaults.unsupported.isNotEmpty()) {
            notes.add(
                "缺省实参默认值：${callDefaults.functionsWithDefaults} 个函数可编译期补位；" +
                    "非字面量 ${callDefaults.unsupported.size} 处不注入（" +
                    callDefaults.unsupported.take(5).joinToString(" | ") + "）",
            )
        }

        // 尺寸常量表：REF/维度求值（下面的生产布局）与步骤 8 的 `#DIM` 折叠共用同一份，
        // 避免两处各算一份而漂移（口径 = 宏表 + ERH 常量，com.erbcc.bridge.sizeConsts）。
        val consts = runCatching { sizeConsts(symbolTable) }.getOrDefault(emptyMap<String, Long>())

        // `#DIM` 解析/折叠失败的 level 2 告警面（本节布局折叠与步骤 8 的侧车折叠共用）。
        val dimWarnings = ArrayList<CompileFailure>()

        // ---- 装载期尺寸/关键字快照（CMP-40 / CMP-59）----
        //
        // VARSIZE 尺寸快照（M4-M `m-dimfold-wire`）：装载时点 = VariableSize.csv 之后、ERH 之前
        // （spec: Process.cs:292-293 装 ConstantData → :300-301 建 IdentifierDictionary →
        // :309/:313 装 ERH；取数结构 = `VariableRegistry.all()` 的 `VarDef.dims`，见 DimVarSizes KDoc）。
        // CSV 目录口径与 core-vm 装置 RealCorpusCompile 逐字一致（`<ERB>.parent/CSV`），两侧必须是
        // 同一份快照，否则布局/侧车尺寸求值与装置/运行期注册分叉。目录缺失时退回出厂 VariableSizes
        // （VariableSizeLoader 找不到文件就静默跳过），与装置的退路同口径。
        // 「erbcc 拿不到游戏 CSV 尺寸表、VARSIZE 只能维持不折叠」的旧论断已被证伪：语料根旁
        // 就有 `CSV/VariableSize.csv`，挂上快照后全局 #DIM 缺口 5→0、私有 7→3。
        // **前移原因（CMP-40）**：生产布局就在下面，必须用这张快照折 `#DIM` 尺寸，不能等到步骤 8。
        val csvDir = root.parent?.resolve("CSV") ?: root
        val gameRoot = root.parent ?: root
        // `USE THE BINARY FORMAT FOR SAVING DATA` 必须与运行期同一把尺（语料
        // `emuera.config:60` = YES）：它是 `#DIM ... SAVEDATA` 的登记闸
        // （spec: UserDefinedVariableData.cs:465-475）。编译期漏读会让 66 条
        // `CHARADATA SAVEDATA` 全局声明在侧车（以及私有声明）里被拒，
        // 运行时 GameSession 却按 YES 装进去 —— 审计只能看到一堆假 level-2 告警。
        // 位置前移到尺寸快照之前：用户全局 #DIM 的解析（下一段）也要吃这一位。
        val saveInBinary = runCatching {
            SystemVariableBootstrap.systemSaveInBinaryFrom(RootedBuiltinHost(gameRoot).configOverrides)
        }.getOrDefault(false)
        val systemDimVarSizes = DimVarSizes.systemSnapshot(csvDir)
        // GETNUM(...) 的装载期折叠表（spec: FunctionMethodCreator.cs:4814-4832）：
        // 与运行期 GameSession 同一份 CSV 名字表；缺 CSV 时保持「不折叠」的失败方向。
        val gameDataForFold = runCatching { GameDataLoader.load(csvDir) }.getOrNull()
        val systemKeywordTables = gameDataForFold?.registry?.systemKeywordTables() ?: emptyMap()
        // CMP-59 数据面：ERD 用户表（`CSV/*.csv` + `ERB/**/*.erd`，键 `NAME` / `NAME@n`）。
        // 原版 `TryKeywordToInteger`（ConstantData.cs:849-871）在内置表未命中后还要查
        // `erdNameToIntDics`（:866-870）；不喂这张快照，第二跳在装载期折叠面永远没有数据。
        // `Config.UseERD` 门与运行期 GameSession 同源（语料 `emuera.config:69` = YES）。
        val erdKeywordTables: Map<String, Map<String, Int>> =
            if (compileConfig.itemOf(ConfigKey.UseERD).bool()) {
                // 系统变量名不是 ERD 用户变量（原版 `#DIM` 重名会被拒）⇒ 排除，
                // 避免给 `GETNUM(TIME, …)` 一类折出原版没有的表（见 foldSnapshot KDoc）。
                val systemNames = gameDataForFold?.registry?.all()
                    ?.mapTo(HashSet()) { it.name.uppercase(Locale.ROOT) } ?: emptySet()
                runCatching { UserKeywordTables.foldSnapshot(gameRoot, systemNames) }
                    .getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
        // 键冲突时内置表优先（= 原版第一跳 `GetKeywordDictionary` 的口径，ConstantData.cs:857-861）。
        // 当前语料两套表零交集（`EI_FLAG` / `COLUMNDIV@2` 都不是内置名表）。
        val foldKeywordTables: Map<String, Map<String, Int>> = erdKeywordTables + systemKeywordTables
        // GETLINESTR 的填充宽度取 PC 基准的可绘制宽（199 半角格，见 [FOLD_CONSOLE_WIDTH_CELLS]）。
        // 侧车里的私有 #DIM 初值在编译期固定，设备传入的宽度不同时仅影响这一个布局变量。
        val dimConsoleWidth = FOLD_CONSOLE_WIDTH_CELLS
        // 用户全局 `#DIM` 的尺寸并进快照：原版 ERH 边装边往 `IdentifierDictionary`
        // 注册（`IdentifierDictionary.cs:429`），后加载的声明能看到先声明的用户全局。
        // 只喂 `systemSnapshot` 时 `VARSIZE("DLC_Toggle")` 折不出 —— 4.981 的
        // `NEWGAME/NEWGAME_MAKE.ERB` 与 `DLC/OPTIONPRINT.ERB` 两条私有 #DIM 在布局面
        // 与侧车双双被丢弃，运行到「游戏前的选择设定」报「未定义的变量 可选择的DLC」。
        val dimVarSizes = mergeGlobalUserDimSizes(
            decls = globalDimDecls,
            systemSizes = systemDimVarSizes,
            keywordTables = foldKeywordTables,
            consoleWidth = dimConsoleWidth,
            macros = symbolTable.config.macros,
            consts = consts,
            saveInBinary = saveInBinary,
        )
        val dimFolder = DimFunctionFolders.pureStatic(dimVarSizes, foldKeywordTables, dimConsoleWidth)

        // CMP-25（P0）：REF 形参信息必须进**生产档**布局。
        //
        // 旧实参 `GlobalNameLayout()` 不持声明、`refParamNamesOf` 恒 null ⇒ 编译器
        // `ErbProgramCompiler.hasRefParams` 恒 false ⇒ `varArgSites` 恒空、
        // `patchRefArgEvals` 空转 ⇒ 裸多维数组实参在 REF 位照常 `_EVAL_INT`，运行期在
        // `VariableStore` 的下标维度校验上抛「变量 X 需要 N 个下标（实际 0）」，`_CALL` 都到不了。
        // 原版 REF 位不读值：装载期只校验实参是 VariableTerm 且同型同维
        // （spec: CalledFunction.cs:154-159），求值期取 `Identifier.GetArray()`
        // （spec: UserDefinedFunctionArgument.cs:45-58 → ReferenceToken.cs:125-128）。
        //
        // 生产档改传与 boot/E2E 装置（RealCorpusCompile.kt:356 / GoldenPipeline.kt:451）同一口径的
        // `DimSpecLayout`：`LabelDef.dimSpecs` → `LocalDim.ref`（com.erbcc.bridge.buildFuncDims，
        // 常量表与侧车同源）。契约 C1 后布局的 slotOf/beginFunction 不参与发射（具名变量只进
        // VariableStore），编译器只读 `refParamNamesOf` ⇒ 发射面差异**仅限 REF 位回填**。
        // ⚠ 修后 `.erbc` 必变（表达式池多两条常量、REF 位的值指令 a 换常量），须全量重编。
        // CMP-40 布局面折叠：`VARSIZE/POWER/COLOR_FROMNAME/UNICODE/GETNUM/STRLENSU/GETLINESTR`
        // 必须与侧车/运行期同源（同一份 dimVarSizes + foldKeywordTables + 控制台宽度），
        // 否则 `#DIM X, VARSIZE("RELATION")` 在布局面折不出、整条声明被静默丢。
        // 布局不参与发射（契约 C1：具名变量只进 VariableStore，编译器只读 refParamNamesOf）
        // ⇒ 本项不改变 `.erbc` / manifest 字节；丢弃走 [layoutDrops] → dimWarnings
        // （CMP-89「不能静默丢」的 erbcc 留痕面）。
        val layoutDrops = ArrayList<DimDrop>()
        val funcDims = buildFuncDims(allDefs, consts, dimFolder, layoutDrops)
        val layout = DimSpecLayout(GlobalNameLayout(), funcDims)
        reportLayoutDrops(layoutDrops, allDefs, dimWarnings)
        notes.add(
            "布局面 #DIM：${funcDims.values.sumOf { it.size }} 条声明进 ${funcDims.size} 个函数的局部表" +
                "（折叠器：VARSIZE 快照 ${dimVarSizes.size} 项，其中用户全局 " +
                "${dimVarSizes.size - systemDimVarSizes.size} 条 + 内置名表 ${systemKeywordTables.size} 张" +
                " + ERD 表 ${erdKeywordTables.size} 张）；丢弃 ${layoutDrops.size} 条",
        )
        val compiler = ErbProgramCompiler(layout, callDefaults.build())
        var compiled = 0
        var compileFailures = 0

        // 侧车要收的三张表（只读取编译产物，不改任何发射行为）：
        val labelPcs = LinkedHashMap<String, Map<String, Int>>()
        val fileByKey = LinkedHashMap<String, String>()
        val metas = ArrayList<FuncMeta>()

        /**
         * 兼容编译档的降级函数体：入口一条 `THROW`。
         *
         * 严格档返回空体（调用方随后会因失败拒落盘）；兼容档让「调用不可用函数」变成
         * 立刻可见的运行期错误，而不是静默返回 —— 与既有的「装载期行级错误 →
         * 运行期 THROW marker」口径一致（`ErbProgramCompiler.emitStaticTargetNotFound`）。
         *
         * 文案取册内官方原文 [`LangKey.CalledFailedFunc`]（`Lang.cs:2450`）：原版装载期
         * 解析失败的函数不删标签、只打 `ErrMes`，调用时抛这一句
         * （`ErbLoader.cs:995-1010` → `CalledFunction.cs:133` → `Process.cs:1017-1035`）。
         * 2026-11-08 前这里是自造串（带函数名），现按 ADR-005 照抄官方。
         */
        fun degradedBody(sigKey: String, line: Int): List<Statement> = if (!degradedStub) {
            emptyList()
        } else {
            listOf(
                InstructionStmt(
                    FunctionCode.THROW,
                    ExpressionArgument(StrLiteral(LangKey.CalledFailedFunc.renderZhs())),
                    line,
                    "THROW",
                ),
            )
        }

        fun compileOne(
            sigKey: String,
            def: LabelDef,
            body: List<Statement>,
            params: List<String>,
            paramRefs: List<String> = emptyList(),
        ) {
            val funcType = scan.funcTypes[def.key.uppercase(Locale.ROOT)]
            val returnsStr = funcType == OperandType.STR
            // 4.981 fork 的第三种 MethodType（#FUNCTIONF）；RETURNF 由此编 `_RETF`（路次 g7）。
            val returnsFloat = funcType == OperandType.FLOAT
            val arity = if (params.isNotEmpty()) params.size else maxOf(def.argCount, def.argIntCount + def.argStrCount)
            // 形参分桶（与 core-vm 装置 RealCorpusCompile.argCounts 同口径）：`#DIM` 私有变量的
            // 运行期绑定要按 ARG/ARGS 两套数组，而 `LabelDef` 的 argInt/argStr 在有括号形参时可能是 0。
            val strParam = params.count { it.uppercase(Locale.ROOT).startsWith("ARGS") }
            val argInt = if (def.argCount > 0) def.argIntCount else params.size - strParam
            val argStr = if (def.argCount > 0) def.argStrCount else strParam
            var fb: FunctionBytecode? = null
            try {
                fb = compiler.compileFunction(
                    FuncSig(sigKey, arity, def.isMethod, returnsStr, paramRefs, returnsFloat = returnsFloat),
                    body, known, exprCalls,
                )
                compiled++
            } catch (e: CompileException) {
                compileFailures++
                // 判据 = `e.line`（0 基结构化字段），不看 message；line<0 是异常声明的**函数级**
                // 错误（重名 / GOTO 目标缺失），此时回退成 `@定义` 行只为给容错清单留一个可比的键，
                // 这件事由 lineSource 明说，不再让读表人把定义行当成出错行（lane compreadout A14）。
                failures.add(
                    CompileFailure(
                        def.file,
                        if (e.line >= 0) e.line else def.lineNo,
                        "[$sigKey] ${e.message}",
                        FailLineSource.of(e),
                    ),
                )
                // 空体桩兜底：保留函数名供 _CALL 链接（与 RealCorpusCompile 同口径）；
                // 兼容档换成入口 THROW，让运行期调用立刻可见。
                try {
                    fb = compiler.compileFunction(
                        FuncSig(sigKey, arity, def.isMethod, returnsStr, paramRefs, returnsFloat = returnsFloat),
                        degradedBody(sigKey, def.lineNo), known, exprCalls,
                    )
                } catch (e2: Exception) {
                    failures.add(
                        CompileFailure(
                            def.file, def.lineNo, "[$sigKey] 空体桩也失败: ${e2::class.simpleName}: ${e2.message}",
                            // 这一格存的是 **def.lineNo**（@定义行），不是 e2 的位置字段 ⇒ 来源必须
                            // 标切片头行；按异常类型标会把「函数头行」冒充成「异常声明的行」。
                            FailLineSource.SLICE_HEAD,
                        ),
                    )
                }
            } catch (e: Exception) {
                compileFailures++
                failures.add(
                    CompileFailure(
                        def.file, def.lineNo, "[$sigKey] 编译内部错误: ${e::class.simpleName}: ${e.message}",
                        // 数值恒为 def.lineNo（@定义行），与异常是否带位置字段无关（键面不许动，
                        // 见 CompileFailure 的 KDoc）⇒ 来源标切片头行；异常类型已经在 message 里。
                        FailLineSource.SLICE_HEAD,
                    ),
                )
            }
            val bytecode = fb ?: return
            metas.add(
                FuncMeta(
                    index = bytecode.index,
                    key = def.key,
                    name = def.name,
                    isEvent = def.isEvent,
                    isMethod = def.isMethod,
                    isOnly = def.isOnly,
                    isPri = def.isPri,
                    isLater = def.isLater,
                    isSingle = def.isSingle,
                    argIntCount = argInt,
                    argStrCount = argStr,
                    localSize = def.localSize,
                    localsSize = def.localsSize,
                    floatLocalSize = def.floatLocalSize,
                    returnsStr = returnsStr,
                    returnsFloat = returnsFloat,
                    defIndex = def.defIndex,
                    position = "${def.file}:${def.lineNo}",
                ),
            )
            labelPcs[sigKey.uppercase(Locale.ROOT)] = bytecode.labels
            fileByKey[sigKey.uppercase(Locale.ROOT)] = def.file
        }

        for (u in units) {
            val s = u.slice
            if (s == null) {
                // 无切片 / 毒化切片：只有合成占位形参（stubParams），它们不是源码头部形参项，
                // 不能落进 PARAM_NAMES（否则 ProcessRuntime 会把实参绑给一个不存在的形参）。
                compileOne(u.sigKey, u.def, degradedBody(u.sigKey, u.def.lineNo), stubParams(u.def))
                continue
            }
            if (s.poisoned) {
                compileOne(u.sigKey, u.def, degradedBody(u.sigKey, u.def.lineNo), stubParams(u.def))
            } else {
                compileOne(u.sigKey, u.def, s.stmts, s.params, s.paramRefs)
            }
        }

        // 6) 事件入口（同名多定义；装载序 = ONLY→PRI→NORMAL→LATER）
        var eventEntries = 0
        // 合成入口的 defIndex 必须排在全部真实定义之后（FunctionTable 按 defIndex 排序，
        // 事件成员的同桶次序依赖这一点）。口径 = core-vm 装置 RealCorpusCompile 的 entrySeq。
        var eventEntrySeq = (allDefs.maxOfOrNull { it.defIndex } ?: 0) + 10_000
        for (evKey in eventKeys) {
            val members = runCatching { symbolTable.eventCallOrder(evKey).map { "${it.key}#${it.defIndex}" } }
                .getOrDefault(emptyList<String>())
            if (members.isEmpty()) continue
            try {
                val fb = compiler.compileEventEntry(evKey, members)
                eventEntries++
                metas.add(
                    FuncMeta(
                        index = fb.index, key = evKey, name = evKey,
                        isEvent = false, isMethod = false, defIndex = eventEntrySeq++,
                        position = EVENT_ENTRY_POSITION,
                    ),
                )
                labelPcs[evKey.uppercase(Locale.ROOT)] = fb.labels
                fileByKey[evKey.uppercase(Locale.ROOT)] = EVENT_ENTRY_POSITION
            } catch (e: Exception) {
                compileFailures++
                failures.add(
                    CompileFailure(
                        "*", -1,
                        "事件入口 $evKey: ${e::class.simpleName}: ${e.message}",
                        FailLineSource.NO_SOURCE,
                    ),
                )
            }
        }

        // 7) 冻结 Program。语料里本来就未定义的 CALL 目标：补 0 参空体桩后重试
        var program: Program? = null
        try {
            var guard = 0
            while (true) {
                try {
                    program = compiler.build(null)
                    break
                } catch (e: CompileException) {
                    // 这里刮的是 message，不是行号 —— `CompileException` 没把 target 做成字段
                    // （`CompileError.kt` 只带 functionName/line/message），本模块改不了那条 main 面。
                    // 失效形状是**响亮**的：刮不到 ⇒ throw 原异常 ⇒ 落 `Program 构建失败` 一条失败
                    // ⇒ 产物不生成、退出码非零，不会像「按行号刮 message」那样静默丢归因。
                    // 待组长接线项（写进交付）：CompileException 补 `val targetFunction: String?`，
                    // 本处改读字段。
                    val target = Regex("未定义的函数:\\s*(.+)$").find(e.message ?: "")
                        ?.groupValues?.get(1)?.trim()
                    if (target.isNullOrEmpty() || guard++ >= 500 ||
                        target.uppercase(Locale.ROOT) in compiler.knownFunctionNames
                    ) {
                        throw e
                    }
                    notes.add("未定义的 CALL 目标 '$target' → 空体桩（原版仅执行到该行才报错）")
                    compiler.compileFunction(FuncSig(target), emptyList(), known, exprCalls)
                }
            }
        } catch (e: Exception) {
            failures.add(
                CompileFailure(
                    "*", -1,
                    "Program 构建失败: ${e::class.simpleName}: ${e.message}",
                    FailLineSource.NO_SOURCE,
                ),
            )
        }

        val p = program
        val stats = CompileStats(
            corpusFiles = allFiles.size,
            slices = slices.size,
            compiled = compiled,
            compileFailures = compileFailures,
            parseFailures = parseFailures.size,
            eventEntries = eventEntries,
            instructions = p?.instructionCount ?: -1,
            statements = p?.statementCount ?: -1,
            functions = p?.functions?.size ?: -1,
            strings = p?.stringCount ?: -1,
            constants = p?.constantCount ?: -1,
            expressions = p?.exprCount ?: -1,
            globalSlots = p?.globalSlots ?: -1,
            entryFunction = p?.entryFunction ?: -1,
            fileLimit = effectiveLimit,
            binarySkipped = binarySkipped,
        )
        val sourceFiles = allFiles.mapNotNull { path ->
            val rel = runCatching { root.relativize(path).toString().replace('\\', '/') }
                .getOrElse { path.toString() }
            val bytes = try {
                Files.readAllBytes(path)
            } catch (e: Exception) {
                notes.add("源文件读取失败（不进 manifest）: $rel ${e::class.simpleName}")
                return@mapNotNull null
            }
            HashEntry(rel, bytes.size.toLong(), ErbcArtifacts.sha256Hex(bytes))
        }

        // 8) 侧车元数据：只读派生（不参与任何发射/统计）
        // 尺寸常量表、VARSIZE/GETNUM 快照、`dimWarnings` 都已在步骤 5 前算好
        // （生产布局的维度/REF 求值与本节 `#DIM` 折叠必须同一份，CMP-40/CMP-59）。
        // M11-E `dimfold-wire` 把三件套包成 [DimFoldSnapshot]：只许整面构造，消费点只许经
        // [DimFoldSnapshot.apply] 挂配置 —— 只传 varSizes 而让其余吃默认值，同一行
        // `#DIM DYNAMIC 行文字数 = STRLENSU(GETLINESTR("─"))` 会在侧车折成 99、在 boot 装置
        // 折成 900（默认 1800 格），侧车与装置读数直接分叉。
        // **数据面只有一份**：快照里的表就是步骤 5 那份（含 CMP-59 的 ERD 第二跳），不在这里
        // 重算 —— 重算一遍会把 `UserKeywordTables.foldSnapshot` 的 ERD 表漏掉（CMP-59 白做）。
        val foldSnapshot = DimFoldSnapshot(dimVarSizes, foldKeywordTables, dimConsoleWidth)
        // `saveInBinary` 已在尺寸快照之前算好（同一把尺，见上文）。
        // 字符串常量同样是私有 `#DIM` 在编译期折叠初值的依据（侧车里的 privateDims 是**已解析**
        // 的 spec，运行期不再重解析），故必须与整型常量一起进 dim-config。
        // 折叠面只挂给**本次解析**：`DimParseConfig.functionFolder` 是回调、不可序列化，
        // 侧车文本（BootMetadata.encodeText）装不下它，而 `BootMetaRoundTripTest` 的
        // 「宏/常量表往返」正是拿整个 DimParseConfig 做 equals 比较 —— 把带回调的配置写进侧车
        // 会让该用例红（folder 无 equals 重写，恒不等）。
        // ⇒ 侧车字段保持无回调；运行期注册点（GameSession 调 metadata.dimParseConfig 装
        // SystemVariableBootstrap.install）按同一快照口径自己再挂一次。
        val foldCfg = foldSnapshot.apply(
            DimParseConfig(
                macros = symbolTable.config.macros,
                intConstants = consts,
                systemSaveInBinary = saveInBinary,
            ),
        )
        val strConsts = foldStringConstants(globalDimDecls, foldCfg, dimWarnings)
        val dimCfg = DimParseConfig(
            macros = symbolTable.config.macros,
            intConstants = consts,
            strConstants = strConsts,
            systemSaveInBinary = saveInBinary,
        )
        val privateDims = collectPrivateDims(
            units,
            foldSnapshot.apply(dimCfg),
            dimWarnings,
        )
        val dataSections = collectDataSections(slices, program)

        // R7 波 0：把两张「设备端补丁编译必需、但 `.erbc` 容器装不下」的表随侧车落盘。
        // - `param-defaults`：编译期补位用的缺省实参默认值表（全量语料 948 量级）。补丁编译
        //   手里没有全量函数头，读不到它就只能「省略位不写、形参保留既有值」，而 PC 全量编译
        //   会把默认值补上 —— 同一行 ERB 两种行为，不报错、不崩溃，只是数值悄悄不对。
        // - `method-types`：方法返回类型三态。`FuncMeta.returnsStr` 只有两态（口径见下面
        //   `compileOne` 的 `returnsStr`），类型未知与 INT 混同；而 `buildExprCallTable` 要三分桶
        //   （误把 `#FUNCTION` 降级成字符串通道会静默读错值）。
        //
        // 键口径 = `FuncMeta.key`（= `LabelDef.key`，`ignoreCase` 默认真时即大写），与 `functions`
        // 段**同键**，消费者按函数表键直查即可，不必再做一次归一。
        //
        // 同键多条定义（`@F` 定义了两次）只取**生效者**：`buildExprCallTable` 的口径是
        // 「defIndex 最大者胜」（`ExprCalls.kt:47-53`，spec: LabelDictionary.cs:60-68 字典直接赋值），
        // 这里必须同口径 —— 否则侧车给出的类型可能来自被遮蔽的那条定义，而全量编译用的是生效者，
        // 补丁编译就会按错的通道降级（静默读错值）。不能靠「遍历序恰好是 defIndex 升序」碰巧对上。
        val methodTypes = LinkedHashMap<String, BootMetadata.MethodType>()
        val methodWinnerDefIndex = HashMap<String, Int>()
        for (m in metas) {
            if (!m.isMethod) continue
            val prev = methodWinnerDefIndex[m.key]
            if (prev != null && prev > m.defIndex) continue
            methodWinnerDefIndex[m.key] = m.defIndex
            methodTypes[m.key] = when (scan.funcTypes[m.key.uppercase(Locale.ROOT)]) {
                OperandType.INT -> BootMetadata.MethodType.INT
                OperandType.STR -> BootMetadata.MethodType.STR
                // 类型未知：既非 `#FUNCTION` 也非 `#FUNCTIONS`。**不许折成 INT** —— 那正是缺口②。
                else -> BootMetadata.MethodType.UNKNOWN
            }
        }
        val duplicateMethodKeys = metas.count { it.isMethod } - methodTypes.size
        if (duplicateMethodKeys > 0) {
            notes.add("method-types：$duplicateMethodKeys 条同名方法定义被遮蔽（只记 defIndex 最大的生效者）")
        }

        // 波 0 出口判据⑤的对拍：侧车 `method-types` 若被忠实读回，喂给 `buildExprCallTable`
        // 必须得到**与全量编译同一张** `ExprCallTable`（四个桶逐一相同）。这里直接比桶归属：
        // 同一份 `scan.funcTypes` 派生的两张视图（内存里的 exprCalls / 落盘的 methodTypes）
        // 若有一处分歧，就说明侧车口径与编译器口径已经分叉 —— 补丁编译会按错的通道降级。
        // 只留痕不改退出码（这是内部一致性读数，不是用户错误）。
        var bucketMismatch = 0
        for ((k, t) in methodTypes) {
            val ok = when (t) {
                BootMetadata.MethodType.INT -> k in exprCalls.intMethods
                BootMetadata.MethodType.STR -> k in exprCalls.strMethods
                BootMetadata.MethodType.UNKNOWN -> k in exprCalls.unknownMethods
            }
            if (!ok) bucketMismatch++
        }
        if (bucketMismatch > 0) {
            notes.add(
                "method-types：$bucketMismatch 条与 buildExprCallTable 的桶归属不一致" +
                    "（侧车口径已与编译器分叉，补丁编译会按错通道降级）",
            )
        }

        // 读数（不参与成败判定）：`param-defaults` 的键是否都落在函数表内。
        // 读侧暂不做这条**跨段**引用完整性闸（坏档与「合法的边界情形」在这里分不开，
        // 而误杀会让整份侧车装不上，比缺一个默认值严重得多）。先测出真产物的读数，
        // 将来若确认恒成立，再带着证据把闸加进读侧。
        val paramDefaults = callDefaults.build()
        if (paramDefaults.isNotEmpty()) {
            val metaKeys = HashSet<String>(metas.size)
            for (m in metas) metaKeys.add(m.key.uppercase(Locale.ROOT))
            val dangling = paramDefaults.keys.filter { it !in metaKeys }
            if (dangling.isNotEmpty()) {
                notes.add(
                    "param-defaults：${dangling.size} 个函数键不在函数表内（" +
                        dangling.take(5).joinToString(" | ") + "）",
                )
            }
        }

        // ---- U8 警告旗标闸（出厂值下三闸全开 = 现有产物零漂移）----
        // W1 DisplayWarningLevel（spec: ParserMediator.cs:54/:111/:132 `level >= Config.DisplayWarningLevel`
        //    才入 warningList；EmueraConsole.cs:3066 PrintWarning 同口径）。LoadWarning 无 level 位
        //    （core-vm 禁触不改），沿用 CompileResult.warnings 的既有 KDoc 口径：语句/重复定义/预扫描
        //    告警按 level 1 收、#DIM 折叠告警按 level 2 收。出厂值 1 ⇒ 两级全过；用户改 2 ⇒ 只留 level 2；
        //    改 3+ ⇒ 全静默（failures/parseFailures 不动 —— 原版 isError 标线在闸前完成，错误面不受此键影响）。
        val fnfGated = ArrayList<LoadWarning>()
        if (displayWarningLevel > 1) loadWarnings.clear()
        if (displayWarningLevel > 2) dimWarnings.clear()
        // W2 WarnBackCompatibility（spec: ParserMediator.cs:132 `(!isBackComp || Config.WarnBackCompatibility)`）：
        //    erbcc 现有告警里 backcompat 类只有 REPEAT 次数 ≤0（core-vm ArgBuilders.kt:178/:387，zhs 文案与
        //    emuera-zhs.xml:478 RepeatCountLessthan0 逐字同源；C# isBackComp:true @ ArgumentParser.cs:1221；
        //    其余 4 个 backcomp 告警点 erbcc 未实现对应文案）。LoadWarning 无类别位（core-vm 禁触），
        //    只能按固定文案识别 —— 语句层告警的 message 带「命令名:」前缀（见本处实测
        //    `REPEAT命令:检测到…`），故按后缀 contains 匹配；日后 StmtWarning/LoadWarning
        //    若加类别位，这里换按位闸（登记在交付报告）。
        if (!warnBackCompatibility) {
            loadWarnings.removeAll { it.message.contains(BACKCOMP_REPEAT_COUNT_NONPOSITIVE) }
        }
        // W3 FunctionNotFoundWarning（spec: ErbLoader.cs:956-993 printFunctionNotFoundWarning 的旗标分派：
        //    IGNORE=只计数、ONCE=每文件首个撞点告警、DISPLAY/LATER=逐点告警（LATER 在 C# switch 无分支，
        //    落默认 flag=false，与 DISPLAY 同为逐点告警）；汇总行门在 ErbLoader.cs:904-907：
        //    「ignoredFNFWarningCount>0 且 DisplayWarningLevel<=2 且 FunctionNotCalledWarning != IGNORE」
        //    —— 原版把 FNF 汇总挂在 FNC 键上，这里原样复刻）。出厂值 IGNORE ⇒ 零告警、零 notes 增行。
        //    撞点集合来自 known 构造处的语句级预收集；计数口径差：erbcc 记唯一目标 × 首撞点，
        //    C# 按调用点逐个计数。
        var fnfSuppressed = 0 // C# ignoredFNFWarningCount：flag=true 的撞点个数（:984-990）
        when (fnfFlagOrdinal) {
            FNF_IGNORE -> fnfSuppressed = fnfSites.size // 全部压制
            FNF_ONCE -> {
                val seenFiles = HashSet<String>()
                for ((target, site) in fnfSites) {
                    if (site.first !in seenFiles) {
                        seenFiles.add(site.first)
                        fnfGated.add(LoadWarning(site.first, site.second, NOT_DEFINED_FUNC_FMT.format(target)))
                    } else {
                        fnfSuppressed++ // 同文件后续撞点走 flag=true 分支（:968-977）
                    }
                }
            }
            else -> { // DISPLAY(3) / LATER(1) / 解析后落在 switch 默认分支的值：逐点告警
                for ((target, site) in fnfSites) {
                    fnfGated.add(LoadWarning(site.first, site.second, NOT_DEFINED_FUNC_FMT.format(target)))
                }
            }
        }
        if (displayWarningLevel > 2) {
            fnfGated.clear()
            fnfSuppressed = 0
        }
        val emittedWarnings: List<LoadWarning> = if (fnfGated.isEmpty()) loadWarnings else loadWarnings + fnfGated
        // W3 汇总行（console 面 = notes，不进 warningList —— 原版走 output.PrintError 直打控制台，
        // spec: ErbLoader.cs:904-907；文案 emuera-zhs.xml:589 IgnoredUndefinedFuncCall）。
        // 门 = 压制计数 > 0（IGNORE 全压制 / ONCE 同文件后续），唯一目标数为 erbcc 能观测的
        // 计数上限（口径差见上）。
        if (fnfSuppressed > 0 && displayWarningLevel <= 2 && fncFlagOrdinal != FNF_IGNORE) {
            notes.add("警告Lv2:已忽略${fnfSuppressed}个调用了未定义函数的警告")
        }

        val bootMetadata = BootMetadata(
            labels = labelPcs,
            dataSections = dataSections,
            globalDims = globalDimDecls,
            privateDims = privateDims,
            functions = metas,
            fileByKey = fileByKey,
            dimParseConfig = dimCfg,
            // M11-E warnmeta：装载期告警清单随侧车 `load-warnings` 段落盘（恒非 null ——
            // 编译管线就是告警通道，零告警也写 `load-warnings 0`，与「无通道」的 null 区分）。
            // 内容与顺序 = [CompileResult.warnings] 逐条直存（不重排、不去重），供设备侧读回。
            // U8 闸后清单（出厂值 = 原 loadWarnings 原样；W3 命中时未定义 CALL 告警按 level 2 追加在尾）。
            loadWarnings = emittedWarnings.map { BootMetadata.LoadWarning(it.file, it.line, it.message) },
            // R7 波 0：两张补丁编译必需的表（空表整段省略，见 BootMetadata 的类 KDoc）。
            paramDefaults = paramDefaults,
            methodTypes = methodTypes,
        )
        // CMP-65：notes 的消费面。这条管线是 CLI/Gradle `compileCorpus` 的生产入口，
        // 但 notes（合成定义、未定义 CALL 空体桩、源文件读失败、预扫描告警、二进制跳过）
        // 此前全 main 源 0 引用 —— 生产出来就丢了。这里在收尾统一打印计数 + 样例到 stderr
        // （与 `Main.cmdCompile` 打印 failures / dimWarnings 同一可见面；不改退出码语义：
        // notes 是留痕，不是失败）。manifest 侧另有 `build-mode` / `binary-skipped` 读数，
        // 受限档可用 `fileLimit` 区分（见 [CompileStats.fileLimit]）。
        if (notes.isNotEmpty()) {
            System.err.println("notes ${notes.size} 条（留痕，不参与成败判定；至多列 20 条）:")
            notes.take(20).forEach { System.err.println("  $it") }
        }
        return CompileResult(
            program, symbolTable, sourceFiles, failures + parseFailures, notes, dimWarnings, stats,
            bootMetadata, emittedWarnings, dimLayout = layout,
        )
    }

    /** 合成事件入口的 `position` 占位（与 core-vm 装置 RealCorpusCompile 同串）。 */
    private const val EVENT_ENTRY_POSITION = "·event-entry·"

    // ------------------------------------------------------------------
    // U8 警告旗标接线（erbcc 编译警告面）的常量与读数辅助
    // ------------------------------------------------------------------

    /**
     * U8 W2：REPEAT 次数 ≤0 的 backcompat 告警文案（= core-vm `ArgBuilders.kt:178` 的常量，
     * zhs 文案与 C# `Lang.Error.RepeatCountLessthan0`（emuera-zhs.xml:478）逐字同源；
     * C# 发射点 ArgumentParser.cs:1221，`isBackComp: true`、level 0）。
     * LoadWarning 无类别位，[compile] 的 W2 闸按此固定文案识别 backcompat 类。
     */
    private const val BACKCOMP_REPEAT_COUNT_NONPOSITIVE = "检测到REPEAT重复的次数为0或以下(eramaker会抛出一个错误)"

    /**
     * U8 W3：未定义函数告警文案（zhs emuera-zhs.xml:520 `NotDefinedFunc`；C# ErbLoader.cs:1619
     * `string.Format(Lang.Error.NotDefinedFunc.Text, constStr)` 同格式 —— 名字不带 `@`，模板自带）。
     */
    private const val NOT_DEFINED_FUNC_FMT = "没有找到指定的函数\"@%s\""

    /** DisplayWarningFlag 底层序数（DisplayWarningFlag.cs:3-9）：IGNORE=0/LATER=1/ONCE=2/DISPLAY=3。 */
    private const val FNF_IGNORE = 0
    private const val FNF_ONCE = 2

    /**
     * DisplayWarningFlag 的条目读数折序数：constant 可能是常量名，也可能是十进制数字串
     * （ConfigValue.kt:80-86 —— Enum.TryParse 对数字串只看装不装得下，不查已定义常量）。
     * 名字大小写不敏感（C# `Enum.TryParse(ignoreCase: true)`）；未知名/未知数字按 C# switch
     * 的默认行为折成「逐点告警」（-1 → W3 的 else 分支）。
     */
    private fun displayFlagOrdinal(constant: String): Int {
        val name = constant.trim().uppercase(Locale.ROOT)
        name.toIntOrNull()?.let { return it }
        return when (name) {
            "IGNORE" -> 0
            "LATER" -> 1
            "ONCE" -> 2
            "DISPLAY" -> 3
            else -> -1
        }
    }

    // U8 十键里**判定不接**的六键（接线三键 DisplayWarningLevel / WarnBackCompatibility /
    // FunctionNotFoundWarning 见 [compile] 收尾段 W1-W3；本段是判定索引，改动前先读锚点）：
    //
    // - FunctionNotCalledWarning：本仓只作 FNF 汇总行的门（ErbLoader.cs:904-907，已按该口径读键）；
    //   「从未被调用的函数」告警本体（ErbLoader.cs:810-860 统计 + :900-903 汇总）不在 erbcc ——
    //   批量编译不建调用图，无对象可报，不接。
    // - IgnoreUncalledFunction：同一条统计的开关（ErbLoader.cs:831），同上无对象可报，不接。
    // - WarnNormalFunctionOverloading：原版门在 ErbLoader.cs:443（`!IsEvent && (键 || Program.AnalysisMode)`）；
    //   erbcc 的批量编译对应 Program.AnalysisMode（Program.cs:87-91），该模式下门恒开 ⇒ 重复定义
    //   告警保持无条件（口径与 TODO 见 core-vm Symbols.kt 的 duplicateFunctionWarnings KDoc），不另接。
    // - ChangeMasterNameIfDebug：唯一消费点 EmueraConsole.cs:1687 的 DebugCommand（Win32 调试台），
    //   移植侧无对应面（DevTools 是另一条通道），不接。
    // - DisplayReport：装载报表面（Process.cs:232/242/265/277/293/313/329、ErbLoader.cs:910-913）
    //   在移植侧没有逐项报表输出；唯一可见面「装载等待屏是否显示 LOADLABEL」已由 app 层
    //   LoadLabelTitle 消费（CONFIG-WIRE U4）。报表本体不复刻 ⇒ 半接。
    // - ReduceArgumentOnLoad：仅 ArgumentParser.cs:44/58/125/235/248/284 三构建器的装载期
    //   Restructure 常量折叠，本仓有意不做这一遍（stmt/ArgBuilders.kt 类头）⇒ 纯数据不接。

    /**
     * 全局 `#DIMS CONST` 的**字符串**常量表（名大写 → 值）。
     *
     * 消费点：`DimParseConfig.strConstants` → [DimParser] 的字符串初值段
     * （spec: `UserDefinedVariableData.cs:405-416`：初值经 `Restructure` 折成
     * `SingleStrTerm` 才写进 `DefaultStr`，否则 `InitialValueOnlyConst`）。
     * 语料实形：`ステータス表示関連/グラフィック特殊効果.ERH:19`
     * `#DIMS CONST EFFNAME_SPLIT = ","` → 同目录 `.ERB:64`（函数体内）
     * `#DIMS CONST C_SPLITWORD = EFFNAME_SPLIT`。后者在 [collectPrivateDims] 里
     * **编译期**解析；表缺席时它按「初值不是常量」失败、侧车不留声明，运行到使用点
     * 才报「未定义的变量」（原版装载期就能折，因为 ERH 先于 ERB 装载）。
     *
     * 口径与运行期 `SystemVariableBootstrap.install` 同源同构：
     * - 输入 = 同一份全局 `#DIM/#DIMS` 原文（[globalDimDecls]：ERH 与 ERB 函数外，
     *   按切片遍历序），剥行内注释的规则也走同一函数
     *   （`LexicalAnalyzer.cs:919-929`）；
     * - 逐轮 `DimParser.parse(isPrivate = false)`，失败的行留到下一轮
     *   （原版重试队列 `ErhLoader.cs:315-321`；整轮无进展才放弃，`:334-337`）；
     * - 只收**标量** CONST（`dims.size == 1 && totalSize == 1`）：数组 CONST 按下标
     *   读取、不能折叠为单值（同 install 的 `rememberConstant`，spec:
     *   `UserDefinedVariableData.cs:374-403` 的逐元素 `DefaultStr`）。
     * 整型常量在同一遍里照常累加：字符串初值可以引用整型常量（`@"%C_NUM%"` 的插值
     * 折叠走 [DimParseConfig.intConstants]），不累加会让这类声明永远等不到解析。
     *
     * 只喂侧车 `dimParseConfig.strConstants`；不改 [CompileStats]、不改 `.erbc` 字节。
     *
     * ## 失败口径（不再静默吞）
     *
     * 解析失败按 [SystemVariableBootstrap.isUnresolvedConstant] 分成两类，与运行期
     * [SystemVariableBootstrap.install] 同源（spec: `ErhLoader.cs:315-321` 重试环）：
     * - 「不能求值为整数常量/常量」= 引用了尚未折叠出的常量 → 留到下一轮重试；
     *   整轮无进展仍剩的行记告警「依赖的常量无法解析」（同 install 的收尾口径，
     *   spec: `ErhLoader.cs:324-325` 无进展后的 `ParserMediator.Warn(…, 2)`）；
     * - 其余 [CodeEE]（声明末尾多余内容一类）→ 立即记 level 2 告警并出队，不再无意义
     *   重试（原版对非 IdentifierNotFound 的 [CodeEE] 就是 `Warn` 后消费掉，
     *   spec: `ErhLoader.cs:328-331`）；非 [CodeEE] 异常同样记告警（原版会炸出装载，
     *   编译器侧不中断整个语料编译，但必须留痕）。
     * 告警进 [warnings]（→ [CompileResult.dimWarnings]），不影响常量表内容——
     * 失败的声明本来就折不出常量，提前出队与重试穷尽后的表逐键相同。
     */
    private fun foldStringConstants(
        decls: List<BootMetadata.RawDim>,
        cfg: DimParseConfig,
        warnings: MutableList<CompileFailure>,
    ): Map<String, String> {
        val ints = LinkedHashMap<String, Long>(cfg.intConstants)
        val strs = LinkedHashMap<String, String>(cfg.strConstants)
        var pending = decls
        while (pending.isNotEmpty()) {
            val round = cfg.copy(intConstants = ints, strConstants = strs)
            val next = ArrayList<BootMetadata.RawDim>()
            for (d in pending) {
                val split = SystemVariableBootstrap.splitDeclaration(
                    SystemVariableBootstrap.stripComment(d.raw),
                ) ?: continue
                val spec = try {
                    DimParser.parse(split.first, split.second, isPrivate = false, config = round)
                } catch (e: CodeEE) {
                    val message = e.message ?: "解析失败"
                    // 引用了尚未出现的常量 → 下一轮再试；其余错误按原版 Warn 后出队。
                    if (SystemVariableBootstrap.isUnresolvedConstant(message)) {
                        next.add(d)
                    } else {
                        warnings.add(
                            CompileFailure(
                                d.file, d.line,
                                "#DIM 解析失败（level 2）: $message | ${d.raw.take(120)}",
                                // 数值取的是**声明行** d.line（0 基 Statement.line 同基），不是 e.line：
                                // DimParser 的 CodeEE 位置字段在这里不参与读数，来源如实标声明行。
                                FailLineSource.STATEMENT,
                            ),
                        )
                    }
                    continue
                } catch (e: Exception) {
                    warnings.add(
                        CompileFailure(
                            d.file, d.line,
                            "#DIM 解析失败（level 2）: ${e::class.simpleName}: ${e.message} | ${d.raw.take(120)}",
                            FailLineSource.STATEMENT,
                        ),
                    )
                    continue
                }
                if (!spec.isConst || spec.isRef) continue
                if (spec.dims.size != 1 || spec.totalSize != 1) continue
                val key = spec.name.uppercase(Locale.ROOT)
                if (spec.isStr) {
                    spec.defaultsStr?.firstOrNull()?.let { strs.putIfAbsent(key, it) }
                } else {
                    spec.defaultsInt?.firstOrNull()?.let { ints.putIfAbsent(key, it) }
                }
            }
            if (next.size == pending.size) break // 整轮无进展（spec: ErhLoader.cs:334-337）
            pending = next
        }
        // 重试穷尽仍剩的行：与 install 的收尾同口径记告警（spec: ErhLoader.cs:324-325）。
        for (d in pending) {
            warnings.add(
                CompileFailure(
                    d.file, d.line,
                    "#DIM 解析失败（level 2）: 依赖的常量无法解析（#DEFINE 宏表或前置 CONST 缺失） | ${d.raw.take(120)}",
                    FailLineSource.STATEMENT,
                ),
            )
        }
        return strs
    }

    /**
     * 用户全局 `#DIM` 的尺寸并进 VARSIZE 折叠快照。
     *
     * 原版 ERH 边装边往 `IdentifierDictionary` 注册（`IdentifierDictionary.cs:429`），
     * 后声明的声明能看到先声明的用户全局；而 [DimVarSizes.systemSnapshot] 只有
     * `VariableSize.csv` 的系统表。4.981 语料实形：`DLC/DLC.ERH:60` 的
     * `#DIM SAVEDATA DLC_Toggle, 200, 10` 不在册，`NEWGAME/NEWGAME_MAKE.ERB` 与
     * `DLC/OPTIONPRINT.ERB` 的 `#DIM ...,VARSIZE("DLC_Toggle")` 在布局面与侧车
     * 双双被丢弃，运行到「游戏前的选择设定」报「未定义的变量 可选择的DLC」。
     *
     * 多轮 [SystemVariableBootstrap.install] 处理链式引用（全局 A 的尺寸引用全局 B）：
     * 每轮把上一轮成功注册的 spec 并进快照再解析，条目数不再增长即收敛。
     * 失败口径与运行期 install 同源（同一份宏/常量/SAVEDATA 配置）—— 这里解析不出的
     * 声明运行期同样解析不出，故不额外造告警。
     *
     * a59cd7ff 首创时此处是本地实现；现在委托
     * [SystemVariableBootstrap.mergedVarSizes]（运行期 GameSession 装配全局 `#DIM` 也要
     * 做同款并入，两侧共用一份实现——快照语义两处抄早晚抄出分叉）。
     */
    private fun mergeGlobalUserDimSizes(
        decls: List<BootMetadata.RawDim>,
        systemSizes: Map<String, LongArray>,
        keywordTables: Map<String, Map<String, Int>>,
        consoleWidth: Int,
        macros: Map<String, String>,
        consts: Map<String, Long>,
        saveInBinary: Boolean,
        maxRounds: Int = 4,
    ): Map<String, LongArray> =
        SystemVariableBootstrap.mergedVarSizes(
            base = systemSizes,
            declarations = decls.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
            config = DimParseConfig(
                macros = macros,
                intConstants = consts,
                systemSaveInBinary = saveInBinary,
            ),
            keywordTables = keywordTables,
            consoleWidth = consoleWidth,
            maxRounds = maxRounds,
        )

    /**
     * 私有 `#DIM`（含 `#DIMF` 浮点声明）：解析后与所属函数键配对。**被遮蔽的非事件重名定义
     * 不注册**（`VariableStore.declareUserVar` 的「只给胜者注册」口径，spec:
     * LabelDictionary.cs:60-68）。
     *
     * 解析口径与 core-vm 装置 `RealCorpusCompile.parseDim` 逐字一致：剥行内注释
     * （`LexicalAnalyzer.cs:919-929`）→ 切关键字 → `DimParser.parse(isPrivate = true)`；
     * 失败的那条不进侧车（运行期用到时按「未定义的变量」暴露，与装置同口径），但**必须
     * 记 level 2 告警**进 [warnings]——原版函数内 `#DIM` 解析异常走
     * `ParserMediator.Warn(…, 2)`（spec: `LogicalLineParser.cs:303-307`），不再静默吞。
     * 定位只能到函数 `@` 行（[Slice.dimLines] 只存原文、不带各自行号），与既有
     * 「@切片含解析失败行」告警的定位粒度一致。
     */
    private fun collectPrivateDims(
        units: List<Unit>,
        cfg: DimParseConfig,
        warnings: MutableList<CompileFailure>,
    ): List<BootMetadata.PrivateDim> {
        val out = ArrayList<BootMetadata.PrivateDim>()
        for (u in units) {
            val s = u.slice ?: continue
            if (s.poisoned) continue
            // 被遮蔽的非事件重名定义不会被 CALL 到，它的私有变量也不该注册（否则同名键冲突）
            if (!u.def.isEvent && u.sigKey != u.def.key) continue
            for (raw in s.dimLines) {
                val spec = try {
                    parseDim(raw, cfg)
                } catch (e: CodeEE) {
                    warnings.add(
                        CompileFailure(
                            s.rel, s.line,
                            "#DIM 解析失败（level 2）: ${e.message ?: "解析失败"} | ${raw.take(120)}",
                            // 私有 #DIM 只能定位到函数 `@` 行（Slice.dimLines 只存原文、不带行号，
                            // 见本函数 KDoc）⇒ 来源标切片头行，不假装是声明行。
                            FailLineSource.SLICE_HEAD,
                        ),
                    )
                    continue
                } catch (e: Exception) {
                    warnings.add(
                        CompileFailure(
                            s.rel, s.line,
                            "#DIM 解析失败（level 2）: ${e::class.simpleName}: ${e.message} | ${raw.take(120)}",
                            FailLineSource.SLICE_HEAD,
                        ),
                    )
                    continue
                } ?: continue
                out.add(BootMetadata.PrivateDim(u.def.key, spec))
            }
        }
        return out
    }

    /**
     * `#DIM`/`#DIMS`/`#DIMF` 原文 → 私有的 `DimSpec`；非 DIM 行返回 null，解析失败抛
     * [CodeEE]/[RuntimeException]（由调用方记 level 2 告警，不再 `runCatching` 吞掉）。
     *
     * `#DIMF`（4.981 fork 浮点声明）与 `#DIM` 同构，只换 `dimtype`
     * （`UserDefinedVariable.cs:74`；spec: variables.yaml:105-111），因此共用同一切分与
     * `DimParser.parse`（其内部按关键字切整数/浮点分支）。切分走 [splitDimDeclaration]，
     * 它委托 [SystemVariableBootstrap.splitDeclaration]（装载期与编译期同一份关键字集）。
     *
     * [cfg] 与运行期 `SystemVariableBootstrap.install` 拿到的配置同源（同一个
     * `sizeConsts` + 同一张宏表 + 同一张字符串常量表，后者由 [foldStringConstants]
     * 按 install 的不动点口径折出；折叠回调由调用方经 `DimFoldSnapshot.apply` 挂上
     * （内部即 `withPureStaticDimFolders`，三件套整面：varSizes + 关键字表 + 条宽），
     * 否则装载期可折叠函数（POWER 一类）会按「非 SingleTerm」方向失败——与原版
     * `FunctionMethodTerm.cs:35-56` 分叉），侧车里的尺寸/初值求值不能与安装期分叉。
     */
    private fun parseDim(raw: String, cfg: DimParseConfig): com.eratw.port.core.vars.DimSpec? {
        val split = splitDimDeclaration(SystemVariableBootstrap.stripComment(raw))
            ?: return null
        return DimParser.parse(split.first, split.second, isPrivate = true, config = cfg)
    }

    /**
     * `#DIM`/`#DIMS`/`#DIMF` 行 → (keyword, body)；其余返回 null。
     *
     * 唯一口径是 [SystemVariableBootstrap.splitDeclaration]（BOM / 空格 / TAB 剥除，
     * 关键字归一固定 `Locale.ROOT`，CMP-44：裸 uppercase 在 tr/az 上会把 i 映射成带点字母）。
     */
    private fun splitDimDeclaration(raw: String): Pair<String, String>? =
        SystemVariableBootstrap.splitDeclaration(raw)

    /**
     * PRINTDATA/STRDATA 数据节：语句实例 → 组列表 → **语句池下标**。
     *
     * 为什么必须换成池下标：`.erbc` 装载会重建全部语句实例（`ErbcReader` 逐条
     * `readStatement`），实例身份跨进程不稳定；而语句池下标被写入端（追加序）与读取端
     * （读回序）共同保证稳定，且 marker 指令的 `a` 操作数就是它（契约 C3）。
     *
     * 语句实例取自切片 AST —— 与编译器投池的是**同一批实例**：数据节家族语句
     * （PrintDataStmt/StrDataStmt）在 `lowerStatementForPool` 里原样返回
     * （`ErbProgramCompiler.kt:1211-1213` 的「数据节语句类本遍不重写」），因此这里能按
     * 身份命中。空体桩/毒化切片里的语句不在池里，查不到即跳过（它们本来也不会被执行）。
     */
    private fun collectDataSections(
        slices: List<Slice>,
        program: Program?,
    ): Map<Int, List<List<Expr>>> {
        if (program == null) return emptyMap()
        val byInstance = IdentityHashMap<Statement, List<List<Expr>>>()
        for (s in slices) harvestDataSections(s.stmts, byInstance)
        if (byInstance.isEmpty()) return emptyMap()
        // 池序 = 追加序：按下标升序产出，Map 的顺序因此确定（写侧还会再按键排序一次）
        val out = LinkedHashMap<Int, List<List<Expr>>>()
        for (i in 0 until program.statementCount) {
            val groups = byInstance[program.statement(i)] ?: continue
            out[i] = groups
        }
        return out
    }

    /**
     * 数据节收割（口径 = core-vm 装置 `RealCorpusCompile.harvestDataSections`，与
     * GoldenPipeline 同源）：从 `PRINTDATA`/`STRDATA` 语句起，扫到 `ENDDATA` 止；
     * `DATALIST..ENDLIST` 内每行 `DATA/DATAFORM` 归一组，裸 `DATA/DATAFORM` 各成一组。
     */
    private fun harvestDataSections(stmts: List<Statement>, sink: IdentityHashMap<Statement, List<List<Expr>>>) {
        var i = 0
        while (i < stmts.size) {
            val s = stmts[i]
            val isConsumer = s is PrintDataStmt || (s is DataStmt && s.code == FunctionCode.STRDATA)
            if (!isConsumer) {
                i++
                continue
            }
            val groups = ArrayList<List<Expr>>()
            var j = i + 1
            while (j < stmts.size) {
                val t = stmts[j]
                if (t is EndBlockStmt && t.code == FunctionCode.ENDDATA) break
                if (t is DataListStmt && t.code == FunctionCode.DATALIST) {
                    val acc = ArrayList<Expr>()
                    j++
                    while (j < stmts.size) {
                        val u = stmts[j]
                        if (u is DataListStmt && u.code == FunctionCode.ENDLIST) break
                        if (u is DataStmt && (u.code == FunctionCode.DATA || u.code == FunctionCode.DATAFORM)) {
                            acc.add(dataExpr(u))
                        }
                        j++
                    }
                    groups.add(acc)
                } else if (t is DataStmt && (t.code == FunctionCode.DATA || t.code == FunctionCode.DATAFORM)) {
                    groups.add(listOf(dataExpr(t)))
                }
                j++
            }
            sink[s] = groups
            i = j + 1
        }
    }

    /** `DATA` 行的取值表达式：表达式参数优先，否则字符串字面量，再否则空串
     *  （口径 = `RealCorpusCompile.dataExpr`）。 */
    private fun dataExpr(d: DataStmt): Expr =
        (d.args as? ExpressionArgument)?.term
            ?: d.args.literalStr?.let { StrLiteral(it) }
            ?: StrLiteral("")

    /**
     * 布局面丢弃的留痕（CMP-89）：[buildFuncDims] 的 `drops` 只带 `DimSpec`，函数/行号要
     * 回到 `LabelDef.dimSpecs` 的身份表里找（同一条 spec 实例，`IdentityHashMap` 精确命中）。
     *
     * 消息形如 `#DIM 布局面丢弃（level 2）: <原因> | #DIM 名, 尺寸…`：竖线后的原文供
     * `SymbolLinkAudit` 的 `rawDimName` 提名字（与 [foldStringConstants] 的告警同一形态）。
     * 侧车面（[collectPrivateDims]）对同一行可能另有一条「解析失败」——两者不同面，都保留。
     */
    private fun reportLayoutDrops(
        drops: List<DimDrop>,
        defs: List<LabelDef>,
        sink: MutableList<CompileFailure>,
    ) {
        if (drops.isEmpty()) return
        val defBySpec = IdentityHashMap<com.eratw.port.erbcc.DimSpec, LabelDef>()
        for (d in defs) for (s in d.dimSpecs) defBySpec[s] = d
        for (drop in drops) {
            val spec = drop.spec
            val def = defBySpec[spec]
            val raw = buildString {
                append(if (spec.isStr) "#DIMS " else "#DIM ")
                append(spec.name)
                if (spec.dims.isNotEmpty()) append(", ").append(spec.dims.joinToString(", "))
            }
            sink.add(
                CompileFailure(
                    def?.file ?: "*",
                    def?.lineNo ?: -1,
                    "#DIM 布局面丢弃（level 2）: ${drop.reason} | $raw",
                    // 定位到的是**声明所在函数**的 @ 行（LabelDef.lineNo，0 基）；无定义可挂 = -1。
                    if (def != null) FailLineSource.SLICE_HEAD else FailLineSource.NO_SOURCE,
                ),
            )
        }
    }

    /**
     * 空体桩的形参占位：只用于让 `FuncSig.arity` 与声明一致（VM 按被调帧 arity
     * 复制实参并分配帧槽；arity=0 而 argCount>0 会读到帧外槽）。
     */
    private fun stubParams(def: LabelDef): List<String> {
        val n = if (def.argCount > 0) def.argCount else def.argIntCount + def.argStrCount
        return List(n) { "ARG$it" }
    }

    /**
     * `置換後 , [[置換前]]`（spec: ParserMediator.LoadEraExRenameFile）。
     * 探测 `<corpus>/../CSV/_Rename.csv`（与 erbcc Main.kt 的 scan 子命令同路径）。
     *
     * 消除与设备端 `com.eratw.port.core.frontend.RenameDictionary.load` 的实现分叉：
     * 统一走 `LogicalLineReader.readAllLines(Files.readAllBytes(candidate))`，三档判据 =
     * ① BOM 五族探测（UTF-8 / UTF-32LE / UTF-16LE / UTF-16BE / UTF-32BE，
     * spec: EncodingHandler.cs:15-41）② 无 BOM 严格 UTF-8 解码（坏字节由 decoder
     * REPORT 判失败，不静默替换）③ 非 UTF-8 回落 CP932（windows-31j）。这正是原版
     * `File.ReadAllLines(filepath, EncodingHandler.DetectEncoding(filepath))`
     * （ParserMediator.cs:75）按内容探测编码的等价形态。本模块 build.gradle.kts 已有
     * `implementation(project(":core-vm"))` 依赖，直接复用 core 读行层，不另造一份解码。
     *
     * 相较 `Files.readAllLines(Path)`（JDK 17 固定 UTF-8、无 CP932 回落、不认
     * UTF-16/32 BOM、坏字节走 REPLACE），三档能力是唯一实质差异；两份装载实现若各留
     * 一份解码，任一处 BOM / 回落 / 严格档漂移都会让 `[[置換名]]` 查不到键，把 `[[`
     * 漏给词法器（`LexicalAnalyzer.kt:552-559`，原版 `LexicalAnalyzer.cs:811-813` /
     * `:825-826`）。出厂两版 `_Rename.csv` 均为 UTF-8 BOM，改后解码结果逐字不变
     * （产物字节不变）——本改动的定位是消除实现分叉，不是修出厂语料的解码结果。
     *
     * 可见性 internal 仅供本模块定向单测端到端调用（钉三档判据），不改变生产调用面。
     */
    internal fun loadRenameDic(corpus: Path): Map<String, String>? {
        val candidate = corpus.toAbsolutePath().normalize().parent?.resolve("CSV/_Rename.csv") ?: return null
        if (!Files.isRegularFile(candidate)) return null
        // 解码实现与 erbcc scan 子命令同源，交 [decodeRenameDic] 单点持有（不复制第四种实现）。
        return decodeRenameDic(Files.readAllBytes(candidate))
    }
}
