package com.eratw.port.core.config

import java.util.Locale

/** 条目所属数组 —— C# `ConfigData` 的三个列表，决定它由哪个文件喂、以及键匹配走哪条查找。 */
enum class ConfigGroup {

    /** `configArray`（spec: `ConfigData.cs:45-151`）：`emuera.config` / `default.config` / `fixed.config` 只认这一组。 */
    GAME,

    /** `debugArray`（spec: `ConfigData.cs:152-158`）：`debug.config`（spec: `ConfigData.cs:34`、`:667-710`）。 */
    DEBUG,

    /** `replaceArray`（spec: `ConfigData.cs:159-174`）：`_Replace.csv`（spec: `Process.cs:246` → `ConfigData.cs:594-639`）。 */
    REPLACE,
}

/**
 * 一个配置条目的**静态规格**：标识、双语标签、缺省值、类型，以及原版的读取期特殊路由。
 *
 * 标签的两条铁则（都来自 `AConfigItem` 构造）：
 *   * `Text` / `EngText` 在构造时就被 `ToUpperInvariant()` 掉过（spec: `AConfigItem.cs:22-23`），
 *     所以匹配时把读到的键大写化后**直接与标签原文**比较即可；本表的 [jpLabel] / [enLabel]
 *     存的是 `ConfigData.cs` 里的**原始大小写**（KDoc 与将来的设置界面要用它显示），
 *     比较一律走 [matchesUpperKey]，那里再大写化 —— 与 C# 的「注册时大写化」等价。
 *   * `EngText` 的尾随空格是标签的一部分（`CBTriggerAnyKeyWait`，spec: `ConfigData.cs:140`
 *     `"Clipboard- AnyKey Wait Trigger "`）：写出时它是 `…TRIGGER :NO`（冒号前多一个空格），
 *     读回时键被 `Trim()`（spec: `ConfigData.cs:539` `array[0].Trim()`）后带着少掉的那个空格
 *     去比较，于是**这一行永远匹配不上**、值永远回落到缺省。原版如此，本表照抄（ADR-005）。
 */
data class ConfigSpec(
    val key: ConfigKey,
    val group: ConfigGroup,
    val jpLabel: String,
    val enLabel: String,
    val valueType: ConfigValueType,
    val defaultValue: ConfigValue,
    /**
     * 读取期重定向 —— `CompatiDRAWLINE` 这个键被读到时，实际写入的是
     * `CompatiLinefeedAs1739` 条目（spec: `ConfigData.cs:544-547`）；
     * `CompatiDRAWLINE` 自己的条目照旧注册（spec: `ConfigData.cs:99`）但永不保存
     * （spec: `ConfigData.cs:433`）。
     */
    val redirectOnReadTo: ConfigKey? = null,
    /**
     * 设置对话框 `NumericUpDown` 的 GUI 值域（spec: `ConfigDialog.cs:432-463` 的 Minimum/Maximum
     * 赋值，控件与条目的对应关系在 `setNumericUpDown(ConfigDialog.cs:760-772`、`:916-919)` 的
     * 与 `setNumericUpDown(ConfigDialog.cs:692-706)` 的控件到条目映射配套）。
     *
     * **这不是解析期夹取**：`setNumericUpDown` 反而会把上下限撑开以容纳现值
     * （spec: `ConfigDialog.cs:696-703`），读文件的路径（`ConfigItem.TryParse`）里也没有任何
     * 范围检查。真正在读取期生效的只有 `Config.SetConfig` 末尾那 5 处夹取，见 [EffectiveConfig]。
     * 本字段只为将来的 Android 设置界面保留「输入建议区间」这一份事实。
     */
    val guiIntRange: IntRange? = null,
) {

    /** `AConfigItem.Name` = 枚举成员字符串（spec: `AConfigItem.cs:21`）。 */
    val name: String
        get() = key.name

    /** 注册期大写化后的日/英标签 —— 对应 `AConfigItem.cs:22-23` 在构造里存下来的形状。 */
    val upperJpLabel: String = jpLabel.uppercase(Locale.ROOT)
    val upperEnLabel: String = enLabel.uppercase(Locale.ROOT)

    /**
     * 键匹配 —— C# 的 `GetConfigItem(string)` / `GetReplaceItem(string)` / `GetDebugItem(string)`
     * 三处比较并不一致（spec: `ConfigData.cs:262-284` 比 `Name`/`Text`/`EngText`；
     * `:298-316` 与 `:330-348` 只比 `Name`/`Text`，**没有** `EngText` 分支），
     * 差异由 [withEnglishLabel] 承担：
     *   * `GAME` 组认英文标签 —— 语料那份 `emuera.config` 全靠这条才匹配得上
     *     （它是 `EnglishConfigOutput:YES` 写出的英文文件）；
     *   * `REPLACE` / `DEBUG` 组**不认**英文标签，英文键的那行会被静默丢弃。
     *
     * @param upperKey 已 `Trim()` 且大写化后的键
     */
    fun matchesUpperKey(upperKey: String, withEnglishLabel: Boolean): Boolean {
        if (upperKey == name) {
            return true // AConfigItem.cs:21 的 Name 分支；键已大写化而 Name 没有，故只对「枚举名本身全大写」的成员成立（全表只有 FPS，ConfigCode.cs:28）
        }
        if (upperKey == upperJpLabel) {
            return true // ConfigData.cs:273 / :309 / :341
        }
        return withEnglishLabel && upperKey == upperEnLabel // ConfigData.cs:277（仅 configArray）
    }

    /** 写出的一行（不含换行）—— `AConfigItem.ToString()`（spec: `ConfigItem.cs:84-87`）的形状。 */
    fun labelForWrite(english: Boolean): String = if (english) upperEnLabel else upperJpLabel
}

/**
 * 全量条目表 —— `ConfigData.setDefault()`（spec: `ConfigData.cs:43-175`）的镜像，
 * 注册顺序即列表顺序（`configArray` 100 条 + `debugArray` 7 条 + `replaceArray` 16 条，共 123 条）。
 *
 * 每行尾的 `// :NN` 是 `ConfigData.cs` 里的注册行号，逐条对应；默认值、日英标签、类型
 * 都取那一行的实参。`ConfigCode.UseImageBuffer`（spec: `ConfigCode.cs:15`）没有注册行，
 * 所以不在本表内。
 */
object ConfigSpecTable {

    private val gameSpecs: List<ConfigSpec> = listOf(
        bool(ConfigKey.IgnoreCase, "大文字小文字の違いを無視する", "Ignore case", true), // :45
        bool(ConfigKey.UseRenameFile, "_Rename.csvを利用する", "Use _Rename.csv file", false), // :46
        bool(ConfigKey.UseReplaceFile, "_Replace.csvを利用する", "Use _Replace.csv file", true), // :47
        bool(ConfigKey.UseMouse, "マウスを使用する", "Use mouse", true), // :48
        bool(ConfigKey.UseMenu, "メニューを使用する", "Show menu", true), // :49
        bool(ConfigKey.UseDebugCommand, "デバッグコマンドを使用する", "Allow debug commands", false), // :50
        bool(ConfigKey.AllowMultipleInstances, "多重起動を許可する", "Allow multiple instances", true), // :51
        bool(ConfigKey.AutoSave, "オートセーブを行なう", "Make autosaves", true), // :52
        bool(ConfigKey.UseKeyMacro, "キーボードマクロを使用する", "Use keyboard macros", true), // :53
        bool(ConfigKey.SizableWindow, "ウィンドウの高さを可変にする", "Changeable window height", true), // :54
        enumSpec(ConfigKey.TextDrawingMode, "描画インターフェース", "Drawing interface", ConfigEnumKind.TEXT_DRAWING_MODE, "TEXTRENDERER"), // :55
        intSpec(ConfigKey.WindowX, "ウィンドウ幅", "Window width", 760, 128..5000), // :56
        intSpec(ConfigKey.WindowY, "ウィンドウ高さ", "Window height", 480, 128..5000), // :57
        intSpec(ConfigKey.WindowPosX, "ウィンドウ位置X", "Window X position", 0, 0..10000), // :58
        intSpec(ConfigKey.WindowPosY, "ウィンドウ位置Y", "Window Y position", 0, 0..10000), // :59
        bool(ConfigKey.SetWindowPos, "起動時のウィンドウ位置を指定する", "Fixed window starting position", false), // :60
        bool(ConfigKey.WindowMaximixed, "起動時にウィンドウを最大化する", "Maximize window on startup", false), // :61
        intSpec(ConfigKey.MaxLog, "履歴ログの行数", "Max history log lines", 5000, 500..1000000), // :62
        intSpec(ConfigKey.PrintCPerLine, "PRINTCを並べる数", "Items per line for PRINTC", 3, 1..100), // :63
        intSpec(ConfigKey.PrintCLength, "PRINTCの文字数", "Number of Item characters for PRINTC", 25, 1..100), // :64
        str(ConfigKey.FontName, "フォント名", "Font name", "ＭＳ ゴシック"), // :65
        intSpec(ConfigKey.FontSize, "フォントサイズ", "Font size", 18, 8..144), // :66
        intSpec(ConfigKey.LineHeight, "一行の高さ", "Line height", 19, 8..144), // :67
        color(ConfigKey.ForeColor, "文字色", "Text color", 192, 192, 192), // :68
        color(ConfigKey.BackColor, "背景色", "Background color", 0, 0, 0), // :69
        color(ConfigKey.FocusColor, "選択中文字色", "Highlight color", 255, 255, 0), // :70
        color(ConfigKey.LogColor, "履歴文字色", "History log color", 192, 192, 192), // :71
        intSpec(ConfigKey.FPS, "フレーム毎秒", "FPS", 5, 1..240), // :72
        intSpec(ConfigKey.SkipFrame, "最大スキップフレーム数", "Skip frames", 3), // :73
        intSpec(ConfigKey.ScrollHeight, "スクロール行数", "Lines per scroll", 1, 1..10), // :74
        intSpec(ConfigKey.InfiniteLoopAlertTime, "無限ループ警告までのミリ秒数", "Milliseconds for infinite loop warning", 5000, 0..100000), // :75
        intSpec(ConfigKey.DisplayWarningLevel, "表示する最低警告レベル", "Minimum warning level", 1), // :76 —— 对话框侧是下拉框（读 ConfigDialog.cs:817、写自 comboBox5.SelectedIndex 的 :1020），无 NumericUpDown 区间
        bool(ConfigKey.DisplayReport, "ロード時にレポートを表示する", "Display loading report", false), // :77
        enumSpec(ConfigKey.ReduceArgumentOnLoad, "ロード時に引数を解析する", "Reduce argument on load", ConfigEnumKind.REDUCE_ARGUMENT_ON_LOAD, "NO"), // :78
        bool(ConfigKey.IgnoreUncalledFunction, "呼び出されなかった関数を無視する", "Ignore uncalled functions", true), // :79
        enumSpec(ConfigKey.FunctionNotFoundWarning, "関数が見つからない警告の扱い", "Function is not found warning", ConfigEnumKind.DISPLAY_WARNING_FLAG, "IGNORE"), // :80
        enumSpec(ConfigKey.FunctionNotCalledWarning, "関数が呼び出されなかった警告の扱い", "Function not called warning", ConfigEnumKind.DISPLAY_WARNING_FLAG, "IGNORE"), // :81
        bool(ConfigKey.ChangeMasterNameIfDebug, "デバッグコマンドを使用した時にMASTERの名前を変更する", "Change MASTER mame in debug", true), // :82 —— 值为 true 时不写出（ConfigData.cs:433）
        bool(ConfigKey.ButtonWrap, "ボタンの途中で行を折りかえさない", "Button wrapping", false), // :83
        bool(ConfigKey.SearchSubdirectory, "サブディレクトリを検索する", "Search subfolders", false), // :84
        bool(ConfigKey.SortWithFilename, "読み込み順をファイル名順にソートする", "Sort filenames", false), // :85
        long(ConfigKey.LastKey, "最終更新コード", "Latest identify code", 0L), // :86 —— 值为 0 时不写出（ConfigData.cs:433）
        intSpec(ConfigKey.SaveDataNos, "表示するセーブデータ数", "Save data count per page", 20, 20..80), // :87
        bool(ConfigKey.WarnBackCompatibility, "eramaker互換性に関する警告を表示する", "Eramaker compatibility warning", true), // :88
        bool(ConfigKey.AllowFunctionOverloading, "システム関数の上書きを許可する", "Allow overriding system functions", true), // :89
        bool(ConfigKey.WarnFunctionOverloading, "システム関数が上書きされたとき警告を表示する", "System function override warning", true), // :90 —— 受 AllowFunctionOverloading 的强制覆写（Config.cs:345-352）
        str(ConfigKey.TextEditor, "関連づけるテキストエディタ", "Text editor", "notepad"), // :91 —— 值里的冒号有专门的重拼分支（ConfigData.cs:548-563）
        enumSpec(ConfigKey.EditorType, "テキストエディタコマンドライン指定", "Text editor command line setting", ConfigEnumKind.TEXT_EDITOR_TYPE, "USER_SETTING"), // :92
        str(ConfigKey.EditorArgument, "エディタに渡す行指定引数", "Text editor command line arguments", ""), // :93 —— 赋值绕过 TryParse（ConfigData.cs:564-568）
        bool(ConfigKey.WarnNormalFunctionOverloading, "同名の非イベント関数が複数定義されたとき警告する", "Duplicated functions warning", false), // :94
        bool(ConfigKey.CompatiErrorLine, "解釈不可能な行があっても実行する", "Execute error lines", false), // :95
        bool(ConfigKey.CompatiCALLNAME, "CALLNAMEが空文字列の時にNAMEを代入する", "Use NAME if CALLNAME is empty", false), // :96
        bool(ConfigKey.UseSaveFolder, "セーブデータをsavフォルダ内に作成する", "Use sav folder", false), // :97
        bool(ConfigKey.CompatiRAND, "擬似変数RANDの仕様をeramakerに合わせる", "Imitate behavior for RAND", false), // :98
        bool(ConfigKey.CompatiDRAWLINE, "DRAWLINEを常に新しい行で行う", "Always start DRAWLINE in a new line", false, redirectOnReadTo = ConfigKey.CompatiLinefeedAs1739), // :99
        bool(ConfigKey.CompatiFunctionNoignoreCase, "関数・属性については大文字小文字を無視しない", "Do not ignore case for functions and attributes", false), // :100
        bool(ConfigKey.SystemAllowFullSpace, "全角スペースをホワイトスペースに含める", "Whitespace includes full-width space", true), // :101
        bool(ConfigKey.CompatiLinefeedAs1739, "ver1739以前の非ボタン折り返しを再現する", "Reproduce wrapping behavior like in pre ver1739", false), // :102
        enumSpec(ConfigKey.useLanguage, "内部で使用する東アジア言語", "Default ANSI encoding", ConfigEnumKind.USE_LANGUAGE, "JAPANESE"), // :103
        bool(ConfigKey.AllowLongInputByMouse, "ONEINPUT系命令でマウスによる2文字以上の入力を許可する", "Allow long input by mouse for ONEINPUT", false), // :104
        bool(ConfigKey.CompatiCallEvent, "イベント関数のCALLを許可する", "Allow CALL on event functions", false), // :105
        bool(ConfigKey.CompatiSPChara, "SPキャラを使用する", "Allow SP characters", false), // :106
        bool(ConfigKey.SystemSaveInBinary, "セーブデータをバイナリ形式で保存する", "Use the binary format for saving data", false), // :107
        bool(ConfigKey.CompatiFuncArgOptional, "ユーザー関数の全ての引数の省略を許可する", "Allow arguments omission for user functions", false), // :108
        bool(ConfigKey.CompatiFuncArgAutoConvert, "ユーザー関数の引数に自動的にTOSTRを補完する", "Auto TOSTR conversion for user function arguments", false), // :109
        bool(ConfigKey.SystemIgnoreTripleSymbol, "FORM中の三連記号を展開しない", "Do not process triple symbols inside FORM", false), // :110
        bool(ConfigKey.TimesNotRigorousCalculation, "TIMESの計算をeramakerにあわせる", "Imitate behavior for TIMES", false), // :111
        bool(ConfigKey.SystemNoTarget, "キャラクタ変数の引数を補完しない", "Do not auto-complete arguments for character variables", false), // :112
        bool(ConfigKey.SystemIgnoreStringSet, "文字列変数の代入に文字列式を強制する", "String variable assignment on valid with string expression", false), // :113
        bool(ConfigKey.UseLazyLoading, "UseLazyLoading", "UseLazyLoading", true), // :114 —— 中日英三个标签同名
        bool(ConfigKey.ForbidUpdateCheck, "UPDATECHECKを許可しない", "Disallow UPDATECHECK", false), // :115
        bool(ConfigKey.UseERD, "ERD機能を利用する", "Use ERD", true), // :116
        bool(ConfigKey.VarsizeDimConfig, "VARSIZEの次元指定をERD機能に合わせる", "Imitate ERD to VARSIZE dimension specification", false), // :117
        bool(ConfigKey.CheckDuplicateIdentifier, "ERDで定義した識別子とローカル変数の重複を確認する", "Check duplicate ERD identifier and private variablea", false), // :118 —— 原文 "variablea" 是拼写错误，照抄
        str(ConfigKey.ReplaceContinuationBR, "行連結の改行コードの置換文字列", "String of replacing new line code inside continuation", "\" \""), // :119 —— 默认值本身含两个半角引号字符
        strList(ConfigKey.ValidExtension, "LOADTEXTとSAVETEXTで使える拡張子", "Valid extensions for LOADTEXT and SAVETEXT", listOf("txt")), // :120-127 —— 保存走专门拼接分支（ConfigData.cs:437-447）
        bool(ConfigKey.ZipSaveData, "セーブデータを圧縮して保存する", "Compress save data", false), // :128
        bool(ConfigKey.EnglishConfigOutput, "CONFIGファイルの内容を英語で保存する", "Output English items in the config file", false), // :129
        str(ConfigKey.EmueraLang, "Emueraの表示言語", "Emuera interface language", ""), // :130
        str(ConfigKey.EmueraIcon, "Emueraのアイコンのパス", "Path to a custom window icon", ""), // :131
        bool(ConfigKey.CBUseClipboard, "表示したテキストをクリップボードにコピーする", "Clipboard- Copy text to Clipboard during Game", false), // :132
        bool(ConfigKey.CBIgnoreTags, "テキスト中の<>タグを無視する", "Clipboard- ignore <> tags in text", false), // :133
        str(ConfigKey.CBReplaceTags, "<>を次の文で置き換える", "Clipboard- Replace <> with this", "."), // :134
        bool(ConfigKey.CBNewLinesOnly, "新しい行のみコピーする", "Clipboard- Show new lines only", true), // :135
        bool(ConfigKey.CBClearBuffer, "画面のリフレッシュ時にクリップボードとバッファを消去する", "Clipboard- Clear Buffer when game clears screen", false), // :136
        bool(ConfigKey.CBTriggerLeftClick, "左クリックをトリガーにする", "Clipboard- LeftClick Trigger", true), // :137
        bool(ConfigKey.CBTriggerMiddleClick, "ホイールクリックをトリガーにする", "Clipboard- MiddleClick Trigger", false), // :138
        bool(ConfigKey.CBTriggerDoubleLeftClick, "ダブルクリックをトリガーにする", "Clipboard- Double Left Click Trigger", false), // :139
        bool(ConfigKey.CBTriggerAnyKeyWait, "WAITをトリガーにする", "Clipboard- AnyKey Wait Trigger ", false), // :140 —— 英标签带尾随空格，读取期恒不匹配，见 [ConfigSpec] 类 KDoc
        bool(ConfigKey.CBTriggerInputWait, "INPUTをトリガーにする", "Clipboard- Wait for Input Trigger", true), // :141
        intSpec(ConfigKey.CBMaxCB, "クリップボードに貼り付ける行数", "Clipboard- Length of Clipboard", 25, 1..200), // :142
        intSpec(ConfigKey.CBBufferSize, "総バッファサイズ", "Clipboard- Buffer Size", 300, 50..5000), // :143
        intSpec(ConfigKey.CBScrollCount, "スクロールの行数", "Clipboard- Scrolled Lines per Key", 5, 1..100), // :144
        intSpec(ConfigKey.CBMinTimer, "クリップボードの更新間隔(ミリ秒)", "Clipboard- min time between pastes", 800, 300..60000), // :145
        bool(ConfigKey.RikaiEnabled, "Rikaichanを使用する", "Rikai- Enabled", false), // :146
        str(ConfigKey.RikaiFilename, "Rikaichanのファイルパス", "Rikai- Dictionary Filename", "Emuera-Rikai-edict.txt-eucjp"), // :147
        color(ConfigKey.RikaiColorBack, "ポップアップの背景色", "Rikai- Back Color", 0, 0, 139), // :148
        color(ConfigKey.RikaiColorText, "ポップアップの文字色", "Rikai- Text Color", 255, 255, 255), // :149
        bool(ConfigKey.RikaiUseSeparateBoxes, "翻訳中の語句を強調表示する", "Rikai- Use Separate Boxes", true), // :150
        bool(ConfigKey.Ctrl_Z_Enabled, "Ctrl-Zで元に戻す機能を有効にする", "Enable undo with ctrl-z", false), // :151
    )

    private val debugSpecs: List<ConfigSpec> = listOf(
        bool(ConfigKey.DebugShowWindow, "起動時にデバッグウインドウを表示する", "Show debug window on startup", true, group = ConfigGroup.DEBUG), // :152
        bool(ConfigKey.DebugWindowTopMost, "デバッグウインドウを最前面に表示する", "Debug window always on top", true, group = ConfigGroup.DEBUG), // :153
        intSpec(ConfigKey.DebugWindowWidth, "デバッグウィンドウ幅", "Debug window width", 400, group = ConfigGroup.DEBUG), // :154
        intSpec(ConfigKey.DebugWindowHeight, "デバッグウィンドウ高さ", "Debug window height", 300, group = ConfigGroup.DEBUG), // :155
        bool(ConfigKey.DebugSetWindowPos, "デバッグウィンドウ位置を指定する", "Fixed debug window starting position", false, group = ConfigGroup.DEBUG), // :156
        intSpec(ConfigKey.DebugWindowPosX, "デバッグウィンドウ位置X", "Debug window X position", 0, group = ConfigGroup.DEBUG), // :157
        intSpec(ConfigKey.DebugWindowPosY, "デバッグウィンドウ位置Y", "Debug window Y position", 0, group = ConfigGroup.DEBUG), // :158
    )

    private val replaceSpecs: List<ConfigSpec> = listOf(
        str(ConfigKey.MoneyLabel, "お金の単位", "Currency symbol", "$", group = ConfigGroup.REPLACE), // :159
        bool(ConfigKey.MoneyFirst, "単位の位置", "Currency symbol position", true, group = ConfigGroup.REPLACE), // :160
        str(ConfigKey.LoadLabel, "起動時簡略表示", "Loading message", "Now Loading...", group = ConfigGroup.REPLACE), // :161
        intSpec(ConfigKey.MaxShopItem, "販売アイテム数", "Max shop item storage", 100, group = ConfigGroup.REPLACE), // :162
        str(ConfigKey.DrawLineString, "DRAWLINE文字", "DRAWLINE character", "-", group = ConfigGroup.REPLACE), // :163 —— 空串在 SetReplace 里回落为 "-"（Config.cs:726-729）
        char(ConfigKey.BarChar1, "BAR文字1", "BAR character 1", '*', group = ConfigGroup.REPLACE), // :164
        char(ConfigKey.BarChar2, "BAR文字2", "BAR character 2", '.', group = ConfigGroup.REPLACE), // :165
        str(ConfigKey.TitleMenuString0, "システムメニュー0", "System menu 0", "最初からはじめる", group = ConfigGroup.REPLACE), // :166
        str(ConfigKey.TitleMenuString1, "システムメニュー1", "System menu 1", "ロードしてはじめる", group = ConfigGroup.REPLACE), // :167
        intSpec(ConfigKey.ComAbleDefault, "COM_ABLE初期値", "Default COM_ABLE", 1, group = ConfigGroup.REPLACE), // :168
        longList(ConfigKey.StainDefault, "汚れの初期値", "Default Stain", listOf(0L, 0L, 2L, 1L, 8L)), // :169
        str(ConfigKey.TimeupLabel, "時間切れ表示", "Time up message", "時間切れ", group = ConfigGroup.REPLACE), // :170
        longList(ConfigKey.ExpLvDef, "EXPLVの初期値", "Default EXPLV", listOf(0L, 1L, 4L, 20L, 50L, 200L)), // :171
        longList(ConfigKey.PalamLvDef, "PALAMLVの初期値", "Default PALAMLV", listOf(0L, 100L, 500L, 3000L, 10000L, 30000L, 60000L, 100000L, 150000L, 250000L)), // :172
        long(ConfigKey.pbandDef, "PBANDの初期値", "Default PBAND", 4L, group = ConfigGroup.REPLACE), // :173
        long(ConfigKey.RelationDef, "RELATIONの初期値", "Default RELATION", 0L, group = ConfigGroup.REPLACE), // :174
    )

    /**
     * 全表。顺序取 `ConfigData.GetItem(string)` 的三级查找顺序
     * （spec: `ConfigData.cs:236-248`：`configArray` → `replaceArray` → `debugArray`），
     * 而不是 `setDefault()` 的注册顺序（那里 debug 在 replace 之前，spec: `ConfigData.cs:152-158`
     * 先于 `:159-174`）；查找语义只有在这个顺序下才与原版逐项一致（三组之间标签不重名，
     * 所以顺序实际不影响命中，但口径要留在正确的位置上）。
     */
    val all: List<ConfigSpec> = gameSpecs + replaceSpecs + debugSpecs

    /** 按 [ConfigGroup] 取的顺序视图 —— `ConfigData.cs:23-27` 三个列表。 */
    fun specsOf(group: ConfigGroup): List<ConfigSpec> = when (group) {
        ConfigGroup.GAME -> gameSpecs
        ConfigGroup.DEBUG -> debugSpecs
        ConfigGroup.REPLACE -> replaceSpecs
    }

    private val byKeyMap: Map<ConfigKey, ConfigSpec> = all.associateBy { it.key }

    /** 条目查找 —— `ConfigData.GetItem(ConfigCode)` 的三条查找合写（spec: `ConfigData.cs:222-234`）。 */
    fun specOf(key: ConfigKey): ConfigSpec? = byKeyMap[key]

    /** `configArray` 的条数（本路 golden 判据的分母基线；C# 侧 spec: `ConfigData.cs:45-151`，共 107 行、ValidExtension 一项占 120-127 八行 ⇒ 100 条）。 */
    val gameCount: Int = gameSpecs.size

    val debugCount: Int = debugSpecs.size

    val replaceCount: Int = replaceSpecs.size

    private fun bool(
        key: ConfigKey,
        jp: String,
        en: String,
        default: Boolean,
        redirectOnReadTo: ConfigKey? = null,
        group: ConfigGroup = ConfigGroup.GAME,
    ): ConfigSpec = ConfigSpec(key, group, jp, en, ConfigValueType.BOOL, ConfigValue.Bool(default), redirectOnReadTo)

    private fun intSpec(
        key: ConfigKey,
        jp: String,
        en: String,
        default: Int,
        gui: IntRange? = null,
        group: ConfigGroup = ConfigGroup.GAME,
    ): ConfigSpec = ConfigSpec(key, group, jp, en, ConfigValueType.INT, ConfigValue.IntValue(default), guiIntRange = gui)

    private fun long(
        key: ConfigKey,
        jp: String,
        en: String,
        default: Long,
        group: ConfigGroup = ConfigGroup.GAME,
    ): ConfigSpec = ConfigSpec(key, group, jp, en, ConfigValueType.LONG, ConfigValue.LongValue(default))

    private fun str(
        key: ConfigKey,
        jp: String,
        en: String,
        default: String,
        group: ConfigGroup = ConfigGroup.GAME,
    ): ConfigSpec = ConfigSpec(key, group, jp, en, ConfigValueType.STRING, ConfigValue.StringValue(default))

    private fun char(
        key: ConfigKey,
        jp: String,
        en: String,
        default: Char,
        group: ConfigGroup = ConfigGroup.GAME,
    ): ConfigSpec = ConfigSpec(key, group, jp, en, ConfigValueType.CHAR, ConfigValue.CharValue(default))

    private fun color(key: ConfigKey, jp: String, en: String, r: Int, g: Int, b: Int): ConfigSpec =
        ConfigSpec(key, ConfigGroup.GAME, jp, en, ConfigValueType.COLOR, ConfigValue.ColorValue(r, g, b))

    private fun strList(key: ConfigKey, jp: String, en: String, default: List<String>): ConfigSpec =
        ConfigSpec(key, ConfigGroup.GAME, jp, en, ConfigValueType.STRING_LIST, ConfigValue.StringList(default))

    private fun longList(key: ConfigKey, jp: String, en: String, default: List<Long>): ConfigSpec =
        ConfigSpec(key, ConfigGroup.REPLACE, jp, en, ConfigValueType.LONG_LIST, ConfigValue.LongList(default))

    private fun enumSpec(
        key: ConfigKey,
        jp: String,
        en: String,
        kind: ConfigEnumKind,
        default: String,
    ): ConfigSpec = ConfigSpec(
        key = key,
        group = ConfigGroup.GAME,
        jpLabel = jp,
        enLabel = en,
        valueType = when (kind) {
            ConfigEnumKind.TEXT_DRAWING_MODE -> ConfigValueType.TEXT_DRAWING_MODE_ENUM
            ConfigEnumKind.REDUCE_ARGUMENT_ON_LOAD -> ConfigValueType.REDUCE_ARGUMENT_ENUM
            ConfigEnumKind.DISPLAY_WARNING_FLAG -> ConfigValueType.DISPLAY_WARNING_ENUM
            ConfigEnumKind.USE_LANGUAGE -> ConfigValueType.USE_LANGUAGE_ENUM
            ConfigEnumKind.TEXT_EDITOR_TYPE -> ConfigValueType.TEXT_EDITOR_TYPE_ENUM
        },
        defaultValue = ConfigValue.EnumValue(kind, default),
    )

    init {
        // `debugArray` / `replaceArray` 的成员要落在各自的组里，否则 `specsOf` 与键匹配口径都会错。
        // 这里用一次性的构造期自检代替外部测试的重复劳动（条目表是从 `ConfigData.cs:43-175` 手抄的，
        // 漏一个组标签是最容易犯的错）。
        specsOf(ConfigGroup.GAME).forEach { require(it.group === ConfigGroup.GAME) { "GAME 组混入 ${it.key}" } }
        specsOf(ConfigGroup.DEBUG).forEach { require(it.group === ConfigGroup.DEBUG) { "DEBUG 组混入 ${it.key}" } }
        specsOf(ConfigGroup.REPLACE).forEach { require(it.group === ConfigGroup.REPLACE) { "REPLACE 组混入 ${it.key}" } }
        all.forEach { require(it.valueType.accepts(it.defaultValue)) { "${it.key} 的默认值类型与 valueType 不符" } }
        require(all.size == byKeyMap.size) { "条目表有重复键" }
    }
}
