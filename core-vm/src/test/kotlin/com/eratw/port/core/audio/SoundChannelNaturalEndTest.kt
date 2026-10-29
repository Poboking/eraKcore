package com.eratw.port.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 「自然播完」反向通道的模型侧取证（CONTRACT-M6-A §2.3 判据 ①②④）。
 *
 * 权威来源：
 * - `SoundMixer.Initialize` 把 `MixerInputEnded` 接到 `SoundEnded`（SoundMixer.cs:31），
 *   后者只置 `Playing = false`（:39-42）；NAudio 的混音器同时把读完的输入从集合里摘掉
 *   （本仓 [AudioMixer.onInputEnded] 的既有语义，SoundChannel.kt:126-130）。
 * - 循环流永不触发结束回调：`LoopStream.HasData` 恒 true（LoopStream.cs:31-34），
 *   对应 repeat == -1 的包装（Sound.cs:98-101）。
 * - repeat > 1 的 RepeatStream 只在最后一遍读完后结束（RepeatStream.cs:38-62 的
 *   `remaining_count <= 1` 分支），所以模型侧只应收到一次完成事件。
 *
 * 平台侧只负责「什么时候调用监听器」；本文件只钉模型收到事件后的注册表变化，
 * 因此用 [FakeChannelDecoder.finishNaturally] 直接模拟完成回灌，不碰 Android。
 */
class SoundChannelNaturalEndTest {

    @Test
    fun `非循环解码流自然播完 只摘自己`() {
        val mixer = AudioMixer()
        val first = SoundChannel(mixer)
        val second = SoundChannel(mixer)
        val firstDecoder = FakeChannelDecoder(total = 2.0)
        val secondDecoder = FakeChannelDecoder(total = 3.0)
        first.play(firstDecoder, repeat = 1)
        second.play(secondDecoder, repeat = 1)
        assertEquals(2, mixer.registeredChannels().size)

        firstDecoder.finishNaturally()

        assertFalse(first.playing, "SoundMixer.cs:39-42：MixerInputEnded ⇒ Playing = false")
        assertFalse(first.isPlaying(), "Sound.cs:171-178 ⇒ ISPLAYINGSOUND 随即读不到它")
        assertEquals(
            listOf(second),
            mixer.registeredChannels(),
            "混音器只摘掉读完的那一路（SoundMixer.cs:31）",
        )
        assertTrue(second.playing, "另一路不受影响")
        assertTrue(mixer.isRegistered(second))
    }

    @Test
    fun `循环通道自然结束不摘通道`() {
        // PLAYBGM 固定 repeat = -1（FunctionIdentifier.cs:2696）；LoopStream.HasData 恒 true
        // （LoopStream.cs:31-34）。即使某些 ROM 仍回调 OnCompletionListener，模型也不摘。
        val channel = SoundChannel()
        val decoder = FakeChannelDecoder(total = 2.0)
        channel.play(decoder, repeat = SoundChannel.LOOP_FOREVER)
        assertTrue(channel.looping)

        decoder.finishNaturally()

        assertTrue(channel.playing, "SoundChannel.kt:126-130 的 looping 守卫")
        assertTrue(channel.isPlaying())
        assertTrue(channel.mixer.isRegistered(channel))
        assertEquals(listOf(channel), channel.mixer.registeredChannels())
    }

    @Test
    fun `重复的结束回调幂等`() {
        // 平台可能因 ROM 差异重复投递完成事件；重复回调不得多摘通道、不得抛。
        val mixer = AudioMixer()
        val channel = SoundChannel(mixer)
        val decoder = FakeChannelDecoder(total = 2.0)
        channel.play(decoder, repeat = 1)

        repeat(5) { decoder.finishNaturally() }

        assertFalse(channel.playing)
        assertTrue(mixer.registeredChannels().isEmpty(), "LinkedHashSet.remove 对已摘通道无副作用")
    }

    @Test
    fun `stop 摘掉解码器的完成监听 之后的回调无效`() {
        val channel = SoundChannel()
        val decoder = FakeChannelDecoder(total = 2.0)
        channel.play(decoder, repeat = 1)
        assertTrue(decoder.hasNaturalEndListener)

        channel.stop()

        assertFalse(decoder.hasNaturalEndListener, "SoundChannel.stop 先摘反向通道再 onStopped/release")
        assertTrue(decoder.released, "Sound.cs:158-161")
        decoder.finishNaturally() // release 后监听器已清 ⇒ no-op
        assertFalse(channel.playing)
        assertTrue(channel.mixer.registeredChannels().isEmpty())
    }

    @Test
    fun `被替换的旧解码流迟到回调不影响新流`() {
        // 平台在 stop/release 之前可能已经把完成事件投递到 looper；SoundChannel 用 decoder
        // 同一性守卫丢掉旧流事件，否则新流会被误摘。
        val channel = SoundChannel()
        val old = StaleFiringDecoder()
        val fresh = FakeChannelDecoder(total = 3.0)
        channel.play(old, repeat = 1)
        channel.play(fresh, repeat = 1)

        old.fireStale()

        assertSame(fresh, channel.decoder, "Sound.cs:85：当前流仍是新的")
        assertTrue(channel.playing, "旧流迟到回调不得摘新流")
        assertTrue(channel.mixer.isRegistered(channel))
    }
}

/**
 * 会保留历史监听器的假解码器：模拟「监听器已摘、但平台事件仍在途」的迟到回调。
 * 只实现时间基与反向通道，其余钩子走默认实现。
 */
private class StaleFiringDecoder : ChannelDecoder {
    private val captured = mutableListOf<() -> Unit>()

    override val totalSeconds: Double get() = 1.0
    override val positionSeconds: Double get() = 0.0

    override fun seekTo(seconds: Double) {}

    override fun setNaturalEndListener(listener: (() -> Unit)?) {
        if (listener != null) captured += listener
    }

    /** 触发最后一次安装过的监听器（不管它是否已被摘掉）。 */
    fun fireStale() {
        captured.last().invoke()
    }
}
