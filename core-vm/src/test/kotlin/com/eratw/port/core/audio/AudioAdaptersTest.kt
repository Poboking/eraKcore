package com.eratw.port.core.audio

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.AudioHost
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.SoundSink
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 适配器层取证：声道模型接上 [AudioHost] / [SoundSink] 后，SOUNDCONTROL / BGMCONTROL /
 * GETSOUNDORBGMINFO 与 PLAYSOUND 等语句看到的是**同一份**状态（原版四处动的都是
 * `GlobalStatic.Sound` / `GlobalStatic.Bgm`，见 FunctionIdentifier.cs:2608-2755 与
 * FunctionMethodCreator.cs:8769-9030）。
 *
 * 红线口径：`pc-golden-root.txt:519-522` 的 SECTION:SOUND-MISSING 只有 `EXISTSOUND=0`、
 * `ISPLAYINGBGM=0`、`ISPLAYINGSOUND=-1` 三条读数，三条音频控制函数是 golden-runbook.md:155
 * 列出的排除项。本波没有把模型塞进任何缺省宿主，所以 `BuiltinHost.DEFAULT` 下这些读数与
 * 基准同判（见最后两条用例）；前面对接线宿主的用例证明「接线」是**显式**动作，不是既成事实。
 */
private class AudioTestCtx : EvalContext {
    override lateinit var variables: VariableReader

    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("AudioTestCtx 不应被直接调用：$name")
}

/** 接线后的宿主：四个音频成员全部同源（口径见 com.eratw.port.core.fn.Builtins.kt 的 sound 分支）。 */
private class WiredHost(private val rig: ChannelAudioHost) : BuiltinHost {
    override val soundChannels: Int get() = rig.channelCount
    override fun isPlayingSound(channel: Int): Boolean = rig.isPlayingSound(channel)
    override fun isPlayingBgm(): Boolean = rig.isPlayingBgm()
    override val audio: AudioHost get() = rig
}

private class AudioEnv(val host: BuiltinHost) {
    val base = AudioTestCtx()
    val store = VariableStore()
    val ctx: BuiltinEvalContext

    init {
        base.variables = store
        ctx = BuiltinEvalContext(base, store, host)
    }

    fun int(name: String, vararg args: Long): Long =
        (ctx.callFunction(name, args.map { Value.IntV(it) }) as Value.IntV).v

    fun result(i: Int): Long = store.readInt("RESULT", longArrayOf(i.toLong()))

    /** GETSOUNDORBGMINFO 无第二参时写的 RESULT[0..4]。 */
    fun results(): List<Long> = (0..4).map { result(it) }

    fun seedResult(i: Int, value: Long) = store.writeInt("RESULT", longArrayOf(i.toLong()), value)
}

class AudioAdaptersTest {

    private fun wired(channels: Int = SoundChannelTable.DEFAULT_CHANNEL_COUNT): Pair<AudioEnv, ChannelAudioHost> {
        val rig = ChannelAudioHost(SoundChannelTable(channels))
        return AudioEnv(WiredHost(rig)) to rig
    }

    /** 往 rig 的表里放一个假文件并播到第一个空闲声道；返回该次播放打开的假流。 */
    private fun playOn(rig: ChannelAudioHost, name: String, total: Double): FakeChannelDecoder {
        val source = FakeSoundSource(mapOf(name to total))
        val used = rig.table.playSound(name, 1, source)
        check(used != SoundChannelTable.NOT_PLAYED)
        return source.opened.last()
    }

    // ------------------------------------------------------------------
    // 快照换算（FunctionMethodCreator.cs:8810-8825）
    // ------------------------------------------------------------------

    @Test
    fun `GETSOUNDORBGMINFO 无第二参时写 RESULT 五项`() {
        val (env, rig) = wired()
        val stream = playOn(rig, "a.mp3", 2.5)
        stream.advance(1.25)
        assertEquals(2500L, env.int("GETSOUNDORBGMINFO", 0))
        assertEquals(
            listOf(2500L, 1250L, 1L, 100L, 100L),
            env.results(),
            "RESULT[0..4] = 总时长毫秒、当前位置毫秒、在播、音量、变速百分比（:8811-8815）",
        )
    }

    @Test
    fun `GETSOUNDORBGMINFO 选择子一到五与非法选择子`() {
        val (env, rig) = wired()
        val stream = playOn(rig, "a.mp3", 2.0)
        stream.advance(0.5)
        assertEquals(2000L, env.int("GETSOUNDORBGMINFO", 0, 1))
        assertEquals(500L, env.int("GETSOUNDORBGMINFO", 0, 2))
        assertEquals(1L, env.int("GETSOUNDORBGMINFO", 0, 3))
        assertEquals(100L, env.int("GETSOUNDORBGMINFO", 0, 4))
        assertEquals(100L, env.int("GETSOUNDORBGMINFO", 0, 5))
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", 0, 0), "选择子 0 落到 default 分支（:8825）")
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", 0, 6))
    }

    @Test
    fun `时长毫秒换算沿用向零截断`() {
        // 原版 (long)(GetTotalTime() * 1000.0)（:8811）是向零截断，不是四舍五入。
        // 期望值 123 / 2999 由独立核对给出（JDK17 jshell 的 (long)(0.1234*1000.0) 与
        // (long)(2.9999999*1000.0) 同为 123 / 2999）。
        val (env, rig) = wired()
        playOn(rig, "a.mp3", 0.1234)
        assertEquals(123L, env.int("GETSOUNDORBGMINFO", 0))
        val (env2, rig2) = wired()
        playOn(rig2, "b.mp3", 2.9999999)
        assertEquals(2999L, env2.int("GETSOUNDORBGMINFO", 0))
    }

    @Test
    fun `越界声道与缺失 Bgm 都返回零且不写 RESULT`() {
        // spec: FunctionMethodCreator.cs:8805-8808（sound == null ⇒ 直接返回 0，不碰 RESULT_ARRAY）
        val (env, rig) = wired()
        env.seedResult(0, 999L)
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", 20), "越界")
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", -2), "既不是 -1 也不在表内")
        rig.table.reset() // 造出 GlobalStatic.cs:69-72 的 Bgm == null 态
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", -1), "reset 后 Bgm 为空")
        assertEquals(999L, env.result(0), "无快照时不得改写 RESULT")
    }

    // ------------------------------------------------------------------
    // SOUNDCONTROL / BGMCONTROL 经由模型
    // ------------------------------------------------------------------

    @Test
    fun `SOUNDCONTROL 暂停后 ISPLAYINGSOUND 读不到 继续后又读得到`() {
        // spec: Sound.cs:171-178（暂停即不在播）+ FunctionMethodCreator.cs:8866-8870
        val (env, rig) = wired()
        playOn(rig, "a.mp3", 5.0)
        assertEquals(0L, env.int("ISPLAYINGSOUND", 0), "在播时返回该下标本身（:8866-8869）")
        assertEquals(1L, env.int("SOUNDCONTROL", 0, 0), "操作码 0 = 暂停，返回 1（:8920-8922）")
        assertEquals(-1L, env.int("ISPLAYINGSOUND", 0))
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", 0, 3), "在播标志读 0（:8822）")
        assertTrue(rig.table.channelAt(0)!!.paused)

        assertEquals(1L, env.int("SOUNDCONTROL", 0, 1), "操作码 1 = 继续（:8923-8925）")
        assertEquals(0L, env.int("ISPLAYINGSOUND", 0))
        assertEquals(1L, env.int("GETSOUNDORBGMINFO", 0, 3))
    }

    @Test
    fun `SOUNDCONTROL 停止后时长读零 但变速率与音量保留`() {
        // spec: Sound.cs:151-163（stop 释放流、不拆变速链路、不清音量）
        val (env, rig) = wired()
        playOn(rig, "a.mp3", 5.0)
        assertEquals(1L, env.int("SOUNDCONTROL", 0, 3, 200), "先设成 2 倍速")
        assertEquals(200L, env.int("GETSOUNDORBGMINFO", 0, 5))
        assertEquals(1L, env.int("SOUNDCONTROL", 0, 2), "操作码 2 = 停止（:8926-8928）")
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", 0, 1), "总时长随 stream 一起没了（:8820）")
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", 0, 2))
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", 0, 3), "停止后不在播")
        assertEquals(200L, env.int("GETSOUNDORBGMINFO", 0, 5), "变速率被 stop 保留（varispeedProvider 不拆）")
        assertEquals(100L, env.int("GETSOUNDORBGMINFO", 0, 4), "音量缺省 100（Sound.cs:13 + :191）")
    }

    @Test
    fun `SOUNDCONTROL 在未播过的声道上设速返回一但无副作用`() {
        // spec: FunctionMethodCreator.cs:8941-8943 恒返回 1；Sound.cs:196 的守卫让 setSpeed 整条 no-op
        val (env, rig) = wired()
        assertEquals(1L, env.int("SOUNDCONTROL", 0, 3, 29))
        assertEquals(100L, env.int("GETSOUNDORBGMINFO", 0, 5), "变速链路还没建，读回仍是 1.0 倍")
        assertFalse(rig.table.channelAt(0)!!.varispeedReady)
        // 一旦真的播上，链路才建起来（Sound.cs:115）
        playOn(rig, "a.mp3", 1.0)
        assertEquals(1L, env.int("SOUNDCONTROL", 0, 3, 29))
        assertEquals(28L, env.int("GETSOUNDORBGMINFO", 0, 5), "binary32 往返损失；期望表见 SoundChannelTest")
    }

    @Test
    fun `SOUNDCONTROL 第四参非零表示关闭音高保留`() {
        // spec: FunctionMethodCreator.cs:8937-8942（第 4 参 != 0 ⇒ preservePitch = false）
        val (env, rig) = wired()
        playOn(rig, "a.mp3", 1.0)
        assertTrue(rig.table.channelAt(0)!!.preservePitch, "缺省 true（Sound.cs:21）")
        assertEquals(1L, env.int("SOUNDCONTROL", 0, 3, 120, 1))
        assertFalse(rig.table.channelAt(0)!!.preservePitch)
        assertEquals(1L, env.int("SOUNDCONTROL", 0, 3, 120, 0))
        assertTrue(rig.table.channelAt(0)!!.preservePitch)
        assertEquals(120L, env.int("GETSOUNDORBGMINFO", 0, 5))
    }

    @Test
    fun `BGMCONTROL 合法操作码会把 reset 掉的 Bgm 建回来`() {
        // spec: FunctionMethodCreator.cs:8996-8998（先建再分派）+ GlobalStatic.cs:69-73（reset 置空）
        val (env, rig) = wired()
        rig.table.reset()
        assertNull(rig.table.bgm)
        assertEquals(1L, env.int("BGMCONTROL", 2), "操作码 2 = 停止（:9010-9012），返回 1")
        assertIs<SoundChannel>(rig.table.bgm, "合法操作码一定会把 Bgm 建回来（:8996-8998 在分派之前）")
        assertEquals(100L, env.int("GETSOUNDORBGMINFO", -1, 4), "新建 Sound 的音量缺省 100")
        assertEquals(100L, env.int("GETSOUNDORBGMINFO", -1, 5), "变速链路还没建 ⇒ 1.0 倍")
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", -1, 3), "还没播 ⇒ 不在播")
        assertEquals(0L, env.int("ISPLAYINGBGM"))
    }

    @Test
    fun `非法操作码也会先物化再分派`() {
        // 原版：SOUNDCONTROL 与 BGMCONTROL 都在**判操作码之前**就 `new Sound()`
        //   （FunctionMethodCreator.cs:8912-8915、:8996-8998）；越界判定才在建道之前
        //   （:8908-8911）。M4-J j-dispatch 把分派切到 AudioMethods 后，本用例按原版落定：
        //   旧实现（曾挂 DebugMiscMethods）推迟建道，其注释自己写了「原版此处已有对象」。
        // ERB 可观测面：`BGMCONTROL 4` 之后 `GETSOUNDORBGMINFO -1`（无第 2 参）会写
        //   RESULT[0..4] = 0,0,0,0,100 并返回 RESULT[0] = 0
        //   （:8809-8817 + Sound.cs:13 的 volume = 1f ⇒ getVolume() = 100）。
        val (env, rig) = wired()
        env.seedResult(4, 7L)
        rig.table.reset()
        assertEquals(-2L, env.int("BGMCONTROL", 4), "非法操作码返回 -2（:9013-9014）")
        assertIs<SoundChannel>(rig.table.bgm, "原版在分派之前就建 Bgm（:8996-8998）")
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", -1), "有对象 ⇒ 返回 RESULT[0] = 总时长毫秒，未播即 0")
        assertEquals(100L, env.result(4), "五项写进 RESULT[0..4]，第 5 项是变速百分比（新建声道 1.0 倍 ⇒ 100）")

        assertEquals(-2L, env.int("SOUNDCONTROL", 0, 4), "声道侧的非法操作码同样返回 -2（:8929-8930）")
        assertIs<SoundChannel>(rig.table.channelAt(0), "合法下标先物化再分派（原版 :8912-8915）")
        // 声道 5 是**合法下标**：原数组定长 10（GlobalStatic.cs:42），`>= Length` 才越界
        // （:8908-8911）。j-dispatch 切到 AudioMethods 后按 10 长度判定，本用例随之重钉：
        // 恰好 2 参 + 操作码 0 = pause ⇒ 返回 1，且 :8912-8915 已把该声道建出来。
        assertEquals(1L, env.int("SOUNDCONTROL", 5, 0), "合法下标 + 操作码 0 = pause ⇒ 1（:8916-8921）")
        assertIs<SoundChannel>(rig.table.channelAt(5), "合法下标先物化（:8912-8915），越界判定早已通过")
        // 真正越界的那一侧仍然**不**物化：判定早于建道（:8908-8911）
        assertEquals(-1L, env.int("SOUNDCONTROL", 10, 0), "下标 10 == Length ⇒ 越界返回 -1（:8908-8911）")
        assertNull(rig.table.channelAt(10), "越界返回 -1 且不建道（:8908-8911 早于 :8912-8915）")
    }

    @Test
    fun `BGMCONTROL 变速 暂停 继续 停止`() {
        val (env, rig) = wired()
        val source = FakeSoundSource(mapOf("bgm.mp3" to 60.0))
        assertTrue(rig.table.playBgm("bgm.mp3", source))
        assertEquals(1L, env.int("ISPLAYINGBGM"), "PLAYBGM 后 ISPLAYINGBGM = 1（:8960-8963）")
        assertEquals(60000L, env.int("GETSOUNDORBGMINFO", -1, 1))
        assertEquals(1L, env.int("BGMCONTROL", 3, 150), "变速：1.5 倍（:9017-9027）")
        assertEquals(150L, env.int("GETSOUNDORBGMINFO", -1, 5))
        assertEquals(1L, env.int("BGMCONTROL", 0), "暂停")
        assertEquals(0L, env.int("ISPLAYINGBGM"))
        assertEquals(1L, env.int("BGMCONTROL", 1), "继续")
        assertEquals(1L, env.int("ISPLAYINGBGM"))
        assertEquals(1L, env.int("BGMCONTROL", 2), "停止")
        assertEquals(0L, env.int("ISPLAYINGBGM"))
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", -1, 1), "停止后时长读 0")
        assertEquals(150L, env.int("GETSOUNDORBGMINFO", -1, 5), "变速率同样保留")
    }

    // ------------------------------------------------------------------
    // 语句层与函数层共享同一份状态
    // ------------------------------------------------------------------

    @Test
    fun `假 sink 与函数层共用一张表`() {
        val (env, rig) = wired()
        val sink: SoundSink = FakeSoundSink(rig.table, FakeSoundSource(mapOf("x.mp3" to 8.0, "y.mp3" to 8.0)))
        sink.playSound("x.mp3", 1)
        sink.playSound("x.mp3", 1)
        assertEquals(0L, env.int("ISPLAYINGSOUND", 0))
        assertEquals(1L, env.int("ISPLAYINGSOUND", 1))
        assertEquals(-1L, env.int("ISPLAYINGSOUND", 2), "第三个声道还没被用到 ⇒ -1")

        assertEquals(1L, env.int("SOUNDCONTROL", 0, 2), "函数层停声道 0")
        assertEquals(-1L, env.int("ISPLAYINGSOUND", 0))
        assertEquals(1L, env.int("SOUNDCONTROL", 1, 0), "函数层暂停声道 1")
        assertEquals(0, rig.table.playingSoundChannels().size, "语句层与函数层看的是同一份在播集合")
        sink.playSound("y.mp3", 2)
        assertEquals(0L, env.int("ISPLAYINGSOUND", 0), "声道 0 已空 ⇒ 又落回 0")
        assertEquals(8000L, env.int("GETSOUNDORBGMINFO", 0, 1))
        assertEquals(8000L, env.int("GETSOUNDORBGMINFO", 1, 1), "暂停不释放流，时长仍读得到")
    }

    @Test
    fun `适配层不再夹 repeat —— 0xFFFFFFFF 截成 -1 仍是无限循环`() {
        // 原版唯一的一次夹取在语句层（FunctionIdentifier.cs:2621）：
        //   int repeat = (int)(Opt == null ? 1 : Math.Max(Opt.GetIntValue(exm), 1L));
        // Math.Max 在 long 域、截断在 int 域 ⇒ 4294967295 到这里是 -1，
        // 落进 Sound.cs:97-99 的 LoopStream（无限循环）。
        // 适配层若再 maxOf(count, 1)，-1 被吃成 1 ⇒ 无限循环退化成「播一遍」。
        val (_, rig) = wired(channels = 3)
        val sink = FakeSoundSink(rig.table, FakeSoundSource(mapOf("x.mp3" to 8.0)))

        sink.playSound("x.mp3", -1)
        assertEquals(LoopMode.FOREVER, rig.table.channelAt(0)!!.loopPlan.mode, "-1 ⇒ LoopStream")

        // 其余值仍是「一遍」：原版只认 -1（Sound.cs:97-105），-2 也不循环
        sink.playSound("x.mp3", 0)
        assertEquals(LoopMode.ONCE, rig.table.channelAt(1)!!.loopPlan.mode, "0 ⇒ 一遍")
        sink.playSound("x.mp3", -2)
        assertEquals(LoopMode.ONCE, rig.table.channelAt(2)!!.loopPlan.mode, "-2 ⇒ 一遍")
    }

    @Test
    fun `假 sink 的音量指令落到每个声道`() {
        val rig = ChannelAudioHost(SoundChannelTable(channelCount = 3))
        val sink = FakeSoundSink(rig.table, FakeSoundSource(mapOf("a.mp3" to 1.0)))
        sink.setSoundVolume(53)
        for (i in 0 until 3) assertEquals(52, rig.table.channelAt(i)!!.getVolume(), "声道 $i")
        sink.setBgmVolume(53)
        assertEquals(52, rig.table.bgm!!.getVolume())
        assertEquals("setSoundVolume(53)", sink.events.first())
        assertEquals("setBgmVolume(53) -> 已写", sink.events.last())
    }

    @Test
    fun `宿主没接音源时所有播放都静默`() {
        // 对应基准的采集环境：夹具播的是不存在的文件（GOLDEN_09_MISC.ERB:42-47）
        val (env, rig) = wired()
        val sink = FakeSoundSink(rig.table) // 默认空音源表 ⇒ 所有 resolve 都返回 null
        sink.playSound("NO_SUCH_SE_XYZ.mp3", 1)
        sink.playBgm("NO_SUCH_BGM_XYZ.mp3")
        sink.stopSound()
        sink.stopBgm()
        sink.setSoundVolume(100)
        sink.setBgmVolume(100)
        assertEquals(-1L, env.int("ISPLAYINGSOUND", 0))
        assertEquals(0L, env.int("ISPLAYINGBGM"))
        assertTrue(
            rig.table.channels().all { it != null },
            "SETSOUNDVOLUME 仍把十个槽都建了出来（FunctionIdentifier.cs:2733-2736）",
        )
        assertTrue(rig.table.playingSoundChannels().isEmpty())
        assertEquals(
            0L,
            env.int("GETSOUNDORBGMINFO", 0, 1),
            "建了但没播 ⇒ 时长读 0（对照原版 :8805-8808：sound 非 null 就正常读五项）",
        )
        assertEquals(100L, env.int("GETSOUNDORBGMINFO", 0, 5), "倍率缺省 1.0 ⇒ 读 100，不是 0")
    }

    // ------------------------------------------------------------------
    // 红线：与基准 SOUND-MISSING 行同判
    // ------------------------------------------------------------------

    @Test
    fun `未接线宿主仍给出基准的 SOUND-MISSING 读数`() {
        // pc-golden-root.txt:520-521 `EXISTSOUND=0` / `ISPLAYINGBGM=0 ISPLAYINGSOUND=-1`
        val env = AudioEnv(BuiltinHost.DEFAULT)
        assertEquals(0, BuiltinHost.DEFAULT.soundChannels, "0 = 宿主没有音频后端")
        assertNull(BuiltinHost.DEFAULT.audio, "本波没把模型塞进缺省宿主")
        assertFalse(BuiltinHost.DEFAULT.isPlayingSound(0))
        assertFalse(BuiltinHost.DEFAULT.isPlayingBgm())
        assertEquals(0L, env.int("ISPLAYINGBGM"))
        assertEquals(-1L, env.int("ISPLAYINGSOUND", 0))
        assertEquals(-1L, env.int("ISPLAYINGSOUND", 9))
    }

    @Test
    fun `三条音频控制函数在未接线宿主上仍抛 CodeEE 不伪造状态`() {
        // 合同 §0.4 + d-audio 红线：不许把「宿主没接」伪装成「已支持」
        val env = AudioEnv(BuiltinHost.DEFAULT)
        val e = assertFailsWith<CodeEE> { env.int("GETSOUNDORBGMINFO", 0) }
        assertTrue(e.message!!.contains("BuiltinHost.audio"), "实际：${e.message}")
        val e2 = assertFailsWith<CodeEE> { env.int("SOUNDCONTROL", 0, 0) }
        assertTrue(e2.message!!.contains("BuiltinHost.audio"), "实际：${e2.message}")
        val e3 = assertFailsWith<CodeEE> { env.int("BGMCONTROL", 0) }
        assertTrue(e3.message!!.contains("BuiltinHost.audio"), "实际：${e3.message}")
    }

    @Test
    fun `声道数为零的宿主对三条函数的表现与基准一致`() {
        // 把模型接上但把声道数留成 0（= 不移植音频后端时的形态），三条函数都不能读出状态
        val rig = ChannelAudioHost(SoundChannelTable(channelCount = 0))
        val env = AudioEnv(object : BuiltinHost {
            override val soundChannels: Int = 0
            override val audio: AudioHost = rig
            override fun isPlayingSound(channel: Int): Boolean = rig.isPlayingSound(channel)
            override fun isPlayingBgm(): Boolean = rig.isPlayingBgm()
        })
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", 0), "越界 ⇒ 返回 0（FunctionMethodCreator.cs:8805-8808）")
        assertEquals(-1L, env.int("SOUNDCONTROL", 0, 0), "越界 ⇒ -1（:8908-8911）")
        assertEquals(-1L, env.int("ISPLAYINGSOUND", 0))
        // BGMCONTROL 与声道数无关（原版 Bgm 是独立对象，GlobalStatic.cs:44）
        assertEquals(1L, env.int("BGMCONTROL", 2))
    }
}
