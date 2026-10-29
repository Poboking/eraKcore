package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DT_SELECT 的 filter/sort 表达式子集（M4-L l-dtsql，分歧 H 接线）的用例面。
 *
 * 权威判据（`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`）：
 * - DataTableSelectMethod :2622-2677 —— 分派在 :2653-2655（sort 非 null → `Select(filter, sort)`、
 *   filter 为 null → `Select()`、否则 `Select(filter)`；空串 filter 进 `Select("")` = 全表），
 *   dest 形态 :2656-2668（有第 4 参从下标 0 写、不写总数；无第 4 参写 RESULT:0 = 总数、:1.. = id），
 *   返回命中数 :2669/:2675。
 * - 子集形态全部取自语料实见文本：DLC/自室描写.ERB:293/:306、魔改内容/UFUFU_LOG.ERB:125/:133/:385、
 *   CHARALIST_DT/TEMP_CHARA_LIST_BY_DT_FUNC.ERB:957-1030（@BUILD_FILTER/@BUILD_SORTRULE 的文本
 *   生成式）与调用点 :1790/:1815、MOVEMENTS/物件関連/MAP_NODE_TO_XML.ERB:133/:158/:161/:264/:337/:341。
 * - DBNull 比较与排序位次是 .NET 框架语义（仓库无行号），本实现按「比较必不命中、排序最前」钉桩，
 *   见 dtSelect KDoc 的 TODO(verify)；字符串序比较用 ordinal（.NET Select 的 culture 口径同属
 *   TODO(verify)，语料排序键均为整数列或大小写一致的 ASCII 列，两口径在语料不可分辨）。
 */
private class SelCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("SelCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun selCtx(clock: Long = 1_700_000_000_000L): BuiltinEvalContext {
    val store = VariableStore()
    val base = SelCtx()
    base.variables = store
    val host = object : BuiltinHost {
        override fun clockMillis(): Long = clock
    }
    return BuiltinEvalContext(base, store, host)
}

private fun selInt(c: BuiltinEvalContext, name: String, vararg a: Value?): Long =
    (c.callFunction(name, a.toList()) as Value.IntV).v

private fun aI(v: Long): Value = Value.IntV(v)

private fun aS(v: String): Value = Value.StrV(v)

/** 建 int64 列（类型码 4，Utils.cs:198-205）并加一行；返回行 id。 */
private fun addInt(c: BuiltinEvalContext, table: String, col: String, v: Long): Long =
    selInt(c, "DT_ROW_ADD", aS(table), aS(col), aI(v))

private fun addStr(c: BuiltinEvalContext, table: String, col: String, v: String): Long =
    selInt(c, "DT_ROW_ADD", aS(table), aS(col), aS(v))

/** 4 参有 dest 形态（dest = RESULT 的 VarRef）：返回命中数；ids 从 RESULT:0 起写、不写总数。 */
private fun selectIntoResult(c: BuiltinEvalContext, table: String, filter: String, sort: String): Long =
    (c.callFunctionRef(
        "DT_SELECT",
        listOf(
            StrLiteral(table),
            StrLiteral(filter),
            StrLiteral(sort),
            VarRef("RESULT", emptyList(), operandType = OperandType.INT),
        ),
    ) as Value.IntV).v

class DtSelectExprTest {

    // ==================================================================
    // 子集内：filter
    // ==================================================================

    @Test
    fun `空 filter 与缺省同走全表`() {
        val c = selCtx()
        selInt(c, "DT_CREATE", aS("T"))
        selInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        addStr(c, "T", "s", "a")
        addStr(c, "T", "s", "b")
        assertEquals(2L, selInt(c, "DT_SELECT", aS("T")))
        assertEquals(2L, selInt(c, "DT_SELECT", aS("T"), aS("")))
    }

    @Test
    fun `整数列六个比较符逐个对钉`() {
        val c = selCtx()
        selInt(c, "DT_CREATE", aS("T"))
        selInt(c, "DT_COLUMN_ADD", aS("T"), aS("n"), aI(4))
        listOf(1L, 3L, 5L, 7L).forEach { addInt(c, "T", "n", it) }
        // 2 行 < 5（1、3），1 行 = 5，2 行 > 3（5、7）……
        assertEquals(1L, selInt(c, "DT_SELECT", aS("T"), aS("n = 5")))
        assertEquals(3L, selInt(c, "DT_SELECT", aS("T"), aS("n <> 5")))
        assertEquals(1L, selInt(c, "DT_SELECT", aS("T"), aS("n > 5")))
        assertEquals(2L, selInt(c, "DT_SELECT", aS("T"), aS("n >= 5")))
        assertEquals(1L, selInt(c, "DT_SELECT", aS("T"), aS("n < 3")))
        assertEquals(2L, selInt(c, "DT_SELECT", aS("T"), aS("n <= 3")))
        // 负字面量（原版数值字面量允许一元负号，自室描写.ERB:293 的日期下界可为负）。
        assertEquals(0L, selInt(c, "DT_SELECT", aS("T"), aS("n < -1")))
        assertEquals(4L, selInt(c, "DT_SELECT", aS("T"), aS("n >= -1")))
    }

    @Test
    fun `AND 大小写不敏感的多条件合取（自室描写293 形态）`() {
        val c = selCtx()
        selInt(c, "DT_CREATE", aS("T"))
        selInt(c, "DT_COLUMN_ADD", aS("T"), aS("PLACE"), aI(4))
        selInt(c, "DT_COLUMN_ADD", aS("T"), aS("DATE"), aI(4))
        // DT_ROW_ADD 单列形态下 DATE 是 DBNull（必不命中），用 DT_CELL_SET 按 id 补值（:2568-2619）。
        fun row(place: Long, date: Long): Long {
            val id = addInt(c, "T", "PLACE", place)
            selInt(c, "DT_CELL_SET", aS("T"), aI(id), aS("DATE"), aI(date), aI(1L))
            return id
        }
        row(5L, 8000L)
        row(5L, 13000L)
        row(5L, 100L) // 日期太小
        row(9L, 8000L) // 地点不符
        // 复刻 自室描写.ERB:293 的文本形态：= AND >= <，大写 AND，窗口 [7200, 14400)。
        assertEquals(
            2L,
            selInt(c, "DT_SELECT", aS("T"), aS("PLACE = 5 AND DATE >= 7200 AND DATE < 14400")),
        )
        // 小写 and（TEMP_CHARA_LIST_BY_DT_FUNC.ERB:990 生成的是 ` and `）。
        assertEquals(
            2L,
            selInt(c, "DT_SELECT", aS("T"), aS("PLACE = 5 and DATE >= 7200 and DATE < 14400")),
        )
        // 混合大小写 And。
        assertEquals(1L, selInt(c, "DT_SELECT", aS("T"), aS("PLACE = 9 And DATE >= 7200")))
    }

    @Test
    fun `字符串列的单引号字面量与双写转义`() {
        val c = selCtx()
        selInt(c, "DT_CREATE", aS("T"))
        selInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        addStr(c, "T", "s", "abc")
        addStr(c, "T", "s", "it's")
        addStr(c, "T", "s", "ABC")
        assertEquals(1L, selInt(c, "DT_SELECT", aS("T"), aS("s = 'abc'")))
        // '' 转义出单引号（MAP_NODE_TO_XML.ERB:158 的 `'%DT_OP%'` 形态在无引号内容时同理）。
        assertEquals(1L, selInt(c, "DT_SELECT", aS("T"), aS("s = 'it''s'")))
        // CaseSensitive=true（:1993-1996 建表默认）→ 'ABC' 与 'abc' 不同。
        assertEquals(1L, selInt(c, "DT_SELECT", aS("T"), aS("s = 'ABC'")))
        // ordinal 序：'abc' < 'abd'、'ABC' < 'abd'（大写在前），只有 "it's" > 'abd'。
        assertEquals(1L, selInt(c, "DT_SELECT", aS("T"), aS("s > 'abd'")))
        // DT_NOCASE(T, 0) → CaseSensitive=false，比较折叠大小写（:1972-1978）。
        // 组长实测改钉（worker 期望 2 是对自身实现方向的误读，非产品分歧）：
        // 原版 `dataDataTables[strValue].CaseSensitive = arguments[1].GetIntValue(exm) == 0`
        // （FunctionMethodCreator.cs:1975）⇒ 传 **0 恰恰是「大小写敏感」**，'abc' 与 'ABC' 不折叠。
        // 移植侧本文件 :110 的 KDoc 已复刻同一口径，故实际读数 1 才是原版读数。
        selInt(c, "DT_NOCASE", aS("T"), aI(0))
        assertEquals(1L, selInt(c, "DT_SELECT", aS("T"), aS("s = 'abc'")))
        // 真·忽略大小写要传非 0（`CaseSensitive = (x == 0)` ⇒ x≠0 → false）→ 'abc'/'ABC' 同命中。
        selInt(c, "DT_NOCASE", aS("T"), aI(1))
        assertEquals(2L, selInt(c, "DT_SELECT", aS("T"), aS("s = 'abc'")))
    }

    @Test
    fun `DBNull 格必不命中`() {
        val c = selCtx()
        selInt(c, "DT_CREATE", aS("T"))
        selInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        val id1 = addStr(c, "T", "s", "a")
        addStr(c, "T", "s", "b")
        // 5 参 + 空 value = 显式写 DBNull（:2596-2599），byId=1（:2568-2583）。
        val before = selInt(c, "DT_CELL_SET", aS("T"), aI(id1), aS("s"), null, aI(1L))
        assertEquals(1L, before)
        // 原版 .NET：与 null 的 = <> 比较都非真（TODO(verify) 框架语义，见产品 KDoc）；
        // `s <> 'a'` 的 1 命中来自未被置空的那行 'b'，DBNull 行不在其中。
        assertEquals(0L, selInt(c, "DT_SELECT", aS("T"), aS("s = 'a'")))
        assertEquals(1L, selInt(c, "DT_SELECT", aS("T"), aS("s <> 'a'")))
        assertEquals(1L, selInt(c, "DT_SELECT", aS("T"), aS("s = 'b'")))
    }

    // ==================================================================
    // 子集内：sort
    // ==================================================================

    @Test
    fun `排序单列升降序与缺省方向`() {
        val c = selCtx()
        selInt(c, "DT_CREATE", aS("T"))
        selInt(c, "DT_COLUMN_ADD", aS("T"), aS("n"), aI(4))
        val idA = addInt(c, "T", "n", 5L)
        val idB = addInt(c, "T", "n", 1L)
        val idC = addInt(c, "T", "n", 9L)
        // UFUFU_LOG.ERB:118-121 的 `列 ASC` / `列 DESC`。
        assertEquals(3L, selectIntoResult(c, "T", "", "n ASC"))
        assertEquals(idB, c.store.readInt("RESULT", longArrayOf(0L)))
        assertEquals(idA, c.store.readInt("RESULT", longArrayOf(1L)))
        assertEquals(idC, c.store.readInt("RESULT", longArrayOf(2L)))
        assertEquals(3L, selectIntoResult(c, "T", "", "n DESC"))
        assertEquals(idC, c.store.readInt("RESULT", longArrayOf(0L)))
        // 缺省方向 = ASC（.NET DataView 缺省；TEMP_CHARA_LIST_BY_DT_FUNC.ERB:1784 的
        // DT_SORT_TYPE 表若非空串形态也走这条）。
        assertEquals(3L, selectIntoResult(c, "T", "", "n"))
        assertEquals(idB, c.store.readInt("RESULT", longArrayOf(0L)))
    }

    @Test
    fun `多列排序与 filter 加 sort 的组合`() {
        val c = selCtx()
        selInt(c, "DT_CREATE", aS("T"))
        selInt(c, "DT_COLUMN_ADD", aS("T"), aS("g"), aI(4))
        selInt(c, "DT_COLUMN_ADD", aS("T"), aS("cost"), aI(4))
        fun row(g: Long, cost: Long): Long {
            val id = addInt(c, "T", "g", g)
            selInt(c, "DT_CELL_SET", aS("T"), aI(id), aS("cost"), aI(cost), aI(1L))
            return id
        }
        // TEMP_CHARA_LIST_BY_DT_FUNC.ERB:1025 生成 `colA ASC,colB DESC`（逗号后无空格）。
        val g1c3 = row(1L, 3L)
        val g1c1 = row(1L, 1L)
        val g0c5 = row(0L, 5L)
        assertEquals(3L, selectIntoResult(c, "T", "g >= 0", "g ASC,cost DESC"))
        assertEquals(g0c5, c.store.readInt("RESULT", longArrayOf(0L)))
        assertEquals(g1c3, c.store.readInt("RESULT", longArrayOf(1L)))
        assertEquals(g1c1, c.store.readInt("RESULT", longArrayOf(2L)))
        // 大小写混排 + 逗号后空格。
        assertEquals(3L, selectIntoResult(c, "T", "g >= 0", "g asc, cost desc"))
        assertEquals(g0c5, c.store.readInt("RESULT", longArrayOf(0L)))
    }

    @Test
    fun `dest 形态与 RESULT 形态的写位差异（子集内 filter）`() {
        val c = selCtx()
        selInt(c, "DT_CREATE", aS("T"))
        selInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        val id1 = addStr(c, "T", "s", "a")
        addStr(c, "T", "s", "b")
        // 无 dest：RESULT:0 = 总数、:1.. = id（:2663-2668）。
        assertEquals(1L, selInt(c, "DT_SELECT", aS("T"), aS("s = 'a'")))
        assertEquals(1L, c.store.readInt("RESULT", longArrayOf(0L)))
        assertEquals(id1, c.store.readInt("RESULT", longArrayOf(1L)))
    }

    // ==================================================================
    // 子集外：必须抛、禁止静默回全表
    // ==================================================================

    @Test
    fun `超出子集的 filter 形态一律抛错且不回全表`() {
        val c = selCtx()
        selInt(c, "DT_CREATE", aS("T"))
        selInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        addStr(c, "T", "s", "a")
        addStr(c, "T", "s", "b")
        val bad = listOf(
            "s = 'a' OR s = 'b'", // 并集未覆盖（语料只注释提到 or，生成代码不发）
            "s LIKE 'a%'",
            "id IN (1,2)",
            "(s = 'a')",
            "s IS NULL",
            "LEN(s) = 1",
            "s + '' = 'a'",
            "s = 'a", // 引号未闭合
            "s =", // 缺字面量
            "= 'a'", // 缺列名
            "nope = 'a'", // 列不存在
            "s = 5", // 字符串列 vs 整字面量：解析期类型错
            "s <> 'a' garbage", // and 之外的连接词残文本
        )
        for (f in bad) {
            val ex = assertFailsWith<CodeEE>("形态不该静默通过：$f") {
                selInt(c, "DT_SELECT", aS("T"), aS(f))
            }
            assertTrue(ex.message!!.contains(f), "报错要带原文：${ex.message}")
        }
        // 抛错不得留下「全表」副作用：无 dest 形态整条没执行，RESULT:0 不被改动。
        // （初值 0，任何一次成功调用都会把 RESULT:0 写成命中数；这里只钉「没有写 2」。）
        c.store.writeInt("RESULT", longArrayOf(0L), -7L)
        for (f in listOf("s = 'a' OR s = 'b'", "s IS NULL")) {
            assertFailsWith<CodeEE> { selInt(c, "DT_SELECT", aS("T"), aS(f)) }
        }
        assertEquals(-7L, c.store.readInt("RESULT", longArrayOf(0L)))
    }

    @Test
    fun `超出子集的 sort 形态一律抛错`() {
        val c = selCtx()
        selInt(c, "DT_CREATE", aS("T"))
        selInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        addStr(c, "T", "s", "a")
        val bad = listOf(
            "nope ASC", // 列不存在
            "s UP", // 方向词表外
            "s ASC DESC", // 残文本
            "s + 1", // 表达式排序
            ",s ASC", // 空段
        )
        for (s in bad) {
            val ex = assertFailsWith<CodeEE>("排序形态不该静默通过：$s") {
                selInt(c, "DT_SELECT", aS("T"), aS(""), aS(s))
            }
            assertTrue(ex.message!!.contains(s))
        }
    }

    // ==================================================================
    // 语料同构回归：三条真实调用文本
    // ==================================================================

    @Test
    fun `语料两条实见调用文本的读数`() {
        val c = selCtx()
        // UFUFU_LOG.ERB:397 同构：`UID = {n} AND MEMBER = {k}`（两 int64 列）。
        selInt(c, "DT_CREATE", aS("M"))
        selInt(c, "DT_COLUMN_ADD", aS("M"), aS("UID"), aI(4))
        selInt(c, "DT_COLUMN_ADD", aS("M"), aS("MEMBER"), aI(4))
        fun mRow(uid: Long, member: Long): Long {
            val id = addInt(c, "M", "UID", uid)
            selInt(c, "DT_CELL_SET", aS("M"), aI(id), aS("MEMBER"), aI(member), aI(1L))
            return id
        }
        val hit = mRow(7L, 2L)
        mRow(7L, 3L)
        mRow(8L, 2L)
        assertEquals(1L, selInt(c, "DT_SELECT", aS("M"), aS("UID = 7 AND MEMBER = 2")))
        // RESULT:1 = 命中行 id（无 dest 形态）。
        assertEquals(hit, c.store.readInt("RESULT", longArrayOf(1L)))
        // MAP_NODE_TO_XML.ERB:158 同构：字符串列 = '引号值' + 单列 ASC 排序。
        val p = selInt(c, "DT_CREATE", aS("P"))
        assertTrue(p == 1L)
        selInt(c, "DT_COLUMN_ADD", aS("P"), aS("STATUS"))
        selInt(c, "DT_COLUMN_ADD", aS("P"), aS("COST"), aI(4))
        fun pRow(status: String, cost: Long): Long {
            val id = addStr(c, "P", "STATUS", status)
            selInt(c, "DT_CELL_SET", aS("P"), aI(id), aS("COST"), aI(cost), aI(1L))
            return id
        }
        val p1 = pRow("MOVE", 5L)
        val p2 = pRow("MOVE", 1L)
        pRow("BLOCK", 2L)
        assertEquals(2L, selectIntoResult(c, "P", "STATUS = 'MOVE'", "COST ASC"))
        assertEquals(p2, c.store.readInt("RESULT", longArrayOf(0L)))
        assertEquals(p1, c.store.readInt("RESULT", longArrayOf(1L)))
    }
}
