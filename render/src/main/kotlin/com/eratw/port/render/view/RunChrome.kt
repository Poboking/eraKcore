package com.eratw.port.render.view

import com.eratw.port.render.RunKind
import com.eratw.port.render.displaylist.DrawCmd

/**
 * 一条 run 的「底色决策」——**两个渲染器共用这一份**。
 *
 * 存在的理由（2026-10-24 真机缺陷）：Android 侧 `CanvasConsoleView.drawCmd` 曾无条件用
 * [DrawCmd.back] 铺矩形，而 `TextRun.back` 这个槽对**按钮段**装的是原版 `ButtonColor`
 * （焦点字色，见 `adapter/ConsoleAdapter.runOf` 的 KDoc），不是背景色 ⇒ 语料里
 * `CALL COLORMESSAGE(..., 0x70C070)` 这种只 `SETCOLOR` 的选项行被整条刷成黄底/粉底
 * （用户截图实证）。纯 JVM 的 [ScreenComposer] 反过来**完全不读** `back`，两个渲染器语义漂移。
 *
 * 口径（对齐原版）：
 * * 按钮段：非按下态**不铺底色**（原版按钮就是普通文字，按下才高亮）；
 * * 按钮段按下：[PRESSED_BACK] 半透明白，`MainWindow` 的按下观感；
 * * 非按钮段：[DrawCmd.back] 非空才铺——**div 已不走这里**（CONTRACT-HTMLDIV-B1 D4
 *   撤销「染整行」近似：div 的 `color` 是覆盖块矩形底色，随 `RenderLine.divBlocks` 走
 *   `adapter/DivOverlayPlanner`，原版染的是 `ConsoleDivPart.DrawTo` 的块矩形，
 *   ConsoleDivPart.cs:186 的 `DrawBorder` 填充；sink 对 div 不再写行底色，生产 run 的
 *   back 槽恒 null）。`SETBGCOLOR` 不走 run 底色：原版背景是
 *   控制台级活值 `bgColor`（EmueraConsole.cs:200），每帧 `graph.Clear(bgColor)` 清整张画布
 *   （`:1816`），由 `ConsoleScreen.backgroundArgb` 整幅铺底（2026-11 玩家反馈「时停只有
 *   文字背景变色」的修复：行快照底色 + 硬编码黑画布两个偏差一并拆掉）。
 *
 * 纯 JVM、无 Android API、无静态可变状态：本文件就是为了能在 `:render` 的单测里直接断言。
 */
object RunChrome {

    /** 按下态底色：半透明白（`0x66FFFFFF`），与 `CanvasConsoleView.BUTTON_PRESSED_BACK` 同值。 */
    const val PRESSED_BACK: Int = 0x66FFFFFF

    /**
     * 这一条 run 该铺什么底色（ARGB，已补不透明位）。
     *
     * @param cmd 绘制命令
     * @param pressed 是否被按下（命中层给出的按下态）
     * @return null = **不铺底色**（保留画布原样）
     */
    fun backgroundFill(cmd: DrawCmd, pressed: Boolean): Int? = when {
        pressed -> PRESSED_BACK
        // 按钮段：back 槽里是 ButtonColor（焦点字色），绝不能当底色用。
        cmd.kind == RunKind.BUTTON -> null
        // 非按钮段：只有 HTML div 的行染矩形（back 非空）才铺；普通文本段落在整屏底色上。
        else -> cmd.back?.let(::opaque)
    }

    /** `0xRRGGBB` → 不透明 ARGB（与原版 GDI+ 的 `Color.FromArgb` 同口径）。 */
    fun opaque(rgb: Int): Int = (0xFF shl 24) or (rgb and 0xFFFFFF)
}
