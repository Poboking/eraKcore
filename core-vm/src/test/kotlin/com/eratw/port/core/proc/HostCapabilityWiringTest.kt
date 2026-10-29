package com.eratw.port.core.proc

import com.eratw.port.core.audio.soundChannelTable
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.SkipState
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M11-A L1 的宿主能力面回归 —— **只钉 HOST-03 音频四件套**。
 *
 * 合流说明：HOST-01（跳过态）与 HOST-02（显示行两面）已由上游 Q1 落地
 * （`d6beb31b` 落每 VM aux 的 `skipState` 槽 + `1e5c0154` 的 `ConsoleLinesWireTest`），
 * 本波原拟的 `skipStateProvider` 闭包与之同责 ⇒ 取上游形态，本文件不再重复钉。
 *
 * 三条缺陷同形：函数读的是**宿主现场**，而生产宿主 `ConsoleBuiltinHost` 当年没把那三处现场
 * 接进来，接口默认值把「未接线」伪装成了「读数 0 / 空 / 假」。本文件两头都钉：
 * 未挂载必须**报错或给未装配形态**（不许静默 0），挂载后必须**现读**同一份真相。
 *
 * 语义出处（逐条 C# 行号另见被测成员的生产 KDoc）：
 * - HOST-03：`GlobalStatic.Sound`（`GlobalStatic.cs:42` 的 `new Sound[10]`）；
 *   `GETSOUNDORBGMINFO` / `SOUNDCONTROL` / `BGMCONTROL` 与 ISPLAYINGSOUND 族读的都是这张表。
 */
class HostCapabilityWiringTest {

    private class BaseCtx : EvalContext {
        override lateinit var variables: com.eratw.port.core.expr.VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    private fun withTempRoot(body: (Path) -> Unit) {
        val root = Files.createTempDirectory("eratw-host-cap")
        try {
            body(root)
        } finally {
            runCatching {
                Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /** 经 `BuiltinHost` 接口引用读，专门压住 `by delegate` 会把带默认实现的成员转给委托的坑。 */
    private fun fn(host: BuiltinHost, name: String): Long {
        val store = VariableStore()
        val base = BaseCtx()
        base.variables = store
        return (BuiltinEvalContext(base, store, host).callFunction(name, emptyList()) as Value.IntV).v
    }

    // ------------------------------------------------------------------
    // HOST-02 — consoleLines / popPrintingLines
    // ------------------------------------------------------------------

    /** 控制台不是 [LineBufferConsoleSink]（含 null）= 未装配形态 → null → 函数抛「宿主未接线」。 */
    @Test
    fun `无控制台行面时给未装配形态`() = withTempRoot { root ->
        val noConsole = ConsoleBuiltinHost(root)
        assertNull(noConsole.consoleLines)
        assertNull(noConsole.popPrintingLines)
    }


    // ------------------------------------------------------------------
    // HOST-03 — 音频四件套
    // ------------------------------------------------------------------

    /** 未接池 = 四项维持接口缺省（0 / false / null）：基准 `SOUND-MISSING` 行依赖这个读数。 */
    @Test
    fun `未接音频池时四项走缺省`() = withTempRoot { root ->
        val host = ConsoleBuiltinHost(root)
        assertEquals(0, host.soundChannels)
        assertEquals(false, host.isPlayingSound(0))
        assertEquals(false, host.isPlayingBgm())
        assertNull(host.audio, "不许伪造静音状态：没接池就是没接")
    }

    /**
     * 接了池则四项**同源单表**：`soundChannels` / `isPlayingSound` / `isPlayingBgm` / `audio`
     * 都取 `store.aux` 的同一张声道表（原版 `GlobalStatic.Sound` 也只有一张）。
     */
    @Test
    fun `音频四项取同一张声道表`() = withTempRoot { root ->
        val store = VariableStore()
        val host = ConsoleBuiltinHost(root, audioStore = store)
        val table = soundChannelTable(store.aux)
        assertSame(table, soundChannelTable(store.aux), "声道表按 aux 槽现取，两次都得是同一张（不另建一份）")
        assertEquals(table.channelCount, host.soundChannels)
        val audio = assertNotNull(host.audio)
        // 空闲态四项同源：没播 BGM 时 `bgmInfo()` 给的是「未播」形态（playing=false），
        // 与 `isPlayingBgm()=false` 必须同一张表的读数，两套口径不许打架
        assertEquals(false, audio.bgmInfo()?.playing)
        assertEquals(false, host.isPlayingBgm())
        assertEquals(false, host.isPlayingSound(0))
    }
}
