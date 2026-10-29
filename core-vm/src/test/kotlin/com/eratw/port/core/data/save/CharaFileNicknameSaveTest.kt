package com.eratw.port.core.data.save

import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 总账 SAVE-04：角色档**不该**无条件写 `NICKNAME` / `MASTERNAME`。
 *
 * 原版读数（三条都取实现无关的独立证据，不用移植侧算出的值当期望）：
 * - 判据本体：`CharacterData.SaveToStreamBinary` 把 `IsSavedata && IsCharacterData && !IsGlobal`
 *   的每一项都交给 writer（CharacterData.cs:515-545），`NICKNAME` 也在其中 ——
 *   它们的 `IsSavedata` 本来就是**真**（`__SAVE_EXTENDED__` 那一支，VariableToken.cs:88-96；
 *   码值 VariableCode.cs:17/:132-133）。所以「注册表标错」这个假设不成立，
 *   `BuiltinSymbols.kt` 的 `isSavedata` 不许改。
 * - 真正拦住它们的是 writer：`EraBinaryDataWriter.WriteWithKey(string, object)` 全靠
 *   `v is long` / `v is string` 的运行时类型匹配分派、末尾没有 else
 *   （EraBinaryDataWriter.cs:83-173）；`dataString` 只有 4 格且是引用数组
 *   （CharacterData.cs:25/:58），ADDCHARA 原样拷 `tmpl.Nickname`/`.Mastername`
 *   （:134-135），而这两个模板字段只有 CSV 出现 `NICKNAME`/`あだ名`、
 *   `主人の呼び方`/`MASTERNAME` 行才被赋值（CharacterTemplate.cs:17/:19；
 *   ConstantData.cs:1591-1598）⇒ **没赋过值 = null ⇒ `null is string` 为假 ⇒ 整条不落盘**。
 * - 真档实测（`$ERATW_GAMEDIR/sav/save00.sav` 逐条目解析，168 个角色块）：每块分隔符之前
 *   恒 27 条系统条目（顺序见 [PC_BLOCK0_SYSTEM_KEYS]），其中**没有** `NICKNAME`；
 *   `MASTERNAME` 只在块 26 / 61 出现（八云紫 / 藤原妹红，值 = 昴），其余 166 块没有；
 *   `NAME`/`CALLNAME`/`ISASSI` 每块都有。仓内快照 `.workbuddy/sav-backup/sav-20261018-110513/save00.sav`
 *   与这份真档字节相同（sha256 前缀 27883f1663969468），同一读数另钉在
 *   `core/save/SaveFormatAuditTest` 的角色块一节。
 *
 * 移植侧没有 null 字符串表示（驻留池下标 0 恒为空串，`StringTable` 类文档写明
 * 「未赋值与空串在可观测语义上等价」），故 [CharaFileIO] 按「值为空串」判原版的 null：
 * 读档后这两列在内存里的期望值 = **空串**（= 原版 null 在移植侧的表示）。
 */
class CharaFileNicknameSaveTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 游戏根：`ERATW_GAMEDIR` 权威，回落 `ERATW_CORPUS`（指到 ERB 子树）的父目录。 */
    private fun gameDir(): Path? {
        System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        val corpus = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Path.of(corpus).parent
    }

    private fun idx(vararg v: Long): LongArray = v

    private fun keys(block: List<SaveEntry>): List<String> = block.map { it.key }

    private fun strOf(block: List<SaveEntry>, key: String): String? =
        block.filterIsInstance<SaveEntry.StrValue>().firstOrNull { it.key == key }?.value

    /** 一个角色：名字两列必给（原版 CSV 一定填），昵称/主人名默认空 = 原版没赋过值。 */
    private fun template(
        no: Long,
        name: String,
        callname: String,
        nickname: String = "",
        mastername: String = "",
    ): CharacterTemplate = CharacterTemplate(
        no = no, name = name, callname = callname, nickname = nickname, mastername = mastername,
        maxbase = mapOf(0 to 10L),
    )

    private fun storeWith(vararg templates: CharacterTemplate): VariableStore {
        val s = VariableStore()
        for (t in templates) s.addCharacter(t)
        return s
    }

    /** 注册表里按原版筛选条件应进档的系统角色变量名（CharacterData.cs:518 的判据）。 */
    private fun systemCharaSavedata(store: VariableStore): List<String> =
        store.registry.all().filter { it.isSavedata && it.isCharacterData && !it.isGlobal }.map { it.name }

    // ------------------------------------------------------------------
    // 写侧
    // ------------------------------------------------------------------

    /**
     * 没赋过值的两列不落档（真档读数：`NICKNAME` 全档 0 条、无主人的块没有 `MASTERNAME`）。
     * 同时钉住「`isSavedata` 仍为真」——修的是写侧的按值判定，不是注册表标签。
     */
    @Test
    fun `空昵称与空主人名的角色档不含这两列`() {
        val store = storeWith(template(1L, "アリス", "小爱"))
        // 注册表侧一个字都没改：这两列的 isSavedata 按原版就是真（VariableToken.cs:88-96）
        assertTrue(store.registry.mustDef("NICKNAME").isSavedata, "NICKNAME 的 isSavedata 保持真（不改 BuiltinSymbols）")
        assertTrue(store.registry.mustDef("MASTERNAME").isSavedata, "MASTERNAME 的 isSavedata 保持真（不改 BuiltinSymbols）")

        val block = CharaFileIO.capture(store, listOf(0L), "t").characters.single()
        assertFalse("NICKNAME" in keys(block), "昵称为空 ⇒ 整条不写（原版 null 走不通 WriteWithKey 的类型分派）")
        assertFalse("MASTERNAME" in keys(block), "主人名为空 ⇒ 整条不写")
        // 名字两列照常写（真档 168/168 块都有）
        assertEquals("アリス", strOf(block, "NAME"), "NAME 照常落档")
        assertEquals("小爱", strOf(block, "CALLNAME"), "CALLNAME 照常落档")
    }

    /**
     * 被赋过值的两列**照常**落档 —— 原版的判据是值而非名字。
     * 真档读数：只有真正有主人的两块（索引 26 / 61）写出 `MASTERNAME`，值 = 昴。
     */
    @Test
    fun `赋过值的两列照常落档`() {
        val store = VariableStore()
        store.addCharacter(template(1L, "八云 紫", "紫", nickname = "妮可"))
        val pos = store.addCharacter(template(2L, "藤原 妹红", "妹红"))
        store.writeStr("MASTERNAME", idx(pos.toLong()), "昴")

        val blocks = CharaFileIO.capture(store, listOf(0L, 1L), "t").characters
        assertEquals("妮可", strOf(blocks[0], "NICKNAME"), "CSV 真给了あだ名的角色照样写出（CharacterData.cs:134）")
        assertFalse("MASTERNAME" in keys(blocks[0]), "没主人的块不写 MASTERNAME")
        assertEquals("昴", strOf(blocks[1], "MASTERNAME"), "有主人的块写 MASTERNAME（真档块 61 同值）")
        assertFalse("NICKNAME" in keys(blocks[1]), "昵称为空的块不写 NICKNAME")
    }

    /**
     * 只省这两列，别的一个不少（防止「顺手排除过头」把系统条目写少了）。
     * 期望集 = **真档实测**的块 0 分隔符前 27 条（`sav/save00.sav` 逐条目解析所得，
     * 与 [PC_BLOCK0_SYSTEM_KEYS] 逐字相同）：ISASSI/NO + 21 个角色 1 维整数（BASE…TCVAR）
     * + NAME/CALLNAME + CSTR + CDFLAG。原版把这 27 条都交给 writer，且它们的值
     * 都不是 null（整数与数组是值类型/已分配实例，CharacterData.cs:57-62）。
     */
    @Test
    fun `除这两列外系统角色条目一个不少`() {
        val store = storeWith(template(1L, "アリス", "小爱"))
        val block = CharaFileIO.capture(store, listOf(0L), "t").characters.single()
        assertEquals(PC_BLOCK0_SYSTEM_KEYS.sorted(), keys(block).sorted(), "系统条目集应与真档块 0 的 27 条完全相同")
        assertEquals(27, block.size, "条目数 = 真档实测（分隔符前 27 条，无用户变量时正好就是这 27 条）")
        // 注册表侧的筛选集只比真档多这两列 ⇒ 说明除按值判定外没有第二条排除路径
        val savedata = systemCharaSavedata(store)
        assertEquals(
            setOf("NICKNAME", "MASTERNAME"),
            (savedata - PC_BLOCK0_SYSTEM_KEYS).toSet(),
            "移植侧按 CharacterData.cs:518 判据筛出的集合，与真档条目集之差恰好只有这两列",
        )
    }

    /**
     * 用户自定义 CHARADATA 变量不受影响：它们在 Separator 之后按自己的 isSavedata 写，
     * 原版那里没有「按值丢条目」的分支（`UserDefCVarDataList` 存的是数组实例，
     * CharacterData.cs:85-125 / :546-556；0 维串只有系统那 4 格）。
     */
    @Test
    fun `用户角色变量照常落档`() {
        val store = storeWith(template(1L, "アリス", "小爱"))
        store.declareUserVar(
            // SAVEDATA CHARADATA 字符串变量只在二进制存档下合法（UserDefinedVariableData.cs:465-475）
            DimParser.parse(
                "#DIMS", "CHARADATA SAVEDATA UC, 2",
                isPrivate = false, DimParseConfig(systemSaveInBinary = true),
            ),
        )
        store.writeStr("UC", idx(0L, 1L), "u")
        val block = CharaFileIO.capture(store, listOf(0L), "t").characters.single()
        assertTrue(SaveEntry.Separator in block, "用户变量前要有 Separator（CharacterData.cs:546-548）")
        val uc = block.filterIsInstance<SaveEntry.StrArray1>().firstOrNull { it.key == "UC" }
        assertNotNull(uc, "用户 CHARADATA 字符串变量照常写出（1 维落 StrArray1）")
        assertEquals("u", uc!!.values[1], "UC 元素值往返")
    }

    // ------------------------------------------------------------------
    // 读侧
    // ------------------------------------------------------------------

    /**
     * 读档后两列的期望值 = 空串（移植侧对原版 null 的表示，StringTable 下标 0）。
     * 原版是「新建 CharacterData → 四格全 null → 只填文件里出现的键」
     * （VariableEvaluator.cs:2311-2312 + CharacterData.cs:55-62 / :560-613），
     * 移植侧同一件事由 `CharacterTable.addBare` → `resetChar` 清零承担（CharacterTable.kt:138-143/:244-250），
     * 本用例钉桩：**没有复位点要接线**，读侧无需改动。
     */
    @Test
    fun `读档后两列落在移植侧的 null 表示上`() {
        val src = storeWith(template(1L, "アリス", "小爱"))
        val bytes = CharaFileIO.encode(src, listOf(0L), "t")

        val dst = VariableStore()
        assertTrue(CharaFileIO.load(bytes, dst), "LOADCHARA 应成功（RESULT=1，VariableEvaluator.cs:2316）")
        assertEquals("", dst.readStr("NICKNAME", idx(0)), "档内没这条 ⇒ 保持未赋值（移植侧 = 空串，原版 = null）")
        assertEquals("", dst.readStr("MASTERNAME", idx(0)), "同上")
        assertEquals("アリス", dst.readStr("NAME", idx(0)), "NAME 照常读回")
    }

    /** 赋过值的两列往返保真（写侧非空 → 档内有这条 → 读回原值）。 */
    @Test
    fun `两列非空时往返保真`() {
        val src = VariableStore()
        src.addCharacter(template(1L, "八云 紫", "紫", nickname = "妮可"))
        src.writeStr("MASTERNAME", idx(0), "昴")
        val bytes = CharaFileIO.encode(src, listOf(0L), "t")

        val dst = VariableStore()
        assertTrue(CharaFileIO.load(bytes, dst))
        assertEquals("妮可", dst.readStr("NICKNAME", idx(0)), "NICKNAME 往返")
        assertEquals("昴", dst.readStr("MASTERNAME", idx(0)), "MASTERNAME 往返")
    }

    /**
     * LOADCHARA 是**追加**新角色（原版 `new CharacterData` + `AddRange`，
     * VariableEvaluator.cs:2311-2315）：新位置的两列取未赋值表示，既有角色一格不动。
     */
    @Test
    fun `追加的角色不复用既有角色的昵称`() {
        val src = storeWith(template(1L, "アリス", "小爱"))
        val bytes = CharaFileIO.encode(src, listOf(0L), "t")

        val dst = storeWith(template(9L, "旧名", "旧呼", nickname = "旧昵称"))
        assertTrue(CharaFileIO.load(bytes, dst))
        assertEquals(2, dst.characters.count, "追加而不是覆盖（:2315）")
        assertEquals("旧昵称", dst.readStr("NICKNAME", idx(0)), "既有角色不受 LOADCHARA 影响")
        assertEquals("", dst.readStr("NICKNAME", idx(1)), "新位置的昵称 = 未赋值表示")
        assertEquals("", dst.readStr("MASTERNAME", idx(1)), "新位置的主人名 = 未赋值表示")
    }

    // ------------------------------------------------------------------
    // 往返稳定性
    // ------------------------------------------------------------------

    /** 写 → 读 → 再写：条目序列与字节都必须一样（省掉的两列在读侧同样是未赋值）。 */
    @Test
    fun `写读再写逐字节稳定`() {
        val src = VariableStore()
        src.addCharacter(template(1L, "アリス", "小爱"))
        val p1 = src.addCharacter(template(2L, "八云 紫", "紫", nickname = "妮可"))
        src.writeStr("MASTERNAME", idx(p1.toLong()), "昴")

        val first = CharaFileIO.encode(src, listOf(0L, 1L), "round trip")
        val back = VariableStore()
        assertTrue(CharaFileIO.load(first, back))
        val second = CharaFileIO.encode(back, listOf(0L, 1L), "round trip")

        val blockA = CharaFileIO.capture(src, listOf(0L, 1L), "round trip").characters
        val blockB = CharaFileIO.capture(back, listOf(0L, 1L), "round trip").characters
        assertEquals(blockA.map { b -> keys(b) }, blockB.map { b -> keys(b) }, "条目序列应逐块相同")
        assertTrue(
            first.contentEquals(second),
            "写→读→再写必须逐字节相同（首差见下）：${firstDiff(first, second)}",
        )
    }

    private fun firstDiff(a: ByteArray, b: ByteArray): String {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) if (a[i] != b[i]) {
            return "偏移 $i 期望=${a[i].toInt() and 0xFF} 实际=${b[i].toInt() and 0xFF}（长度 ${a.size}/${b.size}）"
        }
        return "长度不同 ${a.size} vs ${b.size}"
    }

    // ------------------------------------------------------------------
    // 与 PC 真档的结构对比（动态用例：拿不到 ERATW_GAMEDIR 就 skip）
    // ------------------------------------------------------------------

    /**
     * 真档结构：168 块，`NICKNAME` 0 条，`MASTERNAME` 只在块 26 / 61（值 = 昴），
     * `NAME`/`CALLNAME` 每块都有 —— 移植侧在同样三种状态（无昵称无主人 / 有昵称 / 有主人）
     * 下必须给出**完全相同**的这两列出现模式。真档只读，不拷进仓库。
     */
    @Test
    fun `真档角色块里这两列的出现模式与移植侧一致`() {
        val game = gameDir()
        Assumptions.assumeTrue(game != null, "缺少 ERATW_GAMEDIR / ERATW_CORPUS：跳过真档结构对比")
        val sav = game!!.resolve("sav").resolve("save00.sav")
        Assumptions.assumeTrue(Files.isRegularFile(sav), "缺少只读真档 $sav：跳过结构对比")
        val pc = SaveFormat.read(Files.readAllBytes(sav))

        assertEquals(168, pc.characters.size, "save00.sav 角色块数（实测）")
        val nicknameBlocks = pc.characters.indices.filter { "NICKNAME" in keys(pc.characters[it]) }
        val masterBlocks = pc.characters.indices.filter { "MASTERNAME" in keys(pc.characters[it]) }
        assertTrue(nicknameBlocks.isEmpty(), "全档 168 块都没有 NICKNAME（实测 0 次），实际块号 $nicknameBlocks")
        assertEquals(listOf(26, 61), masterBlocks, "只有这两块写了 MASTERNAME（实测 2 次）")
        assertEquals("昴", strOf(pc.characters[26], "MASTERNAME"), "块 26 的 MASTERNAME 实读值")
        assertEquals(
            27, pc.characters[0].indexOfFirst { it is SaveEntry.Separator },
            "块 0 的分隔符前有 27 条系统条目（= [PC_BLOCK0_SYSTEM_KEYS]）",
        )
        for ((i, block) in pc.characters.withIndex()) {
            assertTrue("NAME" in keys(block), "块 $i 有 NAME（实测 168/168）")
            assertTrue("CALLNAME" in keys(block), "块 $i 有 CALLNAME（实测 168/168）")
            // 原版 WriteWithKey 对 null 串整条不写 ⇒ 真档里出现的那两列必然非空
            strOf(block, "NICKNAME")?.let { assertTrue(it.isNotEmpty(), "块 $i 写出了空的 NICKNAME（原版 null 不可能落档）") }
            strOf(block, "MASTERNAME")?.let { assertTrue(it.isNotEmpty(), "块 $i 写出了空的 MASTERNAME") }
        }

        // 移植侧同状态：块 a（两者皆空）对齐 PC 块 0，块 b（有主人）对齐 PC 块 26 / 61
        val port = VariableStore()
        port.addCharacter(template(0L, "昴", "昴"))
        val withMaster = port.addCharacter(template(26L, "八云 紫", "紫"))
        port.writeStr("MASTERNAME", idx(withMaster.toLong()), "昴")
        val blocks = CharaFileIO.capture(port, listOf(0L, 1L), "t").characters
        assertEquals(
            keys(pc.characters[0]).filter { it == "NICKNAME" || it == "MASTERNAME" },
            keys(blocks[0]).filter { it == "NICKNAME" || it == "MASTERNAME" },
            "无昵称无主人的角色：这两列的出现模式必须与真档块 0 一致",
        )
        assertEquals(
            keys(pc.characters[26]).filter { it == "NICKNAME" || it == "MASTERNAME" },
            keys(blocks[1]).filter { it == "NICKNAME" || it == "MASTERNAME" },
            "有主人的角色：这两列的出现模式必须与真档块 26 一致",
        )
    }

    /**
     * `TODO(verify)`：名字两列在「**字面空串**」上的残差。
     * 原版 `tmpl.Name` 为空串（CSV 给了空 `名前`）时是非 null ⇒ 条目照样落档；
     * 移植侧空串与未赋值同格（池 0），本层对 NAME/CALLNAME 保持无条件写，
     * 于是「空名字」在档结构上比真档多一条目（读回值仍是空串，运行期不可观测）。
     * 卡点：真档里没有空名字的角色可参照（168/168 块 NAME 非空），
     * 要判死需要给字符串列加 null 位（`core/vars` 的驻留池表示，本路禁改）。
     */
    @Test
    fun `名字为空的残差按现状钉桩`() {
        val store = VariableStore()
        store.addCharacter(CharacterTemplate.pseudo(csvNo = 0L))
        val block = CharaFileIO.capture(store, listOf(0L), "t").characters.single()
        assertEquals("", strOf(block, "NAME"), "NAME 为空串时本层仍写这条（原版此处是否为 null 未验证）")
        assertFalse("NICKNAME" in keys(block), "同一状态下 NICKNAME 不写")
    }

    companion object {
        /**
         * 真档 `$ERATW_GAMEDIR/sav/save00.sav` 块 0 **分隔符之前**的 27 条系统条目，
         * 按文件内实际出现顺序逐条抄录（168 块同形；MASTERNAME 只在块 26 / 61 上多一条）。
         * 其中没有 `NICKNAME`、没有 `MASTERNAME` —— 即原版把这两列留给「值为 null ⇒ 整条不写」。
         */
        val PC_BLOCK0_SYSTEM_KEYS: List<String> = listOf(
            "ISASSI", "NO",
            "BASE", "MAXBASE", "ABL", "TALENT", "EXP", "MARK", "PALAM", "SOURCE", "EX",
            "CFLAG", "JUEL", "RELATION", "EQUIP", "TEQUIP", "STAIN", "GOTJUEL", "NOWEX",
            "DOWNBASE", "CUP", "CDOWN", "TCVAR",
            "NAME", "CALLNAME",
            "CSTR", "CDFLAG",
        )
    }
}
