package com.eratw.port.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * U4 upd-rollout 定向单测：灰度发布（P1-5）、强制档优先级、镜像健康降位（P1-7）
 * 与清单新键 rollout-percentage 的消费侧口径。
 *
 * 全是纯 JVM 判定：core 不碰网络、不碰文件系统、不碰 Android API、不取随机数。
 * bucket 与镜像健康的持久化在 app 侧（UiPrefsRolloutTest），这里只钉判定矩阵。
 */
class UpdateRolloutTest {

    // ---- 灰度分桶 ------------------------------------------------------

    @Test
    fun 灰度百分比与bucket边界逐一对上() {
        for (bucket in 0..99) {
            assertFalse(UpdatePolicy.rolloutSelected(0, bucket), "0% 灰度谁都不推：bucket $bucket")
            assertTrue(UpdatePolicy.rolloutSelected(100, bucket), "100% 全量：bucket $bucket")
        }
        for (bucket in 0..49) assertTrue(UpdatePolicy.rolloutSelected(50, bucket), "50%：bucket $bucket")
        for (bucket in 50..99) assertFalse(UpdatePolicy.rolloutSelected(50, bucket), "50%：bucket $bucket")
        assertTrue(UpdatePolicy.rolloutSelected(1, 0))
        assertFalse(UpdatePolicy.rolloutSelected(1, 1))
        assertTrue(UpdatePolicy.rolloutSelected(99, 98))
        assertFalse(UpdatePolicy.rolloutSelected(99, 99))
        assertEquals(100, UpdatePolicy.ROLLOUT_BUCKETS)
        assertEquals(100, UpdatePolicy.FULL_ROLLOUT_PERCENTAGE)
    }

    @Test
    fun 灰度坏值往不误挡方向回落() {
        for (bad in listOf(-1, 101, -999, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertTrue(UpdatePolicy.rolloutSelected(bad, 99), "坏百分比 $bad 按 100 全量")
        }
        for (bad in listOf(-1, 100, 999, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertTrue(UpdatePolicy.rolloutSelected(50, bad), "坏 bucket $bad 归 0 号桶，50% 必中")
        }
        // 0% 是发布者明确的「谁都不推」：坏 bucket 也不放行
        assertFalse(UpdatePolicy.rolloutSelected(0, -1))
    }

    // ---- 强制档优先 ----------------------------------------------------

    @Test
    fun 强制档命中判定只认低于最低支持版本() {
        assertTrue(UpdatePolicy.isForceUpdateRequired(5L, 6L))
        assertFalse(UpdatePolicy.isForceUpdateRequired(6L, 6L), "相等不强制")
        assertFalse(UpdatePolicy.isForceUpdateRequired(7L, 6L), "比最低还新不强制")
        assertFalse(UpdatePolicy.isForceUpdateRequired(5L, 0L), "未声明不强制")
        assertFalse(UpdatePolicy.isForceUpdateRequired(5L, -1L), "坏值不强制")
        // 读不出本机版本（某些 ROM 的 packageInfo 形状）宁可不拦：U4 曾并存一个少了这道闸的同形
        // 函数，于是这类机器上「清单写了 min-supported」= 无条件命中强更，与 U1 的口径直接相悖。
        assertFalse(UpdatePolicy.isForceUpdateRequired(0L, 1L), "本机版本读不出不强制")
        assertFalse(UpdatePolicy.isForceUpdateRequired(-3L, 6L), "负读数不强制")
    }

    @Test
    fun 强制档命中时灰度不得挡() {
        // 0% 灰度 + 强制档命中：100 个桶一个都不许被挡
        for (bucket in 0..99) {
            assertTrue(
                UpdatePolicy.updateSelected(0, bucket, currentVersionCode = 5L, minSupportedVersion = 6L),
                "致命修复必须送达：bucket $bucket 不该被 0% 灰度挡住",
            )
        }
        // 10% 灰度 + 强制档命中：本来不中的桶也必须放行
        for (bucket in 10..99) {
            assertTrue(UpdatePolicy.updateSelected(10, bucket, 5L, 6L), "bucket $bucket")
        }
        // 未命中强制档（版本已达标 / 字段缺席 / 字段坏值）：完全按灰度
        for (bucket in 0..99) {
            assertEquals(bucket < 10, UpdatePolicy.updateSelected(10, bucket, 6L, 6L), "bucket $bucket")
            assertEquals(bucket < 10, UpdatePolicy.updateSelected(10, bucket, 6L, 0L), "bucket $bucket")
            assertEquals(bucket < 10, UpdatePolicy.updateSelected(10, bucket, 6L, -1L), "bucket $bucket")
        }
        // 读不出本机版本时 updateSelected 必须退化成纯灰度：这条把「两份强更判定各说各话」钉死，
        // 组合口只能有一个强更判据源（isForceUpdateRequired）。
        for (bucket in 0..99) {
            assertEquals(
                bucket < 10,
                UpdatePolicy.updateSelected(10, bucket, currentVersionCode = 0L, minSupportedVersion = 6L),
                "本机版本读不出：bucket $bucket 只该按灰度",
            )
        }
    }

    // ---- 健康度判据（一轮一条源只记一次） --------------------------------

    @Test
    fun 验签判死算失联而解析被拒与老格式未签名都不算() {
        // 只有 INVALID 降位：反复被换/签名行被改坏的源要降下去；
        // 字节取回来了但清单内容坏了（原话已进 problems）与没签名的旧格式都不算失联。
        assertFalse(UpdatePolicy.mirrorFetchHealthy(ManifestSignature.Verdict.INVALID))
        assertTrue(UpdatePolicy.mirrorFetchHealthy(ManifestSignature.Verdict.VERIFIED))
        assertTrue(UpdatePolicy.mirrorFetchHealthy(ManifestSignature.Verdict.UNSIGNED))
    }

    // ---- 镜像健康降位 --------------------------------------------------

    private val builtIn = listOf(
        "https://a.example/eratw/",
        "https://b.example/eratw/",
        "https://c.example/eratw/",
    )

    // 真实量级的毫秒时间戳：减去 7 天（MIRROR_STALE_AFTER_MILLIS）后仍是正数，
    // 才不会被 isMirrorUnhealthy 的「lastSuccess <= 0 = 从没成功过」分支吞掉。
    private val now = 1_700_000_000_000L

    /** 造一条「a 源是否降位」的读数：降位后 b 会排到第一位。 */
    private fun demoted(
        state: MirrorHealth,
        failureThreshold: Int = UpdatePolicy.MIRROR_DEMOTE_FAILURES,
        staleMillis: Long = UpdatePolicy.MIRROR_STALE_AFTER_MILLIS,
    ): Boolean {
        val order = UpdatePolicy.mirrorOrderWithHealth(
            builtIn = listOf("https://a.example/eratw/", "https://b.example/eratw/"),
            health = mapOf("https://a.example/eratw/" to state),
            nowMillis = now,
            failureThreshold = failureThreshold,
            staleMillis = staleMillis,
        )
        return order.first() != "https://a.example/eratw/"
    }

    @Test
    fun 全健康时与mirrorOrder逐字同序() {
        assertEquals(
            UpdatePolicy.mirrorOrder(builtIn = builtIn),
            UpdatePolicy.mirrorOrderWithHealth(builtIn = builtIn, health = emptyMap(), nowMillis = now),
        )
    }

    @Test
    fun 失联源降位但不删除且两组内部都保序() {
        val health = mapOf(
            "https://a.example/eratw/" to MirrorHealth(consecutiveFailures = 3, lastSuccessMillis = 1L),
            "https://c.example/eratw/" to MirrorHealth(
                consecutiveFailures = 0,
                lastSuccessMillis = now - UpdatePolicy.MIRROR_STALE_AFTER_MILLIS - 1L,
            ),
        )
        val order = UpdatePolicy.mirrorOrderWithHealth(builtIn, health = health, nowMillis = now)
        assertEquals(
            listOf("https://b.example/eratw/", "https://a.example/eratw/", "https://c.example/eratw/"),
            order,
        )
        assertEquals(3, order.size, "降位不是删除：三条内置源一条都不能少")
        assertEquals(builtIn.toSet(), order.toSet())
    }

    @Test
    fun 成功一次即清零失败数自动回到原位() {
        val failed = mapOf("https://a.example/eratw/" to MirrorHealth(9, 1L))
        assertEquals(
            "https://b.example/eratw/",
            UpdatePolicy.mirrorOrderWithHealth(builtIn, health = failed, nowMillis = now).first(),
        )
        val recovered = builtIn.associateWith { MirrorHealth(0, now) }
        assertEquals(
            builtIn,
            UpdatePolicy.mirrorOrderWithHealth(builtIn, health = recovered, nowMillis = now),
        )
    }

    @Test
    fun 降位的内置源仍排在清单声明的源之前() {
        val health = mapOf(
            "https://a.example/eratw/" to MirrorHealth(consecutiveFailures = 3, lastSuccessMillis = 1L),
        )
        val order = UpdatePolicy.mirrorOrderWithHealth(
            builtIn = builtIn,
            persisted = listOf("https://cached.example/"),
            declared = listOf("https://declared.example/", "https://a.example/eratw/"),
            health = health,
            nowMillis = now,
        )
        assertEquals(
            listOf(
                "https://b.example/eratw/",
                "https://c.example/eratw/",
                "https://a.example/eratw/",
                "https://cached.example/",
                "https://declared.example/",
            ),
            order,
            "降位的内置源仍必须在 persisted 与 declared 之前；declared 里的同名项由 mirrorOrder 去重",
        )
    }

    @Test
    fun MAX_MIRRORS截断不变() {
        val many = (1..12).map { "https://m$it.example/" }
        val capped = UpdatePolicy.mirrorOrderWithHealth(many, health = emptyMap(), nowMillis = now)
        assertEquals(UpdatePolicy.MAX_MIRRORS, capped.size)
        assertEquals(many.take(UpdatePolicy.MAX_MIRRORS), capped)
        // 非法地址一样被丢掉，不占位
        val withBad = UpdatePolicy.mirrorOrderWithHealth(
            listOf("http://明文.example/", "https://good.example/"),
            health = emptyMap(),
            nowMillis = now,
        )
        assertEquals(listOf("https://good.example/"), withBad)
    }

    @Test
    fun 健康判据的坏值不误降位() {
        assertFalse(demoted(MirrorHealth(0, 0L)), "从没成功过且没失败过：先给机会")
        assertFalse(demoted(MirrorHealth(0, now + 10_000L)), "时钟回拨：按新鲜处理")
        assertFalse(demoted(MirrorHealth(2, now - 10_000L)), "连续失败没到阈值")
        assertFalse(demoted(MirrorHealth(0, 0L), failureThreshold = 0), "阈值 <=0 不许把所有源降位")
        assertTrue(demoted(MirrorHealth(3, now - 10_000L)), "连续失败到阈值就降位")
        assertTrue(
            demoted(MirrorHealth(0, now - UpdatePolicy.MIRROR_STALE_AFTER_MILLIS - 1L)),
            "成功过但太久没成功也降位",
        )
    }

    // ---- 清单新键 rollout-percentage -----------------------------------

    private val baseManifest = listOf(
        "update-format 1",
        "assets-version 7",
        "assets-file a.zip",
        "assets-size 1024",
        "assets-sha256 ${"ab".repeat(32)}",
    ).joinToString("\n")

    private fun ready(text: String): UpdateInfo {
        val read = UpdateManifest.parse(text)
        assertTrue(read is ManifestRead.Ready, "应当解析成功，实际 $read")
        return read.info
    }

    @Test
    fun 灰度键缺席按100全量且不告警() {
        val info = ready(baseManifest)
        assertEquals(UpdatePolicy.FULL_ROLLOUT_PERCENTAGE, info.rolloutPercentage)
        assertEquals(emptyList<String>(), info.warnings, "老清单不能因为缺这一行而告警刷屏")
    }

    @Test
    fun 灰度键合法值逐字读出() {
        for (value in listOf(0, 1, 50, 99, 100)) {
            val info = ready("$baseManifest\nrollout-percentage $value")
            assertEquals(value, info.rolloutPercentage, "值 $value")
            assertEquals(emptyList<String>(), info.warnings)
        }
    }

    @Test
    fun 灰度键坏值回落100并告警但绝不拒清单() {
        for (bad in listOf("abc", "-1", "101", "50.5", "1 2", "99999999999999999999")) {
            val info = ready("$baseManifest\nrollout-percentage $bad")
            assertEquals(UpdatePolicy.FULL_ROLLOUT_PERCENTAGE, info.rolloutPercentage, "坏值 [$bad]")
            assertTrue(
                info.warnings.any { it.contains("rollout-percentage") && it.contains("100 全量") },
                "坏值 [$bad] 应告警：${info.warnings}",
            )
        }
    }

    @Test
    fun 灰度键是已知键而真正的未知键仍只告警且重复键整组忽略() {
        val info = ready("$baseManifest\nrollout-percentage 10\nfuture-field 以后的东西")
        assertEquals(1, info.warnings.count { it.contains("忽略不认识的键") }, info.warnings.toString())
        assertTrue(info.warnings.none { it.contains("rollout-percentage") }, info.warnings.toString())
        // P1-2（2026-11 收口）：rollout-percentage 是可选组键，重复只让这一组回落 100 全量并告警，
        // 不再整份拒收（整份拒收 = 整条更新通道对玩家哑掉）。
        val duplicate = UpdateManifest.parse("$baseManifest\nrollout-percentage 10\nrollout-percentage 20")
        val dupInfo = (duplicate as? ManifestRead.Ready)?.info
        assertNotNull(dupInfo, "重复的 rollout-percentage 不该拒整单，实际 $duplicate")
        assertEquals(UpdatePolicy.FULL_ROLLOUT_PERCENTAGE, dupInfo.rolloutPercentage, "整组当缺席后回落 100 全量")
        assertTrue(
            dupInfo.warnings.any { it.contains("rollout-percentage") && it.contains("整组忽略") },
            dupInfo.warnings.toString(),
        )
    }

    @Test
    fun 清单读数接进灰度与强制档判定() {
        val info = ready("$baseManifest\nrollout-percentage 10")
        assertTrue(UpdatePolicy.updateSelected(info.rolloutPercentage, 9, 6L, 0L))
        assertFalse(UpdatePolicy.updateSelected(info.rolloutPercentage, 10, 6L, 0L))
        // 同样的 10% 灰度，强制档命中则放行
        assertTrue(UpdatePolicy.updateSelected(info.rolloutPercentage, 10, 5L, 6L))
    }
}
