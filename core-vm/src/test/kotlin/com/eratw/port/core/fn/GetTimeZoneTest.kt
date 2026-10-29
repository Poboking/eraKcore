package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * GETTIME 的时区口径（缺陷总账 FN-09 / CONTRACT-Q1 §2.6）。
 *
 * 判据出处（`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`）：
 * - `GettimeMethod` `:3967-3982`，取值行 `:3978`：
 *   `((((((DateTime.Now.Year * 100 + Month) * 100 + Day) * 100 + Hour) * 100 + Minute) * 100 + Second) * 1000 + Millisecond`
 *   —— 七个字段全部取自 `DateTime.Now`，即 .NET 的**本地墙上时间**（本机时区）。
 * - 移植侧的「本地」由宿主回答：时刻走 [BuiltinHost.clockMillis]、时区走 [BuiltinHost.zoneId]
 *   （Builtins.kt:37-48，缺省 `ZoneId.systemDefault`，Builtins.kt:48）。同族的
 *   GETTIMES / GETMILLISECOND / GETSECOND 已按这条协议取（TimeMethods.kt:119-142）。
 *
 * 缺陷形态（修前）：`HostMethods` 的 `timeFields` 用 `Calendar.getInstance()`，时区是 JVM 进程
 * 默认区，注入的 [BuiltinHost.zoneId] 被绕过 —— 装置钉住 UTC 时表达式位 GETTIME 仍按闸机本地
 * 时区出数，与语句位 GETTIME（StatementExecutor.kt:832-863 已走 `host.zoneId()`）不同源。
 *
 * 断言策略：时钟与两个时区都钉死（Asia/Tokyo 与 UTC），期望值是**具体数字**，任何机器上都不漂；
 * 「默认 zoneId」一条只比两条缺省路径，不把任何具体时区写进期望值。
 */
private const val TZ_PINNED = 1_700_000_000_000L // 2023-11-14T22:13:20Z（整秒）
private const val TZ_PINNED_MS = 1_700_000_000_123L // 同一秒 + 123 毫秒
private const val TZ_NEW_YEAR = 1_704_067_200_000L // 2024-01-01T00:00:00Z（验「一月」不是 0）

private val TZ_UTC: ZoneId = ZoneId.of("UTC")
private val TZ_TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")

private class TzBaseCtx : EvalContext {
    override lateinit var variables: VariableReader

    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("TzBaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

/**
 * 手工宿主：钉死时刻，时区由测试指定。
 *
 * [zone] 为 null 时**不覆写** [BuiltinHost.zoneId]，走接口缺省实现（Builtins.kt:48）
 * —— 专门用来验证「未接线宿主」的缺省口径。
 */
private class TzHost(
    private val clock: Long,
    private val zone: ZoneId? = null,
) : BuiltinHost {
    override fun clockMillis(): Long = clock

    override fun zoneId(): ZoneId = zone ?: BuiltinHost.DEFAULT.zoneId()
}

private fun tzCtx(host: BuiltinHost): BuiltinEvalContext {
    val store = VariableStore()
    val base = TzBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, host)
}

/** 走**产线注册表**的 GETTIME（注册点在 `registerHostMethods`，Builtins.kt:672）。 */
private fun tzGetTime(host: BuiltinHost): Long {
    val m = assertNotNull(Builtins.registry["GETTIME"], "GETTIME 应已由 registerHostMethods 注册")
    val eval = assertNotNull(m.eval, "GETTIME 没有值求值入口")
    return (eval.invoke(tzCtx(host), emptyList()) as Value.IntV).v
}

/**
 * 独立的期望值折算（不调用被测的 `timeFields`）：按 C# `:3978` 的位权逐段拼。
 * 只吃 java.time 的拆字段结果，与引擎侧实现互不引用。
 */
private fun tzOracle(millis: Long, zone: ZoneId): Long {
    val t = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()
    var n = t.year.toLong()
    n = n * 100 + t.monthValue
    n = n * 100 + t.dayOfMonth
    n = n * 100 + t.hour
    n = n * 100 + t.minute
    n = n * 100 + t.second
    return n * 1000 + t.nano / 1_000_000
}

// ---------------------------------------------------------------------------
// ① 同一钉钟、两个宿主时区 → 两套具体字段（:3978）
// ---------------------------------------------------------------------------

/**
 * UTC 2023-11-14 22:13:20.123 vs Asia/Tokyo(+9) 2023-11-15 07:13:20.123：
 * 日、时两段都变，毫秒与分秒不变 —— 证明 GETTIME 读的是**宿主给的区**。
 */
class GetTimeZoneTest {

@Test
fun `GETTIME 按宿主 zoneId 拆出日历字段`() {
    assertEquals(20231114221320123L, tzGetTime(TzHost(TZ_PINNED_MS, TZ_UTC)))
    assertEquals(20231115071320123L, tzGetTime(TzHost(TZ_PINNED_MS, TZ_TOKYO)))
}

/** 七段字段的顺序与含义（年/月 1-12/日/时/分/秒/毫秒）逐段拆开钉住。 */
@Test
fun `GETTIME 的七段字段顺序与含义不变`() {
    val v = tzGetTime(TzHost(TZ_PINNED_MS, TZ_TOKYO))
    assertEquals(2023L, v / 10_000_000_000_000L, "年")
    assertEquals(11L, v / 100_000_000_000L % 100L, "月（1-12，不是 0-11）")
    assertEquals(15L, v / 1_000_000_000L % 100L, "日")
    assertEquals(7L, v / 10_000_000L % 100L, "时（24 小时制）")
    assertEquals(13L, v / 100_000L % 100L, "分")
    assertEquals(20L, v / 1_000L % 100L, "秒")
    assertEquals(123L, v % 1_000L, "毫秒")
    // 一月必须落 1 而不是 0（Calendar.MONTH 是 0-11，java.time monthValue 是 1-12）
    assertEquals(20240101090000000L, tzGetTime(TzHost(TZ_NEW_YEAR, TZ_TOKYO)))
}

/**
 * 判别力核心：同一钉钟只换宿主 zoneId，两个值必须不同。
 *
 * 若实现仍读 `Calendar.getInstance()` 的进程默认区（FN-09 修前），两个宿主会给出**同一个值**
 * （等于闸机本地区的结果）—— 与闸机在哪个时区无关，这条断言恒红。
 */
@Test
fun `GETTIME 不再读进程默认时区`() {
    val utc = tzGetTime(TzHost(TZ_PINNED_MS, TZ_UTC))
    val tokyo = tzGetTime(TzHost(TZ_PINNED_MS, TZ_TOKYO))
    assertNotEquals(utc, tokyo, "同一钉钟换 zoneId 必须换字段；相等说明仍在读进程默认区")
    assertEquals(tzOracle(TZ_PINNED_MS, TZ_UTC), utc)
    assertEquals(tzOracle(TZ_PINNED_MS, TZ_TOKYO), tokyo)
}

// ---------------------------------------------------------------------------
// ② 缺省 zoneId 的宿主：仍与接口缺省（= systemDefault 那一行）同源
// ---------------------------------------------------------------------------

/**
 * 不覆写 [BuiltinHost.zoneId] 的宿主 = 「未接线」口径（Builtins.kt:48）。
 *
 * 期望值只比两条**缺省路径**（接口匿名实现的 `zoneId()` 与手工宿主的缺省分支），
 * 不写死任何具体时区、也不拿 `ZoneId.systemDefault()` 反推期望值；
 * GETTIME 的期望值由宿主自己答的区经独立 oracle 折算 —— 闸机在哪个时区跑，判定都不变。
 */
@Test
fun `未覆写 zoneId 的宿主仍与接口缺省等价`() {
    val host = TzHost(TZ_PINNED)
    val bare = object : BuiltinHost {}
    assertEquals(bare.zoneId(), host.zoneId(), "缺省 branch 必须与 BuiltinHost 接口缺省同源")
    assertEquals(tzOracle(TZ_PINNED, host.zoneId()), tzGetTime(host))
    // 缺省路径与「显式钉成同一个缺省区」等价（两侧 zone 都取自运行时，不断言具体值）
    assertEquals(tzGetTime(host), tzGetTime(TzHost(TZ_PINNED, bare.zoneId())))
}

// ---------------------------------------------------------------------------
// ③ 两族口径一致：GETTIME 与 GETTIMES 吃同一个宿主 zoneId
// ---------------------------------------------------------------------------

/**
 * FN-09 的验收面：表达式位 GETTIME（本文件修的）与 GETTIMES（TimeMethods.kt:124-128）
 * 在同一个宿主上给出同一套年月日时分秒。
 *
 * `TimeMethods.times` 的文本 `yyyy/MM/dd HH:mm:ss`（:3993）去掉分隔符后应恰为 GETTIME
 * 的年月日时分秒部分，再接上毫秒段。
 */
@Test
fun `GETTIME 与 GETTIMES 两族按同一宿主 zoneId 取值`() {
    val ctx = tzCtx(TzHost(TZ_PINNED_MS, TZ_TOKYO))
    val gettime = assertNotNull(Builtins.registry["GETTIME"], "GETTIME 未注册")
    val gettimes = assertNotNull(Builtins.registry["GETTIMES"], "GETTIMES 未注册")
    val numeric = (assertNotNull(gettime.eval).invoke(ctx, emptyList()) as Value.IntV).v
    val text = (assertNotNull(gettimes.eval).invoke(ctx, emptyList()) as Value.StrV).v
    assertEquals("2023/11/15 07:13:20", text)
    assertEquals(text.filter { it.isDigit() } + "123", numeric.toString())
}

}
