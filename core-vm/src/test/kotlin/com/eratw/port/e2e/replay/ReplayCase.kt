package com.eratw.port.e2e.replay

/**
 * 输入轨迹重放用例（`.replay` 文件）的解析结果与解析器。
 *
 * 格式（CONTRACT-MODERN-R2 §3.1，逐条冻结）：
 * - 纯文本 UTF-8、`\n` 换行；`#` 开头为注释（只在区段外的头部生效）。
 * - 头部键只允许 `case` / `target` / `anchor`（可多行）/ `expect`（唯一合法值 `blocked`）；
 *   `case` 与 `target` 必需，`anchor` 至少一条。
 * - 区段标记 `--- inputs` / `--- expect-console` / `--- expect-vars`，每个最多出现一次。
 * - inputs 指令集固定四个：`submit[ <text>]`、`submit-skip[ <text>]`、`click <text>[ <button>]`、
 *   `pause <ms>`；未知指令一律解析失败（不静默忽略）。
 * - text 转义：`\n` → 换行、`\\` → 反斜杠；其余 `\x` 按原文保留（这样 `\e` 这类
 *   原版标记能原样投喂，见 [Submission] 的 mesSkip 说明）。
 *
 * 语义权威是反编译 C# 与本仓 specs；本文件只负责「把冻结格式读成结构」，
 * 不做任何语义推断，也不回写期望值（没有 update 模式，见契约 §0-5）。
 */
data class ReplayCase(
    /** `case:` 名，用于测试名与失败消息。 */
    val name: String,
    /** `target:` 脚本函数键（`callFunction` 的目标）。 */
    val target: String,
    /** `anchor:` 行（反编译 C# 的 文件:行号 或文档锚）；至少一条。 */
    val anchors: List<String>,
    /** `expect: blocked` = 指令耗尽后函数仍停在等待里才算通过（钉阻塞语义）。 */
    val expectBlocked: Boolean,
    /** 投喂指令序列。 */
    val inputs: List<ReplayInput>,
    /** 每行一条精确控制台期望（比较前两侧都过 GoldenNormalizer 归一化）。 */
    val expectConsole: List<String>,
    /** 变量期望（`store.readInt` / `store.readStr` 口径）。 */
    val expectVars: List<VarExpect>,
    /** 来源文件名（失败消息用）。 */
    val source: String,
)

/** 投喂指令（`.replay` 的 inputs 区段）。 */
sealed interface ReplayInput {
    /**
     * 一条回车文本提交。
     *
     * @param mesSkip 对应 `PressEnterKey(keySkip, ...)` 的第一参（`EmueraConsole.cs:1371-1373`）：
     *   普通回车 false，双击/右键/Esc 跳过 true。`submit-skip` 即 true。
     */
    data class Submission(val text: String, val mesSkip: Boolean) : ReplayInput

    /**
     * 一次画面按钮点击（`MainWindow.cs:824-849` 的鼠标分支）：文本 = 按钮 value，
     * [button] 1 左键 / 2 右键 / 3 中键（Android 触摸恒 1）。
     */
    data class Click(val text: String, val button: Int) : ReplayInput

    /** 投喂线程的裸 sleep（不触碰队列）。 */
    data class Pause(val millis: Long) : ReplayInput
}

/** 一条变量期望：`NAME[:idx[:idx]] = 整数` 或 `= "字符串"`。 */
data class VarExpect(
    val name: String,
    val indices: LongArray,
    /** 非 null = 字符串期望（读 `store.readStr`）；null = 整数期望（读 `store.readInt`）。 */
    val strValue: String?,
    val intValue: Long,
    /** 原文（失败消息里原样回显）。 */
    val raw: String,
) {
    val isStr: Boolean get() = strValue != null

    // LongArray 不是 data class 的 equals 成员（引用相等），显式实现以免两条同形期望不等。
    override fun equals(other: Any?): Boolean =
        this === other || (other is VarExpect && name == other.name &&
            indices.contentEquals(other.indices) && strValue == other.strValue && intValue == other.intValue)

    override fun hashCode(): Int = (name.hashCode() * 31 + indices.contentHashCode()) * 31 + (strValue?.hashCode() ?: 0)

    override fun toString(): String = raw
}

/** 解析失败（带来源文件与行号；调用方据此把用例判红并点名）。 */
class ReplayParseException(val source: String, val line: Int, message: String) :
    IllegalArgumentException("$source:$line $message")

/** `.replay` 解析器（纯函数：文本进、结构出）。 */
object ReplayCaseParser {

    private const val SEC_INPUTS = "inputs"
    private const val SEC_CONSOLE = "expect-console"
    private const val SEC_VARS = "expect-vars"

    fun parse(source: String, text: String): ReplayCase {
        // UTF-8 BOM 与 CRLF：Windows 编辑器保存过的文件也能读。
        val body = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = body.split("\n")

        var name: String? = null
        var target: String? = null
        val anchors = ArrayList<String>()
        var expectBlocked = false
        val inputs = ArrayList<ReplayInput>()
        val expectConsole = ArrayList<String>()
        val expectVars = ArrayList<VarExpect>()

        // 区段状态：null = 还在头部；其它 = 已进入某个区段（区段不得重复）。
        var section: String? = null
        val seenSections = HashSet<String>()

        for ((i, raw) in lines.withIndex()) {
            val lineNo = i + 1
            val line = raw.trimEnd()
            if (line.startsWith("--- ")) {
                val key = line.removePrefix("--- ").trim()
                if (key !in setOf(SEC_INPUTS, SEC_CONSOLE, SEC_VARS)) {
                    throw ReplayParseException(source, lineNo, "未知区段标记「$line」（只允许 --- inputs / --- expect-console / --- expect-vars）")
                }
                if (!seenSections.add(key)) throw ReplayParseException(source, lineNo, "区段「--- $key」重复出现")
                section = key
                continue
            }
            when (section) {
                null -> {
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) continue
                    val colon = t.indexOf(':')
                    if (colon <= 0) throw ReplayParseException(source, lineNo, "头部行既不是 key: value 也不是区段标记：「$t」")
                    val key = t.substring(0, colon).trim().lowercase()
                    val value = t.substring(colon + 1).trim()
                    when (key) {
                        "case" -> {
                            if (name != null) throw ReplayParseException(source, lineNo, "case 重复")
                            if (value.isEmpty()) throw ReplayParseException(source, lineNo, "case 为空")
                            name = value
                        }
                        "target" -> {
                            if (target != null) throw ReplayParseException(source, lineNo, "target 重复")
                            if (value.isEmpty()) throw ReplayParseException(source, lineNo, "target 为空")
                            target = value
                        }
                        "anchor" -> {
                            if (value.isEmpty()) throw ReplayParseException(source, lineNo, "anchor 为空")
                            anchors.add(value)
                        }
                        "expect" -> {
                            if (value != "blocked") throw ReplayParseException(source, lineNo, "expect 只允许 blocked（实为「$value」）")
                            expectBlocked = true
                        }
                        else -> throw ReplayParseException(source, lineNo, "未知头部键「$key」（只允许 case/target/anchor/expect）")
                    }
                }
                SEC_INPUTS -> {
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    inputs.add(parseInput(source, lineNo, t))
                }
                SEC_CONSOLE -> expectConsole.add(line)
                SEC_VARS -> {
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    expectVars.add(parseVar(source, lineNo, t))
                }
            }
        }

        // 尾部空行不是有效期望（文件末尾换行会造出一条空串）。
        while (expectConsole.isNotEmpty() && expectConsole.last().isEmpty()) expectConsole.removeAt(expectConsole.size - 1)

        val n = name ?: throw ReplayParseException(source, 0, "缺少必需头部键 case")
        val tg = target ?: throw ReplayParseException(source, 0, "缺少必需头部键 target")
        if (anchors.isEmpty()) throw ReplayParseException(source, 0, "缺少 anchor（每条用例至少一条 C# 锚）")
        return ReplayCase(
            name = n,
            target = tg,
            anchors = anchors,
            expectBlocked = expectBlocked,
            inputs = inputs,
            expectConsole = expectConsole,
            expectVars = expectVars,
            source = source,
        )
    }

    private fun parseInput(source: String, lineNo: Int, text: String): ReplayInput {
        val space = text.indexOf(' ')
        val op = if (space < 0) text else text.substring(0, space)
        val rest = if (space < 0) "" else text.substring(space + 1)
        return when (op) {
            "submit" -> ReplayInput.Submission(unescape(rest), mesSkip = false)
            "submit-skip" -> ReplayInput.Submission(unescape(rest), mesSkip = true)
            "click" -> {
                val parts = rest.trim().split(' ').filter { it.isNotEmpty() }
                if (parts.isEmpty()) throw ReplayParseException(source, lineNo, "click 缺少按钮文本")
                val button = if (parts.size >= 2) {
                    parts[1].toIntOrNull()
                        ?: throw ReplayParseException(source, lineNo, "click 的按钮位不是整数：「${parts[1]}」")
                } else {
                    1
                }
                if (button !in 1..3) throw ReplayParseException(source, lineNo, "click 的按钮位只允许 1/2/3（实为 $button）")
                ReplayInput.Click(unescape(parts[0]), button)
            }
            "pause" -> {
                val ms = rest.trim().toLongOrNull()
                    ?: throw ReplayParseException(source, lineNo, "pause 缺少毫秒整数：「$rest」")
                if (ms < 0) throw ReplayParseException(source, lineNo, "pause 不接受负毫秒（$ms）")
                ReplayInput.Pause(ms)
            }
            else -> throw ReplayParseException(
                source, lineNo,
                "未知指令「$op」（只允许 submit / submit-skip / click / pause）",
            )
        }
    }

    /** `\n` → 换行、`\\` → 反斜杠；其余 `\x` 原样保留（允许投喂 `\e` 这类原版标记）。 */
    internal fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c); i += 1
            }
        }
        return sb.toString()
    }

    private fun parseVar(source: String, lineNo: Int, text: String): VarExpect {
        val eq = text.indexOf('=')
        if (eq <= 0) throw ReplayParseException(source, lineNo, "变量期望缺 `=`：「$text」")
        val lhs = text.substring(0, eq).trim()
        val rhs = text.substring(eq + 1).trim()
        val segs = lhs.split(':')
        val name = segs[0].trim()
        if (name.isEmpty()) throw ReplayParseException(source, lineNo, "变量名为空：「$text」")
        val indices = LongArray(segs.size - 1) { k ->
            segs[k + 1].trim().toLongOrNull()
                ?: throw ReplayParseException(source, lineNo, "变量「$name」的下标不是整数：「${segs[k + 1].trim()}」")
        }
        if (rhs.isEmpty()) throw ReplayParseException(source, lineNo, "变量「$name」的期望值为空")
        if (rhs.startsWith("\"")) {
            if (rhs.length < 2 || !rhs.endsWith("\"")) {
                throw ReplayParseException(source, lineNo, "字符串期望的引号未闭合：「$rhs」")
            }
            val s = rhs.substring(1, rhs.length - 1)
            if (s.contains('"')) throw ReplayParseException(source, lineNo, "字符串期望不支持内嵌引号：「$rhs」")
            return VarExpect(name, indices, strValue = s, intValue = 0L, raw = text)
        }
        val v = rhs.toLongOrNull() ?: throw ReplayParseException(source, lineNo, "整数期望不是整数：「$rhs」")
        return VarExpect(name, indices, strValue = null, intValue = v, raw = text)
    }
}
