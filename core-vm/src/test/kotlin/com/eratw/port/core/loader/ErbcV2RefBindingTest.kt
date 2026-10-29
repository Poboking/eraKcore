package com.eratw.port.core.loader

import com.eratw.port.core.code.CallArgSource
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * **真机 `.erbc` 路径上的 REF 形参绑定**：编译 → v2 容器 → 装载 → 运行。
 *
 * 背景：编译期把调用点实参元数据放进 `Program.callArgs`、头部形参名放进
 * `FunctionInfo.paramNames`（`Program.kt:9-30`，spec 出处见该处 KDoc）；v1 容器没有承载
 * 它们的段，装载后逐位绑定失效（`ProcessRuntime.kt:547-551` 退回类型桶）。
 * 本文件证明 v2 段 7/8 把这条链路接上：**同一个 REF 写穿用例在 `.erbc` 往返后仍然写穿**，
 * 而把产物降级成 v1 时退回历史行为（`EmptyRefVar`，宁可不绑也不猜）。
 *
 * 装置形态与 `core/compile/RefArgBindingTest` 第 1 例同源（那一路只测内存 Program，
 * 本文件测「产物 → 装载」这一段，两者合起来覆盖 REF 的完整链路）。
 */
class ErbcV2RefBindingTest {

    // ------------------------------------------------------------------
    // 1. v2 产物装载后 REF 仍然写穿调用方数组
    // ------------------------------------------------------------------

    @Test
    fun `v2 产物装载后 REF 形参绑定调用方数组本体`() {
        val pb = compile(
            Fn("MAIN", 1, arrayOf("@MAIN(SRC_ARR)", "CALL TGT_WRITER(SRC_ARR)", "RETURN")),
            Fn(
                "TGT_WRITER", 1,
                arrayOf("@TGT_WRITER(REF_TGT)", "#DIM REF REF_TGT", "REF_TGT:3 = 4242", "RETURN"),
                params = listOf("REF_TGT"),
            ),
        )
        val bytes = ErbcWriter.encode(pb.program)
        val loaded = ProgramLoader.load(bytes)

        assertEquals(ErbcFormat.VERSION_2, ErbcReader.probe(bytes).version)
        assertEquals(CallArgSource.ERBC_V2, loaded.callArgSource)
        assertTrue(loaded.bindsCallArgsPositionally, "v2 装载后逐位绑定必须可用")
        assertEquals(listOf("REF_TGT"), loaded.paramNames(pb.index("TGT_WRITER")), "头部形参名随段 8 回来")
        val callPc = (0 until loaded.instructionCount).first { loaded.op(it) == Opcode._CALL }
        assertEquals(pb.program.callArgsAt(callPc), loaded.callArgsAt(callPc), "调用点实参表逐字段相等")

        // 以装载产物重建运行期：这一步没有别的信息来源，绑不绑得对只取决于 v2 段 7/8
        val store = store()
        run(pb, loaded, store)
        assertEquals(0, store.callDepth(), "帧必须全部退出")
        store.enterFunction("MAIN")
        assertEquals(
            4242L, store.readInt("SRC_ARR", longArrayOf(3)),
            "REF 必须是别名（ReferenceToken.cs:125-128）；v2 产物上仍要写穿调用方数组",
        )
        store.leaveFunction()
    }

    // ------------------------------------------------------------------
    // 2. 同一程序降级成 v1 产物：仍可装载，但绑定退化（不静默绑错）
    // ------------------------------------------------------------------

    @Test
    fun `同一程序降级为 v1 产物后仍可装载且 REF 退回 EmptyRefVar`() {
        val pb = compile(
            Fn("MAIN", 1, arrayOf("@MAIN(SRC_ARR)", "CALL TGT_WRITER(SRC_ARR)", "RETURN")),
            Fn(
                "TGT_WRITER", 1,
                arrayOf("@TGT_WRITER(REF_TGT)", "#DIM REF REF_TGT", "REF_TGT:3 = 4242", "RETURN"),
                params = listOf("REF_TGT"),
            ),
        )
        val v1 = downgradeToV1(ErbcWriter.encode(pb.program))
        val header = ErbcReader.probe(v1)
        assertEquals(ErbcFormat.VERSION_1, header.version)
        assertEquals(ErbcFormat.SECTION_COUNT_V1, header.sectionCount)

        val loaded = ProgramLoader.load(v1)
        assertEquals(CallArgSource.ERBC_V1, loaded.callArgSource, "旧产物必须留可查标记")
        assertTrue(loaded.callArgs.isEmpty(), "v1 容器没有段 7")
        assertTrue(loaded.functions.all { it.paramNames.isEmpty() }, "v1 容器没有段 8")
        assertEquals(pb.program.instructionCount, loaded.instructionCount, "指令流照旧装载")

        val store = store()
        val rt = runtime(pb, loaded, store)
        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertTrue(e.message!!.contains("EmptyRefVar"), "v1 退化路径必须显式失败而不是绑错：${e.message}")
        assertTrue(e.message!!.contains("REF_TGT"), "错误必须指名未绑定的形参：${e.message}")
    }

    // ------------------------------------------------------------------
    // 3. 三条装载路径（bytes / 全读 / 流式 mapped）对 v2 新段逐字段一致
    // ------------------------------------------------------------------

    @Test
    fun `v2 新段在 bytes 全读 映射三条路径上逐字段一致`(@TempDir dir: Path) {
        val pb = compile(
            Fn("MAIN", 1, arrayOf("@MAIN(SRC_ARR)", "CALL TGT_WRITER(SRC_ARR)", "RETURN")),
            Fn(
                "TGT_WRITER", 1,
                arrayOf("@TGT_WRITER(REF_TGT)", "#DIM REF REF_TGT", "REF_TGT:0 = 1", "RETURN"),
                params = listOf("REF_TGT"),
            ),
        )
        val bytes = ErbcWriter.encode(pb.program)
        val f = dir.resolve("program.erbc")
        Files.write(f, bytes)

        val fromBytes = ProgramLoader.load(bytes)
        val fromFile = ProgramLoader.loadFile(f)
        val fromMapped = ProgramLoader.loadMapped(f)
        for ((name, p) in listOf("bytes" to fromBytes, "file" to fromFile, "mapped" to fromMapped)) {
            assertEquals(CallArgSource.ERBC_V2, p.callArgSource, "$name 路径的来源标记")
            assertEquals(fromBytes.callArgs, p.callArgs, "$name 路径的调用点实参表")
            for (i in fromBytes.functions.indices) {
                assertEquals(
                    fromBytes.functions[i].paramNames.toList(),
                    p.functions[i].paramNames.toList(),
                    "$name 路径的函数 $i 形参名",
                )
            }
        }
        ProgramLoader.deferredMapped(f).use { s ->
            assertEquals(fromBytes.callArgs, s.program().callArgs, "deferredMapped 路径的调用点实参表")
        }
    }

    // ==================================================================
    // 装置（与 RefArgBindingTest 同形）
    // ==================================================================

    private data class Fn(
        val name: String,
        val arity: Int,
        val lines: Array<String>,
        val params: List<String> = emptyList(),
    )

    private class Pb(
        val compiler: ErbProgramCompiler,
        val program: Program,
        val names: List<String>,
    ) {
        fun index(name: String): Int = compiler.functions.first { it.sig.name == name }.index
    }

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("SRC_ARR", OperandType.INT, 1)
            .declareVariable("REF_TGT", OperandType.INT, 1),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "erbcv2.erb", i + 1, text))
        }
    }

    private fun compile(vararg fns: Fn): Pb {
        val c = ErbProgramCompiler()
        val names = fns.map { it.name }.toSet()
        for (f in fns) c.compileFunction(FuncSig(f.name, f.arity, paramNames = f.params), parse(*f.lines), names)
        return Pb(c, c.build(fns.first().name), fns.map { it.name })
    }

    private fun runtime(
        pb: Pb,
        program: Program,
        store: VariableStore,
    ): ProcessRuntime {
        val metas = pb.names.map { name -> meta(pb, name) }
        return ProcessRuntime(program, store, FunctionTable(metas), VmConfig())
    }

    private fun run(
        pb: Pb,
        program: Program,
        store: VariableStore,
    ) {
        runtime(pb, program, store).execute()
    }

    private fun meta(
        pb: Pb,
        name: String,
    ): FuncMeta {
        val i = pb.index(name)
        return FuncMeta(index = i, key = name, argIntCount = pb.program.functions[i].arity, argStrCount = 0, defIndex = i)
    }

    private fun store(): VariableStore {
        val store = VariableStore()
        store.declareUserVar(DimSpec("SRC_ARR", false, intArrayOf(10), isPrivate = true), "MAIN")
        store.declareUserVar(
            DimSpec("REF_TGT", false, intArrayOf(0), isRef = true, isPrivate = true),
            "TGT_WRITER",
        )
        return store
    }

    // ==================================================================
    // v2 → v1 降级（模拟「旧产物」：段 0..6 逐字节保留，头缩回 7 槽）
    // ==================================================================

    /**
     * 把一份 v2 产物改写成**合法的 v1 产物**：丢掉段 7/8，其余段正文逐字节搬移，
     * 段表偏移按 46 → 38 的头长差重排。用来证明「旧 v1 产物照旧装载」而不是只测 hand 字节。
     */
    private fun downgradeToV1(v2: ByteArray): ByteArray {
        val header = ErbcReader.probe(v2)
        val ends = ProgramLoader.sectionEnds(header, v2.size)
        val count = ErbcFormat.SECTION_COUNT_V1
        var next = ErbcFormat.firstSectionOffset(count)
        val offsets = IntArray(count)
        val bodies = arrayOfNulls<ByteArray>(count)
        for (i in 0 until count) {
            val off = header.offsets[i]
            if (off == 0) continue
            offsets[i] = next
            bodies[i] = v2.copyOfRange(off, ends[i])
            next += ends[i] - off
        }
        val out = ByteArray(next)
        v2.copyInto(out, 0, 0, 4) // magic 原样
        writeLe16(out, 4, ErbcFormat.VERSION_1)
        writeLe16(out, 6, header.flags and ErbcFormat.FLAG_MASK_V1)
        writeLe16(out, 8, count)
        var at = ErbcFormat.HEADER_BYTES
        for (i in 0 until count) {
            writeLe32(out, at, offsets[i])
            at += 4
        }
        at = ErbcFormat.firstSectionOffset(count)
        for (b in bodies) {
            if (b == null) continue
            b.copyInto(out, at)
            at += b.size
        }
        return out
    }

    private fun writeLe16(
        dst: ByteArray,
        at: Int,
        value: Int,
    ) {
        dst[at] = (value and 0xFF).toByte()
        dst[at + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeLe32(
        dst: ByteArray,
        at: Int,
        value: Int,
    ) {
        dst[at] = (value and 0xFF).toByte()
        dst[at + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[at + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
