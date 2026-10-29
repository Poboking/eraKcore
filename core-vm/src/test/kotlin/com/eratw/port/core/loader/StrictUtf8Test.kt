package com.eratw.port.core.loader

import java.nio.charset.CharacterCodingException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [StrictUtf8] 的**手写判据表**（CMP-86）。
 *
 * ## 期望值从哪来（为什么不再由上游解码器生成）
 *
 * 旧版把期望值写成 `bytes.decodeToString(from, from + len, throwOnInvalidSequence = true)`，
 * 即「被测手写校验器 vs 被测方所替换的那个同族解码器」——两边一起错时仍绿（CMP-86）。
 * 本版期望值全部**手写**，两条来源：
 * - 合法序列：RFC 3629 的最短形式编码规则 + Unicode 码位（表里直接写 `\uXXXX`）；
 * - 非法序列：官方 `EncodingHandler.cs:9` 的
 *   `new UTF8Encoding(false, throwOnInvalidBytes: true)`（解码回退见 `:67-71` 的
 *   `DecoderFallback.ExceptionFallback`）⇒ **非法字节必须抛，绝不许替换成 U+FFFD**；
 *   本仓 [StrictUtf8.decode] 的冷路径（`StrictUtf8.kt:51-55`）把异常原样委托给 JDK
 *   `CharsetDecoder`（REPORT），故这里手写 JDK 的「类型 + 消息」文本作为契约
 *   （`ErbcStreamEquivalenceTest` 的变异差分比的就是这两项）。
 *
 * ## 对照的两处实现
 *
 * - `StrictUtf8.kt:49`：`isWellFormed` 通过 ⇒ `String(bytes, …, UTF_8)` 快路径，**值不变**；
 *   因此合法样本除了断言 `isWellFormed == true`，还要逐码位比值。
 * - `StrictUtf8.kt:51-55` / `:63-134`：非法样本必须 `isWellFormed == false`，且 `decode` 抛
 *   `CharacterCodingException` 家族；异常类型/消息的手写文本按 Temurin 17.0.20.1 的
 *   `sun.nio.cs.UTF_8` REPORT 解码器记录（JDK 改文案 ⇒ 本表红 ⇒ 重新评审，**不得**改回生成）。
 *
 * ## 为什么单列 0x7F / U+0085 / U+FEFF
 *
 * 三者都是**合法标量**，严格校验器不许把它们当非法/控制字符吞掉：`0x7F`(DEL) 与
 * `U+0085`(NEL) 是 meta 侧车引号转义面点名要覆盖的字符（CMP-07），`U+FEFF` 则是
 * 探测层（`EncodingHandler.cs:23-25`）剥 BOM 之外的普通标量 —— 剥 BOM 不归本层做。
 */
class StrictUtf8Test {

    /** 期望被拒绝时捕获的异常（值语义：只用于失败信息）。 */
    private class Rejected(val exception: Throwable)

    private class Valid(
        val label: String,
        val bytes: ByteArray,
        val text: String,
    )

    private class Invalid(
        val label: String,
        val expected: String,
        val bytes: ByteArray,
    )

    private fun escaped(s: String): String = buildString {
        for (ch in s) {
            append("U+")
            append(ch.code.toString(16).uppercase().padStart(4, '0'))
            append(' ')
        }
    }.trimEnd()

    private fun describe(v: Any): String = when (v) {
        is Rejected -> "异常 ${v.exception.javaClass.name}: ${v.exception.message}"
        else -> "字符串 [${escaped(v as String)}]"
    }

    private fun decode(bytes: ByteArray, from: Int, len: Int): Any =
        try {
            StrictUtf8.decode(bytes, from, len)
        } catch (e: Throwable) {
            Rejected(e)
        }

    private fun assertDecodes(
        label: String,
        expected: String,
        bytes: ByteArray,
        from: Int = 0,
        len: Int = bytes.size,
    ) {
        assertTrue(
            StrictUtf8.isWellFormed(bytes, from, len),
            "$label：手写表判为合法，isWellFormed 必须是 true（StrictUtf8.kt:63-134）",
        )
        val got = decode(bytes, from, len)
        assertTrue(got is String, "$label：期望解出 [${escaped(expected)}]，实际 ${describe(got)}")
        assertEquals(expected, got, "$label：逐码位")
    }

    private fun assertRejects(
        label: String,
        expected: String,
        bytes: ByteArray,
        from: Int = 0,
        len: Int = bytes.size,
    ) {
        assertFalse(
            StrictUtf8.isWellFormed(bytes, from, len),
            "$label：手写表判为非法，isWellFormed 必须是 false（StrictUtf8.kt:63-134）",
        )
        val got = decode(bytes, from, len)
        assertTrue(got is Rejected, "$label：期望拒绝，实际 ${describe(got)}")
        assertEquals(
            expected,
            "${got.exception.javaClass.name}: ${got.exception.message}",
            "$label：异常类型 + 消息（冷路径委托 JDK，StrictUtf8.kt:51-55）",
        )
        assertTrue(
            got.exception is CharacterCodingException,
            "$label：必须是 CharacterCodingException 家族（上两条装载路径按 IOException 支收口），实际 ${got.exception.javaClass.name}",
        )
    }

    // ------------------------------------------------------------------
    // 1 合法序列：逐码位手写表
    // ------------------------------------------------------------------

    @Test
    fun `合法序列按手写码位表逐值解码`() {
        for (case in VALID) assertDecodes(case.label, case.text, case.bytes)
    }

    // ------------------------------------------------------------------
    // 2 非法序列：代理项 / 超长 / 截断 / 5-6 字节 / 孤立续字节
    // ------------------------------------------------------------------

    @Test
    fun `非法序列按手写异常表现场拒绝且不许替换成 U+FFFD`() {
        for (case in INVALID) assertRejects(case.label, case.expected, case.bytes)
    }

    // ------------------------------------------------------------------
    // 3 from / len：复用缓冲语境下的切片语义
    // ------------------------------------------------------------------

    @Test
    fun `from 与 len 的切片语义按手写期望`() {
        // 0:7F(DEL) 1:A 2-5:U+1F600 6:B 7:FF(哨兵)
        val buf = bytesOf(0x7F, 0x41, 0xF0, 0x9F, 0x98, 0x80, 0x42, 0xFF)

        assertDecodes("from=1,len=5 只有 A + emoji", "A\uD83D\uDE00", buf, from = 1, len = 5)
        assertDecodes("from=2,len=4 只取 emoji 四个字节", "\uD83D\uDE00", buf, from = 2, len = 4)
        assertDecodes("from=0,len=7 排除尾部哨兵 FF", "\u007FA\uD83D\uDE00B", buf, from = 0, len = 7)
        assertDecodes("len=0 是空串（不许越界读）", "", buf, from = 3, len = 0)
        assertRejects(
            "from=0,len=8 把尾部 FF 纳入 → 截断/非法首字节",
            "java.nio.charset.MalformedInputException: Input length = 1",
            buf,
            from = 0,
            len = 8,
        )
        // 从代理对中间起切：F0 9F 98 80 从第 3 字节（0x98）起 ⇒ 孤立续字节
        assertRejects(
            "from=3,len=3 切断 4 字节序列",
            "java.nio.charset.MalformedInputException: Input length = 1",
            buf,
            from = 3,
            len = 3,
        )
    }

    // ------------------------------------------------------------------
    // 4 随机字节：结构不变量（不生成期望值）
    // ------------------------------------------------------------------

    /**
     * 随机字节上只断**结构不变量**，不用任何解码器当期望值：
     *
     * - `isWellFormed == true` ⇒ `decode` 不抛，且 `String` 重新编码为 UTF-8 必须**逐字节等于**输入
     *   （合法 UTF-8 是规范形式；若校验器放过了非法序列，`String` 快路径会把它换成 U+FFFD，
     *   回编码必然不等 ⇒ 红）；
     * - `isWellFormed == false` ⇒ `decode` 必须抛（若校验器过严、JDK 其实能解，冷路径会返回
     *   字符串而不抛 ⇒ 红）。
     *
     * 这两条一起把「过松」与「过严」都钉住，且不依赖 JDK 生成任何期望值；JDK 只作为
     * 「过严即会放行」的事实出现（`StrictUtf8.kt:51-55` 的委托路径）。
     */
    @Test
    fun `随机字节只断结构不变量：校验通过即逐字节可回编码，校验拒绝即必抛`() {
        val rnd = Random(20261021)
        fun check(label: String, bytes: ByteArray) {
            if (StrictUtf8.isWellFormed(bytes, 0, bytes.size)) {
                val decoded = StrictUtf8.decode(bytes, 0, bytes.size)
                assertTrue(
                    bytes.contentEquals(decoded.toByteArray(Charsets.UTF_8)),
                    "$label：校验通过就必须能逐字节回编码，实际回编码 ${
                        decoded.toByteArray(Charsets.UTF_8).joinToString(" ") { "%02X".format(it) }
                    }",
                )
            } else {
                assertFailsWith<CharacterCodingException>("$label：校验拒绝则 decode 必须抛") {
                    StrictUtf8.decode(bytes, 0, bytes.size)
                }
            }
        }
        repeat(24_000) { i ->
            check("随机#$i", ByteArray(rnd.nextInt(8)) { rnd.nextInt(256).toByte() })
        }
        // 近似合法：偏向 UTF-8 前导/续字节，专压 when 的每个 in 区间与长度边界
        val alphabet = intArrayOf(
            0x41, 0x7F, 0x80, 0x9F, 0xA0, 0xBF, 0xC0, 0xC1, 0xC2, 0xDF,
            0xE0, 0xE1, 0xEC, 0xED, 0xEE, 0xEF, 0xF0, 0xF1, 0xF3, 0xF4,
            0xF5, 0xF8, 0xFC, 0xFE, 0xFF,
        )
        repeat(24_000) { i ->
            check("近似#$i", ByteArray(rnd.nextInt(7)) { alphabet[rnd.nextInt(alphabet.size)].toByte() })
        }
    }

    private companion object {
        /**
         * 合法样本。期望值 = 手写码位（不读实现、不读 JDK）：
         * 2 字节最小/最大、3 字节最小/U+D7FF/U+E000、4 字节最小/U+10FFFF/emoji、
         * 以及 0x7F / U+0085 / U+FEFF 三个必须原样保留的标量。
         */
        val VALID = listOf(
            Valid("ASCII", bytesOf(0x57, 0x41, 0x49, 0x54, 0x20, 0x20, 0x23, 0x31), "WAIT  #1"),
            Valid("0x7F(DEL) 是合法标量，不许丢", bytesOf(0x7F), "\u007F"),
            Valid("U+0085(NEL, C2 85) 是合法标量", bytesOf(0xC2, 0x85), "\u0085"),
            Valid("U+FEFF 在严格解码层是普通标量（剥 BOM 归探测层）", bytesOf(0xEF, 0xBB, 0xBF), "\uFEFF"),
            Valid("2 字节最小 C2 80 = U+0080", bytesOf(0xC2, 0x80), "\u0080"),
            Valid("2 字节最大 DF BF = U+07FF", bytesOf(0xDF, 0xBF), "\u07FF"),
            Valid("3 字节最小 E0 A0 80 = U+0800", bytesOf(0xE0, 0xA0, 0x80), "\u0800"),
            Valid("U+D7FF（代理项区下界之前）", bytesOf(0xED, 0x9F, 0xBF), "\uD7FF"),
            Valid("U+E000（代理项区上界之后）", bytesOf(0xEE, 0x80, 0x80), "\uE000"),
            Valid("4 字节最小 F0 90 80 80 = U+10000", bytesOf(0xF0, 0x90, 0x80, 0x80), "\uD800\uDC00"),
            Valid("4 字节最大 F4 8F BF BF = U+10FFFF", bytesOf(0xF4, 0x8F, 0xBF, 0xBF), "\uDBFF\uDFFF"),
            Valid("emoji U+1F600（F0 9F 98 80）", bytesOf(0xF0, 0x9F, 0x98, 0x80), "\uD83D\uDE00"),
            Valid("中文 U+4E2D（E4 B8 AD）", bytesOf(0xE4, 0xB8, 0xAD), "\u4E2D"),
        )

        /**
         * 非法样本 + 手写异常文本。`Input length = N` 的 N 是 JDK 对**该处非法片段长度**的报告：
         * 首字节非法/超长首字节/孤立续字节 = 1；截断 3 字节 = 2；截断 4 字节 = 3；
         * 代理项 3 字节整体非法 = 3。
         */
        val INVALID = listOf(
            Invalid(
                "代理项码位的 3 字节编码 ED A0 80（U+D800 不许出现在 UTF-8 里）",
                "java.nio.charset.MalformedInputException: Input length = 3",
                bytesOf(0xED, 0xA0, 0x80),
            ),
            Invalid(
                "代理项码位的 3 字节编码 ED BF BF（U+DFFF）",
                "java.nio.charset.MalformedInputException: Input length = 3",
                bytesOf(0xED, 0xBF, 0xBF),
            ),
            Invalid(
                "超长编码 C0 80（应写 C2 80）",
                "java.nio.charset.MalformedInputException: Input length = 1",
                bytesOf(0xC0, 0x80),
            ),
            Invalid(
                "超长编码 C1 BF",
                "java.nio.charset.MalformedInputException: Input length = 1",
                bytesOf(0xC1, 0xBF),
            ),
            Invalid(
                "超长编码 E0 9F BF（3 字节编码 U+07FF）",
                "java.nio.charset.MalformedInputException: Input length = 1",
                bytesOf(0xE0, 0x9F, 0xBF),
            ),
            Invalid(
                "超长编码 F0 8F BF BF（4 字节编码 U+FFFF）",
                "java.nio.charset.MalformedInputException: Input length = 1",
                bytesOf(0xF0, 0x8F, 0xBF, 0xBF),
            ),
            Invalid(
                "越界码位 F4 90 80 80（> U+10FFFF）",
                "java.nio.charset.MalformedInputException: Input length = 1",
                bytesOf(0xF4, 0x90, 0x80, 0x80),
            ),
            Invalid(
                "孤立续字节 80",
                "java.nio.charset.MalformedInputException: Input length = 1",
                bytesOf(0x80),
            ),
            Invalid(
                "孤立续字节 BF",
                "java.nio.charset.MalformedInputException: Input length = 1",
                bytesOf(0xBF),
            ),
            Invalid(
                "FE（永不出现在 UTF-8 里）",
                "java.nio.charset.MalformedInputException: Input length = 1",
                bytesOf(0xFE),
            ),
            Invalid(
                "FF（永不出现在 UTF-8 里）",
                "java.nio.charset.MalformedInputException: Input length = 1",
                bytesOf(0xFF),
            ),
            Invalid(
                "截断 2 字节序列 C3",
                "java.nio.charset.MalformedInputException: Input length = 1",
                bytesOf(0xC3),
            ),
            Invalid(
                "截断 3 字节序列 E4 B8",
                "java.nio.charset.MalformedInputException: Input length = 2",
                bytesOf(0xE4, 0xB8),
            ),
            Invalid(
                "截断 4 字节序列 F0 9F 92",
                "java.nio.charset.MalformedInputException: Input length = 3",
                bytesOf(0xF0, 0x9F, 0x92),
            ),
            Invalid(
                "5 字节序列 F8 88 80 80 80（RFC 3629 已废止）",
                "java.nio.charset.MalformedInputException: Input length = 1",
                bytesOf(0xF8, 0x88, 0x80, 0x80, 0x80),
            ),
            Invalid(
                "6 字节序列 FC 84 80 80 80 80（RFC 3629 已废止）",
                "java.nio.charset.MalformedInputException: Input length = 1",
                bytesOf(0xFC, 0x84, 0x80, 0x80, 0x80, 0x80),
            ),
            Invalid(
                "合法前缀后跟非法字节（错误点在中段）",
                "java.nio.charset.MalformedInputException: Input length = 1",
                bytesOf(0x41, 0xE0, 0x80, 0x80, 0x42),
            ),
        )
    }
}

/** 手写字节表用：`bytesOf(0xED, 0xA0, 0x80)` ⇒ 三个字节。 */
private fun bytesOf(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }
