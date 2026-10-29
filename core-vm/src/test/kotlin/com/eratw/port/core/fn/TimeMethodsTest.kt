package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.FixedTimerSink
import com.eratw.port.core.host.NoopTimerSink
import com.eratw.port.core.vars.VariableStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 时间 / 内存族测试（GETTIMES / GETMILLISECOND / GETSECOND / GETMEMORYUSAGE）。
 *
 * 判据出处（`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`）：
 * - `:9086/:9087/:9088/:9290` 注册表 + 类体 `:3982-3995`（GettimesMethod，
 *   `DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss")` :3993 —— 分隔符是**斜杠**）、
 *   `:3997-4010`（GetmsMethod，`DateTime.Now.Ticks/10000`）、
 *   `:4012-4025`（GetSecondMethod，`Ticks/10000000`）、`:8307-8321`（GetUsingMemoryMethod，`WorkingSet64`）。
 * - 台账文本另见 `specs/builtin-functions.yaml:2066-2081`（GETTIMES 返回 string、
 *   GETMILLISECOND 返回「自公元 1 年 1 月 1 日起经过的毫秒数」）。
 *
 * 时钟一律钉死：`FixedTimerSink` / 固定 `clockMillis()` + 显式 [ZoneId]，
 * 所以时间族断言的是**格式化后的具体字符串与具体整数**，不随主机时钟或时区漂移。
 * 只有最后一条用例吃 ERATW_CORPUS（未设变量即 assumeTrue 跳过）。
 */
private const val NET_EPOCH = 62_135_596_800_000L

/** 2023-11-14T22:13:20Z（整秒，便于逐区断言墙上时间）。 */
private const val PINNED = 1_700_000_000_000L

/** 同一秒内带 123 毫秒的读数，专门用来验证「毫秒被格式串截掉 / 秒向下取整」。 */
private const val PINNED_MS = 1_700_000_000_123L

private val Z_UTC: ZoneId = ZoneId.of("UTC")
private val Z_SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
private val Z_NEWYORK: ZoneId = ZoneId.of("America/New_York")

private class TmBaseCtx : EvalContext {
    override lateinit var variables: VariableReader

    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("TmBaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

/** 钉住 epoch 毫秒 +（可选）固定内存读数的宿主；[clock] 为 null 时退回墙钟。 */
private class TmHost(
    private val clock: Long? = PINNED,
    override val memoryUsageBytes: Long? = null,
) : BuiltinHost {
    override fun clockMillis(): Long = clock ?: System.currentTimeMillis()
}

private class TmEnv(host: BuiltinHost = TmHost()) {
    val base = TmBaseCtx()
    val store = VariableStore()
    val ctx: BuiltinEvalContext

    init {
        base.variables = store
        ctx = BuiltinEvalContext(base, store, host)
    }
}

private fun newRegistry(zone: ZoneId): Pair<BuiltinRegistry, BuiltinEvalContext> {
    val r = BuiltinRegistry()
    registerTimeMethods(r, zone)
    return r to TmEnv().ctx
}

private fun BuiltinRegistry.intOf(name: String, ctx: BuiltinEvalContext): Long {
    val m = assertNotNull(this[name], "注册表里没有 $name")
    return assertNotNull(m.eval, "$name 没有值求值入口").invoke(ctx, emptyList()).let {
        (it as Value.IntV).v
    }
}

private fun BuiltinRegistry.strOf(name: String, ctx: BuiltinEvalContext): String {
    val m = assertNotNull(this[name], "注册表里没有 $name")
    return assertNotNull(m.eval, "$name 没有值求值入口").invoke(ctx, emptyList()).let {
        (it as Value.StrV).v
    }
}

// ---------------------------------------------------------------------------
// GETTIMES —— 零参数、本地时区的单个格式化字符串（:3987 / :3991-3994）
// ---------------------------------------------------------------------------

/** 同一个 epoch 读数在五个时区上的**具体文本**：证明时基是本地墙上时间，且分隔符是斜杠。 */
@Test
fun `GETTIMES 按时区给出固定文本`() {
    assertEquals("2023/11/14 22:13:20", TimeMethods.times(PINNED, Z_UTC))
    assertEquals("2023/11/15 06:13:20", TimeMethods.times(PINNED, Z_SHANGHAI))
    assertEquals("2023/11/14 17:13:20", TimeMethods.times(PINNED, Z_NEWYORK))
    assertEquals("2023/11/15 03:43:20", TimeMethods.times(PINNED, ZoneId.of("Asia/Kolkata")))
    // 伦敦 11 月已是冬令时（GMT），与 UTC 同文本
    assertEquals("2023/11/14 22:13:20", TimeMethods.times(PINNED, ZoneId.of("Europe/London")))
}

/** 亚秒不进文本（格式串只到秒），也不影响秒位（截断而非四舍五入）。 */
@Test
fun `GETTIMES 截掉毫秒`() {
    assertEquals(TimeMethods.times(PINNED, Z_UTC), TimeMethods.times(PINNED_MS, Z_UTC))
    assertEquals("2023/11/15 06:13:20", TimeMethods.times(PINNED_MS, Z_SHANGHAI))
}

/** `yyyy` 是「至少四位」，公元 1 年补零到 0001 —— .NET 与本机格式化同口径。 */
@Test
fun `GETTIMES 补零到四位年`() {
    assertEquals("0001/01/01 00:00:00", TimeMethods.times(-NET_EPOCH, Z_UTC))
}

/** 注册路径（BuiltinMethod 的 eval）：钉住 clockMillis 后必须是确定字符串。 */
@Test
fun `GETTIMES 经注册表取钉住的时钟`() {
    val (r, ctx) = newRegistry(Z_SHANGHAI)
    assertEquals("2023/11/15 06:13:20", r.strOf("GETTIMES", ctx))
    assertEquals(OperandType.STR, assertNotNull(r["GETTIMES"]).retType)
}

/** 零参数函数：多传参数在原版是 ArgsCountNotMatches（FunctionMethod.cs:302-335）。 */
@Test
fun `GETTIMES 的参数个数口径`() {
    val (r, _) = newRegistry(Z_UTC)
    assertNotNull(r["GETTIMES"], "GETTIMES 未注册").checkArity(0) // 合法，不抛
    val e = assertFailsWith<CodeEE> { assertNotNull(r["GETTIMES"], "GETTIMES 未注册").checkArity(1) }
    assertTrue(e.message!!.contains("GETTIMES"), "文案应点名函数名：${e.message}")
    assertTrue(e.message!!.contains("需要 0 个"), "文案应给出期望个数 0：${e.message}")
}

// ---------------------------------------------------------------------------
// GETMILLISECOND / GETSECOND —— Ticks 折算（:4008 / :4023）
// ---------------------------------------------------------------------------

/** 常数本体：0001-01-01 → 1970-01-01 = 719162 天 × 86_400_000 毫秒。 */
@Test
fun `NET 历元常数`() {
    assertEquals(719_162L * 86_400_000L, NET_EPOCH)
    assertEquals(NET_EPOCH, TimeMethods.NET_EPOCH_OFFSET_MILLIS)
    // DateTime(1,1,1).Ticks == 0 ⇒ epoch 往前推整个常数时读数归零
    assertEquals(0L, TimeMethods.millisecondsSinceNetEpoch(-NET_EPOCH, Z_UTC))
    assertEquals(0L, TimeMethods.netTicks(-NET_EPOCH, Z_UTC))
}

/**
 * 具体整数 + **时区偏移必须计入**（`DateTime.Now` 的 Ticks 是本地墙上时间，见 :4008）。
 * Asia/Shanghai +8h = +28 800 000 毫秒、America/New_York -5h = -18 000 000、Asia/Kolkata +5:30 = +19 800 000。
 */
@Test
fun `GETMILLISECOND 是本地墙钟自公元 1 年起的毫秒数`() {
    val base = PINNED + NET_EPOCH // 63_835_596_800_000
    assertEquals(63_835_596_800_000L, base)
    assertEquals(base, TimeMethods.millisecondsSinceNetEpoch(PINNED, Z_UTC))
    assertEquals(base + 28_800_000L, TimeMethods.millisecondsSinceNetEpoch(PINNED, Z_SHANGHAI))
    assertEquals(base - 18_000_000L, TimeMethods.millisecondsSinceNetEpoch(PINNED, Z_NEWYORK))
    assertEquals(base + 19_800_000L, TimeMethods.millisecondsSinceNetEpoch(PINNED, ZoneId.of("Asia/Kolkata")))
    assertEquals(63_835_625_600_000L, TimeMethods.millisecondsSinceNetEpoch(PINNED, Z_SHANGHAI))
}

/** 亚秒保留（毫秒读数不该被整秒化）。 */
@Test
fun `GETMILLISECOND 保留毫秒尾数`() {
    assertEquals(
        TimeMethods.millisecondsSinceNetEpoch(PINNED, Z_UTC) + 123L,
        TimeMethods.millisecondsSinceNetEpoch(PINNED_MS, Z_UTC),
    )
}

/** GETSECOND = `Ticks/10000000` 的整数除法：123 毫秒被截掉（:4023）。 */
@Test
fun `GETSECOND 向下截断到整秒`() {
    assertEquals(63_835_596_800L, TimeMethods.secondsSinceNetEpoch(PINNED, Z_UTC))
    assertEquals(63_835_596_800L, TimeMethods.secondsSinceNetEpoch(PINNED_MS, Z_UTC))
    assertEquals(63_835_625_600L, TimeMethods.secondsSinceNetEpoch(PINNED, Z_SHANGHAI))
    assertEquals(63_835_578_800L, TimeMethods.secondsSinceNetEpoch(PINNED, Z_NEWYORK))
    val kolKata = ZoneId.of("Asia/Kolkata")
    // 与 Ticks 定义互推：秒 = 毫秒 / 1000（整数除法），Ticks = 毫秒 × 10000
    assertEquals(
        TimeMethods.millisecondsSinceNetEpoch(PINNED, kolKata) / 1000L,
        TimeMethods.secondsSinceNetEpoch(PINNED, kolKata),
    )
    assertEquals(638_355_968_000_000_000L, TimeMethods.netTicks(PINNED, Z_UTC))
    assertEquals(PINNED + NET_EPOCH, TimeMethods.millisFromTicks(TimeMethods.netTicks(PINNED, Z_UTC)))
}

@Test
fun `GETMILLISECOND 与 GETSECOND 经注册表取钉住的时钟`() {
    val (r, ctx) = newRegistry(Z_SHANGHAI)
    assertEquals(63_835_625_600_000L, r.intOf("GETMILLISECOND", ctx))
    assertEquals(63_835_625_600L, r.intOf("GETSECOND", ctx))
    assertEquals(OperandType.INT, assertNotNull(r["GETMILLISECOND"]).retType)
    assertEquals(OperandType.INT, assertNotNull(r["GETSECOND"]).retType)
}

// ---------------------------------------------------------------------------
// 时钟注入缝（合同 §2 i-time 的硬约束：不许绕过 TimerSink 读墙钟）
// ---------------------------------------------------------------------------

/** TimeMethodHost 把 TimerSink 的读数转发成 clockMillis —— 每一次读都落在注入的时钟源上。 */
@Test
fun `TimeMethodHost 经 TimerSink 取时钟`() {
    val timer = FixedTimerSink(PINNED)
    val host = TimeMethodHost(timer)
    assertEquals(PINNED, host.clockMillis())
    assertEquals(PINNED, host.clockMillis())
    assertEquals(2L, timer.reads, "两次 clockMillis 应两次落在注入的时钟源上")

    val r = BuiltinRegistry()
    registerTimeMethods(r, Z_SHANGHAI)
    val ctx = TmEnv(host).ctx
    assertEquals("2023/11/15 06:13:20", r.strOf("GETTIMES", ctx))
    assertEquals(3L, timer.reads, "函数路径必须再读一次同一个时钟源（没有旁路读墙钟）")
}

/** 每次读数前进固定步长 ⇒ GETMILLISECOND 的差分可断言（原版两次调用之间必然前进）。 */
@Test
fun `TimerSink 步进模式`() {
    val timer = FixedTimerSink(PINNED, advancePerRead = 1_500L)
    val r = BuiltinRegistry()
    registerTimeMethods(r, Z_UTC)
    val ctx = TmEnv(TimeMethodHost(timer)).ctx
    val first = r.intOf("GETMILLISECOND", ctx)
    val second = r.intOf("GETMILLISECOND", ctx)
    assertEquals(1_500L, second - first)
    assertEquals(PINNED + NET_EPOCH, first)
}

/** 默认实现（不接 FixedTimerSink）必须与 `BuiltinHost.clockMillis()` 的默认值同一条墙钟。 */
@Test
fun `NoopTimerSink 的默认时钟不改既有钉钟点取值`() {
    val before = System.currentTimeMillis()
    val fromSink = NoopTimerSink.nowMillis()
    val fromHost = TimeMethodHost().clockMillis()
    val after = System.currentTimeMillis()
    assertTrue(fromSink in before..after + 1L, "sink 默认值应等于墙钟：$fromSink vs $before..$after")
    assertTrue(fromHost in before..after + 1L, "宿主默认值应等于墙钟：$fromHost vs $before..$after")
}

// ---------------------------------------------------------------------------
// GETMEMORYUSAGE —— WorkingSet64（:8319，单位字节）
// ---------------------------------------------------------------------------

/** 接线后原样返回（与 DebugMiscMethods.kt:112-116 同文案同口径），未接线抛 CodeEE，不静默给 0。 */
@Test
fun `GETMEMORYUSAGE 的接线与未接线两态`() {
    val (r, _) = newRegistry(Z_UTC)
    val wired = TmEnv(TmHost(memoryUsageBytes = 111_968_256L)).ctx
    assertEquals(111_968_256L, r.intOf("GETMEMORYUSAGE", wired))

    val unwired = TmEnv().ctx
    val e = assertFailsWith<CodeEE> { r.intOf("GETMEMORYUSAGE", unwired) }
    assertEquals("函数 GETMEMORYUSAGE：内存用量宿主尚未接线（需要宿主注入 BuiltinHost.memoryUsageBytes）", e.message)
    assertNull(TimeMethodHost(memory = null).memoryUsageBytes, "memory=null 应退回「未接线」而不是伪造读数")
}

/** procfs 文本的解析口径：取 `VmRSS` 的数值段（kB）× 1024；没有该字段返回 null。 */
@Test
fun `VmRSS 解析`() {
    val status = listOf(
        "Name:\tjava",
        "Umask:\t0022",
        "State:\tS (sleeping)",
        "VmPeak:\t  744567 kB",
        "VmSize:\t  744567 kB",
        "VmRSS:\t     3245 kB",
        "VmData:\t  217060 kB",
        "Threads:\t12",
    )
    assertEquals(3_322_880L, parseVmRssBytes(status))
    // 原版 WorkingSet64 返回的是**字节**，KB 口径必须在这里换算掉
    assertEquals(3245L * 1024L, parseVmRssBytes(status))
    assertNull(parseVmRssBytes(listOf("VmSize:\t100 kB")))
    assertNull(parseVmRssBytes(emptyList()))
}

/** 计数器优先级：有 VmRSS 走同口径的物理驻留，没有则回落 used heap 并**如实登记**。 */
@Test
fun `内存计数器的选择顺序`() {
    val withRss = processMemoryReading { listOf("VmRSS:\t1024 kB") }
    assertEquals(MemoryCounter.PROC_STATUS_VM_RSS, withRss.counter)
    assertEquals(1_048_576L, withRss.bytes)

    val noProcfs = processMemoryReading { null }
    assertEquals(MemoryCounter.USED_HEAP, noProcfs.counter)
    assertTrue(noProcfs.bytes > 0L, "used heap 读数应为正：${noProcfs.bytes}")
    assertTrue(noProcfs.bytes <= Runtime.getRuntime().maxMemory(), "used heap 不可能超过 -Xmx")

    val emptyProcfs = processMemoryReading { emptyList() }
    assertEquals(MemoryCounter.USED_HEAP, emptyProcfs.counter)
}

/**
 * 活体读数：环境里到底有哪个计数器，决定必须取哪个（闸机 = Windows，无 procfs ⇒ USED_HEAP；
 * Linux/Android ⇒ PROC_STATUS_VM_RSS）。这条把「选型顺序」钉在真实进程上。
 */
@Test
fun `活体内存读数与 procfs 可用性一致`() {
    val status = File("/proc/self/status")
    val reading = processMemoryReading()
    assertTrue(reading.bytes in 1L..(1L shl 40), "量级异常：${reading.bytes}")
    if (status.isFile && status.canRead() && parseVmRssBytes(status.readLines()) != null) {
        assertEquals(MemoryCounter.PROC_STATUS_VM_RSS, reading.counter)
    } else {
        assertEquals(MemoryCounter.USED_HEAP, reading.counter)
    }
}

/** 与本机 .NET 口径的量级对照（闸机实测见 TimeMethods.kt KDoc 第四节的表）：used heap 少约 37%。 */
@Test
fun `回落计数器的量级`() {
    val heap = usedHeapBytes()
    assertTrue(heap > 0L)
    assertTrue(heap <= Runtime.getRuntime().totalMemory(), "used heap 不该超过当前堆上限")
    val reading = processMemoryReading { null }
    assertEquals(MemoryCounter.USED_HEAP, reading.counter)
    assertTrue(reading.bytes in 1L..(1L shl 34), "used heap 量级异常：${reading.bytes}")
}

// ---------------------------------------------------------------------------
// 与现移植侧（禁改的 HostMethods / DebugMiscMethods）的差分
// ---------------------------------------------------------------------------

/**
 * GETTIMES：本实现与已经接线的 `HostMethods.kt:134-139` 在默认时区下**逐字相同**
 * ⇒ 组长把名字换到本文件时，GETTIMES 这一路不动任何基准读数。
 */
@Test
fun `GETTIMES 与既有接线同值`() {
    val wired = assertNotNull(Builtins.registry["GETTIMES"], "GETTIMES 应已由 HostMethods 接线")
    val ctx = TmEnv(TmHost(clock = PINNED)).ctx
    val wiredValue = assertNotNull(wired.eval).invoke(ctx, emptyList())
    assertEquals(Value.StrV(TimeMethods.times(PINNED)), wiredValue)
}

/**
 * GETMILLISECOND / GETSECOND：现接线漏了本地 UTC 偏移（见 TimeMethods.kt KDoc 第三节）。
 * 断言的是**差值恰为本地偏移**这个关系，所以在任何时区、任何主机都不会漂：
 * UTC 主机上两者相等，Asia/Shanghai 上本实现大 28 800 000 毫秒。
 */
@Test
fun `现接线的毫秒秒读数缺少本地时区偏移`() {
    val zone = ZoneId.systemDefault()
    val offsetMillis = zone.rules.getOffset(Instant.ofEpochMilli(PINNED)).totalSeconds * 1000L
    val ctx = TmEnv(TmHost(clock = PINNED)).ctx

    val wiredMs = assertNotNull(Builtins.registry["GETMILLISECOND"]?.eval).invoke(ctx, emptyList()) as Value.IntV
    assertEquals(PINNED + NET_EPOCH, wiredMs.v, "HostMethods.kt:143 的现口径 = UTC 毫秒 + 常数（无偏移）")
    assertEquals(offsetMillis, TimeMethods.millisecondsSinceNetEpoch(PINNED) - wiredMs.v)

    val wiredSec = assertNotNull(Builtins.registry["GETSECOND"]?.eval).invoke(ctx, emptyList()) as Value.IntV
    assertEquals((PINNED + NET_EPOCH) / 1000L, wiredSec.v, "HostMethods.kt:147 的现口径")
    assertEquals(offsetMillis / 1000L, TimeMethods.secondsSinceNetEpoch(PINNED) - wiredSec.v)
}

/** GETMEMORYUSAGE：本文件与 DebugMiscMethods 的既接线路径同值同文案。 */
@Test
fun `GETMEMORYUSAGE 与既有接线同值`() {
    val wired = assertNotNull(Builtins.registry["GETMEMORYUSAGE"], "GETMEMORYUSAGE 应已由 DebugMiscMethods 接线")
    assertEquals("GETMEMORYUSAGE", wired.name)
    val ctx = TmEnv(TmHost(memoryUsageBytes = 123_456_789L)).ctx
    assertEquals(Value.IntV(123_456_789L), assertNotNull(wired.eval).invoke(ctx, emptyList()))
}

// ---------------------------------------------------------------------------
// 「可重复接线」：组长把它加进 Builtins 时不会撞名
// ---------------------------------------------------------------------------

/** 已有真实现时让路（保住 HostMethods 的现读数）；占位与空位则接管。 */
@Test
fun `registerTimeMethods 的可重复接线`() {
    val r = BuiltinRegistry()
    registerTimeMethods(r, Z_UTC)
    val first = assertNotNull(r["GETTIMES"])
    // 第二次注册不得抛「内建函数重名注册」（Builtins.kt:594-601）
    registerTimeMethods(r, Z_UTC)
    assertEquals(first, r["GETTIMES"])

    val sentinel = BuiltinMethod("GETMILLISECOND", OperandType.INT) { _, _ -> Value.IntV(-7L) }
    val r2 = BuiltinRegistry()
    r2.register(sentinel)
    registerTimeMethods(r2, Z_UTC)
    assertEquals(-7L, r2.intOf("GETMILLISECOND", TmEnv().ctx), "真实现必须不被覆盖")

    val r3 = BuiltinRegistry()
    r3.registerUnimplemented("GETSECOND", OperandType.INT, 0, 0)
    assertTrue(r3.isPlaceholder("GETSECOND"))
    registerTimeMethods(r3, Z_UTC)
    assertTrue(!r3.isPlaceholder("GETSECOND"), "占位应被真实现接管")
    assertEquals(63_835_596_800L, r3.intOf("GETSECOND", TmEnv().ctx))
}

// ---------------------------------------------------------------------------
// 语料侧（本路唯一吃 ERATW_CORPUS 的用例；变量缺失时 assumeTrue 跳过）
// ---------------------------------------------------------------------------

/**
 * 语料确实在用 GETMILLISECOND，而且全是**差分/取模**用法 ——
 * 这正是「常数偏移不影响玩法、但会影响进基准与存档的绝对值」的实证。
 * 调用点：`ERB/魔改内容/示例函数/PLAY_GOMOKU.ERB:377`（`END_TIME = GETMILLISECOND() + 1000 + RAND:1000`）。
 */
@Test
fun `语料里的 GETMILLISECOND 调用点`() {
    val corpus = (System.getenv("ERATW_CORPUS") ?: System.getenv("ERATW_GAMEDIR")).orEmpty()
    assumeTrue(corpus.isNotBlank(), "未设 ERATW_CORPUS / ERATW_GAMEDIR：跳过语料用例")
    val erb = File(corpus, "ERB/魔改内容/示例函数/PLAY_GOMOKU.ERB")
    assumeTrue(erb.isFile, "语料里没有 ${erb.path}：跳过")
    val text = String(erb.readBytes(), Charsets.ISO_8859_1) // 只做 ASCII 子串检查，码页无关
    assertTrue(text.contains("GETMILLISECOND"), "PLAY_GOMOKU.ERB 应含 GETMILLISECOND 调用")
    assertTrue(
        Regex("""GETMILLISECOND\(\)\s*[-+*/%]""").containsMatchIn(text) ||
            Regex("""[-+*/%]\s*GETMILLISECOND\(\)""").containsMatchIn(text),
        "该文件里的 GETMILLISECOND 应至少有一处是差分/取模用法",
    )
}
