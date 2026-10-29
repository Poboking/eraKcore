package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value

/**
 * 音频函数族 —— BGMCONTROL / SOUNDCONTROL / GETSOUNDORBGMINFO（[registerAudioMethods]）。
 *
 * 本文件是「函数层 → 既有引擎」的接驳层：三条函数只依赖 [AudioHost] 这个宿主面
 * （声明在 DebugMiscMethods.kt:226-234），落到引擎的实现是
 * [com.eratw.port.core.audio.ChannelAudioHost]（AudioAdapters.kt，把 core/audio 的
 * [com.eratw.port.core.audio.soundChannelTable] / [com.eratw.port.core.audio.SoundChannel]
 * 状态机映射成宿主）。引擎的动作面（pause / resume / stop / setSpeed / setPreservePitch /
 * 两个时间读数 / 音量读数）在 M4-D～M5-B 三波里已经全部齐备并有
 * `SoundChannelTest`（31 例）钉死，本族**不新增引擎动作**，只补函数层的参数口径。
 *
 * ------------------------------------------------------------------
 * 一、三条注册（原版 `FunctionMethodCreator.cs` 注册表 + 类体行号）
 * ------------------------------------------------------------------
 * - `GETSOUNDORBGMINFO = new GetSoundOrBgmInfoMethod()` —— 注册表 :9308，
 *   类体 :8769-8828（构造 :8771-8787 声明 `ReturnType = typeof(long)`、
 *   参数形如 (Int, Int) 且 `OmitStart = 1` ⇒ 第二参可省；`CanRestructure = false` :8786）。
 * - `SOUNDCONTROL = new SoundControlMethod()` —— 注册表 :9310，
 *   类体 :8874-8947（构造 :8876-8902 给**两个** ArgTypeList：
 *   :8881-8888 恰好 2 个 Int（`OmitStart` 缺省 0 ⇒ 无可选参），
 *   :8889-8899 四个 Int 且 `OmitStart = 3` ⇒ 合法元数只有 2 / 3 / 4）。
 * - `BGMCONTROL = new BgmControlMethod()` —— 注册表 :9312，
 *   类体 :8968-9031（构造 :8970-8991 两个 ArgTypeList：:8977 恰好 1 个 Int，
 *   :8979-8988 三个 Int 且 `OmitStart = 2` ⇒ 合法元数只有 1 / 2 / 3）。
 *
 * ------------------------------------------------------------------
 * 二、参数族与非法参数口径（逐条带行号）
 * ------------------------------------------------------------------
 * GETSOUNDORBGMINFO(声道, 信息位?)：
 * - 声道实参先做 C# 的 `(int)` 转换（:8791），本移植用 `Long.toInt()` 复刻同一个
 *   **低 32 位截断**（`Emuera.csproj` 全目录 grep `CheckForOverflowUnderflow` 零命中 ⇒
 *   C# 默认 unchecked；同一口径已见 expr/FormStringTerm.kt:96）。
 *   ⇒ `4294967295`（0xFFFFFFFF）截断成 -1 ⇒ 走 **BGM** 分支；
 *     `4294967296` 截断成 0 ⇒ 走声道 0。这不是推测，是官方可观测行为。
 * - 声道取值：`-1` = BGM（:8793-8796，**不**惰性建，`GlobalStatic.Bgm` 为 null 即取不到）；
 *   `0 .. Sound.Length-1` = 音效（:8797-8804，空槽就地 `new Sound()` 并写下标）；
 *   其余（`<= -2` 与 `>= Length`）= **静默**，sound 保持 null ⇒ 落到 :8805-8808 返回 **0**，
 *   且**不写 RESULT**（原版在写 RESULT 之前就 return 了）。报错？没有：全程无 throw。
 * - 信息位（第二参）缺省（:8809-8817）：把五项依次写进 `RESULT_ARRAY[0..4]`，
 *   **返回 RESULT[0]**（即总时长毫秒）。
 * - 信息位显式给出（:8818-8826）：同样先做 `(int)` 截断，再 1→总时长毫秒、2→当前毫秒、
 *   3→在播 1/0、4→音量整数、5→变速百分比；**其余一切取值（含 0、6、负数）都回 0**。
 *
 * SOUNDCONTROL(声道, 操作码, 变速? , 不保留音高?)：
 * - 声道与操作码都做 `(int)` 截断（:8906、:8907）。
 * - 越界判定在**建道之前**（:8908-:8911 早于 :8912-:8915）：`<0` 或 `>= Sound.Length`
 *   ⇒ 返回 **-1**、静默、不物化声道。
 * - 合法下标先物化声道（:8912-8915），**再**看操作码 ⇒ 连非法操作码也会留下一个已建的声道
 *   （本移植用 [AudioChannelMaterializer] 复刻这一步，见下节「与既有实现的差异」）。
 * - 恰好 2 参（:8916-:8932）：0=pause、1=resume、2=stop，三条都返回 **1**；其余操作码 **-2**。
 *   注意 0/1/2 三条**只在恰好 2 参时被接受**：带第 3 参再传 0/1/2 会掉到 :8945 返回 -2。
 * - 3 或 4 参且操作码 3（:8933-8944）：`speed = (float)arguments[2] / 100f`（:8935，
 *   long→**float** 再除 100f，是 binary32 侧的除法），第 4 参**非 0 时关闭**音高保留
 *   （:8937-8940，比较用的是 long，不做截断），随后**先 SetPreservePitch 再 setSpeed**
 *   （:8941-8942 的源码顺序），返回 **1**。
 * - 其余形态（3/4 参 + 操作码非 3）：返回 **-2**（:8945）。全程同样无 throw、无文案。
 *
 * BGMCONTROL(操作码, 变速?, 不保留音高?)：
 * - 只有操作码做 `(int)` 截断（:8995），**没有**声道参数，也就没有越界概念。
 * - 分派之前无条件物化 Bgm（:8996-8999：`GlobalStatic.Bgm == null` 就 `new Sound()`），
 *   所以 `BGMCONTROL 9` 这种非法操作码（返回 -2）**也会**把 Bgm 建出来 —— 这是本族
 *   唯一「函数层可直接观测到物化」的地方，因为 GETSOUNDORBGMINFO 的 BGM 侧自己不建
 *   （:8793-8796）：物化前 `GETSOUNDORBGMINFO -1, 4` 回 0（sound == null 分支），
 *   物化后回 **100**（`Sound.cs:13` 的 `volume = 1f` ⇒ `getVolume()` = 100）。
 * - 恰好 1 参（:9000-9015）：0=pause、1=resume、2=stop ⇒ 返回 1；其余 **-2**。
 * - 2/3 参且操作码 3（:9017-9028）：`speed = (float)arguments[1] / 100f`（:9019），
 *   第 3 参非 0 ⇒ 关闭音高保留（:9021-9024），先 SetPreservePitch 再 setSpeed（:9025-9026），
 *   返回 1。
 * - 2/3 参且操作码非 3（含「传 0/1/2 却多给了第 2 参」）：返回 **-2**（:9029）。
 *
 * ------------------------------------------------------------------
 * 三、GETSOUNDORBGMINFO 信息位对照表（「返回什么 / 取不到时返回什么」）
 * ------------------------------------------------------------------
 * 设 `S` = 目标声道（`SoundInfo`，AudioAdapters.kt 的 `snapshot()`），取不到 = `S == null`
 * （BGM 未建 或 音效声道越界）：
 *
 * | 信息位 | 返回 | 取不到（S == null） | 口径来源 |
 * |---|---|---|---|
 * | 省略 | 写 RESULT[0..4] 后返回 RESULT[0] = `(long)(GetTotalTime()*1000.0)` | **不写 RESULT**、返回 0 | :8805-8817 |
 * | 1 | `(long)(GetTotalTime()*1000.0)` 总时长毫秒 | 0 | :8811、:8820 |
 * | 2 | `(long)(GetCurrentTime()*1000.0)` 当前位置毫秒 | 0 | :8812、:8821 |
 * | 3 | `isPlaying() ? 1 : 0`（**已暂停 = 0**，Sound.cs:171-178） | 0 | :8813、:8822 |
 * | 4 | `getVolume()`（出厂 100；binary32 往返使 53→52、59→58，Sound.cs:189-192） | 0 | :8814、:8823 |
 * | 5 | `(long)(getSpeed()*100.0)` 变速百分比（未播过的声道恒 100，Sound.cs:203-206） | 0 | :8815、:8824 |
 * | 0 / 6 / 负数 / 其它 | 0 | 0 | :8825（switch 的 `_ => 0L`） |
 *
 * 时间两个读数的底层口径（`Sound.GetTotalTime` / `GetCurrentTime`，Sound.cs:31-57）与
 * 「循环不改变总时长」这条怪癖已经在 audio/SoundChannel.kt 的 [ChannelDecoder] KDoc 里钉死：
 * 解码流为 null（未播 / 已 stop）时恒 0.0，畸形 WaveFormat 也恒 0.0。
 *
 * ------------------------------------------------------------------
 * 四、与「既有实现」的差异（本文件为什么要存在）
 * ------------------------------------------------------------------
 * DebugMiscMethods.kt 里已经挂着同名的三条（:154-165 注册、:377-452 实现，
 * 该文件本波归 i-time 独占，路次不许动）。与原版逐条比对，那三条有两处**口径缺口**，
 * 本文件按原版落定：
 * 1. **缺 `(int)` 截断**：那三条直接在 Long 上比 `== -1L` / `>= 0 && < soundChannels` /
 *    `== 3`，于是 `GETSOUNDORBGMINFO 4294967295`（截断后是 -1，原版读 BGM）、
 *    `SOUNDCONTROL 0, 4294967299`（截断后是 3，原版进变速分支）在原版能正常工作、
 *    在旧实现里分别回 0 与 -2。
 * 2. **非法操作码不物化声道**：原版建道在分派**之前**（:8912-8915、:8996-8999），
 *    旧实现在返回 -2 的路径上完全没碰宿主 ⇒ `BGMCONTROL 9` 之后再
 *    `GETSOUNDORBGMINFO -1, 4` 会读到 0 而不是 100。
 * 3. 变速入参旧实现走 `Long.toDouble() / 100.0`（再在宿主侧折回 float），本文件按
 *    :8935/:9019 用 `Long.toFloat() / 100f` 的**纯 float** 链路。`SoundChannelTest`
 *    的百分比截断表（:335-373）已独立核对过两条链路在 0..200 逐点相同，
 *    故本差异不改变任何既有读数，只是把算式写成与原版同形。
 *
 * 接线（组长收口）：[registerAudioMethods] 与 DebugMiscMethods 的三条是**同名真实现**，
 * 二者只能留一个（BuiltinRegistry.register 对「真实现撞真实现」直接 error，
 * 见 Builtins.kt:594-601）。收口口径：删掉 DebugMiscMethods.kt 的 :154-165 与 :377-452
 * （连同只被这三条使用的 audioHost :244-246 与 writeResult :454-459；intArg 其它函数还在用，
 * 保留），再在 `Builtins.buildRegistry()` 里调用本函数（顺序无所谓，占位名单不需要动 ——
 * HostMethods.kt:435-437 的三条占位会被本族自动覆盖，见 Builtins.kt:603-610 的让路规则）。
 *
 * 切分派时**必须同步改**的两条既有断言（否则音频闸门回退）：
 * `core-vm/src/test/kotlin/com/eratw/port/core/audio/AudioAdaptersTest.kt:225` 与 `:230` 现在
 * 断言的是「本移植推迟建道」（原文注释自己写了「原版此处已有对象」）。本文件按原版落定后，
 * 那两处 `assertNull` 要改成 `assertNotNull`。其余经 `ctx.callFunction` 走这三条函数的既有断言
 * （AudioAdaptersTest 全篇、SoundHostWiringTest:103/182/189/201/204/206、
 * DebugMiscMethodsTest:362-450）都用的是小数值与 float 精确可表示的倍率（150 → 1.5、50 → 0.5），
 * 与本实现逐点相同，不需要动。
 */
internal fun registerAudioMethods(r: BuiltinRegistry) {
    val i = OperandType.INT
    r.register(BuiltinMethod("GETSOUNDORBGMINFO", i, 1, 2) { c, a -> Value.IntV(AudioMethods.getSoundOrBgmInfo(c, a)) })
    r.register(BuiltinMethod("SOUNDCONTROL", i, 2, 4) { c, a -> Value.IntV(AudioMethods.soundControl(c, a)) })
    r.register(BuiltinMethod("BGMCONTROL", i, 1, 3) { c, a -> Value.IntV(AudioMethods.bgmControl(c, a)) })
}

/**
 * 声道 / Bgm 的「物化」动作 —— 原版在操作码分派**之前**就把空槽 `new Sound()` 写下标
 * （SOUNDCONTROL :8912-8915、BGMCONTROL :8996-8999）。
 *
 * 为什么函数层需要它：`AudioHost`（DebugMiscMethods.kt:226-234）只有「读快照」和
 * 「执行操作」两类动作，非法操作码的路径不调用任何动作，于是原版那条
 * 「先建道后分派」的可观测后果（BGM 侧：之后 `GETSOUNDORBGMINFO -1, 4` 回 100 而不是 0）
 * 就丢了。本接口把它补回来，实现方 = [com.eratw.port.core.audio.ChannelAudioHost]
 * （直接落到 `SoundChannelTable.channelAtOrCreate` / `bgmOrCreate`）。
 *
 * 宿主没实现本接口时函数层照常工作（`as?` 取不到就跳过物化）——音效侧完全不可观测，
 * 只有 BGM 侧的读数差会暴露，见 [AudioMethods] 类头第 2 条。
 */
interface AudioChannelMaterializer {
    /** 物化音效声道 [channel]；越界（原版取不到槽位）返回 false，不做任何事。 */
    fun materializeSound(channel: Int): Boolean

    /** 物化 Bgm（原版 `GlobalStatic.Bgm ??= new Sound()`，:8996-8999）。 */
    fun materializeBgm()
}

/**
 * 三条函数的实现体（与 [registerAudioMethods] 一一对应，测试直接调用本对象，
 * 不经过全局注册表 —— 全局表里现在挂的还是 DebugMiscMethods 的旧三条，见类头第四节）。
 *
 * 宿主缺口口径：`BuiltinHost.audio` 未注入时抛 CodeEE 点名要注入的属性，
 * 与 DebugMiscMethods.kt:244-246 同一条文案（原版是进程级静态、不存在该分支）。
 * 参数类型不对（传字符串）抛 CodeEE，与 DebugMiscMethods.kt:465-468 同一条文案
 * （原版由 CheckArgumentType 报 `ArgsTypeNotMatches`，移植侧的 arity/type 检查
 * 在 BuiltinMethod 桥与 intArg 两处）。
 */
internal object AudioMethods {

    /** GETSOUNDORBGMINFO：信息位五项 + RESULT 写入。spec: FunctionMethodCreator.cs:8769-8828 */
    fun getSoundOrBgmInfo(c: BuiltinContext, a: List<Value?>): Long {
        // spec: :8791 —— int num = (int)arguments[0].GetIntValue(exm)
        val channel = intArg(a, 0, GETSOUNDORBGMINFO).toInt()
        val audio = audioHost(c, GETSOUNDORBGMINFO)
        val info = when {
            // spec: :8793-8796 —— -1 取 GlobalStatic.Bgm，**不**惰性建
            channel == BGM_CHANNEL -> audio.bgmInfo()
            // spec: :8797-8804 —— 合法下标空槽就地 new Sound()（Sound.Length = 10，GlobalStatic.cs:42）
            channel >= 0 && channel < c.host.soundChannels -> audio.soundInfo(channel)
            // spec: :8805-8808 —— 其余取值静默，sound 仍为 null ⇒ 返回 0、不写 RESULT
            else -> null
        } ?: return 0L
        val values = longArrayOf(
            (info.totalSeconds * 1000.0).toLong(),   // spec: :8811 / :8820
            (info.currentSeconds * 1000.0).toLong(), // spec: :8812 / :8821
            if (info.playing) 1L else 0L,            // spec: :8813 / :8822
            info.volume,                             // spec: :8814 / :8823
            (info.speed * 100.0).toLong(),           // spec: :8815 / :8824
        )
        // spec: :8809-8817 —— 无第二参时写 RESULT[0..4] 并返回 RESULT[0]
        if (a.size < 2 || a[1] == null) {
            for (i in values.indices) writeResult(c, i, values[i])
            return values[0]
        }
        // spec: :8818-8826 —— switch 的默认分支（0、6、负数……）恒 0
        return when (intArg(a, 1, GETSOUNDORBGMINFO).toInt()) {
            1 -> values[0]
            2 -> values[1]
            3 -> values[2]
            4 -> values[3]
            5 -> values[4]
            else -> 0L
        }
    }

    /** SOUNDCONTROL：spec: FunctionMethodCreator.cs:8874-8947 */
    fun soundControl(c: BuiltinContext, a: List<Value?>): Long {
        // spec: :8906-8907 —— 声道与操作码各自做 (int) 截断
        val channel = intArg(a, 0, SOUNDCONTROL).toInt()
        val op = intArg(a, 1, SOUNDCONTROL).toInt()
        val audio = audioHost(c, SOUNDCONTROL)
        // spec: :8908-8911 —— 越界在建道之前判定：静默返回 -1，不物化、不报错
        if (channel < 0 || channel >= c.host.soundChannels) return -1L
        // spec: :8912-8915 —— 合法下标先物化声道，之后才轮到操作码分派
        (audio as? AudioChannelMaterializer)?.materializeSound(channel)
        if (a.size == 2) {
            // spec: :8916-8932 —— 恰好 2 参才有 0/1/2 = 暂停/继续/停止，三条都回 1
            return when (op) {
                OP_PAUSE -> { audio.controlSound(channel, AudioOp.PAUSE); RESULT_OK }
                OP_RESUME -> { audio.controlSound(channel, AudioOp.RESUME); RESULT_OK }
                OP_STOP -> { audio.controlSound(channel, AudioOp.STOP); RESULT_OK }
                else -> RESULT_BAD_OP
            }
        }
        if (op == OP_SPEED) {
            // spec: :8935 —— (float)arguments[2].GetIntValue(exm) / 100f（纯 binary32 除法）
            val speed = intArg(a, 2, SOUNDCONTROL).toFloat() / 100f
            // spec: :8937-8940 —— 第 4 参非 0 时**关闭**音高保留；比较用 long，无截断
            val preservePitch = !(a.size >= 4 && intArg(a, 3, SOUNDCONTROL) != 0L)
            // spec: :8941-8942 —— 先 SetPreservePitch 再 setSpeed
            audio.controlSound(channel, AudioOp.SET_SPEED, speed.toDouble(), preservePitch)
            return RESULT_OK
        }
        // spec: :8945 —— 3/4 参形态下操作码不是 3 ⇒ -2（0/1/2 也不例外）
        return RESULT_BAD_OP
    }

    /** BGMCONTROL：spec: FunctionMethodCreator.cs:8968-9031 */
    fun bgmControl(c: BuiltinContext, a: List<Value?>): Long {
        // spec: :8995 —— int num = (int)arguments[0].GetIntValue(exm)
        val op = intArg(a, 0, BGMCONTROL).toInt()
        val audio = audioHost(c, BGMCONTROL)
        // spec: :8996-8999 —— 无条件物化 Bgm（在操作码分派之前；非法操作码也会建）
        (audio as? AudioChannelMaterializer)?.materializeBgm()
        if (a.size == 1) {
            // spec: :9000-9015 —— 恰好 1 参才有 0/1/2
            return when (op) {
                OP_PAUSE -> { audio.controlBgm(AudioOp.PAUSE); RESULT_OK }
                OP_RESUME -> { audio.controlBgm(AudioOp.RESUME); RESULT_OK }
                OP_STOP -> { audio.controlBgm(AudioOp.STOP); RESULT_OK }
                else -> RESULT_BAD_OP
            }
        }
        if (op == OP_SPEED) {
            // spec: :9019 —— (float)arguments[1].GetIntValue(exm) / 100f
            val speed = intArg(a, 1, BGMCONTROL).toFloat() / 100f
            // spec: :9021-9024 —— 第 3 参非 0 ⇒ 关闭音高保留
            val preservePitch = !(a.size >= 3 && intArg(a, 2, BGMCONTROL) != 0L)
            // spec: :9025-9026 —— 先 SetPreservePitch 再 setSpeed
            audio.controlBgm(AudioOp.SET_SPEED, speed.toDouble(), preservePitch)
            return RESULT_OK
        }
        // spec: :9029 —— 2/3 参形态下操作码不是 3 ⇒ -2
        return RESULT_BAD_OP
    }

    /** 原版 `GlobalStatic.Bgm` 的声道编码（FunctionMethodCreator.cs:8793）。 */
    const val BGM_CHANNEL = -1

    /** 操作码 0/1/2/3（FunctionMethodCreator.cs:8918-8933、:9002-9017）。 */
    const val OP_PAUSE = 0
    const val OP_RESUME = 1
    const val OP_STOP = 2
    const val OP_SPEED = 3

    /** 返回值：成功 1（:8922 等）、越界 -1（:8910）、非法操作码 -2（:8930、:8945）。 */
    const val RESULT_OK = 1L
    const val RESULT_OUT_OF_RANGE = -1L
    const val RESULT_BAD_OP = -2L

    private const val GETSOUNDORBGMINFO = "GETSOUNDORBGMINFO"
    private const val SOUNDCONTROL = "SOUNDCONTROL"
    private const val BGMCONTROL = "BGMCONTROL"
}

private fun intArg(args: List<Value?>, i: Int, fn: String): Long {
    val v = args.getOrNull(i) ?: throw CodeEE("函数 $fn：缺少第 ${i + 1} 个参数")
    return (v as? Value.IntV)?.v ?: throw CodeEE("函数 $fn 的第 ${i + 1} 个参数必须是整数")
}

private fun audioHost(c: BuiltinContext, fn: String): AudioHost =
    c.host.audio
        ?: throw CodeEE("函数 $fn：音频宿主尚未接线（需要宿主注入 BuiltinHost.audio）")

/** 写 RESULT 的第 [index] 格；变量表缺该格时跳过（与原版直接写下标 0..4 的差异见 DebugMiscMethods.kt:454）。 */
private fun writeResult(c: BuiltinContext, index: Int, value: Long) {
    if (c.store.getLength("RESULT", -1) > index) {
        c.store.writeInt("RESULT", longArrayOf(index.toLong()), value)
    }
}
