package com.eratw.port.core.compile

/**
 * 变量名 → 槽 的布局接口（编译期把 `A`、`A:1` 的**变量名**解析为帧槽/全局槽操作数）。
 *
 * ## 扩展点（等 dimSpecs 落地后接入）
 *
 * 派单要求：局部布局依赖 erbcc 正在加的 dimSpecs（#DIM/#DIMS 维度信息）。正式布局实现
 * 只需实现本接口——按 (变量名, 类型) 返回 [com.eratw.port.core.code.SlotAddr.local] 或
 * [com.eratw.port.core.code.SlotAddr.global] 编码的槽操作数即可，编译器不做任何假设：
 *
 * ```
 * class DimSpecLayout(private val specs: DimSpecTable) : LocalLayout {
 *     override fun slotOf(name: String, isString: Boolean): Int? =
 *         specs.resolve(name, isString)?.let { SlotAddr.local(it) }
 * }
 * ```
 *
 * 布局对象必须**跨函数共享且结果稳定**（同一变量名在整个 Program 内映射到同一槽），
 * 编译器不会为每个函数重置布局。
 *
 * 已知限制（占位阶段的取舍，见 [GlobalNameLayout]）：
 * - [com.eratw.port.core.expr.VarRef.indices]（`A:1` 的下标）**不参与槽编码**——下标寻址
 *   属 M2 变量层（列式变量池），占位阶段一律落到该变量名的单一槽上。
 * - TODO(verify): dimSpecs 落地后，同名的整型/字符串声明（#DIM vs #DIMS）需按 isString 分桶。
 */
interface LocalLayout {

    /**
     * @param name 变量名（原样书写形式；实现自行归一大小写）
     * @param isString true = 字符串语境（#DIMS/RESULTS 系）
     * @return 槽操作数（[com.eratw.port.core.code.SlotAddr] 编码）；null = 无法布局（编译报错）
     */
    fun slotOf(name: String, isString: Boolean): Int?

    /**
     * 该函数（大写键）的 **REF 形参变量名**集合；null = 本布局不提供 REF 信息（旧行为）。
     *
     * 语义依据：原版 REF 形参在 `#DIM REF` 声明时变成 `ReferenceToken`
     * （spec: UserDefinedVariableData.cs:44-70；`Identifier.IsReference` 判据见
     * CalledFunction.cs:154），调用点对这类位**只取** `VariableTerm.Identifier.GetArray()`、
     * 不读值（spec: CalledFunction.cs:154-159 → ReferenceToken.SetRef:125-128）。
     * 本移植的存储是值槽/列式池，编译器必须在 `_EVAL_*` 之前知道「这个位不用求值」，
     * 否则裸多维数组实参（`CALL F(MAT)`，MAT 为 2D）会在 ReadInt 的
     * `checkElement` 上抛「需要 2 个下标（实际 0）」，根本走不到 REF 绑定。
     *
     * [DimSpecLayout] 用 `funcDims`（erbcc `LabelDef.dimSpecs` → [LocalDim.ref]）实现本方法；
     * `GlobalNameLayout` 不持声明，返回 null ⇒ 编译器退回旧行为（只做值求值）。
     * **REF 位序以头部形参为准**（`FuncSig.paramNames`），不是 `#DIM` 声明序
     * （语料 63/539 例两者不同，见 Program.FunctionInfo.paramNames KDoc），
     * 故本方法只给名字，位序由编译器用被调签名映射。
     */
    fun refParamNamesOf(functionKey: String): Set<String>? = null

    /**
     * 编译器回调：@函数 编译开始（在实参暂存区探测之后、正式发射之前）。
     *
     * @param key 函数大写键（compileFunction 的 sig.name 归一）
     * @param firstLocalSlot 本函数局部槽可用起点（= 实参暂存区 R0..R(maxArgc-1) 之上；
     *   局部槽从这里向上分配，编译器会把临时槽基址抬到「起点 + 返回值」之上避免互撞）
     * @return 本函数局部槽占用数（0 = 无局部布局；编译器据此抬临时槽基址）
     */
    fun beginFunction(key: String, firstLocalSlot: Int): Int = 0

    /** 编译器回调：@函数 编译结束（布局可清掉函数内状态）。 */
    fun endFunction() {}
}

/**
 * 函数私有变量声明 —— erbcc `DimSpec`（specs/loader.md 附录 D.8）求值/分类后的映射产物。
 * core-vm 不依赖 erbcc 类型，由调用方（erbcc / 宿主）完成：
 *
 * - dims 原文 → [DimSizeEvaluator] 求值（装载期可折叠函数经同源回调注入，见 [DimSizeEvaluator]
 *   的类文档；求值结果进 [LocalDim.slots]）
 * - 无 GLOBAL/SAVEDATA/CHARADATA 的 CONST/DYNAMIC/STATIC → 私有局部（进 [DimSpecLayout] 的函数局部表）
 * - GLOBAL/SAVEDATA/CHARADATA → 全局侧（进全局声明，走全局布局；不要放进局部表）
 * - REF → [LocalDim.ref] = true（绑定槽，不占帧槽数组；绑定语义 M2 变量层）
 */
data class LocalDim(
    val name: String,
    /** 声明来自 #DIMS（字符串侧）。只作诊断：布局表按名字单键，见 [DimSpecLayout] 的类文档。 */
    val isStr: Boolean,
    /**
     * 求值后的**总元素槽数** = 各维长度的乘积（无 dims = 1）。CMP-40 之前恒 1，
     * 与侧车/运行期注册的 `dims` 分叉（`#DIM X, VARSIZE("RELATION")` 三方分别是
     * 「缺 / 200 / 200」）；现在由 erbcc 桥 `toLocalDim` 按同一份折叠结果填。
     *
     * 大于 1 时基名 = 第 0 个元素槽，`NAME:i` 是逐元素槽位（供 M2 接入下标寻址时查）；
     * 基名**恒登记**，否则 [DimSpecLayout.slotOf] 查不到该变量、会把它委托成全局槽。
     */
    val slots: Int = 1,
    val ref: Boolean = false,
)

/**
 * **局部布局**：函数私有 #DIM/#DIMS → 帧内局部槽；其余变量委托全局布局。
 *
 * - 每函数槽区 = [firstLocalSlot, firstLocalSlot + beginFunction 返回值)，编译器保证临时槽
 *   基址在此之上 —— 局部变量与实参暂存区/临时槽三者互不重叠。
 * - 表**按变量名单键**（大写归一），与原版一致：`FunctionLabelLine.cs:17` 的
 *   `Dictionary<string, UserDefinedVariableToken>(Config.StrComper)` 只有名字这一个键，
 *   整型/字符串不作为键的一部分。所以同名的第二条声明（无论 #DIM 还是 #DIMS）
 *   照 `:100-103` 的 `ContainsKey → return false` **整条丢弃、不占槽**（先到先占）。
 *   [LocalDim.isStr] 因此只作诊断，不参与键。
 * - 局部表未命中一律委托全局布局（未声明变量的占位行为与 [GlobalNameLayout] 一致）。
 */
class DimSpecLayout(
    private val global: LocalLayout,
    /** 函数大写键 → 该函数私有声明（erbcc 按 resolve()/eventBuckets() 胜者喂入）。 */
    private val funcDims: Map<String, List<LocalDim>>,
) : LocalLayout {

    private val curSlots = HashMap<String, Int>()
    private var nextLocal = 0

    override fun beginFunction(key: String, firstLocalSlot: Int): Int {
        curSlots.clear()
        nextLocal = firstLocalSlot
        for (d in funcDims[key].orEmpty()) {
            if (d.ref) continue                     // REF：绑定槽，不占帧槽数组（M2）
            val base = d.name.uppercase()
            // 重名声明**先到先占**，后到的整条丢弃、连槽位都不占：原版私有变量表按名字
            // 单键（忽略大小写）登记 —— `FunctionLabelLine.cs:17`
            // `Dictionary<string, UserDefinedVariableToken>(Config.StrComper)`，
            // `AddPrivateVariable` 在 `:100-103` 先 `ContainsKey(data.Name)` → `return false`，
            // 变量对象根本不创建（`:104` 的 CreatePrivateVariable 在其后）。
            // 旧实现直接 `curSlots[base] = nextLocal++` 覆盖，于是同名第二条声明既白占一格
            // 帧槽、又把绑定挪到了后一条声明上（与原版相反）。
            if (curSlots.containsKey(base)) continue
            val slots = maxOf(d.slots, 1)
            // 基名恒登记（= 第 0 个元素槽）：`LocalLayout` 的占位口径是「VarRef.indices
            // 不参与槽编码，一律落到该变量名的单一槽上」（本文件顶部 已知限制 第 1 条）。
            // 旧写法在 slots > 1 时只登记 `NAME:i` 形态、基名不入表，`slotOf` 两级键都查不中
            // ⇒ 该私有变量被当成未声明、委托全局布局分一个**全局槽**，私有/全局穿帮。
            curSlots[base] = nextLocal++
            for (i in 1 until slots) curSlots["$base:$i"] = nextLocal++
        }
        return nextLocal - firstLocalSlot
    }

    override fun endFunction() {
        curSlots.clear()
    }

    override fun slotOf(name: String, isString: Boolean): Int? {
        val base = name.uppercase() + if (isString) "$" else ""
        // 先查带类型后缀的键，再退回不带后缀的原名（`#DIMS A` 这类声明名里没有 `$`，
        // 而使用点写 `A$`；反向拼写同样退回命中）。键本身只按名字，见 beginFunction 的原版口径。
        curSlots[base]?.let { return com.eratw.port.core.code.SlotAddr.local(it) }
        curSlots[name.uppercase()]?.let { return com.eratw.port.core.code.SlotAddr.local(it) }
        return global.slotOf(name, isString)
    }

    /**
     * REF 形参名集合（大写）。`funcDims` 的键可能是 `KEY`（非事件）或 `KEY#defIndex`
     * （事件成员/重名逐定义）；静态 `_CALL` 的目标只有 `KEY`，故只做精确键查询 ——
     * 不拿 `KEY#…` 去合并（重名时可能是被遮蔽定义的 REF 声明，合并会造假阳性）。
     */
    private val refNamesByKey: Map<String, Set<String>> by lazy {
        val m = HashMap<String, MutableSet<String>>()
        for ((key, dims) in funcDims) {
            val refs = dims.filter { it.ref }
            if (refs.isEmpty()) continue
            val names = m.getOrPut(key.uppercase()) { HashSet() }
            for (d in refs) names.add(d.name.uppercase())
        }
        m
    }

    override fun refParamNamesOf(functionKey: String): Set<String>? = refNamesByKey[functionKey.uppercase()]

    /** 本函数局部表快照（调试/导出用）。 */
    val currentSlots: Map<String, Int> get() = curSlots.toMap()
}

/**
 * 尺寸表达式求值（specs/loader.md D.8.1 —— 原版 ReduceIntegerTerm(...).Restructure(null)
 * 须为 SingleLongTerm，UserDefinedVariableData.cs:300-341）。
 *
 * **单源**（CMP-45/CMP-40）：实现委托给 [com.eratw.port.core.vars.DimParser.evalDimExpr] ——
 * 与侧车/运行期解析 `#DIM` 行用的是同一个记号流 + 同一个折叠器：
 * - 标识符按 [consts] 解析（ERH `#DIM CONST` / `#DEFINE` 宏常量由调用方展开成值表喂入；
 *   erbcc 不做宏展开）；
 * - 字面量支持十进制 / `0x` / `0b` / `p` / `e` 全部 `ReadInt64` 形态
 *   （spec: LexicalAnalyzer.cs:30-90），运算支持 `+ - * / %`、一元正负、括号与
 *   `<<`/`>>`（spec: OperatorMethodManager.cs:523-534）；
 * - 装载期可折叠函数（VARSIZE / POWER / COLOR_FROMNAME / UNICODE / GETNUM / STRLENSU /
 *   GETLINESTR）经 [functionFolder] 注入后同样能折（spec: FunctionMethodTerm.cs:35-56）；
 *   不注入时保持「函数调用不可折叠」的失败方向（不许静默变成功）。
 *
 * 结果必须唯一（= SingleLongTerm 约束；带逗号/尾巴的文本返回 null）。无法求值返回 null ——
 * 对应原版「该行无效、变量不注册」（D.8：解析失败的行不收集）。
 *
 * **值域不在这里判**：`0`、`-5`、`2000000` 都照常返回数值（`CompileAudit2Test` 钉着这条
 * 契约）；`1..1000000` / REF 只能 0 由声明层（[com.eratw.port.core.vars.DimParser.parse]
 * 与 erbcc 桥的 `dimDropReason`）按 UserDefinedVariableData.cs:329-340 判。
 */
class DimSizeEvaluator(
    private val consts: Map<String, Long> = emptyMap(),
    /** 装载期可折叠函数回调（[com.eratw.port.core.vars.DimFunctionFolders.pureStatic]）。 */
    private val functionFolder: com.eratw.port.core.vars.DimFunctionFolder? = null,
) {

    private val config = com.eratw.port.core.vars.DimParseConfig(
        intConstants = consts,
        functionFolder = functionFolder,
    )

    fun eval(text: String): Long? = com.eratw.port.core.vars.DimParser.evalDimExpr(text, config)
}

/**
 * **占位布局**：全部当全局槽 + 名字表。
 *
 * 每个变量名按**首次出现顺序**分配一个全局槽号（整型/字符串分两张表），映射关系
 * 保持在内存中（[intSlots]/[strSlots]），erbcc 落盘 `.erbc` 时可将其序列化为名字表段。
 * 全局槽自增分配，程序级共享同一实例即得到一致布局。
 */
class GlobalNameLayout : LocalLayout {

    private val ints = LinkedHashMap<String, Int>()
    private val strs = LinkedHashMap<String, Int>()

    override fun slotOf(name: String, isString: Boolean): Int {
        val key = name.uppercase()
        return if (isString) {
            com.eratw.port.core.code.SlotAddr.global(strs.getOrPut(key) { strs.size })
        } else {
            com.eratw.port.core.code.SlotAddr.global(ints.getOrPut(key) { ints.size })
        }
    }

    /** 整型变量名 → 全局槽号（不含 bit31 标志位）。 */
    val intSlots: Map<String, Int> get() = ints.toMap()

    /** 字符串变量名 → 全局槽号。 */
    val strSlots: Map<String, Int> get() = strs.toMap()
}
