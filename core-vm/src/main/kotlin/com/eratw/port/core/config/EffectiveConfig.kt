package com.eratw.port.core.config

/**
 * 读取期夹取/改写产生的**提示** —— 原版是 5 个 `Dialog.Show` 与 1 个 `MessageBox.Show`
 * （spec: `Config.cs:431-460`）。本层是纯 JVM 数据层，不弹窗，只把「要弹什么、把值从几改到几」
 * 作为数据交回上层（同 `LogBuffer.MaxLogResolution.raisedToFloor` 的政策：
 * 标志位给上层，文案里的数是 `requested` 而不是 `effective`）。
 *
 * 标题与正文原文取自 `Lang.MessageBox`：
 *   * 标题 `ConfigError`「設定のエラー」（spec: `Lang.cs:4030`）—— 5 个 `Dialog.Show` 都带它；
 *   * `WINAPI` 那条走的是 `MessageBox.Show`，**只有正文没有标题**（spec: `Config.cs:458`），
 *     所以 [titleJp] 为 null。
 */
data class ConfigClampNotice(
    val kind: Kind,
    val key: ConfigKey?,
    val requested: String,
    val effective: String,
    val titleJp: String?,
    val messageJp: String,
) {

    /** 夹取的种类；`ordinal` 无意义，只用于上层分派图标/按钮。 */
    enum class Kind {
        TOO_SMALL_FONT_SIZE, // Config.cs:431-435，Lang.cs:4034
        LINE_HEIGHT_LESS_THAN_FONT_SIZE, // Config.cs:436-440，Lang.cs:4038
        TOO_SMALL_DISPLAY_SAVE_DATA, // Config.cs:441-445，Lang.cs:4042
        TOO_LARGE_DISPLAY_SAVE_DATA, // Config.cs:446-450，Lang.cs:4046
        TOO_SMALL_LOG_SIZE, // Config.cs:451-455，Lang.cs:4050
        WINAPI_UNSUPPORTED, // Config.cs:456-460，Lang.cs:4214
    }
}

/** `MAXLOG` 的「原值 / 生效值 / 是否被抬到下限」三元组（spec: `Config.cs:325` + `:451-455`）。 */
data class MaxLogSetting(val requested: Int, val effective: Int, val raisedToFloor: Boolean) {
    companion object {

        /** 缺省值 5000（spec: `ConfigData.cs:62`）。 */
        const val DEFAULT = 5000

        /** 配置层下限 500（spec: `Config.cs:451-455`）；**没有上限**（原版只在设置对话框的 `NumericUpDown` 上给到 1000000，见 [ConfigSpec.guiIntRange]）。 */
        const val FLOOR = 500
    }
}

/**
 * [EffectiveConfig.reloadHot] 的结果 —— 热更键落地的三种去向。
 *
 * 与 [ConfigApplyMode] 一一对应：只有登记为 [ConfigApplyMode.HOT] 的键才可能 [Applied]，
 * 其余一律 [NeedsRestart] 且**条目表不动**（不写一半，见该枚举的 KDoc）。
 */
sealed interface ConfigReloadResult {

    /**
     * 热更落地：新值已写进 [EffectiveConfig.source]，本视图的派生字段已重算，
     * 同一份视图的下一次读数（以及同一会话的下一个使用点）即为新值。
     */
    data class Applied(
        val key: ConfigKey,
        val previous: ConfigValue,
        val value: ConfigValue,
    ) : ConfigReloadResult

    /** 该键不在热更表内：条目表**未改动**，调用方必须重开会话（原版 `Reboot()`）才吃到。 */
    data class NeedsRestart(val key: ConfigKey) : ConfigReloadResult

    /** 条目被 `fixed.config` 钉住，写入被忽略（spec: `ConfigItem.cs:20-27`，控件本来也灰掉，spec: `ConfigDialog.cs:689`）。 */
    data class Fixed(val key: ConfigKey) : ConfigReloadResult
}

/**
 * `Config.SetConfig`（spec: `Config.cs:296-486`）的数据层等价物 ——
 * 在**已读完全部配置文件**的 [ConfigStore] 之上，算出「进程真正生效的那份配置」。
 *
 * ## 与条目表的关系（最容易搞错的一点）
 * 原版 `SetConfig` 把条目值读进**静态字段**，夹取改的是静态字段，
 * **不回写 `configArray` 里的条目**（spec: `Config.cs:431-455` 只给 `FontSize` / `LineHeight` /
 * `SaveDataNos` / `MaxLog` 这些静态属性赋值）。于是：
 *   * `emuera.config` 里写 `MAXLOG:400` 时，`Config.MaxLog` 是 500，而条目里仍是 400；
 *     下次 `SaveConfig`（spec: `ConfigData.cs:424-464` 从条目写文件）落盘的还是 400
 *     —— 玩家每次开机都被弹一次框，配置却永远不改；
 *   * 设置对话框显示的是**条目值**（spec: `ConfigDialog.cs:694-705` 读 `ConfigData.Instance`），
 *     且 `setNumericUpDown` 会把上下限撑到能容纳那个越界值（spec: `ConfigDialog.cs:696-703`）。
 * 本类因此把两个视图都留着：[source]（原值，写文件与回显用）与各生效字段（读数用），
 * 并把 6 处夹取/改写产生的提示放进 [notices]。
 *
 * ## 夹取顺序有副作用
 * `FontSize` 先夹到 ≥8（spec: `Config.cs:431-435`），紧接着的 `LineHeight < FontSize`
 * 比较用的是**夹过之后**的 `FontSize`（spec: `Config.cs:436-440`）——
 * 所以 `fontsize:4` + `lineheight:5` 的结果是 `8/8` 而不是 `8/5`。顺序按原版排。
 *
 * ## 派生字段与 [reloadHot]（M5-CONFIG 的运行时重载口径）
 * [notices] 与各生效字段都是**由 [source] 派生**的，不是独立真相：构造期由 [recompute] 算一次，
 * [reloadHot] 命中热更键（[ConfigApplyMode.HOT]）时把新值写进 [source] 再重算一遍。
 * 于是同一份视图对象在热更后立刻给出新读数 —— 这正是「引擎下一帧就读」的等价物
 * （原版是静态字段被 setter 直接改，spec: `ConfigDialog.cs:1107-1109` 的三条 `CBProc.Set*` 就是原版
 * 自己的活推先例；纯重启那条口径见 `ConfigDialog.cs:665-668` / `:672-675` → `MainWindow.cs:1066-1069`
 * 的 `Reboot()`）。
 *
 * 线程口径：[source] 的条目本来就是可变的（`ConfigItem.assign` 从设置页写、VM 线程读），
 * 本类不新增共享面 —— [recompute] 与 [reloadHot] 的调用方与「写条目表」的调用方是同一条线，
 * 与既有 `ConfigStore` 的约定一致。
 *
 * ## 不在本类里做的事
 *   * `SavDir` / `ForceSavDir` 的拼接与建目录、文件搬迁（spec: `Config.cs:467-485`、`:551-590`）
 *     依赖 `Program.ExeDir` 与文件系统，属宿主层；
 *   * `CheckUpdate` 的文件时间戳异或（spec: `Config.cs:592-642`）同理；只有它顺带算出的
 *     [needReduceArgumentOnLoad] 是纯数据，放在这里；
 *   * `SetupDefaultFont` 的字体探测与回退（spec: `Config.cs:501-523`，回退字体 `BIZ UDGothic`
 *     spec: `Config.cs:27`）属渲染层（FONT-LICENSE 那条 BOARD 待办的落点），本类只如实报出
 *     [FontName] 的配置原值；
 *   * 另外两条**静态字段推送链**没有等价视图，因为它们不在本路的判据面上：
 *     `SetDebugConfig`（spec: `Config.cs:708-717`）对 7 个 debug 条目只是逐项透传、没有任何夹取；
 *     `SetReplace`（spec: `Config.cs:719-741`）里唯一的改写是 `DrawLineString` 为空串时回落
 *     `"-"`（spec: `Config.cs:725-729`），消费方在渲染层画 DRAWLINE 时才用得到 ——
 *     需要的话由 M5 的宿主装配层直接读 [source] 的条目再自己合那一条。
 *
 * 纯 JVM：无 Android API、无 `kotlin.random`、无可变静态状态。
 */
class EffectiveConfig private constructor(
    /** 原值视图（含越界值与被钉住的条目）；[reloadHot] 写的就是它。 */
    val source: ConfigStore,
) {

    /** 按原版判定顺序排列的夹取提示（spec: `Config.cs:431-460`）；[reloadHot] 后原地重算。 */
    var notices: List<ConfigClampNotice> = emptyList()
        private set

    /** spec: `Config.cs:332` 读取 + `:431-435` 夹到 ≥8。 */
    var fontSize: Int = FONT_SIZE_FLOOR
        private set

    /** spec: `Config.cs:334` 读取 + `:436-440` 不足 `fontSize` 时抬到 `fontSize`。 */
    var lineHeight: Int = FONT_SIZE_FLOOR
        private set

    /** spec: `Config.cs:338` 读取 + `:441-450` 夹到 20..80。 */
    var saveDataNos: Int = SAVE_DATA_NOS_FLOOR
        private set

    /** spec: `Config.cs:325` 读取 + `:451-455` 抬到 ≥500。 */
    var maxLog: MaxLogSetting = MaxLogSetting(MaxLogSetting.DEFAULT, MaxLogSetting.DEFAULT, false)
        private set

    /** spec: `Config.cs:319` 读取 + `:456-460`：`WINAPI` 不受支持，一律改回 `TEXTRENDERER`。 */
    var textDrawingMode: String = "TEXTRENDERER"
        private set

    /**
     * spec: `Config.cs:345-352`：`AllowFunctionOverloading` 为 false 时**强制** true
     * —— 注意这是静态字段上的强制，条目里玩家写的 `NO` 原样保留，于是
     * 「不允许覆盖 ⇒ 必出覆盖告警」这条无法从配置文件里关掉。
     */
    var warnFunctionOverloading: Boolean = true
        private set

    /** spec: `Config.cs:412-430` 的 `UseLanguage` → LCID + ANSI 代码页。 */
    var language: LanguageSetting? = null
        private set

    /**
     * spec: `Config.cs:461-465` 的 `DrawingParam_ShapePositionShift`。
     *
     * 原版先赋 0、再判 `TextDrawingMode != WINAPI` 才赋 `Math.Max(2, FontSize / 6)`；
     * 但 `:456-460` 已经把 `WINAPI` 改写掉了，所以到 `:462` 时条件**恒为真**
     * —— 死分支，值恒等于 `max(2, fontSize / 6)`（整除）。原样复刻，不修。
     */
    var shapePositionShift: Int = 0
        private set

    /** spec: `Config.cs:466` 的 `DrawableWidth = WindowX - shift`。 */
    var drawableWidth: Int = 0
        private set

    /**
     * spec: `Config.cs:594-604`：`ReduceArgumentOnLoad` 为 `YES` ⇒ true、`NO` ⇒ false、
     * `ONCE` ⇒ **保持上一次的值**（这里用 null 表示「不改动」，由宿主拿上会话状态自己合）。
     */
    var needReduceArgumentOnLoad: Boolean? = null
        private set

    /** 夹取后的 `FontSize` 与 `LineHeight` 一起决定 Android 侧的行盒高度；这里只报数，不做度量。 */
    val windowX: Int get() = int(ConfigKey.WindowX)

    val windowY: Int get() = int(ConfigKey.WindowY)

    /** 大小写不敏感开关（spec: `Config.cs:299-309`：`IgnoreCase` 决定 `StringComparison`/`StringComparer`）。 */
    val ignoreCase: Boolean get() = bool(ConfigKey.IgnoreCase)

    /**
     * `_Rename.csv`（`置換後 , [[置換前]]`）是否启用。
     *
     * spec: `Config.cs:310` 读 `ConfigCode.UseRenameFile`；装载侧只在 true 时读文件、
     * 文件缺失时打错误行（`Process.cs:261-274`）。默认 false（`ConfigSpec.kt:103` = `ConfigData.cs:46`）。
     *
     * 本类只报数：**接线点**在 `CorpusCompile.loadRenameDic`（现在无条件加载）与
     * `LogicalLineReader` 的 `renameDic` 形参（运行期装配侧没有调用点传它）。
     */
    val useRenameFile: Boolean get() = bool(ConfigKey.UseRenameFile)

    /** 全角空格是否算空白（spec: `Config.cs:370`；消费点在 core 的 lexer/host，本类只报数）。 */
    val systemAllowFullSpace: Boolean get() = bool(ConfigKey.SystemAllowFullSpace)

    /** 配置文件是否用英文标签写出（spec: `Config.cs:389`，`ConfigItem.ToString` 的标签选择器）。 */
    val englishConfigOutput: Boolean get() = bool(ConfigKey.EnglishConfigOutput)

    /** `Config.FontName`（spec: `Config.cs:333`）；字体是否存在、是否回退到 `BIZ UDGothic` 由渲染层决定。 */
    val fontName: String get() = string(ConfigKey.FontName)

    /**
     * 运行时热更一个键 —— 命中 [ConfigApplyMode.HOT] 才落地。
     *
     * 落地动作只有两步：把 [value] 写进 [source] 的同名条目，再 [recompute] 一遍派生字段。
     * 于是**同一份视图对象**的下一次读数即为新值（`fontSize` / `drawableWidth` / [notices]
     * 这类派生字段也随之更新），同一会话里任何逐次现读 [source] 的使用点同样看到新值。
     *
     * 未登记为热更的键**一律不写**：返回 [ConfigReloadResult.NeedsRestart]，[source] 保持原样 ——
     * 这些键在 `GameSession` 装配期被烘死进构造参数 / 变量池 / 语义快照，写一半只会造出
     * 「新值旧结构」的分叉（逐键理由见 [ConfigApplyMode.HOT_KEYS] 的 KDoc）。
     *
     * @return [ConfigReloadResult.Applied] / [ConfigReloadResult.NeedsRestart] / [ConfigReloadResult.Fixed]
     */
    fun reloadHot(key: ConfigKey, value: ConfigValue): ConfigReloadResult {
        if (ConfigApplyMode.of(key) != ConfigApplyMode.HOT) {
            return ConfigReloadResult.NeedsRestart(key)
        }
        val item = source.itemOf(key)
        val previous = item.value
        if (!item.assign(value)) {
            return ConfigReloadResult.Fixed(key) // ConfigItem.cs:20-27：钉住的条目静默丢弃
        }
        recompute()
        return ConfigReloadResult.Applied(key = key, previous = previous, value = item.value)
    }

    /**
     * 重算全部派生字段 —— 跑一遍 `Config.SetConfig` 的读取 + 夹取链（spec: `Config.cs:296-486`）。
     *
     * 构造期与 [reloadHot] 各调一次，除此之外没有写入口；字段集合与 [source] 的条目一一对应。
     */
    private fun recompute() {
        val found = ArrayList<ConfigClampNotice>(6)

        // Config.cs:319 —— 模式先按原样读出来；`:456-460` 的改写要等到夹取链的最后一个
        // （原版就把它排在 MaxLog 之后），否则 [notices] 的顺序与原版弹框顺序不一致。
        var drawingMode = source.itemOf(ConfigKey.TextDrawingMode).enumConstant()

        // Config.cs:332 + :431-435
        var size = source.itemOf(ConfigKey.FontSize).int()
        if (size < FONT_SIZE_FLOOR) {
            found += clampNotice(
                ConfigClampNotice.Kind.TOO_SMALL_FONT_SIZE, ConfigKey.FontSize,
                requested = size.toString(), effective = FONT_SIZE_FLOOR.toString(),
                messageJp = "フォントサイズが小さすぎます(8が下限)", // Lang.cs:4034
            )
            size = FONT_SIZE_FLOOR
        }

        // Config.cs:334 + :436-440 —— 与**夹过的** FontSize 比，见类 KDoc。
        var height = source.itemOf(ConfigKey.LineHeight).int()
        if (height < size) {
            found += clampNotice(
                ConfigClampNotice.Kind.LINE_HEIGHT_LESS_THAN_FONT_SIZE, ConfigKey.LineHeight,
                requested = height.toString(), effective = size.toString(),
                messageJp = "行の高さがフォントサイズより小さいため、フォントサイズと同じ高さと解釈されます", // Lang.cs:4038
            )
            height = size
        }

        // Config.cs:338 + :441-450
        var saveNos = source.itemOf(ConfigKey.SaveDataNos).int()
        if (saveNos < SAVE_DATA_NOS_FLOOR) {
            found += clampNotice(
                ConfigClampNotice.Kind.TOO_SMALL_DISPLAY_SAVE_DATA, ConfigKey.SaveDataNos,
                requested = saveNos.toString(), effective = SAVE_DATA_NOS_FLOOR.toString(),
                messageJp = "表示するセーブデータ数が少なすぎます(20が下限)", // Lang.cs:4042
            )
            saveNos = SAVE_DATA_NOS_FLOOR
        }
        if (saveNos > SAVE_DATA_NOS_CEILING) {
            found += clampNotice(
                ConfigClampNotice.Kind.TOO_LARGE_DISPLAY_SAVE_DATA, ConfigKey.SaveDataNos,
                requested = saveNos.toString(), effective = SAVE_DATA_NOS_CEILING.toString(),
                messageJp = "表示するセーブデータ数が多すぎます(80が上限)", // Lang.cs:4046
            )
            saveNos = SAVE_DATA_NOS_CEILING
        }

        // Config.cs:325 + :451-455
        val requestedMaxLog = source.itemOf(ConfigKey.MaxLog).int()
        val raised = requestedMaxLog < MaxLogSetting.FLOOR
        if (raised) {
            found += clampNotice(
                ConfigClampNotice.Kind.TOO_SMALL_LOG_SIZE, ConfigKey.MaxLog,
                requested = requestedMaxLog.toString(), effective = MaxLogSetting.FLOOR.toString(),
                messageJp = "ログ表示行数が少なすぎます(500が下限)", // Lang.cs:4050
            )
        }

        // Config.cs:456-460 —— 夹取链的最后一条：WINAPI 不受支持，改回 TEXTRENDERER。
        if (drawingMode == "WINAPI") {
            found += ConfigClampNotice(
                kind = ConfigClampNotice.Kind.WINAPI_UNSUPPORTED,
                key = ConfigKey.TextDrawingMode,
                requested = drawingMode,
                effective = "TEXTRENDERER",
                titleJp = null, // Config.cs:458 用的是 MessageBox.Show(正文)，没有标题参数
                messageJp = "WINAPIモードはサポートされていません", // Lang.cs:4214
            )
            drawingMode = "TEXTRENDERER"
        }

        // Config.cs:345-352 —— 不允许覆盖 ⇒ 告警强制开。
        val warn = if (!source.itemOf(ConfigKey.AllowFunctionOverloading).bool()) {
            true
        } else {
            source.itemOf(ConfigKey.WarnFunctionOverloading).bool()
        }

        // Config.cs:412-430
        val languageConstant = source.itemOf(ConfigKey.useLanguage).enumConstant()
        val lang = ConfigEnumKind.USE_LANGUAGE.languageSetting(languageConstant)

        // Config.cs:461-466
        val shift = if (drawingMode != "WINAPI") maxOf(2, size / 6) else 0
        val drawable = source.itemOf(ConfigKey.WindowX).int() - shift

        // Config.cs:594-604
        val needReduce = when (source.itemOf(ConfigKey.ReduceArgumentOnLoad).enumConstant()) {
            "YES" -> true // Config.cs:596-599
            "NO" -> false // Config.cs:600-603
            else -> null // ONCE：原版什么都不做，保持上一次的值（Config.cs:594-595）
        }

        notices = found
        fontSize = size
        lineHeight = height
        saveDataNos = saveNos
        maxLog = MaxLogSetting(
            requested = requestedMaxLog,
            effective = if (raised) MaxLogSetting.FLOOR else requestedMaxLog,
            raisedToFloor = raised,
        )
        textDrawingMode = drawingMode
        warnFunctionOverloading = warn
        language = lang
        shapePositionShift = shift
        drawableWidth = drawable
        needReduceArgumentOnLoad = needReduce
    }

    fun bool(key: ConfigKey): Boolean = source.itemOf(key).bool()

    /** 四个被夹取的整型条目在这里返回**生效值**，其余返回条目原值（见类 KDoc「夹取不回写」）。 */
    fun int(key: ConfigKey): Int = when (key) {
        ConfigKey.FontSize -> fontSize
        ConfigKey.LineHeight -> lineHeight
        ConfigKey.SaveDataNos -> saveDataNos
        ConfigKey.MaxLog -> maxLog.effective
        else -> source.itemOf(key).int()
    }

    fun long(key: ConfigKey): Long = source.itemOf(key).long()

    fun string(key: ConfigKey): String = source.itemOf(key).string()

    fun char(key: ConfigKey): Char = source.itemOf(key).char()

    fun color(key: ConfigKey): ConfigValue.ColorValue = source.itemOf(key).color()

    fun stringList(key: ConfigKey): List<String> = source.itemOf(key).stringList()

    fun enumConstant(key: ConfigKey): String = when (key) {
        ConfigKey.TextDrawingMode -> textDrawingMode
        else -> source.itemOf(key).enumConstant()
    }

    companion object {

        /** 下限 8（spec: `Config.cs:431-435`）。 */
        const val FONT_SIZE_FLOOR = 8

        /** 下限/上限 20/80（spec: `Config.cs:441-450`）。 */
        const val SAVE_DATA_NOS_FLOOR = 20
        const val SAVE_DATA_NOS_CEILING = 80

        /** 对话框标题（spec: `Lang.cs:4030`）。 */
        const val CONFIG_ERROR_TITLE_JP = "設定のエラー"

        /**
         * 跑一遍 `Config.SetConfig` 的读取 + 夹取链。
         * 要求 [store] 的 `GAME` 组条目齐全（缺条目会立即失败，而不是像原版那样
         * `GetConfigValue<T>` 抛 `NullReferenceException`）。
         */
        fun of(store: ConfigStore): EffectiveConfig = EffectiveConfig(store).also { it.recompute() }

        private fun clampNotice(
            kind: ConfigClampNotice.Kind,
            key: ConfigKey,
            requested: String,
            effective: String,
            messageJp: String,
        ): ConfigClampNotice = ConfigClampNotice(
            kind = kind,
            key = key,
            requested = requested,
            effective = effective,
            titleJp = CONFIG_ERROR_TITLE_JP, // Config.cs:433/438/443/448/453 的第一参数都是 Lang.MessageBox.ConfigError
            messageJp = messageJp,
        )
    }
}
