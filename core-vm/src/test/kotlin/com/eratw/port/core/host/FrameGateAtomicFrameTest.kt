package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 帧发布闸「内容随 revision 原子发布」（FRAMEGATE-ATOMIC）的回归钉子。
 *
 * 真机病灶（2026-10-31 玩家报告「点料理选项后游戏界面无尽上下跳」，桌面 uipreview 复现）：
 * 语料的实时搜索环（`COMF413 料理を作る.ERB` SEARCH_TYPE 分支：`AWAIT 1` → 回环头
 * `CLEARLINE` 整屏 → 重画，全程不等输入）里，帧闸只延后 [LineBufferConsoleSink.revision]
 * 的自增、不冻结行表本身 —— 消费侧（`ConsoleFeed.pump`）从看到 revision 前进到取快照
 * 之间有整个轮询周期（真机 ≤16ms），而 VM 在 `AWAIT 1` 睡 1ms 就回来摘行重画，取到的
 * 是循环体中间态（菜单被清掉 / 画到一半）⇒ 屏幕在完整菜单与残缺态之间来回翻 = 「上下跳」。
 * 原版没有这个窗口：`Await` 第一步 `RefreshStrings(force_Paint: true)` 在脚本线程内
 * 同步画完才继续（EmueraConsole.cs:840-856），快照点恒等于发布点。
 *
 * 本类钉住修复后的口径：冻结段批量发布的帧，行表在发布时刻抓取
 * （[LineBufferConsoleSink.frameSnapshot]），发布之后 VM 继续写也不漏进这一帧。
 */
class FrameGateAtomicFrameTest {

    private fun texts(s: LineBufferConsoleSink): List<String> = s.frameSnapshot().map { s.textOf(it) }

    /** 发布之后冻结段的清屏+重画不得漏进已发布帧（本刀主判据）。 */
    @Test
    fun `frozen publish captures lines and later frozen writes stay out`() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        s.printLine("菜单头")
        s.printLine("菜单一")
        s.holdFrame()
        s.printLine("B 冻结段")
        s.publishFrame()
        val rev = s.revision

        // 模拟搜索环下一圈：回环头 CLEARLINE 整屏 + 逐行重画（全在冻结段内）。
        s.holdFrame()
        s.deleteLine(3)
        s.printLine("菜单头")
        val frame = texts(s)
        assertEquals(rev, s.revision, "冻结段变更不 bump revision（既有纪律）")
        assertEquals(listOf("菜单头", "菜单一", "B 冻结段"), frame, "已发布帧必须停在发布时刻，不读循环体中间态")
    }

    /** 闸开直通自增（流式打印）回落活表：旧语义逐字不变。 */
    @Test
    fun `gate-open bumps fall back to live lines`() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        s.printLine("A")
        s.holdFrame()
        s.printLine("B")
        s.publishFrame()
        // publishFrame 后闸开：新打印直通发布，快照必须带上它（否则输入回显会丢）。
        s.printLine("C 直通")
        assertEquals(listOf("A", "B", "C 直通"), texts(s), "直通自增作废发布快照，读活表")
    }

    /** REDRAW 1 解冻发布的帧同样原子：解冻后再冻结写的新内容不漏进。 */
    @Test
    fun `redraw1 publish captures frame atomically`() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        s.setRedraw(0)
        s.printLine("冻结攒帧")
        s.setRedraw(1)
        s.setRedraw(0)
        s.printLine("下一圈中间态")
        assertEquals(listOf("冻结攒帧"), texts(s))
    }

    /** 帧闸逃生阀（frameGateEnabled=false）：frameSnapshot 恒等于活表，行为与修前一致。 */
    @Test
    fun `frameGate disabled keeps snapshot live`() {
        val s = LineBufferConsoleSink(clientWidth = 40, frameGateEnabled = false)
        s.printLine("A")
        s.holdFrame()
        s.printLine("B")
        assertEquals(listOf("A", "B"), texts(s), "逃生阀开 ⇒ 永不冻结，直通口径不变")
    }

    /** 从未发布过冻结帧时（首帧/纯直通会话）frameSnapshot 与 snapshot 逐字相同。 */
    @Test
    fun `before any frozen publish frameSnapshot equals snapshot`() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        s.printLine("开场打印")
        assertEquals(s.snapshot().map { s.textOf(it) }, texts(s))
    }
}
