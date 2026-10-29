package com.eratw.port.core.rand

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * SFMT19937 对拍判据（CONTRACT-M3-A §1 硬验证向量）。
 *
 * 基准来源（只读，不许改）：
 * - `fixtures/golden/baseline/pc-golden-root.txt:457-465`
 *   （RNG-SEEDED: RAND1=335 / RAND2=162 / RANDSEQ=2,4,2,3,1；
 *     RNG-DUMPRESTORE: DUMPED=609256 / AFTERDUMP=672230；
 *     RNG-INITRAND: INITSEQ=30,80）
 * - `fixtures/golden/GOLDEN_05_DATA.ERB:54-77`（@GOLDEN_RNG：RANDOMIZE 20261017 / 777）
 * - 算法真相：`reference/emuera-cs/MinorShift.Emuera.Runtime.Utils/MTRandom.cs:5-165`
 *
 * ERB→内核映射：`RAND(1000)` → `next(1000)`（specs/rng.md §3，MTRandom.cs:35-39）；
 * `RAND(100,200)` → `GetNextRand(100)+100` ⇒ 内核 `next(100)=62`
 * （FunctionMethodCreator.cs RandMethod，specs/rng.md §3 表行 4）。
 * `DUMPRAND` 快照 = 本层 `state()`（MTRandom.cs:69-80），`INITRAND` 复原 = `loadState()`
 * （MTRandom.cs:56-67）；语句接线由 A8 完成，本测试在 state 层复现同一向量。
 */
class Sfmt19937Test {

    // ---- PC 基准向量（硬断言，禁止放宽） ----

    @Test
    fun `pc向量 seed 20261017 连取与基准逐值一致`() {
        // pc-golden-root.txt:458 RAND1=335
        val r = Sfmt19937(20261017L)
        assertEquals(335L, r.next(1000L))
        // pc-golden-root.txt:459 RAND2=162 = RAND(100,200) = next(100)+100
        assertEquals(62L, r.next(100L))
        // pc-golden-root.txt:460 RANDSEQ=2,4,2,3,1（GOLDEN_05_DATA.ERB:62 同行连取 5 个 RAND(10)）
        assertEquals(listOf(2L, 4L, 2L, 3L, 1L), (1..5).map { r.next(10L) })
    }

    @Test
    fun `pc向量 seed 777 DUMPRAND 前后两取值一致`() {
        // pc-golden-root.txt:462-463 DUMPED=609256 / AFTERDUMP=672230
        // （DUMPRAND 只导出 state、不改状态，GOLDEN_05_DATA.ERB:69-74）
        val r = Sfmt19937(777L)
        assertEquals(609256L, r.next(1000000L))
        r.state() // DUMPRAND 等价：导出快照不影响后续流
        assertEquals(672230L, r.next(1000000L))
    }

    @Test
    fun `pc向量 INITRAND 语义在 state 层复原后 next 100 得 30 80`() {
        // pc-golden-root.txt:465 INITSEQ=30,80（GOLDEN_05_DATA.ERB:71-77：
        // DUMPRAND 存的是 seed 777 首取值后的 625 元素快照 ⇒ idx=2，不再触发 gen_rand_all）
        val dumper = Sfmt19937(777L)
        assertEquals(609256L, dumper.next(1000000L))
        val snapshot = dumper.state()
        assertEquals(625, snapshot.size)
        assertEquals(2L, snapshot[624]) // 首轮 gen_rand_all 后已消耗 2 个 UInt32（MTRandom.cs:48,89）
        val restored = Sfmt19937(1L) // 任意种子，随即被 loadState 覆盖
        restored.loadState(snapshot)
        assertEquals(30L, restored.next(100L))
        assertEquals(80L, restored.next(100L))
    }

    // ---- 参数校验 ----

    @Test
    fun `bound 非正数抛 IllegalArgumentException`() {
        // MTRandom.cs:37 ThrowIfNegativeOrZero → 契约要求 IllegalArgumentException
        val r = Sfmt19937(20261017L)
        assertFailsWith<IllegalArgumentException> { r.next(0L) }
        assertFailsWith<IllegalArgumentException> { r.next(-1L) }
    }

    @Test
    fun `loadState 长度非 625 抛 IllegalArgumentException`() {
        // MTRandom.cs:58-61（array.Length != 625 → 抛出）
        val r = Sfmt19937(777L)
        for (bad in listOf(0, 624, 626)) {
            assertFailsWith<IllegalArgumentException>("len=$bad") { r.loadState(LongArray(bad)) }
        }
    }

    // ---- 状态往返 ----

    @Test
    fun `state loadState 往返后续流与快照时刻完全一致`() {
        val a = Sfmt19937(20261017L)
        repeat(3) { a.next(97L) } // 消耗 6 个 UInt32（每次 RAND 消耗 2 个，MTRandom.cs:46-49）
        val snap = a.state()
        val tail = (1..20).map { a.nextUInt32() } // a 继续走的流
        val b = Sfmt19937(12345L)
        b.loadState(snap)
        val tailB = (1..20).map { b.nextUInt32() }
        assertEquals(tail, tailB)
        assertEquals(a.state().toList(), b.state().toList()) // 游标与状态字全等
        assertEquals(6L, snap[624]) // 3×2 消耗后 idx=6（每次 next() 消耗 2 个 UInt32，MTRandom.cs:46-49）
    }

    // ---- period_certification / 首次取值触发 gen_rand_all 的边界 ----

    @Test
    fun `reseed 后 idx 为 624 且首次取值先触发 gen_rand_all`() {
        // MTRandom.cs:101（init_gen_rand 末尾 idx=624）+ MTRandom.cs:84-88（首取触发 gen_rand_all）
        // 亦验证 period_certification（MTRandom.cs:104-134）对两个基准种子均命中
        // `sfmt[0] ^= 1` 分支（inner 折叠后为偶 ⇒ MTRandom.cs:126-129，bit=1、PARITY1=1 第一次内层循环即 return）。
        val seed = 20261017L
        val r = Sfmt19937(seed)
        assertEquals(624L, r.state()[624]) // 播种后未生成任何输出
        assertEquals(seed xor 1L, r.state()[0]) // 校验位翻转后、gen_rand_all 前的 sfmt[0]
        val first = r.nextUInt32()
        assertEquals(1L, r.state()[624]) // idx 归零后自增 ⇒ 已完整执行一轮 gen_rand_all
        assertEquals(first, r.state()[0]) // 返回值即递推重写后的 sfmt[0]
        assertNotEquals(seed xor 1L, first)
        // reseed 回到同一边界
        r.reseed(777L)
        assertEquals(624L, r.state()[624])
        assertEquals(776L, r.state()[0]) // 777 同样命中 ^= 1 分支
        assertEquals(609256L, r.next(1000000L)) // 与全新实例等价
    }

    @Test
    fun `loadState 携带 idx 624 的快照同样先触发 gen_rand_all`() {
        // 播种态快照（idx=624）装入新实例 ⇒ 流与源实例逐值一致（gen_rand_all 惰性且确定性）
        val src = Sfmt19937(20261017L)
        val snapAtInit = src.state()
        val dst = Sfmt19937(999L)
        dst.loadState(snapAtInit)
        assertEquals((1..1300).map { src.nextUInt32() }, (1..1300).map { dst.nextUInt32() })
        assertEquals(624L, snapAtInit[624])
    }

    // ---- UInt64 / Double 通道语义（MTRandom.cs:46-54） ----

    @Test
    fun `nextUInt64 高 32 位先取 连续消耗两个 UInt32`() {
        // MTRandom.cs:48：((ulong)NextUInt32() << 32) + NextUInt32()，C# 左操作数先求值 ⇒ 高位字先取
        val seed = 20261017L
        val first = Sfmt19937(seed).nextUInt32()                       // 第 1 个 UInt32
        val second = Sfmt19937(seed).run { nextUInt32(); nextUInt32() } // 第 2 个 UInt32
        val r = Sfmt19937(seed)
        assertEquals((first shl 32) + second, r.nextUInt64())
        assertEquals(2L, r.state()[624]) // 一次 NextUInt64 消耗 2 个游标
    }

    @Test
    fun `nextDouble 等于首个 UInt32 乘 2 的负 32 次方`() {
        // MTRandom.cs:53
        val seed = 777L
        val u = Sfmt19937(seed).nextUInt32()
        assertEquals(u.toDouble() * 2.3283064365386963E-10, Sfmt19937(seed).nextDouble())
    }

    @Test
    fun `种子截断为 UInt32 低 32 位`() {
        // MTRandom.cs:32（init_gen_rand((uint)seed)，specs/rng.md §4.3）
        val wide = Sfmt19937(20261017L + 0x1_0000_0000L)
        val narrow = Sfmt19937(20261017L)
        assertEquals(narrow.state().toList(), wide.state().toList())
        assertEquals(335L, wide.next(1000L))
    }
}
