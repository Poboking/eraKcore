package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.NoopConsoleSink
import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RNG 语句测试（CONTRACT-M3-A §2.6）。
 *
 * 判据：`fixtures/golden/baseline/pc-golden-root.txt:457-465`
 * （RNG-SEEDED: RAND1=335 / RAND2=162 / RANDSEQ=2,4,2,3,1；
 *   RNG-DUMPRESTORE: DUMPED=609256 / AFTERDUMP=672230；
 *   RNG-INITRAND: INITSEQ=30,80）
 * + `fixtures/golden/GOLDEN_05_DATA.ERB:54-77` 的语句序列（RANDOMIZE 20261017 / 777）。
 *
 * 语句语义：FunctionIdentifier.cs:1734-1799（RANDOMIZE/INITRAND/DUMPRAND）+
 * VariableEvaluator.cs:217-230（Randomize/InitRanddata/DumpRanddata 与全局 RANDDATA[625] 互拷）。
 *
 * 取值通道说明：ERB 里的 `RAND(n)` 是**函数**（FunctionMethodCreator.cs:9089 RandMethod，
 * 1/2 参形态，BuiltinsTest 已覆盖）；本测试经伪变量通道 `RAND:n`
 * （VariableStore.kt:879,934-946 → PseudoKind.RAND）驱动同一 provider——
 * 两条通道都落到 `RandProvider.next(bound)`，向量值等价（E2E 的 GOLDEN_RNG 节覆盖函数形态）。
 */
class RngStatementsTest {

    /** 最小执行环境：真实 store + Noop 宿主（RNG 语句不碰 console）。 */
    private class RngEnv(val useNewRandom: Boolean = false) {
        val store = VariableStore()
        val ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = NoopConsoleSink,
            config = SemConfig(useNewRandom = useNewRandom),
        )
        val executor = StatementExecutor()

        fun run(code: FunctionCode, seed: Long? = null) {
            val stmt = if (seed != null) instr(code, intArg(seed)) else instr(code)
            executor.execute(stmt, ctx)
        }

        fun rand(bound: Long): Long = store.readInt("RAND", longArrayOf(bound))
    }

    @Test
    fun `RANDOMIZE 777 DUMPRAND INITRAND 全序列复现 PC 基准`() {
        val env = RngEnv()
        // 进程级 provider 先用任意种子——会被 RANDOMIZE 覆盖（对拍只认 RANDOMIZE 后的流）
        env.store.randProvider = Sfmt19937(1L)

        // RANDOMIZE 777 → RI=RAND(1000000)=609256（pc-golden-root.txt:462）
        env.run(FunctionCode.RANDOMIZE, 777L)
        assertEquals(609256L, env.rand(1000000L))

        // DUMPRAND：状态 → RANDDATA（GetRand，MTRandom.cs:69-80）
        env.run(FunctionCode.DUMPRAND)
        // 快照点 = 首取值后：idx=2（MTRandom.cs:48,89），状态字被 DUMPRAND 原样导出
        assertEquals(2L, env.store.readInt("RANDDATA", longArrayOf(624L)))

        // AFTERDUMP=672230（:463）——DUMPRAND 不改状态
        assertEquals(672230L, env.rand(1000000L))

        // INITRAND：RANDDATA → 状态（SetRand，MTRandom.cs:56-67），回放到 DUMPRAND 时刻
        env.run(FunctionCode.INITRAND)
        // INITSEQ=30,80（:465）
        assertEquals(30L, env.rand(100L))
        assertEquals(80L, env.rand(100L))
    }

    @Test
    fun `RANDOMIZE 20261017 复现 RNG-SEEDED 三行`() {
        val env = RngEnv()
        env.store.randProvider = Sfmt19937(0L)
        env.run(FunctionCode.RANDOMIZE, 20261017L)
        assertEquals(335L, env.rand(1000L)) // RAND1（:458）
        // RAND2=162（:459）= RAND(100,200) = next(100)+100（函数形态；此处经伪变量通道取同流）
        assertEquals(62L + 100L, env.rand(100L) + 100L)
        // RANDSEQ=2,4,2,3,1（:460）
        assertEquals(listOf(2L, 4L, 2L, 3L, 1L), (1..5).map { env.rand(10L) })
    }

    @Test
    fun `RANDOMIZE 省略参数按 0 处理`() {
        // ArgumentParser.cs:1194-1196（nullable INT_EXPRESSION 省略补 SingleLongTerm(0)）
        val env = RngEnv()
        env.store.randProvider = Sfmt19937(999L)
        env.run(FunctionCode.RANDOMIZE) // 无参
        assertEquals(Sfmt19937(0L).next(1000L), env.rand(1000L))
    }

    @Test
    fun `UseNewRandom 时 RANDOMIZE 不重播种`() {
        // FunctionIdentifier.cs:1745-1749（useNewRandom=true 仅告警不动作；
        // 本游戏 setting.json UseNewRandom=false，走经典通道）
        val env = RngEnv(useNewRandom = true)
        env.store.randProvider = Sfmt19937(42L)
        env.run(FunctionCode.RANDOMIZE, 777L)
        // 未重播种：首个取值与 Sfmt19937(42) 的首取值一致（而非 777 的 609256）
        assertEquals(Sfmt19937(42L).next(1000000L), env.rand(1000000L))
    }

    @Test
    fun `未注入 RandProvider 时 RNG 语句显式抛错`() {
        val env = RngEnv()
        env.store.randProvider = null
        assertFailsWith<CodeEE> { env.run(FunctionCode.RANDOMIZE, 1L) }
        assertFailsWith<CodeEE> { env.run(FunctionCode.DUMPRAND) }
        assertFailsWith<CodeEE> { env.run(FunctionCode.INITRAND) }
    }

    @Test
    fun `DUMPRAND 导出 625 元素到 RANDDATA 全局数组`() {
        val env = RngEnv()
        env.store.randProvider = Sfmt19937(777L)
        env.rand(1000000L) // 先取一个值（idx → 2）
        env.run(FunctionCode.DUMPRAND)
        val provider = Sfmt19937(777L)
        provider.next(1000000L)
        val expected = provider.state()
        for (i in 0 until 625) {
            assertEquals(expected[i], env.store.readInt("RANDDATA", longArrayOf(i.toLong())), "RANDDATA[$i]")
        }
        assertTrue(env.store.readInt("RANDDATA", longArrayOf(624L)) == 2L)
    }
}
