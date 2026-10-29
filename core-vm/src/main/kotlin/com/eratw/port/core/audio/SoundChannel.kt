package com.eratw.port.core.audio

/**
 * 解码流的时间基 —— 原版 `Sound.stream`（`WaveStream`）里被状态机读到的那部分。
 *
 * 原版两个时间读数都是**字节**除法：除以 `SampleRate * Channels * 4`（float 采样 4 字节，
 * Sound.cs:37、:51），除数为 0 时恒返回 0.0（Sound.cs:38-41、:52-55）；`stream` 为 null
 * 时同样返回 0.0（Sound.cs:33-36、:47-50）。本接口把口径收敛成「秒」，实现方必须复刻：
 * - 畸形 WaveFormat（rate 或 channels 为 0）时 [totalSeconds] / [positionSeconds] 返回 0.0；
 * - [seekTo] 的入参已由调用方夹到 `0..totalSeconds`（原版 `Seek` 的 Clamp，Sound.cs:208-221）。
 *
 * 注意原版 `LoopStream.Position` 是**代理**到内层流的（LoopStream.cs:14-24），而
 * `Sound.GetTotalTime` 读的是未被包装的原始流字段（Sound.cs:51-56）——所以 repeat（循环/重复）
 * **不改变**上报的总时长。真实后端不要把 Loop/Repeat 包装器接到本接口上。
 */
interface ChannelDecoder {
    /** 解码流总时长（秒）——原版 `((Stream)stream).Length / (rate*channels*4)`。 */
    val totalSeconds: Double

    /** 当前播放位置（秒）——原版 `((Stream)stream).Position / (rate*channels*4)`。 */
    val positionSeconds: Double

    /** 定位（秒）。对应原版 `Seek` 里给 `stream.Position` 赋值那一步。 */
    fun seekTo(seconds: Double)

    /** 释放解码流。对应原版 `stream.Dispose()`（Sound.cs:160）。 */
    fun release() {}

    /**
     * 播放钩子 —— 把 [SoundChannel] 的状态迁移下发给平台播放器。
     *
     * 全部带**默认空实现**：纯模型与假解码器（`FakeChannelDecoder`）不需要实现任何一条，
     * 既有可见状态读数（playing / paused / volume / speed / 两个时间读数）与钩子是否存在无关。
     * 真实后端（Android 的 MediaPlayer）用它们把同一组动作映射到平台播放器；原版把这些动作
     * 直接作用在 NAudio 采样链上，没有独立的「后端」概念（Sound.cs:78-118 建链、
     * :121-136 暂停、:138-149 继续、:151-163 停止、:180-187 音量、:194-201 变速、
     * :242-249 音高、:97-105 循环/重复）。
     *
     * 下发时机与顺序见 [SoundChannel] 各方法的 KDoc；钩子实现**不得**反过来改声道状态，
     * 平台侧的状态异常由实现自己吞掉或上抛（后者最终落到语句层的裸 catch → CodeEE，
     * FunctionIdentifier.cs:2647-2650、:2699-2702）。
     */
    fun onStarted() {}

    /** 暂停已启动的播放（原版 `paused = true` + 摘混音器，Sound.cs:133-134）。 */
    fun onPaused() {}

    /** 从暂停继续（原版 `paused = false` + 挂混音器，Sound.cs:142-147）。 */
    fun onResumed() {}

    /** 停止播放。在 [release] 之前下发（原版 Dispose 前没有单独动作，SoundMixer.cs:79-83）。 */
    fun onStopped() {}

    /**
     * 线性音量，0.0..1.0 —— 原版 `volumeProvider.Volume = volume`（Sound.cs:183-186），
     * 入参已由 [SoundChannel.setVolume] 夹到 0..100 再除 100（Sound.cs:182）。
     */
    fun onVolume(linear: Float) {}

    /** 变速倍率，已夹到 0.1..10（Sound.cs:198-199）。 */
    fun onSpeed(rate: Float) {}

    /** 音高保留开关（Sound.cs:242-248）；后端不支持时按 no-op 处理。 */
    fun onPreservePitch(preserve: Boolean) {}

    /**
     * 播放次数：-1 = 无限循环，其余按原值（[SoundChannel.play] 的 `repeat`）。
     * 原版在开流之后按它套 LoopStream / RepeatStream（Sound.cs:97-105），
     * PLAYBGM 固定传 -1（FunctionIdentifier.cs:2696）。
     */
    fun onRepeat(repeat: Int) {}

    /**
     * 自然播完的**反向通道** —— 原版 `MixingSampleProvider.MixerInputEnded`
     * （SoundMixer.cs:31 的 `+= SoundEnded`、:39-42 的 `Playing = false`；混音器随即把读完的输入摘掉）。
     *
     * 平台解码器在**非循环**流读完最后一块数据后调用监听器；[SoundChannel.play] 在开播前挂一条
     * 转发到 [AudioMixer.onInputEnded] 的监听器，[SoundChannel.stop] 先摘掉它再停止/释放解码流。
     * **默认空实现**：纯模型、`FakeChannelDecoder` 以及只实现时间基的测试装置都不用改。
     *
     * 实现约定（`MediaPlayerChannelDecoder` 逐条落实，KDoc 里带 Android API 依据）：
     * - `repeat == -1` 的循环流永不回调（原版 LoopStream.HasData 恒 true，LoopStream.cs:31-34）；
     * - 同一条解码流最多回调一次；[release] / [onStopped] 之后不得再回调（幂等）；
     * - 传 null = 摘掉监听器。
     *
     * 回调线程不保证是 VM 线程（Android 侧由 MediaPlayer 的完成监听器送达），因此
     * [AudioMixer] 的挂载集合按同步访问实现，见其类头。
     */
    fun setNaturalEndListener(listener: (() -> Unit)?) {}
}

/**
 * 资源名 → 解码流。对应原版 `Path.GetFullPath(".\\sound\\" + name)` + `File.Exists` + 建解码器
 * （FunctionIdentifier.cs:2622-2628、:2691-2696 与 Sound.cs:83-96）。
 *
 * 返回 null = 文件不存在 ⇒ 原版**静默不播**（不报错、不改任何通道状态）。
 * 解码失败在原版是 `catch` 后转 CodeEE（FunctionIdentifier.cs:2647-2650、:2699-2702，
 * 文案 `Lang.Error.ImcompatibleSoundFile` = `非対応のサウンドファイルが指定されました`，
 * Lang.cs:3994）；本模型把该判定留在调用方（sink / 宿主），理由是原版的打开发生在
 * 选中声道**之后**，移植侧提前到之前才不会误停正在播的声道——差异已在交付报告登记。
 */
fun interface SoundSourceResolver {
    fun resolve(name: String): ChannelDecoder?
}

/**
 * 混音器侧状态 —— 原版静态类 `SoundMixer` 的实例化镜像。
 *
 * 原版是进程级静态（SoundMixer.cs:9-21），本仓禁静态可变全局（AGENTS.md §2.4），
 * 故「已初始化」标志与「挂在混音器上的通道集合」都由实例承载；一个 VM 一个 [AudioMixer]
 * （表侧的每 VM 挂载见 SoundChannelTable.kt 的 [soundChannelTable]）。
 *
 * 线程：真实后端的自然播完回调来自平台播放器（Android 侧是 MediaPlayer 的完成监听器，
 * 主 looper 送达），与驱动 [SoundChannel] 的 VM 线程不是同一条；原版 `MixerInputEnded`
 * 则发生在 NAudio 混音线程（SoundMixer.cs:31、:39-42）。为保持「挂载集合只有一份」，
 * 本类的集合操作统一加锁 —— 公开方法语义不变，只是把并发访问收敛。
 *
 * 焦点旁路（[onFocusEvent]，**原版没有**，见 [AudioFocusEvent]）：只借本类的挂载集合做
 * 「哪些声道正在出声」的取样，动作全部落在 [SoundChannel.suspendForFocus] 与
 * [SoundChannel.resumeFromFocus] 上，不写 `Playing` / `paused`，也不摘挂载集合。
 */
class AudioMixer {
    /** 对应 `SoundMixer.Initialized`（SoundMixer.cs:21）。原版只有 Initialize、没有反初始化。 */
    var initialized: Boolean = false
        private set

    private val inputs = LinkedHashSet<SoundChannel>()

    /**
     * 当前焦点挂起作用域；null = 没有挂起（对应「系统焦点一直在本进程」）。
     *
     * 这个标志是 [onFocusEvent] 的幂等闸门：重复 `LOSS` / `LOSS_TRANSIENT` 不会二次下发
     * `onPaused`，没挂起时的 `GAIN` 也不会凭空 `onResumed` —— 平台层（Activity 生命周期与
     * AudioManager 回调）因此可以两路都转发，不会双触发。
     */
    var focusScope: AudioFocusScope? = null
        private set

    /** 幂等初始化（SoundMixer.cs:23-37 的 `if (!initialized)` 守卫；Sound.cs:78-81 是唯一触发点）。 */
    fun initialize() {
        initialized = true
    }

    /**
     * 焦点事件 → 声道传输（[AudioFocusEvent] 的三路映射，**唯一实现处**）。
     *
     * - `LOSS`：挂起全部在播声道，作用域升级/落定为 [AudioFocusScope.PERMANENT]；
     *   已经是 `PERMANENT` 时返回 0（重复事件幂等）。
     * - `LOSS_TRANSIENT`：同上，作用域 [AudioFocusScope.TRANSIENT]；但**不**把 `PERMANENT`
     *   降级（长期失焦还没还回来，不能让一条瞬态事件改写收尾规则）。
     * - `GAIN`：按挂起时的作用域逐个收尾（[SoundChannel.resumeFromFocus]）；未挂起时返回 0。
     *
     * 挂起动作是 [SoundChannel.suspendForFocus]，只动平台传输，不动 `Playing` / `paused`
     * ⇒ ISPLAYINGSOUND / ISPLAYINGBGM 与 PLAYSOUND 选道读数不受影响（见 [AudioFocusEvent]）。
     *
     * @return 本次真正改变了传输状态的声道数（0 = 幂等空转，调用方可据此跳过日志）。
     */
    @Synchronized
    fun onFocusEvent(event: AudioFocusEvent): Int {
        when (event) {
            AudioFocusEvent.LOSS -> {
                if (focusScope == AudioFocusScope.PERMANENT) return 0
                focusScope = AudioFocusScope.PERMANENT
                return suspendInputs()
            }
            AudioFocusEvent.LOSS_TRANSIENT -> {
                if (focusScope != null) return 0
                focusScope = AudioFocusScope.TRANSIENT
                return suspendInputs()
            }
            AudioFocusEvent.GAIN -> {
                val scope = focusScope ?: return 0
                focusScope = null
                return resumeInputs(scope)
            }
        }
    }

    /** 逐个挂起当前挂在混音器上的声道（[inputs] 的快照，避免回调改动集合）。 */
    private fun suspendInputs(): Int = inputs.toList().count { it.suspendForFocus() }

    /** 逐个按作用域收尾；`resumeFromFocus` 可能把一次性音效 [SoundChannel.stop]，故先取快照。 */
    private fun resumeInputs(scope: AudioFocusScope): Int =
        inputs.toList().count { it.resumeFromFocus(scope) }

    /** SoundMixer.cs:73-77 —— `Playing = true` 并挂进混音输入。不清 `paused`（原版不清）。 */
    @Synchronized
    fun play(channel: SoundChannel) {
        channel.playing = true
        inputs.add(channel)
        // 焦点挂起期间脚本又起了一条（PLAYSOUND / PLAYBGM）：立刻跟随当前作用域挂起，
        // 否则后台会突然出声。等 GAIN 时与其它声道走同一条恢复路径。
        if (focusScope != null) channel.suspendForFocus()
    }

    /** SoundMixer.cs:79-83 —— `Playing = false` 并摘掉混音输入。 */
    @Synchronized
    fun stop(channel: SoundChannel) {
        channel.playing = false
        inputs.remove(channel)
    }

    /**
     * 某个混音输入读完（原版 `MixerInputEnded` → SoundEnded，SoundMixer.cs:31、:39-42）。
     *
     * 原版回调只置 `Playing = false`；混音器自身会把结束的输入摘掉，故这里一并摘除。
     * 循环流（repeat == -1 → LoopStream）永不触发本回调：`LoopStream.HasData` 恒 true
     * （LoopStream.cs:31-34），所以 [SoundChannel.looping] 的通道不受影响。
     *
     * 两个入口共用本方法：混音器自己读完（原版回调）与平台解码器的自然播完回调
     * （[ChannelDecoder.setNaturalEndListener]）。重复回调幂等：通道已摘时 `remove` 无副作用。
     *
     * 焦点挂起期间读完的声道同样摘除，同时清掉挂起标志（[SoundChannel.stop] 的那套清理走不到
     * 这里）—— 它已经不在 [inputs] 里，`GAIN` 不会再碰它。
     */
    @Synchronized
    fun onInputEnded(channel: SoundChannel) {
        if (channel.looping) return
        channel.playing = false
        inputs.remove(channel)
        channel.dropFocusSuspension()
    }

    /** 当前挂在混音器上的通道（快照，顺序 = 挂载顺序）。 */
    @Synchronized
    fun registeredChannels(): List<SoundChannel> = inputs.toList()

    /** 当前因焦点而挂起的声道（快照）；[focusScope] 非 null 时它就是在等 `GAIN` 的集合。 */
    @Synchronized
    fun focusSuspendedChannels(): List<SoundChannel> = inputs.filter { it.focusSuspended }

    /** 是否已挂在混音器上（对应原版 mixer 的输入集合）。 */
    @Synchronized
    fun isRegistered(channel: SoundChannel): Boolean = inputs.contains(channel)
}

/**
 * 单条声道播放状态机 —— 原版 `MinorShift.Emuera.Runtime.Utils.Sound` 的纯 JVM 镜像。
 *
 * 本波（CONTRACT-M4-D 的 d-audio）**只钉口径、不加行为**：基准里 SOUND 族是 MISSING 行
 * （`pc-golden-root.txt:519-522` 只有 `EXISTSOUND=0` 与 `ISPLAYINGBGM=0 ISPLAYINGSOUND=-1`；
 * `golden-runbook.md:155` 把 SOUNDCONTROL / BGMCONTROL / GETSOUNDORBGMINFO 列为排除项；
 * 采集夹具 `GOLDEN_09_MISC.ERB:41-52` 播的是不存在的文件）。因此本包不接进任何宿主缺省，
 * [com.eratw.port.core.fn.BuiltinHost] 的 `audio` / `soundChannels` 仍按「未接线」呈现，
 * 三个函数继续抛 CodeEE —— 与基准同判，不伪造成「已支持」。
 *
 * 逐条权威来源（反编译原文）：
 * - 字段缺省：Sound.cs:13-27（volume = 1f、paused = false、savedPosition = 0、
 *   preservePitch = true、Playing = false、stream / volumeProvider / varispeedProvider 皆 null）
 * - 时间读数：Sound.cs:31-43（GetCurrentTime）、:45-57（GetTotalTime）
 * - 播放：Sound.cs:64-119（先按需 Initialize，再 stop，再开流，按 repeat 套 Loop/Repeat，
 *   重建变速与音量级，最后挂混音器）
 * - 暂停 / 继续 / 停止 / 关闭：Sound.cs:121-136、:138-149、:151-163、:165-169
 * - 播放中判定：Sound.cs:171-178（`Playing` 且未 `paused`）
 * - 音量：Sound.cs:180-187（夹 0..100 后除 100）、:189-192（乘 100 后向零截断）
 * - 变速：Sound.cs:194-201（夹 0.1..10，且变速链路未建时**整条 no-op**）、
 *   :203-206（变速链路未建时读数回 1.0）
 * - 音高保留：Sound.cs:242-249（字段常驻；链路存在才下发 profile）
 * - 混音器：SoundMixer.cs:73-83、:39-42
 * - 变速率缺省 1f 与同值短路：VarispeedSampleProvider.cs:18、:26-40
 *
 * 刻意复刻的官方怪癖（ADR-005，不许「顺手修好」）：
 * 1. [play] 会**重建**变速链路，于是变速率被抹回 1.0，而 [preservePitch] 字段存活
 *    （Sound.cs:115 用字段建新 profile + VarispeedSampleProvider.cs:18 的 1f 缺省）。
 * 2. 音量与变速率的读数都带 IEEE binary32 往返损失：`SETSOUNDVOLUME 53` 读回 52、
 *    `59` 读回 58（Sound.cs:182 + :191）；`GETSOUNDORBGMINFO` 的 11..13、16、19、29 一类
 *    变速输入各少 1（Sound.cs:198 + :205 与 FunctionMethodCreator.cs:8815、:8824）。
 *    期望表见 SoundChannelTest，数值由独立 IEEE 核对（Python 精确分数 + JDK17 jshell 双算），
 *    不是实现回读。
 * 3. [stop] 只在混音器已初始化时才清 `playing`（Sound.cs:153-156），但**无条件**清 `paused`
 *    并释放解码流；`savedPosition` 不清零（原版没有这一行）。
 * 4. [resume] 只在 `savedPosition > 0` 时才回卷位置（Sound.cs:143-146）：在第 0 秒暂停再继续，
 *    位置由解码流自己决定，不会被拉回 0。
 * 5. [pause] 的守卫看的是 `Playing` 标志本身而不是 `isPlaying()`（Sound.cs:123），
 *    所以「已暂停」的通道再 pause 一次是彻底 no-op（pause 已把 Playing 清掉）。
 *
 * 平台钩子：每次状态迁移都把动作下发给 [ChannelDecoder] 的 `on*` 钩子（默认空实现）。
 * 钩子只描述「平台播放器该做什么」，**不参与**本类的任何可见状态读数 —— 因此
 * `FakeChannelDecoder` 不实现它们时行为与 M4-D 完全一致（既有 SoundChannelTest 全部保持绿）。
 * 下发顺序逐个方法写在 KDoc 里，依据是 Sound.cs 里各步骤的源码顺序。
 *
 * 焦点旁路（[focusSuspended] / [suspendForFocus] / [resumeFromFocus]，**原版没有这一层**）：
 * 只暂停/恢复平台传输，不动 [playing] / [paused]，口径与理由见 [AudioFocusEvent]。
 *
 * 反向通道：[ChannelDecoder.setNaturalEndListener] 把平台播放器的「读完最后一遍」送回本类，
 * 由 [AudioMixer.onInputEnded] 摘通道（SoundMixer.cs:31、:39-42）。M6-A 之前它没有调用方，
 * 播完的音效会一直显示在播，直到下一条 PLAYSOUND / STOPSOUND 抢占。
 */
class SoundChannel(val mixer: AudioMixer = AudioMixer()) {

    /**
     * 原版 `Sound.stream`（Sound.cs:23）。null = 未加载或已 stop ⇒ 两个时间读数恒 0。
     *
     * `@Volatile`：自然播完回调在平台线程上读它做「旧流迟到回调」判定，VM 线程同时会写。
     */
    @Volatile
    var decoder: ChannelDecoder? = null
        private set

    /**
     * 原版 `Sound.Playing`（Sound.cs:27）：只表示「挂在混音器上」，不代表没被暂停。
     * `@Volatile`：平台自然播完回调会把 false 写到 VM 线程可见的地方（ISPLAYINGSOUND 随即读它）。
     */
    @Volatile
    var playing: Boolean = false
        internal set

    /** 原版 `Sound.paused`（Sound.cs:15）。 */
    var paused: Boolean = false
        private set

    /**
     * 原版 repeat == -1 时套上的 LoopStream（Sound.cs:98-101）：决定播完是否自动结束。
     *
     * 由 [loopPlan] 派生 —— `repeat` 的三分类只发生在 [LoopPlan.of] 一处，平台侧
     * `MediaPlayerChannelDecoder.onRepeat` 读同一份计划，避免「模型说循环、平台说不循环」。
     */
    var loopPlan: LoopPlan = LoopPlan.of(1)
        private set

    /** [loopPlan] 的快捷读数（`LoopMode.FOREVER`）。 */
    val looping: Boolean get() = loopPlan.looping

    /** 原版 `Sound.preservePitch`（Sound.cs:21，缺省 true）。SOUNDCONTROL 第 4 参非 0 时关闭。 */
    var preservePitch: Boolean = true
        private set

    /** 原版 `Sound.savedPosition`（Sound.cs:17，字节口径；本模型换算成秒）。 */
    var savedPositionSeconds: Double = 0.0
        private set

    /** 原版 `VarispeedSampleProvider.Reposition()` 的待处理标志（:22、:64-68、:138-141）。 */
    var repositionPending: Boolean = false
        private set

    /** 原版 `Sound.varispeedProvider != null`：只有 [play] 成功过一次才为真（Sound.cs:115）。 */
    var varispeedReady: Boolean = false
        private set

    /** 上一次 [setPreservePitch] 是否真的下发到变速链路（Sound.cs:245-248 的 null 守卫）。 */
    var pitchProfileApplied: Boolean = false
        private set

    /**
     * 焦点挂起标志（**平台强制项，原版无此状态**；口径见 [AudioFocusEvent]）。
     *
     * 为真时平台播放器已被 `onPaused` 暂停，但 [playing] / [paused] 一个字节都没动 ⇒
     * [isPlaying]（Sound.cs:171-178）与 PLAYSOUND 选道（FunctionIdentifier.cs:2636）读数
     * 与 PC 完全一致。脚本之后无论 `stop()` 还是 `pause()` 都会把它清掉（见各自实现）。
     */
    var focusSuspended: Boolean = false
        private set

    /** 挂起时记下的解码位置（秒）；`GAIN` 续播时回卷到这里，**不会**回 0。 */
    private var focusSavedSeconds: Double = 0.0

    private var volumeLinear: Float = 1f
    private var playbackRate: Float = 1f

    /**
     * 原版 `isPlaying()`（Sound.cs:171-178）：`Playing` 为假直接 false，否则取 `!paused`。
     *
     * 这条口径同时决定 ISPLAYINGSOUND / ISPLAYINGBGM 的读数和 PLAYSOUND 的选空闲道判定
     * （FunctionIdentifier.cs:2636）——**已暂停**的声道算空闲，会被下一条 PLAYSOUND 抢占。
     */
    fun isPlaying(): Boolean = playing && !paused

    /** 原版 `GetCurrentTime()`（Sound.cs:31-43）：stream 为 null 恒 0.0。 */
    fun getCurrentTime(): Double = decoder?.positionSeconds ?: 0.0

    /** 原版 `GetTotalTime()`（Sound.cs:45-57）：stream 为 null 恒 0.0；与 repeat 无关。 */
    fun getTotalTime(): Double = decoder?.totalSeconds ?: 0.0

    /**
     * 原版 `play(filename, repeat)`（Sound.cs:64-119）。
     *
     * 顺序照抄：按需初始化混音器（:78-81）→ [stop]（:82，于是 paused 清零、旧流释放）→
     * 挂新流（:83-96）→ 按 repeat 决定循环/重复（:97-105，只影响采样链，不影响时长读数）→
     * 重建变速链（:115，`playbackRate` 回 1f、profile 取 [preservePitch] 字段）→
     * 重建音量级（:116-117，`volume` 字段原样带过去）→ 挂混音器（:118）。
     *
     * 平台钩子按同一条源码顺序下发：旧解码流先 `onStopped` + `release`（由 [stop] 完成），
     * 新流依次 `onRepeat(repeat)` → `onSpeed(1f)` → `onPreservePitch(preservePitch)` →
     * `onVolume(volume)` → `onStarted`。`onStarted` 在 `mixer.play` **之前**：原版这一段
     * 若抛异常（:85-118 任何一步）`Playing` 仍是 false，平台启动失败时本类也就不该自称在播。
     *
     * 反向通道在挂上新流之后、`onStarted` 之前安装（[ChannelDecoder.setNaturalEndListener]）：
     * 极短音频可能在平台启动后立刻播完，监听器必须先就位；它把完成事件交给
     * [AudioMixer.onInputEnded]（SoundMixer.cs:39-42）。
     *
     * @param repeat 原版 repeat：-1 = 循环，>1 = 重复若干次，其余按 1 处理。
     *               PLAYSOUND 侧已在语句层夹到至少 1（FunctionIdentifier.cs:2621），
     *               PLAYBGM 固定传 -1（:2696）。
     */
    fun play(source: ChannelDecoder, repeat: Int) {
        mixer.initialize()
        stop()
        decoder = source
        loopPlan = LoopPlan.of(repeat)
        source.setNaturalEndListener { onDecoderEnded(source) }
        varispeedReady = true
        playbackRate = SPEED_NEUTRAL
        pitchProfileApplied = true
        source.onRepeat(repeat)
        source.onSpeed(playbackRate)
        source.onPreservePitch(preservePitch)
        source.onVolume(volumeLinear)
        source.onStarted()
        mixer.play(this)
    }

    /**
     * 解码流自然播完 → 混音器摘通道（原版 `MixerInputEnded` → `Playing = false`，
     * SoundMixer.cs:31、:39-42；循环流由 [AudioMixer.onInputEnded] 的 `looping` 守卫挡住，
     * LoopStream.cs:31-34）。
     *
     * 同一性守卫：`play` 换流时旧解码流会被 [stop] 摘掉监听器，但平台可能已经把完成事件
     * 投递出去（迟到回调）。只有事件来源仍是当前 [decoder] 才允许动混音器，否则换流后的
     * 新流会被旧事件误摘。
     */
    private fun onDecoderEnded(source: ChannelDecoder) {
        if (decoder !== source) return
        mixer.onInputEnded(this)
    }

    /**
     * 原版 `pause()`（Sound.cs:121-136）：守卫 `Initialized && Playing`，否则整条 no-op。
     * 守卫放行时在模型状态落定之后下发 `onPaused`（原版最后一步是 `SoundMixer.StopSound`，:134）。
     *
     * 焦点挂起期间被脚本暂停：先摘掉焦点挂起标志（平台的暂停动作已经发生，`onPaused` 幂等），
     * 之后由 [paused] 独占传输 —— `GAIN` 不会再替脚本把它拉起来。
     */
    fun pause() {
        focusSuspended = false
        if (!mixer.initialized || !playing) return
        decoder?.let { savedPositionSeconds = it.positionSeconds }
        if (varispeedReady) repositionPending = true
        paused = true
        mixer.stop(this)
        decoder?.onPaused()
    }

    /**
     * 原版 `resume()`（Sound.cs:138-149）：守卫 `Initialized && paused`。
     * 先回卷位置（:143-146）再下发 `onResumed`，最后挂混音器（:147）。
     *
     * `onResumed` 提到挂载之前：原版这两步是同一条 `SoundMixer.PlaySound`（:147），先后不可观测；
     * 放在前面是为了让 [AudioMixer.play] 的焦点自动挂起排在**最后** —— 焦点仍丢失时，
     * 「脚本继续」必须仍以挂起收尾，否则后台会出声。
     */
    fun resume() {
        if (!mixer.initialized || !paused) return
        paused = false
        decoder?.let {
            if (savedPositionSeconds > 0.0) it.seekTo(savedPositionSeconds)
        }
        decoder?.onResumed()
        mixer.play(this)
    }

    /**
     * 焦点挂起：**只暂停平台传输**，不动 [playing] / [paused]（原版没有这一步，见 [AudioFocusEvent]）。
     *
     * 守卫逐条：
     * - 已挂起 ⇒ false（幂等：重复 `LOSS` / `LOSS_TRANSIENT` 不重复下发 `onPaused`）；
     * - 不在播或已被脚本暂停 ⇒ false（脚本暂停的声道本来就不在混音器里，传输也已停，
     *   不该被焦点二次接管）；
     * - 无解码流 ⇒ false。
     *
     * 位置快照取自 [ChannelDecoder.positionSeconds]（原版 `pause()` 存 `stream.Position`，
     * Sound.cs:125-128；`GAIN` 续播时回卷，:143-146）。
     *
     * @return true = 本次真的下发了 `onPaused`。
     */
    internal fun suspendForFocus(): Boolean {
        if (focusSuspended) return false
        if (!playing || paused) return false
        val stream = decoder ?: return false
        focusSavedSeconds = stream.positionSeconds
        focusSuspended = true
        stream.onPaused()
        return true
    }

    /**
     * 焦点挂起状态作废（流已自然读完 / 已被换掉时由 [AudioMixer.onInputEnded] 调用）：
     * 只清标志与位置快照，**不**下发任何平台动作 —— 传输已经不在了。
     */
    internal fun dropFocusSuspension() {
        focusSuspended = false
        focusSavedSeconds = 0.0
    }

    /**
     * 焦点恢复：把挂起期间冻结的传输续上。
     *
     * - 先清 [focusSuspended]（无论后面走哪条分支，挂起状态都算结束）；
     * - 挂起期间被脚本 `stop()` / `pause()` 过的，`playing` / `paused` 已变 ⇒ 什么都不做，
     *   绝不复播（脚本意图优先）；
     * - [AudioFocusScope.PERMANENT] 且非循环 ⇒ [stop]（一次性音效不重播，见作用域 KDoc）；
     * - 其余：位置回卷到挂起点再 `onResumed`。位置为 0 时不回卷 —— 与 [resume] 的既有守卫
     *   同口径（Sound.cs:143-146 的 `savedPosition > 0`）。
     *
     * `onPaused` / `onResumed` 的配对由本方法自己保证：只有 [suspendForFocus] 真的下发过，
     * 这里才可能走到 `onResumed`。
     *
     * @return true = 本次对传输做了动作（续播或停止）。
     */
    internal fun resumeFromFocus(scope: AudioFocusScope): Boolean {
        if (!focusSuspended) return false
        focusSuspended = false
        if (!playing || paused) return false
        val stream = decoder ?: return false
        if (scope == AudioFocusScope.PERMANENT && !looping) {
            stop()
            return true
        }
        if (focusSavedSeconds > 0.0) stream.seekTo(focusSavedSeconds)
        stream.onResumed()
        return true
    }

    /**
     * 原版 `stop()`（Sound.cs:151-163）。见类头怪癖 3。
     * 有解码流时先摘掉反向通道（[ChannelDecoder.setNaturalEndListener] 传 null），再下发
     * `onStopped` 并 [ChannelDecoder.release]（原版只有 Dispose，:158-161）——stop 之后到达的
     * 完成回调因此不会再动混音器（幂等）。
     *
     * 焦点挂起随之作废（[focusSuspended] 清、位置读数丢）：`stop()` 之后没有可恢复的对象，
     * 迟到的 `GAIN` 不能再把它拉起来。
     */
    fun stop() {
        focusSuspended = false
        focusSavedSeconds = 0.0
        if (mixer.initialized) mixer.stop(this)
        paused = false
        decoder?.let {
            it.setNaturalEndListener(null)
            it.onStopped()
            it.release()
        }
        decoder = null
    }

    /**
     * 原版 `close()`（Sound.cs:165-169）= [stop] + `volumeProvider = null`。
     *
     * 后者在模型里不需要独立状态：两个时间读数的守卫是「stream 或 volumeProvider 为 null
     * 就报 0.0」（Sound.cs:33、:47），而 [stop] 已经把 stream 置 null，读数必然为 0。
     * 退出时原版对 Bgm 与所有已建声道逐个 close（Program.cs:97-103）。
     */
    fun close() {
        stop()
    }

    /**
     * 原版 `setVolume(int)`（Sound.cs:180-187）：先 `Math.Clamp(volume, 0, 100)` 再除 100，
     * 存成 binary32。音量**不是**全局量，逐声道各存一份（FunctionIdentifier.cs:2731-2738）。
     *
     * 下发口径：字段无条件写入（:182），`onVolume` 只在有解码流时下发 —— 对应原版
     * `volumeProvider != null` 的 null 守卫（:183）。本模型里 decoder == null 是它的代理：
     * 未 play / 已 stop / 已 close。原版 stop 后 volumeProvider 仍非 null（只 dispose 流，
     * :158-162），对已死链路的赋值不可观测，故该差异不上报。
     */
    fun setVolume(volume: Int) {
        volumeLinear = volume.coerceIn(VOLUME_MIN, VOLUME_MAX).toFloat() / VOLUME_SCALE
        decoder?.onVolume(volumeLinear)
    }

    /**
     * 原版 `getVolume()`（Sound.cs:189-192）：`volume * 100f` 后向零截断。
     *
     * binary32 往返使 53 读回 52、59 读回 58，其余 0..100 恒等（SoundChannelTest 全表断言）。
     */
    fun getVolume(): Int = (volumeLinear * VOLUME_SCALE).toInt()

    /**
     * 原版 `setSpeed(float)`（Sound.cs:194-201）：变速链路不存在时**整条 no-op**，
     * 存在时夹到 0.1..10；同值短路（VarispeedSampleProvider.cs:32-39）只影响 DSP 侧下发，
     * 对本模型的可见状态没有差别，故不复刻短路。
     *
     * 下发口径：只有 `varispeedReady`（原版 `varispeedProvider != null`，:196）且当前有
     * 解码流时才 `onSpeed`；夹后的值与原版赋给 `PlaybackRate` 的值一致（:198-199）。
     */
    fun setSpeed(speed: Float) {
        if (!varispeedReady) return
        playbackRate = speed.coerceIn(SPEED_MIN, SPEED_MAX)
        decoder?.onSpeed(playbackRate)
    }

    /**
     * 原版 `getSpeed()`（Sound.cs:203-206）：链路未建回 1.0。
     * 函数层再乘 100 截断（FunctionMethodCreator.cs:8815、:8824），见类头怪癖 2。
     */
    fun getSpeed(): Double = if (varispeedReady) playbackRate.toDouble() else SPEED_NEUTRAL.toDouble()

    /**
     * 原版 `SetPreservePitch(bool)`（Sound.cs:242-249）：字段永远写，profile 只在链路存在时下发。
     * 对应 SOUNDCONTROL / BGMCONTROL 的变速分支第 4 / 第 3 参（FunctionMethodCreator.cs:8941、:9025）。
     *
     * 下发口径：`pitchProfileApplied` 的判定不变（只看 `varispeedReady`，:245）；
     * [ChannelDecoder.onPreservePitch] 额外要求当前有解码流（没有流就没有可改 profile 的对象）。
     */
    fun setPreservePitch(preserve: Boolean) {
        preservePitch = preserve
        pitchProfileApplied = varispeedReady
        if (varispeedReady) decoder?.onPreservePitch(preserve)
    }

    companion object {
        /** 原版 repeat == -1 表示无限循环（Sound.cs:98-101）。 */
        const val LOOP_FOREVER = -1

        /** `Math.Clamp(volume, 0, 100)` 的上下限（Sound.cs:182）。 */
        const val VOLUME_MIN = 0
        const val VOLUME_MAX = 100

        /** 出厂音量：`volume = 1f` ⇒ getVolume() = 100（Sound.cs:13、:191）。 */
        const val DEFAULT_VOLUME = 100

        /** `Math.Clamp(speed, 0.1f, 10f)`（Sound.cs:198）。 */
        const val SPEED_MIN = 0.1f
        const val SPEED_MAX = 10f

        /** 变速率缺省 1.0（Sound.cs:205 + VarispeedSampleProvider.cs:18）。 */
        const val DEFAULT_SPEED = 1.0

        private const val VOLUME_SCALE = 100f
        private const val SPEED_NEUTRAL = 1f
    }
}
