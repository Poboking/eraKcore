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
 * 容量本身属分配性副产品：`l3/StateHash` 的口径把它和「超出 count 的尾部」一起排除在
 * 状态哈希之外（`StateHash.kt` 类 KDoc 第 6 条）。
 */
class CharacterTable(
    private val sizes: VariableSizes,
    private val config: VarConfig,
    requestedCapacity: Int = 1,
) {
    var count: Int = 0
        private set

    private var capacity: Int = requestedCapacity.coerceAtLeast(1)

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
        val blockSize: Int = sizes.fold(1) { a, b -> a * b }
        var data: LongArray = LongArray(capacity * blockSize)
            private set

        fun grow(newCapacity: Int, count: Int) {
            val next = LongArray(newCapacity * blockSize)
            data.copyInto(next, 0, 0, count * blockSize)
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
            val next = LongArray(capacity * blockSize)
            for (n in order.indices) {
                data.copyInto(next, n * blockSize, order[n] * blockSize, (order[n] + 1) * blockSize)
            }
            data = next
        }
    }

    class StrCol(val sizes: IntArray, capacity: Int) {
        val blockSize: Int = sizes.fold(1) { a, b -> a * b }
        var data: IntArray = IntArray(capacity * blockSize)
            private set

        fun grow(newCapacity: Int, count: Int) {
            val next = IntArray(newCapacity * blockSize)
            data.copyInto(next, 0, 0, count * blockSize)
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
            val next = IntArray(capacity * blockSize)
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

    /** 删除并左移后续角色。spec: VariableEvaluator.cs:1241-1249 (RemoveAt)。 */
    fun removeAt(index: Int) {
        if (index < 0 || index >= count) throw CodeEE("删除角色失败：角色位置 $index 越界（角色数 $count）")
        val last = count - 1
        if (index != last) {
            shiftBlock(index, last)
        }
        count--
    }

    fun clear() {
        count = 0
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

    /** 拷贝整只角色（含用户定义角色变量）。spec: CharacterData.cs:239-349 */
    fun copy(dst: Int, src: Int) {
        requireIndex(dst)
        requireIndex(src)
        for (col in int0) col.data.copyInto(col.data, dst * col.blockSize, src * col.blockSize, (src + 1) * col.blockSize)
        for (col in str0) col.data.copyInto(col.data, dst * col.blockSize, src * col.blockSize, (src + 1) * col.blockSize)
        for (col in int1) col.data.copyInto(col.data, dst * col.blockSize, src * col.blockSize, (src + 1) * col.blockSize)
        for (col in str1) col.data.copyInto(col.data, dst * col.blockSize, src * col.blockSize, (src + 1) * col.blockSize)
        for (col in int2) col.data.copyInto(col.data, dst * col.blockSize, src * col.blockSize, (src + 1) * col.blockSize)
    }

    /** 交换两角色。spec: VariableEvaluator.cs:1378 (SwapChara) */
    fun swap(a: Int, b: Int) {
        requireIndex(a)
        requireIndex(b)
        if (a == b) return
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
     */
    fun reorder(order: IntArray) {
        if (order.size != count) throw CodeEE("重排数组长度 ${order.size} 与角色数 $count 不一致")
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

    /** 角色位置对应的整行清零（不含模板默认值）。 */
    fun resetChar(pos: Int) {
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
        while (next < needed) next = next shl 1
        for (col in int0) col.grow(next, count)
        for (col in str0) col.grow(next, count)
        for (col in int1) col.grow(next, count)
        for (col in str1) col.grow(next, count)
        for (col in int2) col.grow(next, count)
        capacity = next
    }

    private fun requireIndex(index: Int) {
        if (index < 0 || index >= count) {
            throw CodeEE("角色位置 $index 越界（角色数 $count）")
        }
    }

    val allocatedCapacity: Int get() = capacity
}
