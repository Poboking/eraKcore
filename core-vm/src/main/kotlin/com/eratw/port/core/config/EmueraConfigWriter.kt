package com.eratw.port.core.config

import java.util.Locale

/**
 * 配置文件的**写出**侧 —— C# `ConfigData.SaveConfig`（spec: `ConfigData.cs:424-464`）与
 * `SaveDebugConfig`（spec: `:641-665`）的数据层等价物。
 *
 * 本路实现它的唯一理由是 golden 判据需要**反向社会验证**：把语料那份真实 `emuera.config`
 * 读进 [ConfigStore]，再按原版写出规则还原成文本行，与源文件**逐字节比对**。
 * 只对解析结果做「值等于某个我算出来的期望」是自证；而「还原出的文件与真实文件相同」
 * 同时校验了标签文本、默认值、类型分派、跳过规则四件事，任何一处抄错都会露出来。
 *
 * 不做的事：换行符与编码由调用方决定。原版用 `Config.Encode`
 * = `EncodingHandler.UTF8BOMEncoding`（spec: `Config.cs:17`、`ConfigData.cs:429`），
 * 即写出时**带 UTF-8 BOM**；`debug.config` 同理（spec: `ConfigData.cs:646`）。
 */
object EmueraConfigWriter {

    /**
     * `SaveConfig` 的产物（只含 `configArray`，spec: `ConfigData.cs:430` ——
     * `replaceArray` 与 `debugArray` 都不在这条路径上）。
     *
     * @param english `Config.EnglishConfigOutput`（spec: `Config.cs:389` 从条目读进静态字段，
     *   写出时用的是**静态字段**而不是条目值，spec: `ConfigItem.cs:86`、`ConfigData.cs:441`）
     *
     * 三条「不写出」的规则逐字照抄 spec: `ConfigData.cs:433`：
     *   1. `CompatiDRAWLINE` 永不写出（它的值由 `CompatiLinefeedAs1739` 代持）；
     *   2. `ChangeMasterNameIfDebug` 为 true 时不写出 —— 于是「允许」是隐式的，
     *      配置文件里看不到这一行，下次加载回落到默认的 true，行为一致但文件与内存对不上；
     *   3. `LastKey` 为 0 时不写出（0 是「还没算过更新键」的哨兵，spec: `Config.cs:607-608`）。
     */
    fun gameLines(store: ConfigStore, english: Boolean): List<String> {
        val out = ArrayList<String>(ConfigSpecTable.gameCount)
        for (item in store.itemsOf(ConfigGroup.GAME)) {
            if (skipsWrite(item)) {
                continue // ConfigData.cs:433 三个条件
            }
            out += writeLine(item, english)
        }
        return out
    }

    /**
     * 把 [store] 合并进一份**已有的**配置文件文本 —— 写出侧的「保留未知行」版本。
     *
     * 原版 `SaveConfig`（spec: `ConfigData.cs:424-464`）每次重写整个 `configArray`，
     * 文件里不属于 `configArray` 的行（注释、空行、别的工具写的键）会被整份丢掉。
     * 本方法改为原地合并，只动自己认识的行：
     *
     *   * **已知键**（`Name` / `Text` / `EngText`，大小写不敏感）按读取侧口径匹配
     *     （spec: `ConfigData.cs:539` 先 `Trim()` 再查条目，spec: `ConfigData.cs:262-284`），
     *     命中后整行替换成 [writeLine] 的当前写出行；位置与出现次数不变 ——
     *     同一个键出现多次时每一处都替换。
     *   * **未知键、注释行、空行、缩进与原始顺序**逐字符保留，连每行自己的行尾也不动。
     *   * **缺失的已知键**按条目注册顺序（`configArray` 的遍历顺序，spec: `ConfigData.cs:430`）
     *     追加到文件末尾；被 `ConfigData.cs:433` 三条规则跳过的键不追加。
     *   * 已知键的行若当前值命中三条「不写出」规则（`CompatiDRAWLINE` 恒跳过、
     *     `ChangeMasterNameIfDebug` 为 true、`LastKey` 为 0），保留原行不动（不替换、不删除）。
     *   * 换行符：LF / CRLF 按行保留；追加块用原文件的主流行尾（多数派，平票取先出现的），
     *     原文件没有行尾时用 LF。末尾是否有换行也照原文件：原本没有的，追加后仍然没有。
     *   * 原文为空串时没有风格可循：按 `SaveConfig` 的 `WriteLine` 口径生成整份配置，
     *     块尾带一个行尾（spec: `ConfigData.cs:447`、`:451`）。
     *
     * 写出标签来自 `AConfigItem` 构造期的大写化（spec: `AConfigItem.cs:22-23`），值格式见
     * [valueToString]（spec: `ConfigItem.cs:53-87`）。有一处**必须比读取侧多认**：
     * `CBTriggerAnyKeyWait` 的英文标签以空格结尾（spec: `ConfigData.cs:140`），原版写出的
     * `…TRIGGER :NO` 因读取侧 `Trim()` 永远匹配不上（spec: `ConfigData.cs:539`）；
     * 若只按读取侧口径处理，这一行会被当成未知行保留、同时该键又被判为缺失而追加一份，
     * 凭空多出一行。因此命中不了条目时再用**未裁剪**的键与日/英标签原文比一次。
     *
     * BOM 不归本方法管：入参应已由调用方剥离（[ConfigFile.stripBom]），首字符若是 U+FEFF
     * 会被当成键的一部分而原样留下。本方法无副作用，不改 [store]。
     */
    fun mergeInto(original: String, store: ConfigStore, english: Boolean): String {
        val lines = splitLines(original)
        val out = StringBuilder(original.length + 256)
        val seen = HashSet<ConfigKey>(ConfigSpecTable.gameCount)
        for (line in lines) {
            val item = matchKnownItem(store, line.text)
            if (item == null) {
                out.append(line.text)
            } else {
                seen += item.spec.key
                out.append(if (skipsWrite(item)) line.text else writeLine(item, english))
            }
            out.append(line.eol)
        }
        val missing = store.itemsOf(ConfigGroup.GAME).filter { it.spec.key !in seen && !skipsWrite(it) }
        if (missing.isEmpty()) {
            return out.toString()
        }
        val eol = dominantEol(lines)
        if (lines.isEmpty()) {
            // 没有原文可循：SaveConfig 的 WriteLine 让每行（含末行）都带行尾。
            for ((index, item) in missing.withIndex()) {
                if (index > 0) {
                    out.append(eol)
                }
                out.append(writeLine(item, english))
            }
            out.append(eol)
            return out.toString()
        }
        val terminated = original.endsWith('\n')
        for (item in missing) {
            if (out.isNotEmpty() && out[out.length - 1] != '\n') {
                out.append(eol) // 原文末尾没有换行：只在追加块之前补分隔符，块尾仍不补
            }
            out.append(writeLine(item, english))
            if (terminated) {
                out.append(eol)
            }
        }
        return out.toString()
    }

    /**
     * `SaveDebugConfig` 的产物（spec: `ConfigData.cs:641-665`）：7 条全写、**没有任何跳过规则**，
     * 标签同样由 `Config.EnglishConfigOutput` 决定（spec: `ConfigItem.cs:86`）。
     */
    fun debugLines(store: ConfigStore, english: Boolean): List<String> =
        store.itemsOf(ConfigGroup.DEBUG).map { it.spec.labelForWrite(english) + ":" + valueToString(it) }

    /**
     * `ConfigItem.ValueToString`（spec: `ConfigItem.cs:53-82`）：
     *   * `bool` ⇒ `YES` / `NO`（`:55-62`）—— 注意写出的是大写英文，而读回时还能认
     *     `TRUE`/`FALSE`/`前`/`後`/数字（`tryStringToBool`，spec: `:227-250`）；
     *   * `Color` ⇒ `R,G,B`（`:63-67`）；
     *   * `List<string>` ⇒ 逗号连接（`:68-80`）；
     *   * 其余 ⇒ `val.ToString()`（`:81`）：整数是十进制、字符串原样、`char` 是单字符、
     *     5 个枚举是**常量名**。
     */
    fun valueToString(item: ConfigItem): String = when (val v = item.value) {
        is ConfigValue.Bool -> if (v.value) "YES" else "NO" // ConfigItem.cs:55-62
        is ConfigValue.ColorValue -> v.toConfigString() // ConfigItem.cs:63-67
        is ConfigValue.StringList -> v.toConfigString() // ConfigItem.cs:68-80
        is ConfigValue.IntValue -> v.value.toString() // ConfigItem.cs:81
        is ConfigValue.LongValue -> v.value.toString() // ConfigItem.cs:81
        is ConfigValue.StringValue -> v.value // ConfigItem.cs:81（string 的 ToString 就是它自己）
        is ConfigValue.CharValue -> v.value.toString() // ConfigItem.cs:81
        is ConfigValue.EnumValue -> v.constant // ConfigItem.cs:81（枚举 ToString 给常量名）
        is ConfigValue.LongList -> throw IllegalStateException(
            "${item.spec.key} 是 List<long> 型；原版会写出 .NET 的类型名字符串（ConfigItem.cs:81 落到 " +
                "val.ToString()），但它只存在于 replaceArray，而 SaveConfig 只遍历 configArray" +
                "（ConfigData.cs:430），因此这条路径在原版里不可达 —— 本层不产出那种垃圾串",
        )
    }

    /**
     * 一条已知键的写出行（不含行尾）—— `AConfigItem.ToString()`（spec: `ConfigItem.cs:84-87`）
     * 与 `ValidExtension` 的手写拼接分支（spec: `ConfigData.cs:437-447`）。
     */
    private fun writeLine(item: ConfigItem, english: Boolean): String {
        val label = item.spec.labelForWrite(english)
        if (item.spec.key == ConfigKey.ValidExtension) {
            // 手写拼接：标签 + 冒号，逐项追加「项 + 逗号」，最后无条件删掉末位字符（:446）。
            // 列表非空时与通用 List 分支逐字符相同；列表为空时冒号也被删掉，落盘成裸标签
            // （下一轮读回走 NO_SEPARATOR，spec: ConfigData.cs:535-538）。细节见 [gameLines] 的 KDoc。
            val builder = StringBuilder(label).append(':')
            for (extension in item.stringList()) {
                builder.append(extension).append(',') // :444
            }
            builder.setLength(builder.length - 1) // :446
            return builder.toString() // :447
        }
        return label + ":" + valueToString(item) // ConfigData.cs:451 → ConfigItem.cs:84-87
    }

    /** `ConfigData.cs:433` 的三条「不写出」规则；[gameLines] 与 [mergeInto] 共用同一份判定。 */
    private fun skipsWrite(item: ConfigItem): Boolean {
        val key = item.spec.key
        if (key == ConfigKey.CompatiDRAWLINE) {
            return true // 第一个条件
        }
        if (key == ConfigKey.ChangeMasterNameIfDebug && item.bool()) {
            return true // 第二个条件
        }
        return key == ConfigKey.LastKey && item.long() == 0L // 第三个条件
    }

    /**
     * 一行文本对应的已知条目；null 表示按「未知行」原样保留。
     *
     * 只做键查找，不复刻读取侧的其它副作用（重定向、`fix` 钉位、`EditorArgument` 直写都不在这里）。
     */
    private fun matchKnownItem(store: ConfigStore, line: String): ConfigItem? {
        if (line.isEmpty() || line[0] == ';') {
            return null // ConfigData.cs:529-532：整行没有 Trim；行首空格的 ; 不算注释，但也不会命中条目
        }
        val colon = line.indexOf(':')
        if (colon < 0) {
            // 无冒号的行原版直接跳过（ConfigData.cs:535-538）。这里把「裸标签」也当作该键已存在：
            // 可达形状只有 ValidExtension 列表为空时写出的裸标签（ConfigData.cs:446 连冒号一起删），
            // 不这样认会在下一轮合并时重复追加一份。
            val bare = line.trim()
            return if (bare.isEmpty()) null else store.resolveGame(bare.uppercase(Locale.ROOT))
        }
        val rawKey = line.substring(0, colon)
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        store.resolveGame(trimmed.uppercase(Locale.ROOT))?.let { return it } // ConfigData.cs:539 + :264
        // 写出侧的标签可能带尾随空格（ConfigData.cs:140），读取侧 Trim 后永远匹配不上；
        // 用未裁剪的键再比一次日/英标签原文（两者都在 AConfigItem.cs:22-23 大写化过）。
        val upperRaw = rawKey.uppercase(Locale.ROOT)
        for (item in store.itemsOf(ConfigGroup.GAME)) {
            val spec = item.spec
            if (upperRaw == spec.upperJpLabel || upperRaw == spec.upperEnLabel) {
                return item
            }
        }
        return null
    }

    /** 一行原文 + 它自己的行尾（LF / CRLF / 空串表示末行没有换行）。 */
    private class RawLine(val text: String, val eol: String)

    /**
     * 按 LF 切行并保留每行的行尾。裸 CR 不当换行（原版 `StreamWriter` 只产出 CRLF 或 LF），
     * 会作为行内容保留。
     */
    private fun splitLines(original: String): List<RawLine> {
        if (original.isEmpty()) {
            return emptyList()
        }
        val lines = ArrayList<RawLine>()
        var start = 0
        var index = 0
        while (index < original.length) {
            if (original[index] == '\n') {
                val crlf = index > start && original[index - 1] == '\r'
                val textEnd = if (crlf) index - 1 else index
                lines += RawLine(original.substring(start, textEnd), if (crlf) "\r\n" else "\n")
                index++
                start = index
            } else {
                index++
            }
        }
        if (start < original.length) {
            lines += RawLine(original.substring(start), "")
        }
        return lines
    }

    /**
     * 追加块用的行尾：原文件里 CRLF / LF 谁多取谁，平票取先出现的，没有行尾时用 LF。
     * 已有行不受影响 —— 每行保留自己的行尾。
     */
    private fun dominantEol(lines: List<RawLine>): String {
        var crlf = 0
        var lf = 0
        var first = ""
        for (line in lines) {
            when (line.eol) {
                "\r\n" -> {
                    crlf++
                    if (first.isEmpty()) {
                        first = line.eol
                    }
                }

                "\n" -> {
                    lf++
                    if (first.isEmpty()) {
                        first = line.eol
                    }
                }
            }
        }
        return when {
            crlf > lf -> "\r\n"
            lf > crlf -> "\n"
            else -> first.ifEmpty { "\n" }
        }
    }
}
