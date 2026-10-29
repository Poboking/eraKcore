package com.eratw.port.core.proc

/**
 * 单个 @函数 的运行期元数据 —— ProcessRuntime 做调用绑定 / 事件派发 / 局部实例化所需
 * 的全部静态信息。
 *
 * 数据来源是与 compile agent 的接口契约：erbcc 编译期从符号表（tools/erbcc Symbols.kt 的
 * LabelDef）抽取，运行期以本类注入 [FunctionTable]。在 compile 落地前，测试直接用
 * [FuncMeta] 构造函数手工填写。
 *
 * 命名对齐 LabelDef（tools/erbcc/.../Symbols.kt:131-150）；key 一律 IGNORE CASE 归一
 * （大写），对应 Config.StrComper = OrdinalIgnoreCase（spec: Config.cs:33, 299-308）。
 *
 * **M13-MEM5 刀 2（表示形态改造，语义零改动）**：本类由 `data class` 改为普通类，
 * 16 个属性的**名字、类型、取值、构造签名（具名实参 + 缺省值）、equals/hashCode/toString/
 * copy 的值语义**与 `data class` 时代逐位相同；变的只是内部布局：
 * - 7 个 Boolean 收进一个 Int 位域（[flags]）——属性读出同值；
 * - `position`（`"ERB/FILE.ERB:42"`，erbcc 写侧 CorpusCompile.kt:682）在 [FunctionTable]
 *   装配期压实为「文件前缀串引用 + 行号 Int」（见 [position]）：110,072 条独立长串
 *   （mem5 拆解探针实测 16.9 MiB 常驻）换成 ≤3,706 条共享前缀串（0.5 MiB）+ 每 meta
 *   一个 Int；读面逐字节相同（压实只在能**无损反解析**时发生，见 [packPosition]）。
 * 对象浅尺寸不变（56 B，字段对齐所致），常驻收益全部来自 position 串与查询表
 * （见 [FunctionTable]）。
 *
 * 装载侧（erbcc/E2E 管线）先建占位 meta（index=-1）、链接函数下标后按 [copy] 定稿——
 * 结构化 copy 保留；FunctionTable 只做索引化查询、不依赖实例等值语义。
 *
 * TODO(verify)：#DIM 私有变量的布局信息（dimSpecs：维度/类型/静态性，另一 agent 正在给
 * LabelDef 增加）落地后在此追加字段；ProcessRuntime 只透传给 VariableStore.declareUserVar，
 * 不自行解释。
 */
class FuncMeta(
    /** 程序函数表下标（= Program.functions 的 index，_CALL 的 a 操作数）。 */
    index: Int,
    /** 归一化函数键（大写），即 VariableStore.enterFunction 的 funcKey。 */
    val key: String,
    /** 源码书写名（报错用）。 */
    name: String = key,
    /** 事件函数（EVENTFIRST 等 9 名）。spec: IdentifierDictionary.cs:72-89 */
    isEvent: Boolean = false,
    /** #FUNCTION / #FUNCTIONS 方法（只能被表达式 / CALLF 调用）。spec: CalledFunction.cs:106-109 */
    isMethod: Boolean = false,
    /** 事件 #ONLY。spec: LabelDictionary.cs:90-105 */
    isOnly: Boolean = false,
    /** 事件 #PRI。 */
    isPri: Boolean = false,
    /** 事件 #LATER。 */
    isLater: Boolean = false,
    /** 事件 #SINGLE（返回值 1 时跳过同桶剩余成员）。spec: ProcessState.cs:306-310 */
    isSingle: Boolean = false,
    /** 整型形参数（ARG 桶绑定个数）。 */
    argIntCount: Int = 0,
    /** 字符串形参数（ARGS 桶绑定个数）。 */
    argStrCount: Int = 0,
    /** #LOCALSIZE n；未声明 0（局部长度用注册表默认）。spec: LogicalLineParser.cs:218-262 */
    localSize: Int = 0,
    /** #LOCALSSIZE n。spec: LogicalLineParser.cs:263-273 */
    localsSize: Int = 0,
    /** 方法返回类型：true = #FUNCTIONS（字符串），false = #FUNCTION（整数）。 */
    returnsStr: Boolean = false,
    /** 装载序号（事件同桶内按此升序执行）。 */
    defIndex: Int = index,
    /** 装载来源（报错定位用，如 "ERB/FILE.ERB:42"）。 */
    position: String = "",
) {
    /** 源码书写名（报错用）；与 [key] 同实例时不另占堆（装载侧 internKey 已共享）。 */
    val name: String = name

    // ---- M13-MEM8 刀 B：四只 int 装下全部标量（56 B → 40 B/条，真产物 −1.68 MiB）----
    // 每个取值域都由构造期 require 兜住（真语料实测：下标 < 2^17、argInt ≤ 25 / argStr ≤ 30、
    // #LOCALSIZE ≤ 1000 / #LOCALSSIZE ≤ 10000、行号 ≤ 52,594），越域当场炸而不是静默截断。
    //
    // w0 = 下标(20 位) | 标志位(8 位)：下标上界与装载器 FUNC_COUNT_MAX = 1 shl 20 同源。
    // w1 = position 行号(16 位) | 整型形参数(8 位) | 字符串形参数(8 位)。
    // w2 = #LOCALSIZE(16 位) | #LOCALSSIZE(16 位)。
    // w3 = 装载序号，全 32 位。
    private var w0: Int
    private var w1: Int
    private val w2: Int
    private val w3: Int

    init {
        require(index in 0..INDEX_MASK) { "函数下标越 ${INDEX_BITS} 位域: $index" }
        require(argIntCount in 0..ARG_MASK) { "整型形参个数越 8 位域: $argIntCount" }
        require(argStrCount in 0..ARG_MASK) { "字符串形参个数越 8 位域: $argStrCount" }
        require(localSize in 0..SIZE16_MASK) { "#LOCALSIZE 越 16 位域: $localSize" }
        require(localsSize in 0..SIZE16_MASK) { "#LOCALSSIZE 越 16 位域: $localsSize" }
        w0 = index or (((if (isEvent) F_EVENT else 0) or
            (if (isMethod) F_METHOD else 0) or
            (if (isOnly) F_ONLY else 0) or
            (if (isPri) F_PRI else 0) or
            (if (isLater) F_LATER else 0) or
            (if (isSingle) F_SINGLE else 0) or
            (if (returnsStr) F_RETURNS_STR else 0)) shl INDEX_BITS)
        w1 = (argIntCount shl 16) or (argStrCount shl 24)
        w2 = localSize or (localsSize shl 16)
        w3 = defIndex
    }

    /** 程序函数表下标（= Program.functions 的 index，_CALL 的 a 操作数）。 */
    val index: Int get() = w0 and INDEX_MASK

    /** 标志位域（构造期建、装配期只置 [F_POS_PACKED] 一位）。 */
    private var flags: Int
        get() = w0 ushr INDEX_BITS
        set(v) {
            w0 = (w0 and INDEX_MASK) or (v shl INDEX_BITS)
        }

    /** 整型形参数（ARG 桶绑定个数）。 */
    val argIntCount: Int get() = (w1 ushr 16) and ARG_MASK

    /** 字符串形参数（ARGS 桶绑定个数）。 */
    val argStrCount: Int get() = (w1 ushr 24) and ARG_MASK

    /** #LOCALSIZE n；未声明 0（局部长度用注册表默认）。spec: LogicalLineParser.cs:218-262 */
    val localSize: Int get() = w2 and SIZE16_MASK

    /** #LOCALSSIZE n。spec: LogicalLineParser.cs:263-273 */
    val localsSize: Int get() = w2 ushr 16

    val isEvent: Boolean get() = flags and F_EVENT != 0
    val isMethod: Boolean get() = flags and F_METHOD != 0
    val isOnly: Boolean get() = flags and F_ONLY != 0
    val isPri: Boolean get() = flags and F_PRI != 0
    val isLater: Boolean get() = flags and F_LATER != 0
    val isSingle: Boolean get() = flags and F_SINGLE != 0
    val returnsStr: Boolean get() = flags and F_RETURNS_STR != 0

    /** 装载序号（事件同桶内按此升序执行）。 */
    val defIndex: Int get() = w3


    /**
     * position 的存放体：未压实 = 原文串本体（实例保持，装载侧同值即同串的共享性不受影响）；
     * 已压实 = 文件前缀串（全语料 ≤3,706 个，跨 meta 共享）。
     */
    private var posText: String = position

    /** position 行号（压实档专用，16 位域；越域见 [packPosition]）。 */
    private var posLine: Int
        get() = w1 and SIZE16_MASK
        set(v) {
            w1 = (w1 and (SIZE16_MASK shl 16)) or v
        }

    /**
     * 装载来源。压实后由「前缀串 + ':' + 行号」重建，与原文逐字节相同（[packPosition] 只在
     * 可无损反解析时压实）；每次读分配一个新串——本属性只在报错定位 / FN-05 / 侧车写回
     * 这类冷路径被读（热路径 [FunctionTable] 查询不经过它），分配可接受。
     */
    val position: String
        get() = if (flags and F_POS_PACKED == 0) posText else posText + ':' + posLine

    /**
     * [FunctionTable] 装配期的 position 压实入口（唯一写点；构造后除本方法外不改实例）。
     *
     * 判据：当前未压实、原文最后一个冒号在 0 以后、冒号后是**无前置零的十进制行号**
     * （`line.toString()` 与原文片段逐字相等 ⇒ 重建必逐字节还原）、冒号前有内容。
     * 任一不满足（空串、`·event-entry·` 这类合成标记、无冒号、非常规行号）原样保留。
     */
    internal fun packPosition(prefix: String, line: Int) {
        if (flags and F_POS_PACKED != 0) return
        // 行号超 16 位域：不压（原文串原样留着，读值与重建逐字节同）——真语料最大 52,594。
        if (line < 0 || line > SIZE16_MASK) return
        posText = prefix
        posLine = line
        // 本方法只由 FunctionTable init 单线程装配调用，且发生在实例发布给查询面之前
        // （AGENTS §2 铁律 4 的「构造后不写」由装配序保证）。
        flags = flags or F_POS_PACKED
    }

    internal val positionPacked: Boolean get() = flags and F_POS_PACKED != 0

    /**
     * 事件执行桶（`LabelDictionary.cs:90-105` 的 `array[0..3]`）：
     * ONLY=0 → PRI=1 → NORMAL=2 → LATER=3，执行序由 [FunctionTable.eventCallOrder] 的稳定
     * 排序给出（同桶内保装载序）。
     *
     * **与 C# 的一处已知分叉（TODO(verify)，当前语料不可达）**：C# 对同一 label 是**并列**
     * 加桶 —— `if (IsOnly) array[0].Add; if (IsPri) array[1].Add; if (IsLater) array[3].Add;
     * if (!IsPri && !IsLater) array[2].Add;`（`LabelDictionary.cs:90-105`），因此
     * `IsPri && IsLater` 或 `IsOnly` 的 label 会同时落**两个桶**、被 `ShiftNext`
     * （`CalledFunction.cs:228-243`）派发**两次**；本字段是**排他**桶，只派发一次。
     * 可达性：语料全树扫描 `#ONLY`/`#SINGLE` 命中 0、同一函数体内同时含 `#PRI` 与
     * `#LATER` 的定义 0 处 ⇒ 现值不可观测。要严格复刻需把本字段改成「桶集合」并让
     * `eventCallOrder` 产出重复项（属跨文件接口变更，留给组长裁定）。
     */
    val eventBucket: Int
        get() = when {
            isOnly -> 0
            isPri -> 1
            isLater -> 3
            else -> 2
        }

    /**
     * 值语义与 `data class` 时代逐位相同：16 个构造属性按声明序等值比较
     * （position 走属性读值 ⇒ 压实前后等值不受影响）。
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FuncMeta) return false
        return index == other.index && key == other.key && name == other.name &&
            isEvent == other.isEvent && isMethod == other.isMethod &&
            isOnly == other.isOnly && isPri == other.isPri && isLater == other.isLater &&
            isSingle == other.isSingle && argIntCount == other.argIntCount &&
            argStrCount == other.argStrCount && localSize == other.localSize &&
            localsSize == other.localsSize && returnsStr == other.returnsStr &&
            defIndex == other.defIndex && position == other.position
    }

    /** 属性声明序的 hashCode 合成（与 `data class` 同配方；只由属性读值构成，压实不变）。 */
    override fun hashCode(): Int {
        var result = index
        result = 31 * result + key.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + isEvent.hashCode()
        result = 31 * result + isMethod.hashCode()
        result = 31 * result + isOnly.hashCode()
        result = 31 * result + isPri.hashCode()
        result = 31 * result + isLater.hashCode()
        result = 31 * result + isSingle.hashCode()
        result = 31 * result + argIntCount
        result = 31 * result + argStrCount
        result = 31 * result + localSize
        result = 31 * result + localsSize
        result = 31 * result + returnsStr.hashCode()
        result = 31 * result + defIndex
        result = 31 * result + position.hashCode()
        return result
    }

    /** `data class` 同款渲染（SymbolLinkAudit 的「首差」文案按此格式对账）。 */
    override fun toString(): String =
        "FuncMeta(index=$index, key=$key, name=$name, isEvent=$isEvent, isMethod=$isMethod, " +
            "isOnly=$isOnly, isPri=$isPri, isLater=$isLater, isSingle=$isSingle, " +
            "argIntCount=$argIntCount, argStrCount=$argStrCount, localSize=$localSize, " +
            "localsSize=$localsSize, returnsStr=$returnsStr, defIndex=$defIndex, position=$position)"

    /** `data class` 同款 copy（全部具名 + 缺省 = 当前值）。 */
    fun copy(
        index: Int = this.index,
        key: String = this.key,
        name: String = this.name,
        isEvent: Boolean = this.isEvent,
        isMethod: Boolean = this.isMethod,
        isOnly: Boolean = this.isOnly,
        isPri: Boolean = this.isPri,
        isLater: Boolean = this.isLater,
        isSingle: Boolean = this.isSingle,
        argIntCount: Int = this.argIntCount,
        argStrCount: Int = this.argStrCount,
        localSize: Int = this.localSize,
        localsSize: Int = this.localsSize,
        returnsStr: Boolean = this.returnsStr,
        defIndex: Int = this.defIndex,
        position: String = this.position,
    ): FuncMeta = FuncMeta(
        index = index,
        key = key,
        name = name,
        isEvent = isEvent,
        isMethod = isMethod,
        isOnly = isOnly,
        isPri = isPri,
        isLater = isLater,
        isSingle = isSingle,
        argIntCount = argIntCount,
        argStrCount = argStrCount,
        localSize = localSize,
        localsSize = localsSize,
        returnsStr = returnsStr,
        defIndex = defIndex,
        position = position,
    )

    internal companion object {
        // 位域编号与侧车 `function flags` 逐位一致（BootMetadata.kt:924-930 的 1/2/4/8/16/32/64），
        // 压实标记占 bit 8（侧车读侧值域闸 FUNC_FLAGS_MASK 之下，永不与侧车位冲突）。
        internal const val F_EVENT = 1
        internal const val F_METHOD = 2
        internal const val F_ONLY = 4
        internal const val F_PRI = 8
        internal const val F_LATER = 16
        internal const val F_SINGLE = 32
        internal const val F_RETURNS_STR = 64
        internal const val F_POS_PACKED = 128

        /** 下标域宽（与装载器 FUNC_COUNT_MAX = 1 shl 20 同源）。 */
        internal const val INDEX_BITS = 20
        internal const val INDEX_MASK = (1 shl INDEX_BITS) - 1

        /** 形参个数域（8 位）与局部长度域（16 位）。 */
        internal const val ARG_MASK = 0xFF
        internal const val SIZE16_MASK = 0xFFFF

        /** position 压实用的逐字节反解析判据：`line.toString()` 必须与原文数字片段逐字相等。 */
        internal fun splitPosition(raw: String): Pair<String, Int>? {
            if (raw.isEmpty()) return null
            val cut = raw.lastIndexOf(':')
            if (cut <= 0 || cut == raw.length - 1) return null
            val digits = raw.substring(cut + 1)
            var v = 0
            for (ch in digits) {
                if (ch < '0' || ch > '9') return null
                v = v * 10 + (ch - '0')
                if (v > 0x7FFF_FFFF) return null
            }
            if (digits.length > 1 && digits[0] == '0') return null
            if (v.toString() != digits) return null
            return raw.substring(0, cut) to v
        }
    }
}

/**
 * 运行期函数表：名字解析 + 事件分桶执行序。
 *
 * 与 compile agent 的契约：erbcc 产出 `List<FuncMeta>`（装载序，defIndex 升序），
 * 本表只做索引化与查询，不解释语义。非事件函数重名 → **后装载者生效**
 * （spec: LabelDictionary.cs:60-68 字典直接赋值）；事件函数全部保留、按桶执行
 * （spec: CalledFunction.cs:228-243 + ProcessState.cs:326-333）。
 *
 * **M13-MEM5 刀 2（常驻形态，mem5 拆解探针实测）**：
 * - 装配期把可无损反解析的 `position` 压实为「共享文件前缀 + 行号」（[FuncMeta.packPosition]），
 *   110,072 条独立原文长串（16.9 MiB）缩为 ≤3,706 条前缀串（0.5 MiB）——本桶唯一大头。
 * - 查询表**保持** `HashMap` 原形态：开放寻址替代方案（省 2.3 MiB）在窄基准里按名解析
 *   慢 1.33~1.69x，按「名字解析不变慢」口径退回，实测记录见 [nonEventByKey] 处注释。
 * 只动表示：查询结果、事件分桶、后装载者语义与改造前逐位相同（[nonEvent] 等
 * 读面的返回实例与改造前一致）。
 */
class FunctionTable(metasIn: List<FuncMeta>) {

    /**
     * 装载序（`defIndex` 非降）序列。
     *
     * PERF: erbcc emits the input in load order (`defIndex` ascending), so the
     * unconditional `sortedBy { it.defIndex }` was copying 110,072 entries and
     * doing O(n log n) comparisons, each boxing the defIndex Int. Scan once in
     * O(n) first: when already non-decreasing, reuse the input list (a stable
     * sort of an already sorted input is index-for-index identical, see
     * [isDefIndexSorted]); only copy+sort when the input is actually out of order.
     *
     * **M13-MEM8 刀 D**：真语料「下标序即装载序且槽位稠密」（`defIndex = index + 1`
     * 实测 110,066 / 110,072 条，剩下 6 条见归因探针 §2），于是 [byIndex] 一张表同时
     * 服务「按下标取」与「按装载序遍历」两条面，不再另持一条 110,072 槽的引用背衬
     * （0.42 MiB，与 byIndex 重复持有同一批实例）。判据不成立时才退回这条序列，
     * 行为与改造前逐字相同。
     */
    private val orderedMetas: List<FuncMeta>?

    /**
     * 下标（= Program.functions index）→ 元数据。
     *
     * **数组而不是 `HashMap<Int, …>`**：VM 每次 `_CALL` 都按函数下标取元数据，旧实现
     * 每次把 Int 装箱成 Integer 再做哈希（真机采样 `Integer.valueOf` 在世界初始化那页
     * 可见）；函数下标本来就是 0..N-1 的稠密整数，直接数组寻址即可。
     */
    private val byIndex: Array<FuncMeta?>

    /**
     * 装载序序列的**读面**：[orderedMetas] 为空时就是 [byIndex] 的只读视图（零拷贝，
     * 元素非空由装配期的稠密判据保证）；初始化序（查询表/压实）与 [all] 都只走这一面，
     * 两种形态给出的元素实例与次序逐位相同。
     */
    private val loadOrder: List<FuncMeta>

    /** 名字大写化记忆（同 [VariableRegistry.keyOf] 的理由：ART 的 ICU uppercase 是热点）。 */
    private val nameKeys = HashMap<String, String>(2048)

    private fun keyOf(name: String): String {
        nameKeys[name]?.let { return it }
        val u = name.uppercase()
        nameKeys[name] = u
        return u
    }

    /** 非事件函数：key → 生效定义（后装载者）。 */
    // PERF（M13-MEM5 波次实测记录）：本表曾试「开放寻址槽数组 + hash 过滤数组 + Murmur3
    // 终混」省掉 Node 壳（建模 4.4 → 2.1 MiB），窄基准 `FuncTableQueryBenchTest`
    // 真产物同机对拍：按名解析腿 NEW/OLD 稳定 1.33~1.69x（线性探测未命中腿解槽主
    // key 串的依赖随机访存 + 双数组第二条随机线，都没换回 HashMap「hash 内联在
    // 已加载 Node 里」的便宜；2 的幂主簇问题靠终混解决后差距仍在）。
    // 按波次契约「变慢就退回那一条改动」保持 HashMap 原形态；本桶收益全在
    // position 压实（见下 init 末行与 [FuncMeta.packPosition]）。
    //
    // M13-MEM8 第二次尝试（单张 IntArray 槽：fingerprint 11 位 | 下标+1 20 位，负载 0.25）
    // 同样判死：`FuncTableFormBenchTest` 真产物 3 轮取最小，按名解析腿 SLOT19/HASH
    // = 1.15x（第二次复跑 1.51x）、引用槽变体 REF19/HASH = 1.10~1.18x，均越 5% 闸门。
    // 原因是命中仍要「槽 → byIndex → meta.key」三条依赖随机线（HashMap 只要两条）。
    // 结论：本表形态不动，本桶只做刀 B（壳尺寸）与刀 D（撤重复背衬）。
    // PREALLOC: capacity 110,036 条非事件键（仅容量，无语义变化）。
    private val nonEventByKey = HashMap<String, FuncMeta>(hashCapacityFor(metasIn.size))

    /** 事件函数：key → 全部成员（装载序）。 */
    private val eventMembersByKey = HashMap<String, MutableList<FuncMeta>>()

    /**
     * 事件成员键（`KEY#defIndex`）→ 元数据。FN-05 的当前行前缀要按
     * [FuncMeta.key] 反查源文件，而 [nonEventByKey] 只含非事件函数。
     * 事件成员量级是几十条，单独一张小表即可。
     */
    private val eventMemberByKey = HashMap<String, FuncMeta>()

    init {
        // 装载序 = defIndex 非降；erbcc 侧本来就是下标序，已有序时直接复输入列表（零拷贝）。
        val sorted = if (isDefIndexSorted(metasIn)) metasIn else metasIn.sortedBy { it.defIndex }
        byIndex = arrayOfNulls<FuncMeta?>((sorted.maxOfOrNull { it.index } ?: -1) + 1).also { arr ->
            for (m in sorted) if (m.index in arr.indices) arr[m.index] = m
        }
        // 刀 D：「下标序即装载序 + 槽位稠密」⇒ byIndex 一张表代役两条面（判据见 KDoc）。
        val dense = sorted.size == byIndex.size && isIndexSeq(byIndex)
        if (dense) {
            orderedMetas = null
            loadOrder = asLoadOrder(byIndex)
        } else {
            orderedMetas = sorted
            loadOrder = sorted
        }
        for (m in loadOrder) {
            if (m.isEvent) {
                eventMembersByKey.getOrPut(m.key) { ArrayList() }.add(m)
                eventMemberByKey[m.key] = m
            } else {
                // spec: LabelDictionary.cs:60-68 —— 直接赋值，最后装载者留下
                nonEventByKey[m.key] = m
            }
        }
        // position 压实（刀 2，本桶大头）：一条 meta 一次反解析；前缀串跨 meta 共享。
        compactPositions(loadOrder)
    }

    private fun compactPositions(all: List<FuncMeta>) {
        val prefixes = HashMap<String, String>(1024)
        for (m in all) {
            if (m.positionPacked) continue
            val raw = m.position
            val split = FuncMeta.splitPosition(raw) ?: continue
            // getOrPut 让等值前缀跨 meta 归一到同一实例；position 读值是「前缀 + ':' + 行号」，
            // 换实例不换内容 ⇒ 逐字节还原不受影响。
            val prefix = prefixes.getOrPut(split.first) { split.first }
            m.packPosition(prefix, split.second)
        }
    }

    /** 是否存在该名字的任意定义（事件或非事件）。 */
    fun contains(key: String): Boolean {
        val k = keyOf(key)
        return nonEventByKey.containsKey(k) || eventMembersByKey.containsKey(k)
    }

    /** 非事件函数的生效定义；事件名或未知名返回 null。 */
    fun nonEvent(key: String): FuncMeta? = nonEventByKey[keyOf(key)]

    /**
     * 事件的执行序：桶 ONLY(0) → PRI(1) → NORMAL(2) → LATER(3)，同桶按装载序。
     * spec: CalledFunction.cs:228-243（ShiftNext 逐桶推进）+ LabelDictionary.cs:77-105（分桶）。
     * 非事件名或未知名返回 null。
     */
    fun eventCallOrder(key: String): List<FuncMeta>? {
        val members = eventMembersByKey[keyOf(key)] ?: return null
        // 稳定排序：sortBy 对相等元素保持原次序（装载序）
        return members.sortedBy { it.eventBucket }
    }

    /** 名字的三态分类，供 CALL / CALLFORM / CALLEVENT 的运行期解析。 */
    enum class TargetKind { NON_EVENT, EVENT, NOT_FOUND }

    /** 运行期按名分类（IGNORE CASE）。 */
    fun classify(key: String): TargetKind {
        val k = keyOf(key)
        return when {
            nonEventByKey.containsKey(k) -> TargetKind.NON_EVENT
            eventMembersByKey.containsKey(k) -> TargetKind.EVENT
            else -> TargetKind.NOT_FOUND
        }
    }

    fun byFunctionIndex(index: Int): FuncMeta? =
        if (index >= 0 && index < byIndex.size) byIndex[index] else null

    /**
     * 函数键（大写，含事件成员的 `KEY#defIndex`）→ 源文件显示名（FN-05）。
     *
     * 来源 = [FuncMeta.position]（erbcc 写 `"文件:行号"`，CorpusCompile.kt:679/:717）。
     * 只剥最后一个 `:` 后的行号；`·event-entry·` 这类合成标记返回 null（不是源文件）。
     * 走 [nonEvent]/[eventMemberByKey] 的查表路，UNICODE 告警级别低频调用足够。
     */
    fun sourceFileOf(key: String): String? {
        val k = keyOf(key)
        val m = nonEvent(k) ?: eventMemberByKey[k] ?: return null
        val pos = m.position
        if (pos.isEmpty() || pos.startsWith("·")) return null
        val cut = pos.lastIndexOf(':')
        val file = if (cut > 0) pos.substring(0, cut) else pos
        return file.takeIf { it.isNotEmpty() }
    }

    /** 全部元数据（诊断 / 集成测试用）；装载序，两种形态给出同一批实例与同一次序。 */
    fun all(): List<FuncMeta> = loadOrder
}

/**
 * Whether the input list is already non-decreasing by `defIndex`.
 *
 * Equivalence: Kotlin `sortedBy` is a stable sort (sortedWith(compareBy(...))).
 * For any stable sort, if the input is already non-decreasing by the comparison
 * key, the i-th output element equals the i-th input element: equal keys keep
 * their relative order and unequal keys are already in place. Reusing the input
 * therefore yields exactly the same `all()` sequence (same element instances)
 * as the old `metas.sortedBy { it.defIndex }`.
 *
 * Uses an iterator, not indices, so it stays O(n) for non-RandomAccess lists too.
 */
private fun isDefIndexSorted(metas: List<FuncMeta>): Boolean {
    var prev: FuncMeta? = null
    for (m in metas) {
        if (prev != null && prev.defIndex > m.defIndex) return false
        prev = m
    }
    return true
}

/**
 * 「下标序即装载序」判据（刀 D）：每个槽非空且 `byIndex[i].index == i`。
 *
 * 成立时 `byIndex` 的数组次序 = 装载序（`sorted` 已按 defIndex 非降，且下标与槽位一一对应），
 * 于是遍历面不必再持一条重复的引用背衬；不成立（稀疏/重复下标/乱序）时退回列表字段，
 * 两条面给出的序列逐位相同。
 */
private fun isIndexSeq(arr: Array<FuncMeta?>): Boolean {
    for (i in arr.indices) {
        val m = arr[i] ?: return false
        if (m.index != i) return false
    }
    return true
}

/** [byIndex] 的零拷贝只读视图；调用方必须先过 [isIndexSeq]（保证无空槽、下标自证）。 */
@Suppress("UNCHECKED_CAST")
private fun asLoadOrder(arr: Array<FuncMeta?>): List<FuncMeta> = arr.asList() as List<FuncMeta>

/** Conservative HashMap capacity for [expected] entries with load factor 0.75. */
private fun hashCapacityFor(expected: Int): Int =
    if (expected <= 0) 16 else expected + (expected shr 1) + 1
