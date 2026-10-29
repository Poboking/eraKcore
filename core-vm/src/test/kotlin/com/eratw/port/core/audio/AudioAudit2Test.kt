package com.eratw.port.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 审计二（lane L01 音频）的取证 —— 钉 **PLAYSOUND 的 repeat 折异顺序**落在声道表上的那一半。
 *
 * 权威来源（逐条反编译原文）：
 * - FunctionIdentifier.cs:2621 `int repeat = (int)((spHtmlPrint.Opt == null) ? 1 : Math.Max(实参, 1L));`
 *   ⇒ 「夹到至少 1」发生在 **long 域**，`(int)` 截断在**之后**；工程未开 CheckForOverflowUnderflow
 *   ⇒ 截断是 unchecked 取低 32 位。
 * - Sound.cs:64（`play(string filename, int repeat = 1)`）+ :97-105（`repeat == -1` 套 LoopStream、
 *   `repeat > 1` 套 RepeatStream、其余不包装）⇒ 声道侧对 repeat 只有这一条**三分类**，
 *   没有任何「夹到 1」的动作。
 * - LoopStream.cs:31-34（`HasData` 恒真）+ SoundMixer.cs:31、:39-42（`MixerInputEnded` → `Playing = false`）
 *   ⇒ 循环流永远不会自然摘出混音器，一次性流会。
 * - Sound.cs:51-56 读的是**未被 Loop/Repeat 包装**的原始流 ⇒ repeat 不改变上报的总时长
 *   （GETSOUNDORBGMINFO 的信息位 1 因此与循环无关，FunctionMethodCreator.cs:8811、:8820）。
 *
 * 由此得出的官方可观测行为：`PLAYSOUND "x.mp3", 4294967295`（即 0xFFFFFFFF）先被
 * `Math.Max` 放行、再被 `(int)` 截成 **-1** ⇒ 原版把这条音效**无限循环**；
 * `4294967295 + k·2^32` 同样落 -1。同族截断在函数层已按同一条口径复刻过
 * （fn/AudioMethods.kt 的 GETSOUNDORBGMINFO：4294967295 截成 -1 ⇒ 读 Bgm，FunctionMethodCreator.cs:8791）。
 *
 * 本文件因此钉两件事：① [SoundChannel.play] 收到什么 repeat 就按三分类走，表侧**不得**再夹
 * （表侧夹一次就把上面这条差异在语句层修好之后仍然吃掉）；② 语句层必须按「先 Max 后截断」的
 * 顺序折算 —— 该算式在这里以纯算式钉住，接线点在 sem/StatementExecutor 与 core/host 的
 * SoundSink（不属本路独占文件，已写进交付报告的接线项）。
 *
 * 基准对这些读数**没有任何期望**（fixtures/golden/baseline/pc-golden-root.txt 的 SOUND 族是
 * MISSING 行，采集夹具播的是不存在的文件），所以本文件不把基准文本当期望，也不改基准。
 */
class AudioAudit2Test {

    /** 记录 `onRepeat` 与反向通道的假解码流（原版 = Sound.cs:83-105 建链后套的那条流）。 */
    private class RecordingStream(
        private val total: Double,
    ) : ChannelDecoder {
        override val totalSeconds: Double get() = total
        override val positionSeconds: Double = 0.0
        override fun seekTo(seconds: Double) {}

        var repeat: Int? = null
        var started: Boolean = false
        var released: Boolean = false
        private var naturalEndListener: (() -> Unit)? = null

        override fun onRepeat(repeat: Int) {
            this.repeat = repeat
        }

        override fun onStarted() {
            started = true
        }

        override fun release() {
            released = true
            naturalEndListener = null
        }

        override fun setNaturalEndListener(listener: (() -> Unit)?) {
            naturalEndListener = listener
        }

        fun finishNaturally() {
            naturalEndListener?.invoke()
        }
    }

    /** FunctionIdentifier.cs:2621 的**运算顺序**：先在 long 域 Max，再 (int) 截断。 */
    private fun statementRepeat(opt: Long): Int = maxOf(opt, 1L).toInt()

    @Test
    fun `repeat 为 -1 时声道表按 LoopStream 分支走 无限循环`() {
        // spec: Sound.cs:64 + :98-101（repeat == -1 ⇒ LoopStream）；FunctionIdentifier.cs:2621 的截断可产出 -1
        val table = SoundChannelTable()
        val stream = RecordingStream(12.0)

        assertEquals(0, table.playSoundWith("a.mp3", statementRepeat(4294967295L)) { stream })
        val channel = table.channelAt(0) ?: error("PLAYSOUND 必须把选中的槽建出来（:2632-2635）")
        assertTrue(channel.looping, "0xFFFFFFFF 折算成 -1 ⇒ 循环（原版进 LoopStream）")
        assertEquals(-1, stream.repeat, "表侧不得把 -1 夹成正数：透传才是 Sound.cs:97 的口径")
        assertTrue(channel.isPlaying(), "ISPLAYINGSOUND 读数 = 在播（Sound.cs:171-178）")
        assertTrue(table.isPlayingSound(0))
        // 循环流永不自然摘出混音器（LoopStream.cs:31-34 → SoundMixer.cs:39-42 不触发）。
        stream.finishNaturally()
        assertTrue(table.isPlayingSound(0), "播完一遍不摘：LoopStream 的 HasData 恒真")
        // repeat 不改变上报的总时长（Sound.cs:51-56 读的是未被包装的原始流）。
        assertEquals(12.0, channel.getTotalTime(), "循环不改总时长")
    }

    @Test
    fun `repeat 的其余取值逐个按三分类透传 表侧不夹`() {
        // spec: Sound.cs:97-105 —— 只有 -1 与 >1 会包装，其余（0 / 负数 / 1）都是播一遍
        val expected = listOf(0 to false, 1 to false, 3 to false, -2 to false, Int.MIN_VALUE to false, -1 to true)
        for ((index, case) in expected.withIndex()) {
            val (repeat, looping) = case
            val table = SoundChannelTable()
            val stream = RecordingStream(1.0)
            assertEquals(0, table.playSoundWith("a.mp3", repeat) { stream }, "第 $index 例：空表首次播放落声道 0")
            val channel = table.channelAt(0) ?: error("第 $index 例未建声道")
            assertEquals(looping, channel.looping, "repeat=$repeat ⇒ looping=$looping（Sound.cs:98-101）")
            assertEquals(repeat, stream.repeat, "repeat=$repeat 必须原样下发（onRepeat，Sound.cs:97-105）")
            assertTrue(stream.started, "repeat=$repeat 仍要正常起播")
        }
    }

    @Test
    fun `非循环的 repeat 播完即摘出混音器 与一次性音效同判`() {
        // spec: SoundMixer.cs:31、:39-42（MixerInputEnded → Playing = false）；Sound.cs:102-104（RepeatStream 只有最后一遍结束）
        val table = SoundChannelTable()
        val stream = RecordingStream(2.0)
        table.playSoundWith("a.mp3", statementRepeat(8589934594L)) { stream }
        val channel = table.channelAt(0)!!
        assertFalse(channel.looping, "8589934594 = 2·2^32 + 2 ⇒ Max 放行后截成 2 ⇒ 不循环")
        assertEquals(2, stream.repeat, "套 RepeatStream 的遍数（Sound.cs:102-104）")
        assertTrue(table.isPlayingSound(0))
        stream.finishNaturally()
        assertFalse(table.isPlayingSound(0), "一次性流读完即摘（FunctionMethodCreator.cs:8866 的读数随之回 -1）")
    }

    @Test
    fun `语句层若先截断后夹取就会把无限循环读成播一遍`() {
        // 这条钉的是**接线判据**：sem/StatementExecutor 与 TableSoundSink 现在的算式是
        // 截断在后夹取（`opt.toInt().coerceAtLeast(1)`），于是 0xFFFFFFFF 读成 1；
        // 原版是夹取在后截断（`Math.Max(实参,1L)` 再 `(int)`），读成 -1 ⇒ 循环。
        val originalOrder = statementRepeat(4294967295L)
        val portOrderToday = 4294967295L.toInt().coerceAtLeast(1)
        assertEquals(-1, originalOrder, "FunctionIdentifier.cs:2621 的读数")
        assertEquals(1, portOrderToday, "移植侧当前语句层的读数（差异登记在交付报告）")
        // 表侧只对「已经是 -1」负责：给它 -1 就必须循环，这样接线一改就立刻与 PC 同判。
        val table = SoundChannelTable()
        table.playSoundWith("a.mp3", originalOrder) { RecordingStream(3.0) }
        assertTrue(table.channelAt(0)!!.looping)
    }

    /**
     * [SoundChannelTable.playSound] 的注入点包装：本文件的用例都要看见具体流对象，
     * 而 [FakeSoundSource] 每次 resolve 都新建流，所以这里用一次性音源把注入的流交给表。
     */
    private fun SoundChannelTable.playSoundWith(
        name: String,
        repeat: Int,
        stream: () -> ChannelDecoder,
    ): Int = playSound(name, repeat, SoundSourceResolver {
        assertEquals(name, it, "音源名必须原样交给解析器（FunctionIdentifier.cs:2622）")
        stream()
    })
}
