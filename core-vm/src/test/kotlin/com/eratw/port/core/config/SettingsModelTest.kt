package com.eratw.port.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SettingsModel] 的判据 —— SETTINGS-MODEL 的交付面。
 *
 * 断言的期望值全部指回 C# 出处，不指回本仓的另一份代码：
 *   * 条目数量与分组 —— `setDefault()` 的注册顺序与三个数组（spec: `ConfigData.cs:23-27`、`:45-174`），
 *     `configArray` 100 条 / `debugArray` 7 条 / `replaceArray` 16 条（spec: `:45-151`、`:152-158`、`:159-174`）；
 *   * id 与双语标签 —— `AConfigItem.cs:21-23`（`Name` = 枚举成员名，`Text`/`EngText` 构造期大写化、
 *     源代码里的原文来自 `ConfigData.cs` 的各注册行）；页面文案是另一套（`ConfigPageLabels` 的 Lang 出处）；
 *   * 当前值 / 缺省值 / 生效值 —— 条目值 `ConfigItem.cs:14-27`、缺省 `ConfigData.cs:45-174` 的实参、
 *     生效值 `Config.cs:431-465` 的夹取链；
 *   * set 的校验 —— 控件区间 `ConfigDialog.cs:433-464` 与「区间撑开」`:696-703`、解析口径
 *     `ConfigItem.cs:92-99`、`:119-171`、`:227-250`，原文案 `Lang.cs:958` / `:962` / `:966`；
 *   * 写回 —— `EmueraConfigWriter.mergeInto` 的保留规则与 `ConfigData.cs:433` 的三条不写出规则。
 */
class SettingsModelTest {

    private fun applied(result: SettingsSetResult): SettingsSetResult.Applied {
        assertTrue(result is SettingsSetResult.Applied, "期望写入成功，实际 $result")
        return result as SettingsSetResult.Applied
    }

    private fun rejected(result: SettingsSetResult): SettingsSetResult.Rejected {
        assertTrue(result is SettingsSetResult.Rejected, "期望拒绝写入，实际 $result")
        return result as SettingsSetResult.Rejected
    }

    // -----------------------------------------------------------------
    // 视图形状
    // -----------------------------------------------------------------

    @Test
    fun `条目与分组视图与条目表同量同序`() {
        val model = SettingsModel()
        // 100 + 16 + 7 = 123（ConfigData.cs:45-174 全表）
        assertEquals(123, ConfigSpecTable.all.size)
        assertEquals(ConfigSpecTable.all.size, model.entries.size)
        assertEquals(123, model.entries.map { it.id }.distinct().size, "稳定 id 必须唯一")
        assertEquals("IgnoreCase", model.entries.first().id, "首位 = configArray 第一条（ConfigData.cs:45）")
        assertEquals("DebugWindowPosY", model.entries.last().id, "末位 = debugArray 最后一条（ConfigData.cs:158）")
        // 注册顺序逐条对回 ConfigData.cs 的注册行（45 + 下标 = 那一行）
        assertEquals("SetWindowPos", model.entries[15].id) // ConfigData.cs:60
        assertEquals("FontSize", model.entries[21].id) // :66
        assertEquals("LineHeight", model.entries[22].id) // :67
        assertEquals("ScrollHeight", model.entries[29].id) // :74
        assertEquals("DebugShowWindow", model.entries[116].id) // :152
        assertEquals("DebugWindowPosY", model.entries[122].id) // :158

        // 分组顺序 = setDefault() 的注册顺序（configArray → debugArray → replaceArray）
        assertEquals(
            listOf(ConfigGroup.GAME, ConfigGroup.DEBUG, ConfigGroup.REPLACE),
            model.groups.map { it.group },
        )
        assertEquals(listOf(100, 7, 16), model.groups.map { it.entries.size })
        assertEquals(
            listOf("emuera.config", "debug.config", "_Replace.csv"),
            model.groups.map { it.fileName },
            "ConfigData.cs:33 / :34 / Process.cs:246",
        )
        assertEquals(ConfigSpecTable.gameCount, model.group(ConfigGroup.GAME).entries.size)
        assertEquals(
            model.group(ConfigGroup.GAME).entries.map { it.id },
            model.groups[0].entries.map { it.id },
            "单组视图与分组视图同序",
        )
        // 组员归属不得串组（ConfigData.cs:45-174 三个列表）
        assertTrue(model.group(ConfigGroup.DEBUG).entries.all { it.group === ConfigGroup.DEBUG })
        assertTrue(model.group(ConfigGroup.REPLACE).entries.all { it.group === ConfigGroup.REPLACE })
    }

    @Test
    fun `稳定 id 与英日标签以及页面文案的两套来源`() {
        val model = SettingsModel()
        val font = model.entry("FontSize")!!
        assertEquals("FontSize", font.id, "AConfigItem.Name = ConfigCode 成员名（AConfigItem.cs:21）")
        assertEquals("フォントサイズ", font.jpLabel) // ConfigData.cs:66
        assertEquals("Font size", font.enLabel) // ConfigData.cs:66
        assertEquals("フォントサイズ", font.label(english = false))
        assertEquals("Font size", font.label(english = true))
        assertEquals(ConfigKey.FontSize, font.key)
        assertEquals(ConfigGroup.GAME, font.group)
        assertEquals(SettingsKind.INT, font.kind)
        assertEquals(ConfigValueType.INT, font.valueType)

        // 三形匹配（Name / Text / EngText，ConfigData.cs:262-284）都能定位同一条
        assertEquals(ConfigKey.FontSize, model.entry("FONT SIZE")?.key)
        assertEquals(ConfigKey.FontSize, model.entry("font size")?.key)
        assertEquals(ConfigKey.FontSize, model.entry(" フォントサイズ ")?.key)
        assertNull(model.entry("NOT A CONFIG KEY"))

        // 页面文案与配置文件标签不同源：InfiniteLoopAlertTime 的 Text 带「数」、Lang 不带（ConfigData.cs:75 vs Lang.cs:314）
        val loop = model.entry("InfiniteLoopAlertTime")!!
        assertEquals("無限ループ警告までのミリ秒数", loop.jpLabel)
        assertEquals("無限ループ警告までのミリ秒", loop.pageJaLabel)
        // 未上设置页的九条之一：没有页面文案，但条目本身照样在模型里
        val skip = model.entry("SkipFrame")!!
        assertNull(skip.pageJaLabel)
        assertEquals(ConfigKey.SkipFrame, skip.key)

        // 控件分类的满射：BOOL / INT / STRING / ENUM 四类都能各举一条（ConfigDialog.cs:931 / :1103 / :1081 / :1069）
        assertEquals(SettingsKind.BOOL, model.entry("IgnoreCase")!!.kind)
        assertEquals(SettingsKind.INT, model.entry("LastKey")!!.kind, "long 型按数值框归类")
        assertEquals(SettingsKind.STRING, model.entry("FontName")!!.kind)
        assertEquals(SettingsKind.STRING, model.entry("ForeColor")!!.kind, "颜色框也归 STRING")
        assertEquals(SettingsKind.ENUM, model.entry("useLanguage")!!.kind)
    }

    @Test
    fun `约束覆盖控件区间 枚举常量表与字符长度`() {
        val model = SettingsModel()
        assertEquals(SettingsConstraint.IntBounds(128, 5000), model.entry("WindowX")!!.constraint) // ConfigDialog.cs:435-436
        assertEquals(SettingsConstraint.IntBounds(8, 144), model.entry("FontSize")!!.constraint) // :441-442
        assertEquals(
            SettingsConstraint.None,
            model.entry("SkipFrame")!!.constraint,
            "没有数值框的条目不带区间（ConfigDialog.cs 全表无 SkipFrame 绑定）",
        )
        assertEquals(SettingsConstraint.None, model.entry("FontName")!!.constraint)
        assertEquals(
            SettingsConstraint.Choices(listOf("GRAPHICS", "TEXTRENDERER", "WINAPI")),
            model.entry("TextDrawingMode")!!.constraint,
            "常量表顺序 = C# 枚举序号（TextDrawingMode.cs:3-8）",
        )
        assertEquals(SettingsConstraint.TextLength(1, 1), model.entry("BarChar1")!!.constraint)
        assertTrue(model.entry("StainDefault")!!.constraint is SettingsConstraint.None, "List<long> 没有控件区间")

        // 区间按「控件撑开」口径现算：现值 6000 时上限跟着放到 6000（ConfigDialog.cs:696-703）
        assertTrue(model.store.itemOf(ConfigKey.WindowX).assign(ConfigValue.IntValue(6000)))
        assertEquals(SettingsConstraint.IntBounds(128, 6000), model.entry("WindowX")!!.constraint)
        applied(model.set("WindowX", "6000"))
        assertEquals(6000, model.store.itemOf(ConfigKey.WindowX).int())
    }

    @Test
    fun `当前值 缺省值与生效值三份各自同源`() {
        val store = ConfigStore()
        val model = SettingsModel(store)
        val font = model.entry("FontSize")!!
        assertEquals(ConfigValue.IntValue(18), font.currentValue) // ConfigData.cs:66
        assertEquals(ConfigValue.IntValue(18), font.defaultValue)
        assertEquals(ConfigValue.IntValue(18), font.effectiveValue)

        // 夹取链只改生效值、不回写条目（Config.cs:431-455）
        assertTrue(store.itemOf(ConfigKey.MaxLog).assign(ConfigValue.IntValue(400)))
        val maxLog = model.entry("MaxLog")!!
        assertEquals(ConfigValue.IntValue(400), maxLog.currentValue, "条目原值 = 写回与回显用的那份")
        assertEquals(ConfigValue.IntValue(500), maxLog.effectiveValue, "Config.cs:325 读取 + :451-455 夹到 500")
        assertEquals(ConfigValue.IntValue(5000), maxLog.defaultValue)
        assertEquals(500, model.effectiveConfig().maxLog.effective)
        assertEquals(
            listOf(ConfigClampNotice.Kind.TOO_SMALL_LOG_SIZE),
            model.clampNotices().map { it.kind },
            "提示顺序与 Config.cs:431-460 一致",
        )

        // 未参与夹取的条目三份同值
        val fps = model.entry("FPS")!!
        assertEquals(fps.currentValue, fps.effectiveValue)
        assertEquals(fps.currentValue, fps.defaultValue)

        // 告警强制开（Config.cs:345-352）：条目仍是 NO，生效值是 YES
        assertTrue(store.itemOf(ConfigKey.AllowFunctionOverloading).assign(ConfigValue.Bool(false)))
        val warn = model.entry("WarnFunctionOverloading")!!
        assertEquals(ConfigValue.Bool(true), warn.currentValue, "条目缺省就是 YES（ConfigData.cs:90）")
        assertTrue(store.itemOf(ConfigKey.WarnFunctionOverloading).assign(ConfigValue.Bool(false)))
        assertEquals(ConfigValue.Bool(false), model.entry("WarnFunctionOverloading")!!.currentValue)
        assertEquals(ConfigValue.Bool(true), model.entry("WarnFunctionOverloading")!!.effectiveValue)

        // WINAPI 被改写回 TEXTRENDERER（Config.cs:456-460）
        assertTrue(
            store.itemOf(ConfigKey.TextDrawingMode)
                .assign(ConfigValue.EnumValue(ConfigEnumKind.TEXT_DRAWING_MODE, "WINAPI")),
        )
        assertEquals(
            ConfigValue.EnumValue(ConfigEnumKind.TEXT_DRAWING_MODE, "TEXTRENDERER"),
            model.entry("TextDrawingMode")!!.effectiveValue,
        )
        assertEquals(
            ConfigValue.EnumValue(ConfigEnumKind.TEXT_DRAWING_MODE, "WINAPI"),
            model.entry("TextDrawingMode")!!.currentValue,
        )
    }

    // -----------------------------------------------------------------
    // set 校验
    // -----------------------------------------------------------------

    @Test
    fun `set 按类型与控件区间拒绝非法值`() {
        val model = SettingsModel()

        // 数值框区间（ConfigDialog.cs:435-436）：128..5000
        val tooSmall = rejected(model.set("WindowX", "100"))
        assertEquals(SettingsRejectReason.OUT_OF_RANGE, tooSmall.reason)
        assertEquals("WindowX", tooSmall.id, "拒绝结果回规范 id")
        assertEquals("設定値が範囲外です(128..5000)", tooSmall.message, "本层自定文案，带可用区间")
        assertEquals(760, model.store.itemOf(ConfigKey.WindowX).int(), "拒绝时值不动")

        // 非数字（ConfigItem.cs:127-135 + Lang.cs:962）
        assertEquals("数字でない文字が含まれています", rejected(model.set("WindowX", "abc")).message)
        // 允许的边界值
        applied(model.set("WindowX", "5000"))
        assertEquals(5000, model.store.itemOf(ConfigKey.WindowX).int())
        applied(model.set("WindowX", "128"))

        // 与 Config.SetConfig 夹取文案对齐的四条（Lang.cs:4034 / :4042 / :4046 / :4050）
        assertEquals("フォントサイズが小さすぎます(8が下限)", rejected(model.set("FontSize", "4")).message)
        assertEquals("表示するセーブデータ数が少なすぎます(20が下限)", rejected(model.set("SaveDataNos", "10")).message)
        assertEquals("表示するセーブデータ数が多すぎます(80が上限)", rejected(model.set("SaveDataNos", "200")).message)
        assertEquals("ログ表示行数が少なすぎます(500が下限)", rejected(model.set("MaxLog", "100")).message)

        // 类型不符：勾选框形态给 int 条目、数值框形态给 bool 条目（原版是强转失败，ConfigItem.cs:43-46）
        val boolOnInt = rejected(model.set("FontSize", true))
        assertEquals(SettingsRejectReason.TYPE_MISMATCH, boolOnInt.reason)
        assertEquals("設定項目の型が一致しません", boolOnInt.message)
        assertEquals(SettingsRejectReason.TYPE_MISMATCH, rejected(model.set("IgnoreCase", 1)).reason)

        // 未知 id
        val unknown = rejected(model.set("NoSuchEntry", "1"))
        assertEquals(SettingsRejectReason.UNKNOWN_ID, unknown.reason)
        assertEquals("NoSuchEntry", unknown.id)

        // 未上设置页、无区间的条目照样可写（原版没有这个控件，本层不额外设限）
        applied(model.set("SkipFrame", "0"))
        applied(model.set("SkipFrame", "-3"))
        assertEquals(-3, model.store.itemOf(ConfigKey.SkipFrame).int())
    }

    @Test
    fun `set 的布尔 枚举与字符串解析口径`() {
        val model = SettingsModel()

        // bool：YES/NO/TRUE/FALSE/前/後/整数（ConfigItem.cs:227-250）
        applied(model.set("IgnoreCase", "NO"))
        assertFalse(model.store.itemOf(ConfigKey.IgnoreCase).bool())
        applied(model.set("IgnoreCase", "true"))
        assertTrue(model.store.itemOf(ConfigKey.IgnoreCase).bool())
        applied(model.set("IgnoreCase", "1"))
        assertTrue(model.store.itemOf(ConfigKey.IgnoreCase).bool())
        applied(model.set("IgnoreCase", "後"))
        assertFalse(model.store.itemOf(ConfigKey.IgnoreCase).bool())
        assertEquals("不正な指定です", rejected(model.set("IgnoreCase", "maybe")).message) // Lang.cs:966
        applied(model.set("UseMouse", false))
        assertFalse(model.store.itemOf(ConfigKey.UseMouse).bool())

        // 枚举：常量名大小写不敏感 + 数字串（Enum.TryParse 的形状，ConfigItem.cs:172-211）
        val mode = applied(model.set("TextDrawingMode", "winapi"))
        assertEquals(ConfigValue.EnumValue(ConfigEnumKind.TEXT_DRAWING_MODE, "WINAPI"), mode.value)
        assertEquals(
            ConfigValue.EnumValue(ConfigEnumKind.TEXT_DRAWING_MODE, "TEXTRENDERER"),
            applied(model.set("TextDrawingMode", "1")).value,
        )
        assertEquals(
            ConfigValue.EnumValue(ConfigEnumKind.USE_LANGUAGE, "9"),
            applied(model.set("useLanguage", "9")).value,
            "未定义序号照样解析成功、存数字串（ConfigItem.cs:81 的 ToString 行为）",
        )
        assertEquals("不正な指定です", rejected(model.set("TextDrawingMode", "NOT_A_MODE")).message)

        // string 原样存、不 Trim（设置对话框就是 SetValue(textBox.Text)，ConfigDialog.cs:1081）
        val text = "  C:\\tools\\ed.exe  "
        assertEquals(ConfigValue.StringValue(text), applied(model.set("TextEditor", text)).value)

        // 颜色三段 0..255（ConfigItem.cs:252-274 + Lang.cs:958）
        assertEquals(ConfigValue.ColorValue(10, 20, 30), applied(model.set("ForeColor", "10,20,30")).value)
        assertEquals("値をColor指定子として認識できません", rejected(model.set("ForeColor", "300,0,0")).message)
        assertEquals("値をColor指定子として認識できません", rejected(model.set("ForeColor", "10,20")).message)

        // char 只认单字符（ConfigItem.cs:119-126）
        assertEquals(ConfigValue.CharValue('#'), applied(model.set("BarChar1", "#")).value)
        assertEquals("不正な指定です", rejected(model.set("BarChar1", "##")).message)

        // List<string> 逗号切分并 Trim（ConfigItem.cs:165-171）；空串拒绝（:92-95）
        assertEquals(
            ConfigValue.StringList(listOf("xml", "txt", "json")),
            applied(model.set("ValidExtension", "xml, txt ,json")).value,
        )
        val empty = rejected(model.set("ValidExtension", "   "))
        assertEquals(SettingsRejectReason.EMPTY_TEXT, empty.reason)

        // List<long> 斜杠切分（ConfigItem.cs:145-159）
        assertEquals(
            ConfigValue.LongList(listOf(0L, 0L, 2L, 1L, 8L)),
            applied(model.set("StainDefault", "0/0/2/1/8")).value,
        )
        assertEquals("数字でない文字が含まれています", rejected(model.set("StainDefault", "0/x")).message)
    }

    @Test
    fun `钉住的条目拒绝写入与重置`() {
        val store = ConfigStore()
        val model = SettingsModel(store)
        // fixed.config 那次解析置钉（ConfigData.cs:573-576）
        val loaded = EmueraConfigParser.loadGameFile(
            ConfigFile("fixed.config", listOf("WINDOW WIDTH:800")),
            store,
            fix = true,
        )
        assertEquals(ConfigLineStatus.APPLIED, loaded.records.single().status)
        assertTrue(store.itemOf(ConfigKey.WindowX).fixed)
        assertTrue(model.entry("WindowX")!!.fixed)

        val rejectedResult = rejected(model.set("WindowX", "1024"))
        assertEquals(SettingsRejectReason.FIXED, rejectedResult.reason)
        assertEquals("この項目は固定されているため変更できません", rejectedResult.message)
        assertEquals(800, store.itemOf(ConfigKey.WindowX).int(), "钉住时写入被忽略（ConfigItem.cs:20-27）")
        assertFalse(model.reset("WindowX"), "重置同样写不进")
        assertEquals(800, store.itemOf(ConfigKey.WindowX).int())
    }

    @Test
    fun `reset 与 resetAll 回到缺省值`() {
        val model = SettingsModel()
        applied(model.set("WindowX", "1920"))
        applied(model.set("FontName", "黑体"))
        assertEquals(ConfigValue.StringValue("黑体"), model.entry("FontName")!!.currentValue)

        assertTrue(model.reset("WindowX"))
        assertEquals(ConfigValue.IntValue(760), model.entry("WindowX")!!.currentValue)
        assertFalse(model.reset("NoSuchEntry"))

        assertEquals(123, model.resetAll(), "全表可写（无钉位）⇒ 123 条都写回缺省")
        assertEquals(760, model.store.itemOf(ConfigKey.WindowX).int())
        assertEquals("ＭＳ ゴシック", model.store.itemOf(ConfigKey.FontName).string()) // ConfigData.cs:65
        model.entries.forEach { entry ->
            assertEquals(entry.defaultValue, entry.currentValue, "${entry.id} 应回到缺省")
        }
    }

    @Test
    fun `dirty 标记随修改与回滚变化`() {
        val model = SettingsModel()
        assertFalse(model.dirty, "刚构造时基线就是当前状态")
        applied(model.set("WindowX", "1920"))
        assertTrue(model.dirty)
        model.markClean()
        assertFalse(model.dirty, "写盘成功后基线前移")

        applied(model.set("WindowX", "1024"))
        assertTrue(model.dirty)
        assertEquals(123, model.restore(), "全部条目都写回基线（钉住的除外，此处没有）")
        assertFalse(model.dirty)
        assertEquals(1920, model.store.itemOf(ConfigKey.WindowX).int(), "回滚到 markClean 时的值而不是缺省")

        // 值没变不算脏：写同一个值
        assertTrue(model.set("WindowX", "1920") is SettingsSetResult.Applied)
        assertFalse(model.dirty)
    }

    // -----------------------------------------------------------------
    // 写回
    // -----------------------------------------------------------------

    @Test
    fun `buildMerge 保留注释未知键顺序与行尾且英文开关现算`() {
        val original = "; head\r\nCUSTOM KEY:keep\r\nFONT SIZE:18\r\n; tail\r\n"
        val model = SettingsModel()
        assertFalse(model.englishConfigOutput(), "缺省 NO（ConfigData.cs:129）")
        applied(model.set("FontSize", "22"))

        val jp = model.buildMerge(original)
        val jpLines = jp.split("\r\n")
        assertEquals("; head", jpLines[0], "注释原样（ConfigData.cs:529-532）")
        assertEquals("CUSTOM KEY:keep", jpLines[1], "未知键原样")
        assertEquals("フォントサイズ:22", jpLines[2], "已知键原位替换；english=false 用日文标签（ConfigItem.cs:86）")
        assertEquals("; tail", jpLines[3], "顺序不动")
        assertEquals("大文字小文字の違いを無視する:YES", jpLines[4], "缺失键按注册顺序追加（ConfigData.cs:430）")
        assertTrue(jp.endsWith("\r\n"), "原文以 CRLF 结尾，追加块也以 CRLF 结尾")
        assertFalse(jp.replace("\r\n", "").contains('\n'), "逐行保留行尾 ⇒ 不出现裸 LF")

        // 英文开关现算：改条目的下一轮写回立刻换标签（ConfigItem.cs:86 读静态字段，本层现读条目）
        applied(model.set("EnglishConfigOutput", true))
        assertTrue(model.englishConfigOutput())
        val en = model.buildMerge(original)
        val enLines = en.split("\r\n")
        assertEquals("; head", enLines[0])
        assertEquals("CUSTOM KEY:keep", enLines[1])
        assertEquals("FONT SIZE:22", enLines[2])
        assertEquals("; tail", enLines[3])
        assertEquals("IGNORE CASE:YES", enLines[4])

        // 显式传参优先于条目（shell / 工具链可以强制语言）
        assertTrue(model.buildMerge(original, english = false).contains("フォントサイズ:22"))

        // 空原文 = 首次建文件：整份配置、每行带行尾（ConfigData.cs:429、:447）
        val fresh = model.buildMerge("")
        assertEquals(EmueraConfigWriter.gameLines(model.store, english = true).joinToString("\n") + "\n", fresh)
    }
}
