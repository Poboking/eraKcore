package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.SystemVariableBootstrap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 尺寸校验的**运行期/侧车同源面**（对照 `reference/emuera-cs/.../UserDefinedVariableData.cs:325-341`）。
 *
 * 这三条判据在 [DimParser] 里本来就有（本路不改口径、只钉住，防止把它当「布局面可以放宽」的
 * 借口）：布局桥 `toLocalDim` 与它不是两套语义 —— 桥侧走 `DimSizeEvaluator`（
 * `DimParser.evalDimExpr`），校验也照同一组行号补齐（见 erbcc 的 `DimSpecValidationTest`）。
 * - `:329-335` CanNotSizedRef：REF 变量的尺寸只能写 0；
 * - `:337-340` OoRDefinable：非 REF 段 1..1000000；
 * - `:325-328` HasNotExpressionAfterComma：每段必须是 SingleLongTerm（单值）。
 */
class DimSpecSizeValidationTest {

    private fun parse(raw: String): DimSpec {
        val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw))
            ?: error("不是 #DIM/#DIMS 声明: $raw")
        return DimParser.parse(split.first, split.second, isPrivate = false, config = DimParseConfig())
    }

    @Test
    fun `REF 尺寸非零必须报错`() {
        val e = assertFailsWith<CodeEE> { parse("#DIM REF X, 5") }
        assertTrue((e.message ?: "").contains("REF 变量的尺寸只能写 0"), "实际：${e.message}")
        // 表达式形态同理（求值后仍是 5）
        assertFailsWith<CodeEE> { parse("#DIM REF X, 2 + 3") }
        // 0 的表达式合法
        assertContentEquals(intArrayOf(0), parse("#DIM REF X, 0").dims, "显式 0 是合法 REF 尺寸")
    }

    @Test
    fun `REF 的每个逗号恒记一维零`() {
        val s = parse("#DIM REF 集合,,") // 语料 集合.ERB:121 实形
        assertContentEquals(intArrayOf(0, 0), s.dims, "两个逗号 = 2 维 REF")
        assertTrue(s.isRef)
    }

    @Test
    fun `非 REF 尺寸上下界`() {
        for (raw in listOf("#DIM X, 0", "#DIM X, -1", "#DIM X, 1000001")) {
            val e = assertFailsWith<CodeEE>("$raw 必须报越界") { parse(raw) }
            assertTrue((e.message ?: "").contains("越界"), "$raw 实际：${e.message}")
        }
        // 边界值 1 / 1000000 合法
        assertContentEquals(intArrayOf(1), parse("#DIM X, 1").dims)
        assertContentEquals(intArrayOf(1_000_000), parse("#DIM X, 1000000").dims)
    }

    @Test
    fun `尺寸段必须单值`() {
        // 逗号后缺表达式（`#DIM X, 5,`）
        val e = assertFailsWith<CodeEE> { parse("#DIM X, 5,") }
        assertTrue((e.message ?: "").contains("缺少尺寸表达式"), "实际：${e.message}")
        // 非 SingleLongTerm（函数调用未注入折叠回调）
        val f = assertFailsWith<CodeEE> { parse("#DIM X, VARSIZE(\"RELATION\")") }
        assertTrue((f.message ?: "").contains("函数调用"), "实际：${f.message}")
    }
}
