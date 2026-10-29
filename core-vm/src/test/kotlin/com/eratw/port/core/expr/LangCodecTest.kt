package com.eratw.port.core.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * [LangCodec] 的判据：**码页由 `DEFAULT ANSI ENCODING` 决定，不取决于界面语言**。
 *
 * 出处逐条：
 * - 配置项是 `ConfigData.cs:103` 的 `useLanguage`（行文本 "DEFAULT ANSI ENCODING"），
 *   出厂默认 JAPANESE；`Config.cs:412-430` 的 switch 把它映射成 932 / 949 / 936 / 950。
 * - 界面语言是另一项 `EmueraLang`（`ConfigData.cs:130`、`Config.cs:390`），**不参与**码页选择。
 *   本语料 `emuera.config:56` = `DEFAULT ANSI ENCODING:CHINESE_HANS`，`:76` 才是
 *   `EMUERA INTERFACE LANGUAGE:简体中文` —— 前一条才是证据。
 * - 键匹配复刻 `ConfigData.GetItem`（`:236-249` → `:262-284`）+ `AConfigItem.cs:22-23`
 *   （Text/EngText 构造时大写），值按 `Enum.TryParse(ignoreCase)`（`ConfigItem.cs:196-202`）。
 * - `LangManager.cs:15-22` 的字节数快路径：全 ASCII 直接返回长度。
 *
 * 码页名选择（JDK 与 .NET 不同构）与逐码位实测表见 `FormStringTerm.kt` 的 [LangCodec] KDoc：
 * 日文稿取 `windows-31J` 而不是 `Shift_JIS`（后者对 CP932 表内字 `U+2460` 反而编不出），
 * 中文稿取 `GBK`。下面钉的是**会改变结果的那几个码位**，不是全部。
 */
class LangCodecTest {

    @Test
    fun `出厂默认是 JAPANESE 的 windows-31J`() {
        assertSame(LangCodec.JAPANESE, LangCodec.DEFAULT)
        assertEquals(932, LangCodec.DEFAULT.codepage)
        assertEquals("windows-31J", LangCodec.DEFAULT.charsetName)
    }

    @Test
    fun `四条分派与码页号`() {
        assertEquals(932, LangCodec.byUseLanguage("JAPANESE")?.codepage)
        assertEquals(949, LangCodec.byUseLanguage("KOREAN")?.codepage)
        assertEquals(936, LangCodec.byUseLanguage("CHINESE_HANS")?.codepage)
        assertEquals(950, LangCodec.byUseLanguage("CHINESE_HANT")?.codepage)
        // Enum.TryParse 的 ignoreCase（ConfigItem.cs:196-202）
        assertEquals(936, LangCodec.byUseLanguage("chinese_hans")?.codepage)
        assertEquals(936, LangCodec.byCodepage(936)?.codepage)
        assertEquals(null, LangCodec.byCodepage(1252))
    }

    @Test
    fun `本语料的配置行决定码页 不是界面语言那一行`() {
        // emuera.config:56 —— 真正的码页来源
        assertEquals(
            936,
            LangCodec.fromConfig(mapOf("DEFAULT ANSI ENCODING" to "CHINESE_HANS")).codepage,
        )
        // 键名大小写不敏感（AConfigItem.cs:22-23 把 Text/EngText 大写存）
        assertEquals(
            936,
            LangCodec.fromConfig(mapOf("default ansi encoding" to "chinese_hans")).codepage,
        )
        // emuera.config:76 的界面语言**不碰**码页：键不在 useLanguage 的三形之内 ⇒ 保留出厂默认
        assertEquals(
            932,
            LangCodec.fromConfig(mapOf("EMUERA INTERFACE LANGUAGE" to "简体中文")).codepage,
        )
        // 写歪了/空串 ⇒ ConfigWarn 保留旧值（ConfigData.cs:579-586），本层回落 DEFAULT
        assertEquals(932, LangCodec.fromConfig(mapOf("DEFAULT ANSI ENCODING" to "Klingon")).codepage)
        assertEquals(932, LangCodec.fromConfig(mapOf("DEFAULT ANSI ENCODING" to "")).codepage)
        assertEquals(932, LangCodec.fromConfig(emptyMap()).codepage)
    }

    @Test
    fun `纯数字值按 enum 下标解析`() {
        // Enum.TryParse 对数字串返回 true（按下标），ConfigItem.cs:196-202
        assertEquals(LangCodec.JAPANESE, LangCodec.fromConfig(mapOf("DEFAULT ANSI ENCODING" to "0")))
        assertEquals(LangCodec.CHINESE_HANS, LangCodec.fromConfig(mapOf("DEFAULT ANSI ENCODING" to "2")))
        // 越界数字：原版会 lang=null 后 NRE，本层按「无法识别 ⇒ 默认」（已登记 TODO(verify)）
        assertEquals(LangCodec.DEFAULT, LangCodec.fromConfig(mapOf("DEFAULT ANSI ENCODING" to "9")))
    }

    @Test
    fun `字节数口径逐码位`() {
        // 全 ASCII 走长度快路径（LangManager.cs:15-22 的 Ascii.IsValid 分支）
        assertEquals(3, LangCodec.DEFAULT.strlen("abc"))
        assertEquals(0, LangCodec.DEFAULT.strlen(""))
        // 日文码页（windows-31J）：全角汉字 2 字节
        assertEquals(2, LangCodec.DEFAULT.strlen("全"))
        // ① 在 .NET 的 Shift-JIS(=CP932) 是 2 字节；JDK 的 Shift_JIS 反而编不出（1 个 ?），
        // windows-31J 才与 .NET 同值 —— 这就是选名理由（实测表在 LangCodec 的 KDoc）
        assertEquals(2, LangCodec.DEFAULT.strlen("①"))
        // 半角假名在 932 系是单字节
        assertEquals(1, LangCodec.DEFAULT.strlen("ｱ"))
        // 中文字符串：GBK 2 字节/字
        assertEquals(4, LangCodec.CHINESE_HANS.strlen("全药"))
    }

    @Test
    fun `两个码页给出相反结论的码位`() {
        // U+2014 EM DASH：windows-31J 编不出（替换 1 字节），GBK 是 2 字节
        assertEquals(1, LangCodec.JAPANESE.strlen("—"))
        assertEquals(2, LangCodec.CHINESE_HANS.strlen("—"))
        // U+00B7 MIDDLE DOT：windows-31J 2 字节（0x8145）
        assertEquals(2, LangCodec.JAPANESE.strlen("·"))
    }
}
