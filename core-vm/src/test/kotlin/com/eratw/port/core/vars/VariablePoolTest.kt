package com.eratw.port.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 全局变量池：默认值、维度/越界、常量与存档标记、数组语义、伪变量。
 */
class VariablePoolTest {

    private fun store() = VariableStore()

    private fun idx(vararg v: Long) = v

    // ---------------- 基础读写 ----------------

    @Test
    fun `1 维整数读写`() {
        val s = store()
        s.writeInt("DAY", idx(3), 42)
        assertEquals(42L, s.readInt("DAY", idx(3)))
        assertEquals(0L, s.readInt("DAY", idx(4)))
    }

    @Test
    fun `plusInt 返回加完之后的值`() {
        val s = store()
        s.writeInt("MONEY", idx(0), 100)
        assertEquals(105L, s.plusInt("MONEY", idx(0), 5))
        assertEquals(100L, s.plusInt("MONEY", idx(0), -5))
    }

    @Test
    fun `负数与等于长度的下标都越界`() {
        val s = store()
        val e1 = assertFailsWith<CodeEE> { s.readInt("DAY", idx(-1)) }
        assertTrue(e1.message!!.contains("越界"), e1.message)
        assertFailsWith<CodeEE> { s.readInt("DAY", idx(1000)) }
        assertFailsWith<CodeEE> { s.writeInt("DAY", idx(1000), 1) }
    }

    @Test
    fun `FLAG 长度 10000 而其余默认 1000`() {
        val s = store()
        assertEquals(10000, s.getLength("FLAG"))
        assertEquals(1000, s.getLength("DAY"))
        s.writeInt("FLAG", idx(9999), 7)
        assertEquals(7L, s.readInt("FLAG", idx(9999)))
        assertFailsWith<CodeEE> { s.readInt("FLAG", idx(10000)) }
    }

    @Test
    fun `下标个数不匹配报错`() {
        val s = store()
        assertFailsWith<CodeEE> { s.readInt("DAY", idx()) }
        assertFailsWith<CodeEE> { s.readInt("DAY", idx(0, 0)) }
        assertFailsWith<CodeEE> { s.readInt("SAVEDATA_TEXT", idx(0)) }
    }

    // ---------------- 多维 ----------------

    @Test
    fun `2 维按行主序独立寻址`() {
        val s = store()
        s.writeInt("DA", idx(0, 1), 11)
        s.writeInt("DA", idx(1, 0), 22)
        s.writeInt("DA", idx(99, 99), 33)
        assertEquals(11L, s.readInt("DA", idx(0, 1)))
        assertEquals(22L, s.readInt("DA", idx(1, 0)))
        assertEquals(33L, s.readInt("DA", idx(99, 99)))
        assertEquals(0L, s.readInt("DA", idx(1, 1)))
        assertFailsWith<CodeEE> { s.readInt("DA", idx(100, 0)) }
        assertFailsWith<CodeEE> { s.readInt("DA", idx(0, 100)) }
        assertEquals(100, s.getLength("DA", 0))
        assertEquals(100, s.getLength("DA", 1))
        assertFailsWith<CodeEE> { s.getLength("DA", 2) }
    }

    @Test
    fun `3 维逐维检查`() {
        val s = store()
        s.writeInt("TA", idx(1, 2, 3), 123)
        assertEquals(123L, s.readInt("TA", idx(1, 2, 3)))
        assertFailsWith<CodeEE> { s.readInt("TA", idx(0, 0, 100)) }
        assertEquals(100, s.getLength("TA", 2))
    }

    // ---------------- 默认值 ----------------

    @Test
    fun `默认值抽查`() {
        val s = store()
        assertEquals(1L, s.readInt("TARGET", idx(0)))
        assertEquals(-1L, s.readInt("ASSI", idx(0)))
        assertEquals(10000L, s.readInt("EJAC", idx(0)))
        assertEquals(4L, s.readInt("PBAND", idx(0)))
        // PALAMLV / EXPLV。spec: ConfigData.cs:171-172
        assertEquals(0L, s.readInt("PALAMLV", idx(0)))
        assertEquals(100L, s.readInt("PALAMLV", idx(1)))
        assertEquals(250000L, s.readInt("PALAMLV", idx(9)))
        assertEquals(200L, s.readInt("EXPLV", idx(5)))
        assertEquals("", s.readStr("SAVEDATA_TEXT", idx()))
    }

    @Test
    fun `配置可覆盖 PALAMLV 与 EXPLV`() {
        val cfg = VarConfig(palamLvDef = longArrayOf(7, 9), expLvDef = longArrayOf(1, 2, 3))
        val s = VariableStore(config = cfg)
        assertEquals(7L, s.readInt("PALAMLV", idx(0)))
        assertEquals(9L, s.readInt("PALAMLV", idx(1)))
        assertEquals(0L, s.readInt("PALAMLV", idx(2)))
        assertEquals(3L, s.readInt("EXPLV", idx(2)))
    }

    // ---------------- 常量 / 存档标记 ----------------

    @Test
    fun `ITEMPRICE 与 CSV 名字表是常量`() {
        val s = store()
        assertTrue(s.isConst("ITEMPRICE"))
        assertTrue(s.isConst("ABLNAME"))
        assertFailsWith<CodeEE> { s.writeInt("ITEMPRICE", idx(0), 1) }
        assertFailsWith<CodeEE> { s.writeStr("ABLNAME", idx(0), "x") }
        assertFailsWith<CodeEE> { s.plusInt("ITEMPRICE", idx(0), 1) }
    }

    @Test
    fun `存档标记按源码位规则抽样`() {
        val r = store().registry
        // 经典 0..59 号整数数组
        assertTrue(r.mustDef("DAY").isSavedata)
        assertTrue(r.mustDef("TFLAG").isSavedata)
        assertFalse(r.mustDef("ITEMPRICE").isSavedata) // UNCHANGEABLE
        assertTrue(r.mustDef("GLOBAL").isSavedata)
        assertTrue(r.mustDef("RANDDATA").isSavedata)   // SAVE_EXTENDED
        // 字符串：只有 SAVESTR 进经典存档，STR/RESULTS/TSTR 不进
        assertTrue(r.mustDef("SAVESTR").isSavedata)
        assertTrue(r.mustDef("GLOBALS").isSavedata)
        assertFalse(r.mustDef("STR").isSavedata)
        assertFalse(r.mustDef("RESULTS").isSavedata)
        // TSTR 的 code 带 SAVE_EXTENDED（0x110D0006），按位规则确实入存档；
        // variables.yaml:69 标 false 是"不入经典存档"的口径，见交接报告
        assertTrue(r.mustDef("TSTR").isSavedata)
        // SAVEDATA_TEXT 的 code = 0x01040000（EXTENDED|STRING），构造函数对 EXTENDED 直接
        // 返回 IsSavedata=false；它走的是文本扩展段（GetExtSaveList），不是二进制 IsSavedata。
        // variables.yaml:70 标 saved:true，属"扩展格式写入"的口径差异，见交接报告。
        assertFalse(r.mustDef("SAVEDATA_TEXT").isSavedata)
        // 角色
        assertTrue(r.mustDef("NAME").isSavedata)
        assertTrue(r.mustDef("CALLNAME").isSavedata)
        assertTrue(r.mustDef("NICKNAME").isSavedata)   // SAVE_EXTENDED
        assertTrue(r.mustDef("CFLAG").isSavedata)      // 9 < 17
        assertTrue(r.mustDef("DOWNBASE").isSavedata)   // SAVE_EXTENDED
        // CDFLAG 的 code 带 SAVE_EXTENDED（0x19130000），按位规则入存档
        assertTrue(r.mustDef("CDFLAG").isSavedata)
        // 局部与伪变量不入存档
        assertFalse(r.mustDef("LOCAL").isSavedata)
        assertFalse(r.mustDef("CHARANUM").isSavedata)
    }

    @Test
    fun `全局 GLOBAL 在 resetValues 中保留`() {
        val s = store()
        s.writeInt("GLOBAL", idx(0), 9)
        s.writeInt("DAY", idx(0), 5)
        s.resetValues()
        assertEquals(9L, s.readInt("GLOBAL", idx(0)))
        assertEquals(0L, s.readInt("DAY", idx(0)))
        assertEquals(1L, s.readInt("TARGET", idx(0))) // 默认值重新施加
    }

    // ---------------- 数组语义 ----------------

    @Test
    fun `setAll 1 维区间与 2 维全量`() {
        val s = store()
        s.setAll("DAY", 7, start = 2, end = 5)
        assertEquals(0L, s.readInt("DAY", idx(1)))
        assertEquals(7L, s.readInt("DAY", idx(2)))
        assertEquals(7L, s.readInt("DAY", idx(4)))
        assertEquals(0L, s.readInt("DAY", idx(5)))
        s.setAll("DA", 3)
        assertEquals(3L, s.readInt("DA", idx(0, 0)))
        assertEquals(3L, s.readInt("DA", idx(99, 99)))
    }

    @Test
    fun `setValues 从指定下标连续覆盖`() {
        val s = store()
        s.setValues("DAY", idx(2), longArrayOf(1, 2, 3))
        assertEquals(1L, s.readInt("DAY", idx(2)))
        assertEquals(3L, s.readInt("DAY", idx(4)))
        assertEquals(0L, s.readInt("DAY", idx(5)))
    }

    @Test
    fun `isArrayRangeValid 端点等于长度合法`() {
        val s = store()
        s.isArrayRangeValid("DAY", idx(0), 0, 1000)
        assertFailsWith<CodeEE> { s.isArrayRangeValid("DAY", idx(0), 0, 1001) }
        assertFailsWith<CodeEE> { s.isArrayRangeValid("DAY", idx(0), -1, 10) }
    }

    // ---------------- 字符串 ----------------

    @Test
    fun `字符串写入走驻留池且去重`() {
        val s = store()
        s.writeStr("SAVESTR", idx(0), "abc")
        s.writeStr("SAVESTR", idx(1), "abc")
        val before = s.pool.distinctCount
        s.writeStr("SAVESTR", idx(2), "abc")
        assertEquals(before, s.pool.distinctCount)
        assertEquals("abc", s.readStr("SAVESTR", idx(1)))
        assertFailsWith<CodeEE> { s.readStr("SAVESTR", idx(100)) }
    }

    @Test
    fun `STR 初值来自 STR_CSV`() {
        val sizes = VariableSizes()
        val r = VariableRegistry(sizes)
        r.csvTables[10].set(0, "体力")
        r.csvTables[10].set(1, "気力")
        val s = VariableStore(registry = r)
        assertEquals("体力", s.readStr("STR", idx(0)))
        assertEquals("気力", s.readStr("STR", idx(1)))
        assertEquals("", s.readStr("STR", idx(2)))
    }

    @Test
    fun `类型错配报错`() {
        val s = store()
        assertFailsWith<CodeEE> { s.readInt("SAVESTR", idx(0)) }
        assertFailsWith<CodeEE> { s.readStr("DAY", idx(0)) }
        assertFailsWith<CodeEE> { s.writeStr("DAY", idx(0), "x") }
        assertFailsWith<CodeEE> { s.readInt("NOT_A_VAR", idx(0)) }
    }

    // ---------------- 伪变量 ----------------

    @Test
    fun `伪变量取值与可写项`() {
        val s = store()
        s.lineCount = 12
        s.isTimeout = true
        assertEquals(12L, s.readInt("LINECOUNT", idx()))
        assertEquals(1L, s.readInt("ISTIMEOUT", idx()))
        assertEquals(Long.MAX_VALUE, s.readInt("__INT_MAX__", idx()))
        assertEquals(Long.MIN_VALUE, s.readInt("__INT_MIN__", idx()))
        assertEquals(0L, s.readInt("CHARANUM", idx()))
        assertEquals("1.824.0.0", s.readStr("EMUERA_VERSION", idx()))
        s.writeStr("WINDOW_TITLE", idx(), "eratw")
        assertEquals("eratw", s.readStr("WINDOW_TITLE", idx()))
        assertFailsWith<CodeEE> { s.writeStr("EMUERA_VERSION", idx(), "x") }
    }

    @Test
    fun `GAMEBASE 常量从注入数据读取`() {
        val r = VariableRegistry(gameBase = GameBaseData(title = "画蛇添足", version = 4980))
        val s = VariableStore(registry = r)
        assertEquals("画蛇添足", s.readStr("GAMEBASE_TITLE", idx()))
        assertEquals(4980L, s.readInt("GAMEBASE_VERSION", idx()))
        assertFailsWith<CodeEE> { s.writeStr("GAMEBASE_TITLE", idx(), "x") }
    }

    @Test
    fun `RAND 语义与 CompatiRAND`() {
        val s = VariableStore(randProvider = RandProvider { bound -> bound - 1 })
        assertEquals(9L, s.readInt("RAND", idx(10)))
        assertFailsWith<CodeEE> { s.readInt("RAND", idx(0)) }
        assertFailsWith<CodeEE> { s.readInt("RAND", idx(-5)) }

        // CompatiRAND：GetNextRand(32768) % n，n=0 返回 0，n<0 取绝对值
        val c = VariableStore(
            config = VarConfig(compatiRand = true),
            randProvider = RandProvider { 5L },
        )
        assertEquals(2L, c.readInt("RAND", idx(3)))
        assertEquals(0L, c.readInt("RAND", idx(0)))
        assertEquals(2L, c.readInt("RAND", idx(-3)))
    }

    @Test
    fun `没有 RNG 时 RAND 报错`() {
        assertFailsWith<CodeEE> { store().readInt("RAND", idx(10)) }
    }

    @Test
    fun `字符串关键字下标转换`() {
        val s = store()
        s.registry.csvTables[6].set(5, "薬")      // ITEM.CSV
        s.registry.csvTables[15].set(3, "好感度")  // CFLAG.CSV
        assertEquals(5L, s.keywordToInteger("ITEM", 0, "薬"))
        // CFLAG 的关键字在第 2 个参数位置（角色位置之后）
        assertEquals(3L, s.keywordToInteger("CFLAG", 1, "好感度"))
        assertFailsWith<CodeEE> { s.keywordToInteger("CFLAG", 0, "好感度") }
        assertFailsWith<CodeEE> { s.keywordToInteger("ITEM", 0, "存在しない") }
        assertFailsWith<CodeEE> { s.keywordToInteger("DAY", 0, "x") }
    }

    @Test
    fun `内存估算接口可用`() {
        val s = store()
        assertTrue(s.estimateGlobalIntBytes() > 0)
        assertTrue(s.estimateBytesPerCharacter() > 80_000)
    }
}
