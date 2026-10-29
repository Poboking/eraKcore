package com.eratw.port.core.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CMP-10：容器侧三处尺寸/偏移算术的**回绕面**。
 *
 * ## 缺陷形态（总账 CMP-10，侦察 §7 已改写机理）
 *
 * 1. `ErbcCursor.need`（**改前** `ErbcFormat.kt:483-487`，现 `:564`）用
 *    `pos + n > bytes.size` 判界 —— Int 加法：`pos = 20`、`n = Int.MAX_VALUE` 时
 *    `pos + n` 回绕成负数，判界**放行**，随后 `copyOfRange` 抛 `OutOfMemoryError`，
 *    装载层接不住（它不是 [ErbcDecodeException]）。
 * 2. `ErbcSink.ensure`（**改前** `ErbcFormat.kt:409-414`，现 `:480` 走 [grownCapacity]
 *    `:449`）的 `while (cap < size + n) cap *= 2`：`size + n` 越过 2^31 即回绕成负数、
 *    `cap` 从 2^30 翻到负数 ⇒ **死循环**（不是照写越界）。
 * 3. `ErbcWriter` 的 `offset += body.size`（**改前** `ErbcWriter.kt:219`，现同一位置改调
 *    [checkedSectionEndOffset] `:520`）：段表条目是 u32、装载侧按 Int 解释，越界或回绕
 *    都会让后面的段静默错位。
 *
 * 第 1 条可以端到端造出来（本文件）；第 2、3 条要 2GiB 级正文才会触发，端到端造不出来，
 * 只能直测判界函数（见下方「守卫直测」）。
 */
class ErbcGuardedArithmeticTest {

    // ------------------------------------------------------------------
    // 1. 游标判界：Int 加法回绕
    // ------------------------------------------------------------------

    /** `pos + n` 回绕后判界放行 ⇒ 漏到 `copyOfRange` 抛 IllegalArgumentException（不是解码错误）。 */
    @Test
    fun `游标判界走 Long - 超长 take 必须抛解码错误而不是漏到数组越界`() {
        val cur = ErbcCursor(ByteArray(32) { it.toByte() })
        cur.skip(20)
        val e = assertFailsWith<ErbcDecodeException>("回绕后判界必须仍然拦下") {
            cur.take(Int.MAX_VALUE, "畸形长度字段")
        }
        assertTrue(e.message!!.contains("畸形长度字段"), "报错要点名是哪个字段；实际=${e.message}")

        // 正常路径不受影响：取得出的照旧取出
        assertEquals(3, cur.take(3, "正常字段").size)
        assertEquals(23, cur.pos)
    }

    /** 负长度是另一个方向的同一类缺陷（`need(-1)` 现在也靠 `n < 0` 拦）。 */
    @Test
    fun `负长度直接拒绝`() {
        val cur = ErbcCursor(ByteArray(8))
        assertFailsWith<ErbcDecodeException> { cur.skip(-1) }
        assertFailsWith<ErbcDecodeException> { cur.take(-4, "负字段") }
    }

    // ------------------------------------------------------------------
    // 2. 段偏移累加判界（2GiB 级正文造不出来，直测判界函数）
    // ------------------------------------------------------------------

    /** `ErbcWriter` 唯一的偏移累加点：越界/回绕/负段长都在这里被拦下，并点名段。 */
    @Test
    fun `段偏移累加判界 - 越过容器上限即抛且点名段`() {
        assertEquals(58, checkedSectionEndOffset(0, 46, 12), "正常累加照旧")
        assertEquals(Int.MAX_VALUE, checkedSectionEndOffset(0, Int.MAX_VALUE - 8, 8), "恰好到上限仍合法")

        val e = assertFailsWith<ErbcEncodeException> { checkedSectionEndOffset(3, Int.MAX_VALUE - 8, 16) }
        assertTrue(e.message!!.contains("STATEMENT_POOL"), "报错要点名段；实际=${e.message}")
        assertTrue(e.message!!.contains("超过容器可寻址上限"), "报错要说清判据；实际=${e.message}")

        val neg = assertFailsWith<ErbcEncodeException> { checkedSectionEndOffset(0, 100, -1) }
        assertTrue(neg.message!!.contains("负值"), "负段长要点名；实际=${neg.message}")
        assertFailsWith<ErbcEncodeException> { checkedSectionEndOffset(0, -1, 16) }
    }

    // ------------------------------------------------------------------
    // 3. 扩容容量判界（旧形态是 `cap *= 2` 回绕成负数后的死循环）
    // ------------------------------------------------------------------

    /**
     * 判界函数直测：`16 → 40` 翻到 64；越过 JVM 单数组上限**报错**而不是继续翻倍
     * （旧形态 `cap *= 2` 从 2^30 翻到负数后条件恒真 ⇒ 死循环，且 `size + n` 也回绕）。
     */
    @Test
    fun `扩容容量判界 - 越过单数组上限报错而不是无限翻倍`() {
        assertEquals(64, grownCapacity(16, 40), "16 装不下 40 ⇒ 翻到 64")
        assertEquals(16, grownCapacity(16, 16), "刚好装下就不扩容")
        assertEquals(16, grownCapacity(16, 8))
        assertEquals(0, grownCapacity(0, 0), "零需求返回当前容量，不做多余扩容")

        val e = assertFailsWith<ErbcEncodeException> { grownCapacity(16, Int.MAX_VALUE.toLong()) }
        assertTrue(e.message!!.contains("单数组上限"), "报错要说清判据；实际=${e.message}")
        assertFailsWith<ErbcEncodeException> { grownCapacity(16, -1L) }
    }
}
