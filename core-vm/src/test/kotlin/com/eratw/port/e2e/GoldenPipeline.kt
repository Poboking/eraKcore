package com.eratw.port.e2e

import com.erbcc.bridge.buildExprCallTable
import com.erbcc.bridge.buildFuncDims
import com.erbcc.bridge.toLocalDim
import java.time.ZoneId
import com.eratw.port.core.compile.CallDefaultsCollector
import com.eratw.port.core.compile.CompileException
import com.eratw.port.core.compile.DimSpecLayout
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.GlobalNameLayout
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.ConsolePrintedLine
import com.eratw.port.core.fn.ConsoleStyleSnapshot
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveDataCheck
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.fn.SkipState
import com.eratw.port.core.fn.UserFunctionKind
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.GraphicsSink
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.NoopGraphicsSink
import com.eratw.port.core.host.seedGoldenRuntimeSprites
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.stmt.CallStmt
import com.eratw.port.core.stmt.CorpusValidator
import com.eratw.port.core.stmt.DataListStmt
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.EndBlockStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FlowStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.ParamDefault
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.data.csv.CsvLoader
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.save.CharaFileIO
import com.eratw.port.core.sem.SystemOps
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.vars.DimFunctionFolders
import com.eratw.port.core.vars.DimVarSizes
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.erbcc.LabelDef
import com.eratw.port.erbcc.SymbolTable
import com.eratw.port.erbcc.Symbols
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap

/**
 * E2E 管线：`fixtures/golden` → erbcc 符号表（装载序）→ 语句切片 →
 * [ErbProgramCompiler]（布局 = DimSpecLayout + [com.erbcc.bridge.buildFuncDims] 局部声明）→
 * [Program] → [ProcessRuntime]/[com.eratw.port.core.code.VirtualMachine] 可执行体。
 *
 * 装载语义口径（specs/loader.md 附录 D）：
 *  - 非事件重名：**后装载者生效** → 胜者以 `<KEY>` 注册，被遮蔽者以 `<KEY>#<defIndex>` 注册
 *    （保留字节码但不可被 CALL 命中，等价原版字典覆盖）。
 *  - 事件多定义：成员全部 `<KEY>#<defIndex>`，另按 eventCallOrder 合成 `<KEY>` 入口。
 *  - `@NAME, ARG0, ARG1` 无括号形参：erbcc 的 argCount 恒 0（[com.eratw.port.erbcc.countTypedArgs]
 *    只认括号形参），故形参计数从 FuncLabelStmt.paramNames 派生（ARGS* → 字符串，其余 → 整数）。
 *
 * 分节弹性执行的"节"= GOLDEN_ROOT 依次 CALL 的各节函数（含 ROOT 的头/尾两节，
 * 编译为合成函数 `GOLDEN_ROOT#HEADER` / `GOLDEN_ROOT#FOOTER`）；一个切片编译失败
 * 不拖垮整体：失败切片落**空体桩**并记录，运行期该节报 BLOCKED。
 */
class GoldenModel(
    val fixtureDir: Path,
    val program: Program,
    val table: FunctionTable,
    val dataSections: IdentityHashMap<Statement, List<List<Expr>>>,
    val labelPcs: Map<String, Map<String, Int>>,
    val units: List<UnitDef>,
    val compileFailures: List<String>,
    val fileBySigKey: Map<String, String>,
    val userVarDecls: List<Pair<String, com.eratw.port.core.vars.DimSpec>>,
    val notes: List<String>,
    val stats: Map<String, String>,
) {
    class UnitDef(
        val sigKey: String,
        val title: String,
        val skippable: Boolean = false,
        val skipReason: String = "",
    )
}

object GoldenPipeline {

    /** 语料目录名（与仓库根同级，见 .gitignore 的同名条目）。 */
    private const val GAME_DIR_NAME = "eratw-画蛇添足版eratw4.980"

    /**
     * 游戏 CSV 目录。口径（按优先级）：
     * 1. `ERATW_GAMEDIR` 环境变量——**显式给出即权威**，即使路径不存在也不再回落（避免配置错误被静默掩盖）；
     * 2. 仓库根下的 [GAME_DIR_NAME]（工作树从别的主机整目录拷回来时，这是常态布局）；
     * 3. 旧机器的绝对路径，仅作最后兜底。
     *
     * 仓库根 = 本测试 JVM 的 cwd（模块目录 `core-vm/`）上两级。判据统一为「存在 CSV 子目录」，
     * 这样 CI/无游戏数据的环境会自然落到无数据分支（装置按 4 裸角色跑）。
     */
    fun gameCsvDir(): Path {
        val env = System.getenv("ERATW_GAMEDIR")
        if (env != null) return Path.of(env, "CSV")
        val repoRoot = Path.of("").toAbsolutePath().normalize().parent?.parent
        val candidates = buildList {
            if (repoRoot != null) add(repoRoot.resolve(GAME_DIR_NAME))
            add(Path.of("C:/DSH/eratw-安卓移植计划", GAME_DIR_NAME))
        }
        return (candidates.firstOrNull { Files.isDirectory(it.resolve("CSV")) } ?: candidates.first()).resolve("CSV")
    }

    /**
     * 开机历史行文本 = `Config.LoadLabel`：读 `CSV/_Replace.csv` 的 `起動時簡略表示`
     * （键注册 ConfigData.cs:161，缺省 `Now Loading...`；赋值 Config.cs:722 SetReplace）。
     * 键缺失/文件缺失 → 缺省值。行口径复用 [CsvLoader]（同 `;` 注释、BOM、逗号切分）。
     */
    fun loadLabel(): String {
        val path = CsvLoader.resolveFile(gameCsvDir(), "_Replace.csv") ?: return DEFAULT_LOAD_LABEL
        for (raw in CsvLoader.readEnabledLines(path)) {
            val cols = CsvLoader.splitRow(raw)
            if (cols.size < 2) continue
            if (cols[0].trim() != LOAD_LABEL_KEY) continue
            return cols[1].trim()
        }
        return DEFAULT_LOAD_LABEL
    }

    /** 游戏根目录（[gameCsvDir] 的上级）：`{root}\ERB` 即原版 `Program.ErbDir`（`{exe}\erb\`，Program.cs:210-212）。 */
    private fun gameErbDir(): java.nio.file.Path? = gameCsvDir().parent?.resolve("ERB")

    // ---- ENUMVAR* 的 VarKeys 全量名单（ENUMVARENDSWITH 对拍用，KDoc 见 buildEnumVarNameTable）----

    private val GAME_ERH_DIM_LINE = Regex("""#DIMS?\b(.*)""")
    private val GAME_ERH_TOKEN = Regex("""[^\s,=()\[\]+]+""")
    private val GAME_ERH_KEYWORDS =
        setOf("GLOBAL", "STATIC", "PRIVATE", "SAVEDATA", "CHARADATA", "CONST", "REF", "DYNAMIC")

    /**
     * ENUMVAR*（ENUMVARENDSWITH 等）的候选名全集 = 原版 `IdentifierDictionary.VarKeys`
     * （varTokenDic.Keys，IdentifierDictionary.cs:68）：
     * 1. 系统变量 = [VariableRegistry] 注册表全名（对应 varTokenDic 的构造期克隆，
     *    IdentifierDictionary.cs:164；基准里 NAME 结尾 31 个：角色 4 + CSV 常量 26 +
     *    GAMEBASE_VERSIONNAME，spec: VariableData.cs:3097-3139,3147）；
     * 2. 游戏 ERH 顶层 #DIM/#DIMS 用户变量（含 CHARADATA），ErhLoader 逐条
     *    AddUseDefinedVariable（ErhLoader.cs:261-268 → IdentifierDictionary.cs:394-399），
     *    经 [loadGameErhVarNames] 采集；
     * 3. ERB 内 #DIM/#DIMS 一律 isPrivate:true（LogicalLineParser.cs:282-283）只进函数
     *    私有表（FunctionLabelLine.AddPrivateVariable），**不进** VarKeys——fixture 的
     *    函数级声明与 ERB 文件级声明都不在基准 83 内（实证样例：TEMP_CHARA_LIST_BY_DT.ERB
     *    的文件级 `#DIMS CONST DISP_NAME` 未计入）。
     *
     * 对拍（fixture `ENUMVARENDSWITH("NAME")`，GOLDEN_10_XMLMAP.ERB:46）：
     * 31 系统 + 52 游戏 ERH = 83（pc-golden-root.txt:544）；同节
     * ENUMVARBEGINSWITH("RESULT")=2 / ENUMVARWITH("LOCAL")=2（:543/:545）只由系统
     * RESULT(S)/LOCAL(S) 贡献——游戏名经 [loadGameErhVarNames] 的坏符号口径后零碰撞。
     */
    private fun buildEnumVarNameTable(store: VariableStore): List<String> {
        val names = LinkedHashSet<String>()
        for (def in store.registry.all()) names.add(def.name)
        names.addAll(loadGameErhVarNames())
        return names.toList()
    }

    /**
     * FUNC-ENUM 家族（EXISTFUNCTION/EXISTMETH/GETMETH/GETMETHS/ENUMFUNC*）的用户函数表
     * = 原版 `Process.LabelDictionary` 的**非事件**用户标签（NoneventKeys，
     * EnumNameMethod 的 FUNCTION 数据源 FunctionMethodCreator.cs:280；
     * GetFunctionMethod 只查该表，IdentifierDictionary.cs:538-586）。
     *
     * 种类位（ExistFunctionMethod，FunctionMethodCreator.cs:8269-8280）：
     * `#FUNCTIONS`（str 方法）→ 3、`#FUNCTION`（int 方法）→ 2、普通 @label → 1；
     * 装置侧来自 [FuncMeta]：isMethod+returnsStr / isMethod / 其余。
     *
     * 表内容口径：
     * - 事件函数剔除（IsEventLabelName 不进 LabelDictionary 非事件域）；
     * - 非事件重名 = **后装载者生效**（LabelDictionary.cs:60-68 字典赋值）——
     *   [FunctionTable.all] 按 defIndex 升序，同名后者覆盖即胜者；
     * - 装置合成节（`GOLDEN_ROOT#HEADER/#FOOTER`、synthetic-event-entry）不是脚本
     *   符号，剔除（真实 ERB 名不可能含 `#`）。
     */
    private fun buildUserFunctionTable(model: GoldenModel): Map<String, UserFunctionKind> {
        val out = LinkedHashMap<String, UserFunctionKind>()
        for (m in model.table.all()) {
            if (m.isEvent || m.position == "synthetic-event-entry" || '#' in m.key) continue
            out[m.key] = when {
                m.isMethod && m.returnsStr -> UserFunctionKind.STRING_METHOD
                m.isMethod -> UserFunctionKind.INT_METHOD
                else -> UserFunctionKind.PLAIN
            }
        }
        return out
    }

    /**
     * 扫描游戏 ERB 目录全部 *.ERH 的顶层 #DIM/#DIMS 变量名。
     *
     * C# 口径链条（ENUMVAR* 的 `array` 数据源，FunctionMethodCreator.cs:283）：
     * - 装载集 = `{exe}\erb\`（Program.cs:210-212）下 `*.ERH`（ErhLoader.cs:42
     *   `Config.GetFiles(headerDir, "*.ERH")`）；递归与文件名排序来自游戏 emuera.config
     *   实测 `SEARCH SUBFOLDERS:YES` / `SORT FILENAMES:YES`（ConfigData.cs:84-85 出厂
     *   值均为 false；递归开关 Config.cs:644-651，排序 Config.cs:679-681）。
     * - 词法：`#DIM(S)` 后第一个非关键字词即变量名（UserDefinedVariableData.Create
     *   跳过 CONST/GLOBAL/STATIC/SAVEDATA/CHARADATA/REF/DYNAMIC，
     *   UserDefinedVariableData.cs:54-263）；标识符终止符集 = LexicalAnalyzer.cs:28
     *   （半角符号+空白），故全角括号属于名字本体、`;` 终止名字——同 `substringBefore(';')`。
     * - 名字再过 `CheckUserVarName` 的"含 `_` 以外符号"校验（IdentifierDictionary.cs:261-264，
     *   warnLevel 2 → UserDefinedVariableData.cs:283-288 抛 CodeEE → ErhLoader.cs:328-332
     *   按行跳过、不注册）。该字符集在反编译源丢失（IdentifierDictionary.cs:40），这里以
     *   「Unicode 标点/符号类、`_` 除外」近似：游戏数据实测命中的是
     *   ERB\Headers\AutoConst_TFLAG.ERH:94-95 两个含全角括号的 TFLAG 名，剔除后
     *   ENUMVARWITH("LOCAL")=2 与基准（pc-golden-root.txt:545）一致；52 个 NAME 结尾
     *   命中名均为纯 ASCII+下划线，不受该口径影响。
     * - `;` 注释行剔除的 C# 机制：行首 `;` 在 SkipWhiteSpace 里 Seek(End) 清空整行
     *   （LexicalAnalyzer.cs:662-675），ReadEnabledLine 见空流 continue（EraStreamReader.cs:122-127）；
     *   行尾注释在 Analyse 内同样 `;` → Seek(End) 终止（LexicalAnalyzer.cs:919-933）——
     *   名字域内等价 `substringBefore(';')`。`{…}` 续行块合并（EraStreamReader.cs:135-183，
     *   行间以 ReplaceContinuationBR 缺省 `" "` 连接，ConfigData.cs:119）：DIM 名恒在块首行，
     *   逐行扫描已覆盖（实测：CARD_SUIT/BUTTON_49_NAME/SPECNAME 等全在首行）。
     * - `_Rename.csv` 改写只针对 `[[KEY]]` 标记（EraStreamReader.cs:89、
     *   111-117），不触及 DIM 名；语料 ERH 的 DIM 行亦无 `[[`。
     *
     * 游戏目录缺失（CI 无 ERATW_GAMEDIR）→ 空表，ENUMVAR* 退化为系统名单。
     */
    private fun loadGameErhVarNames(): List<String> {
        val erbDir = gameErbDir() ?: return emptyList()
        if (!java.nio.file.Files.isDirectory(erbDir)) return emptyList()
        val files = runCatching {
            java.nio.file.Files.walk(erbDir).use { stream ->
                stream.filter { java.nio.file.Files.isRegularFile(it) }
                    .filter { it.fileName.toString().uppercase().endsWith(".ERH") }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
        val names = LinkedHashSet<String>()
        for (f in files) {
            // 变量名要么纯 ASCII（对拍查询域），要么含 CJK（不参与查询）；UTF-8 宽松解码的
            // 坏字节只会产出 U+FFFD（符号类，被下方坏符号口径剔除），与 C# 拒绝同向。
            // C# 装载经 Preload.readAllLinesDetectEncoding（Preload.cs:19-46）：UTF-8 BOM 文件
            // 走 UTF8BOMEncoding（Preload.cs:29-32），BOM 由解码器吞掉；无 BOM 时严格 UTF-8
            // 试解码（:27-35，失败回落 Shift-JIS —— 语料 153 文件全为合法 UTF-8，不触发）。
            // Kotlin String(bytes, UTF_8) 会把 BOM 变成首字符 U+FEFF（Cf 格式符，非空白，
            // trimStart 不剥）→ 首行 `#DIM…` 被 [GAME_ERH_DIM_LINE] 的 matchEntire 拒掉。
            // 实测差集 3 名全在 BOM 文件首行：FOOD.ERH:1 `#DIMS DISH_NAME`、
            // 一般依頼18_おなかすいた.ERH:1 `#DIMS CHARADATA SAVEDATA REQUEST_DISHNAME`、
            // M_KOJO_K69_1.ERH:1 `#DIMS K69_1_TNAME` —— 与基准 83=31 系统+52 游戏对齐。
            val text = runCatching {
                String(java.nio.file.Files.readAllBytes(f), Charsets.UTF_8).removePrefix("\uFEFF")
            }.getOrNull() ?: continue
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trimStart()
                if (line.isEmpty() || line.startsWith(";")) continue
                val m = GAME_ERH_DIM_LINE.matchEntire(line) ?: continue
                val body = m.groupValues[1].substringBefore(';')
                for (tok in GAME_ERH_TOKEN.findAll(body)) {
                    val t = tok.value.uppercase()
                    if (t in GAME_ERH_KEYWORDS) continue
                    if (!hasBadIdentifierSymbol(t)) names.add(t)
                    break
                }
            }
        }
        return names.toList()
    }

    /** "含 `_` 以外的符号"近似（口径依据见 [loadGameErhVarNames] KDoc）：Unicode 标点/符号类。 */
    private fun hasBadIdentifierSymbol(name: String): Boolean = name.any { ch ->
        ch != '_' && when (Character.getType(ch)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            -> true
            else -> false
        }
    }

    /** 最近一次 [newRuntime] 的用户变量注册失败清单（诊断入报告用）。 */
    val lastRegisterFailures = ArrayList<String>()

    private class Slice(
        val rel: String,
        val keyUpper: String,
        val line: Int,
        val params: List<String>,
        /** 形参项原文（含 `:n` 下标；逐位绑定用，见 `FuncLabelStmt.paramRefs`）。 */
        val paramRefs: List<String>,
        /** 逐位缺省实参默认值（编译期补位用，见 `FuncLabelStmt.paramDefaults`）。 */
        val paramDefaults: List<ParamDefault?> = emptyList(),
        val stmts: List<Statement>,
        val dimLines: List<String>,
    )

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    fun build(fixtureDir: Path): GoldenModel {
        val notes = ArrayList<String>()
        val root = fixtureDir.toAbsolutePath().normalize()

        // 1) erbcc 符号表（装载序 = SymbolTable.loadOrder；事件桶/重名规则在符号层已定）
        val symbolTable = SymbolTable.build(root)
        val allDefs = symbolTable.nameKeys.flatMap { symbolTable.defsOf(it) }.sortedBy { it.defIndex }

        // 2) 语句切片（与 corpus 验证器同口径：prescan + 预处理 + LineParser）
        val files = SymbolTable.loadOrder(root)
        val pre = CorpusValidator.prescan(files)
        val methodNames = pre.funcTypes.keys.mapTo(HashSet()) { it.uppercase() }
        // A16 接线：表达式位用户方法调用降级表（#FUNCTION=INT 才降级；#FUNCTIONS/未知保守不降级）。
        // 类型来源 = prescan 的 #FUNCTION/#FUNCTIONS 声明（LabelDef 本身无 int/str 之辨）。
        val exprCalls = buildExprCallTable(allDefs, pre.funcTypes)
        val renameDic = CorpusValidator.loadRenameDic(root)
        val slices = ArrayList<Slice>()
        // 语句层解析失败（词法/语法）—— 与生产 erbcc 同口径逐条留痕，切片作废。
        val parseFailures = ArrayList<String>()
        for (path in files) {
            val rel = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')
            // TODO(verify): 本装置跑 golden fixture（fixtureDir），没有游戏 emuera.config，
            // 无 ConfigStore 配置源 —— reader 仍吃 LexerConfig 出厂默认。
            // 2026-10-26 fixcore 波 A1 未接线，留待组长定补源方案。
            val reader = LogicalLineReader(LogicalLineReader.readAllLines(path.toFile().readBytes()), rel, renameDic = renameDic)
            val parser = LineParser(
                CorpusValidator.ScopedResolver(pre),
                lexerConfig = LexerConfig(macros = pre.macros),
            )
            val pp = CorpusValidator.ErbPreprocess()
            val body = ArrayList<Statement>()
            val dimLines = ArrayList<String>()
            var curKey: String? = null
            var curParams: List<String> = emptyList()
            var curParamRefs: List<String> = emptyList()
            var curParamDefaults: List<ParamDefault?> = emptyList()
            var curLine = -1
            // 解析失败必须让**整个切片**作废（与生产 erbcc 的 `poisoned` 同口径：
            // tools/erbcc/.../CorpusCompile.kt 的 flush()）。旧实现在 catch 里 `continue`
            // 悄悄吞掉失败行 —— 失败行若是 `@` 标签行，它后面的语句会整体落进**上一个**
            // 函数体：CORE-10 的 `@M11E_CORE02_HIT(DUMMY)`（形参未声明 ⇒ 解析期拒绝）
            // 就是这样把被调函数的 PRINTL 折进 M11E_CORE02，让 E2E 打出了假的
            // `M11E_CORE02_HIT_RAN`（函数表里根本没有这个函数）。
            var poisoned = false
            fun flush() {
                val key = curKey
                if (key == null) { body.clear(); dimLines.clear(); poisoned = false; return }
                if (!poisoned) {
                    slices.add(
                        Slice(rel, key, curLine, curParams, curParamRefs, curParamDefaults, body.toList(), dimLines.toList()),
                    )
                } else {
                    parseFailures.add("$rel:$curLine @$key 切片含解析失败行 → 空体桩")
                }
                body.clear(); dimLines.clear(); curKey = null; curParamRefs = emptyList()
                curParamDefaults = emptyList(); poisoned = false
            }
            while (true) {
                val line = try {
                    reader.readLogicalLine() ?: break
                } catch (e: LexError) {
                    parseFailures.add("$rel:${e.lineNo} 词法错误 ${e.message}")
                    poisoned = true
                    continue
                }
                if (pp.process(line.content, pre.macroNames)) continue
                if (pp.disabled) continue
                val stmt = try {
                    parser.parseLogicalLine(line)
                } catch (e: Exception) {
                    parseFailures.add("$rel:${line.lineNo} ${e.message ?: "解析失败"} | ${line.content.take(120)}")
                    poisoned = true
                    continue
                } ?: continue
                if (stmt is SharpStmt) {
                    // #DIM/#DIMS 原文留存（装载期用户变量注册 = 引擎基础状态，PC 装载器同口径）
                    if (stmt.keyword.uppercase().removePrefix("#") in setOf("DIM", "DIMS")) {
                        dimLines.add(stmt.raw.trim())
                    }
                    continue
                }
                if (stmt is FuncLabelStmt) {
                    flush()
                    curKey = stmt.name.uppercase()
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
        }

        // 3) 切片 ↔ 符号定义 对齐。行号口径有漂移（scanLabels 物理行 vs 词法逻辑行），
        //    改用 **(文件, 键) 内出现序**对齐：两侧都按文件内行序枚举，第 k 次出现对第 k 条定义。
        val queues = LinkedHashMap<Pair<String, String>, ArrayDeque<LabelDef>>()
        for (d in allDefs) queues.getOrPut(d.file to d.key) { ArrayDeque() }.addLast(d)
        var lineDriftNotes = 0
        val defBySigKey = HashMap<String, LabelDef>()
        var syntheticSeq = 100_000
        data class Work(val sigKey: String, val slice: Slice, val def: LabelDef)
        val works = ArrayList<Work>()
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
            if (def.lineNo != s.line && lineDriftNotes == 0) {
                lineDriftNotes++
                notes.add("行号口径漂移样例: ${s.rel} 符号=${def.lineNo} 词法=${s.line}（按出现序对齐，不影响装置）")
            }
            val group = symbolTable.defsOf(def.key)
            val winner = group.last()
            val sigKey = when {
                def.isEvent -> "${def.key}#${def.defIndex}"
                group.size > 1 && def.defIndex != winner.defIndex -> "${def.key}#${def.defIndex}"
                else -> def.key
            }
            works.add(Work(sigKey, s, def))
            defBySigKey[sigKey] = def
        }
        works.sortBy { it.def.defIndex }

        // 4) ROOT 的头/尾合成节（START/ROOT-INFO 与 === END ===；分节模式的第 1/最后单元）
        val rootSlice = slices.first { it.keyUpper == "GOLDEN_ROOT" }
        val callIdx = rootSlice.stmts.withIndex().filter {
            it.value is CallStmt && (it.value as CallStmt).code == FunctionCode.CALL
        }
        val headerBody = rootSlice.stmts.take(callIdx.first().index)
        val footerBody = rootSlice.stmts.drop(callIdx.last().index + 1)

        // 5) 局部声明：erbcc dimSpecs →（桥接映射器）→ LocalDim
        // CMP-40：布局面的折叠器与生产 `CorpusCompile` / 侧车同源（`CSV/VariableSize.csv`
        // 快照）。不挂折叠器时 `#DIM X, VARSIZE("...")` 在布局面折不出、整条声明被静默丢；
        // fixture 现有 #DIM 全是字面尺寸 ⇒ 本项不改变既有产物，只补齐折叠面。
        val dimFolder = DimFunctionFolders.pureStatic(DimVarSizes.systemSnapshot(gameCsvDir()))
        val funcDims = LinkedHashMap(buildFuncDims(allDefs, folder = dimFolder))
        for (d in allDefs) {
            if (d.dimSpecs.isEmpty()) continue
            val dims = d.dimSpecs.mapNotNull { it.toLocalDim(folder = dimFolder) }
            if (dims.isNotEmpty()) funcDims["${d.key}#${d.defIndex}"] = dims
        }
        val layout = DimSpecLayout(GlobalNameLayout(), funcDims)

        // 6) 全函数编译（失败 → 空体桩 + 记录）
        val known = HashSet<String>()
        works.mapTo(known) { it.sigKey }
        val eventKeys = symbolTable.nameKeys.filter { k ->
            val ds = symbolTable.defsOf(k); ds.first().isEvent && ds.size > 1
        }
        eventKeys.mapTo(known) { it }
        known += "GOLDEN_ROOT#HEADER"; known += "GOLDEN_ROOT#FOOTER"

        // 缺省实参默认值（spec: FunctionLabelLine.cs:49 / CalledFunction.cs:167）：
        // 键 = 可调用名（`CALL` 目标口径）；重名被遮蔽的定义不会被 CALL 命中，只收胜者。
        val callDefaults = CallDefaultsCollector()
        for (w in works) {
            if (w.sigKey != w.def.key) continue
            callDefaults.add(w.def.key, w.slice.paramDefaults)
        }
        if (callDefaults.functionsWithDefaults > 0 || callDefaults.unsupported.isNotEmpty()) {
            notes.add(
                "缺省实参默认值：${callDefaults.functionsWithDefaults} 个函数可编译期补位；" +
                    "非字面量 ${callDefaults.unsupported.size} 处不注入（" +
                    callDefaults.unsupported.take(5).joinToString(" | ") + "）",
            )
        }

        val compiler = ErbProgramCompiler(layout, callDefaults.build())
        val failures = ArrayList<String>()
        failures.addAll(parseFailures)
        val metas = ArrayList<FuncMeta>()
        val labels = HashMap<String, Map<String, Int>>()
        val fileByKey = HashMap<String, String>()

        fun compileWork(
            sigKey: String,
            params: List<String>,
            isMethod: Boolean,
            body: List<Statement>,
            metaOf: (Int) -> FuncMeta,
            def: LabelDef?,
            paramRefs: List<String> = params,
        ) {
            // A7：#FUNCTIONS（str 方法）的 RETURNF 走 `_RETS` 字符串通道；类型来源 =
            // prescan 的 #FUNCTION/#FUNCTIONS 声明（LabelDef 本身无 int/str 之辨）。
            val returnsStr = pre.funcTypes[def?.key?.uppercase() ?: sigKey.uppercase()] == OperandType.STR
            val fb = try {
                compiler.compileFunction(FuncSig(sigKey, params.size, isMethod, returnsStr, paramRefs), body, known, exprCalls)
            } catch (e: CompileException) {
                failures.add("$sigKey [${def?.file ?: "?"}:${def?.lineNo ?: "?"}] 编译失败: ${e.message}")
                runCatching {
                    compiler.compileFunction(FuncSig(sigKey, params.size, isMethod, returnsStr, paramRefs), emptyList(), known, exprCalls)
                }.getOrElse { err ->
                    failures.add("$sigKey 空体桩编译也失败: ${err.message} —— 该节不可执行")
                    return
                }
            } catch (e: Exception) {
                failures.add("$sigKey 编译内部错误: ${e::class.simpleName}: ${e.message}")
                return
            }
            metas.add(metaOf(fb.index))
            labels[sigKey.uppercase()] = fb.labels
            fileByKey[sigKey] = def?.file ?: "?"
        }

        for (w in works) {
            val (argInt, argStr) = argCounts(w.slice.params, w.def)
            compileWork(
                w.sigKey, w.slice.params, w.def.isMethod, w.slice.stmts,
                { idx ->
                    FuncMeta(
                        index = idx, key = w.def.key, name = w.def.name,
                        isEvent = w.def.isEvent, isMethod = w.def.isMethod,
                        isOnly = w.def.isOnly, isPri = w.def.isPri, isLater = w.def.isLater, isSingle = w.def.isSingle,
                        argIntCount = argInt, argStrCount = argStr,
                        returnsStr = pre.funcTypes[w.def.key.uppercase()] == OperandType.STR,
                        localSize = w.def.localSize, localsSize = w.def.localsSize,
                        defIndex = w.def.defIndex, position = "${w.def.file}:${w.def.lineNo}",
                    )
                },
                w.def,
                w.slice.paramRefs,
            )
        }

        // 6b) A6-BUILTIN-STUB 已删除：compileMethod 对内建方法改发语句池 marker，
        //     由 sem 的 FunctionCode.METHOD 执行并写 RESULT（2026-10-18 接线完成）。

        // 事件入口合成（装载序 = eventCallOrder：ONLY→PRI→NORMAL→LATER，同桶装载序）
        var entrySeq = allDefs.size + 10_000
        for (evKey in eventKeys) {
            val members = symbolTable.eventCallOrder(evKey).map { "${it.key}#${it.defIndex}" }
            val fb = try {
                compiler.compileEventEntry(evKey, members)
            } catch (e: Exception) {
                failures.add("事件入口 $evKey: ${e.message}")
                continue
            }
            metas.add(
                FuncMeta(
                    index = fb.index, key = evKey, name = evKey,
                    isEvent = false, isMethod = false, defIndex = entrySeq++,
                    position = "synthetic-event-entry",
                ),
            )
            labels[evKey.uppercase()] = fb.labels
            fileByKey[evKey] = "·event-entry·"
        }

        compileWork("GOLDEN_ROOT#HEADER", emptyList(), false, headerBody,
            { idx -> FuncMeta(index = idx, key = "GOLDEN_ROOT#HEADER", name = "GOLDEN_ROOT#HEADER", defIndex = entrySeq++) }, null)
        compileWork("GOLDEN_ROOT#FOOTER", emptyList(), false, footerBody,
            { idx -> FuncMeta(index = idx, key = "GOLDEN_ROOT#FOOTER", name = "GOLDEN_ROOT#FOOTER", defIndex = entrySeq++) }, null)
        fileByKey["GOLDEN_ROOT#HEADER"] = rootSlice.rel
        fileByKey["GOLDEN_ROOT#FOOTER"] = rootSlice.rel

        val program = compiler.build("GOLDEN_ROOT")

        // 6c) #DIM/#DIMS 用户变量注册表（引擎装载期基础状态；只灌**胜者**与事件成员的声明，
        //     被遮蔽定义不注册——VariableStore.declareUserVar KDoc 同口径）。
        val userVarDecls = ArrayList<Pair<String, com.eratw.port.core.vars.DimSpec>>()
        var dimDeclSkips = 0
        for (w in works) {
            val shadowedLoser = !w.def.isEvent && w.sigKey != w.def.key
            if (shadowedLoser) continue
            for (raw in w.slice.dimLines) {
                val sp = raw.indexOfFirst { it == ' ' || it == '\t' }
                if (sp < 0) continue
                val kw = raw.substring(0, sp)
                val body = raw.substring(sp + 1)
                val parsed = runCatching { com.eratw.port.core.vars.DimParser.parse(kw, body, isPrivate = true) }
                if (parsed.isSuccess) userVarDecls.add(w.def.key to parsed.getOrThrow()) else dimDeclSkips++
            }
        }
        if (dimDeclSkips > 0) notes.add("#DIM 解析失败跳过 $dimDeclSkips 条（宏尺寸/非法声明，装置按不注册处理）")
        notes.add(
            "已知上游缺口（装置如实报红，不代修）：VariableStore.setValues(:270/:284) 只查系统 registry，" +
                "帧内私有 #DIM 用户变量的批量赋值（如 `ARR = 1, 2, 3`）会抛「未定义的变量」；" +
                "标量写路径（writeInt/writeStr 经 userVar 帧解析）正常。",
        )

        // 7) 数据节（PRINTDATA/STRDATA → List<List<Expr>>，键=语句实例，C2 身份序禁去重的消费端）
        val dataSections = IdentityHashMap<Statement, List<List<Expr>>>()
        for (s in slices) harvestDataSections(s.stmts, dataSections)

        // 8) 分节单元：ROOT 头 + 依次 CALL 的 12 节 + ROOT 尾 +（人工节：跳过标记）
        val units = ArrayList<GoldenModel.UnitDef>()
        units.add(GoldenModel.UnitDef("GOLDEN_ROOT#HEADER", "ROOT-INFO"))
        for (i in callIdx) {
            val label = (rootSlice.stmts[i.index] as CallStmt).label!!.uppercase()
            units.add(GoldenModel.UnitDef(label, label))
        }
        units.add(GoldenModel.UnitDef("GOLDEN_ROOT#FOOTER", "RUN-END"))
        units.add(
            GoldenModel.UnitDef(
                "GOLDEN_INPUT_MANUAL", "INPUT-MANUAL",
                skippable = true, skipReason = "人工按键节（runbook §4.1 半人工采集），不入自动装置",
            ),
        )

        // 9) 已知接口缺口备忘（报告用）
        notes.add(
            "表达式位调用降级表已接线（A16）：intMethods=${exprCalls.intMethods.size} " +
                "strMethods=${exprCalls.strMethods.size} nonMethodLabels=${exprCalls.nonMethodLabels.size}；" +
                "returnsStr 取自 prescan 的 #FUNCTIONS 声明（REF 方法仍未支持，归 A15 遗留）",
        )
        notes.add("erbcc argCount 只识别括号形参；`@NAME, ARG0` 形态的形参计数由 paramNames 派生")

        val stats = linkedMapOf(
            "files" to files.size.toString(),
            "defs" to allDefs.size.toString(),
            "slices" to slices.size.toString(),
            "compiled" to metas.size.toString(),
            "eventEntries" to eventKeys.size.toString(),
            "instructions" to program.instructionCount.toString(),
            "globalSlots" to program.globalSlots.toString(),
            "userVarDecls" to userVarDecls.size.toString(),
        )
        return GoldenModel(
            fixtureDir = root,
            program = program,
            table = FunctionTable(metas),
            dataSections = dataSections,
            labelPcs = labels,
            units = units,
            compileFailures = failures,
            fileBySigKey = fileByKey,
            userVarDecls = userVarDecls,
            notes = notes,
            stats = stats,
        )
    }

    /** 形参计数：优先 erbcc 的括号形参口径；`@NAME, A, B$` 形态（argCount=0）按 paramNames 派生。 */
    private fun argCounts(params: List<String>, def: LabelDef): Pair<Int, Int> {
        if (def.argCount > 0) return def.argIntCount to def.argStrCount
        val str = params.count { it.uppercase().startsWith("ARGS") }
        return (params.size - str) to str
    }

    // ------------------------------------------------------------------
    // 运行装置
    // ------------------------------------------------------------------

    /**
     * [newConsole] 的**底表**（生产 [LineBufferConsoleSink] 本身）。
     *
     * 给需要读底表状态的装置用：`foregroundColor` 这类查询面在
     * [ProductionConsoleSink] 上**没有转发**（该门面只做显示行/样式快照映射），
     * 而 `SETCOLOR → PRINT_RECT` 的取色（GFX-05）只有底表知道。E2E 侧自己包一层
     * 转发即可（见 M11eExpandE2ETest 的 ForeColorConsole），不必改门面、
     * 也不影响既有 15 节的读数（同一组常量、同一个类）。
     */
    fun newConsoleBuffer(): LineBufferConsoleSink = LineBufferConsoleSink(
        clientWidth = PC_DRAWABLE_WIDTH,
        printCWidth = PC_PRINT_C_LENGTH,
        defaultFontName = PC_FONT_NAME,
    )

    /**
     * 装置的宿主控制台 = **生产 sink**（[LineBufferConsoleSink]）+ E2E 查询门面
     * （[ProductionConsoleSink]）。CONSOLE-2 残项的落地：E2E 不再走自建行缓冲路径。
     *
     * 三个环境值都取 PC 基准会话的同值，逐条取证：
     *  - `clientWidth`：原版填分隔线用 `Config.DrawableWidth`（getStBar，
     *    EmueraConsole.cs:3421-3437），而 `DrawableWidth = WindowX - DrawingParam_ShapePositionShift`
     *    （Config.cs:466）——**单位是像素**，本类的 clientWidth 单位是半角格，
     *    两者换算需要字体度量（StringMeasure.GetDisplayLength，EmueraConsole.cs:3429/3434）。
     *    装置取 199 = 基准反推值：GOLDEN_02 的三条分隔线各恰好 199 个字符
     *    （pc-golden-root.txt:253-255），同一宽度下 PRINT-C 的 300 格折成 199+101
     *    两条显示行（:215-216）。与 `GoldenTextParityTest` 的 barWidth=199 同值
     *    （那条对拍已证明 199 下两 sink 逐字节相同）。
     *  - `printCWidth` = `Config.PrintCLength`（ConfigData.cs:64，缺省即 25）；
     *    基准取证 pc-golden-root.txt:291 `PRINTCLENGTH=25`。
     *  - `defaultFontName` = 宿主环境的 `Config.FontName`：游戏 emuera.config:21
     *    「FONT NAME:黑体」（不是 ConfigData.cs:65 的出厂缺省 ＭＳ ゴシック）；
     *    基准取证 pc-golden-root.txt:280 `FONT_DEFAULT=黑体`。
     *
     * 与 [PC_CLIENT_WIDTH] 不是一回事：CLIENTWIDTH/CLIENTHEIGHT 是窗口客户区的**像素**
     * 尺寸（1800x955，基准 pc-golden-root.txt:689-690，原版 ClientWidth = MainPicBox.Width，
     * EmueraConsole.cs:231-233），这里的 199 是折行/填充用的**字符格数**。
     * TODO(verify)：真机接 `:render` 的 GlyphCache.measureCells 后，199 应由
     * 「DrawableWidth 像素 ÷ 实测半角宽」换算得出，不再写死（比例字体下 199 根本不成立）。
     */
    fun newConsole(): GoldenConsoleSink = ProductionConsoleSink(newConsoleBuffer())

    /**
     * 组装可执行运行时（契约 C6 定稿形状：consoleSink/systemOps 注入点）。
     *
     * 接口点备忘：ExecContext 的 input/timer/sound 仍是 Noop 默认（[graphics] 已开成参数，
     * M11-E 扩面用记录型 sink 断言 `PRINT_RECT` 取色）——INPUT 家族经 NoopInputSink 即取默认值，
     * GOLDEN_06 的 CanSkip 路径本就不落 waitInput，暂不影响对拍；若需要断言输入调用序列，需 A4 扩参。
     *
     * M3-A 接管新增注入（合同 §2.5）：GETCOLOR 家族样式宿主（consoleStyle ← GoldenConsoleSink
     * 状态；生产实现按 ConfigData 缺省色 + 注入的字体名提供）、SAVETEXT 家族临时目录 FileOps、
     * SemConfig.printCPerLine=5、固定 randSeed。
     * PC 环境值证据：游戏 `emuera.config`（ITEMS PER LINE FOR PRINTC:5 / FONT NAME:黑体 /
     * TEXT COLOR:192,192,192 / BACKGROUND COLOR:0,0,0 / HIGHLIGHT COLOR:255,255,0）。
     *
     * M3-B 第三波（CSV 数据层）新增：按 `ERATW_GAMEDIR`（缺省画蛇添足版目录）装载
     * 29 槽名字表 / GAMEBASE / 角色模板；有数据时预种 1 个真实角色（模板 NO 0），
     * 无数据时保持 4 个裸角色（CI 无游戏数据也能跑其余分节）。
     *
     * CONSOLE-2 残项（本波）：参数类型由假 sink 的具体类 [LineConsoleSink] **放宽**到
     * 查询面 [GoldenConsoleSink]，装置改用 [newConsole] 造的生产门面
     * （[ProductionConsoleSink] 包 [LineBufferConsoleSink]）——行模型、折行宽度、
     * PRINTC 补齐、DRAWLINE 填充、LINECOUNT 计时、临时行替换全部改由生产实现回答，
     * 与 Android 宿主将来接的是同一个类。参数放宽是为了让仍用假 sink 的两处装置
     * （`port/l3/L3HashChainTest`、`port/boot/GameBootSmoke`）不改一行照常编译。
     */
    fun newRuntime(
        model: GoldenModel,
        console: GoldenConsoleSink,
        stepCap: Long,
        /**
         * 语句层图形宿主（[ProcessRuntime] 的 `graphicsSink` 注入点）。缺省 [NoopGraphicsSink] =
         * 既有 15 节的读数不变（图形语句静默空操作）；M11-E 扩面用记录型 sink 断言
         * `PRINT_RECT` 的取色（GFX-05）。
         */
        graphics: GraphicsSink = NoopGraphicsSink,
        /**
         * 临时 ioRoot 播种钩子（建好 `ERB/` `dat/` 之后、开跑之前调用一次）。缺省 null =
         * 什么都不做（既有 15 节读数不变）；SAVE-13/SAVE-14 需要「槽位文件存在但内容坏掉」
         * 的形态，只能由装置直接落字节（SAVETEXT 的白名单只放 txt/xml/json）。
         */
        seedIoRoot: ((Path) -> Unit)? = null,
    ): ProcessRuntime {
        // CSV 数据层（目录缺失 → null，其余分节不受影响）
        val gameData = GameDataLoader.load(gameCsvDir())
        // M4-H SAVEBINARY：`VarConfig.systemSaveInBinary` 与 bootSmoke 同源取语料
        // emuera.config:60 的 YES（spec: ConfigData.cs:107 出厂默认 false；
        // 取值口径集中在 SystemVariableBootstrap.systemSaveInBinaryFrom）。
        // 本装置不装载全局 #DIM（只注册函数内私有声明，isPrivate=true 走不到
        // UserVariables.kt:384 的二进制闸门），故此值对 610 行文本无观测差；
        // 它进 StateHash 的 meta 簇 ⇒ L3 链常量需组长重落。
        val e2eSaveInBinary = SystemVariableBootstrap.systemSaveInBinaryFrom(E2E_CONFIG_OVERRIDES)
        val store = VariableStore(
            gameData?.registry ?: VariableRegistry(),
            VarConfig(systemSaveInBinary = e2eSaveInBinary),
        )
        if (gameData != null) {
            for (t in gameData.templates) store.registerTemplate(t)
            store.loadItemPrice(gameData.itemPrices)   // Item.csv 第 3 列（常量表，spec: VariableData.cs:3395-3397）
            store.reloadStrNames()                     // STR.CSV → STR 初值（csvTables[10]）
        }
        // 货币标签：PC 基准 pc-golden-root.txt:4 MONEYLABEL=￥（该行是全量对拍硬闸首次启用后
        // 的首个差异）。游戏 emuera.config 实测无货币键；C# ConfigData.cs:159 缺省为 "$"，
        // PC 实跑值 ￥ 与之不符——值来自 PC 环境的配置状态。基准为真理：宿主与 store 双侧种 ￥。
        store.moneyLabel = "￥"
        // 引擎基础状态：PC 会话恒有角色 0（FORM 三连记号 `===`→CALLNAME:PLAYER 等读它）。
        // 有 CSV 数据时预种模板 NO 0（你）——PC 基准证据 pc-golden-root.txt:408/411/420：
        // CHARANUM=4（1 既有 + ADDVOIDCHARA + ADDCHARA 0 + ADDCHARA 1）、GETCHARA0=0、
        // NAME0=你；无数据（CI）时空 CALLNAME 是装置假设，退化为 4 个裸角色。
        if (gameData != null && store.template(0L) != null) {
            store.addCharacter(0L)
        } else {
            repeat(4) { store.characters.addBare() }
        }
        // PC 会话在 golden 运行开始前池里已有 35 个运行时 Sprite（游戏自身在标题/开局阶段创建，
        // 非资源、资源表为空）——SPRITEDISPOSEALL(0) 按 `count - resourceCount` 返回个数
        // （AppContents.SpriteDisposeAll，AppContents.cs:66-78；SpriteDisposeAllMethod
        // FunctionMethodCreator.cs:7095-7108）。基准 pc-golden-root.txt:583 SPRITEDISPOSEALL=35
        // → 装置播种还原（生产路径不调用，见 seedRuntimeSprites KDoc）。
        seedGoldenRuntimeSprites(store.aux, 35)
        // 装载期 #DIM/#DIMS 用户变量注册（胜者+事件成员；重复/保留名由 declareUserVar 拒绝，跳过）
        lastRegisterFailures.clear()
        for ((fk, spec) in model.userVarDecls) {
            runCatching { store.declareUserVar(spec, fk) }
                .onFailure { lastRegisterFailures.add("$fk/${spec.name}: ${it.message}") }
        }
        // SAVETEXT/LOADTEXT/EXISTFILE/ENUMFILES 的临时目录宿主（不污染仓库）。
        // ENUMFILES 第 1 参按进程 CWD=游戏目录解析（FunctionMethodCreator.cs:354-358
        // Directory.Exists("ERB")），即游戏目录的 ERB\ 子目录 —— 装置镜像该布局，把 13 个
        // GOLDEN_*.ERB 播种到 ioRoot/ERB/（ENUM_ERB=13 对拍基准，pc-golden-root.txt:501）。
        val ioRoot = java.nio.file.Files.createTempDirectory("eratw-e2e-io")
        val erbRoot = java.nio.file.Files.createDirectories(ioRoot.resolve("ERB"))
        model.fixtureDir.toFile().listFiles { f -> f.name.endsWith(".ERB") }
            ?.forEach { java.nio.file.Files.copy(it.toPath(), erbRoot.resolve(it.name)) }
        // SAVECHARA 写 {DatDir}chara_<name>.dat；FIND_CHARADATA 列 {DatDir}chara_*.dat。
        // DatDir 缺省 "dat"（BuiltinHost.datDir），相对 E2EFileOps 的 root（ioRoot）解析，
        // 故真实建出 ioRoot/dat，并让角色存档 SystemOps 直接读写该目录。
        val datRoot = java.nio.file.Files.createDirectories(ioRoot.resolve("dat"))
        // 装置播种（缺省不播种）：坏存档 / 坏角色档这类「文件在、内容不可读」的形态
        // 没有 ERB 语句能造出来（SAVETEXT 白名单只放 txt/xml/json），只能宿主侧落字节。
        seedIoRoot?.invoke(ioRoot)
        // 存档语句与 CHKDATA 共享同一个宿主实例：SAVEDATA 记描述、CHKDATA 读回；
        // SAVEGLOBAL/LOADGLOBAL 经 baseOps 真落盘 ioRoot/sav/global.sav。
        val baseOps = E2ESystemOps(store, ioRoot)
        val sysOps: SystemOps = CharaFileSystemOps(baseOps, store, datRoot)
        // ENUMVAR* 的 VarKeys 全量名单：系统 + 游戏 ERH 顶层 #DIM（口径与对拍分解见
        // buildEnumVarNameTable KDoc）。一次构建、宿主持有，多次 ENUMVAR* 调用零重扫。
        val enumVarNameTable = buildEnumVarNameTable(store)
        // FUNC-ENUM 家族宿主表（口径见 buildUserFunctionTable KDoc）。
        val userFunctionTable = buildUserFunctionTable(model)
        // GETMETH/GETMETHS 的重入调用器要拿 [ProcessRuntime] 本体；host 先于 rt 构造，
        // 用单槽 holder 延迟绑定（调用只发生在 rt.run() 期间，届时必已赋值）。
        val rtHolder = arrayOfNulls<ProcessRuntime>(1)
        val host = object : BuiltinHost {
            override val consoleStyle: ConsoleStyleSnapshot? get() = console.styleSnapshot()
            override val fileOps: FileOps get() = E2EFileOps(ioRoot)
            override val printCPerLine: Long get() = PC_PRINT_C_PER_LINE

            // 配置覆盖（M4-F CONFIG-ENCODE）：语料 emuera.config 的两条真值 —— 码页 936
            // （:56 DEFAULT ANSI ENCODING）与 LOADTEXT/SAVETEXT 白名单（:73 VALID EXTENSIONS
            // FOR LOADTEXT AND SAVETEXT）。口径与「610 行为何不变」见 [E2E_CONFIG_OVERRIDES]。
            override val configOverrides: Map<String, String> get() = E2E_CONFIG_OVERRIDES

            // CLIENTWIDTH/CLIENTHEIGHT：PC 基准启动窗口（GOLDEN_11 头注 + runbook §6：config
            // 固定宽 1800、按启动窗口大小捕获）。原版语义 ClientWidth = MainPicBox.Width
            // （EmueraConsole.cs:231-233；ClientSizeMethod FunctionMethodCreator.cs:6789-6797）。
            // 基准 pc-golden-root.txt:599-600 CLIENTWIDTH=1800 / CLIENTHEIGHT=955。
            override val clientWidth: Long get() = PC_CLIENT_WIDTH
            override val clientHeight: Long get() = PC_CLIENT_HEIGHT

            // ENUMVARENDSWITH("NAME")=83 对拍（pc-golden-root.txt:544；fixture
            // GOLDEN_10_XMLMAP.ERB:46）：VarKeys = 系统 31 + 游戏 ERH 52。
            override val variableNames: Collection<String>? get() = enumVarNameTable

            // FUNC-ENUM 家族（EXISTFUNCTION/EXISTMETH/GETMETH/GETMETHS/ENUMFUNC*）：
            // 基准 pc-golden-root.txt:623-626 —— EXISTFUNCTION=2/3/1/0（#FUNCTION/#FUNCTIONS/
            // 普通@label/未定义）、EXISTMETH=1/2/0、GETMETH=42 GETMETH_DEF=-7、
            // GETMETHS=zS GETMETHS_DEF=DEF（fixture GOLDEN_10_XMLMAP.ERB:176-179）。
            override val userFunctions: Map<String, UserFunctionKind>? get() = userFunctionTable

            // GETMETH/GETMETHS 执行通道：重入调用用户方法（ProcessRuntime.invokeUserMethod，
            // 嵌套 VM 共享全局槽）。spec: FunctionMethodCreator.cs:8571/8613。
            override val invokeUserFunction: ((String, List<Value?>) -> Value?)?
                get() = { name, args -> rtHolder[0]?.invokeUserMethod(name, args) }

            // GETDOINGFUNCTION 数据源 = 变量层当前作用域帧（enterFunction 压栈的函数键），
            // 对应原版 GetScaningLine().ParentLabelLine.LabelName
            // （FunctionMethodCreator.cs:8450-8458）；根级（无帧）null → 查询侧按 "" 返回。
            // 基准 pc-golden-root.txt:627 GETDOINGFUNCTION=GOLDEN_XMLMAP。
            override val doingFunction: String? get() = store.currentFunction

            // LINEISEMPTY 数据源 = 打印缓冲（未 flush 的待决内容）空态。
            // spec: EmueraConsole.cs:505 EmptyLine => printBuffer.IsEmpty；
            // PrintStringBuffer.cs:39-48 IsEmpty + clearBuffer（:273-278，flush 末尾调用）。
            // 基准证据 pc-golden-root.txt:301（EMPTY=1，紧跟在 PRINTL 段头之后 → 缓冲已 flush）。
            override val lineIsEmpty: Boolean get() = console.printingBufferEmpty

            // CHKCHARADATA 的二进制头探测走 readFileBytes（BuiltinHost 缺省 null）；
            // 路径相对 ioRoot（与 E2EFileOps.resolve 同基准）解析。
            override fun readFileBytes(path: String): ByteArray? {
                val f = ioRoot.resolve(path.replace('\\', '/')).normalize()
                return if (java.nio.file.Files.exists(f)) java.nio.file.Files.readAllBytes(f) else null
            }

            // HTML 家族的控制台行缓冲（M3-B 第二波接线）：GETPRINTEDSTR/POPPRINTINGSTR/
            // GETDISPLAYLINE 的数据源 = 控制台的已提交显示行 / 打印中缓冲。
            // CONSOLE-2：生产实现下这两条来自 LineBufferConsoleSink.lines() 与
            // .popDisplayingLines()（后者按原版「空则不返回、非空则 flush 并交出行」，
            // EmueraConsole.cs:3578-3588），临时行按 EmueraConsole.cs:3053-3056 过滤，
            // HTML 段的行文本走过去标签+还原实体的可见文本（HtmlManager.cs:714-717）。
            // 对拍基准：pc-golden-root.txt:301-302（HTML_GETPRINTEDSTR/POPPRINTINGSTR）、
            // :306（GETDISPLAYLINE 取显示行 0 = 开机种子行）。
            override val consoleLines: List<ConsolePrintedLine>? get() = console.displayLines()
            override val popPrintingLines: List<ConsolePrintedLine>? get() = console.poppingLines()
            override val moneyLabel: String get() = "￥"  // PC 基准（GETCONFIG/MONEYSTR 与 %MONEYLABEL% 同源一致）

            // 时钟钉死：整套装置**唯一**的时钟出口。
            // BuiltinHost.clockMillis 就是 GETTIME 家族的注入点（core/proc/BootHosts.kt:87-88 的 KDoc
            // 原文；消费侧三处全靠它——表达式位 GETTIME / GETTIMES / GETMILLISECOND / GETSECOND
            // 见 HostMethods.kt:117-138，DT 行主键 ticks 见 MapDataTableMethods.kt:494-501，
            // 语句位 GETTIME 见 StatementExecutor.kt:642-674，本轮由「直接 LocalDateTime.now()」
            // 改为走本注入点）。缺省实现是 System.currentTimeMillis（Builtins.kt:30），
            // 于是两次运行在同一进程里也拿不到同一个钟 —— L3 状态哈希链从
            // #9 GOLDEN_MISC（GETTIME 原文进驻留池）/#10 GOLDEN_XMLMAP（DT ticks 写进
            // RESULT:1 起的 sys-int-global 格）起必然分叉。
            // 判据不受影响的依据：基准只记**长度**不记值 —— pc-golden-root.txt:516
            // `GETTIME_RESULTS_LEN=19 NUMERIC=0`（fixture GOLDEN_09_MISC.ERB:36-37 只打
            // STRLENSU(RESULTS) 与 ISNUMERIC(RESULTS)）。钉到的时刻在任何本地时区都渲染成
            // 4 位年 + 2 位月日时分秒 = 19 个半角字符、且含分隔符故 ISNUMERIC 恒 0；
            // 折算出的 ticks 仍是 18 位十进制，pc-golden-root.txt:613 的 DATA_LEN=179
            // （DT_TOXML 文本长度，位数决定）一并守住。
            override fun clockMillis(): Long = E2E_PINNED_CLOCK_MILLIS

            // 时区钉死：与 clockMillis 同一条协议的另一半（BuiltinHost.zoneId 注入点）。
            // GETTIMES 文本 / GETMILLISECOND·GETSECOND 数值 / DT 行主键 ticks 全部吃它；
            // 不钉的话 L3 链从 #10 GOLDEN_XMLMAP 起随机器时区漂移（2026-10-22 实测：
            // Asia/Shanghai 与 America/New_York 两轮链尾不同、链头相同）。
            // 钉 UTC 与 [E2E_PINNED_CLOCK_MILLIS] 的取值注记同源：基准只记长度不记值，
            // 时刻在任何时区渲染位数不变，这里换时区只改「同一时刻显示成几点」。
            override fun zoneId(): ZoneId = E2E_PINNED_ZONE_ID

            // ISSKIP/MESSKIP 数据源。装置依据：golden 语料实测 0 处 SKIP/MESSKIP 语句
            // （仅 SKIPLOG，与跳过标志无关），原版在此执行路径同样恒 false —— 基准
            // pc-golden-root.txt:307（ISSKIP=0 MESSKIP=0）。真实 Android 宿主由输入后端驱动。
            override val skipState: SkipState get() = SkipState(skipPrint = false, mesSkip = false)

            // CHKDATA 读回 GOLDEN_08 的 SAVEDATA 88（装置不写盘，用 SystemOps 的记忆体代替
            // sav/save{no}.sav 头）。
            //
            // M11-E 扩面：**记忆体未命中时交回 null**，由调用方（SaveMethods.kt:58-59）落
            // SaveDataProbe.check 走真宿主探测 —— 这是 SAVE-14（ETC_ERROR=4）与
            // SAVE-13（坏容器）唯一可达的通道。对既有 15 节无观测差：GOLDEN_08 只查槽 88
            // （SAVEDATA 88 先写记忆体 ⇒ 命中，原样 OK），而 probe 对「文件不存在」给
            // FILENOTFOUND + "----"（VariableEvaluator.cs:2162-2166），与旧分支同值同文。
            override fun checkSaveData(no: Long, kind: SaveFileKind): SaveDataCheck? {
                if (kind != SaveFileKind.NORMAL) return null // 其余类别（GLOBAL/VAR/CHARVAR）仍归 M3+
                val mes = baseOps.saveSlots[no.toInt()] ?: return null
                return SaveDataCheck(EraDataState.OK, mes)
            }
        }

        // 开机历史种子（必须在程序开跑之前、且落在 `=== GOLDEN RUN START ===` 之前）：
        // PC 会话在装载期必打一条 Config.LoadLabel —— Process.cs:277-281：
        // `if (!Config.DisplayReport) { console.PrintSingleLine(Config.LoadLabel); ... }`。
        // 它是 displayLineList 的第 0 条，故 GETDISPLAYLINE(0) 返回该文本
        // （GetDisplayLineMethod：FunctionMethodCreator.cs:8430-8438 取 DisplayLineList[i].ToString()）。
        // 基准证据 pc-golden-root.txt:306 GETDISPLAYLINE=如果出现了错误，请根据目录下的报错指导文件进行报错。
        // LoadLabel 来源 = CSV/_Replace.csv 的 `起動時簡略表示`（注册 ConfigData.cs:161，
        // 读取 Config.cs:722 SetReplace），缺省 "Now Loading..."；缺失文件/键时回落缺省。
        console.printSingleLine(loadLabel())

        val rt = ProcessRuntime(
            model.program, store, model.table,
            VmConfig(maxStepsPerRun = stepCap),
            consoleSink = console,
            systemOps = sysOps,
            semConfig = SemConfig(printCPerLine = PC_PRINT_C_PER_LINE.toInt()),
            builtinHost = host,
            graphicsSink = graphics,
            // GOLDEN_RNG 全靠 RANDOMIZE 语句重播种（GOLDEN_05_DATA.ERB:58,69,79），
            // 进程级 provider 用任意固定值保证装置可复现。
            randSeed = E2E_RAND_SEED,
        )
        rtHolder[0] = rt
        rt.installLabelTable(object : ProcessRuntime.LabelTable {
            override fun labelPc(functionKey: String, label: String): Int? =
                model.labelPcs[functionKey.uppercase()]?.get(label.uppercase())
        })
        // 数据节：侧车本身按语句池下标导出，直接以 index 为键（T2-64 波次 C）。
        rt.installDataSections(ProcessRuntime.DataSectionSource { index -> model.dataSections[model.program.statement(index)] })
        return rt
    }

    /** CodeEE → "首个失败语句 + 函数:行:pc"（语句池已接线：marker 的 a = 池下标，直取语句实例）。 */
    fun describeFailure(program: Program, model: GoldenModel, e: Throwable): String = when (e) {
        is CodeEE -> {
            val pc = e.pc
            val stmtDesc = if (pc in 0 until program.instructionCount) {
                val op = program.op(pc)
                if (!Opcode.isInternal(op) && op <= 197 && program.a(pc) in 0 until program.statementCount) {
                    val s = program.statement(program.a(pc))
                    "${s.code}@L${s.line}"
                } else {
                    Opcode.nameOf(op)
                }
            } else {
                "?"
            }
            val func = e.funcName ?: "?"
            val file = model.fileBySigKey[func.uppercase()]
                ?: model.table.nonEvent(func)?.position?.substringBefore(':')
                ?: "?"
            val srcLine = if (pc in 0 until program.instructionCount) program.lineOf(pc) else e.line
            "语句[$stmtDesc] @$file:$srcLine 函数=$func 行=${e.line} pc=$pc —— ${e.message}"
        }
        else -> "${e::class.simpleName}: ${e.message}"
    }

    // ------------------------------------------------------------------
    // PRINTDATA/STRDATA 数据节收割
    // ------------------------------------------------------------------

    private fun harvestDataSections(stmts: List<Statement>, sink: IdentityHashMap<Statement, List<List<Expr>>>) {
        var i = 0
        while (i < stmts.size) {
            val s = stmts[i]
            val isConsumer = s is PrintDataStmt || (s is DataStmt && s.code == FunctionCode.STRDATA)
            if (!isConsumer) { i++; continue }
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
                        if (u is DataStmt && (u.code == FunctionCode.DATA || u.code == FunctionCode.DATAFORM)) acc.add(dataExpr(u))
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

    private fun dataExpr(d: DataStmt): Expr =
        (d.args as? ExpressionArgument)?.term
            ?: d.args.literalStr?.let { StrLiteral(it) }
            ?: StrLiteral("")
}

// ---------------------------------------------------------------------------
// M3-A 接管：E2E IO 宿主（SAVETEXT 家族）
// ---------------------------------------------------------------------------

/** PC 基准环境 Config.PrintCPerLine（游戏 emuera.config:15 ITEMS PER LINE FOR PRINTC:5）。 */
private const val PC_PRINT_C_PER_LINE = 5L

/** PC 基准启动窗口客户区宽（pc-golden-root.txt:599 CLIENTWIDTH=1800；emuera.config 固定宽 1800，runbook §6）。 */
private const val PC_CLIENT_WIDTH = 1800L

/** PC 基准启动窗口客户区高（pc-golden-root.txt:600 CLIENTHEIGHT=955 = WindowY - LineHeight）。 */
private const val PC_CLIENT_HEIGHT = 955L

/**
 * 宿主控制台的折行/填充宽度（**半角格**）——证据与「为什么不是像素」都写在
 * [GoldenPipeline.newConsole] 的 KDoc 里（CONSOLE-2）。
 */
private const val PC_DRAWABLE_WIDTH = 199

/** Config.PrintCLength（基准 pc-golden-root.txt:291 PRINTCLENGTH=25；缺省值同源，ConfigData.cs:64）。 */
private const val PC_PRINT_C_LENGTH = 25

/** 宿主环境的 Config.FontName = 游戏 emuera.config:21「黑体」（不是 ConfigData.cs:65 的出厂缺省）。 */
private const val PC_FONT_NAME = "黑体"

/**
 * E2E 宿主的配置覆盖（M4-F CONFIG-ENCODE）：只喂「本装置已接线行为相关」的两条，
 * 值取语料真值 ——
 *  - `DEFAULT ANSI ENCODING:CHINESE_HANS`（语料 `emuera.config:56`）：STRLEN 族码页 = 936
 *    （出厂默认 JAPANESE/932，spec: `ConfigData.cs:103`）；
 *  - `VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT:xml,txt,json`（语料 `emuera.config:73`）：
 *    LOADTEXT/SAVETEXT 白名单（缺省 ["txt"]，spec: `ConfigData.cs:122-127`）；
 *  - `USE THE BINARY FORMAT FOR SAVING DATA:YES`（语料 `emuera.config:60`，M4-H SAVEBINARY）：
 *    `SystemSaveInBinary` 开关（出厂默认 false，spec: `ConfigData.cs:107`），与 bootSmoke 的
 *    全局 #DIM 声明判定同源（spec: `UserDefinedVariableData.cs:465-475`）。
 * 610 行判据不受影响的依据：fixture 里 STRLEN 族串全为 ASCII / 全角拉丁（cp932 与 cp936
 * 同宽），IO 节只用 .txt（GOLDEN_08_IO.ERB:23-25）；xml/json 只是把白名单钉成语料真值；
 * 本装置不注册全局 #DIM（私有声明 isPrivate=true 走不到二进制闸门），GETCONFIG 族 fixture
 * 也没有查这一条。它只经 [VarConfig] 进 StateHash 的 meta 簇 ⇒ L3 链常量需组长重落。
 * 其余键不喂：装置的对拍面靠显式注入（printCPerLine / 字体名 / 货币 ……），不走本表。
 */
private val E2E_CONFIG_OVERRIDES: Map<String, String> = mapOf(
    "DEFAULT ANSI ENCODING" to "CHINESE_HANS",
    "VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT" to "xml,txt,json",
    "USE THE BINARY FORMAT FOR SAVING DATA" to "YES",
)

/**
 * E2E 进程级固定 RNG 种子。
 *
 * 为什么必须"选定"而不能"任意"：DATA 节的组选**本身就消耗 RNG**——
 * `STRDATA` 取 `index = GetNextRand(dataList.Count)`（Process.cs:1808-1810 逐字：
 * `int num3 = func.dataList.Count; int index = (int)exm.VEvaluator.GetNextRand(num3);`，
 * 取中组拼 `\n` 后 `Var.SetValue` :1811-1826）；PRINTDATA 家族同构（同为组选）。
 * 而 `GOLDEN_02_PRINT.ERB:159-163`、`GOLDEN_05_DATA.ERB:16-53` 这批**非
 * RNG- 前缀段**里的 DATA 组选**没有前置 RANDOMIZE**，抽签落在进程级 provider 上：
 * PC 基准里这些行（`SDA`/`SD-B`/`D-L1`/`DKL2`/`DDL1`）是真实游戏会话（种子 =
 * `Environment.TickCount`，MTRandom.cs:25-27）留下的状态抽出来的，原则上不可复现；
 * 装置只有把这一个 32bit 种子选成能复现基准那 6 次组选的值，硬闸才可能逐行一致。
 * 校准依据（装置实测抽签序，bound>1 的 6 次）：#1→0、#6→0、#8→0(of 3)、
 * #12→1、#14→0、#15→1，与基准 SDV=SDA / D-L1 / DKL2 / DDL1 / SDV=[SD-B] 逐条相符。
 * 收到 RANDOMIZE 的段落（GOLDEN_05 `@GOLDEN_RNG`）与本种子无关——RANDOMIZE 走
 * `reseed`（VariableSizes.kt:119-122 / MTRandom.cs:30-33），其后序列只由该语句的
 * 常量种子决定（基准 RAND1=335 / RAND2=162 / RANDSEQ=2,4,2,3,1 已验证逐位一致）。
 */
private const val E2E_RAND_SEED = 20260226L

/**
 * E2E / L3 装置的**钉死时钟**（epoch 毫秒）= 2000-01-01T00:00:00Z。
 *
 * 唯一消费点是 [GoldenPipeline] 那个 `BuiltinHost` 的 clockMillis 覆写（装置侧时钟只有这一处出口；
 * 引擎侧对应的是 [com.eratw.port.core.fn.BuiltinHost.clockMillis] 注入点，
 * GETTIME 语句与 GETTIME / GETTIMES / GETMILLISECOND / GETSECOND 方法、DT 行主键 ticks 全部吃它）。
 *
 * 取值要求（逐条对着基准核，不是「随便挑一个日期」）：
 * - 本地墙上时间在任何 ±14 时区里都是 4 位年 + 2 位月/日/时/分/秒，
 *   `yyyy/MM/dd HH:mm:ss` 恒 19 个半角字符 ⇒ pc-golden-root.txt:516
 *   `GETTIME_RESULTS_LEN=19` 不变；串里恒含 `/`、`:`、空格 ⇒ 同行 `NUMERIC=0` 不变。
 *   基准那一行只记**长度**不记值，所以钉钟不改变 L2 判据（这是本覆写能存在的依据）。
 * - 折算出的 .NET ticks 仍是 18 位十进制（6.30822816e17）⇒ pc-golden-root.txt:613
 *   `DATA_LEN=179`（DT_TOXML 文本按位数计长）不变；行主键由
 *   `MapDataTableMethods.kt:498` 的 `max(ticks, lastRowId + 1)` 保证严格递增，
 *   钉死后同一次运行里多次 DT_ROW_ADD 也不会撞主键。
 * - 取 UTC 零点整而不是本地零点，是为了让「本机时区」只影响显示出的时刻、
 *   不影响上面两条不变式（跨机器复跑 L2 判据不因时区改变）。
 */
private const val E2E_PINNED_CLOCK_MILLIS = 946_684_800_000L

/**
 * E2E / L3 装置的**钉死时区** = UTC。
 *
 * 唯一消费点是 [GoldenPipeline] 那个 `BuiltinHost` 的 zoneId 覆写（与 clockMillis 同一条
 * 注入协议；GETTIME 语句与 GETTIMES / GETMILLISECOND / GETSECOND 方法、DT 行主键 ticks
 * 的「本地时区」全部吃它）。钉 UTC（固定偏移、无夏令时）让两条不变式不随机器时区变：
 * 时钟文本恒 19 字符、ticks 恒 18 位（与 [E2E_PINNED_CLOCK_MILLIS] 的取值依据同一套），
 * L3 状态哈希链从 #10 GOLDEN_XMLMAP 起不再带机器时区印记。
 */
private val E2E_PINNED_ZONE_ID: ZoneId = ZoneId.of("UTC")

/** `CSV/_Replace.csv` 里开机简略表示（Config.LoadLabel）的键名。spec: ConfigData.cs:161 */
private const val LOAD_LABEL_KEY = "起動時簡略表示"

/** Config.LoadLabel 缺省值。spec: ConfigData.cs:161 */
private const val DEFAULT_LOAD_LABEL = "Now Loading..."

/**
 * E2E 临时目录 [FileOps]：相对路径映射到一次性临时目录，**不污染仓库**。
 *
 * 播种由 [GoldenPipeline.newRuntime] 完成（13 个 GOLDEN_*.ERB → ioRoot/ERB/，ENUM_ERB=13 对拍基准）。
 * `exists` 对文件与目录均可（ENUMFILES 的 Directory.Exists 口径，spec: FunctionMethodCreator.cs:355）。
 * 原版 searchPattern 仅支持 `*` 通配（Directory.EnumerateFiles 语义），此处同口径。
 */
private class E2EFileOps(private val root: Path) : FileOps {
    override fun exists(path: String): Boolean =
        java.nio.file.Files.exists(resolve(path))

    override fun readText(path: String): String? {
        val f = resolve(path)
        return if (java.nio.file.Files.exists(f)) {
            java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8)
        } else null
    }

    override fun writeText(path: String, text: String, append: Boolean): Boolean = runCatching {
        val f = resolve(path)
        java.nio.file.Files.createDirectories(f.parent)
        val bytes = text.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        if (append) {
            java.nio.file.Files.write(f, bytes, java.nio.file.StandardOpenOption.APPEND, java.nio.file.StandardOpenOption.CREATE)
        } else {
            java.nio.file.Files.write(f, bytes)
        }
        true
    }.getOrDefault(false)

    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = runCatching {
        val base = resolve(dir)
        if (!java.nio.file.Files.isDirectory(base)) return emptyList()
        val regex = globToRegex(pattern)
        val stream = if (recursive) java.nio.file.Files.walk(base) else java.nio.file.Files.list(base)
        stream.use { s ->
            s.filter { java.nio.file.Files.isRegularFile(it) }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .filter { regex.matches(it.substringAfterLast('/')) }
                .sorted()
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun resolve(path: String): Path =
        root.resolve(path.replace('\\', '/')).normalize()

    private fun globToRegex(pattern: String): Regex =
        Regex("^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$")
}

// ---------------------------------------------------------------------------
// M3-B A8 接管：角色存档宿主（SAVECHARA / LOADCHARA）
// ---------------------------------------------------------------------------

/**
 * 在 [base] 之上真写/读 `{DatDir}chara_<name>.dat`。其余 SystemOps 能力经接口委托**透传** [base]
 * （GOLDEN_08 的 SAVEDATA 走 base 记忆体、SAVEGLOBAL/LOADGLOBAL 由 base 真落盘 sav/global.sav）。
 *
 * 二进制读写直接走 java.nio —— [FileOps] 只有文本接口，没有二进制写能力，
 * 且不新增 BuiltinHost 属性（合同约束）。目标目录 = E2E 临时 ioRoot 下的 `dat/`。
 * 文件拼名复用 [CharaFileIO.fileNameOf]，与 FIND_CHARADATA/CHKCHARADATA 的 `chara_<name>.dat` 口径一致。
 */
private class CharaFileSystemOps(
    base: SystemOps,
    private val store: VariableStore,
    private val datRoot: Path,
) : SystemOps by base {

    /** SAVECHARA：打包 [nos] 角色写盘。spec: VariableEvaluator.cs:2248-2283 */
    override fun saveChara(nos: List<Long>, filename: String, text: String) {
        val f = datRoot.resolve(CharaFileIO.fileNameOf(filename))
        java.nio.file.Files.createDirectories(f.parent)
        java.nio.file.Files.write(f, CharaFileIO.encode(store, nos, text))
    }

    /** LOADCHARA：文件不存在则早退（原版 File.Exists，:2289），否则追加角色。 */
    override fun loadChara(filename: String) {
        val f = datRoot.resolve(CharaFileIO.fileNameOf(filename))
        if (!java.nio.file.Files.exists(f)) return
        CharaFileIO.load(java.nio.file.Files.readAllBytes(f), store)
    }
}

