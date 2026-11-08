package com.eratw.port.core.vars

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `#DIM` / `#DIMS` / `#DIMF` 关键字归一**不得**随默认语言环境漂移
 * （C-FLOAT-AUTHORITY@1 已定标边界 2：`#DIM` 关键字比较用 `Locale.ROOT`）。
 *
 * 语义出处：`SystemVariableBootstrap.splitDeclaration`（装载/编译两侧的唯一入口）已锁
 * `Locale.ROOT`（f88d4801，CMP-44）；本用例钉住**声明层** `DimParser.parse` 的同一条口径 ——
 * 修复前该处是无参 `uppercase()`，红；修复后绿。
 *
 * 为什么必须拿土耳其默认语言环境来钉：`String.uppercase()`（无参）读
 * `Locale.getDefault()`，tr/az 下小写 'i' 映成带组合点的大写 'İ'（U+0130），于是
 * `"#dimf".uppercase()` 得 `"#DİMF"` ≠ `"#DIMF"` —— 声明被判成「未知的 #DIM 关键字」，
 * `#DIMF` 静默丢失浮点语义（`#dim`/`#dims` 同样当场抛错）。全大写输入不受影响，故生产
 * 调用方（LineParser 硬编码 `"#DIMF"`、Bootstrap/CorpusCompile 已 ROOT 归一）当前不可达，
 * 本用例守的是公共 API `DimParser.parse` 的口径完整性。
 *
 * 隔离说明：本模块 JUnit Platform 未设 `maxParallelForks`（默认单 fork、串行执行），
 * 临时改默认 locale 后在 `finally` 恢复，不会污染其它测试类。
 */
class DimParserKeywordLocaleTest {

    @Test
    fun `关键字归一不随土耳其默认语言环境漂移`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))

            val f = DimParser.parse("#dimf", "RATE, 3 = 1.5", isPrivate = false)
            assertTrue(f.isFloat, "tr locale 下小写 #dimf 仍须判为浮点声明")
            assertEquals(1.5, f.defaultsFloat, "浮点初值照常折出")
            assertEquals(listOf(3), f.dims.toList())

            val i = DimParser.parse("#dim", "X, 3", isPrivate = false)
            assertFalse(i.isFloat, "#dim 仍是整数声明")
            assertFalse(i.isStr, "#dim 不是字符串声明")

            val s = DimParser.parse("#dims", "S, 2", isPrivate = false)
            assertTrue(s.isStr, "#dims 仍是字符串声明")
            assertFalse(s.isFloat, "#dims 不是浮点声明")
        } finally {
            Locale.setDefault(saved)
        }
    }
}