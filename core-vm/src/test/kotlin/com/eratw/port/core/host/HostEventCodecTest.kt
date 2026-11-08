package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `HostEventCodec` v1 的 golden 锁（R3）：固定键序、转义、坏输入七类。
 *
 * 判据来自 CONTRACT-MODERN-T3 §3.1：版本不符/缺字段/值非字符串一律拒收；未知顶层键与
 * 未知 `type` 放行。编码字节形态钉死，保证真机落盘与桌面分析器读同一行。
 */
class HostEventCodecTest {

    private fun event(
        seq: Long = 7L,
        atMs: Long = 1234L,
        type: String = "turn.end",
        payload: Map<String, String> = mapOf("ms" to "42"),
    ) = HostEvent(seq, atMs, type, payload)

    @Test
    fun `编码键序固定为 v_seq_t_type_payload`() {
        val line = HostEventCodec.encode(event())
        assertEquals("""{"v":1,"seq":7,"t":1234,"type":"turn.end","payload":{"ms":"42"}}""", line)
    }

    @Test
    fun `空载荷编码为空对象且解码还原`() {
        val e = event(payload = emptyMap())
        val line = HostEventCodec.encode(e)
        assertEquals("""{"v":1,"seq":7,"t":1234,"type":"turn.end","payload":{}}""", line)
        val back = HostEventCodec.decode(line)
        assertEquals(e, back)
    }

    @Test
    fun `引号反斜杠换行与中文往返逐字相同`() {
        val payload = linkedMapOf(
            "a\"b" to "c\\d",
            "line" to "x\ny\rz\t",
            "中文键" to "中文值",
        )
        val e = event(type = "error", payload = payload)
        val line = HostEventCodec.encode(e)
        val back = HostEventCodec.decode(line)
        assertEquals(e, back)
        assertEquals(payload.toList(), back.payload.toList(), "键序必须保序")
    }

    @Test
    fun `控制字符走 u 转义且可解回`() {
        val e = event(payload = mapOf("k" to "\u0001\u001f"))
        val line = HostEventCodec.encode(e)
        assertTrue(line.contains("\\u0001") && line.contains("\\u001f"), "控制字符必须转义: $line")
        assertEquals(e.payload, HostEventCodec.decode(line).payload)
    }

    @Test
    fun `未知 type 放行`() {
        val line = """{"v":1,"seq":1,"t":2,"type":"future.event","payload":{"x":"1"}}"""
        val e = HostEventCodec.decode(line)
        assertEquals("future.event", e.type)
        assertEquals(mapOf("x" to "1"), e.payload)
    }

    @Test
    fun `未知顶层键忽略但其余字段照读`() {
        val line = """{"v":1,"future":{"a":[1,2,{"b":"c"}]},"seq":3,"t":4,"type":"t","payload":{}}"""
        val e = HostEventCodec.decode(line)
        assertEquals(3L, e.seq)
        assertEquals(4L, e.atMs)
        assertEquals("t", e.type)
    }

    @Test
    fun `版本不符拒收且消息点名 schemaVersion`() {
        val ex = assertFailsWith<HostEventFormatException> {
            HostEventCodec.decode("""{"v":2,"seq":1,"t":2,"type":"t","payload":{}}""")
        }
        assertTrue(ex.message.orEmpty().contains("schemaVersion"), ex.message.orEmpty())
    }

    @Test
    fun `缺 v 字段拒收`() {
        assertFailsWith<HostEventFormatException> {
            HostEventCodec.decode("""{"seq":1,"t":2,"type":"t","payload":{}}""")
        }
    }

    @Test
    fun `缺 seq 或 t 或 type 逐项拒收`() {
        assertFailsWith<HostEventFormatException> {
            HostEventCodec.decode("""{"v":1,"t":2,"type":"t","payload":{}}""")
        }
        assertFailsWith<HostEventFormatException> {
            HostEventCodec.decode("""{"v":1,"seq":1,"type":"t","payload":{}}""")
        }
        assertFailsWith<HostEventFormatException> {
            HostEventCodec.decode("""{"v":1,"seq":1,"t":2,"payload":{}}""")
        }
    }

    @Test
    fun `seq 非整数拒收`() {
        assertFailsWith<HostEventFormatException> {
            HostEventCodec.decode("""{"v":1,"seq":"1","t":2,"type":"t","payload":{}}""")
        }
    }

    @Test
    fun `payload 值非字符串拒收`() {
        assertFailsWith<HostEventFormatException> {
            HostEventCodec.decode("""{"v":1,"seq":1,"t":2,"type":"t","payload":{"k":1}}""")
        }
    }

    @Test
    fun `payload 缺失按空表`() {
        val e = HostEventCodec.decode("""{"v":1,"seq":1,"t":2,"type":"t"}""")
        assertEquals(emptyMap<String, String>(), e.payload)
    }

    @Test
    fun `非对象与截断行拒收`() {
        assertFailsWith<HostEventFormatException> { HostEventCodec.decode("[]") }
        assertFailsWith<HostEventFormatException> { HostEventCodec.decode("""{"v":1,"seq":1""") }
        assertFailsWith<HostEventFormatException> { HostEventCodec.decode("""{"v":1,"seq":1,"t":2,"type":"t","payload":{"k":"v""") }
    }

    @Test
    fun `空白类型编码期拒收`() {
        assertFailsWith<IllegalArgumentException> {
            HostEventCodec.encode(event(type = "  "))
        }
    }
}
