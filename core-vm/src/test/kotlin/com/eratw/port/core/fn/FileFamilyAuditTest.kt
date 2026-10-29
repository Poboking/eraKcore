package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.proc.RootedFileOps
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 文件族逐条对拍（权威 = `reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 * + `MinorShift.Emuera.Runtime.Utils.EvilMask/Utils.cs` + `MinorShift.Emuera.Runtime.Utils/EncodingHandler.cs`）。
 *
 * ## 对拍表（C# 行号 → 本仓实现 → 结论）
 *
 * | C# 位置 | 函数 / 口径 | 本仓实现 | 结论 |
 * |---|---|---|---|
 * | 1915-1923 ExistFileMethod | EXISTFILE | `HostMethods.kt:167-175` | **F3 已修**（`File.Exists`，目录 ⇒ 0） |
 * | 352-374 EnumFilesMethod | ENUMFILES | `HostMethods.kt:247-268` | **F4 已修**（`Directory.Exists`，普通文件 ⇒ -1）；F5 / F6 仍在 |
 * | 7958-8016 SaveTextMethod | SAVETEXT | `HostMethods.kt:192-217` | **F1 已修**（UTF-8 带 BOM） |
 * | 8041-8088 LoadTextMethod | LOADTEXT | `HostMethods.kt:219-243` + `RootedFileOps.readText` | **F2 已修**（BOM → UTF-16 → UTF-8 → CP932） |
 * | Utils.cs:473-488 GetValidPath | 路径合法化 | `HostMethods.kt:277-280` | 一致 |
 * | 7987-7991 / 8070-8074 Path.HasExtension | 扩展名白名单 | `HostMethods.kt:319-336` | 一致；**分歧 F7**（表项大小写） |
 * | 9466-9469 GetSaveDataPathText | 序号寻址 | `HostMethods.kt:344-345` | 一致 |
 * | EncodingHandler.cs:15-41 / Config.cs:19 | 读/写编码 | `core/proc/BootHosts.kt:44-175` | **F1 / F2 已修** |
 * | 3562-3600 FindFilesMethod | FIND_CHARADATA | `CharaFileMethods.kt:50` | 不属本族文件面（归 K-CHARANUM 面） |
 *
 * ## 已修的四条（本波翻到正确值，注释保留原现状）
 *
 * - F1 SAVETEXT 的字节：原版 `File.WriteAllText(path, text, Config.SaveEncode)`（:8010），
 *   `SaveEncode = EncodingHandler.UTF8BOMEncoding`（Config.cs:19）⇒ **UTF-8 带 BOM**；
 *   落字节分工 = 宿主只写 UTF-8、调用方在正文前放 U+FEFF（与 OUTPUTLOG 同一约定，
 *   `DebugMiscMethods.kt:275-276`）⇒ 磁盘字节是 `EF BB BF` + UTF-8 正文。
 * - F2 LOADTEXT 的解码：原版 `File.ReadAllText(path, EncodingHandler.DetectEncoding(path))`（:8082），
 *   探测顺序 = UTF-8 BOM →（StreamReader 的 BOM 嗅探）UTF-16/UTF-32 → 严格 UTF-8 → 回落 **CP932**
 *   （EncodingHandler.cs:23-40）；宿主 `RootedFileOps.readText` 同序，解不出 → null → LOADTEXT ""。
 * - F3 EXISTFILE 对目录：原版 `File.Exists`（:1918）对目录恒 false ⇒ 0；
 *   宿主用 `DirectoryProbeFileOps.isDirectory` 把目录扣掉（`Files.exists` 本身对目录 true）。
 * - F4 ENUMFILES 的目录判定：原版 `Directory.Exists` 不成立就 -1（:355）；
 *   宿主用 `DirectoryProbeFileOps.isDirectory` 判目录，普通文件 ⇒ -1。
 *
 * ## 仍开着的分歧
 *
 * - F5 ENUMFILES 的路径形状：原版拷 `Directory.EnumerateFiles` 的**含目录前缀**路径（:364-372），
 *   宿主给「相对 root + '/'」（FileOps.kt:40-42 已登记 M6-B HOST-AUDIT，本路只引用）。
 * - F6 ENUMFILES 第 4 参不是变量：原版 `(arguments[3] as VariableTerm)` 空引用崩溃（:370），
 *   本仓抛 CodeEE —— ADR-005 偏离登记（`specs/verify-notes.md`）。
 * - F7 扩展名白名单的比较口径：原版只把**扩展名**小写化再 `Contains` 原文表项（:7987 / :8070），
 *   本仓把表项也小写化（HostMethods.kt:315）。语料 `emuera.config:73` 全小写 ⇒ 无观测差；
 *   表里有大写项时才分叉。
 */
private class FaBase : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("FaBase 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/** 内存 FileOps：目录用 [dirs] 显式声明（与 FakeFileOps 同形，但文件内容可控字节由 [raw] 承载）。 */
private class FaOps : DirectoryProbeFileOps {
    val files = LinkedHashMap<String, String>()
    val dirs = mutableSetOf<String>()
    override fun exists(path: String): Boolean = files.containsKey(path) || path in dirs
    override fun isDirectory(path: String): Boolean = path in dirs
    override fun readText(path: String): String? = files[path]
    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        files[path] = if (append) (files[path] ?: "") + text else text
        return true
    }

    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> {
        if (dir !in dirs) return emptyList()
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val regex = Regex("^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$")
        return files.keys.filter { it.startsWith(prefix) }
            .filter { recursive || !it.substring(prefix.length).contains("/") }
            .filter { regex.matches(it.substring(prefix.length).substringAfterLast('/')) }
            .sorted()
    }
}

private fun faCtx(ops: FileOps?, overrides: Map<String, String> = emptyMap()): BuiltinEvalContext {
    val store = VariableStore()
    return BuiltinEvalContext(TestEvalContext(store), store, object : BuiltinHost {
        override val fileOps: FileOps? = ops
        override val configOverrides: Map<String, String> get() = overrides
    })
}

private fun faI(ctx: BuiltinEvalContext, name: String, vararg a: Any): Long =
    (ctx.callFunction(name, a.map { faV(it) }) as Value.IntV).v

private fun faS(ctx: BuiltinEvalContext, name: String, vararg a: Any): String =
    (ctx.callFunction(name, a.map { faV(it) }) as Value.StrV).v

private fun faV(x: Any): Value = if (x is String) Value.StrV(x) else Value.IntV((x as Number).toLong())

/**
 * ENUMFILES 的引用路径（`BuiltinMethod` 只声明了 `evalRef`，见 HostMethods.kt:228 ⇒ 值路径必抛）。
 * 参数按表达式形态给：[StrLiteral] / [IntLiteral] / null（省略位）。
 */
private fun faIRef(ctx: BuiltinEvalContext, name: String, vararg a: com.eratw.port.core.expr.Expr?): Long =
    (ctx.callFunctionRef(name, a.toList()) as Value.IntV).v

class FileFamilyAuditTest {

    // ---------------------------------------------------------------
    // GetValidPath —— Utils.cs:473-488
    // ---------------------------------------------------------------

    @Test
    fun `路径合法化与 GetValidPath 同口径`() {
        val ops = FaOps()
        ops.files["x.txt"] = "X"
        val ctx = faCtx(ops)
        assertEquals(1L, faI(ctx, "EXISTFILE", "x.txt"))
        assertEquals(1L, faI(ctx, "EXISTFILE", "..\\x.txt"))      // "..\" 被剔除 ⇒ x.txt
        assertEquals(0L, faI(ctx, "EXISTFILE", "/x.txt"))         // '/'→'\' 后是根路径 ⇒ null
        assertEquals(0L, faI(ctx, "EXISTFILE", "C:/x.txt"))       // 盘符根 ⇒ null
        assertEquals(0L, faI(ctx, "EXISTFILE", "\\\\srv\\s\\x.txt")) // UNC 根 ⇒ null
        assertEquals(0L, faI(ctx, "EXISTFILE", ""))               // 空串不是文件
    }

    // ---------------------------------------------------------------
    // Path.HasExtension 口径 —— :7987 / :8070
    // ---------------------------------------------------------------

    @Test
    fun `扩展名白名单 无扩展名与末尾点与多点`() {
        val ops = FaOps()
        val ctx = faCtx(ops)
        ops.files["a.b.c"] = "ABC"
        ops.files["a."] = "DOT"
        ops.files["noext"] = "N"
        // "a.b.c"：扩展名 "c" 不在白名单 ⇒ LOADTEXT ""、SAVETEXT 换成 a.b.txt（:7990）
        assertEquals("", faS(ctx, "LOADTEXT", "a.b.c"))
        assertEquals(1L, faI(ctx, "SAVETEXT", "V", "a.b.c"))
        assertTrue("a.b.txt" in ops.files, "多点文件名只换最后一段：${ops.files.keys}")
        // "a."：.NET Path.HasExtension 为 false ⇒ 走无扩展名分支（LOADTEXT ""、SAVETEXT 补 .txt）
        assertEquals("", faS(ctx, "LOADTEXT", "a."))
        assertEquals(1L, faI(ctx, "SAVETEXT", "V", "a."))
        assertTrue("a.txt" in ops.files, "末尾点应补成 .txt：${ops.files.keys}")
        // 无扩展名同样两边一致
        assertEquals("", faS(ctx, "LOADTEXT", "noext"))
        assertEquals(1L, faI(ctx, "SAVETEXT", "V", "noext"))
        assertTrue("noext.txt" in ops.files)
    }

    @Test
    fun `扩展名白名单表项大小写不参与折叠 分歧 F7`() {
        // 分歧 F7：原版 `Config.ValidExtension.Contains(小写化的扩展名)`（:8072），表项保持原样；
        // 本仓把表项也 lowercase（HostMethods.kt:315）⇒ 表里写 "TXT" 时原版拒、本仓收。
        val ops = FaOps()
        ops.files["upper.txt"] = "UP"
        val ctx = faCtx(
            ops,
            mapOf("VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT" to "TXT"),
        )
        assertEquals("UP", faS(ctx, "LOADTEXT", "upper.txt"))   // 现状读得到；.NET ""
    }

    // ---------------------------------------------------------------
    // LOADTEXT —— :8041-8088
    // ---------------------------------------------------------------

    @Test
    fun `LOADTEXT 剥一个前导 BOM 再去掉全部 CR`() {
        val ops = FaOps()
        ops.files["bom.txt"] = "\uFEFFA\r\nB"
        ops.files["bom2.txt"] = "\uFEFF\uFEFFA"
        ops.files["cr.txt"] = "A\rB\r\r\nC"
        val ctx = faCtx(ops)
        // StreamReader 只消费开头一个编码前导符（:8082）⇒ 一个 BOM 消失、第二个留在正文
        assertEquals("A\nB", faS(ctx, "LOADTEXT", "bom.txt"))
        assertEquals("\uFEFFA", faS(ctx, "LOADTEXT", "bom2.txt"))
        // :8088 `text.Replace("\r","")`：正文中间的孤立 CR 也删
        assertEquals("AB\nC", faS(ctx, "LOADTEXT", "cr.txt"))
    }

    @Test
    fun `LOADTEXT 与 SAVETEXT 的序号寻址路径`() {
        val ops = FaOps()
        val ctx = faCtx(ops)
        // GetSaveDataPathText（:9466-9469）= "{dir}txt{index:00}.txt"
        assertEquals(1L, faI(ctx, "SAVETEXT", "Z", 5))
        assertTrue("txt05.txt" in ops.files, "${ops.files.keys}")
        assertEquals(1L, faI(ctx, "SAVETEXT", "H", 100))
        assertTrue("txt100.txt" in ops.files, "${ops.files.keys}")
        // force≠0 ⇒ sav/（默认 UseSaveFolder=false，见 HostMethods.kt:150-161）
        assertEquals(1L, faI(ctx, "SAVETEXT", "F", 2, 1))
        assertTrue("sav/txt02.txt" in ops.files, "${ops.files.keys}")
        assertEquals("Z", faS(ctx, "LOADTEXT", 5))
        assertEquals("F", faS(ctx, "LOADTEXT", 2, 1))
        // 负数/超 Int.MAX 一律失败但不抛（:7973-7976 / :8056-8059）
        assertEquals(0L, faI(ctx, "SAVETEXT", "X", -1))
        assertEquals("", faS(ctx, "LOADTEXT", -1))
        assertEquals(0L, faI(ctx, "SAVETEXT", "X", Int.MAX_VALUE.toLong() + 1))
    }

    // ---------------------------------------------------------------
    // ENUMFILES —— :352-374
    // ---------------------------------------------------------------

    @Test
    fun `ENUMFILES 的 pattern 递归与目标数组`() {
        val ops = FaOps()
        ops.dirs += "dat"
        ops.dirs += "dat/sub"
        ops.files["dat/a.xml"] = "1"
        ops.files["dat/sub/b.xml"] = "2"
        ops.files["dat/c.txt"] = "3"
        val ctx = faCtx(ops)
        // 非递归：只直接子项（:360 SearchOption.TopDirectoryOnly）
        assertEquals(1L, faIRef(ctx, "ENUMFILES", StrLiteral("dat"), StrLiteral("*.xml")))
        val store = ctx.store
        assertEquals("dat/a.xml", store.readStr("RESULTS", longArrayOf(0)))
        // 递归：含子目录（:360 AllDirectories）
        assertEquals(
            2L,
            faIRef(ctx, "ENUMFILES", StrLiteral("dat"), StrLiteral("*.xml"), IntLiteral(1)),
        )
        // 目录不存在 → -1（:355-357）
        assertEquals(-1L, faIRef(ctx, "ENUMFILES", StrLiteral("nope"), StrLiteral("*.xml")))
    }

    @Test
    fun `ENUMFILES 第四参不是变量时抛 CodeEE 分歧 F6`() {
        // 分歧 F6：原版 `(arguments[3] as VariableTerm)` 在非变量上给出 null 引用、
        // 随后 `.Identifier` 直接崩（:370）；本仓抛可诊断的 CodeEE。
        val ops = FaOps()
        ops.dirs += "d"
        ops.files["d/a.txt"] = "1"
        val ctx = faCtx(ops)
        // 三参（省略第 4 位）仍走 RESULTS（:370 `arguments.Count != 4`）
        assertEquals(
            1L,
            faIRef(ctx, "ENUMFILES", StrLiteral("d"), StrLiteral("*.txt"), IntLiteral(0)),
        )
        // 第 4 参给了非变量值 ⇒ 原版空引用崩溃，本仓 CodeEE
        assertFailsWith<CodeEE> {
            faIRef(
                ctx, "ENUMFILES", StrLiteral("d"), StrLiteral("*.txt"), IntLiteral(0), StrLiteral("RESULTS"),
            )
        }
        // 值路径本就不该被调用：注册只给了 evalRef（HostMethods.kt:228）
        assertFailsWith<CodeEE> { faI(ctx, "ENUMFILES", "d") }
    }

    // ---------------------------------------------------------------
    // 宿主层读数 —— BootHosts.kt 的 RootedFileOps
    // ---------------------------------------------------------------

    @Test
    fun `SAVETEXT 落盘字节带 UTF-8 BOM 分歧 F1 已修`(@TempDir tmp: Path) {
        val ops = RootedFileOps(tmp)
        val ctx = faCtx(ops)
        assertEquals(1L, faI(ctx, "SAVETEXT", "ABC", "out.txt"))
        val bytes = Files.readAllBytes(tmp.resolve("out.txt"))
        // 原版 `File.WriteAllText(text, strValue, Config.SaveEncode)`（:8010），
        // `SaveEncode = EncodingHandler.UTF8BOMEncoding`（Config.cs:19）⇒ EF BB BF + 正文。
        // 落字节分工见 `HostMethods.kt` 的 SAVETEXT 就地注释（宿主只写 UTF-8，BOM 由调用方放 U+FEFF）。
        assertEquals(6, bytes.size, "原版 6 字节（EF BB BF + ABC）")
        assertEquals(0xEF, bytes[0].toInt() and 0xFF, "BOM 第 1 字节")
        assertEquals(0xBB, bytes[1].toInt() and 0xFF, "BOM 第 2 字节")
        assertEquals(0xBF, bytes[2].toInt() and 0xFF, "BOM 第 3 字节")
        assertEquals("ABC", String(bytes, 3, 3, java.nio.charset.StandardCharsets.UTF_8))
        // 写出去再读回来：前导 BOM 由解码步消费，不进正文（:8082）
        assertEquals("ABC", faS(ctx, "LOADTEXT", "out.txt"))
        // 覆盖写不累积 BOM（每次都重写前导符，不是 append）
        assertEquals(1L, faI(ctx, "SAVETEXT", "Z", "out.txt"))
        assertEquals(4, Files.readAllBytes(tmp.resolve("out.txt")).size)
    }

    @Test
    fun `EXISTFILE 对目录返回 0 分歧 F3 已修`(@TempDir tmp: Path) {
        val ops = RootedFileOps(tmp)
        Files.createDirectory(tmp.resolve("sav"))
        Files.write(tmp.resolve("sav.txt"), byteArrayOf('x'.code.toByte()))
        val ctx = faCtx(ops)
        // 原版 File.Exists(目录) = false（:1918）⇒ 0；同名文件仍为 1
        assertEquals(0L, faI(ctx, "EXISTFILE", "sav"))
        assertEquals(1L, faI(ctx, "EXISTFILE", "sav.txt"))
    }

    @Test
    fun `LOADTEXT 按 BOM 到 UTF8 到 CP932 三段回退 分歧 F2 已修`(@TempDir tmp: Path) {
        // CP932 "あ" = 0x82 0xA0；0x82 在 UTF-8 里不是合法首字节 ⇒ 原版回落 shiftjisEncoding
        // （EncodingHandler.cs:11 + :37-40）⇒ "あ"
        Files.write(tmp.resolve("sjis.txt"), byteArrayOf(0x82.toByte(), 0xA0.toByte()))
        // UTF-8 无 BOM（严格 UTF-8 通过 ⇒ UTF8Encoding，:33-35）
        Files.write(tmp.resolve("u8.txt"), "中文ABC".toByteArray(Charsets.UTF_8))
        // UTF-8 带 BOM（:23-26 ⇒ UTF8BOMEncoding，前导符不落正文）
        Files.write(
            tmp.resolve("u8bom.txt"),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "中文".toByteArray(Charsets.UTF_8),
        )
        // UTF-16LE 带 BOM（StreamReader 嗅探 CurrentEncoding ≠ UTF8Encoding ⇒ :29-32）
        Files.write(
            tmp.resolve("u16.txt"),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "中文A".toByteArray(Charsets.UTF_16LE),
        )
        // UTF-16BE 带 BOM
        Files.write(
            tmp.resolve("u16be.txt"),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "中".toByteArray(Charsets.UTF_16BE),
        )
        val ctx = faCtx(ops = RootedFileOps(tmp))
        assertEquals("あ", faS(ctx, "LOADTEXT", "sjis.txt"))
        assertEquals("中文ABC", faS(ctx, "LOADTEXT", "u8.txt"))
        assertEquals("中文", faS(ctx, "LOADTEXT", "u8bom.txt"))
        assertEquals("中文A", faS(ctx, "LOADTEXT", "u16.txt"))
        assertEquals("中", faS(ctx, "LOADTEXT", "u16be.txt"))
        // 既非 UTF-8 也非 CP932 的字节序列：原版 ReadAllText 抛 ⇒ LoadTextMethod 的 catch 给 ""
        // （:8084-8087）；本宿主 readText 返回 null ⇒ LOADTEXT ""
        Files.write(tmp.resolve("bad.txt"), byteArrayOf(0x81.toByte(), 0x20.toByte()))
        assertEquals("", faS(ctx, "LOADTEXT", "bad.txt"))
    }

    @Test
    fun `ENUMFILES 把普通文件当目录返回 -1 分歧 F4 已修`(@TempDir tmp: Path) {
        Files.write(tmp.resolve("plain.txt"), "x".toByteArray())
        Files.createDirectory(tmp.resolve("realdir"))
        Files.write(tmp.resolve("realdir/a.txt"), "a".toByteArray())
        Files.createDirectory(tmp.resolve("emptydir"))
        val ops = RootedFileOps(tmp)
        val ctx = faCtx(ops)
        assertEquals(1L, faIRef(ctx, "ENUMFILES", StrLiteral("realdir"), StrLiteral("*.txt")))
        // 原版 Directory.Exists("plain.txt") = false ⇒ -1（:355）
        assertEquals(-1L, faIRef(ctx, "ENUMFILES", StrLiteral("plain.txt"), StrLiteral("*.txt")))
        // 空目录仍是目录（Files.isDirectory 直判，不靠能否枚举出条目）
        assertEquals(0L, faIRef(ctx, "ENUMFILES", StrLiteral("emptydir"), StrLiteral("*.txt")))
        assertEquals(-1L, faIRef(ctx, "ENUMFILES", StrLiteral("nodir"), StrLiteral("*.txt")))
    }

    @Test
    fun `ENUMFILES 返回相对根且斜杠分隔的路径 分歧 F5`(@TempDir tmp: Path) {
        Files.createDirectory(tmp.resolve("d"))
        Files.write(tmp.resolve("d/a.txt"), "a".toByteArray())
        val ops = RootedFileOps(tmp)
        val ctx = faCtx(ops)
        assertEquals(1L, faIRef(ctx, "ENUMFILES", StrLiteral("d"), StrLiteral("*.txt")))
        // 分歧 F5：原版给 "…\\d\\a.txt" 这种含目录前缀的路径（:364-372）；
        // 宿主给相对 root 的 "d/a.txt"（FileOps.kt:40-42，已登记 M6-B HOST-AUDIT）
        assertEquals("d/a.txt", ctx.store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun `只读装饰宿主下的 F3 F4 与空目录残留`(@TempDir tmp: Path) {
        // 真实开局路径（GameSession.kt:251）= ConsoleBuiltinHost，其 fileOps 是
        // WriteBlockedFileOps(RootedFileOps) 的**只读装饰**。修复 R5 漏转发后，
        // WriteBlockedFileOps 已实现 DirectoryProbeFileOps 并转发给内层 RootedFileOps
        // ⇒ 空目录与普通文件可精确区分（不再走「能否枚举出条目」兜底）。
        Files.createDirectory(tmp.resolve("sav"))
        Files.write(tmp.resolve("sav/txt01.txt"), "a".toByteArray())
        Files.write(tmp.resolve("plain.txt"), "x".toByteArray())
        Files.createDirectory(tmp.resolve("emptydir"))
        val store = VariableStore()
        val ctx = BuiltinEvalContext(
            TestEvalContext(store), store, com.eratw.port.core.proc.ConsoleBuiltinHost(tmp),
        )
        // F3：非空目录 → 0；普通文件 → 1
        assertEquals(0L, faI(ctx, "EXISTFILE", "sav"))
        assertEquals(1L, faI(ctx, "EXISTFILE", "plain.txt"))
        // F4：普通文件 → -1；非空目录 → 枚举个数
        assertEquals(-1L, faIRef(ctx, "ENUMFILES", StrLiteral("plain.txt"), StrLiteral("*.txt")))
        assertEquals(1L, faIRef(ctx, "ENUMFILES", StrLiteral("sav"), StrLiteral("*.txt")))
        // 修复后经生产装饰器的口径与直连 RootedFileOps 一致：空目录 F3=0（File.Exists=false）、
        // F4=0（Directory.Exists=true 且枚举 0 条）—— 见 FunctionMethodCreator.cs:1917-1922 / :354-358。
        assertEquals(0L, faI(ctx, "EXISTFILE", "emptydir"))
        assertEquals(0L, faIRef(ctx, "ENUMFILES", StrLiteral("emptydir"), StrLiteral("*.txt")))
        // 直接接 RootedFileOps（能力位在）时同一路径是精确口径
        val direct = faCtx(RootedFileOps(tmp))
        assertEquals(0L, faI(direct, "EXISTFILE", "emptydir"))
        assertEquals(0L, faIRef(direct, "ENUMFILES", StrLiteral("emptydir"), StrLiteral("*.txt")))
    }

    @Test
    fun `宿主未接线时文件族抛 CodeEE`() {
        val ctx = faCtx(null)
        assertFailsWith<CodeEE> { faI(ctx, "EXISTFILE", "x.txt") }
        assertFailsWith<CodeEE> { faS(ctx, "LOADTEXT", "x.txt") }
        assertFailsWith<CodeEE> { faI(ctx, "SAVETEXT", "t", "x.txt") }
    }
}
