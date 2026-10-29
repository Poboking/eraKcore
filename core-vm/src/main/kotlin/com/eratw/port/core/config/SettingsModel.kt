package com.eratw.port.core.config

/**
 * 设置页条目的**类型分类** —— 原版设置对话框按控件种类分派（勾选框 / 数值框 / 文本框 / 下拉框），
 * 四个种类与 [ConfigValueType] 的 13 个成员是多对一：
 *   * [BOOL] —— `ConfigItem<bool>`：勾选框，写回是 `SetValue(checkBox.Checked)`（spec: `ConfigDialog.cs:931`）；
 *   * [INT] —— `ConfigItem<int>` 与 `ConfigItem<long>`：数值框，写回是 `SetValue((int)numericUpDown.Value)`
 *     （spec: `ConfigDialog.cs:1103`，对话框里没有 long 型控件，[ConfigKey.LastKey] 不上设置页）；
 *   * [STRING] —— 文本 / 颜色 / 列表 / 单字符：文本框、颜色框（spec: `ConfigDialog.cs:1081`、`:1112`）；
 *   * [ENUM] —— 5 个枚举型条目：下拉框（spec: `ConfigDialog.cs:1069` 的 `SetValue(TextEditorType.SAKURA)`）。
 *
 * 需要精确类型时读 [SettingsEntry.valueType]，本分类只给控件分派用。
 */
enum class SettingsKind {
    BOOL,
    INT,
    STRING,
    ENUM,
    ;

    companion object {

        /** [ConfigValueType] → 控件分类。映射是满射，13 个成员一个不漏。 */
        fun of(type: ConfigValueType): SettingsKind = when (type) {
            ConfigValueType.BOOL -> BOOL
            ConfigValueType.INT, ConfigValueType.LONG -> INT
            ConfigValueType.STRING, ConfigValueType.CHAR, ConfigValueType.COLOR,
            ConfigValueType.STRING_LIST, ConfigValueType.LONG_LIST -> STRING
            ConfigValueType.TEXT_DRAWING_MODE_ENUM, ConfigValueType.REDUCE_ARGUMENT_ENUM,
            ConfigValueType.DISPLAY_WARNING_ENUM, ConfigValueType.USE_LANGUAGE_ENUM,
            ConfigValueType.TEXT_EDITOR_TYPE_ENUM -> ENUM
        }
    }
}

/**
 * 一条配置改动的**作用域** —— 设置页保存后能否不重开会话就生效（M5-CONFIG 运行时重载口径）。
 *
 * 判据**不是**原版「`Config` 静态字段用时读」的口径 —— 那对 126 个字段全都成立（`Config.cs`
 * 的属性全是 `static` 自动属性，引擎随便哪个使用点都能读），区分不出键。判据是**移植侧**
 * 运行期到底从哪里读这个键：
 *   * [HOT] —— 装配完成后仍**逐次现读** [ConfigStore] 条目，或根本没有读取点；
 *     新值写进同一份 store 后下一个使用点即读到，无需重开会话；
 *   * [RESTART] —— 值在 `GameSession` 装配期被**烘死**进控制台 / 语句层 / 存档层 / 变量池
 *     （构造参数快照、变量池灌值、`by lazy` 快照），或只被装载期消费。
 *
 * **一个键同时有现读点与烘死点时按 [RESTART] 判**（不做「一半新一半旧」的假热更）——
 * 这是本表最容易被误判的一类，逐条列在 [HOT_KEYS] 的 KDoc 里。
 *
 * 原版两个按钮本身是「一律重启生效」（spec: `ConfigDialog.cs:665-668` 的 `buttonSave_Click`
 * 只落盘、`:672-675` 的 `buttonReboot_Click` 落盘 + `SaveReboot` ⇒ `MainWindow.cs:1066-1069`
 * 的 `Reboot()`，实体是 `:1022-1026` 的 `Process.Start` + `Application.ExitThread`）；
 * 但原版自己也有活推先例：`ConfigDialog.cs:1107-1109` 保存时把三个剪贴板参数直接推给
 * `GlobalStatic.Console.CBProc`。本表让移植侧把 [HOT] 键从重启里择出来。
 */
enum class ConfigApplyMode {

    /** 热更：保存后刷 [ConfigStore] 即生效，同一会话的下一个使用点读新值。 */
    HOT,

    /** 重启：装配期烘死，必须重开会话（原版 `Reboot()` 的壳内等价物）才吃到。 */
    RESTART,
    ;

    companion object {

        /**
         * 热更键全集 —— **表外一律 [RESTART]**（默认重启，逐键申请热更）。
         *
         * 每条挂一个移植侧的运行期现读点，或说明它压根没有读取点：
         *   * `MaxShopItem` —— `core/proc/ShopHostModel.kt:47` 的 `maxShopItem()` 每次商店输入
         *     现读（`GameSession.kt:400` 把同一份条目表交给 `StoreShopHostModel`；
         *     原版 `Config.MaxShopItem` 在 `Config.cs:205`，`_Replace.csv` 灌入见 `Config.cs:719-741`）；
         *   * `FontSize` / `WindowX` —— 字号与窗口宽，运行期现读点是 HTML 折行宽度：
         *     `core/fn/HtmlMethods.kt:563` 每次 `HTML_PRINT` 都跑
         *     `EffectiveConfig.of(store).drawableWidth`，而 `drawableWidth = WindowX - max(2, FontSize/6)`
         *     （`EffectiveConfig.recompute`，spec: `Config.cs:461-466`）。
         *     控制台**不消费**这两项（`GameSession.kt:270-281` 只喂 `fontName` / `maxLines` /
         *     `printCWidth` / `buttonWrap`，宽度由 app 侧画布给），所以没有烘死点；
         *     TODO(verify)：字号对正文渲染的影响由 app 侧 `UiPrefs.fontColumns` 决定（不走本表），
         *     本项热更只覆盖 HTML 布局宽度与夹取提示，渲染层接线后重判；
         *   * `LineHeight` / `WindowY` —— 纯读键：移植侧除注册外没有任何消费点（控制台不吃行高，
         *     窗口高度由 app 侧画布给），改它只需让 [EffectiveConfig] 的派生字段重算；
         *   * `SkipFrame` —— 跳过类（ERB 侧 `MESSKIP` / `ISSKIP` 家族读的是每 VM 的
         *     `skipState` 槽，不是配置，见 `core/fn/InputMethods.kt:59-69`）。`SkipFrame` 本身
         *     在移植侧只有注册（`ConfigSpec.kt:130`）没有任何消费点，改它不牵动已装配结构；
         *   * `CBMaxCB` / `CBScrollCount` / `CBMinTimer` —— 原版保存时**活推**剪贴板处理器
         *     （spec: `ConfigDialog.cs:1107-1109` → `ClipboardProcessor` 的三个 setter）。
         *     移植侧剪贴板面尚未接线，按原版口径登记为热更。
         *     TODO(verify)：`CBUseClipboard` 一族接线后补一条同会话读数对拍。
         *
         * **有现读点却被判 [RESTART] 的三个反例**（同时有烘死点，按「不假称热更」判）：
         *   * `FontName` —— GDRAWTEXT 现读（`core/fn/GraphicsMethods.kt:1761` 的 `configFontName`，
         *     原版 `FunctionMethodCreator.cs:6522`），但控制台默认字体是构造参数
         *     （`GameSession.kt:281` → `core/host/LineBufferConsoleSink.kt:110` 的 `defaultFontName`）
         *     ⇒ 热更会造出「GDRAWTEXT 新字体、正文旧字体」；
         *   * `SystemAllowFullSpace` / `SystemIgnoreTripleSymbol` —— 逐次现读的点有三处
         *     （`core/fn/PureMethods.kt:1667` 的 STRFORM、`core/fn/VarMethods.kt:714` 的
         *     `GETVAR` 动态表达式、`core/fn/VarWriteMethods.kt:219` 的 `SETVAR`），但
         *     `core/proc/ProcessRuntime.kt:375` 的 `runtimeLexerConfig` 是 `by lazy` 快照
         *     ⇒ PRINTFORMS 二段展开与 RETURNFORM 吃首次值；
         *   * `MoneyLabel` / `MoneyFirst` —— `MONEYSTR` 现读（`core/proc/BootHosts.kt:350-352`），
         *     但 `%MONEYLABEL%` 伪变量读的是装配期灌进变量池的快照
         *     （`GameSession.kt:300` → `core/vars/VariableStore.kt:1412`）。
         *
         * 表外典型重启键（判据反例，防误迁）：
         *   * `PrintCPerLine` / `PrintCLength` / `MaxLog` / `ButtonWrap` —— 烘进控制台与语句层
         *     （`GameSession.kt:270-281`、`:420-422`）；
         *   * `SaveDataNos` / `SystemSaveInBinary` / `ZipSaveData` / `UseSaveFolder` —— 烘进存档层
         *     （`GameSession.kt:368-379` 的 `SaveConfig` 与 `SaveWiring`）；
         *   * `ComAbleDefault` / `AutoSave` / `UseERD` / `TimesNotRigorousCalculation` —— 分别烘进
         *     BEGIN 宿主、关键字表、语句层语义快照（`GameSession.kt:354`、`:395-396`、`:422`）。
         */
        val HOT_KEYS: Set<ConfigKey> = setOf(
            ConfigKey.MaxShopItem,
            ConfigKey.FontSize,
            ConfigKey.LineHeight,
            ConfigKey.WindowX,
            ConfigKey.WindowY,
            ConfigKey.SkipFrame,
            ConfigKey.CBMaxCB,
            ConfigKey.CBScrollCount,
            ConfigKey.CBMinTimer,
        )

        /** 单键作用域；未登记键默认 [RESTART]。 */
        fun of(key: ConfigKey): ConfigApplyMode = if (key in HOT_KEYS) HOT else RESTART

        /**
         * 把一组改动键按作用域分成两桶，**保持输入顺序**（调用方传注册顺序即得注册顺序）。
         * 空输入返回两个空桶 —— 「没改任何键」与「改了但都需重启」由 [SettingsSaveImpact] 区分。
         */
        fun split(keys: Collection<ConfigKey>): SettingsSaveImpact {
            val hot = ArrayList<ConfigKey>()
            val restart = ArrayList<ConfigKey>()
            for (key in keys) {
                if (of(key) == HOT) hot += key else restart += key
            }
            return SettingsSaveImpact(hot = hot, restart = restart)
        }
    }
}

/**
 * 一次保存的**影响面** —— 设置页拿它决定「写盘后原地刷新」还是「写盘 + 重开会话」。
 *
 * 两桶都保持注册顺序（[ConfigStore.items] 的顺序，spec: `ConfigData.cs:45-174`），
 * 便于界面按原版设置页的页序提示。
 *
 * @property hot 可以热更的键（[ConfigApplyMode.HOT]）
 * @property restart 必须重开会话的键（[ConfigApplyMode.RESTART]）
 */
data class SettingsSaveImpact(
    val hot: List<ConfigKey>,
    val restart: List<ConfigKey>,
) {

    /** 本次保存没有改动任何键。 */
    val isEmpty: Boolean get() = hot.isEmpty() && restart.isEmpty()

    /** 有键必须重开会话才生效 —— 原版 `SaveReboot`（spec: `ConfigDialog.cs:672-675`）那条路。 */
    val requiresRestart: Boolean get() = restart.isNotEmpty()

    /** 全部改动都能原地刷新（非空且无重启键）—— 可以不给壳发重启信号。 */
    val hotOnly: Boolean get() = restart.isEmpty() && hot.isNotEmpty()
}

/**
 * 一个条目的**输入约束** —— [SettingsModel.set] 的拒绝依据。
 *
 * 三类约束各有出处：
 *   * [IntBounds] —— `ConfigDialog` 构造期给每个数值框设的 `Minimum` / `Maximum`
 *     （spec: `ConfigDialog.cs:433-464`，本仓已镜像为 [ConfigSpec.guiIntRange]）。原版数值框在控件层就把
 *     值夹住，用户不可能提交区间外的数；本层把它变成显式拒绝（见 [IntBounds] 的「区间撑开」说明）。
 *   * [Choices] —— 枚举型条目的常量表（spec: `ConfigItem.cs:172-211` 的 `Enum.TryParse`）。
 *   * [TextLength] —— 只有 `char` 型有（`char.TryParse` 只认长度为 1 的串，spec: `ConfigItem.cs:119-126`）。
 *
 * 颜色型（`Color`）与列表型不带约束：0..255 的分量与列表元素在解析处就校验
 * （spec: `ConfigItem.cs:252-274`、`:145-171`），不在本模型另立一套区间。
 */
sealed interface SettingsConstraint {

    /** 无输入约束（字符串、颜色、列表等）。 */
    data object None : SettingsConstraint

    /**
     * 整数的可用区间。
     *
     * 原版数值框的区间不是固定值：`setNumericUpDown` 在打开对话框时会把 `Minimum` / `Maximum`
     * **撑开**以容纳配置文件里的现值（spec: `ConfigDialog.cs:696-703`），所以「当前值」永远落在可用区间内、
     * 玩家可以把越界值原样留在框里再确定。本类照此口径由 [SettingsModel] 现算：
     * `min(min, 当前值)..max(max, 当前值)`。
     */
    data class IntBounds(val min: Int, val max: Int) : SettingsConstraint {

        operator fun contains(value: Int): Boolean = value in min..max

        override fun toString(): String = "$min..$max"
    }

    /** 枚举型条目的可选常量名（顺序即 C# 枚举序号，spec: `ConfigEnumKind.constants`）。 */
    data class Choices(val constants: List<String>) : SettingsConstraint

    /** 文本长度区间；`char` 型是 1..1（spec: `ConfigItem.cs:119-126`）。 */
    data class TextLength(val minLength: Int, val maxLength: Int) : SettingsConstraint
}

/**
 * 一个条目的**设置页视图**（不可变快照）—— 由 [SettingsModel] 每次访问现算，持有者不要长期缓存。
 *
 * 「三份值」的分工与 [EffectiveConfig] 的类 KDoc 完全一致，这里**不另立第二套真相**：
 *   * [currentValue] —— 条目自己存的值（`ConfigItem<T>.Value`，spec: `ConfigItem.cs:14-27`），
 *     即设置页回显与写文件用的那份（spec: `ConfigDialog.cs:694-705`、`ConfigData.cs:430-453`）；
 *   * [defaultValue] —— [ConfigSpec.defaultValue]，即 `setDefault()` 注册时的第三个实参
 *     （spec: `ConfigData.cs:45-174`）；
 *   * [effectiveValue] —— `Config.SetConfig` 夹取/改写之后真正生效的那份（spec: `Config.cs:296-486`），
 *     只有 [ConfigKey.FontSize] / [ConfigKey.LineHeight] / [ConfigKey.SaveDataNos] / [ConfigKey.MaxLog] /
 *     [ConfigKey.TextDrawingMode] / [ConfigKey.WarnFunctionOverloading] 六处会与 [currentValue] 分岔，
 *     其余原样透传。
 *
 * 页面文案与配置文件标签是**两套文本**：设置页显示的是 [pageJaLabel] / [pageZhsLabel]
 * （`Lang.cs` 的 `Lang.UI.ConfigDialog.*`，来源见 `ConfigPageLabels`），而 [jpLabel] / [enLabel] 是
 * `emuera.config` 里那条键的原文（spec: `ConfigData.cs:45-174` 的第二、三个实参）。
 * 两者经常不同（如 `InfiniteLoopAlertTime`、`SaveDataNos`），不要互相顶替。
 */
data class SettingsEntry(
    val spec: ConfigSpec,
    /** 条目现值（写文件与回显用，spec: `ConfigItem.cs:14-27`）。 */
    val currentValue: ConfigValue,
    /** 生效值（含 `Config.SetConfig` 的夹取与改写，spec: `Config.cs:431-465`）。 */
    val effectiveValue: ConfigValue,
    /** 设置页日语标签；该条目没有页面控件时为 null（spec: `ConfigPageLabels` 的登记范围）。 */
    val pageJaLabel: String?,
    /** 设置页简体中文译文；原版 zhs 文件缺这条时为 null（`ConfigPageLabels` 的口径）。 */
    val pageZhsLabel: String?,
    /** 输入约束（见 [SettingsConstraint]）。 */
    val constraint: SettingsConstraint,
    /** 是否被 `fixed.config` 钉住；为 true 时写入被忽略（spec: `ConfigItem.cs:20-27`，控件灰掉 spec: `ConfigDialog.cs:689`）。 */
    val fixed: Boolean,
) {

    /** 稳定 id —— `AConfigItem.Name`，即 `ConfigCode` 枚举成员字符串（spec: `AConfigItem.cs:21`）。 */
    val id: String get() = spec.name

    val key: ConfigKey get() = spec.key

    val group: ConfigGroup get() = spec.group

    /** `emuera.config` 的日文标签（原文大小写；写出行是大写形态，见 [ConfigSpec.labelForWrite]）。 */
    val jpLabel: String get() = spec.jpLabel

    /** `emuera.config` 的英文标签（同上；`CBTriggerAnyKeyWait` 带尾随空格，spec: `ConfigData.cs:140`）。 */
    val enLabel: String get() = spec.enLabel

    /** 控件分类（见 [SettingsKind]）。 */
    val kind: SettingsKind get() = SettingsKind.of(spec.valueType)

    /** 精确值类型（13 个成员，spec: `ConfigValueType`）。 */
    val valueType: ConfigValueType get() = spec.valueType

    /** 缺省值（spec: `ConfigData.cs:45-174` 的注册实参）。 */
    val defaultValue: ConfigValue get() = spec.defaultValue

    /**
     * 显示用标签：`english = true` 取英文标签、否则取日文标签，**原文大小写**
     * （写回文件的形态是大写，spec: `AConfigItem.cs:22-23`，由 [ConfigSpec.labelForWrite] 给）。
     */
    fun label(english: Boolean): String = if (english) spec.enLabel else spec.jpLabel
}

/**
 * 一个分组（C# 的三个数组之一，spec: `ConfigData.cs:23-27`）及其条目。
 *
 * @property group 数组归属
 * @property fileName 原版读它的文件：`emuera.config`（spec: `ConfigData.cs:33` 的 `configPath`）、
 *   `debug.config`（spec: `ConfigData.cs:34` 的 `configdebugPath`）、`_Replace.csv`
 *   （spec: `Process.cs:240-246`）
 * @property entries 该组的条目，**注册顺序**（spec: `ConfigData.cs:45-174`）
 */
data class SettingsGroupView(
    val group: ConfigGroup,
    val fileName: String,
    val entries: List<SettingsEntry>,
) {

    /**
     * 本组在设置页上是否**可编辑**（可写回、可重置）。判据是 C# 的数组归属，不是条目名字：
     *
     *   * `GAME`（`configArray`）—— 可编辑：设置页的写回只走 `ConfigData.SaveConfig`，
     *     而它只遍历 `configArray`（spec: `ConfigData.cs:430`）；设置对话框也是逐控件
     *     `SetValue` 到这些条目（spec: `ConfigDialog.cs:928-1118`）。
     *   * `DEBUG`（`debugArray`）—— **只读**：`debug.config` 由独立的 `DebugConfigDialog`
     *     读写（spec: `DebugConfigDialog.cs:85-99` 的绑定），`emuera.config` 的保存路径
     *     不含它；本移植的设置页只写 `emuera.config`。
     *   * `REPLACE`（`replaceArray`）—— **只读**：`_Replace.csv` 由启动装配读取
     *     （spec: `Process.cs:240-246`），原版没有设置对话框，也没有任何写回入口。
     */
    val editable: Boolean get() = group == ConfigGroup.GAME
}

/** [SettingsModel.set] 的拒绝原因 —— 上层据此决定提示样式（字段红框 / 气泡 / 灰字）。 */
enum class SettingsRejectReason {

    /** id 既不是条目名也不是任何一种标签（spec: `ConfigData.cs:262-284` 的三形匹配全落空）。 */
    UNKNOWN_ID,

    /** 值与条目类型不符（如对 bool 条目写 `7`）。原版这里是强转失败（spec: `ConfigItem.cs:43-46`）。 */
    TYPE_MISMATCH,

    /** 文本解析失败（原版 `TryParse` 抛 `CodeEE` 的三种文案，spec: `ConfigItem.cs:115`、`:132`、`:141`、`:176`）。 */
    INVALID_TEXT,

    /** 空文本：原版 `TryParse` 遇到空串直接返回、不改值（spec: `ConfigItem.cs:92-95`）。 */
    EMPTY_TEXT,

    /** 数值超出控件区间（spec: `ConfigDialog.cs:433-464`）。 */
    OUT_OF_RANGE,

    /** 条目被 `fixed.config` 钉住，写入被忽略（spec: `ConfigItem.cs:20-27`）。 */
    FIXED,
}

/** [SettingsModel.set] 的结果。 */
sealed interface SettingsSetResult {

    /** 写入成功。 */
    data class Applied(val id: String, val value: ConfigValue) : SettingsSetResult

    /**
     * 拒绝写入，[message] 是给玩家看的原文（能对上原版就用原版文案，见 [SettingsModel.set] 的 KDoc）。
     */
    data class Rejected(
        val id: String,
        val reason: SettingsRejectReason,
        val message: String,
    ) : SettingsSetResult
}

/**
 * 设置页的**数据模型**（SETTINGS-MODEL）—— 在既有 [ConfigStore] 之上生成「分组 → 条目」视图，
 * 提供带校验的写入、重置、脏标记与保留式写回。纯 JVM、无可变静态状态、不碰 Android API。
 *
 * ## 真相只有一份
 * 值的唯一存放处是构造时传入的 [store]（默认是一份全新的 [ConfigStore]，即全缺省表）。
 * 本类不复制条目值、不缓存标签：每次 [entries] / [entry] 都从 [store] 现读，
 * 缺省值直接取 [ConfigSpec.defaultValue]，生效值由 [EffectiveConfig.of] 现算 —— 与 M4 读配置那条链
 * 是同一份数据（spec: `Config.cs:296-486` 的 `SetConfig` 读的就是这些条目）。
 * 注意 `VariableStore` 侧的 `VarConfig`（PALAMLV / EXPLV / PBAND / RELATION 初值）是**下游副本**：
 * 它的值来自 `_Replace.csv` → `replaceArray`（spec: `ConfigData.cs:159-174`、`Process.cs:240-246`），
 * 启动时灌进变量池（`VariableSizes.kt` 的 `VarConfig`）—— 装配层重建变量池时读的是本模型的 REPLACE 组条目，
 * 而不是去写 `VarConfig`；设置页上这三组只有 `GAME` 可编辑（见 [SettingsGroupView.editable]），
 * REPLACE 组在页面上是只读展示（原版 `_Replace.csv` 没有写回入口，spec: `Process.cs:240-246`）。
 *
 * ## 英文标签开关是「现算」的
 * 原版写文件时读的是静态字段 `Config.EnglishConfigOutput`（spec: `ConfigItem.cs:86`、`ConfigData.cs:441`），
 * 而这个字段由 `SetConfig` 从条目灌入（spec: `Config.cs:389`）。本层不缓存它：[englishConfigOutput] 与
 * [buildMerge] 的默认参数每次现读条目，于是「先改 `EnglishConfigOutput` 再写文件」立刻生效。
 *
 * ## 与设置对话框的对应关系
 *   * 打开 / 回显：`setCheckBox` / `setNumericUpDown` / `setColorBox` 读条目值（spec: `ConfigDialog.cs:685-716`）；
 *   * 确定：逐控件 `SetValue` 到一份**副本**再写文件（spec: `ConfigDialog.cs:928-1118`，副本 `ConfigData.Instance.Copy()`）；
 *   * 取消：不落盘（spec: `ConfigDialog.cs:679-683`）。
 * 本模型把「副本」换成 [markClean] 记下的基线 + [restore] 回滚，语义与副本等价（改的是同一份 store，
 * 取消时用基线写回即可）。
 *
 * ## 保存后怎么生效（M5-CONFIG 的运行时重载口径）
 * 原版只有「写盘」与「写盘 + 重启进程」两条路（spec: `ConfigDialog.cs:665-668`、`:672-675`
 * → `MainWindow.cs:1066-1069` 的 `Reboot()`）。移植侧按 [ConfigApplyMode] 把改动分两桶：
 *   * [changedKeys] 算「相对基线改过的键」；
 *   * [pendingImpact] 按表分桶 —— [SettingsSaveImpact.requiresRestart] 为真才必须重开会话；
 *   * [pushHotInto] 把热更键推进运行中会话的条目表（[EffectiveConfig.reloadHot] 落地）。
 * 两条都不改本模型的 [store]，所以写盘文本不受热更影响。
 *
 * 传入的 [store] 必须覆盖全部 `GAME` 组条目（默认构造即是）：[EffectiveConfig.of] 缺条目会立即失败，
 * 而那是原版 `GetConfigValue<T>` 的 `NullReferenceException` 位置（spec: `ConfigData.cs:217-220`）。
 */
class SettingsModel(val store: ConfigStore = ConfigStore()) {

    /** [markClean] 记下的基线（值快照）；[dirty] 与 [restore] 都以它为准。 */
    private var baseline: Map<ConfigKey, ConfigValue> = store.snapshot()

    // -----------------------------------------------------------------
    // 视图
    // -----------------------------------------------------------------

    /**
     * 全表视图，顺序 = [ConfigStore.items] 的注册顺序（`configArray` → `replaceArray` → `debugArray`，
     * spec: `ConfigData.cs:45-174`；与 [ConfigSpecTable.all] 同序）。
     *
     * 每次访问现算（123 条共用一次 `EffectiveConfig` 计算）；调用方在同一帧里要多次读时自行缓存。
     */
    val entries: List<SettingsEntry>
        get() {
            val effective = EffectiveConfig.of(store)
            return store.items.map { entryFor(it, effective) }
        }

    /**
     * 分组视图，顺序 = `setDefault()` 的注册顺序 `configArray` → `debugArray` → `replaceArray`
     * （spec: `ConfigData.cs:45-51`、`:152-158`、`:159-174`）。
     */
    val groups: List<SettingsGroupView>
        get() {
            val effective = EffectiveConfig.of(store)
            return GROUP_ORDER.map { group ->
                SettingsGroupView(
                    group = group,
                    fileName = fileNameOf(group),
                    entries = store.itemsOf(group).map { entryFor(it, effective) },
                )
            }
        }

    /** 单组视图；组内顺序同 [groups]。 */
    fun group(group: ConfigGroup): SettingsGroupView {
        val effective = EffectiveConfig.of(store)
        return SettingsGroupView(
            group = group,
            fileName = fileNameOf(group),
            entries = store.itemsOf(group).map { entryFor(it, effective) },
        )
    }

    /**
     * 按稳定 id 取一条 —— id 先按 [ConfigKey] 成员名直查（`AConfigItem.Name`，spec: `AConfigItem.cs:21`），
     * 查不到再走 [ConfigStore.resolveAny] 的三形匹配（`Name` / `Text` / `EngText`，spec: `ConfigData.cs:236-248`、
     * `:262-284`），于是 `set("FontSize", …)` 与 `set("FONT SIZE", …)` 都能用。
     */
    fun entry(id: String): SettingsEntry? {
        val spec = specFor(id) ?: return null
        return entryFor(store.itemOf(spec.key), EffectiveConfig.of(store))
    }

    // -----------------------------------------------------------------
    // 写入 / 重置 / 脏标记
    // -----------------------------------------------------------------

    /**
     * 写一条（文本形态，等价于设置页控件提交的值）。
     *
     * 校验口径逐类型如下，失败一律返回 [SettingsSetResult.Rejected] 且**不动** [store]：
     *   * 条目被钉住 ⇒ [SettingsRejectReason.FIXED]（原版 `Value` setter 静默丢弃，spec: `ConfigItem.cs:20-27`；
     *     控件本来就灰掉，spec: `ConfigDialog.cs:689`，这里显式拒绝以便界面给出原因）；
     *   * `bool` ⇒ 认 `YES`/`NO`/`TRUE`/`FALSE`/`前`/`後`/整数（spec: `ConfigItem.cs:227-250`），
     *     否则 `InvalidSpecification`「不正な指定です」（spec: `Lang.cs:966`）；
     *   * `int` / `long` ⇒ 严格十进制（spec: `ConfigItem.cs:127-144`），非数字给「数字でない文字が含まれています」
     *     （spec: `Lang.cs:962`）；`int` 再按控件区间判 `OUT_OF_RANGE`（见 [SettingsConstraint.IntBounds]），
     *     该键在 `Config.SetConfig` 里有夹取文案时用原版原文，其余给本层自定文案；
     *   * `char` ⇒ 长度必须为 1（spec: `ConfigItem.cs:119-126`），否则「不正な指定です」；
     *   * `color` ⇒ `R,G,B` 三段 0..255（spec: `ConfigItem.cs:252-274`），否则「値をColor指定子として認識できません」
     *     （spec: `Lang.cs:958`）；
     *   * `string` ⇒ **原样存**，不 `Trim`：设置对话框的文本框就是 `SetValue(textBox.Text)`
     *     （spec: `ConfigDialog.cs:1081`、`:1082`、`:1095`、`:1111`）；
     *     **唯一例外是 [ConfigKey.ReplaceContinuationBR]** —— 原版那个文本框走的是
     *     `TryParse(textBox4.Text)`（spec: `ConfigDialog.cs:1084`），于是按字符串解析的规则来：
     *     空串整行不改值（spec: `ConfigItem.cs:92-95`）、非空先 `Trim()` 再整串赋值
     *     （spec: `ConfigItem.cs:100`、`:160-164`）。所以清空文本框**不会**把替换串写成空
     *     （那会让 ERB 行连接失去分隔串，spec: `EraStreamReader.cs:179`），只输入空格才会得到空串
     *     —— `param` 非空、`Trim()` 后为空的这条官方形状照抄；
     *   * `List<string>` ⇒ 空串拒绝（原版 `TryParse` 空串不改值，spec: `ConfigItem.cs:92-95`），
     *     否则按 `,` 切并逐项 `Trim`（spec: `ConfigItem.cs:165-171`）；
     *   * `List<long>` ⇒ 按 `/` 切并逐项严格十进制（spec: `ConfigItem.cs:145-159`）；
     *   * 5 个枚举 ⇒ `Enum.TryParse` 的形状，含常量名与数字串（spec: `ConfigItem.cs:172-211`，
     *     见 [ConfigEnumKind.parseEnumConstant] 的 KDoc），否则「不正な指定です」。
     *
     * @return 写入成功时 [SettingsSetResult.Applied]，否则带原文的 [SettingsSetResult.Rejected]；
     *   两者的 `id` 都是**规范 id**（[ConfigKey] 成员名），不是调用方传进来的标签形态
     */
    fun set(id: String, raw: String): SettingsSetResult {
        val spec = specFor(id)
            ?: return SettingsSetResult.Rejected(id, SettingsRejectReason.UNKNOWN_ID, UNKNOWN_ID_MESSAGE)
        val item = store.itemOf(spec.key)
        if (item.fixed) {
            return SettingsSetResult.Rejected(spec.name, SettingsRejectReason.FIXED, FIXED_MESSAGE)
        }
        return when (val parsed = parseForSet(spec, item.value, raw)) {
            is ParseOutcome.Ok -> {
                // assign 在 Fixed 时返回 false（spec: `ConfigItem.cs:20-27`）；上面已拦，这里只剩并发改钉位的理论分支。
                if (item.assign(parsed.value)) {
                    SettingsSetResult.Applied(spec.name, parsed.value)
                } else {
                    SettingsSetResult.Rejected(spec.name, SettingsRejectReason.FIXED, FIXED_MESSAGE)
                }
            }

            is ParseOutcome.Fail -> SettingsSetResult.Rejected(spec.name, parsed.reason, parsed.message)
        }
    }

    /** [set] 的勾选框形态：只接受 `bool` 条目（原版 `SetValue(checkBox.Checked)`，spec: `ConfigDialog.cs:931`）。 */
    fun set(id: String, value: Boolean): SettingsSetResult {
        val spec = specFor(id)
            ?: return SettingsSetResult.Rejected(id, SettingsRejectReason.UNKNOWN_ID, UNKNOWN_ID_MESSAGE)
        if (spec.valueType != ConfigValueType.BOOL) {
            return SettingsSetResult.Rejected(spec.name, SettingsRejectReason.TYPE_MISMATCH, TYPE_MISMATCH_MESSAGE)
        }
        return set(id, if (value) "YES" else "NO")
    }

    /** [set] 的数值框形态：只接受 `int` / `long` 条目（原版 `SetValue((int)numericUpDown.Value)`，spec: `ConfigDialog.cs:1103`）。 */
    fun set(id: String, value: Int): SettingsSetResult {
        val spec = specFor(id)
            ?: return SettingsSetResult.Rejected(id, SettingsRejectReason.UNKNOWN_ID, UNKNOWN_ID_MESSAGE)
        if (spec.valueType != ConfigValueType.INT && spec.valueType != ConfigValueType.LONG) {
            return SettingsSetResult.Rejected(spec.name, SettingsRejectReason.TYPE_MISMATCH, TYPE_MISMATCH_MESSAGE)
        }
        return set(id, value.toString())
    }

    /**
     * 单项回到缺省 —— 写入 [ConfigSpec.defaultValue]（spec: `ConfigData.cs:45-174` 的注册实参）。
     *
     * 原版没有单项重置入口（`ReLoadConfig` 是整表重读，spec: `ConfigData.cs:466-477`）；这是本层为设置页
     * 补的「恢复默认」。钉住的条目写不进（spec: `ConfigItem.cs:20-27`），返回 false。
     */
    fun reset(id: String): Boolean {
        val spec = specFor(id) ?: return false
        return store.itemOf(spec.key).assign(spec.defaultValue)
    }

    /**
     * 全表回缺省。
     *
     * @return 真正写成功的条数；钉住的条目被 [ConfigItem.assign] 拒掉，不计入
     */
    fun resetAll(): Int {
        var written = 0
        for (spec in ConfigSpecTable.all) {
            if (store.itemOf(spec.key).assign(spec.defaultValue)) {
                written++
            }
        }
        return written
    }

    /**
     * 只把**可编辑组**（[SettingsGroupView.editable]）回到缺省 —— 设置页「全部重置」按钮的落点。
     *
     * 与 [resetAll] 的差别只在范围：`DEBUG` / `REPLACE` 两组在页面上是只读展示
     * （原版 `emuera.config` 的保存路径只遍历 `configArray`，spec: `ConfigData.cs:430`；
     * `_Replace.csv` 由装配读取，spec: `Process.cs:240-246`），
     * 把它们一起重置只会造出「改了但永远写不进文件」的假脏标记。
     *
     * @return 真正写成功的条数，上限是 `configArray` 的条目数（spec: `ConfigData.cs:45-151`）
     */
    fun resetAllEditable(): Int {
        var written = 0
        for (spec in ConfigSpecTable.all) {
            if (spec.group != ConfigGroup.GAME) {
                continue // 只读组：与 SettingsGroupView.editable 同一判据
            }
            if (store.itemOf(spec.key).assign(spec.defaultValue)) {
                written++
            }
        }
        return written
    }

    /**
     * 是否有未保存的修改 —— 与 [markClean] 记下的基线逐条目比**值**（不比 `Fixed` 位：
     * 钉位来自 `fixed.config`，本模型没有改它的入口）。
     */
    val dirty: Boolean
        get() = store.snapshot() != baseline

    /** 把当前状态定为基线（打开对话框 / 写盘成功之后调用）。 */
    fun markClean() {
        baseline = store.snapshot()
    }

    /**
     * 丢弃未保存的修改：把每个条目写回基线值，对应原版「取消」不落盘
     * （spec: `ConfigDialog.cs:679-683`，对话框在副本上改、取消即丢弃，spec: `:930`）。
     *
     * @return 写回成功的条数
     */
    fun restore(): Int {
        var written = 0
        for ((key, value) in baseline) {
            if (store.itemOf(key).assign(value)) {
                written++
            }
        }
        return written
    }

    // -----------------------------------------------------------------
    // 写回
    // -----------------------------------------------------------------

    /** `Config.EnglishConfigOutput` 的现值（现算，不缓存；spec: `ConfigItem.cs:86`、`Config.cs:389`）。 */
    fun englishConfigOutput(): Boolean = store.itemOf(ConfigKey.EnglishConfigOutput).bool()

    /**
     * 把当前状态合并进一份已有的配置文件文本 —— 直接转给 [EmueraConfigWriter.mergeInto]：
     * 已知键原位替换、未知键 / 注释 / 顺序 / 行尾逐字符保留、缺失键按注册顺序追加、
     * `ConfigData.cs:433` 的三条不写出规则照旧（细节见该函数 KDoc）。
     *
     * @param original 已剥 BOM 的原文（首建传空串）
     * @param english 标签语言；默认现读 [englishConfigOutput]
     */
    fun buildMerge(original: String, english: Boolean = englishConfigOutput()): String =
        EmueraConfigWriter.mergeInto(original, store, english)

    // -----------------------------------------------------------------
    // 生效视图
    // -----------------------------------------------------------------

    /** `Config.SetConfig` 的读取 + 夹取链的一次性快照（spec: `Config.cs:296-486`）。 */
    fun effectiveConfig(): EffectiveConfig = EffectiveConfig.of(store)

    /** 与 [effectiveConfig] 同一份 `notices`，供界面按原版顺序弹提示（spec: `Config.cs:431-460`）。 */
    fun clampNotices(): List<ConfigClampNotice> = effectiveConfig().notices

    // -----------------------------------------------------------------
    // 运行时重载（M5-CONFIG 的「保存后怎么生效」口径）
    // -----------------------------------------------------------------

    /**
     * 相对 [markClean] 基线**改过的键**，按注册顺序（[ConfigStore.items] 的顺序）。
     *
     * 判据是值不等（与 [dirty] 同一口径），不比 `Fixed` 位 —— 钉位来自 `fixed.config`，
     * 本模型没有改它的入口。
     *
     * 注意调用时机：[SettingsStore.save] 成功后会 [markClean]，此后本函数返回空表。
     * 设置页必须在写盘**之前**取它（见 `SettingsActivity.save` 的注释）。
     */
    fun changedKeys(): List<ConfigKey> =
        store.items.mapNotNull { item ->
            val key = item.spec.key
            if (baseline[key] == item.value) null else key
        }

    /** [changedKeys] 按 [ConfigApplyMode] 分桶 —— 设置页据此决定「原地刷新」还是「重启」。 */
    fun pendingImpact(): SettingsSaveImpact = ConfigApplyMode.split(changedKeys())

    /**
     * 把改过的**热更键**推进另一份**活的**条目表（运行中会话的那一份），返回真正落地的键。
     *
     * 为什么要传 [target] 而不是写自己：[store] 是设置页从盘上读出来的那一份，运行中的
     * `GameSession.configStore` 是另一份实例（`GameSession.kt:261` 装配时定死）。热更的语义是
     * 「改运行期正在读的那份表」，所以写 [target]；本模型的 [store] 一个字都不动 ——
     * 写盘文本（[buildMerge]）因此逐字节不变，保留式写回不回退。
     *
     * 非热更键**跳过**（不写一半），由调用方走重启。被 `fixed.config` 钉住的条目
     * 也跳过（[EffectiveConfig.reloadHot] 返回 [ConfigReloadResult.Fixed]）。
     *
     * @return 实际落地的键，顺序同 [changedKeys]
     */
    fun pushHotInto(target: ConfigStore): List<ConfigKey> {
        val effective = EffectiveConfig.of(target)
        val applied = ArrayList<ConfigKey>()
        for (key in changedKeys()) {
            if (ConfigApplyMode.of(key) != ConfigApplyMode.HOT) {
                continue
            }
            val result = effective.reloadHot(key, store.itemOf(key).value)
            if (result is ConfigReloadResult.Applied) {
                applied += key
            }
        }
        return applied
    }

    // -----------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------

    private fun entryFor(item: ConfigItem, effective: EffectiveConfig): SettingsEntry {
        val spec = item.spec
        val page = ConfigPageLabels.byKey(spec.key)
        return SettingsEntry(
            spec = spec,
            currentValue = item.value,
            effectiveValue = effectiveValueOf(spec, item.value, effective),
            pageJaLabel = page?.jaLabel,
            pageZhsLabel = page?.zhsLabel,
            constraint = constraintOf(spec, item.value),
            fixed = item.fixed,
        )
    }

    /**
     * 生效值：只有 `Config.SetConfig` 会改写的那几处与条目值分岔，其余透传。
     * 四个整型的夹取见 spec: `Config.cs:431-455`；模式改写见 `:456-460`；
     * 告警强制开见 `:345-352`。
     */
    private fun effectiveValueOf(spec: ConfigSpec, current: ConfigValue, effective: EffectiveConfig): ConfigValue =
        when (spec.key) {
            ConfigKey.FontSize, ConfigKey.LineHeight, ConfigKey.SaveDataNos, ConfigKey.MaxLog ->
                ConfigValue.IntValue(effective.int(spec.key))

            ConfigKey.TextDrawingMode -> ConfigValue.EnumValue(
                requireNotNull(spec.valueType.enumKind) { "TextDrawingMode 不是枚举型" },
                effective.enumConstant(spec.key),
            )

            ConfigKey.WarnFunctionOverloading -> ConfigValue.Bool(effective.warnFunctionOverloading)

            else -> current
        }

    /**
     * 输入约束：整数型的区间按「控件撑开」口径现算（spec: `ConfigDialog.cs:696-703`），
     * 枚举型给常量表，`char` 给 1..1，其余无约束。
     */
    private fun constraintOf(spec: ConfigSpec, current: ConfigValue): SettingsConstraint {
        val gui = spec.guiIntRange
        if (gui != null && current is ConfigValue.IntValue) {
            return SettingsConstraint.IntBounds(minOf(gui.first, current.value), maxOf(gui.last, current.value))
        }
        val enumKind = spec.valueType.enumKind
        if (enumKind != null) {
            return SettingsConstraint.Choices(enumKind.constants)
        }
        if (spec.valueType == ConfigValueType.CHAR) {
            return SettingsConstraint.TextLength(1, 1)
        }
        return SettingsConstraint.None
    }

    /**
     * id → 条目规格：先按枚举成员名直查，再走 `GetItem(string)` 的三级三形匹配
     * （spec: `ConfigData.cs:236-248`；键比较 `GetConfigItem` / `GetReplaceItem(string)`
     * spec: `:262-313`，后者**不认** `EngText`）。
     */
    private fun specFor(id: String): ConfigSpec? {
        val trimmed = id.trim() // 读文件路径的键同样先 Trim（spec: ConfigData.cs:539）
        return byId[trimmed] ?: store.resolveAny(trimmed)?.spec
    }

    private fun parseForSet(spec: ConfigSpec, current: ConfigValue, raw: String): ParseOutcome {
        val text = raw.trim() // ConfigItem.cs:100（string 型例外，见 set 的 KDoc）
        return when (spec.valueType) {
            ConfigValueType.BOOL -> {
                val parsed = parseBoolLiteral(text)
                    ?: return ParseOutcome.fail(SettingsRejectReason.INVALID_TEXT, INVALID_SPECIFICATION_TEXT)
                ParseOutcome.Ok(ConfigValue.Bool(parsed))
            }

            ConfigValueType.INT -> {
                val parsed = parseIntStrictly(text)
                    ?: return ParseOutcome.fail(
                        SettingsRejectReason.INVALID_TEXT,
                        ConfigParseException.LangKey.CONTAINS_NON_NUMERIC_CHARACTERS.jpText,
                    )
                val bounds = (constraintOf(spec, current) as? SettingsConstraint.IntBounds)
                if (bounds != null && parsed !in bounds) {
                    return ParseOutcome.fail(SettingsRejectReason.OUT_OF_RANGE, rangeMessage(spec, parsed, bounds))
                }
                ParseOutcome.Ok(ConfigValue.IntValue(parsed))
            }

            ConfigValueType.LONG -> {
                val parsed = parseLongStrictly(text)
                    ?: return ParseOutcome.fail(
                        SettingsRejectReason.INVALID_TEXT,
                        ConfigParseException.LangKey.CONTAINS_NON_NUMERIC_CHARACTERS.jpText,
                    )
                ParseOutcome.Ok(ConfigValue.LongValue(parsed))
            }

            ConfigValueType.CHAR -> {
                if (text.length != 1) {
                    ParseOutcome.fail(SettingsRejectReason.INVALID_TEXT, INVALID_SPECIFICATION_TEXT)
                } else {
                    ParseOutcome.Ok(ConfigValue.CharValue(text[0]))
                }
            }

            ConfigValueType.STRING -> if (spec.key == ConfigKey.ReplaceContinuationBR) {
                // ConfigDialog.cs:1084 —— 全部 string 条目里只有这一条走 TryParse（其余是 SetValue 原样写）。
                // 空串：ConfigItem.cs:92-95 直接 return false，值不动；非空：:100 Trim 后整串赋值（:160-164）。
                if (raw.isEmpty()) {
                    ParseOutcome.fail(SettingsRejectReason.EMPTY_TEXT, EMPTY_TEXT_MESSAGE)
                } else {
                    ParseOutcome.Ok(ConfigValue.StringValue(text))
                }
            } else {
                ParseOutcome.Ok(ConfigValue.StringValue(raw))
            }

            ConfigValueType.COLOR -> {
                val parsed = parseColorTriple(text)
                    ?: return ParseOutcome.fail(
                        SettingsRejectReason.INVALID_TEXT,
                        ConfigParseException.LangKey.NOT_EXIST_COLOR_SPECIFIER.jpText,
                    )
                ParseOutcome.Ok(parsed)
            }

            ConfigValueType.STRING_LIST -> {
                if (text.isEmpty()) {
                    // 原版 TryParse 对空串直接返回、保留旧值（ConfigItem.cs:92-95）；这里显式拒绝而不是静默无视。
                    ParseOutcome.fail(SettingsRejectReason.EMPTY_TEXT, EMPTY_TEXT_MESSAGE)
                } else {
                    ParseOutcome.Ok(ConfigValue.StringList(text.split(',').map { it.trim() }))
                }
            }

            ConfigValueType.LONG_LIST -> {
                val values = ArrayList<Long>()
                for (token in text.split('/')) {
                    values += parseLongStrictly(token.trim())
                        ?: return ParseOutcome.fail(
                            SettingsRejectReason.INVALID_TEXT,
                            ConfigParseException.LangKey.CONTAINS_NON_NUMERIC_CHARACTERS.jpText,
                        )
                }
                ParseOutcome.Ok(ConfigValue.LongList(values))
            }

            ConfigValueType.TEXT_DRAWING_MODE_ENUM, ConfigValueType.REDUCE_ARGUMENT_ENUM,
            ConfigValueType.DISPLAY_WARNING_ENUM, ConfigValueType.USE_LANGUAGE_ENUM,
            ConfigValueType.TEXT_EDITOR_TYPE_ENUM -> {
                val kind = requireNotNull(spec.valueType.enumKind) { "${spec.key} 不是枚举型" }
                val constant = ConfigEnumKind.parseEnumConstant(kind, text)
                    ?: return ParseOutcome.fail(SettingsRejectReason.INVALID_TEXT, INVALID_SPECIFICATION_TEXT)
                ParseOutcome.Ok(ConfigValue.EnumValue(kind, constant))
            }
        }
    }

    /**
     * 区间拒绝的文案：能在 `Config.SetConfig` 里找到夹取原文的键就用原文
     * （spec: `Config.cs:431-455`、`Lang.cs:4034` / `:4042` / `:4046` / `:4050`），
     * 其余键原版不会走到（数值框在控件层就夹住），用本层自定文案并带上可用区间。
     */
    private fun rangeMessage(spec: ConfigSpec, value: Int, bounds: SettingsConstraint.IntBounds): String = when {
        spec.key == ConfigKey.FontSize && value < EffectiveConfig.FONT_SIZE_FLOOR ->
            "フォントサイズが小さすぎます(8が下限)" // Lang.cs:4034

        spec.key == ConfigKey.SaveDataNos && value < EffectiveConfig.SAVE_DATA_NOS_FLOOR ->
            "表示するセーブデータ数が少なすぎます(20が下限)" // Lang.cs:4042

        spec.key == ConfigKey.SaveDataNos && value > EffectiveConfig.SAVE_DATA_NOS_CEILING ->
            "表示するセーブデータ数が多すぎます(80が上限)" // Lang.cs:4046

        spec.key == ConfigKey.MaxLog && value < MaxLogSetting.FLOOR ->
            "ログ表示行数が少なすぎます(500が下限)" // Lang.cs:4050

        else -> "設定値が範囲外です(${bounds.min}..${bounds.max})" // 本层自定：原版数值框不让提交区间外的数
    }

    /** [parseForSet] 的内部结果 —— 成功带值、失败带原因与原版文案。 */
    private sealed interface ParseOutcome {

        class Ok(val value: ConfigValue) : ParseOutcome

        class Fail(val reason: SettingsRejectReason, val message: String) : ParseOutcome

        companion object {
            fun fail(reason: SettingsRejectReason, message: String): ParseOutcome = Fail(reason, message)
        }
    }

    companion object {

        /** `setDefault()` 的注册顺序：`configArray` → `debugArray` → `replaceArray`（spec: `ConfigData.cs:45-174`）。 */
        private val GROUP_ORDER = listOf(ConfigGroup.GAME, ConfigGroup.DEBUG, ConfigGroup.REPLACE)

        /** 稳定 id → 规格。只读表，构造期建一次（`ConfigKey.name` 与 `AConfigItem.Name` 同源，spec: `AConfigItem.cs:21`）。 */
        private val byId: Map<String, ConfigSpec> = ConfigSpecTable.all.associateBy { it.name }

        /** 「不正な指定です」（spec: `Lang.cs:966`，抛错点 spec: `ConfigItem.cs:176`、`:249`）。 */
        private val INVALID_SPECIFICATION_TEXT = ConfigParseException.LangKey.INVALID_SPECIFICATION.jpText

        /** 以下四条文案是本层自定的（原版对应路径不可达或没有文案），KDoc 里逐条登记差异。 */
        private val UNKNOWN_ID_MESSAGE = "設定項目が見つかりません"

        private val FIXED_MESSAGE = "この項目は固定されているため変更できません"

        private val TYPE_MISMATCH_MESSAGE = "設定項目の型が一致しません"

        /**
         * 空文本拒绝的文案 —— 覆盖两处「原版不改值、本层显式拒绝」的入口：
         * [ConfigKey.ValidExtension] 与 [ConfigKey.ReplaceContinuationBR] 的空串
         * （原版 `TryParse` 在 `ConfigItem.cs:92-95` 直接返回 false，静默保留旧值）。
         */
        private val EMPTY_TEXT_MESSAGE = "空の値は設定できません"

        /** 分组 → 原版读取它的文件（spec: `ConfigData.cs:33`、`:34`、`Process.cs:246`）。 */
        private fun fileNameOf(group: ConfigGroup): String = when (group) {
            ConfigGroup.GAME -> "emuera.config"
            ConfigGroup.DEBUG -> "debug.config"
            ConfigGroup.REPLACE -> "_Replace.csv"
        }
    }
}
