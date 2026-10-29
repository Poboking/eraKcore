package com.eratw.port.vmdbg

import com.eratw.port.erbcc.compile.CorpusCompile
import com.eratw.port.erbcc.compile.ErbcArtifacts
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
 * log（DBG4 P3）的 CLI 机制锁 —— 合成 mini 语料（MiniCorpus 基座 + 追加 RAND/写 MONEY 的
 * `MINI_LOGRAND`，不触碰共享 fixture），断言：
 * - `--tags event`：函数进出事件（func-enter / func-exit 成对，fn/key 落 payload）；
 * - `--tags rng`：出号事件（value = nextUInt32 层的原始 u32、seq = 挂口后累计）；
 * - `--tags var --watch MONEY`：write-int 事件带 old→new；watch 名单 miss → 零事件；
 * - `--max N`：截断 + truncated=true + 执行照常完成；
 * - 用法面：缺 `--` 转发段 / --tags var 无 --watch / 未知 tag = 退出码 1。
 *
 * 行解析注意：payload 是嵌套对象，[Jsonl.parseLine] 只认平面值（内层 `}` 会让它返回
 * null），本文件用正则提取头三字段 + 原样保留 payload 做子串断言。
 */
class CmdLogTest {

    private lateinit var tmp: Path
    private lateinit var built: MiniCorpus.Built
    private lateinit var work: Path

    companion object {
        /** MINI_LOGRAND：RAND 出号 + 写 MONEY —— rng / var 两个 tag 的数据面。 */
        private val EXTRA_ERB = """
            @MINI_LOGRAND
            	MONEY = RAND:100
            	PRINTVL MONEY
        """.trimIndent()

        /** 行头提取：seq / tag / event（payload 嵌套对象，整体原样留在 raw）。 */
        private val LINE_RX = Regex("""^\{"seq":(\d+),"tag":"([^"]+)","event":"([^"]+)","payload":(.*)\}$""")
    }

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-log-test")
        val game = tmp.resolve("game")
        val erbDir = game.resolve("ERB")
        Files.createDirectories(erbDir)
        Files.writeString(game.resolve("emuera.config"), "")
        Files.writeString(erbDir.resolve("mini.erh"), MiniCorpus.MINI_ERH)
        Files.writeString(erbDir.resolve("mini.erb"), MiniCorpus.MINI_ERB + "\n" + EXTRA_ERB + "\n")
        val result = CorpusCompile.compile(erbDir)
        check(result.ok) { "mini 语料编译失败: ${result.failures.joinToString("; ")}" }
        val outDir = tmp.resolve("erbc-out")
        ErbcArtifacts.write(outDir, result.program!!, erbDir, result.sourceFiles, result.stats, result.bootMetadata)
        built = MiniCorpus.Built(game, outDir.resolve("erbc.v1"))
        work = tmp.resolve("work")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val ebuf = ByteArrayOutputStream()
        val code = Cli.run(arrayOf(*args), out = PrintStream(buf, true, Charsets.UTF_8), err = PrintStream(ebuf, true, Charsets.UTF_8))
        return code to buf.toString(Charsets.UTF_8) + ebuf.toString(Charsets.UTF_8)
    }

    /** (seq, tag, event, payloadRaw) 四元组；行序保持。 */
    private fun logLines(outPath: Path): List<List<String>> =
        Files.readAllLines(outPath, Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                LINE_RX.matchEntire(line.trim())?.let { m ->
                    listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
                }
            }

    private fun defaultLog(): Path = work.resolve("dbg-log.jsonl")

    @Test
    fun eventTagRecordsFunctionEnterExitPairs() {
        val before = built.treeSnapshot()
        val (code, text) = run(
            *MiniCorpus.args(built, work, "log", "--tags", "event", "--", "call", "MINI_PASS"),
        )
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("--- log summary ---" in text, text)
        val lines = logLines(defaultLog())
        assertEquals(setOf("event"), lines.map { it[1] }.toSet(), "tag 过滤成立")
        val enters = lines.filter { it[2] == "func-enter" }
        val exits = lines.filter { it[2] == "func-exit" }
        assertEquals(enters.size, exits.size, "进出成对: ${lines.map { it[2] }}")
        val selfEnter = enters.firstOrNull { it[3].contains("\"fn\":\"MINI_PASS\"") }
        assertTrue(selfEnter != null, "payload 应含 MINI_PASS 的 fn/key: $lines")
        assertTrue(selfEnter!!.getOrNull(3)?.contains("\"key\":\"MINI_PASS\"") == true, selfEnter[3])
        assertTrue(exits.firstOrNull { it[3].contains("MINI_PASS") }?.getOrNull(3)?.contains("\"outcome\":\"EXIT\"") == true, "exit 带 outcome")
        // seq 全局递增
        val seqs = lines.map { it[0].toLong() }
        assertEquals((1L..lines.size.toLong()).toList(), seqs, "seq 从 1 连续递增")
        assertEquals(before, built.treeSnapshot(), "游戏树不得有任何新文件")
    }

    @Test
    fun rngTagRecordsDrawsAndDoesNotChangeExecution() {
        val (code, text) = run(
            *MiniCorpus.args(built, work, "log", "--tags", "rng", "--", "call", "MINI_LOGRAND"),
        )
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        val lines = logLines(defaultLog())
        assertTrue(lines.isNotEmpty(), "应有出号事件")
        assertEquals(lines.map { it[1] }.toSet(), setOf("rng"))
        val draws = lines.filter { it[2] == "draw" }
        assertTrue(draws.isNotEmpty(), "RAND:100 至少一次出号: $lines")
        // 探针挂点在 nextUInt32（RngProbe KDoc）：value = 原始 u32（Long 低 32 位承载）
        for (d in draws) {
            val payload = d[3]
            val value = payload.substringAfter("\"value\":").substringBefore(',').toLongOrNull()
            assertTrue(value != null && value in 0..0xFFFFFFFFL, "出号值域 [0, 2^32): $payload")
            assertTrue("\"seq\":" in payload, payload)
        }
        // MINI_LOGRAND 正常完成（挂口不改执行：PASS + 出号事件并存）
        assertTrue("status=PASS" in text, text)
    }

    @Test
    fun varTagWatchRecordsOldNewAndMissYieldsNothing() {
        val (code, text) = run(
            *MiniCorpus.args(built, work, "log", "--tags", "var", "--watch", "MONEY", "--", "call", "MINI_LOGRAND"),
        )
        assertEquals(0, code, text)
        val lines = logLines(defaultLog())
        val writes = lines.filter { it[2] == "write-int" }
        assertEquals(1, writes.size, "MINI_LOGRAND 恰一次 MONEY 写入: $lines")
        val payload = writes[0][3]
        assertTrue("\"name\":\"MONEY\"" in payload, payload)
        assertTrue("\"old\":\"0\"" in payload, "MONEY 启动缺省 0 → old=0: $payload")
        assertTrue("\"new\":" in payload, payload)
        assertTrue("\"fn\":" in payload, payload)

        // miss：名单外名字零事件零记录
        val (code2, _) = run(
            *MiniCorpus.args(built, work, "log", "--tags", "var", "--watch", "NOPE_XYZ", "--", "call", "MINI_LOGRAND"),
        )
        assertEquals(0, code2)
        assertEquals(0, logLines(defaultLog()).size, "名单 miss 不得有任何记录")
    }

    @Test
    fun maxTruncatesRecordingButCallCompletes() {
        // MINI_PASS = enter + exit 恰 2 条；--max 1 → 1 条落盘 + truncated=true
        val (code, text) = run(
            *MiniCorpus.args(built, work, "log", "--tags", "event", "--max", "1", "--", "call", "MINI_PASS"),
        )
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, "执行照常完成")
        assertTrue("truncated=true" in text, text)
        assertTrue("events=1" in text, text)
        assertEquals(1, logLines(defaultLog()).size, "恰好 1 条后停笔")
    }

    @Test
    fun usageErrors() {
        val (code, text) = run(*MiniCorpus.args(built, work, "log", "--tags", "event", "call", "MINI_PASS"))
        assertEquals(1, code, "缺 -- 转发段: $text")
        assertTrue("'--' 之后只支持 call" in text || "log 需要" in text, text)

        val (code2, text2) = run(*MiniCorpus.args(built, work, "log", "--tags", "var", "--", "call", "MINI_PASS"))
        assertEquals(1, code2, "--tags var 缺 --watch: $text2")
        assertTrue("--tags var 需要 --watch" in text2, text2)

        val (code3, text3) = run(*MiniCorpus.args(built, work, "log", "--tags", "nope", "--", "call", "MINI_PASS"))
        assertEquals(1, code3, "未知 tag: $text3")
        assertTrue("未知 --tags 值" in text3, text3)
    }

    @Test
    fun outOptionWritesToGivenFile() {
        val custom = tmp.resolve("custom").resolve("events.jsonl")
        val (code, text) = run(
            *MiniCorpus.args(built, work, "log", "--tags", "event", "--out", custom.toString(), "--", "call", "MINI_PASS"),
        )
        assertEquals(0, code, text)
        assertTrue(Files.isRegularFile(custom), "自定义 --out 生效: $text")
        assertTrue("out=$custom" in text, text)
        assertTrue(logLines(custom).isNotEmpty())
    }
}
