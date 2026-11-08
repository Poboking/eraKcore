package com.eratw.port.vmdbg

import com.eratw.port.vmdbg.SnapSidecar.longArrayOf
import com.eratw.port.vmdbg.SnapSidecar.longOf
import com.eratw.port.vmdbg.SnapSidecar.strOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SnapSidecar] 纯层用例（MODERN-T3 R1）：单行 JSON 解析 / 容器探测 / schema 校验 /
 * expand / diff。全部离线合成输入，不依赖 MiniCorpus 与 `ERATW_*`。
 */
class SnapSidecarTest {

    // ------------------------------------------------------------------
    // 合成文档
    // ------------------------------------------------------------------

    private fun rngOf(size: Int, salt: Int = 0): String =
        "[" + (0 until size).joinToString(",") { ((it * 7 + 3 + salt) % 100000).toString() } + "]"

    private fun sidecar(
        format: String = "1",
        createdAt: String = "\"2026-11-08T10:00:00+08:00\"",
        randSeed: String = "20260226",
        rngState: String = rngOf(SnapSidecar.RNG_STATE_LEN),
        consoleTail: String = "[\"mini-pass-ok\"]",
        funcStack: String = "[\"MINI_PASS\"]",
        funcDepth: String = "0",
        extra: String = "",
    ): String = buildString {
        append("{\"format\":").append(format)
        append(",\"createdAt\":").append(createdAt)
        append(",\"randSeed\":").append(randSeed)
        append(",\"rngState\":").append(rngState)
        append(",\"consoleTail\":").append(consoleTail)
        append(",\"funcStack\":").append(funcStack)
        append(",\"funcDepth\":").append(funcDepth)
        if (extra.isNotEmpty()) append(',').append(extra)
        append('}')
    }

    private fun parseOk(json: String): SnapSidecar.JsonValue.Obj = SnapSidecar.parse(json)

    private fun binary(magic: Long, version: Int, checksums: Int, pad: Int = 24): ByteArray {
        val b = ByteArray(16 + pad)
        for (k in 0 until 8) b[k] = ((magic ushr (8 * k)) and 0xFFL).toByte()
        for (k in 0 until 4) b[8 + k] = ((version ushr (8 * k)) and 0xFF).toByte()
        for (k in 0 until 4) b[12 + k] = ((checksums ushr (8 * k)) and 0xFF).toByte()
        return b
    }

    private fun mainInfo(name: String, bytes: ByteArray): SnapSidecar.MainInfo =
        SnapSidecar.MainInfo(name, bytes.size.toLong(), SnapSidecar.sha256Hex(bytes), SnapSidecar.detectContainer(bytes))

    // ------------------------------------------------------------------
    // R1-1 解析
    // ------------------------------------------------------------------

    @Test
    fun `解析转义与嵌套结构`() {
        val doc = parseOk(
            "{\"s\":\"a\\\"b\\\\c\\nd\\u4e2d\",\"n\":-12,\"t\":true,\"f\":false,\"z\":null," +
                "\"a\":[1,\"x\",[2,3]],\"o\":{\"k\":\"v\"}}",
        )
        assertEquals("a\"b\\c\nd中", doc.strOf("s"))
        assertEquals(-12L, doc.longOf("n"))
        assertEquals(SnapSidecar.JsonValue.True, doc.fields["t"])
        assertEquals(SnapSidecar.JsonValue.False, doc.fields["f"])
        assertEquals(SnapSidecar.JsonValue.Null, doc.fields["z"])
        assertNull(doc.longArrayOf("a"), "[1,\"x\",[2,3]] 不是整数数组")
        val nested = doc.fields["o"] as SnapSidecar.JsonValue.Obj
        assertEquals("v", nested.strOf("k"))
    }

    @Test
    fun `行首 BOM 与空白容忍`() {
        val doc = parseOk("\uFEFF  \r\n" + sidecar())
        assertEquals(1L, doc.longOf("format"))
    }

    @Test
    fun `畸形输入抛格式异常`() {
        for (bad in listOf("", "[]", "{\"a\":1} x", "{\"s\":\"\\q\"}", "{\"a\":}", "{\"a\" 1}")) {
            assertFailsWith<SnapSidecar.SnapFormatException>("应拒绝: $bad") {
                SnapSidecar.parse(bad)
            }
        }
    }

    // ------------------------------------------------------------------
    // R1-2 容器探测
    // ------------------------------------------------------------------

    @Test
    fun `二进制魔数与版本 1808 命中`() {
        val p = SnapSidecar.detectContainer(binary(SnapSidecar.BINARY_MAGIC, SnapSidecar.BINARY_VERSION, 0))
        assertEquals(SnapSidecar.Container.BINARY, p.container)
        assertEquals(1808, p.version)
        assertTrue(p.problems.isEmpty(), p.problems.joinToString())
        assertTrue(p.warnings.isEmpty(), p.warnings.joinToString())
    }

    @Test
    fun `二进制版本不符判 problem`() {
        val p = SnapSidecar.detectContainer(binary(SnapSidecar.BINARY_MAGIC, 1807, 0))
        assertEquals(1, p.problems.size, p.problems.joinToString())
        assertTrue("1808" in p.problems[0], p.problems[0])
    }

    @Test
    fun `gzip 魔数报 binary-gzip 且校验表非零只告警`() {
        val p = SnapSidecar.detectContainer(binary(SnapSidecar.BINARY_GZIP_MAGIC, SnapSidecar.BINARY_VERSION, 3))
        assertEquals(SnapSidecar.Container.BINARY_GZIP, p.container)
        assertTrue(p.problems.isEmpty(), p.problems.joinToString())
        assertEquals(1, p.warnings.size, p.warnings.joinToString())
        assertTrue("校验表数量=3" in p.warnings[0], p.warnings[0])
    }

    @Test
    fun `文本存档按首行整数认出`() {
        val utf8 = SnapSidecar.detectContainer("\uFEFF12345\r\n1|2|3\r\n".toByteArray(Charsets.UTF_8))
        assertEquals(SnapSidecar.Container.TEXT, utf8.container)
        val utf16 = SnapSidecar.detectContainer("4321\r\nx\r\n".toByteArray(Charsets.UTF_16LE))
        assertEquals(SnapSidecar.Container.TEXT, utf16.container)
    }

    @Test
    fun `未知容器只告警并带前 16 字节 hex`() {
        val p = SnapSidecar.detectContainer(ByteArray(16) { (0x40 + it).toByte() })
        assertEquals(SnapSidecar.Container.UNKNOWN, p.container)
        assertTrue(p.problems.isEmpty(), p.problems.joinToString())
        assertEquals(1, p.warnings.size, p.warnings.joinToString())
        assertTrue("40 41" in p.headHex, p.headHex)
    }

    @Test
    fun `小端读整数`() {
        assertEquals(0x0201L, SnapSidecar.leLong(byteArrayOf(1, 2, 0, 0, 0, 0, 0, 0), 0))
        assertEquals(0x04030201, SnapSidecar.leInt(byteArrayOf(1, 2, 3, 4), 0))
    }

    // ------------------------------------------------------------------
    // R1-3 校验
    // ------------------------------------------------------------------

    @Test
    fun `合法 sidecar 全绿`() {
        val v = SnapSidecar.validate(parseOk(sidecar()))
        assertTrue(v.ok, v.problems.joinToString())
        assertEquals(1, v.format)
        assertEquals(20260226L, v.randSeed)
        assertEquals(0, v.funcDepth)
        assertEquals(625, v.rngLen)
        assertEquals(1, v.consoleTailLen)
        assertEquals(1, v.funcStackLen)
        assertEquals("2026-11-08T10:00:00+08:00", v.createdAt)
    }

    @Test
    fun `缺必需字段逐个报`() {
        val v = SnapSidecar.validate(parseOk("{}"))
        assertEquals(SnapSidecar.REQUIRED_FIELDS.size, v.problems.size, v.problems.joinToString())
        for (key in SnapSidecar.REQUIRED_FIELDS) {
            assertTrue(v.problems.any { key in it }, "缺 $key 的违规: ${v.problems}")
        }
    }

    @Test
    fun `format 非 1 报违规`() {
        val v = SnapSidecar.validate(parseOk(sidecar(format = "2")))
        assertTrue(v.problems.any { "format=2" in it }, v.problems.joinToString())
    }

    @Test
    fun `createdAt 非 ISO-8601 报违规`() {
        val v = SnapSidecar.validate(parseOk(sidecar(createdAt = "\"not-a-time\"")))
        assertTrue(v.problems.any { "createdAt" in it }, v.problems.joinToString())
    }

    @Test
    fun `rngState 长度只认 0 或 625`() {
        val short = SnapSidecar.validate(parseOk(sidecar(rngState = rngOf(624))))
        assertTrue(short.problems.any { "rngState 长度=624" in it }, short.problems.joinToString())

        val empty = SnapSidecar.validate(parseOk(sidecar(rngState = "[]")))
        assertTrue(empty.ok, empty.problems.joinToString())
        assertEquals(0, empty.rngLen)

        val nonInt = SnapSidecar.validate(parseOk(sidecar(rngState = "[1,\"x\"]")))
        assertTrue(nonInt.problems.any { "非整数项" in it }, nonInt.problems.joinToString())
    }

    @Test
    fun `funcDepth 只允许 -1 及以上整数`() {
        val minusOne = SnapSidecar.validate(parseOk(sidecar(funcDepth = "-1")))
        assertTrue(minusOne.ok, minusOne.problems.joinToString())
        assertEquals(-1, minusOne.funcDepth)

        val minusTwo = SnapSidecar.validate(parseOk(sidecar(funcDepth = "-2")))
        assertTrue(minusTwo.problems.any { "funcDepth=-2" in it }, minusTwo.problems.joinToString())

        val notInt = SnapSidecar.validate(parseOk(sidecar(funcDepth = "\"0\"")))
        assertTrue(notInt.problems.any { "funcDepth 不是整数" in it }, notInt.problems.joinToString())
    }

    @Test
    fun `consoleTail 与 funcStack 必须是字符串数组`() {
        val tail = SnapSidecar.validate(parseOk(sidecar(consoleTail = "[1,2]")))
        assertTrue(tail.problems.any { "consoleTail" in it }, tail.problems.joinToString())
        val stack = SnapSidecar.validate(parseOk(sidecar(funcStack = "\"MINI\"")))
        assertTrue(stack.problems.any { "funcStack" in it }, stack.problems.joinToString())
    }

    @Test
    fun `未知键放行且 commit 非字符串只告警`() {
        val ok = SnapSidecar.validate(parseOk(sidecar(extra = "\"futureKey\":[1,2,3]")))
        assertTrue(ok.ok, ok.problems.joinToString())

        val warn = SnapSidecar.validate(parseOk(sidecar(extra = "\"commit\":5")))
        assertTrue(warn.ok, warn.problems.joinToString())
        assertTrue(warn.warnings.any { "commit" in it }, warn.warnings.joinToString())
        assertNull(warn.commit)
    }

    // ------------------------------------------------------------------
    // R1-4 expand / diff
    // ------------------------------------------------------------------

    @Test
    fun `expand 输出主文件事实与逆序调用栈`() {
        val doc = parseOk(
            sidecar(
                funcStack = "[\"OUTER\",\"INNER\"]",
                consoleTail = "[\"第一行\",\"第二行\"]",
                extra = "\"commit\":\"abc123\"",
            ),
        )
        val bytes = "12345\n".toByteArray(Charsets.UTF_8)
        val lines = SnapSidecar.expand(doc, "snap.dbg.json", mainInfo("snap.dbg", bytes))
        assertEquals("status=PASS", lines[0])
        assertTrue(lines.contains("container=text"), lines.joinToString("\n"))
        assertTrue(lines.contains("commit=abc123"), lines.joinToString("\n"))
        assertTrue(lines.contains("rngState.len=625"), lines.joinToString("\n"))
        assertTrue(lines.any { it.startsWith("rngState.digest=") }, lines.joinToString("\n"))
        assertTrue(lines.contains("vars=not-expanded(offline; use restore)"), lines.joinToString("\n"))
        val stackAt = lines.indexOf("--- func stack (top first) ---")
        assertTrue(stackAt >= 0, lines.joinToString("\n"))
        assertEquals("INNER", lines[stackAt + 1], "栈顶在前 = 逆序")
        assertEquals("OUTER", lines[stackAt + 2])
        val tailAt = lines.indexOf("--- console tail (oldest first) ---")
        assertEquals("第一行", lines[tailAt + 1])
        assertEquals("第二行", lines[tailAt + 2])
    }

    @Test
    fun `expand 违规文档报 INVALID 且问题行齐全`() {
        val doc = parseOk(sidecar(rngState = rngOf(624)))
        val lines = SnapSidecar.expand(doc, "x.json", mainInfo("x.dbg", "1\n".toByteArray()))
        assertEquals("status=INVALID", lines[0])
        assertTrue(lines.any { it.startsWith("problem=") && "624" in it }, lines.joinToString("\n"))
    }

    @Test
    fun `diff 同一快照 same 为真`() {
        val doc = parseOk(sidecar())
        val bytes = "12345\n".toByteArray(Charsets.UTF_8)
        val lines = SnapSidecar.diff(doc, doc, mainInfo("a.dbg", bytes), mainInfo("b.dbg", bytes))
        assertEquals("status=PASS", lines[0])
        assertTrue(lines.contains("main.bytesSame=true"), lines.joinToString("\n"))
        assertTrue(lines.contains("funcStack.len.a=1 funcStack.len.b=1 same=true"), lines.joinToString("\n"))
        assertTrue(lines.contains("consoleTail.len.a=1 consoleTail.len.b=1 same=true"), lines.joinToString("\n"))
        assertEquals("same=true", lines.last(), lines.joinToString("\n"))
    }

    @Test
    fun `diff 逐字段与数组定位首个差异`() {
        val a = parseOk(sidecar(funcStack = "[\"A\",\"B\"]", consoleTail = "[\"l1\",\"l2\"]"))
        val b = parseOk(sidecar(funcStack = "[\"A\",\"C\"]", consoleTail = "[\"l1\",\"l3\"]", randSeed = "7"))
        val rngB = LongArray(SnapSidecar.RNG_STATE_LEN) { it.toLong() * 7 + 3 }
        rngB[5] = -1L
        val b2 = parseOk(sidecar(funcStack = "[\"A\",\"C\"]", consoleTail = "[\"l1\",\"l3\"]", randSeed = "7", rngState = rngB.joinToString(",", "[", "]")))
        val lines = SnapSidecar.diff(a, b2, mainInfo("a.dbg", "1\n".toByteArray()), mainInfo("b.dbg", "2\n".toByteArray()))
        val text = lines.joinToString("\n")
        assertTrue("main.bytesSame=false" in text, text)
        assertTrue("randSeed.a=20260226 randSeed.b=7 same=false" in text, text)
        assertTrue("funcStack.firstDiff=1 a=B b=C" in text, text)
        assertTrue("consoleTail.firstDiff=1 a=l2 b=l3" in text, text)
        assertTrue("rngState.diffCount=1 firstDiffIndex=5" in text, text)
        assertEquals("same=false", lines.last(), text)
        // 另一份 b（同字段不同值但 rng 相同）也应 same=false，且不误报数组差异
        val bPlain = SnapSidecar.diff(a, b, mainInfo("a.dbg", "1\n".toByteArray()), mainInfo("b.dbg", "2\n".toByteArray()))
        val plainText = bPlain.joinToString("\n")
        assertTrue("rngState.len.a=625 rngState.len.b=625 same=true" in plainText, plainText)
        assertTrue("same=false" in bPlain.last(), plainText)
    }

    @Test
    fun `diff 一侧 sidecar 缺席按空面比较`() {
        val doc = parseOk(sidecar())
        val bytes = "1\n".toByteArray()
        val lines = SnapSidecar.diff(doc, null, mainInfo("a.dbg", bytes), mainInfo("b.dbg", bytes))
        val text = lines.joinToString("\n")
        assertTrue("sidecar.a=present" in text, text)
        assertTrue("sidecar.b=absent" in text, text)
        assertTrue("format.a=1 format.b=<absent> same=false" in text, text)
        assertEquals("same=false", lines.last(), text)
    }

    @Test
    fun `sha256 与 RNG 摘要稳定`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SnapSidecar.sha256Hex("abc".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SnapSidecar.rngDigest(LongArray(0)),
        )
        assertNotNull(SnapSidecar.rngDigest(longArrayOf(1, 2, 3)))
        assertTrue(SnapSidecar.rngDigest(longArrayOf(1, 2, 3)) != SnapSidecar.rngDigest(longArrayOf(1, 2, 4)))
    }
}
