package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.loader.BootMetadata
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * `DRAWLINE文字` 条目 → 分隔线 stBar 的**会话接线**（NEG16）。
 *
 * 键 → [LineBufferConsoleSink.barUnit] → 装配期一次成型的 [LineBufferConsoleSink.stBar] →
 * `com.eratw.port.core.vars.VariableStore` 的 `drawLineStr`（DRAWLINESTR 伪变量背衬）。
 *
 * 这条线此前是断的：store 的 `drawLineStr` 恒为缺省空串（无人灌），DRAWLINE 语句侧又把
 * 分隔线字符硬编码成 `-` ⇒ 语料 COMMON.ERB 等 5 文件的 `%DRAWLINESTR%` 打出空串、
 * 用户改 `DRAWLINE文字` 两处都不生效。原版是 Process init 里 `setStBar(Config.DrawLineString)`
 * 一次成型，PrintBar 与 DRAWLINESTR 读**同一个** stBar 字段（Process.cs:258-259、
 * EmueraConsole.cs:3388-3393/:3437-3441、VariableData.cs:976-986）。
 *
 * 取值语义的分档（填充/逐字符裁剪算法本身）由 `barString` 与 GETLINESTR 侧既有用例钉，
 * 本类只钉「同一个生效值确实进了控制台与变量池两处」。
 */
class GameSessionDrawLineWireTest {

    @TempDir
    lateinit var dir: Path

    /** 最小产物：只有 `@SYSTEM_TITLE` + 一句 `PRINTL`（同 GameSessionCompatiRandWireTest 口径）。 */
    private fun session(root: Path, configStore: ConfigStore? = null): GameSession {
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
            configStore = configStore,
        )
    }

    @Test
    fun `缺省字符填满整行且语句面与伪变量同串`() {
        val s = session(dir)
        assertEquals("-".repeat(199), s.console.stBar, "出厂 \"-\"（ConfigData.cs:163），199 格 = 宽度整行")
        assertEquals(s.console.stBar, s.store.drawLineStr, "DRAWLINESTR 背衬 = 同一份 stBar（Process.cs:259）")
    }

    @Test
    fun `emuera config 不喂 DRAWLINE 文字（REPLACE 组只吃 _Replace csv）`() {
        // DrawLineString 在 C# 的 replaceArray 里（ConfigData.cs:163），装载面只有 _Replace.csv
        // ⇒ emuera.config 写什么都不进（Kotlin 同构：resolveGame 只认 GAME 组、
        // resolveReplace 只认 REPLACE 组且不认英文标签，ConfigStore.kt:40/43）。钉住边界防误接。
        Files.writeString(dir.resolve("emuera.config"), "DRAWLINE character:＝\nDRAWLINE文字:＝\n")
        val s = session(dir)
        assertEquals("-".repeat(199), s.store.drawLineStr)
    }

    @Test
    fun `_Replace csv 的日文键名同样进`() {
        Files.createDirectories(dir.resolve("CSV"))
        Files.writeString(dir.resolve("CSV/_Replace.csv"), "DRAWLINE文字,＝\n")
        val s = session(dir)
        // ＝ 占 2 格：100 份 = 200 格超宽，逐字符裁到 198 ≤ 199（EmueraConsole.cs:3426-3435）
        assertEquals("＝".repeat(99), s.store.drawLineStr)
        assertEquals(s.store.drawLineStr, s.console.stBar)
    }

    @Test
    fun `显式条目表里的空串回落 "-"（SetReplace 唯一改写）`() {
        val st = ConfigStore()
        st.itemOf(ConfigKey.DrawLineString).assign(ConfigValue.StringValue(""))
        val s = session(dir, configStore = st)
        assertEquals("-".repeat(199), s.store.drawLineStr, "Config.cs:725-729")
    }

    @Test
    fun `DRAWLINE 语句面打印装配期成型的那一份`() {
        val sink = LineBufferConsoleSink(clientWidth = 8, barUnit = "ab")
        sink.printBar()
        sink.newLine()
        val text = sink.lines().last().segments.joinToString("") { it.text }
        assertEquals("abababab", text)
        assertEquals(sink.stBar, text)
    }
}
