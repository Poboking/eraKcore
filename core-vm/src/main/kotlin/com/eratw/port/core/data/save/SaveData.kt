package com.eratw.port.core.data.save

import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VarType
import com.eratw.port.core.vars.VariableStore

/**
 * 存档条目模型（对应原版 `EraSaveDataType` 的九种值类型 + 分隔符）。
 *
 * 每个条目 = `类型字节 + 变量名（7-bit 字节长度 + UTF-16LE）+ 值`，
 * 布局证据：`EraBinaryDataWriter.WriteWithKey`（EraBinaryDataWriter.cs:83-173）。
 *
 * 数组一律**行主序压平**（与 .NET 多维数组一致，spec: VariableData.cs:117）：
 * - [IntArray2]：`values[i * dim1 + j]`
 * - [IntArray3]：`values[(i * dim1 + j) * dim2 + k]`
 *
 * 字符串数组保留 `null`（原版跳过项读回为 null，spec: EraBinaryDataReader.cs:481-497）。
 */
sealed class SaveEntry {
    abstract val key: String

    /** 类型字节 0。 */
    data class IntValue(override val key: String, val value: Long) : SaveEntry()

    /** 类型字节 1。长度由 `values.size` 决定（原版先写 Int32 长度）。 */
    data class IntArray1(override val key: String, val values: LongArray) : SaveEntry()

    /** 类型字节 2。 */
    data class IntArray2(
        override val key: String,
        val dim0: Int,
        val dim1: Int,
        val values: LongArray,
    ) : SaveEntry()

    /** 类型字节 3。 */
    data class IntArray3(
        override val key: String,
        val dim0: Int,
        val dim1: Int,
        val dim2: Int,
        val values: LongArray,
    ) : SaveEntry()

    /** 类型字节 16。null 值在写侧落成空串（EraBinaryDataWriter.cs:334-344）。 */
    data class StrValue(override val key: String, val value: String) : SaveEntry()

    /** 类型字节 17。 */
    data class StrArray1(override val key: String, val values: Array<String?>) : SaveEntry()

    /** 类型字节 18。 */
    data class StrArray2(
        override val key: String,
        val dim0: Int,
        val dim1: Int,
        val values: Array<String?>,
    ) : SaveEntry()

    /** 类型字节 19。 */
    data class StrArray3(
        override val key: String,
        val dim0: Int,
        val dim1: Int,
        val dim2: Int,
        val values: Array<String?>,
    ) : SaveEntry()

    /** 类型字节 32。保持插入序（原版 .NET Dictionary 无删除时即插入序）。 */
    data class MapValue(override val key: String, val entries: List<Pair<String, String>>) : SaveEntry()

    /** 类型字节 33。`xml` = `XmlDocument.OuterXml` 原文。 */
    data class XmlValue(override val key: String, val xml: String) : SaveEntry()

    /** 类型字节 34。两段 .NET `DataTable.WriteXmlSchema` / `WriteXml` 产物。 */
    data class DataTableValue(
        override val key: String,
        val schemaXml: String,
        val dataXml: String,
    ) : SaveEntry()

    /** 类型字节 253：角色块内的用户自定义变量分界（CharacterData.cs:545-555）。 */
    object Separator : SaveEntry() {
        override val key: String get() = ""
    }
}

/**
 * 一份解析后的存档（二进制格式）。
 *
 * 字段与写入顺序一一对应 `VariableEvaluator.SaveToStreamBinary`
 * （VariableEvaluator.cs:2591-2607）：
 * ```
 * header(魔数,1808,0) | fileType | uniqueCode | scriptVersion | text
 *   NORMAL/CHARVAR: 角色数 + 每个角色（条目…, EOC=0xFE）
 *   NORMAL/GLOBAL/VAR: 全局条目…, EOF=0xFF
 *   NORMAL/GLOBAL: EM 条目…, EOF=0xFF
 * ```
 * 注意 `GLOBAL` 没有角色数（VariableEvaluator.cs:2483-2493），`CHARVAR` 只有角色块
 * （VariableEvaluator.cs:2248-2275），`VAR` 只有全局条目（VariableEvaluator.cs:2332-2353）。
 *
 * [sourceFile] / [sourcePayload] 是**未改动时的原样回写**依据：见 [SaveFormat.write] 的说明。
 *
 * [version] 只是**读入时容器头里那个 UInt32 的留档**（[SaveFormat.VERSION] 恒 1808，非 1808 的
 * 容器在读侧就被拒收）；写侧不吃它 —— 原版 `WriteHeader` 把 `1808u` 写死
 * （EraBinaryDataWriter.cs:45），与存档内容里的 [scriptVersion] 是两个互不相干的字段。
 */
class SaveData(
    val fileType: SaveFileKind,
    val uniqueCode: Long,
    val scriptVersion: Long,
    val text: String,
    val characters: List<List<SaveEntry>> = emptyList(),
    val variables: List<SaveEntry> = emptyList(),
    val emData: List<SaveEntry> = emptyList(),
    val compressed: Boolean = true,
    /** 读入时容器头的版本字段（只认 1808）；写侧恒按常量 1808 落盘，与本值无关（EraBinaryDataWriter.cs:45）。 */
    val version: Int = SaveFormat.VERSION,
    internal val sourceFile: ByteArray? = null,
    internal val sourcePayload: ByteArray? = null,
) {
    /** 便捷入口：把存档还原进变量池。返回实际写入/跳过的条目数。 */
    fun applyTo(
        store: VariableStore,
        restoreCharacters: Boolean = true,
        setLastLoad: Boolean = true,
    ): RestoreReport {
        var applied = 0
        var skipped = 0

        // 伪变量 LASTLOAD_VERSION / LASTLOAD_TEXT 的赋值点（VariableEvaluator.cs:2627-2629）。
        // LOADDATA 写它们；LOADCHARA 不写（LoadChara 全程不碰 LASTLOAD_*，:2285-2330），
        // 故角色存档还原时传 setLastLoad = false。
        if (setLastLoad) {
            store.lastLoadVersion = scriptVersion
            store.lastLoadText = text
        }

        var charBase = 0
        if (restoreCharacters && (fileType == SaveFileKind.NORMAL || fileType == SaveFileKind.CHARVAR)) {
            if (fileType == SaveFileKind.NORMAL) store.deleteAllCharacters()
            charBase = store.characters.count
            repeat(characters.size) { store.characters.addBare() }
            for ((ci, block) in characters.withIndex()) {
                val pos = (charBase + ci).toLong()
                for (e in block) {
                    if (e is SaveEntry.Separator) continue
                    if (applyEntry(store, e, pos)) applied++ else skipped++
                }
            }
        } else if (restoreCharacters) {
            skipped += characters.sumOf { it.size }
        }

        for (e in variables) {
            if (applyEntry(store, e, null)) applied++ else skipped++
        }
        // Map/Xml/DT 由各自的池消费（A2/A3），存档层只负责原样保留。
        skipped += emData.size

        return RestoreReport(applied, skipped, characters.size)
    }

    /**
     * 单条目写回变量池。
     *
     * 未知变量（存档里有、当前游戏没声明）与类型/维度不符一律返回 false 而不是抛错——
     * 原版 `LoadVariableBinary` 对未知键也是「读掉值再丢弃」（VariableData.cs:4081-4160）。
     *
     * 两个口径对齐原版：
     * - 查找只用**静态用户变量**（`userVar(key, null)`）：原版 `LoadVariableBinary` 走
     *   `GetVariableType` 的固定排除表，函数内 `#DIM PRIVATE` / 局部变量从不参与存档还原
     *   （VariableData.cs:4089-4091 排除 IsPrivate/IsLocal/IsCalc）；运行期帧里若有同名
     *   私有变量，按名查找会错把存档值写进帧里。
     * - `isConst` 条目跳过：原版常量变量 `IsConst` 不带 setter、存档读取同样排除
     *   （VariableData.cs:4089-4091 的排除条件含常量路径）。
     *
     * needInit 尾段清零：原版已知变量一律 `needInit: true` 读入（VariableData.cs:4145-4206），
     * 读到文件 EOF 记号后把「文件长度 ≤ 当前声明长度」的剩余元素清零/置 null
     * （EraBinaryDataReader.cs:161-166 一维、:270-277 二维、:430-437 三维、:501-506 字符串一维
     * 置 null——移植侧按已登记偏离把 null 落成空串，spec/save-format.md:47）；
     * 但若声明尺寸**小于**文件尺寸，原版走临时数组、只把 min 区域拷回，
     * 剩余部分**保留旧值**（EraBinaryDataReader.cs:114-118 临时数组、:172-173 / :280-291 / :447-458
     * 拷回 min 区域）。因此移植侧的清零必须以「各维 store ≥ 各维 file」为前提，
     * 否则会把原版会保留的旧值抹掉。
     */
    private fun applyEntry(store: VariableStore, e: SaveEntry, charPos: Long?): Boolean {
        if (e is SaveEntry.Separator) return false
        if (e is SaveEntry.MapValue || e is SaveEntry.XmlValue || e is SaveEntry.DataTableValue) return false
        // 只在系统注册表与静态用户变量里找（见 KDoc 的排除表口径）。
        val def = store.registry.def(e.key) ?: store.userVar(e.key, null)?.def ?: return false
        if (def.isConst) return false
        if (charPos != null && !def.isCharacterData) return false
        if (charPos == null && def.isCharacterData) return false

        val wantStr = e is SaveEntry.StrValue || e is SaveEntry.StrArray1 ||
            e is SaveEntry.StrArray2 || e is SaveEntry.StrArray3
        if ((def.type == VarType.STR) != wantStr) return false

        /** 角色变量前置角色位置；全局变量无前置。 */
        fun indices(vararg rest: Long): LongArray =
            if (charPos != null) longArrayOf(charPos) + rest else longArrayOf(*rest)

        /** 数组写入按当前声明尺寸截断（原版尺寸不足时会扩容，本层不扩容）。 */
        fun intAt(i: Long, v: Long): Boolean {
            if (def.dims.isEmpty()) return false
            if (i < 0 || i >= def.dims[0]) return false
            store.writeInt(e.key, indices(i), v)
            return true
        }

        return when (e) {
            is SaveEntry.IntValue -> {
                if (def.dims.isNotEmpty()) return false
                store.writeInt(e.key, indices(), e.value); true
            }
            is SaveEntry.IntArray1 -> {
                if (def.dims.size != 1) return false
                // needInit：声明长度 ≥ 文件长度时，文件内容之后的尾段全清零
                // （EraBinaryDataReader.cs:161-166，EOF 后 for(; i < num2; i++) refArray[i] = 0）。
                val d0 = def.dims[0]
                var n = 0
                for (i in 0 until d0) {
                    val v = if (i < e.values.size) e.values[i] else 0L
                    if (intAt(i.toLong(), v)) n++
                }
                n > 0 || e.values.isEmpty()
            }
            is SaveEntry.IntArray2 -> {
                if (def.dims.size != 2) return false
                val d0 = def.dims[0]
                val d1 = def.dims[1]
                var n = 0
                for (i in 0 until minOf(e.dim0, d0)) {
                    for (j in 0 until minOf(e.dim1, d1)) {
                        store.writeInt(e.key, indices(i.toLong(), j.toLong()), e.values[i * e.dim1 + j])
                        n++
                    }
                }
                // 行尾 + 末尾整行清零，但仅在 store 各维 ≥ file 各维时
                // （EraBinaryDataReader.cs:270-277；声明更小时原版走临时数组、
                // 只拷回 min 区域、其余保留旧值，:280-291）。
                if (d0 >= e.dim0 && d1 >= e.dim1) {
                    for (i in 0 until d0) {
                        val jFrom = if (i < e.dim0) e.dim1 else 0
                        for (j in jFrom until d1) {
                            store.writeInt(e.key, indices(i.toLong(), j.toLong()), 0L)
                            n++
                        }
                    }
                }
                n > 0 || e.values.isEmpty()
            }
            is SaveEntry.IntArray3 -> {
                if (def.dims.size != 3) return false
                val d0 = def.dims[0]
                val d1 = def.dims[1]
                val d2 = def.dims[2]
                var n = 0
                for (i in 0 until minOf(e.dim0, d0)) {
                    for (j in 0 until minOf(e.dim1, d1)) {
                        for (k in 0 until minOf(e.dim2, d2)) {
                            store.writeInt(
                                e.key, indices(i.toLong(), j.toLong(), k.toLong()),
                                e.values[(i * e.dim1 + j) * e.dim2 + k],
                            )
                            n++
                        }
                    }
                }
                // 同二维：清零以 store 各维 ≥ file 各维为前提（EraBinaryDataReader.cs:430-437）。
                if (d0 >= e.dim0 && d1 >= e.dim1 && d2 >= e.dim2) {
                    for (i in 0 until d0) {
                        val jFrom = if (i < e.dim0) e.dim1 else 0
                        for (j in jFrom until d1) {
                            val kFrom = if (i < e.dim0 && j < e.dim1) e.dim2 else 0
                            for (k in kFrom until d2) {
                                store.writeInt(e.key, indices(i.toLong(), j.toLong(), k.toLong()), 0L)
                                n++
                            }
                        }
                    }
                }
                n > 0 || e.values.isEmpty()
            }
            is SaveEntry.StrValue -> {
                if (def.dims.isNotEmpty()) return false
                store.writeStr(e.key, indices(), e.value); true
            }
            is SaveEntry.StrArray1 -> {
                if (def.dims.size != 1) return false
                // needInit：整个声明范围写满——文件内容之后置 null，移植侧按登记偏离落空串
                // （EraBinaryDataReader.cs:501-506 置 null；spec/save-format.md:47）。
                val d0 = def.dims[0]
                var n = 0
                for (i in 0 until d0) {
                    val v = if (i < e.values.size) (e.values[i] ?: "") else ""
                    store.writeStr(e.key, indices(i.toLong()), v)
                    n++
                }
                n > 0 || e.values.isEmpty()
            }
            is SaveEntry.StrArray2 -> {
                if (def.dims.size != 2) return false
                val d0 = def.dims[0]
                val d1 = def.dims[1]
                var n = 0
                for (i in 0 until minOf(e.dim0, d0)) {
                    for (j in 0 until minOf(e.dim1, d1)) {
                        store.writeStr(
                            e.key, indices(i.toLong(), j.toLong()),
                            e.values[i * e.dim1 + j] ?: "",
                        )
                        n++
                    }
                }
                if (d0 >= e.dim0 && d1 >= e.dim1) {
                    for (i in 0 until d0) {
                        val jFrom = if (i < e.dim0) e.dim1 else 0
                        for (j in jFrom until d1) {
                            store.writeStr(e.key, indices(i.toLong(), j.toLong()), "")
                            n++
                        }
                    }
                }
                n > 0 || e.values.isEmpty()
            }
            is SaveEntry.StrArray3 -> {
                if (def.dims.size != 3) return false
                val d0 = def.dims[0]
                val d1 = def.dims[1]
                val d2 = def.dims[2]
                var n = 0
                for (i in 0 until minOf(e.dim0, d0)) {
                    for (j in 0 until minOf(e.dim1, d1)) {
                        for (k in 0 until minOf(e.dim2, d2)) {
                            store.writeStr(
                                e.key, indices(i.toLong(), j.toLong(), k.toLong()),
                                e.values[(i * e.dim1 + j) * e.dim2 + k] ?: "",
                            )
                            n++
                        }
                    }
                }
                if (d0 >= e.dim0 && d1 >= e.dim1 && d2 >= e.dim2) {
                    for (i in 0 until d0) {
                        val jFrom = if (i < e.dim0) e.dim1 else 0
                        for (j in jFrom until d1) {
                            val kFrom = if (i < e.dim0 && j < e.dim1) e.dim2 else 0
                            for (k in kFrom until d2) {
                                store.writeStr(e.key, indices(i.toLong(), j.toLong(), k.toLong()), "")
                                n++
                            }
                        }
                    }
                }
                n > 0 || e.values.isEmpty()
            }
            is SaveEntry.MapValue, is SaveEntry.XmlValue, is SaveEntry.DataTableValue, SaveEntry.Separator -> false
        }
    }
}

/**
 * [SaveData.applyTo] 的统计。
 *
 * @property applied  成功写进变量池的条目数
 * @property skipped  当前游戏不认识 / 类型不符 / 由其它池消费的条目数
 * @property characters 存档中的角色数
 */
data class RestoreReport(val applied: Int, val skipped: Int, val characters: Int)
