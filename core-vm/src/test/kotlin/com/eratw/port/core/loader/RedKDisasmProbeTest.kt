package com.eratw.port.core.loader

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * 红 K 取证（2026-10-25 收口读数）：反汇编真产物里 GMF_METHOD_RESTORE 的环区字节码。
 * **结论：字节码健康** —— entry=74452，marker 指向本函数语句 #22593-#22610（行 74-92），
 * 四条 FOR 的 INIT/判定/回边/STEP 接线全部正确（读数落 build/redk-disasm.txt）。
 * 真机 trace 的「p74492/p74493 交替」= 环体(#22608)↔步进(#22609) 的正常循环形态，
 * 计数器不前进的病灶不在编译器/erbc 面（最小同形 + erbc 往返桌面均不复现，
 * 见 ForBackEdgeReproTest 三变体），留给设备侧取证。
 * 注意指令对齐：pc 是**指令号**，字偏移 = pc shl 2（Vm.kt 取指口径）。
 */
class RedKDisasmProbeTest {

    @Test
    fun `反汇编 GMF_METHOD_RESTORE 环区`() = dumpFunction("GMF_METHOD_RESTORE", "build/redk-disasm.txt")

    @Test
    fun `反汇编 CHARA_MOVEMENT 环区`() = dumpFunction("CHARA_MOVEMENT", "build/redk-chara-disasm.txt")

    private fun dumpFunction(name: String, outFile: String) {
        val erbcFile = artifactDir()?.resolve("program.erbc")
        assumeTrue(erbcFile != null, "无取证产物，跳过")
        val program = ProgramLoader.loadMapped(erbcFile!!) { }
        val fn = program.function(name)
        assumeTrue(fn != null, "产物里没有 $name")
        val start = fn!!.entryPc
        // 函数边界：下一个函数入口（粗界即可，多 dump 一段无妨）
        val sortedEntries = program.functions.map { it.entryPc }.filter { it > start }.sorted()
        val end = (sortedEntries.firstOrNull() ?: (start + 400)).coerceAtMost(start + 2000)
        val sb = StringBuilder()
        sb.append(name).append(" entryPc=").append(start).append(" arity=")
            .append(fn.arity).append(" slots=").append(fn.slotCount).append('\n')
        var pc = start
        while (pc < end) {
            val w = pc shl 2
            val op = program.codeSource.word(w)
            val a = program.codeSource.word(w + 1)
            val b = program.codeSource.word(w + 2)
            val c = program.codeSource.word(w + 3)
            val name = try {
                com.eratw.port.core.code.Opcode.nameOf(op)
            } catch (_: Throwable) {
                "op$op"
            }
            val extra = when {
                name.contains("JUMP") && !name.contains("IF") ->
                    " -> target=${pc + 1 + a} (delta=$a)"
                name.contains("JUMP") -> " -> if(${a}) target=${pc + 1 + b} (delta=$b)"
                name.contains("STMT") || op == 121 -> {
                    val s = program.statement(a)
                    val detail = (s as? com.eratw.port.core.stmt.AssignStmt)?.let { asn ->
                        val arg = asn.args
                        " dest=${asn.dest} op=${asn.op} arg=${arg.javaClass.simpleName}" +
                            (arg as? com.eratw.port.core.stmt.SpSetArgument)?.let {
                                " isConst=${it.isConst} constInt=${it.constInt} addConst=${it.addConst} term=${it.term}"
                            }.orEmpty()
                    }.orEmpty()
                    " stmt#$a L${s.line} ${s.javaClass.simpleName} raw=${s.raw.take(40)}$detail"
                }
                else -> ""
            }
            sb.append("p").append(pc).append(": ").append(name).append(' ').append(a)
                .append(',').append(b).append(',').append(c)
                .append('@').append(lineOf(program, pc)).append(extra).append('\n')
            pc += 1
        }
        Files.writeString(Path.of(outFile), sb.toString())
        println(sb)
        assertTrue(sb.isNotEmpty())
    }

    private fun lineOf(program: com.eratw.port.core.code.Program, pc: Int): Int =
        try {
            program.lineOf(pc)
        } catch (_: Throwable) {
            -1
        }

    private fun artifactDir(): Path? {
        System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        return listOf(
            Path.of("build", "forensic-erbc", "erbc.v1"),
        ).firstOrNull { Files.isRegularFile(it.resolve("program.erbc")) }
    }
}
