package com.eratw.port.core.fn

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.proc.ConsoleBuiltinHost
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `C-WINDOW-SEED@1` 的定向用例：宿主像素播种与屏上版面**同源**（R5.1–R5.3）。
 *
 * ## 钉的三件事
 *
 * 1. `BuiltinHost.windowWidthScriptPx <= 0`（未接线）⇒ `WindowX` 播种**逐字**沿用
 *    `host.clientWidth`（headless / 既有装置零变化，R5.3）；
 * 2. `> 0` ⇒ `WindowX` 播种为该脚本 px 值（屏上版面宽按「真机字号 / 脚本字号」反折，
 *    R5.1）；`WindowY` 仍播成 `clientHeight + LineHeight`（与原版 `MainWindow.cs:726`
 *    的逆运算同义）；
 * 3. 播种只走 `ConfigItem.assign`（`ConfigItem.cs:20-27`）：被 `fixed.config` 钉住的条目
 *    丢弃写入。
 *
 * ## 为什么用反射直调 `seedHostState`
 *
 * 公开口 [ConfigMethods.storeFromHost] 的固定顺序是「先播种、再重放三层链」，播种时条目
 * 恒未钉 —— `Fixed` 丢弃写入在公开口**不可观测**。`seedHostState` 是私有实现，这里按
 * `UpperNamesBoundTest` 的既有做法反射直调，喂一份**预先钉住**的条目表，把「播种 →
 * assign → 钉位丢弃」这条链真正跑起来（不是拿 `ConfigItem.assign` 的独立用例冒充）。
 *
 * 真机读数（调窄窗口后核心折行与屏上列数逐帧一致）仍是 `TODO(verify)`：无设备。
 */
class ConfigSeedWindowWidthTest {

    /**
     * `seedHostState` 的反射直调入口。
     *
     * `ConfigMethods` 是 Kotlin `object`（JVM 侧 `INSTANCE` 单例 + 私有实例方法），
     * 与 `UpperNamesBoundTest` 直调 `VariableStore.upperOf` 的口径相同。
     */
    private object Seed {

        private val instance: Any = ConfigMethods::class.java.getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)

        private val method = ConfigMethods::class.java
            .getDeclaredMethod("seedHostState", ConfigStore::class.java, BuiltinHost::class.java)
            .apply { isAccessible = true }

        fun into(store: ConfigStore, host: BuiltinHost) {
            method.invoke(instance, store, host)
        }
    }

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

    /** 生产宿主形态（与 `GameSession` 装配同型：构造参数注入 `clientWidth` / `windowWidthScriptPx`）。 */
    private fun hostOf(
        root: Path,
        clientWidth: Long,
        clientHeight: Long = 900L,
        windowWidthScriptPx: Long,
        fontSize: Long = 18L,
    ): ConsoleBuiltinHost = ConsoleBuiltinHost(
        root,
        clientWidth = clientWidth,
        clientHeight = clientHeight,
        fontSize = fontSize,
        windowWidthScriptPx = windowWidthScriptPx,
    )

    // ------------------------------------------------------------------
    // 1) 未接线 / 已接线两条分支
    // ------------------------------------------------------------------

    @Test
    fun `未接线时 WindowX 播种逐字沿用 clientWidth`() = withTempRoot("eratw-seed-unwired") { root ->
        val host = hostOf(root, clientWidth = 2400L, windowWidthScriptPx = 0L)
        // 先压住构造属性覆写这条（BuiltinHost by delegate 的委托陷阱：转发给 RootedBuiltinHost
        // 时会恒读 0，注入值就丢了）。
        assertEquals(0L, host.windowWidthScriptPx, "未接线 ⇒ 读数就是 0")
        val store = ConfigStore()
        Seed.into(store, host)
        assertEquals(2400, store.itemOf(ConfigKey.WindowX).int(), "未接线 ⇒ WindowX 播种沿用 clientWidth（R5.3）")
    }

    @Test
    fun `接线时 WindowX 播种脚本 px 版面宽而不是 clientWidth`() = withTempRoot("eratw-seed-wired") { root ->
        // 真机算例：内容区 2400 设备 px、真机字号 33、脚本字号 18 ⇒ k = 1.8333，
        // 反折脚本宽 = round(2400 / k) = 1309（与 View.windowWidthScriptPx 同式）。
        val host = hostOf(root, clientWidth = 2400L, windowWidthScriptPx = 1271L)
        assertEquals(1271L, host.windowWidthScriptPx, "构造属性覆写必须压过接口委托的缺省 0")
        val store = ConfigStore()
        Seed.into(store, host)
        assertEquals(1271, store.itemOf(ConfigKey.WindowX).int(), "接线 ⇒ WindowX 播种脚本 px（R5.1）")
    }

    // ------------------------------------------------------------------
    // 2) Fixed 条目丢弃写入（播种只走 ConfigItem.assign）
    // ------------------------------------------------------------------

    @Test
    fun `被 fixed 钉住的 WindowX 丢弃播种写入`() = withTempRoot("eratw-seed-fixed") { root ->
        val store = ConfigStore()
        // 模拟 fixed.config 已经钉住该条目（ConfigItem.cs:20-27）：先钉、再写一个可辨识基线。
        store.itemOf(ConfigKey.WindowX).fixed = true
        store.itemOf(ConfigKey.WindowX).assign(ConfigValue.IntValue(760))
        val host = hostOf(root, clientWidth = 2400L, windowWidthScriptPx = 1271L)
        Seed.into(store, host)
        assertEquals(760, store.itemOf(ConfigKey.WindowX).int(), "钉住的条目必须丢弃播种写入（值原样保留）")
    }

    // ------------------------------------------------------------------
    // 3) WindowY 播种 = clientHeight + LineHeight（原版逆运算）
    // ------------------------------------------------------------------

    @Test
    fun `WindowY 播种仍为 clientHeight 加 LineHeight`() = withTempRoot("eratw-seed-windowy") { root ->
        val store = ConfigStore()
        val lineHeight = store.itemOf(ConfigKey.LineHeight).int()
        val host = hostOf(root, clientWidth = 2400L, clientHeight = 900L, windowWidthScriptPx = 1309L)
        Seed.into(store, host)
        assertEquals(
            900 + lineHeight,
            store.itemOf(ConfigKey.WindowY).int(),
            "WindowY = 客户区高 + 一行高（spec: MainWindow.cs:726；本期 WindowY 不接线 ⇒ clientHeight = 物理内容高）",
        )
    }

    // ------------------------------------------------------------------
    // 4) 同源验收算式：核心可绘制宽折算回设备像素后与屏上可绘制宽差不到一格
    // ------------------------------------------------------------------

    @Test
    fun `播种后核心可绘制宽与屏上可绘制宽差不到一格`() = withTempRoot("eratw-seed-same-ruler") { root ->
        // 与 View 端 resolveFor 的满宽档同一组读数（2400px / 400dpi / 配置字号 18 ⇒ 生效字号 33）。
        val contentWidthPx = 2400
        val scriptFontPx = 18
        val viewFontPx = 33
        val k = viewFontPx.toFloat() / scriptFontPx
        // View.windowWidthScriptPx = round(版面宽 / k)（C-WINDOW-SEED 的同源逆折算）。
        val seedWindowX = (contentWidthPx.toFloat() / k).roundToInt()
        val host = hostOf(
            root,
            clientWidth = contentWidthPx.toLong(),
            windowWidthScriptPx = seedWindowX.toLong(),
            fontSize = scriptFontPx.toLong(),
        )
        val store = ConfigStore()
        Seed.into(store, host)
        assertEquals(seedWindowX, store.itemOf(ConfigKey.WindowX).int(), "播种值 = View 反折的脚本 px")

        // 核心的可绘制宽（Config.cs:461-466）、脚本侧的 shift、折算回设备像素。
        val coreDrawablePx = seedWindowX - max(2, scriptFontPx / 6)
        val deviceDrawableFromCore = k * coreDrawablePx
        // 屏上可绘制宽（View 侧：版面宽 - 形状预留，ShapePositionShift = max(2, 字号/6)）。
        val viewShapeShiftPx = max(2, viewFontPx / 6)
        val screenDrawablePx = contentWidthPx - viewShapeShiftPx
        val tolerancePx = k * 0.52f * scriptFontPx // 一格（半角格宽 = 0.52 × 脚本字号）
        assertTrue(
            abs(deviceDrawableFromCore - screenDrawablePx) < tolerancePx,
            "核心折行宽与屏上可绘制宽差超过一格：core=${deviceDrawableFromCore}px " +
                "screen=${screenDrawablePx}px（容差 ${tolerancePx}px）",
        )
    }
}
