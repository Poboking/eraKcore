package com.eratw.port.erbcc.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.erbcc.cmdCompile
import com.eratw.port.erbcc.listedSourceMissing
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `compile --mode strict|compat --allow-parse-failures <清单>` 的契约。
 *
 * 原版 Emuera 对单函数解析失败只标错误行、游戏照常装载（ErbLoader.cs:1007、:1610-1613）；
 * 本侧缺省档是**更严的出包闸**（任何失败都拒落盘）。兼容档（[compat]）只放行清单内的失败，
 * 并把失败切片降级成入口 THROW 桩（运行到即抛，等价原版「该函数不可用」）：
 * 清单外的失败、清单项未命中（源修好/行号漂移）都必须退出码 1 —— 兼容档不能变成
 * 「吞掉未来编译器回归」的万能钥匙。
 *
 * 2026-11-04（lane allowlist）补钉的三条，都源自「13 条清单全未命中、compat 档出不了包」那次实调：
 * 1. **未命中的成因要分桶报**：清单项的源文件根本不在语料根（口上包没复制进来 / 换了根 / 包撤下）
 *    与「文件在、这些行没失败（修好了 / 行号漂移）」是两种修法，退出码同为 1，但报文不许都写成
 *    「源已修好或行号漂移」—— 否则会把人支去对不存在文件的行号（[listedSourceMissing] 是分桶判据）。
 * 2. **空清单 + 零失败不许谎称降级**：兼容档这时什么都没容错（产物与严格档逐字节一致、
 *    manifest 连 degraded-slices 段都不写），读数必须点名「清单 0 条在册、未降级任何切片」；
 *    放行本身照旧（退 1 会让 compat 通道在清单清空后彻底出不了包，而取证口是严格档）。
 * 3. **清单路径的字符集**：真语料的口上路径是「中文目录 + 中隔点 U+30FB + 方括号 + 中文文件名」，
 *    比对全在 JVM 字符串面（清单按 UTF-8 读、路径由文件系统解码），cp936 控制台把 `・` 打成 `?`
 *    只是上屏问题 —— 用例钉住这种路径能命中，免得下次把「全部未命中」误判成编码问题。
 */
class ParseFailureToleranceTest {

    /** 一个坏切片（未定义标识符）+ 一个正常函数：原版口径下游戏可装载，坏函数不可用。 */
    private fun writeMixedCorpus(dir: Path): Path {
        val corpus = dir.resolve("corpus")
        Files.createDirectories(corpus)
        Files.write(
            corpus.resolve("01_main.ERB"),
            """
            |@SYSTEM_TITLE
            |PRINTL HI
            |
            |@BAD_SLICE
            |SELECTCASE 1
            |CASE 根本不存在的常量
            |	PRINTL X
            |ENDSELECT
            |
            """.trimMargin().toByteArray(Charsets.UTF_8),
        )
        return corpus
    }

    /** 用真实编译结果生成清单（不手写行号，避免行号口径耦合）。 */
    private fun actualFailures(corpus: Path): List<CompileFailure> =
        CorpusCompile.compile(corpus).failures.also { assertTrue(it.isNotEmpty(), "合成语料必须真的有失败") }

    private fun writeAllowList(dir: Path, entries: List<String>): Path {
        val allow = dir.resolve("allow.tsv")
        Files.write(allow, entries.joinToString("\n").toByteArray(Charsets.UTF_8))
        return allow
    }

    @Test
    fun `缺省档解析失败仍拒落盘`(@TempDir dir: Path) {
        val corpus = writeMixedCorpus(dir)
        val out = dir.resolve("out")

        assertEquals(1, cmdCompile(listOf("--corpus", corpus.toString(), "--out", out.toString())))
        assertFalse(Files.exists(out.resolve("erbc.v1/program.erbc")), "缺省档不得落盘")
    }

    @Test
    fun `strict 档不接受容错清单`(@TempDir dir: Path) {
        val corpus = writeMixedCorpus(dir)
        val out = dir.resolve("out")
        val allow = writeAllowList(dir, actualFailures(corpus).map { "${it.file}:${it.line}" })

        // CLI 参数错误走 err() → exitProcess(2)，进程内无法断言退出码；改用 build 期 Gradle
        // 与 `--mode` 语义覆盖。这里只验证「strict + 清单」在有解析失败时同样不落盘。
        // （strict 档根本不读清单：等价于不走兼容档。）
        assertEquals(
            1,
            cmdCompile(
                listOf("--corpus", corpus.toString(), "--out", out.toString(), "--mode", "strict"),
            ),
        )
        assertFalse(Files.exists(out.resolve("erbc.v1/program.erbc")), "strict 档不得落盘")
    }

    @Test
    fun `兼容档清单全命中时落盘且 manifest 留痕`(@TempDir dir: Path) {
        val corpus = writeMixedCorpus(dir)
        val out = dir.resolve("out")
        val failures = actualFailures(corpus)
        val allow = writeAllowList(dir, failures.map { "${it.file}:${it.line}" })

        assertEquals(
            0,
            cmdCompile(
                listOf(
                    "--corpus", corpus.toString(), "--out", out.toString(),
                    "--mode", "compat",
                    "--allow-parse-failures", allow.toString(),
                ),
            ),
        )
        assertTrue(Files.isRegularFile(out.resolve("erbc.v1/program.erbc")), "兼容档必须落盘")
        val manifest = String(Files.readAllBytes(out.resolve("erbc.v1/manifest.txt")), Charsets.UTF_8)
        assertTrue(manifest.contains("degraded-slices ${failures.size}\n"), "manifest 必须登记降级条数")
        assertTrue(manifest.contains("degraded 01_main.ERB:"), "manifest 必须逐条登记降级点")
    }

    @Test
    fun `兼容档失败切片编成入口 THROW 桩`(@TempDir dir: Path) {
        val corpus = writeMixedCorpus(dir)

        val strict = CorpusCompile.compile(corpus)
        val compat = CorpusCompile.compile(corpus, degradedStub = true)

        fun entry(name: String, r: CompileResult): Int {
            val st = r.program!!.functions.first { it.name.contains(name, ignoreCase = true) }
            return st.entryPc
        }

        // strict 档降级体为空（随后整包被 CLI 拒落盘），入口不是 THROW。
        val strictPc = entry("BAD_SLICE", strict)
        assertFalse(strict.program!!.op(strictPc) == Opcode.THROW, "严格档不应合成 THROW")

        // 兼容档降级体 = 入口 THROW：调用时立刻抛，而不是静默返回默认值。
        val compatPc = entry("BAD_SLICE", compat)
        assertEquals(Opcode.THROW, compat.program!!.op(compatPc), "兼容档失败切片必须是入口 THROW")
        // 文案取册内官方原文（ADR-005）：原版 `Lang.Error.CalledFailedFunc`，不是自造串。
        // THROW 桩以语句池 marker 存储，故扫语句池里的字面量（不是字符串常量池）。
        val official = com.eratw.port.core.lexer.LangKey.CalledFailedFunc.renderZhs()
        val p = compat.program!!
        val literalTexts = (0 until p.statementCount).mapNotNull { i ->
            (p.statement(i) as? com.eratw.port.core.stmt.InstructionStmt)
                ?.args
                ?.let { it as? com.eratw.port.core.stmt.ExpressionArgument }
                ?.literalStr
        }
        assertTrue(
            literalTexts.contains(official),
            "降级桩必须内嵌官方文案「$official」；语句池字面量 ${literalTexts.distinct()}",
        )
        assertFalse(
            literalTexts.any { it.contains("在装载时出错") },
            "自造桩文案必须已退役",
        )
    }

    @Test
    fun `兼容档清单外的失败拒绝容错`(@TempDir dir: Path) {
        val corpus = writeMixedCorpus(dir)
        val out = dir.resolve("out")
        val failures = actualFailures(corpus)
        // 只钉住第一条，其余失败就是「清单外」
        val allow = writeAllowList(dir, listOf("${failures.first().file}:${failures.first().line}"))

        assertEquals(
            1,
            cmdCompile(
                listOf(
                    "--corpus", corpus.toString(), "--out", out.toString(),
                    "--mode", "compat",
                    "--allow-parse-failures", allow.toString(),
                ),
            ),
            "清单外失败必须被拒",
        )
        assertFalse(Files.exists(out.resolve("erbc.v1/program.erbc")), "被拒时不得落盘")
    }

    @Test
    fun `兼容档清单项未命中视为过期`(@TempDir dir: Path) {
        val corpus = writeMixedCorpus(dir)
        val out = dir.resolve("out")
        val failures = actualFailures(corpus)
        val allow = writeAllowList(
            dir,
            failures.map { "${it.file}:${it.line}" } + "01_main.ERB:9999",
        )

        assertEquals(
            1,
            cmdCompile(
                listOf(
                    "--corpus", corpus.toString(), "--out", out.toString(),
                    "--mode", "compat",
                    "--allow-parse-failures", allow.toString(),
                ),
            ),
            "源修好或行号漂移后清单必须失效",
        )
    }

    @Test
    fun `全绿语料两种档位产物一致`(@TempDir dir: Path) {
        val corpus = dir.resolve("clean")
        Files.createDirectories(corpus)
        Files.write(corpus.resolve("01_main.ERB"), "@SYSTEM_TITLE\nPRINTL HI\n".toByteArray(Charsets.UTF_8))
        val emptyAllow = writeAllowList(dir, emptyList())

        val strictOut = dir.resolve("out-strict")
        val tolerantOut = dir.resolve("out-tolerant")
        assertEquals(0, cmdCompile(listOf("--corpus", corpus.toString(), "--out", strictOut.toString())))
        assertEquals(
            0,
            cmdCompile(
                listOf(
                    "--corpus", corpus.toString(), "--out", tolerantOut.toString(),
                    "--mode", "compat",
                    "--allow-parse-failures", emptyAllow.toString(),
                ),
            ),
        )
        // 兼容档不得改变正常语料的产物字节（同语料不同 out，容器摘要应相等）
        assertEquals(
            ErbcArtifacts.sha256Hex(Files.readAllBytes(strictOut.resolve("erbc.v1/program.erbc"))),
            ErbcArtifacts.sha256Hex(Files.readAllBytes(tolerantOut.resolve("erbc.v1/program.erbc"))),
        )
    }

    // -------------------------------------------------------------------------
    // 清单项未命中的成因分桶（2026-11-04 lane allowlist：13 条全未命中，真因是
    // 「口上包没复制进语料根」，旧报文一律写「源已修好或行号漂移」，把成因盖掉了）
    // -------------------------------------------------------------------------

    @Test
    fun `清单外的源文件未命中时报文点名缺文件而不是行号漂移`(@TempDir dir: Path) {
        val corpus = writeMixedCorpus(dir)
        val out = dir.resolve("out")
        val allow = writeAllowList(
            dir,
            actualFailures(corpus).map { "${it.file}:${it.line}" } + "没有这个包.ERB:7",
        )

        var code = -1
        val err = captureStderr {
            code = cmdCompile(
                listOf(
                    "--corpus", corpus.toString(), "--out", out.toString(),
                    "--mode", "compat",
                    "--allow-parse-failures", allow.toString(),
                ),
            )
        }
        assertEquals(1, code, "清单项未命中（哪怕成因是缺文件）必须退 1")
        assertFalse(Files.exists(out.resolve("erbc.v1/program.erbc")), "被拒时不得落盘")
        assertContains(err, "源文件不在语料根", message = "缺文件的未命中必须单独成桶：$err")
        assertContains(err, "没有这个包.ERB:7")
        assertFalse(err.contains("容错清单已过期"), "缺文件不该被报成「源已修好或行号漂移」：$err")
    }

    @Test
    fun `在册文件在但行未命中仍报行号漂移那一桶`(@TempDir dir: Path) {
        val corpus = writeMixedCorpus(dir)
        val out = dir.resolve("out")
        // 只钉住真实失败的头一条；`01_main.ERB:9999` 的源文件**在**语料里，只是那一行没失败
        val first = actualFailures(corpus).first()
        val allow = writeAllowList(dir, listOf("${first.file}:${first.line}", "01_main.ERB:9999"))

        var code = -1
        val err = captureStderr {
            code = cmdCompile(
                listOf(
                    "--corpus", corpus.toString(), "--out", out.toString(),
                    "--mode", "compat",
                    "--allow-parse-failures", allow.toString(),
                ),
            )
        }
        assertEquals(1, code, "行号漂移的未命中必须退 1")
        assertContains(err, "容错清单已过期：1 项", message = "在册文件在、行没命中 ⇒ 走「已过期」桶：$err")
        assertContains(err, "01_main.ERB:9999")
        assertFalse(err.contains("源文件不在语料根"), "这一条的源文件确实在语料里：$err")
    }

    @Test
    fun `空清单且零失败时读数说未降级任何切片`(@TempDir dir: Path) {
        val corpus = dir.resolve("clean")
        Files.createDirectories(corpus)
        Files.write(corpus.resolve("01_main.ERB"), "@SYSTEM_TITLE\nPRINTL HI\n".toByteArray(Charsets.UTF_8))
        val emptyAllow = writeAllowList(dir, listOf("# 只有注释：0 条在册"))
        val out = dir.resolve("out")

        var code = -1
        val err = captureStderr {
            code = cmdCompile(
                listOf(
                    "--corpus", corpus.toString(), "--out", out.toString(),
                    "--mode", "compat",
                    "--allow-parse-failures", emptyAllow.toString(),
                ),
            )
        }
        assertEquals(0, code, "空清单 + 零失败：没有东西被容错，产物等同严格档，必须放行")
        assertTrue(Files.isRegularFile(out.resolve("erbc.v1/program.erbc")), "必须照常落盘（否则兼容档通道出不了包）")
        assertContains(err, "清单 0 条在册", message = "读数要点名「清单是空的」：$err")
        assertContains(err, "未降级任何切片")
        assertFalse(err.contains("条预期内失败"), "零降级时不许打「N 条预期内失败…THROW 桩随包」：$err")
        val manifest = String(Files.readAllBytes(out.resolve("erbc.v1/manifest.txt")), Charsets.UTF_8)
        assertFalse(manifest.contains("degraded"), "干净产物不许写 degraded 段（manifest 字节与严格档一致）")
    }

    @Test
    fun `中文与中隔点目录的清单路径能命中`(@TempDir dir: Path) {
        // 真语料里第三方口上的路径形态：中文目录 + 中隔点 U+30FB + 方括号 + 中文文件名。
        // 钉住两件事：①清单按 UTF-8 读、CompileFailure 的路径由文件系统解码，比对全在字符串面，
        // 控制台把 `・` 打成 `?` 只是 cp936 的上屏问题，不是匹配问题；②这种路径的条目能正常命中。
        val pkg = "口上・メッセージ関連/個人口上/016 Remilia [レミリア]/蕾米莉亚色情特化v1.3"
        val corpus = dir.resolve("corpus")
        val pkgDir = corpus.resolve(pkg)
        Files.createDirectories(pkgDir)
        val file = pkgDir.resolve("M_KOJO_K16_5_日常系指令.ERB")
        Files.write(
            file,
            """
            |@SYSTEM_TITLE
            |PRINTL HI
            |
            |@M_KOJO_色情特化_MESSAGE_COM_K16_413
            |SELECTCASE 1
            |CASE 根本不存在的常量
            |	PRINTL X
            |ENDSELECT
            |
            """.trimMargin().toByteArray(Charsets.UTF_8),
        )
        val failures = actualFailures(corpus)
        assertTrue(
            failures.all { it.file.startsWith(pkg) },
            "失败必须落在那条中文路径里，实为：${failures.map { it.file }}",
        )
        val allow = writeAllowList(dir, failures.map { "${it.file}:${it.line}" })
        val out = dir.resolve("out")

        var code = -1
        val err = captureStderr {
            code = cmdCompile(
                listOf(
                    "--corpus", corpus.toString(), "--out", out.toString(),
                    "--mode", "compat",
                    "--allow-parse-failures", allow.toString(),
                ),
            )
        }
        assertEquals(0, code, "含中隔点的清单路径必须能命中（缺文件桶不许误报）：$err")
        assertFalse(err.contains("源文件不在语料根"), "源文件确实在语料根里：$err")
        val manifest = String(Files.readAllBytes(out.resolve("erbc.v1/manifest.txt")), Charsets.UTF_8)
        assertContains(manifest, "degraded-slices ${failures.size}")
    }

    @Test
    fun `分桶判据本身：缺文件真、在册文件假、无冒号算漂移`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        Files.createDirectories(corpus)
        Files.write(corpus.resolve("01_main.ERB"), "@SYSTEM_TITLE\nPRINTL HI\n".toByteArray(Charsets.UTF_8))
        Files.createDirectories(corpus.resolve("子目录"))
        Files.write(corpus.resolve("子目录/02.ERB"), "@A\nRETURN 1\n".toByteArray(Charsets.UTF_8))

        assertTrue(listedSourceMissing(corpus, "没有.ERB:3"), "不存在的源文件必须判成缺文件")
        assertFalse(listedSourceMissing(corpus, "01_main.ERB:3"), "在册文件不得判成缺文件")
        assertFalse(listedSourceMissing(corpus, "子目录/02.ERB:0"), "正斜杠的相对子路径同样能找到")
        assertFalse(listedSourceMissing(corpus, "没有冒号的整串"), "形状不对的项归「行号漂移」桶，不新造第三类")
        assertTrue(listedSourceMissing(corpus, "绝对也没有:1"), "另一台机器的路径形态同样算缺文件")
    }

    /** stderr 捕获（显式 UTF-8：测试 JVM 的默认字符集在本机是 cp936，靠默认值比字符串会假阴性）。 */
    private fun captureStderr(block: () -> Unit): String {
        val saved = System.err
        val buf = java.io.ByteArrayOutputStream()
        val ps = java.io.PrintStream(buf, true, "UTF-8")
        System.setErr(ps)
        try {
            block()
        } finally {
            ps.flush()
            System.setErr(saved)
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }
}
