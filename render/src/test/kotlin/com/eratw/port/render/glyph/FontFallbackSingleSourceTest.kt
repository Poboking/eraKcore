package com.eratw.port.render.glyph

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 缺字回落链在 `:render` 主源集里的**单一权威**闸（契约 §3.4）。
 *
 * ## 为什么是源码形状闸
 *
 * 「两份链」不是某个函数能自证的：`FontWidthRuler.compute` 与 `CanvasConsoleView.effectiveFamily`
 * 各自拼一遍 `listOf(requested) + BundledFontFamilies.fallbacksFor(...)` 时两处**各自都对**，
 * 只有把链并排放才看得出分家。行为面（读数逐值不变）由既有八个 glyph 用例钉；本类钉的是
 * 「以后没有人再手拼一条」。
 *
 * ## 口径
 *
 * 剥掉注释后断言（注释里提到旧写法是正常的，不剥会把散文当代码）：
 * 1. `FontWidthRuler` 读 `GlyphFallback.chainFor(`，且**不再**自己拼链（不含 `listOf(requested) +`）；
 * 2. `src/main` 全树里 `BundledFontFamilies.fallbacksFor(` 的调用点**只有** `GlyphFallback.kt`
 *    一处（`BundledFontFamilies.kt` 自身是 `fun fallbacksFor(` 定义，不含这个限定写法）。
 *
 * 另有一条行为面形状断言：逐跳跳过（null / 非有限 / 负数）与「全落空回落 `cellMetrics.cellsOf`」
 * 两句在换循环头之后必须逐字保留 —— 那是 [FontWidthRuler] 的退化口径，换了链也不许动。
 */
class FontFallbackSingleSourceTest {

    private val moduleRoot: Path by lazy { Path.of("").toAbsolutePath() }

    private val rulerPath: Path by lazy {
        moduleRoot.resolve("src/main/kotlin/com/eratw/port/render/glyph/FontWidthRuler.kt")
    }

    /**
     * 剥掉 `//` 行注释与块注释，**保留换行**（行号与源文件一一对应）。
     *
     * 写法照 `:render-canvas` 的 `CanvasContentInsetSingleSourceTest.stripComments`；
     * 测试源集没有共享 helper，两个模块各留一份（不为一个 30 行的纯函数新增跨模块依赖）。
     */
    private fun stripComments(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '/' && i + 1 < raw.length) {
                val next = raw[i + 1]
                if (next == '/') {
                    while (i < raw.length && raw[i] != '\n') i++
                    continue
                }
                if (next == '*') {
                    i += 2
                    while (i + 1 < raw.length && !(raw[i] == '*' && raw[i + 1] == '/')) {
                        if (raw[i] == '\n') sb.append('\n')
                        i++
                    }
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** 取某个 `fun xxx(` 的函数体（按大括号配平）。 */
    private fun bodyOf(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue(start >= 0, "代码里找不到 $signature")
        val open = code.indexOf('{', start)
        assertTrue(open >= 0, "$signature 后没有函数体")
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
                else -> {}
            }
            i++
        }
        error("$signature 的函数体没有配平")
    }

    private val rulerCode: String by lazy { stripComments(rulerPath.readText()) }

    @Test
    fun `缺字链在 render 主源集里只有 GlyphFallback 一处组装`() {
        assertTrue(
            rulerCode.contains("GlyphFallback.chainFor("),
            "FontWidthRuler.compute 必须读 GlyphFallback.chainFor（与绘制侧同源）",
        )
        assertTrue(
            !rulerCode.contains("listOf(requested) +"),
            "FontWidthRuler 不许再自己拼链（listOf(requested) + ...），链只由 GlyphFallback 组装",
        )

        val mainSources = Files.walk(moduleRoot.resolve("src/main/kotlin")).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.toList()
        }
        assertTrue(mainSources.isNotEmpty(), "扫不到 src/main/kotlin 下的 .kt：工作目录是 $moduleRoot")
        val callSites = mainSources
            .filter { stripComments(it.readText()).contains("BundledFontFamilies.fallbacksFor(") }
            .map { moduleRoot.relativize(it).toString().replace('\\', '/') }
            .sorted()
        assertEquals(
            listOf("src/main/kotlin/com/eratw/port/render/font/GlyphFallback.kt"),
            callSites,
            "BundledFontFamilies.fallbacksFor 只许在 GlyphFallback 里被调用（其余消费者读 chainFor）",
        )
    }

    @Test
    fun `度量尺的逐跳跳过与全落空回落逐字保留`() {
        val compute = bodyOf(rulerCode, "private fun compute(")
        assertTrue(
            compute.contains("if (px == null || !px.isFinite() || px < 0f) continue"),
            "缺行 / 非有限 / 负 advance 必须逐跳跳过（缺表 ≠ 否决）",
        )
        assertTrue(
            compute.contains("return cellMetrics.cellsOf(codePoint).toFloat()"),
            "全链落空必须回落静态网格 cellMetrics.cellsOf（读数与接线前逐值相同）",
        )
        assertTrue(
            compute.contains("BundledFontFamilies.byFamily(family) ?: BundledFontFamilies.default"),
            "请求族归一仍走 byFamily ?: default",
        )
    }
}
