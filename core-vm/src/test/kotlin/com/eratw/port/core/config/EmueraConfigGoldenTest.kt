package com.eratw.port.core.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本路的 golden 判据：读**真实**语料配置
 * `eratw-画蛇添足版eratw4.980/emuera.config` 与 `eratw-画蛇添足版eratw4.980/CSV/_Replace.csv`
 * （两份都只读，一字节不改），把每一行喂给 [EmueraConfigParser]，然后逐条断言
 * 「解析结果 vs C# 默认值」的差异 —— 判据是磁盘上那份由真实 Emuera 写出的文件，不是自产期望。
 *
 * ## 簇的划分依据（三条都是外部读数，不是实现回读）
 *   * **形状簇**：每一行落到哪个 [ConfigLineStatus]。依据是 `ConfigData.cs:529-576` 那串
 *     判定的先后（空行 → 注释 → 无冒号 → 键查不到 → 三个特殊条目 → `TryParse`），
 *     以及 `ConfigData.cs:433` 的三条「不写出」规则；
 *   * **值类型簇**：命中的每一条按 [ConfigSpec.valueType] 归簇。依据是 `ConfigData.cs:45-151`
 *     每行 `new ConfigItem<T>` 的泛型实参 —— 每个簇的行数必须等于「该类型注册在 `configArray`
 *     里的条数」减去「该类型里被 :433 跳过、没写进文件的条数」，两处任一抄错都会露出来；
 *   * **显式设置簇**：条目解析结果与 `setDefault()` 缺省值是否相同（[expectedOverridden]）。
 *     相同的那些由判据 3 的**逐字节反向还原**兜底（写出的是磁盘上那份文件的原文）。
 *   另有**夹取簇**：这份配置一条 [ConfigClampNotice] 都不触发（数值全在 `Config.cs:431-455`
 *   的界内），所以本文件只钉「不触发」这一侧；触发侧的 6 条夹取在 `EffectiveConfigTest` 里钉。
 *
 * ## 三重约束
 *   1. **逐行结论**：97 行里 96 行落到条目上、1 行（第 86 行 `CLIPBOARD- ANYKEY WAIT TRIGGER `）
 *      永远匹配不上 —— 依据是 `ConfigData.cs:140` 的英文标签自带尾随空格，而读取时
 *      `array[0].Trim()`（`ConfigData.cs:539`）把键的那个空格削掉了；
 *   2. **值差异表**：34 个条目被语料覆盖、其余 66 个与 `setDefault()` 的默认值逐字符相同
 *      （34 + 66 = 100 = `configArray` 条数，条目数守恒）；
 *   3. **反向还原**：按原版写出规则（`ConfigData.cs:424-464` 的三条跳过 + `ConfigItem.cs:53-87`
 *      的值格式）把解析结果重新写成 97 行，与源文件**逐字节相同**。
 *
 * 第 3 条是这条判据的关键：比较对象是磁盘上那份由真实 Emuera 写出的文件，而 Emuera 自己的
 * `SaveConfig` 就是那份文件的作者，所以「读进来再写回去应当一字不差」是原版行为的闭合校验 ——
 * 标签文本、默认值、类型分派、跳过规则四件事任何一处抄错都会在这里露出来。
 *
 * `_Replace.csv` 那一测是同族第二份磁盘实据：它专门钉住
 * `ConfigData.cs:617-619` 的取值偏移（语料里的键**后面带一个空格**，
 * 偏移算错就会把分隔符本身读进值里）与 `:622` 的 `?.` 静默跳过（语料第 15 行的
 * 「販売道具数」并不是 `:162` 注册的「販売アイテム数」）。
 *
 * 语料缺失时（未设 `ERATW_CORPUS` 且找不到游戏目录）打印跳过并返回，与
 * `core/stmt/CorpusStmtTest`、`boot/RealCorpus` 同口径。
 */
class EmueraConfigGoldenTest {

    @Test
    fun `真实 emuera-config 的逐行解析与反向还原`() {
        val gameDir = locateGameDir()
        if (gameDir == null) {
            println("跳过 EmueraConfigGoldenTest：未找到 emuera.config（可设 ERATW_CORPUS=<…>/ERB）")
            return
        }
        val file = gameDir.resolve("emuera.config")
        val rawLines = Files.readAllLines(file, Charsets.UTF_8)
        assertTrue(rawLines.isNotEmpty(), "emuera.config 读出来是空的：$file")
        val bomMarked = rawLines[0].isNotEmpty() && rawLines[0][0].code == 0xFEFF
        val lines = rawLines.mapIndexed { i, l -> if (i == 0) ConfigFile.stripBom(l) else l }
        assertTrue(bomMarked, "期望语料配置带 UTF-8 BOM（EncodingHandler.cs:23-25 据此判编码）")
        assertEquals(97, lines.size, "语料配置的行数（不含尾部换行）是外部读数，回退即说明语料被改过或解析入口错了")

        val store = ConfigStore()
        val result = EmueraConfigParser.loadGameFile(ConfigFile("emuera.config", lines), store)

        // ---- 形状簇：97 行的落点 ----
        assertEquals(97, result.records.size, "每行都该留下一条记录")
        assertEquals(
            mapOf(
                ConfigLineStatus.APPLIED to 95, // 95 行走 ConfigItem.cs:89-213 的 TryParse
                ConfigLineStatus.DIRECT_ASSIGNED to 1, // 第 47 行 EditorArgument 直写（ConfigData.cs:564-568）
                ConfigLineStatus.UNMATCHED_KEY to 1, // 第 86 行尾随空格标签（ConfigData.cs:140 + :539）
            ),
            result.records.groupingBy { it.status }.eachCount(),
            "行落点分布是外部读数：多一类少一类都说明读取链的先后变了（ConfigData.cs:529-576）",
        )
        assertEquals(96, result.appliedCount, "96 行落到条目上（95 行走 TryParse + 1 行走 EditorArgument 直写）")
        assertEquals(0, result.warnings.size, "语料这份配置没有任何行抛 CodeEE")
        assertNull(result.abortedAtLine, "没有抛错行 ⇒ 不该中止")
        assertEquals(0, result.droppedLines)
        val unmatched = result.unmatchedKeys
        assertEquals(1, unmatched.size, "只有尾随空格那一条键匹配不上")
        assertEquals(86, unmatched.single().lineNo, "语料第 86 行 `CLIPBOARD- ANYKEY WAIT TRIGGER :NO`")
        assertEquals(
            "CLIPBOARD- ANYKEY WAIT TRIGGER ",
            ConfigSpecTable.specOf(ConfigKey.CBTriggerAnyKeyWait)!!.upperEnLabel,
            "该条目的英文标签必须以空格结尾（ConfigData.cs:140），否则本路就把官方 bug 顺手修了",
        )
        assertEquals(
            ConfigValue.Bool(false),
            store.itemOf(ConfigKey.CBTriggerAnyKeyWait).value,
            "匹配不上 ⇒ 值回落到注册默认 false（语料写的也是 NO，效果相同，但这条不是「解析成功」）",
        )
        assertEquals(
            ConfigLineStatus.DIRECT_ASSIGNED,
            result.records.first { it.lineNo == 47 }.status,
            "第 47 行 TEXT EDITOR COMMAND LINE ARGUMENTS: 走 ConfigData.cs:564-568 的直写分支",
        )
        assertEquals("", store.itemOf(ConfigKey.EditorArgument).string(), "直写不 Trim、也不走 TryParse（:566）⇒ 空串就是空串")

        // ---- 值类型簇：96 个落点按 ConfigItem 的泛型实参归簇 ----
        // 期望值的算法：该类型在 configArray（ConfigData.cs:45-151）里的注册条数
        // 减去被 ConfigData.cs:433 跳过、因而根本没写进这份文件的条数。
        // 手工核对：BOOL 59 条注册，ChangeMasterNameIfDebug（:82）与 CompatiDRAWLINE（:99）不写出
        // ⇒ 57，再减去尾随空格那条不匹配的 CBTriggerAnyKeyWait（:140）⇒ 56；
        // LONG 只有 LastKey（:86）一条，值为 0 ⇒ 不写出 ⇒ 0 行。
        assertEquals(
            mapOf(
                ConfigValueType.BOOL to 56,
                ConfigValueType.INT to 19,
                ConfigValueType.STRING to 8, // 含第 47 行的直写分支
                ConfigValueType.COLOR to 6,
                ConfigValueType.TEXT_DRAWING_MODE_ENUM to 1, // :55
                ConfigValueType.REDUCE_ARGUMENT_ENUM to 1, // :78
                ConfigValueType.DISPLAY_WARNING_ENUM to 2, // :80、:81
                ConfigValueType.USE_LANGUAGE_ENUM to 1, // :103
                ConfigValueType.TEXT_EDITOR_TYPE_ENUM to 1, // :92
                ConfigValueType.STRING_LIST to 1, // :120-127
            ),
            result.records.filter { it.changedValue }.groupingBy { specType(it) }.eachCount(),
            "五种枚举各 1-2 条、COLOR 6 条、STRING_LIST 1 条、LONG/LONG_LIST/CHAR 0 条 —— 条目表把类型抄错就在这里露",
        )

        // ---- 判据 3：反向还原，逐字节 ----
        val effective = EffectiveConfig.of(store)
        val written = EmueraConfigWriter.gameLines(store, english = effective.englishConfigOutput)
        assertEquals(lines, written, "读进来再按原版规则写回去，必须与磁盘上的真实文件一字不差")

        // ---- 显式设置簇：每条目解析结果 vs C# 默认值 ----
        val defaults = ConfigStore()
        val gameKeys = store.itemsOf(ConfigGroup.GAME).map { it.spec.key }
        assertEquals(100, gameKeys.size, "configArray 注册 100 条（ConfigData.cs:45-151 共 107 行，ValidExtension 占 8 行）")
        val overridden = ArrayList<ConfigKey>()
        for (key in gameKeys) {
            val actual = EmueraConfigWriter.valueToString(store.itemOf(key))
            val expected = expectedOverridden[key]
            if (expected == null) {
                assertEquals(
                    EmueraConfigWriter.valueToString(defaults.itemOf(key)),
                    actual,
                    "$key 未被语料覆盖，必须仍是 setDefault() 的默认值",
                )
            } else {
                overridden += key
                assertEquals(expected, actual, "$key 被语料覆盖成的值")
            }
        }
        assertEquals(expectedOverridden.keys, overridden.toSet(), "被覆盖的条目集合本身也是外部读数，多一个少一个都算红")
        assertEquals(34, overridden.size, "覆盖 34 + 未覆盖 66 = 100 条（条目数守恒）")
        assertEquals(66, gameKeys.size - overridden.size)

        // ---- 夹取簇：这份真实配置一条都不触发 ----
        assertEquals(emptyList<ConfigClampNotice>(), effective.notices, "语料的 fontsize/lineheight/savedatanos/maxlog 都在界内")
        assertEquals(MaxLogSetting(900, 900, raisedToFloor = false), effective.maxLog, "MAXLOG:900 高于 500 下限（Config.cs:451-455 不触发）")
        assertEquals(18, effective.fontSize)
        assertEquals(18, effective.lineHeight, "LINE HEIGHT:18 与夹过的 FontSize 相等 ⇒ 不抬（Config.cs:436-440 判的是小于）")
        assertEquals(20, effective.saveDataNos)
        assertEquals("TEXTRENDERER", effective.textDrawingMode)
        assertEquals(3, effective.shapePositionShift, "max(2, 18/6) = 3（Config.cs:461-465，整除）")
        assertEquals(1797, effective.drawableWidth, "WindowX 1800 - 3（Config.cs:466）")
        assertEquals(LanguageSetting(2052, 936), effective.language, "DEFAULT ANSI ENCODING:CHINESE_HANS（Config.cs:422-425）")
        assertTrue(effective.warnFunctionOverloading, "ALLOW OVERRIDING SYSTEM FUNCTIONS:YES ⇒ 取条目值 YES（Config.cs:351）")
        assertEquals(true, effective.needReduceArgumentOnLoad, "REDUCE ARGUMENT ON LOAD:YES ⇒ true（Config.cs:596-599）")
        assertTrue(effective.ignoreCase)
        assertEquals("黑体", effective.fontName, "配置原值如实报出，字体回退属渲染层（Config.cs:501-523）")
        assertTrue(effective.englishConfigOutput, "OUTPUT ENGLISH ITEMS IN THE CONFIG FILE:YES（Config.cs:389）⇒ 这份文件才是英文标签的")
        println("EmueraConfigGolden：97 行 / 覆盖 34 项 / 还原逐字节相同 / 夹取 0 次 —— $file")
    }

    /**
     * 同族第二份磁盘实据：`CSV/_Replace.csv`（`Program.cs:207-209` 的 CsvDir，
     * 调用点 `Process.cs:246` → `ConfigData.cs:594-639`）。
     *
     * 17 行的形状分布：9 注释 + 3 空行 + 4 落到条目 + 1 键不匹配。
     * 这一测专门钉两处只有读真实文件才会撞上的形状：
     *   1. **取值偏移**（spec: `ConfigData.cs:617-619` `num = array[0].Length + 1`）——
     *      语料的键后面带一个空格（「お金の単位 , ￥」），偏移必须按**未裁剪**的首段长度算，
     *      否则值里会多出一个逗号；
     *   2. **键不匹配时静默跳过**（spec: `ConfigData.cs:622` 的 `GetReplaceItem(key)?.TryParse`）——
     *      语料第 15 行写的是「販売道具数」，而条目表注册的是「販売アイテム数」（`:162`），
     *      于是那一行读不到任何东西、也不记告警。
     */
    @Test
    fun `真实 replace-csv 的逐行解析与默认值差异`() {
        val gameDir = locateGameDir() ?: return println("跳过 _Replace.csv 判据：未找到游戏目录")
        val file = listOf("CSV", "csv").map { gameDir.resolve(it).resolve("_Replace.csv") }
            .firstOrNull { Files.isRegularFile(it) }
            ?: return println("跳过 _Replace.csv 判据：$gameDir 下没有 CSV/_Replace.csv")
        val rawLines = Files.readAllLines(file, Charsets.UTF_8)
        val bomMarked = rawLines[0].isNotEmpty() && rawLines[0][0].code == 0xFEFF
        val lines = rawLines.mapIndexed { i, l -> if (i == 0) ConfigFile.stripBom(l) else l }
        assertTrue(bomMarked, "语料 _Replace.csv 同样带 UTF-8 BOM（EncodingHandler.cs:23-25）")
        assertEquals(17, lines.size, "语料 _Replace.csv 的行数是外部读数")

        val store = ConfigStore()
        val result = EmueraConfigParser.loadReplaceFile(ConfigFile("_Replace.csv", lines), store)

        assertEquals(17, result.records.size)
        assertEquals(
            mapOf(
                ConfigLineStatus.COMMENT to 9, // 整行先 Trim（ConfigData.cs:607-611）⇒ 行首空格的 ; 也算注释
                ConfigLineStatus.BLANK to 3, // 第 2、5、16 行
                ConfigLineStatus.APPLIED to 4, // 第 7、9、14、17 行
                ConfigLineStatus.UNMATCHED_KEY to 1, // 第 15 行「販売道具数」（:622 的 `?.` 静默跳过）
            ),
            result.records.groupingBy { it.status }.eachCount(),
        )
        assertEquals(0, result.warnings.size, "没有抛错行 ⇒ 不该有告警，也不该中止")
        assertNull(result.abortedAtLine)
        assertEquals(0, result.droppedLines)

        // ---- 解析结果 vs C# 默认值 ----
        val defaults = ConfigStore()
        val replaceKeys = store.itemsOf(ConfigGroup.REPLACE).map { it.spec.key }
        assertEquals(16, replaceKeys.size, "replaceArray 注册 16 条（ConfigData.cs:159-174）")
        assertEquals("￥", store.itemOf(ConfigKey.MoneyLabel).string(), "第 7 行覆盖默认「${'$'}」（:159）；值里的前导空格由 TryParse 的 Trim 收掉")
        assertEquals(" ￥", result.records[6].rawValue, "记录留的是裁剪前原文：偏移按 array[0].Length+1 算，正好落在逗号后一位")
        assertTrue(store.itemOf(ConfigKey.MoneyFirst).bool(), "第 9 行「前」⇒ tryStringToBool 的 Ordinal 分支（ConfigItem.cs:244）")
        assertEquals(
            "如果出现了错误，请根据目录下的报错指导文件进行报错",
            store.itemOf(ConfigKey.LoadLabel).string(),
            "第 14 行覆盖默认「Now Loading...」（:161）；值里只有一个全角逗号，Split(',', ':') 不吃（:613）",
        )
        assertEquals(
            listOf(0L, 0L, 2L, 1L, 8L, 0L, 1L, 8L),
            store.itemOf(ConfigKey.StainDefault).longList(),
            "第 17 行覆盖默认 5 项（:169）⇒ 8 项。List 是先 Clear 再逐项 Add（ConfigItem.cs:147-158），长度不做校验",
        )
        val differing = ArrayList<ConfigKey>()
        val appliedKeys = setOf(ConfigKey.MoneyLabel, ConfigKey.MoneyFirst, ConfigKey.LoadLabel, ConfigKey.StainDefault)
        for (key in replaceKeys) {
            val same = defaults.itemOf(key).value == store.itemOf(key).value
            // 值对象都是 data class ⇒ 结构相等就够了；这里不能用 valueToString ——
            // List 型条目（ExpLvDef / PalamLvDef）在原版落进 `val.ToString()` 的 .NET 类型名串，
            // 本层故意不产出那种垃圾串（见 ConfigValue.LongList 的注释）。
            if (!same) differing += key
            if (key !in appliedKeys) assertTrue(same, "$key 没有被这份语料的任何一行命中，必须仍是默认值")
        }
        assertEquals(4, appliedKeys.size, "17 行里只有 4 行落到条目上")
        assertEquals(
            setOf(ConfigKey.MoneyLabel, ConfigKey.LoadLabel, ConfigKey.StainDefault),
            differing.toSet(),
            "「解析结果 vs C# 默认值」的差异集合：第 9 行「単位の位置 , 前」写的是「前」，与默认 YES 同值 ⇒ 落条目但不进差异表",
        )
        println("EmueraConfigGolden：_Replace.csv 17 行 / 落条目 4 行 / 与默认不同 3 项 / 键不匹配 1 行 —— $file")
    }

    /** 命中条目的 [ConfigValueType]；未命中键的行没有类型可言，用哨兵值归到 UNMATCHED_KEY 之外（本测已先过滤）。 */
    private fun specType(record: ConfigLineRecord): ConfigValueType {
        val key = requireNotNull(record.matchedKey) { "只有落到条目上的行才按类型归簇：${record.rawLine}" }
        return requireNotNull(ConfigSpecTable.specOf(key)) { "$key 没有条目规格" }.valueType
    }

    /**
     * 「被语料覆盖成什么」的清单（34 项，值一律写成**配置文件的字面形状**）。
     * 每一项都能在语料行里逐字找到；没列出来的 66 项与 `setDefault()` 默认值相同。
     * 尾注释格式：语料行号 + C# 默认值所在注册行（`ConfigData.cs`）。
     */
    private val expectedOverridden: Map<ConfigKey, String> = mapOf(
        ConfigKey.UseRenameFile to "YES", // 第 2 行，默认 NO（ConfigData.cs:46）
        ConfigKey.UseDebugCommand to "YES", // 第 6 行，默认 NO（:50）
        ConfigKey.AutoSave to "NO", // 第 8 行，默认 YES（:52）
        ConfigKey.WindowX to "1800", // 第 12 行，默认 760（:56）
        ConfigKey.WindowY to "973", // 第 13 行，默认 480（:57）
        ConfigKey.WindowPosX to "44", // 第 14 行，默认 0（:58）
        ConfigKey.MaxLog to "900", // 第 18 行，默认 5000（:62）
        ConfigKey.PrintCPerLine to "5", // 第 19 行，默认 3（:63）
        ConfigKey.FontName to "黑体", // 第 21 行，默认 ＭＳ ゴシック（:65）
        ConfigKey.LineHeight to "18", // 第 23 行，默认 19（:67）
        ConfigKey.LogColor to "128,128,128", // 第 27 行，默认 192,192,192（:71）
        ConfigKey.ScrollHeight to "6", // 第 30 行，默认 1（:74）
        ConfigKey.InfiniteLoopAlertTime to "15000", // 第 31 行，默认 5000（:75）
        ConfigKey.DisplayWarningLevel to "0", // 第 32 行，默认 1（:76）
        ConfigKey.ReduceArgumentOnLoad to "YES", // 第 34 行，默认 NO（:78）
        ConfigKey.FunctionNotFoundWarning to "DISPLAY", // 第 36 行，默认 IGNORE（:80）
        ConfigKey.FunctionNotCalledWarning to "ONCE", // 第 37 行，默认 IGNORE（:81）
        ConfigKey.ButtonWrap to "YES", // 第 38 行，默认 NO（:83）
        ConfigKey.SearchSubdirectory to "YES", // 第 39 行，默认 NO（:84）
        ConfigKey.SortWithFilename to "YES", // 第 40 行，默认 NO（:85）
        ConfigKey.WarnBackCompatibility to "NO", // 第 42 行，默认 YES（:88）
        ConfigKey.WarnNormalFunctionOverloading to "YES", // 第 48 行，默认 NO（:94）
        ConfigKey.CompatiCALLNAME to "YES", // 第 50 行，默认 NO（:96）
        ConfigKey.UseSaveFolder to "YES", // 第 51 行，默认 NO（:97）
        ConfigKey.SystemAllowFullSpace to "NO", // 第 54 行，默认 YES（:101）
        ConfigKey.useLanguage to "CHINESE_HANS", // 第 56 行，默认 JAPANESE（:103）
        ConfigKey.SystemSaveInBinary to "YES", // 第 60 行，默认 NO（:107）
        ConfigKey.CompatiFuncArgAutoConvert to "YES", // 第 62 行，默认 NO（:109）
        ConfigKey.CheckDuplicateIdentifier to "YES", // 第 71 行，默认 NO（:118）
        ConfigKey.ValidExtension to "xml,txt,json", // 第 73 行，默认 txt（:120-127）
        ConfigKey.ZipSaveData to "YES", // 第 74 行，默认 NO（:128）
        ConfigKey.EnglishConfigOutput to "YES", // 第 75 行，默认 NO（:129）—— 正因它才是 YES，这份文件才是英文标签的
        ConfigKey.EmueraLang to "简体中文", // 第 76 行，默认空串（:130）
        ConfigKey.EmueraIcon to "icon.ico", // 第 77 行，默认空串（:131）
    )

    /**
     * 游戏目录（= `emuera.config` 所在目录）的四路探测。
     *
     * `ERATW_CORPUS` 指向 `<游戏目录>/ERB`（`Program.cs:210-212` 的 ErbDir），
     * 而 `emuera.config` 在 exe 目录根（`ConfigData.cs:33`：`Program.ExeDir + "emuera.config"`）、
     * `_Replace.csv` 在 `<游戏目录>/CSV`（`Program.cs:207-209`）⇒ 一切都从**父目录**推。
     */
    private fun locateGameDir(): Path? {
        val candidates = LinkedHashSet<Path>()
        System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() }?.let { erb ->
            val p = Paths.get(erb)
            p.parent?.let { candidates.add(it) }
        }
        val cwd = Paths.get("").toAbsolutePath()
        val repoRoot = cwd.parent?.parent ?: cwd
        repoRoot.parent?.let { up ->
            candidates.add(up.resolve(GAME_DIR))
            candidates.add(up.resolve("eratw-安卓移植计划").resolve(GAME_DIR))
        }
        candidates.add(repoRoot.resolve(GAME_DIR))
        candidates.add(Paths.get("D:/DSH/eratw-安卓移植计划", GAME_DIR))
        candidates.add(Paths.get("C:/DSH/eratw-安卓移植计划", GAME_DIR))
        return candidates.firstOrNull { Files.isRegularFile(it.resolve("emuera.config")) }
    }

    private companion object {
        private const val GAME_DIR = "eratw-画蛇添足版eratw4.980"
    }
}
