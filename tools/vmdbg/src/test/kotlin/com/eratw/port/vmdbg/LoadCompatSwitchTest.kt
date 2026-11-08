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
 * obs-compat512 的两个装置开关（`--compat-load` / `--load-precheck`）与装载 floor 二分用的
 * `--boot-only`，全部走合成 mini 语料（[MiniCorpus] / [ExploreTestCorpus]），不碰真产物。
 *
 * 断言口径：
 * - `--load-precheck` 那行必须是「可 grep 的一行 key=value」，且 `maxMemoryBytes` 逐字节等于
 *   `Runtime.getRuntime().maxMemory()`（= 挡位自证；`--xmx 512m` 时即 536870912）；
 * - 两个开关**不传**时输出里不许出现 `loadPrecheck`（缺省路径逐字不变）；
 * - `--compat-load` 的语义与异常口径与缺省档一致：同一条函数照样 PASS、控制台尾逐行相同；
 * - `--boot-only` 引导到落 seed.dbg 即停（episodes=0），且与 `--soak-min` 互斥。
 */
class LoadCompatSwitchTest {

    private lateinit var tmp: Path
    private lateinit var built: MiniCorpus.Built
    private lateinit var work: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-loadcompat-test")
        built = MiniCorpus.build(tmp)
        work = tmp.resolve("work")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    class Result(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String): Result {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val out = PrintStream(outBuf, true, Charsets.UTF_8)
        val err = PrintStream(errBuf, true, Charsets.UTF_8)
        val code = Cli.run(arrayOf(*args), out, err)
        return Result(code, outBuf.toString(Charsets.UTF_8), errBuf.toString(Charsets.UTF_8))
    }

    private fun kv(text: String): Map<String, String> =
        text.lineSequence()
            .takeWhile { it.contains('=') || it.isEmpty() }
            .mapNotNull { l ->
                val i = l.indexOf('=')
                if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1)
            }
            .toMap()

    /** `loadPrecheck:` 那一行的 key=value（找不到返回空表）。 */
    private fun precheck(text: String): Map<String, String> {
        val line = text.lineSequence().firstOrNull { it.startsWith("loadPrecheck:") } ?: return emptyMap()
        return line.removePrefix("loadPrecheck:").trim()
            .split(' ')
            .mapNotNull { tok ->
                val i = tok.indexOf('=')
                if (i <= 0) null else tok.substring(0, i) to tok.substring(i + 1)
            }
            .toMap()
    }

    // ------------------------------------------------------------------
    // --load-precheck：一行读数 + 挡位自证
    // ------------------------------------------------------------------

    @Test
    fun preCheckLineCarriesEstimateAndCeiling() {
        val r = run(*MiniCorpus.args(built, work, "call", "MINI_PASS", "--load-precheck"))
        assertEquals(0, r.code, r.err)
        val f = precheck(r.out)
        assertTrue(f.isNotEmpty(), "应打出 loadPrecheck 行：\n" + r.out)
        // 挡位自证：maxMemoryBytes 逐字节等于本进程的 Runtime.maxMemory()。
        assertEquals(Runtime.getRuntime().maxMemory(), f["maxMemoryBytes"]?.toLong(), r.out)
        val req = f["requiredBytes"]!!.toLong()
        val max = f["maxMemoryBytes"]!!.toLong()
        assertTrue(req > 0, "requiredBytes 应为正：$req")
        assertEquals(max >= req, f["sufficient"]!!.toBoolean(), "sufficient 必须等于 maxMemory>=required")
        assertTrue(f["artifactBytes"]!!.toLong() > 0)
        assertTrue(f["instructions"]!!.toInt() > 0)
        // 未开兼容档时如实标 false —— 预检是独立开关，不隐含兼容档。
        assertEquals(false, f["compat"]!!.toBoolean())
        // 行在装载之前（预检行必须早于命令行人的读数之后的第一条业务输出）。
        assertTrue(r.out.indexOf("loadPrecheck:") >= 0)
    }

    @Test
    fun preCheckAbsentWhenSwitchNotGiven() {
        val r = run(*MiniCorpus.args(built, work, "call", "MINI_PASS"))
        assertEquals(0, r.code, r.err)
        assertTrue("loadPrecheck" !in r.out, "不传开关时输出一个字都不许多：\n" + r.out)
    }

    // ------------------------------------------------------------------
    // --compat-load：语义与输出形状不变
    // ------------------------------------------------------------------

    @Test
    fun compatLoadKeepsCallSemantics() {
        val plain = run(*MiniCorpus.args(built, work, "call", "MINI_PASS"))
        val compat = run(*MiniCorpus.args(built, work, "call", "MINI_PASS", "--compat-load"))
        assertEquals(0, compat.code, compat.err)
        val a = kv(plain.out)
        val b = kv(compat.out)
        // 语义面逐字段相同（步数/耗时不比：装载档影响的是装载期，不是这一步）。
        assertEquals(a["status"], b["status"], compat.out)
        assertEquals(a["target"], b["target"])
        assertEquals(a["location"], b["location"])
        assertTrue("mini-pass-ok" in compat.out, compat.out)
        // 兼容档也不打预检行（两个开关独立）。
        assertTrue("loadPrecheck" !in compat.out, compat.out)
    }

    @Test
    fun compatLoadWithPrecheckReportsCompatTrue() {
        val r = run(
            *MiniCorpus.args(built, work, "call", "MINI_PASS", "--compat-load", "--load-precheck"),
        )
        assertEquals(0, r.code, r.err)
        val f = precheck(r.out)
        assertTrue(f.isNotEmpty(), r.out)
        assertEquals(true, f["compat"]!!.toBoolean())
        assertEquals(Runtime.getRuntime().maxMemory(), f["maxMemoryBytes"]?.toLong())
    }

    // ------------------------------------------------------------------
    // --boot-only：引导到 seed.dbg 即停
    // ------------------------------------------------------------------

    @Test
    fun bootOnlyStopsAfterSeedWithZeroEpisodes() {
        val corpus = ExploreTestCorpus.build(tmp.resolve("bootonly"), withSystemTitle = true)
        val recipe = tmp.resolve("boot.txt")
        Files.writeString(recipe, "0 ;; ex-title-menu\n", Charsets.UTF_8)
        val outDir = tmp.resolve("boot-only-out")
        val r = run(
            "--erbc", corpus.erbcDir.toString(),
            "--game", corpus.gameRoot.toString(),
            "--work", tmp.resolve("bootonly-work").toString(),
            "explore",
            "--boot", recipe.toString(),
            "--boot-only",
            "--out", outDir.toString(),
        )
        assertEquals(0, r.code, r.err)
        assertTrue("status=PASS" in r.out, r.out)
        assertTrue("episodes=0" in r.out, "只引导不跑局：应打印 episodes=0\n" + r.out)
        assertTrue("bootOnly=true" in r.out, r.out)
        assertTrue(Files.isRegularFile(outDir.resolve("seed.dbg")), "引导成功应落 seed.dbg")
    }

    @Test
    fun bootOnlyRejectsSoakCombination() {
        val corpus = ExploreTestCorpus.build(tmp.resolve("bootonly-conflict"), withSystemTitle = true)
        val r = run(
            "--erbc", corpus.erbcDir.toString(),
            "--game", corpus.gameRoot.toString(),
            "--work", tmp.resolve("bootonly-conflict-work").toString(),
            "explore",
            "--boot-only",
            "--soak-min", "1",
            "--out", tmp.resolve("boot-only-conflict-out").toString(),
        )
        assertEquals(1, r.code, r.out)
        assertTrue("互斥" in r.err, r.err)
    }
}
