package com.erbcc.bridge

import com.eratw.port.core.compile.DimSizeEvaluator
import com.eratw.port.core.vars.DimFunctionFolders
import com.eratw.port.core.vars.DimVarSizes
import com.eratw.port.erbcc.DimKeyword
import com.eratw.port.erbcc.DimSpec
import com.eratw.port.erbcc.LabelDef
import com.eratw.port.erbcc.SymbolTable
import org.junit.jupiter.api.Assumptions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CMP-89 的姊妹面：布局桥的**尺寸校验**（对照 `reference` 的
 * `UserDefinedVariableData.cs:325-341`）。
 *
 * 原版规则（逐条行号）：
 * - 每段尺寸 `ReduceIntegerTerm(...).Restructure(null)` 必须是 `SingleLongTerm`
 *   （`:325-328` HasNotExpressionAfterComma）⇒ **单值**，不是「随便求出一个数」；
 * - REF 变量每段恒 0（`:309-319` 每个顶层逗号记一维 0），且显式写出的尺寸只能为 0
 *   （`:329-335` CanNotSizedRef）⇒ `#DIM REF X, 5` 是**无效行**，不许静默收成 REF；
 * - 非 REF 段 `1..1000000`（`:337-340` OoRDefinable）。
 *
 * 修复前：`toLocalDim` 对 REF **整段跳过求值**（`if (REF) continue`），于是
 * `#DIM REF X, 5` 直接返回 `LocalDim(ref = true)`；非 REF 的 `0` / `1000001` 也照收
 * （只判「可解」不判值域），且丢弃无任何留痕（`buildFuncDims` 静默 mapNotNull）。
 */
class DimSpecValidationTest {

    private fun spec(name: String, dims: List<String>, kws: Set<DimKeyword> = emptySet()) =
        DimSpec(name = name, isStr = false, dims = dims, keywords = kws, initializer = null)

    @Test
    fun `REF 尺寸必须为 0`() {
        // 有效形态：erbcc 对 REF 的每段恒为 ""（Symbols.kt:1070-1073，原版 :309-319）
        val ok = spec("R", listOf(""), setOf(DimKeyword.REF)).toLocalDim()
        assertNotNull(ok, "REF 的空段（= 0）是合法形态")
        assertTrue(ok.ref)

        // 无效形态：显式写了非 0 尺寸（原版 :329-335 CanNotSizedRef）
        val bad = spec("R_BAD", listOf("5"), setOf(DimKeyword.REF))
        assertNull(bad.toLocalDim(), "#DIM REF X, 5 必须按无效行丢弃，不许静默收成 REF")
        val reason = bad.dimDropReason()
        assertNotNull(reason, "丢弃必须留痕（不许静默丢）")
        assertTrue(reason.contains("REF"), "留痕要说明是 REF 尺寸规则：$reason")

        // 非 0 的表达式同理（原版要求 SingleLongTerm 且值必须 0）
        assertNull(spec("R_EXPR", listOf("1 + 1"), setOf(DimKeyword.REF)).toLocalDim())
        // 0 的表达式合法（值 = 0）
        assertNotNull(spec("R_ZERO", listOf("1 - 1"), setOf(DimKeyword.REF)).toLocalDim())
    }

    @Test
    fun `非 REF 尺寸 1 到一百万`() {
        assertEquals(1, spec("A", listOf("1")).toLocalDim()!!.slots)
        assertEquals(1_000_000, spec("B", listOf("1000000")).toLocalDim()!!.slots)
        assertNull(spec("ZERO", listOf("0")).toLocalDim(), "0 尺寸越界（:337-340）")
        assertNull(spec("NEG", listOf("-1")).toLocalDim(), "负尺寸越界（:337-340）")
        assertNull(spec("BIG", listOf("1000001")).toLocalDim(), "> 1000000 越界（:337-340）")
        assertTrue(spec("ZERO", listOf("0")).dimDropReason()!!.contains("越界"))
        assertTrue(spec("BIG", listOf("1000001")).dimDropReason()!!.contains("越界"))
    }

    @Test
    fun `尺寸必须单值`() {
        // 逗号分段由 erbcc 先切好；单段里再出现表达式尾巴/逗号 = 非 SingleLongTerm
        assertNull(spec("A", listOf("1 + ")).toLocalDim(), "残缺表达式 ⇒ 非单值")
        assertNull(spec("B", listOf("1, 2")).toLocalDim(), "段内逗号 ⇒ 非单值（:325-328）")
        assertNull(spec("C", listOf("1 @ 2")).toLocalDim(), "非法记号 ⇒ 非单值")
        assertTrue(spec("B", listOf("1, 2")).dimDropReason()!!.isNotEmpty())
    }

    @Test
    fun `多维尺寸 slots 是元素总数而不是恒 1`() {
        assertEquals(12, spec("M", listOf("3", "4")).toLocalDim()!!.slots, "3×4 = 12 个元素槽")
    }

    @Test
    fun `buildFuncDims 的丢弃必须留痕`() {
        val def = LabelDef(
            name = "F", key = "F", file = "t.erb", lineNo = 1, fileIndex = 1, defIndex = 0,
            isEvent = false, isSystem = false, isMethod = false, isOnly = false, isPri = false,
            isLater = false, isSingle = false,
            dimSpecs = listOf(
                spec("OK", listOf("3")),
                spec("BAD_REF", listOf("5"), setOf(DimKeyword.REF)),
                spec("BAD_SIZE", listOf("0")),
                spec("NO_CONST", listOf("UNKNOWN_CONST")),
                spec("GLOBAL_SIDE", listOf("2"), setOf(DimKeyword.GLOBAL)),
            ),
        )
        val drops = ArrayList<DimDrop>()
        val dims = buildFuncDims(listOf(def), drops = drops)
        // 全局侧不是「丢弃」：它走全局声明，不该进 drops
        assertEquals(listOf("OK"), dims["F"]?.map { it.name }, "只有可收集的进局部表")
        assertEquals(
            listOf("BAD_REF", "BAD_SIZE", "NO_CONST"),
            drops.map { it.spec.name },
            "每条被丢的声明都要有留痕；全局侧不算丢弃",
        )
        assertTrue(drops.all { it.reason.isNotBlank() }, "留痕必须带原因：${drops.map { it.reason }}")
    }

    @Test
    fun `VARSIZE 折叠器注入后折出快照值`() {
        val folder = DimFunctionFolders.pureStatic(mapOf("RELATION" to longArrayOf(200)))
        val d = spec("SORT_VALUE", listOf("VARSIZE(\"RELATION\")")).toLocalDim(folder = folder)
        assertNotNull(d, "注入 DimVarSizes 口径的折叠器后必须能折")
        assertEquals(200, d.slots, "VARSIZE(\"RELATION\") 折出 200")
        // 未注入 ⇒ 保持失败方向（不折、不丢成 1）
        assertNull(spec("SORT_VALUE", listOf("VARSIZE(\"RELATION\")")).toLocalDim(), "未注入回调必须保持失败口径")
    }

    /**
     * 全语料读数（只读诊断，无 ERATW_CORPUS 时跳过）：给「与基准差异」一节一组实测数字。
     *
     * - 旧判据 = 修复前的 `droppedByUnresolvableDim`（只看「能不能求值」，不看值域/REF，
     *   也不挂折叠器）；
     * - 新判据 = [droppedByUnresolvableDim]（同一份 [toLocalDim] 判据 + 折叠器）；
     * - 布局面收录数 = `toLocalDim(consts, folder) != null`，与侧车面（DimParser +
     *   同一快照）口径同源。
     */
    @Test
    fun `全语料尺寸校验读数`() {
        val raw = System.getenv("ERATW_CORPUS") ?: System.getProperty("eratw.corpus")
        Assumptions.assumeTrue(!raw.isNullOrBlank(), "未提供语料路径，跳过全量读数")
        val corpus = Path.of(raw!!)
        Assumptions.assumeTrue(Files.isDirectory(corpus), "语料目录不存在: $corpus")
        val table = SymbolTable.build(corpus)
        val consts = sizeConsts(table)
        val folder = DimFunctionFolders.pureStatic(DimVarSizes.systemSnapshot(corpus.parent?.resolve("CSV")))
        var total = 0
        var oldDrop = 0
        var noFolderHit = 0
        var layoutHit = 0
        var newDropNoFolder = 0
        var newDrop = 0
        var segmentMismatch = 0
        val mismatchSamples = ArrayList<String>()
        val reasons = LinkedHashMap<String, Int>()
        val legacy = LegacyDimEval(consts)
        for (key in table.nameKeys) {
            for (d in table.defsOf(key)) {
                if (d.file.startsWith("GOLDEN_")) continue
                for (s in d.dimSpecs) {
                    total++
                    val oldStyle =
                        s.keywords.none { it == DimKeyword.GLOBAL || it == DimKeyword.SAVEDATA || it == DimKeyword.CHARADATA } &&
                            DimKeyword.REF !in s.keywords &&
                            s.dims.isNotEmpty() &&
                            s.dims.any { legacy.eval(it) == null }
                    if (oldStyle) oldDrop++
                    if (s.toLocalDim(consts) != null) noFolderHit++
                    if (s.toLocalDim(consts, folder) != null) layoutHit++
                    if (s.droppedByUnresolvableDim(consts)) newDropNoFolder++
                    if (s.droppedByUnresolvableDim(consts, folder)) newDrop++
                    // 写法等价性：修复前的语法子集 vs 现在的单源折叠（都不挂回调）
                    for (raw in s.dims) {
                        val a = legacy.eval(raw)
                        val b = DimSizeEvaluator(consts).eval(raw)
                        if (a != b) {
                            segmentMismatch++
                            if (mismatchSamples.size < 5) mismatchSamples.add("'$raw': $a → $b")
                        }
                    }
                    val reason = s.dimDropReason(consts, folder)
                    if (reason != null) {
                        val k = reason.substringBefore("（").trim()
                        reasons[k] = (reasons[k] ?: 0) + 1
                    }
                }
            }
        }
        println(
            "CMP-45 全语料读数：dimSpecs=$total 旧判据丢弃=$oldDrop " +
                "新判据丢弃（无折叠器）=$newDropNoFolder 新判据丢弃（挂快照）=$newDrop " +
                "布局面收录（无折叠器）=$noFolderHit 布局面收录（挂 DimVarSizes 快照）=$layoutHit " +
                "尺寸段求值差异=$segmentMismatch 样例=$mismatchSamples " +
                "丢弃原因分布=$reasons",
        )
        assertEquals(0, segmentMismatch, "换实现不得改变既有尺寸段的求值结果（差异样例见上）")
        assertTrue(layoutHit >= noFolderHit, "挂折叠器只应恢复注册，不得减少")
        assertTrue(newDropNoFolder <= oldDrop, "值域校验收紧只会多丢；旧判据已丢的不会复活")
    }
}

/**
 * 修复前 `DimSizeEvaluator` 的语法子集（快照 = git HEAD 的 `LocalLayout.kt`）：
 * 十进制 / `0x`、标识符常量、`+ - * / %`、一元正负、括号。只用于**等价性对拍**——
 * 新实现是它的严格超集（多了 `0b`/`p`/`e`、位移、字符串 `+`、函数折叠），
 * 既有语料里凡是旧实现能算的，新实现必须给出同一个数。
 */
private class LegacyDimEval(private val consts: Map<String, Long>) {
    private var s = ""
    private var i = 0

    fun eval(text: String): Long? = try {
        s = text.trim()
        i = 0
        val v = parseExpr()
        if (i >= s.length) v else null
    } catch (_: Exception) {
        null
    }

    private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun peek(): Char? = if (i < s.length) s[i] else null

    private fun parseExpr(): Long {
        var v = parseTerm()
        while (true) {
            skip()
            when (peek()) {
                '+' -> { i++; v += parseTerm() }
                '-' -> { i++; v -= parseTerm() }
                else -> return v
            }
        }
    }

    private fun parseTerm(): Long {
        var v = parseUnary()
        while (true) {
            skip()
            when (peek()) {
                '*' -> { i++; v *= parseUnary() }
                '/' -> {
                    i++
                    val d = parseUnary()
                    if (d == 0L) throw ArithmeticException("div0")
                    v /= d
                }
                '%' -> {
                    i++
                    val d = parseUnary()
                    if (d == 0L) throw ArithmeticException("div0")
                    v %= d
                }
                else -> return v
            }
        }
    }

    private fun parseUnary(): Long {
        skip()
        return when (peek()) {
            '-' -> { i++; -parseUnary() }
            '+' -> { i++; parseUnary() }
            '(' -> {
                i++
                val v = parseExpr()
                skip()
                if (peek() != ')') throw IllegalArgumentException("缺 )")
                i++
                v
            }
            else -> parseAtom()
        }
    }

    private fun parseAtom(): Long {
        skip()
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        if (i > start) {
            val id = s.substring(start, i)
            if (id[0].isDigit()) {
                if (id.all { it.isDigit() }) return id.toLong()
                if (id.length > 2 && (id[1] == 'x' || id[1] == 'X')) return id.substring(2).toLong(16)
                throw IllegalArgumentException("非法数字 $id")
            }
            return consts[id.uppercase()] ?: throw IllegalArgumentException("未知常量 $id")
        }
        throw IllegalArgumentException("尺寸表达式无法解释")
    }
}
