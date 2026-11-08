package com.eratw.port.erbcc.audit

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 可达性报告的静态面（MODERN-R6 §3.1 / §3.2）。
 *
 * 判据（权威 = `IdentifierDictionary.cs:72-89` 事件名 / `:91-135` 系统名）：
 * 根 = 事件名 ∪ 系统名；调用边按语句位关键字走 BFS，深度 = 最短调用层数；
 * 静态不可判定的目标（FORMS 模板 / 列表 / 表达式）落成 `dynamic-call` 行，报告是下界。
 *
 * 语料全部 `@TempDir` 现场写，不依赖 `ERATW_CORPUS`。
 */
class ReachabilityAuditTest {

    @TempDir
    lateinit var dir: Path

    private fun write(files: Map<String, String>): Path {
        val erb = dir.resolve("game").resolve("ERB")
        Files.createDirectories(erb)
        for ((name, text) in files) {
            val p = erb.resolve(name)
            Files.createDirectories(p.parent)
            Files.writeString(p, text)
        }
        return erb
    }

    private fun reachableRows(report: ReachabilityReport): Map<String, String> =
        report.rows
            .filter { it.kind == ReachabilityKind.REACHABLE }
            .associate { it.function to it.detail }

    @Test
    fun `根集合来自事件与系统名 孤儿不可达`() {
        val erb = write(
            mapOf(
                "01.ERB" to """
                    @SYSTEM_TITLE
                    CALL SUB_A

                    @SUB_A
                    CALL SUB_B

                    @SUB_B
                    PRINTFORML B

                    @ORPHAN
                    PRINTFORML O
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        assertEquals(1, report.roots, "只有 @SYSTEM_TITLE 是根")
        assertEquals(3, report.reachable, "SYSTEM_TITLE → SUB_A → SUB_B 共 3 个，@ORPHAN 不可达")
        val rows = reachableRows(report)
        assertEquals("depth=0 root=SYSTEM_TITLE", rows["SYSTEM_TITLE"])
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_A"])
        assertEquals("depth=2 root=SYSTEM_TITLE", rows["SUB_B"])
        assertFalse(rows.containsKey("ORPHAN"), "@ORPHAN 没有调用边，不能出现在可达集里")
        assertEquals(
            "reachability: roots=1 reachable=3 dynamic-calls=0 portability=0 persist=0",
            report.summaryLine(),
        )
    }

    @Test
    fun `事件名同样是根`() {
        val erb = write(
            mapOf(
                "01.ERB" to """
                    @EVENTFIRST
                    CALL EV_ONLY

                    @EV_ONLY
                    PRINTFORML E

                    @SYSTEM_TITLE
                    PRINTFORML T
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        assertEquals(2, report.roots, "EVENTFIRST（IdentifierDictionary.cs:76-84）与 SYSTEM_TITLE 都是根")
        assertEquals(3, report.reachable)
        val rows = reachableRows(report)
        assertEquals("depth=0 root=EVENTFIRST", rows["EVENTFIRST"])
        assertEquals("depth=1 root=EVENTFIRST", rows["EV_ONLY"])
        assertEquals("depth=0 root=SYSTEM_TITLE", rows["SYSTEM_TITLE"])
    }

    @Test
    fun `静态不可判定的调用落成 dynamic-call 行`() {
        val erb = write(
            mapOf(
                "01.ERB" to """
                    @SYSTEM_TITLE
                    CALLFORM SUB_%C_OK%
                    TRYCALLLIST TARGETS
                    CALL "SUB_X"

                    @SUB_X
                    PRINTFORML X
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        // 引号字面量目标能静态解析 ⇒ 只有前两条是动态
        assertEquals(2, report.dynamicCalls, "报告行:\n${report.rows.joinToString("\n")}")
        val dynamic = report.rows.filter { it.kind == ReachabilityKind.DYNAMIC_CALL }
        assertEquals(listOf(2, 3), dynamic.map { it.line }, "动态调用行应落在原文行号（1 基）")
        assertEquals(
            listOf(
                "CALLFORM CALLFORM SUB_%C_OK%",
                "TRYCALLLIST TRYCALLLIST TARGETS",
            ),
            dynamic.map { it.detail },
        )
        assertTrue(dynamic.all { it.grade == "-" }, "dynamic-call 的 grade 列固定为 -")
        assertEquals("depth=1 root=SYSTEM_TITLE", reachableRows(report)["SUB_X"], "CALL \"SUB_X\" 是静态边")
    }

    @Test
    fun `JUMP 先本函数再本文件`() {
        val erb = write(
            mapOf(
                "01.ERB" to """
                    @SYSTEM_TITLE
                    JUMP SHARED

                    @HELPER
                    ${'$'}SHARED
                    PRINTFORML H
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        // `$SHARED` 不在 @SYSTEM_TITLE 体内 ⇒ 退到同文件的首个声明者（契约 §3.2.3）。
        assertEquals("depth=1 root=SYSTEM_TITLE", reachableRows(report)["HELPER"])
    }

    @Test
    fun `显式追加根按 depth 0 计入`() {
        val erb = write(
            mapOf(
                "01.ERB" to """
                    @SYSTEM_TITLE
                    PRINTFORML T

                    @ISLAND
                    PRINTFORML I
                """.trimIndent(),
            ),
        )
        val plain = ReachabilityAudit.audit(erb)
        assertEquals(1, plain.roots)
        assertEquals(1, plain.reachable)
        val extra = ReachabilityAudit.audit(erb, extraRoots = listOf("island"))
        assertEquals(2, extra.roots, "名字大小写不敏感，island 应命中 @ISLAND")
        assertEquals(2, extra.reachable)
        assertEquals("depth=0 root=ISLAND", reachableRows(extra)["ISLAND"])
    }

    @Test
    fun `同一语料两次运行报告逐字节相同`() {
        val erb = write(
            mapOf(
                "DIM.ERH" to "#DIM CONST C_ONE = 1\n",
                "01.ERB" to """
                    @SYSTEM_TITLE
                    #DIM LOCAL, 3
                    LOCAL:0 = CLIENTWIDTH()
                    CALL SUB_A
                    CALLFORM SUB_%C_ONE%

                    @SUB_A
                    PRINTFORML A
                """.trimIndent(),
            ),
        )
        val first = ReachabilityAudit.audit(erb)
        val second = ReachabilityAudit.audit(erb)
        assertEquals(first.rows.map { it.toStringRow() }, second.rows.map { it.toStringRow() })
        val a = dir.resolve("a").resolve("reach.tsv")
        val b = dir.resolve("b").resolve("reach.tsv")
        first.writeTsv(a)
        second.writeTsv(b)
        val ta = Files.readString(a)
        assertEquals(ta, Files.readString(b), "两次运行的 TSV 应逐字节相同")
        assertTrue(ta.startsWith("kind\tgrade\tfunction\tfile\tline\tdetail\n"))
        assertFalse(ta.contains(dir.toAbsolutePath().normalize().toString()), "TSV 不应含绝对路径")
    }
}

/** 报告行的可比较形态（只给测试内做逐字节对拍用）。 */
private fun ReachabilityRow.toStringRow(): String =
    listOf(kind.label, grade, function, file, line.toString(), detail).joinToString("\t")
