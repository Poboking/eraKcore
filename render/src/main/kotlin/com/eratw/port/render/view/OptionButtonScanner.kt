package com.eratw.port.render.view

import com.eratw.port.render.HitTarget

/**
 * 右侧「大按钮」栏的一枚候选 —— **引擎级**枚举结果。
 *
 * 与旧实现（`CanvasConsoleView.optionChips()` 拿文本快照跑正则认 `[N]`）的区别：
 * 这里的数据全部来自真实按钮命中层 [com.eratw.port.render.button.ButtonLayer]，
 * 所以
 * - 字符串值按钮（`exit`、`戻る` 之类，`TextRun.buttonValue` 是 String）也能出现；
 * - 「只认最新一页」是结构保证：命中层 `attach` 的输入就是当前窗口的行
 *   （`ConsoleScreen.hitRows()`），上一页的按钮根本不在表里；
 * - 行列几何直接可用（按钮的 `colStart..colEnd` 就是它印在屏上的那几格）。
 *
 * @property ordinal 数值按钮的序号（`value` 是 0..999 的 Long）；字符串按钮为 null
 * @property label 按钮上印的字；图形按钮没有文字时为空串（UI 退化成值本身）
 * @property value 提交给输入队列的值：`Long`（数值）或 `String`（字符串按钮），
 *   与 `TextRun.buttonValue` 同源；点它时走 `ButtonLayer.selectedString` 的同一口径
 */
data class OptionButton(
    val ordinal: Int?,
    val label: String,
    val value: Any,
) {
    /** 提交给输入队列的文本：数值 = 十进制串，字符串 = 原串。 */
    val submitText: String get() = value.toString()
}

/**
 * [OptionButton] 的枚举器（纯 JVM，可在 `:render` 单测里直接断言）。
 *
 * **旧路径（2026-10-25 起不再用于右侧栏）**：它从命中层/日志尾部/HTML 快照多路扫描，
 * 无法区分“当前页真选项”和“正文里的 `[N]`/旧页残留”。右侧栏已改用引擎快照
 * `LineBufferConsoleSink.currentOptions()`（见 `ConsoleSink.noteInputWait`）。
 * 保留本对象只为历史测试/调试；新代码不要接。
 */
object OptionButtonScanner {

    /** 标签里的序号前缀（[0] 、[ 12] ）；数值按钮的序号码在 UI 上单独显示。 */
    /** 标签里的序号前缀（`[0] `、`[ 12] `）；数值按钮的序号码在 UI 上单独显示。 */
    private val ORDINAL_PREFIX = Regex("""^\[\s*\d{1,4}\s*\]\s*""")

    /**
     * 枚举当前页的按钮候选。
     *
     * @param screen 已 `refresh()` 过的控制台（读它的 `buttons` 与 `hitRows`）
     * @param maxCount 上限（右侧栏一屏能放下的量级）
     * @param maxLabelChars 标题截断长度
     */
    fun scan(screen: ConsoleScreen, maxCount: Int = 64, maxLabelChars: Int = 24): List<OptionButton> {
        // 三层并集（2026-10-24 定稿，判据 = 原版「有按钮对象就可点」，见 ConsoleButtonString.IsButton）：
        //  ① 可见命中层（screen.buttons 的输入）：屏上能直接点的；
        //  ② 日志尾部宽窗口（chipRows）：**屏外**的按钮；
        //  ③ 原始 HTML 快照里的 `<button value=…>`：兜底 —— 展平/命中层万一漏掉某一行
        //     （实测存档页槽位 0 是 `<button value='0'><shape type='space'>…` 形态），
        //     只要页面上印了 `<button>`，原版就一定有对应 ConsoleButtonString ⇒ 也必须列。
        //
        // 去重口径：**按 value 唯一**。同一个 value 会同时出现在「旧页残留」（如标题页的
        // `0 开始游戏`）和「当前页」（存档槽位 `0`）里，栏里出现两个 0 会让玩家点错；
        // 优先保留**有标签的**（信息多），其次保留**先遇到的**（来源顺序 = 新页优先）。
        val merged = LinkedHashMap<Long, OptionButton>()
        val stringValues = LinkedHashMap<String, OptionButton>()

        fun putAll(list: List<OptionButton>) {
            for (o in list) {
                if (merged.size + stringValues.size >= maxCount) return
                val key = o.value
                if (key is Long) {
                    val exist = merged[key]
                    if (exist == null || (exist.label.isEmpty() && o.label.isNotEmpty())) merged[key] = o
                } else {
                    val k = key.toString()
                    val exist = stringValues[k]
                    if (exist == null || (exist.label.isEmpty() && o.label.isNotEmpty())) stringValues[k] = o
                }
            }
        }

        putAll(screen.buttons.all().map { toOption(it, maxLabelChars) })
        putAll(scanRows(screen.hitRows(), maxCount, maxLabelChars))
        putAll(scanRows(screen.chipRows(), maxCount, maxLabelChars))
        putAll(scanHtmlButtons(screen.snapshotText(), maxCount, maxLabelChars))
        // ⑤ HTML 浮岛（HTML_PRINT_ISLAND）：`div + button` 这类面板的按钮只在浮岛里，
        //    普通显示行与快照都看不到它们。
        putAll(scanHtmlButtons(screen.islandHtml(), maxCount, maxLabelChars))
        // 数值按钮按序号升序（与屏幕上的顺序一致），字符串按钮排后面
        return merged.values.sortedBy { it.ordinal ?: Int.MAX_VALUE } + stringValues.values
    }

    /** `<button value='N'>…</button>`（属性顺序不定、标签可带 title）。 */
    private val HTML_BUTTON = Regex("""<button\b[^>]*?\bvalue='?(\d+)'?[^>]*>(.*?)</button>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * 原始 HTML 里出现的全部按钮（含 `<button>` 内嵌 `<font>` / `<shape>` 的情况）。
     *
     * 口径：
     * - 只看**尾部** [HTML_TAIL_LINES] 行（等价于「最近几页」），不会把远古页面的按钮翻出来；
     * - **自底向上**扫、按 value 去重（先遇到的＝更靠下＝更新的一页），与右侧栏「最新页优先」一致。
     */
    fun scanHtmlButtons(
        lines: List<String>,
        maxCount: Int = 64,
        maxLabelChars: Int = 24,
    ): List<OptionButton> {
        val out = ArrayList<OptionButton>()
        val seen = HashSet<Long>()
        val tail = if (lines.size > HTML_TAIL_LINES) lines.subList(lines.size - HTML_TAIL_LINES, lines.size) else lines
        var i = tail.size - 1
        while (i >= 0 && out.size < maxCount) {
            val line = tail[i]
            i--
            if (!line.contains("<button", ignoreCase = true)) continue
            val matches: List<MatchResult> = HTML_BUTTON.findAll(line).toList()
            // 同一行内也自右向左（后印的按钮在视觉上更靠右）
            for (m in matches.reversed()) {
                val value = m.groupValues[1].toLongOrNull() ?: continue
                if (!seen.add(value)) continue
                val label = informative(stripTags(m.groupValues[2]).replace(ORDINAL_PREFIX, "").trim()).take(maxLabelChars)
                out.add(OptionButton(value.takeIf { it in 0..999 }?.toInt(), label, value))
                if (out.size >= maxCount) break
            }
        }
        return out
    }

    /** HTML 兜底只看尾部这么多行（约 3 页）。 */
    private const val HTML_TAIL_LINES = 160


    /** 标签是否有信息量：去掉标点/横线/空白后还剩字符才算（空槽位的 `- ----` 视为无标签）。 */
    private fun informative(label: String): String {
        val rest = label.replace(Regex("""[\s\-—–_=~·.。,，、|｜/\\]+"""), "")
        return if (rest.isEmpty()) "" else label
    }

    /** 去掉 HTML 标签与常见实体，只留可读文字。 */
    private fun stripTags(raw: String): String =
        raw.replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * 枚举给定行窗口里的按钮候选（**纯函数**：不读控制台状态，便于单测与复用）。
     *
     * @param rows 行窗口（实际调用方传 [ConsoleScreen.chipRows] = 日志尾部宽窗口）
     */
    fun scanRows(
        rows: List<com.eratw.port.render.DisplayRow>,
        maxCount: Int = 64,
        maxLabelChars: Int = 24,
    ): List<OptionButton> {
        val out = ArrayList<OptionButton>()
        // 独立命中层实例：不动真实命中层（点击几何不受影响）。
        val layer = com.eratw.port.render.button.ButtonLayer()
        layer.attach(rows)
        for (t in layer.all()) {
            val entry = toOption(t, maxLabelChars)
            // 同一 (值, 文字) 只留一条：脚本常把同一选项印成「[N] 标签」与「[N]」两段。
            if (out.any { it.value == entry.value && it.label == entry.label }) continue
            out.add(entry)
            if (out.size >= maxCount) break
        }
        return out
    }

    /** 单条命中区 → 候选（暴露出来便于单测逐条核对）。 */
    fun toOption(target: HitTarget, maxLabelChars: Int = 24): OptionButton {
        val ordinal = (target.value as? Long)?.takeIf { it in 0..999 }?.toInt()
        // 按钮文本常自带 [0]  / [ 12]  前缀，而栏里已经单独显示序号徽标 —— 去掉重复。
        val rawLabel = informative(target.label.trim().replace(ORDINAL_PREFIX, "").trim())
        val label = rawLabel.take(maxLabelChars)
        return OptionButton(ordinal, label, target.value)
    }
}
