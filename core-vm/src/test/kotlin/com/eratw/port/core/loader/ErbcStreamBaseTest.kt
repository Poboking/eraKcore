package com.eratw.port.core.loader

import com.eratw.port.core.code.CallArg
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.CompositeCodeSource
import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcFormatException
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ProgramBase
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * R7-C3：`ErbcStream`（mmap 装载路径）的**基线装载**与**拼接组合性**判据
 * （契约 CONTRACT-R7-C.md §2/C3 出口判据 ①②③④）。
 *
 * 缺口（勘察结论，BOARD:1268「ErbcStream(mmap 路径)尚不支持基线」的代码事实）：
 * 全读档 `ErbcReader.decode(bytes, base)` 自波 B L8 起支持 `ProgramBase`（池按基线重灌、
 * [Program] 六基线传入、validate 按绝对 pc/下标判界），而映射档
 * `MappedErbc.toProgram` / `ErbcStreamDecoder` 没有 base 入参 —— 池恒零基线重灌、
 * `Program` 恒零基线构造、段 7 的站点 pc 与 validate 的 entryPc/标签 pc/`_CALL a`
 * 都按 `[0, 本段条数)` 判。本波把同一条口径接进映射路（判据与 `ErbcReader` 逐字同源），
 * 池/语句/函数表仍走惰性视图，带基线装载不物化任何池条目。
 *
 * 判据分组：
 *
 * 1. **正向（出口判据 ①）**：同一份补丁产物（带基线）与同一份全量产物（零基线），
 *    全读档与映射档两条装载路径产出的 [Program] 在 8 个访问点上**逐位一致** ——
 *    六基线读数、绝对 pc 取指（op/a/b/c/lineOf）、四池计数与逐条取用、函数表全字段、
 *    调用点实参表、entryFunction/globalSlots。
 * 2. **正向（出口判据 ②）**：mmap base + 堆内补丁 → [CompositeCodeSource] 拼接成功；
 *    base 区间取指与纯 mmap 档逐位一致（整页直通与跨界合成两个分派分支都踩到），
 *    且对拍全程 base 的指令流堆驻留恒 0（「禁把 base 整段物化」的定量证据）。
 * 3. **负向（契约 §0 铁律 9：每条新闸配「故意做错」）**：
 *    - 补丁产物不带基线走映射装载 → 段 7 站点 pc 区间判界拒绝；
 *    - 不带基线（且不带段 7）→ validate 的池下标绝对口径判界拒绝；
 *    - 基线错一位 → validate 的池取用判界拒绝（证明基线真的进了池与 [Program]）。
 */
class ErbcStreamBaseTest {

    // ==================================================================
    // 1. 带基线：同一份补丁产物，两条装载路径 8 访问点逐位一致
    // ==================================================================

    @Test
    fun `带基线补丁产物 全读档与映射档装载的 8 访问点读数逐位一致`(@TempDir dir: Path) {
        val counts = countsOf(baseProgram())
        val base = counts.toProgramBase()
        val patch = segmentProgram(counts, withCallArgs = true)
        val bytes = ErbcWriter.encode(patch)
        val f = dir.resolve("patch.erbc")
        Files.write(f, bytes)

        val heap = ErbcReader.decode(bytes, base)
        val mapped = loadMapped(f, base)
        assertProgramsIdentical(heap, mapped, "带基线补丁：映射档 vs 全读档")
    }

    // ==================================================================
    // 2. 零基线（I1 零补丁路径）：缺省 NONE 与全读档逐位一致
    // ==================================================================

    @Test
    fun `零基线产物 全读档与映射档装载的 8 访问点读数逐位一致`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(baseProgram())
        val f = dir.resolve("base.erbc")
        Files.write(f, bytes)

        val heap = ErbcReader.decode(bytes)
        val mapped = loadMapped(f, ProgramBase.NONE)
        assertProgramsIdentical(heap, mapped, "零基线全量产物：映射档 vs 全读档")
    }

    // ==================================================================
    // 3. 组合性：mmap base + 堆内补丁 → CompositeCodeSource
    // ==================================================================

    @Test
    fun `mmap base 与堆内补丁经 CompositeCodeSource 拼接 base 区间取指逐位一致且 base 零堆驻留`(@TempDir dir: Path) {
        val baseFile = dir.resolve("base.erbc")
        Files.write(baseFile, ErbcWriter.encode(baseProgram()))
        val mmapBase = loadMapped(baseFile, ProgramBase.NONE)
        // 装载内存红线：映射装载的 base 指令流不许有堆内常驻（映射来源 residentCode == null）
        assertEquals(0L, mmapBase.codeResidentBytes, "映射装载的 base 指令流必须零堆驻留")

        val counts = countsOf(mmapBase)
        val patch = segmentProgram(counts, withCallArgs = true)
        // tailWords = 补丁的本段窗口字（带基线 Program 的 code 只含本段，CompositeCodeSource
        // 的 KDoc 要求 tail 按这个形态给出；页宽继承 base 的 pageWords）
        val tailWords = patch.code
        val composite = CompositeCodeSource(mmapBase.codeSource, tailWords)

        assertEquals(mmapBase.codeSource.wordCount + tailWords.size, composite.wordCount, "拼接后总字数")
        assertEquals(mmapBase.codeSource.pageWords, composite.pageWords, "页宽必须继承 base")

        // 逐字对拍：base 区间与纯 mmap 档逐位一致（「base 段读数逐位一致」），
        // patch 区间与补丁窗口逐位一致
        val baseWords = mmapBase.codeSource.wordCount
        for (i in 0 until composite.wordCount) {
            val expected = if (i < baseWords) mmapBase.codeSource.word(i) else tailWords[i - baseWords]
            assertEquals(expected, composite.word(i), "拼接取指字 $i（base 区间右端 $baseWords）")
        }
        // 页口径：整页落在 base 的页直通 base（零拷贝分支）、跨界页合成 —— 样题 base
        // 大过一页（4,436 字 > 4,096），两个分派分支都被踩到；fillPage 与 page 逐字相等
        val wholeBasePages = baseWords / composite.pageWords
        assertTrue(wholeBasePages >= 1, "样题 base 必须至少占满一页，否则直通分支没被踩到")
        for (p in 0 until wholeBasePages) {
            assertContentEquals(mmapBase.codeSource.page(p), composite.page(p), "整页 base 区间页 $p")
        }
        val dst = IntArray(composite.pageWords)
        for (p in 0 until composite.pageCount) {
            val page = composite.page(p)
            val filled = composite.fillPage(p, dst)
            assertEquals(page.size, filled, "页 $p 的 fillPage 长度")
            for (k in 0 until filled) {
                assertEquals(page[k], dst[k], "页 $p 字 $k 的 fillPage 读数")
            }
        }
        // 禁整段物化：拼接与对拍全程之后，base 的指令流堆驻留仍为 0
        //（CompositeCodeSource 只按页向 base 取数，不产生整段拷贝）
        assertEquals(0L, mmapBase.codeResidentBytes, "拼接对拍全程 base 指令流不许落堆")
    }

    // ==================================================================
    // 4. 负向：故意做错，证明基线真的参与判定（不是恒 0 摆设）
    // ==================================================================

    @Test
    fun `补丁产物不带基线映射装载被调用点 pc 区间判界拒绝`(@TempDir dir: Path) {
        val counts = countsOf(baseProgram())
        val bytes = ErbcWriter.encode(segmentProgram(counts, withCallArgs = true))
        val f = dir.resolve("patch-no-base.erbc")
        Files.write(f, bytes)
        // 补丁产物里的调用点 pc 是绝对 pc（= instructionBase + 窗口内偏移），
        // 不带基线装载时合法区间退回 [0, 本段条数)，必然越界 —— 基线只在补丁头、不进容器
        val e = assertFailsWith<ErbcFormatException> { loadMapped(f, ProgramBase.NONE) }
        val message = e.message ?: ""
        assertTrue(message.contains("调用点"), "应报调用点 pc 判界，实为: $message")
        assertTrue(message.contains("越界"), "应报越界，实为: $message")
    }

    @Test
    fun `补丁产物不带基线且不带段7 映射装载被池下标绝对口径判界拒绝`(@TempDir dir: Path) {
        val counts = countsOf(baseProgram())
        val bytes = ErbcWriter.encode(segmentProgram(counts, withCallArgs = false))
        val f = dir.resolve("patch-no-base-no-callargs.erbc")
        Files.write(f, bytes)
        // 指令里的池下标是绝对口径（= base 计数 + 本段序号），零基线装载时池只有本段条目，
        // validate 的指令趟必然判越界（映射路与 ErbcReader 同一条 requirePool 判据）
        val e = assertFailsWith<ErbcFormatException> { loadMapped(f, ProgramBase.NONE) }
        val message = e.message ?: ""
        assertTrue(message.contains("越界"), "应报池下标越界，实为: $message")
    }

    @Test
    fun `基线错一位的映射装载在池取用判界上被拒绝`(@TempDir dir: Path) {
        val counts = countsOf(baseProgram())
        val bytes = ErbcWriter.encode(segmentProgram(counts, withCallArgs = false))
        val f = dir.resolve("patch-shifted-base.erbc")
        Files.write(f, bytes)
        // stringBase 少 1：装载出的 Program.stringCount 比产物里的绝对下标小 1，
        // validate 的 _SET_STR 判界当场拒绝 —— 证明基线真的进了 ConstantPool 与 Program
        val shifted = ProgramBase(
            codeBase = counts.codeWords,
            functionBase = counts.functions,
            statementBase = counts.statements,
            stringBase = counts.strings - 1,
            longBase = counts.longs,
            exprBase = counts.exprs,
        )
        val e = assertFailsWith<ErbcFormatException> { loadMapped(f, shifted) }
        val message = e.message ?: ""
        assertTrue(message.contains("字符串池"), "应点名字符串池，实为: $message")
        assertTrue(message.contains("越界"), "应报越界，实为: $message")
    }

    // ==================================================================
    // 5. `_CALL` 的 base 引用放行（R7 主流场景：口上调 base 公共函数）
    // ==================================================================

    @Test
    fun `带基线补丁的_CALL指向base区段下标 两路装载放行且逐位一致`(@TempDir dir: Path) {
        val counts = countsOf(baseProgram())
        val base = counts.toProgramBase()
        // a = base 的最后一个函数（绝对下标 < functionBase，本段 functions 里没有那条记录）
        val patch = segmentProgramCalling(counts, counts.functions - 1)
        val bytes = ErbcWriter.encode(patch)
        val f = dir.resolve("patch-call-base.erbc")
        Files.write(f, bytes)

        val heap = ErbcReader.decode(bytes, base)
        val mapped = loadMapped(f, base)
        assertProgramsIdentical(heap, mapped, "补丁调 base 函数：映射档 vs 全读档")
        // a 的 base 区段下标在两路的指令流里都原样保留 —— overlay 拼接后按绝对下标
        // 即可解析到 base 区段的函数（装载期 validate 只放行，不做改写）
        val callPc = patch.instructionBase
        assertEquals(counts.functions - 1, heap.a(callPc), "全读档 _CALL a 保留 base 区段下标")
        assertEquals(counts.functions - 1, mapped.a(callPc), "映射档 _CALL a 保留 base 区段下标")
    }

    @Test
    fun `带基线补丁的_CALL折回后越出本段函数表仍被拒绝`(@TempDir dir: Path) {
        val counts = countsOf(baseProgram())
        // a = functionBase + 5：base 放行分支不豁免折回越界 —— 折回后 5 越出本段函数表（1 条）
        val target = counts.functions + 5
        val patch = segmentProgramCalling(counts, target)
        val bytes = ErbcWriter.encode(patch)
        val f = dir.resolve("patch-call-oob.erbc")
        Files.write(f, bytes)
        val base = counts.toProgramBase()

        val e = assertFailsWith<ErbcFormatException> { loadMapped(f, base) }
        val message = e.message ?: ""
        assertTrue(message.contains("_CALL 引用函数下标"), "应报 _CALL 判界，实为: $message")
        assertTrue(message.contains("$target"), "消息应含实际下标 $target，实为: $message")
        assertTrue(message.contains("越界"), "应报越界，实为: $message")
        // 全读档同口径：同一份产物同一句话（判据与 `ErbcReader.validate` 逐字同源）
        val heapError = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(bytes, base) }
        assertTrue(heapError.message!!.contains("_CALL 引用函数下标"), "全读档应同判，实为: ${heapError.message}")
    }

    // ==================================================================
    // 样题与装置
    // ==================================================================

    /** base 侧的绝对计数（补丁样题的六个基线来源；与 `ProgramBase` 字段一一对应）。 */
    private data class SegmentCounts(
        val codeWords: Int,
        val functions: Int,
        val statements: Int,
        val strings: Int,
        val longs: Int,
        val exprs: Int,
    )

    private fun SegmentCounts.toProgramBase(): ProgramBase = ProgramBase(
        codeBase = codeWords,
        functionBase = functions,
        statementBase = statements,
        stringBase = strings,
        longBase = longs,
        exprBase = exprs,
    )

    private fun countsOf(p: Program): SegmentCounts = SegmentCounts(
        codeWords = p.codeSource.wordCount,
        functions = p.functions.size,
        statements = p.statementCount,
        strings = p.stringCount,
        longs = p.constantCount,
        exprs = p.exprCount,
    )

    /**
     * 零基线全量产物：两个函数（main 内 `_CALL` sub）、四个池各多条、`$TAIL` 标签、
     * 段 7 调用点。主体塞 1,100 条 `_NOP` 把 base 的指令流顶过一页（4,436 字 > 4,096），
     * 让组合判据里 `CompositeCodeSource` 的「整页直通」与「跨界页合成」两个分派分支都被踩到。
     */
    private fun baseProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", b.here(), arity = 1, slotCount = 3)
        val c0 = b.constant(42L)
        val c1 = b.constant(-7L)
        val s0 = b.string("base-str")
        val s1 = b.string("")
        val e = b.expr(IntLiteral(9L, 1, 2))
        val st = b.statement(RendStmt(VoidArgument(), 0, "REND"))
        b.emit(Opcode._SET_INT, SlotAddr.local(0), c0, 0, 1)
        b.emit(Opcode._SET_INT, SlotAddr.local(1), c1, 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s0, 0, 3)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s1, 0, 4)
        b.emit(Opcode._EVAL_INT, e, SlotAddr.local(2), 0, 5)
        b.emit(Opcode.DATA, st, 0, SlotAddr.NONE, 6)
        repeat(1_100) { b.emit(Opcode._NOP, 0, 0, 0, -1) }
        val callPc = b.emit(Opcode._CALL, 0, 0, SlotAddr.NONE, 7)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 8)
        val sub = b.declareFunction("sub", b.here(), arity = 0, slotCount = 1)
        b.patch(callPc, 1, sub)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 9)
        b.declareLabel(main, "TAIL", callPc)
        b.declareCallArgs(callPc, listOf(CallArg(position = 0, bucket = 0, isStr = false, name = "LV", present = true)))
        b.setFunctionSlots(main, 3)
        b.setFunctionSlots(sub, 1)
        return b.build(main)
    }

    /**
     * 带基线补丁样题（形态与 `ProgramBaseRoundTripTest.segmentProgram` 同源）：六个基线
     * 非 0，含 `_CALL`（绝对函数下标，指向补丁自身函数）、`$TAIL` 标签（绝对 pc）、
     * 四池引用（绝对下标）三种形态；[withCallArgs] = false 时省段 7（负向样题用）。
     */
    private fun segmentProgram(
        counts: SegmentCounts,
        withCallArgs: Boolean,
    ): Program {
        val b = CodeBuilder(
            codeBase = counts.codeWords,
            statementBase = counts.statements,
            stringBase = counts.strings,
            longBase = counts.longs,
            exprBase = counts.exprs,
            functionBase = counts.functions,
        )
        val main = b.declareFunction("mod_main", b.here(), arity = 1, slotCount = 3)
        val c = b.constant(7L)
        val s = b.string("mod-str")
        val e = b.expr(IntLiteral(1L, 0, 0))
        val st = b.statement(RendStmt(VoidArgument(), 0, "REND"))
        b.emit(Opcode._SET_INT, SlotAddr.local(0), c, 0, 1)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s, 0, 2)
        b.emit(Opcode._EVAL_INT, e, SlotAddr.local(2), 0, 3)
        b.emit(Opcode.DATA, st, 0, SlotAddr.NONE, 4)
        val callPc = b.emit(Opcode._CALL, 0, 0, SlotAddr.NONE, 5)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 6)
        val sub = b.declareFunction("mod_sub", b.here(), arity = 0, slotCount = 1)
        b.patch(callPc, 1, sub)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 7)
        b.declareLabel(main, "TAIL", callPc)
        if (withCallArgs) {
            b.declareCallArgs(callPc, listOf(CallArg(position = 0, bucket = 0, isStr = false, name = "LV", present = true)))
        }
        b.setFunctionSlots(main, 3)
        b.setFunctionSlots(sub, 1)
        return b.build(main)
    }

    /**
     * `_CALL` 指向任意绝对函数下标的补丁样题（最小形态：单函数 + `_CALL` + `_RET`）：
     * [callTarget] < functionBase = base 区段引用（正向放行），≥ functionBase = 折回判本段
     * （越界即拒）。无段 7、无标签，让失败/放行都只落在 `_CALL` 判界本身。
     */
    private fun segmentProgramCalling(
        counts: SegmentCounts,
        callTarget: Int,
    ): Program {
        val b = CodeBuilder(
            codeBase = counts.codeWords,
            statementBase = counts.statements,
            stringBase = counts.strings,
            longBase = counts.longs,
            exprBase = counts.exprs,
            functionBase = counts.functions,
        )
        val main = b.declareFunction("mod_hook", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, callTarget, 0, SlotAddr.NONE, 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 2)
        b.setFunctionSlots(main, 1)
        return b.build(main)
    }

    /**
     * 映射档装载（带基线），生命周期与 `ProgramLoader.loadMappedHandle` 同构：
     * 只有解出的 Program 确实带惰性池时才把产物通道留给它，其余一切路径关通道。
     */
    private fun loadMapped(
        path: Path,
        base: ProgramBase,
    ): Program {
        val mapped = MappedErbc.open(path)
        var keepChannel = false
        try {
            val program = mapped.toProgram(base = base)
            keepChannel = program.holdsLazyAnyPool
            return program
        } finally {
            if (!keepChannel) mapped.close()
        }
    }

    /**
     * 8 访问点逐位对拍（两路 [Program] 同为窗口口径；带基线时六基线必须先逐项相等，
     * 否则绝对下标空间都没对齐、后面的逐位比较没有意义）：
     * 六基线与 instructionBase、绝对 pc 的 op/a/b/c/lineOf、四个池计数与逐条取用、
     * 函数表全字段、调用点实参表全量、entryFunction/globalSlots、code/lines 本段窗口。
     */
    private fun assertProgramsIdentical(
        expected: Program,
        actual: Program,
        ctx: String,
    ) {
        assertEquals(expected.codeBase, actual.codeBase, "$ctx：codeBase")
        assertEquals(expected.statementBase, actual.statementBase, "$ctx：statementBase")
        assertEquals(expected.stringBase, actual.stringBase, "$ctx：stringBase")
        assertEquals(expected.longBase, actual.longBase, "$ctx：longBase")
        assertEquals(expected.exprBase, actual.exprBase, "$ctx：exprBase")
        assertEquals(expected.functionBase, actual.functionBase, "$ctx：functionBase")
        assertEquals(expected.instructionBase, actual.instructionBase, "$ctx：instructionBase")
        assertEquals(expected.instructionCount, actual.instructionCount, "$ctx：instructionCount（绝对口径）")
        assertContentEquals(expected.code, actual.code, "$ctx：code 本段窗口")
        assertContentEquals(expected.lines, actual.lines, "$ctx：lines 本段窗口")

        // 取指与行号定位（访问点 ①②⑥）：绝对 pc，内部按 instructionBase 折回窗口
        for (pc in expected.instructionBase until expected.instructionCount) {
            assertEquals(expected.op(pc), actual.op(pc), "$ctx：pc=$pc 的 op")
            assertEquals(expected.a(pc), actual.a(pc), "$ctx：pc=$pc 的 a")
            assertEquals(expected.b(pc), actual.b(pc), "$ctx：pc=$pc 的 b")
            assertEquals(expected.c(pc), actual.c(pc), "$ctx：pc=$pc 的 c")
            assertEquals(expected.lineOf(pc), actual.lineOf(pc), "$ctx：pc=$pc 的 lineOf")
        }

        // 四池（访问点 ④⑤）：绝对下标区间逐条取用
        assertEquals(expected.constantCount, actual.constantCount, "$ctx：常量池条数")
        for (i in expected.longBase until expected.constantCount) {
            assertEquals(expected.constant(i), actual.constant(i), "$ctx：常量池 $i")
        }
        assertEquals(expected.stringCount, actual.stringCount, "$ctx：字符串池条数")
        for (i in expected.stringBase until expected.stringCount) {
            assertEquals(expected.string(i), actual.string(i), "$ctx：字符串池 $i")
        }
        assertEquals(expected.exprCount, actual.exprCount, "$ctx：表达式池条数")
        for (i in expected.exprBase until expected.exprCount) {
            assertEquals(expected.expr(i), actual.expr(i), "$ctx：表达式池 $i")
        }
        assertEquals(expected.statementCount, actual.statementCount, "$ctx：语句池条数")
        for (i in expected.statementBase until expected.statementCount) {
            val e = expected.statement(i)
            val a = actual.statement(i)
            assertEquals(e.javaClass, a.javaClass, "$ctx：语句池 $i 类")
            assertEquals(e.code, a.code, "$ctx：语句池 $i 的 FunctionCode")
            assertEquals(e.line, a.line, "$ctx：语句池 $i 行号")
            assertEquals(e.raw, a.raw, "$ctx：语句池 $i 原文")
        }

        // 函数表（访问点 ③）：只含本段条目，全字段对拍
        assertEquals(expected.functions.size, actual.functions.size, "$ctx：函数表条数")
        for (i in expected.functions.indices) {
            val e = expected.functions[i]
            val a = actual.functions[i]
            assertEquals(e.name, a.name, "$ctx：函数 $i 名")
            assertEquals(e.entryPc, a.entryPc, "$ctx：函数 ${e.name} entryPc")
            assertEquals(e.arity, a.arity, "$ctx：函数 ${e.name} arity")
            assertEquals(e.slotCount, a.slotCount, "$ctx：函数 ${e.name} slotCount")
            assertEquals(e.paramNames.toList(), a.paramNames.toList(), "$ctx：函数 ${e.name} 形参名")
            assertEquals(e.labelNames.toList(), a.labelNames.toList(), "$ctx：函数 ${e.name} 标签名")
            assertEquals(e.labelPcs.toList(), a.labelPcs.toList(), "$ctx：函数 ${e.name} 标签 pc")
        }

        // 调用点实参表（访问点 ⑦）：pc 键绝对，站点 pc 与逐字段实参
        assertEquals(expected.callArgs.keys, actual.callArgs.keys, "$ctx：调用点 pc 键集")
        assertEquals(expected.callArgs, actual.callArgs, "$ctx：调用点实参表")
        for (pc in expected.callArgs.keys) {
            assertEquals(expected.callArgsAt(pc), actual.callArgsAt(pc), "$ctx：pc=$pc 的 callArgsAt")
        }

        assertEquals(expected.entryFunction, actual.entryFunction, "$ctx：入口函数（表内序号）")
        assertEquals(expected.globalSlots, actual.globalSlots, "$ctx：全局槽")
    }
}