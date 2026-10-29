package com.eratw.port.core.host

import com.eratw.port.core.audio.ChannelAudioHost
import com.eratw.port.core.audio.SoundChannelTable
import com.eratw.port.core.audio.soundChannelTable
import com.eratw.port.core.fn.AudioHost
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.vars.VariableStore

/**
 * 内建函数的缺省宿主（[com.eratw.port.core.proc.ProcessRuntime] 的 `builtinHost` 未注入时生效）：
 * 配置项全部取自 [SemConfig]，与语句层共用同一份配置真相（不出现"SAVENOS 与
 * SemConfig.saveDataNos 各说各话"的第二真相）；时钟用 [System.currentTimeMillis]
 * （[BuiltinHost.clockMillis] 默认实现）。
 *
 * 音频四项（soundChannels / isPlayingSound / isPlayingBgm / audio）自 M5-AUDIO 起绑定到
 * [VariableStore.aux] 的声道表（[soundChannelTable]），四项**同源单表**（接线口径见
 * com.eratw.port.core.fn.Builtins.kt 的 [BuiltinHost.audio] KDoc）：
 * - [soundChannels] = 表长度（原版恒 10：GlobalStatic.cs:42 的 `new Sound[10]`）；
 * - [isPlayingSound] / [isPlayingBgm] = 表的只查不建查询（FunctionMethodCreator.cs:8866-8870、
 *   :8960-8964）：`Sound[num] != null && isPlaying()`，不物化空槽；
 * - [audio] = [ChannelAudioHost] 视图（GETSOUNDORBGMINFO / SOUNDCONTROL / BGMCONTROL
 *   的函数面，适配本体在 core/audio/AudioAdapters.kt:167-225）。
 *
 * 取表一律现读 `soundChannelTable(store.aux)`（RuntimeAux.kt:24-29 的槽语义），不缓存在
 * 本类字段里：aux 槽是唯一真相，RESTART / 读档重建清空 aux 之后取到的是新表——缓存字段
 * 会与语句层 sink、重建流程分叉。四项都未被读到之前不建表 ⇒ 不碰音频的游戏零开销。
 *
 * 语句层六条（PLAYSOUND 等）走 [com.eratw.port.core.sem.ExecContext.sound] 的 sink
 * （sem/StatementExecutor.kt:730-748 分派；注入点是 [com.eratw.port.core.proc.ProcessRuntime]
 * 的 `soundSink` 参数，缺省 [NoopSoundSink]）。宿主装配时让 sink 与这里的四项共用同一张表
 * 即形成闭环：生产用 [com.eratw.port.core.audio.TableSoundSink] 的 aux 构造，单测用
 * [com.eratw.port.core.audio.FakeSoundSink]（委托前者并记事件流水）。
 *
 * 未接的注入点（如实登记）：本类不持控制台（无 [consoleSink]），故 [lineIsEmpty] 走
 * [BuiltinHost.lineIsEmpty] 的接口缺省 —— `consoleSink == null ⇒ false`（未接线，不伪造
 * 「缓冲已空」）。[ConsoleSink] 早已提供读出侧 `printingBufferEmpty`，接真控制台时由
 * 那个宿主覆写本项即可，不必再动本类。
 *
 * 搬移注记（2026-10-24，波次 ARCH1 / arch1-rt-bridge）：本类连同 KDoc 自
 * `ProcessRuntime.kt` 文件尾原样搬入 host 包——它是 [BuiltinHost] 的缺省宿主实现，
 * 只依赖 [SemConfig] 与 [VariableStore.aux] 槽（都由构造参数传入），不持有
 * ProcessRuntime 的任何私有状态；纯搬移，类名/成员/行为零变化。原为 ProcessRuntime.kt
 * 文件级 private，跨文件外移的最小可见性放宽为 internal：唯一调用方 ProcessRuntime
 * 仍在本模块，不进 core-vm 公共 API 面。
 */
internal class SemConfigHost(
    private val config: SemConfig,
    /** 声道表槽的宿主：四项与语句层 sink 共用的同一张表挂在这个 store 的 aux 上。 */
    private val store: VariableStore,
) : BuiltinHost {
    override val moneyLabel: String get() = config.moneyLabel
    override val moneyLabelFirst: Boolean get() = config.moneyFirst
    override val saveDataNos: Int get() = config.saveDataNos

    /** 取表点唯一：四项都从这里拿同一张表（aux 槽单例；首次读取才建表）。 */
    private fun soundTable(): SoundChannelTable = soundChannelTable(store.aux)

    /** 原版 `GlobalStatic.Sound.Length`（GlobalStatic.cs:42）；取表的长度而非另立常量。 */
    override val soundChannels: Int get() = soundTable().channelCount

    /** spec: FunctionMethodCreator.cs:8866-8870 —— 「已建 且 在播」才为真，不惰性建道。 */
    override fun isPlayingSound(channel: Int): Boolean = soundTable().isPlayingSound(channel)

    /** spec: FunctionMethodCreator.cs:8960-8964 —— 同上；Bgm 侧只判 null 不建。 */
    override fun isPlayingBgm(): Boolean = soundTable().isPlayingBgm()

    /** GETSOUNDORBGMINFO / SOUNDCONTROL / BGMCONTROL 的宿主面；包装的正是 [soundTable]。 */
    override val audio: AudioHost get() = ChannelAudioHost(soundTable())
}
