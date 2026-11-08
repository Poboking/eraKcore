package com.eratw.port.core.proc

import com.eratw.port.core.fn.BuiltinHost

/**
 * 会话期**屏幕像素**读数（LIVE-REFLOW）：`CLIENTWIDTH()` / `CLIENTHEIGHT()` 的活数据源。
 *
 * ## 为什么要有这一格
 *
 * 原版这两个函数读的是窗口控件的**活尺寸**：`EmueraConsole.ClientWidth => window.MainPicBox.Width`
 * （EmueraConsole.cs:232）、`ClientHeight => window.MainPicBox.Height`（:234），函数入口
 * `ClientSizeMethod` 每次调用现取（FunctionMethodCreator.cs:6777-6799，CLIENTWIDTH :6797 /
 * CLIENTHEIGHT :6793），从不缓存 —— 窗口一改大小，脚本下一次读数就是新值。移植层此前把两条
 * 像素冻在 [ConsoleBuiltinHost] 的构造参数里（会话期只进不出），于是「会话期改窗口尺寸」这一
 * 动作对脚本永远不可见。本类把「活尺寸」这一格补回来：写方是
 * [GameSession.resizeConsole]（UI 线程，窗口尺寸变化回调），读方是函数层的
 * [BuiltinHost.clientWidth] / [BuiltinHost.clientHeight]（VM 线程，见
 * [ConsoleBuiltinHost] 的自定义 getter）。
 *
 * ## 单位：脚本 px，不是设备 px
 *
 * 语料把 `CLIENTWIDTH()` / `CLIENTHEIGHT()` 的结果当**脚本 px** 用：CG 画布拿它开尺寸
 * （`QOL_IMAGE.ERB` 的 `GCREATE BG_ID, 图片长度, 图片高度`）、div 的 `width` 直接写进几何。
 * 而渲染层把脚本 px 折成设备像素时乘的是 `设备字号 / 配置字号`
 * （`CellMetrics.scriptPixels`），所以脚本可见的读数必须是同一把尺的另一端 —— 设备内容区像素
 * **除以**同一个比例。把设备像素原样交给脚本等于让渲染层再乘一次：整幅背景图被放大
 * `设备字号 / 配置字号` 倍（真机横屏约 1.83 倍），玩家看到的就是「背景只剩左上角一块」。
 *
 * [widthPx] / [heightPx] 仍是**设备像素**（宿主实测、[toString] 诊断口径），折算落在
 * [scriptWidthPx] / [scriptHeightPx]；函数层经 [ConsoleBuiltinHost] 读后两者。脚本字号
 * 由调用方按会话常量传入（宿主侧的 `fontSize` = 生效 `Config.FontSize`），本类只存会随
 * 视口变化的设备字号 —— 两档字号同存一份可变副本是多余的第二个真相源。
 * 两个字号任一未注入（<= 0）或彼此相等时折算为恒等 ⇒ headless / 既有装置的读数逐字不变。
 *
 * ## 线程
 *
 * 各字段 `@Volatile`：写方 UI 线程、读方 VM 线程，原版两边都在 UI 线程摸同一个控件、
 * 没有可见性问题，移植侧必须显式声明跨线程可见（同 `ConsoleTextBox.text` 的口径）。
 *
 * **像素两格不构成原子对**：读者可能看到「新宽 + 旧高」的一瞬组合。[ScriptScreenState.update]
 * 按先宽后高两次写，中间没有锁。这与原版同形 —— 原版 `ClientWidth` / `ClientHeight`
 * 也是两个独立属性，两次读之间窗口同样可能变；而且 `CLIENTWIDTH()` 与 `CLIENTHEIGHT()`
 * 是两个**独立的函数调用**，各自只读一格，所以不存在「一次求值需要成对快照」的消费面。
 * 不去为此上锁：锁会把 VM 线程的读数变成临界区，而我们换不来任何可观察的差异。
 *
 * ## 缺省值
 *
 * 取原版出厂窗口（WindowX 760 / WindowY - LineHeight 461，spec: ConfigData.cs:56,57,67）：
 * 未写过时与 [BuiltinHost.DEFAULT_CLIENT_WIDTH] / [BuiltinHost.DEFAULT_CLIENT_HEIGHT] 同值。
 * 本类只存**设备**字号（缺省 0 = 未知），脚本字号逐次由取数侧传入（缺省 = 生效配置字号，
 * 不是 0）；两侧任一 <= 0 时折算恒等，因此 headless 装置与真机缺省装配的读数与
 * 「没有本类」时逐字相同。
 *
 * @param widthPx 设备内容区宽（像素；不是折行的半角格数，也不是脚本 px）
 * @param heightPx 设备内容区高（像素）
 * @param deviceFontPixelSize 渲染层解算出的**设备像素字号**（`ScreenLayout.resolve` 的
 *   `fontPixelSize`）；0 = 未知，折算为恒等
 */
class ScriptScreenState(
    @Volatile var widthPx: Long = BuiltinHost.DEFAULT_CLIENT_WIDTH,
    @Volatile var heightPx: Long = BuiltinHost.DEFAULT_CLIENT_HEIGHT,
    @Volatile var deviceFontPixelSize: Int = 0,
) {

    /**
     * `CLIENTWIDTH()` 的读数（脚本 px）：设备内容宽按字尺折回脚本坐标系。
     *
     * @param scriptFontPixelSize 脚本坐标系的基准字号（会话生效 `Config.FontSize`，
     *   由宿主按会话常量传入）；<= 0 或与设备字号相等 = 恒等
     */
    fun scriptWidthPx(scriptFontPixelSize: Int): Long =
        scriptPx(widthPx, scriptFontPixelSize)

    /** `CLIENTHEIGHT()` 的读数（脚本 px）；口径同 [scriptWidthPx]。 */
    fun scriptHeightPx(scriptFontPixelSize: Int): Long =
        scriptPx(heightPx, scriptFontPixelSize)

    /**
     * 任一段**设备**像素（宽 / 高 / 指针坐标及其位移量）→ 脚本 px，折算比例同 [scriptWidthPx]。
     *
     * 专门给**有符号**坐标用（`MOUSEX()` / `MOUSEY()`）：[ScriptPixels] 的取整恒朝 +∞
     * （-299.45 → -299，即负数向零、正数向外），与「补满而不留缝」同一口径；宽度/高度
     * 两个入口也走这里，全场只有一份折算实现。
     */
    fun scriptPx(devicePx: Long, scriptFontPixelSize: Int): Long =
        ScriptPixels.toScriptPx(devicePx, deviceFontPixelSize, scriptFontPixelSize)

    /**
     * 成对写入两条像素读数（先宽后高）。
     *
     * 只做赋值，不做任何时间/宽度比较：「尺寸到底变没变」由 [GameSession.resizeConsole]
     * 的 `console.resize(columns)` 返回值回答（宽度判据在 sink 手里，见
     * `LineBufferConsoleSink.resize` 的 KDoc），这里不另开第二份判据。
     *
     * @param deviceFontPixelSize 新的设备字号；null（缺省）= 沿用现值。视口回调每次都会带上
     *   本次版面解算出的字号（转屏会换字号档），既有调用方（headless 装置）只传两个像素。
     */
    fun update(widthPx: Long, heightPx: Long, deviceFontPixelSize: Int? = null) {
        this.widthPx = widthPx
        this.heightPx = heightPx
        if (deviceFontPixelSize != null) this.deviceFontPixelSize = deviceFontPixelSize
    }

    /** 诊断用读数（DevTools / 日志）：设备像素的 `宽 x 高`（与折算后的脚本读数分开看）。 */
    override fun toString(): String = "${widthPx}x${heightPx}"
}

/**
 * 设备像素 ↔ 脚本 px 的字尺折算（`ScriptScreenState` 的唯一实现）。
 *
 * 与渲染侧 `CellMetrics.scriptPixels` 互逆：后者把脚本 px 折设备像素时算
 * `px * deviceFont / scriptFont`（Long 整除、**向零截断**，非零被截成 0 时保号抬到 ±1）。
 * 本函数算反向，**向上取整**（恒朝 +∞：-299.45 → -299，负数即向零）：
 *
 * - 取整方向选上而不是下：脚本拿读数去开整幅画布（`GCREATE`）再按同比例画回屏上，
 *   向下取整会让整幅图比内容区少 1~2 设备像素，右/下沿留一条缝；
 * - 反向除法的误差是 1 个脚本 px（横屏约 1.8 设备像素），远小于旧口径的整倍放大。
 *
 * 负输入按有符号向上取整（`Math.floorDiv`），因为 `MOUSEY()` 在客户区内恒为负
 * （`EmueraConsole.cs:2264` 的 `result.Y -= ClientHeight`）：截断除法的 `(n + d - 1) / d`
 * 在负数上会取到 floor，边界两侧就各偏 1 px。
 *
 * 任一字号 <= 0（未注入）或两者相等（headless / 1:1 版面）时**恒等** —— 这是生产之外
 * 全部既有装置（PC 基准、桌面 vmdbg、单元测试）读数逐字不变的保证。
 */
internal object ScriptPixels {

    fun toScriptPx(devicePx: Long, deviceFontPixelSize: Int, scriptFontPixelSize: Int): Long {
        if (deviceFontPixelSize <= 0 || scriptFontPixelSize <= 0) return devicePx
        if (deviceFontPixelSize == scriptFontPixelSize) return devicePx
        val numerator = devicePx * scriptFontPixelSize.toLong()
        val denominator = deviceFontPixelSize.toLong()
        return Math.floorDiv(numerator + denominator - 1L, denominator)
    }
}
