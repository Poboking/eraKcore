package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CMP-26：**登记先于发射**。`declaredNames.add` / `CodeBuilder.declareFunction` 若发生在
 * 正式发射遍（pass2）之前，pass2 一旦抛错就留下「名字已占 + 无尾 `_RET` 的截断体」：
 *
 * 1. 截断体的入口 pc 指向失败点之后的第一条指令 —— 执行到该函数会顺延跑进**下一函数**；
 * 2. 名字已在 `declaredNames`/`CodeBuilder.nameToIndex` 里 ⇒ 调用方
 *    （`CorpusCompile.compileOne` 的 `catch (CompileException)` 兜底，`CorpusCompile.kt:564-574`）
 *    用空体重编时直接撞「函数重复声明」，安全桩落不了 ⇒ `compiled` 缺条目 ⇒
 *    任何调用点都会在 `build()` 以「未定义的函数」炸掉整个 Program。
 *
 * 原版是**行级**错误：装载照常完成、错误行执行到才抛（spec: ErbLoader.cs:1249-1255 弹栈续装；
 * Process.cs:1017-1019 `currentLine.IsError → throw CodeEE`）。本侧以函数为装载单元，
 * 对齐方式是「失败不得留下半截注册」。
 *
 * 语料当前 0 编译失败（BOARD CMP-26「需新语料或一处 pass2 独有抛点 ⇒ 潜伏」），故这里用
 * **故障注入**把 pass2 独有失败摆上台面：语句表在第 4 遍遍历（= pass2 的 `run()`）第 2 条抛。
 */
class Cmp26RegistrationRollbackTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareFunction("MISSING", OperandType.INT),
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
            p.parseLogicalLine(LogicalLine(kind, "cmp26.erb", i + 1, text))
        }
    }

    /**
     * 第 4 遍遍历（pass2 `run()`）第 2 条起抛。
     *
     * 遍历序：pass1 = `FunctionPass` 构造里的 `labelNames` 预扫描（1）+ `run()`（2）；
     * pass2 = 构造（3）+ `run()`（4）。前 3 遍共 `3 * size` 次 `get`，
     * 下一遍的第 2 条 = 第 `3 * size + 2` 次。
     */
    private class FailOnSecondPass(private val real: List<Statement>) : AbstractList<Statement>() {
        private var gets = 0
        override val size: Int get() = real.size
        override fun get(index: Int): Statement {
            gets++
            if (gets == 3 * real.size + 2) throw IllegalStateException("注入：pass2 独有失败")
            return real[index]
        }
    }

    @Test
    fun `pass2 抛错后函数名不占位_空体安全桩可落且带尾 _RET`() {
        val c = ErbProgramCompiler()
        // 前置函数：制造「共享 CodeBuilder 里已有指令」的真实现场（截断体会顺延到它的位置）。
        c.compileFunction(FuncSig("PRE"), parse("PRINTL \"PRE\""))

        val boom = FailOnSecondPass(parse("PRINTL \"A\"", "PRINTL \"B\""))
        assertFailsWith<IllegalStateException>("pass2 独有失败必须冒到调用方（本侧由 CorpusCompile 兜底）") {
            c.compileFunction(FuncSig("BAD"), boom)
        }

        assertFalse(
            "BAD" in c.knownFunctionNames,
            "pass2 失败后不得留下已登记的函数名 —— 否则调用方的空体兜底会撞「函数重复声明」",
        )

        // 调用方的兜底路径（CorpusCompile.kt:568-571）：空体重编必须能落地。
        val stub = c.compileFunction(FuncSig("BAD"), emptyList())
        val p = c.build("PRE")

        assertEquals(2, stub.instructionCount, "空体安全桩 = 合成 `RESULT = 0` marker + 兜底 `_RET`")
        assertEquals(
            Opcode._RET, p.instructionAt(stub.endPc - 1).op,
            "安全桩必须以 `_RET` 收尾（截断体不得无尾）",
        )
        assertEquals(
            1, p.functions.count { it.name == "BAD" },
            "函数表里 BAD 只能有一条（半截注册 + 安全桩重复登记会错位）",
        )
        assertEquals(
            stub.entryPc, p.functions.first { it.name == "BAD" }.entryPc,
            "函数表入口必须指向安全桩首条指令，而不是 pass2 的截断位置",
        )
    }

    @Test
    fun `事件入口失败后名字不占位_补齐函数体后可重编`() {
        val c = ErbProgramCompiler()
        val e = assertFailsWith<CompileException> {
            c.compileEventEntry("EV", listOf("MISSING"))
        }
        assertTrue("未编译" in (e.message ?: ""), "应报未编译的函数体，实际=${e.message}")
        assertFalse(
            "EV" in c.knownFunctionNames,
            "事件入口发射失败后不得占住名字（否则同一合成入口无法重试）",
        )

        c.compileFunction(FuncSig("MISSING"), parse("RETURN 1"))
        val fb = c.compileEventEntry("EV", listOf("MISSING"))
        val p = c.build("EV")
        assertEquals(Opcode._RET, p.instructionAt(fb.endPc - 1).op, "事件入口以 `_RET` 收尾")
    }
}
