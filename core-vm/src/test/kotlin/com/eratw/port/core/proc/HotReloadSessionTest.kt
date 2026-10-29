package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigReloadResult
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.config.SettingsModel
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 「热更键改完立刻生效」的会话级对拍（M5-CONFIG 判据：改 [EffectiveConfig] 后**同一 [GameSession]**
 * 读到新值；重启键仍走重启）。
 *
 * 装配口径沿用 [GameSessionSettingsWireTest]：真解析器 + 真编译器的最小 `SYSTEM_TITLE`，
 * 显式传一份 [ConfigStore] 当会话条目表（`GameSession.kt:261` 的 `configStore` 参数）。
 * 断言都打在**会话自己的读点**上，不是打在 `ConfigStore` 上：
 *   * `beginHost.shopHostModel`（`GameSession.kt:400` 装配的 [StoreShopHostModel]，`ShopHostModel.kt:47` 现读）；
 *   * `runtime.execContext.config`（语句层语义快照，烘死）；
 *   * `console.defaultFontName`（控制台构造参数，烘死）；
 *   * `EffectiveConfig.of(configStore).drawableWidth`（`core/fn/HtmlMethods.kt:563` 每次 HTML_PRINT 都跑这条式子）。
 */
class HotReloadSessionTest {

    @TempDir
    lateinit var dir: Path

    private fun session(root: Path, config: ConfigStore): GameSession {
        val compiler = ErbProgramCompiler()
        val stmts = listOf("@SYSTEM_TITLE", "PRINTL PLAIN").mapIndexed { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            LineParser(SymbolTable()).parseLogicalLine(LogicalLine(kind, "TEST.ERB", i + 1, text))
        }.filterNotNull()
        val fb: FunctionBytecode = compiler.compileFunction(FuncSig("SYSTEM_TITLE"), stmts, setOf("SYSTEM_TITLE"))
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
            configStore = config,
        )
    }

    @Test
    fun `热更键落地后同一会话的下一个使用点读到新值`() {
        val config = ConfigStore()
        val s = session(dir, config)
        try {
            val shop = assertNotNull(s.beginHost.shopHostModel, "GameSession 必须装商店宿主（GameSession.kt:400）")
            assertEquals(100, shop.maxShopItem(), "MaxShopItem 注册缺省 100（ConfigSpec.kt:218）")

            val result = EffectiveConfig.of(s.configStore).reloadHot(ConfigKey.MaxShopItem, ConfigValue.IntValue(7))

            assertTrue(result is ConfigReloadResult.Applied, "MaxShopItem 在热更表内：$result")
            assertEquals(7, shop.maxShopItem(), "同一会话的下一次商店输入读到新值")
        } finally {
            s.close()
        }
    }

    @Test
    fun `窗口宽热更后 HTML 可绘制宽立刻变`() {
        val s = session(dir, ConfigStore())
        try {
            val before = EffectiveConfig.of(s.configStore).drawableWidth
            assertEquals(760 - 3, before, "缺省 760 宽、字号 18 ⇒ shift 3（Config.cs:461-466）")

            EffectiveConfig.of(s.configStore).reloadHot(ConfigKey.WindowX, ConfigValue.IntValue(1200))

            // HtmlMethods.kt:563 每次 HTML_PRINT 都现算这条式子 ⇒ 下一帧就是新宽。
            assertEquals(1200 - 3, EffectiveConfig.of(s.configStore).drawableWidth)
        } finally {
            s.close()
        }
    }

    @Test
    fun `重启键被拒且已装配的会话不追改`() {
        val s = session(dir, ConfigStore())
        try {
            assertEquals(3, s.runtime.execContext.config.printCPerLine, "注册缺省 3（ConfigData.cs:63）")

            val result = EffectiveConfig.of(s.configStore).reloadHot(ConfigKey.PrintCPerLine, ConfigValue.IntValue(9))

            assertEquals(ConfigReloadResult.NeedsRestart(ConfigKey.PrintCPerLine), result)
            assertEquals(3, s.runtime.execContext.config.printCPerLine, "语句层语义快照不追改")
            assertEquals(3, s.configStore.itemOf(ConfigKey.PrintCPerLine).int(), "条目表也不写")
        } finally {
            s.close()
        }
    }

    @Test
    fun `字体名判重启的理由是控制台构造参数`() {
        val s = session(dir, ConfigStore())
        try {
            val result = EffectiveConfig.of(s.configStore).reloadHot(
                ConfigKey.FontName,
                ConfigValue.StringValue("黑体"),
            )

            assertEquals(ConfigReloadResult.NeedsRestart(ConfigKey.FontName), result)
            assertNotEquals("黑体", s.console.defaultFontName, "控制台默认字体是构造参数（LineBufferConsoleSink.kt:110）")
        } finally {
            s.close()
        }
    }

    @Test
    fun `设置页模型把热更键推进会话条目表`() {
        val s = session(dir, ConfigStore())
        try {
            val model = SettingsModel()
            model.set("SkipFrame", "9")
            model.set("MaxLog", "900")

            val applied = model.pushHotInto(s.configStore)

            assertEquals(listOf(ConfigKey.SkipFrame), applied)
            assertEquals(9, s.configStore.itemOf(ConfigKey.SkipFrame).int(), "会话条目表吃到热更值")
            assertEquals(5000, s.configStore.itemOf(ConfigKey.MaxLog).int(), "重启键不进会话条目表")
        } finally {
            s.close()
        }
    }
}
