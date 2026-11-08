package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.loader.BootMetadata
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * `WindowX` **链后回填**的会话装配接线（0.2.43「core/屏上同源」的收口面）。
 *
 * ## 钉的是什么
 *
 * [GameSession] 缺省装配（`configStore = null`）走 `RootedBuiltinHost.applyChainTo`——
 * 这条通道此前**只有三层链、没有宿主播种**：`WindowX` 恒为链读数（文件值/裸缺省 760）。
 * 旧盘读数压住真机版面宽的后果：core `drawableWidth` ≈ 42 格而屏上 139 列，
 * `MAXWIDTH()`（= `HTML_STRINGLEN(DRAWLINESTR)`）掉进除零区 —— 语料 `DEBUG.ERB`
 * `CFLAG_DEBUG` 行561/575 的 `MAXWIDTH()/45`、`/90` 双双除零（0.2.42 靠链前播种
 * `clientWidth` 侥幸活下来，那又是另一把混尺）；HTML_PRINT 的折行尺也与屏上分叉。
 *
 * 修复：装配在三层链**之后**调 `ConfigMethods.backfillWindowWidth(configStore,
 * windowWidthScriptPx)` —— 原版 `MainWindow.cs:726` 的「按配置开窗、把实际客户区宽
 * 回填」语义。函数层（`ConfigMethods.storeFromHost`）的同款回填由
 * `ConfigWindowBackfillTest` 钉，两边必须同源（同一个 `WindowX` 终值）。
 *
 * 四条分支：
 * 1. 接线 + 文件写 `WINDOW WIDTH` ⇒ 回填覆盖文件值（同源）；
 * 2. 接线 + 无文件键 ⇒ 回填覆盖裸缺省 760；
 * 3. 未接线（0）⇒ 保持链读数（headless / 既有装置零变化，R5.3）；
 * 4. 外部传入 `configStore` ⇒ 不回填（装配者自己管配置，行为与接线前一致）。
 */
class GameSessionWindowBackfillTest {

    @TempDir
    lateinit var dir: Path

    /** 最小产物：只有 `@SYSTEM_TITLE` + 一句 `PRINTL`（同 GameSessionDrawLineWireTest 口径）。 */
    private fun session(root: Path, configStore: ConfigStore? = null, windowWidthScriptPx: Long = 0L): GameSession {
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
            windowWidthScriptPx = windowWidthScriptPx,
        )
    }

    @Test
    fun `接线时回填覆盖文件里的窗口宽`() {
        Files.writeString(dir.resolve("emuera.config"), "WINDOW WIDTH:760\n")
        val s = session(dir, windowWidthScriptPx = 1309L)
        assertEquals(
            1309,
            s.configStore.itemOf(ConfigKey.WindowX).int(),
            "会话条目表的 WindowX 终值必须 = 屏上实际版面宽（链后回填，MainWindow.cs:726）",
        )
    }

    @Test
    fun `接线时回填覆盖裸缺省`() {
        val s = session(dir, windowWidthScriptPx = 1309L)
        assertEquals(
            1309,
            s.configStore.itemOf(ConfigKey.WindowX).int(),
            "三层链不写 WINDOW WIDTH（_default/fixed 均无此键）⇒ 链读数是裸缺省 760，回填覆盖之",
        )
    }

    @Test
    fun `未接线时保持链读数`() {
        Files.writeString(dir.resolve("emuera.config"), "WINDOW WIDTH:760\n")
        val s = session(dir, windowWidthScriptPx = 0L)
        assertEquals(
            760,
            s.configStore.itemOf(ConfigKey.WindowX).int(),
            "未接线 ⇒ 不回填（headless / 既有装置读数逐值不变，R5.3）",
        )
    }

    @Test
    fun `外部传入的条目表不做回填`() {
        val st = ConfigStore()
        st.itemOf(ConfigKey.WindowX).assign(ConfigValue.IntValue(760))
        val s = session(dir, configStore = st, windowWidthScriptPx = 1309L)
        assertEquals(
            760,
            s.configStore.itemOf(ConfigKey.WindowX).int(),
            "显式装配者自己管配置：GameSession 不回填（行为与接线前一致）",
        )
    }
}
