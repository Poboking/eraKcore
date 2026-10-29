package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CMP-33：循环外的 BREAK/CONTINUE —— **ADR-005 复刻官方 bug**，不是修好它。
 *
 * spec: ErbLoader.cs:1049 —— `InstructionLine instructionLine = null` 声明在扫描
 * `while (true)` **之外**且从不重置；`:1182-1205` 的 BREAK/CONTINUE 只在块栈里找到
 * FOR/REPEAT/WHILE/DO 时才 `instructionLine = 找到的循环`。于是同函数里**先有合法循环、
 * 后有循环外** BREAK/CONTINUE 时，后者沿用**上一条循环锚点**静默错跳（无告警、无 THROW）。
 * 只有函数里根本还没有循环锚点时才走 `:1198-1201 InvalidLoopInstruction`（isError）——
 * 那条由既有用例钉着（`ErbProgramCompilerTest` 的 THROW marker 两条）。
 *
 * 本文件只钉「沿用锚点」的形态：不 THROW、BREAK 跳到旧循环出口、CONTINUE 跳到旧循环的
 * 步进/回边区，且 FOR 类锚点的 BREAK 仍复刻「跳出前补一步」的怪行为
 * （spec: FunctionIdentifier.cs:3312-3317）。运行时该跳转可能回到已执行过的位置
 * （官方形态：大概率绕回），故这里在**字节码层**钉行为，不执行。
 */
class Cmp33StaleLoopAnchorTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable().declareVariable("A", OperandType.INT, 1),
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
            p.parseLogicalLine(LogicalLine(kind, "cmp33.erb", i + 1, text))
        }
    }

    private fun compile(vararg lines: String): Pair<FunctionBytecode, Program> {
        val c = ErbProgramCompiler()
        val fb = c.compileFunction(FuncSig("TEST"), parse(*lines))
        return fb to c.build("TEST")
    }

    private fun jumpTarget(p: Program, pc: Int): Int = pc + 1 + p.instructionAt(pc).a

    /** 该 pc 若是语句 marker → 语句原文；否则 null。 */
    private fun markerRaw(p: Program, pc: Int): String? {
        val ins = p.instructionAt(pc)
        return if (CorpusCompileValidator.isStatementMarker(ins.op)) p.statement(ins.a).raw else null
    }

    private fun jumps(fb: FunctionBytecode, p: Program) =
        (fb.entryPc until fb.endPc).filter { p.instructionAt(it).op == Opcode._JUMP }

    private fun ops(fb: FunctionBytecode, p: Program) =
        (fb.entryPc until fb.endPc).map { p.instructionAt(it).op }

    /** 反证另一侧：函数里**从未**出现过循环锚点时，原版 / 本侧都必须抛行级错误（THROW）。 */
    @Test
    fun `函数内无循环锚点时循环外 BREAK 是行级错误`() {
        val (fb, p) = compile("BREAK")
        assertTrue(
            Opcode.THROW in ops(fb, p),
            "无旧锚点 → InvalidLoopInstruction（spec: ErbLoader.cs:1198-1201，isError）",
        )
    }

    @Test
    fun `循环外 BREAK 沿用上一条 FOR 锚点_跳到旧循环出口且补一步`() {
        val (fb, p) = compile(
            "FOR A, 0, 2",
            "PRINTL \"IN\"",
            "NEXT",
            "PRINTL \"MID\"",
            "BREAK",
            "PRINTL \"AFTER\"",
        )
        assertTrue(
            Opcode.THROW !in ops(fb, p),
            "有旧锚点时官方是静默错跳，不是行级错误 THROW（spec: ErbLoader.cs:1182-1205）",
        )
        // NEXT 的回边也指向前方；只有「沿用旧锚点」的那条跳到 MID（旧循环出口 = NEXT 之后）。
        val toMid = jumps(fb, p).filter { markerRaw(p, jumpTarget(p, it)) == "PRINTL \"MID\"" }
        assertEquals(
            1, toMid.size,
            "循环外 BREAK 必须跳到旧 FOR 的出口（NEXT 之后 = MID），实际 ${toMid.size}",
        )
        val prevPc = toMid.single() - 1
        val prevIns = p.instructionAt(prevPc)
        val prev = if (CorpusCompileValidator.isStatementMarker(prevIns.op)) p.statement(prevIns.a) else null
        assertTrue(
            prev is AssignStmt && prev.op == OperatorCode.PLUS && prev.dest.name == "A",
            "FOR 锚点的 BREAK 跳出前补一步 LoopStep（ADR-005 怪行为，spec: FunctionIdentifier.cs:3312-3317）",
        )
    }

    @Test
    fun `循环外 CONTINUE 沿用上一条 FOR 锚点_跳到旧循环步进区`() {
        val (fb, p) = compile(
            "FOR A, 0, 2",
            "PRINTL \"IN\"",
            "NEXT",
            "PRINTL \"MID\"",
            "CONTINUE",
            "PRINTL \"AFTER\"",
        )
        assertTrue(Opcode.THROW !in ops(fb, p), "有旧锚点时 CONTINUE 同样静默错跳")
        val midPc = (fb.entryPc until fb.endPc).first { markerRaw(p, it) == "PRINTL \"MID\"" }
        val stepped = jumps(fb, p).filter { pc ->
            if (pc <= midPc) return@filter false
            val t = jumpTarget(p, pc)
            val ins = p.instructionAt(t)
            val st = if (CorpusCompileValidator.isStatementMarker(ins.op)) p.statement(ins.a) else null
            st is AssignStmt && st.op == OperatorCode.PLUS && st.dest.name == "A"
        }
        assertEquals(1, stepped.size, "循环外 CONTINUE 必须跳到旧 FOR 的步进区（NEXT 处 `A += step`）")
    }

    @Test
    fun `循环外 BREAK 沿用上一条 WHILE 锚点_不补步进`() {
        val (fb, p) = compile(
            "WHILE 0",
            "PRINTL \"IN\"",
            "WEND",
            "PRINTL \"MID\"",
            "BREAK",
        )
        assertTrue(Opcode.THROW !in ops(fb, p), "WHILE 锚点同样静默错跳")
        val toMid = jumps(fb, p).filter { markerRaw(p, jumpTarget(p, it)) == "PRINTL \"MID\"" }
        assertEquals(1, toMid.size, "BREAK 跳到旧 WHILE 的出口（WEND 之后 = MID）")
        val prevPc = toMid.single() - 1
        val prevIns = p.instructionAt(prevPc)
        assertTrue(
            !(CorpusCompileValidator.isStatementMarker(prevIns.op) &&
                p.statement(prevIns.a) is AssignStmt),
            "WHILE/DO 锚点不补步进（spec: FunctionIdentifier.cs:3314-3317 只对 FOR/REPEAT 加）",
        )
    }

    /**
     * 语料实形（`TEMP_CHARA_LIST_BY_DT.ERB:931`）：循环**已闭合**后，CONTINUE 落在一条
     * 普通 `IF` 体里（块栈只有 IF、没有循环）。原版一样沿用旧 FOR 锚点 —— 中间夹 IF 不改
     * 「最近一次锚点」的取值（spec: ErbLoader.cs:1049 的函数级变量）。
     */
    @Test
    fun `循环闭合后夹在 IF 体里的 CONTINUE 同样沿用旧锚点（语料实形）`() {
        val (fb, p) = compile(
            "FOR A, 0, 2",
            "PRINTL \"IN\"",
            "NEXT",
            "IF A == 0",
            "CONTINUE",
            "ENDIF",
            "PRINTL \"AFTER\"",
        )
        assertTrue(Opcode.THROW !in ops(fb, p), "IF 体里的循环外 CONTINUE 仍是静默错跳")
        val contJumps = jumps(fb, p).filter { pc ->
            val t = jumpTarget(p, pc)
            val ins = p.instructionAt(t)
            val st = if (CorpusCompileValidator.isStatementMarker(ins.op)) p.statement(ins.a) else null
            st is AssignStmt && st.op == OperatorCode.PLUS && st.dest.name == "A"
        }
        assertEquals(1, contJumps.size, "跳到旧 FOR 的步进区（NEXT 处 `A += step`）")
    }
}
