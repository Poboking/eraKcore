package com.eratw.port.core.data.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 编码探测链（`EncodingHandler.DetectEncoding`，EncodingHandler.cs:15-41）：
 * BOM UTF-8 → UTF-16/32 → 严格 UTF-8 → 严格 CP932。CSV 资源表与 LOADTEXT 共用这条链。
 */
class DetectedTextDecoderTest {

    @Test
    fun `带 BOM 的 UTF-8 去掉 BOM`() {
        val raw = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "名前,img.png".toByteArray()
        assertEquals("名前,img.png", DetectedTextDecoder.decode(raw))
    }

    @Test
    fun `非 UTF-8 字节回退 CP932`() {
        // 「あ」的 Shift_JIS = 82 A0，严格 UTF-8 必失败（:33-40）
        val raw = byteArrayOf(0x82.toByte(), 0xA0.toByte())
        assertEquals("あ", DetectedTextDecoder.decode(raw))
    }

    @Test
    fun `无 BOM 的纯 ASCII 走严格 UTF-8`() {
        assertEquals("A_1,a.png", DetectedTextDecoder.decode("A_1,a.png".toByteArray()))
    }

    @Test
    fun `空字节数组解成空串`() {
        assertEquals("", DetectedTextDecoder.decode(ByteArray(0)))
    }

    @Test
    fun `CP932 不可用也不崩`() {
        // 孤立的首字节（0x82 是 Shift_JIS 双字节首字节）：严格 UTF-8 与严格 CP932 都会失败
        assertNull(DetectedTextDecoder.decode(byteArrayOf(0x82.toByte())))
    }
}
