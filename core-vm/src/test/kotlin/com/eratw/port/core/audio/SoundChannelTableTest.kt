package com.eratw.port.core.audio

import com.eratw.port.core.host.RuntimeAux
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 声道表（原版 `GlobalStatic.Sound` + `GlobalStatic.Bgm`）的选路、惰性建道与全声道广播口径。
 *
 * 每条断言旁的行号是反编译原文出处。基准对这些读数**没有任何期望**
 * （pc-golden-root.txt:519-522 只有 MISSING 行；golden-runbook.md:155 把 SOUNDCONTROL /
 * BGMCONTROL / GETSOUNDORBGMINFO 列为排除项），所以本文件既不把基准文本当期望，
 * 也不允许被用来改动基准行为 —— 它与 SOUND-MISSING 行同判（见 AudioAdaptersTest）。
 */
class SoundChannelTableTest {

    /** 一张表 + 一个假音源 + 一个骑在上面的假 sink。 */
    private class World(
        val table: SoundChannelTable,
        val sources: FakeSoundSource,
    ) {
        val sink: FakeSoundSink = FakeSoundSink(table, sources)
    }

    private fun world(
        channels: Int = SoundChannelTable.DEFAULT_CHANNEL_COUNT,
        files: Map<String, Double> = mapOf("a.mp3" to 10.0),
    ): World {
        val sources = FakeSoundSource(files)
        return World(SoundChannelTable(channels), sources)
    }

    // ------------------------------------------------------------------
    // 出厂形态
    // ------------------------------------------------------------------

    @Test
    fun `声道数缺省 10 且 Bgm 出厂即建`() {
        // spec: GlobalStatic.cs:42（new Sound[10]）、:44（Bgm = new Sound()）
        val table = SoundChannelTable()
        assertEquals(10, table.channelCount)
        assertEquals(10, table.channels().size)
        assertTrue(table.channels().all { it == null }, "数组元素出厂全 null")
        assertNotNull(table.bgm, "GlobalStatic.cs:44 出厂非空")
        assertFalse(table.isPlayingBgm(), "刚建的 Sound 不在播（Sound.cs:27）")
        assertNotSame(table.bgm!!, table.channelAtOrCreate(0), "Bgm 不占用任何声道槽")
    }

    @Test
    fun `只查不建的读端与惰性建的写端`() {
        // 惰性建：FunctionMethodCreator.cs:8799-8802（GETSOUNDORBGMINFO）、:8912-8915（SOUNDCONTROL）、
        //         :8996-8998（BGMCONTROL）、FunctionIdentifier.cs:2632-2635（PLAYSOUND 扫描）
        // 只查不建：FunctionMethodCreator.cs:8866（ISPLAYINGSOUND）、:8793-8796（读 Bgm）
        val table = SoundChannelTable()
        assertNull(table.channelAt(0), "只查不建的入口不会写下标 0")
        assertFalse(table.isPlayingSound(0), "ISPLAYINGSOUND 的 null 槽分支")

        val first = table.soundInfoChannel(3)
        assertNotNull(first)
        assertSame(first, table.channelAt(3), "GETSOUNDORBGMINFO 建的声道要写回槽位")
        assertSame(first, table.soundInfoChannel(3), "第二次读必须复用同一对象（:8799 的判空）")
        assertNull(table.soundInfoChannel(-1), "越界不建（原版返回 0，:8805-8808）")
        assertNull(table.soundInfoChannel(10), "越界不建")
    }

    @Test
    fun `controlSoundChannel 越界与 controlBgmChannel 的惰性建`() {
        val table = SoundChannelTable()
        assertNotNull(table.controlSoundChannel(0))
        assertSame(table.channelAt(0), table.controlSoundChannel(0))
        assertNull(
            table.controlSoundChannel(11),
            "越界：原版在判空之前就先返回 -1（FunctionMethodCreator.cs:8908-8911）",
        )

        val reset = SoundChannelTable(initialBgm = null)
        assertNull(reset.bgmInfoChannel(), "读 Bgm 不建（FunctionMethodCreator.cs:8793-8796）")
        val created = reset.controlBgmChannel()
        assertSame(created, reset.bgmInfoChannel(), "BGMCONTROL 会把 Bgm 建回来（:8996-8998）")
    }

    // ------------------------------------------------------------------
    // PLAYSOUND 选路
    // ------------------------------------------------------------------

    @Test
    fun `文件不存在时静默不播 且不建声道`() {
        // spec: FunctionIdentifier.cs:2625-2628（File.Exists 为假直接 return）
        val w = world()
        assertEquals(SoundChannelTable.NOT_PLAYED, w.table.playSound("missing.mp3", 1, w.sources))
        assertTrue(w.table.channels().all { it == null }, "选路之前就返回了，不该建任何声道")
        assertFalse(w.table.mixer.initialized, "没走到 play() 就不会初始化混音器")
        assertTrue(w.sources.opened.isEmpty(), "没查到的名字不会开流")
    }

    @Test
    fun `PLAYSOUND 依次占用空闲声道 满员后回绕 0 抢占`() {
        // spec: FunctionIdentifier.cs:2629-2644
        val w = world()
        for (expected in 0 until SoundChannelTable.DEFAULT_CHANNEL_COUNT) {
            assertEquals(expected, w.table.playSound("a.mp3", 1, w.sources), "第 ${expected + 1} 次 PLAYSOUND")
        }
        assertEquals(0, w.table.playSound("a.mp3", 1, w.sources), "全忙 ⇒ i 回绕 0（:2641-2644）")
        assertTrue(w.table.isPlayingSound(0))
        assertEquals((0 until 10).toList(), w.table.playingSoundChannels())
        assertEquals(11, w.sources.opened.size, "每次 PLAYSOUND 都重新开一个流（Sound.cs:83-96）")
    }

    @Test
    fun `已暂停的声道算空闲 会被下一条 PLAYSOUND 抢占`() {
        // spec: FunctionIdentifier.cs:2636 用的是 isPlaying()；Sound.cs:171-178 让暂停态返回 false
        val w = world()
        assertEquals(0, w.table.playSound("a.mp3", 1, w.sources))
        w.table.channelAt(0)!!.pause()
        assertFalse(w.table.isPlayingSound(0))
        assertEquals(0, w.table.playSound("a.mp3", 1, w.sources), "又落回 0 声道")
        assertTrue(w.table.isPlayingSound(0), "抢占后重新在播")
    }

    @Test
    fun `PLAYSOUND 沿途只建到命中的那一格`() {
        // spec: FunctionIdentifier.cs:2630-2640（循环里逐个补 null，命中即 break）
        val w = world()
        w.table.playSound("a.mp3", 1, w.sources)
        assertNotNull(w.table.channelAt(0))
        assertNull(w.table.channelAt(1), "第一次扫描在 0 就 break，不会建后面的槽")
        w.table.playSound("a.mp3", 1, w.sources)
        assertNotNull(w.table.channelAt(1))
        assertNull(w.table.channelAt(2))
    }

    @Test
    fun `repeat 小于 1 时按 1 处理 只有 -1 是循环`() {
        // spec: FunctionIdentifier.cs:2621 的 Math.Max(实参, 1)；:2696 的 PLAYBGM 固定 -1
        val w = world()
        val used = w.table.playSound("a.mp3", 0, w.sources)
        assertFalse(w.table.channelAt(used)!!.looping, "0 被夹成 1，不是循环")
        assertTrue(w.table.playBgm("a.mp3", w.sources))
        assertTrue(w.table.bgm!!.looping, "PLAYBGM 用 LoopStream（Sound.cs:98-99）")
    }

    @Test
    fun `声道表长度为 0 时 playSound 不播不崩`() {
        // 原版数组定长 10（GlobalStatic.cs:42），0 长只出现在移植侧「无音频后端」。
        // 原版 0 长会 IndexOutOfRange（:2641-2645 回绕后取下标 0）——移植侧按无声道可用静默处理，
        // 该差异已写进交付报告。
        val w = world(channels = 0)
        assertEquals(SoundChannelTable.NOT_PLAYED, w.table.playSound("a.mp3", 1, w.sources))
        assertFalse(w.table.isPlayingSound(0))
        assertTrue(w.table.channels().isEmpty())
    }

    // ------------------------------------------------------------------
    // STOPSOUND / SETSOUNDVOLUME：全声道
    // ------------------------------------------------------------------

    @Test
    fun `STOPSOUND 只停在播的 但把十个槽都建出来`() {
        // spec: FunctionIdentifier.cs:2664-2674
        val w = world()
        w.table.playSound("a.mp3", 1, w.sources)
        w.table.playSound("a.mp3", 1, w.sources)
        assertEquals(2, w.table.stopSounds())
        assertEquals(10, w.table.channels().count { it != null }, "循环内逐个 new（:2666-2669）")
        assertTrue(w.table.playingSoundChannels().isEmpty())
        assertNull(w.table.channelAt(0)?.decoder, "stop 释放流（Sound.cs:161）")
        assertEquals(0, w.table.stopSounds(), "第二次 STOPSOUND 没有在播的了")
    }

    @Test
    fun `SETSOUNDVOLUME 广播到每个声道 不动 Bgm`() {
        // spec: FunctionIdentifier.cs:2731-2738 —— 没有「全局音效音量」，逐声道各存一份；
        //       Bgm 的音量只有 SETBGMVOLUME 会写（:2753）
        val w = world()
        w.table.playSound("a.mp3", 1, w.sources)
        w.table.setSoundVolume(70)
        assertEquals(10, w.table.channels().count { it != null }, "SETSOUNDVOLUME 也建满十个槽")
        for (i in 0 until 10) assertEquals(70, w.table.channelAt(i)!!.getVolume(), "声道 $i")
        assertEquals(SoundChannel.DEFAULT_VOLUME, w.table.bgm!!.getVolume(), "BGM 不受 SETSOUNDVOLUME 影响")
    }

    @Test
    fun `SETSOUNDVOLUME 的截断在广播后同样可见`() {
        // spec: Sound.cs:182（除 100 存 binary32）+ :191（乘 100 截断）
        val w = world()
        w.table.setSoundVolume(53)
        assertEquals(52, w.table.channelAt(0)!!.getVolume())
        w.table.setSoundVolume(59)
        assertEquals(58, w.table.channelAt(0)!!.getVolume())
        w.table.setSoundVolume(50)
        assertEquals(50, w.table.channelAt(0)!!.getVolume())
    }

    // ------------------------------------------------------------------
    // BGM 侧
    // ------------------------------------------------------------------

    @Test
    fun `PLAYBGM 缺文件时静默 也不新建 Bgm`() {
        // spec: FunctionIdentifier.cs:2694-2697（File.Exists 为假就不进 play）
        val table = SoundChannelTable(initialBgm = null)
        assertFalse(table.playBgm("missing.mp3", FakeSoundSource()))
        assertNull(table.bgm, "原版此时根本不动 Bgm；本模型保留 reset 后的 null 口径")
    }

    @Test
    fun `STOPBGM 与 SETBGMVOLUME 在 Bgm 为 null 时返回否`() {
        // spec: FunctionIdentifier.cs:2716、:2753 都直接解引用 Bgm、不判空
        //       （原版是 NullReferenceException，且这两条没有 try/catch ⇒ 硬崩）
        val bare = SoundChannelTable(initialBgm = null)
        assertFalse(bare.stopBgm())
        assertFalse(bare.setBgmVolume(50))
        assertFalse(bare.isPlayingBgm())

        val w = world()
        assertTrue(w.table.playBgm("a.mp3", w.sources))
        assertTrue(w.table.isPlayingBgm())
        assertTrue(w.table.setBgmVolume(40))
        assertEquals(40, w.table.bgm!!.getVolume())
        assertTrue(w.table.stopBgm())
        assertFalse(w.table.isPlayingBgm())
        assertFalse(w.table.bgm!!.playing, "stop 摘掉混音器（Sound.cs:155 + SoundMixer.cs:81）")
    }

    // ------------------------------------------------------------------
    // 查询与清场
    // ------------------------------------------------------------------

    @Test
    fun `ISPLAYINGSOUND 的三种不播形态`() {
        // spec: FunctionMethodCreator.cs:8849-8852（越界 -1）、:8866（未建或不在播 ⇒ -1）
        val w = world()
        assertFalse(w.table.isPlayingSound(0), "从未建过")
        assertFalse(w.table.isPlayingSound(-1), "负下标")
        assertFalse(w.table.isPlayingSound(10), "越界")
        val used = w.table.playSound("a.mp3", 1, w.sources)
        assertTrue(w.table.isPlayingSound(used))
        w.table.channelAt(used)!!.pause()
        assertFalse(w.table.isPlayingSound(used), "暂停 ⇒ 不在播（Sound.cs:171-178）")
        w.table.channelAt(used)!!.resume()
        assertTrue(w.table.isPlayingSound(used))
    }

    @Test
    fun `reset 把已建声道与 Bgm 置空`() {
        // spec: GlobalStatic.cs:58-73
        val w = world()
        w.table.playSound("a.mp3", 1, w.sources)
        w.table.playBgm("a.mp3", w.sources)
        w.table.reset()
        assertTrue(w.table.channels().all { it == null }, "GlobalStatic.cs:65（Sound[i] = null）")
        assertNull(w.table.bgm, "GlobalStatic.cs:72（Bgm = null）")
        assertFalse(w.table.isPlayingSound(0))
        assertFalse(w.table.isPlayingBgm())
        assertNotNull(w.table.controlBgmChannel(), "reset 后 BGMCONTROL 仍能建回来（:8996-8998）")
    }

    @Test
    fun `reset 只处理已建的槽`() {
        // spec: GlobalStatic.cs:60-67（先判 null 再 stop）
        val table = SoundChannelTable(channelCount = 3, initialBgm = null)
        assertNotNull(table.channelAtOrCreate(1))
        table.reset()
        assertNull(table.channelAt(0), "从未建过的槽不会被 reset 建出来")
        assertNull(table.channelAt(1))
        assertTrue(table.channels().all { it == null })
    }

    @Test
    fun `closeAll 释放所有解码流但不置空槽位`() {
        // spec: Program.cs:97-103（逐个 close；不动数组本身）
        val w = world()
        val used = w.table.playSound("a.mp3", 1, w.sources)
        val stream = w.table.channelAt(used)!!.decoder as FakeChannelDecoder
        w.table.closeAll()
        assertTrue(stream.released)
        assertNotNull(w.table.channelAt(used), "close 不删对象")
        assertNotNull(w.table.bgm)
    }

    // ------------------------------------------------------------------
    // 每 VM 一份（AGENTS.md §2.4：状态挂 store.aux，不用静态可变全局）
    // ------------------------------------------------------------------

    @Test
    fun `声道表挂在 RuntimeAux 上且同 VM 复用`() {
        val aux = RuntimeAux()
        val first = soundChannelTable(aux)
        assertSame(first, soundChannelTable(aux))
        assertEquals(setOf("audio.channels"), aux.slotNames())
        assertNotSame(first, soundChannelTable(RuntimeAux()), "两个 VM 不得串味")
        assertEquals(2, soundChannelTable(RuntimeAux(), channelCount = 2).channelCount)
    }

    @Test
    fun `假 sink 的调用都落到同一张表`() {
        val sources = FakeSoundSource(mapOf("a.mp3" to 10.0))
        val table = SoundChannelTable(channelCount = 2)
        val sink = FakeSoundSink(table, sources)
        sink.playSound("a.mp3", 1)
        assertTrue(table.isPlayingSound(0))
        assertSame(table.mixer, table.channelAt(0)!!.mixer, "新建声道必须挂到同一台混音器")
        assertEquals(listOf("playSound(a.mp3, 1) -> ch0"), sink.events)

        sink.setSoundVolume(50)
        sink.stopSound()
        sink.stopBgm()
        sink.playBgm("a.mp3")
        sink.setBgmVolume(20)
        sink.playSound("missing.mp3", 3)
        assertEquals(
            listOf(
                "playSound(a.mp3, 1) -> ch0",
                "setSoundVolume(50)",
                "stopSound -> 停 1",
                "stopBgm -> 已停",
                "playBgm(a.mp3) -> 已播",
                "setBgmVolume(20) -> 已写",
                "playSound(missing.mp3, 3) -> 未播",
            ),
            sink.events,
        )
        assertTrue(table.isPlayingBgm(), "PLAYBGM 之后 BGM 在循环播放")
    }
}
