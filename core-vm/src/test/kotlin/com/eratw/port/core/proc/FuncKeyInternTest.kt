package com.eratw.port.core.proc

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.vars.DimSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * 侧车装载期的函数键与路径串归一（同一实例）。
 *
 * 判据是**身份**而不是内容：`labels` 的外层键、`fileByKey` 的键、`FuncMeta.key`
 * 在 [BootMetadata.parse] 之后必须是同一个 `String` 实例；`fileByKey` 的值（源文件路径）
 * 在同一个文件下也必须是同一个实例。内容层面的等价性由本文件的查表断言与既有的
 * 侧车往返用例共同保证。
 *
 * 归一只发生在解析期的一张临时池里，池在 `parse` 返回前就丢弃，对外可见的仍是同一套
 * `Map` / `List` 结构：[BootMetadata.encodeText] 的输出逐字节不变，`.erbc` 容器不受影响，
 * 因此这不是「新建一张索引表」。
 *
 * 语料侧的实测口径（同一台装置、同一份全量侧车）见 `PoolDecompAuditTest` 的
 * `perf-decomp.md`：`FunctionTable` / `labelPcs` / `fileBySigKey` 三行由 95.7 MiB 降到 58.2 MiB。
 */
class FuncKeyInternTest {

    // ------------------------------------------------------------------
    // 三处同一实例
    // ------------------------------------------------------------------

    @Test
    fun `同一函数名在标签表文件表函数表三处是同一实例`() {
        val back = roundTrip(
            meta(
                functions = listOf(func(0, "FOO")),
                labels = mapOf("FOO" to mapOf("START" to 5)),
                files = mapOf("FOO" to "ERB/A.ERB"),
            ),
        )

        val labelKey = back.labels.keys.single()
        val fileKey = back.fileByKey.keys.single()
        val metaKey = back.functions.single().key
        assertSame(labelKey, fileKey, "labels 外层键与 fileByKey 的键必须是同一实例")
        assertSame(labelKey, metaKey, "labels 外层键与 FuncMeta.key 必须是同一实例")
        assertSame(fileKey, metaKey, "fileByKey 的键与 FuncMeta.key 必须是同一实例")

        // FunctionTable 的索引直接拿 FuncMeta.key 当 HashMap 键（FuncMeta.kt:91-94），
        // 因此第三处（运行期索引）也落在同一个实例上。
        val indexKey = nonEventIndexKeys(FunctionTable(back.functions)).single()
        assertSame(labelKey, indexKey, "FunctionTable 的索引键必须与侧车键同实例")
        assertSame(fileKey, indexKey, "FunctionTable 的索引键必须与文件表键同实例")
    }

    @Test
    fun `多个函数键各自归一且不同键不合并`() {
        val back = roundTrip(
            meta(
                functions = listOf(func(0, "FOO"), func(1, "BAR"), func(2, "BAZ")),
                labels = mapOf("FOO" to emptyMap(), "BAR" to emptyMap(), "BAZ" to emptyMap()),
                files = mapOf("FOO" to "ERB/A.ERB", "BAR" to "ERB/A.ERB", "BAZ" to "ERB/B.ERB"),
            ),
        )

        assertEquals(setOf("FOO", "BAR", "BAZ"), back.labels.keys)
        for (m in back.functions) {
            assertSame(m.key, back.labels.keys.first { it == m.key }, "${m.key} 的标签表键必须与 FuncMeta.key 同实例")
            assertSame(m.key, back.fileByKey.keys.first { it == m.key }, "${m.key} 的文件表键必须与 FuncMeta.key 同实例")
        }
        val keys = back.functions.map { it.key }
        assertNotSame(keys[0], keys[1], "内容不同的函数键不能合并成同一实例")
        assertNotSame(keys[1], keys[2], "内容不同的函数键不能合并成同一实例")
    }

    @Test
    fun `同一源文件的路径值只留一个实例`() {
        val back = roundTrip(
            meta(
                functions = listOf(func(0, "FOO"), func(1, "BAR"), func(2, "BAZ")),
                labels = mapOf("FOO" to emptyMap(), "BAR" to emptyMap(), "BAZ" to emptyMap()),
                files = mapOf("FOO" to "ERB/A.ERB", "BAR" to "ERB/A.ERB", "BAZ" to "ERB/B.ERB"),
                globalDims = listOf(BootMetadata.RawDim("#DIM X, 1", "ERB/A.ERB", 3)),
            ),
        )

        val same = back.fileByKey.getValue("FOO")
        assertSame(same, back.fileByKey.getValue("BAR"), "同一文件的路径串必须共享实例")
        assertEquals("ERB/A.ERB", same)
        assertNotSame(same, back.fileByKey.getValue("BAZ"), "不同文件的路径串不能合并")
        assertSame(same, back.globalDims.single().file, "全局 #DIM 的文件名与文件表共用同一实例")
    }

    @Test
    fun `名字与键逐字相同时共享实例逐字不同时保留各自实例`() {
        val back = roundTrip(
            meta(
                functions = listOf(
                    func(0, "FOO"),
                    func(1, "BAR", name = "Bar"),
                ),
                labels = mapOf("FOO" to emptyMap(), "BAR" to emptyMap()),
                files = mapOf("FOO" to "ERB/A.ERB", "BAR" to "ERB/A.ERB"),
            ),
        )

        val foo = back.functions.first { it.key == "FOO" }
        val bar = back.functions.first { it.key == "BAR" }
        assertSame(foo.key, foo.name, "名字与键逐字相同 → 同一实例")
        assertEquals("Bar", bar.name, "大小写不同的书写名必须原样保留")
        assertNotSame(bar.key, bar.name, "名字与键内容不同 → 不共享实例")
    }

    @Test
    fun `私有 DIM 的函数键与函数键同实例`() {
        val back = roundTrip(
            meta(
                functions = listOf(func(0, "FOO"), func(1, "BAR")),
                labels = mapOf("FOO" to emptyMap(), "BAR" to emptyMap()),
                files = mapOf("FOO" to "ERB/A.ERB", "BAR" to "ERB/A.ERB"),
                privateDims = listOf(
                    BootMetadata.PrivateDim("FOO", DimSpec(name = "X", isStr = false, dims = intArrayOf(3))),
                    BootMetadata.PrivateDim("BAR", DimSpec(name = "Y", isStr = true, dims = intArrayOf(2))),
                ),
            ),
        )

        for (p in back.privateDims) {
            val key = back.functions.first { it.key == p.functionKey }.key
            assertSame(key, p.functionKey, "私有 #DIM 的函数键必须与 FuncMeta.key 同实例")
        }
    }

    // ------------------------------------------------------------------
    // 语义不变（归一前后查表结果相同）
    // ------------------------------------------------------------------

    @Test
    fun `函数表查表结果与归一前一致`() {
        val back = roundTrip(
            meta(
                functions = listOf(
                    func(0, "FOO"),
                    func(1, "BAR", name = "Bar"),
                    FuncMeta(index = 2, key = "EV", isEvent = true, defIndex = 10),
                    FuncMeta(index = 3, key = "EV", isEvent = true, isOnly = true, defIndex = 11),
                ),
                labels = mapOf("FOO" to emptyMap(), "BAR" to emptyMap(), "EV" to emptyMap()),
                files = mapOf("FOO" to "ERB/A.ERB", "BAR" to "ERB/A.ERB", "EV" to "ERB/A.ERB"),
            ),
        )

        val table = FunctionTable(back.functions)
        assertEquals(4, table.all().size)
        assertEquals(0, table.nonEvent("foo")?.index, "非事件查表按 IGNORE CASE 归一")
        assertEquals("FOO", table.nonEvent("FoO")?.key)
        assertSame(table.nonEvent("foo")?.key, back.functions.first { it.index == 0 }.key)
        assertEquals(FunctionTable.TargetKind.NON_EVENT, table.classify("bar"))
        assertEquals(FunctionTable.TargetKind.EVENT, table.classify("ev"))
        assertEquals(FunctionTable.TargetKind.NOT_FOUND, table.classify("nope"))
        assertTrue(table.contains("Ev"))
        assertEquals(listOf(3, 2), table.eventCallOrder("eV")?.map { it.index }, "ONLY 桶先于 NORMAL 桶")
        assertNull(table.eventCallOrder("FOO"), "非事件名没有事件执行序")
        assertEquals("BAR", table.byFunctionIndex(1)?.key)
        assertNull(table.byFunctionIndex(99))
    }

    @Test
    fun `标签表与文件表按名解析不变`() {
        val back = roundTrip(
            meta(
                functions = listOf(func(0, "FOO")),
                labels = mapOf("FOO" to mapOf("START" to 5, "END" to 9)),
                files = mapOf("FOO" to "ERB/A.ERB"),
            ),
        )

        assertEquals(5, back.labelPc("foo", "start"), "两侧键都按大写归一后仍能命中")
        assertEquals(9, back.labelPc("FOO", "END"))
        assertNull(back.labelPc("FOO", "NOPE"))
        assertNull(back.labelPc("NOPE", "START"))
        assertEquals("ERB/A.ERB", back.fileOf("foo"))
        assertNull(back.fileOf("nope"))
    }

    // ------------------------------------------------------------------
    // 边界：空侧车 / 单函数 / 池不跨解析
    // ------------------------------------------------------------------

    @Test
    fun `空表边界不建池也不改变编码`() {
        val text = meta().encodeText()
        val back = BootMetadata.parse(text)

        assertTrue(back.labels.isEmpty())
        assertTrue(back.fileByKey.isEmpty())
        assertTrue(back.functions.isEmpty())
        assertTrue(back.privateDims.isEmpty())
        assertTrue(back.globalDims.isEmpty())
        assertEquals(text, back.encodeText(), "空侧车往返必须逐字相同")
        assertNull(back.labelPc("FOO", "START"))
        assertNull(back.fileOf("FOO"))
        val table = FunctionTable(back.functions)
        assertEquals(FunctionTable.TargetKind.NOT_FOUND, table.classify("FOO"))
        assertTrue(table.all().isEmpty())
    }

    @Test
    fun `单函数无标签无文件的边界`() {
        val back = roundTrip(
            meta(
                functions = listOf(func(7, "ONLY_ONE")),
                files = emptyMap(),
            ),
        )

        val m = back.functions.single()
        assertEquals(7, m.index)
        assertEquals("ONLY_ONE", m.key)
        assertSame(m.key, m.name)
        assertTrue(back.labels.isEmpty(), "侧车没有 labelset 行时标签表必须是空表")
        assertTrue(back.fileByKey.isEmpty())
        assertEquals("ONLY_ONE", FunctionTable(back.functions).nonEvent("only_one")?.key)
        assertNull(back.labelPc("ONLY_ONE", "START"))
    }

    @Test
    fun `两次解析各自建池不共享实例`() {
        val text = meta(
            functions = listOf(func(0, "FOO")),
            labels = mapOf("FOO" to mapOf("START" to 5)),
            files = mapOf("FOO" to "ERB/A.ERB"),
        ).encodeText()

        val a = BootMetadata.parse(text)
        val b = BootMetadata.parse(text)

        assertEquals(a.functions, b.functions, "两份解析结果按内容相等")
        assertNotSame(
            a.functions.single().key,
            b.functions.single().key,
            "驻留池必须随每次解析新建，不能是跨解析的静态全局",
        )
        assertNotSame(a.fileByKey.getValue("FOO"), b.fileByKey.getValue("FOO"))
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 一次完整往返：编码 → 解析 → 再编码必须逐字节相同（归一只改身份，不改文本）。 */
    private fun roundTrip(m: BootMetadata): BootMetadata {
        val text = m.encodeText()
        val back = BootMetadata.parse(text)
        assertEquals(text, back.encodeText(), "解析不得改变侧车文本")
        return back
    }

    private fun meta(
        functions: List<FuncMeta> = emptyList(),
        labels: Map<String, Map<String, Int>> = emptyMap(),
        files: Map<String, String> = emptyMap(),
        globalDims: List<BootMetadata.RawDim> = emptyList(),
        privateDims: List<BootMetadata.PrivateDim> = emptyList(),
    ): BootMetadata = BootMetadata(
        labels = labels,
        functions = functions,
        fileByKey = files,
        globalDims = globalDims,
        privateDims = privateDims,
    )

    /** 缺省 `name = key`（= FuncMeta 的缺省值），模拟语料里 97.3% 的写法。 */
    private fun func(index: Int, key: String, name: String = key): FuncMeta =
        FuncMeta(index = index, key = key, name = name, defIndex = index)

    /**
     * `FunctionTable.nonEventByKey` 的键集（私有字段，只用于身份断言）。
     *
     * 索引是 `HashMap<String, FuncMeta>`，键就是传进来的 `FuncMeta.key` 实例；
     * 取出来比 `===` 才能证明「运行期索引与侧车表共用同一份键串」。
     */
    private fun nonEventIndexKeys(table: FunctionTable): Set<String> {
        val field = FunctionTable::class.java.getDeclaredField("nonEventByKey")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val index = field.get(table) as Map<String, *>
        return index.keys
    }
}
