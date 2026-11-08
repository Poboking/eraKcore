package com.eratw.port.render.view

import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.OptionOrigin
import com.eratw.port.core.host.OptionVisual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LeanOptionFilter] 的口径钉子（2026-10-27 立，2026-10-31 按玩家反馈补「编号在开头」那一形）：
 *
 * 开关打开时，右侧栏只留**屏上自带编号**的常用指令；关闭时调用方根本不走这里，
 * 所以本用例只钉过滤本身：正例必须逐条保留、反例必须逐条剔除。
 *
 * 两种形态都要认（真语料实测）：
 * - 编号在末尾 `移动[400]`：标签原文带着走，纯文本规则就认得出；
 * - 编号在开头 `[100] - 睁开眼睛`：引擎出快照时把编号剥给徽标了，标签只剩 `- 睁开眼睛`，
 *   必须靠 [ConsoleOption.printsOwnNumber] 才认得出 —— 玩家反馈「右边选项里经常找不到
 *   400 移动，经常得直接在左边点」缺的就是这一形（主页面 28 枚候选旧口径全被剔）。
 *
 * 裸 `[31]`（纯数字自动按钮、或前缀被剥空、名字没了的按钮）两种口径下都不算常用指令。
 */
class LeanOptionFilterTest {

    @Suppress("LongParameterList")
    private fun option(
        label: String,
        value: Any = 400L,
        printsOwnNumber: Boolean = false,
        colorRgb: Int? = null,
        lineSerial: Long = 0L,
        visual: OptionVisual = OptionVisual.TEXT,
    ): ConsoleOption = ConsoleOption(
        value = value,
        label = label,
        origin = OptionOrigin.HTML,
        lineSerial = lineSerial,
        printsOwnNumber = printsOwnNumber,
        colorRgb = colorRgb,
        visual = visual,
    )

    @Test
    fun keepsCommandsWhoseNumberIsPrintedAfterTheName() {
        val labels = listOf("会话[31]", "移动[400]", "一键扫除[778]", "CONFIG[810]", "会话 [31]")
        labels.forEach { label ->
            assertTrue(LeanOptionFilter.hasTrailingNumberTag(label), "应保留：$label")
            assertTrue(
                LeanOptionFilter.isCommonCommand(option(label)),
                "末尾形态应进栏：$label",
            )
        }
    }

    /** 真机 2026-10-31 主页面读数：`[100] - 睁开眼睛` 一族，编号已被剥成徽标。 */
    @Test
    fun keepsCommandsWhoseNumberIsPrintedBeforeTheName() {
        val leading = listOf(
            option("- 睁开眼睛", 100L, printsOwnNumber = true),
            option("- OPTION", 400L, printsOwnNumber = true),
            option("- 特殊区域移动", 216L, printsOwnNumber = true),
            option("开始游戏", 0L, printsOwnNumber = true),
            // 编号超出徽标量程（0..999）时引擎不剥前缀，标签原文留着，照样是自带编号的指令。
            option("[2000] 能力编辑", 2000L, printsOwnNumber = true),
        )
        leading.forEach { option ->
            assertTrue(LeanOptionFilter.isCommonCommand(option), "开头形态应进栏：${option.label}")
        }
    }

    @Test
    fun dropsOptionsWithoutPrintedNumber() {
        val labels = listOf(
            "[CONFIG]",     // 只有文字、没有编号
            "退出",          // 无编号
            "开始游戏",      // 屏上没印编号的形态：徽标是提交值兜底出来的，printsOwnNumber=false
            "[＋]",         // 括号里不是数字
            "第[1]章",      // 编号不在末尾，也没被剥成徽标
            "选项[abc]",    // 非数字
            "选项[]",       // 空编号
            "",             // 空标签
            "   ",          // 纯空白
        )
        labels.forEach { label ->
            assertFalse(LeanOptionFilter.isCommonCommand(option(label)), "应剔除：$label")
        }
    }

    @Test
    fun dropsBareNumericButton() {
        // 裸 `[31]`（纯数字自动按钮）没有名字在前，不算常用指令。
        assertFalse(LeanOptionFilter.hasTrailingNumberTag("[31]"))
        assertFalse(LeanOptionFilter.isCommonCommand(option("[31]", 31L)))
        // 前缀被剥空的同一颗按钮：标签没了，编号还在 —— 一样不进栏。
        assertFalse(LeanOptionFilter.isCommonCommand(option("", 31L, printsOwnNumber = true)))
    }

    /**
     * 布娃娃调色板：`■` 既没有名字也没有编号，但它是真实选项。精简开关若按普通
     * 「无编号按钮」裁掉，128 色右栏会整片消失；判据与密集色格入口同源（[ConsoleOption.isColorSwatch]）。
     */
    @Test
    fun keepsDollPaletteColorSwatchesEvenWithoutPrintedNumber() {
        val palette = (0 until 128).map { i ->
            option("■", 80000L + i, colorRgb = (0x010203 * i) and 0xFFFFFF, visual = OptionVisual.COLOR_SWATCH)
        }
        assertTrue(palette.all { it.isColorSwatch })
        assertFalse(palette.any { LeanOptionFilter.isCommonCommand(it) }, "色块不是常用指令")
        assertEquals(palette, LeanOptionFilter.keepCommon(palette))

        val mixed = palette + option("退出", "exit")
        assertEquals(palette, LeanOptionFilter.keepCommon(mixed), "同屏其它无编号按钮照旧被裁，只保色调色板")
    }

    @Test
    fun keepCommonPreservesOrderAndObjects() {
        val commands = listOf(
            option("会话[31]", 31L),
            option("[CONFIG]", 809L),
            option("移动[400]", 400L),
            option("- 睁开眼睛", 100L, printsOwnNumber = true),
            option("退出", "exit"),
        )
        val kept = LeanOptionFilter.keepCommon(commands)
        assertEquals(listOf(commands[0], commands[2], commands[3]), kept)
    }

    @Test
    fun keepCommonDoesNotMutateInput() {
        val input = listOf(option("会话[31]"), option("退出"))
        LeanOptionFilter.keepCommon(input)
        assertEquals(2, input.size)
    }

    @Test
    fun emptyListStaysEmpty() {
        assertTrue(LeanOptionFilter.keepCommon(emptyList()).isEmpty())
    }

    /**
     * 玩家点名的那一屏，从引擎真实读数一路钉到右栏候选（2026-10-31 反馈
     * 「右边选项里经常找不到 400 移动，经常得直接在左边点」）。
     *
     * 出处是移动指令 `@QOL_COM400` 的目的地清单：语料 `魔改内容/qol/qol_MAP.ERB:152`
     * `PRINTL [  0] - 返回私室`、`:159` `PRINTFORML [{LOCAL, 3}] - %NAME_FROM_PLACE(LOCAL)%`、
     * `:176` `PRINTFORM [{BUTTON返回, 3}] - 返回`。原版「一行里只有一个 `[n]` 时整行都可点」
     * （`ButtonStringCreator.cs:63-73`）⇒ 编号写在名字**前面**且等于提交值 ⇒ 引擎出快照时
     * 把它剥给徽标，标签只剩 `- 地名` —— 旧口径按「末尾才有数字标签」判，整屏目的地全被剔，
     * 右栏空着，玩家只能回正文里点。
     */
    @Test
    fun keepsTheTravelDestinationListFromTheEngineSnapshot() {
        val sink = LineBufferConsoleSink(clientWidth = 110, maxLines = 200)
        sink.printLine("[  0] - 返回私室")
        sink.printLine("[123] - 博丽神社")
        sink.printLine("[400] - 返回　　　")
        sink.noteInputWait(
            InputRequest(type = InputRequest.InputType.INT, form = InputRequest.RequestForm.VALUE),
        )

        val recognized = sink.currentOptions()
        assertEquals(3, recognized.size, "三行目的地各一枚按钮；实际=$recognized")
        assertEquals(
            listOf("- 返回私室", "- 博丽神社", "- 返回"),
            recognized.map { it.label },
            "行首编号已剥给徽标（徽标口径不变），本句要的是这件事别让右栏整屏空",
        )
        assertEquals(
            listOf<Any>(0L, 123L, 400L),
            LeanOptionFilter.keepCommon(recognized).map { it.value },
            "精简档下三枚目的地都该进栏",
        )
    }
    @Test
    fun singleColorSwatchIsNotKeptAsAPalette() {
        val one = option("■", 1L, colorRgb = 0x112233, visual = OptionVisual.COLOR_SWATCH)
        assertTrue(LeanOptionFilter.keepCommon(listOf(one)).isEmpty(), "单个色块不是调色板")
    }

    @Test
    fun paletteRunMustStayOnOneGameLine() {
        val lineOne = (0 until 4).map {
            option("■", it.toLong(), colorRgb = 0x112233, lineSerial = 1L, visual = OptionVisual.COLOR_SWATCH)
        }
        val lineTwo = (0 until 4).map {
            option("■", 10L + it, colorRgb = 0x445566, lineSerial = 2L, visual = OptionVisual.COLOR_SWATCH)
        }
        assertTrue(LeanOptionFilter.keepCommon(lineOne + lineTwo).isEmpty(), "跨行拼不成 8 枚调色板")
    }

    @Test
    fun ordinaryColoredButtonIsNotKeptJustBecauseItHasColor() {
        val colored = option("普通选项", colorRgb = 0x112233)
        assertFalse(LeanOptionFilter.isCommonCommand(colored))
        assertTrue(LeanOptionFilter.keepCommon(listOf(colored)).isEmpty())
    }

}
