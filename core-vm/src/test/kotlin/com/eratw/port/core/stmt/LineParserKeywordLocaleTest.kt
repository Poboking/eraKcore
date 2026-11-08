package com.eratw.port.core.stmt

import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 语句层大小写归一**不得**随默认语言环境漂移（T5 · `LineParser` 的 `Locale.ROOT` 锁）。
 *
 * 语义出处：与 T2 已定标的 `#DIM` 关键字同源（C-FLOAT-AUTHORITY@1 已定标边界 2；
 * `DimParserKeywordLocaleTest` 钉声明层、f88d4801 钉 `LexicalAnalyzer.kt`）。本用例钉
 * **行解析层** `LineParser` 的五处 `uppercase()` 全部改走 `Locale.ROOT`（修复前是无参
 * `uppercase()`，读 `Locale.getDefault()`，tr/az 下小写 'i' 映成带组合点的大写
 * 'İ'（U+0130），于是 `"initrand".uppercase()` 得 `"İNİTRAND"` 与注册名表全 Miss）。
 *
 * 五处消费点（`LineParser.kt`）：
 * - 语句名查表 `FunctionCode.BY_NAME`（`parseCodeLine`）；
 * - 内建方法名查表 `BuiltinSymbols.methods`（`parseCodeLine`）；
 * - `#` 预处理关键字归一 `kw`（`parseSharpLine` 的 LOCALSIZE/DIM/DIMS/DIMF 分支）；
 * - `#DIM` 修饰符关键字判据 `it.code !in DIM_KEYWORDS`（本用例的 `DIM_KEYWORDS` 全为大写
 *   无小写 'i' 的名（CONST/REF/DYNAMIC/STATIC/GLOBAL/SAVEDATA/CHARADATA），故**不可观测**，
 *   只随其余四处一并 ROOT 归一，保证同一份口径）；
 * - 保留变量名判定 `reservedVarMessage`。
 *
 * 隔离说明：本模块 JUnit Platform 未设 `maxParallelForks`（默认单 fork、串行执行），
 * 临时改默认 locale 后在 `finally` 恢复，不会污染其它测试类。
 */
class LineParserKeywordLocaleTest {

    @Test
    fun `语句名查表不随土耳其默认语言环境漂移`() {
        withTurkishDefaultLocale {
            // parseCodeLine 行首标识符 → FunctionCode.BY_NAME（LineParser.kt:174）。
            val stmt = LineParser().parseCodeLine("initrand", 1)
            assertEquals(
                FunctionCode.INITRAND, stmt.code,
                "tr locale 下小写 initrand 仍须命中语句名表",
            )
        }
    }

    @Test
    fun `内建方法名查表不随土耳其默认语言环境漂移`() {
        withTurkishDefaultLocale {
            // 名字含小写 'i' 的纯内建方法（ENUMVARENDSWITH，非语句名）。
            val stmt = LineParser(SymbolTable()).parseCodeLine("enumvarendswith 1", 1)
            assertIs<MethodStmt>(stmt, "tr locale 下小写 enumvarendswith 仍须命中内建方法表")
            assertEquals("enumvarendswith", stmt.methodName)
        }
    }

    @Test
    fun `预处理关键字归一不随土耳其默认语言环境漂移`() {
        withTurkishDefaultLocale {
            // LineParser.kt:905 的 kw 归一：小写 #localsize / #dim 仍须进入对应分支。
            val localsize = assertIs<SharpStmt>(LineParser().parseSharpLine("#localsize 5", 1))
            assertEquals(listOf(5L), localsize.values, "tr locale 下小写 #localsize 仍须走 LOCALSIZE 分支")

            val dim = assertIs<SharpStmt>(LineParser().parseSharpLine("#dim abc, 5", 1))
            assertEquals("abc", dim.name, "tr locale 下小写 #dim 仍须进入 DIM 分支并取到变量名")
            assertEquals(listOf(5L), dim.values)
        }
    }

    @Test
    fun `保留变量名判定不随土耳其默认语言环境漂移`() {
        withTurkishDefaultLocale {
            // reservedVarMessage（LineParser.kt:1064）的 rawName 归一：小写保留名仍须被拒。
            val e = assertFailsWith<StmtParseException> {
                LineParser().parseSharpLine("#DIM initrand, 5", 1)
            }
            assertTrue(
                e.message!!.contains("命令名"),
                "tr locale 下 #DIM initrand 仍须按保留名拒绝，实际：${e.message}",
            )
        }
    }

    /**
     * 取 `parseLogicalLine` 的 SHARP 入口形态（与 `parseSharpLine` 等价，见 `LineParser.parseLogicalLine`）。
     * 保留给未来需要走整行分派的用例；当前用例直调 `parseSharpLine` 更贴近被测点。
     */
    @Suppress("unused")
    private fun parseSharpViaLogicalLine(content: String): Statement? =
        LineParser().parseLogicalLine(LogicalLine(LineKind.SHARP, "locale.erb", 1, content))

    /** 单 fork 串行执行；改默认 locale 后 `finally` 恢复，不污染其它测试类。 */
    private inline fun withTurkishDefaultLocale(block: () -> Unit) {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            block()
        } finally {
            Locale.setDefault(saved)
        }
    }
}