package com.eratw.port.core.err

import com.eratw.port.core.lexer.LangKey
import com.eratw.port.core.lexer.LangVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `CodeEE` 结构化文案键通道（i18n，2026-11-07）。
 *
 * 钉住的契约：
 * 1. [CodeEE.keyed] 构造的 `message` 仍是 zhs 定型串 —— 日志、错误报告与上报去重签名
 *    是取证面，不随语言漂（AGENTS §6）；
 * 2. `langKey` / `langArgs` 留存，上屏层可按 [LangVariant] 三语重渲染；
 * 3. 旧构造（无键）行为逐字不变（该通道是纯增量）。
 */
class CodeEEKeyedI18nTest {

    @Test
    fun `keyed 保留 zhs 定型串并留存键与实参`() {
        val e = CodeEE.keyed(LangKey.CanNotOmitRefArg, "FUNC", 2)
        assertEquals("@FUNC的第2个参数是引用形式因此无法省略", e.message)
        assertEquals(LangKey.CanNotOmitRefArg, e.langKey)
        assertEquals(listOf<Any?>("FUNC", 2), e.langArgs)
        assertEquals(
            e.message,
            e.langKey!!.renderZhs(*e.langArgs.toTypedArray()),
            "定型 message 必须与键的 zhs 渲染逐字同形",
        )
    }

    @Test
    fun `上屏三语重渲染取对应语言模板`() {
        val e = CodeEE.keyed(LangKey.CanNotOmitRefArg, "FUNC", 2)
        val args = e.langArgs.toTypedArray()
        assertEquals(
            "\"@FUNC\"の2番目の引数は参照渡しのため省略できません",
            e.langKey!!.render(LangVariant.JA, *args),
        )
        assertEquals(
            "The 2 argument is passed by the reference to \"@FUNC\" function and cannot be omitted",
            e.langKey!!.render(LangVariant.EN, *args),
        )
        assertEquals("@FUNC的第2个参数是引用形式因此无法省略", e.langKey!!.render(LangVariant.ZHS, *args))
    }

    @Test
    fun `无键构造行为不变`() {
        val e = CodeEE("旧形态消息", line = 3)
        assertNull(e.langKey)
        assertEquals(emptyList(), e.langArgs)
        assertEquals("旧形态消息", e.message)
        assertEquals(4, e.displayLine)
    }
}