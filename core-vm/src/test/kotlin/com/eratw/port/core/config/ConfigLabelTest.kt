package com.eratw.port.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设置页标签对账表（[ConfigPageLabels]）的行为测试 —— CONFIG-I18N 的判据线。
 *
 * 断言的对象不是 PC 基准文件（本路是纯标签侧、不接生产链，语料 `emuera.config` 的 96 行键
 * 在这里只当**输入夹具**用），而是逐条指回 C# 出处的期望值：
 *   * 键形与大小写 —— `AConfigItem.cs:18-24`、`ConfigData.cs:236-248` / `:262-284` / `:298-316` /
 *     `:330-348`、读文件路径的 `Trim` 与 `ToUpper`（`ConfigData.cs:539`、`:264`）；
 *   * 页面标签（日语基准）—— `Lang.cs` 与 `ConfigDialog.resx` 的具体行（每条登记的 jaSource）；
 *   * 简体中文译文 —— `lang.emuera-zhs.xml` 的具体 tr 行（每条登记的 zhsSource）；
 *   * 控件绑定 —— `ConfigDialog.cs` 与 `DebugConfigDialog.cs` 的具体行（每条登记的 bindingSource）。
 *
 * 语料键清单取自 `emuera.config`（96 键）实测；行号注释 `// :NN` 是那份文件的行号。
 */
class ConfigLabelTest {

    // -----------------------------------------------------------------
    // 全表形状
    // -----------------------------------------------------------------

    @Test
    fun `全表完整性 98 条且出处字段自洽`() {
        // 91 条 configArray（100 - 9 个未上页）+ 7 条 debugArray。
        assertEquals(98, ConfigPageLabels.all.size)
        assertEquals(
            91,
            ConfigPageLabels.all.count { ConfigSpecTable.specOf(it.key)?.group === ConfigGroup.GAME },
        )
        assertEquals(
            7,
            ConfigPageLabels.all.count { ConfigSpecTable.specOf(it.key)?.group === ConfigGroup.DEBUG },
        )
        ConfigPageLabels.all.forEach { entry ->
            assertTrue(entry.jaLabel.isNotEmpty(), "${entry.key} 的日语标签为空")
            // zhsLabel 与 zhsSource 严格同空、同非空（原版没写译文的条目两字段同为 null）。
            assertEquals(entry.zhsLabel == null, entry.zhsSource == null, "${entry.key} 的空值不配对")
            // 日语标签只有两个来源：Lang.cs 的成员或设计器资源（resx）。
            assertTrue(
                entry.jaSource.startsWith("Lang.cs:") || entry.jaSource.startsWith("ConfigDialog.resx:"),
                "${entry.key} 的 jaSource 形状不对：${entry.jaSource}",
            )
            // 绑定出处只有两个对话框。
            assertTrue(
                entry.bindingSource.startsWith("ConfigDialog.cs:") ||
                    entry.bindingSource.startsWith("DebugConfigDialog.cs:"),
                "${entry.key} 的 bindingSource 形状不对：${entry.bindingSource}",
            )
        }
    }

    @Test
    fun `两个 resx 来源的条目没有可翻译 id 且出处指回设计器资源`() {
        // Ctrl_Z_Enabled 与 ReplaceContinuationBR：TranslateUI 不赋值，文本只在 resx 里。
        val ctrlZ = checkNotNull(ConfigPageLabels.byKey(ConfigKey.Ctrl_Z_Enabled))
        assertNull(ctrlZ.langId)
        assertEquals("ConfigDialog.resx:1316", ctrlZ.jaSource)
        assertEquals("Ctrl-Zで元に戻す機能を有効にする", ctrlZ.jaLabel)

        val replaceBr = checkNotNull(ConfigPageLabels.byKey(ConfigKey.ReplaceContinuationBR))
        assertNull(replaceBr.langId)
        assertEquals("ConfigDialog.resx:796", replaceBr.jaSource)
        // resx 原文含两个半角引号（xml:space=preserve，逐字节照抄）。
        assertEquals("行連結の改行コード置換文字列(\"~\"で括ってください)", replaceBr.jaLabel)
    }

    // -----------------------------------------------------------------
    // 语料 emuera.config 的真实键（96 键里取 65 条上页键）
    // -----------------------------------------------------------------

    @Test
    fun `语料键 65 条逐条对账到键与页面标签`() {
        val corpusKeys = listOf(
            "IGNORE CASE" to ConfigKey.IgnoreCase, // :1
            "USE _RENAME.CSV FILE" to ConfigKey.UseRenameFile, // :2
            "USE _REPLACE.CSV FILE" to ConfigKey.UseReplaceFile, // :3
            "USE MOUSE" to ConfigKey.UseMouse, // :4
            "SHOW MENU" to ConfigKey.UseMenu, // :5
            "ALLOW DEBUG COMMANDS" to ConfigKey.UseDebugCommand, // :6
            "ALLOW MULTIPLE INSTANCES" to ConfigKey.AllowMultipleInstances, // :7
            "MAKE AUTOSAVES" to ConfigKey.AutoSave, // :8
            "USE KEYBOARD MACROS" to ConfigKey.UseKeyMacro, // :9
            "CHANGEABLE WINDOW HEIGHT" to ConfigKey.SizableWindow, // :10
            "DRAWING INTERFACE" to ConfigKey.TextDrawingMode, // :11
            "WINDOW WIDTH" to ConfigKey.WindowX, // :12
            "WINDOW X POSITION" to ConfigKey.WindowPosX, // :14
            "FIXED WINDOW STARTING POSITION" to ConfigKey.SetWindowPos, // :16
            "MAXIMIZE WINDOW ON STARTUP" to ConfigKey.WindowMaximixed, // :17
            "MAX HISTORY LOG LINES" to ConfigKey.MaxLog, // :18
            "ITEMS PER LINE FOR PRINTC" to ConfigKey.PrintCPerLine, // :19
            "FONT NAME" to ConfigKey.FontName, // :21
            "LINE HEIGHT" to ConfigKey.LineHeight, // :23
            "HIGHLIGHT COLOR" to ConfigKey.FocusColor, // :26
            "HISTORY LOG COLOR" to ConfigKey.LogColor, // :27
            "FPS" to ConfigKey.FPS, // :28（全大写枚举名，"FPS" 同时也是 Name 分支的命中形）
            "LINES PER SCROLL" to ConfigKey.ScrollHeight, // :30
            "MILLISECONDS FOR INFINITE LOOP WARNING" to ConfigKey.InfiniteLoopAlertTime, // :31
            "MINIMUM WARNING LEVEL" to ConfigKey.DisplayWarningLevel, // :32
            "REDUCE ARGUMENT ON LOAD" to ConfigKey.ReduceArgumentOnLoad, // :34
            "IGNORE UNCALLED FUNCTIONS" to ConfigKey.IgnoreUncalledFunction, // :35
            "FUNCTION IS NOT FOUND WARNING" to ConfigKey.FunctionNotFoundWarning, // :36
            "FUNCTION NOT CALLED WARNING" to ConfigKey.FunctionNotCalledWarning, // :37
            "SAVE DATA COUNT PER PAGE" to ConfigKey.SaveDataNos, // :41
            "ALLOW OVERRIDING SYSTEM FUNCTIONS" to ConfigKey.AllowFunctionOverloading, // :43
            "SYSTEM FUNCTION OVERRIDE WARNING" to ConfigKey.WarnFunctionOverloading, // :44
            "TEXT EDITOR" to ConfigKey.TextEditor, // :45
            "TEXT EDITOR COMMAND LINE SETTING" to ConfigKey.EditorType, // :46
            "TEXT EDITOR COMMAND LINE ARGUMENTS" to ConfigKey.EditorArgument, // :47
            "DUPLICATED FUNCTIONS WARNING" to ConfigKey.WarnNormalFunctionOverloading, // :48
            "USE NAME IF CALLNAME IS EMPTY" to ConfigKey.CompatiCALLNAME, // :50
            "USE SAV FOLDER" to ConfigKey.UseSaveFolder, // :51
            "WHITESPACE INCLUDES FULL-WIDTH SPACE" to ConfigKey.SystemAllowFullSpace, // :54
            "REPRODUCE WRAPPING BEHAVIOR LIKE IN PRE VER1739" to ConfigKey.CompatiLinefeedAs1739, // :55
            "DEFAULT ANSI ENCODING" to ConfigKey.useLanguage, // :56
            "ALLOW SP CHARACTERS" to ConfigKey.CompatiSPChara, // :59
            "USE THE BINARY FORMAT FOR SAVING DATA" to ConfigKey.SystemSaveInBinary, // :60
            "AUTO TOSTR CONVERSION FOR USER FUNCTION ARGUMENTS" to ConfigKey.CompatiFuncArgAutoConvert, // :62
            "DO NOT PROCESS TRIPLE SYMBOLS INSIDE FORM" to ConfigKey.SystemIgnoreTripleSymbol, // :63
            "DO NOT AUTO-COMPLETE ARGUMENTS FOR CHARACTER VARIABLES" to ConfigKey.SystemNoTarget, // :65
            "DISALLOW UPDATECHECK" to ConfigKey.ForbidUpdateCheck, // :68
            "USE ERD" to ConfigKey.UseERD, // :69
            "IMITATE ERD TO VARSIZE DIMENSION SPECIFICATION" to ConfigKey.VarsizeDimConfig, // :70
            "CHECK DUPLICATE ERD IDENTIFIER AND PRIVATE VARIABLEA" to ConfigKey.CheckDuplicateIdentifier, // :71
            "STRING OF REPLACING NEW LINE CODE INSIDE CONTINUATION" to ConfigKey.ReplaceContinuationBR, // :72
            "VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT" to ConfigKey.ValidExtension, // :73
            "COMPRESS SAVE DATA" to ConfigKey.ZipSaveData, // :74
            "OUTPUT ENGLISH ITEMS IN THE CONFIG FILE" to ConfigKey.EnglishConfigOutput, // :75
            "EMUERA INTERFACE LANGUAGE" to ConfigKey.EmueraLang, // :76
            "CLIPBOARD- IGNORE <> TAGS IN TEXT" to ConfigKey.CBIgnoreTags, // :79
            "CLIPBOARD- REPLACE <> WITH THIS" to ConfigKey.CBReplaceTags, // :80
            "CLIPBOARD- LEFTCLICK TRIGGER" to ConfigKey.CBTriggerLeftClick, // :83
            "CLIPBOARD- WAIT FOR INPUT TRIGGER" to ConfigKey.CBTriggerInputWait, // :87
            "CLIPBOARD- LENGTH OF CLIPBOARD" to ConfigKey.CBMaxCB, // :88
            "CLIPBOARD- MIN TIME BETWEEN PASTES" to ConfigKey.CBMinTimer, // :91
            "RIKAI- ENABLED" to ConfigKey.RikaiEnabled, // :92
            "RIKAI- DICTIONARY FILENAME" to ConfigKey.RikaiFilename, // :93
            "RIKAI- USE SEPARATE BOXES" to ConfigKey.RikaiUseSeparateBoxes, // :96
            "ENABLE UNDO WITH CTRL-Z" to ConfigKey.Ctrl_Z_Enabled, // :97
        )
        assertEquals(65, corpusKeys.size)
        for ((key, expected) in corpusKeys) {
            assertEquals(expected, ConfigPageLabels.configKeyFor(key), "键形查找：$key")
            val label = assertNotNull(ConfigPageLabels.labelFor(key), "页面标签查找：$key")
            assertEquals(expected, label.key, "页面标签：$key")
            assertEquals(label, ConfigPageLabels.byKey(expected), "两种入口应当同一条：$key")
        }
    }

    @Test
    fun `语料抽查 16 条标签的日文与中文逐字`() {
        val spot = listOf(
            // 键形 → 期望日语（Lang.cs 行）→ 期望中文（emuera-zhs.xml 行）
            Triple("IGNORE CASE", "大文字小文字の違いを無視する", "无视文字的大小写"), // Lang.cs:458 / zhs:94
            Triple("DEFAULT ANSI ENCODING", "内部で使用する東アジア言語", "程序的非Unicode的言"), // Lang.cs:494 / zhs:103
            Triple("SAVE DATA COUNT PER PAGE", "使用するセーブデータ数", "每页显示的存档数"), // Lang.cs:318 / zhs:59（≠配置键）
            Triple("MILLISECONDS FOR INFINITE LOOP WARNING", "無限ループ警告までのミリ秒", "无限循环警告超时毫秒数"), // Lang.cs:314 / zhs:58（≠配置键）
            Triple("VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT", "LOADTEXTとSAVETEXTで使える拡張子", "允许在LOADTEXT和SAVETEXT中使用的扩展名"),
            Triple("TEXT EDITOR COMMAND LINE SETTING", "コマンドライン引数", "命令行参数"), // 类级 Translate：Lang.cs:264 / zhs:62
            Triple("REPRODUCE WRAPPING BEHAVIOR LIKE IN PRE VER1739", "ver1739以前の非ボタン折り返しを再現する", "回调至ver1739以前版本的非按钮换行规则"),
            Triple("TEXT EDITOR", "関連づけるテキストエディタ", "关联的文本编辑器"),
            Triple("LINES PER SCROLL", "スクロールの行数", "滚动行数"), // Lang.cs:404 / zhs:81（≠配置键）
            Triple("HISTORY LOG COLOR", "履歴文字色", "历史日志颜色"),
            Triple("EMUERA INTERFACE LANGUAGE", "Emuera表示言語", "Emuera界面语言"), // Lang.cs:357 / zhs:70（≠配置键）
            Triple("COMPRESS SAVE DATA", "セーブデータを圧縮して保存する(バイナリ時のみ)", "存档以压缩的形式保存(仅二进制时)"), // Lang.cs:517 / zhs:108
            Triple("FUNCTION IS NOT FOUND WARNING", "関数が見つからない警告の扱い", "找不到函数时的警告处理"),
            Triple("MINIMUM WARNING LEVEL", "表示する最低警告レベル", "显示的最低警告等级"), // 类级 Translate：Lang.cs:623 / zhs:142
            Triple("REDUCE ARGUMENT ON LOAD", "ロード時に引数を解析する", "读取时解析参数"), // 类级 Translate：Lang.cs:604 / zhs:138
            Triple("CLIPBOARD- IGNORE <> TAGS IN TEXT", "テキスト中の<>タグを無視する", "无视文本中的<>标签"),
        )
        assertEquals(16, spot.size)
        for ((key, ja, zhs) in spot) {
            val label = assertNotNull(ConfigPageLabels.labelFor(key), key)
            assertEquals(ja, label.jaLabel, "日语标签：$key")
            assertEquals(zhs, label.zhsLabel, "中文译文：$key")
            // TranslatableString.Text 的回落（Lang.cs:25-33）：日文界面就是原文，中文界面优先译文。
            assertEquals(ja, label.text(zhHans = false), "ja 界面：$key")
            assertEquals(zhs, label.text(zhHans = true), "zh 界面：$key")
        }
    }

    // -----------------------------------------------------------------
    // 键形规则（ConfigData.cs:236-248 / :262-316 / :330-348）
    // -----------------------------------------------------------------

    @Test
    fun `DEFAULT ANSI ENCODING 英文形 日文形与大小写混写等价`() {
        // useLanguage 的 EngText = "Default ANSI encoding"、Text = "内部で使用する東アジア言語"
        // （ConfigData.cs:103）；输入大写化后与标签比（AConfigItem.cs:22-23 + ConfigData.cs:264）。
        val expected = checkNotNull(ConfigPageLabels.byKey(ConfigKey.useLanguage))
        for (form in listOf(
            "DEFAULT ANSI ENCODING",
            "default ansi encoding",
            "  Default ANSI Encoding  ", // 前导尾随空白按读文件口径裁剪（ConfigData.cs:539）
            "内部で使用する東アジア言語",
        )) {
            assertEquals(expected, ConfigPageLabels.labelFor(form), "键形：$form")
        }
        assertEquals(ConfigKey.useLanguage, ConfigPageLabels.configKeyFor("DEFAULT ANSI ENCODING"))
        assertEquals("内部で使用する東アジア言語", expected.jaLabel)
        assertEquals("程序的非Unicode的言", expected.zhsLabel)
        assertEquals("ConfigDialog.cs:546+865", expected.bindingSource)
    }

    @Test
    fun `未知键返回 null 绝不编造`() {
        assertNull(ConfigPageLabels.labelFor("NO SUCH KEY"))
        assertNull(ConfigPageLabels.configKeyFor("NO SUCH KEY"))
        // 差一个字母的"近义词"同样是未知键：真键是 Valid extensions for LOADTEXT and SAVETEXT。
        assertNull(ConfigPageLabels.labelFor("VALID EXTENSION"))
        assertNull(ConfigPageLabels.labelFor(""))
        assertNull(ConfigPageLabels.labelFor("   "))
        assertNull(ConfigPageLabels.configKeyFor("\t\n"))
    }

    @Test
    fun `混合大小写成员名查不到 全大写成员名才从 Name 分支命中`() {
        // C# 把键 ToUpper 后与**未大写**的 Name 比（ConfigData.cs:264 + AConfigItem.cs:21），
        // 于是 WindowX / WINDOWX 都查不到（GETCONFIG 一族同病，见 DebugMiscMethods.kt KDoc）。
        assertNull(ConfigPageLabels.configKeyFor("WindowX"))
        assertNull(ConfigPageLabels.configKeyFor("WINDOWX"))
        assertNull(ConfigPageLabels.labelFor("WindowX"))
        // 正确查法是标签形；日文形也通。
        assertEquals(ConfigKey.WindowX, ConfigPageLabels.configKeyFor("WINDOW WIDTH"))
        assertEquals(ConfigKey.WindowX, ConfigPageLabels.configKeyFor("ウィンドウ幅"))
        // "FPS" 这种本来就全大写的成员名能从 Name 分支命中（ConfigData.cs:269）。
        assertEquals(ConfigKey.FPS, ConfigPageLabels.configKeyFor("FPS"))
        assertEquals("フレーム毎秒", ConfigPageLabels.labelFor("FPS")?.jaLabel)
    }

    @Test
    fun `尾随空格标签让英文形永远查不到 日文形不受影响`() {
        // CBTriggerAnyKeyWait 的 EngText 带尾随空格（ConfigData.cs:140），大写化后仍与
        // 裁剪过的键不等 —— 语料 :86 写出的那一行自己都读不回来（官方 bug，ADR-005）。
        assertNull(ConfigPageLabels.configKeyFor("CLIPBOARD- ANYKEY WAIT TRIGGER"))
        assertNull(ConfigPageLabels.configKeyFor("CLIPBOARD- ANYKEY WAIT TRIGGER "))
        // 日文形（Text = "WAITをトリガーにする"，ConfigData.cs:123）可以命中。
        assertEquals(ConfigKey.CBTriggerAnyKeyWait, ConfigPageLabels.configKeyFor("WAITをトリガーにする"))
        assertEquals("WAIT時", ConfigPageLabels.labelFor("WAITをトリガーにする")?.jaLabel)
        assertEquals("WAIT时", ConfigPageLabels.labelFor("WAITをトリガーにする")?.zhsLabel)
    }

    @Test
    fun `debug 组只认 Name 与日文 Text 英文形不认`() {
        // debugArray 的查找没有 EngText 分支（ConfigData.cs:330-348）。
        assertEquals(ConfigKey.DebugShowWindow, ConfigPageLabels.configKeyFor("起動時にデバッグウインドウを表示する"))
        val label = assertNotNull(ConfigPageLabels.labelFor("起動時にデバッグウインドウを表示する"))
        assertEquals("起動時にデバッグウインドウを表示する", label.jaLabel)
        assertEquals("启动时打开调试窗口", label.zhsLabel) // Lang.cs:852 / zhs:172 / DebugConfigDialog.cs:88+147
        assertNull(ConfigPageLabels.configKeyFor("SHOW DEBUG WINDOW ON STARTUP"))
    }

    // -----------------------------------------------------------------
    // 未上页条目与「不同源」清单
    // -----------------------------------------------------------------

    @Test
    fun `不在设置页上的 configArray 条目恰好是这九个`() {
        // 证据：ConfigDialog.cs 全文里没有这些 ConfigCode 的引用（grep）；UseLazyLoading 有一个
        // 空壳控件 checkBoxUseLazyLoading（ConfigDialog.cs:1987 挂进面板），但 .Text 全工程无人赋值
        // （resx 里也无 checkBoxUseLazyLoading.Text）、无 ConfigCode 绑定；CompatiDRAWLINE 读取期
        // 被重定向到 CompatiLinefeedAs1739（ConfigData.cs:544-547）。
        val excluded = setOf(
            ConfigKey.SkipFrame,
            ConfigKey.AllowLongInputByMouse,
            ConfigKey.SystemIgnoreStringSet,
            ConfigKey.UseLazyLoading,
            ConfigKey.ChangeMasterNameIfDebug,
            ConfigKey.LastKey,
            ConfigKey.CompatiDRAWLINE,
            ConfigKey.EmueraIcon,
            ConfigKey.CBUseClipboard,
        )
        val gameKeys = ConfigSpecTable.specsOf(ConfigGroup.GAME).map { it.key }.toSet()
        assertEquals(100, gameKeys.size)
        val pageKeys = ConfigPageLabels.all.map { it.key }.toSet()
        assertEquals(excluded, gameKeys - pageKeys)
        // 未上页 ⇒ byKey 也没有。
        excluded.forEach { assertNull(ConfigPageLabels.byKey(it), "$it") }
    }

    @Test
    fun `语料里六个未上页键：键能解析页面标签为 null`() {
        // 逐键出处 = 语料 emuera.config 的实测行。
        val corpusUnpaged = listOf(
            "SKIP FRAMES" to ConfigKey.SkipFrame, // :29
            "ALLOW LONG INPUT BY MOUSE FOR ONEINPUT" to ConfigKey.AllowLongInputByMouse, // :57
            "STRING VARIABLE ASSIGNMENT ON VALID WITH STRING EXPRESSION" to ConfigKey.SystemIgnoreStringSet, // :66
            "USELAZYLOADING" to ConfigKey.UseLazyLoading, // :67（EngText "UseLazyLoading" 的大写形）
            "PATH TO A CUSTOM WINDOW ICON" to ConfigKey.EmueraIcon, // :77
            "CLIPBOARD- COPY TEXT TO CLIPBOARD DURING GAME" to ConfigKey.CBUseClipboard, // :78
        )
        for ((keyText, expected) in corpusUnpaged) {
            assertEquals(expected, ConfigPageLabels.configKeyFor(keyText), "键形：$keyText")
            // 键认识、条目没上设置页 —— 如实返回 null，不编标签。
            assertNull(ConfigPageLabels.labelFor(keyText), "页面标签：$keyText")
        }
        // REPLACE 组（_Replace.csv）同形状：日文形能解析（GetItem 三级），标签没有；
        // 英文形连键都解析不到 —— replaceArray 的查找没有 EngText 分支（ConfigData.cs:298-316），
        // 与 ConfigParsingTest 的「Currency symbol 读不进 emuera.config」是同一现实的另一面。
        assertEquals(ConfigKey.MoneyLabel, ConfigPageLabels.configKeyFor("お金の単位"))
        assertNull(ConfigPageLabels.configKeyFor("CURRENCY SYMBOL"))
        assertNull(ConfigPageLabels.labelFor("お金の単位"))
        assertNull(ConfigPageLabels.byKey(ConfigKey.MoneyLabel))
    }

    @Test
    fun `页面标签与条目注册文本不同源的 20 条逐条登记`() {
        // 复核口径：ConfigPageLabel.jaDiffersFromConfigText（jaLabel vs ConfigSpec.jpLabel）。
        // 这 20 条就是设置页文案不能当配置文件键、配置文件键也不能当设置页文案的全部条目。
        val divergent = setOf(
            ConfigKey.InfiniteLoopAlertTime, // ミリ秒 vs ミリ秒数（Lang.cs:314 vs ConfigData.cs:75）
            ConfigKey.SaveDataNos, // 使用する… vs 表示する…（Lang.cs:318 vs ConfigData.cs:87）
            ConfigKey.EditorType, // コマンドライン引数 vs テキストエディタコマンドライン指定
            ConfigKey.EditorArgument, // コマンドライン引数（共享区标题）vs エディタに渡す行指定引数
            ConfigKey.EmueraLang, // Emuera表示言語 vs Emueraの表示言語
            ConfigKey.SetWindowPos, // …位置を固定する vs …位置を指定する
            ConfigKey.ScrollHeight, // スクロールの行数 vs スクロール行数
            ConfigKey.ZipSaveData, // 带 (バイナリ時のみ) 后缀
            ConfigKey.ReplaceContinuationBR, // resx 文本 vs ConfigData.cs:119
            ConfigKey.CheckDuplicateIdentifier, // 前缀 ERDの vs ERDで定義した
            ConfigKey.CBClearBuffer, // タイトル画面に戻った時… vs 画面のリフレッシュ時…
            ConfigKey.CBTriggerLeftClick,
            ConfigKey.CBTriggerMiddleClick,
            ConfigKey.CBTriggerDoubleLeftClick,
            ConfigKey.CBTriggerAnyKeyWait,
            ConfigKey.CBTriggerInputWait,
            ConfigKey.RikaiEnabled,
            ConfigKey.RikaiFilename,
            ConfigKey.RikaiColorBack,
            ConfigKey.RikaiColorText,
        )
        assertEquals(20, divergent.size)
        val actual = ConfigPageLabels.all.filter { it.jaDiffersFromConfigText }.map { it.key }.toSet()
        assertEquals(divergent, actual)
        // 抽查一对逐字：页面上是「…ミリ秒」，配置键是「…ミリ秒数」。
        assertEquals("無限ループ警告までのミリ秒", ConfigPageLabels.byKey(ConfigKey.InfiniteLoopAlertTime)?.jaLabel)
        assertEquals("無限ループ警告までのミリ秒数", ConfigSpecTable.specOf(ConfigKey.InfiniteLoopAlertTime)?.jpLabel)
        // 反例：同源条目不触发。
        assertFalse(checkNotNull(ConfigPageLabels.byKey(ConfigKey.IgnoreCase)).jaDiffersFromConfigText)
        assertFalse(checkNotNull(ConfigPageLabels.byKey(ConfigKey.UseMouse)).jaDiffersFromConfigText)
    }

    @Test
    fun `zhs 缺失的六条如实为 null 并回落日文`() {
        // 原版 zhs 语言文件里没有这些 id（或条目根本没有可翻译 id）—— 登记为 null，绝不编文本。
        val missingZhs = listOf(
            ConfigKey.CheckDuplicateIdentifier, // emuera-zhs.xml 没有该条 id（122 条 UI.ConfigDialog 里无它）
            ConfigKey.RikaiEnabled, // zhs 无 Rikai 族 id（英文文件有：emuera-eng.xml:1046）
            ConfigKey.RikaiFilename,
            ConfigKey.RikaiUseSeparateBoxes,
            ConfigKey.Ctrl_Z_Enabled, // resx 来源，无 id
            ConfigKey.ReplaceContinuationBR, // resx 来源，无 id
        )
        for (key in missingZhs) {
            val label = assertNotNull(ConfigPageLabels.byKey(key), "$key")
            assertNull(label.zhsLabel, "$key")
            assertNull(label.zhsSource, "$key")
            // 中文界面回落日语基准串（Lang.cs:25-33 的 TranslatableString.Text）。
            assertEquals(label.jaLabel, label.text(zhHans = true), "$key")
        }
        assertEquals(6, ConfigPageLabels.all.count { it.zhsLabel == null })
        // 关键一条：Rikai 的日语标签逐字（Lang.cs:779）。
        assertEquals(
            "ポップアップ翻訳辞書を有効にする(日本語→英語)",
            ConfigPageLabels.byKey(ConfigKey.RikaiEnabled)?.jaLabel,
        )
    }

    @Test
    fun `共享标签与复用译文的三条特例有登记`() {
        // EditorType 与 EditorArgument 共享「コマンドライン引数」（ConfigDialog.cs:1750-1751 同面板）。
        val editorType = checkNotNull(ConfigPageLabels.byKey(ConfigKey.EditorType))
        val editorArgument = checkNotNull(ConfigPageLabels.byKey(ConfigKey.EditorArgument))
        assertEquals(editorType.jaLabel, editorArgument.jaLabel)
        assertEquals(editorType.langId, editorArgument.langId)
        assertTrue(checkNotNull(editorArgument.note).contains("共享"))
        // Rikai 两个颜色控件复用字体页译文（ConfigDialog.cs:616-617）。
        assertEquals(
            "UI.ConfigDialog.Font.BackgroundColor",
            ConfigPageLabels.byKey(ConfigKey.RikaiColorBack)?.langId,
        )
        assertEquals(
            "UI.ConfigDialog.Font.TextColor",
            ConfigPageLabels.byKey(ConfigKey.RikaiColorText)?.langId,
        )
        assertTrue(checkNotNull(ConfigPageLabels.byKey(ConfigKey.RikaiColorBack)?.note).contains("复用"))
    }
}