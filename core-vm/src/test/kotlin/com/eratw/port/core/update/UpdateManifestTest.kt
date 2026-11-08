package com.eratw.port.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [UpdateManifest] / [UpdatePolicy] 定向单测（国内镜像仓自动更新）。
 *
 * 钉的是「发布侧写出的清单」与「消费侧读出的判定」之间的契约面：清单一旦上线就是几百台设备
 * 在读，这里任何一条判定漂移，代价都是玩家白跑几百 MiB 流量或整条更新通道哑掉。
 * 下载与 HTTP 管线不在此（归 app 壳，真机取证）。
 */
class UpdateManifestTest {

    private val goodManifest = listOf(
        "update-format 1",
        "generated 2026-10-29T08:00:00Z",
        "apk-version-code 6",
        "apk-version-name 0.2.4",
        "apk-file eratw-0.2.4+6-abc1234-full-deadbeef.apk",
        "apk-size 778451234",
        "apk-sha256 0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789ABCDEF",
        "assets-version 6",
        "assets-file eratw-assets-6.zip",
        "assets-size 712345678",
        "assets-sha256 ${"ab".repeat(32)}",
        "note 修掉读档 256 MiB 预算误判",
        "mirror https://mirror-a.example/eratw/",
        "mirror https://mirror-b.example",
    ).joinToString("\n")

    private val assetsOnlyManifest = listOf(
        "update-format 1",
        "assets-version 7",
        "assets-file eratw-assets-7.zip",
        "assets-size 1024",
        "assets-sha256 ${"cd".repeat(32)}",
    ).joinToString("\n")

    /** 线上真实形状的清单（差量数据包那一组键齐全，见 CONTRACT-UPDATE §1.2）。 */
    private val deltaManifest = listOf(
        "update-format 1",
        "generated 2026-10-31T02:00:00Z",
        "assets-version 7",
        "assets-file eratw-assets-7.zip",
        "assets-size 712345678",
        "assets-sha256 ${"ab".repeat(32)}",
        "assets-full-file eratw-assets-7.zip",
        "assets-full-size 712345678",
        "assets-full-sha256 ${"AB".repeat(32)}",
        "assets-delta-file eratw-assets-6to7.zip",
        "assets-delta-size 1048576",
        "assets-delta-sha256 ${"cd".repeat(32)}",
        "assets-base-version 6",
        "note 改一条 ERB：只下 1 MiB 差量",
        "mirror https://eratw-android.asia/updates/",
    ).joinToString("\n")

    /** 把某几个键的行整条删掉，用来造「可选组缺半组」。 */
    private fun withoutKeys(text: String, vararg keys: String): String =
        text.lines().filterNot { line -> keys.any { line.startsWith("$it ") } }.joinToString("\n")

    private fun ready(text: String): UpdateInfo {
        val read = UpdateManifest.parse(text)
        assertTrue(read is ManifestRead.Ready, "应当解析成功，实际 $read")
        return read.info
    }

    private fun rejected(text: String): List<String> {
        val read = UpdateManifest.parse(text)
        assertTrue(read is ManifestRead.Rejected, "应当拒绝，实际 $read")
        return read.problems
    }

    // ---- 合法清单 ------------------------------------------------------

    // ---- apk-cert-sha256（覆盖升级要求同章；章不一致要在**下载前**说破） ----

    @Test
    fun `章指纹是可选键，缺席不告警也不拦`() {
        val info = ready(goodManifest)
        assertEquals("", info.apkCertSha256, "没写这一行就是缺席，不是形状错误")
        assertEquals(emptyList<String>(), info.warnings, "老清单不能因为缺这一行而告警刷屏")
    }

    @Test
    fun `章指纹大小写都收并归一成小写`() {
        val upper = "E2FAD8C6342CA97A54826AD8BEB45B1F4F0EF0BAB5AAD7BB8AC398EFD645A181"
        val info = ready(goodManifest + "\n" + "apk-cert-sha256 " + upper)
        assertEquals(upper.lowercase(), info.apkCertSha256, "与 normalizeSha 同口径：64 位十六进制、小写")
        assertEquals(emptyList<String>(), info.warnings)
    }

    @Test
    fun `章指纹形状不对只告警并按缺席处理`() {
        val info = ready(goodManifest + "\n" + "apk-cert-sha256 not-a-sha")
        assertEquals("", info.apkCertSha256, "垃圾值当没写：它不该变成拦下载的理由，也不该被当成合法章")
        assertEquals(1, info.warnings.size, "但必须留痕：" + info.warnings)
    }

    @Test
    fun `只有两边都有值且不等才拦`() {
        val local = "e2fad8c6342ca97a54826ad8beb45b1f4f0ef0bab5aad7bb8ac398efd645a181"
        val debugCert = "76b0aa436970ce200f05b97d3c268872aa1740da3c0c863f6190f0078fab4085"
        assertTrue(
            UpdatePolicy.certBlocksInstall(local, debugCert),
            "章不同 = 装上必然是 INSTALL_FAILED_UPDATE_INCOMPATIBLE，白跑几百 MiB",
        )
        assertFalse(UpdatePolicy.certBlocksInstall(local, local), "同章不拦")
        assertFalse(UpdatePolicy.certBlocksInstall(local, ""), "清单没写 = 不拦（否则老清单全线失效）")
        assertFalse(UpdatePolicy.certBlocksInstall("", debugCert), "设备读不出自己的章 = 不拦")
        assertFalse(UpdatePolicy.certBlocksInstall(local, "垃圾值"), "垃圾值按缺席处理")
    }
    @Test
    fun 两条通道都声明时逐字段读出() {
        val info = ready(goodManifest)
        assertEquals(1, info.format)
        assertEquals("2026-10-29T08:00:00Z", info.generated)
        assertEquals("修掉读档 256 MiB 预算误判", info.note)
        val apk = info.apk
        requireNotNull(apk)
        assertEquals(6L, apk.versionCode)
        assertEquals("0.2.4", apk.versionName)
        assertEquals("eratw-0.2.4+6-abc1234-full-deadbeef.apk", apk.artifact.fileName)
        assertEquals(778451234L, apk.artifact.sizeBytes)
        // 摘要归一小写：发布侧手写大写不该被判成另一个包。
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            apk.artifact.sha256Hex,
        )
        val assets = info.assets
        requireNotNull(assets)
        assertEquals(6L, assets.version)
        assertEquals(712345678L, assets.artifact.sizeBytes)
        assertEquals(listOf("https://mirror-a.example/eratw/", "https://mirror-b.example/"), info.mirrors)
        assertEquals(emptyList<String>(), info.warnings)
    }

    @Test
    fun 只有assets通道时apk为null() {
        val info = ready(assetsOnlyManifest)
        assertNull(info.apk)
        assertEquals(7L, info.assets?.version)
    }

    @Test
    fun 未知键只记warning不拒清单() {
        // 消费侧必须让「老客户端 + 新清单」继续可用：新字段哑掉等于把玩家关在门外。
        val info = ready("$goodManifest\nfuture-field 某种以后才有的东西")
        assertTrue(info.warnings.any { it.contains("future-field") }, info.warnings.toString())
        assertEquals(6L, info.apk?.versionCode)
    }

    @Test
    fun 注释行与空行不参与解析() {
        val info = ready("# 发布人随手写的说明\n\n$goodManifest\n")
        assertEquals(6L, info.apk?.versionCode)
        assertEquals(emptyList<String>(), info.warnings)
    }

    // ---- 拒收 ----------------------------------------------------------

    @Test
    fun 高于支持的格式版本整条拒() {
        val problems = rejected("update-format 2\napk-version-code 6")
        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("本客户端只支持到 1"), problems[0])
    }

    @Test
    fun 没有格式行就不是清单() {
        // 空文本（源上 404 被网关换成空白页）与「一份没有键的文件」都必须拒，不能判成「无新版」。
        assertTrue(UpdateManifest.parse("") is ManifestRead.Rejected)
        val problems = rejected("apk-version-code 6")
        assertTrue(problems[0].contains("update-format"), problems[0])
    }

    @Test
    fun apk组缺sha就拒() {
        val text = listOf(
            "update-format 1",
            "apk-version-code 6",
            "apk-version-name 0.2.4",
            "apk-file a.apk",
            "apk-size 1024",
        ).joinToString("\n")
        val problems = rejected(text)
        assertTrue(problems[0].contains("apk 通道声明不完整"), problems[0])
        assertTrue(problems[0].contains("apk-sha256"), problems[0])
    }

    @Test
    fun assets组缺version就拒() {
        val text = listOf(
            "update-format 1",
            "assets-file a.zip",
            "assets-size 1024",
            "assets-sha256 ${"ef".repeat(32)}",
        ).joinToString("\n")
        assertTrue(rejected(text)[0].contains("assets-version"))
    }

    @Test
    fun 摘要形状不对就拒() {
        val text = listOf(
            "update-format 1",
            "assets-version 7",
            "assets-file a.zip",
            "assets-size 1024",
            "assets-sha256 deadbeef",
        ).joinToString("\n")
        assertTrue(rejected(text)[0].contains("不是 64 位十六进制"))
    }

    @Test
    fun 字节数越界就拒() {
        val text = listOf(
            "update-format 1",
            "assets-version 7",
            "assets-file a.zip",
            "assets-size ${UpdateManifest.MAX_ARTIFACT_BYTES + 1}",
            "assets-sha256 ${"ef".repeat(32)}",
        ).joinToString("\n")
        assertTrue(rejected(text)[0].contains("不在 1 …"))
    }

    @Test
    fun 键名重复就拒() {
        assertTrue(rejected("update-format 1\nupdate-format 1")[0].contains("键名重复"))
    }

    @Test
    fun 没有任何更新通道的清单按疑似截断拒收() {
        // P1-3：只剩格式/元数据/mirror 的半份清单不能读成「没有新版」把玩家永远关在旧版。
        for (text in listOf(
            "update-format 1",
            "update-format 1\ngenerated 2026-11-06T00:00:00Z",
            "update-format 1\nnote 只换了说明\nmirror https://a.example/eratw/",
        )) {
            val problems = rejected(text)
            assertTrue(
                problems.any { it.contains("没有任何更新通道") && it.contains("疑似截断") },
                "[$text] ⇒ $problems",
            )
        }
    }

    @Test
    fun 首行带UTF_8_BOM仍照常解析() {
        // P2-1：Windows 记事本存盘会带 U+FEFF，剥掉后逐字段读数与不带 BOM 完全一致。
        val info = ready("\uFEFF$goodManifest")
        assertEquals(6L, info.apk?.versionCode)
        assertEquals(6L, info.assets?.version)
        assertEquals(2, info.mirrors.size)
        assertEquals(emptyList<String>(), info.warnings, "BOM 不该变成「忽略不认识的键」之类的谎报")
    }

    @Test
    fun mirror行重复按归一后去重不拒单() {
        // P1-2：mirror 是允许多条的键，重复（含只差末尾斜杠）归一后去重即可，不该拒整单。
        val text = listOf(
            goodManifest,
            "mirror https://mirror-a.example/eratw",
            "mirror https://mirror-b.example/",
        ).joinToString("\n")
        val info = ready(text)
        assertEquals(2, info.mirrors.size, "归一后重复的 mirror 只留一条：${info.mirrors}")
        assertEquals("https://mirror-a.example/eratw/", info.mirrors[0])
        assertEquals("https://mirror-b.example/", info.mirrors[1])
        assertEquals(emptyList<String>(), info.warnings, "去重是正常路径，不该留 warning")
    }

    @Test
    fun 文件名带路径就拒() {
        val text = listOf(
            "update-format 1",
            "assets-version 7",
            "assets-file ../evil.zip",
            "assets-size 1024",
            "assets-sha256 ${"ef".repeat(32)}",
        ).joinToString("\n")
        assertTrue(rejected(text)[0].contains("不是合法文件名"))
    }

    @Test
    fun 坏镜像只丢那一条其余照用() {
        val info = ready("$goodManifest\nmirror http://明文.example/eratw/\nmirror https://x.example/a b")
        assertEquals(2, info.mirrors.size)
        assertEquals(2, info.warnings.count { it.contains("mirror") }, info.warnings.toString())
    }

    @Test
    fun mirror超过上限时截断并记warning() {
        val many = (1..12).joinToString("\n") { "mirror https://m$it.example/" }
        val info = ready("$goodManifest\n$many")
        assertEquals(UpdatePolicy.MAX_MIRRORS, info.mirrors.size)
        assertEquals("https://mirror-a.example/eratw/", info.mirrors.first())
        assertTrue(info.warnings.any { it.contains("只取前") }, info.warnings.toString())
    }

    @Test
    fun note超过上限被截断() {
        // 只留一条 note：清单里 note 重复会在 parse 阶段就被「键名重复」拒掉。
        val text = goodManifest.lines().filterNot { it.startsWith("note ") }.joinToString("\n") +
            "\nnote ${"字".repeat(600)}"
        val info = ready(text)
        assertTrue(info.note.endsWith("…"))
        assertTrue(info.note.length <= UpdateManifest.MAX_NOTE_CHARS + 1)
    }

    // ---- 差量数据包那一组可选键（CONTRACT-UPDATE §1.2）------------------

    @Test
    fun 差量三件齐全时逐字段读出() {
        val info = ready(deltaManifest)
        // 摘要归一小写这条口径对新键同样生效。
        assertEquals("ab".repeat(32), info.assets?.artifact?.sha256Hex)
        val full = info.assetsFull
        requireNotNull(full)
        assertEquals("eratw-assets-7.zip", full.fileName)
        assertEquals(712345678L, full.sizeBytes)
        assertEquals("ab".repeat(32), full.sha256Hex)
        val delta = info.assetsDelta
        requireNotNull(delta)
        assertEquals("eratw-assets-6to7.zip", delta.fileName)
        assertEquals(1048576L, delta.sizeBytes)
        assertEquals("cd".repeat(32), delta.sha256Hex)
        assertEquals(6L, info.assetsBaseVersion)
        // 新键都是已知键：一份合法的差量清单不该产生任何告警（告警刷屏会让真问题看不见）。
        assertEquals(emptyList<String>(), info.warnings)
        // assets-version 语义不变：仍然只认严格大于。
        assertTrue(UpdatePolicy.assetsUpdateNeeded(6L, info))
        assertFalse(UpdatePolicy.assetsUpdateNeeded(7L, info))
    }

    @Test
    fun 老清单没有新键时解析不失败并回落旧assets组() {
        // 已经发出去的 0.2.32 包用的就是这份没有差量键的清单；反过来新客户端读它也必须全绿。
        val info = ready(goodManifest)
        assertNull(info.assetsFull)
        assertNull(info.assetsDelta)
        assertEquals(UpdateManifest.NO_ASSETS_BASE_VERSION, info.assetsBaseVersion)
        assertEquals(-1L, info.assetsBaseVersion)
        assertEquals(emptyList<String>(), info.warnings)
        // 回落链：assets-full 缺席 ⇒ 用 assets-*。
        assertEquals(info.assets?.artifact?.fileName, UpdatePolicy.fullDataArtifact(info)?.fileName)
        // 基线 -1 时差量永远不合格，即便盘上版本恰好是 -1 也撞不出来。
        assertFalse(UpdatePolicy.deltaUsable(info.assetsDelta, info.assetsBaseVersion, -1L))
    }

    @Test
    fun 差量三件缺一即整组忽略并回落全量() {
        for (missing in listOf("assets-delta-file", "assets-delta-size", "assets-delta-sha256")) {
            val info = ready(withoutKeys(deltaManifest, missing))
            assertNull(info.assetsDelta, "缺 $missing 时差量必须当成缺席")
            assertNotNull(info.assetsFull, "回落用的全量件不应受差量缺件影响")
            assertTrue(
                info.warnings.any { it.contains("assets-delta") && it.contains("声明不完整") },
                "缺 $missing 应当告警：${info.warnings}",
            )
            // 判定层的落点：基线本来等于盘上版本，也必须落到全量件。
            assertEquals(info.assetsFull?.fileName, UpdatePolicy.dataArtifactFor(info, 6L)?.fileName)
        }
    }

    @Test
    fun 全量三件缺一即回落到现有assets组() {
        for (missing in listOf("assets-full-file", "assets-full-size", "assets-full-sha256")) {
            val info = ready(withoutKeys(deltaManifest, missing))
            assertNull(info.assetsFull, "缺 $missing 时 assets-full 必须当成缺席")
            assertNotNull(info.assetsDelta, "全量缺件不该连带废掉差量")
            assertTrue(
                info.warnings.any { it.contains("assets-full") && it.contains("声明不完整") },
                "缺 $missing 应当告警：${info.warnings}",
            )
            assertEquals(info.assets?.artifact?.fileName, UpdatePolicy.fullDataArtifact(info)?.fileName)
        }
    }

    @Test
    fun 可选组形状不对只告警不拒清单() {
        // 摘要坏掉：老口径（必填组直接拒）在这里会把整条通道哑掉，代价远超「回落全量」。
        val badSha = ready(deltaManifest.replace("assets-delta-sha256 ${"cd".repeat(32)}", "assets-delta-sha256 deadbeef"))
        assertNull(badSha.assetsDelta)
        assertTrue(
            badSha.warnings.any { it.contains("assets-delta-sha256") && it.contains("不是 64 位十六进制") },
            badSha.warnings.toString(),
        )
        // 字节数越界。
        val badSize = ready(deltaManifest.replace("assets-delta-size 1048576", "assets-delta-size 0"))
        assertNull(badSize.assetsDelta)
        assertTrue(badSize.warnings.any { it.contains("assets-delta-size") }, badSize.warnings.toString())
        // 文件名带路径。
        val badName = ready(deltaManifest.replace("assets-delta-file eratw-assets-6to7.zip", "assets-delta-file ../evil.zip"))
        assertNull(badName.assetsDelta)
        assertTrue(badName.warnings.any { it.contains("不是合法文件名") }, badName.warnings.toString())
        // 回落后的件仍然可用：数据通道没被一个坏差量键废掉。
        assertNotNull(badName.assetsFull)
        assertEquals(badName.assetsFull?.fileName, UpdatePolicy.dataArtifactFor(badName, 6L)?.fileName)
    }

    @Test
    fun 基线版本不是正整数时按无基线处理差量不启用() {
        for (bad in listOf("abc", "0", "-6")) {
            val info = ready(deltaManifest.replace("assets-base-version 6", "assets-base-version $bad"))
            assertEquals(UpdateManifest.NO_ASSETS_BASE_VERSION, info.assetsBaseVersion, "坏值 $bad")
            assertTrue(
                info.warnings.any { it.contains("assets-base-version") && it.contains("差量不启用") },
                "坏值 $bad 应当告警：${info.warnings}",
            )
            // 三件齐全也不给用：宁可下全量，也不缝一份内容对不上的语料。
            assertNotNull(info.assetsDelta)
            assertFalse(UpdatePolicy.deltaUsable(info.assetsDelta, info.assetsBaseVersion, 6L))
        }
    }

    @Test
    fun 新键都是已知键而真正的未知键仍只告警() {
        val info = ready("$deltaManifest\napk-zstd-file eratw-0.2.5.zst\nfuture-field 以后的东西")
        assertEquals(2, info.warnings.count { it.contains("忽略不认识的键") }, info.warnings.toString())
        assertTrue(info.warnings.none { it.contains("assets-delta") }, info.warnings.toString())
        assertTrue(info.warnings.none { it.contains("assets-full") }, info.warnings.toString())
        assertTrue(info.warnings.none { it.contains("assets-base-version") }, info.warnings.toString())
        assertEquals(1048576L, info.assetsDelta?.sizeBytes)
    }

    @Test
    fun 可选组键重复整组忽略并告警不拒清单() {
        // P1-2：可选组里任何一键重复 ⇒ 整组当缺席 + warning，整单仍可用（回落方向是多下点字节）。
        val baseDup = ready("$deltaManifest\nassets-base-version 7")
        assertEquals(UpdateManifest.NO_ASSETS_BASE_VERSION, baseDup.assetsBaseVersion, "重复的基线整组当缺席")
        assertTrue(
            baseDup.warnings.any { it.contains("assets-base-version") && it.contains("整组忽略") },
            baseDup.warnings.toString(),
        )
        assertNotNull(baseDup.assetsDelta, "基线组失效不该连带废掉差量件本身")

        val deltaDup = ready("$deltaManifest\nassets-delta-size 2048")
        assertNull(deltaDup.assetsDelta, "重复的差量三件套整组当缺席")
        assertNotNull(deltaDup.assetsFull, "全量件不受影响")
        assertTrue(deltaDup.warnings.any { it.contains("assets-delta") && it.contains("整组忽略") }, deltaDup.warnings.toString())

        val fullDup = ready("$deltaManifest\nassets-full-sha256 ${"ee".repeat(32)}")
        assertNull(fullDup.assetsFull, "重复的全量三件套整组当缺席")
        assertNotNull(fullDup.assetsDelta, "差量件不受影响")
        assertTrue(fullDup.warnings.any { it.contains("assets-full") && it.contains("整组忽略") })
    }

    @Test
    fun 单键可选组重复只让那一项回落并告警() {
        val certDup = ready("$goodManifest\napk-cert-sha256 ${"ef".repeat(32)}\napk-cert-sha256 ${"ef".repeat(32)}")
        assertEquals("", certDup.apkCertSha256, "章指纹重复按未声明处理")
        assertTrue(
            certDup.warnings.any { it.contains("apk-cert-sha256") && it.contains("整组忽略") },
            certDup.warnings.toString(),
        )

        val rolloutDup = ready("$goodManifest\nrollout-percentage 10\nrollout-percentage 20")
        assertEquals(UpdatePolicy.FULL_ROLLOUT_PERCENTAGE, rolloutDup.rolloutPercentage, "灰度重复按 100 全量处理")
        assertTrue(rolloutDup.warnings.any { it.contains("rollout-percentage") && it.contains("整组忽略") })

        val minDup = ready("$goodManifest\nmin-supported-version 6\nmin-supported-version 7")
        assertEquals(UpdateManifest.NO_MIN_SUPPORTED_VERSION, minDup.minSupportedVersion, "最低档重复按无强制档处理")
        assertTrue(minDup.warnings.any { it.contains("min-supported-version") && it.contains("整组忽略") })

        // 签名三行同组：任一重复 ⇒ 整组当缺席（签名行是否存在只看原文，验签层另有形状判式）。
        val sigDup = ready("$goodManifest\nsig-alg rsa-sha256\nsig-alg rsa-sha256")
        assertEquals("", sigDup.sigAlg, "签名声明重复整组当缺席")
        assertTrue(sigDup.warnings.any { it.contains("签名声明") && it.contains("整组忽略") }, sigDup.warnings.toString())
    }

    @Test
    fun 必填键重复仍然整份拒() {
        // 必填组出现两个值时猜哪个都可能装错版本/下错文件，宁可整份拒（P1-2 的第一档）。
        assertTrue(rejected("$goodManifest\napk-file eratw-other.apk").any { it.contains("键名重复") && it.contains("apk-file") })
        assertTrue(rejected("$goodManifest\nassets-version 7").any { it.contains("键名重复") && it.contains("assets-version") })
        assertTrue(rejected("update-format 1\nupdate-format 1").any { it.contains("键名重复") })
    }

    @Test
    fun 只有可选组齐全却没有assets组时数据版本判不出来() {
        // 版本载体是 assets-version（挂在 assets-* 组上）：老包只认它，所以发布侧必须继续写。
        val text = withoutKeys(deltaManifest, "assets-file", "assets-size", "assets-sha256")
        // assets-* 只剩 assets-version ⇒ 必填组缺半组 ⇒ 拒收，而不是静默当成「没有数据通道」。
        assertTrue(rejected(text).any { it.contains("assets 通道声明不完整") })
    }

    // ---- 块级差量那一组键（P2-13，U8b 把读数并进 UpdateInfo 的那一拍） --------------

    /** [deltaManifest] 再加块级三件套：线上「文件级 + 块级同发」的真实形状。 */
    private val blockManifest = (
        deltaManifest.lines() + listOf(
            "assets-blockdelta-file eratw-assets-blockdelta-7.zip",
            "assets-blockdelta-size 104857",
            "assets-blockdelta-sha256 ${"ef".repeat(32)}",
        )
        ).joinToString("\n")

    @Test
    fun `块级三件套齐全时并进 UpdateInfo 且零告警`() {
        val info = ready(blockManifest)
        val block = info.assetsBlockDelta
        requireNotNull(block)
        assertEquals("eratw-assets-blockdelta-7.zip", block.fileName)
        assertEquals(104857L, block.sizeBytes)
        assertEquals("ef".repeat(32), block.sha256Hex)
        // 与文件级差量共用同一个基线：两条通道各自一套基线迟早出现「块级按 6 拼、文件级按 5 删」。
        assertEquals(6L, info.assetsBaseVersion)
        assertEquals(emptyList<String>(), info.warnings, "合法的块级清单不该刷告警：${info.warnings}")
        // 读数直接喂给判定层就是块级档（app 侧不再自己比 assets-base-version）。
        assertEquals(block.fileName, UpdatePolicy.dataArtifactFor(info, block, 6L)?.fileName)
        assertEquals(DataArtifactKind.BLOCK_DELTA, UpdatePolicy.dataArtifactKind(info, block, 6L))
    }

    @Test
    fun `老清单没有块级键时读数与三参判定逐字同结论`() {
        // 这条钉的是「块级新键缺席 ⇒ 与接线前一字不变」：已经发出去的清单一个都没带这三行。
        val subjects = listOf(goodManifest, assetsOnlyManifest, deltaManifest)
        for (text in subjects) {
            val info = ready(text)
            assertNull(info.assetsBlockDelta, "没有这三行就是没有块级档")
            assertTrue(
                info.warnings.none { it.contains("assets-blockdelta") },
                "缺席不是错误，不该留下任何块级相关的话：${info.warnings}",
            )
            for (installed in listOf(0L, 5L, 6L, 7L, 8L)) {
                val before = UpdatePolicy.dataArtifactFor(info, installed)
                val after = UpdatePolicy.dataArtifactFor(info, info.assetsBlockDelta, installed)
                assertEquals(before?.fileName, after?.fileName, "${info.assets?.version} @ $installed")
                assertEquals(
                    UpdatePolicy.dataArtifactKind(info, null, installed),
                    UpdatePolicy.dataArtifactKind(info, info.assetsBlockDelta, installed),
                    "kind 必须与选中的件同向 @ $installed",
                )
            }
        }
    }

    @Test
    fun `块级缺半组只告警并整组当缺席`() {
        for (missing in listOf(BlockDeltaManifest.KEY_FILE, BlockDeltaManifest.KEY_SIZE, BlockDeltaManifest.KEY_SHA)) {
            val info = ready(withoutKeys(blockManifest, missing))
            assertNull(info.assetsBlockDelta, "缺 $missing 时块级档必须当成不存在")
            assertTrue(
                info.warnings.any { it.contains(missing) && it.contains("块级差量不启用") },
                "缺 $missing 应当告警并点名回落方向：${info.warnings}",
            )
            // 回落落点是**文件级差量**，不是全量：块级坏了不该把已经够格的文件级档一起废掉。
            assertEquals("eratw-assets-6to7.zip", UpdatePolicy.dataArtifactFor(info, info.assetsBlockDelta, 6L)?.fileName)
            assertEquals(DataArtifactKind.FILE_DELTA, UpdatePolicy.dataArtifactKind(info, info.assetsBlockDelta, 6L))
        }
    }

    @Test
    fun `块级形状不对只告警不拒清单`() {
        val badSha = ready(blockManifest.replace("assets-blockdelta-sha256 ${"ef".repeat(32)}", "assets-blockdelta-sha256 deadbeef"))
        assertNull(badSha.assetsBlockDelta)
        assertTrue(badSha.warnings.any { it.contains("assets-blockdelta-sha256") && it.contains("不是 64 位十六进制") }, badSha.warnings.toString())
        val badSize = ready(blockManifest.replace("assets-blockdelta-size 104857", "assets-blockdelta-size 0"))
        assertNull(badSize.assetsBlockDelta)
        assertTrue(badSize.warnings.any { it.contains("assets-blockdelta-size") }, badSize.warnings.toString())
        val badName = ready(blockManifest.replace("assets-blockdelta-file eratw-assets-blockdelta-7.zip", "assets-blockdelta-file ../evil.zip"))
        assertNull(badName.assetsBlockDelta)
        assertTrue(badName.warnings.any { it.contains("不是合法文件名") }, badName.warnings.toString())
        // 三行写坏任何一行都只关掉块级档：数据通道仍然成立。
        assertNotNull(badName.assetsFull)
        assertNotNull(badName.assetsDelta)
    }

    @Test
    fun `块级三行是已知键而真正的未知键仍只告警`() {
        val info = ready("$blockManifest\nassets-something-new 1")
        assertEquals(1, info.warnings.count { it.contains("忽略不认识的键") }, info.warnings.toString())
        assertTrue(info.warnings.none { it.contains("assets-blockdelta") }, info.warnings.toString())
        assertNotNull(info.assetsBlockDelta)
    }

    @Test
    fun `块级新键重复整组忽略并告警`() {
        // P1-2：块级组也是可选组，重复只该关掉这一档，不是整份拒。
        val info = ready("$blockManifest\nassets-blockdelta-size 2048")
        assertNull(info.assetsBlockDelta, "重复的块级三件套整组当缺席")
        assertTrue(
            info.warnings.any { it.contains("assets-blockdelta") && it.contains("整组忽略") },
            info.warnings.toString(),
        )
        // 回落落点仍是文件级差量：块级档失效不该把数据通道一起废掉。
        assertEquals("eratw-assets-6to7.zip", UpdatePolicy.dataArtifactFor(info, info.assetsBlockDelta, 6L)?.fileName)
    }

    // ---- 判定 ----------------------------------------------------------

    @Test
    fun 版本比较只认严格大于() {
        val info = ready(goodManifest)
        assertTrue(UpdatePolicy.apkUpdateNeeded(5L, info))
        assertFalse(UpdatePolicy.apkUpdateNeeded(6L, info))
        assertFalse(UpdatePolicy.apkUpdateNeeded(7L, info))
        assertTrue(UpdatePolicy.assetsUpdateNeeded(5L, info))
        assertFalse(UpdatePolicy.assetsUpdateNeeded(6L, info))
        // 玩家退回旧镜像时清单版本比本地低 ⇒ 绝不降级盘上数据。
        assertFalse(UpdatePolicy.assetsUpdateNeeded(9L, info))
        // 只有一条通道时另一侧恒不触发；两条都没有的清单现在由 parse 直接拒（P1-3）。
        val assetsOnly = ready(assetsOnlyManifest)
        assertFalse(UpdatePolicy.apkUpdateNeeded(5L, assetsOnly))
        assertFalse(UpdatePolicy.apkUpdateNeeded(0L, assetsOnly))
    }

    @Test
    fun 检查冷却认间隔也认时钟回拨() {
        val interval = UpdatePolicy.CHECK_INTERVAL_MILLIS
        assertTrue(UpdatePolicy.isCheckDue(0L, 1_000L))
        assertFalse(UpdatePolicy.isCheckDue(1_000L, 1_000L + interval - 1L))
        assertTrue(UpdatePolicy.isCheckDue(1_000L, 1_000L + interval))
        assertTrue(UpdatePolicy.isCheckDue(lastCheckMillis = 10_000L, nowMillis = 500L))
    }

    @Test
    fun 空间闸要留解包与安装的余量() {
        assertTrue(UpdatePolicy.hasEnoughSpace(UpdatePolicy.MIN_FREE_HEADROOM_BYTES + 1_000L, 1_000L))
        assertFalse(UpdatePolicy.hasEnoughSpace(1_000L, 1_000L))
        assertFalse(UpdatePolicy.hasEnoughSpace(0L, 1L))
    }

    @Test
    fun 地址归一拒明文与查询串() {
        assertEquals("https://a.example/eratw/", UpdatePolicy.normalizeMirror("  https://a.example/eratw  "))
        assertEquals("https://a.example/", UpdatePolicy.normalizeMirror("https://a.example/"))
        assertNull(UpdatePolicy.normalizeMirror("http://a.example/"))
        assertNull(UpdatePolicy.normalizeMirror("https://a.example/?token=1"))
        assertNull(UpdatePolicy.normalizeMirror("https://a.example/#frag"))
        assertNull(UpdatePolicy.normalizeMirror("https://a.example/带空格 的路径"))
        assertNull(UpdatePolicy.normalizeMirror("https://no-dot-host"))
        assertNull(UpdatePolicy.normalizeMirror("https://"))
        assertNull(UpdatePolicy.normalizeMirror(""))
        assertNull(UpdatePolicy.normalizeMirror(null))
    }

    @Test
    fun 地址归一收紧主机段_拒userinfo反斜杠与非法字符() {
        // userinfo：凭据会跟着拼接后的 URL 走，host 也可能被解析器读成 userinfo 之后的部分
        assertNull(UpdatePolicy.normalizeMirror("https://user@a.example/"))
        assertNull(UpdatePolicy.normalizeMirror("https://user:pass@a.example/x"))
        // 反斜杠：不同解析器当路径分隔符/普通字符，拼接后语义漂移
        assertNull(UpdatePolicy.normalizeMirror("https://a.example\\x/"))
        assertNull(UpdatePolicy.normalizeMirror("https://a.example/x\\y"))
        // 主机只许 [a-z0-9.-]：下划线 / 百分号 / 方括号 IPv6 / 中文都拒
        assertNull(UpdatePolicy.normalizeMirror("https://a_b.example/"))
        assertNull(UpdatePolicy.normalizeMirror("https://a%2f.example/"))
        assertNull(UpdatePolicy.normalizeMirror("https://[::1]/"))
        assertNull(UpdatePolicy.normalizeMirror("https://例子.example/"))
        // 端口必须是纯数字；空端口 / 非数字 / 多冒号都拒（合法端口保留）
        assertEquals("https://a.example:8443/x/", UpdatePolicy.normalizeMirror("https://a.example:8443/x"))
        assertNull(UpdatePolicy.normalizeMirror("https://a.example:abc/"))
        assertNull(UpdatePolicy.normalizeMirror("https://a.example:/"))
        assertNull(UpdatePolicy.normalizeMirror("https://a.example:80:90/"))
        // 控制字符（含 DEL）仍拒
        assertNull(UpdatePolicy.normalizeMirror("https://a.example/\u0001"))
        assertNull(UpdatePolicy.normalizeMirror("https://a.example/\u007F"))
        // 超长仍拒（既有 ≤512 口径）
        assertNull(UpdatePolicy.normalizeMirror("https://a.example/" + "x".repeat(UpdatePolicy.MAX_MIRROR_CHARS)))
    }

    @Test
    fun 地址归一主机小写去尾点_路径大小写原样() {
        assertEquals("https://a.example/Path/Case/", UpdatePolicy.normalizeMirror("https://A.Example./Path/Case"))
        assertEquals("https://a.example/", UpdatePolicy.normalizeMirror("https://A.EXAMPLE/"))
        assertEquals("https://a.example:8443/x/", UpdatePolicy.normalizeMirror("https://A.Example:8443/x"))
        // 主机大小写归一后，同一源的不同写法在候选序里折叠成一条（不再重复下发）
        assertEquals(
            listOf("https://a.example/"),
            UpdatePolicy.mirrorOrder(builtIn = listOf("https://A.Example/", "https://a.example/")),
        )
        // 但路径是大小写敏感的：两条只有路径大小写不同的源**不**折叠
        assertEquals(
            listOf("https://a.example/X/", "https://a.example/x/"),
            UpdatePolicy.mirrorOrder(builtIn = listOf("https://a.example/X/", "https://a.example/x/")),
        )
    }

    @Test
    fun 镜像候选序内置最前且清单不能插队() {
        val order = UpdatePolicy.mirrorOrder(
            builtIn = listOf("https://built.in/x/"),
            persisted = listOf("https://cached.in/y"),
            declared = listOf("https://declared.in/z/", "https://built.in/x/"),
        )
        assertEquals(
            listOf("https://built.in/x/", "https://cached.in/y/", "https://declared.in/z/"),
            order,
        )
    }

    @Test
    fun 非法镜像在候选序里被丢掉而不是挡路() {
        val order = UpdatePolicy.mirrorOrder(
            builtIn = listOf("http://明文.in/", "", "https://good.in/"),
        )
        assertEquals(listOf("https://good.in/"), order)
    }

    @Test
    fun 下载地址由基址与文件名拼接() {
        assertEquals(
            "https://a.example/eratw/eratw-assets-6.zip",
            UpdatePolicy.downloadUrl("https://a.example/eratw", "eratw-assets-6.zip"),
        )
    }

    @Test
    fun 文件名allowlist挡住控制字符与点点() {
        assertTrue(UpdatePolicy.isArtifactFileName("eratw-0.2.4+6-abc-full-deadbeef.apk"))
        assertTrue(UpdatePolicy.isArtifactFileName("eratw-assets-6.zip"))
        assertFalse(UpdatePolicy.isArtifactFileName(""))
        assertFalse(UpdatePolicy.isArtifactFileName(".."))
        assertFalse(UpdatePolicy.isArtifactFileName("a/../b.apk"))
        assertFalse(UpdatePolicy.isArtifactFileName("a\\b.apk"))
        assertFalse(UpdatePolicy.isArtifactFileName("a b.apk"))
        assertFalse(UpdatePolicy.isArtifactFileName("a\tb.apk"))
        assertFalse(UpdatePolicy.isArtifactFileName("-以短横开头.apk"))
        assertFalse(UpdatePolicy.isArtifactFileName("带中文.zip"))
    }

    @Test
    fun 摘要归一() {
        assertEquals("ab".repeat(32), UpdatePolicy.normalizeSha("AB".repeat(32)))
        assertNull(UpdatePolicy.normalizeSha("ab".repeat(31)))
        assertNull(UpdatePolicy.normalizeSha("gz".repeat(32)))
        assertNull(UpdatePolicy.normalizeSha(null))
    }

    @Test
    fun 计量网络不自动下载() {
        assertTrue(UpdatePolicy.autoDownloadAllowed(isUnmetered = true))
        assertFalse(UpdatePolicy.autoDownloadAllowed(isUnmetered = false))
    }

    @Test
    fun 体积读数向上取整且不报假精确() {
        assertEquals("1 KiB", UpdatePolicy.sizeLabel(1L))
        assertEquals("1 KiB", UpdatePolicy.sizeLabel(1024L))
        assertEquals("1 MiB", UpdatePolicy.sizeLabel(1024L * 1024L))
        assertEquals("2 MiB", UpdatePolicy.sizeLabel(1024L * 1024L + 1L))
        assertEquals("743 MiB", UpdatePolicy.sizeLabel(778_451_234L))
        assertEquals("未知大小", UpdatePolicy.sizeLabel(-1L))
    }

    // ---- 差量/续传/重试/抑制判定（CONTRACT-UPDATE §3 那五个纯函数）------

    private val deltaArtifact = Artifact("eratw-assets-6to7.zip", 1_048_576L, "cd".repeat(32))
    private val fullArtifact = Artifact("eratw-assets-7.zip", 712_345_678L, "ab".repeat(32))

    @Test
    fun 差量可用性只认基线恰好等于盘上版本() {
        assertTrue(UpdatePolicy.deltaUsable(deltaArtifact, 6L, 6L))
        // 盘上比基线新：差量里的删除表可能删掉后来才要用的东西。
        assertFalse(UpdatePolicy.deltaUsable(deltaArtifact, 6L, 7L))
        // 盘上比基线旧：中间几版的变更整段缺失，且不会报错，只会内容不对。
        assertFalse(UpdatePolicy.deltaUsable(deltaArtifact, 6L, 5L))
        // 盘上从没装过数据 ⇒ 只能拿全量。
        assertFalse(UpdatePolicy.deltaUsable(deltaArtifact, 6L, UpdatePolicy.NO_ASSETS_INSTALLED))
        // 三件不齐 ⇒ 无资格谈差量。
        assertFalse(UpdatePolicy.deltaUsable(null, 6L, 6L))
        // 无基线（-1）与非法基线（0）⇒ 恒假，撞不出假阳性。
        assertFalse(UpdatePolicy.deltaUsable(deltaArtifact, UpdateManifest.NO_ASSETS_BASE_VERSION, -1L))
        assertFalse(UpdatePolicy.deltaUsable(deltaArtifact, 0L, 0L))
    }

    @Test
    fun 数据件选择差量可用走差量否则全量() {
        val info = ready(deltaManifest)
        // 差量可用 ⇒ 1 MiB 而不是 ≈680 MiB。
        assertEquals(deltaArtifact.fileName, UpdatePolicy.dataArtifactFor(info, 6L)?.fileName)
        // 基线不等 ⇒ 回落全量（新键），而不是不动盘。
        assertEquals(fullArtifact.fileName, UpdatePolicy.dataArtifactFor(info, 7L)?.fileName)
        // 老清单：全量落到 assets-* 组。
        val legacy = ready(goodManifest)
        assertEquals(legacy.assets?.artifact?.fileName, UpdatePolicy.dataArtifactFor(legacy, 6L)?.fileName)
        // 两者都没有 ⇒ 数据通道不成立；这种清单在 parse 就被拒（P1-3），根本进不到选件层。
        assertTrue(UpdateManifest.parse("update-format 1") is ManifestRead.Rejected)
    }

    @Test
    fun 续传偏移认年龄也认声明字节数() {
        val maxAge = UpdatePolicy.PART_MAX_AGE_MILLIS
        // ① 没有 .part（0 字节）：从头下。
        assertEquals(0L, UpdatePolicy.resumeOffset(0L, 1000L, 0L))
        assertEquals(0L, UpdatePolicy.resumeOffset(-5L, 1000L, 0L))
        // ② 合法：偏移就是现有字节数。
        assertEquals(500L, UpdatePolicy.resumeOffset(500L, 1000L, 60_000L))
        // 字节数已齐：仍然给到末尾，由调用方去核 sha（核不过才重来）。
        assertEquals(1000L, UpdatePolicy.resumeOffset(1000L, 1000L, 0L))
        // ③ 超期：整段重来；恰好等于上限仍算新鲜。
        assertEquals(0L, UpdatePolicy.resumeOffset(500L, 1000L, maxAge + 1L))
        assertEquals(500L, UpdatePolicy.resumeOffset(500L, 1000L, maxAge))
        // 时钟回拨导致的负年龄按新鲜处理：正确性由 sha 兜底，不为抖动白跑几百 MiB。
        assertEquals(500L, UpdatePolicy.resumeOffset(500L, 1000L, -1_000L))
        // ④ 比声明还长：只可能是旧产物残留，拼上新字节必得一个 sha 错的缝合文件。
        assertEquals(0L, UpdatePolicy.resumeOffset(1001L, 1000L, 0L))
        // 声明本身不可信（≤0）时没有「续到哪儿算完」的参照，一律重来。
        assertEquals(0L, UpdatePolicy.resumeOffset(500L, 0L, 0L))
        // 上限可由调用方收窄（测试用的镜像/短任务）。
        assertEquals(0L, UpdatePolicy.resumeOffset(500L, 1000L, 10_000L, maxAgeMillis = 5_000L))
    }

    @Test
    fun 重试档位逐档放大走完就换镜像或收手() {
        val tiers = UpdatePolicy.RETRY_BACKOFF_MILLIS
        assertTrue(tiers.isNotEmpty())
        for (i in tiers.indices) {
            assertEquals(tiers[i], UpdatePolicy.retryBackoffMillis(i))
            assertTrue(tiers[i] > 0L, "档位 $i 必须是正数等待")
            if (i > 0) assertTrue(tiers[i] > tiers[i - 1], "档位必须逐档放大")
        }
        assertEquals(1_000L, tiers.first())
        assertEquals(30_000L, tiers.last())
        // 超出档数 = null：继续在一条死源上烧玩家流量没有意义。
        assertNull(UpdatePolicy.retryBackoffMillis(tiers.size))
        assertNull(UpdatePolicy.retryBackoffMillis(tiers.size + 3))
        assertNull(UpdatePolicy.retryBackoffMillis(Int.MAX_VALUE))
        assertNull(UpdatePolicy.retryBackoffMillis(-1))
    }

    @Test
    fun 跳过此版本只压同一个版本() {
        assertTrue(UpdatePolicy.isVersionSuppressed(6L, 6L))
        // 新版本照样提醒：抑制所有后续版本 = 致命 bug 的修复版永远到不了玩家眼前。
        assertFalse(UpdatePolicy.isVersionSuppressed(7L, 6L))
        assertFalse(UpdatePolicy.isVersionSuppressed(6L, 7L))
        assertFalse(UpdatePolicy.isVersionSuppressed(6L, 5L))
        // 没写过跳过（0 / 缺省 -1）不抑制。
        assertFalse(UpdatePolicy.isVersionSuppressed(6L, 0L))
        assertFalse(UpdatePolicy.isVersionSuppressed(0L, 0L))
        assertFalse(UpdatePolicy.isVersionSuppressed(6L, -1L))
    }

    @Test
    fun 配置禁止更新面时短路() {
        // 原版 UPDATECHECK 的第一件事就是查它，为真直接 RESULT=4 返回，不发任何请求。
        // 证据：reference/emuera-cs/MinorShift.Emuera.GameProc.Function/FunctionIdentifier.cs:2767-2771
        assertTrue(UpdatePolicy.forbiddenByConfig(true))
        assertFalse(UpdatePolicy.forbiddenByConfig(false))
    }

    @Test
    fun PART年龄上限就是七天() {
        assertEquals(7L * 24L * 60L * 60L * 1000L, UpdatePolicy.PART_MAX_AGE_MILLIS)
    }
}
