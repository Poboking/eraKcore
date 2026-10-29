package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.RuntimeAux

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
 * 语料实测：`ERB` 子树零处 `CALLSHARP`，`fixtures/golden/baseline/pc-golden-root.txt` 亦无该语句
 * ⇒ 本通道在 PC 侧**无可比读数**，基准一字不动，只有注册表语义在此钉死。
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
