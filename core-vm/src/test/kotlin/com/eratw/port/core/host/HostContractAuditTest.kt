package com.eratw.port.core.host

import com.eratw.port.core.config.ConfigChainRole
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigLayerPresence
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.TimeMethodHost
import com.eratw.port.core.fn.TimeMethods
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.RootedFileOps
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W7 HOST-AUDIT：`core/host` 的宿主契约与原版宿主语义逐条对拍。
 *
 * 面（排除 `GraphicsBuffer.kt` 与 `SoundSink.kt`，那两块归别的路）：
 * 存储根解析 / 文件存在与枚举 / 文本读写编码 / 时间与时钟 / 配置读取 /
 * 按钮与输入桥 / 控制台尺寸。
 *
 * 期望值一律从反编译参考推导，逐条带 `文件:行号`；不用本仓实现回读当期望。
 * 已被既有用例锁定的条目**不重复**：`HostChainAuditTest`（PRINTN 并入、ButtonWrap、
 * InputRequest 缺省字段）、`BootHostsTest`（相对路径基本读写、越界基本用例）、
 * `ButtonStringCreatorTest`（自动按钮切分）、`LineBufferConsoleSinkTest`（PRINTC/DRAWLINE 细节）、
 * `ConfigChainTest` 与 `ConfigChainWireTest`（三层链本体与接线）。
 *
 * 本文件只做两件事：
 * 1. 钉住「原版这么写、移植侧也这么写」的同口径点（无 TODO）；
 * 2. 钉住**当前有分歧**的读数 —— 按现状断言 + 就地 TODO(verify) + C# 行号，不改产品代码。
 */
class HostContractAuditTest {

    private fun tempRoot(): Path = Files.createTempDirectory("eratw-host-audit")

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        val all = Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
        for (p in all) Files.deleteIfExists(p)
    }

    // ------------------------------------------------------------------
    // 存储根解析（Program.ExeDir / CsvDir 口径）
    // ------------------------------------------------------------------

    /**
     * 根归一化 + 相对路径解析：`root` 带 `.` 段也等价于根本身（`BootHosts.kt:50`
     * 的 `toAbsolutePath().normalize()`），`\\` 与 `/` 都吃（`HostMethods.kt:264-267` 的
     * GetValidPath 把 `/` 换成 `\\`）。
     *
     * 绝对路径这一条是**宿主层与原版的分歧点**：原版 `Utils.GetValidPath` 见到
     * rooted path 直接返回 null（`Utils.cs:478-481` 的 `Path.GetPathRoot(path) != ""`），
     * 而 [RootedFileOps] 接受落在根内的绝对路径（`BootHosts.kt:351-357`）。生产路径上
     * 函数层 `getValidPath` 已经先挡掉（`HostMethods.kt:316-319`），所以脚本无观测差；
     * 宿主被单独调用时口径不同，按现状钉桩。
     * TODO(verify)：是否让宿主也拒绝 rooted path（对齐 `Utils.cs:478-481`），卡点：
     * 宿主自己也拿绝对路径调用（`GameSession` 的装配），改成拒绝会波及内部调用点。
     */
    @Test
    fun `存储根 相对路径按注入根解析 绝对路径仅根内可达`() {
        val root = tempRoot()
        try {
            Files.createDirectories(root.resolve("resources"))
            Files.writeString(root.resolve("resources/a.xml"), "<a/>")

            val ops = RootedFileOps(root.resolve(".")) // `.` 段由 normalize 吃掉
            assertTrue(ops.exists("resources/a.xml"))
            assertEquals("<a/>", ops.readText("resources\\a.xml"), "反斜杠与斜杠等价")
            assertEquals("<a/>", ops.readText("resources/a.xml"))

            // 现状：根内绝对路径被接受（原版见 Utils.cs:478-481 会判 null）
            val absoluteInside = root.resolve("resources/a.xml").toAbsolutePath().toString()
            assertTrue(ops.exists(absoluteInside))
            assertEquals("<a/>", ops.readText(absoluteInside))
        } finally {
            deleteTree(root)
        }
    }

    /**
     * 越界拒绝：`resolvePath` 归一后必须仍在根内（`BootHosts.kt:351-357`）。
     *
     * 与「前缀相同」的兄弟目录也隔开（`Path.startsWith` 按路径元素比较，不是字符串前缀）。
     *
     * 与原版的口径差（已登记在 `RootedFileOps` KDoc，不重复登记）：
     * 原版 `GetValidPath` 把 `..\\` 直接**删掉**（`Utils.cs:475`），于是 `../x.txt` 在原版
     * 等于 `x.txt`（留在 ExeDir 内），本层是「归一后越界即视为不存在」。生产路径由函数层
     * `getValidPath` 先做删串（`HostMethods.kt:317`），所以脚本无观测差。
     * TODO(verify)：宿主被单独调用（`GameSession` 内部、未来的存档回放）时两条口径会分叉，
     * 卡点：是否把删串语义下移到 [RootedFileOps]，需先确认没有调用点依赖「越界判空」。
     */
    @Test
    fun `存储根 越界路径一律视为不存在 同前缀兄弟目录不可达`() {
        val root = tempRoot()
        val sibling = Path.of(root.toString() + "-sib")
        try {
            Files.createDirectories(sibling)
            Files.writeString(sibling.resolve("x.txt"), "leak")

            val ops = RootedFileOps(root)
            assertNull(ops.readText("../" + sibling.fileName.toString() + "/x.txt"))
            assertFalse(ops.exists(sibling.resolve("x.txt").toAbsolutePath().toString()))
            assertTrue(ops.list("../", "*").isEmpty())
            assertFalse(ops.writeText("../escape.txt", "x", append = false))
        } finally {
            deleteTree(root)
            deleteTree(sibling)
        }
    }

    // ------------------------------------------------------------------
    // 文件存在 / 目录判定 / 枚举
    // ------------------------------------------------------------------

    /**
     * `File.Exists` 与 `Directory.Exists` 是**两个判据**，不能合成一个布尔：
     * - EXISTFILE 走 `File.Exists`（`FunctionMethodCreator.cs:1918`）⇒ 目录不算；
     * - ENUMFILES 先走 `Directory.Exists`（`:355`）⇒ 普通文件给 -1。
     *
     * 因此宿主面必须分开报告：[RootedFileOps.exists] 与 [RootedFileOps.isDirectory]
     * （`BootHosts.kt:52-60`），函数层再做 `exists && !isDirectory` 的合取
     * （`HostMethods.kt:199-204`）。
     */
    @Test
    fun `EXISTFILE 与 ENUMFILES 的目录判据分开`() {
        val root = tempRoot()
        try {
            Files.writeString(root.resolve("a.txt"), "a")
            Files.createDirectories(root.resolve("empty"))

            val ops = RootedFileOps(root)
            assertTrue(ops.exists("a.txt"), "File.Exists(file) = true（:1918）")
            assertFalse(ops.isDirectory("a.txt"), "Directory.Exists(file) = false（:355）")
            assertTrue(ops.exists("empty"), "File.Exists(dir) 在 .NET 上为 false，本层 exists 是「路径存在」")
            assertTrue(ops.isDirectory("empty"), "空目录也必须能被判成目录（能力位，HostMethods.kt:154-157）")

            assertNull(ops.readText("empty"), "File.ReadAllText 对目录抛 ⇒ 原版 LOADTEXT 的 catch 给空串（:8082-8087）")
            assertTrue(ops.list("a.txt", "*").isEmpty(), "对普通文件枚举 ⇒ 空表，-1 由函数层给（:355-358）")
        } finally {
            deleteTree(root)
        }
    }

    /**
     * ENUMFILES 的**返回值形状与顺序**。
     *
     * 原版把 `Directory.EnumerateFiles(validPath, pattern, option)` 的结果原样拷进
     * RESULTS_ARRAY（`FunctionMethodCreator.cs:364-372`）：输入相对路径 ⇒ 返回值带该相对
     * 前缀、Windows 上分隔符是 `\\`、顺序由文件系统决定。
     *
     * 本层固定给「相对 root、`/` 分隔、按字典序」的路径（`BootHosts.kt:98-113`），
     * 这是 M6-B 已登记的差异（`FileOps.list` KDoc），这里钉住它的**形状**：
     * 脚本拿到的是 `sub/c.xml` 而不是 `sub\\c.xml`。
     * TODO(verify)：顺序与原版不同（原版依赖文件系统）。语料唯一调用点
     * `ERB/リソース管理/ResourceManager.ERB:46` 把结果喂 `LOADTEXT`，路径形状不影响读取，
     * 但进 RESULTS 的字符串会进变量池/L3 哈希；要对齐得先有 PC 实机枚举序。
     */
    @Test
    fun `ENUMFILES 返回 root 相对斜杠路径 顶层与递归各自成表`() {
        val root = tempRoot()
        try {
            Files.writeString(root.resolve("a.xml"), "<a/>")
            Files.writeString(root.resolve("b.txt"), "b")
            Files.writeString(root.resolve("noext"), "n")
            Files.createDirectories(root.resolve("sub"))
            Files.writeString(root.resolve("sub/c.xml"), "<c/>")
            Files.createDirectories(root.resolve("sub/deep"))
            Files.writeString(root.resolve("sub/deep/d.xml"), "<d/>")
            Files.createDirectories(root.resolve("sub2")) // 目录本身不进结果

            val ops = RootedFileOps(root)
            assertEquals(
                listOf("a.xml", "b.txt", "noext"),
                ops.list(".", "*", recursive = false),
                "TopDirectoryOnly（:360）：只要本层普通文件，目录不进表",
            )
            assertEquals(
                listOf("a.xml", "sub/c.xml", "sub/deep/d.xml"),
                ops.list(".", "*.xml", recursive = true),
                "AllDirectories（:360）+ pattern 只比文件名末段",
            )
            assertEquals(
                listOf("sub/c.xml"),
                ops.list("sub", "*", recursive = false),
                "返回值相对 root，不是相对入参 dir",
            )
            for (p in ops.list(".", "*", recursive = true)) {
                assertFalse(p.contains('\\'), "分隔符固定 '/'（原版是 Path.DirectorySeparatorChar）")
                assertFalse(Path.of(p).isAbsolute, "返回值不是绝对路径（原版会原样给 EnumerateFiles 的返回值）")
            }
        } finally {
            deleteTree(root)
        }
    }

    /**
     * Windows 通配两条：`?` = **恰好一个字符**、`*.*` 要求名字里真有 `.`。
     *
     * 原版把 pattern 原样交给 `Directory.EnumerateFiles`
     * （`FunctionMethodCreator.cs:359-364`），匹配语义由 .NET/OS 决定 —— 即 Windows 的
     * `*`/`?` 通配（`?` 是单字符通配，不是字面量）。`BootHosts.globToRegex` 原先只处理
     * `*`、把 `?` 转义成字面量，2026-10-26 深审波按 .NET 语义补齐（`?` → 单字符），
     * 本用例随之改钉；`*.*` 的读数两侧一致（.NET 的 `*.*` 等价于 `*`，但本层按
     * 「名字里真有 `.`」解释，语料零命中该形态）。
     * TODO(verify)：本机无 dotnet 对拍装置，`?` 与 `*.*` 的 .NET 精确读数仍缺一份
     * PC 侧 `Directory.EnumerateFiles` 采样表；语料唯一调用点只用 `*.xml`
     * （`ResourceManager.ERB:46`）与无 pattern（`:359` 缺省 `"*"`），当前无观测差。
     */
    @Test
    fun `ENUMFILES 通配问号是单字符 点星要求名字含点`() {
        val root = tempRoot()
        try {
            Files.writeString(root.resolve("ab.txt"), "1")
            Files.writeString(root.resolve("abc.txt"), "2")
            Files.writeString(root.resolve("noext"), "3")

            val ops = RootedFileOps(root)
            // `?` = 恰好一个字符 ⇒ 只有 "ab.txt" 命中（"abc.txt" 多一个字符）。
            assertEquals(
                listOf("ab.txt"),
                ops.list(".", "a?.txt", recursive = false),
                "`?` 是单字符通配（Windows/.NET 语义）",
            )
            assertEquals(
                listOf("ab.txt", "abc.txt"),
                ops.list(".", "*.*", recursive = false),
                "`*.*` 在本层要求名字含 `.` ⇒ 无扩展名文件不出现",
            )
            assertEquals(
                listOf("ab.txt", "abc.txt", "noext"),
                ops.list(".", "*", recursive = false),
                "缺省 pattern `*` 才是「全部」（:359）",
            )
        } finally {
            deleteTree(root)
        }
    }

    // ------------------------------------------------------------------
    // 文本读写
    // ------------------------------------------------------------------

    /**
     * LOADTEXT 的解码三段回退 = `EncodingHandler.DetectEncoding`（`EncodingHandler.cs:15-41`）：
     * UTF-8 BOM（:23-26）→ StreamReader 嗅探 UTF-16/32 BOM（:27-32）→ 严格 UTF-8（:33-35）
     * → CP932（:37-40，`shiftjisEncoding` 是 ExceptionFallback，:11、:70）。
     * 解码失败 ⇒ 原版 `ReadAllText` 抛 ⇒ LOADTEXT 的 catch 给空串（`FunctionMethodCreator.cs:8084-8087`）；
     * 本层返回 null，函数层转成空串（`HostMethods.kt:254`）。BOM 由解码消费，不进返回值。
     */
    @Test
    fun `LOADTEXT 编码三段回退 BOM 剥除 坏字节给 null`() {
        val root = tempRoot()
        try {
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            Files.write(root.resolve("utf8bom.txt"), bom + "aあ".toByteArray(StandardCharsets.UTF_8))
            Files.write(
                root.resolve("utf16le.txt"),
                byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "ab".toByteArray(StandardCharsets.UTF_16LE),
            )
            // CP932 的「あ」= 0x82 0xA0；这两个字节不是合法 UTF-8 ⇒ 必须回落 CP932
            Files.write(root.resolve("cp932.txt"), byteArrayOf(0x82.toByte(), 0xA0.toByte()))
            // 0x82 后面跟 0x20：CP932 的尾字节范围不含 0x20 ⇒ 严格解码必失败
            Files.write(root.resolve("bad.txt"), byteArrayOf(0x82.toByte(), 0x20))
            Files.write(root.resolve("empty.txt"), ByteArray(0))
            Files.writeString(root.resolve("plain.txt"), "ok")

            val ops = RootedFileOps(root)
            assertEquals("aあ", ops.readText("utf8bom.txt"), "UTF-8 BOM 被消费（:23-26）")
            assertEquals("ab", ops.readText("utf16le.txt"), "UTF-16LE BOM 嗅探（:27-32）")
            assertEquals("あ", ops.readText("cp932.txt"), "严格 UTF-8 失败后回落 CP932（:37-40）")
            assertNull(ops.readText("bad.txt"), "CP932 也解不出 ⇒ null = 原版 catch 给空串（:8084-8087）")
            assertEquals("", ops.readText("empty.txt"), "空文件读成空串，不是 null")
            assertEquals("ok", ops.readText("plain.txt"), "无 BOM 的纯 UTF-8 走第二段（:33-35）")
        } finally {
            deleteTree(root)
        }
    }

    /**
     * SAVETEXT 的落盘口径：`File.WriteAllText(path, strValue, Config.SaveEncode)`
     * （`FunctionMethodCreator.cs:8010`），`SaveEncode = UTF8BOMEncoding`（`Config.cs:19`）；
     * 父目录在写前建（`:8006-8008`）；失败返回 0（`:8012-8015`）。
     *
     * 编码分工：宿主只写 UTF-8 字节，BOM 由**调用方**以正文前的 U+FEFF 表达
     * （`BootHosts.kt:78-96`；函数层 `HostMethods.kt:230` 负责加）。
     *
     * [FileOps.writeText] 的 `append` 是**宿主能力位**，原版没有追加写：
     * 同一个 `WriteAllText` 恒覆盖（:8010），第 4 参求值后丢弃（:7963-7968）。
     * TODO(verify)：新增消费点前必须先在 C# 里找到对应语句（`FileOps.kt:22-31` 同条约定），
     * 卡点：OUTPUTLOG 也是 WriteAllText（`EmueraConsole.cs:3449`），全仓尚无追加写语句。
     */
    @Test
    fun `SAVETEXT 覆盖写 父目录自建 BOM 由调用方表达`() {
        val root = tempRoot()
        try {
            val ops = RootedFileOps(root)
            assertTrue(ops.writeText("out/note.txt", "one", append = false), ":8008 建父目录后 :8010 写入")
            assertEquals("one", ops.readText("out/note.txt"))
            assertTrue(Files.isRegularFile(root.resolve("out/note.txt")))

            assertEquals(listOf(0x6F, 0x6E, 0x65), Files.readAllBytes(root.resolve("out/note.txt")).map { it.toInt() and 0xFF },
                "宿主只落 UTF-8 字节，不替调用方加 BOM")

            assertTrue(ops.writeText("out/note.txt", "two", append = false))
            assertEquals("two", ops.readText("out/note.txt"), "append=false 覆盖（:8010 的 WriteAllText）")

            assertTrue(ops.writeText("out/note.txt", "three", append = true))
            assertEquals("twothree", ops.readText("out/note.txt"), "append 是能力位，原版无此路径")

            assertTrue(ops.writeText("bom.txt", "\uFEFFx", append = false))
            assertEquals(
                listOf(0xEF, 0xBB, 0xBF, 0x78),
                Files.readAllBytes(root.resolve("bom.txt")).map { it.toInt() and 0xFF },
                "调用方用 U+FEFF 表达 UTF8BOMEncoding 的前导符（Config.cs:19）",
            )

            assertFalse(ops.writeText("../escape.txt", "x", append = false), "越界写失败 = 原版返回 0（:8012-8015）")
        } finally {
            deleteTree(root)
        }
    }

    // ------------------------------------------------------------------
    // 时间与时钟
    // ------------------------------------------------------------------

    /**
     * 墙钟的**唯一注入出口**：`RootedBuiltinHost.clockMillis()` 每次现调注入的 `clock`
     * （`BootHosts.kt:305`），默认值 `System.currentTimeMillis()` 与
     * `BuiltinHost.clockMillis()` 的缺省逐字相同（`Builtins.kt:33`），所以不接新宿主时行为零变化。
     *
     * 原版这一族全部读 `DateTime.Now`（GETTIME `FunctionMethodCreator.cs:3978`、
     * GETTIMES `:3993`、GETMILLISECOND `:4008`、GETSECOND `:4023`），没有第二个时钟源。
     */
    @Test
    fun `时钟 宿主注入是唯一墙钟出口`() {
        val root = tempRoot()
        try {
            var calls = 0
            val pinned = RootedBuiltinHost(root, clock = { calls++; 555L })
            assertEquals(555L, pinned.clockMillis())
            assertEquals(555L, pinned.clockMillis())
            assertEquals(2, calls, "每次读时钟都要真的走注入的 clock，不许缓存成第二真相")

            val real = RootedBuiltinHost(root)
            val before = System.currentTimeMillis()
            val read = real.clockMillis()
            val after = System.currentTimeMillis()
            assertTrue(read in before..after, "缺省 = System.currentTimeMillis()（Builtins.kt:33）")
        } finally {
            deleteTree(root)
        }
    }

    /**
     * 时间函数族的纯折算：原版读的是**本地墙上时间**的 `DateTime.Now.Ticks`
     * （GETMILLISECOND `:4008` 的 `Ticks / 10000`、GETSECOND `:4023` 的 `Ticks / 10000000`，
     * GETTIMES `:3993` 的 `yyyy/MM/dd HH:mm:ss`）。
     *
     * `DateTime.Now.Ticks` 存本地墙钟 ⇒ 折算必须含该时刻的 UTC 偏移（+8h @ Asia/Shanghai），
     * 以及 .NET 元点偏移 719162 天（`TimeMethods.NET_EPOCH_OFFSET_MILLIS`）。
     * GETSECOND 是整数除法（正值 ⇒ 向下取整），与 .NET 的 `Ticks/N` 同口径。
     */
    @Test
    fun `GETMILLISECOND 与 GETSECOND 按本地墙钟折算`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val epoch = 1_700_000_000_000L // 2023-11-14T22:13:20Z = 2023-11-15T06:13:20+08:00

        assertEquals(epoch + 28_800_000L, TimeMethods.localWallMillis(epoch, zone), "本地墙钟 = epoch + 该时刻偏移")
        val millis = TimeMethods.millisecondsSinceNetEpoch(epoch, zone)
        assertEquals(epoch + 28_800_000L + TimeMethods.NET_EPOCH_OFFSET_MILLIS, millis, ":4008 Ticks/10000")
        assertEquals(millis / 1000L, TimeMethods.secondsSinceNetEpoch(epoch, zone), ":4023 Ticks/10000000")
        assertEquals(millis, TimeMethods.millisFromTicks(TimeMethods.netTicks(epoch, zone)), "Ticks 与毫秒互为逆折算")
        assertEquals("1970/01/01 00:00:00", TimeMethods.times(0L, ZoneId.of("UTC")), ":3993 的格式串")
    }

    /**
     * 计时宿主：原版 `EmueraConsole.Await(int time)` 只在 `time > 0` 时 `Thread.Sleep`
     * （`EmueraConsole.cs:851-854`），非正值既不睡也不报错 —— [FixedTimerSink] 的累计量
     * 同样只记正值（`maxOf(millis, 0)`），并与「真睡」解耦，供 E2E 钉钟。
     *
     * [FixedTimerSink.advancePerRead] 用于断言差分行为：原版两次 `DateTime.Now` 之间
     * 必然前进（GETMILLISECOND 的差分用法，见 `ResourceManager.ERB:318` 一类）。
     */
    @Test
    fun `计时宿主 负延时不计 Await 只睡正值`() {
        val timer = FixedTimerSink(pinnedMillis = 1_000L, advancePerRead = 7L)
        assertEquals(1_000L, timer.nowMillis())
        assertEquals(1_007L, timer.nowMillis())
        assertEquals(2L, timer.reads)

        timer.delay(30)
        timer.delay(-5) // :851 `if (time > 0)`
        timer.delay(0)
        assertEquals(30L, timer.totalDelayMs)
        assertEquals(2L, timer.reads, "delay 不读时钟")

        NoopTimerSink.delay(-1)
        NoopTimerSink.delay(10)
        val before = System.currentTimeMillis()
        val read = NoopTimerSink.nowMillis()
        val after = System.currentTimeMillis()
        assertTrue(read in before..after, "缺省 nowMillis = 墙钟（TimerSink.kt:32）")

        val host = TimeMethodHost(FixedTimerSink(123L))
        assertEquals(123L, host.clockMillis(), "TimeMethodHost 把 TimerSink 转发成 BuiltinHost.clockMillis()")
    }

    // ------------------------------------------------------------------
    // 配置读取
    // ------------------------------------------------------------------

    /**
     * 三层链在**宿主面**的读数：`RootedBuiltinHost.configOverrides` 是三层合并快照
     * （`BootHosts.kt:243-291`），对应原版 `Program.cs:95` 一次 `LoadConfig` 的三次
     * `loadConfig` 调用（`ConfigData.cs:491-493`：`_default.config` → `emuera.config` →
     * `_fixed.config`，后层覆盖前层；固定层 `fix = true`，`:573-576` 置 `Fixed`）。
     *
     * CsvDir 的字面量是 `"csv"`（`Program.cs:207`），Windows 大小写不敏感、JVM 敏感 ——
     * 本层用 `resolveCsvDir` 兜（`EmueraConfigParser.kt:394-419`）。这里故意只建小写 `csv/`，
     * 钉「字面名优先 + 大小写不敏感」这一条存储根解析口径。
     *
     * `UseSaveFolder` 决定 `SavDir`（`Config.cs:471-481`：true ⇒ ExeDir + `sav` + 分隔符，
     * false ⇒ ExeDir），宿主把它交出去给存档探针（`BootHosts.kt:294-303`）。
     */
    @Test
    fun `配置读取 三层优先序与固定层钉位 及 csv 目录字面名`() {
        val root = tempRoot()
        try {
            Files.createDirectories(root.resolve("csv")) // Program.cs:207 的字面量
            Files.writeString(root.resolve("csv/_default.config"), "ウィンドウ幅:1000\nフォントサイズ:20\n")
            Files.writeString(root.resolve("emuera.config"), "FONT SIZE:19\nUSE SAV FOLDER:YES\n")
            Files.writeString(root.resolve("csv/_fixed.config"), "セーブデータをバイナリ形式で保存する:YES\n")

            val host = RootedBuiltinHost(root)
            assertEquals("1000", host.configOverrides["WINDOW WIDTH"], "只在 default 层写过的键也要进表（:491）")
            assertEquals("19", host.configOverrides["FONT SIZE"], "emuera.config 覆盖 default（:492）")
            assertEquals(
                "YES",
                host.configOverrides["USE THE BINARY FORMAT FOR SAVING DATA"],
                "fixed 层覆盖前两层（:493）",
            )
            assertTrue(
                host.configChain.fixedKeys.contains(ConfigKey.SystemSaveInBinary),
                "fix = true 的层要置 Fixed（:493、:573-576）",
            )
            assertEquals(
                ConfigLayerPresence.PRESENT,
                host.configChain.layer(ConfigChainRole.FIXED).presence,
            )
            assertEquals(root.resolve("csv"), host.configChain.csvDir, "CsvDir = ExeDir + \"csv\"（Program.cs:207）")
            assertTrue(host.useSaveFolder, "USE SAV FOLDER:YES ⇒ SavDir = ExeDir\\sav\\（Config.cs:471-477）")
        } finally {
            deleteTree(root)
        }
    }

    /**
     * 配置文件的**解码口径**：原版 `ConfigData.loadConfig`（`ConfigData.cs:512-520`）经
     * `EraStreamReader.Open`（`EraStreamReader.cs:52-58`）用 `EncodingHandler.DetectEncoding(path)`
     * 读行；检测顺序是 UTF-8 BOM → UTF-16/32 BOM → 严格 UTF-8 → **CP932**（`EncodingHandler.cs:15-41`，
     * `shiftjisEncoding` 在 :11 / :37-40）。所以无 BOM 的 CP932 配置在原版照常解析。
     *
     * 本层 `readConfigLines` 与 [com.eratw.port.core.config.EmueraConfigParser] 同口径：
     * UTF-8 严格解码失败时回落 shift_jis（`GetEncoding(932)`，EncodingHandler.cs:15-41、:11）
     * 重读一次，两条都失败才判 `UNREADABLE`（等价原版 `return false`）。
     */
    @Test
    fun `配置读取 非 UTF-8 的 emuera_config 按 CP932 回落读出`() {
        val root = tempRoot()
        try {
            Files.createDirectories(root.resolve("CSV"))
            val cp932 = Charset.forName("windows-31J")
            Files.write(root.resolve("emuera.config"), "フォントサイズ:19\n".toByteArray(cp932))

            val host = RootedBuiltinHost(root)
            assertEquals(
                ConfigLayerPresence.PRESENT,
                host.configChain.layer(ConfigChainRole.EMUERA).presence,
                "严格 UTF-8 解不动 ⇒ 回落 CP932 重读成功（EncodingHandler.cs:15-41）",
            )
            assertEquals(
                "19",
                host.configOverrides["FONT SIZE"],
                "CP932 回落后该层照常覆盖（ConfigData.cs:491-493）",
            )
            assertTrue(
                host.configChain.results.isNotEmpty(),
                "PRESENT 层要进 results，下游因此读到 FontSize = 19",
            )
        } finally {
            deleteTree(root)
        }
    }

    // ------------------------------------------------------------------
    // 控制台尺寸
    // ------------------------------------------------------------------

    /**
     * 控制台宽度的单位是**半角格**，且它对应原版的**可绘制宽度** `Config.DrawableWidth`，
     * 不是窗口客户区宽度：
     * - `DrawableWidth = WindowX - max(2, FontSize / 6)`（`Config.cs:461-466`）；
     * - DRAWLINE 用 `getStBar` 填满 DrawableWidth（`EmueraConsole.cs:3420-3437`，追加到
     *   ≥ 宽度再逐字符裁掉），本类 `barString` 同序（`LineBufferConsoleSink.kt:1395-1411`）；
     * - PRINTC 的原版宽度是 **Shift-JIS 字节数** `Encoding.GetEncoding("Shift-JIS").GetByteCount`
     *   （`:3255`），补齐/裁剪只动空格（`:3267-3289`），本类按半角 1/全角 2 近似。
     *
     * 缺省 `clientWidth = 1800` 是**基准占位**（PC 基准 ClientWidth 1800px，KDoc:20-30），
     * 不是原版出厂：出厂 `WindowX = 760`（`ConfigData.cs:56`）⇒ DrawableWidth 757px。
     * TODO(verify)：生产装配必须传「DrawableWidth ÷ 实测半角步进」（PC 基准 = 199 格），
     * 卡点：比例字体下换算依赖 `:render` 的 `GlyphCache.measureCells`。
     */
    @Test
    fun `控制台尺寸 宽度单位是半角格 并驱动 DRAWLINE 与 PRINTC`() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        assertEquals(40, s.clientWidth)
        s.printBar()
        s.newLine()
        assertEquals(1, s.snapshot().size)
        assertEquals(40, s.loggedLines().single().length, "`-` 半角 1 格 ⇒ 40 个字符")
        assertEquals(40, s.widthOf(s.snapshot().single()), "填满 DrawableWidth（:3420-3437）")

        val custom = LineBufferConsoleSink(clientWidth = 40)
        custom.printCustomBar("━") // U+2501 在 CP932 里是双字节 ⇒ 全角 2 格
        custom.newLine()
        assertEquals(20, custom.loggedLines().single().length)
        assertEquals(40, custom.widthOf(custom.snapshot().single()))
        assertFailsWith<CodeEE> { custom.printCustomBar("") } // :3398-3400 空串是 CodeEE

        val c = LineBufferConsoleSink(clientWidth = 40)
        c.printC("あ", alignRight = true)
        c.newLine()
        assertEquals(25, c.widthOf(c.snapshot().single()), "右对齐补到 PrintCLength 个空格宽（:3267-3269）")
        assertEquals("あ", c.loggedLines().single().trim())

        assertEquals(1800, LineBufferConsoleSink().clientWidth, "缺省是基准占位（不是原版出厂 760，ConfigData.cs:56）")
        assertEquals(25, LineBufferConsoleSink().printCWidth, "Config.PrintCLength 出厂 25（ConfigData.cs:64）")
    }

    // ------------------------------------------------------------------
    // 输入 / 按钮桥
    // ------------------------------------------------------------------

    /**
     * 输入宿主的**缺省能力位**与鼠标现场的唯一实例纪律。
     *
     * - [InputSink.mouseState] 缺省 null = 该宿主不提供鼠标现场；原版在窗口未创建时
     *   `GetMousePosition()` 返回 `default(Point)` = (0,0)，连 Y 位移都不做
     *   （`EmueraConsole.cs:2259-2262`），所以 MOUSE 族按 aux 槽缺省值给 0，不抛错。
     * - `MOUSEY` 是**原始 Y 减 ClientHeight**（`:2264`）⇒ 鼠标在可视区内时恒为负，
     *   原版反直觉口径，照抄（`MouseState.kt:96-100`）。
     * - [InputSink.consumeMesSkip] 缺省 false：不区分提交来源的宿主天然无跳过；
     *   原版标志来自 `PressEnterKey(keySkip, ...)`（`EmueraConsole.cs:1371-1373`）。
     * - [RuntimeAux] 是每 VM 一份的弱结构容器（`RuntimeAux.kt:3-18`）：`slot` 只建一次、
     *   `peek` 只查不建、槽一旦建成类型固定（取错类型抛 ClassCastException）。
     */
    @Test
    fun `输入宿主缺省能力位与鼠标现场同一实例`() {
        assertNull(NoopInputSink.mouseState(), "缺省 = 无鼠标现场（原版无窗口读数 0,0，:2259-2262）")
        assertFalse(NoopInputSink.consumeMesSkip())
        NoopInputSink.readAnyKey()
        NoopInputSink.readAnyKey(anyKey = true, stopMesSkip = true) // 无操作，不许抛

        val aux = RuntimeAux()
        assertTrue(aux.slotNames().isEmpty())
        val mouse = mouseStateOf(aux)
        assertTrue(aux.peek<MouseState>(MOUSE_AUX_SLOT) === mouse, "宿主与 fn 层取的是同一个对象")
        assertTrue(mouseStateOf(aux) === mouse, "鼠标现场入口幂等")

        assertEquals(0L, mouse.mouseX(), "窗口未创建 ⇒ 0（:2259-2262）")
        assertEquals(0L, mouse.mouseY())

        mouse.setClientSize(100, 50)
        mouse.setPointerPosition(10, 20)
        assertEquals(0L, mouse.mouseX(), "窗口未创建时即使已有坐标也读 0（:2259-2262）")
        assertTrue(mouse.isPointerInClientArea(), "半开区间 [0,Width) x [0,Height)（FunctionMethodCreator.cs:7879）")

        mouse.windowCreated = true
        assertEquals(10L, mouse.mouseX())
        assertEquals(-30L, mouse.mouseY(), "MOUSEY = 原始 Y - ClientHeight（:2264），可视区内恒负")

        mouse.setPointerPosition(10, 50)
        assertFalse(mouse.isPointerInClientArea(), "y = Height 已在区间外")

        // 槽一旦建成类型固定：取错类型是编程错误，不静默（RuntimeAux.kt:22-29 的类 KDoc）
        assertFailsWith<ClassCastException> {
            @Suppress("UNUSED_VARIABLE")
            val wrong: String = aux.slot(MOUSE_AUX_SLOT) { "not-a-mouse" }
        }
        aux.clear()
        assertTrue(aux.slotNames().isEmpty(), "clear 清空全部槽（RESTART / 读档后重建）")
    }
}
