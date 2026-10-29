package com.eratw.port.core.proc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M13-MEM5 刀 2（[FuncMeta] 表示形态 + [FunctionTable] 查询表形态）的行为闸。
 *
 * 三条既有语义各钉死一条（与 ProcessRuntimeTest / EngineFunctionStubTest /
 * FuncKeyInternTest 的表级闸互补，这里只打 [FunctionTable] 门面）：
 * 1. 事件分桶 ONLY→PRI→NORMAL→LATER 与同桶装载序；
 * 2. 非事件函数**后装载者生效**；
 * 3. 名字大小写归一（IGNORE CASE）。
 *
 * 其余用例钉表示层不变量：position 压实**逐字节无损**（含不可压实的原文必须原样）、
 * equals/hashCode/toString/copy 与 `data class` 时代值语义相同（侧车往返对账与
 * SymbolLinkAudit「首差」文案都在消费这三件套）、标志位位域读值不变。
 */
class FuncMetaShapeGateTest {

    private fun meta(
        index: Int,
        key: String,
        position: String = "",
        defIndex: Int = index,
        isEvent: Boolean = false,
        isOnly: Boolean = false,
        isPri: Boolean = false,
        isLater: Boolean = false,
        isSingle: Boolean = false,
        isMethod: Boolean = false,
        returnsStr: Boolean = false,
    ) = FuncMeta(
        index = index,
        key = key,
        position = position,
        defIndex = defIndex,
        isEvent = isEvent,
        isOnly = isOnly,
        isPri = isPri,
        isLater = isLater,
        isSingle = isSingle,
        isMethod = isMethod,
        returnsStr = returnsStr,
    )

    // ---- 三条既有语义 ----

    @Test
    fun `事件分桶 ONLY-PRI-NORMAL-LATER 且同桶保装载序`() {
        val normal2 = meta(2, "EV", isEvent = true, defIndex = 2)
        val normal1 = meta(1, "EV", isEvent = true, defIndex = 1)
        val pri = meta(3, "EV", isEvent = true, isPri = true, defIndex = 3)
        val later = meta(4, "EV", isEvent = true, isLater = true, defIndex = 4)
        val only = meta(5, "EV", isEvent = true, isOnly = true, defIndex = 5)
        val table = FunctionTable(listOf(normal2, normal1, later, only, pri))
        // ONLY=0 → PRI=1 → NORMAL=2 → LATER=3；NORMAL 桶内按 defIndex 升序（装载序）。
        assertEquals(
            listOf(only, pri, normal1, normal2, later),
            table.eventCallOrder("EV"),
            "桶序 + 同桶装载序必须与改造前一致",
        )
        assertEquals(FunctionTable.TargetKind.EVENT, table.classify("ev"), "事件名 IGNORE CASE 分类")
    }

    @Test
    fun `非事件重名后装载者生效`() {
        val first = meta(1, "DUP", defIndex = 1)
        val second = meta(2, "DUP", defIndex = 2)
        val table = FunctionTable(listOf(first, second))
        assertSame(second, table.nonEvent("DUP"), "字典直接赋值语义：最后装载者留下（LabelDictionary.cs:60-68）")
        // 输入乱序也按 defIndex 定胜者（metas 先按 defIndex 稳定排序）。
        val table2 = FunctionTable(listOf(second, first))
        assertSame(second, table2.nonEvent("dup"))
    }

    @Test
    fun `名字解析按大写归一`() {
        val table = FunctionTable(listOf(meta(7, "SOMEFUNC", defIndex = 7)))
        assertSame(table.nonEvent("SOMEFUNC"), table.nonEvent("somefunc"))
        assertEquals(7, table.nonEvent("SoMeFuNc")?.index)
        assertTrue(table.contains("somefunc"))
        assertEquals(FunctionTable.TargetKind.NON_EVENT, table.classify("somefunc"))
        assertEquals(FunctionTable.TargetKind.NOT_FOUND, table.classify("nada"))
    }

    // ---- position 压实：无损面 ----

    @Test
    fun `position 压实逐字节无损且只压可反解析的原文`() {
        val packable = meta(1, "A", position = "ERB/ITEMS/ITEM_NO.ERB:1245")
        val leadingZero = meta(2, "B", position = "ERB/A.ERB:042")
        val noColon = meta(3, "C", position = "sigdefault.erb")
        val empty = meta(4, "D", position = "")
        val marker = meta(5, "E", position = "·event-entry·")
        val hugeLine = meta(6, "F", position = "ERB/A.ERB:99999999999")
        val table = FunctionTable(listOf(packable, leadingZero, noColon, empty, marker, hugeLine))
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), table.all().map { it.key })
        // 逐条读值与原文逐字节相同。
        assertEquals("ERB/ITEMS/ITEM_NO.ERB:1245", packable.position)
        assertEquals("ERB/A.ERB:042", leadingZero.position, "有前置零：重建会变短，必须不压")
        assertEquals("sigdefault.erb", noColon.position)
        assertEquals("", empty.position)
        assertEquals("·event-entry·", marker.position, "无冒号标记串保持原文（sourceFileOf 判前缀不变）")
        assertEquals("ERB/A.ERB:99999999999", hugeLine.position, "行号越 Int 域：原样保留")
        // 可压实的条目：压实确实发生（否则本用例只是空转）——经查询面反证 sourceFileOf 仍对。
        assertEquals("ERB/ITEMS/ITEM_NO.ERB", table.sourceFileOf("A"))
    }

    @Test
    fun `压实不影响等值与 sourceFileOf`() {
        val raw = meta(1, "FN", position = "ERB/MAIN.ERB:9")
        val twin = meta(1, "FN", position = "ERB/MAIN.ERB:9")
        val table = FunctionTable(listOf(raw))
        assertEquals(twin, raw, "压实前后 equals 值语义不变（SymbolLinkAudit a==b 对账口径）")
        assertEquals(twin.hashCode(), raw.hashCode())
        assertEquals("ERB/MAIN.ERB", table.sourceFileOf("FN"), "FN-05 反查源文件读值不变")
    }

    @Test
    fun `toString 与 copy 保持 data class 值语义`() {
        val m = meta(3, "K", position = "ERB/A.ERB:7")
        FunctionTable(listOf(m))
        assertEquals(
            "FuncMeta(index=3, key=K, name=K, isEvent=false, isMethod=false, isOnly=false, " +
                "isPri=false, isLater=false, isSingle=false, argIntCount=0, argStrCount=0, " +
                "localSize=0, localsSize=0, returnsStr=false, defIndex=3, position=ERB/A.ERB:7)",
            m.toString(),
            "装载侧首差文案按 data class 渲染格式对账（SymbolLinkAudit.firstFunctionDiff）",
        )
        val linked = m.copy(index = 42)
        assertEquals(42, linked.index, "copy(index=) 定稿通道保留")
        assertEquals(m.key, linked.key)
        assertEquals(m.position, linked.position)
    }

    @Test
    fun `标志位位域读值与逐位语义不变`() {
        val flags = listOf(
            meta(1, "A", isEvent = true, isMethod = true, isOnly = true, isPri = true, isLater = true, isSingle = true, returnsStr = true),
            meta(2, "B", isMethod = true),
            meta(3, "C", isEvent = true, isSingle = true),
            meta(4, "D", returnsStr = true),
        )
        FunctionTable(flags)
        val a = flags[0]
        assertTrue(a.isEvent && a.isMethod && a.isOnly && a.isPri && a.isLater && a.isSingle && a.returnsStr)
        assertEquals(0, a.eventBucket, "ONLY 优先（LabelDictionary.cs:90-105 排他桶）")
        assertEquals(2, flags[1].eventBucket, "非事件不参与桶")
        assertEquals(2, flags[2].eventBucket, "isSingle 不改桶位（SINGLE 只影响派发跳过）")
        assertEquals(2, flags[3].eventBucket)
        assertEquals(FunctionTable.TargetKind.NON_EVENT, FunctionTable(flags).classify("d"))
    }

    @Test
    fun `两次建表幂等 已压实的不再重压`() {
        val m = meta(1, "P", position = "ERB/A.ERB:42")
        FunctionTable(listOf(m))
        val first = m.position
        FunctionTable(listOf(m))
        assertEquals(first, m.position, "重复建表读值稳定")
        assertEquals(first, m.copy().position, "copy 出的新实例走原文面，值同")
    }

    @Test
    fun `byFunctionIndex 与下标面不受形态影响`() {
        val sparse = meta(10_000, "EVENTFIRST", isEvent = true, defIndex = 1)
        val table = FunctionTable(listOf(meta(0, "MAIN"), sparse))
        assertEquals(0, table.byFunctionIndex(0)?.index)
        assertEquals("EVENTFIRST", table.byFunctionIndex(10_000)?.key)
        assertEquals(null, table.byFunctionIndex(5))
        assertEquals(null, table.byFunctionIndex(-1))
        assertEquals(null, table.byFunctionIndex(10_001))
    }
}
