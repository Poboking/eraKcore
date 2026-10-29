package com.eratw.port.render.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [InputHistory] 的左移队列与召回游标。
 *
 * ## 期望值出处（全部 `MinorShift.Emuera.Forms/MainWindow.cs`）
 * - 三件套声明：`prevInputs` 定长 100（:52）、`selectedInputs` 初值 100（:54）、
 *   `lastSelected` 初值 100（:56）。100 既是容量也是「不在召回态」的哨兵。
 * - 入历史 `updateInputs`：空串早退（:940-944，只赋 `Text = ""`，**不动** `selectedInputs`）、
 *   是否入队看 :945 的两条或、左移一格再写末尾（:947-951）、
 *   与「召回位前一条」相同则游标上收（:952-955）、否则 `lastSelected = 100`（:958）、
 *   与末条相同只回写 `lastSelected`（:963）、收尾 `Text = ""` + `selectedInputs = Length`（:965-966）。
 * - 召回 `movePrev`：`move == 0` 直接返回（:971-974）、刚从提交回来时第一次按 ↑ 不前进
 *   （:976-984）、范围外不响应（:989）、走到哨兵位即回空框（:991-995）、
 *   槽位为空时「先自增再判是否撞哨兵」（:996）、命中后赋文本 + 整行选中（:998-1001）。
 * - 方向：↑ 传 -1（:1437）、↓ 传 1（:1445），两支都受 `!console.IsInProcess` 夹（:1435、:1443）。
 *
 * 夹具用 [InputHistory] 的 `capacity` 形参开小容量，好把移位写清楚；
 * 默认容量与哨兵那条判据单独用 100 断言（见 `默认容量与哨兵同为一百`）。
 * 基准 `pc-golden-root.txt` 没有历史相关的行，故本文件全部钉 C# 行号。
 */
class InputHistoryTest {

    private fun history(capacity: Int = 4) = InputHistory(capacity)

    private fun record(h: InputHistory, text: String): HistoryEdit.SetText {
        val edit = h.record(text)
        assertIs<HistoryEdit.SetText>(edit)
        return edit
    }

    private fun recall(h: InputHistory, move: Int): HistoryEdit.SetText {
        val edit = h.recall(move)
        assertIs<HistoryEdit.SetText>(edit)
        return edit
    }

    @Test
    fun `默认容量与哨兵同为一百`() {
        val h = InputHistory()
        assertEquals(100, InputHistory.CAPACITY, "prevInputs = new string[100]（:52）")
        assertEquals(100, h.capacity)
        assertEquals(100, h.selectedInputs, "selectedInputs = 100（:54）")
        assertEquals(100, h.lastSelected, "lastSelected = 100（:56）")
    }

    @Test
    fun `提交后写回空串且不动选区`() {
        val h = history()
        val edit = record(h, "abc")
        assertEquals("", edit.text, ":965 赋空串")
        assertEquals(false, edit.selectAll, "那一行之后没有 SelectionStart/Length 两句")
        assertEquals(h.capacity, h.selectedInputs, ":966 复位游标")
    }

    @Test
    fun `左移队列最旧在前`() {
        val h = history()
        h.record("a")
        h.record("b")
        h.record("c")
        assertEquals(listOf("", "a", "b", "c"), h.snapshot(), ":947-951 逐格左移")
        assertEquals("c", h.snapshot().last(), "末尾写入本次提交")
    }

    @Test
    fun `哨兵态下同一条内容照样重复入队`() {
        // :945 的第一个或件是 selectedInputs == Length，成立时**不看**内容是否重复 ⇒ 连着提交
        // 同一条会占两格。官方行为（想「去重」只在召回态那条支里，:961-963）。
        val h = history()
        h.record("a")
        h.record("a")
        assertEquals(listOf("", "", "a", "a"), h.snapshot())
    }

    @Test
    fun `空串提交不进历史且游标留着`() {
        val h = history()
        h.record("a")
        h.recall(-1)
        assertEquals(3, h.selectedInputs, "召回态：游标停在末格")
        val edit = record(h, "")
        assertEquals("", edit.text, ":942 只赋 Text")
        assertEquals(false, edit.selectAll, "同一支不动选区")
        assertEquals(3, h.selectedInputs, ":940-944 的早退绕过了 :966 的游标复位（官方行为）")
        assertEquals(listOf("", "", "", "a"), h.snapshot(), "空串一条都没入队")
    }

    @Test
    fun `召回态提交与末条相同只回写落点`() {
        val h = history()
        h.record("a")
        h.record("b")
        // 停在末格（"b"）后再提交同一条 ⇒ :945 两支都不成立，走 :963
        assertEquals("b", recall(h, -1).text)
        val edit = record(h, "b")
        assertEquals("", edit.text)
        assertEquals(listOf("", "", "a", "b"), h.snapshot(), ":961-963 的支里不移位、不写入")
        assertEquals(3, h.lastSelected, ":963 lastSelected = selectedInputs")
        assertEquals(h.capacity, h.selectedInputs, ":966 仍把游标复位")
    }

    @Test
    fun `召回方向与整行选中`() {
        val h = history()
        h.record("a")
        h.record("b")
        val up = recall(h, -1)
        assertEquals("b", up.text, ":998-999 取 prevInputs[num]")
        assertEquals(true, up.selectAll, ":1000-1001 起点零 + 整行选中")
        assertEquals("a", recall(h, -1).text, "再按 ↑ 往更旧")
        assertEquals("b", recall(h, 1).text, "↓（+1）往新一侧退（:1445）")
    }

    @Test
    fun `退到哨兵位回到空框`() {
        val h = history()
        h.record("a")
        recall(h, -1)
        val toSentinel = recall(h, 1)
        assertEquals("", toSentinel.text, ":991-995：num 等于容量 ⇒ 赋空串")
        assertEquals(false, toSentinel.selectAll, "那一支只赋 Text")
        assertEquals(h.capacity, h.selectedInputs)
    }

    @Test
    fun `空历史按上键不响应`() {
        val h = history()
        // num = capacity - 1 = 3，槽空 ⇒ :996 的 ++num 撞回哨兵 ⇒ 整条不响应
        assertIs<HistoryEdit.None>(h.recall(-1))
        assertIs<HistoryEdit.None>(h.recall(0), "move 为零直接返回（:971-974）")
    }

    @Test
    fun `队首之下再按上键不响应`() {
        val h = history()
        h.record("a")
        h.record("b")
        h.record("c")
        h.record("d")
        // 四格全满：↑ 依次落 3、2、1、0
        assertEquals("d", recall(h, -1).text)
        assertEquals("c", recall(h, -1).text)
        assertEquals("b", recall(h, -1).text)
        assertEquals("a", recall(h, -1).text)
        assertIs<HistoryEdit.None>(h.recall(-1), "num 变负 ⇒ :989 的范围判定挡掉")
    }

    @Test
    fun `空槽被跳过后上键会卡在同一条`() {
        val h = history(capacity = 3)
        h.record("x")
        // slots = ["", "", "x"]；第一次 ↑ ⇒ num=2 命中
        assertEquals("x", recall(h, -1).text)
        assertEquals(2, h.selectedInputs)
        // 第二次 ↑ ⇒ num=1（空槽）⇒ :996 先 ++num 回到 2 ⇒ 又取到同一条，游标仍是 2
        assertEquals("x", recall(h, -1).text, "空槽被 ++num 跳过（:996）")
        assertEquals(2, h.selectedInputs, "游标被 ++num 拉回同一格 ⇒ 再按 ↑ 还是它（官方怪癖）")
        assertEquals("x", recall(h, -1).text)
    }

    @Test
    fun `提交完第一次按上键落在上次召回位`() {
        val h = history()
        h.record("a")
        h.record("b")
        h.record("c")
        recall(h, -1)
        recall(h, -1)
        assertEquals(2, h.selectedInputs, "停在 slots[2] = b")
        // 召回位的前一条（slots[1] = a）不等于提交内容 b ⇒ :945 走移位支，
        // 再按 :952 判 current == slots[selectedInputs - 1] = slots[1] = a ⇒ 不等 ⇒ :958 抹掉落点
        record(h, "x")
        assertEquals(h.capacity, h.lastSelected, ":958 与召回位前一条不同 ⇒ 落点清零")
        // 现在构造 :952-955 成立的场景：召回态里提交「前一条」的内容
        val h2 = history()
        h2.record("a")
        h2.record("b")
        h2.record("c")
        recall(h2, -1)
        recall(h2, -1)
        record(h2, "b")
        assertEquals(1, h2.lastSelected, ":954 lastSelected = --selectedInputs")
        assertEquals(4, h2.selectedInputs, ":966 复位")
        // 于是下一次 ↑ 走 :976-984：move 被抹成零，num 直接落在上次召回位
        val edit = recall(h2, -1)
        assertEquals("b", edit.text)
        assertEquals(1, h2.selectedInputs, "落点是 lastSelected 加零，而不是容量减一那格（:982）")
        assertEquals(h2.capacity, h2.lastSelected, ":983 消费掉落点")
    }

    @Test
    fun `哨兵态下按下键不响应`() {
        val h = history(capacity = 2)
        h.record("a")
        // 游标还在哨兵位 ⇒ num = capacity + 1 越界（:989），而不是回空框
        assertIs<HistoryEdit.None>(h.recall(1))
        assertEquals("a", recall(h, -1).text, "先按 ↑ 落到末格")
        assertEquals("", recall(h, 1).text, "召回态按 ↓ 走到哨兵位是回空框（:991-995），不是不响应")
    }
}
