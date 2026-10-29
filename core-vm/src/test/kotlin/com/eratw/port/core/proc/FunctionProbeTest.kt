package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.Program
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.rand.RngProbe
import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.vars.VarWriteProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DBG4 P3 三个观测口的机制锁（函数 / RNG 出号 / 变量写）：
 * - null 缺省零行为：探针缺席与在场的输出、抽号序列逐值一致（生产路径零变化）；
 * - 命中正确：回调字段 / 序号 / 旧新值口径；
 * - 异常吞口：探针自身抛错不反噬执行流；
 * - 变量写观察口的**入口挂点**（校验前：未定义名的写入也发事件）与 miss 短路
 *   （watches 每写恰一次调用、miss 不回调不建记录）。
 *
 * 全部合成装置，不依赖真语料与 ERATW_* 环境变量（StatementProbeTest 同口径）。
 */
class FunctionProbeTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 函数进出记录器（可注入故障）。 */
    private class RecordingFunctionProbe : FunctionProbe {
        val enters = ArrayList<String?>()
        val exits = ArrayList<Pair<String?, FunctionExitKind>>()
        var failEveryCall = false

        override fun onFunctionEnter(meta: FuncMeta?) {
            if (failEveryCall) throw IllegalStateException("探针自身故障")
            enters.add(meta?.key)
        }

        override fun onFunctionExit(meta: FuncMeta?, outcome: FunctionExitKind) {
            if (failEveryCall) throw IllegalStateException("探针自身故障")
            exits.add(meta?.key to outcome)
        }
    }

    /** RNG 出号记录器。 */
    private class RecordingRngProbe : RngProbe {
        val values = ArrayList<Long>()
        val seqs = ArrayList<Long>()
        var failEveryCall = false

        override fun onDraw(value: Long, seq: Long) {
            if (failEveryCall) throw IllegalStateException("探针自身故障")
            values.add(value)
            seqs.add(seq)
        }
    }

    /** 变量写记录器（名单 = 固定集合 + 原形/大写形同入，VarWriteProbe KDoc 的建议形态）。 */
    private class RecordingVarProbe(names: Collection<String>) : VarWriteProbe {
        val watchSet = HashSet<String>()
        val events = ArrayList<Triple<String, LongArray, Long>>() // int 通道
        var watchesCalls = 0
        var failEveryCall = false

        init {
            for (n in names) {
                watchSet.add(n)
                watchSet.add(n.uppercase())
            }
        }

        override fun watches(name: String): Boolean {
            watchesCalls++
            return name in watchSet
        }

        override fun onWriteInt(name: String, indices: LongArray, value: Long) {
            if (failEveryCall) throw IllegalStateException("探针自身故障")
            events.add(Triple(name, indices, value))
        }
    }

    private fun printStmtOf(code: FunctionCode, text: String): PrintStmt {
        val arg: Argument = ExpressionArgument(StrLiteral(text))
        return PrintStmt(PrintSpec.parse(code)!!, arg, 1, code.name)
    }

    private fun helloProgram(): Program = Assembler.assemble(
        """
        PRINTL stmt#0
        _HALT
        """.trimIndent(),
        statements = listOf(printStmtOf(FunctionCode.PRINTL, "hello")),
    )

    private fun runtime(program: Program, metas: List<FuncMeta> = emptyList()): ProcessRuntime =
        ProcessRuntime(program, VariableStore(), FunctionTable(metas))

    // ------------------------------------------------------------------
    // 函数探针
    // ------------------------------------------------------------------

    @Test
    fun `函数探针缺席与在场 输出一致`() {
        val bare = runtime(helloProgram())
        assertEquals(0L, bare.execute())
        val expected = bare.output.toString()

        val probe = RecordingFunctionProbe()
        val withProbe = runtime(helloProgram())
        withProbe.functionProbe = probe
        assertEquals(0L, withProbe.execute())
        assertEquals(expected, withProbe.output.toString())
        // 无元数据表 → MAIN 帧的 meta 为 null，事件照发（FunctionProbe KDoc）
        assertEquals(listOf<String?>(null), probe.enters)
        assertEquals(listOf<Pair<String?, FunctionExitKind>>(null to FunctionExitKind.EXIT), probe.exits)
    }

    @Test
    fun `函数探针收到非空 meta 且进出配对`() {
        val meta = FuncMeta(index = 0, key = "MAIN")
        val probe = RecordingFunctionProbe()
        val rt = runtime(helloProgram(), listOf(meta))
        rt.functionProbe = probe
        assertEquals(0L, rt.execute())
        assertEquals<List<String?>>(listOf("MAIN"), probe.enters)
        assertEquals<List<Pair<String?, FunctionExitKind>>>(listOf("MAIN" to FunctionExitKind.EXIT), probe.exits)
    }

    @Test
    fun `函数探针自身抛错不反噬执行`() {
        val probe = RecordingFunctionProbe().apply { failEveryCall = true }
        val rt = runtime(helloProgram())
        rt.functionProbe = probe
        assertEquals(0L, rt.execute(), "探针异常必须被吞口吞弃，执行照常完成")
        assertEquals("hello\n", rt.output.toString())
    }

    // ------------------------------------------------------------------
    // RNG 出号探针
    // ------------------------------------------------------------------

    @Test
    fun `出号探针缺席与在场 抽号序列逐值一致`() {
        val baseline = Sfmt19937(20260226L)
        val expected = LongArray(10) { baseline.nextUInt32() }

        val probe = RecordingRngProbe()
        val rng = Sfmt19937(20260226L)
        rng.drawProbe = probe
        val actual = LongArray(10) { rng.nextUInt32() }
        assertTrue(expected.contentEquals(actual), "挂探针不得改变出号序列")
        assertEquals(10, probe.values.size)
        assertTrue(expected.contentEquals(probe.values.toLongArray()), "观察到的出号 = 直接调用返回值")
        assertEquals((1L..10L).toList(), probe.seqs, "seq 从 1 起连续累计")
    }

    @Test
    fun `出号探针挂上前的抽号不推进 seq`() {
        val rng = Sfmt19937(7L)
        repeat(5) { rng.nextUInt32() } // 挂口前 5 抽，不进观察口
        val probe = RecordingRngProbe()
        rng.drawProbe = probe
        repeat(3) { rng.nextUInt32() }
        assertEquals(listOf(1L, 2L, 3L), probe.seqs, "seq = 挂口后的累计（drawProbe KDoc）")
        assertEquals(3, probe.values.size)
    }

    @Test
    fun `出号探针自身抛错不反噬出号`() {
        val expected = LongArray(2)
        run {
            val twin = Sfmt19937(7L) // 无探针对照：出号语义必须与此完全一致
            expected[0] = twin.nextUInt32()
            expected[1] = twin.nextUInt32()
        }
        val rng = Sfmt19937(7L)
        rng.drawProbe = RecordingRngProbe().apply { failEveryCall = true }
        val actual = longArrayOf(rng.nextUInt32(), rng.nextUInt32())
        assertTrue(expected.contentEquals(actual), "探针抛错后出号序列必须与无探针对照逐值一致")
    }

    // ------------------------------------------------------------------
    // 变量写探针
    // ------------------------------------------------------------------

    @Test
    fun `写探针 miss 短路 命中正确`() {
        // 裸 VariableStore 的缺省注册表自带 SystemLayout 系统变量（DAY/MONEY 可写）
        val store = VariableStore()
        val probe = RecordingVarProbe(listOf("DAY"))
        store.writeProbe = probe

        // miss：未监视名 → watches 恰一次调用、零回调零记录、写入照常落
        store.writeInt("MONEY", longArrayOf(0L), 1L)
        assertEquals(1, probe.watchesCalls)
        assertEquals(0, probe.events.size)
        assertEquals(1L, store.readInt("MONEY", longArrayOf(0L)))

        // 命中：事件带 name/indices/value，写入照常落（入口挂点不改写入语义）
        store.writeInt("DAY", longArrayOf(0L), 42L)
        assertEquals(2, probe.watchesCalls, "每次 writeInt 恰一次 watches 调用")
        assertEquals(1, probe.events.size)
        assertEquals("DAY", probe.events[0].first)
        assertEquals(42L, probe.events[0].third)
        assertEquals(42L, store.readInt("DAY", longArrayOf(0L)))
    }

    @Test
    fun `写探针自身抛错不反噬写入`() {
        val store = VariableStore()
        store.writeProbe = RecordingVarProbe(listOf("DAY")).apply { failEveryCall = true }
        store.writeInt("DAY", longArrayOf(0L), 5L) // 探针异常被吞口吞弃，写入照常
        assertEquals(5L, store.readInt("DAY", longArrayOf(0L)))
    }
}
