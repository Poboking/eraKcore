package com.eratw.port.core.audio

/**
 * `repeat` 的三分类 —— 原版 `Sound.play` 只认这三种（Sound.cs:97-105）。
 *
 * 注意分类边界照抄原版，不许「顺手修好」：
 * - `-1` ⇒ [FOREVER]（套 `LoopStream`）；
 * - `> 1` ⇒ [REPEAT]（套 `RepeatStream(source, repeat)`）；
 * - **其余一律 [ONCE]** —— 包括 `0`、`-2`、`Int.MIN_VALUE`。原版没有 `Math.Max` 在这一层
 *   （夹取发生在语句层 `PLAYSOUND` 的 `Math.Max(实参, 1L)` 再 `(int)` 截断，
 *   FunctionIdentifier.cs:2621；那条截断会把 `4294967295` 变成 `-1` ⇒ 循环，见
 *   [SoundChannelTable.playSound] 的 KDoc）。所以本类必须让 `-1` 与 `-2` 分道扬镳。
 */
enum class LoopMode {
    /** 播一遍。 */
    ONCE,

    /** 重复 `plays` 遍（含第一遍）。 */
    REPEAT,

    /** 无限循环。 */
    FOREVER,
}

/**
 * `repeat` → 播放计划的纯函数（**唯一**分类点）。
 *
 * 两个消费者共用它，避免「模型说循环、平台说不循环」的第二真相：
 * - [SoundChannel.play] 只取 [looping]（决定自然播完要不要摘通道）；
 * - `MediaPlayerChannelDecoder.onRepeat` 取 [looping] 与 [plays]（映射到 `isLooping` 与
 *   手动重播计数）。
 *
 * ## 循环点（本波点名项）
 * 原版的循环点**恒为 0**：`LoopStream.Read` 读到文件尾就把内层流 `Position = 0`
 * （LoopStream.cs:44-50），没有 smpl 块 / 循环标记之类的东西，也没有「前奏只播一次」。
 * [restartAtSeconds] 因此是常量 0.0 —— 写成具名属性是为了让这条口径可断言、可 grep，
 * 而不是散落在各处的魔法 0。
 *
 * ## 时长不随遍数放大
 * `RepeatStream.Length` 是 `source.Length * total_count`（RepeatStream.cs:16），
 * `LoopStream.Length` 是 `long.MaxValue / 32`（LoopStream.cs:12）—— 但
 * `Sound.GetTotalTime` 读的是**未被包装**的原始 `stream` 字段（Sound.cs:51-56），
 * 包装器只活在 `val2` 这个局部变量里（Sound.cs:97-106）。所以 `GETSOUNDORBGMINFO` 的
 * 总时长对 repeat=3 与 repeat=-1 都等于单遍时长，[plays] 不参与时长换算。
 *
 * @param mode 三分类。
 * @param plays 实际遍数：ONCE = 1、REPEAT = repeat 原值、FOREVER = [PLAYS_FOREVER]。
 */
class LoopPlan private constructor(
    val mode: LoopMode,
    val plays: Int,
) {
    /** 是否无限循环（原版 `repeat == -1` ⇒ 套 LoopStream，Sound.cs:98-101）。 */
    val looping: Boolean get() = mode == LoopMode.FOREVER

    /** 是否会自然播完（循环流永不回调 `MixerInputEnded`，LoopStream.cs:31-34）。 */
    val endsNaturally: Boolean get() = mode != LoopMode.FOREVER

    /** 每遍结束后的回卷位置（秒）—— 恒 0，见类头。 */
    val restartAtSeconds: Double get() = 0.0

    override fun toString(): String = "LoopPlan($mode, plays=$plays)"

    companion object {
        /** [FOREVER] 的 [plays] 哨兵值；只用于日志/诊断，不参与任何计数。 */
        const val PLAYS_FOREVER: Int = Int.MAX_VALUE

        private val ONCE_PLAN = LoopPlan(LoopMode.ONCE, 1)

        /** `repeat` → 计划（Sound.cs:97-105 的三分支，逐字）。 */
        fun of(repeat: Int): LoopPlan = when {
            repeat == SoundChannel.LOOP_FOREVER -> LoopPlan(LoopMode.FOREVER, PLAYS_FOREVER)
            repeat > 1 -> LoopPlan(LoopMode.REPEAT, repeat)
            else -> ONCE_PLAN
        }
    }
}
