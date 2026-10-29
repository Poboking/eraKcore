package com.eratw.port.render.view

import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.OptionOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LeanOptionFilter] 的口径钉子（2026-10-27）：
 *
 * 开关打开时，右侧栏只留「名字[数字]」形态的常用指令；关闭时调用方根本不走这里，
 * 所以本用例只钉过滤本身：正例必须逐条保留、反例必须逐条剔除，裸 `[31]` 与
 * 标签不在末尾的 `第[1]章` 都不算常用指令。
 */
class LeanOptionFilterTest {

    private fun option(label: String, value: Any = 400L): ConsoleOption =
        ConsoleOption(value = value, label = label, origin = OptionOrigin.HTML)

    @Test
    fun keepsCommandsWithTrailingNumericTag() {
        val labels = listOf("会话[31]", "移动[400]", "一键扫除[778]", "CONFIG[810]", "会话 [31]")
        labels.forEach { label ->
            assertTrue(LeanOptionFilter.isCommon(label), "应保留：$label")
        }
    }

    @Test
    fun dropsLabelsWithoutTrailingNumericTag() {
        val labels = listOf(
            "[CONFIG]",     // 只有文字、没有数字标签
            "退出",          // 无标签
            "开始游戏",      // 无标签
            "[＋]",         // 非数字标签
            "第[1]章",      // 标签不在末尾
            "选项[abc]",    // 非数字
            "选项[]",       // 空标签
            "",             // 空标签
            "   ",          // 纯空白
        )
        labels.forEach { label ->
            assertFalse(LeanOptionFilter.isCommon(label), "应剔除：$label")
        }
    }

    @Test
    fun dropsBareNumericButton() {
        // 裸 `[31]`（纯数字自动按钮）没有名字在前，不算常用指令。
        assertFalse(LeanOptionFilter.isCommon("[31]"))
    }

    @Test
    fun keepCommonPreservesOrderAndObjects() {
        val commands = listOf(
            option("会话[31]", 31L),
            option("[CONFIG]", 809L),
            option("移动[400]", 400L),
            option("退出", "exit"),
        )
        val kept = LeanOptionFilter.keepCommon(commands)
        assertEquals(listOf(commands[0], commands[2]), kept)
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
}
