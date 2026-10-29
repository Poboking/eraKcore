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
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.stmt.DataListStmt
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.EndBlockStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.FuncLabelStmt
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
import com.eratw.port.erbcc.LabelDef
import com.eratw.port.erbcc.LoadWarning
import com.eratw.port.erbcc.SymbolTable
import com.eratw.port.erbcc.Symbols
import com.erbcc.bridge.DimDrop
import com.erbcc.bridge.buildExprCallTable
import com.erbcc.bridge.buildFuncDims
import com.erbcc.bridge.sizeConsts
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.io.path.name

/** 一条编译/解析失败（定位到 `文件:行号`，CLI 退出码判据用）。 */
class CompileFailure(val file: String, val line: Int, val message: String) {
    override fun toString(): String = "$file:$line $message"
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
    val symbolTable: SymbolTable,
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
     * 1. [SymbolTable.duplicateFunctionWarnings] —— 同名非事件函数重复定义
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
 * - 符号表装载序 / 标签规则：erbcc `SymbolTable.build`（spec 见 specs/loader.md 附录 D）；
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
        /** 本切片内的 `#DIM/#DIMS` 原文（函数内声明 → 私有用户变量）。 */
        val dimLines: List<String>,
        /** 本切片内有解析失败行 → 结构不可信，只进空体桩。 */
        val poisoned: Boolean,
    )

    private class Unit(val sigKey: String, val def: LabelDef, val slice: Slice?)

    /**
     * 编译整个语料目录。
     *
     * @param fileLimit 只切片前 N 个文件（调试用；报告会注明，正式产物必须全量）。
     * @param uses 非空时顺带采集全量符号使用点（[SymbolUseWalker]）。只读遍历解析结果，
     *   不改任何指令 / 池 / 统计读数；装载期 link audit 用它和运行期命名空间做差。
     */
    fun compile(corpusRoot: Path, fileLimit: Int? = null, uses: SymbolUses? = null): CompileResult {
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

        // 1) 全量符号表（装载序 = SymbolTable.loadOrder；含 ERH 宏/常量）
        val symbolTable = SymbolTable.build(root)
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
        val renameDic = loadRenameDic(root)

        // 3) 语句切片：整轮共用一个 LineParser + 一个 CompileResolver（A13 口径）
        //    #DEFINE 宏表必须喂进词法器：PC 在 token 流上展开宏（LexicalAnalyzer.cs:942-1006），
        //    `天候` / `DEF_DT_CID` 一类名字不能落到变量解析（否则运行期「未定义的变量」）。
        val slices = ArrayList<Slice>()
        // CMP-36 接线（L06 交付项）：编译期词法开关必须吃本游戏的 `emuera.config`，
        // 不能停在 [LexerConfig] 的出厂默认（原版 `LexicalAnalyzer.cs:748-752`/`:681`
        // 的 SystemAllowFullSpace / SystemIgnoreTripleSymbol 都取配置值；本作两键为 NO）。
        // 语料代码位当前全角空格 0 命中 ⇒ 产物字节预期不变（组长复跑 corpusSmoke 与重编读数确认）。
        val compileConfig = ConfigStore().also {
            EmueraConfigParser.loadChainFromDisk(root.parent ?: root, it)
        }
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
        )
        var binarySkipped = 0
        for (path in files) {
            val rel = runCatching { root.relativize(path).toString().replace('\\', '/') }
                .getOrElse { path.toString() }
            val bytes = try {
                Files.readAllBytes(path)
            } catch (e: Exception) {
                parseFailures.add(CompileFailure(rel, -1, "读取失败: ${e::class.simpleName}: ${e.message}"))
                continue
            }
            // 与语料门同口径：前 8KB 出现 NUL 视为二进制，不解析。
            // CMP-64：这里原先静默 `continue` —— 不留痕、不计数，`stats.corpusFiles` 又取
            // `allFiles.size`（跳过发生在计数之后）⇒ 产物整文件缺失仍全绿。现在按预扫侧
            // `CorpusScan.readForScan` 的口径留痕（原版对读不开的文件是 CodeEE
            // FailedOpenFile，spec: ErhLoader.cs:81-84），并进独立读数 `stats.binarySkipped`。
            // 清单口径不变：文件仍由 [sourceFiles]（走 `allFiles`）登记 —— 登记但不解析。
            val nulAt = bytes.indexOf(0.toByte())
            if (nulAt in 0 until minOf(bytes.size, 8192)) {
                binarySkipped++
                notes.add(
                    "二进制跳过（前 8KB 出现 NUL，偏移 $nulAt）：$rel —— ${bytes.size} 字节，" +
                        "不产生任何切片，产物里没有它的指令（源清单仍登记）",
                )
                continue
            }

            val reader = LogicalLineReader(
                LogicalLineReader.readAllLines(bytes),
                rel,
                // A1（2026-10-26 fixcore 波）：reader 接与上方 LineParser 同一份词法配置
                config = lexerConfig,
                renameDic = renameDic,
                // `[IF 宏]` 判定要用 ERH 宏表（spec: ErbLoader.cs:94；ERH 全量先于 ERB 装载 :40-75）
                macroNames = scan.macroNames,
            )
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
                    parseFailures.add(CompileFailure(rel, curLine, "@$key 切片含解析失败行 → 空体桩"))
                }
                body.clear(); dimLines.clear(); curKey = null; poisoned = false
                curParamDefaults = emptyList()
            }

            while (true) {
                val line = try {
                    reader.readLogicalLine() ?: break
                } catch (e: LexError) {
                    parseFailures.add(CompileFailure(rel, e.lineNo, "词法错误 ${e.message}"))
                    poisoned = true
                    continue
                }
                val stmt = try {
                    parser.parseLogicalLine(line)
                } catch (e: Exception) {
                    parseFailures.add(CompileFailure(rel, line.lineNo, "${e.message ?: "解析失败"} | ${line.content.take(120)}"))
                    poisoned = true
                    continue
                } ?: continue
                if (stmt is SharpStmt) {
                    // # 行不产生指令（#FUNCTION 已在预扫描）；但 #DIM/#DIMS 声明是装载期元数据，
                    // 必须随侧车带走：函数外的进全局原文（交 SystemVariableBootstrap.install），
                    // 函数内的进本切片（私有用户变量）。
                    val kw = stmt.keyword.uppercase(Locale.ROOT).removePrefix("#")
                    if (kw == "DIM" || kw == "DIMS") {
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
        }
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
        val dimVarSizes = DimVarSizes.systemSnapshot(csvDir)
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
                "（折叠器：VARSIZE 快照 ${dimVarSizes.size} 项 + 内置名表 ${systemKeywordTables.size} 张" +
                " + ERD 表 ${erdKeywordTables.size} 张）；丢弃 ${layoutDrops.size} 条",
        )
        val compiler = ErbProgramCompiler(layout, callDefaults.build())
        var compiled = 0
        var compileFailures = 0

        // 侧车要收的三张表（只读取编译产物，不改任何发射行为）：
        val labelPcs = LinkedHashMap<String, Map<String, Int>>()
        val fileByKey = LinkedHashMap<String, String>()
        val metas = ArrayList<FuncMeta>()

        fun compileOne(
            sigKey: String,
            def: LabelDef,
            body: List<Statement>,
            params: List<String>,
            paramRefs: List<String> = emptyList(),
        ) {
            val returnsStr = scan.funcTypes[def.key.uppercase(Locale.ROOT)] == OperandType.STR
            val arity = if (params.isNotEmpty()) params.size else maxOf(def.argCount, def.argIntCount + def.argStrCount)
            // 形参分桶（与 core-vm 装置 RealCorpusCompile.argCounts 同口径）：`#DIM` 私有变量的
            // 运行期绑定要按 ARG/ARGS 两套数组，而 `LabelDef` 的 argInt/argStr 在有括号形参时可能是 0。
            val strParam = params.count { it.uppercase(Locale.ROOT).startsWith("ARGS") }
            val argInt = if (def.argCount > 0) def.argIntCount else params.size - strParam
            val argStr = if (def.argCount > 0) def.argStrCount else strParam
            var fb: FunctionBytecode? = null
            try {
                fb = compiler.compileFunction(
                    FuncSig(sigKey, arity, def.isMethod, returnsStr, paramRefs), body, known, exprCalls,
                )
                compiled++
            } catch (e: CompileException) {
                compileFailures++
                failures.add(CompileFailure(def.file, if (e.line >= 0) e.line else def.lineNo, "[$sigKey] ${e.message}"))
                // 空体桩兜底：保留函数名供 _CALL 链接（与 RealCorpusCompile 同口径）
                try {
                    fb = compiler.compileFunction(
                        FuncSig(sigKey, arity, def.isMethod, returnsStr, paramRefs), emptyList(), known, exprCalls,
                    )
                } catch (e2: Exception) {
                    failures.add(CompileFailure(def.file, def.lineNo, "[$sigKey] 空体桩也失败: ${e2::class.simpleName}: ${e2.message}"))
                }
            } catch (e: Exception) {
                compileFailures++
                failures.add(CompileFailure(def.file, def.lineNo, "[$sigKey] 编译内部错误: ${e::class.simpleName}: ${e.message}"))
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
                    returnsStr = returnsStr,
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
                compileOne(u.sigKey, u.def, emptyList(), stubParams(u.def))
                continue
            }
            if (s.poisoned) {
                compileOne(u.sigKey, u.def, emptyList(), stubParams(u.def))
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
                failures.add(CompileFailure("*", -1, "事件入口 $evKey: ${e::class.simpleName}: ${e.message}"))
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
            failures.add(CompileFailure("*", -1, "Program 构建失败: ${e::class.simpleName}: ${e.message}"))
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
        // `USE THE BINARY FORMAT FOR SAVING DATA` 必须与运行期同一把尺（语料
        // `emuera.config:60` = YES）：它是 `#DIM ... SAVEDATA` 的登记闸
        // （spec: UserDefinedVariableData.cs:465-475）。编译期漏读会让 66 条
        // `CHARADATA SAVEDATA` 全局声明在侧车（以及私有声明）里被拒，
        // 运行时 GameSession 却按 YES 装进去 —— 审计只能看到一堆假 level-2 告警。
        val saveInBinary = runCatching {
            SystemVariableBootstrap.systemSaveInBinaryFrom(RootedBuiltinHost(gameRoot).configOverrides)
        }.getOrDefault(false)
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
            loadWarnings = loadWarnings.map { BootMetadata.LoadWarning(it.file, it.line, it.message) },
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
            bootMetadata, loadWarnings, dimLayout = layout,
        )
    }

    /** 合成事件入口的 `position` 占位（与 core-vm 装置 RealCorpusCompile 同串）。 */
    private const val EVENT_ENTRY_POSITION = "·event-entry·"

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
                        warnings.add(CompileFailure(d.file, d.line, "#DIM 解析失败（level 2）: $message | ${d.raw.take(120)}"))
                    }
                    continue
                } catch (e: Exception) {
                    warnings.add(
                        CompileFailure(
                            d.file, d.line,
                            "#DIM 解析失败（level 2）: ${e::class.simpleName}: ${e.message} | ${d.raw.take(120)}",
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
                CompileFailure(d.file, d.line, "#DIM 解析失败（level 2）: 依赖的常量无法解析（#DEFINE 宏表或前置 CONST 缺失） | ${d.raw.take(120)}"),
            )
        }
        return strs
    }

    /**
     * 私有 `#DIM`：解析后与所属函数键配对。**被遮蔽的非事件重名定义不注册**
     * （`VariableStore.declareUserVar` 的「只给胜者注册」口径，spec: LabelDictionary.cs:60-68）。
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
                    warnings.add(CompileFailure(s.rel, s.line, "#DIM 解析失败（level 2）: ${e.message ?: "解析失败"} | ${raw.take(120)}"))
                    continue
                } catch (e: Exception) {
                    warnings.add(CompileFailure(s.rel, s.line, "#DIM 解析失败（level 2）: ${e::class.simpleName}: ${e.message} | ${raw.take(120)}"))
                    continue
                } ?: continue
                out.add(BootMetadata.PrivateDim(u.def.key, spec))
            }
        }
        return out
    }

    /**
     * `#DIM ...` 原文 → 私有的 `DimSpec`；非 DIM 行返回 null，解析失败抛
     * [CodeEE]/[RuntimeException]（由调用方记 level 2 告警，不再 `runCatching` 吞掉）。
     *
     * [cfg] 与运行期 `SystemVariableBootstrap.install` 拿到的配置同源（同一个
     * `sizeConsts` + 同一张宏表 + 同一张字符串常量表，后者由 [foldStringConstants]
     * 按 install 的不动点口径折出；折叠回调由调用方经 `DimFoldSnapshot.apply` 挂上
     * （内部即 `withPureStaticDimFolders`，三件套整面：varSizes + 关键字表 + 条宽），
     * 否则装载期可折叠函数（POWER 一类）会按「非 SingleTerm」方向失败——与原版
     * `FunctionMethodTerm.cs:35-56` 分叉），侧车里的尺寸/初值求值不能与安装期分叉。
     */
    private fun parseDim(raw: String, cfg: DimParseConfig): com.eratw.port.core.vars.DimSpec? {
        val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw))
            ?: return null
        return DimParser.parse(split.first, split.second, isPrivate = true, config = cfg)
    }

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
     */
    private fun loadRenameDic(corpus: Path): Map<String, String>? {
        val candidate = corpus.toAbsolutePath().normalize().parent?.resolve("CSV/_Rename.csv") ?: return null
        if (!Files.isRegularFile(candidate)) return null
        val map = HashMap<String, String>()
        for (line in Files.readAllLines(candidate)) {
            val t = line.trimStart('\uFEFF').trim()
            if (t.isEmpty() || t.startsWith(";")) continue
            val parts = t.split(',')
            if (parts.size == 2) map["[[${parts[1].trim()}]]"] = parts[0].trim()
        }
        return map
    }
}
