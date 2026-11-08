package com.eratw.port.core.host

import com.eratw.port.core.host.SpritePool.Frame
import com.eratw.port.core.host.SpritePool.Sprite
import com.eratw.port.core.image.PngImage
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * **reportres 波（2026-11-05）—— 报告/取证链上那一发贴顶现搭表**。
 *
 * 病灶（同族第四刀，lane refbuffers 收口时点名未动的一处）：
 * [SpritePool.snapshotResourceResidentBytesLocked] 每次调用按 `resourceSprites.size * 2 + 1`
 * 现搭一枚 `HashSet` 做身份去重。消费链三处（宿主 `GameHost.graphicsPoolReportLine` 的报告行、
 * 宿主 `GameHost.censusTrailLine` 与 `memoryCensusReportLine` → `LiveSetCensus.census` 的
 * `csvResidentPixelBytes` 一项），后两条跑在 `HeapCensusPin` 的**本挡 Java 堆 ≥90% 贴顶相位**
 * （VM 线程、30 s 冷却一发），第一条在崩溃报告正文里 ⇒ 与前三刀（`evictDeferredPixels`、
 * 两处回收闸、`releaseUnused` 护栏名单）同一个相位、同一个形状。
 *
 * ## 本文件钉四件事
 * 1. **先量（步序 1，主代码未动时跑的那一发）**：规模曲线 `量·资源表规模曲线` 与真语料生产路
 *    `量·真语料装载` —— 表数组按**登记条数**长、节点按**去重后的父图张数**长，两段分开读；
 * 2. **等价臂**：面臂与集合臂在**同一次现场**上给出逐字相同的 Long（共享父图 / 动画帧 /
 *    空表 / 已 dispose / 运行表混装 / 真语料装载六种形状 + 「同一块面先后服务两块池」的
 *    clear 语义）；
 * 3. **两臂字节读数**：贴顶规模（4.981 真语料的登记条数量级）下「不接这一发 = 现搭哈希表」
 *    与「接这一发 = 复用面稳态」的实测字节，装置同 [RefBufferGuardFaceTest] /
 *    [SpriteEvictSiteScratchTest]（HotSpot `com.sun.management.ThreadMXBean` 线程累计分配；
 *    非 HotSpot 按 assumeTrue 跳过）；
 * 4. **面本体的首判口径**：[SpriteEvictScratch.addGuard] 的 Boolean 返回形与 `guardCount`
 *    的计数口径（本波把这条口从 `Unit` 收成 `Boolean`，既有调用点全当语句用）。
 *
 * **摘除臂的做法**（破坏性验证，本文件不自带开关）：把 [SpritePool.resourceResidentPixelBytes]
 * 的分派改成两臂都走 [SpritePool.snapshotResourceResidentBytesLocked]（即面参数被忽略、闸内
 * 恒现搭 `HashSet`）⇒ 下面 `接面臂` 的两条字节钉（`reused < 2 048 B`）当场变红，等价臂仍绿；
 * 复原后复绿。2026-11-05 实测读数写在交付回执里。
 *
 * 真机（贴顶堆）读数本文件给不出（本机 `adb devices` 只挂着模拟器，且 0.2.39 出包不在本车道）；
 * 本文件全部字节读数都是**桌面装置读数**（3g 测试挡 + HotSpot 分配计数）。
 */
class ResourceResidentSnapScratchTest {

    /** 装置尺寸：2×2 像素、16 字节驻留 —— 判据是字节算式，与真实图同形。 */
    private val side = 2
    private val bytesPerSheet = side.toLong() * side * 4L

    private fun sheet(id: Int): GraphicsBuffer = GraphicsBuffer(id).apply { create(side, side) }

    /**
     * n 条登记 / parents 张**真对象**父图（父图对象预建、按 `i % parents` 复用 —— 身份去重认的
     * 是对象，不是 id，现 new 会把父图张数悄悄放大成登记条数）。
     */
    private fun buildPool(n: Int, parents: Int): SpritePool {
        val pool = SpritePool()
        val sheets = if (parents > 0) Array(parents) { sheet(it + 1) } else emptyArray()
        for (i in 0 until n) pool.putResource(Sprite("PLAIN$i", side, side, sheets[i % parents]))
        return pool
    }

    // ------------------------------------------------------------------
    // 1 先量：规模曲线 + 真语料生产路（主代码未动时即为「现搭」单边读数）
    // ------------------------------------------------------------------

    @Test
    fun `量·资源表规模曲线 现搭哈希表与复用面各多少字节`() {
        val bean = requireAllocBean()
        val tid = Thread.currentThread().id
        println("[snap-scale] 口径：n=登记条数 parents=去重父图张数 fresh=现搭臂单发字节 reused=接面臂稳态字节")
        for (n in listOf(0, 134, 853, 2_795, 8_000, 20_000, 38_861)) {
            // 父图张数按真语料的共享比取（4.981 EAGLOV3：38,861 条 CSV 行 / 2,696 张去重父图
            // ≈ 14.4 条/张，两个数都是 `量·真语料装载` 那一发的现场产物）；
            // 小档位取全独立（节点段上界），免得把节点账读丢。
            val parents = if (n >= 8_000) n * 2_696 / 38_861 else n
            val pool = buildPool(n, parents)
            val face = SpriteEvictScratch()
            assertEquals(n, pool.resourceSize(), "装置：$n 条登记")
            // 预热两臂各一发（JIT 稳态 + 把护栏表长到水位），读数只取「一次」的净增量。
            assertEquals(bytesPerSheet * parents, pool.resourceResidentPixelBytes(), "装置：集合臂驻留")
            assertEquals(bytesPerSheet * parents, pool.resourceResidentPixelBytes(face), "装置：面臂驻留")
            val beforeFresh = bean.getThreadAllocatedBytes(tid)
            val fresh = pool.resourceResidentPixelBytes()
            val freshDelta = bean.getThreadAllocatedBytes(tid) - beforeFresh
            val beforeReused = bean.getThreadAllocatedBytes(tid)
            val reused = pool.resourceResidentPixelBytes(face)
            val reusedDelta = bean.getThreadAllocatedBytes(tid) - beforeReused
            assertEquals(fresh, reused, "两臂同现场同值（n=$n）")
            println(
                "[snap-scale] n=$n parents=$parents fresh=${freshDelta}B reused=${reusedDelta}B " +
                    "guardCount=${face.guardCount()}",
            )
            System.gc()
        }
    }

    @Test
    fun `量·真语料装载 生产路的表规模与两臂字节`() {
        val bean = requireAllocBean()
        val tid = Thread.currentThread().id
        for ((label, root) in realCorpusRoots()) {
            val pool = SpritePool()
            val report = ResourceContents.load(pool, HeaderSizeCsvHost(root))
            val n = pool.resourceSize()
            assertTrue(n > 0, "$label：资源表装不出条目")
            val face = SpriteEvictScratch()
            pool.resourceResidentPixelBytes()
            pool.resourceResidentPixelBytes(face)
            val beforeFresh = bean.getThreadAllocatedBytes(tid)
            val fresh = pool.resourceResidentPixelBytes()
            val freshDelta = bean.getThreadAllocatedBytes(tid) - beforeFresh
            val beforeReused = bean.getThreadAllocatedBytes(tid)
            val reused = pool.resourceResidentPixelBytes(face)
            val reusedDelta = bean.getThreadAllocatedBytes(tid) - beforeReused
            assertEquals(fresh, reused, "$label：真语料现场两臂逐字同值")
            println(
                "[snap-corpus] root=$label csv=${report.fileCount} 告警=${report.warnings.size} " +
                    "登记=$n 去重父图=${face.guardCount()} 驻留=${fresh / (1024 * 1024)}MiB " +
                    "fresh=${freshDelta}B reused=${reusedDelta}B",
            )
            System.gc()
        }
    }

    // ------------------------------------------------------------------
    // 2 等价臂：同一次现场，两侧同值
    // ------------------------------------------------------------------

    @Test
    fun `等价臂 六种现场两臂逐字同值`() {
        // ① 空表
        val empty = SpritePool()
        assertEquals(0L, empty.resourceResidentPixelBytes(), "装置：空表集合臂 0")
        assertEquals(empty.resourceResidentPixelBytes(), empty.resourceResidentPixelBytes(SpriteEvictScratch()))

        // ② 一张父图两条名字（去重必须只数一次）
        val shared = sheet(10)
        val dup = SpritePool().apply {
            putResource(Sprite("A1", side, side, shared))
            putResource(Sprite("A2", side, side, shared))
        }
        assertEquals(bytesPerSheet, dup.resourceResidentPixelBytes(), "装置：共享父图只计一份")
        assertEquals(dup.resourceResidentPixelBytes(), dup.resourceResidentPixelBytes(SpriteEvictScratch()))

        // ③ 动画：多帧同一父图 + 另一张静态同一父图
        val animeSheet = GraphicsBuffer(11).apply { create(4, 4) }
        val anime = SpritePool().apply {
            putResource(
                Sprite(
                    name = "S_ANIME", width = 4, height = 4,
                    frames = mutableListOf(
                        Frame(animeSheet, 0, 0, 2, 2, 0, 0, 100),
                        Frame(animeSheet, 2, 0, 2, 2, 0, 0, 100),
                    ),
                ),
            )
            putResource(Sprite("S_SAME", side, side, animeSheet))
        }
        assertEquals(4L * 4L * 4L, anime.resourceResidentPixelBytes(), "装置：动画父图两帧 + 同名静态 ⇒ 一份")
        assertEquals(anime.resourceResidentPixelBytes(), anime.resourceResidentPixelBytes(SpriteEvictScratch()))

        // ④ 已 dispose 的缓冲：成员判定同、字节段为 0
        val dead = sheet(12).also { it.dispose() }
        val deadPool = SpritePool().apply { putResource(Sprite("DEAD", side, side, dead)) }
        assertEquals(0L, deadPool.resourceResidentPixelBytes(), "装置：dispose 后驻留 0")
        assertEquals(deadPool.resourceResidentPixelBytes(), deadPool.resourceResidentPixelBytes(SpriteEvictScratch()))

        // ⑤ 运行表混装：`put`（非资源）那一半不进负号段读数，两侧同口径
        val mixed = SpritePool().apply {
            putResource(Sprite("R1", side, side, sheet(13)))
            put(Sprite("G_RUNTIME", side, side, sheet(14)))
        }
        assertEquals(bytesPerSheet, mixed.resourceResidentPixelBytes(), "装置：运行表缓冲不进读数")
        assertEquals(mixed.resourceResidentPixelBytes(), mixed.resourceResidentPixelBytes(SpriteEvictScratch()))

        // ⑥ 无基图的动画 sprite（buffer = null）：两侧都不许把它算进来、也不许炸
        val noBuffer = SpritePool().apply {
            putResource(Sprite("ONLY_FRAMES", side, side, frames = mutableListOf(Frame(sheet(15), 0, 0, 2, 2, 0, 0, 100))))
        }
        assertEquals(bytesPerSheet, noBuffer.resourceResidentPixelBytes())
        assertEquals(noBuffer.resourceResidentPixelBytes(), noBuffer.resourceResidentPixelBytes(SpriteEvictScratch()))

        // 中规模混装（5,000 条 / 400 张父图）：两臂同值 = 独立算术
        val big = buildPool(5_000, 400)
        val bigFace = SpriteEvictScratch()
        assertEquals(bytesPerSheet * 400, big.resourceResidentPixelBytes(), "独立算术：400 张去重驻留")
        assertEquals(bytesPerSheet * 400, big.resourceResidentPixelBytes(bigFace), "面臂同值")
        assertEquals(400, bigFace.guardCount(), "面里就是去重后的父图张数")
    }

    @Test
    fun `面进门先清空 同一块面先后服务两块池读数各自正确`() {
        val face = SpriteEvictScratch()
        val first = buildPool(50, 50)
        assertEquals(bytesPerSheet * 50, first.resourceResidentPixelBytes(face), "第一块池：50 张")
        assertEquals(50, face.guardCount(), "第一块池装到 50 项")
        val second = buildPool(30, 30)
        // 不 clear 就会把第二块池的每一项读成「已见过」⇒ 读数当场塌成 0；这条钉住 clear 语义。
        assertEquals(bytesPerSheet * 30, second.resourceResidentPixelBytes(face), "第二块池：30 张")
        assertEquals(30, face.guardCount(), "面被清空后重灌")
        // 容量保留（不缩表）：水位仍在上一次的高点，稳态零新建。
        val bean = requireAllocBean()
        val tid = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(tid)
        assertEquals(bytesPerSheet * 30, second.resourceResidentPixelBytes(face))
        assertTrue(
            bean.getThreadAllocatedBytes(tid) - before < 2_048L,
            "同一块面第二次走完应当是纯读写（实测 ${bean.getThreadAllocatedBytes(tid) - before} B）",
        )
    }

    @Test
    fun `默认参数就是旧臂 传 null 与不传逐字同值`() {
        val pool = buildPool(600, 90)
        val expect = bytesPerSheet * 90
        assertEquals(expect, pool.resourceResidentPixelBytes(), "不传面（旧出入口）")
        assertEquals(expect, pool.resourceResidentPixelBytes(null), "显式传 null = 同一条旧臂")
        assertEquals(expect, pool.resourceResidentPixelBytes(SpriteEvictScratch()), "传面 = 新臂同值")
    }

    // ------------------------------------------------------------------
    // 3 两臂字节读数：贴顶规模
    // ------------------------------------------------------------------

    @Test
    fun `两臂字节读数 4981 真语料量级 现搭一臂百 KB 级 接面一臂稳态零分配`() {
        val bean = requireAllocBean()
        val tid = Thread.currentThread().id
        // 现场量级照本文件 `量·真语料装载` 的 4.981 生产路读数（CSV 行 38,861 / 去重父图 2,696）：
        // 登记条数就是表数组的尺，父图张数就是节点段的尺。
        val n = 38_861
        val parents = 2_696
        val pool = buildPool(n, parents)
        val face = SpriteEvictScratch()
        assertEquals(bytesPerSheet * parents, pool.resourceResidentPixelBytes(face), "装置：预热并核读数")

        val beforeReused = bean.getThreadAllocatedBytes(tid)
        val reused = pool.resourceResidentPixelBytes(face)
        val reusedDelta = bean.getThreadAllocatedBytes(tid) - beforeReused
        val beforeFresh = bean.getThreadAllocatedBytes(tid)
        val fresh = pool.resourceResidentPixelBytes()
        val freshDelta = bean.getThreadAllocatedBytes(tid) - beforeFresh

        println("[snap-arms] n=$n parents=$parents reused=${reusedDelta}B fresh=${freshDelta}B")
        assertEquals(fresh, reused, "两臂读数逐字一致（口径一条没动）")
        assertEquals(bytesPerSheet * parents, reused)
        assertTrue(
            reusedDelta < 2_048L,
            "接面臂：$n 条登记的快照稳态零新建（实测 $reusedDelta 字节）—— " +
                "GameHost.graphicsPoolReportLine 接这一发后就是这条臂",
        )
        assertTrue(
            freshDelta > 100_000L,
            "现搭臂：不接这一发时单发现搭哈希表实测只分配了 $freshDelta 字节（表数组按 $n 条长、" +
                "节点按 $parents 张长）—— 崩溃报告与 ≥90% 贴顶分项钉发都吃这一发，" +
                "与 6c08e8bb 簇「贴顶堆上自造分配」同族",
        )
        System.gc()
    }

    @Test
    fun `两臂字节读数 4980 真语料量级 现搭一臂仍大于接面一臂两个数量级`() {
        val bean = requireAllocBean()
        val tid = Thread.currentThread().id
        val n = 134
        val pool = buildPool(n, n)
        val face = SpriteEvictScratch()
        assertEquals(bytesPerSheet * n, pool.resourceResidentPixelBytes(face), "装置：预热并核读数")

        val beforeReused = bean.getThreadAllocatedBytes(tid)
        val reused = pool.resourceResidentPixelBytes(face)
        val reusedDelta = bean.getThreadAllocatedBytes(tid) - beforeReused
        val beforeFresh = bean.getThreadAllocatedBytes(tid)
        val fresh = pool.resourceResidentPixelBytes()
        val freshDelta = bean.getThreadAllocatedBytes(tid) - beforeFresh

        println("[snap-arms-small] n=$n reused=${reusedDelta}B fresh=${freshDelta}B")
        assertEquals(fresh, reused, "两臂读数逐字一致")
        assertTrue(reusedDelta < 2_048L, "接面臂稳态（实测 $reusedDelta 字节）")
        // 小语料（4.980 的 134 条）这一臂只有 6 KB 量级 —— 写清楚：本刀的价值全在大语料一侧，
        // 小语料上两臂都是零头，不构成回归。
        assertTrue(freshDelta in 1_000L..50_000L, "现搭臂在 134 条量级是 KB 级（实测 $freshDelta 字节）")
    }

    // ------------------------------------------------------------------
    // 4 面本体的首判口径（本波把 addGuard 从 Unit 收成 Boolean）
    // ------------------------------------------------------------------

    @Test
    fun `addGuard 首判只在第一次给 true 计数口径不动`() {
        val face = SpriteEvictScratch()
        val a = sheet(20)
        val b = sheet(21)
        assertTrue(face.addGuard(a), "首次登记给 true")
        assertFalse(face.addGuard(a), "重复登记给 false")
        assertTrue(face.addGuard(b))
        assertEquals(2, face.guardCount(), "计数只在首次增长（旧口径一字没动）")
        assertTrue(face.containsGuard(a) && face.containsGuard(b))
        face.clear()
        assertEquals(0, face.guardCount(), "clear 把计数归零")
        assertTrue(face.addGuard(a), "clear 之后同一条口重新给 true")
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 真语料根：`$ERATW_GAMEDIR`（4.980）与它同级的 4.981 EAGLOV3；缺则整发跳过。 */
    private fun realCorpusRoots(): List<Pair<String, Path>> {
        val out = ArrayList<Pair<String, Path>>()
        val primary = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        if (primary != null && Files.isDirectory(primary.resolve("resources"))) {
            out += Pair(primary.fileName.toString(), primary)
        }
        val sibling = primary?.parent?.resolve("eraTW4.981-261017-EAGLOV3")
        if (sibling != null && Files.isDirectory(sibling.resolve("resources"))) {
            out += Pair(sibling.fileName.toString(), sibling)
        }
        assumeTrue(out.isNotEmpty(), "缺 ERATW_GAMEDIR 或其 resources 目录：跳过（本装置不回落假语料）")
        return out
    }

    /**
     * 真语料宿主：**只读文件头取尺寸**（PNG IHDR / WEBP 三型 / GIF 逻辑屏，与
     * `SpritePoolBudgetProbeTest` 同一口径），从不解码像素（`decode` 恒 null，且本装置不碰
     * [GraphicsBuffer.pixels]）。装出来的登记条数与去重父图张数是语料真产物 ——
     * 用固定尺寸会把带矩形列的行判成「与父图无交集」而丢行（4.981 会少装九成条目），
     * 本装置要的正是生产路的条数量级。
     */
    private class HeaderSizeCsvHost(private val root: Path) : ResourceFileHost {

        override fun listCsvFiles(): List<String> {
            val res = root.resolve("resources")
            if (!Files.isDirectory(res)) return emptyList()
            val out = ArrayList<String>()
            Files.walk(res).use { s ->
                s.forEach { p ->
                    val fn = p.fileName.toString().lowercase()
                    if (Files.isRegularFile(p) && fn.endsWith(".csv")) {
                        out.add(root.relativize(p).toString().replace('\\', '/'))
                    }
                }
            }
            return out.sorted()
        }

        override fun readBytes(path: String): ByteArray? = runCatching {
            Files.readAllBytes(root.resolve(path))
        }.getOrNull()

        override fun imageSize(path: String, bytes: ByteArray?): IntArray? {
            val b = bytes ?: readBytes(path) ?: return null
            return headerSize(b)
        }

        override fun decode(path: String, bytes: ByteArray): PngImage? = null
    }

    private fun requireAllocBean(): com.sun.management.ThreadMXBean {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported, "本 JVM 不支持线程分配计数，跳过分配读数钉")
        assumeTrue(
            bean.getThreadAllocatedBytes(Thread.currentThread().id) != -1L,
            "线程分配计数未开启，跳过分配读数钉",
        )
        return bean
    }
}

// ---- 文件级纯函数：真文件头解析（口径同 SpritePoolBudgetProbeTest 的同名装置；
// ---- 那边的 helper 是 private top-level，本文件拿不到，故此处自带一份最小实现）----

/** 真文件头解析：PNG IHDR / WEBP 三型容器 / GIF 逻辑屏。解不出返回 null（装载侧记告警）。 */
private fun headerSize(bytes: ByteArray): IntArray? {
    if (bytes.size >= 24 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
    ) {
        val w = be32(bytes, 16)
        val h = be32(bytes, 20)
        return if (w > 0 && h > 0) intArrayOf(w, h) else null
    }
    if (bytes.size >= 30 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
        String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP"
    ) {
        return when (String(bytes, 12, 4, Charsets.ISO_8859_1)) {
            "VP8X" -> intArrayOf(1 + le24(bytes, 24), 1 + le24(bytes, 27))
            "VP8L" -> {
                val v = le32(bytes, 21)
                intArrayOf((v and 0x3FFF) + 1, ((v ushr 14) and 0x3FFF) + 1)
            }
            "VP8 " -> if (bytes[23] == 0x9D.toByte() && bytes[24] == 0x01.toByte() &&
                bytes[25] == 0x2A.toByte()
            ) {
                intArrayOf(le16(bytes, 26) and 0x3FFF, le16(bytes, 28) and 0x3FFF)
            } else {
                null
            }
            else -> null
        }
    }
    if (bytes.size >= 10 && String(bytes, 0, 6, Charsets.ISO_8859_1).startsWith("GIF8")) {
        return intArrayOf(le16(bytes, 6), le16(bytes, 8))
    }
    return null
}

private fun be32(b: ByteArray, at: Int): Int =
    ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
        ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

private fun le16(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

private fun le24(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16)

private fun le32(b: ByteArray, at: Int): Int =
    le16(b, at) or (le16(b, at + 2) shl 16)
