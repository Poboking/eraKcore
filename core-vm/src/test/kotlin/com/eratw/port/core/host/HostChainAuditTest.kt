package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M6-B HOST-AUDIT 的回归用例：四条主链里**有真分歧**的那几条。
 *
 * 期望值全部从 C# 参考实现推导（`EmueraConsole.cs` / `PrintStringBuffer.cs` /
 * `ConsoleDisplayLine.cs` / `InputRequest.cs`），不用本仓实现回读当期望。
 * 已被既有用例锁定、审计后判定零分歧的条目（PRINTN 合并、折行、PRINTC、DRAWLINE、
 * 临时行、样式快照、对齐快照、ResetStyle、REDRAW 折减）不在这里重复；
 * 有分歧但改不动本文件面的条目（Popup、2000 字上限、按钮代际、输入回显）只登记在回执。
 */
class HostChainAuditTest {

    // ------------------------------------------------------------------
    // 链 1：打印缓冲与 flush —— 并入行的身份（标志位取新行）
    // ------------------------------------------------------------------

    /**
     * PRINTN 的**续行**（同一次 flush 的折行尾段）被下一批并入时，并入结果的全部标志取
     * 新行（EmueraConsole.cs:2947-2967：`deleteLine(1)` 删旧行 → `line.ShiftPositionX(...)`
     * → `line.ChangeStr(旧按钮 + 新按钮)` → `displayLineList.Add(line)`；`IsLogicalLine`
     * 只在构造时赋值，ConsoleDisplayLine.cs:12、:45）。
     *
     * 直接后果（W8 刀一改钉，2026-11-01）：并入那一步走的是 `deleteLine(1)` **本体**，
     * 而 `deleteLine` 的循环按**逻辑行**计数 —— 折行尾段 `IsLogicalLine=false`
     * （`ButtonsToDisplayLines` 的 `firstLine` 只有第一排为真，PrintStringBuffer.cs:193/206），
     * 所以摘掉末排后 `num` 仍为 0，会继续摘到本逻辑行首排才停（EmueraConsole.cs:3007-3021）。
     * ⇒ 并入把整条折行一起吃掉，`line.ChangeStr(旧末排按钮 + 新行按钮)` 只留住末排内容，
     * 首排文本整体消失。`line.ShiftPositionX(...)`（:2955）的「接在旧行末尾」意图因此落空，
     * 但这就是原版的实际行为（ADR-005 复刻，不许「顺手」留下首排）。
     *
     * 旧断言（并入后 `size=2`、首排仍留）钉的是移植侧裸 `removeAt(末排)` 的漂移：
     * 它把「只删一排」当成了 `deleteLine(1)` 的语义。
     *
     * 注：本用例逐值钉 LINECOUNT 与原版一致（2026-11-02 AUD-h3-linecount 收口）——
     * 原版在「PRINTN 折行」的待决窗口里就把组内首排计一次（折行的中间排带的是构造缺省
     * `IsLineEnd=true`，ConsoleDisplayLine.cs:16/:32/:47；`Flush` 只改写末排，
     * PrintStringBuffer.cs:183；计数门是「首排 ∧ 行尾」，EmueraConsole.cs:2971-2977），
     * 并入时 `deleteLine(1)` 从末排吃到首排把那枚扣回（:3014-3021），结行后再加回 1
     * ⇒ 窗口内 1、稳态 1。旧写法把每一排都写成 `isLineEnd=false`，窗口内读成 0。
     */
    @Test
    fun wrappedPrintnMergedLineAdoptsIncomingRowIdentity() {
        val s = LineBufferConsoleSink(clientWidth = 10)

        s.print("ああああああ", lineEnd = false) // 6 全角 = 12 格 → 2 条显示行
        s.newLine()

        assertEquals(2, s.snapshot().size)
        assertTrue(s.snapshot()[0].isLogicalLine, "折行首段 = 逻辑行起点（PrintStringBuffer.cs:193,206）")
        assertFalse(s.snapshot()[1].isLogicalLine, "折行尾段不是逻辑行起点")
        assertTrue(s.snapshot()[0].isLineEnd, "折行的中间排带构造缺省 true（ConsoleDisplayLine.cs:16）")
        assertFalse(s.snapshot()[1].isLineEnd, "只有末排被 Flush 改写成 isLastLineEnd=false（PrintStringBuffer.cs:183）")
        assertEquals(1L, s.lineCount, "待决窗口内原版计 1（:2971-2977 只认组内首排的行尾标志）")

        s.print("x") // 默认 lineEnd=true
        s.newLine()

        assertEquals(1, s.snapshot().size, "deleteLine(1) 按逻辑行计数，整条折行（首排+尾排）一起出列")
        val merged = s.snapshot()[0]
        assertEquals("あx", s.textOf(merged), "只留住末排内容（:2952-2953 的旧行引用）+ 新行")
        assertTrue(merged.isLogicalLine, "并入后保留的是新行对象 ⇒ isLogicalLine 取新行（:2966、:2969）")
        assertTrue(merged.isLineEnd)
        assertEquals(1L, s.lineCount)

        s.deleteLine(1)

        assertEquals(0, s.snapshot().size, "并入后的那一条就是唯一的逻辑行，删掉即空")
        assertEquals(0L, s.lineCount)
    }

    // ------------------------------------------------------------------
    // 链 3：按钮表 —— Config.ButtonWrap 的「整颗按钮不拆」
    // ------------------------------------------------------------------

    /**
     * `Config.ButtonWrap = true`（语料 emuera.config:38 `BUTTON WRAPPING:YES`）时，
     * 当前行放不下的**可选按钮**不拆分，整颗移到下一行：PrintStringBuffer.cs:217
     * （`PointX + Width <= DrawableWidth` 放不下）→ :222 的条件在 ButtonWrap 为真、
     * 当前行非空、且是可选按钮（`IsButton`）时为假 ⇒ :242 结束本行、:244-249 重排到新行。
     */
    @Test
    fun buttonWrapMovesWholeButtonToNextRow() {
        val s = LineBufferConsoleSink(clientWidth = 10, buttonWrap = true)

        s.print("aaaaaaaaa") // 9 格
        s.printButton("abc", 7L) // 9 + 3 > 10 ⇒ 整颗移到下一行
        s.newLine()

        assertEquals(2, s.snapshot().size)
        assertEquals(listOf("aaaaaaaaa", "abc"), s.loggedLines())
        assertTrue(s.snapshot()[0].buttons.isEmpty(), "第一行不该有按钮残留（不拆）")
        assertEquals(listOf(LineBufferConsoleSink.Button(0, 3, 7L)), s.snapshot()[1].buttons)
        assertEquals(listOf(LineBufferConsoleSink.PlacedButton(1, 0, 3, 7L)), s.buttons())
        assertEquals(1L, s.lineCount, "折行仍是一个逻辑行")
    }

    /**
     * 整颗移到新行后仍放不下（按钮比一整行还宽）⇒ 回到按字符拆分的路径
     * （PrintStringBuffer.cs:222 的 `list2.Count == 0` 分支让 :224 的 getDivideIndex 生效）。
     */
    @Test
    fun buttonWrapStillSplitsButtonWiderThanOneRow() {
        val s = LineBufferConsoleSink(clientWidth = 4, buttonWrap = true)

        s.printButton("abcdef", 1L)
        s.newLine()

        assertEquals(listOf("abcd", "ef"), s.loggedLines())
        assertEquals(
            listOf(
                LineBufferConsoleSink.PlacedButton(0, 0, 4, 1L),
                LineBufferConsoleSink.PlacedButton(1, 0, 2, 1L),
            ),
            s.buttons(),
        )
    }

    /**
     * 缺省值 = `ConfigData.cs:83` 的出厂 `false`（拆），且这个开关只影响可选按钮 ——
     * 普通文本两种取值都按字符折行（原版 :222 的第三项条件是 `!IsButton`）。
     */
    @Test
    fun buttonWrapDefaultsOffAndOnlyAffectsSelectableButtons() {
        assertFalse(LineBufferConsoleSink().buttonWrap, "ConfigData.cs:83 出厂 false")

        // 缺省（拆）：与 LineBufferConsoleSinkTest.buttonIsSplitAcrossWrappedLines 同一形态，
        // 这里只钉「缺省值确实是拆」，避免默认值被误改成语料的 YES。
        val split = LineBufferConsoleSink(clientWidth = 10, buttonWrap = false)
        split.print("aaaaaaaaa")
        split.printButton("abc", 7L)
        split.newLine()
        assertEquals(listOf(LineBufferConsoleSink.Button(9, 10, 7L)), split.snapshot()[0].buttons)
        assertEquals(listOf(LineBufferConsoleSink.Button(0, 2, 7L)), split.snapshot()[1].buttons)

        // 文本不受开关影响。
        val plain = LineBufferConsoleSink(clientWidth = 10, buttonWrap = true)
        plain.print("abcdefghijk")
        plain.newLine()
        assertEquals(listOf("abcdefghij", "k"), plain.loggedLines())
    }

    // ------------------------------------------------------------------
    // 链 4：输入等待 —— 宿主接口层的字段契约
    // ------------------------------------------------------------------

    /**
     * [InputRequest] 的缺省值逐字段对齐原版 `InputRequest`（InputRequest.cs:5-27）：
     * `Timelimit` 缺省 **-1**（:23）、其余 bool 缺省 false、默认值字段 0 / 空串。
     *
     * 三种 C# InputType（Void / AnyValue / PrimitiveMouseKey）与系统输入在本层没有对应项，
     * 差异与归属见 [InputRequest] 的 KDoc 与 M6-B HOST-AUDIT 回执。
     */
    @Test
    fun inputRequestDefaultsMatchCSharpInputRequest() {
        val int = InputRequest(type = InputRequest.InputType.INT)

        assertFalse(int.oneInput, "OneInput 缺省 false（InputRequest.cs:11）")
        assertFalse(int.hasDef, "HasDefValue 缺省 false（:17）")
        assertEquals(0L, int.defInt, "DefIntValue 缺省 0（:19）")
        assertEquals("", int.defStr, "DefStrValue 缺省 null，本层归一成空串（:21）")
        assertEquals(-1L, int.timeLimit, "Timelimit 缺省 -1 = 不限时（:23）")
        assertFalse(int.displayTime, "DisplayTime 缺省 false（:25）")
        assertEquals("", int.timeUpMessage, "TimeUpMes 缺省 null（由语句层回落 Config.TimeupLabel，:27）")
        assertFalse(int.mouseInput, "MouseInput 缺省 false（:9）")

        // 无宿主时等待立即返回全零结果，且不报告消息跳过（不伪造一次「跳过提交」）。
        val out = NoopInputSink.waitInput(int)
        assertEquals(0L, out.intValue)
        assertEquals("", out.strValue)
        assertFalse(out.isTimeout, "ISTIMEOUT 的缺省来源（InputOutcome）")
        assertFalse(NoopInputSink.consumeMesSkip())
    }
}
