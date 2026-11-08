package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CONFIG-WIRE U5：控制台三色（背景/焦点/履历）的**会话装配链**。
 *
 * 接线前的形状：[GameSession] 构造 [com.eratw.port.core.host.LineBufferConsoleSink]
 * 时只喂 ForeColor，BackColor/FocusColor/LogColor 恒吃工厂常量 ⇒ 玩家改「背景色」
 * 「履歴文字色」等条目不生效。
 *
 * 原版链：`ConfigData.cs:68-71` 注册四色 → `emuera.config` 条目经
 * `ConfigItem.tryStringsToColor`（ConfigItem.cs:252-274）解析 → `EmueraConsole` 构造期
 * 取进 StringStyle/bgColor（EmueraConsole.cs:200/:206-208）→ 履历绘制读 LogColor
 * （ConsoleStyledString.cs:115/:153）。
 *
 * 基准零漂移：语料 `emuera.config:24-26` 的 TEXT/BACKGROUND/HIGHLIGHT 与出厂同值；
 * `:27` HISTORY LOG COLOR = 128,128,128 ≠ 出厂 192,192,192 —— 本类用自定义值证明
 * 三条通道都吃生效条目（golden 侧颜色条目不在 root 取数面，漂移面为零，见交付报告）。
 */
class GameSessionConfigWireColorTest {

    /** 同 GameSessionConfigWireInitTest 的真解析器 + 真编译器口径。 */
    private fun session(root: Path): GameSession {
        val compiler = ErbProgramCompiler()
        val stmts = listOf("@SYSTEM_TITLE", "PRINTL PLAIN").mapIndexed { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            LineParser(SymbolTable()).parseLogicalLine(
                LogicalLine(kind, "TEST.ERB", i + 1, text),
            )
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

    private fun rootWith(emuera: String): Path {
        val root = Files.createTempDirectory("eratw-config-wire-color")
        Files.writeString(root.resolve("emuera.config"), emuera)
        return root
    }

    private fun withSession(root: Path, body: (GameSession) -> Unit) {
        val s = session(root)
        try {
            body(s)
        } finally {
            s.close()
        }
    }

    @Test
    fun `emuera config 的四色条目经会话装配进控制台`() {
        val root = rootWith(
            listOf(
                "TEXT COLOR:10,20,30",
                "BACKGROUND COLOR:40,50,60",
                "HIGHLIGHT COLOR:70,80,90",
                "HISTORY LOG COLOR:11,22,33",
            ).joinToString("\n", postfix = "\n"),
        )
        withSession(root) { s ->
            val console = s.console
            assertEquals(0x0A141E, console.defaultForeColor, "既有通道复核：ConfigData.cs:68 同链")
            assertEquals(0x28323C, console.defaultBackColor, "ConfigData.cs:69 → EmueraConsole.cs:200")
            assertEquals(0x46505A, console.focusColor, "ConfigData.cs:70（焦点绘制留接口）")
            assertEquals(0x0B1621, console.logColor, "ConfigData.cs:71 → ConsoleStyledString.cs:115/:153")
        }
    }

    @Test
    fun `无配置文件时四色保持出厂常量 基准零漂移`() {
        val root = Files.createTempDirectory("eratw-config-wire-color-default")
        withSession(root) { s ->
            val console = s.console
            assertEquals(
                com.eratw.port.core.host.LineBufferConsoleSink.DEFAULT_FORE_COLOR,
                console.defaultForeColor,
            )
            assertEquals(
                com.eratw.port.core.host.LineBufferConsoleSink.DEFAULT_BACK_COLOR,
                console.defaultBackColor,
            )
            assertEquals(
                com.eratw.port.core.host.LineBufferConsoleSink.DEFAULT_FOCUS_COLOR,
                console.focusColor,
            )
            assertEquals(
                com.eratw.port.core.host.LineBufferConsoleSink.DEFAULT_LOG_COLOR,
                console.logColor,
            )
        }
    }
}
