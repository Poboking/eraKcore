package com.eratw.port.core.code

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R7 波 C / L11：`LineTable` 的**组合形态**（base + 补丁尾段，按 pc 分派）出口判据。
 *
 * ## 这个测试守的是什么
 *
 * 装载期要给出「base 的行号 + 补丁的行号」，但**不能拼接**：真机 base 是 4,884,338 条
 * 指令，拼成一张 `IntArray` 是 19.5 MB，而 `Program.lines` 是惰性物化 —— 每次导入 mod
 * 就付一次，正好压在 512 MiB 堆、已实测 422 MiB 的那条线上。于是
 * [LineTable.composite] 只拼「pc 空间」：`pc < base.count` 归 base，其余折回 tail。
 *
 * 九条判据（逐条对应本文件的用例）：
 * ① 零组合（`of(...)`）逐位不变：`count` / `lineOf` / `toIntArray` / `bytes` 与改造前同一口径，
 *   含越界回落 [LineTable.UNKNOWN_LINE]。
 * ② 组合后 `count == base.count + tail.count`，**每一个** pc 都与手工拼接的期望行号逐项相等。
 * ③ 跨界三点（`base.count - 1` / `base.count` / `count - 1`）逐个正确 —— `pc - base.count`
 *   折回最容易在这里错。
 * ④ 越界口径不变：`-1` / `count` / `count + 100` 全部回落 -1，**不抛错**（与 `getOrElse` 同口径）。
 * ⑤ 逃逸行号跨段：两段各带一条 > 0xFFFF 的行号，组合后两侧都读得出（逃逸表没被组合层吞掉）。
 * ⑥ 分块形态的 base：base 段的 pc 走 base 的块填充，tail 段的 pc **不触发** base 的块回读
 *   （用计数桩断言 `read` 调用次数）。
 * ⑦ `toIntArray()` 逐元素等于 `base.toIntArray() + tail.toIntArray()`（冷路径的拼接口径）。
 * ⑧ 构造期不变式：能通过公开工厂触发的（负 `count`、空 tail）各有用例；
 *   其余（总条数溢出 Int、`count` 与两段之和不符、组合视图自带 packed/blocks/source 或逃逸表、
 *   非组合表带 tail）在公开工厂下**构造不出来**，只能靠 init 兜底 —— 见文末说明。
 * ⑨ 诊断面：`bytes` / `blockCount` / `filledBlocks` 两段相加，`blockLength` / `blockIndexOf`
 *   明确抛错（两段各有各的块几何，按哪一段答都是错值）。
 */
class LineTableCompositeTest {

    // ------------------------------------------------------------------
    // 桩：计数的行号块供给（判据 ⑥）
    // ------------------------------------------------------------------

    /** 记 [read] 调用次数与块号的块供给；内容取自同一份原始 i32 行号。 */
    private class CountingLineBlocks(private val raw: IntArray) : LineBlockSource {
        var reads: Int = 0
            private set

        val readBlocks: MutableList<Int> = ArrayList()

        override fun read(blockIndex: Int, out: IntArray, length: Int) {
            reads++
            readBlocks.add(blockIndex)
            val from = blockIndex * LineTable.BLOCK
            for (i in 0 until length) out[i] = raw[from + i]
        }
    }

    // ------------------------------------------------------------------
    // 夹具：base 10 条（行号 100..109）、tail 5 条（行号 200..204）
    // ------------------------------------------------------------------

    private val baseLines = IntArray(10) { 100 + it }
    private val tailLines = IntArray(5) { 200 + it }

    private fun base(): LineTable = LineTable.of(baseLines)
    private fun tail(): LineTable = LineTable.of(tailLines)
    private fun composite(): LineTable = LineTable.composite(base(), tail())

    private val expected: List<Int> get() = baseLines.toList() + tailLines.toList()

    // ------------------------------------------------------------------
    // 判据 ①：零组合逐位不变
    // ------------------------------------------------------------------

    @Test
    fun `零组合：of 的 count 与取用面逐位不变`() {
        // 三种取值各来一条：未知（-1）、直存（0..65533）、逃逸（> 65533 / < -1）
        val lines = intArrayOf(-1, 0, 1, 65533, 65534, 70_000, -2, 42)
        val table = LineTable.of(lines)

        assertEquals(8, table.count)
        for (i in lines.indices) assertEquals(lines[i], table.lineOf(i), "pc=$i 与入参逐字相同")
        assertContentEquals(lines, table.toIntArray(), "toIntArray 是同一函数的下标枚举")
        assertEquals(
            3 * 16L + 8 * 2L + 3 * 4L * 2,
            table.bytes,
            "整表形态公式不变：3 个数组头 + count×2 B + 两张逃逸表（本表 3 条逃逸）",
        )
        assertFalse(table.isPaged, "of 造的是整表形态")
        assertEquals(0, table.blockCount(), "整表形态没有块位表")
        assertEquals(0, table.filledBlocks())
        assertEquals(8, table.blockLength(0), "整表形态的 blockLength 仍是纯算术（末块按 count 截断）")
        assertEquals(0, table.blockIndexOf(0))
    }

    @Test
    fun `零组合：越界回落 UNKNOWN_LINE 且不抛错`() {
        val table = LineTable.of(baseLines)
        assertEquals(LineTable.UNKNOWN_LINE, table.lineOf(-1), "负下标回落 -1")
        assertEquals(LineTable.UNKNOWN_LINE, table.lineOf(10), "count 回落 -1")
        assertEquals(LineTable.UNKNOWN_LINE, table.lineOf(10 + 100), "远超 count 回落 -1")
        assertEquals(-1, LineTable.UNKNOWN_LINE, "UNKNOWN_LINE 就是 -1（与 CodeBuilder.emit 的缺省同口径）")
    }

    // ------------------------------------------------------------------
    // 判据 ②：组合后计数 + 逐 pc 取用
    // ------------------------------------------------------------------

    @Test
    fun `组合：count 等于两段之和，每个 pc 与手工拼接逐项相等`() {
        val t = composite()
        assertEquals(15, t.count, "10 base + 5 tail")
        val want = expected
        for (pc in 0 until t.count) {
            assertEquals(want[pc], t.lineOf(pc), "pc=$pc")
        }
    }

    // ------------------------------------------------------------------
    // 判据 ③：跨界三点
    // ------------------------------------------------------------------

    @Test
    fun `组合：跨界三个 pc 逐个正确`() {
        val t = composite()
        val bc = 10
        assertEquals(109, t.lineOf(bc - 1), "base.count - 1 归 base 的末条")
        assertEquals(200, t.lineOf(bc), "base.count 归 tail 的首条（折回 tail 的 0）")
        assertEquals(204, t.lineOf(t.count - 1), "count - 1 归 tail 的末条")

        // 反向钉：段界两侧的值不同，防止「全归 base」也能凑出答案
        assertTrue(t.lineOf(bc - 1) != t.lineOf(bc), "段界两侧必须是不同的行号")
    }

    // ------------------------------------------------------------------
    // 判据 ④：越界口径不变
    // ------------------------------------------------------------------

    @Test
    fun `组合：越界回落 UNKNOWN_LINE 且不抛错`() {
        val t = composite()
        assertEquals(LineTable.UNKNOWN_LINE, t.lineOf(-1), "pc < 0 直接回落，不许转给 base 变成负偏移")
        assertEquals(LineTable.UNKNOWN_LINE, t.lineOf(-100), "更负同样回落")
        assertEquals(LineTable.UNKNOWN_LINE, t.lineOf(t.count), "count 回落")
        assertEquals(LineTable.UNKNOWN_LINE, t.lineOf(t.count + 100), "远超 count 回落")
        assertEquals(LineTable.UNKNOWN_LINE, t.lineOf(Int.MAX_VALUE), "Int 上界也不许折出负下标")
    }

    // ------------------------------------------------------------------
    // 判据 ⑤：逃逸行号跨段
    // ------------------------------------------------------------------

    @Test
    fun `组合：两段的逃逸行号都读得出（逃逸表没被组合层吞掉）`() {
        val big1 = 70_000
        val big2 = 90_001
        val baseT = LineTable.of(intArrayOf(1, 2, big1, 4))
        val tailT = LineTable.of(intArrayOf(5, big2))
        val t = LineTable.composite(baseT, tailT)

        assertEquals(6, t.count)
        assertEquals(big1, t.lineOf(2), "base 段的逃逸行号")
        assertEquals(big2, t.lineOf(5), "tail 段的逃逸行号（组合层不许把它吞成 -1）")
        assertContentEquals(intArrayOf(1, 2, big1, 4, 5, big2), t.toIntArray())
        // 组合层自身不持逃逸表，两段的常驻各算各的 ⇒ bytes 相加即可覆盖
        assertEquals(baseT.bytes + tailT.bytes, t.bytes)
    }

    // ------------------------------------------------------------------
    // 判据 ⑥：分块形态的 base
    // ------------------------------------------------------------------

    @Test
    fun `组合：分块 base 的段内 pc 走块填充，tail 段不触发 base 回读`() {
        val raw = IntArray(20) { 300 + it }
        val src = CountingLineBlocks(raw)
        var i = 0
        val baseT = LineTable.ofPaged(raw.size, { raw[i++] }, src)
        assertEquals(0, src.reads, "装载趟不许回读（分块形态装载后零块）")
        assertTrue(baseT.isPaged, "样本必须是分块形态，否则本用例什么也没证明")
        assertFalse(composite().isPaged, "两段都整表 ⇒ 组合层不许报分块")

        val tailT = LineTable.of(tailLines)
        val t = LineTable.composite(baseT, tailT)
        assertTrue(t.isPaged, "任一段分块 ⇒ 组合层必须报分块（否则装置把「没常驻」读成「整表常驻」）")

        // 先取 tail 段：一块都不该灌（tail 是整表，与 base 的块供给无关）
        assertEquals(200, t.lineOf(20), "tail 首条")
        assertEquals(204, t.lineOf(24), "tail 末条")
        assertEquals(0, src.reads, "取 tail 段不许触发 base 的块回读")
        assertEquals(0, baseT.filledBlocks(), "tail 段取用后 base 仍零块")

        // 再取 base 段：走 base 的 lineOf，块按需灌一次
        assertEquals(300, t.lineOf(0), "base 首条")
        assertEquals(319, t.lineOf(19), "base 末条")
        assertEquals(1, src.reads, "同一块只回读一次（后续 pc 直取已灌块）")
        assertEquals(listOf(0), src.readBlocks.toList(), "只碰了第 0 块")
        assertEquals(1, baseT.filledBlocks())
        assertEquals(1, t.filledBlocks(), "组合层的已灌块 = 两段相加")
        assertEquals(baseT.blockCount() + tailT.blockCount(), t.blockCount(), "块位数也是两段相加")
        assertEquals(baseT.bytes + tailT.bytes, t.bytes, "常驻是两段相加（不许报拼接后的整表大小）")
    }

    // ------------------------------------------------------------------
    // 判据 ⑦：toIntArray 拼接正确
    // ------------------------------------------------------------------

    @Test
    fun `组合：toIntArray 逐元素等于两段拼接`() {
        val b = base()
        val ta = tail()
        val t = LineTable.composite(b, ta)
        assertContentEquals(b.toIntArray() + ta.toIntArray(), t.toIntArray())
        assertContentEquals(expected.toIntArray(), t.toIntArray(), "也与手工期望逐项相等")

        // 分块 base 的物化口径同一条（会灌满所有块 —— 冷路径允许）
        val raw = IntArray(20) { 300 + it }
        var i = 0
        val pagedBase = LineTable.ofPaged(raw.size, { raw[i++] }, CountingLineBlocks(raw))
        val mixed = LineTable.composite(pagedBase, ta)
        assertContentEquals(pagedBase.toIntArray() + ta.toIntArray(), mixed.toIntArray())
    }

    // ------------------------------------------------------------------
    // 判据 ⑧：构造期不变式（能通过公开工厂触发的）
    // ------------------------------------------------------------------

    @Test
    fun `空 tail：count 仍等于 base 的 count，且分派不走错段`() {
        val b = base()
        val emptyTail = LineTable.of(IntArray(0))
        val t = LineTable.composite(b, emptyTail)
        assertEquals(b.count, t.count, "空 tail 不许让 count 漂移")
        for (pc in 0 until b.count) assertEquals(b.lineOf(pc), t.lineOf(pc), "pc=$pc 仍归 base")
        assertEquals(LineTable.UNKNOWN_LINE, t.lineOf(b.count), "空 tail 上界外仍回落")
        assertContentEquals(b.toIntArray(), t.toIntArray())
        // 空 tail 自己仍有一张零长主表（3 个数组头），相加口径照旧
        assertEquals(b.bytes + emptyTail.bytes, t.bytes, "常驻仍是两段相加")
    }

    @Test
    fun `空 base：整段 pc 都归 tail`() {
        val ta = tail()
        val t = LineTable.composite(LineTable.of(IntArray(0)), ta)
        assertEquals(ta.count, t.count)
        for (pc in 0 until ta.count) assertEquals(ta.lineOf(pc), t.lineOf(pc), "pc=$pc 折回 tail 的同一 pc")
        assertEquals(LineTable.UNKNOWN_LINE, t.lineOf(ta.count))
    }

    @Test
    fun `组合可以嵌套：两段拼成的表再拼第三段仍逐项正确`() {
        val third = IntArray(3) { 400 + it }
        val t = LineTable.composite(composite(), LineTable.of(third))
        assertEquals(18, t.count, "10 + 5 + 3")
        val want = expected + third.toList()
        for (pc in 0 until t.count) assertEquals(want[pc], t.lineOf(pc), "pc=$pc")
        assertEquals(LineTable.UNKNOWN_LINE, t.lineOf(t.count))
    }

    @Test
    fun `负 count 的构造期不变式：ofPaged 传入负数当场抛错并点名`() {
        // count 为负只能从流式/分块工厂喂进去（of(IntArray) 的负长度被数组本身挡掉）
        val ex = assertFailsWith<IllegalArgumentException>("负 count 必须 fail-fast，不许静默纠正") {
            LineTable.ofPaged(-1, { -1 }, CountingLineBlocks(IntArray(0)))
        }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("count 不能为负"), "错误消息必须点名 count，实际: $msg")
        assertTrue(msg.contains("-1"), "必须报出实际值，实际: $msg")
    }

    // ------------------------------------------------------------------
    // 判据 ⑨：诊断面
    // ------------------------------------------------------------------

    @Test
    fun `组合：块几何读数明确抛错，不返回看起来合理的错数`() {
        val t = composite()
        val e1 = assertFailsWith<UnsupportedOperationException>("组合形态没有单一套块几何") { t.blockLength(0) }
        assertTrue(e1.message!!.contains("组合"), "抛错必须说明是组合形态，实际: ${e1.message}")
        val e2 = assertFailsWith<UnsupportedOperationException>("tail 段块号要按 pc - base.count 折算") {
            t.blockIndexOf(10)
        }
        assertTrue(e2.message!!.contains("组合"), "抛错必须说明是组合形态，实际: ${e2.message}")

        // 对照：普通表的这两个读数照旧（不因组合分支而改变）
        val plain = LineTable.of(IntArray(LineTable.BLOCK + 1))
        assertEquals(LineTable.BLOCK, plain.blockLength(0), "满块")
        assertEquals(1, plain.blockLength(1), "末块只剩 1 槽")
        assertEquals(1, plain.blockIndexOf(LineTable.BLOCK), "块定位是纯算术")
    }

    /*
     * 判据 ⑧ 的其余不变式（公开工厂下构造不出来，只能由 init 兜底）：
     * 1. base.count / tail.count 为负 —— LineTable 自身的 count 非负不变式已先挡（见上一条用例）；
     * 2. 总条数溢出 Int —— 需要两段合计 > 2^31-1 条，测试里造不出来；
     * 3. count != base.count + tail.count —— composite 自己算 count，工厂面无从喂错；
     * 4. 组合视图自带 packed / blocks / source 或逃逸表 —— composite 恒传 null / 空表；
     * 5. 非组合表带 tail —— 主构造是 private，只有本类内部能调。
     * 这五条留在 init 里是给「后来接线的人」的护栏（例如改用次构造器拼装时），
     * 不以单测覆盖为目标。
     */
}
