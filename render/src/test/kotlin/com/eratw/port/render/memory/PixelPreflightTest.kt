package com.eratw.port.render.memory

import com.eratw.port.core.host.GraphicsPool
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PixelPreflight] 的定向单测（lane oomalloc）：三条腿各钉一组，其中**头寸腿整组用的都是
 * 端点真机上报的原样读数**（0.2.39 / 512 MiB 挡贴顶现场），断言的是
 * 「这笔分配当时要不起 ⇒ 预检判不足 ⇒ 调用方走降级而不是整局 OOM」。
 *
 * 报告出处（`<work-root>/.tmp/report-20261104/raw-500/`，逐份的调用点见交付报告）：
 *
 * | 需要的字节 | 报的 free | used = 上限 - free | 抛在哪 |
 * |---|---|---|---|
 * | 1,440,016 | 974,768 | 535,896,144 | `GraphicsBuffer.create`（084134） |
 * | 1,000,016 | 511,024 | 536,359,888 | `GraphicsBuffer.blitFrom`（133432） |
 * | 1,000,016 | 218,056 | 536,652,856 | `GraphicsBuffer.blitFrom`（151435） |
 * | 1,565,112 | 372,584 | 536,498,328 | `GraphicsBuffer.blitFrom`（233215） |
 * | 500,016 | 287,440 | 536,583,472 | `GraphicsBuffer.blitFrom`（004311） |
 * | 4,352,016 | 1,957,760 | 534,913,152 | `GraphicsBuffer.create`（083415） |
 * | 8,000,016 | 4,294,608 | 532,576,304 | `SaveFormat.entryWithTag`（224037） |
 */
class PixelPreflightTest {

    private val max512 = 536_870_912L

    /** 装置性质用完就摘：本类的现取用例不许把覆写留给下一个测试类。 */
    @AfterTest
    fun clearOverrides() {
        System.clearProperty(PixelPreflight.MAX_MEMORY_OVERRIDE_PROPERTY)
        System.clearProperty(PixelPreflight.USED_OVERRIDE_PROPERTY)
        System.clearProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY)
    }

    // ---- 尺寸反核：报告里那串字节数就是 ARGB 像素阵列（减 16 字节数组头）----

    @Test
    fun `像素阵列字节数与上报的分配尺寸同式`() {
        // ART 给数组的对象头 + 长度 = 16 字节，上报尺寸 = 数据 + 16。
        assertEquals(1_440_016L - 16L, PixelPreflight.pixelBytes(600, 600))
        assertEquals(1_000_016L - 16L, PixelPreflight.pixelBytes(500, 500))
        assertEquals(500_016L - 16L, PixelPreflight.pixelBytes(500, 250))
        assertEquals(8_000_016L - 16L, PixelPreflight.pixelBytes(2_000, 1_000))
        // 语料最大单张工作表：8000x4000x4 = 122 MiB 级（各档 KDoc 同读数）。
        assertEquals(128_000_000L, PixelPreflight.pixelBytes(8_000, 4_000))
    }

    @Test
    fun `中间量升 Long 不被 Int 回绕骗过`() {
        // 60000x60000x4 = 14,400,000,000 B：Int 会绕成 1,748,288,384 这种「看起来很小」的数，
        // 头寸腿就会被谎报骗开放行。
        assertEquals(14_400_000_000L, PixelPreflight.pixelBytes(60_000, 60_000))
        assertEquals(0L, PixelPreflight.pixelBytes(0, 4_000))
        assertEquals(0L, PixelPreflight.pixelBytes(-1, -1))
    }

    // ---- 腿 1：头寸（真机读数整组）----

    @Test
    fun `真机贴顶现场的七笔分配全部判要不起`() {
        val cases = listOf(
            1_440_016L to 535_896_144L,
            1_000_016L to 536_359_888L,
            1_000_016L to 536_652_856L,
            1_565_112L to 536_498_328L,
            500_016L to 536_583_472L,
            4_352_016L to 534_913_152L,
            8_000_016L to 532_576_304L,
        )
        cases.forEach { (needed, used) ->
            assertFalse(
                PixelPreflight.fits(needed, max512, used, BUDGET_512),
                "needed=$needed used=$used 时空闲只剩 ${max512 - used} B，本该判不足",
            )
        }
    }

    @Test
    fun `空闲刚好装得下时判要得起`() {
        // 边界：needed == 空闲（装载 tight 预检同式的 `>` 才判不足）。
        val used = max512 - 1_440_016L
        assertTrue(PixelPreflight.fits(1_440_016L, max512, used, BUDGET_512))
        assertFalse(PixelPreflight.fits(1_440_016L + 1L, max512, used, BUDGET_512))
    }

    @Test
    fun `稳态回合水位下语料最大单张照常放行`() {
        // used=182 MiB 是真机回合稳态读数（`GraphicsPool` 预算档 KDoc 第 2 条引的
        // HeapCompositionProbeTest 同一条），一张整工作表 122 MiB 要得起。
        val used = 182L * MIB
        assertTrue(PixelPreflight.fits(PixelPreflight.pixelBytes(8_000, 4_000), max512, used, BUDGET_512))
    }

    // ---- 腿 2：单件上限（既有像素预算，档位无关；只加码、不永久拒）----

    @Test
    fun `单张超过像素预算的一件在空闲不够时判要不起`() {
        // 256 MiB 挡：预算 = 64 MiB，122 MiB 的一张工作表要「自己那份 + 一整个预算档」
        // ⇒ 空闲得凑到 186 MiB 才解；这里 used=100 MiB 只剩 156 MiB，判要不起。
        // 加码的本意（预算档 KDoc 第 2 条「留下 3/4 给装载基线与瞬时拷贝」）在这里照样守住，
        // 只是不再写成「任何时候都拒」。
        val max256 = 268_435_456L
        val budget256 = max256 / 4L
        assertFalse(
            PixelPreflight.fits(PixelPreflight.pixelBytes(8_000, 4_000), max256, 100L * MIB, budget256),
            "256 MiB 挡 + 已用 100 MiB 时 122 MiB 单张要不起（差的就是那 64 MiB 加码）",
        )
        // 512 MiB 挡上同一张正是「预算恰好容得下一张整工作表」那条论据，放行。
        assertTrue(PixelPreflight.fits(PixelPreflight.pixelBytes(8_000, 4_000), max512, 100L * MIB, BUDGET_512))
    }

    @Test
    fun `越上限的大件在空闲充足时照常放行`() {
        // 4.981 语料唯一越过 128 MiB 档的一张（lane preflightblast 尺寸普查，
        // `resources/汎用モブ子dairi版/汎用モブ子dairi版.webp` = 6964x6191 = 164.5 MiB ARGB，
        // 装载期 CSV 必经）。旧的绝对否决把它在**任何时候**都打成透明图 —— 那正是
        // 「修 OOM 修出一屏缺图」的形状。
        val sheet = PixelPreflight.pixelBytes(6_964, 6_191)
        assertEquals(172_456_496L, sheet)
        // 标题页稳态 live ≈ 69 MiB（预算档 KDoc 第 2 条引的 heapRootAttribution 读数）。
        assertTrue(PixelPreflight.fits(sheet, max512, 69L * MIB, BUDGET_512), "空闲 443 MiB 时必须解得出来")
        // 真机回合稳态 used ≈ 182 MiB：空闲 330 MiB ≥ 164.5 + 128 ⇒ 仍放行。
        assertTrue(PixelPreflight.fits(sheet, max512, 182L * MIB, BUDGET_512))
    }

    @Test
    fun `越上限的大件只在压力真上来时才拒`() {
        val sheet = PixelPreflight.pixelBytes(6_964, 6_191)
        // 80% 水位（RunTrail.RELEASE_AT_PERCENT）那一档：used 410 MiB ⇒ 空闲 102 MiB，
        // 连头寸腿都过不去 ⇒ 拒（这一形就是原报要防的「贴顶还在 decode」）。
        assertFalse(PixelPreflight.fits(sheet, max512, 410L * MIB, BUDGET_512))
        // 加码腿自己那一刀：空闲 280 MiB 装得下 164.5 MiB，但装不下「164.5 + 128 的留白」⇒ 拒。
        assertFalse(PixelPreflight.fits(sheet, max512, max512 - 280L * MIB, BUDGET_512))
        assertTrue(PixelPreflight.fits(sheet, max512, max512 - 293L * MIB, BUDGET_512), "边界：293 MiB 空闲刚好够")
    }

    @Test
    fun `上限以内的一件不受加码影响`() {
        // 恰好等于上限的一件不加码（判式是 `>` 才加码）：512 MiB 全空时空闲 512 MiB 走得通；
        // 越过一个字节就要多留 128 MiB，同一条 used 下就拒。
        val atCeiling = BUDGET_512
        val overCeiling = BUDGET_512 + 1L
        val used = 380L * MIB
        assertTrue(PixelPreflight.fits(atCeiling, max512, used, BUDGET_512), "等于上限的一件按头寸腿判")
        assertFalse(PixelPreflight.fits(overCeiling, max512, used, BUDGET_512), "越界一件要额外一整个预算档")
    }

    @Test
    fun `单件上限哨兵表示这一腿不加码`() {
        // 240 MiB 的一件、空闲 300 MiB：把这一腿摘掉（UNKNOWN）只剩头寸腿 ⇒ 放行；
        // 装上它就要多留 128 MiB ⇒ 拒 —— 两条腿各管各的，加码腿只在越档时才动手。
        val item = PixelPreflight.pixelBytes(9_000, 7_000)
        assertEquals(252_000_000L, item)
        val used = max512 - 300L * MIB
        assertTrue(PixelPreflight.fits(item, max512, used, PixelPreflight.UNKNOWN))
        assertFalse(PixelPreflight.fits(item, max512, used, BUDGET_512))
    }

    @Test
    fun `谎报的极大件不会被加法回绕骗开放行`() {
        // 判式写成减法就是为了这一形：needed 与加码都接近 Long 上限时，
        // `needed + margin` 会绕成负数、看着「比空闲小」而放行。
        assertFalse(PixelPreflight.fits(Long.MAX_VALUE, max512, 0L, BUDGET_512))
        assertFalse(PixelPreflight.fits(Long.MAX_VALUE / 2, max512, 0L, Long.MAX_VALUE / 4))
    }

    // ---- 腿 3：不可知堆不设闸（桌面装置/纯 JVM 单测/语料对拍的行为一字不动）----

    @Test
    fun `堆上限不可知时一律放行`() {
        assertTrue(PixelPreflight.fits(1L shl 40, Long.MAX_VALUE, 0L, BUDGET_512))
        assertTrue(PixelPreflight.fits(1L shl 40, 0L, 0L, BUDGET_512))
        assertTrue(PixelPreflight.fits(1L shl 40, -1L, 0L, BUDGET_512))
        assertFalse(PixelPreflight.heapKnown(Long.MAX_VALUE))
        assertTrue(PixelPreflight.heapKnown(max512))
    }

    @Test
    fun `不需要分配的东西永远要得起`() {
        assertTrue(PixelPreflight.fits(0L, max512, max512, BUDGET_512))
        assertTrue(PixelPreflight.fits(-1L, max512, max512, BUDGET_512))
        assertTrue(PixelPreflight.fits(PixelPreflight.pixelBytes(0, 0), max512, max512, BUDGET_512))
    }

    // ---- 现取那一层：覆写性质 + 尺寸入口 ----

    @Test
    fun `覆写性质把装置拉到真机贴顶挡位`() {
        System.setProperty(PixelPreflight.MAX_MEMORY_OVERRIDE_PROPERTY, max512.toString())
        // 084134 现场：free 974,768，要 1,440,016 ⇒ 拒；1 KiB 的小件仍放行（不误拦）。
        System.setProperty(PixelPreflight.USED_OVERRIDE_PROPERTY, toppedOutUsed().toString())
        assertFalse(PixelPreflight.affordsPixels(600, 600), "511/512 MiB 现场不该放行 1.44 MiB 像素")
        assertTrue(PixelPreflight.affordsBytes(1_024L))
        // 004311 现场：free 只剩 287,440，要 500,016 ⇒ 拒。
        System.setProperty(PixelPreflight.USED_OVERRIDE_PROPERTY, (max512 - 287_440L).toString())
        assertFalse(PixelPreflight.affordsBytes(500_016L))

        System.setProperty(PixelPreflight.USED_OVERRIDE_PROPERTY, (100L * MIB).toString())
        assertTrue(PixelPreflight.affordsPixels(600, 600), "空闲充足时必须照常放行")
        assertTrue(PixelPreflight.affordsBytes(500_016L))
    }

    /** 报告 084134 的已用读数（上限减掉它报的那点空闲）。 */
    private fun toppedOutUsed(): Long = max512 - 974_768L

    @Test
    fun `桌面装置没有覆写时现取读数不误拦`() {
        // 本测试 JVM 的 maxMemory 是 1g 档（render 的 Test 任务 maxHeapSize），
        // 已用远小于它 ⇒ 一张 600x600 必须放行；缺了这条，语料闸与黄金对拍会被我这道闸改动。
        assertTrue(PixelPreflight.affordsPixels(600, 600), PixelPreflight.readout(PixelPreflight.pixelBytes(600, 600)))
    }

    // ---- 缺口补钉（lane eng-forced，任务 D5）：豁免支之外那几条判式过去没有证伪力 ----

    @Test
    fun `空闲为零或为负时任何一笔都判要不起`() {
        // `fits` 的 `headroom <= 0` 那一行（PixelPreflight.kt:120-121）过去被「needed <= 0」与
        // 「堆不可知」两条早退夹在中间，没有任何一条用例真的走到它：把这一行删掉、或把 `<=` 改成
        // `<`，整套单测照样全绿，而现取读数抖动到「已用 ≥ 上限」时就会拿一笔注定装不下的分配去撞 OOM。
        assertFalse(PixelPreflight.fits(1L, max512, max512), "空闲恰好为 0：连 1 字节都装不下")
        assertFalse(PixelPreflight.fits(1L, max512, max512 + 1L), "已用读数超过上限（抖动）：同样判不足")
        assertFalse(PixelPreflight.fits(PixelPreflight.pixelBytes(600, 600), max512, max512))
        // 同一读数下 0 字节仍然放行 —— `neededBytes <= 0` 的早退必须在前，两条判式的先后不许换。
        assertTrue(PixelPreflight.fits(0L, max512, max512))
    }

    @Test
    fun `现取读数下越界的超大一笔必须判不足`() {
        // 不设任何覆写：判的是本 JVM 的真实读数（生产里 maxMemoryOverride() 恒 null 的那一路）。
        // 要一笔两倍上限的东西 ⇒ 头寸腿必拒，拒绝分支里那次 GC 重判也救不回来
        // （affordsWithCeiling 的第二次 fits 仍用同一上限）。
        val max = PixelPreflight.maxMemoryBytes()
        assertFalse(PixelPreflight.affordsBytes(max * 2L), "两倍上限的一笔不可能装得下")
        assertFalse(PixelPreflight.affordsFileBytes(max * 2L), "整文件那一路的头寸腿无条件生效")
        // 60000x60000 = 14.4 GB 的谎报像素（Int 上会绕成 1.7 GB）：同样拒。
        assertFalse(PixelPreflight.affordsPixels(60_000, 60_000))
        // 对照：同一发里一笔小件必须放行 —— 否则上面三个 false 是「永远拒」而不是读数的结论。
        assertTrue(PixelPreflight.affordsBytes(1_024L))
    }

    @Test
    fun `整文件字节那一路不套单件上限`() {
        // 装置把挡位拉到 512 MiB、已用 280 MiB ⇒ 空闲 232 MiB。200 MiB 的一笔：头寸腿过得去，
        // 但单件上限 = 128 MiB，加码后要 200 + 128 > 232 ⇒ 像素那一路拒、整文件那一路放行。
        // 这一格钉的是 affordsWithCeiling 的 `ceiling = false` 支（affordsFileBytes 专用）；
        // 把两路合并成同一个 ceiling=true，ResourceCsvHost.readBytes 就会在装载期被误拒。
        System.setProperty(PixelPreflight.MAX_MEMORY_OVERRIDE_PROPERTY, max512.toString())
        System.setProperty(PixelPreflight.USED_OVERRIDE_PROPERTY, (280L * MIB).toString())
        val twoHundredMiB = 200L * MIB
        assertFalse(PixelPreflight.affordsBytes(twoHundredMiB), "像素那一路要额外留一整个预算档")
        assertTrue(PixelPreflight.affordsFileBytes(twoHundredMiB), "整文件那一路只看头寸")
        // 对照：同一笔在像素那一路里只要不越上限就放行（100 MiB < 128 MiB 上限不加码）。
        assertTrue(PixelPreflight.affordsBytes(100L * MIB))
    }

    @Test
    fun `单件上限读数走像素预算的覆写性质`() {
        System.setProperty(PixelPreflight.MAX_MEMORY_OVERRIDE_PROPERTY, max512.toString())
        System.setProperty(PixelPreflight.USED_OVERRIDE_PROPERTY, (100L * MIB).toString()) // 空闲 412 MiB
        // 性质落 300 MiB ⇒ 单件上限就是 300 MiB（不另算 ÷4）。310 MiB 的一笔：头寸过得去但越了上限
        // ⇒ 要再加一整个 300 MiB 档 ⇒ 拒；整文件那一路不套这条上限 ⇒ 放行。
        System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, (300L * MIB).toString())
        assertEquals(300L * MIB, PixelPreflight.ceilingBytes(), "覆写性质优先于 ÷4 算式")
        assertFalse(PixelPreflight.affordsBytes(310L * MIB))
        assertTrue(PixelPreflight.affordsFileBytes(310L * MIB))
        // 对照：恰好等于上限的一笔不加码（判式是 `>` 不是 `>=`）⇒ 放行。
        assertTrue(PixelPreflight.affordsBytes(300L * MIB), "等于上限按头寸腿判")
        // 非正读数与坏读数一律回落 ÷4（512 MiB 挡 = 128 MiB）：不许把 0 / 负数当上限用，否则
        // 「上限 = 0」会把每一件大件都判成超限（= 永久拒，正是 11-05 误伤普查撤回的那一形）。
        for (bad in listOf("0", "-5", "not-a-number")) {
            System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, bad)
            assertEquals(BUDGET_512, PixelPreflight.ceilingBytes(), "坏值 $bad 必须回落到堆上限 ÷ 4")
        }
    }

    @Test
    fun `覆写读数的合法区间`() {
        // maxMemory 覆写只认正数：0 / 负数 ⇒ 无视、回落真实读数。真实读数下「一笔等于真实上限」
        // 必拒（已用恒 > 0）；若 0 被当成上限，heapKnown(0) = false 就会一律放行 —— 这条立刻红。
        val realMax = PixelPreflight.maxMemoryBytes()
        System.setProperty(PixelPreflight.MAX_MEMORY_OVERRIDE_PROPERTY, "0")
        assertFalse(PixelPreflight.affordsBytes(realMax), "maxMemory 覆写 0 必须被无视")
        System.setProperty(PixelPreflight.MAX_MEMORY_OVERRIDE_PROPERTY, "-1")
        assertFalse(PixelPreflight.affordsBytes(realMax), "maxMemory 覆写负数必须被无视")

        // usedBytes 覆写允许 0（takeIf { it >= 0L }）：used 写 0 且上限写 512 MiB 时，
        // 「恰好一整上限」的一笔必须放行（空闲 = 上限）。这一格把 `>=` 写成 `>` 就红：
        // 那时 used=0 被无视、回落真实已用，512 MiB 的一笔装不进剩下的空闲。
        System.setProperty(PixelPreflight.MAX_MEMORY_OVERRIDE_PROPERTY, max512.toString())
        System.setProperty(PixelPreflight.USED_OVERRIDE_PROPERTY, "0")
        assertTrue(PixelPreflight.affordsFileBytes(max512), "used = 0 是合法读数")
        System.setProperty(PixelPreflight.USED_OVERRIDE_PROPERTY, "1")
        assertFalse(PixelPreflight.affordsFileBytes(max512), "used = 1 ⇒ 空闲少 1 字节，装不下")
        // 负数 used 一律无视（回落到真实已用）：上限 512 MiB、真实已用 > 0 ⇒ 一整上限装不下。
        System.setProperty(PixelPreflight.USED_OVERRIDE_PROPERTY, "-1")
        assertFalse(PixelPreflight.affordsFileBytes(max512), "used 覆写负数必须被无视")
    }

    private companion object {
        const val MIB = 1024L * 1024L

        /** 512 MiB 挡的既有像素预算 = 堆上限 ÷ 4（`PIXEL_BUDGET_DIVISOR`，128 MiB）。 */
        const val BUDGET_512 = 512L * MIB / 4L
    }
}
