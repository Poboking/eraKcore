package com.eratw.port.core.proc

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M4-M `m-callmes`：CALL 未定义报错的**上屏整串原文**对拍。
 *
 * 原版三条 key（三条不同、不得互相"统一"）：
 *
 * | key | jp（[Lang.cs]） | zhs（[lang.emuera-zhs.xml]） | 抛点 |
 * |---|---|---|---|
 * | `Error.NotDefinedFunc` | `Lang.cs:2262` | zhs:520 = 没有找到指定的函数"@{0}" | `FunctionIdentifier.cs:3581`（CALL/CALLFORM 运行期，静态名与动态名共用）、`ErbLoader.cs:1619`（装载期）、`FunctionIdentifier.cs:1137`（const 名 CALLF 的装载期） |
 * | `Error.NotDefinedUserFunc` | `Lang.cs:2210` | zhs:507 = 没有找到行内函数"{0}" | `FunctionIdentifier.cs:1163`（CALLF/CALLFORMF 运行期） |
 * | `Error.FuncIsNotFound` | `Lang.cs:3126` | zhs:736 = 找不到函数"@{0}" | `Process.cs:2099`（BEGIN/FORCE_BEGIN 且 force=true） |
 *
 * 相位（`CALL_Instruction.DoInstruction`，`FunctionIdentifier.cs:3560-3599`）：
 * 动态名取名 `:3574` → 查表 `:3575` → **空判定 `:3577`** → `!isTry` 才抛 `:3579-3581`；
 * `isTry` 时 `:3583-3586` 只有挂了 ENDCATCH 才跳、否则 `:3587` 直接 return（= 跳过本行），
 * 且两条都不写 RESULT。⇒ 本文件的 try 用例断"不抛 + 继续执行下一条指令"。
 *
 * 移植侧两条抛出点必须同口径（本路的立身之本）：
 * - 语句层根级入口 `ProcessRuntime.resolveForCall`（静态名）；
 * - VM `_CALL_BY_NAME`（动态名，`core/code/Vm.kt`）。
 * 只改一处会在同一款游戏里留下两种上屏串，故下面的交叉用例直接把两条**抓出来比对**。
 */
class CallMesTextTest {

    private companion object {
        /** Vm.kt 私有的 CALLF_FLAG（b 操作数 bit29）按契约字面量复刻。 */
        const val CALLF_FLAG = 0x20000000

        /** Vm.kt 私有的 TRY_FLAG（b 操作数 bit31）。 */
        const val TRY_FLAG = Int.MIN_VALUE

        /** zhs 表形逐字照抄（含成对直引号与 `@` 前缀），供断言比对。 */
        const val ZHS_NOT_DEFINED_FUNC = "没有找到指定的函数\"@%s\""

        const val ZHS_NOT_DEFINED_USER_FUNC = "没有找到行内函数\"%s\""

        const val ZHS_FUNC_IS_NOT_FOUND = "找不到函数\"@%s\""

        fun notDefinedFunc(name: String) = ZHS_NOT_DEFINED_FUNC.format(name)
        fun notDefinedUserFunc(name: String) = ZHS_NOT_DEFINED_USER_FUNC.format(name)
        fun funcIsNotFound(name: String) = ZHS_FUNC_IS_NOT_FOUND.format(name)
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /**
     * main（pc0）：
     * ```
     * _SET_STR  R1, <目标名>              // R1 = 名字槽（动态名，走 VM 的 _CALL_BY_NAME）
     * _CALL_BY_NAME R1, 0|<flags>, NONE
     * _PRINT_LITERAL "AFTER", 1          // 只有"跳过"路才会执行到这里
     * _HALT
     * ```
     * [withMethod] 再追加一个用户 `#FUNCTION` 方法 MYMETHOD（供 CALLF 命中对照组）。
     * 下标按 declareFunction 顺序：main=0、MYMETHOD=1。
     */
    private fun byNameProgram(
        targetName: String,
        flags: Int,
        withMethod: Boolean = true,
    ): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.noteGlobalSlot(0)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), b.string(targetName), 0, 1)
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(1), flags, SlotAddr.NONE, 2)
        b.emit(Opcode._PRINT_LITERAL, b.string("AFTER"), 1, 0, 3)
        b.emit(Opcode._HALT, 0, 0, 0, 4)
        b.setFunctionSlots(main, 2)

        if (withMethod) {
            val methodPc = b.here()
            val method = b.declareFunction("MYMETHOD", methodPc, 0, 1)
            b.emit(Opcode._RET, 0, 0, 0, 5)
            b.setFunctionSlots(method, 1)
        }
        return b.build(0)
    }

    private fun meta(program: Program, name: String, isMethod: Boolean): FuncMeta {
        val idx = program.functions.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        assertTrue(idx >= 0, "函数 $name 不在程序中")
        return FuncMeta(index = idx, key = name.uppercase(), name = name, isMethod = isMethod, defIndex = idx)
    }

    /** 表里只有 main（无事件、无 MYMETHOD）⇒ 目标名一律 NOT_FOUND。 */
    private fun runtimeOnlyMain(program: Program): ProcessRuntime =
        ProcessRuntime(program, VariableStore(), FunctionTable(listOf(meta(program, "main", isMethod = false))))

    /** 表里有 main + MYMETHOD（方法）：CALLF 命中对照组 + 事件名对照组另加。 */
    private fun runtimeWithMethod(program: Program): ProcessRuntime =
        ProcessRuntime(
            program,
            VariableStore(),
            FunctionTable(listOf(meta(program, "main", isMethod = false), meta(program, "MYMETHOD", isMethod = true))),
        )

    // ------------------------------------------------------------------
    // 1) 非事件函数缺失（语句层根级入口 = 静态名那条）
    // ------------------------------------------------------------------

    @Test
    fun `CALL 静态名未定义抛 zhs 原文 带成对直引号与 at 前缀`() {
        val program = byNameProgram("ABSENT", 0)
        val rt = runtimeOnlyMain(program)

        val e = assertFailsWith<CodeEE> { rt.callFunction("ABSENT") }
        // 上屏**整串**：字面量逐字 = 没有找到指定的函数"@ABSENT"
        // spec: FunctionIdentifier.cs:3577-3581 抛 NotDefinedFunc；文案 zhs:520 / Lang.cs:2262。
        assertEquals("没有找到指定的函数\"@ABSENT\"", e.message)
        assertEquals(notDefinedFunc("ABSENT"), e.message, "断言自身与 zhs 模板同源")
    }

    @Test
    fun `CALLFORM 动态名未定义走 VM 抛同一条 zhs 原文`() {
        val program = byNameProgram("ABSENT", 0)
        val rt = runtimeOnlyMain(program)

        val e = assertFailsWith<CodeEE> { rt.execute() }
        // VM 路（core/code/Vm.kt 的 _CALL_BY_NAME 非 try 分支）：
        // 原版静态名与 FORM 动态名共用 :3581 这一条串（`FunctionIdentifier.cs:3566-3576`
        // 只决定名字来源，抛点在 if 之外），所以两路上屏必须一字不差。
        assertEquals("没有找到指定的函数\"@ABSENT\"", e.message)
    }

    @Test
    fun `交叉用例 静态名与动态名两条抛出点产出完全相同`() {
        val program = byNameProgram("GHOSTTARGET", 0)
        val fromStatic = assertFailsWith<CodeEE> { runtimeOnlyMain(program).callFunction("GHOSTTARGET") }
        val fromDynamic = assertFailsWith<CodeEE> { runtimeOnlyMain(program).execute() }

        assertEquals(fromStatic.message, fromDynamic.message, "只改一处会留下两种上屏串（本路要根除的形态）")
        // 旧自造串必须彻底消失（回退任一改动点即红）。
        val text = fromStatic.message ?: ""
        assertTrue(
            text != "函数 GHOSTTARGET 未定义" && text != "未定义的用户函数 GHOSTTARGET",
            "仍是移植侧自造文案：$text",
        )
    }

    // ------------------------------------------------------------------
    // 2) CALLF 家族（另一条 key：NotDefinedUserFunc，**不带 @**）
    // ------------------------------------------------------------------

    @Test
    fun `CALLF 语句层未定义抛 zhs 行内函数原文 不带 at 前缀`() {
        val program = byNameProgram("GHOST", CALLF_FLAG)
        val rt = runtimeWithMethod(program)

        val e = assertFailsWith<CodeEE> { rt.callF("GHOST") }
        // spec: FunctionIdentifier.cs:1161-1163（CALLF_Instruction.DoInstruction，
        // 动态名经 GetFunctionMethod(userDefinedOnly) 取 null 后抛 NotDefinedUserFunc）；
        // zhs:507 / Lang.cs:2210。这条与 NotDefinedFunc 是**两个 key**，@ 前缀只属于后者。
        assertEquals("没有找到行内函数\"GHOST\"", e.message)
    }

    @Test
    fun `CALLF 动态名经 VM 抛出与语句层同一条 zhs 行内函数原文`() {
        val program = byNameProgram("GHOST", CALLF_FLAG)
        val rt = runtimeWithMethod(program)

        val e = assertFailsWith<CodeEE> { rt.execute() }
        // VM 路：host.resolveMethodIndex 返回 null（ProcessRuntime.kt 的 CALLF 解析）
        // → Vm.kt 的 isCallF 分支。
        assertEquals("没有找到行内函数\"GHOST\"", e.message)
    }

    @Test
    fun `交叉用例 CALLF 两条抛出点相同且与 CALL 那条不是同一条串`() {
        val program = byNameProgram("GHOST", CALLF_FLAG)
        val callfStatic = assertFailsWith<CodeEE> { runtimeWithMethod(program).callF("GHOST") }
        val callfDynamic = assertFailsWith<CodeEE> { runtimeWithMethod(program).execute() }
        assertEquals(callfStatic.message, callfDynamic.message, "CALLF 族两处也要同一口径")

        // 族与族之间不得被"统一"：NotDefinedUserFunc 无 @、NotDefinedFunc 有 @。
        assertTrue(callfStatic.message != notDefinedFunc("GHOST"), "两族文案串味了：${callfStatic.message}")
        assertTrue(callfStatic.message == notDefinedUserFunc("GHOST"))
    }

    // ------------------------------------------------------------------
    // 3) isTry（TRYCALL 族）不该抛
    // ------------------------------------------------------------------

    @Test
    fun `TRYCALL 未定义不抛且返回 null`() {
        val program = byNameProgram("ABSENT", 0)
        val rt = runtimeOnlyMain(program)
        // spec: FunctionIdentifier.cs:3577-3579 的 if(!isTry) 是唯一抛点；
        // :3583-3587 是 isTry 路（无 ENDCATCH 时直接 return，不写 RESULT）。
        assertNull(rt.tryCall("ABSENT"), "isTry 分支不得抛（zhs:520 那条只属于 !isTry）")
    }

    @Test
    fun `TRYCALLFORM 动态名未定义静默跳过并继续执行下一条指令`() {
        val program = byNameProgram("ABSENT", TRY_FLAG)
        val rt = runtimeOnlyMain(program)
        rt.execute()
        // 抛则到不了后面的 _PRINT_LITERAL（Vm.kt 的 isTry 路只 pc++）。
        assertEquals("AFTER\n", rt.output.toString())
    }

    @Test
    fun `TRYCALLF 未定义不抛并返回假`() {
        val program = byNameProgram("GHOST", CALLF_FLAG or TRY_FLAG)
        val rt = runtimeWithMethod(program)
        assertFalse(rt.tryCallF("GHOST"), "isTry 下 CALLF 族也不得抛（同一 if 结构）")
        rt.execute()
        assertEquals("AFTER\n", rt.output.toString())
    }

    @Test
    fun `try 位只豁免未知名 事件名与方法名照旧抛`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), b.string("EVENTFIRST"), 0, 1)
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(1), TRY_FLAG, SlotAddr.NONE, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)
        b.setFunctionSlots(main, 2)
        val program = b.build(0)
        val rt = ProcessRuntime(
            program,
            VariableStore(),
            FunctionTable(
                listOf(
                    meta(program, "main", isMethod = false),
                    FuncMeta(index = 1, key = "EVENTFIRST", name = "EVENTFIRST", isEvent = true),
                ),
            ),
        )
        // spec: CalledFunction.cs:100-103 的抛发生在 isTry 判定**之前**（:3575 查表就抛），
        // 所以 try 位吞不掉事件名。文案 = zhs:679 原文（2026-10-26 改钉，旧自造串已废）。
        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals(
            "对事件函数\"@EVENTFIRST\"进行CALL调用" +
                "(此警告可以用兼容性配置「イベント関数のCALLを許可する/ALLOW CALL ON EVENT FUNCTIONS」来无视)",
            e.message,
        )
    }

    // ------------------------------------------------------------------
    // 4) BEGIN/FORCE_BEGIN 的 force 缺失 = 第三条 key（FuncIsNotFound）
    // ------------------------------------------------------------------

    @Test
    fun `begin 强制派发缺失事件名抛第三条 key 的原文`() {
        val program = byNameProgram("ABSENT", 0)
        val rt = runtimeOnlyMain(program)
        // 端到端：ProcessRuntime.begin(FIRST) → BeginFlow.stepFirst()
        // → BeginCallPort.callFunction("EVENTFIRST", force=true, isEvent=true)
        // → ProcessRuntime.callScriptFunction（表里没有 EVENTFIRST）。
        // spec: Process.cs:2090-2099（calledFunction==null 且 force 才抛）；
        // 文案 = Lang.Error.FuncIsNotFound（Lang.cs:3126 / zhs lang.emuera-zhs.xml:736）。
        val e = assertFailsWith<CodeEE> { rt.begin(BeginType.FIRST) }
        assertEquals("找不到函数\"@EVENTFIRST\"", e.message)
    }

    @Test
    fun `三条 key 各自成形 不得互相统一`() {
        val program = byNameProgram("ABSENT", 0)
        val rt = runtimeOnlyMain(program)
        // NotDefinedFunc 带 @、NotDefinedUserFunc 不带 @、FuncIsNotFound 带 @ 但措辞不同；
        // 三条分别来自 zhs:520 / zhs:507 / zhs:736。
        assertEquals(notDefinedFunc("X"), rt.notDefinedFuncMes("X"))
        assertEquals(notDefinedUserFunc("X"), rt.notDefinedUserFuncMes("X"))
        assertEquals(funcIsNotFound("X"), rt.funcIsNotFoundMes("X"))
        assertEquals(3, setOf(rt.notDefinedFuncMes("X"), rt.notDefinedUserFuncMes("X"), rt.funcIsNotFoundMes("X")).size)
    }

    // ------------------------------------------------------------------
    // 5) 名字原文透传：`@` 只由文案模板加，调用方不得预拼
    // ------------------------------------------------------------------

    @Test
    fun `函数名按原文进引号 不预先加 at 也不做大写化`() {
        val program = byNameProgram("MyGhost", 0)
        val rt = runtimeOnlyMain(program)
        // 原版 :3569/:3574 传的是 ConstStr / GetStrValue 的**原样字符串**，
        // zhs 模板只补一个 @；移植侧表键大小写不敏感但文案不得改写实参。
        assertEquals("没有找到指定的函数\"@MyGhost\"", assertFailsWith<CodeEE> { rt.callFunction("MyGhost") }.message)
    }

    // ------------------------------------------------------------------
    // 6) ConvertArg 的两条"到不了 IntoFunction"的硬判（2026-10-26 补）
    // ------------------------------------------------------------------

    /**
     * 实参多于形参 = `TooManyFuncArgs`（spec: CalledFunction.cs:136-140）：
     * `ConvertArg` 按被调 arity 建 `AExpression[]`，`array.Length < srcArgs.Count` 直接
     * 出 errMes，静态名在装载期 Warn 成 error、动态名在 `DoInstruction` 抛 CodeEE。
     *
     * 装置：ONE(ARG) 收 1 个形参，main 发 `_CALL ONE, 2, NONE`（两个实参）。
     * 旧实现 `callInto` 的 `min(argc, arity)` 会**静默裁剪**第二个实参、照常执行 ——
     * 本用例钉住"必须抛"。
     */
    @Test
    fun `实参多于形参抛 TooManyFuncArgs 原文`() {
        val b = CodeBuilder()
        // ONE 在 pc 0（下标 0），main 在其后（下标 1）：_CALL 的 a 要在发射时已知下标，
        // 故先声明被调、后声明调用方，build(1) 从 main 起跑。
        val one = b.declareFunction("ONE", 0, 1, 1)
        b.emit(Opcode._RET, SlotAddr.local(0), 0, 0, 1)
        val mainPc = b.here()
        val main = b.declareFunction("main", mainPc, 0, 4)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(1L), 0, 2)
        b.emit(Opcode._SET_INT, SlotAddr.local(1), b.constant(2L), 0, 3)
        b.emit(Opcode._CALL, one, 2, SlotAddr.NONE, 4)
        b.emit(Opcode._HALT, 0, 0, 0, 5)
        b.setFunctionSlots(one, 1)
        b.setFunctionSlots(main, 4)
        val program = b.build(main)
        val rt = ProcessRuntime(
            program,
            VariableStore(),
            FunctionTable(
                listOf(
                    FuncMeta(index = one, key = "ONE", name = "ONE", defIndex = 0),
                    FuncMeta(index = main, key = "MAIN", name = "main", defIndex = 1),
                ),
            ),
        )
        // zhs:901 = `{0}函数: 参数过多`，{0} = 标签名原文（不带 @）
        assertEquals("ONE函数: 参数过多", assertFailsWith<CodeEE> { rt.execute() }.message)
    }

    /** 实参数恰好等于形参数（含 0 参）不得误报 —— 同一装置把 b 改回 1。 */
    @Test
    fun `实参数不超过形参数时不报参数过多`() {
        val b = CodeBuilder()
        val one = b.declareFunction("ONE", 0, 1, 1)
        b.emit(Opcode._RET, SlotAddr.local(0), 0, 0, 1)
        val mainPc = b.here()
        val main = b.declareFunction("main", mainPc, 0, 4)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(7L), 0, 2)
        b.emit(Opcode._CALL, one, 1, SlotAddr.NONE, 3)
        b.emit(Opcode._HALT, 0, 0, 0, 4)
        b.setFunctionSlots(one, 1)
        b.setFunctionSlots(main, 4)
        val program = b.build(main)
        val rt = ProcessRuntime(
            program,
            VariableStore(),
            FunctionTable(
                listOf(
                    FuncMeta(index = one, key = "ONE", name = "ONE", defIndex = 0),
                    FuncMeta(index = main, key = "MAIN", name = "main", defIndex = 1),
                ),
            ),
        )
        rt.execute()
    }

    /**
     * 普通 `CALL` 落到 `#FUNCTION` 方法上 = `CallToUserFunc`（zhs:680 原文，含官方拼写
     * 错误 `FUCNTION` 与 `(文件:第N行)` 定位段）。旧实现自造串已废。
     */
    @Test
    fun `CALL 落到方法上抛 CallToUserFunc 原文带定义位置`() {
        val program = byNameProgram("MYMETHOD", 0)
        val idx = program.functions.indexOfFirst { it.name.equals("MYMETHOD", ignoreCase = true) }
        val rt = ProcessRuntime(
            program,
            VariableStore(),
            FunctionTable(
                listOf(
                    meta(program, "main", isMethod = false),
                    FuncMeta(
                        index = idx, key = "MYMETHOD", name = "MYMETHOD", isMethod = true,
                        defIndex = idx, position = "ERB/TEST.ERB:42",
                    ),
                ),
            ),
        )
        assertEquals(
            "对标记为#FUCNTION(S)的函数\"@MYMETHOD\"(ERB/TEST.ERB:第42行)进行CALL调用",
            assertFailsWith<CodeEE> { rt.execute() }.message,
        )
    }

    @Test
    fun `旧自造串在两条抛出点都已消失`() {
        val program = byNameProgram("ABSENT", 0)
        val texts = listOf(
            assertFailsWith<CodeEE> { runtimeOnlyMain(program).callFunction("ABSENT") }.message!!,
            assertFailsWith<CodeEE> { runtimeOnlyMain(program).execute() }.message!!,
        )
        for (t in texts) {
            assertTrue("函数 ABSENT 未定义" !in t, "残留旧自造串：$t")
            assertTrue("未定义的用户函数" !in t, "残留旧自造串：$t")
        }
    }
}
