package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.SlotAddr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 局部布局（dimSpecs 接入）单测 —— 函数私有 #DIM → 帧内局部槽、全局侧委托、
 * 局部/暂存/临时三区隔离、尺寸表达式求值（specs/loader.md 附录 D.8）。
 */
class DimSpecLayoutTest {

    private fun layout(dims: Map<String, List<LocalDim>>): DimSpecLayout =
        DimSpecLayout(GlobalNameLayout(), dims)

    @Test
    fun `私有 dim 落局部槽并返回占用数`() {
        val l = layout(mapOf("F" to listOf(LocalDim("LOCAL_X", false), LocalDim("LOCAL_S", true))))
        val n = l.beginFunction("F", firstLocalSlot = 2)
        assertEquals(2, n)
        assertEquals(2, SlotAddr.index(l.slotOf("LOCAL_X", false)!!))
        assertEquals(3, SlotAddr.index(l.slotOf("LOCAL_S", true)!!))
        l.endFunction()
    }

    @Test
    fun `不同函数同名单独分槽`() {
        val l = layout(mapOf(
            "F1" to listOf(LocalDim("COUNT", false)),
            "F2" to listOf(LocalDim("COUNT", false)),
        ))
        l.beginFunction("F1", 1)
        assertEquals(1, SlotAddr.index(l.slotOf("COUNT", false)!!))
        l.endFunction()
        l.beginFunction("F2", 3)
        assertEquals(3, SlotAddr.index(l.slotOf("COUNT", false)!!), "同名局部变量随函数各分各的槽")
        l.endFunction()
    }

    @Test
    fun `未声明变量委托全局布局`() {
        val l = layout(mapOf("F" to listOf(LocalDim("LOCAL_X", false))))
        l.beginFunction("F", 1)
        val localSlot = l.slotOf("LOCAL_X", false)!!
        assertTrue(!SlotAddr.isGlobal(localSlot) && !SlotAddr.isNone(localSlot), "声明的私有变量落局部槽")
        val g = l.slotOf("UNDECLARED", false)
        assertTrue(g != null && SlotAddr.isGlobal(g), "局部表未命中 → 全局槽（占位行为不变）")
        l.endFunction()
    }

    @Test
    fun `ref 不占局部槽`() {
        val l = layout(mapOf("F" to listOf(LocalDim("R", false, ref = true), LocalDim("X", false))))
        val n = l.beginFunction("F", 0)
        assertEquals(1, n, "REF 绑定槽不占帧槽数组")
        assertTrue(SlotAddr.isGlobal(l.slotOf("R", false)!!), "REF 委托全局侧（绑定语义 M2 变量层）")
        l.endFunction()
    }

    @Test
    fun `编译器主路径 具名变量走语句池且实参暂存与临时槽隔离`() {
        // 旧断言（`LV = 5` → _SET_INT 到 **布局局部槽 R2**）锁的是契约 C1 废除前的形态：
        // 具名变量一律不再映射 VM 槽（主路径不查 layout），赋值只以 AssignStmt 进语句池。
        // 布局侧（DimSpecLayout 本身）的分槽行为仍由上面几个用例锁死。
        val layout = layout(mapOf("TEST" to listOf(LocalDim("LV", false), LocalDim("LW", false))))
        val c = ErbProgramCompiler(layout)
        c.compileFunction(FuncSig("TEST"), parseTestLines("CALL F, 1, 2", "LV = 5", "PRINTL X"), knownFunctions = setOf("F"))
        c.compileFunction(FuncSig("F"), parseTestLines("PRINTL F"))
        val p = c.build("TEST")
        // 1) `LV = 5` 是语句池 marker（契约 C1/C3），不是任何槽的 _SET_INT
        var lvMarker = -1
        for (pc in 0 until p.instructionCount) {
            val ins = p.instructionAt(pc)
            if (CorpusCompileValidator.isStatementMarker(ins.op) &&
                (p.statement(ins.a) as? com.eratw.port.core.stmt.AssignStmt)?.dest?.name == "LV"
            ) { lvMarker = pc; break }
        }
        assertTrue(lvMarker >= 0, "LV = 5 应作为 AssignStmt marker 入语句池")
        assertTrue(
            (0 until p.instructionCount).none {
                val ins = p.instructionAt(it)
                ins.op == Opcode._SET_INT && p.constant(ins.b) == 5L
            },
            "赋值不得再降级成 _SET_INT 到槽（那会造出第二份真相，契约 C1）",
        )
        // 2) 主路径零全局槽写入：槽写入类指令的目标只允许匿名帧内临时
        for (pc in 0 until p.instructionCount) {
            val ins = p.instructionAt(pc)
            when (ins.op) {
                Opcode._SET_INT, Opcode._SET_STR, Opcode._MOV_INT, Opcode._MOV_STR ->
                    assertTrue(!SlotAddr.isGlobal(ins.a), "pc$pc 写全局槽：违反契约 C1")
                Opcode._EVAL_INT, Opcode._EVAL_STR ->
                    assertTrue(!SlotAddr.isGlobal(ins.b), "pc$pc 求值落全局槽：违反契约 C1")
                else -> {}
            }
        }
        // 3) 实参暂存与调用传参隔离（2 个实参：暂存从 R2 起，R0/R1 只被 _MOV 写入）：
        //    旧口径"局部槽紧接暂存区（R2/R3）"随布局退出主路径而失效，改为锁三区不变量。
        val call = (0 until p.instructionCount).first { p.instructionAt(it).op == Opcode._CALL }
        for (pc in 0 until call) {
            val ins = p.instructionAt(pc)
            if (ins.op == Opcode._SET_INT && (p.constant(ins.b) == 1L || p.constant(ins.b) == 2L)) {
                assertTrue(SlotAddr.index(ins.a) >= 2, "暂存槽（maxArgc=2 起）不得占用 R0/R1 实参区")
            }
        }
    }

    @Test
    fun `尺寸表达式求值`() {
        val e = DimSizeEvaluator(mapOf("MAXSIZE" to 10L, "BASE" to 3L))
        assertEquals(5L, e.eval("5"))
        assertEquals(13L, e.eval("MAXSIZE + BASE"))
        assertEquals(30L, e.eval("MAXSIZE * BASE"))
        assertEquals(4L, e.eval("(MAXSIZE - 2) / 2"))
        assertEquals(-3L, e.eval("-3"))
        assertEquals(7L, e.eval("1 + 2 * 3"))
        // 0x/0X 十六进制面值（spec: LexicalAnalyzer.cs:36-45）
        assertEquals(0xC070C0L, e.eval("0xC070C0"))
        assertEquals(0xC0C0C0L, e.eval("0XC0C0C0"))
        assertEquals(18L, e.eval("0x10 + 2"))
        assertEquals(-16L, e.eval("-0x10"))
        assertEquals(null, e.eval("0x"))
        assertEquals(null, e.eval("0xZZ"))
        assertEquals(null, e.eval("UNKNOWN_CONST"))
        assertEquals(null, e.eval(""))
        assertEquals(null, e.eval("1 +"))
        assertEquals(null, e.eval("A B"))
    }

    @Test
    fun `beginFunction 前后 slotOf 行为`() {
        val l = layout(mapOf("F" to listOf(LocalDim("LOCAL_X", false))))
        // beginFunction 之前（无当前函数）：查不到局部，委托全局
        assertTrue(SlotAddr.isGlobal(l.slotOf("LOCAL_X", false)!!))
        l.beginFunction("F", 0)
        val ls = l.slotOf("LOCAL_X", false)!!
        assertTrue(!SlotAddr.isGlobal(ls) && !SlotAddr.isNone(ls), "函数作用域内落局部槽")
        l.endFunction()
        assertTrue(SlotAddr.isGlobal(l.slotOf("LOCAL_X", false)!!), "endFunction 后恢复全局委托")
    }

    private fun parseTestLines(vararg lines: String): List<com.eratw.port.core.stmt.Statement> {
        val p = com.eratw.port.core.stmt.LineParser(
            com.eratw.port.core.stmt.SymbolTable()
                .declareVariable("LV", com.eratw.port.core.expr.OperandType.INT, 0)
                .declareVariable("LW", com.eratw.port.core.expr.OperandType.INT, 0)
                .declareVariable("RESULT", com.eratw.port.core.expr.OperandType.INT, 0),
        )
        return lines.mapIndexed { i, t ->
            p.parseLogicalLine(com.eratw.port.core.lexer.LogicalLine(com.eratw.port.core.lexer.LineKind.CODE, "t.erb", i + 1, t))
        }.filterNotNull()
    }
}
