package com.eratw.port.render.input

import com.eratw.port.render.scroll.Viewport

/**
 * 输入会话 —— 把「文本框 / 历史 / 宏表 / 位置 / 视口」缝成**唯一**的提交口，
 * 用来固化本波最关键的一条判据：**预填永远不提交，提交必须由用户显式按下回车**。
 *
 * ## 为什么要多这一个类
 * [MacroKeys] 只回答「这枚按键该怎么处置」，[InputHistory] 只管历史数组，
 * [EmueraTextBox] 只管内容与光标；「按完之后到底有没有把文本交给核心层」这件事
 * 在原版是散在 `MainWindow.cs` 的四个入口里的，本类把那四个入口收成一个可单测的面：
 * - **回车（显式提交）**：`richTextBox1_KeyDown` 的 `Keys.Return` 支
 *   （`MainWindow.cs:1407-1414`）⇒ `e.SuppressKeyPress = true`，且只在 `!console.IsInProcess`
 *   （属性本体 `EmueraConsole.cs:298`）时才调 `PressEnterKey(mesSkip: false, inputsByMouse: false)`。
 * - **Esc（也是提交，但带 mesSkip）**：同函数的 `Keys.Escape` 支
 *   （`MainWindow.cs:1415-1422`）：`console.KillMacro = true` 后
 *   `PressEnterKey(mesSkip: true, ...)`，同样受 `!console.IsInProcess` 夹（:1419-1421）。
 * - **HOTKEY.ERB 命中（自动提交）**：`MainWindow.cs:1362-1374` ——
 *   清框 → `AppendText(数字)` → 吞键 → **提交** → `SelectAll`。这是原版唯一一处
 *   「按键自己把文本交出去」的路径，与宏预填形成对照，所以必须建在同一个类里才测得出差别。
 * - **`KeysUp/Down` 召回（不提交）**：`MainWindow.cs:1432-1447`，只改框内文本。
 *
 * 宏预填（[applyMacro] 的 `Prefill` 支、:507-512）与右键菜单预填（:1565-1576）**都不在**这份名单里：
 * `ProcessCmdKey` 那一段从头到尾没有一次 `PressEnterKey` 调用（`MainWindow.cs:507-512` 只有
 * `Text=` 与 `SelectionStart=` 两句），板上同口径（`docs/golden-runbook.md:106`「不会自动回车提交」、
 * `:125`「回车仍需手按」）。触屏侧因此必须自己给一个「提交」按钮，等价于 :1407 那枚 `Keys.Return`。
 *
 * ## `richTextBox1_KeyDown` 的总闸与「按键即回贴底」（2026-10-21 审计补）
 * 上面四个入口都挂在同一个事件处理器 `richTextBox1_KeyDown`（`MainWindow.cs:1350-1452`）上，
 * 而这个处理器还有两条**所有分支共用**的前后置：
 * 1. **总闸 `console == null` 就直接 return**（`MainWindow.cs:1352-1355`）。
 *    它在 :1362 的 `hotkeyState.keyToNumberRunInterpreter` **之前**，所以没有核心层时
 *    连「清框 + 追加数字」都不发生（不只是不提交），`movePrev` 同样一次都不调。
 *    见 [onHotkeyErbNumber] 与 [recallHistory] 的 `consolePresent` 判定。
 * 2. **回贴底**：`MainWindow.cs:1402-1406` 在派发具体按键之前无条件做一次
 *    `if (vScrollBar.Value != vScrollBar.Maximum) { vScrollBar.Value = vScrollBar.Maximum;
 *    console.RefreshStrings(force_Paint: true); }`。也就是说**只要键落到了这个处理器的
 *    公共段，上翻着的画面先弹回底部再处理这个键**。
 *    走到这一步的有：回车（:1407）、Esc（:1415）、边界键 Left/Home/Back 与 Right/End
 *    （:1424、:1428）、↑/↓ 召回（:1432、:1440）、Insert（:1448）以及一切普通打字。
 *    **不**走到这一步的有三支，因为它们在前面的 `return` 就出去了：
 *    `IsWaitingPrimitive`（:1356-1361）、`HOTKEY.ERB` 命中（:1362-1374，:1373 处 return）、
 *    PageUp/PageDown（:1375-1401，:1400 处 return —— 翻页键本来就是在上翻态用的）。
 *    F1-F12 宏与 Ctrl+数字换组走的是 `ProcessCmdKey`（:383 起），根本不经这个处理器，
 *    所以宏预填也不贴底（`docs/golden-runbook.md:106` 那条判据的另一半）。
 *    贴底这件事在 WinForms 里还会**顺带**触发一次 `vScrollBar.ValueChanged`
 *    （订阅见 `MainWindow.cs:279`）⇒ [TextBoxPosition.onScrollBarValueChanged] 拿到
 *    `atMaximum = true`，`ScrollBack` 态的输入框位置因此被补回来（:335-338）。
 *    [snapToBottomForKeyDown] 把这条链一起建模；没有接视口（[viewport] 为 null，
 *    单测与纯输入场景）时整段是空操作，行为与本文件改动前逐条相同。
 *
 * ## 唯一的例外：`ONEINPUT` 系的「打字即提交」
 * `richTextBox1_TextChanged`（`MainWindow.cs:1313-1332`）在
 * `console.IsWaintingOnePhrase`（`EmueraConsole.cs:359-369`，即 `inputReq.OneInput`）
 * 或 `console.IsWaitAnyKey`（:346-357）为真、且框内文本比 `last_inputed` **变长**时，
 * 会直接调 `PressEnterKey`（:1330）。所以「预填不提交」这条判据的准确表述是：
 * **宏这一路自己绝不提交**，但如果此刻核心层正在等单键输入，赋文本这件事会经
 * `TextChanged` 触发提交 —— 这是官方行为，[onTextChanged] 原样建模，不藏。
 * 守卫链 :1315 的六个条件（`console == null`、`IsInProcess`、`!textBox_flag`、
 * 两个等待位都不成立、文本为空、`changeTextbyMouse`）逐个照抄，少一条都会让安卓侧多提交。
 *
 * ## 提交前后的两步副作用（顺序有出处，不许换）
 * 1. 先切掉单键等待前缀再入历史（`MainWindow.cs:926-933`：:927-931 去 `last_inputed`、
 *    :933 `updateInputs`、:934 才交给 `console.PressEnterKey`）。原版用的是
 *    `text.Remove(0, last_inputed.Length)`（:929）——**按下标硬切**，不做「是不是这个前缀」的匹配，
 *    且 `last_inputed` 比 `text` 长时 C# 抛 `ArgumentOutOfRangeException`；本层用
 *    [kotlin.text.String.substring] 保持同样的抛出行为（ADR-005，不夹取）。
 * 2. 核心层接受之后才复位文本框位置（`EmueraConsole.cs:1283-1286`）：
 *    `if (window.TextBoxPosChanged) window.ResetTextBoxPos()` —— 只夹 `Changed` 态，
 *    `WatingToChange` 不会被复位（详见 [TextBoxPosition.afterAcceptedInput]）。
 *    被核心层拒绝的输入（`EmueraConsole.cs:1125-1128` 一类 `return false`）走不到 :1283，
 *    但**已经进过历史**了（第 1 步在前）。
 *
 * ## 本类不管什么
 * 文件 I/O、编码嗅探、`MessageBox`、真正的软键盘/输入法（属宿主与 Android 绑定层）；
 * `@` 系统命令（`EmueraConsole.cs:1412-1416`）、`InputType.Void` 的空转（:1444-1448）与
 * `parseInput` 的表达式求值（:1425-1428）都属核心层，本类只把它们标出来（[isSystemCommand]），
 * 不替核心层做决定。
 * TODO(verify)：`KillMacro`（`MainWindow.cs:1418` → `EmueraConsole.cs:1402`、:1462-1466）在
 * 安卓侧等价于「中止当前宏回放」，但本波没有宏回放，故只保留 Esc 的提交语义，不建模 KillMacro。
 */
class InputSession(
    /** 文本框本体（原版 `richTextBox1`，声明 `MainWindow.cs:112`，对外经 :210 暴露）。 */
    val box: EmueraTextBox = EmueraTextBox(),
    /** ↑↓ 历史三件套（`MainWindow.cs:52-56`）。 */
    val history: InputHistory = InputHistory(),
    /** 键盘宏表（`KeyMacro` 静态类的实例化，见 [KeyMacroTable]）。 */
    val macroTable: KeyMacroTable = KeyMacroTable(),
    /** 文本框位置状态机；没有窗口时传 null（原版这些都挂在 `MainWindow` 上）。 */
    val position: TextBoxPosition? = null,
    /**
     * 滚动视口（`vScrollBar` 那一侧，见 `scroll/Viewport`）。
     * 传 null（默认）= 本会话不管滚动，[snapToBottomForKeyDown] 是空操作；
     * 真机绑定层把同一个 [Viewport] 实例同时交给绘制侧与本会话，:1402-1406 那条
     * 「按键先弹回底部」才有效果。
     */
    val viewport: Viewport? = null,
) {

    /** 当前宏组（`macroGroup` `MainWindow.cs:68`，只有 :1604 会改它）。 */
    var macroGroup: Int = 0
        private set

    /**
     * 「上一次由程序写入框内的文本」（`last_inputed` `MainWindow.cs:62`）。
     * 写它的只有 `update_lastinput`（:1305）、`TextChanged` 的两支（:1321、:1328）。
     */
    var lastInputed: String = ""
        private set

    /** `console.IsWaintingOnePhrase`（`EmueraConsole.cs:359-369`）。 */
    var waitingOnePhrase: Boolean = false

    /** `console.IsWaitAnyKey`（`EmueraConsole.cs:346-357`）。 */
    var waitAnyKey: Boolean = false

    /** `console.IsInProcess`（`EmueraConsole.cs:298`）；四个提交入口共用这一道闸。 */
    var inProcess: Boolean = false

    /** `console != null`（`MainWindow.cs:924` 的前半个条件）。 */
    var consolePresent: Boolean = true

    /** `textBox_flag` 重入闸（`MainWindow.cs:60`，:1324 关、:1331 开）。 */
    var textBoxFlag: Boolean = true
        private set

    /** `changeTextbyMouse`（`MainWindow.cs:50`；:932 在提交前清 false，写 true 的在鼠标侧）。 */
    var changedByMouse: Boolean = false

    /** `Config.AllowLongInputByMouse`（默认 false，`ConfigData.cs:104`）。 */
    var allowLongInputByMouse: Boolean = false

    /**
     * 已经交给核心层的提交流水（只追加）。
     * 存在的理由就一条：让「预填不提交」这句判据可以被直接断言 ——
     * 宏那一路跑完后它是空的，回车/`HOTKEY.ERB`/`TextChanged` 跑完才多出一条。
     * 挂在实例上而不是静态场（AGENTS.md 铁律 4），顺序即提交序，读数确定性。
     */
    private val submissionLog = ArrayList<Submission>()

    /** [submissionLog] 的只读视图。 */
    val submissions: List<Submission>
        get() = submissionLog.toList()

    /**
     * 应用 [MacroKeys.dispatch] / [MacroKeys.prefillFromMenu] 的结论；**本函数不会提交**。
     *
     * - `Prefill` → `Text = 宏` + `SelectionStart = Text.Length`（`MainWindow.cs:509-510`），
     *   宏为空串时同样赋值，于是框被清空（原版那一路没有空值守卫）。
     * - `Record` → `KeyMacro.SetMacro(fkey, macroGroup, 框内文本)`（:503），
     *   但框内为空时**不写**（:501-504 的 `IsNullOrEmpty` 守卫），两支都吞键。
     * - `SwitchGroup` → 换组并给出组名标签文本（`setNewMacroGroup` :1601-1611，
     *   标签文本 = [KeyMacroTable.groupName]，:1605）。
     * - `Ignored` → 什么都不做。
     *
     * @return 换组时给绑定层的标签文本，其余为 null
     */
    fun applyMacro(decision: MacroKeys.Decision): String? = when (decision) {
        MacroKeys.Decision.Ignored -> null
        is MacroKeys.Decision.Prefill -> {
            box.assign(decision.text)
            box.moveCaretToEnd()
            null
        }
        is MacroKeys.Decision.Record -> {
            if (decision.write) {
                macroTable.set(decision.fkey, decision.group, decision.value)
            }
            null
        }
        is MacroKeys.Decision.SwitchGroup -> {
            macroGroup = decision.group
            macroTable.groupName(decision.group)
        }
    }

    /**
     * 宏这一路的完整「按键 → 框内」链，方便单测直接喂按键码（不含任何提交动作）。
     * 组合键的判定与门槛都在 [MacroKeys.dispatch]，这里只负责把结论落到框与表上。
     *
     * 门槛一在原版是 `console != null && console.IsWaitingPrimitive` 的**合取**
     * （`MainWindow.cs:385`）：没有核心层时那道早退不成立，宏照样预填。
     * 所以这里传的是 `waitingPrimitive && consolePresent`，而不是裸的 `waitingPrimitive`。
     * 这一路整体**不**贴底（`ProcessCmdKey` 不经 `richTextBox1_KeyDown`，见类文档）。
     */
    fun onMacroKey(
        keyCode: Int,
        modifier: MacroKeys.Modifier,
        waitingPrimitive: Boolean = false,
        useKeyMacro: Boolean = true,
    ): MacroKeys.Decision {
        val decision = MacroKeys.dispatch(
            keyCode = keyCode,
            modifier = modifier,
            boxText = box.text,
            macroAtSlot = if (keyCode in MacroKeys.VK_F1..MacroKeys.VK_F12) {
                macroTable.get(keyCode - MacroKeys.VK_F1, macroGroup)
            } else {
                ""
            },
            group = macroGroup,
            useKeyMacro = useKeyMacro,
            waitingPrimitive = waitingPrimitive && consolePresent,
        )
        applyMacro(decision)
        return decision
    }

    /**
     * `update_lastinput()`（`MainWindow.cs:1298-1306`）：把框内文本记为「程序已写入的基线」。
     * 原版在赋值前后摘挂 `TextChanged`/`KeyDown`（:1300-1304）并 `Application.DoEvents()`
     * （:1302），那三件事是 WinForms 的事，本层只保留 `last_inputed` 的读数。
     * 谁调它：`console.WaitInput` 在 `req.Timelimit > 0 && req.OneInput` 时（`EmueraConsole.cs:866-872`）。
     */
    fun updateLastInput() {
        lastInputed = box.text
    }

    /**
     * 唯一的提交口 `PressEnterKey(mesSkip, inputsByMouse)`（`MainWindow.cs:922-936`）。
     *
     * 守卫 :924 两条都不满足就返回 null（核心层没建 / 正在跑程序）。
     * 之后按 :926-934 的顺序：读框 → 切单键前缀 → 清 `changeTextbyMouse` → **入历史** →
     * 把文本交给核心层。历史动作（[HistoryEdit]）在这里就地落到框上，
     * 因为原版 `updateInputs` 的两条 `Text = ""` 就是直接写框的（:942、:965）。
     *
     * @return 交给核心层的那一条；被守卫挡掉时为 null
     */
    fun pressEnterKey(mesSkip: Boolean, inputsByMouse: Boolean): Submission? {
        if (!consolePresent || inProcess) {
            return null
        }
        var text = box.text
        if (waitingOnePhrase && lastInputed.isNotEmpty()) {
            text = text.substring(lastInputed.length)
            lastInputed = ""
        }
        changedByMouse = false
        applyHistory(history.record(text))
        val submission = Submission(text = text, mesSkip = mesSkip, inputsByMouse = inputsByMouse)
        submissionLog.add(submission)
        return submission
    }

    /** 回车键那一路（`MainWindow.cs:1407-1414`）；:1402 的回贴底在前，:1410 的闸在后。 */
    fun onEnterKeyPressed(): Submission? {
        if (!consolePresent) {
            return null
        }
        snapToBottomForKeyDown()
        return pressEnterKey(mesSkip = false, inputsByMouse = false)
    }

    /**
     * Esc 键那一路（`MainWindow.cs:1415-1422`）：`KillMacro` 属核心层（本波不建模，见类文档），
     * 提交时 `mesSkip = true`。同样先吃 :1402 的回贴底。
     */
    fun onEscapeKeyPressed(): Submission? {
        if (!consolePresent) {
            return null
        }
        snapToBottomForKeyDown()
        return pressEnterKey(mesSkip = true, inputsByMouse = false)
    }

    /**
     * `richTextBox1_KeyDown` 公共段的「按键即回贴底」（`MainWindow.cs:1402-1406`）。
     *
     * 三条判据都有出处：
     * - **只在真的贴过底时才有副作用**：原版外层那句 `if (Value != Maximum)`（:1402），
     *   已经在底部时连 `RefreshStrings` 都不调，`ValueChanged` 也不触发 ⇒ 本函数幂等，
     *   绑定层对同一枚键重复调用不会多滚一次。
     * - **顺带通知位置状态机**：`vScrollBar.Value = vScrollBar.Maximum`（:1404）会触发
     *   `ValueChanged`，订阅方是 `textBoxHandleScrollValueChanged`（`MainWindow.cs:279`），
     *   它在 `Value == Maximum && TextBoxPosScrolledBack` 时调 `ApplyTextBoxChanges()`
     *   （:335-338）。所以这里把 `atMaximum = true` 转给 [TextBoxPosition]。
     *   闸门 [TextBoxPosition.ignoreScrollBarChanges] 只在程序化滚动那一路合（
     *   `EmueraConsole.cs:2551`、`:2566`），用户按键这一路原版不关闸，故这里照直调。
     * - **不该调它的三支**：`IsWaitingPrimitive`（:1356-1361）、`HOTKEY.ERB` 命中
     *   （:1362-1374，:1373 提前 return）、PageUp/PageDown（:1375-1401，:1400 提前 return）。
     *   翻页键若也贴底，用户就再也翻不上去看了 —— 这是原版特意 `return` 掉的。
     *   宏与换组走 `ProcessCmdKey`，不经本处理器。
     *
     * @return 本次是否真的把视口弹回了底部（本来就在底部、或没接视口时为 false）
     */
    fun snapToBottomForKeyDown(): Boolean {
        val view = viewport ?: return false
        if (view.atBottom) {
            return false
        }
        view.snapToBottom()
        position?.onScrollBarValueChanged(atMaximum = true)
        return true
    }

    /**
     * `richTextBox1_TextChanged` 的自动提交（`MainWindow.cs:1313-1332`）。
     *
     * 六条守卫（:1315）任一命中就返回 null；文本没变长时只把基线推平（:1319-1323）；
     * 真要提交时先关重入闸（:1324），`waitAnyKey` 那一路还要先清框并清空基线
     * （:1325-1329 的 `Clear()` + `last_inputed = ""`），再提交，最后开闸（:1331）。
     * 注意 :1327 清空**之后**才提交 ⇒ 交给核心层的文本是空串，官方行为原样保留。
     */
    fun onTextChanged(): Submission? {
        if (!consolePresent || inProcess || !textBoxFlag) {
            return null
        }
        if (!waitingOnePhrase && !waitAnyKey) {
            return null
        }
        if (box.text.isEmpty() || changedByMouse) {
            return null
        }
        if (box.text.length <= lastInputed.length) {
            lastInputed = box.text
            return null
        }
        textBoxFlag = false
        if (waitAnyKey) {
            box.clear()
            lastInputed = ""
        }
        val submission = pressEnterKey(mesSkip = false, inputsByMouse = false)
        textBoxFlag = true
        return submission
    }

    /**
     * HOTKEY.ERB 命中那一路（`MainWindow.cs:1362-1374`）：清框 → 追加数字 → 提交 → 全选。
     *
     * 与 [onMacroKey] 的区别就在中间那次提交：这一路**确实**会自动把文本交出去，
     * 但仍受 `!console.IsInProcess` 夹（:1368）；提交被挡掉时后面的 `SelectAll()` 照做（:1372）。
     * 两道前置也照抄：`console == null` 时整个处理器在 :1352 就 return，
     * 连 :1365-1366 的清框/追加都不发生；而 :1373 的 `return` 在本文件
     * [snapToBottomForKeyDown] 那一段（:1402-1406）**之前**，所以这一路**不**贴底。
     *
     * @param number [HotkeyErbProgram.keyToNumber] 的返回值（非 [HotkeyErbProgram.NO_MATCH]）
     */
    fun onHotkeyErbNumber(number: Int): Submission? {
        if (!consolePresent) {
            return null
        }
        box.clear()
        box.appendText(number.toString())
        val submission = pressEnterKey(mesSkip = false, inputsByMouse = false)
        box.selectAll()
        return submission
    }

    /**
     * ↑/↓ 召回（`MainWindow.cs:1432-1447` 调 `movePrev`）；召回**不提交**，只写框。
     * 三段前后置都按处理器的物理顺序排：
     * `console == null` 总闸（:1352）→ 回贴底（:1402-1406）→ `!console.IsInProcess`（:1435、:1443）。
     * 中间那段是官方的「上翻着按 ↑ ：画面先弹回底部，然后照样召回历史」——
     * 贴底与召回是两件独立的事，所以 `inProcess` 挡住召回时贴底**已经发生了**，
     * 这一点在屏上是看得见的（[snapToBottomForKeyDown] 的返回值就是那条读数）。
     *
     * @return 是否真的改了框内文本（不命中时为 false，对应 [HistoryEdit.None]）
     */
    fun recallHistory(move: Int): Boolean {
        if (!consolePresent) {
            return false
        }
        snapToBottomForKeyDown()
        if (inProcess) {
            return false
        }
        return applyHistory(history.recall(move))
    }

    /**
     * 把历史层的动作落到框上。
     * `selectAll = true` 那一支对应 `movePrev` 尾部的两句
     * （`SelectionStart = 0` + `SelectionLength = Text.Length`，`MainWindow.cs:1000-1001`）。
     */
    private fun applyHistory(edit: HistoryEdit): Boolean = when (edit) {
        HistoryEdit.None -> false
        is HistoryEdit.SetText -> {
            box.assign(edit.text)
            if (edit.selectAll) {
                box.selectWholeLine()
            }
            true
        }
    }

    /**
     * 核心层接受之后的收尾（`EmueraConsole.cs:1283-1286` 的 `ResetTextBoxPos` 与
     * `EmueraConsole.cs:1456` 的 `MesSkip = false`）。位置复位只在 `Changed` 态发生。
     */
    fun onInputAccepted() {
        position?.afterAcceptedInput()
    }

    /**
     * `input.Split(spliter, None)`（`EmueraConsole.cs:1429`，切分符表见 :146）。
     * 鼠标改过的文本不切（:1406-1409 的 `changedByMouse` 支只留一整条）。
     *
     * 注意 :146 的四元素里含**字面量反斜杠加 n**，所以原文里成对的 `\n` 两字符也会断开 ——
     * 官方行为，原样保留（`docs/golden-runbook.md` 的输入重放口径依赖它）。
     */
    fun splitPieces(submission: Submission): List<InputPiece> {
        val changedByMouseHere = submission.inputsByMouse
        val raw: List<String> = if (changedByMouseHere) {
            listOf(submission.text)
        } else {
            splitBySpliter(submission.text)
        }
        return raw.map { piece ->
            // :1435-1439：`\e` 两字符被剥掉并把 MesSkip 置真。
            val mesSkipRequested = piece.contains("\\e")
            val stripped = if (mesSkipRequested) piece.replace("\\e", "") else piece
            // :1440-1443：单键输入截成 1 字符（鼠标长输入按配置放行）。
            val truncated =
                if (waitingOnePhrase && (!allowLongInputByMouse || !changedByMouseHere) && stripped.length > 1) {
                    stripped.substring(0, 1)
                } else {
                    stripped
                }
            InputPiece(truncated, mesSkipRequested)
        }
    }

    /** `EmueraConsole.cs:1412-1416` 的那条短路：`@` 开头且长度大于 1 且不是单键输入 ⇒ 走系统命令。 */
    fun isSystemCommand(submission: Submission): Boolean =
        submission.text.length > 1 && !waitingOnePhrase && submission.text.startsWith("@")

    /** :1417-1420 的 `InputType.Void` 早退（纯回车等待）由核心层判，本层只透传现状给绑定层。 */
    var waitingForVoidInput: Boolean = false

    companion object {
        /** `EmueraConsole.cs:146` 的 `spliter` 四元素，顺序照抄（数组序即匹配序，确定性）。 */
        val SPLITTER: List<String> = listOf("\\n", "\r\n", "\n", "\r")

        /**
         * 按 [SPLITTER] 切串。`String.split(vararg, None)` 语义：任一元素都算分隔符，
         * 空段保留（连续分隔符产生空串），无分隔符时得单元素。
         */
        fun splitBySpliter(text: String): List<String> {
            val out = ArrayList<String>()
            val current = StringBuilder()
            var i = 0
            while (i < text.length) {
                val matched = SPLITTER.firstOrNull { token -> text.startsWith(token, i) }
                if (matched == null) {
                    current.append(text[i])
                    i++
                } else {
                    out.add(current.toString())
                    current.setLength(0)
                    i += matched.length
                }
            }
            out.add(current.toString())
            return out
        }
    }
}

/**
 * 一次提交交给核心层的东西（`MainWindow.cs:934` 的三个实参）。
 * `data class` 便于等值断言；字段全部是纯值，没有 Map 迭代序参与。
 */
data class Submission(
    /** 去掉单键等待前缀后的文本（`MainWindow.cs:926-931`）。 */
    val text: String,
    /** `mesSkip` 实参：回车为 false（:1412），Esc 为 true（:1421）。 */
    val mesSkip: Boolean,
    /** `inputsByMouse` 实参；四个入口原版都传 false（:1330、:1370、:1412、:1421）。 */
    val inputsByMouse: Boolean,
)

/**
 * 提交文本切出来的一段（`EmueraConsole.cs:1432-1449` 循环体里的那个 `text`）。
 *
 * @property text 剥掉 `\e` 并按 `OneInput` 截断后的文本
 * @property mesSkipRequested 本段是否含 `\e`（:1435-1439 会据此把 `MesSkip` 置真）
 */
data class InputPiece(val text: String, val mesSkipRequested: Boolean)
