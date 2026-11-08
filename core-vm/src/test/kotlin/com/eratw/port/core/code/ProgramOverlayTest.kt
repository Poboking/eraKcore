package com.eratw.port.core.code

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * R7-C2 `ProgramOverlay` 的出口判据（CONTRACT-R7-C §2/C2 ①-⑥）。
 *
 * ## 判据与用例的映射
 *
 * - ① 8 访问点逐位对拍：`base 区间逐位等于 base 单独` / `patch 区间与补丁单独逐位一致`
 * - ② I2 线性自检闸（负向逐条）：`负向 I2：` 前缀的 8 条用例，每条判据一个「故意做错」
 * - ③ I4 零漂移：`I4:` 前缀用例（overlay 前后 base 计数与指令流逐项不变）
 * - ④ I1 零补丁路径：本测试不改任何既有测试；定向回归由命令行
 *   `--tests "com.eratw.port.core.code.*"` 全绿背书（波 A/B 的既有面零改动）
 * - ⑤ 页分派：`页分派:` 用例（base 整页直通、跨界页合成、尾页短数组、fillPage）
 * - ⑥ lineOf 拼接：`lineOf 拼接:` 用例（base 段定位不变、patch 段定位到补丁行）
 *
 * ## 夹具口径
 *
 * base 与补丁都用 [CodeBuilder] 真实编译（补丁按波 A 钉子 A1 证过的「独立 builder 续编」
 * 模式，六个基线取 base 的绝对计数）；补丁内容覆盖全部四池（常量/字符串/表达式/语句）
 * 与跨段调用（补丁 `_CALL` base 的函数）。页分派用例的 base 需要显式页宽
 * （[CodeBuilder] 产物整段一页、页宽=自身字数，踩不出跨界页——这正是波 A 钉子 A3
 * 记录的 `CodeSource.of` 退化行为），故用 [PagedIntArraySource] 直接装配 base 的
 * `codeSource`，字内容是合法指令流（I2 自检会逐条扫它）。
 */
class ProgramOverlayTest {

    // ------------------------------------------------------------------
    // 夹具：base 与补丁的确定性编译
    // ------------------------------------------------------------------

    /** 编译产物与对拍要用的关键锚点。 */
    private class Compiled(
        val program: Program,
        val entryFunction: Int,
        val entryPc: Int,
        val calleeFunction: Int,
        val markerStmt: Statement,
        val markerPc: Int,
        val callPc: Int,
        val patchCallFn: Int,
        val crossCallPc: Int,
    )

    /**
     * base：7 条指令 / 2 个函数 / 四池各 1 条 / 1 个调用点 / 全局槽空间 5 槽。
     * 布局：SET_INT@0, SET_STR@1, EVAL_INT@2, REND@3, [CALLEE] RET@4, CALL@5, RET@6。
     */
    private fun compileBase(): Compiled {
        val b = CodeBuilder()
        val entryPc = b.here()
        val entry = b.declareFunction("ENTRY", entryPc, arity = 0, slotCount = 4)
        b.emit(Opcode._SET_INT, 0, b.constant(7L), 0, 1)
        b.emit(Opcode._SET_STR, 1, b.string("base字符串"), 0, 2)
        b.emit(Opcode._EVAL_INT, b.expr(IntLiteral(42L, 2, 0)), 2, 0, 3)
        val markerStmt = RendStmt(VoidArgument(), 3, "BASE_REND")
        val markerPc = b.emit(Opcode.REND, b.statement(markerStmt), 0, SlotAddr.NONE, 4)
        val callee = b.declareFunction("CALLEE", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 5)
        val callPc = b.emit(Opcode._CALL, callee, 0, SlotAddr.NONE, 6)
        b.declareCallArgs(callPc, listOf(CallArg(0, 0, false, null, true)))
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 7)
        b.noteGlobalSlot(4)
        return Compiled(
            program = b.build(entry),
            entryFunction = entry,
            entryPc = entryPc,
            calleeFunction = callee,
            markerStmt = markerStmt,
            markerPc = markerPc,
            callPc = callPc,
            patchCallFn = -1,
            crossCallPc = -1,
        )
    }

    /**
     * 补丁：独立 builder，六个基线取 base 的绝对计数（波 A 钉子 A1 的「独立 builder 续编」
     * 模式）。10 条指令 / 2 个函数 / 四池各含新条目，含一条**跨段调用**（`_CALL` base 的
     * CALLEE，绝对下标 1）。布局（段内序）：SET_INT@0, SET_STR@1, EVAL_INT@2, REND@3,
     * CALL(base CALLEE)@4, RET@5, [PATCH_FN] PRINT_LITERAL@6, RET@7, CALL(PATCH_FN)@8, RET@9。
     */
    private fun compilePatch(base: Program): Compiled {
        val b = alignedBuilder(base)
        val entryPc = b.here()
        val entry = b.declareFunction("PATCH_ENTRY", entryPc, arity = 0, slotCount = 4)
        b.emit(Opcode._SET_INT, 0, b.constant(99L), 0, 101)
        b.emit(Opcode._SET_STR, 1, b.string("patch字符串"), 0, 102)
        b.emit(Opcode._EVAL_INT, b.expr(IntLiteral(777L, 103, 0)), 2, 0, 103)
        val markerStmt = RendStmt(VoidArgument(), 4, "PATCH_REND")
        val markerPc = b.emit(Opcode.REND, b.statement(markerStmt), 0, SlotAddr.NONE, 104)
        // 跨段调用：base 的 CALLEE 是绝对函数下标 1（补丁编译期就这么发，拼接后必须仍命中）。
        b.emit(Opcode._CALL, baseCompiledCalleeIndex, 0, SlotAddr.NONE, 105)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 106)
        val patchCallFn = b.declareFunction("PATCH_FN", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._PRINT_LITERAL, b.string("patch字面量"), 0, 0, 107)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 108)
        val crossCallPc = b.emit(Opcode._CALL, patchCallFn, 0, SlotAddr.NONE, 109)
        b.declareCallArgs(crossCallPc, listOf(CallArg(0, 0, false, "ARG:0", true)))
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 110)
        return Compiled(
            program = b.build(entry),
            entryFunction = entry,
            entryPc = entryPc,
            calleeFunction = baseCompiledCalleeIndex,
            markerStmt = markerStmt,
            markerPc = markerPc,
            callPc = -1,
            patchCallFn = patchCallFn,
            crossCallPc = crossCallPc,
        )
    }

    /** base 的 CALLEE 绝对函数下标（[compileBase] 里第 2 个声明的函数）。 */
    private val baseCompiledCalleeIndex = 1

    /** 与 [compileBase] 的产物基线对齐的空补丁 builder（负向用例往里发射坏指令）。 */
    private fun alignedBuilder(base: Program): CodeBuilder = CodeBuilder(
        codeBase = base.instructionCount * Instruction.WORDS,
        statementBase = base.statementCount,
        stringBase = base.stringCount,
        longBase = base.constantCount,
        exprBase = base.exprCount,
        functionBase = base.functions.size,
    )

    /** 造一个基线对齐的补丁：入口函数 + [body] 里发射的内容 + 一条收尾 `_RET`。 */
    private fun buildPatch(base: Program, body: CodeBuilder.() -> Unit): Program {
        val b = alignedBuilder(base)
        val entry = b.declareFunction("PATCH_ENTRY", b.here(), arity = 0, slotCount = 1)
        b.body()
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, -1)
        return b.build(entry)
    }

    private fun overlayOf(base: Program, patch: Program): Program = ProgramOverlay.overlay(base, patch)

    // ------------------------------------------------------------------
    // 判据 ①：8 访问点逐位对拍 —— base 区间
    // ------------------------------------------------------------------

    @Test
    fun `拼接后 base 区间八访问点逐位等于 base 单独`() {
        val baseC = compileBase()
        val base = baseC.program
        val patch = compilePatch(base).program
        val overlay = overlayOf(base, patch)

        val baseIC = base.instructionCount
        // 指令流：base 区间每条指令的四个字逐位相同。
        for (pc in 0 until baseIC) {
            assertEquals(base.op(pc), overlay.op(pc), "op@pc=$pc")
            assertEquals(base.a(pc), overlay.a(pc), "a@pc=$pc")
            assertEquals(base.b(pc), overlay.b(pc), "b@pc=$pc")
            assertEquals(base.c(pc), overlay.c(pc), "c@pc=$pc")
        }
        // 访问点 1/2：指令总数 = base 条数 + 补丁本段条数；补丁的绝对口径计数（基线 + 本段）
        // 就是拼接后的总数，两者必须一致。
        assertEquals(baseIC + patch.code.size / Instruction.WORDS, overlay.instructionCount)
        assertEquals(patch.instructionCount, overlay.instructionCount, "补丁的绝对口径计数 = 拼接后的总数")
        // 访问点 4/5：四池 base 区间逐条相同，且语句/表达式按引用共享（不复制实例）。
        for (i in 0 until base.statementCount) {
            assertSame(base.statement(i), overlay.statement(i), "statement@$i 必须是同一实例")
        }
        for (i in 0 until base.exprCount) {
            assertSame(base.expr(i), overlay.expr(i), "expr@$i 必须是同一实例")
        }
        for (i in 0 until base.stringCount) {
            assertEquals(base.string(i), overlay.string(i), "string@$i")
        }
        for (i in 0 until base.constantCount) {
            assertEquals(base.constant(i), overlay.constant(i), "constant@$i")
        }
        // 访问点 3：函数表 base 段按下标命中同一条目。
        for (k in base.functions.indices) {
            assertSame(base.functions[k], overlay.functions[k], "function@$k 必须是同一条目")
        }
        // 访问点 7：base 的调用点表按键取回一致。
        assertContentEquals(base.callArgsAt(baseC.callPc), overlay.callArgsAt(baseC.callPc))
        // 访问点 6：base 段行号定位不变（判据 ⑥ 的 base 侧，逐 pc）。
        for (pc in 0 until baseIC) {
            assertEquals(base.lineOf(pc), overlay.lineOf(pc), "lineOf@pc=$pc")
        }
        // 兼容视图（冷路径物化口径）：整段字/行号 = 两段顺序拼接。
        assertContentEquals(base.code + patch.code, overlay.code)
        assertContentEquals(base.lines + patch.lines, overlay.lines)
    }

    @Test
    fun `拼接后 patch 区间八访问点与补丁单独逐位一致`() {
        val base = compileBase().program
        val patchC = compilePatch(base)
        val patch = patchC.program
        val overlay = overlayOf(base, patch)

        val baseIC = base.instructionCount
        val total = overlay.instructionCount
        // 指令流：patch 区间逐位与补丁单独时相同（补丁的读面就是绝对口径）。
        for (pc in baseIC until total) {
            assertEquals(patch.op(pc), overlay.op(pc), "op@pc=$pc")
            assertEquals(patch.a(pc), overlay.a(pc), "a@pc=$pc")
            assertEquals(patch.b(pc), overlay.b(pc), "b@pc=$pc")
            assertEquals(patch.c(pc), overlay.c(pc), "c@pc=$pc")
        }
        // 四池 patch 区间：绝对下标折回续段，取回的实例与补丁池内相同。
        assertEquals(99L, overlay.constant(patch.longBase), "补丁常量落在 longBase 处")
        assertEquals("patch字符串", overlay.string(patch.stringBase))
        assertEquals("patch字面量", overlay.string(patch.stringBase + 1))
        assertEquals(patch.expr(patch.exprBase), overlay.expr(patch.exprBase))
        assertSame(patch.statement(patch.statementBase), overlay.statement(patch.statementBase))
        // 访问点 3：补丁函数尾段追加，entryPc 落在补丁区间。
        val baseFn = base.functions.size
        assertEquals(baseFn + patch.functions.size, overlay.functions.size)
        for (k in patch.functions.indices) {
            assertSame(patch.functions[k], overlay.functions[baseFn + k], "function@${baseFn + k}")
            val f = overlay.functions[baseFn + k]
            assertTrue(
                f.entryPc in baseIC until total,
                "补丁函数 ${f.name} 的 entryPc=${f.entryPc} 必须落在补丁区间 [$baseIC, $total)",
            )
        }
        // 访问点 7：补丁调用点按键取回一致（值语义：Builder 归一后实例可能与补丁侧不同）。
        assertContentEquals(patch.callArgsAt(patchC.crossCallPc), overlay.callArgsAt(patchC.crossCallPc))
        // 访问点 6：patch 段行号 = 补丁自己的行号。
        for (pc in baseIC until total) {
            assertEquals(patch.lineOf(pc), overlay.lineOf(pc), "lineOf@pc=$pc")
        }
    }

    @Test
    fun `entryFunction 恒为 base 且 globalSlots 不许变`() {
        val base = compileBase().program
        val patch = compilePatch(base).program
        val overlay = overlayOf(base, patch)

        assertEquals(base.entryFunction, overlay.entryFunction, "入口函数恒为 base 的")
        assertEquals(base.globalSlots, overlay.globalSlots, "全局槽总数不许变（存档列不变）")
        // 跨段调用自洽：补丁里的 _CALL a=1 在拼接表上命中的仍是 base 的 CALLEE。
        val callee = overlay.functions[baseCompiledCalleeIndex]
        assertEquals("CALLEE", callee.name)
        // 按名查表（Vm.resolveFunctionIndex 的消费形态）也认得出补丁函数。
        assertEquals("PATCH_ENTRY", overlay.function("PATCH_ENTRY")?.name)
    }

    // ------------------------------------------------------------------
    // 判据 ⑥：lineOf 拼接定位
    // ------------------------------------------------------------------

    @Test
    fun `lineOf 拼接：base 段定位不变、patch 段定位到补丁行`() {
        val base = compileBase().program
        val patchC = compilePatch(base)
        val patch = patchC.program
        val overlay = overlayOf(base, patch)

        val baseIC = base.instructionCount
        // 跨界三点：base 末条 / patch 首条 / 末条。
        assertEquals(base.lineOf(baseIC - 1), overlay.lineOf(baseIC - 1), "base 末条")
        assertEquals(patch.lineOf(baseIC), overlay.lineOf(baseIC), "patch 首条（折回补丁的 0）")
        assertEquals(
            patch.lineOf(total(overlay) - 1),
            overlay.lineOf(total(overlay) - 1),
            "patch 末条",
        )
        // 补丁行号确实是补丁 ERB 那几行（101..110），不是 base 的行号串段。
        assertEquals(107, overlay.lineOf(patchC.crossCallPc - 2), "PRINT_LITERAL 那条的行号")
        assertTrue(overlay.lineOf(baseIC) >= 101, "patch 首条的行号必须落在补丁行号域")
    }

    private fun total(p: Program): Int = p.instructionCount

    // ------------------------------------------------------------------
    // 判据 ⑤：页分派（整页直通、跨界页合成、尾页短数组、fillPage）
    // ------------------------------------------------------------------

    /** 页宽可控的 base 夹具：4,404 字（1,101 条 `_NOP`）按 4,096 切页 → 末页 308 字。 */
    private class PagedFixture(val base: Program, val baseWords: IntArray)

    private fun compilePagedBase(): PagedFixture {
        val words = IntArray(4404)
        var i = 0
        for (pc in 0 until 1101) {
            words[i] = Opcode._NOP
            words[i + 1] = pc
            words[i + 2] = 0
            words[i + 3] = SlotAddr.NONE
            i += Instruction.WORDS
        }
        val pool = ConstantPool()
        pool.addString("paged")
        val base = Program(
            codeSource = PagedIntArraySource(words, CodeSource.DEFAULT_PAGE_WORDS),
            lineTable = LineTable.of(IntArray(1101) { 500 + it }),
            pool = pool,
            functions = listOf(FunctionInfo("BIG", 0, 0, 1)),
            entryFunction = 0,
            globalSlots = 1,
        )
        return PagedFixture(base, words)
    }

    @Test
    fun `页分派：base 整页直通、跨界页合成、尾页短数组`() {
        val fixture = compilePagedBase()
        val base = fixture.base

        // 补丁 10 条 = 40 字：总 4,444 字，页宽取 base 的 4,096 → 2 页，第 2 页是跨界短页。
        val b = CodeBuilder(
            codeBase = base.instructionCount * Instruction.WORDS,
            stringBase = base.stringCount,
            functionBase = base.functions.size,
        )
        val entry = b.declareFunction("PATCH_NOP", b.here(), arity = 0, slotCount = 1)
        repeat(9) { b.emit(Opcode._NOP, it, 0, 0, -1) }
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, -1)
        val patch = b.build(entry)

        val overlay = overlayOf(base, patch)
        val src = overlay.codeSource

        assertEquals(4096, src.pageWords, "页宽必须跟 base（不是补丁自己的字数）")
        assertEquals(4444, src.wordCount)
        assertEquals(2, src.pageCount)

        // 整页落在 base ⇒ 直通 base.page（零拷贝路径），逐位相同。
        val p0 = src.page(0)
        assertEquals(4096, p0.size)
        assertContentEquals(base.codeSource.page(0), p0)

        // 跨界页：base 末 308 字 + 补丁 40 字，一次合成；同时就是短尾页。
        val p1 = src.page(1)
        assertEquals(348, p1.size, "尾页可短（348 = 308 base + 40 patch）")
        val expectCross = fixture.baseWords.copyOfRange(4096, 4404) + patch.code
        assertContentEquals(expectCross, p1)

        // fillPage 同一口径（VM 独占缓冲的原地填充）。
        val dst = IntArray(4096)
        assertEquals(348, src.fillPage(1, dst))
        assertContentEquals(expectCross, dst.copyOf(348))

        // 逐字抽查跨界点：页界两侧不许错位（波 A 钉子 A3 的判法）。
        assertEquals(fixture.baseWords[4095], src.word(4095))
        assertEquals(fixture.baseWords[4096], src.word(4096))
        assertEquals(fixture.baseWords[4403], src.word(4403))
        assertEquals(patch.code[0], src.word(4404))
        assertEquals(patch.code[39], src.word(4443))
        // 整段物化 = 两段顺序拼接。
        assertContentEquals(fixture.baseWords + patch.code, src.toIntArray())
    }

    // ------------------------------------------------------------------
    // 判据 ③：I4 base 读数零漂移
    // ------------------------------------------------------------------

    @Test
    fun `I4：overlay 前后 base 的四计数与指令流零漂移`() {
        val baseC = compileBase()
        val base = baseC.program
        val patch = compilePatch(base).program

        val instrBefore = base.instructionCount
        val stmtBefore = base.statementCount
        val fnBefore = base.functions.size
        val strBefore = base.stringCount
        val constBefore = base.constantCount
        val exprBefore = base.exprCount
        val codeBefore = base.code.copyOf()
        val stmt0Before = base.statement(0)

        overlayOf(base, patch)

        // 判据点名的四计数 + 常量/表达式池计数（A1 证过语句池按引用共享会反向污染，必须断言）。
        assertEquals(instrBefore, base.instructionCount, "instructionCount 不许漂")
        assertEquals(stmtBefore, base.statementCount, "statementCount 不许漂")
        assertEquals(fnBefore, base.functions.size, "functionCount 不许漂")
        assertEquals(strBefore, base.stringCount, "stringCount 不许漂")
        assertEquals(constBefore, base.constantCount)
        assertEquals(exprBefore, base.exprCount)
        assertContentEquals(codeBefore, base.code, "base 指令流一个字节都不许变")
        assertSame(stmt0Before, base.statement(0), "base 池条目不许被换掉")
    }

    // ------------------------------------------------------------------
    // 判据 ②：I2 线性自检闸（负向逐条）
    // ------------------------------------------------------------------

    @Test
    fun `负向 I2：CALL 函数下标越总函数数被拒绝`() {
        val base = compileBase().program
        val patch = buildPatch(base) {
            emit(Opcode._CALL, 999_999, 0, SlotAddr.NONE, -1)
        }
        val ex = assertFailsWith<IllegalArgumentException> { overlayOf(base, patch) }
        assertTrue(ex.message!!.contains("_CALL"), "消息要点名指令，实际: ${ex.message}")
        assertTrue(ex.message!!.contains("函数表"), "消息要点名哪个池，实际: ${ex.message}")
    }

    @Test
    fun `负向 I2：marker 语句下标越总语句数被拒绝`() {
        val base = compileBase().program
        val patch = buildPatch(base) {
            emit(Opcode.REND, 999_999, 0, SlotAddr.NONE, -1)
        }
        val ex = assertFailsWith<IllegalArgumentException> { overlayOf(base, patch) }
        assertTrue(ex.message!!.contains("语句池"), "实际: ${ex.message}")
    }

    @Test
    fun `负向 I2：EVAL 表达式下标越总表达式数被拒绝`() {
        val base = compileBase().program
        val patch = buildPatch(base) {
            emit(Opcode._EVAL_INT, 999_999, 0, 0, -1)
        }
        val ex = assertFailsWith<IllegalArgumentException> { overlayOf(base, patch) }
        assertTrue(ex.message!!.contains("表达式池"), "实际: ${ex.message}")
    }

    @Test
    fun `负向 I2：SET_STR 字符串下标越界被拒绝`() {
        val base = compileBase().program
        val patch = buildPatch(base) {
            emit(Opcode._SET_STR, 0, 999_999, 0, -1)
        }
        val ex = assertFailsWith<IllegalArgumentException> { overlayOf(base, patch) }
        assertTrue(ex.message!!.contains("字符串池"), "实际: ${ex.message}")
    }

    @Test
    fun `负向 I2：PRINT_LITERAL 字符串下标越界被拒绝`() {
        val base = compileBase().program
        val patch = buildPatch(base) {
            emit(Opcode._PRINT_LITERAL, 999_999, 0, 0, -1)
        }
        val ex = assertFailsWith<IllegalArgumentException> { overlayOf(base, patch) }
        assertTrue(ex.message!!.contains("字符串池"), "实际: ${ex.message}")
    }

    @Test
    fun `负向 I2：SET_INT 常量下标越界被拒绝`() {
        val base = compileBase().program
        val patch = buildPatch(base) {
            emit(Opcode._SET_INT, 0, 999_999, 0, -1)
        }
        val ex = assertFailsWith<IllegalArgumentException> { overlayOf(base, patch) }
        assertTrue(ex.message!!.contains("常量池"), "实际: ${ex.message}")
    }

    @Test
    fun `负向 I2：栈码 PUSH_IMM 常量下标越界被拒绝`() {
        val base = compileBase().program
        val patch = buildPatch(base) {
            emit(Opcode._STK_PUSH_IMM, 999_999, 0, 0, -1)
        }
        // M2-1 起判据的分类面收进 OpcodeRelocation（specs §5.6b），本路不再转调装载路的
        // requireStackOpPool，处置策略统一为 IllegalArgumentException（此前栈码这一条是
        // ErbcDecodeException）。两者都是 RuntimeException，波 C4 的「全或无」回退口径不变。
        val ex = assertFailsWith<IllegalArgumentException> { overlayOf(base, patch) }
        assertTrue(ex.message!!.contains("常量池"), "实际: ${ex.message}")
    }

    @Test
    fun `负向 I2：补丁函数 entryPc 落在补丁区间外被拒绝`() {
        val base = compileBase().program
        val patch = buildPatch(base) {
            // entryPc=0 由调用方给（declareFunction 不校验）：指向 base 区间 = 函数表串段。
            declareFunction("MISPLACED", 0, arity = 0, slotCount = 0)
        }
        val ex = assertFailsWith<IllegalArgumentException> { overlayOf(base, patch) }
        assertTrue(ex.message!!.contains("MISPLACED"), "消息要点名函数，实际: ${ex.message}")
        assertTrue(ex.message!!.contains("补丁区间"), "实际: ${ex.message}")
    }

    @Test
    fun `负向 I2：callArgs 键越总指令数被拒绝`() {
        val base = compileBase().program
        val patch = buildPatch(base) {
            declareCallArgs(base.instructionCount + 50, listOf(CallArg(0, 0, false, null, true)))
        }
        val ex = assertFailsWith<IllegalArgumentException> { overlayOf(base, patch) }
        assertTrue(ex.message!!.contains("绝对 pc"), "实际: ${ex.message}")
    }

    @Test
    fun `负向：补丁 callArgs 键落在 base 区间被拒绝`() {
        val base = compileBase().program
        val patch = buildPatch(base) {
            // 键 0 是 base 的绝对 pc：补丁基线错位的典型形态（实参会绑到 base 的指令上）。
            declareCallArgs(0, listOf(CallArg(0, 0, false, null, true)))
        }
        val ex = assertFailsWith<IllegalArgumentException> { overlayOf(base, patch) }
        assertTrue(ex.message!!.contains("base 区间"), "实际: ${ex.message}")
    }

    @Test
    fun `负向：补丁扩全局槽被拒绝（存档列不许变）`() {
        val base = compileBase().program
        val patch = buildPatch(base) {
            noteGlobalSlot(150) // base 只有 5 槽（noteGlobalSlot(4)），150 号槽是新增全局槽
        }
        val ex = assertFailsWith<IllegalArgumentException> { overlayOf(base, patch) }
        assertTrue(ex.message!!.contains("全局槽"), "实际: ${ex.message}")
    }

    @Test
    fun `负向：补丁基线错位(codeBase)被拒绝`() {
        val base = compileBase().program
        // codeBase 故意多偏一条指令：补丁窗口与 base 之间出现一条指令的空洞。
        val b = CodeBuilder(
            codeBase = base.instructionCount * Instruction.WORDS + Instruction.WORDS,
            statementBase = base.statementCount,
            stringBase = base.stringCount,
            longBase = base.constantCount,
            exprBase = base.exprCount,
            functionBase = base.functions.size,
        )
        val entry = b.declareFunction("PATCH_ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, -1)
        val patch = b.build(entry)

        val ex = assertFailsWith<IllegalArgumentException> { overlayOf(base, patch) }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("codeBase"), "消息要点名哪个基线，实际: $msg")
        assertTrue(msg.contains("期望"), "消息要给出期望与实际，实际: $msg")
    }

    // ------------------------------------------------------------------
    // 正向补两条：调用点表的空表与「补丁无表」两条合并路径
    // ------------------------------------------------------------------

    @Test
    fun `两段都无 callArgs 时合并结果为空且按键查询恒 null`() {
        // mini base 与补丁都无调用点(CompileBaseTest 同款最小骨架)。
        val b = CodeBuilder()
        val e = b.declareFunction("MINI", 0, arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, -1)
        val miniBase = b.build(e)
        val patch = buildPatch(miniBase) {
            emit(Opcode._RET, SlotAddr.NONE, 0, 0, -1)
        }
        val overlay = overlayOf(miniBase, patch)
        assertTrue(overlay.callArgs.isEmpty(), "空对空合并后仍为空表")
        assertNull(overlay.callArgsAt(0), "空表按键查询恒 null")
    }

    @Test
    fun `补丁无调用点时合并结果就是 base 的表`() {
        val baseC = compileBase()
        val base = baseC.program
        val patch = buildPatch(base) {
            emit(Opcode._RET, SlotAddr.NONE, 0, 0, -1)
        }
        val overlay = overlayOf(base, patch)
        assertEquals(base.callArgs.size, overlay.callArgs.size, "补丁无调用点 ⇒ 合并结果 = base 的表")
        assertContentEquals(base.callArgsAt(baseC.callPc), overlay.callArgsAt(baseC.callPc))
        assertNull(overlay.callArgsAt(base.instructionCount), "base 区间之外的键恒 null")
    }

}