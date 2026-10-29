package com.eratw.port.core.fn

import com.eratw.port.core.audio.ChannelAudioHost
import com.eratw.port.core.audio.FakeChannelDecoder
import com.eratw.port.core.audio.FakeSoundSource
import com.eratw.port.core.audio.SoundChannel
import com.eratw.port.core.audio.SoundChannelTable
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import java.io.File
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 音频函数族测试（BGMCONTROL / SOUNDCONTROL / GETSOUNDORBGMINFO）。
 *
 * 判据（CONTRACT-M4-I §2 i-audiofn）：
 * - 每个参数分支各一条断言，用**状态表前后读数对照**：声道是否被物化（`channelAt(i)` 的
 *   null / 非 null）、playing / paused / decoder 是否释放、getVolume() / getSpeed() 的**具体值**、
 *   GETSOUNDORBGMINFO 五个信息位与 RESULT[0..4] 的具体读数。
 * - 引擎面全部走真实的 [ChannelAudioHost] + [SoundChannelTable]（不是 FakeAudio），
 *   因此这里断言的就是 `SoundChannelTest`（31 例）钉过的那台状态机，
 *   本文件不改动它的任何语义。
 * - 原版口径来源：`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 *   的 GetSoundOrBgmInfoMethod :8769-8828、SoundControlMethod :8874-8947、
 *   BgmControlMethod :8968-9031（注册表 :9308 / :9310 / :9312）。
 * - `SoundInfo.volume` / 变速读数走的是引擎的 binary32 往返（Sound.cs:180-206），
 *   期望值取自 SoundChannelTest 的独立核对表（音量损失点 53→52、变速 11→10）。
 *
 * 语料依赖：只有最后一例吃 `ERATW_GAMEDIR` / `ERATW_CORPUS`（变量缺失时该用例不生成，
 * 不计入 skipped），交付报告已登记「本路 1 例吃语料变量」。
 */
private class AmBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("AmBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) = Unit
}

private class AmHost(private val rig: ChannelAudioHost) : BuiltinHost {
    override val soundChannels: Int get() = rig.channelCount
    override val audio: AudioHost get() = rig
}

/** 无音频宿主的宿主：验证「宿主缺口抛 CodeEE」而不是伪造 0。 */
private class NoAudioHost : BuiltinHost {
    override val soundChannels: Int = SoundChannelTable.DEFAULT_CHANNEL_COUNT
    override val audio: AudioHost? = null
}

private class AmEnv(
    val table: SoundChannelTable = SoundChannelTable(channelCount = 3),
    val source: FakeSoundSource = FakeSoundSource(mapOf("a.wav" to 2.0, "b.wav" to 4.0)),
) {
    val base = AmBaseCtx()
    val store = VariableStore()
    val rig = ChannelAudioHost(table)
    val ctx: BuiltinEvalContext

    init {
        base.variables = store
        ctx = BuiltinEvalContext(base, store, AmHost(rig))
    }

    /** 三条函数入口（直接调实现体，不经过全局注册表 —— 见 AudioMethods.kt 类头第四节）。 */
    fun info(vararg args: Long): Long =
        AudioMethods.getSoundOrBgmInfo(ctx, args.map { Value.IntV(it) })

    fun soundControl(vararg args: Long): Long =
        AudioMethods.soundControl(ctx, args.map { Value.IntV(it) })

    fun bgmControl(vararg args: Long): Long =
        AudioMethods.bgmControl(ctx, args.map { Value.IntV(it) })

    fun channel(i: Int): SoundChannel? = table.channelAt(i)

    /** 取已物化声道的句柄，断言前后读数时用；未物化直接失败（避免 NPE 掩盖判据）。 */
    fun requireChannel(i: Int): SoundChannel =
        assertNotNull(table.channelAt(i), "声道 $i 尚未物化（原版 GlobalStatic.Sound[$i] == null）")

    fun decoder(i: Int): FakeChannelDecoder = requireChannel(i).decoder as FakeChannelDecoder

    fun resultOf(index: Int): Long = store.readInt("RESULT", longArrayOf(index.toLong()))

    fun playSoundOnChannel0(name: String = "a.wav"): Int = table.playSound(name, 1, source)

    fun playBgm(name: String = "b.wav"): Boolean = table.playBgm(name, source)
}

class AudioMethodsTest {

    // ==================================================================
    // GETSOUNDORBGMINFO：声道选取 + 五个信息位 + 取不到时的口径
    // ==================================================================

    @Test
    fun `GETSOUNDORBGMINFO 合法下标惰性建道并读到出厂五项`() {
        val env = AmEnv()
        // 改前：声道 1 从未被访问（GlobalStatic.cs:42 的可空数组语义）
        assertNull(env.channel(1), "改前 Sound[1] == null")
        assertEquals(0L, env.info(1), "新声道总时长 0 ⇒ 返回 RESULT[0] = 0（:8811、:8816）")
        // 改后：函数自己把声道建了出来（:8799-8802）
        assertNotNull(env.channel(1), "GETSOUNDORBGMINFO 惰性 new Sound()（:8799-8802）")
        // 出厂五项：total 0 / current 0 / playing 0 / volume 100（Sound.cs:13 + :191）/ speed 100
        assertEquals(listOf(0L, 0L, 0L, 100L, 100L), (0..4).map { env.resultOf(it) }, "RESULT[0..4]")
    }

    @Test
    fun `GETSOUNDORBGMINFO 无第二参写 RESULT 零到四并返回 RESULT 零`() {
        val env = AmEnv()
        assertEquals(0, env.playSoundOnChannel0("a.wav"), "PLAYSOUND 选到声道 0")
        env.requireChannel(0).setVolume(50)
        assertEquals(2000L, env.info(0), "a.wav 时长 2.0 秒 ⇒ (long)(2.0*1000.0)")
        assertEquals(
            listOf(2000L, 0L, 1L, 50L, 100L),
            (0..4).map { env.resultOf(it) },
            "RESULT[0]=总时长毫秒 [1]=当前毫秒 [2]=在播 [3]=音量 [4]=变速百分比",
        )
    }

    @Test
    fun `GETSOUNDORBGMINFO 信息位一到五各自的具体读数`() {
        val env = AmEnv()
        env.playSoundOnChannel0("b.wav")            // 总时长 4.0 秒
        env.decoder(0).advance(1.5)                 // 当前位置 1.5 秒
        env.requireChannel(0).setVolume(80)
        env.store.writeInt("RESULT", longArrayOf(1), 12345L)   // 残留哨兵
        assertEquals(4000L, env.info(0, 1), "信息位 1 = GetTotalTime()*1000（:8820）")
        assertEquals(1500L, env.info(0, 2), "信息位 2 = GetCurrentTime()*1000（:8821）")
        assertEquals(1L, env.info(0, 3), "信息位 3 = isPlaying()?1:0（:8822）")
        assertEquals(80L, env.info(0, 4), "信息位 4 = getVolume()（:8823）")
        assertEquals(100L, env.info(0, 5), "信息位 5 = getSpeed()*100（:8824）")
        // 显式第二参这一支**不写** RESULT（原版只在 :8809 的缺省分支写数组），哨兵原样保留
        assertEquals(12345L, env.resultOf(1), "带信息位时不写 RESULT（:8818-8826 只 return）")
    }

    @Test
    fun `GETSOUNDORBGMINFO 信息位零或六或负数一律回零`() {
        val env = AmEnv()
        env.playSoundOnChannel0("b.wav")
        for (sel in listOf(0L, 6L, -1L, 99L)) {
            assertEquals(0L, env.info(0, sel), "switch 默认分支 ⇒ 0（:8825）信息位 $sel")
        }
        // 对照：合法信息位确实非零，排除「恒 0」的假绿
        assertEquals(4000L, env.info(0, 1), "合法信息位 1 的对照读数")
    }

    @Test
    fun `GETSOUNDORBGMINFO 的 BGM 侧不惰性建道 取不到时返回零且不写 RESULT`() {
        val env = AmEnv(table = SoundChannelTable(channelCount = 3, initialBgm = null))
        assertNull(env.table.bgm, "改前 GlobalStatic.Bgm == null（只有 reset 造得出该状态）")
        env.store.writeInt("RESULT", longArrayOf(0), 999L)
        assertEquals(0L, env.info(-1), "sound == null ⇒ 返回 0（:8805-8808）")
        assertEquals(0L, env.info(-1, 4), "带信息位同样回 0")
        assertEquals(999L, env.resultOf(0), "取不到时**不写** RESULT：残留 999 原样保留")
        assertNull(env.table.bgm, "GETSOUNDORBGMINFO 的 BGM 侧不建道（:8793-8796）")
    }

    @Test
    fun `GETSOUNDORBGMINFO 越界声道静默返回零且不物化`() {
        val env = AmEnv()
        env.store.writeInt("RESULT", longArrayOf(0), 777L)
        for (bad in listOf(3L, -2L, 99L)) {
            assertEquals(0L, env.info(bad), "越界声道 $bad ⇒ 静默 0（:8805-8808）")
        }
        assertNull(env.channel(3), "越界不物化：Sound.Length 之外的下标连 new Sound() 都没有")
        assertEquals(777L, env.resultOf(0), "取不到时**不写** RESULT：原版在写数组之前就 return（:8805-8808 早于 :8809）")
    }

    @Test
    fun `GETSOUNDORBGMINFO 的声道与信息位都做 C# 的 int 截断`() {
        val env = AmEnv()
        env.playSoundOnChannel0("a.wav")   // 声道 0，2.0 秒
        env.playBgm("b.wav")               // Bgm，4.0 秒
        // :8791 —— int num = (int)arguments[0]：0xFFFFFFFF 截断成 -1 ⇒ 走 BGM 分支
        assertEquals(4000L, env.info(4294967295L), "4294967295 截断为 -1 ⇒ 读 BGM 而不是回 0")
        // 0x100000000 截断成 0 ⇒ 读音效声道 0
        assertEquals(2000L, env.info(4294967296L), "4294967296 截断为 0 ⇒ 读声道 0")
        // :8818 —— (int)arguments[1]：0x100000001 截断成 1 ⇒ 总时长
        assertEquals(4000L, env.info(-1, 4294967297L), "信息位 4294967297 截断为 1")
    }

    @Test
    fun `GETSOUNDORBGMINFO 音量与变速读数带引擎的 binary32 往返`() {
        val env = AmEnv()
        env.playSoundOnChannel0("a.wav")
        env.requireChannel(0).setVolume(53)
        assertEquals(52L, env.info(0, 4), "Sound.cs:182 + :191 的往返损失（SoundChannelTest 同一张表）")
        env.requireChannel(0).setVolume(59)
        assertEquals(58L, env.info(0, 4), "59 → 58")
        env.requireChannel(0).setSpeed(0.11f)
        assertEquals(10L, env.info(0, 5), "11% 的变速读回 10（SoundChannelTest:342 的损失点）")
    }

    @Test
    fun `GETSOUNDORBGMINFO 停止后两个时间读数归零`() {
        val env = AmEnv()
        env.playSoundOnChannel0("b.wav")
        assertEquals(4000L, env.info(0, 1), "改前：总时长 4000")
        env.soundControl(0, 2)
        assertEquals(0L, env.info(0, 1), "改后：decoder == null ⇒ GetTotalTime() 恒 0（Sound.cs:45-57）")
        assertEquals(0L, env.info(0, 2), "当前位置同样 0")
        assertNull(env.requireChannel(0).decoder, "stop 将 stream 置 null（Sound.cs:158-161）")
    }

    @Test
    fun `GETSOUNDORBGMINFO 非整数实参抛 CodeEE`() {
        val env = AmEnv()
        val e = assertFailsWith<CodeEE> {
            AudioMethods.getSoundOrBgmInfo(env.ctx, listOf(Value.StrV("0")))
        }
        assertTrue(e.message!!.contains("必须是整数"), "实际文案：${e.message}")
    }

    @Test
    fun `音频三条在未注入宿主时抛 CodeEE 点名缺口`() {
        val base = AmBaseCtx()
        val store = VariableStore()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store, NoAudioHost())
        val cases = listOf(
            Triple("GETSOUNDORBGMINFO", "BuiltinHost.audio", Runnable {
                AudioMethods.getSoundOrBgmInfo(ctx, listOf(Value.IntV(0)))
            }),
            Triple("SOUNDCONTROL", "BuiltinHost.audio", Runnable {
                AudioMethods.soundControl(ctx, listOf(Value.IntV(0), Value.IntV(0)))
            }),
            Triple("BGMCONTROL", "BuiltinHost.audio", Runnable {
                AudioMethods.bgmControl(ctx, listOf(Value.IntV(0)))
            }),
        )
        for ((name, hint, body) in cases) {
            val e = assertFailsWith<CodeEE> { body.run() }
            assertTrue(
                e.message!!.contains("函数 $name") && e.message!!.contains(hint),
                "$name 的文案应点名函数名与缺口：${e.message}",
            )
        }
    }

    // ==================================================================
    // SOUNDCONTROL：声道 0..9 的暂停/继续/停止/变速
    // ==================================================================

    @Test
    fun `SOUNDCONTROL 操作码 0 暂停 前后读数对照`() {
        val env = AmEnv()
        env.playSoundOnChannel0()
        assertTrue(env.requireChannel(0).isPlaying(), "改前：在播")
        assertFalse(env.requireChannel(0).paused, "改前：未暂停")
        assertEquals(1L, env.soundControl(0, 0), "暂停成功返回 1（:8922）")
        assertFalse(env.table.isPlayingSound(0), "改后：isPlaying() 为假（Sound.cs:121-136）")
        assertTrue(env.requireChannel(0).paused, "改后：paused == true")
        assertTrue(env.requireChannel(0).playing.not(), "改后：已从混音器摘掉（SoundMixer.cs:79-83）")
        assertEquals(0L, env.info(0, 3), "信息位 3 跟着归零（已暂停算 0）")
        // 官方怪癖 5：pause 的守卫看 Playing 标志本身，再 pause 一次彻底 no-op
        assertEquals(1L, env.soundControl(0, 0), "第二次暂停照样返回 1")
        assertTrue(env.requireChannel(0).paused, "第二次 pause 是 no-op（Sound.cs:123 的守卫）")
    }

    @Test
    fun `SOUNDCONTROL 操作码 1 继续并按 savedPosition 回卷`() {
        val env = AmEnv()
        env.playSoundOnChannel0()
        env.decoder(0).advance(1.0)
        env.soundControl(0, 0)
        assertEquals(1.0, env.requireChannel(0).savedPositionSeconds, "pause 记下当前位置")
        val stream = env.decoder(0)
        assertEquals(1L, env.soundControl(0, 1), "继续成功返回 1（:8925）")
        assertEquals(1, stream.seekCount, "savedPosition > 0 ⇒ 回卷一次（Sound.cs:143-146）")
        assertEquals(1.0, stream.lastSeekSeconds, "回卷到暂停点")
        assertTrue(env.table.isPlayingSound(0), "改后：重新在播")
        assertFalse(env.requireChannel(0).paused, "改后：paused 归 false")
        assertEquals(1L, env.info(0, 3), "信息位 3 回到 1")
    }

    @Test
    fun `SOUNDCONTROL 在第零秒暂停再继续不回卷位置`() {
        val env = AmEnv()
        env.playSoundOnChannel0()
        env.soundControl(0, 0)
        assertEquals(0.0, env.requireChannel(0).savedPositionSeconds, "暂停点就是 0 秒")
        assertEquals(1L, env.soundControl(0, 1))
        assertEquals(0, env.decoder(0).seekCount, "savedPosition == 0 ⇒ 不 seek（Sound.cs:143 的守卫）")
    }

    @Test
    fun `SOUNDCONTROL 操作码 2 停止 释放解码流`() {
        val env = AmEnv()
        env.playSoundOnChannel0()
        val stream = env.decoder(0)
        assertFalse(stream.released, "改前：流未释放")
        assertEquals(1L, env.soundControl(0, 2), "停止成功返回 1（:8928）")
        assertTrue(stream.released, "改后：stop 释放 stream（Sound.cs:158-161）")
        assertFalse(env.table.isPlayingSound(0), "改后：不在播")
        assertEquals(0L, env.info(0, 1), "改后：时长读数恒 0")
        // stop 之后 resume 走的是 paused 守卫（此时 paused 已被 stop 清掉）⇒ no-op 但返回 1
        assertEquals(1L, env.soundControl(0, 1), "原版不看结果，只要操作码合法就回 1")
        assertFalse(env.requireChannel(0).isPlaying(), "继续一条对已停止的声道是 no-op")
    }

    @Test
    fun `SOUNDCONTROL 非法操作码返回零下仍物化声道`() {
        val env = AmEnv()
        assertNull(env.channel(2), "改前：声道 2 未物化")
        assertEquals(-2L, env.soundControl(2, 9), "非法操作码 ⇒ -2（:8929-8930）")
        assertNotNull(env.channel(2), "改后：原版在建道之后才分派 ⇒ 声道 2 已被 new Sound()（:8912-8915）")
        // 越界路径不物化，两者对照
        assertEquals(-1L, env.soundControl(3, 9))
        assertNull(env.channel(3), "越界判定早于建道 ⇒ 不物化（:8908-8911）")
    }

    @Test
    fun `SOUNDCONTROL 越界声道静默返回零下标`() {
        val env = AmEnv()
        env.playSoundOnChannel0()
        for (bad in listOf(-1L, -5L, 3L, 99L)) {
            assertEquals(-1L, env.soundControl(bad, 0), "越界声道 $bad ⇒ -1，静默无文案（:8910）")
        }
        assertTrue(env.requireChannel(0).isPlaying(), "越界的暂停没有动到声道 0")
        // 声道 -1 在原版是音效越界值，不是 BGM（BGM 只有 GETSOUNDORBGMINFO 认 -1）
        assertTrue(env.table.isPlayingBgm().not(), "SOUNDCONTROL -1 也不会去停 BGM")
    }

    @Test
    fun `SOUNDCONTROL 三参形态拒绝操作码零一`() {
        val env = AmEnv()
        env.playSoundOnChannel0()
        assertEquals(-2L, env.soundControl(0, 0, 100), "带第 3 参时 0 不再是暂停（:8916 的 Count == 2 判定）")
        assertTrue(env.requireChannel(0).isPlaying(), "改后仍在播：什么都没发生")
        assertEquals(-2L, env.soundControl(0, 1, 100), "带第 3 参的 1 同样 -2")
        assertEquals(-2L, env.soundControl(0, 2, 100), "带第 3 参的 2 同样 -2")
        assertTrue(env.requireChannel(0).isPlaying(), "对照：三条都没停掉播放")
    }

    @Test
    fun `SOUNDCONTROL 两参形态拒绝操作码三`() {
        val env = AmEnv()
        env.playSoundOnChannel0()
        assertEquals(-2L, env.soundControl(0, 3), "恰好 2 参时操作码 3 掉到默认分支（:8916 → :8945）")
        assertEquals(100L, env.info(0, 5), "改前改后变速读数都是 100，说明什么都没改")
    }

    @Test
    fun `SOUNDCONTROL 操作码三改变速百分比`() {
        val env = AmEnv()
        env.playSoundOnChannel0()
        assertEquals(100L, env.info(0, 5), "改前：出厂 1.0 倍 ⇒ 100")
        assertEquals(1L, env.soundControl(0, 3, 150), "变速成功返回 1（:8943）")
        assertEquals(150L, env.info(0, 5), "改后：1.5 倍 ⇒ 150")
        assertEquals(1.5, env.requireChannel(0).getSpeed(), "引擎侧倍率同样是 1.5")
        assertEquals(1L, env.soundControl(0, 3, 11), "损失点 11 ⇒ 返回 1")
        assertEquals(10L, env.info(0, 5), "11% 经 binary32 读回 10（SoundChannelTest 同一张表）")
    }

    @Test
    fun `SOUNDCONTROL 变速上下限夹到百分之一十与一千`() {
        val env = AmEnv()
        env.playSoundOnChannel0()
        env.soundControl(0, 3, 0)
        assertEquals(10L, env.info(0, 5), "0 ⇒ 夹到下限 0.1 ⇒ 读数 10（Sound.cs:198）")
        env.soundControl(0, 3, 5000)
        assertEquals(1000L, env.info(0, 5), "50 倍 ⇒ 夹到上限 10 ⇒ 读数 1000")
        env.soundControl(0, 3, -100)
        assertEquals(10L, env.info(0, 5), "负倍率同样夹到下限")
    }

    @Test
    fun `SOUNDCONTROL 对从未播过的声道变速整条 no-op`() {
        val env = AmEnv()
        assertNull(env.channel(1), "改前：声道 1 未物化")
        assertEquals(1L, env.soundControl(1, 3, 300), "操作码合法 ⇒ 返回 1")
        val ch = env.requireChannel(1)
        assertFalse(ch.varispeedReady, "变速链路只有 play 成功过一次才建（Sound.cs:115）")
        assertEquals(1.0, ch.getSpeed(), "setSpeed 整条 no-op（Sound.cs:196-197）")
        assertEquals(100L, env.info(1, 5), "信息位 5 仍是 100")
        ch.play(FakeChannelDecoder(1.0), repeat = 1)
        assertEquals(1.0, ch.getSpeed(), "补一次 play 之后链路建成，倍率回到 1.0（变速字段被抹平）")
    }

    @Test
    fun `SOUNDCONTROL 第四参非零关闭音高保留`() {
        val env = AmEnv()
        env.playSoundOnChannel0()
        assertTrue(env.requireChannel(0).preservePitch, "改前：出厂 true（Sound.cs:21）")
        assertEquals(1L, env.soundControl(0, 3, 150, 1), "第 4 参非 0 ⇒ 关闭保留（:8937-8940）")
        assertFalse(env.requireChannel(0).preservePitch, "改后：preservePitch == false")
        assertEquals(1L, env.soundControl(0, 3, 150, 0), "第 4 参为 0 ⇒ 保留")
        assertTrue(env.requireChannel(0).preservePitch, "改回 true")
        assertEquals(1L, env.soundControl(0, 3, 150), "三参形态同样保留（第 4 参缺省）")
        assertTrue(env.requireChannel(0).preservePitch)
        // 第 4 参不做 (int) 截断：0x100000001 截断成 1，但 long 比较只看是否为 0
        assertEquals(1L, env.soundControl(0, 3, 150, 4294967297L))
        assertFalse(env.requireChannel(0).preservePitch, "非 0 即关闭，与截断无关")
    }

    @Test
    fun `SOUNDCONTROL 变速后重播抹平倍率但音高标志存活`() {
        val env = AmEnv()
        env.playSoundOnChannel0()
        env.soundControl(0, 3, 200, 1)
        assertFalse(env.requireChannel(0).preservePitch)
        assertEquals(200L, env.info(0, 5), "改前：2 倍")
        env.requireChannel(0).play(FakeChannelDecoder(3.0), repeat = 1)
        assertEquals(100L, env.info(0, 5), "改后：play 重建变速链 ⇒ 倍率回 1.0（Sound.cs:115 怪癖 1）")
        assertFalse(env.requireChannel(0).preservePitch, "preservePitch 字段存活（同一怪癖的另一半）")
        assertEquals(3000L, env.info(0, 1), "改后：新流的时长读数换成了 3.0 秒")
    }

    @Test
    fun `SOUNDCONTROL 声道与操作码都做 C# 的 int 截断`() {
        val env = AmEnv()
        env.playSoundOnChannel0()
        assertEquals(1L, env.soundControl(4294967296L, 0), "声道 0x100000000 截断成 0 ⇒ 正常暂停")
        assertTrue(env.requireChannel(0).paused)
        env.soundControl(0, 1)
        assertEquals(1L, env.soundControl(0, 4294967299L, 150), "操作码 0x100000003 截断成 3 ⇒ 进变速分支")
        assertEquals(150L, env.info(0, 5))
        assertEquals(-2L, env.soundControl(0, 4294967300L, 150), "截断成 4 ⇒ 非法操作码 -2")
    }

    @Test
    fun `SOUNDCONTROL 非整数与缺参抛 CodeEE`() {
        val env = AmEnv()
        assertTrue(
            assertFailsWith<CodeEE> {
                AudioMethods.soundControl(env.ctx, listOf(Value.IntV(0), Value.StrV("x")))
            }.message!!.contains("第 2 个参数必须是整数"),
        )
        assertTrue(
            assertFailsWith<CodeEE> {
                AudioMethods.soundControl(env.ctx, listOf(Value.IntV(0), null))
            }.message!!.contains("缺少第 2 个参数"),
        )
    }

    // ==================================================================
    // BGMCONTROL
    // ==================================================================

    @Test
    fun `BGMCONTROL 单参零一二对 Bgm 的三条动作各自前后对照`() {
        val env = AmEnv()
        assertTrue(env.playBgm("b.wav"), "PLAYBGM 成功（循环 repeat = -1）")
        val bgm = env.table.bgm!!
        assertTrue(bgm.isPlaying(), "改前：BGM 在播")
        assertEquals(1L, env.bgmControl(0), "暂停成功回 1（:9006）")
        assertTrue(bgm.paused and bgm.playing.not(), "改后：paused 置位、摘出混音器")
        assertEquals(0L, env.info(-1, 3), "信息位 3 归零")
        assertEquals(1L, env.bgmControl(1), "继续成功回 1（:9009）")
        assertTrue(bgm.isPlaying(), "改后：重新在播")
        assertEquals(1L, env.bgmControl(2), "停止成功回 1（:9012）")
        assertNull(bgm.decoder, "改后：stream 已释放")
        assertEquals(0L, env.info(-1, 1), "时长读数归零")
    }

    @Test
    fun `BGMCONTROL 非法操作码照样物化 Bgm`() {
        val env = AmEnv(table = SoundChannelTable(channelCount = 3, initialBgm = null))
        assertEquals(0L, env.info(-1, 4), "改前：Bgm 为 null ⇒ 信息位 4 回 0（:8805-8808）")
        assertEquals(-2L, env.bgmControl(9), "非法操作码 ⇒ -2（:9013-9014）")
        assertNotNull(env.table.bgm, "改后：原版 :8996-8999 在建道之后才分派 ⇒ Bgm 已被 new Sound()")
        assertEquals(100L, env.info(-1, 4), "物化后的出厂音量读数 100（Sound.cs:13 + :191）")
        // 两参形态的非法分支同样先建道
        val env2 = AmEnv(table = SoundChannelTable(channelCount = 3, initialBgm = null))
        assertEquals(-2L, env2.bgmControl(0, 100))
        assertNotNull(env2.table.bgm, "带第 2 参的非法路径也一样物化")
    }

    @Test
    fun `BGMCONTROL 两参形态拒绝操作码零一`() {
        val env = AmEnv()
        env.playBgm("b.wav")
        assertEquals(-2L, env.bgmControl(0, 100), "arguments.Count != 1 ⇒ 0/1/2 不被接受（:9000）")
        assertTrue(env.table.isPlayingBgm(), "改后仍在播")
        assertEquals(-2L, env.bgmControl(2, 100))
        assertTrue(env.table.isPlayingBgm(), "对照：停止也没生效")
    }

    @Test
    fun `BGMCONTROL 操作码三改变速与音高保留`() {
        val env = AmEnv()
        env.playBgm("b.wav")
        assertEquals(100L, env.info(-1, 5), "改前：1.0 倍")
        assertEquals(1L, env.bgmControl(3, 150), "两参的 3 ⇒ 变速（:9017-9028）")
        assertEquals(150L, env.info(-1, 5), "改后：1.5 倍")
        assertTrue(env.table.bgm!!.preservePitch, "第三参缺省 ⇒ 保留音高")
        assertEquals(1L, env.bgmControl(3, 250, 1), "三参形态")
        assertEquals(250L, env.info(-1, 5))
        assertFalse(env.table.bgm!!.preservePitch, "第三参非 0 ⇒ 关闭保留（:9021-9024）")
        assertEquals(1L, env.bgmControl(3, 250, 0), "第三参为 0 ⇒ 重新开启保留")
        assertTrue(env.table.bgm!!.preservePitch, "顺序照抄原版：先 SetPreservePitch 再 setSpeed（:9025-9026）")
    }

    @Test
    fun `BGMCONTROL 在未播过的 Bgm 上变速 no-op 但返回一`() {
        val env = AmEnv()
        assertFalse(env.table.bgm!!.varispeedReady, "出厂 Bgm 存在但从未 play（GlobalStatic.cs:44）")
        assertEquals(1L, env.bgmControl(3, 300))
        assertEquals(100L, env.info(-1, 5), "变速链路未建 ⇒ 读回 1.0（Sound.cs:203-206）")
        assertEquals(1L, env.bgmControl(3, 300, 1), "第 3 参非 0 ⇒ 关闭音高保留")
        assertFalse(env.table.bgm!!.preservePitch, "字段常驻，profile 只在链路存在时下发（Sound.cs:242-249）")
        assertFalse(env.table.bgm!!.pitchProfileApplied, "链路没建 ⇒ 没有下发过 profile")
    }

    @Test
    fun `BGMCONTROL 对已停止的 Bgm 继续是 no-op 仍回一`() {
        val env = AmEnv()
        env.playBgm("b.wav")
        env.bgmControl(2)
        assertEquals(1L, env.bgmControl(1), "原版不看结果，操作码合法就回 1（:9009）")
        assertFalse(env.table.isPlayingBgm(), "改后：仍在停止态（resume 的 paused 守卫，Sound.cs:140）")
    }

    @Test
    fun `BGMCONTROL 操作码做 int 截断`() {
        val env = AmEnv()
        env.playBgm("b.wav")
        assertEquals(1L, env.bgmControl(4294967296L), "0x100000000 截断成 0 ⇒ 暂停")
        assertTrue(env.table.bgm!!.paused)
        assertEquals(1L, env.bgmControl(4294967297L), "0x100000001 截断成 1 ⇒ 继续")
        assertTrue(env.table.isPlayingBgm())
        assertEquals(1L, env.bgmControl(4294967299L, 150), "0x100000003 截断成 3 ⇒ 变速")
        assertEquals(150L, env.info(-1, 5))
    }

    @Test
    fun `BGMCONTROL 非整数实参抛 CodeEE`() {
        val env = AmEnv()
        assertTrue(
            assertFailsWith<CodeEE> {
                AudioMethods.bgmControl(env.ctx, listOf(Value.StrV("0")))
            }.message!!.contains("第 1 个参数必须是整数"),
        )
    }

    // ==================================================================
    // 三条函数的循环配合：状态表跨函数一致性
    // ==================================================================

    @Test
    fun `音效与 BGM 共用同一张表时三条函数读数互不串台`() {
        val env = AmEnv()
        env.playSoundOnChannel0("a.wav")
        env.playBgm("b.wav")
        env.soundControl(0, 3, 200)
        env.bgmControl(0)
        assertEquals(200L, env.info(0, 5), "声道 0 的变速")
        assertEquals(100L, env.info(-1, 5), "BGM 的变速未被波及")
        assertEquals(1L, env.info(0, 3), "声道 0 仍在播")
        assertEquals(0L, env.info(-1, 3), "BGM 已暂停")
        assertEquals(1, env.table.playingSoundChannels().size, "只有声道 0 记为在播")
        env.table.reset()
        assertNull(env.table.bgm, "reset 后 Bgm == null（GlobalStatic.cs:69-73）")
        assertEquals(0L, env.info(-1), "BGM 侧回到取不到形态")
    }

    // ==================================================================
    // 注册面：arity 形态与占位覆盖
    // ==================================================================

    @Test
    fun `注册三条时覆盖同名占位并带原版 arity 形态`() {
        val r = BuiltinRegistry()
        r.registerUnimplemented("GETSOUNDORBGMINFO", OperandType.INT, 0, 4)
        r.registerUnimplemented("SOUNDCONTROL", OperandType.INT, 0, 4)
        r.registerUnimplemented("BGMCONTROL", OperandType.INT, 0, 4)
        registerAudioMethods(r)
        assertTrue(r.placeholderNames().none { it in listOf("GETSOUNDORBGMINFO", "SOUNDCONTROL", "BGMCONTROL") })
        assertEquals(OperandType.INT, r["SOUNDCONTROL"]!!.retType)
        // SOUNDCONTROL：合法元数 2/3/4（FunctionMethodCreator.cs:8881-8899）
        for (n in listOf(2, 3, 4)) r["SOUNDCONTROL"]!!.checkArity(n)
        for (n in listOf(1, 5)) assertFailsWith<CodeEE> { r["SOUNDCONTROL"]!!.checkArity(n) }
        // BGMCONTROL：合法元数 1/2/3（:8977-8988）
        for (n in listOf(1, 2, 3)) r["BGMCONTROL"]!!.checkArity(n)
        for (n in listOf(0, 4)) assertFailsWith<CodeEE> { r["BGMCONTROL"]!!.checkArity(n) }
        // GETSOUNDORBGMINFO：1 或 2（:8776-8784，OmitStart = 1）
        for (n in listOf(1, 2)) r["GETSOUNDORBGMINFO"]!!.checkArity(n)
        for (n in listOf(0, 3)) assertFailsWith<CodeEE> { r["GETSOUNDORBGMINFO"]!!.checkArity(n) }
    }

    @Test
    fun `真实现撞真实现时注册表直接拒绝`() {
        val r = BuiltinRegistry()
        registerAudioMethods(r)
        // 本族与 DebugMiscMethods 的音频三条是同名真实现，二者只能留一个
        // （AudioMethods.kt 类头第四节的收口口径就是这条：接线时删掉旧三条）
        assertFailsWith<IllegalStateException> { registerAudioMethods(r) }
    }

    @Test
    fun `materializer 由声道宿主实现且与惰性建道幂等`() {
        val env = AmEnv()
        // 编译期即要求 ChannelAudioHost 实现该接口（函数层靠它复刻「先建道后分派」）
        val mat: AudioChannelMaterializer = env.rig
        assertNull(env.channel(1))
        assertTrue(mat.materializeSound(1), "合法下标物化成功")
        assertTrue(mat.materializeSound(1), "重复物化幂等")
        assertNotNull(env.channel(1))
        val before = env.channel(1)
        assertEquals(1L, env.soundControl(1, 3, 150), "之后 controlSound 走同一槽位")
        assertSame(before, env.channel(1), "没有换对象：惰性建道与物化共用 channelAtOrCreate")
        assertFalse(mat.materializeSound(7), "越界不物化（原版 IndexOutOfRange 的移植口径）")
        env.table.reset()
        assertNull(env.table.bgm, "reset 之后 Bgm == null（GlobalStatic.cs:69-73）")
        mat.materializeBgm()
        assertNotNull(env.table.bgm, "materializeBgm 把 Bgm 建回来（对应 :8996-8999）")
        assertEquals(100L, env.info(-1, 4), "建过之后信息位 4 读到出厂音量 100")
    }

    // ==================================================================
    // 语料判据（1 例；ERATW_GAMEDIR / ERATW_CORPUS 缺失时该用例不生成）
    // ==================================================================

    @Test
    fun `语料 sound 目录的真实文件名走同一条函数链路`() {
        val gameDir = listOfNotNull(System.getenv("ERATW_GAMEDIR"), System.getenv("ERATW_CORPUS"))
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
        Assumptions.assumeTrue(gameDir != null, "跳过：未设置 ERATW_GAMEDIR / ERATW_CORPUS")
        // 原版 PLAYSOUND 的资源根是 `.\sound\`（FunctionIdentifier.cs:2622-2628）
        val soundDir = File(gameDir!!).listFiles { f -> f.isDirectory && f.name.equals("sound", ignoreCase = true) }
            ?.firstOrNull()
        Assumptions.assumeTrue(soundDir != null, "跳过：$gameDir 下没有 sound 目录")
        val names = soundDir!!.walkTopDown().maxDepth(1).filter { it.isFile }.map { it.name }.toList()
        Assumptions.assumeTrue(names.isNotEmpty(), "跳过：${soundDir.path} 里没有文件")
        val source = FakeSoundSource(names.take(8).associateWith { 1.0 })
        val env = AmEnv(table = SoundChannelTable(channelCount = 3), source = source)
        val played = env.table.playSound(names.first(), 1, source).toLong()
        assertTrue(played >= 0, "PLAYSOUND 应命中真实文件名 ${names.first()}")
        assertEquals(1000L, env.info(played, 1), "假解码流固定 1.0 秒 ⇒ 信息位 1 = 1000")
        assertEquals(1L, env.soundControl(played, 0), "SOUNDCONTROL 暂停真实文件名所在的声道")
        assertEquals(0L, env.info(played, 3), "改后：信息位 3 归零")
        assertEquals(1L, env.soundControl(played, 2))
        assertEquals(0L, env.info(played, 1), "改后：时长归零")
    }
}
