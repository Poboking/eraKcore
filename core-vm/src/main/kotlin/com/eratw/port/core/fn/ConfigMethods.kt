package com.eratw.port.core.fn

import com.eratw.port.core.config.ConfigChainLoad
import com.eratw.port.core.config.ConfigChainSource
import com.eratw.port.core.config.ConfigItem
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.config.EmueraConfigWriter
import com.eratw.port.core.config.parseLongStrictly
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.lexer.LexerConfig
import java.nio.file.Path

/**
 * `GETCONFIG` / `GETCONFIGS` —— 从条目表按名字读一个配置值（int 形与 string 形）。
 *
 * ## 权威出处
 * `reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`：
 *   * 注册表 `:9148 ["GETCONFIG"] = new GetConfigMethod(typeisInt: true)`、
 *     `:9149 ["GETCONFIGS"] = new GetConfigMethod(typeisInt: false)`；
 *   * 类体 `GetConfigMethod :6021-6074`：
 *     `funcname = "GETCONFIG"` 在 `:6029`、`ReturnType = typeof(long)` 在 `:6030`；
 *     `funcname = "GETCONFIGS"` 在 `:6034`、`ReturnType = typeof(string)` 在 `:6035`；
 *     参数表 `argumentTypeArray = new Type[1]{ typeof(string) }` 在 `:6037`
 *     （**两形的参数完全相同**，都是 1 个 string；差别只在返回类型）；
 *     `CanRestructure = true` 在 `:6038`；
 *   * 取值入口 `GetSingleTerm :6041-6055`：实参取字符串（`:6043`）→ 空串/null 抛
 *     `ArgIsEmptyString(Name, 1)`（`:6046`）→ 交给 `ConfigData.GetConfigValueInERB`
 *     （`:6049`）→ 它写的 `errMes != null` 就抛该文案（`:6052`）；
 *   * `GetIntValue :6057-6064`：ReturnType 不是 long ⇒ `ExeEE(funcname + "関数:不正な呼び出し")`
 *     （`:6061`，纯内部错误，ERB 侧碰不到）；取到的 `SingleTerm` 不是 `SingleLongTerm`
 *     ⇒ 抛 `InvalidType(Name, "GETCONFIGS")`（`:6063`）；
 *   * `GetStrValue :6066-6073`：对称地，`InvalidType(Name, "GETCONFIG")` 在 `:6072`。
 *
 * `MinorShift.Emuera.Runtime.Config/ConfigData.cs`：
 *   * `GetConfigValueInERB :350-422` —— 键查找与类型分派（本文件 [valueInErb] 的逐分支依据）；
 *   * `GetItem(string) :236-248` —— config → replace → debug 三级串联；
 *     `GetConfigItem(string) :262-284` 比 `Name`/`Text`/`EngText`，
 *     `GetReplaceItem :298-316` 与 `GetDebugItem :330-348` 只比 `Name`/`Text`；
 *   * 未知键：`:352-356` 置 `errMes = InvalidConfigName(text)` 并返回 null；
 *   * 未定义 ConfigCode：`:401` 的 `Enum.IsDefined` 不过 ⇒ `:419` 置
 *     `NotAllowGetConfigValue(text)`。
 *
 * ## 三个必须定死的口径
 * 1. **`typeisInt` 两形的返回值差异**：同一个 `GetConfigValueInERB` 读数，
 *    `GETCONFIG` 只在拿到 `SingleLongTerm` 时给出 `long`，`GETCONFIGS` 只在拿到
 *    `SingleStrTerm` 时给出 `string`；**形状不匹配就报 `InvalidType`**，不做任何转换。
 *    于是 `GETCONFIG("フォント名")`（字符串型条目）报
 *    `GETCONFIG関数: 型が違います (GETCONFIGS関数を使用してください)`，
 *    而 `GETCONFIGS("USE MOUSE")`（布尔型条目 ⇒ `YES`/`NO` ⇒ 1/0，spec: `ConfigData.cs:405-412`）
 *    报对称的另一条。**布尔/整数条目只有 int 形能用、字符串条目只有 string 形能用**，
 *    这一点与「两形只是大小写别名」的直觉相反。
 * 2. **未知键返回什么**：不返回 0、不返回空串 —— 抛 `CodeEE`，文案是
 *    `Lang.Error.InvalidConfigName`（原文 `文字列"{0}"は適切なコンフィグ名ではありません`，
 *    `MinorShift.Emuera.Runtime.Utils.EvilMask/Lang.cs:1742`），`{0}` 是**调用方原样的键串**
 *    （不做 `ToUpperInvariant`；大写化只发生在查表内部，spec: `ConfigData.cs:264`）。
 * 3. **报错文案原文 + 格式化参数**（本文件 [LangError]）：
 *    * `ArgIsEmptyString`（`Lang.cs:3902`）= `{0}関数: 第{1}引数が空文字列です`，
 *      实参 `(base.Name, 1)` —— 参数序号是**字面量 1**；
 *    * `InvalidType`（`Lang.cs:3914`）= `{0}関数: 型が違います ({1}関数を使用してください)`，
 *      实参 `(base.Name, "GETCONFIGS")` 或 `(base.Name, "GETCONFIG")`；
 *    * `InvalidConfigName`（`Lang.cs:1742`）/ `NotAllowGetConfigValue`（`Lang.cs:1746`）如上。
 *    `base.Name` 由 `FunctionMethod.SetMethodName`（spec:
 *    `MinorShift.Emuera.Runtime.Script.Statements.Function/FunctionMethod.cs:168`、`:361-363`，
 *    调用点 `FunctionMethodCreator.cs:9316 method.Value.SetMethodName(method.Key)`）在注册时
 *    写成**注册表的键**，所以恒为大写字面量 `GETCONFIG` / `GETCONFIGS`，
 *    与 ERB 源码里的大小写写法无关。
 *
 * ## 状态挂哪儿
 * 条目表实例放在 `store.aux` 的 [AUX_SLOT]（铁律 4：不许静态可变全局；原版对应物是
 * `ConfigData.Instance` 静态字段，spec: `ConfigData.cs:21`、`:29-36`）。
 * 首次取用时由 [storeFromHost] 播种：宿主实现了 [ConfigChainSource]（开局读过盘的 rooted 宿主）
 * 就走三层链重放，否则退回宿主的 `configOverrides` 表一层（自 M4-K 起那张表本身就是三层链的
 * 合并快照，差别只剩钉位与 `EditorArgument` 的 Trim，见 [storeFromHost] 的 KDoc）。
 * 装配好的 [ConfigChainLoad] 也可以由调用方直接用 [install] 换成正式条目表。
 */
object ConfigMethods {

    /** `store.aux` 槽名（约定：家族名前缀，见 `core/host/RuntimeAux.kt` 的类 KDoc）。 */
    const val AUX_SLOT: String = "config.chain"

    /** 注册表里的两个名字（`FunctionMethodCreator.cs:9148-9149`）。 */
    const val GETCONFIG: String = "GETCONFIG"
    const val GETCONFIGS: String = "GETCONFIGS"

    /**
     * `List<long>` 条目的 `ValueToString` 串（spec: `ConfigItem.cs:81` 落到 `val.ToString()`，
     * .NET 对泛型集合输出运行时类型名）。`GETCONFIGS("EXPLVの初期値")` 能观察到它 ——
     * 写出侧（[EmueraConfigWriter.valueToString]）故意不产出这个垃圾串，读取侧必须有，
     * 因为原版读取侧没有那种守卫。切换前旧实现（曾有 `DebugMiscMethods.configValueToString`）
     * 用的是同一个字面量，读数不变。
     */
    private const val NET_LONG_LIST_TYPE_NAME = "System.Collections.Generic.List`1[System.Int64]"

    // ------------------------------------------------------------------
    // 条目表：取用 / 播种 / 替换
    // ------------------------------------------------------------------

    /** 当前 VM 的条目表；不存在时按 [storeFromHost] 建一份。 */
    fun store(c: BuiltinContext): ConfigStore = holder(c).store ?: storeFromHost(c.host).also { holder(c).store = it }

    /**
     * 把正式装载链的结果装进当前 VM。
     *
     * `RuntimeAux` 只暴露 `slot(name, create)` / `peek` / `clear`（`core/host` 下的 `RuntimeAux.kt`，本波禁改），
     * 没有「替换槽值」的入口，所以槽里放的是一个**可变壳**：槽本身恒建一次，条目表可以换。
     * 这条不违反铁律 4 —— 可变性在每 VM 一份的 aux 槽内，不在静态字段上。
     */
    fun install(c: BuiltinContext, load: ConfigChainLoad): ConfigStore = install(c, load.store)

    /** 直接装一份条目表（单测与宿主已自行组好链时用这条，不必伪造 [ConfigChainLoad]）。 */
    fun install(c: BuiltinContext, fresh: ConfigStore): ConfigStore {
        holder(c).store = fresh
        return fresh
    }

    /** aux 槽里的可变壳；只承载「每 VM 一份」的条目表引用。 */
    private class StoreHolder {
        var store: ConfigStore? = null
    }

    private fun holder(c: BuiltinContext): StoreHolder = holder(c.aux)

    private fun holder(aux: RuntimeAux): StoreHolder = aux.slot(AUX_SLOT) { StoreHolder() }

    /**
     * [store] 的 aux 直入形：给手里只有 `RuntimeAux` + 宿主、没有 [BuiltinContext] 的调用方
     * （`core/proc` 的 [com.eratw.port.core.proc.ProcessRuntime] 运行期词法配置）用。
     * 与 [store] 共用同一个 aux 槽 —— 条目表在一份 VM 里仍然只有一份。
     */
    fun store(aux: RuntimeAux, host: BuiltinHost): ConfigStore =
        holder(aux).store ?: storeFromHost(host).also { holder(aux).store = it }

    /**
     * 运行期词法配置：把 System 组的两个词法开关从当前 VM 的条目表读进 [LexerConfig]。
     *
     * C# 没有这个「组装」动作 —— `Config.SystemAllowFullSpace`（spec: `Config.cs:370`）
     * 与 `Config.SystemIgnoreTripleSymbol`（spec: `Config.cs:372`）是静态字段，
     * `LexicalAnalyzer` 直接读：
     *   * 全角空格：`Analyse` 主循环遇 U+3000 且开关为 false 时抛
     *     `UnexpectedFullWidthSpace`（spec: `LexicalAnalyzer.cs:748-754`）；
     *     `SkipWhiteSpace` 同分支静默停剥（spec: `LexicalAnalyzer.cs:677-684`）。
     *   * 三连记号：`AnalyseFormattedString` 遇 `$`/`*`/`+`/`/`/`=` 时，开关为 false 才展开
     *     （spec: `LexicalAnalyzer.cs:1138`）—— 注意方向：条目默认 false = 展开，
     *     条目置 true 才「不展开」（spec: `ConfigData.cs:110` 的条目名
     *     「FORM中の三連記号を展開しない」）。
     *
     * 两个消费面：
     *   * 运行期动态解析位（GETVAR/GETVARS、SETVAR 系动态表达式、STRFORM、
     *     PRINTFORMS 二段展开、RETURNFORM 整数列表）—— 由本函数供值；
     *   * 装载/编译期（LogicalLineParser / ErbLexer 的离线编译）—— C# 在
     *     `Program.cs:95` 的 `LoadConfig()` 之后才装载脚本，同样吃这份配置；
     *     移植侧编译发生在 erbcc 离线（无配置通道），维持出厂默认 true/false，
     *     差异登记在交付报告（语料 golden 装载告警里没有一条全角空格告警，
     *     编译期读数不可观察，见 `.workbuddy/golden-pc-loadwarnings.txt`）。
     *
     * 条目表走 [store]（aux 槽单例）：首次取用播种与 GETCONFIG 同一条路
     * （rooted 宿主三层链重放 / 覆盖表降级），语料 `emuera.config` 的
     * `WHITESPACE INCLUDES FULL-WIDTH SPACE:NO` 由此真正进入运行期词法。
     */
    fun lexerConfig(c: BuiltinContext): LexerConfig = lexerConfig(c.aux, c.host)

    /**
     * [lexerConfig] 的 aux 直入形（同 [store] 的 aux 直入形理由）。
     *
     * CMP-34：`#DEFINE` 宏表也必须在这里进 [LexerConfig] —— 运行期四个动态解析位
     * （`STRFORM`、`GETVAR`/`GETVARS`、`SETVAR` 系、`PRINTFORMS` 二段展开 / `RETURNFORM`）
     * 都只吃本函数的返回值；空表 = 宏名落到变量解析 ⇒ 真机「未定义的变量」。
     * 表由装配期 [com.eratw.port.core.lexer.installLexerMacros] 挂进 `store.aux`
     * （来源 = `BootMetadata.dimParseConfig.macros`，`BootMetadata.kt:363-365` / `:849-859`）；
     * 宿主显式覆写 [com.eratw.port.core.fn.BuiltinHost.macros] 时以宿主为准（同
     * [com.eratw.port.core.fn.BuiltinHost.macroNames] 的优先序）。
     * 查表口径同 `Config.IgnoreCase`（spec: `Config.cs:299-303`、`IdentifierDictionary.cs:419-427`）。
     */
    fun lexerConfig(aux: RuntimeAux, host: BuiltinHost): LexerConfig {
        val s = store(aux, host)
        return LexerConfig(
            systemAllowFullSpace = s.itemOf(ConfigKey.SystemAllowFullSpace).bool(), // Config.cs:370
            systemIgnoreTripleSymbol = s.itemOf(ConfigKey.SystemIgnoreTripleSymbol).bool(), // Config.cs:372
            // CMP-34：宿主显式注入的宏表优先；否则现读装配期挂在 store.aux 的会话宏表。
            macros = host.macros.ifEmpty { com.eratw.port.core.lexer.lexerMacrosOf(aux) },
            macroIgnoreCase = s.itemOf(ConfigKey.IgnoreCase).bool(), // Config.cs:299-303
        )
    }

    /**
     * 从磁盘跑一遍三层链（`default` → `emuera` → `fixed`）并装载到当前 VM。
     *
     * @param gameDir 游戏根目录（原版的 `Program.ExeDir`）
     * @param analysisMode 原版 `-a` 命令行；true 时 `MAXLOG` 被改写成 10000
     *   （spec: `ConfigData.cs:569-572`）
     * @param debugMode 原版 `-debug` 命令行开关（spec: `Program.cs:81`、`:142-144`）；
     *   true 时在主链之后叠读 `debug.config`（spec: `ConfigData.cs:667-710`），
     *   这是 `DEBUG` 组条目**唯一**的写入口（主链只查 `GetConfigItem`，spec: `:539`）
     * @param store 播种用的条目表；宿主运行态要先塞进来的场合传自己建的那份，
     *   省略时用出厂默认新开一份
     */
    fun installFromDisk(
        c: BuiltinContext,
        gameDir: Path,
        csvDir: Path? = null,
        analysisMode: Boolean = false,
        debugMode: Boolean = false,
        store: ConfigStore = ConfigStore(),
    ): ConfigChainLoad {
        val load = EmueraConfigParser.loadChainFromDisk(gameDir, store, csvDir, analysisMode)
        if (debugMode) EmueraConfigParser.loadDebugFromDisk(gameDir, store, debugDir = null)
        install(c, load)
        return load
    }

    /**
     * 用宿主播种一份条目表 —— 两条通道，顺序都有意义：
     *
     * **优先通道**（宿主实现了 [ConfigChainSource]，即开局已经读过盘的 rooted 宿主）：
     * 1. [seedHostState] 先写宿主**运行态**；
     * 2. [EmueraConfigParser.replayChainOn] 把三层链重放进同一份表 —— 链跑在运行态**之后**，
     *    所以文件值覆盖宿主值，正是原版方向（`LoadConfig` → `Config.SetConfig`，
     *    spec: `ConfigData.cs:491-494`；窗口尺寸是配置读出来后才拿去开窗口的，
     *    `Config.cs` 的 `SetConfig` 全文 `:296` 起）。钉位（`fixed` 层）与 `-a` 改写
     *    这条通道**有**，降级通道没有。
     *
     * **降级通道**（宿主只交得出 `configOverrides` 表，例如只转发 `BuiltinHost` 成员的包装宿主）：
     * 同样先 [seedHostState] 再 [EmueraConfigParser.loadOverrideMap]。自本波起这张表本身就是
     * 三层链的合并快照（`core/proc/BootHosts.kt` 的 `configOverrides` 说明），
     * 所以两条通道的**读数相同**，只有钉位与 `EditorArgument` 的 Trim 两处差别
     * （逐条见 `EmueraConfigParser.loadOverrideMap` 的 KDoc 与交付报告）。
     *
     * [seedHostState] 的口径：宿主运行态（窗口宽高、字号、PRINTC 两项、存档页数、货币
     * 标签与位置、consoleStyle 的三色与字体名）覆盖出厂默认。原版的对应物是
     * `Config.WindowX` 一类由主窗口回填的静态字段（spec: `MainWindow.cs:726` 的逆运算
     * 给出 WindowY = ClientHeight + LineHeight），不是配置文件里的常量 —— 不播种的话
     * 文件里没写的键会永远回注册默认，与实际窗口无关。
     * 其余条目保持 `ConfigData.setDefault` 的出厂值（spec: `ConfigData.cs:52-220`，
     * 已由 `core/config/ConfigSpecTable` 逐条镜像）。
     *
     * 解析失败的单项按 ConfigWarn 口径保留旧值、不让整局启动失败
     * （spec: `ConfigData.cs:563-586`）；这条与切换前的旧实现
     * （曾有 `DebugMiscMethods.ConfigState`，中文文案那版）读数一致。
     */
    fun storeFromHost(host: BuiltinHost): ConfigStore {
        val store = ConfigStore()
        seedHostState(store, host)
        val source = host as? ConfigChainSource
        if (source != null) {
            source.applyChainTo(store) // 三层链 + 在位时的 debug 层（ConfigData.cs:491-493、:667-710）
        } else {
            EmueraConfigParser.loadOverrideMap(store, "host.configOverrides", host.configOverrides)
        }
        return store
    }

    /**
     * `UseSaveFolder` 条目现值 —— 存档目录口径的**唯一取值点**。
     *
     * 原版：`Config.UseSaveFolder`（spec: `Config.cs:147`）由 `SetConfig` 从条目取
     * （spec: `Config.cs:367` `instance.GetConfigValue<bool>(ConfigCode.UseSaveFolder)`），
     * 再决定 `SavDir`（spec: `Config.cs:471-481`：true ⇒ `ExeDir\sav\`，false ⇒ `ExeDir`），
     * 存档路径全部拼在 `SavDir` 上（spec: `VariableEvaluator.cs:2018-2037`）。
     * 注册默认 false（spec: `ConfigData.cs:97`），语料 `emuera.config:51` 与
     * `CSV/_default.config:17` 都是 YES。
     *
     * 本路只负责**把值从条目表取出来交出去**：消费点在 `core/save`（对面 M6-SAVE-* 落盘面），
     * `SaveDataProbe.candidatePaths` 已按 `useSaveFolder` 取**单候选**
     * （`core/save/SaveDataProbe.kt:97-142`），`RootedBuiltinHost.checkSaveData` 也已把本函数的
     * 读数喂进去（M4-L l-savefolder 收口 + 组长接线）。
     */
    fun useSaveFolder(store: ConfigStore): Boolean = store.itemOf(ConfigKey.UseSaveFolder).bool()

    /** [useSaveFolder] 的 VM 面形：读当前 VM 装好的条目表。 */
    fun useSaveFolder(c: BuiltinContext): Boolean = useSaveFolder(store(c))


    /**
     * 宿主运行态 → 条目现值（口径逐条来自切换前的 `DebugMiscMethods.ConfigState.seedFromHost`）。
     * 写入一律走 `ConfigItem.assign`（`value` 是 `private set`，且 `assign` 正是 C# 的 `Value` setter ——
     * `ConfigItem.cs:20-27`：Fixed 时丢弃写入），不在 fn 层开第二条写值通道。
     */
    private fun seedHostState(store: ConfigStore, host: BuiltinHost) {
        fun setInt(key: ConfigKey, raw: Long) {
            // 与旧实现的 Long.toIntClamped() 同口径：夹到 Int32 域（条目本身是 `ConfigItem<int>`）
            store.itemOf(key).assign(
                ConfigValue.IntValue(raw.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()),
            )
        }
        setInt(ConfigKey.WindowX, host.clientWidth)
        // spec: MainWindow.cs:726 —— WindowY 是外框高 = 客户区高 + 一行高
        setInt(ConfigKey.WindowY, host.clientHeight + store.itemOf(ConfigKey.LineHeight).int().toLong())
        setInt(ConfigKey.FontSize, host.fontSize)
        setInt(ConfigKey.PrintCPerLine, host.printCPerLine)
        setInt(ConfigKey.PrintCLength, host.printCLength)
        setInt(ConfigKey.SaveDataNos, host.saveDataNos.toLong())
        store.itemOf(ConfigKey.MoneyLabel).assign(ConfigValue.StringValue(host.moneyLabel))
        store.itemOf(ConfigKey.MoneyFirst).assign(ConfigValue.Bool(host.moneyLabelFirst))
        host.consoleStyle?.let { style ->
            store.itemOf(ConfigKey.FontName).assign(ConfigValue.StringValue(style.fontName))
            store.itemOf(ConfigKey.ForeColor).assign(colorOf(style.defFore))
            store.itemOf(ConfigKey.BackColor).assign(colorOf(style.defBack))
            store.itemOf(ConfigKey.FocusColor).assign(colorOf(style.focus))
        }
    }

    /** `ConfigItem<Color>`：三个 0..255 分量（spec: `ConfigItem.cs:252-274`）。 */
    private fun colorOf(rgb: IntArray): ConfigValue.ColorValue = ConfigValue.ColorValue(rgb[0], rgb[1], rgb[2])

    // ------------------------------------------------------------------
    // GetConfigValueInERB（ConfigData.cs:350-422）
    // ------------------------------------------------------------------

    /**
     * 一次读数，返回 `SingleTerm` 的等价物（[Value.IntV] ⇔ `SingleLongTerm`、
     * [Value.StrV] ⇔ `SingleStrTerm`）。两个函数形共用它，形状不匹配由调用方报错
     * —— 这正是原版 `GetIntValue` / `GetStrValue` 的分工（spec: `:6057-6073`）。
     *
     * @throws CodeEE 未知键（[LangError.invalidConfigName]）或未定义 ConfigCode
     *   （[LangError.notAllowGetConfigValue]，真实条目表下不可达，见 [valueInErb] 的 else 分支注释）
     */
    fun valueInErb(store: ConfigStore, name: String): Value {
        val item = store.resolveAny(name) // :352 Instance.GetItem(text)
            ?: throw CodeEE(LangError.invalidConfigName(name)) // :353-356
        return when (item.spec.key) {
            // :357-364 —— 这两个 bool 条目有专属分支，返回 1/0（其余 bool 走 else，结果相同）
            ConfigKey.AutoSave, ConfigKey.MoneyFirst -> Value.IntV(if (item.bool()) 1L else 0L)

            // :365-374 —— 八个 int 条目；注意 `Config.GetValue<int>` 的强转口径，类型写错即 ClassCastException
            ConfigKey.WindowX, ConfigKey.PrintCPerLine, ConfigKey.PrintCLength, ConfigKey.FontSize,
            ConfigKey.LineHeight, ConfigKey.SaveDataNos, ConfigKey.MaxShopItem, ConfigKey.ComAbleDefault,
            -> Value.IntV(item.int().toLong())

            // :375-382 —— 四个 Color 条目编码成 (R*256 + G)*256 + B
            ConfigKey.ForeColor, ConfigKey.BackColor, ConfigKey.FocusColor, ConfigKey.LogColor -> {
                val rgb = item.color()
                Value.IntV((rgb.r * 256L + rgb.g) * 256L + rgb.b)
            }

            // :383-386 —— 两个 long 条目
            ConfigKey.pbandDef, ConfigKey.RelationDef -> Value.IntV(item.long())

            // :387-396 —— 七个 string 条目
            ConfigKey.FontName, ConfigKey.MoneyLabel, ConfigKey.LoadLabel, ConfigKey.DrawLineString,
            ConfigKey.TitleMenuString0, ConfigKey.TitleMenuString1, ConfigKey.TimeupLabel,
            -> Value.StrV(item.string())

            // :397-400 —— 两个 char 条目转成单字符串
            ConfigKey.BarChar1, ConfigKey.BarChar2 -> Value.StrV(item.char().toString())

            // :401-403 —— TextDrawingMode 的专属分支：返回枚举常量名
            ConfigKey.TextDrawingMode -> Value.StrV(item.enumConstant())

            // :404-421 —— 其余全部条目
            else -> {
                // :401 `Enum.IsDefined(typeof(ConfigCode), item.Code)`：本文件的键都来自
                // [ConfigStore]，而该表的键与 `ConfigCode` 逐成员镜像（spec: `ConfigCode.cs:3-129`），
                // 所以判定恒真、`NotAllowGetConfigValue` 恒不触发 —— 守卫仍写出来，
                // 将来若镜像表里多出未注册的 code 就立刻暴露，而不是静默按值返回。
                if (ConfigKey.fromCode(item.spec.key.code) == null) {
                    throw CodeEE(LangError.notAllowGetConfigValue(name)) // :419-420
                }
                val text = valueToStringForErb(item) // :405 item.ValueToString()
                when {
                    text == "YES" -> Value.IntV(1L) // :406-412（原版的嵌套 if 先判 YES）
                    text == "NO" -> Value.IntV(0L) // :413-416
                    else -> {
                        val s = valueToStringForErb(item) // :417 再取一次（原版就是调了两遍）
                        parseLongStrictly(s)?.let { Value.IntV(it) } ?: Value.StrV(s) // :418-420
                    }
                }
            }
        }
    }

    /**
     * `int` 形：`GetConfigMethod.GetIntValue`（spec: `:6057-6064`）。
     * 读数不是 `SingleLongTerm` ⇒ `InvalidType(GETCONFIG, GETCONFIGS)`（`:6063`）。
     */
    fun getConfigValue(c: BuiltinContext, name: String): Long =
        when (val v = valueInErb(store(c), name)) {
            is Value.IntV -> v.v
            is Value.StrV -> throw CodeEE(LangError.invalidType(GETCONFIG, GETCONFIGS))
        }

    /** `string` 形：`GetConfigMethod.GetStrValue`（spec: `:6066-6073`）；镜像方向报对称的文案（`:6072`）。 */
    fun getConfigsValue(c: BuiltinContext, name: String): String =
        when (val v = valueInErb(store(c), name)) {
            is Value.StrV -> v.v
            is Value.IntV -> throw CodeEE(LangError.invalidType(GETCONFIGS, GETCONFIG))
        }

    /**
     * `GetSingleTerm`（spec: `:6041-6055`）的共用前段：实参必须是**非空字符串**，
     * 否则 `ArgIsEmptyString(fn, 1)`（`:6046`）。
     *
     * 参数类型本身（`typeof(string)`）在原版由解析期的 `CheckArgumentType` 拦下
     * （spec: `:6037` 的 `argumentTypeArray`）；移植侧的桥只检查个数（`BuiltinMethod.checkArity`），
     * 所以这里保留一道运行期守卫，文案沿用工程既有口径（与 `core/fn/DebugMiscMethods.kt:487`
     * 同一条串），以免同一件事出现两种写法。
     * TODO(verify): 原版解析期类型错误的 `Lang.Error` 原文与抛出时机
     * （接线 `core/code` / `ExpressionMediator` 的实参类型检查后才能定死，那两处不在本路文件面内）。
     */
    fun singleTerm(c: BuiltinContext, args: List<Value?>, fn: String): Value {
        val text = (args.getOrNull(0) as? Value.StrV)?.v
            ?: throw CodeEE("函数 $fn 的第 1 个参数必须是字符串")
        if (text.isEmpty()) throw CodeEE(LangError.argIsEmptyString(fn, 1)) // :6045-6047
        return valueInErb(store(c), text) // :6048-6053（errMes != null 时 valueInErb 已抛）
    }

    private fun valueToStringForErb(item: ConfigItem): String =
        if (item.value is ConfigValue.LongList) NET_LONG_LIST_TYPE_NAME // ConfigItem.cs:81
        else EmueraConfigWriter.valueToString(item) // ConfigItem.cs:53-80
}

/**
 * `Lang.Error` 四条文案的原文与格式化参数（日文原文取自
 * `MinorShift.Emuera.Runtime.Utils.EvilMask/Lang.cs`，逐条行号见各成员）。
 *
 * `CodeEE` 的 message 在本工程里保持原版 `Lang.Error` 文案（见 `core/err/CodeEE.kt` 的类 KDoc），
 * 因此这里不做任何本地化改写；`{0}`/`{1}` 按 C# `string.Format` 的实参顺序手工代入。
 */
object LangError {

    /** `Lang.cs:3902` = `{0}関数: 第{1}引数が空文字列です`；调用点 `FunctionMethodCreator.cs:6046`。 */
    fun argIsEmptyString(functionName: String, argumentIndex: Int): String =
        "${functionName}関数: 第${argumentIndex}引数が空文字列です"

    /** `Lang.cs:1742` = `文字列"{0}"は適切なコンフィグ名ではありません`；调用点 `ConfigData.cs:355`。 */
    fun invalidConfigName(configName: String): String =
        "文字列\"$configName\"は適切なコンフィグ名ではありません"

    /** `Lang.cs:1746` = `コンフィグ文字列"{0}"の値の取得は許可されていません`；调用点 `ConfigData.cs:419`。 */
    fun notAllowGetConfigValue(configName: String): String =
        "コンフィグ文字列\"$configName\"の値の取得は許可されていません"

    /** `Lang.cs:3914` = `{0}関数: 型が違います ({1}関数を使用してください)`；调用点 `FunctionMethodCreator.cs:6063`、`:6072`。 */
    fun invalidType(functionName: String, suggestedFunctionName: String): String =
        "${functionName}関数: 型が違います (${suggestedFunctionName}関数を使用してください)"
}

/**
 * 注册两个函数名 —— 已在 `core/fn/Builtins.kt` 的 `buildRegistry()` 收口处调用
 * （M4-J j-dispatch 接线）。
 *
 * 与切换前 `DebugMiscMethods` 的同名注册**互斥**（`BuiltinRegistry.register` 撞真实现即
 * error，Builtins.kt:594-601）：那一份自带私有条目表（曾有 `CONFIG_ROWS`，125 行手抄）
 * 与中文改写文案。本路裁定的归属：
 * - 条目表 → 唯一真相在 `core/config`（[com.eratw.port.core.config.ConfigSpecTable]，
 *   123 条逐行带 `ConfigData.cs` 行号）；`DebugMiscMethods` 只为 GETLINESTR 读
 *   WindowX / FontSize，也改吃这份表；
 * - 文案 → 保持 `Lang.cs` **日文原文**（[LangError]）。理由：`CodeEE` 的 message 是内核/契约层
 *   报错，按工程口径只有**使用者面**的提示才中文化；且 `DebugMiscMethods` 的类头 KDoc
 *   自己就要求「保持原版文案」。因此 `ConfigTypoLookupTest` 里按中文子串（「不是合适的配置名」
 *   「类型不对」）判「查得到/查不到」的断言需要重钉 —— 写进交付报告「需组长接线」。
 */
internal fun registerConfigMethods(r: BuiltinRegistry) {
    // spec: FunctionMethodCreator.cs:9148（GETCONFIG，ReturnType=long，1 个 string 实参）
    r.register(
        BuiltinMethod(ConfigMethods.GETCONFIG, OperandType.INT, 1, 1) { c, a ->
            when (val v = ConfigMethods.singleTerm(c, a, ConfigMethods.GETCONFIG)) {
                is Value.IntV -> v
                is Value.StrV -> throw CodeEE(LangError.invalidType(ConfigMethods.GETCONFIG, ConfigMethods.GETCONFIGS))
            }
        },
    )
    // spec: FunctionMethodCreator.cs:9149（GETCONFIGS，ReturnType=string，实参形状同上）
    r.register(
        BuiltinMethod(ConfigMethods.GETCONFIGS, OperandType.STR, 1, 1) { c, a ->
            when (val v = ConfigMethods.singleTerm(c, a, ConfigMethods.GETCONFIGS)) {
                is Value.StrV -> v
                is Value.IntV -> throw CodeEE(LangError.invalidType(ConfigMethods.GETCONFIGS, ConfigMethods.GETCONFIG))
            }
        },
    )
}
