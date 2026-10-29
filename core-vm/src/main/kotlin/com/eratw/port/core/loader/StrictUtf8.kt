package com.eratw.port.core.loader

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * 严格 UTF-8 解码的**零临时对象**实现（装载期的热路径）。
 *
 * ## 为什么要自己写
 *
 * 上游两条装载路径都用 `ByteArray.decodeToString(0, len, throwOnInvalidSequence = true)`
 * 解串池条目。Kotlin 在 `throwOnInvalidSequence = true` 时**每次调用**都新建一个
 * `CharsetDecoder`（`Charsets.UTF_8.newDecoder()` + 两个 `onXxx(REPORT)`）再
 * `decode(ByteBuffer.wrap(...))`（一次 `ByteBuffer` + 一次 `CharBuffer`）。
 * 实测（JDK 17，16 字节样本，`ErbcStreamAllocProfileTest` 的同款计量）：
 * **249 B / 次**，其中真正的 `String` + 其紧凑字节数组只占 ~40 B。
 *
 * 全量产物装载要取 7,968,940 次串池条目（`[erbc-alloc]` 读数；备忘命中去重后仍有
 * 1,896,921 次真解码），仅这一项的 churn 就是 ~1.9 GiB —— 与「装载期总分配 2.7 GiB、
 * 语句池段占 67%」的实测完全对得上。
 *
 * ## 等价性怎么保证
 *
 * 热路径是手写的 UTF-8 结构校验（与 `sun.nio.cs.UTF_8` 的 REPORT 口径逐条对齐：
 * 拒绝过长编码 0xC0/0xC1、拒绝代理项区间 0xD800..0xDFFF、拒绝 > U+10FFFF、
 * 拒绝 5/6 字节序列、拒绝截断的多字节序列），校验通过后走
 * `String(byte[], off, len, UTF_8)` 的一次拷贝快路径（值相同、实例不同 ——
 * `String` 是值语义，装载链上没有任何一处按字符串身份取数）。
 *
 * 非法序列的处理**不复制**上游行为，而是把它原样委托给 JDK 解码器：
 * 只有校验失败（冷路径，正常产物零次）才构造那个一次性解码器，于是抛出的异常类型、
 * 异常消息与 `decodeToString(..., throwOnInvalidSequence = true)` 逐字相同
 * （`CharacterCodingException` 家族；`ErbcStreamEquivalenceTest` 的逐字节变异差分
 * 比的就是「两条路径的异常类型 + 消息」，这条委托是让该判据继续成立的关键）。
 */
internal object StrictUtf8 {

    /**
     * 解码 `src[from, from + len)` 为 `String`。
     *
     * @throws java.nio.charset.CharacterCodingException 序列非法（与上游同类型同消息，
     *   它是 `IOException` 支，两条装载路径都照原样漏给 `ProgramLoader` 的收口）
     */
    fun decode(
        src: ByteArray,
        from: Int,
        len: Int,
    ): String {
        if (isWellFormed(src, from, len)) return String(src, from, len, Charsets.UTF_8)
        // 冷路径：让 JDK 自己产异常，类型与消息与上游逐字相同
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(src, from, len))
            .toString()
    }

    /**
     * UTF-8 结构校验（RFC 3629 的最短形式口径，与 Java `UTF_8` 解码器的 REPORT 一致）。
     *
     * 纯下标循环、零分配：调用方已经持有复用缓冲，这里不再碰堆。
     */
    fun isWellFormed(
        src: ByteArray,
        from: Int,
        len: Int,
    ): Boolean {
        var i = from
        val end = from + len
        while (i < end) {
            val b0 = src[i].toInt() and 0xFF
            if (b0 < 0x80) {
                i++
                continue
            }
            when (b0) {
                in 0xC2..0xDF -> {
                    if (i + 1 >= end) return false
                    if (!cont(src[i + 1])) return false
                    i += 2
                }

                0xE0 -> {
                    if (i + 2 >= end) return false
                    val b1 = src[i + 1].toInt() and 0xFF
                    if (b1 < 0xA0 || b1 > 0xBF) return false
                    if (!cont(src[i + 2])) return false
                    i += 3
                }

                in 0xE1..0xEC, 0xEE, 0xEF -> {
                    if (i + 2 >= end) return false
                    if (!cont(src[i + 1]) || !cont(src[i + 2])) return false
                    i += 3
                }

                0xED -> {
                    if (i + 2 >= end) return false
                    val b1 = src[i + 1].toInt() and 0xFF
                    // 0xED A0..BF 是代理项 U+D800..U+DFFF：UTF-8 不允许编码它们
                    if (b1 < 0x80 || b1 > 0x9F) return false
                    if (!cont(src[i + 2])) return false
                    i += 3
                }

                0xF0 -> {
                    if (i + 3 >= end) return false
                    val b1 = src[i + 1].toInt() and 0xFF
                    if (b1 < 0x90 || b1 > 0xBF) return false
                    if (!cont(src[i + 2]) || !cont(src[i + 3])) return false
                    i += 4
                }

                in 0xF1..0xF3 -> {
                    if (i + 3 >= end) return false
                    if (!cont(src[i + 1]) || !cont(src[i + 2]) || !cont(src[i + 3])) return false
                    i += 4
                }

                0xF4 -> {
                    if (i + 3 >= end) return false
                    val b1 = src[i + 1].toInt() and 0xFF
                    // > U+10FFFF
                    if (b1 < 0x80 || b1 > 0x8F) return false
                    if (!cont(src[i + 2]) || !cont(src[i + 3])) return false
                    i += 4
                }

                // 0x80..0xC1（孤立续字节 + 过长编码）、0xF5..0xFF（越界）一律非法
                else -> return false
            }
        }
        return true
    }

    private fun cont(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v in 0x80..0xBF
    }
}
