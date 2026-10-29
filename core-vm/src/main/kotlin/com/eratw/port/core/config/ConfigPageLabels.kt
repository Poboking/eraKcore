package com.eratw.port.core.config

import java.util.Locale

/**
 * 一个配置条目在**设置页**上显示的标签 —— 「页面标签 ↔ 配置键」对账表的一行。
 *
 * 为什么需要本表：设置页控件上的文字与 `emuera.config` 里那条键**不是同一来源**。
 * 键（Text / EngText）注册在 `ConfigData.setDefault()`（spec: `ConfigData.cs:45-174`），
 * 而控件文字来自 `Lang.cs` 的 `Lang.UI.ConfigDialog.*` / `Lang.UI.DebugConfigDialog.*`
 * （`ConfigDialog.TranslateUI` spec: `ConfigDialog.cs:489-627` 逐控件赋值），
 * 中文界面再用 `lang.emuera-zhs.xml` 的译文覆盖。两层文本经常不一样
 * （如 `InfiniteLoopAlertTime`：配置键是「…ミリ秒数」、页面标签是「…ミリ秒」），
 * 所以任何「拿页面标签当键、或拿键当标签」的用法都必须先过本表对账。
 *
 * 三套出处的钉法：
 *   * [key] 的键形匹配与大小写规则 —— `AConfigItem.cs:18-24`（`Text` / `EngText` 构造期
 *     `ToUpper(InvariantCulture)`，`Name` 不转）＋ `ConfigData.cs:262-284` / `:298-316` /
 *     `:330-348`（三组各自的比较范围：只有 configArray 认 `EngText`）；
 *   * [jaLabel] 与 [jaSource] —— `Lang.cs` 里那个 `TranslatableString` 的构造实参行
 *     （消息 id 由反射按「类名路径.属性名」注册，spec: `Lang.cs:4726-4775`）；
 *     **另有两个条目没有 Lang 成员**，标签只存在于 WinForms 设计器资源
 *     （`ConfigDialog.resx`，`TranslateUI` 不覆盖、不可翻译）→ [langId] 为 null；
 *   * [zhsLabel] 与 [zhsSource] —— `lang.emuera-zhs.xml` 里同 id 的 `tr` 行。
 *     原版 zhs 文件没写的（`CheckDuplicateIdentifier`、Rikai 族三条、两个 resx 条目）
 *     如实为 null，**绝不编造**；运行时回落日语基准串（`TranslatableString.Text`：
 *     有译文用译文、没有用原文，spec: `Lang.cs:23-33`），本类用 [text] 复刻这一回落。
 *
 * @property key        配置条目 —— 与 `ConfigKey` 枚举（`ConfigCode` 镜像）一一对应，
 *                      设置页所有控件都经由 `ConfigCode` 寻址（spec: `ConfigDialog.cs:685-716`
 *                      的 `setCheckBox` / `setNumericUpDown` / `setColorBox` 一族）
 * @property langId     `Lang.cs` 的可翻译消息 id（如 `UI.ConfigDialog.System.ANSI`）；
 *                      resx 来源的条目为 null（没有可翻译的 id）
 * @property jaLabel    设置页在日语（原版基准语言）下显示的文本
 * @property zhsLabel   简体中文译文；原版 zhs 文件缺就是 null
 * @property jaSource   [jaLabel] 的出处，形如 `Lang.cs:494` 或 `ConfigDialog.resx:1316`
 * @property zhsSource  [zhsLabel] 的出处，形如 `lang.emuera-zhs.xml:103`；
 *                      与 [zhsLabel] 严格同空、同非空
 * @property bindingSource 控件 ↔ 条目 的绑定出处（`ConfigDialog.cs` / `DebugConfigDialog.cs`），
 *                      形如 `ConfigDialog.cs:493+720`（标签赋值行 + 控件绑定行）；
 *                      无独立控件文本的条目（textbox / 数值框 / 下拉框）给的就是
 *                      「相邻标签赋值行 + 值控件绑定行」，相邻关系见 [note] 或面板结构
 * @property note       特例说明：共享标签、复用译文、官方缺陷、原文出处；没有就是 null
 */
data class ConfigPageLabel(
    val key: ConfigKey,
    val langId: String?,
    val jaLabel: String,
    val zhsLabel: String?,
    val jaSource: String,
    val zhsSource: String?,
    val bindingSource: String,
    val note: String? = null,
) {

    /**
     * 页面标签的读法（原版 `TranslatableString.Text` 的回落，spec: `Lang.cs:25-33`）：
     * 装了 zh-Hans 语言文件就用 [zhsLabel]，没装（或该条没有译文）就是日语基准 [jaLabel]。
     */
    fun text(zhHans: Boolean): String = if (zhHans) (zhsLabel ?: jaLabel) else jaLabel

    /**
     * 页面标签与条目注册文本是否**不同源** —— 本表存在的理由（BOARD CONFIG-I18N 一行）。
     *
     * 比较对象是 `ConfigData.setDefault()` 的 `Text` 实参（本仓已镜像为
     * [ConfigSpec.jpLabel]，spec: `ConfigData.cs:45-174`）。为 true 的条目，
     * 拿 `emuera.config` 的日文键去做设置页文案、或反过来，都会拿到非法文本。
     */
    val jaDiffersFromConfigText: Boolean
        get() = jaLabel != ConfigSpecTable.specOf(key)?.jpLabel
}

/**
 * 「设置页标签 ↔ 配置键」逐条对账表与查询函数（CONFIG-I18N + M6-CONFIG-REST，标签侧单一来源）。
 *
 * 覆盖范围 = 两个设置对话框里**绑定到配置条目**的每一个控件：
 *   * `ConfigDialog`（emuera.config 的设置页）—— `ConfigDialog.cs:685-925` 的绑定清单
 *     去掉 9 个没有控件的条目（清单见 `ConfigLabelTest.不在设置页上的 configArray 条目恰好是这九个`；
 *     其中 `UseLazyLoading` 有一个只有名字的空壳控件 `checkBoxUseLazyLoading`：
 *     spec: `ConfigDialog.cs:1987` 挂进了面板，但 `.Text` 全工程无人赋值、
 *     也无 `ConfigCode.UseLazyLoading` 绑定，在设计器资源里连文本都没有 → 不算页面条目）；
 *   * `DebugConfigDialog`（debug.config 的设置页，`-Debug` 启动）—— 7 条 debugArray，
 *     绑定清单 spec: `DebugConfigDialog.cs:147-153`。
 *
 * 键形查询 [labelFor] / [configKeyFor] 的匹配口径（照抄现状、不修原版缺陷，ADR-005）：
 *   1. 输入 `trim()`（读文件路径的大写化与裁剪入口 spec: `ConfigData.cs:539`）；
 *   2. `uppercase(Locale.ROOT)`（`ToUpper(InvariantCulture)` 的对应物，spec: `ConfigData.cs:264`、
 *      `:300`、`:332`）；
 *   3. 按 `GetItem(string)` 的三级顺序查找（spec: `ConfigData.cs:236-248`）：
 *      configArray 比 `Name` / `Text` / `EngText`（spec: `ConfigData.cs:262-284`），
 *      replaceArray 与 debugArray 只比 `Name` / `Text`（spec: `ConfigData.cs:298-316`、`:330-348`）。
 *   于是有两条「官方行为」直接可见：
 *   * 混合大小写的成员名（如 `WindowX`）大写后与未大写的 `Name` 等不上、也不是任何标签
 *     → **查不到**（`GETCONFIG` 一族同样查不到，见 `core/fn/DebugMiscMethods.kt` 的 KDoc）；
 *     只有本来就全大写的成员名（如 `FPS`）能从 `Name` 分支命中（spec: `AConfigItem.cs:21`）；
 *   * `CBTriggerAnyKeyWait` 的英文标签带尾随空格（spec: `ConfigData.cs:140`），
 *     大写化后仍与裁剪过的键不等 → 英文形（含语料自己在 `:86` 写出的那一行）**永远查不到**，
 *     只有日文形 `WAITをトリガーにする` 能命中。日文形与 [key] 直查不受影响。
 *
 * **未知键返回 null，绝不编造**。此外还有第三种 null：键能解析到条目、
 * 但该条目不在任何设置页上（如 `SkipFrame`、`Currency symbol`）—— 对账函数只能如实返回 null，
 * 调用方要区分「键不认识」与「条目没上页面」就结合 [configKeyFor] 判断。
 *
 * 消费方：`app/.../SettingsActivity.kt` 的三组列表都走 [displayLabel]（GAME / DEBUG 取页面文案，
 * REPLACE 原版没有设置页，回落配置键）；`core/fn/DebugMiscMethods.kt` 的 `GETCONFIG` 一族只读
 * [ConfigStore]，不再自带条目表。
 *
 * TODO(verify)：zhs 缺失的 6 条（CheckDuplicateIdentifier、Rikai 三条、两个 resx 条目）
 * 在中文界面会回落日文基准串（`Lang.cs:25-33` 的既定行为）；「移植侧是否要给它们补译、
 * 或换一份自带 zhs 语言文件」是产品决策 —— 本表只登记 null，不编文本。
 * 另：本表未在真实语言文件加载路径上实测渲染（loadLangXML 语义按
 * `Lang.cs:4611-4631` 静态对账）。
 */
object ConfigPageLabels {

    /**
     * 全表。按设置页分节排列，节内顺序 = `TranslateUI` 的标签赋值顺序
     * （`ConfigDialog.cs:489-627` 分页赋值；页尾两个 resx 标签按其控件在面板里的位置收尾；
     * debug 页照 `DebugConfigDialog.cs:85-99`）。**完整性**（不是顺序）由构造期自检钉住：
     * 键集合必须恰好等于条目表里「有页面标签的那些条目」。
     */
    val all: List<ConfigPageLabel> = listOf(
        // -------------------------------------------------------------
        // ConfigDialog · 环境（Environment）页 —— spec: ConfigDialog.cs:1664-1775
        // -------------------------------------------------------------
        page(
            ConfigKey.UseMouse, "UI.ConfigDialog.Environment.UseMouse", "マウスを使用する", "使用鼠标",
            "Lang.cs:278", "lang.emuera-zhs.xml:49", "ConfigDialog.cs:493+720",
        ),
        page(
            ConfigKey.UseMenu, "UI.ConfigDialog.Environment.UseMenu", "メニューを使用する", "使用菜单",
            "Lang.cs:282", "lang.emuera-zhs.xml:50", "ConfigDialog.cs:494+721",
        ),
        page(
            ConfigKey.UseDebugCommand, "UI.ConfigDialog.Environment.UseDebugCommand", "デバッグコマンドを使用する", "使用调试命令",
            "Lang.cs:286", "lang.emuera-zhs.xml:51", "ConfigDialog.cs:495+722",
        ),
        page(
            ConfigKey.AllowMultipleInstances, "UI.ConfigDialog.Environment.AllowMultipleInstances", "多重起動を許可する", "允许运行多个窗口实例",
            "Lang.cs:290", "lang.emuera-zhs.xml:52", "ConfigDialog.cs:496+723",
        ),
        page(
            ConfigKey.UseKeyMacro, "UI.ConfigDialog.Environment.UseKeyMacro", "キーボードマクロを使用する", "使用按键宏",
            "Lang.cs:294", "lang.emuera-zhs.xml:53", "ConfigDialog.cs:497+733",
        ),
        page(
            ConfigKey.AutoSave, "UI.ConfigDialog.Environment.AutoSave", "オートセーブを行なう", "开启自动保存",
            "Lang.cs:298", "lang.emuera-zhs.xml:54", "ConfigDialog.cs:498+724",
        ),
        page(
            ConfigKey.UseSaveFolder, "UI.ConfigDialog.Environment.UseSaveFolder", "セーブデータをsavフォルダ内に作成する", "将存档保存到sav文件夹",
            "Lang.cs:302", "lang.emuera-zhs.xml:55", "ConfigDialog.cs:499+741",
        ),
        page(
            ConfigKey.EnglishConfigOutput, "UI.ConfigDialog.Environment.EnglishConfigOutput", "CONFIGファイルの内容を英語で保存する", "用英文输出配置文件的设置项目",
            "Lang.cs:306", "lang.emuera-zhs.xml:56", "ConfigDialog.cs:500+742",
        ),
        page(
            ConfigKey.MaxLog, "UI.ConfigDialog.Environment.MaxLog", "履歴ログの行数", "历史日志行数",
            "Lang.cs:310", "lang.emuera-zhs.xml:57", "ConfigDialog.cs:501+762",
        ),
        page(
            ConfigKey.InfiniteLoopAlertTime, "UI.ConfigDialog.Environment.InfiniteLoopAlertTime", "無限ループ警告までのミリ秒", "无限循环警告超时毫秒数",
            "Lang.cs:314", "lang.emuera-zhs.xml:58", "ConfigDialog.cs:502+769",
            note = "页面标签「…ミリ秒」与配置键「…ミリ秒数」不同源（ConfigData.cs:75 的 Text vs Lang.cs:314）",
        ),
        page(
            ConfigKey.SaveDataNos, "UI.ConfigDialog.Environment.SaveDataPerPage", "使用するセーブデータ数", "每页显示的存档数",
            "Lang.cs:318", "lang.emuera-zhs.xml:59", "ConfigDialog.cs:503+770",
            note = "页面标签「使用する…」与配置键「表示する…」不同源（ConfigData.cs:87 的 Text vs Lang.cs:318）",
        ),
        page(
            ConfigKey.TextEditor, "UI.ConfigDialog.Environment.TextEditor", "関連づけるテキストエディタ", "关联的文本编辑器",
            "Lang.cs:322", "lang.emuera-zhs.xml:60", "ConfigDialog.cs:504+897",
            note = "值控件是 textBox1（回写 spec: ConfigDialog.cs:1081）；标签与配置键同源",
        ),
        page(
            ConfigKey.EditorType, "UI.ConfigDialog.Environment.TextEditorCommandline", "コマンドライン引数", "命令行参数",
            "Lang.cs:264", "lang.emuera-zhs.xml:62", "ConfigDialog.cs:506+880",
            note = "标签是嵌套类 [Translate(\"コマンドライン引数\")] 的类级文本（Lang.cs:264），控件是 comboBox6",
        ),
        page(
            ConfigKey.EditorArgument, "UI.ConfigDialog.Environment.TextEditorCommandline", "コマンドライン引数", "命令行参数",
            "Lang.cs:264", "lang.emuera-zhs.xml:62", "ConfigDialog.cs:898+1082",
            note = "与 EditorType 共享同一区标题：flowLayoutPanel11 = flowLayoutPanel12[label23+comboBox6] + textBox2（ConfigDialog.cs:1750-1751）；原版没有给它单独写标签",
        ),
        page(
            ConfigKey.Ctrl_Z_Enabled, null, "Ctrl-Zで元に戻す機能を有効にする", null,
            "ConfigDialog.resx:1316", null, "ConfigDialog.cs:925+1115",
            note = "无 Lang 成员：TranslateUI 不覆盖，文本只在 WinForms 设计器资源里（xml:space=preserve）→ 原版 zhs 无法翻译该标签",
        ),

        // -------------------------------------------------------------
        // ConfigDialog · 显示（Display）页 —— spec: ConfigDialog.cs:1776-1800
        // -------------------------------------------------------------
        page(
            ConfigKey.TextDrawingMode, "UI.ConfigDialog.Display.TextDrawingMode", "描画インターフェース", "渲染API",
            "Lang.cs:337", "lang.emuera-zhs.xml:65", "ConfigDialog.cs:509+777",
        ),
        page(
            ConfigKey.FPS, "UI.ConfigDialog.Display.FPS", "フレーム毎秒", "FPS",
            "Lang.cs:341", "lang.emuera-zhs.xml:66", "ConfigDialog.cs:510+766",
        ),
        page(
            ConfigKey.PrintCPerLine, "UI.ConfigDialog.Display.PrintCPerLine", "PRINTCを並べる数", "PRINTC的每行项目数",
            "Lang.cs:345", "lang.emuera-zhs.xml:67", "ConfigDialog.cs:511+763",
        ),
        page(
            ConfigKey.PrintCLength, "UI.ConfigDialog.Display.PrintCLength", "PRINTCの文字数", "PRINTC项目的文字长度",
            "Lang.cs:349", "lang.emuera-zhs.xml:68", "ConfigDialog.cs:512+764",
        ),
        page(
            ConfigKey.ButtonWrap, "UI.ConfigDialog.Display.ButtonWrap", "ボタンの途中で行を折りかえさない", "按钮文本不产生换行",
            "Lang.cs:353", "lang.emuera-zhs.xml:69", "ConfigDialog.cs:513+729",
        ),
        page(
            ConfigKey.EmueraLang, "UI.ConfigDialog.Display.EmueraLang", "Emuera表示言語", "Emuera界面语言",
            "Lang.cs:357", "lang.emuera-zhs.xml:70", "ConfigDialog.cs:514+475",
            note = "页面标签「Emuera表示言語」与配置键「Emueraの表示言語」不同源（ConfigData.cs:130 的 Text vs Lang.cs:357）",
        ),

        // -------------------------------------------------------------
        // ConfigDialog · 窗口（Window）页 —— spec: ConfigDialog.cs:1841-1913
        // -------------------------------------------------------------
        page(
            ConfigKey.WindowX, "UI.ConfigDialog.Window.WindowWidth", "ウィンドウ幅", "窗口宽度",
            "Lang.cs:368", "lang.emuera-zhs.xml:72", "ConfigDialog.cs:516+760",
        ),
        page(
            ConfigKey.WindowY, "UI.ConfigDialog.Window.WindowHeight", "ウィンドウ高さ", "窗口高度",
            "Lang.cs:372", "lang.emuera-zhs.xml:73", "ConfigDialog.cs:517+761",
        ),
        page(
            ConfigKey.SizableWindow, "UI.ConfigDialog.Window.ChangeableWindowHeight", "ウィンドウの高さを可変にする", "窗口高度可变",
            "Lang.cs:380", "lang.emuera-zhs.xml:75", "ConfigDialog.cs:519+725",
        ),
        page(
            ConfigKey.WindowMaximixed, "UI.ConfigDialog.Window.WindowMaximixed", "起動時にウィンドウを最大化する", "启动时窗口最大化",
            "Lang.cs:384", "lang.emuera-zhs.xml:76", "ConfigDialog.cs:520+736",
        ),
        page(
            ConfigKey.SetWindowPos, "UI.ConfigDialog.Window.SetWindowPos", "起動時のウィンドウの位置を固定する", "固定启动时的窗口位置",
            "Lang.cs:388", "lang.emuera-zhs.xml:77", "ConfigDialog.cs:521+732",
            note = "页面标签「…の位置を固定する」与配置键「…位置を指定する」不同源（ConfigData.cs:60 的 Text vs Lang.cs:388）",
        ),
        page(
            ConfigKey.WindowPosX, "UI.ConfigDialog.Window.WindowX", "ウィンドウ位置X", "窗口位置X",
            "Lang.cs:392", "lang.emuera-zhs.xml:78", "ConfigDialog.cs:522+771",
        ),
        page(
            ConfigKey.WindowPosY, "UI.ConfigDialog.Window.WindowY", "ウィンドウ位置Y", "窗口位置Y",
            "Lang.cs:396", "lang.emuera-zhs.xml:79", "ConfigDialog.cs:523+772",
        ),
        page(
            ConfigKey.ScrollHeight, "UI.ConfigDialog.Window.LinesPerScroll", "スクロールの行数", "滚动行数",
            "Lang.cs:404", "lang.emuera-zhs.xml:81", "ConfigDialog.cs:525+767",
            note = "页面标签「スクロールの行数」与配置键「スクロール行数」不同源（ConfigData.cs:74 的 Text vs Lang.cs:404）",
        ),

        // -------------------------------------------------------------
        // ConfigDialog · 字体（Font）页 —— spec: ConfigDialog.cs:1914-1971
        // -------------------------------------------------------------
        page(
            ConfigKey.BackColor, "UI.ConfigDialog.Font.BackgroundColor", "背景色", "背景颜色",
            "Lang.cs:415", "lang.emuera-zhs.xml:83", "ConfigDialog.cs:527+774",
        ),
        page(
            ConfigKey.ForeColor, "UI.ConfigDialog.Font.TextColor", "文字色", "文本颜色",
            "Lang.cs:419", "lang.emuera-zhs.xml:84", "ConfigDialog.cs:528+773",
        ),
        page(
            ConfigKey.FocusColor, "UI.ConfigDialog.Font.HighlightColor", "選択中文字色", "选中文本颜色",
            "Lang.cs:423", "lang.emuera-zhs.xml:85", "ConfigDialog.cs:529+775",
        ),
        page(
            ConfigKey.LogColor, "UI.ConfigDialog.Font.LogHistoryColor", "履歴文字色", "历史日志颜色",
            "Lang.cs:427", "lang.emuera-zhs.xml:86", "ConfigDialog.cs:530+776",
        ),
        page(
            ConfigKey.FontName, "UI.ConfigDialog.Font.FontName", "フォント名", "字体名称",
            "Lang.cs:431", "lang.emuera-zhs.xml:87", "ConfigDialog.cs:531+791",
        ),
        page(
            ConfigKey.FontSize, "UI.ConfigDialog.Font.FontSize", "フォントサイズ", "字体大小",
            "Lang.cs:439", "lang.emuera-zhs.xml:89", "ConfigDialog.cs:533+768",
        ),
        page(
            ConfigKey.LineHeight, "UI.ConfigDialog.Font.LineHeight", "一行の高さ", "一行的高度",
            "Lang.cs:443", "lang.emuera-zhs.xml:90", "ConfigDialog.cs:534+765",
        ),

        // -------------------------------------------------------------
        // ConfigDialog · 系统（System）页 —— spec: ConfigDialog.cs:1972-2035
        // -------------------------------------------------------------
        page(
            ConfigKey.IgnoreCase, "UI.ConfigDialog.System.IgnoreCase", "大文字小文字の違いを無視する", "无视文字的大小写",
            "Lang.cs:458", "lang.emuera-zhs.xml:94", "ConfigDialog.cs:537+718",
        ),
        page(
            ConfigKey.UseRenameFile, "UI.ConfigDialog.System.UseRename", "_Rename.csvを利用する", "使用_Rename.csv",
            "Lang.cs:462", "lang.emuera-zhs.xml:95", "ConfigDialog.cs:538+719",
        ),
        page(
            ConfigKey.UseReplaceFile, "UI.ConfigDialog.System.UseReplace", "_Replace.csvを利用する", "使用_Replace.csv",
            "Lang.cs:466", "lang.emuera-zhs.xml:96", "ConfigDialog.cs:539+726",
        ),
        page(
            ConfigKey.SearchSubdirectory, "UI.ConfigDialog.System.SearchSubfolder", "サブディレクトリを検索する", "检索子文件夹",
            "Lang.cs:470", "lang.emuera-zhs.xml:97", "ConfigDialog.cs:540+730",
        ),
        page(
            ConfigKey.SortWithFilename, "UI.ConfigDialog.System.SortFileNames", "読み込み順をファイル名順にソートする", "读取顺序以文件名排序",
            "Lang.cs:474", "lang.emuera-zhs.xml:98", "ConfigDialog.cs:541+731",
        ),
        page(
            ConfigKey.AllowFunctionOverloading, "UI.ConfigDialog.System.SystemFuncOverride", "システム関数の上書きを許可する", "允许覆盖系统函数",
            "Lang.cs:478", "lang.emuera-zhs.xml:99", "ConfigDialog.cs:542+734",
        ),
        page(
            ConfigKey.WarnFunctionOverloading, "UI.ConfigDialog.System.SystemFuncOverrideWarn", "システム関数が上書きされたとき警告を表示する", "系统函数被覆盖时发出警告",
            "Lang.cs:482", "lang.emuera-zhs.xml:100", "ConfigDialog.cs:543+735",
        ),
        page(
            ConfigKey.WarnNormalFunctionOverloading, "UI.ConfigDialog.System.DuplicateFuncWarn", "同名の非イベント関数が複数定義されたとき警告する", "定义了多个同名的非事件函数时发出警告",
            "Lang.cs:486", "lang.emuera-zhs.xml:101", "ConfigDialog.cs:544+737",
        ),
        page(
            ConfigKey.SystemAllowFullSpace, "UI.ConfigDialog.System.WSIncludesFullWidth", "全角スペースをホワイトスペースに含める", "全角空格视为普通空格",
            "Lang.cs:490", "lang.emuera-zhs.xml:102", "ConfigDialog.cs:545+747",
        ),
        page(
            ConfigKey.useLanguage, "UI.ConfigDialog.System.ANSI", "内部で使用する東アジア言語", "程序的非Unicode的言",
            "Lang.cs:494", "lang.emuera-zhs.xml:103", "ConfigDialog.cs:546+865",
            note = "DEFAULT ANSI ENCODING 的页面标签；zhs 译文原文如此（有漏字的翻译缺陷，照抄不修）",
        ),

        // -------------------------------------------------------------
        // ConfigDialog · 系统2（System2）页 —— spec: ConfigDialog.cs:2036-2094
        // -------------------------------------------------------------
        page(
            ConfigKey.SystemIgnoreTripleSymbol, "UI.ConfigDialog.System2.IgnoreTripleSymbol", "FORM中の三連記号を展開しない", "不展开FORM中的三联符号",
            "Lang.cs:509", "lang.emuera-zhs.xml:105", "ConfigDialog.cs:549+751",
        ),
        page(
            ConfigKey.SystemSaveInBinary, "UI.ConfigDialog.System2.SaveInBinary", "セーブデータをバイナリ形式で保存する", "存档以二进制的形式保存",
            "Lang.cs:513", "lang.emuera-zhs.xml:106", "ConfigDialog.cs:550+750",
        ),
        page(
            ConfigKey.ZipSaveData, "UI.ConfigDialog.System2.CompressSave", "セーブデータを圧縮して保存する(バイナリ時のみ)", "存档以压缩的形式保存(仅二进制时)",
            "Lang.cs:517", "lang.emuera-zhs.xml:108", "ConfigDialog.cs:551+759",
            note = "页面标签带「(バイナリ時のみ)」后缀，配置键没有（ConfigData.cs:128 的 Text vs Lang.cs:517）",
        ),
        page(
            ConfigKey.SystemNoTarget, "UI.ConfigDialog.System2.NoAutoCompleteCVar", "キャラクタ変数の引数を補完しない", "不自动补完角色变量的参数",
            "Lang.cs:521", "lang.emuera-zhs.xml:109", "ConfigDialog.cs:552+754",
        ),
        page(
            ConfigKey.ForbidUpdateCheck, "UI.ConfigDialog.System2.DisallowUpdateCheck", "UPDATECHECKを許可しない", "禁止UPDATECHECK",
            "Lang.cs:525", "lang.emuera-zhs.xml:110", "ConfigDialog.cs:553+755",
        ),
        page(
            ConfigKey.UseERD, "UI.ConfigDialog.System2.UseERD", "ERD機能を利用する", "使用ERD功能",
            "Lang.cs:529", "lang.emuera-zhs.xml:111", "ConfigDialog.cs:554+756",
        ),
        page(
            ConfigKey.VarsizeDimConfig, "UI.ConfigDialog.System2.VarsizeDimConfig", "VARSIZEの次元指定をERD機能に合わせる", "VARSIZE使用与ERD相同的维度指定方式",
            "Lang.cs:533", "lang.emuera-zhs.xml:112", "ConfigDialog.cs:555+757",
        ),
        page(
            ConfigKey.ValidExtension, "UI.ConfigDialog.System2.SaveLoadExt", "LOADTEXTとSAVETEXTで使える拡張子", "允许在LOADTEXT和SAVETEXT中使用的扩展名",
            "Lang.cs:537", "lang.emuera-zhs.xml:113", "ConfigDialog.cs:556+903",
        ),
        page(
            ConfigKey.ReplaceContinuationBR, null, "行連結の改行コード置換文字列(\"~\"で括ってください)", null,
            "ConfigDialog.resx:796", null, "ConfigDialog.cs:906+1084",
            note = "无 Lang 成员（TranslateUI 不赋值）；label35 与 textBox4 同面板（ConfigDialog.cs:2053-2054）。页面文本「…置換文字列」与配置键「…の置換文字列」不同源（ConfigData.cs:119 的 Text）",
        ),

        // -------------------------------------------------------------
        // ConfigDialog · 互換性（Compatibility）页 —— spec: ConfigDialog.cs:2095-2165
        // -------------------------------------------------------------
        page(
            ConfigKey.CompatiErrorLine, "UI.ConfigDialog.Compatibility.ExecuteErrorLine", "解釈不可能な行があっても実行する", "执行无法解析的行",
            "Lang.cs:552", "lang.emuera-zhs.xml:118", "ConfigDialog.cs:559+739",
        ),
        page(
            ConfigKey.CompatiCALLNAME, "UI.ConfigDialog.Compatibility.NameForCallname", "CALLNAMEが空文字列の時にNAMEを代入する", "CALLNAME为空时以NAME的值代入",
            "Lang.cs:556", "lang.emuera-zhs.xml:119", "ConfigDialog.cs:560+740",
        ),
        page(
            ConfigKey.CompatiRAND, "UI.ConfigDialog.Compatibility.EramakerRAND", "擬似変数RANDの仕様をeramakerに合わせる", "使虚拟变量RAND的表现与eramaker一致",
            "Lang.cs:560", "lang.emuera-zhs.xml:120", "ConfigDialog.cs:561+743",
        ),
        page(
            ConfigKey.TimesNotRigorousCalculation, "UI.ConfigDialog.Compatibility.EramakerTIMES", "TIMESの計算をeramakerにあわせる", "使TIMES的计算与eramaker一致",
            "Lang.cs:564", "lang.emuera-zhs.xml:121", "ConfigDialog.cs:562+753",
        ),
        page(
            ConfigKey.CompatiFunctionNoignoreCase, "UI.ConfigDialog.Compatibility.NoIgnoreCase", "関数・属性については大文字小文字を無視しない", "函数以及参数的大小写敏感",
            "Lang.cs:568", "lang.emuera-zhs.xml:122", "ConfigDialog.cs:563+746",
        ),
        page(
            ConfigKey.CompatiCallEvent, "UI.ConfigDialog.Compatibility.CallEvent", "イベント関数のCALLを許可する", "允许调用事件函数",
            "Lang.cs:572", "lang.emuera-zhs.xml:123", "ConfigDialog.cs:564+745",
        ),
        page(
            ConfigKey.CompatiSPChara, "UI.ConfigDialog.Compatibility.UseSPCharacters", "SPキャラを使用する", "使用SP角色",
            "Lang.cs:576", "lang.emuera-zhs.xml:124", "ConfigDialog.cs:565+752",
        ),
        page(
            ConfigKey.CompatiLinefeedAs1739, "UI.ConfigDialog.Compatibility.ButtonWarp", "ver1739以前の非ボタン折り返しを再現する", "回调至ver1739以前版本的非按钮换行规则",
            "Lang.cs:580", "lang.emuera-zhs.xml:125", "ConfigDialog.cs:566+744",
            note = "值控件是 checkBoxCompatiLinefeedAs1739；CompatiDRAWLINE 读取期被重定向到本条（ConfigData.cs:544-547），但重定向不改变页面绑定",
        ),
        page(
            ConfigKey.CompatiFuncArgOptional, "UI.ConfigDialog.Compatibility.OmitArgs", "ユーザー関数の全ての引数の省略を許可する", "允许省略用户定义函数的所有参数",
            "Lang.cs:584", "lang.emuera-zhs.xml:126", "ConfigDialog.cs:567+748",
        ),
        page(
            ConfigKey.CompatiFuncArgAutoConvert, "UI.ConfigDialog.Compatibility.AutoTOSTR", "ユーザー関数の引数に自動的にTOSTRを補完する", "对用户定义函数的参数自动补充TOSTR命令",
            "Lang.cs:588", "lang.emuera-zhs.xml:127", "ConfigDialog.cs:568+749",
        ),

        // -------------------------------------------------------------
        // ConfigDialog · 解析（Debug）页 —— spec: ConfigDialog.cs:2166-2255
        // -------------------------------------------------------------
        page(
            ConfigKey.WarnBackCompatibility, "UI.ConfigDialog.Debug.CompatibilityWarn", "eramaker互換性に関する警告を表示する", "与eramaker不兼容时发出警告",
            "Lang.cs:669", "lang.emuera-zhs.xml:131", "ConfigDialog.cs:572+738",
        ),
        page(
            ConfigKey.DisplayReport, "UI.ConfigDialog.Debug.LoadingReport", "ロード時にレポートを表示する", "读取脚本时显示报告",
            "Lang.cs:673", "lang.emuera-zhs.xml:132", "ConfigDialog.cs:573+728",
        ),
        page(
            ConfigKey.CheckDuplicateIdentifier, "UI.ConfigDialog.Debug.CheckDuplicateIdentifier", "ERDの識別子とローカル変数の重複を確認する", null,
            "Lang.cs:677", null, "ConfigDialog.cs:574+758",
            note = "zhs 语言文件里没有这条 id（emuera-zhs.xml 全文 122 条 UI.ConfigDialog 中无 CheckDuplicateIdentifier）→ 中文界面回落日文",
        ),
        page(
            ConfigKey.ReduceArgumentOnLoad, "UI.ConfigDialog.Debug.ReduceArgs", "ロード時に引数を解析する", "读取时解析参数",
            "Lang.cs:604", "lang.emuera-zhs.xml:138", "ConfigDialog.cs:575+803",
            note = "标签是嵌套类 [Translate(\"ロード時に引数を解析する\")] 的类级文本（Lang.cs:604）",
        ),
        page(
            ConfigKey.DisplayWarningLevel, "UI.ConfigDialog.Debug.WarnLevel", "表示する最低警告レベル", "显示的最低警告等级",
            "Lang.cs:623", "lang.emuera-zhs.xml:142", "ConfigDialog.cs:579+817",
            note = "标签是嵌套类 [Translate(\"表示する最低警告レベル\")] 的类级文本（Lang.cs:623）",
        ),
        page(
            ConfigKey.IgnoreUncalledFunction, "UI.ConfigDialog.Debug.IgnoreUnusedFuncs", "呼び出されなかった関数を無視する", "忽略没有被调用的函数",
            "Lang.cs:681", "lang.emuera-zhs.xml:133", "ConfigDialog.cs:584+727",
        ),
        page(
            ConfigKey.FunctionNotFoundWarning, "UI.ConfigDialog.Debug.FuncNotFoundWarn", "関数が見つからない警告の扱い", "找不到函数时的警告处理",
            "Lang.cs:685", "lang.emuera-zhs.xml:134", "ConfigDialog.cs:585+831",
        ),
        page(
            ConfigKey.FunctionNotCalledWarning, "UI.ConfigDialog.Debug.UnusedFuncWarn", "関数が呼び出されなかった警告の扱い", "函数没有被调用时的警告处理",
            "Lang.cs:689", "lang.emuera-zhs.xml:135", "ConfigDialog.cs:590+848",
        ),

        // -------------------------------------------------------------
        // ConfigDialog · 剪贴板（Clipboard）页 —— spec: ConfigDialog.cs:2261-2371
        // -------------------------------------------------------------
        page(
            ConfigKey.CBIgnoreTags, "UI.ConfigDialog.Clipboard.IgnoreTags", "テキスト中の<>タグを無視する", "无视文本中的<>标签",
            "Lang.cs:712", "lang.emuera-zhs.xml:153", "ConfigDialog.cs:598+907",
        ),
        page(
            ConfigKey.CBReplaceTags, "UI.ConfigDialog.Clipboard.ReplaceTags", "<>を次の文で置き換える", "将标签<>替换为以下文字",
            "Lang.cs:716", "lang.emuera-zhs.xml:154", "ConfigDialog.cs:599+908",
            note = "值控件是 textBoxCBReplaceTags（回写 spec: ConfigDialog.cs:1095）",
        ),
        page(
            ConfigKey.CBNewLinesOnly, "UI.ConfigDialog.Clipboard.NewLineOnly", "新しい行のみコピーする", "只复制新行",
            "Lang.cs:720", "lang.emuera-zhs.xml:155", "ConfigDialog.cs:600+909",
        ),
        page(
            ConfigKey.CBClearBuffer, "UI.ConfigDialog.Clipboard.ClearClipboard", "タイトル画面に戻った時にクリップボードとバッファを消去する", "画面清除的同时清除剪贴板与历史记录",
            "Lang.cs:724", "lang.emuera-zhs.xml:156", "ConfigDialog.cs:601+910",
            note = "页面标签「タイトル画面に戻った時に…」与配置键「画面のリフレッシュ時に…」不同源（ConfigData.cs:136 的 Text vs Lang.cs:724）",
        ),
        page(
            ConfigKey.CBTriggerLeftClick, "UI.ConfigDialog.Clipboard.LClick", "左クリック時", "鼠标左键单击时",
            "Lang.cs:732", "lang.emuera-zhs.xml:158", "ConfigDialog.cs:603+911",
        ),
        page(
            ConfigKey.CBTriggerMiddleClick, "UI.ConfigDialog.Clipboard.MClick", "ホイールクリック時", "转动鼠标滚轮时",
            "Lang.cs:736", "lang.emuera-zhs.xml:159", "ConfigDialog.cs:604+912",
        ),
        page(
            ConfigKey.CBTriggerDoubleLeftClick, "UI.ConfigDialog.Clipboard.DoubleClick", "ダブルクリック時", "双击鼠标左键时",
            "Lang.cs:740", "lang.emuera-zhs.xml:160", "ConfigDialog.cs:605+913",
        ),
        page(
            ConfigKey.CBTriggerAnyKeyWait, "UI.ConfigDialog.Clipboard.AnyKeyWait", "WAIT時", "WAIT时",
            "Lang.cs:744", "lang.emuera-zhs.xml:161", "ConfigDialog.cs:606+914",
            note = "英文标签带尾随空格（ConfigData.cs:140），英文形键（含语料 :86 写出的那一行）永远匹配不上；日文形可匹配。键形查询口径见本对象 KDoc",
        ),
        page(
            ConfigKey.CBTriggerInputWait, "UI.ConfigDialog.Clipboard.InputWait", "INPUT時", "INPUT时",
            "Lang.cs:748", "lang.emuera-zhs.xml:162", "ConfigDialog.cs:607+915",
        ),
        page(
            ConfigKey.CBMaxCB, "UI.ConfigDialog.Clipboard.LinesToClipboard", "クリップボードに貼り付ける行数", "复制到剪贴板的行数",
            "Lang.cs:752", "lang.emuera-zhs.xml:163", "ConfigDialog.cs:608+916",
        ),
        page(
            ConfigKey.CBBufferSize, "UI.ConfigDialog.Clipboard.TotalBuffer", "総バッファサイズ", "总缓存大小",
            "Lang.cs:756", "lang.emuera-zhs.xml:164", "ConfigDialog.cs:609+917",
        ),
        page(
            ConfigKey.CBScrollCount, "UI.ConfigDialog.Clipboard.LinesToScroll", "スクロールの行数", "滚动行数",
            "Lang.cs:760", "lang.emuera-zhs.xml:165", "ConfigDialog.cs:610+918",
        ),
        page(
            ConfigKey.CBMinTimer, "UI.ConfigDialog.Clipboard.UpdateTime", "クリップボードの更新間隔(ミリ秒)", "剪贴板的更新间隔(毫秒)",
            "Lang.cs:764", "lang.emuera-zhs.xml:166", "ConfigDialog.cs:611+919",
        ),

        // -------------------------------------------------------------
        // ConfigDialog · Rikai 页 —— spec: ConfigDialog.cs:2372-2394
        // -------------------------------------------------------------
        page(
            ConfigKey.RikaiEnabled, "UI.ConfigDialog.Rikai.RikaiEnable", "ポップアップ翻訳辞書を有効にする(日本語→英語)", null,
            "Lang.cs:779", null, "ConfigDialog.cs:614+920",
            note = "zhs 语言文件没有 Rikai 族 id（122 条 UI.ConfigDialog 里一条都无；英文文件里有，emuera-eng.xml:1046）→ 中文界面回落日文",
        ),
        page(
            ConfigKey.RikaiFilename, "UI.ConfigDialog.Rikai.RikaiFilename", "Dictionaryファイルのパス(Emuera本体との相対パス)", null,
            "Lang.cs:783", null, "ConfigDialog.cs:615+924",
            note = "同上：zhs 无该 id；值控件是 rikaiDictFilenameTextBox（回写 spec: ConfigDialog.cs:1111）",
        ),
        page(
            ConfigKey.RikaiColorBack, "UI.ConfigDialog.Font.BackgroundColor", "背景色", "背景颜色",
            "Lang.cs:415", "lang.emuera-zhs.xml:83", "ConfigDialog.cs:616+922",
            note = "原版 Rikai 页复用字体页译文（ConfigDialog.cs:616 直接引 Font.BackgroundColor）",
        ),
        page(
            ConfigKey.RikaiColorText, "UI.ConfigDialog.Font.TextColor", "文字色", "文本颜色",
            "Lang.cs:419", "lang.emuera-zhs.xml:84", "ConfigDialog.cs:617+923",
            note = "原版 Rikai 页复用字体页译文（ConfigDialog.cs:617 直接引 Font.TextColor）",
        ),
        page(
            ConfigKey.RikaiUseSeparateBoxes, "UI.ConfigDialog.Rikai.RikaiSeparateBox", "翻訳中の語句を強調表示する", null,
            "Lang.cs:787", null, "ConfigDialog.cs:618+921",
            note = "同上：zhs 无该 id",
        ),

        // -------------------------------------------------------------
        // DebugConfigDialog（debug.config 的设置页，-Debug 启动）—— spec: DebugConfigDialog.cs:85-99、:147-153
        // -------------------------------------------------------------
        page(
            ConfigKey.DebugShowWindow, "UI.DebugConfigDialog.OpenDebugWindowOnStartup", "起動時にデバッグウインドウを表示する", "启动时打开调试窗口",
            "Lang.cs:852", "lang.emuera-zhs.xml:172", "DebugConfigDialog.cs:88+147",
        ),
        page(
            ConfigKey.DebugWindowTopMost, "UI.DebugConfigDialog.AlwaysOnTop", "デバッグウインドウを最前面に表示する", "调试时窗口总是在最前面",
            "Lang.cs:856", "lang.emuera-zhs.xml:173", "DebugConfigDialog.cs:89+148",
        ),
        page(
            ConfigKey.DebugWindowWidth, "UI.DebugConfigDialog.WindowWidth", "デバッグウィンドウ幅", "调试窗口宽度",
            "Lang.cs:860", "lang.emuera-zhs.xml:174", "DebugConfigDialog.cs:90+150",
        ),
        page(
            ConfigKey.DebugWindowHeight, "UI.DebugConfigDialog.WindowHeight", "デバッグウィンドウ高さ", "调试窗口高度",
            "Lang.cs:864", "lang.emuera-zhs.xml:175", "DebugConfigDialog.cs:91+151",
        ),
        page(
            ConfigKey.DebugSetWindowPos, "UI.DebugConfigDialog.SetWindowPos", "デバッグウィンドウ位置を指定する", "调试窗口在固定位置开启",
            "Lang.cs:868", "lang.emuera-zhs.xml:176", "DebugConfigDialog.cs:93+149",
        ),
        page(
            ConfigKey.DebugWindowPosX, "UI.DebugConfigDialog.WindowX", "デバッグウィンドウ位置X", "调试窗口位置X",
            "Lang.cs:872", "lang.emuera-zhs.xml:177", "DebugConfigDialog.cs:94+152",
        ),
        page(
            ConfigKey.DebugWindowPosY, "UI.DebugConfigDialog.WindowY", "デバッグウィンドウ位置Y", "调试窗口位置Y",
            "Lang.cs:876", "lang.emuera-zhs.xml:178", "DebugConfigDialog.cs:95+153",
        ),
    )

    private val byKeyMap: Map<ConfigKey, ConfigPageLabel> = all.associateBy { it.key }

    /** 按条目键直查 —— 设置页最顺手的入口（控件绑定本来就是 ConfigCode 寻址）。 */
    fun byKey(key: ConfigKey): ConfigPageLabel? = byKeyMap[key]

    /**
     * 键形查询：给定 `emuera.config` 一侧的键文本（英文标签如 `DEFAULT ANSI ENCODING`、
     * 日文标签如 `内部で使用する東アジア言語`、或大小写任意混写的同一文本），
     * 返回该条目的页面标签。
     *
     * 匹配口径见本对象 KDoc（trim + 大写化 + 三级查找，照抄 `GetItem(string)`，spec:
     * `ConfigData.cs:236-248`）。**未知键返回 null**；键能解析但条目不在设置页上
     * （如 `SKIP FRAMES`、`Currency symbol`）同样返回 null —— 表里没有的东西不编造。
     */
    fun labelFor(keyText: String): ConfigPageLabel? = specFor(keyText)?.let { byKeyMap[it.key] }

    /**
     * 键形 → 条目键。同 [labelFor] 的匹配口径，但**不要求**该条目有页面标签 ——
     * 用于区分 [labelFor] 的三种 null：「键不认识」（这里也 null）与
     * 「键认识、条目没上设置页」（这里非 null、labelFor 却是 null）。
     *
     * 注意 `emuera.config` 的文件读取只走第一级（spec: `ConfigData.cs:539` 直接调
     * `GetConfigItem`，replace / debug 两组在 emuera.config 里读不到，spec: `ConfigData.cs:540-543`
     * 静默跳过）；这里的第三级是给 `GETCONFIG` 一族与 debug.config 用的（spec: `ConfigData.cs:236-248`）。
     */
    fun configKeyFor(keyText: String): ConfigKey? = specFor(keyText)?.key

    /**
     * 设置页一个条目的**控件标题** —— GAME / DEBUG / REPLACE 三组共用的唯一显示口径。
     *
     *   * [english] = true：取配置文件键的英文标签 [ConfigSpec.enLabel]（设置页的
     *     「英文标签（配置键原文）」开关，spec: `AConfigItem.cs:23`）；
     *   * 否则先查本表的页面文案 [ConfigPageLabel.text]（简体中文优先、缺译回落日语，
     *     spec: `Lang.cs:23-33` 的 `TranslatableString.Text`）；
     *   * 条目不在本表里（[byKey] 为 null）就回落配置文件键的日文标签 [ConfigSpec.jpLabel]。
     *
     * DEBUG 组 7 条都有 `DebugConfigDialog` 页面文案（spec: `DebugConfigDialog.cs:85-99`）；
     * REPLACE 组 16 条在原版**没有设置对话框**（`_Replace.csv` 只由 `Process.cs:240-246` 读），
     * 本表按构造期自检不登记它们 —— 中文界面显示日文配置键、英文开关下显示英文配置键，
     * 不编造页面文案（ADR-005）。
     */
    fun displayLabel(spec: ConfigSpec, zhHans: Boolean, english: Boolean): String {
        if (english) {
            return spec.enLabel
        }
        return byKey(spec.key)?.text(zhHans) ?: spec.jpLabel
    }

    /**
     * `GetItem(string)` 三级查找（spec: `ConfigData.cs:236-248` + 三组各自的比较范围
     * `:262-284` / `:298-316` / `:330-348`）。大写化与裁剪对应读文件路径的
     * `array[0].Trim()`（:539）与 `ToUpper(InvariantCulture)`（:264）。
     */
    private fun specFor(keyText: String): ConfigSpec? {
        val upperKey = keyText.trim().uppercase(Locale.ROOT)
        if (upperKey.isEmpty()) {
            return null // 空键在 C# 侧同样什么都匹配不上
        }
        for (spec in ConfigSpecTable.all) {
            val withEnglishLabel = spec.group === ConfigGroup.GAME // 只有 configArray 认 EngText（ConfigData.cs:277）
            if (spec.matchesUpperKey(upperKey, withEnglishLabel)) {
                return spec
            }
        }
        return null
    }

    init {
        require(all.map { it.key }.distinct().size == all.size) { "页面标签表有重复键" }
        all.forEach { entry ->
            require((entry.zhsLabel == null) == (entry.zhsSource == null)) {
                "${entry.key} 的 zhsLabel 与 zhsSource 空值不配对"
            }
            if (entry.langId != null) {
                require(
                    entry.langId.startsWith("UI.ConfigDialog.") ||
                        entry.langId.startsWith("UI.DebugConfigDialog."),
                ) { "${entry.key} 的 langId 形状不对：${entry.langId}" }
            }
            val spec = ConfigSpecTable.specOf(entry.key) ?: error("${entry.key} 在条目表里不存在")
            require(spec.group !== ConfigGroup.REPLACE) {
                "${entry.key} 属于 _Replace.csv（replaceArray），不应当有设置页标签"
            }
        }
        // 完整性钉死：表里每个键都来自条目表；「哪些条目没有页面标签」
        // 由测试 `不在设置页上的 configArray 条目恰好是这九个` 逐条登记。
        val registeredKeys = ConfigSpecTable.all.map { it.key }.toSet()
        require(all.all { it.key in registeredKeys }) { "页面标签表混入了条目表之外的键" }
    }

    private fun page(
        key: ConfigKey,
        langId: String?,
        ja: String,
        zhs: String?,
        jaSrc: String,
        zhsSrc: String?,
        bind: String,
        note: String? = null,
    ): ConfigPageLabel = ConfigPageLabel(key, langId, ja, zhs, jaSrc, zhsSrc, bind, note)
}

/**
 * 一条**读取期夹取提示**（[ConfigClampNotice]）的中日文案 —— 设置页把提示本地化时的一行。
 *
 * 原版的提示文本都是 `Lang.MessageBox.` 下的 `TranslatableString`（spec: `Config.cs:431-460`）：
 * 装了 zh-Hans 语言文件就用 `lang.emuera-zhs.xml` 里同 id 的译文，装了英文文件就用
 * `lang.emuera-eng.xml` 的同 id 译文，都没有译文就回落日语原文
 * （`TranslatableString.Text` 的回落链，spec: `Lang.cs:23-33`）。本类逐条登记这三层文本与出处。
 *
 * 映射键是 [kind] 而不是条目键：[ConfigKey.SaveDataNos] 的上下限是两条不同的提示
 * （spec: `Config.cs:441-450`），同一个条目键区分不了它们。
 *
 * @property kind 提示种类 —— 与本表一一对应
 * @property key 产生该提示的配置条目（spec: [EffectiveConfig.of] 的夹取点）
 * @property jaText 日语原文（`Lang.cs` 里 `TranslatableString` 的构造实参）
 * @property zhsText 简体中文译文；原版 zhs 文件没有该消息 id 时为 null
 * @property enText 英文译文；原版 eng 文件没有该消息 id 时为 null
 * @property jaSource [jaText] 的出处
 * @property zhsSource [zhsText] 的出处；与 [zhsText] 严格同空、同非空
 * @property enSource [enText] 的出处；与 [enText] 严格同空、同非空
 * @property titleJa 对话框标题的日语原文；WINAPI 那条走 `MessageBox.Show(正文)`，没有标题
 *   （spec: `Config.cs:458`），为 null
 * @property titleZhs 对话框标题的简体中文译文；同上，WINAPI 那条为 null
 * @property titleEn 对话框标题的英文译文；同上，WINAPI 那条为 null
 */
data class ConfigClampLabel(
    val kind: ConfigClampNotice.Kind,
    val key: ConfigKey,
    val jaText: String,
    val zhsText: String?,
    val enText: String?,
    val jaSource: String,
    val zhsSource: String?,
    val enSource: String?,
    val titleJa: String?,
    val titleZhs: String?,
    val titleEn: String?,
) {

    /**
     * 与 [ConfigPageLabel.text] 同一条回落链（spec: `Lang.cs:23-33` 的 `TranslatableString.Text`）：
     * 有该语种译文就用译文，没有就回落日语原文；日语恒为原文。
     */
    fun text(language: ConfigClampLanguage): String = when (language) {
        ConfigClampLanguage.JAPANESE -> jaText
        ConfigClampLanguage.CHINESE_SIMPLIFIED -> zhsText ?: jaText
        ConfigClampLanguage.ENGLISH -> enText ?: jaText
    }

    /** 标题的同口径读法；[titleJa] 为 null（原版这条提示没有标题）时恒为 null。 */
    fun title(language: ConfigClampLanguage): String? {
        if (titleJa == null) {
            return null
        }
        return when (language) {
            ConfigClampLanguage.JAPANESE -> titleJa
            ConfigClampLanguage.CHINESE_SIMPLIFIED -> titleZhs ?: titleJa
            ConfigClampLanguage.ENGLISH -> titleEn ?: titleJa
        }
    }
}

/**
 * 夹取提示可显示的语种 —— 原版三份语言文件的覆盖范围
 * （`Lang.cs` 日语基准、`lang.emuera-zhs.xml`、`lang.emuera-eng.xml`）。
 *
 * 原版按装了什么语言文件决定 `TranslatableString.Text` 的返回值（spec: `Lang.cs:23-33`）；
 * 本枚举只给调用方选列，回落规则在 [ConfigClampLabel.text] 里。
 */
enum class ConfigClampLanguage {

    /** `Lang.cs` 的构造实参（原版基准语言，永远有文案）。 */
    JAPANESE,

    /** `lang.emuera-zhs.xml` 的 `tr` 行。 */
    CHINESE_SIMPLIFIED,

    /** `lang.emuera-eng.xml` 的 `tr` 行。 */
    ENGLISH,
}

/**
 * 六种夹取提示的「种类 → 原文 / 简中 / 英文」表与只读查询函数（CONFIG-REST）。
 *
 * 覆盖范围 = [ConfigClampNotice.Kind] 的全部成员（`Config.cs:431-460` 的 5 个
 * `Dialog.Show` + 1 个 `MessageBox.Show`）：
 *   * 正文日语原文与 `Lang.cs` 的 `MessageBox.` 构造行逐字对应；
 *   * 正文简体中文取自 `lang.emuera-zhs.xml` 的同 id 行；
 *   * 正文英文取自 `lang.emuera-eng.xml` 的同 id 行；
 *   * 标题 `ConfigError`「設定のエラー」的译文是「设定错误」（spec: `lang.emuera-zhs.xml:947`）
 *     与 "Config Error"（spec: `lang.emuera-eng.xml:950`）。
 *
 * **原版 zhs 与 eng 文件都没有 `MessageBox.DoNotSupportWINAPI`** ——
 * WINAPI 提示在任何非日语界面都回落日文原文，这是 `TranslatableString.Text` 的既定行为
 * （spec: `Lang.cs:23-33`），本表如实登记 [ConfigClampLabel.zhsText] / [ConfigClampLabel.enText]
 * 为 null，不编造译文。
 *
 * 未收录的种类（将来新增 Kind 时）由 [textFor] / [titleFor] 原样回传
 * [ConfigClampNotice.messageJp] / [ConfigClampNotice.titleJp]，与 `TranslatableString.Text`
 * 「没有译文用原文」同一条规则。
 */
object ConfigClampLabels {

    /** `Lang.MessageBox.ConfigError` 的简体中文译文（spec: `lang.emuera-zhs.xml:947`）。 */
    const val CONFIG_ERROR_TITLE_ZH = "设定错误"

    /** `Lang.MessageBox.ConfigError` 的英文译文（spec: `lang.emuera-eng.xml:950`）。 */
    const val CONFIG_ERROR_TITLE_EN = "Config Error"

    /**
     * 全表，顺序 = `Config.cs:431-460` 的夹取顺序（与原版弹框顺序一致）。
     * 完整性由构造期自检钉住：种类集合必须恰好等于 [ConfigClampNotice.Kind] 的全部成员。
     */
    val all: List<ConfigClampLabel> = listOf(
        clamp(
            ConfigClampNotice.Kind.TOO_SMALL_FONT_SIZE, ConfigKey.FontSize,
            "フォントサイズが小さすぎます(8が下限)",
            "字体大小过小(下限为8)",
            "Font size is too small (Less than 8).",
            "Lang.cs:4034", "lang.emuera-zhs.xml:948", "lang.emuera-eng.xml:951",
        ),
        clamp(
            ConfigClampNotice.Kind.LINE_HEIGHT_LESS_THAN_FONT_SIZE, ConfigKey.LineHeight,
            "行の高さがフォントサイズより小さいため、フォントサイズと同じ高さと解釈されます",
            "由于行高小于字体大小，将其视为与字体大小等同",
            "The line height will be interpreted the same as the font size due to it being smaller.",
            "Lang.cs:4038", "lang.emuera-zhs.xml:949", "lang.emuera-eng.xml:952",
        ),
        clamp(
            ConfigClampNotice.Kind.TOO_SMALL_DISPLAY_SAVE_DATA, ConfigKey.SaveDataNos,
            "表示するセーブデータ数が少なすぎます(20が下限)",
            "显示的存档数据数量过小(下限为20)",
            "Amount of save data to display is too small (Less than 20).",
            "Lang.cs:4042", "lang.emuera-zhs.xml:950", "lang.emuera-eng.xml:953",
        ),
        clamp(
            ConfigClampNotice.Kind.TOO_LARGE_DISPLAY_SAVE_DATA, ConfigKey.SaveDataNos,
            "表示するセーブデータ数が多すぎます(80が上限)",
            "显示的存档数据数量过大(上限为80)",
            "Amount of save data to display exceeds the limit (More than 80).",
            "Lang.cs:4046", "lang.emuera-zhs.xml:951", "lang.emuera-eng.xml:954",
        ),
        clamp(
            ConfigClampNotice.Kind.TOO_SMALL_LOG_SIZE, ConfigKey.MaxLog,
            "ログ表示行数が少なすぎます(500が下限)",
            "日志显示行数过少(下限为500)",
            "Log size is too small (Less than 500).",
            "Lang.cs:4050", "lang.emuera-zhs.xml:952", "lang.emuera-eng.xml:955",
        ),
        // WINAPI：zhs / eng 语言文件都没有 MessageBox.DoNotSupportWINAPI 这个 id，
        // 非日语界面回落日文原文（Lang.cs:23-33）；这条走 MessageBox.Show(正文)，没有标题（Config.cs:458）。
        clamp(
            ConfigClampNotice.Kind.WINAPI_UNSUPPORTED, ConfigKey.TextDrawingMode,
            "WINAPIモードはサポートされていません", null, null,
            "Lang.cs:4214", null, null,
            titleJa = null, titleZhs = null, titleEn = null,
        ),
    )

    private val byKindMap: Map<ConfigClampNotice.Kind, ConfigClampLabel> =
        all.associateBy { it.kind }

    /** 按提示种类直查；未知种类返回 null（不编造文案）。 */
    fun labelFor(kind: ConfigClampNotice.Kind): ConfigClampLabel? = byKindMap[kind]

    /**
     * 提示正文的显示文本。
     *
     * @param language 界面语种；该列缺译与未知种类都回落 [ConfigClampNotice.messageJp]
     *   （spec: `Lang.cs:23-33`）
     */
    fun textFor(notice: ConfigClampNotice, language: ConfigClampLanguage): String =
        labelFor(notice.kind)?.text(language) ?: notice.messageJp

    /**
     * 提示框标题的显示文本；原版这条提示没有标题时（`Config.cs:458` 的 WINAPI 提示）
     * 返回 null，调用方应当不显示标题。
     *
     * 未知种类回落 [ConfigClampNotice.titleJp]；该语种缺标题译文时同样回落日语原文。
     */
    fun titleFor(notice: ConfigClampNotice, language: ConfigClampLanguage): String? {
        if (notice.titleJp == null) {
            return null
        }
        val label = labelFor(notice.kind) ?: return notice.titleJp
        return label.title(language)
    }

    init {
        require(all.map { it.kind }.distinct().size == all.size) { "夹取提示文案表有重复种类" }
        require(all.map { it.kind }.toSet() == ConfigClampNotice.Kind.entries.toSet()) {
            "夹取提示文案表必须覆盖 ConfigClampNotice.Kind 的全部成员"
        }
        all.forEach { label ->
            require(label.jaText.isNotEmpty()) { "${label.kind} 的日语正文为空" }
            require((label.zhsText == null) == (label.zhsSource == null)) {
                "${label.kind} 的 zhsText 与 zhsSource 空值不配对"
            }
            require((label.enText == null) == (label.enSource == null)) {
                "${label.kind} 的 enText 与 enSource 空值不配对"
            }
            require((label.titleJa == null) == (label.titleZhs == null)) {
                "${label.kind} 的标题中日文空值不配对"
            }
            require((label.titleJa == null) == (label.titleEn == null)) {
                "${label.kind} 的标题中英文空值不配对"
            }
        }
    }

    private fun clamp(
        kind: ConfigClampNotice.Kind,
        key: ConfigKey,
        ja: String,
        zhs: String?,
        en: String?,
        jaSrc: String,
        zhsSrc: String?,
        enSrc: String?,
        titleJa: String? = EffectiveConfig.CONFIG_ERROR_TITLE_JP,
        titleZhs: String? = CONFIG_ERROR_TITLE_ZH,
        titleEn: String? = CONFIG_ERROR_TITLE_EN,
    ): ConfigClampLabel = ConfigClampLabel(
        kind, key, ja, zhs, en, jaSrc, zhsSrc, enSrc, titleJa, titleZhs, titleEn,
    )
}