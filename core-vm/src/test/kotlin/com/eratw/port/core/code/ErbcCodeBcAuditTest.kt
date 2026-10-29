package com.eratw.port.core.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * deep-code-bc 路深审：`core/code` 字节码三处缺陷回归。
 *
 * 1. **读侧不判操作码范围**（`ErbcReader.kt` / `ErbcFormat.kt`）：字节路 `validate` 只判池/函数/pc
 *    下标，`op` 原样放过。一条 `op=500` 的脏指令流被**静默装载**，直到 VM 跑到那条才报
 *    「语句 <op:500> 尚未实现」—— 运行期文案与真因（产物损坏）无关，装载层的畸形收口
 *    （`BootFailures.classify`）也接不住（规约 §1.6「禁止静默降级」）。
 *    ⇒ [ErbcFormat.isValidOpcode] + `ErbcReader.needsStatementPool` 的**单一咽喉**：
 *    它是字节路与映射路（`ErbcStreamDecoder.validate`，逐字副本）唯一的共同调用点，
 *    单边加判会立刻让两条路径对同一份损坏产物给出不同结论（`ErbcStreamEquivalenceTest` 锁死）。
 * 2. **汇编器 `.end` 之后的隐式 main 事故**（`Assembler.kt`）：`implicitMain` 布尔在 EOF 用
 *    **最后那个函数**的 `funcMaxSlot` 回填 index 0 的帧大小（静默污染前一函数的槽数）；
 *    `.func main` + `.end` 再写指令还会撞 `CodeBuilder.declareFunction` 的重复声明
 *    `IllegalArgumentException`。⇒ 隐式 main 只在「文本未出现任何 `.func`/`.end`」时成立，
 *    其余当场 `AssemblyException`。
 * 3. **函数内 `$标签` 表不随反汇编往返**（`Disassembler.kt` / `Assembler.kt`）：`_JUMP_DYN` 的
 *    a 是 [FunctionInfo.labelPcs] 的下标，旧反汇编只打 `.func` + 指令 ⇒ `asm(disasm(p))`
 *    把 `_JUMP_DYN` 指向空表（指令字虽同，运行期跳到不存在的标签）。⇒ 反汇编随 `.func`
 *    头打回 `.label "<名>" <pc>`，汇编器原样登记。
 *
 * 附：写侧**故意**不判跳转归属（CMP-55 的判据是装载期的事）—— `JumpTargetLoadTest` 正是
 * 用 `ErbcWriter.encode` 伪造跨函数跳转容器来验读侧拒载，写侧加判会把那条夹具打死。
 */
class ErbcCodeBcAuditTest {

    // ------------------------------------------------------------------
    // 1：读侧操作码范围
    // ------------------------------------------------------------------

    /** 把一个程序的第 0 条指令的 op 改成 [op] 后编码（绕开汇编器，模拟损坏产物）。 */
    private fun bytesWithOp0(op: Int): ByteArray {
        val src = Assembler.assemble("_HALT")
        val code = src.code.copyOf()
        code[0] = op
        val tampered = Program(
            code = code,
            lines = src.lines.copyOf(),
            pool = ConstantPool(),
            functions = src.functions,
            entryFunction = src.entryFunction,
            globalSlots = src.globalSlots,
        )
        return ErbcWriter.encode(tampered)
    }

    @Test
    fun `越界操作码在装载期当场拒载而不是留给 VM`() {
        // 202..243 自 T2-21 起是合法栈码组（池引用越界由 requireStackOpPool 另行拒载，
        // 见 StackOpcodeTest 的池校验用例），非法域从栈码组末条 + 1 开始。
        for (op in listOf(Opcode._STK_POP_SLOT_S + 1, 500, -1, Int.MIN_VALUE)) {
            val e = assertFailsWith<ErbcDecodeException>("op=$op 应被拒载") {
                ErbcReader.decode(bytesWithOp0(op))
            }
            assertTrue(
                e.message!!.contains("操作码") && e.message!!.contains("$op"),
                "报错要点名操作码与真值，实际: ${e.message}",
            )
        }
        // 合法上界 243（栈码组末条 _STK_POP_SLOT_S）必须放行；栈码组首末条都要合法。
        assertEquals(Opcode._STK_POP_SLOT_S, ErbcFormat.OPCODE_MAX, "上界 = 栈码组末条")
        assertTrue(ErbcFormat.isValidOpcode(0))
        assertTrue(ErbcFormat.isValidOpcode(Opcode.MAX_STATEMENT_OPCODE))
        assertTrue(ErbcFormat.isValidOpcode(Opcode._RETS))
        assertTrue(ErbcFormat.isValidOpcode(Opcode._STK_PUSH_IMM), "栈码组首条 202 必须合法")
        assertTrue(ErbcFormat.isValidOpcode(Opcode._STK_POP_SLOT_S), "栈码组末条 243 必须合法")
        assertTrue(!ErbcFormat.isValidOpcode(Opcode._STK_POP_SLOT_S + 1))
        assertTrue(!ErbcFormat.isValidOpcode(-1))
    }

    // ------------------------------------------------------------------
    // 2：汇编器隐式 main / `.end`
    // ------------------------------------------------------------------

    @Test
    fun `end 之后再写指令显式失败而不是静默污染前一函数`() {
        val e = assertFailsWith<AssemblyException> {
            Assembler.assemble(
                """
                .func a slots=1
                  _HALT
                .end
                  _NOP
                """.trimIndent(),
            )
        }
        assertTrue(e.message!!.contains("函数之外"), "实际文案: ${e.message}")

        // 纯隐式 main 之后 `.end` 再写指令，同样失败（旧实现会撞重复声明 IllegalArgumentException）。
        val e2 = assertFailsWith<AssemblyException> {
            Assembler.assemble(
                """
                _NOP
                .end
                _HALT
                """.trimIndent(),
            )
        }
        assertTrue(e2.message!!.contains("函数之外"), "实际文案: ${e2.message}")
    }

    @Test
    fun `隐式 main 与后续显式函数的帧大小各自独立不受污染`() {
        // 旧实现：EOF 用最后那个函数（x, slots=10）的 funcMaxSlot 回填 index 0 的帧大小
        // ⇒ 只用 0 个槽的隐式 main 被静默写成 10。
        val p = Assembler.assemble(
            """
            _NOP
            .func x slots=10
              _HALT
            """.trimIndent(),
        )
        assertEquals(listOf("main", "x"), p.functions.map { it.name })
        assertEquals(0, p.functions[0].slotCount, "隐式 main 的帧大小不得被后续函数污染")
        assertEquals(10, p.functions[1].slotCount)

        // 纯隐式 main 的帧大小仍按自身用量回填（原语义不变）。
        val q = Assembler.assemble("_SET_INT R5, 1\n_HALT")
        assertEquals(6, q.functions.single().slotCount, "隐式 main 帧大小 = 最大局部槽 + 1")
    }

    // ------------------------------------------------------------------
    // 3：函数内标签表随反汇编往返
    // ------------------------------------------------------------------

    @Test
    fun `函数内标签表随反汇编往返且 _JUMP_DYN 的 a 不漂`() {
        val p1 = Assembler.assemble(
            """
            .func main slots=4
              _NOP
              _JUMP_DYN 1
              _NOP
              _HALT
            .label "TAIL" 3
            .label "NEXT LABEL" 1
            """.trimIndent(),
        )
        assertEquals(listOf("TAIL", "NEXT LABEL"), p1.functions[0].labelNames)
        assertEquals(listOf(3, 1), p1.functions[0].labelPcs)

        val text = p1.disassemble()
        assertTrue(text.contains(".label \"TAIL\" 3"), "反汇编未打回标签 TAIL:\n$text")
        assertTrue(text.contains(".label \"NEXT LABEL\" 1"), "带空格标签名未打回:\n$text")

        val p2 = Assembler.assemble(text, p1.expressions(), p1.statements())
        assertEquals(p1.functions[0].labelNames, p2.functions[0].labelNames, "标签名表往返漂移")
        assertEquals(p1.functions[0].labelPcs, p2.functions[0].labelPcs, "标签 pc 表往返漂移")
        // `_JUMP_DYN` 的 a = 标签下标：整表复原后 a 仍指向 NEXT LABEL（pc=1）。
        assertEquals(1, p2.a(1), "_JUMP_DYN 的 a 必须是标签下标")
        assertEquals(1, p2.functions[0].labelPcs[p2.a(1)])
        assertEquals(p1.code.toList(), p2.code.toList())
    }

    @Test
    fun `无标签函数的反汇编不产生 label 行`() {
        val p = Assembler.assemble(".func main slots=1\n  _HALT")
        assertTrue(!p.disassemble().contains(".label"), "无标签函数不得产生 .label 行")
        // `.label` 必须在函数体内：函数外出现即显式失败。
        val e = assertFailsWith<AssemblyException> { Assembler.assemble(".label \"X\" 0") }
        assertTrue(e.message!!.contains("`.label` 必须出现在"), "实际文案: ${e.message}")
    }
}