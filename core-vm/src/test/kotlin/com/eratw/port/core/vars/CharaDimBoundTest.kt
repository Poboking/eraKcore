package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
 */
class CharaDimBoundTest {

    private fun idx(vararg v: Long) = v

    private fun store(vararg templates: CharacterTemplate): VariableStore {
        val s = VariableStore()
        templates.forEach { s.registerTemplate(it) }
        return s
    }

    @Test
    fun `第一维上界随活角色数走`() {
        val s = store()
        // 0 人：位置 0 也越界，文案带活角色数
        val e0 = assertFailsWith<CodeEE> { s.readInt("EXP", idx(0, 0)) }
        assertTrue(e0.message!!.contains("角色数 0"))
        s.addPseudoCharacter()
        // 1 人：位置 0 合法、位置 1 越界 —— bootSmoke 首差在 VM 以下层的最小复现
        assertEquals(0L, s.readInt("EXP", idx(0, 0)))
        val e1 = assertFailsWith<CodeEE> { s.readInt("EXP", idx(1, 0)) }
        assertTrue(e1.message!!.contains("角色数 1"))
        s.addPseudoCharacter()
        // 2 人：位置 1 变合法、位置 2 越界 —— 上界跟着 count 涨，不是定长
        assertEquals(0L, s.readInt("EXP", idx(1, 0)))
        val e2 = assertFailsWith<CodeEE> { s.readInt("EXP", idx(2, 0)) }
        assertTrue(e2.message!!.contains("角色数 2"))
    }

    @Test
    fun `模板存在不放宽角色位置上界`() {
        // EXISTCSV 读的是模板表（VarMethods.kt 的 EXISTCSV = store.template(no) != null），
        // 角色位置判的是角色表：两张表，模板在而角色未 ADDCHARA 时读角色变量照样红
        // （首差根因链：EXISTCSV(1)=1 为真、ADDCHARA 1 被跳过、EXP:1 越界）。
        val s = store(CharacterTemplate(no = 0, name = "A"), CharacterTemplate(no = 1, name = "B"))
        assertTrue(s.template(0) != null)
        assertTrue(s.template(1) != null)
        val e = assertFailsWith<CodeEE> { s.readInt("EXP", idx(0, 0)) }
        assertTrue(e.message!!.contains("角色数 0"))
        s.addCharacter(0)
        assertEquals(0L, s.readInt("EXP", idx(0, 0)))
        // 模板 NO=1 存在（EXISTCSV(1) 为真）不等于位置 1 合法
        val e1 = assertFailsWith<CodeEE> { s.readInt("EXP", idx(1, 0)) }
        assertTrue(e1.message!!.contains("角色数 1"))
    }

    @Test
    fun `第二维按声明尺寸判与角色数无关`() {
        val s = store()
        s.addPseudoCharacter()
        // 默认 registry：EXP 槽无 VariableSize 覆盖 = 100（C# 默认同值，ConstantData.cs:242）
        assertEquals(100, s.getLength("EXP", 0))
        assertEquals(0L, s.readInt("EXP", idx(0, 99)))
        val e = assertFailsWith<CodeEE> { s.readInt("EXP", idx(0, 100)) }
        assertTrue(e.message!!.contains("第 2 维下标 100 越界（长度 100）"))
        // 角色位置判据先于第二维：位置越界时报活角色数，第二维下标合法也救不回
        val e1 = assertFailsWith<CodeEE> { s.readInt("EXP", idx(1, 99)) }
        assertTrue(e1.message!!.contains("角色数 1"))
    }

    @Test
    fun `零维角色变量同上界`() {
        val s = store()
        s.addPseudoCharacter()
        assertEquals(0L, s.readInt("NO", idx(0)))
        val e = assertFailsWith<CodeEE> { s.readInt("NO", idx(1)) }
        assertTrue(e.message!!.contains("角色数 1"))
    }

    @Test
    fun `写路径同判据`() {
        val s = store()
        s.addPseudoCharacter()
        s.writeInt("EXP", idx(0, 0), 5)
        assertEquals(5L, s.readInt("EXP", idx(0, 0)))
        val e = assertFailsWith<CodeEE> { s.writeInt("EXP", idx(1, 0), 5) }
        assertTrue(e.message!!.contains("角色数 1"))
    }

    @Test
    fun `删人后上界收缩`() {
        val s = store()
        s.addPseudoCharacter()
        s.addPseudoCharacter()
        assertEquals(0L, s.readInt("EXP", idx(1, 0)))
        s.deleteCharacter(0)
        assertEquals(1L, s.readInt("CHARANUM", idx()))
        val e = assertFailsWith<CodeEE> { s.readInt("EXP", idx(1, 0)) }
        assertTrue(e.message!!.contains("角色数 1"))
    }
}
