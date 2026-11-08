package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CONFIG-WIRE U5：BackColor / FocusColor / LogColor 的控制台模型取数点。
 *
 * C# 侧语义锚：
 * - `Config.BackColor`（ConfigData.cs:69 出厂 `0,0,0`）→ 控制台构造期 `bgColor` 初值
 *   （EmueraConsole.cs:200）；SETBGCOLOR/RESETBGCOLOR 写读的是**控制台级活值**（:2843-2846），
 *   未显式设置时整屏底色回落它 —— 移植侧 2026-11-05 合流裁定：行级底色模型已由 tstopbg
 *   收成控制台活值（`backgroundColor` 直达渲染层），本注入值就是渲染回落源
 *   （`ConsoleFeed.sinkBackgroundArgb` 的 null 支）。
 * - `Config.FocusColor`（ConfigData.cs:70 出厂 `255,255,0`）→ 按钮焦点绘制
 *   （StringStyle.cs:22）与 GETFOCUSCOLOR（FunctionMethodCreator.cs:3677）；
 * - `Config.LogColor`（ConfigData.cs:71 出厂 `192,192,192`）→ 履历绘制前景
 *   （ConsoleStyledString.cs:115/:153 的 isBackLog 分支）。
 *
 * 移植侧：三者都是 [LineBufferConsoleSink] 构造注入项（与 ForeColor 同一条规矩），
 * 装配层（proc/GameSession）按生效条目喂入；渲染壳层的消费留接口（core 只存值）。
 * 语料 `emuera.config:24-27`：TEXT/BACKGROUND/HIGHLIGHT 与出厂同值，HISTORY LOG
 * COLOR = `128,128,128` **不等于**出厂 `192,192,192` ⇒ logColor 必须走注入通道。
 */
class ConfigWireConsoleColorTest {

    /** 注入 defaultBackColor 接管控制台活值的回落源，SETBGCOLOR 显式值照旧吃紧。 */
    @Test
    fun injectedDefaultBackColorDrivesLiveValueFallbackAndExplicitSetWins() {
        val custom = 0x00FF00
        val s = LineBufferConsoleSink(clientWidth = 80, defaultBackColor = custom)

        // 未 SETBGCOLOR：backgroundColor 保持 null，回落源 = 注入值（渲染层
        // ConsoleFeed.sinkBackgroundArgb 在 null 支取 defaultBackColor；合流裁定
        // 2026-11-05：tstopbg 已把行级底色模型收成控制台级活值，行模型不再回落）。
        assertNull(s.backgroundColor, "未显式背景时行模型不带底色，回落交给渲染层")
        assertEquals(custom, s.defaultBackColor, "注入值即渲染回落源（EmueraConsole.cs:200 的 bgColor 口径）")

        // SETBGCOLOR 显式值优先。
        s.setBackColor(1, 2, 3)
        assertEquals(0x010203, s.backgroundColor)

        // resetBackColor 回到 null = 回落注入值，不是工厂常量。
        s.resetBackColor()
        assertNull(s.backgroundColor, "RESETBGCOLOR（FunctionIdentifier.cs:1515）后回落注入值")
    }

    /** 出厂不注入 = 工厂常量（基准侧 `emuera.config:25` 与之同值，读数不变）。 */
    @Test
    fun factoryDefaultsMatchRegisteredConstants() {
        val s = LineBufferConsoleSink()
        assertEquals(LineBufferConsoleSink.DEFAULT_BACK_COLOR, s.defaultBackColor, "ConfigData.cs:69")
        assertEquals(LineBufferConsoleSink.DEFAULT_FOCUS_COLOR, s.focusColor, "ConfigData.cs:70")
        assertEquals(LineBufferConsoleSink.DEFAULT_LOG_COLOR, s.logColor, "ConfigData.cs:71")
        assertEquals(0x000000, LineBufferConsoleSink.DEFAULT_BACK_COLOR)
        assertEquals(0xFFFF00, LineBufferConsoleSink.DEFAULT_FOCUS_COLOR)
        assertEquals(0xC0C0C0, LineBufferConsoleSink.DEFAULT_LOG_COLOR)
    }

    /**
     * focusColor / logColor 是渲染壳层的留接口：core 侧先保证「喂进来的值原样可取」。
     * logColor 用语料值 128,128,128（≠ 出厂 192,192,192），证明它不吃工厂常量。
     */
    @Test
    fun focusAndLogColorsAreInjectablePassthrough() {
        val s = LineBufferConsoleSink(
            clientWidth = 80,
            focusColor = 0xABCDEF,
            logColor = 0x808080,
        )
        assertEquals(0xABCDEF, s.focusColor)
        assertEquals(0x808080, s.logColor, "语料 emuera.config:27 的生效值通道")
    }
}
