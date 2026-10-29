package com.eratw.port.core.host

/**
 * 声音宿主（语句层入口；播放/停止/音量六条）。
 *
 * 调用面权威：`MinorShift.Emuera.GameProc.Function/FunctionIdentifier.cs`
 * - PLAYSOUND_Instruction :2608-2652 —— 名字走 SP_HTML_PRINT 参数形态（:2612），
 *   `repeat = Math.Max(第二参, 1)`（:2621），路径 `.\sound\ + 名字`（:2622），
 *   **文件不存在直接 return**（:2625-2628），然后从 0 起找第一个「不在播」的声道
 *   （:2629-2640，沿途把空槽 `new Sound()` 填上），全忙则回绕 0（:2641-2644），
 *   最后 `Sound[i].play(fullPath, repeat)`（:2645）；任何异常（含解码失败）转
 *   CodeEE `非対応のサウンドファイルが指定されました`（:2647-2650、Lang.cs:3994）
 * - STOPSOUND_Instruction :2654-2676 —— 遍历**全部**声道，先补 null 再只停 `isPlaying()` 的
 *   （:2664-2674），所以这条语句会把 10 个槽全部实体化
 * - PLAYBGM_Instruction :2678-2704 —— `GlobalStatic.Bgm.play(fullPath, -1)`（:2696，固定循环）；
 *   文件不存在静默（:2694）；异常同样转 CodeEE（:2699-2702）
 * - STOPBGM_Instruction :2706-2718 —— **不判空**直接 `Bgm.stop()`（:2716）；
 *   原版这条没有 try/catch，Bgm 为 null 时是硬崩而不是 CodeEE（与 PLAYBGM 不同）
 * - SETSOUNDVOLUME_Instruction :2720-2740 —— 逐个声道 `setVolume(volume)`（:2731-2738），
 *   同样先补 null。原版**没有**「全局音效音量」，音量是每声道各存一份（Sound.cs:180-187）
 * - SETBGMVOLUME_Instruction :2742-2755 —— 只写 `Bgm`（:2753），也不判空
 *
 * 声道数由 `GlobalStatic.Sound` 的定长数组给出（GlobalStatic.cs:42，长度 10），
 * `GlobalStatic.Bgm` 出厂即建（:44）、只有 `GlobalStatic.Reset()` 会置空（:69-73）。
 *
 * 移植侧状态机本体在 `core/audio`（SoundChannel.kt 与 SoundChannelTable.kt），逐条带 C# 行号；
 * [com.eratw.port.core.audio.FakeSoundSink] 是它的假实现，[NoopSoundSink] 是「宿主没有音频
 * 后端」的缺省。二者的分工是硬口径：基准里 SOUND 族是 MISSING 行
 * （fixtures/golden/baseline/pc-golden-root.txt:519-522 只有 `EXISTSOUND=0`、
 * `ISPLAYINGBGM=0 ISPLAYINGSOUND=-1`、`SOUND-DONE`），夹具 GOLDEN_09_MISC.ERB:41-52 播的是
 * 不存在的文件，而 SOUNDCONTROL / BGMCONTROL / GETSOUNDORBGMINFO 被 docs/golden-runbook.md:155
 * 列为排除项 —— 所以**不许**把假后端塞进任何缺省宿主，也不许让本接口的新增成员改变
 * [NoopSoundSink] 的全空实现。
 *
 * 与表达式层的分工：本接口只承载**语句层**六条；SOUNDCONTROL / BGMCONTROL /
 * GETSOUNDORBGMINFO 走 [com.eratw.port.core.fn.BuiltinHost.audio]
 * （类型见 core/fn/DebugMiscMethods.kt 的 AudioHost）。接线时本接口的实现与那个 AudioHost
 * 必须共用同一张声道表，否则 PLAYBGM 播完的东西 SOUNDCONTROL 会停不下来。
 */
interface SoundSink {
    /**
     * 播放 sound 目录下音频（PLAYSOUND）。
     *
     * [count] 是 `Sound.play` 的 `repeat`，**已由语句层按 :2621 夹过**
     * （`(int)Math.Max(实参, 1L)`）—— 注意夹取在 long 域、截断在 int 域，所以
     * `4294967295` 到这里是 **-1**（原版据此进 `LoopStream`，Sound.cs:97-99）。
     * 实现方**不许**再夹一次 `≥1`，那会把 -1 吃成「播一遍」。
     */
    fun playSound(name: String, count: Int)

    /** STOPSOUND：全声道扫描，只停 in-play 的（:2664-2674）。 */
    fun stopSound()

    /** 循环播放 BGM（PLAYBGM，原版固定 repeat = -1，:2696）。 */
    fun playBgm(name: String)

    /** STOPBGM（:2716）。原版在 Bgm 为 null 时硬崩；移植实现要显式表态，不许静默当成功。 */
    fun stopBgm()

    /** SETSOUNDVOLUME：逐声道写音量（:2731-2738），音量域 0..100（Sound.cs:182）。 */
    fun setSoundVolume(volume: Int)

    /** SETBGMVOLUME：只写 Bgm 的音量（:2753）。 */
    fun setBgmVolume(volume: Int)
}

/**
 * 「宿主没有音频后端」的缺省实现（[com.eratw.port.core.sem.ExecContext.sound] 的默认值）。
 *
 * 六条**必须**继续保持空实现：基准 SOUND-MISSING 行的 `ISPLAYINGBGM=0` /
 * `ISPLAYINGSOUND=-1` 是靠 `BuiltinHost.soundChannels = 0` 与空 sink 一起得到的
 * （见 HostMethods.kt 的 ISPLAYINGSOUND 注释）。将来接真后端是「换个实例」，不是改这里。
 */
object NoopSoundSink : SoundSink {
    override fun playSound(name: String, count: Int) {}
    override fun stopSound() {}
    override fun playBgm(name: String) {}
    override fun stopBgm() {}
    override fun setSoundVolume(volume: Int) {}
    override fun setBgmVolume(volume: Int) {}
}
