package com.eratw.port.e2e.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `.replay` 解析器的负向用例（契约 §1 冻结格式）。
 *
 * 口径：**未知一律报错**——未知头部键、未知区段、未知指令、坏下标、坏期望值都不许静默忽略；
 * 错误消息必须带来源文件与行号，否则用例红得看不出是哪一行坏的。
 */
class ReplayCaseParserTest {

    private fun parse(text: String) = ReplayCaseParser.parse("t.replay", text)

    private fun assertParseError(text: String, contains: String) {
        val e = assertFailsWith<ReplayParseException> { parse(text) }
        assertTrue(
            e.message!!.contains(contains),
            "错误消息未点名「$contains」：${e.message}",
        )
        assertTrue(e.message!!.startsWith("t.replay:"), "错误消息缺来源文件与行号：${e.message}")
    }

    private val minimal = """
        case: min
        target: MIN
        anchor: reference/emuera-cs/a.cs:1
    """.trimIndent()

    @Test
    fun `最小合法用例`() {
        val c = parse(
            minimal + "\n--- inputs\nsubmit\nsubmit 123\nsubmit-skip 42\nclick OK 2\npause 5\n" +
                "--- expect-console\nA\nB\n--- expect-vars\nRESULT:0 = 7\nRESULTS:0 = \"X\"\n",
        )
        assertEquals("min", c.name)
        assertEquals("MIN", c.target)
        assertEquals(listOf("reference/emuera-cs/a.cs:1"), c.anchors)
        assertEquals(false, c.expectBlocked)
        assertEquals(5, c.inputs.size)
        assertEquals(ReplayInput.Submission("", mesSkip = false), c.inputs[0])
        assertEquals(ReplayInput.Submission("123", mesSkip = false), c.inputs[1])
        assertEquals(ReplayInput.Submission("42", mesSkip = true), c.inputs[2])
        assertEquals(ReplayInput.Click("OK", 2), c.inputs[3])
        assertEquals(ReplayInput.Pause(5), c.inputs[4])
        assertEquals(listOf("A", "B"), c.expectConsole)
        assertEquals(2, c.expectVars.size)
        assertEquals(VarExpect("RESULT", longArrayOf(0), null, 7, "RESULT:0 = 7"), c.expectVars[0])
        assertTrue(c.expectVars[1].isStr)
        assertEquals("X", c.expectVars[1].strValue)
    }

    @Test
    fun `expect blocked 被读成阻塞期望`() {
        val c = parse(minimal + "\nexpect: blocked\n")
        assertTrue(c.expectBlocked)
    }

    @Test
    fun `缺 case`() {
        assertParseError("target: MIN\nanchor: a.cs:1\n", "case")
    }

    @Test
    fun `缺 target`() {
        assertParseError("case: c\nanchor: a.cs:1\n", "target")
    }

    @Test
    fun `缺 anchor`() {
        assertParseError("case: c\ntarget: MIN\n", "anchor")
    }

    @Test
    fun `未知头部键`() {
        assertParseError(minimal + "\nupdate: true\n", "未知头部键")
    }

    @Test
    fun `expect 只允许 blocked`() {
        assertParseError(minimal + "\nexpect: skip\n", "blocked")
    }

    @Test
    fun `未知区段标记`() {
        assertParseError(minimal + "\n--- expect-output\nX\n", "未知区段标记")
    }

    @Test
    fun `区段重复`() {
        assertParseError(minimal + "\n--- inputs\nsubmit\n--- inputs\nsubmit\n", "重复")
    }

    @Test
    fun `未知指令不许静默忽略`() {
        assertParseError(minimal + "\n--- inputs\nenterkey\n", "未知指令")
    }

    @Test
    fun `click 按钮位非整数`() {
        assertParseError(minimal + "\n--- inputs\nclick OK left\n", "按钮位不是整数")
    }

    @Test
    fun `click 按钮位越界`() {
        assertParseError(minimal + "\n--- inputs\nclick OK 4\n", "1/2/3")
    }

    @Test
    fun `click 缺按钮文本`() {
        assertParseError(minimal + "\n--- inputs\nclick\n", "click 缺少按钮文本")
    }

    @Test
    fun `pause 缺毫秒`() {
        assertParseError(minimal + "\n--- inputs\npause soon\n", "pause")
    }

    @Test
    fun `pause 拒绝负数`() {
        assertParseError(minimal + "\n--- inputs\npause -1\n", "负")
    }

    @Test
    fun `变量期望缺等号`() {
        assertParseError(minimal + "\n--- expect-vars\nRESULT:0\n", "缺 `=`")
    }

    @Test
    fun `变量下标不是整数`() {
        assertParseError(minimal + "\n--- expect-vars\nRESULT:x = 1\n", "下标不是整数")
    }

    @Test
    fun `字符串期望引号未闭合`() {
        assertParseError(minimal + "\n--- expect-vars\nRESULTS:0 = \"X\n", "引号未闭合")
    }

    @Test
    fun `整数期望不是整数`() {
        assertParseError(minimal + "\n--- expect-vars\nRESULT:0 = 12x\n", "不是整数")
    }

    @Test
    fun `转义与未定义转义原样保留`() {
        assertEquals("A\nB\\C", ReplayCaseParser.unescape("A\\nB\\\\C"))
        // 未定义的转义按原文保留：`\e` 这类原版段内标记才投得进去（EmueraConsole.cs:1435-1438）。
        assertEquals("\\e", ReplayCaseParser.unescape("\\e"))
    }

    @Test
    fun `头部的注释与空行不参与解析`() {
        val c = parse("# 头注释\n\ncase: c\ntarget: T\n# 另一行注释\nanchor: a.cs:1\n\n--- inputs\nsubmit 1\n")
        assertEquals("c", c.name)
        assertEquals(1, c.inputs.size)
    }

    @Test
    fun `段内注释不是注释`() {
        assertParseError(minimal + "\n--- inputs\n# 段内注释\n", "未知指令")
    }

    @Test
    fun `尾部空行不进入 expect-console`() {
        val c = parse(minimal + "\n--- expect-console\nA\n\n")
        assertEquals(listOf("A"), c.expectConsole)
    }

    @Test
    fun `CRLF 与 BOM 可读`() {
        val c = parse("\uFEFFcase: c\r\ntarget: T\r\nanchor: a.cs:1\r\n--- inputs\r\nsubmit 7\r\n")
        assertEquals("c", c.name)
        assertEquals(ReplayInput.Submission("7", mesSkip = false), c.inputs[0])
    }
}
