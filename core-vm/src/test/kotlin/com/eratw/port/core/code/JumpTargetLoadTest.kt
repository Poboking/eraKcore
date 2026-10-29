package com.eratw.port.core.code

import com.eratw.port.core.loader.ErbcLoadException
import com.eratw.port.core.loader.ProgramLoader
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CMP-55 夹具①：`_JUMP` 家族的函数归属/越界必须在**装载期**拒绝。
 *
 * 现状（改前）：装载期 `validate` 完全没有跳转分支 ⇒ `_JUMP a=1` 从 pc0 落到
 * 另一个函数的首指令，装载零抛出、运行期静默执行错函数体（本文件用
 * `LEAK-FROM-OTHER` 作证）；`a=4` 越过指令流末端，只在 VM 跑到时才抛
 * 「指令指针越界」。
 *
 * 改后：`ErbcReader.requireJumpTargets` 在装载期逐条判「目标落在当前函数
 * `[entryPc, 下一个 entryPc)` 内」，`ProgramLoader.load(bytes, source)` 把
 * [ErbcDecodeException] 收口成 [ErbcLoadException]。
 *
 * 反向可证：合法前向 `_JUMP`、合法 `_JUMP_IF_FALSE`、合法 `_JUMP_DYN`（标签在
 * 本函数内）都必须照常装载并执行；守卫不许把编译器产物误杀。
 */
class JumpTargetLoadTest {

    /** 只收输出的最简宿主。 */
    private class Probe : VmHost {
        override val evalContext: com.eratw.port.core.expr.EvalContext? = null
        val output = StringBuilder()

        override fun print(text: String) {
            output.append(text)
        }
    }

    private companion object {
        /** 夹具一里 `_JUMP` 所在的 pc。 */
        const val JUMP_PC = 0

        /** `_PRINT_INT` / `_PRINT_LITERAL` 的标志位：bit0 = 末尾换行。 */
        const val FLAG_NEWLINE = 1
    }

    /**
     * 两函数夹具：
     * ```
     * main  (pc0..1): _JUMP a=?        ; pc0，a 由被测用例回填
     *                 _HALT            ; pc1
     * other (pc2..3): _PRINT_LITERAL "LEAK-FROM-OTHER"
     *                 _HALT            ; 跨函数跳的目标就是 other 的首指令
     * ```
     */
    private fun crossFunctionBytes(): ByteArray {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._JUMP, 1, 0, 0, 1) // pc0：a=1 ⇒ 目标 pc2 = other 首指令
        b.emit(Opcode._HALT, 0, 0, 0, 2) // pc1
        b.declareFunction("other", b.here(), 0, 1) // entryPc = 2
        b.emit(Opcode._PRINT_LITERAL, b.string("LEAK-FROM-OTHER"), FLAG_NEWLINE, 0, 3)
        b.emit(Opcode._HALT, 0, 0, 0, 4)
        return ErbcWriter.encode(b.build(main))
    }

    /** 回填 `a` 操作数：`offs[SECTION_CODE] + 4（instructionCount）+ pc*16 + operand*4`。 */
    private fun patchOperand(
        bytes: ByteArray,
        pc: Int,
        operand: Int,
        value: Int,
    ): ByteArray {
        val out = bytes.copyOf()
        val code = ErbcReader.probe(out).offsets[ErbcFormat.SECTION_CODE]
        val at = code + 4 + pc * Instruction.WORDS * 4 + operand * 4
        writeLe32(out, at, value)
        return out
    }

    private fun writeLe32(
        bytes: ByteArray,
        at: Int,
        value: Int,
    ) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[at + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    // ------------------------------------------------------------------
    // 红：跨函数 / 越界跳转装载期拒绝
    // ------------------------------------------------------------------

    @Test
    fun `夹具一 跨函数 _JUMP 装载期拒绝而不是静默执行错函数体`() {
        val bytes = patchOperand(crossFunctionBytes(), JUMP_PC, 1, 1)
        val program = try {
            ProgramLoader.load(bytes, "jump-cross-fn")
        } catch (e: ErbcLoadException) {
            val message = e.message.orEmpty()
            assertTrue("_JUMP" in message, "装载期错误必须点名 _JUMP，实际：$message")
            assertTrue("main" in message, "文案须指出所属函数，实际：$message")
            return
        }
        // 红形：装载零抛出 ⇒ 运行期静默跳到 other 的首指令。
        val probe = Probe()
        VirtualMachine(program, probe).run()
        fail(
            "装载期必须拒绝跨函数 _JUMP（期望 ErbcLoadException）；" +
                "当前静默执行到另一函数体，输出=[${probe.output}]",
        )
    }

    @Test
    fun `夹具一 _JUMP 偏移越过指令流末端装载期拒绝`() {
        val bytes = patchOperand(crossFunctionBytes(), JUMP_PC, 1, 4) // 目标 pc5 ≥ 指令数 4
        val e = assertFailsWith<ErbcLoadException> { ProgramLoader.load(bytes, "jump-oob") }
        assertTrue("_JUMP" in e.message.orEmpty(), "装载期错误必须点名 _JUMP，实际：${e.message}")
    }

    @Test
    fun `_JUMP_IF_FALSE 跨函数同样装载期拒绝`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(0L), 0, 1)
        // pc1：b=1 ⇒ 目标 1+1+1 = 3 = other 首指令
        b.emit(Opcode._JUMP_IF_FALSE, SlotAddr.local(0), 1, 0, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)
        b.declareFunction("other", b.here(), 0, 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("LEAK-FROM-OTHER"), FLAG_NEWLINE, 0, 4)
        b.emit(Opcode._HALT, 0, 0, 0, 5)

        val e = assertFailsWith<ErbcLoadException> {
            ProgramLoader.load(ErbcWriter.encode(b.build(main)), "if-cross-fn")
        }
        assertTrue("_JUMP_IF_FALSE" in e.message.orEmpty(), "文案须点名 _JUMP_IF_FALSE，实际：${e.message}")
    }

    @Test
    fun `_JUMP_DYN 标签下标越界装载期拒绝`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._JUMP_DYN, 0, 0, 0, 1) // 函数没有任何标签 ⇒ 下标 0 已越界
        b.emit(Opcode._HALT, 0, 0, 0, 2)

        val e = assertFailsWith<ErbcLoadException> {
            ProgramLoader.load(ErbcWriter.encode(b.build(main)), "jump-dyn")
        }
        assertTrue("_JUMP_DYN" in e.message.orEmpty(), "文案须点名 _JUMP_DYN，实际：${e.message}")
    }

    // ------------------------------------------------------------------
    // 绿（反向用例）：合法跳转不得误报
    // ------------------------------------------------------------------

    @Test
    fun `合法函数内前向 _JUMP 照常装载并执行`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._JUMP, 1, 0, 0, 1) // pc0 → pc2
        b.emit(Opcode._HALT, 0, 0, 0, 2) // pc1：跳过
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(41L), 0, 3)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(0), FLAG_NEWLINE, 0, 4)
        b.emit(Opcode._HALT, 0, 0, 0, 5)

        val program = ProgramLoader.load(ErbcWriter.encode(b.build(main)), "jump-legal")
        val host = StringHost()
        assertEquals(0L, VirtualMachine(program, host).run())
        assertEquals("41\n", host.output.toString(), "合法跳转的目标必须照常执行")
    }

    @Test
    fun `合法 _JUMP_IF_FALSE 照常装载并执行`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(0L), 0, 1)
        b.emit(Opcode._JUMP_IF_FALSE, SlotAddr.local(0), 1, 0, 2) // 条件假 ⇒ pc3
        b.emit(Opcode._PRINT_LITERAL, b.string("MUST-NOT-RUN"), FLAG_NEWLINE, 0, 3)
        b.emit(Opcode._PRINT_LITERAL, b.string("TAKEN"), FLAG_NEWLINE, 0, 4)
        b.emit(Opcode._HALT, 0, 0, 0, 5)

        val program = ProgramLoader.load(ErbcWriter.encode(b.build(main)), "if-legal")
        val host = StringHost()
        assertEquals(0L, VirtualMachine(program, host).run())
        assertEquals("TAKEN\n", host.output.toString(), "条件假必须跳到本函数内的目标")
    }

    @Test
    fun `合法 _JUMP_DYN 按本函数标签表照常装载并执行`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._JUMP_DYN, 0, 0, 0, 1) // pc0 → 标签 L
        b.emit(Opcode._PRINT_LITERAL, b.string("MUST-NOT-RUN"), FLAG_NEWLINE, 0, 2)
        val target = b.here() // pc2 = L
        b.emit(Opcode._PRINT_LITERAL, b.string("DYN-TAKEN"), FLAG_NEWLINE, 0, 3)
        b.emit(Opcode._HALT, 0, 0, 0, 4)
        b.declareLabel(main, "L", target)

        val program = ProgramLoader.load(ErbcWriter.encode(b.build(main)), "jump-dyn-legal")
        val host = StringHost()
        assertEquals(0L, VirtualMachine(program, host).run())
        assertEquals("DYN-TAKEN\n", host.output.toString(), "合法标签跳转必须照常执行")
    }

    // ------------------------------------------------------------------
    // 栈码条件跳（T2-21）：同一档归属判据
    // ------------------------------------------------------------------

    /**
     * `_STK_JZ_POP` / `_STK_JNZ_POP` / `_STK_JZ_KEEP` 的偏移在 `a`、相对**下一条**指令，
     * 与 `_JUMP_IF_*`（偏移在 `b`）是同一个「函数内跳转」语义。
     *
     * 它们必须进 [ErbcReader.requireJumpTargets] 的判据：漏掉就是 CMP-55 要防的那件事
     * —— 栈码跳进**别的函数体**，装载零抛出、运行期静默执行错函数（调用栈返回点留在
     * 另一个函数里）。这条用例就是那条漏网路径的钉子。
     */
    @Test
    fun `_STK_JZ_POP 跨函数装载期拒绝`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        // pc0：a=1 ⇒ 目标 0+1+1 = 2 = other 首指令
        b.emit(Opcode._STK_JZ_POP, 1, 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        b.declareFunction("other", b.here(), 0, 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("LEAK-FROM-OTHER"), FLAG_NEWLINE, 0, 3)
        b.emit(Opcode._HALT, 0, 0, 0, 4)

        val e = assertFailsWith<ErbcLoadException> {
            ProgramLoader.load(ErbcWriter.encode(b.build(main)), "stk-jz-cross-fn")
        }
        assertTrue("_STK_JZ_POP" in e.message.orEmpty(), "文案须点名 _STK_JZ_POP，实际：${e.message}")
        assertTrue("main" in e.message.orEmpty(), "文案须指出所属函数，实际：${e.message}")
    }

    @Test
    fun `合法 _STK_JZ_POP 照常装载并执行`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._STK_PUSH_IMM, b.constant(0L), 0, 0, 1) // pc0：压 0
        b.emit(Opcode._STK_JZ_POP, 1, 0, 0, 2) // pc1：为 0 ⇒ 压 0 并跳 pc3
        b.emit(Opcode._PRINT_LITERAL, b.string("MUST-NOT-RUN"), FLAG_NEWLINE, 0, 3)
        b.emit(Opcode._PRINT_LITERAL, b.string("STK-TAKEN"), FLAG_NEWLINE, 0, 4)
        b.emit(Opcode._HALT, 0, 0, 0, 5)

        val program = ProgramLoader.load(ErbcWriter.encode(b.build(main)), "stk-jz-legal")
        val host = StringHost()
        assertEquals(0L, VirtualMachine(program, host).run())
        assertEquals("STK-TAKEN\n", host.output.toString(), "函数内前向栈码跳转必须照常执行")
    }

    // 映射路（ProgramLoader.loadMapped = 生产装载路）
    //
    // CMP-55 收口时只接了字节路：`ErbcStreamDecoder.validate` 从不调用
    // `ErbcReader.requireJumpTargets` ⇒ 同一份跨函数跳产物，字节路装载期拒绝、映射路
    // 零抛出。2026-10-26 补 `MappedFunctionTable.JumpScan`（不物化 FunctionInfo 的
    // 区间扫描）。下面四个用例把**同一份字节**同时喂两条路：拒绝要一致、文案要逐字相同，
    // 合法跳转在映射路上也必须照常执行。
    // ------------------------------------------------------------------

    /** 取 loader 包装**之前**的格式层原文（`withSource` 只加来源前缀，cause 保留原句）。 */
    private fun rawFormatMessage(e: Throwable): String {
        var cur: Throwable = e
        while (true) {
            val next = cur.cause ?: return cur.message.orEmpty()
            cur = next
        }
    }

    /** 同一份字节走两条装载路，返回各自的格式层原文；任一路没抛出即 fail。 */
    private fun refuseBoth(
        bytes: ByteArray,
        dir: Path,
        what: String,
    ): Pair<String, String> {
        val byteMsg =
            try {
                ProgramLoader.load(bytes, "jump-fixture")
                fail("$what：字节路必须拒绝该产物")
            } catch (e: ErbcLoadException) {
                rawFormatMessage(e)
            }
        val file = dir.resolve("jump-${what.hashCode().toUInt()}.erbc")
        Files.write(file, bytes)
        val mappedMsg =
            try {
                ProgramLoader.loadMapped(file)
                fail("$what：映射路必须拒绝该产物")
            } catch (e: ErbcLoadException) {
                rawFormatMessage(e)
            }
        return byteMsg to mappedMsg
    }

    @Test
    fun `映射路 跨函数 _JUMP 装载期拒绝且文案与字节路逐字相同`(@TempDir dir: Path) {
        val bytes = patchOperand(crossFunctionBytes(), JUMP_PC, 1, 1)
        val (byteMsg, mappedMsg) = refuseBoth(bytes, dir, "cross-fn")
        assertTrue("跳转目标" in mappedMsg && "main" in mappedMsg, "映射路必须点名目标与所属函数：$mappedMsg")
        assertEquals(byteMsg, mappedMsg, "同一份产物两条装载路的跳转文案必须逐字相同")
    }

    @Test
    fun `映射路 _JUMP 越过指令流末端装载期拒绝`(@TempDir dir: Path) {
        val bytes = patchOperand(crossFunctionBytes(), JUMP_PC, 1, 4) // 目标 pc5 ≥ 指令数 4
        val (byteMsg, mappedMsg) = refuseBoth(bytes, dir, "oob")
        assertEquals(byteMsg, mappedMsg, "越界跳的文案必须与字节路逐字相同")
    }

    @Test
    fun `映射路 _JUMP_DYN 标签下标越界装载期拒绝且文案逐字相同`(@TempDir dir: Path) {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._JUMP_DYN, 0, 0, 0, 1) // 函数没有任何标签 ⇒ 下标 0 已越界
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        val (byteMsg, mappedMsg) = refuseBoth(ErbcWriter.encode(b.build(main)), dir, "dyn")
        assertTrue("_JUMP_DYN" in mappedMsg, "映射路必须点名 _JUMP_DYN：$mappedMsg")
        assertEquals(byteMsg, mappedMsg, "标签下标越界的文案必须与字节路逐字相同")
    }

    @Test
    fun `映射路合法跳转照常装载并执行`(@TempDir dir: Path) {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._JUMP, 1, 0, 0, 1) // pc0 → pc2
        b.emit(Opcode._PRINT_LITERAL, b.string("MUST-NOT-RUN"), FLAG_NEWLINE, 0, 2)
        b.emit(Opcode._JUMP_DYN, 0, 0, 0, 3) // pc2 → 标签 L
        b.emit(Opcode._PRINT_LITERAL, b.string("MUST-NOT-RUN-2"), FLAG_NEWLINE, 0, 4)
        val target = b.here() // pc4 = L
        b.emit(Opcode._PRINT_LITERAL, b.string("MAPPED-OK"), FLAG_NEWLINE, 0, 5)
        b.emit(Opcode._HALT, 0, 0, 0, 6)
        b.declareLabel(main, "L", target)

        val file = dir.resolve("jump-legal.erbc")
        Files.write(file, ErbcWriter.encode(b.build(main)))
        val program = ProgramLoader.loadMapped(file)
        val host = StringHost()
        assertEquals(0L, VirtualMachine(program, host).run())
        assertEquals("MAPPED-OK\n", host.output.toString(), "映射路不得误杀合法前向跳与合法标签跳")
    }
}
