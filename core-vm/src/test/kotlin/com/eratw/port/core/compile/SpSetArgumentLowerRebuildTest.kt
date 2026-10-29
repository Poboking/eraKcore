package com.eratw.port.core.compile

import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 语句池降级（[ErbProgramCompiler] 的 `exprCalls` 非 null 路径）**不得**吞掉常量增量的折叠形态。
 *
 * 原版语义：常量增量在解析期折成 `Term=null, IsConst=true, AddConst=true, ConstInt=±N`
 * （`ArgumentParser.cs:1004-1015`；`-=` 的负号已带上，`:1011`），执行期按
 * `IsConst ? ConstInt : Term` 取值、`AddConst ? ChangeValue : SetValue`
 * （`FunctionIdentifier.cs:647-658`）。
 *
 * 触发面：降级会把 dest 下标里的表达式位用户方法调用换成「前导 `_CALL` + 返回哨兵」
 * （[ErbProgramCompiler] 的 [lowerVarRef] → `v.copy(indices = idx)`），于是
 * [lowerStatementForPool] / [lowerArgument] 会**重建** `SpSetArgument` 副本。
 * 副本若只迁 `addConst` 而不迁 `isConst/constStr/constInt`，`ARR:FN():2 += 2` 在池里
 * 就退化成「`Term=null, IsConst=false, AddConst=true`」——执行层取值恒 0，增量静默失效
 * （与红 J 的 `FOR` 步进退化同族：循环变量永不推进 ⇒ 回边永真）。
 */
class SpSetArgumentLowerRebuildTest {

    private fun parse(texts: List<String>): List<com.eratw.port.core.stmt.Statement> {
        val symbols = SymbolTable()
            .declareVariable("ARR", OperandType.INT, 2)
            .declareFunction("FN", OperandType.INT)
            .declareMethod("FN")
        val parser = LineParser(symbols)
        return texts.mapIndexedNotNull { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            parser.parseLogicalLine(LogicalLine(kind, "rebuild.erb", i + 1, text))
        }
    }

    @Test
    fun `dest 下标含表达式位调用时 重建副本仍保留常量折态`() {
        val compiler = ErbProgramCompiler()
        val table = ExprCallTable(intMethods = setOf("FN"))
        compiler.compileFunction(
            FuncSig("FN", 0, isMethod = true),
            parse(listOf("@FN", "RETURNF 1")),
            setOf("FN", "T"),
            table,
        )
        compiler.compileFunction(
            FuncSig("T", 0),
            parse(listOf("@T", "ARR:FN():2 += 2")),
            setOf("FN", "T"),
            table,
        )
        val program = compiler.build("T")
        val set = (0 until program.statementCount)
            .map { program.statement(it) }
            .filterIsInstance<AssignStmt>()
            .single { it.dest.name == "ARR" }
        // 前提：dest 下标确实被降级（否则本用例没走到重建分支，锁不住目标缺陷）。
        assertTrue(
            set.dest.indices[0] is FuncCall &&
                (set.dest.indices[0] as FuncCall).name.startsWith(EXPR_TEMP_PREFIX),
            "下标 0 应已被降级成返回哨兵，实际 ${set.dest.indices}",
        )
        val a = set.args as SpSetArgument
        assertTrue(a.isConst, "重建副本丢了 IsConst（ArgumentParser.cs:1006-1013）")
        assertTrue(a.addConst, "重建副本丢了 AddConst")
        assertEquals(2L, a.constInt, "重建副本丢了 ConstInt（+= 2）")
        assertEquals(null, a.term, "折叠形态 Term 必须为 null")
    }
}
