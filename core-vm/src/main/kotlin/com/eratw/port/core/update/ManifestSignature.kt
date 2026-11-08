package com.eratw.port.core.update

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 更新清单**本体签名**的验签层（契约 CONTRACT-UPD1-3LANE §2 U1 病①）。
 *
 * ## 为什么清单要自己带签名
 *
 * artifact 的 sha256 全写在清单里，而清单本身在同一个静态目录里 —— 攻击者把镜像上
 * manifest+APK+zip **整包换掉**，旧客户端无从发现（每一件都对得上清单）。唯一的钉子是
 * 清单本体：发布方用私钥对清单字节签名，客户端用**编进包里的公钥**验 —— 公钥不在被替换
 * 的那份目录里，整包替换到这一步必败。
 *
 * ## 算法裁定（契约已定，不许换）
 *
 * RSA-2048 + `SHA256withRSA`（PKCS#1 v1.5）。理由：`java.security` 的 Ed25519 在 Android
 * 要 API 33+，本工程 minSdk 21 不可用；RSA 这条 JDK 1.x 起就有（TODO(verify)：SHA256withRSA
 * 于 API 21 可用的官方文档引证 —— Conscrypt 的算法支持面，交付时给 URL）。
 *
 * ## 签名形状（发布侧 `devtools/emit_update.py` 与本层一字不差的契约）
 *
 * 清单最后一行是 `manifest-signature <base64>`（与全清单同族：一行一条「键 值」，无冒号），
 * 配套两行 `sig-alg rsa-sha256`、`sig-key-id k1`。**被签字节 = 去掉签名行（含其行尾换行）
 * 后的其余全部字节**：UTF-8 原样，不做归一化、不去空白、不动行尾 —— 签的就是发出去的那份
 * 字节，任何「帮你顺手规整」的步骤都会让验签对不上同一份文件。
 *
 * ## 签名覆盖面必须是一整份清单（P0-1，2026-11 收口）
 *
 * 被签字节只到签名行之前，解析却读全文 —— 于是「签名行之后追加几行」就是一条完全合法的注入
 * 通道：追加 `assets-full-file` / `mirror` / `rollout-percentage` 行既能改下载目标又能加镜像。
 * 所以形状契约收紧为：**签名行必须是最后一个非空行**，其后只许出现空白（通常就一个行尾换行）。
 * 签名行之后出现任何非空白字节 ⇒ [Verdict.INVALID]（**不是** UNSIGNED：有签名却夹带了签外
 * 内容，是攻击形状而不是旧格式）。
 *
 * ## 删签名行不能降级（P0-2，同日）
 *
 * 反过来的一条路：把签名行删掉，让清单退回「未签名旧格式」放行。所以只要清单里出现
 * `sig-alg` 或 `sig-key-id` 行，就说明发布方本来签过 —— 此时没有签名行 ⇒ [Verdict.INVALID]。
 * 三者都没有才是真正的历史未签名清单（[Verdict.UNSIGNED]）。
 *
 * 切割实现走 [splitSignedPortion]：在文本里找最后一行 `manifest-signature ` 的起点，
 * 前面那一段（含它自己的行尾换行）就是被签字节；签名行之后的原文一并带出去，供上面的
 * 覆盖面判式读。清单是合法 UTF-8（发布侧固定 `\n` 行尾写出），String 与原始字节可无损互转。
 *
 * ## 公钥表：编进包里，有序支持轮换
 *
 * 公钥不是秘密，硬编码在 core（不进 gradle —— 那要抢 `app/build.gradle.kts` 的 resValue 面，
 * 且 core 的判定单测拿不到 resValue）。`k1` 是现役键；`next` 是**空位**：轮换时先把下一把
 * 公钥填进去（清单还指着 `k1`，不影响现役），发布侧切 `sig-key-id` 后老包也能验过新清单 ——
 * 而只带新公钥的新包同样验得过老清单。`next` 未填时按「验不过」处理（三态里没有第四态：
 * 指着一把包里没有的钥匙 = 这个镜像的清单不可信）。
 *
 * 纯 JVM `java.security`：不碰网络、不碰文件系统、不碰 Android API（AGENTS.md 铁律 4）。
 * Base64 走 `kotlin.io.encoding`（stdlib，API 21 可用；`java.util.Base64` 要 API 26）。
 */
@OptIn(ExperimentalEncodingApi::class)
object ManifestSignature {

    /** 清单 `sig-alg` 的现役取值；其他取值一律判「不过」（不认识的算法不能当未签名放行）。 */
    const val ALG_RSA_SHA256: String = "rsa-sha256"

    /** `java.security` 侧的真实算法名（与 [ALG_RSA_SHA256] 是同一件事的两个叫法）。 */
    const val JAVA_ALGORITHM: String = "SHA256withRSA"

    /** RSA 密钥族名（KeyFactory 用）。 */
    private const val KEY_FAMILY: String = "RSA"

    /** 清单里签名行的键名（最后一行；与发布侧 `emit_update.py` 同名同值）。 */
    const val SIGNATURE_KEY: String = "manifest-signature"

    /** 现役键 id（清单 `sig-key-id` 的值）。 */
    const val KEY_ID_K1: String = "k1"

    /** 轮换预留键 id：公钥还没填，填上后老包新包互验（见类头「公钥表」）。 */
    const val KEY_ID_NEXT: String = "next"

    /**
     * k1 的公钥（X.509 `SubjectPublicKeyInfo` DER 的 Base64，单行无换行）。
     *
     * **正式发布键**（组长 2026-11-05 收口时用 `openssl genpkey -algorithm RSA` 生成，
     * RSA-2048）：配对私钥在共享根 `<work-root>/eratw-安卓移植计划/local.update-signing/k1-private.pk8.pem`
     * （已 gitignore，**不入库**）；出清单时 `python devtools/emit_update.py --sign-key <该路径>`。
     * 跨语言闭环钉在 `ManifestSignatureTest` 的 golden 常量里 —— 换键必须同步换那份签名，
     * 用例会以 VERIFIED 变 INVALID 的方式吵起来。
     */
    private const val K1_BASE64: String =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1kKfv/iqAGESU0Xorzf0E19r/4T++VaPEVGyyzlC9LU1hRmcopW7uVWFT7+0yd37s3Ah2xf6bL9QGmPUXmlk766Z8jGvKiVBsKzZLDMLa3bCGdhuzmP2FuYUpHpzPhjl/8Un67KvJrnfZJR2drMB8H3E1MOVc38LdJw2O5l8BOBcdlk6TCqEpR1mJCLZKz4bTtxu480GQKxAEywJxdRUj3iaCthihH2N5NkC3meWUTQ9Ogo2WszD/GmXhcWacB1YGZ0Peu3FXsiGi6Y1IjVI7ZNi8u3UdkheW2uKfKzkchxtGzIe78/qyV/lfrIfcvW2745Daun69vDsANN4tlyl9QIDAQAB"

    /** 轮换空位：未填 = 空串，[builtinKeys] 跳过它（「空位」不是一把钥匙）。 */
    private const val NEXT_BASE64: String = ""

    /** 签名声明的算法行键名（P0-2 用来识别「签名行被删」的降级形状）。 */
    private const val SIG_ALG_KEY: String = "sig-alg"

    /** 签名声明的键 id 行键名（同上）。 */
    private const val SIG_KEY_ID_KEY: String = "sig-key-id"

    /** 内置公钥表（有序：k1 现役在前）。解析失败（常量被改坏）就整条不进表 —— 坏常量不静默。 */
    val builtinKeys: List<Pair<String, PublicKey>> = listOf(
        KEY_ID_K1 to parsePublicKey(K1_BASE64),
        KEY_ID_NEXT to NEXT_BASE64.takeIf { it.isNotEmpty() }?.let(::parsePublicKey),
    ).mapNotNull { (id, key) -> key?.let { id to it } }

    /** 单条 Base64 → [PublicKey]；形状不对返回 null（调用方决定进不进表、算不算过）。 */
    fun parsePublicKey(base64: String): PublicKey? = try {
        KeyFactory.getInstance(KEY_FAMILY)
            .generatePublic(X509EncodedKeySpec(Base64.decode(base64)))
    } catch (_: Throwable) {
        null
    }

    /**
     * 验签三态（契约 §2 U1：过 / 不过 / 清单未签名）。
     *
     * - [UNSIGNED]：清单既没有 `manifest-signature` 行，也没有 `sig-alg` / `sig-key-id`
     *   声明行 —— 真正的历史旧格式，放行由调用方落 warning（迁移口径：不带键是历史形态，
     *   不是攻击信号）；
     * - [VERIFIED]：带了签名、签名行是最后一个非空行、`sig-alg` 认识、`sig-key-id` 在内置表里、
     *   验签通过；
     * - [INVALID]：其余一切 —— 签名对不上、字节被改、签名行之后夹带非空白内容、有签名声明
     *   却缺签名行、算法不认识、key id 不在表里。「该镜像清单不可用」，调用方按逐镜像回退
     *   走下一条。
     */
    enum class Verdict { VERIFIED, INVALID, UNSIGNED }

    /**
     * 清单文本 → 验签结论。输入是**取回来的原始文本**（app 壳从镜像 GET 到的字符串原样），
     * 本函数自己完成切割（[splitSignedPortion]）与验签，调用方不再碰字节。
     *
     * 三条形状判式按顺序先于验签（见类头「签名覆盖面」与「删签名行」两节）：
     * 1. 没有签名行：带 `sig-alg` / `sig-key-id` 声明 ⇒ [INVALID]（删签名行降级），否则 [UNSIGNED]；
     * 2. 签名行之后还有非空白字节 ⇒ [INVALID]（签外夹带）；
     * 3. 其余走算法/键表/密码学验签。
     *
     * `sig-alg` / `sig-key-id` 行缺席时：`sig-alg` 缺席按 [ALG_RSA_SHA256] 处理（签名字节
     * 自证算法，多一道显式声明是为了轮换时不猜）；`sig-key-id` 缺席按 [KEY_ID_K1] 处理
     * （单键时代的旧写法）。两者带了但不认识 → [INVALID]。
     */
    fun verify(manifestText: String): Verdict {
        val split = splitSignedPortion(manifestText)
        if (split == null) {
            // 删签名行不能退回「未签名放行」：sig-alg / sig-key-id 还在说明发布方本来签过，
            // 这条路径只可能是被删或被截断（P0-2）。
            return if (declaresSigning(manifestText)) Verdict.INVALID else Verdict.UNSIGNED
        }
        // 签名只覆盖签名行之前，解析却读全文：签名行之后只要还有非空白字节，后面那一段就是
        // 没被签过的内容（可追加 assets-full / mirror / rollout 行做注入），整份判不可用（P0-1）。
        if (!split.trailingOnlyWhitespace) return Verdict.INVALID
        val alg = firstValue(manifestText, SIG_ALG_KEY)
        if (alg != null && alg != ALG_RSA_SHA256) return Verdict.INVALID
        val keyId = firstValue(manifestText, SIG_KEY_ID_KEY) ?: KEY_ID_K1
        val key = builtinKeys.firstOrNull { (id, _) -> id == keyId }?.second
            ?: return Verdict.INVALID
        return if (verifyWith(split.signedBytes, split.signatureBase64, key)) {
            Verdict.VERIFIED
        } else {
            Verdict.INVALID
        }
    }

    /** 清单里有没有签名行（只看形状、不验签）：分派层在 INVALID 里区分「缺签名」与「验不过」。 */
    fun hasSignatureLine(manifestText: String): Boolean = splitSignedPortion(manifestText) != null

    /** 签名行是不是最后一个非空行（P0-1 的形状判式；[verify] 已内建，消费侧再做一道双保险）。 */
    fun isSignatureLineLast(manifestText: String): Boolean =
        splitSignedPortion(manifestText)?.trailingOnlyWhitespace == true

    /** 清单里有没有签名声明行（sig-alg / sig-key-id）—— 用来识别「签名行被删」的降级形状。 */
    private fun declaresSigning(manifestText: String): Boolean = manifestText.lineSequence().any { raw ->
        val line = raw.removeSuffix("\r").trim()
        line.startsWith("$SIG_ALG_KEY ") || line == SIG_ALG_KEY ||
            line.startsWith("$SIG_KEY_ID_KEY ") || line == SIG_KEY_ID_KEY
    }

    /** 第一条 `键 值` 形的值（去行尾 CR、trim）；没有这一行返回 null。 */
    private fun firstValue(manifestText: String, key: String): String? = manifestText.lineSequence()
        .map { it.removeSuffix("\r") }
        .firstOrNull { it.startsWith("$key ") }
        ?.substringAfter(' ')?.trim()

    /**
     * 清单文本 → （被签字节, 签名 Base64, 签名行之后的原文）；没有签名行返回 null（未签名清单）。
     *
     * 签名行 = 文本里**最后一行**以 `manifest-signature ` 开头的行（发布侧永远写在末行；
     * 取最后一行是为了「理论上签名行后面还有别的行」时切割仍是契约形状，但那种形状会被
     * [SignedSplit.trailingOnlyWhitespace] 判成签外夹带，见 [verify]）。
     * 被签字节 = 从文件头到该行起点前的一切字节（含前一行的行尾换行），签名行自己的
     * 行尾换行**不在**被签字节里（契约 §2：去掉该行含其行尾换行）。
     */
    fun splitSignedPortion(manifestText: String): SignedSplit? {
        var lineStart = 0
        var signatureLineStart = -1
        var signatureLineEnd = -1
        var signatureValue = ""
        val length = manifestText.length
        while (lineStart < length) {
            var lineEnd = manifestText.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = length
            val line = manifestText.substring(lineStart, lineEnd).removeSuffix("\r")
            if (line.startsWith("$SIGNATURE_KEY ")) {
                signatureLineStart = lineStart
                signatureLineEnd = lineEnd
                signatureValue = line.substringAfter(' ').trim()
            }
            lineStart = lineEnd + 1
        }
        if (signatureLineStart < 0 || signatureValue.isEmpty()) return null
        val signedText = if (signatureLineStart <= 0) "" else manifestText.substring(0, signatureLineStart)
        // 尾部读数从签名行的行尾换行之后开始；签名行是末行且没有行尾换行时就是空串。
        val trailingText = manifestText.substring(minOf(signatureLineEnd + 1, length))
        return SignedSplit(signedText.toByteArray(Charsets.UTF_8), signatureValue, trailingText)
    }

    /**
     * 切割结果：被签字节 + 签名值（Base64 原样，不重排不补 padding）+ 签名行之后的原文。
     *
     * @param trailingText 签名行（含它自己的行尾换行）之后的全部原文；正常清单只该是空串或
     *   纯空白（发布侧固定一个 `\n` 收尾）。出现非空白 ⇒ 那段内容在签名覆盖面之外。
     */
    class SignedSplit(
        val signedBytes: ByteArray,
        val signatureBase64: String,
        val trailingText: String = "",
    ) {
        /** 签名行之后是否只有空白字节：契约要求签名行是最后一个非空行（P0-1）。 */
        val trailingOnlyWhitespace: Boolean get() = trailingText.all { it.isWhitespace() }
    }

    /** 单把钥匙的验签本体；任何异常（坏 Base64、坏签名长度）都算「不过」，不外抛。 */
    fun verifyWith(signedBytes: ByteArray, signatureBase64: String, key: PublicKey): Boolean = try {
        val signature = Signature.getInstance(JAVA_ALGORITHM)
        signature.initVerify(key)
        signature.update(signedBytes)
        signature.verify(Base64.decode(signatureBase64))
    } catch (_: Throwable) {
        false
    }
}
