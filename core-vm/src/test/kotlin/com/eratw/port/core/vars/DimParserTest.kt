package com.eratw.port.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #DIM / #DIMS 声明解析：关键字冲突、尺寸限制、初值、REF/SAVEDATA 特例。 */
class DimParserTest {

    private fun p(body: String, isPrivate: Boolean = false, scalar: Boolean = false, cfg: DimParseConfig = DimParseConfig()) =
        DimParser.parse(if (scalar) "#DIMS" else "#DIM", body, isPrivate, cfg)

    @Test
    fun `缺省尺寸为 1`() {
        val d = p("X")
        assertEquals(1, d.dimension)
        assertEquals(1, d.dims[0])
        assertTrue(d.isStatic)
        assertFalse(d.isPrivate)
    }

    @Test
    fun `名字可含非 ASCII 且关键字顺序无关`() {
        val d = p("GLOBAL SAVEDATA 好感度, 10")
        assertEquals("好感度", d.name)
        assertTrue(d.isGlobal)
        assertTrue(d.isSavedata)
        assertEquals(10, d.dims[0])
    }

    @Test
    fun `CONST 必须有初值且不可多维`() {
        assertFailsWith<CodeEE> { p("CONST X") }
        assertFailsWith<CodeEE> { p("CONST X, 3 = 1, 2") }          // 个数不等
        assertFailsWith<CodeEE> { p("CONST X, 2, 2 = 1, 2, 3, 4") }  // 多维
        val ok = p("CONST X, 3 = 1, 2, 3")
        assertTrue(ok.isConst)
        assertEquals(listOf(1L, 2L, 3L), ok.defaultsInt!!.toList())
    }

    @Test
    fun `DYNAMIC 与 STATIC 只能在函数内`() {
        assertFailsWith<CodeEE> { p("DYNAMIC X, 2") }
        assertFailsWith<CodeEE> { p("STATIC X, 2") }
        assertFalse(p("DYNAMIC X, 2", isPrivate = true).isStatic)
        assertTrue(p("STATIC X, 2", isPrivate = true).isStatic)
        assertFailsWith<CodeEE> { p("DYNAMIC STATIC X, 2", isPrivate = true) }
        assertFailsWith<CodeEE> { p("DYNAMIC DYNAMIC X, 2", isPrivate = true) }
    }

    @Test
    fun `CHARADATA 与 GLOBAL REF CONST 互斥`() {
        assertFailsWith<CodeEE> { p("CHARADATA GLOBAL X, 2") }
        assertFailsWith<CodeEE> { p("GLOBAL CHARADATA X, 2") }
        assertFailsWith<CodeEE> { p("CHARADATA REF X, 0") }
        assertFailsWith<CodeEE> { p("REF CHARADATA X, 0", isPrivate = true) }
        assertFailsWith<CodeEE> { p("CHARADATA CONST X = 1") }
        // 最多 2 维
        assertFailsWith<CodeEE> { p("CHARADATA X, 2, 2, 2") }
        assertEquals(2, p("CHARADATA X, 2, 2").dimension)
    }

    @Test
    fun `尺寸上限与总元素上限`() {
        assertFailsWith<CodeEE> { p("X, 0") }
        assertFailsWith<CodeEE> { p("X, 1000001") }
        assertFailsWith<CodeEE> { p("X, 1000000, 2") }
        assertEquals(1_000_000, p("X, 1000000").dims[0])
        assertFailsWith<CodeEE> { p("X, 2, 2, 2, 2") } // 维度 > 3
    }

    @Test
    fun `REF 尺寸只能写 0 且不能有初值`() {
        assertFailsWith<CodeEE> { p("REF R, 1", isPrivate = true) }
        assertFailsWith<CodeEE> { p("REF R, 2", isPrivate = true) }
        assertFailsWith<CodeEE> { p("REF R = 1", isPrivate = true) }
        val r = p("REF R, 0, 0", isPrivate = true)
        assertTrue(r.isRef)
        assertFalse(r.isStatic)
        assertEquals(listOf(0, 0), r.dims.toList())
    }

    @Test
    fun `初值只能是常量`() {
        // g-dimdecl：初值段逐项 ReduceArguments + Restructure，折成 SingleTerm 即可
        // （spec: UserDefinedVariableData.cs:375 / :406-408），故 `1 + 2` 是合法初值而非错误
        assertEquals(longArrayOf(3L, 3L, 4L).toList(), p("X, 3 = 1 + 2, 3, 4").defaultsInt?.toList())
        assertFailsWith<CodeEE> { p("X, 3 = ") }
        assertFailsWith<CodeEE> { p("X, 2 = 1, 2, 3") } // 超过长度
        assertFailsWith<CodeEE> { p("X, 3 = 未注册常量 + 1") } // 非常量初值仍失败
        assertFailsWith<CodeEE> { p("X, 3 = COLOR_FROMNAME(\"dimgray\")") } // 函数调用仍失败
        val d = p("X = 1, 2")
        assertEquals(2, d.dims[0]) // 尺寸由初值个数推出
    }

    @Test
    fun `DIMS 只接受字符串初值`() {
        assertFailsWith<CodeEE> { p("S, 2 = 1, 2", scalar = true) }
        assertFailsWith<CodeEE> { p("S, 2 = \"a\", \"b\"", scalar = false) }
        val d = p("S, 3 = \"a\", \"b\"", scalar = true)
        assertTrue(d.isStr)
        assertEquals(listOf("a", "b"), d.defaultsStr)
    }

    @Test
    fun `非二进制存档下 SAVEDATA 字符串与角色变量受限`() {
        assertFailsWith<CodeEE> { p("SAVEDATA S, 2, 2", scalar = true) }
        assertFailsWith<CodeEE> { p("SAVEDATA CHARADATA X, 2") }
        val ok = p("SAVEDATA S, 2, 2", scalar = true, cfg = DimParseConfig(systemSaveInBinary = true))
        assertEquals(2, ok.dimension)
        // 一维字符串 SAVEDATA 不受限
        assertEquals(2, p("SAVEDATA S, 2", scalar = true).dims[0])
    }

    @Test
    fun `GLOBAL SAVEDATA CHARADATA 只能用在函数外`() {
        assertFailsWith<CodeEE> { p("GLOBAL X, 2", isPrivate = true) }
        assertFailsWith<CodeEE> { p("SAVEDATA X, 2", isPrivate = true) }
        assertFailsWith<CodeEE> { p("CHARADATA X, 2", isPrivate = true) }
    }

    @Test
    fun `缺名字与多余内容报错`() {
        assertFailsWith<CodeEE> { p("CONST") }
        assertFailsWith<CodeEE> { p("X, 2 3") }
        assertFailsWith<CodeEE> { DimParser.parse("#DIMX", "X", false) }
    }

    @Test
    fun `尺寸可以是宏或整型常量标识符`() {
        // 求值时序见 DimParser.resolveDimConstant KDoc：ERH 先于 ERB 装载，
        // 尺寸段装载期静态求值（spec: UserDefinedVariableData.cs:325-341）
        val cfg = DimParseConfig(
            macros = mapOf("HNKF_MAX" to "300", "HNKF_CHAIN" to "HNKF_MAX"),
            intConstants = mapOf("人物数量上限" to 200L),
        )
        // 语料实况 集合.ERB `#DIM 集合, 人物数量上限, 3`（DIM.ERH:209 为 ERH #DIM CONST）
        val d = p("集合, 人物数量上限, 3", cfg = cfg)
        assertEquals(2, d.dimension)
        assertEquals(200, d.dims[0])
        assertEquals(3, d.dims[1])
        assertEquals(300, p("X, HNKF_MAX", cfg = cfg).dims[0])
        assertEquals(300, p("X, HNKF_CHAIN", cfg = cfg).dims[0])   // 宏链展开
        // 无常量表/无宏时保持报错（与原版 HasNotExpressionAfterComma 同向）
        assertFailsWith<CodeEE> { p("集合, 人物数量上限, 3") }
        assertFailsWith<CodeEE> { p("X, UNKNOWN_MACRO", cfg = cfg) }
        // REF 仍只能写 0
        assertFailsWith<CodeEE> { p("REF R, 人物数量上限", isPrivate = true, cfg = cfg) }
    }

    @Test
    fun `重复关键字报错`() {
        assertFailsWith<CodeEE> { p("CONST CONST X = 1") }
        assertFailsWith<CodeEE> { p("SAVEDATA SAVEDATA X, 2") }
        assertFailsWith<CodeEE> { p("CHARADATA CHARADATA X, 2") }
    }

    @Test
    fun `整数字面量支持 0x 与 0b 进位`() {
        // 16 进制：spec: LexicalAnalyzer.cs:36-45（前缀）+ :112-123（数字集）。
        // 语料实况 DIM.ERH:571 `#DIM CONST C_GREEN = 0x00FF00`、CASINO_SLOT_HELP.ERB:17
        // `#DIM CONST 色_RED7 = 0xFF00FF`（实测全语料 #DIM 行 208 个此类面值）。
        assertEquals(0x00FF00L, p("CONST C_GREEN = 0x00FF00").defaultsInt!![0])
        assertEquals(0xFF00FFL, p("CONST 色_RED7 = 0xFF00FF").defaultsInt!![0])
        assertEquals(0x4000L, p("CONST X = 0X4000").defaultsInt!![0])        // 大写前缀同支（cs:40-41）
        assertEquals(16, p("X, 0x10").dims[0])                               // 尺寸段同规则（cs:765 → UserDefinedVariableData.cs:325）
        // 2 进制：spec: LexicalAnalyzer.cs:46-51（前缀）+ :124-139（只允许 0/1）
        assertEquals(5L, p("CONST X = 0b101").defaultsInt!![0])
        assertEquals(0L, p("CONST X = 0b0").defaultsInt!![0])
        // 2 进制里出现 2..9 = 原版 CanNotUseBinaryNotate（spec: LexicalAnalyzer.cs:133-136）
        assertFailsWith<CodeEE> { p("CONST X = 0b12") }
    }

    @Test
    fun `整数字面量支持 p 与 e 后缀并保留官方跳过缩放的怪癖`() {
        // p/P = 乘 2 的 n 次方（spec: LexicalAnalyzer.cs:66-69），e/E = 乘 10 的 n 次方（:70-73）。
        // 语料实况 DIM.ERH:311-318 `#DIM CONST 状態_* = 1pN`（实测 164 个，位掩码）。
        assertEquals(1L, p("CONST A = 1p0").defaultsInt!![0])
        assertEquals(2L, p("CONST B = 1p1").defaultsInt!![0])
        assertEquals(128L, p("CONST C = 1p7").defaultsInt!![0])
        assertEquals(16L, p("CONST D = 1p4").defaultsInt!![0])
        assertEquals(1000L, p("CONST E = 1e3").defaultsInt!![0])
        // 官方怪癖 1（照抄，ADR-005）：指数为 0 时整个缩放被跳过（spec: LexicalAnalyzer.cs:80）
        // —— 数学上等价，但 `1p0` 走的是「不缩放」分支，越界判定也不发生。
        assertEquals(1L, p("CONST F = 1p0").defaultsInt!![0])
        assertEquals(0L, p("CONST G = 0p63").defaultsInt!![0])               // 尾数 0 → 跳过缩放 → 0
        // 官方怪癖 2：指数按**尾数的进制**读（spec: LexicalAnalyzer.cs:77 readDigits(st, num3)）
        assertEquals(32L, p("CONST H = 0x10p1").defaultsInt!![0])            // 16 × 2^1
        assertEquals(1048576L, p("CONST I = 0x10p10").defaultsInt!![0])      // 16 × 2^16（指数里的 10 读作 16）
        assertEquals(1024L, p("CONST J = 1p10").defaultsInt!![0])            // 尾数十进制 → 指数按十进制
        // 缩放后越界 = CodeEE OoRInt64（spec: LexicalAnalyzer.cs:83-86）
        assertFailsWith<CodeEE> { p("CONST K = 1e19") }
        // 负号 + 字面量：原版 = 一元负运算符叠在 LiteralIntegerWord 上
        // （spec: LexicalAnalyzer.cs:767-791 运算符分支 + :755-765 数字分支），
        // ConstantData.tryToInt64 则先剥符号再乘回（spec: ConstantData.cs:1532-1541 / :1558），两口径同值。
        assertEquals(-16L, p("CONST L = -0x10").defaultsInt!![0])
        assertEquals(-16L, p("CONST M = -1p4").defaultsInt!![0])
        assertEquals(-1000L, p("CONST N = -1e3").defaultsInt!![0])
    }

    @Test
    fun `宏体面值同样走 ReadInt64`() {
        // spec: ConstantData.cs:1523-1565 tryToInt64 → LexicalAnalyzer.ReadInt64（:1557-1558）
        val cfg = DimParseConfig(
            macros = mapOf("BITFLAG" to "1p4", "COLORBASE" to "0x1000", "NEGBIT" to "-1p3", "BADBIN" to "0b12"),
        )
        assertEquals(16, p("X, BITFLAG", cfg = cfg).dims[0])
        assertEquals(4096, p("X, COLORBASE", cfg = cfg).dims[0])
        // 越界尺寸按原版方向报错（尺寸必须 1..1000000，spec: UserDefinedVariableData.cs:337-340）
        assertFailsWith<CodeEE> { p("X, NEGBIT", cfg = cfg) }
        // 宏体非法（0b 后跟 2）= ReadInt64 抛、tryToInt64 的 catch 分支返回 false
        // （spec: LexicalAnalyzer.cs:133-136 + ConstantData.cs:1560-1563）
        // → 尺寸不可求值，按 HasNotExpressionAfterComma 方向报错
        assertFailsWith<CodeEE> { p("X, BADBIN", cfg = cfg) }
    }

    @Test
    fun `非法字面量一律 CodeEE 不逃逸其它异常`() {
        // SystemVariableBootstrap.install 只 catch CodeEE（不动点重试环的判据，
        // spec: ErhLoader.cs:315-337），词法层异常必须在这一层收敛成 CodeEE。
        assertFailsWith<CodeEE> { p("X, 0x") }                               // 前缀后无数字
        assertFailsWith<CodeEE> { p("X, 0b") }
        assertFailsWith<CodeEE> { p("CONST Y = 99999999999999999999999") }   // 十进制溢出（cs:153-156）
        assertFailsWith<CodeEE> { p("X, ０") }                               // 全角数字：原版走标识符分支（cs:755-764 只认 0..9）
    }

    @Test
    fun `声明结果可生成 VarDef`() {
        val d = p("X, 5")
        val def = d.toVarDef(0, chara = false, code = VariableCode.VAR)
        assertEquals(1, def.dimension)
        assertEquals(1, def.argCount)
        assertEquals(5, def.elementCount)
        val c = p("CHARADATA C, 3")
        val cdef = c.toVarDef(0, chara = true, code = VariableCode.CVAR)
        assertEquals(1, cdef.dimension)
        assertEquals(2, cdef.argCount)
        assertTrue(cdef.isCharacterData)
    }
}
