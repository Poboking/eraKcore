package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.vars.Storage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * L12 续任审计（audit2）：存档编解码 / RNG 的第二轮逐条核验。
 *
 * 覆盖到的口径（每条都带 C# `文件:行号`；源码根 `reference/emuera-cs/`）：
 *
 * | 核验项 | 结论 | 证据 |
 * |---|---|---|
 * | `VarExt*.csv` 的行接受（前导空白、`;` 注释、`;!;`/`;^;` 标记、纯 CR 行尾、指令名大小写） | 与原版不一致 → 本路已修（见 [EmSaveSets]） | EraStreamReader.cs:51、:96-129；LexicalAnalyzer.cs:647-686；ConstantData.cs:1334-1408；Config.cs:299-307；ConfigData.cs:45 |
 * | 扩展段名字表的大小写（指令名跟随 `Config.StringComparison`，**条目名恒 Ordinal**） | 已修 + 钉死 | ConstantData.cs:132-156（`new HashSet<string>()`）、:1350（`array[j].Trim()`） |
 * | 二进制容器判定 = 魔数 **且** 容器版本 1808 | 一致（`SaveCodec.isBinary` ≡ `CreateReader != null`） | EraBinaryDataWriter.cs:37-45；EraBinaryDataReader.cs:790-799、:820-824 |
 * | `CheckData` 分支序（类别 → 游戏码 → 版本 → 描述；文本档不比类别） | 一致 | VariableEvaluator.cs:2159-2217 |
 * | 存档文件名与 `SavDir` 单候选 | 一致（含 GLOBAL 不吃下标、VAR/CHARVAR 走 DatDir） | VariableEvaluator.cs:2018-2057；Config.cs:471-481；Program.cs:216-218 |
 * | `SaveCapture` 的进档筛选（系统 + 用户、普通/全局二分，零漏网） | 一致 | VariableData.cs:3961-3978、:3980-3997；CharacterData.cs:513-558 |
 * | `SaveWiring` 的 aux 装配（会话级、重复 install 保留先装的） | 一致（本仓纪律，非 C# 对应物） | 见 [SaveWiring] 类 KDoc |
 * | SFMT19937 的取值序 / 截断 / 状态装载越界 | 一致 | MTRandom.cs:32、:46-49、:56-80、:92-102、:141-164 |
 *
 * 未在本文件断言、已交「需组长接线」的两项（详见交付报告）：
 * `SaveFormat.peek` 的 `bytes.size < 16` 早退把「小于 16 字节的**文本**档」判成 ETC_ERROR
 * （原版此时回落 `EraDataReader` 逐行读，EraBinaryDataReader.cs:790-793 + VariableEvaluator.cs:2176）；
 * `RESETDATA` 不清 EM 池（原版 `ResetData` 首步即 `RemoveEMSaveData`，
 * VariableEvaluator.cs:1336-1346；`RESETGLOBAL` 的 global/static 两步已接线，
 * 见 StatementExecutor.kt 的 `EmDataPools.clear(em.global / em.staticSets)`）。
 */
class SaveAudit2Test {

    // ==================================================================
    // 1. EmSaveSets：VarExt CSV 的行接受口径（本路修复）
    // ==================================================================

    private fun parseOf(@Suppress("UNUSED_PARAMETER") name: String, content: String, ignoreCase: Boolean = true): EmSaveSets {
        val dir = Files.createTempDirectory("varext")
        Files.write(dir.resolve("VarExtAudit.csv"), content.toByteArray(Charsets.UTF_8))
        return try {
            EmSaveSets.parse(dir, ignoreCase)
        } finally {
            Files.deleteIfExists(dir.resolve("VarExtAudit.csv"))
            Files.deleteIfExists(dir)
        }
    }

    /**
     * 行首空白被跳过、`;` 才是注释、`;!;` / `;^;` 是被跳过的三字符标记、
     * 全角空格不跳（`Config.SystemAllowFullSpace` 在 eratw 是 NO）。
     * spec: EraStreamReader.cs:96-129（`ReadEnabledLine` 先 `SkipWhiteSpace` 再判 `EOS`）
     * + LexicalAnalyzer.cs:647-686（`' '`/`\t` 跳；`;` ⇒ `st.Seek(0, End)` 整行作废；
     * `;!;`/`;^;` ⇒ `st.Jump(3)` 继续；`\u3000` 受 `SystemAllowFullSpace` 门）。
     */
    @Test
    fun `VarExt 行首空白与注释标记按 SkipWhiteSpace 处理`() {
        val sets = parseOf(
            "lead",
            buildString {
                append("   SAVE_DTS, A\r\n")
                append("\tSAVE_MAPS, M1\r\n")
                append("  ; SAVE_DTS, NOPE\r\n")
                append(";\r\n")
                append("   \r\n")
                append(";!;SAVE_DTS, B\r\n")
                append(";^;SAVE_DTS, C\r\n")
                append(";#;SAVE_DTS, NOPE2\r\n")
                append("　SAVE_DTS, NOPE3\r\n")
            },
        )
        assertEquals(listOf("A", "B", "C"), sets.save.dts, "行首空白后照读；`;!;`/`;^;` 标记跳过后照读；`;#;` 按注释")
        assertEquals(listOf("M1"), sets.save.maps)
        assertTrue(sets.global.isEmpty, "没有 GLOBAL_* 指令")
    }

    /**
     * 指令名按 `Config.StringComparison` 比较（`IgnoreCase` 出厂默认 true，
     * ConfigData.cs:45；eratw 的 emuera.config 明写 `IGNORE CASE:YES` ⇒ OrdinalIgnoreCase，
     * Config.cs:299-307），而**条目名恒 Ordinal**（`new HashSet<string>()` 用默认比较器，
     * ConstantData.cs:132-156）。`ignoreCase=false` 一侧同时钉住「关掉开关就严格区分」。
     */
    @Test
    fun `指令名跟随 IgnoreCase 而条目名恒大小写敏感`() {
        val ci = parseOf("ci", "save_dts, x\r\nSAVE_DTS, Y\r\nGLOBAL_MAPS, gm\r\n")
        assertEquals(listOf("x", "Y"), ci.save.dts, "小写指令也认，且条目名大小写视为两条")
        assertEquals(listOf("gm"), ci.global.maps)

        val strict = parseOf("strict", "save_dts, x\r\nSAVE_DTS, Y\r\n", ignoreCase = false)
        assertEquals(listOf("Y"), strict.save.dts, "IgnoreCase=NO ⇒ 走 Ordinal ⇒ 小写指令被忽略")
    }

    /**
     * `array[0]` **不 trim**（ConstantData.cs:1353 的 `array[0].Equals(...)`）⇒
     * 「指令名后带空格」的行两侧都不认；后段才 `Trim()`（:1350）。
     */
    @Test
    fun `指令名带空格不认而条目名去空格`() {
        val sets = parseOf("trim", "SAVE_DTS , X\r\nSAVE_MAPS,  M  ,N\r\n")
        assertTrue(sets.save.dts.isEmpty(), "array[0] = \"SAVE_DTS \" 不等于 SAVE_DTS")
        assertEquals(listOf("M", "N"), sets.save.maps, "后段逐个 Trim")
    }

    /**
     * 纯 CR 行尾也逐行读（`File.ReadAllLines`，EraStreamReader.cs:51）；
     * 无逗号行与空指令名行整体忽略（`array.Length < 2` / `array[0].Length == 0`
     * 两支只 Warn，ConstantData.cs:1338/1342）。
     */
    @Test
    fun `纯 CR 行尾切行且多余行只告警不中断`() {
        val sets = parseOf("cr", "SAVE_DTS, R1\rSAVE_DTS, R2\r;\r\r\nSAVE_DTS\r,X\r")
        assertEquals(listOf("R1", "R2"), sets.save.dts, "CR 分隔的两条都读到")
        assertEquals(0, sets.save.maps.size, "无逗号的 SAVE_DTS 行与空指令名行都不产条目")
    }

    /**
     * 原版把 `array[j].Trim()` **无条件**入表（ConstantData.cs:1350），含空串；
     * 写侧靠 `DataStringMaps.ContainsKey("")` 恒假而不观测（VariableData.cs:3936-3959），
     * 但读侧接受集里确实有这一条（:4099）⇒ 移植侧不得自作主张过滤。
     */
    @Test
    fun `空名字照入表且写侧不可观测`() {
        val sets = parseOf("empty", "SAVE_MAPS,\r\nSAVE_XMLS, ,X\r\n")
        assertEquals(listOf(""), sets.save.maps)
        assertEquals(listOf("", "X"), sets.save.xmls)
        assertFalse(sets.save.isEmpty, "入表了就不算空表（原版 HashSet 同样有元素）")
    }

    /** 三条 `STATIC_*` 只服务 `RemoveEMStaticData`（VariableData.cs:4056-4074），与存档字节无关。 */
    @Test
    fun `STATIC 指令不进存档侧两张表`() {
        val sets = parseOf("static", "STATIC_DTS, SD\r\nSTATIC_MAPS, SM\r\nSTATIC_XMLS, SX\r\nSAVE_DTS, keep\r\n")
        assertTrue(sets.save.dts == listOf("keep") && sets.save.maps.isEmpty() && sets.save.xmls.isEmpty())
        assertTrue(sets.global.isEmpty)
    }

    /** 目录里没有 `VarExt*.csv` ⇒ 两张空表（ConstantData.cs:1311-1316 的清空形态）；`ignoreCase` 缺省即出厂口径。 */
    @Test
    fun `无 VarExt 文件时为空表且缺省开关为忽略大小写`(@org.junit.jupiter.api.io.TempDir root: Path) {
        val csv = Files.createDirectories(root.resolve("CSV"))
        assertEquals(EmSaveSets.EMPTY, EmSaveSets.parse(csv))
        Files.write(
            csv.resolve("VarExtGameData.csv"),
            "save_dts, DT_LOWER\r\n".toByteArray(Charsets.UTF_8),
        )
        assertEquals(listOf("DT_LOWER"), EmSaveSets.parse(csv).save.dts, "缺省 = IgnoreCase=YES 的出厂口径")
        assertTrue(EmSaveSets.parse(csv, ignoreCase = false).save.isEmpty)
    }

    // ==================================================================
    // 2. SaveCodec：二进制容器判定与写读分派同锁
    // ==================================================================

    /**
     * 判据 = 魔数命中 **且** 容器版本恰为 1808（原版即 `CreateReader` 返回非 null：
     * EraBinaryDataWriter.cs:37-45 写、EraBinaryDataReader.cs:790-799 比魔数、:820-824 比版本）。
     * 两个魔数分别是未压缩 `727905341820519817` 与 GZip `743174590507926921`。
     */
    @Test
    fun `容器判定同时锁魔数与容器版本`() {
        val gz = SaveFormat.write(SaveData(SaveFileKind.NORMAL, 7153L, 0L, "t", compressed = true))
        val plain = SaveFormat.write(SaveData(SaveFileKind.NORMAL, 7153L, 0L, "t", compressed = false))
        assertTrue(SaveCodec.isBinary(gz), "GZip 分支魔数 + 1808")
        assertTrue(SaveCodec.isBinary(plain), "未压缩分支魔数 + 1808")
        assertEquals(SaveFormat.MAGIC_GZIP, readU64(gz, 0), "壳标志即压缩标志")
        assertEquals(SaveFormat.MAGIC_PLAIN, readU64(plain, 0))

        val badVersion = gz.copyOf().also { it[8] = 0x30; it[9] = 0x07 } // 容器版本 1808 → 1840
        assertFalse(SaveCodec.isBinary(badVersion), "魔数对而容器版本非 1808 ⇒ 原版当作文本再读一次")
        assertFalse(SaveCodec.isBinary(gz.copyOfRange(0, 15)), "不足 16 字节不可能是容器（:790-793）")
    }

    /** 写侧配置与读侧分派同源：二进制配置 ⇒ 容器字节；文本配置 ⇒ 非容器字节且能读回。 */
    @Test
    fun `写读分派跟随 SystemSaveInBinary`() {
        val store = VariableStore()
        val data = SaveCapture.normal(store, "dispatch")
        val bin = SaveCodec.write(data, store.registry, SaveConfig(systemSaveInBinary = true, zipSaveData = true))
        val txt = SaveCodec.write(data, store.registry, SaveConfig(systemSaveInBinary = false))
        assertTrue(SaveCodec.isBinary(bin))
        assertFalse(SaveCodec.isBinary(txt), "文本档首 8 字节是十进制数字，不可能命中魔数")

        val back = SaveCodec.read(txt, store.registry, SaveFileKind.NORMAL)
        assertEquals("dispatch", back.text)
        assertEquals(SaveFileKind.NORMAL, back.fileType)
        assertEquals(SaveCodec.read(bin, store.registry, SaveFileKind.NORMAL).text, back.text, "两式描述文本同源")
    }

    // ==================================================================
    // 3. SaveDataProbe：CheckDataByFilename 的分支序
    // ==================================================================

    private fun gb(code: Long = 7153L, version: Long = 0L, defined: Boolean? = false, allow: Long = -1L) =
        GameBaseData(gameCode = code, version = version, versionDefined = defined, allowVersion = allow)

    private fun globalBytes(text: String, uniqueCode: Long = 7153L) = SaveFormat.write(
        SaveData(SaveFileKind.GLOBAL, uniqueCode, 0L, text, compressed = true),
    )

    /**
     * 全通过 ⇒ OK + 存档描述（VariableEvaluator.cs:2213-2215 的 `DataMes = ReadString()`）。
     */
    @Test
    fun `探测通过时描述文本即存档头`() {
        val r = SaveDataProbe.checkBytes(globalBytes("global-desc"), SaveFileKind.GLOBAL, gb())
        assertEquals(EraDataState.OK, r.state)
        assertEquals("global-desc", r.mes)
    }

    /**
     * 类别不符 ⇒ ETC_ERROR（:2196-2201），且这一条**只**在二进制容器里比（文本档无类别字节，
     * :2176-2194 直接走码与版本）。这里同时钉住「比类别先于比游戏码」的顺序。
     */
    @Test
    fun `类别检查先于游戏码与版本`() {
        val wrong = SaveDataProbe.checkBytes(globalBytes("g"), SaveFileKind.NORMAL, gb(code = 1L, allow = 9L))
        assertEquals(EraDataState.ETC_ERROR, wrong.state)
        assertEquals(SaveDataProbe.MES_CORRUPTED, wrong.mes)
    }

    /** 游戏码与版本同时不符时先报 GAME_ERROR（:2202-2206 在 :2207-2212 之前）。 */
    @Test
    fun `游戏码不符先于版本不符命中`() {
        val r = SaveDataProbe.checkBytes(
            globalBytes("g", uniqueCode = 7153L), SaveFileKind.GLOBAL,
            gb(code = 999L, version = 5L, defined = true, allow = 9L),
        )
        assertEquals(EraDataState.GAME_ERROR, r.state)
        assertEquals(SaveDataProbe.MES_DIFFERENT_GAME, r.mes)
    }

    /** 游戏码对（含存档侧 0 的豁免，GameBase.cs:60-67）而版本三条件全不过 ⇒ VIRSION_ERROR（GameBase.cs:69-80）。 */
    @Test
    fun `版本不符在码通过后命中`() {
        val r = SaveDataProbe.checkBytes(globalBytes("g"), SaveFileKind.GLOBAL, gb(version = 5L, defined = true, allow = 9L))
        assertEquals(EraDataState.VIRSION_ERROR, r.state)
        assertEquals(SaveDataProbe.MES_DIFFERENT_VERSION, r.mes)
        val zeroExempt = SaveDataProbe.checkBytes(
            globalBytes("g", uniqueCode = 0L), SaveFileKind.GLOBAL, gb(code = 7153L),
        )
        assertEquals(EraDataState.OK, zeroExempt.state, "存档 UniqueCode = 0 ⇒ 豁免")
    }

    /** 魔数对但容器版本非 1808 ⇒ 原版 `CreateReader` 返回 null 后按文本读同一份字节、随即失败 ⇒ ETC_ERROR。 */
    @Test
    fun `非 1808 容器的读侧落点是 ETC_ERROR`() {
        val bytes = globalBytes("g").copyOf().also { it[8] = 0x30; it[9] = 0x07 } // 1808 → 1840
        assertFalse(SaveCodec.isBinary(bytes))
        val r = SaveDataProbe.checkBytes(bytes, SaveFileKind.GLOBAL, gb())
        assertEquals(EraDataState.ETC_ERROR, r.state)
    }

    // ==================================================================
    // 4. SaveCapture：进档筛选与头部字段
    // ==================================================================

    /**
     * 系统侧零漏网：`registry.allInVarTokenDicOrder()`（= C# varTokenDic 的 Add 序，
     * VariableData.cs:3014-3179）里每一条 `IsSavedata && !IsCharacterData && !IsGlobal`
     * （普通档，VariableData.cs:3980-3997）/ `&& IsGlobal`（全局档，:3961-3978）都必须
     * 产出一个同名条目，且条目形状与 `VarDef.dims` 一致 —— 即 `entryForDef` 的
     * `else -> null` 分支对真注册表不可达（漏一条就是静默丢档）。
     * 条目序 = varTokenDic 枚举序（:3982 foreach；SAVE-12 修复前曾错用按存储形态
     * 分组的构造序，SAVESTR/TSTR 会排到 TA/TB 之后）。
     */
    @Test
    fun `系统 SAVEDATA 变量逐条进档且形状与声明一致`() {
        val store = VariableStore()
        for (global in listOf(false, true)) {
            val defs = store.registry.allInVarTokenDicOrder().filter {
                it.isSavedata && !it.isCharacterData && it.isGlobal == global
            }
            val entries = (if (global) SaveCapture.global(store) else SaveCapture.normal(store, "t")).variables
            assertEquals(defs.map { it.name }, entries.map { it.key }, "global=$global：条目序 = varTokenDic Add 序")
            for ((def, e) in defs.zip(entries)) {
                assertEquals(def.name, e.key)
                when (def.storage) {
                    Storage.G_INT1 -> assertEquals(
                        def.dims[0],
                        (e as SaveEntry.IntArray1).values.size,
                        "1D 元素数 = dims[0]",
                    )

                    Storage.G_STR1 -> assertEquals(
                        def.dims[0],
                        (e as SaveEntry.StrArray1).values.size,
                        "Str1D 元素数 = dims[0]",
                    )

                    else -> {}
                }
            }
        }
    }

    /** 档头三字段取自 GameBase（VariableEvaluator.cs:2595-2597）；全局档的描述文本恒空串（:2488）。 */
    @Test
    fun `全局档描述恒空串而普通档描述由调用方给`() {
        val store = VariableStore()
        val g = SaveCapture.global(store)
        assertEquals(SaveFileKind.GLOBAL, g.fileType)
        assertEquals("", g.text, "WriteString(\"\")：global.sav 没有描述文本位")
        assertEquals(store.registry.gameBase.gameCode, g.uniqueCode)
        assertEquals(store.registry.gameBase.version, g.scriptVersion)
        val n = SaveCapture.normal(store, "2026/10/22 第 1日 ")
        assertEquals("2026/10/22 第 1日 ", n.text, "描述文本原样入库（Process.cs:2872 拼好的串，含尾空格）")
        assertEquals(SaveFileKind.NORMAL, n.fileType)
    }

    /** 0 角色 ⇒ 不写角色块（`CharacterList.Count` 为 0 时循环体不执行，VariableEvaluator.cs:2598-2602）。 */
    @Test
    fun `零角色时角色块为空`() {
        val store = VariableStore()
        assertEquals(0, SaveCapture.normal(store, "t").characters.size)
        assertEquals(0, SaveCapture.global(store).characters.size, "全局档根本没有角色位")
    }

    // ==================================================================
    // 5. SaveFileIo：路径口径与只读判定
    // ==================================================================

    /** 越界路径一律按「不存在 / 读不出 / 写不进」处理，且不误伤根目录外的真实文件。 */
    @Test
    fun `RootedSaveFileIo 拒绝越界并接受反斜杠`(@org.junit.jupiter.api.io.TempDir root: Path) {
        val io = RootedSaveFileIo(root)
        val bytes = byteArrayOf(1, 2, 3)
        assertTrue(io.write("sav\\save00.sav", bytes), "反斜杠与正斜杠同口径")
        assertTrue(Files.exists(root.resolve("sav/save00.sav")), "父目录自动补建")
        assertTrue(io.read("sav/save00.sav")!!.contentEquals(bytes))
        assertTrue(io.exists("sav/save00.sav"))

        val outside = root.resolveSibling("evil-outside.bin")
        Files.write(outside, bytes)
        try {
            assertFalse(io.write("../evil-outside2.bin", bytes))
            assertNull(io.read("../${outside.fileName}"))
            assertFalse(io.exists("../${outside.fileName}"))
            assertFalse(io.write(outside.toString(), bytes), "绝对路径越界同样拒绝")
            assertFalse(Files.exists(root.resolveSibling("evil-outside2.bin")), "被拒的写不落盘")
            assertTrue(Files.isDirectory(root), "拒绝越界不该动到根目录本身")
        } finally {
            Files.deleteIfExists(outside)
        }
        assertTrue(io.delete("sav/save00.sav"))
        assertTrue(io.delete("missing.sav"), "不存在也算删除成功（DelData 的 no-op 分支）")
        assertFalse(io.exists("sav/save00.sav"))
    }

    /** `isReadOnly` 只在真只读时为 true：DELDATA 的前置判定（VariableEvaluator.cs:2710-2717）。 */
    @Test
    fun `只读判定跟随文件系统可写位`(@org.junit.jupiter.api.io.TempDir root: Path) {
        val io = RootedSaveFileIo(root)
        assertFalse(io.isReadOnly("no-such.sav"), "不存在的文件不判只读")
        assertTrue(io.write("ro.bin", byteArrayOf(9)))
        val f = root.resolve("ro.bin")
        f.toFile().setWritable(false)
        try {
            if (!Files.isWritable(f)) {
                assertTrue(io.isReadOnly("ro.bin"), "平台把只读反映为不可写 ⇒ 必须判只读")
            }
        } finally {
            f.toFile().setWritable(true)
        }
        assertFalse(io.isReadOnly("ro.bin"))
    }

    // ==================================================================
    // 6. SaveWiring：aux 装配
    // ==================================================================

    /**
     * 会话级：未装配 ⇒ [SaveWiring.of] 给 null（函数层各自退级，绝不抛）；
     * `resetValues()`（读档复位）不清 aux；重复 install 保留先装的那份。
     */
    @Test
    fun `装配读得到且读档复位不清它`() {
        val store = VariableStore()
        assertNull(SaveWiring.of(store))
        val first = SaveWiring.install(store, SaveConfig(systemSaveInBinary = true, useSaveFolder = true), 20)
        val second = SaveWiring.install(store, SaveConfig(systemSaveInBinary = false), 80)
        assertSame(first, second, "重复 install 不覆盖先装的")
        assertEquals(20, SaveWiring.of(store)?.saveDataNos)
        store.resetValues()
        assertEquals(first, SaveWiring.of(store), "aux 是会话级状态，不属于存档内容")
        store.aux.clear()
        assertNull(SaveWiring.of(store))
    }

    /** 缺省 `emSets` = 空表 ⇒ 不写不读扩展段；`scriptVersionDefined` 缺省 null ⇒ 判定自行退级。 */
    @Test
    fun `装配缺省值即无扩展段与退级版本判定`() {
        val store = VariableStore()
        val w = SaveWiring.install(store, SaveConfig.DEFAULT, 20)
        assertEquals(EmSaveSets.EMPTY, w.emSets)
        assertNull(w.scriptVersionDefined)
        assertTrue(w.emSets.loadAccept.isEmpty)
    }

    // ==================================================================
    // 7. Sfmt19937：状态装载的越界截断
    // ==================================================================

    /**
     * `SetRand` 的 `idx = (int)array[624]`（MTRandom.cs:66）是**截断**而非校验：
     * 第 625 位超出 Int32 时取低 32 位，于是 2^32+5 落成 idx = 5，
     * 下一次取值直接读 `sfmt[5]`、**不**触发 `gen_rand_all`（:82-90 的 `idx >= 624` 门）。
     * 状态字同理按 `(uint)` 截断（:64）。
     */
    @Test
    fun `loadState 的游标按 Int32 低 32 位截断`() {
        val rng = Sfmt19937(1L)
        val data = LongArray(Sfmt19937.RANDDATA_LEN) { it.toLong() }
        data[Sfmt19937.STATE_SIZE] = (1L shl 32) + 5L
        rng.loadState(data)
        assertEquals(5L, rng.state()[Sfmt19937.STATE_SIZE], "idx = (int)(2^32+5) = 5")
        assertEquals(5L, rng.nextUInt32(), "未过 624 门 ⇒ 直接吃 sfmt[5]")
        assertEquals(6L, rng.state()[Sfmt19937.STATE_SIZE], "取一个走一格（sfmt[idx++]）")

        val wrap = LongArray(Sfmt19937.RANDDATA_LEN) { 0L }
        wrap[0] = (1L shl 33) + 0xFFFF_FFFFL
        rng.loadState(wrap)
        assertEquals(0xFFFF_FFFFL, rng.state()[0], "状态字按 UInt32 取低 32 位")
        assertEquals(0L, rng.state()[Sfmt19937.STATE_SIZE], "idx 位同理")
    }

    /**
     * `init_gen_rand` 之后必走 `period_certification`（MTRandom.cs:100），两支都要落到
     * 与 C# 真值同形的读数（真值表同 `RngParityAuditTest.init_gen_rand 与 period_certification`）：
     * - seed 20261017：奇偶内积折叠后 `num & 1 == 0` ⇒ 从 PARITY1=1 的最低置位异或回
     *   `sfmt[0]` ⇒ `20261017 ^ 1 = 20261016`（:117-133）；
     * - seed 0：`sfmt[i] = 1812433253 * (sfmt[i-1] ^ (sfmt[i-1] >> 30)) + i`（:96-99）
     *   ⇒ `sfmt[1] = 1`，且奇偶位本来就满足 ⇒ 直接 return、**不**翻转（:117-120）；
     * - 两支播种后 `idx` 恒为 624（:101），即首次取值先触发一次 `gen_rand_all`。
     */
    @Test
    fun `播种后的奇偶校验两支与初始化后的游标`() {
        val flip = Sfmt19937(20261017L).state()
        assertEquals(20261016L, flip[0], "命中翻转分支：sfmt[0] ^= PARITY1 的最低位")
        assertEquals(Sfmt19937.STATE_SIZE.toLong(), flip[Sfmt19937.STATE_SIZE], "初始化后 idx = 624")

        val plain = Sfmt19937(0L).state()
        assertEquals(0L, plain[0], "奇偶位已满足 ⇒ 不翻转")
        assertEquals(1L, plain[1], "init 值：MULT * (0 ^ 0) + 1")
        assertEquals(Sfmt19937.STATE_SIZE.toLong(), plain[Sfmt19937.STATE_SIZE])
    }

    // ------------------------------------------------------------------
    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFFL) shl (8 * i))
        return v
    }
}
