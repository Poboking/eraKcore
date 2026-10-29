package com.eratw.port.render.input

/**
 * `HOTKEY.ERB` 解释器 + `HOTKEY_STATE` 状态数组 ——
 * `MinorShift.Emuera.UI.Game/HotkeyState.cs` 的逐行移植（该类只有 274 行，本文件把它整个吃掉）。
 *
 * 这份东西与 [KeyMacroTable]/[MacroKeys] 是**两套互不相干**的机制，别混：
 * - 键名走 `ProcessCmdKey` 的 F1-F12 宏（`MainWindow.cs:496-512`）⇒ 预填、**不提交**；
 * - 本类走 `richTextBox1_KeyDown` 的 `hotkeyState.keyToNumberRunInterpreter(e)`
 *   （`MainWindow.cs:1362-1374`）⇒ 命中即「清框 + 写入那个数字 + 吞键 + **提交回车** + 全选」，
 *   提交条件是 `!console.IsInProcess`（:1368-1371）。
 * 开关是 Ctrl+D（`MainWindow.cs:479-484` 的 `case Keys.D` + Control ⇒ `hotkeyState.Toggle()`）。
 *
 * ## 文件与字节码格式（逐条行号）
 * - 文件名固定 `ExeDir + "HOTKEY.ERB"`（`HotkeyState.cs:180`），读取编码由
 *   `EncodingHandler.DetectEncoding` 嗅探（:186），**属宿主**，本类只吃 `List<String>`。
 * - 首行必须逐字等于 `@HOTKEY(KEY)`（:91-94），且总行数至少 2（:87-90）⇒ 否则错误码 1。
 * - 每行先 `TrimStart`、在第一个 `;` 处截断、再 `TrimEnd`，空行跳过（:100-110）。
 * - 行首四类：`IF `（:111-122）、`RETURN `（:123-132）、`SIF `（:135-148）、`ENDIF`（:149-155）；
 *   其它文本 `throw new Exception("invalid line")`（:151）。
 * - 求值串三类：`KEY == KEYS:`（:52-62）、`STATE:<n> == <v>` / `STATE:<n>`（:63-79）、
 *   裸整数（:80-82）。
 * - 两个枚举各自从 0 编号（`Eval` :11-17、`Command` :19-24），**值域重叠**：
 *   命令位只有 `0 = RETURN`、`1 = GOTOIFNOT`，求值位是
 *   `0 = NUMBER`、`1 = KEYCOMPARE`、`2 = STATECOMPARE`、`3 = STATEGET`。
 *   同一个字节在不同位置含义不同，所以解释器必须按「命令位读法 / 求值位读法」分开解码
 *   （:206-231 与 :251-270 两个 switch 各自只认自己那套）。
 * - `IF`/`SIF` 先落 `1`，再压栈「占位槽下标」并写占位值 777（:114-116、:139-141）；
 *   `ENDIF` 弹栈把占位改成当时的 `bytecode.Count`（:153-154）。
 * - `SIF` 是「单行 IF」：置 `flag` 后 `continue`，补丁发生在**下一条 IF/RETURN 处理完时**
 *   （:156-161）。栈是 LIFO，所以 `SIF` 后紧跟 `IF` 时先弹到的是那条 `IF` 自己的占位，
 *   `SIF` 的占位留给后面的 `ENDIF`；写成 `SIF` + `RETURN` 时弹到的才是 `SIF` 的占位。
 *   多一个 `ENDIF` 会对空栈 `Pop()` ⇒ C# 抛 `InvalidOperationException`（:153），
 *   本层同样让它抛（不静默）。
 *
 * ## 两个官方怪癖（原样复刻，ADR-005）
 * 1. **解析失败不回收字节码**：`Parse` 是往 `bytecode` 里**追加**的（:59-60、:114-116 等），
 *    失败那次已经写进去的半截留在表里（`availableHotkeyFile` 保持 false，所以此时不会执行；
 *    但下次 `Toggle` 重试是在半截之上继续追加）。本类照抄这个「累加」语义。
 * 2. **`KEY ==` 用 TryParse、`STATE:` 与裸数用 int.Parse**（:55-58 对 :72-77、:81）：
 *    前者解析失败返回错误码 1（弹「HOTKEY.ERB parsing failed.」），
 *    后者解析失败直接抛 `FormatException`（崩溃路径），两支待遇不同。
 * 3. **末尾 `ENDIF` 的落点必然抛**（2026-10-21 审计登记，语料无 HOTKEY.ERB 可比）：
 *    `ENDIF` 把占位格写成 `bytecode.Count`（:153-154）= 程序末尾，而跳转后的夹是
 *    `i < 0 || i + 1 > bytecode.Count`（:259-261），`i == Count` 恰好命中 ⇒
 *    `throw new Exception("something is wrong")`（:261）。于是**只要一个键没被任何
 *    `IF` 块接住、且最后一个块以 `ENDIF` 收尾（= 所有常规写法），解释器就抛**，
 *    而调用点在输入框的 KeyDown 里（`MainWindow.cs:1362`，`Program.cs:193` 的
 *    `Application.Run` 之前没有注册任何 `ThreadException` 处理器）⇒ PC 上是
 *    WinForms 自带的未处理异常对话框（可「继续」），命中那一路不受影响。
 *    本层照抛（ADR-005），**不夹、不吞、不改返回 -1**。
 *    TODO(verify)：安卓侧没有等价对话框，一次抛 = 输入路径炸给宿主；
 *    卡点＝没有 PC 截图能证明「按任意非热键键都会弹一次错误框」，需要真机复核后
 *    由组长决定是否按 ADR-005 的「官方 bug」通道在绑定层兜住。
 *
 * ## 键名解析（唯一需要外部知识的一步）
 * 原版 `Enum.TryParse<Keys>(line, out var result)`（:55，该重载默认**区分大小写**）吃的是
 * `System.Windows.Forms.Keys` 的名字或数字串；`Keys` 是 flags 枚举 ⇒「Shift, F1」这类
 * 逗号分隔名按位或成一个值。比较对象是 `e.KeyData`（:247），即「修饰键位 | 键码」。
 * 本层不引 WinForms：[KeysNames] 是**可注入**解析器，[KeysNames.DEFAULT] 只覆盖有出处的
 * 常用子集，子集外的名字一律判解析失败（= 错误码 1，不猜值）。
 * TODO(verify)：`Keys` 全量名表（多媒体键、IME 处理键、鼠标名、`KeysConverter` 的容错拼法）
 * 与当前区域设置的列表分隔符都没有 PC 可比读数；卡点＝本机没有 WinForms，反编译里只有
 * `Enum.TryParse` 这一个调用点。真机侧应由 Android 绑定层提供完整映射后替换默认实现。
 */
class HotkeyErbProgram(
    private val states: HotkeyStates = HotkeyStates(),
) {

    /** 与 C# `List<int> bytecode = new List<int>(512)` 同构的累加缓冲（:33）。 */
    private val bytecode = ArrayList<Int>(512)

    /** 是否已启用（`enabled` :25；由 [toggle] 翻转）。 */
    var enabled: Boolean = false
        private set

    /** 是否已成功解析过文件（`availableHotkeyFile` :29）。 */
    var availableHotkeyFile: Boolean = false
        private set

    /** 状态数组是否已 `HOTKEY_STATE_INIT` 过（`availableStateArray` :27）。 */
    val availableStateArray: Boolean
        get() = states.initialized

    /** 状态数组本体（`HOTKEY_STATE` / `HOTKEY_STATE_INIT` 两个函数壳要写它）。 */
    val hotkeyStates: HotkeyStates
        get() = states

    /** 当前字节码读数（只给单测与绑定层看；语义见类文档的操作码表）。 */
    fun bytecodeSnapshot(): List<Int> = bytecode.toList()

    /**
     * `Parse`（:85-164）。返回 0 = 成功，非 0 = 错误码（原版只有 1 这一种返回值，
     * `Toggle` 只判 `!= 0`，:187-191）。**不**清空既有字节码（怪癖 1）。
     */
    fun parse(lines: List<String>, keys: KeysNames = KeysNames.DEFAULT): Int {
        if (lines.size < 2) {
            return 1
        }
        if (lines[0] != HEADER) {
            return 1
        }
        var pendingSingleLineIf = false
        val stack = ArrayList<Int>()
        for (index in 1 until lines.size) {
            var line = lines[index].trimStart()
            val commentAt = line.indexOf(';')
            if (commentAt != -1) {
                line = line.substring(0, commentAt)
            }
            line = line.trimEnd()
            if (line.isEmpty()) {
                continue
            }
            if (line.startsWith("IF ")) {
                // :113-116
                bytecode.add(CMD_GOTOIFNOT)
                stack.add(bytecode.size)
                bytecode.add(PLACEHOLDER)
                val code = parseEval(line.substring(3), keys)
                if (code != 0) {
                    return code
                }
            } else if (line.startsWith("RETURN ")) {
                // :125-127
                bytecode.add(CMD_RETURN)
                val code = parseEval(line.substring(7), keys)
                if (code != 0) {
                    return code
                }
            } else if (line.startsWith("SIF ")) {
                // :137-147（注意 :147 的 continue：本轮不走下面的 flag 补丁）
                pendingSingleLineIf = true
                bytecode.add(CMD_GOTOIFNOT)
                stack.add(bytecode.size)
                bytecode.add(PLACEHOLDER)
                val code = parseEval(line.substring(4), keys)
                if (code != 0) {
                    return code
                }
                continue
            } else {
                if (line != "ENDIF") {
                    throw IllegalStateException("invalid line")
                }
                bytecode[stack.removeAt(stack.size - 1)] = bytecode.size
            }
            if (pendingSingleLineIf) {
                bytecode[stack.removeAt(stack.size - 1)] = bytecode.size
                pendingSingleLineIf = false
            }
        }
        return 0
    }

    /**
     * `ParseEval`（:50-83）：0 = 成功、1 = 失败（失败时**不**落字节码，:55-58）。
     * `STATE:` 与裸数走 [String.toInt]（原版 `int.Parse`，:72-77、:81）⇒ 非法数字在这里抛
     * `NumberFormatException`，与 C# 的 `FormatException` 同性质（崩溃路径，不转错误码）。
     */
    private fun parseEval(line: String, keys: KeysNames): Int {
        if (line.startsWith("KEY == KEYS:")) {
            val value = keys.resolve(line.substring("KEY == KEYS:".length)) ?: return 1
            bytecode.add(EVAL_KEYCOMPARE)
            bytecode.add(value)
            return 0
        }
        if (line.startsWith("STATE:")) {
            val body = line.substring("STATE:".length)
            val splitAt = body.indexOf(" == ")
            if (splitAt != -1) {
                bytecode.add(EVAL_STATECOMPARE)
                bytecode.add(body.substring(0, splitAt).toInt())
                bytecode.add(body.substring(splitAt + " == ".length).toInt())
                return 0
            }
            bytecode.add(EVAL_STATEGET)
            bytecode.add(body.toInt())
            return 0
        }
        bytecode.add(EVAL_NUMBER)
        bytecode.add(line.toInt())
        return 0
    }

    /**
     * `keyToNumberRunInterpreter`（:233-273）。三道前置门槛任一不满足都返回 -1
     * （未启用 :235-238、状态数组未初始化 :239-242、文件未成功解析 :243-246）——
     * 注意第二道：`HOTKEY_STATE_INIT` 没调过 ⇒ 即使宏文件解析成功、即使 Toggle 提示了
     * 「hotkeys ON, HOTKEY_STATE_INIT not called.」（:200），解释器也一律不响应。
     *
     * @param keyData `KeyEventArgs.KeyData` 的整值（:247），即「修饰键位 | 键码」
     * @return 命中的数字，或 [NO_MATCH] 表示本次按键不归 HOTKEY.ERB 管
     */
    fun keyToNumber(keyData: Int): Int {
        if (!enabled || !states.initialized || !availableHotkeyFile) {
            return NO_MATCH
        }
        var i = 0
        while (i < bytecode.size) {
            val command = bytecode[i]
            i += 1
            when (command) {
                CMD_GOTOIFNOT -> {
                    val target = bytecode[i]
                    i += 1
                    val evaluated = interpreterEval(i, keyData)
                    i = evaluated.next
                    if (evaluated.value == 0) {
                        i = target
                        // :259-262 逐字照抄。这条夹的准确读法是「跳到的下标必须仍在数组内」
                        // （`i + 1 > Count` 即 `i >= Count`），而 `ENDIF` 写回的补丁值正是
                        // `bytecode.Count`（:154），所以**跳到程序末尾必然越界抛异常** ——
                        // 见类文档的官方怪癖 3。原先这里注成「只剩一字节也算越界」是误读：
                        // `i == Count - 1` 时条件是假的（不抛），只是那一格是操作数不是命令，
                        // 下一轮照样在取指令时炸。
                        if (i < 0 || i + 1 > bytecode.size) {
                            throw IllegalStateException("something is wrong")
                        }
                    }
                }
                CMD_RETURN -> {
                    val evaluated = interpreterEval(i, keyData)
                    return evaluated.value
                }
                else -> throw IllegalStateException("something is wrong")
            }
        }
        return NO_MATCH
    }

    /** `InterpreterEval`（:204-231）：Kotlin 没有 `ref int`，用「值 + 新游标」返回。 */
    private fun interpreterEval(from: Int, keyData: Int): Evaluated {
        var cursor = from
        return when (bytecode[cursor++]) {
            EVAL_NUMBER -> Evaluated(bytecode[cursor], cursor + 1)
            EVAL_KEYCOMPARE -> {
                val want = bytecode[cursor]
                Evaluated(if (keyData == want) 1 else 0, cursor + 1)
            }
            EVAL_STATECOMPARE -> {
                val index = bytecode[cursor]
                val want = bytecode[cursor + 1]
                Evaluated(if (states[index] == want.toLong()) 1 else 0, cursor + 2)
            }
            EVAL_STATEGET -> {
                val index = bytecode[cursor]
                Evaluated(states[index].toInt(), cursor + 1)
            }
            else -> throw IllegalStateException("something is wrong")
        }
    }

    /**
     * Ctrl+D 开关（`Toggle` :166-202）。文件是否可读、行内容由调用方给
     * （原版 `File.Exists` + `File.ReadAllLines`，:181-186 属宿主 I/O）。
     *
     * @param fileExists `File.Exists(ExeDir + "HOTKEY.ERB")`（:181）
     * @param lines 文件行；`fileExists` 为 false 时忽略
     * @return 该弹的提示文本（原版 `MessageBox.Show`，逐字照抄）与开关后的状态
     */
    fun toggle(fileExists: Boolean, lines: List<String>, keys: KeysNames = KeysNames.DEFAULT): HotkeyToggleResult {
        if (enabled) {
            enabled = false
            return HotkeyToggleResult(false, MSG_OFF, null)
        }
        if (availableHotkeyFile) {
            enabled = true
            return HotkeyToggleResult(true, MSG_ON, null)
        }
        if (!fileExists) {
            return HotkeyToggleResult(false, MSG_NOT_FOUND, null)
        }
        val code = parse(lines, keys)
        if (code != 0) {
            return HotkeyToggleResult(false, MSG_PARSE_FAILED, code)
        }
        availableHotkeyFile = true
        enabled = true
        return if (states.initialized) {
            HotkeyToggleResult(true, MSG_ON, null)
        } else {
            HotkeyToggleResult(true, MSG_ON_NO_INIT, null)
        }
    }

    /** 一次求值的返回值与推进后的游标（对应 C# 的 `ref int i`）。 */
    private data class Evaluated(val value: Int, val next: Int)

    companion object {
        /** 首行必须逐字相等（:91）。 */
        const val HEADER: String = "@HOTKEY(KEY)"

        /** 占位跳转值（:116、:141 的 777 字面量）。 */
        const val PLACEHOLDER: Int = 777

        /** 未命中（:237、:241、:245、:272 的 -1）。 */
        const val NO_MATCH: Int = -1

        /** `Command.RETURN`（:21）。 */
        const val CMD_RETURN: Int = 0

        /** `Command.GOTOIFNOT`（:22）。 */
        const val CMD_GOTOIFNOT: Int = 1

        /** `Eval.NUMBER`（:13）。 */
        const val EVAL_NUMBER: Int = 0

        /** `Eval.KEYCOMPARE`（:14）。 */
        const val EVAL_KEYCOMPARE: Int = 1

        /** `Eval.STATECOMPARE`（:15）。 */
        const val EVAL_STATECOMPARE: Int = 2

        /** `Eval.STATEGET`（:16）。 */
        const val EVAL_STATEGET: Int = 3

        /** `MessageBox` 原文（:171、:177、:183、:189、:196、:200）。 */
        const val MSG_OFF: String = "hotkeys OFF"
        const val MSG_ON: String = "hotkeys ON"
        const val MSG_NOT_FOUND: String = "HOTKEY.ERB not found."
        const val MSG_PARSE_FAILED: String = "HOTKEY.ERB parsing failed."
        const val MSG_ON_NO_INIT: String = "hotkeys ON, HOTKEY_STATE_INIT not called."
    }
}

/**
 * `Toggle` 的结论：当前是否启用、该弹哪句原文、解析错误码（原版不区分失败原因，:187-191）。
 */
data class HotkeyToggleResult(
    val enabled: Boolean,
    val message: String,
    val parseErrorCode: Int?,
)

/**
 * `HOTKEY_STATE` 的状态数组（`HotkeyState.cs:31-48`）。
 *
 * - `HOTKEY_STATE_INIT(size)` = 分配 `nint[size]` 并置可用（:35-39，函数壳
 *   `FunctionMethodCreator.cs:8708-8730`，注册表 `FunctionMethodCreator.cs:9306-9307`）；
 * - `HOTKEY_STATE(index, value)` 写一格，**未初始化即抛**
 *   `Exception("use HOTKEY_STATE_INIT before using HOTKEY_STATE")`（:41-48，函数壳
 *   `FunctionMethodCreator.cs:8679-8706`）；
 * - **没有下标检查**：越界照原版直接抛（C# 的 `state[index]`，`index` 是 `nint`，:47；
 *   比较侧 :221、:226 同样裸取）。本层不加「看起来合理」的夹取。
 */
class HotkeyStates {

    private var cells: LongArray? = null

    /** `availableStateArray`（:27）。 */
    val initialized: Boolean
        get() = cells != null

    /** `HotkeyStateInit`（:35-39）。 */
    fun init(size: Long) {
        cells = LongArray(size.toInt())
    }

    /** `HotkeyStateSet`（:41-48）：未初始化即抛原文异常，下标越界照原版裸抛。 */
    fun set(index: Long, value: Long) {
        cellArray()[index.toInt()] = value
    }

    /** 解释器侧的裸取（:221、:226）。 */
    operator fun get(index: Int): Long = cellArray()[index]

    /** 长度（原版不暴露，本层给绑定层做读数用；不参与任何判定）。 */
    val size: Int
        get() = cells?.size ?: 0

    private fun cellArray(): LongArray =
        cells ?: throw IllegalStateException("use HOTKEY_STATE_INIT before using HOTKEY_STATE")
}

/**
 * `Keys` 名字 → 整值。可注入实现；[DEFAULT] 只覆盖有出处的常用子集。
 */
interface KeysNames {

    /** 解析一个 `Enum.TryParse<Keys>` 位置的串；无法确定时返回 null（= 解析失败）。 */
    fun resolve(name: String): Int?

    companion object {
        /** 修饰键位（`System.Windows.Forms.Keys` 的 flags 值）。 */
        const val SHIFT_BIT: Int = 0x10000
        const val CONTROL_BIT: Int = 0x20000
        const val ALT_BIT: Int = 0x40000

        /**
         * 默认实现：数字串直接取值；逗号分隔的多名按位或（flags 枚举语义）；
         * 名字**区分大小写**（`Enum.TryParse` 该重载的默认，`HotkeyState.cs:55`）。
         * 子集见 [TABLE]，子集外返回 null。
         */
        val DEFAULT: KeysNames = StandardKeys(StandardKeys.TABLE)
    }
}

/**
 * [KeysNames] 的标准实现（子集表可换）。
 *
 * @param table 名字 → 整值表，**键按 WinForms 原样大小写**（`Enum.TryParse` 默认区分大小写）
 */
class StandardKeys(private val table: Map<String, Int>) : KeysNames {

    override fun resolve(name: String): Int? {
        if (name.isEmpty()) {
            return null
        }
        var acc = 0
        for (token in name.split(',')) {
            val value = token.toIntOrNull() ?: table[token.trim()] ?: return null
            acc = acc or value
        }
        return acc
    }

    companion object {
        /**
         * 常用子集：字母/数字/功能键/编辑键 + 三个修饰键。
         * 主键盘数字名用 WinForms 的 `D0`..`D9`（= VK 0x30..0x39，同 [MacroKeys.VK_DIGIT_0]），
         * 小键盘用 `NumPad0`..`NumPad9`（= 0x60..0x69，同 [MacroKeys.VK_NUMPAD_0]）。
         * TODO(verify)：完整 `Keys` 名表（多媒体键、IME 处理键、鼠标名）无 PC 读数控点。
         */
        val TABLE: Map<String, Int> = buildMap {
            for (c in 'A'..'Z') put(c.toString(), 0x41 + (c - 'A'))
            for (d in 0..9) put("D$d", 0x30 + d)
            for (d in 0..9) put("NumPad$d", 0x60 + d)
            for (f in 1..24) put("F$f", 0x70 + (f - 1))
            put("None", 0)
            put("Shift", KeysNames.SHIFT_BIT)
            put("Control", KeysNames.CONTROL_BIT)
            put("Alt", KeysNames.ALT_BIT)
            put("LWin", 0x5B)
            put("RWin", 0x5C)
            put("Backspace", 0x08)
            put("Tab", 0x09)
            put("Clear", 0x0C)
            put("Enter", 0x0D)
            put("Return", 0x0D)
            put("Pause", 0x13)
            put("Capital", 0x14)
            put("Escape", 0x1B)
            put("Space", 0x20)
            put("Prior", 0x21)
            put("PageUp", 0x21)
            put("Next", 0x22)
            put("PageDown", 0x22)
            put("End", 0x23)
            put("Home", 0x24)
            put("Left", 0x25)
            put("Up", 0x26)
            put("Right", 0x27)
            put("Down", 0x28)
            put("Select", 0x29)
            put("Print", 0x2A)
            put("Execute", 0x2B)
            put("PrintScreen", 0x2C)
            put("Insert", 0x2D)
            put("Delete", 0x2E)
            put("Help", 0x2F)
        }
    }
}
