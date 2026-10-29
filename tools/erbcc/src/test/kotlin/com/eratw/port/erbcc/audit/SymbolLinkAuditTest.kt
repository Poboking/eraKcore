package com.eratw.port.erbcc.audit

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 装载期符号链接审计：解析期使用点 × 运行期真实命名空间。
 *
 * 三条用例分别钉：
 * 1. 干净语料 → 零硬差异（不产生假红）；
 * 2. 「预扫描认、运行期不认」的幽灵私有 `#DIM` → 一次编译就出现在报告里
 *    （这正是 `C_RANK10` / `氷精` 那类红 A 的产生机制）；
 * 3. 用户 `#DIM` 变量用字符串下标 → 运行期 `keywordToInteger` 的 ERD 未实现拒绝。
 */
class SymbolLinkAuditTest {

    @TempDir
    lateinit var dir: Path

    private fun writeGame(files: Map<String, String>): Path {
        val game = dir.resolve("game")
        val erb = game.resolve("ERB")
        Files.createDirectories(erb)
        // 空 CSV 目录：GameDataLoader 走缺省注册表（系统变量够审计用，关键字表为空）。
        Files.createDirectories(game.resolve("CSV"))
        for ((name, text) in files) {
            val p = erb.resolve(name)
            Files.createDirectories(p.parent)
            Files.writeString(p, text)
        }
        return game
    }

    @Test
    fun `干净语料全部绑定`() {
        val game = writeGame(
            mapOf(
                "DIM.ERH" to "#DIM CONST C_OK = 1\n",
                "01.ERB" to """
                    @GOOD_SUB()
                    #FUNCTION
                    #DIM CONST C_LOCAL = C_OK
                    #DIM LOCAL_OK, 3
                    LOCAL_OK:0 = C_LOCAL
                    RETURNF LOCAL_OK:0

                    @GOOD_MAIN
                    #DIM BACK, 3
                    BACK:0 = GOOD_SUB()
                    PRINTFORM {BACK:0}
                """.trimIndent(),
            ),
        )
        val report = SymbolLinkAudit.audit(game.resolve("ERB"), game)
        assertTrue(report.result.ok, "合成语料应编译成功: ${report.result.failures}")
        assertEquals(
            emptyList(),
            report.blocking.map { "${it.kind}:${it.name}@${it.file}:${it.line}" },
            "干净语料不应有硬差异",
        )
        assertTrue(report.varUseSites >= 3, "应采到多处 VarRef，实际 ${report.varUseSites}")
    }

    @Test
    fun `幽灵私有 DIM 一次报出`() {
        val game = writeGame(
            mapOf(
                "01.ERB" to """
                    @GHOST_USER()
                    #DIM BAD, 10 = NONEXISTENT
                    #DIM OK, 10
                    OK:0 = 1
                    BAD:0 = OK:0
                    PRINTFORM {BAD:0}
                """.trimIndent(),
            ),
        )
        val report = SymbolLinkAudit.audit(game.resolve("ERB"), game)
        assertTrue(report.result.ok, "幽灵声明不阻断编译: ${report.result.failures}")
        val unbound = report.findings.filter { it.kind == FindingKind.VAR_UNBOUND }
        assertEquals(setOf("BAD"), unbound.map { it.name.uppercase() }.toSet())
        assertTrue(unbound.all { it.functionKey == "GHOST_USER" })
        // 编译期 level 2 告警同时留痕（非红）
        assertTrue(report.findings.any { it.kind == FindingKind.COMPILE_DIM_WARNING && it.name.uppercase() == "BAD" })
        assertTrue(report.blocking.isNotEmpty())
    }

    @Test
    fun `用户变量字符串下标缺 ERD 表时报红`() {
        val game = writeGame(
            mapOf(
                "01.ERB" to """
                    @KW_USER()
                    #DIMS MYSTR, 10
                    MYSTR:abc = "x"
                    PRINTFORM %MYSTR:abc%
                """.trimIndent(),
            ),
        )
        val report = SymbolLinkAudit.audit(game.resolve("ERB"), game)
        assertTrue(report.result.ok, "用户变量关键字下标应能编译: ${report.result.failures}")
        val kw = report.findings.filter { it.kind == FindingKind.KEYWORD_USER_VAR }
        // 两条语句各用一次（赋值 + PRINTFORM），按「文件+函数+行」各记一处。
        assertEquals(2, kw.size, "应命中两条语句，实际 ${report.findings}")
        assertTrue(kw.all { it.name.startsWith("MYSTR:abc") })
        assertTrue(kw.all { it.functionKey == "KW_USER" })
    }

    @Test
    fun `用户变量字符串下标在 CSV 表里时全部绑定`() {
        val game = writeGame(
            mapOf(
                "01.ERB" to """
                    @KW_USER()
                    #DIMS MYSTR, 10
                    MYSTR:abc = "x"
                    PRINTFORM %MYSTR:abc%
                """.trimIndent(),
            ),
        )
        // `UserKeywordTables.install` 的 CSV 顶层表：变量名 = 文件基名（大写）。
        Files.writeString(game.resolve("CSV/MYSTR.csv"), "1,abc\n")
        val report = SymbolLinkAudit.audit(game.resolve("ERB"), game)
        assertTrue(report.result.ok, "编译应成功: ${report.result.failures}")
        assertEquals(
            emptyList(),
            report.blocking.map { "${it.kind}:${it.name}" },
            "有 ERD/CSV 表时不应判红",
        )
    }

    @Test
    fun `DEFINE 宏展开后按真实变量绑定`() {
        val game = writeGame(
            mapOf(
                "DIM.ERH" to "; 宏值不是变量名（未展开时会被当成未定义变量）\n#DEFINE MY_TIME TIME:2\n",
                "01.ERB" to """
                    @MACRO_USER
                    #DIM XX
                    XX = MY_TIME
                    PRINTFORM {XX}
                """.trimIndent(),
            ),
        )
        val report = SymbolLinkAudit.audit(game.resolve("ERB"), game)
        assertTrue(report.result.ok, "编译应成功: ${report.result.failures}")
        assertEquals(
            emptyList(),
            report.findings.filter { it.kind == FindingKind.VAR_UNBOUND }.map { it.name },
            "宏在词法层展开后不应有未绑定变量（未展开时 MY_TIME 会被当成变量 ⇒ VAR_UNBOUND）",
        )
    }

    @Test
    fun `strict 模式把硬差异变成退出码 1`() {
        val game = writeGame(
            mapOf(
                "01.ERB" to """
                    @GHOST_USER()
                    #DIM BAD, 10 = NONEXISTENT
                    BAD:0 = 1
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
        assertEquals(1, code, "幽灵私有 #DIM 在 --strict 下应判红")
        val tsv = Files.readString(out)
        assertTrue(tsv.startsWith("kind\tfile\tfunction\tline\tname\tdetail\tcount\n"))
        assertTrue(tsv.contains("VAR_UNBOUND"))
        assertTrue(tsv.contains("\tBAD\t"))
    }

    @Test
    fun `白名单放行后 strict 通过`() {
        val game = writeGame(
            mapOf(
                "01.ERB" to """
                    @GHOST_USER()
                    #DIM BAD, 10 = NONEXISTENT
                    BAD:0 = 1
                """.trimIndent(),
            ),
        )
        val allow = dir.resolve("allow.tsv")
        Files.writeString(allow, "# 已知项\nVAR_UNBOUND\tBAD\n")
        val code = com.eratw.port.erbcc.cmdAudit(
            listOf(
                "--corpus", game.resolve("ERB").toString(),
                "--game-root", game.toString(),
                "--out", dir.resolve("audit.tsv").toString(),
                "--strict",
                "--allow", allow.toString(),
            ),
        )
        assertEquals(0, code, "白名单命中后不应判红")
    }
}
