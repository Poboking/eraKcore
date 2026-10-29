package com.eratw.port.core.config

import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.ConfigMethods
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.loadConfigOverrides
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `_Replace.csv` **接进生产消费面**的回归 —— 缺陷点是 P1：解析器 `loadReplaceFile` 一直只有
 * 测试调用，产线从不装载这一层，于是 `REPLACE` 组 16 条（`ConfigData.cs:159-174`）永远是
 * 注册默认（语料上 `お金の単位` = `$`、`汚れの初期値` = 5 项）。
 *
 * 本文件钉四件事：
 * 1. [RootedBuiltinHost] 开局按 `Config.UseReplaceFile && !AnalysisMode && File.Exists`
 *    （spec: `Process.cs:240`）装载 `CsvDir/_Replace.csv`，并把结果**就地写进**
 *    `configChain.store`（原版只有一张进程级条目表，spec: `ConfigData.cs:23-27`）；
 * 2. `configOverrides`（`barChars` / 码页 / LOADTEXT 白名单等消费面）、`BuiltinHost.moneyLabel`
 *    （`MONEYSTR` / `MONEYLABEL`，spec: `Config.cs:199`、`FunctionMethodCreator.cs:3873-3921`）
 *    与函数层 `ConfigMethods.storeFromHost` 的 `GETCONFIG` 条目表看到同一份 Replace 后的值 ——
 *    三条通道都走链 + Replace 的重放（spec: `ConfigData.cs:494` → `Config.cs:719-741`
 *    的 `SetReplace`）；
 * 3. `UseReplaceFile=false` 与分析模式 `-a` 都不读（`Process.cs:240` 的另两条守卫）；
 * 4. 重放幂等：同一批行叠两次，`汚れの初期値` 仍是 8 项而不是 16 项
 *    （`ConfigItem.cs:147-158` 的 `Value.Clear()`）。
 *
 * 语料用例吃 `ERATW_GAMEDIR`，未设置或缺文件时按 skipped 计（与 `ConfigChainWireTest` 同风格）。
 */
class ReplaceWireTest {

    private val eightStains = listOf(0L, 0L, 2L, 1L, 8L, 0L, 1L, 8L)

    @Test
    fun `宿主覆盖表与函数层条目表都吃到 Replace 层`() {
        val root = newGameDir(
            replace = listOf(
                "お金の単位 , ￥",
                "単位の位置 , 前",
                "BAR文字1 , =",
                "起動時簡略表示 , LOADING",
                "販売アイテム数,0",
                "汚れの初期値, 0/0/2/1/8/0/1/8",
            ),
        )
        val host = RootedBuiltinHost(root)
        val replace = assertNotNull(host.configReplace, "UseReplaceFile 默认 true（ConfigData.cs:47）且文件在位 ⇒ 必读这一层")
        assertEquals(ConfigLayerPresence.PRESENT, replace.presence)
        assertEquals(6, replace.appliedKeys.size, "6 条有效行都落到 REPLACE 组条目上")

        // 消费面（barChars / 码页 / 白名单读的那张表）：键 = 大写英文标签（AConfigItem.cs:22-23）
        assertEquals("￥", host.configOverrides["CURRENCY SYMBOL"], "お金の単位 ⇒ MoneyLabel（Config.cs:720）")
        assertEquals("前", host.configOverrides["CURRENCY SYMBOL POSITION"], "単位の位置 ⇒ MoneyFirst")
        assertEquals("=", host.configOverrides["BAR CHARACTER 1"], "BAR文字1 ⇒ BarChar1（Config.cs:730）")
        assertEquals("LOADING", host.configOverrides["LOADING MESSAGE"], "起動時簡略表示 ⇒ LoadLabel（Config.cs:722）")
        assertEquals("0", host.configOverrides["MAX SHOP ITEM STORAGE"], "販売アイテム数 ⇒ MaxShopItem（ConfigData.cs:162）")
        // MONEYSTR / MONEYLABEL 走 BuiltinHost.moneyLabel，而不是 configOverrides；
        // 原版同一个来源是 Config.MoneyLabel / MoneyFirst（Config.cs:199、:721）；
        assertEquals("￥", host.moneyLabel, "MONEYSTR 的标签来自同一条 replace 值（FunctionMethodCreator.cs:3873-3921）")
        assertTrue(host.moneyLabelFirst, "単位の位置=前 ⇒ MoneyFirst = true（ConfigItem.cs:244）")

        // 函数层（GETCONFIG / 函数实现读的那张表）：走 ConfigChainSource 的优先通道
        val store = ConfigMethods.storeFromHost(host)
        assertEquals("￥", store.itemOf(ConfigKey.MoneyLabel).string())
        assertTrue(store.itemOf(ConfigKey.MoneyFirst).bool(), "「前」经 tryStringToBool 认下（ConfigItem.cs:244）")
        assertEquals('=', store.itemOf(ConfigKey.BarChar1).char())
        assertEquals("LOADING", store.itemOf(ConfigKey.LoadLabel).string())
        assertEquals(0, store.itemOf(ConfigKey.MaxShopItem).int())
        assertEquals(eightStains, store.itemOf(ConfigKey.StainDefault).longList())
        assertEquals("￥", (ConfigMethods.valueInErb(store, "お金の単位") as Value.StrV).v, "GETCONFIG 的读数与条目表同源")

        // 降级通道（只转发 configOverrides 的包装宿主）也应拿到同一份 Replace：
        // 覆盖表的 REPLACE 键取英文标签，loadOverrideMap 在 REPLACE 组补一次英文匹配。
        val wrapper = object : BuiltinHost {
            override val configOverrides: Map<String, String> = host.configOverrides
        }
        val degraded = ConfigMethods.storeFromHost(wrapper)
        assertEquals(store.itemOf(ConfigKey.MoneyLabel).value, degraded.itemOf(ConfigKey.MoneyLabel).value)
        assertEquals(store.itemOf(ConfigKey.StainDefault).value, degraded.itemOf(ConfigKey.StainDefault).value)
        assertEquals(store.itemOf(ConfigKey.BarChar1).value, degraded.itemOf(ConfigKey.BarChar1).value)
    }

    @Test
    fun `UseReplaceFile 关闭与分析模式都不读 Replace`() {
        val off = newGameDir(
            emuera = listOf("USE _REPLACE.CSV FILE:NO"),
            replace = listOf("お金の単位 , ￥"),
        )
        val offHost = RootedBuiltinHost(off)
        assertNull(offHost.configReplace, "Config.UseReplaceFile = NO ⇒ 不读（Process.cs:240）")
        assertEquals("${'$'}", offHost.configChain.store.itemOf(ConfigKey.MoneyLabel).string(), "宿主快照保持注册默认（ConfigData.cs:159）")
        assertEquals("${'$'}", ConfigMethods.storeFromHost(offHost).itemOf(ConfigKey.MoneyLabel).string(), "函数层通道同样保持默认")
        assertEquals("${'$'}", offHost.moneyLabel, "MONEYSTR 也保持注册默认（Config.cs:720）")
        assertNull(offHost.configOverrides["CURRENCY SYMBOL"], "未装载的层不进覆盖表")

        val analysis = newGameDir(replace = listOf("お金の単位 , ￥"))
        val analysisHost = RootedBuiltinHost(analysis, analysisMode = true)
        assertNull(analysisHost.configReplace, "-a 分析模式不读（Process.cs:240 的 !Program.AnalysisMode）")
        assertEquals("${'$'}", ConfigMethods.storeFromHost(analysisHost).itemOf(ConfigKey.MoneyLabel).string())
    }

    @Test
    fun `Replace 重放幂等 同一批行叠两次列表不翻倍`() {
        val root = newGameDir(
            replace = listOf("お金の単位 , ￥", "汚れの初期値, 0/0/2/1/8/0/1/8"),
        )
        val host = RootedBuiltinHost(root)
        val store = ConfigStore()
        host.applyChainTo(store)
        host.applyChainTo(store) // 第二次重放同一批字节（模拟 reset 后复用同一张表）
        assertEquals(eightStains, store.itemOf(ConfigKey.StainDefault).longList(), "List<long> 每次先清空再写（ConfigItem.cs:147-158）")
        assertEquals("￥", store.itemOf(ConfigKey.MoneyLabel).string())
        assertEquals(host.configChain.store.itemOf(ConfigKey.StainDefault).value, store.itemOf(ConfigKey.StainDefault).value)
    }

    @Test
    fun `loadConfigOverrides 无宿主形态同样带 Replace`() {
        val root = newGameDir(replace = listOf("お金の単位 , ￥"), emuera = listOf("USE SAV FOLDER:YES"))
        val overrides = loadConfigOverrides(root)
        assertEquals("￥", overrides["CURRENCY SYMBOL"], "GameBootSmoke / BootPlayChain 用的那条通道也吃到 Replace")
        assertEquals("YES", overrides["USE SAV FOLDER"], "GAME 组读数不变")
    }

    // ------------------------------------------------------------------
    // 语料真实文件（吃 ERATW_GAMEDIR，缺失按 skipped 计）
    // ------------------------------------------------------------------

    @Test
    fun `语料 _Replace 的 4 条有效行经两条通道生效`() {
        val gameDir = corpusGameDir()
        val host = RootedBuiltinHost(gameDir)
        val replace = assertNotNull(host.configReplace, "语料 emuera.config:3 是 USE _REPLACE.CSV FILE:YES")
        assertEquals(ConfigLayerPresence.PRESENT, replace.presence)

        val result = assertNotNull(replace.result)
        assertEquals(
            mapOf(
                ConfigLineStatus.COMMENT to 9,
                ConfigLineStatus.BLANK to 3,
                ConfigLineStatus.APPLIED to 4,
                ConfigLineStatus.UNMATCHED_KEY to 1, // 第 15 行「販売道具数」不是注册标签（见下）
            ),
            result.records.groupingBy { it.status }.eachCount(),
            "逐行口径与 EmueraConfigGoldenTest 的语料外部读数一致",
        )
        assertEquals(
            setOf(ConfigKey.MoneyLabel, ConfigKey.MoneyFirst, ConfigKey.LoadLabel, ConfigKey.StainDefault),
            replace.appliedKeys,
        )

        // 覆盖表通道（barChars / 白名单等消费面）
        assertEquals("￥", host.configOverrides["CURRENCY SYMBOL"], "CSV/_Replace.csv:7「お金の単位 , ￥」")
        assertEquals("前", host.configOverrides["CURRENCY SYMBOL POSITION"], "第 9 行")
        assertEquals("￥", host.moneyLabel, "MONEYSTR 同源（Config.MoneyLabel，Config.cs:720）")
        assertEquals(
            "如果出现了错误，请根据目录下的报错指导文件进行报错",
            host.configOverrides["LOADING MESSAGE"],
            "第 14 行（基准 pc-golden-root.txt:9 就是这句）",
        )

        // 函数层通道（GETCONFIG 的条目表）
        val store = ConfigMethods.storeFromHost(host)
        assertEquals("￥", store.itemOf(ConfigKey.MoneyLabel).string(), "原缺陷：这里恒为 $")
        assertTrue(store.itemOf(ConfigKey.MoneyFirst).bool())
        assertEquals(
            "如果出现了错误，请根据目录下的报错指导文件进行报错",
            store.itemOf(ConfigKey.LoadLabel).string(),
        )
        assertEquals(eightStains, store.itemOf(ConfigKey.StainDefault).longList(), "原缺陷：这里恒为 5 项的注册默认")

        // 语料第 15 行写的是「販売道具数」，C# replaceArray 注册的是「販売アイテム数」
        // （ConfigData.cs:162），`GetReplaceItem(key)?.TryParse` 的 `?.` 让它静默跳过
        // （ConfigData.cs:622）—— 原版读数就是「不生效」，因此 MaxShopItem 保持 100。
        // 这不是接线缺口：EmueraConfigGoldenTest 早已把该行钉成 UNMATCHED_KEY。
        assertEquals(ConfigLineStatus.UNMATCHED_KEY, result.records[14].status)
        assertEquals(100, store.itemOf(ConfigKey.MaxShopItem).int(), "語料 typo 不改写注册默认（官方行为，ADR-005）")
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun newGameDir(emuera: List<String>? = null, replace: List<String>? = null): Path {
        val root = Files.createTempDirectory("eratw-replace-wire")
        val csv = Files.createDirectories(root.resolve(CSV_DIR_NAME))
        emuera?.let { write(root.resolve(EMUERA_CONFIG_FILE_NAME), it) }
        replace?.let { write(csv.resolve(REPLACE_FILE_NAME), it) }
        return root
    }

    private fun write(file: Path, lines: List<String>): Path {
        Files.createDirectories(file.parent)
        Files.write(file, lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        return file
    }

    /**
     * 语料游戏根目录（`ERATW_GAMEDIR` 指向的那层，含 `emuera.config` 与 `CSV/_Replace.csv`）。
     * 不猜路径：环境变量未设置或缺文件即 assumeTrue 跳过（按 skipped 计数）。
     */
    private fun corpusGameDir(): Path {
        val env = System.getenv("ERATW_GAMEDIR")
        assumeTrue(env != null, "ERATW_GAMEDIR 未设置：跳过 _Replace.csv 语料判据")
        val dir = Paths.get(env!!)
        val csv = EmueraConfigParser.resolveCsvDir(dir)
        assumeTrue(Files.isRegularFile(dir.resolve(EMUERA_CONFIG_FILE_NAME)), "语料缺 emuera.config：跳过")
        assumeTrue(Files.isRegularFile(csv.resolve(REPLACE_FILE_NAME)), "语料缺 CSV/_Replace.csv：跳过")
        return dir
    }
}
