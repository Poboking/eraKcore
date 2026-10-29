package com.eratw.port.core.proc

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprToken
import com.eratw.port.core.expr.IdentifierResolver
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.colon
import com.eratw.port.core.expr.i
import com.eratw.port.core.expr.id
import com.eratw.port.core.expr.op
import com.eratw.port.core.expr.parseOrThrow
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * R10 尾跳实参绑定回归测试 —— 锁住 `ProcessRuntime.bindArgs` 的"根帧槽为零 ⇒ 绑零等价"分支。
 *
 * 场景：`execute()` 经 launcher 的合成根帧进入 `main`（scopeDepth==2、pendingRoot==null），
 * `main` 用 `_CALL` 的 b bit30 尾跳到 `callee`。`Vm.jumpInto` **不加 depth**，且只先
 * `onFunctionExit(main)` 再 `onFunctionEnter(callee)` —— 后者触发时 scopeDepth 仍为 2、
 * pendingRoot 已被消费。若 `bindArgs` 以"scopeDepth==2"早退，实参不会落到 ARG/ARGS。
 *
 * 字节码用 [CodeBuilder] 直接发射（Assembler 无法表达 b bit30）；表达式用 core.expr
 * 解析器 + [VariableStore] 作为变量来源（与 ProcessRuntimeTest 同款）。
 */
class JumpBindArgsTest {

    private companion object {
        /** `_CALL` 的 b bit30：尾跳（JUMP 家族）标志，见 Vm.kt 的 JUMP_FLAG。 */
        const val JUMP_FLAG = 0x40000000

        /** `_PRINT_INT` 的 b 换行标志（Vm.kt FLAG_NEWLINE）。 */
        const val FLAG_NEWLINE = 1
    }

    // ------------------------------------------------------------------
    // 构建工具
    // ------------------------------------------------------------------

    private class StoreResolver(private val store: VariableStore) : IdentifierResolver {
        override fun resolveVariable(name: String, subKey: String?): VarInfo? =
            store.resolveVarInfo(name, null)

        override fun resolveFunction(name: String): OperandType? = null
    }

    private fun expr(store: VariableStore, tokens: List<ExprToken>): Expr =
        parseOrThrow(tokens, StoreResolver(store))

    /** `ARG:0 + ARG:1`：只有 ARG 绑定成功时才是 5+6=11；未绑定则为 0。 */
    private fun eArgSum(store: VariableStore): Expr =
        expr(store, listOf(id("ARG"), colon(), i(0), op("+"), id("ARG"), colon(), i(1)))

    /**
     * 程序骨架（main=下标 0，callee=下标 1）：
     *
     * ```
     * main:   _SET_INT R0, 5
     *         _SET_INT R1, 6
     *         _CALL callee, 2[|JUMP_FLAG], NONE|R2
     *         [_PRINT_INT R2, 1]        ; 仅普通调用路径
     *         _HALT
     * callee: _EVAL_INT <ARG:0+ARG:1>, R2
     *         _RET R2
     * ```
     *
     * 尾跳时 `_HALT` 不可达（jumpInto 换帧后 `_RET` 直达合成根帧）。
     * main 不进 [FunctionTable]：`execute()` → `runEntry(entry, null)`，pendingRoot 为 null，
     * 根帧的 `bindArgs` 早退与它无关。
     */
    private fun tailProgram(store: VariableStore, tail: Boolean, calleeArity: Int = 2): Program {
        val b = CodeBuilder()
        val mainPc = b.here()
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(5))
        b.emit(Opcode._SET_INT, SlotAddr.local(1), b.constant(6))
        val callPc = b.emit(
            Opcode._CALL,
            0, // a = 函数下标，callee 声明后回填
            if (tail) 2 or JUMP_FLAG else 2,
            if (tail) SlotAddr.NONE else SlotAddr.local(2),
        )
        if (!tail) {
            b.emit(Opcode._PRINT_INT, SlotAddr.local(2), FLAG_NEWLINE)
        }
        b.emit(Opcode._HALT)

        val calleePc = b.here()
        b.emit(Opcode._EVAL_INT, b.expr(eArgSum(store)), SlotAddr.local(2))
        b.emit(Opcode._RET, SlotAddr.local(2))

        val mainIndex = b.declareFunction("main", mainPc, arity = 0, slotCount = 3)
        val calleeIndex = b.declareFunction("callee", calleePc, arity = calleeArity, slotCount = 3)
        b.patch(callPc, 1, calleeIndex)
        assertEquals(0, mainIndex)
        assertEquals(1, calleeIndex)
        return b.build(entryFunction = mainIndex)
    }

    private fun runtime(program: Program, store: VariableStore, metas: List<FuncMeta>): ProcessRuntime =
        ProcessRuntime(program, store, FunctionTable(metas))

    /** callee 的元数据：index 必须与 program.functions 下标一致。 */
    private fun calleeMeta(argInt: Int = 2): FuncMeta =
        FuncMeta(index = 1, key = "CALLEE", argIntCount = argInt)

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    fun `尾跳替换根帧后实参仍绑定到 ARG`() {
        val store = VariableStore()
        val rt = runtime(tailProgram(store, tail = true), store, listOf(calleeMeta()))
        // 尾跳：callee 的 ARG:0/ARG:1 必须由 main 帧的 R0/R1（5、6）绑定。
        // bindArgs 若早退，ARG 全 0 → 返回 0，本断言必红。
        assertEquals(11L, rt.execute())
        assertEquals(0, store.callDepth())
    }

    @Test
    fun `普通调用路径的实参绑定不受影响`() {
        val store = VariableStore()
        val rt = runtime(tailProgram(store, tail = false), store, listOf(calleeMeta()))
        // 对照：callInto 建新帧（scopeDepth>2），实参从调用方帧槽绑定，返回值经 R2 回到 main。
        assertEquals(11L, rt.execute())
        assertEquals("11\n", rt.output.toString())
        assertEquals(0, store.callDepth())
    }

    /**
     * 尾跳**同样**受 `ConvertArg` 的实参过多硬判约束 —— `JUMP`/`CALL` 在原版是同一个
     * `CALL_Instruction.DoInstruction`（`isJump` 只是字段），两者都先过 `ConvertArg`
     * （spec: FunctionIdentifier.cs:3592-3596 → CalledFunction.cs:136-140）。
     *
     * 旧实现 `jumpInto` 取 `argc=min(2,1)=1` **静默裁剪**，本例断言 5（ARG:1 保持 0）。
     * 2026-10-26 起 `onFunctionEnter` 在建帧前先判 `argc > arity` ⇒ 抛 CodeEE，
     * 文案 = zhs:901 `{0}函数: 参数过多`（`{0}` 不带 `@`）。
     */
    @Test
    fun `尾跳时超出被调 arity 的实参抛参数过多而非静默裁剪`() {
        val store = VariableStore()
        val rt = runtime(
            tailProgram(store, tail = true, calleeArity = 1),
            store,
            listOf(calleeMeta(argInt = 1)),
        )
        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals("CALLEE函数: 参数过多", e.message)
        assertEquals(0, store.callDepth(), "抛在建帧前 ⇒ 帧栈不泄漏")
    }
}
