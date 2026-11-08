package com.eratw.port.core.sem

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ARRAYCOPY 的只读目标闸（B5，2026-11-07）：官方**执行期也拦**的那一类，删掉存储层常量闸后
 * 必须留在执行期，且文案照官方。
 *
 * 证据：非常量名路径 `Process.cs:1748-1750`
 * `if (array3[1].IsConst) throw new CodeEE(string.Format(Lang.Error.ArraycopyArgIsConst.Text, "2", array4[1]))`；
 * 常量名路径在解析期用**同一条文案** warn（`ArgumentParser.cs:2481-2484`，level 2 = 行级错误）。
 * 文案 = zhs:513 `ARRAYCOPY命令的第{0}参数"{1}"是一个只读变量`。
 *
 * 反向病（删闸前）：移植侧存储层常量闸把它包成「变量 X 是常量，不能赋值」，文案与官方不同；
 * 删闸后若不留这一条，常量目标会被**静默写穿**。本闸两向都钉：只读目标抛官方文案，
 * 正常目标照常复制。
 */
class ArrayCopyConstTargetTest {

    private fun resolver(): SymbolTable = SymbolTable()
        .declareVariable("SRCARR", OperandType.STR, 1)
        .declareVariable("CONSTARR", OperandType.STR, 1, isConst = true)

    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(resolver())
        return lines.mapIndexedNotNull { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            p.parseLogicalLine(LogicalLine(kind, "arraycopyconst.erb", i + 1, text))
        }
    }

    private fun compile(vararg lines: String): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(
            FuncSig("ARRAYCOPY_CONST_PROBE", 0),
            parse("@ARRAYCOPY_CONST_PROBE", *lines),
            setOf(),
            ExprCallTable(),
        )
        return c.build("ARRAYCOPY_CONST_PROBE")
    }

    /** 同形真语料：#DIMS CONST 的目标数组 + 普通来源数组。 */
    private fun storeWithArrays(constDest: Boolean): VariableStore {
        val store = VariableStore()
        store.declareUserVar(
            DimSpec(name = "SRCARR", isStr = true, dims = intArrayOf(2), defaultsStr = listOf("a", "b")),
        )
        store.declareUserVar(
            DimSpec(
                name = "CONSTARR", isStr = true, dims = intArrayOf(2), isConst = constDest,
                defaultsStr = listOf("x", "y"),
            ),
        )
        return store
    }

    private fun runtimeWith(program: Program, store: VariableStore): ProcessRuntime =
        ProcessRuntime(
            program,
            store,
            FunctionTable(emptyList()),
            VmConfig(maxStepsPerRun = 100_000),
            consoleSink = LineBufferConsoleSink(clientWidth = 136, printCWidth = 25, maxLines = 900),
        )

    @Test
    fun `只读目标的执行期闸照官方文案`() {
        val store = storeWithArrays(constDest = true)
        val e = assertFailsWith<CodeEE> { runtimeWith(compile("ARRAYCOPY \"SRCARR\", \"CONSTARR\""), store).execute() }
        assertEquals(
            "ARRAYCOPY命令的第2参数\"CONSTARR\"是一个只读变量",
            e.message,
            "Process.cs:1748-1750 + zhs:513 逐字",
        )
        // 一行都没写进去（抛点在复制之前）。
        assertEquals("x", store.readStr("CONSTARR", longArrayOf(0)))
    }

    @Test
    fun `非常量目标照常复制`() {
        val store = storeWithArrays(constDest = false)
        runtimeWith(compile("ARRAYCOPY \"SRCARR\", \"CONSTARR\""), store).execute()
        assertEquals("a", store.readStr("CONSTARR", longArrayOf(0)))
        assertEquals("b", store.readStr("CONSTARR", longArrayOf(1)))
    }
}
