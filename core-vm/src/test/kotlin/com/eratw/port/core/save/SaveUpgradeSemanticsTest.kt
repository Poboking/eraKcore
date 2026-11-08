package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.GameBaseGate
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 4.981 升版存档语义锁（契约 CONTRACT-U981-SAVE §3.3）。
 *
 * 背景见 specs/verify-notes.md「4.981 升版 · C 路」节：eratw 两版的 GameBase.csv 都没有
 * 可解析的 `バージョン`（`proto` ⇒ ScriptVersionDefined=false，GameBase.cs:138-140），
 * 版本闸走「未定义即通过」分支（GameBase.cs:69-74）；存档按变量名索引
 * （specs/save-format.md §2.2），未知键丢弃、缺失键由变量池保持默认值。
 *
 * 用例全部吃**合成档**（零环境依赖，不吃 ERATW_* 门）：
 * 1. 未知键丢弃：4.981 新增的 SAVEDATA 键在旧表（keepKey）外 ⇒ 装载不炸、既有键照常生效；
 * 2. 版本校验：复刻两版 GameBase.csv 的实值（コード,7153 / バージョン,proto /
 *    バージョン違い認める,0012），ScriptVersion 未声明 ⇒ 任意存档版本号都放行；
 * 3. 缺失键填 0/空串：缩微合成档（只有 4.980 时代就存在的键）经生产装载路径
 *    （[FileSystemOps.loadData] = 复位 + [SaveData.applyTo]）灌回后，
 *    新变量读回 0 / 空串 —— 与「未初始化」同值，对应 4.981 新增 SAVEDATA 变量里
 *    「0 等价未初始化」已判定的那一批（verify-notes 同节有逐变量证据）；
 * 4. `GMF` 位 + `GMF_HOME_MAP` + `MAIN_MAP` 三者随升版档独立往返，并按 GMF 位自洽 ——
 *    升版语料 `@GET_HOME_MAP()`（4.981 新增，`COMMON2.ERB:90-98`）的语义前提
 *    （取证见 verify-notes §26.3；三键声明 `DLC/DLC.ERH:139`、`GENSOKYO_MAP_FIX.ERH:2`、`DIM.ERH:248`）。
 */
class SaveUpgradeSemanticsTest {

    // ------------------------------------------------------------------
    // 装置（构造手法与 SaveRoundTripTest / SaveUnknownKeySkipTest 同源）
    // ------------------------------------------------------------------

    private fun idx(vararg v: Long): LongArray = v

    /** 内存 SaveFileIo（路径键与生产实现同为相对路径）。 */
    private class MemorySaveIo : SaveFileIo {
        val files = LinkedHashMap<String, ByteArray>()
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun read(path: String): ByteArray? = files[path]?.copyOf()
        override fun write(path: String, bytes: ByteArray): Boolean {
            files[path] = bytes.copyOf()
            return true
        }
        override fun delete(path: String): Boolean {
            files.remove(path)
            return true
        }
    }

    /** 缩微合成档：NORMAL + 无压缩，头部字段对齐真档实测（uniqueCode/scriptVersion 可调）。 */
    private fun minimalSave(
        variables: List<SaveEntry>,
        character: List<SaveEntry> = listOf(SaveEntry.IntValue("NO", 0)),
        uniqueCode: Long = 0L,
        scriptVersion: Long = 0L,
    ): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = uniqueCode,
            scriptVersion = scriptVersion,
            text = "upgrade-semantics",
            characters = listOf(character),
            variables = variables,
            emData = emptyList(),
            compressed = false,
        ),
    )

    /** 4.980 语料里已存在的键（= 升版前变量表口径的 keepKey）。 */
    private val legacyKeys: Set<String> = setOf("MONEY", "DAY", "NO", "CFLAG", "SAVESTR")

    // ------------------------------------------------------------------
    // 1. 未知键丢弃（specs/save-format.md §2.2 按变量名索引 / §5.3 未知键丢弃）
    // ------------------------------------------------------------------

    @Test
    fun `升版档的 4点981 新增键被丢弃 既有键照常生效`() {
        // 新增键取自 verify-notes「4.981 新增 SAVEDATA 变量」实取证：
        // 生活系统_存档版本（0_生活系统_变量.ERH:16）、ADD_BANQUETS_SCALE（宴会补丁）、
        // 孵化槽_蛋种（字符串，0_生活系统_变量.ERH:123）、生活系统_养蜂。
        val bytes = minimalSave(
            variables = listOf(
                SaveEntry.IntValue("MONEY", 123),
                SaveEntry.IntValue("DAY", 42),
                SaveEntry.IntValue("生活系统_存档版本", 1),
                SaveEntry.IntArray1("ADD_BANQUETS_SCALE", idx(5000, 2500, 1500)),
                SaveEntry.StrValue("孵化槽_蛋种", "鸽蛋"),
            ),
            character = listOf(
                SaveEntry.IntValue("NO", 3),
                SaveEntry.IntArray1("CFLAG", LongArray(8) { (it + 1).toLong() }),
                SaveEntry.IntValue("生活系统_养蜂", 7),
            ),
        )

        // 生产口径（keepKey = 升版前变量表）：装载不炸；新增键整条不落地。
        val kept = SaveFormat.read(bytes) { it in legacyKeys }
        assertEquals(listOf("MONEY", "DAY"), kept.variables.map { it.key }, "未知键整条丢弃，顺序保持")
        assertEquals(123L, (kept.variables[0] as SaveEntry.IntValue).value, "MONEY 值保留")
        assertEquals(42L, (kept.variables[1] as SaveEntry.IntValue).value, "DAY 值保留")
        assertEquals(listOf("NO", "CFLAG"), kept.characters[0].map { it.key }, "角色块新增键同样丢弃")
        val cflag = kept.characters[0].single { it.key == "CFLAG" } as SaveEntry.IntArray1
        assertEquals(8, cflag.values.size, "已知数组按存档尺寸读满")
        assertEquals(5L, cflag.values[4], "CFLAG 元素值保留")
        assertNull(kept.variables.firstOrNull { it.key == "生活系统_存档版本" })
        assertNull(kept.characters[0].firstOrNull { it.key == "生活系统_养蜂" })

        // 旧口径（不给过滤器）：同一份档全量读入、同样不炸（读侧对未知键「读完即丢」不在这里发生，
        // SaveFormat 层只留不留值由 keepKey 决定 —— 与 SaveUnknownKeySkipTest 的两口径一致）。
        val all = SaveFormat.read(bytes)
        assertEquals(5, all.variables.size, "旧口径保留全部条目")
        assertNotNull(all.variables.firstOrNull { it.key == "ADD_BANQUETS_SCALE" })
        assertEquals(3, all.characters[0].size, "旧口径角色块三条全留")
    }

    // ------------------------------------------------------------------
    // 2. 版本校验（GameBase.cs:60-80；两版 GameBase.csv 实值）
    // ------------------------------------------------------------------

    /**
     * 复刻 4.980 / 4.981 两版 `CSV/GameBase.csv` 的实值（verify-notes 同节取证）：
     * コード,7153（ScriptUniqueCode=7153，GameBase.cs:132-137）；
     * バージョン,proto（tryatoi 失败 ⇒ ScriptVersionDefined=false、ScriptVersion=0，
     * GameBase.cs:138-140）；バージョン違い認める,0012（ScriptCompatibleMinVersion=12，
     * GameBase.cs:141-143）。
     */
    private fun eratwGameBase(): GameBaseData = GameBaseData(
        gameCode = 7153,
        version = 0,
        versionDefined = false,
        allowVersion = 12,
    )

    @Test
    fun `ScriptVersion 未声明时任意存档版本号都放行`() {
        val gb = eratwGameBase()
        // 第 1 条放行（GameBase.cs:71-74）：!ScriptVersionDefined && target != 1000。
        // 真档 scriptVersion 实测为 0（specs/save-format.md §6.3 表）。
        assertTrue(GameBaseGate.checkVersion(gb, 0), "真档版本号 0 必须放行")
        assertTrue(GameBaseGate.checkVersion(gb, 20_250_413), "任意非 1000 版本号放行（modding_version 同理）")
        // target == 1000 时第 1 条不成立，落到第 2 条：allowVersion(12) <= 1000。
        assertTrue(GameBaseGate.checkVersion(gb, 1000), "target=1000 由兼容下限放行")
    }

    @Test
    fun `唯一码对拍 存档码 0 豁免 其余必须等于 7153`() {
        val gb = eratwGameBase()
        assertTrue(GameBaseGate.uniqueCodeEqualTo(gb, 7153), "存档码 == コード（GameBase.cs:66）")
        assertTrue(GameBaseGate.uniqueCodeEqualTo(gb, 0), "存档码 0 豁免（GameBase.cs:62-64）")
        assertEquals(false, GameBaseGate.uniqueCodeEqualTo(gb, 999), "其余码拒绝（GameBase.cs:66）")
    }

    @Test
    fun `探测入口对真档同款头判 OK`() {
        // uniqueCode=7153 / scriptVersion=0 即真档实测头（specs/save-format.md §6.3 表 save00.sav 行）。
        val bytes = minimalSave(
            variables = listOf(SaveEntry.IntValue("MONEY", 1)),
            uniqueCode = 7153,
            scriptVersion = 0,
        )
        val probe = SaveDataProbe.checkBytes(bytes, SaveFileKind.NORMAL, eratwGameBase())
        assertEquals(EraDataState.OK, probe.state, "实际：${probe.state} ${probe.mes}")
    }

    /**
     * GAMEBASE「バージョン」行的两种形态在版本闸下必须同判（组长接线补充，2026-11-01）。
     *
     * 原版对存档头 scriptVersion 的比较分支：`VariableEvaluator` 从存档头读出 target 后调
     * `gamebase.CheckVersion(target)`（文本 CHKDATA `VariableEvaluator.cs:2183-2190`、
     * 二进制 CHKDATA `:2209-2215`、LOADDATA 的 `LoadFromStreamBinary` `:2620-2623`），
     * 不过闸即 `VIRSION_ERROR`；分支本体在 GameBase.cs:69-80（第 1 条 `:71-74`、
     * 兼容下限 `:75-78`、版本相等比较 `:79`）。
     *
     * - 形态 A（键不存在）：`バージョン` 行整行缺失 ⇒ `ScriptVersionDefined` 保持 false、
     *   `ScriptVersion = 0`（GameBase.cs:23/25 的字段初值），走第 1 条放行；
     * - 形态 B（4.981 实况，`CSV/GAMEBASE.CSV:2` = `バージョン,proto`）：行**存在**但
     *   `tryatoi`（GameBase.cs:82-104）遇非数字即空手而归 ⇒ `ScriptVersionDefined = false`
     *   且 `out` 落 0（`GameBase.cs:139` 的赋值），同样走第 1 条放行。
     *
     * 两种形态在 [GameBaseData] 上的投影：A = version=0 + versionDefined 交 null
     * （CSV 装载层没交出该位 ⇒ [GameBaseGate.checkVersion] 退回 `version != 0` 的近似，
     * 0 ⇒ false）；B = version=0 + versionDefined=false（显式）。两者判等结果必须一致。
     */
    @Test
    fun `GAMEBASE バージョン解析失败落 0 与缺行同判`() {
        // 形态 A：键不存在（versionDefined 未交出，退回 version != 0 近似）。
        val missingRow = GameBaseData(gameCode = 7153, version = 0, versionDefined = null, allowVersion = 12)
        // 形态 B：键存在但值 proto，tryatoi（GameBase.cs:82-104）失败落 0（GameBase.cs:139）。
        val protoRow = eratwGameBase()
        for ((label, gb) in listOf("缺行" to missingRow, "proto 行" to protoRow)) {
            assertTrue(GameBaseGate.checkVersion(gb, 0), "$label：存档头 scriptVersion=0 放行")
            assertTrue(GameBaseGate.checkVersion(gb, 20_250_413), "$label：任意非 1000 target 放行（第 1 条先于 ：79 的相等比较）")
        }
        // 两形态对同一 target 判等一致 —— 版本闸层面「解析失败落 0」与「没写」不可区分。
        assertEquals(
            GameBaseGate.checkVersion(missingRow, 999),
            GameBaseGate.checkVersion(protoRow, 999),
            "缺行与 proto 行判等必须一致",
        )
    }

    // ------------------------------------------------------------------
    // 3. 缺失键填 0 / 空串（生产装载路径：复位 + applyTo）
    // ------------------------------------------------------------------

    @Test
    fun `缩微升版档装载后新增变量读回 0 与空串`() {
        // 档里只有 4.980 时代就存在的键（= 旧版存档的缩微形态，4.981 新增键全部缺失）。
        // MONEY/DAY 在注册表里是 1D 数组（原版系统变量口径），条目须用 IntArray1；
        // 标量 IntValue 对数组 def 会被按「类型/维度不符丢弃」跳过（SaveData.applyEntry）。
        val bytes = minimalSave(
            variables = listOf(
                SaveEntry.IntArray1("MONEY", idx(123, 0, 0, 0)),
                SaveEntry.IntArray1("DAY", idx(42)),
            ),
            character = listOf(
                SaveEntry.IntValue("NO", 1),
                SaveEntry.IntArray1("CFLAG", LongArray(4) { 9L }),
            ),
        )

        // 目标池声明 4.981 新增变量并预置「上一局残留值」：
        // load 的第一步是 resetValues（FileSystemOps.loadData 内，VariableEvaluator.cs:2625-2626 口径），
        // 缺失键不再被写 ⇒ 停在默认值 0 / 空串，与「未初始化」同值。
        val dst = VariableStore()
        dst.addCharacter(
            CharacterTemplate(no = 1, name = "アリス", callname = "アリス", maxbase = mapOf(0 to 10L)),
        )
        dst.declareUserVar(
            DimParser.parse("#DIM", "SAVEDATA 生活系统_存档版本", false, DimParseConfig(systemSaveInBinary = true)),
        )
        dst.declareUserVar(
            DimParser.parse("#DIMS", "SAVEDATA 孵化槽_蛋种", false, DimParseConfig(systemSaveInBinary = true)),
        )
        dst.declareUserVar(
            DimParser.parse("#DIM", "SAVEDATA 生活系统_蜂箱蜜, 8", false, DimParseConfig(systemSaveInBinary = true)),
        )
        dst.writeInt("生活系统_存档版本", idx(0), 5)
        dst.writeStr("孵化槽_蛋种", idx(0), "上一局的蛋")
        dst.writeInt("生活系统_蜂箱蜜", idx(3), 9)

        val io = MemorySaveIo()
        io.files["sav/save00.sav"] = bytes
        val cfg = SaveConfig(systemSaveInBinary = true, zipSaveData = false, useSaveFolder = true)
        FileSystemOps(dst, io, cfg).loadData(0)

        // 缺失键读回默认值（= 未初始化）。
        assertEquals(0L, dst.readInt("生活系统_存档版本", idx(0)), "新增标量缺失 ⇒ 0")
        assertEquals("", dst.readStr("孵化槽_蛋种", idx(0)), "新增字符串缺失 ⇒ 空串（null 落成空串口径）")
        assertEquals(0L, dst.readInt("生活系统_蜂箱蜜", idx(3)), "新增数组元素缺失 ⇒ 0")
        // 其余键照常生效（升版不是丢档）。
        assertEquals(123L, dst.readInt("MONEY", idx(0)), "MONEY")
        assertEquals(42L, dst.readInt("DAY", idx(0)), "DAY")
        assertEquals(9L, dst.readInt("CFLAG", idx(0, 3)), "CFLAG（角色 0 的第 3 元素）")
    }

    // ------------------------------------------------------------------
    // 4. GMF 位 + GMF_HOME_MAP + MAIN_MAP 三者自洽（升版语料 GET_HOME_MAP 语义前提）
    // ------------------------------------------------------------------

    /**
     * 复刻 4.981 升版语料 `MOVEMENTS/物件関連/COMMON2.ERB:90-98` 的 `@GET_HOME_MAP()`：
     * GMF 模式下取 `GMF_HOME_MAP`、否则取 `MAIN_MAP`；GMF 模式由 `GENSOKYO_MAP_FIX`
     * 的 bit0 决定（同 DLC 的 `@GMF_GETBIT`：`RETURNF GETBIT(GENSOKYO_MAP_FIX, 0)`，
     * `DLC/GENSOKYO_MAP_FIX/幻想乡地图重修.ERB:416-418`）。
     *
     * 这是**语料公式**对**引擎持久化面**的依赖，不是引擎内建语义：引擎只需保证三个
     * `#DIM SAVEDATA` 键各自按名往返（见本用例断言）；公式本体的正确性由语料版本负责。
     */
    private fun corpusGetHomeMap(store: VariableStore): Long {
        val gmfBit = store.readInt("GENSOKYO_MAP_FIX", idx(0)) and 1L
        return if (gmfBit != 0L) store.readInt("GMF_HOME_MAP", idx(0)) else store.readInt("MAIN_MAP", idx(0))
    }

    @Test
    fun `GMF 位与 GMF_HOME_MAP MAIN_MAP 三者随升版档往返并自洽`() {
        // 三个键都是 `#DIM SAVEDATA`（两版一致）：
        //   GENSOKYO_MAP_FIX  -> DLC/DLC.ERH:139   （GMF 位容器，bit0=幻想乡大地图）
        //   GMF_HOME_MAP      -> DLC/GENSOKYO_MAP_FIX/GENSOKYO_MAP_FIX.ERH:2
        //   MAIN_MAP          -> DIM.ERH:248（4.980 为 DIM.ERH:245）
        // 升版新增的 `@GET_HOME_MAP()`（4.981 138 处 / 4.980 0 处）在
        // COMMON2.ERB:94-98 按 GMF 位在二者间切换；判式 `@CHK_DATENOW` 由 4.980 的
        // `ARG == MAIN_MAP`（4.980 COMMON2.ERB:15）改为 `ARG == GET_HOME_MAP()`（4.981 :11）。
        // 引擎侧前提：三键必须各自独立往返（`applyEntry` 只写同名同型/同维声明，
        // SaveData.kt:268-289），否则对应模式下读到 0/旧值 ⇒ 判式误判。
        val bytes = minimalSave(
            variables = listOf(
                SaveEntry.IntArray1("GENSOKYO_MAP_FIX", idx(1)), // bit0=1：GMF 模式开
                SaveEntry.IntArray1("GMF_HOME_MAP", idx(7)), // GMF 下的家地图
                SaveEntry.IntArray1("MAIN_MAP", idx(3)), // 非 GMF 下的家地图（刻意与 GMF_HOME_MAP 不同）
            ),
        )

        val dst = VariableStore()
        dst.declareUserVar(
            DimParser.parse("#DIM", "SAVEDATA GENSOKYO_MAP_FIX", false, DimParseConfig(systemSaveInBinary = true)),
        )
        dst.declareUserVar(
            DimParser.parse("#DIM", "SAVEDATA GMF_HOME_MAP", false, DimParseConfig(systemSaveInBinary = true)),
        )
        dst.declareUserVar(
            DimParser.parse("#DIM", "SAVEDATA MAIN_MAP", false, DimParseConfig(systemSaveInBinary = true)),
        )
        // 预置残留值：装载后必须被档内值覆盖（不是保持旧值）。
        dst.writeInt("GENSOKYO_MAP_FIX", idx(0), 0L)
        dst.writeInt("GMF_HOME_MAP", idx(0), 99L)
        dst.writeInt("MAIN_MAP", idx(0), 99L)

        val io = MemorySaveIo()
        io.files["sav/save00.sav"] = bytes
        val cfg = SaveConfig(systemSaveInBinary = true, zipSaveData = false, useSaveFolder = true)
        FileSystemOps(dst, io, cfg).loadData(0)

        // 三键独立往返（覆盖残留值）。
        assertEquals(1L, dst.readInt("GENSOKYO_MAP_FIX", idx(0)), "GMF 位容器")
        assertEquals(7L, dst.readInt("GMF_HOME_MAP", idx(0)), "GMF 家地图")
        assertEquals(3L, dst.readInt("MAIN_MAP", idx(0)), "普通家地图")

        // 语料公式按位自洽：GMF 开 → GMF_HOME_MAP；GMF 关 → MAIN_MAP。
        assertEquals(7L, corpusGetHomeMap(dst), "GMF 开时 GET_HOME_MAP 取 GMF_HOME_MAP")
        dst.writeInt("GENSOKYO_MAP_FIX", idx(0), 0L)
        assertEquals(3L, corpusGetHomeMap(dst), "GMF 关时 GET_HOME_MAP 取 MAIN_MAP")
    }
}
