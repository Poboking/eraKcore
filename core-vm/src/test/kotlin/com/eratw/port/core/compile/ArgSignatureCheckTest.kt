package com.eratw.port.core.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **装载期实参签名校验**（CORE-05，[ArgSignatureCheck]）的合成用例。
 *
 * 现场：此前没有任何装载期实参校验，`CALL F(1)` 打到 `@F(A, B)` 上被省略的位静默按
 * `0` / `""` 执行；原版在装载期即红（spec: `CalledFunction.cs:138-199`
 * `ConvertArg` 的 TooManyFuncArgs / CanNotOmitArgWithMessage / CanNotConvert*，
 * 表达式位内建走 `IdentifierDictionary.cs:576-585` → `FunctionMethod.cs:302-330`）。
 *
 * 本文件锁四件事（对应任务的四条合成用例）：
 * 1. 实参**过多**（个数 > 形参）；
 * 2. 实参**过少**（形参位无实参、无默认值；`CompatiFuncArgOptional=NO` 口径）；
 * 3. **类型不符**（整型实参进 `$` 后缀字符串形参）；
 * 4. **正确**调用零告警；
 * 外加：严格模式逐条文案抛 [CompileException]、宽松模式只收集不抛、
 * 内建函数 arity（`POWER` 的 `int("POWER", 2, 2)` 元数据可读）、
 * `compatiFuncArgOptional=YES` 豁免、以及「校验不碰产物」的幂等证据。
 *
 * 口径全部走**传参形态**（[ArgSignatureCheck.checkProgram] 的 [ArgSignatureCheck.Options]
 * 入参 + 返回值显式接住），不写任何静态字段——[ArgSignatureCheck] 曾是全主源集
 * 唯一静态可变全局（三个 var），2026-10-24 架构审查收口后 object 无状态。
 */
class ArgSignatureCheckTest {

    /** 待编译函数：行 = 含 `@行` 的完整切片。 */
    private data class Fn(
        val name: String,
        val lines: List<String>,
        /**
         * 形参名覆盖（默认取 `@行` 解析结果）。
         *
         * 类型用例必须用它：词法层拒绝 '$'（原版同口径，spec: LexicalAnalyzer.cs:916-918），
         * 解析器产不出 `B$` 形参名，而 [ArgSignatureCheck] 读的是**调用方构造的**
         * [FuncSig.paramNames] —— 覆盖即模拟「装载侧喂进来字符串形参标记」。
         */
        val paramNames: List<String>? = null,
    )

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("F", OperandType.INT, 1)
            .declareVariable("G", OperandType.INT, 1)
            .declareFunction("F", OperandType.INT)
            .declareFunction("G", OperandType.INT)
            .declareFunction("POWER", OperandType.INT),
    )

    private fun logical(text: String, lineNo: Int) = LogicalLine(
        kind = when {
            text.startsWith("@") -> LineKind.FUNCTION_LABEL
            text.startsWith("$") -> LineKind.GOTO_LABEL
            text.startsWith("#") -> LineKind.SHARP
            else -> LineKind.CODE
        },
        filename = "argsig.erb",
        lineNo = lineNo,
        content = text,
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text -> p.parseLogicalLine(logical(text, i + 1)) }
    }

    /**
     * 解析 → 编译（形参数/形参名从 `@行` 解析结果取）→ 冻结。
     *
     * [ErbProgramCompiler.build] 的钩子会在返回前以缺省 [ArgSignatureCheck.Options]
     * 跑 [ArgSignatureCheck.checkProgram]（宽松口径，返回值由钩子丢弃）。断言读数
     * 一律对返回的 [Program] 再显式调 [ArgSignatureCheck.checkProgram] 接住
     * （校验幂等纯净：不碰产物、同输入重复调用读数逐条相同）。
     */
    private fun compile(vararg fns: Fn): Program {
        val parsed = fns.map { parse(*it.lines.toTypedArray()) }
        val labels = parsed.map { stmts ->
            stmts.filterIsInstance<FuncLabelStmt>().firstOrNull() ?: error("切片缺 @行")
        }
        val compiler = ErbProgramCompiler()
        val known = fns.mapTo(HashSet()) { it.name.uppercase() }
        for ((i, f) in fns.withIndex()) {
            compiler.compileFunction(
                FuncSig(f.name, labels[i].paramNames.size, paramNames = f.paramNames ?: labels[i].paramNames),
                parsed[i],
                known,
            )
        }
        return compiler.build(fns.first().name)
    }

    /** 合成切片：调用方一行 `CALL` + 被调定义。 */
    private fun callCase(callerLine: String, calleeHead: String): Array<Fn> = arrayOf(
        Fn("CALLER", listOf("@CALLER", callerLine, "RETURN")),
        Fn("F", listOf(calleeHead, "RETURN")),
    )

    // ------------------------------------------------------------------
    // 四条合成用例
    // ------------------------------------------------------------------

    @Test
    fun `实参过多 - 个数大于形参`() {
        val program = compile(*callCase("CALL F(1, 2)", "@F(A)"))
        val ws = ArgSignatureCheck.checkProgram(program)
        assertEquals(1, ws.size, "只报一处")
        val w = ws.single()
        assertEquals(ArgSignatureWarningKind.TOO_MANY, w.kind)
        assertEquals("F", w.callee)
        assertEquals("CALLER", w.caller)
        assertEquals(2, w.argc)
        assertEquals(1, w.arity)
        assertTrue(w.message.contains("参数过多"), "文案：${w.message}")
        assertTrue(w.message.contains("形参 1 个，实参 2 个"), "文案带个数上下文：${w.message}")
    }

    @Test
    fun `实参过少 - 形参位无实参且无默认值`() {
        val program = compile(*callCase("CALL F(1)", "@F(A, B)"))
        val ws = ArgSignatureCheck.checkProgram(program)
        assertEquals(1, ws.size, "只报一处（第 2 个形参位）")
        val w = ws.single()
        assertEquals(ArgSignatureWarningKind.TOO_FEW, w.kind)
        assertEquals("F", w.callee)
        assertEquals(2, w.position, "位号 1 基")
        assertEquals(1, w.argc)
        assertEquals(2, w.arity)
        assertTrue(w.message.contains("不能省略"), "文案：${w.message}")
    }

    @Test
    fun `类型不符 - 整型实参进字符串形参`() {
        // `$` 后缀 = 字符串形参标记（FuncSig.paramNames 口径）。词法层拒绝 '$'，故形参名
        // 经 Fn.paramNames 覆盖直接喂给 FuncSig（装载侧构造签名的同一形态）。
        val program = compile(
            Fn("CALLER", listOf("@CALLER", "CALL F(1, 2)", "RETURN")),
            Fn("F", listOf("@F(A, B)", "RETURN"), paramNames = listOf("A", "B$")),
        )
        val ws = ArgSignatureCheck.checkProgram(program)
        assertEquals(1, ws.size, "个数够（2=2），只报类型")
        val w = ws.single()
        assertEquals(ArgSignatureWarningKind.TYPE_MISMATCH, w.kind)
        assertEquals("F", w.callee)
        assertEquals(2, w.position, "第 2 个实参位")
        assertTrue(w.message.contains("字符串"), "文案：${w.message}")
    }

    @Test
    fun `正确调用 - 零告警`() {
        val program = compile(
            Fn("CALLER", listOf("@CALLER", "CALL F(1, \"x\")", "RETURN")),
            Fn("F", listOf("@F(A, B)", "RETURN"), paramNames = listOf("A", "B$")),
        )
        assertTrue(ArgSignatureCheck.checkProgram(program).isEmpty(), "正确调用不产告警")
        assertTrue(program.instructionCount > 0, "照常出字节码")
    }

    // ------------------------------------------------------------------
    // 严格 / 宽松双模式（Options 入参选择加入，不再写静态字段）
    // ------------------------------------------------------------------

    @Test
    fun `严格模式 - 聚合逐条文案并抛`() {
        val fns = arrayOf(
            Fn("CALLER", listOf("@CALLER", "CALL F(1, 2)", "CALL G(1)", "RETURN")),
            Fn("F", listOf("@F(A)", "RETURN")),
            Fn("G", listOf("@G(A, B)", "RETURN")),
        )
        val program = compile(*fns)
        // 宽松读数先行接住告警全集（严格抛出路径聚合的就是它；抛出即无返回值可接）
        assertEquals(2, ArgSignatureCheck.checkProgram(program).size)
        // 严格口径经 Options 入参选择加入：聚合全部不符项、逐条文案抛
        val ex = try {
            ArgSignatureCheck.checkProgram(program, ArgSignatureCheck.Options(strict = true))
            error("严格模式应当抛 CompileException")
        } catch (e: CompileException) {
            e
        }
        // 逐条文案：过多 + 过少各一句
        assertTrue(ex.message!!.contains("参数过多"), "过多文案：${ex.message}")
        assertTrue(ex.message!!.contains("不能省略"), "过少文案：${ex.message}")
        assertTrue(ex.message!!.contains("失败 2 处"), "聚合计数：${ex.message}")
    }

    @Test
    fun `宽松模式 - 只收集不抛`() {
        val fns = arrayOf(
            Fn("CALLER", listOf("@CALLER", "CALL F(1, 2)", "CALL G(1)", "RETURN")),
            Fn("F", listOf("@F(A)", "RETURN")),
            Fn("G", listOf("@G(A, B)", "RETURN")),
        )
        // strict 缺省 false：同样错的脚本，编译照常返回 Program
        val program = compile(*fns)
        assertEquals(2, ArgSignatureCheck.checkProgram(program).size, "全收集、不抛")
        assertTrue(program.instructionCount > 0, "宽松模式不阻断编译")
    }

    // ------------------------------------------------------------------
    // 内建函数 arity（BuiltinRegistry 元数据；POWER = int("POWER", 2, 2)）
    // ------------------------------------------------------------------

    @Test
    fun `内建函数 arity - 过多与正确`() {
        // 表达式位内建调用降级不了（不是 #FUNCTION 用户方法），FuncCall 节点留在语句池
        val tooMany = compile(Fn("CALLER", listOf("@CALLER", "A = POWER(1, 2, 3)", "RETURN")))
        val ws = ArgSignatureCheck.checkProgram(tooMany)
        assertEquals(1, ws.size, "POWER 实参 3 个 > 登记上限 2")
        val w = ws.single()
        assertEquals(ArgSignatureWarningKind.TOO_MANY, w.kind)
        assertEquals("POWER", w.callee)
        assertEquals(3, w.argc)
        assertEquals(2, w.arity, "arity 读数 = 注册表 minArgs")
        assertTrue(w.message.contains("需要 2 个参数，但传入了 3 个"), "文案：${w.message}")

        val exact = compile(Fn("CALLER", listOf("@CALLER", "A = POWER(2, 3)", "RETURN")))
        assertTrue(ArgSignatureCheck.checkProgram(exact).isEmpty(), "2 个实参命中 [2, 2] 区间")
    }

    // ------------------------------------------------------------------
    // 兼容口径与产物不变性
    // ------------------------------------------------------------------

    @Test
    fun `compatiFuncArgOptional=YES - 缺参不报`() {
        // 兼容档经 Options 入参选择加入（原为写静态字段再 try/finally 复位）
        val program = compile(*callCase("CALL F(1)", "@F(A, B)"))
        val ws = ArgSignatureCheck.checkProgram(
            program,
            ArgSignatureCheck.Options(compatiFuncArgOptional = true),
        )
        assertTrue(ws.isEmpty(), "兼容档下缺参合法")
    }

    @Test
    fun `校验不碰产物 - 重复 build 幂等`() {
        val fns = arrayOf(
            Fn("CALLER", listOf("@CALLER", "CALL F(1, 2)", "RETURN")),
            Fn("F", listOf("@F(A)", "RETURN")),
        )
        val parsed = fns.map { parse(*it.lines.toTypedArray()) }
        val labels = parsed.map { it.filterIsInstance<FuncLabelStmt>().first() }
        val compiler = ErbProgramCompiler()
        val known = fns.mapTo(HashSet()) { it.name.uppercase() }
        for ((i, f) in fns.withIndex()) {
            compiler.compileFunction(
                FuncSig(f.name, labels[i].paramNames.size, paramNames = labels[i].paramNames),
                parsed[i],
                known,
            )
        }
        val first = compiler.build("CALLER")
        val warnings1 = ArgSignatureCheck.checkProgram(first)
        val second = compiler.build("CALLER")
        val warnings2 = ArgSignatureCheck.checkProgram(second)
        assertEquals(first.instructionCount, second.instructionCount, "指令数不变")
        assertEquals(first.statementCount, second.statementCount, "语句池不变")
        assertEquals(first.functions.size, second.functions.size, "函数表不变")
        assertEquals(warnings1, warnings2, "告警读数幂等")
        assertEquals(1, warnings1.size)
    }
}
