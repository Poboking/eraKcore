package com.eratw.port.core.proc

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 引擎级函数屏蔽（[EngineFunctionPolicy]）的端到端锁定。
 *
 * 判据只有一条：**被屏蔽函数 = 空体 `RETURN 0`**。逐形态钉住：
 * - 静态 `_CALL`（非尾跳 / 尾跳两种 b 位）；
 * - 动态 `_CALL_BY_NAME`（CALLFORM 家族）；
 * - 根级 API（[ProcessRuntime.callFunction]，直接以目标函数为第 0 帧、不走 `callInto`）。
 *
 * 另外钉住两件容易漏的事：
 * - 关掉开关（`disabledFunctions = emptySet()`）时行为与改造前**逐字相同**（函数体照跑）；
 * - 名字匹配大小写不敏感（口径同 `FuncMeta.key` / `Config.StrComper`，spec: `Config.cs:33`）。
 *
 * 为什么必须由引擎兜：`@BADAPPLE` 这类函数（6573 帧 `AWAIT 16` ≈ 105 秒的标题彩蛋）
 * 只烧时间不产内容，而语料不进仓 —— 靠每个玩家手工注释 `ERB/TITLE.ERB` 既不可持续，
 * 也会被语料升级覆盖。语料侧改动在这里是**零**，全靠这张表。
 */
class EngineFunctionStubTest {

    private companion object {
        /** `Opcode.JUMP_FLAG`（b 操作数 bit30）的契约值，本文件按字面量复刻以钉住位序。 */
        const val JUMP_FLAG = 0x40000000
    }

    /**
     * main(0) 调 [target]，随后打印 `AFTER`；被调函数体打印 `PLAY` 再 `RETURN 0`。
     *
     * ```
     * main:
     *   _CALL <target>, b=argc|flags, c=R1     // R1 = 返回值槽
     *   _PRINT_LITERAL "AFTER", 换行
     *   _HALT
     * <target>:
     *   _PRINT_LITERAL "PLAY", 换行
     *   _RET none
     * ```
     *
     * 函数下标：main = 0、target = 1。
     */
    private fun buildProgram(target: String, callFlags: Int = 0, argc: Int = 0): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2) // R0 = 实参槽，R1 = 返回槽
        val callPc = b.emit(Opcode._CALL, 0, argc or callFlags, SlotAddr.local(1), 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("AFTER"), 1, 0, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)
        b.setFunctionSlots(main, 2)

        val targetPc = b.here()
        val idx = b.declareFunction(target, targetPc, argc, 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("PLAY"), 1, 0, 4)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 5)
        b.setFunctionSlots(idx, 1)

        b.patch(callPc, 1, idx) // a = 被调函数下标
        return b.build(main)
    }

    /** 函数名 → `Program.functions` 下标（index 与 defIndex 都取该下标，FunctionTable 按两者建索引）。 */
    private fun meta(program: Program, index: Int): FuncMeta = FuncMeta(
        index = index,
        key = program.functions[index].name.uppercase(),
        name = program.functions[index].name,
        isMethod = false,
        argIntCount = program.functions[index].arity,
        defIndex = index,
    )

    private fun runtime(
        program: Program,
        disabled: Set<String> = EngineFunctionPolicy.DEFAULT_DISABLED,
    ): ProcessRuntime = ProcessRuntime(
        program,
        VariableStore(),
        FunctionTable(program.functions.indices.map { meta(program, it) }),
        disabledFunctions = disabled,
    )

    // ------------------------------------------------------------------
    // 静态 CALL（非尾跳）
    // ------------------------------------------------------------------

    @Test
    fun `静态 CALL 被屏蔽时函数体不执行且调用点拿到 0`() {
        val program = buildProgram("BADAPPLE")
        val rt = runtime(program)
        assertEquals(0L, rt.execute(), "屏蔽调用 = 空体 RETURN 0")
        assertEquals("AFTER\n", rt.output.toString(), "被屏蔽函数体一句都不该执行，调用方继续往下走")
        assertEquals(listOf("BADAPPLE"), rt.stubbedFunctionNames)
    }

    @Test
    fun `关掉屏蔽后函数体照常执行`() {
        val program = buildProgram("BADAPPLE")
        val rt = runtime(program, disabled = emptySet())
        rt.execute()
        assertEquals("PLAY\nAFTER\n", rt.output.toString(), "emptySet = 全部放行，行为与改造前逐字相同")
        assertTrue(rt.stubbedFunctionNames.isEmpty())
    }

    @Test
    fun `名字匹配大小写不敏感`() {
        val program = buildProgram("BADAPPLE")
        val rt = runtime(program, disabled = setOf("badapple"))
        rt.execute()
        assertEquals("AFTER\n", rt.output.toString(), "语料里叫 BADAPPLE、宿主写 badapple，也得拦住")
        assertEquals(listOf("BADAPPLE"), rt.stubbedFunctionNames, "报出的是 Program.functions 里的原名")
    }

    @Test
    fun `未命中任何函数时屏蔽表为空`() {
        val program = buildProgram("SOMETHINGELSE")
        val rt = runtime(program)
        rt.execute()
        assertEquals("PLAY\nAFTER\n", rt.output.toString(), "屏蔽表没命中就不许改行为")
        assertTrue(rt.stubbedFunctionNames.isEmpty())
    }

    // ------------------------------------------------------------------
    // 静态 CALL（尾跳位 bit30）
    // ------------------------------------------------------------------

    @Test
    fun `尾调到被屏蔽函数等价于本帧立刻返回`() {
        // depth == 0 的尾跳：被屏蔽 ⇒ 整个 run 直接以 0 结束，AFTER 不该执行。
        val program = buildProgram("BADAPPLE", callFlags = JUMP_FLAG)
        val rt = runtime(program)
        assertEquals(0L, rt.execute())
        assertEquals("", rt.output.toString(), "尾调被屏蔽函数 = 本帧返回，后续语句一律不执行")
    }

    @Test
    fun `尾跳未被屏蔽时照常换帧执行`() {
        val program = buildProgram("BADAPPLE", callFlags = JUMP_FLAG)
        val rt = runtime(program, disabled = emptySet())
        rt.execute()
        assertEquals("PLAY\n", rt.output.toString(), "尾跳进被调函数体，AFTER 位于尾跳之后故不执行")
    }

    // ------------------------------------------------------------------
    // 动态 CALL（CALLFORM 家族）
    // ------------------------------------------------------------------

    /**
     * 动态名调用：`_CALL_BY_NAME R1, argc|flags, NONE`，R1 = 目标名。
     * 这条路径先经 [ProcessRuntime.resolveFunctionIndex] 拿到下标，再走同一个 `callInto`，
     * 因此屏蔽判据与静态 CALL 同源（这也是"只加一张表就够"的原因）。
     */
    @Test
    fun `动态 CALLFORM 同样被屏蔽`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), b.string("BADAPPLE"), 0, 1)
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(1), 0, SlotAddr.NONE, 2)
        b.emit(Opcode._PRINT_LITERAL, b.string("AFTER"), 1, 0, 3)
        b.emit(Opcode._HALT, 0, 0, 0, 4)
        b.setFunctionSlots(main, 2)
        val targetPc = b.here()
        val idx = b.declareFunction("BADAPPLE", targetPc, 0, 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("PLAY"), 1, 0, 5)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 6)
        b.setFunctionSlots(idx, 1)
        val program = b.build(main)

        val rt = runtime(program)
        rt.execute()
        assertEquals("AFTER\n", rt.output.toString(), "动态名解析命中后照样短路")
    }

    // ------------------------------------------------------------------
    // 根级 API（不走 callInto）
    // ------------------------------------------------------------------

    @Test
    fun `根级 callFunction 调用被屏蔽函数返回 0 且不执行函数体`() {
        val program = buildProgram("BADAPPLE")
        val rt = runtime(program)
        assertEquals(0L, rt.callFunction("BADAPPLE"), "根级入口把目标函数当第 0 帧，必须同样短路")
        assertEquals("", rt.output.toString())
    }

    @Test
    fun `根级 callFunction 在关掉屏蔽后正常执行`() {
        val program = buildProgram("BADAPPLE")
        val rt = runtime(program, disabled = emptySet())
        rt.callFunction("BADAPPLE")
        assertEquals("PLAY\n", rt.output.toString())
    }
}
