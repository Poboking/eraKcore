package com.eratw.port.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 音频焦点旁路的模型侧取证（M5-AUDIO-PLATFORM 余项）。
 *
 * 权威来源 = **「原版没有这一层」** 这条反证，加上被它约束的既有 C# 口径：
 * - `MainWindow.cs:2118-2119` 是全部事件挂载点（`FormClosing` / `Shown`），
 *   :1219-1234 的 `MainWindow_FormClosing` 不动音频；全文无 `Sound` / `Bgm` 引用，
 *   也无 `Deactivate` / `VisibleChanged` / `LostFocus` 处理器。
 * - 音频停止只有脚本 `STOPSOUND`（FunctionIdentifier.cs:2664-2674）、`STOPBGM`（:2716）
 *   与启动清场 `Program.cs:97-104`。
 * ⇒ 焦点挂起**不得**改 `Playing` / `paused`，否则 `isPlaying()`（Sound.cs:171-178）、
 * ISPLAYINGSOUND / ISPLAYINGBGM（FunctionMethodCreator.cs:8866-8870、:8960-8964）与
 * PLAYSOUND 选道（FunctionIdentifier.cs:2636）会在切后台后与 PC 分叉。本文件钉的就是
 * 「模型可见状态不变、只有传输被挂起」。
 */
class AudioFocusTest {

    @Test
    fun `瞬态失焦挂起全部在播声道 但脚本可见状态不变`() {
        val mixer = AudioMixer()
        val bgm = SoundChannel(mixer)
        val sfx1 = SoundChannel(mixer)
        val sfx2 = SoundChannel(mixer)
        val bgmDecoder = RecordingDecoder(total = 30.0, start = 5.0)
        val sfxDecoder = RecordingDecoder(total = 2.0, start = 0.5)
        bgm.play(bgmDecoder, SoundChannel.LOOP_FOREVER)
        sfx1.play(sfxDecoder, repeat = 1)
        sfx2.play(RecordingDecoder(total = 1.0, start = 0.25), repeat = 1)
        bgmDecoder.pausedCount = 0
        sfxDecoder.pausedCount = 0

        val changed = mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT)

        assertEquals(3, changed, "三条在播声道全部挂起")
        assertEquals(AudioFocusScope.TRANSIENT, mixer.focusScope)
        assertEquals(1, bgmDecoder.pausedCount, "每条流只 onPaused 一次")
        assertEquals(1, sfxDecoder.pausedCount)
        assertTrue(bgm.focusSuspended)
        assertTrue(sfx1.focusSuspended)
        // 脚本可见状态一字节不动（Sound.cs:171-178 / FunctionIdentifier.cs:2636）。
        assertTrue(bgm.playing, "Playing 仍是 true（Sound.cs:27）")
        assertFalse(bgm.paused, "paused 仍是 false（Sound.cs:15）")
        assertTrue(bgm.isPlaying(), "ISPLAYINGBGM 读数不变")
        assertTrue(sfx1.isPlaying(), "ISPLAYINGSOUND 读数不变")
        assertTrue(mixer.isRegistered(bgm), "挂载集合不动（SoundMixer.cs:73-83）")
        assertEquals(3, mixer.registeredChannels().size)
        assertEquals(
            listOf(bgm, sfx1, sfx2),
            mixer.focusSuspendedChannels(),
            "暂停集合按挂载顺序 = 全部在播声道",
        )
    }

    @Test
    fun `瞬态恢复从挂起点续播 不从零重播`() {
        val mixer = AudioMixer()
        val sfx = SoundChannel(mixer)
        val decoder = RecordingDecoder(total = 3.0, start = 1.25)
        sfx.play(decoder, repeat = 1)
        decoder.pausedCount = 0
        decoder.resumedCount = 0
        decoder.seekCount = 0

        mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT)
        val changed = mixer.onFocusEvent(AudioFocusEvent.GAIN)

        assertEquals(1, changed)
        assertNull(mixer.focusScope, "GAIN 后没有挂起")
        assertFalse(sfx.focusSuspended)
        assertEquals(1, decoder.pausedCount)
        assertEquals(1, decoder.resumedCount, "只下发一次 onResumed（不重播）")
        assertEquals(1, decoder.seekCount, "回卷到挂起点")
        assertEquals(1.25, decoder.lastSeekSeconds, "位置不是 0（Sound.cs:143-146 的 >0 守卫）")
        assertTrue(sfx.isPlaying())
    }

    @Test
    fun `长期失焦的恢复 循环继续 一次性音效停止且不重播`() {
        val mixer = AudioMixer()
        val bgm = SoundChannel(mixer)
        val sfx = SoundChannel(mixer)
        val bgmDecoder = RecordingDecoder(total = 30.0, start = 7.0)
        val sfxDecoder = RecordingDecoder(total = 2.0, start = 0.5)
        bgm.play(bgmDecoder, SoundChannel.LOOP_FOREVER)
        sfx.play(sfxDecoder, repeat = 1)
        bgmDecoder.seekCount = 0
        sfxDecoder.seekCount = 0

        assertEquals(2, mixer.onFocusEvent(AudioFocusEvent.LOSS))
        assertEquals(AudioFocusScope.PERMANENT, mixer.focusScope)

        val changed = mixer.onFocusEvent(AudioFocusEvent.GAIN)

        assertEquals(2, changed)
        // 循环 BGM：回卷到挂起点继续（Sound.cs:98-101 的 LoopStream / :2696 的固定 -1）。
        assertEquals(1, bgmDecoder.resumedCount)
        assertEquals(7.0, bgmDecoder.lastSeekSeconds)
        assertTrue(bgm.isPlaying())
        // 一次性音效：按「时机已过」停掉，绝不 seek(0) + start。
        assertEquals(0, sfxDecoder.seekCount, "没有 seek(0) 就没有重播")
        assertEquals(0, sfxDecoder.resumedCount)
        assertFalse(sfx.playing, "Sound.cs:151-163 的 stop")
        assertTrue(sfxDecoder.released, "Sound.cs:158-161 释放解码流")
        assertFalse(mixer.isRegistered(sfx), "混音器摘掉（SoundMixer.cs:79-83）")
        assertEquals(listOf(bgm), mixer.registeredChannels())
    }

    @Test
    fun `重复的失焦与重复的恢复都幂等`() {
        val mixer = AudioMixer()
        val channel = SoundChannel(mixer)
        val decoder = RecordingDecoder(total = 2.0, start = 0.5)
        channel.play(decoder, repeat = 1)
        decoder.pausedCount = 0
        decoder.resumedCount = 0

        assertEquals(1, mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT))
        assertEquals(0, mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT), "第二道闸：已挂起不重发")
        assertEquals(1, decoder.pausedCount)

        assertEquals(1, mixer.onFocusEvent(AudioFocusEvent.GAIN))
        assertEquals(0, mixer.onFocusEvent(AudioFocusEvent.GAIN), "未挂起时的 GAIN 是空转")
        assertEquals(1, decoder.resumedCount)
        assertTrue(channel.isPlaying())
    }

    @Test
    fun `瞬态不降级 长期失焦可以升级`() {
        val mixer = AudioMixer()
        val sfx = SoundChannel(mixer)
        val decoder = RecordingDecoder(total = 2.0, start = 0.5)
        sfx.play(decoder, repeat = 1)

        assertEquals(1, mixer.onFocusEvent(AudioFocusEvent.LOSS))
        assertEquals(AudioFocusScope.PERMANENT, mixer.focusScope)
        // 长期失焦期间再来一条瞬态：不许把收尾规则改回「全部续播」。
        assertEquals(0, mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT))
        assertEquals(AudioFocusScope.PERMANENT, mixer.focusScope)

        mixer.onFocusEvent(AudioFocusEvent.GAIN)
        assertFalse(sfx.playing, "按 PERMANENT 收尾：一次性音效停掉")
        assertEquals(0, decoder.resumedCount)
    }

    @Test
    fun `脚本暂停的声道不受焦点影响 恢复后也不会被拉起`() {
        val mixer = AudioMixer()
        val channel = SoundChannel(mixer)
        val decoder = RecordingDecoder(total = 2.0, start = 0.5)
        channel.play(decoder, repeat = 1)
        channel.pause()
        decoder.pausedCount = 0
        decoder.resumedCount = 0
        decoder.seekCount = 0

        assertEquals(0, mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT), "它本来就不在混音器里")
        assertEquals(0, mixer.onFocusEvent(AudioFocusEvent.GAIN))
        assertEquals(0, decoder.resumedCount, "脚本暂停由 resume() 负责，焦点不得代劳")
        assertFalse(channel.isPlaying(), "Sound.cs:171-178 仍读到暂停")
        assertEquals(0, decoder.seekCount)
    }

    @Test
    fun `挂起期间自然播完的流 恢复时不会重播`() {
        val mixer = AudioMixer()
        val sfx = SoundChannel(mixer)
        val decoder = RecordingDecoder(total = 2.0, start = 1.9)
        sfx.play(decoder, repeat = 1)
        decoder.resumedCount = 0
        decoder.seekCount = 0

        mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT)
        decoder.finishNaturally() // 平台在挂起期间仍可能投递完成事件
        assertFalse(sfx.playing, "SoundMixer.cs:39-42")
        assertFalse(sfx.focusSuspended, "摘通道时顺手清掉挂起标志")
        assertTrue(mixer.registeredChannels().isEmpty())

        assertEquals(0, mixer.onFocusEvent(AudioFocusEvent.GAIN), "没有可恢复的对象")
        assertEquals(0, decoder.resumedCount)
        assertEquals(0, decoder.seekCount, "不 seek(0) 就不重播")
    }

    @Test
    fun `挂起期间新起的声道立刻跟随挂起`() {
        val mixer = AudioMixer()
        mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT)
        val late = SoundChannel(mixer)
        val decoder = RecordingDecoder(total = 4.0, start = 0.0)
        decoder.pausedCount = 0

        late.play(decoder, repeat = 1)

        assertTrue(late.focusSuspended, "后台不能突然出声")
        assertEquals(1, decoder.pausedCount, "onStarted 之后紧跟 onPaused")
        assertTrue(late.playing, "但模型仍算在播（脚本语义不变）")

        mixer.onFocusEvent(AudioFocusEvent.GAIN)
        assertEquals(1, decoder.resumedCount)
        assertTrue(late.isPlaying())
    }

    @Test
    fun `挂起期间换曲 旧流的迟到恢复不影响新流`() {
        val mixer = AudioMixer()
        val channel = SoundChannel(mixer)
        val old = RecordingDecoder(total = 5.0, start = 2.0)
        channel.play(old, repeat = 1)
        mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT)
        old.resumedCount = 0

        val fresh = RecordingDecoder(total = 6.0, start = 0.0)
        fresh.pausedCount = 0
        channel.play(fresh, repeat = 1) // play 内部先 stop 旧流，再被 mixer.play 跟随挂起

        assertEquals(0, old.resumedCount, "旧流已 release，不能恢复")
        assertTrue(old.released)
        assertTrue(channel.focusSuspended, "新流跟随当前作用域")
        assertEquals(1, fresh.pausedCount)

        mixer.onFocusEvent(AudioFocusEvent.GAIN)
        assertEquals(1, fresh.resumedCount)
        assertEquals(0, old.resumedCount)
        assertSame(fresh, channel.decoder)
    }

    @Test
    fun `挂起期间脚本暂停再继续 最终仍以挂起收尾`() {
        val mixer = AudioMixer()
        val channel = SoundChannel(mixer)
        val decoder = RecordingDecoder(total = 3.0, start = 1.0)
        channel.play(decoder, repeat = 1)
        mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT)
        decoder.resumedCount = 0
        decoder.pausedCount = 0

        channel.pause() // 脚本暂停：接管传输，焦点挂起标志让位
        assertFalse(channel.focusSuspended)
        decoder.pausedCount = 0
        decoder.resumedCount = 0
        channel.resume() // 脚本继续：焦点仍丢失 ⇒ mixer.play 立刻补挂起
        assertTrue(channel.focusSuspended, "后台不能因为脚本 RESUME 就出声")
        assertEquals(1, decoder.resumedCount)
        assertEquals(1, decoder.pausedCount, "resume 后紧跟一次 onPaused")
        assertFalse(channel.paused, "脚本的 paused 字段仍是 false（Sound.cs:142）")
        assertTrue(channel.isPlaying(), "模型读数按脚本意图：在播（Sound.cs:171-178）")

        mixer.onFocusEvent(AudioFocusEvent.GAIN)
        assertEquals(2, decoder.resumedCount, "GAIN 再续一次，位置从挂起点")
        assertEquals(1.0, decoder.lastSeekSeconds)
    }

    @Test
    fun `没有声道在播时失焦与恢复都是空转`() {
        val mixer = AudioMixer()
        assertEquals(0, mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT))
        assertEquals(AudioFocusScope.TRANSIENT, mixer.focusScope, "作用域仍要记录，供后续 play 跟随")
        assertEquals(0, mixer.onFocusEvent(AudioFocusEvent.GAIN))
        assertNull(mixer.focusScope)
    }
}

/**
 * 记录播放钩子的假解码流：在 [FakeChannelDecoder]（生产假体，只记时间基与释放）之外补上
 * 「平台传输被动了多少次」的计数器，用来钉焦点旁路**只动传输、不动模型**。
 */
private class RecordingDecoder(
    private val total: Double,
    private val start: Double,
) : ChannelDecoder {

    override val totalSeconds: Double get() = total

    override val positionSeconds: Double get() = pos

    private var pos: Double = start

    var pausedCount: Int = 0
    var resumedCount: Int = 0
    var seekCount: Int = 0
    var lastSeekSeconds: Double? = null
    var released: Boolean = false
        private set

    private var naturalEndListener: (() -> Unit)? = null

    override fun seekTo(seconds: Double) {
        seekCount++
        lastSeekSeconds = seconds
        pos = seconds
    }

    override fun release() {
        released = true
        naturalEndListener = null
    }

    override fun onPaused() {
        pausedCount++
    }

    override fun onResumed() {
        resumedCount++
    }

    override fun setNaturalEndListener(listener: (() -> Unit)?) {
        naturalEndListener = listener
    }

    /** 模拟平台在挂起期间仍投递完成事件（ROM 差异）。 */
    fun finishNaturally() {
        naturalEndListener?.invoke()
    }
}
