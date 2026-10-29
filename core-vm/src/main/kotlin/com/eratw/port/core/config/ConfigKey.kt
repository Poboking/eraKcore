package com.eratw.port.core.config

/**
 * `emuera.config` 的条目标识 —— C# `ConfigCode` 枚举的**逐成员镜像**
 * （spec: `ConfigCode.cs:3-129`，声明顺序与显式赋值都照抄，`code` 就是 C# 里的枚举值）。
 *
 * 为什么要连 `code` 一起抄：
 *   * C# 侧条目名 `AConfigItem.Name` 直接取枚举成员字符串（spec: `AConfigItem.cs:21`
 *     `Name = Enums.AsString<ConfigCode>(code)`），所以本枚举的成员名必须与 C# 完全一致
 *     （含 `useLanguage`、`pbandDef`、`Ctrl_Z_Enabled` 这类不符合常规命名的大小写与下划线，
 *     spec: `ConfigCode.cs:70`、`:96`、`:111`）；
 *   * 配置键的匹配口径是「把读到的键 `ToUpperInvariant` 后与 `Name` 比」
 *     （spec: `ConfigData.cs:264` 与 `ConfigData.cs:269`），而 `Name` 本身**没有**被大写化
 *     （spec: `AConfigItem.cs:21` 只赋枚举字符串，`:22-23` 只对 `Text`/`EngText` 做大写化），
 *     于是 `Name` 那条分支对全小写混合的枚举成员恒不成立 —— 本镜像保留同样的比较，
 *     不「顺手修好」（ADR-005）；
 *   * `ConfigCode.cs:52` 把 `EditorType` 插在 `TextEditor`(48) 与 `EditorArgument`(49) 之间，
 *     顺序是乱的 —— 原样保留，将来若要做 `Enum.IsDefined` 一类的判定（spec: `ConfigData.cs:401`）
 *     才不会多/少一个成员。
 *
 * `UseImageBuffer`(10) 只在枚举里存在、`ConfigData.cs` 的 `setDefault()` 里**没有**注册条目
 * （spec: `ConfigData.cs:45-174` 全表无它），因此它在 [ConfigSpecTable] 里没有对应 [ConfigSpec]，
 * 读配置时命中不了（等价于 C# 的 `GetConfigItem(ConfigCode.UseImageBuffer) == null`，
 * spec: `ConfigData.cs:250-260`）。
 *
 * 纯 JVM：无 Android API、无 `kotlin.random`、无可变静态状态（伴生索引是只读 `Map`）。
 */
enum class ConfigKey(val code: Int) {
    IgnoreCase(0), // ConfigCode.cs:5
    UseRenameFile(1), // ConfigCode.cs:6
    UseReplaceFile(2), // ConfigCode.cs:7
    UseMouse(3), // ConfigCode.cs:8
    UseMenu(4), // ConfigCode.cs:9
    UseDebugCommand(5), // ConfigCode.cs:10
    AllowMultipleInstances(6), // ConfigCode.cs:11
    AutoSave(7), // ConfigCode.cs:12
    SizableWindow(8), // ConfigCode.cs:13
    TextDrawingMode(9), // ConfigCode.cs:14
    UseImageBuffer(10), // ConfigCode.cs:15 —— 无条目注册，见类 KDoc
    WindowX(11), // ConfigCode.cs:16
    WindowY(12), // ConfigCode.cs:17
    MaxLog(13), // ConfigCode.cs:18
    PrintCPerLine(14), // ConfigCode.cs:19
    PrintCLength(15), // ConfigCode.cs:20
    FontName(16), // ConfigCode.cs:21
    FontSize(17), // ConfigCode.cs:22
    LineHeight(18), // ConfigCode.cs:23
    ForeColor(19), // ConfigCode.cs:24
    BackColor(20), // ConfigCode.cs:25
    FocusColor(21), // ConfigCode.cs:26
    LogColor(22), // ConfigCode.cs:27
    FPS(23), // ConfigCode.cs:28
    SkipFrame(24), // ConfigCode.cs:29
    InfiniteLoopAlertTime(25), // ConfigCode.cs:30
    DisplayWarningLevel(26), // ConfigCode.cs:31
    DisplayReport(27), // ConfigCode.cs:32
    ReduceArgumentOnLoad(28), // ConfigCode.cs:33
    IgnoreUncalledFunction(30), // ConfigCode.cs:34 —— 29 在原版枚举里就是空档
    FunctionNotFoundWarning(31), // ConfigCode.cs:35
    FunctionNotCalledWarning(32), // ConfigCode.cs:36
    ChangeMasterNameIfDebug(34), // ConfigCode.cs:37 —— 33 同样是空档
    LastKey(35), // ConfigCode.cs:38
    ButtonWrap(36), // ConfigCode.cs:39
    SearchSubdirectory(37), // ConfigCode.cs:40
    SortWithFilename(38), // ConfigCode.cs:41
    SetWindowPos(39), // ConfigCode.cs:42
    WindowPosX(40), // ConfigCode.cs:43
    WindowPosY(41), // ConfigCode.cs:44
    ScrollHeight(42), // ConfigCode.cs:45
    SaveDataNos(43), // ConfigCode.cs:46
    WarnBackCompatibility(44), // ConfigCode.cs:47
    AllowFunctionOverloading(45), // ConfigCode.cs:48
    WarnFunctionOverloading(46), // ConfigCode.cs:49
    WindowMaximixed(47), // ConfigCode.cs:50
    TextEditor(48), // ConfigCode.cs:51
    EditorType(99), // ConfigCode.cs:52 —— 声明位置在 48 与 49 之间，原样保留
    EditorArgument(49), // ConfigCode.cs:53
    WarnNormalFunctionOverloading(50), // ConfigCode.cs:54
    CompatiErrorLine(51), // ConfigCode.cs:55
    CompatiCALLNAME(52), // ConfigCode.cs:56
    DebugShowWindow(53), // ConfigCode.cs:57
    DebugWindowTopMost(54), // ConfigCode.cs:58
    DebugWindowWidth(55), // ConfigCode.cs:59
    DebugWindowHeight(56), // ConfigCode.cs:60
    DebugSetWindowPos(57), // ConfigCode.cs:61
    DebugWindowPosX(58), // ConfigCode.cs:62
    DebugWindowPosY(59), // ConfigCode.cs:63
    UseSaveFolder(60), // ConfigCode.cs:64
    CompatiRAND(61), // ConfigCode.cs:65
    CompatiDRAWLINE(62), // ConfigCode.cs:66 —— 读取时被重定向到 CompatiLinefeedAs1739，见 [ConfigSpec.redirectOnRead]
    CompatiFunctionNoignoreCase(63), // ConfigCode.cs:67
    SystemAllowFullSpace(64), // ConfigCode.cs:68
    CompatiLinefeedAs1739(65), // ConfigCode.cs:69
    useLanguage(66), // ConfigCode.cs:70 —— 原版首字母小写
    SystemSaveInBinary(67), // ConfigCode.cs:71
    CompatiFuncArgAutoConvert(68), // ConfigCode.cs:72
    CompatiFuncArgOptional(69), // ConfigCode.cs:73
    AllowLongInputByMouse(70), // ConfigCode.cs:74
    CompatiCallEvent(71), // ConfigCode.cs:75
    SystemIgnoreTripleSymbol(72), // ConfigCode.cs:76
    CompatiSPChara(73), // ConfigCode.cs:77
    TimesNotRigorousCalculation(74), // ConfigCode.cs:78
    SystemNoTarget(75), // ConfigCode.cs:79
    SystemIgnoreStringSet(76), // ConfigCode.cs:80
    UseLazyLoading(77), // ConfigCode.cs:81
    MoneyLabel(100), // ConfigCode.cs:82
    MoneyFirst(101), // ConfigCode.cs:83
    LoadLabel(102), // ConfigCode.cs:84
    MaxShopItem(103), // ConfigCode.cs:85
    DrawLineString(104), // ConfigCode.cs:86
    BarChar1(105), // ConfigCode.cs:87
    BarChar2(106), // ConfigCode.cs:88
    TitleMenuString0(107), // ConfigCode.cs:89
    TitleMenuString1(108), // ConfigCode.cs:90
    ComAbleDefault(109), // ConfigCode.cs:91
    StainDefault(110), // ConfigCode.cs:92
    TimeupLabel(111), // ConfigCode.cs:93
    ExpLvDef(112), // ConfigCode.cs:94
    PalamLvDef(113), // ConfigCode.cs:95
    pbandDef(114), // ConfigCode.cs:96 —— 原版首字母小写
    RelationDef(115), // ConfigCode.cs:97
    UseKeyMacro(162), // ConfigCode.cs:98
    ForbidUpdateCheck(163), // ConfigCode.cs:99
    UseERD(164), // ConfigCode.cs:100
    VarsizeDimConfig(165), // ConfigCode.cs:101
    CheckDuplicateIdentifier(166), // ConfigCode.cs:102
    ReplaceContinuationBR(167), // ConfigCode.cs:103
    ValidExtension(200), // ConfigCode.cs:104
    ZipSaveData(201), // ConfigCode.cs:105
    RikaiEnabled(250), // ConfigCode.cs:106
    RikaiFilename(251), // ConfigCode.cs:107
    RikaiColorBack(252), // ConfigCode.cs:108
    RikaiColorText(253), // ConfigCode.cs:109
    RikaiUseSeparateBoxes(254), // ConfigCode.cs:110
    Ctrl_Z_Enabled(255), // ConfigCode.cs:111 —— 原版带下划线
    EnglishConfigOutput(256), // ConfigCode.cs:112
    EmueraLang(257), // ConfigCode.cs:113
    EmueraIcon(258), // ConfigCode.cs:114
    CBUseClipboard(259), // ConfigCode.cs:115
    CBIgnoreTags(260), // ConfigCode.cs:116
    CBReplaceTags(261), // ConfigCode.cs:117
    CBNewLinesOnly(262), // ConfigCode.cs:118
    CBClearBuffer(263), // ConfigCode.cs:119
    CBTriggerLeftClick(264), // ConfigCode.cs:120
    CBTriggerMiddleClick(265), // ConfigCode.cs:121
    CBTriggerDoubleLeftClick(266), // ConfigCode.cs:122
    CBTriggerAnyKeyWait(267), // ConfigCode.cs:123 —— 该条目的英/日文标签带尾随空格，读取时永不匹配，见 [ConfigSpec] 里对应行
    CBTriggerInputWait(268), // ConfigCode.cs:124
    CBMaxCB(269), // ConfigCode.cs:125
    CBBufferSize(270), // ConfigCode.cs:126
    CBScrollCount(271), // ConfigCode.cs:127
    CBMinTimer(272), // ConfigCode.cs:128
    ;

    companion object {
        private val byCodeMap: Map<Int, ConfigKey> = entries.associateBy { it.code }

        /** 按 C# 显式枚举值取键（`ConfigCode.cs` 的数值口径，不是 Kotlin `ordinal`）。 */
        fun fromCode(code: Int): ConfigKey? = byCodeMap[code]
    }
}
