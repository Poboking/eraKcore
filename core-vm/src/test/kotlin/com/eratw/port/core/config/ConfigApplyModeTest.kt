package com.eratw.port.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 运行时重载口径的单测（M5-CONFIG 的「哪些键必须重启 / 哪些可以热更」）。
 *
 * 钉四件事：
 *   1. [ConfigApplyMode.HOT_KEYS] 里每个键都是条目表里真实存在的条目（不是幽灵键），
 *      且表外键一律 [ConfigApplyMode.RESTART]；
 *   2. 有现读点但**同时有烘死点**的那批键不许被挪进热更表（本类逐条列成回归钉子）；
 *   3. [EffectiveConfig.reloadHot] 只写热更键：落地后**同一份视图**的派生字段与条目表同步更新，
 *      重启键返回 `NeedsRestart` 且条目表一个字节都不动，钉住的条目返回 `Fixed`；
 *   4. [SettingsModel.changedKeys] / [pendingImpact] / [pushHotInto] 的分桶与「不碰写盘文本」。
 *
 * 证据行号见各断言的 message 与 [ConfigApplyMode] 的 KDoc。
 */
class ConfigApplyModeTest {

    // ------------------------------------------------------------------
    // 表本身的完整性
    // ------------------------------------------------------------------

    @Test
    fun `热更键都是条目表里真实存在的键`() {
        val store = ConfigStore()
        for (key in ConfigApplyMode.HOT_KEYS) {
            assertNotNull(store.item(key), "$key 登记为热更，但条目表里没有这个条目（幽灵键）")
        }
    }

    @Test
    fun `表外键默认重启`() {
        // 逐条都是装配期烘死点，理由见 ConfigApplyMode.HOT_KEYS 的 KDoc。
        val restart = mapOf(
            ConfigKey.PrintCPerLine to "烘进语句层 SemConfig（GameSession.kt:420）",
            ConfigKey.PrintCLength to "烘进控制台 printCWidth（GameSession.kt:276）",
            ConfigKey.MaxLog to "烘进控制台 maxLines（GameSession.kt:270）",
            ConfigKey.ButtonWrap to "烘进控制台 buttonWrap（GameSession.kt:281）",
            ConfigKey.SaveDataNos to "烘进 SaveWiring（GameSession.kt:376）",
            ConfigKey.SystemSaveInBinary to "烘进 VarConfig 与 SaveConfig（GameSession.kt:369）",
            ConfigKey.ZipSaveData to "烘进 SaveConfig（GameSession.kt:370）",
            ConfigKey.UseSaveFolder to "烘进 SaveConfig（GameSession.kt:371）",
            ConfigKey.ComAbleDefault to "烘进 BEGIN 宿主（GameSession.kt:395）",
            ConfigKey.AutoSave to "烘进 BEGIN 宿主（GameSession.kt:396）",
            ConfigKey.UseERD to "关键字表装配期消费（GameSession.kt:354）",
            ConfigKey.TimesNotRigorousCalculation to "烘进语句层语义快照（GameSession.kt:422）",
            // 有现读点、但同样有烘死点 ⇒ 按「不假称热更」判重启。
            ConfigKey.FontName to "控制台 defaultFontName 是构造参数（GameSession.kt:281）",
            ConfigKey.SystemAllowFullSpace to "ProcessRuntime.runtimeLexerConfig 是 by lazy 快照（:375）",
            ConfigKey.SystemIgnoreTripleSymbol to "ProcessRuntime.runtimeLexerConfig 是 by lazy 快照（:375）",
            ConfigKey.MoneyLabel to "%MONEYLABEL% 读变量池快照（GameSession.kt:300）",
            ConfigKey.MoneyFirst to "%MONEYLABEL% 读变量池快照（GameSession.kt:300）",
        )
        for ((key, why) in restart) {
            assertEquals(ConfigApplyMode.RESTART, ConfigApplyMode.of(key), "$key 必须重启：$why")
        }
    }

    @Test
    fun `热更表覆盖字号列宽与跳过类`() {
        // 组长判据：字号 / 列宽 / 跳过类至少要能热更。
        for (key in listOf(ConfigKey.FontSize, ConfigKey.LineHeight, ConfigKey.WindowX, ConfigKey.WindowY)) {
            assertEquals(ConfigApplyMode.HOT, ConfigApplyMode.of(key), "$key 应可热更（HTML 折行宽度现读）")
        }
        assertEquals(ConfigApplyMode.HOT, ConfigApplyMode.of(ConfigKey.SkipFrame), "跳过类应可热更")
    }

    @Test
    fun `split 分桶保持注册顺序`() {
        val impact = ConfigApplyMode.split(listOf(ConfigKey.MaxLog, ConfigKey.SkipFrame, ConfigKey.FontSize))
        assertEquals(listOf(ConfigKey.SkipFrame, ConfigKey.FontSize), impact.hot)
        assertEquals(listOf(ConfigKey.MaxLog), impact.restart)
        assertTrue(impact.requiresRestart)
        assertFalse(impact.hotOnly)
    }

    // ------------------------------------------------------------------
    // reloadHot
    // ------------------------------------------------------------------

    @Test
    fun `热更键落地后同一份视图立刻读到新值`() {
        val store = ConfigStore()
        val eff = EffectiveConfig.of(store)
        assertEquals(3, eff.int(ConfigKey.SkipFrame), "注册缺省 SKIP FRAMES:3（ConfigSpec.kt:130）")

        val result = eff.reloadHot(ConfigKey.SkipFrame, ConfigValue.IntValue(9))

        assertTrue(result is ConfigReloadResult.Applied, "SkipFrame 在热更表内：$result")
        assertEquals(ConfigValue.IntValue(3), (result as ConfigReloadResult.Applied).previous)
        assertEquals(9, eff.int(ConfigKey.SkipFrame), "同一份视图的下一次读数即新值")
        assertEquals(9, store.itemOf(ConfigKey.SkipFrame).int(), "条目表被就地改写")
    }

    @Test
    fun `字号热更会重算夹取链与可绘制宽`() {
        val store = ConfigStore()
        val eff = EffectiveConfig.of(store)
        val windowX = eff.windowX
        assertEquals(windowX - 3, eff.drawableWidth, "缺省 18/6 = 3（Config.cs:461-466）")

        // 4 低于下限 8 ⇒ 落地值被夹到 8，提示同时出现（Config.cs:431-435）。
        val result = eff.reloadHot(ConfigKey.FontSize, ConfigValue.IntValue(4))

        assertTrue(result is ConfigReloadResult.Applied, "FontSize 在热更表内：$result")
        assertEquals(8, eff.fontSize, "夹到下限 8")
        assertEquals(1, eff.notices.count { it.kind == ConfigClampNotice.Kind.TOO_SMALL_FONT_SIZE })
        assertEquals(windowX - 2, eff.drawableWidth, "max(2, 8/6) = 2，可绘制宽原地重算")
    }

    @Test
    fun `重启键被拒且条目表不动`() {
        val store = ConfigStore()
        val eff = EffectiveConfig.of(store)
        val before = store.itemOf(ConfigKey.PrintCPerLine).value

        val result = eff.reloadHot(ConfigKey.PrintCPerLine, ConfigValue.IntValue(9))

        assertEquals(ConfigReloadResult.NeedsRestart(ConfigKey.PrintCPerLine), result)
        assertEquals(before, store.itemOf(ConfigKey.PrintCPerLine).value, "重启键一个字节都不写")
        assertEquals(3, eff.int(ConfigKey.PrintCPerLine), "视图读数仍是旧值（要重开会话才变）")
    }

    @Test
    fun `钉住的条目返回 Fixed`() {
        val store = ConfigStore()
        store.itemOf(ConfigKey.SkipFrame).fixed = true // fixed.config 的钉位（ConfigItem.cs:20-27）
        val eff = EffectiveConfig.of(store)

        val result = eff.reloadHot(ConfigKey.SkipFrame, ConfigValue.IntValue(9))

        assertEquals(ConfigReloadResult.Fixed(ConfigKey.SkipFrame), result)
        assertEquals(3, store.itemOf(ConfigKey.SkipFrame).int(), "钉住的条目保留旧值")
    }

    // ------------------------------------------------------------------
    // SettingsModel 的分桶与投递
    // ------------------------------------------------------------------

    @Test
    fun `changedKeys 与 pendingImpact 按作用域分桶`() {
        val model = SettingsModel()
        model.set("SkipFrame", "9")
        model.set("MaxLog", "900")

        // 注册顺序：MaxLog(13) 在 SkipFrame(24) 之前（ConfigKey.kt）。
        assertEquals(listOf(ConfigKey.MaxLog, ConfigKey.SkipFrame), model.changedKeys())
        val impact = model.pendingImpact()
        assertEquals(listOf(ConfigKey.SkipFrame), impact.hot)
        assertEquals(listOf(ConfigKey.MaxLog), impact.restart)
        assertTrue(impact.requiresRestart)
        assertFalse(impact.hotOnly)
    }

    @Test
    fun `markClean 之后影响面为空`() {
        val model = SettingsModel()
        model.set("SkipFrame", "9")
        assertFalse(model.pendingImpact().isEmpty)

        model.markClean() // SettingsStore.save 成功后的动作（SettingsStore.kt 的 save）

        assertTrue(model.pendingImpact().isEmpty, "保存后必须重新改才有影响面 —— 调用方要先算再写盘")
    }

    @Test
    fun `pushHotInto 只写热更键`() {
        val model = SettingsModel()
        model.set("SkipFrame", "9")
        model.set("MaxLog", "900")
        val live = ConfigStore() // 模拟运行中会话的那一份

        val applied = model.pushHotInto(live)

        assertEquals(listOf(ConfigKey.SkipFrame), applied)
        assertEquals(9, live.itemOf(ConfigKey.SkipFrame).int(), "热更键落进活条目表")
        assertEquals(5000, live.itemOf(ConfigKey.MaxLog).int(), "重启键不写（缺省 5000）")
        assertEquals(9, model.store.itemOf(ConfigKey.SkipFrame).int(), "源模型本来就改了")
    }

    @Test
    fun `pushHotInto 不改变写盘文本`() {
        val model = SettingsModel()
        model.set("SkipFrame", "9")
        model.set("MaxLog", "900")
        val original = "IGNORE CASE:YES\n# 手写注释保留\nCUSTOM KEY:1\n"
        val before = model.buildMerge(original)

        model.pushHotInto(ConfigStore())

        assertEquals(before, model.buildMerge(original), "热更投递不碰源模型 ⇒ 保留式写回逐字节不变")
        assertTrue(before.contains("# 手写注释保留"), "注释保留")
        assertTrue(before.contains("CUSTOM KEY:1"), "未知键保留")
    }
}
