package com.eratw.port.core.lexer

import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.EmueraConfigParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CMP-36：编译期词法开关的**条目表读入口**（[LexerConfig.fromConfigStore]）。
 *
 * 本游戏 `emuera.config:54` = `WHITESPACE INCLUDES FULL-WIDTH SPACE:NO` ⇒
 * `systemAllowFullSpace` 必须为 false；C# 消费点 `LexicalAnalyzer.cs:748-752` / `:681`，
 * 静态字段注册 `ConfigData.cs:101`、赋值 `Config.cs:370`、读盘 `ConfigData.cs:491-494`。
 *
 * 真正把 `CorpusCompile.kt:225-228` 改成调本入口的接线归 erbcc 面（L03/L15），
 * 本用例只钉「入口按磁盘值读数」这一半，防止入口默认值又漂回出厂 true。
 */
class LexerConfigFromStoreTest {

    private fun storeWith(overrides: Map<String, String>): ConfigStore {
        val store = ConfigStore()
        EmueraConfigParser.loadOverrideMap(store, "test.config", overrides)
        return store
    }

    @Test
    fun `语料 emuera config 54 的 NO 进入 LexerConfig`() {
        // 与 LexerConfigWireTest 同一组语料英文标签（ConfigData.cs:277 的 EngText 分支）
        val store = storeWith(
            mapOf(
                "WHITESPACE INCLUDES FULL-WIDTH SPACE" to "NO",
                "DO NOT PROCESS TRIPLE SYMBOLS INSIDE FORM" to "YES",
            ),
        )
        val lc = LexerConfig.fromConfigStore(store, macros = mapOf("FOO" to "42"), macroIgnoreCase = false)
        assertFalse(lc.systemAllowFullSpace, "emuera.config:54 = NO ⇒ 全角空格不是空白")
        assertTrue(lc.systemIgnoreTripleSymbol, "三连记号条目 YES = FORM 内不展开")
        assertEquals(mapOf("FOO" to "42"), lc.macros)
        assertEquals(false, lc.macroIgnoreCase)
    }

    @Test
    fun `无覆盖时回落到 ConfigData 出厂值`() {
        val lc = LexerConfig.fromConfigStore(ConfigStore())
        assertTrue(lc.systemAllowFullSpace, "ConfigData.cs:101 默认 true")
        assertFalse(lc.systemIgnoreTripleSymbol, "ConfigData.cs:110 默认 false")
        assertTrue(lc.macros.isEmpty())
        assertTrue(lc.macroIgnoreCase)
    }
}
