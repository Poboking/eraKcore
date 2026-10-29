package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef

/**
 * 符号 / 宏 / 变量枚举与"当前函数"查询（[registerSymbolQueryMethods]）。
 * **文件所有者：A5**（M3-B 第二波）。
 *
 * 硬判据：`fixtures/golden/baseline/pc-golden-root.txt:622-633`（SECTION:FUNC-ENUM）、
 * `:542-546`（SECTION:ENUMVAR）、`:540`（ISDEFINED，GOLDEN_10_XMLMAP.ERB:42）。
 * 调用序列：`fixtures/golden/GOLDEN_10_XMLMAP.ERB:45-48`（ENUM*）、`:173-185`（FUNC-ENUM）。
 *
 * 权威实现（`reference/emuera-cs/.../FunctionMethodCreator.cs`）与证据行号：
 * - ISDEFINED          :218-230 IsDefinedMethod → IdentifierDictionary.GetMacro :419-427
 *                       （macroDic 是 #DEFINE 宏表，MacroKeys = macroDic.Values.Keyword，
 *                       IdentifierDictionary.cs:66,70）；
 * - EnumNameMethod     :233-328，注册表 :9210-9216（ENUMVAR* / ENUMMACRO*）与
 *                       :9274-9278 附近（ENUMFUNC*，同族 9 个入口共用一个类）；
 * - EXISTFUNCTION      :8222-8304 ExistFunctionMethod；
 * - GETDOINGFUNCTION   :8441-8459 GetDoingFunctionMethod；
 * - GETMETH            :8533-8572 GetMethMethod；
 * - GETMETHS           :8575-8614 GetMethsMethod；
 * - EXISTMETH          :8617-8653 ExistMethMethod。
 *
 * 数据源（对应原版 IdentifierDictionary 三张表与 Process.LabelDictionary）：
 * - 变量名（VarKeys，IdentifierDictionary.cs:68）→ 宿主 [BuiltinHost.variableNames]；
 *   null 回落 `VariableStore.registry.all() + store.allUserVars()`（含文件级 #DIM
 *   全局变量，对应 AddUseDefinedVariable，IdentifierDictionary.cs:394-399）。
 * - 用户函数（LabelDictionary.NoneventKeys）→ 宿主 [BuiltinHost.userFunctions]
 *   （int 方法 #FUNCTION=2 / string 方法 #FUNCTIONS=3 / 普通 @label=1 的区分见
 *   FunctionMethodCreator.cs:8269-8280）。
 * - 宏（MacroKeys）→ 宿主 [BuiltinHost.macroNames]。
 * - GETDOINGFUNCTION → 宿主 [BuiltinHost.doingFunction]。
 *
 * ENUM* 九入口共用 EnumNameMethod 语义（:273-327），逐条照抄：
 * 1. 查询串与候选名都 ToUpper(InvariantCulture) 后比较（:275 与 :302/:308/:314）；
 * 2. 比查询串短的候选直接跳过（:295-298）；
 * 3. 命中名单按**源表顺序**保留（不做排序）；
 * 4. 写目标：给了第 2 参（RefString1D）就写那条数组的**整条**（书写时带的显式下标
 *    被忽略，:322），否则写 RESULTS（`VEvaluator.RESULTS_ARRAY` = DataStringArray[2]，
 *    VariableEvaluator.cs:81）；
 * 5. 返回值 = `Math.Min(目标数组长度, 命中数)`（:324-326）——不是命中数本身；
 * 6. 查询串为空串 → 0 命中（:290-291）。
 */
internal fun registerSymbolQueryMethods(r: BuiltinRegistry) {
    val I = OperandType.INT
    val S = OperandType.STR

    // ---- ISDEFINED(name) ---- :218-230
    r.register(BuiltinMethod("ISDEFINED", I, 1, 1) { c, a ->
        val name = sv(a[0])
        val macros = c.host.macroNames
        // 原版 GetMacro 用 Config.IgnoreCase（默认 true，Config.cs:299-303）算 hash，
        // 即大小写不敏感；宿主给的表按此口径比较。
        Value.IntV(if (macros != null && macros.any { it.equals(name, ignoreCase = true) }) 1L else 0L)
    })

    // ---- EXISTFUNCTION(name[, mode]) ---- :8222-8304
    // mode 省略或为 0：直接查用户函数表；非 0：线性扫 NoneventKeys 比较（:8282-8302）。
    // 两条路径判定等价（都是大小写不敏感地找同一个用户函数），故同一实现。
    r.register(BuiltinMethod("EXISTFUNCTION", I, 1, 2) { c, a ->
        val name = sv(a[0])
        val kind = c.host.userFunctions?.get(name.uppercase())
        Value.IntV(if (kind == null) 0L else existFunctionBits(kind))
    })

    // ---- EXISTMETH(name) ---- :8617-8653
    // 只认用户 #FUNCTION/#FUNCTIONS；普通 @label 在 GetFunctionMethod 处抛
    // CallfNonMethodFunc（IdentifierDictionary.cs:557-559），被 :8630-8637 捕获成 0。
    r.register(BuiltinMethod("EXISTMETH", I, 1, 1) { c, a ->
        val name = sv(a[0])
        val kind = c.host.userFunctions?.get(name.uppercase())
        Value.IntV(
            when (kind) {
                UserFunctionKind.INT_METHOD -> 1L
                UserFunctionKind.STRING_METHOD -> 2L
                else -> 0L
            },
        )
    })

    // ---- GETMETH(name[, def], args...) ---- :8533-8572
    // 第 2 参起是**转传给用户 #FUNCTION 的实参**（arguments.Skip(2)，:8557）。
    //
    // 求值顺序照 :8553-8571：先求名字 → 查函数（GetFunctionMethod 只绑定表达式，不求值）→
    // 找不到才求默认值 `arguments[1]`（:8561-8565）、类型不符先抛（:8566-8569）→ 最后才把
    // 第 3 参起交给函数体（:8571）。所以 evalRef 路径把 def / callArgs 做成惰性求值：
    // 函数存在时默认值**不**求值，未定义时转发实参**不**求值（副作用不会多跑）。
    // eval 路径（测试/直接值调用）仍按已求值的实参工作，读数相同。
    r.register(BuiltinMethod("GETMETH", I, 1, -1, evalRef = { c, e ->
        getMethRef(c, "GETMETH", true, e.strAt(c, 0), { e.getOrNull(1)?.toValue(c) }) {
            e.drop(2).map { it?.toValue(c) }
        }
    }) { c, a ->
        getMethRef(c, "GETMETH", true, sv(a[0]), { a.getOrNull(1) }) { a.drop(2) }
    })

    // ---- GETMETHS(name[, def], args...) ---- :8575-8614（与 GETMETH 同构的字符串通道）
    r.register(BuiltinMethod("GETMETHS", S, 1, -1, evalRef = { c, e ->
        getMethRef(c, "GETMETHS", false, e.strAt(c, 0), { e.getOrNull(1)?.toValue(c) }) {
            e.drop(2).map { it?.toValue(c) }
        }
    }) { c, a ->
        getMethRef(c, "GETMETHS", false, sv(a[0]), { a.getOrNull(1) }) { a.drop(2) }
    })

    // ---- GETDOINGFUNCTION() ---- :8441-8459
    // 原版取"正在扫描的逻辑行"的父标签名；无扫描行（不在函数内）→ ""
    // （:8453-8456）。宿主未接线时同样按 "" 处理（KDoc 见 BuiltinHost.doingFunction）。
    r.register(BuiltinMethod("GETDOINGFUNCTION", S, 0, 0) { c, _ ->
        Value.StrV(c.host.doingFunction ?: "")
    })

    // ---- ENUM* 九入口 ---- :233-328 + 注册表 :9210-9216, :9274-9278
    registerEnumName(r, "ENUMVARBEGINSWITH", EnumKind.VARIABLE, EnumAction.BEGINS_WITH)
    registerEnumName(r, "ENUMVARENDSWITH", EnumKind.VARIABLE, EnumAction.ENDS_WITH)
    registerEnumName(r, "ENUMVARWITH", EnumKind.VARIABLE, EnumAction.WITH)
    registerEnumName(r, "ENUMMACROBEGINSWITH", EnumKind.MACRO, EnumAction.BEGINS_WITH)
    registerEnumName(r, "ENUMMACROENDSWITH", EnumKind.MACRO, EnumAction.ENDS_WITH)
    registerEnumName(r, "ENUMMACROWITH", EnumKind.MACRO, EnumAction.WITH)
    registerEnumName(r, "ENUMFUNCBEGINSWITH", EnumKind.FUNCTION, EnumAction.BEGINS_WITH)
    registerEnumName(r, "ENUMFUNCENDSWITH", EnumKind.FUNCTION, EnumAction.ENDS_WITH)
    registerEnumName(r, "ENUMFUNCWITH", EnumKind.FUNCTION, EnumAction.WITH)
}

// ===========================================================================
// ENUM* 家族（EnumNameMethod，FunctionMethodCreator.cs:233-328）
// ===========================================================================

/** EnumNameMethod.EType（:235-240）：查哪张表。 */
private enum class EnumKind { FUNCTION, VARIABLE, MACRO }

/** EnumNameMethod.EAction（:242-247）：怎么比。 */
private enum class EnumAction { BEGINS_WITH, ENDS_WITH, WITH }

private fun registerEnumName(r: BuiltinRegistry, name: String, kind: EnumKind, action: EnumAction) {
    val I = OperandType.INT
    r.register(
        BuiltinMethod(name, I, 1, 2, evalRef = { c, e ->
            val prefix = e.strAt(c, 0)
            val target = if (e.size >= 2) {
                // 第 2 参是 RefString1D（:322）：必须是变量引用，非引用按原版解析期口径报错
                (e[1] as? VarRef)?.name
                    ?: throw CodeEE("函数 $name 的第 2 个参数必须是字符串数组变量引用")
            } else {
                "RESULTS"
            }
            Value.IntV(writeEnumMatches(c, name, target, matchEnumNames(c, kind, prefix, action)))
        }) { c, a ->
            // 值路径只覆盖 1 参形态：原版第 2 参恒为引用参数，值路径不可达。
            if (a.size > 1) throw CodeEE("函数 $name 的第 2 个参数必须是变量引用")
            Value.IntV(
                writeEnumMatches(
                    c,
                    name,
                    "RESULTS",
                    matchEnumNames(c, kind, sv(a[0]), action),
                ),
            )
        },
    )
}

/** 取参助手（本文件私有；VarMethods/PureMethods 的同名助手可见性不动）。 */
private fun sv(v: Value?): String =
    (v as? Value.StrV)?.v ?: throw CodeEE("函数参数类型错误：需要字符串")

private fun List<Expr?>.strAt(c: BuiltinContext, i: Int): String = this[i]?.getStrValue(c) ?: ""
private fun existFunctionBits(kind: UserFunctionKind): Long = when (kind) {
    UserFunctionKind.INT_METHOD -> 2L
    UserFunctionKind.STRING_METHOD -> 3L
    UserFunctionKind.PLAIN -> 1L
}

/**
 * `GetMethMethod` / `GetMethsMethod` 的共用体（:8553-8571 / :8595-8613）。
 *
 * [def] / [callArgs] 是**惰性**求值器，调用时机严格照 C# 函数体：
 * 1. 先查 [BuiltinHost.userFunctions]（原版 `GetFunctionMethod`，:8555/:8597）；
 * 2. 未定义 ⇒ 只求 [def]（:8561-8565），没有默认值才抛 NotDefinedUserFunc；
 * 3. 普通 @label / 返回类型不符 ⇒ 直接抛，两个求值器都不跑（:8566-8569）；
 * 4. 类型对 ⇒ 才求 [callArgs] 并转给宿主执行（:8571/:8613）。
 *
 * @param wantInt GETMETH=true（要求 `#FUNCTION`）；GETMETHS=false（要求 `#FUNCTIONS`）
 */
private fun getMethRef(
    c: BuiltinContext,
    fn: String,
    wantInt: Boolean,
    name: String,
    def: () -> Value?,
    callArgs: () -> List<Value?>,
): Value {
    val kind = c.host.userFunctions?.get(name.uppercase())
    if (kind == null) {
        return def() ?: throw CodeEE("函数 $fn：用户函数 \"$name\" 未定义（且未给默认值）")
    }
    return when (kind) {
        // 普通 @label 不是用户方法 → CallfNonMethodFunc，GetMethMethod 不捕获（:564-565）
        UserFunctionKind.PLAIN ->
            throw CodeEE(
                "函数 $fn：\"$name\" 不是 " +
                    if (wantInt) "#FUNCTION 定义的用户方法" else "#FUNCTIONS 定义的用户方法",
            )
        UserFunctionKind.STRING_METHOD -> {
            if (wantInt) throw CodeEE("函数 $fn：\"$name\" 是字符串方法（#FUNCTIONS），结果不是数值")
            Value.StrV(callUserFunctionStr(c, name, callArgs(), fn))
        }
        UserFunctionKind.INT_METHOD -> {
            if (!wantInt) throw CodeEE("函数 $fn：\"$name\" 是数值方法（#FUNCTION），结果不是字符串")
            Value.IntV(callUserFunctionInt(c, name, callArgs(), fn))
        }
    }
}

/**
 * 经宿主调用用户函数（GETMETH/GETMETHS 的执行通道）。
 *
 * 对应原版 `IdentifierDictionary.GetFunctionMethod` 绑定实参后
 * `functionMethod.GetIntValue(exm)`（:8571）——用户 #FUNCTION 的函数体执行
 * 在 ERB 解释器侧，core 层只负责"找到用户函数并调用"。
 */
private fun callUserFunctionInt(c: BuiltinContext, name: String, args: List<Value?>, fn: String): Long {
    val invoke = c.host.invokeUserFunction
        ?: throw CodeEE("函数 $fn：执行用户函数 \"$name\" 需要宿主接线 invokeUserFunction")
    val r = invoke(name, args)
        ?: throw CodeEE("函数 $fn：用户函数 \"$name\" 未定义或调用失败")
    return (r as? Value.IntV)?.v ?: throw CodeEE("函数 $fn：用户函数 \"$name\" 返回了非数值结果")
}

private fun callUserFunctionStr(c: BuiltinContext, name: String, args: List<Value?>, fn: String): String {
    val invoke = c.host.invokeUserFunction
        ?: throw CodeEE("函数 $fn：执行用户函数 \"$name\" 需要宿主接线 invokeUserFunction")
    val r = invoke(name, args)
        ?: throw CodeEE("函数 $fn：用户函数 \"$name\" 未定义或调用失败")
    return (r as? Value.StrV)?.v ?: throw CodeEE("函数 $fn：用户函数 \"$name\" 返回了非字符串结果")
}

/**
 * 候选名表（EnumNameMethod 的 `array`，:276-288）。
 *
 * - VARIABLE：原版 IdentifierDictionary.VarKeys（:283）——varTokenDic.Keys（:68），
 *   即系统变量全集（VariableData 构造期克隆注入，IdentifierDictionary.cs:164）+
 *   游戏 ERH 顶层 #DIM/#DIMS 用户变量（ErhLoader.cs:261-268 逐条
 *   AddUseDefinedVariable，IdentifierDictionary.cs:394-399；CHARADATA 同路径）。
 *   ERB 内 #DIM/#DIMS 一律 isPrivate:true（LogicalLineParser.cs:282-283）只进函数
 *   私有表，不在 VarKeys。
 *   宿主 [BuiltinHost.variableNames] 给全量权威表（E2E 装置已接线，见
 *   GoldenPipeline.buildEnumVarNameTable）；null 回落变量池注册表全名
 *   + 全局用户变量名（store.allUserVars，对应 AddUseDefinedVariable）。
 * - MACRO：原版 MacroKeys（:286）。宿主 [BuiltinHost.macroNames]；null = 无宏。
 * - FUNCTION：原版 LabelDictionary.NoneventKeys（:280）。宿主
 *   [BuiltinHost.userFunctions]；null 回落到**内建函数名**（Builtins.registry.names()），
 *   这是契约认可的"内建优先"近似：枚举内建函数名而非用户标签名。
 */
private fun enumKeyList(c: BuiltinContext, kind: EnumKind): Collection<String> = when (kind) {
    EnumKind.FUNCTION -> c.host.userFunctions?.keys ?: Builtins.registry.names()
    EnumKind.MACRO -> c.host.macroNames ?: emptyList()
    EnumKind.VARIABLE -> c.host.variableNames ?: buildStoreVariableNames(c)
}

private fun buildStoreVariableNames(c: BuiltinContext): List<String> {
    val names = ArrayList<String>()
    for (def in c.store.registry.all()) names.add(def.name)
    for (v in c.store.allUserVars()) names.add(v.def.name)
    return names
}

/**
 * 前缀匹配（EnumNameMethod :290-321）。
 *
 * 口径照抄：查询串先 ToUpper；空查询串直接 0 命中；比查询串短的候选跳过；
 * 命中按源表顺序。.NET 的 IndexOf/LastIndexOf(Ordinal) 与 Kotlin 的
 * startsWith/endsWith/contains 在 ASCII 变量名域等价。
 */
private fun matchEnumNames(
    c: BuiltinContext,
    kind: EnumKind,
    prefix: String,
    action: EnumAction,
): List<String> {
    val q = prefix.uppercase()
    if (q.isEmpty()) return emptyList()
    val out = ArrayList<String>()
    for (k in enumKeyList(c, kind)) {
        if (k.length < q.length) continue
        val ku = k.uppercase()
        val hit = when (action) {
            EnumAction.BEGINS_WITH -> ku.startsWith(q)
            EnumAction.ENDS_WITH -> ku.endsWith(q)
            EnumAction.WITH -> ku.contains(q)
        }
        if (hit) out.add(k)
    }
    return out
}

/**
 * 写命中名单并返回 `Math.Min(目标数组长度, 命中数)`（:322-326）。
 *
 * 目标数组按"整条 1 维字符串数组"处理（书写时的显式下标被忽略，同
 * ArrArg 口径）；超出目标容量的命中丢弃。
 */
private fun writeEnumMatches(
    c: BuiltinContext,
    fn: String,
    target: String,
    matches: List<String>,
): Long {
    val len = try {
        c.store.getLength(target, -1)
    } catch (t: CodeEE) {
        throw CodeEE("函数 $fn 的目标数组 \"$target\" 不是可写的字符串数组：${t.message}")
    }
    val n = minOf(len, matches.size)
    for (i in 0 until n) c.store.writeStr(target, longArrayOf(i.toLong()), matches[i])
    return n.toLong()
}
