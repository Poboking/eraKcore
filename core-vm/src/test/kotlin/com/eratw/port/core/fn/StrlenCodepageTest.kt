package com.eratw.port.core.fn

import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.sem.instr
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * STRLEN-CODEPAGE 的**消费侧**闸：表达式层与语句层都必须问同一份配置码页。
 *
 * 收口前 `core/fn/PureMethods.kt` 自带一份写死 GBK(936) 的实现、`core/expr/FormStringTerm.kt`
 * 写死 Shift_JIS(932)；本波收成 `core/expr` 的 `LangCodec` 一份，码页由
 * `BuiltinHost.configOverrides` 的 `DEFAULT ANSI ENCODING` 决定
 * （`Config.cs:412-416` → `LangManager.setEncode`；`ConfigData.cs:103` 出厂默认 JAPANESE）。
 *
 * 判据码位 `U+2014 EM DASH`：windows-31J 编不出（替换 1 字节）、GBK 是 2 字节
 * （逐码位实测表在 FormStringTerm.kt 的 LangCodec KDoc），故它能把两条路分开。
 *
 * 注意码页按 VM 缓存（`RuntimeAux` 槽）；每个期望值用**独立 store**，否则第二次会读到首次缓存。
 */
class StrlenCodepageTest {

    private fun host(overrides: Map<String, String>): BuiltinHost = object : BuiltinHost {
        override val configOverrides: Map<String, String> get() = overrides
    }

    private fun fresh(overrides: Map<String, String>): BuiltinEvalContext {
        val store = VariableStore()
        return BuiltinEvalContext(TestEvalContext(store), store, host(overrides))
    }

    private fun intOf(ctx: BuiltinEvalContext, name: String, vararg args: Value): Long =
        (ctx.callFunction(name, args.toList()) as Value.IntV).v

    private fun strOf(ctx: BuiltinEvalContext, name: String, vararg args: Value): String =
        (ctx.callFunction(name, args.toList()) as Value.StrV).v

    private val hans = mapOf("DEFAULT ANSI ENCODING" to "CHINESE_HANS")

    @Test
    fun `表达式 STRLENS 走宿主配置的码页`() {
        // 本语料配置（936）
        assertEquals(2L, intOf(fresh(hans), "STRLENS", Value.StrV("—")))
        // 未接线/日文出厂默认（932）
        assertEquals(1L, intOf(fresh(emptyMap()), "STRLENS", Value.StrV("—")))
    }

    @Test
    fun `SUBSTRING 按字节切 跟着码页走`() {
        // GBK：— 占 2 字节 ⇒ 取前 2 字节正好是一个字符
        assertEquals(
            "—",
            strOf(fresh(hans), "SUBSTRING", Value.StrV("—abc"), Value.IntV(0), Value.IntV(2)),
        )
        // 932：— 编不出、按替换字符算 1 字节 ⇒ 取前 2 字节是 —a
        assertEquals(
            "—a",
            strOf(fresh(emptyMap()), "SUBSTRING", Value.StrV("—abc"), Value.IntV(0), Value.IntV(2)),
        )
    }

    @Test
    fun `STRLENSU 与码页无关 始终按 UTF-16 长度`() {
        assertEquals(1L, intOf(fresh(hans), "STRLENSU", Value.StrV("—")))
        assertEquals(1L, intOf(fresh(emptyMap()), "STRLENSU", Value.StrV("—")))
    }

    @Test
    fun `语句层 STRLEN 从 ctx eval 取同一份码页`() {
        fun run(overrides: Map<String, String>): Long {
            val store = VariableStore()
            StatementExecutor().execute(
                instr(FunctionCode.STRLEN, ExpressionArgument(StrLiteral("—"))),
                ExecContext(variables = store, eval = BuiltinEvalContext(TestEvalContext(store), store, host(overrides))),
            )
            return store.readInt("RESULT", longArrayOf(0))
        }
        assertEquals(2L, run(hans))
        assertEquals(1L, run(emptyMap()))
    }
}
