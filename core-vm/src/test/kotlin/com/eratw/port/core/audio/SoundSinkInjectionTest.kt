package com.eratw.port.core.audio

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.NoopSoundSink
import com.eratw.port.core.host.SoundSink
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.SpHtmlPrint
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M5-A AUDIO-SINK：语句层音频 sink 的生产注入点（`ProcessRuntime.soundSink` →
 * `ExecContext.sound`）与 [TableSoundSink] 的端到端取证。
 *
 * 装置形状：空 MAIN 的最小 [ProcessRuntime] + 注入 [TableSoundSink] 的 aux 构造
 * （`TableSoundSink(store.aux, resolver)`）；语句走 [ProcessRuntime.executeStatement]
 * （与 VM marker 同一条 sem 分派路径，StatementExecutor.kt:730-748），函数层读数走
 * `evalContext`（未注入 builtinHost 时的缺省宿主 SemConfigHost，四项读同一张 aux 表）。
 *
 * 逐条对齐（行号 = 反编译原文，与 SoundChannelTable.kt / AudioAdapters.kt 的 KDoc 同源）：
 * - PLAYSOUND：repeat = Math.Max(实参, 1)（FunctionIdentifier.cs:2621）；文件不存在静默
 *   （:2625-2628）；从 0 起扫第一个 `!isPlaying()` 的槽、沿途补 null、暂停算空闲、全忙回绕 0
 *   （:2629-2644）；每次播放重开解码流（Sound.cs:83-96）。
 * - STOPSOUND：全声道补 null 后只停 `isPlaying()`（:2664-2674）；暂停的通道不满足
 *   `isPlaying()`（Sound.cs:171-178），不被 stop。
 * - PLAYBGM：固定 repeat = -1（循环，:2696）；STOPBGM 只 stop 不置 null（:2716 +
 *   GlobalStatic.cs:44 出厂即建）。
 * - 解码失败：解析器抛异常 → CodeEE `非対応のサウンドファイルが指定されました`
 *   （:2647-2650、:2699-2702，Lang.cs:3994）。
 * - SETSOUNDVOLUME：逐声道广播、沿途补 null（:2731-2738）；SETBGMVOLUME 只写 Bgm（:2753）。
 * - 红线：缺省 `soundSink` 仍是 [NoopSoundSink]，六条全空、不碰 aux 音频槽 ⇒ 基准
 *   SOUND-MISSING 读数（fixtures/golden/baseline/pc-golden-root.txt:519-522）不变。
 */
class SoundSinkInjectionTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 最小运行时 + 注入的 sink + 与内建宿主四项同源的 aux 表。 */
    private class Rig(
        val store: VariableStore,
        val sources: FakeSoundSource,
        val sink: SoundSink,
        val rt: ProcessRuntime,
    ) {
        /** 未注入 builtinHost 时 [ProcessRuntime] 的缺省宿主（音频四项读 store.aux 的同表）。 */
        val host: BuiltinHost = (rt.evalContext as BuiltinEvalContext).host

        /** 与 sink 共用的 aux 表（首读建表，与 SemConfigHost 的取表点同源）。 */
        val table: SoundChannelTable get() = soundChannelTable(store.aux)

        fun int(name: String, vararg args: Long): Long =
            (rt.evalContext.callFunction(name, args.map { Value.IntV(it) }) as Value.IntV).v

        fun run(stmt: Statement) = rt.executeStatement(stmt, TestEnv())
    }

    /** VM 复用同一个可变 StmtEnv；测试直接调用钩子时自建只读实现。 */
    private class TestEnv : StmtEnv {
        override val functionKey: String = "MAIN"
        override val pc: Int = -1
        override val line: Int = -1
        override val functionName: String? = "main"
    }

    /** 空 MAIN 的最小运行时；[sink] 缺省 = [NoopSoundSink]（与被测的注入点同一条构造路径）。 */
    private fun runtime(
        store: VariableStore = VariableStore(),
        sink: SoundSink = NoopSoundSink,
    ): ProcessRuntime = ProcessRuntime(
        Assembler.assemble("_HALT"),
        store,
        FunctionTable(emptyList()),
        VmConfig(),
        soundSink = sink,
    )

    private fun rig(files: Map<String, Double> = emptyMap()): Rig {
        val store = VariableStore()
        val sources = FakeSoundSource(files)
        val sink = TableSoundSink(store.aux, sources)
        return Rig(store, sources, sink, runtime(store, sink))
    }

    private fun playSoundStmt(name: String, repeat: Long? = null): Statement = InstructionStmt(
        FunctionCode.PLAYSOUND,
        SpHtmlPrint(StrLiteral(name), repeat?.let { IntLiteral(it) }),
        1,
        "PLAYSOUND \"$name\"",
    )

    private fun stopSoundStmt(): Statement =
        InstructionStmt(FunctionCode.STOPSOUND, VoidArgument(), 1, "STOPSOUND")

    private fun playBgmStmt(name: String): Statement =
        InstructionStmt(FunctionCode.PLAYBGM, ExpressionArgument(StrLiteral(name)), 1, "PLAYBGM \"$name\"")

    private fun stopBgmStmt(): Statement =
        InstructionStmt(FunctionCode.STOPBGM, VoidArgument(), 1, "STOPBGM")

    private fun setSoundVolumeStmt(volume: Long): Statement =
        InstructionStmt(FunctionCode.SETSOUNDVOLUME, ExpressionArgument(IntLiteral(volume)), 1, "SETSOUNDVOLUME $volume")

    private fun setBgmVolumeStmt(volume: Long): Statement =
        InstructionStmt(FunctionCode.SETBGMVOLUME, ExpressionArgument(IntLiteral(volume)), 1, "SETBGMVOLUME $volume")

    // ------------------------------------------------------------------
    // 注入点
    // ------------------------------------------------------------------

    @Test
    fun `注入的 sink 真到 ExecContext_sound 缺省仍是 Noop`() {
        val w = rig(mapOf("se.mp3" to 1.0))
        assertSame(w.sink, w.rt.execContext.sound, "构造参数 soundSink 原样落到 ExecContext.sound")

        // 缺省 = NoopSoundSink：六条语句全空实现，不碰 aux 音频槽
        val store = VariableStore()
        val plain = runtime(store)
        assertSame(NoopSoundSink, plain.execContext.sound)
        plain.executeStatement(playSoundStmt("se.mp3", 1), TestEnv())
        plain.executeStatement(stopSoundStmt(), TestEnv())
        plain.executeStatement(playBgmStmt("bgm.mp3"), TestEnv())
        plain.executeStatement(stopBgmStmt(), TestEnv())
        plain.executeStatement(setSoundVolumeStmt(70), TestEnv())
        plain.executeStatement(setBgmVolumeStmt(70), TestEnv())
        assertNull(store.aux.peek<SoundChannelTable>(AUDIO_CHANNEL_SLOT), "Noop 不碰 aux 音频槽")

        // 基准 SOUND-MISSING 同判（pc-golden-root.txt:520-521）：函数层仍读不到任何在播状态
        fun int(name: String, vararg args: Long): Long =
            (plain.evalContext.callFunction(name, args.map { Value.IntV(it) }) as Value.IntV).v
        assertEquals(-1L, int("ISPLAYINGSOUND", 0))
        assertEquals(0L, int("ISPLAYINGBGM"))
    }

    // ------------------------------------------------------------------
    // 六条语句 → 表
    // ------------------------------------------------------------------

    @Test
    fun `PLAYSOUND 选空闲声道 且 repeat 至少一`() {
        val w = rig(mapOf("se.mp3" to 3.0))
        w.run(playSoundStmt("se.mp3", 3))
        assertEquals(0L, w.int("ISPLAYINGSOUND", 0), "第一条 PLAYSOUND 从 0 起选道（:2629-2636）")
        assertFalse(w.table.channelAt(0)!!.looping, "repeat=3 ⇒ RepeatStream，不是循环（Sound.cs:102-105）")
        assertEquals(3000L, w.int("GETSOUNDORBGMINFO", 0, 1), "流时长读得到（同表）")

        w.run(playSoundStmt("se.mp3", 1))
        assertEquals(1L, w.int("ISPLAYINGSOUND", 1), "第二条落到下一个空闲声道")

        w.run(playSoundStmt("se.mp3", 0))
        assertEquals(2L, w.int("ISPLAYINGSOUND", 2), "repeat 0 被夹到 1 仍会播（:2621）")
        assertFalse(w.table.channelAt(2)!!.looping)
        assertEquals(3, w.sources.opened.size, "每次 PLAYSOUND 都重新打开解码流（Sound.cs:83-96）")

        w.run(playSoundStmt("missing.mp3", 1))
        assertNull(w.table.channelAt(3), "文件不存在直接 return，选道之前就退出（:2625-2628）")
        assertEquals(3, w.sources.opened.size, "解析失败不开流")
    }

    @Test
    fun `STOPSOUND 只停播放中的声道 暂停的保持原状`() {
        val w = rig(mapOf("a.mp3" to 5.0, "b.mp3" to 5.0))
        w.run(playSoundStmt("a.mp3", 1))
        w.run(playSoundStmt("b.mp3", 1))
        assertEquals(1L, w.int("SOUNDCONTROL", 1, 0), "函数层暂停声道 1（操作码 0 = pause）")
        val playing = w.table.channelAt(0)!!.decoder as FakeChannelDecoder
        val paused = w.table.channelAt(1)!!.decoder as FakeChannelDecoder

        w.run(stopSoundStmt())
        assertTrue(playing.released, "在播的流被 stop 释放（Sound.cs:158-161）")
        assertFalse(paused.released, "暂停的 isPlaying() == false，STOPSOUND 不碰它（:2670-2673）")
        assertTrue(w.table.channelAt(1)!!.paused, "暂停态原样保留")
        assertEquals(
            SoundChannelTable.DEFAULT_CHANNEL_COUNT,
            w.table.channels().count { it != null },
            "STOPSOUND 仍把十个槽都建出来（:2666-2669）",
        )
        assertTrue(w.table.playingSoundChannels().isEmpty())
    }

    @Test
    fun `PLAYBGM 固定循环 STOPBGM 停掉后对象保留`() {
        val w = rig(mapOf("bgm.mp3" to 60.0))
        w.run(playBgmStmt("bgm.mp3"))
        val bgm = w.table.bgm!!
        assertTrue(bgm.looping, "PLAYBGM 固定 repeat=-1（:2696）⇒ LoopStream（Sound.cs:98-101）")
        assertEquals(1L, w.int("ISPLAYINGBGM"))
        assertEquals(60000L, w.int("GETSOUNDORBGMINFO", -1, 1))
        val decoder = bgm.decoder as FakeChannelDecoder

        w.run(stopBgmStmt())
        assertTrue(decoder.released, "stop 释放流（Sound.cs:158-161）")
        assertFalse(bgm.playing, "stop 摘掉混音器（Sound.cs:155）")
        assertEquals(0L, w.int("ISPLAYINGBGM"))
        assertSame(bgm, w.table.bgm, "STOPBGM 只 stop 不置 null（:2716 + GlobalStatic.cs:44）")
    }

    @Test
    fun `解析异常转 CodeEE 不建道 两条播放同判`() {
        // 原版把建流与播放整段包在裸 catch 里转 CodeEE（FunctionIdentifier.cs:2647-2650、:2699-2702）
        val store = VariableStore()
        val throwing = SoundSourceResolver { throw java.io.IOException("bad wave") }
        val sink = TableSoundSink(store.aux, throwing)
        val rt = runtime(store, sink)
        val env = TestEnv()

        val e1 = assertFailsWith<CodeEE> { rt.executeStatement(playSoundStmt("bad.mp3", 1), env) }
        assertTrue(e1.message!!.contains("非対応のサウンドファイルが指定されました"), "实际：${e1.message}")
        assertTrue(soundChannelTable(store.aux).channels().all { it == null }, "解析失败发生在选道之前 ⇒ 不建道")

        val e2 = assertFailsWith<CodeEE> { rt.executeStatement(playBgmStmt("bad.mp3"), env) }
        assertTrue(e2.message!!.contains("非対応のサウンドファイルが指定されました"), "实际：${e2.message}")
        assertFalse(soundChannelTable(store.aux).isPlayingBgm())
    }

    @Test
    fun `SETSOUNDVOLUME 广播全声道 SETBGMVOLUME 只写 Bgm`() {
        val w = rig(mapOf("se.mp3" to 1.0))
        w.run(playSoundStmt("se.mp3", 1))
        w.run(setSoundVolumeStmt(53))
        assertEquals(
            SoundChannelTable.DEFAULT_CHANNEL_COUNT,
            w.table.channels().count { it != null },
            "沿途补 null（:2733-2736）",
        )
        for (i in 0 until SoundChannelTable.DEFAULT_CHANNEL_COUNT) {
            assertEquals(52, w.table.channelAt(i)!!.getVolume(), "声道 $i：53 的 binary32 读回 52（Sound.cs:182 + :191）")
        }
        assertEquals(
            SoundChannel.DEFAULT_VOLUME,
            w.table.bgm!!.getVolume(),
            "SETSOUNDVOLUME 不动 Bgm（Bgm 的音量只有 :2753 会写）",
        )

        w.run(setBgmVolumeStmt(40))
        assertEquals(40, w.table.bgm!!.getVolume())
        assertEquals(52, w.table.channelAt(0)!!.getVolume(), "SETBGMVOLUME 不动声道")
    }

    // ------------------------------------------------------------------
    // 与内建宿主四项同表
    // ------------------------------------------------------------------

    @Test
    fun `sink 与 soundChannelTable 是同一张表 isPlayingSound 随播放变化`() {
        val w = rig(mapOf("se.mp3" to 2.0))
        val table = soundChannelTable(w.store.aux)
        assertSame(table, w.store.aux.peek<SoundChannelTable>(AUDIO_CHANNEL_SLOT), "就是 audio.channels 槽")
        assertEquals(SoundChannelTable.DEFAULT_CHANNEL_COUNT, w.host.soundChannels, "GlobalStatic.cs:42")

        assertEquals(-1L, w.int("ISPLAYINGSOUND", 0), "未播时 -1（FunctionMethodCreator.cs:8866-8870）")
        w.run(playSoundStmt("se.mp3", 1))
        assertEquals(0L, w.int("ISPLAYINGSOUND", 0), "sink 播起来后函数层立刻读得到（同一张表）")
        assertTrue(table.isPlayingSound(0))

        assertEquals(1L, w.int("SOUNDCONTROL", 0, 2), "函数层停同一张表的声道（:8926-8928）")
        assertEquals(-1L, w.int("ISPLAYINGSOUND", 0))
        assertEquals(-1L, w.int("ISPLAYINGSOUND", 1))
    }

    @Test
    fun `aux 清空重建后 sink 跟新表 不缓存旧表`() {
        val w = rig(mapOf("se.mp3" to 2.0))
        w.run(playSoundStmt("se.mp3", 1))
        val old = soundChannelTable(w.store.aux)
        assertTrue(old.isPlayingSound(0))

        // RESTART / 读档语义：aux 清空后第一次取表建新实例（RuntimeAux.clear）
        w.store.aux.clear()
        val fresh = soundChannelTable(w.store.aux)
        assertNotSame(old, fresh)
        assertFalse(fresh.isPlayingSound(0), "新表全空")

        w.run(playSoundStmt("se.mp3", 1))
        assertTrue(fresh.isPlayingSound(0), "sink 现读 aux ⇒ 落到新表")
        assertEquals(0L, w.int("ISPLAYINGSOUND", 0), "内建宿主四项同样跟到新表")
    }
}
