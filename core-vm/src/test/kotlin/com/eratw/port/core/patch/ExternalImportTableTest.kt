package com.eratw.port.core.patch

import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SymbolCandidate
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.proc.FuncMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-1 外部导入解析钉：负哨兵槽位 = `externalCalls` 列表下标、绑定最终 LSC 胜者、
 * 缺提供者 / 事件误当函数 / 重复导入整组拒绝。
 */
class ExternalImportTableTest {

    private fun meta(idx: Int, key: String, event: Boolean = false) =
        FuncMeta(index = idx, key = key, isEvent = event)

    private fun catalogOf(vararg candidates: SymbolCandidate): SymbolCatalog =
        SymbolCatalog.build(candidates.toList(), ResolverPolicy.MODERN)

    private fun mod(id: String, vararg imports: String) =
        ModuleDescriptor(packageId = id, externalCalls = imports.toList())

    private val catalog = catalogOf(
        SymbolCandidate(meta(0, "FOO"), ownerPackageId = "base"),
        SymbolCandidate(meta(1, "BAR"), ownerPackageId = "modA"),
        SymbolCandidate(meta(2, "EVENTFIRST", event = true), ownerPackageId = "base"),
    )

    @Test
    fun importsResolveToWinnerInDeclarationOrder() {
        val table = ExternalImportTable.resolve(listOf(mod("modA", "bar", "FOO")), catalog)
        val imports = table.importsOf("modA")!!.imports
        assertEquals(listOf("bar", "FOO"), imports.map { it.name })
        assertEquals(listOf(0, 1), imports.map { it.importIndex })
        assertEquals(listOf(1, 0), imports.map { it.physicalIndex })
        // 哨兵回填表与列表序一一对应。
        assertEquals(listOf(1, 0), table.importsOf("modA")!!.physicalIndices().toList())
        // 原文名忽略大小写查询，原文保留在诊断面。
        assertEquals(0, table.physicalIndexOf("modA", "foo"))
        assertEquals(1, table.physicalIndexOf("modA", "BAR"))
        assertEquals(0, table.importIndexOf("modA", "Bar"))
        assertNull(table.physicalIndexOf("modB", "FOO"))
        assertNull(table.physicalIndexOf("modA", "EVENTFIRST"))
    }

    @Test
    fun importsBindToFinalWinnerNotLinkOrder() {
        // 两个包各自导入同一个名字：都解析到同一个最终胜者（与谁先链接无关）。
        val table = ExternalImportTable.resolve(
            listOf(mod("modA", "FOO"), mod("modB", "foo")),
            catalog,
        )
        assertEquals(0, table.physicalIndexOf("modA", "FOO"))
        assertEquals(0, table.physicalIndexOf("modB", "foo"))
        assertEquals(listOf("modA", "modB"), table.packageIds.toList())
    }

    @Test
    fun missingProviderRejectsWithPackageAndName() {
        val e = assertFailsWith<ImportResolutionException> {
            ExternalImportTable.resolve(listOf(mod("modA", "NOPE")), catalog)
        }
        assertEquals("modA", e.packageId)
        assertEquals("NOPE", e.importedName)
        assertTrue(e.reason.contains("没有任何非事件函数"), e.reason)
    }

    @Test
    fun eventNamesCannotBeImportedAsFunctions() {
        val e = assertFailsWith<ImportResolutionException> {
            ExternalImportTable.resolve(listOf(mod("modA", "EVENTFIRST")), catalog)
        }
        assertTrue(e.reason.contains("事件"), e.reason)
    }

    @Test
    fun duplicateImportSlotsRejectEvenCaseInsensitively() {
        val e = assertFailsWith<ImportResolutionException> {
            ExternalImportTable.resolve(listOf(mod("modA", "FOO", "foo")), catalog)
        }
        assertTrue(e.reason.contains("重复导入"), e.reason)
    }

    @Test
    fun blankImportNameRejects() {
        val e = assertFailsWith<ImportResolutionException> {
            ExternalImportTable.resolve(listOf(mod("modA", "  ")), catalog)
        }
        assertTrue(e.reason.contains("为空"), e.reason)
    }

    @Test
    fun duplicatePackageIdRejects() {
        val e = assertFailsWith<ImportResolutionException> {
            ExternalImportTable.resolve(listOf(mod("modA", "FOO"), mod("modA", "BAR")), catalog)
        }
        assertTrue(e.reason.contains("重复"), e.reason)
    }

    @Test
    fun packageWithoutImportsYieldsEmptyTable() {
        val table = ExternalImportTable.resolve(listOf(mod("modA")), catalog)
        assertTrue(table.importsOf("modA")!!.imports.isEmpty())
        assertEquals(0, table.importsOf("modA")!!.physicalIndices().size)
        assertNull(table.importsOf("absent"))
    }
}
