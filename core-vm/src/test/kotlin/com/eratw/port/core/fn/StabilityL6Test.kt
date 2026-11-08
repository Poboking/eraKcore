package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.font.FontAdvanceTable
import com.eratw.port.core.font.FontSidecarCatalog
import com.eratw.port.core.font.SLOT_FONT_SIDECAR
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 车道 L6 稳定性用例（图形度量 / 字体 sidecar 缓存 / STRJOIN 区间端点溢出）。
 *
 * 每一条都对应一处**可证伪**的改动：把 main 上的旧实现放回去，对应用例必红
 * （证伪方式逐条写在用例 KDoc 里）。语义依据见各改动点的 KDoc 与
 * `reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`。
 */
private class L6BaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("L6BaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

/** 计数用的文件口：`reads` 记录读取口被问了几次（负缓存是否生效就看它）。 */
private class L6Ops : FileOps {
    val files = LinkedHashMap<String, String>()
    var reads = 0

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun readText(path: String): String? {
        reads++
        return files[path]
    }

    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        files[path] = if (append) (files[path] ?: "") + text else text
        return true
    }

    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

/** 读取口可在用例中途替换（验证适配器只在宿主换口时重建）。 */
private class L6Host(var ops: FileOps? = null) : BuiltinHost {
    override val fileOps: FileOps? get() = ops
}

private fun l6Ctx(host: BuiltinHost = L6Host()): BuiltinEvalContext {
    val store = VariableStore()
    val base = L6BaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, host)
}

private fun l6Iv(v: Value): Long = (v as Value.IntV).v

private fun l6Sv(v: Value): String = (v as Value.StrV).v

private fun l6Call(ctx: BuiltinEvalContext, name: String, vararg args: Any): Value =
    ctx.callFunction(
        name,
        args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
    )

private fun l6RefCall(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
    ctx.callFunctionRef(
        name,
        args.map {
            when (it) {
                is VarRef -> it
                is Long -> IntLiteral(it)
                is String -> StrLiteral(it)
                else -> throw IllegalArgumentException("$it")
            }
        },
    )

private fun l6RefS(name: String, vararg indices: Long): VarRef =
    VarRef(name, indices.map { IntLiteral(it) }, operandType = OperandType.STR)

private fun l6Catalog(ctx: BuiltinEvalContext): FontSidecarCatalog =
    ctx.store.aux.slot(SLOT_FONT_SIDECAR) { FontSidecarCatalog() }

private const val L6_TABLE = "#family MeasureFont\n#unitsPerEm 1280\n" +
    "0020 400 0\n" +
    "0041 800 0\n"

class StabilityL6Test {

    // ==================================================================
    // 1) STRJOIN：区间端点 start + count 溢出成负数
    // ==================================================================

    /**
     * 原版把 `num + num2`（unchecked long）交给 `IsArrayRangeValid`，那边对 index2 同样查
     * `< 0`（VariableData.cs:96-99）⇒ 溢出必抛 CodeEE；旧移植实现只判 `start + count > len`，
     * 回绕成负数时为假，静默返回空串。
     *
     * 证伪：把 `end` 判据改回 `start + count > len`，第二/三条 assertFailsWith 会变成返回值。
     */
    @Test
    fun `STRJOIN 区间端点溢出按原版报错而不是静默空串`() {
        val ctx = l6Ctx()
        ctx.store.declareUserVar(DimSpec("L6STR", isStr = true, dims = intArrayOf(4)))
        ctx.store.writeStr("L6STR", longArrayOf(0), "a")
        ctx.store.writeStr("L6STR", longArrayOf(1), "b")
        ctx.store.writeStr("L6STR", longArrayOf(2), "c")
        ctx.store.writeStr("L6STR", longArrayOf(3), "d")
        assertEquals("b,c", l6Sv(l6RefCall(ctx, "STRJOIN", l6RefS("L6STR"), ",", 1L, 2L)))
        // start=1 + Long.MAX_VALUE 回绕到 Long.MIN_VALUE：官方 index2 < 0 → CodeEE
        assertFailsWith<CodeEE> { l6RefCall(ctx, "STRJOIN", l6RefS("L6STR"), ",", 1L, Long.MAX_VALUE) }
        // 无溢出但越界的那一档保持原判据
        assertFailsWith<CodeEE> { l6RefCall(ctx, "STRJOIN", l6RefS("L6STR"), ",", 2L, 3L) }
        // start == len && count == 0 仍是合法空区间
        assertEquals("", l6Sv(l6RefCall(ctx, "STRJOIN", l6RefS("L6STR"), ",", 4L, 0L)))
    }

    // ==================================================================
    // 2) GGETTEXTSIZE：正字号 × 多行 的 long 乘法溢出
    // ==================================================================

    /**
     * 脚本字号只校验 `> 0`（FunctionMethodCreator.cs:6562 无上界、:6584 只拒非正），
     * 旧实现 `sizePx * lineCount` 回绕后把负数写进 RESULT_ARRAY[1]。
     * 修复后溢出饱和到 Long.MAX_VALUE；可达域内（单行 / 正常字号）读数不变。
     *
     * 证伪：删掉饱和分支恢复裸乘法，高度会等于 Long.MIN_VALUE。
     */
    @Test
    fun `GGETTEXTSIZE 两行超高字号高度饱和而不是负数`() {
        val ctx = l6Ctx()
        val huge = Long.MAX_VALUE / 2 + 1
        l6Call(ctx, "GGETTEXTSIZE", "a\nb", "黑体", huge)
        val h = ctx.store.readInt("RESULT", longArrayOf(1))
        assertEquals(Long.MAX_VALUE, h)
        assertTrue(h > 0)
        // 正常字号：两行 = 字号 × 2（旧读数不变）
        l6Call(ctx, "GGETTEXTSIZE", "a\nb", "黑体", 16L)
        assertEquals(32L, ctx.store.readInt("RESULT", longArrayOf(1)))
        // 单行仍是字号本身
        l6Call(ctx, "GGETTEXTSIZE", "ab", "黑体", 16L)
        assertEquals(16L, ctx.store.readInt("RESULT", longArrayOf(1)))
    }

    // ==================================================================
    // 3) fontCatalogOf：读取口适配器热路径复用
    // ==================================================================

    /**
     * 旧实现每次 `GDRAWTEXT` / `GGETTEXTSIZE` 都给 `catalog.reader` 新建一个捕获 lambda
     * （逐帧多一次分配）。修复后宿主 FileOps 实例不变就复用同一个适配器；换口才重建，
     * 且没有文件口时按 `reader == null` 归还（load 的负缓存约定依赖这一点）。
     *
     * 证伪：把实现改回每次新建 lambda，`assertSame` 会红。
     */
    @Test
    fun `字体读取口在宿主不变时复用同一实例`() {
        val ops = L6Ops()
        val host = L6Host(ops)
        val ctx = l6Ctx(host)
        val catalog = l6Catalog(ctx)
        l6Call(ctx, "GGETTEXTSIZE", "A", "黑体", 16L)
        val first = catalog.reader
        assertNotNull(first)
        l6Call(ctx, "GGETTEXTSIZE", "A", "黑体", 16L)
        assertSame(first, catalog.reader)
        // 宿主摘掉文件口：reader 必须回到 null（不是留一个会走的适配器）。用一个没查过的名字，
        // 才能走到 load 的「reader == null」分支（已缓存的负结果会提前返回，不刷新 lastReason）。
        host.ops = null
        l6Call(ctx, "GGETTEXTSIZE", "A", "NoSuchL6", 16L)
        assertNull(catalog.reader)
        assertTrue(catalog.lastReason!!.contains("未接入"))
        // 换一个新口：适配器重建，并确实从新口读到表（A = 800/1280 * 16 = 10）
        val ops2 = L6Ops().apply { files["fonts/measurefont-advance.txt"] = L6_TABLE }
        host.ops = ops2
        assertEquals(10L, l6Iv(l6Call(ctx, "GGETTEXTSIZE", "A", "MeasureFont", 16L)))
        assertNotNull(catalog.reader)
    }

    // ==================================================================
    // 4) FontSidecarCatalog：脚本字体名驱动的负缓存有界
    // ==================================================================

    /**
     * 缓存键来自脚本给的字体名，数量随脚本走。修复后负缓存只留前
     * `MAX_MISS_CACHE_ENTRIES` 个键：超限的缺失仍返回 null、仍写 lastReason，但不再入表
     * （再查会重新问一次读取口，结果不变）。正表一条都不淘汰。
     *
     * 证伪：去掉上限（旧实现什么都缓存），末尾对超限名字的 reads 断言会红。
     */
    @Test
    fun `负缓存条数有上界且不淘汰正表`() {
        val ops = L6Ops()
        val catalog = FontSidecarCatalog(reader = { path -> ops.readText(path) })
        catalog.put(FontAdvanceTable.parse(L6_TABLE))
        val cap = FontSidecarCatalog.MAX_MISS_CACHE_ENTRIES
        for (i in 0 until cap + 10) {
            assertNull(catalog.tableFor("MissingFont$i"))
        }
        val afterFill = ops.reads
        assertTrue(afterFill > 0)
        // 前 cap 个名字已在负缓存：再查不再问读取口
        catalog.tableFor("MissingFont0")
        assertEquals(afterFill, ops.reads)
        // 第 cap+9 个名字超限未入表：再查会重新问读取口
        catalog.tableFor("MissingFont${cap + 9}")
        assertTrue(ops.reads > afterFill)
        // 缺失语义没变：仍返回 null，lastReason 仍可读
        assertNull(catalog.tableFor("MissingFont${cap + 9}"))
        assertNotNull(catalog.lastReason)
        // 正表不受负缓存上限影响
        assertNotNull(catalog.tableFor("MeasureFont"))
    }
}
