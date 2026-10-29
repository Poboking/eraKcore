package com.eratw.port.core.loader

/**
 * 可判等的字符串归一流：内容相等 ⇒ 同一实例，内容一字不改。
 *
 * 归一不是语义操作 —— C# 侧对字符串身份零依赖（`Argument.cs:5-9` 的
 * IsConst/ConstStr/ConstInt 全是按内容消费的普通字段，全仓无 `ReferenceEquals` /
 * `string.Intern` / `RuntimeHelpers.GetHashCode`），所以 `.erbc` 装载后再编码逐字节相同。
 *
 * 实例由**调用方持有**（无 object 单例、无静态可变状态）；装载单线程（ADR-001）。
 *
 * @param initialCapacity 起始桶数（向上取 2 的幂，至少 16）
 */
class StringPool(initialCapacity: Int = 1 shl 12) {

    /** 累计读数（装载期一次摊销，之后不再变）。 */
    class Snapshot(
        /** [intern] 调用次数。 */
        val requests: Long,
        /** 命中已有实例的次数（含传入的就是代表实例本身）。 */
        val hits: Long,
        /** 表内不同内容数（= 归一的代表实例数）。 */
        val distinct: Int,
        /** 被丢弃的重复实例的估算字节数（口径：40 B 头 + 2 B/字符）。 */
        val savedBytes: Long,
    ) {
        val misses: Long get() = requests - hits

        val hitRate: Double get() = if (requests == 0L) 0.0 else hits.toDouble() / requests

        override fun toString(): String =
            "StringPool(requests=$requests, hits=$hits, misses=$misses, distinct=$distinct, " +
                "hitRate=${(hitRate * 1000).toInt() / 10.0}%, saved=${savedBytes shr 10} KiB)"

        companion object {
            val EMPTY = Snapshot(0, 0, 0, 0)
        }
    }

    private var table: Array<String?> = arrayOfNulls<String>(poolCapacity(initialCapacity))
    private var mask: Int = table.size - 1
    private var size: Int = 0

    private var requests: Long = 0
    private var hits: Long = 0
    private var savedBytes: Long = 0

    /** 表内不同内容数。 */
    val distinct: Int get() = size

    /** 返回与 [s] 内容相等的代表实例（可能就是 [s] 自己）。 */
    fun intern(s: String): String {
        requests++
        if (size * 3 >= table.size * 2) grow()
        var i = slotOf(s)
        while (true) {
            val cur = table[i]
            if (cur == null) {
                table[i] = s
                size++
                return s
            }
            if (cur === s || cur == s) {
                hits++
                if (cur !== s) savedBytes += ESTIMATED_STRING_BYTES + 2L * s.length
                return cur
            }
            i = (i + 1) and mask
        }
    }

    /** 当前读数快照（只读，可反复取）。 */
    fun snapshot(): Snapshot = Snapshot(requests, hits, size, savedBytes)

    private fun slotOf(s: String): Int {
        val h = s.hashCode()
        return (h xor (h ushr 16)) and mask
    }

    private fun grow() {
        val old = table
        table = arrayOfNulls<String>(old.size shl 1)
        mask = table.size - 1
        for (e in old) {
            if (e == null) continue
            var i = slotOf(e)
            while (table[i] != null) i = (i + 1) and mask
            table[i] = e
        }
    }

    private companion object {
        /**
         * 一个重复 String 被丢弃时释放的估算保留量：对象头 + 缓存哈希 + 字节数组头 ≈ 40 B，
         * 再按 2 B/字符 计正文（语料为日文，UTF-16 口径；拉丁串会高估）。粗估，不做精确测量。
         */
        const val ESTIMATED_STRING_BYTES = 40L
    }
}

private fun poolCapacity(n: Int): Int {
    var c = 16
    while (c < n) c = c shl 1
    return c
}
