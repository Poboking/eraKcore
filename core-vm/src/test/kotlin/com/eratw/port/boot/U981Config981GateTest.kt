package com.eratw.port.boot

import com.eratw.port.core.config.ConfigChainLayer
import com.eratw.port.core.config.ConfigChainRole
import com.eratw.port.core.config.ConfigFile
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigLayerPresence
import com.eratw.port.core.config.ConfigLineStatus
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.ConfigWarning
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.loadConfigOverrides
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U981-config（B 路）：4.981 `emuera.config` 升版的解析闸与消费点两档（CONTRACT-U981-CFG §3）。
 *
 * 4.981 相对 4.980 的真实 diff（2026-11-01 逐行实读两版文件，`tr -d '\r'` 归一后 diff）：
 *   * 改值键 2 条：`MAX HISTORY LOG LINES` 900→1500（两文件 ：18）、`FPS` 5→3（:28）；
 *   * 新增键 6 条（4.981 emuera.config:68-71、:77-78）：`SKIASHARP IMAGE QUALITY:High`、
 *     `SKIASHARP FONT HINTING:None`、`SKIASHARP FONT EDGING:SubpixelAntiAlias`、
 *     `RENDERING BACKEND:Auto`、`PLUGIN AVAILABLE WARNING:YES`、
 *     `DISABLE BEFORE_ERROR/THROW EVENTS:YES`；
 *   * 编码：两版都带 UTF-8 BOM；换行 4.980 为 LF-only、4.981 为 CRLF（CR=103/LF=103）。
 *
 * 未知键口径（原版证据）：`GetConfigItem(array[0].Trim())` 查不到条目就 `continue`，
 * 不告警不弹框（spec: `ConfigData.cs:539-543`，4.981 反编译对应 `ConfigData.cs:547-549`）。
 * 移植侧对应
 * [com.eratw.port.core.config.ConfigLineStatus.UNMATCHED_KEY]
 * （EmueraConfigParser.kt:178-182）。6 条新键在 1.824 原版源码中均无踪迹
 * （reference 全树 grep `Skiasharp` / `PluginAvailable` / `BeforeError` 零命中）。
 * 4.981 语义已取证（2026-11-01，证据源 = `Emuera_skiaV16_x64.exe` .NET 单文件
 * bundle 手工解包出 `Emuera.dll` 后用 ilspycmd 8.2.0 反编译；与 fork
 * commit qirchen421/emuera.sk@48494165 的对应关系版本未对证）：
 *   - 6 条键在 4.981 是**已注册键**（`ConfigData.cs:115-118`、`:124-125`；
 *     `GetConfigItem(string)` 按 Name/Text/EngText 匹配，`ConfigData.cs:269-291`），
 *     emuera.config 里按正常键 `TryParse`（`ConfigData.cs:580`），**不是**未知键
 *     continue；移植侧「按未知键静默忽略」与原版「已注册、PC 侧消费」在解析闸
 *     与本层行为上等效（本层无消费点），下方断言维持不变；
 *   - 注册缺省：ImageQuality=High、FontHinting=None、FontEdging=SubpixelAntiAlias、
 *     RenderingBackend=Auto、PluginAvailableWarn=true、DisableBeforeErrorThrow=false；
 *   - PC 侧读取（`Config.SetConfig`，Config.cs:309 起）：`Config.cs:349-352`、
 *     `:404-405`；消费：ImageQuality → `ASpriteSingle.cs:17` 等处的
 *     `SKPaint.FilterQuality`（图像缩放采样质量）；FontHinting/FontEdging →
 *     `FontFactory.cs:227-228`（SKFont.Hinting/Edging）与 `HtmlManager.cs:976-977`
 *     （CSS 逐行覆盖判断）；Backend → `MainWindow.cs:330-370`（CPU=软件 SKControl，
 *     OpenGL/Auto 试建 SKGLControl，失败回退软件）；PluginAvailableWarn →
 *     `Process.cs:345-348`（装了外部插件时打一条注意行）；DisableBeforeErrorThrow →
 *     `Process.cs:586`、`:2052`（true 时 BEFORE_ERROR/BEFORE_THROW 事件不调用）。
 *     ERB 面：`SET_SKIA_QUALITY`（FunctionIdentifier.cs:4074-4099，`:4661` 注册，
 *     运行期直改三键并 `FontFactory.ClearFont()`）与 `GET_SKIA_QUALITY`
 *     （FunctionMethodCreator.cs:12691-12717，`:13408` 注册）。
 *   - 4.981 版本串：GAMEBASE.CSV「バージョン」值实为 `proto`（CSV/GAMEBASE.CSV:2），
 *     `tryatoi`（GameBase.cs:82-104）解析失败 ⇒ ScriptVersion=0 ⇒
 *     ScriptVersionText="0.000"（GameBase.cs:41-56）；窗口标题 = ScriptTitle +
 *     " " + ScriptVersionText（GameBase.cs:200-210；加载 Process.cs:315，落窗体
 *     Process.cs:321 → EmueraConsole.cs:1951-1962）。「4.981」字样不在程序内，
 *     语料侧来源是 `ERB/DIM.ERH:3` 的 `#DIMS CONST eraTW_Version = "4.981"`；
 *     Emuera 本体版本串 = AssemblyData.cs:19（exe PE 资源实测
 *     ProductVersion=`1824+v24+EMv18+EEv56+Skiav16`）。
 *
 * 消费点两档：
 *   * `MAX HISTORY LOG LINES` 的移植侧消费点在 core-vm 内且**已是 config 驱动**：
 *     GameSession.kt:461 读 `configOverrides["MAX HISTORY LOG LINES"]`（GameSession.kt:926
 *     的 `CONFIG_MAX_LOG`），键缺失才落 `maxLines` 形参 ⇒ `DEFAULT_MAX_LINES`
 *     = `LineBufferConsoleSink.DEFAULT_MAX_LINES` = 5000（LineBufferConsoleSink.kt:3090，
 *     即原版注册缺省，spec: `ConfigData.cs:62`）。本类锁两档：空表 5000、config 1500。
 *   * `FPS` 的消费点是**游戏脚本**：`COLOREDOPTION.ERB:13` 的
 *     `ANIMATERECOLOREDMAPS = 1000 / FPS`，脚本经 `GETCONFIG("フレーム毎秒")` 读条目表，
 *     移植侧无独立消费点（core-vm main grep `ConfigKey.FPS` 只有注册与标签）。
 *     条目值即全部语义，锁两档：空表 5（spec: `ConfigData.cs:72`）、config 3。
 *
 * 语料门控：`ERATW_GAMEDIR` 指向 4.981 语料档才跑，判档只走 [CorpusProfile.detect]
 * （就近读 `ERB/DIM.ERH` 的 `#DIM CONST NOW_VERSION` = 20261017，与 `ConfigChainTest.corpusRoot`
 * 同一把尺），不再看 `emuera.config` 里是否出现 `SKIASHARP IMAGE QUALITY` 等键行（C5）。
 * 缺变量、`emuera.config` 不存在或判为 V4_980 时 `assumeTrue` 跳过 —— 跳过条件逐条写在用例
 * 文案里、交付回执单列 skipped 与原因，不用静默跳过冒充全绿；根不完整或 NOW_VERSION 未登记
 * 时 `detect` 判红不降级（configroot 波红线 2/3）。构造样本见 [U981ExplicitRootCriterionTest]。
 */
class U981Config981GateTest {

    /** 五处门控用例共用的显式跳过条件；回执按此逐条单列 skipped 与原因，不静默报全绿。 */
    private val skipCondition = "ERATW_GAMEDIR 未设/为空、emuera.config 不存在、" +
        "或 CorpusProfile.detect 判为 V4_980（NOW_VERSION 非 20261017）；" +
        "根不完整/未登记判红不跳过"

    /** 4.981 新增的 6 条键（原文标签，emuera.config:68-71、:77-78）。 */
    private val newKeyLines = listOf(
        "SKIASHARP IMAGE QUALITY",
        "SKIASHARP FONT HINTING",
        "SKIASHARP FONT EDGING",
        "RENDERING BACKEND",
        "PLUGIN AVAILABLE WARNING",
        "DISABLE BEFORE_ERROR/THROW EVENTS",
    )

    /**
     * 游戏根发现：`ERATW_GAMEDIR` 必须指向 4.981 语料档。
     *
     * 判档只走 [CorpusProfile.detect]（就近读 `ERB/DIM.ERH` 的 `#DIM CONST NOW_VERSION`，
     * 4.981=20261017），与 `ConfigChainTest.corpusRoot()` 用同一把尺；不再以 `emuera.config`
     * 里是否出现 `SKIASHARP IMAGE QUALITY` / `RENDERING BACKEND` 键行做代理（C5）。
     * 原版同形依据：装载期配置项靠注册条目 + 精确查表 + 值解析 + 显式分支裁决，不靠
     * 「某键字符串是否出现」——`ConfigData.cs:529-543` 的 `array[0].Trim()` 精确查表、
     * `:262-284` 的大写后相等匹配、`ConfigItem.cs:174-178` 的枚举值解析。见
     * [U981ExplicitRootCriterionTest] 类 KDoc。
     *
     * 返回 null 的三档**显式跳过条件**（各用例的 assumeTrue 文案逐条写明，回执单列 skipped）：
     * 一 `ERATW_GAMEDIR` 未设或为空；二 `emuera.config` 不存在；三 `detect` 判为 V4_980。
     * 根不完整（就近找不到 DIM.ERH）或 NOW_VERSION 未登记 ⇒ `detect` 抛 AssertionError 判红，
     * 不降级、不静默跳过（configroot 波红线 2/3，与 `ConfigChainTest.corpusRoot()` 同口径）。
     */
    private fun gameRoot981OrNull(): Path? {
        val g = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }
        val p = g?.let { Path.of(it).toAbsolutePath().normalize() }
        val root = p?.takeIf { Files.isRegularFile(it.resolve("emuera.config")) && is981Root(it) }
        // 跳过原因上屏：Gradle 8.10.2 的 JUnit XML 不序列化 TestAbortedException 文案，
        // assumeTrue 的 message 在报告里只退化成 ignored；这里补一条 stdout 让原因可观测，
        // 不让跳过变成静默。回执按这行与 [skipCondition] 逐条单列 skipped 与原因。
        if (root == null) println("[C5 SKIP] 4.981 语料根缺席：$skipCondition")
        return root
    }

    @Test
    fun `解析闸：4_981 真实 emuera_config 走三层链零告警零中止`() {
        val root = gameRoot981OrNull()
        assumeTrue(root != null, "跳过 <解析闸：4_981 真实 emuera_config 走三层链零告警零中止>：$skipCondition")

        val load = EmueraConfigParser.loadChainFromDisk(root!!, ConfigStore())

        // 三层全在位（语料实测：emuera.config、CSV/_default.config、CSV/_fixed.config 都存在）。
        for (layer in load.layers) {
            assertEquals(
                ConfigLayerPresence.PRESENT,
                layer.presence,
                "层 ${layer.role} 应在位，实际 $layer",
            )
        }
        assertEquals(103, load.layer(ConfigChainRole.EMUERA).lineCount, "emuera.config 层行数")
        assertEquals(emptyList<ConfigWarning>(), load.warnings, "三层链零告警（6 条新键必须按未知键静默，不得报 level-1）")
        assertEquals(emptyList<ConfigChainLayer>(), load.abortedLayers, "零中止（ConfigData.cs:540-543 未知键不抛）")
    }

    @Test
    fun `六条 4_981 新键按未知键静默忽略`() {
        val root = gameRoot981OrNull()
        assumeTrue(root != null, "跳过 <六条 4_981 新键按未知键静默忽略>：$skipCondition")

        val load = EmueraConfigParser.loadChainFromDisk(root!!, ConfigStore())
        val emuera = load.result(ConfigChainRole.EMUERA)!!
        val byKey = emuera.records.associateBy {
            it.rawLine.substringBefore(':').trim().uppercase(java.util.Locale.ROOT)
        }
        for (key in newKeyLines) {
            val record = byKey[key]
            assertTrue(record != null, "4.981 emuera.config 应含行键 $key")
            assertEquals(
                ConfigLineStatus.UNMATCHED_KEY, record!!.status,
                "$key 应判未知键（GetConfigItem == null ⇒ continue，ConfigData.cs:540-543）",
            )
            assertTrue(!record.changedValue, "$key 不得写条目表")
        }
        for (key in newKeyLines) {
            assertTrue(
                emuera.appliedKeys.none { it.name.replace('_', ' ') == key },
                "$key 不得进 appliedKeys",
            )
        }
    }

    @Test
    fun `改值键落表：链后 MaxLog=1500、FPS=3 且生效视图不夹取`() {
        val root = gameRoot981OrNull()
        assumeTrue(root != null, "跳过 <改值键落表：链后 MaxLog=1500、FPS=3 且生效视图不夹取>：$skipCondition")

        val load = EmueraConfigParser.loadChainFromDisk(root!!, ConfigStore())
        assertEquals(1500, load.store.itemOf(ConfigKey.MaxLog).int(), "MAX HISTORY LOG LINES（4.981 emuera.config:18）")
        assertEquals(3, load.store.itemOf(ConfigKey.FPS).int(), "FPS（4.981 emuera.config:28）")

        // 1500 ≥ FLOOR(500) ⇒ 生效视图不抬也不夹（EffectiveConfig.kt:290-296、:341）。
        val effective = EffectiveConfig.of(load.store)
        assertEquals(1500, effective.maxLog.effective)
        assertEquals(false, effective.maxLog.raisedToFloor)
    }

    @Test
    fun `4_981 的 CRLF 换行被 ReadLine 口径归一（行文本不含 CR）`() {
        val root = gameRoot981OrNull()
        assumeTrue(root != null, "跳过 <4_981 的 CRLF 换行被 ReadLine 口径归一>：$skipCondition")

        val lines = EmueraConfigParser.readConfigLines(root!!.resolve("emuera.config"))
        assumeTrue(lines != null, "跳过条件 = EmueraConfigParser.readConfigLines 返回 null（emuera.config 读不动）：<4_981 的 CRLF 换行被 ReadLine 口径归一>")
        assertEquals(103, lines!!.size, "ReadLine 口径行数（4.981 实测 103 行）")
        assertTrue(lines.none { it.contains('\r') }, "行文本不得残留 CR（EmueraConfigParser.kt:519 前置剥 CR）")
        // readConfigLines 的口径：BOM 字符留在第 0 行行首，由 ConfigFile.stripBom 在解析时剥掉
        //（EmueraConfigParser.kt:479-480 的 KDoc 与 :164 的调用点）；在解析层断言剥除后的形状。
        assertEquals("IGNORE CASE:YES", ConfigFile.stripBom(lines[0]), "BOM 经 stripBom 剥除且首行完整")
    }

    @Test
    fun `缺省档：空条目表 MaxLog=5000、FPS=5（0_2_x 玩家行为不变）`() {
        val store = ConfigStore()
        assertEquals(5000, store.itemOf(ConfigKey.MaxLog).int(), "注册缺省（ConfigData.cs:62）")
        assertEquals(5, store.itemOf(ConfigKey.FPS).int(), "注册缺省（ConfigData.cs:72）")
        assertEquals(5000, LineBufferConsoleSink.DEFAULT_MAX_LINES, "行表容量缺省（LineBufferConsoleSink.kt:3090）")
        assertEquals(LineBufferConsoleSink.DEFAULT_MAX_LINES, GameSession.DEFAULT_MAX_LINES, "GameSession 缺省与行表同源")
    }

    @Test
    fun `消费档：loadConfigOverrides 读出 1500 与 3 供 GameSession 消费`() {        val root = gameRoot981OrNull()
        assumeTrue(root != null, "跳过 <消费档：loadConfigOverrides 读出 1500 与 3 供 GameSession 消费>：$skipCondition")

        // 这张表就是 GameSession 取数源（configOverrides）。
        val overrides = loadConfigOverrides(root!!)
        assertEquals("1500", overrides["MAX HISTORY LOG LINES"], "GameSession CONFIG_MAX_LOG 键")
        assertEquals("3", overrides["FPS"], "GETCONFIG(\"FPS\") 的脚本消费链取同一条目")
    }

    @Test
    fun `FPS 经 GETCONFIG 英文标签命中 脚本侧消费链读到 config 值`() {
        // FPS 的**真实消费点在脚本侧**（core-vm main 无独立消费者，见类 KDoc）：语料
        // `COLOREDMAPS/COLOREDOPTION.ERB:13`、`SHOP関連/OPTION.ERB:607` 的 `1000 / GETCONFIG("FPS")`。
        // GETCONFIG 的取数路径 = `ConfigStore.resolveAny`（ConfigMethods.kt:336
        // `store.resolveAny(name)`；GetItem 三级串联 spec: `ConfigData.cs:236-248`）。
        // `"FPS"` 是全表唯一的全大写枚举名 ⇒ 经 [ConfigSpec.matchesUpperKey] 的 Name 分支命中
        // （ConfigSpec.kt:78-80），与 config 文件里的英文键 `FPS:3` 走**同一条**匹配；
        // 缺省/有值两档都在这里锁定。
        val store = ConfigStore()
        val registered = store.resolveAny("FPS")
        assertTrue(registered != null, "GETCONFIG(\"FPS\") 应命中 FPS 条目（Name 分支）")
        assertEquals(ConfigKey.FPS, registered!!.spec.key, "命中的是 FPS 条目")
        assertEquals(5, registered.int(), "注册缺省 5（ConfigData.cs:72）")

        // 有值则采用（4.981 emuera.config:28 = 3）：同一查表路径读到条目里的新值。
        assertTrue(store.itemOf(ConfigKey.FPS).assign(ConfigValue.IntValue(3)), "FPS 条目应可写")
        assertEquals(3, store.resolveAny("FPS")!!.int(), "config 3 经 GETCONFIG 同一条路读到")
        assertEquals(3, store.itemOf(ConfigKey.FPS).int(), "条目值随之更新")
    }
}
