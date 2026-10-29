package com.eratw.port.core.host

/**
 * 文字流桥接适配器（契约 C6 的实现体，[com.eratw.port.core.proc.ProcessRuntime.consoleBridge]）：
 * **一次调用、三个去向**——镜像 [mirror]（= ProcessRuntime.output）、原始 print 回调
 * [rawSink]（printSink，历史契约保留）、宿主 [delegate]（consoleSink）。
 * 语义只在这里定义一份；VM `_PRINT_*` 与 sem 语句都流经本对象，不存在第二套输出路径。
 *
 * 镜像规则（纯文本口径）：文本类动作原样入镜像（print/printLine/newLine/...）；
 * 样式/删除/重绘类动作（setForeColor、deleteLine、setRedraw、HTML）不入镜像——
 * 它们是宿主渲染语义，文本对拍口径归 A6 的 Fake ConsoleSink + runbook 归一化，
 * 桥接器不擅自渲染分隔线宽度（[printBar] / [printCustomBar] 只透传）。
 * 镜像有 [maxMirrorChars] 封顶（超限即停写，一次性封顶不做环形）：镜像只为测试
 * 对账存在（生产路径零读者），无界累积会在长跑局里 OOM；控制台的滚动缓冲才是
 * 权威输出来源，封顶不改变任何脚本可见行为。
 * TODO(verify): `output` 与 PC 基准文本对拍时，bar/换行等待动作是否需要在镜像里
 * 物化为固定宽度字符（现口径：不需要，对拍走宿主 sink）。
 *
 * 搬移注记（2026-10-24，波次 ARCH1 / arch1-rt-bridge）：本类连同 KDoc 自
 * `ProcessRuntime.kt` 文件尾原样搬入 host 包——它是 [ConsoleSink] 的委托桥实现，
 * 与 [LineBufferConsoleSink] 等 sink 实现同住一层；纯搬移，类名/成员/行为零变化。
 * 原为 ProcessRuntime.kt 文件级 private，跨文件外移的最小可见性放宽为 internal：
 * 唯一调用方 ProcessRuntime 仍在本模块，不进 core-vm 公共 API 面。
 */
internal class ConsoleBridge(
    private val delegate: ConsoleSink?,
    private val rawSink: ((String) -> Unit)?,
    private val mirror: StringBuilder,
    private val maxMirrorChars: Long,
) : ConsoleSink {
    /** LINECOUNT 由宿主 sink 记账，桥接器只转发（契约 C6：语义唯一在宿主）。 */
    override val lineCount: Long get() = delegate?.lineCount ?: 0L

    /** 当前字体样式（FONTBOLD/FONTITALIC 的 OR 基值）由宿主 sink 记账，只转发。 */
    override val fontStyle: Int get() = delegate?.fontStyle ?: 0

    /** 打印缓冲空态由宿主 sink 记账，桥接器只转发（同 [lineCount] 契约）。 */
    override val printingBufferEmpty: Boolean get() = delegate?.printingBufferEmpty ?: true

    /**
     * 当前前景色由宿主 sink 记账，桥接器只转发。PRINT_RECT 在执行时点取它
     * （GFX-05；原版 `PrintShape(..., userStyle.Color, ...)`，EmueraConsole.cs:3197-3199）；
     * 接口缺省 null = 未显式 SETCOLOR，语句层回落 Config.ForeColor（ConfigData.cs:68）。
     */
    override val foregroundColor: Int? get() = delegate?.foregroundColor

    /**
     * 镜像的唯一写入口：未到 [maxMirrorChars] 上限时追加，超限后一次性停写。
     * 所有 `mirror.append` 必须收口到这里——防止某个调用点漏检把封顶打穿。
     */
    private fun mirrorAppend(text: String) {
        if (mirror.length < maxMirrorChars) mirror.append(text)
    }

    override fun print(text: String) = print(text, lineEnd = true)

    override fun print(text: String, lineEnd: Boolean) {
        mirrorAppend(text)
        rawSink?.invoke(text)
        delegate?.print(text, lineEnd)
    }

    override fun printLine(text: String) {
        mirrorAppend(text)
        mirrorAppend("\n")
        delegate?.printLine(text)
    }

    override fun printC(text: String, alignRight: Boolean) {
        mirrorAppend(text)
        delegate?.printC(text, alignRight)
    }

    override fun printSingleLine(text: String) {
        mirrorAppend(text)
        mirrorAppend("\n")
        delegate?.printSingleLine(text)
    }

    override fun printButton(text: String, value: Long) {
        mirrorAppend(text)
        delegate?.printButton(text, value)
    }

    override fun printButton(text: String, value: String) {
        mirrorAppend(text)
        delegate?.printButton(text, value)
    }

    override fun printButtonC(text: String, value: Long, alignRight: Boolean) {
        mirrorAppend(text)
        delegate?.printButtonC(text, value, alignRight)
    }

    override fun printButtonC(text: String, value: String, alignRight: Boolean) {
        mirrorAppend(text)
        delegate?.printButtonC(text, value, alignRight)
    }

    override fun printPlain(text: String) {
        mirrorAppend(text)
        delegate?.printPlain(text)
    }

    override fun printTemporaryLine(text: String) {
        mirrorAppend(text)
        delegate?.printTemporaryLine(text)
    }

    override fun printBar() {
        delegate?.printBar()
    }

    override fun printCustomBar(text: String) {
        delegate?.printCustomBar(text)
    }

    override fun newLine() {
        mirrorAppend("\n")
        delegate?.newLine()
    }

    override fun deleteLine(count: Int) {
        delegate?.deleteLine(count)
    }

    override fun clearText() {
        delegate?.clearText()
    }

    /**
     * 输入等待通知必须转发（[ConsoleSink.noteInputWait] 的缺省实现是 no-op）：
     * sem 的 `ctx.console` 是本桥接器，右侧选项快照的真正冻结在宿主 sink 里。
     * 不转发时真机表现为「等待 INT 但 `chips` 恒空」——2026-10-25 真机实测的标题页漏识别。
     */
    override fun noteInputWait(request: InputRequest) {
        delegate?.noteInputWait(request)
    }

    override fun setForeColor(r: Int, g: Int, b: Int) {
        delegate?.setForeColor(r, g, b)
    }

    override fun setBackColor(r: Int, g: Int, b: Int) {
        delegate?.setBackColor(r, g, b)
    }

    override fun resetForeColor() {
        delegate?.resetForeColor()
    }

    override fun resetBackColor() {
        delegate?.resetBackColor()
    }

    /** D 系打印取色开关必须转发：真 sink 的取色状态只有一份（ConsoleSink.setUseSetColorStyle 的 KDoc）。 */
    override fun setUseSetColorStyle(value: Boolean) {
        delegate?.setUseSetColorStyle(value)
    }

    /**
     * 系统行开关同样必须转发：它与 [setUseSetColorStyle] 是 `Style` 取值器的两条轴
     * （`EmueraConsole.cs:471-489`），状态只允许存在于真 sink 一份
     * （[ConsoleSink.setUseUserStyle] 的 KDoc）。
     */
    override fun setUseUserStyle(value: Boolean) {
        delegate?.setUseUserStyle(value)
    }

    override fun setFontStyle(mask: Int) {
        delegate?.setFontStyle(mask)
    }

    override fun setFont(name: String) {
        delegate?.setFont(name)
    }

    override fun setAlignment(kind: Alignment) {
        delegate?.setAlignment(kind)
    }

    override fun setRedraw(mode: Int) {
        delegate?.setRedraw(mode)
    }

    /**
     * 帧发布闸必须显式转发（[ConsoleSink.publishFrame] 的缺省实现是 no-op）：
     * `ctx.console` 在 sem 层经本桥接器落到宿主 sink，sem 的让出边界
     * （Companion 里 `waitInput` 等语句的让出边界）调的是桥接器；
     * 不转发则闸一旦被剧本 `REDRAW 0` 关上就再也不会解冻，UI 永久停在旧帧。
     */
    override fun publishFrame() {
        delegate?.publishFrame()
    }

    /** 同 [publishFrame]：让出返回后重新冻结，见 [ConsoleSink.holdFrame]。 */
    override fun holdFrame() {
        delegate?.holdFrame()
    }

    override fun printHtml(text: String, option: Boolean) {
        delegate?.printHtml(text, option)
    }

    override fun addHtmlIsland(text: String) {
        delegate?.addHtmlIsland(text)
    }

    override fun clearHtmlIslands() {
        delegate?.clearHtmlIslands()
    }

    override fun printError(text: String) {
        mirrorAppend(text)
        mirrorAppend("\n")
        delegate?.printError(text)
    }
}
