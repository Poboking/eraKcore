package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 变量下标族文案钉（2026-11-04 lane errtext）—— `core/vars/VarErrorText.kt` 的真源逐字对账。
 *
 * 口径与照抄纪律同 `core/err/OperatorErrorTextTest`（运行期 CodeEE 取 zhs 译文、只做按序号
 * 原位替换、不 trim、不改引号形态、不修误译，ADR-005）。四条模板在**两份官方表同文**，
 * 所以本族对齐不受「语料根表 vs reference 表」的口径之争影响：
 *
 * | 键 | 日文 `Lang.cs` | 语料根 `lang/emuera.zhs.xml` | reference 表 | 原版抛出点（本机可核） |
 * |---|---|---|---|---|
 * | OoRVarArg | :1638 | :585 | :364 | UserDefinedVariableToken.cs:62/66/70、VariableData.cs:85/177、LocalVariableToken.cs:43、ReferenceToken.cs:66/70/74 |
 * | OoRCharaVarArg | :1622 | :568 | :360 | CharaVariableToken.cs:60/64/68、UserDefinedFunctionArgument.cs:51 |
 * | ZeroDVarHasArg | :1566 | :774 | :346 | VariableParser.cs:178 |
 * | EmptyRefVar | :1642 | :272 | :365 | ReferenceToken.cs:36/49/62/120、VariableData.cs:2329 起 |
 *
 * 上报取证（`<work-root>/.tmp/report-20261104/raw-500`）：
 * `变量 ITEMNAME 的第 1 维下标 1000 越界（长度 1000）` 2 份（0.2.39）、
 * `变量 EXPLV 的第 1 维下标 5002 越界（长度 200）` 与 `变量 EXPNAME 的第 1 维下标 666 越界（长度 200）`
 * 各 1 份（0.2.35）、`引用类型变量"BOARD"缺少引用对象` 3 份（0.2.0，这一条本来就是官方串，
 * 说明移植侧同一个键曾同时存在两形）。
 */
class VarErrorTextTest {

    // ------------------------------------------------------------------
    // 一、成品串逐字 = 官方（含照抄纪律的三个易错点）
    // ------------------------------------------------------------------

    @Test
    fun `数组变量越界逐字等于官方 OoRVarArg`() {
        assertEquals(
            "数组变量\"ITEMNAME\"的第1参数(1000)超出数组范围",
            oorVarArg("ITEMNAME", 1, 1000L).message,
            "上报 2 份的原读数收口后应上屏成官方串",
        )
        // 维号按序号原位替换（原版实参是字面量 "1"/"2"/"3"，本层喂十进制数，同形）
        assertEquals("数组变量\"MAT\"的第2参数(9)超出数组范围", oorVarArg("MAT", 2, 9L).message)
        assertEquals("数组变量\"X\"的第3参数(0)超出数组范围", oorVarArg("X", 3, 0L).message)
    }

    @Test
    fun `角色变量越界逐字等于官方且保留误译`() {
        // 官方中文译文把「第{1}引数」误译成「第{1}个变量」—— ADR-005：原样保留，不许顺手修好。
        val m = oorCharaVarArg("EXP", 1, 1L).message!!
        assertEquals("角色数组变量\"EXP\"的第1个变量(1)超出角色登记编号的最大范围", m)
        assertTrue(m.contains("个变量"), "误译必须原样在位（Lang.cs:1622 的「引数」不许被改回）")
        assertFalse(m.contains("引数"), "不许半程对齐成日文键的中文直译")
        assertEquals(
            "角色数组变量\"EXP\"的第2个变量(100)超出角色登记编号的最大范围",
            oorCharaVarArg("EXP", 2, 100L).message,
            "CharaVariableToken.cs:64 喂 \"2\"",
        )
    }

    @Test
    fun `零维变量带下标与空引用逐字等于官方`() {
        assertEquals("变量\"RESULTF\"不是数组却带了一个参数", zeroDVarHasArg("RESULTF").message)
        assertEquals("引用类型变量\"BOARD\"缺少引用对象", emptyRefVar("BOARD").message)
    }

    @Test
    fun `照抄纪律的形态细节`() {
        val all = listOf(
            oorVarArg("A", 1, 2L),
            oorCharaVarArg("A", 1, 2L),
            zeroDVarHasArg("A"),
            emptyRefVar("A"),
        )
        for (e in all) {
            val m = e.message!!
            assertFalse(m.contains('{') || m.contains('}'), "占位符必须已全部展开：$m")
            assertFalse(m.contains('“') || m.contains('”'), "引号是半角 U+0022，不许变全角：$m")
            assertFalse(m.contains('「') || m.contains('『'), "不许混入日文引号：$m")
            assertFalse(m != m.trim(), "不 trim、不补空格（官方串首尾无空白）：$m")
        }
        // 「第1参数」之间没有空格（照抄；自造串当初写的是「第 1 维下标」）
        assertTrue(oorVarArg("A", 1, 2L).message!!.contains("的第1参数(2)"))
        // 括号半角
        assertTrue(oorVarArg("A", 1, 2L).message!!.contains("(2)"), "下标用半角括号包")
    }

    // ------------------------------------------------------------------
    // 二、抛出点接线：每个通道都真的走上面那份真源
    // ------------------------------------------------------------------

    @Test
    fun `一维用户变量越界走 OoRVarArg`() {
        val s = VariableStore()
        s.declareUserVar(DimSpec("G0", isStr = false, dims = intArrayOf(1000)))
        assertEquals(0L, s.readInt("G0", longArrayOf(999)), "界内可读")
        val e = assertFailsWith<CodeEE> { s.readInt("G0", longArrayOf(1000)) }
        assertEquals("数组变量\"G0\"的第1参数(1000)超出数组范围", e.message)
    }

    @Test
    fun `多维用户变量按维号走 OoRVarArg`() {
        val s = VariableStore()
        s.declareUserVar(DimSpec("MM", isStr = false, dims = intArrayOf(3, 4)))
        val e = assertFailsWith<CodeEE> { s.readInt("MM", longArrayOf(0, 9)) }
        assertEquals("数组变量\"MM\"的第2参数(9)超出数组范围", e.message, "UserDefinedVariableToken.cs:66 喂 \"2\"")
        // 第 0 维越界报第 1 参数
        val e0 = assertFailsWith<CodeEE> { s.writeInt("MM", longArrayOf(7, 0), 1L) }
        assertEquals("数组变量\"MM\"的第1参数(7)超出数组范围", e0.message)
    }

    @Test
    fun `局部与系统数组越界同串`() {
        val s = VariableStore()
        s.defineLocalLengths("T", VariableStore.LocalLengths(local = 3))
        s.enterFunction("T")
        val e = assertFailsWith<CodeEE> { s.readInt("LOCAL", longArrayOf(5)) }
        assertEquals("数组变量\"LOCAL\"的第1参数(5)超出数组范围", e.message, "LocalVariableToken.cs:43")
        // Long 域判界：2^32 不许先 toInt 回绕成 0 号格（同一条文案，数字原样上屏）
        val big = assertFailsWith<CodeEE> { s.writeInt("LOCAL", longArrayOf(1L shl 32), 1L) }
        assertEquals("数组变量\"LOCAL\"的第1参数(4294967296)超出数组范围", big.message)
        s.leaveFunction()
    }

    @Test
    fun `角色变量两个实参位各自报号`() {
        val s = VariableStore()
        s.addPseudoCharacter() // 1 个活角色，EXP 槽长 100
        val pos = assertFailsWith<CodeEE> { s.readInt("EXP", longArrayOf(1, 0)) }
        assertEquals("角色数组变量\"EXP\"的第1个变量(1)超出角色登记编号的最大范围", pos.message)
        val elem = assertFailsWith<CodeEE> { s.readInt("EXP", longArrayOf(0, 100)) }
        assertEquals("角色数组变量\"EXP\"的第2个变量(100)超出角色登记编号的最大范围", elem.message)
    }

    @Test
    fun `零维变量带下标与空引用 REF 各通道同串`() {
        val s = VariableStore()
        s.declareUserVar(DimSpec("ZZ", isStr = false, dims = intArrayOf()))
        val e = assertFailsWith<CodeEE> { s.readInt("ZZ", longArrayOf(0)) }
        assertEquals("变量\"ZZ\"不是数组却带了一个参数", e.message, "Error.ZeroDVarHasArg 逐字（VariableParser.cs:178）")

        // 全局 REF 在现版本被 NotImplCodeEE 挡下（spec: SystemVariableBootstrap.kt:219-223），
        // 所以 REF 只能作函数私有变量声明并先进帧。
        s.declareUserVar(DimSpec("UR", isStr = false, dims = intArrayOf(0), isRef = true, isPrivate = true), "F")
        s.enterFunction("F")
        val unbound = assertFailsWith<CodeEE> { s.readInt("UR", longArrayOf(0)) }
        assertEquals("引用类型变量\"UR\"缺少引用对象", unbound.message)

        (s.userVar("UR") as RefUserVar).bindInt(LongArray(4))
        val oob = assertFailsWith<CodeEE> { s.readInt("UR", longArrayOf(9)) }
        assertEquals("数组变量\"UR\"的第1参数(9)超出数组范围", oob.message, "ReferenceToken.cs:66")
        s.leaveFunction()
    }

    /**
     * `RefUserVar.checkBound` 是压平通道的第二道闸（元素下标已折成 Int）：
     * 它与 [com.eratw.port.core.vars.VariableStore] 的 refElem 必须报同一句，
     * 否则同一个错误会因走哪条路而不同形。
     */
    @Test
    fun `REF 压平写入的边界闸同串`() {
        val s = VariableStore()
        s.declareUserVar(DimSpec("UR", isStr = false, dims = intArrayOf(0), isRef = true, isPrivate = true), "F")
        s.enterFunction("F")
        (s.userVar("UR") as RefUserVar).bindInt(LongArray(4))
        val ref = s.userVar("UR") as RefUserVar
        val e = assertFailsWith<CodeEE> { ref.writeInt(0, 9, 1L) }
        assertEquals("数组变量\"UR\"的第1参数(9)超出数组范围", e.message)
        assertFalse(e.message!!.contains("目标长度"), "官方串不报目标长度（照抄，不许留旧尾巴）")
        s.leaveFunction()
    }
}
