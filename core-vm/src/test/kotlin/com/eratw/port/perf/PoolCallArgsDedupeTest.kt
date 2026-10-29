package com.eratw.port.perf

import com.eratw.port.core.code.CallArg
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M6-MEMCODE 第二项：**调用点实参表构造期规范化**（`canonicalCallArgs`）的契约。
 *
 * 全量语料账单（`PoolDecomp` §2b）：150,195 个调用点 / 294,444 个 [CallArg] = 25.0 MiB。
 * 形态高度重复（`position`/`bucket`/`isStr`/`present` 只在 0..argc 里取值，唯一自由量是
 * `name`），故按 data class 结构归一。
 *
 * 锁死：**共享实例、值不变、顺序不变、读取面类型不变**。任何一条破了都会打到
 * `ProcessRuntime.bindCallArgs`（逐位 REF/具名形参绑定）。
 */
class PoolCallArgsDedupeTest {

    @Test
    fun `结构相等的调用点共享同一份实参表`() {
        val b = CodeBuilder()
        val site = listOf(CallArg(0, 0, isStr = false, name = "TARGET", present = true))
        b.emit(Opcode._EVAL_INT, 0, SlotAddr.local(0), 0, 1) // pc0
        b.emit(Opcode._EVAL_INT, 0, SlotAddr.local(0), 0, 2) // pc1
        b.declareCallArgs(0, site)
        b.declareCallArgs(1, site.toList()) // 另一份等值 list（不同实例）
        val p = build(b)

        val a = p.callArgsAt(0)
        val c = p.callArgsAt(1)
        assertTrue(a != null && c != null, "两个调用点的实参表都应保留")
        assertSame(a, c, "等值实参表应归一到同一实例")
        assertSame(a!![0], c!![0], "等值 CallArg 应归一到同一实例")
        // 值不变
        with(a[0]) {
            assertEquals(0, position)
            assertEquals(0, bucket)
            assertEquals(false, isStr)
            assertEquals("TARGET", name)
            assertEquals(true, present)
        }
        assertEquals(2, p.callArgs.size, "pc → 表 的键集合不变")
    }

    @Test
    fun `不同形态的实参表不合并`() {
        val b = CodeBuilder()
        b.emit(Opcode._EVAL_INT, 0, SlotAddr.local(0), 0, 1)
        b.emit(Opcode._EVAL_INT, 0, SlotAddr.local(0), 0, 2)
        b.declareCallArgs(0, listOf(CallArg(0, 0, isStr = false, name = null, present = true)))
        b.declareCallArgs(1, listOf(CallArg(0, 0, isStr = true, name = null, present = true)))
        val p = build(b)

        assertNotSame(p.callArgsAt(0), p.callArgsAt(1), "isStr 不同的实参表不许合并")
        assertEquals(false, p.callArgsAt(0)!![0].isStr)
        assertEquals(true, p.callArgsAt(1)!![0].isStr)
    }

    @Test
    fun `同内容不同实例的变量名归一`() {
        val b = CodeBuilder()
        b.emit(Opcode._EVAL_INT, 0, SlotAddr.local(0), 0, 1)
        b.emit(Opcode._EVAL_INT, 0, SlotAddr.local(0), 0, 2)
        // 同内容、不同实例的 name（String 内容相等）
        b.declareCallArgs(0, listOf(CallArg(0, 0, isStr = false, name = String(charArrayOf('A', 'R', 'G')), present = true)))
        b.declareCallArgs(1, listOf(CallArg(0, 0, isStr = false, name = String(charArrayOf('A', 'R', 'G')), present = true)))
        val p = build(b)

        assertSame(p.callArgsAt(0)!![0], p.callArgsAt(1)!![0], "name 内容相同的 CallArg 应归一")
        assertEquals("ARG", p.callArgsAt(0)!![0].name)
    }

    @Test
    fun `空表与逐位绑定标志不变`() {
        val b = CodeBuilder()
        b.emit(Opcode._EVAL_INT, 0, SlotAddr.local(0), 0, 1)
        val p = build(b)
        assertTrue(p.callArgs.isEmpty(), "没有调用点时表为空")
        assertEquals(false, p.bindsCallArgsPositionally, "空表 ⇒ 逐位绑定不成立（v1 口径）")
    }

    private fun build(b: CodeBuilder): Program {
        val f = b.declareFunction("MAIN", 0, 0)
        return b.build(f)
    }
}
