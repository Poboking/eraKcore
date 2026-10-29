package com.eratw.port.core.audio

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.AudioChannelMaterializer
import com.eratw.port.core.fn.AudioHost
import com.eratw.port.core.fn.AudioOp
import com.eratw.port.core.fn.SoundInfo
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.host.SoundSink

/**
 * 假解码流：只有时长与位置两个数，不碰任何真实音频。
 *
 * 用途是把 [SoundChannel] 的时间读数（原版从 `WaveStream` 的字节位置换算，
 * Sound.cs:31-57）变成可断言的输入。[seekCount] / [lastSeekSeconds] 用来验证
 * `resume()` 的「savedPosition > 0 才回卷」守卫（Sound.cs:143-146），
 * [released] 用来验证 `stop()` 真的释放了流（Sound.cs:158-161）。
 * [finishNaturally] 模拟平台自然播完，把 [ChannelDecoder.setNaturalEndListener] 的反向通道
 * 变成可触发的测试输入（对应 SoundMixer.cs:31、:39-42 的 `MixerInputEnded`）。
 */
class FakeChannelDecoder(
    private val total: Double,
    startPosition: Double = 0.0,
) : ChannelDecoder {
    override val totalSeconds: Double get() = total

    private var pos: Double = startPosition

    override val positionSeconds: Double get() = pos

    var seekCount: Int = 0
        private set

    var lastSeekSeconds: Double? = null
        private set

    var released: Boolean = false
        private set

    /** 自然播完的反向通道（[ChannelDecoder.setNaturalEndListener]）；null = 已摘。 */
    private var naturalEndListener: (() -> Unit)? = null

    /** 监听器是否还挂着（[SoundChannel.stop] 之后应为 false）。 */
    val hasNaturalEndListener: Boolean get() = naturalEndListener != null

    override fun seekTo(seconds: Double) {
        seekCount++
        lastSeekSeconds = seconds
        pos = seconds
    }

    /** 测试侧推进播放位置（真实后端由混音器读取推进）。 */
    fun advance(seconds: Double) {
        pos += seconds
    }

    override fun setNaturalEndListener(listener: (() -> Unit)?) {
        naturalEndListener = listener
    }

    /**
     * 测试侧模拟平台自然播完（非循环流读完最后一遍）。
     * 重复调用模拟「平台重复/迟到回调」，用于钉住混音器侧的幂等。
     */
    fun finishNaturally() {
        naturalEndListener?.invoke()
    }

    override fun release() {
        released = true
        naturalEndListener = null
    }
}

/**
 * 内存假音源：`名字 → 时长（秒）`。名字不在表里就返回 null，对应原版
 * `File.Exists == false` 时 PLAYSOUND / PLAYBGM 静默不播
 * （FunctionIdentifier.cs:2625-2628、:2694-2697）。
 *
 * 每次 [resolve] 都**新建**一个 [FakeChannelDecoder]：原版每次 play 都重新打开解码器
 * （Sound.cs:83-96），并且 play 开头先 stop 旧流（:82），所以两条播放从不共用流对象；
 * [opened] 因此可以当「开过几次文件」的计数器用。
 */
class FakeSoundSource(initial: Map<String, Double> = emptyMap()) : SoundSourceResolver {
    private val durations = LinkedHashMap(initial)
    private val openedStreams = mutableListOf<FakeChannelDecoder>()

    /** 迄今打开过的假流（按顺序）；末元素 = 最近一次成功 resolve 建立的流。 */
    val opened: List<FakeChannelDecoder> get() = openedStreams.toList()

    fun put(name: String, totalSeconds: Double) {
        durations[name] = totalSeconds
    }

    fun remove(name: String) {
        durations.remove(name)
    }

    fun names(): Set<String> = durations.keys.toSet()

    override fun resolve(name: String): ChannelDecoder? =
        durations[name]?.let { FakeChannelDecoder(it).also { stream -> openedStreams += stream } }
}

/**
 * 生产用表驱动 sink：语句层六条（PLAYSOUND / STOPSOUND / PLAYBGM / STOPBGM /
 * SETSOUNDVOLUME / SETBGMVOLUME）→ [SoundChannelTable]，资源解析走注入的
 * [SoundSourceResolver]。选道、暂停算空闲、repeat、全声道广播这些语义全在
 * [SoundChannelTable]（逐条带 C# 行号）；本类只做语句 → 表的映射。
 * [FakeSoundSink] 委托本类并额外记事件流水，二者不会分叉。
 *
 * 表的取法：
 * - 推荐 `TableSoundSink(store.aux, resolver)`：每次调用现读 [soundChannelTable]，与内建
 *   宿主四项（soundChannels / isPlayingSound / isPlayingBgm / audio）共用**同一张**
 *   store.aux 表；RESTART / 读档清空 aux 之后自动跟到新表，不缓存旧表。
 * - `TableSoundSink(table, resolver)`：固定单表接线（单测 / 宿主自持一张表时用）；调用方
 *   自负与内建宿主同源的接线责任。
 *
 * 逐条语义（原文出处）：
 * - PLAYSOUND：repeat = Math.Max(实参, 1)（FunctionIdentifier.cs:2621）；文件不存在直接
 *   return（:2625-2628）；从 0 起找第一个 `!isPlaying()` 的声道、沿途把空槽建出来
 *   （:2629-2644；暂停算空闲），全忙回绕 0（:2641-2644）。
 * - STOPSOUND：全声道补 null 后只停 `isPlaying()` 的（:2664-2674）。
 * - PLAYBGM：固定 repeat = -1（循环，:2696）；文件不存在静默（:2694-2697）。
 * - 解码失败：PLAYSOUND / PLAYBGM 都把「建流 + 播放」整段包在裸 catch 里、任何异常转
 *   CodeEE `非対応のサウンドファイルが指定されました`（:2647-2650、:2699-2702，
 *   Lang.cs:3994）。[SoundSourceResolver] 约定「null = 文件不存在（静默）、抛异常 =
 *   解码失败」，转换点因此在本类。
 * - STOPBGM：原版直接解引用 Bgm（:2716，无判空、无 try）⇒ Bgm 为 null 是
 *   NullReferenceException 硬崩；本类改抛 [CodeEE] 显式表态，不许静默当成功。
 *   该分支只在本仓 [SoundChannelTable.reset] 之后可达（GlobalStatic.Reset 在反编译目录
 *   没有调用点，见 SoundChannelTable.kt 的 TODO(verify)）。
 * - SETSOUNDVOLUME：逐声道 setVolume、沿途补 null（:2731-2738）；没有全局音效音量。
 * - SETBGMVOLUME：只写 Bgm（:2753）；null 时同 STOPBGM 抛 [CodeEE]。
 *
 * 缺省仍是 [com.eratw.port.core.host.NoopSoundSink]（基准 SOUND-MISSING 读数依赖它），
 * 本类只在宿主显式注入时生效（`ProcessRuntime` 的 soundSink 参数）。
 */
class TableSoundSink private constructor(
    private val tableOf: () -> SoundChannelTable,
    private val resolver: SoundSourceResolver,
) : SoundSink {

    /** 生产接线：表按 [aux] 槽现读，与内建宿主四项同源（见类头）。 */
    constructor(aux: RuntimeAux, resolver: SoundSourceResolver) : this({ soundChannelTable(aux) }, resolver)

    /** 固定表接线（单测 / 宿主自持）；不随 aux 重建换表。 */
    constructor(table: SoundChannelTable, resolver: SoundSourceResolver) : this({ table }, resolver)

    /** 每次调用现取的声道表：aux 构造下是当前槽，不缓存。 */
    private fun table(): SoundChannelTable = tableOf()

    /**
     * PLAYSOUND 的带结果形态：返回选中的声道号，[SoundChannelTable.NOT_PLAYED] = 文件不存在
     * 或表长为 0（原版前者静默 return，后者在回绕后 IndexOutOfRange，见表侧 KDoc）。
     * 解析 / 播放抛出的异常转 CodeEE（:2647-2650）。
     *
     * [count] **原样透传，不在这里再夹**。原版唯一的那次夹取在语句层
     * （`FunctionIdentifier.cs:2621`）：
     * ```
     * int repeat = (int)(spHtmlPrint.Opt == null ? 1 : Math.Max(spHtmlPrint.Opt.GetIntValue(exm), 1L));
     * ```
     * `Math.Max` 在 **long** 域做完才 `(int)` 截断，于是 `PLAYSOUND "x", 4294967295` 的
     * `repeat` 是 **-1**，落进 `Sound.cs:97-99` 的 `LoopStream`（无限循环）。
     * 适配层若再来一次 `maxOf(count, 1)`，-1 会被吃成 1 ⇒ 无限循环退化成播一遍。
     * 截断口径的落点在 `StatementExecutor` 的 `FunctionCode.PLAYSOUND` 分支。
     */
    fun playSoundChannel(name: String, count: Int): Int = translatingDecodeError {
        table().playSound(name, count, resolver)
    }

    /** STOPSOUND 的带结果形态：返回实际停掉的声道数（FunctionIdentifier.cs:2664-2674）。 */
    fun stopSoundChannels(): Int = table().stopSounds()

    /** PLAYBGM 的带结果形态：false = 文件不存在而静默（:2694-2697）；异常转 CodeEE（:2699-2702）。 */
    fun playBgmTrack(name: String): Boolean = translatingDecodeError {
        table().playBgm(name, resolver)
    }

    /** STOPBGM 的带结果形态：false = Bgm 为 null（原版 NRE，:2716）；不抛。 */
    fun stopBgmTrack(): Boolean = table().stopBgm()

    /** SETSOUNDVOLUME 的广播本体（:2731-2738）。 */
    fun applySoundVolume(volume: Int) {
        table().setSoundVolume(volume)
    }

    /** SETBGMVOLUME 的带结果形态：false = Bgm 为 null（原版 NRE，:2753）；不抛。 */
    fun applyBgmVolume(volume: Int): Boolean = table().setBgmVolume(volume)

    /**
     * 原版两条播放的裸 catch（FunctionIdentifier.cs:2647-2650、:2699-2702）：任何异常都转成
     * CodeEE `非対応のサウンドファイルが指定されました`（Lang.cs:3994）。该文案是 Lang 的
     * 缺省值（zhs 语言包没有对应条目），故保留原文。
     */
    private inline fun <T> translatingDecodeError(block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            throw CodeEE(INCOMPATIBLE_SOUND_FILE)
        }

    override fun playSound(name: String, count: Int) {
        playSoundChannel(name, count)
    }

    override fun stopSound() {
        stopSoundChannels()
    }

    override fun playBgm(name: String) {
        playBgmTrack(name)
    }

    override fun stopBgm() {
        if (!stopBgmTrack()) throw CodeEE(STOP_BGM_MISSING)
    }

    override fun setSoundVolume(volume: Int) {
        applySoundVolume(volume)
    }

    override fun setBgmVolume(volume: Int) {
        if (!applyBgmVolume(volume)) throw CodeEE(SET_BGM_VOLUME_MISSING)
    }

    private companion object {
        const val STOP_BGM_MISSING = "STOPBGM：BGM 声道不存在（原版此处抛 NullReferenceException 硬崩）"
        const val SET_BGM_VOLUME_MISSING = "SETBGMVOLUME：BGM 声道不存在（原版此处抛 NullReferenceException 硬崩）"
        const val INCOMPATIBLE_SOUND_FILE = "非対応のサウンドファイルが指定されました"
    }
}

/**
 * 假声音宿主（[SoundSink] 的可单测实现）：把语句层六条指令接到 [SoundChannelTable]，
 * 不产生任何真实声音，并把每次调用记成一条可读事件。
 *
 * 六条语句 → 表的逻辑委托 [TableSoundSink]（生产类），本类只负责事件流水——两边的
 * 选道/暂停算空闲/repeat/全声道广播因此共用同一份实现。
 *
 * 它是 M5-AUDIO 之前唯一能把 PLAYSOUND / STOPSOUND / PLAYBGM / STOPBGM /
 * SETSOUNDVOLUME / SETBGMVOLUME 与 SOUNDCONTROL / BGMCONTROL / GETSOUNDORBGMINFO
 * 串成**同一份**声道状态的装置（原版四条语句与三个函数动的都是 `GlobalStatic.Sound` /
 * `GlobalStatic.Bgm`，见 FunctionIdentifier.cs:2608-2755 与 FunctionMethodCreator.cs:8769-9030）。
 *
 * 与 [com.eratw.port.core.host.NoopSoundSink] 的分工：Noop 是「宿主没有音频后端」的
 * 缺省，必须保持全空实现（基准 SOUND-MISSING 行 pc-golden-root.txt:519-522 靠它）；
 * 本类是**显式接线**才生效的假后端，本波不把它塞进任何缺省宿主。
 *
 * @param table 共享的声道表；默认新建一张 10 声道的表（GlobalStatic.cs:42）。
 * @param resolver 资源名解析；默认空表 ⇒ 所有播放请求都按「文件不存在」静默跳过。
 */
class FakeSoundSink(
    val table: SoundChannelTable = SoundChannelTable(),
    resolver: SoundSourceResolver = FakeSoundSource(),
) : SoundSink {

    /** 六条语句 → 表的唯一实现；本类只把每次调用的结果记成事件。 */
    private val delegate = TableSoundSink(table, resolver)

    /** 调用流水（按发生顺序）。E2E / 单测用它断言语句层落到了哪个声道。 */
    val events: List<String> get() = calls.toList()

    private val calls = mutableListOf<String>()

    /** PLAYSOUND：repeat 最小 1（FunctionIdentifier.cs:2621 的 Math.Max 在语句层已夹，这里再夹一次）。 */
    override fun playSound(name: String, count: Int) {
        val channel = delegate.playSoundChannel(name, count)
        calls += if (channel == SoundChannelTable.NOT_PLAYED) {
            "playSound($name, $count) -> 未播"
        } else {
            "playSound($name, $count) -> ch$channel"
        }
    }

    /** STOPSOUND：全声道扫描（FunctionIdentifier.cs:2664-2674）。 */
    override fun stopSound() {
        calls += "stopSound -> 停 ${delegate.stopSoundChannels()}"
    }

    /** PLAYBGM：固定循环（FunctionIdentifier.cs:2696）。 */
    override fun playBgm(name: String) {
        val played = delegate.playBgmTrack(name)
        calls += "playBgm($name) -> ${if (played) "已播" else "未播"}"
    }

    /** STOPBGM：Bgm 为 null 时原版直接 NRE（FunctionIdentifier.cs:2716）——这里记成 MISS 不静默。 */
    override fun stopBgm() {
        calls += "stopBgm -> ${if (delegate.stopBgmTrack()) "已停" else "无BGM"}"
    }

    /** SETSOUNDVOLUME：逐声道写音量（FunctionIdentifier.cs:2731-2738）。 */
    override fun setSoundVolume(volume: Int) {
        delegate.applySoundVolume(volume)
        calls += "setSoundVolume($volume)"
    }

    /** SETBGMVOLUME：只写 Bgm（FunctionIdentifier.cs:2753）。 */
    override fun setBgmVolume(volume: Int) {
        val applied = delegate.applyBgmVolume(volume)
        calls += "setBgmVolume($volume) -> ${if (applied) "已写" else "无BGM"}"
    }
}

/**
 * 把 [SoundChannelTable] 暴露成 [AudioHost]（SOUNDCONTROL / BGMCONTROL / GETSOUNDORBGMINFO
 * 的宿主面，接口见 DebugMiscMethods.kt:224-232）。
 *
 * 快照换算逐条对应原版（FunctionMethodCreator.cs:8810-8815、:8820-8825）：
 * - `RESULT[0] = (long)(GetTotalTime() * 1000.0)` ⇒ [SoundInfo.totalSeconds] 保持「秒」，
 *   乘 1000 与截断都留在函数层；
 * - `RESULT[1] = (long)(GetCurrentTime() * 1000.0)` ⇒ [SoundInfo.currentSeconds]；
 * - `RESULT[2] = isPlaying() ? 1 : 0` ⇒ [SoundInfo.playing]；
 * - `RESULT[3] = getVolume()` ⇒ [SoundInfo.volume]（已是截断后的整数）；
 * - `RESULT[4] = (long)(getSpeed() * 100.0)` ⇒ [SoundInfo.speed]。
 *
 * 变速率入参：函数层把百分比除以 100.0（Double，DebugMiscMethods.kt:417、:439），原版是
 * `(float)arguments[2].GetIntValue() / 100f`（FunctionMethodCreator.cs:8935、:9019）。
 * 本类转回 binary32（[toFloat]）以复刻原版的截断读数；两条路径对 -200..3000 的每个整数
 * 输入结果逐一相同（用独立 IEEE 核对，见 SoundChannelTest 的期望表），故无口径缺口。
 *
 * 接线提醒：宿主必须让 `soundChannels` / `isPlayingSound` / `isPlayingBgm` / `audio`
 * 四项都指向**同一张表**（[channelCount] / [isPlayingSound] / [isPlayingBgm] 就是为此提供），
 * 否则 ISPLAYINGSOUND 与 GETSOUNDORBGMINFO 会读到两套状态。
 *
 * i-audiofn 追加：本类同时实现 [AudioChannelMaterializer]，把原版「先物化声道、后分派
 * 操作码」的那一步（FunctionMethodCreator.cs:8912-8915、:8996-8999）暴露给函数层
 * （fn/AudioMethods.kt）。两个新动作只是既有惰性建道入口的转发，不新增状态迁移。
 */
class ChannelAudioHost(
    val table: SoundChannelTable = SoundChannelTable(),
) : AudioHost, AudioChannelMaterializer {

    /** 给 `BuiltinHost.soundChannels` 用（原版 `GlobalStatic.Sound.Length`）。 */
    val channelCount: Int get() = table.channelCount

    /** 给 `BuiltinHost.isPlayingSound` 用（FunctionMethodCreator.cs:8866-8870）。 */
    fun isPlayingSound(channel: Int): Boolean = table.isPlayingSound(channel)

    /** 给 `BuiltinHost.isPlayingBgm` 用（FunctionMethodCreator.cs:8960-8964）。 */
    fun isPlayingBgm(): Boolean = table.isPlayingBgm()

    /**
     * 函数层已做过 `0 <= channel < soundChannels` 判定（DebugMiscMethods.kt:376），
     * 因此这里拿到 null 只可能是接线把表和 soundChannels 接歪了 —— 明确抛错而不是伪造 0。
     */
    override fun soundInfo(channel: Int): SoundInfo =
        (table.soundInfoChannel(channel) ?: error("声道 $channel 越界（soundChannels 与声道表不同源）")).snapshot()

    /** 原版读 Bgm 不惰性建，null ⇒ 函数返回 0（FunctionMethodCreator.cs:8793-8808）。 */
    override fun bgmInfo(): SoundInfo? = table.bgmInfoChannel()?.snapshot()

    /**
     * SOUNDCONTROL 的声道侧。原版在越界时提前返回 -1、合法时先建道再分派
     * （FunctionMethodCreator.cs:8908-8915）；越界判定留在函数层，这里 [SoundChannelTable.controlSoundChannel]
     * 为 null 时不做任何事（函数层已经返回 -1 了）。
     */
    override fun controlSound(channel: Int, op: AudioOp, speed: Double, preservePitch: Boolean) {
        val target = table.controlSoundChannel(channel) ?: return
        target.apply(op, speed, preservePitch)
    }

    /** BGMCONTROL 的 BGM 侧：无条件惰性建（FunctionMethodCreator.cs:8996-8998）。 */
    override fun controlBgm(op: AudioOp, speed: Double, preservePitch: Boolean) {
        table.controlBgmChannel().apply(op, speed, preservePitch)
    }

    /**
     * 新增动作（i-audiofn）：把「操作码分派之前就物化声道」这条原版顺序补出来。
     * 原版 SOUNDCONTROL 在越界判定之后、分派之前就 `new Sound()`
     * （FunctionMethodCreator.cs:8912-8915），非法操作码（返回 -2）也会留下实体化声道；
     * 走的是既有 [SoundChannelTable.controlSoundChannel]（惰性建，行号见其 KDoc），
     * 不新增任何状态迁移，因此与 controlSound 里的同一次建道是幂等的。
     */
    override fun materializeSound(channel: Int): Boolean = table.controlSoundChannel(channel) != null

    /**
     * 新增动作（i-audiofn）：原版 BGMCONTROL 在分派之前无条件建 Bgm
     * （FunctionMethodCreator.cs:8996-8999）。这条的后果**可观测**：
     * GETSOUNDORBGMINFO 的 BGM 侧自己不建道（:8793-8796），建过之后
     * `GETSOUNDORBGMINFO -1, 4` 才读得到出厂音量 100（Sound.cs:13 + :191），
     * 没建过则走 `sound == null` 分支返回 0（:8805-8808）。
     * 走既有 [SoundChannelTable.bgmOrCreate]，幂等。
     */
    override fun materializeBgm() {
        table.bgmOrCreate()
    }

    private fun SoundChannel.snapshot(): SoundInfo = SoundInfo(
        totalSeconds = getTotalTime(),
        currentSeconds = getCurrentTime(),
        playing = isPlaying(),
        volume = getVolume().toLong(),
        speed = getSpeed(),
    )

    /** 变速分支的顺序照抄原版：先 SetPreservePitch 再 setSpeed（:8941-8942、:9025-9026）。 */
    private fun SoundChannel.apply(op: AudioOp, speed: Double, preservePitch: Boolean) {
        when (op) {
            AudioOp.PAUSE -> pause()
            AudioOp.RESUME -> resume()
            AudioOp.STOP -> stop()
            AudioOp.SET_SPEED -> {
                setPreservePitch(preservePitch)
                setSpeed(speed.toFloat())
            }
        }
    }
}
