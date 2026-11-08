package com.eratw.port.core.host

import com.eratw.port.core.image.PngDecoder
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong

/**
 * 降级态 PNG 载荷的**堆外存放通道**（OOM-B 波，2026-11-08）。
 *
 * ## 它解决什么
 *
 * [GraphicsBuffer.demotePixelsToPng] 把画过的合成图压成 PNG 后挂在延迟闭包里 ——
 * 压缩载荷仍是 Java 堆大对象空间里的一份 `ByteArray`。`2c9954df` 起它已进池级账本
 * （[GraphicsBuffer.publishSurfaceLocked] 的 `retainedPayloadBytes`），但账本说真话
 * 不等于堆里没这份字节：长游玩下「每降级一张留一份」的累积压力还在（CONTRACT-OOM-A §5
 * 登记的下一波首项）。本通道把载荷写进临时文件再 `FileChannel.map` READ_ONLY ——
 * 字节搬出 Java 堆，晋升时从映射解码，旧代作废时释放映射并删文件。
 *
 * ## 形状照 [ImageDecoderHost] 的既有宿主通道
 *
 * core 只定义接口与 aux 槽名 [PNG_PAYLOAD_SLOT]；实现由宿主在装配期装入
 * （Android 侧 `GameHost.installGraphics`，目录 = `cacheDir` 子目录）。**未装时**
 * （纯 JVM 闸 / 语料对拍 / 桌面装置）`aux.peek` 为 null，降级行为与今天逐字相同
 * （PNG 留堆内闭包、照旧记账）—— 本波是减量优化，不是行为变更。
 *
 * ## 线程
 *
 * [spill] 与 [Payload.decode] 都只在缓冲实例监视器内被调（demote 的编码段 / pixels 的
 * 解码段全程持锁），实现方无需自锁；[Payload.close] 由换代口在锁内调、由
 * [MappedPngPayloadStore.close] 在宿主退出清场时调（两条路幂等）。
 */
interface PngPayloadStore {
    /**
     * 把 [png] 搬出 Java 堆，返回可解码句柄；**失败返回 null**（目录不可写 / 映射失败），
     * 调用方回落堆内闭包。实现方不得把异常抛给降级路径 —— 那一发的 OOM 捕住口径
     * 写在 [GraphicsBuffer.demotePixelsToPng] 里，本通道再抛一层会把「这张退不了」
     * 升级成「VM 线程炸穿」。
     */
    fun spill(png: ByteArray): Payload?

    /** 一份已出堆的载荷。 */
    interface Payload : AutoCloseable {
        /**
         * 从堆外载荷解回像素；解不出返回 null（调用方按 [GraphicsBuffer.pixels] 的
         * 失败支退化成透明图）。已 [close] 后调用同样返回 null，不读悬空内存。
         */
        fun decode(): IntArray?

        /**
         * 一次性读出载荷的 PNG 字节（瞬时拷贝，只在调用栈里活一瞬）；读不出 / 已 [close]
         * 返回 null。这是渲染侧只读取图口（[GraphicsBuffer.demotedPngBytes]）对堆外载荷的
         * 取材路：平台拿字节去 `BitmapFactory` 直解 native 位图，**不经** [decode] ——
         * 那会解回整幅 `IntArray`（1.44 MiB 级）落进调用栈，正是渲染取图要拆的那条晋升回路。
         * 缺省 null：非字节形态的实现不装支持，调用方按缺图支兜住。
         * 实现方每次调用都该是新拷贝（映射没有堆内正本可还）；调用方按凭据缓存解码结果、
         * 只在缓存 miss 时读一次，别每帧读。
         */
        fun readBytes(): ByteArray? = null

        /**
         * 释放句柄 + 删文件（幂等）。`MappedByteBuffer` 没有公开 unmap（ART 无 Cleaner
         * 反射入口，见 `MappedErbc` 类末 TODO(verify)）⇒ 丢弃引用后映射活到 GC；删文件是
         * 独立的 best-effort 一步 —— Linux 与 Windows（JDK 以 share-delete 打开映射）实测
         * 都能立即删掉，删不掉的（进程崩溃等）由宿主启动期的 `sweepStale` 清扫。
         */
        override fun close()
    }

    /**
     * 把「堆外载荷的 decode」包成 [GraphicsBuffer] 延迟源要的 `() -> IntArray?`，
     * 同时 implements AutoCloseable —— 换代口（publishSurfaceLocked）按 AutoCloseable
     * 收旧载荷。**lambda 做不到这件事**：Kotlin 函数类型不接额外接口，裸闭包捕获句柄
     * 时换代口认不出它，映射与文件就漏到宿主退出才收（本波第一版实测漏在晋升/dispose）。
     */
    class DecodeClosure(private val payload: Payload) : (() -> IntArray?), AutoCloseable {
        override fun invoke(): IntArray? = payload.decode()

        /** 渲染侧只读字节口；本对象本身即「降级代凭据」（见 [GraphicsBuffer.demotedCredential]）。 */
        fun readBytes(): ByteArray? = payload.readBytes()

        override fun close() = payload.close()
    }
}

/** [PngPayloadStore] 在 `store.aux` 里的槽名（每 VM 一份，随存档/重启作废）。 */
const val PNG_PAYLOAD_SLOT: String = "pngPayload"

/**
 * [PngPayloadStore] 的 mmap 实现：载荷写临时文件 → 只读映射 → 关通道。
 *
 * 文件生命周期：创建在 [dir]（宿主给的是 cacheDir 子目录，系统可随时清），名字
 * `payload-<序号>.png`；[Payload.close] best-effort 删除（Linux / Windows 实测立即可删），
 * 删不掉的（进程崩溃等）由 [sweepStale] 在下次装配时清扫 —— 与 `MappedErbc` 的
 * 「映射活到 GC」同一口径。
 *
 * 无静态可变状态：序号与存活集都挂实例（AGENTS §2 铁律 4）；实例由宿主每会话一份，
 * 随 `store.aux` 同生命周期。
 */
class MappedPngPayloadStore(private val dir: Path) : PngPayloadStore, AutoCloseable {

    private val seq = AtomicLong(0L)

    /** 存活载荷集（close 时逐个收尾）；只在缓冲监视器或本实例监视器内被摸。 */
    private val live = HashSet<MappedPayload>()

    @Volatile
    private var closed = false

    override fun spill(png: ByteArray): PngPayloadStore.Payload? {
        if (closed || png.isEmpty()) return null
        val path = dir.resolve("payload-${seq.incrementAndGet()}.png")
        return try {
            Files.createDirectories(dir)
            Files.write(path, png)
            FileChannel.open(path, StandardOpenOption.READ).use { ch ->
                val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0L, png.size.toLong())
                val payload = MappedPayload(path, mapped, png.size)
                synchronized(live) {
                    if (closed) {
                        payload.close()
                        return null
                    }
                    live.add(payload)
                }
                payload
            }
        } catch (_: IOException) {
            runCatching { Files.deleteIfExists(path) }
            null
        } catch (_: RuntimeException) {
            runCatching { Files.deleteIfExists(path) }
            null
        } catch (_: OutOfMemoryError) {
            // FileChannel.map 在直接内存贴顶时抛 OOM。接口契约要求不得抛给降级路径，
            // 否则「这张退不了」会升级成 VM 线程炸穿；回落堆内闭包即可。
            runCatching { Files.deleteIfExists(path) }
            null
        }
    }

    /**
     * 收掉本实例还活着的载荷（宿主退出清场调；幂等）。已走 [Payload.close] 的不在这里。
     * 不收 [dir] 本身 —— 目录是宿主的 cacheDir 子目录，下次装配还要用。
     */
    override fun close() {
        closed = true
        val remaining = synchronized(live) {
            val copy = live.toList()
            live.clear()
            copy
        }
        for (payload in remaining) runCatching { payload.close() }
    }

    /**
     * 清扫上次进程残留（进程崩溃 / 映射未及收尾）：只删本目录里名字符合
     * `payload-<数字>.png` 的文件，别的一个字节不动。装配期调一次，失败静默
     * （cacheDir 不可写时本通道整体回落堆内闭包，清扫失败不挡装配）。
     */
    fun sweepStale() {
        runCatching {
            Files.newDirectoryStream(dir, "payload-*.png").use { stream ->
                for (p in stream) {
                    val name = p.fileName.toString()
                    val num = name.removePrefix("payload-").removeSuffix(".png")
                    if (num.isNotEmpty() && num.all { it in '0'..'9' }) {
                        runCatching { Files.deleteIfExists(p) }
                    }
                }
            }
        }
    }

    private inner class MappedPayload(
        private val path: Path,
        mapping: MappedByteBuffer,
        private val size: Int,
    ) : PngPayloadStore.Payload {
        // 映射引用只此一份；close 置 null 后 decode 走「已关闭」支，不读悬空内存。
        @Volatile
        private var mapping: MappedByteBuffer? = mapping

        override fun decode(): IntArray? {
            val m = mapping ?: return null
            return try {
                // duplicate：位置游标各自独立，多次 decode / 并发读互不串位。
                val dup = m.duplicate()
                val bytes = ByteArray(size)
                dup.get(bytes)
                PngDecoder.decode(bytes).pixels
            } catch (_: Throwable) {
                null
            }
        }

        override fun readBytes(): ByteArray? {
            val m = mapping ?: return null
            return try {
                // 与 decode 同一条 duplicate 纪律；这份拷贝只在调用栈里活一瞬，
                // 渲染侧解完 native 位图就丢，不能拿它当缓存键（见接口 KDoc）。
                val dup = m.duplicate()
                ByteArray(size).also { dup.get(it) }
            } catch (_: Throwable) {
                null
            }
        }

        override fun close() {
            if (mapping == null) return
            mapping = null
            synchronized(live) { live.remove(this) }
            // 丢弃引用 ⇒ 映射活到 GC（无公开 unmap）；删文件不依赖 unmap 成功
            // （Linux / Windows 实测立即可删），删不掉的残留由 sweepStale 收。
            runCatching { Files.deleteIfExists(path) }
        }
    }

}