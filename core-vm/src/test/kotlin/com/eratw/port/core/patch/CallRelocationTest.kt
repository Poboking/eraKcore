package com.eratw.port.core.patch

import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SymbolCandidate
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.proc.FuncMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2-1 调用点重定位钉：`_CALL.a` 四支判序（super / TRY-super / 外部导入 / 模块内）、
 * 净偏移算术、HAS_OVERRIDES 判据（最终 dispatch 非恒等，不是「装了 Mod」）。
 */
class CallRelocationTest {

    private fun meta(idx: Int, key: String, event: Boolean = false) =
        FuncMeta(index = idx, key = key, isEvent = event)

    private fun candidate(idx: Int, key: String, owner: String, override: Boolean = false,
                          event: Boolean = false) =
        SymbolCandidate(meta(idx, key, event), override, owner, "sig:$key")

    /** FOO: base@0 → modA@1（显式覆盖）；BAR: modA@2；EVENT: base@3。 */
    private val catalog: SymbolCatalog = SymbolCatalog.build(
        listOf(
            candidate(0, "FOO", "base"),
            candidate(1, "FOO", "modA", override = true),
            candidate(2, "BAR", "modA"),
            candidate(3, "EVENT", "base", event = true),
        ),
        ResolverPolicy.MODERN,
    )

    private val imports: ExternalImportTable.PackageImports = ExternalImportTable
        .resolve(
            listOf(ModuleDescriptor(packageId = "modA", externalCalls = listOf("BAR"))),
            catalog,
        )
        .importsOf("modA")!!

    @Test
    fun moduleInternalIndexGetsNetOffset() {
        val out = CallRelocation.resolve(
            a = 5,
            containingFunction = 2,
            netOffset = 10,
            imports = null,
            catalog = catalog,
        )
        assertEquals(CallRelocation.Outcome.Internal(15), out)
        // 零偏移模块原样。
        assertEquals(
            CallRelocation.Outcome.Internal(5),
            CallRelocation.resolve(5, 2, 0, null, catalog),
        )
        // 越过 Int 域拒绝（不静默回绕）。
        assertFailsWith<RelocationException> {
            CallRelocation.resolve(Int.MAX_VALUE, 2, 1, null, catalog)
        }
    }

    @Test
    fun externalSentinelResolvesThroughImportTable() {
        val out = CallRelocation.resolve(
            a = CallSentinels.external(0),
            containingFunction = 1,
            netOffset = 0,
            imports = imports,
            catalog = catalog,
        )
        assertEquals(CallRelocation.Outcome.External(2), out)
        // 槽越域 / 无导入表都拒绝。
        assertFailsWith<RelocationException> {
            CallRelocation.resolve(CallSentinels.external(1), 1, 0, imports, catalog)
        }
        assertFailsWith<RelocationException> {
            CallRelocation.resolve(CallSentinels.external(0), 1, 0, null, catalog)
        }
    }

    @Test
    fun callSuperResolvesToPreviousImplementation() {
        val out = CallRelocation.resolve(
            a = CallSentinels.CALL_SUPER_SENTINEL,
            containingFunction = 1, // modA 的 FOO，前实现是 base@0
            netOffset = 0,
            imports = null,
            catalog = catalog,
        )
        assertEquals(CallRelocation.Outcome.SuperCall(0), out)
        // 无前实现的函数用 CALL_SUPER = 链接错误。
        assertFailsWith<RelocationException> {
            CallRelocation.resolve(CallSentinels.CALL_SUPER_SENTINEL, 2, 0, null, catalog)
        }
    }

    @Test
    fun tryCallSuperWithoutSuperFoldsToNop() {
        assertEquals(
            CallRelocation.Outcome.SuperCall(0),
            CallRelocation.resolve(CallSentinels.TRY_CALL_SUPER_SENTINEL, 1, 0, null, catalog),
        )
        assertEquals(
            CallRelocation.Outcome.NoSuperNop,
            CallRelocation.resolve(CallSentinels.TRY_CALL_SUPER_SENTINEL, 2, 0, null, catalog),
        )
        // 两个 super 哨兵绝不能被当成外部导入（即便传了导入表也走 super 分支）。
        assertEquals(
            CallRelocation.Outcome.SuperCall(0),
            CallRelocation.resolve(CallSentinels.CALL_SUPER_SENTINEL, 1, 0, imports, catalog),
        )
        assertEquals(
            CallRelocation.Outcome.SuperCall(0),
            CallRelocation.resolve(CallSentinels.TRY_CALL_SUPER_SENTINEL, 1, 0, imports, catalog),
        )
    }

    @Test
    fun hasOverridesFollowsFinalDispatchNotDeclarations() {
        assertTrue(catalog.hasOverrides, "存在覆盖 ⇒ 非恒等 dispatch ⇒ 旗标为真")
        // 只有新函数、无覆盖 ⇒ 恒等表 ⇒ 旗标为假（即使装了 Mod）。
        val additive = SymbolCatalog.build(
            listOf(
                candidate(0, "FOO", "base"),
                candidate(1, "BAR", "modA"),
                candidate(2, "EVENT", "modA", event = true),
            ),
            ResolverPolicy.MODERN,
        )
        assertFalse(additive.hasOverrides)
        // Base 内部重名在 LEGACY 下也产生非恒等绑定 ⇒ 旗标必须为真（P1-2 判据）。
        val legacyDuplicate = SymbolCatalog.build(
            listOf(
                candidate(0, "FOO", "base"),
                candidate(1, "FOO", "base"),
            ),
            ResolverPolicy.LEGACY_LAST_WINS,
        )
        assertTrue(legacyDuplicate.hasOverrides)
    }
}
