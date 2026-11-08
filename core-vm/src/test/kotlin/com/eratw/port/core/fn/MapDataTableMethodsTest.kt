package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MAP / DataTable 家族（[registerMapMethods] / [registerDataTableMethods]）。
 *
 * 判据全部是 `fixtures/golden/baseline/pc-golden-root.txt` 的**字面值**：
 * - MAP：`550-562`（GOLDEN_10_XMLMAP.ERB:55-72）
 * - DT：`591-617`（GOLDEN_10_XMLMAP.ERB:112-168）
 *
 * 断言里刻意保留了几个"怪值"，它们都是原版行为的指纹，不是笔误：
 * - `MAP_RELEASE=1/1`：表不存在也返回 1（FunctionMethodCreator.cs:2793-2798）；
 * - `DT_COLUMN_EXIST=5/0`：命中返回**类型号**（string = 5），不是布尔 1；
 * - `DT_ROW_SET=-2`：第 2 参是**行 id**，id=0 不存在（:2269-2272）；
 * - `DT_ROW_REMOVE=0`：同样按 id 找，id=1 不存在（:2416-2420）；
 * - `DT_CELL_GET`/`DT_CELL_SET` 的第 2 参默认是**行号**（第 4/5 参才是按 id 的开关）。
 */
private class MdBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("MdBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/** 固定时钟（行 id 是时间戳，基准只依赖它的**位数**）。 */
private fun mdCtx(clock: Long = 1_700_000_000_000L): BuiltinEvalContext {
    val store = VariableStore()
    val base = MdBaseCtx()
    base.variables = store
    val host = object : BuiltinHost {
        override fun clockMillis(): Long = clock
    }
    return BuiltinEvalContext(base, store, host)
}

private fun mdInt(c: BuiltinEvalContext, name: String, vararg a: Value?): Long =
    (c.callFunction(name, a.toList()) as Value.IntV).v

private fun mdStr(c: BuiltinEvalContext, name: String, vararg a: Value?): String =
    (c.callFunction(name, a.toList()) as Value.StrV).v

private fun mdI(v: Long): Value = Value.IntV(v)

private fun mdS(v: String): Value = Value.StrV(v)

/** STRLENSU 的等价物：UTF-16 码元个数（产物全 ASCII，故等于 length）。 */
private fun strlenu(s: String): Int = s.length

class MapDataTableMethodsTest {

    // ==================================================================
    // MAP —— pc-golden-root.txt:550-562
    // ==================================================================

    @Test
    fun `MAP 段逐行复刻基准 550-562`() {
        val c = mdCtx()
        // :551 MAP_CREATE=1 AGAIN=0 EXIST=1
        assertEquals(1L, mdInt(c, "MAP_CREATE", mdS("GM")))
        assertEquals(0L, mdInt(c, "MAP_CREATE", mdS("GM")))
        assertEquals(1L, mdInt(c, "MAP_EXIST", mdS("GM")))
        // :552 MAP_SET=1 SET2=1
        assertEquals(1L, mdInt(c, "MAP_SET", mdS("GM"), mdS("K1"), mdS("V1")))
        assertEquals(1L, mdInt(c, "MAP_SET", mdS("GM"), mdS("K2"), mdS("V2")))
        // :553 MAP_HAS=1/0
        assertEquals(1L, mdInt(c, "MAP_HAS", mdS("GM"), mdS("K1")))
        assertEquals(0L, mdInt(c, "MAP_HAS", mdS("GM"), mdS("KX")))
        // :554 MAP_GET=V1/（缺键 → 空串，:2956-2960）
        assertEquals("V1", mdStr(c, "MAP_GET", mdS("GM"), mdS("K1")))
        assertEquals("", mdStr(c, "MAP_GET", mdS("GM"), mdS("KX")))
        // :555 MAP_SIZE=2
        assertEquals(2L, mdInt(c, "MAP_SIZE", mdS("GM")))
        // :556 MAP_KEYS=K1,K2 —— 插入序（.NET Dictionary 的实际枚举序）
        assertEquals("K1,K2", mdStr(c, "MAP_GETKEYS", mdS("GM")))
        // :557 MAP_TOXML 逐字节
        val xml = mdStr(c, "MAP_TOXML", mdS("GM"))
        assertEquals("<map><p><k>K1</k><v>V1</v></p><p><k>K2</k><v>V2</v></p></map>", xml)
        // :558-559 目标表必须先存在（:3048-3051），导入后 V1/V2、SIZE=2
        assertEquals(1L, mdInt(c, "MAP_CREATE", mdS("GM2")))
        assertEquals(1L, mdInt(c, "MAP_FROMXML", mdS("GM2"), mdS(xml)))
        assertEquals("V1", mdStr(c, "MAP_GET", mdS("GM2"), mdS("K1")))
        assertEquals("V2", mdStr(c, "MAP_GET", mdS("GM2"), mdS("K2")))
        assertEquals(2L, mdInt(c, "MAP_SIZE", mdS("GM2")))
        // :560 MAP_REMOVE=1 SIZE=1
        assertEquals(1L, mdInt(c, "MAP_REMOVE", mdS("GM"), mdS("K1")))
        assertEquals(1L, mdInt(c, "MAP_SIZE", mdS("GM")))
        // :561 MAP_CLEAR=1 SIZE=0
        assertEquals(1L, mdInt(c, "MAP_CLEAR", mdS("GM")))
        assertEquals(0L, mdInt(c, "MAP_SIZE", mdS("GM")))
        // :562 MAP_RELEASE=1/1 —— 表不存在也返回 1（:2793-2798）
        assertEquals(1L, mdInt(c, "MAP_RELEASE", mdS("GM")))
        assertEquals(1L, mdInt(c, "MAP_RELEASE", mdS("GM2")))
        assertEquals(0L, mdInt(c, "MAP_EXIST", mdS("GM")))
    }

    @Test
    fun `MAP 缺表一律返回 -1 或空串`() {
        val c = mdCtx()
        // :2856-2859 缺表 -1（MAP_SET / MAP_HAS / MAP_REMOVE / MAP_CLEAR / MAP_SIZE 同源）
        assertEquals(-1L, mdInt(c, "MAP_SET", mdS("NO"), mdS("k"), mdS("v")))
        assertEquals(-1L, mdInt(c, "MAP_HAS", mdS("NO"), mdS("k")))
        assertEquals(-1L, mdInt(c, "MAP_REMOVE", mdS("NO"), mdS("k")))
        assertEquals(-1L, mdInt(c, "MAP_CLEAR", mdS("NO")))
        assertEquals(-1L, mdInt(c, "MAP_SIZE", mdS("NO")))
        // :2948-2951 字符串族缺表 → ""；MAP_FROMXML 缺表 → 0（:3048-3051）
        assertEquals("", mdStr(c, "MAP_GET", mdS("NO"), mdS("k")))
        assertEquals("", mdStr(c, "MAP_GETKEYS", mdS("NO")))
        assertEquals("", mdStr(c, "MAP_TOXML", mdS("NO")))
        assertEquals(0L, mdInt(c, "MAP_FROMXML", mdS("NO"), mdS("<map></map>")))
        // :2791-2792 EXIST 只回 1/0
        assertEquals(0L, mdInt(c, "MAP_EXIST", mdS("NO")))
    }

    @Test
    fun `MAP_GETKEYS 两参与三参形态把键写进数组并置 RESULT`() {
        val c = mdCtx()
        mdInt(c, "MAP_CREATE", mdS("M"))
        mdInt(c, "MAP_SET", mdS("M"), mdS("A"), mdS("1"))
        mdInt(c, "MAP_SET", mdS("M"), mdS("B"), mdS("2"))

        // 2 参形态：第 2 参是开关，键写 RESULTS，RESULT=键总数，返回 RESULTS:0（:2977-3001）
        assertEquals("A", mdStr(c, "MAP_GETKEYS", mdS("M"), mdI(1)))
        assertEquals(2L, c.store.readInt("RESULT", longArrayOf(0L)))
        assertEquals("A", c.store.readStr("RESULTS", longArrayOf(0L)))
        assertEquals("B", c.store.readStr("RESULTS", longArrayOf(1L)))
        // 开关为 0 → 空串且不写（:2981-2984）
        assertEquals("", mdStr(c, "MAP_GETKEYS", mdS("M"), mdI(0)))

        // 3 参形态：目标数组是字符串变量引用，返回 ""（:2966-3000）
        val r = c.callFunctionRef(
            "MAP_GETKEYS",
            listOf(StrLiteral("M"), VarRef("RESULTS", emptyList(), operandType = OperandType.STR), IntLiteral(1)),
        )
        assertEquals("", (r as Value.StrV).v)
        assertEquals(2L, c.store.readInt("RESULT", longArrayOf(0L)))
        assertEquals("A", c.store.readStr("RESULTS", longArrayOf(0L)))
    }

    /**
     * 3 参形态的第 2 槽不是变量引用 ⇒ CodeEE，不静默返回空串。
     *
     * 判据：原版 `arguments[1] as VariableTerm` 之后直接 `.Identifier.GetArray()`
     * （spec: FunctionMethodCreator.cs:2968-2973），非变量实参在 C# 侧是裸 NRE。
     * 折成空串会把「一个键都没写」伪装成「表里没有键」，与 DT_ROW_ADD 的折码口径不一致。
     */
    @Test
    fun `MAP_GETKEYS 目标槽非变量引用报 CodeEE`() {
        val c = mdCtx()
        mdInt(c, "MAP_CREATE", mdS("M"))
        mdInt(c, "MAP_SET", mdS("M"), mdS("A"), mdS("1"))
        val e = assertFailsWith<CodeEE> {
            c.callFunctionRef("MAP_GETKEYS", listOf(StrLiteral("M"), IntLiteral(1), IntLiteral(1)))
        }
        assertTrue(e.message!!.contains("第 2 个参数必须是字符串一维数组变量"), "实际：${e.message}")
        // 省略第 2 槽的 2 参/1 参形态不受影响（各自走 RESULTS 与逗号串分支）
        assertEquals("A", mdStr(c, "MAP_GETKEYS", mdS("M"), mdI(1)))
    }

    @Test
    fun `MAP_FROMXML 坏 XML 抛 CodeEE 且值取 InnerXml`() {
        val c = mdCtx()
        mdInt(c, "MAP_CREATE", mdS("M"))
        assertFailsWith<CodeEE> { mdInt(c, "MAP_FROMXML", mdS("M"), mdS("not xml at all")) }
        // 值取 InnerXml（:3072）——.NET 的 InnerXml 是子节点**重序列化**：不剥标签，
        // 但空元素归一成 `<b />`、文本转义 `& < >`（dotnet 8.0.421 探针口径，
        // 见 core/xml/XmlNode.kt 的序列化登记；2026-10-24 ARCH1 P3b 随解析器合一改钉）
        mdInt(c, "MAP_FROMXML", mdS("M"), mdS("<map><p><k>k</k><v>a<b/>c</v></p></map>"))
        assertEquals("a<b />c", mdStr(c, "MAP_GET", mdS("M"), mdS("k")))
        // 键取 InnerText（实体解码）
        mdInt(c, "MAP_FROMXML", mdS("M"), mdS("<map><p><k>a&amp;b</k><v>1</v></p></map>"))
        assertEquals("1", mdStr(c, "MAP_GET", mdS("M"), mdS("a&b")))
    }

    @Test
    fun `MAP_FROMXML 畸形实体与结构按原版抛 CodeEE`() {
        val c = mdCtx()
        mdInt(c, "MAP_CREATE", mdS("M"))
        // 未定义实体：无 DTD 时 .NET 对 &name; 一律抛 XmlException（LoadXml，FMC.cs:3058；
        // 上层 catch 转文案 → CodeEE，:3061-3064）
        assertFailsWith<CodeEE> {
            mdInt(c, "MAP_FROMXML", mdS("M"), mdS("<map><p><k>&bad;</k><v>1</v></p></map>"))
        }
        // '&' 后没有闭合 ';'（.NET: invalid entity reference）
        assertFailsWith<CodeEE> {
            mdInt(c, "MAP_FROMXML", mdS("M"), mdS("<map><p><k>a&b</k><v>1</v></p></map>"))
        }
        // 数字字符引用低位控制符（#x0）：dotnet 8.0.425 LoadXml 实测**放行**、InnerText 即该
        // 码位（XML-FIVE X1 翻案；旧钉按拒绝，方向与 .NET 相反，已翻）——键就是 U+0000。
        assertEquals(1L, mdInt(c, "MAP_FROMXML", mdS("M"), mdS("<map><p><k>&#0;</k><v>1</v></p></map>")))
        assertEquals("1", mdStr(c, "MAP_GET", mdS("M"), mdS("\u0000")))
        // 解析不出（#xZZ）照旧报错
        assertFailsWith<CodeEE> {
            mdInt(c, "MAP_FROMXML", mdS("M"), mdS("<map><p><k>&#xZZ;</k><v>1</v></p></map>"))
        }
        // 重复属性（.NET "duplicate attribute"）
        assertFailsWith<CodeEE> { mdInt(c, "MAP_FROMXML", mdS("M"), mdS("<map a='1' a='2'></map>")) }
        // 属性值里的裸 '<'（.NET "'<' is an unexpected token"）
        assertFailsWith<CodeEE> { mdInt(c, "MAP_FROMXML", mdS("M"), mdS("<map a='<'></map>")) }
        // 内容级 <!DOCTYPE>（.NET 不允许文档中段出现 DTD 标记）
        assertFailsWith<CodeEE> {
            mdInt(c, "MAP_FROMXML", mdS("M"), mdS("<map><!DOCTYPE x><p><k>k</k><v>1</v></p></map>"))
        }
        // 未闭合标签
        assertFailsWith<CodeEE> {
            mdInt(c, "MAP_FROMXML", mdS("M"), mdS("<map><p><k>k</k><v>1</p></map>"))
        }
    }

    @Test
    fun `DT_FROMXML 畸形 XML 静默返回 0（原版裸 catch 降级）`() {
        val c = mdCtx()
        // 结构坏（未闭合）
        assertEquals(0L, mdInt(c, "DT_FROMXML", mdS("T"), mdS("<schema><unclosed>"), mdS("<data>")))
        // 实体坏（&bad;）
        assertEquals(0L, mdInt(c, "DT_FROMXML", mdS("T"), mdS("<xs:schema/>"), mdS("<data><r>&bad;</r></data>")))
    }

    // ==================================================================
    // DT —— pc-golden-root.txt:591-617
    // ==================================================================

    @Test
    fun `DT 段逐行复刻基准 591-617`() {
        val c = mdCtx()
        // :592 DT_CREATE=1 AGAIN=0
        assertEquals(1L, mdInt(c, "DT_CREATE", mdS("GT")))
        assertEquals(0L, mdInt(c, "DT_CREATE", mdS("GT")))
        // :593 DT_COLUMN_ADD=1 ADD2=1（无类型 → .NET 默认 string；类型 4 = int64）
        assertEquals(1L, mdInt(c, "DT_COLUMN_ADD", mdS("GT"), mdS("NAME")))
        assertEquals(1L, mdInt(c, "DT_COLUMN_ADD", mdS("GT"), mdS("VAL"), mdI(4)))
        // :594 DT_COLNAMES=3（id + NAME + VAL）
        assertEquals(3L, mdInt(c, "DT_COLUMN_NAMES", mdS("GT")))
        // :595 DT_COLUMN_EXIST=5/0 —— 5 是 string 的类型号（Utils.cs:173-196 TypeToInt），不是布尔
        assertEquals(5L, mdInt(c, "DT_COLUMN_EXIST", mdS("GT"), mdS("NAME")))
        assertEquals(0L, mdInt(c, "DT_COLUMN_EXIST", mdS("GT"), mdS("NOPE")))
        // :596 DT_COLUMN_OPTIONS 是语句（FunctionIdentifier.cs:2531-2578），成功路径不写 RESULT；
        //     基准那行的 1 来自同一脚本的 `MAP_CREATE "GM2"`（GOLDEN_10:67，METHOD 语句
        //     把返回值写 RESULT:0，FunctionIdentifier.cs:584-604）。这里只验证本家族不碰 RESULT。
        c.store.writeInt("RESULT", longArrayOf(0L), 1L)
        mdInt(c, "DT_COLUMN_NAMES", mdS("GT"))
        mdInt(c, "DT_COLUMN_EXIST", mdS("GT"), mdS("NAME"))
        assertEquals(1L, c.store.readInt("RESULT", longArrayOf(0L)), "DT 家族（除 DT_SELECT）不写 RESULT")
        // 语句层接线点：DT_COLUMN_OPTIONS "GT", "NAME", DEFAULT, "NONAME"
        dtColumnOptionsDefault(c.store, "GT", "NAME", mdS("NONAME"))
        // :597-598 DT_ROW_ADD1_LEN=1 / DT_ROW_ADD2_LEN=2（返回值是行 id，被丢弃）
        val id1 = mdInt(c, "DT_ROW_ADD", mdS("GT"), mdS("NAME"), mdS("R1"), mdS("VAL"), mdI(10))
        assertEquals(1L, mdInt(c, "DT_ROW_LENGTH", mdS("GT")))
        mdInt(c, "DT_ROW_ADD", mdS("GT"), mdS("NAME"), mdS("R2"), mdS("VAL"), mdI(20))
        assertEquals(2L, mdInt(c, "DT_ROW_LENGTH", mdS("GT")))
        // 行 id 是 18 位时间戳（Utils.TimePoint，Utils.cs:464-471）——基准 DATA_LEN=179 依赖它
        assertEquals(18, id1.toString().length)
        // :599 DT_ROW_LENGTH=2 DT_COLUMN_LENGTH=3
        assertEquals(2L, mdInt(c, "DT_ROW_LENGTH", mdS("GT")))
        assertEquals(3L, mdInt(c, "DT_COLUMN_LENGTH", mdS("GT")))
        // :600-601 第 2 参是行号
        assertEquals("R1", mdStr(c, "DT_CELL_GETS", mdS("GT"), mdI(0), mdS("NAME")))
        assertEquals(10L, mdInt(c, "DT_CELL_GET", mdS("GT"), mdI(0), mdS("VAL")))
        assertEquals("R2", mdStr(c, "DT_CELL_GETS", mdS("GT"), mdI(1), mdS("NAME")))
        assertEquals(20L, mdInt(c, "DT_CELL_GET", mdS("GT"), mdI(1), mdS("VAL")))
        // :602-604
        assertEquals(1L, mdInt(c, "DT_CELL_SET", mdS("GT"), mdI(0), mdS("VAL"), mdI(99)))
        assertEquals(99L, mdInt(c, "DT_CELL_GET", mdS("GT"), mdI(0), mdS("VAL")))
        assertEquals(0L, mdInt(c, "DT_CELL_ISNULL", mdS("GT"), mdI(0), mdS("VAL")))
        // :605-606 DT_SELECT=2 且 RESULT:0=2（命中数写 RESULT:0，id 写 RESULT:1..）
        assertEquals(2L, mdInt(c, "DT_SELECT", mdS("GT")))
        assertEquals(2L, c.store.readInt("RESULT", longArrayOf(0L)))
        assertTrue(c.store.readInt("RESULT", longArrayOf(1L)) > 0L, "RESULT:1 是行 id")
        // :607 DT_ROW_SET=-2 —— id=0 不存在（:2269-2272）
        assertEquals(
            -2L,
            mdInt(c, "DT_ROW_SET", mdS("GT"), mdI(0), mdS("NAME"), mdS("R1X"), mdS("VAL"), mdI(55)),
        )
        // :608 DT_ROW_SET_AFTER=R1/99（未改动）
        assertEquals("R1", mdStr(c, "DT_CELL_GETS", mdS("GT"), mdI(0), mdS("NAME")))
        assertEquals(99L, mdInt(c, "DT_CELL_GET", mdS("GT"), mdI(0), mdS("VAL")))
        // :609-610 DT_ROW_REMOVE=0（id=1 不存在）且行数仍为 2
        assertEquals(0L, mdInt(c, "DT_ROW_REMOVE", mdS("GT"), mdI(1)))
        assertEquals(2L, mdInt(c, "DT_ROW_LENGTH", mdS("GT")))
        // :611-612 删 VAL 列 → 2 列
        assertEquals(1L, mdInt(c, "DT_COLUMN_REMOVE", mdS("GT"), mdS("VAL")))
        assertEquals(2L, mdInt(c, "DT_COLUMN_NAMES", mdS("GT")))
        // :613 DT_TOXML_SCHEMA_LEN=954 DATA_LEN=179
        val data = mdStr(c, "DT_TOXML", mdS("GT"))
        // 1 参形态把 schema 写 RESULTS:1（:2708-2713 的 num=1 怪行为）
        val schema = c.store.readStr("RESULTS", longArrayOf(1L))
        assertEquals(954, strlenu(schema))
        assertEquals(179, strlenu(data))
        // 声明无 standalone：doc.Save(xw) 走 WriteStartDocument() 无参重载
        // （XmlDocument.cs Save(XmlWriter)）；standalone="yes" 只在 fileName 重载出现。
        assertEquals("<?xml version=\"1.0\" encoding=\"utf-16\"?>", schema.substringBefore("\r\n"))
        assertEquals("<DocumentElement>", data.substringBefore("\r\n"))
        // :614 DT_FROMXML=1 ROWS=2 COLS=2
        assertEquals(1L, mdInt(c, "DT_FROMXML", mdS("GT"), mdS(schema), mdS(data)))
        assertEquals(2L, mdInt(c, "DT_ROW_LENGTH", mdS("GT")))
        assertEquals(2L, mdInt(c, "DT_COLUMN_LENGTH", mdS("GT")))
        // :615 DT_NOCASE=1
        assertEquals(1L, mdInt(c, "DT_NOCASE", mdS("GT"), mdI(1)))
        // :616 DT_CLEAR=1 ROWS=0（清行不清列）
        assertEquals(1L, mdInt(c, "DT_CLEAR", mdS("GT")))
        assertEquals(0L, mdInt(c, "DT_ROW_LENGTH", mdS("GT")))
        // :617 DT_RELEASE=1 EXIST=0
        assertEquals(1L, mdInt(c, "DT_RELEASE", mdS("GT")))
        assertEquals(0L, mdInt(c, "DT_EXIST", mdS("GT")))
    }

    @Test
    fun `DT_TOXML 两参形态把 schema 写进引用数组`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("GT"))
        mdInt(c, "DT_COLUMN_ADD", mdS("GT"), mdS("NAME"))
        dtColumnOptionsDefault(c.store, "GT", "NAME", mdS("NONAME"))
        mdInt(c, "DT_ROW_ADD", mdS("GT"), mdS("NAME"), mdS("R1"))
        mdInt(c, "DT_ROW_ADD", mdS("GT"), mdS("NAME"), mdS("R2"))
        val r = c.callFunctionRef(
            "DT_TOXML",
            listOf(StrLiteral("GT"), VarRef("RESULTS", emptyList(), operandType = OperandType.STR)),
        )
        assertEquals(179, strlenu((r as Value.StrV).v))
        assertEquals(954, strlenu(c.store.readStr("RESULTS", longArrayOf(0L))))
        // 缺表 → ""（:2703-2706）
        assertEquals("", mdStr(c, "DT_TOXML", mdS("NO")))
    }

    @Test
    fun `DT_TOXML schema 的 954 依赖 DT_COLUMN_OPTIONS 写入的 default`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("GT"))
        mdInt(c, "DT_COLUMN_ADD", mdS("GT"), mdS("NAME"))
        mdInt(c, "DT_ROW_ADD", mdS("GT"), mdS("NAME"), mdS("R1"))
        mdInt(c, "DT_ROW_ADD", mdS("GT"), mdS("NAME"), mdS("R2"))
        mdStr(c, "DT_TOXML", mdS("GT"))
        val withoutDefault = strlenu(c.store.readStr("RESULTS", longArrayOf(1L)))
        // 没有 default="NONAME" 时少 17 字符 → 937 ≠ 954。这正是"基准 596 行的
        // DT_COLUMN_OPTIONS 必须先落地"的证据。minOccurs 在两种形态下都写
        // （HandleColumn 的条件只有 AllowDBNull，DefaultValue 不参与）—— 旧口径下
        // 无 default 会转而写出 minOccurs（+14）得 951，新口径不再有此互补。
        assertEquals(937, withoutDefault)
        dtColumnOptionsDefault(c.store, "GT", "NAME", mdS("NONAME"))
        mdStr(c, "DT_TOXML", mdS("GT"))
        assertEquals(954, strlenu(c.store.readStr("RESULTS", longArrayOf(1L))))
        // 列元素属性集（FN-07 写侧落地后的新口径，逐字对齐 xmlsaver.cs）：
        // - 主键列**不带** ReadOnly/AutoIncrement（AddColumnProperties 只看列自身
        //   flag，原版建表 :1997-1999 只设 AllowDBNull/Unique）；
        // - 可空列**带 default 也写** minOccurs="0"（HandleColumn 条件只有 AllowDBNull）；
        // - DataSet 级元素带 msdata:CaseSensitive="true"（字面量，FillDataSetElement
        //   的 No-DataSet 分支，夹在 MainDataTable 与 UseCurrentLocale 之间）；
        // - 表元素带 msdata:CaseSensitive="True"（bool.ToString() 首字母大写，
        //   HandleTable，DT_CREATE :1995 显式设过 ⇒ userSet 为真）。
        val schema = c.store.readStr("RESULTS", longArrayOf(1L))
        assertTrue(schema.contains("<xs:element name=\"id\" type=\"xs:long\" />"))
        assertTrue(schema.contains("<xs:element name=\"NAME\" type=\"xs:string\" default=\"NONAME\" minOccurs=\"0\" />"))
        assertTrue(
            schema.contains(
                "<xs:element name=\"NewDataSet\" msdata:IsDataSet=\"true\" msdata:MainDataTable=\"GT\" " +
                    "msdata:CaseSensitive=\"true\" msdata:UseCurrentLocale=\"true\">",
            ),
        )
        assertTrue(schema.contains("<xs:element name=\"GT\" msdata:CaseSensitive=\"True\">"))
        assertTrue(schema.contains("<xs:unique name=\"Constraint1\" msdata:PrimaryKey=\"true\">"))
        assertTrue(schema.endsWith("</xs:schema>"), "schema 末尾无换行（Save 写完根只 Flush）")
    }

    @Test
    fun `DT 缺表与缺行缺列的返回码`() {
        val c = mdCtx()
        // :2351 / :2071-2074 / :2468-2470 —— 缺表
        assertEquals(-1L, mdInt(c, "DT_COLUMN_LENGTH", mdS("NO")))
        assertEquals(-1L, mdInt(c, "DT_ROW_LENGTH", mdS("NO")))
        assertEquals(-1L, mdInt(c, "DT_COLUMN_ADD", mdS("NO"), mdS("x")))
        assertEquals(-1L, mdInt(c, "DT_COLUMN_EXIST", mdS("NO"), mdS("x")))
        assertEquals(-1L, mdInt(c, "DT_COLUMN_REMOVE", mdS("NO"), mdS("x")))
        assertEquals(-1L, mdInt(c, "DT_CLEAR", mdS("NO")))
        assertEquals(-1L, mdInt(c, "DT_NOCASE", mdS("NO"), mdI(1)))
        assertEquals(-1L, mdInt(c, "DT_CELL_ISNULL", mdS("NO"), mdI(0), mdS("x")))
        assertEquals(-1L, mdInt(c, "DT_CELL_SET", mdS("NO"), mdI(0), mdS("x"), mdI(1)))
        assertEquals(-1L, mdInt(c, "DT_SELECT", mdS("NO")))
        assertEquals(0L, mdInt(c, "DT_CELL_GET", mdS("NO"), mdI(0), mdS("x")))
        assertEquals("", mdStr(c, "DT_CELL_GETS", mdS("NO"), mdI(0), mdS("x")))
        // 缺表 → -1：原版 DataTableRowRemoveMethod 明写 `if (!ContainsKey) return -1L`
        // （FunctionMethodCreator.cs:2387-2391；golden:609 的 0 是"表存在但无此 id 行"）
        assertEquals(-1L, mdInt(c, "DT_ROW_REMOVE", mdS("NO"), mdI(1)))
        // :1981-1986 RELEASE 不判存在性，恒返回 1
        assertEquals(1L, mdInt(c, "DT_RELEASE", mdS("NO")))

        // 表存在后的行/列未命中
        mdInt(c, "DT_CREATE", mdS("T"))
        assertEquals(-2L, mdInt(c, "DT_CELL_ISNULL", mdS("T"), mdI(0), mdS("id")), "无行 → -2（:2506）")
        mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("S"))
        mdInt(c, "DT_ROW_ADD", mdS("T"), mdS("S"), mdS("v"))
        assertEquals(-2L, mdInt(c, "DT_CELL_ISNULL", mdS("T"), mdI(9), mdS("S")), "行越界 → -2")
        assertEquals(-2L, mdInt(c, "DT_CELL_ISNULL", mdS("T"), mdI(0), mdS("NOPE")), "缺列 → -2")
        assertEquals(1L, mdInt(c, "DT_CELL_SET", mdS("T"), mdI(0), mdS("S")))
        assertEquals(1L, mdInt(c, "DT_CELL_ISNULL", mdS("T"), mdI(0), mdS("S")), "显式写 null → 1")
        assertEquals("", mdStr(c, "DT_CELL_GETS", mdS("T"), mdI(0), mdS("S")))
    }

    @Test
    fun `DT_CELL_SET 的返回码 0 -2 -3 与 id 列保护`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("T"))
        mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("S"))
        mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("N"), mdI(4))
        mdInt(c, "DT_ROW_ADD", mdS("T"), mdS("S"), mdS("v"))
        // :2580-2583 id 列不可写 → 0（`ToLower() == "id"`）
        assertEquals(0L, mdInt(c, "DT_CELL_SET", mdS("T"), mdI(0), mdS("ID"), mdI(1)))
        // :2603-2606 类型不符 → -2
        assertEquals(-2L, mdInt(c, "DT_CELL_SET", mdS("T"), mdI(0), mdS("S"), mdI(1)))
        assertEquals(-2L, mdInt(c, "DT_CELL_SET", mdS("T"), mdI(0), mdS("N"), mdS("x")))
        // :2618 行未命中 → -3
        assertEquals(-3L, mdInt(c, "DT_CELL_SET", mdS("T"), mdI(7), mdS("S"), mdS("x")))
        // 按 id 定位（第 5 参非 0）时 id 不存在同样 -3
        mdInt(c, "DT_SELECT", mdS("T"))
        val id = c.store.readInt("RESULT", longArrayOf(1L))
        assertEquals(1L, mdInt(c, "DT_CELL_SET", mdS("T"), mdI(id), mdS("S"), mdS("byid"), mdI(1)))
        assertEquals("byid", mdStr(c, "DT_CELL_GETS", mdS("T"), mdI(0), mdS("S")))
        assertEquals(-3L, mdInt(c, "DT_CELL_SET", mdS("T"), mdI(id + 1), mdS("S"), mdS("x"), mdI(1)))
    }

    @Test
    fun `DT_ROW_REMOVE 三参形态按 id 数组删除`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("T"))
        mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("S"))
        mdInt(c, "DT_ROW_ADD", mdS("T"), mdS("S"), mdS("a"))
        mdInt(c, "DT_ROW_ADD", mdS("T"), mdS("S"), mdS("b"))
        // 4 参 DT_SELECT：两个 id 从 RESULT:0 起写入（不带总数槽，:2656-2664）
        c.callFunctionRef(
            "DT_SELECT",
            listOf(
                StrLiteral("T"),
                StrLiteral(""),
                StrLiteral(""),
                VarRef("RESULT", emptyList(), operandType = OperandType.INT),
            ),
        )
        val r = c.callFunctionRef(
            "DT_ROW_REMOVE",
            listOf(
                StrLiteral("T"),
                VarRef("RESULT", emptyList(), operandType = OperandType.INT),
                IntLiteral(2),
            ),
        )
        assertEquals(2L, (r as Value.IntV).v, "`id IN (...)` 命中 2 行（:2408/:2428）")
        assertEquals(0L, mdInt(c, "DT_ROW_LENGTH", mdS("T")))
    }

    /**
     * 4 参 DT_SELECT 的第 4 槽留空 / 写成非变量 ⇒ CodeEE。
     *
     * 判据：原版 `(arguments[3] as VariableTerm).Identifier`（spec: FunctionMethodCreator.cs:2656-2657）
     * 在 C# 侧是裸 NRE。移植侧此前静默回落到 RESULT，得到「不写计数头、却从 RESULT:0 起裸写 id」
     * 的混合形态；3 参形态（不写第 4 槽）才是合法的 RESULT 落点。
     */
    @Test
    fun `DT_SELECT 目标槽留空或非变量报 CodeEE`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("T"))
        mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("S"))
        mdInt(c, "DT_ROW_ADD", mdS("T"), mdS("S"), mdS("a"))
        // 显式写了第 4 槽却留空
        assertFailsWith<CodeEE> {
            c.callFunctionRef(
                "DT_SELECT",
                listOf(StrLiteral("T"), StrLiteral(""), StrLiteral(""), null),
            )
        }
        // 第 4 槽写成非变量（整数常量）
        val e = assertFailsWith<CodeEE> {
            c.callFunctionRef(
                "DT_SELECT",
                listOf(StrLiteral("T"), StrLiteral(""), StrLiteral(""), IntLiteral(0)),
            )
        }
        assertTrue(e.message!!.contains("第 4 个参数必须是数组变量"), "实际：${e.message}")
        // 3 参形态照常：命中数写 RESULT:0，返回 id 串
        assertEquals(1L, mdInt(c, "DT_SELECT", mdS("T"), mdS(""), mdS("")))
    }

    @Test
    fun `DT_SELECT 单参形态命中数与 RESULT 总数`() {
        val c = mdCtx()
        // 复刻 golden（GOLDEN_10_XMLMAP.ERB:112-134 → pc-golden-root.txt:605-606：
        // DT_SELECT=2 / DT_SELECT_HITS=2）。DT_ROW_ADD 是 col/val 成对形态（golden:122）。
        mdInt(c, "DT_CREATE", mdS("GT"))
        mdInt(c, "DT_COLUMN_ADD", mdS("GT"), mdS("NAME"), mdS("string"))
        mdInt(c, "DT_COLUMN_ADD", mdS("GT"), mdS("VAL"), mdS("int64"))
        mdInt(c, "DT_ROW_ADD", mdS("GT"), mdS("NAME"), mdS("R1"), mdS("VAL"), mdI(10))
        mdInt(c, "DT_ROW_ADD", mdS("GT"), mdS("NAME"), mdS("R2"), mdS("VAL"), mdI(20))
        // 单参：命中 2；<4 参（flag）形态 → RESULT:0 = 命中数（C# FunctionMethodCreator.cs:2660-2667）
        assertEquals(2L, mdInt(c, "DT_SELECT", mdS("GT")))
        assertEquals(2L, c.store.readInt("RESULT", longArrayOf(0L)))
        // filter 子集已接线（M4-L l-dtsql，分歧 H）：子集内正常求值，不再报「不支持」。
        // NAME='a' 不命中（行是 R1/R2）→ 0；命中数仍写 RESULT:0（:2660-2667）。
        assertEquals(0L, mdInt(c, "DT_SELECT", mdS("GT"), mdS("NAME = 'a'")))
        assertEquals(0L, c.store.readInt("RESULT", longArrayOf(0L)))
        assertEquals(1L, mdInt(c, "DT_SELECT", mdS("GT"), mdS("VAL >= 20")))
        assertEquals(1L, c.store.readInt("RESULT", longArrayOf(0L)))
        // 子集外（OR）仍抛，绝不静默回全表；逐形态清单见 DtSelectExprTest 与 dtSelect KDoc。
        assertFailsWith<CodeEE> {
            mdInt(c, "DT_SELECT", mdS("GT"), mdS("NAME = 'a' OR NAME = 'b'"))
        }
        // 注：第 2 参在原版就是 filter 字符串（ArgType.String，:2628-2638），
        // 往里传整数数组引用原版同样读不出字符串——本族不再臆造"2 参=目标数组"形态。
    }

    @Test
    fun `DT 列的类型号与 NOCASE 的大小写语义`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("T"))
        // 名字形式（Utils.cs:212-219）。返回值恒为 1（成功）——golden:593
        // `DT_COLUMN_ADD=1 ADD2=1`，第二次显式 type=4 也返回 1，不是类型号。
        assertEquals(1L, mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("c1"), mdS("int8")))
        assertEquals(1L, mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("c2"), mdS("int16")))
        assertEquals(1L, mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("c3"), mdS("int32")))
        assertEquals(1L, mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("c4"), mdS("int64")))
        assertEquals(1L, mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("c5"), mdS("string")))
        assertEquals(1L, mdInt(c, "DT_COLUMN_EXIST", mdS("T"), mdS("c1")))
        assertEquals(4L, mdInt(c, "DT_COLUMN_EXIST", mdS("T"), mdS("c4")))
        // 0 / 越界 → CodeEE（:2111-2115）
        assertFailsWith<CodeEE> { mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("bad"), mdI(0)) }
        assertFailsWith<CodeEE> { mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("bad"), mdI(9)) }
        assertFailsWith<CodeEE> { mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("bad"), mdS("float")) }
        // 重复列 → 0（:2104-2107）
        assertEquals(0L, mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("c1")))

        // CaseSensitive 建表为 true（:1993-1996）
        assertEquals(0L, mdInt(c, "DT_COLUMN_EXIST", mdS("T"), mdS("C1")))
        // DT_NOCASE(x)：CaseSensitive = (x == 0)（:1975）
        assertEquals(1L, mdInt(c, "DT_NOCASE", mdS("T"), mdI(1)))
        assertEquals(1L, mdInt(c, "DT_COLUMN_EXIST", mdS("T"), mdS("C1")))
        assertEquals(1L, mdInt(c, "DT_NOCASE", mdS("T"), mdI(0)))
        assertEquals(0L, mdInt(c, "DT_COLUMN_EXIST", mdS("T"), mdS("C1")))
    }

    @Test
    fun `DT 整数列按类型饱和截断`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("T"))
        mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("b"), mdS("int8"))
        mdInt(c, "DT_ROW_ADD", mdS("T"), mdS("b"), mdI(1000))
        // Utils.cs:237-252：Math.Min(Max(v,-128),127) —— 饱和而不是回绕
        assertEquals(127L, mdInt(c, "DT_CELL_GET", mdS("T"), mdI(0), mdS("b")))
        mdInt(c, "DT_CELL_SET", mdS("T"), mdI(0), mdS("b"), mdI(-1000))
        assertEquals(-128L, mdInt(c, "DT_CELL_GET", mdS("T"), mdI(0), mdS("b")))
    }

    @Test
    fun `DT_FROMXML 表名不匹配返回 0`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("GT"))
        mdInt(c, "DT_COLUMN_ADD", mdS("GT"), mdS("NAME"))
        mdInt(c, "DT_ROW_ADD", mdS("GT"), mdS("NAME"), mdS("R1"))
        mdStr(c, "DT_TOXML", mdS("GT"))
        val schema = c.store.readStr("RESULTS", longArrayOf(1L))
        val data = mdStr(c, "DT_TOXML", mdS("GT"))
        // :2749-2752 全捕获 → 0（schema 里没有名为 GT2 的表元素）
        assertEquals(0L, mdInt(c, "DT_FROMXML", mdS("GT2"), mdS(schema), mdS(data)))
        assertEquals(0L, mdInt(c, "DT_EXIST", mdS("GT2")))
        // 垃圾输入同样 0
        assertEquals(0L, mdInt(c, "DT_FROMXML", mdS("GT"), mdS("x"), mdS("y")))
    }

    /**
     * P1 回归：`msdata:MainDataTable` 只是 DataSet 元数据（`WriteXmlSchema` 写的是宿主库名），
     * 原版 `new DataTable(name)` + `ReadXmlSchema`（FunctionMethodCreator.cs:2741-2745）按
     * **表元素名**匹配。语料 plugins/schema.xml:3 是 `MainDataTable="db"`、表元素
     * `bbas_dataset`、数据行也叫 `bbas_dataset`（BBAS_DATASET.ERB:11）。
     * DT-FRAME 波补探针证据（dotnet 8，netcoreapp8.0 与游戏运行时同源）：J1/J6 ——
     * `ReadXmlSchema` 不消费 MainDataTable（指向不存在的 ghost 也静默无视、不改名不抛）；
     * J3/J4/J7 —— ERB 名匹配不到表元素名时抛 ArgumentException → :2749-2752 catch → 0。
     */
    @Test
    fun `DT_FROMXML 不卡 MainDataTable 表元素名匹配即装载`() {
        val c = mdCtx()
        val schema = "<xs:schema id=\"NewDataSet\" xmlns=\"\" " +
            "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" " +
            "xmlns:msdata=\"urn:schemas-microsoft-com:xml-msdata\">" +
            "<xs:element name=\"NewDataSet\" msdata:IsDataSet=\"true\" " +
            "msdata:MainDataTable=\"db\" msdata:UseCurrentLocale=\"true\">" +
            "<xs:complexType><xs:choice minOccurs=\"0\" maxOccurs=\"unbounded\">" +
            "<xs:element name=\"bbas_dataset\"><xs:complexType><xs:sequence>" +
            "<xs:element name=\"id\" type=\"xs:long\" />" +
            "<xs:element name=\"name\" type=\"xs:string\" minOccurs=\"0\" />" +
            "<xs:element name=\"代表色1\" type=\"xs:int\" minOccurs=\"0\" />" +
            "</xs:sequence></xs:complexType></xs:element>" +
            "</xs:choice></xs:complexType>" +
            "<xs:unique name=\"Constraint1\" msdata:PrimaryKey=\"true\">" +
            "<xs:selector xpath=\".//bbas_dataset\" /><xs:field xpath=\"id\" /></xs:unique>" +
            "</xs:element></xs:schema>"
        val data = "<DocumentElement>" +
            "<bbas_dataset><id>1</id><name>靈夢</name><代表色1>13838113</代表色1></bbas_dataset>" +
            "<bbas_dataset><id>2</id><name>魔理沙</name></bbas_dataset>" +
            "</DocumentElement>"
        assertEquals(1L, mdInt(c, "DT_FROMXML", mdS("bbas_dataset"), mdS(schema), mdS(data)))
        assertEquals(2L, mdInt(c, "DT_ROW_LENGTH", mdS("bbas_dataset")))
        assertEquals(3L, mdInt(c, "DT_COLUMN_LENGTH", mdS("bbas_dataset")))
        assertEquals("魔理沙", mdStr(c, "DT_CELL_GETS", mdS("bbas_dataset"), mdI(1), mdS("name")))
        assertEquals(13838113L, mdInt(c, "DT_CELL_GET", mdS("bbas_dataset"), mdI(0), mdS("代表色1")))
        // 表元素名与传入名不一致 → 原版 ReadXmlSchema 找不到表 → 0（:2749-2752）
        assertEquals(0L, mdInt(c, "DT_FROMXML", mdS("db"), mdS(schema), mdS(data)))
    }

    /**
     * P2 回归：schema 的 `default` 必须按列类型落成值（xs:int → Long）。
     * 旧实现把默认值原样存成字符串，`DT_ROW_ADD` 的 NewRow 把它塞进整数列后，
     * `DT_SELECT` 排序在 `(a as Long)` 处抛 ClassCastException。
     */
    @Test
    fun `DT_FROMXML 整数列 default 参与 NewRow 与 DT_SELECT 排序`() {
        val c = mdCtx()
        val schema = "<xs:schema id=\"NewDataSet\" xmlns=\"\" " +
            "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" " +
            "xmlns:msdata=\"urn:schemas-microsoft-com:xml-msdata\">" +
            "<xs:element name=\"NewDataSet\" msdata:IsDataSet=\"true\" msdata:MainDataTable=\"db\">" +
            "<xs:complexType><xs:choice minOccurs=\"0\" maxOccurs=\"unbounded\">" +
            "<xs:element name=\"T\"><xs:complexType><xs:sequence>" +
            "<xs:element name=\"id\" type=\"xs:long\" />" +
            "<xs:element name=\"v\" type=\"xs:int\" default=\"5\" />" +
            "</xs:sequence></xs:complexType></xs:element>" +
            "</xs:choice></xs:complexType></xs:element></xs:schema>"
        // 第三行故意缺 v：ReadXml 的 NewRow 口径是取 DefaultValue，不是 DBNull。
        val data = "<DocumentElement>" +
            "<T><id>2</id><v>9</v></T>" +
            "<T><id>1</id><v>3</v></T>" +
            "<T><id>4</id></T>" +
            "</DocumentElement>"
        assertEquals(1L, mdInt(c, "DT_FROMXML", mdS("T"), mdS(schema), mdS(data)))
        assertEquals(3L, mdInt(c, "DT_ROW_LENGTH", mdS("T")))
        assertEquals(0L, mdInt(c, "DT_CELL_ISNULL", mdS("T"), mdI(2), mdS("v")), "缺元素列取 default")
        assertEquals(5L, mdInt(c, "DT_CELL_GET", mdS("T"), mdI(2), mdS("v")))
        // 新行（无「列名, 值」对）也必须取 default 的 Long，而不是字符串 "5"。
        val newId = mdInt(c, "DT_ROW_ADD", mdS("T"))
        assertEquals(5L, mdInt(c, "DT_CELL_GET", mdS("T"), mdI(newId), mdS("v"), mdI(1)))
        // 排序走过单元格比较的 `as Long`：修复前这里抛 ClassCastException。
        assertEquals(4L, mdInt(c, "DT_SELECT", mdS("T"), mdS(""), mdS("v ASC")))
        val ids = LongArray(4) { c.store.readInt("RESULT", longArrayOf(1L + it)) }
        val values = ids.map { mdInt(c, "DT_CELL_GET", mdS("T"), mdI(it), mdS("v"), mdI(1)) }
        assertEquals(listOf(3L, 5L, 5L, 9L), values)
    }

    /**
     * P2 回归：`DT_COLUMN_ADD` 是表级操作，老行同步扩到新列宽（`Columns.Add` 的框架语义，
     * FunctionMethodCreator.cs:2118）。旧实现只 `columns.add`，CELL 族直接越界。
     * DT-FRAME 波补钉分歧 D 的提交边界：第 4 参 0（AllowDBNull=false）在表内已有行时
     * .NET 的 setter（:2119）抛 DataException（dotnet 8 探针 A2），异常后**列已在表内**、
     * AllowDBNull 仍 true、老行新格 DBNull（探针 A2b 部分状态，一并复刻）。
     */
    @Test
    fun `DT_COLUMN_ADD 给老行补新列初值`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("T"))
        mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("s"))
        mdInt(c, "DT_ROW_ADD", mdS("T"), mdS("s"), mdS("v"))
        // 可空整数列：老行新格 = DBNull。
        mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("n"), mdI(4))
        assertEquals(1L, mdInt(c, "DT_CELL_ISNULL", mdS("T"), mdI(0), mdS("n")))
        assertEquals("", mdStr(c, "DT_CELL_GETS", mdS("T"), mdI(0), mdS("n")))
        assertEquals(0L, mdInt(c, "DT_CELL_GET", mdS("T"), mdI(0), mdS("n")))
        assertEquals(1L, mdInt(c, "DT_CELL_SET", mdS("T"), mdI(0), mdS("n"), mdI(9)))
        assertEquals(9L, mdInt(c, "DT_CELL_GET", mdS("T"), mdI(0), mdS("n")))
        // 原断言（现状钉桩）：不可空字符串列老行新格 = 类型零值 ""，ISNULL=0。
        // 新断言：.NET AllowDBNull setter 在有行时抛 DataException（探针 A2），
        // 列已入表（EXIST=5）、保持可空（ISNULL=1、GETS=""）——探针 A2b 部分状态。
        assertFailsWith<IllegalStateException> {
            mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("s2"), mdI(5), mdI(0))
        }
        assertEquals(5L, mdInt(c, "DT_COLUMN_EXIST", mdS("T"), mdS("s2")))
        assertEquals(1L, mdInt(c, "DT_CELL_ISNULL", mdS("T"), mdI(0), mdS("s2")))
        assertEquals("", mdStr(c, "DT_CELL_GETS", mdS("T"), mdI(0), mdS("s2")))
        // 空表上加不可空列不抛（探针 B4）。
        mdInt(c, "DT_CREATE", mdS("T9"))
        assertEquals(1L, mdInt(c, "DT_COLUMN_ADD", mdS("T9"), mdS("s3"), mdI(5), mdI(0)))
        // 既有单元格不受影响。
        assertEquals("v", mdStr(c, "DT_CELL_GETS", mdS("T"), mdI(0), mdS("s")))
    }

    @Test
    fun `DT_ROW_ADD 四参数组形态`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("T"))
        mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("N1"), mdS("int64"))
        mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("N2"), mdS("int64"))
        // 列名走 RESULTS（字符串数组），值走 RESULT（整数数组）——都是系统变量
        c.store.writeStr("RESULTS", longArrayOf(0L), "N1")
        c.store.writeStr("RESULTS", longArrayOf(1L), "N2")
        c.store.writeInt("RESULT", longArrayOf(0L), 7L)
        c.store.writeInt("RESULT", longArrayOf(1L), 8L)
        val r = c.callFunctionRef(
            "DT_ROW_ADD",
            listOf(
                StrLiteral("T"),
                VarRef("RESULTS", emptyList(), operandType = OperandType.STR),
                VarRef("RESULT", emptyList(), operandType = OperandType.INT),
                IntLiteral(2),
            ),
        )
        assertTrue((r as Value.IntV).v > 0L)
        assertEquals(1L, mdInt(c, "DT_ROW_LENGTH", mdS("T")))
        assertEquals(7L, mdInt(c, "DT_CELL_GET", mdS("T"), mdI(0), mdS("N1")))
        assertEquals(8L, mdInt(c, "DT_CELL_GET", mdS("T"), mdI(0), mdS("N2")))
    }

    @Test
    fun `DT_ROW_ADD 缺列名抛 CodeEE 且 id 列不可写`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("T"))
        assertFailsWith<CodeEE> { mdInt(c, "DT_ROW_ADD", mdS("T"), mdS("NOPE"), mdS("v")) }
        // :2200-2203 列名 == "id"（区分大小写）→ CodeEE
        assertFailsWith<CodeEE> { mdInt(c, "DT_ROW_ADD", mdS("T"), mdS("id"), mdI(1)) }
        // 列名, 值 不成对
        assertFailsWith<CodeEE> { mdInt(c, "DT_ROW_ADD", mdS("T"), mdS("NAME")) }
    }

    // ==================================================================

    // 4.981 上游新增 MAP 增强集（reference / 基准均无对应读数，断言按自改说明 :34-41）
    // ==================================================================

    @Test
    fun `MAP_MERGE 覆盖同 key 并保留目标表原有键序`() {
        val c = mdCtx()
        mdInt(c, "MAP_CREATE", mdS("A"))
        mdInt(c, "MAP_SET", mdS("A"), mdS("k1"), mdS("1"))
        mdInt(c, "MAP_CREATE", mdS("B"))
        mdInt(c, "MAP_SET", mdS("B"), mdS("k1"), mdS("2"))
        mdInt(c, "MAP_SET", mdS("B"), mdS("k2"), mdS("3"))

        assertEquals(1L, mdInt(c, "MAP_MERGE", mdS("A"), mdS("B")))
        // 同 key 覆盖（自改说明 :36），插入序里 k1 仍在前
        assertEquals("2", mdStr(c, "MAP_GET", mdS("A"), mdS("k1")))
        assertEquals("3", mdStr(c, "MAP_GET", mdS("A"), mdS("k2")))
        assertEquals(2L, mdInt(c, "MAP_SIZE", mdS("A")))
        assertEquals("k1,k2", mdStr(c, "MAP_GETKEYS", mdS("A")))
        // 任一侧缺表 → 0（不抛错；spec builtin-functions.yaml:2246；fork-src Creator.Method.cs:2434-2452）
        assertEquals(0L, mdInt(c, "MAP_MERGE", mdS("NOPE"), mdS("B")))
        assertEquals(0L, mdInt(c, "MAP_MERGE", mdS("A"), mdS("NOPE")))
    }

    @Test
    fun `MAP_FINDKEY 按条件收集 key 且缺表返回空串`() {
        val c = mdCtx()
        mdInt(c, "MAP_CREATE", mdS("M"))
        mdInt(c, "MAP_SET", mdS("M"), mdS("k1"), mdS("1"))
        mdInt(c, "MAP_SET", mdS("M"), mdS("k2"), mdS("1"))
        mdInt(c, "MAP_SET", mdS("M"), mdS("k3"), mdS("2"))

        // 三参必填（fork Creator.Method.cs:2505-2510 无 OmitStart）；匹配条数写入 RESULT（spec:2261）。
        assertEquals("k1,k2", mdStr(c, "MAP_FINDKEY", mdS("M"), mdS("1"), mdS("VAL_EQ")))
        assertEquals(2L, c.store.readInt("RESULT", longArrayOf(0L)), "RESULT = 匹配条数")
        // VAL_NE **不受支持**：fork 的 switch 无该分支，落到 switch 之外 ⇒ "" 且 RESULT=0（spec:2262）
        assertEquals("", mdStr(c, "MAP_FINDKEY", mdS("M"), mdS("1"), mdS("VAL_NE")))
        assertEquals(0L, c.store.readInt("RESULT", longArrayOf(0L)))
        // KEY_PREFIX
        assertEquals("k1,k2,k3", mdStr(c, "MAP_FINDKEY", mdS("M"), mdS("k"), mdS("KEY_PREFIX")))
        assertEquals(3L, c.store.readInt("RESULT", longArrayOf(0L)))
        // 缺表 → ""（早退不写 RESULT，与 MAP_GETKEYS 缺表口径一致）
        assertEquals("", mdStr(c, "MAP_FINDKEY", mdS("NOPE"), mdS("1"), mdS("VAL_EQ")))
        // 其它未知条件同 VAL_NE 口径：不抛错、返回 ""（fork 落到 switch 之外）
        assertEquals("", mdStr(c, "MAP_FINDKEY", mdS("M"), mdS("1"), mdS("BOGUS")))
    }

    @Test
    fun `MAP_REMOVEIF 返回删除条数且缺表为零非法条件为负一`() {
        val c = mdCtx()
        mdInt(c, "MAP_CREATE", mdS("M"))
        mdInt(c, "MAP_SET", mdS("M"), mdS("k1"), mdS("1"))
        mdInt(c, "MAP_SET", mdS("M"), mdS("k2"), mdS("1"))
        mdInt(c, "MAP_SET", mdS("M"), mdS("k3"), mdS("2"))

        // 成功 → 删除条数（spec:2254「返回删除条数」；fork-src Creator.Method.cs:2454-2503）
        assertEquals(2L, mdInt(c, "MAP_REMOVEIF", mdS("M"), mdS("1"), mdS("VAL_EQ")))
        assertEquals(1L, mdInt(c, "MAP_SIZE", mdS("M")))
        assertEquals(1L, mdInt(c, "MAP_HAS", mdS("M"), mdS("k3")))
        assertEquals(0L, mdInt(c, "MAP_HAS", mdS("M"), mdS("k1")))
        // 缺表 → 0（不抛错）
        assertEquals(0L, mdInt(c, "MAP_REMOVEIF", mdS("NOPE"), mdS("1"), mdS("VAL_EQ")))
        // mode 非法 → -1（不抛错）
        assertEquals(-1L, mdInt(c, "MAP_REMOVEIF", mdS("M"), mdS("1"), mdS("BOGUS")))
    }

    /**
     * 小写条件名 + 土耳其语宿主：条件必须照常命中，而不是被判「无法解释的条件」。
     *
     * 条件名是与**源码字面量**比对的单侧折叠，因此 `mapMatch` 钉 `Locale.ROOT`。
     * 实测记录（Kotlin 2.0.20 + Temurin 17，本闸环境）：默认文化设为 `tr_TR` 时
     * `"key_prefix".uppercase()` 得 `KEY_PREFIX`，而 `"key_prefix".uppercase(tr)` 与
     * Java 的 `toUpperCase(tr)` 都得 `KEY_PREFİX`（U+0130）⇒ 无参版当前靠 ASCII 快路径
     * 侥幸安全，标准库换版本/换平台（Android libcore）就不再保证；显式传 ROOT 是把这条
     * 保证写死。本用例钉的是**可观测行为**：任何宿主默认文化下，小写条件名照常命中。
     * 同类口径见 `specs/verify-notes.md` §16 第 20 条与 `vars/FloatDeclFaceTest` 的 `#DIMF`。
     */
    @Test
    fun `MAP 条件名小写在土耳其语宿主下仍命中`() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
        try {
            val c = mdCtx()
            mdInt(c, "MAP_CREATE", mdS("M"))
            mdInt(c, "MAP_SET", mdS("M"), mdS("k1"), mdS("1"))
            mdInt(c, "MAP_SET", mdS("M"), mdS("k2"), mdS("2"))

            assertEquals(1L, mdInt(c, "MAP_REMOVEIF", mdS("M"), mdS("k1"), mdS("key_prefix")))
            assertEquals(0L, mdInt(c, "MAP_HAS", mdS("M"), mdS("k1")), "小写条件名必须真的删掉命中格")
            assertEquals(1L, mdInt(c, "MAP_HAS", mdS("M"), mdS("k2")))

            // 同族的 `val_contains`（也含 i）同样不许掉进 else
            assertEquals(1L, mdInt(c, "MAP_SIZE", mdS("M")))
            assertEquals(1L, mdInt(c, "MAP_REMOVEIF", mdS("M"), mdS("2"), mdS("val_contains")))
            assertEquals(0L, mdInt(c, "MAP_SIZE", mdS("M")))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `MAP_TOSTRING 缺省与自定义分隔符`() {
        val c = mdCtx()
        mdInt(c, "MAP_CREATE", mdS("M"))
        mdInt(c, "MAP_SET", mdS("M"), mdS("k1"), mdS("1"))
        mdInt(c, "MAP_SET", mdS("M"), mdS("k2"), mdS("2"))

        assertEquals("k1=1,k2=2", mdStr(c, "MAP_TOSTRING", mdS("M")))
        assertEquals("k1:1;k2:2", mdStr(c, "MAP_TOSTRING", mdS("M"), mdS(";"), mdS(":")))
        // 缺表 → ""（与 MAP_TOXML 同口径）
        assertEquals("", mdStr(c, "MAP_TOSTRING", mdS("NOPE")))
    }

    // ==================================================================
    // mapPoolDiagnostics —— 壳层错误报告的「图像簿记」读数口（2026-11-01 报告收编）
    // ==================================================================

    @Test
    fun `mapPoolDiagnostics 报各 map 尺寸且按插入序`() {
        val c = mdCtx()
        // 键就是传给 MAP_SET 的整串：语料 RM_GMAP 的值是带 `map:` 前缀的
        // 「map:グラフィックス」（ResourceManager.ERH:18），壳层对账时用同一整串。
        mdInt(c, "MAP_CREATE", mdS("map:グラフィックス"))
        mdInt(c, "MAP_SET", mdS("map:グラフィックス"), mdS("path/a.webp"), mdI(200001L))
        mdInt(c, "MAP_SET", mdS("map:グラフィックス"), mdS("path/b.webp"), mdI(200002L))
        mdInt(c, "MAP_CREATE", mdS("リソース"))
        assertEquals(
            listOf("map:グラフィックス" to 2, "リソース" to 0),
            mapPoolDiagnostics(c.store),
            "只报（名字, 元素数）：键值内容（画像路径）一概不外泄",
        )
    }

    @Test
    fun `mapPoolDiagnostics 键区分大小写与前后缀（裸名不等于带前缀键）`() {
        val c = mdCtx()
        mdInt(c, "MAP_CREATE", mdS("map:グラフィックス"))
        assertEquals(
            listOf("map:グラフィックス" to 0),
            mapPoolDiagnostics(c.store),
            "裸「グラフィックス」不产生独立条目，池里只有带前缀键",
        )
    }

    @Test
    fun `mapPoolDiagnostics 空池给空表`() {
        val c = mdCtx()
        assertEquals(emptyList(), mapPoolDiagnostics(c.store))
    }

    @Test
    fun `mapPoolDiagnostics 对 MAP_RELEASE 后的池如实缩减`() {
        val c = mdCtx()
        mdInt(c, "MAP_CREATE", mdS("グラフィックス"))
        mdInt(c, "MAP_SET", mdS("グラフィックス"), mdS("k"), mdI(1L))
        mdInt(c, "MAP_RELEASE", mdS("グラフィックス"))
        assertEquals(emptyList(), mapPoolDiagnostics(c.store))
    }

    // ==================================================================
    // RESIDUE-BX2（2026-11-06）：R3 主键唯一约束 + R4 成对形态空位实参
    // ==================================================================

    /**
     * RESIDUE-BX2 R3：`Rows.Add` 的主键唯一约束（FunctionMethodCreator.cs:2001
     * `PrimaryKey = {id}`、:1999 `Unique = true`）。dotnet 8.0.425 探针
     * （`<work-root>/residue-probe/probe-output.txt` R3a/R3b）：对撞抛 ConstraintException
     * "Column 'id' is constrained to be unique.  Value '1' is already present."
     * （unique 后双空格，原样），异常后行不落表。:2749-2752 的 `catch { return 0L; }`
     * 只在 DataTableFromXmlMethod 内，**不**覆盖 DataTableRowSetMethod ⇒ 按分歧 C 的
     * 裸 CLR→裸 JVM 映射抛 [IllegalStateException]，不是折 0。
     *
     * 对撞构造：[nextRowId] 的严格递增只管本池自产 id，故用 DT_FROMXML 载入
     * id = 「下一自产 id」的行（固定时钟下第二次 DT_ROW_ADD 落 lastRowId+1），
     * 再 DT_ROW_ADD 即对撞。
     */
    @Test
    fun `DT_ROW_ADD 主键对撞按 ConstraintException 复刻且行不落表`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("A"))
        val taken = mdInt(c, "DT_ROW_ADD", mdS("A"))
        val schema = "<xs:schema id=\"NewDataSet\" xmlns=\"\" " +
            "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" " +
            "xmlns:msdata=\"urn:schemas-microsoft-com:xml-msdata\">" +
            "<xs:element name=\"NewDataSet\" msdata:IsDataSet=\"true\" msdata:MainDataTable=\"db\">" +
            "<xs:complexType><xs:choice minOccurs=\"0\" maxOccurs=\"unbounded\">" +
            "<xs:element name=\"B\"><xs:complexType><xs:sequence>" +
            "<xs:element name=\"id\" type=\"xs:long\" />" +
            "</xs:sequence></xs:complexType></xs:element>" +
            "</xs:choice></xs:complexType>" +
            "<xs:unique name=\"Constraint1\" msdata:PrimaryKey=\"true\">" +
            "<xs:selector xpath=\".//B\" /><xs:field xpath=\"id\" /></xs:unique>" +
            "</xs:element></xs:schema>"
        val data = "<DocumentElement><B><id>${taken + 1}</id></B></DocumentElement>"
        assertEquals(1L, mdInt(c, "DT_FROMXML", mdS("B"), mdS(schema), mdS(data)))
        assertEquals(1L, mdInt(c, "DT_ROW_LENGTH", mdS("B")))
        val err = assertFailsWith<IllegalStateException> { mdInt(c, "DT_ROW_ADD", mdS("B")) }
        assertTrue(
            err.message!!.contains("is constrained to be unique") &&
                err.message!!.contains("Value '${taken + 1}' is already present"),
            "应带 .NET ConstraintException 指纹，实际：${err.message}",
        )
        assertEquals(1L, mdInt(c, "DT_ROW_LENGTH", mdS("B")), "异常后行不落表（探针 R3b）")
    }

    /**
     * 探针 R3c 基准：schema 未声明 msdata:PrimaryKey ⇒ 表无 UniqueConstraint，
     * 重复 id 的行照常装载、Rows.Add 也不设唯一闸（DT_ROW_ADD 自产 id 与既有行
     * 不同值即可正常加行）。
     */
    @Test
    fun `无主键表不设 RowsAdd 唯一闸`() {
        val c = mdCtx()
        val schema = "<xs:schema id=\"NewDataSet\" xmlns=\"\" " +
            "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" " +
            "xmlns:msdata=\"urn:schemas-microsoft-com:xml-msdata\">" +
            "<xs:element name=\"NewDataSet\" msdata:IsDataSet=\"true\" msdata:MainDataTable=\"db\">" +
            "<xs:complexType><xs:choice minOccurs=\"0\" maxOccurs=\"unbounded\">" +
            "<xs:element name=\"B\"><xs:complexType><xs:sequence>" +
            "<xs:element name=\"id\" type=\"xs:long\" />" +
            "</xs:sequence></xs:complexType></xs:element>" +
            "</xs:choice></xs:complexType></xs:element></xs:schema>"
        val data = "<DocumentElement><B><id>7</id></B><B><id>7</id></B></DocumentElement>"
        assertEquals(1L, mdInt(c, "DT_FROMXML", mdS("B"), mdS(schema), mdS(data)))
        assertEquals(2L, mdInt(c, "DT_ROW_LENGTH", mdS("B")), "无主键时重复 id 照常装载")
        assertTrue(mdInt(c, "DT_ROW_ADD", mdS("B")) > 0L, "自产 id 与 7 无对撞 ⇒ 正常加行")
        assertEquals(3L, mdInt(c, "DT_ROW_LENGTH", mdS("B")))
    }

    /**
     * RESIDUE-BX2 R4：成对形态的**空位**实参（尾随逗号）在原版解析期就被
     * CheckArgumentTypeEx 拒绝——DT_ROW_ADD/DT_ROW_SET 的 ArgTypeList 无 OmitStart
     * （缺省 -1，FunctionMethodCreator.cs:2140-2155 / :2168-2187；
     * FunctionMethod.cs:198-205 ⇒ ArgCanNotBeNull，Lang.cs:3734，zhs 文案
     * `{0}函数: 第{1}个参数不能省略`）⇒ :2215 的 `v == null → DBNull` 对成对形态不可达。
     * 旧实现把空位静默写成 DBNull（DT_ROW_ADD 侧靠 Rows.Add 边界兜底、
     * DT_ROW_SET 侧直接污染附加行的不可空列）——翻案为同文案 CodeEE。
     * 数组形态走 string/long 重载（:2228-2248）无空位，不可达性同判。
     */
    @Test
    fun `DT_ROW_ADD 成对形态空位实参按 ArgCanNotBeNull 拒绝`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("T"))
        mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("C"))
        val err = assertFailsWith<CodeEE> { mdInt(c, "DT_ROW_ADD", mdS("T"), mdS("C"), null) }
        assertEquals("DT_ROW_ADD函数: 第3个参数不能省略", err.message)
        assertEquals(0L, mdInt(c, "DT_ROW_LENGTH", mdS("T")), "拒绝后行不落表")
    }

    @Test
    fun `DT_ROW_SET 成对形态空位实参按 ArgCanNotBeNull 拒绝且行不被污染`() {
        val c = mdCtx()
        mdInt(c, "DT_CREATE", mdS("T"))
        mdInt(c, "DT_COLUMN_ADD", mdS("T"), mdS("C"))
        val id = mdInt(c, "DT_ROW_ADD", mdS("T"), mdS("C"), mdS("v"))
        val err = assertFailsWith<CodeEE> { mdInt(c, "DT_ROW_SET", mdS("T"), mdI(id), mdS("C"), null) }
        assertEquals("DT_ROW_SET函数: 第4个参数不能省略", err.message)
        assertEquals("v", mdStr(c, "DT_CELL_GETS", mdS("T"), mdI(id), mdS("C"), mdI(1)), "拒绝后原值不动")
    }
}
