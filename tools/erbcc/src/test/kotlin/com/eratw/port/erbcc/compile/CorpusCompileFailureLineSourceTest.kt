package com.eratw.port.erbcc.compile

import com.eratw.port.core.compile.CompileException
import com.eratw.port.core.err.erbDisplayLine
import com.eratw.port.core.expr.ExprParseException
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.stmt.StmtParseException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * erbcc 侧失败条目的**行号来源**与坐标系（lane compreadout，A14，2026-11-05）。
 *
 * 与 core-vm 装置那一侧（`core-vm/.../CorpusCompileAttributionTest`）同一口径：
 * - [CompileFailure.line] = **内部 0 基**下标，取的是异常自带字段
 *   （`CompileException.line` / `LexError.lineNo`）或声明/切片头行，
 *   **不是**从 message 里刮「行:N」；
 * - 这一列同时是**键面**：`Main.kt` 的容错清单按 `"文件:行"` 比对
 *   （`devtools/erbc-degraded-allowlist.tsv` 表头写明「0 基，与 CompileFailure 口径一致」），
 *   所以本波**只加来源列、不动数值**；给人看的 1 基走 [CompileFailure.displayLine]
 *   （唯一出口 [erbDisplayLine]，`core/err/CodeEE.kt`）。
 *
 * 断言逐条读数级：比较具体的 line / lineSource / displayLine / toString 片段，
 * 不做 `manifest.contains(...)` 那种形状断言。
 */
class CorpusCompileFailureLineSourceTest {

    private fun erb(dir: Path, name: String, text: String): Path {
        Files.createDirectories(dir)
        val f = dir.resolve(name)
        Files.write(f, text.trimIndent().toByteArray(Charsets.UTF_8))
        return f
    }

    // ------------------------------------------------------------------
    // 1) 真装置：CompileException 的 0 基字段直进 CompileFailure.line
    // ------------------------------------------------------------------

    /**
     * ```text
     * 第 1 行  @ERBC_PROBE     （0 基 0；切片头行）
     * 第 2 行  PRINTL 1        （0 基 1）
     * 第 3 行  RETURNF 2       （0 基 2）← 非 #FUNCTION 内 ⇒ CompileException(line=2)
     * 第 4 行  PRINTL 3        （0 基 3）← 按 message 反查会错指到这一条
     * ```
     */
    @Test
    fun compileFailureLineComesFromTheExceptionField(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        erb(
            corpus, "01_probe.ERB",
            """
            @ERBC_PROBE
            PRINTL 1
            RETURNF 2
            PRINTL 3
            """,
        )
        val result = CorpusCompile.compile(corpus)
        assertEquals(1, result.failures.size, "应恰好 1 条编译失败，实际=${result.failures.map { it.message }}")
        val f = result.failures.first { it.message.contains("ERBC_PROBE") }
        assertEquals(2, f.line, "0 基下标：物理第 3 行的 RETURNF ⇒ 2（取 CompileException.line）")
        assertEquals(3, f.displayLine, "1 基展示行号 = 物理行号（唯一出口 erbDisplayLine）")
        assertEquals(FailLineSource.COMPILE_EXCEPTION, f.lineSource, "来源必须标成异常字段，不是消息刮取")
        // 键面读数不变：容错清单比的仍是 `文件:行`（0 基），本波没动它
        assertEquals("01_probe.ERB:2 " + f.message, f.toString(), "toString 仍打 0 基键面值，格式 文件:行 文案 不变")
        assertTrue(f.message.startsWith("[ERBC_PROBE] "), "前缀口径不变：sigKey 打头")
        // 物理回读：1 基 3 ⇒ 第 3 行确实是 RETURNF
        val physical = corpus.resolve("01_probe.ERB").toFile().readLines()
        assertEquals("RETURNF 2", physical[f.displayLine - 1].trim(), "按 displayLine 回读必须命中抛点")
        // 差一现证：把 `line` 那列当 1 基行号去数（第 2 行），读到的是抛点的**上一行**
        assertEquals("PRINTL 1", physical[f.line - 1].trim(), "差一现证：把 0 基下标当行号数会少一行")
    }

    /** message 换成 1 基形状（lane errtext 的 `f843f46d` 那一笔）也不动读数。 */
    @Test
    fun messageShapeDoesNotMoveCompileFailureLine() {
        val messages = listOf(
            CompileException("ERBC_PROBE", 2, "RETURNF 只能在 #FUNCTION 内").message!!,
            "编译错误(函数:ERBC_PROBE, 行:3): RETURNF 只能在 #FUNCTION 内", // errtext 合入后的 1 基形状
            "COMPILE-FAILED <<<ERBC_PROBE>>> :: RETURNF misuse",
            "",
        )
        for (m in messages) {
            val f = CompileFailure("x.ERB", 2, "[$m]", FailLineSource.of(CompileException("ERBC_PROBE", 2, m)))
            assertEquals(2, f.line, "四种消息形状下键面值恒 2：$m")
            assertEquals(3, f.displayLine)
            assertEquals(FailLineSource.COMPILE_EXCEPTION, f.lineSource)
        }
    }

    // ------------------------------------------------------------------
    // 2) 词法臂 / 毒化切片：来源分类
    // ------------------------------------------------------------------

    /**
     * ```text
     * 第 1 行  @LEX_PROBE   （0 基 0）
     * 第 2 行  PRINTL 1     （0 基 1）
     * 第 3 行  }            （0 基 2）← LexError(lineNo=2)
     * ```
     */
    @Test
    fun lexErrorAndPoisonedSliceArmsLabelTheirLineSources(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        erb(
            corpus, "02_lex.ERB",
            """
            @LEX_PROBE
            PRINTL 1
            }
            PRINTL 2
            """,
        )
        val result = CorpusCompile.compile(corpus)
        val lex = result.failures.first { it.message.startsWith("词法错误") }
        assertEquals(2, lex.line, "LexError.lineNo 是 0 基（LogicalLineReader 的 curNo）")
        assertEquals(3, lex.displayLine)
        assertEquals(FailLineSource.LEX_ERROR, lex.lineSource)
        val poison = result.failures.first { it.message.contains("切片含解析失败行") }
        assertEquals(0, poison.line, "毒化切片记的是 @函数 头行（0 基 0），粒度到切片不到行")
        assertEquals(FailLineSource.SLICE_HEAD, poison.lineSource)
    }

    // ------------------------------------------------------------------
    // 3) 分类器逐档（各臂的行号来源判据，不靠文案）
    // ------------------------------------------------------------------

    @Test
    fun failLineSourceClassifierIsPerType() {
        assertEquals(FailLineSource.COMPILE_EXCEPTION, FailLineSource.of(CompileException("F", 5, "x")))
        assertEquals(FailLineSource.FUNCTION_LEVEL_FALLBACK, FailLineSource.of(CompileException("F", -1, "函数重复声明")))
        assertEquals(FailLineSource.LEX_ERROR, FailLineSource.of(LexError("x", lineNo = 4)))
        assertEquals(FailLineSource.NO_SOURCE, FailLineSource.of(LexError("x")), "lineNo 缺省 -1 = 无位置")
        assertEquals(FailLineSource.PARSE_EXCEPTION, FailLineSource.of(StmtParseException(3, 0, "", "x")))
        assertEquals(FailLineSource.PARSE_EXCEPTION, FailLineSource.of(ExprParseException(2, 0, "x")))
        // 负行号 = 「异常没带位置」，与 core 侧 structuredLineOf 同条（不许标成有结构化行号）
        assertEquals(
            FailLineSource.NO_SOURCE, FailLineSource.of(StmtParseException(-1, 0, "", "x")),
            "StmtParseException.line<0 ⇒ 无结构化行号（调用点翻转成 LOGICAL_LINE）",
        )
        assertEquals(
            FailLineSource.NO_SOURCE, FailLineSource.of(ExprParseException(-1, 0, "x")),
            "ExprParseException.line<0 ⇒ 无结构化行号",
        )
        assertEquals(FailLineSource.CRASH_NO_STRUCTURED_LINE, FailLineSource.of(IllegalStateException("行:9 只是个诱饵")))
        assertEquals(
            FailLineSource.CRASH_NO_STRUCTURED_LINE,
            FailLineSource.of(IllegalStateException(CompileException("F", 7, "x"))),
            "包装在别的异常里的 CompileException **不**被拆出来当行号来源 —— 宁少不多，避免假归因",
        )
    }

    /** 1 基出口只有一处：无位置 -1 原样、0 基 0 ⇒ 1 基 1。 */
    @Test
    fun displayLineIsTheSingleOutlet() {
        assertEquals(3, erbDisplayLine(2))
        assertEquals(1, erbDisplayLine(0))
        assertEquals(-1, erbDisplayLine(-1))
        assertEquals(-1, CompileFailure("f", -1, "m").displayLine)
        assertEquals("f:-1 m", CompileFailure("f", -1, "m").toString(), "-1 不进键面换算，原样打印")
    }
}
