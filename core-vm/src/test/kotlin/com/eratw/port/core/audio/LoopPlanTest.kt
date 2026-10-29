package com.eratw.port.core.audio

import com.eratw.port.core.fn.AudioOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 循环点 / 时长 / 停止 与 通道上限的纯 JVM 取证（m11e-audio-format ②③）。
 *
 * 权威来源：
 * - repeat 三分类：Sound.cs:97-105（`-1` ⇒ LoopStream、`> 1` ⇒ RepeatStream、其余一遍）
 * - 循环点恒 0：LoopStream.cs:44-50（读到尾就把内层流 Position 置 0）
 * - 时长不随遍数放大：Sound.GetTotalTime 读的是**未被包装**的 stream 字段（Sound.cs:51-56），
 *   包装器只活在局部变量里（Sound.cs:97-106）；对照 RepeatStream.Length = Length * count
 *   （RepeatStream.cs:16）、LoopStream.Length = long.MaxValue / 32（LoopStream.cs:12）
 * - 停止是硬切：SOUNDCONTROL 的 op 2 直接 `stop()`（FunctionMethodCreator.cs:8926-8928），
 *   stop 只做「摘混音器 + 清 paused + Dispose 流」（Sound.cs:151-163）——**没有任何淡出**
 * - 通道上限 10 与满员回绕 0：GlobalStatic.cs:42、FunctionIdentifier.cs:2629-2644
 */
class LoopPlanTest {

    // ------------------------------------------------------------------
    // ② 循环点与时长
    // ------------------------------------------------------------------

    @Test
    fun `repeat 三分类照抄 Sound cs 97-105`() {
        assertEquals(LoopMode.FOREVER, LoopPlan.of(-1).mode, "-1 ⇒ LoopStream")
        assertTrue(LoopPlan.of(-1).looping)
        assertEquals(LoopPlan.PLAYS_FOREVER, LoopPlan.of(-1).plays)

        assertEquals(LoopMode.REPEAT, LoopPlan.of(2).mode)
        assertEquals(2, LoopPlan.of(2).plays)
        assertEquals(LoopMode.REPEAT, LoopPlan.of(7).mode)
        assertEquals(7, LoopPlan.of(7).plays)

        // 其余一律一遍：包括 0 与 -1 以外的负数（原版没有 Math.Max 在这一层）
        for (repeat in intArrayOf(0, 1, -2, -7, Int.MIN_VALUE)) {
            val plan = LoopPlan.of(repeat)
            assertEquals(LoopMode.ONCE, plan.mode, "repeat=$repeat 不是循环")
            assertEquals(1, plan.plays)
            assertFalse(plan.looping)
        }
        // Int.MAX_VALUE > 1 ⇒ 重复（不是循环），与原版 RepeatStream 分支一致
        assertEquals(LoopMode.REPEAT, LoopPlan.of(Int.MAX_VALUE).mode)
    }

    @Test
    fun `循环点恒为 0 且循环流不会自然结束`() {
        val plan = LoopPlan.of(SoundChannel.LOOP_FOREVER)
        assertEquals(0.0, plan.restartAtSeconds, "LoopStream.cs:44-50 回到文件头，没有循环标记")
        assertFalse(plan.endsNaturally, "LoopStream.HasData 恒 true（LoopStream.cs:31-34）")
        assertTrue(LoopPlan.of(1).endsNaturally)
        assertEquals(0.0, LoopPlan.of(3).restartAtSeconds, "每遍之间同样回到 0")
    }

    @Test
    fun `时长不随遍数放大 包装器的 Length 不泄漏到读数`() {
        // RepeatStream.Length = source.Length * total_count（RepeatStream.cs:16），
        // 但 Sound.GetTotalTime 读原始 stream（Sound.cs:51-56）⇒ 重复 3 遍仍是单遍时长。
        val channel = SoundChannel()
        val decoder = FakeChannelDecoder(total = 120.0)
        channel.play(decoder, repeat = 3)

        assertEquals(120.0, channel.getTotalTime(), "repeat=3 不得读成 360")
        assertEquals(LoopMode.REPEAT, channel.loopPlan.mode)
        assertFalse(channel.looping, "repeat>1 不是无限循环")
    }

    @Test
    fun `PLAYBGM 的循环流时长等于单遍时长`() {
        // PLAYBGM 固定 repeat = -1（FunctionIdentifier.cs:2696）⇒ LoopStream；
        // LoopStream.Length 是 long.MaxValue/32（LoopStream.cs:12），若泄漏进读数，
        // GETSOUNDORBGMINFO 的总时长会变成天文数字。
        val table = SoundChannelTable()
        val sources = FakeSoundSource(mapOf("bgm.mp3" to 95.5))

        assertTrue(table.playBgm("bgm.mp3", sources))
        val bgm = table.bgm!!
        assertTrue(bgm.looping)
        assertEquals(LoopMode.FOREVER, bgm.loopPlan.mode)
        assertEquals(95.5, bgm.getTotalTime(), "循环不改变上报总时长")
        assertEquals(0.0, bgm.getCurrentTime(), "刚起播位置在 0")
    }

    @Test
    fun `SOUNDCONTROL 的停止是硬切 没有淡出`() {
        // op 2 ⇒ stop()（FunctionMethodCreator.cs:8926-8928）→ Sound.cs:151-163：
        // 摘混音器、清 paused、Dispose 流。全程没有一次音量写入 —— 移植侧不得自行加淡出，
        // 否则 getVolume()（Sound.cs:189-192）会读到中间值并与 PC 分叉。
        val host = ChannelAudioHost()
        val channel = host.table.channelAtOrCreate(0)!!
        val decoder = HookLogDecoder(total = 42.0)
        channel.play(decoder, repeat = 1)
        channel.setVolume(60)
        decoder.hooks.clear()

        host.controlSound(0, AudioOp.STOP, speed = 1.0, preservePitch = true)

        assertEquals(
            listOf("naturalEnd(false)", "onStopped", "release"),
            decoder.hooks,
            "先摘反向通道，再停，再释放（SoundChannel.stop 的顺序）",
        )
        assertFalse(decoder.hooks.any { it.startsWith("onVolume") }, "停止路径不写音量 ⇒ 没有淡出")
        assertEquals(60, channel.getVolume(), "音量读数不受停止影响（PC 的 volume 字段不随 stop 变）")
        assertEquals(0.0, channel.getCurrentTime(), "流已释放 ⇒ 位置读 0.0（Sound.cs:33-36）")
        assertFalse(channel.isPlaying())
        assertTrue(decoder.released)
    }

    @Test
    fun `暂停中的声道被 SOUNDCONTROL 停止时不重复下发暂停`() {
        // pause 已把平台传输停下（Sound.cs:133-134），stop 只补 onStopped + release。
        val host = ChannelAudioHost()
        val channel = host.table.channelAtOrCreate(0)!!
        val decoder = HookLogDecoder(total = 10.0)
        channel.play(decoder, repeat = 1)
        host.controlSound(0, AudioOp.PAUSE, speed = 1.0, preservePitch = true)
        decoder.hooks.clear()

        host.controlSound(0, AudioOp.STOP, speed = 1.0, preservePitch = true)

        assertEquals(listOf("naturalEnd(false)", "onStopped", "release"), decoder.hooks)
        assertFalse(channel.paused, "stop 无条件清 paused（Sound.cs:157）")
    }

    // ------------------------------------------------------------------
    // ③ 通道上限与溢出策略
    //
    // 「并发上限」不在混音器上：SoundMixer.PlaySound 只是 AddMixerInput（SoundMixer.cs:73-77），
    // 本身不数输入条数；上限来自定长 10 的声道表（GlobalStatic.cs:42）与 PLAYSOUND 的
    // 选路（FunctionIdentifier.cs:2629-2644）。SoundChannelTable 与 SoundChannel 逐条同构。
    // ------------------------------------------------------------------

    @Test
    fun `上限恒为 10 且溢出抢的是 0 号而不是最旧`() {
        // 原版定长 10（GlobalStatic.cs:42）；全忙时 i 落到 Length 后回绕 0（:2641-2644）。
        val table = SoundChannelTable()
        val sources = FakeSoundSource(mapOf("a.mp3" to 1.0))
        for (expected in 0 until 10) {
            assertEquals(expected, table.playSound("a.mp3", 1, sources))
        }
        // 让 0 号变成「刚播过的」而不是最旧的：先暂停 5 号，再占用 5 号，
        // 此时 0 号仍是最旧 —— 换个构造：把 0 号停掉重播，0 号就变成最新的在播声道。
        table.channelAt(0)!!.stop()
        assertEquals(0, table.playSound("a.mp3", 1, sources), "空出来的 0 号被选中")
        // 现在 0..9 全在播，且 0 号是最新起播的：溢出仍抢 0 号 ⇒ 策略是「固定回绕 0」而非 LRU。
        assertEquals(0, table.playSound("a.mp3", 1, sources), "溢出固定回绕 0")
        assertEquals((0 until 10).toList(), table.playingSoundChannels())
    }

    @Test
    fun `溢出抢占会释放被抢声道的解码流`() {
        // play() 开头先 stop()（Sound.cs:82）⇒ 旧流 Dispose（:158-161）。
        // 若移植侧漏了这一步，10 条以上音效会泄漏解码器（真机上 = MediaPlayer 实例泄漏）。
        val table = SoundChannelTable()
        val sources = FakeSoundSource(mapOf("a.mp3" to 1.0))
        repeat(10) { table.playSound("a.mp3", 1, sources) }
        val first = sources.opened[0]
        assertFalse(first.released)

        table.playSound("a.mp3", 1, sources)

        assertTrue(first.released, "0 号被抢占前必须释放旧流（Sound.cs:82 + :158-161）")
        assertEquals(11, sources.opened.size, "每次 PLAYSOUND 都重新开流")
        assertFalse(sources.opened[10].released)
        assertEquals(sources.opened[10], table.channelAt(0)!!.decoder)
    }

    @Test
    fun `暂停算空闲 所以满员时优先抢占暂停槽而不是 0 号`() {
        // 选路用 isPlaying()（FunctionIdentifier.cs:2636 + Sound.cs:171-178）⇒ 暂停态算空闲。
        val table = SoundChannelTable()
        val sources = FakeSoundSource(mapOf("a.mp3" to 1.0))
        repeat(10) { table.playSound("a.mp3", 1, sources) }
        table.channelAt(7)!!.pause()

        assertEquals(7, table.playSound("a.mp3", 1, sources), "先找第一个不在播的槽")
        assertTrue(table.isPlayingSound(7))
    }
}

/**
 * 记录平台钩子调用的假解码器 —— 用来断言「停止路径到底下发了什么」。
 * 时间基与 [FakeChannelDecoder] 同口径（只有 total / position 两个数）。
 *
 * 名字带 `HookLog` 前缀是必须的：本包里 `AudioFocusTest` 已有一个私有
 * `RecordingDecoder`，Kotlin 不允许同包跨文件重名（会报 Redeclaration），
 * 而重名时构造调用还会解析到对方的签名上，把一串无关断言连带打红。
 */
private class HookLogDecoder(
    total: Double,
    private var pos: Double = 0.0,
) : ChannelDecoder {

    override val totalSeconds: Double = total

    val hooks: MutableList<String> = mutableListOf()

    var released: Boolean = false
        private set

    override val positionSeconds: Double get() = pos

    override fun seekTo(seconds: Double) {
        hooks += "seek($seconds)"
        pos = seconds
    }

    override fun setNaturalEndListener(listener: (() -> Unit)?) {
        hooks += "naturalEnd(${listener != null})"
    }

    override fun onStarted() { hooks += "onStarted" }
    override fun onPaused() { hooks += "onPaused" }
    override fun onResumed() { hooks += "onResumed" }
    override fun onStopped() { hooks += "onStopped" }
    override fun onVolume(linear: Float) { hooks += "onVolume($linear)" }
    override fun onSpeed(rate: Float) { hooks += "onSpeed($rate)" }
    override fun onPreservePitch(preserve: Boolean) { hooks += "onPreservePitch($preserve)" }
    override fun onRepeat(repeat: Int) { hooks += "onRepeat($repeat)" }

    override fun release() {
        hooks += "release"
        released = true
    }
}
