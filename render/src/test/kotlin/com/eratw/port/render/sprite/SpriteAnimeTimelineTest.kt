package com.eratw.port.render.sprite

import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.SpritePool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SpriteAnimeTimeline] / [SpriteAnimeClock] 单测（GFX-07）。
 *
 * 判据逐条对 `reference/emuera-cs/MinorShift.Emuera.UI.Game.Image/SpriteAnime.cs`：
 * - 帧号 = `(now - StartTime) 毫秒 % totaltime` 后逐帧累减、`num <= 0` 命中（:108-117）；
 * - 首帧取 `FrameList[0]` 并把 `StartTime` 置为当下（:96-102）；
 * - `totaltime <= 0`（没有帧）返回 null，不画（:92-95）；
 * - delay 入表时夹到 ≥1、`totaltime` 加的是夹取后的值（:72-78）；
 * - `SETANIMETIMER` → `setRedrawTimer`：`<=0` 关、`<10` 抬到 10（EmueraConsole.cs:967-980）。
 *
 * 「闭区间」那组是最容易写歪的：C# 用 `num <= 0` 判命中，elapsed 恰好等于首帧 delay 时
 * 仍归第 0 帧（100ms 不是第 1 帧的起点），照抄不修。
 */
class SpriteAnimeTimelineTest {

    private val sheet = GraphicsBuffer(1).also { it.create(64, 16) }

    private fun frame(delayMs: Int, srcX: Int = 0) =
        SpritePool.Frame(sheet, srcX, 0, 16, 16, 0, 0, delayMs)

    /** 合成 sprite：3 帧、每帧 100ms（totaltime = 300，SpriteAnime.cs:78）。 */
    private fun threeFrames() = listOf(frame(100, 0), frame(100, 16), frame(100, 32))

    @Test
    fun `三帧各100ms 在 0 99 100 250ms 的帧号`() {
        val delays = SpriteAnimeTimeline.delaysOf(threeFrames())
        assertEquals(300L, SpriteAnimeTimeline.totalMs(delays), "totaltime = Σdelay（SpriteAnime.cs:78）")
        assertEquals(0, SpriteAnimeTimeline.frameAt(0, delays), "t=0 是首帧（:96-102）")
        assertEquals(0, SpriteAnimeTimeline.frameAt(99, delays))
        // :112 的 `num <= 0`：elapsed == 首帧 delay 时仍归第 0 帧，不是第 1 帧。
        assertEquals(0, SpriteAnimeTimeline.frameAt(100, delays), "闭区间：100ms 仍是第 0 帧")
        assertEquals(1, SpriteAnimeTimeline.frameAt(101, delays), "过了闭区间才进第 1 帧")
        assertEquals(2, SpriteAnimeTimeline.frameAt(250, delays), "250-100-100 = 50 > 0，再减 100 → 第 2 帧")
    }

    @Test
    fun `闭区间边界与环绕`() {
        val delays = SpriteAnimeTimeline.delaysOf(threeFrames())
        // 第 0 帧 = [0,100]；第 1 帧 = (100,200]；第 2 帧 = (200,300]（300 ≡ 0）
        assertEquals(0, SpriteAnimeTimeline.frameAt(100L, delays))
        assertEquals(1, SpriteAnimeTimeline.frameAt(200, delays), "200-100 = 100 > 0，再减 100 = 0 → 第 1 帧")
        assertEquals(2, SpriteAnimeTimeline.frameAt(201, delays))
        assertEquals(2, SpriteAnimeTimeline.frameAt(299, delays))
        assertEquals(0, SpriteAnimeTimeline.frameAt(300, delays), "300 % 300 = 0，回到首帧")
        assertEquals(0, SpriteAnimeTimeline.frameAt(399, delays))
        assertEquals(0, SpriteAnimeTimeline.frameAt(400, delays), "400 % 300 = 100，闭区间仍归首帧")
        assertEquals(1, SpriteAnimeTimeline.frameAt(401, delays))
        // 大 elapsed 只看模，与 elapsed % totaltime 同解（:108 的 %）。
        for (x in longArrayOf(1_234_567L, 987_654_321L, 86_400_000L)) {
            assertEquals(
                SpriteAnimeTimeline.frameAt(x % 300L, delays),
                SpriteAnimeTimeline.frameAt(x, delays),
                "elapsed=$x 应等于 elapsed%300 的帧",
            )
        }
    }

    @Test
    fun `不等长 delay 的累减顺序`() {
        val delays = SpriteAnimeTimeline.delaysOf(listOf(frame(50), frame(150), frame(100)))
        assertEquals(300L, SpriteAnimeTimeline.totalMs(delays))
        assertEquals(0, SpriteAnimeTimeline.frameAt(50, delays))
        assertEquals(1, SpriteAnimeTimeline.frameAt(51, delays))
        assertEquals(1, SpriteAnimeTimeline.frameAt(200, delays), "50+150 = 200 是第 1 帧的右闭端")
        assertEquals(2, SpriteAnimeTimeline.frameAt(201, delays))
        assertEquals(0, SpriteAnimeTimeline.frameAt(300, delays))
    }

    @Test
    fun `没有帧就不画`() {
        assertEquals(SpriteAnimeTimeline.NO_FRAME, SpriteAnimeTimeline.frameAt(0, IntArray(0)), "totaltime <= 0 → null（:92-95）")
        assertEquals(0L, SpriteAnimeTimeline.totalMs(IntArray(0)))
        assertEquals(0, SpriteAnimeTimeline.delaysOf(emptyList()).size)
    }

    @Test
    fun `单帧 sprite 恒第 0 帧`() {
        val delays = SpriteAnimeTimeline.delaysOf(listOf(frame(100)))
        for (t in longArrayOf(0L, 1L, 99L, 100L, 101L, 999L, 1_000_000L)) {
            assertEquals(0, SpriteAnimeTimeline.frameAt(t, delays), "单帧在 t=$t 仍只有第 0 帧")
        }
    }

    @Test
    fun `delay 非正按 1ms 夹取`() {
        // AddFrame 的 `if (delay <= 0) delay = 1`（:72-76）；语句层虽已拒 delay<=0，这里给直构路径兜底。
        val delays = SpriteAnimeTimeline.delaysOf(listOf(frame(0), frame(-5)))
        assertEquals(2L, SpriteAnimeTimeline.totalMs(delays), "totaltime 加的是夹取后的值（:78）")
        // 整数毫秒 + `num <= 0` 闭区间的直接后果：每帧 1ms 时 rest ∈ {0,1} 都 ≤ 首帧 delay，
        // 第 1 帧永远选不到。这是 C# 的读数，不是本实现的取舍（:108-117）。
        for (t in 0L..10L) {
            assertEquals(0, SpriteAnimeTimeline.frameAt(t, delays), "1ms 帧在 t=$t 仍命中首帧")
        }
        assertEquals(2L, SpriteAnimeTimeline.totalMs(SpriteAnimeTimeline.delaysOf(listOf(frame(-1), frame(-1)))))
    }

    @Test
    fun `时钟首次取帧即起点 之后按挂钟推进`() {
        val delays = SpriteAnimeTimeline.delaysOf(threeFrames())
        val clock = SpriteAnimeClock()
        assertFalse(clock.started, "未取帧前没有起点（lastFrame == -1，:96）")
        assertEquals(0, clock.frameAt(5_000, delays), "第一次取帧：起点=5000，返回首帧（:96-102）")
        assertTrue(clock.started)
        assertEquals(0, clock.frameAt(5_099, delays))
        assertEquals(0, clock.frameAt(5_100, delays), "5000+100，闭区间仍是首帧")
        assertEquals(1, clock.frameAt(5_101, delays))
        assertEquals(2, clock.frameAt(5_250, delays))
        assertEquals(0, clock.frameAt(5_300, delays), "满一圈回到首帧")
        // 同一时刻重复取帧必同帧（纯函数 ⇒ 原版 lastFrameTime 同 tick 短路的等价物，:103-106）。
        assertEquals(1, clock.frameAt(5_401, delays))
        assertEquals(1, clock.frameAt(5_401, delays))
    }

    @Test
    fun `时钟空帧表不置起点 reset 后从头播`() {
        val delays = SpriteAnimeTimeline.delaysOf(threeFrames())
        val clock = SpriteAnimeClock()
        assertEquals(SpriteAnimeTimeline.NO_FRAME, clock.frameAt(1_000, IntArray(0)))
        assertFalse(clock.started, "空帧表在 lastFrame==-1 之前就返回了（:92-95）")
        assertEquals(0, clock.frameAt(2_000, delays))
        assertEquals(2, clock.frameAt(2_250, delays))
        clock.reset()
        assertFalse(clock.started, "ResetTime 把 lastFrame 打回 -1（:83-88）")
        assertEquals(0, clock.frameAt(9_000, delays), "重开后重新从首帧起算")
        assertEquals(1, clock.frameAt(9_101, delays))
    }

    @Test
    fun `重绘节奏 0 关闭 小于10 抬到10`() {
        assertEquals(0, SpriteAnimeTimeline.repaintIntervalMs(0), "tickcount <= 0 关定时器（EmueraConsole.cs:969-973）")
        assertEquals(0, SpriteAnimeTimeline.repaintIntervalMs(-1))
        assertEquals(0, SpriteAnimeTimeline.repaintIntervalMs(Int.MIN_VALUE))
        assertEquals(10, SpriteAnimeTimeline.repaintIntervalMs(1), "tickcount < 10 抬到 10（:974-977）")
        assertEquals(10, SpriteAnimeTimeline.repaintIntervalMs(9))
        assertEquals(10, SpriteAnimeTimeline.repaintIntervalMs(10))
        assertEquals(16, SpriteAnimeTimeline.repaintIntervalMs(16))
        assertEquals(32767, SpriteAnimeTimeline.repaintIntervalMs(32767), "语句层上限（FunctionMethodCreator.cs:7929-7933）")
    }
}
