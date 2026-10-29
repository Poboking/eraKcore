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
 * set / cheat（DBG4 P1）的机制锁：
 * - set 整数 / 字符串（带引号）→ 写入 + 立即回读 old→new（注入面）；
 * - cheat money/day 落 MONEY[0] / DAY[0]（SystemLayout.int1D 注册面，G_INT1 可写）；
 * - cheat raw 与 set 同路径；
 * - 审计 trail：<work>/audit.jsonl 每次成功注入追加一行，字段序 ts/op/name/idx/old/new；
 * - 未定义名 → status=CODEEE + 退出码 2，不抛裸栈；未知配方 → 用法错误退出码 1；
 * - **会话独立性**：CLI 每命令新装配，set 的效果只活在当次会话（var 交叉读见启动缺省）
 *   —— 注入的真正消费面是同进程组合（P2/P3 的包装）与下波的 DevTools 接线；
 * - **语料只读铁律**：set/cheat 全程不碰游戏树（写只进内存变量池）。
 *
 * 断言面用 MONEY/DAY 而不是 GLOBAL_TOTAL：ERH 静态 `#DIM` 走 registry.def 分支
 * （isWritable=false，「是常量，不能赋值」），不可写 —— 静态全局本来就是注入禁区。
 */
class CmdSetTest {

    private lateinit var tmp: Path
    private lateinit var built: MiniCorpus.Built
    private lateinit var work: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-set-test")
        built = MiniCorpus.build(tmp)
        work = tmp.resolve("work")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val ebuf = ByteArrayOutputStream()
        val out = PrintStream(buf, true, Charsets.UTF_8)
        val code = Cli.run(arrayOf(*args), out, PrintStream(ebuf, true, Charsets.UTF_8))
        // 用法错误（CliError）走 stderr；stdout/stderr 合并断言，两种出口都盖到
        return code to buf.toString(Charsets.UTF_8) + ebuf.toString(Charsets.UTF_8)
    }

    private fun auditLines(): List<Map<String, String>> {
        val f = work.resolve("audit.jsonl")
        if (!Files.isRegularFile(f)) return emptyList()
        return Files.readAllLines(f, Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull { Jsonl.parseLine(it) }
    }

    @Test
    fun setIntWritesAndReadsBackWithOldNew() {
        val before = built.treeSnapshot()
        val (code, text) = run(*MiniCorpus.args(built, work, "set", "MONEY[0]", "=", "77"))
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("name=MONEY" in text, text)
        assertTrue("idx=[0]" in text, text)
        assertTrue("old=0" in text, text)
        assertTrue("new=77" in text, text)
        // CLI 每命令新装配：var 是独立会话，读到启动缺省而不是上一次注入值
        val (_, varText) = run(*MiniCorpus.args(built, work, "var", "MONEY[0]"))
        assertTrue("MONEY[0]=0" in varText, "var 新会话应为启动缺省 0：$varText")
        assertEquals(before, built.treeSnapshot(), "游戏树不得有任何新文件")
    }

    @Test
    fun setStringWithQuotesHitsTypeGuardOfIntVar() {
        // 引号剥离面：带引号的值按字符串写 → 整数变量的类型守卫给可读文案
        val (code, text) = run(*MiniCorpus.args(built, work, "set", "MONEY", "=", "\"abc\""))
        assertEquals(2, code, text)
        assertTrue("status=CODEEE" in text, text)
        assertTrue("不能写入字符串" in text, text)
    }

    @Test
    fun undefinedNameGivesReadableCodeeeAndExit2() {
        val (code, text) = run(*MiniCorpus.args(built, work, "set", "NOPE_UNDEFINED_XYZ[3]", "=", "1"))
        assertEquals(2, code, text)
        assertTrue("status=CODEEE" in text, text)
        assertTrue("未定义的变量 NOPE_UNDEFINED_XYZ" in text, text)
        assertTrue(auditLines().isEmpty(), "失败注入不得写审计行")
    }

    @Test
    fun dimensionMismatchGivesReadableCodeee() {
        // MONEY 是 1 维；给两维下标应得到维度不符的可读文案
        val (code, text) = run(*MiniCorpus.args(built, work, "set", "MONEY[0,1]", "=", "5"))
        assertEquals(2, code, text)
        assertTrue("status=CODEEE" in text, text)
        assertTrue("下标" in text, text)
    }

    @Test
    fun cheatMoneyAndDayWriteSystemVars() {
        val (c1, t1) = run(*MiniCorpus.args(built, work, "cheat", "money", "12345"))
        assertEquals(0, c1, t1)
        assertTrue("name=MONEY" in t1, t1)
        assertTrue("new=12345" in t1, t1)
        val (c2, t2) = run(*MiniCorpus.args(built, work, "cheat", "day", "9"))
        assertEquals(0, c2, t2)
        assertTrue("name=DAY" in t2, t2)
        assertTrue("new=9" in t2, t2)
        val lines = auditLines()
        assertEquals(2, lines.size, "两次注入各一条审计行: $lines")
        assertEquals("MONEY", lines[0]["name"])
        assertEquals("cheat", lines[0]["op"])
        assertEquals("[0]", lines[0]["idx"], "audit idx 为数字数组形式")
        assertEquals("12345", lines[0]["new"])
        assertEquals("DAY", lines[1]["name"])
        assertTrue((lines[0]["ts"] ?: "").toLongOrNull() != null, "ts 为 epoch 毫秒数字")
    }

    @Test
    fun cheatRawIsAliasOfSet() {
        val (code, text) = run(*MiniCorpus.args(built, work, "cheat", "raw", "MONEY[0]", "=", "5"))
        assertEquals(0, code, text)
        assertTrue("new=5" in text, text)
        val lines = auditLines()
        assertEquals(1, lines.size)
        assertEquals("MONEY", lines[0]["name"])
        assertEquals("cheat", lines[0]["op"])
    }

    @Test
    fun unknownRecipeIsUsageError() {
        val (code, text) = run(*MiniCorpus.args(built, work, "cheat", "invincible", "1"))
        assertEquals(1, code, text)
        assertTrue("未知 cheat 配方" in text, text)
    }

    @Test
    fun missingEqualsIsUsageError() {
        val (code, text) = run(*MiniCorpus.args(built, work, "set", "MONEY"))
        assertEquals(1, code, text)
        assertTrue("缺 '='" in text, text)
    }

    @Test
    fun auditAppendsAcrossInvocationsWithFieldOrder() {
        run(*MiniCorpus.args(built, work, "set", "MONEY[0]", "=", "1"))
        run(*MiniCorpus.args(built, work, "cheat", "money", "2"))
        val raw = Files.readAllLines(work.resolve("audit.jsonl"), Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(2, raw.size)
        // 字段序钉死（ts/op/name/idx/old/new），供 ledger 式文本 diff 稳定消费
        assertTrue(raw[0].startsWith("{\"ts\":"), raw[0])
        assertTrue("\"op\":\"set\"" in raw[0], raw[0])
        assertTrue("\"name\":\"MONEY\"" in raw[0], raw[0])
        assertTrue("\"idx\":[0]" in raw[0], raw[0])
        assertTrue(raw[0].endsWith("}"), raw[0])
    }
}
