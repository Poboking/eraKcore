package com.eratw.port.core.rand

import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `core/rand` 与原版 SFMT19937 的逐值对拍审计（w7-randaudit）。
 *
 * 真值来源（只读，未改一个字节）：
 * - 算法：`reference/emuera-cs/MinorShift.Emuera.Runtime.Utils/MTRandom.cs:5-165`
 *   （NextInt64 :35-39 取模、NextUInt64 :46-49、NextDouble :51-54、SetRand :56-67、
 *    GetRand :69-80、NextUInt32 :82-90、init_gen_rand :92-102、
 *    period_certification :104-134、gen_rand_all_19937 :136-164）。
 * - 接线：`VariableEvaluator.cs:28-30`（rand 与 _newRand 两实例）、:117（RANDDATA 别名）、
 *   :217-230（Randomize/InitRanddata/DumpRanddata）、:232-239（GetNextRand 分发）；
 *   `VariableData.cs:838-876`（RAND 伪变量与 CompatiRAND）、
 *   `FunctionMethodCreator.cs:4027-4073`（RAND(min,max)）、`FunctionIdentifier.cs:1734-1799`。
 * - PC 基准：`fixtures/golden/baseline/pc-golden-root.txt:457-465`
 *   （RAND1=335 / RAND2=162 / RANDSEQ=2,4,2,3,1；DUMPED=609256 / AFTERDUMP=672230；
 *    INITSEQ=30,80）+ `fixtures/golden/GOLDEN_05_DATA.ERB:54-77`。
 *
 * 真值生成方式（可复现，产物不入仓）：用本机 .NET 8 SDK 编译**未修改的** MTRandom.cs
 * （csproj 里以 Compile Include 指向参考文件），跑一段只调用其公开 API
 * （NextInt64 / NextUInt64 / NextDouble / GetRand / SetRand）的临时控制台程序，
 * 抄回下列绝对值与 FNV-1a 64 折叠值。折叠函数在测试内重写：
 * 状态按 625 个 UInt64（各 8 字节小端）折叠、UInt32 流按 4 字节小端折叠。
 * 这样 624 字的整块状态可被一条断言钉住，而不必抄 624 个数。
 *
 * 已对拍面：初始化（含 period_certification 两支）、前 N 输出、NextDouble、
 * 625 元素状态往返、外部负值归一化、idx 边界（623 跨块 / 624 复位 / 负数越界）、
 * 64 位无符号取模与上界范围（含 Long.MAX_VALUE）、CompatiRAND 通道、错误路径。
 *
 * 顺带钉住一处文档歧义：`specs/rng.md` §4.2 括注「低 32 位先取」与 MTRandom.cs:48 的
 * C# 求值顺序（左操作数先求值 ⇒ 高位字先取）不符，本文件第 1 例按源码顺序断言；
 * 规约未改，留给 w7-specgap 收口。
 *
 * TODO(verify)：JSON 配置 UseNewRandom=true 时的 System.Random（.NET 8 xoshiro256**）通道未对拍。
 *   卡点：①该通道种子取自内核熵、.NET 8 的 Random 未暴露种子注入点，天然不可复现；
 *   ②此模式下原版 RANDOMIZE/INITRAND/DUMPRAND 只告警不动作（FunctionIdentifier.cs:1745/1767/1789）；
 *   ③本仓库也没有该通道的实现可做逐值对拍（VariableEvaluator.cs:232-239 仅是分发）。
 *   本游戏 setting.json 该开关为 false，故不影响默认路径的逐值一致。
 */
class RngParityAuditTest {

    /** FNV-1a 64 常量（与临时 C# 对拍程序完全一致）。 */
    private val fnvOffset = 1469598103934665603uL
    private val fnvPrime = 1099511628211uL

    /** 初始化真值行：seed、播种态 sfmt[0]、fold(625)、首个 NextUInt64、首个 next(1000)。 */
    private data class Case(
        val seed: Long,
        val firstWord: Long,
        val stateHash: ULong,
        val firstU64: ULong,
        val first1000: Long,
    )

    /** 状态（625 个 UInt64，8 字节小端）折叠；[count] 可取 624 以排除 idx 只看状态字。 */
    private fun fnvState(words: LongArray, count: Int = words.size): ULong {
        var h = fnvOffset
        for (i in 0 until count) {
            val w = words[i]
            for (k in 0 until 8) {
                h = h xor ((w ushr (k * 8)) and 0xFFL).toULong()
                h *= fnvPrime
            }
        }
        return h
    }

    /** UInt32 流（每个 4 字节小端）折叠。 */
    private fun fnvU32(values: List<Long>): ULong {
        var h = fnvOffset
        for (v in values) {
            for (k in 0 until 4) {
                h = h xor ((v ushr (k * 8)) and 0xFFL).toULong()
                h *= fnvPrime
            }
        }
        return h
    }

    /** 造 625 元素快照：前 624 字按 [f] 生成、第 625 项为 idx（SetRand 入参形态）。 */
    private fun craft(idx: Long, f: (Int) -> Long): LongArray =
        LongArray(Sfmt19937.RANDDATA_LEN) { if (it < Sfmt19937.STATE_SIZE) f(it) else idx }

    /**
     * 对拍程序里的模式状态：`sfmt[i] = (uint)(i * 2654435761u)`，即 int 转 uint 的乘法回绕。
     * 用来覆盖任意位型，而不只靠种子推导出的位型。
     */
    private fun pattern(i: Int): Long = (i.toLong() * 2654435761L) and 0xFFFFFFFFL

    // ---------------- 1. 前 N 输出 ----------------

    @Test
    fun `seed 20261017 前 10 个 NextUInt64 与 C# 真值逐值一致`() {
        // MTRandom.cs:46-49：((ulong)NextUInt32() << 32) + NextUInt32()，高位字先取。
        val r = Sfmt19937(20261017L)
        val expected = listOf(
            7132263206313008335uL,
            15154389946278293462uL,
            6494534497379374682uL,
            837808569149044154uL,
            16738646734484343302uL,
            15017679737721975863uL,
            18119395321465180941uL,
            15869244811068538170uL,
            4764103258610205993uL,
            16045936268291059093uL,
        )
        for ((i, e) in expected.withIndex()) {
            assertEquals(e, r.nextUInt64().toULong(), "NextUInt64[$i]")
        }
        // 已取 20 个 UInt32 ⇒ idx = 20（MTRandom.cs:89）
        assertEquals(20L, r.state()[Sfmt19937.STATE_SIZE])
    }

    @Test
    fun `next 1000 与 nextDouble 前若干与 C# 真值一致且与 PC 基准同源`() {
        // PC 基准 pc-golden-root.txt:458 RAND1=335 即 seq[0]；
        // RAND(1000) → NextInt64(1000)（specs/rng.md §3；FunctionMethodCreator.cs:4071）。
        val r = Sfmt19937(20261017L)
        assertEquals(
            listOf(335L, 462L, 682L, 154L, 302L, 863L, 941L, 170L, 993L, 93L),
            (1..10).map { r.next(1000L) },
        )

        // MTRandom.cs:51-54：NextUInt32() * 2.3283064365386963E-10，原样复刻。
        val d = Sfmt19937(20261017L)
        val doubles = listOf(
            0.3866407631430775,
            0.03466968587599695,
            0.8215211250353605,
            0.3737058541737497,
            0.3520694205071777,
        )
        for ((i, e) in doubles.withIndex()) {
            assertEquals(e, d.nextDouble(), 0.0, "NextDouble[$i]")
        }

        // 7 次 next(97) 后的整块状态（idx=14；每次消耗 2 个 UInt32）
        val s = Sfmt19937(20261017L)
        repeat(7) { s.next(97L) }
        assertEquals(14L, s.state()[Sfmt19937.STATE_SIZE])
        assertEquals(17949547211779054254uL, fnvState(s.state()))
    }

    @Test
    fun `跨两轮 gen_rand_all 的 2600 个 UInt32 流哈希与 C# 真值一致`() {
        // seed 20261017：2600 个 NextUInt32（= 1300 次 NextUInt64 的高/低字序），
        // 覆盖 idx 0..623 两轮半，验证 gen_rand_all 的滚动指针 num2/num3/num4（MTRandom.cs:141-164）。
        val r = Sfmt19937(20261017L)
        val stream = (1..2600).map { r.nextUInt32() }
        assertEquals(13489266022864429429uL, fnvU32(stream))
        assertEquals(104L, r.state()[Sfmt19937.STATE_SIZE]) // 2600 - 4*624
        assertEquals(1180567283515673331uL, fnvState(r.state()))

        // seed 0：1400 个 UInt32（顺带覆盖 sfmt[0]=0 且不翻转的奇偶校验分支）
        val z = Sfmt19937(0L)
        val zs = (1..1400).map { z.nextUInt32() }
        assertEquals(7472539657767240951uL, fnvU32(zs))
        assertEquals(152L, z.state()[Sfmt19937.STATE_SIZE])
        assertEquals(4273091849375266117uL, fnvState(z.state()))
    }

    // ---------------- 2. 初始化与 period_certification ----------------

    @Test
    fun `init_gen_rand 与 period_certification 对四种种子与 C# 真值一致`() {
        val cases = listOf(
            // seed 20261017 命中翻转分支：sfmt[0] = seed ^ 1（MTRandom.cs:121-129，PARITY1=1）
            Case(20261017L, 20261016L, 3880223536984403606uL, 7132263206313008335uL, 335L),
            // seed 0：奇偶位已满足 ⇒ inner=1 直接返回，不翻转（MTRandom.cs:117-120）
            Case(0L, 0L, 5121480242947410476uL, 3318214320664290314uL, 314L),
            // seed -1 ⇒ (uint)-1 全 1，翻转后 0xFFFFFFFE
            Case(-1L, 4294967294L, 8356373705910561795uL, 5300838679282289724uL, 724L),
            Case(1234567890123L, 1912276170L, 7339340735352414785uL, 4313331819884065174uL, 174L),
        )
        for (c in cases) {
            val st = Sfmt19937(c.seed).state()
            assertEquals(624L, st[Sfmt19937.STATE_SIZE], "seed=${c.seed} 播种后 idx")
            assertEquals(c.firstWord, st[0], "seed=${c.seed} sfmt[0]")
            assertEquals(c.stateHash, fnvState(st), "seed=${c.seed} 播种态折叠")
            assertEquals(c.firstU64, Sfmt19937(c.seed).nextUInt64().toULong(), "seed=${c.seed} 首个 u64")
            assertEquals(c.first1000, Sfmt19937(c.seed).next(1000L), "seed=${c.seed} 首个 next(1000)")
        }
        // 抽查播种态各段，防止折叠哈希把「局部互换」漏掉（seed 20261017）
        val a = Sfmt19937(20261017L).state()
        assertEquals(2497832286L, a[1])
        assertEquals(2350911507L, a[100])
        assertEquals(2106162547L, a[623])
    }

    @Test
    fun `种子按 UInt32 截断且 reseed 等价于新建实例`() {
        // MTRandom.cs:32 (uint)seed；specs/rng.md §4.3
        val wide = Sfmt19937(20261017L + 0x1_0000_0000L)
        assertEquals(fnvState(Sfmt19937(20261017L).state()), fnvState(wide.state()))
        assertEquals(335L, wide.next(1000L))

        // RANDOMIZE 语义：VariableEvaluator.cs:217-220 是 new MTRandom(seed) ⇒ 与 reseed 等价
        val r = Sfmt19937(20261017L)
        repeat(5) { r.next(1000L) }
        r.reseed(777L)
        assertEquals(fnvState(Sfmt19937(777L).state()), fnvState(r.state()))
        assertEquals(609256L, r.next(1000000L))
    }

    // ---------------- 3. RANDDATA[625] 往返 ----------------

    @Test
    fun `RANDDATA 625 元素往返后流与快照时刻逐值一致（PC 基准 777）`() {
        // pc-golden-root.txt:462-465：DUMPED=609256 / AFTERDUMP=672230 / INITSEQ=30,80。
        val dumper = Sfmt19937(777L)
        assertEquals(609256L, dumper.next(1000000L))
        val snap = dumper.state() // DUMPRAND → GetRand（MTRandom.cs:69-80）
        assertEquals(Sfmt19937.RANDDATA_LEN, snap.size)
        assertEquals(2L, snap[Sfmt19937.STATE_SIZE]) // 首轮 gen_rand_all 后已消耗 2 个 UInt32
        assertEquals(12105299397468054204uL, fnvState(snap))
        assertEquals(672230L, dumper.next(1000000L)) // DUMPRAND 不改状态

        val restored = Sfmt19937(1L) // 任意种子，随即被 SetRand 覆盖（MTRandom.cs:56-67）
        restored.loadState(snap)
        assertEquals(fnvState(snap), fnvState(restored.state()))
        assertEquals(30L, restored.next(100L))
        assertEquals(80L, restored.next(100L))
        // 往返后 50 个原始 UInt32 仍与快照时刻的源实例一致（游标也一起搬走）：
        // 上面 restored 已多走两次 next(100)，故另取一个只装快照的实例来比尾流，
        // 源侧用同种子重放到快照时刻（idx=2）的实例。
        val tailRestored = Sfmt19937(2L)
        tailRestored.loadState(snap)
        val replay = Sfmt19937(777L)
        assertEquals(609256L, replay.next(1000000L))
        assertEquals((1..50).map { replay.nextUInt32() }, (1..50).map { tailRestored.nextUInt32() })
    }

    @Test
    fun `RANDDATA 外部负值按 UInt32 低 32 位归一化（存档写入形态）`() {
        // SetRand 逐项 (uint)array[i]（MTRandom.cs:62-65）：负数按补码截断，回写永远是无符号值。
        // 真值：仅 sfmt[3] = -2，其余 0，idx = 0 ⇒ GetRand 得 [0,0,0,4294967294,0,...,0]。
        val r = Sfmt19937(11L)
        r.loadState(craft(0L) { if (it == 3) -2L else 0L })
        val st = r.state()
        assertEquals(4294967294L, st[3])
        assertEquals(0L, st[0])
        assertEquals(0L, st[100])
        assertEquals(12186642176649557694uL, fnvState(st))
        assertEquals(0uL, r.nextUInt64().toULong())

        // 全 -1 快照：归一化为全 0xFFFFFFFF（round trip 不等于原样回写，属原版行为）
        r.loadState(craft(0L) { -1L })
        assertEquals(4294967295L, r.state()[0])
        assertEquals(8394131878997165091uL, fnvState(r.state()))
    }

    // ---------------- 4. 取模分布 / 无拒绝采样 ----------------

    @Test
    fun `next 为无符号 64 位取模且每次固定消耗两个 UInt32`() {
        // MTRandom.cs:35-39：NextInt64(max) = (long)(NextUInt64() % (ulong)max) — 纯取模，
        // 原版没有拒绝采样；用全 1 状态（NextUInt64 = 2^64-1）可精确钉住每个上界的余数。
        val ones = craft(0L) { -1L }
        val table = listOf(
            1L to 0L,     // 2^64-1 % 1
            2L to 1L,     // 末位 1
            3L to 0L,     // 2^64 ≡ 1 (mod 3)
            7L to 1L,
            10L to 5L,
            1000L to 615L,
        )
        for ((bound, expected) in table) {
            val r = Sfmt19937(5L)
            r.loadState(ones)
            assertEquals(expected, r.next(bound), "next($bound)")
            // 恰好吃掉 2 个 UInt32；若做拒绝采样，部分上界会多消耗甚至重试
            assertEquals(2L, r.state()[Sfmt19937.STATE_SIZE], "next($bound) 后 idx")
            // 未跨过 624 ⇒ 状态字原样（取模不回头改状态）
            assertEquals(13214116195310202307uL, fnvState(r.state(), Sfmt19937.STATE_SIZE), "next($bound) 后状态")
        }
        // 同一位型连取：全 1 是 SFMT 递推的不动点，NextUInt64 恒为 2^64-1，游标每步 +2
        val seq = Sfmt19937(5L)
        seq.loadState(ones)
        assertEquals(
            listOf(18446744073709551615uL, 18446744073709551615uL, 18446744073709551615uL),
            (1..3).map { seq.nextUInt64().toULong() },
        )
        assertEquals(6L, seq.state()[Sfmt19937.STATE_SIZE])

        // 上界超过 2^32 时仍按 64 位取模：32 位实现或带符号实现会给出不同余数
        // （原版按 (ulong)max 取模，MTRandom.cs:38）。
        val wideTable = listOf(
            4294967295L to 0L,                    // 2^64-1 = (2^32-1)(2^32+1)
            4294967296L to 4294967295L,            // 取低 32 位
            4294967297L to 0L,
            5000000000L to 3709551615L,            // 18446744073709551615 % 5000000000
            281474976710656L to 281474976710655L,  // 2^48
            Long.MAX_VALUE to 1L,                  // 有符号实现会给出 -1，这里必须是 1
        )
        for ((bound, expected) in wideTable) {
            val r = Sfmt19937(3L)
            r.loadState(ones)
            assertEquals(expected, r.next(bound), "next($bound)")
            assertEquals(2L, r.state()[Sfmt19937.STATE_SIZE], "next($bound) 后 idx")
        }
    }

    // ---------------- 5. idx 边界 ----------------

    @Test
    fun `idx 623 跨块与 idx 大于等于 624 复位均与 C# 真值一致`() {
        // idx=623：第一个 UInt32 取 sfmt[623]，第二个先触发 gen_rand_all（MTRandom.cs:82-90），
        // 于是一个 NextUInt64 跨块 —— 滚动指针 num/num2/num3/num4 最易错的边界。
        val cross = Sfmt19937(9L)
        cross.loadState(craft(623L) { pattern(it) })
        assertEquals(8933208552273026411uL, fnvState(cross.state()))
        val first4 = listOf(
            648841325652101950uL,
            3057863419203862500uL,
            16909276992269921232uL,
            15492256363327050759uL,
        )
        for ((i, e) in first4.withIndex()) {
            assertEquals(e, cross.nextUInt64().toULong(), "idx=623 NextUInt64[$i]")
        }
        assertEquals(7L, cross.state()[Sfmt19937.STATE_SIZE])
        assertEquals(11928671133662326111uL, fnvState(cross.state()))
        assertEquals(10418846597568543915uL, cross.nextUInt64().toULong(), "idx=623 NextUInt64[4]")
        assertEquals(9L, cross.state()[Sfmt19937.STATE_SIZE])

        // idx >= 624 一律「先 gen_rand_all 再归零」：624 / 625 / 1000 三个快照等价。
        val firsts = listOf(624L, 625L, 1000L).map { idx ->
            val r = Sfmt19937(11L)
            r.loadState(craft(idx) { pattern(it) })
            val v = r.nextUInt64().toULong()
            assertEquals(2L, r.state()[Sfmt19937.STATE_SIZE], "idx=$idx 后游标")
            v
        }
        assertEquals(List(3) { 8869359246524790420uL }, firsts)
    }

    // ---------------- 6. NextDouble 边界 ----------------

    @Test
    fun `nextDouble 两端边界与 C# 真值一致`() {
        val ones = Sfmt19937(11L)
        ones.loadState(craft(0L) { -1L })
        assertEquals(0.9999999997671694, ones.nextDouble(), 0.0) // (2^32-1) * 2^-32

        val zeros = Sfmt19937(11L)
        zeros.loadState(craft(0L) { 0L })
        assertEquals(0.0, zeros.nextDouble(), 0.0)

        val r = Sfmt19937(20261017L)
        val stream = (1..300).map { r.nextDouble() }
        assertTrue(stream.all { it >= 0.0 && it < 1.0 }, "NextDouble 必须落在 [0,1) 半开区间")
        assertTrue(stream.distinct().size > 290, "NextDouble 不应大量重复")
    }

    // ---------------- 7. 错误路径 ----------------

    @Test
    fun `非法上界 状态长度与负 idx 与原版同点位失败`() {
        // C# 为 ArgumentOutOfRangeException（MTRandom.cs:37、:58-61、:71-74）；
        // 本层按 CONTRACT-M3-A §1 映射成 IllegalArgumentException（已登记的类型面差异）。
        val r = Sfmt19937(20261017L)
        r.loadState(craft(0L) { -1L })
        for (bad in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException>("bound=$bad") { r.next(bad) }
        }
        assertEquals(0L, r.state()[Sfmt19937.STATE_SIZE], "非法上界不得推进游标")
        for (bad in listOf(0, 624, 626)) {
            assertFailsWith<IllegalArgumentException>("len=$bad") { r.loadState(LongArray(bad)) }
        }

        // 原版 SetRand 不校验 idx（MTRandom.cs:66），NextUInt32 直接 sfmt[idx++]（:89）：
        // idx=-1 ⇒ C# IndexOutOfRangeException；Kotlin 侧同点位抛 ArrayIndexOutOfBoundsException。
        // TODO(verify)：ERB 层可见文案（Lang.Error 越界提示）尚未与本路径对拍；
        //   卡点：需经 INITRAND 语句 + 伪造 RANDDATA 的 ERB 用例，属 sem 层闸位，不在本路文件面。
        val neg = Sfmt19937(11L)
        neg.loadState(craft(-1L) { pattern(it) })
        assertEquals(-1L, neg.state()[Sfmt19937.STATE_SIZE])
        assertFailsWith<IndexOutOfBoundsException> { neg.nextUInt64() }
    }

    // ---------------- 8. ERB 消费通道（CompatiRAND） ----------------

    @Test
    fun `CompatiRAND 通道 GetNextRand32768 再取模与 C# 真值一致`() {
        // VariableData.cs:863-875：n==0 直接返回 0（不耗流）；n<0 取绝对值；
        // 否则 GetNextRand(32768) % n。RANDDATA 全局数组接线见 VariableEvaluator.cs:117。
        val rng = Sfmt19937(20261017L)
        val store = VariableStore(config = VarConfig(compatiRand = true), randProvider = rng)
        fun rand(n: Long): Long = store.readInt("RAND", longArrayOf(n))
        val ns = listOf(3L, 3L, 3L, 7L, 1000L, -5L, 0L, 3L)
        val expected = listOf(1L, 0L, 2L, 6L, 566L, 1L, 0L, 2L)
        for ((i, n) in ns.withIndex()) assertEquals(expected[i], rand(n), "CompatiRAND($n)")
        assertEquals(14L, rng.state()[Sfmt19937.STATE_SIZE]) // n=0 那次没有消耗（仍 7×2）

        // n = Long.MIN_VALUE：-n 在 long 上仍是 Long.MIN_VALUE（原版未检查溢出），
        // 32768 以内的余数原样返回；真值 7375（.NET 8 实跑）。
        val edge = Sfmt19937(20261017L)
        val edgeStore = VariableStore(config = VarConfig(compatiRand = true), randProvider = edge)
        assertEquals(7375L, edgeStore.readInt("RAND", longArrayOf(Long.MIN_VALUE)))
        assertEquals(2L, edge.state()[Sfmt19937.STATE_SIZE])

        // 非 Compati 通道：RAND(n) 直接 next(n)（VariableData.cs:845-853）
        val plain = VariableStore(randProvider = Sfmt19937(20261017L))
        assertEquals(335L, plain.readInt("RAND", longArrayOf(1000L)))
        assertEquals(162L, plain.readInt("RAND", longArrayOf(100L)) + 100L) // RAND(100,200)
    }
}
