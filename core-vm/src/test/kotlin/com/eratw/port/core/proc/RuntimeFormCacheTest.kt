package com.eratw.port.core.proc

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 运行期 FORM / RETURNFORM 解析缓存的定向单测。
 *
 * 两条不变量：
 * 1. **只缓存解析结果**：同一 FORM 文本第二次求值必须读到被引用变量的当前值
 *    （证明缓存里没有冻结求值结果）；
 * 2. **缓存有界**：命中时不再解析（[ProcessRuntime.runtimeFormParseCount] 不涨），
 *    条目超过 256 条按 LRU 淘汰、超过 512 字符的输入不缓存。
 */
class RuntimeFormCacheTest {

    /** 只有 main 一条 `_HALT` 的程序：本组用例只走运行期的 FORM 解析/求值通道。 */
    private fun haltProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        b.setFunctionSlots(main, 1)
        return b.build(0)
    }

    private fun runtime(store: VariableStore = VariableStore()): ProcessRuntime =
        ProcessRuntime(haltProgram(), store, FunctionTable(emptyList()), VmConfig())

    // ------------------------------------------------------------------
    // 求值不缓存
    // ------------------------------------------------------------------

    @Test
    fun `同一 FORM 第二次求值读到变量新值`() {
        val store = VariableStore()
        val rt = runtime(store)
        val form = rt.execContext.runtimeForm!!

        store.writeInt("DAY", longArrayOf(0L), 3L)
        assertEquals("3", form("{DAY}"))

        store.writeInt("DAY", longArrayOf(0L), 7L)
        assertEquals("7", form("{DAY}"), "缓存的是解析结果（AST），不是求值结果")
        assertEquals(1, rt.runtimeFormParseCount, "第二次必须命中缓存：只解析一次")
    }

    @Test
    fun `RETURNFORM 整数列表第二次求值读到变量新值`() {
        val store = VariableStore()
        val rt = runtime(store)
        val parse = rt.execContext.runtimeIntList!!

        store.writeInt("DAY", longArrayOf(0L), 5L)
        assertEquals(listOf(5L, 2L), parse("DAY, 2"))

        store.writeInt("DAY", longArrayOf(0L), 9L)
        assertEquals(listOf(9L, 2L), parse("DAY, 2"), "表达式列表被缓存、求值每次重做")
        assertEquals(1, rt.runtimeIntListParseCount, "第二次必须命中缓存：只解析一次")
    }

    // ------------------------------------------------------------------
    // 有界（LRU + 长度闸）
    // ------------------------------------------------------------------

    @Test
    fun `FORM 缓存满 256 条后按 LRU 淘汰最早条目`() {
        val rt = runtime()
        val form = rt.execContext.runtimeForm!!

        repeat(300) { i -> assertEquals("t$i", form("t$i")) }
        val parsed = rt.runtimeFormParseCount
        assertEquals(300, parsed, "300 条互不相同的文本各解析一次")

        assertEquals("t0", form("t0"))
        assertEquals(parsed + 1, rt.runtimeFormParseCount, "最早条目应已被 LRU 淘汰（重新解析）")
        assertEquals("t299", form("t299"))
        assertEquals(parsed + 1, rt.runtimeFormParseCount, "近期条目应仍在缓存（命中不解析）")
    }

    @Test
    fun `超过 512 字符的 FORM 文本不进缓存`() {
        val rt = runtime()
        val form = rt.execContext.runtimeForm!!
        val longText = "a".repeat(600)

        assertEquals(longText, form(longText))
        assertEquals(longText, form(longText))
        assertEquals(2, rt.runtimeFormParseCount, "长文本每次都重新解析（不缓存）")
    }
}
