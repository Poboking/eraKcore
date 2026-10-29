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
 * snapshot / restore（DBG4 P2）的机制锁 —— 验收口径即规格 §4 的两条，全部在单测内
 * 跨会话（同进程多次 [Assembler.assemble]，CLI 一命令一进程只落「存完即验」形态）：
 * - 快照 → set 一撮变量 → restore → 逐值还原（MONEY / DAY，可写的 G_INT1 系统变量）；
 * - 快照 → restore → 同参数 call，控制台输出逐行一致 + RNG 状态/抽号序列一致；
 * - sidecar 字段（rngState 625 / consoleTail / funcStack 只记录 / randSeed / createdAt）；
 * - LOADDATA 语义链整栈丢弃：funcStack **不**还原（restore 输出明示）；
 * - **语料只读铁律**：snapshot/restore 全程不碰游戏树（写只进各会话私有沙箱）。
 */
class CmdSnapTest {

    private lateinit var tmp: Path
    private lateinit var built: MiniCorpus.Built
    private lateinit var work: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-snap-test")
        built = MiniCorpus.build(tmp)
        work = tmp.resolve("work")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun assemble(): Assembler.Assembled =
        Assembler.assemble(Args.parse(MiniCorpus.args(built, work).toList()))

    private fun run(vararg args: String): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val ebuf = ByteArrayOutputStream()
        val code = Cli.run(arrayOf(*args), out = PrintStream(buf, true, Charsets.UTF_8), err = PrintStream(ebuf, true, Charsets.UTF_8))
        return code to buf.toString(Charsets.UTF_8) + ebuf.toString(Charsets.UTF_8)
    }

    private fun snapMain(): Path = work.resolve("snapshot.dbg")

    @Test
    fun snapshotThenMutateThenRestoreRecoversVariablesAndRng() {
        val before = built.treeSnapshot()
        val a = assemble()
        val storeA = a.session.store
        storeA.writeInt("MONEY", longArrayOf(0L), 777L)
        storeA.writeInt("DAY", longArrayOf(0L), 5L)
        repeat(3) { storeA.randProvider!!.next(100L) } // 推进 RNG，状态非零位
        val stateAtSnapshot = storeA.randProvider!!.state()

        val buf = ByteArrayOutputStream()
        assertEquals(0, CmdSnap.snapshot(a, snapMain(), PrintStream(buf, true, Charsets.UTF_8)), buf.toString())

        // 快照后继续污染 A 面
        storeA.writeInt("MONEY", longArrayOf(0L), 1L)
        storeA.writeInt("DAY", longArrayOf(0L), 99L)
        repeat(7) { storeA.randProvider!!.next(1000L) }

        val b = assemble()
        val buf2 = ByteArrayOutputStream()
        assertEquals(0, CmdSnap.restore(b, snapMain(), PrintStream(buf2, true, Charsets.UTF_8)), buf2.toString())
        val storeB = b.session.store
        assertEquals(777L, storeB.readInt("MONEY", longArrayOf(0L)), buf2.toString())
        assertEquals(5L, storeB.readInt("DAY", longArrayOf(0L)), buf2.toString())
        // RNG 状态逐元素还原（Sfmt19937 625 元素，Sfmt19937.kt:90-106）
        assertTrue(stateAtSnapshot.contentEquals(storeB.randProvider!!.state()), "restore 后 RNG 状态应等于快照时刻")
        // 还原后抽号序列与「回灌同一快照态的独立会话」一致（规格 §4 的序列一致性判据）
        val c = assemble()
        c.session.store.randProvider!!.loadState(stateAtSnapshot)
        val drawsB = LongArray(5) { storeB.randProvider!!.next(1000L) }
        val drawsC = LongArray(5) { c.session.store.randProvider!!.next(1000L) }
        assertTrue(drawsB.contentEquals(drawsC), "restore 后抽号序列应与快照态续抽一致：${drawsB.toList()} vs ${drawsC.toList()}")
        assertEquals(before, built.treeSnapshot(), "游戏树不得有任何新文件")
    }

    @Test
    fun restoreEnablesSameOutputForSameCall() {
        val a = assemble()
        assertEquals(0, CmdSnap.snapshot(a, snapMain(), PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8)))
        val tailBeforeA = a.session.console.snapshot().size
        a.session.runtime.callFunction("MINI_ARGS", longArrayOf(7L), arrayOf("x"))

        val b = assemble()
        assertEquals(0, CmdSnap.restore(b, snapMain(), PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8)))
        val tailBeforeB = b.session.console.snapshot().size
        b.session.runtime.callFunction("MINI_ARGS", longArrayOf(7L), arrayOf("x"))

        val tailA = a.session.console.snapshot().drop(tailBeforeA).map { a.session.console.textOf(it) }
        val tailB = b.session.console.snapshot().drop(tailBeforeB).map { b.session.console.textOf(it) }
        assertEquals(tailA, tailB, "同参数 call 在还原会话与原会话的输出应逐行一致")
    }

    @Test
    fun sidecarCarriesRngStateConsoleTailAndFuncStackFields() {
        val a = assemble()
        a.session.store.writeInt("MONEY", longArrayOf(0L), 3L)
        a.session.runtime.callFunction("MINI_PASS", longArrayOf(), arrayOf())
        val buf = ByteArrayOutputStream()
        assertEquals(0, CmdSnap.snapshot(a, snapMain(), PrintStream(buf, true, Charsets.UTF_8)), buf.toString())
        val line = Files.readString(CmdSnap.sidecarPath(snapMain()), Charsets.UTF_8)
        val rng = CmdSnapTestJson.rngArray(line)
        assertEquals(625, rng.size, "rngState = Sfmt19937.RANDDATA_LEN 元素")
        assertTrue("\"consoleTail\":[" in line && "mini-pass-ok" in line, "console 尾含快照前输出: $line")
        assertTrue("\"funcStack\":[" in line, line)
        assertTrue("\"funcDepth\":" in line, line)
        assertTrue("\"randSeed\":20260226" in line, line)
        assertTrue("\"createdAt\":\"" in line, line)
        assertTrue("\"commit\":" in line, line)
        // 快照主文件 = 游戏自身 save 格式（SaveCodec 分派），restore 侧 probe 必须认
        val mainBytes = Files.readAllBytes(snapMain())
        assertTrue(mainBytes.isNotEmpty(), "快照主文件非空")
    }

    @Test
    fun restoreWithoutSidecarStillRestoresVariables() {
        val a = assemble()
        a.session.store.writeInt("MONEY", longArrayOf(0L), 55L)
        assertEquals(0, CmdSnap.snapshot(a, snapMain(), PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8)))
        Files.delete(CmdSnap.sidecarPath(snapMain()))
        val b = assemble()
        val buf = ByteArrayOutputStream()
        assertEquals(0, CmdSnap.restore(b, snapMain(), PrintStream(buf, true, Charsets.UTF_8)), buf.toString())
        assertEquals(55L, b.session.store.readInt("MONEY", longArrayOf(0L)))
        assertTrue("rngLoaded=false" in buf.toString(), "sidecar 缺席 → RNG 段跳过并注明: $buf")
    }

    @Test
    fun restoreMissingSnapshotIsUsageError() {
        val (_, text) = run(*MiniCorpus.args(built, work, "restore", tmp.resolve("nope.dbg").toString()))
        assertTrue("快照主文件不存在" in text, text)
    }

    @Test
    fun cliRestorePrintsNotRestoredNoteForCallStack() {
        val a = assemble()
        assertEquals(0, CmdSnap.snapshot(a, snapMain(), PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8)))
        val (code, text) = run(*MiniCorpus.args(built, work, "restore", snapMain().toString()))
        assertEquals(0, code, text)
        assertTrue("funcStack=not-restored" in text, text)
        assertTrue("lastLoadNo=99" in text, text)
    }

    @Test
    fun useSaveFolderConfigStillRoundTrips() {
        // 回归（2026-10-25 真语料冒烟实锤）：USE SAV FOLDER:YES 时槽路径带 `sav/` 前缀
        // （SaveConfig.saveFolderPrefix），restore 落盘必须建父目录 —— 缺失即 NoSuchFile。
        val game2 = tmp.resolve("game-savfolder")
        val erbDir2 = game2.resolve("ERB")
        Files.createDirectories(erbDir2)
        Files.writeString(game2.resolve("emuera.config"), "USE SAV FOLDER:YES\n")
        Files.writeString(erbDir2.resolve("mini.erh"), MiniCorpus.MINI_ERH)
        Files.writeString(erbDir2.resolve("mini.erb"), MiniCorpus.MINI_ERB)
        val r2 = CorpusCompile.compile(erbDir2)
        check(r2.ok) { "mini 语料(sav folder) 编译失败: ${r2.failures.joinToString("; ")}" }
        val out2 = tmp.resolve("erbc-out2")
        ErbcArtifacts.write(out2, r2.program!!, erbDir2, r2.sourceFiles, r2.stats, r2.bootMetadata)
        val built2 = MiniCorpus.Built(game2, out2.resolve("erbc.v1"))

        val args2 = MiniCorpus.args(built2, work).toList()
        val a = Assembler.assemble(Args.parse(args2))
        a.session.store.writeInt("MONEY", longArrayOf(0L), 31337L)
        val snap2 = work.resolve("snap2.dbg")
        assertEquals(0, CmdSnap.snapshot(a, snap2, PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8)))

        val b = Assembler.assemble(Args.parse(args2))
        val buf = ByteArrayOutputStream()
        assertEquals(0, CmdSnap.restore(b, snap2, PrintStream(buf, true, Charsets.UTF_8)), buf.toString())
        assertEquals(31337L, b.session.store.readInt("MONEY", longArrayOf(0L)), buf.toString())
    }
}

/** 测试侧的 sidecar rngState 提取（与 CmdSnap 的自读同形，独立实现防同源盲区）。 */
private object CmdSnapTestJson {
    fun rngArray(line: String): List<Long> {
        val needle = "\"rngState\":["
        val start = line.indexOf(needle)
        if (start < 0) return emptyList()
        val from = start + needle.length
        val end = line.indexOf(']', from)
        if (end < 0) return emptyList()
        val body = line.substring(from, end).trim()
        if (body.isEmpty()) return emptyList()
        return body.split(',').mapNotNull { it.trim().toLongOrNull() }
    }
}
