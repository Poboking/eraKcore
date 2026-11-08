package com.eratw.port.core.vars

/**
 * 角色库：列式存储，**每个角色不建对象**（ADR-004）。
 *
 * 原版 `CharacterData` 每个实例持有
 * `dataInteger[2] + dataString[4] + dataIntegerArray[84] + dataStringArray[1] + dataIntegerArray2D[1]`
 * （spec: CharacterData.cs:55-126）。本层把每一列拉平成连续数组：
 * `列数据[charIndex * blockSize + elementIndex]`，其中 blockSize = 该列元素数。
 * 这样一次 ADDCHARA 只是 [count]++（必要时扩容），CHARANUM 读取是 O(1)，
 * 排序/删除是整块 memmove。
 *
 * 用户 `#DIM CHARADATA` 变量在声明时**追加列**（[appendIntColumn]/[appendStrColumn]），
 * 与系统 84 列同样寻址。
 *
 * **初始容量取 1**（mem6 2026-10-28）：84+2+1+1+4 列 × 一个角色的块 ≈ 26,450 个 long，
 * 旧的缺省 16 就是 3.3 MiB（真产物 §5 实测「角色表 CharacterTable」桶 3.3 MiB），
 * 而标题页只有 1 只角色（`GameSession` 走 `addCharacter(0)`；无 CSV 时才 4 只裸角色）。
 * 原版是 `List<CharacterData>`（`CharacterData.cs:55-126` 的字段在 new 角色时才分配，
 * 加角色走 `List.Add` 的自然扩容，spec: VariableEvaluator.cs:1241-1249 的 RemoveAt 同表），
 * 本层的 [ensureCapacity] 按 2 倍扩容，与 `ArrayList` 同形 —— 从 1 起步只是把「预支 16 格」
 * 换成「用到才长」，可观测状态（`0 直到 count` 的格子值）一个都不动。
 * 翻倍的**空尾**另有上限（[RESERVE_TAIL_FLOOR_ROWS]）：原版翻倍只预留引用位，本层一次给满
 * 五族列的整行，「为不存在的角色预留整行」是移植侧多出的一笔，钳掉它对脚本与存档不可见。
 * 容量本身属分配性副产品：`l3/StateHash` 的口径把它和「超出 count 的尾部」一起排除在
 * 状态哈希之外（`StateHash.kt` 类 KDoc 第 6 条）。
 */
class CharacterTable(
    private val sizes: VariableSizes,
    private val config: VarConfig,
    requestedCapacity: Int = 1,
) {
    /** 起步容量（[clear] 复位后回到的档位）；口径见类 KDoc 的「初始容量取 1」。 */
    private val startCapacity: Int = requestedCapacity.coerceAtLeast(1)

    var count: Int = 0
        private set

    private var capacity: Int = startCapacity

    /**
     * 逐行**半死位**（B5，2026-11-06 翻案落地）：`true` = 该行已被 DELCHARA 数组参失败路径
     * `Dispose()` 但仍在表内（原版「边校验边 Dispose、全过才 Remove」的中间态，
     * spec: VariableEvaluator.cs:1253-1273 —— `:1267` 在校验循环内逐个释放、
     * `:1269-1272` 的 `CharacterList.Remove` 只在全部校验通过后执行 ⇒ 第 k 个元素越界时
     * 前 k 个已释放仍在表内）。
     *
     * 原版 `CharacterData.Dispose`（CharacterData.cs:756-770）只把**本实例**的
     * `dataIntegerArray / dataStringArray / dataIntegerArray2D`（1 维/2 维系统角色变量的
     * 存储本体）置 null —— 0 维的 `dataInteger[2]`（ISASSI/NO）与 `dataString[4]`
     * （NAME/CALLNAME/NICKNAME/MASTERNAME）以及用户 CVAR 的 `UserDefCVarDataList`
     * （CharacterData.cs:85/:124，Dispose 不触碰）**不在释放面内** ⇒ 半死行的可读性按列族分档：
     * - `int0`/`str0`（0 维）与用户 CVAR 列：照常读写（值还是释放前的旧值）；
     * - 系统 `int1`/`str1`/`int2`（1 维/2 维）：读写皆踩 null ⇒ .NET
     *   `NullReferenceException`（非 CodeEE），移植侧同点抛裸 [NullPointerException]
     *   （口径照 §13.2 分歧 C 的裸 CLR→裸 JVM 映射先例）。
     * [requireSystemRowAlive] 是该分档的唯一落点；本数组只负责把「哪行是半死」记住。
     */
    private var disposed: BooleanArray = BooleanArray(capacity)

    /** 角色 0 维整数列：ISASSI(0) / NO(1)。 */
    val int0: MutableList<LongCol> = MutableList(SystemLayout.charaInt0D.size) {
        LongCol(intArrayOf(1), capacity)
    }

    /** 角色 0 维字符串列：NAME/CALLNAME/NICKNAME/MASTERNAME。 */
    val str0: MutableList<StrCol> = MutableList(SystemLayout.charaStr0D.size) {
        StrCol(intArrayOf(1), capacity)
    }

    /** 角色 1 维整数列：84 列，前 21 列有名字。 */
    val int1: MutableList<LongCol> = MutableList(sizes.charaInt1D.size) { i ->
        LongCol(intArrayOf(sizes.charaInt1D[i]), capacity)
    }

    /** 角色 1 维字符串列：CSTR。 */
    val str1: MutableList<StrCol> = MutableList(sizes.charaStr1D.size) { i ->
        StrCol(intArrayOf(sizes.charaStr1D[i]), capacity)
    }

    /** 角色 2 维整数列：CDFLAG。 */
    val int2: MutableList<LongCol> = MutableList(sizes.charaInt2D.size) { i ->
        LongCol(sizes.charaInt2D[i].copyOf(), capacity)
    }

    // ------------------------------------------------------------------
    // 列
    // ------------------------------------------------------------------

    class LongCol(val sizes: IntArray, capacity: Int) {
        // 每行元素数走 dimProduct（Long 预乘 + 上限判定），分配总数走 columnElements。
        // 旧实现是 Int 折叠：sizes = 50000 x 50000 时 blockSize 变负数，构造直接
        // NegativeArraySizeException，而不是 CodeEE。
        val blockSize: Int = dimProduct("角色整数列", sizes)
        var data: LongArray = LongArray(columnElements(capacity, blockSize))
            private set

        fun grow(newCapacity: Int, count: Int) {
            val next = LongArray(columnElements(newCapacity, blockSize))
            data.copyInto(next, 0, 0, columnElements(count, blockSize))
            data = next
        }

        fun get(charIndex: Int, element: Int): Long = data[charIndex * blockSize + element]

        fun set(charIndex: Int, element: Int, value: Long) {
            data[charIndex * blockSize + element] = value
        }

        fun elemIndex(indices: IntArray): Int {
            // 行主序（C# 多维数组的默认布局，spec: VariableData.cs:117）
            var idx = 0
            for (i in indices.indices) idx = idx * sizes[i] + indices[i]
            return idx
        }

        fun fill(charIndex: Int, value: Long) {
            val from = charIndex * blockSize
            data.fill(value, from, from + blockSize)
        }

        internal fun permute(order: IntArray, capacity: Int) {
            val next = LongArray(columnElements(capacity, blockSize))
            for (n in order.indices) {
                data.copyInto(next, n * blockSize, order[n] * blockSize, (order[n] + 1) * blockSize)
            }
            data = next
        }
    }

    class StrCol(val sizes: IntArray, capacity: Int) {
        val blockSize: Int = dimProduct("角色字符串列", sizes)
        var data: IntArray = IntArray(columnElements(capacity, blockSize))
            private set

        fun grow(newCapacity: Int, count: Int) {
            val next = IntArray(columnElements(newCapacity, blockSize))
            data.copyInto(next, 0, 0, columnElements(count, blockSize))
            data = next
        }

        fun get(charIndex: Int, element: Int): Int = data[charIndex * blockSize + element]

        fun set(charIndex: Int, element: Int, value: Int) {
            data[charIndex * blockSize + element] = value
        }

        fun elemIndex(indices: IntArray): Int {
            var idx = 0
            for (i in indices.indices) idx = idx * sizes[i] + indices[i]
            return idx
        }

        fun fill(charIndex: Int, value: Int) {
            val from = charIndex * blockSize
            data.fill(value, from, from + blockSize)
        }

        internal fun permute(order: IntArray, capacity: Int) {
            val next = IntArray(columnElements(capacity, blockSize))
            for (n in order.indices) {
                data.copyInto(next, n * blockSize, order[n] * blockSize, (order[n] + 1) * blockSize)
            }
            data = next
        }
    }

    // ------------------------------------------------------------------
    // 增删
    // ------------------------------------------------------------------

    /**
     * 只分配位置并清零（不含模板回填）。模板字段的拷贝由 [VariableStore] 完成，
     * 因为字符串列需要经驻留池写入。spec: CharacterData.cs:128-192
     */
    fun addBare(): Int {
        ensureCapacity(count + 1)
        val pos = count++
        resetChar(pos)
        return pos
    }

    // ------------------------------------------------------------------
    // 半死位（B5）
    // ------------------------------------------------------------------

    /**
     * 把 [row] 标成半死（DELCHARA 数组参校验循环内逐行调，对应原版 `:1267` 的
     * `characterData.Dispose()` —— 边校验边释放，不回滚）。已半死的行再标一次是空操作
     * （原版对已释放的 CharacterData 再 Dispose 同样是再置一轮 null，幂等）。
     */
    fun disposeRow(row: Int) {
        if (row < 0 || row >= count) throw CodeEE("删除角色失败：角色位置 $row 越界（角色数 $count）")
        disposed[row] = true
    }

    /** 该行是否半死（仅诊断/测试面用；读写路径走 [requireSystemRowAlive]）。 */
    fun isRowDisposed(row: Int): Boolean = row in 0 until count && disposed[row]

    /**
     * 半死行的**系统 1 维/2 维变量读写闸**（[disposed] KDoc 的分档落点）。
     *
     * 只在 `VariableStore` 的 `Storage.C_INT1 / C_INT2 / C_STR1` 读写分支与
     * [copy]（COPYCHARA/ADDCOPYCHARA 的整行搬运，原版 CharacterData.CopyTo
     * spec: CharacterData.cs:239-349 读写 `dataIntegerArray` 一族）调用；
     * `int0`/`str0`（0 维）与用户 CVAR 列不经这里 —— 原版 Dispose 不释放它们
     * （CharacterData.cs:756-770 只置三张 1 维/2 维数组），半死行照常可读。
     */
    fun requireSystemRowAlive(row: Int) {
        if (row in disposed.indices && disposed[row]) {
            throw NullPointerException(
                "角色 $row 已被失败的 DELCHARA 释放（半死态：仍在表内但系统 1 维/2 维变量数组已置 null），" +
                    "读写复刻原版 NullReferenceException（spec: VariableEvaluator.cs:1253-1273 + CharacterData.cs:756-770，ADR-005）",
            )
        }
    }

    /** 删除并左移后续角色。spec: VariableEvaluator.cs:1241-1249 (RemoveAt)。 */
    fun removeAt(index: Int) {
        if (index < 0 || index >= count) throw CodeEE("删除角色失败：角色位置 $index 越界（角色数 $count）")
        val last = count - 1
        if (index != last) {
            shiftBlock(index, last)
            // 半死位跟数据一起左移（被摘行的位自然被覆盖；末位空档清掉）。
            // 半死行本身也可以被后续 DELCHARA 摘除（原版对已 Dispose 的角色再 Dispose 是
            // 空操作再 Remove，spec: VariableEvaluator.cs:1267 对 null 数组再置 null），位值随行走。
            System.arraycopy(disposed, index + 1, disposed, index, last - index)
        }
        disposed[last] = false
        count--
    }

    /**
     * 清空角色表并**归还列容量**（复位链的落点）。
     *
     * 原版对拍：`VariableEvaluator.ResetData()` 与 `DelAllCharacter()`
     * （spec: VariableEvaluator.cs:1336-1346、:1275-1285）都是**逐角色
     * `character.Dispose()` 再 `CharacterList.Clear()`** —— `Dispose()` 把每角色的
     * `dataIntegerArray / dataStringArray / dataIntegerArray2D` 置 null
     * （spec: CharacterData.cs:756-770），角色数据本体在复位后立刻整体归还 GC，
     * 下一次 `ADDCHARA` 走 `new CharacterData` 现场重分配（spec: CharacterData.cs:55-126）。
     *
     * 本层列式存储没有逐角色对象，「Dispose 归还」的等价形态就是把每列的 `data`
     * 换回起步容量的新数组：`count=0`、列定义（`int1`/`str1` 的成员，含用户
     * `#DIM CHARADATA` 追加列）保留 ⇒ 列下标与 `CharaIntUserVar.col` 一类缓存索引
     * 不失效；随后 [addBare] 仍走 [ensureCapacity] 的 2 倍翻倍，与原版
     * `List.Add` 的自然扩容同形。旧列数组没有任何跨复位的持有者：REF 拒绝绑定
     * 角色变量（spec: ReferenceToken.cs:138-167，移植侧 `refBindTarget` 的角色分支
     * 只交元信息），而既有 [grow]/[permute] 本来就会换 `data` 对象 ⇒ 缓存裸数组引用
     * 的写法在本表从未成立。可观测口径不变：容量属分配性副产品，StateHash 第 6 条
     * 把它与超出 count 的尾部一起排除在状态哈希之外（类 KDoc）。
     *
     * 容量已在起步档时**不**换数组（换不出收益），但仍把整表清成全 0：`count = 0` 后没有任何
     * 可达行（`requireIndex`/`checkElement` 都按 count 判界），旧行的值是纯垃圾。串列尤其要紧 ——
     * 复位链末尾的串表压实只改写「可达区 `[0, count)`」的池下标（见
     * `VariableStore.strIdCarriers` 的第 ④ 档），尾部留着旧下标就是留着一批「看着像活、
     * 其实指向已被压实的条目」的悬空值；下一次 [addBare] 的 [resetChar] 虽会覆盖，
     * 但任何越界读都该读到空串而不是读到别的角色的名字。整数列一并清，让两条分支同形。
     */
    fun clear() {
        count = 0
        java.util.Arrays.fill(disposed, false)
        if (capacity == startCapacity) {
            for (col in int0) col.data.fill(0L)
            for (col in str0) col.data.fill(0)
            for (col in int1) col.data.fill(0L)
            for (col in str1) col.data.fill(0)
            for (col in int2) col.data.fill(0L)
            return
        }
        for (col in int0) col.grow(startCapacity, 0)
        for (col in str0) col.grow(startCapacity, 0)
        for (col in int1) col.grow(startCapacity, 0)
        for (col in str1) col.grow(startCapacity, 0)
        for (col in int2) col.grow(startCapacity, 0)
        capacity = startCapacity
    }

    /** 把 [from]..[last] 整体左移一格（覆盖 [from]）。 */
    private fun shiftBlock(from: Int, last: Int) {
        val len = last - from
        for (col in int0) System.arraycopy(col.data, (from + 1) * col.blockSize, col.data, from * col.blockSize, len * col.blockSize)
        for (col in str0) System.arraycopy(col.data, (from + 1) * col.blockSize, col.data, from * col.blockSize, len * col.blockSize)
        for (col in int1) System.arraycopy(col.data, (from + 1) * col.blockSize, col.data, from * col.blockSize, len * col.blockSize)
        for (col in str1) System.arraycopy(col.data, (from + 1) * col.blockSize, col.data, from * col.blockSize, len * col.blockSize)
        for (col in int2) System.arraycopy(col.data, (from + 1) * col.blockSize, col.data, from * col.blockSize, len * col.blockSize)
    }

    /**
     * 拷贝整只角色（含用户定义角色变量）。spec: CharacterData.cs:239-349
     *
     * B5：源或目标是半死行时抛裸 [NullPointerException] —— 原版 `CopyTo` 首步就读写
     * `dataIntegerArray` 一族（CharacterData.cs:239-349 的循环体），半死行那里是 null
     * ⇒ .NET NullReferenceException，COPYCHARA/ADDCOPYCHARA 都经此闸
     * （可达性同 [disposed] KDoc：TRYC 家族捕不到 DELCHARA 的 CodeEE，半死行要进到
     * COPYCHARA 得先有一个没被顶上错误态的失败批次，语料 0 处，构造用例钉）。
     */
    fun copy(dst: Int, src: Int) {
        requireIndex(dst)
        requireIndex(src)
        requireSystemRowAlive(dst)
        requireSystemRowAlive(src)
        for (col in int0) col.data.copyInto(col.data, dst * col.blockSize, src * col.blockSize, (src + 1) * col.blockSize)
        for (col in str0) col.data.copyInto(col.data, dst * col.blockSize, src * col.blockSize, (src + 1) * col.blockSize)
        for (col in int1) col.data.copyInto(col.data, dst * col.blockSize, src * col.blockSize, (src + 1) * col.blockSize)
        for (col in str1) col.data.copyInto(col.data, dst * col.blockSize, src * col.blockSize, (src + 1) * col.blockSize)
        for (col in int2) col.data.copyInto(col.data, dst * col.blockSize, src * col.blockSize, (src + 1) * col.blockSize)
    }

    /**
     * 交换两角色。spec: VariableEvaluator.cs:1378 (SwapChara)
     *
     * 不带半死闸：原版 SwapChara 只交换 `CharacterList` 的两个引用（不触碰数据数组），
     * 半死行照常可被交换/挪位（PICKUPCHARA 的交换就位同面）；半死位跟着行走。
     */
    fun swap(a: Int, b: Int) {
        requireIndex(a)
        requireIndex(b)
        if (a == b) return
        val d = disposed[a]
        disposed[a] = disposed[b]
        disposed[b] = d
        for (col in int0) swapBlocks(col.data, a, b, col.blockSize)
        for (col in str0) swapBlocks(col.data, a, b, col.blockSize)
        for (col in int1) swapBlocks(col.data, a, b, col.blockSize)
        for (col in str1) swapBlocks(col.data, a, b, col.blockSize)
        for (col in int2) swapBlocks(col.data, a, b, col.blockSize)
    }

    private fun swapBlocks(data: LongArray, a: Int, b: Int, block: Int) {
        for (i in 0 until block) {
            val ai = a * block + i
            val bi = b * block + i
            val t = data[ai]
            data[ai] = data[bi]
            data[bi] = t
        }
    }

    private fun swapBlocks(data: IntArray, a: Int, b: Int, block: Int) {
        for (i in 0 until block) {
            val ai = a * block + i
            val bi = b * block + i
            val t = data[ai]
            data[ai] = data[bi]
            data[bi] = t
        }
    }

    /**
     * 按 [order]（order[新位置] = 旧位置）整体重排。SORTCHARA 用。
     * spec: CharacterData.cs:772-790（稳定排序由调用方保证 order 的稳定性）。
     *
     * 不带半死闸：原版排序按引用搬动 List 条目，不触碰数据数组；排序**键**若取自
     * 半死行的系统 1 维/2 维变量，NRE 在键求值处（VariableStore 读路径闸）先爆，
     * 走不到这里 —— 与原版一致（SortChara 的键读取 spec: VariableEvaluator.cs:1408）。
     */
    fun reorder(order: IntArray) {
        if (order.size != count) throw CodeEE("重排数组长度 ${order.size} 与角色数 $count 不一致")
        val nextDisposed = BooleanArray(count)
        for (n in order.indices) nextDisposed[n] = disposed[order[n]]
        System.arraycopy(nextDisposed, 0, disposed, 0, count)
        for (col in int0) col.permute(order, capacity)
        for (col in str0) col.permute(order, capacity)
        for (col in int1) col.permute(order, capacity)
        for (col in str1) col.permute(order, capacity)
        for (col in int2) col.permute(order, capacity)
    }

    // ------------------------------------------------------------------
    // 用户角色变量列
    // ------------------------------------------------------------------

    fun appendIntColumn(dimSizes: IntArray): Int {
        int1.add(LongCol(dimSizes.copyOf(), capacity))
        return int1.size - 1
    }

    fun appendStrColumn(dimSizes: IntArray): Int {
        str1.add(StrCol(dimSizes.copyOf(), capacity))
        return str1.size - 1
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 角色位置对应的整行清零（不含模板默认值）。新建行走这里 ⇒ 半死位一并清。 */
    fun resetChar(pos: Int) {
        if (pos in disposed.indices) disposed[pos] = false
        for (col in int0) col.fill(pos, 0L)
        for (col in str0) col.fill(pos, 0)
        for (col in int1) col.fill(pos, 0L)
        for (col in str1) col.fill(pos, 0)
        for (col in int2) col.fill(pos, 0L)
    }

    /** 新建角色时 RELATION 全元素填默认值。spec: CharacterData.cs:163-167 */
    fun fillRelation(pos: Int) {
        int1[VariableCode.lowIndex(VariableCode.RELATION)].fill(pos, config.relationDef)
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= capacity) return
        var next = capacity
        while (next < needed) {
            // 2 倍扩容；越过 Int.MAX_VALUE / 2 后直接取 needed，避免左移溢出成负数死循环。
            next = if (next > Int.MAX_VALUE / 2) needed else next shl 1
        }
        // 预留尾部钳制（fix-varcapacity）：翻倍仍是增长主形，但「为不存在的角色预留的空尾」
        // 钳到 max(RESERVE_TAIL_FLOOR_ROWS, needed shr RESERVE_TAIL_SHIFT) 行。依据与口径见
        // companion 的 [RESERVE_TAIL_FLOOR_ROWS] KDoc。
        next = clampReserveTail(needed, next)
        if (next > disposed.size) disposed = disposed.copyOf(next)
        for (col in int0) col.grow(next, count)
        for (col in str0) col.grow(next, count)
        for (col in int1) col.grow(next, count)
        for (col in str1) col.grow(next, count)
        for (col in int2) col.grow(next, count)
        capacity = next
    }

    /** 把翻倍目标 [doubled] 的空尾钳制到上限内（钳制后仍 ≥ needed，翻倍在尾部预算内原样保留）。 */
    private fun clampReserveTail(needed: Int, doubled: Int): Int {
        val maxTail = maxOf(RESERVE_TAIL_FLOOR_ROWS, needed shr RESERVE_TAIL_SHIFT)
        return if (doubled - needed > maxTail) needed + maxTail else doubled
    }

    private fun requireIndex(index: Int) {
        if (index < 0 || index >= count) {
            throw CodeEE("角色位置 $index 越界（角色数 $count）")
        }
    }

    val allocatedCapacity: Int get() = capacity

    /**
     * 一个角色在本表全部列上占用的字节数（按列定义，不含容量冗余）。
     *
     * 存档还原前用它做「角色数 × 每行字节」预算：存档头里的角色数可与负载大小脱钩
     * （二进制档每个空角色块只占 1 字节），不先判预算就会按 1e6 个角色逐行扩容。
     * 用户 `#DIM CHARADATA` 列已在 [int1]/[str1] 里，一并计入。
     */
    fun rowBytes(): Long {
        var total = 0L
        for (col in int0) total += col.blockSize.toLong() * 8L
        for (col in int1) total += col.blockSize.toLong() * 8L
        for (col in int2) total += col.blockSize.toLong() * 8L
        for (col in str0) total += col.blockSize.toLong() * 4L
        for (col in str1) total += col.blockSize.toLong() * 4L
        return total
    }

    companion object {
        /**
         * 翻倍预留的**空尾下限**（行）。fix-varcapacity，判性在册 obs-varpool「乙·次要」那条。
         *
         * 为什么要钳：原版是 `List<CharacterData>`，翻倍翻倍的是**引用数组**——每个空槽只预留
         * 一个引用位（对象本体在 `new CharacterData` 时才逐字段分配，spec: CharacterData.cs:55-126，
         * 建角色点 spec: VariableEvaluator.cs:1201-1239）。本层列式存储一次 `shl 1` 给五族列同时
         * 预留**整行**（约 200 KiB/行，见 [rowBytes]），「为根本不存在的角色预留整行」是移植侧
         * 多出原版的一笔 —— 收掉它**不违反 ADR-005**（原版本来就没有这一笔）。
         *
         * 钳制口径：`2× 翻倍仍是增长主形`（从起步档起逐次翻倍，读 1→2→4→…→128 阶梯可证，
         * `CharacterTableReserveTest` 钉住）；只有当翻倍得到的空尾超过
         * `max(RESERVE_TAIL_FLOOR_ROWS, needed shr RESERVE_TAIL_SHIFT)` 时才截到 `needed + 该上限`。
         * 下限取 64 行：让「容量 ≤ 128 的完整翻倍阶梯」一个字节都不被钳（65→128 的空尾至多 63
         * 行 < 64），钳制只在大表（P3 实测 角色168/容量256、空尾 88 行 ≈ 17.8 MiB）上生效，
         * 该处容量 256→193，收掉 ≈ 12–13 MiB 预留。
         *
         * 安全性（为什么脚本/存档看不见）：容量是分配性副产品，`l3/StateHash` 的「不纳入项 6」
         * 把 `allocatedCapacity` 与「超出 count 的尾部」一起排除在状态哈希之外
         * （spec: StateHash.kt 类 KDoc；对照原版无此维度）；列访问一律按 `count` 判界
         * （[requireIndex]/[checkElement] 口径），空尾从不被任何读写路径触达。
         * `CharacterTableReserveTest` 另跑**真哈希对照**：同一可观测终态经不同扩容历史
         * （容量不同）求 `StateHash.digest` 必须逐值相等。
         *
         * 代价（登记，不隐蔽）：越过钳制点后每 ~64 行才整列搬一次（而非每 128 行），
         * 复制总量从 O(n) 摊到 O(n²/64)·行字节；n=168 实测增量 ≈ 3 次整列搬，n≤1000 内可忽略。
         */
        const val RESERVE_TAIL_FLOOR_ROWS: Int = 64

        /** 空尾的**比例上限**：`needed shr RESERVE_TAIL_SHIFT`（1/8），与下限取大者后参与钳制。 */
        const val RESERVE_TAIL_SHIFT: Int = 3
    }
}

/**
 * 列分配的元素总数 = 行数 × 每行元素数，Long 预乘后判可分配上限。
 *
 * 旧实现直接在 Int 域乘：`capacity * blockSize` 越过 2^31 会变成负数
 * （`NegativeArraySizeException`）或偏小的正数（后续 `charIndex * blockSize + element`
 * 寻址 AIOOBE）。与 [dimProduct] 同口径，超限一律抛 CodeEE。
 */
private fun columnElements(rows: Int, blockSize: Int): Int {
    val total = rows.toLong() * blockSize.toLong()
    if (total > Int.MAX_VALUE) {
        throw CodeEE("角色表列容量溢出：$rows 行 × 每行 $blockSize 个元素 = $total，超过可分配上限")
    }
    return total.toInt()
}
