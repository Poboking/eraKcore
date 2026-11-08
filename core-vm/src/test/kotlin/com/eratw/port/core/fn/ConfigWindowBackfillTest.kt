package com.eratw.port.core.fn

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.proc.ConsoleBuiltinHost
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `WindowX` **链后回填**（`ConfigMethods.backfillWindowWidth`）的定向用例。
 *
 * ## 为什么必须链后
 *
 * 原版 `Config.WindowX` 的终值是「实际客户区宽」：`MainWindow` 按 `WINDOW WIDTH` 配置
 * 开窗后把 `clientRectangle.Width` 回填进这个静态字段（spec: `MainWindow.cs:726`）——
 * 配置值只是开窗**输入**。移植侧对应读数是 [BuiltinHost.windowWidthScriptPx]（屏上实际
 * 版面宽的同尺逆折算）。`storeFromHost` 的既有顺序是「链前播种 → 重放三层链」，文件值
 * 按原版方向覆盖播种值 ⇒ 播种永不生效，0.2.43（`0664085e`）声称的「core/屏上同源」
 * 在真机没有落地：旧盘读数压住真机版面宽，`MAXWIDTH()` 掉进除零区
 * （`DEBUG.ERB` `CFLAG_DEBUG` 行582「除数为0的除法运算」）。
 *
 * 本类钉住回填的两条分支与两个边界：
 * 1. 接线（`windowWidthScriptPx > 0`）⇒ 三层链读数（含文件值/裸缺省 760）被回填覆盖；
 * 2. 未接线（≤ 0）⇒ 读数逐值不变（headless / 既有装置零变化，R5.3）；
 * 3. `Fixed` 钉住的条目丢弃回填写入（`ConfigItem.cs:20-27`，与播种同一条 assign 通道）；
 * 4. 同源验收算式：回填后核心 `drawableWidth` 折回设备像素与屏上可绘制宽差不到一格。
 *
 * 链前 [ConfigSeedWindowWidthTest]（反射直调 `seedHostState`）与本类互补：
 * 那边钉播种形态，这边钉 `storeFromHost` 公开口的**终值**。
 */
class ConfigWindowBackfillTest {

    private fun <T> withTempRoot(prefix: String, body: (Path) -> T): T {
        val root = Files.createTempDirectory(prefix)
        try {
            return body(root)
        } finally {
            runCatching {
                Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun hostOf(root: Path, windowWidthScriptPx: Long): ConsoleBuiltinHost = ConsoleBuiltinHost(
        root,
        clientWidth = 2400L,
        clientHeight = 900L,
        fontSize = 18L,
        windowWidthScriptPx = windowWidthScriptPx,
    )

    // ------------------------------------------------------------------
    // 1) storeFromHost 公开口：链后回填覆盖三层链读数
    // ------------------------------------------------------------------

    @Test
    fun `接线时回填覆盖 emuera config 的窗口宽`() = withTempRoot("eratw-backfill-wired") { root ->
        // 旧盘形态：文件写了 WINDOW WIDTH（或链裸缺省 760）——按旧实现它会盖住播种值。
        Files.writeString(root.resolve("emuera.config"), "WINDOW WIDTH:760\n")
        val store = ConfigMethods.storeFromHost(hostOf(root, windowWidthScriptPx = 1309L))
        assertEquals(
            1309,
            store.itemOf(ConfigKey.WindowX).int(),
            "链后回填必须覆盖文件值：WindowX 终值 = 屏上实际版面宽（MainWindow.cs:726 回填语义）",
        )
    }

    @Test
    fun `未接线时保持链读数（零变化）`() = withTempRoot("eratw-backfill-unwired") { root ->
        Files.writeString(root.resolve("emuera.config"), "WINDOW WIDTH:760\n")
        val store = ConfigMethods.storeFromHost(hostOf(root, windowWidthScriptPx = 0L))
        assertEquals(
            760,
            store.itemOf(ConfigKey.WindowX).int(),
            "未接线 ⇒ 不回填，文件值原样保留（headless / 既有装置读数逐值不变，R5.3）",
        )
    }

    // ------------------------------------------------------------------
    // 2) 数值形：Fixed 丢弃 / ≤ 0 不写 / 正常写入
    // ------------------------------------------------------------------

    @Test
    fun `被 fixed 钉住的 WindowX 丢弃回填写入`() {
        val store = ConfigStore()
        store.itemOf(ConfigKey.WindowX).fixed = true
        store.itemOf(ConfigKey.WindowX).assign(ConfigValue.IntValue(760))
        ConfigMethods.backfillWindowWidth(store, 1309L)
        assertEquals(760, store.itemOf(ConfigKey.WindowX).int(), "钉住的条目必须丢弃回填（ConfigItem.cs:20-27）")
    }

    @Test
    fun `小于等于零的读数不写`() {
        val store = ConfigStore()
        store.itemOf(ConfigKey.WindowX).assign(ConfigValue.IntValue(760))
        ConfigMethods.backfillWindowWidth(store, 0L)
        assertEquals(760, store.itemOf(ConfigKey.WindowX).int(), "0 = 未接线 ⇒ 不写（R5.3）")
    }

    @Test
    fun `正常接线写入脚本 px 版面宽`() {
        val store = ConfigStore()
        store.itemOf(ConfigKey.WindowX).assign(ConfigValue.IntValue(760))
        ConfigMethods.backfillWindowWidth(store, 1309L)
        assertEquals(1309, store.itemOf(ConfigKey.WindowX).int())
    }

    // ------------------------------------------------------------------
    // 3) 同源验收算式：回填后核心折行宽与屏上可绘制宽差不到一格
    // ------------------------------------------------------------------

    @Test
    fun `回填后核心可绘制宽与屏上可绘制宽差不到一格`() = withTempRoot("eratw-backfill-same-ruler") { root ->
        // 与 View 端 resolveFor 的满宽档同一组读数（2400px / 400dpi / 配置字号 18 ⇒ 生效字号 33）。
        val contentWidthPx = 2400
        val scriptFontPx = 18
        val viewFontPx = 33
        val k = viewFontPx.toFloat() / scriptFontPx
        val backfillWindowX = (contentWidthPx.toFloat() / k).toInt()
        // 旧盘毒值还在：修复前它压住版面宽，core 与屏上分叉成两把尺。
        Files.writeString(root.resolve("emuera.config"), "WINDOW WIDTH:760\n")
        val store = ConfigMethods.storeFromHost(hostOf(root, windowWidthScriptPx = backfillWindowX.toLong()))
        assertEquals(backfillWindowX, store.itemOf(ConfigKey.WindowX).int(), "回填值 = View 反折的脚本 px")

        // 核心的可绘制宽（Config.cs:461-466）折算回设备像素。
        val coreDrawablePx = k * (backfillWindowX - maxOf(2, scriptFontPx / 6))
        // 屏上可绘制宽（View 侧：版面宽 - 形状预留，ShapePositionShift = max(2, 字号/6)）。
        val screenDrawablePx = contentWidthPx - maxOf(2, viewFontPx / 6)
        val tolerancePx = k * 0.52f * scriptFontPx // 一格（半角格宽 = 0.52 × 脚本字号）
        val diff = kotlin.math.abs(coreDrawablePx - screenDrawablePx)
        assertEquals(
            true,
            diff < tolerancePx,
            "核心折行宽与屏上可绘制宽差超过一格：core=${coreDrawablePx}px screen=${screenDrawablePx}px" +
                "（差 ${diff}px，容差 ${tolerancePx}px）—— 两把尺分叉，MAXWIDTH() 系算式与 HTML_PRINT 折行都会错位",
        )
    }
}
