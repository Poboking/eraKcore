package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M4-J j-dispatch：三族「第二实现接管分派」的收口回归。
 *
 * 钉三件事，全部断言**具体读数**：
 * 1. 注册表里每个名字的真相只有一个（既不是占位、也不是两份实现撞名 ——
 *    `BuiltinRegistry.register` 撞真实现即 error，Builtins.kt:594-601）；
 * 2. 时间族的本地时区偏移修正前后的**实测差值**（钉钟后精确到毫秒）；
 * 3. 音频族的 `(int)` 截断与「先物化再分派」、配置族的 Lang.cs 原文文案与宿主播种。
 *
 * 权威出处逐条写在被断言的实现文件里（TimeMethods.kt / AudioMethods.kt / ConfigMethods.kt），
 * 本文件的每条断言旁再挂一次 `FunctionMethodCreator.cs` 行号。
 */

/** 钉死的 epoch 毫秒：2026-05-28T20:26:40Z（Asia/Shanghai 本地 = 2026-05-29 04:26:40）。 */
private const val PINNED_EPOCH_MILLIS = 1_780_000_000_000L

/** 0001-01-01 → 1970-01-01 的毫秒数（TimeMethods.NET_EPOCH_OFFSET_MILLIS）。 */
private const val NET_EPOCH = 62_135_596_800_000L

/** Asia/Shanghai 在该时刻的 UTC 偏移（该时区无夏令时，恒 +8 小时）。 */
private const val SHANGHAI_OFFSET_MILLIS = 28_800_000L

/** 切换前旧接线（曾挂 HostMethods.kt）的算式：UTC epoch 毫秒 + 常数，**没有**本地偏移。 */
private fun legacyMillisSinceNetEpoch(epochMillis: Long): Long = epochMillis + NET_EPOCH

private class DsBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("DsBaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) = Unit
}

/** 钉钟 + 可选内存读数 + 可选音频宿主的测试宿主。 */
private class DsHost(
    private val now: Long = PINNED_EPOCH_MILLIS,
    private val memory: Long? = null,
    override val audio: AudioHost? = null,
    override val clientWidth: Long = 1800L,
    override val fontSize: Long = 18L,
    override val configOverrides: Map<String, String> = emptyMap(),
) : BuiltinHost {
    override fun clockMillis(): Long = now
    override val memoryUsageBytes: Long? get() = memory
    override val soundChannels: Int get() = if (audio == null) 0 else 10
}

/**
 * 把「注册表 + 宿主 + 变量池」粘起来的调用装置。
 *
 * 不借道 [BuiltinEvalContext.callFunction]（它写死读全局 [Builtins.registry]），
 * 而是按同一条桥的口径手工分派：`checkArity` → `eval`（Builtins.kt:692-697），
 * 这样时间/音频两族「显式时区的新注册表」与「全局产线注册表」都能用同一个装置断言。
 */
private class DsEnv(
    host: BuiltinHost,
    private val registry: BuiltinRegistry = Builtins.registry,
) {
    val store = VariableStore()
    val ctx: BuiltinEvalContext

    init {
        val base = DsBaseCtx().apply { variables = store }
        ctx = BuiltinEvalContext(base, store, host)
    }

    fun call(name: String, args: List<Value?> = emptyList()): Value {
        val m = registry[name] ?: error("函数 $name 未注册")
        m.checkArity(args.size)
        return m.eval!!.invoke(ctx, args)
    }

    fun int(name: String, vararg args: Value?): Long = (call(name, args.toList()) as Value.IntV).v
    fun str(name: String, vararg args: Value?): String = (call(name, args.toList()) as Value.StrV).v
}

/** 记录调用序列的假音频宿主：只用来观测「物化有没有发生」与「读的是 BGM 还是声道」。 */
private class DsAudioHost : AudioHost, AudioChannelMaterializer {
    val log = mutableListOf<String>()
    var bgmExists = false

    private fun info() = SoundInfo(
        totalSeconds = 0.0,
        currentSeconds = 0.0,
        playing = false,
        volume = 100L,
        speed = 1.0,
    )

    override fun soundInfo(channel: Int): SoundInfo {
        log += "soundInfo($channel)"
        return info()
    }

    override fun bgmInfo(): SoundInfo? {
        log += "bgmInfo"
        return if (bgmExists) info() else null
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
        bgmExists = true
    }
}

private fun registryWithTime(zone: ZoneId): BuiltinRegistry =
    BuiltinRegistry().also { registerTimeMethods(it, zone) }

private fun registryWithAudio(): BuiltinRegistry =
    BuiltinRegistry().also { registerAudioMethods(it) }

class DispatchSwitchTest {

    // ==================================================================
    // ① 判据：名单与实际注册一致，一处真相
    // ==================================================================

    @Test
    fun `切换后的九个名字都是真实现且不再是占位`() {
        for (name in listOf(
            "GETTIMES", "GETMILLISECOND", "GETSECOND", "GETMEMORYUSAGE",
            "GETSOUNDORBGMINFO", "SOUNDCONTROL", "BGMCONTROL", "GETCONFIG", "GETCONFIGS",
        )) {
            val m = assertNotNull(Builtins.registry[name], "函数 $name 未注册")
            assertFalse(Builtins.registry.isPlaceholder(name), "函数 $name 仍是占位")
            assertNotNull(m.eval, "函数 $name 没有值求值入口")
        }
        // GETTIME 不在切换名单里（HostMethods 仍持有：逐字段取本地日历，与 DateTime.Now 同值）
        assertNotNull(Builtins.registry["GETTIME"])
        // 旧家族文件里不该再留这九个名字的任何注册
        val debugMisc = com.eratw.port.core.fn.DebugMiscMethodsOwnerNames
        for (name in listOf("GETCONFIG", "GETMEMORYUSAGE", "GETSOUNDORBGMINFO", "SOUNDCONTROL", "BGMCONTROL")) {
            assertFalse(name in debugMisc, "函数 $name 在 DebugMiscMethods 里重复注册")
        }
    }

    // ==================================================================
    // ② 时间族：本地时区偏移修正前后的实测差值
    // ==================================================================

    @Test
    fun `GETMILLISECOND 在 Asia-Shanghai 下比切换前的接线大恰好八小时`() {
        val env = DsEnv(DsHost(), registryWithTime(ZoneId.of("Asia/Shanghai")))
        val actual = env.int("GETMILLISECOND")
        val legacy = legacyMillisSinceNetEpoch(PINNED_EPOCH_MILLIS)
        // spec: FunctionMethodCreator.cs:4008 —— DateTime.Now.Ticks/10000，DateTime.Now 是本地墙上时间
        assertEquals(PINNED_EPOCH_MILLIS + SHANGHAI_OFFSET_MILLIS + NET_EPOCH, actual)
        assertEquals(63_915_625_600_000L, actual, "钉死读数：旧接线给 63915596800000")
        assertEquals(SHANGHAI_OFFSET_MILLIS, actual - legacy, "修正前后的差值 = 8 小时的毫秒数")
    }

    @Test
    fun `GETSECOND 与 GETMILLISECOND 同一条时基且整数除法截断`() {
        val env = DsEnv(DsHost(), registryWithTime(ZoneId.of("Asia/Shanghai")))
        val ms = env.int("GETMILLISECOND")
        // spec: :4023 —— Ticks/10000000 = 毫秒读数再除 1000（值为正 ⇒ 截断即 floor）
        assertEquals(ms / 1000L, env.int("GETSECOND"))
        assertEquals(63_915_625_600L, env.int("GETSECOND"))
    }

    @Test
    fun `系统时区下的产线读数等于本地偏移加常数`() {
        val zone = ZoneId.systemDefault()
        val offsetMillis = zone.rules.getOffset(Instant.ofEpochMilli(PINNED_EPOCH_MILLIS)).totalSeconds * 1000L
        // 全局注册表用 ZoneId.systemDefault()（Builtins.buildRegistry 里没显式传时区）
        assertEquals(PINNED_EPOCH_MILLIS + offsetMillis + NET_EPOCH, DsEnv(DsHost()).int("GETMILLISECOND"))
        // 与显式时区注册的那份在同一时区下必须逐位相同（否则就是两份口径）
        assertEquals(DsEnv(DsHost(), registryWithTime(zone)).int("GETMILLISECOND"), DsEnv(DsHost()).int("GETMILLISECOND"))
    }

    @Test
    fun `GETTIMES 零参返回单个本地墙上时间串`() {
        val env = DsEnv(DsHost(), registryWithTime(ZoneId.of("Asia/Shanghai")))
        // spec: :3987 Array.Empty<Type>()（零参数）、:3991-3994 返回**单个** yyyy/MM/dd HH:mm:ss
        assertEquals("2026/05/29 04:26:40", env.str("GETTIMES"))
        // UTC 时区下同一条函数给 UTC 文本，证明差异只来自时区而不是格式化
        assertEquals("2026/05/28 20:26:40", DsEnv(DsHost(), registryWithTime(ZoneId.of("UTC"))).str("GETTIMES"))
        // 传参数 ⇒ 原版 ArgsCountNotMatches（FunctionMethod.cs:302-335）
        val e = assertFailsWith<CodeEE> { env.call("GETTIMES", listOf(Value.IntV(1L))) }
        assertTrue((e.message ?: "").contains("需要 0 个"), "实际：${e.message}")
    }

    @Test
    fun `GETMEMORYUSAGE 报宿主注入的字节数且无 KB 换算`() {
        // spec: :8307-8321（WorkingSet64 直接返回 long，没有任何单位换算）
        assertEquals(111_968_256L, DsEnv(DsHost(memory = 111_968_256L)).int("GETMEMORYUSAGE"))
        val e = assertFailsWith<CodeEE> { DsEnv(DsHost()).int("GETMEMORYUSAGE") }
        assertTrue((e.message ?: "").contains("memoryUsageBytes"), "文案要点名缺的宿主属性，实际：${e.message}")
    }

    @Test
    fun `DT 行主键的 tick 折算同样带本地偏移`() {
        // spec: Utils.cs:464-471（Utils.TimePoint = DateTime.Now.Ticks）；
        // MapDataTableMethods.nextRowId 现走 TimeMethods.netTicks
        val shanghai = ZoneId.of("Asia/Shanghai")
        assertEquals(
            (PINNED_EPOCH_MILLIS + SHANGHAI_OFFSET_MILLIS + NET_EPOCH) * 10_000L,
            TimeMethods.netTicks(PINNED_EPOCH_MILLIS, shanghai),
        )
        // 修正前后差 2.88e11 ticks；18 位十进制的位数不变（基准 DATA_LEN=179 依赖它）
        assertEquals(288_000_000_000L, TimeMethods.netTicks(PINNED_EPOCH_MILLIS, shanghai) - legacyMillisSinceNetEpoch(PINNED_EPOCH_MILLIS) * 10_000L)
        assertEquals(18, TimeMethods.netTicks(PINNED_EPOCH_MILLIS, shanghai).toString().length)
    }

    // ==================================================================
    // ③ 音频族：截断口径 + 先物化再分派
    // ==================================================================

    @Test
    fun `声道实参先做 int 截断于是低三十二位的负一仍读 BGM`() {
        val audio = DsAudioHost()
        audio.bgmExists = true
        val env = DsEnv(DsHost(audio = audio), registryWithAudio())
        // spec: :8791 —— int num = (int)arguments[0].GetIntValue(...)；0xFFFFFFFF 截断为 -1
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", Value.IntV(4_294_967_295L), Value.IntV(3L)))
        assertEquals(100L, env.int("GETSOUNDORBGMINFO", Value.IntV(4_294_967_295L), Value.IntV(4L)))
        assertTrue(audio.log.contains("bgmInfo"), "截断后必须走 BGM 分支，实际：${audio.log}")
        // spec: :8797-8804 —— 0x100000000 截断为 0 ⇒ 读声道 0
        assertEquals(100L, env.int("GETSOUNDORBGMINFO", Value.IntV(4_294_967_296L), Value.IntV(4L)))
        assertTrue(audio.log.contains("soundInfo(0)"), "实际：${audio.log}")
    }

    @Test
    fun `BGMCONTROL 非法操作码也会先物化 Bgm`() {
        val audio = DsAudioHost()
        val env = DsEnv(DsHost(audio = audio), registryWithAudio())
        assertFalse(audio.bgmExists)
        // spec: :8996-8999（物化早于分派）、:9029（2/3 参形态下操作码非 3 ⇒ -2）
        assertEquals(-2L, env.int("BGMCONTROL", Value.IntV(9L)))
        assertTrue(audio.bgmExists, "非法操作码也要把 Bgm 建出来")
        // 物化后的可观测后果：音量读数从 0（没有对象）变成 100（Sound.cs:13 volume = 1f）
        assertEquals(0L, env.int("GETSOUNDORBGMINFO", Value.IntV(-2L), Value.IntV(4L)), "越界取值仍静默回 0（:8805-8808）")
        assertEquals(100L, env.int("GETSOUNDORBGMINFO", Value.IntV(-1L), Value.IntV(4L)))
    }

    @Test
    fun `SOUNDCONTROL 越界早于建道 合法下标才物化`() {
        val audio = DsAudioHost()
        val env = DsEnv(DsHost(audio = audio), registryWithAudio())
        // spec: :8908-8911 —— 越界判定在建道之前：静默 -1，什么宿主动作都不调
        assertEquals(-1L, env.int("SOUNDCONTROL", Value.IntV(-1L), Value.IntV(0L)))
        assertEquals(-1L, env.int("SOUNDCONTROL", Value.IntV(10L), Value.IntV(0L)))
        assertFalse(audio.log.any { it.startsWith("materializeSound") }, "实际：${audio.log}")
        // spec: :8912-8915 —— 合法下标先物化，再判操作码；:8945 操作码非 0/1/2/3 ⇒ -2
        assertEquals(-2L, env.int("SOUNDCONTROL", Value.IntV(0L), Value.IntV(4L)))
        assertTrue(audio.log.contains("materializeSound(0)"), "实际：${audio.log}")
        // spec: :8935 —— (float)arguments[2] / 100f；:8937-8940 第 4 参非 0 ⇒ 关闭音高保留
        assertEquals(1L, env.int("SOUNDCONTROL", Value.IntV(0L), Value.IntV(3L), Value.IntV(150L), Value.IntV(1L)))
        assertEquals("controlSound(0,SET_SPEED,1.5,false)", audio.log.last())
        // spec: :8916-8932 —— 0/1/2 只在恰好 2 参时被接受
        assertEquals(1L, env.int("SOUNDCONTROL", Value.IntV(1L), Value.IntV(2L)))
        assertEquals(-2L, env.int("SOUNDCONTROL", Value.IntV(1L), Value.IntV(2L), Value.IntV(0L)))
    }

    // ==================================================================
    // ④ 配置族：Lang.cs 原文文案 + 宿主播种 + 条目表唯一真相
    // ==================================================================

    @Test
    fun `GETCONFIG 的宿主运行态播种在切换后仍然生效`() {
        // clientWidth 1800 ⇒「ウィンドウ幅」1800（原版这是主窗口回填的实际值，不是常量）
        val env = DsEnv(DsHost(clientWidth = 1800L, fontSize = 18L))
        assertEquals(1800L, env.int("GETCONFIG", Value.StrV("ウィンドウ幅")))
        assertEquals(1800L, env.int("GETCONFIG", Value.StrV("Window width")))
        // 未播种的条目保持 ConfigData.setDefault 出厂值（spec: ConfigData.cs:52-220）
        assertEquals(5L, env.int("GETCONFIG", Value.StrV("フレーム毎秒")))
        assertEquals("TEXTRENDERER", env.str("GETCONFIGS", Value.StrV("描画インターフェース")))
        // 颜色编码 (R*256+G)*256+B（spec: ConfigData.cs:375-382）
        assertEquals(12632256L, env.int("GETCONFIG", Value.StrV("文字色")))
    }

    @Test
    fun `GETCONFIG 类型错误与未知键报 Lang 原文`() {
        val env = DsEnv(DsHost())
        // spec: FunctionMethodCreator.cs:6063 + Lang.cs:3914
        val e1 = assertFailsWith<CodeEE> { env.int("GETCONFIG", Value.StrV("フォント名")) }
        assertEquals("GETCONFIG関数: 型が違います (GETCONFIGS関数を使用してください)", e1.message)
        // spec: :6072（对称方向）
        val e2 = assertFailsWith<CodeEE> { env.str("GETCONFIGS", Value.StrV("フォントサイズ")) }
        assertEquals("GETCONFIGS関数: 型が違います (GETCONFIG関数を使用してください)", e2.message)
        // spec: ConfigData.cs:353-356 + Lang.cs:1742；{0} 是调用方原样的键串（大写化只发生在查表内部）
        val e3 = assertFailsWith<CodeEE> { env.int("GETCONFIG", Value.StrV("WindowX")) }
        assertEquals("文字列\"WindowX\"は適切なコンフィグ名ではありません", e3.message)
        // spec: FunctionMethodCreator.cs:6046 + Lang.cs:3902（参数序号是字面量 1）
        val e4 = assertFailsWith<CodeEE> { env.int("GETCONFIG", Value.StrV("")) }
        assertEquals("GETCONFIG関数: 第1引数が空文字列です", e4.message)
    }

    @Test
    fun `条目表唯一真相：两个入口对同一批读数同值`() {
        // 同一份 core/config 表：GETCONFIG 与 GETLINESTR 的 WindowX 读的是同一个条目
        val env = DsEnv(DsHost(clientWidth = 900L))
        assertEquals(900L, env.int("GETCONFIG", Value.StrV("ウィンドウ幅")))
        // Name 分支永不命中（原版缺陷：大写化的键比混合大小写的 Name，ConfigData.cs:262-283）
        assertFailsWith<CodeEE> { env.int("GETCONFIG", Value.StrV("WINDOWX")) }
        // List 型条目读 .NET 运行时类型名（spec: ConfigItem.cs:81，基准可观测）
        assertEquals(
            "System.Collections.Generic.List`1[System.Int64]",
            env.str("GETCONFIGS", Value.StrV("EXPLVの初期値")),
        )
        // bool → YES/NO → 1/0（spec: ConfigData.cs:405-416）
        assertEquals(1L, env.int("GETCONFIG", Value.StrV("マウスを使用する")))
        // configOverrides 走同一条解析路径，且能盖住宿主播种（spec: ConfigData.cs:563-586）
        val overridden = DsEnv(DsHost(clientWidth = 900L, configOverrides = mapOf("ウィンドウ幅" to "1024")))
        assertEquals(1024L, overridden.int("GETCONFIG", Value.StrV("ウィンドウ幅")))
        val bad = DsEnv(DsHost(configOverrides = mapOf("PBANDの初期値" to "not-a-number")))
        assertEquals(4L, bad.int("GETCONFIG", Value.StrV("PBANDの初期値")), "解析失败 ⇒ ConfigWarn 口径保留出厂默认")
    }
}

/**
 * DebugMiscMethods 现在不再注册的名字（防止分派回退成两份）。
 * 判据 = 那个文件里的注册函数跑一遍后，这些名字**不是**它注册出来的。
 */
internal val DebugMiscMethodsOwnerNames: Set<String> by lazy {
    val r = BuiltinRegistry()
    registerDebugMiscMethods(r)
    r.names()
}
