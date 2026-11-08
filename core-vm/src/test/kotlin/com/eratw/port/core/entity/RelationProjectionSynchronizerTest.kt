package com.eratw.port.core.entity

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private typealias WriteBackReport = RelationProjectionSynchronizer.RelationWriteBackReport
private typealias WriteBackReason = RelationProjectionSynchronizer.RelationWriteBackReport.Reason

private val probeSchemas = listOf(
    ComponentSchema("pkg", "Profile", listOf(ComponentFieldSpec("level"))),
)
private val probeArchetypes = listOf(EntityArchetype("PERSON", listOf("Profile")))

/**
 * [RelationProjectionSynchronizer] 的判据（M5-1 关系投影协议，蓝图 §7 Roster 安全不变量第 3 条）。
 *
 * 断言面：进场恢复活动 roster 子矩阵（两方向、无锚行不写）、退场按基线差分回写 Long 度数、
 * 清残留**只清自己的行与列**（原生↔原生的格一格不动）、非 roster 实体的边永不进矩阵、
 * 列键寻址对行排列免疫、多实体同时投射不互相把清残留当脚本改动、
 * 诚实边界（NO_ANCHOR / ENTITY_GONE / goneKeys / lostWriteBacks）与全部 fail-closed 负控。
 *
 * 原生口径锚：RELATION 是 per-character 一维整型数组 + 关键字反查表
 * （VariableData.cs:3087 CharaInt1DVariableToken；ConstantData.cs:743-754 关键字值 = CSV NO），
 * 新行全填 Config.RelationDef（ConfigData.cs:174、CharacterData.cs:163-167），
 * 格值是 Long 度数（语料 ANOTHER_TALK.ERB:102/:186 与 100/200 比大小）。
 */
class RelationProjectionSynchronizerTest {

    private fun newKernel(
        relations: List<RelationshipSchema> = listOf(RelationshipSchema("AFFINITY"), RelationshipSchema("BLOOD")),
    ): EntityKernel = EntityKernel(probeSchemas, relations, probeArchetypes, 8)

    /**
     * 原生矩阵 fake：格子挂在**行身份**上（行排列/删除时整行跟着走，与引擎的
     * per-character 数组同构），物理行号只是当前寻址。`setCell` = 脚本直写（不进 writes 日志），
     * `writeCell` = 本协议写（进日志，用来断言「哪些格被碰过」）。
     */
    private class FakeMatrix(override val relationDefault: Long = 0L) : RelationProjectionBridge {
        class Row(var columnKey: Long, var charIndex: Int, var identity: Long) {
            val cells = HashMap<Long, Long>()
        }

        val rows = ArrayList<Row>()
        val writes = ArrayList<Triple<Int, Long, Long>>()

        /** 尾部追加一行（ADDCHARA/ADDVOIDCHARA 口径），返回物理行号。 */
        fun addRow(columnKey: Long, identity: Long): Int {
            val index = rows.size
            rows += Row(columnKey, index, identity)
            return index
        }

        fun rowAt(charIndex: Int): Row = rows.first { it.charIndex == charIndex }

        fun cell(charIndex: Int, targetKey: Long): Long = readCell(charIndex, targetKey)

        fun setCell(charIndex: Int, targetKey: Long, value: Long) {
            rowAt(charIndex).cells[targetKey] = value
        }

        override fun readCell(charIndex: Int, targetKey: Long): Long =
            rowAt(charIndex).cells[targetKey] ?: relationDefault

        override fun writeCell(charIndex: Int, targetKey: Long, value: Long) {
            writes += Triple(charIndex, targetKey, value)
            rowAt(charIndex).cells[targetKey] = value
        }

        override fun rosterRows(): List<RosterRow> =
            rows.sortedBy { it.charIndex }.map { RosterRow(it.charIndex, it.columnKey) }

        override fun graphIdentityOf(charIndex: Int): Long = rowAt(charIndex).identity

        /** 引擎删行：整行（连同 RELATION 数组）消失，大于它的行号左移 1。 */
        fun deleteRow(charIndex: Int) {
            rows.removeAll { it.charIndex == charIndex }
            for (r in rows) if (r.charIndex > charIndex) r.charIndex--
        }

        /** 引擎行排列：map[oldIndex] = newIndex。 */
        fun permute(oldToNew: IntArray) {
            for (r in rows) r.charIndex = oldToNew[r.charIndex]
        }
    }

    /** 一个有 master/native/无锚原生行/投射实体行的世界（投射行已分配，尚未登记进场）。 */
    private class World(
        relations: List<RelationshipSchema> = listOf(RelationshipSchema("AFFINITY"), RelationshipSchema("BLOOD")),
        val default: Long = 0L,
    ) {
        val k = EntityKernel(probeSchemas, relations, probeArchetypes, 8)
        val m = FakeMatrix(default)
        val master = k.create(1, "PERSON")
        val native = k.create(1, "PERSON")
        val child = k.create(2, "PERSON")
        val masterRow = m.addRow(1L, master)
        val nativeRow = m.addRow(7L, native)
        val unanchoredRow = m.addRow(9L, EntityIdCodec.NULL)
        val childRow = m.addRow(10000L, child)
        val s = RelationProjectionSynchronizer(k, m, "AFFINITY")

        fun project() {
            s.onProjected(child, childRow)
        }

        fun exit(): WriteBackReport = s.onUnprojecting(child, childRow)
    }

    private fun edge(k: IEntityKernel, from: Long, to: Long, value: Long, kind: String = "AFFINITY") {
        assertTrue(k.addRelation(from, kind, to), "建边 $from → $to")
        assertTrue(k.setRelationValue(from, kind, to, value), "设值 $from → $to")
    }

    // ------------------------------------------------------------------
    // 进场
    // ------------------------------------------------------------------

    @Test
    fun `进场恢复活动roster关系子矩阵两个方向`() {
        val w = World()
        edge(w.k, w.child, w.master, 100L)
        edge(w.k, w.master, w.child, 200L)
        edge(w.k, w.child, w.native, 50L)
        w.project()
        assertEquals(100L, w.m.cell(w.childRow, 1L))
        assertEquals(200L, w.m.cell(w.masterRow, 10000L))
        assertEquals(50L, w.m.cell(w.childRow, 7L))
        // 图里 native→child 无边 ⇒ 原生格落默认值（不是「留旧值」）。
        assertEquals(0L, w.m.cell(w.nativeRow, 10000L))
        // 无锚原生行：两个方向一格不写（M5-2 接线缺口不靠猜身份补）。
        assertEquals(0L, w.m.cell(w.childRow, 9L))
        assertEquals(0L, w.m.cell(w.unanchoredRow, 10000L))
        assertTrue(w.m.writes.none { it.first == w.unanchoredRow || it.second == 9L })
        assertEquals(1, w.s.activeProjections)
    }

    @Test
    fun `进场把原生格覆写成图值而不是叠加`() {
        val w = World()
        // 行是复用的：上一任留了残值 999（宿主桥没清干净），图里无边。
        w.m.setCell(w.childRow, 1L, 999L)
        w.m.setCell(w.masterRow, 10000L, 888L)
        w.project()
        assertEquals(0L, w.m.cell(w.childRow, 1L))
        assertEquals(0L, w.m.cell(w.masterRow, 10000L))
        // 图里有边时同样以图为准（不是取原生残值）。
        val w2 = World()
        edge(w2.k, w2.child, w2.master, 100L)
        w2.m.setCell(w2.childRow, 1L, 999L)
        w2.project()
        assertEquals(100L, w2.m.cell(w2.childRow, 1L))
    }

    @Test
    fun `原生默认值非零时进场与回写都按该默认值判无边`() {
        val w = World(default = 50L)
        edge(w.k, w.child, w.master, 100L)
        w.project()
        assertEquals(100L, w.m.cell(w.childRow, 1L))
        assertEquals(50L, w.m.cell(w.nativeRow, 10000L)) // 无边 → 默认 50
        // 脚本把格改回默认 50 ⇒ 图里摘边（不是建一条值 50 的边）。
        w.m.setCell(w.childRow, 1L, 50L)
        val report = w.exit()
        assertEquals(1, report.applied)
        assertFalse(w.k.hasRelation(w.child, "AFFINITY", w.master))
        assertEquals(50L, w.m.cell(w.childRow, 1L))
        assertEquals(50L, w.m.cell(w.masterRow, 10000L))
    }

    // ------------------------------------------------------------------
    // 退场：差分回写 + scoped 清残留
    // ------------------------------------------------------------------

    @Test
    fun `退场差分回写投射期间的原生度数变化`() {
        val w = World()
        edge(w.k, w.child, w.master, 100L)
        edge(w.k, w.master, w.child, 200L)
        w.project()
        // 原版互动函数改格（语料与 100/200 比大小 ⇒ 格值是度数不是布尔）。
        w.m.setCell(w.childRow, 1L, 200L)
        w.m.setCell(w.masterRow, 10000L, 300L)
        val report = w.exit()
        assertEquals(2, report.applied)
        assertTrue(report.isClean)
        assertEquals(200L, w.k.getRelationValue(w.child, "AFFINITY", w.master))
        assertEquals(300L, w.k.getRelationValue(w.master, "AFFINITY", w.child))
        assertEquals(0, w.s.activeProjections)
        assertSame(report, w.s.lastReport)
    }

    @Test
    fun `退场只清自己的行与列不动原生之间的关系`() {
        val w = World()
        // 原生↔原生的既有关系先于投射存在（MASTER 与原生角色的恋慕度等）。
        w.m.setCell(w.masterRow, 7L, 999L)
        w.m.setCell(w.nativeRow, 1L, 888L)
        w.m.setCell(w.masterRow, 9L, 777L)
        w.m.setCell(w.unanchoredRow, 1L, 666L)
        edge(w.k, w.child, w.master, 100L)
        w.project()
        w.m.setCell(w.childRow, 1L, 150L)
        w.exit()
        // 自己的行与列全回默认。
        assertEquals(0L, w.m.cell(w.childRow, 1L))
        assertEquals(0L, w.m.cell(w.masterRow, 10000L))
        assertEquals(0L, w.m.cell(w.childRow, 7L))
        assertEquals(0L, w.m.cell(w.nativeRow, 10000L))
        assertEquals(0L, w.m.cell(w.childRow, 9L))
        assertEquals(0L, w.m.cell(w.unanchoredRow, 10000L))
        // 别人之间的关系一格不动（粗暴全表清零会抹掉这四格）。
        assertEquals(999L, w.m.cell(w.masterRow, 7L))
        assertEquals(888L, w.m.cell(w.nativeRow, 1L))
        assertEquals(777L, w.m.cell(w.masterRow, 9L))
        assertEquals(666L, w.m.cell(w.unanchoredRow, 1L))
        // 清之前的值进了图（回写先于清残留）。
        assertEquals(150L, w.k.getRelationValue(w.child, "AFFINITY", w.master))
    }

    @Test
    fun `自反格不碰也不回写`() {
        val w = World()
        w.project()
        w.m.setCell(w.childRow, 10000L, 42L)
        val report = w.exit()
        assertEquals(42L, w.m.cell(w.childRow, 10000L))
        assertEquals(0, report.applied)
        assertTrue(report.isClean)
        assertFalse(w.k.hasRelation(w.child, "AFFINITY", w.child))
    }

    @Test
    fun `回写默认值摘边非默认值建边`() {
        val w = World()
        edge(w.k, w.child, w.master, 100L)
        w.project()
        w.m.setCell(w.childRow, 1L, 0L)          // 归默认 ⇒ 摘边
        w.m.setCell(w.nativeRow, 10000L, 66L)    // 原本无边 ⇒ 建边并设值
        val report = w.exit()
        assertEquals(2, report.applied)
        assertTrue(report.isClean)
        assertFalse(w.k.hasRelation(w.child, "AFFINITY", w.master))
        assertTrue(w.k.hasRelation(w.native, "AFFINITY", w.child))
        assertEquals(66L, w.k.getRelationValue(w.native, "AFFINITY", w.child))
    }

    @Test
    fun `无改动时退场零回写且报告干净`() {
        val w = World()
        edge(w.k, w.child, w.master, 100L)
        w.project()
        val report = w.exit()
        assertEquals(0, report.applied)
        assertTrue(report.isClean)
        assertEquals(100L, w.k.getRelationValue(w.child, "AFFINITY", w.master))
        assertTrue(w.s.unwrittenChanges.isEmpty())
        assertEquals(0, w.s.goneKeyCount)
    }

    @Test
    fun `非roster实体的关系一格不碰且跨退场存活`() {
        val w = World()
        val outsider = w.k.create(3, "PERSON")
        edge(w.k, w.child, outsider, 77L)
        edge(w.k, outsider, w.child, 88L, kind = "BLOOD") // 另一种 kind 更不该被碰
        w.project()
        w.m.setCell(w.childRow, 1L, 123L)
        w.exit()
        assertEquals(77L, w.k.getRelationValue(w.child, "AFFINITY", outsider))
        assertEquals(88L, w.k.getRelationValue(outsider, "BLOOD", w.child))
        assertTrue(w.k.hasRelation(w.child, "AFFINITY", outsider))
        // outsider 没有 roster 行 ⇒ 结构上不可能被 addressing；写过的列键只有 roster 那四个。
        assertTrue(w.m.writes.all { it.second in setOf(1L, 7L, 9L, 10000L) })
        assertTrue(w.m.rosterRows().none { it.columnKey == 3L })
    }

    @Test
    fun `投射期间新加入roster的角色按默认基线差分`() {
        val w = World()
        w.project()
        val late = w.k.create(1, "PERSON")
        val lateRow = w.m.addRow(55L, late)
        w.m.setCell(lateRow, 10000L, 123L)
        w.m.setCell(w.childRow, 55L, 45L)
        val report = w.exit()
        assertEquals(2, report.applied)
        assertTrue(report.isClean)
        assertEquals(123L, w.k.getRelationValue(late, "AFFINITY", w.child))
        assertEquals(45L, w.k.getRelationValue(w.child, "AFFINITY", late))
        assertEquals(0L, w.m.cell(lateRow, 10000L))
        assertEquals(0L, w.m.cell(w.childRow, 55L))
    }

    // ------------------------------------------------------------------
    // 行排列 / 多实体同时投射
    // ------------------------------------------------------------------

    @Test
    fun `行排列后按列键寻址不需同步器重映射`() {
        val w = World()
        edge(w.k, w.child, w.master, 100L)
        w.m.setCell(w.masterRow, 7L, 999L)
        w.project()
        // SORTCHARA：master(0) 与 child(3) 互换。整行跟着走 ⇒ 格值不丢，列键不变。
        w.m.permute(intArrayOf(3, 1, 2, 0))
        val newChildRow = 0
        val newMasterRow = 3
        assertEquals(100L, w.m.cell(newChildRow, 1L))
        w.m.setCell(newChildRow, 1L, 200L)
        // 同步器一个钩子都没调（排列只归 ICharaSlotProjector 管投射映射）。
        val report = w.s.onUnprojecting(w.child, newChildRow)
        assertEquals(1, report.applied)
        assertTrue(report.isClean)
        assertEquals(200L, w.k.getRelationValue(w.child, "AFFINITY", w.master))
        // 清残留按**当前**行号落地。
        assertEquals(0L, w.m.cell(newMasterRow, 10000L))
        assertEquals(0L, w.m.cell(newChildRow, 1L))
        assertEquals(0L, w.m.cell(newChildRow, 7L))
        // 原生↔原生的格跟着行排列走，且不被清残留波及。
        assertEquals(999L, w.m.cell(newMasterRow, 7L))
    }

    @Test
    fun `多实体同时投射退场不把自己清的残留当别人的脚本改动`() {
        val w = World()
        val child2 = w.k.create(2, "PERSON")
        val child2Row = w.m.addRow(10001L, child2)
        edge(w.k, w.child, child2, 10L)
        edge(w.k, child2, w.child, 20L)
        edge(w.k, child2, w.master, 30L)
        w.project()
        w.s.onProjected(child2, child2Row)
        assertEquals(10L, w.m.cell(w.childRow, 10001L))
        assertEquals(20L, w.m.cell(child2Row, 10000L))
        assertEquals(30L, w.m.cell(child2Row, 1L))
        assertEquals(2, w.s.activeProjections)
        // child 退场：脚本只改了 child→child2 这一格。
        w.m.setCell(w.childRow, 10001L, 40L)
        val first = w.exit()
        assertEquals(1, first.applied)
        assertTrue(first.isClean)
        assertEquals(40L, w.k.getRelationValue(w.child, "AFFINITY", child2))
        // child 的列被清成默认，但 child2 的基线里该列键已摘 ⇒ 不会被当成改动。
        assertEquals(0L, w.m.cell(child2Row, 10000L))
        assertEquals(1, w.s.activeProjections)
        w.m.deleteRow(w.childRow) // 宿主删行（child2 行号 4→3）
        val second = w.s.onUnprojecting(child2, child2Row - 1)
        assertEquals(0, second.applied)
        assertTrue(second.isClean, "清残留不得变成 child2 的回写：${second.unwritten} / ${second.goneKeys}")
        assertTrue(second.goneKeys.isEmpty())
        // 图里 child2→child 的 20 与 child2→master 的 30 都活着。
        assertEquals(20L, w.k.getRelationValue(child2, "AFFINITY", w.child))
        assertEquals(30L, w.k.getRelationValue(child2, "AFFINITY", w.master))
        assertEquals(0, w.s.activeProjections)
    }

    @Test
    fun `后进场实体的投影不被先在册基线当成脚本改动`() {
        val w = World()
        edge(w.k, w.child, w.master, 100L)
        w.project()
        // child2 进场：它把 child↔child2 两格按图写成默认（图里无边）。
        val child2 = w.k.create(2, "PERSON")
        val child2Row = w.m.addRow(10001L, child2)
        w.s.onProjected(child2, child2Row)
        assertEquals(0L, w.m.cell(w.childRow, 10001L))
        // child 退场：那两格与 child 的基线（进场时 child2 还不存在）无关，按当前值差分 = 无变化。
        val report = w.exit()
        assertEquals(0, report.applied)
        assertTrue(report.isClean)
        assertFalse(w.k.hasRelation(w.child, "AFFINITY", child2))
        assertEquals(100L, w.k.getRelationValue(w.child, "AFFINITY", w.master))
    }

    // ------------------------------------------------------------------
    // 诚实边界
    // ------------------------------------------------------------------

    @Test
    fun `无锚行的改动进诊断不静默丢两个方向都记`() {
        val w = World()
        w.project()
        w.m.setCell(w.childRow, 9L, 150L)
        w.m.setCell(w.unanchoredRow, 10000L, 160L)
        val report = w.exit()
        assertEquals(0, report.applied)
        assertFalse(report.isClean)
        assertEquals(2, report.unwritten.size)
        val out = report.unwritten.first { it.fromKey == 10000L }
        assertEquals(WriteBackReason.NO_ANCHOR, out.reason)
        assertEquals(9L, out.toKey)
        assertEquals(0L, out.oldValue)
        assertEquals(150L, out.newValue)
        val inc = report.unwritten.first { it.toKey == 10000L }
        assertEquals(WriteBackReason.NO_ANCHOR, inc.reason)
        assertEquals(9L, inc.fromKey)
        assertEquals(160L, inc.newValue)
        // 累计诊断面留痕（宿主据此上屏/上报）。
        assertEquals(2, w.s.unwrittenChanges.size)
        assertFalse(w.k.hasRelation(w.child, "AFFINITY", w.native))
    }

    @Test
    fun `对端实体在投射期间被销毁记ENTITY_GONE`() {
        val w = World()
        w.project()
        assertTrue(w.k.destroy(w.native))
        w.m.setCell(w.childRow, 7L, 123L)
        val report = w.exit()
        assertEquals(0, report.applied)
        assertEquals(1, report.unwritten.size)
        assertEquals(WriteBackReason.ENTITY_GONE, report.unwritten.single().reason)
        assertEquals(123L, report.unwritten.single().newValue)
        assertFalse(report.isClean)
    }

    @Test
    fun `基线列键随行消失记goneKeys`() {
        val w = World()
        w.project()
        w.m.deleteRow(w.nativeRow) // 原版脚本自己 DELCHARA：行连同 RELATION 数组消失
        val report = w.s.onUnprojecting(w.child, w.childRow - 1)
        assertContentEquals(listOf(7L), report.goneKeys)
        assertFalse(report.isClean)
        assertEquals(1, w.s.goneKeyCount)
        assertEquals(0, report.applied)
    }

    @Test
    fun `引擎直接删投射行走forget损失可见且不重复报`() {
        val w = World()
        val child2 = w.k.create(2, "PERSON")
        val child2Row = w.m.addRow(10001L, child2)
        w.project()
        w.s.onProjected(child2, child2Row)
        assertTrue(w.s.forget(w.child))
        assertEquals(1, w.s.lostWriteBacks)
        assertEquals(1, w.s.activeProjections)
        assertFalse(w.s.forget(w.child))
        assertEquals(1, w.s.lostWriteBacks)
        // 已 forget 的实体再走退场 = 无基线，拒绝。
        val ex = assertFailsWith<IllegalArgumentException> { w.s.onUnprojecting(w.child, w.childRow) }
        assertTrue("无基线" in ex.message!!, ex.message)
        // child2 的基线里 child 的列键已摘 ⇒ 不重复报 goneKeys（损失只记一次）。
        w.m.deleteRow(w.childRow)
        val report = w.s.onUnprojecting(child2, child2Row - 1)
        assertTrue(report.isClean)
        assertContentEquals(emptyList(), report.goneKeys)
        assertEquals(0, w.s.goneKeyCount)
    }

    // ------------------------------------------------------------------
    // fail-closed 负控
    // ------------------------------------------------------------------

    @Test
    fun `构造期按内核冻结声明拒绝未注册与空名`() {
        val w = World()
        val ex = assertFailsWith<IllegalArgumentException> {
            RelationProjectionSynchronizer(w.k, w.m, "GHOST")
        }
        assertTrue("未注册" in ex.message!!, ex.message)
        assertFailsWith<IllegalArgumentException> { RelationProjectionSynchronizer(w.k, w.m, " ") }
        // 键大小写归一后仍读得到声明。
        RelationProjectionSynchronizer(w.k, w.m, "affinity")
    }

    @Test
    fun `构造期拒绝无向与autoInverse的投影kind`() {
        val undirected = newKernel(listOf(RelationshipSchema("AFFINITY", directed = false)))
        val ex = assertFailsWith<IllegalArgumentException> {
            RelationProjectionSynchronizer(undirected, FakeMatrix(), "AFFINITY")
        }
        assertTrue("无向" in ex.message!!, ex.message)
        val autoInverse = newKernel(
            listOf(RelationshipSchema("AFFINITY", autoInverse = true, inverseName = "AFFINITY_REV")),
        )
        val ex2 = assertFailsWith<IllegalArgumentException> {
            RelationProjectionSynchronizer(autoInverse, FakeMatrix(), "AFFINITY")
        }
        assertTrue("autoInverse" in ex2.message!!, ex2.message)
        assertTrue("AFFINITY_REV" in ex2.message!!, ex2.message)
    }

    @Test
    fun `进场负控重复登记与行不在roster与非良构身份`() {
        val w = World()
        w.project()
        val dup = assertFailsWith<IllegalArgumentException> { w.s.onProjected(w.child, w.childRow) }
        assertTrue("重复进场" in dup.message!!, dup.message)
        assertEquals(1, w.s.activeProjections)

        val absent = assertFailsWith<IllegalArgumentException> { w.s.onProjected(w.native, 99) }
        assertTrue("不在 roster" in absent.message!!, absent.message)
        val negative = assertFailsWith<IllegalArgumentException> { w.s.onProjected(w.native, -1) }
        assertTrue("为负" in negative.message!!, negative.message)
        val malformed = assertFailsWith<IllegalArgumentException> { w.s.onProjected(0L, w.nativeRow) }
        assertTrue("非良构" in malformed.message!!, malformed.message)
        assertEquals(1, w.s.activeProjections)
    }

    @Test
    fun `进场遇非良构图身份一格不写`() {
        val w = World()
        // pkg 段为 0 的非 NULL 值 = 非良构（EntityIdCodec.isWellFormed）。
        w.m.rowAt(w.nativeRow).identity = 0x0000_0001_0000_0009L
        val before = w.m.writes.size
        val ex = assertFailsWith<IllegalArgumentException> { w.project() }
        assertTrue("图身份非良构" in ex.message!!, ex.message)
        assertEquals(before, w.m.writes.size)
        assertEquals(0, w.s.activeProjections)
    }

    @Test
    fun `roster行表损坏fail-closed列键重复与行号重复与负行号`() {
        val dupKey = World()
        dupKey.m.rows += FakeMatrix.Row(1L, 4, EntityIdCodec.NULL) // 与 master 同列键
        val ex = assertFailsWith<IllegalArgumentException> { dupKey.project() }
        assertTrue("列键重复" in ex.message!!, ex.message)
        assertTrue(dupKey.m.writes.isEmpty())

        val dupRow = World()
        dupRow.m.rows += FakeMatrix.Row(77L, dupRow.masterRow, EntityIdCodec.NULL)
        val ex2 = assertFailsWith<IllegalArgumentException> { dupRow.project() }
        assertTrue("行号重复" in ex2.message!!, ex2.message)

        val negRow = World()
        negRow.m.rows += FakeMatrix.Row(78L, -3, EntityIdCodec.NULL)
        val ex3 = assertFailsWith<IllegalArgumentException> { negRow.project() }
        assertTrue("行号为负" in ex3.message!!, ex3.message)
    }

    @Test
    fun `退场负控无基线与删行早于回写与列键漂移`() {
        val noBaseline = World()
        val ex = assertFailsWith<IllegalArgumentException> { noBaseline.exit() }
        assertTrue("无基线" in ex.message!!, ex.message)

        val deleted = World()
        deleted.project()
        deleted.m.deleteRow(deleted.childRow)
        val writesBefore = deleted.m.writes.size
        val ex2 = assertFailsWith<IllegalArgumentException> { deleted.exit() }
        assertTrue("不在 roster" in ex2.message!!, ex2.message)
        // 预校验先于摘基线：拒绝时状态原样留着。
        assertEquals(1, deleted.s.activeProjections)
        assertEquals(writesBefore, deleted.m.writes.size)

        val drifted = World()
        drifted.project()
        drifted.m.rowAt(drifted.childRow).columnKey = 10001L
        val writesBefore3 = drifted.m.writes.size
        val ex3 = assertFailsWith<IllegalArgumentException> { drifted.exit() }
        assertTrue("列键漂移" in ex3.message!!, ex3.message)
        assertEquals(1, drifted.s.activeProjections)
        assertEquals(writesBefore3, drifted.m.writes.size)
    }
}