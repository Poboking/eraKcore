package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.loader.BootMetadata
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `Config.CompatiRAND` 的**会话接线**（SAVE-09b）：键 → [com.eratw.port.core.vars.VarConfig]。
 *
 * 这条线此前是断的：`VarConfig` 在 [GameSession] 装配时只喂 `systemSaveInBinary`，
 * `compatiRand` 恒取 data class 缺省 `false`，于是把 `emuera.config` 的
 * `IMITATE BEHAVIOR FOR RAND` 改成 YES 也不分叉（SAVE-09 的「运行期 0 消费」）。
 * 取值面的语义分叉由 `CompatiRandKeyTest` 钉，本类只钉「同一个键的两种取值确实进了变量池」。
 *
 * 语料现值 `emuera.config:52` = NO ⇒ 与出厂缺省（`ConfigData.cs:98`）一致，基准读数不变。
 */
class GameSessionCompatiRandWireTest {

    @TempDir
    lateinit var dir: Path

    /** 最小产物：只有 `@SYSTEM_TITLE` + 一句 `PRINTL`（同 GameSessionSettingsWireTest 口径）。 */
    private fun session(root: Path): GameSession {
        val compiler = ErbProgramCompiler()
        val stmts = listOf("@SYSTEM_TITLE", "PRINTL PLAIN").mapIndexed { i, text ->
            val kind = if (text.startsWith("@")) {
                com.eratw.port.core.lexer.LineKind.FUNCTION_LABEL
            } else {
                com.eratw.port.core.lexer.LineKind.CODE
            }
            com.eratw.port.core.stmt.LineParser(com.eratw.port.core.stmt.SymbolTable())
                .parseLogicalLine(com.eratw.port.core.lexer.LogicalLine(kind, "TEST.ERB", i + 1, text))
        }.filterNotNull()
        val fb: FunctionBytecode = compiler.compileFunction(
            FuncSig("SYSTEM_TITLE"),
            stmts,
            setOf("SYSTEM_TITLE"),
        )
        val program: Program = compiler.build("SYSTEM_TITLE")
        val metadata = BootMetadata(
            functions = listOf(
                FuncMeta(
                    index = fb.index, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE",
                    defIndex = 0, position = "TEST.ERB:1",
                ),
            ),
        )
        return GameSession(
            gameRoot = root,
            program = program,
            metadata = metadata,
            consoleWidth = 199,
            randSeed = 1L,
        )
    }

    @Test
    fun `IMITATE BEHAVIOR FOR RAND 进变量池`() {
        Files.writeString(dir.resolve("emuera.config"), "IMITATE BEHAVIOR FOR RAND:YES\n")
        val on = session(dir)
        assertTrue(on.store.config.compatiRand, "YES ⇒ VarConfig.compatiRand（VariableData.cs:3154）")

        Files.writeString(dir.resolve("emuera.config"), "IMITATE BEHAVIOR FOR RAND:NO\n")
        val off = session(dir)
        assertFalse(off.store.config.compatiRand, "NO ⇒ 走 RandToken 通道")
    }
}
