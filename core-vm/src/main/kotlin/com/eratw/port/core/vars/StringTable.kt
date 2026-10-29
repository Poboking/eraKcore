package com.eratw.port.core.vars

/**
 * 字符串驻留池（ADR-004：字符串走驻留池，变量槽只存 Int 索引）。
 *
 * 下标 0 约定为空串（对应原版 `string[]` 里 null 读出来按 `""` 处理，
 * spec: VariableTerm.cs:91-96），因此槽位"未赋值"与"空串"在可观测语义上等价。
 *
 * 注意（内存模型）：池**只增不减**——同一内容只存一份，但槽位被覆盖后旧内容仍留在池里。
 * 这是"Int 索引列式存储"的必然代价；ERB 字符串绝大多数来自 CSV 名字表/固定文案，
 * 去重收益远大于残留成本。若后续出现大量 `STR:x = STR:x + "..."` 循环，
 * 需要在 M5 加带引用计数的回收（TODO(verify)：原版是 `string[]` 直接持有引用，
 * 覆盖即释放；本层为了列式布局牺牲了这一点）。
 */
class StringTable {
    private val ids = HashMap<String, Int>(256)
    private val values = ArrayList<String>(256)

    init {
        values.add("")
        ids[""] = 0
    }

    /** 池中不同的字符串个数（含下标 0）。用于测试与内存观测。 */
    val distinctCount: Int get() = values.size

    /** 驻留并返回索引；相同内容永远得到同一索引。 */
    fun intern(s: String): Int {
        ids[s]?.let { return it }
        val id = values.size
        values.add(s)
        ids[s] = id
        return id
    }

    /** 读回；越界视为内部错误（不是 ERB 层可观测错误）。 */
    fun lookup(id: Int): String {
        if (id < 0 || id >= values.size) {
            throw CodeEE("字符串驻留池下标 $id 越界（池大小 ${values.size}）")
        }
        return values[id]
    }

    fun lookupOrNull(id: Int): String? =
        if (id in values.indices) values[id] else null

    fun idOf(s: String): Int? = ids[s]

    /** 只保留空串。等价于"新游戏"时的字符串重置。 */
    fun clear() {
        ids.clear()
        values.clear()
        values.add("")
        ids[""] = 0
    }
}
