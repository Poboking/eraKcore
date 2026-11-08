package com.eratw.port.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * L7 稳定性复查（2026-11-01）：音频平台钩子异常路径的隔离与释放。
 *
 * 四处行为的触发条件都是「平台侧抛异常」，模型侧此前没有可证伪的用例：
 * 1. 焦点旁路 `AudioMixer.onFocusEvent` 旧写法是一条 `count { ... }` —— 一条声道抛了，
 *    后面的声道全部不挂起（后台继续出声），异常还会抛回 AudioManager 回调线程；
 * 2. `AudioMixer.play` 在焦点挂起期间起的声道同理，异常会把 PLAYSOUND 变成 CodeEE；
 * 3. `SoundChannel.stop` 里 `onStopped` 抛了会跳过 `release`，平台句柄随播放次数累积；
 * 4. `SoundChannelTable.playSound` 在「无声道可用」时已经开了流却不释放。
 *
 * 反证方式：把主源里的 `runCatching` / `try-finally` / `if (channel == null)` 三个补丁分别
 * 去掉，本文件的对应用例会以「异常上抛 / 兄弟声道未挂起 / released == false」失败；
 * 保留时全绿。语义侧的口径（脚本可见状态不变、异常照旧上抛）与既有
 * [AudioFocusTest]、[SoundChannelTest] 同判。
 */
class StabilityL7AudioTest {

    @Test
    fun `失焦时单条声道的平台钩子抛异常 不拦其它声道也不上抛`() {
        val mixer = AudioMixer()
        val a = SoundChannel(mixer)
        val b = SoundChannel(mixer)
        val c = SoundChannel(mixer)
        val da = FaultyDecoder()
        val db = FaultyDecoder().apply { positionFault = true }
        val dc = FaultyDecoder()
        a.play(da, repeat = 1)
        b.play(db, repeat = 1)
        c.play(dc, repeat = 1)

        // 必须不抛：旧写法在 b 上抛 IllegalStateException，c 永远轮不到挂起。
        val changed = mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT)

        assertEquals(2, changed, "失败的那条不计入，其余两条照常挂起")
        assertEquals(AudioFocusScope.TRANSIENT, mixer.focusScope)
        assertTrue(a.focusSuspended)
        assertTrue(c.focusSuspended, "后面的声道不能被前一条的平台异常拦住")
        assertFalse(b.focusSuspended, "位置读数在置标志之前抛 ⇒ 这条没挂起")
        assertEquals(1, da.pausedCount)
        assertEquals(0, db.pausedCount)
        assertEquals(1, dc.pausedCount)
        assertEquals(3, mixer.registeredChannels().size, "模型挂载集合不动（SoundMixer.cs:73-83）")

        // 恢复同样逐条隔离：两条正常声道照常续播。
        assertEquals(2, mixer.onFocusEvent(AudioFocusEvent.GAIN))
        assertNull(mixer.focusScope)
        assertEquals(1, da.resumedCount)
        assertEquals(1, dc.resumedCount)
    }

    @Test
    fun `恢复时单条声道的平台钩子抛异常 不拦其它声道也不上抛`() {
        val mixer = AudioMixer()
        val a = SoundChannel(mixer)
        val b = SoundChannel(mixer)
        val c = SoundChannel(mixer)
        val da = FaultyDecoder()
        val db = FaultyDecoder()
        val dc = FaultyDecoder()
        a.play(da, repeat = 1)
        b.play(db, repeat = 1)
        c.play(dc, repeat = 1)
        assertEquals(3, mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT))
        db.resumeFault = true

        val changed = mixer.onFocusEvent(AudioFocusEvent.GAIN) // 旧写法在这里上抛

        assertEquals(2, changed)
        assertEquals(1, db.resumedCount, "钩子先计数再抛，说明它确实被下发了")
        assertEquals(1, da.resumedCount)
        assertEquals(1, dc.resumedCount, "排在失败声道之后的仍要续播")
        assertNull(mixer.focusScope, "作用域闸门先落定，不因单条失败回滚")
    }

    @Test
    fun `挂起期间起的声道 平台暂停异常不变成脚本可见错误`() {
        val mixer = AudioMixer()
        assertEquals(0, mixer.onFocusEvent(AudioFocusEvent.LOSS_TRANSIENT))

        val channel = SoundChannel(mixer)
        val decoder = FaultyDecoder().apply { pauseFault = true }
        // 原版没有焦点这一层（AudioFocusTest 类头），PLAYSOUND 不该因为平台暂停失败而 CodeEE。
        channel.play(decoder, repeat = 1)

        assertTrue(channel.playing, "mixer.play 已挂上（SoundMixer.cs:73-77）")
        assertTrue(channel.isPlaying(), "脚本可见读数不变（Sound.cs:171-178）")
        assertTrue(channel.focusSuspended, "位置快照之后才抛，挂起标志已落定")
        assertEquals(1, decoder.pausedCount)
    }

    @Test
    fun `stop 的 onStopped 抛异常时 release 仍执行 异常照旧上抛`() {
        val mixer = AudioMixer()
        val channel = SoundChannel(mixer)
        val decoder = FaultyDecoder().apply { stopFault = true }
        channel.play(decoder, repeat = 1)

        val boom = assertFailsWith<IllegalStateException> { channel.stop() }

        assertEquals("stop boom", boom.message, "异常照旧上抛（语句层 CodeEE 口径）")
        assertTrue(decoder.released, "finally 里的 release 必达，平台句柄不泄漏")
        assertSame(
            decoder,
            channel.decoder,
            "原版 stream.Dispose() 抛出时 stream 同样不置 null（Sound.cs:158-161）",
        )
        assertFalse(channel.playing, "mixer.stop 在 onStopped 之前（Sound.cs:151-163）")
        assertFalse(mixer.isRegistered(channel))
    }

    @Test
    fun `声道表为空时 PLAYSOUND 释放已打开的解码流`() {
        // 原版先选道后开文件（FunctionIdentifier.cs:2629-2644 → Sound.cs:83-96），
        // 表长 0 时根本走不到开流；移植侧 resolve 在选道之前，已开的流必须就地释放。
        val sources = FakeSoundSource(mapOf("a.mp3" to 10.0))
        val table = SoundChannelTable(0)

        assertEquals(SoundChannelTable.NOT_PLAYED, table.playSound("a.mp3", 1, sources))

        assertEquals(1, sources.opened.size, "resolve 确实开过流")
        assertTrue(sources.opened[0].released, "没有可播声道 ⇒ 立即释放，不留句柄")
        assertTrue(table.channels().isEmpty())
    }

    @Test
    fun `正常选到声道时解码流不被提前释放`() {
        val sources = FakeSoundSource(mapOf("a.mp3" to 10.0))
        val table = SoundChannelTable(1)

        assertEquals(0, table.playSound("a.mp3", 1, sources))

        assertFalse(sources.opened[0].released, "在播的流由 stop()/closeAll() 负责释放")
    }
}

/**
 * 可注入故障的平台解码流：每条钩子都能单独抛，用来钉「一条声道失败不拖垮其它声道」。
 * 抛出的异常类型取 `MediaPlayer` 在非法状态下真实会抛的 `IllegalStateException`。
 */
private class FaultyDecoder(
    private val total: Double = 10.0,
    private val start: Double = 1.0,
) : ChannelDecoder {

    override val totalSeconds: Double get() = total

    var positionFault: Boolean = false
    var pauseFault: Boolean = false
    var resumeFault: Boolean = false
    var stopFault: Boolean = false

    var pausedCount: Int = 0
        private set
    var resumedCount: Int = 0
        private set

    var released: Boolean = false
        private set

    override val positionSeconds: Double
        get() {
            if (positionFault) throw IllegalStateException("position boom")
            return start
        }

    override fun seekTo(seconds: Double) = Unit

    override fun release() {
        released = true
    }

    override fun onPaused() {
        pausedCount++
        if (pauseFault) throw IllegalStateException("pause boom")
    }

    override fun onResumed() {
        resumedCount++
        if (resumeFault) throw IllegalStateException("resume boom")
    }

    override fun onStopped() {
        if (stopFault) throw IllegalStateException("stop boom")
    }
}
