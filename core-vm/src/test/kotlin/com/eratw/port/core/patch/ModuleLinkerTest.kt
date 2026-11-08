package com.eratw.port.core.patch

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.OverrideSignatureException
import com.eratw.port.core.proc.FuncMeta
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ModuleLinker] 链接拼装的出口判据（M2-1 单位 C）。
 *
 * base 与模块都用 [CodeBuilder] 真实编译（零基线模块产物）。场景布局：
 *
 * - base：`ENTRY`（入口）+ `BASEFN`，各一条 `_RET`（2 函数 / 2 指令 / 池空）；
 * - mod.a：10 指令 5 函数 —— `AUSED`@0 / `ACALLER`@1（`_SET_INT`+`_SET_STR`+
 *   `_CALL a=0` 模块内净偏移样本）/ `AEXT`@2（外部导入 BASEFN）/ `ATRY`@3
 *   （TRYCALL_SUPER 无前实现）/ `X`@4（待覆盖函数）；池 long[0]=42、string[0]="あ"；
 *   全局槽引用 9；
 * - mod.b：`BX` 覆盖 `X`（签名一致）+ 体内 `CALL_SUPER`。
 *
 * 绝对 pc 锚点（base 2 指令在前）：mod.a 区间 [2,12)（SET_INT@3、SET_STR@4、
 * 模块内 CALL@5、导入 CALL@7、TRY_SUPER CALL@9）；mod.b 区间 [12,14)
 * （CALL_SUPER@12）。绝对函数下标：mod.a 占 2..6（X=6），BX=7。
 *
 * 断言面：指令改写四支（净偏移 / 导入 / super+DIRECT / 折 NOP）、池平移与
 * 行号 rebase、catalog 胜者与 hasOverrides、入口与 globalSlots 透传、
 * base-only 空模块拒绝、覆盖 base 的签名闸负控。
 */
class ModuleLinkerTest {

    private class TestModule(
        override val descriptor: ModuleDescriptor,
        override val program: Program,
        override val functions: List<FuncMeta>,
        private val signatures: Map<String, String> = emptyMap(),
    ) : LinkableModule {
        override fun signatureHashOf(functionKey: String): String =
            signatures[functionKey.uppercase(Locale.ROOT)] ?: ""
    }

    // ------------------------------------------------------------------
    // 夹具：base 与模块的确定性编译
    // ------------------------------------------------------------------

    /** base：ENTRY@0（RET）+ BASEFN@1（RET）。 */
    private fun compileBase(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        b.declareFunction("BASEFN", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 1)
        return b.build(entry)
    }

    private val baseMetas = listOf(FuncMeta(index = 0, key = "ENTRY"), FuncMeta(index = 1, key = "BASEFN"))

    private fun buildModuleA(): Program {
        val b = CodeBuilder()
        b.declareFunction("AUSED", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        b.declareFunction("ACALLER", b.here(), arity = 0, slotCount = 4)
        b.emit(Opcode._SET_INT, 0, b.constant(42L), 0, 1)
        b.emit(Opcode._SET_STR, 0, b.string("あ"), 0, 2)
        b.emit(Opcode._CALL, 0, 0, SlotAddr.NONE, 3) // a=0：模块内 AUSED
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 4)
        b.declareFunction("AEXT", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, CallSentinels.external(0), 0, SlotAddr.NONE, 5) // 导入表[0]
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 6)
        b.declareFunction("ATRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, CallSentinels.TRY_CALL_SUPER_SENTINEL, 0, SlotAddr.NONE, 7)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 8)
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 9)
        b.noteGlobalSlot(9)
        return b.build(0)
    }

    private val moduleAMetas = listOf(
        FuncMeta(index = 0, key = "AUSED"),
        FuncMeta(index = 1, key = "ACALLER"),
        FuncMeta(index = 2, key = "AEXT"),
        FuncMeta(index = 3, key = "ATRY"),
        FuncMeta(index = 4, key = "X"),
    )

    private fun buildModuleB(): Program {
        val b = CodeBuilder()
        // 覆盖方与被覆盖方**同名**（ERB 覆盖语义 = 同名重新定义；overrideFunctions
        // 声明只是 MODERN 的显式化政策，不是改名）。
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, CallSentinels.CALL_SUPER_SENTINEL, 0, SlotAddr.NONE, 0)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 1)
        return b.build(0)
    }

    private fun moduleA(): LinkableModule = TestModule(
        descriptor = ModuleDescriptor(packageId = "mod.a", externalCalls = listOf("BASEFN")),
        program = buildModuleA(),
        functions = moduleAMetas,
        signatures = mapOf("X" to "sig-x"),
    )

    private fun moduleB(): LinkableModule = TestModule(
        descriptor = ModuleDescriptor(packageId = "mod.b", overrideFunctions = setOf("X")),
        program = buildModuleB(),
        functions = listOf(FuncMeta(index = 0, key = "X")),
        signatures = mapOf("X" to "sig-x"),
    )

    /** 只加新函数的模块（无重名 → dispatch 恒等）。 */
    private fun moduleC(): LinkableModule {
        val b = CodeBuilder()
        b.declareFunction("CNEW", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val prog = b.build(0)
        return TestModule(
            descriptor = ModuleDescriptor(packageId = "mod.c"),
            program = prog,
            functions = listOf(FuncMeta(index = 0, key = "CNEW")),
        )
    }

    private fun linkAll(vararg modules: LinkableModule, policy: ResolverPolicy = ResolverPolicy.MODERN) =
        ModuleLinker.link(compileBase(), baseMetas, modules.toList(), policy)

    // ------------------------------------------------------------------
    // 正向：指令改写四支 + 拼接面
    // ------------------------------------------------------------------

    @Test
    fun `模块内调用净偏移与池平移与行号`() {
        val linked = linkAll(moduleA())
        val p = linked.program
        // _CALL 模块内 a=0（AUSED）→ 绝对 2 + 0 = 2（AUSED 物理下标）。
        assertEquals(Opcode._CALL, p.op(5))
        assertEquals(2, p.a(5))
        // _SET_INT 的 b：模块内常量下标 0 → 绝对 0（base 常量池空），值 42。
        assertEquals(Opcode._SET_INT, p.op(3))
        assertEquals(42L, p.constant(p.b(3)))
        // _SET_STR 的 b：同上，字符串值逐位。
        assertEquals(Opcode._SET_STR, p.op(4))
        assertEquals("あ", p.string(p.b(4)))
        // 行号 rebase：模块内行 3 → 绝对 pc 5 的行号仍 3。
        assertEquals(3, p.lineOf(5))
        // 入口与 globalSlots 透传/放宽。
        assertEquals(0, p.entryFunction)
        assertEquals(10, p.globalSlots)
        // 函数窗口：X 绝对下标 6，entryPc = 2 + 9 = 11。
        assertEquals("X", p.functions[6].name)
        assertEquals(11, p.functions[6].entryPc)
        // catalog 胜者：无覆盖 → X 胜者 = 模块内定义本身。
        assertEquals(6, linked.catalog.winnerIndexOf("X"))
        // base-only golden 前提：只加新函数（无重名）→ dispatch 恒等。
        assertEquals(false, linked.hasOverrides)
        assertEquals(listOf("mod.a"), linked.moduleOrder)
    }

    @Test
    fun `外部导入回填最终胜者`() {
        val linked = linkAll(moduleA())
        // 导入表[0]=BASEFN，胜者 = base 的 BASEFN（物理下标 1，未被覆盖）。
        assertEquals(Opcode._CALL, linked.program.op(7))
        assertEquals(1, linked.program.a(7))
    }

    @Test
    fun `CALL_SUPER 回填前实现并置DIRECT`() {
        val linked = linkAll(moduleA(), moduleB())
        val p = linked.program
        // super 目标 = 前一同名实现（mod.a 的 X，物理下标 6），b 置 DIRECT 位。
        assertEquals(Opcode._CALL, p.op(12))
        assertEquals(6, p.a(12))
        assertEquals(CallSentinels.DIRECT_CALL_FLAG, p.b(12) and CallSentinels.DIRECT_CALL_FLAG)
        // catalog：X 的胜者是 BX（7），dispatch[6] = 7（非恒等）。
        assertEquals(7, linked.catalog.winnerIndexOf("X"))
        assertEquals(true, linked.hasOverrides)
        assertEquals(6, linked.catalog.superIndexOf(7))
        // 多模块链接序。
        assertEquals(listOf("mod.a", "mod.b"), linked.moduleOrder)
    }

    @Test
    fun `TRYCALL_SUPER 无前实现折NOP`() {
        val linked = linkAll(moduleA())
        val p = linked.program
        // ATRY 无前实现 → 整条折 _NOP（a/b/c 全 0）。
        assertEquals(Opcode._NOP, p.op(9))
        assertEquals(0, p.a(9))
        assertEquals(0, p.b(9))
        assertEquals(0, p.c(9))
    }

    @Test
    fun `base段零改写`() {
        val linked = linkAll(moduleA(), moduleB())
        val p = linked.program
        // base 段两条 _RET 原样（拼接不写 base 字）。
        assertEquals(Opcode._RET, p.op(0))
        assertEquals(Opcode._RET, p.op(1))
        assertEquals("ENTRY", p.functions[0].name)
        assertEquals(1, p.functions[1].entryPc)
    }

    // ------------------------------------------------------------------
    // 负向：fail-closed 判据
    // ------------------------------------------------------------------

    @Test
    fun `空模块集拒绝`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ModuleLinker.link(compileBase(), baseMetas, emptyList(), ResolverPolicy.MODERN)
        }
        assertTrue("base-only" in ex.message!!, ex.message)
    }

    @Test
    fun `覆盖base被签名闸拒绝`() {
        // base 候选签名恒空（完整签名导出落地前）→ MODERN 覆盖 base 一律拒，fail-closed。
        val b = CodeBuilder()
        b.declareFunction("BASEFN", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val prog = b.build(0)
        val covarier = TestModule(
            descriptor = ModuleDescriptor(packageId = "mod.d", overrideFunctions = setOf("BASEFN")),
            program = prog,
            functions = listOf(FuncMeta(index = 0, key = "BASEFN")),
            signatures = mapOf("BASEFN" to "sig-anything"),
        )
        val ex = assertFailsWith<OverrideSignatureException> {
            linkAll(covarier)
        }
        assertTrue("BASEFN" in ex.message!!, ex.message)
    }

    @Test
    fun `base元数据不对齐拒绝`() {
        val shortMetas = listOf(FuncMeta(index = 0, key = "ENTRY"))
        val ex = assertFailsWith<IllegalArgumentException> {
            ModuleLinker.link(compileBase(), shortMetas, listOf(moduleC()), ResolverPolicy.MODERN)
        }
        assertTrue("不同长" in ex.message!!, ex.message)
    }
}
