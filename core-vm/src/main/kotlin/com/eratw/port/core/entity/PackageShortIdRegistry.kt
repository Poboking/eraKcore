package com.eratw.port.core.entity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * packageId → pkgShortId 分配器 —— v6.1-u ABI-2 冻结契约（era-arch-v61u.md §7/§10）。
 *
 * 协议要点：
 * - **0 = NULL 保留**（EntityId 全零即空引用），**1 = `__core__` 固定**（构造期预注册）；
 * - 自 2 起**单调分配**，永不回收复用——generation 防悬空靠槽位侧，短 ID 侧靠单调性；
 * - **孤儿锁定**：磁盘上仍有某包 sidecar 分片（未激活 Mod 的孤儿载荷）时，该包短 ID
 *   必须保留在注册表里（[lock]）；锁定只影响「能不能忘记」，不影响分配单调性；
 * - **装载 = 全局注册表 ∪ 磁盘全部 sidecar Manifest 快照的并集**（[mergeSnapshot]）：
 *   双向冲突（同包异 ID / 同 ID 异包）当场拒绝，不静默择一；
 * - 持久化双落点：全局 `rt2_pkg_registry.bin` + 每个 .rt2 的注册表快照段
 *   （快照字节即 [encode] 产物，容器侧见 SidecarSaveBundle）。
 *
 * 实例状态（core 禁静态可变全局，AGENTS.md §2.4）；线程约束由宿主串行化保证。
 * 二进制格式（大端）：魔数 "PREG" + u16 版本=1 + i32 nextId + i32 条目数 +
 * 每条目（i32 shortId、u8 locked、i32 名长、UTF-8 名）。解码全程边界校验。
 */
class PackageShortIdRegistry {

    /** packageId → shortId。插入序 = 分配序（确定性重放）。 */
    private val byName = LinkedHashMap<String, Int>()

    /** shortId → packageId 反查。 */
    private val byId = HashMap<Int, String>()

    /** 下一个可分配短 ID；单调递增，合并时取双方最大值。 */
    private var nextId: Int = 2

    /** 孤儿载荷锁定的短 ID 集合。 */
    private val lockedIds = HashSet<Int>()

    init {
        register(CORE_PACKAGE_ID, EntityIdCodec.CORE_PKG_SHORT_ID)
    }

    val count: Int get() = byName.size

    fun shortIdOf(packageId: String): Int? = byName[packageId]

    fun packageIdOf(shortId: Int): String? = byId[shortId]

    fun isLocked(shortId: Int): Boolean = shortId in lockedIds

    /**
     * 取或分配短 ID。已注册直接返回（幂等）；新包单调分配。
     * 容量耗尽（> [EntityIdCodec.MAX_PKG_SHORT_ID]）抛 [IllegalStateException]。
     */
    fun allocate(packageId: String): Int {
        require(packageId.isNotEmpty()) { "packageId 不能为空" }
        byName[packageId]?.let { return it }
        check(nextId <= EntityIdCodec.MAX_PKG_SHORT_ID) {
            "pkgShortId 容量耗尽（上界 ${EntityIdCodec.MAX_PKG_SHORT_ID}）"
        }
        val id = nextId++
        register(packageId, id)
        return id
    }

    /**
     * 锁定短 ID（孤儿载荷存在期间不得遗忘）。未注册的 ID 拒绝锁定——
     * 锁定必须先有注册（合并快照会先注册再锁）。
     */
    fun lock(shortId: Int) {
        require(byId.containsKey(shortId)) { "短 ID $shortId 未注册，无法锁定" }
        lockedIds.add(shortId)
    }

    /**
     * 并入一份快照（sidecar Manifest 或另一全局注册表的 [encode] 字节）。
     * 并集语义：新条目照单全收（含其锁定旗标与 nextId 水位）；
     * 同包同 ID 幂等；**同包异 ID 或同 ID 异包 = 损坏/分裂，抛 [RegistryConflictException]**。
     */
    fun mergeSnapshot(bytes: ByteArray) {
        val other = decode(bytes)
        for ((name, id) in other.byName) {
            val mine = byName[name]
            if (mine != null && mine != id) {
                throw RegistryConflictException("包 [$name] 短 ID 分裂：本地 $mine vs 快照 $id")
            }
            val occupant = byId[id]
            if (occupant != null && occupant != name) {
                throw RegistryConflictException("短 ID $id 分裂：本地 [$occupant] vs 快照 [$name]")
            }
        }
        for ((name, id) in other.byName) {
            if (!byName.containsKey(name)) register(name, id)
        }
        lockedIds.addAll(other.lockedIds)
        if (other.nextId > nextId) nextId = other.nextId
    }

    /** 序列化为持久化字节（全局 bin 与 sidecar 快照共用同一格式）。 */
    fun encode(): ByteArray {
        val buf = ByteArrayOutputStream()
        DataOutputStream(buf).use { out ->
            out.writeInt(MAGIC)
            out.writeShort(FORMAT_VERSION)
            out.writeInt(nextId)
            out.writeInt(byName.size)
            for ((name, id) in byName) {
                val bytes = name.toByteArray(Charsets.UTF_8)
                out.writeInt(id)
                out.writeByte(if (id in lockedIds) 1 else 0)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }
        return buf.toByteArray()
    }

    private fun register(packageId: String, shortId: Int) {
        byName[packageId] = shortId
        byId[shortId] = packageId
    }

    companion object {
        /** 内核包固定名（短 ID 1）。 */
        const val CORE_PACKAGE_ID: String = "__core__"

        /** 魔数 "PREG"。 */
        const val MAGIC: Int = 0x50524547

        const val FORMAT_VERSION: Int = 1

        /** 条目数上界（短 ID 空间 1..32767 + 余量闸：注册表快照容量闸同 ABI-6 口径）。 */
        const val MAX_ENTRIES: Int = 32768

        /**
         * 从持久化字节重建。魔数/版本/容量/边界/双射一致性全程校验，
         * 任何损坏抛 [IllegalArgumentException]（容器侧再包成存档错误文案）。
         */
        fun decode(bytes: ByteArray): PackageShortIdRegistry {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val reg = PackageShortIdRegistry()
            fun fail(msg: String): Nothing = throw IllegalArgumentException("注册表快照损坏: $msg")
            if (bytes.size < 14) fail("长度不足")
            if (input.readInt() != MAGIC) fail("魔数不符")
            val version = input.readUnsignedShort()
            if (version != FORMAT_VERSION) fail("版本 $version 不支持（本引擎 v$FORMAT_VERSION）")
            val nextId = input.readInt()
            val count = input.readInt()
            if (count < 1 || count > MAX_ENTRIES) fail("条目数越界: $count")
            if (nextId < 2 || nextId > EntityIdCodec.MAX_PKG_SHORT_ID + 1) fail("nextId 越界: $nextId")
            // 构造期已预注册 __core__=1；快照必须包含它且一致。
            var sawCore = false
            repeat(count) {
                val id = input.readInt()
                val locked = input.readUnsignedByte()
                val nameLen = input.readInt()
                if (id !in 1..EntityIdCodec.MAX_PKG_SHORT_ID) fail("shortId 越界: $id")
                if (locked > 1) fail("locked 旗标非法: $locked")
                if (nameLen <= 0 || nameLen > bytes.size) fail("名长越界: $nameLen")
                val nameBytes = ByteArray(nameLen)
                input.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                if (name.isEmpty()) fail("空包名")
                val existingName = reg.byId[id]
                if (existingName != null && existingName != name) fail("短 ID $id 双射冲突")
                val existingId = reg.byName[name]
                if (existingId != null && existingId != id) fail("包 [$name] 双射冲突")
                if (existingId == null) reg.register(name, id)
                if (id == EntityIdCodec.CORE_PKG_SHORT_ID) {
                    if (name != CORE_PACKAGE_ID) fail("1 号短 ID 被非内核包占用: $name")
                    sawCore = true
                }
                if (locked == 1) reg.lockedIds.add(id)
            }
            if (!sawCore) fail("缺少 __core__=1 预注册条目")
            if (input.available() != 0) fail("尾部多余字节")
            // 水位必须高于一切已注册 ID，否则后续分配会撞双射。
            var maxId = 0
            for (id in reg.byId.keys) if (id > maxId) maxId = id
            if (nextId <= maxId) fail("nextId $nextId 不大于已注册最大 ID $maxId")
            reg.nextId = nextId
            return reg
        }
    }
}

/** 注册表合并冲突（同包异 ID / 同 ID 异包）——装载期拒绝，不静默择一。 */
class RegistryConflictException(message: String) : RuntimeException(message)