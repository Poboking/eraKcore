package com.eratw.port.render.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * [MacroKeys] 的按键分派判据：F1-F12 **只预填、绝不提交**。
 *
 * ## 期望值出处（全部 `MinorShift.Emuera.Forms/MainWindow.cs`）
 * - 提前返回的门槛：`console.IsWaitingPrimitive` ⇒ `ProcessCmdKey` 直接 `return false`（:385-388），
 *   属性本体 `EmueraConsole.cs:383-392`。
 * - 开关：`Config.UseKeyMacro` 关掉 ⇒ `break`（:488-491），默认开（`ConfigData.cs:53` 的 `t: true`）。
 * - 功能键区间 :496（112..123）与 `fkeyNum = num - 112`（:498）。
 * - 修饰键是**整集合相等**而非 HasFlag：:493（Shift）、:494（Control）、:495（无修饰）。
 *   ⇒ Alt+F1、Ctrl+Shift+F1 三个旗标都不成立，整条 `default` 走完 `break`（:530），什么都不做。
 * - Shift+Fn 存宏 + 空框守卫：:499-506（:501 的 `IsNullOrEmpty` 决定不覆盖旧宏）。
 * - 无修饰预填：:507-512，只有 `Text =`（:509）与 `SelectionStart =`（:510）两句，
 *   **没有** `PressEnterKey` 调用 —— 本文件与 `InputSessionTest` 都钉这一条。
 * - Ctrl+数字换组：:514-528（主键盘 :517-520、小键盘 :521-524），走 :530 的 `break` ⇒ **不吞键**。
 * - 右键菜单那一路：:1565-1576，守卫与按键那一路不同 ——
 *   要求 `!console.IsInProcess`（:1567）且宏非空（:1570）才赋文本；
 *   菜单项可用态另由 `GetMacro(j, macroGroup).Length > 0` 决定（:1492）。
 *
 * 板上口径：`docs/golden-runbook.md:106`「不会自动回车提交」、`:125`「回车仍需手按」。
 * 基准 `pc-golden-root.txt` 无宏/文本框相关行，故本路不引用基准行号。
 */
class MacroKeysTest {

    private fun macro(group: Int = 0, fkey: Int = 0, value: String) =
        KeyMacroTable(KeyMacroTable.NameScheme.SIMPLIFIED_CHINESE).apply {
            set(fkey, group, value)
        }

    @Test
    fun `功能键区间换算为零基序号`() {
        assertEquals(112, MacroKeys.VK_F1, "MainWindow.cs:496 区间下界")
        assertEquals(123, MacroKeys.VK_F12, "同处上界")
        assertEquals(0, MacroKeys.groupOf(MacroKeys.VK_DIGIT_0), "主键盘零（:517-520 减 48）")
        assertEquals(9, MacroKeys.groupOf(MacroKeys.VK_DIGIT_0 + 9))
        assertEquals(0, MacroKeys.groupOf(MacroKeys.VK_NUMPAD_0), "小键盘零（:521-524 减 96）")
        assertEquals(9, MacroKeys.groupOf(MacroKeys.VK_NUMPAD_0 + 9))
        assertNull(MacroKeys.groupOf(MacroKeys.VK_F1), "非数字键不换组")
        assertNull(MacroKeys.groupOf(58), "主键盘区间之外")
        assertNull(MacroKeys.groupOf(106), "小键盘区间之外")
    }

    @Test
    fun `无修饰功能键只预填宏文本`() {
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_F1 + 2,
            modifier = MacroKeys.Modifier.NONE,
            boxText = "框里原有内容",
            macroAtSlot = "123",
            group = 0,
        )
        assertIs<MacroKeys.Decision.Prefill>(d)
        assertEquals("123", d.text, ":509 用宏覆盖框内，不看原内容")
    }

    @Test
    fun `空宏同样预填于是清框`() {
        // :507-512 没有空值守卫 ⇒ 宏为空串时赋空串（与右键菜单那一路相反）
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_F1 + 4, // F5
            modifier = MacroKeys.Modifier.NONE,
            boxText = "旧内容",
            macroAtSlot = "",
            group = 0,
        )
        assertIs<MacroKeys.Decision.Prefill>(d)
        assertEquals("", d.text)
    }

    @Test
    fun `Shift加功能键记录框内文本`() {
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_F1 + 11,
            modifier = MacroKeys.Modifier.SHIFT,
            boxText = "要存进宏的内容",
            macroAtSlot = "旧宏",
            group = 4,
        )
        assertIs<MacroKeys.Decision.Record>(d)
        assertEquals(11, d.fkey, "F12 ⇒ 零基 11（:498）")
        assertEquals(4, d.group)
        assertEquals("要存进宏的内容", d.value)
        assertEquals(true, d.write, "框非空才写（:501-504）")
    }

    @Test
    fun `Shift加功能键在空框时不覆盖旧宏`() {
        val table = macro(fkey = 3, value = "旧宏")
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_F1 + 3,
            modifier = MacroKeys.Modifier.SHIFT,
            boxText = "",
            macroAtSlot = table.get(3, 0),
            group = 0,
        )
        assertIs<MacroKeys.Decision.Record>(d)
        assertEquals(false, d.write, ":501 的 IsNullOrEmpty 守卫：框空 ⇒ 不动表，但仍吞键（:505）")
        assertEquals("旧宏", table.get(3, 0))
    }

    @Test
    fun `组合修饰键三个旗标都不成立`() {
        // :493-495 用整集合相等判定 ⇒ Alt+F1 与 Ctrl+Shift+F1 都不匹配任何一支
        for (modifier in listOf(MacroKeys.Modifier.OTHER, MacroKeys.Modifier.CONTROL)) {
            val d = MacroKeys.dispatch(
                keyCode = MacroKeys.VK_F1,
                modifier = modifier,
                boxText = "x",
                macroAtSlot = "宏",
                group = 0,
            )
            assertIs<MacroKeys.Decision.Ignored>(d, "$modifier + F1 应整条不响应（连吞键都不吞）")
        }
    }

    @Test
    fun `控制加数字换宏组且不吞键`() {
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_DIGIT_0 + 3,
            modifier = MacroKeys.Modifier.CONTROL,
            boxText = "x",
            macroAtSlot = "",
            group = 0,
        )
        assertIs<MacroKeys.Decision.SwitchGroup>(d)
        assertEquals(3, d.group, ":519 减 48")
        assertEquals(false, d.suppressKey, ":530 走 break ⇒ 交回基类 :650，键继续往控件走")
    }

    @Test
    fun `控制加功能键不换组也不预填`() {
        // C# 的结构是「先判功能键区间，else if 判 Ctrl」⇒ Ctrl+F1 落在区间支里，
        // 既不是 Shift 也不是无修饰，整条什么都不做（:496-513 后直接 :530）
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_F1,
            modifier = MacroKeys.Modifier.CONTROL,
            boxText = "x",
            macroAtSlot = "宏",
            group = 0,
        )
        assertIs<MacroKeys.Decision.Ignored>(d)
    }

    @Test
    fun `等待原始键时宏一律不响应`() {
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_F1,
            modifier = MacroKeys.Modifier.NONE,
            boxText = "x",
            macroAtSlot = "宏",
            group = 0,
            waitingPrimitive = true,
        )
        assertIs<MacroKeys.Decision.Ignored>(d, ":385-388 在任何宏判定之前就返回")
    }

    @Test
    fun `开关关闭时宏不响应`() {
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_F1,
            modifier = MacroKeys.Modifier.NONE,
            boxText = "x",
            macroAtSlot = "宏",
            group = 0,
            useKeyMacro = false,
        )
        assertIs<MacroKeys.Decision.Ignored>(d, ":488-491")
    }

    @Test
    fun `菜单那一路空宏不响应`() {
        assertIs<MacroKeys.Decision.Ignored>(MacroKeys.prefillFromMenu(macro = "", inProcess = false))
        assertIs<MacroKeys.Decision.Ignored>(
            MacroKeys.prefillFromMenu(macro = "宏", inProcess = true),
            ":1567 要求 !console.IsInProcess",
        )
        assertIs<MacroKeys.Decision.Ignored>(
            MacroKeys.prefillFromMenu(macro = "宏", inProcess = false, useKeyMacro = false),
            ":1567 同样要求 Config.UseKeyMacro",
        )
        val ok = MacroKeys.prefillFromMenu(macro = "宏", inProcess = false)
        assertIs<MacroKeys.Decision.Prefill>(ok)
        assertEquals("宏", ok.text, ":1572-1573")
    }

    @Test
    fun `表与按键两路共用同一零基口径`() {
        val table = macro(fkey = 0, group = 2, value = "组二的宏")
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_F1,
            modifier = MacroKeys.Modifier.NONE,
            boxText = "",
            macroAtSlot = table.get(fkey = 0, group = 2),
            group = 2,
        )
        assertIs<MacroKeys.Decision.Prefill>(d)
        assertEquals("组二的宏", d.text, "GetMacro(fkeyNum, macroGroup)（:509 与 KeyMacro.cs:150-153）")
    }
}
