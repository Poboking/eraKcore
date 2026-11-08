package com.eratw.port.core.update

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.security.KeyPairGenerator
import java.security.Signature

/**
 * 清单本体验签 + 强制更新档的**跨语言闭环钉子**（CONTRACT-UPD1-3LANE §2 U1）。
 *
 * [GOLDEN_BODY] 与 [GOLDEN_SIGNATURE] 由发布侧 `devtools/emit_update.py --sign-key <正式 k1 私钥>`
 * 的同一套 `SHA256withRSA` 签出（组长 2026-11-05 收口时生成），所以这条用例同时钉住三件事：
 * python 侧签的字节形状、Kotlin 侧 `splitSignedPortion` 的切割形状、内置公钥是不是配对的那把。
 * 换正式密钥时**必须**同步换 golden 签名，否则本用例立刻由 VERIFIED 变 INVALID —— 这是故意的。
 *
 * 迁移口径也在这一层钉住：带签名验不过 = INVALID（`UpdateManager.checkNow` 走下一镜像），
 * 没签名 = UNSIGNED（放行 + warning），两者绝不可混。
 *
 * 2026-11 收口追加的两个形状钉子（P0-1 / P0-2）：
 * - 签名行必须是**最后一个非空行**，其后出现任何非空白字节 ⇒ INVALID（签外夹带）；
 * - 清单出现 `sig-alg` / `sig-key-id` 却没有签名行 ⇒ INVALID（删签名行不能降级成旧格式）。
 *
 * 兼容切换点（P0-2 后半）：`UpdatePolicy.verifyManifestPolicy(text, signedRequired = true)`
 * 把 UNSIGNED 改判 INVALID —— 装 18+ 的包起只接受签名清单，17 及以下维持迁移放行。
 */
@OptIn(ExperimentalEncodingApi::class)
class ManifestSignatureTest {

    companion object {
        private val GOLDEN_BODY: String = listOf(
            "update-format 1",
            "generated 2026-11-05T00:00:00Z",
            "apk-version-code 17",
            "apk-version-name 0.2.40",
            "apk-file eratw-0.2.40.apk",
            "apk-size 12345",
            "apk-sha256 ${"a".repeat(64)}",
            "min-supported-version 15",
            "sig-alg rsa-sha256",
            "sig-key-id k1",
            "",
        ).joinToString("\n")

        private const val GOLDEN_SIGNATURE: String =
            "im1AQWz+qvQGDgs0ZsNLqkwUOk6oCU4vMSCD1USvN5CvItZNc1EHtlJqX9G/5CdC5KGJH1MdVPsdFW9yQbbmdFjJCHBCjMNknRHmtFn9p9hk1yRVDov1jPEYajV+XDZqpltedtWeCv1BZ8lhKBGHBOvGb6FaGj1FMXHOcNcmoN/M2zQYQGE20QeGsL3cSYYI4ZQcrFfFv7Zs72qKvWFo2IkCRy8eXuRmXl2sYMmkOsHjxbJgiemZXdvgiJdIOoLuRB07HvmDoko3hZXTznRTnJjrKMlCVIShfaOzvvrWK0gH1Dp3dCcuvwUkRSzcoSwkFoQ7IgRtn1GfBnzdPuQURA=="

        private fun signed(body: String = GOLDEN_BODY, signature: String = GOLDEN_SIGNATURE): String =
            body + "manifest-signature $signature\n"
    }

    @Test
    fun golden_from_python_publisher_verifies_with_builtin_key() {
        assertEquals(ManifestSignature.Verdict.VERIFIED, ManifestSignature.verify(signed()))
    }

    @Test
    fun tampered_body_is_invalid_not_unsigned() {
        val tampered = GOLDEN_BODY.replace("apk-size 12345", "apk-size 12346")
        assertEquals(ManifestSignature.Verdict.INVALID, ManifestSignature.verify(signed(body = tampered)))
    }

    @Test
    fun `删签名行但留签名声明时判 INVALID 而不是降级成 UNSIGNED`() {
        // 攻掉「签名行删了就当旧格式放行」这条路（P0-2）：sig-alg / sig-key-id 还在，
        // 说明发布方本来签过 —— 此时没有签名行只可能是被删或被截断，判不可用。
        assertEquals(ManifestSignature.Verdict.INVALID, ManifestSignature.verify(GOLDEN_BODY))
    }

    @Test
    fun `只留 sig_alg 或 sig_key_id 之一而没有签名行也判 INVALID`() {
        val onlyAlg = GOLDEN_BODY.replace("sig-key-id k1\n", "")
        val onlyKeyId = GOLDEN_BODY.replace("sig-alg rsa-sha256\n", "")
        assertEquals(ManifestSignature.Verdict.INVALID, ManifestSignature.verify(onlyAlg))
        assertEquals(ManifestSignature.Verdict.INVALID, ManifestSignature.verify(onlyKeyId))
    }

    @Test
    fun `签名行之后追加任何非空白字节都判 INVALID`() {
        // P0-1：被签字节只到签名行之前，解析却读全文 —— 追加行就是未签的注入通道。
        for (injected in listOf(
            "assets-full-file evil.zip",
            "mirror https://evil.example/eratw/",
            "rollout-percentage 100",
            "apk-file evil.apk",
        )) {
            assertEquals(
                ManifestSignature.Verdict.INVALID,
                ManifestSignature.verify(signed() + "$injected\n"),
                "签名行之后追加 [$injected] 必须判 INVALID",
            )
        }
        // 不换行直接粘在签名行后面（签名值被改坏，同样不可用）。
        assertEquals(ManifestSignature.Verdict.INVALID, ManifestSignature.verify(signed() + "junk"))
    }

    @Test
    fun `签名行之后只有空白仍算 VERIFIED`() {
        // 发布侧固定 `\n` 收尾；多几个空行/空格是排版噪声，不是签外内容。
        assertEquals(ManifestSignature.Verdict.VERIFIED, ManifestSignature.verify(signed()))
        assertEquals(ManifestSignature.Verdict.VERIFIED, ManifestSignature.verify(signed() + "\n"))
        assertEquals(ManifestSignature.Verdict.VERIFIED, ManifestSignature.verify(signed() + "   \n\t\n"))
    }

    @Test
    fun split_尾部读数只含签名行之后的内容() {
        val clean = assertNotNull(ManifestSignature.splitSignedPortion(signed()))
        assertEquals("", clean.trailingText)
        assertTrue(clean.trailingOnlyWhitespace)

        val spaced = assertNotNull(ManifestSignature.splitSignedPortion(signed() + "\n  \n"))
        assertEquals("\n  \n", spaced.trailingText)
        assertTrue(spaced.trailingOnlyWhitespace)

        val injected = assertNotNull(ManifestSignature.splitSignedPortion(signed() + "mirror https://evil.example/\n"))
        assertEquals("mirror https://evil.example/\n", injected.trailingText)
        assertFalse(injected.trailingOnlyWhitespace)
    }

    @Test
    fun 签名行形状判式与验签结论同向() {
        assertTrue(ManifestSignature.isSignatureLineLast(signed()))
        assertFalse(ManifestSignature.isSignatureLineLast(signed() + "assets-full-file evil.zip\n"))
        assertFalse(ManifestSignature.isSignatureLineLast(GOLDEN_BODY), "没有签名行就谈不上「最后一行」")
        assertTrue(ManifestSignature.hasSignatureLine(signed()))
        assertFalse(ManifestSignature.hasSignatureLine(GOLDEN_BODY))
    }

    @Test
    fun bad_signature_base64_is_invalid() {
        assertEquals(ManifestSignature.Verdict.INVALID, ManifestSignature.verify(signed(signature = "!!!not-base64!!!")))
    }

    @Test
    fun unknown_algorithm_is_invalid() {
        val body = GOLDEN_BODY.replace("sig-alg rsa-sha256", "sig-alg ed25519")
        assertEquals(ManifestSignature.Verdict.INVALID, ManifestSignature.verify(signed(body = body)))
    }

    @Test
    fun unknown_key_id_is_invalid() {
        val body = GOLDEN_BODY.replace("sig-key-id k1", "sig-key-id next")
        assertEquals(ManifestSignature.Verdict.INVALID, ManifestSignature.verify(signed(body = body)))
    }

    @Test
    fun unsigned_legacy_manifest_is_unsigned() {
        val legacy = "update-format 1\napk-version-code 17\napk-file eratw.apk\n"
        assertEquals(ManifestSignature.Verdict.UNSIGNED, ManifestSignature.verify(legacy))
    }

    @Test
    fun split_signed_portion_excludes_signature_line_but_keeps_every_other_byte() {
        val split = assertNotNull(ManifestSignature.splitSignedPortion(signed()))
        assertTrue(split.signedBytes.contentEquals(GOLDEN_BODY.toByteArray(Charsets.UTF_8)))
        assertEquals(GOLDEN_SIGNATURE, split.signatureBase64)
    }

    @Test
    fun verify_with_generated_keypair_accepts_own_signature_and_rejects_tamper() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val bytes = GOLDEN_BODY.toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance(ManifestSignature.JAVA_ALGORITHM)
        signer.initSign(keys.private)
        signer.update(bytes)
        val signature = Base64.encode(signer.sign())

        assertTrue(ManifestSignature.verifyWith(bytes, signature, keys.public))
        assertFalse(
            ManifestSignature.verifyWith(
                GOLDEN_BODY.replace("apk-size 12345", "apk-size 99999").toByteArray(Charsets.UTF_8),
                signature,
                keys.public,
            ),
        )
    }

    @Test
    fun policy_delegates_three_verdicts() {
        assertEquals(ManifestSignature.Verdict.VERIFIED, UpdatePolicy.verifyManifestPolicy(signed()))
        assertEquals(ManifestSignature.Verdict.INVALID, UpdatePolicy.verifyManifestPolicy(signed(signature = "AAAA")))
        assertEquals(ManifestSignature.Verdict.UNSIGNED, UpdatePolicy.verifyManifestPolicy("update-format 1\n"))
    }

    @Test
    fun `签名必须性判式把 UNSIGNED 按本机版本档改判 INVALID`() {
        // 老格式清单（无签名、无 sig 行）在 signedRequired=false 下维持迁移放行。
        val legacy = "update-format 1\napk-version-code 17\napk-file eratw.apk\n"
        assertEquals(ManifestSignature.Verdict.UNSIGNED, UpdatePolicy.verifyManifestPolicy(legacy))
        assertEquals(ManifestSignature.Verdict.UNSIGNED, UpdatePolicy.verifyManifestPolicy(legacy, signedRequired = false))
        // 装到切换点（18）起的本机只接受签名清单：未签名 ⇒ 不可用，走下一镜像。
        assertEquals(ManifestSignature.Verdict.INVALID, UpdatePolicy.verifyManifestPolicy(legacy, signedRequired = true))
        // 签名清单在两种档位下都是 VERIFIED；真被改坏的仍是 INVALID（不会被必须性判式洗白）。
        assertEquals(ManifestSignature.Verdict.VERIFIED, UpdatePolicy.verifyManifestPolicy(signed(), signedRequired = true))
        val tampered = signed(body = GOLDEN_BODY.replace("apk-size 12345", "apk-size 12346"))
        assertEquals(ManifestSignature.Verdict.INVALID, UpdatePolicy.verifyManifestPolicy(tampered, signedRequired = true))
        // 形状判式优先于必须性判式：有签名声明却删了签名行，即便 signedRequired=false 也是 INVALID。
        assertEquals(ManifestSignature.Verdict.INVALID, UpdatePolicy.verifyManifestPolicy(GOLDEN_BODY))
    }

    @Test
    fun `老格式完整清单在迁移档下仍 UNSIGNED 且可解析`() {
        // 兼容性自查的钉子（P0-2 反向）：update-format 1 + apk 组齐全 + 无签名行 + 无 sig 行，
        // 在 signedRequired=false（已发出去的 17 及以下老包）下必须仍「验签放行 + 解析 Ready」，
        // 整条老更新通道不被这轮收紧哑掉；切到 18+ 才强制签名。
        val legacy = listOf(
            "update-format 1",
            "generated 2026-11-05T00:00:00Z",
            "apk-version-code 17",
            "apk-version-name 0.2.40",
            "apk-file eratw-0.2.40.apk",
            "apk-size 12345",
            "apk-sha256 ${"a".repeat(64)}",
        ).joinToString("\n")
        assertEquals(ManifestSignature.Verdict.UNSIGNED, ManifestSignature.verify(legacy))
        assertEquals(ManifestSignature.Verdict.UNSIGNED, UpdatePolicy.verifyManifestPolicy(legacy, signedRequired = false))
        assertEquals(ManifestSignature.Verdict.INVALID, UpdatePolicy.verifyManifestPolicy(legacy, signedRequired = true))
        val read = UpdateManifest.parse(legacy)
        val info = (read as? ManifestRead.Ready)?.info
        assertNotNull(info, "老格式完整清单必须仍解析成功，实际 $read")
        assertEquals(17L, info.apk?.versionCode)
    }

    @Test
    fun `兼容切换点就是下一个要发的 versionCode`() {
        // 2026-11 收口时 app/build.gradle.kts 的 versionCode = 17；切换点定在下一个要发的 18。
        // 换切点时**必须**同步改这条断言与 KDoc（它是「老包维持放行、新包强制签名」的唯一判据源）。
        assertEquals(18L, UpdatePolicy.SIGNED_MANIFEST_REQUIRED_FROM_APK_CODE)
    }

    @Test
    fun force_update_matrix_strictly_less_than_min_supported() {
        assertTrue(UpdatePolicy.isForceUpdateRequired(14L, 15L))
        assertFalse(UpdatePolicy.isForceUpdateRequired(15L, 15L), "等于 min 仍在支持档内")
        assertFalse(UpdatePolicy.isForceUpdateRequired(16L, 15L), "高于 min 不拦")
        assertFalse(UpdatePolicy.isForceUpdateRequired(0L, 15L), "读不出本机版本宁可不拦")
        assertFalse(UpdatePolicy.isForceUpdateRequired(-1L, 15L))
        assertFalse(UpdatePolicy.isForceUpdateRequired(14L, 0L), "清单没写 min")
        assertFalse(UpdatePolicy.isForceUpdateRequired(14L, -1L), "清单坏值")
    }

    @Test
    fun manifest_parses_min_supported_version_with_absent_default() {
        val parsed = UpdateManifest.parse(signed())
        val info = (parsed as? ManifestRead.Ready)?.info
        assertNotNull(info, "签约清单也应解析成功，实际 $parsed")
        assertEquals(15L, info.minSupportedVersion)

        // 旧清单 = 同一份完整形状，只是没有 min-supported-version 这一行（不是缺字段的坏清单）。
        val legacyBody = GOLDEN_BODY.replace("min-supported-version 15\n", "")
        val legacy = UpdateManifest.parse(legacyBody + "manifest-signature $GOLDEN_SIGNATURE\n")
        val legacyInfo = (legacy as? ManifestRead.Ready)?.info
        assertNotNull(legacyInfo, "旧清单也应解析成功，实际 $legacy")
        assertEquals(UpdateManifest.NO_MIN_SUPPORTED_VERSION, legacyInfo.minSupportedVersion)
    }
}
