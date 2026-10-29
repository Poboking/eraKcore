package com.eratw.port.core.host

/**
 * 计时宿主（AWAIT + 时间函数族的时钟入口）。
 *
 * spec: `FunctionIdentifier.cs:2581-2606` → `EmueraConsole.Await`（[delay]）；
 * `FunctionMethodCreator.cs:3982-4026`（GETTIMES / GETMILLISECOND / GETSECOND 三条都只读一个
 * 「当前时刻」，那条读数在本移植里必须有**唯一一个**可注入出口，见 [nowMillis]）。
 *
 * 时钟注入口径（CONTRACT-M4-I §2 i-time 的硬约束）：
 * - 生产侧的墙钟出口是 `BuiltinHost.clockMillis()`（`core/fn/Builtins.kt:33`），
 *   已由 `core/proc/BootHosts.kt:97` 的 `RootedBuiltinHost(root, clock)` 做成可注入；
 *   E2E / L3 的钉钟点走的就是这条缝，**本波一个取值都不改**。
 * - [TimerSink] 是语句层的同源入口：`ExecContext.timer`（`core/sem/ExecContext.kt:131`）。
 *   新增 [nowMillis] 只是把「同一个 epoch 毫秒读数」也暴露给计时宿主，
 *   默认实现直接调 `System.currentTimeMillis()`，与 `BuiltinHost.clockMillis()` 的默认值
 *   逐字相同 ⇒ 不接新宿主时行为零变化；接了 [FixedTimerSink] 的测试可以把时钟钉死。
 * - 需要把两者并成一条路时用 `core/fn/TimeMethods.kt` 的 `TimeMethodHost`
 *   （它把 [nowMillis] 转发成 `BuiltinHost.clockMillis()`），不要在各处再读 `System` 墙钟。
 */
interface TimerSink {
    /** 暂停 [millis] 毫秒；负值/上限校验由语句层完成。 */
    fun delay(millis: Long)

    /**
     * 当前时刻（epoch 毫秒，UTC 基准，不含时区偏移）。
     *
     * 本地墙上时间的折算（原版 `DateTime.Now` 的口径）由消费方完成，
     * 见 `core/fn/TimeMethods.kt` 的 `localWallMillis` —— 时钟源只负责给 epoch 毫秒，
     * 否则时区会被算两次。
     */
    fun nowMillis(): Long = System.currentTimeMillis()
}

// ------------------------------------------------------------------
// 计时单位（2026-10-22 L09 续任复核，逐条对 `reference/emuera-cs`；本接口三条取数口互不混用）
// - `AWAIT` 的参数 = **毫秒**，且校验在语句层：负值与超 10 秒各抛 CodeEE
//   （FunctionIdentifier.cs:2595-2602），随后 `(int)num` 截断交给 `Console.Await`（:2604）；
//   无参时原版取 -1（:2591），而 `Await` 只在 `time > 0` 才 `Thread.Sleep`
//   （EmueraConsole.cs:840-856，睡点在 :851-854）⇒ 无参 `AWAIT` = 只让出、不睡。
// - TINPUT 系的 `Timelimit` 同为**毫秒**，出厂缺省 -1（InputRequest.cs:23），
//   `Timelimit > 0` 才装计时器（EmueraConsole.cs:866-873），倒计时基准是
//   `Stopwatch.ElapsedMilliseconds`（:987/:1023）—— 与 [delay] 同一个毫秒单位，不是帧数。
// - GETMILLISECOND / GETSECOND 读的不是「上机时长」：原版是 `DateTime.Now.Ticks/10000` 与
//   `/10000000`（FunctionMethodCreator.cs:4008/:4023）= **本地墙上时间自公元 0001-01-01** 的
//   毫秒/秒数。故 [nowMillis] 只交 epoch 毫秒，本地偏移的折算归 `core/fn/TimeMethods.kt`。
// ------------------------------------------------------------------

object NoopTimerSink : TimerSink {
    override fun delay(millis: Long) {}
}

/**
 * 真让出的计时宿主：`AWAIT n` 在**调用线程**上睡 `n` 毫秒（`n <= 0` 只让出不睡）。
 *
 * 对应原版 `EmueraConsole.Await`（`EmueraConsole.cs:840-856`）里的
 * `state = Sleep → Application.DoEvents() → if (time > 0) Thread.Sleep(time)`。
 * 移植侧重绘与输入各有自己的线程，所以 VM 线程只需要睡这一条；
 * 缺省 [NoopTimerSink] 下整段 `@BADAPPLE`（`TITLE.ERB:123`，`FOR count, 1, 6573` 每帧
 * `AWAIT`/`AWAIT 16`）会全速跑完、与配乐彻底脱节。
 *
 * 被 interrupt 时恢复中断位并返回，让宿主的关停路径照常收敛（不吞中断）。
 */
object SleepingTimerSink : TimerSink {
    override fun delay(millis: Long) {
        if (millis <= 0L) return
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

/**
 * 钉钟计时宿主：[nowMillis] 恒返回 [pinnedMillis]，[delay] 只累计不真睡。
 *
 * 给时间函数族的单测与 E2E/L3 用（黄金基准与 L3 状态哈希链要求每次运行同一读数）。
 * [advancePerRead] 是可选的「每读一次前进固定步长」模式，用于断言 GETMILLISECOND
 * 的差分行为（原版 `DateTime.Now` 两次调用之间必然前进）。
 */
class FixedTimerSink(
    val pinnedMillis: Long,
    private val advancePerRead: Long = 0L,
) : TimerSink {
    var totalDelayMs = 0L
        private set

    /** 已读时钟次数（诊断用：证明函数确实经本宿主取时，而不是绕过去读墙钟）。 */
    var reads = 0L
        private set

    override fun delay(millis: Long) {
        totalDelayMs += maxOf(millis, 0L)
    }

    override fun nowMillis(): Long {
        val value = pinnedMillis + reads * advancePerRead
        reads++
        return value
    }
}
