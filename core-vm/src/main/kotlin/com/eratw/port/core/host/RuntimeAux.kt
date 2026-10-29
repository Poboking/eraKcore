package com.eratw.port.core.host

/**
 * 每 VM 一份的附属运行时状态容器（与 [com.eratw.port.core.vars.VariableStore] 同生命周期）。
 *
 * 为什么需要它：XML 文档池 / Map 池 / DataTable 池 / 图形缓冲 / Sprite 都是**跨调用存活**的
 * 进程级状态（原版是 `Program.*` 静态字段或 `GlobalStatic`），但移植版必须做到「一个 VM 实例
 * 一套状态」——测试并行、存档/读档重建、未来多开都不能串味。
 *
 * 设计取舍：不做强类型字段表，避免 `core.host` 反向依赖 `core.fn` 的具体类型；用**按名字取槽**
 * 的弱结构容器。槽一旦创建即固定类型，取错类型抛 [ClassCastException]（属于编程错误，不静默）。
 *
 * 用法：
 * ```
 * val pool = c.store.aux.slot("xml") { XmlPool() }
 * ```
 * 约定：槽名以所属能力家族为前缀（`xml` / `map` / `dt` / `gfx` / `sprite`），全小写。
 */
class RuntimeAux {
    private val slots = HashMap<String, Any>()

    /** 取槽；不存在则用 [create] 建一个并存入。非线程安全：VM 单线程（ADR-003）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> slot(name: String, create: () -> T): T {
        slots[name]?.let { return it as T }
        val v = create()
        slots[name] = v
        return v
    }

    /** 只查不建；不存在返回 null。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> peek(name: String): T? = slots[name] as T?

    /** 清空全部槽（RESTART / 读档后重建运行时用）。 */
    fun clear() = slots.clear()

    fun slotNames(): Set<String> = slots.keys.toSet()
}
