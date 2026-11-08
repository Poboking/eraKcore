package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 4.981 上游「单点」内建函数的定向用例（W3 波次）。
 *
 * 覆盖：[UNCHECKED_ADD] / [UNCHECKED_MUL] 的回绕口径（C# `unchecked` = 二进制补码回绕，
 * 与 Kotlin Long 四则运算是同一模 2^64 语义）、[GETPLATFORM] 的返回值分支
 * （语料 `TITLE.ERB:86-91` 只分 `== 0` 与非 0 两支）、[GETANIMETIMER] 的读出对称性
 * （与 SETANIMETIMER 同一 SpritePool 字段）。语义证据见各实现点 KDoc。
 */
private class UpCtx(val lines: MutableList<String> = mutableListOf()) : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("UpCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {
        lines += message
    }
}

private fun upEnv(): BuiltinEvalContext {
    val store = VariableStore()
    val base = UpCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, BuiltinHost.DEFAULT)
}

private fun upCall(ctx: BuiltinEvalContext, name: String, vararg args: Long): Long =
    (ctx.callFunction(name, args.map { Value.IntV(it) }) as Value.IntV).v

class UpstreamSingleMethodsTest {
    @Test
    fun `UNCHECKED_ADD 按补码回绕不抛错`() {
        val ctx = upEnv()
        assertEquals(Long.MAX_VALUE, upCall(ctx, "UNCHECKED_ADD", Long.MAX_VALUE, 0L))
        assertEquals(Long.MIN_VALUE, upCall(ctx, "UNCHECKED_ADD", Long.MAX_VALUE, 1L))
        assertEquals(-2L, upCall(ctx, "UNCHECKED_ADD", -1L, -1L))
    }

    @Test
    fun `UNCHECKED_MUL 按补码回绕不抛错`() {
        val ctx = upEnv()
        assertEquals(-2L, upCall(ctx, "UNCHECKED_MUL", Long.MAX_VALUE, 2L))
        assertEquals(0L, upCall(ctx, "UNCHECKED_MUL", 0L, Long.MIN_VALUE))
        assertEquals(-1L, upCall(ctx, "UNCHECKED_MUL", -1L, 1L))
    }

    @Test
    fun `GETPLATFORM 非 PC 分支取 1`() {
        // 语料 `TITLE.ERB:86-91`：0 → TINPUTNF（PC 专用），非 0 → INPUT。
        assertEquals(1L, upCall(upEnv(), "GETPLATFORM"))
    }

    @Test
    fun `GETANIMETIMER 缺省 0 且与 SETANIMETIMER 对称`() {
        val ctx = upEnv()
        assertEquals(0L, upCall(ctx, "GETANIMETIMER"), "未设过 ⇒ 池字段初值 0")
        assertEquals(1L, upCall(ctx, "SETANIMETIMER", 300L))
        assertEquals(300L, upCall(ctx, "GETANIMETIMER"), "读回 SETANIMETIMER 写入的同一字段")
    }

    @Test
    fun `GETANIMETIMER 读 Interval 小于十抬到十非正返回零`() {
        // spec builtin-functions.yaml:2230：定时器启用时取 Interval；<10 的正值抬到 10、
        // 0 或负值关定时器（此后返 0）。池字段存 SETANIMETIMER 的**原参**，读出侧按同一规则折算。
        val ctx = upEnv()
        assertEquals(1L, upCall(ctx, "SETANIMETIMER", 5L))
        assertEquals(10L, upCall(ctx, "GETANIMETIMER"), "SET 5 → Interval 10")
        assertEquals(1L, upCall(ctx, "SETANIMETIMER", 10L))
        assertEquals(10L, upCall(ctx, "GETANIMETIMER"), "SET 10 → 10（不小于下限）")
        assertEquals(1L, upCall(ctx, "SETANIMETIMER", 0L))
        assertEquals(0L, upCall(ctx, "GETANIMETIMER"), "SET 0 → 定时器关 → 0")
        assertEquals(1L, upCall(ctx, "SETANIMETIMER", -5L))
        assertEquals(0L, upCall(ctx, "GETANIMETIMER"), "SET 负值 → 定时器关 → 0")
    }
}
