package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 总账 FN-06 + FN-07：DT 表的**排序口径**与**大小写口径**（BUG-DT-CASE）。
 *
 * 判据 = dotnet 8 探针的原版读数（探针是临时工程，产物不落仓；运行时与游戏同源 ——
 * 反编译工程 `reference/emuera-cs/Emuera.csproj:7` 目标框架 netcoreapp8.0）。
 * 原版消费链本身只给到框架门口：`FunctionMethodCreator.cs:2655` 把 filter/sort 整条交给
 * `DataTable.Select(表达式, 排序)`，`CaseSensitive` 由 :1995 建表置真、:1975 由 DT_NOCASE
 * 改写（**传 0 = 大小写敏感**，方向已被组长实测改钉，本文件按原样钉住）、:2086 的列名判定走
 * `Columns.Contains`。框架侧（DataView/Index/Sort、ReadXmlSchema）零行号 ⇒ 全部用探针取证。
 *
 * 探针关键读数（下表即本文件的期望值来源）：
 * - `Select("", "s ASC")`，CaseSensitive=true，行序 `Zebra,apple,Banana,abc,ABC,abd,it's`
 *   ⇒ `abc,ABC,abd,apple,Banana,it's,Zebra`（**不是** UTF-16 ordinal 的
 *   `ABC,Banana,Zebra,abc,abd,apple,it's`）；DESC = 反序 `Zebra,it's,Banana,apple,abd,ABC,abc`。
 * - 同一条序列在 en-US / ja-JP / de-DE / zh-CN / fr-FR 五个 `DataTable.Locale` 下**读数全同**
 *   （拉丁区不受文化影响 ⇒ 移植侧可以写死一条；CJK 区做不到，见最后一个用例）。
 * - CaseSensitive=false 时 `abc`/`ABC` 是并键，ASC 与 DESC 都给 `abc,ABC`（= 原行序）
 *   ⇒ 与「比较判 0 + 稳定排序」同构。
 * - 判别式 filter（行序 `ABC,abc,apple`）：见 DT 判别式读数 用例内的对照注释。
 * - `DataTable.ReadXmlSchema` 的 `msdata:CaseSensitive`：**表元素**级才落敏感位；
 *   DataSet 级只参与合法性校验；值不是 true/false 的拼写 ⇒ FormatException ⇒ DT_FROMXML 0。
 *
 * **本路不动的东西**：列名查找的折叠口径（`DtTable.colIndex`）。它不在契约 §4 L5 的
 * FN-06 / FN-07 范围内，且既有两条钉桩（`MapDataTableMethodsTest` 的「DT 列的类型号与
 * NOCASE 的大小写语义」：敏感位为真时 `DT_COLUMN_EXIST "T","C1"` = 0；`MapDataAuditTest`
 * 的「DT_ROW_ADD 单参与失败路径的行状态」：`DT_ROW_ADD "T","ID",…` 抛 CodeEE ——
 * 对应 `FunctionMethodCreator.cs:2200` 的 id 守卫是**精确名** `name == "id"`）与「恒忽略
 * 大小写」的说法相冲。本文件只钉住「敏感位落地后列名查找按 flag 走精确」这条**与既有钉桩
 * 一致**的现状（见最后一条用例），并把它留给组长另立任务取证。
 */
private class DcCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("DcCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun dcCtx(clock: Long = 1_700_000_000_000L): BuiltinEvalContext {
    val store = VariableStore()
    val base = DcCtx()
    base.variables = store
    val host = object : BuiltinHost {
        override fun clockMillis(): Long = clock
    }
    return BuiltinEvalContext(base, store, host)
}

private fun dcInt(c: BuiltinEvalContext, name: String, vararg a: Value?): Long =
    (c.callFunction(name, a.toList()) as Value.IntV).v

private fun dcStr(c: BuiltinEvalContext, name: String, vararg a: Value?): String =
    (c.callFunction(name, a.toList()) as Value.StrV).v

private fun dcI(v: Long): Value = Value.IntV(v)

private fun dcS(v: String): Value = Value.StrV(v)

/** 建字符串列并加一行，返回行 id（DT_ROW_ADD 的 col/val 成对形态，:2126-2323）。 */
private fun dcRow(c: BuiltinEvalContext, table: String, col: String, v: String): Long =
    dcInt(c, "DT_ROW_ADD", dcS(table), dcS(col), dcS(v))

/** 4 参有 dest 形态：ids 从 RESULT:0 起写、不写总数（:2656-2664 的 flag==false 分支）。 */
private fun dcSelectDest(c: BuiltinEvalContext, table: String, filter: String, sort: String): Long =
    (c.callFunctionRef(
        "DT_SELECT",
        listOf(
            StrLiteral(table),
            StrLiteral(filter),
            StrLiteral(sort),
            VarRef("RESULT", emptyList(), operandType = OperandType.INT),
        ),
    ) as Value.IntV).v

/** 命中数（无 dest 形态只读返回值；判等类断言用它）。 */
private fun dcHits(c: BuiltinEvalContext, table: String, filter: String): Long =
    dcInt(c, "DT_SELECT", dcS(table), dcS(filter))

/** 按 DT_SELECT 的行序把 id 还原成字符串列的值序列（DT_CELL_GETS 的 byId=1 形态，:2496-2520）。 */
private fun dcValues(c: BuiltinEvalContext, table: String, col: String, n: Long): List<String> =
    (0L until n).map { i ->
        val id = c.store.readInt("RESULT", longArrayOf(i))
        dcStr(c, "DT_CELL_GETS", dcS(table), dcI(id), dcS(col), dcI(1L))
    }

/**
 * DT_FROMXML 的夹具（内联串，不吃语料环境变量）。
 * 形态照原版 `WriteXmlSchema` 的双层结构；[dsCase] 写 DataSet 级、[tblCase] 写**表元素**级，
 * 语料 plugins/schema.xml:3,6 正是 `true` + `True` 这一对拼写。
 */
private fun dcSchema(
    table: String,
    cols: String,
    dsCase: String? = null,
    tblCase: String? = null,
): String = "<xs:schema id=\"NewDataSet\" xmlns=\"\" " +
    "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" " +
    "xmlns:msdata=\"urn:schemas-microsoft-com:xml-msdata\">" +
    "<xs:element name=\"NewDataSet\" msdata:IsDataSet=\"true\" msdata:MainDataTable=\"db\"" +
    (if (dsCase == null) "" else " msdata:CaseSensitive=\"$dsCase\"") +
    " msdata:UseCurrentLocale=\"true\">" +
    "<xs:complexType><xs:choice minOccurs=\"0\" maxOccurs=\"unbounded\">" +
    "<xs:element name=\"$table\"" +
    (if (tblCase == null) "" else " msdata:CaseSensitive=\"$tblCase\"") +
    "><xs:complexType><xs:sequence>" + cols + "</xs:sequence></xs:complexType></xs:element>" +
    "</xs:choice></xs:complexType></xs:element></xs:schema>"

private val DC_COLS_S = "<xs:element name=\"id\" type=\"xs:long\" />" +
    "<xs:element name=\"s\" type=\"xs:string\" minOccurs=\"0\" />"

/** 与 [DC_COLS_S] 同一段数据（两行 abc / ABC，插入序 abc 在前）。 */
private fun dcData(table: String): String =
    "<DocumentElement><$table><id>2</id><s>abc</s></$table>" +
        "<$table><id>1</id><s>ABC</s></$table></DocumentElement>"

class DtSortAndCaseTest {

    // ==================================================================
    // FN-06：排序口径（探针 = culture，不是 UTF-16 ordinal）
    // ==================================================================

    @Test
    fun `大小写混排表的 DT_SELECT 排序序列等于探针读数`() {
        val c = dcCtx()
        dcInt(c, "DT_CREATE", dcS("T"))
        dcInt(c, "DT_COLUMN_ADD", dcS("T"), dcS("s"))
        listOf("Zebra", "apple", "Banana", "abc", "ABC", "abd", "it's").forEach {
            dcRow(c, "T", "s", it)
        }
        // 探针（CaseSensitive=true，DT_CREATE 的缺省位，:1995）：
        // abc,ABC,abd,apple,Banana,it's,Zebra
        val n = dcSelectDest(c, "T", "", "s ASC")
        assertEquals(7L, n)
        assertEquals(
            listOf("abc", "ABC", "abd", "apple", "Banana", "it's", "Zebra"),
            dcValues(c, "T", "s", n),
        )
        // 旧口径（UTF-16 ordinal）给的是 ABC,Banana,Zebra,abc,abd,apple,it's —— 三条大写整体提前。
        // DESC = 探针的 `Zebra,it's,Banana,apple,abd,ABC,abc`。
        val d = dcSelectDest(c, "T", "", "s DESC")
        assertEquals(7L, d)
        assertEquals(
            listOf("Zebra", "it's", "Banana", "apple", "abd", "ABC", "abc"),
            dcValues(c, "T", "s", d),
        )
        // 缺省方向 = ASC（与 DtSelectExprTest 同口径）。
        val nDefault = dcSelectDest(c, "T", "", "s")
        assertEquals(7L, nDefault)
        assertEquals(
            listOf("abc", "ABC", "abd", "apple", "Banana", "it's", "Zebra"),
            dcValues(c, "T", "s", nDefault),
        )
    }

    @Test
    fun `DT_NOCASE 前后的判等与并键稳定序`() {
        val c = dcCtx()
        dcInt(c, "DT_CREATE", dcS("T"))
        dcInt(c, "DT_COLUMN_ADD", dcS("T"), dcS("s"))
        val abc = dcRow(c, "T", "s", "abc")
        val up = dcRow(c, "T", "s", "ABC")
        // 建表缺省 CaseSensitive=true（:1995）⇒ 判等不折叠、大小写次序级把 abc 排在 ABC 前。
        assertEquals(1L, dcHits(c, "T", "s = 'abc'"))
        assertEquals(2L, dcSelectDest(c, "T", "", "s ASC"))
        assertEquals(listOf("abc", "ABC"), dcValues(c, "T", "s", 2L))
        // 探针：CaseSensitive=true 的 DESC = ABC,abc（次序级拆得开，不是并键）。
        assertEquals(listOf("ABC", "abc"), dcValues(c, "T", "s", dcSelectDest(c, "T", "", "s DESC")))
        // DT_NOCASE(T, 1) ⇒ CaseSensitive = (1 == 0) = false（:1975）⇒ 折叠判等、并键。
        assertEquals(1L, dcInt(c, "DT_NOCASE", dcS("T"), dcI(1)))
        assertEquals(2L, dcHits(c, "T", "s = 'abc'"))
        // 探针：忽略大小写下 ASC 与 DESC 都给 `abc,ABC`（并键保持原行序 ⇒ 与稳定排序同构）。
        assertEquals(listOf("abc", "ABC"), dcValues(c, "T", "s", dcSelectDest(c, "T", "", "s ASC")))
        assertEquals(listOf("abc", "ABC"), dcValues(c, "T", "s", dcSelectDest(c, "T", "", "s DESC")))
        // **方向**：传 0 恰是「大小写敏感」（组长实测改钉过一次，别再翻）⇒ 判等回到 1。
        assertEquals(1L, dcInt(c, "DT_NOCASE", dcS("T"), dcI(0)))
        assertEquals(1L, dcHits(c, "T", "s = 'abc'"))
        assertEquals(listOf("abc", "ABC"), dcValues(c, "T", "s", dcSelectDest(c, "T", "", "s ASC")))
        // 行 id 单调（同一时钟下 lastRowId+1），断言用的两行没被排序改写。
        assertEquals(abc + 1L, up)
    }

    @Test
    fun `DT 判别式读数`() {
        val c = dcCtx()
        dcInt(c, "DT_CREATE", dcS("T"))
        dcInt(c, "DT_COLUMN_ADD", dcS("T"), dcS("s"))
        listOf("ABC", "abc", "apple").forEach { dcRow(c, "T", "s", it) }
        // CaseSensitive=true 的探针读数：
        assertEquals(2L, dcHits(c, "T", "s > 'abc'"))   // ABC,apple
        assertEquals(2L, dcHits(c, "T", "s >= 'ABC'"))  // ABC,apple（abc 在 ABC 之前）
        assertEquals(2L, dcHits(c, "T", "s <> 'ABC'"))  // abc,apple
        assertEquals(1L, dcHits(c, "T", "s = 'abc'"))   // 只有 abc
        assertEquals(3L, dcHits(c, "T", "s < 'Apple'")) // abc,ABC,apple（apple 与 Apple 比大小写级）
        // DT_NOCASE(T, 1) ⇒ CaseSensitive=false 的探针读数：
        dcInt(c, "DT_NOCASE", dcS("T"), dcI(1))
        assertEquals(1L, dcHits(c, "T", "s > 'abc'"))   // apple
        assertEquals(3L, dcHits(c, "T", "s >= 'ABC'"))  // ABC,abc,apple（折叠同键也算 >=）
        assertEquals(1L, dcHits(c, "T", "s <> 'ABC'"))  // apple
        assertEquals(2L, dcHits(c, "T", "s = 'abc'"))   // ABC,abc
        assertEquals(2L, dcHits(c, "T", "s < 'Apple'")) // ABC,abc（apple 与 Apple 判等）
        // 同一份比较器也服务 sort：CaseSensitive=true ⇒ 次序级把 abc 拆到 ABC 前，
        // ASC = abc,ABC,apple（探针 CaseSensitive=true 的 ASC 同形）。
        dcInt(c, "DT_NOCASE", dcS("T"), dcI(0))
        assertEquals(listOf("abc", "ABC", "apple"), dcValues(c, "T", "s", dcSelectDest(c, "T", "", "s ASC")))
    }

    @Test
    fun `字符串列与整数列共用一条排序链（filter 加 sort 的组合）`() {
        val c = dcCtx()
        dcInt(c, "DT_CREATE", dcS("T"))
        dcInt(c, "DT_COLUMN_ADD", dcS("T"), dcS("g"))
        dcInt(c, "DT_COLUMN_ADD", dcS("T"), dcS("n"), dcI(4))
        fun row(g: String, n: Long): Long {
            val id = dcRow(c, "T", "g", g)
            dcInt(c, "DT_CELL_SET", dcS("T"), dcI(id), dcS("n"), dcI(n), dcI(1L))
            return id
        }
        row("apple", 2L)
        row("Banana", 1L)
        row("abc", 2L)
        row("ABC", 1L)
        // 第一键整数、第二键字符串：探针 n ASC 后同 n 组内按 culture 字符串序。
        val ids = dcSelectDest(c, "T", "n >= 1", "n ASC,g ASC")
        assertEquals(4L, ids)
        assertEquals(listOf("ABC", "Banana", "abc", "apple"), dcValues(c, "T", "g", ids))
        // 单字符串键 ASC ⇒ 与 [大小写混排表的 DT_SELECT 排序序列] 同一条比较器。
        assertEquals(listOf("abc", "ABC", "apple", "Banana"), dcValues(c, "T", "g", dcSelectDest(c, "T", "", "g ASC")))
    }

    // ==================================================================
    // FN-07：DT_FROMXML 真消费 msdata:CaseSensitive
    // ==================================================================

    @Test
    fun `DT_FROMXML 读入表元素级 CaseSensitive 后判等敏感`() {
        val c = dcCtx()
        // 语料 plugins/schema.xml:6 的拼写就是首字母大写的 True。
        val schema = dcSchema("bbas", DC_COLS_S, dsCase = "true", tblCase = "True")
        assertEquals(1L, dcInt(c, "DT_FROMXML", dcS("bbas"), dcS(schema), dcS(dcData("bbas"))))
        // 数据行序是 abc(id 2) 在前、ABC(id 1) 在后（DC_COLS 的 dcData 形态）。
        assertEquals(1L, dcHits(c, "bbas", "s = 'ABC'"), "敏感位落地：只有 ABC 命中")
        assertEquals(1L, dcHits(c, "bbas", "s >= 'ABC'"))
        assertEquals(1L, dcHits(c, "bbas", "s <> 'ABC'"), "敏感 ⇒ abc 命中、ABC 不命中")
        // 排序也走同一位：探针 CaseSensitive=true ⇒ abc 在 ABC 前。
        assertEquals(listOf("abc", "ABC"), dcValues(c, "bbas", "s", dcSelectDest(c, "bbas", "", "s ASC")))
        assertEquals(listOf("ABC", "abc"), dcValues(c, "bbas", "s", dcSelectDest(c, "bbas", "", "s DESC")))
    }

    @Test
    fun `DT_FROMXML 的 DataSet 级属性不决定敏感位`() {
        // 探针：只给 DataSet 级 true ⇒ ReadXmlSchema 后 CaseSensitive=False。
        val a = dcCtx()
        val sa = dcSchema("T", DC_COLS_S, dsCase = "true")
        assertEquals(1L, dcInt(a, "DT_FROMXML", dcS("T"), dcS(sa), dcS(dcData("T"))))
        assertEquals(2L, dcHits(a, "T", "s = 'ABC'"), "DataSet 级不落位 ⇒ 忽略大小写")
        // DataSet true + 表 false ⇒ False；DataSet false + 表 true ⇒ True（两条探针读数）。
        val b = dcCtx()
        assertEquals(
            1L,
            dcInt(b, "DT_FROMXML", dcS("T"), dcS(dcSchema("T", DC_COLS_S, "true", "false")), dcS(dcData("T"))),
        )
        assertEquals(2L, dcHits(b, "T", "s = 'ABC'"))
        val d = dcCtx()
        assertEquals(
            1L,
            dcInt(d, "DT_FROMXML", dcS("T"), dcS(dcSchema("T", DC_COLS_S, "false", "TRUE")), dcS(dcData("T"))),
        )
        assertEquals(1L, dcHits(d, "T", "s = 'ABC'"))
        // 两层都不写 ⇒ 框架缺省 false（`new DataTable(name)`，只有 DT_CREATE 才置真，:1995）。
        val e = dcCtx()
        assertEquals(1L, dcInt(e, "DT_FROMXML", dcS("T"), dcS(dcSchema("T", DC_COLS_S)), dcS(dcData("T"))))
        assertEquals(2L, dcHits(e, "T", "s = 'ABC'"))
    }

    @Test
    fun `DT_FROMXML 与 DT_CREATE 走同一比较器`() {
        val c = dcCtx()
        // 同一个 flag、同一份实现：DT_CREATE（:1995 置真）与读入表级 True 的表读数必须一致。
        dcInt(c, "DT_CREATE", dcS("CR"))
        dcInt(c, "DT_COLUMN_ADD", dcS("CR"), dcS("s"))
        dcRow(c, "CR", "s", "abc")
        dcRow(c, "CR", "s", "ABC")
        val schema = dcSchema("FX", DC_COLS_S, tblCase = "True")
        assertEquals(1L, dcInt(c, "DT_FROMXML", dcS("FX"), dcS(schema), dcS(dcData("FX"))))
        listOf("s = 'ABC'", "s <> 'ABC'", "s > 'ABC'", "s >= 'abc'", "s < 'z'").forEach { f ->
            assertEquals(dcHits(c, "CR", f), dcHits(c, "FX", f), "判等/定序必须同一条比较器：$f")
        }
        assertEquals(
            dcValues(c, "CR", "s", dcSelectDest(c, "CR", "", "s ASC")),
            dcValues(c, "FX", "s", dcSelectDest(c, "FX", "", "s ASC")),
        )
    }

    @Test
    fun `msdata CaseSensitive 值非法时整条 DT_FROMXML 落零`() {
        val c = dcCtx()
        // 探针：值 "1" 让 ReadXmlSchema 抛 FormatException ⇒ 原版 :2749-2752 的 catch 全捕获 ⇒ 0。
        assertEquals(0L, dcInt(c, "DT_FROMXML", dcS("T"), dcS(dcSchema("T", DC_COLS_S, tblCase = "1")), dcS(dcData("T"))))
        assertEquals(0L, dcInt(c, "DT_FROMXML", dcS("T"), dcS(dcSchema("T", DC_COLS_S, dsCase = "1")), dcS(dcData("T"))))
        assertEquals(0L, dcInt(c, "DT_EXIST", dcS("T")), "失败路径不得把半成品表挂进池")
        // 合法拼写（true/false 的任意大小写）都能读通。
        assertEquals(1L, dcInt(c, "DT_FROMXML", dcS("T"), dcS(dcSchema("T", DC_COLS_S, tblCase = "true")), dcS(dcData("T"))))
        assertEquals(1L, dcHits(c, "T", "s = 'ABC'"))
    }

    // ==================================================================
    // FN-07 落地后 flag 的既有作用面（列名查找跟 flag 走，本波不改）
    // ==================================================================

    /**
     * 敏感位落地 = **两个读者同时变**：单元格字符串比较（[DtStringComparer]）与列名折叠
     * 口径（`DtTable.colIndex`）。后者是既有钉桩的口径（`MapDataTableMethodsTest` 在
     * `DT_NOCASE` 段钉的「C1 敏感位为真 → 0」），本用例只补 FN-07 这一侧：
     * 读入 `msdata:CaseSensitive="True"` 的表（语料 plugins/schema.xml:6 正是这个拼写）
     * 之后，列名必须**精确**命中，大小写错写的名字不命中。
     * 语料侧核过：BBAS_DATASET.ERB / GET_BODY_SETS.ERB 用的都是 schema 原样的名字
     * （`name`、`尾巴`、`髮色`…），没有大小写错写形态 ⇒ 本条落地不动 E2E / 语料闸读数。
     */
    @Test
    fun `敏感位落地后列名查找按 flag 走精确`() {
        val c = dcCtx()
        val schema = dcSchema("U", DC_COLS_S, dsCase = "true", tblCase = "True")
        assertEquals(1L, dcInt(c, "DT_FROMXML", dcS("U"), dcS(schema), dcS(dcData("U"))))
        // 精确名命中：返回类型号（string = 5，:2089-2093 的 TypeToInt）。
        assertEquals(5L, dcInt(c, "DT_COLUMN_EXIST", dcS("U"), dcS("s")))
        // 敏感位为真 ⇒ 大小写错写不命中（与 MapDataTableMethodsTest 的 C1 钉桩同口径）。
        assertEquals(0L, dcInt(c, "DT_COLUMN_EXIST", dcS("U"), dcS("S")))
        assertEquals("abc", dcStr(c, "DT_CELL_GETS", dcS("U"), dcI(0), dcS("s")))
        // DT_NOCASE(U, 1) ⇒ CaseSensitive=false（:1975）⇒ 同一张表的列名查找转为折叠。
        assertEquals(1L, dcInt(c, "DT_NOCASE", dcS("U"), dcI(1)))
        assertEquals(5L, dcInt(c, "DT_COLUMN_EXIST", dcS("U"), dcS("S")))
        // 真缺列仍是 0（:2071-2074 缺表 / :2086 的 Contains 为假 → 0），折叠只吃同名变体。
        assertEquals(0L, dcInt(c, "DT_COLUMN_EXIST", dcS("U"), dcS("NOPE")))
    }

    /**
     * id 列守卫是**精确名**（`FunctionMethodCreator.cs:2200` 的 `name == "id"`，组长回读原文
     * 确认）：敏感位为真的表里 `"ID"` 既不走 id 保护、也找不到列 ⇒ CodeEE（:2204-2207）。
     * 这条与 `MapDataAuditTest` 的「DT_ROW_ADD 单参与失败路径的行状态」同向，钉在这里是
     * 防止 FN-07 把读入表判成敏感后又被「列名折叠」一条改动悄悄改掉。
     */
    @Test
    fun `读入表的 id 守卫与 ID 变体仍走缺列报错`() {
        val c = dcCtx()
        val schema = dcSchema("U", DC_COLS_S, tblCase = "True")
        assertEquals(1L, dcInt(c, "DT_FROMXML", dcS("U"), dcS(schema), dcS(dcData("U"))))
        assertFailsWith<CodeEE> { dcInt(c, "DT_ROW_ADD", dcS("U"), dcS("id"), dcI(1)) }
        assertFailsWith<CodeEE> { dcInt(c, "DT_ROW_ADD", dcS("U"), dcS("ID"), dcI(1)) }
        assertEquals(2L, dcInt(c, "DT_ROW_LENGTH", dcS("U")), "失败路径不落行（Rows.Add 在尾部 :2318）")
    }

    // ==================================================================
    // 残差登记：非拉丁区原版随文化漂移，移植侧写死一条
    // ==================================================================

    @Test
    fun `假名汉字序列写死口径与探针文化读数对照`() {
        val c = dcCtx()
        dcInt(c, "DT_CREATE", dcS("T"))
        dcInt(c, "DT_COLUMN_ADD", dcS("T"), dcS("s"))
        listOf("色", "ん", "あ", "漢", "か").forEach { dcRow(c, "T", "s", it) }
        // 移植侧口径（折叠全等 ⇒ 退化为 UTF-16 码元序）：あ,か,ん,漢,色
        // 探针对照：en-US 与 ja-JP 同为 あ,か,ん,漢,色（**此条无残差**），
        //           zh-CN 为 漢,色,あ,か,ん（拼音序）⇒ 原版这条读数本身跨机不可重复，
        //           故移植侧不接文化依赖（AGENTS 铁律 4 / 契约 §0.6），只钉一条。
        assertEquals(listOf("あ", "か", "ん", "漢", "色"), dcValues(c, "T", "s", dcSelectDest(c, "T", "", "s ASC")))
        // 标点与全角数字：本侧按码元序（A < 、U+3001 < ０U+FF10），
        // 探针 en-US / ja-JP 给的是 、,０,A（文化把标点与全字符提前）⇒ **登记残差**，
        // 语料 DT_SELECT 的排序键无此形态（34 处 / 6 个文件全为整数列或 ASCII 列）。
        val p = dcCtx()
        dcInt(p, "DT_CREATE", dcS("P"))
        dcInt(p, "DT_COLUMN_ADD", dcS("P"), dcS("s"))
        listOf("０", "A", "、").forEach { dcRow(p, "P", "s", it) }
        assertEquals(listOf("A", "、", "０"), dcValues(p, "P", "s", dcSelectDest(p, "P", "", "s ASC")))
    }

    /**
     * FN-07 写侧（M11-E 落地）：`dtSchemaXml` 双写 `msdata:CaseSensitive` ——
     * DataSet 级是字面量 `true`、表元素级是 `bool.ToString()` 的 `True`/`False`
     * （xmlsaver.cs `FillDataSetElement` / `HandleTable`，属性序与拼写逐字对齐，
     * schema 总长仍 954）。自环 round-trip 因此保留敏感位，与 PC 同口径 ——
     * 旧口径「登记不修、自环丢位」随本波修复（改钉理由：写侧已补属性，
     * 读侧消费点不变，丢位不再是现状）。
     *
     * 读回必须用**同名**（`readTableFromSchema` 按表元素名匹配，FunctionMethodCreator.cs
     * :2741-2745），所以这里覆盖表 `T` 本身；schema 走两参形态写 RESULTS:0，
     * 避开一参形态「RESULTS 长度 > 1 才写 :1」的条件（:2708-2713）。
     */
    @Test
    fun `移植侧自环 roundtrip 保留敏感位`() {
        val c = dcCtx()
        dcInt(c, "DT_CREATE", dcS("T")) // 敏感位 = true（:1995）
        dcInt(c, "DT_COLUMN_ADD", dcS("T"), dcS("s"))
        dcRow(c, "T", "s", "abc")
        dcRow(c, "T", "s", "ABC")
        assertEquals(1L, dcHits(c, "T", "s = 'ABC'"))
        val data = (
            c.callFunctionRef(
                "DT_TOXML",
                listOf(StrLiteral("T"), VarRef("RESULTS", emptyList(), operandType = OperandType.STR)),
            ) as Value.StrV
            ).v
        val schema = c.store.readStr("RESULTS", longArrayOf(0L))
        assertTrue(schema.isNotEmpty(), "两参形态将 schema 写进 RESULTS:0")
        // 双写形态：DataSet 级夹在 MainDataTable 与 UseCurrentLocale 之间（字面量小写），
        // 表元素级紧跟 name（bool.ToString() 首字母大写）。
        assertTrue(schema.contains("msdata:MainDataTable=\"T\" msdata:CaseSensitive=\"true\" msdata:UseCurrentLocale=\"true\">"))
        assertTrue(schema.contains("<xs:element name=\"T\" msdata:CaseSensitive=\"True\">"))
        assertEquals(1L, dcInt(c, "DT_FROMXML", dcS("T"), dcS(schema), dcS(data)))
        assertEquals(1L, dcHits(c, "T", "s = 'ABC'"), "写侧补属性 ⇒ 读回敏感位为真（FN-07 落地）")
        // 读回的表仍是敏感位为真 ⇒ 列名查找也跟着精确（flag 的两个读者同步）。
        assertEquals(5L, dcInt(c, "DT_COLUMN_EXIST", dcS("T"), dcS("s")))
        assertEquals(0L, dcInt(c, "DT_COLUMN_EXIST", dcS("T"), dcS("S")))
        // DT_NOCASE(1) ⇒ CaseSensitive=false（:1975，传 0 才敏感）。setter 无条件置
        // userSet ⇒ 表元素写 "False"、DataSet 级按**值**条件不写；读回不敏感 ⇒ 并键 2 命中。
        assertEquals(1L, dcInt(c, "DT_NOCASE", dcS("T"), dcI(1L)))
        val data2 = (
            c.callFunctionRef(
                "DT_TOXML",
                listOf(StrLiteral("T"), VarRef("RESULTS", emptyList(), operandType = OperandType.STR)),
            ) as Value.StrV
            ).v
        val schema2 = c.store.readStr("RESULTS", longArrayOf(0L))
        assertTrue(schema2.contains("<xs:element name=\"T\" msdata:CaseSensitive=\"False\">"))
        assertTrue(schema2.contains("msdata:MainDataTable=\"T\" msdata:UseCurrentLocale=\"true\">"))
        assertEquals(1L, dcInt(c, "DT_FROMXML", dcS("T"), dcS(schema2), dcS(data2)))
        assertEquals(2L, dcHits(c, "T", "s = 'ABC'"), "读回 False ⇒ 不敏感 ⇒ abc/ABC 并键 2 命中")
    }
}
