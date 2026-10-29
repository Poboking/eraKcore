package com.eratw.port.erbcc.compile

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.stmt.DefinitionScopedResolver
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.erbcc.ErbPreprocessor
import com.eratw.port.erbcc.LoadWarning
import com.eratw.port.erbcc.Symbols
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * 定义身份 = （规范化绝对文件路径, 标签行的 0 基物理行号）。
 *
 * 原版每个 `FunctionLabelLine` 各持一份 `privateVar`（spec: FunctionLabelLine.cs:15 +
 * 98-117），同名多定义（事件成员组）互不共享 —— G2 口径。
 * 行号口径与 `LogicalLine.lineNo` 对齐（0 基物理行；`{}` 合并行为闭合 `}` 所在行，
 * spec: EraStreamReader.cs:130-184）。
 */
data class DefKey(val file: String, val line: Int)

/**
 * 全量语料预扫描结果（CLI 侧移植，口径 = core-vm 测试装置
 * `CorpusValidator.prescan`，见该文件 189-415 行；本 CLI 不能依赖 core-vm 的测试源）。
 *
 * 提供三件事：
 * 1. [funcTypes] —— `#FUNCTION`(INT) / `#FUNCTIONS`(STR) 的返回类型表：
 *    `FuncSig.returnsStr`（决定 RETURNF 编 `_RET` 还是 `_RETS`，合同 M3-A §3 A7）与
 *    `buildExprCallTable` 的 methodTypes 都只有这一个来源（LabelDef 已合并二者，
 *    无 int/str 之辨，spec 侧证据 Symbols.kt 的 scanLabels）。
 * 2. [privateVars] —— 按**定义身份**的私有 `#DIM/#DIMS` 表：函数内私有名（含中文名、
 *    `VARSET 私有名`）不是未定义标识符（spec: IdentifierDictionary.cs:429-447 私有表优先；
 *    漏切作用域曾把 5,345 切片误判成解析失败，A13）。
 * 3. [global] —— 全局符号表（全局 `#DIM` + `#FUNCTION` 方法名），resolveVariable/
 *    resolveFunction 的兜底层。**不登记两类幽灵**：
 *    - `#DEFINE` 宏名：宏已在词法层展开（[com.eratw.port.core.lexer.LexerConfig.macros]）；
 *    - 赋值左值（旧 `registerDest` 兜底）：它会把 `口:0 =` / `集合:人数:0 =` 这类
 *      **函数内私有名**登记成全局变量，反过来抢走 `STAIN:…:口` / `TALENT:…:集合`
 *      的 CSV 关键字（解析期命中变量表优先于关键字表，运行期「未定义的变量」）。
 *    原版没有「裸用即声明」：赋值目标未声明就是装载期 `未定义的标识符`。
 * 4. [warnings] —— 预扫描自身的**装载期告警与降级留痕**：未闭合的预处理区间、带多余
 *    参数的指令、读失败或二进制而被跳过的文件。原先一律静默 —— 原版对前者是
 *    `ParserMediator.Warn(…, 1)`（spec: ErbLoader.cs:44-48、:169-180），对后者是
 *    `CodeEE FailedOpenFile`（spec: ErhLoader.cs:81-84），都不该表现成「什么都没发生」。
 */
class CorpusScan(
    val funcTypes: Map<String, OperandType>,
    val privateVars: Map<DefKey, Map<String, VarInfo>>,
    val global: SymbolTable,
    /** ERH `#DEFINE` 宏名（大写归一）—— `[IF 宏]` 判定用（spec: ErhLoader.cs:170/246）。 */
    val macroNames: Set<String>,
    /** 预扫描期告警/降级留痕（level 1 语义：不参与成败判定，但必须可见）。 */
    val warnings: List<LoadWarning> = emptyList(),
) {
    companion object {

        /** 与 CorpusValidator.prescan 同口径的修饰符表（UserDefinedVariableData.cs:54-264）。 */
        private val MODIFIERS = listOf("CONST", "REF", "DYNAMIC", "STATIC", "GLOBAL", "SAVEDATA", "CHARADATA")

        /** `{ }` 块的连接串（与切片侧 `LogicalLineReader` 同源；见 [ErbPreprocessor.continuationSeparator]）。 */
        private val CONT_JOIN: String = ErbPreprocessor.continuationSeparator()

        /**
         * 预扫描全部语料文件（ERB + ERH）。
         *
         * 两遍结构（spec: ErbLoader.cs:413-423）：第一遍（本函数）登记私有名/全局名，
         * 第二遍（调用方的 LineParser）才解析函数体行 —— 因此私有名对整函数体可见。
         *
         * @param debugMode `Program.DebugMode`（本移植恒 false）：`[IF_DEBUG]`/`[IF_NDEBUG]`
         *   分支判据（spec: ErbLoader.cs:70/82）。
         */
        fun scan(files: List<Path>, debugMode: Boolean = false): CorpusScan {
            val funcTypes = HashMap<String, OperandType>()
            val privateVars = HashMap<DefKey, LinkedHashMap<String, VarInfo>>()
            val global = SymbolTable()
            var pendingLabel: String? = null
            // 本 @ 块内 #FUNCTION[S] 是否已接受声明（spec: LogicalLineParser.cs:161-176 ——
            // 同一 FunctionLabelLine 上重复声明 AlreadySharpDeclared 后 return false，先到者胜；
            // 跨同名定义块不在此限——每个 @ 行是独立的 FunctionLabelLine 对象）。
            var pendingDeclared = false
            var curFunc: String? = null
            var curDef: DefKey? = null
            val warnings = ArrayList<LoadWarning>()

            /** 规范化绝对路径（`DefKey.file` 与告警定位共用同一个串）。 */
            fun relOf(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/')

            // 宏名先行：ERB 装载前 ERH 已全量装载（spec: ErbLoader.cs:40-75 + 170/246），
            // 故 [IF 宏] 判定不依赖文件遍历序。
            val macroNames = HashSet<String>()
            for (path in files) {
                if (!path.fileName.toString().endsWith(".erh", ignoreCase = true)) continue
                val lines = readForScan(path, relOf(path), warnings, "宏名预扫") ?: continue
                for (raw in lines) {
                    val line = raw.trim()
                    if (!line.startsWith("#")) continue
                    val body = line.substring(1).trim()
                    // CMP-44：宏名归一必须钉 Locale.ROOT（tr/az 上裸 uppercase() 会把
                    // i/I 映射成带点/无点字母，与建表侧 Symbols.kt 的 Locale.ROOT 失配）。
                    val kw = body.takeWhile { !it.isWhitespace() }.uppercase(Locale.ROOT)
                    if (kw == "DEFINE") {
                        val name = body.substring(kw.length).trim().takeWhile { !it.isWhitespace() }
                        if (name.isNotEmpty()) macroNames.add(name.uppercase(Locale.ROOT))
                    }
                }
            }

            fun declare(name: String, type: OperandType, dim: Int, chara: Boolean, const: Boolean) {
                val info = VarInfo(name, type, dim, chara, const)
                val fn = curFunc
                if (fn != null) {
                    curDef?.let { privateVars.getOrPut(it) { LinkedHashMap() }[name.uppercase(Locale.ROOT)] = info }
                } else {
                    global.declareVariable(name, type, dim, chara, const)
                }
            }

            for (path in files) {
                val absFile = relOf(path)
                val lines = readForScan(path, absFile, warnings, "预扫描") ?: continue
                curFunc = null
                curDef = null
                val pp = ErbPreprocessor(debugMode, macroNames, absFile)
                var idx = 0
                while (idx < lines.size) {
                    var line = trimLine(lines[idx])
                    var logicalLineNo = idx          // 0 基物理行号（LogicalLine.lineNo 口径）
                    idx++
                    if (line.isEmpty()) continue
                    // 行连续块 { } 被拼成一条逻辑行（spec: EraStreamReader.cs:130-184）：
                    // 禁用区间内不拼（`ReadEnabledLine(disabled)` 跳过整段 `{}` 处理，
                    // spec: EraStreamReader.cs:96/130 + ErbLoader.cs:387），块内各行以
                    // Config.ReplaceContinuationBR（本游戏 = 一个空格）连接（:178-179）——
                    // 直接拼接会把相邻两行的标识符粘成一个 token，与切片侧分叉。
                    if (line == "{" && !pp.disabled) {
                        val sb = StringBuilder()
                        while (idx < lines.size) {
                            val t = trimLine(lines[idx])
                            idx++
                            if (t == "}") break
                            if (sb.isNotEmpty()) sb.append(CONT_JOIN)
                            sb.append(t)
                        }
                        line = sb.toString().trim()
                        logicalLineNo = idx - 1
                        if (line.isEmpty()) continue
                    }
                    // spec: ErbLoader.cs:43-158 —— 预处理指令与禁用区间的行不进预扫描
                    if (pp.process(line, logicalLineNo)) continue
                    if (pp.disabled) continue
                    // 物理行里的 `@"..."` 是 {} 续行中的 FORM 串，不是函数标签（spec: ErbLoader.cs:388-427）
                    if (line.startsWith("@") && line.length > 1 && (line[1].isLetter() || line[1] == '_')) {
                        pendingLabel = line.substring(1)
                            .takeWhile { it != '(' && it != ',' && it != ';' && !it.isWhitespace() }
                        pendingDeclared = false
                        curFunc = pendingLabel
                        curDef = DefKey(absFile, logicalLineNo)
                        continue
                    }
                    if (line.startsWith("#")) {
                        val body = line.substring(1).trim()
                        val kw = body.takeWhile { !it.isWhitespace() }.uppercase(Locale.ROOT)
                        val rest = body.substring(kw.length).trim()
                        if (kw == "FUNCTION" || kw == "FUNCTIONS") {
                            val label = pendingLabel
                            // spec: LogicalLineParser.cs:151-213 —— 三类声明无效（IsMethod 不置位）：
                            //   ① 函数名数字开头（:153-158）；② 事件/系统标签（:178-182 Depth==0，
                            //   ParseLabelLine.cs:352-362）；③ 同一标签行重复声明（:161-176 先到者胜）。
                            if (label != null && label.isNotEmpty() && !pendingDeclared &&
                                !label[0].isDigit() &&
                                !Symbols.isEventLabelName(label) && !Symbols.isSystemLabelName(label)
                            ) {
                                funcTypes[label.uppercase(Locale.ROOT)] =
                                    if (kw == "FUNCTIONS") OperandType.STR else OperandType.INT
                                pendingDeclared = true
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
                                    // 修饰符后允许任意空白（原版按 WordCollection 的 IdentifierWord 边界分词，
                                    // spec: UserDefinedVariableData.cs:54-58；语料实况 UpdateMenus.ERB:216 有 TAB）
                                    if (head.length > mod.length && head.startsWith(mod) && head[mod.length].isWhitespace()) {
                                        head = head.substring(mod.length).trim()
                                        if (mod == "CONST") isConst = true
                                        if (mod == "CHARADATA") isChara = true
                                        if (mod == "REF") isRef = true
                                        changed = true
                                    }
                                }
                            }
                            val upHead = head.uppercase(Locale.ROOT)
                            if (upHead.contains("CHARADATA")) isChara = true
                            if (upHead.startsWith("CONST")) isConst = true
                            val name = head.takeWhile { it != ',' && it != '=' && it != ';' && !it.isWhitespace() }.trim()
                            if (name.isEmpty()) continue
                            // `#DIM X = 1, 25, 50` 的逗号后是初始值，不是维度（spec: UserDefinedVariableData.cs:344-427）
                            val tail = head.substring(name.length).substringBefore(';').substringBefore('=')
                            // 维度计数（spec: UserDefinedVariableData.cs:300-343）：
                            // 一般变量每个 ',' 后须跟可求值整数表达式；REF 每个 ',' 恰贡献一个 0 尺寸（cs:309-319）
                            val dims = if (isRef) {
                                tail.count { it == ',' }.coerceIn(1, 3)
                            } else {
                                tail.split(',').map { it.trim() }.count { it.isNotEmpty() }.coerceIn(1, 3)
                            }
                            declare(name, if (kw == "DIMS") OperandType.STR else OperandType.INT, dims, isChara, isConst)
                            continue
                        }
                        continue
                    }
                    // spec: ErbLoader.cs:413-424 —— '#' 行只在紧随 FunctionLabelLine 时生效
                    //（logicalLine2 必须仍是该标签行；连续 '#' 行因 ErbLoader continue 不更新
                    // logicalLine2 而保持归属）。普通行 / '$' 行使归属失效；函数中间的散落
                    // '#'（语料实况 IMAGE.ERB:11 等）原版报 InvalidSharp，此处仅不归属。
                    // 纯注释行不打断归属：ParseLine 对首个非空白字符为 ';' 的行（词法
                    // SkipWhiteSpace 直接跳到行尾，LexicalAnalyzer.cs:662-676）返回 null
                    //（LogicalLineParser.cs:387-391），ErbLoader.cs:482-485 continue 不经
                    // addLine（:508）更新 logicalLine2；空行同理在 ReadEnabledLine 被跳过
                    //（EraStreamReader.cs:107-110）。
                    if (!line.startsWith(";")) {
                        pendingLabel = null
                        pendingDeclared = false
                    }
                }
                // 文件结束仍未闭合的区间（spec: ErbLoader.cs:513 + PPState.FileEnd :169-180）
                pp.fileEnd(lines.size)
                warnings.addAll(pp.warnings)
            }
            return CorpusScan(funcTypes, privateVars, global, macroNames, warnings)
        }

        /**
         * 预扫描侧的读文件（与切片侧 `CorpusCompile` 同口径）：
         * 前 8KB 出现 NUL 视为二进制、不解析；读失败/二进制都**留痕**后跳过，
         * 既不炸整批、也不静默（原版对读不开的文件是 `CodeEE FailedOpenFile`，
         * spec: ErhLoader.cs:81-84）。
         */
        private fun readForScan(path: Path, rel: String, sink: MutableList<LoadWarning>, stage: String): List<String>? {
            val bytes = try {
                Files.readAllBytes(path)
            } catch (e: Exception) {
                sink.add(LoadWarning(rel, -1, "$stage 读取失败: ${e::class.simpleName}: ${e.message}（该文件的宏/#DIM/#FUNCTION 全部缺席）"))
                return null
            }
            if (containsNul(bytes, 8192)) {
                sink.add(LoadWarning(rel, -1, "$stage 按二进制跳过（前 8KB 出现 NUL）"))
                return null
            }
            return try {
                LogicalLineReader.readAllLines(bytes)
            } catch (e: Exception) {
                sink.add(LoadWarning(rel, -1, "$stage 解码失败: ${e::class.simpleName}: ${e.message}"))
                null
            }
        }

        /**
         * 行首/行尾截断，与 Symbols.stripWs 同口径：只剥 ' ' 与 '\t'，不吞全角空格 U+3000
         * （spec: LexicalAnalyzer.cs:647-690 SkipWhiteSpace —— U+3000 仅在
         * SystemAllowFullSpace 时算空白，本游戏 NO；Symbols.stripWs 即同口径的 erbcc 版）。
         * Kotlin 的 [String.trim] 按 Char.isWhitespace() 截断，会把 U+3000 一起吞掉
         * （2026-10-26 fixcore 波 A1）。与 Symbols.stripWs 的差异：本函数只做双侧空白
         * 截断，不做 ';' 注释归约 —— 预扫描需要保留 ';' 行本身供下游判断。
         */
        private fun trimLine(raw: String): String {
            var start = 0
            var end = raw.length
            while (start < end && (raw[start] == ' ' || raw[start] == '\t')) start++
            while (end > start && (raw[end - 1] == ' ' || raw[end - 1] == '\t')) end--
            return raw.substring(start, end)
        }

        private fun containsNul(bytes: ByteArray, limit: Int): Boolean {
            val end = minOf(bytes.size, limit)
            for (i in 0 until end) if (bytes[i] == 0.toByte()) return true
            return false
        }
    }
}

/**
 * 全量语料编译的语句层解析器（CLI 侧移植，口径 = core-vm 测试装置
 * `CorpusValidator.ScopedResolver`，见该文件 494-553 行）。
 *
 * 作用域模型（spec: IdentifierDictionary.cs:429-447 GetVariableToken 三级）：
 * ① 当前 `@函数` **定义**的私有表（[DefKey] 身份，同名多定义互不共享 —— G2）；
 * ② LOCAL/ARG/LOCALS/ARGS 局部名册（由 core-vm `LineParser` 的 StmtResolver 默认实现处理）；
 * ③ 全局/内建表（[CorpusScan.global]，内含 BuiltinSymbols 兜底）。
 */
class CompileResolver(private val scan: CorpusScan) : DefinitionScopedResolver {

    /** 当前定义的私有表（可变副本，供 VARI/VARS 即时登记；spec: LogicalLineParser.cs:471/508）。 */
    private var cur: Map<String, VarInfo> = emptyMap()
    private var curVars: LinkedHashMap<String, VarInfo>? = null

    /** 相对文件名 → 预扫描的规范化绝对路径（每个文件只解析一次）。 */
    private val fileAlias = HashMap<String, String>()

    /** 定义身份集合（相对/绝对文件名后缀匹配用）。 */
    private val defFiles: Set<String> = scan.privateVars.keys.mapTo(HashSet()) { it.file }

    override fun enterFunction(functionName: String?) {
        cur = emptyMap()
        curVars = null
    }

    override fun enterFunctionDefinition(functionName: String?, filename: String, line: Int) {
        val key = DefKey(canonicalFile(filename), line)
        val vars = LinkedHashMap(scan.privateVars[key] ?: emptyMap())
        curVars = vars
        cur = vars
    }

    override fun declarePrivateVariable(name: String, type: OperandType, dimensions: IntArray) {
        val vars = curVars ?: return
        vars[name.uppercase(Locale.ROOT)] = VarInfo(
            name = name,
            operandType = type,
            dimension = dimensions.size.coerceIn(1, 3),
            isCharacterData = false,
            isConst = false,   // VARI/VARS 没有 CONST 修饰（spec: LogicalLineParser.cs:463-470 / 500-507）
        )
    }

    /**
     * 把逻辑行里的文件名（语料相对路径，如 `BODY_INFO/X.ERB`）映射到预扫描用的
     * 绝对路径。预扫描传的是绝对路径、LineParser 传的是相对路径，故做后缀匹配。
     */
    private fun canonicalFile(filename: String): String {
        val norm = filename.replace('\\', '/').removePrefix("./")
        fileAlias[norm]?.let { return it }
        val hit = if (norm in defFiles) {
            norm
        } else {
            defFiles.firstOrNull { it.endsWith("/$norm") } ?: norm
        }
        fileAlias[norm] = hit
        return hit
    }

    override fun resolveVariable(name: String, subKey: String?): VarInfo? =
        cur[name.uppercase(Locale.ROOT)] ?: scan.global.resolveVariable(name, subKey)

    override fun resolveFunction(name: String): OperandType? =
        scan.funcTypes[name.uppercase(Locale.ROOT)] ?: scan.global.resolveFunction(name)

    override fun canBeReferencedBare(name: String): Boolean = scan.global.canBeReferencedBare(name)

    override fun isUserMethod(name: String): Boolean = scan.global.isUserMethod(name)
}
