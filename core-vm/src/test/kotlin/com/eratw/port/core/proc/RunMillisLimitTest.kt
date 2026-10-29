package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 段落墙钟闸（[VmConfig.maxRunMillis]）—— 「读档有时报错」的回归锁。
 *
 * ## 现场（2026-10-29 玩家反馈）
 *
 * 「读档的时候，有时会出现报错情况。」真机复现过的形状见 BOARD 2026-10-27：
 * 读 9/21 第 47 日旧档（命莲寺），恢复演出里 `EVENTTRAIN → CHARA_MOVEMENT →
 * MOVEMENT共通処理 → BATHROOM → GMF_MAP_METHODF → GMF_METHOD_UPDATE` 累积到
 * **20,000,001 步**，被移植层单段落步数闸拦停，失败屏写
 * 「执行步数超过上限(20000001)，疑似死循环」。第 1 日新档没有这个现象 ⇒ 玩家感知就是
 * 「有时报错」。
 *
 * ## 原版口径（为什么这不是死循环）
 *
 * * 原版**没有「每段指令上限」**：`Process.cs:1000-1012` 的主循环只按行推进，
 *   `runScriptProc` 里唯一的中断是 `checkInfiniteLoop`。
 * * 那个检查是**墙钟**：每 10000 行读一次 `startTime.ElapsedMilliseconds`
 *   （`Process.cs:1012`），超过 `Config.InfiniteLoopAlertTime`（`ConfigData.cs:75`
 *   注册缺省 5000 ms）就弹框问「是否中止」；玩家点「否」⇒ `state.lineCount = 0` +
 *   `startTime.Restart()`（:509-510）**继续跑**。
 * * `startTime` 的重置点是 `UpdateCheckInfiniteLoopState()`（:487-491），唯一调用方是
 *   `EmueraConsole.Await`（`EmueraConsole.cs:849`）⇒ 段落边界 = 让出边界，与
 *   [VmConfig.maxStepsPerRun] 的计数边界同源。
 *
 * 所以「合法重负载跑很久」在原版是**允许**的，移植层必须给它一条出路：时间闸 + 放宽的
 * 步数兜底（生产取值见 `GameSession.PRODUCTION_MAX_STEPS_PER_RUN` /
 * `GameSession.DEFAULT_MAX_RUN_MILLIS`）。
 *
 * ## 本测试锁什么
 *
 * 1. 时间超限时抛 [StepLimitExceeded]，但**文案走时间口径**（否则玩家/台账看到的仍是
 *    「步数超过上限」，等于没区分「跑了太多指令」与「这一段跑太久」）；
 * 2. `maxRunMillis = 0`（装置缺省）时**不启用**时间闸 —— CI 上的确定性不能变；
 * 3. 步数闸的旧文案与旧行为逐字不变（既有装置与失败屏口径）。
 */
class RunMillisLimitTest {

    private data class Fn(val name: String, val lines: Array<String>)

    private class Pb(val program: Program, val names: List<String>) {
        fun index(name: String): Int =
            program.functions.indices.first { program.functions[it].name.equals(name, ignoreCase = true) }
    }

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("LOCAL", OperandType.INT, 1)
            .declareVariable("COUNT", OperandType.INT, 1),
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
            p.parseLogicalLine(LogicalLine(kind, "runmillis.erb", i + 1, text))
        }
    }

    private fun compile(vararg fns: Fn): Pb {
        val parsed = fns.map { parse(*it.lines) }
        val labels = parsed.map { stmts ->
            stmts.filterIsInstance<FuncLabelStmt>().firstOrNull() ?: error("切片缺 @行")
        }
        val compiler = ErbProgramCompiler()
        val known = fns.mapTo(HashSet()) { it.name.uppercase() }
        for ((i, f) in fns.withIndex()) {
            compiler.compileFunction(
                FuncSig(name = f.name, arity = labels[i].paramRefs.size, paramNames = labels[i].paramRefs),
                parsed[i],
                known,
            )
        }
        return Pb(compiler.build(fns.first().name), fns.map { it.name })
    }

    private fun runtime(pb: Pb, store: VariableStore, config: VmConfig): ProcessRuntime {
        val metas = pb.names.map { name ->
            val index = pb.index(name)
            val arity = pb.program.functions[index].arity
            FuncMeta(index = index, key = name, argIntCount = arity, argStrCount = arity, defIndex = index)
        }
        val host = object : BuiltinHost {}
        return ProcessRuntime(pb.program, store, FunctionTable(metas), config, builtinHost = host)
    }

    /** 200 万次空转 `FOR`：约 600 万条指令，远超 65536 步的检查间隔。 */
    private fun longLoop(): Pb = compile(
        Fn(
            "MAIN",
            arrayOf(
                "@MAIN",
                "FOR LOCAL, 0, 2000000",
                "COUNT = LOCAL",
                "NEXT",
                "RETURN",
            ),
        ),
    )

    // ------------------------------------------------------------------
    // 1. 时间闸触发：文案必须是时间口径
    // ------------------------------------------------------------------

    @Test
    fun `段落墙钟超限时抛时间口径的 StepLimitExceeded`() {
        val pb = longLoop()
        val store = VariableStore()
        val rt = runtime(
            pb,
            store,
            // 步数闸放到不可能触发的位置，只留时间闸。
            VmConfig(maxStepsPerRun = Long.MAX_VALUE, maxRunMillis = 1L),
        )
        val e = assertFailsWith<StepLimitExceeded> { rt.execute() }
        val msg = e.message.orEmpty()
        assertTrue(
            msg.contains("执行时间超过上限") && msg.contains("疑似死循环"),
            "时间闸必须用时间文案（玩家/台账据此区分「指令太多」与「这一段太久」）：$msg",
        )
        assertTrue(e.elapsedMs >= 1L, "异常必须带墙钟读数，实际 elapsedMs=${e.elapsedMs}")
        assertTrue(e.steps > 0L, "异常必须带已执行步数，实际 steps=${e.steps}")
    }

    // ------------------------------------------------------------------
    // 2. 缺省 0 = 不启用（装置/CI 的确定性不能被墙钟污染）
    // ------------------------------------------------------------------

    @Test
    fun `maxRunMillis 为 0 时不启用时间闸`() {
        val pb = longLoop()
        val store = VariableStore()
        val rt = runtime(pb, store, VmConfig(maxStepsPerRun = Long.MAX_VALUE, maxRunMillis = 0L))
        rt.execute()
        assertTrue(true, "0 = 不限：同一段 600 万条指令必须跑完")
    }

    // ------------------------------------------------------------------
    // 3. 步数闸的旧文案/旧行为不变
    // ------------------------------------------------------------------

    @Test
    fun `步数闸仍按指令数口径抛且文案不变`() {
        val pb = longLoop()
        val store = VariableStore()
        val rt = runtime(pb, store, VmConfig(maxStepsPerRun = 100_000L, maxRunMillis = 0L))
        val e = assertFailsWith<StepLimitExceeded> { rt.execute() }
        assertTrue(
            e.message.orEmpty().contains("执行步数超过上限(100001)，疑似死循环"),
            "步数闸文案必须逐字保留（既有装置/失败屏断言依赖它）：${e.message}",
        )
        assertTrue(e.elapsedMs < 0L, "步数闸触发时 elapsedMs 必须是 -1（不是时间口径）")
    }
}
