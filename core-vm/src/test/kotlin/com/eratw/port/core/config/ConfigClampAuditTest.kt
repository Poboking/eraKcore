package com.eratw.port.core.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `n-configaudit` 的第二张对账单 —— **上下界钳位**（CONTRACT-M4-N §2.4 里的「上下界钳位」一项）。
 *
 * 原版有**两套**界限，混起来就会得出错误的分歧：
 *   1. **读取期**：`ConfigItem.TryParse`（`ConfigItem.cs:89-213`）里**没有任何范围检查** ——
 *      `int.TryParse` 只管能不能解析成 `Int32`（`:127-135`）。所以 `MAXLOG:400` 会原样进条目。
 *   2. **生效期**：只有 `Config.SetConfig` 末尾的 5 个 `Dialog.Show` + 1 个 `MessageBox.Show`
 *      会改数（`Config.cs:431-460`），且改的是**静态字段**、**不回写条目**
 *      （`FontSize` / `LineHeight` / `SaveDataNos` / `MaxLog` 的赋值都在 `:434`、`:439`、`:444`、
 *      `:449`、`:454` 这些静态属性上）。于是下次 `SaveConfig`（`ConfigData.cs:430-453` 从条目写文件）
 *      落盘的还是玩家写的越界原值 —— 移植侧用 [EffectiveConfig.source] 与生效字段两个视图复刻这一点。
 *   3. **GUI 值域**：`ConfigDialog` 的 `NumericUpDown` 上还有一份第三套界限（`ConfigDialog.cs:433-464`），
 *      它跟上面两套都不同，而且 `setNumericUpDown`（`:692-706`）还会为容纳现值把界限**撑开**
 *      （`:696-703`）。移植侧把它单独存在 [ConfigSpec.guiIntRange] 里，本文件逐条对账。
 *
 * 另加一条**枚举行钉不住**的读数（`ConfigItem.cs:212` 的 `return flag` 与 5 个枚举分支从不置 `flag`）。
 */
class ConfigClampAuditTest {

    // ------------------------------------------------------------------
    // 生效期钳位：Config.cs:431-460
    // ------------------------------------------------------------------

    @Test
    fun `读取期不夹取且生效期的夹取不回写条目`() {
        val store = ConfigStore()
        store.itemOf(ConfigKey.FontSize).assign(ConfigValue.IntValue(4)) // 低于下限 8（Config.cs:431-435）
        store.itemOf(ConfigKey.LineHeight).assign(ConfigValue.IntValue(5)) // 低于夹过之后的 FontSize（:436-440）
        store.itemOf(ConfigKey.MaxLog).assign(ConfigValue.IntValue(400)) // 低于下限 500（:451-455）
        store.itemOf(ConfigKey.SaveDataNos).assign(ConfigValue.IntValue(5)) // 低于下限 20（:441-445）

        val eff = EffectiveConfig.of(store)
        assertEquals(8, eff.fontSize, "FontSize 夹到 8（Config.cs:434）")
        assertEquals(8, eff.lineHeight, "LineHeight 与夹过之后的 FontSize 比，所以是 8 不是 5（Config.cs:436-440）")
        assertEquals(20, eff.saveDataNos, "SaveDataNos 夹到 20（Config.cs:444）")
        assertEquals(500, eff.maxLog.effective, "MaxLog 抬到 500（Config.cs:454）")
        assertEquals(400, eff.maxLog.requested, "原值另存一份（下次写出仍是 400）")
        assertTrue(eff.maxLog.raisedToFloor)

        // 条目侧一个字都没动 —— 原版夹取只改静态字段（Config.cs:431-455），SaveConfig 从条目写（ConfigData.cs:430-453）。
        assertEquals(4, store.itemOf(ConfigKey.FontSize).int(), "回写就等于修好官方行为（ADR-005 禁止）")
        assertEquals(5, store.itemOf(ConfigKey.LineHeight).int())
        assertEquals(400, store.itemOf(ConfigKey.MaxLog).int())
        assertEquals(5, store.itemOf(ConfigKey.SaveDataNos).int())
        assertEquals(4, eff.source.itemOf(ConfigKey.FontSize).int(), "source 视图必须还是原值")
    }

    @Test
    fun `夹取提示的种类与顺序与原版弹框顺序一致`() {
        val store = ConfigStore()
        store.itemOf(ConfigKey.FontSize).assign(ConfigValue.IntValue(4))
        store.itemOf(ConfigKey.LineHeight).assign(ConfigValue.IntValue(5))
        store.itemOf(ConfigKey.SaveDataNos).assign(ConfigValue.IntValue(5))
        store.itemOf(ConfigKey.MaxLog).assign(ConfigValue.IntValue(400))
        store.itemOf(ConfigKey.TextDrawingMode)
            .assign(ConfigValue.EnumValue(ConfigEnumKind.TEXT_DRAWING_MODE, "WINAPI"))

        val notices = EffectiveConfig.of(store).notices
        assertEquals(
            listOf(
                ConfigClampNotice.Kind.TOO_SMALL_FONT_SIZE, // Config.cs:431-435
                ConfigClampNotice.Kind.LINE_HEIGHT_LESS_THAN_FONT_SIZE, // :436-440
                ConfigClampNotice.Kind.TOO_SMALL_DISPLAY_SAVE_DATA, // :441-445
                ConfigClampNotice.Kind.TOO_SMALL_LOG_SIZE, // :451-455
                ConfigClampNotice.Kind.WINAPI_UNSUPPORTED, // :456-460（排在最后）
            ),
            notices.map { it.kind },
        )
        // 5 个 Dialog.Show 都带标题「設定のエラー」（Lang.cs:4030），MessageBox.Show 那条没有标题（Config.cs:458）。
        assertEquals(listOf("設定のエラー", "設定のエラー", "設定のエラー", "設定のエラー", null), notices.map { it.titleJp })
        assertEquals("5", notices[2].requested)
        assertEquals("20", notices[2].effective)
        assertEquals("WINAPI", notices[4].requested)
        assertEquals("TEXTRENDERER", notices[4].effective)
        assertNull(notices[4].titleJp, "MessageBox.Show 只传了正文（Config.cs:458）")
    }

    @Test
    fun `SaveDataNos 的上限与 WINAPI 改写后的派生量`() {
        val store = ConfigStore()
        store.itemOf(ConfigKey.SaveDataNos).assign(ConfigValue.IntValue(200)) // 高于上限 80（Config.cs:446-450）
        val eff = EffectiveConfig.of(store)
        assertEquals(80, eff.saveDataNos)
        assertEquals(listOf(ConfigClampNotice.Kind.TOO_LARGE_DISPLAY_SAVE_DATA), eff.notices.map { it.kind })

        // Config.cs:461-466：先赋 0、再判 != WINAPI 才赋 max(2, FontSize/6)；
        // 而 :456-460 已把 WINAPI 改掉 ⇒ 条件恒真 ⇒ 死分支，值恒等于 max(2, FontSize/6)（整除）。
        val normal = EffectiveConfig.of(ConfigStore())
        assertEquals(3, normal.shapePositionShift, "缺省 FontSize 18 ⇒ max(2, 18/6) = 3（Config.cs:464）")
        assertEquals(757, normal.drawableWidth, "DrawableWidth = WindowX - shift = 760 - 3（Config.cs:466）")

        val winapi = ConfigStore()
        winapi.itemOf(ConfigKey.TextDrawingMode)
            .assign(ConfigValue.EnumValue(ConfigEnumKind.TEXT_DRAWING_MODE, "WINAPI"))
        val effWinapi = EffectiveConfig.of(winapi)
        assertEquals("TEXTRENDERER", effWinapi.textDrawingMode, "改写发生在派生量之前（Config.cs:456-460）")
        assertEquals("WINAPI", winapi.itemOf(ConfigKey.TextDrawingMode).enumConstant(), "条目里仍是 WINAPI（不回写）")
    }

    @Test
    fun `不允许函数覆盖时告警被强制打开但条目不动`() {
        // Config.cs:344-352：false ⇒ 静态字段 WarnFunctionOverloading 恒 true，条目里的 NO 原样保留，
        // 于是「不允许覆盖 ⇒ 必出覆盖告警」这条从配置文件里关不掉。
        val store = ConfigStore()
        store.itemOf(ConfigKey.AllowFunctionOverloading).assign(ConfigValue.Bool(false))
        store.itemOf(ConfigKey.WarnFunctionOverloading).assign(ConfigValue.Bool(false))
        val eff = EffectiveConfig.of(store)
        assertTrue(eff.warnFunctionOverloading, "静态字段被强制为 true（Config.cs:347）")
        assertEquals(false, store.itemOf(ConfigKey.WarnFunctionOverloading).bool(), "条目不回写 ⇒ 下次落盘还是 NO")

        val def = EffectiveConfig.of(ConfigStore())
        assertTrue(def.warnFunctionOverloading, "缺省 AllowFunctionOverloading 是 YES（ConfigData.cs:89），走 Config.cs:351")
    }

    // ------------------------------------------------------------------
    // GUI 值域：ConfigDialog.cs:433-464 与 setNumericUpDown 的控件到条目映射
    // ------------------------------------------------------------------

    /** 一行 = 一个 `NumericUpDown`：控件界限（`:433-464`）+ 绑定的条目（`:760-772`、`:916-919`）。 */
    private data class GuiRow(val key: ConfigKey, val range: IntRange, val minLine: Int, val maxLine: Int, val bindLine: Int)

    private val guiRows: List<GuiRow> = listOf(
        GuiRow(ConfigKey.PrintCPerLine, 1..100, 433, 434, 763), // numericUpDown1
        GuiRow(ConfigKey.WindowX, 128..5000, 435, 436, 760), // numericUpDown2
        GuiRow(ConfigKey.WindowY, 128..5000, 437, 438, 761), // numericUpDown3
        GuiRow(ConfigKey.MaxLog, 500..1000000, 439, 440, 762), // numericUpDown4
        GuiRow(ConfigKey.FontSize, 8..144, 441, 442, 768), // numericUpDown5
        GuiRow(ConfigKey.LineHeight, 8..144, 443, 444, 765), // numericUpDown6
        GuiRow(ConfigKey.FPS, 1..240, 445, 446, 766), // numericUpDown7
        GuiRow(ConfigKey.ScrollHeight, 1..10, 447, 448, 767), // numericUpDown8
        GuiRow(ConfigKey.PrintCLength, 1..100, 449, 450, 764), // numericUpDown9
        GuiRow(ConfigKey.InfiniteLoopAlertTime, 0..100000, 451, 452, 769), // numericUpDown10
        GuiRow(ConfigKey.SaveDataNos, 20..80, 453, 454, 770), // numericUpDown11
        // numericUpDownPosX / posY 只显式设了 Maximum（:455-456），Minimum 取 NumericUpDown 的框架默认 0。
        // TODO(verify): 该默认值来自 System.Windows.Forms.NumericUpDown.Minimum（框架侧无行号可引）。
        GuiRow(ConfigKey.WindowPosX, 0..10000, 0, 455, 771),
        GuiRow(ConfigKey.WindowPosY, 0..10000, 0, 456, 772),
        GuiRow(ConfigKey.CBMaxCB, 1..200, 457, 458, 916),
        GuiRow(ConfigKey.CBBufferSize, 50..5000, 459, 460, 917),
        GuiRow(ConfigKey.CBScrollCount, 1..100, 461, 462, 918),
        GuiRow(ConfigKey.CBMinTimer, 300..60000, 463, 464, 919),
    )

    @Test
    fun `GUI 值域逐条对拍且只有这 17 个条目有值域`() {
        val mismatches = ArrayList<String>()
        for (row in guiRows) {
            val spec = ConfigSpecTable.specOf(row.key)
            if (spec == null) {
                mismatches += "${row.key} 在移植侧表里查不到（ConfigDialog.cs:${row.bindLine} 绑的条目）"
                continue
            }
            if (spec.guiIntRange != row.range) {
                mismatches += "${row.key} 值域 期望 ${row.range} 实际 ${spec.guiIntRange}（ConfigDialog.cs:${row.minLine}-${row.maxLine}）"
            }
        }
        assertEquals(emptyList<String>(), mismatches, "GUI 值域分歧清单（空 = 17/17 全对）")

        // 反向：表里凡是给了 guiIntRange 的条目，必须都能在这份绑定名单里找到。
        val withRange = ConfigSpecTable.all.filter { it.guiIntRange != null }.map { it.key }.toSet()
        assertEquals(guiRows.map { it.key }.toSet(), withRange, "绑定名单外的条目不该有值域（ConfigDialog.cs:760-772、:916-919）")
        assertEquals(17, guiRows.size)
    }

    @Test
    fun `没有 NumericUpDown 绑定的整型条目在移植侧也没有值域`() {
        // SkipFrame（ConfigData.cs:73）与 DisplayWarningLevel（:76）都没有 NumericUpDown 绑定：
        // 前者在 reference 里除注册行与枚举声明外零命中（死条目，CFG-A5），
        // 后者走下拉框 —— 读为 ConfigItem<int>（ConfigDialog.cs:817）、写自 comboBox5.SelectedIndex（:1020）。
        for (key in listOf(ConfigKey.SkipFrame, ConfigKey.DisplayWarningLevel)) {
            assertNull(ConfigSpecTable.specOf(key)?.guiIntRange, "$key 没有 setNumericUpDown 绑定")
        }
        // DEBUG / REPLACE 两组一条也没有绑定（ConfigDialog.cs:760-772、:916-919 里没有这两组的条目）。
        assertEquals(
            emptyList<ConfigKey>(),
            ConfigSpecTable.all.filter { it.group !== ConfigGroup.GAME && it.guiIntRange != null }.map { it.key },
        )
    }

    // ------------------------------------------------------------------
    // 解析期：唯一「范围」形态的读数边界是 Color 的 0..255
    // ------------------------------------------------------------------

    @Test
    fun `解析期只有 Color 带范围检查`() {
        // ConfigItem.cs:252-274 的 tryStringsToColor 对三个分量各判 0..255（`:260`、`:264`、`:268`）；
        // 除此之外 TryParse（:89-213）没有任何配置界限判定。
        val item = ConfigItem(requireNotNull(ConfigSpecTable.specOf(ConfigKey.ForeColor)))
        val out = item.tryParse("256,0,0", 1)
        assertTrue(out is TryParseOutcome.Failed, "越界的 Color 分量在解析期就失败（ConfigItem.cs:113-116 抛 CodeEE）")
        assertEquals(ConfigParseException.LangKey.NOT_EXIST_COLOR_SPECIFIER, (out as TryParseOutcome.Failed).error.langKey)
        assertEquals(192, item.color().r, "抛错 ⇒ 旧值（缺省 192,192,192）原样保留（ConfigData.cs:579-586 只告警不改值）")
        // 只取前三段，后面多余的段被忽略（ConfigItem.cs:260-270 只解析 array[0..2]）。
        assertTrue(item.tryParse("1,2,3,4", 1) is TryParseOutcome.Applied)
        assertEquals(ConfigValue.ColorValue(1, 2, 3), item.color())
    }

    @Test
    fun `整型条目在读取期不设上下限`() {
        val maxLog = ConfigItem(requireNotNull(ConfigSpecTable.specOf(ConfigKey.MaxLog)))
        assertTrue(maxLog.tryParse("-2147483648", 1) is TryParseOutcome.Applied, "负数照收（ConfigItem.cs:127-135 只判能否解析成 Int32）")
        assertEquals(-2147483648, maxLog.int())
        // 超 Int32 才是「解析失败」，不是「钳到上限」：C# 的 int.TryParse 溢出即 false（:129）⇒ 抛错（:132）。
        assertTrue(maxLog.tryParse("3000000000", 1) is TryParseOutcome.Failed)
        assertEquals(-2147483648, maxLog.int(), "失败的行不留任何改动")

        // 于是「500 的下限」只出现在生效期：条目是负数，读数却是 500（Config.cs:451-455）。
        val store = ConfigStore()
        store.itemOf(ConfigKey.MaxLog).assign(ConfigValue.IntValue(-2147483648))
        val eff = EffectiveConfig.of(store)
        assertEquals(500, eff.maxLog.effective)
        assertEquals(-2147483648, store.itemOf(ConfigKey.MaxLog).int())
    }

    @Test
    fun `五个枚举型条目的解析成功位为 false 故 fixed 钉不住`() {
        // ConfigItem.cs:212 的 return flag；flag 只在 bool/char/int/long/串/串表分支里被置
        // （:104、:112、:121、:129、:138、:149-152、:162、:167），5 个枚举分支（:172-211）从不碰它
        // ⇒ ConfigData.cs:573 的 `TryParse(...) && fix` 恒 false ⇒ 枚举行改了值但不置钉。
        val enumKinds = listOf(
            ConfigValueType.TEXT_DRAWING_MODE_ENUM,
            ConfigValueType.REDUCE_ARGUMENT_ENUM,
            ConfigValueType.DISPLAY_WARNING_ENUM,
            ConfigValueType.USE_LANGUAGE_ENUM,
            ConfigValueType.TEXT_EDITOR_TYPE_ENUM,
        )
        assertTrue(enumKinds.none { it.tryParseReturnsTrue }, "ConfigItem.cs:172-211 五处都不置 flag")
        assertTrue(ConfigValueType.entries.filterNot { it in enumKinds }.all { it.tryParseReturnsTrue }, "其余分支置 flag")
        assertEquals(
            enumKinds.toSet(),
            ConfigSpecTable.all.filter { !it.valueType.tryParseReturnsTrue }.map { it.valueType }.toSet(),
            "表里恰好这 5 类条目享受「钉不住」待遇",
        )
    }
}
