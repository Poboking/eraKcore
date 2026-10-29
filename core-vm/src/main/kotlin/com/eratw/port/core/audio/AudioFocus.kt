package com.eratw.port.core.audio

/**
 * 音频焦点事件 —— Android 平台强制项的**归一三值**，与 `AudioManager` 的常量解耦
 * （平台侧 `AudioFocusMapping` 负责翻译，本包不依赖任何 Android API）。
 *
 * ## 这不是原版语义
 *
 * 反编译原文里**没有**窗口失活 / 焦点相关的音频行为，逐条取证：
 * - `MainWindow.cs:2118-2119` 是全部事件挂载点，只挂 `FormClosing`（→ `MainWindow_FormClosing`，
 *   :1219-1234）与 `Shown`（→ `Init`）；全文 grep `Deactivate` / `VisibleChanged` / `LostFocus`
 *   零命中，grep `Sound` / `Bgm` 也零命中。
 * - `MainWindow_FormClosing`（:1219-1234）只做 `KeyMacro.SaveMacro()`、关 DebugDialog、
 *   `console.Dispose()`；**不动音频**。
 * - 音频停止只有两类显式来源：脚本语句 `STOPSOUND`（FunctionIdentifier.cs:2664-2674 逐声道
 *   `stop()`）与 `STOPBGM`（:2716）；进程启动清场 `Program.cs:97-104`（对 `GlobalStatic.Bgm`
 *   与所有已建 `Sound` 逐个 `close()`）。
 * - `Sound.pause()`（Sound.cs:121-136）/ `resume()`（:138-149）唯一调用方是脚本
 *   `SOUNDCONTROL` / `BGMCONTROL`（FunctionMethodCreator.cs:8921、:8924、:9005、:9008）。
 *
 * 结论：PC 版切走窗口音乐照放。Android 系统会把音频通道收走，所以焦点是**移植侧新增的平台
 * 约束**，不是 C# 口径。因此它必须落在**不改脚本可见状态**的旁路上：
 * [SoundChannel.isPlaying]（Sound.cs:171-178）的读数、`Playing` / `paused` 字段、混音器挂载
 * 集合都不因焦点变化而改 —— 否则 ISPLAYINGSOUND / ISPLAYINGBGM 与 PLAYSOUND 的「暂停算空闲」
 * 选道（FunctionIdentifier.cs:2636）会在切后台后与 PC 分叉。
 */
enum class AudioFocusEvent {
    /**
     * `AUDIOFOCUS_LOSS`：被长期抢走（另一个播放器接管），对方不会主动还回来，
     * 必须重新申请才可能再拿到 `GAIN`。见 [AudioFocusScope.PERMANENT]。
     */
    LOSS,

    /**
     * `AUDIOFOCUS_LOSS_TRANSIENT`：短暂让出（来电 / 导航播报），系统随后自行送回 `GAIN`。
     * `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` 也归到本值 —— 本移植侧不做闪避（duck）而是直接暂停，
     * 因为原版没有音量闪避概念（音量只有脚本 `SETSOUNDVOLUME` / `SETBGMVOLUME` 一处写入口，
     * FunctionIdentifier.cs:2731-2738、:2753），临时改音量会污染 `getVolume()` 读数
     * （Sound.cs:189-192）。
     */
    LOSS_TRANSIENT,

    /** `AUDIOFOCUS_GAIN`：焦点回到本进程。恢复规则见 [AudioFocusScope]。 */
    GAIN,
}

/**
 * 挂起作用域 —— 决定 `GAIN` 时怎么收尾。两个值的差别**只在恢复路径**，
 * 挂起路径（暂停全部在播声道的平台传输）完全相同。
 *
 * - [TRANSIENT]（来自 `LOSS_TRANSIENT`）：`GAIN` 时**全部**从挂起点原样续播。
 *   一次性音效也是「续播」而不是「重播」：位置回卷到挂起点，`seek(0)` 一次都不发。
 * - [PERMANENT]（来自 `LOSS`）：`GAIN` 时只有**循环声道**（`repeat == -1`，Sound.cs:98-101 /
 *   144 PlayBGM 固定 -1，FunctionIdentifier.cs:2696）续播；一次性音效按「时机已过」直接
 *   [SoundChannel.stop]，绝不重播。
 *
 * 为什么 `LOSS` 不干脆连 BGM 一起停：BGM 是场景的常驻背景，脚本没有为「外部抢占」准备任何
 * 分支（原版根本没有这个概念），停掉它会让 `ISPLAYINGBGM` 读到 0 并与 PC 分叉；而一次性音效
 * 停掉反而**更接近** PC 的观测 —— PC 上它早已自然播完并脱离混音器（SoundMixer.cs:31、:39-42），
 * 留着冻结只会白占一个声道槽（PLAYSOUND 选道 FunctionIdentifier.cs:2629-2644）。
 */
enum class AudioFocusScope {
    /** `LOSS_TRANSIENT`：`GAIN` 时全部续播。 */
    TRANSIENT,

    /** `LOSS`：`GAIN` 时只续播循环声道，一次性音效停止且不重播。 */
    PERMANENT,
}
