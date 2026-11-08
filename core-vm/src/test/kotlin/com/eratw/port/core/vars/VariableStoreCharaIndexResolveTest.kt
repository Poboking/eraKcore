package com.eratw.port.core.vars

import com.eratw.port.core.entity.VirtualCharaIndexResolver
import com.eratw.port.core.entity.VirtualCharaNoRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * D-2 接线验收（蓝图 §16.4）：VariableStore 角色位唯一翻译入口
 * [VariableStore.resolveCharaPos] 的五条判据，逐条钉死。
 *
 * ## 装置语义
 * - 3 行原生角色（模板 NO = 1/2/3，物理行 0/1/2）；
 * - 实体 `42L` 分配虚拟 NO（缺省游标 ⇒ 10005），宿主桥 [rowOfEntity] 投射到物理行 1；
 * - `CFLAG` = 1 维角色整数变量（角色位 + 元素维，spec: SystemLayout.charaInt1D）、
 *   `NAME` = 0 维角色字符串变量（charaStr0D）、`NO` = 0 维角色整数变量（存模板 NO，
 *   spec: VariableStore.addCharacter 写 `template.no`，C# 锚 CharacterData.cs:131）。
 *
 * ## 文案口径
 * OOR 路径断**上屏整串**（项目惯例；模板 = `LangKey.OoRCharaVarArg`，
 * zhs「角色数组变量…超出角色登记编号的最大范围」，VarErrorTextTest 已钉形状），
 * 且第 3 实参必须是**脚本看到的原始下标**（§16.4 OOR 原值穿透裁定）——
 * 显示 -1 即红线。
 */
class VariableStoreCharaIndexResolveTest {

    /** 实体身份（任意非 0 Long）。 */
    private val entityId = 42L

    /** 虚拟 NO 缺省游标 = VIRTUAL_NO_BASE（注册表空 + 首次 allocate；不是 10005）。 */
    private val vno = 10_000L

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 3 行原生角色 + resolver 注入；[rowOf] = 宿主投射桥（生产 = ICharaSlotProjector.charIndexOf）。 */
    private fun storeWithResolver(rowOf: (Long) -> Int = { if (it == entityId) 1 else -1 }): Pair<VariableStore, VirtualCharaNoRegistry> {
        val reg = VirtualCharaNoRegistry()
        val s = VariableStore(charaIndexResolver = VirtualCharaIndexResolver(reg, rowOf))
        repeat(3) { i ->
            val no = (i + 1).toLong()
            s.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
            s.addCharacter(no)
        }
        return s to reg
    }

    /** 无 resolver（生产缺省 = 无 Mod）：解析层完全旁路的对照装置。 */
    private fun plainStore(): VariableStore {
        val s = VariableStore()
        repeat(3) { i ->
            val no = (i + 1).toLong()
            s.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
            s.addCharacter(no)
        }
        return s
    }

    // ------------------------------------------------------------------
    // 判据 1：无 resolver（null）→ 原版越界，文案逐字同、含原始下标
    // ------------------------------------------------------------------

    @Test
    fun `无 resolver 虚拟NO域下标 报原文串且含原值`() {
        val s = plainStore()
        val e = assertFailsWith<CodeEE> { s.readInt("CFLAG", longArrayOf(vno, 0)) }
        assertEquals(
            "角色数组变量\"CFLAG\"的第1个变量($vno)超出角色登记编号的最大范围",
            e.message,
            "第 3 实参必须是原始下标 10005，翻译成 -1 即红线（§16.4 OOR 原值穿透）",
        )
        // 旁路回归：合法原生行访问零变化（CFLAG 初值 0；行 2 的 NO 列 = 模板 NO 3）
        assertEquals(0L, s.readInt("CFLAG", longArrayOf(1, 0)))
        assertEquals(3L, s.readInt("NO", longArrayOf(2)))
    }

    // ------------------------------------------------------------------
    // 判据 2：resolver 注入 + 注册表空 → 空表旁路（同样 OOR）
    // ------------------------------------------------------------------

    @Test
    fun `注册表空 虚拟NO域下标 报原文串`() {
        val (s, reg) = storeWithResolver()
        assertEquals(0, reg.size, "前置：注册表为空")
        val e = assertFailsWith<CodeEE> { s.readInt("CFLAG", longArrayOf(vno, 0)) }
        assertEquals(
            "角色数组变量\"CFLAG\"的第1个变量($vno)超出角色登记编号的最大范围",
            e.message,
        )
    }

    // ------------------------------------------------------------------
    // 判据 3：虚拟 NO 命中已投射实体 → 读/写落实体当前投射行（四路）
    // ------------------------------------------------------------------

    @Test
    fun `虚拟NO读 C_INT1 落投射行`() {
        val (s, reg) = storeWithResolver()
        reg.allocate(entityId)
        s.writeInt("CFLAG", longArrayOf(1, 0), 777L)
        assertEquals(777L, s.readInt("CFLAG", longArrayOf(vno, 0)))
        assertEquals(0L, s.readInt("CFLAG", longArrayOf(0, 0)), "相邻行不受影响")
    }

    @Test
    fun `虚拟NO写 C_INT1 落投射行`() {
        val (s, reg) = storeWithResolver()
        reg.allocate(entityId)
        s.writeInt("CFLAG", longArrayOf(vno, 0), 888L)
        assertEquals(888L, s.readInt("CFLAG", longArrayOf(1, 0)))
    }

    @Test
    fun `虚拟NO读 C_INT0 的NO列落投射行`() {
        val (s, reg) = storeWithResolver()
        reg.allocate(entityId)
        assertEquals(2L, s.readInt("NO", longArrayOf(vno)), "NO 列存模板 NO（行 1 = 角色2）")
    }

    @Test
    fun `虚拟NO字符串读写 C_STR0 落投射行`() {
        val (s, reg) = storeWithResolver()
        reg.allocate(entityId)
        assertEquals("角色2", s.readStr("NAME", longArrayOf(vno)))
        s.writeStr("NAME", longArrayOf(vno), "子嗣")
        assertEquals("子嗣", s.readStr("NAME", longArrayOf(1)))
    }

    @Test
    fun `虚拟NO 用户CHARADATA变量 走 checkAndSlot 通道`() {
        val (s, reg) = storeWithResolver()
        s.declareUserVar(DimParser.parse("#DIM", "CHARADATA UD, 2", false))
        reg.allocate(entityId)
        s.writeInt("UD", longArrayOf(1, 0), 555L)
        assertEquals(555L, s.readInt("UD", longArrayOf(vno, 0)))
        s.writeInt("UD", longArrayOf(vno, 1), 556L)
        assertEquals(556L, s.readInt("UD", longArrayOf(1, 1)))
    }

    @Test
    fun `虚拟NO setAll 整段写通道落投射行`() {
        val (s, reg) = storeWithResolver()
        reg.allocate(entityId)
        s.setAll("CFLAG", 999L, charaPos = vno.toInt())
        assertEquals(999L, s.readInt("CFLAG", longArrayOf(1, 0)))
        assertEquals(0L, s.readInt("CFLAG", longArrayOf(2, 0)), "整段写只落翻译后的那一行")
    }

    // ------------------------------------------------------------------
    // 判据 4：未投射/行失效/负数/超 Int 域 → OOR，文案含原始虚拟 NO
    // ------------------------------------------------------------------

    @Test
    fun `未投射实体 原值穿透报错`() {
        // rowOf 恒 -1 = 实体未投射（注册表里有号，宿主桥给不出行）
        val (s, reg) = storeWithResolver(rowOf = { -1 })
        reg.allocate(entityId)
        val e = assertFailsWith<CodeEE> { s.readInt("CFLAG", longArrayOf(vno, 0)) }
        assertEquals(
            "角色数组变量\"CFLAG\"的第1个变量($vno)超出角色登记编号的最大范围",
            e.message,
        )
    }

    @Test
    fun `投射行越界 行失效同 OOR`() {
        val (s, reg) = storeWithResolver(rowOf = { 9 })
        reg.allocate(entityId)
        val e = assertFailsWith<CodeEE> { s.readInt("CFLAG", longArrayOf(vno, 0)) }
        assertTrue(e.message!!.contains("$vno"), "文案含原始虚拟 NO")
    }

    @Test
    fun `负数下标 OOR 原样入串`() {
        val (s, _) = storeWithResolver()
        val e = assertFailsWith<CodeEE> { s.readInt("CFLAG", longArrayOf(-1, 0)) }
        assertEquals(
            "角色数组变量\"CFLAG\"的第1个变量(-1)超出角色登记编号的最大范围",
            e.message,
        )
    }

    @Test
    fun `超Int域下标 OOR`() {
        val (s, reg) = storeWithResolver()
        reg.allocate(entityId)
        val beyond = 4_294_967_296L
        val e = assertFailsWith<CodeEE> { s.readInt("CFLAG", longArrayOf(beyond, 0)) }
        assertTrue(e.message!!.contains("4294967296"), "Long 原值上屏（非 toInt 回绕后的 0）")
    }

    @Test
    fun `checkAndSlot 通道 OOR 同串`() {
        val (s, reg) = storeWithResolver()
        s.declareUserVar(DimParser.parse("#DIM", "CHARADATA UD, 2", false))
        reg.allocate(entityId)
        val e = assertFailsWith<CodeEE> { s.readInt("UD", longArrayOf(50_000, 0)) }
        assertEquals(
            "角色数组变量\"UD\"的第1个变量(50000)超出角色登记编号的最大范围",
            e.message,
        )
    }

    @Test
    fun `setAll 通道 OOR 同串`() {
        val (s, _) = storeWithResolver()
        // 未分配号 → 注册表 miss → OOR 原值穿透（命中面已由 setAll 命中例覆盖）
        val e = assertFailsWith<CodeEE> { s.setAll("CFLAG", 1L, charaPos = 50_000) }
        assertEquals(
            "角色数组变量\"CFLAG\"的第1个变量(50000)超出角色登记编号的最大范围",
            e.message,
        )
    }

    // ------------------------------------------------------------------
    // 判据 5：resolver 直驱契约——CHARANUM < 0 抛 IllegalArgumentException
    // ------------------------------------------------------------------

    @Test
    fun `resolver 直驱 CHARANUM负数 抛 IllegalArgumentException`() {
        val (_, reg) = storeWithResolver()
        val resolver = VirtualCharaIndexResolver(reg) { 0 }
        val ex = assertFailsWith<IllegalArgumentException> { resolver.resolve(0L, -1) }
        assertTrue(ex.message!!.contains("CHARANUM"))
    }

    // ------------------------------------------------------------------
    // 分配面回归：虚拟 NO 永不复用 + 原生 CSV 避让（D-1 既有口径的接线侧复述）
    // ------------------------------------------------------------------

    @Test
    fun `分配号避让现存CSV编号`() {
        val (s, reg) = storeWithResolver()
        // 模板 NO 1..3 已注册；游标从 VIRTUAL_NO_BASE = 10000 起 ⇒ 首个分配即 10000
        assertEquals(10_000, reg.allocate(entityId))
        assertEquals(10_001, reg.allocate(entityId + 1))
        assertEquals(2, reg.size)
        assertEquals(3, s.characters.count, "分配不改角色表")
    }
}