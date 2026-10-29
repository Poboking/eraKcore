package com.eratw.port.core.config

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.ConfigMethods
import java.util.Locale
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `n-configaudit` —— **出厂配置表逐条对拍**（CONTRACT-M4-N §2.4）。
 *
 * 本文件不测装载链（那是 `ConfigChainTest` / `ConfigChainWireTest` 的面），只按**键**逐条核对
 * 移植侧 `ConfigSpecTable` 与原版 `ConfigData.setDefault()`（
 * `reference/emuera-cs/MinorShift.Emuera.Runtime.Config/ConfigData.cs:43-175`）的四件事：
 * 键标识与注册顺序、类型（C# 泛型实参）、缺省值（构造的第三个实参）、命中形态（别名/大小写）。
 * 另加 `GETCONFIG` / `GETCONFIGS` 的取数路径（`ConfigData.cs:350-422` 的逐分支）。
 *
 * ## 三组条目的条数（本路 golden 分母）
 * `configArray` = 100、`debugArray` = 7、`replaceArray` = 16，共 123；
 * 出处是注册行的行段：`ConfigData.cs:45-151`（107 行，其中 `ValidExtension` 一项被
 * 反编译拆成 `:120-127` 八行 ⇒ 107-7 = 100 条）、`:152-158`（7 条）、`:159-174`（16 条）。
 *
 * ## 三条必须复核而不是照抄的原版事实（复核结论写在各用例里）
 *   * `ConfigData.cs:262-283` —— `GetConfigItem(string)` 把键 `ToUpper` 后与 `Name` 比，
 *     而 `Name` 是**没大写化**的枚举成员名（`AConfigItem.cs:21`），于是 `WINDOWX` 这类
 *     混合大小写条目的枚举名恒查不到；但**不是恒不成立** —— 全大写的枚举名能命中，
 *     整个 `ConfigCode`（`ConfigCode.cs:5-128`）里只有 `FPS`（`:28`）是全大写。见 [只有全大写的枚举名能经名字分支命中]。
 *   * `ConfigItem.cs:81` —— 落到 `val.ToString()` 的 List 型只有 `List` 泛型实参为
 *     `Int64` 的那三个条目（`ConfigData.cs:169`、`:171-172`），回吐的是
 *     `System.Collections.Generic.List`1[System.Int64]`，**不是契约里写的 `[System.Int32]`**；
 *     `List<string>`（`ConfigData.cs:127`）有 `:68-80` 的专属逗号拼接分支，不吃 `:81`。
 *     见 [GETCONFIGS 的 List 型回显形态（分歧编号 CFG-A2）]。
 *   * 契约里的 `SET CONFIG` —— 原版**没有**这条：全 `reference/emuera-cs` grep
 *     `SETCONFIG` / `SET CONFIG` 零命中，`FunctionMethodCreator.cs:9148-9149` 只注册
 *     `GETCONFIG`（`typeisInt: true`）与 `GETCONFIGS`（`typeisInt: false`）两个函数名。
 *     写侧只有配置文件读取链与设置对话框，登记为 CFG-A4。
 *
 * 官方 bug 一律原样复刻、不顺手修（ADR-005）。
 */
class ConfigKeyAuditTest {

    // ------------------------------------------------------------------
    // 对账表本体
    // ------------------------------------------------------------------

    /**
     * 一行 = 原版 `setDefault()` 的一条 `Add(...)`。
     *
     * @param csLine `ConfigData.cs` 的注册行号（`ValidExtension` 取该项的最后一行 `:127`）
     * @param type C# 泛型实参 `ConfigItem<T>` 的 `T`（枚举型按 `ConfigValueType` 的五个 `*_ENUM` 细分）
     * @param expected 缺省值的**渲染形态**，直接来自那一行的实参：
     *   `YES`/`NO`（`ConfigItem.cs:55-62`）、`r,g,b`（`:63-67`）、逗号连接的串表（`:68-80`）、
     *   `L#` 前缀加逗号连接的长整型表（原版没有写出侧，见 [render] 的说明）、其余是 `val.ToString()`
     *   （`:81`）的字面量形状。
     */
    private data class AuditRow(
        val key: ConfigKey,
        val csLine: Int,
        val type: ConfigValueType,
        val expected: String,
    )

    private val audit: List<AuditRow> = listOf(
        // ---- configArray（ConfigData.cs:45-151）----
        AuditRow(ConfigKey.IgnoreCase, 45, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.UseRenameFile, 46, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.UseReplaceFile, 47, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.UseMouse, 48, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.UseMenu, 49, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.UseDebugCommand, 50, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.AllowMultipleInstances, 51, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.AutoSave, 52, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.UseKeyMacro, 53, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.SizableWindow, 54, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.TextDrawingMode, 55, ConfigValueType.TEXT_DRAWING_MODE_ENUM, "TEXTRENDERER"),
        AuditRow(ConfigKey.WindowX, 56, ConfigValueType.INT, "760"),
        AuditRow(ConfigKey.WindowY, 57, ConfigValueType.INT, "480"),
        AuditRow(ConfigKey.WindowPosX, 58, ConfigValueType.INT, "0"),
        AuditRow(ConfigKey.WindowPosY, 59, ConfigValueType.INT, "0"),
        AuditRow(ConfigKey.SetWindowPos, 60, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.WindowMaximixed, 61, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.MaxLog, 62, ConfigValueType.INT, "5000"),
        AuditRow(ConfigKey.PrintCPerLine, 63, ConfigValueType.INT, "3"),
        AuditRow(ConfigKey.PrintCLength, 64, ConfigValueType.INT, "25"),
        AuditRow(ConfigKey.FontName, 65, ConfigValueType.STRING, "ＭＳ ゴシック"),
        AuditRow(ConfigKey.FontSize, 66, ConfigValueType.INT, "18"),
        AuditRow(ConfigKey.LineHeight, 67, ConfigValueType.INT, "19"),
        AuditRow(ConfigKey.ForeColor, 68, ConfigValueType.COLOR, "192,192,192"),
        AuditRow(ConfigKey.BackColor, 69, ConfigValueType.COLOR, "0,0,0"),
        AuditRow(ConfigKey.FocusColor, 70, ConfigValueType.COLOR, "255,255,0"),
        AuditRow(ConfigKey.LogColor, 71, ConfigValueType.COLOR, "192,192,192"),
        AuditRow(ConfigKey.FPS, 72, ConfigValueType.INT, "5"),
        AuditRow(ConfigKey.SkipFrame, 73, ConfigValueType.INT, "3"),
        AuditRow(ConfigKey.ScrollHeight, 74, ConfigValueType.INT, "1"),
        AuditRow(ConfigKey.InfiniteLoopAlertTime, 75, ConfigValueType.INT, "5000"),
        AuditRow(ConfigKey.DisplayWarningLevel, 76, ConfigValueType.INT, "1"),
        AuditRow(ConfigKey.DisplayReport, 77, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.ReduceArgumentOnLoad, 78, ConfigValueType.REDUCE_ARGUMENT_ENUM, "NO"),
        AuditRow(ConfigKey.IgnoreUncalledFunction, 79, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.FunctionNotFoundWarning, 80, ConfigValueType.DISPLAY_WARNING_ENUM, "IGNORE"),
        AuditRow(ConfigKey.FunctionNotCalledWarning, 81, ConfigValueType.DISPLAY_WARNING_ENUM, "IGNORE"),
        AuditRow(ConfigKey.ChangeMasterNameIfDebug, 82, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.ButtonWrap, 83, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.SearchSubdirectory, 84, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.SortWithFilename, 85, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.LastKey, 86, ConfigValueType.LONG, "0"),
        AuditRow(ConfigKey.SaveDataNos, 87, ConfigValueType.INT, "20"),
        AuditRow(ConfigKey.WarnBackCompatibility, 88, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.AllowFunctionOverloading, 89, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.WarnFunctionOverloading, 90, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.TextEditor, 91, ConfigValueType.STRING, "notepad"),
        AuditRow(ConfigKey.EditorType, 92, ConfigValueType.TEXT_EDITOR_TYPE_ENUM, "USER_SETTING"),
        AuditRow(ConfigKey.EditorArgument, 93, ConfigValueType.STRING, ""),
        AuditRow(ConfigKey.WarnNormalFunctionOverloading, 94, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CompatiErrorLine, 95, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CompatiCALLNAME, 96, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.UseSaveFolder, 97, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CompatiRAND, 98, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CompatiDRAWLINE, 99, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CompatiFunctionNoignoreCase, 100, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.SystemAllowFullSpace, 101, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.CompatiLinefeedAs1739, 102, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.useLanguage, 103, ConfigValueType.USE_LANGUAGE_ENUM, "JAPANESE"),
        AuditRow(ConfigKey.AllowLongInputByMouse, 104, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CompatiCallEvent, 105, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CompatiSPChara, 106, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.SystemSaveInBinary, 107, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CompatiFuncArgOptional, 108, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CompatiFuncArgAutoConvert, 109, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.SystemIgnoreTripleSymbol, 110, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.TimesNotRigorousCalculation, 111, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.SystemNoTarget, 112, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.SystemIgnoreStringSet, 113, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.UseLazyLoading, 114, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.ForbidUpdateCheck, 115, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.UseERD, 116, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.VarsizeDimConfig, 117, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CheckDuplicateIdentifier, 118, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.ReplaceContinuationBR, 119, ConfigValueType.STRING, "\" \""),
        AuditRow(ConfigKey.ValidExtension, 127, ConfigValueType.STRING_LIST, "txt"),
        AuditRow(ConfigKey.ZipSaveData, 128, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.EnglishConfigOutput, 129, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.EmueraLang, 130, ConfigValueType.STRING, ""),
        AuditRow(ConfigKey.EmueraIcon, 131, ConfigValueType.STRING, ""),
        AuditRow(ConfigKey.CBUseClipboard, 132, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CBIgnoreTags, 133, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CBReplaceTags, 134, ConfigValueType.STRING, "."),
        AuditRow(ConfigKey.CBNewLinesOnly, 135, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.CBClearBuffer, 136, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CBTriggerLeftClick, 137, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.CBTriggerMiddleClick, 138, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CBTriggerDoubleLeftClick, 139, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CBTriggerAnyKeyWait, 140, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.CBTriggerInputWait, 141, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.CBMaxCB, 142, ConfigValueType.INT, "25"),
        AuditRow(ConfigKey.CBBufferSize, 143, ConfigValueType.INT, "300"),
        AuditRow(ConfigKey.CBScrollCount, 144, ConfigValueType.INT, "5"),
        AuditRow(ConfigKey.CBMinTimer, 145, ConfigValueType.INT, "800"),
        AuditRow(ConfigKey.RikaiEnabled, 146, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.RikaiFilename, 147, ConfigValueType.STRING, "Emuera-Rikai-edict.txt-eucjp"),
        AuditRow(ConfigKey.RikaiColorBack, 148, ConfigValueType.COLOR, "0,0,139"),
        AuditRow(ConfigKey.RikaiColorText, 149, ConfigValueType.COLOR, "255,255,255"),
        AuditRow(ConfigKey.RikaiUseSeparateBoxes, 150, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.Ctrl_Z_Enabled, 151, ConfigValueType.BOOL, "NO"),
        // ---- replaceArray（ConfigData.cs:159-174）----
        AuditRow(ConfigKey.MoneyLabel, 159, ConfigValueType.STRING, "\u0024"),
        AuditRow(ConfigKey.MoneyFirst, 160, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.LoadLabel, 161, ConfigValueType.STRING, "Now Loading..."),
        AuditRow(ConfigKey.MaxShopItem, 162, ConfigValueType.INT, "100"),
        AuditRow(ConfigKey.DrawLineString, 163, ConfigValueType.STRING, "-"),
        AuditRow(ConfigKey.BarChar1, 164, ConfigValueType.CHAR, "*"),
        AuditRow(ConfigKey.BarChar2, 165, ConfigValueType.CHAR, "."),
        AuditRow(ConfigKey.TitleMenuString0, 166, ConfigValueType.STRING, "最初からはじめる"),
        AuditRow(ConfigKey.TitleMenuString1, 167, ConfigValueType.STRING, "ロードしてはじめる"),
        AuditRow(ConfigKey.ComAbleDefault, 168, ConfigValueType.INT, "1"),
        AuditRow(ConfigKey.StainDefault, 169, ConfigValueType.LONG_LIST, "L#0,0,2,1,8"),
        AuditRow(ConfigKey.TimeupLabel, 170, ConfigValueType.STRING, "時間切れ"),
        AuditRow(ConfigKey.ExpLvDef, 171, ConfigValueType.LONG_LIST, "L#0,1,4,20,50,200"),
        AuditRow(ConfigKey.PalamLvDef, 172, ConfigValueType.LONG_LIST, "L#0,100,500,3000,10000,30000,60000,100000,150000,250000"),
        AuditRow(ConfigKey.pbandDef, 173, ConfigValueType.LONG, "4"),
        AuditRow(ConfigKey.RelationDef, 174, ConfigValueType.LONG, "0"),
        // ---- debugArray（ConfigData.cs:152-158）----
        AuditRow(ConfigKey.DebugShowWindow, 152, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.DebugWindowTopMost, 153, ConfigValueType.BOOL, "YES"),
        AuditRow(ConfigKey.DebugWindowWidth, 154, ConfigValueType.INT, "400"),
        AuditRow(ConfigKey.DebugWindowHeight, 155, ConfigValueType.INT, "300"),
        AuditRow(ConfigKey.DebugSetWindowPos, 156, ConfigValueType.BOOL, "NO"),
        AuditRow(ConfigKey.DebugWindowPosX, 157, ConfigValueType.INT, "0"),
        AuditRow(ConfigKey.DebugWindowPosY, 158, ConfigValueType.INT, "0"),
    )

    @Test
    fun `出厂表三组条数与原版三个数组的注册行段一致`() {
        // ConfigData.cs:45-151 ⇒ 100 条；:159-174 ⇒ 16 条；:152-158 ⇒ 7 条。
        assertEquals(100, ConfigSpecTable.gameCount, "configArray 条数（ConfigData.cs:45-151，ValidExtension 占 :120-127 八行）")
        assertEquals(16, ConfigSpecTable.replaceCount, "replaceArray 条数（ConfigData.cs:159-174）")
        assertEquals(7, ConfigSpecTable.debugCount, "debugArray 条数（ConfigData.cs:152-158）")
        assertEquals(123, ConfigSpecTable.all.size, "三组合计")
        assertEquals(123, audit.size, "对账表自身必须逐条覆盖，少一行就是本路没对到")
    }

    @Test
    fun `出厂表的顺序就是原版查找链的三段顺序`() {
        // ConfigData.cs:236-248 的 GetItem(string) 串联顺序是 config → replace → debug；
        // 移植侧 all 按这个顺序拼（ConfigSpec.kt:240），与 setDefault 的注册顺序（debug 在 replace 之前）不同。
        assertEquals(audit.map { it.key }, ConfigSpecTable.all.map { it.key }, "键的顺序（含三段拼接顺序）")

        // 每一段内部必须按注册行升序：SaveConfig 是按 configArray 的下标顺序遍历写出的（ConfigData.cs:430-453）。
        for (group in ConfigGroup.entries) {
            val lines = auditLinesOf(group)
            assertTrue(lines == lines.sorted(), "$group 组内的注册行必须升序（否则写出顺序会漂，ConfigData.cs:430）")
            assertEquals(ConfigSpecTable.specsOf(group).size, lines.size, "$group 组的对账行数与表条数不符")
        }
        assertEquals(123, ConfigGroup.entries.sumOf { auditLinesOf(it).size })
    }

    @Test
    fun `逐条对拍键的类型与缺省值`() {
        val mismatches = ArrayList<String>()
        for (row in audit) {
            val spec = ConfigSpecTable.specOf(row.key)
            if (spec == null) {
                mismatches += "${row.key} ConfigData.cs:${row.csLine} 在移植侧表里查不到"
                continue
            }
            if (spec.valueType != row.type) {
                mismatches += "${row.key} ConfigData.cs:${row.csLine} 类型 期望 ${row.type} 实际 ${spec.valueType}"
            }
            val actual = render(spec.defaultValue)
            if (actual != row.expected) {
                mismatches += "${row.key} ConfigData.cs:${row.csLine} 默认值 期望 ${row.expected} 实际 $actual"
            }
        }
        assertEquals(emptyList<String>(), mismatches, "出厂表逐条对账分歧清单（空 = 123/123 全对）")
    }

    @Test
    fun `ConfigCode 枚举镜像完整且只有 UseImageBuffer 没有条目`() {
        // ConfigCode.cs:5-128 共 124 个成员；setDefault 只注册 123 条 ⇒ UseImageBuffer 是唯一的死枚举值。
        assertEquals(124, ConfigKey.entries.size, "ConfigCode.cs:5-128 共 124 个成员")
        assertEquals(listOf(ConfigKey.UseImageBuffer), ConfigKey.entries.filter { ConfigSpecTable.specOf(it) == null },
            "ConfigData.cs:45-174 全表没有 UseImageBuffer 的注册行（ConfigCode.cs:15）")
        assertEquals(123, ConfigKey.entries.mapNotNull { ConfigSpecTable.specOf(it) }.size)

        // 显式赋值逐条对齐：29 与 33 是原版就有的空档，EditorType 是 99 且插在 48 与 49 之间。
        assertNull(ConfigKey.fromCode(29), "ConfigCode.cs 没有 29（IgnoreUncalledFunction=30 之前的空档）")
        assertNull(ConfigKey.fromCode(33), "ConfigCode.cs 没有 33（FunctionNotCalledWarning=32 与 ChangeMasterNameIfDebug=34 之间的空档）")
        assertEquals(ConfigKey.EditorType, ConfigKey.fromCode(99), "ConfigCode.cs:52 EditorType = 99")
        assertEquals(ConfigKey.EditorArgument, ConfigKey.fromCode(49), "ConfigCode.cs:53 EditorArgument = 49（声明在 EditorType 之后）")
        assertEquals(ConfigKey.FPS, ConfigKey.fromCode(23), "ConfigCode.cs:28 FPS = 23")
        assertEquals(ConfigKey.CBMinTimer, ConfigKey.fromCode(272), "ConfigCode.cs:128 CBMinTimer = 272（末位）")
        assertEquals(ConfigKey.entries.size, ConfigKey.entries.map { it.code }.distinct().size, "镜像表不许出现重复 code")
    }

    // ------------------------------------------------------------------
    // 命中形态：别名 / 大小写
    // ------------------------------------------------------------------

    @Test
    fun `只有全大写的枚举名能经名字分支命中（分歧编号 CFG-A1）`() {
        // AConfigItem.cs:21 存的 Name 没有大写化，而 ConfigData.cs:264 把读到的键 ToUpper 了，
        // 所以 :269 的 Name 分支只对「枚举名本身就是全大写」的成员成立。
        val allCaps = ConfigKey.entries.filter { it.name == it.name.uppercase(Locale.ROOT) }
        assertEquals(listOf(ConfigKey.FPS), allCaps, "ConfigCode.cs:5-128 里全大写的成员只有 FPS（:28）")

        val store = ConfigStore()
        assertSame(ConfigKey.FPS, store.resolveAny("FPS")?.spec?.key, "Name 分支命中（ConfigData.cs:269）")
        assertSame(ConfigKey.FPS, store.resolveAny("fps")?.spec?.key, "同一形态：键在查表前就被 ToUpper（:264）")
        assertSame(ConfigKey.UseMouse, store.resolveAny("USE MOUSE")?.spec?.key, "EngText 分支命中（ConfigData.cs:277）")
        assertSame(ConfigKey.UseMouse, store.resolveAny("use mouse")?.spec?.key, "标签匹配与调用方大小写无关")
        assertSame(ConfigKey.WindowX, store.resolveAny("ウィンドウ幅")?.spec?.key, "Text 分支命中（ConfigData.cs:273）")
        assertSame(ConfigKey.WindowX, store.resolveAny("WINDOW WIDTH")?.spec?.key, "英文标签也是等价命中形态")
        assertSame(ConfigKey.WindowX, store.resolveGame("ウィンドウ幅")?.spec?.key, "读文件路径用的就是这条 GAME 查找（ConfigData.cs:539）")
        assertNull(store.resolveAny("WINDOWX"), "混合大小写的枚举名查不到 —— 契约说的 WINDOWX 官方行为")
        assertNull(store.resolveAny("MAXLOG"), "同上（Name 是 MaxLog，ConfigCode.cs:18）")
        assertNull(store.resolveAny("USEIMAGEBUFFER"), "枚举名再大也没用，UseImageBuffer 压根没注册条目")
    }

    @Test
    fun `每条出厂键的日英标签都解析回自己`() {
        val store = ConfigStore()
        val failures = ArrayList<String>()
        for (spec in ConfigSpecTable.all) {
            val jp = spec.upperJpLabel
            val hit = store.resolveAny(jp)?.spec?.key
            if (hit != spec.key) {
                failures += "日文标签「$jp」期望 ${spec.key} 实际 $hit"
            }
            if (spec.group === ConfigGroup.GAME) {
                // ConfigData.cs:277 的 EngText 分支只存在于 configArray；
                // GetReplaceItem(:298-316) 与 GetDebugItem(:330-348) 都只比 Name/Text。
                val en = spec.upperEnLabel
                val enHit = store.resolveAny(en)?.spec?.key
                if (enHit != spec.key) {
                    failures += "英文标签「$en」期望 ${spec.key} 实际 $enHit"
                }
            }
        }
        assertEquals(emptyList<String>(), failures, "标签遮蔽（两条键共用一个标签 ⇒ 先到先得，ConfigData.cs:265-281 的循环顺序）")
    }

    @Test
    fun `REPLACE 与 DEBUG 组不认英文标签`() {
        val store = ConfigStore()
        // 语料那份 emuera.config 是 EnglishConfigOutput:YES 写出来的英文文件；
        // 但读取链只调 GetConfigItem（ConfigData.cs:539），后两组本来就够不着，这里只核对查找面。
        assertSame(ConfigKey.MoneyLabel, store.resolveAny("お金の単位")?.spec?.key, "Name/Text 分支（ConfigData.cs:305-312）")
        assertNull(store.resolveAny("CURRENCY SYMBOL"), "GetReplaceItem 没有 EngText 分支")
        assertNull(store.resolveReplace("CURRENCY SYMBOL"), "同上，直接走 replace 查找也一样")
        assertSame(ConfigKey.DebugWindowWidth, store.resolveAny("デバッグウィンドウ幅")?.spec?.key, "ConfigData.cs:341")
        assertNull(store.resolveAny("DEBUG WINDOW WIDTH"), "GetDebugItem 没有 EngText 分支")
        assertNull(store.resolveDebug("DEBUG WINDOW WIDTH"))
    }

    @Test
    fun `主文件读不到的两组键在装载面上恒不可达`() {
        // ConfigData.cs:539 的 loadConfig 只调 GetConfigItem(string) —— 这是「三层链只喂 GAME 组」的根因，
        // 本路不重做链，只在条目表这一侧钉住：GAME 查找绝不能命中 REPLACE / DEBUG 组的任何标签。
        val store = ConfigStore()
        val leaked = ConfigSpecTable.specsOf(ConfigGroup.REPLACE)
            .map { it.upperJpLabel to it.key }
            .filter { store.resolveGame(it.first)?.spec?.key != null }
            .plus(
                ConfigSpecTable.specsOf(ConfigGroup.DEBUG)
                    .map { it.upperJpLabel to it.key }
                    .filter { store.resolveGame(it.first)?.spec?.key != null },
            )
        assertEquals(emptyList<Pair<String, ConfigKey>>(), leaked, "GAME 组遮蔽了后两组的标签（会让 _default.config 里的同名行生效，原版不会）")
    }

    @Test
    fun `英文标签的尾随空格让 CBTriggerAnyKeyWait 在读文件路径上永不命中`() {
        // ConfigData.cs:140 的英标签是 "Clipboard- AnyKey Wait Trigger "（尾随空格是标签的一部分，
        // AConfigItem.cs:23 只大写化不裁剪）；读文件时键先 array[0].Trim()（ConfigData.cs:539），
        // 空格被抹掉 ⇒ 永远比不中 ⇒ 值恒回落缺省 NO。原版如此（ADR-005），移植侧照抄。
        val store = ConfigStore()
        assertNull(store.resolveAny("CLIPBOARD- ANYKEY WAIT TRIGGER"), "Trim 之后的键形态（读文件路径）")
        assertSame(ConfigKey.CBTriggerAnyKeyWait, store.resolveAny("CLIPBOARD- ANYKEY WAIT TRIGGER ")?.spec?.key,
            "带尾随空格才命中 —— GETCONFIG 不吃 Trim（ConfigData.cs:352 直接把实参交给查找）")
        assertEquals("WAITをトリガーにする", ConfigSpecTable.specOf(ConfigKey.CBTriggerAnyKeyWait)?.jpLabel)
        assertTrue(requireNotNull(ConfigSpecTable.specOf(ConfigKey.CBTriggerAnyKeyWait)).enLabel.endsWith(" "))
    }

    @Test
    fun `CompatiDRAWLINE 的读取期重定向只挂在该键上`() {
        // ConfigData.cs:544-547：命中 CompatiDRAWLINE 后换成 CompatiLinefeedAs1739 的条目再赋值；
        // 条目本身照旧注册（:99），但写出被 ConfigData.cs:433 跳过。
        assertEquals(ConfigKey.CompatiLinefeedAs1739, ConfigSpecTable.specOf(ConfigKey.CompatiDRAWLINE)?.redirectOnReadTo)
        assertNull(ConfigSpecTable.specOf(ConfigKey.CompatiLinefeedAs1739)?.redirectOnReadTo)
        assertEquals(
            listOf(ConfigKey.CompatiDRAWLINE),
            ConfigSpecTable.all.filter { it.redirectOnReadTo != null }.map { it.key },
            "全表只有这一条重定向（ConfigData.cs:544-547 只判这一个 Code）",
        )
    }

    @Test
    fun `SaveConfig 的三个跳过条件所依赖的缺省值`() {
        // ConfigData.cs:433：CompatiDRAWLINE 永不写出；ChangeMasterNameIfDebug 为 true 时不写；LastKey 为 0 时不写。
        // 写出侧在 EmueraConfigWriter（本路禁碰），这里只钉住条目侧那三个读数。
        assertEquals(true, (ConfigSpecTable.specOf(ConfigKey.ChangeMasterNameIfDebug)?.defaultValue as ConfigValue.Bool).value)
        assertEquals(0L, (ConfigSpecTable.specOf(ConfigKey.LastKey)?.defaultValue as ConfigValue.LongValue).value)
        assertEquals(ConfigValueType.BOOL, ConfigSpecTable.specOf(ConfigKey.CompatiDRAWLINE)?.valueType)
    }

    // ------------------------------------------------------------------
    // GETCONFIG / GETCONFIGS 的取数路径（ConfigData.cs:350-422）
    // ------------------------------------------------------------------

    @Test
    fun `GETCONFIG 的显式分支逐条对拍`() {
        val store = ConfigStore()
        // :360-366 专属 bool 分支（其余 bool 走默认分支，结果同形）
        assertEquals(1L, store.erbInt("オートセーブを行なう"), "AutoSave（ConfigData.cs:360-366）")
        assertEquals(1L, store.erbInt("単位の位置"), "MoneyFirst —— replaceArray 成员，靠 GetItem 三级串联才够得着（:236-248）")
        // :367-375 八个 int 成员
        assertEquals(760L, store.erbInt("WINDOW WIDTH"), "WindowX（:367）")
        assertEquals(3L, store.erbInt("PRINTCを並べる数"), "PrintCPerLine（:368）")
        assertEquals(25L, store.erbInt("PRINTCの文字数"), "PrintCLength（:369）")
        assertEquals(18L, store.erbInt("FONT SIZE"), "FontSize（:370）")
        assertEquals(19L, store.erbInt("一行の高さ"), "LineHeight（:371）")
        assertEquals(20L, store.erbInt("表示するセーブデータ数"), "SaveDataNos（:372）")
        assertEquals(100L, store.erbInt("販売アイテム数"), "MaxShopItem（:373，replaceArray）")
        assertEquals(1L, store.erbInt("COM_ABLE初期値"), "ComAbleDefault（:374，replaceArray）")
        // :376-383 四个 Color：编码成 (R*256 + G)*256 + B
        assertEquals(16776960L, store.erbInt("HIGHLIGHT COLOR"), "FocusColor 255,255,0（:376-383；英标签是 Highlight color，ConfigData.cs:70）")
        assertEquals((192 * 256 + 192) * 256 + 192L, store.erbInt("文字色"), "ForeColor（:376）")
        // :384-386 两个 long
        assertEquals(4L, store.erbInt("PBANDの初期値"), "pbandDef（:384，replaceArray）")
        assertEquals(0L, store.erbInt("RELATIONの初期値"), "RelationDef（:385）")
        // :387-394 七个 string
        assertEquals("ＭＳ ゴシック", store.erbStr("フォント名"), "FontName（:387）")
        assertEquals("\u0024", store.erbStr("お金の単位"), "MoneyLabel（:388）")
        assertEquals("Now Loading...", store.erbStr("起動時簡略表示"), "LoadLabel（:389）")
        assertEquals("-", store.erbStr("DRAWLINE文字"), "DrawLineString（:390）")
        assertEquals("最初からはじめる", store.erbStr("システムメニュー0"), "TitleMenuString0（:391）")
        assertEquals("ロードしてはじめる", store.erbStr("システムメニュー1"), "TitleMenuString1（:392）")
        assertEquals("時間切れ", store.erbStr("時間切れ表示"), "TimeupLabel（:393）")
        // :395-397 两个 char 转成单字符串
        assertEquals("*", store.erbStr("BAR文字1"), "BarChar1（:395-396）")
        assertEquals(".", store.erbStr("BAR文字2"), "BarChar2（:397）")
        // :398-399 TextDrawingMode 专属：返回枚举常量名
        assertEquals("TEXTRENDERER", store.erbStr("描画インターフェース"), "TextDrawingMode（:398-399）")
    }

    @Test
    fun `GETCONFIG 的默认分支覆盖其余全部条目`() {
        val store = ConfigStore()
        // :403-417 —— ValueToString 为 YES/NO 的先折成 1/0，再按 long.TryParse 决定形状。
        assertEquals(1L, store.erbInt("USE MOUSE"), "bool ⇒ ValueToString 是 YES ⇒ 1（ConfigItem.cs:55-62）")
        assertEquals(0L, store.erbInt("ボタンの途中で行を折りかえさない"), "ButtonWrap 缺省 NO ⇒ 0")
        assertEquals(5000L, store.erbInt("履歴ログの行数"), "int 但不在 :367-375 名单里 ⇒ 走 :411-413 的数字回吐")
        assertEquals(3L, store.erbInt("最大スキップフレーム数"), "SkipFrame（条目在，SetConfig 不读，见 CFG-A5）")
        assertEquals(5L, store.erbInt("フレーム毎秒"), "FPS —— 只有这一条能经 Name 分支命中")
        assertEquals(0L, store.erbInt("最終更新コード"), "LastKey 缺省 0（ConfigData.cs:86）")
        assertEquals("notepad", store.erbStr("関連づけるテキストエディタ"), "string ⇒ long.TryParse 失败 ⇒ StrV（:415）")
        assertEquals("\" \"", store.erbStr("行連結の改行コードの置換文字列"), "ReplaceContinuationBR 的默认值含两个半角引号（ConfigData.cs:119）")
        assertEquals("IGNORE", store.erbStr("関数が見つからない警告の扱い"), "枚举型 ⇒ 常量名（ConfigItem.cs:81）")
        assertEquals("JAPANESE", store.erbStr("内部で使用する東アジア言語"), "useLanguage（ConfigData.cs:103）")
        assertEquals("txt", store.erbStr("LOADTEXTとSAVETEXTで使える拡張子"), "List of string 走逗号拼接分支（ConfigItem.cs:68-80）")
        assertEquals("0,0,139", store.erbStr("ポップアップの背景色"), "Color 不在 :376-383 名单里 ⇒ ValueToString 是 r,g,b 串 ⇒ StrV")
        assertEquals("", store.erbStr("Emueraの表示言語"), "空串条目（ConfigData.cs:130）")
    }

    @Test
    fun `GETCONFIGS 的 List 型回显形态（分歧编号 CFG-A2）`() {
        // 契约写的是 System.Collections.Generic.List`1[System.Int32]；复核结论是 Int64。
        // 依据：原版表里的 List 型只有两种泛型实参 ——
        //   List<string>（ConfigData.cs:127）有 ConfigItem.cs:68-80 的专属拼接，不吃 :81；
        //   List<long>（:169、:171-172）落到 :81 的 val.ToString() ⇒ .NET 输出运行时类型名，元素类型是 Int64。
        val store = ConfigStore()
        assertEquals("System.Collections.Generic.List`1[System.Int64]", store.erbStr("汚れの初期値"), "StainDefault（ConfigData.cs:169）")
        assertEquals("System.Collections.Generic.List`1[System.Int64]", store.erbStr("EXPLVの初期値"), "ExpLvDef（:171）")
        assertEquals("System.Collections.Generic.List`1[System.Int64]", store.erbStr("PALAMLVの初期値"), "PalamLvDef（:172）")
        assertNull(store.resolveAny("DEFAULT STAIN"), "replaceArray 不认英文标签，所以那个类型名串只能经日文标签观察到")
        // 移植侧的 LongList 值本身仍是解析得动的列表 —— 写出侧（ConfigData.cs:430-453 只遍历 configArray）
        // 永远不会落这个垃圾串，因此 ConfigValue.LongList 故意不提供 toConfigString。
        assertEquals(listOf(0L, 0L, 2L, 1L, 8L), store.itemOf(ConfigKey.StainDefault).longList())
    }

    @Test
    fun `未知配置名抛 InvalidConfigName 且文案里是原样的键`() {
        val store = ConfigStore()
        // ConfigData.cs:353-356；大写化只发生在查表内部（:264），消息用的是调用方原样的串。
        val e = assertFailsWith<CodeEE> { ConfigMethods.valueInErb(store, "WINDOWX") }
        assertEquals("文字列\"WINDOWX\"は適切なコンフィグ名ではありません", e.message, "Lang.cs:1742")
        val e2 = assertFailsWith<CodeEE> { ConfigMethods.valueInErb(store, "windowx") }
        assertEquals("文字列\"windowx\"は適切なコンフィグ名ではありません", e2.message, "大小写原样回显，不参与判定")
        // NotAllowGetConfigValue（:419）在真实条目表下不可达 —— :401 的 Enum.IsDefined 对合法 ConfigCode 恒真。
        assertTrue(ConfigKey.entries.all { ConfigKey.fromCode(it.code) != null })
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 对账表里属于某一组的注册行号（保持表内顺序）。 */
    private fun auditLinesOf(group: ConfigGroup): List<Int> =
        audit.filter { ConfigSpecTable.specOf(it.key)?.group === group }.map { it.csLine }

    /** 取 `SingleLongTerm` 形状（`Value.IntV`）；形状不对即 ClassCastException，正是本路要暴露的分歧。 */
    private fun ConfigStore.erbInt(name: String): Long = (ConfigMethods.valueInErb(this, name) as Value.IntV).v

    /** 取 `SingleStrTerm` 形状（`Value.StrV`）。 */
    private fun ConfigStore.erbStr(name: String): String = (ConfigMethods.valueInErb(this, name) as Value.StrV).v

    /**
     * 缺省值的对账形态 —— 按 `ConfigItem.ValueToString`（`ConfigItem.cs:53-82`）的分支手写一遍，
     * 刻意**不吃**移植侧写出器，这样产品侧改了序列化也不会把断言一起带走。
     * `LONG_LIST` 用 `L#` 前缀承载真实列表内容（原版那条分支只会回吐 .NET 类型名，见 [ConfigValue.LongList]）。
     */
    private fun render(value: ConfigValue): String = when (value) {
        is ConfigValue.Bool -> if (value.value) "YES" else "NO" // ConfigItem.cs:55-62
        is ConfigValue.ColorValue -> "${value.r},${value.g},${value.b}" // :63-67
        is ConfigValue.StringList -> value.value.joinToString(",") // :68-80
        is ConfigValue.LongList -> "L#" + value.value.joinToString(",") // :81 的读数在 CFG-A2 里单测
        is ConfigValue.IntValue -> value.value.toString() // :81
        is ConfigValue.LongValue -> value.value.toString()
        is ConfigValue.StringValue -> value.value
        is ConfigValue.CharValue -> value.value.toString()
        is ConfigValue.EnumValue -> value.constant
    }
}
