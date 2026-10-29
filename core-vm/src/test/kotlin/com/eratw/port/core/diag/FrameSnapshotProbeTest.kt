package com.eratw.port.core.diag

import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
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
import kotlin.test.assertTrue

/**
 * **帧局部变量导出**（红 J / 红 K 取证缺口）的回归锁。
 *
 * ## 现场（2026-10-25 真机）
 *
 * 红 J 在 `ABL.ERB:88` 的 `FOR LOCAL:1,0,100` 里烧穿 5,000,001 步；装置侧要读
 * `LOCAL:1` 判定「FOR 计数器有没有写回」，`var LOCAL:1` 却一律「不可读」——常规读口
 * 只按**当前帧**解析 LOCAL（[VariableStore.readInt] → `localFor` → `topFrame`），
 * 而取证总在撞闸之后：`Vm.run` 的 finally 已把调用栈回卷。
 *
 * ## 锁的两件事
 *
 * 1. **抛点快照**：撞闸异常自带逐帧 `LOCAL/ARG` 现值（`StepLimitExceeded.frameSnapshot`
 *    ← `VmHost.frameSnapshotLines` ← [VariableStore.frameSnapshotLines]），宿主事后也能看到。
 * 2. **LOCAL 不被被调方清掉**：调用方 `FOR LOCAL:1,0,3` 的计数器，在被调函数体内
 *    `VARSET LOCAL` 之后必须还在（原版 `LocalVariableToken` 以 subKey = 函数名 为键，
 *    无 ScopeIn/ScopeOut，spec: IdentifierDictionary.cs:454-471、LocalVariableToken.cs:7-58）
 *    —— 这正是红 J「计数器写回丢失」假设的桌面反证。若本用例红，红 J 就在桌面可复现。
 */
class FrameSnapshotProbeTest {

    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "framesnap.erb", i + 1, text))
        }
    }

    private data class Fn(val name: String, val lines: Array<String>)

    private fun compile(vararg fns: Fn): Pair<ErbProgramCompiler, Program> {
        val c = ErbProgramCompiler()
        val names = fns.map { it.name }.toSet()
        for (f in fns) c.compileFunction(FuncSig(f.name, 0), parse(*f.lines), names)
        return c to c.build(fns.first().name)
    }

    private fun run(
        c: ErbProgramCompiler,
        program: Program,
        store: VariableStore,
        fns: List<Fn>,
        maxSteps: Long,
    ): ProcessRuntime {
        val metas = fns.map { f ->
            val idx = c.functions.first { it.sig.name == f.name }.index
            FuncMeta(index = idx, key = f.name, argIntCount = 0, argStrCount = 0, defIndex = idx)
        }
        val rt = ProcessRuntime(program, store, FunctionTable(metas), VmConfig(maxStepsPerRun = maxSteps))
        rt.execute()
        return rt
    }

    @Test
    fun `撞闸异常带着帧局部变量快照（LOCAL 计数器可读）`() {
        val fns = arrayOf(Fn("SPIN", arrayOf("@SPIN", "LOCAL:0 = 0", "\$L", "LOCAL:0 += 1", "GOTO L")))
        val (c, program) = compile(*fns)
        val store = VariableStore()
        store.defineLocalLengths("SPIN", VariableStore.LocalLengths(local = 4))
        val t = assertFailsWith<StepLimitExceeded> {
            run(c, program, store, fns.toList(), maxSteps = 200)
        }
        assertEquals(0, store.callDepth(), "异常离栈后调用栈必须已回卷")
        assertTrue(t.frameSnapshot.isNotEmpty(), "抛点必须带帧快照（否则真机仍读不到 LOCAL）")
        val top = t.frameSnapshot.first()
        assertTrue(top.startsWith("#0 SPIN LOCAL[4]="), "快照首行应是以 #0 入口帧为键的 LOCAL 现值，实际：$top")
        val counter = top.substringAfter("LOCAL[4]=").substringBefore(' ').split(',').first().toLong()
        assertTrue(counter > 0, "LOCAL:0 在转圈过程中必须被推进，实际 $counter")
    }

    @Test
    fun `被调方 VARSET LOCAL 不清调用方的 FOR 计数器`() {
        val fns = arrayOf(
            Fn("CALLER", arrayOf("@CALLER", "FOR LOCAL:1, 0, 3", "CALL CALLEE", "NEXT", "RETURN")),
            Fn("CALLEE", arrayOf("@CALLEE", "VARSET LOCAL", "RETURN")),
        )
        val (c, program) = compile(*fns)
        val store = VariableStore()
        store.defineLocalLengths("CALLER", VariableStore.LocalLengths(local = 4))
        store.defineLocalLengths("CALLEE", VariableStore.LocalLengths(local = 4))
        run(c, program, store, fns.toList(), maxSteps = 10_000)
        assertEquals(0, store.callDepth(), "帧必须全部退出（enter/leave 配对）")
        store.enterFunction("CALLER")
        assertEquals(
            3L, store.readInt("LOCAL", longArrayOf(1)),
            "LOCAL 按函数名分实例（IdentifierDictionary.cs:454-471）⇒ 被调 VARSET LOCAL 不得清调用方计数器",
        )
        store.leaveFunction()
    }
}
