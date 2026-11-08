package com.eratw.port.core.vars

/**
 * 字符串驻留池（ADR-004：字符串走驻留池，变量槽只存 Int 索引）。
 *
 * 下标 0 约定为空串（对应原版 `string[]` 里 null 读出来按 `""` 处理，
 * spec: VariableTerm.cs:91-96），因此槽位"未赋值"与"空串"在可观测语义上等价。
 *
 * 注意（内存模型）：池**只增不减**——同一内容只存一份，但槽位被覆盖后旧内容仍留在池里。
 * 这是「Int 索引列式存储」的必然代价；ERB 字符串绝大多数来自 CSV 名字表/固定文案，
 * 去重收益远大于残留成本。
 *
 * 回收**不靠引用计数**（原版是 `string[]` 直接持有引用、覆盖即释放；本层为了列式布局
 * 牺牲了这一点），而是靠**复位链上的存活集压实**（[compact]，调用方
 * `VariableStore.compactStringPool`）：复位把所有不再复活的槽清成 0，剩下的下标就是
 * 「复位后仍可达」的全部引用，按它们重建小池即可等价回收其余全部条目。
 *
 * 局内（两次复位之间）跨页的无界增长**已定量**（fix-varcapacity，2026-11-01，
 * `:core-vm:phaseHeapLadder` 真产物 erbc.v1 + 真开局脚本 `0,0`，报告 §2b/§2c 逐页计数器，
 * 计数口径 = `VarPoolFacets` 的 10.7 子块与「串表.条数」）：P2（标题链走完）90,625 条 /
 * 池面 23.0 MiB ⇒ 第 3 页复位链压实后 5,655 条 ⇒ 第 4 页 +0 / 第 5 页 **+620 条 ≈ +0.1 MiB** /
 * 第 6–8 页 ±0。⇒ 「局内跨页只增」的量级是 **≤0.1 MiB/页、数百条**，不是大头；大头是
 * 标题页的一次性灌入（P1 +22.9 MiB），而那半边已由复位链 [compact] 收掉（P4 回灌 91,546
 * 条属标题页合法驻留）。因此**不再给局内路径接第二处 compact**——收益不足 0.1 MiB/页，
 * 而误接存活集漏登记一个持有者就是存档级事故（三条契约见 [compact]）。若中后局出现
 * `STR:x = STR:x + ...` 型真无界灌入（本装置停在开局链、未覆盖中局），才需要 M5 的
 * 引用计数——那是**未取证**的另一相位，不许拿本读数外推。
 */
class StringTable {
    private var ids = HashMap<String, Int>(256)
    private var values = ArrayList<String>(256)

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

    /**
     * 只保留空串。**复位链不走这条路**（走 [compact]），只有单测与「整池作废」的重建式
     * 时机才可以用它——复位后仍存活的槽持有的是**下标**，清池会把它们打成悬空或错别名，
     * 逐条持有者清单见 `VariableStore.compactStringPool` 的 KDoc 与 [compact] 的三条契约。
     * 用例钉点：`CharacterTableTest.resetData 归还角色表容量但 GLOBALS 串槽跨复位存活`
     * （那条用例同时钉「归还的归还、保留的保留」两侧）。
     */
    fun clear() {
        ids.clear()
        values.clear()
        values.add("")
        ids[""] = 0
    }

    /**
     * 就地压实：只保留 [live] 标记为 true 的下标，其余条目（串本体 + `ids` 的反查项）
     * 一起归还 GC；返回**旧下标 → 新下标**表（长度 = 压实前的条数）。
     *
     * 三条契约（各有用例钉住，见 `StringTableTest` 的三条 compact 用例）：
     * 1. **逐字不变**：对每个 `live[old] == true` 的 `old`，压实后 `lookup(remap[old])`
     *    与压实前 `lookup(old)` **同一个 String 实例**——池对脚本层不可见，槽读回的串
     *    必须一模一样，这是本操作唯一允许保持的可观测语义。
     * 2. **顺序稳定**：保留项按**旧下标升序**重新编号，下标 0 恒为空串且恒映到 0；
     *    于是「同一旧池 + 同一存活集」的压实结果是确定的函数，与遍历顺序、哈希序无关。
     * 3. **未保留的旧下标映到 -1**：漏登记一个持有者时，那里读到的是**越界**（[lookup]
     *    抛 CodeEE），而不是同下标指向另一条串的**静默错值** —— 错别名比响更坏，所以
     *    宁可响。
     *
     * 成本：O(压实前条数) 的一次扫描 + 一次重建，**无 IO**、不做两两比较 ⇒ 复位链上可算
     * （复位本身已经对每张串列做过整列 `fill`，同量级）。
     *
     * 调用方（`VariableStore.compactStringPool`）拿到 `remap` 后**就地改写**全部载体的元素：
     * 载体的数组对象身份不变 ⇒ 绑定着数组本体的 REF 照旧看得见（spec:
     * `ReferenceToken.cs:125-128` `SetRef(Array)` 绑的就是数组对象），压实不动绑定。
     *
     * @param live 下标存活表；比当前池短时，超出部分一律按「不存活」处理（保守：
     *   越界项被映到 -1，读到时响）。
     */
    fun compact(live: BooleanArray): IntArray {
        val old = values
        val remap = IntArray(old.size) { -1 }
        val kept = ArrayList<String>(old.size)
        kept.add("")
        remap[0] = 0
        for (i in 1 until old.size) {
            if (i >= live.size || !live[i]) continue
            remap[i] = kept.size
            kept.add(old[i])
        }
        // 全存活 ⇒ kept 与 old 同长且逐项同位（升序重编号 = 恒等），不换表、不建哈希：
        // 零收益的压实不付重建成本。
        if (kept.size == old.size) return remap
        val newIds = HashMap<String, Int>(kept.size + (kept.size shr 1) + 1)
        for (k in kept.indices) newIds[kept[k]] = k
        values = kept
        ids = newIds
        return remap
    }

    /** 压实用的存活表：按当前条数开一张 `BooleanArray`（调用方逐载体标记，见 [compact]）。 */
    fun newLiveMask(): BooleanArray = BooleanArray(values.size)
}

