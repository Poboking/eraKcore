package com.eratw.port.core.stmt

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LogicalLineReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * 全量语料语句层验证器（测试辅助，不进入发布代码）。
 *
 * 判定口径与 M1 词法扫描一致（specs/loader.md 附录 C）：全量目录扫描的 .ERB/.ERH，
 * 逻辑行 → 语句 AST；统计按语句码/语义类的分布、解析失败（带行号与原文）、块平衡错误。
 *
 * 语料清单经 [erbFiles]，**默认排除**混进语料根的 `GOLDEN_*.ERB` 金标采集副本。
 * 私有变量作用域由 `LineParser` 在解析 `@` 行时回调 [ScopedResolver.enterFunctionDefinition] 切换
 * （编译层 [com.eratw.port.core.compile.CorpusCompileValidator] 共用同一条路径，A13 起两侧不可能分叉）。
 *
 * 环境变量：
 * - `ERATW_CORPUS`：语料目录（如 `…/ERB`）；未设置时测试跳过。
 * - `ERATW_LIMIT`：只扫描前 N 个文件（调试用）。
 * - `ERATW_STMT_REPORT`：报告输出目录（默认 build/stmt-report，相对 gradle test JVM 的
 *   工作目录即 core-vm 模块目录，故落在 core-vm/build/stmt-report）。
 */
object CorpusValidator {

    data class Failure(val file: String, val line: Int, val name: String, val message: String, val text: String)

    class Report {
        var files = 0
        var logicalLines = 0L
        var codeLines = 0L
        var statements = 0L
        var sharpLines = 0L
        var labelLines = 0L
        val byCode = LinkedHashMap<FunctionCode, Long>()
        val byKind = LinkedHashMap<StmtKind, Long>()
        val failures = mutableListOf<Failure>()
        val blockErrors = mutableListOf<Failure>()
        var warnings = 0L
        var elapsedMs = 0L

        fun ok(): Boolean = failures.isEmpty() && blockErrors.isEmpty()
    }

    fun run(corpus: Path, limit: Int = Int.MAX_VALUE, outDir: Path? = null): Report {
        val t0 = System.currentTimeMillis()
        val report = Report()
        val files = erbFiles(corpus).take(limit)

        val renameDic = loadRenameDic(corpus)
        val pre = prescan(files)
        val resolver = ScopedResolver(pre)
        // #DEFINE 宏与生产 erbcc 同口径在词法层展开（spec: LexicalAnalyzer.cs:942-1006）；
        // 旧实现把宏名当变量登记，参数位的宏会被解析成 VarRef（运行期「未定义的变量」）。
        val parser = LineParser(resolver, lexerConfig = LexerConfig(macros = pre.macros))
        val blockChecker = BlockChecker()

        for (path in files) {
            report.files++
            val rel = corpus.relativize(path).toString().replace('\\', '/')
            val bytes = try {
                Files.readAllBytes(path)
            } catch (e: Exception) {
                report.failures.add(Failure(rel, -1, "", "读取失败: ${e.message}", ""))
                continue
            }
            if (bytes.indexOf(0.toByte()) in 0 until minOf(bytes.size, 8192)) continue // 二进制
            // TODO(verify): 本装置只拿语料目录（corpus），不读 emuera.config，没有可喂
            // LexerConfig.fromConfigStore 的 ConfigStore —— reader 仍吃出厂默认。
            // 2026-10-26 fixcore 波 A1 未接线，留待组长定补源方案。
            val reader = LogicalLineReader(LogicalLineReader.readAllLines(bytes), rel, renameDic = renameDic)
            val stmts = mutableListOf<Statement>()
            val pp = ErbPreprocess()
            while (true) {
                val line = try {
                    reader.readLogicalLine() ?: break
                } catch (e: LexError) {
                    report.failures.add(Failure(rel, e.lineNo, "", "词法错误: ${e.message}", ""))
                    continue
                }
                // spec: ErbLoader.cs:43-158 —— 预处理指令行不进语句层；禁用区间（[SKIPSTART]..[SKIPEND]/
                // 未命中的 [IF 宏]/[IF_DEBUG]）的逻辑行原版根本不交给 LogicalLineParser，
                // 其间不平衡的块语句不构成块错误（口上文件 18 处块平衡误报的根因）
                if (pp.process(line.content, pre.macroNames)) continue
                if (pp.disabled) continue
                report.logicalLines++
                val stmt = try {
                    parser.parseLogicalLine(line)
                } catch (e: Exception) {
                    report.failures.add(Failure(rel, line.lineNo, line.content.take(40), e.message ?: "解析失败", line.content))
                    continue
                } ?: continue
                report.statements++
                report.byCode.merge(stmt.code, 1L, Long::plus)
                report.byKind.merge(stmt.kind, 1L, Long::plus)
                when (stmt) {
                    is SharpStmt -> report.sharpLines++
                    is FuncLabelStmt, is GotoLabelStmt -> report.labelLines++
                    else -> report.codeLines++
                }
                // 块平衡按函数分段（块不能跨 @ 标签）；私有变量作用域由 LineParser
                // 在解析 @ 行时统一切换（见 StmtResolver.enterFunction），此处不再手动调用。
                if (stmt is FuncLabelStmt) {
                    checkBlocks(blockChecker, stmts, rel, report)
                    stmts.clear()
                }
                stmts.add(stmt)
            }
            checkBlocks(blockChecker, stmts, rel, report)
            report.warnings += parser.warnings.size
            parser.warnings.clear()
            try {
                reader.fileEnd()
            } catch (_: Exception) {
            }
        }
        report.elapsedMs = System.currentTimeMillis() - t0
        if (outDir != null) write(report, outDir)
        return report
    }

    /**
     * 语料根目录里的 `GOLDEN_*.ERB` 是金标采集副本（今日混入 ERB 根，内容与
     * `fixtures/golden/` 同源），**不是原版语料文件**：语料统计一律排除，
     * 否则与基线「3,971 文件 / 104,725 切片」口径不可比。
     * 金标自身的路径由 `GoldenCompileTest` 走 `fixtures/golden`，不经此过滤。
     */
    fun isCollectionCopy(path: Path): Boolean = path.name.startsWith("GOLDEN_", ignoreCase = true)

    /** 语料 ERB/ERH 清单（默认排除金标采集副本）。 */
    fun erbFiles(root: Path, includeCollectionCopies: Boolean = false): List<Path> =
        Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) && (it.name.endsWith(".erb", true) || it.name.endsWith(".erh", true)) }
                .filter { includeCollectionCopies || !isCollectionCopy(it) }
                .sorted().toList()
        }

    /** `置換後 , [[置換前]]`（spec: ParserMediator.LoadEraExRenameFile） */
    fun loadRenameDic(corpus: Path): Map<String, String>? {
        val candidate = corpus.toAbsolutePath().normalize().parent?.resolve("CSV/_Rename.csv")
        if (candidate == null || !Files.isRegularFile(candidate)) return null
        val map = HashMap<String, String>()
        for (line in Files.readAllLines(candidate)) {
            val t = line.trimStart('\uFEFF').trim()
            if (t.isEmpty() || t.startsWith(";")) continue
            val parts = t.split(',')
            if (parts.size == 2) map["[[${parts[1].trim()}]]"] = parts[0].trim()
        }
        return map
    }

    private fun checkBlocks(checker: BlockChecker, stmts: List<Statement>, rel: String, report: Report) {
        if (stmts.isEmpty()) return
        for (e in checker.check(stmts)) {
            report.blockErrors.add(Failure(rel, e.line, e.code.name, e.message, ""))
        }
    }

    /**
     * 符号预扫描：#DIM/#DIMS 声明 + 「带下标使用」的名字补登记（内建表未覆盖的系统变量）。
     * TODO(verify): 正式符号层（M2）就绪后由 VariableToken 全量替换本预扫描。
     */
    /**
     * 标签定义身份 =（规范化文件路径, 标签行号）。
     * 原版每个 `FunctionLabelLine` 各持一份 `privateVar`（spec: FunctionLabelLine.cs:15），
     * 同名多定义（事件成员组）互不共享（G2）。
     */
    data class FuncDefKey(val file: String, val line: Int)

    /**
     * 符号预扫描结果：全局变量表 + 每个 `@函数` 的私有变量表 + 用户函数返回类型。
     * 作用域模型依据 specs/variables.yaml#scoping（#DIM 在函数内为私有、函数外为全局）。
     *
     * [funcVars] 保留「按函数名合并」的旧视图，仅供报告归因与既有断言使用；
     * 解析期作用域一律走 [funcVarDefs]（按定义身份，见 [FuncDefKey]）。
     */
    class Prescan(
        val base: SymbolTable,
        val funcVars: Map<String, Map<String, VarInfo>>,
        val funcTypes: Map<String, OperandType>,
        /** ERH #DEFINE 宏名（大写）。[IF 宏] 判定用 —— spec: ErhLoader.cs:170/246 是 AddMacro 的全部调用点，
         *  ERB 内 #DEFINE 不注册宏（LogicalLineParser 无 DEFINE 分支），故只收 ERH。 */
        val macroNames: Set<String>,
        /** ERH #DEFINE 展开体（宏名 → 原文）；与生产 `CorpusCompile` 同一份词法层宏表。 */
        val macros: Map<String, String> = emptyMap(),
        /** 定义身份 → 该定义的私有 #DIM/#DIMS 表（spec: FunctionLabelLine.cs:98-117）。 */
        val funcVarDefs: Map<FuncDefKey, Map<String, VarInfo>> = emptyMap(),
    ) {
        /** 预扫描到的全部定义文件名（规范化 '/' 分隔）；供 [ScopedResolver] 解析相对文件名。 */
        val defFiles: Set<String> = funcVarDefs.keys.mapTo(HashSet()) { it.file }
    }

    fun prescan(files: List<Path>): Prescan {
        val base = SymbolTable()
        val funcVars = HashMap<String, LinkedHashMap<String, VarInfo>>()
        val funcVarDefs = HashMap<FuncDefKey, LinkedHashMap<String, VarInfo>>()
        val funcTypes = HashMap<String, OperandType>()
        var pendingLabel: String? = null
        var curFunc: String? = null
        var curDef: FuncDefKey? = null

        // 宏名先行收集：ERB 装载前 ERH 已全量装载（ErbLoader 前置 ErhLoader），
        // 因此 [IF 宏] 判定不依赖文件遍历序（spec: ErhLoader.cs:40-75 + 170/246）。
        val macroNames = HashSet<String>()
        val macroBodies = LinkedHashMap<String, String>()
        for (path in files) {
            if (!path.name.endsWith(".erh", true)) continue
            val lines = try {
                LogicalLineReader.readAllLines(Files.readAllBytes(path))
            } catch (e: Exception) {
                continue
            }
            for (raw in lines) {
                val line = raw.trim()
                if (!line.startsWith("#")) continue
                val body = line.substring(1).trim()
                val kw = body.takeWhile { !it.isWhitespace() }.uppercase()
                if (kw == "DEFINE") {
                    val rest = body.substring(kw.length).trim()
                    val name = rest.takeWhile { !it.isWhitespace() }
                    if (name.isNotEmpty()) {
                        macroNames.add(name.uppercase())
                        macroBodies[name] = rest.substring(name.length).trim().substringBefore(';').trim()
                    }
                }
            }
        }

        fun declare(name: String, type: OperandType, dim: Int, chara: Boolean, const: Boolean) {
            val info = VarInfo(name, type, dim, chara, const)
            val fn = curFunc
            if (fn != null) {
                // 旧视图：按函数名合并（last-wins），仅保留给报告归因/既有断言
                funcVars.getOrPut(fn!!.uppercase()) { LinkedHashMap() }[name.uppercase()] = info
                // 解析期真实作用域：按定义身份分开（G2，spec: FunctionLabelLine.cs:15/98-117）
                curDef?.let { funcVarDefs.getOrPut(it) { LinkedHashMap() }[name.uppercase()] = info }
            } else {
                base.declareVariable(name, type, dim, chara, const)
            }
        }

        for (path in files) {
            val lines = try {
                LogicalLineReader.readAllLines(Files.readAllBytes(path))
            } catch (e: Exception) {
                continue
            }
            curFunc = null
            curDef = null
            val absFile = path.toAbsolutePath().normalize().toString().replace('\\', '/')
            val pp = ErbPreprocess()
            var idx = 0
            while (idx < lines.size) {
                var line = lines[idx].trim()
                var logicalLineNo = idx // 0 基物理行号（LogicalLine.lineNo 口径）
                idx++
                if (line.isEmpty()) continue
                // 行连续块 { } 被 EraStreamReader 拼成**一条**逻辑行
                // （spec: EraStreamReader.cs:130-184）。块内首行可以是 `#DIM…`
                // （如 `{\n#DIMS CONST DISP_MEMO = …\n}`），拼后仍按首字符分类。
                if (line == "{") {
                    val sb = StringBuilder()
                    while (idx < lines.size) {
                        val t = lines[idx].trim()
                        idx++
                        if (t == "}") break
                        sb.append(t)
                    }
                    line = sb.toString().trim()
                    // 原版合并后的逻辑行行号 = 闭合 '}' 所在物理行（spec: EraStreamReader.cs:130-184）
                    logicalLineNo = idx - 1
                    if (line.isEmpty()) continue
                }
                // spec: ErbLoader.cs:43-158 —— 预处理指令与禁用区间的行不进符号预扫描
                if (pp.process(line, macroNames)) continue
                if (pp.disabled) continue
                // 物理行里的 `@"..."` 是 {} 续行中的 FORM 串，不是函数标签（spec: ErbLoader.cs:388-427）
                if (line.startsWith("@") && line.length > 1 && (line[1].isLetter() || line[1] == '_')) {
                    pendingLabel = line.substring(1)
                        .takeWhile { it != '(' && it != ',' && it != ';' && !it.isWhitespace() }
                    curFunc = pendingLabel
                    // LogicalLine.lineNo 是 0 基物理行号（spec: EraStreamReader.cs:30）
                    curDef = FuncDefKey(absFile, logicalLineNo)
                    continue
                }
                if (line.startsWith("#")) {
                    val body = line.substring(1).trim()
                    val kw = body.takeWhile { !it.isWhitespace() }.uppercase()
                    val rest = body.substring(kw.length).trim()
                    if (kw == "FUNCTION" || kw == "FUNCTIONS") {
                        val label = pendingLabel
                        if (label != null) {
                            funcTypes[label.uppercase()] =
                                if (kw == "FUNCTIONS") OperandType.STR else OperandType.INT
                        }
                        continue
                    }
                    if (kw == "DIM" || kw == "DIMS") {
                        var head = rest
                        var isConst = false
                        var isChara = false
                        var isRef = false
                        var changed = true
                        while (changed) {
                            changed = false
                            for (mod in MODIFIERS) {
                                // 修饰符后允许任意空白（含 TAB/全角空格）。原版按 WordCollection 的
                                // IdentifierWord 边界分词，空白种类无关（spec: UserDefinedVariableData.cs:54-58
                                // 关键字循环；语料实况 UpdateMenus.ERB:216 `#DIM DYNAMIC\tOPERATE_MODE`）
                                if (head.length > mod.length && head.startsWith(mod) && head[mod.length].isWhitespace()) {
                                    head = head.substring(mod.length).trim()
                                    if (mod == "CONST") isConst = true
                                    if (mod == "CHARADATA") isChara = true
                                    if (mod == "REF") isRef = true
                                    changed = true
                                }
                            }
                        }
                        val upHead = head.uppercase()
                        if (upHead.contains("CHARADATA")) isChara = true
                        if (upHead.startsWith("CONST")) isConst = true
                        val name = head.takeWhile { it != ',' && it != '=' && it != ';' && !it.isWhitespace() }.trim()
                        if (name.isEmpty()) continue
                        // `#DIM X = 1, 25, 50` 的逗号后是初始值，不是维度（spec: UserDefinedVariableData.cs:344-427）
                        // 先切掉行尾注释，再切初始值（注释里的逗号不是维度）
                        val tail = head.substring(name.length).substringBefore(';').substringBefore('=')
                        // 维度计数规则（spec: UserDefinedVariableData.cs:300-343）：
                        // - 一般变量：每个 ',' 后必须跟一个可求值的整数表达式（ReduceIntegerTerm →
                        //   SingleLongTerm，cs:325-341）——尺寸可以是 #DEFINE 宏、ERH #DIM CONST 等
                        //   常量标识符（装载期 ERH 先于 ERB，ConstantData 已就绪）；空段非法但验证器
                        //   宽松跳过（原版 cs:321-323 HasNotExpressionAfterComma）。
                        // - REF 变量：每个 ',' 恰贡献一个 0 尺寸（cs:309-319），Dimension = 逗号数
                        //   （如 `#DIM REF 集合, ,` → 2 维）。
                        val dims = if (isRef) {
                            tail.count { it == ',' }.coerceIn(1, 3)
                        } else {
                            tail.split(',').map { it.trim() }.count { it.isNotEmpty() }.coerceIn(1, 3)
                        }
                        declare(
                            name,
                            if (kw == "DIMS") OperandType.STR else OperandType.INT,
                            dims,
                            isChara, isConst,
                        )
                        continue
                    }
                    if (kw == "DEFINE") continue
                    continue
                }
            }
        }
        // 不再登记两类幽灵（与生产 `CorpusScan.scan` 同口径，2026-10-23）：
        // - `#DEFINE` 宏名：宏已在词法层展开（[Prescan.macros] 喂给 LineParser）；
        // - 赋值左值：原版没有「裸用即声明」，赋值目标未声明就是装载期未定义标识符。
        //   旧兜底会把 `口:0 =` / `集合:人数:0 =` 这类函数内私有名登记成全局变量，
        //   抢走 `STAIN:…:口` / `TALENT:…:集合` 的 CSV 关键字（解析期变量表优先于关键字表）。
        return Prescan(
            base = base,
            funcVars = funcVars,
            funcTypes = funcTypes,
            macroNames = macroNames,
            macros = macroBodies,
            funcVarDefs = funcVarDefs,
        )
    }

    /**
     * ERB 预处理状态机（[SKIPSTART]/[SKIPEND]/[IF 宏]/[ELSEIF]/[ELSE]/[ENDIF]/[IF_DEBUG]/[IF_NDEBUG]）。
     * 与 tools/erbcc Symbols.scanLabels 同一实现口径（spec: ErbLoader.cs:43-158）：
     * - 指令在 Disabled 期间仍被处理（嵌套区间正确开闭）；
     * - [SKIPEND]/[ENDIF] 与栈顶不匹配时按容错忽略（原版同行为）；
     * - `[[...]]` 转义形态不是指令。
     * DebugMode=false：[IF_DEBUG] 区间禁用、[IF_NDEBUG] 区间启用（与 erbcc 默认一致）。
     */
    internal class ErbPreprocess {
        var disabled = false
            private set
        private var done = false
        private val ppMatch = ArrayList<String>()
        private val disabledStack = ArrayList<Boolean>()
        private val doneStack = ArrayList<Boolean>()

        /** 处理一条逻辑行（content 需已 trim）。返回 true = 该行是预处理指令，调用方跳过。 */
        fun process(content: String, macros: Set<String>): Boolean {
            if (!content.startsWith("[") || content.startsWith("[[")) return false
            val body = content.substring(1).substringBefore(']')
            val parts = body.trim().split(Regex("\\s+"), 2)
            val kw = parts.firstOrNull()?.uppercase() ?: ""
            val arg = parts.getOrNull(1)?.trim()?.uppercase() ?: ""
            when (kw) {
                "SKIPSTART" -> {
                    ppMatch.add("SKIPEND"); disabledStack.add(disabled); doneStack.add(done)
                    disabled = true; done = false
                }
                "SKIPEND" -> {
                    if (ppMatch.isNotEmpty() && ppMatch.removeAt(ppMatch.size - 1) == "SKIPEND") {
                        disabled = disabledStack.removeAt(disabledStack.size - 1)
                        done = doneStack.removeAt(doneStack.size - 1)
                    }
                }
                "IF", "IF_DEBUG", "IF_NDEBUG" -> {
                    ppMatch.add("ELSEIF"); disabledStack.add(disabled); doneStack.add(done)
                    disabled = when (kw) {
                        "IF" -> arg !in macros       // GetMacro(arg)==null（ErbLoader.cs:61-96）
                        "IF_DEBUG" -> true
                        else -> false
                    }
                    done = !disabled
                }
                "ELSEIF" -> {
                    if (ppMatch.isNotEmpty()) { ppMatch.removeAt(ppMatch.size - 1); ppMatch.add("ELSEIF") }
                    disabled = done || arg !in macros
                    done = done || !disabled
                }
                "ELSE" -> {
                    if (ppMatch.isNotEmpty()) { ppMatch.removeAt(ppMatch.size - 1); ppMatch.add("ENDIF") }
                    disabled = done; done = true
                }
                "ENDIF" -> {
                    if (ppMatch.isNotEmpty() && ppMatch.removeAt(ppMatch.size - 1) in setOf("ENDIF", "ELSEIF")) {
                        disabled = disabledStack.removeAt(disabledStack.size - 1)
                        done = doneStack.removeAt(doneStack.size - 1)
                    }
                }
                else -> return false
            }
            return true
        }
    }

    /**
     * 作用域解析器：#DIM 私有变量优先于全局/内建（spec: specs/variables.yaml#scoping）。
     *
     * 作用域切换**不再由本类的使用方手动调用**：`LineParser` 解析到 `@` 行时自动回调
     * [enterFunctionDefinition]（spec: IdentifierDictionary.cs:429-447），所以语句层与编译层
     * 共用同一份状态机、不可能分叉（A13 缺陷根因：编译层漏调 enter，函数内 `#DIM` 私有名
     * 全部按未定义标识符拒绝）。
     *
     * **G2（按定义而非函数名）**：原版每个 `FunctionLabelLine` 各持一份 `privateVar`
     * （spec: FunctionLabelLine.cs:15 + 98-117），同名多定义（事件成员组）互不共享。
     * 本类按 [FuncDefKey]（文件 + 标签行号）取表，[enterFunction] 仅保留旧的按名视图
     * 供非定义感知的调用点使用。
     */
    class ScopedResolver(private val pre: Prescan) : DefinitionScopedResolver {
        private var cur: Map<String, VarInfo> = emptyMap()

        /** 当前定义的私有表（可变副本，供 VARI/VARS 即时登记；spec: LogicalLineParser.cs:471/508）。 */
        private var curVars: LinkedHashMap<String, VarInfo>? = null

        /** 相对文件名 → 预扫描的规范化绝对路径（每个文件只解析一次）。 */
        private val fileAlias = HashMap<String, String>()

        override fun enterFunction(functionName: String?) {
            cur = functionName?.let { pre.funcVars[it.uppercase()] } ?: emptyMap()
            curVars = null
        }

        override fun enterFunctionDefinition(functionName: String?, filename: String, line: Int) {
            val key = FuncDefKey(canonicalFile(filename), line)
            val vars = LinkedHashMap(pre.funcVarDefs[key] ?: emptyMap())
            curVars = vars
            cur = vars
        }

        override fun declarePrivateVariable(name: String, type: OperandType, dimensions: IntArray) {
            val vars = curVars ?: return
            vars[name.uppercase()] = VarInfo(
                name = name,
                operandType = type,
                dimension = dimensions.size.coerceIn(1, 3),
                isCharacterData = false,
                // VARI/VARS 没有 CONST 修饰（spec: LogicalLineParser.cs:463-470 / 500-507）
                isConst = false,
            )
        }

        /** 兼容旧调用点（等价于 [enterFunction]；解析器已自动调用，正常代码不必再调）。 */
        fun enter(functionName: String?) = enterFunction(functionName)

        /**
         * 把逻辑行里的文件名（语料相对路径，如 `BODY_INFO/X.ERB`）映射到预扫描用的
         * 绝对路径。两个验证器分别传 `corpus.relativize(path)` 与绝对路径，故做后缀匹配。
         */
        private fun canonicalFile(filename: String): String {
            val norm = filename.replace('\\', '/').removePrefix("./")
            fileAlias[norm]?.let { return it }
            val hit = if (norm in pre.defFiles) {
                norm
            } else {
                pre.defFiles.firstOrNull { it.endsWith("/$norm") } ?: norm
            }
            fileAlias[norm] = hit
            return hit
        }

        override fun resolveVariable(name: String, subKey: String?): VarInfo? =
            cur[name.uppercase()] ?: pre.base.resolveVariable(name, subKey)

        override fun resolveFunction(name: String): OperandType? =
            pre.funcTypes[name.uppercase()] ?: pre.base.resolveFunction(name)

        override fun isUserMethod(name: String): Boolean = pre.base.isUserMethod(name)
    }

    private val MODIFIERS = listOf("CONST", "REF", "DYNAMIC", "STATIC", "GLOBAL", "SAVEDATA", "CHARADATA")

    // -----------------------------------------------------------------------
    // 报告
    // -----------------------------------------------------------------------

    fun write(report: Report, outDir: Path) {
        Files.createDirectories(outDir)
        val sb = StringBuilder()
        sb.appendLine("# 语句层全量验证报告")
        sb.appendLine()
        sb.appendLine("- 文件数: ${report.files}")
        sb.appendLine("- 逻辑行: ${report.logicalLines}")
        sb.appendLine("- 解析出的语句: ${report.statements}（代码行 ${report.codeLines} / 标签行 ${report.labelLines} / # 行 ${report.sharpLines}）")
        sb.appendLine("- 解析失败: ${report.failures.size}")
        sb.appendLine("- 块平衡错误: ${report.blockErrors.size}")
        sb.appendLine("- 告警: ${report.warnings}")
        sb.appendLine("- 耗时: ${report.elapsedMs} ms")
        sb.appendLine()
        sb.appendLine("## 按语义类分布")
        sb.appendLine()
        sb.appendLine("| kind | 数量 |")
        sb.appendLine("|---|---|")
        for ((k, v) in report.byKind.entries.sortedByDescending { it.value }) sb.appendLine("| $k | $v |")
        sb.appendLine()
        sb.appendLine("## 按语句码分布（TOP 40）")
        sb.appendLine()
        sb.appendLine("| code | 数量 |")
        sb.appendLine("|---|---|")
        for ((k, v) in report.byCode.entries.sortedByDescending { it.value }.take(40)) sb.appendLine("| $k | $v |")
        sb.appendLine()
        appendFailures(sb, "解析失败分组", report.failures)
        appendFailures(sb, "块平衡错误分组", report.blockErrors)
        Files.writeString(outDir.resolve("summary.md"), sb.toString())
    }

    private fun appendFailures(sb: StringBuilder, title: String, list: List<Failure>) {
        sb.appendLine("## $title")
        sb.appendLine()
        if (list.isEmpty()) {
            sb.appendLine("无")
            sb.appendLine()
            return
        }
        val groups = list.groupBy { it.message }
        for ((msg, items) in groups.entries.sortedByDescending { it.value.size }.take(30)) {
            sb.appendLine("- **${items.size}** × `$msg`")
            for (it in items.take(3)) sb.appendLine("    - ${it.file}:${it.line}  `${it.text}`")
        }
        sb.appendLine()
    }
}
