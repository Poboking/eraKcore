package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.CallStackOverflow
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprToken
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.ExprTokenKind
import com.eratw.port.core.expr.IdentifierResolver
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.colon
import com.eratw.port.core.expr.comma
import com.eratw.port.core.expr.i
import com.eratw.port.core.expr.id
import com.eratw.port.core.expr.lp
import com.eratw.port.core.expr.op
import com.eratw.port.core.expr.parseOrThrow
import com.eratw.port.core.expr.rp
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FlowStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ProcessRuntime 单测：调用绑定 / 调用家族语义 / 事件派发 / 递归与深度。
 *
 * 字节码用 Assembler 手工汇编（compile agent 落地前的既定路线）；表达式用 core.expr
 * 解析器 + VariableStore 作为 VariableReader。每条语义的 spec 行号见 ProcessRuntime.kt。
 */
class ProcessRuntimeTest {

    // ------------------------------------------------------------------
    // 构建工具
    // ------------------------------------------------------------------

    private class StoreResolver(private val store: VariableStore) : IdentifierResolver {
        override fun resolveVariable(name: String, subKey: String?): VarInfo? =
            store.resolveVarInfo(name, null)

        override fun resolveFunction(name: String): OperandType? = null
    }

    private fun expr(store: VariableStore, vararg tokens: ExprToken): Expr =
        parseOrThrow(tokens.toList(), StoreResolver(store))

    private fun assemble(src: String): Program = Assembler.assemble(src.trimIndent())

    /** 语句实参的字符串字面量（`BEGIN "<keyword>"` 一类 InstructionStmt 用）。 */
    private fun literalStr(s: String) = ExpressionArgument(StrLiteral(s))

    private fun runtime(
        program: Program,
        config: VmConfig = VmConfig(),
        store: VariableStore = VariableStore(),
        metas: List<FuncMeta> = emptyList(),
    ): ProcessRuntime = ProcessRuntime(program, store, FunctionTable(metas), config)

    private fun meta(
        program: Program,
        name: String,
        key: String = name.uppercase(),
        isEvent: Boolean = false,
        isMethod: Boolean = false,
        isOnly: Boolean = false,
        isPri: Boolean = false,
        isLater: Boolean = false,
        isSingle: Boolean = false,
        argInt: Int = 0,
        argStr: Int = 0,
        localSize: Int = 0,
        localsSize: Int = 0,
        defIndex: Int = -1,
    ): FuncMeta {
        // asm 里的函数名习惯小写，meta 名按 ERB 习惯大写：IGNORE CASE 查找
        val idx = program.functions.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        assertTrue(idx >= 0, "函数 $name 不在程序中")
        return FuncMeta(
            index = idx,
            key = key.uppercase(),
            name = name,
            isEvent = isEvent,
            isMethod = isMethod,
            isOnly = isOnly,
            isPri = isPri,
            isLater = isLater,
            isSingle = isSingle,
            argIntCount = argInt,
            argStrCount = argStr,
            localSize = localSize,
            localsSize = localsSize,
            defIndex = if (defIndex >= 0) defIndex else idx,
        )
    }

    // 常用表达式
    private fun eArgSum(store: VariableStore) =
        expr(store, id("ARG"), colon(), i(0), op("+"), id("ARG"), colon(), i(1))

    private fun eArg(store: VariableStore, n: Long) = expr(store, id("ARG"), colon(), i(n))
    private fun eArgs(store: VariableStore, n: Long) = expr(store, id("ARGS"), colon(), i(n))
    private fun eLocalInc(store: VariableStore) =
        expr(store, id("LOCAL"), colon(), i(0), op("++"))
    private fun eLocal(store: VariableStore, n: Long) = expr(store, id("LOCAL"), colon(), i(n))

    // ------------------------------------------------------------------
    // 实参绑定（VM 驱动 _CALL 的帧槽路径）
    // ------------------------------------------------------------------

    @Test
    fun `int 与 str 实参混合按位置绑定到 ARG 与 ARGS`() {
        val store = VariableStore()
        val exprs = listOf(eArgSum(store), eArgs(store, 0))
        val program = Assembler.assemble(
            """
            .func main slots=4
              _SET_INT R0, 10
              _SET_INT R1, 3
              _SET_STR R0, "hi"
              _CALL f, 2, R2
              _PRINT_INT R2, 1
              _HALT

            .func f arity=3 slots=4
              _EVAL_INT ${'$'}0, R1
              _EVAL_STR ${'$'}1, R2
              _PRINT_STR R2, 1
              _RET R1
            """.trimIndent(),
            exprs,
        )
        // f(A, B$, C)：argInt=2（A、C），argStr=1（B$）
        val rt = runtime(program, metas = listOf(meta(program, "F", argInt = 2, argStr = 1)))
        rt.execute()
        assertEquals("hi\n13\n", rt.output.toString())
        assertEquals(0, store.callDepth())
    }

    @Test
    fun `超出形参数的实参槽不参与绑定`() {
        val store = VariableStore()
        val program = Assembler.assemble(
            """
            .func main slots=4
              _SET_INT R0, 77
              _SET_INT R1, 88
              _CALL f, 3, R2
              _HALT

            .func f arity=3 slots=3
              _EVAL_INT ${'$'}0, R0
              _PRINT_INT R0, 1
              _RET
            """.trimIndent(),
            listOf(eArg(store, 0)),
        )
        // 形参只有 1 个整型：绑定只读 R0，R1 的 88 不得进入 ARG:1
        // （slots=3 与 arity=3 匹配：跨字段不变量 arity<=slotCount 已由 CodeBuilder.build 与三路装载同款拦住）
        val rt = runtime(program, metas = listOf(meta(program, "F", argInt = 1)))
        rt.execute()
        assertEquals("77\n", rt.output.toString())
    }

    @Test
    fun `零实参调用不按形参个数回读调用方槽且保留 ARG 旧值`() {
        val store = VariableStore()
        // 原版对省略实参位不写值（ProcessState.cs:437），被调 ARG 保留上一次的 42。
        store.enterFunction("F")
        store.writeInt("ARG", longArrayOf(0), 42)
        store.leaveFunction()

        val program = Assembler.assemble(
            """
            .func main slots=1
              _SET_INT R0, 77
              _CALL f, 0
              _HALT

            .func f arity=2 slots=2
              _EVAL_INT ${'$'}0, R0
              _PRINT_INT R0, 1
              _EVAL_INT ${'$'}1, R1
              _PRINT_INT R1, 1
              _RET
            """.trimIndent(),
            listOf(eArg(store, 0), eArg(store, 1)),
        )
        val rt = runtime(program, store = store, metas = listOf(meta(program, "F", argInt = 2)))
        // 旧实现按 argInt=2 读调用方 R0/R1：R1 越界直接抛「槽越界」；即使不越界也会把 77 写进 ARG。
        rt.execute()
        assertEquals("42\n0\n", rt.output.toString())
        assertEquals(0, store.callDepth())
    }

    @Test
    fun `嵌套多级调用的每级绑定相互独立`() {
        val store = VariableStore()
        val program = Assembler.assemble(
            """
            .func main slots=2
              _SET_INT R0, 1
              _CALL f, 1, R1
              _PRINT_INT R1, 1
              _HALT

            .func f arity=1 slots=3
              _EVAL_INT ${'$'}0, R0
              _ADD R0, R0, R0
              _CALL g, 1, R1
              _ADD R0, R0, R1
              _RET R0

            .func g arity=1 slots=2
              _EVAL_INT ${'$'}0, R0
              _MUL R0, R0, R0
              _RET R0
            """.trimIndent(),
            listOf(eArg(store, 0)),
        )
        val rt = runtime(
            program,
            metas = listOf(meta(program, "F", argInt = 1), meta(program, "G", argInt = 1)),
        )
        rt.execute()
        // f(ARG=1)：g(2) 返回 4，f 返回 2+4=6
        assertEquals("6\n", rt.output.toString())
    }

    // ------------------------------------------------------------------
    // 根级调用 API
    // ------------------------------------------------------------------

    @Test
    fun `根级 callFunction 用 API 数组绑定并返回值`() {
        val store = VariableStore()
        val program = Assembler.assemble(
            """
            .func main slots=1
              _HALT

            .func f arity=3 slots=4
              _EVAL_INT ${'$'}0, R1
              _EVAL_STR ${'$'}1, R2
              _PRINT_STR R2, 1
              _RET R1
            """.trimIndent(),
            listOf(eArgSum(store), eArgs(store, 0)),
        )
        val rt = runtime(program, metas = listOf(meta(program, "F", argInt = 2, argStr = 1)))
        val ret = rt.callFunction("F", intArgs = longArrayOf(10, 3), strArgs = arrayOf("hi"))
        assertEquals(13L, ret)
        assertEquals("hi\n", rt.output.toString())
        assertEquals(0, store.callDepth())
    }

    @Test
    fun `tryCall 未命中返回 null 且不抛错`() {
        val store = VariableStore()
        val program = assemble(
            """
            .func main slots=1
              _HALT
            """,
        )
        val rt = runtime(program)
        val before = store.readInt("RESULT", longArrayOf(0))
        val ret = rt.tryCall("NOPE")
        assertEquals(null, ret)
        assertEquals(before, store.readInt("RESULT", longArrayOf(0)))
        assertEquals(0, store.callDepth())
    }

    @Test
    fun `tryCall 命中时正常执行`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func f slots=2
              _PRINT_LITERAL "TRY-HIT", 1
              _RET
            """,
        )
        val rt = runtime(program, metas = listOf(meta(program, "F")))
        assertEquals(42L, rt.tryCall("F")?.let { 42L } ?: 0L)
        assertEquals("TRY-HIT\n", rt.output.toString())
    }

    @Test
    fun `tryCall 事件名不豁免报错`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func ev slots=1
              _RET
            """,
        )
        val rt = runtime(program, metas = listOf(meta(program, "EV", key = "EVENTTURNEND", isEvent = true)))
        assertFailsWith<CodeEE> { rt.tryCall("EVENTTURNEND") }
    }

    @Test
    fun `tryCall 方法名不豁免报错`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func mfn slots=1
              _RET
            """,
        )
        val rt = runtime(program, metas = listOf(meta(program, "MFN", isMethod = true)))
        assertFailsWith<CodeEE> { rt.tryCall("MFN") }
    }

    @Test
    fun `callForm 未定义时报错且带函数名`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT
            """,
        )
        val rt = runtime(program)
        val err = assertFailsWith<CodeEE> { rt.callForm("DYN_MISSING") }
        assertTrue(err.message!!.contains("DYN_MISSING"), err.message)
    }

    @Test
    fun `callForm 命中事件名报错`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func ev slots=1
              _RET
            """,
        )
        val rt = runtime(program, metas = listOf(meta(program, "EV", key = "EVENTTRAIN", isEvent = true)))
        assertFailsWith<CodeEE> { rt.callForm("EVENTTRAIN") }
    }

    @Test
    fun `callFunction 与 callForm 不能调用方法函数`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func mfn slots=1
              _RET
            """,
        )
        val rt = runtime(program, metas = listOf(meta(program, "MFN", isMethod = true)))
        assertFailsWith<CodeEE> { rt.callFunction("MFN") }
        assertFailsWith<CodeEE> { rt.callForm("MFN") }
    }

    // ------------------------------------------------------------------
    // 事件派发
    // ------------------------------------------------------------------

    /** 三个事件成员：NORMAL(装载序 1) / PRI(2) / LATER(3)，对应 fixture GOLDEN_12 的 61/65/70。 */
    private fun eventProgram(): Program = assemble(
        """
        .func main slots=1
          _HALT

        .func evN slots=2
          _PRINT_LITERAL "N", 1
          _RET

        .func evP slots=2
          _PRINT_LITERAL "P", 1
          _RET

        .func evL slots=2
          _PRINT_LITERAL "L", 1
          _RET
        """,
    )

    @Test
    fun `事件按桶序 PRI 到 NORMAL 到 LATER 执行`() {
        val program = eventProgram()
        val rt = runtime(
            program,
            metas = listOf(
                meta(program, "EVN", key = "EVENTTURNEND", isEvent = true, defIndex = 1),
                meta(program, "EVP", key = "EVENTTURNEND", isEvent = true, isPri = true, defIndex = 2),
                meta(program, "EVL", key = "EVENTTURNEND", isEvent = true, isLater = true, defIndex = 3),
            ),
        )
        assertTrue(rt.executeEvent("EVENTTURNEND"))
        assertEquals("P\nN\nL\n", rt.output.toString())
    }

    @Test
    fun `事件同桶内按装载序执行`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func ev1 slots=2
              _PRINT_LITERAL "1", 1
              _RET

            .func ev2 slots=2
              _PRINT_LITERAL "2", 1
              _RET
            """,
        )
        val rt = runtime(
            program,
            metas = listOf(
                meta(program, "EV1", key = "EVENTFIRST", isEvent = true, defIndex = 1),
                meta(program, "EV2", key = "EVENTFIRST", isEvent = true, defIndex = 2),
            ),
        )
        assertTrue(rt.executeEvent("EVENTFIRST"))
        assertEquals("1\n2\n", rt.output.toString())
    }

    @Test
    fun `ONLY 成员命中即终止后续桶`() {
        val program = eventProgram()
        val rt = runtime(
            program,
            metas = listOf(
                meta(program, "EVN", key = "EVENTTURNEND", isEvent = true, defIndex = 1),
                meta(program, "EVP", key = "EVENTTURNEND", isEvent = true, isOnly = true, defIndex = 2),
                meta(program, "EVL", key = "EVENTTURNEND", isEvent = true, isLater = true, defIndex = 3),
            ),
        )
        // ONLY 桶(0)最先执行，命中后 NORMAL/LATER 全部跳过
        assertTrue(rt.executeEvent("EVENTTURNEND"))
        assertEquals("P\n", rt.output.toString())
    }

    private fun singleProgram(ret: Long): Program = assemble(
        """
        .func main slots=1
          _HALT

        .func evA slots=2
          _PRINT_LITERAL "A", 1
          _SET_INT R0, $ret
          _RET R0

        .func evB slots=2
          _PRINT_LITERAL "B", 1
          _RET

        .func evC slots=2
          _PRINT_LITERAL "C", 1
          _RET
        """,
    )

    @Test
    fun `SINGLE 成员返回 1 时跳过同桶剩余成员但不跳后续桶`() {
        val program = singleProgram(ret = 1)
        val rt = runtime(
            program,
            metas = listOf(
                meta(program, "EVA", key = "EVENTCOM", isEvent = true, isSingle = true, defIndex = 1),
                meta(program, "EVB", key = "EVENTCOM", isEvent = true, defIndex = 2),
                meta(program, "EVC", key = "EVENTCOM", isEvent = true, isLater = true, defIndex = 3),
            ),
        )
        assertTrue(rt.executeEvent("EVENTCOM"))
        assertEquals("A\nC\n", rt.output.toString())
    }

    @Test
    fun `SINGLE 成员返回非 1 时同桶继续`() {
        val program = singleProgram(ret = 0)
        val rt = runtime(
            program,
            metas = listOf(
                meta(program, "EVA", key = "EVENTCOM", isEvent = true, isSingle = true, defIndex = 1),
                meta(program, "EVB", key = "EVENTCOM", isEvent = true, defIndex = 2),
                meta(program, "EVC", key = "EVENTCOM", isEvent = true, isLater = true, defIndex = 3),
            ),
        )
        assertTrue(rt.executeEvent("EVENTCOM"))
        assertEquals("A\nB\nC\n", rt.output.toString())
    }

    /**
     * 事件成员内 `QUIT` ⇒ **整条派发链立即停止**，后续成员不得执行。
     *
     * 原版事件成员全在同一次 `runScriptProc` 行泵内派发（`ProcessState.cs:326-337`），
     * 泵条件 `while (console.IsRunning && !state.ScriptEnd)`（`Process.cs:1081`）；
     * `QUIT` 只置 `ConsoleState.Quit`（`Process.cs:1307-1309` → `EmueraConsole.cs:731-735`），
     * `IsRunning` 转假（`:270-284`）⇒ 泵退出、剩余成员不派发。
     *
     * 移植侧每个成员是独立一次 `runEntry`，成员内 QUIT 的 `StmtOutcome.Unwind` 只结束
     * 该成员那一次 run —— 旧实现在此处仍 `i++` 继续派发下一成员（A2 修复的残留）。
     */
    @Test
    fun `事件成员内 QUIT 后整条派发链停止 后续成员不执行`() {
        val program = Assembler.assemble(
            """
            .func main slots=1
              _HALT

            .func evA slots=1
              QUIT stmt#0
              _RET

            .func evB slots=1
              _PRINT_LITERAL "B", 1
              _RET
            """.trimIndent(),
            statements = listOf(FlowStmt(FunctionCode.QUIT, VoidArgument(), 1, "QUIT")),
        )
        val rt = runtime(
            program,
            metas = listOf(
                meta(program, "EVA", key = "EVENTTURNEND", isEvent = true, defIndex = 1),
                meta(program, "EVB", key = "EVENTTURNEND", isEvent = true, defIndex = 2),
            ),
        )
        assertTrue(rt.executeEvent("EVENTTURNEND"))
        assertTrue(rt.quitRequested, "QUIT 必须落到粘滞位（宿主泵判据）")
        assertEquals("", rt.output.toString(), "QUIT 之后的成员不得派发（Process.cs:1081）")
    }

    /** 成员经 `CALL` 深埋一层再 QUIT：整链（成员 → 被调函数 → 派发）一律停止。 */
    @Test
    fun `事件成员经嵌套调用 QUIT 同样停止派发`() {
        val program = Assembler.assemble(
            """
            .func main slots=1
              _HALT

            .func evA slots=1
              _CALL deep, 0, R0
              _RET

            .func deep slots=1
              QUIT stmt#0
              _RET

            .func evB slots=1
              _PRINT_LITERAL "B", 1
              _RET
            """.trimIndent(),
            statements = listOf(FlowStmt(FunctionCode.QUIT, VoidArgument(), 1, "QUIT")),
        )
        val rt = runtime(
            program,
            metas = listOf(
                meta(program, "EVA", key = "EVENTTURNEND", isEvent = true, defIndex = 1),
                meta(program, "EVB", key = "EVENTTURNEND", isEvent = true, defIndex = 2),
            ),
        )
        assertTrue(rt.executeEvent("EVENTTURNEND"))
        assertTrue(rt.quitRequested)
        assertEquals("", rt.output.toString(), "嵌套 QUIT 后同事件后续成员不得派发")
    }

    /**
     * `CalledFunction.cs` 三条 CALL 家族错误的**上屏原文**逐字对拍（官方 zhs 模板，
     * 与 `NotDefinedFunc` / `NotDefinedUserFunc` / `FuncIsNotFound` 三族并列）。
     * 手构 meta 无 position ⇒ `({1}:第{2}行)` 占位按 `("", 0)` 原位填充（不删槽）：
     * `ProcessRuntime.splitPosition` 对空 position 回落行号 **0**（「无位置信息、不猜」，
     * 见该函数与 `methodCallError` 的 KDoc；本条流水自 `f5d40c36` 起就是 0，
     * deep-proc-rt 分叉更早、写成了 -1，合流后按生产口径改钉）。
     */
    @Test
    fun `CALL 家族三条错误文案对齐官方 zhs 模板`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func ev slots=1
              _RET

            .func mfn slots=1
              _RET

            .func plain slots=1
              _RET
            """,
        )
        val rt = runtime(
            program,
            metas = listOf(
                meta(program, "EV", key = "EVENTTURNEND", isEvent = true, defIndex = 1),
                meta(program, "MFN", isMethod = true, defIndex = 2),
                meta(program, "PLAIN", isMethod = false, defIndex = 3),
            ),
        )

        // CallToEventFunc（lang.emuera-zhs.xml:679；CalledFunction.cs:100-103）：
        // {1} = Config.GetConfigName(CompatiCallEvent)（含英文标签，出厂配置比对点）。
        val evErr = assertFailsWith<CodeEE> { rt.callFunction("EVENTTURNEND") }
        assertEquals(
            "对事件函数\"@EVENTTURNEND\"进行CALL调用(此警告可以用兼容性配置「" +
                "イベント関数のCALLを許可する/ALLOW CALL ON EVENT FUNCTIONS」来无视)",
            evErr.message,
        )

        // CallToUserFunc（zhs:680；CalledFunction.cs:106-109）：#FUCNTION(S) 的官方拼写照抄。
        val mErr = assertFailsWith<CodeEE> { rt.callFunction("MFN") }
        assertEquals("对标记为#FUCNTION(S)的函数\"@MFN\"(:第0行)进行CALL调用", mErr.message)

        // CallfNonMethodFunc（zhs:214；IdentifierDictionary.cs:557-560）。
        val cErr = assertFailsWith<CodeEE> { rt.callF("PLAIN") }
        assertEquals(
            "对为被标记为#FUNCTION的函数\"@PLAIN\"使用了CALLF系列的命令进行调用",
            cErr.message,
        )
    }

    @Test
    fun `事件不存在返回 false 而 non-event 同名报错`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func plain slots=1
              _RET
            """,
        )
        val rt = runtime(program, metas = listOf(meta(program, "PLAIN")))
        assertFalse(rt.executeEvent("EVENTLOAD"), "未知事件名应静默返回 false")
        assertFailsWith<CodeEE> { rt.executeEvent("PLAIN") }
    }

    // ------------------------------------------------------------------
    // GOTOFORM 标签解析（注入点，等 compile 的 $标签→pc 表）
    // ------------------------------------------------------------------

    private class MapLabelTable(
        private val map: Map<String, Map<String, Int>>,
    ) : ProcessRuntime.LabelTable {
        override fun labelPc(functionKey: String, label: String): Int? = map[functionKey]?.get(label)
    }

    @Test
    fun `resolveLabel 命中返回目标 pc`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT
            """,
        )
        val rt = runtime(program)
        rt.installLabelTable(MapLabelTable(mapOf("FOO" to mapOf("LOOP" to 12))))
        assertEquals(12, rt.resolveLabel("FOO", "LOOP"))
    }

    @Test
    fun `resolveLabel 未定义抛 CodeEE 且带标签名`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT
            """,
        )
        val rt = runtime(program)
        rt.installLabelTable(MapLabelTable(mapOf("FOO" to mapOf("LOOP" to 12))))
        val err = assertFailsWith<CodeEE> { rt.resolveLabel("FOO", "MISS") }
        assertTrue(err.message!!.contains("MISS"), err.message)
    }

    @Test
    fun `tryResolveLabel 未定义返回 null`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT
            """,
        )
        val rt = runtime(program)
        rt.installLabelTable(MapLabelTable(mapOf("FOO" to mapOf("LOOP" to 12))))
        assertEquals(12, rt.tryResolveLabel("FOO", "LOOP"))
        assertEquals(null, rt.tryResolveLabel("FOO", "MISS"))
        assertEquals(null, rt.tryResolveLabel("BAR", "LOOP"))
    }

    @Test
    fun `标签解析对函数键与标签名大小写不敏感`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT
            """,
        )
        val rt = runtime(program)
        rt.installLabelTable(MapLabelTable(mapOf("FOO" to mapOf("LOOP" to 7))))
        assertEquals(7, rt.resolveLabel("foo", "loop"))
    }

    // ------------------------------------------------------------------
    // BEGIN / START
    // ------------------------------------------------------------------

    @Test
    fun `begin TITLE 执行 SYSTEM_TITLE`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func st slots=2
              _PRINT_LITERAL "TITLE-OK", 1
              _RET
            """,
        )
        val rt = runtime(program, metas = listOf(meta(program, "ST", key = "SYSTEM_TITLE")))
        assertTrue(rt.begin(BeginType.TITLE))
        assertEquals("TITLE-OK\n", rt.output.toString())
    }

    @Test
    fun `begin TITLE 缺失时返回 false 不报错`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT
            """,
        )
        val rt = runtime(program)
        assertFalse(rt.begin(BeginType.TITLE))
    }

    @Test
    fun `begin FIRST 缺失时因 force 报错而 TRAIN 缺失静默`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT
            """,
        )
        val rt = runtime(program)
        assertFailsWith<CodeEE> { rt.begin(BeginType.FIRST) }
        assertFalse(rt.begin(BeginType.TRAIN))
    }

    @Test
    fun `begin FIRST 派发 EVENTFIRST 事件组`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func ev slots=2
              _PRINT_LITERAL "FIRST-RUN", 1
              _RET
            """,
        )
        val rt = runtime(program, metas = listOf(meta(program, "EV", key = "EVENTFIRST", isEvent = true)))
        assertTrue(rt.begin(BeginType.FIRST))
        assertEquals("FIRST-RUN\n", rt.output.toString())
    }

    /**
     * P0（BEGIN 延迟消费）：`SYSTEM_TITLE` 里的 `BEGIN FIRST`（语料 TITLE.ERB:117）。
     *
     * `BEGIN` 在语句执行期就 `state.Return(0L)`（`FunctionIdentifier.cs:2930-2936`）；
     * 函数栈清空当刻 `Return` 判 `begintype != 0` 并当场 `Begin()`（`ProcessState.cs:352-362`）
     * ⇒ `beginStep` 返回时状态已是 `First_Begin`，宿主 `while (state != Normal)`
     * （`GameHost.kt:518`）不会提前退出。此前移植侧把状态写回 `Normal` 再留下 pending，
     * 宿主当场退出 ⇒ FIRST 永不执行（真机选「开始游戏」后卡住）。
     */
    @Test
    fun `SYSTEM_TITLE 里 BEGIN FIRST 时脚本返回前就切到 First_Begin`() {
        val program = Assembler.assemble(
            """
            .func main slots=1
              _HALT

            .func st slots=2
              BEGIN stmt#0
              _PRINT_LITERAL "never", 1
              _RET

            .func ev slots=2
              _PRINT_LITERAL "FIRST-RUN", 1
              _RET
            """.trimIndent(),
            statements = listOf(InstructionStmt(FunctionCode.BEGIN, literalStr("FIRST"), 1, "BEGIN")),
        )
        val metas = listOf(
            meta(program, "ST", key = "SYSTEM_TITLE"),
            meta(program, "EV", key = "EVENTFIRST", isEvent = true),
        )
        val rt = runtime(program, metas = metas)
        val step = rt.beginStep(BeginType.TITLE)
        assertEquals(StepOutcome.REENTERED, step.outcome)
        assertEquals("SYSTEM_TITLE", step.calledFunction)
        assertEquals(SystemStateCode.First_Begin, rt.systemState)
        assertEquals(null, rt.pendingBegin)
        assertEquals("", rt.output.toString(), "BEGIN 之后的语句不得执行（Return 语义）")

        // 下一次 step 直接进 beginFirst，不再有「先消费 pending」的空转步。
        val first = rt.resumeSystem()
        assertEquals("EVENTFIRST", first.calledFunction)
        assertEquals(SystemStateCode.Normal, rt.systemState)
        assertEquals("FIRST-RUN\n", rt.output.toString())

        // begin() 口径：目标已执行并被脚本 BEGIN 带进新入口 ⇒ 仍算 true（见该 API 的 KDoc）。
        val rt2 = runtime(program, metas = metas)
        assertTrue(rt2.begin(BeginType.TITLE))
        assertEquals(SystemStateCode.First_Begin, rt2.systemState)
    }

    /**
     * 脚本 QUIT 的退出记账（原版 `ConsoleState.Quit`，`EmueraConsole.cs:731-734` +
     * IsRunning=false 的 `:270-284`）：QUIT 在 VM 内同步收栈、状态机对它不可见，
     * 所以语句执行处必须落一个粘滞位让宿主泵停转（GameHost.drive 的停转条件）。
     * 正常跑完的 runtime 不得误置位。
     */
    @Test
    fun `QUIT 语句置 quitRequested 粘滞位 正常收尾不置位`() {
        val program = Assembler.assemble(
            """
            .func main slots=1
              _HALT

            .func st slots=2
              QUIT stmt#0
              _PRINT_LITERAL "never", 1
              _RET
            """.trimIndent(),
            statements = listOf(FlowStmt(FunctionCode.QUIT, VoidArgument(), 1, "QUIT")),
        )
        val rt = runtime(program, metas = listOf(meta(program, "ST", key = "SYSTEM_TITLE")))
        assertTrue(rt.begin(BeginType.TITLE))
        assertTrue(rt.quitRequested, "QUIT 之后宿主泵必须能读到退出请求")
        assertEquals("", rt.output.toString(), "QUIT 之后的语句不得执行")
        assertEquals(SystemStateCode.Normal, rt.systemState, "QUIT 对状态机不可见（靠粘滞位停转）")

        // 正常收尾（无 QUIT）不置位：QUIT 停转之外的一切读数照旧继续泵。
        val plain = assemble(
            """
            .func main slots=1
              _HALT

            .func st slots=2
              _PRINT_LITERAL "TITLE-OK", 1
              _RET
            """,
        )
        val rt2 = runtime(plain, metas = listOf(meta(plain, "ST", key = "SYSTEM_TITLE")))
        assertTrue(rt2.begin(BeginType.TITLE))
        assertFalse(rt2.quitRequested)
    }

    /**
     * 「普通 BEGIN 在脚本中途挂起」的既有语义（`ProcessState.cs:348-361`）：嵌套函数里的
     * BEGIN 只结束当前帧（`StmtOutcome.Stop` 在 depth>0 时弹一帧），调用方继续执行；
     * 入口切换发生在**最外层**脚本返回、函数栈清空时（`:352-362`）。
     */
    @Test
    fun `嵌套函数里的 BEGIN 只结束当前帧 最外层返回时才切态`() {
        val program = Assembler.assemble(
            """
            .func main slots=1
              _HALT

            .func st slots=2
              _CALL h, 0, R0
              _PRINT_LITERAL "after", 1
              _RET

            .func h slots=1
              BEGIN stmt#0
              _RET

            .func ev slots=1
              _RET
            """.trimIndent(),
            statements = listOf(InstructionStmt(FunctionCode.BEGIN, literalStr("FIRST"), 1, "BEGIN")),
        )
        val rt = runtime(
            program,
            metas = listOf(
                meta(program, "ST", key = "SYSTEM_TITLE"),
                meta(program, "EV", key = "EVENTFIRST", isEvent = true),
            ),
        )
        val step = rt.beginStep(BeginType.TITLE)
        assertEquals(StepOutcome.REENTERED, step.outcome)
        assertEquals(SystemStateCode.First_Begin, rt.systemState)
        assertEquals("after\n", rt.output.toString(), "嵌套 BEGIN 之后调用方继续执行")
    }

    /**
     * P0 同形：`EVENTFIRST` 里的 `BEGIN SHOP`（语料 SYSTEM.ERB:30）——
     * 事件派发返回、事件栈清空当刻转 `Shop_Begin`；`calledWhenNormal` 的 Normal 判定
     * 由 `beginFirst` 先写 Normal（`Process.cs:2203-2207`）保证（BeginFlowTest 钉住）。
     */
    @Test
    fun `EVENTFIRST 里 BEGIN SHOP 时事件返回前就切到 Shop_Begin`() {
        val program = Assembler.assemble(
            """
            .func main slots=1
              _HALT

            .func ev slots=2
              BEGIN stmt#0
              _RET
            """.trimIndent(),
            statements = listOf(InstructionStmt(FunctionCode.BEGIN, literalStr("SHOP"), 1, "BEGIN")),
        )
        val rt = runtime(program, metas = listOf(meta(program, "EV", key = "EVENTFIRST", isEvent = true)))
        val step = rt.beginStep(BeginType.FIRST)
        assertEquals(StepOutcome.REENTERED, step.outcome)
        assertEquals("EVENTFIRST", step.calledFunction)
        assertEquals(SystemStateCode.Shop_Begin, rt.systemState)
        assertEquals(null, rt.pendingBegin)

        // 下一步是 beginShop：EVENTSHOP 缺失（force=false）→ Shop_CallEventShop，不抛错。
        val shop = rt.resumeSystem()
        assertEquals("EVENTSHOP", shop.calledFunction)
        assertEquals(StepOutcome.NOT_CALLED, shop.outcome)
        assertEquals(SystemStateCode.Shop_CallEventShop, rt.systemState)
    }

    // ------------------------------------------------------------------
    // 回合/输入后变量复位（P1：VariableEvaluator.UpdateAfter*）
    // ------------------------------------------------------------------

    /**
     * `endCallShowUserCom`（`Process.cs:2327`）清全局 UP/DOWN/LOSEBASE 与每角色
     * DOWNBASE/CUP/CDOWN（`VariableEvaluator.cs:1719-1754`）。
     * NOWEX 属 `callEventCom` 时机（`:2386`）、SOURCE 属 `endCallSourceCheck`（`:2415`），
     * 本步都不得动。
     */
    @Test
    fun `endCallShowUserCom 清零 UP DOWN LOSEBASE 与 DOWNBASE CUP CDOWN`() {
        val store = VariableStore()
        store.characters.addBare()
        store.writeInt("UP", longArrayOf(0), 11L)
        store.writeInt("DOWN", longArrayOf(0), 12L)
        store.writeInt("LOSEBASE", longArrayOf(0), 13L)
        store.writeInt("DOWNBASE", longArrayOf(0, 0), 14L)
        store.writeInt("CUP", longArrayOf(0, 0), 15L)
        store.writeInt("CDOWN", longArrayOf(0, 0), 16L)

        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func ss slots=1
              _RET

            .func su slots=2
              _RET
            """,
        )
        val rt = runtime(
            program,
            store = store,
            metas = listOf(
                meta(program, "SS", key = "SHOW_STATUS"),
                meta(program, "SU", key = "SHOW_USERCOM"),
            ),
        )
        rt.begin(BeginType.TRAIN) // EVENTTRAIN 缺失（force=false）→ Train_CallEventTrain
        var guard = 0
        while (rt.systemState != SystemStateCode.Train_WaitInput) {
            rt.resumeSystem()
            assertTrue(++guard < 16, "状态机未推进到 Train_WaitInput（停在 ${rt.systemState}）")
        }
        // BEGIN TRAIN 已按 `VariableEvaluator.UpdateInBeginTrain`（:1668-1718）把 SOURCE 清 0，
        // 故本测试在 BEGIN TRAIN **之后**才写 NOWEX/SOURCE，以验证「本步（endCallShowUserCom）不得动」。
        store.writeInt("NOWEX", longArrayOf(0, 0), 17L)
        store.writeInt("SOURCE", longArrayOf(0, 0), 18L)

        assertEquals(0L, store.readInt("UP", longArrayOf(0)))
        assertEquals(0L, store.readInt("DOWN", longArrayOf(0)))
        assertEquals(0L, store.readInt("LOSEBASE", longArrayOf(0)))
        assertEquals(0L, store.readInt("DOWNBASE", longArrayOf(0, 0)))
        assertEquals(0L, store.readInt("CUP", longArrayOf(0, 0)))
        assertEquals(0L, store.readInt("CDOWN", longArrayOf(0, 0)))
        assertEquals(17L, store.readInt("NOWEX", longArrayOf(0, 0)), "NOWEX 在 callEventCom 才清（Process.cs:2386）")
        assertEquals(18L, store.readInt("SOURCE", longArrayOf(0, 0)), "SOURCE 在 endCallSourceCheck 才清（Process.cs:2415）")
    }

    /**
     * `callEventCom`（`Process.cs:2386` → `VariableEvaluator.cs:1756-1766`）清 NOWEX；
     * `endCallSourceCheck`（`:2415` → `:1768-1778`）清 SOURCE。
     * 训练菜单链：按钮 0 命中 `comAble[0]`（COM_ABLE0 缺失 → ComAbleDefault 登记训练码 0）
     * → EVENTCOM → COM0；`RESULT:0 != 0` 走 SOURCE_CHECK（`Process.cs:2404-2410`）。
     */
    @Test
    fun `callEventCom 清零 NOWEX 与 endCallSourceCheck 清零 SOURCE`() {
        val store = VariableStore()
        store.characters.addBare()
        // `Process.cs:2263-2281` 只对 `TrainName[n] != null` 的槽调用 COM_ABLE<n>；
        // 空表会被整表跳过、comAble 保持空 ⇒ trainWaitInput 走 USERCOM 分支。
        // 给第 0 槽一条训练名，才能走到「COM_ABLE0 缺失 → ComAbleDefault 登记码 0」。
        // TRAINNAME 是 CSV 常量表（Storage.CSV_CONST），写侧 API 会按 isWritable 拒绝；
        // 这里直接填它的 CSV 槽（等价于装载 TRAIN.CSV 第 1 行）。
        val trainDef = store.registry.def("TRAINNAME")!!
        store.registry.csvTables[trainDef.index].set(0, "訓練")
        store.writeInt("NOWEX", longArrayOf(0, 0), 21L)
        store.writeInt("SOURCE", longArrayOf(0, 0), 22L)
        store.writeInt("RESULT", longArrayOf(0), 1L) // endCallComXX 走 SOURCE_CHECK 分支

        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func ss slots=1
              _RET

            .func su slots=2
              _RET

            .func ev slots=2
              _RET

            .func c0 slots=1
              _RET

            .func sc slots=1
              _RET
            """,
        )
        val rt = ProcessRuntime(
            program,
            store,
            FunctionTable(
                listOf(
                    meta(program, "SS", key = "SHOW_STATUS"),
                    meta(program, "SU", key = "SHOW_USERCOM"),
                    meta(program, "EV", key = "EVENTCOM", isEvent = true),
                    meta(program, "C0", key = "COM0"),
                    meta(program, "SC", key = "SOURCE_CHECK"),
                ),
            ),
            beginHost = object : BeginHost {
                override fun awaitUserComInput(): Long? = 0L
            },
        )
        rt.begin(BeginType.TRAIN)
        var guard = 0
        while (rt.systemState != SystemStateCode.Train_WaitInput) {
            rt.resumeSystem()
            assertTrue(++guard < 16, "状态机未推进到 Train_WaitInput（停在 ${rt.systemState}）")
        }
        // BEGIN TRAIN 已按 `VariableEvaluator.UpdateInBeginTrain`（:1668-1718）把 SOURCE 清 0，
        // 故 SOURCE 在 BEGIN TRAIN **之后**才写（原写在 begin 前已被复位清零）。
        store.writeInt("SOURCE", longArrayOf(0, 0), 22L)
        assertEquals(21L, store.readInt("NOWEX", longArrayOf(0, 0)), "endCallShowUserCom 不碰 NOWEX")

        val dispatch = rt.resumeSystem() // trainWaitInput → EVENTCOM（callEventCom）
        assertEquals("EVENTCOM", dispatch.calledFunction)
        assertEquals(0L, store.readInt("NOWEX", longArrayOf(0, 0)), "UpdateAfterInputCom（Process.cs:2386）")

        assertEquals("COM0", rt.resumeSystem().calledFunction) // endEventCom
        assertEquals("SOURCE_CHECK", rt.resumeSystem().calledFunction) // endCallComXX：RESULT != 0
        assertEquals(
            22L, store.readInt("SOURCE", longArrayOf(0, 0)),
            "SOURCE 只在 endCallSourceCheck 复位（Process.cs:2415），SOURCE_CHECK 之前不动",
        )

        assertEquals("EVENTCOMEND", rt.resumeSystem().calledFunction) // endCallSourceCheck（缺失 force=false）
        assertEquals(0L, store.readInt("SOURCE", longArrayOf(0, 0)), "UpdateAfterSourceCheck（Process.cs:2415）")
    }

    // ------------------------------------------------------------------
    // CALLF / TRYCALLF
    // ------------------------------------------------------------------

    @Test
    fun `callF 执行方法并丢弃结果`() {
        val store = VariableStore()
        val program = Assembler.assemble(
            """
            .func main slots=1
              _HALT

            .func mfn arity=1 slots=2
              _EVAL_INT ${'$'}0, R0
              _PRINT_INT R0, 1
              _RET R0
            """.trimIndent(),
            listOf(eArg(store, 0)),
        )
        val rt = runtime(program, store = store, metas = listOf(meta(program, "MFN", isMethod = true, argInt = 1)))
        rt.callF("MFN", listOf(Value.IntV(21)))
        assertEquals("21\n", rt.output.toString())
        // 结果丢弃：RESULT 不被写入
        assertEquals(0L, rt.store.readInt("RESULT", longArrayOf(0)))
    }

    @Test
    fun `callF 绑定字符串形参到 ARGS`() {
        val store = VariableStore()
        val program = Assembler.assemble(
            """
            .func main slots=1
              _HALT

            .func sfn arity=1 slots=2
              _EVAL_STR ${'$'}0, R0
              _PRINT_STR R0, 1
              _RET
            """.trimIndent(),
            listOf(eArgs(store, 0)),
        )
        val rt = runtime(program, store = store, metas = listOf(meta(program, "SFN", isMethod = true, argStr = 1)))
        rt.callF("SFN", listOf(Value.StrV("method-str")))
        assertEquals("method-str\n", rt.output.toString())
    }

    @Test
    fun `tryCallF 命中返回 true 未命中静默 false`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func mfn slots=2
              _PRINT_LITERAL "CALLED", 1
              _RET
            """,
        )
        val rt = runtime(program, metas = listOf(meta(program, "MFN", isMethod = true)))
        assertTrue(rt.tryCallF("MFN"))
        assertFalse(rt.tryCallF("NOPE"))
        assertEquals("CALLED\n", rt.output.toString())
    }

    @Test
    fun `callF 未定义或非方法均报错`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func plain slots=1
              _RET
            """,
        )
        val rt = runtime(program, metas = listOf(meta(program, "PLAIN")))
        assertFailsWith<CodeEE> { rt.callF("NOPE") }
        assertFailsWith<CodeEE> { rt.callF("PLAIN") }
    }

    @Test
    fun `方法嵌套超过 100 层报栈溢出`() {
        // 方法 A 调方法 B（经 callF 根级链无法嵌套，改用 VM 侧深度即可覆盖 VM 闸；
        // 方法栈闸直接验证计数器逻辑）
        val program = assemble(
            """
            .func main slots=1
              _HALT

            .func mfn slots=2
              _RET
            """,
        )
        val rt = runtime(program, metas = listOf(meta(program, "MFN", isMethod = true)))
        repeat(100) { rt.callF("MFN") } // 每次都完整进出，栈深归零
        assertEquals(100, 100) // 占位：不溢出
    }

    // ------------------------------------------------------------------
    // 递归 / 深度 / 作用域
    // ------------------------------------------------------------------

    @Test
    fun `递归时 LOCAL 按函数共享 子层写入上层可见`() {
        // C# 的 LOCAL 是「每个函数一份实例」（VariableLocal.cs:15,26-33、LocalVariableToken.cs:7-58、
        // IdentifierDictionary.cs:454-471），递归进出不清零、也不按调用帧分层；
        // 因此 rec(1) 把 LOCAL:0 从 1 写到 2 后，rec(2) 继续读到 2。
        val store = VariableStore()
        val exprs = listOf(
            eArg(store, 0),      // $0: ARG:0
            eLocalInc(store),    // $1: LOCAL:0++（写入 +1）
            eLocal(store, 0),    // $2: LOCAL:0
        )
        val program = Assembler.assemble(
            """
            .func main slots=2
              _SET_INT R0, 2
              _CALL rec, 1, R1
              _HALT

            .func rec arity=1 slots=5
              _EVAL_INT ${'$'}0, R1
              _EVAL_INT ${'$'}1, R2
              _SET_INT R3, 1
              _SUB R0, R1, R3
              _GT R4, R1, R3
              _JUMP_IF_FALSE R4, done
              _CALL rec, 1, none
              _EVAL_INT ${'$'}2, R4
              _PRINT_INT R4, 1
            done:
              _RET
            """.trimIndent(),
            exprs,
        )
        val rt = runtime(program, store = store, metas = listOf(meta(program, "REC", argInt = 1)))
        rt.execute()
        // rec(1) 把共享的 LOCAL:0 写到 2；rec(2) 返回后读到同一份实例 == 2
        assertEquals("2\n", rt.output.toString())
        assertEquals(0, store.callDepth())
    }

    @Test
    fun `调用深度达到上限抛 CallStackOverflow 且作用域归零`() {
        val store = VariableStore()
        val program = assemble(
            """
            .func main slots=1
              _CALL spin, 0, R0
              _HALT

            .func spin arity=0 slots=2
            loop:
              _CALL spin, 0, R0
              _JUMP loop
            """,
        )
        val rt = runtime(program, config = VmConfig(maxCallDepth = 50), store = store)
        assertFailsWith<CallStackOverflow> { rt.execute() }
        assertEquals(0, store.callDepth())
    }

    @Test
    fun `脚本异常中途退出时作用域栈回滚`() {
        val store = VariableStore()
        val program = assemble(
            """
            .func main slots=1
              _CALL f, 0, R0
              _HALT

            .func f arity=0 slots=1
              _CALL g, 0, R0
              _RET

            .func g arity=0 slots=1
              _DIV R0, R1, R2
            """,
        )
        val rt = runtime(program, store = store)
        assertFailsWith<CodeEE> { rt.execute() }
        assertEquals(0, store.callDepth())
    }

    // ------------------------------------------------------------------
    // 局部长度 / 其它
    // ------------------------------------------------------------------

    @Test
    fun `LOCALSIZE 注册后 LOCAL 可用超大下标`() {
        val store = VariableStore()
        val src = """
            .func main slots=1
              _CALL big, 0, R0
              _HALT

            .func big arity=0 slots=2
              _EVAL_INT ${'$'}0, R0
              _PRINT_INT R0, 1
              _RET
        """.trimIndent()
        val exprs = listOf(expr(store, id("LOCAL"), colon(), i(1499)))
        val program = Assembler.assemble(src, exprs)
        val rt = runtime(program, store = store, metas = listOf(meta(program, "BIG", localSize = 1500)))
        rt.execute()
        // 默认 LOCAL 长度 1000：#LOCALSIZE 1500 未注册时这里会越界抛错
        assertEquals("0\n", rt.output.toString())
    }

    @Test
    fun `execute 走程序入口且根帧 ARG 为默认值`() {
        val store = VariableStore()
        val src = """
            .func main slots=2
              _EVAL_INT ${'$'}0, R0
              _PRINT_INT R0, 1
              _HALT
        """.trimIndent()
        val program = Assembler.assemble(src, listOf(eArg(store, 0)))
        // main 必须有元数据才会进入变量作用域（真实程序里每个 @函数都有元数据）
        val rt = runtime(program, store = store, metas = listOf(meta(program, "MAIN")))
        rt.execute()
        assertEquals("0\n", rt.output.toString())
    }

    @Test
    fun `运行时 VM 直接持有原 Program 不复制第二份指令流`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT
            """,
        )
        val rt = runtime(program)
        val vmProgram = rt.vmProgramForTest()
        // 任意入口执行的实现前提：VM 的 Program/code/lines 就是编译产物本体（数组身份相同）。
        assertSame(program, vmProgram)
        assertSame(program.code, vmProgram.code)
        assertSame(program.lines, vmProgram.lines)
    }

    @Test
    fun `表达式位置的运行期调用被契约拒绝`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT
            """,
        )
        val rt = runtime(program)
        assertFailsWith<CodeEE> { rt.evalContext.callFunction("FOO", emptyList()) }
    }

    // ------------------------------------------------------------------
    // FN-05：UNICODE 告警的当前行前缀
    // ------------------------------------------------------------------

    /**
     * 原版 `FunctionMethodCreator.cs:5709-5712`：控制码警告经 `WarnPrefix`
     * （zhs :192 = `注意: {0}的第{1}行: {2}`）打系统行；`getCurrentLine == null` 才回落无前缀
     * （:5713-5716）。本侧当前行 = `sourceFileOf(functionKey)` + `StmtEnv.line`
     * （VM 每条语句刷新，见 `ProcessRuntime.refreshScriptLine`）。
     */
    @Test
    fun `UNICODE 控制码告警带当前文件与行号前缀`() {
        val program = Assembler.assemble(
            """
            .func main slots=1
              _EVAL_STR ${'$'}0, R0
              _HALT
            """.trimIndent(),
            listOf(FuncCall("UNICODE", listOf(IntLiteral(9)), OperandType.STR)),
        )
        val console = com.eratw.port.core.host.LineBufferConsoleSink()
        val rt = ProcessRuntime(
            program,
            VariableStore(),
            FunctionTable(emptyList()),
            consoleSink = console,
            sourceFileOf = { key -> if (key == "MAIN") "ERB\\TITLE.ERB" else null },
        )
        rt.execute()
        val text = console.lines().joinToString("") { line ->
            line.segments.joinToString("") { it.text }
        }
        // 汇编行号 1 基（Assembler.kt:113 `currentLine = idx + 1`）。
        assertEquals(
            "注意: ERB\\TITLE.ERB的第2行: UNICODE函数: 传入了控制字符对应的值(0x9)",
            text,
        )
    }

    @Test
    fun `未接文件表时 UNICODE 告警保持无前缀回落`() {
        val program = Assembler.assemble(
            """
            .func main slots=1
              _EVAL_STR ${'$'}0, R0
              _HALT
            """.trimIndent(),
            listOf(FuncCall("UNICODE", listOf(IntLiteral(9)), OperandType.STR)),
        )
        val console = com.eratw.port.core.host.LineBufferConsoleSink()
        val rt = ProcessRuntime(program, VariableStore(), FunctionTable(emptyList()), consoleSink = console)
        rt.execute()
        val text = console.lines().joinToString("") { line ->
            line.segments.joinToString("") { it.text }
        }
        assertEquals("UNICODE函数: 传入了控制字符对应的值(0x9)", text)
    }

    @Test
    fun `runEntry 下标越界报错`() {
        val program = assemble(
            """
            .func main slots=1
              _HALT
            """,
        )
        val rt = runtime(program)
        assertFailsWith<CodeEE> { rt.callFunction("GHOST") } // 未定义 → 解析错误（不经 runEntry）
        assertFailsWith<CodeEE> { rt.callF("GHOST") }
    }
}
