package com.eratw.port.core.proc

/**
 * 脚本行身份位的「函数键 → 单调 id」分配表（AUD-b5-linestamp，挖虫波 W9 判活）。
 *
 * 原版 `Process.getCurrentLine`（EmueraConsole.cs:789-797）交出去的是**行对象引用**，
 * 消费端只做相等比较（同一行恒等、换行即变）。移植侧此前把身份位打包成
 * `(key.hashCode().toLong() shl 32) xor pc`：JVM `String.hashCode` 有半数取值带符号，
 * 负 hash 经符号扩展后整个 token 为负，被消费端 `scriptLine >= 0L` 的「已接线」哨兵
 * 吞掉 ⇒ 那一半函数的身份位静默退化成行水位（实测负 hash 的语料键：`TITLE_MAIN`、
 * `TESTLIFE`、`H_EVAL`）。
 *
 * 本类改为按**首次见到顺序**发单调 id（从 0 起、恒非负），token 取
 * `[id : 32][pc : 32]`：同一函数同一 pc 恒同值、换语句即变、不同函数不同 id。
 * 表按**串内容**查而不看 hash ⇒ 同 `hashCode` 的不同串也不会折叠（原版对象恒等）。
 *
 * 线程口径：只在 VM 线程调用（[ProcessRuntime] 的两处调用点都在语句执行 / 等待路径上），
 * 与 ADR-003 一致，不加锁。
 *
 * 生命周期：实例挂在 `store.aux` 的 `proc.lineid` 槽（铁律 4：状态不落静态全局），随
 * VariableStore 一起重建；`RuntimeAux.clear()`（RESTART / 读档重建运行时）后 id 会重新
 * 分配 —— 与原版「重建后是另一批行对象」等价，消费端只做相等比较，不依赖跨重建稳定。
 */
internal class LineIdentityIds {

    private val ids = HashMap<String, Int>()

    private var next = 0

    /** 取该函数键的稳定 id；首次见到时按顺序分配（恒非负）。 */
    fun idOf(key: String): Int {
        ids[key]?.let { return it }
        val id = next
        next++
        ids[key] = id
        return id
    }

    /**
     * 打包身份位：高 32 位 = 函数 id，低 32 位 = pc。
     *
     * `pc` 由 [ProcessRuntime] 保证非负；id 从 0 递增且远小于 `Int.MAX_VALUE`
     * （语料函数表约 11 万条）⇒ 结果恒非负，不会撞上 `-1L`（未接线）与消费端
     * `>= 0L` 的「已接线」哨兵。
     */
    fun tokenOf(key: String, pc: Int): Long =
        (idOf(key).toLong() shl 32) or (pc.toLong() and 0xffffffffL)

    /** 已分配 id 的函数数（诊断 / 用例读数用）。 */
    val size: Int get() = ids.size
}
