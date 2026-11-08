package com.eratw.port.erbcc.audit

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `cmdAudit --reachability` 的 CLI 面（MODERN-R6 §3.4 / §4）。
 *
 * 三条用例各自钉一件事：
 * 1. **缺省关**：stdout / 退出码 / 未绑定符号 TSV 与「改动前」冻结的基线逐字节相同
 *    （基线 `resources/reachability/cli-default-off/` 是动 `Main.kt` 之前用同一份合成语料录的）；
 * 2. **显式开**：多出 summary 行 + 可达性 TSV，未绑定符号 TSV 一个字节不变；
 * 3. **确定性**：同一语料两次运行可达性 TSV 逐字节相同，且不含绝对路径。
 *
 * 语料由 [ReachabilityCliFixture] 现场写进 `@TempDir`，不依赖 `ERATW_CORPUS`。
 * 环境量掩码：编译读数里的耗时（`（N ms）`）与输出文件绝对路径在基线与断言两侧同样掩码
 * （见 [normalize]）—— 这两项本来就是环境量，掩码不改判据；`SymbolLinkAudit` 的既有输出
 * 一字未动。
 */
class ReachabilityCliTest {

    @TempDir
    lateinit var dir: Path

    private class CliRun(val code: Int, val stdout: String, val out: Path)

    private fun runAudit(game: Path, out: Path, vararg extra: String): CliRun {
        Files.createDirectories(out.toAbsolutePath().normalize().parent)
        val buf = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(buf, true, Charsets.UTF_8))
        val code: Int
        try {
            code = com.eratw.port.erbcc.cmdAudit(
                listOf(
                    "--corpus", game.resolve("ERB").toString(),
                    "--game-root", game.toString(),
                    "--out", out.toString(),
                ) + extra.toList(),
            )
        } finally {
            System.setOut(old)
        }
        return CliRun(code, normalize(buf.toString("UTF-8"), out), out)
    }

    /**
     * 环境量掩码（基线与现网两侧同口径）：
     * - CRLF → LF（Windows 控制台）；
     * - `（N ms）` → `（<ms>）`（编译耗时）；
     * - `--out` 的绝对路径 → `<out>`（临时目录每次不同）。
     */
    private fun normalize(stdout: String, out: Path): String =
        stdout.replace("\r\n", "\n")
            .replace(Regex("（\\d+ ms）"), "（<ms>）")
            .replace(out.toAbsolutePath().normalize().toString(), "<out>")

    private fun baseline(name: String): String {
        val stream = ReachabilityCliTest::class.java.getResourceAsStream(
            "/reachability/cli-default-off/$name",
        )
        assertNotNull(stream, "缺基线资源 $name（应随测试一同提交）")
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun `缺省关时 stdout 退出码与 TSV 与冻结基线逐字节相同`() {
        val game = ReachabilityCliFixture.writeGame(dir)
        val out = dir.resolve("off").resolve("unresolved-symbols.tsv")
        val run = runAudit(game, out)
        assertEquals(0, run.code, "缺省关的审计应照常退出 0")
        assertEquals(baseline("stdout.txt"), run.stdout, "缺省关的 stdout 必须与改动前逐字节相同")
        assertEquals(baseline("unresolved-symbols.tsv"), Files.readString(out), "缺省关的 TSV 必须与改动前逐字节相同")
        assertFalse(
            Files.exists(out.toAbsolutePath().normalize().parent.resolve("reachability.tsv")),
            "缺省关不应产出可达性报告",
        )
    }

    @Test
    fun `显式开启追加 summary 行与可达性 TSV`() {
        val game = ReachabilityCliFixture.writeGame(dir)
        val out = dir.resolve("on").resolve("unresolved-symbols.tsv")
        val reachOut = dir.resolve("on").resolve("reach.tsv")
        val run = runAudit(game, out, "--reachability", "--reach-out", reachOut.toString())
        assertEquals(0, run.code, "开启报告不应改变退出码")

        val pattern = Regex(
            "^reachability: roots=(\\d+) reachable=(\\d+) dynamic-calls=(\\d+) portability=(\\d+) persist=(\\d+)$",
            RegexOption.MULTILINE,
        )
        val hit = pattern.find(run.stdout)
        assertNotNull(hit, "应打印 reachability summary 行，实际 stdout:\n${run.stdout}")
        val roots = hit.groupValues[1].toInt()
        val reachable = hit.groupValues[2].toInt()
        val portability = hit.groupValues[4].toInt()
        // 语料只有 @SYSTEM_TITLE 一个根，@SUB_ONE 由 CALL 到达；GETFONT() 一行落进可移植性诊断。
        assertEquals(1, roots, "合成语料应只有 SYSTEM_TITLE 一个根")
        assertTrue(reachable >= 2, "@SYSTEM_TITLE 与 @SUB_ONE 都应可达，实际 $reachable")
        assertTrue(portability >= 1, "PRINTFORML GETFONT() 应命中可移植性诊断，实际 $portability")
        // summary 落在「明细：」之后、「结论：」之前（不动既有输出的位置约定）。
        val summaryAt = run.stdout.indexOf(hit.value)
        assertTrue(summaryAt > run.stdout.indexOf("明细："), "summary 应在明细行之后")
        assertTrue(summaryAt < run.stdout.indexOf("结论："), "summary 应在结论行之前")

        val tsv = Files.readString(reachOut)
        assertTrue(tsv.startsWith("kind\tgrade\tfunction\tfile\tline\tdetail\n"), "首行应是固定表头")
        assertTrue(
            tsv.contains("reachable\t-\tSYSTEM_TITLE\t01_MAIN.ERB\t1\tdepth=0 root=SYSTEM_TITLE"),
            "根自身应占一行（depth=0）:\n$tsv",
        )
        assertTrue(
            tsv.contains("reachable\t-\tSUB_ONE\t01_MAIN.ERB\t8\tdepth=1 root=SYSTEM_TITLE"),
            "CALL 边应把 @SUB_ONE 记成 depth=1:\n$tsv",
        )
        assertTrue(
            tsv.contains("portability\tdisplay\tSYSTEM_TITLE\t01_MAIN.ERB\t5\tGETFONT PRINTFORML GETFONT()"),
            "GETFONT 行应判 display:\n$tsv",
        )
        assertFalse(
            tsv.contains(dir.toAbsolutePath().normalize().toString()),
            "报告不应含绝对路径",
        )
        // 未绑定符号 TSV 与缺省关基线逐字节相同（开启报告不改既有产物）。
        assertEquals(baseline("unresolved-symbols.tsv"), Files.readString(out))
    }

    @Test
    fun `同一语料两次运行记录逐字节相同`() {
        val game = ReachabilityCliFixture.writeGame(dir)
        val first = dir.resolve("r1").resolve("reach.tsv")
        val second = dir.resolve("r2").resolve("reach.tsv")
        runAudit(game, dir.resolve("r1").resolve("u.tsv"), "--reachability", "--reach-out", first.toString())
        runAudit(game, dir.resolve("r2").resolve("u.tsv"), "--reachability", "--reach-out", second.toString())
        assertEquals(Files.readString(first), Files.readString(second), "同一语料两次运行应逐字节相同")
    }
}

/** CLI 用例的合成语料（`@TempDir` 现场写；与录制基线时逐字节同一份）。 */
internal object ReachabilityCliFixture {

    fun writeGame(dir: Path): Path {
        val game = dir.resolve("game")
        val erb = game.resolve("ERB")
        Files.createDirectories(erb)
        // 空 CSV：GameDataLoader 走缺省注册表（与 SymbolLinkAuditTest 同口径）
        Files.createDirectories(game.resolve("CSV"))
        Files.writeString(erb.resolve("DIM.ERH"), "#DIM CONST C_BASE = 3\n")
        Files.writeString(
            erb.resolve("01_MAIN.ERB"),
            """
            @SYSTEM_TITLE
            #DIM BUF, 3
            BUF:0 = C_BASE
            PRINTFORML {BUF:0}
            PRINTFORML GETFONT()
            CALL SUB_ONE

            @SUB_ONE
            #DIM BAD, 10 = NONEXISTENT
            BAD:0 = 1
            PRINTFORML ONE
            """.trimIndent() + "\n",
        )
        return game
    }
}
