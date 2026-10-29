package com.eratw.port.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 声道状态机的逐迁移取证（权威 = Runtime.Utils/Sound.cs 与 SoundMixer.cs，行号写在每条断言旁）。
 *
 * 基准关系：`pc-golden-root.txt:519-522` 的 SECTION:SOUND-MISSING 只有
 * `EXISTSOUND=0` 与 `ISPLAYINGBGM=0 ISPLAYINGSOUND=-1`（夹具 GOLDEN_09_MISC.ERB:41-52
 * 播的是不存在的文件），SOUNDCONTROL / BGMCONTROL / GETSOUNDORBGMINFO 全部是
 * golden-runbook.md:155 列出的排除项 —— 所以本文件的期望值**全部**来自反编译 C# 与
 * 独立的 IEEE 运算核对，不引用基准行（基准对这些读数没有任何断言）。
 *
 * 变速率/音量的截断期望表用两种方式独立算出并互相核对：
 * 1. Python `fractions` 精确值 + 手工 round-to-nearest-even 到 binary32；
 * 2. 项目自带 JDK 17 的 jshell（`(int)(v/100f*100f)`、`(long)((double)(n/100f)*100.0)`）。
 * 两份结果完全一致，本文件按该表断言（不是实现回读）。
 */
class SoundChannelTest {

    private fun channel(total: Double = 4.0, position: Double = 0.0): Pair<SoundChannel, FakeChannelDecoder> {
        val decoder = FakeChannelDecoder(total, position)
        return SoundChannel() to decoder
    }

    // ------------------------------------------------------------------
    // 缺省值（Sound.cs:13-27）
    // ------------------------------------------------------------------

    @Test
    fun `新建声道的所有缺省值`() {
        val ch = SoundChannel()
        assertFalse(ch.playing, "Sound.cs:27 Playing 缺省 false")
        assertFalse(ch.paused, "Sound.cs:15 paused 缺省 false")
        assertFalse(ch.isPlaying(), "Sound.cs:171-178")
        assertFalse(ch.looping, "未 play 过就没有循环流")
        assertNull(ch.decoder, "Sound.cs:23 stream 缺省 null")
        assertEquals(0.0, ch.getTotalTime(), "Sound.cs:47-50 stream 为 null ⇒ 0.0")
        assertEquals(0.0, ch.getCurrentTime(), "Sound.cs:33-36 stream 为 null ⇒ 0.0")
        assertEquals(SoundChannel.DEFAULT_VOLUME, ch.getVolume(), "Sound.cs:13 volume=1f + :191 乘 100")
        assertEquals(SoundChannel.DEFAULT_SPEED, ch.getSpeed(), "Sound.cs:205 链路未建回 1.0")
        assertTrue(ch.preservePitch, "Sound.cs:21 preservePitch 缺省 true")
        assertEquals(0.0, ch.savedPositionSeconds, "Sound.cs:17 savedPosition 缺省 0")
        assertFalse(ch.varispeedReady, "Sound.cs:19 varispeedProvider 缺省 null")
        assertFalse(ch.repositionPending, "VarispeedSampleProvider.cs:22 repositionRequested 缺省 false")
        assertFalse(ch.mixer.initialized, "SoundMixer.cs:11 initialized 缺省 false")
    }

    // ------------------------------------------------------------------
    // play
    // ------------------------------------------------------------------

    @Test
    fun `play 挂上混音器并采用传入的时长`() {
        val (ch, decoder) = channel(total = 12.0)
        ch.play(decoder, repeat = 1)
        assertTrue(ch.playing, "Sound.cs:118 → SoundMixer.cs:75")
        assertTrue(ch.isPlaying(), "Sound.cs:171-178")
        assertSame(decoder, ch.decoder, "Sound.cs:85 赋 stream")
        assertEquals(12.0, ch.getTotalTime())
        assertEquals(0.0, ch.getCurrentTime())
        assertFalse(ch.looping, "repeat = 1 不套 LoopStream（Sound.cs:98-101）")
        assertTrue(ch.varispeedReady, "Sound.cs:115 新建变速链路")
        assertTrue(ch.mixer.initialized, "Sound.cs:78-81 首次 play 触发 Initialize")
        assertSame(ch, ch.mixer.registeredChannels().single(), "SoundMixer.cs:76 AddMixerInput")
    }

    @Test
    fun `repeat 只决定循环标志 不改变上报的总时长`() {
        // Sound.cs:97-105：Loop/Repeat 只套在采样链上，stream 字段仍是原始流；
        // GetTotalTime 读的是原始流（Sound.cs:51-56）。
        val (ch, decoder) = channel(total = 3.0)
        ch.play(decoder, repeat = SoundChannel.LOOP_FOREVER)
        assertTrue(ch.looping, "repeat == -1 → LoopStream（Sound.cs:98-99）")
        assertEquals(3.0, ch.getTotalTime(), "循环不放大总时长")

        val (ch2, decoder2) = channel(total = 3.0)
        ch2.play(decoder2, repeat = 5)
        assertFalse(ch2.looping, "repeat > 1 → RepeatStream（Sound.cs:102-104），有限")
        assertEquals(3.0, ch2.getTotalTime())
    }

    @Test
    fun `play 先 stop 旧流 并清 paused`() {
        val (ch, first) = channel(total = 5.0)
        ch.play(first, repeat = 1)
        ch.pause()
        assertTrue(ch.paused)
        val second = FakeChannelDecoder(total = 9.0, startPosition = 2.0)
        ch.play(second, repeat = 1)
        assertTrue(first.released, "Sound.cs:82 的 stop() 释放旧流（:158-161）")
        assertFalse(ch.paused, "stop() 无条件清 paused（Sound.cs:157）")
        assertSame(second, ch.decoder)
        assertEquals(9.0, ch.getTotalTime())
        assertEquals(2.0, ch.getCurrentTime(), "新流的位置由解码器给（不被 savedPosition 覆盖）")
    }

    @Test
    fun `play 重置变速率但保留音高与音量`() {
        // Sound.cs:115 新建 VarispeedSampleProvider ⇒ PlaybackRate 回 1f
        // （VarispeedSampleProvider.cs:18），但 profile 用 preservePitch 字段重建，
        // volume 字段被带到新的音量级（Sound.cs:116-117）。
        val (ch, decoder) = channel()
        ch.play(decoder, repeat = 1)
        ch.setSpeed(4.0f)
        assertEquals(4.0, ch.getSpeed(), "先证明 setSpeed 在链路存在时生效")
        ch.setPreservePitch(false)
        ch.setVolume(30)
        ch.play(FakeChannelDecoder(6.0), repeat = 1)
        assertEquals(SoundChannel.DEFAULT_SPEED, ch.getSpeed(), "play 把变速率抹回 1.0")
        assertFalse(ch.preservePitch, "preservePitch 字段存活（Sound.cs:115 + :244）")
        assertEquals(30, ch.getVolume(), "volume 字段存活（Sound.cs:117）")
    }

    // ------------------------------------------------------------------
    // pause / resume / stop
    // ------------------------------------------------------------------

    @Test
    fun `pause 记录位置 摘掉混音器并置 paused`() {
        val (ch, decoder) = channel(total = 8.0)
        ch.play(decoder, repeat = 1)
        decoder.advance(2.5)
        ch.pause()
        assertTrue(ch.paused, "Sound.cs:133")
        assertFalse(ch.playing, "Sound.cs:134 → SoundMixer.cs:81")
        assertFalse(ch.isPlaying(), "Sound.cs:171-178：暂停即不算在播")
        assertEquals(2.5, ch.savedPositionSeconds, "Sound.cs:127 存 stream.Position")
        assertTrue(ch.repositionPending, "Sound.cs:129-132 Reposition()")
        assertTrue(ch.mixer.registeredChannels().isEmpty(), "SoundMixer.cs:82 RemoveMixerInput")
    }

    @Test
    fun `pause 在未播放时整条 no-op`() {
        // Sound.cs:123 的守卫看的是 Playing 标志本身，不是 isPlaying()。
        val (ch, decoder) = channel()
        ch.pause()
        assertFalse(ch.paused, "从未播过 ⇒ 守卫失败")
        assertEquals(0.0, ch.savedPositionSeconds)

        ch.play(decoder, repeat = 1)
        ch.pause()
        decoder.advance(3.0)
        val pausedAt = ch.savedPositionSeconds
        ch.pause() // 第二次 pause：Playing 已被第一次 pause 清掉 ⇒ 仍然 no-op
        assertEquals(pausedAt, ch.savedPositionSeconds, "已暂停的声道再 pause 不会更新 savedPosition")
        assertTrue(ch.paused)
        assertFalse(ch.playing)
    }

    @Test
    fun `resume 回卷位置并重新挂载`() {
        val (ch, decoder) = channel(total = 8.0)
        ch.play(decoder, repeat = 1)
        decoder.advance(1.75)
        ch.pause()
        decoder.advance(1.0) // 暂停期间位置仍可被外部推进（模型不阻止）
        ch.resume()
        assertFalse(ch.paused, "Sound.cs:142")
        assertTrue(ch.playing, "Sound.cs:147 → SoundMixer.cs:75")
        assertTrue(ch.isPlaying())
        assertEquals(1.75, decoder.lastSeekSeconds, "Sound.cs:145 恢复 savedPosition")
        assertEquals(1, decoder.seekCount)
        assertEquals(1.75, ch.getCurrentTime())
    }

    @Test
    fun `resume 在 savedPosition 为 0 时不移位`() {
        // Sound.cs:143-146 的守卫是 savedPosition > 0。
        val (ch, decoder) = channel(total = 8.0)
        ch.play(decoder, repeat = 1)
        ch.pause() // 位置 0
        decoder.advance(4.0)
        ch.resume()
        assertTrue(ch.playing)
        assertEquals(0, decoder.seekCount, "savedPosition == 0 ⇒ 不调用 Position 赋值")
        assertEquals(4.0, ch.getCurrentTime(), "第 0 秒暂停再继续会往前走，官方行为")
    }

    @Test
    fun `resume 在未暂停时整条 no-op`() {
        val (ch, decoder) = channel()
        ch.resume()
        assertFalse(ch.playing, "Sound.cs:140 的守卫")
        ch.play(decoder, repeat = 1)
        ch.resume() // 已在播、paused = false ⇒ no-op
        assertTrue(ch.playing)
        assertEquals(0, decoder.seekCount)
    }

    @Test
    fun `stop 清 paused 释放流 但不清 savedPosition`() {
        // Sound.cs:151-163：没有一行重置 savedPosition。
        val (ch, decoder) = channel(total = 8.0)
        ch.play(decoder, repeat = 1)
        decoder.advance(6.0)
        ch.pause()
        assertEquals(6.0, ch.savedPositionSeconds)
        ch.stop()
        assertFalse(ch.playing, "Sound.cs:155 → SoundMixer.cs:81")
        assertFalse(ch.paused, "Sound.cs:157")
        assertNull(ch.decoder, "Sound.cs:161 stream = null")
        assertTrue(decoder.released, "Sound.cs:160 Dispose")
        assertEquals(6.0, ch.savedPositionSeconds, "savedPosition 不清零")
        assertEquals(0.0, ch.getTotalTime(), "stream 为 null ⇒ 两个时间读数 0.0")
        assertEquals(0.0, ch.getCurrentTime())
        // 变速链路与音量都不受影响（stop 没有重建它们）
        assertEquals(SoundChannel.DEFAULT_VOLUME, ch.getVolume())
        assertEquals(SoundChannel.DEFAULT_SPEED, ch.getSpeed())
    }

    @Test
    fun `从未 play 过的 stop 不会初始化混音器`() {
        // Sound.cs:153-156：mixer 未初始化时连 StopSound 都不调。
        val mixer = AudioMixer()
        val ch = SoundChannel(mixer)
        ch.stop()
        assertFalse(mixer.initialized)
        assertFalse(ch.playing)
        assertNull(ch.decoder)
        assertTrue(mixer.registeredChannels().isEmpty())
    }

    @Test
    fun `close 等价于 stop 且释放解码流`() {
        // Sound.cs:165-169；Program.cs:97-103 退出时逐个 close。
        val (ch, decoder) = channel(total = 2.0)
        ch.play(decoder, repeat = 1)
        ch.close()
        assertNull(ch.decoder)
        assertTrue(decoder.released)
        assertFalse(ch.isPlaying())
    }

    // ------------------------------------------------------------------
    // 混音器回调
    // ------------------------------------------------------------------

    @Test
    fun `输入结束回调只在非循环时结束播放`() {
        // SoundMixer.cs:31 + :39-42；LoopStream.HasData 恒 true（LoopStream.cs:31-34）
        val (ch, decoder) = channel()
        ch.play(decoder, repeat = 1)
        ch.mixer.onInputEnded(ch)
        assertFalse(ch.playing, "普通播放读完即 Playing = false")
        assertFalse(ch.isPlaying(), "⇒ ISPLAYINGSOUND 读不到它")

        val (loop, loopDecoder) = channel()
        loop.play(loopDecoder, repeat = SoundChannel.LOOP_FOREVER)
        loop.mixer.onInputEnded(loop)
        assertTrue(loop.playing, "循环流不会触发结束回调")
    }

    // ------------------------------------------------------------------
    // 音量域（Sound.cs:180-192）
    // ------------------------------------------------------------------

    @Test
    fun `音量入参夹到 0 到 100 区间`() {
        // spec: Sound.cs:182 的 Math.Clamp(volume, 0, 100)
        val ch = SoundChannel()
        ch.setVolume(-1)
        assertEquals(0, ch.getVolume())
        ch.setVolume(0)
        assertEquals(0, ch.getVolume())
        ch.setVolume(101)
        assertEquals(100, ch.getVolume())
        ch.setVolume(Int.MAX_VALUE)
        assertEquals(100, ch.getVolume())
        ch.setVolume(Int.MIN_VALUE)
        assertEquals(0, ch.getVolume())
    }

    @Test
    fun `音量读数的 binary32 往返只有 53 与 59 两个损失点`() {
        // 期望表由独立 IEEE 核对给出（Python 精确分数 + JDK17 jshell 双算一致）：
        // setVolume(v) ⇒ volume = (float)v / 100f；getVolume() ⇒ (int)(volume * 100f)
        // 0..100 中只有 53 读回 52、59 读回 58，其余恒等。
        val lossy = mapOf(53 to 52, 59 to 58)
        for (v in 0..100) {
            val ch = SoundChannel()
            ch.setVolume(v)
            assertEquals(lossy[v] ?: v, ch.getVolume(), "SETSOUNDVOLUME $v 的读回值（Sound.cs:182 + :191）")
        }
    }

    @Test
    fun `音量在链路未建时也已写入字段`() {
        // Sound.cs:182-183：赋值无条件，只有 volumeProvider.Volume 那行有 null 守卫。
        val ch = SoundChannel()
        ch.setVolume(80)
        assertEquals(80, ch.getVolume(), "从未 play 的声道也记得住音量")
    }

    // ------------------------------------------------------------------
    // 变速域（Sound.cs:194-206）
    // ------------------------------------------------------------------

    @Test
    fun `变速链路未建时 setSpeed 整条 no-op`() {
        // Sound.cs:196 的守卫：varispeedProvider == null 直接什么都不做。
        val ch = SoundChannel()
        ch.setSpeed(3.0f)
        assertEquals(SoundChannel.DEFAULT_SPEED, ch.getSpeed(), "getSpeed 回 1.0（Sound.cs:205）")
        ch.setSpeed(0.0f)
        assertEquals(SoundChannel.DEFAULT_SPEED, ch.getSpeed())
        // SOUNDCONTROL 在这种情况下仍然返回 1（FunctionMethodCreator.cs:8943），副作用为空
        val (played, decoder) = channel()
        played.play(decoder, repeat = 1)
        played.setSpeed(3.0f)
        assertEquals(3.0, played.getSpeed(), "play 之后同一调用才真正生效")
    }

    @Test
    fun `变速入参夹到 0 到 1 倍与 10 倍`() {
        // spec: Sound.cs:198 的 Math.Clamp(speed, 0.1f, 10f)
        val (ch, decoder) = channel()
        ch.play(decoder, repeat = 1)
        ch.setSpeed(0.0f)
        assertEquals(SoundChannel.SPEED_MIN.toDouble(), ch.getSpeed(), "下限 0.1")
        ch.setSpeed(-5.0f)
        assertEquals(SoundChannel.SPEED_MIN.toDouble(), ch.getSpeed())
        ch.setSpeed(99.0f)
        assertEquals(SoundChannel.SPEED_MAX.toDouble(), ch.getSpeed(), "上限 10")
        ch.setSpeed(1.0f)
        assertEquals(1.0, ch.getSpeed())
    }

    @Test
    fun `变速率读数的百分比截断表`() {
        // 链路：SOUNDCONTROL 传 n ⇒ speed = n/100f（FunctionMethodCreator.cs:8935）
        //       ⇒ Math.Clamp(0.1f,10f)（Sound.cs:198）⇒ PlaybackRate（binary32）
        //       ⇒ getSpeed() 转 double（Sound.cs:205）⇒ (long)(x * 100.0)
        // （FunctionMethodCreator.cs:8815、:8824）⇒ GETSOUNDORBGMINFO 选择子 5 的读数。
        // 期望表来自独立 IEEE 核对（Python 精确分数 + JDK17 jshell 一致），0..200 内的损失点：
        val lossy = mapOf(
            11 to 10, 12 to 11, 13 to 12, 16 to 15, 19 to 18, 21 to 20, 22 to 21, 24 to 23,
            26 to 25, 29 to 28, 32 to 31, 35 to 34, 38 to 37, 39 to 38, 41 to 40, 42 to 41,
            44 to 43, 45 to 44, 47 to 46, 48 to 47, 51 to 50, 52 to 51, 53 to 52, 57 to 56,
            58 to 57, 59 to 58, 63 to 62, 64 to 63, 65 to 64, 69 to 68, 70 to 69, 71 to 70,
            76 to 75, 77 to 76, 78 to 77, 82 to 81, 83 to 82, 84 to 83, 88 to 87, 89 to 88,
            90 to 89, 94 to 93, 95 to 94, 96 to 95,
            101 to 100, 102 to 101, 103 to 102, 104 to 103, 105 to 104, 106 to 105,
            113 to 112, 114 to 113, 115 to 114, 116 to 115, 117 to 116, 118 to 117,
            126 to 125, 127 to 126, 128 to 127, 129 to 128, 130 to 129, 131 to 130,
            138 to 137, 139 to 138, 140 to 139, 141 to 140, 142 to 141, 143 to 142,
            151 to 150, 152 to 151, 153 to 152, 154 to 153, 155 to 154, 156 to 155,
            163 to 162, 164 to 163, 165 to 164, 166 to 165, 167 to 166, 168 to 167,
            176 to 175, 177 to 176, 178 to 177, 179 to 178, 180 to 179, 181 to 180,
            188 to 187, 189 to 188, 190 to 189, 191 to 190, 192 to 191, 193 to 192,
        )
        for (n in 0..200) {
            val (ch, decoder) = channel()
            ch.play(decoder, repeat = 1)
            // 函数层把百分比除以 100.0（Double）后交给宿主，本类转回 binary32；
            // 两条路径对 0..200 的每个整数结果相同（独立核对过）。
            ch.setSpeed((n / 100.0).toFloat())
            val expected = if (n < 10) 10L else (lossy[n] ?: n).toLong() // 下限 0.1 ⇒ 10%
            assertEquals(expected, (ch.getSpeed() * 100.0).toLong(), "SOUNDCONTROL ch,3,$n 的读回值")
        }
        // 上限侧：>1000 一律夹到 10.0 ⇒ 1000
        for (n in listOf(1001, 1200, Int.MAX_VALUE)) {
            val (ch, decoder) = channel()
            ch.play(decoder, repeat = 1)
            ch.setSpeed((n / 100.0).toFloat())
            assertEquals(1000L, (ch.getSpeed() * 100.0).toLong(), "变速上限 $n")
        }
    }

    // ------------------------------------------------------------------
    // 音高保留（Sound.cs:242-249）
    // ------------------------------------------------------------------

    @Test
    fun `音高保留字段常驻 下发与否取决于链路`() {
        val ch = SoundChannel()
        ch.setPreservePitch(false)
        assertFalse(ch.preservePitch, "Sound.cs:244 无条件写字段")
        assertFalse(ch.pitchProfileApplied, "Sound.cs:245 的守卫：链路未建 ⇒ 没下发")

        val (played, decoder) = channel()
        played.setPreservePitch(false)
        played.play(decoder, repeat = 1)
        assertTrue(played.pitchProfileApplied, "Sound.cs:115 用字段建新 profile")
        played.setPreservePitch(true)
        assertTrue(played.pitchProfileApplied)
        assertTrue(played.preservePitch)
    }

    // ------------------------------------------------------------------
    // 平台钩子下发（M5-B AUDIO-BACKEND；钩子默认空实现，不影响可见状态）
    //
    // 顺序依据 Sound.cs 的源码顺序：play 建链（:97-118）、pause（:121-136）、
    // resume（:138-149）、stop（:151-163）、setVolume（:180-187）、setSpeed（:194-201）、
    // SetPreservePitch（:242-249）。每条断言只钉「钩子收到的动作与顺序」，
    // 可见状态由本文件上半部分与 FakeChannelDecoder 的既有用例继续钉住。
    // ------------------------------------------------------------------

    @Test
    fun `play 按建链顺序下发钩子`() {
        // Sound.cs:97-118：先按 repeat 套循环（:97-105）→ 重建变速链并回 1f（:115）→
        // 建音量级并写入 volume（:116-117）→ 挂混音器（:118）。
        val decoder = RecordingChannelDecoder("d")
        val ch = SoundChannel()
        ch.play(decoder, repeat = 3)

        assertEquals(
            listOf(
                "d.onRepeat(3)",
                "d.onSpeed(1.0)",
                "d.onPreservePitch(true)",
                "d.onVolume(1.0)",
                "d.onStarted",
            ),
            decoder.events,
        )
        assertTrue(ch.playing, "钩子不改变可见状态：仍由 mixer.play 置 playing")
        assertEquals(4.0, ch.getTotalTime(), "repeat 不改变上报的总时长（Sound.cs:51-56）")
        assertFalse(ch.looping, "repeat = 3 是有限重复（Sound.cs:102-104）")
    }

    @Test
    fun `play 先把旧流停掉再开新流`() {
        // Sound.cs:82 的 stop() 在开新流之前；旧流 Dispose 在 :158-161。
        val first = RecordingChannelDecoder("old")
        val second = RecordingChannelDecoder("new")
        val ch = SoundChannel()
        ch.play(first, repeat = 1)
        first.events.clear()

        ch.play(second, repeat = SoundChannel.LOOP_FOREVER)

        assertEquals(listOf("old.onStopped", "old.release"), first.events, "旧流先停后释放")
        assertTrue(first.released)
        assertFalse(second.released, "新流没有被释放")
        assertEquals("new.onRepeat(-1)", second.events.first(), "PLAYBGM 的循环来自 repeat = -1（FunctionIdentifier.cs:2696）")
        assertTrue(ch.looping)
        assertTrue(ch.playing)
        assertSame(second, ch.decoder)
    }

    @Test
    fun `pause 守卫放行才下发 重复暂停不重发`() {
        val decoder = RecordingChannelDecoder("d")
        val ch = SoundChannel()
        ch.pause()
        assertTrue(decoder.events.isEmpty(), "Sound.cs:123 的守卫失败 ⇒ 整条 no-op，连钩子也不发")

        ch.play(decoder, repeat = 1)
        decoder.events.clear()
        ch.pause()
        assertEquals(listOf("d.onPaused"), decoder.events)
        assertTrue(ch.paused)
        assertFalse(ch.playing)

        ch.pause() // Playing 已被清掉 ⇒ 第二次 pause 仍是 no-op
        assertEquals(listOf("d.onPaused"), decoder.events, "重复暂停不重复下发")
    }

    @Test
    fun `resume 先回卷再下发继续`() {
        val decoder = RecordingChannelDecoder("d", total = 8.0)
        val ch = SoundChannel()
        ch.play(decoder, repeat = 1)
        decoder.advance(1.5)
        ch.pause()
        decoder.events.clear()

        ch.resume()

        // Sound.cs:142-147：先 paused = false、再回卷 savedPosition、最后挂混音器。
        assertEquals(listOf("d.seek(1.5)", "d.onResumed"), decoder.events)
        assertTrue(ch.playing)
        assertFalse(ch.paused)

        ch.resume() // 已在播 ⇒ 守卫失败，不重发
        assertEquals(listOf("d.seek(1.5)", "d.onResumed"), decoder.events)
    }

    @Test
    fun `resume 在位置为 0 时只下发继续不回卷`() {
        // Sound.cs:143-146 的守卫是 savedPosition > 0。
        val decoder = RecordingChannelDecoder("d")
        val ch = SoundChannel()
        ch.play(decoder, repeat = 1)
        ch.pause()
        decoder.events.clear()

        ch.resume()

        assertEquals(listOf("d.onResumed"), decoder.events)
    }

    @Test
    fun `stop 先下发停止再释放`() {
        val decoder = RecordingChannelDecoder("d")
        val ch = SoundChannel()
        ch.play(decoder, repeat = 1)
        decoder.events.clear()

        ch.stop()

        assertEquals(listOf("d.onStopped", "d.release"), decoder.events)
        assertTrue(decoder.released)
        assertNull(ch.decoder)
    }

    @Test
    fun `setVolume 向当前解码流下发线性音量`() {
        val decoder = RecordingChannelDecoder("d")
        val ch = SoundChannel()
        ch.setVolume(30)
        assertTrue(decoder.events.isEmpty(), "Sound.cs:183 的 volumeProvider != null 守卫：没有流就不下发")
        assertEquals(30, ch.getVolume(), "字段照常写入（:182）")

        ch.play(decoder, repeat = 1)
        decoder.events.clear()
        ch.setVolume(30)
        // binary32 的 30/100f 原样回读为 0.3f（JDK17 核对：0.30000001192...），直接比 Float。
        assertEquals(listOf("d.onVolume(0.3)"), decoder.events)
        assertEquals(30, ch.getVolume())

        ch.setVolume(150) // Math.Clamp(volume, 0, 100)（:182）
        assertEquals("d.onVolume(1.0)", decoder.events.last())
        assertEquals(100, ch.getVolume())
    }

    @Test
    fun `setSpeed 只在变速链路存在且有流时下发夹后的值`() {
        val decoder = RecordingChannelDecoder("d")
        val ch = SoundChannel()
        ch.setSpeed(2.5f)
        assertTrue(decoder.events.isEmpty(), "Sound.cs:196 的 varispeedProvider == null ⇒ 整条 no-op")

        ch.play(decoder, repeat = 1)
        decoder.events.clear()
        ch.setSpeed(2.5f)
        assertEquals(listOf("d.onSpeed(2.5)"), decoder.events)

        ch.setSpeed(0.0f) // Math.Clamp(speed, 0.1f, 10f)（:198）
        assertEquals("d.onSpeed(0.1)", decoder.events.last())
        ch.setSpeed(99.0f)
        assertEquals("d.onSpeed(10.0)", decoder.events.last())
        assertEquals(10.0, ch.getSpeed())
    }

    @Test
    fun `setPreservePitch 只在链路存在且播放过之后下发`() {
        val decoder = RecordingChannelDecoder("d")
        val ch = SoundChannel()
        ch.setPreservePitch(false)
        assertTrue(decoder.events.isEmpty(), "Sound.cs:245 的 varispeedProvider == null 守卫")

        ch.play(decoder, repeat = 1)
        decoder.events.clear()
        ch.setPreservePitch(false)
        assertEquals(listOf("d.onPreservePitch(false)"), decoder.events)
        assertFalse(ch.preservePitch)
        assertTrue(ch.pitchProfileApplied)
    }

    @Test
    fun `钩子有默认空实现 只实现必需成员的解码器照常工作`() {
        // 只实现 totalSeconds / positionSeconds / seekTo 三个必需成员；钩子全部走默认实现。
        val minimal = object : ChannelDecoder {
            override val totalSeconds: Double get() = 2.0
            override val positionSeconds: Double get() = 0.0
            override fun seekTo(seconds: Double) {}
        }
        val ch = SoundChannel()
        ch.play(minimal, repeat = SoundChannel.LOOP_FOREVER)
        ch.setVolume(40)
        ch.setSpeed(3.0f)
        ch.setPreservePitch(false)
        ch.pause()
        ch.resume()
        ch.stop()
        ch.close()

        assertNull(ch.decoder)
        assertFalse(ch.isPlaying())
        assertEquals(40, ch.getVolume(), "钩子缺省不影响可见状态")
        assertEquals(3.0, ch.getSpeed())
        assertFalse(ch.preservePitch)
        assertEquals(0.0, ch.getTotalTime(), "stop 后 stream 为 null ⇒ 0.0")
    }
}

/**
 * 记录全部钩子与 seek / release 事件的假解码器（测试装置，不是生产实现）。
 *
 * 事件统一记字符串是为了让「钩子顺序 + seek/release 的相对位置」能一条 assertEquals 钉住；
 * 时间读数与 [FakeChannelDecoder] 同口径（秒），因此可以在同一个用例里同时断言状态与事件。
 */
private class RecordingChannelDecoder(
    private val label: String,
    private val total: Double = 4.0,
    startPosition: Double = 0.0,
) : ChannelDecoder {

    /** 按发生顺序记录的事件；`label` 前缀用于区分同一条声道上的新旧解码流。 */
    val events: MutableList<String> = mutableListOf()

    var released: Boolean = false
        private set

    private var pos: Double = startPosition

    override val totalSeconds: Double get() = total

    override val positionSeconds: Double get() = pos

    override fun seekTo(seconds: Double) {
        events += "$label.seek($seconds)"
        pos = seconds
    }

    override fun release() {
        events += "$label.release"
        released = true
    }

    override fun onStarted() {
        events += "$label.onStarted"
    }

    override fun onPaused() {
        events += "$label.onPaused"
    }

    override fun onResumed() {
        events += "$label.onResumed"
    }

    override fun onStopped() {
        events += "$label.onStopped"
    }

    override fun onVolume(linear: Float) {
        events += "$label.onVolume($linear)"
    }

    override fun onSpeed(rate: Float) {
        events += "$label.onSpeed($rate)"
    }

    override fun onPreservePitch(preserve: Boolean) {
        events += "$label.onPreservePitch($preserve)"
    }

    override fun onRepeat(repeat: Int) {
        events += "$label.onRepeat($repeat)"
    }

    /** 测试侧推进播放位置（真实后端由平台播放器推进）。 */
    fun advance(seconds: Double) {
        pos += seconds
    }
}
