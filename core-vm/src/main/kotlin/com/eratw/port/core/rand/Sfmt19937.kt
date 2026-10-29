package com.eratw.port.core.rand

import com.eratw.port.core.vars.RandProvider

/**
 * SFMT19937（原版 MTRandom）。全部 UInt32 语义用 Long 的低 32 位承载。
 *
 * 唯一真相源：`reference/emuera-cs/MinorShift.Emuera.Runtime.Utils/MTRandom.cs`
 * （下文所有 `MTRandom.cs:行号` 均指该反编译文件；`specs/rng.md` §1/§4 为导读）。
 * 注意本引擎的 gen_rand_all_19937（MTRandom.cs:141-164）参数组合为原版内联写法
 * （非 SFMT 通用参考实现），移位/掩码/滚动指针逐行对照，不得凭通用 SFMT 知识改动。
 *
 * 状态：`uint[624] sfmt` + `int idx`（MTRandom.cs:21-23）；全部 UInt32 值以 Long
 * 低 32 位（0..4294967295）存储，写回一律 `and 0xFFFFFFFFL`；右移一律逻辑右移
 * （值恒非负且 ≤32bit，`shr`/`ushr` 等价，此处显式用 `ushr` 表意）。
 *
 * @param seed 对应 `MTRandom(long seed)` → `init_gen_rand((uint)seed)`（MTRandom.cs:30-33）：
 *             种子截断为低 32 位，负 seed 按补码截断（specs/rng.md §4.3）。
 */
class Sfmt19937(seed: Long) : RandProvider {

    /**
     * 出号观察口（DBG4 P3 log 事件流）：非 null 时 [nextUInt32] 每出一个 UInt32 同步回调
     * 一次（出号值 + 累计序号）。**null = 生产缺省 = 零行为**——守卫是单次 volatile 读 +
     * 分支，零分配、零虚调用、序号不推进（StatementProbe 同款判据）。@Volatile：
     * 宿主线程赋值、VM 线程消费（ADR-003 单线程执行）。
     *
     * [RngProbe.onDraw] 的 `seq` 是**观察口挂上之后**的出号累计（挂上前的抽号不补计）：
     * vmdbg 的用法是会话装配后、执行前挂口，seq 因此与本次执行内抽号一一对应。
     * 回调实现不得阻塞、不得抛——探针异常由 runtime 吞弃，不影响出号语义。
     */
    @Volatile
    var drawProbe: RngProbe? = null

    /** 观察口挂上后的出号累计序号（只在探针非 null 时推进，见 [drawProbe] KDoc）。 */
    private var drawSeq = 0L

    /** sfmt[0..623]：状态字（Long 低 32 位承载 UInt32）。MTRandom.cs:21 */
    private val sfmt = LongArray(STATE_SIZE)

    /** idx：游标。MTRandom.cs:23 */
    private var idx = STATE_SIZE

    init {
        reseed(seed) // MTRandom.cs:30-33
    }

    /**
     * `NextInt64(max) = NextUInt64() % max`（MTRandom.cs:35-39）。
     * max 为 0 或负数时原版 `ArgumentOutOfRangeException.ThrowIfNegativeOrZero`（MTRandom.cs:37），
     * 契约（CONTRACT-M3-A §1）要求本层抛 [IllegalArgumentException]。
     */
    override fun next(bound: Long): Long {
        require(bound > 0L) { "随机数上界必须为正数（实际 $bound）" } // MTRandom.cs:37
        // (long)(NextUInt64() % (ulong)max)：无符号取模，结果必落在 [0, bound)（MTRandom.cs:38）
        return (nextUInt64().toULong() % bound.toULong()).toLong()
    }

    /**
     * `NextUInt64() = ((ulong)NextUInt32() << 32) + NextUInt32()`（MTRandom.cs:46-49）。
     * C# 左操作数先求值 ⇒ **第一个 UInt32 进高位**，第二个进低位（specs/rng.md §4.2
     * 括注"低 32 位先取"与源码求值顺序不符，以 MTRandom.cs:48 为准；对拍向量已验证本序）。
     * 返回值是完整 64bit，可能为负 Long（有符号视角），按位语义使用（`toULong()` 再取模）。
     */
    fun nextUInt64(): Long = (nextUInt32() shl 32) + nextUInt32() // MTRandom.cs:48

    /**
     * `NextDouble() = (double)NextUInt32() * 2.3283064365386963E-10`（MTRandom.cs:51-54），
     * 即 u32 / 2^32，∈ [0, 1)。与 System.Random 不同，需原样复刻（specs/rng.md §4.3）。
     */
    fun nextDouble(): Double = nextUInt32().toDouble() * 2.3283064365386963E-10 // MTRandom.cs:53

    /**
     * 取一个 UInt32（Long 低 32 位）：`idx >= 624` 时先 `gen_rand_all()` 并置 `idx = 0`，
     * 返回 `sfmt[idx++]`（MTRandom.cs:82-90）。
     */
    fun nextUInt32(): Long {
        if (idx >= STATE_SIZE) { // MTRandom.cs:84
            genRandAll()         // MTRandom.cs:86（gen_rand_all → gen_rand_all_19937，:136-139）
            idx = 0              // MTRandom.cs:87
        }
        val value = sfmt[idx++]  // MTRandom.cs:89
        // DBG4 P3 观察口：单读单判，null 路径零分配零调用（守卫判据见 drawProbe KDoc）
        val probe = drawProbe ?: return value
        runCatching { probe.onDraw(value, ++drawSeq) }
        return value
    }

    /**
     * 重播种 = `init_gen_rand((uint)seed)`：`sfmt[0]=seed`；
     * `sfmt[i] = 1812433253 * (sfmt[i-1] ^ (sfmt[i-1] >> 30)) + i`（UInt32 模 2^32，MTRandom.cs:92-102）；
     * 随后 `period_certification()`（MTRandom.cs:100）并置 **`idx = 624`**（MTRandom.cs:101），
     * 即首次取值必触发一次 gen_rand_all（specs/rng.md §4.1-3）。
     */
    override fun reseed(seed: Long) {
        sfmt[0] = seed and UINT32_MASK // MTRandom.cs:32 (uint)seed 截断、:95 sfmt[0] = seed
        for (i in 1 until STATE_SIZE) { // MTRandom.cs:96-99
            val prev = sfmt[i - 1]
            // 1812433253 < 2^31、prev < 2^32 ⇒ 乘积 < 2^63，Long 不溢出，尾掩码即 UInt32 回绕
            sfmt[i] = (MULT * (prev xor (prev ushr 30)) + i.toLong()) and UINT32_MASK // MTRandom.cs:98
        }
        periodCertification() // MTRandom.cs:100
        idx = STATE_SIZE      // MTRandom.cs:101（等价原版 init_gen_rand 的 idx=624，specs/rng.md §4.1）
    }

    /**
     * 导出 625 元素：[0..623]=状态字（无符号 32bit 存 Long），[624]=idx。对应 `GetRand(long[625])`
     * （MTRandom.cs:69-80）与全局 RANDDATA（specs/rng.md §1，DUMPRAND/INITRAND 由 A8 接线）。
     */
    override fun state(): LongArray { // MTRandom.cs:69-80
        val out = LongArray(RANDDATA_LEN)
        for (i in 0 until STATE_SIZE) out[i] = sfmt[i] // MTRandom.cs:75-78
        out[STATE_SIZE] = idx.toLong()                 // MTRandom.cs:79
        return out
    }

    /**
     * 装载 625 元素状态。对应 `SetRand(long[])`（MTRandom.cs:56-67）：
     * 长度非 625 原版抛 ArgumentOutOfRangeException（MTRandom.cs:58-61），契约要求 [IllegalArgumentException]；
     * 状态字取低 32 位（`(uint)array[i]`，MTRandom.cs:64），`idx = (int)array[624]`（MTRandom.cs:66）。
     */
    override fun loadState(data: LongArray) {
        require(data.size == RANDDATA_LEN) { "loadState 需要 $RANDDATA_LEN 个元素（实际 ${data.size}）" } // MTRandom.cs:58
        for (i in 0 until STATE_SIZE) sfmt[i] = data[i] and UINT32_MASK // MTRandom.cs:62-65
        idx = data[STATE_SIZE].toInt()                                  // MTRandom.cs:66
    }

    companion object {
        /** 状态字数 = MTRandom.N32 = 624（MTRandom.cs:19）。 */
        const val STATE_SIZE = 624

        /** RANDDATA 长度 = 624 状态 + 1 游标（MTRandom.cs:58,63,71）。 */
        const val RANDDATA_LEN = 625

        /** UInt32 承载掩码（本类公共约定，非源码常量）。 */
        private const val UINT32_MASK = 0xFFFFFFFFL

        /** init_gen_rand 乘数 1812433253（MTRandom.cs:98）。 */
        private const val MULT = 1812433253L

        /** 奇偶校验掩码 {PARITY1..4} = {1,0,0,331998852}（MTRandom.cs:9-15、106）。 */
        private val PARITY = longArrayOf(1L, 0L, 0L, 331998852L) // MTRandom.cs:106

        /** gen_rand_all_19937 输出掩码（MTRandom.cs:150-153）。 */
        private const val MASK_W3 = 0xBFFFFFF6L // MTRandom.cs:150
        private const val MASK_W2 = 0xBFFAFFFFL // MTRandom.cs:151
        private const val MASK_W1 = 0xDDFECB7FL // MTRandom.cs:152
        private const val MASK_W0 = 0xDFFFFFEFL // MTRandom.cs:153

        /** `x << n`（UInt32 回绕，MTRandom.cs:150-153 的 `<<8`/`<<24`/`<<18`）。 */
        private inline fun u32Shl(x: Long, n: Int): Long = (x shl n) and UINT32_MASK
    }

    /**
     * SFMT19937 奇偶校验（MTRandom.cs:104-134）：
     * `inner = ^(sfmt[i] & PARITY[i])`（:107-111）；折叠 `for (i=16; i>0; i>>=1) num ^= num >> i`
     * ⇒ i 依次取 16,8,4,2,1（:112-115）；`num &= 1`（:116）；已为 1 则返回（:117-120）；
     * 否则按 PARITY 从低位找第一个置位 bit 异或进对应 sfmt[i] 后**立即返回**（:121-133，
     * 原版内层 return 直接退出整个方法，本处用 labeled return 复刻）。
     */
    private fun periodCertification() {
        var inner = 0L
        for (i in 0..3) inner = inner xor (sfmt[i] and PARITY[i]) // MTRandom.cs:107-111
        var k = 16
        while (k > 0) { // MTRandom.cs:112-115
            inner = inner xor (inner ushr k)
            k = k ushr 1
        }
        inner = inner and 1L // MTRandom.cs:116
        if (inner == 1L) return // MTRandom.cs:117-120
        certify@ for (i in 0..3) { // MTRandom.cs:121-133
            var bit = 1L
            for (j in 0 until 32) { // MTRandom.cs:124
                if ((bit and PARITY[i]) != 0L) { // MTRandom.cs:126
                    sfmt[i] = sfmt[i] xor bit    // MTRandom.cs:128
                    break@certify                // MTRandom.cs:129（return）
                }
                bit = u32Shl(bit, 1) // MTRandom.cs:131（num2 <<= 1，uint 回绕）
            }
        }
    }

    /**
     * `gen_rand_all()` → `gen_rand_all_19937()`（MTRandom.cs:136-164）。
     * 156 个 128-bit 块（4×uint）就地滚动更新；`num` 遍历块首（0,4,...,620），
     * `num2` 起点 488（M=156 块的滞后指针，:145，逢 624 归零 :158-161）、
     * `num3` 起点 616、`num4` 起点 620（上一轮/上两轮块指针，:146-147、:154-155）。
     * 每块内按 +3 → +2 → +1 → +0 顺序写回，读到的同块低位字为本轮旧值、
     * 高位滞后块可能已被本轮先行迭代就地更新——与 C# 求值顺序逐字一致。
     */
    private fun genRandAll() {
        val a = sfmt // MTRandom.cs:143
        var num = 0  // MTRandom.cs:144
        var num2 = 488 // MTRandom.cs:145
        var num3 = 616 // MTRandom.cs:146
        var num4 = 620 // MTRandom.cs:147
        do {
            // word 3（MTRandom.cs:150）：a ^ (a<<8) ^ (prev3[2]>>24) ^ (lag3[3]>>8)
            //   ^ ((lag2[3]>>11) & 0xBFFFFFF6) ^ (prev2[3]<<18)
            a[num + 3] = (a[num + 3]
                xor u32Shl(a[num + 3], 8)
                xor (a[num + 2] ushr 24)
                xor (a[num3 + 3] ushr 8)
                xor ((a[num2 + 3] ushr 11) and MASK_W3)
                xor u32Shl(a[num4 + 3], 18)
                ) and UINT32_MASK
            // word 2（MTRandom.cs:151）
            a[num + 2] = (a[num + 2]
                xor u32Shl(a[num + 2], 8)
                xor (a[num + 1] ushr 24)
                xor u32Shl(a[num3 + 3], 24)
                xor (a[num3 + 2] ushr 8)
                xor ((a[num2 + 2] ushr 11) and MASK_W2)
                xor u32Shl(a[num4 + 2], 18)
                ) and UINT32_MASK
            // word 1（MTRandom.cs:152）
            a[num + 1] = (a[num + 1]
                xor u32Shl(a[num + 1], 8)
                xor (a[num] ushr 24)
                xor u32Shl(a[num3 + 2], 24)
                xor (a[num3 + 1] ushr 8)
                xor ((a[num2 + 1] ushr 11) and MASK_W1)
                xor u32Shl(a[num4 + 1], 18)
                ) and UINT32_MASK
            // word 0（MTRandom.cs:153）：本行原版无 `>>24` 同块项
            a[num] = (a[num]
                xor u32Shl(a[num], 8)
                xor u32Shl(a[num3 + 1], 24)
                xor (a[num3] ushr 8)
                xor ((a[num2] ushr 11) and MASK_W0)
                xor u32Shl(a[num4], 18)
                ) and UINT32_MASK
            num3 = num4 // MTRandom.cs:154
            num4 = num  // MTRandom.cs:155
            num += 4    // MTRandom.cs:156
            num2 += 4   // MTRandom.cs:157
            if (num2 >= STATE_SIZE) { // MTRandom.cs:158-161
                num2 = 0
            }
        } while (num < STATE_SIZE) // MTRandom.cs:163（do-while，共 156 次）
    }
}

/**
 * RNG 出号观察口（DBG4 P3 log 事件流；挂点见 [Sfmt19937.drawProbe]）。
 * 实现不得阻塞、不得抛（探针异常由 runtime 吞弃）；`value` = 本次出号（UInt32，
 * Long 低 32 位承载）；`seq` = 观察口挂上后的出号累计序号，与本次执行内抽号一一对应。
 */
fun interface RngProbe {
    fun onDraw(value: Long, seq: Long)
}
