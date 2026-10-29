package com.eratw.port.core.sem

import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.StmtParseException
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SET` 增量赋值（红 SET-ADDCONST）的形态与落库口径。
 *
 * 原版链路（证据）：
 * - 解析期 `ArgumentParser.cs:922-940`（`++` / `--`）、`:1004-1015`（常量 `+=` / `-=`）
 *   把常量增量折成 `Term=null, IsConst=true, AddConst=true, ConstInt=±N`；
 *   `-=` 存的是**负号已带上**的 `-N`（`:1011`）。
 * - 非常量复合赋值走 `:1017` `ReduceBinaryTerm(assignOperator, variableTerm, rhs)`，
 *   即 `Term = (dest op rhs)` **整式**，且不设 `IsConst` / `AddConst`。
 * - 执行期 `FunctionIdentifier.cs:647-658`：`IsConst ? ConstInt : Term.GetIntValue`，
 *   `AddConst ? ChangeValue(value) : SetValue(value)`，执行层**不得**再套一次 op。
 * - `ChangeValue` = `VariableTerm.cs:202-220` → 各 Token 的 `PlusValue`
 *   （`VariableData.cs` 内 `Int1DVariableToken` / `CharaInt1DVariableToken` /
 *   `LocalInt1DVariableToken` 等）；越界由 `IndexOutOfRangeException` 兜到
 *   `Identifier.CheckElement(transporter)`。
 *
 * 移植侧对应 `sem/StatementExecutor.kt:1864-1897`（`execAssign` 的 `SpSetArgument` 分支）
 * 与 `stmt/ArgBuilders.kt:1361-1483`（解析期折叠）、`vars/VariableStore.kt:177-195`（`plusInt`）。
 * 本文件钉住三件事：折叠符号、非常量整式不二次套运算、`plusInt` 与 `SetValue` 同源到
 * 各类落点（全局 / 角色 / 用户 / LOCAL / 带下标表达式 / 越界 / 伪变量）。
 */
class SetAddConstTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun fixture(vararg decls: DimSpec): Pair<SemTestEnv, LineParser> {
        val env = SemTestEnv()
        // 角色用户变量要先有角色行，声明时才能分配合法列高（CharacterTable.addBare → ensureCapacity）
        if (decls.any { it.isCharaData }) env.store.characters.addBare()
        val symbols = SymbolTable()
        for (d in decls) {
            symbols.declareVariable(
                d.name,
                if (d.isStr) OperandType.STR else OperandType.INT,
                d.dims.size,
                d.isCharaData,
                d.isConst,
            )
            env.store.declareUserVar(d)
        }
        return env to LineParser(symbols)
    }

    private fun assignArg(parser: LineParser, text: String): AssignStmt {
        val stmt = parser.parseCodeLine(text, 1)!!
        assertEquals(FunctionCode.SET, stmt.code, "应被解析成 SET：$text")
        return stmt as AssignStmt
    }

    private fun setArg(parser: LineParser, text: String): SpSetArgument {
        val arg = assignArg(parser, text).args
        assertTrue(arg is SpSetArgument, "$text 的参数应是 SpSetArgument，实际 ${arg.shape}")
        return arg as SpSetArgument
    }

    private fun SemTestEnv.runLine(parser: LineParser, text: String) = run(parser.parseCodeLine(text, 1)!!)

    // ------------------------------------------------------------------
    // 1. 常量增量的折叠口径（符号 + AddConst 组合）
    // ------------------------------------------------------------------

    @Test
    fun `常量加等折成 ConstInt 正值 且 AddConst 不再求值 Term`() {
        val (_, parser) = fixture()
        val arg = setArg(parser, "DAY += 2")
        assertTrue(arg.isConst, "IsConst 必须为 true（ArgumentParser.cs:1006-1013）")
        assertTrue(arg.addConst, "AddConst 必须为 true")
        assertEquals(2L, arg.constInt)
        assertNull(arg.term, "常量增量形态 Term 为 null，执行层只读 ConstInt")
    }

    @Test
    fun `常量减等折成 ConstInt 负值`() {
        val (_, parser) = fixture()
        val arg = setArg(parser, "DAY -= 2")
        assertEquals(-2L, arg.constInt, "-= 在解析期就把负号折进 ConstInt（ArgumentParser.cs:1011）")
        assertTrue(arg.isConst && arg.addConst)
        assertNull(arg.term)
    }

    @Test
    fun `自增自减折成 ConstInt 正负一`() {
        val (_, parser) = fixture()
        val inc = setArg(parser, "DAY++")
        assertEquals(1L, inc.constInt, "ArgumentParser.cs:935-940")
        assertTrue(inc.isConst && inc.addConst)
        assertNull(inc.term)

        val dec = setArg(parser, "DAY--")
        assertEquals(-1L, dec.constInt)
        assertTrue(dec.isConst && dec.addConst)
        assertNull(dec.term)
    }

    @Test
    fun `常量增量与自增减落库等价于 ChangeValue`() {
        val (env, parser) = fixture()
        env.runLine(parser, "DAY = 10")
        env.runLine(parser, "DAY += 2")
        env.runLine(parser, "DAY -= 3")
        env.runLine(parser, "DAY++")
        env.runLine(parser, "DAY--")
        assertEquals(9L, env.store.readInt("DAY", longArrayOf(0)), "10+2-3+1-1")
    }

    /**
     * 语料锚点：`MOVEMENTS/DRAW_MAP.ERB:70,80,89` 的 `X_POINT += 2` / `X_POINT ++`，
     * 其中 `X_POINT` 是 `#DIM` 声明的 **0 维用户变量**（同文件 :14）。
     * 旧执行层只读 Term ⇒ rhs=0 ⇒ 常量增量全 no-op ⇒ DRAW_MAP 死循环。
     */
    @Test
    fun `零维用户变量的常量增量生效（DRAW_MAP 锚点）`() {
        val (env, parser) = fixture(DimSpec("X_POINT", false, intArrayOf()))
        env.runLine(parser, "X_POINT = 0")
        env.runLine(parser, "X_POINT += 2")
        env.runLine(parser, "X_POINT += 2")
        env.runLine(parser, "X_POINT ++")
        assertEquals(5L, env.store.readInt("X_POINT", longArrayOf()), "0 + 2 + 2 + 1")
    }

    // ------------------------------------------------------------------
    // 2. 非常量复合赋值：整式进 Term，执行层不二次套 op
    // ------------------------------------------------------------------

    @Test
    fun `非常量加等进整式且不设常量位`() {
        val (_, parser) = fixture()
        val arg = setArg(parser, "DAY += DAY")
        assertEquals(false, arg.isConst, "非 SingleTerm ⇒ C# 不设 IsConst（ArgumentParser.cs:1017-1018）")
        assertEquals(false, arg.addConst, "非 AddConst：走 SetValue 而不是 ChangeValue")
        val term = arg.term
        assertTrue(term is Binary, "Term 必须是整式 dest op rhs，实际 $term")
        assertEquals(OperatorCode.PLUS, (term as Binary).op)
    }

    @Test
    fun `非常量加等只应用一次`() {
        val (env, parser) = fixture()
        env.runLine(parser, "DAY = 10")
        env.runLine(parser, "DAY += DAY")
        assertEquals(20L, env.store.readInt("DAY", longArrayOf(0)), "整式 (DAY+DAY)=20，不得再套一次 op")
    }

    @Test
    fun `非常量乘等按整式落库`() {
        val (env, parser) = fixture()
        val arg = setArg(parser, "DAY *= 2")
        assertEquals(false, arg.isConst)
        assertEquals(false, arg.addConst, "乘等没有常量增量形态（ArgumentParser.cs:1017）")
        env.runLine(parser, "DAY = 10")
        env.runLine(parser, "DAY *= 2")
        assertEquals(20L, env.store.readInt("DAY", longArrayOf(0)))
    }

    /** 语料锚点：`ステータス計算関連/睡眠深度処理.ERB:9` 的 `LOCAL:1 += <表达式>`。 */
    @Test
    fun `带下标的局部变量非增量按整式落库`() {
        val (env, parser) = fixture()
        env.store.defineLocalLengths("T", VariableStore.LocalLengths(local = 3))
        env.store.enterFunction("T")
        env.runLine(parser, "LOCAL:1 = 3")
        env.runLine(parser, "LOCAL:2 = 4")
        env.runLine(parser, "LOCAL:1 += LOCAL:2 * 10")
        assertEquals(43L, env.store.readInt("LOCAL", longArrayOf(1)), "3 + 4*10")
    }

    // ------------------------------------------------------------------
    // 3. AddConst 的各类落点：plusInt 必须与 SetValue 走同一套判界/判类型
    // ------------------------------------------------------------------

    @Test
    fun `全局一维带下标表达式`() {
        val (env, parser) = fixture(DimSpec("IDX", false, intArrayOf()))
        env.runLine(parser, "IDX = 0")
        env.runLine(parser, "DAY:IDX = 10")
        env.runLine(parser, "DAY:IDX += 2")
        assertEquals(12L, env.store.readInt("DAY", longArrayOf(0)))
        assertEquals(0L, env.store.readInt("DAY", longArrayOf(1)), "下标表达式求值后只落在该元素上")
    }

    /** 语料锚点：`ステータス計算関連/泥酔処理.ERB:14` 的 `CFLAG:ARG:不會喝酒 -= 3`（角色变量）。 */
    @Test
    fun `角色变量常量减量`() {
        val (env, parser) = fixture()
        env.store.characters.addBare()
        env.runLine(parser, "CFLAG:0:5 = 10")
        env.runLine(parser, "CFLAG:0:5 -= 3")
        assertEquals(7L, env.store.readInt("CFLAG", longArrayOf(0, 5)))
    }

    /**
     * 角色用户变量（`#DIM CHARADATA`）的常量增量落点 —— [VariableStore.plusInt] 的 userVar 分支。
     * 语料同形态见 `睡眠深度処理.ERB:52` 的 `TCVAR:ARG:睡眠深度 -= LOCAL:1`（该处右边是局部变量，
     * 走整式 writeInt；本用例把右边换成常量，钉住同一条 userVar 路径上的 addConst 分支）。
     */
    @Test
    fun `角色用户变量常量减量`() {
        val (env, parser) = fixture(DimSpec("TCVARX", false, intArrayOf(5), isCharaData = true))
        env.runLine(parser, "TCVARX:0:2 = 10")
        env.runLine(parser, "TCVARX:0:2 -= 4")
        assertEquals(6L, env.store.readInt("TCVARX", longArrayOf(0, 2)))
    }

    @Test
    fun `二维用户变量带下标表达式常量加等`() {
        val (env, parser) = fixture(
            DimSpec("MAT", false, intArrayOf(3, 4)),
            DimSpec("IDX", false, intArrayOf()),
        )
        env.runLine(parser, "IDX = 1")
        env.runLine(parser, "MAT:IDX:2 = 5")
        env.runLine(parser, "MAT:IDX:2 += 7")
        env.runLine(parser, "MAT:(IDX+0):2 += 1")
        assertEquals(13L, env.store.readInt("MAT", longArrayOf(1, 2)), "5+7+1")
        assertEquals(0L, env.store.readInt("MAT", longArrayOf(0, 2)), "下标表达式不得落到 0 号元素")
    }

    /** 语料锚点：`睡眠深度処理.ERB:24-42` 的 `LOCAL:1 -= 250` 等（LocalInt1D 常量增量）。 */
    @Test
    fun `局部变量常量减量`() {
        val (env, parser) = fixture()
        env.store.defineLocalLengths("T", VariableStore.LocalLengths(local = 3))
        env.store.enterFunction("T")
        env.runLine(parser, "LOCAL:1 = 100")
        env.runLine(parser, "LOCAL:1 -= 250")
        assertEquals(-150L, env.store.readInt("LOCAL", longArrayOf(1)))
    }

    @Test
    fun `增量赋值与直接赋值同源判界`() {
        val (env, parser) = fixture(DimSpec("MAT", false, intArrayOf(3, 4)))
        val plus = assertFailsWith<CodeEE> { env.runLine(parser, "MAT:0:9 += 1") }
        assertEquals("变量 MAT 的第 2 维下标 9 越界（长度 4）", plus.message)
        val plain = assertFailsWith<CodeEE> { env.runLine(parser, "MAT:0:9 = 1") }
        assertEquals(plain.message, plus.message, "plusInt 不得绕过 SetValue 的越界判定")
    }

    /**
     * 伪变量在**解析期**就被拦成「左值是常量」，走不到执行期的 `AssignToPseudoVar`。
     *
     * spec: `ArgumentParser.cs:903-907` —— `SP_SET_ArgumentBuilder.CreateArgument` 在
     * 运算符分派**之前**先判 `variableTerm.Identifier.IsConst`，命中即
     * `assignwarn(LeftIsConst, level=2)`；`ArgumentBuilder.cs:19-28` 的 `assignwarn`
     * 对 `level >= 2` 置 `line.IsError`/`ErrMes` ⇒ 这是**装载期错误**，不是运行期 CodeEE。
     * `LINECOUNT` 的 `VariableCode` = 29491208（0x1C20008）含 `__UNCHANGEABLE__`(0x400000)
     * （`VariableCode.cs:11/208`）⇒ `IsConst` 为真（`VariableToken.cs:44`）。
     *
     * 2026-10-25 本处曾钉「执行期 `试图赋值给伪变量"LINECOUNT"`」：那条路在原版对
     * `SET` 不可达（`PseudoVariableToken.SetValue` 的 AssignToPseudoVar 只服务宿主 API /
     * 直调写入口），2026-10-26 深审波按上列基准行改钉。
     */
    @Test
    fun `伪变量赋值在解析期即被拒为常量`() {
        val (_, parser) = fixture()
        for (text in listOf("LINECOUNT += 1", "LINECOUNT = 1")) {
            val e = assertFailsWith<StmtParseException>("$text 应在解析期失败") {
                parser.parseCodeLine(text, 1)!!
            }
            assertTrue(e.message!!.contains("左值是常量"), "实际文案: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 4. 字符串形态不受影响：`+=` 一律整式，从不走 AddConst
    // ------------------------------------------------------------------

    @Test
    fun `字符串加等为整式且不设 AddConst`() {
        val (env, parser) = fixture(DimSpec("SBUF", true, intArrayOf()))
        val arg = setArg(parser, "SBUF += \"b\"")
        assertEquals(false, arg.addConst, "C# 字符串分支只走 ReduceBinaryTerm（ArgumentParser.cs:1101-1108）")
        assertEquals(false, arg.isConst)
        // 字符串 `=` 的右值是 FORM（ArgumentParser.cs:1032-1038），FORM 里 `"` 是字面量
        // （LexicalAnalyzer.cs:1083-1089：endWith != DoubleQuotation 时原样 append）——
        // 所以 `SBUF = "a"` 落库就是带引号的 `"a"`，与原版一致。
        env.runLine(parser, "SBUF = \"a\"")
        assertEquals("\"a\"", env.store.readStr("SBUF", longArrayOf()), "字符串 = 右值走 FORM，引号是字面量")
        // `+=` 走表达式路径（ArgumentParser.cs:1046-1071）：`"b"` 是字符串字面量（不含引号），
        // 于是 `"a"` + b = `"a"b`。
        env.runLine(parser, "SBUF += \"b\"")
        assertEquals("\"a\"b", env.store.readStr("SBUF", longArrayOf()))
    }

    // ------------------------------------------------------------------
    // 5. 生产链路：.erbc 往返后 isConst / constInt / addConst 仍能落库
    // ------------------------------------------------------------------

    /**
     * 生产 erbcc 走 `.erbc`：若写读两侧同时丢掉 `isConst/constInt`（只剩 `addConst`），
     * 常量增量又会退化成 `dest = 0`。这里跑「解析 → 规范化 → 编码 → 解码 → 执行」全链。
     */
    @Test
    fun `erbc 往返后常量增量仍落库`() {
        val symbols = SymbolTable().declareVariable("X_POINT", OperandType.INT, 0)
        val parser = LineParser(symbols)
        val lines = listOf("@T", "X_POINT = 0", "X_POINT += 2", "X_POINT += 2", "X_POINT++")
        val stmts = lines.mapIndexedNotNull { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            parser.parseLogicalLine(LogicalLine(kind, "test.erb", i + 1, text))
        }
        val compiler = ErbProgramCompiler()
        val info = compiler.compileFunction(FuncSig("T", 0), stmts, setOf("T"))
        val program = ErbcReader.decode(ErbcWriter.encode(compiler.build("T")))

        val store = VariableStore()
        store.declareUserVar(DimSpec("X_POINT", false, intArrayOf()))
        ProcessRuntime(
            program,
            store,
            FunctionTable(listOf(FuncMeta(index = info.index, key = "T", defIndex = 0))),
            VmConfig(maxStepsPerRun = 100_000L),
        ).execute()

        assertEquals(5L, store.readInt("X_POINT", longArrayOf()), "0 + 2 + 2 + 1（AddConst 经 .erbc 往返后仍在）")
    }
}
