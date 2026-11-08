package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.NativeParam
import com.eratw.port.core.fn.nativeMethods
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * B1a：CALLSHARP「No native method」的**行级联结记录**（2026-11-06 报错相位翻案）。
 *
 * 原版口径：联结期 `CALLSHARP_Instruction.SetJumpTo`（FunctionIdentifier.cs:1193-1210）对常量名
 * 查 `PluginManager.HasMethod`，未命中 `:1204 ParserMediator.Warn(isError: true)` 只把
 * `line.IsError/ErrMes` 记到行上（ParserMediator.cs:129-130），命中把 CallFunc 绑死（:1208），
 * **此后不再重查**；执行期走到该行 Process.cs:1017-1019 抛 `CodeEE(ErrMes)` —— 抛的是
 * **记录值**，不是当次的查询结果。
 *
 * 移植侧裁决点后移到「该行第一次执行」（注册表挂 store.aux、宿主装配晚于语料装载，
 * GameHost.kt:3476 ⇒ 装载期注册表恒空，照抄原版相位会误杀已接桩的 ModelSet/Chat）；
 * 机制与不重查口径见 `core/fn/PluginMethods.kt` 的 SharpLinkTable。
 * 本钉四例把「记行错 → 抛记录值 → 不复活 / 命中 → 绑死 → 不受 clear 影响」
 * 在对象树通道（Android 生产默认）与扁平通道（归因/装置）各钉一遍。
 */
class CallSharpLinkPhaseTest {

    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "linkphase.erb", i + 1, text))
        }.filterNotNull()
    }

    private fun compile(vararg lines: String): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(
            FuncSig("SHARP_LINK_PROBE", 0),
            parse("@SHARP_LINK_PROBE", *lines),
            setOf(),
            ExprCallTable(),
        )
        return c.build("SHARP_LINK_PROBE")
    }

    private fun runtimeWith(program: Program, store: VariableStore): ProcessRuntime =
        ProcessRuntime(
            program,
            store,
            FunctionTable(emptyList()),
            VmConfig(maxStepsPerRun = 100_000),
            consoleSink = LineBufferConsoleSink(clientWidth = 136, printCWidth = 25, maxLines = 900),
        )

    /** 对象树通道（Android 生产默认形态）：关掉 flat 直读（Program 的 internal 开关，同模块测试可及）。 */
    private fun asTreeChannel(program: Program): Program {
        program.flatStatementsEnabled = false
        return program
    }

    // -----------------------------------------------------------------
    // 未命中：行级错误记一次，之后抛记录值（注册表再长出同名也不复活）
    // -----------------------------------------------------------------

    @Test
    fun `flat 未命中首执行记行错 接线后重执行仍抛记录值`() {
        val store = VariableStore()
        val rt = runtimeWith(compile("CALLSHARP Ghost(1)"), store)
        val first = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals("No native method Ghost found", first.message, "首执行报官方原文（FunctionIdentifier.cs:1204）")
        // 模拟「宿主此刻才把方法接上」：行级错误已记死，重执行照记录值重抛 ——
        // 原版 CallFunc 在联结期已定（:1208），IsError 行不因后注册而复活。
        nativeMethods(store.aux).register("Ghost") { }
        val second = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals(first.message, second.message, "二次执行抛记录值，不重查注册表")
    }

    @Test
    fun `对象树通道未命中同口径`() {
        val store = VariableStore()
        val rt = runtimeWith(asTreeChannel(compile("CALLSHARP Ghost(1)")), store)
        val first = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals("No native method Ghost found", first.message)
        nativeMethods(store.aux).register("Ghost") { }
        assertEquals(first.message, assertFailsWith<CodeEE> { rt.execute() }.message)
    }

    // -----------------------------------------------------------------
    // 命中：方法本体绑死（= 原版联结期 CallFunc），clear 注册表不影响已绑定行
    // -----------------------------------------------------------------

    @Test
    fun `flat 命中绑死 清空注册表后该行照常执行`() {
        val store = VariableStore()
        val calls = mutableListOf<Int>()
        nativeMethods(store.aux).register("Ghost") { _: Array<NativeParam> -> calls.add(calls.size) }
        val rt = runtimeWith(compile("CALLSHARP Ghost(1)"), store)
        rt.execute()
        assertEquals(listOf(0), calls, "首执行走注册表命中")
        // 原版 ClearMethods（PluginManager.cs:545-548）不清已绑定的 SpCallSharpArgment.CallFunc
        // ⇒ 联结期绑定的行继续可用；移植侧绑定缓存在 SharpLinkTable，同口径。
        nativeMethods(store.aux).clear()
        rt.execute()
        assertEquals(listOf(0, 1), calls, "重执行用绑定的方法本体，不重查（也不因空表报错）")
    }

    @Test
    fun `对象树通道命中绑死同口径`() {
        val store = VariableStore()
        val calls = mutableListOf<Int>()
        nativeMethods(store.aux).register("Ghost") { _: Array<NativeParam> -> calls.add(calls.size) }
        val rt = runtimeWith(asTreeChannel(compile("CALLSHARP Ghost(1)")), store)
        rt.execute()
        nativeMethods(store.aux).clear()
        rt.execute()
        assertEquals(listOf(0, 1), calls)
    }

    // -----------------------------------------------------------------
    // 首执行前的判名不消耗实参求值：无桩时同函数里 CALLSHARP 之前的语句照常生效
    // -----------------------------------------------------------------

    @Test
    fun `无桩抛点在该行 实参求值前不误伤前置语句`() {
        // Process.cs:1017-1019 的 IsError 抛点在 DoInstruction 之前 ⇒ 该行自己的实参不求值，
        // 前一行的 PRINT 正常生效（flat 链上 decoded 面也不受影响）。
        val store = VariableStore()
        val rt = runtimeWith(compile("PRINTL marker", "CALLSHARP Ghost(1)"), store)
        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals("No native method Ghost found", e.message)
        assertTrue(
            "marker" in rt.output.toString(),
            "前置 PRINTL 已生效：错误只属于 CALLSHARP 那一行",
        )
    }
}
