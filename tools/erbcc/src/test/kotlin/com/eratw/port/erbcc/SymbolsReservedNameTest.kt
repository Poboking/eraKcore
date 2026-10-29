package com.eratw.port.erbcc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CORE-06 + CMP-42/45 的符号表层回归。
 *
 * CORE-06 —— 原版把「系统变量名 / 系统方法名 / 系统指令名 / 保留名」四类登记在
 * 一张 `nameDic` 里（spec: IdentifierDictionary.cs:136-183），用户 `#DIM` / `#DEFINE`
 * 撞上即报错：
 *  - 变量名 `CheckUserVarName`（spec: IdentifierDictionary.cs:258-297）→ warnLevel 2
 *    → `UserDefinedVariableData.cs:280-292` 抛 CodeEE → 该行无效、变量不注册；
 *  - 宏名 `CheckUserMacroName`（spec: IdentifierDictionary.cs:299-338）→ warnLevel 2
 *    → `ErhLoader.cs:155-164` 置 `noError=false` → 整条 ERH 链中断。
 * 文案取 zhs 表原文（reference 侧 emuera-zhs.xml:872-885）。
 *
 * CMP-42 —— ERH 链「首错即中止」（spec: ErhLoader.cs:57-61 + :90-93）。
 * CMP-45 —— `#DIM` 尺寸上下限与 REF 尺寸（spec: UserDefinedVariableData.cs:329-341）。
 */
class SymbolsReservedNameTest {

    // -----------------------------------------------------------------------
    // CORE-06：四类拒绝表的正负例
    // -----------------------------------------------------------------------

    @Test
    fun `保留字十五个全部被拒`() {
        // spec: IdentifierDictionary.cs:141-155（顺序照抄 nameDic.Add）
        val expect = listOf(
            "IS", "TO", "INT", "STR", "REFFUNC", "STATIC", "DYNAMIC", "GLOBAL", "PRIVATE",
            "SAVEDATA", "CHARADATA", "REF", "__DEBUG__", "__SKIP__", "_",
        )
        assertEquals(expect, Symbols.RESERVED_NAMES)
        // spec: IdentifierDictionary.cs:270-273 → zhs:873 VarConflictReservedWord
        for (n in expect) {
            assertEquals("变量名\"$n\"为Emuera的保留字", Symbols.checkUserVarName(n), "保留字 $n")
            assertEquals(Symbols.NameKind.RESERVED, Symbols.nameKind(n))
        }
    }

    @Test
    fun `系统变量名被拒且文案区分于命令名`() {
        // spec: IdentifierDictionary.cs:279-282 → zhs:875 VarNameAlreadyUsedInternalVariable
        for (n in listOf("FLAG", "MONEY", "DAY", "M", "TALENT")) {
            assertNotNull(com.eratw.port.core.stmt.BuiltinSymbols.variable(n), "前提：$n 是内建变量")
            assertEquals("变量名\"$n\"为Emuera所使用的变量名", Symbols.checkUserVarName(n), "系统变量 $n")
        }
        // 大小写不敏感（nameDic 用 Config.StrComper = OrdinalIgnoreCase，spec: IdentifierDictionary.cs:48）
        assertEquals("变量名\"flag\"为Emuera所使用的变量名", Symbols.checkUserVarName("flag"))
    }

    @Test
    fun `系统方法名与系统指令名被拒`() {
        // spec: IdentifierDictionary.cs:274-278 → zhs:874 VarNameAlreadyUsedInternalInstruction
        for (n in listOf("STRLENS", "POWER", "VARSIZE", "PRINTL", "IF")) {
            assertEquals("变量名\"$n\"为Emuera所使用的变量或者命令名", Symbols.checkUserVarName(n), "内建名 $n")
        }
        // 宏名走同一张表、文案换成「宏名」（spec: IdentifierDictionary.cs:315-319 → zhs:882）
        assertEquals("宏名\"PRINTL\"为Emuera所使用的变量或者命令名", Symbols.checkUserMacroName("PRINTL"))
        assertEquals("宏名\"REF\"为Emuera的保留字", Symbols.checkUserMacroName("REF"))
    }

    @Test
    fun `合法名字与非法符号`() {
        // 用户自定义空间：ASCII 下划线名与日文名都合法（原版只禁 ASCII 符号）
        for (n in listOf("MY_VAR", "_X", "X1", "人物数量上限", "GT_SET2")) {
            assertNull(Symbols.checkUserVarName(n), "$n 应合法")
            assertNull(Symbols.checkUserMacroName(n), "$n 应合法宏名")
        }
        // spec: IdentifierDictionary.cs:261 → zhs:872 VarContainsOtherThanUnderline
        assertEquals("变量名\"A+B\"包含\"_\"以外的符号", Symbols.checkUserVarName("A+B"))
        assertEquals("宏名\"A-B\"包含\"_\"以外的符号", Symbols.checkUserMacroName("A-B"))
    }

    @Test
    fun `已定义宏与已声明全局的重名判定`() {
        // spec: IdentifierDictionary.cs:284-287 / 324-327 → zhs:876 / zhs:884
        assertEquals("变量名\"DUP\"为已定义的宏", Symbols.checkUserVarName("DUP", setOf("DUP")))
        assertEquals("宏名\"DUP\"为已定义的宏", Symbols.checkUserMacroName("DUP", setOf("DUP")))
        // spec: IdentifierDictionary.cs:288-291 → zhs:877
        assertEquals(
            "变量名\"G1\"为用户已定义的全局变量名",
            Symbols.checkUserVarName("G1", emptySet(), setOf("G1")),
        )
        // nameDic 一类一键：宏先于全局登记 ⇒ 同时命中时报「宏」
        assertEquals(
            "变量名\"MY_X\"为已定义的宏",
            Symbols.checkUserVarName("MY_X", setOf("MY_X"), setOf("MY_X")),
        )
    }

    // -----------------------------------------------------------------------
    // CORE-06 × ERH 收集：拒绝要落到 diag，且不误伤合法声明
    // -----------------------------------------------------------------------

    @Test
    fun `ERH 里 DIM 撞系统名被拒且不中止链`() {
        val erh = SymbolTable.collectErhSymbols(
            """
            #DIM CONST IS 1
            #DIM CONST FLAG 1
            #DIM MONEY
            #DIM CONST GOOD = 1
            """.trimIndent() + "\n",
            "T.ERH",
        )
        assertEquals(listOf("GOOD"), erh.dimConsts.map { it.name }, "只有合法声明进常量表")
        assertEquals(3, erh.diag.size, "三条拒绝逐条留痕：${erh.diag.joinToString("\n")}")
        assertEquals(listOf(1, 2, 3), erh.diag.map { it.line })
        assertEquals("变量名\"IS\"为Emuera的保留字", erh.diag[0].message)
        assertEquals("变量名\"FLAG\"为Emuera所使用的变量名", erh.diag[1].message)
        assertEquals("变量名\"MONEY\"为Emuera所使用的变量名", erh.diag[2].message)
        assertFalse(erh.chainAborted, "#DIM 的 CodeEE 不中止 ERH 链（spec: ErhLoader.cs:328-332）")
    }

    @Test
    fun `ERH 里 DEFINE 撞保留名是链级错误`() {
        val erh = SymbolTable.collectErhSymbols(
            """
            #DEFINE IS 1
            #DEFINE AFTER 2
            """.trimIndent() + "\n",
            "T.ERH",
            stopAtFirstChainError = true,
        )
        assertTrue(erh.macros.isEmpty(), "被拒的宏不登记，且后续行不再处理")
        assertTrue(erh.chainAborted, "宏名冲突 → noError=false → 中止整条链")
        assertEquals(1, erh.diag.size, "首错即止：${erh.diag.joinToString("\n")}")
        assertEquals("宏名\"IS\"为Emuera的保留字", erh.diag[0].message)
    }

    // -----------------------------------------------------------------------
    // CMP-42：跨文件重名 + 首错即中止
    // -----------------------------------------------------------------------

    @Test
    fun `跨文件宏重名留痕并中止`() {
        val got = SymbolTable.collectErhSymbols(
            """
            #DEFINE DUP 2
            #DEFINE LATER 3
            """.trimIndent() + "\n",
            "SECOND.ERH",
            priorMacroKeys = setOf("DUP"),
            stopAtFirstChainError = true,
        )
        assertEquals(1, got.diag.size)
        assertTrue(got.diag[0].message.startsWith("宏名\"DUP\"为已定义的宏"), "实得：${got.diag[0].message}")
        assertTrue(got.macros.isEmpty(), "首错即止：LATER 也不该被收")
        assertTrue(got.chainAborted)
    }

    @Test
    fun `跨文件全局声明重名只留痕不中止`() {
        val got = SymbolTable.collectErhSymbols(
            """
            #DIM CONST SHARED = 7
            #DIM CONST OTHER = 8
            """.trimIndent() + "\n",
            "SECOND.ERH",
            priorDimConstKeys = setOf("SHARED"),
            stopAtFirstChainError = true,
        )
        assertEquals(listOf("OTHER"), got.dimConsts.map { it.name })
        assertEquals(1, got.diag.size)
        assertEquals("变量名\"SHARED\"为用户已定义的全局变量名", got.diag[0].message)
        assertFalse(got.chainAborted, "#DIM 不中止链")
    }

    @Test
    fun `诊断模式下整份文件逐条留痕`() {
        val got = SymbolTable.collectErhSymbols(
            """
            #DEFINE DUP 1
            #DEFINE DUP 2
            #DEFINE TAIL 3
            """.trimIndent() + "\n",
            "T.ERH",
        )
        assertEquals("1", got.macros["DUP"], "先到者胜")
        assertEquals("3", got.macros["TAIL"], "默认模式不因首错停止（既有诊断口径）")
        assertEquals(1, got.diag.size)
        assertTrue(got.chainAborted, "仍标记链级错误，供装载方决定")
    }

    // -----------------------------------------------------------------------
    // CMP-45：尺寸上下限与 REF 尺寸
    // -----------------------------------------------------------------------

    private fun dim(line: String): Pair<DimSpec?, String?> {
        var reason: String? = null
        val spec = SymbolTable.parseDimSpec("DIM", line, isPrivate = false) { reason = it }
        return spec to reason
    }

    @Test
    fun `字面尺寸越界报 OoRDefinable`() {
        // spec: UserDefinedVariableData.cs:337-340（<=0 或 >1000000）→ zhs:763
        for (bad in listOf("A, 0", "A, -3", "A, 1000001", "A, 2, 0")) {
            val (spec, reason) = dim(bad)
            assertNull(spec, "应拒：$bad")
            assertEquals("用户定义变量的数组大小须在1至1000000之间", reason, "越界文案：$bad")
        }
        for (ok in listOf("A, 1", "A, 1000000", "A, 0x10", "A, 3, 4, 5")) {
            val (spec, reason) = dim(ok)
            assertNotNull(spec, "应收：$ok（reason=$reason）")
            assertNull(reason)
        }
        // 非字面量（宏 / CONST 标识符）不在收集期判上下限，交下游求值
        assertNotNull(dim("A, MAXN").first, "标识符尺寸保留原文")
    }

    @Test
    fun `REF 尺寸必须为 0`() {
        // spec: UserDefinedVariableData.cs:329-335 → zhs:762
        val (bad, reason) = dim("REF R, 3")
        assertNull(bad, "REF 带非 0 尺寸应拒")
        assertEquals("引用型变量不能指定数组大小(请省略或指定为0)", reason)

        val (zero, noReason) = dim("REF R, 0")
        assertNotNull(zero, "REF 尺寸 0 合法")
        assertNull(noReason)
        assertEquals(1, zero!!.dims.size)

        val (twoDims, _) = dim("REF R, ,")
        assertEquals(2, twoDims!!.dims.size, "每个顶层逗号贡献一维（spec: UserDefinedVariableData.cs:309-320）")
    }
}
