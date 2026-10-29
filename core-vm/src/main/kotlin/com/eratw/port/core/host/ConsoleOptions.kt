package com.eratw.port.core.host

/**
 * 一条按钮的创建来源。右侧选项栏只消费引擎快照，不再从可见文本里猜。
 *
 * - [EXPLICIT]：`PRINTBUTTON` / `PRINTBUTTONC` / `PRINTBUTTONLC` 显式创建。
 * - [HTML]：`HTML_PRINT` / `HTML_PRINT_ISLAND` 里的 `<button value=…>`。
 * - [AUTO_TEXT]：普通文本里的 `[N]` 被原版 `ButtonStringCreator` 自动变成可点段
 *   （`PrintStringBuffer.fromCssToButton`，`PrintStringBuffer.cs:280-292`）。
 *
 * 三者都是原版真的可点对象；来源只用于诊断与显示徽标，**不参与可点性判定**
 * （可点性只由世代 + 等待类型决定，见 [LineBufferConsoleSink.currentOptions]）。
 */
enum class OptionOrigin { EXPLICIT, HTML, AUTO_TEXT }

/**
 * 引擎在**进入一次输入等待**时冻结下来的一个选项。
 *
 * 与 `OptionButtonScanner` 的旧产物不同：这里没有正则、没有“扫日志尾部 N 行”，
 * 只有 [LineBufferConsoleSink] 按原版世代（`Generation == lastButtonGeneration`）
 * 筛出来的真实按钮对象。`value` 与原版 `ConsoleButtonString.Input/Inputs` 同型
 * （`Long` 或 `String`），点击提交时直接 `toString()`，与 `ButtonLayer.selectedString`
 * 同一口径。
 *
 * @property value 提交值：数值按钮是 `Long`，字符串按钮是 `String`。**永远以按钮对象自己的
 *   value 为准**，不从标签里的 `[N]` 前缀反推 —— 原版允许 `[27] 示例` 的按钮提交 `227`
 *   （地图 `MAIN_MAP*100+LOCAL` 形态，`DRAW_MAP.ERB:467`），前缀只是屏上印的字。
 * @property label 按钮上印的字（已去 HTML 标签、已折空白；可能为空）。标签前缀是否剥掉
 *   取决于它与 [value] 是否一致，见 [LineBufferConsoleSink] 的 `cleanOptionLabel`。
 * @property origin 按钮来源（见 [OptionOrigin]；仅诊断/徽标，不参与可点性）
 * @property lineSerial 按钮所在显示行的单调序号（UI 按它做行优先排序）
 * @property startCol 行内起始半角格（行优先排序的第二关键字）
 * @property endCol 行内结束半角格（半开区间）
 * @property generation 按钮对象的世代（原版 `ConsoleButtonString.Generation`，诊断用）
 * @property ordinal 数值按钮的序号徽标；**只有标签前缀与 value 一致、且 value 在 0..999 时**
 *   才非 null。前缀与 value 不一致（如标签 `[27]`、value `227`）时为 null：既不用前缀
 *   当提交值，也不把 `227` 冒充成屏上的 `27`，徽标交给标签原文。
 * @property groupId **源按钮身份**（原版是一个 `ConsoleButtonString` 实例；本仓用单调编号表达，
 *   见 `LineBufferConsoleSink.nextButtonGroupId`）。同一颗按钮折行/跨 `<br>` 重放时各处都是
 *   同一个编号 ⇒ 右侧栏并成一枚；屏上两颗不同的按钮即使 [value] 相同也是两个编号 ⇒ 两枚都留。
 *   右侧栏的去重键是它、**不是 [value]**（[ConsoleOptionFilter.prefer] 的口径说明）。
 *   `-1` = 身份未知（手工构造的本类型），永不与别的候选合并。
 */
data class ConsoleOption(
    val value: Any,
    val label: String,
    val origin: OptionOrigin,
    val lineSerial: Long = 0L,
    val startCol: Int = 0,
    val endCol: Int = 0,
    val generation: Long = 0L,
    val ordinal: Int? = null,
    val groupId: Int = -1,
) {
    /** 点这枚候选要提交给输入队列的文本：数值 = 十进制串，字符串 = 原串。 */
    val submitValue: String get() = value.toString()
}

/**
 * 选项快照的查询口径（引擎侧唯一实现，见 [LineBufferConsoleSink.currentOptions]）。
 *
 * 入参是**当前等待请求**：只有取值等待（`INPUT`/`INPUTS`/`INPUTANY`/`BINPUT` 系）
 * 才有“选项”；`WAIT`/`ENTER`/`VOID` 这类只等回车的页面一律返回空表。数值等待只留
 * `Long`，字符串等待两种值都收（原版 `SelectedString` 的 StrValue 支取 `Inputs`，
 * `ConsoleButtonString.cs:97-110`）。
 *
 * 这是一条纯函数，便于单测直接钉口径；[LineBufferConsoleSink] 只负责在等待入口
 * 把当前页按钮喂进来。
 */
internal object ConsoleOptionFilter {

    fun accepts(request: InputRequest, option: ConsoleOption): Boolean =
        when (request.form) {
            InputRequest.RequestForm.VALUE ->
                request.type == InputRequest.InputType.STR || option.value is Long
            InputRequest.RequestForm.ANY_VALUE -> true
            InputRequest.RequestForm.ENTER_KEY,
            InputRequest.RequestForm.ANY_KEY,
            InputRequest.RequestForm.VOID,
            InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY,
            -> false
        }

    /**
     * 同**源**候选（同一个 [ConsoleOption.groupId] 折行/跨 `<br>` 重放出来的几条）留哪一条。
     *
     * 同源恒同值、恒同来源，所以 [OptionOrigin] 的优先级实际只在「身份未知的候选被并到一起」
     * 这种非生产形态下起作用；真正会发生的差别是**标签**：一条按钮的正文可能只印在首行
     * （图形按钮、`<button>` 里带 `<br>` 的那颗），后出的分片标签为空 ⇒ 让位于有标签的那条。
     *
     * 不同源的候选**不进这里**：屏上有几颗按钮，右侧栏就出几枚，即使它们的 [ConsoleOption.value]
     * 相同（地图格子「2」与同屏菜单「2」是两颗按钮，原版点哪个就提交哪个的值）。
     */
    fun prefer(existing: ConsoleOption, candidate: ConsoleOption): Boolean {
        val existingRank = rank(existing.origin)
        val candidateRank = rank(candidate.origin)
        if (candidateRank != existingRank) return candidateRank > existingRank
        return existing.label.isEmpty() && candidate.label.isNotEmpty()
    }

    private fun rank(origin: OptionOrigin): Int = when (origin) {
        OptionOrigin.EXPLICIT, OptionOrigin.HTML -> 2
        OptionOrigin.AUTO_TEXT -> 1
    }
}
