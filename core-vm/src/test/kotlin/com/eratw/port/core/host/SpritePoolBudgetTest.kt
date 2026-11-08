package com.eratw.port.core.host

import com.eratw.port.core.image.PngImage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [SpritePool] 负号段（CSV 资源表父图）的 **Java 堆侧主动像素预算**档
 * （fix-spritebudget 车道）。上一路 fix-pixelbudget 把预算闸挂在 [GraphicsPool.get]，
 * 但负号段缓冲从不进 `GraphicsPool.buffers`（`ResourceContents.kt:183-204` 建出来的
 * `GraphicsBuffer(-(n+1))` 只被 `SpritePool.resourceSprites` 引用）⇒ 那道闸**结构上看不见
 * 它们**。本测试钉负号段自己的四件事：
 * ① 超预算时父图确实退回未解码态、[SpritePool.resourceResidentPixelBytes] 跟着降，
 *   且重解**逐字节得同一张图**（`evictDeferred` 判据 1：负号段像素只会被读，
 *   GraphicsBuffer.kt 的 evictDeferred KDoc；isCreated/尺寸不动的语义根据是
 *   GraphicsImage.cs:44-54、ASpriteSingle.cs:24-34，反编译在 ERATW_ROOT 下 reference/emuera-cs）；
 * ② 预算与正号段**同源共档**：读同一个覆写性质、同一个分母（[GraphicsPool.sharedPixelBudgetBytes]），
 *   正号段驻留多少就从共用预算里占多少——这是任务书判据 2 选定的形状，各设一档等于把
 *   两段上限抬到 maxMemory/2，论据见 [SpritePool.evictDeferredPixelsWithinSharedBudget] 的 KDoc；
 * ③ 检查点触发面在宿主页档（VM 线程回合边界，GameHost 接线），本测试钉 API 语义；
 * ④ 护持者：`protect` 传 null 时能退掉那一张、传它时**必须不退**（对照组自证判据真的在做事，
 *   手感沿上一路 GraphicsPoolBudgetTest 的「无 protect 对照」例）。
 *
 * 全部走 [ResourceContents.load] 的真实装载路（假宿主、真解析），小尺寸缓冲（4 KiB 像素级）
 * ——预算是字节判据，行为与真语料 8000×4000 工作表同形，单测不拿真尺寸图烧堆。
 */
class SpritePoolBudgetTest {

    private val side = 64
    private val sheetPixels = side * side.toLong()          // 4096
    private val sheetBytes = sheetPixels * 4L               // 16384

    @AfterTest
    fun clearOverride() {
        System.clearProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY)
    }

    /**
     * 假宿主：CSV 逐行登记「name,file.webp」，尺寸从 image 表给，像素由**确定性合成函数**给出
     * （同一 seed 重解逐字同图 —— 这正是 evictDeferred 判据的装置化）；decodeCalls 记录每张
     * 父图解了几次，用来证明重解真的发生过（不是断言一个恒 0 的计数）。
     */
    private class FakeHost(
        private val sizes: MutableMap<String, IntArray> = LinkedHashMap(),
        private val seeds: MutableMap<String, Int> = LinkedHashMap(),
        /** 头读得出尺寸、像素却解不出的坏图（decode 恒 null）。 */
        private val broken: MutableSet<String> = LinkedHashSet(),
    ) : ResourceFileHost {

        val decodeCalls = HashMap<String, Int>()
        private val csVs = ArrayList<String>()
        private val rows = LinkedHashMap<String, String>()

        fun csv(path: String, body: String) {
            csVs.add(path)
            rows[path] = body
        }

        /** 注册一张可解图（路径 = csv 所在目录 + 文件名，与 ResourceContents 拼法一致）。 */
        fun image(path: String, w: Int, h: Int, seed: Int) {
            sizes[path] = intArrayOf(w, h)
            seeds[path] = seed
        }

        fun brokenImage(path: String, w: Int, h: Int) {
            sizes[path] = intArrayOf(w, h)
            broken.add(path)
        }

        override fun listCsvFiles(): List<String> = csVs

        override fun readBytes(path: String): ByteArray? =
            rows[path]?.toByteArray(Charsets.UTF_8) ?: if (sizes.containsKey(path)) ByteArray(0) else null

        override fun imageSize(path: String, bytes: ByteArray?): IntArray? = sizes[path]?.copyOf()

        override fun decode(path: String, bytes: ByteArray): PngImage? {
            decodeCalls[path] = (decodeCalls[path] ?: 0) + 1
            if (path in broken) return null
            val size = sizes[path] ?: return null
            val seed = seeds[path] ?: 0
            return PngImage(size[0], size[1], synthPixels(seed, size[0] * size[1]))
        }
    }

    /** 装载 n 张独立父图（每张恰好被一个整图 sprite 引用），返回池与宿主。 */
    private fun loadSheets(count: Int): Pair<SpritePool, FakeHost> {
        val host = FakeHost()
        val body = StringBuilder()
        for (i in 1..count) {
            body.append("S$i,s$i.webp\n")
            host.image("resources/budget/s$i.webp", side, side, seed = 1000 + i)
        }
        host.csv("resources/budget/budget.csv", body.toString())
        val pool = SpritePool()
        val report = ResourceContents.load(pool, host)
        assertTrue(report.warnings.isEmpty(), "装载不该有告警：${report.warnings}")
        assertEquals(count, report.spriteCount)
        return pool to host
    }

    /** 首访问落地一张父图的像素（真机上由 GDRAWSPRITE 合成读触发）。 */
    private fun materialize(pool: SpritePool, name: String): GraphicsBuffer {
        val buffer = pool.find(name)!!.buffer!!
        buffer.pixels
        return buffer
    }

    // ------------------------------------------------------------------
    // ① 超预算 ⇒ 退回未解码态、读数跟着降、重解逐字同图
    // ------------------------------------------------------------------

    @Test
    fun `超预算时父图退回未解码态且驻留读数跟着降`() {
        val (pool, host) = loadSheets(3)
        val a = materialize(pool, "S1")
        val b = materialize(pool, "S2")
        materialize(pool, "S3")
        assertEquals(sheetBytes * 3, pool.resourceResidentPixelBytes(), "三张都驻留")

        // 未超预算的对照：判据不成立时零动作、一次都不许多解。
        assertEquals(0L, pool.evictDeferredPixelsWithinBudget(sheetBytes * 3))
        assertEquals(mapOf(
            "resources/budget/s1.webp" to 1,
            "resources/budget/s2.webp" to 1,
            "resources/budget/s3.webp" to 1,
        ), host.decodeCalls, "预算未破时不得触发任何解码")

        val freed = pool.evictDeferredPixelsWithinBudget(sheetBytes * 2)

        assertEquals(sheetPixels, freed, "退回一张：freed 按像素条数计（口径同 evictDeferredPixels）")
        assertEquals(sheetBytes * 2, pool.resourceResidentPixelBytes(), "驻留读数跟着降")
        assertEquals(0L, a.pixelBytes, "登记序最前的一张先退（CSV 声明序，见方法 KDoc）")
        assertEquals(sheetBytes, b.pixelBytes, "预算达标即停，不多退")

        // 重解逐字得同一张图，且解码计数真的 +1 —— 判据自证执行过。
        val redecoded = a.pixels
        assertEquals(sheetPixels, redecoded.size.toLong())
        val expected = synthPixels(1001, side * side)
        for (idx in expected.indices) {
            assertEquals(expected[idx], redecoded[idx], "重解像素第 $idx 格必须逐字相同")
        }
        assertEquals(2, host.decodeCalls["resources/budget/s1.webp"], "重解必须真的又解了一次")
    }

    @Test
    fun `逐出前后 isCreated 宽高与登记数逐字不变`() {
        val (pool, _) = loadSheets(2)
        val a = materialize(pool, "S1")
        val beforeCreated = a.isCreated
        val beforeWidth = a.width
        val beforeHeight = a.height

        pool.evictDeferredPixelsWithinBudget(0L)

        assertEquals(beforeCreated, a.isCreated, "IsCreated 只看尺寸在不在（GraphicsImage.cs:44-54）")
        assertEquals(beforeWidth, a.width, "宽度不得被回收动")
        assertEquals(beforeHeight, a.height, "高度不得被回收动")
        assertEquals(2, pool.resourceSize(), "资源表登记数不动（重解必得同一张图，sprite 语义无变化）")
        assertEquals(0L, pool.resourceResidentPixelBytes(), "预算 0 = 尽量退（自洽读法同正号段）")
    }

    // ------------------------------------------------------------------
    // ② 预算同源共档
    // ------------------------------------------------------------------

    @Test
    fun `负号段与正号段读同一个预算旋钮`() {
        System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, (sheetBytes * 4).toString())
        val gfx = GraphicsPool()
        // 实例口与伴生口同值（伴生口是 fix-spritebudget 上收的共用读法）。
        assertEquals(sheetBytes * 4, gfx.pixelBudgetBytes())
        assertEquals(gfx.pixelBudgetBytes(), GraphicsPool.sharedPixelBudgetBytes())
        // 无覆写时两者都回落同一个默认算式。
        System.clearProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY)
        assertEquals(
            Runtime.getRuntime().maxMemory() / GraphicsPool.PIXEL_BUDGET_DIVISOR,
            GraphicsPool.sharedPixelBudgetBytes(),
            "算式只有一份：预算 = Runtime.maxMemory() ÷ 4",
        )
        // 负号段不许有第二个分母：共享档入口就是拿这个数减正号段驻留。
        val (pool, _) = loadSheets(3)
        materialize(pool, "S1"); materialize(pool, "S2"); materialize(pool, "S3")
        val effective = GraphicsPool.sharedPixelBudgetBytes() - 0L
        assertTrue(effective > sheetBytes * 3, "本挡默认预算必须容得下三张小图（没超就不退）")
        assertEquals(0L, pool.evictDeferredPixelsWithinSharedBudget(0L))
        assertEquals(sheetBytes * 3, pool.resourceResidentPixelBytes())
    }

    @Test
    fun `正号段驻留从共用预算里挤占负号段额度`() {
        System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, (sheetBytes * 4).toString())
        // 正号段：两张 4 KiB 工作表驻留（走真实 get + 延迟落地路）。
        val gfx = GraphicsPool()
        for (id in 1..2) {
            val buffer = gfx.get(id)
            assertTrue(
                buffer.createDeferredFromFile(side, side, "resources/budget/gfx-$id.webp") {
                    IntArray(side * side) { idx -> 0x01000000 or idx }
                },
            )
            buffer.pixels
        }
        assertEquals(sheetBytes * 2, gfx.residentBytes())

        val (pool, _) = loadSheets(3)
        materialize(pool, "S1"); materialize(pool, "S2"); materialize(pool, "S3")

        // 共用预算 4 档、正号占了 2 ⇒ 负号只许持 2，超出的那 1 张被退。
        val freed = pool.evictDeferredPixelsWithinSharedBudget(gfx.residentBytes())
        assertEquals(sheetPixels, freed, "负号段额度 = 总预算 − 正号段驻留")
        assertEquals(sheetBytes * 2, pool.resourceResidentPixelBytes())

        // 正号段贴满整档 ⇒ 负号段额度归零，剩余两张全退（预算 ≤ 0 = 尽量退的自洽读法）。
        for (id in 3..4) {
            val buffer = gfx.get(id)
            assertTrue(
                buffer.createDeferredFromFile(side, side, "resources/budget/gfx-$id.webp") {
                    IntArray(side * side) { idx -> 0x01000000 or idx }
                },
            )
            buffer.pixels
        }
        assertEquals(sheetBytes * 4, gfx.residentBytes(), "正号段自己没超档，预算档不该动它")
        val freedRest = pool.evictDeferredPixelsWithinSharedBudget(gfx.residentBytes())
        assertEquals(sheetPixels * 2, freedRest)
        assertEquals(0L, pool.resourceResidentPixelBytes())
    }

    @Test
    fun `总预算不可知时负号段一像素都不退`() {
        // 桌面装置与全部单测走的那一支：`defaultPixelBudgetBytes` 对不可知堆回落
        // `Long.MAX_VALUE` ⇒ `sharedPixelBudgetBytes() - gfxResidentBytes` 仍是 MAX 量级，
        // 判停判据恒不成立（减法不许翻符号 —— 减错方向会变成负数 = 尽量退，桌面直接把父图全退光）。
        System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, Long.MAX_VALUE.toString())
        val (pool, _) = loadSheets(1)
        val only = materialize(pool, "S1")
        assertEquals(sheetBytes, pool.resourceResidentPixelBytes())

        assertEquals(0L, pool.evictDeferredPixelsWithinSharedBudget(0L), "不可知堆 ⇒ 负号段预算档不生效")
        assertEquals(sheetBytes, pool.resourceResidentPixelBytes(), "驻留读数一格不动")
        assertEquals(sheetBytes, only.pixelBytes, "父图必须还在驻留态")

        // 对照：把共用预算拉到 1 字节 ⇒ 同一张必须退。上面那个 0 是预算给的，不是压根没候选
        // （否则本用例改成「永远返回 0」也会绿）。
        System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, "1")
        assertEquals(sheetPixels, pool.evictDeferredPixelsWithinSharedBudget(0L))
        assertEquals(0L, pool.resourceResidentPixelBytes())
    }

    // ------------------------------------------------------------------
    // ④ 护持者（对照组自证判据在做事）
    // ------------------------------------------------------------------

    @Test
    fun `护持者不被逐出且无护持者时同一张必须可退`() {
        val (pool, host) = loadSheets(1)
        val only = materialize(pool, "S1")
        assertEquals(sheetBytes, pool.resourceResidentPixelBytes())

        assertEquals(
            0L,
            pool.evictDeferredPixelsWithinBudget(sheetBytes - 1L, protect = only),
            "护持者就是全池唯一可退项时，必须什么都不退（否则读侧每次取图都整图重解的螺旋）",
        )
        assertEquals(sheetBytes, pool.resourceResidentPixelBytes(), "没退成功就不该动驻留读数")
        assertEquals(sheetBytes, only.pixelBytes, "被护持的那一张必须还在驻留态")
        assertEquals(1, host.decodeCalls["resources/budget/s1.webp"], "护持生效时不得发生任何重解")

        // 对照组：同一判据去掉护持者 ⇒ 它被退掉。上面那个 0 是护栏给的，不是判据没触发。
        assertEquals(
            sheetPixels,
            pool.evictDeferredPixelsWithinBudget(sheetBytes - 1L),
            "无护持者时同一张必须可退",
        )
        assertEquals(0L, pool.resourceResidentPixelBytes())
    }

    // ------------------------------------------------------------------
    // 读数形状：去重、帧计入、运行表与真解失败面
    // ------------------------------------------------------------------

    @Test
    fun `读数按对象身份去重且不含运行表缓冲`() {
        val host = FakeHost()
        host.csv(
            "resources/p/pack.csv",
            "A,pack.png\nB,pack.png,10,10,20,20\nAN,ANIME,64,64\nAN,frame1.png,0,0,64,64\nAN,frame2.png,0,0,64,64\n",
        )
        host.image("resources/p/pack.png", 64, 64, seed = 7)
        host.image("resources/p/frame1.png", 64, 64, seed = 8)
        host.image("resources/p/frame2.png", 64, 64, seed = 9)
        val pool = SpritePool()
        val report = ResourceContents.load(pool, host)
        assertEquals(1, report.animeCount, "前置：AN 必须是动画 sprite（帧走 frames 表）")

        // 三个静态/动画条目共享 pack 之外只有两帧：去重后缓冲 = pack + frame1 + frame2。
        materialize(pool, "A")
        pool.find("AN")!!.frames!!.forEach { it.buffer.pixels }
        assertEquals(sheetBytes * 3, pool.resourceResidentPixelBytes(), "同一父图被两个 sprite 共享时只计一份，动画帧要计入")

        // 运行表（SPRITECREATE 形状）挂一张正号 gid 缓冲：负号段读数必须不含它（那段归 GraphicsPool 记账）。
        val runtimeBuffer = GraphicsBuffer(777)
        assertTrue(runtimeBuffer.createFromArgb(side * 2, side * 2, IntArray(side * 2 * side * 2)))
        pool.put(SpritePool.Sprite("RUNTIME_ONE", side * 2, side * 2, runtimeBuffer))
        assertEquals(sheetBytes * 3, pool.resourceResidentPixelBytes(), "运行表缓冲不进负号段读数")
        val sweep = pool.evictDeferredPixelsWithinBudget(0L)
        assertEquals(sheetPixels * 3, sweep, "预算闸只扫负号段")
        assertEquals(sheetBytes * 4, runtimeBuffer.pixelBytes, "正号段那一张一个字都没被动过")
        assertSame(runtimeBuffer, pool.find("RUNTIME_ONE")!!.buffer)
    }

    @Test
    fun `真解失败的缓冲自动退出预算档候选集`() {
        val host = FakeHost()
        host.csv("resources/bad/bad.csv", "X,bad.png\n")
        host.brokenImage("resources/bad/bad.png", side, side)
        val pool = SpritePool()
        ResourceContents.load(pool, host)

        val buffer = materialize(pool, "X")
        // 解码失败支：像素折成同尺寸全透明图、deferredSource 置 null（GraphicsBuffer.pixels 的
        // 失败分支）——「重解必得同一张图」不再成立，预算档与水位档都不得再动它。
        assertEquals(sheetBytes, pool.resourceResidentPixelBytes(), "透明兜底图照样占 Java 堆驻留")
        assertEquals(0L, pool.evictDeferredPixelsWithinBudget(0L), "不可重解的缓冲不在候选集")
        assertEquals(sheetBytes, pool.resourceResidentPixelBytes())
        assertEquals(sheetBytes, buffer.pixelBytes)
    }

    // ------------------------------------------------------------------
    // 成对读数：反复逐出 + 重解读数不漂移
    // ------------------------------------------------------------------

    @Test
    fun `反复逐出与重解后读数不漂移`() {
        val (pool, host) = loadSheets(4)
        val buffers = (1..4).map { materialize(pool, "S$it") }
        for (round in 1..6) {
            buffers.forEach { it.pixels }
            assertEquals(
                sheetBytes * 4, pool.resourceResidentPixelBytes(),
                "第 $round 轮：落地后读数 = 去重缓冲 pixelBytes 之和",
            )
            assertEquals(
                buffers.sumOf { it.pixelBytes }, pool.resourceResidentPixelBytes(),
                "第 $round 轮：快照与逐张 pixelBytes 手工和恒等（无计数器 ⇒ 无漂移可言）",
            )
            pool.evictDeferredPixelsWithinBudget(sheetBytes)
            assertEquals(sheetBytes, pool.resourceResidentPixelBytes(), "第 $round 轮：退到预算线上停")
            pool.evictDeferredPixelsWithinBudget(0L)
            assertEquals(0L, pool.resourceResidentPixelBytes(), "第 $round 轮：尽量退后归零")
        }
        // 6 轮 × (4 首/重解 + 全退后再落地) 必然远超 4 次 —— 每轮都真解过，计数不撒谎。
        assertTrue(host.decodeCalls.values.sum() >= 4 * 6, "重解次数读数：${host.decodeCalls}")
    }

    // ------------------------------------------------------------------
    // 快路径（零分配，2026-11-04 OOM 波）
    // ------------------------------------------------------------------

    /**
     * 真机 0.2.39+15 报告 `eratw/2026-11-04/1793779421942`（HUAWEI TET-AN00）：负号段驻留
     * 已是 0 MiB，`SpritePool.evictDeferredPixelsWithinBudget` 内的 `HashMap.resize` 仍 OOM
     * —— 旧形状下这道闸每次进闸都要为读数建 30k+ 槽的 HashSet，堆贴顶（可用不足 1%）时
     * 闸自己就是那根稻草。新形状：先按「不去重」零分配扫一遍，不去重和 ≤ 预算必然无事可做。
     * 本用例钉快路径的**行为**面（分配量 JVM 单测测不了，行为等价由这里与去重路用例合钉）：
     * 短路时零动作、零重解。
     */
    @Test
    fun `预算内快路径零动作且不付额外解码`() {
        val (pool, host) = loadSheets(2)
        materialize(pool, "S1")
        materialize(pool, "S2")
        val before = host.decodeCalls.toMap()

        // 不去重和 = 去重驻留 = 两张整表 = 预算 ⇒ 快路径短路（去重路也会判在预算内，
        // 两条路同一结论；这里钉的是短路形态不触发任何解码）。
        assertEquals(0L, pool.evictDeferredPixelsWithinBudget(sheetBytes * 2))
        assertEquals(sheetBytes * 2, pool.resourceResidentPixelBytes())
        assertEquals(before, host.decodeCalls, "快路径不许触发重解")
    }

    /**
     * 快路径的判据正确性：不去重和是**上界**（共享父图的每一片 sprite 都把同一份
     * pixelBytes 再数一遍）。上界超预算 ≠ 真超预算 —— 快路径必须放行进去重路，
     * 而去重路必须判「在预算内，一个不退」；预算压到去重驻留之下时仍然要退得掉。
     */
    @Test
    fun `共享父图的多 sprite 上界超预算而去重读数仍在预算内`() {
        val host = FakeHost()
        host.image("resources/budget/shared.webp", side, side, seed = 7)
        host.csv("resources/budget/budget.csv", "S1,shared.webp\nS2,shared.webp\n")
        val pool = SpritePool()
        val report = ResourceContents.load(pool, host)
        assertTrue(report.warnings.isEmpty(), "装载不该有告警：${report.warnings}")
        materialize(pool, "S1")
        assertEquals(1, host.decodeCalls["resources/budget/shared.webp"], "同路径只解一次")

        // 不去重和 = 2×sheetBytes > 预算（快路径放行）；去重驻留 = sheetBytes ≤ 预算 ⇒ 零退。
        assertEquals(0L, pool.evictDeferredPixelsWithinBudget(sheetBytes + 1L))
        assertEquals(sheetBytes, pool.find("S1")!!.buffer!!.pixelBytes, "去重后驻留在预算内")

        // 对照：预算压到去重驻留之下 ⇒ 退得掉（快路径没有把闸变成摆设）。
        assertEquals(sheetPixels, pool.evictDeferredPixelsWithinBudget(sheetBytes - 1L))
        assertEquals(0L, pool.resourceResidentPixelBytes())
    }
}

// 合成像素的单一算式：宿主 decode 与测试期望共用（嵌套类拿不到外部实例成员，故放文件级）。
private fun synthPixels(seed: Int, count: Int): IntArray =
    IntArray(count) { idx -> 0xFF000000.toInt() or ((idx * 31 + seed) and 0x00FFFFFF) }
