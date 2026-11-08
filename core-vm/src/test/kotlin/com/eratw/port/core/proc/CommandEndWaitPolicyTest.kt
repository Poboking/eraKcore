package com.eratw.port.core.proc

import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 指令链收尾那次按键等待（`Process.cs:2450-2453` 的 `setWait()`）在**生产宿主**上的适用范围。
 *
 * 背景：原版口径是「凡走 `SOURCE_CHECK` 的指令都等一次」（`Process.cs:2417`），移植侧照抄后
 * 手机上表现为「每条指令回菜单前都要多戳一下」。移动（训练码 400）每走一步就 `RETURN 1`
 * 回训练链（`魔改内容/qol/qol_MAP.ERB:59-66`），于是那一下落在每次移动之后 —— 地图还在屏上，
 * 玩家下一次点目的地被这次等待吃掉，观感是「点了没反应」（2026-11-02 玩家反馈）。
 *
 * 现在的口径（[ConsoleBeginHost.commandEndWaitComCodes]）：
 * 只对**被语料拒掉**的清掃（`CSV/Train.csv:199` 的 410；`COMF410 掃除.ERB:18-26` 只打一行提示
 * 就 `RETURN -1`）等一次，其余一律不等 —— 既保住「提示行不被整帧刷掉」的原始动机
 * （BOARD 2026-11-01），又不给高频操作加点击。
 */
class CommandEndWaitPolicyTest {

    private fun host(codes: Set<Int>? = setOf(BeginFlow.COM_CLEANING)) = ConsoleBeginHost(
        input = QueueInputSink(),
        console = LineBufferConsoleSink(clientWidth = 40),
        commandEndWaitComCodes = codes,
    )

    /** 清掃被拒（`RETURN -1`）：等一次 —— 那行「就算不扫除可以吧」要被钉在屏上。 */
    @Test
    fun `清扫被拒时收尾等待`() {
        assertTrue(host().shouldWaitAfterCommand(BeginFlow.COM_CLEANING, -1L))
    }

    /** 清掃成功（`RETURN 1`）：不等 —— 成功那一路输出本来就长，不需要额外点击。 */
    @Test
    fun `清扫成功时不等`() {
        assertFalse(host().shouldWaitAfterCommand(BeginFlow.COM_CLEANING, 1L))
    }

    /** 移动（400）无论成败都不等：`QOL_COM400` 走一步就 `RETURN 1`、退出地图 `RETURN -1`。 */
    @Test
    fun `移动无论成败都不等`() {
        val h = host()
        assertFalse(h.shouldWaitAfterCommand(400, 1L), "走了一步就回训练链，不能多要一次点击")
        assertFalse(h.shouldWaitAfterCommand(400, -1L), "退出地图同样不再多要一次点击")
    }

    /** 其余被拒指令也不等（本次收窄只认清掃；要放宽就换 `commandEndWaitComCodes`）。 */
    @Test
    fun `其他指令被拒时不等`() {
        assertFalse(host().shouldWaitAfterCommand(82, -1L))
    }

    /** `null` = 恢复原版「每条指令都等一次」，供对照与回退。 */
    @Test
    fun `传 null 时恢复原版口径每条指令都等`() {
        val h = host(codes = null)
        assertTrue(h.shouldWaitAfterCommand(400, 1L))
        assertTrue(h.shouldWaitAfterCommand(BeginFlow.COM_CLEANING, -1L))
    }

    /** 空集 = 永不等（只想验证「完全没有收尾等待」时的形态）。 */
    @Test
    fun `传空集时永不等`() {
        assertFalse(host(codes = emptySet()).shouldWaitAfterCommand(BeginFlow.COM_CLEANING, -1L))
    }
}
