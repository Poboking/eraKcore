package com.eratw.port.core.entity

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RosterPinner] 投射映射生命周期判据（M5-1）。
 *
 * 断言面：project 幂等与失败路（实体不存在 / 行满）、unproject 的**脱水先于删行**
 * 时序与其余映射左移、读档前全量脱水、行排列/删除钩子的 fail-closed 判据、
 * 关系投影协议接线（进场自动登记、退场三步时序 = 脱水 → 回写清残留 → 删行、
 * 引擎直接删行走 forget 且损失计数）。
 */
class RosterPinnerTest {

    /** fake 桥：记录调用序 + 简单行表（尾部追加、删除左移语义由宿主实现，这里只记账）。 */
    private class FakeBridge : RosterBridge {
        val calls = ArrayList<String>()
        val rows = HashMap<Int, String>() // charIndex → 实体标记（注水后的行内容记账）
        var nextRow = 0
        var full = false

        override fun allocateRow(): Int = if (full) -1 else nextRow.also { nextRow++ }
        override fun releaseRow(charIndex: Int) {
            calls += "release:$charIndex"
            rows.remove(charIndex)
        }
        override fun pourFromEntity(entityId: Long, charIndex: Int) {
            calls += "pour:$entityId->$charIndex"
            rows[charIndex] = "E$entityId"
        }
        override fun captureToEntity(entityId: Long, charIndex: Int) {
            calls += "capture:$entityId<-$charIndex"
        }
    }

    private fun kernel(): EntityKernel = EntityKernel(
        listOf(ComponentSchema("pkg", "Profile", listOf(ComponentFieldSpec("level")))),
        listOf(RelationshipSchema("PARENT")),
        listOf(EntityArchetype("PERSON", listOf("Profile"))),
        8,
    )

    @Test
    fun `project幂等与失败路`() {
        val k = kernel()
        val bridge = FakeBridge()
        val p = RosterPinner(k, bridge)
        val e = k.create(1, "PERSON")
        assertEquals(0, p.project(e))
        assertEquals(0, p.project(e)) // 幂等
        assertEquals(1, p.projectedCount)
        // 不存在的实体 -1。
        assertEquals(-1, p.project(0x00FF_0000_0009L))
        // 行满 -1 且不登记。
        bridge.full = true
        val e2 = k.create(1, "PERSON")
        assertEquals(-1, p.project(e2))
        assertEquals(-1, p.charIndexOf(e2))
    }

    @Test
    fun `unproject先脱水后删行且其余映射左移`() {
        val k = kernel()
        val bridge = FakeBridge()
        val p = RosterPinner(k, bridge)
        val e1 = k.create(1, "PERSON")
        val e2 = k.create(1, "PERSON")
        val e3 = k.create(1, "PERSON")
        val r1 = p.project(e1)
        val r2 = p.project(e2)
        p.project(e3)
        bridge.calls.clear()
        assertTrue(p.unproject(e2))
        // 时序：capture 先于 release，同一行。
        assertEquals(listOf("capture:$e2<-$r2", "release:$r2"), bridge.calls)
        // 其余映射左移（引擎行删除语义）：e3 旧行 2 → 左移到 r2；e1 不动。
        assertEquals(r1, p.charIndexOf(e1))
        assertEquals(r2, p.charIndexOf(e3))
        assertEquals(e1, p.entityAt(r1))
        assertEquals(e3, p.entityAt(r2))
        // 未投射实体 unproject false。
        assertFalse(p.unproject(e2))
    }

    @Test
    fun `读档前全量脱水`() {
        val k = kernel()
        val bridge = FakeBridge()
        val p = RosterPinner(k, bridge)
        val e1 = k.create(1, "PERSON")
        val e2 = k.create(1, "PERSON")
        p.project(e1)
        p.project(e2)
        bridge.calls.clear()
        p.syncFromCharaSlot(e1)
        p.syncFromCharaSlot(e2)
        assertEquals(listOf("capture:$e1<-0", "capture:$e2<-1"), bridge.calls)
    }

    @Test
    fun `投射枚举按EntityId升序且是快照拷贝`() {
        val k = kernel()
        val bridge = FakeBridge()
        val p = RosterPinner(k, bridge)
        val e1 = k.create(1, "PERSON")
        val e2 = k.create(1, "PERSON")
        val e3 = k.create(1, "PERSON")
        assertEquals(0, p.projectedEntities().size)
        // 故意乱序投射：枚举口径是 EntityId 升序（与 ENTITY_QUERY 同口径），不是投射序/行序。
        p.project(e3)
        p.project(e1)
        p.project(e2)
        val held = p.projectedEntities()
        assertContentEquals(longArrayOf(e1, e2, e3), held)
        held[0] = 0L // 调用方改写自己那份拷贝，不得污染内部映射
        assertContentEquals(longArrayOf(e1, e2, e3), p.projectedEntities())
        assertTrue(p.unproject(e2))
        assertContentEquals(longArrayOf(e1, e3), p.projectedEntities())
    }

    @Test
    fun `行排列钩子全表重写`() {
        val k = kernel()
        val bridge = FakeBridge()
        val p = RosterPinner(k, bridge)
        val e1 = k.create(1, "PERSON")
        val e2 = k.create(1, "PERSON")
        p.project(e1) // 行 0
        p.project(e2) // 行 1
        // 排列：旧 1 → 新 0、旧 0 → 新 1（交换）。
        p.onCharacterRowsPermuted(intArrayOf(1, 0))
        assertEquals(1, p.charIndexOf(e1))
        assertEquals(0, p.charIndexOf(e2))
        // 把已投射实体映到 -1 = fail-closed（先 unproject 再排列）。
        val ex = assertFailsWith<IllegalArgumentException> {
            p.onCharacterRowsPermuted(intArrayOf(-1, 0))
        }
        assertTrue("fail-closed" in ex.message!!, ex.message)
    }

    @Test
    fun `行删除钩子`() {
        val k = kernel()
        val bridge = FakeBridge()
        val p = RosterPinner(k, bridge)
        val e1 = k.create(1, "PERSON")
        val e2 = k.create(1, "PERSON")
        val e3 = k.create(1, "PERSON")
        p.project(e1) // 0
        p.project(e2) // 1
        p.project(e3) // 2
        // 引擎直接删了行 1（e2 所在——宿主侧已处理数据，钩子同步映射）。
        p.onCharacterRowDeleted(1)
        assertEquals(-1, p.charIndexOf(e2))
        assertEquals(0, p.charIndexOf(e1))
        assertEquals(1, p.charIndexOf(e3)) // 左移
        assertEquals(e3, p.entityAt(1))
    }

    // ------------------------------------------------------------------
    // 关系投影协议接线（协议由本类强制，不靠宿主「记得调」）
    // ------------------------------------------------------------------

    /** 合体 fake：角色行桥 + 关系矩阵桥，调用序记进同一份 calls（三步时序判据靠它）。 */
    private class RosterAndMatrix : RosterBridge, RelationProjectionBridge {
        class Row(val columnKey: Long, var charIndex: Int, var identity: Long) {
            val cells = HashMap<Long, Long>()
        }

        val calls = ArrayList<String>()
        val rows = ArrayList<Row>()
        override val relationDefault: Long = 0L
        private var nextKey = 10000L

        /** 原生角色行（先于任何投射存在，引擎里本来就有）。 */
        fun seedNative(columnKey: Long, identity: Long): Int {
            val index = rows.size
            rows += Row(columnKey, index, identity)
            return index
        }

        fun rowAt(charIndex: Int): Row = rows.first { it.charIndex == charIndex }

        /** 原版脚本直写格（不进 calls）。 */
        fun scriptSet(charIndex: Int, targetKey: Long, value: Long) {
            rowAt(charIndex).cells[targetKey] = value
        }

        override fun allocateRow(): Int {
            val index = rows.size
            rows += Row(nextKey++, index, EntityIdCodec.NULL)
            calls += "alloc:$index"
            return index
        }

        override fun pourFromEntity(entityId: Long, charIndex: Int) {
            rowAt(charIndex).identity = entityId
            calls += "pour:$entityId->$charIndex"
        }

        override fun captureToEntity(entityId: Long, charIndex: Int) {
            calls += "capture:$entityId<-$charIndex"
            // 脱水必须看到**未清残留**的原始行快照（清残留晚于这一步）。
            calls += "captureCells:" + rowAt(charIndex).cells.entries.sortedBy { it.key }
                .joinToString(";") { "${it.key}=${it.value}" }
        }

        override fun releaseRow(charIndex: Int) {
            calls += "release:$charIndex"
            rows.removeAll { it.charIndex == charIndex }
            for (r in rows) if (r.charIndex > charIndex) r.charIndex--
        }

        override fun readCell(charIndex: Int, targetKey: Long): Long {
            calls += "read:$charIndex,$targetKey"
            return rowAt(charIndex).cells[targetKey] ?: relationDefault
        }

        override fun writeCell(charIndex: Int, targetKey: Long, value: Long) {
            calls += "write:$charIndex,$targetKey=$value"
            rowAt(charIndex).cells[targetKey] = value
        }

        override fun rosterRows(): List<RosterRow> =
            rows.sortedBy { it.charIndex }.map { RosterRow(it.charIndex, it.columnKey) }

        override fun graphIdentityOf(charIndex: Int): Long = rowAt(charIndex).identity
    }

    @Test
    fun `退场三步时序脱水先于关系回写先于删行`() {
        val k = kernel()
        val host = RosterAndMatrix()
        val anchor = k.create(1, "PERSON")
        val anchorRow = host.seedNative(1L, anchor)
        val sync = RelationProjectionSynchronizer(k, host, "PARENT")
        val p = RosterPinner(k, host, sync)
        val child = k.create(1, "PERSON")
        assertTrue(k.addRelation(child, "PARENT", anchor))
        assertTrue(k.setRelationValue(child, "PARENT", anchor, 100L))
        host.calls.clear()
        val row = p.project(child)
        assertEquals(anchorRow + 1, row)
        // 进场登记自动发生（宿主一个同步器方法都没调）。
        assertEquals(1, sync.activeProjections)
        assertEquals(100L, host.rowAt(row).cells[1L])
        // 投射期间原版互动函数改格。
        host.scriptSet(row, 1L, 200L)
        host.calls.clear()
        assertTrue(p.unproject(child))
        val steps = host.calls.filter { !it.startsWith("read:") }
        assertEquals("capture:$child<-$row", steps.first())
        assertTrue(steps[1].startsWith("captureCells:1=200"), steps[1])
        val clearColumn = "write:$anchorRow,10000=0"
        assertTrue(clearColumn in steps, steps.toString())
        assertTrue(steps.indexOf(clearColumn) < steps.indexOf("release:$row"), steps.toString())
        assertEquals("release:$row", steps.last())
        // 清残留之前的值差分进了图；报告干净。
        assertEquals(200L, k.getRelationValue(child, "PARENT", anchor))
        assertEquals(0, sync.activeProjections)
        assertTrue(sync.lastReport!!.isClean)
        assertEquals(1, sync.lastReport!!.applied)
        assertEquals(-1, p.charIndexOf(child))
    }

    @Test
    fun `引擎直接删投射行时基线被丢弃且损失可见`() {
        val k = kernel()
        val host = RosterAndMatrix()
        val sync = RelationProjectionSynchronizer(k, host, "PARENT")
        val p = RosterPinner(k, host, sync)
        val e = k.create(1, "PERSON")
        val row = p.project(e)
        assertEquals(1, sync.activeProjections)
        p.onCharacterRowDeleted(row)
        assertEquals(0, sync.activeProjections)
        assertEquals(1, sync.lostWriteBacks)
        assertEquals(-1, p.charIndexOf(e))
        // 删行钩子不删行也不脱水（宿主侧已删；格子随行消失，回写不可达）。
        assertTrue(host.calls.none { it.startsWith("release:") || it.startsWith("capture:") })
    }
}
