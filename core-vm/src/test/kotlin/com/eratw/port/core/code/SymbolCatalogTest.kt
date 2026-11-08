package com.eratw.port.core.code

import com.eratw.port.core.proc.FuncMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ABI-7 LSC 构建器契约钉（era-arch-v61u.md §3/§10）：
 * 无覆盖恒等（golden Gate 的构建器半边）、legacy last-wins + super 链、
 * modern FORBID/显式 OVERWRITE、事件恒等不参与覆盖、切面链头优先。
 */
class SymbolCatalogTest {

    private fun meta(idx: Int, key: String, event: Boolean = false, method: Boolean = false) =
        FuncMeta(index = idx, key = key, isEvent = event, isMethod = method)

    private fun candidate(idx: Int, key: String, owner: String, override: Boolean = false,
                          event: Boolean = false, method: Boolean = false,
                          signature: String = "sig:$key") =
        SymbolCandidate(meta(idx, key, event, method), override, owner, signature)

    @Test
    fun noOverrideMeansIdentityDispatch() {
        val catalog = SymbolCatalog.build(
            listOf(candidate(0, "FOO", "base"), candidate(1, "BAR", "base"), candidate(2, "EVENTFIRST", "base", event = true)),
            ResolverPolicy.MODERN,
        )
        assertContentEquals(intArrayOf(0, 1, 2), catalog.dispatch)
        assertContentEquals(intArrayOf(-1, -1, -1), catalog.superChain)
        assertEquals(0, catalog.winnerIndexOf("FOO"))
        assertEquals(1, catalog.winnerIndexOf("bar")) // 查询键归一
        assertEquals(-1, catalog.winnerIndexOf("EVENTFIRST")) // 事件不进胜者表
        assertEquals(-1, catalog.winnerIndexOf("MISSING"))
        assertFalse(catalog.hasSuper("FOO"))
    }

    @Test
    fun legacyLastWinsWithSuperChain() {
        val catalog = SymbolCatalog.build(
            listOf(
                candidate(0, "FOO", "base"),
                candidate(1, "FOO", "modA"),
                candidate(2, "FOO", "modB"),
            ),
            ResolverPolicy.LEGACY_LAST_WINS,
        )
        assertEquals(2, catalog.winnerIndexOf("FOO"))
        // 全部物理实现的静态调用都导向胜者（base 的 _CALL 被重定向）。
        assertContentEquals(intArrayOf(2, 2, 2), catalog.dispatch)
        // super 链 = 链接序：B → A → base。
        assertEquals(1, catalog.superIndexOf(2))
        assertEquals(0, catalog.superIndexOf(1))
        assertEquals(-1, catalog.superIndexOf(0))
        assertTrue(catalog.hasSuper("FOO"))
    }

    @Test
    fun modernForbidsUndeclaredDuplicate() {
        val ex = assertFailsWith<SymbolConflictException> {
            SymbolCatalog.build(
                listOf(candidate(0, "FOO", "base"), candidate(1, "FOO", "modA")),
                ResolverPolicy.MODERN,
            )
        }
        assertEquals("FOO", ex.key)
        assertEquals(listOf("base", "modA"), ex.providers)
    }

    @Test
    fun modernExplicitOverrideBuildsSuperChain() {
        val catalog = SymbolCatalog.build(
            listOf(candidate(0, "FOO", "base"), candidate(1, "FOO", "modA", override = true)),
            ResolverPolicy.MODERN,
        )
        assertEquals(1, catalog.winnerIndexOf("FOO"))
        assertEquals(0, catalog.superIndexOf(1))
        assertContentEquals(intArrayOf(1, 1), catalog.dispatch)
        // 第三方未声明覆盖仍拒绝（报告列全部三个提供者）。
        val ex = assertFailsWith<SymbolConflictException> {
            SymbolCatalog.build(
                listOf(
                    candidate(0, "FOO", "base"),
                    candidate(1, "FOO", "modA", override = true),
                    candidate(2, "FOO", "modB"),
                ),
                ResolverPolicy.MODERN,
            )
        }
        assertEquals(listOf("base", "modA", "modB"), ex.providers)
    }

    @Test
    fun modernOverrideRequiresCompleteSignature() {
        // 被覆盖方缺指纹（erbcc 未导出完整签名）⇒ 拒绝，不拿 arity 猜。
        assertFailsWith<OverrideSignatureException> {
            SymbolCatalog.build(
                listOf(
                    candidate(0, "FOO", "base", signature = ""),
                    candidate(1, "FOO", "modA", override = true),
                ),
                ResolverPolicy.MODERN,
            )
        }
        // 覆盖方缺指纹同样拒绝。
        val ex = assertFailsWith<OverrideSignatureException> {
            SymbolCatalog.build(
                listOf(
                    candidate(0, "FOO", "base"),
                    candidate(1, "FOO", "modA", override = true, signature = ""),
                ),
                ResolverPolicy.MODERN,
            )
        }
        assertEquals("FOO", ex.key)
        assertTrue(ex.reason.contains("签名信息不完整"), ex.reason)
    }

    @Test
    fun modernOverrideRejectsSignatureMismatch() {
        val ex = assertFailsWith<OverrideSignatureException> {
            SymbolCatalog.build(
                listOf(
                    candidate(0, "FOO", "base", signature = "int,int"),
                    candidate(1, "FOO", "modA", override = true, signature = "int,str"),
                ),
                ResolverPolicy.MODERN,
            )
        }
        assertEquals("FOO", ex.key)
        assertEquals("base", ex.previousPackageId)
        assertEquals("modA", ex.overridingPackageId)
        assertTrue(ex.reason.contains("签名不一致"), ex.reason)
    }

    @Test
    fun legacyIgnoresSignatureGate() {
        // legacy last-wins 是行为复刻口径，不设签名闸（空指纹也照常胜出）。
        val catalog = SymbolCatalog.build(
            listOf(
                candidate(0, "FOO", "base", signature = ""),
                candidate(1, "FOO", "modA", signature = ""),
            ),
            ResolverPolicy.LEGACY_LAST_WINS,
        )
        assertEquals(1, catalog.winnerIndexOf("FOO"))
    }

    @Test
    fun bindingCarriesWinnerSignature() {
        val catalog = SymbolCatalog.build(
            listOf(candidate(0, "FOO", "base", signature = "sig:v1")),
            ResolverPolicy.MODERN,
        )
        assertEquals("sig:v1", catalog.bindings.getValue("FOO").signatureHash)
    }

    @Test
    fun eventsNeverOverrideAndStayIdentity() {
        val catalog = SymbolCatalog.build(
            listOf(
                candidate(0, "EVENTFIRST", "base", event = true),
                candidate(1, "EVENTFIRST", "modA", event = true),
                candidate(2, "FOO", "base"),
            ),
            ResolverPolicy.MODERN, // 事件重名在 MODERN 下也不报冲突
        )
        assertContentEquals(intArrayOf(0, 1, 2), catalog.dispatch)
        assertEquals(-1, catalog.superIndexOf(1))
        assertEquals(-1, catalog.winnerIndexOf("EVENTFIRST"))
        assertEquals(2, catalog.winnerIndexOf("FOO"))
    }

    @Test
    fun aspectHeadTakesPriorityOverWinner() {
        val catalog = SymbolCatalog.build(
            listOf(
                candidate(0, "FOO", "base"),
                candidate(1, "FOO", "modA", override = true),
                candidate(2, "FOO_WRAPPER", "aspect"),
            ),
            ResolverPolicy.MODERN,
            aspectHeads = mapOf("foo" to 2), // 键归一后命中
        )
        // 切面链头压过胜者；wrapper 自身也是普通符号。
        assertContentEquals(intArrayOf(2, 2, 2), catalog.dispatch)
        assertEquals(1, catalog.winnerIndexOf("FOO")) // 胜者绑定不受切面影响
        assertEquals(0, catalog.superIndexOf(1))
    }

    @Test
    fun aspectHeadMustBeRegisteredNonEvent() {
        val base = listOf(candidate(0, "FOO", "base"), candidate(1, "EV", "base", event = true))
        assertFailsWith<IllegalArgumentException> {
            SymbolCatalog.build(base, ResolverPolicy.MODERN, aspectHeads = mapOf("FOO" to 5))
        }
        assertFailsWith<IllegalArgumentException> {
            SymbolCatalog.build(base, ResolverPolicy.MODERN, aspectHeads = mapOf("FOO" to 1))
        }
    }

    @Test
    fun methodsResolveLikeFunctionsButKindDiffers() {
        val catalog = SymbolCatalog.build(
            listOf(candidate(0, "GET_X", "base", method = true)),
            ResolverPolicy.MODERN,
        )
        assertEquals(SymbolKind.METHOD, catalog.bindings["GET_X"]!!.kind)
        assertEquals(0, catalog.winnerIndexOf("GET_X"))
    }

    @Test
    fun dispatchIsIdempotentUnderOverrideAndAspect() {
        // 分派幂等不变量（v6.2-c）：名字通道返回执行入口后二次查表不漂移。
        val catalog = SymbolCatalog.build(
            listOf(
                candidate(0, "FOO", "base"),
                candidate(1, "FOO", "modA", override = true),
                candidate(2, "FOO_WRAPPER", "aspect"),
                candidate(3, "EVENTFIRST", "base", event = true),
            ),
            ResolverPolicy.MODERN,
            aspectHeads = mapOf("FOO" to 2),
        )
        for (i in catalog.dispatch.indices) {
            val target = catalog.dispatch[i]
            assertEquals(target, catalog.dispatch[target], "dispatch[$i] 的像必须是不动点")
        }
    }

    @Test
    fun overriddenWrapperBreaksIdempotenceAndIsRejected() {
        // 链头自身被覆盖 ⇒ dispatch 值不再是不动点 ⇒ 构建期 fail-closed（不留到运行期）。
        assertFailsWith<IllegalStateException> {
            SymbolCatalog.build(
                listOf(
                    candidate(0, "FOO", "base"),
                    candidate(1, "WRAP", "aspect"),
                    candidate(2, "WRAP", "modB", override = true),
                ),
                ResolverPolicy.MODERN,
                aspectHeads = mapOf("FOO" to 1),
            )
        }
    }

    @Test
    fun duplicateOrNegativePhysicalIndexRejected() {
        assertFailsWith<IllegalArgumentException> {
            SymbolCatalog.build(
                listOf(candidate(0, "FOO", "a"), candidate(0, "BAR", "b")),
                ResolverPolicy.LEGACY_LAST_WINS,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SymbolCatalog.build(listOf(candidate(-1, "FOO", "a")), ResolverPolicy.LEGACY_LAST_WINS)
        }
    }

    @Test
    fun eventDefinitionsKeepAllMembersInLinkOrder() {
        // v6.4-c（GPT 三审 P0-1）：事件一名多定义——单一绑定表达不了全部成员，
        // LSC 必须显式一对多；Base 两个 + 两个 Mod 的同名事件全部按链接序保留。
        val catalog = SymbolCatalog.build(
            listOf(
                candidate(0, "EVENTTURN", "base", event = true),
                candidate(1, "EVENTTURN", "base", event = true),
                candidate(2, "EVENTTURN", "modA", event = true),
                candidate(3, "EVENTTURN", "modB", event = true),
                candidate(4, "FOO", "base"),
            ),
            ResolverPolicy.MODERN, // 事件重名在 MODERN 下也不报冲突
        )
        val members = catalog.eventMembersOf("eventturn") // 查询键归一
        assertEquals(listOf(0, 1, 2, 3), members.map { it.physicalIndex })
        assertEquals(listOf("base", "base", "modA", "modB"), members.map { it.ownerPackageId })
        assertTrue(members.all { it.kind == SymbolKind.EVENT })
        assertContentEquals(intArrayOf(0, 1, 2, 3, 4), catalog.dispatch) // 事件成员恒等
        assertEquals(-1, catalog.winnerIndexOf("EVENTTURN")) // 事件不进胜者表
        assertTrue(catalog.eventMembersOf("FOO").isEmpty()) // 非事件不进事件视图
        assertTrue(catalog.eventMembersOf("MISSING").isEmpty())
    }

    private fun assertContentEquals(expected: IntArray, actual: IntArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}