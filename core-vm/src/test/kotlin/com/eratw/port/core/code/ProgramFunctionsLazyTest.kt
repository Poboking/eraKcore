package com.eratw.port.core.code

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `FunctionInfo` 的 `$标签` 平行表**惰性化**（w7-progfuncs）。
 *
 * 钉死三件事：
 * 1. **空表口径不变**：没登记过标签的函数，`labelNames`/`labelPcs` 仍可读、仍为空、
 *    越界下标仍抛 IndexOutOfBoundsException（旧实现是空 `ArrayList`，语义同此）；
 *    区别只在于它们是**共享空表单例**，不再每个函数各带两个空 `ArrayList` 壳。
 * 2. **VM 读函数表行为不变**：`_JUMP_DYN` 在带标签函数上按表跳转；在无标签函数上
 *    仍按「下标越界」抛 CodeEE（`Vm.kt:469-474`，本次未动 Vm）。
 * 3. **装载后再编码逐字节相同**：带标签 / 不带标签两种形态经 `ErbcWriter` →
 *    `ErbcReader` → `ErbcWriter` 后字节完全一致（`ErbcWriter.kt:310` 写出的
 *    「标签数 = 0」不受惰性化影响）。
 *
 * 语义依据：原版标签结构按声明增量登记 —— `LabelDictionary.cs:346`
 * `labelDollarList.TryAdd(...)` 只在函数真的出现 `$标签` 时建条目。
 */
class ProgramFunctionsLazyTest {

    /**
     * 三函数夹具：`withLabel`（下标 0，带一个 `$L` 指向 pc 0）+ `plain1`/`plain2`
     * （无标签，对应全量语料里占绝大多数的无 `$` 标签函数）。
     */
    private fun fixture(): Pair<CodeBuilder, Program> {
        val b = CodeBuilder()
        val main = b.declareFunction("withLabel", 0, 0, 1)
        b.emit(Opcode._HALT)
        b.declareFunction("plain1", b.here(), 0, 0)
        b.emit(Opcode._HALT)
        b.declareFunction("plain2", b.here(), 0, 0)
        b.emit(Opcode._HALT)
        b.declareLabel(main, "L", 0)
        return b to b.build(0)
    }

    // ------------------------------------------------------------------
    // 1) 空表：共享实例 + 读面口径
    // ------------------------------------------------------------------

    @Test
    fun `无标签函数的标签表是共享空表单例而非各自空壳`() {
        val (_, program) = fixture()
        val plain1 = program.functions[1]
        val plain2 = program.functions[2]

        assertSame(emptyList<String>(), plain1.labelNames, "空标签名表必须是共享空表单例")
        assertSame(emptyList<Int>(), plain1.labelPcs, "空标签 pc 表必须是共享空表单例")
        assertSame(plain1.labelNames, plain2.labelNames, "两个无标签函数必须共用同一实例（不再各带一个空壳）")
        assertSame(plain1.labelPcs, plain2.labelPcs)
        assertEquals(0, plain1.labelCount)
    }

    @Test
    fun `空标签表的元素读与空 ArrayList 口径一致`() {
        val (_, program) = fixture()
        val plain = program.functions[1]

        assertTrue(plain.labelNames.isEmpty())
        assertEquals(0, plain.labelNames.size)
        assertEquals(0, plain.labelPcs.size)
        assertEquals(-1, plain.labelNames.indexOf("L"), "空表按名查标签必须返回 -1")
        assertTrue(plain.labelNames.toList().isEmpty())
        assertTrue(plain.labelPcs.toList().isEmpty())
        // 旧实现是空 ArrayList：读 [0] 抛 IndexOutOfBoundsException。惰性化后同一异常。
        assertFailsWith<IndexOutOfBoundsException> { plain.labelNames[0] }
        assertFailsWith<IndexOutOfBoundsException> { plain.labelPcs[0] }
    }

    // ------------------------------------------------------------------
    // 2) 登记过标签：两表平行、下标/名字/pc 可读、同名幂等更新
    // ------------------------------------------------------------------

    @Test
    fun `登记过标签的函数两表平行且按下标可读`() {
        val (_, program) = fixture()
        val f = program.functions[0]

        assertEquals(1, f.labelCount)
        assertEquals(listOf("L"), f.labelNames, "名字大写归一（declareLabel 口径）")
        assertEquals(listOf(0), f.labelPcs)
        assertEquals(0, f.labelPcs[f.labelNames.indexOf("L")], "按名查下标后取 pc")
    }

    @Test
    fun `同名标签重复声明只更新 pc 不新增条目`() {
        val b = CodeBuilder()
        val idx = b.declareFunction("f", 0, 0, 1)
        b.emit(Opcode._HALT)
        b.emit(Opcode._HALT)

        assertEquals(0, b.declareLabel(idx, "loop", 0), "首次登记返回 0")
        assertEquals(0, b.declareLabel(idx, "LOOP", 1), "同名（大小写归一）返回同一小标")
        val program = b.build(0)
        val f = program.functions[0]

        assertEquals(1, f.labelCount, "同名只更新 pc，不新增条目")
        assertEquals(listOf("LOOP"), f.labelNames)
        assertEquals(listOf(1), f.labelPcs, "pc 被更新为最后一次登记值")
    }

    // ------------------------------------------------------------------
    // 3) VM 读函数表：带标签按表跳转；无标签仍报越界 CodeEE
    // ------------------------------------------------------------------

    @Test
    fun `VM 的 _JUMP_DYN 在带标签函数上按标签表跳转`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._JUMP_DYN, 0) // pc0：跳到标签 L
        b.emit(Opcode._SET_INT, 0, b.constant(99L)) // pc1：不应被执行
        val target = b.here() // pc2 = L
        b.emit(Opcode._SET_INT, 0, b.constant(7L))
        b.emit(Opcode._RET, 0)
        b.declareLabel(main, "L", target)

        assertEquals(7L, VirtualMachine(b.build(0), StringHost()).run())
    }

    @Test
    fun `VM 的 _JUMP_DYN 在无标签函数上仍抛标签下标越界 CodeEE`() {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._JUMP_DYN, 0)
        b.emit(Opcode._HALT)

        val e = assertFailsWith<CodeEE> { VirtualMachine(b.build(0), StringHost()).run() }
        assertTrue(
            e.message.orEmpty().contains("标签下标越界(0)"),
            "期望 CodeEE 文案含「标签下标越界(0)」，实际：${e.message}",
        )
    }

    // ------------------------------------------------------------------
    // 4) 装载后再编码逐字节相同 + 装载侧同样是共享空表
    // ------------------------------------------------------------------

    @Test
    fun `装载后再编码逐字节相同且装载出的无标签函数仍共享空表`() {
        val (_, program) = fixture()

        val bytes = ErbcWriter.encode(program)
        val decoded = ErbcReader.decode(bytes)
        val again = ErbcWriter.encode(decoded)

        assertContentEquals(bytes, again, "装载后再编码必须逐字节相同")
        assertEquals(listOf("L"), decoded.functions[0].labelNames, "带标签函数的名字表原样复原")
        assertEquals(listOf(0), decoded.functions[0].labelPcs)
        assertSame(
            decoded.functions[1].labelNames,
            decoded.functions[2].labelNames,
            "装载侧无标签函数也不得各分配一个空壳",
        )
        assertEquals(0, decoded.functions[1].labelCount)
        assertEquals(0, decoded.functions[2].labelPcs.size)
    }

    // ------------------------------------------------------------------
    // 5) Program 的公开读面不受影响
    // ------------------------------------------------------------------

    @Test
    fun `Program 按名查函数与形参名的公开语义不变`() {
        val (_, program) = fixture()

        assertSame(program.functions[1], program.function("plain1"))
        assertEquals(null, program.function("nope"))
        assertEquals(emptyList<String>(), program.paramNames(1), "未喂形参名 = 空表（既有口径）")
        assertEquals(emptyList<String>(), program.paramNames(program.functions.size), "越界下标 = 空表")
        assertEquals(false, program.bindsCallArgsPositionally)
    }
}
