package com.eratw.port.perf

import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ===========================================================================
// OBS-VARPOOL 变量池子块装置的定向单测（纯 JVM，不依赖产物）。
//
// 钉住四件事：
// 1. 子块算术：角色表的「容量 / 已用 / 冗余」三者关系与列数组字节实测一致（`reserveBytes` 不是模型臆造）；
// 2. **只读**纪律：快照一个字段都不写、不把懒分配的列物化出来（快照前后 `gInt1` 的 null 个数不变）——
//    产品侧 `UserVar.rawArray()` 走的是懒分配那一步（spec: UserVariables.kt:1613 + :1595-1598），
//    装置一旦调它就等于「参与」而不是「观察」；
// 3. 判性判式：`VarPoolMath.classify` 在「原版满配 / 原版复位释放 / 移植侧预留」三种输入下各给对的那一个；
// 4. 口径不混：子块之间互斥（角色变量的字节只进角色表列，不在用户变量列重复计一遍）。
// ===========================================================================

class VarPoolFacetsTest {

    private val mib = 1024L * 1024L
    private val sizer = SizeModel(SizeModel.detectRefWidth())

    /** 与探针同一条路径：`VarPoolFacets(SizeModel).snapshot(store, store.characters)`。 */
    private fun snap(store: VariableStore): VarPoolCounters = VarPoolFacets(sizer).snapshot(store, store.characters)

    private fun storeWithCharacters(n: Int): VariableStore {
        val s = VariableStore(initialCharaCapacity = 1)
        repeat(n) { i -> s.addCharacter(CharacterTemplate(no = i.toLong())) }
        return s
    }

    // ------------------------------------------------------------------
    // 1. 子块算术
    // ------------------------------------------------------------------

    @Test
    fun `角色表整数列字节等于各列数组的对齐本体`() {
        val s = storeWithCharacters(3)
        val c = s.characters
        var expect = 0L
        for (col in c.int0 + c.int1 + c.int2) expect += SizeModel.align8(16L + col.data.size.toLong() * 8L)
        assertEquals(expect, snap(s).block(VarPoolBlock.CHARA_INT), "整数列字节 = 逐列 align8(16 + 8×长度)，与列数组实测同源")
    }

    @Test
    fun `容量冗余等于容量减计数乘行字节`() {
        val s = storeWithCharacters(5)
        val c = s.characters
        val vp = snap(s)
        val cap = c.allocatedCapacity
        val rowBytes = c.rowBytes()
        assertEquals(
            VarPoolMath.reserveBytes(cap, 5, rowBytes),
            vp.counts["角色表.容量冗余字节"]!!.toLong(),
            "冗余 = (容量 - 已用) × 行字节：列式存储的预留面，判性(乙)的靶子",
        )
        assertEquals(VarPoolMath.usedBytes(5, rowBytes), vp.counts["角色表.已用字节"]!!.toLong(), "已用 = 角色数 × 行字节")
        assertEquals(rowBytes, vp.counts["角色表.行字节"]!!.toLong(), "行字节 = 一行角色在本表全部列上的占用")
    }

    @Test
    fun `纯算术口径与无定义档`() {
        assertEquals(300L, VarPoolMath.usedBytes(3, 100L))
        assertEquals(500L, VarPoolMath.reserveBytes(8, 3, 100L))
        assertEquals(0L, VarPoolMath.reserveBytes(2, 5, 100L), "已用超过容量按 0 —— 模型不该给出负冗余")
        assertEquals(0L, VarPoolMath.usedBytes(-3, 100L), "负行数按 0")
        assertEquals(-1, VarPoolMath.reservePermille(8, 0), "已用为 0 时倍率无定义，给 -1 而不是硬造数")
        assertEquals(8 * 1000 / 3, VarPoolMath.reservePermille(8, 3))
    }

    @Test
    fun `翻倍扩容的冗余落在 doubling 上`() {
        // 与 CharacterTable.ensureCapacity 同形（spec: CharacterTable.kt:270-283）：1→2→4→8。
        var cap = 1
        while (cap < 5) cap = cap shl 1
        assertEquals(8, cap, "装 5 只角色时翻倍后的容量")
        assertEquals(3L * 1_000L, VarPoolMath.reserveBytes(cap, 5, 1_000L), "冗余 = (8 - 5) 行 × 行字节")
        assertTrue(
            VarPoolMath.reserveBytes(cap, 5, 1_000L) < VarPoolMath.usedBytes(cap, 1_000L),
            "冗余必须小于「容量 × 行字节」总额，否则读数口径不对",
        )
    }

    // ------------------------------------------------------------------
    // 2. 只读纪律
    // ------------------------------------------------------------------

    @Test
    fun `快照不物化懒分配的系统整数列`() {
        val s = VariableStore(initialCharaCapacity = 1)
        s.addCharacter(CharacterTemplate(no = 0))
        val before = lazyIntColNulls(s)
        snap(s)
        val after = lazyIntColNulls(s)
        assertEquals(
            before,
            after,
            "子块快照只读：懒分配的 gInt1 列不许被物化（null 个数必须不变）。" +
                "对照：原版 65 列在构造时全灌满（spec: VariableData.cs:2963-2967），" +
                "移植侧是 arrayOfNulls 懒分配（spec: VariableStore.kt:113）",
        )
        assertTrue(after > 0, "至少一列在快照后仍是 null ⇒ 装置没有逐列触发 arr()")
    }

    @Test
    fun `快照不改变角色数与容量`() {
        val s = storeWithCharacters(4)
        val c = s.characters
        val capBefore = c.allocatedCapacity
        val countBefore = c.count
        snap(s)
        assertEquals(countBefore, c.count, "快照不增角色")
        assertEquals(capBefore, c.allocatedCapacity, "快照不扩容")
    }

    /** `gInt1` 里仍为 null 的列数（懒分配未物化的证据）。 */
    private fun lazyIntColNulls(store: VariableStore): Int {
        val f = VariableStore::class.java.getDeclaredField("gInt1")
        f.isAccessible = true
        return (f.get(store) as Array<*>).count { it == null }
    }

    // ------------------------------------------------------------------
    // 3. 判性判式
    // ------------------------------------------------------------------

    @Test
    fun `判式给甲——两侧都不还且冗余在容差内`() {
        assertEquals(
            "甲-原版口径即满配(报峰值预留/水位余量)",
            VarPoolMath.classify(
                portReserveBytes = 2L * mib,
                portReserveTolBytes = 10L * mib,
                originalEagerFullConfig = true,
                originalReleasesOnReset = false,
                portReleasesOnReset = false,
            ),
            "冗余在容差内 ⇒ 不是缺陷，改报峰值预留/水位余量",
        )
    }

    @Test
    fun `判式给乙——移植侧容量冗余越容差`() {
        assertEquals(
            "乙-移植侧过度预留",
            VarPoolMath.classify(40L * mib, 10L * mib, originalEagerFullConfig = true, originalReleasesOnReset = false, portReleasesOnReset = false),
            "原版逐角色精确分配（没有为未用行预留的整块），移植侧列式翻倍预留越容差",
        )
    }

    @Test
    fun `判式给丙——原版复位释放而移植侧不还`() {
        // 原版 ResetData 逐角色 Dispose（spec: CharacterData.cs:756-770 把数组置 null）+ CharacterList.Clear()
        //（spec: VariableEvaluator.cs:1336-1346）；移植侧 deleteAllCharacters() = characters.clear()
        // 移植侧同形修复前的现实（obs-varpool 取证）：`deleteAllCharacters() = characters.clear()`
        // 曾只置 `count = 0` ⇒ 列数组与容量原地留；fix-resetrelease 已让 `clear()` 把列换回起步容量
        // （对照 CharacterData.cs:756-770 的置 null），事实侧见下面那条「产品复位确实归还角色表列」。
        // 本用例钉的是 **classify 判式本身**的三选一优先级，与产品现状无关，两个分支都保留。
        assertEquals(
            "丙-复位链缺口(移植侧复位不还)",
            VarPoolMath.classify(1L * mib, 10L * mib, originalEagerFullConfig = true, originalReleasesOnReset = true, portReleasesOnReset = false),
            "复位缺口优先于预留量 —— 判读的是「回不回得来」，不是「峰值多大」",
        )
        assertEquals(
            "甲-原版口径即满配(报峰值预留/水位余量)",
            VarPoolMath.classify(1L * mib, 10L * mib, originalEagerFullConfig = true, originalReleasesOnReset = true, portReleasesOnReset = true),
            "两侧复位都不还且冗余在容差内 ⇒ 落回甲",
        )
    }

    @Test
    fun `产品复位确实归还角色表列——修复后的事实侧`() {
        // fix-resetrelease：CharacterTable.clear() 除置 count=0 外把五族列的 data 换回起步容量的新数组，
        // 与原版「逐角色 Dispose 置 null + CharacterList.Clear」（VariableEvaluator.cs:1336-1346 /
        // CharacterData.cs:756-770）对齐。列定义（含用户 #DIM CHARADATA 追加列）保留 ⇒ 列下标不失效。
        val s = storeWithCharacters(9)
        val c = s.characters
        val capBefore = c.allocatedCapacity
        val bytesBefore = snap(s).block(VarPoolBlock.CHARA_INT)
        s.resetData()
        assertEquals(0, c.count, "resetData 后角色数归零（spec: VariableEvaluator.cs:1341-1345 CharacterList.Clear）")
        assertTrue(
            c.allocatedCapacity < capBefore,
            "复位后列容量回到起步档、不再原样留：cap $capBefore → ${c.allocatedCapacity}（对照 CharacterData.cs:756-770 的置 null）",
        )
        assertTrue(
            snap(s).block(VarPoolBlock.CHARA_INT) < bytesBefore,
            "整数列字节随复位回吐（修复前该块一字节不还，基准读数 51.9 MiB 原地留）：$bytesBefore → ${snap(s).block(VarPoolBlock.CHARA_INT)}",
        )
    }

    // ------------------------------------------------------------------
    // 4. 口径不混
    // ------------------------------------------------------------------

    @Test
    fun `子块互斥——角色变量列不在用户变量列重复计账`() {
        val s = storeWithCharacters(3)
        val vp0 = snap(s)
        assertEquals(VarPoolBlock.ORDER.size, vp0.bytes.size, "快照出行 = 全部子块键（缺列按 0 也出行）")
        for (k in VarPoolBlock.ORDER) assertTrue(vp0.block(k) >= 0L, "子块字节非负：$k")
        val userGBefore = vp0.block(VarPoolBlock.USER_GLOBAL)
        val charaIntBefore = vp0.block(VarPoolBlock.CHARA_INT)
        s.declareUserVar(DimSpec(name = "CADATA", isStr = false, dims = intArrayOf(4, 8), isCharaData = true))
        val vp1 = snap(s)
        assertEquals(userGBefore, vp1.block(VarPoolBlock.USER_GLOBAL), "用户 CHARADATA 追加列只进角色表列(10.1/10.2)，不进 10.5")
        assertTrue(vp1.block(VarPoolBlock.CHARA_INT) > charaIntBefore, "追加列让角色表整数列变长")
    }

    @Test
    fun `懒分配的用户变量在没写过之前计零`() {
        val s = storeWithCharacters(1)
        val v = s.declareUserVar(DimSpec(name = "GUN", isStr = false, dims = intArrayOf(64), isGlobal = true))
        assertEquals(0L, snap(s).block(VarPoolBlock.USER_GLOBAL), "声明而不写 ⇒ 数据数组还是 null（spec: UserVariables.kt:1592-1598）")
        s.declareUserVar(DimSpec(name = "PRI", isStr = false, dims = intArrayOf(32), isPrivate = true), functionKey = "SYSTEM_TITLE")
        assertEquals(0L, snap(s).block(VarPoolBlock.USER_PRIV), "私有变量同样懒分配")
        v.readInt(0, 0)
        assertTrue(
            snap(s).block(VarPoolBlock.USER_GLOBAL) > 0L,
            "读也物化：StaticIntUserVar.readInt 走的是同一个 arr()（spec: UserVariables.kt:1600 + :1595-1598），" +
                "与原版 IfNullInitArray 同形（spec: VariableData.cs:1311-1321）⇒ 装置的读数时机要认这条",
        )
    }

    @Test
    fun `串表子块随驻留条数增长`() {
        val s = VariableStore(initialCharaCapacity = 1)
        val a = snap(s).block(VarPoolBlock.STR_POOL)
        repeat(200) { s.pool.intern("obs-varpool-$it") }
        val vp = snap(s)
        assertTrue(vp.block(VarPoolBlock.STR_POOL) > a, "驻留 200 条后串表子块必须变长")
        assertTrue(vp.counts["串表.条数/背衬容量/表长"]!!.startsWith("201"), "条数含下标 0 的空串；背衬容量与哈希表长要能分开读")
    }

    @Test
    fun `背衬读不到时的上界标注不是读数`() {
        // JDK 强封装下 `ArrayList.elementData` 反射不到（实测恒 0）⇒ 装置给 1.5× 扩容上界并写明「上界」。
        assertEquals(0, ArrayListCapacityBound.of(0))
        assertEquals(10, ArrayListCapacityBound.of(1), "缺省种子容量 10：装 1 个也是 10")
        assertEquals(10, ArrayListCapacityBound.of(10))
        assertEquals(15, ArrayListCapacityBound.of(11), "10 → 15（JDK grow：old + old/2）")
        assertEquals(33, ArrayListCapacityBound.of(23), "15 → 22 → 33")
        assertTrue(ArrayListCapacityBound.of(90622) >= 90622, "上界必须不小于实际元素数")
    }

    @Test
    fun `串表计数列在背衬读不到时写明口径`() {
        val s = VariableStore(initialCharaCapacity = 1)
        repeat(30) { s.pool.intern("obs-$it") }
        val txt = snap(s).counts["串表.条数/背衬容量/表长"]!!
        assertEquals("31", txt.substringBefore(" / "), "条数含下标 0 的空串")
        val capField = txt.split(" / ")[1]
        assertTrue(
            capField.startsWith("-1(读不到") || capField.toIntOrNull()?.let { it >= 31 } == true,
            "背衬列要么写明「-1(读不到，上界 N)」，要么给不小于条数的真实容量 —— 0 会被读成「没有预留」（来源标注纪律）",
        )
    }
    @Test
    fun `相位差分的键与符号`() {
        val s = VariableStore(initialCharaCapacity = 1)
        val p0 = snap(s)
        s.addCharacter(CharacterTemplate(no = 0))
        s.addCharacter(CharacterTemplate(no = 1))
        val p1 = snap(s)
        val d = p0.diff(p1)
        assertFalse(d.isEmpty(), "加两只角色后必有子块增量")
        assertTrue((d[VarPoolBlock.CHARA_INT] ?: 0L) > 0L, "角色表整数列在长")
        assertEquals(true, d.keys.all { VarPoolBlock.ORDER.contains(it) }, "差分按键对齐（键取自 VarPoolBlock）")
        assertNull(d[VarPoolBlock.USER_PRIV], "两侧都为 0 的键不进差分表（差分只出非零项）")
        assertEquals(0L, p1.diff(p1).values.sum(), "同一次快照自差分为 0")
    }

    @Test
    fun `增量排行按字节降序`() {
        val d = linkedMapOf("a" to 5L, "b" to 90L, "c" to -3L)
        val top = VarPoolMath.topDeltas(d, 2)
        assertEquals(2, top.size, "按 n 截断")
        assertEquals("b", top[0].first, "首位是最大增量的子块")
        assertEquals("a", top[1].first, "次位按降序")
    }

    @Test
    fun `背衬与哈希表公式按容量而非条数`() {
        assertEquals(SizeModel.align8(16L + 16L * 4L), VarPoolMath.arrayListBackingBytes(16, 4), "背衬算**容量**，不是 size")
        assertEquals(SizeModel.align8(16L + 0L) + 24L, VarPoolMath.hashMapTableBytes(0, 4, 24L), "表长 0 时只剩头 + 壳")
        assertEquals(0L, VarPoolMath.reserveBytes(8, 8, 128L), "容量 == 已用 ⇒ 无预留（判性甲的口径）")
    }
}
