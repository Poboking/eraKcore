package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.save.SaveFolderMigrationLog
import com.eratw.port.core.save.SaveMigrationOutcome
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * M11-A-WIRE-2：`GameSession` 的构造参数 `saveMigrationPrompt` 必须真的透传到
 * `FileSystemOps`（生产缺省 AUTO_ACCEPT ⇒ 不问就搬是本条的原始缺陷）。
 *
 * 原版语义（spec: `Config.cs:482-485` + `:551-590`）：
 * - 开关 YES 且 `sav/` 不存在才进迁移；根下没有 `global.sav` / `save*.sav` ⇒ **不问**；
 * - 有东西才 `Dialog.ShowPrompt(数据移动, 已创建sav文件夹…)`（`:564-567`）；
 * - 答 Yes 才搬，答 No 文件原地不动、目录留着 ⇒ 下次因 `sav/` 已存在不再问（一次性）。
 */
class GameSessionSaveMigrationPromptWireTest {

    @TempDir
    lateinit var dir: Path

    private fun writeErbcMinimal(): Pair<Program, BootMetadata> {
        val compiler = ErbProgramCompiler()
        val stmts = listOf("@SYSTEM_TITLE", "PRINTL PLAIN").mapIndexedNotNull { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            LineParser(SymbolTable()).parseLogicalLine(LogicalLine(kind, "TEST.ERB", i + 1, text))
        }
        val fb: FunctionBytecode = compiler.compileFunction(FuncSig("SYSTEM_TITLE"), stmts, setOf("SYSTEM_TITLE"))
        return compiler.build("SYSTEM_TITLE") to BootMetadata(
            functions = listOf(
                FuncMeta(index = fb.index, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE",
                    defIndex = 0, position = "TEST.ERB:1"),
            ),
        )
    }

    private fun session(prompt: ((String, String) -> Boolean)?): GameSession {
        val (program, metadata) = writeErbcMinimal()
        return GameSession(
            gameRoot = dir,
            program = program,
            metadata = metadata,
            consoleWidth = 80,
            randSeed = 1L,
            saveMigrationPrompt = prompt,
        )
    }

    private fun enableSaveFolder() {
        Files.writeString(dir.resolve("emuera.config"), "USE SAV FOLDER:YES\n")
    }

    private fun seedRootSaves() {
        Files.writeString(dir.resolve("global.sav"), "g")
        Files.writeString(dir.resolve("save00.sav"), "s")
    }

    @Test
    fun `宿主答是 旧档搬进 sav 且只问一次`() {
        enableSaveFolder()
        seedRootSaves()
        val asked = ArrayList<Pair<String, String>>()
        val s = session { title, message -> asked += title to message; true }
        try {
            assertEquals(1, asked.size, "有东西可搬时必须问且只问一次")
            assertEquals("数据移动", asked.single().first)
            assertTrue(Files.exists(dir.resolve("sav/global.sav")))
            assertTrue(Files.exists(dir.resolve("sav/save00.sav")))
            assertFalse(Files.exists(dir.resolve("global.sav")))
            assertEquals(SaveMigrationOutcome.Completed, SaveFolderMigrationLog.of(s.store)?.latest?.outcome)
            assertTrue(SaveFolderMigrationLog.of(s.store)?.latest?.filesMoved == true)
        } finally {
            s.close()
        }
    }

    @Test
    fun `宿主答否 文件原地不动且目录已建`() {
        enableSaveFolder()
        seedRootSaves()
        val asked = ArrayList<Pair<String, String>>()
        val s = session { title, message -> asked += title to message; false }
        try {
            assertEquals(1, asked.size)
            assertTrue(Files.exists(dir.resolve("global.sav")), "答否不搬")
            assertTrue(Files.exists(dir.resolve("save00.sav")))
            assertTrue(Files.isDirectory(dir.resolve("sav")), "原版先建目录再问（Config.cs:555）")
            assertEquals(SaveMigrationOutcome.Declined, SaveFolderMigrationLog.of(s.store)?.latest?.outcome)
        } finally {
            s.close()
        }
    }

    @Test
    fun `第二局不再问 因为 sav 目录已存在`() {
        enableSaveFolder()
        seedRootSaves()
        session { _, _ -> false }.close()

        var askedSecond = 0
        val s = session { _, _ -> askedSecond++; true }
        try {
            assertEquals(0, askedSecond, "目录已存在 ⇒ 原版整段跳过（Config.cs:482-485）")
            assertTrue(Files.exists(dir.resolve("global.sav")), "上一局答否的文件仍原地")
            assertEquals(SaveMigrationOutcome.FolderAlreadyExists, SaveFolderMigrationLog.of(s.store)?.latest?.outcome)
        } finally {
            s.close()
        }
    }

    @Test
    fun `缺省 prompt 为 null 时与旧版 AUTO_ACCEPT 逐字同解`() {
        enableSaveFolder()
        seedRootSaves()
        val s = session(null)
        try {
            assertTrue(Files.exists(dir.resolve("sav/global.sav")), "缺省仍静默搬，行为零变化")
            assertEquals(SaveMigrationOutcome.Completed, SaveFolderMigrationLog.of(s.store)?.latest?.outcome)
        } finally {
            s.close()
        }
    }

    @Test
    fun `根下没东西时一次都不问`() {
        enableSaveFolder()
        var asked = 0
        val s = session { _, _ -> asked++; true }
        try {
            assertEquals(0, asked, "原版 :564 先判空再弹框")
            assertTrue(Files.isDirectory(dir.resolve("sav")))
            assertEquals(SaveMigrationOutcome.NothingToMove, SaveFolderMigrationLog.of(s.store)?.latest?.outcome)
        } finally {
            s.close()
        }
    }
}
