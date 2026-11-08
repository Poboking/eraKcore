package com.eratw.port.core.config

import com.eratw.port.boot.CorpusProfile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本路的 golden 判据：读**当前语料根**（`ERATW_GAMEDIR`）里的 `emuera.config` 与 `CSV/_Replace.csv`
 * （两份都只读，一字节不改），把每一行喂给 [EmueraConfigParser]，然后逐条断言
 * 「解析结果 vs C# 默认值」的差异 —— 判据是磁盘上那份由真实 Emuera 写出的文件，不是自产期望。
 *
 * ## 按根取列（cfgolden 波，2026-11-06）
 *
 * 本机两份语料根的 `emuera.config` 不是同一份文件：4.980 是 97 行、`MAXLOG:900`、尾随空格标签在
 * 第 86 行；4.981 是 103 行、`MAXLOG:1500`，并多出 6 条 configArray 之外的键盘行（`:68-71`、`:77-78`）。
 * 其余行两根逐字相同（只有 `:18` 与 `:28` 两处值不同）—— 这一点由两个文件规范化行尾后的 `diff` 实读确认。
 *
 * 于是本文件里**每一笔随根而变的读数都按根取列**：判根走 [CorpusProfile.detect]（就近读
 * `ERB/DIM.ERH` 的 `#DIM CONST NOW_VERSION`），每列仍是具体定值（出处见 [CorpusProfile] 的 KDoc）；
 * 认不出根 / 版本不在册 / 语料根缺文件 ⇒ 一律判红，**不退成跳过**（旧实现的跳过是假闸）。
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
 *   另有**夹取簇**：两份语料都一条 [ConfigClampNotice] 都不触发（数值全在 `Config.cs:431-455`
 *   的界内），所以本文件只钉「不触发」这一侧；触发侧的 6 条夹取在 `EffectiveConfigTest` 里钉。
 *
 * ## 三重约束
 *   1. **逐行结论**：两份语料各 n 行里 96 行落到条目上、其余匹配不上 —— 1 行（尾随空格标签
 *      `CLIPBOARD- ANYKEY WAIT TRIGGER `，4.980 第 86 行 / 4.981 第 92 行）**永远**匹配不上 ——
 *      依据是 `ConfigData.cs:140` 的英文标签自带尾随空格，而读取时 `array[0].Trim()`
 *      （`ConfigData.cs:539`）把键的那个空格削掉了；4.981 另有 6 行是该版新增、configArray
 *      （1.824）里没有的键 ⇒ 未知键静默忽略（`ConfigData.cs:540-543`），4.980 没有这 6 行；
 *   2. **值差异表**：4.980 有 34 个条目被语料覆盖、4.981 有 35 个，其余 66 / 65 个与 `setDefault()`
 *      的默认值逐字符相同（覆盖数 + 未覆盖数 = 100 = `configArray` 条数，条目数守恒）。
 *      两根的差别只有两格：4.981 把 MAXLOG 900→1500、FPS 5→3（FPS 从「与默认同值」变成覆盖项）；
 *   3. **反向还原**：按原版写出规则（`ConfigData.cs:424-464` 的三条跳过 + `ConfigItem.cs:53-87`
 *      的值格式）把解析结果重新写出行，与源文件**剔除未注册新键行后**逐字节相同 ——
 *      4.980 没有未注册新键行 ⇒ 与原文全等；4.981 有 6 条 ⇒ 与剔除后的 97 行全等。
 *      原版 `SaveConfig` 整份重写本来就会丢掉 configArray 之外的行；未知行的保留语义
 *      是移植侧 mergeInto 的扩展，由 ConfigPreserveTest 单独钉住。
 *
 * 第 3 条是这条判据的关键：比较对象是磁盘上那份由真实 Emuera 写出的文件，而 Emuera 自己的
 * `SaveConfig` 就是那份文件的作者，所以「读进来再写回去应当一字不差」是原版行为的闭合校验 ——
 * 标签文本、默认值、类型分派、跳过规则四件事任何一处抄错都会在这里露出来。
 *
 * `_Replace.csv` 那一测是同族第二份磁盘实据：它专门钉住
 * `ConfigData.cs:617-619` 的取值偏移（语料里的键**后面带一个空格**，
 * 偏移算错就会把分隔符本身读进值里）与 `:622` 的 `?.` 静默跳过（语料第 15 行的
 * 「販売道具数」并不是 `:162` 注册的「販売アイテム数」）。该文件两根**同值**（2026-11-06 实读：
 * 除行尾符 4.980 为 LF、4.981 为 CRLF 外逐行相同），故其读数保留定值。
 *
 * 语料缺失时**判红**（未设 `ERATW_GAMEDIR`、目录不是语料根、缺 `CSV/_Replace.csv`），
 * 与 `core/config/ConfigChainTest.corpusRoot()` 同口径 —— 跳过会让这道金基准闸失效。
 */
class EmueraConfigGoldenTest {

    @Test
    fun `真实 emuera-config 的逐行解析与反向还原`() {
        val (gameDir, profile) = corpusRoot()
        val file = gameDir.resolve(EMUERA_CONFIG_FILE_NAME)
        val rawLines = Files.readAllLines(file, Charsets.UTF_8)
        assertTrue(rawLines.isNotEmpty(), "emuera.config 读出来是空的：$file")
        val bomMarked = rawLines[0].isNotEmpty() && rawLines[0][0].code == 0xFEFF
        val lines = rawLines.mapIndexed { i, l -> if (i == 0) ConfigFile.stripBom(l) else l }
        assertTrue(bomMarked, "两根语料的配置都带 UTF-8 BOM（EncodingHandler.cs:23-25 据此判编码）")
        assertEquals(
            profile.emueraConfigLineCount,
            lines.size,
            "$gameDir/emuera.config 的行数是外部读数（4.980 = 97、4.981 = 103）：认到 $profile ⇒ " +
                "期望 ${profile.emueraConfigLineCount}，回退即说明语料被改过或解析入口错了",
        )

        val store = ConfigStore()
        val result = EmueraConfigParser.loadGameFile(ConfigFile("emuera.config", lines), store)

        // ---- 形状簇：每一行的落点（匹配不上的行数随根取列）----
        assertEquals(profile.emueraConfigLineCount, result.records.size, "每行都该留下一条记录")
        assertEquals(
            mapOf(
                ConfigLineStatus.APPLIED to 95, // 95 行走 ConfigItem.cs:89-213 的 TryParse（两根同值）
                ConfigLineStatus.DIRECT_ASSIGNED to 1, // EditorArgument 直写（ConfigData.cs:564-568；两根同在 :47）
                // 4.980 = 1（只有尾随空格标签那行）、4.981 = 7（同一行 + 6 条未注册新键）
                ConfigLineStatus.UNMATCHED_KEY to profile.emueraConfigUnmatchedKeyCount,
            ),
            result.records.groupingBy { it.status }.eachCount(),
            "行落点分布是外部读数（ConfigData.cs:529-576）：多一类少一类都说明读取链的先后变了；" +
                "认到 $profile ⇒ 未匹配应为 ${profile.emueraConfigUnmatchedKeyCount}",
        )
        assertEquals(
            96,
            result.appliedCount,
            "96 行落到条目上（95 行走 TryParse + 1 行走 EditorArgument 直写；两根同值 —— " +
                "4.981 比 4.980 多出的 6 行全是不落条目的未注册键）",
        )
        assertEquals(0, result.warnings.size, "语料这份配置没有任何行抛 CodeEE")
        assertNull(result.abortedAtLine, "没有抛错行 ⇒ 不该中止")
        assertEquals(0, result.droppedLines)
        val unmatched = result.unmatchedKeys
        assertEquals(
            profile.emueraConfigUnmatchedKeyCount,
            unmatched.size,
            "4.980 只 1 枚（尾随空格那条）、4.981 7 枚（同一枚 + 6 条新键）；认到 $profile ⇒ " +
                "期望 ${profile.emueraConfigUnmatchedKeyCount}，实际匹配不上的行是 ${unmatched.map { it.rawLine }}",
        )
        assertEquals(
            profile.emueraConfigTrailingSpaceLineNo,
            unmatched.single { it.rawLine == "CLIPBOARD- ANYKEY WAIT TRIGGER :NO" }.lineNo,
            "尾随空格那行的行号随根取列：4.980 第 86 行、4.981 第 92 行（4.981 在它前面多插了 6 条新键行）",
        )
        assertEquals(
            profile.emueraConfigUnmatchedKeyCount - 1,
            profile.emueraConfigUnregisteredKeyLines.size,
            "匹配不上的行里除尾随空格那一枚外，全是 configArray 未注册的新键行（4.980 = 0、4.981 = 6）",
        )
        assertEquals(
            setOf("CLIPBOARD- ANYKEY WAIT TRIGGER :NO") + profile.emueraConfigUnregisteredKeyLines,
            unmatched.map { it.rawLine }.toSet(),
            "匹配不上的行**内容**同样是外部读数：$profile 应恰好是尾随空格那一枚 + " +
                "${profile.emueraConfigUnregisteredKeyLines.size} 条未注册新键",
        )
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
            "第 47 行 TEXT EDITOR COMMAND LINE ARGUMENTS: 走 ConfigData.cs:564-568 的直写分支（两根同在 :47）",
        )
        assertEquals("", store.itemOf(ConfigKey.EditorArgument).string(), "直写不 Trim、也不走 TryParse（:566）⇒ 空串就是空串")

        // ---- 值类型簇：96 个落点按 ConfigItem 的泛型实参归簇（两根同值）----
        // 期望值的算法：该类型在 configArray（ConfigData.cs:45-151）里的注册条数
        // 减去被 ConfigData.cs:433 跳过、因而根本没写进这份文件的条数。
        // 手工核对：BOOL 59 条注册，ChangeMasterNameIfDebug（:82）与 CompatiDRAWLINE（:99）不写出
        // ⇒ 57，再减去尾随空格那条不匹配的 CBTriggerAnyKeyWait（:140）⇒ 56；
        // LONG 只有 LastKey（:86）一条，值为 0 ⇒ 不写出 ⇒ 0 行。
        // 两根的差集（4.981 的 6 条新键）全是不落条目的未知键行，所以这份分布不随根变。
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

        // ---- 判据 3：反向还原，逐字节（剔除未注册新键行后）----
        val effective = EffectiveConfig.of(store)
        val written = EmueraConfigWriter.gameLines(store, english = effective.englishConfigOutput)
        val knownLines = lines.filter { it !in profile.emueraConfigUnregisteredKeyLines }
        assertEquals(
            profile.emueraConfigLineCount - profile.emueraConfigUnregisteredKeyLines.size,
            knownLines.size,
            "剔除 $profile 的 ${profile.emueraConfigUnregisteredKeyLines.size} 条未注册键行后应剩 " +
                "${profile.emueraConfigLineCount - profile.emueraConfigUnregisteredKeyLines.size} 行",
        )
        assertEquals(
            knownLines,
            written,
            "读进来再按原版规则写回去：原版 SaveConfig 整份重写会丢掉 configArray 之外的行（ConfigData.cs:424-464），" +
                "4.980 语料没有未注册行 ⇒ 与原文逐字节全等；4.981 有 6 条 ⇒ 与剔除后的行序列逐字节相同" +
                "（未知行的保留语义由 mergeInto / ConfigPreserveTest 钉）",
        )

        // ---- 显式设置簇：每条目解析结果 vs C# 默认值 ----
        val defaults = ConfigStore()
        val gameKeys = store.itemsOf(ConfigGroup.GAME).map { it.spec.key }
        assertEquals(
            100,
            gameKeys.size,
            "configArray 注册 100 条（ConfigData.cs:45-151 共 107 行，ValidExtension 占 8 行；条目表是代码侧，两根同值）",
        )
        val expectedOverridden = expectedOverridden(profile)
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
                assertEquals(expected, actual, "$key 被语料覆盖成的值（根 $profile）")
            }
        }
        assertEquals(expectedOverridden.keys, overridden.toSet(), "被覆盖的条目集合本身也是外部读数，多一个少一个都算红")
        assertEquals(
            expectedOverridden.size,
            overridden.size,
            "覆盖 ${expectedOverridden.size} + 未覆盖 ${gameKeys.size - expectedOverridden.size} = ${gameKeys.size} 条" +
                "（条目数守恒；4.980 = 34/66 —— FPS 写 5 与注册默认同值、不进覆盖表；" +
                "4.981 = 35/65 —— MAXLOG 1500、FPS 3 都是覆盖项）",
        )

        // ---- 夹取簇：两份真实配置都一条都不触发 ----
        assertEquals(emptyList<ConfigClampNotice>(), effective.notices, "语料的 fontsize/lineheight/savedatanos/maxlog 都在界内")
        assertEquals(
            MaxLogSetting(profile.emueraConfigMaxLog, profile.emueraConfigMaxLog, raisedToFloor = false),
            effective.maxLog,
            "MAXLOG 随根取列（4.980 = 900、4.981 = 1500），都高于 500 下限（Config.cs:451-455 不触发）",
        )
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
        println(
            "EmueraConfigGolden：$profile / ${lines.size} 行 / 覆盖 ${overridden.size} 项 / " +
                "剔除 ${profile.emueraConfigUnregisteredKeyLines.size} 条未注册新键后还原逐字节相同 / " +
                "夹取 0 次 / MAXLOG ${profile.emueraConfigMaxLog} —— $file",
        )
    }

    /**
     * 同族第二份磁盘实据：`CSV/_Replace.csv`（`Program.cs:207-209` 的 CsvDir，
     * 调用点 `Process.cs:246` → `ConfigData.cs:594-639`）。
     *
     * 17 行的形状分布：9 注释 + 3 空行 + 4 落到条目 + 1 键不匹配。
     * 这份文件**两根同值**（2026-11-06 实读：除行尾符 LF / CRLF 外逐行相同），所以读数全是定值。
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
        val (gameDir, _) = corpusRoot()
        val file = EmueraConfigParser.resolveCsvDir(gameDir).resolve("_Replace.csv")
        val rawLines = Files.readAllLines(file, Charsets.UTF_8)
        val bomMarked = rawLines[0].isNotEmpty() && rawLines[0][0].code == 0xFEFF
        val lines = rawLines.mapIndexed { i, l -> if (i == 0) ConfigFile.stripBom(l) else l }
        assertTrue(bomMarked, "语料 _Replace.csv 同样带 UTF-8 BOM（EncodingHandler.cs:23-25）")
        assertEquals(17, lines.size, "语料 _Replace.csv 的行数是外部读数（两根同值，实读）")

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
     * 「被语料覆盖成什么」的清单，按根取列（值一律写成**配置文件的字面形状**）：
     * 4.980 = 34 项、4.981 = 35 项。两根的差别只有两处，且都在 `when` 分支里逐条写死：
     *   * `MAXLOG` 的值取自同一个 [CorpusProfile] 列（4.980 = 900、4.981 = 1500；两根同在 `:18`），
     *     两份都覆盖了注册默认 5000（`ConfigData.cs:62`）⇒ 两根都进覆盖表；
     *   * `FPS` 只有 4.981 是覆盖项（写 3、默认 5，`:72`）；4.980 写的就是默认 5 ⇒ 不在表里，
     *     由「未覆盖」那一支断言它仍等于 `setDefault()` 的 5。
     *
     * 每一项都能在语料行里逐字找到；没列出来的条目与 `setDefault()` 默认值相同。
     * 尾注释格式：语料行号（两根编号不同处写两遍 —— 4.980 在前，因为 4.981 多插了 6 条未注册键行）
     * + C# 默认值所在注册行（`ConfigData.cs`）。
     */
    private fun expectedOverridden(profile: CorpusProfile): Map<ConfigKey, String> {
        val common = mapOf(
            ConfigKey.UseRenameFile to "YES", // 第 2 行，默认 NO（ConfigData.cs:46）
            ConfigKey.UseDebugCommand to "YES", // 第 6 行，默认 NO（:50）
            ConfigKey.AutoSave to "NO", // 第 8 行，默认 YES（:52）
            ConfigKey.WindowX to "1800", // 第 12 行，默认 760（:56）
            ConfigKey.WindowY to "973", // 第 13 行，默认 480（:57）
            ConfigKey.WindowPosX to "44", // 第 14 行，默认 0（:58）
            ConfigKey.MaxLog to profile.emueraConfigMaxLog.toString(), // 第 18 行（两根同行），默认 5000（:62）
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
            ConfigKey.CheckDuplicateIdentifier to "YES", // 第 71 行（4.981 第 75 行），默认 NO（:118）
            ConfigKey.ValidExtension to "xml,txt,json", // 第 73 行（4.981 第 79 行），默认 txt（:120-127）
            ConfigKey.ZipSaveData to "YES", // 第 74 行（4.981 第 80 行），默认 NO（:128）
            ConfigKey.EnglishConfigOutput to "YES", // 第 75 行（4.981 第 81 行），默认 NO（:129）—— 正因它才是 YES，这份文件才是英文标签的
            ConfigKey.EmueraLang to "简体中文", // 第 76 行（4.981 第 82 行），默认空串（:130）
            ConfigKey.EmueraIcon to "icon.ico", // 第 77 行（4.981 第 83 行），默认空串（:131）
        )
        // 穷尽枚举：将来给 CorpusProfile 加一根 ⇒ 这里编译不过，逼着重落读数而不是静默漏闸。
        return when (profile) {
            // FPS:5 恰是注册默认（ConfigData.cs:72）⇒ 不进覆盖表；「未覆盖」那一支会断言它仍是 5。
            CorpusProfile.V4_980 -> common
            CorpusProfile.V4_981 -> common + (ConfigKey.FPS to "3") // 第 28 行，默认 5（:72）
        }
    }

    /**
     * 语料游戏根（= `emuera.config` 所在目录）连同**认出来的根版本档**。
     *
     * 口径与 `core/config/ConfigChainTest.corpusRoot()` 同源（corpusroot 波立的尺，cfgolden 波复核）：
     *   * 只认 `ERATW_GAMEDIR`，**不猜路径** —— 旧实现按硬编码目录名 `GAME_DIR` 回退，在 §5 标准闸
     *     （`ERATW_GAMEDIR` 指 4.980）下会认到另一根、读出一组必然失败的 4.981 读数（2026-11-02 T7 查出）；
     *   * 判根走 [CorpusProfile.detect]：就近读 `ERB/DIM.ERH` 的 `#DIM CONST NOW_VERSION`；
     *   * 未设环境变量 / 目录不是语料根 / 缺 `CSV/_Replace.csv` / 版本不在册 ⇒ **AssertionError 判红**，
     *     并打印已经认到的证据。旧实现是三处「找不到就 println 跳过并 return」，那是假闸（2026-11-06 拆掉）。
     */
    private fun corpusRoot(): Pair<Path, CorpusProfile> {
        val env = System.getenv("ERATW_GAMEDIR")
            ?: run {
                // 公开仓无外部语料/样本：按公开适配口径跳过（上游为硬判红）。
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "公开仓无 ERATW_GAMEDIR：跳过配置金基准")
                throw AssertionError("unreachable")
            }
        val dir = Paths.get(env)
        assertTrue(
            Files.isRegularFile(dir.resolve(EMUERA_CONFIG_FILE_NAME)),
            "ERATW_GAMEDIR=$env 下没有 $EMUERA_CONFIG_FILE_NAME：它不是语料游戏根",
        )
        // csv 目录走装载链同一个定位器（语料实测拼写是 `CSV`，原版字面量是 `csv`）
        val csv = EmueraConfigParser.resolveCsvDir(dir)
        assertTrue(
            Files.isRegularFile(csv.resolve("_Replace.csv")),
            "语料缺 $csv/_Replace.csv：Replace 判据无根可对（ERATW_GAMEDIR=$env）",
        )
        // 判不出 / 不在册 ⇒ CorpusProfile.detect 自己抛 AssertionError（未登记版本必须撞红，不许降级）
        return dir to CorpusProfile.detect(dir)
    }
}
