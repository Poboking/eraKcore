package com.eratw.port.core.fn

import com.eratw.port.core.code.Program
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.stmt.SpCallSharpArgument

/**
 * `CALLSHARP` 的本地方法侧（原版的插件方法通道）。
 *
 * 原版链路：语句 `CALLSHARP_Instruction`（spec: `GameProc.Function/FunctionIdentifier.cs:1169-1235`）
 * 在装载期把常量方法名绑定到 `PluginManager` 表里的 `IPluginMethod`
 * （spec: `Runtime.Utils.PluginSystem/PluginManager.cs:452-470`），运行期把实参转成
 * [PluginMethodParameter] 数组交给方法本体，再把**变量实参**位置上的值回写
 * （spec: `FunctionIdentifier.cs:1212-1233`）。
 *
 * 本工程不加载 .NET 插件 DLL（宿主侧能力，属 platform/M5），但**通道本身是 core 语义**：
 * 参数转换、回写口径、名字查表都得在 core 里定死，宿主只负责往 [NativeMethodRegistry] 注册。
 * 语料实测：4.980 的 `ERB` 子树零处 `CALLSHARP`（`pc-golden-root.txt` 亦无该语句），4.981 起
 * AI对话补丁有 3 处（`ERB/DLC/AI对话补丁/对话调用.erb:35-36` 调 `ModelSet`/`Chat`）⇒
 * 宿主侧的桩接线在 app GameHost.wireCorpusNativeMethods（4.981 收口），注册表语义仍在此钉死。
 */

/**
 * 单个实参槽 —— 与原版 `PluginMethodParameter` 同形：两个值域并存，用哪一侧由
 * **接收方的静态类型**决定，不是由槽自己的 `isString` 决定（spec: `FunctionIdentifier.cs:1221-1231`
 * 用 `variableTerm.IsString` 选 `strValue` / `intValue`）。
 *
 * `var` 是有意的：本地方法就是靠改这些字段把结果带回语句层（spec: `IPluginMethod.cs:3-14`
 * 的 `void Execute(PluginMethodParameter[] args)` —— 没有返回值，出参全靠改数组元素）。
 *
 * @property isString 转换期的来源类型（spec: `PluginMethodParameter.cs:7-24` 两个构造器各自的 `isString`）。
 * @property intValue INT 侧值；原版的 `long intValue`。
 * @property strValue STR 侧值；原版的 `string strValue`。
 */
class NativeParam(
    val isString: Boolean = false,
    var intValue: Long = 0L,
    var strValue: String = "",
)

/**
 * 宿主注册的本地方法（原版 `IPluginMethod` 的 core 侧等价物，spec: `IPluginMethod.cs:5-14`）。
 * 返回值忽略 —— 原版 `Execute` 是 `void`，结果只能写进出参槽。
 */
fun interface NativeMethod {
    fun execute(args: Array<NativeParam>)
}

/**
 * 名字 → [NativeMethod] 的注册表。
 *
 * 大小写口径：原版键归一化取决于 `Config.IgnoreCase` —— 注册与查表都做
 * `ToUpperInvariant`（spec: `PluginManager.cs:550-558` / `:452-460` / `:462-470`），
 * 而 `methods` 字典本身是**区分大小写**的普通 Dictionary（spec: `PluginManager.cs:25`），
 * 所以 `IgnoreCase=false` 时是「原文键 + 不折叠」，`IgnoreCase=true` 时两边都大写化 = 等效不敏感。
 * 本表恒按大写化取键（= 原版 `IgnoreCase=true` 一侧）；本工程 `Config.IgnoreCase`
 * 由配置层提供，语句层拿不到该位 ⇒ 差异登记为 TODO(verify)，见本类 [find]。
 *
 * 重名：原版 `methods.Add(text, method)` 命中已有键直接抛 `ArgumentException`
 * （spec: `PluginManager.cs:557`）—— 装载期就崩，不是静默覆盖。此处同口径抛 [CodeEE]。
 */
class NativeMethodRegistry {
    private val methods = HashMap<String, NativeMethod>()

    /** 注册（宿主接线点）。重名报错，复刻原版 `Dictionary.Add` 的崩溃口径。 */
    fun register(name: String, method: NativeMethod) {
        val key = name.uppercase()
        if (methods.containsKey(key)) throw CodeEE("本地方法 $name 重复注册")
        methods[key] = method
    }

    /** 查表；未注册返回 null（调用方决定报错口径，见 StatementExecutor 的 CALLSHARP 分支）。 */
    fun find(name: String): NativeMethod? = methods[name.uppercase()]

    /** 是否存在。spec: `PluginManager.cs:462-470` HasMethod。 */
    fun has(name: String): Boolean = methods.containsKey(name.uppercase())

    /** 已注册名单（排序，便于测试与调试输出稳定）。 */
    fun names(): List<String> = methods.keys.sorted()

    /** 清空（RESTART / 重载插件后用；同原版 `ClearMethods`，spec: `PluginManager.cs:545-548`）。 */
    fun clear() = methods.clear()
}

/** 注册表挂在每 VM 一份的 aux 槽名（AGENTS.md §2 铁律 4：禁静态可变全局）。 */
const val NATIVE_METHODS_SLOT: String = "sharpMethods"

/** 取（首次访问即建）挂在 [aux] 上的 [NativeMethodRegistry]；与 `soundChannelTable` 同形态。 */
fun nativeMethods(aux: RuntimeAux): NativeMethodRegistry =
    aux.slot(NATIVE_METHODS_SLOT) { NativeMethodRegistry() }

/**
 * CALLSHARP 的**行级联结记录**（B1a 报错相位，2026-11-06）。
 *
 * 原版把「常量名查不查得到本地方法」的裁决放在**装载/联结期**：
 * `CALLSHARP_Instruction.SetJumpTo`（spec: FunctionIdentifier.cs:1193-1210）对常量名查
 * `PluginManager.HasMethod`（:1201-1202），未命中 `:1204 ParserMediator.Warn(..., isError: true)`
 * 只把 `line.IsError/ErrMes` 记到行上（ParserMediator.cs:127-131 的 :129-130），命中则把
 * `CallFunc` 绑死在实参对象上（:1208）—— **此后不再重查**；执行期走到该行由
 * Process.cs:1017-1019（惰性建参后再查一遍是 :1030-1032）抛 `CodeEE(ErrMes)`。
 *
 * ## 为什么移植侧有两条裁决通道（装载趟预填 + 首执行兜底）
 *
 * 原版的 `PluginManager` 是进程级单例，**先于**脚本装载填好（DLL 在启动期加载）；
 * 移植侧的注册表挂 `store.aux`（[nativeMethods]），而宿主装配点在
 * app `GameHost.wireCorpusNativeMethods`（GameHost.kt:2572 起的函数，调用点 :3531）——
 * **晚于** GameSession 构造期的语料装配（GameSession.kt init）⇒ 注册表不可能早于会话构造
 * 就位，照抄原版相位
 * 会把已接桩的 ModelSet/Chat 全部误杀（0.2.39 上报簇的复发方向）。
 * CSHARP-WIRE（2026-11-06）把装配序收口成会话装配段内的三段严格序：
 * `wireCorpusNativeMethods`（注册终表）→ [prefillSharpLinks]（**装载趟裁决**：扫 CALLSHARP
 * 常量名、绑本表、未命中收警告统一上屏 = 原版 warningList 装载完一起显示的相位，
 * ParserMediator.cs:143-158）→ `drive()`。生产装配两段都跑，CALLSHARP 行在**任何执行之前**
 * 就已带裁决 —— 与原版「该行不执行也见警告」等值。
 *
 * 首执行裁决（本类的历史语义）保留为**兜底通道**：没跑过预填的会话（core 装置 / 直接
 * `ProcessRuntime` 的测试）与非常量名（原版 `useCallForm`，联结期不裁决）的行，第一次
 * 执行时按同一套口径裁 —— 机制与时点见 [com.eratw.port.core.sem.StatementExecutor]
 * 的 CALLSHARP 分支。两条通道写同一张表、同一套键，预填过的行首执直接取记录值
 * （**不二次裁决**）。
 *
 * ## 裁决一经落笔就固定（不重查）
 *
 * 与原版「联结期绑死 CallFunc / IsError」同口径：命中把方法本体缓存进 [SharpLinkTable.Link]，
 * 未命中把文案记进行级错误态。此后即使注册表长出同名方法（或 `NativeMethodRegistry.clear`
 * 清了表），该行也照记录值走 —— 原版 `SpCallSharpArgment.CallFunc` 在装载期已绑死，行为相同。
 *
 * ## 一张表、两种行键（B5，2026-11-07）
 *
 * 执行侧只有**一份**裁决逻辑（`StatementExecutor` 的 `resolveSharpMethod`），行键由入口形态决定：
 * - 扁平语句池通道（`Program.flatStmtSource` 就绪时，桌面/测试默认）= 语句池下标
 *   （每 VM 一份 program、装载一次，下标跨执行稳定）；
 * - 对象树通道（`flatStmtSource == null`，**Android 生产默认**：`GameHost` 传
 *   `flatStatements = false`，见 `GameHost.kt:3341-3360` + `Vm.kt:1866-1874` +
 *   `ProcessRuntime.kt:331-336`）= `SpCallSharpArgument` 实例身份（对象树每行一份参数实例，
 *   与语句 1:1，`SpCallSharpArgument` 未覆写 equals ⇒ 普通 HashMap 即身份语义）。
 *
 * 两种键都落进同一张表：[bind] 对 `Any` 键写入，装载趟预填把同一份 [Link] 按两种键各写一次，
 * 因此两条通道读到的是**同一份裁决**，不会各裁一次。
 */
class SharpLinkTable {
    /** 单行裁决：[method] 非 null = 命中（联结期绑定）；[errMes] 非 null = 行级错误态。 */
    class Link(val method: NativeMethod?, val errMes: String?)

    private val byKey = HashMap<Any, Link>()

    /** 读裁决；[key] = 语句池下标（装箱 Long）或 `SpCallSharpArgument` 实例；null = 该行还没裁过。 */
    fun link(key: Any): Link? = byKey[key]

    /** 落裁决（同一键重复落 = 后写覆盖；正常流每行每种键只裁一次）。 */
    fun bind(key: Any, link: Link) {
        byKey[key] = link
    }
}

/** [SharpLinkTable] 挂的 aux 槽名（同 [NATIVE_METHODS_SLOT] 的槽纪律）。 */
const val SHARP_LINK_SLOT: String = "sharpLink"

/** 取（首次访问即建）挂在 [aux] 上的 [SharpLinkTable]。 */
fun sharpLinks(aux: RuntimeAux): SharpLinkTable = aux.slot(SHARP_LINK_SLOT) { SharpLinkTable() }

/**
 * CALLSHARP 的**装载趟联结裁决**（CSHARP-WIRE C2，2026-11-06）：扫描 [program] 里全部
 * CALLSHARP 语句的常量名实参，对照 [nativeMethods] 注册表把裁决**预填**进 [SharpLinkTable]
 * （语句池下标与实参对象身份两种行键各写一次，见该表 KDoc —— 扁平通道与对象树通道
 * 因此读到同一份裁决），未命中收集
 * `No native method X found`（官方英文原样，`FunctionIdentifier.cs:1204`，ADR-005）
 * 按语句池序返回 —— 取用方（宿主装配段）在装载结束后统一上屏（C3，形态对齐
 * `ParserMediator.cs:143-158` FlushWarningList 的「装载完一起显示」）。
 *
 * ## 裁决口径（与原版 SetJumpTo 逐条对齐，spec: FunctionIdentifier.cs:1193-1210）
 *
 * - 常量名判定 = `funcNameTerm is StrLiteral`：原版 SP_CALLCSHARP_ArgumentBuilder 的名字恒
 *   SingleStrTerm（ArgumentParser.cs:800）⇒ `IsConst/ConstStr` 恒置（`:850-853`），没有动态名
 *   形态；非常量名走 `useCallForm`（`:1195-1199`），**联结期不裁决、不告警** —— 本函数同口径
 *   跳过（跳过的行保留执行期「现求现查」的既有语义，见 StatementExecutor 的 KDoc）。
 * - 未命中 = 行级错误态（原版 `:1204` Warn(isError:true) → line.IsError/ErrMes，
 *   ParserMediator.cs:129-130）：把文案绑进 [SharpLinkTable.Link.errMes]，此后**不重查** ——
 *   注册表再长出同名也不复活；已裁决过的行（该语句池下标已有记录）直接跳过，
 *   绝不二次裁决（原版 CallFunc/IsError 在联结期绑死，`:1208`）。
 * - 命中 = 方法本体绑死（[SharpLinkTable.Link.method]），`NativeMethodRegistry.clear` 不影响
 *   已绑定行（原版 `PluginManager.ClearMethods` 同口径，PluginManager.cs:545-548）。
 *
 * ## 扫描点与装配序（C1 的证据链）
 *
 * 原版 `PluginManager` 是进程级单例，DLL 在启动期先于脚本装载填好，联结期（装载期）查表
 * 水到渠成；移植侧的注册表挂 [nativeMethods]（`store.aux`），而 store 在 GameSession 构造
 * init 第 3 步才出生（GameSession.kt 变量池段），宿主注册（app `GameHost.wireCorpusNativeMethods`）
 * 又只能在会话建成后（构造喂参段被契约冻结，CONTRACT-CSHARP-WIRE §1）⇒ 「注册表先于容器
 * 解码」在物理上做不到。装配序挪动因此落地为会话装配段内的**三段严格序**：
 * `wireCorpusNativeMethods`（注册终表）→ **本函数**（装载趟裁决 + 警告收集）→ `drive()`
 * （任何脚本语句之前）—— 「本地方法表必须在装载趟扫描时已可用」（C1）由此保证。
 *
 * 扫描本身不进容器装载趟（装载语义红线）：erbc 生产的侧车可信档**不解语句**
 * （StmtOffsetSidecar 直供记录偏移，ErbcStream 装载段），压扁趟只在 flatStatements=true
 * （Android 生产默认 false，GameHost 装配段）——本函数改走 [Program.sharpStatementIndices]
 * 的**记录标签字节扫**（语句记录首字节 = StmtTag，ErbcWriter.stmtHead 的写序；
 * CALLSHARP = `ErbcFormat.StmtTag.CALL_SHARP`），命中标签的候选才经 [Program.statement]
 * 物化（惰性池缓存的实例 = 运行期同一实例 ⇒ 按实参对象身份绑的行键命中有效）。
 * source 编译路（桌面/erbcc/测试）语句树本在堆内，同一 accessor 按 args 形态识别，
 * 两路径同一份裁决代码。真语料 CALLSHARP 仅 4.981 的 3 处（AI对话补丁，对话调用.erb:35-36），
 * 物化与绑定的常驻都是个位数语句；标签字节扫 ≈ 一次顺序映射读（LazyStatementRecords 的
 * override KDoc 有成本账）。
 *
 * @return 未命中的警告文案（语句池序 = 原版装载序）；空表 = 全部命中或无 CALLSHARP。
 *   已裁决过的行不重复上报（幂等：重复调用返回值只含新增未裁决行的未命中）。
 */
fun prefillSharpLinks(
    aux: RuntimeAux,
    program: Program,
): List<String> {
    val registry = nativeMethods(aux)
    val links = sharpLinks(aux)
    val warnings = ArrayList<String>()
    for (index in program.sharpStatementIndices()) {
        if (links.link(index.toLong()) != null) continue // 已裁决（两种行键同表）= 不重查
        val stmt = program.statement(index) // 惰性池在此物化并缓存 = 运行期同一实例
        val arg = stmt.args as? SpCallSharpArgument ?: continue // 标签候选的形态闸（防御支）
        val literal = arg.funcNameTerm as? StrLiteral ?: continue // 非常量名 = useCallForm 通道
        val name = literal.value
        val found = registry.find(name)
        val link = if (found != null) {
            SharpLinkTable.Link(found, null)
        } else {
            SharpLinkTable.Link(null, "No native method $name found")
        }
        // 同一份裁决按两种行键各写一次：扁平通道按语句池下标取，对象树通道按实参对象取。
        links.bind(index.toLong(), link)
        links.bind(arg, link)
        if (found == null) warnings.add(link.errMes!!)
    }
    return warnings
}
