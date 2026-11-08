package com.eratw.port.core.patch

import com.eratw.port.core.code.ConstantPool
import com.eratw.port.core.code.FunctionInfo
import com.eratw.port.core.code.Program
import com.eratw.port.core.proc.FuncMeta
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [LinkCandidateAssembler] 候选装配的契约钉（M2-1 单位 B；纯函数，可穷举）。
 *
 * 覆盖：下标/装载序号重写、owner 归属、覆盖声明、签名透传、窗口稠密分配、
 * 空模块、多模块链，以及四条 fail-closed 判据的负向钉。
 */
class LinkCandidateAssemblerTest {

    /** 测试模块：给键列表造 0 基 FuncMeta + 同长 stub 产物；签名表大写归一。 */
    private fun mod(
        pkg: String,
        fnKeys: List<String>,
        overrides: Set<String> = emptySet(),
        signatures: Map<String, String> = emptyMap(),
    ): LinkableModule = object : LinkableModule {
        override val descriptor = ModuleDescriptor(
            packageId = pkg,
            overrideFunctions = overrides,
        )
        override val program: Program = stubProgram(fnKeys.size)
        override val functions: List<FuncMeta> =
            fnKeys.mapIndexed { i, k -> FuncMeta(index = i, key = k) }

        override fun signatureHashOf(functionKey: String): String =
            signatures[functionKey.uppercase(Locale.ROOT)] ?: ""
    }

    /** 形状占位产物（assemble 不读 program，只要 functions 形状对齐）。 */
    private fun stubProgram(fnCount: Int): Program = Program(
        code = IntArray(4 * (fnCount.coerceAtLeast(1))),
        lines = IntArray(fnCount.coerceAtLeast(1)),
        pool = ConstantPool(),
        functions = List(fnCount) { FunctionInfo("FN$it", 0, 0, 1) },
        entryFunction = 0,
        globalSlots = 0,
    )

    // ------------------------------------------------------------------
    // 正向：窗口分配与候选装配
    // ------------------------------------------------------------------

    @Test
    fun `functionStarts 从 base 数起按链接序累加`() {
        val mods = listOf(
            mod("mod.a", listOf("A1", "A2", "A3")),
            mod("mod.b", listOf("B1", "B2")),
        )
        assertContentEquals(intArrayOf(5, 8), LinkCandidateAssembler.functionStarts(5, mods))
    }

    @Test
    fun `无模块时起始表为空`() {
        assertContentEquals(IntArray(0), LinkCandidateAssembler.functionStarts(0, emptyList()))
        assertContentEquals(IntArray(0), LinkCandidateAssembler.functionStarts(42, emptyList()))
    }

    @Test
    fun `assemble 重写下标与装载序号`() {
        val mods = listOf(mod("mod.a", listOf("A1", "A2", "A3")))
        val out = LinkCandidateAssembler.assemble(mods, intArrayOf(5))
        assertEquals(3, out.size)
        assertEquals(listOf(5, 6, 7), out.map { it.meta.index })
        // defIndex（事件同桶执行序）随绝对下标改写：跨模块事件桶序 = 链接序。
        assertEquals(5, out[0].meta.defIndex)
        assertEquals(7, out[2].meta.defIndex)
        // 其余字段原样透传。
        assertEquals("A1", out[0].meta.key)
        assertEquals("mod.a", out[0].ownerPackageId)
    }

    @Test
    fun `assemble 多模块链窗口连续`() {
        val mods = listOf(
            mod("mod.a", listOf("A1", "A2")),
            mod("mod.b", listOf("B1", "B2", "B3")),
        )
        val starts = LinkCandidateAssembler.functionStarts(4, mods)
        val out = LinkCandidateAssembler.assemble(mods, starts)
        assertEquals(5, out.size)
        assertEquals(listOf(4, 5, 6, 7, 8), out.map { it.meta.index })
        assertEquals(
            listOf("mod.a", "mod.a", "mod.b", "mod.b", "mod.b"),
            out.map { it.ownerPackageId },
        )
    }

    @Test
    fun `assemble 空模块产出空候选`() {
        assertTrue(LinkCandidateAssembler.assemble(emptyList(), IntArray(0)).isEmpty())
    }

    @Test
    fun `覆盖声明与签名透传`() {
        val mods = listOf(
            mod(
                pkg = "mod.ov",
                fnKeys = listOf("OVRD", "PLAIN"),
                overrides = setOf("OVRD"),
                signatures = mapOf("OVRD" to "sig-abc"),
            ),
        )
        val out = LinkCandidateAssembler.assemble(mods, intArrayOf(0))
        assertEquals(2, out.size)
        val overridden = out.first { it.meta.key == "OVRD" }
        val plain = out.first { it.meta.key == "PLAIN" }
        assertEquals(true, overridden.declaredOverride)
        assertEquals("sig-abc", overridden.signatureHash)
        assertEquals(false, plain.declaredOverride)
        assertEquals("", plain.signatureHash)
    }

    // ------------------------------------------------------------------
    // 负向：fail-closed 判据逐条
    // ------------------------------------------------------------------

    @Test
    fun `模块数与起始下标不同长拒绝`() {
        val mods = listOf(mod("mod.a", listOf("A1")))
        val ex = assertFailsWith<IllegalArgumentException> {
            LinkCandidateAssembler.assemble(mods, intArrayOf(0, 1))
        }
        assertTrue("不同长" in ex.message!!, ex.message)
    }

    @Test
    fun `窗口重叠拒绝`() {
        val mods = listOf(
            mod("mod.a", listOf("A1", "A2")),
            mod("mod.b", listOf("B1")),
        )
        // mod.b 起始 1，落在 mod.a 窗口 [0, 2) 内 —— 拒绝并点名两包。
        val ex = assertFailsWith<IllegalArgumentException> {
            LinkCandidateAssembler.assemble(mods, intArrayOf(0, 1))
        }
        assertTrue("窗口重叠" in ex.message!!, ex.message)
        assertTrue("mod.a" in ex.message!! && "mod.b" in ex.message!!, ex.message)
    }

    @Test
    fun `非稠密模块内下标拒绝`() {
        val m = mod("mod.bad", listOf("X"))
        // 绕过 validate 直喂装配：模块内下标非 0 基在源头被兜住。
        val broken = object : LinkableModule by m {
            override val functions: List<FuncMeta> = listOf(FuncMeta(index = 3, key = "X"))
        }
        val ex = assertFailsWith<IllegalArgumentException> {
            LinkCandidateAssembler.assemble(listOf(broken), intArrayOf(0))
        }
        assertTrue("0 基稠密" in ex.message!!, ex.message)
    }

    // ------------------------------------------------------------------
    // base 候选装配
    // ------------------------------------------------------------------

    @Test
    fun `baseCandidates 原样透传不改下标`() {
        val metas = listOf(
            FuncMeta(index = 7, key = "BASEFN"),
            FuncMeta(index = 8, key = "BASEFN2", isEvent = true),
        )
        val out = LinkCandidateAssembler.baseCandidates(
            metas,
            ownerPackageId = "base",
            signatureHashOf = { k -> if (k == "BASEFN") "sig-b" else "" },
        )
        assertEquals(2, out.size)
        assertEquals(7, out[0].meta.index)
        assertEquals(8, out[1].meta.defIndex)
        assertEquals(false, out[0].declaredOverride)
        assertEquals("base", out[0].ownerPackageId)
        assertEquals("sig-b", out[0].signatureHash)
        assertEquals("", out[1].signatureHash)
    }

    @Test
    fun `baseCandidates 缺省签名查询恒空`() {
        val out = LinkCandidateAssembler.baseCandidates(listOf(FuncMeta(index = 0, key = "F")))
        assertEquals("", out[0].signatureHash)
        assertEquals("", out[0].ownerPackageId)
        assertEquals(false, out[0].declaredOverride)
    }
}
