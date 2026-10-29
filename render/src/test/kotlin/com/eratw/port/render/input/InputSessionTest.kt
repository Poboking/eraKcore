package com.eratw.port.render.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [InputSession]：四个提交入口，以及本波最关键的一条判据 —— **预填不提交、显式提交才产出**。
 *
 * ## 期望值出处
 * - 显式回车：`MinorShift.Emuera.Forms/MainWindow.cs:1407-1414`（`SuppressKeyPress` 之后，
 *   只在 `!console.IsInProcess` 才调 `PressEnterKey`）。
 * - Esc：`MainWindow.cs:1415-1422`（同一道闸，但 `mesSkip = true`）。
 * - `HOTKEY.ERB` 命中：`MainWindow.cs:1362-1374`（清框 → `AppendText` → 吞键 → 提交 → `SelectAll`；
 *   清框、追加与全选都在 :1368 那道闸**之外**）。
 * - 提交口本体：`MainWindow.cs:922-936`（:924 的 `console != null && !IsInProcess`、:926 读框、
 *   :927-931 硬切单键前缀、:932 清 `changeTextbyMouse`、:933 先入历史、:934 才交核心层）。
 * - 打字即提交：`MainWindow.cs:1313-1332`（六条守卫 :1315、变长判定 :1319、
 *   `IsWaitAnyKey` 那一路先 `Clear()` 再提交 :1325-1329、重入闸 :1324 与 :1331）。
 * - 等待位与闸：`EmueraConsole.cs:359-369`（IsWaintingOnePhrase）、:346-357（IsWaitAnyKey）、
 *   :298（IsInProcess）。
 * - 切串与逐段处理：`EmueraConsole.cs:1406-1443`（`changedByMouse` 单条 :1406-1409、
 *   `@` 系统命令短路 :1412-1416、四元素切分符 :1429 对 :146、`\e` :1435-1439、
 *   `OneInput` 截断 :1440-1443；`Config.AllowLongInputByMouse` 默认 false 见 `ConfigData.cs:104`）。
 * - 宏那一路**没有**提交：`MainWindow.cs:507-512` 整段只有 `Text=` 与 `SelectionStart=` 两句；
 *   板上同口径 `docs/golden-runbook.md:106`、`:125`。
 * - 提交后被接受才复位位置：`EmueraConsole.cs:1283-1286`。
 *
 * 基准 `pc-golden-root.txt` 不含文本框/输入行读数（全文只有 :525-528 的 HOTKEY 状态段），
 * 所以本文件的每条断言都钉在上面这些 C# 行号上。
 */
class InputSessionTest {

    private val zhScheme = KeyMacroTable.NameScheme.SIMPLIFIED_CHINESE

    /**
     * 建一个会话，并按需在宏表 (group, fkey) 槽里预置宏文本。
     * 铺数据走 `loadLines`（宏文件的读盘形态，`KeyMacro.cs:99-133`）而不是 `set`，
     * 这样 `changed` 保持 false，「预填只读表」那条断言才不会被夹具自己污染。
     */
    private fun session(macro: String = "", fkey: Int = 0, group: Int = 0): InputSession {
        val table = KeyMacroTable(zhScheme)
        if (macro.isNotEmpty()) {
            table.loadLines(listOf(table.nameOf(fkey, group) + macro))
        }
        return InputSession(macroTable = table)
    }

    @Test
    fun `宏预填只写框不提交`() {
        val s = session(macro = "123")
        val decision = s.onMacroKey(MacroKeys.VK_F1, MacroKeys.Modifier.NONE)
        assertIs<MacroKeys.Decision.Prefill>(decision)
        assertEquals("123", s.box.text, ":509 赋宏文本")
        assertEquals(3, s.box.selectionStart, ":510 插入点放到末尾")
        assertEquals(0, s.box.selectionLength)
        assertTrue(s.submissions.isEmpty(), "整段 ProcessCmdKey 没有一次 PressEnterKey ⇒ 流水必须是空的")
        assertEquals(s.history.capacity, s.history.selectedInputs, "没提交就不该动历史游标（:966 只在 updateInputs 里）")
        assertFalse(s.macroTable.changed, "预填只读表，不写表（:509 走的是 GetMacro）")
    }

    @Test
    fun `预填之后显式回车才产出提交`() {
        val s = session(macro = "123")
        s.onMacroKey(MacroKeys.VK_F1, MacroKeys.Modifier.NONE)
        assertTrue(s.submissions.isEmpty(), "按 F1 之后仍然零产出")

        val submission = s.onEnterKeyPressed()
        assertNotNull(submission)
        assertEquals("123", submission.text, "交出去的就是预填的那串（:926 读框）")
        assertEquals(false, submission.mesSkip, ":1412 传 mesSkip = false")
        assertEquals(false, submission.inputsByMouse, ":1412 传 inputsByMouse = false")
        assertEquals(1, s.submissions.size, "触屏侧那颗「提交」按钮等价于这枚回车")
        assertEquals("", s.box.text, ":965 提交后清空")
        assertEquals("123", s.history.snapshot().last(), ":933 先入历史")
    }

    @Test
    fun `空宏预填等于清框`() {
        val s = session()
        s.box.assign("旧内容")
        val decision = s.onMacroKey(MacroKeys.VK_F1 + 2, MacroKeys.Modifier.NONE) // F3
        assertIs<MacroKeys.Decision.Prefill>(decision)
        assertEquals("", decision.text, "宏为空 ⇒ :509 赋空串，那一路没有空值守卫")
        assertEquals("", s.box.text)
        assertTrue(s.submissions.isEmpty())
    }

    @Test
    fun `Shift加功能键写宏但不提交`() {
        val s = session()
        s.box.assign("要存进宏的内容")
        val d = s.onMacroKey(MacroKeys.VK_F1 + 5, MacroKeys.Modifier.SHIFT)
        assertIs<MacroKeys.Decision.Record>(d)
        assertEquals("要存进宏的内容", s.macroTable.get(fkey = 5, group = 0), ":503 写进表")
        assertTrue(s.macroTable.changed, "SetMacro 置脏（KeyMacro.cs:146）")
        assertEquals("要存进宏的内容", s.box.text, "记录那一路不动框")
        assertTrue(s.submissions.isEmpty())
    }

    @Test
    fun `空框时Shift加功能键不覆盖旧宏`() {
        val s = session(macro = "旧宏", fkey = 5)
        s.box.clear()
        val d = MacroKeys.dispatch(
            keyCode = MacroKeys.VK_F1 + 5,
            modifier = MacroKeys.Modifier.SHIFT,
            boxText = s.box.text,
            macroAtSlot = s.macroTable.get(fkey = 5, group = 0),
            group = 0,
        )
        assertIs<MacroKeys.Decision.Record>(d)
        assertEquals(false, d.write, ":501-504 的 IsNullOrEmpty 守卫")
        s.applyMacro(d)
        assertEquals("旧宏", s.macroTable.get(fkey = 5, group = 0))
        assertFalse(s.macroTable.changed, "没写成 ⇒ 连脏都不置")
    }

    @Test
    fun `换组给出组名标签文本`() {
        val s = session()
        val label = s.applyMacro(MacroKeys.Decision.SwitchGroup(3))
        assertEquals(3, s.macroGroup, "setNewMacroGroup 里的 macroGroup = group（:1604）")
        assertEquals("设置为按键宏组3", label, ":1605 取 GetGroupName")
        assertTrue(s.submissions.isEmpty(), "换组同样不提交")
        assertNull(s.applyMacro(MacroKeys.Decision.Prefill("x")), "预填那一路没有标签要刷")
    }

    @Test
    fun `热键脚本命中那一路自动提交`() {
        val s = session()
        s.box.assign("框里原有内容")
        val submission = s.onHotkeyErbNumber(42)
        assertNotNull(submission)
        assertEquals("42", submission.text, ":1365-1366 先清框再追加那个数字")
        assertEquals(false, submission.mesSkip, ":1370 传 mesSkip = false")
        assertEquals(1, s.submissions.size, "这是原版唯一一处按键自己提交")
        assertEquals("", s.box.text, ":965 提交后清空")
        assertEquals(0, s.box.selectionLength, "框已空 ⇒ :1372 的 SelectAll 选了个空")
        assertEquals("42", s.history.snapshot().last())
    }

    @Test
    fun `热键脚本那一路在核心层运行中不提交但仍动框`() {
        val s = session()
        s.box.assign("框里原有内容")
        s.inProcess = true
        assertNull(s.onHotkeyErbNumber(7), ":1368 的 !console.IsInProcess 挡住提交")
        assertEquals("7", s.box.text, "清框与追加都在闸之外（:1365-1366）")
        assertEquals(1, s.box.selectionLength, ":1372 的 SelectAll 也在闸之外")
        assertTrue(s.submissions.isEmpty())
        assertTrue(s.history.snapshot().none { it == "7" }, "提交被挡 ⇒ 连历史都不写（:933 在 :924 的闸之内）")
    }

    @Test
    fun `提交口的两道守卫`() {
        val noConsole = session()
        noConsole.box.assign("abc")
        noConsole.consolePresent = false
        assertNull(noConsole.onEnterKeyPressed(), ":924 的前半个条件")
        assertTrue(noConsole.history.snapshot().none { it == "abc" }, "被挡时连历史都不写")
        assertEquals("abc", noConsole.box.text, "框内原样留着")

        val running = session()
        running.box.assign("abc")
        running.inProcess = true
        assertNull(running.onEnterKeyPressed(), ":924 的后半个条件（IsInProcess 见 EmueraConsole.cs:298）")
        assertEquals("abc", running.box.text)
    }

    @Test
    fun `Esc 提交带跳过标志`() {
        val s = session()
        s.box.assign("随便")
        val submission = s.onEscapeKeyPressed()
        assertNotNull(submission)
        assertEquals(true, submission.mesSkip, ":1421 传 mesSkip = true")
        assertEquals("随便", submission.text)
    }

    @Test
    fun `单键等待的前缀是按下标硬切`() {
        val s = session()
        s.waitingOnePhrase = true
        s.box.assign("abc")
        s.updateLastInput()
        assertEquals("abc", s.lastInputed, ":1305 记基线")
        s.box.assign("abcd")
        val submission = s.onEnterKeyPressed()
        assertNotNull(submission)
        assertEquals("d", submission.text, ":929 的 Remove(0, last_inputed.Length) 只切长度，不匹配内容")
        assertEquals("", s.lastInputed, ":930 复位基线")
    }

    @Test
    fun `基线比文本长时按原样抛`() {
        val s = session()
        s.waitingOnePhrase = true
        s.box.assign("abc")
        s.updateLastInput()
        s.box.assign("a")
        assertFailsWith<IndexOutOfBoundsException> { s.onEnterKeyPressed() }
    }

    @Test
    fun `空提交不进历史但照样交给核心层`() {
        val s = session()
        val submission = s.onEnterKeyPressed()
        assertNotNull(submission, ":924 之后没有空值判定 ⇒ 空回车照样走提交")
        assertEquals("", submission.text)
        assertEquals(1, s.submissions.size)
        assertTrue(s.history.snapshot().all { it.isEmpty() }, ":940-944 的空串早退")
        assertEquals(s.history.capacity, s.history.selectedInputs, "早退还绕过了 :966 的游标复位")
    }

    @Test
    fun `被核心层拒绝的输入也已经进过历史`() {
        val s = session()
        s.box.assign("不是数字")
        assertNotNull(s.onEnterKeyPressed())
        assertEquals("不是数字", s.history.snapshot().last(), ":933 在 :934 之前 ⇒ 核心层拒收也留在历史里")
    }

    @Test
    fun `打字即提交只在两种等待位成立`() {
        val s = session()
        s.box.assign("a")
        s.updateLastInput()
        s.box.appendText("b")
        assertNull(s.onTextChanged(), "既不是单键也不是任键 ⇒ :1315 的守卫直接返回")
        assertTrue(s.submissions.isEmpty())

        s.waitingOnePhrase = true
        val submission = s.onTextChanged()
        assertNotNull(submission, ":1330 那一句")
        assertEquals("b", submission.text, "基线是 a ⇒ 硬切后交出 b")
        assertEquals("", s.box.text)
        assertEquals(1, s.submissions.size)

        val anyKey = session()
        anyKey.waitAnyKey = true
        anyKey.box.assign("k")
        anyKey.updateLastInput()
        anyKey.box.appendText("y")
        val second = anyKey.onTextChanged()
        assertNotNull(second)
        assertEquals("", second.text, ":1327 先 Clear 再提交 ⇒ 交出去的是空串（官方行为）")
        assertEquals("", anyKey.lastInputed, ":1328 清空基线")
        assertTrue(anyKey.textBoxFlag, ":1331 开闸")
    }

    @Test
    fun `文本没变长只推平基线`() {
        val s = session()
        s.waitingOnePhrase = true
        s.box.assign("abcd")
        s.updateLastInput()
        s.box.assign("ab")
        assertNull(s.onTextChanged(), ":1319-1323 那一步")
        assertEquals("ab", s.lastInputed, "基线被推平成当前文本")
        assertTrue(s.submissions.isEmpty())
    }

    @Test
    fun `TextChanged 守卫链逐条`() {
        val s = session()
        s.waitingOnePhrase = true
        s.box.assign("xy")
        s.updateLastInput()
        s.inProcess = true
        s.box.appendText("z")
        assertNull(s.onTextChanged(), ":1315 的 IsInProcess")
        s.inProcess = false
        s.changedByMouse = true
        assertNull(s.onTextChanged(), ":1315 的 changeTextbyMouse")
        s.changedByMouse = false
        val submission = s.onTextChanged()
        assertNotNull(submission, "同一串文本，鼠标旗标撤掉后就走 :1330 提交")
        assertEquals("z", submission.text, "基线 xy ⇒ 硬切出 z")
        assertTrue(s.textBoxFlag, "提交后重入闸开回 :1331")
    }

    @Test
    fun `召回不提交只写框`() {
        val s = session()
        s.box.assign("第一条")
        assertNotNull(s.onEnterKeyPressed())
        s.box.assign("第二条")
        assertNotNull(s.onEnterKeyPressed())
        assertEquals(2, s.submissions.size)

        assertTrue(s.recallHistory(-1), ":1437 的 ↑")
        assertEquals("第二条", s.box.text, ":998-999")
        assertEquals(0, s.box.selectionStart, ":1000")
        assertEquals(3, s.box.selectionLength, ":1001 整行选中")
        assertEquals(2, s.submissions.size, "召回不动提交流水")

        s.inProcess = true
        assertFalse(s.recallHistory(-1), ":1435 的 !console.IsInProcess")
    }

    @Test
    fun `切串四元素与鼠标整条`() {
        val s = session()
        val pieces = s.splitPieces(Submission(text = "a\\nb\nc", mesSkip = false, inputsByMouse = false))
        assertEquals(listOf("a", "b", "c"), pieces.map { it.text }, ":1429 的四元素切分符（:146），含字面量反斜杠加 n")

        val byMouse = s.splitPieces(Submission(text = "a\\nb\nc", mesSkip = false, inputsByMouse = true))
        assertEquals(1, byMouse.size, ":1406-1409 鼠标改过的整串不切")
        assertEquals("a\\nb\nc", byMouse.first().text)

        val cr = s.splitPieces(Submission(text = "a\r\nb\rc", mesSkip = false, inputsByMouse = false))
        assertEquals(listOf("a", "b", "c"), cr.map { it.text }, "CRLF 与 CR 也在表里")

        val emptyTail = s.splitPieces(Submission(text = "a\n", mesSkip = false, inputsByMouse = false))
        assertEquals(listOf("a", ""), emptyTail.map { it.text }, "StringSplitOptions.None ⇒ 末尾空段保留")
    }

    @Test
    fun `反斜杠e 被剥掉并要求跳过`() {
        val s = session()
        val pieces = s.splitPieces(Submission(text = "a\\eb", mesSkip = false, inputsByMouse = false))
        assertEquals(1, pieces.size)
        assertEquals("ab", pieces.first().text, ":1437 的 Replace")
        assertTrue(pieces.first().mesSkipRequested, ":1438 把 MesSkip 置真")

        val plain = s.splitPieces(Submission(text = "aeb", mesSkip = false, inputsByMouse = false))
        assertFalse(plain.first().mesSkipRequested, "少一个反斜杠就不算")
    }

    @Test
    fun `单键输入截成第一字符`() {
        val s = session()
        s.waitingOnePhrase = true
        val pieces = s.splitPieces(Submission(text = "abc", mesSkip = false, inputsByMouse = false))
        assertEquals("a", pieces.first().text, ":1440-1443 的 Remove(1)")

        s.allowLongInputByMouse = true
        val fromMouse = s.splitPieces(Submission(text = "abc", mesSkip = false, inputsByMouse = true))
        assertEquals("abc", fromMouse.first().text, "鼠标长输入按配置放行（ConfigData.cs:104 默认 false）")
        val fromKeyboard = s.splitPieces(Submission(text = "abc", mesSkip = false, inputsByMouse = false))
        assertEquals("a", fromKeyboard.first().text, "开关开着但来源是键盘 ⇒ 照样截")

        s.waitingOnePhrase = false
        val notOne = s.splitPieces(Submission(text = "abc", mesSkip = false, inputsByMouse = false))
        assertEquals("abc", notOne.first().text, "非单键输入不截")
    }

    @Test
    fun `系统命令短路只看形状`() {
        val s = session()
        assertTrue(s.isSystemCommand(Submission(text = "@BLOAD", mesSkip = false, inputsByMouse = false)), ":1412 的三个条件")
        assertFalse(s.isSystemCommand(Submission(text = "@", mesSkip = false, inputsByMouse = false)), "长度必须大于 1")
        assertFalse(s.isSystemCommand(Submission(text = "x", mesSkip = false, inputsByMouse = false)), "不以 at 开头")
        s.waitingOnePhrase = true
        assertFalse(s.isSystemCommand(Submission(text = "@x", mesSkip = false, inputsByMouse = false)), "单键等待期不抢系统命令")
        s.waitingOnePhrase = false
        assertTrue(s.isSystemCommand(Submission(text = "@x", mesSkip = false, inputsByMouse = false)), "退出单键等待后同一串又算")
    }

    @Test
    fun `接受之后才复位文本框位置`() {
        val position = TextBoxPosition(800, 600, BoxGeom(0, 580, 800, 20))
        val s = InputSession(position = position)
        position.requestMove(100, 40, 300)
        position.applyChanges()
        assertEquals(BoxGeom(100, 540, 300, 20), position.applied)
        s.onInputAccepted()
        assertEquals(BoxGeom(0, 580, 800, 20), position.applied, "EmueraConsole.cs:1283-1286 只夹 Changed 态")
        assertEquals(PosState.UNCHANGED, position.state)

        position.requestMove(100, 40, 300)
        s.onInputAccepted()
        assertEquals(PosState.WATING_TO_CHANGE, position.state, "还没生效的请求不会被复位抹掉")
    }

    @Test
    fun `宏表与按键表同用零基槽号`() {
        val s = session(macro = "组二的第一格", fkey = 0, group = 2)
        s.applyMacro(MacroKeys.Decision.SwitchGroup(2))
        val d = s.onMacroKey(MacroKeys.VK_F1, MacroKeys.Modifier.NONE)
        assertIs<MacroKeys.Decision.Prefill>(d)
        assertEquals("组二的第一格", d.text, "GetMacro(0, 2)（KeyMacro.cs:150-153）")
        assertEquals("组二的第一格", s.box.text)
        assertEquals(6, s.box.selectionStart, "插入点在末尾（:510）")
        assertTrue(s.submissions.isEmpty())
    }
}
