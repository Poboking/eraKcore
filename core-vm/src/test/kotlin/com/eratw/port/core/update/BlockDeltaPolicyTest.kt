package com.eratw.port.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 块级差量（P2-13，U8）的**判定层**：清单新键的读数，与「这一版到底下哪一件」的次序。
 *
 * 这里钉的两件事各自最贵的地方不同：
 *
 * 1. **向后兼容**（第二节）。新键是**加**出来的，不是改出来的：清单里没有 `assets-blockdelta-*`
 *    时，判定结果必须与这一版之前**逐字相同**。已经发出去的包里没有任何一份带这三行，
 *    只要新代码在「键缺席」时改变了一点点行为，就等于把老清单在新客户端上打死 ——
 *    那种事只在玩家点「检查更新」时才暴露，而且表现为「没有更新」，最难查。
 * 2. **回落次序**（第一节与第三节）。块级 → 文件级 → 全量，三档之间只在「够格」时才降级；
 *    基线不等、盘上没装过、盘上那份不可信这三条一视同仁地挡在两档差量之前
 *    （判式与 [UpdatePolicy.deltaUsable] 同源，本文件把它钉成矩阵）。
 *
 * 应用侧（真把补丁拼回文件）不在这里：那是 `platform` 的 `BlockDeltaAssetTreeTest`。
 */
class BlockDeltaPolicyTest {

    private fun artifact(name: String) = Artifact(name, 4096L, "a".repeat(64))

    private fun info(
        full: Artifact? = artifact("eratw-assets-8.zip"),
        delta: Artifact? = null,
        baseVersion: Long = UpdateManifest.NO_ASSETS_BASE_VERSION,
    ): UpdateInfo = UpdateInfo(
        format = 1,
        generated = "2026-11-05T00:00:00Z",
        note = "",
        apk = null,
        assets = AssetsChannel(8L, artifact("eratw-assets-8.zip")),
        mirrors = emptyList(),
        warnings = emptyList(),
        assetsFull = full,
        assetsDelta = delta,
        assetsBaseVersion = baseVersion,
    )

    private val block = artifact("eratw-assets-blockdelta-8.zip")

    // ------------------------------------------------------------------
    // 一、块级档的资格矩阵（与文件级差量同一条闸）
    // ------------------------------------------------------------------

    @Test
    fun `块级差量只在基线恰好等于盘上版本时可用`() {
        // 等于基线 ⇒ 可用
        assertTrue(UpdatePolicy.blockDeltaUsable(block, 7L, 7L))
        // 盘上比基线新 ⇒ 不可用（差量会盖掉更新过的块）
        assertFalse(UpdatePolicy.blockDeltaUsable(block, 7L, 8L))
        // 盘上比基线旧 ⇒ 不可用（中间那几版整段缺失）
        assertFalse(UpdatePolicy.blockDeltaUsable(block, 7L, 6L))
        // 盘上从没装过 ⇒ 只能拿全量
        assertFalse(UpdatePolicy.blockDeltaUsable(block, 7L, 0L))
    }

    @Test
    fun `块级差量对无基线与缺件一视同仁地不启用`() {
        // 清单没有 assets-base-version（-1）：没资格谈差量
        assertFalse(UpdatePolicy.blockDeltaUsable(block, -1L, 0L))
        assertFalse(UpdatePolicy.blockDeltaUsable(block, 0L, 0L), "基线为 0 也是没基线")
        assertFalse(UpdatePolicy.blockDeltaUsable(null, 7L, 7L), "三件套不齐（解析侧给 null）⇒ 不可用")
    }

    @Test
    fun `盘上那份不可信时块级档与文件级档一起关死`() {
        // diskTrusted=false 的两个来源：体检读不出/对不上，或同一基线的差量刚失败过。
        assertFalse(UpdatePolicy.blockDeltaUsable(block, 7L, 7L, diskTrusted = false))
        assertFalse(UpdatePolicy.deltaUsable(block, 7L, 7L, diskTrusted = false))
    }

    // ------------------------------------------------------------------
    // 二、向后兼容：新键缺席时结论与这一版之前逐字相同
    // ------------------------------------------------------------------

    @Test
    fun `新键缺席时三档回落结论与不传块级时逐字相同`() {
        val cases = listOf(
            "只有全量" to info(),
            "全量 + 文件级差量（够格）" to info(delta = artifact("eratw-assets-delta-8.zip"), baseVersion = 7L),
            "全量 + 文件级差量（基线不符）" to info(delta = artifact("d.zip"), baseVersion = 5L),
            "只有旧键 assets 那种老包" to info(full = null),
            "什么都没发" to info(full = null, delta = null),
        )
        for (installed in listOf(0L, 5L, 6L, 7L, 8L)) {
            for ((label, subject) in cases) {
                val without = UpdatePolicy.dataArtifactFor(subject, installed)
                val withNull = UpdatePolicy.dataArtifactFor(subject, null, installed)
                assertEquals(without?.fileName, withNull?.fileName,
                    "$label @ installed=$installed：传 null 必须与原判定同结论")
                val kind = UpdatePolicy.dataArtifactKind(subject, null, installed)
                assertEquals(without != null, kind != DataArtifactKind.NONE,
                    "$label @ installed=$installed：kind 与选中的件同源")
            }
        }
    }

    @Test
    fun `老清单读不出块级三件套时判为缺席而不是判为坏`() {
        val legacy = listOf(
            "update-format 1",
            "apk-version-code 16",
            "assets-version 7",
            "assets-file eratw-assets-7.zip",
            "assets-size 100",
            "assets-sha256 " + "b".repeat(64),
            "assets-delta-file eratw-assets-delta-7.zip",
            "assets-delta-size 10",
            "assets-delta-sha256 " + "c".repeat(64),
            "assets-base-version 6",
            "note 上一版",
        ).joinToString("\n")
        val read = BlockDeltaManifest.parseManifest(legacy)
        // 没有这三行 ⇒ 块级通道当作不存在
        assertNull(read.artifact)
        // 缺席不是错误：不该给出问题文案去吓调用方
        assertNull(read.problem)
        assertFalse(read.isPresent)
    }

    // ------------------------------------------------------------------
    // 三、回落次序：块级 → 文件级 → 全量
    // ------------------------------------------------------------------

    @Test
    fun `够格的块级档优先于文件级差量`() {
        val fileDelta = artifact("eratw-assets-delta-8.zip")
        val subject = info(delta = fileDelta, baseVersion = 7L)
        assertEquals(block.fileName, UpdatePolicy.dataArtifactFor(subject, block, 7L)?.fileName)
        assertEquals(DataArtifactKind.BLOCK_DELTA, UpdatePolicy.dataArtifactKind(subject, block, 7L))
    }

    @Test
    fun `块级档不够格时退回文件级差量，结论与不接新键时一致`() {
        val fileDelta = artifact("eratw-assets-delta-8.zip")
        val subject = info(delta = fileDelta, baseVersion = 7L)
        // 块级的三件套坏了（解析侧给 null）：文件级还够格，就走文件级。
        assertEquals(fileDelta.fileName, UpdatePolicy.dataArtifactFor(subject, null, 7L)?.fileName)
        assertEquals(DataArtifactKind.FILE_DELTA, UpdatePolicy.dataArtifactKind(subject, null, 7L))
        // 基线不符（盘上已经是 8）：两档差量一起不成立 ⇒ 全量。
        assertEquals(UpdatePolicy.fullDataArtifact(subject)?.fileName,
            UpdatePolicy.dataArtifactFor(subject, block, 8L)?.fileName)
        assertEquals(DataArtifactKind.FULL, UpdatePolicy.dataArtifactKind(subject, block, 8L))
    }

    @Test
    fun `只有块级包而没有文件级包时块级仍可用`() {
        // 发布侧允许「只发块级」这一形（新对象与旧对象各写各的三件套，谁也不要求谁）。
        val subject = info(baseVersion = 7L)
        assertEquals(block.fileName, UpdatePolicy.dataArtifactFor(subject, block, 7L)?.fileName)
        assertEquals(DataArtifactKind.BLOCK_DELTA, UpdatePolicy.dataArtifactKind(subject, block, 7L))
    }

    @Test
    fun `数据通道不成立与没写基线这两种形状都不猜一件`() {
        val nothing = UpdateInfo(1, "", "", null, null, emptyList(), emptyList())
        assertNull(UpdatePolicy.dataArtifactFor(nothing, block, 7L))
        assertEquals(DataArtifactKind.NONE, UpdatePolicy.dataArtifactKind(nothing, block, 7L))
        // 没有 assets-base-version 的清单：块级包再齐全也不启用（基线没处对）。
        // 回落的落点是**全量件**，不是「通道不成立」：玩家照样能更新，只是多下几百 MiB。
        val noBase = info()
        assertEquals(UpdatePolicy.fullDataArtifact(noBase)?.fileName,
            UpdatePolicy.dataArtifactFor(noBase, block, UpdatePolicy.NO_ASSETS_INSTALLED)?.fileName,
            "清单没写基线 ⇒ 差量一律不成立，回落全量")
        assertEquals(DataArtifactKind.FULL,
            UpdatePolicy.dataArtifactKind(noBase, block, UpdatePolicy.NO_ASSETS_INSTALLED))
    }

    // ------------------------------------------------------------------
    // 四、清单三件套的形状判式（与 UpdateManifest 的可选组同口径）
    // ------------------------------------------------------------------

    private val full3 = mapOf(
        BlockDeltaManifest.KEY_FILE to "eratw-assets-blockdelta-8.zip",
        BlockDeltaManifest.KEY_SIZE to "12345",
        BlockDeltaManifest.KEY_SHA to "d".repeat(64),
    )

    @Test
    fun `三件齐全就读出文件名与字节数与摘要`() {
        val read = BlockDeltaManifest.parse(full3)
        assertTrue(read.isPresent)
        assertNull(read.problem, "读出来了就不该有问题文案")
        assertEquals("eratw-assets-blockdelta-8.zip", read.artifact!!.fileName)
        assertEquals(12345L, read.artifact!!.sizeBytes)
        assertEquals("d".repeat(64), read.artifact!!.sha256Hex)
    }

    @Test
    fun `缺半组时整组忽略并说清缺哪一键`() {
        for (missing in listOf(BlockDeltaManifest.KEY_SIZE, BlockDeltaManifest.KEY_SHA)) {
            val read = BlockDeltaManifest.parse(full3 - missing)
            assertNull(read.artifact, "缺一条就整组当不存在（不猜）")
            assertTrue(read.problem!!.contains(missing), "问题文案要点名缺的那一键：${read.problem}")
        }
    }

    @Test
    fun `形状不对的三件各自都要落到问题文案里`() {
        val badShapes = listOf(
            "文件名带路径" to (full3 + mapOf(BlockDeltaManifest.KEY_FILE to "../evil.zip")),
            "文件名字符非法" to (full3 + mapOf(BlockDeltaManifest.KEY_FILE to "eratw 8.zip")),
            "字节数不是整数" to (full3 + mapOf(BlockDeltaManifest.KEY_SIZE to "12.3")),
            "字节数为 0" to (full3 + mapOf(BlockDeltaManifest.KEY_SIZE to "0")),
            "摘要不是 64 位" to (full3 + mapOf(BlockDeltaManifest.KEY_SHA to "abc")),
            "摘要带非十六进制字符" to (full3 + mapOf(BlockDeltaManifest.KEY_SHA to "g".repeat(64))),
        )
        for ((label, values) in badShapes) {
            val read = BlockDeltaManifest.parse(values)
            assertNull(read.artifact, "$label 必须读不出件")
            assertTrue(read.problem != null, "$label 要给出原因")
        }
    }

    @Test
    fun `摘要大小写都收并归一成小写（与 UpdatePolicy 归一SHA 同一条）`() {
        val read = BlockDeltaManifest.parse(full3 + mapOf(BlockDeltaManifest.KEY_SHA to "D".repeat(64)))
        assertTrue(read.isPresent)
        assertEquals("d".repeat(64), read.artifact!!.sha256Hex)
    }

    @Test
    fun `一个键都没有时是缺席不是坏`() {
        val read = BlockDeltaManifest.parse(emptyMap())
        assertNull(read.artifact)
        assertNull(read.problem)
    }

    @Test
    fun `键名常量与发布侧写出的三行逐字同值`() {
        val text = listOf(
            "update-format 1",
            BlockDeltaManifest.KEY_FILE + " eratw-assets-blockdelta-9.zip",
            BlockDeltaManifest.KEY_SIZE + " 77",
            BlockDeltaManifest.KEY_SHA + " " + "e".repeat(64),
        ).joinToString("\n")
        val read = BlockDeltaManifest.parseManifest(text)
        assertEquals("eratw-assets-blockdelta-9.zip", read.artifact!!.fileName)
        assertEquals(77L, read.artifact!!.sizeBytes)
        assertEquals(
            listOf("assets-blockdelta-file", "assets-blockdelta-size", "assets-blockdelta-sha256"),
            listOf(BlockDeltaManifest.KEY_FILE, BlockDeltaManifest.KEY_SIZE, BlockDeltaManifest.KEY_SHA),
        )
    }

    @Test
    fun `同前缀的杂键不会串进块级读数`() {
        val text = listOf(
            "update-format 1",
            "mirror https://eratw-android.asia/updates/",
            "assets-blockdelta-file-only-decoy x",
        ).joinToString("\n")
        assertFalse(BlockDeltaManifest.parseManifest(text).isPresent)
    }
}
