package com.eratw.port.core.save

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

/**
 * .rt2 sidecar 容器编解码 —— v6.1-u ABI-6 冻结契约（era-arch-v61u.md §8/§10）。
 *
 * `saveNN.sav` + `saveNN.sav.rt2` = 一个逻辑存档单元（SaveService 统管配对生命周期）。
 * 本文件只冻结**容器字节协议**（纯 ByteArray 进出，无文件 IO）；双文件 .part + fsync +
 * 定序 move + 崩溃补完、.bak 轮转、删除/复制/列表配对是 M4 SaveService 路次。
 *
 * 字节布局（大端）：
 * ```
 * i32  魔数 "ERT2" = 0x45525432
 * u16  版本 = 1
 * i64  boundUniqueCode      ┐
 * i64  boundScriptVersion   ├ 绑定三元组（读取时三重校验）
 * i32  boundSaveTextHash    ┘
 * i32 + bytes  注册表快照（PackageShortIdRegistry.encode 原样字节）
 * i32 + bytes  虚拟 NO 注册表段（VirtualCharaNoRegistry.encode 原样字节；global.rt2 恒空）
 * i32 + bytes  实体图载荷（EntityKernel 序列化，M4 定内部格式）
 * i32          分片数（≤ 4096）
 *   每片：i32 + UTF-8 packageId，i32 + bytes 载荷（≤ 32 MiB）
 * i32  CRC32（以上全部字节的校验和，尾部）
 * ```
 *
 * **v2（D-1，2026-11-08）**：新增「虚拟 NO 注册表段」。虚拟 NO ↔ EntityId 是每槽身份，
 * 读档必须先于实体图恢复（§16 第 6 点）。ABI-6 未冻结、无已发布存档 ⇒ **v1 直接拒载**，
 * 不做双版本读取面（同 [com.eratw.port.core.entity.EntityPayloadCodec] v1 的口径）。
 *
 * **孤儿分片透传**：未激活 Mod 的分片在解码后原样保留、存盘时原样回写——
 * 容器层不解释分片内容，激活与否由 SaveService 判定。
 *
 * **实体数据严禁寄生 .sav 的 emData/MapValue**（审计 19.19 + sidecar 裁定）。
 * .sav 文件结构保持 PC Emuera 兼容，但**完整扩展存档不保证 PC 可继续游戏**
 * （§8 兼容性矩阵，v6.2-c 起「100% 互通」表述作废）：扩展实体/组件/关系只有本引擎认识。
 */

/** 容器损坏 / 版本不符 / 容量越界 / CRC 失败。文案由宿主侧包成三语存档错误。 */
class SidecarFormatException(message: String) : RuntimeException(message)

/** 绑定三元组：.rt2 与 .sav 的同一性凭据，读取时逐字段校验。 */
data class SidecarBinding(
    val uniqueCode: Long,
    val scriptVersion: Long,
    val saveTextHash: Int,
)

/** 按 Package 分片的载荷（孤儿片原样透传，容器层不解释内容）。 */
class PackageChunk(val packageId: String, val payload: ByteArray)

/** 一个 .rt2 的逻辑内容（编解码均为纯函数，无文件 IO）。 */
class SidecarSaveBundle(
    val binding: SidecarBinding,
    val registrySnapshot: ByteArray,
    val entityGraphPayload: ByteArray,
    val chunks: List<PackageChunk>,
    /**
     * 虚拟 NO 注册表段（[com.eratw.port.core.entity.VirtualCharaNoRegistry.encode] 原样字节）。
     * 空 = 无投射实体（base-only 档的常态）；global.rt2 恒空（虚拟 NO 是每槽身份，§7）。
     *
     * 放在构造器**末位**是为保持既有四处位置调用点原样编译；字节布局里它排在
     * 注册表之后、实体图之前（见类 KDoc）。
     */
    val virtualNoSnapshot: ByteArray = ByteArray(0),
) {
    /** 读取侧绑定校验：三元组全等才允许应用本 sidecar。 */
    fun matchesBinding(other: SidecarBinding): Boolean = binding == other

    /** 编码为容器字节（含 CRC32 尾部）。容量越界当场拒绝。 */
    fun encode(): ByteArray {
        require(registrySnapshot.size <= MAX_REGISTRY_BYTES) {
            "注册表快照超容量: ${registrySnapshot.size}"
        }
        require(virtualNoSnapshot.size <= MAX_VIRTUAL_NO_BYTES) {
            "虚拟 NO 注册表段超容量: ${virtualNoSnapshot.size}"
        }
        require(entityGraphPayload.size <= MAX_GRAPH_BYTES) {
            "实体图载荷超容量: ${entityGraphPayload.size}"
        }
        require(chunks.size <= MAX_CHUNKS) { "分片数超容量: ${chunks.size}" }
        for (chunk in chunks) {
            require(chunk.payload.size <= MAX_CHUNK_BYTES) {
                "分片 [${chunk.packageId}] 载荷超容量: ${chunk.payload.size}"
            }
        }
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeInt(MAGIC)
        out.writeShort(FORMAT_VERSION)
        out.writeLong(binding.uniqueCode)
        out.writeLong(binding.scriptVersion)
        out.writeInt(binding.saveTextHash)
        out.writeInt(registrySnapshot.size)
        out.write(registrySnapshot)
        out.writeInt(virtualNoSnapshot.size)
        out.write(virtualNoSnapshot)
        out.writeInt(entityGraphPayload.size)
        out.write(entityGraphPayload)
        out.writeInt(chunks.size)
        for (chunk in chunks) {
            val nameBytes = chunk.packageId.toByteArray(Charsets.UTF_8)
            out.writeInt(nameBytes.size)
            out.write(nameBytes)
            out.writeInt(chunk.payload.size)
            out.write(chunk.payload)
        }
        out.flush()
        val body = buf.toByteArray()
        val crc = CRC32()
        crc.update(body)
        val withTrailer = ByteArrayOutputStream(body.size + 4)
        withTrailer.write(body)
        DataOutputStream(withTrailer).writeInt(crc.value.toInt())
        return withTrailer.toByteArray()
    }

    companion object {
        /** 魔数 "ERT2"。 */
        const val MAGIC: Int = 0x45525432

        const val FORMAT_VERSION: Int = 2

        /** 实体图载荷上界 64 MiB。 */
        const val MAX_GRAPH_BYTES: Int = 64 * 1024 * 1024

        /** 注册表快照上界（32768 条 × 每条名长上限的宽松闸，取 8 MiB）。 */
        const val MAX_REGISTRY_BYTES: Int = 8 * 1024 * 1024

        /** 虚拟 NO 注册表段上界（2^20 条 × 12 字节 ≈ 12 MiB；取 16 MiB 与条目闸对齐）。 */
        const val MAX_VIRTUAL_NO_BYTES: Int = 16 * 1024 * 1024

        /** 分片数上界。 */
        const val MAX_CHUNKS: Int = 4096

        /** 单片载荷上界 32 MiB。 */
        const val MAX_CHUNK_BYTES: Int = 32 * 1024 * 1024

        /** 最小容器长度：头 26 + 四段长度字 16 + CRC 4。 */
        private const val MIN_BYTES: Int = 4 + 2 + 8 + 8 + 4 + 4 + 4 + 4 + 4 + 4

        /**
         * 解码并全程校验：长度/魔数/版本/容量闸/CRC32。
         * 任何损坏抛 [SidecarFormatException]——容器级损坏前置拦截，
         * 不进「先复位后解析」的官方语义路径（那是 .sav 变量层，ADR-005 不动）。
         */
        fun decode(bytes: ByteArray): SidecarSaveBundle {
            fun fail(msg: String): Nothing = throw SidecarFormatException(msg)
            if (bytes.size < MIN_BYTES) fail("长度不足: ${bytes.size}")
            val bodyLen = bytes.size - 4
            val expected = DataInputStream(ByteArrayInputStream(bytes, bodyLen, 4)).readInt()
            val crc = CRC32()
            crc.update(bytes, 0, bodyLen)
            if (crc.value.toInt() != expected) fail("CRC32 不符（容器损坏）")

            val input = DataInputStream(ByteArrayInputStream(bytes, 0, bodyLen))
            if (input.readInt() != MAGIC) fail("魔数不符")
            val version = input.readUnsignedShort()
            if (version != FORMAT_VERSION) fail("版本 $version 不支持（本引擎 v$FORMAT_VERSION）")
            val binding = SidecarBinding(input.readLong(), input.readLong(), input.readInt())

            fun readBlob(cap: Int, what: String): ByteArray {
                val len = input.readInt()
                if (len < 0 || len > cap) fail("$what 长度越界: $len")
                if (len > input.available()) fail("$what 长度超出剩余字节: $len")
                val data = ByteArray(len)
                input.readFully(data)
                return data
            }

            val registry = readBlob(MAX_REGISTRY_BYTES, "注册表快照")
            val virtualNo = readBlob(MAX_VIRTUAL_NO_BYTES, "虚拟 NO 注册表段")
            val graph = readBlob(MAX_GRAPH_BYTES, "实体图载荷")
            val chunkCount = input.readInt()
            if (chunkCount < 0 || chunkCount > MAX_CHUNKS) fail("分片数越界: $chunkCount")
            val chunks = ArrayList<PackageChunk>(chunkCount)
            repeat(chunkCount) {
                val nameLen = input.readInt()
                if (nameLen <= 0 || nameLen > input.available()) fail("分片名长越界: $nameLen")
                val nameBytes = ByteArray(nameLen)
                input.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                if (name.isEmpty()) fail("空分片名")
                chunks.add(PackageChunk(name, readBlob(MAX_CHUNK_BYTES, "分片 [$name]")))
            }
            if (input.available() != 0) fail("尾部多余字节")
            return SidecarSaveBundle(binding, registry, graph, chunks, virtualNo)
        }
    }
}