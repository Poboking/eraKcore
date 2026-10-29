package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 调试 / 配置 / 文本框 / 音频家族测试（13 条函数）。
 *
 * 基准判据：
 * - `fixtures/golden/baseline/pc-golden-root.txt:534` 的 OUTPUTLOG 成功行
 *   `※※※已创建Log文件GOLDEN_LOG.txt※※※`（调用点 `GOLDEN_09_MISC.ERB:80-81`）
 * - 同文件 :691 `BITMAPCACHE=0`（`GOLDEN_11_GVIEW.ERB:78`）
 * - 配置默认值取自 `Runtime.Config/ConfigData.cs:52-220`
 * - 音频操作码/选择子语义取自 `FunctionMethodCreator.cs:8769-9030`
 */
private class DmBaseCtx(val lines: MutableList<String> = mutableListOf()) : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("DmBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {
        lines += message
    }
}

private class DmHost(
    override val clientWidth: Long = BuiltinHost.DEFAULT_CLIENT_WIDTH,
    override val fontSize: Long = BuiltinHost.DEFAULT_FONT_SIZE,
    override val soundChannels: Int = 0,
    override val fileOps: FileOps? = null,
    override val consoleLines: List<ConsolePrintedLine>? = null,
    override val logHeaderLines: List<String> = emptyList(),
    override val memoryUsageBytes: Long? = null,
    override val textBox: TextBoxHost? = null,
    override val audio: AudioHost? = null,
    override val measureText: ((String) -> Long)? = null,
    override val configOverrides: Map<String, String> = emptyMap(),
) : BuiltinHost

private class DmEnv(host: BuiltinHost = DmHost()) {
    val base = DmBaseCtx()
    val store = VariableStore()
    val ctx: BuiltinEvalContext

    init {
        base.variables = store
        ctx = BuiltinEvalContext(base, store, host)
    }
}

private fun dmInt(ctx: BuiltinEvalContext, name: String, vararg args: Value): Long =
    (ctx.callFunction(name, args.toList()) as Value.IntV).v

private fun dmStr(ctx: BuiltinEvalContext, name: String, vararg args: Value): String =
    (ctx.callFunction(name, args.toList()) as Value.StrV).v

private fun dmArgStr(s: String): Value = Value.StrV(s)
private fun dmArgNum(n: Long): Value = Value.IntV(n)

private class FakeTextBox(start: String = "") : TextBoxHost {
    private var value = start
    override val text: String get() = value
    var moved: Triple<Int, Int, Int>? = null
    var resets: Int = 0

    override fun setText(text: String) {
        value = text
    }

    override fun moveTextBox(x: Int, y: Int, width: Int) {
        moved = Triple(x, y, width)
    }

    override fun resetTextBoxPos() {
        resets++
    }
}

private data class SoundOp(val channel: Int, val op: AudioOp, val speed: Double, val preservePitch: Boolean)
private data class BgmOp(val op: AudioOp, val speed: Double, val preservePitch: Boolean)

private class FakeAudio(
    private val sounds: Map<Int, SoundInfo> = emptyMap(),
    private val bgm: SoundInfo? = null,
) : AudioHost {
    val soundOps = mutableListOf<SoundOp>()
    val bgmOps = mutableListOf<BgmOp>()

    override fun soundInfo(channel: Int): SoundInfo =
        sounds[channel] ?: SoundInfo(0.0, 0.0, false, 0L, 1.0)

    override fun bgmInfo(): SoundInfo? = bgm

    override fun controlSound(channel: Int, op: AudioOp, speed: Double, preservePitch: Boolean) {
        soundOps += SoundOp(channel, op, speed, preservePitch)
    }

    override fun controlBgm(op: AudioOp, speed: Double, preservePitch: Boolean) {
        bgmOps += BgmOp(op, speed, preservePitch)
    }
}

private class DmFileOps(private val failWrite: Boolean = false) : FileOps {
    val writes = LinkedHashMap<String, String>()

    override fun exists(path: String): Boolean = writes.containsKey(path)

    override fun readText(path: String): String? = writes[path]

    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        if (failWrite) return false
        writes[path] = if (append) (writes[path] ?: "") + text else text
        return true
    }

    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

class DebugMiscMethodsTest {

    // ==================================================================
    // GETCONFIG / GETCONFIGS
    // ==================================================================

    @Test
    fun `GETCONFIG 默认值与英文键`() {
        val env = DmEnv()
        // EngText 在构造时已转大写；输入再大写后命中（ConfigData.cs:262-282）
        assertEquals(760L, dmInt(env.ctx, "GETCONFIG", dmArgStr("Window width")))
        assertEquals(18L, dmInt(env.ctx, "GETCONFIG", dmArgStr("FONT SIZE")))
        assertEquals(1L, dmInt(env.ctx, "GETCONFIG", dmArgStr("Ignore case")))
        assertEquals(12632256L, dmInt(env.ctx, "GETCONFIG", dmArgStr("Text color")))
        // REPLACE 区只比较 Name / Text（ConfigData.cs:301-313），Name 因 EnumsNET 混合
        // 大小写永不命中大写 key，故这里只能走日文 Text。
        assertEquals(4L, dmInt(env.ctx, "GETCONFIG", dmArgStr("PBANDの初期値")))
        assertEquals(0L, dmInt(env.ctx, "GETCONFIG", dmArgStr("RELATIONの初期値")))
        assertEquals("\$", dmStr(env.ctx, "GETCONFIGS", dmArgStr("お金の単位")))
        assertEquals(1L, dmInt(env.ctx, "GETCONFIG", dmArgStr("単位の位置")))
        assertEquals("*", dmStr(env.ctx, "GETCONFIGS", dmArgStr("BAR文字1")))
        assertEquals("TEXTRENDERER", dmStr(env.ctx, "GETCONFIGS", dmArgStr("Drawing interface")))
    }

    @Test
    fun `DEBUG 区三处日文标签按 C# 原文取ウィ 其余保留ウイ`() {
        val env = DmEnv()
        // ConfigData.cs:156-158 原文是 デバッグウィンドウ…（ウィ）；转写曾误作 ウイ
        assertEquals(0L, dmInt(env.ctx, "GETCONFIG", dmArgStr("デバッグウィンドウ位置を指定する")))
        assertEquals(0L, dmInt(env.ctx, "GETCONFIG", dmArgStr("デバッグウィンドウ位置X")))
        assertEquals(0L, dmInt(env.ctx, "GETCONFIG", dmArgStr("デバッグウィンドウ位置Y")))
        // 同区另外两条 C# 原文就是 ウイ（ConfigData.cs:152-153），不得顺手统一
        assertEquals(1L, dmInt(env.ctx, "GETCONFIG", dmArgStr("起動時にデバッグウインドウを表示する")))
        assertEquals(1L, dmInt(env.ctx, "GETCONFIG", dmArgStr("デバッグウインドウを最前面に表示する")))
        // 改正后的旧错形不再命中
        assertFailsWith<CodeEE> { dmInt(env.ctx, "GETCONFIG", dmArgStr("デバッグウインドウ位置X")) }
    }

    @Test
    fun `GETCONFIG 复刻 Name 分支永不命中的原版缺陷`() {
        val env = DmEnv()
        // key 大写后与混合大小写的 Name 比较，永远不等（ConfigData.cs:267-281）
        assertFailsWith<CodeEE> { dmInt(env.ctx, "GETCONFIG", dmArgStr("WindowX")) }
        assertFailsWith<CodeEE> { dmInt(env.ctx, "GETCONFIG", dmArgStr("AUTOSAVE")) }
        // 同一配置用 Text / EngText 可命中
        assertEquals(1L, dmInt(env.ctx, "GETCONFIG", dmArgStr("MAKE AUTOSAVES")))
    }

    @Test
    fun `GETCONFIG 类型错误文案互相指向对方`() {
        val env = DmEnv()
        val e1 = assertFailsWith<CodeEE> { dmInt(env.ctx, "GETCONFIG", dmArgStr("Drawing interface")) }
        assertTrue(e1.message!!.contains("GETCONFIGS"), "实际：${e1.message}")
        val e2 = assertFailsWith<CodeEE> { dmStr(env.ctx, "GETCONFIGS", dmArgStr("Font size")) }
        assertTrue(e2.message!!.contains("GETCONFIG"), "实际：${e2.message}")
    }

    @Test
    fun `GETCONFIG 非法键与空串`() {
        val env = DmEnv()
        assertFailsWith<CodeEE> { dmInt(env.ctx, "GETCONFIG", dmArgStr("")) }
        assertFailsWith<CodeEE> { dmInt(env.ctx, "GETCONFIG", dmArgStr("NO SUCH CONFIG KEY")) }
        assertFailsWith<CodeEE> { dmInt(env.ctx, "GETCONFIG", dmArgNum(1)) }
    }

    @Test
    fun `GETCONFIGS 列表型配置输出 NET 类型名`() {
        val env = DmEnv()
        // ConfigItem.ValueToString 对 List<long> 没有特化分支（ConfigItem.cs:53-87）
        assertEquals(
            "System.Collections.Generic.List`1[System.Int64]",
            dmStr(env.ctx, "GETCONFIGS", dmArgStr("EXPLVの初期値")),
        )
    }

    @Test
    fun `GETCONFIG 宿主覆盖按 TryParse 口径生效且非法值保留默认`() {
        val env = DmEnv(
            DmHost(
                configOverrides = mapOf(
                    "Window width" to "1024",
                    "Font size" to "20",
                    "Text color" to "1,2,3",
                    "Make autosaves" to "NO",
                    "PBANDの初期値" to "not-a-number",
                ),
            ),
        )
        assertEquals(1024L, dmInt(env.ctx, "GETCONFIG", dmArgStr("Window width")))
        assertEquals(20L, dmInt(env.ctx, "GETCONFIG", dmArgStr("Font size")))
        assertEquals(66051L, dmInt(env.ctx, "GETCONFIG", dmArgStr("Text color")))  // (1*256+2)*256+3
        assertEquals(0L, dmInt(env.ctx, "GETCONFIG", dmArgStr("Make autosaves")))
        // 解析失败 → ConfigWarn 口径，保留出厂默认 4
        assertEquals(4L, dmInt(env.ctx, "GETCONFIG", dmArgStr("PBANDの初期値")))
    }

    // ==================================================================
    // 文本框
    // ==================================================================

    @Test
    fun `文本框四个函数在宿主接线后读写状态`() {
        val box = FakeTextBox("初始")
        val env = DmEnv(DmHost(textBox = box))
        assertEquals("初始", dmStr(env.ctx, "GETTEXTBOX"))
        assertEquals(1L, dmInt(env.ctx, "SETTEXTBOX", dmArgStr("改后")))
        assertEquals("改后", box.text)
        assertEquals(1L, dmInt(env.ctx, "MOVETEXTBOX", dmArgNum(10), dmArgNum(20), dmArgNum(300)))
        assertEquals(Triple(10, 20, 300), box.moved)
        assertEquals(1L, dmInt(env.ctx, "RESUMETEXTBOX", dmArgNum(1), dmArgNum(2), dmArgNum(3)))
        assertEquals(1, box.resets)
    }

    @Test
    fun `文本框未接线时四个函数一律抛 CodeEE`() {
        val env = DmEnv()
        val e = assertFailsWith<CodeEE> { dmStr(env.ctx, "GETTEXTBOX") }
        assertTrue(e.message!!.contains("BuiltinHost.textBox"), "实际：${e.message}")
        assertFailsWith<CodeEE> { dmInt(env.ctx, "SETTEXTBOX", dmArgStr("x")) }
        assertFailsWith<CodeEE> { dmInt(env.ctx, "MOVETEXTBOX", dmArgNum(1), dmArgNum(2), dmArgNum(3)) }
        assertFailsWith<CodeEE> { dmInt(env.ctx, "RESUMETEXTBOX", dmArgNum(1), dmArgNum(2), dmArgNum(3)) }
    }

    @Test
    fun `SETTEXTBOX 参数类型检查`() {
        val env = DmEnv(DmHost(textBox = FakeTextBox()))
        assertFailsWith<CodeEE> { dmInt(env.ctx, "SETTEXTBOX", dmArgNum(1)) }
    }

    // ==================================================================
    // 内存 / 日志 / 线宽
    // ==================================================================

    @Test
    fun `GETMEMORYUSAGE 未接线抛错 接线后原样返回`() {
        assertFailsWith<CodeEE> { dmInt(DmEnv().ctx, "GETMEMORYUSAGE") }
        assertEquals(123456789L, dmInt(DmEnv(DmHost(memoryUsageBytes = 123456789L)).ctx, "GETMEMORYUSAGE"))
    }

    @Test
    fun `OUTPUTLOG 复现基准 534 行`() {
        val ops = DmFileOps()
        val env = DmEnv(
            DmHost(
                fileOps = ops,
                consoleLines = listOf(ConsolePrintedLine("LINE-A"), ConsolePrintedLine("LINE-B")),
                logHeaderLines = listOf("HEADER-1", "HEADER-2"),
            ),
        )
        assertEquals(1L, dmInt(env.ctx, "OUTPUTLOG", dmArgStr("GOLDEN_LOG.txt")))
        assertEquals("\uFEFFHEADER-1\r\nHEADER-2\r\nLINE-A\r\nLINE-B\r\n", ops.writes["GOLDEN_LOG.txt"])
        assertEquals(listOf("※※※已创建Log文件GOLDEN_LOG.txt※※※"), env.base.lines)
    }

    @Test
    fun `OUTPUTLOG 省略文件名与隐藏环境头`() {
        val ops = DmFileOps()
        val env = DmEnv(
            DmHost(
                fileOps = ops,
                consoleLines = listOf(ConsolePrintedLine("LINE-A")),
                logHeaderLines = listOf("HEADER-1"),
            ),
        )
        assertEquals(1L, dmInt(env.ctx, "OUTPUTLOG"))
        assertEquals("\uFEFFHEADER-1\r\nLINE-A\r\n", ops.writes["emuera.log"])
        assertEquals(1L, dmInt(env.ctx, "OUTPUTLOG", dmArgStr(""), dmArgNum(1)))
        assertEquals("\uFEFFLINE-A\r\n", ops.writes["emuera.log"])
    }

    @Test
    fun `OUTPUTLOG 父目录与绝对路径静默失败但仍返回 1`() {
        val ops = DmFileOps()
        val env = DmEnv(DmHost(fileOps = ops, consoleLines = emptyList()))
        assertEquals(1L, dmInt(env.ctx, "OUTPUTLOG", dmArgStr("../evil.log")))
        assertEquals(1L, dmInt(env.ctx, "OUTPUTLOG", dmArgStr("..\\evil.log")))
        assertEquals(1L, dmInt(env.ctx, "OUTPUTLOG", dmArgStr("C:/evil.log")))
        assertEquals(1L, dmInt(env.ctx, "OUTPUTLOG", dmArgStr("/tmp/evil.log")))
        assertTrue(ops.writes.isEmpty(), "不应落盘：${ops.writes.keys}")
        assertTrue(env.base.lines.isEmpty(), "不应打印成功行：${env.base.lines}")
    }

    @Test
    fun `OUTPUTLOG 写盘失败时不打印成功行`() {
        val env = DmEnv(DmHost(fileOps = DmFileOps(failWrite = true), consoleLines = emptyList()))
        assertEquals(1L, dmInt(env.ctx, "OUTPUTLOG", dmArgStr("x.log")))
        assertTrue(env.base.lines.isEmpty())
    }

    @Test
    fun `OUTPUTLOG 未接线宿主抛 CodeEE`() {
        val e1 = assertFailsWith<CodeEE> {
            dmInt(DmEnv(DmHost(consoleLines = emptyList())).ctx, "OUTPUTLOG")
        }
        assertTrue(e1.message!!.contains("BuiltinHost.fileOps"), "实际：${e1.message}")
        val e2 = assertFailsWith<CodeEE> {
            dmInt(DmEnv(DmHost(fileOps = DmFileOps())).ctx, "OUTPUTLOG")
        }
        assertTrue(e2.message!!.contains("BuiltinHost.consoleLines"), "实际：${e2.message}")
    }

    @Test
    fun `GETLINESTR 默认等宽近似下短横线恰好 84 个`() {
        // DrawableWidth = 760 - max(2, 18/6) = 757；半角 9px：85 个越界，裁剪到 84
        val out = dmStr(DmEnv().ctx, "GETLINESTR", dmArgStr("-"))
        assertEquals(84, out.length)
        assertTrue(out.all { it == '-' }, "实际：$out")
    }

    @Test
    fun `GETLINESTR 宿主测量函数优先`() {
        val host = DmHost(measureText = { s -> s.length * 10L })
        // 76 个 = 760 > 757，裁剪到 75 个 = 750
        assertEquals(75, dmStr(DmEnv(host).ctx, "GETLINESTR", dmArgStr("-")).length)
    }

    @Test
    fun `GETLINESTR 空串与零宽输入`() {
        assertFailsWith<CodeEE> { dmStr(DmEnv().ctx, "GETLINESTR", dmArgStr("")) }
        val e = assertFailsWith<CodeEE> {
            dmStr(DmEnv(DmHost(measureText = { 0L })).ctx, "GETLINESTR", dmArgStr("-"))
        }
        assertTrue(e.message!!.contains("无法确定长度"), "实际：${e.message}")
    }

    @Test
    fun `BITMAP_CACHE_ENABLE 复现基准 691 行的 0`() {
        val env = DmEnv()
        assertEquals(0L, dmInt(env.ctx, "BITMAP_CACHE_ENABLE", dmArgNum(1)))
        assertEquals(0L, dmInt(env.ctx, "BITMAP_CACHE_ENABLE", dmArgNum(0)))
    }

    // ==================================================================
    // 音频
    // ==================================================================

    @Test
    fun `GETSOUNDORBGMINFO 无第二参写 RESULT 0 到 4`() {
        val audio = FakeAudio(
            sounds = mapOf(0 to SoundInfo(3.0, 1.0, false, 55L, 1.0)),
            bgm = SoundInfo(12.5, 0.5, true, 77L, 1.5),
        )
        val env = DmEnv(DmHost(soundChannels = 2, audio = audio))
        assertEquals(3000L, dmInt(env.ctx, "GETSOUNDORBGMINFO", dmArgNum(0)))
        assertEquals(
            listOf(3000L, 1000L, 0L, 55L, 100L),
            (0..4).map { env.store.readInt("RESULT", longArrayOf(it.toLong())) },
        )
        assertEquals(12500L, dmInt(env.ctx, "GETSOUNDORBGMINFO", dmArgNum(-1)))
        assertEquals(
            listOf(12500L, 500L, 1L, 77L, 150L),
            (0..4).map { env.store.readInt("RESULT", longArrayOf(it.toLong())) },
        )
    }

    @Test
    fun `GETSOUNDORBGMINFO 选择子 1 到 5`() {
        val audio = FakeAudio(bgm = SoundInfo(12.5, 0.5, true, 77L, 1.5))
        val env = DmEnv(DmHost(soundChannels = 2, audio = audio))
        for ((sel, expected) in listOf(1L to 12500L, 2L to 500L, 3L to 1L, 4L to 77L, 5L to 150L)) {
            assertEquals(expected, dmInt(env.ctx, "GETSOUNDORBGMINFO", dmArgNum(-1), dmArgNum(sel)), "选择子 $sel")
        }
        assertEquals(0L, dmInt(env.ctx, "GETSOUNDORBGMINFO", dmArgNum(-1), dmArgNum(0)))
        assertEquals(0L, dmInt(env.ctx, "GETSOUNDORBGMINFO", dmArgNum(-1), dmArgNum(6)))
    }

    @Test
    fun `GETSOUNDORBGMINFO 无 BGM 或声道越界返回 0`() {
        val audio = FakeAudio()
        val env = DmEnv(DmHost(soundChannels = 2, audio = audio))
        env.store.writeInt("RESULT", longArrayOf(0), 999L)
        assertEquals(0L, dmInt(env.ctx, "GETSOUNDORBGMINFO", dmArgNum(-1)))
        assertEquals(0L, dmInt(env.ctx, "GETSOUNDORBGMINFO", dmArgNum(2)))
        assertEquals(0L, dmInt(env.ctx, "GETSOUNDORBGMINFO", dmArgNum(-2)))
        assertEquals(999L, env.store.readInt("RESULT", longArrayOf(0)), "无快照时不应改写 RESULT")
    }

    @Test
    fun `SOUNDCONTROL 操作码与参数个数`() {
        val audio = FakeAudio()
        val env = DmEnv(DmHost(soundChannels = 2, audio = audio))
        assertEquals(1L, dmInt(env.ctx, "SOUNDCONTROL", dmArgNum(0), dmArgNum(0)))
        assertEquals(1L, dmInt(env.ctx, "SOUNDCONTROL", dmArgNum(1), dmArgNum(1)))
        assertEquals(1L, dmInt(env.ctx, "SOUNDCONTROL", dmArgNum(0), dmArgNum(2)))
        assertEquals(
            listOf(
                SoundOp(0, AudioOp.PAUSE, 1.0, true),
                SoundOp(1, AudioOp.RESUME, 1.0, true),
                SoundOp(0, AudioOp.STOP, 1.0, true),
            ),
            audio.soundOps,
        )
        // 带第三参的 0/1/2 不是合法操作（:8910-8945）
        assertEquals(-2L, dmInt(env.ctx, "SOUNDCONTROL", dmArgNum(0), dmArgNum(0), dmArgNum(100)))
        assertEquals(-1L, dmInt(env.ctx, "SOUNDCONTROL", dmArgNum(-1), dmArgNum(0)))
        assertEquals(-1L, dmInt(env.ctx, "SOUNDCONTROL", dmArgNum(2), dmArgNum(0)))
        assertEquals(-2L, dmInt(env.ctx, "SOUNDCONTROL", dmArgNum(0), dmArgNum(4)))
    }

    @Test
    fun `SOUNDCONTROL 变速与音高标志`() {
        val audio = FakeAudio()
        val env = DmEnv(DmHost(soundChannels = 2, audio = audio))
        assertEquals(1L, dmInt(env.ctx, "SOUNDCONTROL", dmArgNum(0), dmArgNum(3), dmArgNum(150)))
        assertEquals(1L, dmInt(env.ctx, "SOUNDCONTROL", dmArgNum(1), dmArgNum(3), dmArgNum(50), dmArgNum(1)))
        assertEquals(
            listOf(
                SoundOp(0, AudioOp.SET_SPEED, 1.5, true),
                SoundOp(1, AudioOp.SET_SPEED, 0.5, false),
            ),
            audio.soundOps,
        )
    }

    @Test
    fun `BGMCONTROL 操作码与参数个数`() {
        val audio = FakeAudio()
        val env = DmEnv(DmHost(soundChannels = 2, audio = audio))
        assertEquals(1L, dmInt(env.ctx, "BGMCONTROL", dmArgNum(0)))
        assertEquals(1L, dmInt(env.ctx, "BGMCONTROL", dmArgNum(1)))
        assertEquals(1L, dmInt(env.ctx, "BGMCONTROL", dmArgNum(2)))
        assertEquals(-2L, dmInt(env.ctx, "BGMCONTROL", dmArgNum(0), dmArgNum(50)))
        assertEquals(-2L, dmInt(env.ctx, "BGMCONTROL", dmArgNum(4)))
        assertEquals(1L, dmInt(env.ctx, "BGMCONTROL", dmArgNum(3), dmArgNum(50)))
        assertEquals(1L, dmInt(env.ctx, "BGMCONTROL", dmArgNum(3), dmArgNum(200), dmArgNum(1)))
        assertEquals(
            listOf(
                BgmOp(AudioOp.PAUSE, 1.0, true),
                BgmOp(AudioOp.RESUME, 1.0, true),
                BgmOp(AudioOp.STOP, 1.0, true),
                BgmOp(AudioOp.SET_SPEED, 0.5, true),
                BgmOp(AudioOp.SET_SPEED, 2.0, false),
            ),
            audio.bgmOps,
        )
    }

    @Test
    fun `音频三函数未接线时抛 CodeEE`() {
        val env = DmEnv(DmHost(soundChannels = 2))
        val e = assertFailsWith<CodeEE> { dmInt(env.ctx, "GETSOUNDORBGMINFO", dmArgNum(0)) }
        assertTrue(e.message!!.contains("BuiltinHost.audio"), "实际：${e.message}")
        assertFailsWith<CodeEE> { dmInt(env.ctx, "SOUNDCONTROL", dmArgNum(0), dmArgNum(0)) }
        assertFailsWith<CodeEE> { dmInt(env.ctx, "BGMCONTROL", dmArgNum(0)) }
    }
}
