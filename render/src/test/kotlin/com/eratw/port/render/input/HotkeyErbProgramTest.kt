package com.eratw.port.render.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [HotkeyErbProgram] 与 [HotkeyStates] —— `HOTKEY.ERB` 解释器的逐行移植。
 *
 * ## 期望值出处（全部 `MinorShift.Emuera.UI.Game/HotkeyState.cs`）
 * - 操作码表：`Eval` :11-17（NUMBER 0 / KEYCOMPARE 1 / STATECOMPARE 2 / STATEGET 3）与
 *   `Command` :19-24（RETURN 0 / GOTOIFNOT 1）⇒ 同一个数字在命令位与求值位含义不同，
 *   两个 switch（:251-270 与 :206-231）各读各的。
 * - 装载：行数下限 :87-90、首行逐字 :91-94、TrimStart/截分号/TrimEnd/跳空行 :100-110、
 *   `IF ` :111-122、`RETURN ` :123-132、`SIF ` :135-148（:147 的 `continue`）、
 *   `ENDIF` :149-154、其它行 `throw new Exception("invalid line")` :151、尾部 flag 补丁 :156-161。
 * - 求值串：`KEY == KEYS:` 用 `Enum.TryParse`（:52-62，失败 ⇒ 错误码 1 且**不**落字节码），
 *   `STATE:` 与裸数用 `int.Parse`（:63-79、:80-82，失败 ⇒ 抛异常）。
 * - 解释：三道前置门槛 :235-246、`InterpreterEval` :204-231、
 *   跳转后的夹取 `if (i < 0 || i + 1 > bytecode.Count) throw` :259-262。
 * - 开关：`Toggle` :166-202，六句 `MessageBox` 原文 :171/:177/:183/:189/:196/:200。
 * - 状态数组：声明 :31、`HotkeyStateInit` :35-39、`HotkeyStateSet` :41-48（未初始化即抛 :45）。
 *   函数壳 `FunctionMethodCreator.cs:8679-8706`（HOTKEY_STATE）与 :8708-8730（INIT），
 *   注册表 `:9306-9307`，两者都 `return 0L`。
 * - 基准读数：`fixtures/golden/baseline/pc-golden-root.txt:526-527` =
 *   `HOTKEY_STATE_INIT=0` / `HOTKEY_STATE=0`，源见
 *   `fixtures/golden/GOLDEN_09_MISC.ERB:66-67` 的 `HOTKEY_STATE_INIT(2)` 与 `HOTKEY_STATE(1, 0)`
 *   ⇒ 顺序必须是「先 INIT 再 SET」，且写形态的第 1 格值 0 落在容量 2 的表里。
 *
 * ## 钉住的两个官方怪癖（ADR-005，不修）
 * 1. **失败解析不回收字节码**（怪癖 1）：`Parse` 只追加，重试在半截之上继续。
 * 2. **跳转界的夹取比循环条件更严**（:259-262）：`IF … ENDIF` 后面若不跟一条兜底语句，
 *    条件不成立时跳到的正是表尾，`i + 1 > Count` 立刻成立 ⇒ 抛 `something is wrong`，
 *    而不是安静地返回 -1。见 `表尾跳转按原样抛`。
 */
class HotkeyErbProgramTest {

    private val header = HotkeyErbProgram.HEADER

    private fun lines(vararg body: String) = listOf(header) + body.toList()

    /** 装载 + 启用（只走 `Toggle` 一次，避免怪癖 1 把字节码写两遍）。 */
    private fun ready(body: List<String>, states: HotkeyStates = HotkeyStates().apply { init(4L) }): Pair<HotkeyErbProgram, HotkeyStates> {
        val program = HotkeyErbProgram(states)
        val result = program.toggle(fileExists = true, lines = body)
        assertTrue(result.enabled, "Toggle 后应启用：${result.message}")
        return program to states
    }

    @Test
    fun `操作码常量与两个枚举同值`() {
        assertEquals(0, HotkeyErbProgram.EVAL_NUMBER, "Eval.NUMBER（:13）")
        assertEquals(1, HotkeyErbProgram.EVAL_KEYCOMPARE, ":14")
        assertEquals(2, HotkeyErbProgram.EVAL_STATECOMPARE, ":15")
        assertEquals(3, HotkeyErbProgram.EVAL_STATEGET, ":16")
        assertEquals(0, HotkeyErbProgram.CMD_RETURN, "Command.RETURN（:21）")
        assertEquals(1, HotkeyErbProgram.CMD_GOTOIFNOT, ":22")
        assertEquals(777, HotkeyErbProgram.PLACEHOLDER, ":116、:141 的字面量")
        assertEquals(-1, HotkeyErbProgram.NO_MATCH, ":237、:272 的 -1")
        assertEquals("@HOTKEY(KEY)", HotkeyErbProgram.HEADER, ":91 逐字比较的首行")
    }

    @Test
    fun `行数与首行两道门槛都给错误码`() {
        val program = HotkeyErbProgram()
        assertEquals(1, program.parse(emptyList()), "lines.Length 小于 2（:87-90）")
        assertEquals(1, program.parse(listOf(header)), "只有一行首行也是不足两行")
        assertEquals(1, program.parse(listOf("@hotkey(KEY)", "RETURN 1")), "首行区分大小写（:91）")
        assertEquals(emptyList(), program.bytecodeSnapshot(), "两道门槛都在任何追加之前")
    }

    @Test
    fun `裸数分支的字节码形状`() {
        val program = HotkeyErbProgram()
        assertEquals(0, program.parse(lines("RETURN 5")))
        assertEquals(listOf(0, 0, 5), program.bytecodeSnapshot(), "RETURN(:126) + NUMBER(:80) + 值(:81)")
        val (running, _) = ready(lines("RETURN 5"))
        assertEquals(5, running.keyToNumber(0), "裸数分支与按下的键无关（:209）")
    }

    @Test
    fun `IF与ENDIF 的占位补丁落在下一条语句之前`() {
        val program = HotkeyErbProgram()
        assertEquals(0, program.parse(lines("IF KEY == KEYS:F1", "RETURN 1", "ENDIF")))
        // :114-116 先落 GOTOIFNOT、压入当时的 Count、再落占位 ⇒ 占位夹在操作码与求值体之间
        assertEquals(7, program.bytecodeSnapshot().size)
        assertEquals(listOf(1, 7, 1, 112), program.bytecodeSnapshot().take(4))
        assertEquals(listOf(0, 0, 1), program.bytecodeSnapshot().drop(4), "ENDIF 之后才轮到 RETURN")
        assertEquals(7, program.bytecodeSnapshot()[1], ":154 把占位改成补丁当时的 Count")
    }

    @Test
    fun `两条IF与一条兜底RETURN`() {
        val body = lines("IF KEY == KEYS:F1", "RETURN 1", "ENDIF", "IF KEY == KEYS:F2", "RETURN 2", "ENDIF", "RETURN -1")
        val program = HotkeyErbProgram()
        assertEquals(0, program.parse(body))
        assertEquals(listOf(1, 7, 1, 112, 0, 0, 1, 1, 14, 1, 113, 0, 0, 2, 0, 0, -1), program.bytecodeSnapshot())
        val (running, _) = ready(body)
        assertEquals(1, running.keyToNumber(112), "第一条比较成立 ⇒ 不跳，直接落 RETURN 1")
        assertEquals(2, running.keyToNumber(113), "第一条跳到下标 7，第二条成立 ⇒ RETURN 2")
        assertEquals(-1, running.keyToNumber(114), "两条都不成立 ⇒ 一路跳到兜底的 RETURN -1")
    }

    @Test
    fun `表尾跳转按原样抛`() {
        // 去掉兜底 RETURN 的同一种写法：条件不成立时跳到 Count 处，:259-262 的夹判定为越界
        val body = lines("IF KEY == KEYS:F1", "RETURN 7", "ENDIF")
        val (running, _) = ready(body)
        assertEquals(7, running.keyToNumber(112))
        val e = assertFailsWith<IllegalStateException> { running.keyToNumber(113) }
        assertEquals("something is wrong", e.message, "官方陷阱 2，原样复刻（:261）")
    }

    @Test
    fun `SIF 的补丁推迟到下一条语句处理完`() {
        val program = HotkeyErbProgram()
        assertEquals(0, program.parse(lines("SIF KEY == KEYS:F1", "RETURN 3")))
        assertEquals(listOf(1, 7, 1, 112, 0, 0, 3), program.bytecodeSnapshot(), ":147 的 continue ⇒ flag 留着到 :156-161")
    }

    @Test
    fun `SIF 后紧跟IF 弹到的是IF 自己的占位`() {
        val program = HotkeyErbProgram()
        assertEquals(0, program.parse(lines("SIF KEY == KEYS:F1", "IF KEY == KEYS:F2", "RETURN 9")))
        assertEquals(listOf(1, 777, 1, 112), program.bytecodeSnapshot().take(4), "栈是 LIFO ⇒ SIF 的占位没被动过")
        assertEquals(1, program.bytecodeSnapshot()[4], "IF 的 GOTOIFNOT")
        assertEquals(8, program.bytecodeSnapshot()[5], "IF 的占位被 flag 补丁改成当时的 Count")
        assertEquals(11, program.bytecodeSnapshot().size)
    }

    @Test
    fun `多一个ENDIF 对空栈弹`() {
        val program = HotkeyErbProgram()
        // :153 的 Pop 在空栈上 ⇒ C# 抛 InvalidOperationException，本层抛下标异常，都不静默
        assertFailsWith<IndexOutOfBoundsException> {
            program.parse(lines("IF KEY == KEYS:F1", "ENDIF", "ENDIF"))
        }
    }

    @Test
    fun `非法行抛原文异常`() {
        val program = HotkeyErbProgram()
        val e = assertFailsWith<IllegalStateException> { program.parse(lines("PRINTL hello")) }
        assertEquals("invalid line", e.message, ":151")
    }

    @Test
    fun `失败解析的半截留在表里`() {
        val program = HotkeyErbProgram()
        // IF 先落 GOTOIFNOT + 占位，再解析求值串 ⇒ 失败时那两格已经写进去了（怪癖 1）
        assertEquals(1, program.parse(lines("IF KEY == KEYS:NoSuchKey5566", "ENDIF")))
        assertEquals(listOf(1, HotkeyErbProgram.PLACEHOLDER), program.bytecodeSnapshot())
        assertFalse(program.availableHotkeyFile, "失败 ⇒ availableHotkeyFile 保持 false（:192 走不到）")
        assertEquals(0, program.parse(lines("RETURN 5")))
        assertEquals(listOf(1, 777, 0, 0, 5), program.bytecodeSnapshot(), "重试是在半截之上继续追加")
    }

    @Test
    fun `注释与空行与行首空白`() {
        val program = HotkeyErbProgram()
        val body = listOf("   RETURN 1 ; 这是注释", "", "  ", "\t;整行都是注释", "RETURN 2")
        assertEquals(0, program.parse(listOf(header) + body))
        assertEquals(listOf(0, 0, 1, 0, 0, 2), program.bytecodeSnapshot(), ":100-110 的裁剪与跳过")
    }

    @Test
    fun `STATE 的比较与取值`() {
        val body = lines("IF STATE:2 == 7", "RETURN 42", "ENDIF", "RETURN STATE:2")
        val shape = HotkeyErbProgram()
        assertEquals(0, shape.parse(body))
        assertEquals(listOf(1, 8, 2, 2, 7, 0, 0, 42, 0, 3, 2), shape.bytecodeSnapshot(), "STATECOMPARE(:71-73) 与 STATEGET(:76-77)")
        val (running, states) = ready(body)
        assertEquals(0, running.keyToNumber(112), "初值零不等于七 ⇒ 跳到兜底的 STATEGET 取回零")
        states.set(2L, 7L)
        assertEquals(42, running.keyToNumber(112), ":221 的比较成立 ⇒ 落 RETURN 42")
        states.set(2L, 9L)
        assertEquals(9, running.keyToNumber(112), ":226 把状态值本身当号码返回")
    }

    @Test
    fun `长整状态值在取出处按整型截断`() {
        val (running, states) = ready(lines("RETURN STATE:1"))
        states.set(1L, 0x1_0000_0020L)
        assertEquals(0x20, running.keyToNumber(0), "(int)state[num]（:226）⇒ 只留低 32 位")
        assertEquals(0x1_0000_0020L, states[1], "数组本体仍是 nint 宽度")
    }

    @Test
    fun `三道门槛各自都能挡住解释器`() {
        val states = HotkeyStates().apply { init(2L) }
        val program = HotkeyErbProgram(states)
        assertEquals(-1, program.keyToNumber(112), "未 Toggle ⇒ enabled 为假（:235-238）")
        assertEquals(0, program.parse(lines("RETURN 5")))
        assertEquals(-1, program.keyToNumber(112), "光解析不启用 ⇒ 仍是第一道门槛挡下")
        assertTrue(program.toggle(fileExists = true, lines = lines("RETURN 5")).enabled)
        assertEquals(5, program.keyToNumber(112))

        // 第二道门槛：文件解析成功且已启用，但 HOTKEY_STATE_INIT 没调过 ⇒ 一律不理（:239-242）
        val noInit = HotkeyErbProgram()
        val result = noInit.toggle(fileExists = true, lines = lines("RETURN 5"))
        assertTrue(result.enabled)
        assertTrue(noInit.availableHotkeyFile)
        assertFalse(noInit.availableStateArray, ":27 的 availableStateArray 仍未置")
        assertEquals(HotkeyErbProgram.MSG_ON_NO_INIT, result.message, ":200 那句提示")
        assertEquals(-1, noInit.keyToNumber(112))

        // 第三道门槛：Toggle 里解析失败根本走不到 enabled ⇒ availableHotkeyFile 的判定是死支（:243-246）
        val broken = HotkeyErbProgram(HotkeyStates().apply { init(1L) })
        assertEquals(-1, broken.keyToNumber(112))
        assertFalse(broken.toggle(fileExists = true, lines = lines("IF KEY == KEYS:NoSuchKey5566")).enabled)
        assertEquals(-1, broken.keyToNumber(112))
    }

    @Test
    fun `开关的六句提示原文`() {
        val fresh = HotkeyErbProgram()
        val missing = fresh.toggle(fileExists = false, lines = lines("RETURN 1"))
        assertEquals(HotkeyErbProgram.MSG_NOT_FOUND, missing.message, ":183")
        assertFalse(missing.enabled, ":181-185 不置 enabled")

        val broken = HotkeyErbProgram()
        val failed = broken.toggle(fileExists = true, lines = listOf("@HOTKEY(KEY)"))
        assertEquals(HotkeyErbProgram.MSG_PARSE_FAILED, failed.message, ":189")
        assertEquals(1, failed.parseErrorCode, "原版只判非零（:187-191）")
        assertFalse(failed.enabled)
        assertFalse(broken.availableHotkeyFile)

        val ok = HotkeyErbProgram(HotkeyStates().apply { init(1L) })
        val on = ok.toggle(fileExists = true, lines = lines("RETURN 1"))
        assertEquals(HotkeyErbProgram.MSG_ON, on.message, ":196：状态数组已就绪")
        assertTrue(on.enabled)

        val off = ok.toggle(fileExists = true, lines = lines("RETURN 1"))
        assertEquals(HotkeyErbProgram.MSG_OFF, off.message, ":171：已启用时先关，不重读文件")
        assertFalse(off.enabled)

        val again = ok.toggle(fileExists = false, lines = emptyList())
        assertEquals(HotkeyErbProgram.MSG_ON, again.message, ":177：已有可用文件 ⇒ 直接开，连文件都不看")
        assertTrue(again.enabled)
    }

    @Test
    fun `键名解析只认有出处的子集`() {
        val keys = KeysNames.DEFAULT
        assertEquals(112, keys.resolve("F1"), "Keys.F1 = 0x70")
        assertEquals(123, keys.resolve("F12"))
        assertEquals(0x41, keys.resolve("A"))
        assertEquals(0x30, keys.resolve("D0"), "主键盘数字名")
        assertEquals(0x60, keys.resolve("NumPad0"), "小键盘数字名")
        assertEquals(0x20000 or 112, keys.resolve("Control,F1"), "flags 枚举的逗号分隔名（:55）")
        assertEquals(112, keys.resolve("112"), "数字串本身就是合法的枚举文本")
        assertEquals(null, keys.resolve("f1"), "Enum.TryParse 该重载默认区分大小写")
        assertEquals(null, keys.resolve("Shift+Up"), "加号不是分隔符")
        assertEquals(null, keys.resolve("MouseButtons"), "子集外一律判失败 ⇒ 错误码 1，不猜值")
        assertEquals(null, keys.resolve(""), "空串不解析")
    }

    @Test
    fun `修饰键与功能键的KeyData是整值比较`() {
        val (running, _) = ready(lines("RETURN KEY == KEYS:Shift, F1"))
        assertEquals(1, running.keyToNumber(0x10000 or 112), ":213 的 key == num")
        assertEquals(0, running.keyToNumber(112), "只按 F1 不算 Shift+F1 ⇒ 比较对象是 e.KeyData（:247）")
    }

    @Test
    fun `基准那两行要求先INIT再SET`() {
        val uninitialized = HotkeyStates()
        assertFalse(uninitialized.initialized, "availableStateArray 初值 false（:27）")
        val e = assertFailsWith<IllegalStateException> { uninitialized.set(1L, 0L) }
        assertEquals("use HOTKEY_STATE_INIT before using HOTKEY_STATE", e.message, ":45 原文")

        val states = HotkeyStates()
        states.init(2L)
        assertTrue(states.initialized, ":38")
        assertEquals(2, states.size)
        states.set(1L, 0L)
        assertEquals(0L, states[1], "GOLDEN_09_MISC.ERB:67 写的是容量 2 表的第 1 格、值 0")
        assertEquals(0L, states[0], "另一格仍是默认零 ⇒ 基准那两行都取到零")
    }

    @Test
    fun `状态数组没有下标检查`() {
        val states = HotkeyStates().apply { init(2L) }
        assertFailsWith<IndexOutOfBoundsException> { states.set(2L, 1L) }
        assertFailsWith<IndexOutOfBoundsException> { states.get(-1) }
    }

    @Test
    fun `比较用整宽取值用截断`() {
        val (running, states) = ready(lines("IF STATE:0 == 7", "RETURN 1", "ENDIF", "RETURN 0"))
        states.set(0L, 7L)
        assertEquals(1, running.keyToNumber(0))
        states.set(0L, 0x1_0000_0007L)
        assertEquals(0, running.keyToNumber(0), ":221 比的是 nint 全宽 ⇒ 低 32 位相同也不算相等")
    }

    @Test
    fun `字节码快照是副本`() {
        val program = HotkeyErbProgram()
        assertEquals(0, program.parse(lines("RETURN 5")))
        val view = program.bytecodeSnapshot()
        assertEquals(0, program.parse(lines("RETURN 6")), "怪癖 1：再解析只追加")
        assertEquals(3, view.size, "先前取到的快照不被后续追加改写")
        assertEquals(6, program.bytecodeSnapshot().size)
    }
}
