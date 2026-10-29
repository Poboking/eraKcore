package com.eratw.port.core.config

import java.util.Locale

/**
 * 配置条目的**值模型**与类型标签 —— C# 用 `ConfigItem<T>` 的泛型实参区分条目类型
 * （spec: `ConfigItem.cs:10-51`），`T` 的取值集合就是 [ConfigValueType] 的成员；
 * 解析期靠 `this is ConfigItem<X>` 的类型探测分派（spec: `ConfigItem.cs:101-211`）。
 *
 * Kotlin 侧不用 `Any` 兜底：[ConfigValue] 是密封接口，读取端拿到的永远是类型确定的值，
 * 避免「配置层往渲染层传 `Any` 再强转」这种把类型错误推迟到运行期的做法
 * （C# 的 `GetConfigValue<T>` 正是 `(ConfigItem<T>)` 强转，类型写错就 `InvalidCastException`，
 * spec: `ConfigData.cs:217-220`）。
 */
sealed interface ConfigValue {

    /** `ConfigItem<bool>`（spec: `ConfigItem.cs:101-109`）；写出时是 `YES`/`NO`（spec: `ConfigItem.cs:55-62`）。 */
    data class Bool(val value: Boolean) : ConfigValue

    /** `ConfigItem<int>`；C# 用 `int.TryParse`，超出 `Int` 范围即解析失败并抛错（spec: `ConfigItem.cs:127-135`）。 */
    data class IntValue(val value: Int) : ConfigValue

    /** `ConfigItem<long>`；只有 `LastKey` 与 `pbandDef`/`RelationDef` 用它（spec: `ConfigData.cs:86`、`:173-174`）。 */
    data class LongValue(val value: Long) : ConfigValue

    /** `ConfigItem<string>`：解析时整串取值并 `Trim()`（spec: `ConfigItem.cs:160-164`）。 */
    data class StringValue(val value: String) : ConfigValue

    /** `ConfigItem<char>`：只有长度为 1 的串能解析成功，**失败时静默保留旧值**（spec: `ConfigItem.cs:119-126`）。 */
    data class CharValue(val value: Char) : ConfigValue

    /**
     * `ConfigItem<Color>`：`R`/`G`/`B` 三个 0..255 的分量（spec: `ConfigItem.cs:252-274`）。
     * 不用 `java.awt.Color`，本层要保持纯数据且无 AWT 依赖。
     */
    data class ColorValue(val r: Int, val g: Int, val b: Int) : ConfigValue {
        /** `ConfigItem.ValueToString` 的写出格式 `R,G,B`（spec: `ConfigItem.cs:63-67`）。 */
        fun toConfigString(): String = "$r,$g,$b"
    }

    /** `ConfigItem<List<string>>`：解析时按 `,` 切分并逐项 `Trim()`（spec: `ConfigItem.cs:215-225`）。 */
    data class StringList(val value: List<String>) : ConfigValue {
        /**
         * 通用写出格式：逗号连接、无空格（spec: `ConfigItem.cs:68-80`，逗号是**前置**的）。
         * `ValidExtension` 在保存时另走一条手写拼接分支（spec: `ConfigData.cs:437-447`：
         * 标签 + `:` + 逐项追加「项 + 逗号」，最后无条件删掉末位字符），列表非空时两者
         * 逐字符相同；**列表为空时手写分支会把冒号一起删掉**，与本条不同 ——
         * 见 `EmueraConfigWriter.gameLines` 的对应分支。
         */
        fun toConfigString(): String = value.joinToString(",")
    }

    /**
     * `ConfigItem<List<long>>`：解析时按 `/` 切分（spec: `ConfigItem.cs:145-159`）。
     *
     * **本类型没有写出侧**：C# 的 `ValueToString` 只对 `List<string>` 做了逗号拼接特例
     * （spec: `ConfigItem.cs:68-80`），`List<long>` 落到末尾的 `val.ToString()`
     * （spec: `ConfigItem.cs:81`），得到的是 .NET 的运行时类型名
     * `System.Collections.Generic.List`1[System.Int64]`。
     * 该串**永远不会进配置文件** —— 三个数组里只有 `configArray` 会被 `SaveConfig` 写出
     * （spec: `ConfigData.cs:430-453`），而 `List<long>` 条目全部注册在 `replaceArray`
     * （`StainDefault`/`ExpLvDef`/`PalamLvDef`，spec: `ConfigData.cs:169`、`:171-172`），
     * 它们靠 `LoadReplaceFile` 从 `_Replace.csv` 读入（spec: `Process.cs:246`），没有回写路径。
     * 因此这里不实现 `toConfigString`，避免给上层一个看起来能用的假序列化。
     * 唯一能观察到那个类型名串的路径是 ERB 的 `GETCONFIGS`（走 `ConfigData.cs:403` 的
     * `item.ValueToString()`），落点在 `core/fn/ConfigMethods.kt`：字面量在 `:96`
     * （`NET_LONG_LIST_TYPE_NAME`），分派在 `:348-350` 的 `valueToStringForErb`。
     * （旧口径曾写作「由 `core/fn/DebugMiscMethods.kt` 的 `configValueToString` 复刻」——
     * 那个函数已随 M4-J j-dispatch 的条目表收口被删，该文件现 416 行、无此函数，
     * 坐实过程见 M4-N `n-configaudit` 的交付报告，分歧编号 CFG-A3。）
     */
    data class LongList(val value: List<Long>) : ConfigValue

    /**
     * 枚举型条目（[ConfigValueType] 里 5 个 `*_ENUM` 成员）。存**规范化的常量名**而不是 Kotlin
     * enum 实例，因为 C# 侧的取值就是字符串 `Enum.TryParse(ignoreCase)` 的结果
     * （spec: `ConfigItem.cs:172-211`），且 `ValueToString` 直接 `val.ToString()` 输出常量名
     * （spec: `ConfigItem.cs:81`）。
     *
     * [constant] 也可能是**十进制数字串**：.NET 的 `Enum.TryParse` 对数字串只看底层类型
     * （这里是默认的 `Int32`）是否装得下，不检查该数是不是已定义的常量，于是
     * `DRAWING INTERFACE:9` 得到的是「值为 9 的 TextDrawingMode」而不是报错；
     * 这样一个未定义值的 `ToString()` 回吐的就是 `"9"` 本身（spec: `ConfigItem.cs:81` 的
     * `val.ToString()`）。取值口径见 [ConfigEnumKind.parseEnumConstant] 与本机实测
     * （证据段末）。原版对这 5 个条目也没有 `Enum.IsDefined` 守卫（spec: `ConfigItem.cs:174`、
     * `:182`、`:190`、`:198`、`:206` 都只用 `Enum.TryParse` 的布尔返回值判失败）。
     */
    data class EnumValue(val kind: ConfigEnumKind, val constant: String) : ConfigValue
}

/**
 * 配置条目的类型标签 —— 与 C# `ConfigItem<T>` 的 `T` 一一对应；枚举型条目按
 * [ConfigEnumKind] 细分（C# 侧是 5 个不同的 `T`，spec: `ConfigItem.cs:172-211`）。
 *
 * [tryParseReturnsTrue] 复刻的是 C# 那个**容易被漏掉的返回值差异**：`ConfigItem.TryParse`
 * 末尾 `return flag`（spec: `ConfigItem.cs:212`），而 `flag` 只在 bool/char/int/long/
 * List 与 string 各分支里被赋值（spec: `:104`、`:112`、`:121`、`:129`、`:138`、`:149-152`、
 * `:162`、`:167`），**5 个枚举分支从头到尾没有碰过它**（spec: `:172-211` 只在解析失败时
 * `throw`，成功就直接写 `Value`）。后果落在写文件那一步：
 * `ConfigData.cs:573` 是 `if (configItem.TryParse(array[1]) && fix) configItem.Fixed = true`，
 * 于是 `fixed.config` **钉不住任何枚举条目** —— 值照样被改写，但钉位永远不置。
 * 原版如此，本层按 [EmueraConfigParser.loadGameFile] 里的同一个开关原样复刻（ADR-005），
 * 解析成功的枚举行仍然是 `APPLIED`（值确实变了），只是不参与置钉。
 */
enum class ConfigValueType(
    val enumKind: ConfigEnumKind? = null,
    val tryParseReturnsTrue: Boolean = true,
) {
    BOOL,
    INT,
    LONG,
    STRING,
    CHAR,
    COLOR,
    STRING_LIST,
    LONG_LIST,
    TEXT_DRAWING_MODE_ENUM(ConfigEnumKind.TEXT_DRAWING_MODE, tryParseReturnsTrue = false),
    REDUCE_ARGUMENT_ENUM(ConfigEnumKind.REDUCE_ARGUMENT_ON_LOAD, tryParseReturnsTrue = false),
    DISPLAY_WARNING_ENUM(ConfigEnumKind.DISPLAY_WARNING_FLAG, tryParseReturnsTrue = false),
    USE_LANGUAGE_ENUM(ConfigEnumKind.USE_LANGUAGE, tryParseReturnsTrue = false),
    TEXT_EDITOR_TYPE_ENUM(ConfigEnumKind.TEXT_EDITOR_TYPE, tryParseReturnsTrue = false),
    ;

    /** 该类型缺省写出的字面量形状是否与 [ConfigValue] 的具体子类型一致，供装配期自检用。 */
    fun accepts(value: ConfigValue): Boolean = when (this) {
        BOOL -> value is ConfigValue.Bool
        INT -> value is ConfigValue.IntValue
        LONG -> value is ConfigValue.LongValue
        STRING -> value is ConfigValue.StringValue
        CHAR -> value is ConfigValue.CharValue
        COLOR -> value is ConfigValue.ColorValue
        STRING_LIST -> value is ConfigValue.StringList
        LONG_LIST -> value is ConfigValue.LongList
        TEXT_DRAWING_MODE_ENUM, REDUCE_ARGUMENT_ENUM, DISPLAY_WARNING_ENUM, USE_LANGUAGE_ENUM,
        TEXT_EDITOR_TYPE_ENUM -> value is ConfigValue.EnumValue && value.kind == enumKind
    }
}

/**
 * 五个枚举型条目的常量表 —— 顺序即 C# 声明顺序，因此**下标就是 C# 的枚举序号值**，
 * 这条是复刻 `Enum.TryParse` 的两个隐式行为所必需：
 *   1. 数字串也解析得动（spec: `ConfigItem.cs:174` 等 5 处 `Enum.TryParse<X>(text, ignoreCase: true, ...)`，
 *      且 5 处都没有 `Enum.IsDefined` 守卫）—— .NET 的 `Enum.TryParse` 对数字串只按底层类型
 *      （默认 `Int32`）判能否容纳，于是 `DRAWING INTERFACE:1` 得 `TEXTRENDERER`、
 *      `DEFAULT ANSI ENCODING:2` 得 `CHINESE_HANS`、`DRAWING INTERFACE:+1` 也得 `TEXTRENDERER`；
 *   2. 数字串**不需要是已定义的常量** —— `DRAWING INTERFACE:9` 在原版里不报错，
 *      得到未定义值 9，而它的 `ToString()` 就是 `"9"`（spec: `ConfigItem.cs:81` 的 `val.ToString()`），
 *      于是下一次 `SaveConfig` 落盘的是 `DRAWING INTERFACE:9`。
 *      本机实测（`dotnet` 9.0.2 单文件程序，2026-10-20，本机无 8.x 运行时；该数字分支自
 *      .NET Framework 4.0 起未变）：`TryParse('9')` ok=True 且 `ToString()` 为 "9"；
 *      `TryParse('-1')` ok=True；`TryParse('2147483647')` ok=True；`TryParse('2147483648')`
 *      ok=False（超 Int32 ⇒ 原版抛 `InvalidSpecification`）；`TryParse('1,2')` ok=False
 *      （非 flags 枚举不吃逗号组合，spec: `TextDrawingMode.cs` 等 5 个文件均无 `Flags` 特性）。
 *      生效读数那一侧也跟着原版：未定义的 `UseLanguage` 值在 `Config.cs:413-430` 那个
 *      switch 里一个 case 都不进（**没有 default 分支**），`Config.Language` 保持 0；
 *      未定义的 `TextDrawingMode` 不等于 `WINAPI`，所以既不告警也不改写（spec: `Config.cs:456-460`）。
 * 两条都由 [parseEnumConstant] 落地。
 */
enum class ConfigEnumKind(val constants: List<String>) {

    /** spec: `TextDrawingMode.cs:3-8`（GRAPHICS=0、TEXTRENDERER=1、WINAPI=2）。 */
    TEXT_DRAWING_MODE(listOf("GRAPHICS", "TEXTRENDERER", "WINAPI")),

    /** spec: `ReduceArgumentOnLoadFlag.cs:3-8`（YES=0、ONCE=1、NO=2）。 */
    REDUCE_ARGUMENT_ON_LOAD(listOf("YES", "ONCE", "NO")),

    /** spec: `DisplayWarningFlag.cs:3-9`（IGNORE=0、LATER=1、ONCE=2、DISPLAY=3）。 */
    DISPLAY_WARNING_FLAG(listOf("IGNORE", "LATER", "ONCE", "DISPLAY")),

    /** spec: `UseLanguage.cs:3-9`。 */
    USE_LANGUAGE(listOf("JAPANESE", "KOREAN", "CHINESE_HANS", "CHINESE_HANT")),

    /** spec: `TextEditorType.cs:3-9`。 */
    TEXT_EDITOR_TYPE(listOf("SAKURA", "TERAPAD", "EMEDITOR", "USER_SETTING")),
    ;

    /**
     * 仅 [USE_LANGUAGE] 有意义：`Config.SetConfig` 末尾按语言常量设置 `Config.Language`
     * （Windows LCID）并调用 `LangManager.setEncode`（ANSI 代码页），
     * spec: `Config.cs:412-430`。其它种类返回 null（C# 里没有对应 switch 分支）。
     */
    fun languageSetting(constant: String): LanguageSetting? {
        if (this !== USE_LANGUAGE) {
            return null
        }
        return when (constant) {
            "JAPANESE" -> LanguageSetting(1041, 932) // Config.cs:414-417
            "KOREAN" -> LanguageSetting(1042, 949) // Config.cs:418-421
            "CHINESE_HANS" -> LanguageSetting(2052, 936) // Config.cs:422-425
            "CHINESE_HANT" -> LanguageSetting(1028, 950) // Config.cs:426-429
            else -> null
        }
    }

    companion object {

        /** 常量名（大写）到序号；大小写不敏感比较用 [Locale.ROOT] 口径（C# 是 `ignoreCase: true`）。 */
        fun byName(kind: ConfigEnumKind, text: String): Pair<String, Int>? {
            val upper = text.trim().uppercase(Locale.ROOT)
            kind.constants.forEachIndexed { index, constant ->
                if (constant == upper) {
                    return constant to index
                }
            }
            return null
        }

        /**
         * `Enum.TryParse` 的形状，三段（spec: `ConfigItem.cs:174` 等 5 处的调用与
         * 「失败才 throw」的判定；返回 null 即调用方抛「不正な指定です」，spec: `ConfigItem.cs:176` 等）：
         *   1. 常量名（忽略大小写）命中 ⇒ 规范常量名；
         *   2. 否则按**十进制整数**读（可带正负号，`.NET` 数字串只受底层 `Int32` 的范围约束）：
         *      命中某个已定义序号 ⇒ 该常量名（`02`、`+1` 这类写法会被规范化，实测见类 KDoc）；
         *      没命中 ⇒ **未定义值照样解析成功**，存 `Int.toString()` 的十进制串
         *      （与 `val.ToString()` 对未定义枚举值输出数字串的行为一致，spec: `ConfigItem.cs:81`）；
         *   3. 两者都不中（含超出 `Int32` 的数字串）⇒ null，调用方抛错。
         *
         * 未复刻项：.NET 对 `[Flags]` 枚举接受 `A,B` 形式的逗号组合 —— C# 这五个枚举都不是
         * flags 枚举（spec: `TextDrawingMode.cs` 等 5 个文件均无 `Flags` 特性），实测 `1,2` 也解析失败。
         */
        fun parseEnumConstant(kind: ConfigEnumKind, text: String): String? {
            byName(kind, text)?.let { return it.first }
            val digits = text.trim().toIntOrNull() ?: return null // null = 非十进制整数或超 Int32 ⇒ 原版 TryParse 返回 false
            return if (digits in kind.constants.indices) kind.constants[digits] else digits.toString()
        }
    }
}

/**
 * `UseLanguage` 常量对应的语言参数 —— LCID 与 ANSI 代码页
 * （spec: `Config.cs:412-430`；`Language` 属性声明 spec: `Config.cs:171`）。
 */
data class LanguageSetting(val languageIdentifier: Int, val ansiCodePage: Int)

/**
 * 配置解析期抛出的错误 —— C# 的 `CodeEE`（Emuera 脚本/配置通用错误异常）。
 *
 * C# 侧 `ConfigItem.TryParse` 对 4 类情况抛错，消息取自 `Lang.Error`：
 *   * Color 形状不对：`NotExistColorSpecifier`「値をColor指定子として認識できません」
 *     （spec: `ConfigItem.cs:110-118`，文案 spec: `Lang.cs:958`）；
 *   * int / long 含非数字：`ContainsNonNumericCharacters`「数字でない文字が含まれています」
 *     （spec: `ConfigItem.cs:127-144`，文案 spec: `Lang.cs:962`）；
 *   * 5 个枚举型取值不在常量表里：`InvalidSpecification`「不正な指定です」
 *     （spec: `ConfigItem.cs:172-211`，文案 spec: `Lang.cs:966`）；
 *   * bool 既不是数字也不是 YES/NO/TRUE/FALSE/前/後：`InvalidSpecification`
 *     （spec: `ConfigItem.cs:249`，文案 spec: `Lang.cs:966`）。
 *
 * 抛出后的**控制流后果**同样要复刻：C# 的 `try/catch` 包住的是整个读行循环
 * （spec: `ConfigData.cs:524-586`），所以一条坏行会中止**本文件剩余全部行**的读取，
 * 坏行之前已生效的改动保留、之后的全部丢失。见 [EmueraConfigParser] 的 `abortedAt`。
 */
data class ConfigParseException(
    val langKey: LangKey,
    val lineNo: Int,
    val configKey: ConfigKey,
    val rawValue: String,
) {

    /** 与 C# `Lang.Error.X.Text` 等价的日文原文（本层不做本地化，M5 接 `Lang` 时再换）。 */
    val message: String
        get() = langKey.jpText

    enum class LangKey(val jpText: String) {
        NOT_EXIST_COLOR_SPECIFIER("値をColor指定子として認識できません"), // Lang.cs:958
        CONTAINS_NON_NUMERIC_CHARACTERS("数字でない文字が含まれています"), // Lang.cs:962
        INVALID_SPECIFICATION("不正な指定です"), // Lang.cs:966
    }
}

/**
 * 一次配置错误产生的**告警** —— C# 侧 `catch` 到异常后走
 * `ParserMediator.ConfigWarn(message, pos, 1, stack)`（spec: `ConfigData.cs:579-586`），
 * 级别固定 1；是否记入 `warningList` 取决于 `level >= Config.DisplayWarningLevel`
 * 或处于分析模式（spec: `ParserMediator.cs:52-58`）。
 *
 * 这里**不做**该门控：`Config.DisplayWarningLevel` 是在 `SetConfig` 里才落到静态字段的
 * （spec: `Config.cs:353`），而读文件发生在更早的 `LoadConfig`（spec: `ConfigData.cs:491-493`），
 * 首次加载时静态字段还是 0（C# 静态 `int` 缺省），故 `1 >= 0` 恒真、告警恒被记录。
 * 门控属于上层告警呈现，本层一律收集并原样交给上层（LOG-FLOOR 同族的「数据层不吞提示」政策）。
 */
data class ConfigWarning(
    val fileName: String,
    val lineNo: Int,
    val level: Int,
    val message: String,
    val configKey: ConfigKey?,
)
