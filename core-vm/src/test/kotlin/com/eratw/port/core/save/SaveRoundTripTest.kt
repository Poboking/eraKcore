package com.eratw.port.core.save

import com.eratw.port.boot.CorpusProfile
import com.eratw.port.core.data.csv.GameData
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableCode
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 存档**写路径**的往返验收：真实 PC 存档（`ERATW_SAVE_SAMPLES` 指向的只读样本目录）
 * 读 → 写 → 再读，逐字段对拍。公开仓不含样本时按适配口径 skip。
 *
 * ## 本文件钉住的三层
 *
 * 1. **编码层**（`core/data/save` 的 `SaveFormat`，由本路调用而不是修改）：
 *    `read` → 重建同内容 `SaveData`（丢掉 `sourceFile` 原样回写捷径）→ `write` → `read`，
 *    六份样本的**每个条目**必须逐项相同，且 payload 逐字节等于原文件 payload。
 *    这条是 [SaveCodec] 写分支的证据：一旦 `0xF0/0xE0/0xE1/0xF1/0xF2` 游程编码或
 *    7-bit 字符串前缀错一位，读回就会漂。
 * 2. **容器层**：重新压缩的分支必须是 `.NET 8 GZipStream` 同壳 ——
 *    魔数 `743174590507926921`（EraBinaryDataWriter.cs:39）、版本 1808（:45）、
 *    校验表条数恒 0（:46）、GZip 头 `1f 8b 08 00 00000000 00 0a`（实测），
 *    尾部 CRC32 + ISIZE 自洽。.NET 侧只要求「能解压」（spec/save-format.md §6.3），
 *    故不要求 deflate 字节与 .NET 一致。
 * 3. **装配层**（`core/save` 目录）：真实存档 → 变量池（CSV 注册表 + ERH 全局 `#DIM`）→
 *    `FileSystemOps.saveData` → 新变量池，系统/角色/全局字段逐项一致，
 *    写出的文件能被现有探测路径（[SaveDataProbe.checkBytes]）判 OK。
 *
 * ## 哪些字段**还不能**写（本路实测，详见交付报告）
 *
 * - **ERH 全局 `#DIM SAVEDATA` 未注册时整批丢失**：写侧筛选只认 `VarDef.isSavedata`
 *   （`SaveCapture` 类 KDoc；spec VariableData.cs:3980-3997），没走过
 *   `SystemVariableBootstrap.install` 的变量池里没有这些变量 ⇒ 只有 63 个系统条目可写
 *   （真档 540 条）。本文件的变量池用例因此**先**装 ERH 声明，与 `GameSession` 第 4 步同源。
 * - **`NICKNAME` / `MASTERNAME` 只在「被赋过值」的角色块里落条目**：原版靠
 *   `EraBinaryDataWriter.WriteWithKey(string, object)` 的 `v is T` 分派、`null` 时整条不落
 *   （EraBinaryDataWriter.cs:83-173），移植侧按值等价收窄在 `data/save/CharaFileIO` 的
 *   `isNullInOriginal`（总账 SAVE-04 收口，B5 L4）；见 [额外角色字段]。
 * - **Map/Xml/DT 扩展段**要求 `FileSystemOps` 拿到 `EmSaveSets`（`VarExt*.csv`）；
 *   未装配 = 空表 = 不写（`ConstantData.cs:1311-1316`）。本文件装 `ERATW_GAMEDIR/CSV` 的真表。
 *
 * ## 语料根两列（savecorpus 波，2026-11-06）
 *
 * 本文件的样本档是 **4.980 时代**的真实 PC 存档（`ERATW_SAVE_SAMPLES` 指向的目录），
 * 而「写回时池面按哪一版注册」由 `ERATW_GAMEDIR` 指向的语料根决定：
 * 语料根是 4.980 时样本与池同代 ⇒ 逐项全等；是 4.981 时池按新增 `SAVEDATA` 面重写样本 ⇒
 * 出现对账清单里的差/缺/多。故凡**随池面走**的读数一律 `when (CorpusProfile)` 取列
 * （见 [CorpusColumns]），不再写死 4.981 一列 —— 两个根都必须是绿的。
 * 随样本字节走的读数（540 条全局条目、25 条全局档条目、168 个角色块等）两根同值，保留单常量并注明。
 */
class SaveRoundTripTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /**
     * 真存档样本目录：公开仓只认 `ERATW_SAVE_SAMPLES`（`-PsaveSamples` 由
     * `core-vm/build.gradle.kts` 导出）。不携带任何内部存档备份路径；没有显式来源时按
     * 公开适配口径跳过（上游是硬判红）。
     */
    private fun samplesDir(): Path {
        val fromEnv = System.getenv("ERATW_SAVE_SAMPLES")?.trim()?.takeIf { it.isNotEmpty() }
            ?: run {
                // 公开仓不含内部存档样本：按公开适配口径跳过。
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "公开仓无 ERATW_SAVE_SAMPLES：跳过真存档样本判据")
                throw AssertionError("unreachable")
            }
        val p = Path.of(fromEnv)
        if (Files.isDirectory(p)) return p
        throw AssertionError("ERATW_SAVE_SAMPLES=$fromEnv 不是目录：真存档样本判据无根可对")
    }

    /**
     * 游戏根：`ERATW_GAMEDIR` 显式给出即权威；回落 `ERATW_CORPUS`（指到 ERB 子树）的父目录。
     *
     * 两根都没设 ⇒ **判红**（口径同 `core/config/ConfigChainTest` 的 `corpusRoot`）：
     * 语料根是本族判据的取列依据，没有根就没有可对的读数。
     */
    private fun gameDir(): Path {
        System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        val corpus = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() }
            ?: run {
                // 公开仓无外部语料/样本：按公开适配口径跳过（上游为硬判红）。
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "公开仓无 ERATW_GAMEDIR / ERATW_CORPUS：跳过存档写回语料根")
                throw AssertionError("unreachable")
            }
        return Path.of(corpus).parent
    }

    /** 语料版本档：`ERB/DIM.ERH` 的 `#DIM CONST NOW_VERSION`（判不了 / 不在册 ⇒ 自己抛 AssertionError）。 */
    private fun profile(): CorpusProfile = CorpusProfile.detect(gameDir())

    /** 本轮的「随池面走」读数（见 [CorpusColumns]）；只在真的要写回时才判根。 */
    private val columns: CorpusColumns by lazy { columnsOf(profile()) }

    private fun sample(name: String): ByteArray {
        val f = samplesDir().resolve(name)
        if (!Files.isRegularFile(f)) throw AssertionError("存档样本 $name 不在位：$f")
        return Files.readAllBytes(f)
    }

    private fun store(): VariableStore = VariableStore()

    private fun VariableStore.addHero(name: String = "アリス"): Int = addCharacter(
        CharacterTemplate(
            no = 1, name = name, callname = name, maxbase = mapOf(0 to 10L),
        ),
    )

    private fun idx(vararg v: Long): LongArray = v

    /** 内存 [SaveFileIo]（路径键与生产实现同为相对路径）。 */
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

    /**
     * 条目签名：把条目压成一个可比字符串。int 数组**保留维度与尾零**（写读两侧同形），
     * 字符串数组把 null 归一成空串（写侧 null 与 "" 等同，EraBinaryDataWriter.cs:352）。
     */
    private fun sig(e: SaveEntry): String = when (e) {
        is SaveEntry.IntValue -> "I:${e.value}"
        is SaveEntry.IntArray1 -> "I1:" + e.values.joinToString(",")
        is SaveEntry.IntArray2 -> "I2:${e.dim0}x${e.dim1}:" + e.values.joinToString(",")
        is SaveEntry.IntArray3 -> "I3:${e.dim0}x${e.dim1}x${e.dim2}:" + e.values.joinToString(",")
        // 浮点按原始位签名（[Double.toList] 的十进制打印会把 `-0.0`/`0.0` 与不同尾数的 NaN 混成同一条）
        is SaveEntry.FloatValue -> "F:" + e.value.toRawBits()
        is SaveEntry.FloatArray1 -> "F1:" + e.values.map { it.toRawBits() }.joinToString(",")
        is SaveEntry.FloatArray2 -> "F2:${e.dim0}x${e.dim1}:" + e.values.map { it.toRawBits() }.joinToString(",")
        is SaveEntry.FloatArray3 ->
            "F3:${e.dim0}x${e.dim1}x${e.dim2}:" + e.values.map { it.toRawBits() }.joinToString(",")
        is SaveEntry.StrValue -> "S:${e.value}"
        is SaveEntry.StrArray1 -> "S1:" + e.values.joinToString(",") { it ?: "" }
        is SaveEntry.StrArray2 -> "S2:${e.dim0}x${e.dim1}:" + e.values.joinToString(",") { it ?: "" }
        is SaveEntry.StrArray3 -> "S3:${e.dim0}x${e.dim1}x${e.dim2}:" + e.values.joinToString(",") { it ?: "" }
        is SaveEntry.MapValue -> "M:" + e.entries.joinToString(",") { "${it.first}=${it.second}" }
        is SaveEntry.XmlValue -> "X:${e.xml}"
        is SaveEntry.DataTableValue -> "D:${e.schemaXml}\u0000${e.dataXml}"
        SaveEntry.Separator -> "SEP"
    }

    private fun byKey(entries: List<SaveEntry>): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (e in entries) if (e !is SaveEntry.Separator) m[e.key] = sig(e)
        return m
    }

    /** 两个 blocks 的键 → 值差异（`orig` 有而 `back` 无/不同 ⇒ 「缺」「差」；反之「多」）。 */
    private fun diffBlocks(label: String, orig: List<SaveEntry>, back: List<SaveEntry>): List<String> {
        val out = ArrayList<String>()
        val a = byKey(orig)
        val b = byKey(back)
        for ((k, v) in a) {
            val w = b[k]
            when {
                w == null -> out.add("$label 缺 $k")
                w != v -> out.add("$label 差 $k：${v.take(60)} ≠ ${w.take(60)}")
            }
        }
        for (k in b.keys) if (!a.containsKey(k)) out.add("$label 多 $k")
        return out
    }

    /** 只查「原档有、写回没有/不同」的方向（写侧允许产出原档没有的已知变量，见 [额外角色字段]）。 */
    private fun missingOnly(label: String, orig: List<SaveEntry>, back: List<SaveEntry>): List<String> {
        val out = ArrayList<String>()
        val b = byKey(back)
        for ((k, v) in byKey(orig)) {
            val w = b[k]
            when {
                w == null -> out.add("$label 缺 $k")
                w != v -> out.add("$label 差 $k")
            }
        }
        return out
    }

    /** diffBlocks 行（`<label> <缺|差> <key>…`）归一成 `<缺|差> <key>`，剥掉 label。 */
    private fun driftKey(diff: String): String {
        val head = diff.substringBefore('：')
        val m = Regex("(缺|差|多) (.+)$").find(head) ?: return head
        return "${m.groupValues[1]} ${m.groupValues[2]}"
    }

    /**
     * 4.981 语料升版后，老档（4.980 时代）经 4.981 变量池写回被改形/丢弃的**对账清单**
     * （= [CorpusColumns.globalOrigSideDrift] 的 4.981 列；4.980 列是空集，见 [columnsOf]）。
     * 每条都有语料出处，全部是「池按 4.981 注册面重写」的预期行为、不是引擎回归：
     * - `差 ITEM / NOITEM / ITEMSALES`：4.981 VariableSize.csv:17-19 把三者 1000→1200 ⇒ 数组装长；
     * - `差 PLANT_TYPE / PLANT_DAYS / PLANT_GROW / PLANT_DRY / PLANT_FOOD / HATAKE_NAME`：
     *   4.980 DIM.ERH:251 `PLANT_TYPE, 5` → 4.981 由 `DLC/生活系统大修/0_生活系统_变量.ERH:90`
     *   起接管（`PLANT_TYPE, 240` 等），种田面数组扩容；
     * - `缺 P_AdjList / P_AdjListDone`：4.980 `魔改内容/qol/qol_skipmove.ERH:2/:4` 的 SAVEDATA
     *   全局声明在 4.981 整文件移除 ⇒ 池里不再注册、写出自然没有（原版 varTokenDic 同口径）；
     * - `缺 MP_GRADE`：4.980 `DLC/魔法DLC/魔法相关参数.ERH:20` 是 GLOBAL SAVEDATA，
     *   4.981 `:23` 改成 CHARADATA ⇒ 全局块不再有它；
     * - `差 BIRD_CAGE / BIRD_NAME / EGG_SPAWN / EGG_KIND / EGG_STOCK / BIRD_COND /
     *   BIRD_FAVORITE / BIRD_ID`：4.981 `コマンド関連/COMF/日常系/EGGFARM.ERH:7` 的
     *   EGGFARM_MAXCAGE 3→8 ⇒ 养鸡面数组 3→8；
     * - `差 GLOBAL_BONUS`：4.980 DIM.ERH:765 12 格 → 4.981 `:883` 16 格；
     * - `差 NEW_SAVE_MARK`：4.980 魔改内容/MODDING_VALUE.ERH:8 1001 格 → 4.981 `:5` 1002 格。
     */
    private val SAVE_CORPUS_DRIFT: Set<String> = setOf(
        "差 ITEM", "差 NOITEM", "差 ITEMSALES",
        "差 PLANT_TYPE", "差 PLANT_DAYS", "差 PLANT_GROW", "差 PLANT_DRY", "差 PLANT_FOOD",
        "差 HATAKE_NAME",
        "缺 P_AdjList", "缺 P_AdjListDone", "缺 MP_GRADE",
        "差 BIRD_CAGE", "差 BIRD_NAME", "差 EGG_SPAWN", "差 EGG_KIND", "差 EGG_STOCK",
        "差 BIRD_COND", "差 BIRD_FAVORITE", "差 BIRD_ID",
        "差 GLOBAL_BONUS", "差 NEW_SAVE_MARK",
    )

    /**
     * 角色面的同源对账清单：
     * - `差 BUFF`：4.980 DIM.ERH:453 `CHARADATA SAVEDATA BUFF, 16` → 4.981 `:571` 50 格；
     * - `缺 cTempRawPerm`：4.980 `魔改内容/AnonTW搬运口上标识符库.ERH:6` 的 CHARADATA SAVEDATA
     *   在 4.981 整文件移除。
     */
    private val CHARA_CORPUS_DRIFT: Set<String> = setOf(
        "差 BUFF", "缺 cTempRawPerm",
    )

    /**
     * 4.981 新注册、经 4.981 池面写回 4.980 时代样本档时允许多出的角色字段（9 个）。
     * 全部取证为 4.981 新增 `CHARADATA SAVEDATA`（4.980 语料全库无此声明）：
     * - `MP_GRADE`：4.980 `DLC/魔法DLC/魔法相关参数.ERH:20` 为 `#DIM SAVEDATA MP_GRADE`（全局），
     *   4.981 `:23` 改 `#DIM SAVEDATA CHARADATA MP_GRADE`（转角色级）；
     * - `M_TALENT`：4.981 同文件 `:17` 新增 `#DIM SAVEDATA CHARADATA M_TALENT,22`；
     * - `Setting_CFLAG`/`Setting_TCVAR`：4.981 `DLC/实用设置补丁/实用设置.ERH:13-14` 新增；
     * - `EVENT_CLOTHING_SET_STACK/SOURCE/PARAM`：4.981 `fromEN/QOL/QoL_Misc.ERH:75-77` 新增；
     * - `CUSTOM_DAILY_OUTFIT`：4.981 `fromEN/QOL/QoL_Misc.ERH` 新增（4.980 全库 grep 无）；
     * - `SEMEN_MATRIX`：4.981 `魔改内容/SEMEN_MATRIX.ERH:7` 新增。
     * 与全局面「多 KOJO_PRESET / KOJO_PRESET_NAME」同机制：写侧按池面落已注册变量的格，
     * 原版样本档（4.980 时代）没有这些格子。
     */
    private val EXTRA_CHARA_FIELDS_981: Set<String> = setOf(
        "Setting_CFLAG", "Setting_TCVAR", "M_TALENT", "MP_GRADE",
        "EVENT_CLOTHING_SET_STACK", "EVENT_CLOTHING_SET_SOURCE", "EVENT_CLOTHING_SET_PARAM",
        "CUSTOM_DAILY_OUTFIT", "SEMEN_MATRIX",
    )

    /**
     * 「样本档（4.980 时代）经**本轮语料根**的变量池写回」这一动作的期望读数，按根一列。
     *
     * 为什么必须两列：样本字节不随语料根变（仓内备份 6 个 `.sav` 全部 git 跟踪），
     * 但写回时池的注册面随 `ERATW_GAMEDIR` 变 —— 4.980 根下样本与池同代 ⇒ 逐项全等；
     * 4.981 根下池多了/改了一批 `SAVEDATA` 声明 ⇒ 出现对账清单里的差/缺/多。
     * 旧版把这些读数写死成 4.981 一列，闸口径（AGENTS.md §5）指向 4.980 时恒红。
     */
    private class CorpusColumns(
        /** 写回后全局块的条目数（样本基线 540 条）。 */
        val writtenGlobalCount: Int,
        /** 原档侧允许出现的「缺/差」（空集 = 逐项全等）。 */
        val globalOrigSideDrift: Set<String>,
        /** 角色块原档侧允许出现的「缺/差」（空集 = 逐项全等）。 */
        val charaOrigSideDrift: Set<String>,
        /** 写出面允许**多出**的角色字段。 */
        val extraCharaFields: Set<String>,
        /** `global.sav` 回写的差异行（`全局 <缺|差|多> <key>`，不含「：」之后的取值）。 */
        val globalSaveDiffLines: Set<String>,
        /** `GLOBAL_BONUS` 写回后尾部的零格数（0 = 数组长度不变）。 */
        val globalBonusTailZeros: Int,
        /** 断言消息里的口径说明。 */
        val note: String,
    )

    /**
     * 两列读数。4.980 列 = 本路 2026-11-06 在 `ERATW_GAMEDIR=…/eratw-画蛇添足版eratw4.980`
     * 下**实读**（写回 save00.sav：`back.variables.size`=540、`diffBlocks` 原档侧空、
     * 角色块差异空、额外角色字段空；写回 global.sav：25→25 无差异、`GLOBAL_BONUS` 12→12）；
     * 4.981 列 = 合流头实测的原钉值（本路未改读数）。
     */
    private fun columnsOf(profile: CorpusProfile): CorpusColumns = when (profile) {
        // 4.980 列：样本档与池同代 ⇒ 写回逐项全等。实读出处见本函数 KDoc。
        CorpusProfile.V4_980 -> CorpusColumns(
            writtenGlobalCount = 540,
            globalOrigSideDrift = emptySet(),
            charaOrigSideDrift = emptySet(),
            extraCharaFields = emptySet(),
            globalSaveDiffLines = emptySet(),
            globalBonusTailZeros = 0,
            note = "4.980 池面 = 样本同代，写回应逐项全等（2026-11-06 实读）",
        )
        // 4.981 列：池按新增 SAVEDATA 面重写样本 ⇒ 下表逐条对账（合流头实测）。
        CorpusProfile.V4_981 -> CorpusColumns(
            writtenGlobalCount = 749,
            globalOrigSideDrift = SAVE_CORPUS_DRIFT,
            charaOrigSideDrift = CHARA_CORPUS_DRIFT,
            extraCharaFields = EXTRA_CHARA_FIELDS_981,
            globalSaveDiffLines = setOf(
                "全局 差 GLOBAL_BONUS",
                "全局 差 NEW_SAVE_MARK",
                "全局 多 KOJO_PRESET",
                "全局 多 KOJO_PRESET_NAME",
            ),
            globalBonusTailZeros = 4,
            note = "4.981 池面把 4.980 时代样本按新注册面重写（对账清单见 SAVE_CORPUS_DRIFT 等）",
        )
    }

    /** 整帧差异（头 + 角色块 + 全局块）。 */
    private fun diffFrame(orig: SaveData, back: SaveData, withEm: Boolean = true): List<String> {
        val out = ArrayList<String>()
        if (orig.fileType != back.fileType) out.add("类别 ${orig.fileType} ≠ ${back.fileType}")
        if (orig.uniqueCode != back.uniqueCode) out.add("UniqueCode ${orig.uniqueCode} ≠ ${back.uniqueCode}")
        if (orig.scriptVersion != back.scriptVersion) {
            out.add("ScriptVersion ${orig.scriptVersion} ≠ ${back.scriptVersion}")
        }
        if (orig.text != back.text) out.add("描述「${orig.text}」≠「${back.text}」")
        if (orig.characters.size != back.characters.size) {
            out.add("角色数 ${orig.characters.size} ≠ ${back.characters.size}")
        }
        for (i in 0 until minOf(orig.characters.size, back.characters.size)) {
            out += diffBlocks("角色$i", orig.characters[i], back.characters[i])
        }
        out += diffBlocks("全局", orig.variables, back.variables)
        if (withEm) out += diffBlocks("EM", orig.emData, back.emData)
        return out
    }

    /** 重建同内容的 [SaveData]（不带 `sourceFile` ⇒ 强制走编码器而不是原样回写）。 */
    private fun rebuild(d: SaveData): SaveData = SaveData(
        fileType = d.fileType,
        uniqueCode = d.uniqueCode,
        scriptVersion = d.scriptVersion,
        text = d.text,
        characters = d.characters,
        variables = d.variables,
        emData = d.emData,
        compressed = d.compressed,
        version = d.version,
    )

    /**
     * UTF-16LE 字节级串搜索（不经过 `decode`）：
     * 二进制 payload 含随机字节，用「解码成字符串再正则」会被代理对/奇数字节干扰，
     * 故直接按 `char` 低位在前逐字节比较。仅用于「样本里有没有这个变量名」这类存在性判定。
     */
    private fun countUtf16(bytes: ByteArray, s: String): Int {
        if (s.isEmpty()) return 0
        val pat = ByteArray(s.length * 2)
        for (i in s.indices) {
            pat[2 * i] = (s[i].code and 0xFF).toByte()
            pat[2 * i + 1] = ((s[i].code ushr 8) and 0xFF).toByte()
        }
        var n = 0
        var i = 0
        while (i + pat.size <= bytes.size) {
            var hit = true
            for (j in pat.indices) {
                if (bytes[i + j] != pat[j]) {
                    hit = false
                    break
                }
            }
            if (hit) n++
            i++
        }
        return n
    }

    private fun readU32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    // ==================================================================
    // 1. 编码层：真实存档 read → （强制重编码）→ read
    // ==================================================================

    /**
     * 六份真实样本（5 个 Normal + 1 个 Global）读→写→再读，**每个条目逐项相同**。
     *
     * 判据不是「字节相等」而是「字段相等」：重编码分支必然换一套 deflate 字节
     * （spec §6.3 实测 .NET 510 B vs zlib 508 B），故这里只要求 payload 相等 + 读回逐项相等。
     * 条目覆盖：168 个角色块 × 27 系统条目 + 用户角色变量、540 条全局条目、2 个 DataTable。
     */
    @Test
    fun `真实存档重编码后逐项一致`() {
        val names = listOf("save00.sav", "save01.sav", "save02.sav", "save03.sav", "save1000.sav", "global.sav")

        val diffs = ArrayList<String>()
        for (name in names) {
            val src = sample(name)
            val orig = SaveFormat.read(src)
            val rebuilt = rebuild(orig)
            val payloadOrig = SaveFormat.sourcePayloadOf(src)
            assertNotNull(payloadOrig, "$name: 应有 GZip 容器")

            // 编码器对真实数据逐字节同构（写侧 RLE / 7-bit 前缀 / 类型字节的证据）
            val payloadNew = SaveFormat.encodePayload(rebuilt)
            if (!payloadNew.contentEquals(payloadOrig)) {
                diffs.add("$name: payload 重编码不等（${payloadOrig.size} vs ${payloadNew.size}）")
                continue
            }

            val written = SaveFormat.write(rebuilt)
            if (written.contentEquals(src)) {
                diffs.add("$name: 重编码分支不应退回原文件字节（sourceFile 已剥掉）")
            }
            val back = SaveFormat.read(written)
            diffs += diffFrame(orig, back).map { "$name: $it" }
        }
        assertTrue(diffs.isEmpty(), "真实存档写读不一致：\n" + diffs.take(30).joinToString("\n"))
    }

    /** 未改动存档：`SaveFormat.write` 直接回写原字节（spec §6.3 的 6/6 幂等机制）。 */
    @Test
    fun `未改动存档写回原字节`() {
        val src = sample("save00.sav")
        val back = SaveFormat.write(SaveFormat.read(src))
        assertTrue(back.contentEquals(src), "未改动档必须原样回写（SaveFormat.write 的 sourceFile 分支）")
    }

    // ==================================================================
    // 2. 容器层
    // ==================================================================

    /**
     * 重新压缩的容器必须与 `.NET 8 GZipStream` 同壳：
     * 魔数/版本/校验表（EraBinaryDataWriter.cs:37-50）+ GZip 头（spec §6.1 实测）
     * + CRC32/ISIZE（GZip 尾，spec §6.1 第二条）。
     */
    @Test
    fun `重新压缩的容器与 GZip 同壳`() {
        val src = sample("save00.sav")
        val written = SaveFormat.write(rebuild(SaveFormat.read(src)))

        var magic = 0L
        for (i in 0 until 8) magic = magic or ((written[i].toLong() and 0xFF) shl (8 * i))
        assertEquals(SaveFormat.MAGIC_GZIP, magic, "GZip 分支魔数（EraBinaryDataWriter.cs:39）")
        assertEquals(1808L, readU32(written, 8), "版本号恒 1808（:45）")
        assertEquals(0L, readU32(written, 12), "校验表条数恒 0（:46，无有效校验和）")

        val gzipHead = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0, 0, 0, 0, 0, 0x0a)
        assertTrue(
            written.copyOfRange(16, 26).contentEquals(gzipHead),
            "GZip 头应为 1f 8b 08 00 00000000 00 0a（MTIME=0 / XFL=0 / OS=0x0A，spec §6.1）",
        )

        val payload = SaveFormat.sourcePayloadOf(written)
        assertNotNull(payload, "写出的文件应能被同一读路径解出 payload")
        val crc = CRC32().apply { update(payload) }.value
        assertEquals(crc, readU32(written, written.size - 8), "GZip 尾 CRC32 应覆盖 payload")
        assertEquals(
            payload.size.toLong() and 0xFFFF_FFFFL,
            readU32(written, written.size - 4),
            "GZip 尾 ISIZE = payload 长度",
        )
    }

    // ==================================================================
    // 3. 装配层：真实存档 → 变量池 → 写盘 → 另一个变量池
    // ==================================================================

    /**
     * 真实档经 [FileSystemOps.saveData] 落盘，再读回另一个变量池：系统与角色字段逐项一致。
     *
     * 变量池的注册面与 [com.eratw.port.core.proc.GameSession] 同源：
     * `GameDataLoader`（CSV）+ `SystemVariableBootstrap.install`（ERH 全局 `#DIM`）。
     * 少了后者就只有 63 条系统变量可写（真档 540 条），本用例会把差额暴露成「缺」。
     */
    @Test
    fun `真实存档经变量池写盘后逐项一致`(@TempDir tmp: Path) {
        val src = sample("save00.sav")
        val game = gameDir()
        val csv = game.resolve("CSV")
        val data = GameDataLoader.load(csv)
            ?: throw AssertionError("$csv 装不出 CSV 注册表：语料根不完整（ERATW_GAMEDIR=$game），无法建真实变量池")
        val cols = columns

        val root = Files.createDirectories(tmp.resolve("game"))
        val sav = Files.createDirectories(root.resolve("sav"))
        Files.write(sav.resolve("save00.sav"), src)
        val sets = EmSaveSets.parse(csv)
        val cfg = SaveConfig.ERATW

        val store = realStore(data)
        FileSystemOps(store, RootedSaveFileIo(root), cfg, sets).loadData(0)
        assertEquals(168, store.characters.count, "样本实测角色数 168（spec §6.2）")

        val orig = SaveFormat.read(src)
        val text = orig.text
        assertTrue(FileSystemOps(store, RootedSaveFileIo(root), cfg, sets).saveData(20, text), "SAVEDATA 应成功")

        val written = Files.readAllBytes(sav.resolve("save20.sav"))
        assertEquals(1808L, readU32(written, 8), "写出的档仍是 1808 容器")
        val back = SaveFormat.read(written)
        assertEquals(orig.characters.size, back.characters.size, "角色数")
        // 两根同值，实读：样本档自带 540 条全局条目（与语料根无关，样本是同一份字节）
        assertEquals(540, orig.variables.size, "样本（4.980 时代档）实测全局条目数 540")
        assertEquals(cols.writtenGlobalCount, back.variables.size, "写回后的全局条目数（${cols.note}）")

        // 全局块：原档的 540 条要么原样写回、要么命中本根的语料升版对账清单（见 [columnsOf]）；
        // 写出面多出的条目是本根新注册的全局变量，属语料升版的预期增量，不算差异。
        val globalDiffs = diffBlocks("全局", orig.variables, back.variables)
        val origSide = globalDiffs.map { driftKey(it) }.filter { !it.startsWith("多 ") }
        assertTrue(
            origSide.all { it in cols.globalOrigSideDrift },
            "全局块原档侧出现对账清单之外的差异（${cols.note}）：\n" + globalDiffs.joinToString("\n"),
        )
        assertEquals("RANDDATA", orig.variables.first { it.key == "RANDDATA" }.key, "真档全局块含 RANDDATA")

        // 角色块：原档的每个条目都必须原样写回（角色面语料漂移见 CHARA_CORPUS_DRIFT）
        val charDiffs = ArrayList<String>()
        for (i in 0 until orig.characters.size) {
            val o = byKey(orig.characters[i])
            val b = byKey(back.characters[i])
            for ((k, v) in o) {
                val w = b[k]
                if (w == null) charDiffs.add("角色$i 缺 $k") else if (w != v) charDiffs.add("角色$i 差 $k")
            }
        }
        assertTrue(
            charDiffs.map { driftKey(it) }.all { it in cols.charaOrigSideDrift },
            "角色块出现对账清单之外的丢字段/改值（${cols.note}）：\n" + charDiffs.distinct().joinToString("\n"),
        )

        // 不该再多出任何角色字段——除本根新注册的 CHARADATA 变量（4.981 是 9 个，见 EXTRA_CHARA_FIELDS_981）：
        // `NICKNAME`/`MASTERNAME` 为空的格原版整条不落（总账 SAVE-04）
        val extra = LinkedHashSet<String>()
        for (i in 0 until orig.characters.size) {
            val o = byKey(orig.characters[i])
            for (k in byKey(back.characters[i]).keys) if (!o.containsKey(k)) extra.add(k)
        }
        assertEquals(
            emptySet<String>(),
            extra - cols.extraCharaFields,
            "额外角色字段应为空（原版按值省略 null 格，见 [额外角色字段]；${cols.note}）",
        )

        // 描述原样（SAVEDATA_TEXT 是写侧入参，Process.cs:2872）
        assertEquals(text, back.text, "描述文本")

        // 写出的档必须被现有探测路径判 OK（类别 / 游戏码 / 版本三重校验）
        val probe = SaveDataProbe.checkBytes(written, SaveFileKind.NORMAL, GameBaseData(gameCode = 7153L))
        assertEquals(EraDataState.OK, probe.state, "写出的 save20.sav 应被 CHKDATA 判 OK：${probe.mes}")
        assertEquals(text, probe.mes, "探测到的描述应等于写入的描述")
    }

    /**
     * 额外角色字段 `NICKNAME` / `MASTERNAME`（总账 SAVE-04，B5 L4 已收口写侧）。
     *
     * 现象曾为「本仓写出的每个角色块比 PC 样本多这两条」。**归因（2026-10-23 更正，此前写的
     * 「实机走 `VarCodeInt < 2` 那一支」是错的）**：
     * - `isSavedata` 两侧都判真：`NICKNAME = 286523394`、`MASTERNAME = 286523395` 都带
     *   `__SAVE_EXTENDED__`，`VariableToken` 构造第一支即置 true（C# `VariableToken.cs:88-96`；
     *   码值 `VariableCode.cs:17`/`:132-133`；移植侧 `core/vars/VariableCode.kt:259-261` 同规则）
     *   ⇒ 注册表没标错，下面那条现状断言仍然成立；
     * - `CharacterData.SaveToStreamBinary` 也确实把这两格交给了 writer（`CharacterData.cs:515-529`）；
     * - 真正的排除点在 writer：`WriteWithKey(string, object)` 全程按 `v is T` 运行时类型分派且
     *   **末尾没有 else**（`EraBinaryDataWriter.cs:83-173`），`dataString` 是 `new string[4]`
     *   （`CharacterData.cs:25`/`:58`），模板的 `Nickname`/`Mastername` 只在 CSV 出现
     *   `あだ名`/`主人の呼び方` 时才被赋值（`CharacterTemplate.cs:17`/`:19`；`ConstantData.cs:1591-1598`），
     *   ADDCHARA 原样拷进第 3/4 格（`CharacterData.cs:134-135`）⇒ **没赋过值就是 null ⇒ 整条不落**。
     *   真档实测（`sav/save00.sav` 逐条目解析，168 块）：分隔符前恒 27 条系统条目、无 `NICKNAME`；
     *   `MASTERNAME` 只在块 26/61 出现（值 = 昴）。
     *
     * 收口落点：`core/data/save/CharaFileIO` 的 `isNullInOriginal`（按「值为空串」判原版的 null ——
     * 移植侧字符串走驻留池、下标 0 恒为空串，没有 null 位可查，这是**已知的存档结构侧偏离**，
     * 不丢信息：省掉的空条目读回仍是池 0）。残留：`NAME`/`CALLNAME` 取**字面空串**时原版会落条目、
     * 移植侧无法与「未赋值」区分（`TODO(verify)`，真档 168/168 块名字非空，无可参照样本）。
     */
    @Test
    fun `额外角色字段`() {
        val src = sample("save00.sav")
        val payload = SaveFormat.sourcePayloadOf(src)
        assertNotNull(payload)
        assertEquals(0, countUtf16(payload, "NICKNAME"), "PC 样本 payload 里不出现 NICKNAME（UTF-16LE 字节搜索）")
        // 走同一条判定入口，钉住「本仓认为它进档」这一现状（上修则会红，提醒同步本用例）
        assertTrue(
            VariableCode.isSavedata(286523394, "NICKNAME"),
            "现状：isSavedata(NICKNAME)=true（VariableCode.kt:259-261 / C# VariableToken.cs:93-96）",
        )
    }

    /** `global.sav`（25 条全局条目，无角色块）经变量池写盘后逐项一致。 */
    @Test
    fun `真实全局档经变量池写盘后逐项一致`(@TempDir tmp: Path) {
        val src = sample("global.sav")
        val game = gameDir()
        val csv = game.resolve("CSV")
        val data = GameDataLoader.load(csv)
            ?: throw AssertionError("$csv 装不出 CSV 注册表：语料根不完整（ERATW_GAMEDIR=$game）")
        val cols = columns

        val root = Files.createDirectories(tmp.resolve("game"))
        Files.createDirectories(root.resolve("sav"))
        Files.write(root.resolve("sav/global.sav"), src)
        val sets = EmSaveSets.parse(csv)
        val cfg = SaveConfig.ERATW

        val store = realStore(data)
        assertTrue(FileSystemOps(store, RootedSaveFileIo(root), cfg, sets).loadGlobal(), "LOADGLOBAL 应成功")
        FileSystemOps(store, RootedSaveFileIo(root), cfg, sets).saveGlobal()

        val written = Files.readAllBytes(root.resolve("sav/global.sav"))
        val orig = SaveFormat.read(src)
        val back = SaveFormat.read(written)
        assertEquals(SaveFileKind.GLOBAL, back.fileType, "类别（EraSaveFileType.Global）")
        // 两根同值，实读：样本档自带 25 条全局条目（与语料根无关）
        assertEquals(25, orig.variables.size, "样本实测全局条目数 25")
        // 本根池面写回样本的差异逐条对账（4.981 列是 4 处语料升版项，4.980 列是空集）：
        // - GLOBAL_BONUS 12→16 格（4.980 DIM.ERH:765 → 4.981 DIM.ERH:883，前 12 格原值 + 4 个 0）；
        // - NEW_SAVE_MARK 1001→1002 格（4.980 MODDING_VALUE.ERH:8 → 4.981 :5）⇒ 数组长一位；
        // - 多 KOJO_PRESET / KOJO_PRESET_NAME（4.981 DLC/实用设置补丁/实用设置.ERH:30-31 新注册）。
        val diffs = diffBlocks("全局", orig.variables, back.variables)
        assertEquals(
            cols.globalSaveDiffLines,
            diffs.map { it.substringBefore('：') }.toSet(),
            "全局档差异须恰为本根的对账项（${cols.note}）：\n" + diffs.joinToString("\n"),
        )
        val bonusOrig = (orig.variables.first { it.key == "GLOBAL_BONUS" } as SaveEntry.IntArray1).values.toList()
        val bonusBack = (back.variables.first { it.key == "GLOBAL_BONUS" } as SaveEntry.IntArray1).values.toList()
        assertEquals(
            bonusOrig + List(cols.globalBonusTailZeros) { 0L },
            bonusBack,
            "GLOBAL_BONUS = 原 ${bonusOrig.size} 格原值 + ${cols.globalBonusTailZeros} 个 0（${cols.note}）",
        )
        assertEquals("", back.text, "全局档描述恒空串（VariableEvaluator.cs:2488）")
    }

    /** 改动一个字段后写盘：读回只见该字段变化，其余字段仍与真档一致。 */
    @Test
    fun `改动单字段后写盘只该字段变化`(@TempDir tmp: Path) {
        val src = sample("save00.sav")
        val game = gameDir()
        val csv = game.resolve("CSV")
        val data = GameDataLoader.load(csv)
            ?: throw AssertionError("$csv 装不出 CSV 注册表：语料根不完整（ERATW_GAMEDIR=$game）")
        val cols = columns

        val root = Files.createDirectories(tmp.resolve("game"))
        val sav = Files.createDirectories(root.resolve("sav"))
        Files.write(sav.resolve("save00.sav"), src)
        val sets = EmSaveSets.parse(csv)
        val cfg = SaveConfig.ERATW
        val store = realStore(data)
        FileSystemOps(store, RootedSaveFileIo(root), cfg, sets).loadData(0)

        val orig = SaveFormat.read(src)
        val moneyKey = "MONEY"
        val moneyIdx = orig.variables.indexOfFirst { it.key == moneyKey }
        assertTrue(moneyIdx >= 0, "真档全局块应有 MONEY")
        val before = (orig.variables[moneyIdx] as SaveEntry.IntArray1).values.toList()
        val slot = before.indexOfFirst { it != 0L }.let { if (it < 0) 0 else it }
        val newValue = before[slot] + 12345L
        store.writeInt(moneyKey, idx(slot.toLong()), newValue)

        assertTrue(FileSystemOps(store, RootedSaveFileIo(root), cfg, sets).saveData(21, orig.text))
        val written = Files.readAllBytes(sav.resolve("save21.sav"))
        assertTrue(!written.contentEquals(src), "改了字段就不该原样回写")

        val back = SaveFormat.read(written)
        val after = (back.variables.first { it.key == moneyKey } as SaveEntry.IntArray1).values.toList()
        assertEquals(before.size, after.size, "MONEY 数组长度")
        val changed = before.indices.filter { before[it] != after[it] }
        assertEquals(listOf(slot), changed, "只有被改的那个元素变化")
        assertEquals(newValue, after[slot], "改后的值")
        assertEquals(orig.text, back.text, "描述不变")

        // 其余全局条目与全部角色条目：原档侧要么原样写回、要么命中本根的对账清单
        //（4.981 池面写回会多出新注册的全局变量，与 [真实存档经变量池写盘后逐项一致] 同一口径，不算差异）
        val others = orig.variables.filter { it.key != moneyKey }
        val othersBack = back.variables.filter { it.key != moneyKey }
        val otherDiffs = diffBlocks("全局(除 MONEY)", others, othersBack)
        assertTrue(
            otherDiffs.map { driftKey(it) }.all { it in cols.globalOrigSideDrift || it.startsWith("多 ") },
            "其余全局条目出现对账清单之外的差异（${cols.note}）：\n" + otherDiffs.joinToString("\n"),
        )
        assertTrue(
            missingOnly("角色0", orig.characters[0], back.characters[0])
                .map { driftKey(it) }.all { it in cols.charaOrigSideDrift },
            "角色块出现对账清单之外的丢字段/改值：${missingOnly("角色0", orig.characters[0], back.characters[0]).take(5)}",
        )
    }

    // ==================================================================
    // 4. 文本格式（SystemSaveInBinary=false 的默认分支）
    // ==================================================================

    /**
     * 文本分支（`EraDataWriter` 复刻）写出的档必须能被**同一读路径**读回：
     * `__FINISHED` 数组收尾（EraDataWriter.cs:75/108）、`__EMUERA_1808_STRAT__` 扩展区
     * （:117）、`__EMU_SEPARATOR__` 分段（:126）、UTF-8 BOM（spec §1.4）。
     *
     * 校验方式：写 → 读回新变量池 → 关键字段相等；再写一次 → 字节稳定（确定性）。
     */
    @Test
    fun `文本格式写盘可被读回且二次写稳定`() {
        val io = MemorySaveIo()
        val s = store()
        s.addHero()
        s.declareUserVar(
            DimParser.parse(
                "#DIM", "SAVEDATA UI, 3", isPrivate = false, DimParseConfig(systemSaveInBinary = false),
            ),
        )
        s.writeInt("DAY", idx(0), 42)
        s.writeInt("MONEY", idx(3), 987654)
        s.writeStr("SAVESTR", idx(0), "保存文字𠮟")
        s.writeInt("CFLAG", idx(0, 5), 17)
        s.writeStr("CSTR", idx(0, 1), "角色文")
        s.writeInt("UI", idx(2), 7)

        val cfg = SaveConfig(systemSaveInBinary = false, zipSaveData = false, useSaveFolder = true)
        val fs = FileSystemOps(s, io, cfg)
        assertTrue(fs.saveData(2, "文本描述"))
        val first = io.files.getValue("sav/save02.sav")

        assertEquals(0xEF, first[0].toInt() and 0xFF, "文本档首字节应为 UTF-8 BOM（spec §1.4）")
        val text = String(first, 3, first.size - 3, Charsets.UTF_8)
        assertTrue(text.contains(SaveTextFormat.EMU_START), "应带 1808 扩展区标记")
        assertTrue(text.contains(SaveTextFormat.FINISHED), "数组应以 __FINISHED 收尾")
        assertTrue(text.contains(SaveTextFormat.SEPARATOR), "扩展段以 __EMU_SEPARATOR__ 分段")

        // 读回另一个池
        val dst = store()
        dst.addHero()
        dst.declareUserVar(
            DimParser.parse(
                "#DIM", "SAVEDATA UI, 3", isPrivate = false, DimParseConfig(systemSaveInBinary = false),
            ),
        )
        FileSystemOps(dst, io, cfg).loadData(2)
        assertEquals(42L, dst.readInt("DAY", idx(0)), "DAY")
        assertEquals(987654L, dst.readInt("MONEY", idx(3)), "MONEY")
        assertEquals("保存文字𠮟", dst.readStr("SAVESTR", idx(0)), "SAVESTR（增补平面字符）")
        assertEquals(17L, dst.readInt("CFLAG", idx(0, 5)), "CFLAG")
        assertEquals("角色文", dst.readStr("CSTR", idx(0, 1)), "CSTR")
        assertEquals(7L, dst.readInt("UI", idx(2)), "用户 #DIM SAVEDATA")
        assertEquals("文本描述", dst.lastLoadText, "SAVEDATA_TEXT")

        // 二次写：同状态必须逐字节相同（文本分支的确定性）
        assertTrue(FileSystemOps(dst, io, cfg).saveData(2, "文本描述"))
        assertTrue(first.contentEquals(io.files.getValue("sav/save02.sav")), "同状态二次写应逐字节相同")
    }

    // ==================================================================
    // 5. 落盘路径（SavDir 单候选）与角色档压缩标志
    // ==================================================================

    /**
     * `SavDir` 单候选：`UseSaveFolder=true` ⇒ 只写 `<root>/sav/`，根目录不留副本；
     * 写路径（[SaveConfig.normalPath]）与探测路径（[SaveDataProbe.candidatePaths]）同一字符串
     * （C# 写读删同源：VariableEvaluator.cs:2646 / :2689 / :2712 / :2144；Config.cs:471-481）。
     */
    @Test
    fun `落盘路径与探测路径同源且只在 sav`(@TempDir root: Path) {
        val store = store()
        val cfg = SaveConfig.ERATW
        val host = RootedBuiltinHost(root)
        for (no in listOf(0L, 3L, 79L)) {
            assertEquals(
                listOf(cfg.normalPath(no.toInt())),
                SaveDataProbe.candidatePaths(host, SaveFileKind.NORMAL, no, cfg.useSaveFolder),
                "探测候选必须等于落盘路径（no=$no）",
            )
        }
        assertEquals(listOf(cfg.globalPath()), SaveDataProbe.candidatePaths(host, SaveFileKind.GLOBAL, 0L, true))

        assertTrue(FileSystemOps.rooted(store, root, cfg).saveData(4, "desc"))
        assertTrue(Files.isRegularFile(root.resolve("sav/save04.sav")), "应落在 sav/（Config.cs:471-477）")
        assertTrue(!Files.exists(root.resolve("save04.sav")), "根目录不得留副本（原版靠迁移而非双写）")
    }

    /**
     * `SAVECHARA` 的容器压缩标志 = `SystemSaveInBinary && ZipSaveData`
     * （`EraBinaryDataWriter..ctor`，EraBinaryDataWriter.cs:22-32；SaveChara 用它，:2259）。
     *
     * 回归点：写侧曾经硬编码 `compressed = true`，在文本配置下会产出 GZip 魔数的角色档，
     * 与原版「文本配置 ⇒ 未压缩二进制角色档（MAGIC_PLAIN）」不符。
     */
    @Test
    fun `角色档压缩标志跟随配置`() {
        val configs = listOf(
            SaveConfig.ERATW, // binary + zip ⇒ GZip 壳
            SaveConfig(systemSaveInBinary = true, zipSaveData = false), // 二进制不压缩 ⇒ MAGIC_PLAIN
            SaveConfig(systemSaveInBinary = false, zipSaveData = true), // 文本配置下 ZipSaveData 不生效
        )
        for (cfg in configs) {
            val io = MemorySaveIo()
            val s = store()
            s.addHero()
            s.writeInt("CFLAG", idx(0, 0), 5)
            FileSystemOps(s, io, cfg).saveChara(listOf(0L), "T", "chara")
            val bytes = io.files.getValue("dat/chara_T.dat")
            var magic = 0L
            for (i in 0 until 8) magic = magic or ((bytes[i].toLong() and 0xFF) shl (8 * i))
            val expected = if (cfg.systemSaveInBinary && cfg.zipSaveData) SaveFormat.MAGIC_GZIP else SaveFormat.MAGIC_PLAIN
            assertEquals(expected, magic, "压缩标志应为 SystemSaveInBinary && ZipSaveData（cfg=$cfg）")

            // 两种壳都必须能被 LOADCHARA 读回（CreateReader 认两个魔数，EraBinaryDataReader.cs:786-830）
            val dst = store()
            FileSystemOps(dst, io, cfg).loadChara("T")
            assertEquals(1, dst.characters.count, "LOADCHARA 应追加 1 个角色（cfg=$cfg）")
            assertEquals(5L, dst.readInt("CFLAG", idx(0, 0)), "角色字段回读")
        }
    }

    // ==================================================================
    // 工具：真实变量池（CSV 注册表 + ERH 全局 #DIM）
    // ==================================================================

    /**
     * 建一个与 `GameSession` 前 4 步同源的变量池：CSV 数据 → 模板 → ERH 全局 `#DIM`。
     *
     * ERH 声明按**文本行**收集（`#DIM`/`#DIMS` 行），再交给
     * [SystemVariableBootstrap.install] 的不动点环 —— 与该类的生产用法一致
     * （`core/proc/GameSession.kt:207-212`）。这是写侧能覆盖 540 条真档条目的前提：
     * `SaveCapture` 只认已注册变量的 `isSavedata` 位，未注册的 477 条会被静默跳过。
     */
    private fun realStore(data: GameData): VariableStore {
        val store = VariableStore(data.registry)
        data.templates.forEach { store.registerTemplate(it) }
        store.loadItemPrice(data.itemPrices)
        store.reloadStrNames()
        val game = gameDir()
        val inst = SystemVariableBootstrap.install(
            store,
            erhDeclarations(game),
            DimParseConfig(systemSaveInBinary = true),
        )
        assertTrue(inst.declared > 5000, "ERH 全局 #DIM 应注册 >5000 条（实测 7181），实际 ${inst.declared}")
        return store
    }

    /** 扫 `ERB` 子树下的 ERH，收集 `#DIM`/`#DIMS` 原文（行号取文件物理行，报告可定位）。 */
    private fun erhDeclarations(gameDir: Path): List<SystemVariableBootstrap.RawDeclaration> {
        val root = gameDir.resolve("ERB")
        val out = ArrayList<SystemVariableBootstrap.RawDeclaration>()
        if (!Files.isDirectory(root)) return out
        Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".erh", ignoreCase = true) }
                .sorted()
                .forEach { p ->
                    val bytes = runCatching { Files.readAllBytes(p) }.getOrNull() ?: return@forEach
                    // 与语料门同口径：前 8KB 有 NUL 视为二进制，不解析
                    if (bytes.indexOf(0.toByte()) in 0 until minOf(bytes.size, 8192)) return@forEach
                    val rel = root.relativize(p).toString().replace('\\', '/')
                    var no = 0
                    for (raw in String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").split('\n')) {
                        no++
                        val line = raw.trimStart('\uFEFF', ' ', '\t')
                        if (!line.startsWith("#") || line.length < 4) continue
                        val kw = line.substring(1).takeWhile { !it.isWhitespace() }.uppercase()
                        if (kw == "DIM" || kw == "DIMS") {
                            out.add(SystemVariableBootstrap.RawDeclaration(line, rel, no))
                        }
                    }
                }
        }
        return out
    }

}
