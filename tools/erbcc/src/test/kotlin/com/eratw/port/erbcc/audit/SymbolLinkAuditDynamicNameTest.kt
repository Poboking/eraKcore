package com.eratw.port.erbcc.audit

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * CMP-77：链接审计对「运行期才解析成变量名的字符串」的使用点覆盖。
 *
 * 覆盖入口（首参为名字字符串，运行期才 `ErbLexer` + `ExprParser` 现解）：
 * GETVAR / GETVARS / SETVAR / VARSETEX / EXISTVAR / VARSIZE / GETNUMB。原版同一通道见
 * `FunctionMethodCreator.cs:377-405`（GetVarMethod）/ `:407-435`（GetVarsMethod），
 * 名字查表见 `IdentifierDictionary.cs:429-495`（GetVariableToken）。
 *
 * 本文件三类用例：
 * 1. 常量名字串指向**不存在**的变量 ⇒ 审计必须报 `VAR_UNBOUND` 且 `--strict` 退出码 1；
 * 2. 反向控制：合法动态名（首参是变量）不误报、已知存在的变量不报未绑定；
 * 3. CMP-78 的 subKey 盲区现状（`LOCAL@别函数` 不判未绑定）+ 私有/全局变量带 `@` 的必红红线。
 */
class SymbolLinkAuditDynamicNameTest {

    @TempDir
    lateinit var dir: Path

    private fun writeGame(files: Map<String, String>): Path {
        val game = dir.resolve("game")
        val erb = game.resolve("ERB")
        Files.createDirectories(erb)
        Files.createDirectories(game.resolve("CSV"))
        for ((name, text) in files) {
            val p = erb.resolve(name)
            Files.createDirectories(p.parent)
            Files.writeString(p, text)
        }
        return game
    }

    /** 解析审计 TSV：kind/file/function/line/name/detail/count。 */
    private fun readTsvRows(out: Path): List<List<String>> =
        Files.readAllLines(out).drop(1).filter { it.isNotBlank() }.map { it.split('\t') }

    @Test
    fun `GETVAR 常量名字串指向不存在变量时进审计并判红`() {
        val game = writeGame(
            mapOf(
                "01.ERB" to """
                    @DYN_VAR
                    #DIM YLOCAL, 3
                    YLOCAL:0 = 0
                    PRINTFORM {GETVAR("不存在变量")}
                """.trimIndent(),
            ),
        )
        val out = dir.resolve("audit.tsv")
        val code = com.eratw.port.erbcc.cmdAudit(
            listOf(
                "--corpus", game.resolve("ERB").toString(),
                "--game-root", game.toString(),
                "--out", out.toString(),
                "--strict",
            ),
        )
        val rows = readTsvRows(out)
        assertEquals(1, code, "常量名字串指向不存在变量时 --strict 必须判红；实际 TSV=$rows")
        val hit = rows.filter { it[0] == "VAR_UNBOUND" && it[4] == "不存在变量" }
        assertEquals(1, hit.size, "TSV 里应恰有一条该名字的未绑定记录；实际 $rows")
        val row = hit.single()
        assertEquals("01.ERB", row[1], "文件名（相对语料根）")
        assertEquals("DYN_VAR", row[2], "函数键取调用点所在函数")
        // 行号：本移植与 C# 同口径按 0 基计（@DYN_VAR=0 / #DIM=1 / YLOCAL=2 / PRINTFORM=3）
        // —— 取**调用点**行，不是字符串字面量自己的行号。
        assertEquals("3", row[3], "行号必须是调用点（PRINTFORM 那行）")
        assertTrue(row[5].contains("GETVAR"), "detail 应带入口名便于定位；实际 ${row[5]}")
    }

    @Test
    fun `VARSIZE 常量名字串同样进审计`() {
        val game = writeGame(
            mapOf(
                "01.ERB" to """
                    @DYN_SIZE
                    PRINTFORM {VARSIZE("不存在的数组")}
                """.trimIndent(),
            ),
        )
        val report = SymbolLinkAudit.audit(game.resolve("ERB"), game)
        assertTrue(report.result.ok, "合成语料应编译成功: ${report.result.failures}")
        val unbound = report.findings.filter { it.kind == FindingKind.VAR_UNBOUND }
        assertEquals(
            listOf("不存在的数组"),
            unbound.map { it.name },
            "VARSIZE 的名字串也应被二次解析；实际 ${report.findings}",
        )
        assertEquals(listOf("01.ERB"), unbound.map { it.file })
        assertEquals(listOf("DYN_SIZE"), unbound.map { it.functionKey })
    }

    @Test
    fun `反向控制 已知存在的变量不报未绑定`() {
        val game = writeGame(
            mapOf(
                "DIM.ERH" to "#DIM EXIST_I, 3\n",
                "01.ERB" to """
                    @DYN_EXISTS
                    EXIST_I:0 = 7
                    PRINTFORM {GETVAR("EXIST_I:0")}
                    PRINTFORM {EXISTVAR("EXIST_I")}
                """.trimIndent(),
            ),
        )
        val report = SymbolLinkAudit.audit(game.resolve("ERB"), game)
        assertTrue(report.result.ok, "合成语料应编译成功: ${report.result.failures}")
        assertEquals(
            emptyList(),
            report.findings.filter { it.kind == FindingKind.VAR_UNBOUND }.map { "${it.name}@${it.line}" },
            "已存在的变量不得判未绑定",
        )
    }

    @Test
    fun `反向控制 合法动态名不误报硬差异`() {
        val game = writeGame(
            mapOf(
                "DIM.ERH" to "#DIM EXIST_I, 3\n",
                "01.ERB" to """
                    @DYN_NAME_VAR
                    #DIMS NAME_STR, 3
                    NAME_STR = "EXIST_I:0"
                    PRINTFORM {GETVAR(NAME_STR)}
                """.trimIndent(),
            ),
        )
        val report = SymbolLinkAudit.audit(game.resolve("ERB"), game)
        assertTrue(report.result.ok, "合成语料应编译成功: ${report.result.failures}")
        assertEquals(
            emptyList(),
            report.blocking.map { "${it.kind}:${it.name}@${it.line}" },
            "首参是变量（名字运行期才成形）不该产生硬差异",
        )
        assertEquals(1L, report.dynamicVarNameSites, "首参是变量的入口应单列计数")
        assertEquals(0, report.dynamicNameUseSites, "没有常量名字串入口")
        assertTrue(report.result.ok)
    }
}
