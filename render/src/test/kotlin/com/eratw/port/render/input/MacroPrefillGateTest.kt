package com.eratw.port.render.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * WIRE-5 / C-MACRO-FLAG@1（新增消费者）：输入宿主消费当前等待请求的
 * `InputRequest.macroEnabled` —— **为假时不执行宏预填**（宏按钮 / 功能键预填），
 * 为真（缺省）时行为与本波前逐项一致（R4-AC1 / R4-AC2）。
 *
 * ## 期望值出处
 * - 标志面：`InputRequest.macroEnabled: Boolean = true`
 *   （`core-vm/src/main/kotlin/com/eratw/port/core/host/InputSink.kt:86`），由 sem 在
 *   `StatementExecutor.kt:3907` 以 `base.copy(macroEnabled = ctx.inputMacroEnabled)` 下发；
 *   状态源 `ExecContext.inputMacroEnabled`（`DISABLE_INPUT_MACRO` / `ENABLE_INPUT_MACRO`
 *   写在 `StatementExecutor.kt:546` / `:2962`）。core-vm 一行未改。
 * - 唯一判据：[MacroKeys.prefillAllowed] —— `dispatch`（无修饰功能键那一路）与
 *   `prefillFromMenu` 都从这里过闸；[InputSession.onMacroKey] 只把该标志贯通下去
 *   （R4-AC3 单一来源）。
 * - 宏预填语义不变：`MainWindow.cs:507-512` 整段只有 `Text =` 与 `SelectionStart =` 两句，
 *   **不自动回车**（`docs/golden-runbook.md:106`「不会自动回车提交」、`:125`「回车仍需手按」）
 *   —— 本闸只关「预填」这个动作，不改提交语义。
 *
 * ## 与基准的关系
 * 基准 `pc-golden-root.txt` 不含宏/文本框读数，故本文件每条断言都钉在上述 C# 行号与
 * `InputSink.kt:86` 上。`macroEnabled` 是 4.981 上游扩展（`reference` 全文零命中，语义按
 * `DLC/实用设置补丁/实用设置.ERB:571-573` 的
 * `DISABLE_INPUT_MACRO … INPUTS … ENABLE_INPUT_MACRO` 用法推定，见
 * `specs/builtin-functions.yaml:2286`）。
 *
 * TODO(verify)：原版开关的生效面是**提交文本的 parseInput 宏展开**（括号宏与 `\e` 跳过），
 * 移植侧无该子系统（OD2）；本波只把判据接到宿主侧唯一存在的宏机制（预填），故本文件的
 * 「宿主侧证伪」只覆盖预填这一路。
 */
class MacroPrefillGateTest {

    /**
     * 建一个会话，并按需在宏表 (group, fkey) 槽里预置宏文本。
     * 铺数据走 `loadLines`（宏文件读盘形态，`KeyMacro.cs:99-133`）而不是 `set`，
     * 这样 `changed` 保持 false，「不预填也不碰表」那条断言才不被夹具自身污染。
     */
    private fun sessionWithMacro(
        macro: String = "123",
        fkey: Int = 0,
        group: Int = 0,
    ): InputSession {
        val table = KeyMacroTable(KeyMacroTable.NameScheme.SIMPLIFIED_CHINESE)
        if (macro.isNotEmpty()) {
            table.loadLines(listOf(table.nameOf(fkey, group) + macro))
        }
        return InputSession(macroTable = table)
    }

    // ---- 判据本体 ----

    @Test
    fun `判据本体只在标志为真时放行`() {
        assertTrue(MacroKeys.prefillAllowed(true), "缺省/为真 ⇒ 允许宏预填（R4-AC2）")
        assertFalse(MacroKeys.prefillAllowed(false), "等待请求关闭宏 ⇒ 不预填（R4-AC1）")
    }

    // ---- 功能键那一路 ----

    @Test
    fun `缺省时无修饰功能键照旧预填`() {
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_F1,
            modifier = MacroKeys.Modifier.NONE,
            boxText = "框里原有内容",
            macroAtSlot = "123",
            group = 0,
        )
        assertIs<MacroKeys.Decision.Prefill>(d, "不传 macroEnabled 与本波前逐项一致（R4-AC2）")
        assertEquals("123", d.text, ":509 用宏覆盖框内，不看原内容")
    }

    @Test
    fun `标志为假时无修饰功能键不预填`() {
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_F1,
            modifier = MacroKeys.Modifier.NONE,
            boxText = "框里原有内容",
            macroAtSlot = "123",
            group = 0,
            macroEnabled = false,
        )
        assertIs<MacroKeys.Decision.Ignored>(d, "macroEnabled == false ⇒ 宏层不响应（R4-AC1）")
    }

    @Test
    fun `标志为假不影响 Shift 记录宏`() {
        // R4-AC1 只关「预填」；Shift+Fn 存宏不是预填，故照旧（本波不越界改它）。
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_F1 + 2,
            modifier = MacroKeys.Modifier.SHIFT,
            boxText = "要存的内容",
            macroAtSlot = "旧宏",
            group = 0,
            macroEnabled = false,
        )
        assertIs<MacroKeys.Decision.Record>(d)
        assertEquals("要存的内容", d.value)
        assertEquals(true, d.write)
    }

    @Test
    fun `标志为假不影响 Ctrl 加数字换组`() {
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_DIGIT_0 + 3,
            modifier = MacroKeys.Modifier.CONTROL,
            boxText = "x",
            macroAtSlot = "",
            group = 0,
            macroEnabled = false,
        )
        assertIs<MacroKeys.Decision.SwitchGroup>(d)
        assertEquals(3, d.group)
    }

    // ---- 右键菜单那一路 ----

    @Test
    fun `菜单预填在标志为假时不响应`() {
        assertIs<MacroKeys.Decision.Ignored>(
            MacroKeys.prefillFromMenu(macro = "宏", inProcess = false, macroEnabled = false),
            "macroEnabled == false ⇒ 菜单项不预填（R4-AC1）",
        )
    }

    @Test
    fun `菜单预填缺省时照旧`() {
        val ok = MacroKeys.prefillFromMenu(macro = "宏", inProcess = false)
        assertIs<MacroKeys.Decision.Prefill>(ok)
        assertEquals("宏", ok.text, "R4-AC2")
    }

    // ---- 会话贯通（InputSession.onMacroKey → MacroKeys.dispatch → applyMacro）----

    @Test
    fun `标志为假时会话的 F 键不动框不提交`() {
        val s = sessionWithMacro(macro = "123")
        s.box.assign("旧内容")
        val d = s.onMacroKey(MacroKeys.VK_F1, MacroKeys.Modifier.NONE, macroEnabled = false)
        assertIs<MacroKeys.Decision.Ignored>(d)
        assertEquals("旧内容", s.box.text, "不预填 ⇒ 框一字不动（R4-AC1）")
        assertTrue(s.submissions.isEmpty(), "预填这一路本来就不提交")
        assertFalse(s.macroTable.changed, "不预填也不碰表")
    }

    @Test
    fun `缺省时会话的 F 键照常预填`() {
        val s = sessionWithMacro(macro = "123")
        val d = s.onMacroKey(MacroKeys.VK_F1, MacroKeys.Modifier.NONE)
        assertIs<MacroKeys.Decision.Prefill>(d)
        assertEquals("123", s.box.text, "R4-AC2：与本波前逐项一致")
        assertEquals(3, s.box.selectionStart, ":510 插入点放到末尾")
        assertTrue(s.submissions.isEmpty())
    }

    @Test
    fun `标志为假时连空宏清框都不发生`() {
        // macroEnabled == true 时「空宏预填 = 清框」（:509 那一路没有空值守卫）；
        // 为假时连清框都不发生 —— 这条与「缺省」的差别正是本闸的可见效果。
        val s = sessionWithMacro(macro = "")
        s.box.assign("保留")
        val d = s.onMacroKey(MacroKeys.VK_F1, MacroKeys.Modifier.NONE, macroEnabled = false)
        assertIs<MacroKeys.Decision.Ignored>(d)
        assertEquals("保留", s.box.text, "不预填 ⇒ 旧框内容保留，不被空宏清掉")
    }
}