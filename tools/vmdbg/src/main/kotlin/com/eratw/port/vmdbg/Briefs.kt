package com.eratw.port.vmdbg

import com.eratw.port.core.code.Disassembler
import com.eratw.port.core.code.Program
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.CallFStmt
import com.eratw.port.core.stmt.CallStmt
import com.eratw.port.core.stmt.IfStmt
import com.eratw.port.core.stmt.JumpStmt
import com.eratw.port.core.stmt.MethodStmt
import com.eratw.port.core.stmt.Statement

/**
 * vmdbg 自写的语句/表达式短摘要（CONTRACT-DBG1 路2：core 的 ProgramDiagnostics.brief /
 * briefVar 是 internal（diag/ProgramDiagnostics.kt:146/160），跨模块不可见；输出格式
 * 不必与它逐字节对齐，语义对齐：类名 + 行号 + 关键字段）。
 */
object Briefs {

    /** 语句短摘要：`类名(line=N)` + 赋值目标/调用/跳转/分支关键字段。trace 与诊断输出共用。 */
    fun stmt(s: Statement?): String {
        if (s == null) return "(null)"
        val sb = StringBuilder(s.javaClass.simpleName).append("(line=").append(s.line).append(')')
        when (s) {
            is AssignStmt -> sb.append(" dest=").append(expr(s.dest)).append(" op=").append(s.op)
            is CallStmt -> sb.append(" label=").append(s.label)
            is CallFStmt -> sb.append(" fn=").append(s.functionName)
            is MethodStmt -> sb.append(" method=").append(s.methodName)
            is JumpStmt -> sb.append(" label=").append(s.label)
            is IfStmt -> sb.append(' ').append(expr(s.condition))
            else -> sb.append(' ').append(s.code)
        }
        return sb.toString()
    }

    /** 表达式短摘要：VarRef 打 name[下标]，IntLiteral 打值，其余类名 + 截断原文。 */
    fun expr(e: Expr?): String = when (e) {
        null -> "(null)"
        is VarRef ->
            e.name + "[" + e.indices.joinToString(",") { idx ->
                (idx as? IntLiteral)?.value?.toString() ?: "?"
            } + "]"
        is IntLiteral -> "Int(" + e.value + ")"
        else -> e.javaClass.simpleName + ":" + e.toString().take(60)
    }
}

/**
 * 反汇编区间切片：`Disassembler.disassemble(program)`（Disassembler.kt:12，public object）
 * 对 pc `0..instructionCount-1` 各输出**恰好一条** 4 空格缩进的指令行（Disassembler.kt:36-40
 * 的单遍循环序），因此第 k 条缩进行就是 pc k 的规范文本 —— 按序计数即可切片，不重写
 * 指令格式化器。
 *
 * 代价：每次调用整程序反汇编一遍（真语料 4,800,579 指令 ≈ 数秒 + 大字符串）。
 * 调试 CLI 的单发命令可接受；高频调用场景未来换 per-pc formatter 时保持本入口不变。
 */
object DisasmSlice {

    /** 一条反汇编行：pc + 该 pc 的指令四字组 + Disassembler 规范文本。 */
    data class Row(val pc: Int, val words: IntArray, val line: Int, val text: String)

    /** 取 `[fromPc, fromPc+count)` 的指令行；`count` 被程序末尾截断。 */
    fun slice(program: Program, fromPc: Int, count: Int): List<Row> {
        val end = (fromPc + count).coerceAtMost(program.instructionCount)
        if (fromPc < 0 || fromPc >= end) return emptyList()
        val full = Disassembler.disassemble(program)
        val out = ArrayList<Row>(end - fromPc)
        var pc = 0
        for (raw in full.split('\n')) {
            if (!raw.startsWith("    ")) continue
            if (pc >= fromPc) {
                out.add(
                    Row(
                        pc = pc,
                        words = intArrayOf(program.op(pc), program.a(pc), program.b(pc), program.c(pc)),
                        line = program.lineOf(pc),
                        text = raw.trim(),
                    ),
                )
            }
            pc++
            if (pc >= end) break
        }
        return out
    }
}
