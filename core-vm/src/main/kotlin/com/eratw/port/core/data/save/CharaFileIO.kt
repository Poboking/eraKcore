package com.eratw.port.core.data.save

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.VarType
import com.eratw.port.core.vars.VariableStore

/**
 * 角色存档文件（`{DatDir}chara_<name>.dat`）的双向往返内核。
 *
 * 对齐原版 `VariableEvaluator.SaveChara` / `LoadChara`（VariableEvaluator.cs:2248-2330）：
 * - write：`header(魔数,1808,0) | FileType=CharVar | UniqueCode | ScriptVersion | text
 *   | 角色数 | 每个角色（SaveToStreamBinary 条目… + EOC）| EOF`。
 *   每个角色的条目集 = `varData.GetVarTokenDic()` 里
 *   `IsSavedata && IsCharacterData && !IsGlobal` 的项（CharacterData.cs:513-545），
 *   但**逐条落盘前还要过 `EraBinaryDataWriter.WriteWithKey` 的类型分派**
 *   （EraBinaryDataWriter.cs:83-173）：值为 `null` 的 0 维字符串格**整条不落盘**
 *   （类型字节和键都不写，总账 SAVE-04，见 [isNullInOriginal]）。
 *   系统变量写完后若有用户自定义角色变量则先写 Separator 再逐个写（:546-556）。
 * - read：类别必须是 CharVar、UniqueCode 相符、CheckVersion 通过，才把角色**追加**进角色表
 *   （不清空），成功置 `RESULT=1`（:2300-2317）。
 *
 * 字节布局与容器头由 [SaveFormat] 负责；本对象只做「变量池 ↔ 角色块」的映射。
 * CHARVAR 的条目顺序 = [com.eratw.port.core.vars.VariableRegistry.allInVarTokenDicOrder]
 * 的 varTokenDic 字典枚举序（已含角色段序，对齐 VariableData.cs:3074-3102），与原版
 * CharacterData.cs:515 的 GetVarTokenDic 遍历序同构；XML/键名回读不依赖顺序。
 */
object CharaFileIO {

    /** `chara_` 文件名前缀（VariableEvaluator.cs:2044-2047）。 */
    const val PREFIX = "chara_"

    /** 角色存档文件名：`chara_<savename>.dat`。spec: VariableEvaluator.cs:2044-2047 */
    fun fileNameOf(savename: String): String = "$PREFIX$savename.dat"

    /**
     * 原版「按值决定写不写」的角色 0 维字符串列（= `dataString` 里 `strCount = 2`
     * 之外的两格，CharacterData.cs:25 / :58 / :134-135）。判读见 [isNullInOriginal]。
     */
    private val NULLABLE_CHARA_STR0 = setOf("NICKNAME", "MASTERNAME")

    /**
     * 把 [nos] 指定的角色打包成 CHARVAR 存档对象（未落盘）。spec: VariableEvaluator.cs:2248-2283
     *
     * 参数校验完全按原版 `SAVECHARA` 指令的**逐元素顺序**（FunctionIdentifier.cs:1876-1896），
     * 且对每个元素一次性跑完全部检查再进下一个：
     * 1. 负值 → [CodeEE]（toUInt32inArg，:4320-4325，消息 `ArgIsNegative`：
     *    lang/emuera-zhs.xml:913「{0}函数: 第{1}个参数被是负值({2})」）；
     * 2. 超过 Int.MaxValue → [CodeEE]（:4326-4329，消息 `ArgIsTooLarge`：:915）；
     * 3. ≥ CHARANUM → [CodeEE]（OoRSavecharaArg，:1884-1887，消息：:524）；
     * 4. 与前面元素重复 → [CodeEE]（DuplicateCharaNo，:1888-1894，消息：:525）。
     * 实参序从 3 起算（前两个实参是文件名与描述，:1877-1884），故角色号是第 `i + 3` 个参数。
     * 全部通过才调 `SaveChara`（:1895-1896）。
     *
     * @throws CodeEE 按上述顺序逐元素校验失败
     */
    fun capture(
        store: VariableStore,
        nos: List<Long>,
        text: String,
        compressed: Boolean = true,
    ): SaveData {
        val gb = store.registry.gameBase
        val count = store.characters.count.toLong()
        val seen = HashSet<Long>()
        for ((i, no) in nos.withIndex()) {
            val argnum = i + 3 // 原版实参序 = (文件名, 描述, 角色号…)：第 3 个起是角色号
            if (no < 0) {
                throw CodeEE("SAVECHARA函数: 第${argnum}个参数被是负值($no)")
            }
            if (no > Int.MAX_VALUE) {
                throw CodeEE("SAVECHARA函数: 第${argnum}个参数($no)过大")
            }
            if (no >= count) {
                throw CodeEE("SAVECHARA第${argnum}参数的值超出角色登记编号的范围")
            }
            if (!seen.add(no)) {
                throw CodeEE("同一个角色登记编号($no)被多次指定")
            }
        }
        val blocks = nos.map { no -> captureCharacter(store, no.toInt()) }
        return SaveData(
            fileType = SaveFileKind.CHARVAR,
            uniqueCode = gb.gameCode,
            scriptVersion = gb.version,
            text = text,
            characters = blocks,
            compressed = compressed,
        )
    }

    /** [capture] + [SaveFormat.write]：直接得到可写盘的字节。 */
    fun encode(
        store: VariableStore,
        nos: List<Long>,
        text: String,
        compressed: Boolean = true,
    ): ByteArray = SaveFormat.write(capture(store, nos, text, compressed))

    /**
     * 把角色存档字节还原进变量池（**追加**角色，不清空既有角色）。
     *
     * @param scriptVersionDefined `GameBase.ScriptVersionDefined`（GameBase.cs:25），
     *   由调用方从 [com.eratw.port.core.save.SaveWiring] 交出；null = 走近似口径，
     *   见 [GameBaseGate.checkVersion]。
     * @return true = 版本检查通过并已写入（原版置 `RESULT=1`，spec: VariableEvaluator.cs:2316）；
     *   false = **不是二进制容器** / 类别不符 / UniqueCode 不符 / 版本不符
     *   （原版这四条同一条 `if` 里静默 `return`、`RESULT` 保持 0，:2299-2303 + :2305）。
     *
     * 「不是二进制容器」这一条 M10-SAVE 已按 :2300 收口：`CreateReader` 返回 null
     * （文本档、魔数不符、容器版本非 1808、文件短于 16 字节 `EraBinaryDataReader.cs:790-794`）
     * 时原版**不抛**，移植侧此前直接调 [SaveFormat.read] 会多抛一层 [SaveFormatException]
     * （经 `LOADCHARA` 冒成 `CodeEE`）。容器判定用 [SaveFormat.isBinaryContainer]，
     * 与 [com.eratw.port.core.save.SaveCodec.isBinary] 同一个口径。
     * `CHKCHARADATA` 侧的类别/游戏码/版本比对已同步走 [GameBaseGate]（组长接线），两侧不再各写一份近似；
     * 它按 [SaveFormat.peek] 判容器，文本档由 peek 的双格式解析承担，与本条回落同口径。
     */
    fun load(bytes: ByteArray, store: VariableStore, scriptVersionDefined: Boolean? = null): Boolean {
        if (!SaveFormat.isBinaryContainer(bytes)) return false // :2300（CreateReader == null）
        // 容器壳完好但 payload 损坏：原版 LoadChara 只包 try/finally（VariableEvaluator.cs:2295-2329），
        // CharacterData.LoadFromStreamBinary 的 default 分支抛 FileEE(AbnormalData)（CharacterData.cs:670-671）
        // 冒泡到 DoScript 顶层 catch（Process.cs:462-476）受控进 Error 态。移植侧 SaveFormatException
        // 不是 CodeEE，会裸穿语句层（ProcessRuntime 只盖章 CodeEE/ExprEvalException）⇒ 收敛成 CodeEE
        // 走同一受控失败面（2026-10-26 fixcore 审查 save-B2；文本档/魔数不符/壳损坏三条已由
        // isBinaryContainer 收口，只剩这一角）。
        val data = try {
            // 与 LOADDATA 同一条「不存在的变量不建数组」口径（见 SaveFormat.read 的 keepKey）：
            // 角色档同样可能来自旧版本，已删除的角色变量按存档尺寸建数组既白花堆也可能顶穿预算。
            SaveFormat.read(bytes) { k -> store.registry.def(k) != null || store.userVar(k, null) != null }
        } catch (e: SaveFormatException) {
            throw CodeEE(e.message ?: "存档数据异常")
        }
        if (data.fileType != SaveFileKind.CHARVAR) return false // :2300
        val gb = store.registry.gameBase
        if (!GameBaseGate.uniqueCodeEqualTo(gb, data.uniqueCode)) return false // :2300
        if (!GameBaseGate.checkVersion(gb, data.scriptVersion, scriptVersionDefined)) return false // :2305
        data.applyTo(store, restoreCharacters = true, setLastLoad = false) // 追加角色块，:2315
        return true
    }

    // ------------------------------------------------------------------
    // 单个角色 → 条目块
    // ------------------------------------------------------------------
    private fun captureCharacter(store: VariableStore, pos: Int): List<SaveEntry> {
        val out = ArrayList<SaveEntry>()
        // 系统角色变量：按 varTokenDic 字典枚举序（CharacterData.cs:515 的 GetVarTokenDic 遍历），
        // 取 IsSavedata && IsCharacterData && !IsGlobal；值按原版为 null 的格整条不写（[isNullInOriginal]）。
        // 此前用 registry.all() 的分组构造序：改档整档重写（SAVECHARA/SAVEDATA 经 encode）时角色段
        // 与 PC 产字节不同序，破坏 specs/save-format.md §5.3 的逐字节目标
        // （2026-10-26 fixcore 审查 save-B3；allInVarTokenDicOrder 已含角色段序）。
        for (def in store.registry.allInVarTokenDicOrder()) {
            if (!def.isSavedata || !def.isCharacterData || def.isGlobal) continue
            if (isNullInOriginal(store, def, pos)) continue
            out.add(systemEntry(store, def, pos))
        }
        // 用户自定义角色变量：原版在一个 Separator 之后逐个写。spec: CharacterData.cs:546-556
        val userChara = store.savedUserVarList().filter {
            it.def.isSavedata && it.def.isCharacterData && !it.def.isGlobal
        }
        if (userChara.isNotEmpty()) {
            out.add(SaveEntry.Separator)
            for (uv in userChara) out.add(userEntry(uv, pos))
        }
        return out
    }

    /**
     * 原版在这一格**根本不会落出条目**时为 true（总账 SAVE-04）。
     *
     * 判据不是「`IsSavedata` 为假」，也不是「写侧清单里硬缺这两列」——
     * `NICKNAME`/`MASTERNAME` 都带 `__SAVE_EXTENDED__`，`VariableToken` 构造里
     * `(Code & __SAVE_EXTENDED__) == __SAVE_EXTENDED__` 这一支直接置 `IsSavedata = true`
     * （VariableToken.cs:88-96；码值 VariableCode.cs:17/:132-133，移植侧
     * [com.eratw.port.core.vars.VariableCode] 同值），`CharacterData.SaveToStreamBinary`
     * 的遍历也确实把它们交给了 writer（CharacterData.cs:515-529）。
     *
     * 真正把它们挡在档外的是 writer：`EraBinaryDataWriter.WriteWithKey(string key, object v)`
     * 全程用 `v is long` / `v is string` … 的**运行时类型匹配**分派，末尾没有 `else`
     * （EraBinaryDataWriter.cs:83-173）。`dataString` 是 `new string[4]`
     * （CharacterData.cs:58），`CharacterTemplate.Nickname`/`.Mastername` 是普通引用字段、
     * 只有 CSV 里出现 `NICKNAME`/`あだ名`、`主人の呼び方`/`MASTERNAME` 行才被赋值
     * （CharacterTemplate.cs:17/:19；ConstantData.cs:1591-1598），ADDCHARA 时原样拷进
     * `dataString[2]`/`dataString[3]`（CharacterData.cs:134-135）——**没赋过值就是 null**，
     * 而 `null is string` 为假 ⇒ 类型字节、键、值三段一个字节都不写。
     * 于是原版只在「这一格被赋过值」的角色块里出现这两列。真档实测
     * （`sav/save00.sav` 逐条目解析，168 个角色块）：块 0 的分隔符之前恒 **27** 条系统条目
     * （ISASSI/NO + 21 个角色 1 维整数 + NAME/CALLNAME + CSTR + CDFLAG），里面**没有**
     * `NICKNAME`；`MASTERNAME` 只在块 26 / 61 出现（八云紫 / 藤原妹红，值 = 昴），其余 166 块没有。
     * 同一读数已钉在 `core/save/SaveFormatAuditTest` 的角色块一节。
     *
     * `NAME`/`CALLNAME` 不在此列：原版的**位置化正文**（`strCount = 2`，CharacterData.cs:25
     * 与 :351-356 的两次 `for (i < 2)`）把它们当「一定存在」的两格，写侧也只有这两格是
     * 无条件落盘的；移植侧因此对这两格保持无条件写。
     *
     * 移植侧的表示（**已知偏离**）：字符串走驻留池、下标 0 恒为空串，「未赋值」与「空串」
     * 在运行期语义上等价（StringTable 类文档），没有 null 位可查。所以这里按
     * 「值为空串」判原版的 null。偏离只在存档结构上可见，且不丢信息：
     * - 空串条目被省掉 → 读侧该格仍是池 0 = 空串（`SaveData.applyTo` 按名字查条目、
     *   缺项不动；角色位由 `CharacterTable.addBare` → `resetChar` 先清零，
     *   对齐原版 `new CharacterData(constant, varData)` 后 `LoadFromStreamBinary`
     *   只填文件里出现的键，VariableEvaluator.cs:2311-2312 + CharacterData.cs:560-613）；
     * - 被赋过值的格（含 CSV 真给了 `あだ名` 的角色）非空 ⇒ 照常写出，与原版一致。
     */
    private fun isNullInOriginal(store: VariableStore, def: com.eratw.port.core.vars.VarDef, pos: Int): Boolean {
        if (def.storage != com.eratw.port.core.vars.Storage.C_STR0) return false
        if (def.name.uppercase() !in NULLABLE_CHARA_STR0) return false
        return store.pool.lookup(store.characters.str0[def.index].get(pos, 0)).isEmpty()
    }

    /** 系统角色变量：直接读列存储（绕开 indices 口径，避免角色位置混入元素下标）。 */
    private fun systemEntry(store: VariableStore, def: com.eratw.port.core.vars.VarDef, pos: Int): SaveEntry {
        val c = store.characters
        return when (def.storage) {
            com.eratw.port.core.vars.Storage.C_INT0 ->
                SaveEntry.IntValue(def.name, c.int0[def.index].get(pos, 0))
            com.eratw.port.core.vars.Storage.C_INT1 ->
                intEntry(def, c.int1[def.index].blockSize, { e -> c.int1[def.index].get(pos, e) })
            com.eratw.port.core.vars.Storage.C_INT2 ->
                intEntry(def, c.int2[def.index].blockSize, { e -> c.int2[def.index].get(pos, e) })
            com.eratw.port.core.vars.Storage.C_STR0 ->
                SaveEntry.StrValue(def.name, store.pool.lookup(c.str0[def.index].get(pos, 0)))
            com.eratw.port.core.vars.Storage.C_STR1 ->
                strEntry(def, c.str1[def.index].blockSize, { e -> store.pool.lookup(c.str1[def.index].get(pos, e)) })
            else -> error("非角色变量存储 $def")
        }
    }

    /** 用户自定义角色变量：走 [UserVar.readInt]/readStr（elem 为行主序扁平下标）。 */
    private fun userEntry(uv: com.eratw.port.core.vars.UserVar, pos: Int): SaveEntry {
        val def = uv.def
        val n = def.totalSize
        return if (def.type == VarType.INT) {
            intEntry(def, n) { e -> uv.readInt(pos, e) }
        } else {
            strEntry(def, n) { e -> uv.readStr(pos, e) }
        }
    }

    private fun intEntry(def: com.eratw.port.core.vars.VarDef, n: Int, read: (Int) -> Long): SaveEntry =
        when (def.dims.size) {
            0 -> SaveEntry.IntValue(def.name, read(0))
            1 -> SaveEntry.IntArray1(def.name, LongArray(n) { read(it) })
            2 -> SaveEntry.IntArray2(def.name, def.dims[0], def.dims[1], LongArray(def.dims[0] * def.dims[1]) { read(it) })
            else -> error("角色变量 ${def.name} 的维度 ${def.dims.size} 不受支持")
        }

    private fun strEntry(def: com.eratw.port.core.vars.VarDef, n: Int, read: (Int) -> String): SaveEntry =
        when (def.dims.size) {
            0 -> SaveEntry.StrValue(def.name, read(0))
            1 -> SaveEntry.StrArray1(def.name, Array<String?>(n) { read(it) })
            2 -> SaveEntry.StrArray2(
                def.name, def.dims[0], def.dims[1],
                Array<String?>(def.dims[0] * def.dims[1]) { read(it) },
            )
            else -> error("角色变量 ${def.name} 的维度 ${def.dims.size} 不受支持")
        }

    // ------------------------------------------------------------------
    // 校验：口径本体在 [GameBaseGate]（GameBase.cs:60-80），本层不再各写一份近似。
    // ------------------------------------------------------------------
}
