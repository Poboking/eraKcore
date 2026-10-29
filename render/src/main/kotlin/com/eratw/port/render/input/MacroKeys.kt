package com.eratw.port.render.input

/**
 * F1-F12 键盘宏的**按键分派**（不提交！）—— `MainWindow.cs:383-390` 的 `ProcessCmdKey`
 * 入口与 `:486-531` 那个 `default` 分支的纯函数化。
 *
 * ## 本波最重要的一条口径
 * 按 F1-F12 **只把宏文本灌进输入框，绝不自动回车**：
 * `richTextBox1.Text = KeyMacro.GetMacro(fkeyNum, macroGroup)` 之后只补了一句
 * `SelectionStart = Text.Length`，然后 `return true`（`MainWindow.cs:507-512`）——
 * 全流程里没有 `PressEnterKey`。板上同口径（`docs/golden-runbook.md:106`「不会自动回车提交」、
 * `:125`「回车仍需手按」），触屏侧的要求也写在 `BOARD.md` 的 M5-TOUCH 行
 * （「F1-F12 只预填不自动回车，触屏须显式提交」）。
 * 对照：**HOTKEY.ERB 命中那一路是会自动提交的**
 * （`MainWindow.cs:1362-1374`：清框 → `AppendText(数字)` → 吞键 → `PressEnterKey` → `SelectAll`），
 * 见 [HotkeyErbProgram.keyToNumber] 的用法与 [InputSession.onHotkeyErbNumber]。
 * 两者都在「预填文本框」这一步长得一样，差别只在最后一句是否提交，所以必须分开建模。
 *
 * ## 判定细节（逐条行号）
 * - 门槛一：`console != null && console.IsWaitingPrimitive` 为真时 `ProcessCmdKey`
 *   **立刻 return false**，宏一律不响应（`MainWindow.cs:385-388`；该属性 = 等待原始鼠标/键盘输入，
 *   `EmueraConsole.cs:383-392`）。注意这是**合取**：没有核心层时条件不成立、**不会**早退，
 *   `default` 支里也不解引用 `console`，所以宏在 `console == null` 时照样预填 ——
 *   调用方要传进来的 `waitingPrimitive` 必须是「两个条件与出来的结果」
 *   （`InputSession.onMacroKey` 已这么算），不能只喂 `IsWaitingPrimitive`。
 * - 门槛二：`Config.UseKeyMacro` 关掉 ⇒ 走 `break`（`MainWindow.cs:488-491`），
 *   默认开（`ConfigData.cs:53` 的 `t: true`）。
 * - 功能键区间：`(int)(keyData & Keys.KeyCode)` 落在 112..123 ⇒ `fkeyNum = num - 112`
 *   （:492、:496-498），即 F1=0 … F12=11，与 [KeyMacroTable.get] 的第一维同口径。
 * - 修饰键判定用的是**整集合相等**，不是 HasFlag：
 *   `flag = (keyData & Keys.Modifiers) == Keys.Shift`、
 *   `flag2 = ... == Keys.Control`、`flag3 = ... == 0`（:493-495）。
 *   因此 Alt+F1、Ctrl+Shift+F1 这类组合三个旗标全 false ⇒ **什么都不做**（连吞键都不吞），
 *   官方行为，原样保留。
 * - Shift+F1-F12 ⇒ 「把框内现文本存进宏」，且**框内为空时不覆盖旧宏**（:499-506）；
 *   无修饰 ⇒ 预填（:507-512）。两支都 `return true`。
 * - Ctrl+数字 ⇒ 换宏组：主键盘 48..57 减 48、小键盘 96..105 减 96（:514-528），
 *   且这条**不吞键**（走 :530 的 `break` → `:650` 交回基类）。
 *   换组后 `setNewMacroGroup` 会在输入框上方显示组名标签，200ms × 11 拍后隐藏
 *   （:1601-1611、:1590-1599），标签文本 = [KeyMacroTable.groupName]。
 * - 右键菜单那一路（:1565-1576）与按键那一路**守卫不同**：
 *   菜单只在宏非空时才赋文本（`if (macro.Length > 0)` :1570-1574），
 *   按键则无条件赋（:509，宏为空 ⇒ 清空框内）。建模成 [prefillFromMenu] 与 [dispatch] 两个入口。
 *   菜单那一路的三道闸是 `console != null && !console.IsInProcess && Config.UseKeyMacro`
 *   （:1567，三个条件**都在**取宏之前；按键那一路没有 `IsInProcess` 这一条）。
 *   `ProcessCmdKey` 与菜单这两路都**不**动滚动条 —— 滚动条那一段在
 *   `richTextBox1_KeyDown` 的公共段（`MainWindow.cs:1402-1406`），见 `InputSession` 类文档。
 */
object MacroKeys {

    /** `Keys.F1` 的数值（`MainWindow.cs:496` 区间下界）。 */
    const val VK_F1: Int = 112

    /** `Keys.F12` 的数值（:496 区间上界）。 */
    const val VK_F12: Int = 123

    /** 主键盘 0/9 的虚拟键码（:517）。 */
    const val VK_DIGIT_0: Int = 48

    /** 小键盘 0/9 的虚拟键码（:521）。 */
    const val VK_NUMPAD_0: Int = 96

    /**
     * 修饰键集合。原版是 `Keys.Modifiers` 的整值比较（:493-495），
     * 本层把它收成四态：`NONE`/`SHIFT`/`CONTROL`/`OTHER`（`OTHER` 涵盖
     * Alt、Ctrl+Shift 等一切「三个旗标都不成立」的组合）。
     */
    enum class Modifier { NONE, SHIFT, CONTROL, OTHER }

    /** 一次按键的处置结论。 */
    sealed class Decision {
        /** 宏层不响应（含 `OTHER` 修饰组合、非功能键非数字、开关关闭、等待原始键）。 */
        object Ignored : Decision()

        /**
         * 预填：把 [text] 赋给输入框并把插入点放到末尾（:509-510）。
         * [text] 为空串时同样赋值 ⇒ 清空框内（按键那一路无空值守卫）。
         */
        data class Prefill(val text: String) : Decision()

        /**
         * 记录：把框内现文本写进 (fkey, group) 槽（:503）。
         * [write] 为 false 表示框内为空、原版跳过写入但仍吞键（:501-505）。
         */
        data class Record(val fkey: Int, val group: Int, val value: String, val write: Boolean) : Decision()

        /** 换宏组（:527）。[suppressKey] 原版为 false —— 这条键会继续往控件走。 */
        data class SwitchGroup(val group: Int, val suppressKey: Boolean = false) : Decision()
    }

    /**
     * 按键分派（`ProcessCmdKey` 的 `default` 支 + 两道前置门槛）。
     *
     * @param keyCode `keyData & Keys.KeyCode` 的整值（:492）
     * @param modifier 修饰键集合（:493-495 折成的三旗标之一）
     * @param boxText 输入框当前文本（:501-503 读它）
     * @param macroAtSlot 该功能键在当前组的宏文本，即 `KeyMacro.GetMacro(fkeyNum, macroGroup)`
     *   （:509 读的那一句；由调用方查 [KeyMacroTable]，本函数不持表）
     * @param group 当前宏组 `macroGroup`（声明 `MainWindow.cs:68`，读取 :503、:509、:1569）
     * @param useKeyMacro `Config.UseKeyMacro`（:488）
     * @param waitingPrimitive 「门槛一」整条的结论，即 `console != null && console.IsWaitingPrimitive`
     *   （:385，见类文档；单喂 `IsWaitingPrimitive` 会在无核心层时少预填一次）
     */
    fun dispatch(
        keyCode: Int,
        modifier: Modifier,
        boxText: String,
        macroAtSlot: String,
        group: Int,
        useKeyMacro: Boolean = true,
        waitingPrimitive: Boolean = false,
    ): Decision {
        if (waitingPrimitive) {
            // :385-388：等待原始键期间，整段 ProcessCmdKey 提前返回，宏一律不响应。
            return Decision.Ignored
        }
        if (!useKeyMacro) {
            // :488-491：开关关掉直接 break（交回基类）。
            return Decision.Ignored
        }
        if (keyCode in VK_F1..VK_F12) {
            val fkey = keyCode - VK_F1
            return when (modifier) {
                // :499-506：Shift+Fn 存宏（空框不覆盖），吞键。
                Modifier.SHIFT -> Decision.Record(fkey, group, boxText, write = boxText.isNotEmpty())
                // :507-512：无修饰预填（宏为空即清框），吞键。
                Modifier.NONE -> Decision.Prefill(macroAtSlot)
                // :493-495 的三个旗标都不成立 ⇒ 整条 default 走完 break，什么都不做。
                Modifier.CONTROL, Modifier.OTHER -> Decision.Ignored
            }
        }
        if (modifier == Modifier.CONTROL) {
            val g = groupOf(keyCode) ?: return Decision.Ignored
            return Decision.SwitchGroup(g)
        }
        return Decision.Ignored
    }

    /**
     * 右键菜单那一路（`MainWindow.cs:1565-1576`）：宏为空 ⇒ 整条不响应，
     * 非空 ⇒ 赋文本 + 插入点到末尾。菜单项的可用态另由
     * `GetMacro(j, macroGroup).Length > 0` 决定（:1492）。
     *
     * 三道闸按原版的合取顺序整条排在取宏**之前**（:1567），少一条都会在
     * 「核心层还没建 / 正在跑程序 / 开关关了」时多写一次框内文本。
     */
    fun prefillFromMenu(
        macro: String,
        inProcess: Boolean,
        useKeyMacro: Boolean = true,
        consolePresent: Boolean = true,
    ): Decision {
        if (!consolePresent || inProcess || !useKeyMacro || macro.isEmpty()) {
            return Decision.Ignored
        }
        return Decision.Prefill(macro)
    }

    /** 数字键 → 宏组号（:516-524）；非数字键给 null。 */
    fun groupOf(keyCode: Int): Int? = when (keyCode) {
        in VK_DIGIT_0..(VK_DIGIT_0 + 9) -> keyCode - VK_DIGIT_0
        in VK_NUMPAD_0..(VK_NUMPAD_0 + 9) -> keyCode - VK_NUMPAD_0
        else -> null
    }
}
