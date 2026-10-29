package com.eratw.port.vmdbg

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * trace 命令（CONTRACT-DBG1 路2）：环形缓冲装探针 → 跑完输出 `行号 语句摘要 耗时`。
 * **本文件与 Trace.kt 一样依赖路1 语句探针（ProcessRuntime.statementProbe），
 * 本 worktree 未合流前编译阻塞，属预期状态**；其余命令的单测已在写本文件之前跑绿。
 */
class TraceTest {

    private lateinit var tmp: Path
    private lateinit var built: MiniCorpus.Built
    private lateinit var work: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-trace-test")
        built = MiniCorpus.build(tmp)
        work = tmp.resolve("work")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val out = PrintStream(buf, true, Charsets.UTF_8)
        val code = Cli.run(arrayOf(*args), out, System.err)
        return code to buf.toString(Charsets.UTF_8)
    }

    @Test
    fun traceCollectsStatementEntriesWithLineBriefAndElapsed() {
        val (code, text) = run(*MiniCorpus.args(built, work, "trace", "MINI_PASS"))
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        // 轨迹段：MINI_PASS 的 PRINTL 语句有一条轨迹，行号 + 摘要 + 耗时ns 三段齐全
        val traceLines = text.lines().filter { it.startsWith("--- trace") }
        assertTrue(traceLines.size == 1, text)
        val body = text.lines().dropWhile { !it.startsWith("--- trace") }.drop(1).filter { it.isNotBlank() }
        assertTrue(body.isNotEmpty(), "轨迹为空：$text")
        assertTrue(body.any { "PrintStmt" in it || "PRINTL" in it }, "PRINTL 应有轨迹：$body")
        assertTrue(body.all { it.split(" ").size >= 3 && it.split(" ").last().endsWith("ns") }, "每条 = 行号 摘要 耗时ns：$body")
    }

    @Test
    fun traceRingDropsOldestWhenOverCapacity() {
        // MINI_ARGS 体 = PRINTVL + PRINTSL + SAVEGLOBAL（3 条语句）；capacity=2 必丢最旧 1 条。
        // （首版盲写用例 trace 的是 MINI_PASS——体只有 1 条语句，capacity=2 永不触发丢弃，
        // 合流后首跑即红；组长改钉为本形。）
        val (code, text) = run(*MiniCorpus.args(built, work, "trace", "MINI_ARGS", "--capacity", "2", "--args", "7,hello"))
        assertEquals(0, code, text)
        assertTrue("dropped-oldest " in text, "应报告丢最旧计数：$text")
        val head = text.lineSequence().firstOrNull { it.startsWith("--- trace") } ?: ""
        val dropped = head.substringAfter("dropped-oldest ").substringBefore(")").toLongOrNull() ?: -1L
        val kept = head.substringAfter("(").substringBefore(" entries").toIntOrNull() ?: -1
        assertTrue(dropped > 0, "capacity=2 应丢最旧：$head")
        assertEquals(2, kept, "满容量保留最新 2 条：$head")
    }

    @Test
    fun traceWritesToFileWithOutOption() {
        val file = tmp.resolve("trace-out.txt")
        val (code, text) = run(*MiniCorpus.args(built, work, "trace", "MINI_PASS", "--out", file.toString()))
        assertEquals(0, code, text)
        assertTrue("trace-file=" in text, text)
        val content = Files.readString(file, Charsets.UTF_8)
        assertTrue(content.contains("--- trace"), content)
        assertTrue(content.lineSequence().any { "mini-pass" in it.lowercase() || "Print" in it }, content)
    }
}
