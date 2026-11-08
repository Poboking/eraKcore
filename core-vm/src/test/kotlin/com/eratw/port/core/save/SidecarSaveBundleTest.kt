package com.eratw.port.core.save

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ABI-6 sidecar 容器契约钉（era-arch-v61u.md §8/§10）：字节往返、绑定三元组、
 * CRC/魔数/版本拒绝、容量闸、孤儿分片原样透传、v2 虚拟 NO 段。
 */
class SidecarSaveBundleTest {

    private fun bundle(chunks: List<PackageChunk> = emptyList()) = SidecarSaveBundle(
        binding = SidecarBinding(uniqueCode = 0x1122334455667788L, scriptVersion = 4981L, saveTextHash = 0x7F7F7F7F),
        registrySnapshot = byteArrayOf(1, 2, 3, 4),
        entityGraphPayload = byteArrayOf(9, 8, 7),
        chunks = chunks,
        virtualNoSnapshot = byteArrayOf(4, 5, 6),
    )

    @Test
    fun roundtripPreservesAllSegments() {
        val original = bundle(
            listOf(
                PackageChunk("org.era.active", byteArrayOf(10, 20)),
                PackageChunk("org.era.孤儿包", byteArrayOf(30)), // UTF-8 名 + 孤儿片
            ),
        )
        val decoded = SidecarSaveBundle.decode(original.encode())
        assertEquals(original.binding, decoded.binding)
        assertArrayEquals(original.registrySnapshot, decoded.registrySnapshot)
        assertArrayEquals(original.virtualNoSnapshot, decoded.virtualNoSnapshot)
        assertArrayEquals(original.entityGraphPayload, decoded.entityGraphPayload)
        assertEquals(2, decoded.chunks.size)
        assertEquals("org.era.active", decoded.chunks[0].packageId)
        assertArrayEquals(byteArrayOf(10, 20), decoded.chunks[0].payload)
        assertEquals("org.era.孤儿包", decoded.chunks[1].packageId)
        assertArrayEquals(byteArrayOf(30), decoded.chunks[1].payload)
    }

    @Test
    fun orphanChunkIsOpaquePassthrough() {
        // 容器层不解释分片内容：任意字节（含伪造魔数）原样往返。
        val payload = byteArrayOf(0x45, 0x52, 0x54, 0x32, 0, -1, -128)
        val decoded = SidecarSaveBundle.decode(bundle(listOf(PackageChunk("inactive.mod", payload))).encode())
        assertArrayEquals(payload, decoded.chunks.single().payload)
    }

    @Test
    fun bindingTripleCheckedFieldByField() {
        val b = bundle()
        assertTrue(b.matchesBinding(b.binding))
        assertFalse(b.matchesBinding(b.binding.copy(uniqueCode = 0)))
        assertFalse(b.matchesBinding(b.binding.copy(scriptVersion = 4980L)))
        assertFalse(b.matchesBinding(b.binding.copy(saveTextHash = 0)))
    }

    @Test
    fun crcCorruptionRejected() {
        val bytes = bundle().encode()
        // 翻转绑定段一个字节（CRC 覆盖区）。
        val corruptBody = bytes.copyOf()
        corruptBody[10] = (corruptBody[10].toInt() xor 0xFF).toByte()
        assertFailsWith<SidecarFormatException> { SidecarSaveBundle.decode(corruptBody) }
        // 翻转 CRC 尾部本身。
        val corruptTrailer = bytes.copyOf()
        corruptTrailer[bytes.size - 1] = (corruptTrailer[bytes.size - 1].toInt() xor 0x01).toByte()
        assertFailsWith<SidecarFormatException> { SidecarSaveBundle.decode(corruptTrailer) }
    }

    @Test
    fun magicVersionAndTruncationRejected() {
        val bytes = bundle().encode()
        val badMagic = bytes.copyOf()
        badMagic[0] = 0
        assertFailsWith<SidecarFormatException> { SidecarSaveBundle.decode(badMagic) }
        val badVersion = bytes.copyOf()
        badVersion[5] = 99
        assertFailsWith<SidecarFormatException> { SidecarSaveBundle.decode(badVersion) }
        assertFailsWith<SidecarFormatException> { SidecarSaveBundle.decode(bytes.copyOfRange(0, 10)) }
        assertFailsWith<SidecarFormatException> { SidecarSaveBundle.decode(ByteArray(0)) }
        // 截断但 CRC 区恰好被当 body：长度闸先行。
        assertFailsWith<SidecarFormatException> { SidecarSaveBundle.decode(bytes.copyOfRange(0, bytes.size - 2)) }
    }

    @Test
    fun trailingGarbageRejected() {
        // 尾部追加字节 ⇒ CRC 校验域变化，先炸 CRC。
        assertFailsWith<SidecarFormatException> { SidecarSaveBundle.decode(bundle().encode() + byteArrayOf(0)) }
    }

    @Test
    fun encodeCapsEnforced() {
        val tooManyChunks = List(SidecarSaveBundle.MAX_CHUNKS + 1) { PackageChunk("p$it", ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { bundle(tooManyChunks).encode() }
        // 单片上界 +1 字节即拒（32 MiB + 1，测试堆可承受）。
        val oversized = bundle(listOf(PackageChunk("big", ByteArray(SidecarSaveBundle.MAX_CHUNK_BYTES + 1))))
        assertFailsWith<IllegalArgumentException> { oversized.encode() }
        // 虚拟 NO 段上界 +1 字节即拒（D-1）。
        val oversizedVirtual = SidecarSaveBundle(
            SidecarBinding(0, 0, 0),
            ByteArray(0),
            ByteArray(0),
            emptyList(),
            ByteArray(SidecarSaveBundle.MAX_VIRTUAL_NO_BYTES + 1),
        )
        assertFailsWith<IllegalArgumentException> { oversizedVirtual.encode() }
    }

    @Test
    fun emptyPayloadsRoundtrip() {
        val empty = SidecarSaveBundle(SidecarBinding(0, 0, 0), ByteArray(0), ByteArray(0), emptyList())
        val decoded = SidecarSaveBundle.decode(empty.encode())
        assertEquals(0, decoded.registrySnapshot.size)
        assertEquals(0, decoded.virtualNoSnapshot.size)
        assertEquals(0, decoded.entityGraphPayload.size)
        assertEquals(0, decoded.chunks.size)
        assertEquals(SidecarBinding(0, 0, 0), decoded.binding)
    }

    @Test
    fun v1ContainerRejected() {
        // v2 新增虚拟 NO 段；ABI-6 未冻结、无已发布存档 ⇒ 不做双版本读取，v1 直接拒载。
        val bytes = bundle().encode()
        val body = bytes.copyOfRange(0, bytes.size - 4)
        body[4] = 0
        body[5] = 1
        val v1 = retrailer(body)
        val ex = assertFailsWith<SidecarFormatException> { SidecarSaveBundle.decode(v1) }
        assertTrue("版本" in ex.message!!, "实际: ${ex.message}")
    }

    /** 改过 body 后重算 CRC，确保被拒的原因是内容而不是校验和。 */
    private fun retrailer(body: ByteArray): ByteArray {
        val crc = java.util.zip.CRC32()
        crc.update(body)
        return body + java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array()
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}