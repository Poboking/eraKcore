package com.eratw.port.perf

import com.eratw.port.core.vars.CharacterTable
import com.eratw.port.core.vars.UserVar
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

// ===========================================================================
// OBS-VARPOOL 变量池子块拆分：把 `PhaseFacets` 面 10 的四个粗块拆到**可判性的子块**。
//
// 为什么要拆：上一路（obs-phasetitle，已合 main）只给到 `CharacterTable` 51.9 / `StringTable` 25.6 /
// `privateVars` 7.8 / 全局用户四表 5.0 四个粗块，于是「+58.9 MiB 是角色数在长，还是容量在预留」
// 这件事读不出来 —— 二者在产品侧的处置完全不同（前者是原版口径、后者是移植侧欠账）。
//
// 三条口径纪律（与 `HeapRootAttributionProbe` / `PhaseFacets` 同源，一条都不放宽）：
// 1. **只读**：全部走公开字段（`CharacterTable.count/allocatedCapacity/rowBytes()`）或反射取值，
//    **一个字段都不写**，尤其**不调 `UserVar.rawArray()`** —— 静态用户变量的 `rawArray()` 走的是
//    `arr()`（`UserVariables.kt:1613` + `:1595-1598` 的懒分配），调一次就把没写过的列**物化**出来，
//    装置就从「观察」变成「参与」。懒分配的真值一律反射读 `data`（null 就是 null，计 0）。
// 2. **子块互斥**：`bytes` 里的键两两不重叠，求和 = 变量池数据面的**下界**（深对象图仍由面 10 的
//    遍历行负责，见 [VARS_SUB_BLOCKS] 的 note）。所以这里的行全部按 `枚举-精确公式(不入账)` 出行，
//    不许与遍历行相加，也不改动 `RootLedger` 的恒等式。
// 3. **预留与已用分开**：列式存储的容量冗余（`allocatedCapacity` × 行字节 - `count` × 行字节）
//    单列一行，因为它是「移植侧过度预留（乙）」与「原版口径就是按需」（甲）的分水岭。
// ===========================================================================

/** 变量池子块的字节键（差分表按这个键对齐，所以名字写死成常量）。 */
internal object VarPoolBlock {
    /** 角色表整数列（`int0`/`int1`/`int2` 的全部 `LongArray`；含用户 `#DIM CHARADATA` 追加列）。 */
    const val CHARA_INT = "10.1 角色表·整数列 long"

    /** 角色表字符串列（`StrCol.data` = 驻留池下标的 `IntArray`）。 */
    const val CHARA_STR = "10.2 角色表·字符串列 池下标"

    /** 系统全局整数列（`gInt1/gInt2/gInt3` 已分配的列 + `itemPriceSource`）。 */
    const val SYS_INT = "10.3 系统全局·整数列(惰性已分配)"

    /** 系统全局字符串列（`gStr1` + `gStr0`，存驻留池下标）。 */
    const val SYS_STR = "10.4 系统全局·字符串列"

    /** 全局/普通用户 `#DIM` 的数据数组（`userVars`，角色变量列除外 —— 那些在 10.1/10.2）。 */
    const val USER_GLOBAL = "10.5 用户变量·全局与普通数据数组"

    /** 私有用户变量 `privateVars` 的数据数组（含动态变量的作用域栈）。 */
    const val USER_PRIV = "10.6 用户变量·私有数据数组(含动态栈)"

    /** 字符串驻留池：`values` 背衬 + `ids` 哈希表 + 池中串实例本体。 */
    const val STR_POOL = "10.7 串表·驻留池(背衬+哈希+串本体)"

    /** 局部变量态：`localsByFunc` 的实例数组 + 帧与帧池。 */
    const val LOCALS = "10.8 局部态·localsByFunc+帧池"

    /** 绑定/名字记忆缓存与句柄表（点名的几张）。 */
    const val BIND_CACHE = "10.9 绑定与记忆缓存"

    /** store 上未被上面点名的引用字段（只计浅尺寸与数组本体，不追深图）。 */
    const val REST = "10.10 其余字段(浅尺寸+数组本体，不追深图)"

    /** 子块求和时用的顺序（出行顺序 = 这个顺序）。 */
    val ORDER = listOf(
        CHARA_INT, CHARA_STR, SYS_INT, SYS_STR, USER_GLOBAL, USER_PRIV,
        STR_POOL, LOCALS, BIND_CACHE, REST,
    )
}

/**
 * `java.util.ArrayList` 的 1.5× 扩容上界（JDK `grow()`：`oldCapacity + (oldCapacity >> 1)`），
 * 从缺省容量起步。只用来在**反射读不到 `elementData`**（JDK 强封装，实测恒不可访问）时
 * 给一个可判的预留上界 —— 它不是读数，来源标注必须写成「上界」。
 */
internal object ArrayListCapacityBound {
    const val DEFAULT_SEED = 10

    /** 装了 [size] 个元素之后背衬数组的最大可能长度（10 → 15 → 22 → 33 …）。 */
    fun of(size: Int): Int {
        if (size <= 0) return 0
        if (size <= DEFAULT_SEED) return DEFAULT_SEED
        var cap = DEFAULT_SEED
        while (cap < size) {
            val next = cap + (cap shr 1)
            cap = if (next > cap) next else size // 防 int 溢出死循环
        }
        return cap
    }
}

/** 一次子块快照：互斥字节 + 计数列（计数只做判读，不参与求和）。 */
internal class VarPoolCounters(
    val bytes: LinkedHashMap<String, Long>,
    val counts: LinkedHashMap<String, String>,
) {
    /** 互斥子块的合计（面 10 数据面的下界）。 */
    fun sumBytes(): Long = bytes.values.sum()

    fun block(b: String): Long = bytes[b] ?: 0L

    /** 逐键差分（正 = 这一相位/这一页变长）；键集取两侧并集，缺键按 0。 */
    fun diff(other: VarPoolCounters): LinkedHashMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (k in VarPoolBlock.ORDER) {
            val v = other.block(k) - this.block(k)
            if (v != 0L) out[k] = v
        }
        return out
    }
}

/**
 * 子块算术与判性判式（纯函数，进 `VarPoolFacetsTest` 定向单测；不碰产物、不碰堆、不碰产品对象）。
 *
 * 剥出来的理由：判读要的是可断言的口径 —— 「容量冗余怎么算」「判性三选一怎么判」
 * 不能是探针里一次性的 println。
 */
internal object VarPoolMath {

    /** 列式存储的**容量冗余** = 已分配行数 - 已用行数，再乘行字节；负数按 0（模型不该给出负冗余）。 */
    fun reserveBytes(allocatedRows: Int, usedRows: Int, rowBytes: Long): Long =
        (allocatedRows.toLong() - usedRows.toLong()).coerceAtLeast(0L) * rowBytes.coerceAtLeast(0L)

    /** 已用量 = 行数 × 行字节（原版逐角色分配，恒等于「已用」；本层还要另算容量）。 */
    fun usedBytes(rows: Int, rowBytes: Long): Long = rows.coerceAtLeast(0).toLong() * rowBytes.coerceAtLeast(0L)

    /** 预留倍率（容量 / 已用，千分比整数；已用为 0 时给 -1 表示无定义，不硬造数）。 */
    fun reservePermille(allocatedRows: Int, usedRows: Int): Int =
        if (usedRows <= 0) -1 else allocatedRows.toLong().let { a -> usedRows.toLong().let { u -> a * 1000 / u } }.toInt()

    /**
     * 判性判式（本车道的主产出，三选一）。
     *
     * @param portReserveBytes   移植侧「容量冗余」（已分配但未用）字节
     * @param portReserveTolBytes 冗余的判读容差（与 `RootLedger` 的 ±10 MiB 同源）
     * @param originalEagerFullConfig 原版口径是否「启动即满配」（C# 证据：`VariableData.cs:2957-2975` 全局列
     *        在构造时按 #DIM 全长分配；`CharacterData.cs:55-126` 每角色建时全长分配）
     * @param originalReleasesOnReset 原版复位是否释放（C# 证据：`VariableEvaluator.cs:1336-1346` 逐角色
     *        `Dispose()` + `CharacterList.Clear()`；`CharacterData.cs:756-770` 把数组置 null）
     * @param portReleasesOnReset 移植侧同一动作（`VariableStore.kt:1536` + `CharacterTable.kt:168-170`
     *        只置 `count = 0`，列数组与容量原样留 ⇒ false）
     */
    fun classify(
        portReserveBytes: Long,
        portReserveTolBytes: Long,
        originalEagerFullConfig: Boolean,
        originalReleasesOnReset: Boolean,
        portReleasesOnReset: Boolean,
    ): String = when {
        // 丙：原版复位会释放、移植侧漏了 —— 缺口就在复位那一步，与「峰值多大」无关。
        originalReleasesOnReset && !portReleasesOnReset -> "丙-复位链缺口(移植侧复位不还)"

        // 乙：原版按需（每角色精确分配，不存在为未用行预留的整块），移植侧容量冗余越容差。
        portReserveBytes > portReserveTolBytes -> "乙-移植侧过度预留"

        // 甲：两侧都是「建的时候就把这一份养满」，且移植侧冗余在容差内 ⇒ 这不是缺陷，报峰值预留/水位余量。
        originalEagerFullConfig -> "甲-原版口径即满配(报峰值预留/水位余量)"

        else -> "甲-原版口径即满配(报峰值预留/水位余量)"
    }

    /** 逐相位/逐页子块增量排行（前三，按字节降序）。 */
    fun topDeltas(diff: Map<String, Long>, n: Int = 3): List<Pair<String, Long>> =
        diff.entries.sortedByDescending { it.value }.take(n.coerceAtLeast(0)).map { it.key to it.value }

    /** HashMap 背衬表的字节（table 长度 × 引用宽 + 16 头 + 壳；与 `SizeModel` 同口径）。 */
    fun hashMapTableBytes(tableLength: Int, refWidth: Int, shellBytes: Long): Long =
        SizeModel.align8(16L + tableLength.toLong() * refWidth) + shellBytes

    /** ArrayList 背衬的字节（**容量**不是 size —— 这一列的意义就是「预留 vs 实际」）。 */
    fun arrayListBackingBytes(capacity: Int, refWidth: Int): Long =
        SizeModel.align8(16L + capacity.toLong() * refWidth)
}

/**
 * 子块拆分器。
 *
 * 与 `PhaseFacets.bucket` 的关系：那边是**遍历口径**（身份去重的对象图深账），这边是**公式口径**
 * （逐列数组长度 × 元素宽）。两边读同一个 `store`，但来源标注不许混：本类的行一律
 * `枚举-精确公式(不入账)`，跨相位差分和逐页差分都用它（它比遍历便宜三个数量级，才能每页都读）。
 */
internal class VarPoolFacets(private val sizer: SizeModel) {

    private fun field(target: Any?, name: String): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) return runCatching { f.isAccessible = true; f.get(target) }.getOrNull()
            c = c.superclass
        }
        return null
    }

    /** 一个对象的全部非静态引用字段值（用于「其余字段」catch-all）。 */
    private fun refFields(o: Any): List<Any?> {
        val vals = ArrayList<Any?>()
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                vals.add(runCatching { f.isAccessible = true; f.get(o) }.getOrNull())
            }
            c = c.superclass
        }
        return vals
    }

    /**
     * 「数组就算本体，不是数组就算浅尺寸」的安全版：
     * 反射取到的字段值在产品侧换成 `ArrayDeque` / 别的容器时不许把装置自己炸掉
     * （`SizeModel.arraySize` 走 `java.lang.reflect.Array.getLength`，非数组直接抛
     * `IllegalArgumentException`）。
     */
    private fun arrBytes(v: Any?): Long = when {
        v == null -> 0L
        v.javaClass.isArray -> sizer.arraySize(v)
        else -> sizer.objectSize(v)
    }

    /**
     * 一个用户变量实例上的**全部**原始数组字节：直接持有的 `LongArray`/`IntArray` 字段，
     * 加上装在 `List` 里的数组（私有动态变量的作用域栈，`UserVariables.kt:1656-1714`）。
     *
     * 不调 `rawArray()`（会触发懒分配，见文件头第 1 条）；角色变量（`CharaIntUserVar`/
     * `CharaStrUserVar`）在本层返回 0 —— 它们的字节全在角色表的列里，已在 10.1/10.2 计过，
     * 这里返回 0 正是「子块互斥」的要求。
     */
    private fun userDataBytes(v: Any): Long {
        var sum = 0L
        var c: Class<*>? = v.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                val x = runCatching { f.isAccessible = true; f.get(v) }.getOrNull() ?: continue
                sum += when {
                    x is LongArray || x is IntArray || x is ShortArray || x is ByteArray || x is DoubleArray ->
                        sizer.arraySize(x)
                    x is List<*> -> x.filterIsInstance<Any>().sumOf { e ->
                        if (e.javaClass.isArray && e.javaClass.componentType?.isPrimitive == true) sizer.arraySize(e) else 0L
                    }
                    else -> 0L
                }
            }
            c = c.superclass
        }
        return sum
    }

    /**
     * 一次子块快照。
     *
     * @param store `session.store`（`VariableStore`）；类型按 `Any` 收，全部走反射 + 公开列访问，
     *        装置不因为产品 API 改名而编不过的那条腿已经由 [CharacterTable] 的公开列覆盖了。
     */
    fun snapshot(store: Any, characters: CharacterTable?): VarPoolCounters {
        val bytes = LinkedHashMap<String, Long>()
        val counts = LinkedHashMap<String, String>()
        val claimed = IdentityHashMap<Any, Boolean>()

        // ---- 10.1 / 10.2 角色表（列式：容量 × 行字节；含用户 CHARADATA 追加列） ----
        var charaInt = 0L
        var charaStr = 0L
        var colsInt = 0
        var colsStr = 0
        if (characters != null) {
            for (col in characters.int0 + characters.int1 + characters.int2) {
                val d = col.data
                charaInt += sizer.arraySize(d)
                claimed[d] = true
                colsInt++
            }
            for (col in characters.str0 + characters.str1) {
                val d = col.data
                charaStr += sizer.arraySize(d)
                claimed[d] = true
                colsStr++
            }
            val rb = characters.rowBytes()
            val cap = characters.allocatedCapacity
            val cnt = characters.count
            bytes[VarPoolBlock.CHARA_INT] = charaInt
            bytes[VarPoolBlock.CHARA_STR] = charaStr
            counts["角色表.列数"] = "整数 $colsInt / 字符串 $colsStr"
            counts["角色表.count/容量"] = "$cnt / $cap"
            counts["角色表.行字节"] = VarPoolMath.usedBytes(1, rb).toString()
            counts["角色表.已用字节"] = VarPoolMath.usedBytes(cnt, rb).toString()
            counts["角色表.容量冗余字节"] = VarPoolMath.reserveBytes(cap, cnt, rb).toString()
            counts["角色表.预留倍率‰"] = VarPoolMath.reservePermille(cap, cnt).toString()
        }

        // ---- 10.3 / 10.4 系统全局列 ----
        var sysInt = 0L
        var sysIntCols = 0
        var sysIntTotal = 0
        for (name in listOf("gInt1", "gInt2", "gInt3")) {
            when (val arr = field(store, name)) {
                is Array<*> -> {
                    sysIntTotal += arr.size
                    for (col in arr) {
                        if (col == null) continue
                        val a = col as Any
                        sysInt += sizer.arraySize(a)
                        if (a is LongArray) sysIntCols++
                        claimed[a] = true
                    }
                }
                null -> Unit
                else -> {
                    sysInt += arrBytes(arr)
                    claimed[arr] = true
                }
            }
        }
        field(store, "itemPriceSource")?.let {
            if (it is LongArray) {
                sysInt += sizer.arraySize(it)
                claimed[it] = true
            }
        }
        var sysStr = 0L
        for (name in listOf("gStr1", "gStr0")) {
            when (val arr = field(store, name)) {
                is Array<*> -> for (col in arr) {
                    if (col == null) continue
                    sysStr += sizer.arraySize(col)
                    claimed[col!!] = true
                }
                null -> Unit
                else -> {
                    sysStr += arrBytes(arr)
                    claimed[arr] = true
                }
            }
        }
        bytes[VarPoolBlock.SYS_INT] = sysInt
        bytes[VarPoolBlock.SYS_STR] = sysStr
        // 「已分配 / 总列数」这一列就是移植侧与原版的反向差：原版 65 列构造时全灌满
        // （spec: VariableData.cs:2963-2967），移植侧是 `arrayOfNulls` 懒分配（spec: VariableStore.kt:113）。
        counts["系统整数列.已分配/总列"] = "$sysIntCols / $sysIntTotal"

        // ---- 10.5 / 10.6 用户变量 ----
        var userG = 0L
        var userGVars = 0
        var userGChars = 0
        (field(store, "userVars") as? Map<*, *>)?.let { m ->
            for ((_, v) in m) {
                if (v == null) continue
                userGVars++
                if ((v as? UserVar)?.spec?.isCharaData == true) userGChars++
                userG += userDataBytes(v)
            }
        }
        var priv = 0L
        var privVars = 0
        var privKeys = 0
        (field(store, "privateVars") as? Map<*, *>)?.let { m ->
            privKeys = m.size
            for ((_, inner) in m) {
                for ((_, v) in (inner as? Map<*, *>).orEmpty()) {
                    if (v == null) continue
                    privVars++
                    priv += userDataBytes(v)
                }
            }
        }
        bytes[VarPoolBlock.USER_GLOBAL] = userG
        bytes[VarPoolBlock.USER_PRIV] = priv
        counts["用户全局.变量数"] = "$userGVars（角色变量列 $userGChars 计入 10.1/10.2）"
        counts["用户私有.函数键/变量数"] = "$privKeys / $privVars"

        // ---- 10.7 串表 ----
        var pool = 0L
        val st = field(store, "pool")
        if (st != null) {
            val values = field(st, "values") as? List<*>
            val ids = field(st, "ids") as? Map<*, *>
            val backingCap = (field(st, "values")?.let { field(it, "elementData") } as? Array<*>)?.size ?: 0
            val tableLen = (field(st, "ids")?.let { field(it, "table") } as? Array<*>)?.size ?: 0
            val strBytes = values?.filterIsInstance<String>().orEmpty().sumOf { SizeModel.stringBytes(it) }
            val idsEntryShells = (ids?.size ?: 0) * (sizer.shallowOf(nodeClass()) .toLong())
            pool = sizer.objectSize(st) +
                VarPoolMath.arrayListBackingBytes(backingCap, sizer.refWidth) +
                VarPoolMath.hashMapTableBytes(tableLen, sizer.refWidth, sizer.objectSize(ids ?: Any())) +
                strBytes + idsEntryShells
            // JDK 强封装：`ArrayList.elementData` / `HashMap.table` 反射不到（实测恒 0），
            // 所以这两列取 -1 表示「读不到」并给出 ArrayList 1.5× 扩容的上界，绝不把 0 当成「没预留」。
            val capBound = if (backingCap > 0) backingCap.toString() else ArrayListCapacityBound
                .of(values?.size ?: 0).toString() + "(上界)"
            counts["串表.条数/背衬容量/表长"] = "${values?.size ?: 0} / " +
                (if (backingCap > 0) "$backingCap" else "-1(读不到，上界 $capBound)") + " / " +
                (if (tableLen > 0) "$tableLen" else "-1(读不到)")
            counts["串表.串本体字节"] = strBytes.toString()
            (field(st, "values") as? List<*>)?.let { lst ->
                for (e in lst) if (e != null) claimed[e] = true
            }
        }
        bytes[VarPoolBlock.STR_POOL] = pool

        // ---- 10.8 局部态 ----
        var localBytes = 0L
        var localVars = 0
        var localFuncs = 0
        (field(store, "localsByFunc") as? Map<*, *>)?.let { m ->
            localFuncs = m.size
            for ((_, ls) in m) {
                if (ls == null) continue
                localBytes += sizer.objectSize(ls)
                val map = field(ls, "locals") as? Map<*, *>
                map?.let { localBytes += VarPoolMath.hashMapTableBytes(
                    (field(it, "table") as? Array<*>)?.size ?: 0, sizer.refWidth, sizer.objectSize(it)) }
                for ((_, lv) in map.orEmpty()) {
                    if (lv == null) continue
                    localVars++
                    localBytes += userDataBytes(lv)
                }
            }
        }
        for (n in listOf("frames", "framePool", "localLengths")) {
            field(store, n)?.let {
                val v = it
                localBytes += if (v is List<*>) {
                    sizer.objectSize(v) +
                        VarPoolMath.arrayListBackingBytes(
                            (field(v, "elementData") as? Array<*>)?.size ?: v.size, sizer.refWidth)
                } else arrBytes(v)
            }
        }
        bytes[VarPoolBlock.LOCALS] = localBytes
        counts["局部态.函数/变量实例"] = "$localFuncs / $localVars"

        // ---- 10.9 绑定与记忆缓存 ----
        var bindBytes = 0L
        for (n in listOf(
            "bindNames", "bindPriv", "bindVals", "upperNames", "privateNames",
            "handleEntries", "handleSlots", "indexScratch", "userVarsRaw", "globalUserVars", "savedUserVars",
        )) {
            field(store, n)?.let { bindBytes += cacheBytes(it); claimed[it] = true }
        }
        bytes[VarPoolBlock.BIND_CACHE] = bindBytes

        // ---- 10.10 其余字段（浅尺寸 + 数组本体，不追深图） ----
        var rest = 0L
        var restFields = 0
        for (v in refFields(store)) {
            if (v == null) continue
            if (claimed[v] == true) continue
            if (v === characters) continue
            restFields++
            rest += restFieldBytes(v)
        }
        bytes[VarPoolBlock.REST] = rest
        counts["其余字段.个数"] = "$restFields"

        return VarPoolCounters(bytes, counts)
    }

    /** 缓存类字段（Map / Set / List / 数组）的**背衬**字节：只计算容量与桶，不追元素深图。 */
    private fun cacheBytes(v: Any): Long {
        val shell = sizer.objectSize(v)
        return when (v) {
            is Map<*, *> -> shell + VarPoolMath.hashMapTableBytes(
                (field(v, "table") as? Array<*>)?.size ?: v.size, sizer.refWidth, 0L)
            is Set<*> -> shell + VarPoolMath.hashMapTableBytes(
                (field(v, "table") as? Array<*>)?.size ?: v.size, sizer.refWidth, 0L)
            is List<*> -> shell + VarPoolMath.arrayListBackingBytes(
                (field(v, "elementData") as? Array<*>)?.size ?: v.size, sizer.refWidth)
            else -> arrBytes(v)
        }
    }

    /** 「其余字段」的单字段字节：原始数组算本体，其余只算浅尺寸（深图归面 10 的遍历行）。 */
    private fun restFieldBytes(v: Any): Long = when {
        v.javaClass.isArray && v.javaClass.componentType?.isPrimitive == true -> sizer.arraySize(v)
        v is Map<*, *> || v is Set<*> || v is List<*> -> cacheBytes(v)
        else -> sizer.objectSize(v)
    }

    private fun nodeClass(): Class<*> = runCatching {
        Class.forName("java.util.HashMap\$Node")
    }.getOrDefault(Any::class.java)
}
