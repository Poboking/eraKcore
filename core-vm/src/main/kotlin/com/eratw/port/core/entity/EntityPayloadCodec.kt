package com.eratw.port.core.entity

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * [EntitySnapshot] ↔ 二进制的纯函数编解码（M4-1；.rt2 容器的**实体段**载荷，
 * 容器组装/CRC/分片归 SaveService 与 [com.eratw.port.core.save.SidecarSaveBundle]）。
 *
 * ## 布局（**大端**，与 .rt2 容器/注册表快照同口径；u16 = 无符号 16 位，本格式名长 ≤ 0xFFFF）
 *
 * ```
 * u32  version                        恒 2；未知版本拒绝（前向兼容另立）
 * u32  slotCount;  × u32 slot, u16 generation, u16 pkgShortId, u8 state, u16 archetypeIdx
 * u16  archetypeCount; × u16 len, utf8
 * u16  fieldCount;  × u16 len, utf8, u8 type(0=INT/1=STR), u8 entityRef(0/1)
 *        u32 intCount;  × u32 slot, i64 value
 *        u32 strCount;  × u32 slot, u16 len, utf8
 * u16  relationKindCount; × u16 len, utf8
 * u32  edgeCount;   × u64 from, u16 kindIdx, u64 to, i64 value
 * ```
 *
 * **v2 变更（关系投影协议）**：边尾部加 i64 边值。原生 RELATION 格是 Long 值，
 * 退场回写要无损就必须把值带进实体段；v1 的「只有端点」载荷记不住 100→200。
 * ABI-6 尚未冻结、无已发布存档，故直接拒载 v1（不做双版本读取面）。
 *
 * fail-closed（解码期逐项判界，损坏 = 拒载而不是带病恢复）：
 * 尾部截断 / 名长越 u16 域实读越界 / archetypeIdx、kindIdx 越各自名表 / state 非法 /
 * entityRef 字段出现非良构 EntityId —— 任一命中抛 [IllegalArgumentException]。
 */
object EntityPayloadCodec {

    private const val VERSION: Int = 2

    private const val TYPE_INT: Byte = 0
    private const val TYPE_STR: Byte = 1

    /** 编码（[snapshot] → 大端字节）。 */
    fun encode(snapshot: EntitySnapshot): ByteArray {
        val buf = ByteBuffer.allocate(estimateSize(snapshot))
        buf.putInt(VERSION)
        buf.putInt(snapshot.slots.size)
        for (s in snapshot.slots) {
            buf.putInt(s.slot)
            buf.putShort(s.generation.toShort())
            buf.putShort(s.pkgShortId.toShort())
            buf.put(s.state.toByte())
            buf.putShort(s.archetypeIdx.toShort())
        }
        writeNameTable(buf, snapshot.archetypes)
        buf.putShort(snapshot.fields.size.toShort())
        for (f in snapshot.fields) {
            writeName(buf, f.name)
            buf.put(if (f.type == ComponentFieldType.INT) TYPE_INT else TYPE_STR)
            buf.put(if (f.isEntityRef) 1 else 0)
            buf.putInt(f.intValues.size)
            for (p in f.intValues) {
                buf.putInt(p.slot)
                buf.putLong(p.value)
            }
            buf.putInt(f.strValues.size)
            for (p in f.strValues) {
                buf.putInt(p.slot)
                writeName(buf, p.value)
            }
        }
        writeNameTable(buf, snapshot.relationKinds)
        buf.putInt(snapshot.edges.size)
        for (e in snapshot.edges) {
            buf.putLong(e.from)
            buf.putShort(e.kindIdx.toShort())
            buf.putLong(e.to)
            buf.putLong(e.value)
        }
        return buf.array().copyOfRange(0, buf.position())
    }

    /** 解码（字节 → [snapshot]）；判据见类 KDoc。截断统一包成 [IllegalArgumentException]。 */
    fun decode(bytes: ByteArray): EntitySnapshot {
        val snapshot = try {
            readSnapshot(bytes)
        } catch (e: BufferUnderflowException) {
            throw IllegalArgumentException("实体段截断：剩余字节不足（共 ${bytes.size} 字节）", e)
        }
        validate(snapshot)
        return snapshot
    }

    private fun readSnapshot(bytes: ByteArray): EntitySnapshot {
        val buf = ByteBuffer.wrap(bytes)
        val version = buf.int
        require(version == VERSION) { "实体段版本不支持: $version（本读取面只认 $VERSION）" }
        val slotCount = buf.int
        val slots = ArrayList<EntitySnapshot.SlotSnapshot>(slotCount)
        for (i in 0 until slotCount) {
            val slot = buf.int
            val gen = buf.short.toInt() and 0xFFFF
            val pkg = buf.short.toInt() and 0xFFFF
            val state = buf.get().toInt()
            val archIdx = buf.short.toInt() and 0xFFFF
            slots += EntitySnapshot.SlotSnapshot(slot, gen, pkg, state, archIdx)
        }
        val archetypes = readNameTable(buf, "原型")
        val fieldCount = buf.short.toInt() and 0xFFFF
        val fields = ArrayList<EntitySnapshot.FieldSnapshot>(fieldCount)
        repeat(fieldCount) {
            val name = readName(buf, "字段")
            val type = when (buf.get()) {
                TYPE_INT -> ComponentFieldType.INT
                TYPE_STR -> ComponentFieldType.STR
                else -> throw IllegalArgumentException("实体段字段 $name 的类型字节非法")
            }
            val isRef = when (buf.get().toInt()) {
                0 -> false
                1 -> true
                else -> throw IllegalArgumentException("实体段字段 $name 的 entityRef 字节非法")
            }
            val intCount = buf.int
            require(intCount >= 0) { "实体段字段 $name 的 int 值数为负" }
            val ints = ArrayList<EntitySnapshot.IntLongPair>(intCount)
            repeat(intCount) { ints += EntitySnapshot.IntLongPair(buf.int, buf.long) }
            val strCount = buf.int
            require(strCount >= 0) { "实体段字段 $name 的 str 值数为负" }
            val strs = ArrayList<EntitySnapshot.IntStringPair>(strCount)
            repeat(strCount) { strs += EntitySnapshot.IntStringPair(buf.int, readName(buf, "字段 $name 的值")) }
            fields += EntitySnapshot.FieldSnapshot(name, type, isRef, ints, strs)
        }
        val kinds = readNameTable(buf, "关系类型")
        val edgeCount = buf.int
        require(edgeCount >= 0) { "实体段边数为负" }
        val edges = ArrayList<EntitySnapshot.EdgeSnapshot>(edgeCount)
        repeat(edgeCount) {
            val from = buf.long
            val kindIdx = buf.short.toInt() and 0xFFFF
            val to = buf.long
            val value = buf.long
            edges += EntitySnapshot.EdgeSnapshot(from, kindIdx, to, value)
        }
        if (buf.hasRemaining()) {
            throw IllegalArgumentException("实体段尾部有 ${buf.remaining()} 字节残留（格式不符）")
        }
        return EntitySnapshot(slots, archetypes, fields, kinds, edges)
    }

    /** 语义校验（下标越界/状态非法/entity-ref 非良构；编解码两侧共用的出口闸）。 */
    private fun validate(s: EntitySnapshot) {
        for (slot in s.slots) {
            // state=0 = 空槽（generation 随快照走，见 EntitySnapshot 稀疏口径）；活槽三态校验。
            require(slot.state == 0 || EntityLifecycle.isValid(slot.state)) {
                "实体段槽 ${slot.slot} 的状态非法: ${slot.state}"
            }
            if (slot.state != 0) {
                require(slot.archetypeIdx in s.archetypes.indices) {
                    "实体段槽 ${slot.slot} 的原型下标 ${slot.archetypeIdx} 越界（共 ${s.archetypes.size}）"
                }
            }
            require(slot.generation in 0..EntityIdCodec.MAX_GENERATION) {
                "实体段槽 ${slot.slot} 的 generation 越域: ${slot.generation}"
            }
            require(slot.pkgShortId in 1..EntityIdCodec.MAX_PKG_SHORT_ID) {
                "实体段槽 ${slot.slot} 的 pkgShortId 越域: ${slot.pkgShortId}"
            }
        }
        for (e in s.edges) {
            require(e.kindIdx in s.relationKinds.indices) {
                "实体段边的类型下标 ${e.kindIdx} 越界（共 ${s.relationKinds.size}）"
            }
            require(EntityIdCodec.isWellFormed(e.from) && EntityIdCodec.isWellFormed(e.to)) {
                "实体段边端点不是良构 EntityId（from=${e.from.toString(16)} to=${e.to.toString(16)}）"
            }
        }
        for (f in s.fields) {
            if (f.isEntityRef && f.type == ComponentFieldType.INT) {
                for (p in f.intValues) {
                    require(EntityIdCodec.isWellFormed(p.value)) {
                        "实体段 entity-ref 字段 ${f.name} 的值不是良构 EntityId：${p.value.toString(16)}"
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 名表与容量估算
    // ------------------------------------------------------------------

    private fun writeNameTable(buf: ByteBuffer, names: List<String>) {
        require(names.size <= 0xFFFF) { "名表超 u16 容量: ${names.size}" }
        buf.putShort(names.size.toShort())
        for (n in names) writeName(buf, n)
    }

    private fun writeName(buf: ByteBuffer, name: String) {
        val bytes = name.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "名字超 u16 容量（${bytes.size} 字节）: $name" }
        buf.putShort(bytes.size.toShort())
        buf.put(bytes)
    }

    private fun readNameTable(buf: ByteBuffer, what: String): List<String> {
        val count = buf.short.toInt() and 0xFFFF
        val out = ArrayList<String>(count)
        repeat(count) { out += readName(buf, what) }
        return out
    }

    private fun readName(buf: ByteBuffer, what: String): String {
        val len = buf.short.toInt() and 0xFFFF
        require(buf.remaining() >= len) {
            "$what 名长 $len 越出剩余字节（${buf.remaining()}）——实体段截断"
        }
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun estimateSize(s: EntitySnapshot): Int {
        var n = 16
        n += s.slots.size * 13
        n += 2 + s.archetypes.sumOf { 2 + it.toByteArray(StandardCharsets.UTF_8).size }
        for (f in s.fields) {
            n += 4 + f.name.toByteArray(StandardCharsets.UTF_8).size
            n += f.intValues.size * 12 + f.strValues.sumOf { 6 + it.value.toByteArray(StandardCharsets.UTF_8).size }
        }
        n += 2 + s.relationKinds.sumOf { 2 + it.toByteArray(StandardCharsets.UTF_8).size }
        n += 4 + s.edges.size * 28
        return n + 64
    }
}
