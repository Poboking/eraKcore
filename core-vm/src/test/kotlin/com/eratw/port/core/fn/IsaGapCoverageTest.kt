package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.ConsoleButtonPointing
import com.eratw.port.core.host.MouseState
import com.eratw.port.core.host.flowInputFlagsOf
import com.eratw.port.core.host.mouseStateOf
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SpCallSharpArgument
import com.eratw.port.core.stmt.StmtKind
import com.eratw.port.core.vars.VariableStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ISA-GAP 对拍审计（CONTRACT-M4-M §2.6，路 `m-isagap`）。
 *
 * **性质**：对拍审计，不是补函数。本文件**只钉现状读数**，产品侧零改动。
 * 基准 fixtures/golden 目录一个字节未动 ⇒ `tools/golden-coverage.py` 的 19 个 MISS 函数名
 * 与 3 条 MISS 语句名**不会**因为本文件而变化（覆盖率量的是 GOLDEN 下 ERB 里出现过没有，
 * 不是产品实现有没有）。覆盖率没命中 = 基准没覆盖，不是移植侧缺失。
 *
 * 22 个名字的逐条处置（① 与原版一致 = 钉桩、② 官方 bug 已复刻、③ 分歧 = 交组长接线）
 * 在下列每个测试的 KDoc 与行内注释里给出；C# 证据行号一律指
 * `reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`（FMCreator）、
 * `MinorShift.Emuera.GameView/EmueraConsole.cs`、`MinorShift.Emuera.Runtime.Utils.EvilMask/Lang.cs`。
 *
 * 装置：走**生产注册表** [Builtins.registry]（不是局部注册表），分派口径与
 * [BuiltinEvalContext.callFunction] 一致（`checkArity` → `eval`），这样钉的读数就是产线读数。
 * 同款装置见 [DispatchSwitchTest]。
 *
 * 语料命中数（`grep -c` 于 `eratw-画蛇添足版eratw4.980/ERB`，只读）逐条写在用例 KDoc 里。
 */
private class IgBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("IgBaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) = Unit
}

/**
 * 可控宿主：钉钟、内存读数、跳过状态、文本测量、音频宿主、配置覆盖。
 *
 * [soundChannels] 缺 0 与 [audio] 缺 null 的取舍见 [DispatchSwitchTest] 的 DsHost：
 * 未接音频时原版也没有设备，本路只观测「有没有被调用」。
 */
private class IgHost(
    private val now: Long = PINNED_EPOCH_MILLIS,
    private val memory: Long? = null,
    override val audio: AudioHost? = null,
    override val skipState: SkipState? = null,
    override val measureText: ((String) -> Long)? = null,
    override val configOverrides: Map<String, String> = emptyMap(),
) : BuiltinHost {
    override fun clockMillis(): Long = now
    override val memoryUsageBytes: Long? get() = memory
    override val soundChannels: Int get() = if (audio == null) 0 else 10
}

private const val PINNED_EPOCH_MILLIS = 1_780_000_000_000L

/** 0001-01-01 到 1970-01-01 的毫秒数（= TimeMethods.NET_EPOCH_OFFSET_MILLIS，:4008 的 Ticks 基准换算）。 */
private const val NET_EPOCH = 62_135_596_800_000L

/** 生产注册表 + 一份 [VariableStore]（因此一份 aux 鼠标现场 / aux 配置快照）。 */
private class IgEnv(
    host: BuiltinHost = IgHost(),
    private val registry: BuiltinRegistry = Builtins.registry,
) {
    val store = VariableStore()
    val ctx: BuiltinEvalContext

    init {
        val base = IgBaseCtx().apply { variables = store }
        ctx = BuiltinEvalContext(base, store, host)
    }

    val mouse: MouseState get() = mouseStateOf(store.aux)
    val flow: com.eratw.port.core.host.FlowInputFlags get() = flowInputFlagsOf(store.aux)

    fun call(name: String, args: List<Value?> = emptyList()): Value {
        val m = registry[name] ?: error("函数 $name 未注册")
        m.checkArity(args.size)
        return m.eval!!.invoke(ctx, args)
    }

    fun i(name: String, vararg args: Value?): Long = (call(name, args.toList()) as Value.IntV).v
    fun s(name: String, vararg args: Value?): String = (call(name, args.toList()) as Value.StrV).v
    fun method(name: String): BuiltinMethod = registry[name] ?: error("函数 $name 未注册")
    fun isPlaceholder(name: String): Boolean = registry.isPlaceholder(name)

    companion object {
        fun s(v: String): Value = Value.StrV(v)
        fun i(v: Long): Value = Value.IntV(v)
    }
}

// ===========================================================================
// 第 1 段：MOUSE 族六条（MOUSEX / MOUSEY / MOUSEB / MOUSESKIP / FLOWINPUT / FLOWINPUTS）
// ===========================================================================

/**
 * MOUSE 族对拍（FMCreator 注册 :9165 / :9166 / :9167 / :9065 / :9300 / :9301）。
 *
 * 契约书列的「移植侧 `core/fn/InputMethods.kt:116-119` 直读 `inputState.mouseY`（正数）」
 * 与「窗口未创建抛 CodeEE」两条分歧，自 **M4-I i-mouse** 起已由 `MouseMethods.kt`
 * 在 [Builtins.buildRegistry] 末尾覆盖掉（该六条同名注册在 InputMethods 已删，见
 * Builtins.kt:666-670 的接线注释）。本段的职责 = 把「已经对齐」钉成回归闸门：
 * 任何后续接线把 Y 位移或 (0,0) 口径改回去，这里立刻变红。
 */
class IsaGapCoverageTest {

    /** 原版窗口口径：客户区 760 x 461（FMCreator :7879 的 ClientRectangle、:2264 的 ClientHeight）。 */
    private fun window(w: Int = 760, h: Int = 461, x: Int, y: Int): Pair<MouseState, IgEnv> {
        val env = IgEnv()
        env.mouse.setClientSize(w, h)
        env.mouse.windowCreated = true
        env.mouse.setPointerPosition(x, y)
        return env.mouse to env
    }

    @Test
    fun MOUSEX_取客户区原始X_不做位移() {
        // FMCreator :7853 / :7861（.X 原样）；EmueraConsole.cs:2263 PointToClient 之后即为读数
        val (_, env) = window(x = 100, y = 200)
        assertEquals(100L, env.i("MOUSEX"))
        assertEquals(OperandType.INT, env.method("MOUSEX").retType)
        // 语料命中：MOUSEX 5 处 / 2 个文件
    }

    @Test
    fun MOUSEY_必须再减ClientHeight_客户区内恒为负() {
        // EmueraConsole.cs:2264 `result.Y -= ClientHeight`；FMCreator :7855-7857 取 .Y
        // 原版官方口径（反直觉），ADR-005 原样复刻 ⇒ 处置①+②：与原版一致且复刻了原版的减法。
        val (_, env) = window(x = 100, y = 200)
        assertEquals(-261L, env.i("MOUSEY"))   // 200 - 461
        // 可视区底边最后一格 = -1（原版唯一能给 0 的格内读数是 y = Height，但那已越界）
        env.mouse.setPointerPosition(100, 460)
        assertEquals(-1L, env.i("MOUSEY"))
        // 顶边 = -ClientHeight
        env.mouse.setPointerPosition(100, 0)
        assertEquals(-461L, env.i("MOUSEY"))
        // 语料命中：MOUSEY 1 处 / 1 个文件
    }

    @Test
    fun 窗口未创建时MOUSEX与MOUSEY双0且不抛() {
        // EmueraConsole.cs:2259-2262：window == null 或 !Created → default(Point) = (0,0)，
        // 且**连 Y 位移都不做**。移植侧 MouseState.kt:94/:100 的 windowCreated 门。
        // 契约书列的「移植侧抛 CodeEE」分歧已由 M4-I 消除；本用例钉住「不抛 + 双 0」。
        val env = IgEnv()
        assertFalse(env.mouse.windowCreated)
        assertEquals(0L, env.i("MOUSEX"))
        assertEquals(0L, env.i("MOUSEY"))
    }

    @Test
    fun MOUSEB_客户区内刷新指向并返回按钮串() {
        // FMCreator :7876-7883 置 AlwaysRefresh → :7879 ClientRectangle.Contains → :7881 MoveMouse
        val (m, env) = window(x = 100, y = 200)
        val hit = RecordingPointing(ConsoleButtonPointing(isButton = true, inputs = "はい"))
        m.hitTest = { x, y -> hit.calls++; hit.lastX = x; hit.lastY = y; hit.result }
        m.alwaysRefresh = false
        assertEquals("はい", env.s("MOUSEB"))       // :7893-7894 字符串按钮 → Inputs
        assertEquals(1, hit.calls)
        assertEquals(100, hit.lastX)                 // :7877 传的是**未位移**的原始点
        assertEquals(200, hit.lastY)
        assertFalse(m.alwaysRefresh)                 // :7883 finally 还原
        // 语料命中：MOUSEB 8 处 / 3 个文件
    }

    @Test
    fun MOUSEB_越界时不调命中测试_返回过期指向串() {
        // 官方 bug 复刻点：FMCreator :7879 判越界 ⇒ :7881 的 if 不成立 ⇒ **不调 MoveMouse**，
        // 于是 :7884-7896 读到的还是上一次留下的 PointingSring（可能早已过期）。
        // ADR-005 原样复刻 ⇒ 处置②。移植侧 MouseState.kt:119-131（isPointerInClientArea :110-111）。
        val (m, env) = window(x = 100, y = 200)
        val hit = RecordingPointing(ConsoleButtonPointing(isButton = true, inputs = "新值"))
        m.hitTest = { x, y -> hit.calls++; hit.result }
        assertEquals("新值", env.s("MOUSEB"))       // 先在区内刷一次
        assertEquals(1, hit.calls)
        // 半开区间右界：x = 760 已越界（WinForms Rectangle.Contains 为 [0, Width)）
        m.setPointerPosition(760, 200)
        assertEquals("新值", env.s("MOUSEB"))       // **过期值原样返回**
        assertEquals(1, hit.calls)                   // 命中测试没有被调用（这就是官方行为）
        // 半开区间下界：y = 461 越界
        m.setPointerPosition(100, 461)
        assertEquals("新值", env.s("MOUSEB"))
        assertEquals(1, hit.calls)
        // 负坐标同样越界
        m.setPointerPosition(-1, 0)
        assertEquals("新值", env.s("MOUSEB"))
        assertEquals(1, hit.calls)
    }

    @Test
    fun MOUSEB_四条返回分支_空指向与非按钮段回空串() {
        // FMCreator :7884 判空 → :7896 `""`；:7886-7889 非按钮段 → `""`；
        // :7890-7892 整型按钮 → Input.ToString()（十进制串，不是位掩码）；:7893-7894 → Inputs。
        val (m, env) = window(x = 100, y = 200)
        // 分支 1：无指向
        m.pointing = null
        assertEquals("", env.s("MOUSEB"))
        // 分支 2：纯文本段（isButton = false）
        m.pointing = ConsoleButtonPointing(isButton = false, inputs = "正文")
        assertEquals("", env.s("MOUSEB"))
        // 分支 3：整型按钮（ConsoleButtonString.cs:84-87 的 Inputs = input.ToString() 同值）
        m.pointing = ConsoleButtonPointing(isButton = true, isInteger = true, input = 12345L)
        assertEquals("12345", env.s("MOUSEB"))
        m.pointing = ConsoleButtonPointing(isButton = true, isInteger = true, input = -7L)
        assertEquals("-7", env.s("MOUSEB"))
        // 分支 4：字符串按钮
        m.pointing = ConsoleButtonPointing(isButton = true, inputs = "次へ")
        assertEquals("次へ", env.s("MOUSEB"))
        // MOUSEB 返回类型必须是字符串（:7869 ReturnType = typeof(string)）
        assertEquals(OperandType.STR, env.method("MOUSEB").retType)
    }

    @Test
    fun MOUSEB_窗口未创建时不崩溃且保留旧指向() {
        // 原版 :7877 无 Created 防护 ⇒ 无窗口时 NRE（框架崩溃，不是可读回值）。
        // 移植侧**不复刻崩溃**，按「视为越界、不刷新」处理（MouseState.kt:123）。
        // 这是有意偏离崩溃语义的一处：登记为处置③的「不可对拍项」，不是数值分歧。
        val env = IgEnv()
        env.mouse.setClientSize(760, 461)
        env.mouse.windowCreated = false
        env.mouse.pointing = ConsoleButtonPointing(isButton = true, inputs = "上一次")
        assertNull(env.mouse.hitTest)
        assertEquals("上一次", env.s("MOUSEB"))
        assertFalse(env.mouse.windowCreated)
    }

    @Test
    fun MOUSESKIP_返回MesSkip的1或0并投递弃用警告原文() {
        // FMCreator :9065 = MesSkipMethod(warn = true)，类体 :3617-3646。
        // 取值 :3642-3645 = Console.MesSkip ? 1 : 0；警告 :3635-3638。
        // 模板 Lang.cs:3850 FuncDeprecated；第二实参是**硬编码字面量 "MESSKIP"**（:3637）。
        // 零参（argumentTypeArray = null :3624）⇒ 传参时 checkArity 先抛。
        var warned: String? = null
        val env = IgEnv().also { skipStateSlot(it.store.aux).mesSkip = true }
        env.mouse.warnRecorder = { warned = it }
        assertEquals(1L, env.i("MOUSESKIP"))
        assertEquals(MOUSESKIP_WARNING_TEXT, warned)
        assertEquals(
            "関数MOUSESKIP()は推奨されません。代わりに関数MESSKIP()を使用してください",
            warned,
        )
        warned = null
        val off = IgEnv().also { skipStateSlot(it.store.aux).mesSkip = false }
        off.mouse.warnRecorder = { warned = it }
        assertEquals(0L, off.i("MOUSESKIP"))
        assertEquals(MOUSESKIP_WARNING_TEXT, warned)
        // 未接警告通道时静默（现状；见 MouseMethods.kt:60-63 的 TODO(verify)：解析期警告缺位）
        val quiet = IgEnv().also { skipStateSlot(it.store.aux).mesSkip = true }
        assertEquals(1L, quiet.i("MOUSESKIP"))
        assertNull(quiet.mouse.warnRecorder)
    }

    @Test
    fun MOUSESKIP_宿主未接跳过状态时抛CodeEE_零参以外先报个数() {
        // 现状钉桩：aux 跳过状态槽缺失（无语句层）→ CodeEE（MouseMethods.kt:163-167）。
        // 处置③候选之一，但**不是与原版分歧**：原版永远有 Console 实例，
        // 移植侧「宿主未接线」本身就是未接线状态，不静默 0 是刻意的（理由见 InputMethods 类 KDoc）。
        val env = IgEnv()
        val e = assertFailsWith<CodeEE> { env.i("MOUSESKIP") }
        assertTrue(e.message!!.contains("MOUSESKIP"), "异常文案应含函数名：${e.message}")
        assertTrue(
            e.message!!.contains("skipState == null"),
            "异常文案应含判据：${e.message}",
        )
        // 超参：FMCreator :3631-3634 先查个数并直接 return ⇒ 移植侧交给 checkArity（顺序一致）
        assertFailsWith<CodeEE> { env.method("MOUSESKIP").checkArity(1) }
    }

    @Test
    fun FLOWINPUT_写六个槽中的四个并恒返回0() {
        // FMCreator :9300 注册，类体 :8461-8500；ArgTypeList 四元 + OmitStart = 1（:8466-8479）
        // ⇒ 实参 1..4 个。恒返回 0L（:8498）——原版就是常量 0 ⇒ 处置①（钉桩）。
        val env = IgEnv()
        assertEquals(0L, env.i("FLOWINPUT", IgEnv.i(9L)))
        assertEquals(9L, env.flow.flowinputDef)          // :8485 原样存 long，无 !=0 转换
        assertFalse(env.flow.flowinput)                   // 未传的槽保持初值（Process.cs:185）
        assertEquals(0L, env.i("FLOWINPUT", IgEnv.i(0L), IgEnv.i(2L), IgEnv.i(1L), IgEnv.i(-1L)))
        assertEquals(0L, env.flow.flowinputDef)           // :8485：0 就是 0，不做真假转换
        assertTrue(env.flow.flowinput)                    // :8486-8489 非 0 → true
        assertTrue(env.flow.flowinputCanSkip)             // :8490-8493
        assertTrue(env.flow.flowinputForceSkip)           // :8494-8497
        // 第 2..4 参数传 null（省略实参）时**不覆盖**既有值：移植侧判据 a[i] != null
        assertEquals(0L, env.i("FLOWINPUT", IgEnv.i(5L), null, null, null))
        assertEquals(5L, env.flow.flowinputDef)
        assertTrue(env.flow.flowinput)                    // 保留上一次的 true
        // 个数下限 1（OmitStart = 1）
        assertFailsWith<CodeEE> { env.method("FLOWINPUT").checkArity(0) }
        assertFailsWith<CodeEE> { env.method("FLOWINPUT").checkArity(5) }
        // 语料命中：FLOWINPUT 0 处
    }

    @Test
    fun FLOWINPUTS_写两个字符串槽并恒返回0() {
        // FMCreator :9301，类体 :8502-8531；ArgTypeList{Int,String} OmitStart = 1（:8507-8516）
        // ⇒ 1..2 个；恒返回 0L（:8529）⇒ 处置①。
        val env = IgEnv()
        assertEquals(0L, env.i("FLOWINPUTS", IgEnv.i(1L), IgEnv.s("既定")))
        assertTrue(env.flow.flowinputString)               // :8524
        assertEquals("既定", env.flow.flowinputDefString)  // :8525-8528
        assertEquals(0L, env.i("FLOWINPUTS", IgEnv.i(0L)))
        assertFalse(env.flow.flowinputString)              // :8524 非 0 才 true
        assertEquals("既定", env.flow.flowinputDefString)  // 省略第 2 参：不覆盖
        assertFailsWith<CodeEE> { env.method("FLOWINPUTS").checkArity(0) }
        assertFailsWith<CodeEE> { env.method("FLOWINPUTS").checkArity(3) }
        // 原版消费侧的官方坑（Process.cs:2055 总闸）：flowinput 为 false 时
        // :2060 的 DefStrValue 根本不赋值 ⇒ 这里写的默认串被丢掉。**本路不修**（ADR-005），
        // 消费方接线由 M4 的 WAIT 面负责（登记在 MouseState.kt:181-188）。
        // 语料命中：FLOWINPUTS 0 处
    }
}

/** MOUSEB 命中测试的记录替身（本路只观测「有没有被调」与「传了哪个点」）。 */
private class RecordingPointing(var result: ConsoleButtonPointing?) {
    var calls = 0
    var lastX = Int.MIN_VALUE
    var lastY = Int.MIN_VALUE
}

// ===========================================================================
// 第 3 段：AUDIO 族三条
//   BGMCONTROL / SOUNDCONTROL / GETSOUNDORBGMINFO
// ===========================================================================

/** 记录型音频宿主：只观测「调了哪个操作、传了什么倍率」，不出真声。 */
private class IgAudioHost(
    var bgm: SoundInfo? = SoundInfo(
        totalSeconds = 3.5,
        currentSeconds = 1.25,
        playing = true,
        volume = 100L,
        speed = 1.5,
    ),
    var sound: SoundInfo = SoundInfo(
        totalSeconds = 2.0,
        currentSeconds = 0.5,
        playing = false,
        volume = 80L,
        speed = 1.0,
    ),
) : AudioHost, AudioChannelMaterializer {
    val log = mutableListOf<String>()

    override fun soundInfo(channel: Int): SoundInfo {
        log += "soundInfo($channel)"
        return sound
    }

    override fun bgmInfo(): SoundInfo? {
        log += "bgmInfo"
        return bgm
    }

    override fun controlSound(channel: Int, op: AudioOp, speed: Double, preservePitch: Boolean) {
        log += "controlSound($channel,$op,$speed,$preservePitch)"
    }

    override fun controlBgm(op: AudioOp, speed: Double, preservePitch: Boolean) {
        log += "controlBgm($op,$speed,$preservePitch)"
    }

    override fun materializeSound(channel: Int): Boolean {
        log += "materializeSound($channel)"
        return true
    }

    override fun materializeBgm() {
        log += "materializeBgm"
    }
}

/**
 * 音频族对拍（FMCreator 注册 :9308 / :9310 / :9312）。
 *
 * C# 类体：`GetSoundOrBgmInfoMethod` :8769-8828、`SoundControlMethod` :8874-8947、
 * `BgmControlMethod` :8968-9031；移植侧全部在 `core/fn/AudioMethods.kt`
 * （注册 :132-134，函数体 :171-265），M4-J j-dispatch 已从 `DebugMiscMethods.kt` 接管。
 *
 * 三条共同的「宿主未接线」口径：`audioHost()`（AudioMethods.kt:291-293）抛 CodeEE，
 * 不静默返回 0。
 */
class IsaGapAudioFamilyTest {

    private fun env(audio: AudioHost?) = IgEnv(IgHost(audio = audio))

    @Test
    fun 三条音频函数在未接宿主时各抛一条原文() {
        // 现状钉桩：接线后必须同步改（届时这三条应改为真读数，见 AudioMethods.kt:291-293）
        val e = env(null)
        for ((name, args) in listOf(
            "GETSOUNDORBGMINFO" to listOf(Value.IntV(0L)),
            "SOUNDCONTROL" to listOf(Value.IntV(0L), Value.IntV(0L)),
            "BGMCONTROL" to listOf(Value.IntV(0L)),
        )) {
            val ee = assertFailsWith<CodeEE> { e.call(name, args) }
            assertEquals("函数 $name：音频宿主尚未接线（需要宿主注入 BuiltinHost.audio）", ee.message)
        }
        // 三条都仍是注册过的真实现，不是 registerUnimplementedAll 的占位
        for (name in listOf("GETSOUNDORBGMINFO", "SOUNDCONTROL", "BGMCONTROL")) {
            assertFalse(e.isPlaceholder(name), "$name 不应是占位实现")
        }
    }

    @Test
    fun GETSOUNDORBGMINFO_信息位五项与RESULT写入() {
        // FMCreator :8809-8817：单参形态写 RESULT[0..4] 并返回 RESULT[0]。
        // 五项换算（:8811-8815）：总时长 ms = Length*1000、当前位置 ms、播放中 1/0、音量、倍速*100。
        // 组长实测改钉（worker 交回的是**未跑过的猜测**）：实参 0 是**声道 0**，走
        // `audio.soundInfo(0)`（AudioMethods.kt:176-178，spec: :8797-8804）⇒ 读的是假体里的
        // `sound`（total 2.0 / current 0.5 / playing false / volume 80 / speed 1.0），
        // 不是 `bgm`（3.5/1.25/true/100/1.5）。BGM 要传 -1（:8793-8796，不惰性建）⇒ 见下面第二条。
        val audio = IgAudioHost()
        val e = env(audio)
        // 哨兵先行：证明下面五行断言读到的确实是本函数写进去的，不是残留值（接线后必须同步改）
        for (i in 0..4) e.store.writeInt("RESULT", longArrayOf(i.toLong()), -777L)
        assertEquals(2000L, e.i("GETSOUNDORBGMINFO", IgEnv.i(0L)))   // 声道 0：2.0s → 2000
        // 副作用逐项钉：RESULT 的第 0..4 格
        listOf(2000L, 500L, 0L, 80L, 100L).forEachIndexed { idx, expected ->
            assertEquals(expected, e.store.readInt("RESULT", longArrayOf(idx.toLong())))
        }
        // 同一假体的 BGM 侧（channel = -1 ⇒ bgmInfo()，:8793-8796）：五项各按同一换算
        assertEquals(3500L, e.i("GETSOUNDORBGMINFO", IgEnv.i(-1L)))
        listOf(3500L, 1250L, 1L, 100L, 150L).forEachIndexed { idx, expected ->
            assertEquals(expected, e.store.readInt("RESULT", longArrayOf(idx.toLong())))
        }
        // 语料命中：GETSOUNDORBGMINFO 10 处 / 2 个文件
    }

    @Test
    fun GETSOUNDORBGMINFO_第二实参的位选择与默认分支() {
        val audio = IgAudioHost()
        val e = env(audio)
        // :8818-8826 的 switch：1..5 选五项，其余（0、6、负数）恒 0
        // 组长实测改钉：声道 0 读的是假体的 `sound`（2.0/0.5/false/80/1.0），不是 `bgm`（见上一条用例的说明）
        assertEquals(2000L, e.i("GETSOUNDORBGMINFO", IgEnv.i(0L), IgEnv.i(1L)))
        assertEquals(500L, e.i("GETSOUNDORBGMINFO", IgEnv.i(0L), IgEnv.i(2L)))
        assertEquals(0L, e.i("GETSOUNDORBGMINFO", IgEnv.i(0L), IgEnv.i(3L)))
        assertEquals(80L, e.i("GETSOUNDORBGMINFO", IgEnv.i(0L), IgEnv.i(4L)))
        assertEquals(100L, e.i("GETSOUNDORBGMINFO", IgEnv.i(0L), IgEnv.i(5L)))
        assertEquals(0L, e.i("GETSOUNDORBGMINFO", IgEnv.i(0L), IgEnv.i(0L)))    // 默认分支
        assertEquals(0L, e.i("GETSOUNDORBGMINFO", IgEnv.i(0L), IgEnv.i(6L)))
        assertEquals(0L, e.i("GETSOUNDORBGMINFO", IgEnv.i(0L), IgEnv.i(-5L)))
        // BGM 侧同一 switch（-1 ⇒ bgmInfo，:8793-8796）
        assertEquals(3500L, e.i("GETSOUNDORBGMINFO", IgEnv.i(-1L), IgEnv.i(1L)))
        assertEquals(150L, e.i("GETSOUNDORBGMINFO", IgEnv.i(-1L), IgEnv.i(5L)))
        // 双参形态**不写** RESULT（:8818-8826 那条分支只 return）——接线后仍应保持
        // 先把 RESULT[0] 灌成哨兵：双参形态**不得**改写 RESULT（:8818-8826 只 return）
        e.store.writeInt("RESULT", longArrayOf(0L), 999L)
        assertEquals(2000L, e.i("GETSOUNDORBGMINFO", IgEnv.i(0L), IgEnv.i(1L)))
        assertEquals(999L, e.store.readInt("RESULT", longArrayOf(0L)), "双参形态不该写 RESULT")
    }

    @Test
    fun GETSOUNDORBGMINFO_的int截断让4294967295读成BGM() {
        // FMCreator :8791 `int num = (int)arguments[0].GetIntValue(exm)` ⇒ 低 32 位截断。
        // 4294967295L.toInt() == -1 ⇒ :8793-8796 走 GlobalStatic.Bgm（BGM_CHANNEL = -1）。
        // 这是 j-dispatch 切换修掉的一条：旧实现无截断，回 0。
        val audio = IgAudioHost()
        val e = env(audio)
        assertEquals(3500L, e.i("GETSOUNDORBGMINFO", IgEnv.i(4_294_967_295L)))
        assertTrue(audio.log.any { it == "bgmInfo" }, "应读 BGM 而非声道，实际：${audio.log}")
        // BGM 为 null（未播 BGM）时 :8793-8796 拿到 null ⇒ 返回 0、不写 RESULT（:8805-8808 同形）
        audio.bgm = null
        assertEquals(0L, e.i("GETSOUNDORBGMINFO", IgEnv.i(-1L)))
    }

    @Test
    fun GETSOUNDORBGMINFO_越界声道静默返回0_合法下标才读快照() {
        // 声道数按原版**定长 10**（Sound.Length = 10，GlobalStatic.cs:42；
        // 移植侧由 BuiltinHost.soundChannels 给出，IgHost 接了音频时恒 10）。
        // :8797-8804 合法下标才取快照；:8805-8808 其余取值**静默**（不报错、不写 RESULT）。
        val audio = IgAudioHost()
        val e = env(audio)
        assertEquals(0L, e.i("GETSOUNDORBGMINFO", IgEnv.i(10L)))     // 上界外
        assertEquals(0L, e.i("GETSOUNDORBGMINFO", IgEnv.i(9999L)))
        assertEquals(0L, e.i("GETSOUNDORBGMINFO", IgEnv.i(-2L)))     // -1 才是 BGM，-2 越界
        assertFalse(audio.log.any { it.startsWith("soundInfo") }, "越界不应取快照：${audio.log}")
        assertEquals(2000L, e.i("GETSOUNDORBGMINFO", IgEnv.i(9L)))   // 合法上界内（2.0s）
        assertTrue(audio.log.any { it == "soundInfo(9)" })
    }

    @Test
    fun SOUNDCONTROL_越界先判_合法才物化_操作码分派在后() {
        // FMCreator :8906-8915：(int) 截断 → **越界判定早于建道** → 合法下标先物化再分派。
        val audio = IgAudioHost()
        val e = env(audio)
        // 越界：静默 -1，一条宿主动作都不调（:8908-8911）
        assertEquals(-1L, e.i("SOUNDCONTROL", IgEnv.i(10L), IgEnv.i(0L)))
        assertEquals(-1L, e.i("SOUNDCONTROL", IgEnv.i(-1L), IgEnv.i(0L)))
        assertTrue(audio.log.isEmpty(), "越界路径不该有宿主调用：${audio.log}")
        // 合法 + 恰好 2 参：0/1/2 = 暂停/继续/停止，三条都回 1（:8916-8932）
        assertEquals(1L, e.i("SOUNDCONTROL", IgEnv.i(0L), IgEnv.i(0L)))
        assertEquals(1L, e.i("SOUNDCONTROL", IgEnv.i(0L), IgEnv.i(1L)))
        assertEquals(1L, e.i("SOUNDCONTROL", IgEnv.i(0L), IgEnv.i(2L)))
        assertTrue(audio.log.first() == "materializeSound(0)", "先物化后分派：${audio.log}")
        // 2 参形态下操作码 3 → -2（:8916-8932 的 else）
        assertEquals(-2L, e.i("SOUNDCONTROL", IgEnv.i(0L), IgEnv.i(3L)))
        // 3/4 参形态：只有操作码 3 有效，倍率 = (float)实参/100f（:8935，纯 binary32 除法）
        audio.log.clear()
        assertEquals(1L, e.i("SOUNDCONTROL", IgEnv.i(1L), IgEnv.i(3L), IgEnv.i(150L)))
        assertEquals("controlSound(1,SET_SPEED,1.5,true)", audio.log.last())
        // 第 4 参非 0 ⇒ **关闭**音高保留（:8937-8940，比较用 long 无截断）
        audio.log.clear()
        assertEquals(1L, e.i("SOUNDCONTROL", IgEnv.i(1L), IgEnv.i(3L), IgEnv.i(50L), IgEnv.i(1L)))
        assertEquals("controlSound(1,SET_SPEED,0.5,false)", audio.log.last())
        audio.log.clear()
        assertEquals(1L, e.i("SOUNDCONTROL", IgEnv.i(1L), IgEnv.i(3L), IgEnv.i(50L), IgEnv.i(0L)))
        assertEquals("controlSound(1,SET_SPEED,0.5,true)", audio.log.last())
        // 3/4 参形态下操作码 0/1/2 同样 -2（:8945）
        assertEquals(-2L, e.i("SOUNDCONTROL", IgEnv.i(0L), IgEnv.i(0L), IgEnv.i(150L)))
        // 个数 2..4
        assertFailsWith<CodeEE> { e.method("SOUNDCONTROL").checkArity(1) }
        assertFailsWith<CodeEE> { e.method("SOUNDCONTROL").checkArity(5) }
        // 语料命中：SOUNDCONTROL 6 处 / 1 个文件
    }

    @Test
    fun BGMCONTROL_无条件先物化Bgm_再按操作码分派() {
        // FMCreator :8995-8999：(int) 截断后**无条件**物化 Bgm，非法操作码也会建对象。
        // 这正是 j-dispatch 的切换理由之一：旧实现在返回 -2 的路径上一个动作都不调，
        // 于是 BGMCONTROL 9 之后 GETSOUNDORBGMINFO -1,4 回 0 而原版回 100（Sound.cs:13 volume = 1f）。
        val audio = IgAudioHost(bgm = null)
        val e = env(audio)
        assertEquals(-2L, e.i("BGMCONTROL", IgEnv.i(9L)))            // 1 参形态、操作码 9 ⇒ -2（:9000-9015 的 else）
        assertTrue(audio.log.contains("materializeBgm"), "非法操作码也要先物化：${audio.log}")
        // 物化之后 BGM 存在 ⇒ 信息位可读（这里用宿主的 bgm 字段模拟「已建对象」）
        audio.bgm = SoundInfo(0.0, 0.0, false, 100L, 1.0)
        assertEquals(100L, e.i("GETSOUNDORBGMINFO", IgEnv.i(-1L), IgEnv.i(4L)))
        // 1 参形态 0/1/2 → 1
        assertEquals(1L, e.i("BGMCONTROL", IgEnv.i(0L)))
        assertEquals(1L, e.i("BGMCONTROL", IgEnv.i(1L)))
        assertEquals(1L, e.i("BGMCONTROL", IgEnv.i(2L)))
        // 2/3 参形态：只有操作码 3 有效（:9016-9029）
        assertEquals(-2L, e.i("BGMCONTROL", IgEnv.i(0L), IgEnv.i(150L)))
        audio.log.clear()
        assertEquals(1L, e.i("BGMCONTROL", IgEnv.i(3L), IgEnv.i(150L)))
        assertEquals("controlBgm(SET_SPEED,1.5,true)", audio.log.last())
        audio.log.clear()
        assertEquals(1L, e.i("BGMCONTROL", IgEnv.i(3L), IgEnv.i(50L), IgEnv.i(1L)))
        assertEquals("controlBgm(SET_SPEED,0.5,false)", audio.log.last())
        // 个数 1..3
        assertFailsWith<CodeEE> { e.method("BGMCONTROL").checkArity(0) }
        assertFailsWith<CodeEE> { e.method("BGMCONTROL").checkArity(4) }
        // 语料命中：BGMCONTROL 0 处
    }
}

// ===========================================================================
// 第 4 段：CONFIG 族两条 + GETSPCHARA + GRAPHICS 两条
//   GETCONFIG / GETCONFIGS / GETSPCHARA / GCREATEFROMFILE / GGETTEXTSIZE
// ===========================================================================

/**
 * 配置读取族 + SP 角色查号 + 图形两条的对拍。
 *
 * C# 依据：
 * - `GETCONFIG` / `GETCONFIGS` = 同一个 `GetConfigMethod(typeisInt)`，注册 :9148 / :9149，
 *   类体 :6021-6080（`GetSingleTerm` :6041-6055、`GetIntValue` :6057-6065、`GetStrValue` :6066-6073）
 * - `GETSPCHARA` = `GetspcharaMethod`，注册 :9043，类体 :3155-3174
 * - `GCREATEFROMFILE` = `GraphicsCreateFromFileMethod`，注册 :9177，类体 :6850 起（绝对路径开关 :6883-6895）
 * - `GGETTEXTSIZE` = `GraphicsGetTextSizeMethod`，注册 :9286，类体 :6532-6590
 *
 * 移植侧：`core/fn/ConfigMethods.kt:394-413`、`core/fn/VarMethods.kt:99-102`、
 * `core/fn/GraphicsMethods.kt:105-117` 与 `:384-400`。
 */
class IsaGapConfigAndMiscTest {

    @Test
    fun GETCONFIG_出厂默认条目四项读数钉死() {
        // 条目表唯一真源在 core/config（M4-J 收口），这几条都是**未播种**的出厂值，
        // 因此与时区/窗口无关，可以硬钉。
        val e = IgEnv()
        assertEquals(5L, e.i("GETCONFIG", IgEnv.s("フレーム毎秒")))                  // ConfigData.setDefault
        assertEquals(12_632_256L, e.i("GETCONFIG", IgEnv.s("文字色")))               // #C0C0C0，:375-382 编码
        assertEquals(1L, e.i("GETCONFIG", IgEnv.s("マウスを使用する")))              // bool → 1（:405-416）
        assertEquals(OperandType.INT, e.method("GETCONFIG").retType)                 // :6030 ReturnType = long
        assertEquals(1, e.method("GETCONFIG").maxArgs)                                 // :6029 单参
        // 语料命中：GETCONFIG 65 处 / 28 个文件（MISS 纯属基准没写）
    }

    @Test
    fun GETCONFIGS_字符串形与List型的NET运行时类型名() {
        val e = IgEnv()
        assertEquals(OperandType.STR, e.method("GETCONFIGS").retType)                 // :6035 ReturnType = string
        assertEquals("TEXTRENDERER", e.s("GETCONFIGS", IgEnv.s("描画インターフェース")))
        // 官方缺陷：List 型条目返回 .NET 运行时类型名而不是内容（ConfigItem.cs:81）⇒ 处置②
        assertEquals(
            "System.Collections.Generic.List`1[System.Int64]",
            e.s("GETCONFIGS", IgEnv.s("EXPLVの初期値")),
        )
        // 语料命中：GETCONFIGS 3 处 / 2 个文件
    }

    @Test
    fun GETCONFIG族四条报错全部回到Lang原文() {
        val e = IgEnv()
        // 型違い：:6063（int 形读到 string 条目）与 :6072（对称方向），模板 Lang.cs:3914
        assertEquals(
            "GETCONFIG関数: 型が違います (GETCONFIGS関数を使用してください)",
            assertFailsWith<CodeEE> { e.i("GETCONFIG", IgEnv.s("フォント名")) }.message,
        )
        assertEquals(
            "GETCONFIGS関数: 型が違います (GETCONFIG関数を使用してください)",
            assertFailsWith<CodeEE> { e.s("GETCONFIGS", IgEnv.s("フォントサイズ")) }.message,
        )
        // 空串：:6046 + Lang.cs:3902，**序号是字面量 1**
        assertEquals(
            "GETCONFIG関数: 第1引数が空文字列です",
            assertFailsWith<CodeEE> { e.i("GETCONFIG", IgEnv.s("")) }.message,
        )
        // 非配置名：ConfigData.cs:353-356 + Lang.cs:1742，{0} 是**调用方原样键串**（大写化只发生在查表内部）
        assertEquals(
            "文字列\"WindowX\"は適切なコンフィグ名ではありません",
            assertFailsWith<CodeEE> { e.i("GETCONFIG", IgEnv.s("WindowX")) }.message,
        )
        // 官方缺陷复刻（处置②）：Name 分支拿「大写化后的键」去比「混合大小写的 Name」⇒ **永不命中**
        // （ConfigData.cs:262-283）。故 `WINDOWX` 这种 ASCII 大写名查不到，只能走日文 Text / EngText。
        assertFailsWith<CodeEE> { e.i("GETCONFIG", IgEnv.s("WINDOWX")) }
    }

    @Test
    fun GETSPCHARA_SP未启用时抛函数层文案_启用后查无角色回负一() {
        // FMCreator :3155-3174：`!Config.CompatiSPChara` 时在**求实参之前**抛
        // Lang.Error.SPCharacterFeatureDisabled（:3166-3169）；启用则 GetChara_UseSp(no, getSp: true)（:3171）。
        // 注册 :9043；移植侧 VarMethods.kt:99-102。
        val off = IgEnv()
        val e = assertFailsWith<CodeEE> { off.i("GETSPCHARA", IgEnv.i(0L)) }
        assertEquals(
            "不能在标准设置下使用SP角色相关功能(请启用兼容性设置「使用SP角色」)",
            e.message,
        )
        assertEquals(SP_CHARACTER_FEATURE_DISABLED, e.message)
        // 语句层（ADDSPCHARA）用的是**另一条** zhs 译文（Lang id 不同：SPCharaConfigIsOff vs
        // SPCharacterFeatureDisabled，Lang.cs:2266 vs :3826）⇒ 两条串必须不等，
        // 这条断言就是把「不许合并成一条文案」钉住。
        assertFalse(SP_CHARA_CONFIG_IS_OFF == SP_CHARACTER_FEATURE_DISABLED)
        // 启用后（兼容性开关走 configOverrides，键名三种口径见 CompatiOptions.kt:36-40）
        val on = IgEnv(IgHost(configOverrides = mapOf("ALLOW SP CHARACTERS" to "YES")))
        assertEquals(-1L, on.i("GETSPCHARA", IgEnv.i(0L)))    // 空角色表 ⇒ 查无（:1584-1634 的找不到口径）
        assertEquals(OperandType.INT, on.method("GETSPCHARA").retType)
        assertFailsWith<CodeEE> { on.method("GETSPCHARA").checkArity(0) }
        assertFailsWith<CodeEE> { on.method("GETSPCHARA").checkArity(2) }
        // 语料命中：GETSPCHARA 0 处
    }

    @Test
    fun GCREATEFROMFILE_参数形态与三条守卫读数() {
        // FMCreator :6850 起；注册 :9177。移植侧 GraphicsMethods.kt:105-117。
        val e = IgEnv()
        assertEquals(OperandType.INT, e.method("GCREATEFROMFILE").retType)
        assertEquals(2, e.method("GCREATEFROMFILE").minArgs)
        assertEquals(3, e.method("GCREATEFROMFILE").maxArgs)
        // 负编号：:867-870 的 readGraphics 守卫
        val neg = assertFailsWith<CodeEE> { e.call("GCREATEFROMFILE", listOf(Value.IntV(-1L), Value.StrV("a.png"))) }
        assertTrue(neg.message!!.contains("图形编号不能为负"), neg.message ?: "")
        // 宿主无文件能力：求值体 :112 的 fileOps 守卫（第 2 参合法、编号 0 未创建时才走到这里）
        val noOps = assertFailsWith<CodeEE> { e.call("GCREATEFROMFILE", listOf(Value.IntV(0L), Value.StrV("a.png"))) }
        assertTrue(noOps.message!!.contains("fileOps == null"), noOps.message ?: "")
        // 个数 2/3 之外的显式守卫（注册表允许 2..3，体内再判一次，:108-110）
        assertFailsWith<CodeEE> { e.method("GCREATEFROMFILE").checkArity(1) }
        assertFailsWith<CodeEE> { e.method("GCREATEFROMFILE").checkArity(4) }
        // 已创建的槽重复创建 ⇒ 返回 0（:106-107 的 isCreated 分支；原版 Utils.LoadImage
        // 任何异常都吞成 null → 0，Utils.cs:502-525，所以「文件不存在/坏图」也是 0 而不是报错）
        // 语料命中：GCREATEFROMFILE 9 处 / 6 个文件
    }

    @Test
    fun GGETTEXTSIZE_参数形态与非正字号的守卫原文() {
        // FMCreator :6532-6590；注册 :9286。移植侧 GraphicsMethods.kt:384-400。
        val e = IgEnv()
        assertEquals(OperandType.INT, e.method("GGETTEXTSIZE").retType)
        assertEquals(3, e.method("GGETTEXTSIZE").minArgs)
        assertEquals(4, e.method("GGETTEXTSIZE").maxArgs)
        // 字号 <= 0：原版 :6584 的 new Font(name, emSize) 抛 ArgumentException 且**这段没有 try/catch**
        // （对比 GSETFONT 的 catch → 0，:6404-6406）⇒ 原版此处是崩溃；移植侧没有崩溃通道，转 CodeEE。
        // 登记为处置③「崩溃类不可对拍」，与 GETLINESTR 的零宽死循环同类。
        val bad = assertFailsWith<CodeEE> {
            e.call("GGETTEXTSIZE", listOf(Value.StrV("あ"), Value.StrV("MS Gothic"), Value.IntV(0L)))
        }
        assertTrue(bad.message!!.contains("字号必须为正数"), bad.message ?: "")
        assertTrue(bad.message!!.contains("ArgumentException"), bad.message ?: "")
        // 正常度量依赖字体侧车表（FontSidecarMetricsTest / FnWidthSingleSourceTest 已钉），
        // 本路不重复钉数值，只钉 RESULT[1] 有写入这条**结构**事实见 :400（height 写 RESULT 下标 1）。
        // 语料命中：GGETTEXTSIZE 0 处
    }
}

// ===========================================================================
// 第 5 段：三条 MISS 的 ISA 语句（ADDSPCHARA / CALLSHARP / FORCE_BEGIN）
// ===========================================================================

/**
 * 语句层三条对拍。
 *
 * 任务书给的实现位置「OpcodeTable.kt:667/:830/:871」在本仓**不存在**
 * （`core/proc` 下没有 OpcodeTable.kt），实际实现面在 `core/stmt`：
 * - `ADDSPCHARA` = `core/stmt/FunctionCode.kt:114`（与 ADDCHARA/DELCHARA 同一个
 *   `ADDCHARA_Instruction`，spec: FunctionIdentifier.cs:1344-1397，注册 :3944）
 * - `CALLSHARP` = `core/stmt/FunctionCode.kt:134` + `LineParser.kt:431` +
 *   `ArgBuilders.kt:647-655` + `Argument.kt:222-229`（spec: FunctionIdentifier.cs:1169-1235，注册 :4055）
 * - `FORCE_BEGIN` = `core/stmt/FunctionCode.kt:182`（spec: FunctionIdentifier.cs:2939-2954，注册 :4111）
 *
 * 三条都能在装载期解析（`specs/isa.yaml` 有完整条目），MISS 是**基准没写这三行**，
 * 语料实测也都是 0 处 ⇒ 不属于产品缺失，本段只钉注册表面 + 文案。
 */
class IsaGapStatementTest {

    @Test
    fun ADDSPCHARA_注册表面与SP门控文案() {
        // C# :1344-1355：ArgBuilder = INT_ANY、**flag = 4**（:1353）、注册 :3944 用 flagSp: true
        val code = FunctionCode.ADDSPCHARA
        assertEquals(ArgType.INT_ANY, code.argType)
        assertEquals(StmtKind.VARIABLE, code.kind)
        // 2026-10-22 合流 origin/main@6332185 后改钉：远端 `5b4467d` 把 `FunctionCode.flag`
        // 按 specs/isa.yaml（flag / flag_by_alias）+ FunctionIdentifier.cs 逐名收口
        // （231 处偏差 → 0）⇒ 本条的「账面分歧」已消解，读数回到规约值 4。
        assertEquals(4, code.flag)
        // 门控与文案：SP 关闭时 :1360-1363 抛 SPCharaConfigIsOff（在任何实参求值**之前**）
        assertEquals("SP角色相关功能不可用(请启用兼容性设置「使用SP角色」)", SP_CHARA_CONFIG_IS_OFF)
        // DELCHARA 的按个数分流（:1384-1393）归 m-delchara，本路不重复钉。
    }

    @Test
    fun CALLSHARP_注册表面与参数形态() {
        // C# :1169-1175：ArgBuilder = SP_CALLCSHARP、**flag = 38**（:1174）、注册 :4055
        val code = FunctionCode.CALLSHARP
        assertEquals(ArgType.SP_CALLCSHARP, code.argType)
        assertEquals(StmtKind.CALL, code.kind)
        assertEquals(38, code.flag)                       // 已接线（5b4467d）⇒ 回到 specs/isa.yaml:1533 的 38
        // 移植侧解析件齐备：LineParser.kt:431 建 CallSharpStmt、Argument.kt:222-229 携带
        // funcNameTerm / subNames / rowArgs 三段（对应 SpCallSharpArgment.cs:7-25）
        val arg = SpCallSharpArgument(funcNameTerm = null, subNames = emptyList(), rowArgs = emptyList())
        assertEquals(ArgType.SP_CALLCSHARP, arg.shape)
    }

    @Test
    fun FORCE_BEGIN_注册表面与BeginType口径() {
        // C# :2939-2945：ArgBuilder = STR、**flag = 1**（:2944）；DoInstruction :2946-2952 =
        //   state.SetBegin(ConstStr, force: true) + state.Return(0) + exm.Console.ResetStyle()
        // 注册 :4111；isa.yaml 的 FORCE_BEGIN 条目记 flag: 1（:1884）、source :2939-2954
        val code = FunctionCode.FORCE_BEGIN
        assertEquals(ArgType.STR, code.argType)
        assertEquals(StmtKind.FLOW, code.kind)
        assertEquals(1, code.flag)                        // 已接线（5b4467d）⇒ 回到 specs/isa.yaml:1884 的 1
        // 三条语句名都进了装载期符号表（BuiltinSymbols 的语句名单），不是「查无此语句」
        assertEquals("FORCE_BEGIN", FunctionCode.valueOf("FORCE_BEGIN").name)
        // ResetStyle 这一句的接线缺口归 m-resetstyle（本路不重复登记）。
    }
}

// ===========================================================================
// 第 2 段：TIME 族四条 + GETLINESTR
//   GETTIMES / GETMILLISECOND / GETSECOND / GETMEMORYUSAGE / GETLINESTR
// ===========================================================================

/**
 * 时间族与分隔线函数对拍。
 *
 * C# 依据（FMCreator）：
 * - `GETTIMES` 注册 :9086，类体 `GettimesMethod` :3982-3995（返回 string，`Ticks` → `ToString(格式串)`）
 * - `GETMILLISECOND` 注册 :9087，类体 `GetmsMethod` :3997-4010（`DateTime.Now.Ticks/10000`，取数在 :4008）
 * - `GETSECOND` 注册 :9088，类体 `GetSecondMethod` :4012-4025（`Ticks/10000000`，取数在 :4023）
 * - `GETMEMORYUSAGE` 注册 :9290，类体 `GetUsingMemoryMethod` :8307-8321（`Process.GetCurrentProcess().WorkingSet64`）
 * - `GETLINESTR` 注册 :9145，类体 `GetLineStrMethod` :5894-5912（**收单个字符串实参**，
 *   返回 `exm.Console.getStBar(str)`，`EmueraConsole.cs:3420-3437` 按 `Config.DrawableWidth` 像素填充）
 *
 * 移植侧：前三条 + GETMEMORYUSAGE 由 `core/fn/TimeMethods.kt`（M4-J j-dispatch）持有，
 * GETLINESTR 由 `core/fn/DebugMiscMethods.kt:129-135` 持有；两者都已在 `Builtins.buildRegistry`
 * 末尾接管（Builtins.kt:684 附近）。
 */
class IsaGapTimeFamilyTest {

    /** 该钉钟时刻在**当前默认时区**的 UTC 偏移毫秒数（java.time 直取，不经 TimeMethods）。 */
    private fun offsetMillisOf(epochMillis: Long): Long =
        ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(epochMillis)).totalSeconds * 1000L

    @Test
    fun GETTIMES_返回本地墙上时间的定长串() {
        // FMCreator :3993 = DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss")（本地时区、单串）。
        // 纯函数用固定时区钉死读数：Asia/Shanghai 恒 +8h、无夏令时。
        val shanghai = ZoneId.of("Asia/Shanghai")
        assertEquals("2026/05/29 04:26:40", TimeMethods.times(PINNED_EPOCH_MILLIS, shanghai))
        // 产线注册表走 systemDefault，故按「同一时刻的本地读数」交叉验证格式化口径
        // （期望串由 java.time + C# 的格式串独立排出，不复用 TimeMethods 的 String.format 路径）
        val local = Instant.ofEpochMilli(PINNED_EPOCH_MILLIS).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val expected = local.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT))
        val env = IgEnv(IgHost())
        assertEquals(expected, env.s("GETTIMES"))
        assertEquals(19, expected.length)                    // 定长 19：4+1+2+1+2 +1+ 2+1+2+1+2
        assertEquals(OperandType.STR, env.method("GETTIMES").retType)
        assertEquals(0, env.method("GETTIMES").minArgs)      // 零参
        // 语料命中：GETTIMES 2 处 / 2 个文件
    }

    @Test
    fun GETMILLISECOND_是本地墙上毫秒_含时区偏移() {
        // FMCreator :4008 `DateTime.Now.Ticks/10000` ⇒ 基准是**本地** 0001-01-01，不是 UTC。
        // M4-J 修正的正是旧接线漏掉的 UTC 偏移（Asia/Shanghai 下差 28 800 000 ms）。
        val shanghai = ZoneId.of("Asia/Shanghai")
        val expectedShanghai = PINNED_EPOCH_MILLIS + NET_EPOCH + 28_800_000L
        assertEquals(expectedShanghai, TimeMethods.millisecondsSinceNetEpoch(PINNED_EPOCH_MILLIS, shanghai))
        // 旧口径（无偏移）必须与新读数不同：这条就是把「回退成 UTC 基准」钉红的守卫
        val legacy = PINNED_EPOCH_MILLIS + NET_EPOCH
        assertFalse(expectedShanghai == legacy)
        // 产线读数：偏移量由当前默认时区决定（闸机机为 Asia/Shanghai ⇒ +28 800 000）
        val env = IgEnv(IgHost())
        assertEquals(PINNED_EPOCH_MILLIS + NET_EPOCH + offsetMillisOf(PINNED_EPOCH_MILLIS), env.i("GETMILLISECOND"))
        assertEquals(OperandType.INT, env.method("GETMILLISECOND").retType)
        // 语料命中：GETMILLISECOND 191 处 / 18 个文件（MISS 纯粹是基准没写，不是产品没做）
    }

    @Test
    fun GETSECOND_整毫秒除一千_与GETMILLISECOND同基准() {
        // FMCreator :4023 `Ticks/10000000`（整数除法截断）。
        val shanghai = ZoneId.of("Asia/Shanghai")
        val ms = TimeMethods.millisecondsSinceNetEpoch(PINNED_EPOCH_MILLIS, shanghai)
        assertEquals(ms / 1000L, TimeMethods.secondsSinceNetEpoch(PINNED_EPOCH_MILLIS, shanghai))
        // 63 915 625 600 = (1 780 000 000 000 + 62 135 596 800 000 + 28 800 000) / 1000
        // 组长实测改钉：worker 交回的字面量 63 915 596 828 漏加了本地时区的 +28 800 000 ms
        // （闸机本机 = Asia/Shanghai ⇒ UTC+8；前一条 `ms/1000 == seconds` 断言已把同基准钉死）
        assertEquals(63_915_625_600L, TimeMethods.secondsSinceNetEpoch(PINNED_EPOCH_MILLIS, shanghai))
        val env = IgEnv(IgHost())
        // 同一次调用里两条必须同基准（截断而非四舍五入；值为正 ⇒ floor）
        assertEquals(env.i("GETMILLISECOND") / 1000L, env.i("GETSECOND"))
        assertEquals(OperandType.INT, env.method("GETSECOND").retType)
        // 语料命中：GETSECOND 0 处
    }

    @Test
    fun GETMEMORYUSAGE_原样返回宿主字节数_未接线时抛原文() {
        // FMCreator :8307-8321：WorkingSet64（**字节**，不是 KB）。
        // 移植侧选型（JVM 无对等计数器）见 TimeMethods.kt 文件头第四节：
        // Linux/Android 取 /proc/self/status 的 VmRSS，读不到时回落 used heap，
        // 并把计数器名放进 MemoryUsageReading.counter ⇒ 口径可判、不装作等价。
        val env = IgEnv(IgHost(memory = 123_456_789L))
        assertEquals(123_456_789L, env.i("GETMEMORYUSAGE"))
        assertEquals(OperandType.INT, env.method("GETMEMORYUSAGE").retType)
        // 零参：多传先报个数（注册 maxArgs = 0）
        assertFailsWith<CodeEE> { env.method("GETMEMORYUSAGE").checkArity(1) }
        // 未接线 = 抛，不静默 0（TimeMethods.kt:145 的文案与旧 DebugMisc 注册逐字相同）
        val off = IgEnv()
        val e = assertFailsWith<CodeEE> { off.i("GETMEMORYUSAGE") }
        assertEquals(
            "函数 GETMEMORYUSAGE：内存用量宿主尚未接线（需要宿主注入 BuiltinHost.memoryUsageBytes）",
            e.message,
        )
        // 0 字节是合法读数，不能被当成「未接线」
        assertEquals(0L, IgEnv(IgHost(memory = 0L)).i("GETMEMORYUSAGE"))
        // 语料命中：GETMEMORYUSAGE 0 处
    }

    @Test
    fun GETLINESTR_是分隔线填充串生成器_不是按行号取历史行() {
        // 契约书点名要纠正的口径：GETLINESTR **不是**「取控制台第 n 行」。
        // FMCreator :5894-5912：argumentTypeArray = { string }（:5899）、ReturnType = string（:5898）、
        // CanRestructure = true（:5900）；实参空串 → CodeEE（:5906-5908，ArgIsEmptyString）；
        // 否则 return exm.Console.getStBar(str)（:5910）。
        // getStBar（EmueraConsole.cs:3420-3437）：先 Append 一次，再 while(num < DrawableWidth) 追加、
        // while(num > DrawableWidth) 逐字回退 ⇒ 返回值 = **像素宽度不超过 DrawableWidth 的最长重复串**。
        // 默认口径（DrawableWidth = WindowX - max(2, FontSize/6) = 757，半角步进 FontSize/2 = 9px）
        // ⇒ 半角横线 84 个（84*9 = 756 ≤ 757 < 85*9 = 765）。
        val env = IgEnv()
        val bar = env.s("GETLINESTR", IgEnv.s("-"))
        assertEquals(84, bar.length)
        assertEquals("-".repeat(84), bar)
        // 宿主测量优先：10px/字 ⇒ 757/10 = 75 个（回退循环从 76 削到 75）
        val measured = IgEnv(IgHost(measureText = { 10L * it.length }))
        assertEquals(75, measured.s("GETLINESTR", IgEnv.s("-")).length)
        // 全角横线走同一张宽度表（core/host/CellWidth.kt，CP932 字节数口径）⇒ 字数减半
        assertEquals("─".repeat(42), env.s("GETLINESTR", IgEnv.s("─")))
        // 语料命中：GETLINESTR 1 处 / 1 个文件
        // 唯一一处 = `ERB/カラム機能/カラム機能(div).ERB:21` 的
        // `#DIM DYNAMIC 行文字数 = STRLENSU(GETLINESTR("─"))`，属**装载期折叠**缺口（M4-M 的 m-dimfold），
        // 不是本路的运行期缺口。
    }

    @Test
    fun GETLINESTR_参数口径三条_空串与非字符串实参各抛一条() {
        // FMCreator :5905-5908：只判 `string.IsNullOrEmpty` ⇒ 抛 ArgIsEmptyString。
        val env = IgEnv()
        val e = assertFailsWith<CodeEE> { env.s("GETLINESTR", IgEnv.s("")) }
        assertTrue(e.message!!.contains("GETLINESTR"), "文案应含函数名：${e.message}")
        // 类型实参错位：DebugMiscMethods.kt:131-133 先做 as? Value.StrV 判定
        assertFailsWith<CodeEE> { env.call("GETLINESTR", listOf(Value.IntV(1L))) }
        // 个数：1..1（:5899 单参），0 个与 2 个都报（:5905 会越界访问 arguments[0]）
        assertFailsWith<CodeEE> { env.method("GETLINESTR").checkArity(0) }
        assertFailsWith<CodeEE> { env.method("GETLINESTR").checkArity(2) }
    }

    @Test
    fun GETLINESTR_零宽字符串原版死循环_移植侧抛CodeEE是有意的不可对拍偏离() {
        // 官方 bug 点：EmueraConsole.cs:3426-3430 的 while 只在 `num` 增长时收敛，
        // barStr 显示宽度为 0 时 num 恒 0 ⇒ **无限追加**（原版表现为内存耗尽/假死，没有可读回值）。
        // 移植侧 DebugMiscMethods.kt:308-310 检测到「宽度不随追加增长」即抛 CodeEE。
        // 崩溃类语义无法对拍 ⇒ 登记为处置③「不修」：移植侧比原版更早停下，但原版那一侧没有基准可比。
        val env = IgEnv(IgHost(measureText = { 0L }))
        val e = assertFailsWith<CodeEE> { env.s("GETLINESTR", IgEnv.s("-")) }
        assertTrue(
            e.message!!.contains("的显示宽度不随追加增长"),
            "应为零宽守卫文案，实际：${e.message}",
        )
    }
}
