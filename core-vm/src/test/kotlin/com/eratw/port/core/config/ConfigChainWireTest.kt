package com.eratw.port.core.config

import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.ConfigMethods
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.RootedBuiltinHost
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 三层配置链**接进生产消费点**的回归（M4-K k-configwire）——[ConfigChainTest] 钉的是链本体，
 * 本文件钉的是「链读到的字节怎么到达每个消费者」：
 *
 * * [RootedBuiltinHost]（`core/proc/BootHosts.kt`）开局一次读盘：`configChain` 三层、
 *   `configReplace` = `CsvDir/_Replace.csv`（在链条之后摊平 / 重放，spec: `Process.cs:240-270`）、
 *   `configOverrides` = 三层 + Replace 合并快照（原版对应 `ConfigData.LoadConfig` 的三次 `loadConfig`
 *   加 `Config.SetConfig`，spec: `ConfigData.cs:479-494`，调用点 `Program.cs:95`）；
 * * [ConfigMethods.storeFromHost] 双通道：宿主实现 `ConfigChainSource` 就重放三层
 *   （`replayChainOn`），否则回落覆盖表（`loadOverrideMap`）—— 两通道读数必须一致；
 * * [ConfigMethods.useSaveFolder]：`UseSaveFolder` 条目现值的**唯一取值点**
 *   （原版 `Config.UseSaveFolder` 由 `SetConfig` 从条目取，spec: `Config.cs:147`、`:367`，
 *   再决定 `SavDir`，spec: `Config.cs:471-481`）；
 * * `debug.config` 写面（spec: `ConfigData.cs:667-710`）：DEBUG 组七条（`:152-158`）
 *   **只由 debug 层写**——主链只查 `GetConfigItem`（`:539`，查不到 `:541` 静默 continue），
 *   debug 层只查 `GetDebugItem`（`:684`）且不认英文标签（`:330-348` 无 EngText 分支），
 *   两侧分区都是官方行为，原样保留（ADR-005）。
 *
 * 语料判据（画蛇添足版 4.980 实测，只读）：三层全在位 —— `CSV/_default.config` 17 行、
 * `emuera.config` 97 行、`CSV/_fixed.config` 8 行；default 的 17 键与 fixed 的 8 键都被
 * `emuera.config` 的 96 键覆盖且值一致 ⇒ 链合并值与单层相同、差别只有 `fixed` 层钉位；
 * 真语料**没有** `debug/` 目录 ⇒ debug 层恒 ABSENT、DEBUG 七条保持注册默认。
 * 语料用例吃 `ERATW_GAMEDIR` 环境变量，未设置或缺文件时按 skipped 计。
 */
class ConfigChainWireTest {

    // ------------------------------------------------------------------
    // 合成装置：覆盖表 = 三层合并快照（BootHosts 接线的单层退休判据）
    // ------------------------------------------------------------------

    @Test
    fun `RootedBuiltinHost 的覆盖表是三层合并快照`() {
        val root = newGameDir(
            default = listOf("ウィンドウ幅:1000", "マウスを使用する:NO"),
            emuera = listOf("WINDOW WIDTH:1800", "FONT SIZE:19"),
            fixed = listOf("セーブデータをバイナリ形式で保存する:YES"),
        )
        val host = RootedBuiltinHost(root)
        // 层序即优先级（ConfigData.cs:491-493）：emuera 盖 default、fixed 盖 emuera
        assertEquals(
            mapOf(
                "WINDOW WIDTH" to "1800",
                "USE MOUSE" to "NO",
                "FONT SIZE" to "19",
                "USE THE BINARY FORMAT FOR SAVING DATA" to "YES",
            ),
            host.configOverrides,
            "覆盖表 = default → emuera → fixed 依次摊平，键取大写英文标签（AConfigItem.cs:22-23）",
        )
        // 只被 default 写过的键也在表里（单层口径下它会整个消失）
        assertEquals("NO", host.configOverrides["USE MOUSE"], "default 层独有键进表（ConfigData.cs:491）")
        // 与摊平器同源：宿主表就是 toOverrideMap(链 + Replace)（合成目录没有 _Replace.csv ⇒ replace 为 ABSENT）
        assertEquals(EmueraConfigParser.toOverrideMap(host.configChain, host.configReplace), host.configOverrides)
    }

    @Test
    fun `坏行不崩开局且中止层保前缀而覆盖表整表空`() {
        val root = newGameDir(
            default = listOf("ウィンドウ幅:1000", "フォントサイズ:abc"),
            emuera = listOf("マウスを使用する:NO"),
        )
        val host = RootedBuiltinHost(root)
        assertTrue(host.configOverrides.isEmpty(), "本波失败合同：任一层行级中止 ⇒ 覆盖表整表空（不抛）")
        assertTrue(host.configChain.abortedLayers.map { it.role }.contains(ConfigChainRole.DEFAULT))

        val store = ConfigMethods.storeFromHost(host)
        assertEquals(1000, store.itemOf(ConfigKey.WindowX).int(), "中止层保住已解析前缀（ConfigData.cs:579-586 的 catch 包在 while 外）")
        assertFalse(store.itemOf(ConfigKey.UseMouse).bool(), "三次 loadConfig 彼此独立，其余层照旧装载（ConfigData.cs:491-493）")
        assertEquals(18, store.itemOf(ConfigKey.FontSize).int(), "抛错那行保留旧值，开局不崩（ConfigData.cs:563-586）")
    }

    // ------------------------------------------------------------------
    // 合成装置：storeFromHost 双通道 + replayChainOn
    // ------------------------------------------------------------------

    @Test
    fun `storeFromHost 双通道读数一致且钉位只在链通道`() {
        val root = newGameDir(
            default = listOf("ウィンドウ幅:1000"),
            emuera = listOf("WINDOW WIDTH:1800", "FONT SIZE:19"),
            fixed = listOf("セーブデータをバイナリ形式で保存する:YES"),
        )
        val rooted = RootedBuiltinHost(root)
        // 只转发覆盖表的包装宿主（ConsoleBuiltinHost 一类）—— 走降级通道
        val wrapper = object : BuiltinHost {
            override val configOverrides: Map<String, String> = rooted.configOverrides
        }
        assertTrue(rooted is ConfigChainSource, "rooted 宿主实现 ConfigChainSource ⇒ 优先通道")
        val chainStore = ConfigMethods.storeFromHost(rooted)
        val mapStore = ConfigMethods.storeFromHost(wrapper)

        assertEquals(chainStore.snapshot(), mapStore.snapshot(), "两通道读数一致（覆盖表自本波起就是三层合并快照）")
        assertEquals(1800, chainStore.itemOf(ConfigKey.WindowX).int(), "文件值盖过宿主运行态 760（ConfigData.cs:491-494 → Config.cs:296 的方向）")
        assertEquals(19, chainStore.itemOf(ConfigKey.FontSize).int())
        assertTrue(chainStore.itemOf(ConfigKey.SystemSaveInBinary).fixed, "钉位走链通道（ConfigData.cs:573-576）")
        assertFalse(mapStore.itemOf(ConfigKey.SystemSaveInBinary).fixed, "覆盖表是值快照，表达不了 Fixed ⇒ 降级通道不钉")
    }

    /**
     * 组长接线（M4-K 收口后）：`ConsoleBuiltinHost` 用 `ConfigChainSource by delegate` 把
     * 三层链能力一起带过来 ⇒ console/开局路径的 GETCONFIG 走**优先通道**，`fixed` 层钉位不丢
     * （缺委托时只能退回值快照，钉位表达不了）。
     */
    @Test
    fun `ConsoleBuiltinHost 携带三层链所以钉位不丢`() {
        val root = newGameDir(
            default = listOf("ウィンドウ幅:1000"),
            emuera = listOf("WINDOW WIDTH:1800"),
            fixed = listOf("セーブデータをバイナリ形式で保存する:YES"),
        )
        val consoleHost = ConsoleBuiltinHost(root)
        assertTrue(
            consoleHost is ConfigChainSource,
            "接口委托把 ConfigChainSource 一起带过来（BuiltinHost by delegate 只搬 BuiltinHost 成员）",
        )
        val store = ConfigMethods.storeFromHost(consoleHost)
        assertEquals(1800, store.itemOf(ConfigKey.WindowX).int())
        assertTrue(
            store.itemOf(ConfigKey.SystemSaveInBinary).fixed,
            "console 路径也走链通道 ⇒ fixed 层钉位在（ConfigData.cs:573-576）",
        )
    }

    @Test
    fun `replayChainOn 把同一批字节重放进第二条目表`() {
        val root = newGameDir(
            default = listOf("ウィンドウ幅:1000"),
            emuera = listOf("WINDOW WIDTH:1800"),
            fixed = listOf("セーブデータをバイナリ形式で保存する:YES"),
        )
        val load = EmueraConfigParser.loadChainFromDisk(root, ConfigStore())
        val second = ConfigStore()
        val replayed = EmueraConfigParser.replayChainOn(load, second)

        assertEquals(load.results.size, replayed.size, "重放的逐层结果与原链在位层数相同")
        assertEquals(load.store.snapshot(), second.snapshot(), "每 VM 的条目表吃的是同一批字节")
        assertEquals(load.fixedKeys, second.items.filter { it.fixed }.map { it.spec.key }.toSet(), "钉位一并复现（ConfigData.cs:491-493 的层序与 fix 开关）")
    }

    // ------------------------------------------------------------------
    // 合成装置：useSaveFolder 取值点 + debug 层写面（ADR-005）
    // ------------------------------------------------------------------

    @Test
    fun `useSaveFolder 从条目表现值取数`() {
        assertFalse(ConfigMethods.useSaveFolder(ConfigStore()), "注册默认 false（ConfigData.cs:97）")

        val root = newGameDir(emuera = listOf("USE SAV FOLDER:YES"))
        val host = RootedBuiltinHost(root)
        assertTrue(host.useSaveFolder, "宿主交出面读 configChain.store 的条目现值")
        assertTrue(ConfigMethods.useSaveFolder(ConfigMethods.storeFromHost(host)), "VM 条目表同样取到 true")

        val noRoot = newGameDir(emuera = listOf("USE SAV FOLDER:NO"))
        assertFalse(RootedBuiltinHost(noRoot).useSaveFolder, "false ⇒ SavDir = ExeDir（Config.cs:471-481 的 else 分支）")
    }

    @Test
    fun `debug 层只认日文名而主链写不到 DEBUG 组`() {
        val root = newGameDir()
        write(root.resolve(DEBUG_DIR_NAME).resolve(DEBUG_CONFIG_FILE_NAME), listOf("デバッグウィンドウ位置X:777", "DEBUG WINDOW X POSITION:777"))
        val load = EmueraConfigParser.loadDebugFromDisk(root, ConfigStore())
        assertEquals(ConfigLayerPresence.PRESENT, load.presence)
        val result = assertNotNull(load.result)
        assertEquals(listOf(ConfigLineStatus.APPLIED, ConfigLineStatus.UNMATCHED_KEY), result.records.map { it.status }, "debug 层只比 Name 与 Text，英文标签形落空（ConfigData.cs:684、:330-348；ADR-005）")
        assertEquals(777, load.store.itemOf(ConfigKey.DebugWindowPosX).int())

        val second = ConfigStore()
        EmueraConfigParser.replayDebugOn(load, second)
        assertEquals(777, second.itemOf(ConfigKey.DebugWindowPosX).int(), "重放同字节（ConfigData.cs:684 的 GetDebugItem 口径）")

        // 反向：主链写不进 DEBUG 组，debug 层也写不进 GAME 组 —— 官方分区原样保留
        val mainStore = ConfigStore()
        val mainResult = EmueraConfigParser.loadGameFile(ConfigFile("emuera.config", listOf("デバッグウィンドウ位置X:777")), mainStore)
        assertEquals(ConfigLineStatus.UNMATCHED_KEY, mainResult.records.single().status, "主链只查 GetConfigItem（ConfigData.cs:539 的 configArray）")
        assertEquals(0, mainStore.itemOf(ConfigKey.DebugWindowPosX).int())
        val dbgStore = ConfigStore()
        val dbgResult = EmueraConfigParser.loadDebugFile(ConfigFile("debug.config", listOf("WINDOW WIDTH:1800")), dbgStore)
        assertEquals(ConfigLineStatus.UNMATCHED_KEY, dbgResult.records.single().status, "debug 层只查 debugArray，GAME 组条目一条都写不到")
        assertEquals(760, dbgStore.itemOf(ConfigKey.WindowX).int())
    }

    // ------------------------------------------------------------------
    // 语料真实文件（吃 ERATW_GAMEDIR，缺失按 skipped 计）
    // ------------------------------------------------------------------

    @Test
    fun `语料三层经宿主接线全在位且合并值与单层一致`() {
        val gameDir = corpusGameDir()
        val host = RootedBuiltinHost(gameDir)
        val load = host.configChain

        assertEquals(ConfigLayerPresence.PRESENT, load.layer(ConfigChainRole.DEFAULT).presence)
        assertEquals(ConfigLayerPresence.PRESENT, load.layer(ConfigChainRole.EMUERA).presence)
        assertEquals(ConfigLayerPresence.PRESENT, load.layer(ConfigChainRole.FIXED).presence)
        assertEquals(17, load.defaultLineCount, "CSV/_default.config 17 行")
        assertEquals(97, load.emueraLineCount, "emuera.config 97 行")
        assertEquals(8, load.fixedLineCount, "CSV/_fixed.config 8 行")
        assertEquals(listOf(17, 96, 8), load.perLayerKeys.map { it.size }, "emuera 层 96 = 97 行 − 1 行 CLIPBOARD- ANYKEY WAIT TRIGGER 匹配不上（EditorArgument 空值行按 DIRECT_ASSIGNED 计，ConfigData.cs:564-568）")
        assertEquals(96, load.touchedKeys.size, "default 的 17 键与 fixed 的 8 键都被 emuera 的 96 键覆盖")

        // 链合并值与单层相同：把 emuera 层单独读进新表，逐条比全量快照
        val emueraOnly = ConfigStore()
        EmueraConfigParser.loadGameFile(load.layer(ConfigChainRole.EMUERA).file!!, emueraOnly)
        // 宿主快照的 REPLACE 组已被 CSV/_Replace.csv 覆盖（configReplace 就地写进 configChain.store），
        // 单层对照也叠一次同字节，才能逐组比全量快照（GAME 组仍等价于单层 emuera.config）。
        host.configReplace?.let { EmueraConfigParser.replayReplaceOn(it, emueraOnly) }
        assertEquals(emueraOnly.snapshot(), load.store.snapshot(), "链合并 + Replace 后的值读数与单层 emuera.config + 同一份 Replace 完全相同")
        assertEquals(emptySet(), emueraOnly.items.filter { it.fixed }.map { it.spec.key }.toSet(), "单层没有钉位")
        assertEquals(8, load.fixedKeys.size, "fixed 层 8 行全是 bool ⇒ 全部置钉（ConfigData.cs:573-576）")
        assertTrue(load.fixedKeys.contains(ConfigKey.SystemSaveInBinary))
        assertTrue(load.fixedKeys.contains(ConfigKey.UseERD))
        for (key in load.fixedKeys) {
            assertEquals(emueraOnly.itemOf(key).value, load.store.itemOf(key).value, "钉位键的值两层一致")
            assertTrue(load.store.itemOf(key).fixed)
            assertFalse(emueraOnly.itemOf(key).fixed, "与单层的差别只有 fixed 层钉位")
        }
    }

    @Test
    fun `语料两通道读数一致且 UseSaveFolder 为真`() {
        val gameDir = corpusGameDir()
        val host = RootedBuiltinHost(gameDir)
        val chainStore = ConfigMethods.storeFromHost(host)
        val wrapper = object : BuiltinHost {
            override val configOverrides: Map<String, String> = host.configOverrides
        }
        val mapStore = ConfigMethods.storeFromHost(wrapper)

        assertEquals(
            EmueraConfigParser.toOverrideMap(host.configChain, host.configReplace),
            host.configOverrides,
            "宿主覆盖表与摊平器同源（链三层 + CSV/_Replace.csv）",
        )
        assertEquals(mapStore.snapshot(), chainStore.snapshot(), "replayChainOn 通道与覆盖表通道读数一致")
        assertTrue(host.useSaveFolder, "语料 emuera.config:51 与 CSV/_default.config:17 都是 YES")
        assertTrue(ConfigMethods.useSaveFolder(chainStore))
        assertEquals("USE SAV FOLDER:YES", host.configChain.layer(ConfigChainRole.EMUERA).file!!.lines[50], "emuera.config 第 51 行（Config.cs:471-481 的 true ⇒ ExeDir 下的 sav 目录）")
        assertEquals("セーブデータをsavフォルダ内に作成する:YES", host.configChain.layer(ConfigChainRole.DEFAULT).file!!.lines[16], "CSV/_default.config 第 17 行")
    }

    @Test
    fun `真语料无 debug 层且 DEBUG 七条保持注册默认`() {
        val gameDir = corpusGameDir()
        assertFalse(Files.exists(EmueraConfigParser.resolveDebugDir(gameDir)), "语料根下没有 debug 目录（只有两支 -debug 启动脚本）")

        val host = RootedBuiltinHost(gameDir, debugMode = true)
        val dbg = host.configDebug
        assertNotNull(dbg, "debugMode=true 时必有一个读数对象（哪怕 ABSENT，ConfigData.cs:670 的 File.Exists 短路）")
        assertEquals(ConfigLayerPresence.ABSENT, dbg!!.presence, "debug.config 不存在 ⇒ ABSENT，不发明内容")
        assertNull(dbg.file)
        assertNull(dbg.result)

        val fresh = ConfigStore()
        val store = ConfigMethods.storeFromHost(host)
        val debugItems = store.itemsOf(ConfigGroup.DEBUG)
        assertEquals(7, debugItems.size, "DEBUG 组七条（ConfigData.cs:152-158）")
        for (item in debugItems) {
            assertEquals(fresh.itemOf(item.spec.key).value, item.value, "DEBUG 条目保持注册默认：debug 层缺席不写任何值")
        }
        assertEquals(0, store.itemOf(ConfigKey.DebugWindowPosX).int())
        assertNull(RootedBuiltinHost(gameDir).configDebug, "未开 -debug 不读 debug 层（Program.cs:142-144 的 if (DebugMode) 守卫）")
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun newGameDir(
        default: List<String>? = null,
        emuera: List<String>? = null,
        fixed: List<String>? = null,
    ): Path {
        val root = Files.createTempDirectory("eratw-config-wire")
        val csv = Files.createDirectories(root.resolve(CSV_DIR_NAME))
        default?.let { write(csv.resolve("_default.config"), it) }
        emuera?.let { write(root.resolve(EMUERA_CONFIG_FILE_NAME), it) }
        fixed?.let { write(csv.resolve("_fixed.config"), it) }
        return root
    }

    private fun write(file: Path, lines: List<String>): Path {
        Files.createDirectories(file.parent)
        Files.write(file, lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        return file
    }

    /**
     * 语料游戏根目录（`ERATW_GAMEDIR` 指向的那层，含 `emuera.config` 与 `CSV` 两层配置）。
     * 不猜路径：环境变量未设置或三层缺一即 assumeTrue 跳过（按 skipped 计数）。
     */
    private fun corpusGameDir(): Path {
        val env = System.getenv("ERATW_GAMEDIR")
        assumeTrue(env != null, "ERATW_GAMEDIR 未设置：跳过三层链接线判据")
        val dir = Paths.get(env!!)
        val csv = EmueraConfigParser.resolveCsvDir(dir)
        assumeTrue(Files.isRegularFile(dir.resolve(EMUERA_CONFIG_FILE_NAME)), "语料缺 emuera.config：跳过")
        assumeTrue(Files.isRegularFile(csv.resolve("_default.config")), "语料缺 CSV/_default.config：跳过")
        assumeTrue(Files.isRegularFile(csv.resolve("_fixed.config")), "语料缺 CSV/_fixed.config：跳过")
        return dir
    }
}
