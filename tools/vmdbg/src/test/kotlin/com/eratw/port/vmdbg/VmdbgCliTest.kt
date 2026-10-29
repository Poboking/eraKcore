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
 * call / where / var / vars / disasm 主干（CONTRACT-DBG1 路2）：
 * 合成 mini 语料（[MiniCorpus]）→ Cli.run 进程内调用 → 断言输出与退出码。
 * 断言口径 = CONTRACT-DBG1 路2 子命令规格表（状态串、函数名:行号:pc、步数、控制台尾 10 行）。
 */
class VmdbgCliTest {

    private lateinit var tmp: Path
    private lateinit var built: MiniCorpus.Built
    private lateinit var work: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-cli-test")
        built = MiniCorpus.build(tmp)
        work = tmp.resolve("work")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    /** 进程内跑一次 CLI，stdout/stderr 分开捕获。 */
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
            .takeWhile { it.contains('=') }
            .mapNotNull { l ->
                val i = l.indexOf('=')
                if (i <= 0) null else l.substring(0, i) to l.substring(i + 1)
            }
            .toMap()

    @Test
    fun callPassOutputsStatusStepsAndConsoleTail() {
        val r = run(*MiniCorpus.args(built, work, "call", "MINI_PASS"))
        assertEquals(0, r.code, r.out)
        val fields = kv(r.out)
        assertEquals("PASS", fields["status"])
        assertEquals("MINI_PASS", fields["target"])
        // PASS 无错误现场：location 为占位 -
        assertEquals("-", fields["location"])
        // 步数已读出且为正（反射 executedSteps；GameBootSmoke.kt:85 口径）
        assertTrue((fields["steps"]?.toLong() ?: -1L) > 0, "steps=${fields["steps"]}")
        assertTrue((fields["elapsedMs"]?.toLong() ?: -1L) >= 0)
        // 控制台尾 10 行段存在且包含 MINI_PASS 的 PRINTL 产出
        assertTrue(r.out.contains("--- console tail"), r.out)
        assertTrue(r.out.contains("mini-pass-ok"), r.out)
    }

    @Test
    fun callWithArgsPassesBothBuckets() {
        val r = run(*MiniCorpus.args(built, work, "call", "MINI_ARGS", "--args", "7,hello"))
        assertEquals(0, r.code, r.out)
        val fields = kv(r.out)
        assertEquals("PASS", fields["status"])
        // PRINTVL/PRINTSL 各自收尾成行，控制台尾应含两行实参回显
        assertTrue(r.out.contains("7"), "PRINTVL 应输出实参 7：${r.out}")
        assertTrue(r.out.contains("hello"), "PRINTSL 应输出实参 hello：${r.out}")
    }

    @Test
    fun callUnknownFunctionIsNotFoundWithExit2() {
        val r = run(*MiniCorpus.args(built, work, "call", "NO_SUCH_FUNC_QQ"))
        assertEquals(2, r.code, r.out)
        assertEquals("NOT_FOUND", kv(r.out)["status"])
    }

    @Test
    fun callScriptErrorClassifiesCodeeeAndRecordsSite() {
        val r = run(*MiniCorpus.args(built, work, "call", "MINI_CODEEE"))
        assertEquals(3, r.code, r.out)
        val fields = kv(r.out)
        assertEquals("CODEEE", fields["status"])
        // location = 函数名:行号:pc（CodeEE 结构化字段，stampPosition 以 StmtEnv 补齐，
        // ProcessRuntime.kt:684/:1547）。行号口径 = 移植侧行表（CALLFORM 所在物理行 -1，
        // 见本文件 mini.erb 布局注释）；断言钉当前行为。
        val loc = fields["location"] ?: ""
        assertTrue(loc.startsWith("MINI_CODEEE:"), "location=$loc")
        val line = loc.split(":").getOrNull(1)?.toIntOrNull() ?: -1
        assertTrue(line in 10..11, "CALLFORM 在 mini.erb 物理第 11 行，行表报 10..11：$loc")
        assertTrue((fields["message"] ?: "").contains("MINI_NOPE_XYZ"), "message 应点名未定义函数：${fields["message"]}")
    }

    @Test
    fun whereReadsLastErrorWrittenByCall() {
        run(*MiniCorpus.args(built, work, "call", "MINI_CODEEE"))
        val r = run(*MiniCorpus.args(built, work, "where"))
        assertEquals(0, r.code, r.out)
        val fields = kv(r.out)
        assertEquals("CODEEE", fields["status"])
        assertTrue((fields["funcName"] ?: "").contains("MINI_CODEEE"), r.out)
        assertTrue((fields["line"]?.toIntOrNull() ?: -1) >= 0, "line 应与 call 的 location 同源：${r.out}")
        assertTrue((fields["message"] ?: "").isNotEmpty(), r.out)
    }

    @Test
    fun whereWithoutHistoryReportsNoError() {
        val r = run(*MiniCorpus.args(built, work, "where"))
        assertEquals(0, r.code, r.out)
        assertEquals("NO_ERROR", kv(r.out)["status"])
    }

    @Test
    fun varReadsGlobalUserVar() {
        // 全局 #DIM GLOBAL_TOTAL（ERH）注册后缺省 0（私有 #DIM 出帧即失，不能当 var 断言对象）
        val r = run(*MiniCorpus.args(built, work, "var", "GLOBAL_TOTAL"))
        assertEquals(0, r.code, r.out)
        val fields = kv(r.out)
        assertEquals("PASS", fields["status"])
        assertEquals("GLOBAL_TOTAL[0]=0", r.out.lineSequence().firstOrNull { "GLOBAL_TOTAL" in it })
    }

    @Test
    fun varUndefinedIsNotFound() {
        val r = run(*MiniCorpus.args(built, work, "var", "NO_SUCH_VAR_QQ"))
        assertEquals(2, r.code, r.out)
        assertEquals("NOT_FOUND", kv(r.out)["status"])
    }

    @Test
    fun varsEnumeratesRegistryAndUserVarsWithGlob() {
        val r = run(*MiniCorpus.args(built, work, "vars", "GLOBAL_*"))
        assertEquals(0, r.code, r.out)
        val names = r.out.lines().filter { it.isNotBlank() }
        assertTrue("GLOBAL_TOTAL" in names, "vars 输出缺 GLOBAL_TOTAL：$names")
        assertTrue(names.all { it.startsWith("GLOBAL_") }, "glob 过滤失效：$names")

        val r2 = run(*MiniCorpus.args(built, work, "vars", "*RESULT*"))
        assertEquals(0, r2.code, r2.out)
        assertTrue("RESULT" in r2.out.lines(), "系统注册表变量 RESULT 应被枚举：${r2.out}")
    }

    @Test
    fun disasmPrintsWordsLineAndText() {
        val r = run(*MiniCorpus.args(built, work, "disasm", "0", "-n", "4"))
        assertEquals(0, r.code, r.out)
        // 头行 + 恰好 4 条指令行
        assertTrue(r.out.contains("instructionCount="), r.out)
        val rows = r.out.lines().filter { it.startsWith("pc=") && "instructionCount" !in it }
        assertEquals(4, rows.size, r.out)
        // 每行带 words=[op,a,b,c] 与 line=
        assertTrue(rows.all { "words=[" in it && "line=" in it }, r.out)
    }

    @Test
    fun disasmOutOfRangeIsUsageError() {
        val r = run(*MiniCorpus.args(built, work, "disasm", "999999"))
        assertEquals(1, r.code, "${r.out} | ${r.err}")
        assertTrue("越界" in r.err, r.err)
    }
}
