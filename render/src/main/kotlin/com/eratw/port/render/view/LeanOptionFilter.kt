package com.eratw.port.render.view

import com.eratw.port.core.host.ConsoleOption

/**
 * 右侧选项栏的「更精简的游戏选项」过滤器：只保留标签末尾带数字标签的常用指令。
 *
 * ## 为什么按尾标签筛
 * 本作的常用指令统一由 `HTML_PRINTC` 印成「名字[值]」：语料里
 * `USERCOM_コマンド表示処理.ERB:124` 的 `@"移动[400]"`、`:151` 的 `@"散步[604]"`、
 * `:190` 的 `@"污渍显示[801]"` 都是这个形状；而设置/系统类按钮多是
 * `PRINTBUTTON_EX("[CONFIG]",809)`（同文件 `:40`）这类不带尾标签的字。
 * 于是「标签末尾是 `[数字]`」就是区分常用指令与其它按钮的稳定特征。
 *
 * ## 边界
 * - 必须**有名字在前**：裸 `[31]`（纯数字自动按钮、或前缀被剥空的按钮）不算常用指令；
 * - 数字标签必须收尾：`第[1]章` 不匹配；标签与括号间的空白允许（`会话 [31]`）；
 * - 空标签不匹配。
 *
 * ## 不碰原有识别
 * 这是**纯显示层**策略：只在开关打开时裁剪右侧栏要渲染的候选；可点性仍由引擎的
 * 世代 + 等待类型决定（`LineBufferConsoleSink.currentOptions`），正文里的按钮命中
 * （[com.eratw.port.render.button.ButtonLayer]）也一个字节都不动。
 */
object LeanOptionFilter {

    /**
     * 末尾数字标签：`名字[31]`、`移动[400]`。
     *
     * `\S` 打头保证括号前至少有一个非空白字符（排除裸 `[31]`）；`\d+` 不设上限，
     * 因为语料里的标签值有 4 位（`[778]`）甚至更长。
     */
    private val TRAILING_TAG = Regex("""\S.*\[\s*\d+\s*\]\s*$""")

    /** 只保留标签末尾带数字标签的候选，顺序与其余字段原样保留。 */
    fun keepCommon(options: List<ConsoleOption>): List<ConsoleOption> =
        options.filter { isCommon(it.label) }

    /** 单条判定：标签末尾是否有「名字[数字]」形态的尾标签。 */
    fun isCommon(label: String): Boolean = TRAILING_TAG.matches(label)
}
