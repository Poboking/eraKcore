package com.eratw.port.core.code

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 审计二波 L10（字节码 / 编译 / 装载不变量）落在 `core/code` 的三条缺陷。
 *
 * 1. **全局槽号上界撞 `NONE` 哨兵**：`slot or ABS_BIT` 在 `slot == Int.MAX_VALUE` 时正好得到
 *    `0xFFFFFFFF`，而规约里这个值只表示「此处为 null」（`specs/erbc-format.md:26-28`，
 *    同一句话在 `:23` 被引为 `SlotAddr.NONE` 的补码往返依据）。一个**真实**的全局槽被编码成
 *    它，读出来就是「无此操作数」：[SlotAddr.isNone] 为真、[SlotAddr.format] 打印 `none`，
 *    `_RET` 的 a / `_CALL` 的 c 这类可缺省操作数的语义被静默改写。⇒ 编码侧
 *    [SlotAddr.global] 与记账侧 [CodeBuilder.noteGlobalSlot] 都只收 `0..SlotAddr.MAX_GLOBAL_SLOT`。
 * 2. **`noteGlobalSlot` 的加一溢出**：旧写法 `if (slot + 1 > globalSlots)` 在
 *    `slot == Int.MAX_VALUE` 时 `slot + 1` 回绕成负数 ⇒ 计数**静默不涨**，于是
 *    `Vm` 按偏小的 `globalSlots` 分配 `globalInts`/`globalStrs`（`Vm.kt:257-258`），
 *    运行期取指时才炸成一句与真因无关的下标错误。
 * 3. **映射来源的窗口判界按 Int 乘**：`wordCount` 是 CODE 段声明的 u32 计数
 *    （规约 §5.6 `specs/erbc-format.md:291-293`），`wordCount * 4` 在 `wordCount ≥ 2^29`
 *    时回绕成负数，判据形同虚设；构造出的对象随后还用同样会回绕的 `index * 4` 去读映射。
 *    装载侧 `MappedErbc.codeSource` 自己按 Long 算过（其 KDoc 明写 `words < 2^29`），
 *    本类的 require 是最后一道，不能比调用方更松。
 *
 * 另有一条口径回归护栏：[canonicalCallArgs] 的「无收益 ⇒ 原样返回入参表」。
 */
class CodeAudit2Test {

    // ------------------------------------------------------------------
    // 1 / 2：槽号上界
    // ------------------------------------------------------------------

    @Test
    fun `全局槽号上界排除编码后等于 NONE 哨兵的那个值`() {
        // 这一行就是缺陷本身：Int.MAX_VALUE 带 bit31 后 = 0xFFFFFFFF = NONE。
        assertEquals(SlotAddr.NONE, Int.MAX_VALUE or SlotAddr.ABS_BIT, "0x7FFFFFFF 编码后与 NONE 同值")
        assertEquals(0x7FFFFFFE, SlotAddr.MAX_GLOBAL_SLOT, "上界 = bit31 之外的 31 位再减一个哨兵")

        val top = SlotAddr.global(SlotAddr.MAX_GLOBAL_SLOT)
        assertTrue(SlotAddr.isGlobal(top), "上界内仍是全局槽")
        assertFalse(SlotAddr.isNone(top), "上界内不得读成「无此操作数」")
        assertEquals(SlotAddr.MAX_GLOBAL_SLOT, SlotAddr.index(top), "槽号往返不漂")
        assertEquals("G2147483646", SlotAddr.format(top))

        assertFailsWith<IllegalArgumentException> { SlotAddr.global(Int.MAX_VALUE) }
            .let {
                assertTrue(it.message!!.contains("NONE"), "报错要点名哨兵冲突，实际: ${it.message}")
            }
        assertFailsWith<IllegalArgumentException> { SlotAddr.global(-1) }
        assertFailsWith<IllegalArgumentException> { SlotAddr.global(Int.MIN_VALUE) }
    }

    @Test
    fun `noteGlobalSlot 与 global 同一口径且加一不回绕`() {
        val b = CodeBuilder()
        b.noteGlobalSlot(SlotAddr.MAX_GLOBAL_SLOT)
        assertEquals(Int.MAX_VALUE, b.globalSlotCount, "上界内计数 = 槽号 + 1（这一步正是不许回绕的判据）")

        // 旧实现在这里不抛、只把 globalSlots 静默留在旧值上。
        val c = CodeBuilder()
        c.noteGlobalSlot(3)
        assertFailsWith<IllegalArgumentException> { c.noteGlobalSlot(Int.MAX_VALUE) }
        assertEquals(4, c.globalSlotCount, "越界记账不得污染已记录的槽数")
    }

    // ------------------------------------------------------------------
    // 3：映射窗口判界
    // ------------------------------------------------------------------

    @Test
    fun `映射来源的窗口判界按 Long 算，撒谎的字数构造即失败`() {
        // 16 字节的窗口 vs 2^29 字（= 2^31 字节，Int 乘法回绕成 0）：
        // 旧写法 `remaining >= wordCount * 4` 在回绕后恒真，对象照建。
        val tiny = ByteBuffer.allocate(16)
        val e = assertFailsWith<IllegalArgumentException> {
            CodeSource.ofLittleEndian(tiny, 1 shl 29)
        }
        assertTrue(
            e.message!!.contains("需要 2147483648 字节"),
            "消息要给未回绕的 Long 需求数，实际: ${e.message}",
        )

        // 同一条判据对合法尺寸原样放行（4 字 = 1 条指令 = 16 字节）。
        val ok = CodeSource.ofLittleEndian(ByteBuffer.allocate(16), Instruction.WORDS)
        assertEquals(Instruction.WORDS, ok.wordCount)
        assertEquals(1, ok.pageCount)
    }

    // ------------------------------------------------------------------
    // 口径护栏：调用点实参表的构造期去重
    // ------------------------------------------------------------------

    @Test
    fun `调用点实参表无收益时不复制第二张表，有收益时按结构归一`() {
        // 无收益：每个站点的实参表形态都互不相同 ⇒ 原样返回入参表（不新建 Map）。
        val unique = linkedMapOf(
            10 to listOf(CallArg(0, 0, false, "A", true)),
            20 to listOf(CallArg(0, 0, true, null, true)),
        )
        assertSame(unique, canonicalCallArgs(unique), "全唯一 ⇒ 返回入参表本体")
        assertSame(emptyMap<Int, List<CallArg>>(), canonicalCallArgs(emptyMap()), "空表 ⇒ 直接返回入参")

        // 有收益：两站点的表结构相等 ⇒ 收敛成同一实例，pc 迭代序不变。
        val dup = linkedMapOf(
            30 to listOf(CallArg(0, 0, false, "SHARED", true)),
            10 to listOf(CallArg(0, 0, false, "SHARED", true)),
        )
        val out = canonicalCallArgs(dup)
        assertEquals(listOf(30, 10), out.keys.toList(), "迭代序 = 入参序（LinkedHashMap 保序）")
        assertSame(out[30], out[10], "等值实参表归一到同一实例")
        assertSame(out[30]!![0], dup[30]!![0], "串与结点本就唯一时不换实例")
    }
}
