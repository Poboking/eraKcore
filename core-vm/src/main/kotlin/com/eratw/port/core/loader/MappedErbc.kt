package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeSource
import com.eratw.port.core.code.ErbcCursor
import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcHeader
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.MappedCodeSource
import com.eratw.port.core.code.Program
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 以 `FileChannel.map`（READ_ONLY）按段直读的一份 `.erbc` 产物。
 *
 * ## 为什么要它（内存口径；规约 specs 下的 erbc-format.md §7「分片与 assets」）
 *
 * 既有 [ProgramLoader.loadFile] 一次 `Files.readAllBytes` 把整个产物拉进 Java 堆，再解出
 * `code`/`lines` 两个 IntArray —— 解码期峰值堆 = 「产物字节 + 两个大数组」。本类把**大段**
 * （段 5 CODE、段 6 LINE_TABLE、段 0 字符串池）改为**按需映射、按需切片**：
 *
 * - [header] / [offsetOf] / [instructionCount]：只碰文件头 38 字节 + 一个 u32，
 *   不读任何段正文（原路径 `probeFile` 为拿同样的读数要把整份文件读进堆）；
 * - [readCode] / [readLines]：只映射自己那一段，逐字小端装配成 IntArray，
 *   中间不产生「该段的 ByteArray 副本」；[codeSource] 更进一步，CODE 段**不装配**
 *   IntArray，直接把映射窗口交给 `Program` 当取指来源（M8-COD-EMAP）；
 * - [stringTable]：字符串表只把 `count+1` 个偏移读进堆，第 i 项在被取用时才从映射页
 *   切出并解码（[MappedStringTable.get]）；
 * - [checkSectionLayout]：装载层段长校验只需几个 u32，畸形产物在这里就被拒掉，
 *   不必先把整份文件读进堆；
 * - [toProgram]（物化）：整份解码交给 [ErbcStreamDecoder]，**任何段都不整体拷进堆**。
 *
 * ## 与「语义零改动」的分工（硬判据的实现方式）
 *
 * [toProgram] 走 [ErbcStreamDecoder]：按段开映射窗口、按绝对偏移逐字段读，**不再把整份文件
 * 读成一个 `ByteArray`**。解码语义不是「另一个实现」：`ErbcStreamDecoder` 里的
 * `decodeProgram` / `validate` / `RecordSource` 是 `core/code/ErbcReader` 同名字段的
 * 逐字副本，只换了字节源与字符串表视图（见那份文件的类 KDoc）。
 * 本类自己实现的只有两件：文件头解析（判定与消息逐字对齐 `core/code/ErbcReader.kt:307-351`）
 * 与段级取数；两者都由 `MmapLoaderTest` 对着既有 `ProgramLoaderTest` 的畸形输入面做差分钉死。
 *
 * ## 生命周期（Android 侧的真实约束）
 *
 * `MappedByteBuffer` 没有公开 `unmap`，`Cleaner` 反射在 ART 上不可用（HotSpot 9 起也被模块
 * 系统封死）。所以本类的释放策略是：**映射窗口 = 一次调用的粒度**（每个窗口只覆盖真正要读
 * 的那几个字节到一段的范围，调用结束即丢弃引用，由 GC 兜底 munmap），[close] 关通道并让后续
 * 读数直接报错。残留风险见类末 `TODO(verify)`：映射存活期间文件无法删除/替换、地址空间窗口
 * 占用、驻留 RSS 何时回落 —— 都需要真机读数，本层不做对象池（池本身即静态可变全局，铁律 4）。
 * [fullBytes] 因此走位置读而不是「整文件一次性映射」：32 位进程的地址空间是稀缺资源，
 * 而这里需要的只是一次顺序拷贝 —— 该入口已 [Deprecated]（装载链改按段流式后它只剩
 * 「改造前基线」一个用途，见该方法的 KDoc）。
 *
 * ## 线程与状态
 *
 * 通道、段表、段界数组全部挂在实例上，无静态可变字段（AGENTS §2 铁律 4）。
 * `FileChannel` / 映射缓冲的绝对位置读在单线程内安全；多线程共享同一实例需外部同步。
 */
class MappedErbc internal constructor(
    val path: Path,
    private val channel: FileChannel,
    val byteSize: Int,
    val header: ErbcHeader,
    /** 每段的上界（不含）；不存在的段为 0。与 [ProgramLoader.sectionEnds] 同源。 */
    private val ends: IntArray,
) : AutoCloseable {

    companion object {

        /**
         * 打开一份产物：只读通道 + 读文件头 + 解析段表。
         *
         * 段表畸形（magic/版本/标志位/偏移越界）在这里就抛出，**一个段正文字节都不读**；
         * 结果与 [ProgramLoader.probe] 对同一份字节的结果逐字一致（含异常类型与消息）。
         */
        fun open(path: Path): MappedErbc {
            val ch = try {
                FileChannel.open(path, StandardOpenOption.READ)
            } catch (e: IOException) {
                throw ErbcLoadException("$path: 打开失败（${e.javaClass.simpleName}: ${e.message}）", e)
            }
            return try {
                val sizeLong = ch.size()
                if (sizeLong > Int.MAX_VALUE.toLong()) {
                    // 规约 §7：v1 容器容量上界由 u32 段偏移决定；装载侧同样按 Int 偏移寻址。
                    throw ErbcLoadException("$path: 产物 $sizeLong 字节超过 u32 偏移寻址上界（Int.MAX_VALUE）")
                }
                val size = sizeLong.toInt()
                // v2 头 + 段表 = 46 字节；按「最新版本的槽数」取，读到 v1 文件时多余的读取只会被截到文件尾。
                val headLen = minOf(size, ErbcFormat.firstSectionOffset(ErbcFormat.SECTION_COUNT))
                val headBytes = ByteArray(headLen)
                readFully(ch, headBytes, 0L, path)
                val hdr = parseHeader(headBytes, size)
                MappedErbc(path, ch, size, hdr, ProgramLoader.sectionEnds(hdr, size))
            } catch (e: RuntimeException) {
                runCatching { ch.close() }
                throw e
            } catch (e: IOException) {
                runCatching { ch.close() }
                throw ErbcLoadException("$path: 读取失败（${e.javaClass.simpleName}: ${e.message}）", e)
            }
        }

        /**
         * 文件头 + 段表解析：**判定与消息逐字对齐 `core/code/ErbcReader.kt:307-351`**。
         *
         * [head] 长度 = `min(fileSize, 38)`：文件被截短到 38 以内时，[ErbcCursor] 的
         * 「需要 n 字节…总长」消息里的长度就是真实文件大小；文件够长时这些读取根本不会失败。
         * 段偏移的上界判定必须用 [fileSize]（不能用 38 的头副本长度），否则合法段表会被误判越尾。
         */
        private fun parseHeader(
            head: ByteArray,
            fileSize: Int,
        ): ErbcHeader {
            val cur = ErbcCursor(head)
            if (head.size < ErbcFormat.HEADER_BYTES) {
                throw ErbcDecodeException(0, "文件不足文件头长度($fileSize 字节)")
            }
            val m0 = cur.u8()
            val m1 = cur.u8()
            val m2 = cur.u8()
            val m3 = cur.u8()
            if (m0 != ErbcFormat.MAGIC_0 || m1 != ErbcFormat.MAGIC_1 ||
                m2 != ErbcFormat.MAGIC_2 || m3 != ErbcFormat.MAGIC_3
            ) {
                val got = listOf(m0, m1, m2, m3).joinToString(" ") { it.toString(16) }
                throw ErbcDecodeException(
                    0,
                    "magic 不符：期望 ${ErbcFormat.MAGIC_TEXT}（45 52 42 43），实为 [$got]",
                )
            }
            val version = cur.u16()
            val expectedSections = ErbcFormat.sectionCountFor(version)
            if (expectedSections < 0) {
                throw ErbcDecodeException(
                    4,
                    "不支持的 .erbc 版本 $version（本引擎只认 v${ErbcFormat.VERSION_1}/v${ErbcFormat.VERSION_2}，" +
                        "不做降级解释）",
                )
            }
            val flags = cur.u16()
            val flagMask = ErbcFormat.flagMaskFor(version)
            if ((flags and flagMask.inv()) != 0) {
                throw ErbcDecodeException(6, "标志位含 v$version 未定义位: 0x${flags.toString(16)}")
            }
            val sectionCount = cur.u16()
            if (sectionCount != expectedSections) {
                throw ErbcDecodeException(
                    8,
                    "段表槽数 $sectionCount 与 v$version 固定的 $expectedSections 不符",
                )
            }
            val offsets = IntArray(sectionCount) { cur.u32() }
            // 报错位置用 cur.pos - 4：与 reader 一样，整张表先读完再逐条判，pos 恒为头+表 ⇒ 尾-4
            for (i in 0 until sectionCount) {
                val off = offsets[i]
                if (off < 0) throw ErbcDecodeException(cur.pos - 4, "段 $i 偏移为负: $off")
                if (off != 0 && off < ErbcFormat.HEADER_BYTES) {
                    throw ErbcDecodeException(cur.pos - 4, "段 $i 偏移($off)落在文件头/段表内")
                }
                if (off >= fileSize) throw ErbcDecodeException(cur.pos - 4, "段 $i 偏移($off)越过文件尾($fileSize)")
            }
            requireBit(flags, offsets, ErbcFormat.FLAG_HAS_LINES, ErbcFormat.SECTION_LINES)
            requireBit(flags, offsets, ErbcFormat.FLAG_HAS_EXPRESSIONS, ErbcFormat.SECTION_EXPRESSIONS)
            requireBit(flags, offsets, ErbcFormat.FLAG_HAS_STATEMENTS, ErbcFormat.SECTION_STATEMENTS)
            if (version >= ErbcFormat.VERSION_2) {
                requireBit(flags, offsets, ErbcFormat.FLAG_HAS_CALL_ARGS, ErbcFormat.SECTION_CALL_ARGS)
                requireBit(flags, offsets, ErbcFormat.FLAG_HAS_PARAM_NAMES, ErbcFormat.SECTION_PARAM_NAMES)
            }
            return ErbcHeader(version, flags, sectionCount, offsets)
        }

        /** 与 `ErbcReader.kt:353-368` 同一条双向自洽校验（规约 §3），消息逐字一致。 */
        private fun requireBit(
            flags: Int,
            offsets: IntArray,
            bit: Int,
            section: Int,
        ) {
            val present = offsets[section] != 0
            val declared = (flags and bit) != 0
            if (present != declared) {
                throw ErbcDecodeException(
                    ErbcFormat.HEADER_BYTES + section * 4,
                    "标志位与段表不一致：${ErbcFormat.SECTION_NAMES[section]} 段表偏移=${offsets[section]}，" +
                        "标志位声明=${if (declared) "存在" else "不存在"}",
                )
            }
        }

        /** 位置读满 [dst]（`FileChannel.read` 允许短读，必须循环；提前 EOF 视为文件被截短）。 */
        private fun readFully(
            ch: FileChannel,
            dst: ByteArray,
            at: Long,
            path: Path,
        ) {
            var pos = at
            var written = 0
            while (written < dst.size) {
                val n = try {
                    ch.read(ByteBuffer.wrap(dst, written, dst.size - written), pos)
                } catch (e: IOException) {
                    throw ErbcLoadException("$path: 读取失败（${e.javaClass.simpleName}: ${e.message}）", e)
                }
                if (n < 0) {
                    throw ErbcLoadException("$path: 读到 EOF（已读 $written/${dst.size} 字节，位置 $pos）")
                }
                written += n
                pos += n
            }
        }

        /** 小端 u32（与 `ErbcFormat.kt:412-420` 的 ErbcCursor.u32、[ProgramLoader] 的 u32 同一条位序）。 */
        private fun le32(
            src: ByteBuffer,
            at: Int,
        ): Int = (src.get(at).toInt() and 0xFF) or
            ((src.get(at + 1).toInt() and 0xFF) shl 8) or
            ((src.get(at + 2).toInt() and 0xFF) shl 16) or
            ((src.get(at + 3).toInt() and 0xFF) shl 24)

        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocate(0)
    }

    // ------------------------------------------------------------------
    // 段表读数（不触碰段正文）
    // ------------------------------------------------------------------

    /** 标志位（[ErbcFormat.FLAG_HAS_LINES] 等）。 */
    val flags: Int get() = header.flags

    /** 该段在文件内的绝对起始偏移；0 = 该段不存在（规约 §2）。 */
    fun offsetOf(section: Int): Int {
        checkOpen()
        return header.offsets.getOrElse(section) { 0 }
    }

    /** 该段是否存在。 */
    fun isPresent(section: Int): Boolean = header.hasSection(section)

    /** 该段上界（不含）：下一个更大的段起点，没有则文件尾（与装载层段长校验同口径）。 */
    fun endOf(section: Int): Int {
        checkOpen()
        return ends.getOrElse(section) { byteSize }
    }

    /** 该段可用区间；段不存在返回 null。 */
    fun sectionRange(section: Int): LongRange? {
        val off = offsetOf(section)
        if (off == 0) return null
        return off.toLong() until endOf(section).toLong()
    }

    /** 文件内任意绝对偏移上的 u32（4 字节窗口映射，不复制周边字节）。 */
    fun u32At(offset: Int): Int {
        checkOpen()
        // CMP-10 余项：`offset + 4` 走 Long，避免 Int 回绕后判界放行（offset 接近 Int.MAX 时）。
        if (offset < 0 || offset.toLong() + 4L > byteSize.toLong()) {
            throw ErbcDecodeException(offset, "读取 u32 越界（文件长 $byteSize）")
        }
        return mapped(offset, offset + 4) { le32(it, 0) }
    }

    /**
     * CODE 段声明的指令条数（段表 + 一个 u32，不复制指令流）。
     * `n <= 0` 的报错消息与 `ProgramLoader.validateSections` 的同一条判定逐字一致。
     */
    fun instructionCount(): Int {
        val off = startOrThrow(ErbcFormat.SECTION_CODE)
        val n = u32At(off)
        if (n <= 0) throw ErbcDecodeException(off, "CODE 段指令条数($n)必须为正")
        return n
    }

    /**
     * 装载层段长校验（[ProgramLoader.validateSections] 的同一份实现，零重复）。
     * 只需段表 + CODE/LINE_TABLE 的几个计数 u32，不需要把段正文读进堆。
     */
    fun checkSectionLayout() {
        ProgramLoader.validateSections(header, byteSize, ::u32At, path.toString())
    }

    // ------------------------------------------------------------------
    // 大段惰性读数
    // ------------------------------------------------------------------

    /**
     * 段 5：指令流 → IntArray（4 字/条，顺序恒为 op, a, b, c；规约 §5.6）。
     *
     * 只映射 CODE 段自己的区间，中间不产生该段的 ByteArray 副本；装配出的数组与
     * `ErbcReader.kt:247` 的逐字 u32 读取结果相等（`MmapLoaderTest` 做全量比对，非抽样）。
     */
    fun readCode(): IntArray {
        val off = startOrThrow(ErbcFormat.SECTION_CODE)
        val n = instructionCount()
        val need = n.toLong() * Instruction.WORDS.toLong() * 4L
        val available = (endOf(ErbcFormat.SECTION_CODE) - off - 4).toLong()
        if (need > available) {
            throw ErbcDecodeException(
                off,
                "CODE 段声明 $n 条指令（需 $need 字节），本段区间只剩 $available 字节",
            )
        }
        val out = IntArray(n * Instruction.WORDS)
        mappedRange(off + 4, need) { buf ->
            for (i in out.indices) out[i] = le32(buf, i * 4)
        }
        return out
    }

    /**
     * 段 5：指令流的**只读映射字视图**（M8-COD-EMAP 的装载侧落点）。
     *
     * 与 [readCode] 读同一段、同一段布局判定（同一句报错），区别只在**不装配 `IntArray`**：
     * 窗口 = `[off + 4, off + 4 + 16n)`（段首 u32 是 instructionCount，字流紧跟其后），
     * 返回的 [MappedCodeSource] 把它连同已校验的字数交给 `Program`，运行期由 VM 按页取指。
     *
     * 窗口必须是**一个**整段映射（而不是 [WINDOW_BYTES] 分块）：`FileChannel.map` 的块
     * 是 VM 取指的生命周期底座，分块会把「跨块重映射」塞进取指热路径。
     *
     * 生命周期：返回的 [CodeSource] 持有映射块 ⇒ 映射与 `Program` 同寿。[close] 之后
     * 已建映射照样可读（JVM 语义：关通道不 unmap），只要 Program 还被引用，块就不会被回收；
     * 反过来这段映射存活期间产物文件在 Windows 上无法重写/删除（类末 TODO(verify) 第 3 条）。
     *
     * @param instructionCount 段首已由调用方读过并校验过的指令条数（> 0）
     */
    fun codeSource(instructionCount: Int): CodeSource {
        checkOpen()
        val off = startOrThrow(ErbcFormat.SECTION_CODE)
        val words = instructionCount.toLong() * Instruction.WORDS
        val need = words * 4L
        val available = (endOf(ErbcFormat.SECTION_CODE) - off - 4).toLong()
        // 判定与消息逐字对齐 readCode 的同两行（畸形产物在两条入口报同一句话）
        if (instructionCount <= 0) throw ErbcDecodeException(off, "CODE 段指令条数($instructionCount)必须为正")
        if (need > available) {
            throw ErbcDecodeException(
                off,
                "CODE 段声明 $instructionCount 条指令（需 $need 字节），本段区间只剩 $available 字节",
            )
        }
        // need <= available <= byteSize < 2^31 ⇒ 两个 toInt 都不会溢出（words < 2^29）
        val buf = mapWindow(off + 4, off + 4 + need.toInt())
        return CodeSource.ofLittleEndian(buf, words.toInt())
    }

    /**
     * 段 6：行号表 → IntArray。`HAS_LINES` 未置位时整表填 -1（规约 §3 的设计意图，
     * 与 `ErbcReader.kt:250-251` 同口径，不是降级）。
     *
     * 三条判定（带源文件 id / 行表长度与指令条数不符 / 段区间装不下）逐字对齐
     * `ProgramLoader.validateSections`，所以畸形文件在两条路径上报的是同一句话。
     */
    fun readLines(): IntArray {
        val count = instructionCount()
        if (!isPresent(ErbcFormat.SECTION_LINES)) return IntArray(count) { -1 }
        val off = startOrThrow(ErbcFormat.SECTION_LINES)
        val fileCount = u32At(off)
        if (fileCount != 0) {
            throw ErbcDecodeException(
                off,
                "行号表带源文件 id($fileCount)，但 Program.lines 只有行号（规约 §5.7）",
            )
        }
        val n = u32At(off + 4)
        if (n != count) {
            throw ErbcDecodeException(off + 4, "行号表长度($n)与 CODE 段指令条数($count)不符")
        }
        val need = 8L + n.toLong() * 4L
        val available = (endOf(ErbcFormat.SECTION_LINES) - off).toLong()
        if (n < 0 || need > available) {
            throw ErbcDecodeException(
                off,
                "LINE_TABLE 声明 $n 行（需 $need 字节），本段区间只有 $available 字节",
            )
        }
        val out = IntArray(n)
        mappedRange(off + 8, n.toLong() * 4L) { buf ->
            for (i in 0 until n) out[i] = le32(buf, i * 4)
        }
        return out
    }

    /**
     * 段 [section] 的字符串表（布局见规约 §5.1）：堆上只放 `count+1` 个偏移，条目现取现解码。
     * 段不存在返回空表（与 `ErbcReader.kt:149-154` 把缺段读成空池同口径）。
     */
    fun stringTable(section: Int): MappedStringTable {
        checkOpen()
        val off = offsetOf(section)
        if (off == 0) return MappedStringTable(this, off, IntArray(1), 0, 0)
        // 计数位的「剩余字节数」口径 = 到文件尾（ErbcCursor.remaining 就是这口径，别改成段内）
        val count = counted(off, "字符串表项数", itemBytes = 4)
        val dataLen = counted(off + 4, "字符串表数据长度")
        val indexFrom = off + 8
        val indexLen = (count.toLong() + 1L) * 4L
        if (indexFrom.toLong() + indexLen > byteSize.toLong()) {
            // 复刻 ErbcCursor.need：在第一个装不下的 u32 上失败（ErbcFormat.kt:391-395）
            val bad = (byteSize - indexFrom) / 4
            needBytes(indexFrom + bad * 4, 4, "u32")
        }
        val offsets = IntArray(count + 1)
        mappedRange(indexFrom, indexLen) { buf ->
            for (i in 0..count) offsets[i] = le32(buf, i * 4)
        }
        val tailPos = indexFrom + count * 4
        if (count == 0) {
            if (dataLen != 0) {
                throw ErbcDecodeException(tailPos, "空字符串表的数据长度应为 0，实为 $dataLen")
            }
            return MappedStringTable(this, indexFrom + 4, offsets, 0, 0)
        }
        if (offsets[0] != 0) throw ErbcDecodeException(tailPos, "字符串表首偏移应为 0，实为 ${offsets[0]}")
        if (offsets[count] != dataLen) {
            throw ErbcDecodeException(tailPos, "字符串表末偏移(${offsets[count]})与数据长度($dataLen)不符")
        }
        for (i in 1..count) {
            if (offsets[i] < offsets[i - 1]) throw ErbcDecodeException(tailPos, "字符串表偏移非单调: $i")
        }
        needBytes(indexFrom + indexLen.toInt(), dataLen, "字符串表数据")
        return MappedStringTable(this, indexFrom + indexLen.toInt(), offsets, count, dataLen)
    }

    /** 某段的原样字节（小段全读用）；段不存在返回 null。 */
    fun sectionBytes(section: Int): ByteArray? {
        val off = offsetOf(section)
        if (off == 0) return null
        val len = endOf(section) - off
        if (len <= 0) return ByteArray(0)
        val out = ByteArray(len)
        mapped(off, off + len) { buf -> buf.duplicate().get(out, 0, len) }
        return out
    }

    // ------------------------------------------------------------------
    // 物化
    // ------------------------------------------------------------------

    /**
     * 整份产物的字节（**位置读**，不建全文件映射窗口，理由见类 KDoc「生命周期」）。
     * 得到的字节与 `Files.readAllBytes` 逐字相同。
     *
     * **装载链不再走这里**：[toProgram] 按段流式解码（见 [ErbcStreamDecoder]），
     * [ProgramLoader.loadMapped] / [ProgramLoader.open] 都不产生整份产物的 `ByteArray`。
     * 保留该入口**只为「改造前」基线**（`ErbcStreamPeakTest` 用它量那一次 313.6 MiB 的
     * 整文件拷贝），生产路径与 [ProgramLoader] 的任何入口都不调用它 —— 想拿整份字节的装置
     * 必须显式写 `fullBytes()`，不给「顺手回退到全读」留默认路径。
     */
    @Deprecated(
        "装载链已按段流式解码（ErbcStreamDecoder）；本入口只为峰值测试量「改造前」的整文件拷贝，" +
            "生产路径禁止调用",
        level = DeprecationLevel.WARNING,
    )
    fun fullBytes(): ByteArray {
        checkOpen()
        val out = ByteArray(byteSize)
        readFully(channel, out, 0L, path)
        return out
    }

    /**
     * 物化成 [Program]：段布局校验通过后交给 [ErbcStreamDecoder] 按段读（**不再整份拷贝**），
     * 解出的 Program 与 [ProgramLoader.loadFile] 逐字段一致（`MmapLoaderTest` 差分 +
     * `ErbcStreamEquivalenceTest` 的真实产物计数/哈希）。
     *
     * [mark] 非空时解码器会在每个段边界与索引趟的每个 1/8 条带回调它（装载分段 mark，
     * M10-MEM 判据③）。**注意生命周期**：解出的 Program 若带惰性语句池，段 3 的读数路径
     * 归 Program 所有 ⇒ 本通道**不能**在返回前关闭（[ProgramLoader.loadMapped] 就是这样处理的：
     * 只有非惰性产物才当场 [close]）。
     */
    fun toProgram(
        mark: LoadStageMark? = null,
        lazyStatements: Boolean = true,
        /** 惰性节点重建的递归上限（CMP-21 的可配旋钮）；默认 ART 安全值 [MAX_ENSURE_DEPTH]。 */
        maxEnsureDepth: Int = MAX_ENSURE_DEPTH,
        /** 兼容装载档：true = 解码期去重与校验串行（见 [ProgramLoader.loadMapped] 的 `compat`）。 */
        serialPhases: Boolean = false,
    ): Program {
        checkOpen()
        checkSectionLayout()
        // 分段 mark（M11-MEM）：段布局校验只读几个 u32，但它是「段表被改坏」的失败点，
        // 失败屏要能把它与真正的解码段区分开。
        mark?.onStage("program.${LoadStages.SECTION_TABLE}")
        return ProgramLoader.decodeMapped(this, mark, lazyStatements, maxEnsureDepth, serialPhases)
    }

    /**
     * 开一个只读映射窗口 `[from, to)`：段级解码的字节源（[ErbcStreamDecoder.SectionWindow]
     * 每次只取一块，越界与 IO 异常收口成 [ErbcLoadException]，与既有映射路径同口径）。
     */
    internal fun mapWindow(
        from: Int,
        to: Int,
    ): ByteBuffer {
        checkOpen()
        if (to <= from) return EMPTY_BUFFER
        return mapped(from, to) { it }
    }

    /**
     * 释放映射：关通道 + 丢弃引用（幂等）。映射窗口本身要等 GC，见类 KDoc 与类末 TODO(verify)。
     * 关闭后再取任何段数据都抛 [ErbcLoadException]，而不是读到悬空内存。
     */
    override fun close() {
        closed = true
        runCatching { channel.close() }
    }

    /** 是否已 [close]。 */
    val isClosed: Boolean get() = closed

    private var closed = false

    /**
     * 把 `[from, from + len)` 读进调用方的复用缓冲（[MappedStringTable] 的逐条解码走这里）。
     *
     * 字符串表是全量产物上被取上千万次的热路径：以前每取一项都新分配一个 `ByteArray(len)`
     * （旧的 `sliceBytes` 形态），全量语料上是上千万个短命数组；缓冲改由调用方持有后，
     * 读到的字节与逐次分配逐字相同。
     */
    internal fun readInto(
        from: Int,
        len: Int,
        dst: ByteArray,
    ) {
        checkOpen()
        if (len <= 0) return
        needBytes(from, len, "切片")
        mapped(from, from + len) { buf -> buf.duplicate().get(dst, 0, len) }
    }

    private fun checkOpen() {
        if (closed) throw ErbcLoadException("$path: 映射已关闭，不能再取段数据")
    }

    private fun startOrThrow(section: Int): Int {
        val off = offsetOf(section)
        if (off == 0) throw ErbcDecodeException(0, "段 ${nameOf(section)} 不存在")
        return off
    }

    private fun nameOf(section: Int): String =
        ErbcFormat.SECTION_NAMES.getOrElse(section) { "SECTION$section" }

    /**
     * 计数位：u32 + 「负值 / 大于剩余字节数 / 大于剩余字节数可容纳的项数」三条判定
     * （前两条口径同 `ErbcFormat.kt` 的 ErbcCursor.count）。
     *
     * [itemBytes] 见 `ErbcCursor.count`：计数字段只按剩余字节判界时，`IntArray(count + 1)`
     * 这类按项分配仍可被放大成 4× 文件大小；字符串表按每项 4 字节（一个偏移）卡上界。
     */
    private fun counted(
        at: Int,
        what: String,
        itemBytes: Int = 1,
    ): Int {
        val v = u32At(at)
        if (v < 0) throw ErbcDecodeException(at, "$what 计数越界(u32=$v)")
        val remaining = byteSize - at - 4
        if (v > remaining) throw ErbcDecodeException(at, "$what 计数($v)大于剩余字节数($remaining)")
        if (v.toLong() * itemBytes > remaining.toLong()) {
            throw ErbcDecodeException(
                at,
                "$what 计数($v)超出剩余字节数($remaining)可容纳的项数" +
                    "（每项至少 $itemBytes 字节，上界 ${remaining / itemBytes}）",
            )
        }
        return v
    }

    /** 越界判定，消息逐字对齐 `ErbcFormat.kt:391-395` 的 ErbcCursor.need。 */
    private fun needBytes(
        at: Int,
        n: Int,
        what: String,
    ) {
        if (n < 0 || at.toLong() + n > byteSize.toLong()) {
            throw ErbcDecodeException(
                at,
                "$what 需要 $n 字节，文件只剩 ${byteSize - at} 字节（总长 $byteSize）",
            )
        }
    }

    /** 在绝对偏移 [from] 上映射 [length] 字节（Long 判界，防 2GB 附近的 Int 溢出）。 */
    private fun mappedRange(
        from: Int,
        length: Long,
        block: (ByteBuffer) -> Unit,
    ) {
        if (length <= 0L) {
            block(EMPTY_BUFFER)
            return
        }
        mappedLong(from.toLong(), length, block)
    }

    private fun <R> mapped(
        from: Int,
        to: Int,
        block: (ByteBuffer) -> R,
    ): R {
        if (to <= from) return block(EMPTY_BUFFER)
        return mappedLong(from.toLong(), (to - from).toLong(), block)
    }

    /**
     * 开一个只读映射窗口交给 [block]，调用结束即丢弃窗口引用（不缓存、不池化）。
     *
     * `map` 的 position/size 必须落在文件内，越界由 [needBytes] 提前拦下；
     * 平台侧的 IllegalArgumentException / IOException 一律收口成 [ErbcLoadException]，
     * 与 [ProgramLoader] 对运行期异常的处理同口径。
     */
    private fun <R> mappedLong(
        from: Long,
        size: Long,
        block: (ByteBuffer) -> R,
    ): R {
        val buf = try {
            channel.map(FileChannel.MapMode.READ_ONLY, from, size)
        } catch (e: IOException) {
            throw ErbcLoadException(
                "$path: 映射 [$from,$size) 失败（${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        } catch (e: IllegalArgumentException) {
            throw ErbcLoadException("$path: 映射区间 [$from,$size) 非法（${e.message}）", e)
        }
        return block(buf)
    }

    // TODO(verify) Android/ART 上 MappedByteBuffer 的释放：libcore 的 MappedByteBufferImpl
    //   只在对象被 GC 时 munmap；既没有公开 unmap，也没有可用的 Cleaner 反射入口。
    //   卡点（都要真机读数，桌面 JVM 无法替代）：
    //   (1) 短生命期映射的驻留 RSS 何时回落（/proc/pc/smaps 的 Mapped 行 vs Rss）；
    //   (2) 「只 probe 不物化」全量产物时映射窗口的地址空间上限（32 位进程约 3GiB）；
    //   (3) 产物热更新（覆盖同名文件）是否必须等旧映射被回收才能成功 —— 桌面 Windows
    //       已实测**会**失败：映射存活期间重写同名文件抛
    //       `FileSystemException: 另一个程序正在使用此文件`（`ErbcStreamEquivalenceTest`
    //       因此每个变异点写独立文件）。M8-COD-EMAP 之后 CODE 段的映射由 Program 持有
    //       ⇒ 热更新窗口从「装载期」拉长到「整个进程运行期」，宿主必须改成「换文件名 +
    //       重启」或等 Program 被回收；本层无法 unmap（没有公开 API）；
    //   (4) `ErbcStreamDecoder` 把单张字符串表数据块整块映射（全量语料最大 104,923,457 B），
    //       外加 CODE 段整段映射（4,800,579 条 = 76.8 MiB，与 Program 同寿）：
    //       32 位 ART 上这两块地址空间 + 堆是否够用、RSS 何时回落。
    //   本层的缓解：窗口 = 单次调用的粒度 + [close] 让悬空使用直接报错；不做对象池。
}

/**
 * 字符串表的惰性视图（规约 §5.1 的 `count+1` 偏移索引）。
 *
 * 堆上只有 `count+1` 个 Int 与数据区起点；每条字符串在被取用时才从映射页切
 * `offsets[i]..offsets[i+1]` 这几个字节，并按**严格 UTF-8** 解码
 * （非法序列 ⇒ [ErbcDecodeException]，不允许悄悄变成替换字符）。
 * 两条报错消息（下标越界 / 非法 UTF-8）逐字对齐 `ErbcFormat.kt:516-525` 的 ErbcStringView，
 * 包括「越界报 -1、非法序列报表内相对偏移」这两个细节。
 */
class MappedStringTable internal constructor(
    private val owner: MappedErbc,
    private val dataFrom: Int,
    private val offsets: IntArray,
    private val entryCount: Int,
    /** 数据区字节数（内存模型/诊断用）。 */
    val dataBytes: Int,
) {
    /** 表为空（段不存在 / 空池）时为 0。 */
    val count: Int get() = entryCount

    /**
     * 逐条解码的复用缓冲（只有超过它的那条字符串才扩一次）。
     *
     * 旧形态是「每取一项就 `MappedErbc.sliceBytes` 一个新 `ByteArray(len)`」：全量产物上千万次
     * 取值 → 上千万个短命数组。缓冲挂在实例上（不是静态可变全局），与 `MappedErbc` 既有的
     * 「单实例单线程」约定一致。
     */
    private var scratch = ByteArray(0)

    operator fun get(i: Int): String {
        if (i < 0 || i >= count) throw ErbcDecodeException(-1, "字符串表下标越界: $i（共 $count 项）")
        val from = offsets[i]
        val len = offsets[i + 1] - from
        if (len == 0) return ""
        if (scratch.size < len) scratch = ByteArray(maxOf(len, scratch.size * 2))
        owner.readInto(dataFrom + from, len, scratch)
        return try {
            StrictUtf8.decode(scratch, 0, len)
        } catch (e: CharacterCodingException) {
            // 严格解码抛 CharacterCodingException（IOException 支）；不包成 ErbcDecodeException
            // 会让它越过 ProgramLoader 的异常收口（见 ErbcFormat.kt 的 ErbcStringView 同一收口）。
            throw ErbcDecodeException(from, "字符串表第 $i 项不是合法 UTF-8", e)
        }
    }

    /**
     * 全量物化（与 [Program.string] 对拍、内存读数用）。
     *
     * 这里**不用** `ArrayList(count).apply { add(get(i)) }`：apply 的接收者是 ArrayList，
     * `get(i)` 会解析成 `ArrayList.get` 而不是本表的取值算子（2026-10-20 实际踩过的坑）。
     */
    fun materialize(): List<String> {
        val out = ArrayList<String>(count)
        for (i in 0 until count) out.add(this[i])
        return out
    }
}

/**
 * 延迟物化的产物句柄：打开即拿到段表，**Program 要等第一次 [program]** 才真的读字节解码。
 *
 * 用途：装机后 enumerate 全部产物做 manifest 对账（规约 §7 的 `probe` 口子），
 * 只把本次会话真会用到的少数产物变成堆上的 [Program]。未物化的句柄在堆上只有
 * 文件头 + 段表副本（v1 为 38 B、v2 为 46 B）加段界 Int 数组，产物正文一个字节都不进堆。
 *
 * 生命周期（[ProgramLoader.open] / [ProgramLoader.deferredMapped] 的返回物）：
 * - 打开后到 [close] 之前，句柄持有只读 `FileChannel`：映射存活期间产物文件在 Windows 上
 *   无法重写，Android 侧则占一个 fd —— 用完 [close] 是调用方义务；
 * - [program] 物化出的 [Program]：各池与行表已进堆，**指令流是一段只读映射**（见
 *   [MappedErbc.codeSource]），由 Program 自己持有 —— [close] 之后照常可跑、可反汇编、
 *   可 `.erbc` 再编码（关通道不等于 unmap），但**产物文件在 Program 存活期间不能替换/删除**
 *   （Windows 实测会 `另一个程序正在使用此文件`）；
 * - **惰性语句池**（M10-MEM）的产物例外：第一次 `CALL`/取指才回读段 3，所以物化一旦带上
 *   惰性池，[close] 就**不关通道**（关了就断掉惰性解码，而 `RecordSource`/`SectionWindow`
 *   正是从本句柄取字节的）。通道此时随 [Program] 同寿：调用方丢掉 Program 之后由 GC 回收
 *   （[MappedProgramSource] 自己不留强引用，见 [close]）；产物文件同样在那之前不可替换/删除；
 * - [close] 之后**本句柄**的任何读数（[instructionCount]、[header]、[program]、二次 [close]
 *   之外的全部段读数）都抛 [ErbcLoadException]，而不是读到悬空映射；[close] 幂等。
 *   这一条对惰性产物同样成立：失效的是句柄，不是 Program 已经拿到的那条读数路径。
 */
class MappedProgramSource internal constructor(
    file: MappedErbc,
    /** 对照档（差分装置用）：true = 段 3 走改造前的整池顺序解码，见 [ProgramLoader.deferredMappedEager]。 */
    private val eagerStatements: Boolean = false,
) : AutoCloseable {
    /**
     * 句柄持有的映射/通道。close 之后置 null —— 句柄不许留强引用（CMP-20）：
     * 惰性产物的通道寿命只该跟着 [Program] 走，句柄本身丢掉之后整块（段 3 映射 + fd）
     * 才能回收。注意 `@Volatile`：读取面有锁外快路径（见 [program]）。
     */
    @Volatile
    private var file: MappedErbc? = file

    /** close 之后仍可读的路径文本（[path] 是诊断身份，不是段读数）。 */
    private val pathText: Path = file.path

    val path: Path get() = pathText
    val byteSize: Int get() {
        checkHandleOpen()
        return fileOrThrow().byteSize
    }
    val header: ErbcHeader get() {
        checkHandleOpen()
        return fileOrThrow().header
    }

    /** 指令条数（段表 + 一个 u32，不复制指令流）。 */
    fun instructionCount(): Int {
        checkHandleOpen()
        return fileOrThrow().instructionCount()
    }

    /** 是否已物化（诊断用）。 */
    var isMaterialized: Boolean = false
        private set

    /**
     * 已物化的 [Program]（close 时置 null）。
     *
     * `@Volatile`（CMP-20）：[program] 的锁外快路径直接读它，没有它 JMM 允许读到旧值
     * ⇒ close 之后又被「二次物化」一遍（整池解码 + `internStatementArgs` 重跑，等于把
     * 惰性化白做）。写入始终在 `synchronized(this)` 里，读侧一个 volatile 读。
     */
    @Volatile
    private var cached: Program? = null

    /**
     * close 之后段读数的拒绝理由：非惰性产物是真的「映射已关闭」（通道已关），惰性产物是
     * 「通道被 Program 接管、句柄自己失效」。分开写是因为这两件事在诊断上必须能区分 ——
     * 后者不意味着文件已解锁。
     */
    @Volatile
    private var closedReason: String? = null

    /** 本句柄是否已 [close]（惰性产物下通道可能仍被 [Program] 使用，见 [close]）。 */
    val isClosed: Boolean get() = closedReason != null

    private fun checkHandleOpen() {
        closedReason?.let { throw ErbcLoadException("$pathText: $it，不能再取段数据") }
    }

    /**
     * 取本句柄的映射源；已 close 即抛。
     *
     * [closedReason] 与 [file] 都是 volatile、且写入顺序是「先置理由、后置 null」：
     * 单看 [closedReason] 有极小窗口看到 null 而 [file] 已被置空，所以段读数统一走这里
     * （缺失就按「映射已关闭」抛，绝不 NPE）。
     */
    private fun fileOrThrow(): MappedErbc =
        file ?: throw ErbcLoadException("$pathText: 映射已关闭，不能再取段数据")

    /** 首次访问才物化；之后恒返回同一实例（`Statement` 池以实例为键，不能有第二份）。 */
    fun program(): Program = program(null)

    /**
     * 物化（可选带装载分段 mark，M10-MEM）。
     *
     * 惰性语句池的产物会把「回读段 3」这条路径接走：[close] 之后**通道不能关**（关了第一次
     * `CALL` 就撞上「映射已关闭」），但句柄自己立刻失效 —— 二者不冲突，因为接走路径的是
     * [Program] 持有的 `RecordSource`，不是本句柄的调用方。
     *
     * 并发口径（CMP-20）：锁外快路径读 [cached]，所以它必须是 `@Volatile`（否则 close 把
     * 它置 null 之后，另一线程仍可能拿旧值/或反过来在 close 之后重跑整池去重）；真正的
     * 关闭判定放在同一把锁里 —— close 要么先关（这里抛「不能再取段数据」），要么等物化做完
     * 再决定通道去留，不会出现「解码中途关通道」。
     */
    fun program(mark: LoadStageMark?): Program {
        checkHandleOpen()
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            checkHandleOpen()
            val p = file?.toProgram(mark, lazyStatements = !eagerStatements)
                ?: throw ErbcLoadException("$pathText: 映射已关闭，不能再取段数据")
            cached = p
            isMaterialized = true
            return p
        }
    }

    override fun close() {
        var toClose: MappedErbc? = null
        synchronized(this) {
            if (closedReason != null) return
            val p = cached
            // 句柄不留强引用：惰性形态下通道与映射的寿命只该跟着 Program 走，调用方丢掉
            // Program 之后这一整块（段 3 的映射 + fd）才有回收机会。
            cached = null
            val keepChannel = p != null && p.holdsLazyStatementPool
            closedReason = if (keepChannel) "句柄已关闭（通道留给惰性语句池）" else "映射已关闭"
            val f = file
            file = null
            if (!keepChannel) toClose = f
        }
        // 关通道放在锁外：close 只做状态切换，真正的 close 不持有句柄锁（避免与 Program 侧
        // 的惰性解码相互等待）。惰性产物这里 toClose 恒 null ⇒ 通道留给 Program。
        toClose?.close()
    }
}
