package com.eratw.port.render.scroll

import com.eratw.port.render.DisplayRow

/**
 * 滚动视口（M4-SCROLL 视口侧）：贴底跟随 / 上翻锁定。
 *
 * ## 唯一的滚动状态 = `offsetFromEnd`
 * 原版没有独立的滚动模型，它把 WinForms 纵向滚动条当视口用，并且**把 `ScrollBar.Value`
 * 当成「视口底部那一行的下标 + 1」**来读：`num2 = ScrollBar.Value - 1`
 * （spec: `EmueraConsole.cs:1786-1795` 的 `GetLinePointY`、`EmueraConsole.cs:1804-1812` 的绘制起止、
 * `EmueraConsole.cs:2322-2331` 的命中检测），`Maximum` 恒等于列表长度
 * （spec: `EmueraConsole.cs:2543-2567`）。是否贴底则用 `Value != Maximum` 判定
 * （spec: `EmueraConsole.cs:1738`、`EmueraConsole.cs:1804`、`MainWindow.cs:799`）。
 * 本类把这一对数换算成 **底部之下还压着多少行**：
 * `offsetFromEnd = Maximum - Value`，`atBottom == (offsetFromEnd == 0)`，
 * 于是 `Value` 的增减与 `Maximum` 的增减互相抵消，滚动状态不再依赖列表长度。
 *
 * ## 上翻锁定 = 偏移守恒（不是绝对行锁）
 * `verticalScrollBarUpdate` 只做 `Maximum = count; Value += (count - Maximum)`
 * （spec: `EmueraConsole.cs:2552-2556`），`count` 没变时直接早退（spec: `EmueraConsole.cs:2546-2550`）。
 * 换成 `offsetFromEnd` 就是：**追加新行时偏移一个数都不改** ——
 * 贴底时偏移 0，底部永远是最新行（自动跟随）；上翻时偏移保持，
 * 视口底部与尾部的距离恒定，绝不跳回底部，也不会被新行顶掉。
 * 缓冲区写满后每次追加同时淘汰一行，`count` 不变 ⇒ 走早退分支 ⇒ 偏移仍然守恒。
 *
 * ## 缩短不再拽回（2026-10-29 真机反馈改：玩家锚点优先）
 * `count` 变小（`CLEAR_DISPLAY` / 上层删行 / 整屏重印）时原版走 `num < 0` 分支，把 `Value`
 * 抬到 `count` 再令 `Maximum = count`（spec: `EmueraConsole.cs:2557-2564`），等价于
 * `offsetFromEnd = 0`；`count == 0` 时滚动条整体禁用（spec: `EmueraConsole.cs:2565`）。
 *
 * **根因**：本移植的 [retainedLines] 数的是渲染层**折行后**的显示行数（`ConsoleLogSync` 按
 * `clientWidth` 折），不是原版的 `displayLineList.Count`，所以同一个页面重印一次就可能因为消息行、
 * 末尾空行的归属而少几行。QoL 地图/外出屏每 `ANIMATERECOLOREDMAPS` 毫秒整屏 `CLEARLINE` + 重印
 * （`ERB/魔改内容/qol/qol_MAP.ERB:34-44`），于是「归零」每个重印周期发生一次：玩家刚上滑想看地图
 * 顶部，≤200 ms 就被扔回底部，画面同时跟着跳一下 —— 玩家报上来的就是「向上滑会抖动并把视角
 * 拖拽回来，看不到地图最上方」。原版那条分支在 PC 上被走到的频次没有取证（两侧计数口径不同，
 * `TODO(verify)` 只在 PC 同屏对照时才需要）。
 * **取舍**：按波次契约铁律 9（贴底/滚动位置属平台观感行为，按平台最优并写明取舍，先例见
 * 下面「能上翻到哪里」），缩短时**只把偏移夹进 `[0, maxOffset]`、不清零** —— 剩下的行不够一屏时
 * `maxOffset` 本身是 0，仍然回到唯一合法的贴底态；够一屏时玩家看的那一处保持不动。
 * 主动回底线的两条入口不变（点击 [snapToBottomForTap]、按键
 * `InputSession.snapToBottomForKeyDown`），仍与原版一致 —— 拽回只由**玩家自己的动作**决定。
 *
 * ## 能上翻到哪里（M11-B DEV-01 改：上界收紧，滚到顶不再空屏）
 * 原版滚动条 `Minimum` 为默认值 0（spec: `MainWindow.cs:1797-1805` 只显式设 `LargeChange = 1` 与 `Maximum`），
 * 滚轮与翻页键把 `Value` 夹在 `[Minimum, Maximum]`（spec: `MainWindow.cs:1276-1287`、`MainWindow.cs:1383-1397`），
 * 所以原版允许 `Value == 0`：此时 `num2 == -1`，绘制循环 `for (i = num3; i <= num2 …)` 一次都不进
 * （spec: `EmueraConsole.cs:1806-1812`、`EmueraConsole.cs:1852`），整屏不画任何行。
 *
 * **根因**：把这条口径直译（clamp 上界 = `retainedLines` 本身）就是「滚到顶整屏黑」——
 * `offsetFromEnd == retainedLines` 时 [bottomRingIndex] 为 -1、[visibleRange] 为空，
 * 而内容锚定一侧（[topRingIndex] 的 `num3 < 0` 夹 0）并没有错。原版这是 WinForms
 * 滚动条模型的副产品，PC 上有滚动条拇指做位置反馈、且一滚就回，尚可接受；手机上
 * 没有滚动条，滚到顶只得到一屏黑、阅读上下文全部丢失，属**纯观感缺陷**。
 * **取舍**：按波次契约铁律 9（贴底/滚动位置属平台观感行为，按平台最优并写明取舍），
 * 本类把 clamp 上界收为 [maxOffset] = `max(0, retainedLines - heightLines)`：
 * 滚到顶时 [bottomRingIndex] 恰为 `heightLines - 1`、[topRingIndex] 夹到 0，
 * 显示保留区**最早的一屏**；内容不足一屏（`retainedLines <= heightLines`）时没有
 * 可上翻的余量，视口恒贴底。其余口径（偏移守恒）逐字不变。
 *
 * ## 输入侧的三条入口（口径照抄）
 *   * 滚轮：`num = -Math.Sign(delta) * SmallChange * Config.ScrollHeight`，`Value += num`
 *     （spec: `MainWindow.cs:1262`、`MainWindow.cs:1276-1287`；`SCROLLHEIGHT` 默认 1 行，spec: `ConfigData.cs:74`）
 *     —— 向上滚（`delta > 0`）使 `Value` 变小，即 [offsetFromEnd] 变大。
 *   * PageUp / PageDown：固定 10 行，`Prior` 取负（spec: `MainWindow.cs:1379-1398`）。
 *   * 鼠标点击与任意按键：上翻状态下直接回贴底（`Value = Maximum` 并强刷，
 *     spec: `MainWindow.cs:799-805`、`MainWindow.cs:1402-1406`）—— 对应 [snapToBottom]
 *     与 [snapToBottomForTap]（tap 半边在 `CanvasConsoleView.tap` 接线，按键半边
 *     在 `InputSession.snapToBottomForKeyDown` 接线）。
 *
 * ## 高度口径：C# 两式恒等，多要的那一排被画布裁掉
 * 原版绘制 `num3 = num2 - ((Height - LineHeight) / LineHeight + 1)`（spec: `EmueraConsole.cs:1805-1807`）
 * 与命中 `num3 = num2 - Height / LineHeight`（spec: `EmueraConsole.cs:2328-2331`）在整数除法下
 * **逐值相同**：`(H - L) / L + 1 == H / L`。原版画的是 `H / L + 1` 行，最上面那行的 y 为负、
 * 整行落在窗口之外（`:1812` 的 y 回推），既不可见也点不到。
 * 本类的 [heightLines] 就是**命中窗口的行数**（`H / L + 1`），`topRingIndex` 的算式
 * `num2 - heightLines + 1 - extraAbove` 在 `extraAbove = 0` 时与 C# 的 `num3` 逐值相同；
 * 绘制侧传 `extraAbove = 1` 再多要一排（同样落在画布外），于是
 * `rows(extraAbove = 1).drop(1) == rows(extraAbove = 0)`（行数不足以填满窗口、
 * 或已滚到 [maxOffset] 极限使顶部夹到 0 时，两支窗口重合、多要的那排拿不出来）。
 * 等价性读数钉在 `scroll/ViewportAuditTest` 与 `adapter/ConsoleAdapterTest`。
 *
 * 纯 JVM：无 Android API、无 `kotlin.random`、无静态可变状态；`refresh` 一类的节流
 * （`lastDrawnLineNo`，spec: `EmueraConsole.cs:1758`、`EmueraConsole.cs:1942-1949`）留给显示列表路，
 * 它只需要读 [atBottom]。
 */
class Viewport(heightLines: Int) {

    /** 可见行数（半角网格的行数，命中检测口径，spec: `EmueraConsole.cs:2328-2331`）。窗口 resize 时由绑定层改写，负值夹到 0；改动后重夹 [offsetFromEnd]，否则 [maxOffset] 随窗口变矮缩小时旧偏移会越界。 */
    var heightLines: Int = if (heightLines < 0) 0 else heightLines
        set(value) {
            field = if (value < 0) 0 else value
            clampOffset()
        }

    /** 滚动条 `Maximum` 口径 = 缓冲当前保留行数（spec: `EmueraConsole.cs:2554`、`EmueraConsole.cs:2563`）。 */
    var retainedLines: Int = 0
        private set

    /** 视口底部之下还压着的行数；0 表示贴底。上翻锁定就是「追加新行时这个数不变」。 */
    var offsetFromEnd: Int = 0
        private set

    /** 是否贴底 —— 原版 `window.ScrollBar.Value == window.ScrollBar.Maximum`（spec: `EmueraConsole.cs:1738`、`EmueraConsole.cs:1804`）。 */
    val atBottom: Boolean
        get() = offsetFromEnd == 0

    /**
     * 上翻的极限偏移 = `max(0, retainedLines - heightLines)`：滚到顶时 [bottomRingIndex]
     * 恰为 `heightLines - 1`、[topRingIndex] 夹到 0，显示保留区**最早的一屏**。
     * 原版上界是 `retainedLines` 本身（`Value == 0` ⇒ `num2 == -1` ⇒ 整屏不画一行，
     * spec: `EmueraConsole.cs:1806-1812`、`EmueraConsole.cs:1852`），不复刻这条观感缺陷的
     * 取舍见类文档「能上翻到哪里」。
     */
    val maxOffset: Int
        get() = (retainedLines - heightLines).coerceAtLeast(0)

    /** 缓冲非空时滚动条才启用（spec: `EmueraConsole.cs:2565`）。 */
    val scrollEnabled: Boolean
        get() = retainedLines > 0

    /**
     * 与缓冲对齐。行数没变早退；变多时 [offsetFromEnd] 守恒（原版的 `Value += num`）；
     * 变少时**只夹取、不归零** —— 这一支有意偏离原版 `verticalScrollBarUpdate`，取舍与出处见类文档
     * 「缩短不再拽回」。
     */
    fun sync(retainedLines: Int) {
        val num = retainedLines - this.retainedLines
        if (num == 0) {
            return
        }
        this.retainedLines = retainedLines
        // 原版 `num < 0` 把 Value 抬到 count（= 贴底，spec: EmueraConsole.cs:2557-2564）。本移植不能照抄：
        // retainedLines 数的是**折行后的显示行数**，游戏整屏重印时它天然会摆 —— QoL 地图/外出屏每
        // `ANIMATERECOLOREDMAPS` 毫秒 `CLEARLINE` + 重印一次（ERB/魔改内容/qol/qol_MAP.ERB:34-44），
        // 消息行/末尾空行的归属一变就少几行，归零 = 每个重印周期把上翻的读者扔回底部，
        // 地图顶部就再也看不到了（2026-10-29 真机反馈）。夹取照留：剩下的行不够一屏时
        // maxOffset 就是 0，自然回到唯一合法的贴底态。
        clampOffset()
    }

    /** [sync] 的 [LogBuffer] 版本。 */
    fun sync(buffer: LogBuffer) {
        sync(buffer.size)
    }

    /** 上翻 [lines] 行（`lines > 0` 生效，`lines < 0` 等同 [scrollDown]）。 */
    fun scrollUp(lines: Int) {
        shift(-(lines.toLong()))
    }

    /** 下移 [lines] 行；到 0 就停（贴底后继续下滚不会翻到最新行之下，对应 `Value` 夹到 `Maximum`）。 */
    fun scrollDown(lines: Int) {
        shift(lines.toLong())
    }

    /**
     * 滚轮：`delta > 0`（向上滚）= 上翻。步长 = `SmallChange * Config.ScrollHeight`，
     * 默认 1 行（spec: `MainWindow.cs:1262`、`ConfigData.cs:74`）。
     */
    fun onWheel(delta: Int, linesPerScroll: Int = LINES_PER_SCROLL_DEFAULT) {
        if (delta == 0) {
            return
        }
        shift(if (delta > 0) -linesPerScroll else linesPerScroll)
    }

    /** PageUp（spec: `MainWindow.cs:1379-1398` 的 `Keys.Prior`，固定 10 行）。 */
    fun pageUp(lines: Int = PAGE_STEP) {
        scrollUp(lines)
    }

    /** PageDown（spec: `MainWindow.cs:1379-1398` 的 `Keys.Next`，固定 10 行）。 */
    fun pageDown(lines: Int = PAGE_STEP) {
        scrollDown(lines)
    }

    /** 回贴底（原版在鼠标点击与按键两处无条件 `Value = Maximum`，spec: `MainWindow.cs:803`、`MainWindow.cs:1404`）。 */
    fun snapToBottom() {
        offsetFromEnd = 0
    }

    /**
     * 点击的贴底半边（原版 `MainWindow.cs:799-805`）：`:799` 先取
     * `flag = Value != Maximum`，`:800` 命中目标 `selectedString` 也先取好，
     * `:801-805` 才 `Value = Maximum` 并强刷 —— 即**命中用贴底前的视图、
     * 贴底无论是否命中**。本函数只做后一半：未贴底时归零偏移并返回 true
     * （调用方据此重算取行窗口/命中表并重绘），已在底部返回 false。
     * 按键半边的同构入口是 `InputSession.snapToBottomForKeyDown`
     * （spec: `MainWindow.cs:1402-1406`）。
     */
    fun snapToBottomForTap(): Boolean {
        if (offsetFromEnd == 0) {
            return false
        }
        offsetFromEnd = 0
        return true
    }

    /** 缓冲区清空 / `ClearDisplay` 后回到贴底初始态（spec: `EmueraConsole.cs:2801-2813` 末尾的 `verticalScrollBarUpdate`）。 */
    fun onCleared() {
        retainedLines = 0
        offsetFromEnd = 0
    }

    /** 反算 WinForms `ScrollBar.Value`（供真机绑定与调试面板对照，spec: `EmueraConsole.cs:2546` 的 `Maximum`）。 */
    fun scrollBarValue(retainedLines: Int = this.retainedLines): Int {
        val v = retainedLines - offsetFromEnd
        return if (v < 0) 0 else if (v > retainedLines) retainedLines else v
    }

    /**
     * 视口底部的**保留区下标**（0 基，对应原版 `num2 = Value - 1` 并夹到 `Count - 1`，
     * spec: `EmueraConsole.cs:1788-1794`）。`retainedLines == 0` 或上翻到空时返回 -1。
     */
    fun bottomRingIndex(retainedLines: Int = this.retainedLines): Int {
        var num2 = scrollBarValue(retainedLines) - 1
        if (retainedLines - 1 < num2) {
            num2 = retainedLines - 1
        }
        return num2
    }

    /**
     * 视口顶部的**保留区下标**（对应 `num3 = num2 - (可见高 / 行高 + 1)` 再 `if (num3 < 0) num3 = 0`，
     * spec: `EmueraConsole.cs:1805-1811`）。[extraAbove] 用于绘制侧多读的溢出行。
     */
    fun topRingIndex(retainedLines: Int = this.retainedLines, extraAbove: Int = 0): Int {
        val num2 = bottomRingIndex(retainedLines)
        if (num2 < 0) {
            return 0
        }
        val num3 = num2 - heightLines + 1 - (if (extraAbove < 0) 0 else extraAbove)
        return if (num3 < 0) 0 else num3
    }

    /**
     * 可见绝对行号区间（闭区间；[LongRange.isEmpty] 表示视口内没有任何行）。
     * 上下界都夹在保留区内（行号从 [LogBuffer.firstIndex] 起、共 `retainedLines` 行）。
     */
    fun visibleRange(retainedLines: Int, firstIndex: Long, extraAbove: Int = 0): LongRange {
        val top = topRingIndex(retainedLines, extraAbove)
        val bottom = bottomRingIndex(retainedLines)
        if (bottom < 0 || bottom < top) {
            return 1L..0L
        }
        return (firstIndex + top)..(firstIndex + bottom)
    }

    /** [visibleRange] 的 [LogBuffer] 版本。 */
    fun visibleRange(buffer: LogBuffer, extraAbove: Int = 0): LongRange =
        visibleRange(buffer.size, buffer.firstIndex, extraAbove)

    /**
     * 视口内应当绘制的行（按行号升序）。空视口（上翻到 `Value == 0` 那种极限）返回空表。
     * 读法与原版绘制循环同口径：`for (i = num3; i <= num2 && i < displayLineList.Count; i++)`
     * （spec: `EmueraConsole.cs:1852`）。
     */
    fun rows(buffer: LogBuffer, extraAbove: Int = 0): List<DisplayRow> {
        val range = visibleRange(buffer, extraAbove)
        if (range.isEmpty()) {
            return emptyList()
        }
        return buffer.rowsAt(range.first, (range.last - range.first + 1).toInt())
    }

    private fun shift(lines: Int) {
        shift(lines.toLong())
    }

    /**
     * `Value += num` 的等价写法：`num > 0` 向下（偏移变小）、`num < 0` 向上（偏移变大）。
     * 用 Long 中间量，避免调用方传 `Int.MIN_VALUE` 这类极端步长时溢出。
     */
    private fun shift(lines: Long) {
        if (lines == 0L) {
            return
        }
        val next = offsetFromEnd.toLong() - lines
        offsetFromEnd = if (next < 0L) 0 else if (next > maxOffset.toLong()) maxOffset else next.toInt()
    }

    /** 夹到 `[0, maxOffset]`：下界对应 WinForms 的 `Minimum`，上界按平台最优收到最早一屏（类文档「能上翻到哪里」）。 */
    private fun clampOffset() {
        if (offsetFromEnd < 0) {
            offsetFromEnd = 0
        }
        if (offsetFromEnd > maxOffset) {
            offsetFromEnd = maxOffset
        }
    }

    companion object {
        /** `SCROLLHEIGHT` 默认 1 行（spec: `ConfigData.cs:74`，"Lines per scroll"）。 */
        const val LINES_PER_SCROLL_DEFAULT = 1

        /** PageUp / PageDown 的固定步长 10 行（spec: `MainWindow.cs:1381`，`int num2 = 10`）。 */
        const val PAGE_STEP = 10
    }
}
