package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.SettingsModel
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.sem.SemConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * 设置页写回 → 会话配置链的接线单测（settings 落盘 → 下一局 [GameSession] 装配吃到）。
 *
 * 口径：原版 `ConfigData.LoadConfig` 只在进程启动跑一次（spec: `Program.cs:95`），设置对话框
 * 只落盘不刷新运行期（spec: `ConfigDialog.cs:928-930`、`:1116`），所以「下一局」就是移植侧
 * 唯一的生效时机。这里钉的是两条易断的线：
 *   * `PRINTCを並べる数` / `PRINTCの文字数`（`ConfigData.cs:63-64` 注册、`Config.cs:326-327`
 *     读静态字段）此前在 [GameSession] 是硬编码缺省，设置页改了也不进会话 —— 本类按
 *     emuera.config / 注册缺省 / 显式参数三条路逐一钉住读数；
 *   * 设置页写回用的 [SettingsModel.buildMerge] 产物（与 [SettingsStore.save] 同一条 core 侧
 *     合并链，BOM 与原子写在 platform 层）必须能被会话链读回。
 *
 * 小语料沿用 `GameSessionTest` 的真解析器 + 真编译器口径（只要一个 `PRINTL` 的最小 SYSTEM_TITLE）。
 */
class GameSessionSettingsWireTest {

    @TempDir
    lateinit var dir: Path

    // ------------------------------------------------------------------
    // 最小产物
    // ------------------------------------------------------------------

    private fun session(root: Path, printCWidth: Int = 0, semConfig: SemConfig? = null): GameSession {
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
                FuncMeta(index = fb.index, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE",
                    defIndex = 0, position = "TEST.ERB:1"),
            ),
        )
        val s = GameSession(
            gameRoot = root,
            program = program,
            metadata = metadata,
            consoleWidth = 199,
            randSeed = 1L,
            printCWidth = printCWidth,
            semConfig = semConfig,
        )
        return s
    }

    private fun writeEmueraConfig(root: Path, text: String) {
        Files.writeString(root.resolve("emuera.config"), text)
    }

    // ------------------------------------------------------------------
    // PRINTC 两项的三条路
    // ------------------------------------------------------------------

    @Test
    fun `emuera config 的 PRINTC 两项进控制台与语句层`() {
        writeEmueraConfig(dir, "PRINTCを並べる数:7\nPRINTCの文字数:33\n")
        val s = session(dir)
        try {
            // `Config.PrintCLength` / `Config.PrintCPerLine`（Config.cs:326-327）的会话等价读数
            assertEquals(33, s.console.printCWidth, "PRINTCの文字数 进控制台补齐宽度")
            assertEquals(7, s.runtime.execContext.config.printCPerLine, "PRINTCを並べる数 进语句层")
            // 第三条通道：**脚本面**。`PRINTCLENGTH()` / `PRINTCPERLINE()` 的原版语义就是这两个
            // 条目（FunctionMethodCreator.cs:3922-3950），此前恒读接口缺省 25/3 ⇒ 玩家改了条目
            // 之后，语料那层按 `PRINTCLENGTH() * GETCONFIG("フォントサイズ") / 2` 算的按钮格宽
            // 就和引擎的补齐宽度分叉（与 2026-10-29 指令列表错位同族）。
            assertEquals(33L, s.builtinHost.printCLength, "PRINTCLENGTH() 读条目值")
            assertEquals(7L, s.builtinHost.printCPerLine, "PRINTCPERLINE() 读条目值")
        } finally {
            s.close()
        }
    }

    @Test
    fun `无配置文件时用注册缺省 25 与 3`() {
        val s = session(dir)
        try {
            // ConfigData.cs:63-64 的出厂值（Config.cs:326-327 原样读入，无夹取）
            assertEquals(25, s.console.printCWidth)
            assertEquals(3, s.runtime.execContext.config.printCPerLine)
            // 脚本面与引擎面必须是同一个数：三条通道缺省一致才谈得上「一把尺」。
            assertEquals(25L, s.builtinHost.printCLength)
            assertEquals(3L, s.builtinHost.printCPerLine)
            assertEquals(s.console.printCWidth.toLong(), s.builtinHost.printCLength)
        } finally {
            s.close()
        }
    }

    @Test
    fun `宿主显式传入的 printCWidth 与 semConfig 仍优先`() {
        writeEmueraConfig(dir, "PRINTCを並べる数:7\nPRINTCの文字数:33\n")
        val s = session(dir, printCWidth = 40, semConfig = SemConfig(printCPerLine = 9))
        try {
            assertEquals(40, s.console.printCWidth, "显式参数优先于配置条目")
            assertEquals(9, s.runtime.execContext.config.printCPerLine, "显式 semConfig 优先于配置条目")
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // 设置页写回 → 会话读取（core 侧同一条合并链）
    // ------------------------------------------------------------------

    @Test
    fun `设置页模型改动经 buildMerge 落盘后被下一局读到`() {
        val model = SettingsModel()
        // 条目 id = ConfigKey.name（SettingsEntry.id ← spec.name），设置页控件都用它寻址。
        val applied =
            model.set("PrintCPerLine", "7") as com.eratw.port.core.config.SettingsSetResult.Applied
        assertEquals("PrintCPerLine", applied.id)
        assertEquals(ConfigValue.IntValue(7), applied.value)
        model.set("PrintCLength", "33")
        // SettingsStore.save（platform）落盘用的就是这份合并文本；这里直接落同一份。
        writeEmueraConfig(dir, model.buildMerge(""))
        val s = session(dir)
        try {
            assertEquals(7, s.runtime.execContext.config.printCPerLine)
            assertEquals(33, s.console.printCWidth)
        } finally {
            s.close()
        }
    }

    @Test
    fun `设置页改动只进下一局 已装配的会话不被追改`() {
        val s = session(dir)
        try {
            assertEquals(3, s.runtime.execContext.config.printCPerLine)
            // 保存发生在会话装配之后：原版 LoadConfig 只在进程启动跑一次（Program.cs:95），
            // 运行中的条目表不重读（ConfigDialog.cs:1116 只落盘）。
            writeEmueraConfig(dir, "PRINTCを並べる数:7\n")
            assertEquals(3, s.runtime.execContext.config.printCPerLine, "运行中会话不追改")
            assertEquals(3, s.configStore.itemOf(ConfigKey.PrintCPerLine).int(), "会话条目表也不追改")
        } finally {
            s.close()
        }
        // 下一局（重新装配）吃到新值
        val next = session(dir)
        try {
            assertEquals(7, next.runtime.execContext.config.printCPerLine, "下一局读到落盘值")
        } finally {
            next.close()
        }
    }
}
