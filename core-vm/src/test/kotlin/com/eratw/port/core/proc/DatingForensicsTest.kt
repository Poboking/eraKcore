package com.eratw.port.core.proc

import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [datingForensics] 的定向单测（diag-datingsync）。
 *
 * 断言的是**观察口自己的形状**，不是语料行为：判式 `CFLAG:no:约会中 != MAIN_MAP` 逐字
 * 复刻 4.980 `情事発覚.ERB:475` + `物件関連/COMMON2.ERB:15-18`（取证见 BOARD），
 * 所以「MAIN_MAP=1、无人真约会 ⇒ 全员计入」这条用例是**故意**复刻官方的过判（ADR-005 不修）。
 * 裸 store 无 CSV：全部走数字元素号（12 / 73 / 199 / 300），出处为 AutoConst 头。
 */
class DatingForensicsTest {

    private fun store(): VariableStore = VariableStore()

    private fun VariableStore.declareMainMap(value: Long): VariableStore = apply {
        declareUserVar(DimParser.parse("#DIM", "SAVEDATA MAIN_MAP", false))
        writeInt("MAIN_MAP", longArrayOf(0), value)
    }

    private fun VariableStore.declareRandomCharaNum(value: Long): VariableStore = apply {
        declareUserVar(DimParser.parse("#DIM", "SAVEDATA RANDOM_CHARANUM", false))
        writeInt("RANDOM_CHARANUM", longArrayOf(0), value)
    }

    private fun VariableStore.dating(no: Int, value: Long) =
        writeInt("CFLAG", longArrayOf(no.toLong(), DatingForensics.CFLAG_DATING), value)

    @Test
    fun `裸 store 不抛——语料侧 #DIM 缺席即整行无信息量`() {
        val f = datingForensics(store())
        assertTrue(f.isEmpty, "MAIN_MAP 与 RANDOM_CHARANUM 都没声明 ⇒ 判定为不可用")
        assertNull(f.mainMap)
        assertNull(f.mismatchCount)
        // 系统变量恒可读（尺寸走 VariableSizes 缺省表），它们不该被算作"有读数"
        assertEquals(0L, f.affairTarget)
        assertEquals(0L, f.forgetBit)
        // MASTER 指针本身是 0，但角色表空 ⇒ CFLAG:0 越界，这一格照样降级不抛
        assertNull(f.masterDate)
    }

    @Test
    fun `MAIN_MAP 未声明时明细整段放弃——其余字段照读`() {
        val s = store()
        repeat(3) { s.characters.addBare() }
        s.writeInt("FLAG", longArrayOf(DatingForensics.FLAG_AFFAIR_TARGET), 67L)
        val f = datingForensics(s)
        assertNull(f.mismatchCount)
        assertNull(f.mismatchDetails)
        assertEquals(3, f.charaNum)
        assertEquals(67L, f.affairTarget)
    }

    @Test
    fun `独处误报形态——MAIN_MAP 非 0 时空角色全被判成约会中`() {
        val s = store().declareMainMap(1L)
        repeat(4) { s.characters.addBare() }
        s.dating(2, 1L) // 唯一"确实没被误判"的角色：值正等于 MAIN_MAP
        val f = datingForensics(s)
        assertEquals(3, f.mismatchCount)
        assertEquals(listOf("0:0", "1:0", "3:0"), f.mismatchDetails)
        assertFalse(f.isEmpty)
    }

    @Test
    fun `明细截断到 12 条而计数不截断`() {
        val s = store().declareMainMap(9L)
        repeat(20) { s.characters.addBare() }
        val f = datingForensics(s)
        assertEquals(20, f.mismatchCount)
        assertEquals(DatingForensics.MISMATCH_DETAIL_LIMIT, f.mismatchDetails?.size)
        assertEquals("0:0", f.mismatchDetails?.first())
        assertEquals("11:0", f.mismatchDetails?.last())
    }

    @Test
    fun `MASTER 取角色指针——负指针降级、正指针按号读`() {
        val s = store().declareMainMap(0L)
        repeat(3) { s.characters.addBare() }
        s.dating(1, 5L)
        s.writeInt("MASTER", longArrayOf(0), -1L)
        assertNull(datingForensics(s).masterDate, "MASTER=-1（无主人/数组禁用）时不读 CFLAG")
        s.writeInt("MASTER", longArrayOf(0), 1L)
        assertEquals(5L, datingForensics(s).masterDate)
    }

    @Test
    fun `路人子双半闸——RANDOM_CHARANUM 驱动 TALENT 与 bit9`() {
        val s = store().declareMainMap(0L).declareRandomCharaNum(2L)
        repeat(3) { s.characters.addBare() }
        s.writeInt("TALENT", longArrayOf(2, DatingForensics.TALENT_MOBGIRL), 1L)
        s.writeInt("TFLAG", longArrayOf(DatingForensics.TFLAG_ONCE_PER_DAY), 1L shl DatingForensics.FORGET_BIT)
        val f = datingForensics(s)
        assertEquals(2L, f.randomCharaNum)
        assertEquals(1L, f.mobTalent)
        assertEquals(1L, f.forgetBit)
        // bit8 不算：GETBIT 只取第 9 位（spec: FunctionMethodCreator.cs:4766-4791 的 shr/and 式）
        s.writeInt("TFLAG", longArrayOf(DatingForensics.TFLAG_ONCE_PER_DAY), 1L shl 8)
        assertEquals(0L, datingForensics(s).forgetBit)
    }

    @Test
    fun `路人号越界时天赋一格读不到——不抛、其余照旧`() {
        val s = store().declareRandomCharaNum(500L)
        repeat(2) { s.characters.addBare() }
        val f = datingForensics(s)
        assertEquals(500L, f.randomCharaNum)
        assertNull(f.mobTalent)
    }

    @Test
    fun `render 单行——缺的字段不出现`() {
        val s = store().declareMainMap(1L)
        repeat(2) { s.characters.addBare() }
        s.dating(0, 3L)
        s.writeInt("MASTER", longArrayOf(0), 0L)
        val full = datingForensics(s).render()
        assertEquals("约会现场：MAIN_MAP=1 约会中≠MAIN_MAP=2/2 明细[0:3,1:0] 约会的对象=0 MASTER约会中=3 忘记位=0", full)

        val sparse = DatingForensics(
            mainMap = null, charaNum = 0, mismatchCount = null, mismatchDetails = null,
            affairTarget = null, masterDate = null, randomCharaNum = 7L, mobTalent = 2L, forgetBit = 0L,
        ).render()
        assertEquals("约会现场：路人号=7 路人天赋=2 忘记位=0", sparse)
    }
}
