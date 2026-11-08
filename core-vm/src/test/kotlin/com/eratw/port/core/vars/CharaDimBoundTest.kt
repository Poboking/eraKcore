package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 角色变量第一维（角色位置）越界判据的口径钉：上界 = **活角色数**
 * （[VariableStore] 内部角色表的 count，与 CHARANUM 伪变量同源），不是定长
 * MAXCHARA，也不是模板数。
 *
 * C# 对拍（reference 只读）：
 * - 第一维判据在 `CharaVariableToken.CheckElement`（CharaVariableToken.cs:56-61）：
 *   第 1 参数与 `varData.CharacterList.Count` 比较，越界文案的第 2 实参恒为 "1"（维度号）；
 * - 第 2/3 参数才用 `sizes[0]`/`sizes[1]`（:62-69），sizes 来自
 *   `CharacterData.CharacterVarLength`（CharacterData.cs:194-237）：1 维整数角色变量
 *   取 `constant.CharacterIntArrayLength[num]`（:209-211），默认 100、TALENT/CFLAG
 *   1000、JUEL/GOTJUEL 200（ConstantData.cs:240-247）；装载来源是 VariableSize.csv
 *   （ConstantData.cs:262、:285 → :506/:510）。移植侧默认表见 VariableSizes.kt
 *   （84 槽全 100 + 同四处覆盖），EXP 无覆盖 = 100。
 *
 * 首差背景（M4-K k-charanum，bootSmoke `ADD_ALL_CHARACTERS:95`）：语料
 * NEWGAME/NEWGAME.ERB:96 的 `IF !EXP:LOCAL:接吻経験` 在活角色数 1、LOCAL=1 时抛出。
 * 移植侧判据（VariableStore.checkElement 的第一维分支）与原版同构 —— 红的根因是
 * 「角色没加上」（形参绑定把第 0 个实参的值写进了 ARG 桶 0 号槽，ADDCHARA_INIT
 * 体内的 `SIF !ARG` 为假而跳过 ADDCHARA），不是判据错。本文件把判据口径钉死，
 * 防止后续把上界改回定长而偏离原版。
 *
 * 全部用例假体角色、不读语料（无 ERATW_CORPUS/ERATW_GAMEDIR 依赖）。
 *
 * ## 文案口径（2026-11-04 lane errtext 收口）
 * 本层原先用自造串「角色变量 X 的第 1 参数 P 越界（角色数 N）」，屏上没有 N；
 * 现取官方 `Error.OoRCharaVarArg` 逐字（Lang.cs:1622 / 语料根 `lang/emuera.zhs.xml:568` /
 * reference 表 `:360`，两表同文）：`角色数组变量"{0}"的第{1}个变量({2})超出角色登记编号的最大范围`。
 * 官方串**不报角色数**（只报变量名/序号/越界值，spec: CharaVariableToken.cs:60/64/68 的实参就是
 * `varName, "1"|"2"|"3", arguments[d]`），所以「上界随活角色数走」这一判据在本文件里由
 * **边界探针**钉：同一个下标在 count 变化前后从「越界」变「合法」，而不是靠文案里的数字。
 * 中文译文把「第{1}引数」误译成「第{1}个变量」，按 ADR-005 原样保留、不许「顺手修好」。
 */
class CharaDimBoundTest {

    /** 官方 `Error.OoRCharaVarArg` 展开形如（[no] = 1 基序号、[v] = 越界值）。 */
    private fun charaOob(name: String, no: Int, v: Long): String =
        "角色数组变量\"$name\"的第${no}个变量($v)超出角色登记编号的最大范围"

    private fun idx(vararg v: Long) = v

    private fun store(vararg templates: CharacterTemplate): VariableStore {
        val s = VariableStore()
        templates.forEach { s.registerTemplate(it) }
        return s
    }

    @Test
    fun `第一维上界随活角色数走`() {
        val s = store()
        // 0 人：位置 0 也越界
        val e0 = assertFailsWith<CodeEE> { s.readInt("EXP", idx(0, 0)) }
        assertEquals(charaOob("EXP", 1, 0), e0.message, "Error.OoRCharaVarArg 逐字（CharaVariableToken.cs:60）")
        s.addPseudoCharacter()
        // 1 人：位置 0 合法、位置 1 越界 —— bootSmoke 首差在 VM 以下层的最小复现
        assertEquals(0L, s.readInt("EXP", idx(0, 0)))
        val e1 = assertFailsWith<CodeEE> { s.readInt("EXP", idx(1, 0)) }
        assertEquals(charaOob("EXP", 1, 1), e1.message, "上界 = 活角色数 1 ⇒ 位置 1 越界")
        assertFalse(e1.message!!.contains("第2个"), "角色位是第 1 个实参（:60 喂 \"1\"）")
        s.addPseudoCharacter()
        // 2 人：位置 1 变合法、位置 2 越界 —— 上界跟着 count 涨，不是定长
        assertEquals(0L, s.readInt("EXP", idx(1, 0)))
        val e2 = assertFailsWith<CodeEE> { s.readInt("EXP", idx(2, 0)) }
        assertEquals(charaOob("EXP", 1, 2), e2.message, "上界 = 活角色数 2 ⇒ 位置 2 越界")
    }

    @Test
    fun `模板存在不放宽角色位置上界`() {
        // EXISTCSV 读的是模板表（VarMethods.kt 的 EXISTCSV = store.template(no) != null），
        // 角色位置判的是角色表：两张表，模板在而角色未 ADDCHARA 时读角色变量照样红
        // （首差根因链：EXISTCSV(1)=1 为真、ADDCHARA 1 被跳过、EXP:1 越界）。
        val s = store(CharacterTemplate(no = 0, name = "A"), CharacterTemplate(no = 1, name = "B"))
        assertTrue(s.template(0) != null)
        assertTrue(s.template(1) != null)
        assertEquals(charaOob("EXP", 1, 0), assertFailsWith<CodeEE> { s.readInt("EXP", idx(0, 0)) }.message)
        s.addCharacter(0)
        assertEquals(0L, s.readInt("EXP", idx(0, 0)))
        // 模板 NO=1 存在（EXISTCSV(1) 为真）不等于位置 1 合法
        assertEquals(charaOob("EXP", 1, 1), assertFailsWith<CodeEE> { s.readInt("EXP", idx(1, 0)) }.message)
    }

    @Test
    fun `第二维按声明尺寸判与角色数无关`() {
        val s = store()
        s.addPseudoCharacter()
        // 默认 registry：EXP 槽无 VariableSize 覆盖 = 100（C# 默认同值，ConstantData.cs:242）
        assertEquals(100, s.getLength("EXP", 0))
        assertEquals(0L, s.readInt("EXP", idx(0, 99)))
        // 第 2 个实参 = 第 1 维元素下标（CharaVariableToken.cs:64 喂 "2"）
        assertEquals(charaOob("EXP", 2, 100), assertFailsWith<CodeEE> { s.readInt("EXP", idx(0, 100)) }.message)
        // 角色位置判据先于第二维：位置越界时报第 1 个实参，第二维下标合法也救不回
        assertEquals(charaOob("EXP", 1, 1), assertFailsWith<CodeEE> { s.readInt("EXP", idx(1, 99)) }.message)
    }

    @Test
    fun `零维角色变量同上界`() {
        val s = store()
        s.addPseudoCharacter()
        assertEquals(0L, s.readInt("NO", idx(0)))
        assertEquals(charaOob("NO", 1, 1), assertFailsWith<CodeEE> { s.readInt("NO", idx(1)) }.message)
    }

    @Test
    fun `写路径同判据`() {
        val s = store()
        s.addPseudoCharacter()
        s.writeInt("EXP", idx(0, 0), 5)
        assertEquals(5L, s.readInt("EXP", idx(0, 0)))
        assertEquals(charaOob("EXP", 1, 1), assertFailsWith<CodeEE> { s.writeInt("EXP", idx(1, 0), 5) }.message)
    }

    @Test
    fun `删人后上界收缩`() {
        val s = store()
        s.addPseudoCharacter()
        s.addPseudoCharacter()
        assertEquals(0L, s.readInt("EXP", idx(1, 0)))
        s.deleteCharacter(0)
        assertEquals(1L, s.readInt("CHARANUM", idx()))
        assertEquals(charaOob("EXP", 1, 1), assertFailsWith<CodeEE> { s.readInt("EXP", idx(1, 0)) }.message)
    }
}
