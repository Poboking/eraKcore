package com.eratw.port.core.patch

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ConstantPool
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.proc.FuncMeta
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [LinkableModule] 输入契约的形状钉（M2-1 单位 A）。
 *
 * 契约层测试：不读真实容器（M2-2 才落容器读写），用 [CodeBuilder] 真实编译
 * 零基线小模块产物 + 手工对齐的 [FuncMeta] 列表，逐条钉 [LinkableModule.validate]
 * 的 fail-closed 判据与签名查询的键口径。
 */
class LinkableModuleTest {

    /** 测试实现：直接持三样 + 签名表（键按契约大写归一）。 */
    private class TestModule(
        override val descriptor: ModuleDescriptor,
        override val program: Program,
        override val functions: List<FuncMeta>,
        private val signatures: Map<String, String> = emptyMap(),
    ) : LinkableModule {
        override fun signatureHashOf(functionKey: String): String =
            signatures[functionKey.uppercase(Locale.ROOT)] ?: ""
    }

    /** 编译一个单函数零基线模块产物（入口一条 RET），返回 Program 与函数下标。 */
    private fun compileOneFunctionModule(fnName: String): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction(fnName, b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    private fun module(
        pkg: String = "mod.a",
        program: Program = compileOneFunctionModule("MODFN"),
        metas: List<FuncMeta> = listOf(FuncMeta(index = 0, key = "MODFN")),
        signatures: Map<String, String> = emptyMap(),
    ): LinkableModule = TestModule(
        descriptor = ModuleDescriptor(packageId = pkg),
        program = program,
        functions = metas,
        signatures = signatures,
    )

    // ------------------------------------------------------------------
    // 正向：契约形状成立
    // ------------------------------------------------------------------

    @Test
    fun `零基线单函数模块通过形状校验`() {
        val m = module()
        LinkableModule.validate(m) // 不抛即通过
        assertEquals(0, m.program.codeBase)
        assertEquals(1, m.program.functions.size)
        assertEquals(1, m.functions.size)
    }

    @Test
    fun `签名查询未知键返空串`() {
        val m = module(signatures = mapOf("MODFN" to "sig-1"))
        assertEquals("sig-1", m.signatureHashOf("MODFN"))
        assertEquals("", m.signatureHashOf("NOT_EXPORTED"))
        // 空指纹 = MODERN 覆盖闸拒绝（fail-closed），不是异常 —— 契约钉。
        assertEquals("", m.signatureHashOf(""))
    }

    @Test
    fun `签名查询键大写归一`() {
        val m = module(signatures = mapOf("MODFN" to "sig-1"))
        // 查询键与 FuncMeta.key 同口径（大写）；小写键查询同样命中。
        assertEquals("sig-1", m.signatureHashOf("modfn"))
        assertEquals("sig-1", m.signatureHashOf("ModFn"))
    }

    // ------------------------------------------------------------------
    // 负向：形状违反逐条拒绝（fail-closed，消息带 packageId 定位）
    // ------------------------------------------------------------------

    @Test
    fun `六基线任一非零拒绝`() {
        // ProgramOverlay 的带基线补丁窗口不是链接器输入：六个基线逐一故意非零
        //（internal 构造器，同模块测试可用）。
        val variants: List<Pair<String, Program>> = listOf(
            "codeBase" to withBaseline(codeBase = 8),
            "statementBase" to withBaseline(statementBase = 1),
            "stringBase" to withBaseline(stringBase = 1),
            "longBase" to withBaseline(longBase = 1),
            "exprBase" to withBaseline(exprBase = 1),
            "functionBase" to withBaseline(functionBase = 1),
        )
        for ((which, program) in variants) {
            val ex = assertFailsWith<IllegalArgumentException> {
                LinkableModule.validate(module(program = program))
            }
            assertTrue(
                "不是零基线形态" in ex.message!! && "mod.a" in ex.message!!,
                "基线 $which 的报错应带判据词与 packageId：${ex.message}",
            )
        }
    }

    /** 编译零基线产物再按 internal 构造器改带基线（只动指定基线，其余 0）。 */
    private fun withBaseline(
        codeBase: Int = 0,
        statementBase: Int = 0,
        stringBase: Int = 0,
        longBase: Int = 0,
        exprBase: Int = 0,
        functionBase: Int = 0,
    ): Program {
        val zero = compileOneFunctionModule("MODFN")
        return Program(
            code = zero.code,
            lines = zero.lines,
            pool = ConstantPool(),
            functions = zero.functions,
            entryFunction = 0,
            globalSlots = 0,
            codeBase = codeBase,
            statementBase = statementBase,
            stringBase = stringBase,
            longBase = longBase,
            exprBase = exprBase,
            functionBase = functionBase,
        )
    }

    @Test
    fun `函数元数据与产物函数表不同长拒绝`() {
        // 多一条 FuncMeta（引用不存在的函数记录）。
        val metas = listOf(
            FuncMeta(index = 0, key = "MODFN"),
            FuncMeta(index = 1, key = "GHOST"),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            LinkableModule.validate(module(metas = metas))
        }
        assertTrue("不同长" in ex.message!! && "mod.a" in ex.message!!, ex.message)
    }

    @Test
    fun `下标非零基拒绝`() {
        // 起头不是 0：模块内下标从 1 起（编译侧 base 没清零的病态产物）。
        val ex = assertFailsWith<IllegalArgumentException> {
            LinkableModule.validate(
                module(metas = listOf(FuncMeta(index = 1, key = "MODFN"))),
            )
        }
        assertTrue("0 基稠密下标" in ex.message!!, ex.message)
    }

    @Test
    fun `下标中间空洞拒绝`() {
        // 0 存在、1 空洞、2 占位：改写成绝对下标后窗口重叠。
        val program = compileOneFunctionModule("MODFN")
        // 单函数产物配 3 条 meta 需要产物函数表同长 —— 改用 3 函数产物对齐。
        val b = CodeBuilder()
        val f0 = b.declareFunction("F0", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val f1 = b.declareFunction("F1", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 1)
        val f2 = b.declareFunction("F2", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 2)
        val three = b.build(f0)
        assertEquals(3, three.functions.size)
        // 中间那条故意把模块内下标写成 2（空洞在 1）。
        val metas = listOf(
            FuncMeta(index = 0, key = "F0"),
            FuncMeta(index = 2, key = "F2"),
            FuncMeta(index = 2, key = "F2_ALT"),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            LinkableModule.validate(module(program = three, metas = metas))
        }
        assertTrue("0 基稠密下标" in ex.message!!, ex.message)
    }
}
