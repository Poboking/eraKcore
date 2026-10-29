package com.eratw.port.erbcc.audit

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * CMP-78：`VarRef.subKey`（源码 `变量@函数`）参与绑定判定。
 *
 * 原版三条分支（`IdentifierDictionary.cs:429-495` GetVariableToken）：
 * - 当前函数私有变量命中且带 `@` ⇒ `:440-443` UsedAtForPrivVar（CodeEE）；
 * - 全局变量命中且带 `@` ⇒ `:484-487` UsedAtForGlobalVar（CodeEE）；
 * - LOCAL/ARG/LOCALS/ARGS 命中且带 `@` ⇒ `:448-473` 按 subKey 建独立 local token（合法，
 *   长度取**当前函数**的声明）。
 *
 * 移植侧原先 `SymbolLinkAudit.checkVar` 只把 subKey 写进 detail、`VariableStore.resolveVarInfo`
 * 也没有 subKey 形参 ⇒ 前两条「运行期必红」被静默放行。本文件：
 * 1. 私有/全局变量带 `@` ⇒ 必须进硬差异（修前必红）；
 * 2. `LOCAL@别函数` ⇒ 合法，不得判未绑定（反向控制，防审计加严后误报）。
 */
class SymbolLinkAuditSubKeyTest {

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

    @Test
    fun `私有变量带 at 必须判硬差异`() {
        val game = writeGame(
            mapOf(
                "01.ERB" to """
                    @PRIV_AT
                    #DIM PV, 3
                    PV:0 = 1
                    PRINTFORM {PV@PRIV_AT:0}
                """.trimIndent(),
            ),
        )
        val report = SymbolLinkAudit.audit(game.resolve("ERB"), game)
        assertTrue(report.result.ok, "合成语料应编译成功: ${report.result.failures}")
        val unbound = report.findings.filter { it.kind == FindingKind.VAR_UNBOUND }
        assertEquals(
            listOf("PV"),
            unbound.map { it.name },
            "私有变量带 @ 是原版解析期 CodeEE（IdentifierDictionary.cs:440-443）；实际 ${report.findings}",
        )
        val f = unbound.single()
        assertEquals("01.ERB", f.file)
        assertEquals("PRIV_AT", f.functionKey)
        assertEquals(3, f.line, "行号取调用点（0 基）")
        assertTrue(f.detail.contains("不得带 @"), "detail 应点名非法 @ 用法；实际 ${f.detail}")
    }

    @Test
    fun `全局变量带 at 必须判硬差异`() {
        val game = writeGame(
            mapOf(
                "DIM.ERH" to "#DIM GV, 3\n",
                "01.ERB" to """
                    @GLOBAL_AT
                    GV:0 = 1
                    PRINTFORM {GV@GLOBAL_AT:0}
                """.trimIndent(),
            ),
        )
        val report = SymbolLinkAudit.audit(game.resolve("ERB"), game)
        assertTrue(report.result.ok, "合成语料应编译成功: ${report.result.failures}")
        val unbound = report.findings.filter { it.kind == FindingKind.VAR_UNBOUND }
        assertEquals(
            listOf("GV"),
            unbound.map { it.name },
            "全局变量带 @ 是原版解析期 CodeEE（IdentifierDictionary.cs:484-487）；实际 ${report.findings}",
        )
        assertTrue(unbound.single().detail.contains("不得带 @"))
    }

    @Test
    fun `LOCAL 带 at 指别函数是合法用法不判未绑定`() {
        val game = writeGame(
            mapOf(
                "01.ERB" to """
                    @LOCAL_OWNER
                    LOCAL:0 = 1
                    @LOCAL_OTHER
                    LOCAL:0 = 2
                    PRINTFORM {LOCAL@LOCAL_OWNER:0}
                """.trimIndent(),
            ),
        )
        val report = SymbolLinkAudit.audit(game.resolve("ERB"), game)
        assertTrue(report.result.ok, "合成语料应编译成功: ${report.result.failures}")
        assertEquals(
            emptyList(),
            report.blocking.map { "${it.kind}:${it.name}@${it.line}" },
            "LOCAL@函数 是合法用法（IdentifierDictionary.cs:448-473），不得判未绑定",
        )
    }
}
