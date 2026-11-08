package com.eratw.port.render.view

import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.paletteSwatchMask

/**
 * 右侧选项栏的「更精简的游戏选项」过滤器：只保留**屏上自带编号**的常用指令。
 *
 * ## 为什么按「按钮自己印的编号」筛
 * 本作的常用指令都把编号印在按钮上，但**编号写在名字的哪一侧由各处代码自己定**，两种形态并存：
 * - 编号在末尾：`HTML_PRINTC` 的指令栏，语料 `USERCOM_コマンド表示処理.ERB:124` 的
 *   `@"移动[400]"`、`:151` 的 `@"散步[604]"`、`:190` 的 `@"污渍显示[801]"`；
 * - 编号在开头：菜单/列表的 `[编号] - 名称`，语料 `SHOP関連/SHOP.ERB:201` 的
 *   `[400] - OPTION`、`SHOP関連/TUTORIAL.ERB:130` 的 `[216] - 特殊区域移动`、
 *   `魔改内容/qol/qol_MAP.ERB:152-176` 的移动目的地清单，以及 2026-10-31 真机读数的
 *   主页面整屏 24 枚（`[100] - 睁开眼睛` 一族）。
 * 反过来，**不带编号的字**屏上没有自己的编号：`PRINTBUTTON_EX("[CONFIG]",809)`
 * （`USERCOM_コマンド表示処理.ERB:40`，括号里不是数字）、主页面的 `[自宅位置]`。
 * 于是「这颗按钮在屏上印了自己的编号」就是区分常用指令与其它按钮的稳定特征 —— 玩家也正是
 * 这么称呼它们的（「400 移动」「100 睁开眼睛」）。
 *
 * 注意别按**清洗后的标签**倒推形态：标题页屏上印的是 `[0] 开始游戏`（编号在开头），
 * 剥完只剩 `开始游戏` —— 它属于「编号在开头」那一类、该进栏；判「屏上有没有自己的编号」
 * 只有引擎那句 [ConsoleOption.printsOwnNumber] 可靠，见下节。
 *
 * ## 两种形态为什么不能只看标签
 * 编号写在开头时，引擎出快照会把它剥下来交给徽标显示（口径见
 * `LineBufferConsoleSink` 的 `describeOption`），到显示端标签只剩 `- 睁开眼睛` ——
 * **只看标签文本永远认不出这一族**（2026-10-31 玩家反馈「右边选项里经常找不到 400 移动，
 * 经常得直接在左边点」的根因：主页面 28 枚候选按旧口径全被剔，右栏整屏空）。
 * 所以这条判据要引擎搭一句 [ConsoleOption.printsOwnNumber]，末尾形态仍由本类的文本规则认。
 *
 * ## 边界
 * - 剥完编号必须**还有名字**：裸 `[31]`（纯数字自动按钮、或前缀被剥空的按钮）不算常用指令；
 * - 末尾形态只认收尾的数字标签：`第[1]章` 不匹配；标签与括号间的空白允许（`会话 [31]`）；
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

    /**
     * 只保留屏上自带编号的常用指令，顺序与其余字段原样保留。
     *
     * 调色板不是「常用指令」，但它也不是普通无编号按钮：这里用 core 的
     * [paletteSwatchMask] 只保住**连续 8 枚以上**的真实调色板色块，
     * 单个彩色 `■` 仍按普通无编号按钮裁掉。
     */
    fun keepCommon(options: List<ConsoleOption>): List<ConsoleOption> {
        val palette = paletteSwatchMask(options)
        return options.filterIndexed { index, option ->
            palette[index] || isCommonCommand(option)
        }
    }

    /**
     * 单条候选判定：屏上自带编号（末尾形态看标签，开头形态看引擎那句 [ConsoleOption.printsOwnNumber]），
     * 且还有名字。
     *
     * 调色板色块不在这里判：`■` 没有名字也没有编号，但真正调色板由 [keepCommon] 通过
     * [paletteSwatchMask] 单独保留；本函数只回答「是不是常用指令」。
     */
    fun isCommonCommand(option: ConsoleOption): Boolean =
        option.label.isNotBlank() && (hasTrailingNumberTag(option.label) || option.printsOwnNumber)

    /** 单条标签文本判定：标签末尾是否有「名字[数字]」形态的尾标签。 */
    fun hasTrailingNumberTag(label: String): Boolean = TRAILING_TAG.matches(label)
}
