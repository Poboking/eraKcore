package com.eratw.port.core.stmt

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **PC 原版拒绝语料 → 解析器保真度回归（A10 parser-oracle）**
 *
 * 证据：`.workbuddy/golden-pc-loadwarnings.txt`（2026-10-18 用 Emuera_LazyLoadingV3.0_x64.exe
 * 装载 fixtures/golden/GOLDEN_*.ERB 实测，239 条，引擎以「由于ERB脚本出现无法解释的行，
 * Emuera停止运行」中止 —— Process.cs:2124-2128，触发点 ErbLoader.cs:469-473/486-490）。
 * 类别去重与根因见 `tools/golden_pc_oracle.py`（同一套类别 id）。
 *
 * 本文件只锁**我们已经同样拒绝**的类别；我们比原版宽松的类别集中在
 * [PcOracleGapTest]（缺口台账），FORM 插值类型面在 [PcFormInterpolationTest]。
 *
 * 原版判定口径（务必区分，ADR-001 把错误行为列为逐位契约）：
 * - `InvalidLine` = 装载期致命：LogicalLineParser.cs:385-563 的每条 `return new InvalidLine(...)`，
 *   以及 ParseLine 末尾 catch(CodeEE) 把任何参数构造期异常也兜成 InvalidLine（:558-562）。
 * - level≥2 的 `ParserMediator.Warn(..., isError:true)` 只把该行标错（ArgumentBuilder.cs:19-40），
 *   不触发中止，但仍属"该行不可执行"，本文件按拒绝处理并在注释里标明层级。
 */
class PcRejectedSyntaxTest {

    /**
     * 默认解析器（门控开 = 现状兼容）：金标里 VARI 声明的 GA/GSB 视为未注册。
     * 本作口径（setting.json 关闭 VARI/VARS）的拒绝面见 [scopedClosed] 与文末 SCOPED_VAR_INSTR 段。
     */
    private fun bare(): LineParser {
        val sym = SymbolTable()
            .declareVariable("ARR", OperandType.INT, 1)
            .declareVariable("ARR2", OperandType.INT, 1)
            .declareVariable("KA", OperandType.INT, 1)
            .declareVariable("COUNT", OperandType.INT, 1)
            .declareVariable("STRARR", OperandType.STR, 1)
            .declareVariable("XBUF", OperandType.STR, 1)
            .declareFunction("MYFUNC", OperandType.INT)
        return LineParser(sym)
    }

    /**
     * 本作口径解析器：`UseScopedVariableInstruction=false`（setting.json），
     * VARI/VARS 在标识符解析期即抛 CanNotUseVAR 同口径错误（A3 门控）。
     */
    private fun scopedClosed(): LineParser {
        val sym = SymbolTable()
            .declareVariable("ARR", OperandType.INT, 1)
            .declareVariable("ARR2", OperandType.INT, 1)
            .declareVariable("KA", OperandType.INT, 1)
            .declareVariable("COUNT", OperandType.INT, 1)
            .declareVariable("STRARR", OperandType.STR, 1)
            .declareVariable("XBUF", OperandType.STR, 1)
            .declareFunction("MYFUNC", OperandType.INT)
        return LineParser(sym, stmtConfig = StmtParseConfig(useScopedVariableInstruction = false))
    }

    /** 对照场景：若上游真的注册了 GA/GSB（即 VARI 合法时），后续语句本身应当合法。 */
    private fun withVari(): LineParser {
        val sym = SymbolTable()
            .declareVariable("GA", OperandType.INT, 1)
            .declareVariable("GSB", OperandType.STR, 1)
        return LineParser(sym)
    }

    private fun logical(text: String, lineNo: Int = 10): LogicalLine = when (text.first()) {
        '@' -> LogicalLine(LineKind.FUNCTION_LABEL, "GOLDEN_ORACLE.ERB", lineNo, text)
        '$' -> LogicalLine(LineKind.GOTO_LABEL, "GOLDEN_ORACLE.ERB", lineNo, text)
        else -> LogicalLine(LineKind.CODE, "GOLDEN_ORACLE.ERB", lineNo, text)
    }

    private fun parse(p: LineParser, text: String, lineNo: Int = 10): Statement? =
        p.parseLogicalLine(logical(text, lineNo))

    private fun reject(p: LineParser, text: String, why: String, contains: String? = null): StmtParseException {
        val e = assertFailsWith(
            StmtParseException::class,
            "原版拒绝（$why），我们也必须拒绝：$text",
        ) { parse(p, text) }
        if (contains != null) assertContains(e.message ?: "", contains, message = "错误信息应体现「$contains」：${e.message}")
        return e
    }

    // -----------------------------------------------------------------------
    // SET_INSTR_FORM —— `SET 目标, 值` 这种指令写法在原版根本不存在
    // 根因：`setFunc = new FunctionIdentifier("SET", FunctionCode.SET, …)` 只在
    //       FunctionIdentifier.cs:3829 构造、**从未** addFunction 进 funcDic（入表只走
    //       addFunction，:3791-3805；名字表就是 funcDic，:3807-3810）。
    //       它仅被 LogicalLineParser.cs:401（++/--）与 :556（赋值两段式）内部使用。
    //       于是行首 "SET" 在 funcDic 查不到（:406-411）⇒ 原版落到赋值两段式
    //       （:536-547）：Analyse(…, LexEndWith.Operator) 之后 ReadAssignmentOperator
    //       读不到赋值运算符（下一个词是目标变量）⇒ catch(CodeEE) ⇒ InvalidLine
    //       `Lang.Error.CanNotInterpretedLine`。移植侧同一条路径：`SET` 已不在
    //       `FunctionCode.BY_NAME`（伪码被过滤，FunctionCode.kt:476-490），LineParser
    //       因此走 `parseAssignmentLine` 的 readAssignmentOperator 失败分支
    //       （LineParser.kt:292-303），文案「无法解释为赋值语句：…」。
    //       用户 `#DIM SET` 合法：SET 不属 Reserved/SystemVariable/SystemMethod/
    //       SystemInstrument（IdentifierDictionary.cs:258-297），见 Cmp48PseudoCodeNameTest。
    // 正确写法：`GA = 20` / `ARR = 1, 2, 3`（多值只在 Assignment 运算符下合法，
    //       ArgumentParser.cs:949-983）。真实语料 `^SET ` 出现 0 次（已排除 GOLDEN_*）。
    // -----------------------------------------------------------------------

    @Test
    fun `SET 指令写法一律拒绝`() {
        val p = bare()
        for (src in listOf(
            "SET GA, 20",
            "SET GSB, \"world\"",
            "SET GA, GA + 5",
            "SET ARR, 1, 2, 3, 4, 5",
            "SET ARR, 100",
            "SET COUNT, 0",
            "SET COUNT, COUNT + 1",
            "SET ABL:0:0, 5",
            "SET CFLAG:0:7, 42",
            "SET MONEY:0, 12345",
            "SET GLOBAL:0, 777",
            "SET GLOBALS:0, \"GSTR\"",
            "SET CSTR:0:0, \"CDATA\"",
            "SET FLAG:5, 5",
            "SET WINDOW_TITLE, \"GOLDEN-TITLE\"",
            "SET HTM, \"<i>AB</i>CD\"",
            "SET RI, RAND(1000000)",
            "SET FTXT, LOADTEXT(\"GOLDEN_TEST.txt\")",
            "SET HSUB, HTML_SUBSTRING(\"<i>AB</i>CD\", 3)",
            "SET MAPSTR, MAP_TOXML(\"GM\")",
            "SET XDATA, DT_TOXML(\"GT\", XSCHEMA)",
            "SET KA, 3, 1, 2, 1, 3",
        )) {
            // SET 不是语句名 ⇒ 走赋值两段式，在「读赋值运算符」处失败
            //（LogicalLineParser.cs:536-547 同一句），文案不再是「缺少左值」
            //（那是 SET 还留在 BY_NAME 时的 SP_SET 参数面错误）。
            reject(p, src, "SET_INSTR_FORM / 赋值两段式失败", "无法解释为赋值语句")
        }
    }

    @Test
    fun `SET 写法即使用已注册变量也拒绝`() {
        // 证明拒绝原因是指令面而不是「GA 未声明」的级联：
        // 给 GA/GSB 建好符号后 `SET GA, 20` 仍然必须失败。
        val p = withVari()
        reject(p, "SET GA, 20", "SET_INSTR_FORM", "无法解释为赋值语句")
        reject(p, "SET GSB, \"world\"", "SET_INSTR_FORM", "无法解释为赋值语句")
        // 而同样的变量用合法赋值写法必须通过（反向证明：不是符号表在挡路）
        assertIs<AssignStmt>(parse(p, "GA = 20"))
        assertIs<AssignStmt>(parse(p, "GSB = \"world\""))
        assertIs<AssignStmt>(parse(p, "GA += 5"))
    }

    // -----------------------------------------------------------------------
    // UNKNOWN_INSTR_NAME —— 原版没有的语句名（金标自造的 PRINTSINGLE*L 系列等）
    // 根因：LogicalLineParser.cs:406-411 未命中 funcDic → :536-547 赋值两段式失败 → InvalidLine。
    // 正确写法：PRINTSINGLE 族只有 FunctionIdentifier.cs:3875-3889 注册的 15 个名字，
    //       其中**没有任何 *L 变体**（L 后缀只在 PRINT/PRINTV/PRINTS/PRINTFORM… 上存在）。
    // -----------------------------------------------------------------------

    @Test
    fun `不存在的 PRINTSINGLE 变体被拒`() {
        val p = bare()
        for (src in listOf(
            "PRINTSINGLEL TWO",
            "PRINTSINGLEVL 20",
            "PRINTSINGLESKL SK30",
            "PRINTSINGLEKL K26",
            "PRINTSINGLEVKL VK28",
            "PRINTSINGLEFORMKL FK32",
            "PRINTSINGLEFORMSKL FSK34",
            "PRINTSINGLEDL D36",
            "PRINTSINGLEVDL VD38",
            "PRINTSINGLESDL SD40",
            "PRINTSINGLEFORMDL FD42",
            "PRINTSINGLEFORMSDL FSD44",
        )) {
            reject(p, src, "UNKNOWN_INSTR_NAME / 无法解析的行")
        }
    }

    @Test
    fun `语句名表不含幻影名且与原版注册面一致`() {
        // 原版注册表统计（本次 reference 全量比对：addFunction/addPrintFunction/addPrintDataFunction
        // 共 302 个名字，与本枚举除去 SET/METHOD/__NULL__ 三个伪码后的名字集**完全相等**）。
        val real = setOf(
            "PRINTSINGLE", "PRINTSINGLEV", "PRINTSINGLES", "PRINTSINGLEFORM", "PRINTSINGLEFORMS",
            "PRINTSINGLEK", "PRINTSINGLEVK", "PRINTSINGLESK", "PRINTSINGLEFORMK", "PRINTSINGLEFORMSK",
            "PRINTSINGLED", "PRINTSINGLEVD", "PRINTSINGLESD", "PRINTSINGLEFORMD", "PRINTSINGLEFORMSD",
        )
        assertContains(FunctionCode.entries.map { it.name }.toSet(), "PRINTSINGLEVK")
        assertTrue(real.all { FunctionCode.BY_NAME.containsKey(it) }, "PRINTSINGLE 族 15 名必须齐备")
        // 幻影名（金标自造 / 直觉外推）必须不存在：一旦有人补进枚举，本断言立刻红。
        for (phantom in listOf(
            "PRINTSINGLEL", "PRINTSINGLEVL", "PRINTSINGLESL", "PRINTSINGLEKL", "PRINTSINGLEVKL",
            "PRINTSINGLESKL", "PRINTSINGLEFORMKL", "PRINTSINGLEFORMSKL", "PRINTSINGLEDL",
            "PRINTSINGLEVDL", "PRINTSINGLESDL", "PRINTSINGLEFORMDL", "PRINTSINGLEFORMSDL",
            "PRINTSINGLEW", "PRINTSINGLELW", "SETS", "SETSTR",
        )) {
            assertTrue(FunctionCode.BY_NAME[phantom] == null, "原版没有语句 $phantom，不得进枚举")
        }
    }

    // -----------------------------------------------------------------------
    // ILLEGAL_SEP_AFTER_INSTR —— 指令名后紧跟 ',' 等非空白
    // 根因：LogicalLineParser.cs:513-527（只允许 ' ' / '\t' / ';'，'　' 需
    //       Config.SystemAllowFullSpace；本游戏未开）→ InvalidCharacterAfterInstruction2 → InvalidLine。
    // 正确写法：`INPUT 100, 0, 1`（语料 `^INPUT,` 0 次、`^INPUT ` 7 次）。
    // 我们的拒绝点：LineParser.kt:196-202 splitArgs。
    // -----------------------------------------------------------------------

    @Test
    fun `指令名后非法分隔符被拒`() {
        val p = bare()
        for (src in listOf(
            "INPUT, 42, 0, 1",
            "INPUTS, \"ANS\", 0, 1",
            "BINPUT, 55, 0, 1",
            "BINPUTS, \"BS\", 0, 1",
            "ONEBINPUT, 66, 0, 1",
            "ONEBINPUTS, \"OBS\", 0, 1",
            "INPUT, 999",
            "PRINTL, x",
            "PRINTFORML, {1}",
        )) {
            reject(p, src, "ILLEGAL_SEP_AFTER_INSTR", "非法字符")
        }
        // 合法对照：指令名后一个空格再写参数（原版 :517-521 只丢弃 1 个分隔字符）
        assertIs<InstructionStmt>(parse(p, "INPUT 100, 0, 1"))
        // ';' 紧跟指令名 = 行尾注释（原版 :519 直接构造无参 InstructionLine）
        assertIs<InstructionStmt>(parse(p, "RESETCOLOR;后续是注释"))
    }

    // -----------------------------------------------------------------------
    // UNKNOWN_IDENTIFIER —— 「无法解析的标识符」
    // 根因：IdentifierDictionary.cs:588-628 末尾抛 IdentifierNotFoundCodeEE。
    // 三个子型（tools/golden_pc_oracle.py 统计 33 条 = 21 真未声明 + 9 VARI 级联 + 3 通配符）：
    //  1) UNDECLARED_BY_DESIGN：S3 / VK27 / BTN / PCL / SN / RESULT_ARRAY …
    //  2) VARI_CASCADE：GA / GSB —— 上游 VARI 被拒导致变量从未注册（根因见 SCOPED_VAR_INSTR）
    //  3) WILDCARD：FIND_CHARADATA GOLDEN_* / ENUMFILES sav 之类把「名字」写成裸标识符
    // 我们的拒绝点：Parser.kt:287-308（resolver 非空且查不到 → parseErr「未定义的标识符」）。
    // -----------------------------------------------------------------------

    @Test
    fun `未声明标识符在参数面被拒`() {
        val p = bare()
        for (src in listOf(
            "PRINTSINGLES S3",
            "PRINTSINGLEVK VK27",
            "PRINTSINGLESK SK29",
            "PRINTSINGLEFORMSK FSK33",
            "PRINTSINGLEVD VD37",
            "PRINTSINGLESD SD39",
            "PRINTSINGLEFORMSD FSD43",
            "PRINTVKW KW52",
            "PRINTSKW KW53",
            "PRINTFORMSKW KW55",
            "PRINTVDW DW57",
            "PRINTSDW DW58",
            "PRINTFORMSDW DW60",
            "PRINTBUTTON BTN, 1",
            "PRINTBUTTONC BC, 2",
            "PRINTBUTTONLC BLC, 3",
            "PRINTCPERLINE PCL",
            "SAVENOS SN",
        )) {
            reject(p, src, "UNKNOWN_IDENTIFIER")
        }
    }

    @Test
    fun `VARI 级联的标识符在未注册符号表下同样被拒`() {
        // GA/GSB 在原版里从未注册（VARI 不可用）→ SETBIT/PRINTFORML 全部连带失败。
        val p = bare()
        reject(p, "SETBIT GA, 1, 3", "UNKNOWN_IDENTIFIER / VARI_CASCADE")
        reject(p, "CLEARBIT GA, 1", "UNKNOWN_IDENTIFIER / VARI_CASCADE")
        reject(p, "INVERTBIT GA, 2, 4", "UNKNOWN_IDENTIFIER / VARI_CASCADE")
        // 反证：变量真的注册过以后这些写法合法（说明差异只在 VARI 门控，不在语句面）
        val q = withVari()
        assertIs<InstructionStmt>(parse(q, "SETBIT GA, 1, 3"))
        assertIs<InstructionStmt>(parse(q, "CLEARBIT GA, 1"))
    }

    @Test
    fun `RESULT_ARRAY 不是 Emuera 变量`() {
        // 金标把它当 RESULTS 的别名用；原版 VariableData.cs 的 varTokenDic 无 RESULT_ARRAY。
        val p = bare()
        reject(p, "PRINTFORML UNI_STMT={RESULT_ARRAY:0}", "UNKNOWN_IDENTIFIER")
        reject(p, "COUNT = RESULT_ARRAY:0", "UNKNOWN_IDENTIFIER")
    }

    @Test
    fun `通配符与裸关键字参数被拒`() {
        // 正确写法：FIND_CHARADATA "GOLDEN_*"（第一/二参是字符串），见 oracle 类别 UNKNOWN_IDENTIFIER/WILDCARD
        val p = bare()
        reject(p, "FIND_CHARADATA GOLDEN_*", "WILDCARD_OR_KEYWORD_ARG")
        reject(p, "ENUMFILES sav, GOLDEN_*", "WILDCARD_OR_KEYWORD_ARG")
        reject(p, "ENUMFILES ERB, GOLDEN_*, 0, RESULTS", "WILDCARD_OR_KEYWORD_ARG")
        reject(p, "DT_COLUMN_OPTIONS GT, NAME, default, \"NONAME\"", "UNKNOWN_IDENTIFIER")
    }

    // -----------------------------------------------------------------------
    // UNEXPECTED_SYMBOL —— `PRINTBUTTON [X], "SVAL"`
    // 根因：ExpressionParser.cs:370-374（'[' 形式的函数调用未实现 → CodeEE）与
    //       reduceTerm 兜底分支；消息 lang Error.UnexpectedSymbol。
    // -----------------------------------------------------------------------

    @Test
    fun `中括号不是合法的表达式形态`() {
        reject(bare(), "PRINTBUTTON [X], \"SVAL\"", "UNEXPECTED_SYMBOL")
    }

    // -----------------------------------------------------------------------
    // INSTRUCTION_ARG_TYPE —— 「X命令:第N参数的类型错误 / 参数过少 / 格式错误」
    // 根因：ArgumentBuilder.cs:19-90（warn 前缀 = 语句名 + Lang.Error.Instruction，
    //       level≥2 → line.IsError）+ ArgumentParser 各形态的显式校验。
    // 正确写法全部取自真实语料（已 --exclude=GOLDEN_* 并带词边界）：
    //   ARRAYCOPY "ADD_SEX_MODULE_CHARA", "CHARAS"        （第1参是数组**名**字符串，26 次全为字符串）
    //   SPLIT STR_IMAGE, "/", LOCALS                       （第3参必须是字符串数组变量，120 次）
    //   TINPUT 20, (NOWCURRENT+1), 0, ""                   （第4参是字符串默认值，4 次）
    //   SORTCHARA CFLAG:7, FORWARD                          （第二项只能 FORWARD/BACK）
    //   SAVECHARA TOSTR(CHOSEN_MOB), CSTR:..., RANDOM_CHARANUM（3 参）
    // -----------------------------------------------------------------------

    @Test
    fun `指令参数面与原版一致`() {
        val p = bare()
        // 官方 zhs = 「第{0}参数的类型错误」（IncorrectArg，Lang.cs:1938 / zhs xml:439）、
        // 「参数过少」（NotEnoughArguments，Lang.cs:1926 / zhs xml:436）
        reject(p, "ARRAYCOPY ARR, ARR2", "INSTRUCTION_ARG_TYPE / 第1参必须是字符串", "第1参数")
        reject(p, "SPLIT \"a,b,c\", \",\", I", "INSTRUCTION_ARG_TYPE / 第3参类型", "第3参数")
        reject(p, "TINPUT 500, 111, 0, 0", "INSTRUCTION_ARG_TYPE / 第4参类型", "第4参数")
        reject(p, "TINPUTS 500, \"TDEF\", 0, 0", "INSTRUCTION_ARG_TYPE / 第4参类型", "第4参数")
        reject(p, "SORTCHARA CFLAG, 7, 0", "INSTRUCTION_ARG_TYPE / 格式错误", "SORTCHARA")
        reject(p, "SORTCHARA CFLAG, 7, 1", "INSTRUCTION_ARG_TYPE / 格式错误", "SORTCHARA")
        reject(p, "SAVECHARA \"GOLDEN_CHARA.dat\", \"golden chara\"", "INSTRUCTION_ARG_TYPE / 参数过少", "参数过少")
        // 合法对照（语料实测形态）
        assertIs<InstructionStmt>(parse(p, "ARRAYCOPY \"ARR\", \"ARR2\""))
        assertIs<InstructionStmt>(parse(p, "SPLIT \"a,b,c\", \",\", STRARR"))
        assertIs<InstructionStmt>(parse(p, "TINPUT 500, 111, 0, \"\""))
        assertIs<InstructionStmt>(parse(p, "SORTCHARA CFLAG:7, FORWARD"))
        assertIs<InstructionStmt>(parse(p, "SAVECHARA \"a.dat\", \"memo\", 0"))
    }

    @Test
    fun `数组批量赋值的合法写法是逗号右值`() {
        // 金标想表达 `SET ARR, 1, 2, 3`，原版对应写法只有 `ARR = 1, 2, 3`
        // （ArgumentParser.cs:949-983：多右值仅在 Assignment 运算符下构造 SpSetArrayArgument）。
        val s = assertIs<AssignStmt>(bare().parseLogicalLine(
            LogicalLine(LineKind.CODE, "GOLDEN_ORACLE.ERB", 23, "ARR = 1, 2, 3, 4, 5"),
        ))
        assertIs<SpSetArrayArgument>(s.args)
        // 复合赋值不允许多右值（:951-954 CanNotContainMultipleValue）
        reject(bare(), "ARR += 1, 2", "多右值只属于 '='", "多个右值")
    }

    // -----------------------------------------------------------------------
    // BLOCK_MISSING_PAIR —— 缺少 "CATCH" 对应的 TRYC*  （lang Error.MissingCorresponding）
    // 根因：FunctionIdentifier.cs:4148-4153 funcMatch[TRYCGOTO/TRYCJUMP/TRYCCALL(+FORM)]=CATCH；
    //       校验点 ErbLoader.cs:1332/1568（level 2 isError，行标错）。
    // 我们的对应实现：BlockTable.openToClose + BlockChecker（语句层已覆盖）。
    // -----------------------------------------------------------------------

    @Test
    fun `TRYC 系列缺少 CATCH 时报块错误`() {
        val p = bare()
        for (src in listOf("TRYCGOTO NOLAB_XYZ", "TRYCGOTOFORM LAB{8+9}", "TRYCJUMPFORM GOLDEN_NO{1+1}")) {
            val s = parse(p, src)
            assertIs<Statement>(s, "该行应能被解析（拒绝点在块结构）：$src")
            val errors = BlockChecker().check(listOf(s!!))
            assertTrue(
                errors.any { it.message.contains("CATCH") },
                "$src 必须报「缺少 CATCH」，实际=$errors",
            )
        }
        // 合法对照：TRYCGOTO … CATCH … ENDCATCH 三层齐全
        val ok = listOf("TRYCGOTO LAB", "CATCH", "ENDCATCH", "\$LAB").mapIndexed { i, t ->
            parse(p, t, 100 + i)
        }
        assertTrue(BlockChecker().check(ok.filterNotNull()).isEmpty(), "合法 TRYC/CATCH 结构不应报错")
    }

    // -----------------------------------------------------------------------
    // EXPR_SYNTAX —— 「表达式异常」（lang Error.UnrecognizedSyntax）
    // 根因：ExpressionParser.cs:29-34 —— 一元前缀运算符把状态推到 2/3 后，
    //       再来一个运算符即抛；故 `{!0}` 合法、`{!!2}` 非法（金标 241 行的实际触发点）。
    // 我们的对应实现：Parser.kt:444-466 同款状态机（"无法识别的语法"）。
    // -----------------------------------------------------------------------

    @Test
    fun `叠加一元运算符被拒`() {
        val p = bare()
        // 金标 241 行的实际触发点：{!!2}（原版状态机 state==3 时再来运算符即抛）
        reject(p, "PRINTFORML NOT={!0} Z={!!2}", "EXPR_SYNTAX / {!!2}")
        reject(p, "COUNT = !!2", "EXPR_SYNTAX / 赋值右值同样受限")
        // 合法对照：单层一元、括号形式、&&/|| 逻辑运算符（原版 OperatorCode.And/Or 存在）
        assertIs<PrintStmt>(parse(p, "PRINTFORML V={!2}"))
        assertIs<PrintStmt>(parse(p, "PRINTFORML V={!(!2)}"))
        assertIs<PrintStmt>(parse(p, "PRINTFORML A={1 && 1} B={0 || 1} C={!0}"))
    }

    // -----------------------------------------------------------------------
    // BRACE_OUTSIDE_FORM —— 「词法解析中出现意料之外的字符'{'」
    // 根因：LexicalAnalyzer.cs:916-918；且 PRINT*S / PRINTFORMS 的参数形态是
    //       STR_EXPRESSION 而不是 FORM_STR（FunctionIdentifier.cs:101-111），
    //       所以 `PRINTFORMS XY{3+3}` 里的 '{' 落在普通词法上下文。
    // 我们的对应实现：LexicalAnalyzer.kt:527-532（同消息），ArgBuilders.create 包成 StmtParseException。
    // -----------------------------------------------------------------------

    @Test
    fun `非 FORM 上下文的左花括号被拒`() {
        val p = bare()
        for (src in listOf(
            "PRINTFORMS XY{3+3}",
            "PRINTFORMSL Z{4+4}",
            "PRINTFORMSK SK{11}",
            "PRINTFORMSKL SKL{12}",
            "PRINTFORMSD SDD{17}",
            "PRINTFORMSDL SDDL{18}",
            "PRINTFORMSN FSN{47}",
            "PRINTFORMSW FSW{50}",
            "PRINTSINGLEFORMS FS{23}",
            "PRINTSINGLEFORML F{22}",
            "PRINTSINGLEFORMSL FS{24}",
        )) {
            reject(p, src, "BRACE_OUTSIDE_FORM")
        }
    }

    // -----------------------------------------------------------------------
    // SCOPED_VAR_INSTR —— VARI/VARS 语句门控（A3，迁移自 PcOracleGapTest 台账）
    // 根因：FunctionIdentifier.cs:4123-4127 —— 仅 UseScopedVariableInstruction=true 时
    //       VARI/VARS 才 addFunction 进 funcDic；本作 setting.json 为 false ⇒ 原版对
    //       本作不存在这两个语句（金标 57 条「无法解析的行」与 9 条标识符级联的根因）。
    //       关闭时标识符解析期抛 CanNotUseVAR（IdentifierDictionary.cs:624-627，
    //       Lang.cs:4010「{0}命令は現在の設定では使用できません」）。
    // 我们的门控点：LineParser.kt parseInstructionLine 顶部（先于参数切分）。
    // 门控名单（FunctionIdentifier.cs:4125-4126 抄录）：恰为 VARI、VARS 两条。
    // -----------------------------------------------------------------------

    @Test
    fun `门控关闭时 VARI 各形态被拒（CanNotUseVAR 口径）`() {
        val p = scopedClosed()
        for (src in listOf(
            "VARI GA, 10",           // 长度式（金标形态）
            "VARI ARR2, 10, 20",     // 多维长度式
            "VARI CNT = 5",          // 初值式
            "VARI CNT",              // 裸名
            "VARI",                  // 裸语句名
            "vari ga, 10",           // 小写：语句名分派大小写不敏感，同样命中门控
        )) {
            reject(p, src, "SCOPED_VAR_INSTR / CanNotUseVAR", "当前设置")
        }
    }

    @Test
    fun `门控关闭时 VARS 各形态被拒（CanNotUseVAR 口径）`() {
        val p = scopedClosed()
        for (src in listOf(
            "VARS GSB = \"hello\"",
            "VARS GSB, 10",
            "VARS GSB",
            "VARS",
            "Vars gsb = \"x\"",
        )) {
            reject(p, src, "SCOPED_VAR_INSTR / CanNotUseVAR", "当前设置")
        }
    }

    @Test
    fun `门控关闭先于参数形态校验`() {
        // 原版开关关闭时语句名解析即失败，永远走不到参数校验：`VARI,GA`（非法分隔）
        // 报的也是 CanNotUseVAR，而不是「指令后跟随非法字符」。
        reject(scopedClosed(), "VARI,GA", "SCOPED_VAR_INSTR / 门控先行", "当前设置")
    }

    @Test
    fun `门控关闭不影响其他语句（反证：不是符号表在挡路）`() {
        val p = scopedClosed()
        assertIs<PrintStmt>(parse(p, "PRINTL hello"))
        assertIs<InstructionStmt>(parse(p, "RESETCOLOR"))
        assertIs<AssignStmt>(parse(p, "COUNT = 1"))
    }

    @Test
    fun `门控打开时 VARI VARS 正常声明（可反向证明）`() {
        // 默认构造（不传 stmtConfig）= 现状兼容 = 门开：VARI/VARS 照常产出 VarDeclStmt，
        // 证明拒绝只来自门控而不是语句面损坏（LineParserTest.kt:328-341 同口径）。
        val p = bare()
        assertIs<VarDeclStmt>(parse(p, "VARI GA, 10"))
        assertIs<VarDeclStmt>(parse(p, "VARS GSB = \"hello\""))
        // 显式开门同样合法
        val q = LineParser(
            SymbolTable(),
            stmtConfig = StmtParseConfig(useScopedVariableInstruction = true),
        )
        assertIs<VarDeclStmt>(q.parseLogicalLine(logical("VARI CNT = 5")))
    }

    @Test
    fun `逗号式 VARS 初值必须拒绝（门开时的严格面，与原版整数解析一致）`() {
        // 迁移自 PcOracleGapTest：原版（开关打开时）`VARS GSB, "hello"` 会让
        // int.Parse('"hello"') 抛 FormatException（LogicalLineParser.cs:485-492）；
        // 我们同样拒绝逗号式 VARS 初值。门控关闭时该行先被 CanNotUseVAR 拦下，
        // 故本断言必须在门开解析器（bare()）上验证。
        reject(bare(), "VARS GSB, \"hello\"", "SCOPED_VAR_INSTR / 逗号式 VARS 初值", "不是整数")
    }

    // -----------------------------------------------------------------------
    // METHOD_AS_VARIABLE（迁移自 PcOracleGapTest，A4 收口）
    //
    // 原版：裸标识符位（无括号）`GetFunctionMethod(arguments == null)` **只**查 refmethodDic
    // （IdentifierDictionary.cs:538-547），内建方法一律返回 null；随后 nameDic 判为
    // DefinedNameType.SystemMethod（:170）→ :607-612 抛「用变量的方式使用函数名"NAME"」。
    // 移植侧：Parser.kt 裸标识符分支新增 `IdentifierResolver.canBeReferencedBare` 门，
    // 由 BuiltinSymbols.isBuiltinMethod 供判据。
    // -----------------------------------------------------------------------

    @Test
    fun `裸内建方法名被拒`() {
        // 22 条金标形态的代表：{} 内 / 赋值右值 / 运算符两侧
        for (name in listOf(
            "GETCOLOR", "GETBGCOLOR", "GETSTYLE", "CURRENTREDRAW", "CLIENTWIDTH", "CLIENTHEIGHT",
            "GETDEFCOLOR", "GETFOCUSCOLOR", "GETDEFBGCOLOR", "GETFONT", "CURRENTALIGN",
        )) {
            reject(bare(), "PRINTFORML V={$name}", "METHOD_AS_VARIABLE / 裸方法名", "用变量的方式使用函数名")
        }
        reject(bare(), "COUNT = GETCOLOR", "METHOD_AS_VARIABLE / 赋值右值", "用变量的方式使用函数名")
    }

    // -----------------------------------------------------------------------
    // CORE-06：`#DIM` 名撞系统保留名（IdentifierDictionary.cs:258-297 / :340-390）
    // -----------------------------------------------------------------------

    /**
     * 原版 `CheckUser(Private)VarName` 按 `DefinedNameType` 分四类拒绝（warnLevel = 2 →
     * `CodeEE`，UserDefinedVariableData.cs:273-290）。文案取 lang.emuera-zhs.xml:873-875。
     */
    @Test
    fun `DIM 系统保留名被拒`() {
        val cases = mapOf(
            "#DIM I" to "变量名\"I\"为Emuera所使用的变量名",                 // SystemVariable
            "#DIMS S" to "变量名\"S\"为Emuera所使用的变量名",               // SystemVariable
            "#DIM GETCOLOR" to "变量名\"GETCOLOR\"为Emuera所使用的变量或者命令名", // SystemMethod
            "#DIM PRINTL" to "变量名\"PRINTL\"为Emuera所使用的变量或者命令名",     // SystemInstrument
            "#DIM INT" to "变量名\"INT\"为Emuera的保留字",                  // Reserved
            "#DIM PRIVATE" to "变量名\"PRIVATE\"为Emuera的保留字",          // Reserved
            "#DIM __DEBUG__" to "变量名\"__DEBUG__\"为Emuera的保留字",      // Reserved
        )
        for ((src, msg) in cases) {
            val e = assertFailsWith<StmtParseException>("原版拒绝：$src") {
                bare().parseSharpLine(src, 1)
            }
            assertContains(e.message ?: "", msg)
        }
    }

    /** 反证：只拒内建/保留名，不误伤用户自定义名与修饰符形态（`SET`/`METHOD` 是伪码不在指令表）。 */
    @Test
    fun `DIM 用户自定义名与伪码仍然合法`() {
        for (src in listOf(
            "#DIM MY_VAR",
            "#DIMS MY_STR",
            "#DIM CONST MY_CONST, 3 = 1",
            "#DIM DYNAMIC VAL",
            "#DIM SET",
            "#DIM METHOD",
        )) {
            assertIs<SharpStmt>(bare().parseSharpLine(src, 1), src)
        }
    }

    @Test
    fun `带括号的方法调用仍然合法（反证：收的是形态不是名字）`() {
        for (src in listOf(
            "PRINTFORML V={GETCOLOR()}", "PRINTFORML V={GETCOLOR(1)}",
            "PRINTFORML V=%MONEYSTR(1)%", "PRINTFORML V=%CSVNAME(1)%",
        )) {
            assertIs<PrintStmt>(parse(bare(), src))
        }
        // 语句位同样不受影响（funcDic 的 methodInstruction，FunctionIdentifier.cs:4140-4148）
        assertIs<MethodStmt>(parse(bare(), "GETCOLOR 1"))
    }
}
