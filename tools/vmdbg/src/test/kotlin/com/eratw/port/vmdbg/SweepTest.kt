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
 * sweep 机制（CONTRACT-DBG1 路2 sweep 规格）：
 * - 清单过滤：事件函数与 erbcc 合成事件入口（·event-entry·）不出现；
 * - 每函数一条终态记录（RUNNING 占位被覆写，终态文件无 RUNNING 残留）；
 * - 失败折成状态不中断批跑（MINI_CODEEE 折 CODEEE，后续 MINI_METHOD 照跑）；
 * - --skip-methods / --only / --shard 过滤；
 * - --resume：终态跳过、RUNNING 视为待重跑；
 * - **语料只读铁律**：sweep 跑完后合成语料树快照逐字节不变（含 SAVEGLOBAL 的写被沙箱吸走）。
 */
class SweepTest {

    private lateinit var tmp: Path
    private lateinit var built: MiniCorpus.Built
    private lateinit var work: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-sweep-test")
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

    private fun records(jsonl: Path): List<Map<String, String>> =
        Files.readAllLines(jsonl, Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull { Jsonl.parseLine(it) }

    @Test
    fun sweepFullRunProducesTerminalRecordsAndSkipsEvents() {
        val before = built.treeSnapshot()
        val outDir = tmp.resolve("sweep-out")
        val (code, text) = run(*MiniCorpus.args(built, work, "sweep", "--out", outDir.toString()))
        assertEquals(0, code, text)

        val jsonl = outDir.resolve("sweep.jsonl")
        assertTrue(Files.isRegularFile(jsonl), text)
        val recs = records(jsonl)
        val keys = recs.map { it["key"] }.toSet()

        // 终态文件：无 RUNNING 残留（占位被覆写 = 每函数一条终态）
        assertTrue(recs.none { it["status"] == "RUNNING" }, "终态文件残留 RUNNING：$recs")
        // 非事件函数全部在列
        assertTrue("MINI_PASS" in keys, keys.toString())
        assertTrue("MINI_ARGS" in keys, keys.toString())
        assertTrue("MINI_CODEEE" in keys, keys.toString())
        assertTrue("MINI_METHOD" in keys, keys.toString())
        // 事件函数 + erbcc 合成事件入口（·event-entry·）都不在列
        assertTrue("EVENTFIRST" !in keys, "事件函数被清扫：$keys")
        // 必含字段齐全（契约：fn/key/status/line/pc/msg/steps/elapsedMs + 覆盖率三列）
        val rec = recs.first { it["key"] == "MINI_CODEEE" }
        for (f in listOf("fn", "key", "status", "line", "pc", "msg", "steps", "elapsedMs", "covered", "stmts", "coverPct")) {
            assertTrue(rec.containsKey(f), "缺字段 $f：$rec")
        }
        assertEquals("CODEEE", rec["status"])

        // 失败不中断批跑：MINI_CODEEE 之后的 MINI_METHOD 也有记录
        val method = recs.first { it["key"] == "MINI_METHOD" }
        assertEquals("CODEEE", method["status"], "方法被 callFunction 直调应 methodCallError：$method")

        // summary.md：免责声明 + 状态计数 + 指纹 TOP 表
        val summary = Files.readString(outDir.resolve("summary.md"), Charsets.UTF_8)
        assertTrue("不是功能判据" in summary, summary)
        assertTrue("状态串染" in summary, summary)
        assertTrue("PASS" in summary && "指纹 TOP" in summary, summary)

        // **语料树无新文件**（SAVEGLOBAL 的 global.sav 落沙箱，不落语料）
        assertEquals(before, built.treeSnapshot(), "sweep 改写了语料树")
    }

    @Test
    fun sweepSkipMethodsAndOnlyAndShard() {
        val outDir = tmp.resolve("sweep-out-2")
        val (code, text) = run(
            *MiniCorpus.args(built, work, "sweep", "--out", outDir.toString(),
                "--skip-methods", "--only", "MINI_(PASS|ARGS)"),
        )
        assertEquals(0, code, text)
        val keys = records(outDir.resolve("sweep.jsonl")).map { it["key"] }.toSet()
        assertEquals(setOf("MINI_PASS", "MINI_ARGS"), keys, "过滤结果不符：$keys")

        // shard：0/2 与 1/2 按原清单下标取模，并起来覆盖全量、互不相交
        val shardA = tmp.resolve("sweep-shard-0")
        val shardB = tmp.resolve("sweep-shard-1")
        run(*MiniCorpus.args(built, work, "sweep", "--out", shardA.toString(), "--shard", "0/2"))
        run(*MiniCorpus.args(built, work, "sweep", "--out", shardB.toString(), "--shard", "1/2"))
        val keysA = records(shardA.resolve("sweep.jsonl")).map { it["key"] }.toSet()
        val keysB = records(shardB.resolve("sweep.jsonl")).map { it["key"] }.toSet()
        assertTrue((keysA intersect keysB).isEmpty(), "分片相交：$keysA / $keysB")
        assertEquals(keysA + keysB, setOf("MINI_PASS", "MINI_ARGS", "MINI_CODEEE", "MINI_METHOD", "MINI_INPUTLOOP"), "分片并集不符")
    }

    @Test
    fun sweepResumeSkipsTerminalAndRerunsRunning() {
        val outDir = tmp.resolve("sweep-out-resume")
        run(*MiniCorpus.args(built, work, "sweep", "--out", outDir.toString(), "--only", "MINI_PASS"))

        // 伪造一次挂死现场：追加一行 RUNNING 占位（进程挂死 = 终态没写上）
        val jsonl = outDir.resolve("sweep.jsonl")
        Files.writeString(
            jsonl,
            Jsonl.obj(listOf("fn" to "MINI_PASS", "key" to "MINI_PASS", "status" to "RUNNING")) + "\n",
            Charsets.UTF_8,
            java.nio.file.StandardOpenOption.APPEND,
        )
        val (code, text) = run(*MiniCorpus.args(built, work, "sweep", "--out", outDir.toString(), "--only", "MINI_PASS", "--resume"))
        assertEquals(0, code, text)

        val recs = records(jsonl)
        // MINI_PASS 的终态记录只有一条（旧 RUNNING 被剔除、旧终态被复用不重跑）
        assertEquals(1, recs.size, "resume 后应只有一条终态记录：$recs")
        assertEquals("PASS", recs[0]["status"], recs.toString())
        assertTrue("resume" in text, text)
    }

    @Test
    fun sweepResumeRerunsFunctionWithOnlyRunningRecord() {
        // 真实挂死形态：文件里只有一行 RUNNING（终态没来得及写）→ resume 必须重跑它并折成终态
        val outDir = tmp.resolve("sweep-out-crash")
        Files.createDirectories(outDir)
        val jsonl = outDir.resolve("sweep.jsonl")
        Files.writeString(
            jsonl,
            Jsonl.obj(listOf("fn" to "MINI_CODEEE", "key" to "MINI_CODEEE", "status" to "RUNNING")) + "\n",
            Charsets.UTF_8,
        )
        val (code, text) = run(*MiniCorpus.args(built, work, "sweep", "--out", outDir.toString(), "--only", "MINI_CODEEE", "--resume"))
        assertEquals(0, code, text)
        val recs = records(jsonl)
        assertEquals(1, recs.size, "RUNNING 被重跑并覆写为终态：$recs")
        assertEquals("CODEEE", recs[0]["status"], recs.toString())
    }

    @Test
    fun sweepSaveSandboxAbsorbsSaveglobal() {
        // 语料只读铁律的正面证据：MINI_ARGS 体里 SAVEGLOBAL，沙箱里出现 global.sav、
        // 语料树里没有（上一条已断言整树不变，这里把沙箱侧的产物钉出来）。
        val outDir = tmp.resolve("sweep-out-sandbox")
        val (code, text) = run(*MiniCorpus.args(built, work, "sweep", "--out", outDir.toString(), "--only", "MINI_ARGS"))
        assertEquals(0, code, text)
        val sandboxDirs = Files.list(work).use { s ->
            s.filter { it.fileName.toString().startsWith("save-sandbox-") }.toList()
        }
        assertTrue(sandboxDirs.isNotEmpty(), "沙箱目录不存在：$text")
        val sandboxFiles = sandboxDirs.flatMap { dir -> Files.walk(dir).use { s -> s.filter(Files::isRegularFile).toList() } }
        assertTrue(
            sandboxFiles.any { it.fileName.toString() == "global.sav" },
            "SAVEGLOBAL 应把 global.sav 落进沙箱：${sandboxFiles.map { it.fileName }}",
        )
        assertEquals(0, sandboxFiles.count { it.startsWith(built.gameRoot) }, "存档写进了语料树")
    }

    @Test
    fun sweepInputLoopFunctionTripsWallLimitNotPark() {
        // 输入路径墙钟闸的回归面（2026-10-25 收口修钉）：MINI_INPUTLOOP 每圈 INPUT，
        // 输入是让出边界（步数闸重算）——立即应答 sink 没接进会话时它会 park 死在
        // QueueInputSink 上（真语料 BATTLE_ATK 首跑实锤）；接好后每圈应答一次 + 查一次
        // 墙钟，--wall-ms 200 必在 FOR 百万圈耗尽前折成 WALL_LIMIT 终态。
        val outDir = tmp.resolve("sweep-out-wall")
        val (code, text) = run(
            *MiniCorpus.args(built, work, "sweep", "--out", outDir.toString(),
                "--only", "MINI_INPUTLOOP", "--wall-ms", "200"),
        )
        assertEquals(0, code, text)
        val recs = records(outDir.resolve("sweep.jsonl"))
        val rec = recs.single { it["key"] == "MINI_INPUTLOOP" }
        assertEquals("WALL_LIMIT", rec["status"], "应折成 WALL_LIMIT 而非挂死：$rec")
        assertTrue((rec["elapsedMs"]?.toLongOrNull() ?: -1L) >= 200, "墙钟应真实生效：$rec")
    }

    @Test
    fun sweepRecordsCarryStatementCoverage() {
        // 覆盖率仪表（DBG1 增量）：分母 = 函数区间内语句 marker 数（op 29..197），分子 =
        // distinct 执行 marker pc（嵌套被调方经区间过滤不计入）。分母别忘了编译器给每个
        // 掉尾函数补的合成 `RESULT = 0` 赋值 marker（ErbProgramCompiler.kt:300-304）：
        // - MINI_PASS = PRINTL + 合成句 = 2 条全执行 → 100.0%；
        // - MINI_ARGS = PRINTVL/PRINTSL/SAVEGLOBAL + 合成句 = 4 条全执行 → 100.0%；
        // - MINI_CODEEE = CALLFORM + 合成句，但 CALLFORM 编译成 _CALL_BY_NAME **扩展指令**
        //   （198..201，非语句 marker）——「未定义函数」错误在调用指令层抛出、一条语句都
        //   没执行（steps=0）→ 分母只剩合成句 1 条、分子 0 → 0.0%。
        //   语句覆盖率不把调用点算语句，是引擎口径的必然（CALL 家族在 VM 指令层）。
        val outDir = tmp.resolve("sweep-out-cover")
        val (code, text) = run(
            *MiniCorpus.args(built, work, "sweep", "--out", outDir.toString(),
                "--only", "MINI_(PASS|ARGS|CODEEE)"),
        )
        assertEquals(0, code, text)
        val recs = records(outDir.resolve("sweep.jsonl"))
        val pass = recs.single { it["key"] == "MINI_PASS" }
        assertEquals("2", pass["covered"], "$pass")
        assertEquals("2", pass["stmts"], "$pass")
        assertEquals("100.0", pass["coverPct"], "$pass")
        val args = recs.single { it["key"] == "MINI_ARGS" }
        assertEquals("4", args["stmts"], "$args")
        assertEquals("4", args["covered"], "$args")
        assertEquals("100.0", args["coverPct"], "$args")
        val codeee = recs.single { it["key"] == "MINI_CODEEE" }
        assertEquals("1", codeee["stmts"], "$codeee")
        assertEquals("0", codeee["covered"], "$codeee")
        assertEquals("0.0", codeee["coverPct"], "$codeee")
    }

    @Test
    fun sweepFreshRunTruncatesStaleOutput() {
        // 非 resume 的全新跑必须清空旧 sweep.jsonl（appendLine 是 CREATE+APPEND，
        // 不清空的话 --out 复用旧目录会把上一轮记录原样混进来，记录翻倍）。
        val outDir = tmp.resolve("sweep-out-truncate")
        run(*MiniCorpus.args(built, work, "sweep", "--out", outDir.toString(), "--only", "MINI_PASS"))
        run(*MiniCorpus.args(built, work, "sweep", "--out", outDir.toString(), "--only", "MINI_PASS"))
        val recs = records(outDir.resolve("sweep.jsonl"))
        assertEquals(1, recs.size, "第二次跑应只有本次记录：$recs")
    }
}
