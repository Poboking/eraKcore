package com.eratw.port.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `addCharacterFromCsvNo`（ADDDEFCHARA 的查表路径）与 `templatesByNo` 同 NO 去重的
 * 定向锁。契约：CONTRACT-W7-AUD §3 W7-d1（AUD-d1-charatmpl）。
 *
 * ## 原版语义（本机 reference 实测行号）
 * - 查找路径：`VariableEvaluator.AddCharacterFromCsvNo`（VariableEvaluator.cs:1223-1232）
 *   → `ConstantData.GetCharacterTemplateFromCsvNo`（ConstantData.cs:1240-1250）：
 *   对**全模板表** `CharacterTmplList` 线性扫、`csvNo` 首命中即返回；未命中 →
 *   `GetPseudoChara()`（ConstantData.cs:1252-1255，NO=0、名字全空）。
 * - 表序：装载期逐文件 append（ConstantData.cs:1492）后按 NO **稳定**排序
 *   （:1503）⇒ 同 NO 的模板按文件序排列，`GetCharacterTemplateFromCsvNo` 的
 *   「首个」= 同 csvNo 候选里 NO 最小、再同则文件序最先。
 * - 消费点只有 ADDDEFCHARA（Process.cs:1289/1292 与 :2172/:2175，`AddCharacterFromCsvNo(0)`
 *   + GameBase `DefaultCharacter`）。
 *
 * ## 与移植的差异形态（审计标的）与判死结论
 * 移植侧 `VariableStore.registerTemplate`（VariableStore.kt:1804-1809）用
 * `putIfAbsent` 把同 NO 的后到模板挡在 `templatesByNo` 外，而
 * `addCharacterFromCsvNo`（VariableStore.kt:1832-1835）只扫 `templatesByNo.values`
 * ⇒ 「同 NO 出现在多个 csvNo 不同的文件」时，后到模板对 csvNo 查找**不可达**
 * （原版能给，移植落伪角色）。这是本波审计点名的唯一丢候选形态：
 * - 同 csvNo 多文件（NO 不同）不丢：两侧都取 NO 最小者（表序 == values 插入序，
 *   装载序由 CharaCsvLoader.load 的 `sortBy`（CharaCsvLoader.kt:61）保证升序、
 *   GameSession.kt:619 按该序注册、`clearTemplates` 无调用方 ⇒ 插入序恒为 NO 升序）；
 * - ADDCHARA/ADDSPCHARA 的按 NO 路（`GetCharacterTemplate` ConstantData.cs:1218-1228 /
 *   `_UseSp` :1230-1238）与 `templatesByNo[no]` 首命中一致，不受影响。
 *
 * **判死依据（2026-11-04 只读普查，python 脚本逐文件解析 NO 行 + 双口径 csvNo）**：
 * 4.980 与 4.981（共享根与 C:/game 副本同型）各 168 个 chara*.csv、各产出 168 模板，
 * 「同 NO 多文件」0 组 ⇒「同 NO 且 csvNo 不同」0 组（Kotlin 文件名口径与 PC 相对路径
 * 口径都为 0）；Kotlin 口径下 168 个文件编号互不重复（csvNo=0 仅 Chara0.csv，NO=0）；
 * 单文件多 NO 行 0、NO 解析失败 0；GameBase.csv 均无 最初からいるキャラ 行
 * （DefaultCharacter 缺省 ⇒ `defNo > 0` 不触发，ADDDEFCHARA 只加 csvNo=0 一个）。
 * ⇒ 该差异形态在两版语料均不可达，按契约「普查为不存在 → 判死登记」处置：**不修码**，
 * 本文件把现行为钉死，作为回归闸。重评条件：将来语料/插件出现同 NO 多文件且 csvNo
 * 不同的 CHARA 源，或 ADDDEFCHARA 之外出现新的 csvNo 查表消费点（如把 Chara 目录
 * 摆平的游戏），届时按 `GetCharacterTemplateFromCsvNo` 的全表线性扫口径重接。
 */
class CharaTemplateLookupTest {

    private fun store(): VariableStore = VariableStore()

    /** 命中：不同 NO 不同 csvNo，各查各的（原版 VariableEvaluator.cs:1225 首命中）。 */
    @Test
    fun csvNoHitReturnsTemplateWithThatCsvNo() {
        val s = store()
        s.registerTemplate(CharacterTemplate(no = 10, name = "甲", csvNo = 10))
        s.registerTemplate(CharacterTemplate(no = 20, name = "乙", csvNo = 20))
        val a = s.addCharacterFromCsvNo(10)
        val b = s.addCharacterFromCsvNo(20)
        assertEquals(10L, s.readInt("NO", longArrayOf(a.toLong())))
        assertEquals("甲", s.readStr("NAME", longArrayOf(a.toLong())))
        assertEquals(20L, s.readInt("NO", longArrayOf(b.toLong())))
        assertEquals("乙", s.readStr("NAME", longArrayOf(b.toLong())))
    }

    /** 未命中 → 伪角色（NO=0、NAME 空）。spec: VariableEvaluator.cs:1226-1229 + ConstantData.cs:1252-1255。 */
    @Test
    fun csvNoMissFallsToPseudoCharacter() {
        val s = store()
        s.registerTemplate(CharacterTemplate(no = 10, name = "甲", csvNo = 10))
        val pos = s.addCharacterFromCsvNo(99)
        assertEquals(0L, s.readInt("NO", longArrayOf(pos.toLong())))
        assertEquals("", s.readStr("NAME", longArrayOf(pos.toLong())))
    }

    /**
     * 同 csvNo 多文件（NO 不同）：取 NO 最小者。原版 = NO 稳定排序后的列表首命中
     * （ConstantData.cs:1503 + :1240-1250）；移植 = values 插入序首个。两侧一致的
     * **前提是插入序为 NO 升序** —— 装载可达态恒满足（CharaCsvLoader.load 的
     * `sortBy`，CharaCsvLoader.kt:61；GameSession.kt:619 按该序注册），本用例即按
     * 该序构造；手工乱序注册是装载不可达态，移植按插入序、原版按 NO 序，可以分叉
     * （登记于类 KDoc，不在锁的范围内）。
     */
    @Test
    fun sameCsvNoDifferentNoLowestNoWins() {
        val s = store()
        s.registerTemplate(CharacterTemplate(no = 1, name = "丁", csvNo = 5))
        s.registerTemplate(CharacterTemplate(no = 3, name = "丙", csvNo = 5))
        val pos = s.addCharacterFromCsvNo(5)
        assertEquals(1L, s.readInt("NO", longArrayOf(pos.toLong())))
        assertEquals("丁", s.readStr("NAME", longArrayOf(pos.toLong())))
    }

    /**
     * 审计标的形态（同 NO 不同 csvNo）：**锁现行为**。`registerTemplate` 的 putIfAbsent
     * 只留文件序首个（`先`），后到模板（`后`，csvNo=2）不在 `templatesByNo` ⇒
     * `addCharacterFromCsvNo(2)` 落伪角色。原版会给出 `后`（全表扫描能越过同 NO 的
     * 前者）——此差异按类 KDoc 的普查结论判死登记（两版语料均无该形态，不可达），
     * 本用例把现行为钉死；改普查结论时这里是要翻的第一块闸。
     */
    @Test
    fun sameNoDifferentCsvNoCurrentBehaviorLocked() {
        val s = store()
        s.registerTemplate(CharacterTemplate(no = 7, name = "先", csvNo = 1))
        s.registerTemplate(CharacterTemplate(no = 7, name = "后", csvNo = 2))
        // csvNo=1：两侧一致（putIfAbsent 首个 == 列表序首个，恒为文件序最先的同 NO 模板）
        val first = s.addCharacterFromCsvNo(1)
        assertEquals(7L, s.readInt("NO", longArrayOf(first.toLong())))
        assertEquals("先", s.readStr("NAME", longArrayOf(first.toLong())))
        // csvNo=2：现行为 = 伪角色（原版 = 「后」；判死登记，见类 KDoc）
        val second = s.addCharacterFromCsvNo(2)
        assertEquals(0L, s.readInt("NO", longArrayOf(second.toLong())))
        assertEquals("", s.readStr("NAME", longArrayOf(second.toLong())))
        // 同 NO 的按 NO 路（ADDCHARA）：首个胜出，两侧一致（GetCharacterTemplate 首命中）
        val byNo = s.addCharacter(7)
        assertEquals(7L, s.readInt("NO", longArrayOf(byNo.toLong())))
        assertEquals("先", s.readStr("NAME", longArrayOf(byNo.toLong())))
    }
}
