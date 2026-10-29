package com.eratw.port.core.audio

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M5-AUDIO 引擎侧宿主接线取证（CONTRACT-M4-F §2 f-sound）。
 *
 * 被测对象 = 未注入 `builtinHost` 时 [ProcessRuntime] 的真实缺省宿主（private class
 * SemConfigHost 的音频四项，ProcessRuntime.kt:1248-1271）。装置形状：一条空 MAIN 的最小
 * ProcessRuntime → 从 `evalContext` 取出缺省宿主本体 → 语句层六条用 [FakeSoundSink]
 * （sem/StatementExecutor.kt:730-748 分派 `ctx.sound.playSound` 等）驱动 `store.aux` 上的
 * 同一张表；函数层读数走 `evalContext`（ISPLAYINGSOUND / ISPLAYINGBGM / SOUNDCONTROL /
 * BGMCONTROL / GETSOUNDORBGMINFO）。
 *
 * 逐条对齐（行号 = 反编译原文出处，与 core/audio/AudioAdapters.kt 的 d-audio 实证记录同源）：
 * - 四项同表：[SoundChannelTable.channelCount] = 原版长度 10（GlobalStatic.cs:42 的
 *   `new Sound[10]`）；ISPLAYINGSOUND 越界 -1（FunctionMethodCreator.cs:8849-8852），
 *   只认「已建 且 在播」且**不惰性建道**（:8866-8870，暂停读不到：Sound.cs:171-178）；
 *   ISPLAYINGBGM 同（:8960-8964，Bgm 出厂非空 GlobalStatic.cs:44）；[ChannelAudioHost]
 *   视图覆盖 GETSOUNDORBGMINFO（:8769-8827）/ SOUNDCONTROL（:8874-8946）/ BGMCONTROL（:8968+）。
 * - PLAYSOUND：文件不存在静默（FunctionIdentifier.cs:2625-2628）、从头扫第一个
 *   `!isPlaying()` 的槽（:2630-2636，**暂停算空闲**）、全忙回绕 0（:2641-2644）。
 * - PLAYBGM 固定 repeat = -1（:2696）；STOPBGM（:2716）与 SETBGMVOLUME（:2753）直接
 *   解引用 Bgm、不判空——原版是 NullReferenceException 硬崩，移植侧由表侧显式表态
 *   （返回 false / 事件流记「无BGM」不静默当成功），登记在 SoundChannelTable.kt:25-27 与
 *   AudioAdapters.kt:128-131、:139-142。
 *
 * 基准同判（fixtures/golden/baseline/pc-golden-root.txt:520-521 的
 * `ISPLAYINGBGM=0 ISPLAYINGSOUND=-1`，采集夹具 GOLDEN_09_MISC.ERB:41-52 播不存在的文件）：
 * E2E 与 bootSmoke 管线显式注入自己的 builtinHost（GoldenPipeline.kt:829-839、
 * GameBootSmoke.kt:228-238），四项仍取接口缺省（Builtins.kt:236/246/253/376 一字未动）；
 * 本波只给「未注入」时的缺省宿主接表 ⇒ E2E 610 行与基准 SOUND-MISSING 读数不受影响；
 * 未播状态下缺省宿主读表给出的同样还是 -1 / 0（「查询不建道」「reset 后」两条用例钉住）。
 */
class SoundHostWiringTest {

    // ------------------------------------------------------------------
    // 装置：空 MAIN 的最小 ProcessRuntime + 真实缺省宿主
    // ------------------------------------------------------------------

    private class Rig(val store: VariableStore, val rt: ProcessRuntime) {
        /** 缺省宿主本体（[BuiltinEvalContext] 的 host 槽；builtinHost 未注入时即 SemConfigHost）。 */
        val host: BuiltinHost = (rt.evalContext as BuiltinEvalContext).host

        /** 与宿主四项同源的 aux 表（测试按 [soundChannelTable] 契约自取，用于直读断言）。 */
        val table: SoundChannelTable get() = soundChannelTable(store.aux)

        fun call(name: String, args: List<Value?> = emptyList()): Value =
            rt.evalContext.callFunction(name, args)

        fun int(name: String, vararg args: Long): Long =
            (call(name, args.map { Value.IntV(it) }) as Value.IntV).v
    }

    private fun rig(store: VariableStore = VariableStore(), builtinHost: BuiltinHost? = null): Rig {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("MAIN", 0), emptyList())
        val main = c.functions.first { it.sig.name.equals("MAIN", ignoreCase = true) }
        val rt = ProcessRuntime(
            c.build("MAIN"), store,
            FunctionTable(listOf(FuncMeta(index = main.index, key = "MAIN", name = "MAIN"))),
            VmConfig(), builtinHost = builtinHost,
        )
        return Rig(store, rt)
    }

    // ------------------------------------------------------------------
    // 同源单表
    // ------------------------------------------------------------------

    @Test
    fun `同一 store 两次取到同一张表 且缺省宿主四项同源`() {
        val r = rig()
        // 表身份 = aux 槽单例（SoundChannelTable.kt:258-259 / RuntimeAux.kt:24-29）
        assertSame(r.table, soundChannelTable(r.store.aux), "同一 store 两次取到同一实例")
        assertSame(r.table, r.store.aux.peek<SoundChannelTable>(AUDIO_CHANNEL_SLOT), "就是 audio.channels 槽")
        assertNotSame(r.table, soundChannelTable(VariableStore().aux), "两个 store 不得串味")

        // 缺省宿主已接线（不再是 BuiltinHost.DEFAULT 的四项缺省）
        assertNotSame(BuiltinHost.DEFAULT, r.host)
        assertEquals(SoundChannelTable.DEFAULT_CHANNEL_COUNT, r.host.soundChannels, "GlobalStatic.cs:42 = 10")
        assertNotNull(r.host.audio, "audio 视图已接（ChannelAudioHost）")

        // 同表直证：函数面惰性建道（GETSOUNDORBGMINFO 合法下标，FunctionMethodCreator.cs:8799-8802）
        // 建出来的声道必须写回 aux 的同一张表，才能被 PLAYSOUND 侧看见
        assertEquals(0L, r.int("GETSOUNDORBGMINFO", 0))
        assertNotNull(r.table.channelAt(0), "host.audio 建的声道就是 aux 表里的同一条")
    }

    @Test
    fun `soundChannels 取自表长度 而不是写死十`() {
        // 接线口径：soundChannels = 该表的 channelCount（不是常量 10）——
        // 预置一张 2 声道的表进 aux 槽，再建运行时，宿主必须报 2，
        // 越界判定（FunctionMethodCreator.cs:8849-8852）随之落在表长度上。
        val store = VariableStore()
        soundChannelTable(store.aux, channelCount = 2)
        val r = rig(store = store)
        assertEquals(2, r.host.soundChannels)
        assertEquals(-1L, r.int("ISPLAYINGSOUND", 1), "表内空槽 ⇒ -1")
        assertEquals(-1L, r.int("ISPLAYINGSOUND", 2), "越界 -1 跟着表长度走（:8849-8852）")
    }

    @Test
    fun `注入宿主优先 缺省音频绑定只作用于未注入时的宿主`() {
        // 显式注入的宿主四项仍走它自己的实现；不覆写时是 0/false/null —— 即基准
        // SOUND-MISSING 行（pc-golden-root.txt:520-521）依赖的形态
        val injected = object : BuiltinHost {}
        val r = rig(builtinHost = injected)
        // 装配层会套一层用户函数包装（GETMETH/EXISTMETH 需要），但音频四项仍委托注入宿主：
        // 下面逐项断言行为，不再要求宿主对象同一性。
        assertEquals(0, r.host.soundChannels)
        assertNull(r.host.audio)
        assertFalse(r.host.isPlayingSound(0))
        assertFalse(r.host.isPlayingBgm())
        assertEquals(-1L, r.int("ISPLAYINGSOUND", 0))
        assertEquals(0L, r.int("ISPLAYINGBGM"))
    }

    @Test
    fun `四项未读到之前 aux 不建表`() {
        // 取表现读：构造 ProcessRuntime / 读其它内建都不触碰音频槽；首次读音频四项才建表
        val r = rig()
        assertNull(r.store.aux.peek<SoundChannelTable>(AUDIO_CHANNEL_SLOT), "未读前无表")
        assertFalse(r.host.isPlayingBgm())
        assertNotNull(r.store.aux.peek<SoundChannelTable>(AUDIO_CHANNEL_SLOT), "读到即建（aux 槽单例）")
    }

    // ------------------------------------------------------------------
    // 语句层（FakeSoundSink）→ 函数层（缺省宿主）同表
    // ------------------------------------------------------------------

    @Test
    fun `PLAYSOUND 后 ISPLAYINGSOUND 读得到 且 soundChannels 不变`() {
        val r = rig()
        val before = r.host.soundChannels
        assertEquals(SoundChannelTable.DEFAULT_CHANNEL_COUNT, before)

        val sink = FakeSoundSink(r.table, FakeSoundSource(mapOf("se.mp3" to 4.0)))
        sink.playSound("se.mp3", 1)
        assertEquals("playSound(se.mp3, 1) -> ch0", sink.events.last())

        // 函数层立刻读得到（同一张表；:8866-8870 返回声道号本身）
        assertEquals(0L, r.int("ISPLAYINGSOUND", 0))
        assertEquals(-1L, r.int("ISPLAYINGSOUND", 1))

        // 原版 Sound.Length 恒 10（GlobalStatic.cs:42）：播起声音不改长度
        assertEquals(before, r.host.soundChannels)

        // audio 视图读到的就是这条在播流（同表第三证）
        val info = r.host.audio!!.soundInfo(0)
        assertTrue(info.playing)
        assertEquals(4000L, (info.totalSeconds * 1000.0).toLong())
    }

    @Test
    fun `暂停的声道算空闲 会被下一条 PLAYSOUND 抢占`() {
        val r = rig()
        val sources = FakeSoundSource(mapOf("a.mp3" to 3.0, "b.mp3" to 5.0))
        val sink = FakeSoundSink(r.table, sources)

        sink.playSound("a.mp3", 1)
        assertEquals("playSound(a.mp3, 1) -> ch0", sink.events.last())
        assertEquals(0L, r.int("ISPLAYINGSOUND", 0))

        // 函数层暂停 ch0（SOUNDCONTROL 操作码 0 = pause，:8920-8922）
        assertEquals(1L, r.int("SOUNDCONTROL", 0, 0))
        assertEquals(-1L, r.int("ISPLAYINGSOUND", 0), "暂停中的声道读不到（Sound.cs:171-178）")

        // 选道用的是 !isPlaying()（:2636）⇒ 暂停槽算空闲，新 PLAYSOUND 落回 0
        sink.playSound("b.mp3", 1)
        assertEquals("playSound(b.mp3, 1) -> ch0", sink.events.last(), "暂停声道被抢占（:2636）")
        assertEquals(0L, r.int("ISPLAYINGSOUND", 0), "抢占后重新在播")
        assertEquals(5000L, r.int("GETSOUNDORBGMINFO", 0, 1), "流已换 b.mp3")
        assertTrue(sources.opened.first().released, "被抢占声道上的 a.mp3 流随 play() 开头的 stop() 释放（Sound.cs:82）")
    }

    @Test
    fun `PLAYBGM 后 ISPLAYINGBGM 读得到 BGMCONTROL 暂停归零`() {
        val r = rig()
        val sink = FakeSoundSink(r.table, FakeSoundSource(mapOf("bgm.mp3" to 60.0)))
        sink.playBgm("bgm.mp3")
        assertEquals("playBgm(bgm.mp3) -> 已播", sink.events.last())

        assertEquals(1L, r.int("ISPLAYINGBGM"), "PLAYBGM 后 1（:8960-8963）")
        assertEquals(60000L, r.int("GETSOUNDORBGMINFO", -1, 1))
        assertTrue(r.table.bgm!!.looping, "PLAYBGM 固定 repeat=-1（:2696）⇒ 循环流")

        assertEquals(1L, r.int("BGMCONTROL", 0), "暂停")
        assertEquals(0L, r.int("ISPLAYINGBGM"))
        assertEquals(1L, r.int("BGMCONTROL", 1), "继续")
        assertEquals(1L, r.int("ISPLAYINGBGM"))
    }

    // ------------------------------------------------------------------
    // 查询不惰性建道 / 静默路径 / null 表态
    // ------------------------------------------------------------------

    @Test
    fun `ISPLAYINGSOUND 与 ISPLAYINGBGM 查询不惰性建道`() {
        val r = rig()
        r.table.reset() // 全空 + Bgm == null 的极端态（GlobalStatic.cs:58-73 的 Reset 镜像）
        assertTrue(r.table.channels().all { it == null })
        assertNull(r.table.bgm)
        val before = r.table.channels()

        for (ch in 0 until SoundChannelTable.DEFAULT_CHANNEL_COUNT) {
            assertEquals(-1L, r.int("ISPLAYINGSOUND", ch.toLong()))
        }
        assertEquals(0L, r.int("ISPLAYINGBGM"))
        assertEquals(-1L, r.int("ISPLAYINGSOUND", -1), "负下标 -1（:8849-8852）")
        assertEquals(-1L, r.int("ISPLAYINGSOUND", 10), "越界 -1（:8849-8852）")
        assertFalse(r.host.isPlayingSound(3), "宿主查询同样不建")
        assertFalse(r.host.isPlayingBgm())

        assertEquals(before, r.table.channels(), "查询前后槽位不许被物化（:8866、:8960）")
        assertNull(r.table.bgm, "ISPLAYINGBGM 不建 Bgm（:8960 只判 null）")
    }

    @Test
    fun `文件不存在时 PLAYSOUND 与 PLAYBGM 静默 不物化声道`() {
        // 与基准采集环境同判：GOLDEN_09_MISC.ERB:41-52 播的全是不存在的文件
        val r = rig()
        val sink = FakeSoundSink(r.table) // 默认空音源 ⇒ resolve 恒 null
        sink.playSound("NO_SUCH_SE_XYZ.mp3", 1)
        sink.playBgm("NO_SUCH_BGM_XYZ.mp3")
        assertEquals("playSound(NO_SUCH_SE_XYZ.mp3, 1) -> 未播", sink.events[0])
        assertEquals("playBgm(NO_SUCH_BGM_XYZ.mp3) -> 未播", sink.events[1])
        assertTrue(r.table.channels().all { it == null }, "文件不存在直接 return，不建声道（:2625-2628）")
        assertEquals(-1L, r.int("ISPLAYINGSOUND", 0))
        assertEquals(0L, r.int("ISPLAYINGBGM"))
    }

    @Test
    fun `reset 后的 STOPBGM 不静默当成功`() {
        // 原版 :2716 直接解引用 Bgm（无判空、无 try）：Bgm == null 时是 NRE 硬崩。
        // GlobalStatic.Reset 在反编译目录里没有调用点（SoundChannelTable.kt:25-27 的
        // TODO(verify)），故 Bgm == null 只由本仓 reset() 造出；移植侧不静默当成功：
        // 表返回 false、假 sink 记「无BGM」（AudioAdapters.kt:129-131）。
        val r = rig()
        r.table.reset()
        val sink = FakeSoundSink(r.table)
        sink.stopBgm()
        assertEquals("stopBgm -> 无BGM", sink.events.last())
        assertEquals(0L, r.int("ISPLAYINGBGM"))
    }
}