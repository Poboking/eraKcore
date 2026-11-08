package com.eratw.port.erbcc.audit

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.vars.DimSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 可移植性诊断的三档分级（MODERN-R6 §3.3）。
 *
 * 判据：
 * - `persist`：命中令牌的行写**持久变量**（`#DIM` 修饰符带 `SAVEDATA` / `GLOBAL`）——存档格式面；
 * - `display`：命中令牌但没有赋值，或只写普通变量 —— 展示适配面；
 * - `unknown`：赋值目标解析不出声明 —— 照实输出，不默认成 display。
 *
 * 只诊断**可达函数**；不可达函数里的令牌不进报告。
 * 语料全部 `@TempDir` 现场写，不依赖 `ERATW_CORPUS`。
 */
class PortabilityDiagnosticsTest {

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

    private fun portabilityRows(report: ReachabilityReport): Map<Int, ReachabilityRow> =
        report.rows
            .filter { it.kind == ReachabilityKind.PORTABILITY }
            .associateBy { it.line }

    @Test
    fun `持久 显示 未知三档与可达面过滤`() {
        val erb = write(
            mapOf(
                "DIM.ERH" to """
                    #DIMS SAVEDATA SD_TXT
                    #DIMS GLOBAL GM_TXT
                """.trimIndent(),
                "01.ERB" to """
                    @SYSTEM_TITLE
                    #DIMS LOCAL_S
                    #DIM LOCAL_I
                    PRINTFORML GETFONT()
                    LOCAL_S:0 = GETFONT()
                    LOCAL_I:0 = CLIENTWIDTH()
                    SD_TXT:0 = GETFONT()
                    GM_TXT:0 = CLIENTWIDTH()
                    NOPE:0 = GGETFONT(0)

                    @ORPHAN
                    PRINTFORML CLIENTHEIGHT()
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        val rows = portabilityRows(report)
        assertEquals(6, report.portability, "可达面里 6 行命中令牌，@ORPHAN 那行不算:\n${report.rows}")
        assertEquals(2, report.persist, "SD_TXT（SAVEDATA）与 GM_TXT（GLOBAL）两行进 persist")
        assertFalse(rows.containsKey(12), "@ORPHAN 不可达，其 CLIENTHEIGHT 行不得进报告")
        assertEquals(PortabilityGrade.DISPLAY.label, rows[4]?.grade, "无赋值的打印行是 display")
        assertEquals(PortabilityGrade.DISPLAY.label, rows[5]?.grade, "写普通私有变量的行是 display")
        assertEquals(PortabilityGrade.DISPLAY.label, rows[6]?.grade, "CLIENTWIDTH() 写普通私有变量也是 display")
        assertEquals(PortabilityGrade.PERSIST.label, rows[7]?.grade, "SAVEDATA 变量 ⇒ persist")
        assertEquals(PortabilityGrade.PERSIST.label, rows[8]?.grade, "GLOBAL 变量 ⇒ persist")
        assertEquals(PortabilityGrade.UNKNOWN.label, rows[9]?.grade, "解析不出声明的目标 ⇒ unknown")
        assertEquals("GETFONT PRINTFORML GETFONT()", rows[4]?.detail)
        assertTrue(rows[9]?.detail?.startsWith("GGETFONT ") == true, "词边界命中 GGETFONT，不误报 GETFONT")
        assertEquals("SYSTEM_TITLE", rows[7]?.function)
        assertTrue(rows.values.all { it.grade in setOf("persist", "display", "unknown") }, "grade 只允许三档")
    }

    @Test
    fun `侧车把未知目标抬出 unknown 档`() {
        val erb = write(
            mapOf(
                "01.ERB" to """
                    @SYSTEM_TITLE
                    P_SIDE = GETFONT()
                    G_SIDE = CLIENTWIDTH()
                """.trimIndent(),
            ),
        )
        // 无侧车：两个目标都没有声明可查 ⇒ unknown（下界照实报）。
        val plain = portabilityRows(ReachabilityAudit.audit(erb))
        assertEquals(PortabilityGrade.UNKNOWN.label, plain[2]?.grade)
        assertEquals(PortabilityGrade.UNKNOWN.label, plain[3]?.grade)

        // 带侧车：私有 #DIM 的 isSavedata 位 + 全局 #DIM 原文都参与判定（权威覆盖扫描面）。
        val boot = BootMetadata(
            globalDims = listOf(BootMetadata.RawDim("#DIM SAVEDATA G_SIDE", "DIM.ERH", 1)),
            privateDims = listOf(
                BootMetadata.PrivateDim(
                    "SYSTEM_TITLE",
                    DimSpec("P_SIDE", isStr = true, dims = IntArray(0), isSavedata = true),
                ),
            ),
        )
        val sidecar = portabilityRows(ReachabilityAudit.audit(erb, bootMetadata = boot))
        // 本条只钉「侧车参与判定 ⇒ 不再是 unknown」；具体档位由上面那条用例钉，
        // 这样「把 persist 全降成 display」的变异只会翻红一条（合同 §4 反证 B）。
        assertNotEquals(PortabilityGrade.UNKNOWN.label, sidecar[2]?.grade, "侧车私有维度应抬出 unknown")
        assertNotEquals(PortabilityGrade.UNKNOWN.label, sidecar[3]?.grade, "侧车全局维度应抬出 unknown")
        assertEquals("GETFONT P_SIDE = GETFONT()", sidecar[2]?.detail)
        assertEquals("CLIENTWIDTH G_SIDE = CLIENTWIDTH()", sidecar[3]?.detail)
    }
}
