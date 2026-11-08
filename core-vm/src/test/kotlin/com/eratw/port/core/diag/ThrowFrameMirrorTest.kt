package com.eratw.port.core.diag

import com.eratw.port.core.code.CodeEE
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **抛点帧链镜像**（[VirtualMachine.funcStackAtThrow]）的回归锁。
 *
 * ## 现场（2026-11-01 上报端点）
 *
 * `VirtualMachine.run()` 的 finally 在异常离栈时 [unwindScopes] 把 `depth` 收成 -1，
 * 而错误报告是宿主**拿到异常之后**才写的 ⇒ 采集侧反射读到的实时帧表恒空。端点当时
 * 现存 100 份报告里 **93 份**的「[ERB 调用栈与 VM 现场]」一节只剩采集器自己的 Java 栈：
 * CodeEE 只留下最内层那一个函数名，「哪个口上链走到这里」永远读不出（OOM 那类连函数名都没有）。
 * 同一条路在 [FrameSnapshotProbeTest] 里已经为「帧局部变量」结清过一次（`StepLimitExceeded.frameSnapshot`
 * 就是抛点就地导出的），这一刀把**帧链**也补上。
 *
 * ## 锁的三件事
 *
 * 1. 异常离栈后镜像仍在，且下标序与实时 `funcStack` 一致（最外层在前）——宿主按同一套
 *    格式化（`VmStack.frameLines(mirror, mirror.size - 1, …)`）就能出 `#0 = 最内帧`；
 * 2. 正常返回的 run 不留镜像，且每次 run 起跑清零（不许把上一局的链挂到下一条报告）；
 * 3. 采集侧反射口（字段 `vm` / 方法 `funcStackAtThrow`）在本测试里**真的走一遍**，
 *    改名不会只在设备上静默降级。
 */
class ThrowFrameMirrorTest {

    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "throwmirror.erb", i + 1, text))
        }
    }

    private data class Fn(val name: String, val lines: Array<String>)

    private fun compile(vararg fns: Fn): Pair<ErbProgramCompiler, Program> {
        val c = ErbProgramCompiler()
        val names = fns.map { it.name }.toSet()
        for (f in fns) c.compileFunction(FuncSig(f.name, 0), parse(*f.lines), names)
        return c to c.build(fns.first().name)
    }

    private fun runtime(c: ErbProgramCompiler, program: Program, fns: List<Fn>): ProcessRuntime {
        val metas = fns.map { f ->
            val idx = c.functions.first { it.sig.name == f.name }.index
            FuncMeta(index = idx, key = f.name, argIntCount = 0, argStrCount = 0, defIndex = idx)
        }
        return ProcessRuntime(program, VariableStore().apply {
            fns.forEach { defineLocalLengths(it.name, VariableStore.LocalLengths(local = 4)) }
        }, FunctionTable(metas), VmConfig())
    }

    /** 反射口与 `ErrorReportCollector` 取实时帧表那条路同源：改名要在这里判红。 */
    private fun vmOf(rt: ProcessRuntime): VirtualMachine {
        val vf = ProcessRuntime::class.java.getDeclaredField("vm")
        vf.isAccessible = true
        return vf.get(rt) as VirtualMachine
    }

    // 最内层函数里的一条真报错：RAND(0) 在 core 侧就是 CodeEE（玩家报告原文同此串）。
    private val throwing = arrayOf(
        Fn("CALLER", arrayOf("@CALLER", "CALL CALLEE", "RETURN")),
        Fn("CALLEE", arrayOf("@CALLEE", "RESULT = RAND(0)")),
    )

    @Test
    fun `异常离栈后镜像带着完整帧链且实时帧表已空`() {
        val (c, program) = compile(*throwing)
        val rt = runtime(c, program, throwing.toList())
        val vm = vmOf(rt)

        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertTrue("RAND" in e.message.orEmpty(), "报错串应保持原样，实际：${e.message}")

        val liveDepth = VirtualMachine::class.java.getDeclaredField("depth")
            .apply { isAccessible = true }.getInt(vm)
        assertEquals(-1, liveDepth, "unwindScopes 已把 depth 收成 -1（这正是报告读不到帧链的原因）")

        val mirror = assertNotNull(vm.funcStackAtThrow(), "抛点必须留下帧链镜像")
        val names = mirror.map { program.functions[it].name }
        assertEquals(
            listOf("CALLER", "CALLEE"),
            names,
            "镜像下标序与实时 funcStack 一致（最外层在前），宿主反着摊才出 #0=最内帧",
        )
    }

    @Test
    fun `正常返回不留镜像且新一次 run 起跑清零`() {
        val fns = arrayOf(
            Fn("CALLER", arrayOf("@CALLER", "CALL CALLEE", "RETURN")),
            Fn("CALLEE", arrayOf("@CALLEE", "RESULT = RAND(0)")),
            Fn("OK", arrayOf("@OK", "RETURN")),
        )
        val (c, program) = compile(*fns)
        val rt = runtime(c, program, fns.toList())
        val vm = vmOf(rt)
        val okIndex = c.functions.first { it.sig.name == "OK" }.index

        assertNull(vm.funcStackAtThrow(), "装配后没跑过就不许有镜像")
        assertFailsWith<CodeEE> { rt.execute() }
        assertNotNull(vm.funcStackAtThrow(), "抛过一次 ⇒ 镜像在场（对照组）")

        // 同一个 VM 实例再正常跑一次：起跑必须把上一次的链清零，否则下一条报告会被挂上一条
        // 不相干的旧链（采集侧只在「本报告确实带异常」时回落，但那一条异常未必来自这一次 run）。
        vm.run(okIndex)
        assertNull(vm.funcStackAtThrow(), "新一次 run 起跑清零，不许留旧链")
    }
}
