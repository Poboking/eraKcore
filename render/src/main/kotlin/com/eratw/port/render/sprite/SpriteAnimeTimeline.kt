package com.eratw.port.render.sprite

import com.eratw.port.core.host.SpritePool

/**
 * `SPRITEANIME` 的取帧语义（GFX-07）。纯 JVM、无时钟、无状态，`elapsedMs` 由宿主喂。
 *
 * ## 权威：`SpriteAnime.GetCurrentFrame`（SpriteAnime.cs:90-119）
 *
 * 逐行照抄的三段：
 * 1. **停止条件**：`totaltime <= 0` 直接返回 null（:92-95）⇒ 没有任何帧就一个像素都不画，
 *    也**不**置 `StartTime`（下一帧列表非空时才从头开始计时）。`totaltime` 是各帧 delay 之和
 *    （:78），而 delay 在入表时被夹到 ≥1（:72-76），所以「有帧」恒等于「totaltime > 0」。
 * 2. **首帧**：`lastFrame == -1` 时 `StartTime = DateTime.Now` 并返回 `FrameList[0]`（:96-102）。
 *    即：t=0 落在第 0 帧，计时起点是**第一次取帧**那一刻，不是 `SPRITEANIMECREATE` 那一刻。
 * 3. **推进**：`num = (now - StartTime) 毫秒 % totaltime`（:108），然后从头逐帧
 *    `num -= frame.DelayTimeMs; if (num <= 0) 选这一帧`（:109-117）。
 *
 * ## 帧序是**正序循环**，没有往返
 *
 * 参考实现里没有 ping-pong / 逆序分支：取帧就是一个 `% totaltime` 加一次单向累减
 * （SpriteAnime.cs:108-117），`ResetTime`（:83-88）在本快照里**没有任何调用点**（全仓检索
 * 只命中定义处）。所以这里只实现正序；若日后 emuera 版本加入往返模式，落点就是 [frameAt]。
 *
 * ## `<= 0` 是**闭区间**，帧边界与直觉差 1ms
 *
 * 累减到 `num <= 0` 才算命中（:112），于是「elapsed 恰好等于第一帧 delay」仍归第 0 帧。
 * 3 帧 × 100ms 的实测口径：`0..100 → 0`、`101..200 → 1`、`201..299 → 2`，300 回到 0。
 * 即 100ms 取到的是**第 0 帧**、不是第 1 帧 —— 这不是 off-by-one，是 C# 的 `<=` 本身。
 * 用例见 SpriteAnimeTimelineTest 的「闭区间边界」。
 *
 * ## 与渲染帧的关系
 *
 * 帧号只由「取帧时刻 - 首次取帧时刻」决定，跟绘制次数、跟屏幕刷新率都无关：
 * 掉帧就按 `% totaltime` 跳过中间帧（与 C# 同），同一毫秒内重复取帧必得同一帧（纯函数）。
 * `SETANIMETIMER` 只决定**重绘节奏**（EmueraConsole.cs:967-980），不参与帧推进，
 * 见 [repaintIntervalMs]。
 */
object SpriteAnimeTimeline {

    /** 没有可画的帧（`GetCurrentFrame` 返回 null 的等价物，SpriteAnime.cs:92-95）。 */
    const val NO_FRAME: Int = -1

    /**
     * `setRedrawTimer` 的下限（EmueraConsole.cs:973-976：`tickcount < 10` 时抬到 10）。
     *
     * 原版这里是 `Timer.Interval` 的单位毫秒下限，不是帧间隔；`SETANIMETIMER` 的
     * 参数范围校验在语句层（FunctionMethodCreator.cs:7925-7933，`Int.MIN..32767`）。
     */
    const val MIN_REPAINT_MS: Int = 10

    /**
     * 把帧表折算成 delay 数组，**逐项夹到 ≥1**（SpriteAnime.cs:72-76 的 `if (delay <= 0) delay = 1`）。
     *
     * 语句层已经先拒了 `delay <= 0`（FunctionMethodCreator.cs:7571-7574 返回 0、不入表），
     * 这里的夹取是给「直接构造 [SpritePool.Frame]」的路径兜底，同时保证 [totalMs] 与
     * 原版 `totaltime`（:78 加的是夹取后的值）逐字节一致。
     */
    fun delaysOf(frames: List<SpritePool.Frame>): IntArray =
        IntArray(frames.size) { maxOf(1, frames[it].delayMs) }

    /** 原版 `SpriteAnime.totaltime`（SpriteAnime.cs:46/:78）：夹取后的 delay 之和。 */
    fun totalMs(delays: IntArray): Long {
        var total = 0L
        for (d in delays) total += maxOf(1, d)
        return total
    }

    /**
     * `elapsedMs`（取帧时刻 - 首次取帧时刻）落在第几帧；没有帧返回 [NO_FRAME]。
     *
     * 逐行对应 SpriteAnime.cs:92-118：
     * ```
     * if (totaltime <= 0) return null;                       // :92-95
     * num = (now - StartTime).TotalMilliseconds % totaltime; // :108
     * foreach (frame) { num -= frame.DelayTimeMs; if (num <= 0) return frame; }  // :109-117
     * ```
     * 循环走完仍没命中在原版是 `throw ExeEE(SpriteTimeOut)`（:118）。数学上不可达
     * （rest 恒小于 totaltime = Σdelay，逐项减完必然 `<= 0`），这里返回末帧而不是抛：
     * 渲染路径上抛异常等于整屏黑，收益为零。
     *
     * 负的 `elapsedMs` 原版不可能出现（`DateTime.Now - StartTime` 非负），这里按模运算
     * 折回正区间，避免时钟回跳（改系统时间、单调源重置）时算出负下标。
     */
    fun frameAt(elapsedMs: Long, delays: IntArray): Int {
        if (delays.isEmpty()) return NO_FRAME
        val total = totalMs(delays)
        if (total <= 0L) return NO_FRAME
        var rest = elapsedMs % total
        if (rest < 0L) rest += total
        for (i in delays.indices) {
            rest -= maxOf(1, delays[i])
            if (rest <= 0L) return i
        }
        return delays.size - 1
    }

    /**
     * `SETANIMETIMER` 的读数 → 宿主该按多少毫秒重绘一次；0 = 不排周期重绘。
     *
     * 对应 `EmueraConsole.setRedrawTimer`（EmueraConsole.cs:967-980）：
     * `tickcount <= 0` 关掉定时器（:969-973）、`tickcount < 10` 抬到 10（:974-977）。
     * **只管重绘节奏**：帧推进仍按 [frameAt] 的挂钟算，所以「定时器关了」不等于「画面冻结」——
     * 原版此时任何一次别的原因触发的重绘（输入、打印）都会取到新帧，照抄。
     *
     * 卡点：本函数只给读数，真正的排程在宿主（`CanvasConsoleView.postInvalidateOnAnimation`
     * 那条重绘循环），归组长接线。
     */
    fun repaintIntervalMs(animeTimerMs: Int): Int = when {
        animeTimerMs <= 0 -> 0
        animeTimerMs < MIN_REPAINT_MS -> MIN_REPAINT_MS
        else -> animeTimerMs
    }
}

/** [planAnimeRepaint] 的判定结果（宿主据此撤表 / 重排 / 不动）。 */
enum class AnimeRepaintPlan {
    /** 在路上的那一拍间隔没变：不动（倒计时不许被每帧一次的问询重置）。 */
    Keep,

    /** 撤掉在路上的回调，按新间隔重排。 */
    Rearm,

    /** 关表（间隔 `<= 0`）：撤掉在路上的回调。 */
    Cancel,
}

/**
 * 宿主每次「排重画」前该做什么（纯函数，接线口 = `CanvasConsoleView.scheduleAnimeTick`）。
 *
 * 原版 `setRedrawTimer` 改值时是**当场**赋 `Timer.Interval` 再 `Enabled = true`
 * （EmueraConsole.cs:978-979），间隔变更即刻生效。宿主侧只有「排表」这一个动作，
 * 所以要自己判断「在路上的那一拍是不是还是当前间隔」：EraTW 平时把定时器压在
 * 32627 ms 的「关」哨兵（`USERCOM.ERB:16` / `EVENTCOMEND.ERB:18`），只在展示动态图时
 * 改 40（`Chara_data_25_藍.ERB:229`、`Toolkits.ERB:283`）—— 沿用「已排就不排」会让
 * 那条 32.6 秒的旧回调把 40 ms 一直挡到它到期，整段展示窗口一拍都不重画。
 *
 * @param intervalMs 本次采样到的间隔（0 = 关表；`< 10` 已由 [SpriteAnimeTimeline.repaintIntervalMs] 抬过）
 * @param pending 是否已经排了一拍还没跑
 * @param scheduledMs 排着的那一拍用的间隔
 */
fun planAnimeRepaint(intervalMs: Int, pending: Boolean, scheduledMs: Int): AnimeRepaintPlan = when {
    // 关表优先：它不看 [scheduledMs]，状态再怪也只会落到「撤表」或「什么都不做」。
    intervalMs <= 0 -> if (pending) AnimeRepaintPlan.Cancel else AnimeRepaintPlan.Keep
    pending && intervalMs == scheduledMs -> AnimeRepaintPlan.Keep
    else -> AnimeRepaintPlan.Rearm
}

/**
 * 单只动画 sprite 的计时起点（原版 `SpriteAnime.StartTime` / `lastFrame`，SpriteAnime.cs:48-52）。
 *
 * 只存「第一次取帧的时刻」，帧号现算不缓存：原版 `lastFrame` 只在 `GetCurrentFrame` 里
 * 回写并用于 `DateTime.Now == lastFrameTime` 的同 tick 短路（:103-106），那个短路的结果
 * 与「同一毫秒再算一次」完全相同（[SpriteAnimeTimeline.frameAt] 是纯函数），故不另存。
 *
 * **非线程安全**：本类由持有方加锁（platform 的 `SpriteBitmapSource` 一把锁管全部 sprite）。
 * 每只 sprite 一份实例，键用对象身份 —— `SPRITECREATE` 覆盖同名会换新 `Sprite`，
 * 原版同样换新 `SpriteAnime`（`StartTime` 归零、动画从头播）。
 */
class SpriteAnimeClock {

    private var startMs: Long = UNSET

    /** 已经取过帧（原版 `lastFrame != -1`，SpriteAnime.cs:96）。 */
    val started: Boolean get() = startMs != UNSET

    /**
     * 以 [nowMs] 为当前时刻取帧号（[SpriteAnimeTimeline.NO_FRAME] = 不画）。
     *
     * 第一次调用把 [nowMs] 记为起点并恒返回第 0 帧（SpriteAnime.cs:96-102）；
     * 空帧表**不**起点（:92-95 在 `lastFrame == -1` 之前就 return null）。
     */
    fun frameAt(nowMs: Long, delays: IntArray): Int {
        if (delays.isEmpty()) return SpriteAnimeTimeline.NO_FRAME
        if (startMs == UNSET) startMs = nowMs
        return SpriteAnimeTimeline.frameAt(nowMs - startMs, delays)
    }

    /** 原版 `ResetTime`（SpriteAnime.cs:83-88）：下一次取帧重新从第 0 帧开始。 */
    fun reset() {
        startMs = UNSET
    }

    private companion object {
        /** 未起点的哨兵；不能用 0（合法时刻）。 */
        const val UNSET = Long.MIN_VALUE
    }
}
