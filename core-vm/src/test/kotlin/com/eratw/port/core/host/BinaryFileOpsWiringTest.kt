package com.eratw.port.core.host

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.image.PngDecoder
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.RootedFileOps
import com.eratw.port.core.proc.WriteBlockedFileOps
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M11-E hostwire —— [BinaryFileOps] 宿主接线回归（CONTRACT-M11-E §5「hostwire」判据①②③）。
 *
 * C# 锚点（本机 reference 逐行核对，2026-10-24）：
 * GSAVE = `GraphicsSaveMethod.GetIntValue`（`FunctionMethodCreator.cs:8105-8132`）：
 * - :8121 路径 = `GetSaveDataPathGraphics(index)` ⇒ `{Config.SavDir}img{index:0000}.png`（:9471-9474）；
 * - :8124 `Config.CreateSavDir()`（实现 `Config.cs:543-548`：仅 `UseSaveFolder` 时
 *   `Directory.CreateDirectory(SavDir)`）⇒ 落盘前**父目录由写侧创建**；移植侧
 *   `writeBytes` 无条件建父目录（`createDirectories` 对已存在目录幂等，与 false 分支
 *   「SavDir = ExeDir、目录本就在」同结局；`savePath` 恒带 `sav/` 前缀是 fn 层既有口径，
 *   不在本路范围）；
 * - :8125 `graphicsImage.Bitmap.Save(path)` ⇒ 覆盖写、格式由 `.png` 扩展名定（真 PNG）；
 * - :8122-8130 整个 Save 包在 try 里，任何异常被 catch 折成 0 ⇒ **宿主侧写失败 = false，
 *   函数层折 0，不抛 CodeEE**。
 *
 * 三段覆盖：
 * 1. [RootedFileOps.writeBytes]：根内落盘、建父目录、覆盖语义、出根拒绝、IO 失败收 false；
 * 2. [WriteBlockedFileOps]：二进制与文本**同一道写闸**（同一个 `blockedWrites`，不另开旁路），
 *    writable 二态；
 * 3. GSAVE 经**生产宿主**（[RootedBuiltinHost] / [ConsoleBuiltinHost] + writeRoot）写真 PNG，
 *    磁盘读回的字节与 `GraphicsBuffer.toSavePng()` 相等（判据③）。
 *
 * 既有 `fn/GsavePngTest`（7 例，纯内存假宿主）不动：本文件只钉生产宿主形态，
 * 载体编码细节（签名/IHDR/像素序）由那边负责，这里只做**整字节相等**对拍。
 */
private class BinBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("BinBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/** 函数调用装置：真 [BuiltinHost] 挂进 [BuiltinEvalContext]，走 Builtins 注册表。 */
private class BinCtx(val host: BuiltinHost) {
    val store = VariableStore()
    private val base = BinBaseCtx().also { it.variables = store }
    private val eval = BuiltinEvalContext(base, store, host)

    fun i(name: String, vararg args: Any): Long =
        (eval.callFunction(
            name,
            args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
        ) as Value.IntV).v

    /** GSAVE 写入的像素池（与 fn 层 GraphicsMethods 的 aux 槽同名同实例）。 */
    fun gfx(): GraphicsPool = store.aux.slot("gfx") { GraphicsPool() }
}

/** 只有文本通道的 FileOps（模拟「writable 未实现 BinaryFileOps」的宿主形态）。 */
private class NoBinOps : FileOps {
    val texts = LinkedHashMap<String, String>()
    override fun exists(path: String): Boolean = texts.containsKey(path)
    override fun readText(path: String): String? = texts[path]
    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        texts[path] = if (append) (texts[path] ?: "") + text else text
        return true
    }
    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

/** PNG 大端 32 位读（校验 IHDR 宽高用；与 GsavePngTest 同式，文件私有不冲突）。 */
private fun intAt(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 24) or
        ((bytes[at + 1].toInt() and 0xFF) shl 16) or
        ((bytes[at + 2].toInt() and 0xFF) shl 8) or
        (bytes[at + 3].toInt() and 0xFF)

class BinaryFileOpsWiringTest {

    private fun withTempRoot(prefix: String, body: (Path) -> Unit) {
        val root = Files.createTempDirectory(prefix)
        try {
            body(root)
        } finally {
            runCatching {
                Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    // ===== 1. RootedFileOps.writeBytes（判据①）=====

    @Test
    fun `writeBytes 根内落盘并读回同一字节串`() = withTempRoot("eratw-bin-rooted") { root ->
        val ops = RootedFileOps(root)
        // 含 0x00 / 负字节 / 高位字节：文本通道会损坏的形态（接口 KDoc 的立项理由）
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x00, -1, 0x7F.toByte(), -128, 0x4E, 0x47)
        assertTrue(ops.writeBytes("out.bin", bytes), "根内写必须成功（true）")
        assertTrue(bytes.contentEquals(Files.readAllBytes(root.resolve("out.bin"))), "落盘字节必须原样")
    }

    /**
     * 建父目录 = 原版 GSAVE 落盘前的 `Config.CreateSavDir()`
     * （FunctionMethodCreator.cs:8124；实现 Config.cs:543-548）。
     * GSAVE 的 `sav/img0005.png` 正依赖这一步把 `sav/` 铺出来。
     */
    @Test
    fun `writeBytes 创建缺失父目录`() = withTempRoot("eratw-bin-parent") { root ->
        val ops = RootedFileOps(root)
        assertTrue(ops.writeBytes("sav/img0005.png", byteArrayOf(1, 2, 3)))
        assertTrue(Files.isDirectory(root.resolve("sav")), "sav 层须由写侧创建")
        // 多层缺失同样创建（`Directory.CreateDirectory` 本就整链创建，Config.cs:547 同形；
        // 比原版只铺 SavDir 更强的路径形状由移植侧的 resolvePath 允许，行为一致）
        assertTrue(ops.writeBytes("a/b/c.bin", byteArrayOf(4)))
        assertTrue(Files.isRegularFile(root.resolve("a/b/c.bin")))
    }

    /**
     * 覆盖写 = 原版 `Bitmap.Save` 对同名文件的行为（FunctionMethodCreator.cs:8125，
     * `Files.write` 默认 CREATE+TRUNCATE+WRITE，无 APPEND 通道）。
     */
    @Test
    fun `writeBytes 是覆盖语义而非追加`() = withTempRoot("eratw-bin-overwrite") { root ->
        val ops = RootedFileOps(root)
        assertTrue(ops.writeBytes("x.bin", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertTrue(ops.writeBytes("x.bin", byteArrayOf(-1, -2, -3, -4)))
        val back = Files.readAllBytes(root.resolve("x.bin"))
        assertEquals(4, back.size, "第二次写必须截断旧内容")
        assertTrue(byteArrayOf(-1, -2, -3, -4).contentEquals(back))
    }

    /** 出根一律 false 且不逃逸：与读路径 / [RootedFileOps.writeText] 同一条 `resolvePath` 边界。 */
    @Test
    fun `writeBytes 拒绝出根路径`() = withTempRoot("eratw-bin-escape") { root ->
        val outside = Files.createTempDirectory("eratw-bin-outside")
        try {
            val ops = RootedFileOps(root)
            assertFalse(ops.writeBytes("../escape.bin", byteArrayOf(1)), "上跳出根必须 false")
            assertFalse(ops.writeBytes("sub/../../escape.bin", byteArrayOf(1)), "多级上跳同样 false")
            assertFalse(ops.writeBytes(outside.resolve("abs.bin").toString(), byteArrayOf(1)), "绝对根外必须 false")
            assertFalse(Files.exists(outside.resolve("escape.bin")), "根外不得出现文件")
            assertFalse(Files.exists(outside.resolve("abs.bin")), "根外不得出现文件")
            assertFalse(Files.exists(root.resolve("sub")), "被拒路径不得创建父目录")
        } finally {
            runCatching {
                Files.walk(outside).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /**
     * IO 失败收成 false、不把异常抛回函数层 —— 对应原版把 `Bitmap.Save` 的**任何**异常
     * 折成返回 0 的那只手（FunctionMethodCreator.cs:8122-8130）。
     * 失败样本：目标名已被同名**目录**占用（`Files.write` 必抛）。
     */
    @Test
    fun `writeBytes 的 IO 失败折成 false 不抛异常`() = withTempRoot("eratw-bin-iofail") { root ->
        val ops = RootedFileOps(root)
        Files.createDirectories(root.resolve("blocked.bin"))
        assertFalse(ops.writeBytes("blocked.bin", byteArrayOf(1, 2, 3)), "写进目录必须折成 false")
    }

    // ===== 2. WriteBlockedFileOps 同一道闸（判据②）=====

    /**
     * 闸关（writable = null）：二进制写 false 且**与文本共用同一个 `blockedWrites`**
     * ——「不另开旁路」的钉法：两条通道各写一次，计数器读到 2。
     */
    @Test
    fun `写闸关时二进制与文本共用同一计数器`() = withTempRoot("eratw-bin-blocked") { root ->
        val ops = WriteBlockedFileOps(RootedFileOps(root), writable = null)
        assertEquals(0, ops.blockedWrites)
        assertFalse(ops.writeBytes("out.bin", byteArrayOf(1, 2)), "闸关必须 false（GSAVE 由此折 0）")
        assertEquals(1, ops.blockedWrites, "二进制拒写同样计数")
        assertFalse(ops.writeText("out.txt", "X", append = false))
        assertEquals(2, ops.blockedWrites, "两条通道打进同一个计数器 = 同一道闸")
        assertFalse(Files.exists(root.resolve("out.bin")), "闸关不落盘")
        assertFalse(Files.exists(root.resolve("out.txt")), "闸关不落盘")
    }

    /** 闸开：转发到放行根（生产形态 [RootedFileOps]），真落盘且父目录随建随写。 */
    @Test
    fun `写闸开时二进制转发到放行根`() = withTempRoot("eratw-bin-forward") { root ->
        val wroot = Files.createTempDirectory("eratw-bin-writable")
        try {
            val ops = WriteBlockedFileOps(RootedFileOps(root), RootedFileOps(wroot))
            val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, -1)
            assertTrue(ops.writeBytes("sav/img0006.png", bytes), "放行态转发必须 true")
            assertTrue(bytes.contentEquals(Files.readAllBytes(wroot.resolve("sav/img0006.png"))))
            assertEquals(0, ops.blockedWrites, "放行态不计数（既有口径）")
            assertFalse(Files.exists(root.resolve("sav")), "写只落放行根，读根保持干净")
        } finally {
            runCatching {
                Files.walk(wroot).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /**
     * 放行目标未实现 [BinaryFileOps]：按「宿主未接二进制写」false，**不重复计**
     * blockedWrites（闸是开的，缺的是通道不是权限）——WriteBlockedFileOps KDoc 登记的形态。
     */
    @Test
    fun `放行目标无二进制通道时按未接线折 false`() = withTempRoot("eratw-bin-nobin") { root ->
        val target = NoBinOps()
        val ops = WriteBlockedFileOps(RootedFileOps(root), writable = target)
        assertFalse(ops.writeBytes("x.bin", byteArrayOf(1)))
        assertEquals(0, ops.blockedWrites, "闸开而无通道 ≠ 闸拒写")
        // 文本通道不受影响，仍可转发
        assertTrue(ops.writeText("y.txt", "OK", append = false))
        assertEquals("OK", target.texts["y.txt"])
    }

    // ===== 3. GSAVE 经生产宿主写真 PNG（判据③）=====

    /**
     * `RootedBuiltinHost`（fileOps 直挂 [RootedFileOps]，天然实现 [BinaryFileOps]）：
     * GSAVE 返回 1，`{root}/sav/img0005.png` 的磁盘字节与 `toSavePng()` **逐字节相等**，
     * 且可被 [PngDecoder] 解回同一份像素（真 PNG，非文本载体）。
     */
    @Test
    fun `GSAVE 经 RootedBuiltinHost 写真 PNG 字节等于 toSavePng`() = withTempRoot("eratw-bin-gsave-rooted") { root ->
        val ctx = BinCtx(RootedBuiltinHost(root))
        assertEquals(1L, ctx.i("GCREATE", 10, 4, 3))
        assertEquals(1L, ctx.i("GCLEAR", 10, 0xFF112233))
        assertEquals(1L, ctx.i("GSETCOLOR", 10, 0xFF00FF00, 1, 2))
        assertEquals(1L, ctx.i("GSETCOLOR", 10, 0x80ABCDEF, 3, 0))
        // :8121 路径口径 sav/img0005.png（{index:0000}，:9473）
        assertEquals(1L, ctx.i("GSAVE", 10, 5))
        val onDisk = Files.readAllBytes(root.resolve("sav/img0005.png"))
        val expected = ctx.gfx().get(10).toSavePng()
        assertTrue(expected.contentEquals(onDisk), "磁盘字节必须等于 toSavePng()（判据③）")
        // 载体是 PNG 而非文本回落：签名 + IHDR 宽高（GsavePngTest 已细验编码，这里只认形态）
        assertEquals(0x89.toByte(), onDisk[0])
        assertEquals("PNG", String(onDisk, 1, 3, Charsets.US_ASCII))
        assertEquals(4, intAt(onDisk, 16))
        assertEquals(3, intAt(onDisk, 20))
        val decoded = PngDecoder.decode(onDisk)
        assertTrue(ctx.gfx().get(10).pixels.contentEquals(decoded.pixels), "PngDecoder 应解回同一份像素")
    }

    /**
     * 生产装配形态 `ConsoleBuiltinHost(writeRoot = …)`：GSAVE 的 `ops as? BinaryFileOps`
     * 命中的是写闸装饰本身（接线后它实现 [BinaryFileOps]），转发到放行根真落盘。
     */
    @Test
    fun `GSAVE 经 ConsoleBuiltinHost writeRoot 写真 PNG`() = withTempRoot("eratw-bin-gsave-console") { root ->
        val wroot = Files.createTempDirectory("eratw-bin-gsave-writable")
        try {
            val host = ConsoleBuiltinHost(root, writeRoot = wroot)
            val ctx = BinCtx(host)
            assertEquals(1L, ctx.i("GCREATE", 10, 3, 2))
            assertEquals(1L, ctx.i("GCLEAR", 10, 0xFF010203))
            assertEquals(1L, ctx.i("GSAVE", 10, 6))
            val onDisk = Files.readAllBytes(wroot.resolve("sav/img0006.png"))
            assertTrue(ctx.gfx().get(10).toSavePng().contentEquals(onDisk), "放行根上的字节须等于 toSavePng()")
            assertEquals(0, host.writes.blockedWrites, "放行态下 GSAVE 不该被闸拒")
        } finally {
            runCatching {
                Files.walk(wroot).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /**
     * 缺省拒写宿主（无 writeRoot）：GSAVE 返回 0（原版 :8122-8130 的失败折 0），
     * 读根**零落盘**（二进制被闸拒后不再回落文本载体——函数层已选中二进制分支，
     * 落盘面恒空与闸口径一致），且 blockedWrites 恰 +1。
     */
    @Test
    fun `GSAVE 在拒写宿主返回 0 零落盘并计数`() = withTempRoot("eratw-bin-gsave-blocked") { root ->
        val host = ConsoleBuiltinHost(root)
        val ctx = BinCtx(host)
        assertEquals(1L, ctx.i("GCREATE", 10, 2, 2))
        assertEquals(0L, ctx.i("GSAVE", 10, 0), "闸关写失败 ⇒ 0（:8122-8130 同口径）")
        assertEquals(1, host.writes.blockedWrites)
        assertFalse(Files.exists(root.resolve("sav")), "拒写宿主不得创建 sav 目录")
    }
}
