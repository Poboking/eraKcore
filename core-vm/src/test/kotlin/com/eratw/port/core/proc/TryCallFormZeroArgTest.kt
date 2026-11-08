package com.eratw.port.core.proc

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TRYCALLFORM 零参调用 `@F(ARG)` 的隐式缺省复现（OPTION 补丁页错乱排查的定位件）。
 *
 * 真机语料（4.981-EAGLOV3）`UPDATE_DLC_Toggle` 以 `TRYCALLFORM OTHER_DLC_Toggle_{LOCAL}`
 * 零参调用 `@OTHER_DLC_Toggle_70(ARG)`（Addition_DLC_70.ERB:29，ARG 形参无显式缺省）。
 * 原版 ErbLoader.parseLabel:714-722 对 ARG/ARGS/私有形参的无缺省位折成
 * `SingleLongTerm(0)` / `SingleStrTerm("")`（CalledFunction.cs:165-173 消费）⇒ 零参调用合法、
 * ARG:0 = 0。本用例钉住：零参调用不抛「参数不能省略」、ARG:0 = 0。
 */
class TryCallFormZeroArgTest {

    private class RecordingConsole : ConsoleSink {
        val text = StringBuilder()

        override val printingBufferEmpty: Boolean get() = false

        override fun print(text: String) { this.text.append(text) }
        override fun printLine(text: String) { this.text.append(text).append('\n') }
        override fun printC(text: String, alignRight: Boolean) { this.text.append(text) }
        override fun printSingleLine(text: String) { this.text.append(text).append('\n') }
        override fun printButton(text: String, value: Long) { this.text.append(text) }
        override fun printButton(text: String, value: String) { this.text.append(text) }
        override fun printButtonC(text: String, value: Long, alignRight: Boolean) { this.text.append(text) }
        override fun printButtonC(text: String, value: String, alignRight: Boolean) { this.text.append(text) }
        override fun printPlain(text: String) { this.text.append(text) }
        override fun printTemporaryLine(text: String) {}
        override fun printBar() {}
        override fun printCustomBar(text: String) {}
        override fun newLine() { this.text.append('\n') }
        override fun deleteLine(count: Int) {}
        override fun clearText() {}
        override fun setForeColor(r: Int, g: Int, b: Int) {}
        override fun setBackColor(r: Int, g: Int, b: Int) {}
        override fun resetForeColor() {}
        override fun resetBackColor() {}
        override fun setFontStyle(mask: Int) {}
        override fun setFont(name: String) {}
        override fun setAlignment(kind: Alignment) {}
        override fun setRedraw(mode: Int) {}
        override fun printHtml(text: String, option: Boolean) {}
        override fun addHtmlIsland(text: String) {}
        override fun clearHtmlIslands() {}
        override fun printError(text: String) { this.text.append(text).append('\n') }
    }

    private fun runtimeWith(program: Program, console: RecordingConsole, metas: List<FuncMeta>): ProcessRuntime =
        ProcessRuntime(
            program, VariableStore(), FunctionTable(metas), VmConfig(),
            consoleSink = console,
        )

    @Test
    fun `TRYCALLFORM 零参调用 ARG 形参函数 隐式补 0 不报省略错`() {
        val console = RecordingConsole()
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("OTHER_DLC_Toggle_70"), 0, 1)
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(0), Int.MIN_VALUE, SlotAddr.NONE, 1) // try 位
        b.emit(Opcode._PRINT_LITERAL, b.string("after"), 1, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        val entry = b.here()
        b.declareFunction("OTHER_DLC_TOGGLE_70", entry, 1, 2, paramNames = listOf("ARG"))
        b.emit(Opcode._PRINT_LITERAL, b.string("callee"), 1, 0, 2)
        val argRef = com.eratw.port.core.expr.VarRef(
            "ARG", listOf(com.eratw.port.core.expr.IntLiteral(0)), operandType = com.eratw.port.core.expr.OperandType.INT,
        )
        b.emit(Opcode._EVAL_INT, b.expr(argRef), SlotAddr.local(1), 0, 2)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(1), 1, 0, 2)
        b.emit(Opcode._RET, SlotAddr.local(0), 0, 0, 2)
        val program = b.build(0)

        val meta = FuncMeta(index = 1, key = "OTHER_DLC_TOGGLE_70", name = "OTHER_DLC_Toggle_70")
        val table = FunctionTable(listOf(meta))
        assertEquals(FunctionTable.TargetKind.NON_EVENT, table.classify("OTHER_DLC_Toggle_70"), "classify")
        val rt = runtimeWith(program, console, metas = listOf(meta))
        assertEquals(1, rt.resolveFunctionIndex("OTHER_DLC_Toggle_70"), "resolveFunctionIndex")
        rt.execute()
        assertEquals("callee\n0\nafter\n", console.text.toString())
        assertEquals(listOf("ARG"), program.paramNames(1))
    }
}