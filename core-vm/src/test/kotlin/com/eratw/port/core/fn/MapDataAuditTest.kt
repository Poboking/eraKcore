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
 * MAP / DT 33 函数逐条 C# 对拍审计（M4-L l-dtaudit 路，前任 K-DTAUDIT 草稿重核版）的钉桩文件。
 * 分歧 B（DT_COLUMN_ADD 老行扩容）与分歧 J（DT_FROMXML 的 MainDataTable 卡名）已在
 * wave/audit-dt 修复，对应用例改钉**修后读数**；其余条目仍是「登记现状 / 不修正」口径。
 * 文件内引用的移植侧行号以 wave/audit-dt 修复前为准，修复后整体下移约 10-40 行。
 *
 * 权威类体（`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`，行号已在
 * main@d643e18 起点逐个复读）：
 * - MAP：MapManagementMethod :2765-2808、MapDataOperationMethod :2810-2888、
 *   MapGetStrMethod :2890-3029、MapFromXmlMethod :3031-3077（注册名 :9244-9255）
 * - DT：DataTableManagementMethod :1926-2006、DataTableColumnManagementMethod :2008-2124、
 *   DataTableRowSetMethod :2126-2323、DataTableLengthMethod :2325-2353、
 *   DataTableRowRemoveMethod :2355-2430、DataTableCellGetMethod :2432-2543、
 *   DataTableCellSetMethod :2545-2620、DataTableSelectMethod :2622-2677、
 *   DataTableToXmlMethod :2679-2718、DataTableFromXmlMethod :2720-2763（注册名 :9256-9276）
 * 辅助：`MinorShift.Emuera.Runtime.Utils.EvilMask/Utils.cs`（DataTable 内建类型表 :116-123、
 * TypeToInt :173-196、IntToType :198-205、NameToType :207-214、ConvertInt :225-240、
 * stopwatch_base :245、TimePoint :464-471）、`MinorShift.Emuera.Runtime.Utils.EvilMask/Lang.cs`
 * （XmlParseError :1374、UnsupportedType :3954、DTLackOfNamedColumn :3962、DTInvalidDataType :3966、
 * DTCanNotEditIdColumn :3970）、`MinorShift.Emuera.Runtime.Script.Statements.Function/FunctionMethod.cs`
 * （变参成对检查 :186-193，ArgsNotFitExpr 在 :191）。
 *
 * golden 主路径（pc-golden-root.txt:550-562 与 :591-617）已由同包 MapDataTableMethodsTest 钉死，
 * 本文件只钉审计发现的**分歧现状**与既有测试没盖住的口径：
 * - 「钉现状」= 断言移植侧当前行为；处置三分法（钉现状 / 登记不修正 / 需组长接线）写在断言旁注释里。
 * - 逐条对拍结论（33 个函数每个都给了 C# 行号判定）在交付报告的分歧清单表里，
 *   本文件只承载其中需要用例固化的条目；与基准无差且已被既有测试覆盖的条目不在此重复。
 * - 移植侧行号引用按 main 现状（MapDataTableMethods.kt 在 ec46ee1 M6-SAVE-REST 后共 1502 行）复读更新过；
 *   前任草稿里的旧行号（502-508 / 731-736 / 810-813 / 818 / 837 / 887-888 / 1051 / 1070-1081 等）已全部换正。
 *
 * 审计结论里不设用例的三条口径（免得重复既有覆盖）：
 * - 参数求值顺序：原版 GetIntValue 的实参消费序为 0→3→1→2（:2466、:2472、:2474、:2475），
 *   移植侧同序（MapDataTableMethods.kt:820 → cellRow :813 → :814 → :822）；成对循环
 *   （:2307-2315）与移植侧 :741-746 同序。DT_CELL_SET 两侧都是 0→2→4（移植 :855-858），
 *   无副作用实参下不可观测，无分歧。
 * - 变参成对检查在原版是**编译期**（FunctionMethod.cs:186-193 ArgsNotFitExpr），移植侧落在
 *   运行期 CodeEE（MapDataTableMethods.kt:742、:779）：报错等价、相位不同；移植编译层是否
 *   复刻同一检查归组长确认（需组长接线，本路禁改 core/compile 面）。
 * - 引用型实参传非变量项时原版是裸强转（:2282、:2296、:2657、:2708 的 as VariableTerm → NRE），
 *   唯 DT_COLUMN_NAMES 例外——:2078 的三元判到非 VariableTerm 会**静默回落 RESULTS_ARRAY**；
 *   这些都先被解析期 Ref 形参校验（FunctionMethod.cs:208 起的 argType.Ref 分支）挡住，
 *   两侧运行期均不可达。移植侧回落 "" / CodeEE，登记不修正。
 */
private class AuditBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("AuditBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/** 固定时钟（行 id 是时间戳读数，见行主键那一条用例）。 */
private fun auditCtx(clock: Long = 1_700_000_000_000L): BuiltinEvalContext {
    val store = VariableStore()
    val base = AuditBaseCtx()
    base.variables = store
    val host = object : BuiltinHost {
        override fun clockMillis(): Long = clock
    }
    return BuiltinEvalContext(base, store, host)
}

private fun auditInt(c: BuiltinEvalContext, name: String, vararg a: Value?): Long =
    (c.callFunction(name, a.toList()) as Value.IntV).v

private fun auditStr(c: BuiltinEvalContext, name: String, vararg a: Value?): String =
    (c.callFunction(name, a.toList()) as Value.StrV).v

private fun aI(v: Long): Value = Value.IntV(v)

private fun aS(v: String): Value = Value.StrV(v)

/** 取「最近一次 DT_SELECT 无 dest 形态」写在 RESULT:1 里的行 id。 */
private fun auditFirstRowId(c: BuiltinEvalContext, table: String): Long {
    auditInt(c, "DT_SELECT", aS(table))
    return c.store.readInt("RESULT", longArrayOf(1L))
}

/** DT_ROW_REMOVE 三参形态（id 数组 + count）经 RESULT 数组驱动的调用器。 */
private fun auditRemoveIds(c: BuiltinEvalContext, table: String, count: Long): Long =
    (c.callFunctionRef(
        "DT_ROW_REMOVE",
        listOf(
            StrLiteral(table),
            VarRef("RESULT", emptyList(), operandType = OperandType.INT),
            IntLiteral(count),
        ),
    ) as Value.IntV).v

class MapDataAuditTest {

    // ==================================================================
    // 对齐后必须重读的事实：DT 行主键的时区口径（CONTRACT-M4-L §1 l-dtaudit 行）
    // ==================================================================

    @Test
    fun `DT 行主键的时区口径与 GETMILLISECOND 同源本地墙钟`() {
        val clock = 1_700_000_000_000L
        val c = auditCtx(clock)
        auditInt(c, "DT_CREATE", aS("T"))
        val id1 = auditInt(c, "DT_ROW_ADD", aS("T"))
        // 原版口径：行 id 写入点 FunctionMethodCreator.cs:2278 = Utils.TimePoint()（Utils.cs:464-471）
        // = stopwatch_base + Stopwatch.Elapsed.Ticks，而 stopwatch_base = DateTime.Now.Ticks
        // （Utils.cs:245）——**本地墙钟**，与 GETMILLISECOND 的 DateTime.Now.Ticks 10000 分之一
        // （FunctionMethodCreator.cs:4008）同一时基，自公元 0001-01-01 起算的 100ns 计数。
        // 移植侧 main 现状：MapDataTableMethods.kt:511-517 走 TimeMethods.netTicks
        // （TimeMethods.kt:220-221），内部先 localWallMillis 加**当时**（per-instant，考虑夏令时）
        // 的本地 UTC 偏移（TimeMethods.kt:185-186）再乘 10000 ⇒ Asia/Shanghai 下即 +28,800,000 ms 口径，
        // 与 GETMILLISECOND 完全同源。任务线索里「DT 侧未动、照抄旧结论」作废：j-dispatch（e787646）
        // 修的是共享的 netTicks 本体，DT 行主键随之一同修正；本用例直通断言「行 id == netTicks(clock)」
        // 是对当前实现**实测**的读数，不依赖主机时区取值。
        assertEquals(TimeMethods.netTicks(clock), id1)
        assertEquals(18, id1.toString().length, "18 位十进制（基准 DATA_LEN=179 依赖）")
        val id2 = auditInt(c, "DT_ROW_ADD", aS("T"))
        // 同一毫秒内靠 lastRowId 严格递增（MapDataTableMethods.kt:492-493、:514-516）；原版靠 Stopwatch
        // 持续走（Utils.cs:466-470），理论上有同 100ns 刻返回同值的窗口（届时 Rows.Add 撞
        // Unique 主键抛异常），不可确定复现，移植侧取安全超集——登记不修正。
        assertEquals(id1 + 1, id2)
    }

    // ==================================================================
    // DT_NOCASE 的作用面
    // ==================================================================

    @Test
    fun `DT_NOCASE 作用面是列名查找而非表名池`() {
        val c = auditCtx()
        assertEquals(1L, auditInt(c, "DT_CREATE", aS("t")))
        assertEquals(1L, auditInt(c, "DT_CREATE", aS("T")))
        // 表名池是独立的 Dictionary（FunctionMethodCreator.cs:1961、:2070），永远 ordinal
        // 区分大小写，DT_NOCASE 管不到它。
        assertEquals(0L, auditInt(c, "DT_EXIST", aS("T2")))
        assertEquals(1L, auditInt(c, "DT_EXIST", aS("t")))
        auditInt(c, "DT_COLUMN_ADD", aS("t"), aS("c1"))
        // CaseSensitive = (x == 0)（FunctionMethodCreator.cs:1975）只改本表的列名比较器。
        assertEquals(1L, auditInt(c, "DT_NOCASE", aS("t"), aI(1)))
        // 命中返回**类型号**（TypeToInt，Utils.cs:173-196）：默认列型是 string ⇒ 5，不是布尔 1
        // （:2094；前任草稿此处误写 1，已按 :2094 改钉 5）。
        assertEquals(5L, auditInt(c, "DT_COLUMN_EXIST", aS("t"), aS("C1")))
        assertEquals(0L, auditInt(c, "DT_COLUMN_EXIST", aS("T"), aS("c1")), "不跨表")
        // DT_CLEAR 只清行（:1965-1971），NOCASE 状态保持。
        assertEquals(1L, auditInt(c, "DT_CLEAR", aS("t")))
        assertEquals(5L, auditInt(c, "DT_COLUMN_EXIST", aS("t"), aS("C1")))
        // 行写入路径同样吃 CaseSensitive（CheckName 走 Columns.Contains，:2204-2207）。
        val rid = auditInt(c, "DT_ROW_ADD", aS("t"), aS("C1"), aS("v"))
        assertTrue(rid > 0L)
    }

    // ==================================================================
    // DT_CELL_GET / ISNULL / GETS 的 DBNull 与空串三态
    // ==================================================================

    @Test
    fun `DT_CELL_GET 与 DT_CELL_ISNULL 的 DBNull 空串整数列三态`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("n"), aI(4))
        // s = 空串，n = DBNull（行构造时未给值的可空列）。
        auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS(""))
        val id = auditFirstRowId(c, "T")
        // DBNull：GET 0（:2490/:2504）、ISNULL 1（:2484/:2498）、GETS ""（:2527-2541）。
        assertEquals(0L, auditInt(c, "DT_CELL_GET", aS("T"), aI(0), aS("n")))
        assertEquals(1L, auditInt(c, "DT_CELL_ISNULL", aS("T"), aI(0), aS("n")))
        assertEquals("", auditStr(c, "DT_CELL_GETS", aS("T"), aI(0), aS("n")))
        // 空串：GET 抛（Convert.ToInt64("") 解析失败，:2488/:2502 → 移植侧 CodeEE 等价面）、
        // GETS "" 与 DBNull 不可分辨（:2529/:2538）、ISNULL 0（:2484/:2498）。
        // ⇒ NULL 与空串的区分只靠 DT_CELL_ISNULL，两侧同构。
        assertFailsWith<CodeEE> { auditInt(c, "DT_CELL_GET", aS("T"), aI(0), aS("s")) }
        assertEquals("", auditStr(c, "DT_CELL_GETS", aS("T"), aI(0), aS("s")))
        assertEquals(0L, auditInt(c, "DT_CELL_ISNULL", aS("T"), aI(0), aS("s")))
        // 按行号（3 参，:2493）与按 id（4 参开关，:2472-2478）两条判定路径同一三态。
        assertEquals(1L, auditInt(c, "DT_CELL_ISNULL", aS("T"), aI(id), aS("n"), aI(1)))
        assertEquals(0L, auditInt(c, "DT_CELL_ISNULL", aS("T"), aI(id), aS("s"), aI(1)))
    }

    // ==================================================================
    // 分歧 G：DT_CELL_GETS 按 id 路径对整列的强转（ADR-005 议题）
    // ==================================================================

    @Test
    fun `DT_CELL_GETS 按 id 取整列强转中止（fixcore fn-data-B1）`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("n"), aI(4))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        auditInt(c, "DT_ROW_ADD", aS("T"), aS("n"), aI(123), aS("s"), aS("x"))
        val id = auditFirstRowId(c, "T")
        // fixcore fn-data-B1（2026-10-26）落地本注曾经预留的复刻方向：原版按行号路径是
        // obj.ToString()（:2535-2538）整列可取；按 id 路径却是 (string)obj 直接强转
        // （:2529）—— InvalidCastException 且链路无 try（FunctionMethodTerm.cs:20-23）
        // ⇒ 想定外中止。byId + 整列现在抛 CodeEE，行号路径维持 toString。
        assertEquals("123", auditStr(c, "DT_CELL_GETS", aS("T"), aI(0), aS("n")))
        assertFailsWith<CodeEE> { auditStr(c, "DT_CELL_GETS", aS("T"), aI(id), aS("n"), aI(1)) }
        assertEquals("x", auditStr(c, "DT_CELL_GETS", aS("T"), aI(id), aS("s"), aI(1)))
        // 缺行 / 缺列 → ""（:2541）。
        assertEquals("", auditStr(c, "DT_CELL_GETS", aS("T"), aI(99), aS("s")))
        assertEquals("", auditStr(c, "DT_CELL_GETS", aS("T"), aI(0), aS("NOPE")))
    }

    // ==================================================================
    // 分歧 E：字符串列读整数的解析面
    // ==================================================================

    @Test
    fun `DT_CELL_GET 字符串列读整数的解析面`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("12"))
        auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS(" 12 "))
        auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("abc"))
        assertEquals(12L, auditInt(c, "DT_CELL_GET", aS("T"), aI(0), aS("s")))
        // 现状：toLongOrNull 严格解析（MapDataTableMethods.kt:827），首尾空白与字母都 CodeEE。
        assertFailsWith<CodeEE> { auditInt(c, "DT_CELL_GET", aS("T"), aI(1), aS("s")) }
        assertFailsWith<CodeEE> { auditInt(c, "DT_CELL_GET", aS("T"), aI(2), aS("s")) }
        // 登记分歧（登记不修正）：原版 Convert.ToInt64(object)（:2488、:2502）对字符串走
        // Int64.Parse 的 NumberStyles.Integer，容忍首尾空白（" 12 " 读出 12）；解析失败抛
        // FormatException 而非 CodeEE（异常类型与文案不同构，框架语义无仓库行号）。
    }

    // ==================================================================
    // 分歧 C：DT_COLUMN_NAMES 越界
    // ==================================================================

    @Test
    fun `DT_COLUMN_NAMES 超出数组长度时抛越界`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        val cap = c.store.getLength("RESULTS", -1)
        for (i in 0 until cap + 3) auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("c" + i))
        // 修后读数（分歧 C，M4-M m-dtcolnames）：原版 :2079-2082 按列数无界写 array[i]，
        // 列数 > 目标数组长度 ⇒ 裸 .NET IndexOutOfRangeException，整个函数无返回值
        // （旧现状「静默 break 截断 + 返回真实列数」是移植侧自定，已废）。
        // 抛出形态链路：FunctionMethodTerm.cs:20-23 无 try；VariableTerm.cs:70-77 只包自身且 throw;
        // 顶层 Process.cs:462-477 → handleException(:595) 的非 EmueraException 分支(:651-660)。
        assertFailsWith<IndexOutOfBoundsException> { auditInt(c, "DT_COLUMN_NAMES", aS("T")) }
        // 崩溃前的部分写入与原版同构：0 until cap 已落进 RESULTS（抛点在 cap 那一拍）。
        assertEquals("id", c.store.readStr("RESULTS", longArrayOf(0L)))
        assertEquals("c" + (cap - 2), c.store.readStr("RESULTS", longArrayOf((cap - 1).toLong())))
        // RESULTS 默认长较小，常规表碰不到；逐条用例见同包 DtColumnNamesTest。
    }

    // ==================================================================
    // 分歧 D：不可空列未赋值
    // ==================================================================

    @Test
    fun `DT_ROW_ADD 不可空列未赋值的预填现状`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        // 第 4 参 0 → AllowDBNull=false（FunctionMethodCreator.cs:2117）。
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("n"), aI(4), aI(0))
        // 现状：newRow 预填类型零值（MapDataTableMethods.kt:476-487），行照常加入。
        auditInt(c, "DT_ROW_ADD", aS("T"))
        assertEquals(1L, auditInt(c, "DT_ROW_LENGTH", aS("T")))
        assertEquals(0L, auditInt(c, "DT_CELL_GET", aS("T"), aI(0), aS("n")))
        assertEquals(0L, auditInt(c, "DT_CELL_ISNULL", aS("T"), aI(0), aS("n")))
        // 登记分歧（需组长接线；ADR-005 议题）：原版 :2277 NewRow 对该列保持 DBNull、
        // :2318 Rows.Add 触发 ADO.NET 非空约束 → NoNullAllowedException（框架语义，
        // 仓库内无行内行号），即「加行时漏给不可空列」在 PC 上是运行错，移植侧静默 0。
    }

    // ==================================================================
    // DT_ROW_SET：id 先行判定与负计数回显（形参语法 :2168-2192、:2280-2315）
    // ==================================================================

    @Test
    fun `DT_ROW_SET 负数计数回显与 id 先行判定`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        val id = auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("v"))
        assertTrue(id > 0L)
        // 5 参数组形态（原版 Count == num+4 即 Set 的 5 参，:2181-2191 的形参表）：count 为负时
        // Math.Min 直通负值（:2283、:2297），循环不进、num2 累加负数、:2321 原样返回 ⇒ 移植侧
        // （MapDataTableMethods.kt:762-775）同构回显 -3。
        val r = c.callFunctionRef(
            "DT_ROW_SET",
            listOf(
                StrLiteral("T"),
                IntLiteral(id),
                VarRef("RESULTS", emptyList(), operandType = OperandType.STR),
                VarRef("RESULT", emptyList(), operandType = OperandType.INT),
                IntLiteral(-3),
            ),
        )
        assertEquals(-3L, (r as Value.IntV).v)
        assertEquals("v", auditStr(c, "DT_CELL_GETS", aS("T"), aI(0), aS("s")), "负计数未写任何格")
        // id 判定先行于列名检查（:2265-2273 在写入循环之前）：坏 id + 坏列 → -2 而非 CodeEE。
        assertEquals(-2L, auditInt(c, "DT_ROW_SET", aS("T"), aI(id + 1), aS("NOPE"), aS("x")))
        assertFailsWith<CodeEE> { auditInt(c, "DT_ROW_SET", aS("T"), aI(id), aS("NOPE"), aS("x")) }
        // 2 参形态：**原版解析期就拒**（Set 分支第一份 ArgTypeList 4 槽、OmitStart 缺省 -1 ⇒
        // arguments.Count >= 4，FunctionMethodCreator.cs:2168-2199 + FunctionMethod.cs:183/266），
        // 所以「行存在、无写入对 → 0」在原版不可达；N8 裁决（CMP-70）把注册下界从 2 抬到 4，
        // 移植侧在这条调用上由个数闸抛 CodeEE（相位=运行期，原版=解析期，可观测同为脚本错误）。
        assertFailsWith<CodeEE>("DT_ROW_SET 2 参在原版就不可达，注册下界 4 后必须被个数闸拒绝") {
            auditInt(c, "DT_ROW_SET", aS("T"), aI(id))
        }
    }

    // ==================================================================
    // DT_ROW_ADD：单参与失败路径、四参强制数组形态的路由
    // ==================================================================

    @Test
    fun `DT_ROW_ADD 单参与失败路径的行状态`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        // 1 参形态也建行（NewRow + TimePoint + Rows.Add，:2277-2279、:2316-2319），返回行 id。
        val id = auditInt(c, "DT_ROW_ADD", aS("T"))
        assertEquals(1L, auditInt(c, "DT_ROW_LENGTH", aS("T")))
        assertTrue(id > 0L)
        assertEquals(1L, auditInt(c, "DT_CELL_ISNULL", aS("T"), aI(0), aS("s")))
        // 坏列抛 CodeEE 且行不落（Rows.Add 在尾部 :2318；移植侧 MapDataTableMethods.kt:747）。
        assertFailsWith<CodeEE> { auditInt(c, "DT_ROW_ADD", aS("T"), aS("NOPE"), aS("x")) }
        assertEquals(1L, auditInt(c, "DT_ROW_LENGTH", aS("T")))
        // id 列保护是精确匹配（CheckName 的 name == "id"，:2200-2203）；
        // "ID" 不是 id 列保护路径，走缺列 CodeEE（:2204-2207）。
        assertFailsWith<CodeEE> { auditInt(c, "DT_ROW_ADD", aS("T"), aS("id"), aI(1)) }
        assertFailsWith<CodeEE> { auditInt(c, "DT_ROW_ADD", aS("T"), aS("ID"), aI(1)) }
    }

    @Test
    fun `DT_ROW_ADD 四参一律走数组形态与计数三重夹逼`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("n1"), aI(4))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("n2"), aI(4))
        // 原版 :2280 按 `arguments.Count == num + 4` 路由：Add 的 4 参**永远是数组形态**
        // （成对形态的 4 参在解析期就被 MatchVariadicGroup 归入奇数校验，:2141-2164 +
        // FunctionMethod.cs:186-193）；移植侧 MapDataTableMethods.kt:726 同判据 `e.size == 4`。
        c.store.writeStr("RESULTS", longArrayOf(0L), "n1")
        c.store.writeStr("RESULTS", longArrayOf(1L), "n2")
        c.store.writeInt("RESULT", longArrayOf(0L), 42L)
        c.store.writeInt("RESULT", longArrayOf(1L), 43L)
        val id = (
            c.callFunctionRef(
                "DT_ROW_ADD",
                listOf(
                    StrLiteral("T"),
                    VarRef("RESULTS", emptyList(), operandType = OperandType.STR),
                    VarRef("RESULT", emptyList(), operandType = OperandType.INT),
                    IntLiteral(2),
                ),
            ) as Value.IntV
            ).v
        assertTrue(id > 0L)
        // 计数三重夹逼 min(names.Length, count, values.Length)（:2283、:2297）：count 给 999 →
        // 夹到两个数组的公共长度 100 里非零段之外的空名照样命中 CheckName 抛错——原版如此，
        // 移植侧 nameArr 是整条 100 长数组，i 从 0 到 min(100, 999, 100)=100，
        // 第 2 格起列名 "" → CodeEE（MapDataTableMethods.kt:732-737 + :694）。
        assertFailsWith<CodeEE> {
            c.callFunctionRef(
                "DT_ROW_ADD",
                listOf(
                    StrLiteral("T"),
                    VarRef("RESULTS", emptyList(), operandType = OperandType.STR),
                    VarRef("RESULT", emptyList(), operandType = OperandType.INT),
                    IntLiteral(999),
                ),
            )
        }
        // 4 参成对形态（把值当第二对列名）在两侧都不可能出现：原版解析期路由到数组形态后
        // :2282 裸强转 NRE；移植侧值路径把 StrLiteral 当数组参数 → CodeEE（:727）。登记不修正。
        assertFailsWith<CodeEE> { auditInt(c, "DT_ROW_ADD", aS("T"), aS("n1"), aI(1), aS("n2")) }
    }

    // ==================================================================
    // 分歧 I：DT_ROW_REMOVE 三参形态计数的 (int) 截断（本路新发现，草稿没有）
    // ==================================================================

    @Test
    fun `DT_ROW_REMOVE 三参形态计数不做 Int32 截断的现状`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        val id1 = auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("a"))
        val id2 = auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("b"))
        c.store.writeInt("RESULT", longArrayOf(0L), id1)
        c.store.writeInt("RESULT", longArrayOf(1L), id2)
        // 原版 :2397 `Math.Min((int)arguments[2].GetIntValue(exm), array.Length)` ——count 先被
        // (int) 折到 Int32：2^32+1 → 1 ⇒ 只删 id1 一行、返回 1。移植侧原状（MapDataTableMethods.kt
        // 旧 :798）minOf 用完整 Long 再 .toInt() ⇒ n = min(2^32+1, 100) = 100，返回 2。
        // 【M4-L l-dtsql 已修】移植侧改为 `minOf(cnt.toInt(), arr.size)`，复刻 :2397 的截断
        // （Kotlin Long.toInt() 与 C# unchecked (int) 同为低 32 位回绕）⇒ 读数与原版一致。
        assertEquals(1L, auditRemoveIds(c, "T", (1L shl 32) + 1))
        assertEquals(1L, auditInt(c, "DT_ROW_LENGTH", aS("T")))
        // 负计数与 0 两侧同返回 0（:2398-2401），无差。
    }

    // ==================================================================
    // DT_ROW_REMOVE：计数边界与重复 id 集合语义
    // ==================================================================

    @Test
    fun `DT_ROW_REMOVE 计数边界与重复 id 集合语义`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        val id1 = auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("a"))
        auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("b"))
        assertTrue(id1 > 0L)
        // count = 0 → 0（:2397 取 min 后 :2398-2401 的 num <= 0）；负数同口径。
        c.store.writeInt("RESULT", longArrayOf(0L), id1)
        assertEquals(0L, auditRemoveIds(c, "T", 0))
        // 重复 id 是集合语义：IN 筛选每行只命中一次，返回去重后的命中行数（:2408、:2428）。
        c.store.writeInt("RESULT", longArrayOf(0L), id1)
        c.store.writeInt("RESULT", longArrayOf(1L), id1)
        assertEquals(1L, auditRemoveIds(c, "T", 2))
        assertEquals(1L, auditInt(c, "DT_ROW_LENGTH", aS("T")))
        // 2 参形态按 **id** 找行（Rows.Find，:2416-2420），不是行号：行号 0 现在指的是剩下那行
        // 的 id（id 是 18 位时间戳）⇒ 传 0 返回 0。基准 DT_ROW_REMOVE=0 的指纹（:609-610）。
        assertEquals(0L, auditInt(c, "DT_ROW_REMOVE", aS("T"), aI(0)))
    }

    // ==================================================================
    // DT_CLEAR
    // ==================================================================

    @Test
    fun `DT_CLEAR 保列保主键且行 id 续单调`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        val id1 = auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("v"))
        assertEquals(1L, auditInt(c, "DT_CLEAR", aS("T")))
        assertEquals(0L, auditInt(c, "DT_ROW_LENGTH", aS("T")))
        assertEquals(2L, auditInt(c, "DT_COLUMN_LENGTH", aS("T")))
        val id2 = auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("w"))
        // 行 id 发生器跨 CLEAR 单调（原版 Stopwatch 持续计时，Utils.cs:466-470；
        // 移植侧 lastRowId 持久在池上，MapDataTableMethods.kt:492-493、:514-516）。
        assertEquals(id1 + 1, id2)
    }

    // ==================================================================
    // DT_CELL_SET 的 id 变体与显式 DBNull
    // ==================================================================

    @Test
    fun `DT_CELL_SET 的 id 变体与显式 DBNull`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("v"))
        // id 列保护对大小写变体同样生效：ToLower 口径（:2580-2583），与 DT_ROW_ADD 的
        // 精确匹配（:2200）不同源——这是原版两处判据的真实差异，移植侧照抄（:857 lowercase）。
        assertEquals(0L, auditInt(c, "DT_CELL_SET", aS("T"), aI(0), aS("Id"), aS("x")))
        // 不给 value → DBNull（:2584、:2596-2599）：ISNULL 1、GETS ""。
        assertEquals(1L, auditInt(c, "DT_CELL_SET", aS("T"), aI(0), aS("s")))
        assertEquals(1L, auditInt(c, "DT_CELL_ISNULL", aS("T"), aI(0), aS("s")))
        assertEquals("", auditStr(c, "DT_CELL_GETS", aS("T"), aI(0), aS("s")))
        // 类型不符 → -2（:2602-2605，**不是** CodeEE——与 DT_ROW_SET 的成对写入抛错不对称，
        // 移植侧 :870/:873 照抄给 -2）。行/列未命中 → -3（:2618）。
        assertEquals(-2L, auditInt(c, "DT_CELL_SET", aS("T"), aI(0), aS("s"), aI(1)))
        assertEquals(-3L, auditInt(c, "DT_CELL_SET", aS("T"), aI(0), aS("NOPE"), aS("x")))
    }

    // ==================================================================
    // 分歧 A：DT_SELECT 的有 dest 形态（本路补钉，草稿没盖）
    // ==================================================================

    @Test
    fun `DT_SELECT 有 dest 形态从下标零写且不写总数`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        val id1 = auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("a"))
        val id2 = auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("b"))
        // 4 参有 dest：flag=false（:2656），ids 从下标 0 写起、**不写总数**（:2661-2668 的
        // flag 分支跳过 array2[0] = Length）；返回命中数（:2669/:2675）。
        // 移植侧 MapDataTableMethods.kt:899、:906-909 同构。dest 用 RESULT 本身：先写入哨兵。
        c.store.writeInt("RESULT", longArrayOf(0L), 42L)
        val r = (
            c.callFunctionRef(
                "DT_SELECT",
                listOf(
                    StrLiteral("T"),
                    StrLiteral(""),
                    StrLiteral(""),
                    VarRef("RESULT", emptyList(), operandType = OperandType.INT),
                ),
            ) as Value.IntV
            ).v
        assertEquals(2L, r)
        assertEquals(id1, c.store.readInt("RESULT", longArrayOf(0L)), "dest 形态从 0 写起（哨兵 42 被覆盖）")
        assertEquals(id2, c.store.readInt("RESULT", longArrayOf(1L)))
    }

    // ==================================================================
    // 分歧 H：DT_SELECT 筛选式（语料在用）
    // ==================================================================

    @Test
    fun `DT_SELECT 非空筛选式的现状与空串口径`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("a"))
        auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("b"))
        // 【M4-L l-dtsql 已接线】filter/sort 表达式子集已实现（DtSelectExprTest 逐形态覆盖）；
        // 本用例改钉修后读数：子集内命中、子集外仍按原文口径抛。
        assertEquals(1L, auditInt(c, "DT_SELECT", aS("T"), aS("s = 'a'")))
        assertEquals(2L, auditInt(c, "DT_SELECT", aS("T"), aS(""), aS("id ASC")))
        assertFailsWith<CodeEE> { auditInt(c, "DT_SELECT", aS("T"), aS("s = 'a' or s = 'b'")) }
        // 空串与缺省同走全表（原版 :2653-2655：null 与 "" 都进 Select，空表达式 = 全表）。
        assertEquals(2L, auditInt(c, "DT_SELECT", aS("T"), aS("")))
        assertEquals(2L, auditInt(c, "DT_SELECT", aS("T"), aS(""), aS("")))
        // 登记微差（登记不修正，TODO(verify)）：移植侧用 isNullOrBlank，纯空白串（" "）当空表达式
        // 走全表；原版把 " " 交给 Select（:2655），.NET 对纯空白 filter/sort 的处理属框架语义，
        // 仓库无行号，且语料无此形态（实见 filter 皆由 %列名% 宏拼出非空文本）。
        // 语料在用非空筛选/排序（行号登记于 l-dtaudit 起点，逐个复读）：
        // - DLC/自室描写.ERB:293（= AND >= <）、:306（=）
        // - 魔改内容/UFUFU_LOG.ERB:125（空 filter + 排序）、:133（AND + 列名宏）、:385（变量 filter + sort）
        // - CHARALIST_DT/TEMP_CHARA_LIST_BY_DT_FUNC.ERB:957 起 @BUILD_FILTER 生成 =、<>、
        //   AND、引号字符串与多列 ASC/DESC 文本；调用点 :1784-1790（= 与 AND）、:1810-1815（<> 与 AND）
        // - MOVEMENTS/物件関連/MAP_NODE_TO_XML.ERB:133、:158、:161、:264、:337、:341
        //   （=、ASC、缺 filter 带 sort）
        // 已覆盖子集：= <> >= <= > < 与 AND（大小写不敏感）、单引号字符串与整数字面量、
        // 多列 ASC/DESC（缺省方向 ASC）。超出形态抛错，见 dtSelect KDoc 的未覆盖清单。
    }

    // ==================================================================
    // 分歧 J：DT_FROMXML 的 MainDataTable 卡名（语料启动路径，wave/audit-dt 已修）
    // ==================================================================

    @Test
    fun `DT_FROMXML 只按表元素名匹配 不卡 MainDataTable`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        val schemaHead = "<xs:schema id=\"NewDataSet\" xmlns=\"\" " +
            "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" " +
            "xmlns:msdata=\"urn:schemas-microsoft-com:xml-msdata\">"
        val schemaBody = "<xs:element name=\"NewDataSet\" msdata:IsDataSet=\"true\" " +
            "msdata:MainDataTable=\"db\" msdata:UseCurrentLocale=\"true\">" +
            "<xs:complexType><xs:choice minOccurs=\"0\" maxOccurs=\"unbounded\">" +
            "<xs:element name=\"T\"><xs:complexType><xs:sequence>" +
            "<xs:element name=\"id\" type=\"xs:long\" />" +
            "<xs:element name=\"nm\" type=\"xs:string\" minOccurs=\"0\" />" +
            "</xs:sequence></xs:complexType></xs:element>" +
            "</xs:choice></xs:complexType>" +
            "<xs:unique name=\"Constraint1\" msdata:PrimaryKey=\"true\">" +
            "<xs:selector xpath=\".//T\" /><xs:field xpath=\"id\" /></xs:unique>" +
            "</xs:element></xs:schema>"
        val schema = schemaHead + schemaBody
        val data = "<DocumentElement><T><id>1</id><nm>a</nm></T></DocumentElement>"
        // 原版 = `new DataTable("T")` + ReadXmlSchema/ReadXml（FunctionMethodCreator.cs:2741-2745）：
        // 表由 schema 的表元素名与数据行元素名匹配，`msdata:MainDataTable` 只是 DataSet 元数据
        // （语料 plugins/schema.xml:3 写的是宿主库名 "db"，表元素与数据行都叫 bbas_dataset；
        // ERB/BODY_INFO/BBAS_DATASET.ERB:11 传入 bbas_dataset，PC 装载成功并读出 160 行）。
        // wave/audit-dt 起 readTableFromSchema 不再拿 MainDataTable 卡名。
        assertEquals(1L, auditInt(c, "DT_FROMXML", aS("T"), aS(schema), aS(data)))
        assertEquals(1L, auditInt(c, "DT_ROW_LENGTH", aS("T")))
        assertEquals("a", auditStr(c, "DT_CELL_GETS", aS("T"), aI(0), aS("nm")))
        // 对照组：表元素名与传入名不一致时仍然拒绝（原版 ReadXmlSchema 找不到同名表）。
        assertEquals(0L, auditInt(c, "DT_FROMXML", aS("T2"), aS(schema), aS(data)))
    }

    @Test
    fun `DT_FROMXML 读回后大小写口径与 schema 属性现状`() {
        val c = auditCtx()
        val schema = "<xs:schema id=\"NewDataSet\" xmlns=\"\" " +
            "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" " +
            "xmlns:msdata=\"urn:schemas-microsoft-com:xml-msdata\">" +
            "<xs:element name=\"NewDataSet\" msdata:IsDataSet=\"true\">" +
            "<xs:complexType><xs:choice minOccurs=\"0\" maxOccurs=\"unbounded\">" +
            "<xs:element name=\"T12\"><xs:complexType><xs:sequence>" +
            "<xs:element name=\"id\" type=\"xs:long\" />" +
            "<xs:element name=\"nm\" type=\"xs:string\" minOccurs=\"0\" />" +
            "</xs:sequence></xs:complexType></xs:element>" +
            "</xs:choice></xs:complexType></xs:element></xs:schema>"
        val data = "<DocumentElement><T12><id>1</id><nm>v</nm></T12></DocumentElement>"
        assertEquals(1L, auditInt(c, "DT_FROMXML", aS("T12"), aS(schema), aS(data)))
        // 现状：DT_FROMXML 建的表一律 CaseSensitive=false（MapDataTableMethods.kt:1088-1090
        // 硬编码；与原版一致——:2741 的 new DataTable(name) 默认 false，只有 DT_CREATE 显式
        // 设 true（:1993-1996），移植侧对应 :529）。
        assertEquals(5L, auditInt(c, "DT_COLUMN_EXIST", aS("T12"), aS("NM")))
        assertEquals(5L, auditInt(c, "DT_COLUMN_EXIST", aS("T12"), aS("nm")))
        // 登记分歧（登记不修正，TODO(verify)）：schema 里的 msdata:CaseSensitive 属性
        // （语料 plugins/schema.xml:3 与 :7 都带 True）未被移植侧消费；.NET 是否据此设
        // CaseSensitive 属框架行为（reference 无仓内行号）。语料读数列名全小写一致，暂无可观测差。
    }

    // ==================================================================
    // 分歧 B：DT_COLUMN_ADD 追加列时老行同步扩容（wave/audit-dt 已修）
    // ==================================================================

    @Test
    fun `DT_COLUMN_ADD 追加列时老行补默认值`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("v"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("x"), aI(4))
        // .NET `DataColumnCollection.Add` 是表级操作：所有老行同步扩到新列宽，新格取
        // DefaultValue 或 DBNull（FunctionMethodCreator.cs:2118 的 `Columns.Add`）。
        // 旧实现只 columns.add 不扩 rows，CELL 族在 rows[ri][ci] 直接越界。
        assertEquals(1L, auditInt(c, "DT_CELL_ISNULL", aS("T"), aI(0), aS("x")))
        assertEquals("", auditStr(c, "DT_CELL_GETS", aS("T"), aI(0), aS("x")))
        assertEquals(0L, auditInt(c, "DT_CELL_GET", aS("T"), aI(0), aS("x")))
        assertEquals(1L, auditInt(c, "DT_CELL_SET", aS("T"), aI(0), aS("x"), aI(9)))
        assertEquals(9L, auditInt(c, "DT_CELL_GET", aS("T"), aI(0), aS("x")))
        // 新行正常（newRow 按当前列数建）。
        assertTrue(auditInt(c, "DT_ROW_ADD", aS("T"), aS("x"), aI(1)) > 0L)
    }

    // ==================================================================
    // DT_COLUMN_REMOVE
    // ==================================================================

    @Test
    fun `DT_COLUMN_REMOVE 的 id 变体保护与行值前移`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("T"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("s"))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("x"), aI(4))
        auditInt(c, "DT_COLUMN_ADD", aS("T"), aS("y"))
        // 缺列 → 0（:2095-2101 的 flag=false 分支）。
        assertEquals(0L, auditInt(c, "DT_COLUMN_REMOVE", aS("T"), aS("NOPE")))
        // NOCASE 下 "ID" 能命中 id 列，但 ToLower 保护同样生效 → 0（:2096）。
        assertEquals(1L, auditInt(c, "DT_NOCASE", aS("T"), aI(1)))
        assertEquals(0L, auditInt(c, "DT_COLUMN_REMOVE", aS("T"), aS("ID")))
        assertEquals(1L, auditInt(c, "DT_NOCASE", aS("T"), aI(0)))
        // 行在全部列就位后才建（避开「老行不扩」那条分歧，见上一用例）。
        auditInt(c, "DT_ROW_ADD", aS("T"), aS("s"), aS("v"))
        assertEquals(1L, auditInt(c, "DT_CELL_SET", aS("T"), aI(0), aS("y"), aS("yy")))
        // 删除中间列后行值前移（:2098 的 Columns.Remove 收缩行数据；移植侧
        // MapDataTableMethods.kt:669-671 同步 removeAt）。
        assertEquals(1L, auditInt(c, "DT_COLUMN_REMOVE", aS("T"), aS("x")))
        assertEquals(3L, auditInt(c, "DT_COLUMN_LENGTH", aS("T")))
        assertEquals("yy", auditStr(c, "DT_CELL_GETS", aS("T"), aI(0), aS("y")))
        assertEquals("v", auditStr(c, "DT_CELL_GETS", aS("T"), aI(0), aS("s")))
        // 登记分歧（登记不修正）：ToLower 与 Kotlin lowercase 在土耳其语环境对 I 的折叠不同
        // （.NET ToLower 走 CurrentCulture），目标语言集（日中英）无差。
    }

    // ==================================================================
    // MAP：大小写、键序、GETKEYS、TOXML、FROMXML
    // ==================================================================

    @Test
    fun `MAP 大小写与键序的池口径`() {
        val c = auditCtx()
        auditInt(c, "MAP_CREATE", aS("m"))
        auditInt(c, "MAP_SET", aS("m"), aS("k"), aS("1"))
        auditInt(c, "MAP_SET", aS("m"), aS("K"), aS("2"))
        auditInt(c, "MAP_SET", aS("m"), aS("D"), aS("3"))
        // 两层 Dictionary 都是默认 ordinal 比较器（:2787/:2804），键与表名都区分大小写。
        assertEquals(3L, auditInt(c, "MAP_SIZE", aS("m")))
        assertEquals("1", auditStr(c, "MAP_GET", aS("m"), aS("k")))
        assertEquals("", auditStr(c, "MAP_GET", aS("m"), aS("ZZ")))
        assertEquals(0L, auditInt(c, "MAP_EXIST", aS("M")))
        // 无删除时 .NET Dictionary 枚举序 = 插入序（移植侧 LinkedHashMap 恒插入序，同构）。
        assertEquals("k,K,D", auditStr(c, "MAP_GETKEYS", aS("m")))
        // 删除后重加：移植侧仍是插入序（K 删掉、加 E → k,D,E）。
        assertEquals(1L, auditInt(c, "MAP_REMOVE", aS("m"), aS("K")))
        assertEquals(1L, auditInt(c, "MAP_SET", aS("m"), aS("E"), aS("4")))
        assertEquals("k,D,E", auditStr(c, "MAP_GETKEYS", aS("m")))
        // 登记分歧（登记不修正，CLR 与 JVM 容器不同构）：.NET Dictionary 删除后新键复用
        // 空槽，枚举序不再等于插入序（本例在 PC 上是 k,E,D——框架语义，仓库无行号）。
        // 基准与语料都没有「删除后重加再读序」的组合，暂无可观测差。
    }

    @Test
    fun `MAP_GETKEYS 显式下标忽略与数组形态开关`() {
        val c = auditCtx()
        auditInt(c, "MAP_CREATE", aS("M"))
        for (i in 0 until 3) auditInt(c, "MAP_SET", aS("M"), aS("k" + i), aS("v"))
        // 3 参形态：键写满目标数组为止、RESULT = 键总数、返回 ""（:2966-3000，RESULT 写 :2996）；
        // 目标参数取 Identifier.GetArray() 的整条数组，书写下标被忽略（:2973，
        // 移植侧 ArrArg 只用变量名，MapDataTableMethods.kt:214-227）。
        val r = c.callFunctionRef(
            "MAP_GETKEYS",
            listOf(
                StrLiteral("M"),
                VarRef("RESULTS", listOf(IntLiteral(1)), operandType = OperandType.STR),
                IntLiteral(1),
            ),
        )
        assertEquals("", (r as Value.StrV).v)
        assertEquals(3L, c.store.readInt("RESULT", longArrayOf(0L)))
        assertEquals("k0", c.store.readStr("RESULTS", longArrayOf(0L)), "RESULTS:1 的显式下标被忽略")
        // 2 参形态开关为 0 → 空串且不写 RESULT 与 RESULTS（:2981-2984）。
        c.store.writeInt("RESULT", longArrayOf(0L), 42L)
        assertEquals("", auditStr(c, "MAP_GETKEYS", aS("M"), aI(0)))
        assertEquals(42L, c.store.readInt("RESULT", longArrayOf(0L)))
        // 2 参开关非 0 时返回的是 RESULTS:0 本体（:3001 `return exm.VEvaluator.RESULTS`），
        // 既有测试已钉（golden 侧），此处补钉 3 参非零开关返回 ""（:2997-2999）。
    }

    @Test
    fun `MAP_FROMXML 结构不符时的静默跳过`() {
        val c = auditCtx()
        auditInt(c, "MAP_CREATE", aS("M"))
        auditInt(c, "MAP_SET", aS("M"), aS("old"), aS("keep"))
        // 根元素不是 map：SelectNodes("/map/p") 空集（:3059），循环 0 次、恒返回 1（:3075）。
        assertEquals(1L, auditInt(c, "MAP_FROMXML", aS("M"), aS("<notmap><p><k>x</k><v>1</v></p></notmap>")))
        assertEquals("keep", auditStr(c, "MAP_GET", aS("M"), aS("old")))
        // p 里 k 或 v 不是恰好一个：整段 p 跳过（:3068-3073），其余 p 照常导入。
        assertEquals(
            1L,
            auditInt(c, "MAP_FROMXML", aS("M"), aS("<map><p><k>a</k><k>a2</k><v>1</v></p><p><k>b</k><v>2</v></p></map>")),
        )
        assertEquals("", auditStr(c, "MAP_GET", aS("M"), aS("a")))
        assertEquals("2", auditStr(c, "MAP_GET", aS("M"), aS("b")))
    }

    @Test
    fun `MAP_TOXML 不做任何转义`() {
        val c = auditCtx()
        auditInt(c, "MAP_CREATE", aS("M"))
        auditInt(c, "MAP_SET", aS("M"), aS("a<b&c>"), aS("<v&>"))
        // :3020-3025 是字符串插值原样拼接，键值里的 XML 元字符不转义——照抄（原样产出）。
        assertEquals("<map><p><k>a<b&c></k><v><v&></v></p></map>", auditStr(c, "MAP_TOXML", aS("M")))
    }

    @Test
    fun `MAP_RELEASE 与 DT_RELEASE 对不存在目标的恒一`() {
        // 两侧同源判据：MAP_RELEASE :2793-2798、DT_RELEASE :1981-1986 —— 不管存在与否都返回 1；
        // 移植侧 MapDataTableMethods.kt:278-281（mapRelease）、:540-543（dtRelease）。
        // golden :562 已钉 MAP 侧、:617 已钉 DT 侧的存在路径，这里补钉「从未建过」的冷路径。
        val c = auditCtx()
        assertEquals(1L, auditInt(c, "MAP_RELEASE", aS("NEVER")))
        assertEquals(1L, auditInt(c, "DT_RELEASE", aS("NEVER")))
        assertEquals(0L, auditInt(c, "MAP_EXIST", aS("NEVER")))
        assertEquals(0L, auditInt(c, "DT_EXIST", aS("NEVER")))
        // RELEASE 后重建是全新的表（行 id 发生器不随 RELEASE 复位，:2278 的 TimePoint 单调）：
        assertEquals(1L, auditInt(c, "DT_CREATE", aS("R")))
        val id1 = auditInt(c, "DT_ROW_ADD", aS("R"))
        assertEquals(1L, auditInt(c, "DT_RELEASE", aS("R")))
        assertEquals(1L, auditInt(c, "DT_CREATE", aS("R")))
        val id2 = auditInt(c, "DT_ROW_ADD", aS("R"))
        assertTrue(id2 > id1, "RELEASE 重建后行 id 继续单调（lastRowId 挂在池上，:492-493）")
    }

    // ==================================================================
    // XML 出口：DT_TOXML 的 DBNull 省略与 954/179 之外没被 golden 钉到的分支
    // ==================================================================

    @Test
    fun `DT_TOXML 对 DBNull 格与空表串的产物形态`() {
        val c = auditCtx()
        auditInt(c, "DT_CREATE", aS("E"))
        // 空表（只有 id 主键列）：schema 仍完整、数据段只剩 DocumentElement 壳。
        // golden :613 的 954/179 钉的是两列两行表；这里钉的是它没盖的 0 行形态。
        val data = auditStr(c, "DT_TOXML", aS("E"))
        assertEquals("<DocumentElement>\r\n</DocumentElement>", data)
        assertTrue(
            c.store.readStr("RESULTS", longArrayOf(1L)).contains("msdata:MainDataTable=\"E\""),
            "schema 带表名（:2712 WriteXmlSchema）",
        )
        // DBNull 格整元素省略（移植 :1060-1063 的 getOrNull?:continue；原版 WriteXml 对
        // DBNull 默认 DiffGram 之外同样不写元素，框架语义）。
        auditInt(c, "DT_COLUMN_ADD", aS("E"), aS("v"), aI(4))
        auditInt(c, "DT_ROW_ADD", aS("E"))
        val d2 = auditStr(c, "DT_TOXML", aS("E"))
        assertTrue(!d2.contains("<v>"), "v 列 DBNull 不落元素")
        // 缺表 → ""（:2703-2706），且**不碰 RESULTS:1**（移植 :921-924 先查表后写；
        // 原版同理：查不到表直接 return，:2703-2706 在 :2708 之前）。
        c.store.writeStr("RESULTS", longArrayOf(1L), "SENTINEL")
        assertEquals("", auditStr(c, "DT_TOXML", aS("NOPE")))
        assertEquals("SENTINEL", c.store.readStr("RESULTS", longArrayOf(1L)))
    }
}
