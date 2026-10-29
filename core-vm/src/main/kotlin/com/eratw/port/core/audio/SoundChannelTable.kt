package com.eratw.port.core.audio

import com.eratw.port.core.host.RuntimeAux

/**
 * 声道表 —— 原版 `GlobalStatic.Sound`（定长 10 的**可空**数组）与 `GlobalStatic.Bgm` 的镜像。
 *
 * 权威来源：
 * - 数组长度 10、Bgm 出厂即建：GlobalStatic.cs:42（`new Sound[10]`）、:44（`Bgm = new Sound()`）
 * - 惰性 `new Sound()`：FunctionMethodCreator.cs:8799-8802（GETSOUNDORBGMINFO）、
 *   :8912-8915（SOUNDCONTROL）、:8996-8998（BGMCONTROL）、
 *   FunctionIdentifier.cs:2632-2635（PLAYSOUND 扫描）、:2666-2669（STOPSOUND）、
 *   :2733-2736（SETSOUNDVOLUME）
 * - **不**惰性建的读端：FunctionMethodCreator.cs:8866（ISPLAYINGSOUND 只看非 null 且在播）、
 *   :8960（ISPLAYINGBGM 同）、:8793-8796（GETSOUNDORBGMINFO 读 Bgm 时不建，null ⇒ 返回 0）
 * - PLAYSOUND 选路：FunctionIdentifier.cs:2625-2645（先查文件、再从头扫、全忙回绕 0）
 * - STOPSOUND / SETSOUNDVOLUME 是**全声道**操作：FunctionIdentifier.cs:2664-2674、:2731-2738
 * - PLAYBGM 固定 repeat = -1（循环）：FunctionIdentifier.cs:2696
 * - STOPBGM / SETBGMVOLUME 直接解引用 Bgm、不判空：FunctionIdentifier.cs:2716、:2753
 *   （Bgm 为 null 时原版是 NullReferenceException；PLAYBGM 的那条被裸 catch 转成 CodeEE，
 *   STOPBGM / SETBGMVOLUME 没有 try，是硬崩）
 * - 停机清场：Program.cs:97-103 逐个 close；`GlobalStatic.Reset`（GlobalStatic.cs:46-74）
 *   会把已建声道 stop 后置 null、Bgm stop 后置 null
 *
 * TODO(verify)：反编译目录内 `GlobalStatic.Reset()` 没有任何调用点（grep `GlobalStatic.Reset`
 * 零命中），所以「Bgm == null」在 PC 侧实际不可达，只有本模型的 [reset] 会造出该状态。
 * 卡点：需要 LazyLoading / EvilMask 补丁侧的调用证据才能判定 STOPBGM 的 NRE 分支是否要复刻。
 *
 * 本波不接入任何宿主缺省（基准 SOUND 族是 MISSING 行），该类只作为 M5 真实后端的对齐目标；
 * 一个 VM 一个实例（禁静态可变全局），接线时用 [soundChannelTable] 挂到 `store.aux`。
 */
class SoundChannelTable(
    /** 原版 `GlobalStatic.Sound.Length`，恒 10（GlobalStatic.cs:42）。 */
    val channelCount: Int = DEFAULT_CHANNEL_COUNT,
    /** 混音器侧状态；各声道的 play/stop 都落到这里。 */
    val mixer: AudioMixer = AudioMixer(),
    initialBgm: SoundChannel? = SoundChannel(mixer),
) {
    private val slots = arrayOfNulls<SoundChannel>(channelCount)

    /**
     * 原版 `GlobalStatic.Bgm`。出厂非空（GlobalStatic.cs:44），只有 [reset] 会置空
     * （:69-73）——函数层的 `bgmInfo() == null` 分支因此只在 reset 后可达。
     */
    var bgm: SoundChannel? = initialBgm
        private set

    /** 声道快照（顺序 = 下标）；null 元素 = 该声道从未被访问（GlobalStatic.cs:42 的数组语义）。 */
    fun channels(): List<SoundChannel?> = slots.toList()

    /** 只查不建，对应原版 `GlobalStatic.Sound[num]` 的直接读取。越界返回 null。 */
    fun channelAt(channel: Int): SoundChannel? = slots.getOrNull(channel)

    /**
     * 查到就返回、为空就地 `new Sound()` 并写下槽位 —— 原版六个调用点共用的惰性建道语义
     * （行号见类头）。越界返回 null（原版是 IndexOutOfRange，移植侧按「无该声道」处理）。
     */
    fun channelAtOrCreate(channel: Int): SoundChannel? {
        val existing = slots.getOrNull(channel)
        if (existing != null) return existing
        if (channel < 0 || channel >= channelCount) return null
        return SoundChannel(mixer).also { slots[channel] = it }
    }

    /** Bgm 惰性建（FunctionMethodCreator.cs:8996-8998）。 */
    fun bgmOrCreate(): SoundChannel {
        return bgm ?: SoundChannel(mixer).also { bgm = it }
    }

    // ------------------------------------------------------------------
    // 语句层六条（PLAYSOUND / STOPSOUND / PLAYBGM / STOPBGM / SETSOUNDVOLUME / SETBGMVOLUME）
    // ------------------------------------------------------------------

    /**
     * PLAYSOUND（FunctionIdentifier.cs:2616-2651）。
     *
     * `repeat` **原样透传**给 [SoundChannel.play]：本方法是 `Sound.play(filename, repeat)`
     * （Sound.cs:64）的镜像，而原版对 repeat 只有一条分类规则（Sound.cs:97-105）——
     * `-1` 套 LoopStream（无限循环）、`> 1` 套 RepeatStream、**其余一律播一遍**（含 0 与负数）。
     * 「夹到至少 1」不是这里的事，理由见下面那条官方口径。
     *
     * 官方口径（必须逐字照抄的运算**顺序**，FunctionIdentifier.cs:2621）：
     * `int repeat = (int)((spHtmlPrint.Opt == null) ? 1 : Math.Max(实参, 1L));`
     * —— `Math.Max` 在 **long 域**做，`(int)` 截断在**它之后**（工程未开
     * CheckForOverflowUnderflow ⇒ unchecked 取低 32 位）。于是所有
     * `实参 ≡ 0xFFFFFFFF (mod 2^32)` 的写法（`4294967295`、`8589934591` …）先被 Max 放行、
     * 再被截成 **-1** ⇒ 原版进 Sound.cs:98-101 的 LoopStream 分支 ⇒ **这条音效无限循环**。
     * 表侧再夹一次 `maxOf(repeat, 1)` 就会把它吃成播一遍，所以这里不夹
     * （同族截断已在 fn/AudioMethods.kt 的 GETSOUNDORBGMINFO 上按同一条口径复刻：
     * `4294967295` 截成 -1 ⇒ 读 BGM，FunctionMethodCreator.cs:8791）。
     *
     * @param repeat 原版 `Sound.play` 的 repeat：-1 = 无限循环，>1 = 重复若干遍，其余播一遍。
     *               语句层必须按 :2621 的**先 Max(实参, 1L) 后 (int)** 顺序将实参折算成 Int 传进来，
     *               PLAYBGM 侧固定传 -1（:2696，见 [playBgm]）。
     * @return 使用的声道号；[NOT_PLAYED] 表示没播上（文件不存在，或声道表长度为 0）。
     */
    fun playSound(name: String, repeat: Int, source: SoundSourceResolver): Int {
        // spec: :2625-2628 —— 文件不存在直接 return，不建声道、不改状态
        val decoder = source.resolve(name) ?: return NOT_PLAYED
        val channel = selectFreeChannel() ?: return NOT_PLAYED
        // spec: Sound.cs:64 + :97-105 —— repeat 逐值透传，表侧不做 Max 夹取（夹法属 :2621）
        channel.play(decoder, repeat)
        return slots.indexOf(channel)
    }

    /**
     * PLAYSOUND 的选路（FunctionIdentifier.cs:2629-2644）。
     *
     * 逐条照抄：从 0 起扫描，**沿途把访问到的空槽都 new 出来**（:2632-2635）；第一个
     * 「不在播」的槽胜出（:2636 用的是 `isPlaying()` ⇒ 暂停中的槽算空闲，会被抢占）；
     * 全部在播时 `i` 落到 Length，回绕成 0 抢占第一个（:2641-2644）。
     *
     * 表长为 0 时返回 null：原版数组定长 10（GlobalStatic.cs:42）走不到这一步，
     * 真走到也会因为回绕后取 `Sound[0]` 而 IndexOutOfRange —— 移植侧按「无声道可用」静默处理，
     * 该差异已写进交付报告。
     */
    private fun selectFreeChannel(): SoundChannel? {
        var index = 0
        while (index < channelCount) {
            val channel = channelAtOrCreate(index) ?: return null
            if (!channel.isPlaying()) return channel
            index++
        }
        return channelAtOrCreate(0)
    }

    /**
     * STOPSOUND（FunctionIdentifier.cs:2662-2675）。
     *
     * 原版先把**所有**槽位 `new` 出来，再只停「正在播」的那些，所以本调用会把 10 个声道
     * 全部实体化（可观测面：之后的 `GlobalStatic.Sound[i] != null` 判定）。
     *
     * @return 被停掉的声道数。
     */
    fun stopSounds(): Int {
        var stopped = 0
        for (i in 0 until channelCount) {
            val channel = channelAtOrCreate(i) ?: continue
            if (channel.isPlaying()) {
                channel.stop()
                stopped++
            }
        }
        return stopped
    }

    /**
     * PLAYBGM（FunctionIdentifier.cs:2686-2703）：固定 repeat = -1（循环，:2696）。
     *
     * @return false = 文件不存在而静默不播（:2694-2697）。
     */
    fun playBgm(name: String, source: SoundSourceResolver): Boolean {
        val decoder = source.resolve(name) ?: return false
        bgmOrCreate().play(decoder, SoundChannel.LOOP_FOREVER)
        return true
    }

    /**
     * STOPBGM（FunctionIdentifier.cs:2714-2717）。
     *
     * @return false = Bgm 为 null；原版此处直接 NRE（无 try 包裹），M5 接线时由 sink
     *         决定把它呈现成什么（不许静默当成功）。
     */
    fun stopBgm(): Boolean {
        val current = bgm ?: return false
        current.stop()
        return true
    }

    /**
     * SETSOUNDVOLUME（FunctionIdentifier.cs:2728-2739）：对**每个**声道分别 setVolume，
     * 沿途把空槽都建出来。原版没有「全局音效音量」这个概念。
     */
    fun setSoundVolume(volume: Int) {
        for (i in 0 until channelCount) {
            channelAtOrCreate(i)?.setVolume(volume)
        }
    }

    /**
     * SETBGMVOLUME（FunctionIdentifier.cs:2750-2754）：直接解引用 Bgm、不判空（:2753）。
     *
     * @return false = Bgm 为 null（原版 NRE，且不套 try ⇒ 硬崩）。
     */
    fun setBgmVolume(volume: Int): Boolean {
        val current = bgm ?: return false
        current.setVolume(volume)
        return true
    }

    // ------------------------------------------------------------------
    // 函数层三条 + 两条查询的通道侧口径
    // ------------------------------------------------------------------

    /** GETSOUNDORBGMINFO 的声道侧：合法下标惰性建，越界 null（FunctionMethodCreator.cs:8797-8804）。 */
    fun soundInfoChannel(channel: Int): SoundChannel? = channelAtOrCreate(channel)

    /** GETSOUNDORBGMINFO 的 BGM 侧：**不**惰性建（FunctionMethodCreator.cs:8793-8796）。 */
    fun bgmInfoChannel(): SoundChannel? = bgm

    /**
     * SOUNDCONTROL 的声道侧：越界 null；合法则惰性建。
     * 原版在建道**之后**才判操作码（FunctionMethodCreator.cs:8908-8915 与 :8916-8945 的顺序），
     * 所以非法操作码也会留下一个实体化声道。
     */
    fun controlSoundChannel(channel: Int): SoundChannel? = channelAtOrCreate(channel)

    /** BGMCONTROL 的 BGM 侧：无条件惰性建（FunctionMethodCreator.cs:8996-8998）。 */
    fun controlBgmChannel(): SoundChannel = bgmOrCreate()

    /** ISPLAYINGSOUND：只认「已建 且 在播」，不建道（FunctionMethodCreator.cs:8866-8870）。 */
    fun isPlayingSound(channel: Int): Boolean = channelAt(channel)?.isPlaying() == true

    /** ISPLAYINGBGM：只认「已建 且 在播」，不建道（FunctionMethodCreator.cs:8960-8964）。 */
    fun isPlayingBgm(): Boolean = bgm?.isPlaying() == true

    /**
     * 当前在播的声道号（升序）。
     *
     * 对应原版 ISPLAYINGSOUND 的**零参扫描**分支（FunctionMethodCreator.cs:8853-8865）。
     * 那条分支自带官方 bug：循环条件比的是 `num`（从不递增）、体内只 `num2++`
     * （:8856-8863），真走到就是死循环后 IndexOutOfRange；因 `OmitStart = 0` 让
     * `arguments[0]` 恒非 null 而永不可达（:8835-8842，函数层的 arity 检查同样挡住零参形态，
     * 见 HostMethods.kt 的 ISPLAYINGSOUND 注释）。本方法只把「往后找第一个在播声道」
     * 这个意图留在模型里，供测试与 M5 调试面板使用。
     */
    fun playingSoundChannels(): List<Int> =
        slots.mapIndexedNotNull { index, channel -> if (channel?.isPlaying() == true) index else null }

    // ------------------------------------------------------------------
    // 清场
    // ------------------------------------------------------------------

    /**
     * 退出清场（Program.cs:97-103）：Bgm 与所有已建声道逐个 close。
     * 不置 null（原版退出时数组仍持有对象）。
     */
    fun closeAll() {
        bgm?.close()
        for (channel in slots) channel?.close()
    }

    /**
     * 对应 `GlobalStatic.Reset()`（GlobalStatic.cs:46-74）：已建声道 stop 后**置 null**、
     * Bgm stop 后置 null。只停已建的，不会把空槽建出来（与 [stopSounds] 的关键差别）。
     */
    fun reset() {
        for (i in 0 until channelCount) {
            val channel = slots[i] ?: continue
            channel.stop()
            slots[i] = null
        }
        bgm?.stop()
        bgm = null
    }

    companion object {
        /** 原版 `GlobalStatic.Sound` 的固定长度（GlobalStatic.cs:42）。 */
        const val DEFAULT_CHANNEL_COUNT = 10

        /** [playSound] 的「没播上」返回值（原版是静默 return，没有返回值）。 */
        const val NOT_PLAYED = -1
    }
}

/** 每 VM 一份声道表的槽名（AGENTS.md §2.4：状态挂 `store.aux`，不用静态字段）。 */
const val AUDIO_CHANNEL_SLOT = "audio.channels"

/**
 * 取（或首次创建）挂在 [aux] 上的 [SoundChannelTable]。
 *
 * 用法与 RuntimeAux.kt:13-17 的约定一致：槽名以能力家族为前缀。BGM 与音效共用同一张表，
 * 接线时 `BuiltinHost` 的 `soundChannels` / `isPlayingSound` / `isPlayingBgm` / `audio`
 * 四项**必须同源**（见 com.eratw.port.core.fn.Builtins.kt 的 sound 分支 KDoc）。
 */
fun soundChannelTable(aux: RuntimeAux, channelCount: Int = SoundChannelTable.DEFAULT_CHANNEL_COUNT): SoundChannelTable =
    aux.slot(AUDIO_CHANNEL_SLOT) { SoundChannelTable(channelCount) }
