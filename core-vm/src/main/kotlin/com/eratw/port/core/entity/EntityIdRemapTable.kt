package com.eratw.port.core.entity

/**
 * 跨设备导入的 EntityId 重映射表（ABI-2 / §7 跨设备迁移协议，MR-1a 落点）。
 *
 * 场景：导入侧的本机注册表已把某个 packageId 分配给另一个短 ID（或同一短 ID 被别的
 * 包占用）。协议不允许简单拒绝或择一（§7），而是给冲突包分配新 shortId，然后**整体
 * 重映射**所有持久实体引用。
 *
 * 本类只做「单个 EntityId 的位域改写」这一最内层、最可穷举的动作：
 * - 保留 generation 与 handleSlot，只替换 pkg 段（EntityIdCodec.encode 逐段校验）；
 * - NULL 原样透传；**未映射的包原样不动**（不猜值、不顺手改写）；
 * - 非良构 Long（负数 / pkg 段为 0）直接拒绝 —— 声明为 entity-ref 的字段里出现这种值
 *   是 Mod 违约，改写它只会把坏值带进新身份空间；
 * - 映射必须**单射**：两个旧短 ID 不许折叠到同一个新短 ID（那会让两个包的实体在
 *   导入后同号，等同于「把两个独立分支合并」——v1 明确不支持，§7）。
 *
 * 遍历面（组件 EntityRef 字段 + 关系边 + Manifest entityRefs 变量槽，三面缺一不可）
 * 与重复 EntityId 导入拒绝是 MR-1b 原型装置的职责；本类不持有存储，也不扫描任何载荷。
 *
 * @see PackageShortIdRegistry 给导入侧冲突包分配新短 ID 的那一半。
 */
class EntityIdRemapTable private constructor(
    private val shortIdMap: Map<Int, Int>,
) {
    /** 旧短 ID → 新短 ID（只含真正变化的项；不可变副本）。 */
    val mappings: Map<Int, Int> = shortIdMap.toMap()

    val isEmpty: Boolean get() = mappings.isEmpty()

    /** 该旧短 ID 的目标；未映射 = null（调用方可据此决定是否扫描）。 */
    fun targetShortIdOf(oldShortId: Int): Int? = mappings[oldShortId]

    /**
     * 重映射一个 EntityId。
     *
     * [EntityIdCodec.NULL] 原样返回；未映射的包原样返回；其余保留 generation/handleSlot、
     * 只换 pkg 段。
     *
     * @throws IllegalArgumentException 非 NULL 但不是良构 EntityId（负值 / pkg 段 0）。
     */
    fun remap(id: Long): Long {
        if (EntityIdCodec.isNull(id)) return EntityIdCodec.NULL
        require(EntityIdCodec.isWellFormed(id)) {
            "不是良构 EntityId，拒绝重映射：${id.toString(16)}（声明为 entity-ref 的槽里出现了非实体值）"
        }
        val old = EntityIdCodec.pkgShortIdOf(id)
        val new = mappings[old] ?: return id
        return EntityIdCodec.encode(new, EntityIdCodec.generationOf(id), EntityIdCodec.handleSlotOf(id))
    }

    companion object {
        /** 恒等表（无需重映射；remap 只做良构校验后原样返回）。 */
        val IDENTITY: EntityIdRemapTable = EntityIdRemapTable(emptyMap())

        /**
         * 构造重映射表。逐项 fail-closed：
         * - 旧/新短 ID 都必须在 `1..MAX_PKG_SHORT_ID`；**1 号（内核包）不许参与映射**；
         * - 映射必须单射（两旧 → 一新拒绝，见类 KDoc）；
         * - 恒等项允许（导入侧可能把未冲突的包也列进来，语义是无操作）。
         */
        fun of(mappings: Map<Int, Int>): EntityIdRemapTable {
            if (mappings.isEmpty()) return IDENTITY
            val seenTargets = HashMap<Int, Int>()
            for ((old, new) in mappings) {
                require(old in 1..EntityIdCodec.MAX_PKG_SHORT_ID) {
                    "重映射源短 ID 越域：$old（合法 1..${EntityIdCodec.MAX_PKG_SHORT_ID}）"
                }
                require(new in 1..EntityIdCodec.MAX_PKG_SHORT_ID) {
                    "重映射目标短 ID 越域：$new（源 $old）"
                }
                require(old != EntityIdCodec.CORE_PKG_SHORT_ID && new != EntityIdCodec.CORE_PKG_SHORT_ID) {
                    "内核包短 ID 1 不参与跨设备重映射：$old → $new"
                }
                val prev = seenTargets.put(new, old)
                require(prev == null) {
                    "重映射目标短 ID $new 被两个源复用：$prev 与 $old" +
                        "（等价于合并两个独立分支的实体集合，v1 不支持）"
                }
            }
            return EntityIdRemapTable(mappings)
        }
    }
}
