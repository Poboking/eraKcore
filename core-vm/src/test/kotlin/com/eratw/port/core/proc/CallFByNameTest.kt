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
import kotlin.test.assertTrue

/**
 * 契约 R11（CALLF 家族动态名解析）在 [ProcessRuntime] 桥上的端到端锁定。
 *
 * 全部用手工字节码：`_CALL_BY_NAME` 的 b 操作数 = 实参数 | bit31 try | bit29 CALLF 家族，
 * Assembler 表达不了这些位，故用 [CodeBuilder] 直接发射。
 *
 * 分叉点：
 * - VM 侧 `Opcode._CALL_BY_NAME`（Vm.kt:429-485）：bit29 时改走 `host.resolveMethodIndex`；
 * - proc 侧 [ProcessRuntime.resolveMethodIndex]（ProcessRuntime.kt:396-409）：
 *   只认用户 `#FUNCTION`/`#FUNCTIONS` 方法，非方法 label 抛 CodeEE（try 位不豁免），
 *   未知/事件名返回 null 交给 VM 的 try 位决定跳过或报错。
 *   spec: IdentifierDictionary.cs:538-586 `GetFunctionMethod(userDefinedOnly: true)`
 *   （:554-560 事件不进 non-event 表 / 非方法抛 CallfNonMethodFunc；:572-575 未知名返回 null）。
 */
class CallFByNameTest {

    private companion object {
        /** Vm.kt 的私有常量 CALLF_FLAG（b 操作数 bit29）按契约字面量复刻。 */
        const val CALLF_FLAG = 0x20000000

        /** Vm.kt 的私有常量 TRY_FLAG（b 操作数 bit31）。 */
        const val TRY_FLAG = Int.MIN_VALUE
    }

    // ------------------------------------------------------------------
    // 构建工具
    // ------------------------------------------------------------------

    /**
     * main（pc0）：
     * ```
     * _SET_STR R1, <目标名>          // R1 = 名字槽（帧内字符串临时）
     * _SET_INT R0, 5                 // R0 = 唯一整型实参
     * _CALL_BY_NAME R1, 1|<flags>, NONE
     * _PRINT_INT G0, 1               // printGlobal = true：证明方法真的写了 G0
     * _PRINT_LITERAL "AFTER", 1      // printGlobal = false：证明“跳过”后仍继续执行
     * _HALT
     * ```
     * 随后 MYMETHOD（方法，1 个整型形参）：`G0 = 77; _RET 0`。
     * [withPlain] 再追加普通 label PLAIN（非方法，`_RET 0`）作为 CALLF 的非法目标。
     *
     * 函数下标按 declareFunction 顺序：main=0、MYMETHOD=1、PLAIN=2。
     */
    private fun buildProgram(
        targetName: String,
        flags: Int,
        printGlobal: Boolean = true,
        withPlain: Boolean = false,
    ): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2) // R0 = 实参槽，R1 = 名字槽
        b.noteGlobalSlot(0)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), b.string(targetName), 0, 1)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(5L), 0, 2)
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(1), 1 or flags, SlotAddr.NONE, 3)
        if (printGlobal) {
            b.emit(Opcode._PRINT_INT, SlotAddr.global(0), 1, 0, 4)
        } else {
            b.emit(Opcode._PRINT_LITERAL, b.string("AFTER"), 1, 0, 4)
        }
        b.emit(Opcode._HALT, 0, 0, 0, 5)
        b.setFunctionSlots(main, 2)

        val methodPc = b.here()
        val method = b.declareFunction("MYMETHOD", methodPc, 1, 1)
        b.emit(Opcode._SET_INT, SlotAddr.global(0), b.constant(77L), 0, 6)
        b.emit(Opcode._RET, 0, 0, 0, 7)
        b.setFunctionSlots(method, 1)

        if (withPlain) {
            val plainPc = b.here()
            val plain = b.declareFunction("PLAIN", plainPc, 0, 1)
            b.emit(Opcode._RET, 0, 0, 0, 8)
            b.setFunctionSlots(plain, 1)
        }
        return b.build(0)
    }

    /** 函数名 → Program.functions 下标；index 与 defIndex 都取该下标（FunctionTable 按两者建索引）。 */
    private fun meta(
        program: Program,
        name: String,
        isMethod: Boolean,
        argInt: Int = 0,
    ): FuncMeta {
        val idx = program.functions.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        assertTrue(idx >= 0, "函数 $name 不在程序中")
        return FuncMeta(
            index = idx,
            key = name.uppercase(),
            name = name,
            isMethod = isMethod,
            argIntCount = argInt,
            defIndex = idx,
        )
    }

    private fun runtime(
        program: Program,
        metas: List<FuncMeta>,
        store: VariableStore = VariableStore(),
    ): ProcessRuntime = ProcessRuntime(program, store, FunctionTable(metas))

    // ------------------------------------------------------------------
    // R11：CALLF 家族（bit29）动态名解析
    // ------------------------------------------------------------------

    @Test
    fun `CALLF 位命中方法名时经 resolveMethodIndex 建帧执行`() {
        val program = buildProgram("MYMETHOD", CALLF_FLAG)
        val store = VariableStore()
        val rt = runtime(program, listOf(meta(program, "MYMETHOD", isMethod = true, argInt = 1)), store)
        rt.execute()
        // G0 初值为 0，77 只可能由被调方法写入：证明 CALLF 位走到了 resolveMethodIndex，
        // 且与 _CALL 共用同一条建帧/实参绑定路径。
        assertEquals("77\n", rt.output.toString())
        assertEquals(0, store.callDepth()) // 帧已正常拆栈
    }

    @Test
    fun `CALLF 未知名字非 try 抛没有找到行内函数原文`() {
        val program = buildProgram("GHOST", CALLF_FLAG)
        val rt = runtime(program, listOf(meta(program, "MYMETHOD", isMethod = true, argInt = 1)))
        val e = assertFailsWith<CodeEE> { rt.execute() }
        // Vm.kt 的 _CALL_BY_NAME CALLF 位分支：文案 = Lang.Error.NotDefinedUserFunc 的 zhs 表形
        // （zhs:507 / Lang.cs:2210，抛点 FunctionIdentifier.cs:1161-1163）；
        // IdentifierDictionary.cs:572-575 返回 null 后由调用方抛，这条**不带** `@`。
        assertEquals("没有找到行内函数\"GHOST\"", e.message)
    }

    @Test
    fun `CALLF 未知名字 try 位静默跳过并继续执行下一条指令`() {
        val program = buildProgram("GHOST", CALLF_FLAG or TRY_FLAG, printGlobal = false)
        val rt = runtime(program, listOf(meta(program, "MYMETHOD", isMethod = true, argInt = 1)))
        rt.execute()
        // try 位：resolveMethodIndex 返回 null → 不抛、pc++，后一条 _PRINT_LITERAL 照常执行
        assertEquals("AFTER\n", rt.output.toString())
    }

    @Test
    fun `CALLF 非方法 label 抛非方法函数且 try 位不豁免`() {
        val program = buildProgram("PLAIN", CALLF_FLAG or TRY_FLAG, withPlain = true)
        val metas = listOf(
            meta(program, "MYMETHOD", isMethod = true, argInt = 1),
            meta(program, "PLAIN", isMethod = false),
        )
        val e = assertFailsWith<CodeEE> { runtime(program, metas).execute() }
        // ProcessRuntime.kt:402-409 在 VM 的 isTry 判断之前抛（spec: IdentifierDictionary.cs:554-560），
        // 所以即使带 TRY 位也必抛，不会被当作“查不到”而跳过。
        assertTrue(e.message!!.contains("使用了CALLF系列的命令进行调用"), e.message ?: "")
    }

    // ------------------------------------------------------------------
    // 回归对照：两条解析路不得混用
    // ------------------------------------------------------------------

    @Test
    fun `无 CALLF 位的同一方法名走普通解析仍抛不能调用方法`() {
        val program = buildProgram("MYMETHOD", 0) // bit29 清零 = 普通 CALLFORM 解析
        val rt = runtime(program, listOf(meta(program, "MYMETHOD", isMethod = true, argInt = 1)))
        val e = assertFailsWith<CodeEE> { rt.execute() }
        // resolveFunctionIndex（ProcessRuntime.kt:386-394）命中方法 → methodCallError；
        // 证明普通路没有因为 bit29 的实现而“顺手放行”方法。
        assertTrue(e.message!!.contains("#FUCNTION(S)的函数\"@MYMETHOD\""), e.message ?: "")
    }
}
