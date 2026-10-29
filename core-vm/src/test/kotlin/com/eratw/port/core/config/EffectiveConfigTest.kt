package com.eratw.port.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Config.SetConfig` 夹取链测试（spec: `Config.cs:296-486`，夹取段 `:431-466`）。
 *
 * 两条最容易做错、因此必须钉住的口径：
 *   1. **夹取不回写条目**：`Config.cs:431-455` 只给静态属性赋值，`configArray` 里仍是玩家写的越界值；
 *      于是写出（[EmueraConfigWriter.gameLines]）与设置界面回显看到的都是**原值**
 *      （spec: `ConfigDialog.cs:694-705`），每次开机都会重新弹一次框。
 *   2. **`LineHeight` 比的是夹过的 `FontSize`**（spec: `Config.cs:436-440` 紧跟在 `:434` 之后）。
 */
class EffectiveConfigTest {

    private fun storeWith(vararg overrides: Pair<ConfigKey, ConfigValue>): ConfigStore {
        val store = ConfigStore()
        for ((key, value) in overrides) {
            store.itemOf(key).assign(value)
        }
        return store
    }

    private fun i(v: Int) = ConfigValue.IntValue(v)

    private fun b(v: Boolean) = ConfigValue.Bool(v)

    private fun e(kind: ConfigEnumKind, constant: String) = ConfigValue.EnumValue(kind, constant)

    @Test
    fun `全默认配置下一条夹取都不触发`() {
        val eff = EffectiveConfig.of(ConfigStore())
        assertEquals(emptyList<ConfigClampNotice>(), eff.notices)
        assertEquals(18, eff.fontSize) // ConfigData.cs:66
        assertEquals(19, eff.lineHeight) // :67
        assertEquals(MaxLogSetting(5000, 5000, raisedToFloor = false), eff.maxLog) // :62 + Config.cs:451-455
        assertEquals(20, eff.saveDataNos) // :87
        assertEquals("TEXTRENDERER", eff.textDrawingMode) // :55
        assertEquals(3, eff.shapePositionShift, "max(2, 18/6) = 3（Config.cs:464，整除）")
        assertEquals(757, eff.drawableWidth, "760 - 3（Config.cs:466 + ConfigData.cs:56）")
        assertEquals(LanguageSetting(1041, 932), eff.language, "默认 JAPANESE（ConfigData.cs:103 → Config.cs:414-417）")
        assertTrue(eff.warnFunctionOverloading)
        assertEquals(false, eff.needReduceArgumentOnLoad, "默认 NO ⇒ false（Config.cs:600-603）")
        assertFalse(eff.englishConfigOutput)
        // 默认表写出去是 97 行：100 条减去三条不写出的（ConfigData.cs:433）
        val defaultLines = EmueraConfigWriter.gameLines(ConfigStore(), english = false)
        assertEquals(97, defaultLines.size)
        assertTrue(defaultLines.contains("大文字小文字の違いを無視する:YES"), "缺省用日文标签（ConfigItem.cs:86 听 Config.EnglishConfigOutput）")
        assertTrue(defaultLines.contains("履歴ログの行数:5000"))
        assertTrue(defaultLines.none { it.startsWith("DRAWLINEを常に新しい行で行う") }, "CompatiDRAWLINE 永不写出")
        assertTrue(defaultLines.none { it.startsWith("デバッグコマンドを使用した時にMASTERの名前を変更する") }, "值为 true 时不写出（ConfigData.cs:433）")
        assertTrue(defaultLines.none { it.startsWith("最終更新コード") }, "LastKey 为 0 时不写出（ConfigData.cs:433）")
    }

    @Test
    fun `字号下限 8 且行高跟着夹过的字号`() {
        val store = storeWith(ConfigKey.FontSize to i(4), ConfigKey.LineHeight to i(5))
        val eff = EffectiveConfig.of(store)
        assertEquals(8, eff.fontSize, "Config.cs:431-435")
        assertEquals(8, eff.lineHeight, "5 与**夹过之后**的 8 比 ⇒ 抬到 8（Config.cs:436-440）")
        assertEquals(
            listOf(ConfigClampNotice.Kind.TOO_SMALL_FONT_SIZE, ConfigClampNotice.Kind.LINE_HEIGHT_LESS_THAN_FONT_SIZE),
            eff.notices.map { it.kind },
        )
        assertEquals("4", eff.notices[0].requested)
        assertEquals("8", eff.notices[0].effective)
        assertEquals("5", eff.notices[1].requested)
        assertEquals("設定のエラー", eff.notices[0].titleJp, "Lang.cs:4030")
        assertEquals("フォントサイズが小さすぎます(8が下限)", eff.notices[0].messageJp) // Lang.cs:4034
        assertEquals("行の高さがフォントサイズより小さいため、フォントサイズと同じ高さと解釈されます", eff.notices[1].messageJp) // Lang.cs:4038
        // 夹取不回写条目 ⇒ 写出与回显仍是原值
        assertEquals(4, store.itemOf(ConfigKey.FontSize).int())
        assertEquals(5, store.itemOf(ConfigKey.LineHeight).int())
        assertTrue(EmueraConfigWriter.gameLines(store, english = true).contains("FONT SIZE:4"))
        assertTrue(EmueraConfigWriter.gameLines(store, english = true).contains("LINE HEIGHT:5"))
        assertEquals(2, eff.shapePositionShift, "max(2, 8/6=1) = 2")
    }

    @Test
    fun `保存页数双向夹到 20 到 80`() {
        val low = EffectiveConfig.of(storeWith(ConfigKey.SaveDataNos to i(10)))
        assertEquals(20, low.saveDataNos) // Config.cs:441-445
        assertEquals(ConfigClampNotice.Kind.TOO_SMALL_DISPLAY_SAVE_DATA, low.notices.single().kind)
        assertEquals("表示するセーブデータ数が少なすぎます(20が下限)", low.notices.single().messageJp) // Lang.cs:4042
        val high = EffectiveConfig.of(storeWith(ConfigKey.SaveDataNos to i(200)))
        assertEquals(80, high.saveDataNos) // Config.cs:446-450
        assertEquals(ConfigClampNotice.Kind.TOO_LARGE_DISPLAY_SAVE_DATA, high.notices.single().kind)
        assertEquals("表示するセーブデータ数が多すぎます(80が上限)", high.notices.single().messageJp) // Lang.cs:4046
        assertEquals(0, EffectiveConfig.of(storeWith(ConfigKey.SaveDataNos to i(80))).notices.size, "边界值 80 不触发")
        assertEquals(0, EffectiveConfig.of(storeWith(ConfigKey.SaveDataNos to i(20))).notices.size, "边界值 20 不触发")
    }

    @Test
    fun `LOG 下限 500 属配置层且上报 requested 供上层弹框`() {
        val store = storeWith(ConfigKey.MaxLog to i(400))
        val eff = EffectiveConfig.of(store)
        assertEquals(500, eff.maxLog.effective)
        assertEquals(400, eff.maxLog.requested, "文案里的数是原值（Config.cs:451-455 的 Dialog.Show 先于改写）")
        assertTrue(eff.maxLog.raisedToFloor)
        assertEquals(ConfigClampNotice.Kind.TOO_SMALL_LOG_SIZE, eff.notices.single().kind)
        assertEquals("ログ表示行数が少なすぎます(500が下限)", eff.notices.single().messageJp) // Lang.cs:4050
        assertEquals(400, store.itemOf(ConfigKey.MaxLog).int(), "条目不回写 ⇒ 下次开机还要再弹一次")
        assertEquals(500, eff.int(ConfigKey.MaxLog), "生效读数走 effective")
        assertEquals(500, MaxLogSetting.FLOOR)
        assertEquals(5000, MaxLogSetting.DEFAULT)
        // 恰好 500 与高于 500 都不触发（原版只有 `< 500` 一个分支，没有上限）
        assertEquals(0, EffectiveConfig.of(storeWith(ConfigKey.MaxLog to i(500))).notices.size)
        assertEquals(0, EffectiveConfig.of(storeWith(ConfigKey.MaxLog to i(1000000))).notices.size)
    }

    @Test
    fun `WINAPI 改回 TEXTRENDERER 并让 shift 的死分支恒真`() {
        val store = storeWith(ConfigKey.TextDrawingMode to e(ConfigEnumKind.TEXT_DRAWING_MODE, "WINAPI"))
        val eff = EffectiveConfig.of(store)
        assertEquals("TEXTRENDERER", eff.textDrawingMode) // Config.cs:456-460
        assertEquals("TEXTRENDERER", eff.enumConstant(ConfigKey.TextDrawingMode), "生效读数跟着改写走")
        assertNull(eff.notices.single().titleJp, "这条走 MessageBox.Show，没有标题参数（Config.cs:458）")
        assertEquals("WINAPIモードはサポートされていません", eff.notices.single().messageJp) // Lang.cs:4214
        assertEquals(ConfigClampNotice.Kind.WINAPI_UNSUPPORTED, eff.notices.single().kind)
        assertEquals(3, eff.shapePositionShift, "改写发生在 shift 判定之前 ⇒ `!= WINAPI` 恒真（Config.cs:462 是死分支）")
        assertEquals("WINAPI", store.itemOf(ConfigKey.TextDrawingMode).enumConstant(), "条目不回写")
    }

    @Test
    fun `不允许系统函数覆盖时告警被强制打开`() {
        val store = storeWith(
            ConfigKey.AllowFunctionOverloading to b(false),
            ConfigKey.WarnFunctionOverloading to b(false),
        )
        val eff = EffectiveConfig.of(store)
        assertTrue(eff.warnFunctionOverloading, "Config.cs:345-348：不允许覆盖 ⇒ 必出告警，配置文件关不掉")
        assertFalse(store.itemOf(ConfigKey.WarnFunctionOverloading).bool(), "条目仍是玩家写的 NO")
        val allowed = storeWith(ConfigKey.AllowFunctionOverloading to b(true), ConfigKey.WarnFunctionOverloading to b(false))
        assertFalse(EffectiveConfig.of(allowed).warnFunctionOverloading, "允许覆盖时才听条目的（Config.cs:351）")
    }

    @Test
    fun `语言常量映射到 LCID 与 ANSI 代码页`() {
        val cases = listOf(
            "JAPANESE" to LanguageSetting(1041, 932), // Config.cs:414-417
            "KOREAN" to LanguageSetting(1042, 949), // Config.cs:418-421
            "CHINESE_HANS" to LanguageSetting(2052, 936), // Config.cs:422-425
            "CHINESE_HANT" to LanguageSetting(1028, 950), // Config.cs:426-429
        )
        for ((constant, expected) in cases) {
            val store = storeWith(ConfigKey.useLanguage to e(ConfigEnumKind.USE_LANGUAGE, constant))
            assertEquals(expected, EffectiveConfig.of(store).language, constant)
        }
        // 非 UseLanguage 的种类没有语言映射（Config.cs 那个 switch 只覆盖它）
        assertNull(ConfigEnumKind.TEXT_EDITOR_TYPE.languageSetting("TERAPAD"))
        assertNull(ConfigEnumKind.USE_LANGUAGE.languageSetting("NOT_A_LANGUAGE"))
    }

    @Test
    fun `ReduceArgumentOnLoad 的 ONCE 保持上一次的值`() {
        val once = storeWith(ConfigKey.ReduceArgumentOnLoad to e(ConfigEnumKind.REDUCE_ARGUMENT_ON_LOAD, "ONCE"))
        assertNull(EffectiveConfig.of(once).needReduceArgumentOnLoad, "Config.cs:594-595：ONCE 两个分支都不进 ⇒ 静态字段不动")
        val yes = storeWith(ConfigKey.ReduceArgumentOnLoad to e(ConfigEnumKind.REDUCE_ARGUMENT_ON_LOAD, "YES"))
        assertEquals(true, EffectiveConfig.of(yes).needReduceArgumentOnLoad)
    }

    @Test
    fun `夹取同时触发时提示顺序与源码顺序一致`() {
        val store = storeWith(
            ConfigKey.FontSize to i(4),
            ConfigKey.LineHeight to i(5),
            ConfigKey.SaveDataNos to i(5),
            ConfigKey.MaxLog to i(1),
            ConfigKey.TextDrawingMode to e(ConfigEnumKind.TEXT_DRAWING_MODE, "WINAPI"),
        )
        val eff = EffectiveConfig.of(store)
        assertEquals(
            listOf(
                ConfigClampNotice.Kind.TOO_SMALL_FONT_SIZE, // Config.cs:431
                ConfigClampNotice.Kind.LINE_HEIGHT_LESS_THAN_FONT_SIZE, // :436
                ConfigClampNotice.Kind.TOO_SMALL_DISPLAY_SAVE_DATA, // :441
                ConfigClampNotice.Kind.TOO_SMALL_LOG_SIZE, // :451
                ConfigClampNotice.Kind.WINAPI_UNSUPPORTED, // :456
            ),
            eff.notices.map { it.kind },
            "原版是依次弹框，顺序就是源码顺序；本层的 notices 顺序必须与之一致",
        )
        assertEquals(8, eff.fontSize)
        assertEquals(8, eff.lineHeight)
        assertEquals(20, eff.saveDataNos)
        assertEquals(500, eff.maxLog.effective)
        assertEquals("TEXTRENDERER", eff.textDrawingMode)
        assertEquals(2, eff.shapePositionShift)
    }

    @Test
    fun `未定义的枚举数字值不进任何 case`() {
        // 数字串解析成功后存的是数字本身（ConfigEnumKind 类 KDoc 的 dotnet 实测），
        // 于是原版那两个 switch 一个 case 都不进：`Config.cs:413-430` 没有 default 分支，
        // `Config.cs:456` 的 `== WINAPI` 与 `:462` 的 `!= WINAPI` 各判各的。
        val store = storeWith(
            ConfigKey.useLanguage to e(ConfigEnumKind.USE_LANGUAGE, "9"),
            ConfigKey.TextDrawingMode to e(ConfigEnumKind.TEXT_DRAWING_MODE, "9"),
            ConfigKey.ReduceArgumentOnLoad to e(ConfigEnumKind.REDUCE_ARGUMENT_ON_LOAD, "9"),
        )
        val eff = EffectiveConfig.of(store)
        assertNull(eff.language, "UseLanguage 只有 0..3 ⇒ Config.Language 保持 0、setEncode 不被调用（Config.cs:412-430）")
        assertEquals("9", eff.textDrawingMode, "不是 WINAPI ⇒ 既不告警也不改写（Config.cs:456-460）")
        assertEquals(0, eff.notices.size)
        assertEquals(3, eff.shapePositionShift, "`!= WINAPI` 对未定义值同样成立 ⇒ 走 max(2, 18/6)（Config.cs:461-465）")
        assertNull(eff.needReduceArgumentOnLoad, "既不是 YES 也不是 NO ⇒ 两个分支都不进，静态字段不动（Config.cs:594-604）")
    }

    @Test
    fun `写出侧的两条 List 分支只在空列表时分岔`() {
        val store = ConfigStore()
        // 通用分支（ConfigItem.cs:68-80）逗号前置 ⇒ 空列表给出空串
        store.itemOf(ConfigKey.ValidExtension).assign(ConfigValue.StringList(emptyList()))
        assertEquals("", EmueraConfigWriter.valueToString(store.itemOf(ConfigKey.ValidExtension)))
        // ValidExtension 走的是 ConfigData.cs:437-447 的手写分支：末位字符无条件删掉，
        // 空列表时被删掉的是冒号 ⇒ 落盘成一条没有冒号的裸标签（下一轮读回来走 NO_SEPARATOR）
        val line = EmueraConfigWriter.gameLines(store, english = true)
            .single { it.startsWith("VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT") }
        assertEquals("VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT", line)
        // 非空时两条分支逐字符相同（本路 golden 判据 3 的还原里第 73 行就是这一条）
        store.itemOf(ConfigKey.ValidExtension).assign(ConfigValue.StringList(listOf("xml", "txt")))
        assertEquals(
            "VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT:xml,txt",
            EmueraConfigWriter.gameLines(store, english = true)
                .single { it.startsWith("VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT") },
        )
    }

    @Test
    fun `EffectiveConfig 的其余读数直接透传条目`() {
        val store = storeWith(
            ConfigKey.WindowX to i(1800),
            ConfigKey.WindowY to i(973),
            ConfigKey.IgnoreCase to b(false),
            ConfigKey.SystemAllowFullSpace to b(false),
            ConfigKey.FontName to ConfigValue.StringValue("黑体"),
            ConfigKey.ForeColor to ConfigValue.ColorValue(10, 20, 30),
            ConfigKey.BarChar1 to ConfigValue.CharValue('#'),
            ConfigKey.LastKey to ConfigValue.LongValue(7L),
            ConfigKey.ValidExtension to ConfigValue.StringList(listOf("xml", "txt")),
        )
        val eff = EffectiveConfig.of(store)
        assertEquals(1800, eff.windowX)
        assertEquals(973, eff.windowY)
        assertEquals(1797, eff.drawableWidth, "1800 - 3")
        assertFalse(eff.ignoreCase, "Config.cs:299-309 的 StringComparison 由它决定")
        assertFalse(eff.systemAllowFullSpace) // Config.cs:370
        assertEquals("黑体", eff.fontName)
        assertEquals(ConfigValue.ColorValue(10, 20, 30), eff.color(ConfigKey.ForeColor))
        assertEquals('#', eff.char(ConfigKey.BarChar1))
        assertEquals(7L, eff.long(ConfigKey.LastKey))
        assertEquals(listOf("xml", "txt"), eff.stringList(ConfigKey.ValidExtension))
        assertEquals(
            "10,20,30",
            EmueraConfigWriter.valueToString(store.itemOf(ConfigKey.ForeColor)),
            "写出格式 R,G,B（ConfigItem.cs:63-67）",
        )
        assertEquals(5000, eff.int(ConfigKey.InfiniteLoopAlertTime), "未被夹取的条目原样透传")
    }
}
