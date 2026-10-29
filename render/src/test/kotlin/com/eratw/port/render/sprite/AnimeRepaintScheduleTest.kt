package com.eratw.port.render.sprite

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [planAnimeRepaint] 单测：宿主「排下一拍」的判定（GFX-07 宿主侧，2026-10-29 真机修复）。
 *
 * 判据是原版 `setRedrawTimer`（EmueraConsole.cs:967-980）的**改值即刻生效**：
 * `:978-979` 当场赋 `timer.Interval` 与 `Enabled = true`，不等旧回调到期。
 * 真机症状（动态立绘/`@PRINT_ANIME` 只有静态图）的成因就是「已排就不排」把 40 ms
 * 挡在 32627 ms 的旧回调后面，所以第二条用例是本缺陷的钉子。
 */
class AnimeRepaintScheduleTest {

    @Test
    fun `未排时任何正间隔都要排`() {
        assertEquals(AnimeRepaintPlan.Rearm, planAnimeRepaint(40, pending = false, scheduledMs = 0))
    }

    @Test
    fun `已排 32627 后采样到 40 必须撤旧重排`() {
        // EraTW：USERCOM/EVENTCOMEND 压 32627，展示动态图时改 40（藍/る～こと的動的立絵、PRINT_ANIME）。
        assertEquals(AnimeRepaintPlan.Rearm, planAnimeRepaint(40, pending = true, scheduledMs = 32627))
    }

    @Test
    fun `间隔没变就保持倒计时`() {
        // onDraw 每帧都问一次；这里若判 Rearm，倒计时会被逐帧重置，40 ms 的回调永远不到点。
        assertEquals(AnimeRepaintPlan.Keep, planAnimeRepaint(40, pending = true, scheduledMs = 40))
        assertEquals(AnimeRepaintPlan.Keep, planAnimeRepaint(32627, pending = true, scheduledMs = 32627))
    }

    @Test
    fun `关表撤掉在路上的回调`() {
        assertEquals(AnimeRepaintPlan.Cancel, planAnimeRepaint(0, pending = true, scheduledMs = 40))
    }

    @Test
    fun `没排且关表时无事可做`() {
        assertEquals(AnimeRepaintPlan.Keep, planAnimeRepaint(0, pending = false, scheduledMs = 0))
        // scheduledMs 是上一拍的残留，不影响「没排 + 关表」的结论。
        assertEquals(AnimeRepaintPlan.Keep, planAnimeRepaint(0, pending = false, scheduledMs = 40))
    }

    @Test
    fun `关表判定不吃 scheduledMs 的残留`() {
        // 关表优先：pending 为真时「在路上的那一拍」必须撤掉，哪怕 scheduledMs 恰好也是 0
        // （宿主不会用 0 排表，这条只为把「关表不看 scheduledMs」钉在函数契约上）。
        assertEquals(AnimeRepaintPlan.Cancel, planAnimeRepaint(0, pending = true, scheduledMs = 0))
    }

    @Test
    fun `十毫秒下限的抬升口径在 repaintIntervalMs 里，判定只看差值`() {
        val raised = SpriteAnimeTimeline.repaintIntervalMs(5)
        assertEquals(10, raised, "EmueraConsole.cs:974-977：<10 抬到 10")
        assertEquals(AnimeRepaintPlan.Rearm, planAnimeRepaint(raised, pending = true, scheduledMs = 32627))
        assertEquals(AnimeRepaintPlan.Keep, planAnimeRepaint(raised, pending = true, scheduledMs = raised))
    }
}
