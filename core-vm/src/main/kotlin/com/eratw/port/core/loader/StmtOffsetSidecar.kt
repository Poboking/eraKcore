package com.eratw.port.core.loader

import com.eratw.port.core.code.ErbcFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

/**
 * 段 3（语句池）「语句记录偏移表」侧车（2026-10-23 装载提速）。
 *
 * ## 为什么需要它
 *
 * 惰性语句池装载时只该建「每条记录在段里的起始偏移」；改造前这一步是
 * `buildRecordOffsets()` **逐条完整反序列化 171 万条语句**（连 `Expr` 子树和原文
 * `String` 一起建出来再丢掉），只为拿一个 `Int`。真机实测这一段 **9.5 s**
 * （`load.program.段3 语句记录 1/8..8/8` 各 ≈1.1 s），并且是整个装载期最大的
 * 短命对象来源（GC 主源）。
 *
 * 偏移表本质上是**构建期就已知的常量**（`ErbcWriter.writeStatements` 里每条
 * 记录写入前 `entries.size` 就是它的相对偏移），所以这里把它挪到构建期：
 * `erbcc` 与 `program.erbc` 同目录落一份 `program.stmt.idx`，装载期直接读
 * （≈6.8 MB 顺序读，真机 <100 ms）。
 *
 * ## 为什么不改进 erbc 容器格式
 *
 * 容器段表是定长 9 段、追加段要走 v3 版本门；而侧车是**可选**的：缺失 / 尺寸
 * 不符 / 校验不过 ⇒ 装载器**原样回落到扫描**，旧产物与既有逐字节不变性都不受影响。
 *
 * ## 文件格式（`program.stmt.idx`，小端）
 *
 * v2（当前写出格式，[VERSION_PAIRED]）：
 *
 * ```
 * 0   8   magic "ERSTMT01"
 * 8   4   u32 version = 2
 * 12  4   u32 stmtCount        （= 段 3 语句池长度）
 * 16  4   u32 recordBytes      （= 段 3 记录区总字节数；装载期必须等于解出来的真实长度）
 * 20  4   u32 recordCrc32      （= 段 3 记录区全部字节的 CRC-32/ISO-HDLC）
 * 24  4×N u32 offsets[i]       （相对**记录区起点**的偏移，单调递增，offsets[0] = 0）
 * ```
 *
 * v1（2026-10-23 的旧写出格式，只有 20 字节头、**没有** `recordCrc32`）：
 * 装载期**一律按不可信处理并回落扫描**（它没有与产物配对的任何凭证，正是 CMP-01
 * 静默解错语句的成因）。旧产物仍可正常装载，只是失去加速；重跑 `erbcc` 即恢复。
 *
 * ## 读侧信任模型（CMP-01/CMP-02/CMP-03）
 *
 * 「单调 + 条数 + 首条为 0 + 末条收在段尾」不够：中段偏移整体平移 1 字节时这四条
 * 全都成立，装载器会按错位边界解出**一条合法但错位的语句**（`valueOf` 不失败）。
 * 所以读侧分两层校验（实现在 `ErbcStreamDecoder.validateRecordOffsets`）：
 *
 * 1. **产物配对（内容级）**：`recordBytes` 必须等于解码出来的真实记录区长度，
 *    且整段记录区 CRC32 必须等于头里记的值 ⇒ 「侧车与产物不是一对」（重编 erbc
 *    忘重写侧车、热更新只换其一、erbcc 崩在两次 write 之间）在这里被拒；
 * 2. **边界一致性（内容级抽查）**：首两条、末两条 + 每个 1/8 条带的起点各回放一条
 *    记录，要求它正好收在下一条偏移（末条则正好收在段尾）⇒ 「指纹对得上、但表本身
 *    被平移/改坏」在这里被拒。指纹只覆盖产物字节、不覆盖表，所以这一层不能省。
 *
 * 两层都过才走加速档，否则发「偏移表不可信（回落扫描）」并回落到逐条扫描。
 */
object StmtOffsetSidecar {

    /** 与 `program.erbc` 同目录的侧车文件名。 */
    const val FILE_NAME: String = "program.stmt.idx"

    /** 与侧车同目录的产物文件名（配对指纹的取数对象）。 */
    const val PROGRAM_FILE_NAME: String = "program.erbc"

    private const val MAGIC: String = "ERSTMT01"

    /** 旧格式：只有 20 字节头、没有产物配对指纹（装载期按不可信处理）。 */
    const val VERSION_LEGACY: Int = 1

    /** 当前格式：头里带 `recordBytes` + 记录区 CRC32。 */
    const val VERSION_PAIRED: Int = 2

    private const val VERSION: Int = VERSION_PAIRED

    /** v1 头长度：magic(8) + version(4) + count(4) + recordBytes(4)。 */
    const val HEADER_BYTES_LEGACY: Int = 20

    /** 当前头长度：magic(8) + version(4) + count(4) + recordBytes(4) + recordCrc32(4)。 */
    const val HEADER_BYTES: Int = 24

    /** 侧车头（读侧的配对判据输入）。 */
    data class Header(
        val version: Int,
        val stmtCount: Int,
        val recordBytes: Int,
        /** 记录区 CRC32；v1 恒 0（无此字段）。 */
        val recordCrc32: Int,
    )

    /** `program.erbc` 同目录的侧车路径（不判断存在性）。 */
    fun pathFor(programPath: Path): Path = programPath.resolveSibling(FILE_NAME)

    /** 侧车同目录的产物路径（不判断存在性）。 */
    fun programPathFor(sidecarPath: Path): Path = sidecarPath.resolveSibling(PROGRAM_FILE_NAME)

    /**
     * 只读侧车头（v2 的 24 字节 / v1 的 20 字节；只读这么多字节，不碰偏移表）。
     *
     * @return 头；文件缺失、魔数/版本/尺寸不符一律 null（调用方回落到扫描，不报错）
     */
    fun readHeader(programPath: Path): Header? {
        val path = pathFor(programPath)
        if (!Files.isRegularFile(path)) return null
        return try {
            FileChannel.open(path, StandardOpenOption.READ).use { ch -> readHeaderFrom(ch) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从**位置为 0** 的只读通道解出头（[readHeader] 与 [read] 共用，两个入口的判据必须同源）。
     *
     * 只取 [HEADER_BYTES]（v2 的完整头）；文件比这短就先读到 EOF，缓冲区尾字节留在 0 上。
     * 偏移表不靠这里的通道位置续读，由 [read] 按绝对位置取（v1 的头比 v2 短 4 字节）。
     */
    private fun readHeaderFrom(ch: FileChannel): Header? {
        val size = ch.size()
        if (size < HEADER_BYTES_LEGACY.toLong() || size > Int.MAX_VALUE.toLong()) return null
        val raw = ByteBuffer.allocate(HEADER_BYTES)
        var read = 0
        while (raw.hasRemaining()) {
            val n = ch.read(raw)
            if (n < 0) break
            read += n
        }
        if (read < HEADER_BYTES_LEGACY) return null
        // 「短到不足一个 u32」的残件（整个文件只有 20 到 23 字节）走这条路：拿 capacity
        // 恒为 HEADER_BYTES 的底层数组解析，尾字节是 0，尺寸校验把它判成 null —— 而不是
        // 按实际读到的长度包裹、在解 recordCrc32 时抛 BufferUnderflowException 冒到装载链
        //（侧车是可选加速件，读坏了的正确处置就是回落扫描）。
        val buf = ByteBuffer.wrap(raw.array()).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8)
        buf.get(magic)
        if (String(magic, Charsets.US_ASCII) != MAGIC) return null
        val version = buf.int
        if (version != VERSION_LEGACY && version != VERSION_PAIRED) return null
        val count = buf.int
        val recordBytes = buf.int
        val crc = if (version >= VERSION_PAIRED) buf.int else 0
        if (count < 0 || recordBytes < 0) return null
        return Header(version, count, recordBytes, crc)
    }

    /**
     * 读侧车（v1/v2 都读；**是否可信由 [Header] + 记录区内容判定**，不在这一步下结论）。
     *
     * 偏移表按 [OFFSET_CHUNK] 分块从小端解出**直接灌进最终的 `IntArray`**，不再整份
     * `Files.readAllBytes`：真机 512 MiB 堆限下，装载走到这里时峰值已经贴顶，旧写法要
     * 在同瞬间排下 6,866,568 B 的文件副本 + 6,866,560 B 的偏移表这两笔同尺寸分配，
     * 后者正是 2026-10-28 崩溃报告里那笔失败的分配。改完这一段的最大额外驻留是一块
     * [OFFSET_CHUNK]，判据（校验项、null 面、尺寸闸）与改造前逐输入一致。
     *
     * @return 偏移数组（长度 = stmtCount）；文件缺失、魔数/版本/尺寸不符一律 null
     *   （调用方回落到扫描，不报错 —— 侧车本来就是可选加速件）
     */
    fun read(programPath: Path): IntArray? {
        val path = pathFor(programPath)
        if (!Files.isRegularFile(path)) return null
        try {
            FileChannel.open(path, StandardOpenOption.READ).use { ch ->
                val header = readHeaderFrom(ch) ?: return null
                val headerBytes =
                    if (header.version >= VERSION_PAIRED) HEADER_BYTES else HEADER_BYTES_LEGACY
                val expected = headerBytes.toLong() + header.stmtCount.toLong() * 4L
                if (ch.size() != expected) return null
                // 头是按 HEADER_BYTES 读的，v1 的偏移表却在第 20 字节就开始 ⇒ 按绝对位置取
                return ch.readOffsets(headerBytes.toLong(), header.stmtCount)
            }
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * 从通道的绝对位置 [from] 顺序解出 [count] 个小端 u32（值按 [Int] 原样落，高位为 1
     * 的记号仍是负数，与旧的 `ByteBuffer.getInt` 逐位相同）。
     *
     * 只复用一块 [OFFSET_CHUNK] 的字节缓冲，小端换算走手写位运算（同 `CodeSource` 的
     * 字节转 int 口径）：不调 `Buffer` 的 clear/flip/limit —— 那些「JDK 9+ 才在
     * `ByteBuffer` 上协变重声明」的方法在 android.jar 编译面没有，调用点会在真机 ART
     * 运行时抛 NoSuchMethodError（`AndroidApiCompatTest` 的口径，见 `ErbcStream.readInto`
     * 的同条注释）。
     *
     * 调用方已按文件实际尺寸确认偏移区完整；读到 EOF 早于条数（文件在两次读之间被换小）
     * 也返回 null，与旧写法「尺寸校验不过」同结果。
     */
    private fun FileChannel.readOffsets(
        from: Long,
        count: Int,
    ): IntArray? {
        val offsets = IntArray(count)
        val chunk = ByteArray(OFFSET_CHUNK)
        var index = 0
        var pos = from
        while (index < count) {
            val len = minOf(OFFSET_CHUNK, (count - index) * 4)
            val dst = ByteBuffer.wrap(chunk, 0, len)
            var got = 0
            while (got < len) {
                val n = read(dst, pos + got)
                if (n < 0) return null
                got += n
            }
            var j = 0
            while (j < len) {
                offsets[index++] = (chunk[j].toInt() and 0xFF) or
                    ((chunk[j + 1].toInt() and 0xFF) shl 8) or
                    ((chunk[j + 2].toInt() and 0xFF) shl 16) or
                    ((chunk[j + 3].toInt() and 0xFF) shl 24)
                j += 4
            }
            pos += len
        }
        return offsets
    }

    /**
     * 以**只读映射**打开侧车偏移表（M13-MEM10 刀 E：堆内 6.5 MiB → 0）。
     *
     * 侧车 6,866,568 B 里偏移区是连续的小端 u32，本质是「产物旁边的一张常量表」；
     * 装载期整张读进 `IntArray` 只为运行期按语句下标随机取一条。改成映射后：
     * - Java 堆不再有那 171 万个 int（真机 §1 的「段3 语句记录偏移表 6.5 MiB」整行消失）；
     * - 取用仍是一次 4 B 读（映射页由内核按需调页，落在文件页缓存里，可回收）；
     * - 侧车与产物不同寿不影响语义：映射在 [Program] 存活期间有效，`erbcc` 原子改名
     *   换侧车不改旧映射的内容（旧 inode 仍被映射钉住）。
     *
     * 头部解析、尺寸闸与 [read] 逐字同源（同一个 [readHeaderFrom]）；返回 null 的口径也
     * 相同：文件缺失 / 魔数 / 版本 / 尺寸不符 ⇒ 调用方回落扫描，不报错。
     *
     * 映射对象由调用方（[RecordOffsets]）持住；通道在这里关掉（关通道不 unmap）。
     */
    internal fun openMapped(programPath: Path): SidecarOffsets? {
        val path = pathFor(programPath)
        if (!Files.isRegularFile(path)) return null
        try {
            val ch = FileChannel.open(path, StandardOpenOption.READ)
            try {
                val header = readHeaderFrom(ch) ?: return null
                val headerBytes =
                    if (header.version >= VERSION_PAIRED) HEADER_BYTES else HEADER_BYTES_LEGACY
                val expected = headerBytes.toLong() + header.stmtCount.toLong() * 4L
                if (ch.size() != expected) return null
                if (header.stmtCount == 0) return SidecarOffsets(ByteBuffer.allocate(0), 0)
                val buf = ch.map(
                    FileChannel.MapMode.READ_ONLY,
                    headerBytes.toLong(),
                    header.stmtCount.toLong() * 4L,
                )
                buf.order(ByteOrder.LITTLE_ENDIAN)
                return SidecarOffsets(buf, header.stmtCount)
            } finally {
                ch.close()
            }
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * 写侧车（构建期，`erbcc` 用；格式恒为 v2）。
     *
     * 配对指纹**由本方法自己从同目录 `program.erbc` 取**（读段 3 记录区的真实字节）：
     * 写侧签名因此不需要额外参数，`ErbcArtifacts` 的调用点保持原样即可拿到凭证。
     * 取不到产物（文件缺失/段 3 缺失/记录区长度不合理）时**直接失败**而不是写一份
     * 「无凭证侧车」—— 那会让真机静默回落到 9.5 s 的扫描档，属于必须当场暴露的构建事故。
     *
     * 先写 `.tmp` 再原子改名：产物目录是跨进程共享的（可能与正在跑的装载/打包并存），
     * 半截侧车必须不能被读到；读到也会被尺寸校验挡下，但原子改名让「要么旧要么新」更干净。
     */
    fun write(
        path: Path,
        offsets: IntArray,
        recordBytes: Int,
    ) {
        val programPath = programPathFor(path)
        val recordCrc32 = try {
            MappedErbc.open(programPath).use { mapped -> recordRegionCrc(mapped, recordBytes) }
        } catch (e: RuntimeException) {
            throw IllegalStateException(
                "侧车不能无配对凭证落盘：读不到同目录 $PROGRAM_FILE_NAME 的段 3 记录区" +
                    "（$programPath：${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        }
        val bytes = ByteArray(HEADER_BYTES + offsets.size * 4)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC.toByteArray(Charsets.US_ASCII))
        buf.putInt(VERSION)
        buf.putInt(offsets.size)
        buf.putInt(recordBytes)
        buf.putInt(recordCrc32)
        for (o in offsets) buf.putInt(o)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** 段 3 记录区 CRC32（区域由产物自己的段 3 上界与 [recordBytes] 反推）。 */
    private fun recordRegionCrc(
        mapped: MappedErbc,
        recordBytes: Int,
    ): Int {
        if (recordBytes <= 0) {
            throw IllegalStateException("记录区长度为 $recordBytes（必须 > 0）")
        }
        if (!mapped.header.hasSection(ErbcFormat.SECTION_STATEMENTS)) {
            throw IllegalStateException("产物没有段 ${ErbcFormat.SECTION_NAMES[ErbcFormat.SECTION_STATEMENTS]}")
        }
        val end = mapped.endOf(ErbcFormat.SECTION_STATEMENTS)
        val from = end - recordBytes
        if (from < mapped.offsetOf(ErbcFormat.SECTION_STATEMENTS)) {
            throw IllegalStateException(
                "记录区 [$from,$end) 越出段 ${ErbcFormat.SECTION_NAMES[ErbcFormat.SECTION_STATEMENTS]}" +
                    "（段首 ${mapped.offsetOf(ErbcFormat.SECTION_STATEMENTS)}）",
            )
        }
        return regionCrc32(mapped, from, end)
    }

    /**
     * 区间 `[from, to)` 的 CRC-32/ISO-HDLC（JDK/Android 同一个 `java.util.zip.CRC32`）。
     *
     * 按 [CRC_CHUNK] 分块映射 + 复用一块缓冲：整段一次映射在低地址空间设备上不划算，
     * 逐块映射也能让页缓存按需进入。两侧（写侧 [`write`] 与读侧校验）**用同一份实现**，
     * 所以「同一条记录区」在两处必然算出同一个值。
     */
    internal fun regionCrc32(
        mapped: MappedErbc,
        from: Int,
        to: Int,
    ): Int {
        require(from >= 0 && to >= from && to <= mapped.byteSize) {
            "CRC 区间 [$from,$to) 非法（文件长 ${mapped.byteSize}）"
        }
        val crc = CRC32()
        val buf = ByteArray(CRC_CHUNK)
        var pos = from
        while (pos < to) {
            val len = minOf(CRC_CHUNK, to - pos)
            val win = mapped.mapWindow(pos, pos + len)
            win.get(buf, 0, len)
            crc.update(buf, 0, len)
            pos += len
        }
        return crc.value.toInt()
    }

    /** CRC 分块大小（地址空间占用与顺序读吞吐的折中）。 */
    private const val CRC_CHUNK: Int = 1 shl 20

    /**
     * 偏移表分块读大小（64 KiB）：装载期这一段的**最大额外驻留**就是它，
     * 不再是整份 `program.stmt.idx`（2026-10-28 真机堆顶 OOM 的成因）。
     * 必须是 4 的倍数 —— 每条偏移正好 4 字节，块内不留半条记录。
     */
    private const val OFFSET_CHUNK: Int = 1 shl 16
}

/**
 * 侧车偏移区的**映射视图**（M13-MEM10 刀 E）：一张小端 u32 表的只读映射 + 条数。
 *
 * 只做「第 i 条相对偏移」这一件事；判据（单调、首条为 0、末条收在段尾）在
 * [ErbcStreamDecoder] 的校验里做，校验通过后由 [RecordOffsets.mapped] 按记录区起点
 * 换成绝对偏移读面。映射对象被 [RecordOffsets] 持住 ⇒ 与 `Program` 同寿。
 */
internal class SidecarOffsets(
    private val buffer: ByteBuffer,
    val count: Int,
) {
    /** 相对**记录区起点**的偏移（侧车原值）。 */
    fun rel(index: Int): Int = buffer.getInt(index shl 2)

    /** 叠加记录区起点后的绝对偏移读面。 */
    fun absolute(base: Int): RecordOffsets = RecordOffsets.mapped(buffer, count, base)
}

/**
 * 语句记录起始偏移（绝对，可直接喂 `StreamCursor`）——两种形态：
 *
 * - [heap]：回落扫描当场算出的 `IntArray`（旧形态，1,716,636 × 4 B = 6.5 MiB）；
 * - 映射：[SidecarOffsets] 的 u32 表 + 记录区起点，读一条 = 一次 4 B 映射读（堆内 0）。
 *
 * 取用面只有 `LazyStatementRecords` 的「首次物化某条语句」——每条语句最多一次，
 * 与真正的记录解码（几十字节 + 节点子树）比是零头，映射页在文件页缓存里。
 */
internal class RecordOffsets private constructor(
    val size: Int,
    private val heap: IntArray?,
    private val buf: ByteBuffer?,
    private val base: Int,
) {
    fun get(index: Int): Int =
        if (heap != null) heap[index] else base + buf!!.getInt(index shl 2)

    companion object {
        /** 回落扫描档：绝对偏移的堆内数组。 */
        fun heap(absolute: IntArray): RecordOffsets =
            RecordOffsets(absolute.size, absolute, null, 0)

        /** 侧车映射档：相对偏移 + 记录区起点。 */
        fun mapped(buf: ByteBuffer, count: Int, base: Int): RecordOffsets =
            RecordOffsets(count, null, buf, base)
    }
}

