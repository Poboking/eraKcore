package com.eratw.port.core.config

/**
 * 一个配置条目的**运行期状态**（值 + `Fixed` 位），C# 对应 `ConfigItem<T>` 的实例
 * （spec: `ConfigItem.cs:10-51`）。
 *
 * `Fixed` 是「被 `fixed.config` 钉死」的标记，它改变的不只是解析：
 *   * [tryParse] 在 `Fixed` 时**先返回 false**，连 `Trim` 都不做（spec: `ConfigItem.cs:96-99`）；
 *   * 值写入器 [assign] 在 `Fixed` 时**静默忽略**（spec: `ConfigItem.cs:20-27`），
 *     所以连 `EditorArgument` 那条绕过 `TryParse` 的直写分支、以及设置界面的赋值都同样无效；
 *   * 设置界面据此把控件 `Enabled = !configItem.Fixed` 灰掉（spec: `ConfigDialog.cs:689`、`:705`）；
 *   * `ReLoadConfig` 会把所有 `Fixed` 先清零再重读（spec: `ConfigData.cs:466-477`）。
 *
 * 原版把「钉住的项」放在最后一步读（spec: `ConfigData.cs:491-493`：
 * `default.config` → `emuera.config` → `fixed.config`），配合上面两条守卫，
 * 结果就是游戏目录里的 `_fixed.config` 能压过玩家自己的 `emuera.config`。
 */
class ConfigItem(val spec: ConfigSpec, initialValue: ConfigValue = spec.defaultValue) {

    /** 当前值；缺省等于 [ConfigSpec.defaultValue]（spec: `ConfigData.cs:45-174` 里 `ConfigCode` 之后的第三个实参）。 */
    var value: ConfigValue = initialValue
        private set

    /** 是否被 `fixed.config` 钉住（spec: `AConfigItem.cs:16`，赋值点 spec: `ConfigData.cs:573-576`）。 */
    var fixed: Boolean = false
        internal set

    /**
     * C# 的 `Value` setter：`Fixed` 时丢弃写入（spec: `ConfigItem.cs:20-27`）。
     *
     * @return 是否真的写进去了
     */
    fun assign(newValue: ConfigValue): Boolean {
        if (fixed) {
            return false
        }
        require(spec.valueType.accepts(newValue)) {
            "${spec.key} 是 ${spec.valueType} 型，不能写入 ${newValue::class.simpleName}"
        }
        value = newValue
        return true
    }

    /**
     * 类型化的布尔读数 —— C# 的 `GetConfigValue<bool>` 是 `(ConfigItem<bool>)` 强转
     * （spec: `ConfigData.cs:217-220`），类型不对就在运行期 `InvalidCastException`；
     * 本层把它变成即时、带条目名的失败，避免错误推迟到消费点。
     */
    fun bool(): Boolean = (value as ConfigValue.Bool).value

    fun int(): Int = (value as ConfigValue.IntValue).value

    fun long(): Long = (value as ConfigValue.LongValue).value

    fun string(): String = (value as ConfigValue.StringValue).value

    fun char(): Char = (value as ConfigValue.CharValue).value

    fun color(): ConfigValue.ColorValue = value as ConfigValue.ColorValue

    fun stringList(): List<String> = (value as ConfigValue.StringList).value

    fun longList(): List<Long> = (value as ConfigValue.LongList).value

    /** 枚举型条目的常量名（`ConfigItem.GetValue<TextDrawingMode>()` 一类读数的字符串形态）。 */
    fun enumConstant(): String = (value as ConfigValue.EnumValue).constant

    /**
     * `ConfigItem.TryParse`（spec: `ConfigItem.cs:89-213`）。
     *
     * 副作用与原版逐项对齐：
     *   * 空串 / null 在最前面直接返回，**不看 `Fixed`**（spec: `ConfigItem.cs:92-95`，
     *     `Fixed` 的守卫在 `:96-99`，所以顺序是「先空判、再钉死判」）；
     *   * `char` 型解析失败**不抛错**，静默保留旧值（spec: `ConfigItem.cs:119-126`，
     *     `flag` 为 false 就不进赋值）；`int`/`long` 同形状的代码后面都跟一条 `throw`
     *     （spec: `ConfigItem.cs:127-144`），`char` 是唯一没有的；
     *   * `List<long>` 型**先把现列表清空再逐项解析**（spec: `ConfigItem.cs:147-158`），
     *     中途抛错时旧值已经没了、只剩下解析成功的前缀 —— 本层因为值对象不可变，
     *     把「前缀」显式写回（[TryParseOutcome.Failed.partial] 为 true），语义等价；
     *   * 抛错一律是 `CodeEE` 形状（spec: `ConfigItem.cs:115`、`:132`、`:141`、`:157`、`:176` 等），
     *     由上层 `catch` 转成告警并中止本文件剩余行（spec: `ConfigData.cs:579-586`）；
     *   * **返回值与「值有没有被改」不是一回事**：C# 末尾 `return flag`（spec: `ConfigItem.cs:212`），
     *     而 5 个枚举分支解析成功后从不给 `flag` 赋值（spec: `:172-211`）—— 于是枚举行
     *     「改了值但返回 false」，`fixed.config` 因此钉不住它们（spec: `ConfigData.cs:573`）。
     *     本层同样用 [TryParseOutcome.Applied] 表示「值改了」，返回位挂在
     *     [ConfigValueType.tryParseReturnsTrue] 上，由 `EmueraConfigParser` 决定置钉。
     */
    fun tryParse(rawParam: String?, lineNo: Int): TryParseOutcome {
        if (rawParam == null || rawParam.isEmpty()) {
            return TryParseOutcome.NotParsedEmptyValue // ConfigItem.cs:92-95
        }
        if (fixed) {
            return TryParseOutcome.NotParsedFixed // ConfigItem.cs:96-99
        }
        val text = rawParam.trim() // ConfigItem.cs:100
        return when (spec.valueType) {
            ConfigValueType.BOOL -> {
                val p = parseBoolLiteral(text)
                if (p == null) {
                    fail(ConfigParseException.LangKey.INVALID_SPECIFICATION, rawParam, lineNo)
                } else {
                    finishAssign(ConfigValue.Bool(p)) // ConfigItem.cs:101-109
                }
            }

            ConfigValueType.INT -> {
                val n = parseIntStrictly(text)
                if (n == null) {
                    fail(ConfigParseException.LangKey.CONTAINS_NON_NUMERIC_CHARACTERS, rawParam, lineNo)
                } else {
                    finishAssign(ConfigValue.IntValue(n)) // ConfigItem.cs:127-135
                }
            }

            ConfigValueType.LONG -> {
                val n = parseLongStrictly(text)
                if (n == null) {
                    fail(ConfigParseException.LangKey.CONTAINS_NON_NUMERIC_CHARACTERS, rawParam, lineNo)
                } else {
                    finishAssign(ConfigValue.LongValue(n)) // ConfigItem.cs:136-144
                }
            }

            ConfigValueType.CHAR -> if (text.length == 1) {
                finishAssign(ConfigValue.CharValue(text[0]))
            } else {
                TryParseOutcome.NotParsedSilently // ConfigItem.cs:119-126（char.TryParse 失败不抛）
            }

            ConfigValueType.STRING -> finishAssign(ConfigValue.StringValue(text)) // ConfigItem.cs:160-164

            ConfigValueType.COLOR -> {
                val c = parseColorTriple(text)
                if (c == null) {
                    fail(ConfigParseException.LangKey.NOT_EXIST_COLOR_SPECIFIER, rawParam, lineNo)
                } else {
                    finishAssign(c) // ConfigItem.cs:110-118
                }
            }

            ConfigValueType.STRING_LIST -> finishAssign(
                ConfigValue.StringList(text.split(',').map { it.trim() }),
            ) // ConfigItem.cs:165-171 + :215-225

            ConfigValueType.LONG_LIST -> parseLongList(text, rawParam, lineNo) // ConfigItem.cs:145-159

            ConfigValueType.TEXT_DRAWING_MODE_ENUM, ConfigValueType.REDUCE_ARGUMENT_ENUM,
            ConfigValueType.DISPLAY_WARNING_ENUM, ConfigValueType.USE_LANGUAGE_ENUM,
            ConfigValueType.TEXT_EDITOR_TYPE_ENUM -> {
                val kind = requireNotNull(spec.valueType.enumKind)
                val constant = ConfigEnumKind.parseEnumConstant(kind, text)
                if (constant == null) {
                    fail(ConfigParseException.LangKey.INVALID_SPECIFICATION, rawParam, lineNo)
                } else {
                    finishAssign(ConfigValue.EnumValue(kind, constant)) // ConfigItem.cs:172-211
                }
            }
        }
    }

    private fun parseLongList(text: String, rawParam: String, lineNo: Int): TryParseOutcome {
        // ConfigItem.cs:147 `Value.Clear()` —— 旧值先没了；然后 :149-158 逐项解析，失败即抛。
        val parsed = ArrayList<Long>()
        for (token in text.split('/')) {
            val n = parseLongStrictly(token.trim())
            if (n == null) {
                assign(ConfigValue.LongList(parsed))
                return fail(
                    ConfigParseException.LangKey.CONTAINS_NON_NUMERIC_CHARACTERS,
                    rawParam,
                    lineNo,
                    partial = true,
                )
            }
            parsed.add(n)
        }
        return finishAssign(ConfigValue.LongList(parsed))
    }

    private fun finishAssign(newValue: ConfigValue): TryParseOutcome =
        if (assign(newValue)) {
            TryParseOutcome.Applied
        } else {
            TryParseOutcome.NotParsedFixed // 只有赋值途中条目已被钉住才走到；正常路径不可达
        }

    private fun fail(
        langKey: ConfigParseException.LangKey,
        rawParam: String,
        lineNo: Int,
        partial: Boolean = false,
    ): TryParseOutcome =
        TryParseOutcome.Failed(ConfigParseException(langKey, lineNo, spec.key, rawParam), partial)

    /** `ConfigData.Copy` 用的逐条目复制（spec: `ConfigItem.cs:35-41`：先解钉、写值、再复原钉位）。 */
    fun copyTo(other: ConfigItem) {
        other.fixed = false
        other.assign(value)
        other.fixed = fixed
    }

    fun copy(): ConfigItem = ConfigItem(spec, value).also { it.fixed = fixed }
}

/** [ConfigItem.tryParse] 的结果分类 —— 本路 golden 判据要逐条区分「改了什么 / 没改为什么」。 */
sealed interface TryParseOutcome {

    /** 解析成功且写入（spec: `ConfigItem.cs` 各分支里 `flag = true` 后的赋值）。 */
    data object Applied : TryParseOutcome

    /** 参数为 null 或空串，未做任何事（spec: `ConfigItem.cs:92-95`）。 */
    data object NotParsedEmptyValue : TryParseOutcome

    /** 条目被钉住，未做任何事（spec: `ConfigItem.cs:96-99`）。 */
    data object NotParsedFixed : TryParseOutcome

    /** 解析失败但原版不抛错、静默保留旧值 —— 只有 `char` 型会走到（spec: `ConfigItem.cs:119-126`）。 */
    data object NotParsedSilently : TryParseOutcome

    /**
     * 抛 `CodeEE`（spec: `ConfigItem.cs:115`、`:132`、`:141`、`:157`、`:176`、`:249`）。
     *
     * @param partial 值是否已被这次失败的**副作用**改掉（`List<long>` 的 `Clear()` 在前，
     *   spec: `ConfigItem.cs:147-158`）。为 true 时 [ConfigItem.value] 是解析成功的前缀。
     */
    data class Failed(val error: ConfigParseException, val partial: Boolean = false) : TryParseOutcome
}

/**
 * `tryStringToBool`（spec: `ConfigItem.cs:227-250`）。
 * 顺序：先当整数（`!= 0` 即真），再认 `NO`/`FALSE`/`後`（`NO`/`FALSE` 忽略大小写、
 * `後`/`前` 走 `StringComparison.Ordinal` 即严格相等，`:239`、`:244`），都不中就抛。
 *
 * @return 解析出的布尔值；`null` 表示「既不是数字也不是六个认可的写法」，调用方据此抛
 *   `InvalidSpecification`（spec: `ConfigItem.cs:249`）
 */
internal fun parseBoolLiteral(text: String): Boolean? {
    parseIntStrictly(text)?.let { return it != 0 } // ConfigItem.cs:234-238
    if (text.equals("NO", ignoreCase = true) || text.equals("FALSE", ignoreCase = true) || text == "後") {
        return false // ConfigItem.cs:239-243
    }
    if (text.equals("YES", ignoreCase = true) || text.equals("TRUE", ignoreCase = true) || text == "前") {
        return true // ConfigItem.cs:244-248
    }
    return null // ConfigItem.cs:249
}

private val SIGNED_INTEGER = Regex("^[+-]?[0-9]+$")

/**
 * `int.TryParse` 的可用子集：`NumberStyles.Integer` = 可选符号 + 至少一位数字，
 * 允许的前后空白在调用点已经 `Trim()` 掉（spec: `ConfigItem.cs:100`），故这里不再容忍空白。
 * 溢出（如 `3000000000`）与 C# 一样判失败。
 */
internal fun parseIntStrictly(text: String): Int? {
    if (!SIGNED_INTEGER.matches(text)) {
        return null
    }
    return text.toIntOrNull()
}

/** [parseIntStrictly] 的 `long` 版（spec: `ConfigItem.cs:136-144` 用 `long.TryParse`）。 */
internal fun parseLongStrictly(text: String): Long? {
    if (!SIGNED_INTEGER.matches(text)) {
        return null
    }
    return text.toLongOrNull()
}

/**
 * `tryStringsToColor`（spec: `ConfigItem.cs:252-274`）：按 `,` 切，
 * 段数少于 3 直接 false（`:256-259`）；**只取前三段**，后面多余的段忽略（`:260-270` 只解析
 * `array[0..2]`）；每段 `Trim` 后必须是 0..255 的整数，否则 false。
 * false 时调用方抛 `NotExistColorSpecifier`（spec: `ConfigItem.cs:113-116`）。
 */
internal fun parseColorTriple(text: String): ConfigValue.ColorValue? {
    val parts = text.split(',')
    if (parts.size < 3) {
        return null // ConfigItem.cs:256-259
    }

    fun component(token: String): Int? {
        val n = parseIntStrictly(token.trim()) ?: return null
        return if (n < 0 || n > 255) null else n // ConfigItem.cs:260 等三处的范围判断
    }

    val r = component(parts[0]) ?: return null // :260-263
    val g = component(parts[1]) ?: return null // :264-267
    val b = component(parts[2]) ?: return null // :268-271
    return ConfigValue.ColorValue(r, g, b) // :272
}
