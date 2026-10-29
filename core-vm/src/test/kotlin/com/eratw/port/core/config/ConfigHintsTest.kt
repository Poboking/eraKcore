package com.eratw.port.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CONFIG-REST 的判据 —— 设置页只读组（DEBUG / REPLACE）的标签、夹取提示本地化与「不写回」。
 *
 * 断言的期望值全部指回 C# 出处，不指回本仓的另一份代码：
 *   * 三组条目数量与来源 —— `configArray` 100 条（spec: `ConfigData.cs:45-151`）、
 *     `debugArray` 7 条（spec: `:152-158`、读取路径 `:34`）、
 *     `replaceArray` 16 条（spec: `:159-174`、读取路径 `Process.cs:240-246`）；
 *   * DEBUG 页面标签 —— `DebugConfigDialog.cs:85-99` 的绑定与 `Lang.cs` / `lang.emuera-zhs.xml`
 *     的译文（`ConfigPageLabels` 已逐条登记出处）；REPLACE 在**原版没有设置页**，
 *     标签只能回落配置文件键（`ConfigData.cs:159-174` 的 Text / EngText），`ConfigPageLabels`
 *     按构造期自检不登记它 —— 测试钉住这个「没有」；
 *   * 夹取提示 —— `Config.cs:431-460` 的 5 个 `Dialog.Show` + 1 个 `MessageBox.Show`，
 *     正文与标题取自 `Lang.cs:4030` / `:4034` / `:4038` / `:4042` / `:4046` / `:4050` / `:4214`，
 *     简体中文取自 `lang.emuera-zhs.xml:947-952`，英文取自 `lang.emuera-eng.xml:950-955`；
 *     两份语言文件都没有的 WINAPI 条目按 `TranslatableString.Text` 回落日语原文（spec: `Lang.cs:23-33`）；
 *   * 只读组无写回路径 —— `EmueraConfigWriter.mergeInto` 只认 `configArray` 的键
 *     （spec: `ConfigData.cs:430`），所以非 GAME 组的原值必须逐字节留在原地。
 */
class ConfigHintsTest {

    // -----------------------------------------------------------------
    // 分组口径
    // -----------------------------------------------------------------

    @Test
    fun `123 项三组全可见且只有 GAME 组可编辑`() {
        val model = SettingsModel()
        assertEquals(123, model.entries.size, "123 项全表（ConfigData.cs:45-174）")
        assertEquals(123, ConfigSpecTable.all.size, "条目表与 C# 三份注册数组同规模")

        val groups = model.groups
        assertEquals(
            listOf(ConfigGroup.GAME, ConfigGroup.DEBUG, ConfigGroup.REPLACE),
            groups.map { it.group },
            "分组顺序 = setDefault 的注册顺序（ConfigData.cs:45-174）",
        )
        assertEquals(listOf(100, 7, 16), groups.map { it.entries.size }, "100 + 7 + 16 = 123")
        assertEquals(123, groups.sumOf { it.entries.size })
        assertEquals(
            listOf(true, false, false),
            groups.map { it.editable },
            "只有 configArray 有写回路径（ConfigData.cs:430）",
        )
        assertEquals(
            setOf("emuera.config", "debug.config", "_Replace.csv"),
            groups.map { it.fileName }.toSet(),
        )
        // 只读组的条目键集合 = 条目表里非 GAME 的全部键（不是按名字挑的）。
        assertEquals(
            ConfigSpecTable.all.filter { it.group != ConfigGroup.GAME }.map { it.key }.toSet(),
            groups.filter { !it.editable }.flatMap { group -> group.entries.map { it.key } }.toSet(),
        )
    }

    @Test
    fun `只读组键集合逐条对齐 ConfigData 的 debugArray 与 replaceArray`() {
        val model = SettingsModel()
        // 期望值是 ConfigData.cs 的两个注册数组逐行抄下来的（不是从本仓条目表反推）。
        assertEquals(
            listOf(
                ConfigKey.DebugShowWindow, // :152
                ConfigKey.DebugWindowTopMost, // :153
                ConfigKey.DebugWindowWidth, // :154
                ConfigKey.DebugWindowHeight, // :155
                ConfigKey.DebugSetWindowPos, // :156
                ConfigKey.DebugWindowPosX, // :157
                ConfigKey.DebugWindowPosY, // :158
            ),
            model.group(ConfigGroup.DEBUG).entries.map { it.key },
            "debugArray 7 条（ConfigData.cs:152-158）",
        )
        assertEquals(
            listOf(
                ConfigKey.MoneyLabel, // :159
                ConfigKey.MoneyFirst, // :160
                ConfigKey.LoadLabel, // :161
                ConfigKey.MaxShopItem, // :162
                ConfigKey.DrawLineString, // :163
                ConfigKey.BarChar1, // :164
                ConfigKey.BarChar2, // :165
                ConfigKey.TitleMenuString0, // :166
                ConfigKey.TitleMenuString1, // :167
                ConfigKey.ComAbleDefault, // :168
                ConfigKey.StainDefault, // :169
                ConfigKey.TimeupLabel, // :170
                ConfigKey.ExpLvDef, // :171
                ConfigKey.PalamLvDef, // :172
                ConfigKey.pbandDef, // :173
                ConfigKey.RelationDef, // :174
            ),
            model.group(ConfigGroup.REPLACE).entries.map { it.key },
            "replaceArray 16 条（ConfigData.cs:159-174）",
        )
        // 两组都落在 ConfigGroup 上（生产判定用 SettingsGroupView.editable，不看条目名）。
        assertTrue(model.group(ConfigGroup.DEBUG).entries.all { it.group == ConfigGroup.DEBUG })
        assertTrue(model.group(ConfigGroup.REPLACE).entries.all { it.group == ConfigGroup.REPLACE })
    }

    // -----------------------------------------------------------------
    // 只读组标签
    // -----------------------------------------------------------------

    @Test
    fun `只读组 23 个条目在中文 日文与英文开关下都有标签`() {
        val model = SettingsModel()
        val readOnly = model.entries.filter { it.group != ConfigGroup.GAME }
        // 7 + 16 = 23（ConfigData.cs:152-174）
        assertEquals(23, readOnly.size)
        for (entry in readOnly) {
            // 设置页的三种显示形态：中文页面文案、日文页面文案、英文开关下的配置键原文。
            for ((zhHans, english) in listOf(true to false, false to false, true to true)) {
                assertTrue(
                    ConfigPageLabels.displayLabel(entry.spec, zhHans, english).isNotBlank(),
                    "${entry.id} 在 zh=$zhHans english=$english 下标签为空",
                )
            }
        }

        // DEBUG 7 条：标签来自 DebugConfigDialog 的页面文案，中日文都应有（ConfigPageLabels 的登记）。
        val debug = model.group(ConfigGroup.DEBUG).entries
        assertEquals(7, debug.size)
        debug.forEach { entry ->
            val page = assertNotNull(
                ConfigPageLabels.byKey(entry.spec.key),
                "${entry.id} 应有 DebugConfigDialog 页面标签（DebugConfigDialog.cs:85-99）",
            )
            // 7 条 debug 的 zhs 译文都不缺（lang.emuera-zhs.xml:172-178）。
            assertNotNull(page.zhsLabel, "${entry.id} 的 zhs 译文不应缺")
            assertEquals(page.zhsLabel, ConfigPageLabels.displayLabel(entry.spec, true, false))
            assertEquals(page.jaLabel, ConfigPageLabels.displayLabel(entry.spec, false, false))
            assertEquals(
                entry.enLabel,
                ConfigPageLabels.displayLabel(entry.spec, true, true),
                "英文开关显示配置键原文（AConfigItem.cs:23）",
            )
        }

        // REPLACE 16 条：原版 _Replace.csv 没有设置对话框，ConfigPageLabels 如实不登记，
        // 中文/日文界面都回落 ConfigData.cs:159-174 的日文 Text，英文开关取 EngText。
        val replace = model.group(ConfigGroup.REPLACE).entries
        assertEquals(16, replace.size)
        replace.forEach { entry ->
            assertNull(
                ConfigPageLabels.byKey(entry.spec.key),
                "${entry.id} 属于 replaceArray，原版无设置页，不应有页面标签",
            )
            assertEquals(entry.jpLabel, ConfigPageLabels.displayLabel(entry.spec, true, false))
            assertEquals(entry.jpLabel, ConfigPageLabels.displayLabel(entry.spec, false, false))
            assertEquals(entry.enLabel, ConfigPageLabels.displayLabel(entry.spec, true, true))
        }
    }

    @Test
    fun `GAME 组 100 项标签口径与旧设置页逐条一致`() {
        val model = SettingsModel()
        val game = model.group(ConfigGroup.GAME).entries
        assertEquals(100, game.size, "configArray 100 条（ConfigData.cs:45-151）")
        game.forEach { entry ->
            // 旧设置页：englishLabels ? enLabel : pageZhsLabel ?: pageJaLabel ?: jpLabel
            val old = entry.pageZhsLabel ?: entry.pageJaLabel ?: entry.jpLabel
            assertEquals(old, ConfigPageLabels.displayLabel(entry.spec, true, false), "${entry.id} 中文标签")
            assertEquals(
                entry.pageJaLabel ?: entry.jpLabel,
                ConfigPageLabels.displayLabel(entry.spec, false, false),
                "${entry.id} 日文标签（TranslatableString 的基准语言，Lang.cs:23-33）",
            )
            assertEquals(entry.enLabel, ConfigPageLabels.displayLabel(entry.spec, true, true), "${entry.id} 英文键")
        }
    }

    // -----------------------------------------------------------------
    // 夹取提示文案
    // -----------------------------------------------------------------

    @Test
    fun `六种夹取提示都有原文 简中与英文文案且逐字对齐原版语言文件`() {
        val notices = noticesTriggeringAllKinds()
        // 顺序 = Config.cs:431-460 的夹取顺序；第六种来自另一个 store（同一条目不能同时越上下限）。
        assertEquals(
            listOf(
                ConfigClampNotice.Kind.TOO_SMALL_FONT_SIZE,
                ConfigClampNotice.Kind.LINE_HEIGHT_LESS_THAN_FONT_SIZE,
                ConfigClampNotice.Kind.TOO_SMALL_DISPLAY_SAVE_DATA,
                ConfigClampNotice.Kind.TOO_SMALL_LOG_SIZE,
                ConfigClampNotice.Kind.WINAPI_UNSUPPORTED,
                ConfigClampNotice.Kind.TOO_LARGE_DISPLAY_SAVE_DATA,
            ),
            notices.map { it.kind },
        )
        assertEquals(
            ConfigClampNotice.Kind.entries.toSet(),
            ConfigClampLabels.all.map { it.kind }.toSet(),
            "文案表必须覆盖全部提示种类",
        )

        val expectedZhs = mapOf(
            // lang.emuera-zhs.xml:948
            ConfigClampNotice.Kind.TOO_SMALL_FONT_SIZE to "字体大小过小(下限为8)",
            // lang.emuera-zhs.xml:949
            ConfigClampNotice.Kind.LINE_HEIGHT_LESS_THAN_FONT_SIZE to
                "由于行高小于字体大小，将其视为与字体大小等同",
            // lang.emuera-zhs.xml:950
            ConfigClampNotice.Kind.TOO_SMALL_DISPLAY_SAVE_DATA to "显示的存档数据数量过小(下限为20)",
            // lang.emuera-zhs.xml:951
            ConfigClampNotice.Kind.TOO_LARGE_DISPLAY_SAVE_DATA to "显示的存档数据数量过大(上限为80)",
            // lang.emuera-zhs.xml:952
            ConfigClampNotice.Kind.TOO_SMALL_LOG_SIZE to "日志显示行数过少(下限为500)",
            // zhs / eng 语言文件都没有 MessageBox.DoNotSupportWINAPI 这个 id。
            ConfigClampNotice.Kind.WINAPI_UNSUPPORTED to null,
        )
        assertEquals(ConfigClampNotice.Kind.entries.size, expectedZhs.size)

        val expectedEn = mapOf(
            // lang.emuera-eng.xml:951
            ConfigClampNotice.Kind.TOO_SMALL_FONT_SIZE to "Font size is too small (Less than 8).",
            // lang.emuera-eng.xml:952
            ConfigClampNotice.Kind.LINE_HEIGHT_LESS_THAN_FONT_SIZE to
                "The line height will be interpreted the same as the font size due to it being smaller.",
            // lang.emuera-eng.xml:953
            ConfigClampNotice.Kind.TOO_SMALL_DISPLAY_SAVE_DATA to
                "Amount of save data to display is too small (Less than 20).",
            // lang.emuera-eng.xml:954
            ConfigClampNotice.Kind.TOO_LARGE_DISPLAY_SAVE_DATA to
                "Amount of save data to display exceeds the limit (More than 80).",
            // lang.emuera-eng.xml:955
            ConfigClampNotice.Kind.TOO_SMALL_LOG_SIZE to "Log size is too small (Less than 500).",
            // 同上：eng 文件也没有 DoNotSupportWINAPI，非日语界面回落日文原文。
            ConfigClampNotice.Kind.WINAPI_UNSUPPORTED to null,
        )
        assertEquals(ConfigClampNotice.Kind.entries.size, expectedEn.size)

        val expectedSources = mapOf(
            ConfigClampNotice.Kind.TOO_SMALL_FONT_SIZE to
                Triple("Lang.cs:4034", "lang.emuera-zhs.xml:948", "lang.emuera-eng.xml:951"),
            ConfigClampNotice.Kind.LINE_HEIGHT_LESS_THAN_FONT_SIZE to
                Triple("Lang.cs:4038", "lang.emuera-zhs.xml:949", "lang.emuera-eng.xml:952"),
            ConfigClampNotice.Kind.TOO_SMALL_DISPLAY_SAVE_DATA to
                Triple("Lang.cs:4042", "lang.emuera-zhs.xml:950", "lang.emuera-eng.xml:953"),
            ConfigClampNotice.Kind.TOO_LARGE_DISPLAY_SAVE_DATA to
                Triple("Lang.cs:4046", "lang.emuera-zhs.xml:951", "lang.emuera-eng.xml:954"),
            ConfigClampNotice.Kind.TOO_SMALL_LOG_SIZE to
                Triple("Lang.cs:4050", "lang.emuera-zhs.xml:952", "lang.emuera-eng.xml:955"),
            ConfigClampNotice.Kind.WINAPI_UNSUPPORTED to Triple("Lang.cs:4214", null, null),
        )
        assertEquals(ConfigClampNotice.Kind.entries.size, expectedSources.size)

        for (notice in notices) {
            val label = assertNotNull(ConfigClampLabels.labelFor(notice.kind), "${notice.kind} 缺文案")
            val sources = expectedSources.getValue(notice.kind)
            assertEquals(notice.key, label.key, "${notice.kind} 的配置条目")
            assertEquals(sources.first, label.jaSource, "${notice.kind} 日语出处")
            assertEquals(sources.second, label.zhsSource, "${notice.kind} 中文出处")
            assertEquals(sources.third, label.enSource, "${notice.kind} 英文出处")
            assertEquals(notice.messageJp, label.jaText, "${notice.kind} 的日语原文")
            assertEquals(notice.messageJp, label.text(ConfigClampLanguage.JAPANESE))
            assertEquals(
                notice.messageJp,
                ConfigClampLabels.textFor(notice, ConfigClampLanguage.JAPANESE),
            )

            val zhs = expectedZhs.getValue(notice.kind)
            if (zhs == null) {
                assertNull(label.zhsSource, "${notice.kind} 没有中文译文就不应有中文出处")
                assertEquals(
                    notice.messageJp,
                    ConfigClampLabels.textFor(notice, ConfigClampLanguage.CHINESE_SIMPLIFIED),
                    "缺译回落日语原文（Lang.cs:23-33）",
                )
            } else {
                assertNotNull(label.zhsSource, "${notice.kind} 有中文译文就应有出处")
                assertEquals(zhs, label.zhsText, "${notice.kind} 的中文译文")
                assertEquals(
                    zhs,
                    ConfigClampLabels.textFor(notice, ConfigClampLanguage.CHINESE_SIMPLIFIED),
                    "${notice.kind} 中文界面",
                )
            }

            val en = expectedEn.getValue(notice.kind)
            if (en == null) {
                assertNull(label.enSource, "${notice.kind} 没有英文译文就不应有英文出处")
                assertEquals(
                    notice.messageJp,
                    ConfigClampLabels.textFor(notice, ConfigClampLanguage.ENGLISH),
                    "缺译回落日语原文（Lang.cs:23-33）",
                )
            } else {
                assertNotNull(label.enSource, "${notice.kind} 有英文译文就应有出处")
                assertEquals(en, label.enText, "${notice.kind} 的英文译文")
                assertEquals(
                    en,
                    ConfigClampLabels.textFor(notice, ConfigClampLanguage.ENGLISH),
                    "${notice.kind} 英文界面",
                )
            }

            if (notice.kind == ConfigClampNotice.Kind.WINAPI_UNSUPPORTED) {
                // MessageBox.Show(正文) 没有标题参数（Config.cs:458）。
                assertNull(notice.titleJp)
                for (language in ConfigClampLanguage.entries) {
                    assertNull(ConfigClampLabels.titleFor(notice, language))
                }
            } else {
                // 5 个 Dialog.Show 的第一参数都是 Lang.MessageBox.ConfigError（Config.cs:433/438/443/448/453）。
                assertEquals("設定のエラー", notice.titleJp) // Lang.cs:4030
                assertEquals(
                    "設定のエラー",
                    ConfigClampLabels.titleFor(notice, ConfigClampLanguage.JAPANESE),
                )
                assertEquals(
                    "设定错误",
                    ConfigClampLabels.titleFor(notice, ConfigClampLanguage.CHINESE_SIMPLIFIED),
                ) // zhs:947
                assertEquals(
                    "Config Error",
                    ConfigClampLabels.titleFor(notice, ConfigClampLanguage.ENGLISH),
                ) // eng:950
            }
        }
    }

    @Test
    fun `zhs 与 eng 缺译的 WINAPI 提示回落日文原文`() {
        val winapi = noticesTriggeringAllKinds()
            .single { it.kind == ConfigClampNotice.Kind.WINAPI_UNSUPPORTED }
        val label = assertNotNull(
            ConfigClampLabels.labelFor(ConfigClampNotice.Kind.WINAPI_UNSUPPORTED),
            "WINAPI 提示应有登记行",
        )
        assertNull(label.zhsText)
        assertNull(label.zhsSource)
        assertNull(label.enText)
        assertNull(label.enSource)
        assertEquals("WINAPIモードはサポートされていません", label.jaText) // Lang.cs:4214
        // 原版 TranslatableString.Text：tr 为 null 时返回原文（Lang.cs:23-33）。
        assertEquals(
            winapi.messageJp,
            ConfigClampLabels.textFor(winapi, ConfigClampLanguage.CHINESE_SIMPLIFIED),
        )
        assertEquals(
            winapi.messageJp,
            ConfigClampLabels.textFor(winapi, ConfigClampLanguage.ENGLISH),
        )
        for (language in ConfigClampLanguage.entries) {
            assertNull(ConfigClampLabels.titleFor(winapi, language))
        }
    }

    // -----------------------------------------------------------------
    // 只读组无写回路径
    // -----------------------------------------------------------------

    @Test
    fun `DEBUG 与 REPLACE 的键既不被改写也不被追加`() {
        val model = SettingsModel()
        // 原文 = SaveConfig 口径的全量 GAME 行（ConfigData.cs:430）+ 两行只读组原值 + 一行注释。
        val original = EmueraConfigWriter.gameLines(model.store, english = false).joinToString("\n") + "\n" +
            "; 只读组：本移植的设置页不写回\n" +
            "DEBUG WINDOW WIDTH:640\n" +
            "CURRENCY SYMBOL:円\n"
        assertEquals(original, model.buildMerge(original), "未改动时合并必须逐字节还原原文")

        // 模型层不按组设限（装配层还要用 SETCONFIG 那条链），但值变化不得进入写出集合。
        assertTrue(
            model.set("DebugWindowWidth", "800") is SettingsSetResult.Applied,
            "DEBUG 条目在模型层可写（原版 DebugConfigDialog 自己写 debug.config）",
        )
        assertTrue(
            model.set("MoneyLabel", "＄") is SettingsSetResult.Applied,
            "REPLACE 条目在模型层可写（装配层据此重建变量池）",
        )
        assertEquals(800, model.store.itemOf(ConfigKey.DebugWindowWidth).int())
        assertEquals("＄", model.store.itemOf(ConfigKey.MoneyLabel).string())

        val merged = model.buildMerge(original)
        assertEquals(original, merged, "非 GAME 组的值变化不改变 emuera.config 的合并结果（逐字节）")
        // 原行逐字符保留：既没被替换成新值，也没有在尾部追加第二份。
        assertTrue(merged.contains("DEBUG WINDOW WIDTH:640\n"), "DEBUG 行保持原值")
        assertTrue(merged.contains("CURRENCY SYMBOL:円\n"), "REPLACE 行保持原值")
        assertEquals(1, merged.split("DEBUG WINDOW WIDTH").size - 1, "DEBUG 行不得被追加第二份")
        assertEquals(1, merged.split("CURRENCY SYMBOL").size - 1, "REPLACE 行不得被追加第二份")
        // 写出集合恰好等于 configArray（ConfigData.cs:430）。
        assertEquals(
            EmueraConfigWriter.gameLines(model.store, english = false).joinToString("\n") + "\n",
            EmueraConfigWriter.mergeInto("", model.store, english = false),
            "空原文时的产物 = 全量 GAME 行（首建路径）",
        )
        val readOnly = model.entries.filter { it.group != ConfigGroup.GAME }
        assertEquals(23, readOnly.size)
        for (entry in readOnly) {
            assertFalse(merged.contains(entry.jpLabel + ":"), "${entry.id} 的日文键不应被写出")
            assertFalse(merged.contains(entry.enLabel + ":"), "${entry.id} 的英文键不应被写出")
        }
    }

    @Test
    fun `只读组值变化时 CRLF 行尾与注释仍逐字节保留`() {
        val model = SettingsModel()
        // 原文 = SaveConfig 口径的全量 GAME 行（ConfigData.cs:430）+ 注释 + 两行只读组原值，统一 CRLF。
        val gameText = EmueraConfigWriter.gameLines(model.store, english = false)
            .joinToString("\r\n", postfix = "\r\n")
        val original = gameText +
            "; 手写注释：只读组原样留着\r\n" +
            "DEBUG WINDOW WIDTH:640\r\n" +
            "CURRENCY SYMBOL:円\r\n"
        assertEquals(original, model.buildMerge(original, english = false), "初始状态必须逐字节还原")

        assertTrue(model.set("DebugWindowWidth", "1280") is SettingsSetResult.Applied)
        assertTrue(model.set("MoneyLabel", "＄") is SettingsSetResult.Applied)
        assertTrue(model.set("DebugShowWindow", "NO") is SettingsSetResult.Applied)
        assertEquals(
            original,
            model.buildMerge(original, english = false),
            "只读组改动后，CRLF、注释与两行原值都必须逐字节不动",
        )
    }

    @Test
    fun `GAME 组写回路径未被只读改造影响`() {
        val model = SettingsModel()
        val original = model.buildMerge("", english = false)
        assertTrue(original.contains("ウィンドウ幅:760\n"), "缺省写出含 GAME 默认值（ConfigData.cs:56）")

        assertTrue(model.set("WindowX", "1000") is SettingsSetResult.Applied)
        val merged = model.buildMerge(original, english = false)
        assertNotEquals(original, merged, "GAME 组必须仍可写回")
        assertEquals(
            original.replace("ウィンドウ幅:760\n", "ウィンドウ幅:1000\n"),
            merged,
            "值变化只落在自己那一行，其余 GAME 行逐字节不动（ConfigData.cs:430-453）",
        )
    }

    @Test
    fun `全部重置只动 GAME 组`() {
        val model = SettingsModel()
        assertTrue(model.set("WindowX", "1000") is SettingsSetResult.Applied)
        assertTrue(model.set("DebugWindowWidth", "800") is SettingsSetResult.Applied)
        assertTrue(model.set("MoneyLabel", "円") is SettingsSetResult.Applied)

        assertEquals(
            100,
            model.resetAllEditable(),
            "只读组不在重置范围内，写出条数上界 = configArray 100 条（ConfigData.cs:45-151）",
        )
        assertEquals(760, model.store.itemOf(ConfigKey.WindowX).int(), "GAME 条目回到缺省（ConfigData.cs:56）")
        // 只读组保持页面打开期间的值：它们不参与 reset 写回，页面上也不会被改（控件 disabled）。
        assertEquals(800, model.store.itemOf(ConfigKey.DebugWindowWidth).int())
        assertEquals("円", model.store.itemOf(ConfigKey.MoneyLabel).string())
    }

    // -----------------------------------------------------------------
    // 夹具
    // -----------------------------------------------------------------

    private fun storeWith(vararg pairs: Pair<ConfigKey, ConfigValue>): ConfigStore {
        val store = ConfigStore()
        pairs.forEach { (key, value) ->
            assertTrue(store.itemOf(key).assign(value), "准备夹具失败：$key")
        }
        return store
    }

    /**
     * 触发全部六种 [ConfigClampNotice.Kind]：
     * `Config.cs:431-455` 的 5 个夹取 + `:456-460` 的 WINAPI 改写；`SaveDataNos` 同一条目不能
     * 同时低于 20 又高于 80，因此上下限各用一份 store。
     */
    private fun noticesTriggeringAllKinds(): List<ConfigClampNotice> {
        val low = EffectiveConfig.of(
            storeWith(
                ConfigKey.FontSize to ConfigValue.IntValue(4),
                ConfigKey.LineHeight to ConfigValue.IntValue(5),
                ConfigKey.SaveDataNos to ConfigValue.IntValue(10),
                ConfigKey.MaxLog to ConfigValue.IntValue(400),
                ConfigKey.TextDrawingMode to
                    ConfigValue.EnumValue(ConfigEnumKind.TEXT_DRAWING_MODE, "WINAPI"),
            ),
        ).notices
        val high = EffectiveConfig.of(
            storeWith(ConfigKey.SaveDataNos to ConfigValue.IntValue(200)),
        ).notices
        return low + high
    }
}
