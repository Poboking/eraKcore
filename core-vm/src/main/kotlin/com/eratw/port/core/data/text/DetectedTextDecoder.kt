package com.eratw.port.core.data.text

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * `EncodingHandler.DetectEncoding`（`EncodingHandler.cs:15-41`）的字节版解码移植，
 * 供 **不能碰 `java.nio.file`** 的 Android 宿主（minSdk 21）复用。
 *
 * 口径（与 `CsvLoader` 的私有分支逐条同源，`specs/verify-notes.md` §4.1）：
 * 1. 前 3 字节 `EF BB BF` → 跳过 BOM 严格 UTF-8（`:23-26`，`UTF8BOMEncoding` 带
 *    `throwOnInvalidBytes: true`）；
 * 2. 否则按 `StreamReader` 缺省 BOM 嗅探顺序 UTF-32LE / UTF-16LE / UTF-32BE / UTF-16BE
 *    宽松解码（这些编码无异常回退，坏字节替换，`:27`）；
 * 3. 否则整文件严格 UTF-8（`:33-35`）；
 * 4. 失败 → 严格 CP932（`:37-40`，`GetEncoding(932)` + ExceptionFallback，`:67-71`）；
 * 5. 仍失败 → null（原版 `File.ReadAllLines` 抛异常 → 上游跳过文件）。
 */
object DetectedTextDecoder {

    /** 原版 `shiftjisEncoding = GetEncoding(932)`（`EncodingHandler.cs:11`）在 JDK 侧的名字。 */
    private val CP932: Charset? = runCatching { Charset.forName("windows-31J") }.getOrNull()

    fun decode(raw: ByteArray): String? {
        val n = raw.size
        fun at(i: Int): Int = raw[i].toInt() and 0xFF
        if (n >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) {
            return strictDecode(raw, 3, StandardCharsets.UTF_8)
        }
        if (n >= 2 && at(0) == 0xFF && at(1) == 0xFE) {
            if (n >= 4 && at(2) == 0 && at(3) == 0) {
                return String(raw, 4, n - 4, charsetOrNull("UTF-32LE") ?: return null)
            }
            return String(raw, 2, n - 2, StandardCharsets.UTF_16LE)
        }
        if (n >= 2 && at(0) == 0xFE && at(1) == 0xFF) {
            if (n >= 4 && at(2) == 0 && at(3) == 0) {
                return String(raw, 4, n - 4, charsetOrNull("UTF-32BE") ?: return null)
            }
            return String(raw, 2, n - 2, StandardCharsets.UTF_16BE)
        }
        strictDecode(raw, 0, StandardCharsets.UTF_8)?.let { return it }
        return strictDecode(raw, 0, CP932 ?: return null)
    }

    /** 严格解码（.NET `DecoderFallback.ExceptionFallback` 口径）：坏字节返回 null（原版抛异常）。 */
    private fun strictDecode(bytes: ByteArray, offset: Int, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    }.getOrNull()

    private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()
}
