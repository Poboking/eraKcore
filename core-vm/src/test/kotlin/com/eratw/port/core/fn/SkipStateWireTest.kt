package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Value
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.sem.instr
import com.eratw.port.core.sem.intArg
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 产线同构的最小装配：一个 VM = 一份 [VariableStore]（因此一口 aux 跳过状态槽）
 * + 同一份 store 上的求值语境 + 一份语句层 [ExecContext]。
 */
private class WireEnv {
    val store = VariableStore()

    /** 产线同构：`BuiltinContext` 与本 VM 的语句层共用同一份 [VariableStore]。 */
    val eval: BuiltinEvalContext = BuiltinEvalContext(
        base = TestEvalContext(store),
        store = store,
        host = BuiltinHost.DEFAULT,
    )
    val ctx = ExecContext(variables = store, eval = eval)
    private val executor = StatementExecutor()

    /** 走真实语句分派（SKIPDISP / SKIPLOG 的语义在 StatementExecutor，不在本测试）。 */
    fun run(stmt: Statement) {
        executor.execute(stmt, ctx)
    }

    /** 走真实内建注册表与桥（同 BuiltinEvalContext.callFunction 的产线路径）。 */
    fun i(name: String): Long = (eval.callFunction(name, emptyList()) as Value.IntV).v
}

/**
 * HOST-SKIP 接线闸（缺陷总账 HOST-01）：`ISSKIP` / `MESSKIP` / `MOUSESKIP` 的数据源
 * 从「宿主未接线的 `BuiltinHost.skipState`」改成**每 VM 的 aux 跳过状态槽**之后的端到端读数。
 *
 * 被钉的链路（sem 写 → aux 槽 → fn 读，全程同一份状态，没有第二份拷贝）：
 * - `SKIPDISP` 语句写 `ExecContext.skipPrint`（StatementExecutor 的 SKIPDISP 分支，
 *   spec: Process.cs:1587-1592，直写 :1589）；`ISSKIP` 读回（FunctionMethodCreator.cs:3613）。
 * - `SKIPLOG` 语句写 `ExecContext.mesSkip`（spec: Process.cs:1830-1833，直写 :1832）；
 *   `MESSKIP` / `MOUSESKIP` 读回（后者与 MESSKIP 同一实现，FunctionMethodCreator.cs:3644）。
 * - 输入消费路径直接写 `ExecContext.mesSkip`（EmueraConsole.cs:1373 的 `MesSkip = keySkip`、
 *   :1456 的 `MesSkip = false`）：本测试用属性写入复现该路径，断言 fn 立刻看到新值。
 *
 * 装置是 [WireEnv]：一份 [VariableStore]（= 一个 VM）、同一份 store 上的 [BuiltinEvalContext]
 * （对应 ProcessRuntime 的 `evalContext`）、一份 [ExecContext]。宿主用 [BuiltinHost.DEFAULT]
 * （`skipState == null`）—— 正是生产 `ConsoleBuiltinHost` 的现状，因此本文件同时证明
 * 「宿主不接线也能读出正确值」。
 *
 * 反向闸：没有 [ExecContext]（无语句层、无跳过状态槽）的纯 fn 装置仍然抛 CodeEE，
 * 不允许静默答 0（SKIPDISP 进行中 ISSKIP 必须为 1）。
 *
 * **语料依赖：0 个用例**（全部读数由语句分派与属性写入给出，不读 ERB 与存档）。
 */
class SkipStateWireTest {

    @Test
    fun `新建 VM 初始态 ISSKIP MESSKIP MOUSESKIP 都是 0 且不抛未接线`() {
        val env = WireEnv()
        // 出厂态：Process.skipPrint / Console.MesSkip 的字段初值都是 false（Process.cs:160、
        // EmueraConsole.cs:148）⇒ 三个函数都读 0，而不是 HOST-01 之前的 CodeEE。
        assertNull(env.eval.host.skipState, "装置必须复现生产宿主的现状：skipState == null")
        assertEquals(0L, env.i("ISSKIP"))
        assertEquals(0L, env.i("MESSKIP"))
        assertEquals(0L, env.i("MOUSESKIP"))
        // sem 侧视图与 fn 侧读数同源
        assertEquals(false, env.ctx.skipPrint)
        assertEquals(false, env.ctx.mesSkip)
    }

    @Test
    fun `SKIPDISP 置真后 ISSKIP 为 1 复位后为 0`() {
        val env = WireEnv()
        assertEquals(0L, env.i("ISSKIP"))
        // SKIPDISP 1 —— Process.cs:1587-1592（:1589 直写 skipPrint；:1591 同时写 RESULT=1）
        env.run(instr(FunctionCode.SKIPDISP, intArg(1L)))
        assertEquals(true, env.ctx.skipPrint, "语句必须写进 sem 的 skipPrint")
        assertEquals(1L, env.i("ISSKIP"), "FunctionMethodCreator.cs:3613 读的是同一次写入")
        // SKIPDISP 0 —— 同一语句的复位分支
        env.run(instr(FunctionCode.SKIPDISP, intArg(0L)))
        assertEquals(0L, env.i("ISSKIP"))
        assertEquals(false, env.ctx.skipPrint)
    }

    @Test
    fun `SKIPLOG 置真后 MESSKIP 与 MOUSESKIP 为 1 且不影响 ISSKIP`() {
        val env = WireEnv()
        // SKIPLOG 1 —— Process.cs:1830-1833（:1832 直写 Console.MesSkip）
        env.run(instr(FunctionCode.SKIPLOG, intArg(1L)))
        assertEquals(true, env.ctx.mesSkip)
        assertEquals(1L, env.i("MESSKIP"), "FunctionMethodCreator.cs:3644 同一条读数")
        assertEquals(1L, env.i("MOUSESKIP"), "MOUSESKIP 与 MESSKIP 同一实现（:9065/:9066）")
        // 两个标志互相独立：只置 MesSkip 时 SkipPrint 仍是出厂态
        assertEquals(0L, env.i("ISSKIP"))
        env.run(instr(FunctionCode.SKIPLOG, intArg(0L)))
        assertEquals(0L, env.i("MESSKIP"))
        assertEquals(0L, env.i("MOUSESKIP"))
    }

    @Test
    fun `输入消费路径写 mesSkip 后 MESSKIP 立即可见`() {
        val env = WireEnv()
        // EmueraConsole.cs:1373 `MesSkip = keySkip`（跳过中的提交）→ sem 属性写 → 槽
        env.ctx.mesSkip = true
        assertEquals(1L, env.i("MESSKIP"))
        assertEquals(1L, env.i("MOUSESKIP"))
        // EmueraConsole.cs:1456 `MesSkip = false`（输入循环结束跳过）
        env.ctx.mesSkip = false
        assertEquals(0L, env.i("MESSKIP"))
        // 同一条写入路径的另一半：skipPrint 也要镜像（NOSKIP/ENDNOSKIP 走的就是属性写）
        env.ctx.skipPrint = true
        assertEquals(1L, env.i("ISSKIP"))
    }

    @Test
    fun `两个 VM 的跳过状态互不串味`() {
        val a = WireEnv()
        val b = WireEnv()
        a.run(instr(FunctionCode.SKIPDISP, intArg(1L)))
        a.run(instr(FunctionCode.SKIPLOG, intArg(1L)))
        assertEquals(1L, a.i("ISSKIP"))
        assertEquals(1L, a.i("MESSKIP"))
        // 另一个 VM（另一份 VariableStore ⇒ 另一口 aux 槽）保持出厂态
        assertEquals(0L, b.i("ISSKIP"))
        assertEquals(0L, b.i("MESSKIP"))
        assertEquals(0L, b.i("MOUSESKIP"))
        // 各自槽是不同实例（每 VM 一份，不是进程级单例）
        val slotA = a.store.aux.peek<SkipStateSlot>(SKIP_STATE_AUX_SLOT)
        val slotB = b.store.aux.peek<SkipStateSlot>(SKIP_STATE_AUX_SLOT)
        assertTrue(slotA !== slotB, "两个 VM 不能共用同一口跳过状态槽")
    }

    @Test
    fun `没有语句层的纯 fn 装置仍抛 CodeEE 而不是静默 0`() {
        // 反向闸：槽由 ExecContext 构造时建立；只有 VariableStore 的纯 fn 装置没有槽
        // ⇒ 保持「未接线报错」，不把缺状态伪装成「没在跳过」。
        val store = VariableStore()
        val eval = BuiltinEvalContext(base = TestEvalContext(store), store = store)
        assertNull(store.aux.peek<SkipStateSlot>(SKIP_STATE_AUX_SLOT))
        val e = assertFailsWith<CodeEE> { eval.callFunction("ISSKIP", emptyList()) }
        assertTrue(e.message!!.contains("ISSKIP"), "异常文案应含函数名：${e.message}")
        assertTrue(e.message!!.contains("skipState == null"), "异常文案应含判据：${e.message}")
        assertFailsWith<CodeEE> { eval.callFunction("MESSKIP", emptyList()) }
        assertFailsWith<CodeEE> { eval.callFunction("MOUSESKIP", emptyList()) }
    }
}
