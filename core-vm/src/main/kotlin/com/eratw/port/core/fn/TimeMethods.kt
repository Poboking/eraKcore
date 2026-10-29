package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.host.NoopTimerSink
import com.eratw.port.core.host.TimerSink
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * 时间 / 内存函数族：GETTIMES / GETMILLISECOND / GETSECOND / GETMEMORYUSAGE
 * （[registerTimeMethods] + 纯折算函数 [TimeMethods] + JVM 侧内存计数器 [processMemoryReading]）。
 *
 * 所有者：CONTRACT-M4-I §2 的 i-time 路；**分派已由 M4-J j-dispatch 接上**
 * （[registerTimeMethods] 在 `Builtins.buildRegistry()` 末尾调用，同时摘掉了
 * `HostMethods.kt` 的 GETTIMES/GETMILLISECOND/GETSECOND 与 `DebugMiscMethods.kt` 的
 * GETMEMORYUSAGE 四条旧注册）。[claimable] 的让路闸保留：它保证任何一路单独接线都不会
 * 撞上「真实现撞真实现即 error」（Builtins.kt:594-601）。
 *
 * ------------------------------------------------------------------
 * 一、权威出处（逐条 `reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`）
 * ------------------------------------------------------------------
 * - 注册表：`:9086 ["GETTIMES"] = new GettimesMethod()`、`:9087 ["GETMILLISECOND"] = new GetmsMethod()`、
 *   `:9088 ["GETSECOND"] = new GetSecondMethod()`（任务书让「回注册表找 GETSECOND 的类体」，键在 :9088）、
 *   `:9290 ["GETMEMORYUSAGE"] = new GetUsingMemoryMethod()`。
 * - `GettimeMethod`（GETTIME，相邻的一条）`:3967-3981`：逐字段拼 `yyyymmddhhmmssmmm`，取值全来自 `DateTime.Now`。
 * - `GettimesMethod` `:3982-3995`：`ReturnType = typeof(string)`（:3986）、
 *   `argumentTypeArray = Array.Empty<Type>()`（:3987，**零参数**）、`CanRestructure = false`（:3988）、
 *   `return DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss")`（:3993）。
 * - `GetmsMethod` `:3997-4010`：`ReturnType = typeof(long)`（:4001）、零参数（:4002）、
 *   `return DateTime.Now.Ticks / 10000`（:4008）。
 * - `GetSecondMethod` `:4012-4025`：`ReturnType = typeof(long)`（:4016）、零参数（:4017）、
 *   `return DateTime.Now.Ticks / 10000000`（:4023）。
 * - `GetUsingMemoryMethod` `:8307-8321`：`ReturnType = typeof(long)`（:8311）、零参数（:8312）、
 *   `using Process process = Process.GetCurrentProcess(); return process.WorkingSet64;`（:8318-8319）。
 *   同类 `ClearMemoryMethod` `:8323-8340` 取的是**同一个** WorkingSet64 在 `GC.Collect()` 前后的差（:8334-8338），
 *   可反证 WorkingSet64 是「每次现取的进程级读数」，不是缓存值。
 *
 * ------------------------------------------------------------------
 * 二、合同要求「定死」的五个分歧点（本路的裁定，全部带上面的行号）
 * ------------------------------------------------------------------
 * 1. **时基**：`DateTime.Now`（墙钟绝对时刻），**不是** `Environment.TickCount`（上机时长、32 位回绕）。
 *    四条函数体里没有出现 TickCount；GETMILLISECOND 名字容易让人以为是「毫秒级运行计时」，
 *    实际是 `Ticks/10000` = 自公元 0001-01-01 起的毫秒数（:4008）。
 * 2. **时区**：**本地时区**。`DateTime.Now` 返回 `Kind = Local` 的 `DateTime`，其 `Ticks` 字段存的是
 *    **本地墙上时间**相对 0001-01-01T00:00:00 的 tick 数（不是 UTC）。所以
 *    `Ticks/10000 = epoch毫秒 + 该时刻的 UTC 偏移`。`ToString` 同样按本地（:3993）。
 * 3. **GETTIMES 的「元素」**：不存在「九个元素」—— 它零参数、返回**一个** `yyyy/MM/dd HH:mm:ss` 字符串
 *    （:3987 / :3991-3994）。契约 §2 i-time 那句「九个元素顺序与越界返回」的前提与反编译不符，
 *    顺序/个数由 :3993 的格式串唯一决定（年/月/日/时/分/秒 六段，秒以下被格式串截掉）。
 *    「越界」在这条函数上只有一个含义：**参数个数**不对 → 原版 `ArgsCountNotMatches`
 *    （FunctionMethod.cs:302-335），移植侧由 `BuiltinMethod.checkArity`（Builtins.kt:565-573）承担，
 *    文案 `函数 GETTIMES 的参数个数不匹配（需要 0 个）：实际 1 个`。
 * 4. **GETMILLISECOND / GETSECOND 的值域**：正数、约 1.78e13（ms）/ 1.78e10（s）量级。
 *    `.NET` 的 `Ticks/N` 是整数除法（向零截断；值为正 ⇒ 等价 floor），本实现用 `Long` 除法同口径。
 *    0001-01-01 → 1970-01-01 的毫秒常数 = 719162 天 × 86_400_000 = 62_135_596_800_000
 *    （[TimeMethods.NET_EPOCH_OFFSET_MILLIS]，与 `HostMethods.kt:143`、`MapDataTableMethods.kt:497` 同值）。
 * 5. **GETMEMORYUSAGE 的计数器与单位**：`WorkingSet64` = **进程物理工作集（working set）字节数**，
 *    单位是**字节**，原版没有任何 KB 换算（:8319 直接返回 long）。
 *
 * ------------------------------------------------------------------
 * 三、与现移植侧的差口（实测；M4-J j-dispatch 已按原版全部修正）
 * ------------------------------------------------------------------
 * - `GETTIMES`：`HostMethods.kt:134-139` 已落地且口径正确 —— 它经 `timeFields`（:347-359）用
 *   `Calendar.getInstance()`（默认时区）取本地字段，与 :3993 一致。本文件的 [TimeMethods.times] 与它同值。
 * - `GETMILLISECOND` / `GETSECOND`：`HostMethods.kt:140-148` 用 `clockMillis() + 62135596800000`，
 *   `clockMillis()` 是 **UTC epoch 毫秒**（Builtins.kt:33），**漏了第 2 条的本地 UTC 偏移**。
 *   在 Asia/Shanghai（闸机实测 `ZoneId.systemDefault()`）上移植侧比原版**小 28 800 000**（8 小时）。
 *   同一漏口也出现在 `MapDataTableMethods.kt:497`（DT 行主键的 tick 折算）。
 *   影响面：语料里 GETMILLISECOND 全部是**差分/取模**用法（如
 *   `ERB/魔改内容/示例函数/PLAY_GOMOKU.ERB:377,396` 的 `END_TIME = GETMILLISECOND() + 1000 + RAND:1000`、
 *   `ERB/ビジュアル関連/_Design.erb:585` 的 `/ 50`、`ERB/リソース管理/ResourceManager.ERB:318` 的 LRU 标记），
 *   差分不受常数偏移影响，取模只在**偏移不被模数整除**时变（28 800 000 能被 50 / 15 / 80 整除，故那几条不变），
 *   但**绝对值会进 RESULT / 变量池 / 存档 / L3 哈希链**，修正它必然要求重落 L3 链常量 ⇒
 *   接线切到本文件即修正，代价是**绝对值进 RESULT / 变量池 / 存档 / L3 哈希链**的读数变化
 *   ⇒ L3 链常量必须由组长重落（本路不动登记常量，见交付报告）。
 *
 * ------------------------------------------------------------------
 * 四、JVM 侧内存计数器的选型（合同 §2 i-time 判据 2 的实测对照）
 * ------------------------------------------------------------------
 * 闸机实测（主机 Haruki，`C:/DSH/eratw-安卓移植计划/.tooling/jdk-17.0.20.1+1`，
 * 命令 `java -Xmx512m`，进程持有 64 MiB 活跃 `byte[]`；同一进程同时用 PowerShell
 * `Get-Process` 采 Windows 工作集）：
 *
 * | 候选读数 | 实测字节数 | 与原版 WorkingSet64 的比 |
 * |---|---|---|
 * | Windows `Process.WorkingSet64`（**原版口径**） | 111 968 256 | 1.00（基准） |
 * | `Runtime.totalMemory - freeMemory`（本实现的回落值） | 70 737 048 | 0.63（少约 41.2 MB） |
 * | `MemoryMXBean` heapUsed + nonHeapUsed | 72 717 704 | 0.65 |
 * | `com.sun...OperatingSystemMXBean.getCommittedVirtualMemorySize()` | 359 104 512 | 3.21（对应 .NET `PrivateMemorySize64` = 360 058 880，**不是**工作集） |
 *
 * 另外反射列举 `com.sun.management.OperatingSystemMXBean` 的方法表，JDK 17.0.20 只有
 * getCommittedVirtualMemorySize / getCpuLoad / getFreeMemorySize / getFreePhysicalMemorySize /
 * getFreeSwapSpaceSize / getProcessCpuLoad / getProcessCpuTime / getSystemCpuLoad /
 * getTotalMemorySize / getTotalPhysicalMemorySize / getTotalSwapSpaceSize ——
 * **没有**任何「每进程物理内存」方法（`getTotalProcessRss` / `getTotalProcessPhysicalMemorySize`
 * 实测 `NoSuchMethodException`），故本文件不引用它们（AGENTS 铁律 1：禁脑补）。
 *
 * ⇒ 选型：与 WorkingSet64 同口径（物理驻留）的是 Linux/Android 的 `/proc/self/status` 的 `VmRSS`
 * 字段（proc(5) 单位为 kB，× 1024 得字节；不需要页大小，比 `/proc/self/statm` 的「页数 × 页大小」
 * 更稳，16 KB 页的 Android 设备也不会错倍）。该文件不可读时（Windows 闸机就是这种情况）
 * 回落到 used heap，并把**取的是哪个计数器**放进 [MemoryUsageReading.counter]，
 * 让宿主/上层能判口径，不装作两者等价。
 *
 * core 层纯 JVM：只用了 `java.io.File` / `java.lang.ManagementFactory` 之外的标准库能力
 * （读 `/proc` 属于**进程自省**，不是游戏数据 IO，不违反 `HostMethods.kt` KDoc 的
 * 「core 不触碰文件系统」那条 —— 该条针对的是 ERB/存档文件；需要严格隔离时宿主可自喂
 * [processMemoryReading] 的 statusReader，或直接实现 `BuiltinHost.memoryUsageBytes`）。
 * 禁 `kotlin.random`、禁静态可变全局：本文件没有任何可变单例，读数是每次现取（与 :8318 的
 * `using Process` 每次新建同口径）。
 */
/**
 * 时区取序：显式传参（单测钉死）优先；缺省按**宿主注入点**现取
 * （`BuiltinHost.zoneId()`，缺省 `ZoneId.systemDefault`）——不再在注册期捕获机器时区，
 * 否则 E2E/L3 装置钉不住它，L3 链 #10 起跨环境漂移（BuiltinHost.zoneId 的 KDoc）。
 */
internal fun registerTimeMethods(r: BuiltinRegistry, zone: ZoneId? = null) {
    val I = OperandType.INT
    val S = OperandType.STR

    // spec: :3982-3995 —— 零参数、本地时区的 yyyy/MM/dd HH:mm:ss 单串。
    if (claimable(r, "GETTIMES")) {
        r.register(BuiltinMethod("GETTIMES", S) { c, _ ->
            Value.StrV(TimeMethods.times(c.host.clockMillis(), zone ?: c.host.zoneId()))
        })
    }

    // spec: :3997-4010 —— DateTime.Now.Ticks/10000 = 本地墙上时间自 0001-01-01 起的毫秒数。
    if (claimable(r, "GETMILLISECOND")) {
        r.register(BuiltinMethod("GETMILLISECOND", I) { c, _ ->
            Value.IntV(TimeMethods.millisecondsSinceNetEpoch(c.host.clockMillis(), zone ?: c.host.zoneId()))
        })
    }

    // spec: :4012-4025 —— 同上除以 10^7（整数除法截断）。
    if (claimable(r, "GETSECOND")) {
        r.register(BuiltinMethod("GETSECOND", I) { c, _ ->
            Value.IntV(TimeMethods.secondsSinceNetEpoch(c.host.clockMillis(), zone ?: c.host.zoneId()))
        })
    }

    // spec: :8307-8321 —— WorkingSet64，单位字节。宿主没接线时抛 CodeEE，不静默返回 0
    // （与 DebugMiscMethods.kt:112-116 现有实现的文案逐字相同，保证两条路同判）。
    if (claimable(r, "GETMEMORYUSAGE")) {
        r.register(BuiltinMethod("GETMEMORYUSAGE", I) { c, _ ->
            val bytes = c.host.memoryUsageBytes
                ?: throw CodeEE("函数 GETMEMORYUSAGE：内存用量宿主尚未接线（需要宿主注入 BuiltinHost.memoryUsageBytes）")
            Value.IntV(bytes)
        })
    }
}

/**
 * 名字是否可注册：没注册过、或只是 `registerUnimplementedAll` 铺的占位（Builtins.kt:603-610）。
 *
 * 已存在**真实现**时必须让路 —— `BuiltinRegistry.register` 对「真实现撞真实现」直接
 * `error("内建函数重名注册")`（Builtins.kt:594-601），而 GETTIMES/GETMILLISECOND/GETSECOND
 * M4-J j-dispatch 之前由 `HostMethods.kt` 注册、GETMEMORYUSAGE 由 `DebugMiscMethods.kt` 注册；那四条旧注册已摘掉，故本函数在产线注册表里实际是接管方。
 * 有了这道闸，组长把 [registerTimeMethods] 加进 `Builtins.buildRegistry`（Builtins.kt:633-656）
 * 时**不需要**先删任何一边，也不会崩；等 HostMethods 那三条被摘掉后本实现自动接管。
 */
private fun claimable(r: BuiltinRegistry, name: String): Boolean =
    r[name] == null || r.isPlaceholder(name)

/**
 * 时间族的纯折算函数：入参一律是 **UTC epoch 毫秒**（= `BuiltinHost.clockMillis()` 的口径），
 * 时区显式传入（默认 [ZoneId.systemDefault]，对应 .NET 的本地时区）。
 *
 * 全部无副作用、可钉死，单测直接断言格式化后的字符串与整数。
 */
object TimeMethods {

    /** 公元 0001-01-01T00:00:00 → 1970-01-01T00:00:00 的毫秒数 = 719162 × 86_400_000。 */
    const val NET_EPOCH_OFFSET_MILLIS: Long = 62_135_596_800_000L

    /** .NET 的 tick = 100 纳秒；`Ticks/10000` 即毫秒（:4008）。 */
    const val TICKS_PER_MILLI: Long = 10_000L

    /** `Ticks/10000000` 即秒（:4023）。 */
    const val TICKS_PER_SECOND: Long = 10_000_000L

    /**
     * 本地墙上时间的毫秒数（自 1970-01-01 本地 00:00:00 起算）。
     *
     * `DateTime.Now.Ticks` 存的就是这个基准，所以三条时间函数都要先加当时（per-instant，
     * 考虑夏令时）的 UTC 偏移，再折算 —— 这是本文件第三节那条漏口的正解。
     */
    fun localWallMillis(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        epochMillis + zone.rules.getOffset(Instant.ofEpochMilli(epochMillis)).totalSeconds * 1000L

    /**
     * GETTIMES 的返回（:3993）。
     *
     * .NET 侧的 `ToString(格式串)` 走 CurrentCulture：斜杠是 culture 的日期分隔符、冒号是时间分隔符，
     * 且 ja-JP / zh-CN / ko-KR / en-US（`Config.useLanguage` 的枚举档见
     * `ConfigSpec.kt:160` 的 USE_LANGUAGE 常量表，出厂默认 JAPANESE 同那条）
     * 的分隔符正是斜杠与冒号、历法是 Gregorian、数字是 ASCII ⇒ 与这里的固定 ASCII 格式化同值。
     * TODO(verify)：th-TH 之类 Buddhist 历/非 ASCII 数字的 culture 下 .NET 会输出不同文本；
     * 本移植的目标语言集合里没有它。
     */
    fun times(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val zdt = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val local = zdt.toLocalDateTime()
        return String.format(
            java.util.Locale.ROOT,
            "%04d/%02d/%02d %02d:%02d:%02d",
            local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute, local.second,
        )
    }

    /**
     * GETMILLISECOND 的返回（:4008）：`Ticks/10000` = 本地墙上时间自 0001-01-01 起的毫秒数。
     * 入参是 UTC epoch 毫秒，故内部先 [localWallMillis] 再 [NET_EPOCH_OFFSET_MILLIS]。
     */
    fun millisecondsSinceNetEpoch(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        localWallMillis(epochMillis, zone) + NET_EPOCH_OFFSET_MILLIS

    /** GETSECOND 的返回（:4023）：`Ticks/10000000`，整数除法截断（值为正 ⇒ floor）。 */
    fun secondsSinceNetEpoch(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        millisecondsSinceNetEpoch(epochMillis, zone) / 1000L

    /** `DateTime.Now.Ticks` 本体（100 ns 单位）；`MapDataTableMethods.kt:497` 折算 DT 行主键时要用同一条。 */
    fun netTicks(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        millisecondsSinceNetEpoch(epochMillis, zone) * TICKS_PER_MILLI

    /** 由 `Ticks` 反推毫秒（= `Ticks/10000`），用于对照断言，不参与分派。 */
    fun millisFromTicks(ticks: Long): Long = ticks / TICKS_PER_MILLI
}

/**
 * [TimeMethods] 与 JVM 内存计数器的宿主实现：把时间/内存两条取数口做成可注入的一件东西。
 *
 * - [clockMillis] 转发给 [timer] 的 [TimerSink.nowMillis]。`NoopTimerSink` 的默认实现就是
 *   `System.currentTimeMillis()`，与 `BuiltinHost.clockMillis()` 的默认值逐字相同 ⇒
 *   **不接 FixedTimerSink 时产线行为零变化**，既有钉钟点一个都不动。
 * - [memoryUsageBytes] 返回 [memory] 的读数（默认 [processMemoryReading]）。宿主想自己
 *   报口径时把 [memory] 传 null ⇒ 退回「未接线抛 CodeEE」的既有语义（DebugMiscMethods.kt:112-116）。
 */
class TimeMethodHost(
    private val timer: TimerSink = NoopTimerSink,
    private val memory: (() -> MemoryUsageReading)? = { processMemoryReading() },
) : BuiltinHost {
    override fun clockMillis(): Long = timer.nowMillis()

    override val memoryUsageBytes: Long? get() = memory?.invoke()?.bytes
}

/** [MemoryUsageReading] 用的计数器标识，值即「本实现取的是哪一个数」。 */
enum class MemoryCounter {
    /** `/proc/self/status` 的 `VmRSS`（kB × 1024）；与原版 WorkingSet64 同口径（物理驻留）。 */
    PROC_STATUS_VM_RSS,

    /** `Runtime.totalMemory - freeMemory`（仅 Java 堆已用；Windows/无 procfs 时的回落）。 */
    USED_HEAP,
}

/**
 * 一次内存读数的结果。
 *
 * @property bytes   字节数（原版 WorkingSet64 的单位，无 KB 换算，:8319）
 * @property counter 取的是哪个计数器 —— 与原版口径的差要靠这个字段判，不许静默等价
 */
data class MemoryUsageReading(val bytes: Long, val counter: MemoryCounter)

/** 从 `/proc/self/status` 的文本里取 `VmRSS`（proc(5)：单位 kB）。没有该字段返回 null。 */
internal fun parseVmRssBytes(statusLines: List<String>): Long? {
    for (line in statusLines) {
        if (!line.startsWith("VmRSS:")) continue
        val kb = line.substring("VmRSS:".length).trim().split(' ', '\t').firstOrNull { it.isNotEmpty() }
            ?.toLongOrNull() ?: return null
        return kb * 1024L
    }
    return null
}

/** Java 堆已用字节数（`totalMemory - freeMemory`，与 `HostMethods.kt:118` 的 CLEARMEMORY 同源口径）。 */
fun usedHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

/**
 * 现取一次进程内存读数：优先 `/proc/self/status` 的 `VmRSS`（与原版 WorkingSet64 同口径），
 * 该文件不存在/不可读/无 `VmRSS` 字段时回落 used heap，并在 [MemoryUsageReading.counter] 里
 * 如实登记回落（口径差多少见本文件 KDoc 第四节表）。
 *
 * [statusReader] 是可注入的取行器：单测喂固定文本、Android 侧喂 `Debug.getMemoryInfo` 一类
 * 更准的读数时都不必改本函数。默认实现每次现读（原版 :8318 也是每次 `new Process`，不缓存）。
 */
fun processMemoryReading(statusReader: () -> List<String>? = { readSelfStatus() }): MemoryUsageReading {
    parseVmRssBytes(statusReader() ?: emptyList())?.let {
        return MemoryUsageReading(it, MemoryCounter.PROC_STATUS_VM_RSS)
    }
    return MemoryUsageReading(usedHeapBytes(), MemoryCounter.USED_HEAP)
}

/** procfs 读取器；Windows 上没有 procfs，返回 null 走回落（不抛，内存读数不许让函数失败）。 */
private fun readSelfStatus(): List<String>? {
    val f = File("/proc/self/status")
    return if (f.isFile && f.canRead()) runCatching { f.readLines() }.getOrNull() else null
}
