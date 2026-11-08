package com.eratw.port.core.code

import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EventRegistry] 四桶派生判据（M3-1）。
 *
 * 断言面：四桶执行序（ONLY→PRI→NORMAL→LATER，同桶链接序稳定，跨包成员全保留）、
 * 与 [FunctionTable.eventCallOrder]（base-only 装载视图）对同一批元数据的**双路同序**
 * （桶语义单一真源证明）、SINGLE 旗标透传、LSC 脱节负控。
 */
class EventRegistryTest {

    private fun eventMeta(
        index: Int,
        key: String,
        isOnly: Boolean = false,
        isPri: Boolean = false,
        isLater: Boolean = false,
        isSingle: Boolean = false,
        defIndex: Int = index,
    ): FuncMeta = FuncMeta(
        index = index,
        key = key,
        isEvent = true,
        isOnly = isOnly,
        isPri = isPri,
        isLater = isLater,
        isSingle = isSingle,
        defIndex = defIndex,
    )

    private fun candidate(meta: FuncMeta): SymbolCandidate =
        SymbolCandidate(meta = meta, ownerPackageId = "pkg${meta.index % 2}")

    /** 构造带事件的 catalog：candidates 直接给，政策用 LEGACY（事件不参与覆盖语义无关政策）。 */
    private fun catalogOf(metas: List<FuncMeta>): SymbolCatalog =
        SymbolCatalog.build(metas.map { candidate(it) }, ResolverPolicy.LEGACY_LAST_WINS)

    @Test
    fun `四桶执行序与跨包成员全保留`() {
        // 同名事件 6 个成员：链接序 = index 升序；桶位乱序。
        val metas = listOf(
            eventMeta(0, "EVT", isLater = true),
            eventMeta(1, "EVT"),
            eventMeta(2, "EVT", isPri = true),
            eventMeta(3, "EVT", isOnly = true),
            eventMeta(4, "EVT", isLater = true),
            eventMeta(5, "EVT", isPri = true),
        )
        val reg = EventRegistry.of(catalogOf(metas))
        val members = reg.membersOf("EVT")
        // 桶序：ONLY(3) → PRI(2,5) → NORMAL(1) → LATER(0,4)；同桶保持链接序（2 在 5 前、0 在 4 前）。
        assertContentEquals(intArrayOf(3, 2, 5, 1, 0, 4), members.map { it.physicalIndex }.toIntArray())
        // 全部成员保留（一对多基数），归属包透传（candidate helper 的 pkg = index % 2）。
        assertEquals(6, members.size)
        assertEquals("pkg1", members[0].ownerPackageId)
        assertEquals(0, members[0].bucket)
        assertEquals(2, members[3].bucket)
        // 非事件键空表；键大写归一。
        assertTrue(reg.membersOf("NOT_EVENT").isEmpty())
        assertEquals(1, reg.eventKeys.size)
        assertEquals(setOf("EVT"), reg.eventKeys)
        assertFalse(reg.hasSingle("EVT"))
    }

    @Test
    fun `与FunctionTable双路对拍同序`() {
        // 三事件 × 各 3-4 成员，桶位与装载序交叉；base-only 装载视图（FunctionTable）
        // 与链接派生视图（EventRegistry）对同一批元数据必须同序（桶语义单一真源）。
        val metas = mutableListOf<FuncMeta>()
        var idx = 0
        for (key in listOf("EVENTFIRST", "EVENTTURNEND", "EVT3")) {
            val plans = listOf(
                arrayOf(false, false, true), // LATER
                arrayOf(true, false, false), // NORMAL（无桶位）
                arrayOf(false, true, false), // PRI
                arrayOf(true, false, true),  // ONLY
            )
            for (p in plans) {
                metas += eventMeta(idx, key, isOnly = p[0], isPri = p[1], isLater = p[2], defIndex = idx)
                idx++
            }
        }
        val table = FunctionTable(metas)
        val reg = EventRegistry.of(catalogOf(metas))
        for (key in listOf("EVENTFIRST", "EVENTTURNEND", "EVT3", "eventturnend")) {
            val viaTable = table.eventCallOrder(key)!!.map { it.index }
            val viaRegistry = reg.membersOf(key).map { it.physicalIndex }
            assertEquals(viaTable, viaRegistry, "键 $key 双路同序失败")
        }
    }

    @Test
    fun `SINGLE旗标透传`() {
        val metas = listOf(
            eventMeta(0, "EVT", isSingle = true),
            eventMeta(1, "EVT"),
        )
        val reg = EventRegistry.of(catalogOf(metas))
        val members = reg.membersOf("evt")
        assertTrue(members[0].isSingle)
        assertFalse(members[1].isSingle)
        assertTrue(reg.hasSingle("EVT"))
    }

    @Test
    fun `事件不参与覆盖全部成员保留`() {
        // MODERN 政策下事件成员即使「重名」也不冲突（事件不进 bindings、不参与覆盖）。
        val metas = listOf(
            eventMeta(0, "EVT"),
            eventMeta(1, "EVT"),
        )
        val reg = EventRegistry.of(
            SymbolCatalog.build(
                metas.map { SymbolCandidate(meta = it, ownerPackageId = "m") },
                ResolverPolicy.MODERN,
            ),
        )
        assertEquals(2, reg.membersOf("EVT").size)
    }

    // 「LSC 脱节 / 非事件成员污染」的负控不可达：SymbolCatalog 只有 build 一个构建
    // 出口（private constructor，ABI-7 封装），合法输入下 events 与候选集必然一致。
    // EventRegistry.of 里对应的 require 是防未来演化的防御判据，不立用例。
}
