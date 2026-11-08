package com.eratw.port.core.host

import com.eratw.port.core.image.PngImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * **真语料负号段体量装置**（fix-spritebudget 车道，任务书「量出这一段到底占多少」）：
 * 装载 `$ERATW_GAMEDIR/resources` 下全部 CSV 资源表（[ResourceContents.load] 的**生产路**，
 * 真文件头尺寸），报出负号段（CSV 父图）的体量与预算档前后对照。
 *
 * ## 口径声明（不装）
 * - **张数与尺寸是真产物**：`imageSize` 直接解析真文件的 PNG IHDR / WEBP 容器头
 *   （VP8 / VP8L / VP8X / GIF），驻留字节 = 真尺寸 × 4 B/像素 —— 「负号段占多少 MiB」
 *   这个读数与真机同源；
 * - **像素内容是确定性合成**（`synthPixels`）：桌面无 BitmapFactory，真语料的 VP8 有损位流
 *   core 侧明确不解（WebpDecoder：语料 1893 个文件属此类），真解会大面积失败折成透明兜底。
 *   合成像素保「重解逐字同图」这一不变量可验（预算档的语义根据），不代表真图内容。
 *
 * ## 报的读数（逐条一行，可直接抄进回执）
 * 1. CSV 文件数 / sprite 登记数 / 动画数 / 告警数；
 * 2. **去重父图张数与全落地驻留 MiB** —— 上一路回执留下的缺口段的确切体量（定性主会话的滞留数）；
 * 3. 无预算档对照：判据恒不成立时驻留与解码计数不动（= 加档前的旧行为）；
 * 4. 设备挡位（512 MiB 堆 ⇒ 共用预算 128 MiB）两形：
 *    a) 正号段空载 —— 本语料负号段自然体量够不够得着预算线，直接读出来；
 *    b) 正号段一张 8000×4000 工作表贴顶（122.1 MiB）—— 负号段被挤到剩 6 MiB 以内的实际退场量；
 * 5. 退场后全量重解：每张恰好重解一次、像素逐字复原（无损性读数和 CPU 代价一起给）。
 *
 * 复现（动态用例依赖环境变量，务必带 cleanTest，否则 UP-TO-DATE 假绿）：
 * ```
 * export ERATW_GAMEDIR=<work-root>/eratw-安卓移植计划/eratw-画蛇添足版eratw4.980
 * ./gradlew --configure-on-demand :core-vm:cleanTest :core-vm:test --tests "*SpritePoolBudgetProbe*" -i
 * ```
 * test worker 堆 3g（core-vm/build.gradle.kts），全落地驻留 ≈ 105 MiB + 瞬时份，挡位安全。
 */
class SpritePoolBudgetProbeTest {

    private val mib = 1024L * 1024L

    private class JvmCsvHost(private val root: Path) : ResourceFileHost {

        val decodeCalls = AtomicInteger(0)

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

        /** 装载期传 null（生产口径：只读文件头）；装置没有 `inJustDecodeBounds`，就地读一份再弃。 */
        override fun imageSize(path: String, bytes: ByteArray?): IntArray? {
            val b = bytes ?: readBytes(path) ?: return null
            return headerSize(b)
        }

        /** 确定性合成像素（口径见类 KDoc「像素内容是确定性合成」条）。 */
        override fun decode(path: String, bytes: ByteArray): PngImage? {
            val size = headerSize(bytes) ?: return null
            decodeCalls.incrementAndGet()
            val seed = path.hashCode()
            val n = size[0] * size[1]
            return PngImage(size[0], size[1], IntArray(n) { idx ->
                0xFF000000.toInt() or ((idx * 31 + seed) and 0x00FFFFFF)
            })
        }
    }

    @Test
    fun `真语料负号段体量与共用预算档前后对照`() {
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        assumeTrue(
            gameRoot != null && Files.isDirectory(gameRoot.resolve("resources")),
            "缺 ERATW_GAMEDIR 或其 resources 目录：跳过（本装置不回落假语料）",
        )
        val lines = ArrayList<String>()
        fun report(s: String) {
            lines.add(s)
            println("[csv-budget-probe] $s")
        }

        val host = JvmCsvHost(gameRoot!!)
        val pool = SpritePool()
        val t0 = System.nanoTime()
        val report0 = ResourceContents.load(pool, host)
        report(
            "装载 ${report0.fileCount} 个 CSV / sprite ${report0.spriteCount} 条 / 动画 ${report0.animeCount} 个 / " +
                "告警 ${report0.warnings.size} 条（${((System.nanoTime() - t0) / 1_000_000)} ms）",
        )

        // 去重父图集合 = resourceSprites 可达（负号 id 结构性成立：ResourceContents 只建负号缓冲）。
        val parents = HashMap<Int, GraphicsBuffer>()
        for (name in pool.names()) {
            val sprite = pool.find(name) ?: continue
            sprite.buffer?.takeIf { it.id < 0 }?.let { parents[it.id] = it }
            sprite.frames?.forEach { frame -> frame.buffer.takeIf { it.id < 0 }?.let { parents[it.id] = it } }
        }
        val potentialBytes = parents.values.sumOf { it.width.toLong() * it.height * 4L }
        report(
            "读数·负号段 CSV 父图 = ${parents.size} 张（去重），全落地驻留 = " +
                String.format("%.1f", potentialBytes.toDouble() / mib) + " MiB（真文件头尺寸 × 4 B/像素）",
        )
        assertTrue(parents.isNotEmpty(), "语料的 resources 下必须装得出父图")

        // 全落地（真机上由 GDRAWSPRITE 合成读逐张触发；这里一次扫完拿稳态读数）。
        for (buffer in parents.values) buffer.pixels
        val resident = pool.resourceResidentPixelBytes()
        report("读数·全落地后快照驻留 = ${resident / mib} MiB（与潜在量一致：${resident == potentialBytes}）")
        assertEquals(potentialBytes, resident, "全落地驻留必须等于尺寸算式（快照读数与逐张 pixelBytes 同值）")

        val savedOverride = System.getProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY)
        try {
            // —— 对照臂：无预算档（覆写成天文数字 ⇒ 判据恒不成立 = 加档前的旧行为）。
            System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, (Long.MAX_VALUE / 2).toString())
            val decodesBefore = host.decodeCalls.get()
            val freedNone = pool.evictDeferredPixelsWithinSharedBudget(0L)
            report(
                "无预算档：退 ${freedNone / 262144L} MiB（应恒 0），驻留 ${pool.resourceResidentPixelBytes() / mib} MiB，" +
                    "解码计数 ${decodesBefore} → ${host.decodeCalls.get()}（旧行为零动作）",
            )
            assertEquals(0L, freedNone)
            assertEquals(resident, pool.resourceResidentPixelBytes())

            // —— 设备挡位臂：512 MiB 堆 ⇒ 共用预算 128 MiB（与正号段同一性质、同一算式）。
            System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, (128L * mib).toString())
            val freedIdle = pool.evictDeferredPixelsWithinSharedBudget(0L)
            report(
                "预算档 128 MiB·正号空载：退 ${freedIdle / 262144L} MiB，末态驻留 " +
                    "${pool.resourceResidentPixelBytes() / mib} MiB —— " +
                    if (freedIdle == 0L) {
                        "本语料负号段自然体量够不着共用预算线（单段自足，闸不咬）"
                    } else {
                        "单段自然体量已越线，闸真的在退"
                    },
            )

            // 正号段一张 8000×4000 工作表贴顶的形状：负号段被挤到剩 128 − 122.1 ≈ 6 MiB。
            val gfxWorksheetBytes = 8000L * 4000L * 4L
            val sample = parents.values.first()
            val samplePixelsBefore = sample.pixels // 已驻留：拿住这一代数组做逐字对照
            val decodesBefore2 = host.decodeCalls.get()
            val freedPress = pool.evictDeferredPixelsWithinSharedBudget(gfxWorksheetBytes)
            val afterPress = pool.resourceResidentPixelBytes()
            val remainingBudgetMiB = (128L * mib - gfxWorksheetBytes) / mib
            report(
                "预算档·正号贴顶 122.1 MiB：负号段退 ${freedPress / 262144L} MiB，末态驻留 " +
                    "${afterPress / mib} MiB（剩余额度上限 $remainingBudgetMiB MiB；两段合计贴共用 128 MiB 档）",
            )
            assertTrue(afterPress <= 128L * mib - gfxWorksheetBytes, "被挤形状下负号段必须退到剩余额度内")
            assertTrue(freedPress > 0L, "正号贴顶时闸必须真的退得出东西（否则本档形同虚设）")

            // 退场后全量重解：每张恰好再解一次、像素逐字复原。
            for (buffer in parents.values) buffer.pixels
            val reDecodes = host.decodeCalls.get() - decodesBefore2
            assertEquals(
                parents.size.toLong(), reDecodes.toLong(),
                "被退的每张恰好重解一次（CPU 代价口径 = 每检查点周期每图一次解码）",
            )
            assertEquals(resident, pool.resourceResidentPixelBytes(), "重解后驻留读数逐字复原")
            assertTrue(
                samplePixelsBefore.contentEquals(sample.pixels),
                "重解像素逐字相同（${sample.width}x${sample.height} 整图对照，无损性读数）",
            )
        } finally {
            if (savedOverride == null) {
                System.clearProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY)
            } else {
                System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, savedOverride)
            }
        }
    }
}

// ---- 文件级纯函数：真文件头解析与字节读位（装置与 JvmCsvHost 共用，嵌套类拿不到外部实例成员） ----

/** 真文件头解析：PNG IHDR / WEBP 三型容器 / GIF 逻辑屏。解不出返回 null（装载侧会记告警）。 */
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
            // VP8X：画布尺寸在 payload 偏移 4/7 的 24 位 LE，各减 1 存储（WebpDecoder.kt:212-213）。
            "VP8X" -> intArrayOf(1 + le24(bytes, 24), 1 + le24(bytes, 27))
            // VP8L：签名 0x2F 后 14+14 位宽高超小包（WebpDecoder 的位流头同款）。
            "VP8L" -> {
                val v = le32(bytes, 21)
                intArrayOf((v and 0x3FFF) + 1, ((v ushr 14) and 0x3FFF) + 1)
            }
            // VP8 有损：3 字节 start code 0x9D 0x01 0x2A 后 14 位宽/高各 2 字节 LE。
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
