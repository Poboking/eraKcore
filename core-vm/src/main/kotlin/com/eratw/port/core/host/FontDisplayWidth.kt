package com.eratw.port.core.host

/**
 * **字体感知的显示宽度**（半角格）—— `SETFONT` 在排版侧的唯一落点。
 *
 * ## 为什么要有这个契约
 *
 * 原版按当前段的字体用 GDI+ 实测像素宽逐段累加定位
 * （`ConsoleStyledString.cs:25,82` 的 `Font = GetFont(style.Fontname)` +
 * `Width = sm.GetDisplayLength(Text, Font)`，`ConsoleDisplayLine.cs:66` 的 `num += node.Width`；
 * `StringMeasure.cs:42-72`）。本端口此前把宽度钉成一张**与字体无关**的静态 CP932 格数表
 * （[CellWidth.displayCodePoint] / `render` 的 `Grid.cells`），真机绘制再用
 * `Paint.textScaleX` 把字形横向拉伸塞进那个格子 ⇒ `SETFONT` 只换了字形长相、没换排版尺。
 * 玩家反馈的两处字符画错位都由此而来：
 * - 老虎机 `SETFONT "MS Gothic"` 的块元素 `▂`/`▉` 在 CP932 表里是「表外字」⇒ 2 格，
 *   随包等宽字体的 hmtx 给的是半角 ⇒ 实际 1 格；82 字符上框被算成 164 格还会被误折行；
 * - 觉的 AA 画 `SETFONT "Saitamaar"` 是比例字体，靠 `U+2009`/`U+200A`/`:` 做像素级微调，
 *   静态表把这些窄字符放大 2.7~16 倍，各行膨胀量不同 ⇒ 整幅画散架。
 *
 * ## 放在 `:core-vm` 的理由
 *
 * 依赖方向是 `:render -> :core-vm`（单向）。核心层 [LineBufferConsoleSink] 的折行是
 * **第一关**：若它先按静态表把 82 个 `▂` 折成两行，渲染层拿到断行后的段就再也拼不回原画。
 * 所以「同一把尺」必须能从 core 层一路递到 `LineFolder` / `DisplayList` / `ButtonLayer`
 * 与真机逐字形绘制，而 core 不许反向 import `render` 的 `FontWidthRuler` ⇒ 契约落在这里，
 * 由 `:render` 的尺实现，装配期由宿主**注入同一实例**给两侧。
 *
 * ## 单位与口径
 *
 * 两条入口都给**整数半角格**（折行预算、`DisplayRow.cells`、`DrawCmd.colStart/colEnd`、
 * `HitTarget` 列区间在契约里都是 `Int`）。真实 advance 的浮点格与逐字形像素推进是
 * `:render` 侧 `FontWidthRuler.advanceCells` 的事，不进本契约；整串入口必须「浮点累加后
 * **一次**取整」，不许逐码位取整（比例字体 0.12 格的字会被逐字抹成 0）。
 *
 * TAB 与增补平面的静态读数必须与 [CellWidth.displayCells] 同值：TAB = 8 格、
 * 增补平面一个码位 = 4 格、孤立代理项半 = 2 格。静态实现逐值等价于接线前的
 * `Grid.cells`（后者逐 UTF-16 码元累加，合法代理对 2 + 2 = 4），所以把前缀扫描从
 * 「逐码元」换成「逐码位」不会改变任何既有装置的读数。
 *
 * ## 退化纪律
 *
 * 未注入（缺省 [StaticFontDisplayWidth]）、sidecar 缺表、或该码位缺行时，一律回落到
 * [CellWidth] 的静态网格，**不猜系统字体宽度**（GFX-12 的确定性口径）。
 * headless、黄金基准与所有未接表的装置因此与接线前**逐值不变**。
 */
interface FontDisplayWidth {

    /** 单个码位在 [fontName] 下占的整数半角格。 */
    fun codePointCells(codePoint: Int, fontName: String): Int

    /** 整串文本在 [fontName] 下占的整数半角格（整串一次取整）。 */
    fun cells(text: String, fontName: String): Int
}

/**
 * 接线前的静态 CP932 网格尺：忽略字体名，读数与 [CellWidth] 的唯一表同源。
 *
 * 这是**缺省值而非补丁**：所有既有构造点不显式注入字体尺时走它，逐值不变；
 * 只有真机装配（宿主接了随包 sidecar）才换成 `:render` 的字体感知实现。
 */
object StaticFontDisplayWidth : FontDisplayWidth {

    override fun codePointCells(codePoint: Int, fontName: String): Int =
        CellWidth.displayCodePoint(codePoint)

    override fun cells(text: String, fontName: String): Int =
        CellWidth.displayCells(text)
}
