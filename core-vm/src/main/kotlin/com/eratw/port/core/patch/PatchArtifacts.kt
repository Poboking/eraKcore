package com.eratw.port.core.patch

import com.eratw.port.core.Version
import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.Program
import java.security.MessageDigest

/**
 * 补丁产物：文本头 + 内嵌的**完整合法 `.erbc`**（不新造容器，也不升 `ErbcFormat.VERSION_1`）。
 *
 * ```
 * patch 文件 = 文本头（UTF-8、LF、逐行 "key value"）+ 一行 "---" + 原始 .erbc 字节
 * ```
 *
 * ## 为什么补丁体是「内嵌一份 .erbc」而不是新容器
 *
 * `.erbc` 的段布局、段表、下标契约已经有一套读侧/写侧/映射侧/规约四方对齐的实现
 * （`ErbcReader` / `ErbcWriter` / `ErbcStream` / `specs/erbc-format.md`）。补丁只需要
 * 「一份完整的 .erbc 字节 + 一句『它长在哪个 base 上』」，多造一层容器等于把这四方
 * 各改一遍，收益为零、风险全额。故 v1 的补丁格式**只加了一层文本头**，容器一个字节不动。
 *
 * ## 补丁体做哪一层校验（以及为什么不做整段解码）
 *
 * [read] 与 [of] 都走 `ErbcReader.probe`（文件头 + 段表 + 标志位一致性），
 * **不做整段解码**，理由是硬性的：补丁里的指令下标是**绝对下标**（base + 新增，
 * 见 L1 的 `CodeBuilder` 基线），函数的 `entryPc` 也落在拼接后的指令流里 ——
 * 拿一份补丁体单独 `ErbcReader.decode`，它引用的池下标/入口 pc 必然越出自身范围，
 * 会把一份**合法**补丁判成坏产物。所以本层只判「它是不是一个结构自洽的 .erbc 容器」。
 *
 * TODO(verify)：容器没有总长度字段 ⇒ 「截断点落在最后一段正文内部」这种损坏，
 * probe 判不出来（段偏移仍小于文件尾）。这类损坏必须由装载期「拼接后线性自检」
 * （契约 §1.6 的 I2）兜住 —— 已写进交付报告的「需组长接线」。
 *
 * ## 不变量对应
 *
 * - **I8 写读往返**：[of] 是写侧闸（写出来的体必须先过容器校验），[read] 是读侧闸；
 *   `read(x).write()` 与 `x` 逐字节相同由单测钉死。
 * - **I5 全或无**：[read] 的任何失败都发生在对象构造之前，不返回半成品。
 * - **I3 身份绑定**：[checkAgainst] 见 [PatchHeader.checkAgainst]。
 */
class PatchArtifacts private constructor(
    val header: PatchHeader,
    body: ByteArray,
) {
    /** 补丁体（内嵌 `.erbc`）的内部副本：外部拿不到可改写的引用。 */
    private val bodyBacking: ByteArray = body.copyOf()

    /** 补丁体字节数（= 内嵌 `.erbc` 的字节数）。 */
    val bodySize: Int get() = bodyBacking.size

    /** 补丁体副本（内嵌的完整 `.erbc` 容器）。 */
    fun bodyBytes(): ByteArray = bodyBacking.copyOf()

    /**
     * 序列化成补丁文件字节：文本头（UTF-8、LF）+ 原始 `.erbc` 字节。
     *
     * 确定性：头由 [PatchHeader] 的只读字段按固定序编码，体是原样字节
     * ⇒ 同一份模型两次调用逐字节相同。
     */
    fun write(): ByteArray {
        val head = header.encodeText().toByteArray(Charsets.UTF_8)
        val out = ByteArray(head.size + bodyBacking.size)
        System.arraycopy(head, 0, out, 0, head.size)
        System.arraycopy(bodyBacking, 0, out, head.size, bodyBacking.size)
        return out
    }

    /**
     * 身份校验（不变量 I3）：见 [PatchHeader.checkAgainst]。
     *
     * @throws PatchIdentityMismatchException 任一 base 读数/哈希不符（逐项点名期望 vs 实际）
     */
    fun checkAgainst(base: PatchBaseIdentity) {
        header.checkAgainst(base)
    }

    companion object {
        /**
         * 写侧入口：头 + 补丁体 → 产物（**先校验体**，I8 的「写完立刻读回」左移到构造期）。
         *
         * @throws PatchFormatException 补丁体为空，或不是结构自洽的 `.erbc` 容器
         */
        fun of(
            header: PatchHeader,
            body: ByteArray,
        ): PatchArtifacts {
            requireContainer(body)
            return PatchArtifacts(header, body)
        }

        /**
         * 读侧入口：补丁文件字节 → 产物。
         *
         * 全或无：头解析失败、`---` 缺失、补丁体缺失/截断/不是 `.erbc` 容器，都在
         * 返回对象之前抛出，调用方拿不到半成品（I5）。
         *
         * @throws PatchFormatException 头或体任一不合规
         */
        fun read(bytes: ByteArray): PatchArtifacts {
            val parsed = PatchHeaderCodec.parse(bytes)
            val body = bytes.copyOfRange(parsed.bodyOffset, bytes.size)
            requireContainer(body)
            return PatchArtifacts(parsed.header, body)
        }

        /**
         * 编译器摘要（`compiler` 字段的写侧口径）：`Version.NAME + " " + Version.VERSION`。
         *
         * 它不是身份闸，只用于「这份补丁是谁编的」这类定位信息（I7）；故读侧不校验内容。
         */
        fun compilerSummary(): String = Version.NAME + " " + Version.VERSION

        /**
         * 字节的 SHA-256，**小写十六进制**（`base-sha256` 字段的口径）。
         *
         * 不用 `java.util.HexFormat`：那是 Java 17 API，android 编译面没有它
         * （`AndroidApiCompatTest` 会当场闸红）。
         */
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
            }
            return sb.toString()
        }

        /**
         * 补丁体校验：空体 ⇒ 缺体；其余交给 `ErbcReader.probe` 做容器级判据。
         *
         * 单独成函数是为了让 [of] 与 [read] 用**同一段判据与同一句文案**（两侧口径漂移是
         * 这类格式最常见的退化形态）。
         */
        private fun requireContainer(body: ByteArray) {
            if (body.isEmpty()) {
                throw PatchFormatException(
                    "补丁体缺失：补丁体必须是一份完整的 .erbc（至少 ${ErbcFormat.HEADER_BYTES} 字节），实为 0 字节",
                )
            }
            try {
                ErbcReader.probe(body)
            } catch (e: ErbcDecodeException) {
                throw PatchFormatException("补丁体不是结构自洽的 .erbc 容器：${e.message}", e)
            }
        }
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * 取 [Program] 的 base 身份读数（只读，不改 `Program.kt` —— 那一面归 L1）。
 *
 * 字段对应关系（与契约字段表逐项对齐）：
 *
 * | 补丁头字段 | `Program` 的只读读数 |
 * |---|---|
 * | `base-instructions` | `instructionCount` |
 * | `base-functions` | `functions.size`（`Program` 没有 `functionCount` 属性；惰性函数表也是 `List`） |
 * | `base-statements` | `statementCount` |
 * | `base-strings` | `stringCount` |
 * | `base-longs` | `constantCount`（`Program` 的常量池只有 Long 池，故无 `longCount` 属性） |
 * | `base-exprs` | `exprCount` |
 * | `base-sha256` | 由调用方传入（装载侧手里有 `program.erbc` 的字节，`Program` 本身不记哈希） |
 *
 * @param sha256 base `program.erbc` 的 SHA-256，64 位小写十六进制（见 [PatchArtifacts.sha256Hex]）
 */
fun Program.patchBaseIdentity(sha256: String): PatchBaseSnapshot {
    require(isLowerHexSha256(sha256)) {
        "base sha256 必须是 $SHA256_HEX_LENGTH 位小写十六进制：$sha256"
    }
    return PatchBaseSnapshot(
        instructionCount = instructionCount,
        functionCount = functions.size,
        statementCount = statementCount,
        stringCount = stringCount,
        longCount = constantCount,
        exprCount = exprCount,
        sha256 = sha256,
    )
}
