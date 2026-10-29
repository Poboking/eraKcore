package com.eratw.port.core.vars

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.VariableHandles
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.expr.VariableWriter

/**
 * 下标暂存池的嵌套深度上限（`VariableStore.borrowIndices` 的分槽基数）。
 *
 * 8 层足够覆盖语料里最深的下标嵌套（下标表达式里再套带下标的变量访问，实测最深 3 层）；
 * 超过就退回分配，只影响那一次，不影响借还配对。
 */
private const val SCRATCH_DEPTH = 8

/**
 * 「变量名 → 定义」绑定缓存的槽数（直接映射，2 的幂）。
 *
 * 语料里不同的变量名有 2 万余个，但**热**名字（`CFLAG`/`TALENT`/`BASE`/`LOCAL`/`GMF_*`…）
 * 只有几百个，512 槽足以把热集常驻；槽冲突只损失一次重查，不会给结果。
 *
 * ⚠ 别为了降冲突把它加大：三个平行数组按 `hashCode and MASK` **随机**取下标，槽数一上去
 * 每个数组就是几十 KB，热路径从「整表常驻 L1」退化成逐次 L1 miss。2026-10-27 实测
 * 4096 槽（≈116 KB）比 512 槽（≈12 KB）**慢 12%**（同窗口配对 median 431.0 → 484.2 ns/指令）。
 */
private const val BIND_SLOTS = 512
private const val BIND_MASK = BIND_SLOTS - 1

/** 绑定缓存的「查过、确实不存在」哨兵（与 null 区分：null 表示这一槽还没填）。 */
private val BIND_MISSING: Any = Any()

/**
 * [VariableStore.Frame.bySlot] 的初始容量。
 *
 * 语料的局部变量名字只有四个（`LOCAL`/`LOCALS`/`ARG`/`ARGS`，见
 * `VariableStore.LOCAL_NAME_ORDER`），所以 4 槽一次到位、稳态不再扩容；
 * 万一语料出现更多（`Storage.L_INT` 的其他名字），`localForBind` 按需翻倍。
 */
private const val LOCAL_SLOT_INITIAL = 4

/**
 * 绑定缓存的「该名字的解析结果与当前作用域无关」哨兵。
 *
 * 别名/系统变量（绝大多数热名字）在任何函数里都解析成同一条目：它们的绑定结果不必绑
 * 当前帧的私有变量表。`bindPriv` 存这个哨兵，命中判据就不必比较 `currentPrivMap`，
 * 于是**跨函数调用恒命中**。
 */
private val SCOPE_ANY: Any = Any()

/**
 * 变量存储池：全局列 + 角色列 + 用户变量 + 局部变量，并实现 expr 层的 [VariableReader]。
 *
 * 布局（ADR-004）：
 * - 全局：`LongArray` 列（1D/2D/3D 各按行主序压平）；字符串列存 [StringTable] 下标。
 * - 角色：见 [CharacterTable]，`charIndex * blockSize + elem`。
 * - 用户 `#DIM`：静态单份 / 私有动态栈 / REF 绑定 / CHARADATA 追加列。
 *
 * 下标一律先经 [checkElement] 逐维检查（spec: VariableData.cs:81-100, CharaVariableToken.cs:56-70），
 * 越界抛 [CodeEE]（对应原版 OoRVarArg / OoRCharaVarArg）。
 */
class VariableStore(
    val registry: VariableRegistry = VariableRegistry(),
    val config: VarConfig = VarConfig(),
    val pool: StringTable = StringTable(),
    /**
     * 角色表初始容量（缺省 1，用到才长）；理由与原版对照见 [CharacterTable] 的类 KDoc。
     * 显式传更大值仍是合法提示（少几次扩容），不改变任何格子取值。
     */
    initialCharaCapacity: Int = 1,
    var randProvider: RandProvider? = null,
) : VariableReader, VariableWriter, VariableHandles {

    private val sizes = registry.sizes
    val characters: CharacterTable = CharacterTable(sizes, config, initialCharaCapacity)

    /**
     * 附属运行时状态（XML/Map/DT/图形池）。与变量池同生命周期——一个 VM 一套，
     * 测试并行与读档重建都不会串味。见 [com.eratw.port.core.host.RuntimeAux]。
     */
    val aux: com.eratw.port.core.host.RuntimeAux = com.eratw.port.core.host.RuntimeAux()

    /**
     * 变量写观察口（DBG4 P3 log 事件流，形状见 [VarWriteProbe]）：非 null 且
     * [VarWriteProbe.watches] 命中时，[writeInt] / [writeStr] 在**入口**（校验前）同步回调。
     * **null = 生产缺省 = 零行为**——守卫是单次 volatile 读 + 名单查找短路，miss 零分配
     * 零虚调用（StatementProbe 同款判据；写路径百万级/秒，名单判定必须 O(1)）。
     * @Volatile：宿主线程赋值、VM 线程消费（ADR-003 单线程执行）。
     */
    @Volatile
    var writeProbe: VarWriteProbe? = null

    // ---- 全局列 ----
    // 系统整数列**按需分配**（mem6 2026-10-28）。三张 `Array<LongArray?>` 里 null = 「这一列
    // 还没人写过」，读它等价于读一个刚 new 出来、全 0 的数组。理由与边界：
    //
    // - **为什么值得改**：语料 `CSV/VariableSize.csv` 没给 3 维尺寸 ⇒ `TA`/`TB` 吃出厂值
    //   `109951267635300L`（= 100×100×100，spec: ConstantData.cs:232-235），两列各
    //   1,000,000 个 long。真产物 §5 实测「系统全局整数列」桶 = **16.0 MiB / 74 个 LongArray**，
    //   占 §1 那枚「VariableStore + 运行期图」34.0 MiB 的一半；标题页一行脚本都没跑过，
    //   这两列连一个元素都没被读过。1 维/2 维列合计不到 1 MiB，改动对它们只是形态一致。
    // - **读**：越界判定发生在取数**之前**（[checkFlat] → [checkElement]，
    //   spec: VariableData.cs:81-100 / CharaVariableToken.cs:56-70），列缺席不会把「越界读」
    //   吞成 0；列在场时 `a[elem]` 的越界形态照旧原样保留。
    // - **写 / REF 绑定 / 整组赋值 / 初值回填**：一律经 [intCol] 现分配，尺寸与初值与旧写法
    //   逐字节相同（`dimProduct` 的**报错时点**也保持构造期：见 [gInt2Len]/[gInt3Len]）。
    // - **复位**：[resetValues] / [resetGlobalValues] 只把**已分配**的列原地 `fill(0)`，
    //   绝不丢回 null —— 原版 `SetDefaultValue` 对同一个数组对象原地清零，而 REF 绑的就是
    //   那个对象（spec: ReferenceToken.cs:125-128 `SetRef(Array)`）；换对象会让已绑定的 REF
    //   看不见复位结果，那是行为改动，本路不许。
    // - 尺寸仍来自 [VariableSizes]（构造后不再变化：`VariableSize.csv` 在 registry 构造之前
    //   就装载完，spec: ConstantData.cs:309-551 的 `changeVariableSizeData` 全在装载期）。
    private val gInt1: Array<LongArray?> = arrayOfNulls(sizes.int1D.size)
    private val gInt2: Array<LongArray?> = arrayOfNulls(sizes.int2D.size)
    private val gInt3: Array<LongArray?> = arrayOfNulls(sizes.int3D.size)

    /**
     * 各维乘积（**构造期算完**，与旧的 `LongArray(dimProduct(…))` 同一条报错时序）：
     * VARSIZE.CSV 把维度乘积写过 2^31 时，装载当场 CodeEE，而不是等第一次写到那一列才炸。
     * 口径与理由见 [VarDef.totalSize] 与 [dimProduct]。
     */
    private val gInt2Len: IntArray =
        IntArray(sizes.int2D.size) { dimProduct("系统 2 维整数数组 #$it", sizes.int2D[it]) }
    private val gInt3Len: IntArray =
        IntArray(sizes.int3D.size) { dimProduct("系统 3 维整数数组 #$it", sizes.int3D[it]) }

    /** 取列并必要时分配（写侧唯一入口；`length` 恒等于旧实现一次性分配时的数组长度）。 */
    private fun intCol(cols: Array<LongArray?>, index: Int, length: Int): LongArray {
        val existing = cols[index]
        if (existing != null) return existing
        val fresh = LongArray(length)
        cols[index] = fresh
        return fresh
    }

    private fun intCol1(index: Int): LongArray = intCol(gInt1, index, sizes.int1D[index])

    private fun intCol2(index: Int): LongArray = intCol(gInt2, index, gInt2Len[index])

    private fun intCol3(index: Int): LongArray = intCol(gInt3, index, gInt3Len[index])

    // 维度乘积走 [dimProduct]（Long 预乘 + 上限判定）：VARIABLESIZE.CSV 会把这里的维度
    // 换成装载期数值，Int 折叠溢出会先分配成负数/偏小数组，再在寻址时炸成 AIOOBE。
    private val gStr1: Array<IntArray> = Array(sizes.str1D.size) { IntArray(sizes.str1D[it]) }
    private val gStr0: IntArray = IntArray(1)

    /** ITEMPRICE 的常量来源（ITEM.CSV 第 3 列）。spec: ConstantData.cs:1803-1813 */
    private var itemPriceSource: LongArray = LongArray(sizes.int1D[60])

    // ---- 用户变量 ----
    private val userVars = LinkedHashMap<String, UserVar>()
    private val privateVars = LinkedHashMap<String, LinkedHashMap<String, UserVar>>()
    private val globalUserVars = LinkedHashMap<String, UserVar>()
    private val savedUserVars = LinkedHashMap<String, UserVar>()

    // ---- 函数帧 / 局部变量 ----
    private class Local1D(val def: VarDef, var size: Int) {
        var ints: LongArray? = null
        var strs: IntArray? = null
    }

    /**
     * 一帧的局部作用域快照：`(函数键, 名字表, 私有变量表)`。
     *
     * ## 为什么字段全是 `var`（PERF 2026-10-27 第三批）
     *
     * 旧实现每次 `_CALL` 都 `new Frame(...)`。JFR 分配采样实测 `VariableStore$Frame`
     * 占执行循环内分配的 **16.0%（102.9 MiB）**，而它只有 4 个引用字段（32 B）——
     * 纯属「每调一次造一个能复用的壳」。改成**池化**：`leaveFunction` 归还、
     * `enterFunction` 取用，稳态下池里只有「最深的那个调用栈」那么多个实例。
     *
     * 因此字段不能是 `val`：复用时要重写。**安全性**依据是「帧的生命周期严格等于它在
     * [frames] 栈上的时间」——全类内 `Frame` 只有两种持有方式：栈内（`frames`）与
     * 方法内的临时参数（`localForBind` / `localSizeOf`），没有任何字段或闭包跨
     * `enterFunction`/`leaveFunction` 边界持有它。
     */
    private class Frame {
        /** 该函数的键（已过 [upperOf]）；归还池时清成空串，避免池钉住字符串。 */
        var funcKey: String = ""

        /**
         * 该函数的局部实例表 —— 与 [localsByFunc] 里的是**同一个实例**（跨递归帧共享，
         * 见 [localsByFunc] 的 KDoc）。建帧时取一次，之后每访问一次局部变量都省掉
         * `localsByFunc[funcKey]` 这次哈希。
         */
        var locals: LinkedHashMap<String, Local1D> = EMPTY_LOCALS

        /**
         * 该函数的私有用户变量表（`privateVars[funcKey]`，没有就是 null）。
         *
         * 建帧时取一次 ⇒ [refreshPrivMap] 从「一次哈希查表」变成「一次字段读」，
         * 而它在每次 `enterFunction`/`leaveFunction` 都会被调用（一轮 69.8 万次 ×2）。
         *
         * **必须可写**：`#DIM …, isPrivate` 可以在帧建立**之后**才执行（过程体内声明），
         * 那一刻建帧时取的快照还是 null。[refreshPrivMap] 会在 null 时回查一次并回填，
         * 之后仍是字段读 —— 语义与旧实现「每次都现查」一致。
         */
        var privMap: MutableMap<String, UserVar>? = null

        /**
         * 该函数的局部状态（名字表 + 槽位镜像）。
         *
         * 镜像改挂在这里而不是帧上（PERF 2026-10-27 第二批）：`Frame` 每次 `_CALL` 都新建，
         * 而镜像的内容只跟「函数」有关。见 [LocalState] 的 KDoc。
         *
         * 归还池时置 null —— [bySlot] 的转发因此在池里是安全的（读到的就是 null）。
         */
        var state: LocalState? = null

        /** 复用：把这一帧指向一次新调用。 */
        fun bind(
            funcKey: String,
            locals: LinkedHashMap<String, Local1D>,
            privMap: MutableMap<String, UserVar>?,
            state: LocalState,
        ) {
            this.funcKey = funcKey
            this.locals = locals
            this.privMap = privMap
            this.state = state
        }

        /** 归还池前断开所有引用（否则池会把名字表/私有表钉到进程结束）。 */
        fun release() {
            funcKey = ""
            locals = EMPTY_LOCALS
            privMap = null
            state = null
        }

        private companion object {
            /**
             * 池里占位用的空名字表。
             *
             * 归还的帧 `state` 也置 null，所以这个实例**永不被读**；它只是为了满足
             * 非空类型，避免给 `locals` 声明成可空而让热路径多一次空判。
             */
            val EMPTY_LOCALS = LinkedHashMap<String, Local1D>()
        }

        /**
         * 按 [VarDef.localSlot] 直取的**密集镜像**；元素恒与 [locals] 里同一实例。
         *
         * 只是缓存，不是权威：未命中时回落 [locals]（`localFor` 的 `localForBind`）。
         * 取用侧必须再用 `v.def === def` 验身份 —— 槽位号是全局单调分配的，
         * 跨 `VariableStore` 实例复用同一批 `VarDef` 时可能撞号。
         *
         * 存储实际在 [LocalState.bySlot]（按函数一份，见那里的 KDoc）；这里只是转发，
         * 让热路径的写法保持 `f.bySlot`（JIT 内联后就是两次字段读）。
         */
        var bySlot: Array<Local1D?>?
            get() = state?.bySlot
            set(value) {
                state?.bySlot = value
            }
    }

    private val frames = ArrayList<Frame>()

    /**
     * 空闲帧池（PERF 2026-10-27 第三批）：`leaveFunction` 归还、`enterFunction` 取用。
     *
     * 稳态大小 = 历史最深调用栈深度（不是调用次数）—— 一次 `CHARA_MOVEMENT` 回合
     * 69.8 万次 `_CALL` 只在这一条池上反复取还。归还时 [Frame.release] 断开全部引用，
     * 否则池会把每个函数的名字表与私有变量表钉到进程结束。
     */
    private val framePool = ArrayList<Frame>()

    /**
     * 复用缓冲（PERF 2026-10-27 第六批）：替代 `enterFunction` 里每次 `_CALL` 的
     * `privMap.values.toList()`（`ArrayList` + `Object[]` 分配）。仅在 `enterFunction`
     * 方法体内使用，`scopeIn()` 是纯数据结构操作（压一层值）不会重入 `enterFunction`
     * ⇒ 单实例安全。
     */
    private val scopeInBuf = ArrayList<UserVar>(16)

    /**
     * 当前帧（热路径直取末元素）。
     *
     * `frames.lastOrNull()` 是带接收者空检查的 Kotlin 扩展调用：真机采样里
     * `Intrinsics.checkNotNullParameter <- CollectionsKt.lastOrNull <- userVar` 占
     * 世界初始化那页 2.4%（每个变量访问都走一次）。直接判空 + 下标即可。
     */
    private fun topFrame(): Frame? = if (frames.isEmpty()) null else frames[frames.size - 1]
    private var localLengths = HashMap<String, LocalLengths>()

    /**
     * 局部变量按**函数**存一份，不按调用帧。
     *
     * 原版 `VariableLocal.localVarTokens` 以 subKey（`ParentLabelLine.LabelName`，
     * spec: IdentifierDictionary.cs:454-471）为键，`LocalVariableToken` 本身没有
     * ScopeIn/ScopeOut（spec: LocalVariableToken.cs:7-58）⇒ 递归调用共享同一份
     * LOCAL/ARG 数组、返回值后**不回滚**外层值。本层旧实现把 locals 挂在调用帧上，
     * 递归时会自动恢复外层值，与原版不一致。
     */
    private val localsByFunc = LinkedHashMap<String, LocalState>()

    /**
     * 一个函数的局部变量**状态**：名字表 + 槽位镜像，同生共死。
     *
     * PERF（2026-10-27 第二批）：`bySlot` 原本挂在 [Frame] 上 ⇒ **每次 `_CALL` 一份**。
     * 而 `Frame` 每次进函数都新建（一轮 69.8 万次），于是这张 4 槽的 `Array<Local1D?>`
     * 也跟着每次分配一份 —— JFR 分配采样里 `VariableStore$Local1D[]` 占执行循环内
     * 分配的 **10.8%**（111.9 MiB），比它缓存的那点 CPU 省下来的还贵。
     *
     * 关键观察：这张镜像的元素是**名字表里那些 `Local1D` 实例的引用**，而名字表本来就
     * 按函数复用（[localsByFunc]，递归帧共享同一份，spec: VariableLocal.cs:15,26-33）。
     * 所以镜像的正确生命周期就是「按函数」，不是「按帧」—— 跟名字表一起建、一起清即可。
     *
     * 递归：同一个函数的多个在栈帧共享同一张镜像，与它们共享名字表完全同构，不引入新语义。
     * 扩容时把新数组写回本对象，其他在栈帧要么已经拿到（同一次 `localForBind`），
     * 要么下次未命中时回落名字表再取新数组 —— 镜像**永远只是缓存**，落后只会变慢不会变错。
     */
    private class LocalState {
        val locals: LinkedHashMap<String, Local1D> = LinkedHashMap()

        /** 按 [VarDef.localSlot] 直取的密集镜像；元素恒与 [locals] 里同一实例。 */
        var bySlot: Array<Local1D?>? = null
    }

    /**
     * 「局部变量实例按 [VarDef.localSlot] 直取」的开关（缺省开）。
     * `-Deratw.perf.noLocalSlots=true` 退回旧的「两层名字哈希」路径。
     *
     * 只为一件事存在：**同窗口 A/B**。跨窗口比较在本项目已被证伪（`CONTRACT-T2` 的
     * T2-62 回执第 4 条：单轮内 7 个稳态样本自身跨 17%），而「改代码 → 重编 → 再测」
     * 天然是跨窗口。构造期读一次 ⇒ 热路径零成本。
     */
    private val useLocalSlots: Boolean = !java.lang.Boolean.getBoolean("eratw.perf.noLocalSlots")

    /** [VarDef.localSlot] 的单调分配游标（只增；语料里只有 LOCAL/LOCALS/ARG/ARGS 四个）。 */
    private var localSlotCount = 0

    private fun assignLocalSlot(def: VarDef): Int {
        val s = localSlotCount
        localSlotCount = s + 1
        def.localSlot = s
        return s
    }

    /** [frameSnapshotLines] 的导出序：整数在前、字符串在后（与 `#DIM` 声明顺序无关）。 */
    private val LOCAL_NAME_ORDER = arrayOf("LOCAL", "LOCALS", "ARG", "ARGS")

    // ---- 角色模板 ----
    private val templatesByNo = LinkedHashMap<Long, CharacterTemplate>()
    private val templatesByName = LinkedHashMap<String, CharacterTemplate>()

    // ---- 宿主状态（伪变量取值来源）----
    var lineCount: Long = 0
    /**
     * LINECOUNT 的实时来源（原版 `Console.LineCount`，spec: VariableData.cs:930-942）。
     * 非 null 时优先于 [lineCount] 快照；纯变量池测试无宿主，走快照。
     */
    var lineCountSource: (() -> Long)? = null
    var isTimeout: Boolean = false
    var windowTitle: String = ""
    var moneyLabel: String = "$"
    var drawLineStr: String = ""
    var lastLoadText: String = ""
    var lastLoadVersion: Long = -1
    var lastLoadNo: Long = -1
    var debugFile: String = ""
    var debugFunction: String = ""
    var debugLine: Long = -1

    init {
        resetValues()
    }

    // ==================================================================
    // VariableReader
    // ==================================================================

    override fun isConst(name: String): Boolean {
        userVar(name)?.let { return it.spec.isConst }
        return registry.def(name)?.isConst ?: false
    }

    override fun readInt(name: String, indices: LongArray): Long =
        readIntEntry(resolveEntry(name) ?: throw CodeEE("未定义的变量 $name"), indices)

    override fun readStr(name: String, indices: LongArray): String =
        readStrEntry(resolveEntry(name) ?: throw CodeEE("未定义的变量 $name"), indices)

    override fun plusInt(name: String, indices: LongArray, delta: Long): Long =
        plusIntEntry(resolveEntry(name) ?: throw CodeEE("未定义的变量 $name"), name, indices, delta)

    /**
     * 名字 → 变量条目（[UserVar] 或 [VarDef]），解析顺序与原生 `readInt` 一致：
     * 先用户变量（私有按当前帧隔离），再系统变量表。
     *
     * 一条**唯一**的解析入口：按名路径与句柄路径共用它，`resolveInt`/`resolveStr` 也走它。
     * 这样「按名读」与「按句柄读」不是两份实现，而是同一份实现的两个入口 —— 差分测试
     * 想红都红不了，也不可能在后续改动里各自漂移。
     *
     * 实现走 [bindOf]（PERF 2026-10-26 绑定缓存）：语义与 `userVar(name) ?:
     * registry.def(name)` 逐字相同（私有变量优先当前帧，其次全局用户变量，最后系统变量），
     * 只是把「四次哈希查表」的结果记住了。
     */
    private fun resolveEntry(name: String): Any? = bindOf(name)

    // ------------------------------------------------------------------
    // 下标暂存缓冲（PERF 2026-10-26）
    // ------------------------------------------------------------------

    /**
     * 按下标个数复用的缓冲池，**栈式借还**（[borrowIndices] / [returnIndices]）。
     *
     * 带下标的变量访问（`CFLAG:LOCAL:X` 之类）在环体里是百万级次数，旧实现每次
     * `LongArray(n)`。改成按「嵌套深度 × 下标个数」取用：同一层、同一下标个数的访问
     * 复用同一块，于是稳态下**零分配**。可重入：下标表达式本身可能又是带下标的变量访问，
     * 它拿的是更深一层的缓冲，不会踩掉外层正在填的那块。
     *
     * **数组长度必须精确等于本次下标个数**：存储层按 `indices.size` 校验下标个数
     * （`checkElement` 的 `indices.size != def.argCount`），借出超长数组会被判成
     * 「多给了下标」（2026-10-26 实测：复用 4 长缓冲去读 1 维 `LOCALS` 报
     * 「变量 LOCALS 需要 1 个下标（实际 4）」）。故按 `深度*8 + 下标个数` 分槽，长度恒精确。
     *
     * 只服务 VM 单线程（ADR-003）；借用方只有表达式层的 `VarRef` / 一元 `++` `--`，
     * 语句层与宿主读口仍自带数组，不进这个池。
     */
    private val indexScratch = arrayOfNulls<LongArray>(SCRATCH_DEPTH * 8)

    private var indexScratchDepth = 0

    override fun borrowIndices(size: Int): LongArray {
        val d = indexScratchDepth
        indexScratchDepth = d + 1
        // 深于池容量或下标多到没分槽（异常形态）：照旧分配，但仍占一个深度位，保证借还配对。
        if (d >= SCRATCH_DEPTH || size !in 0 until 8) return LongArray(size)
        val slot = d * 8 + size
        val cur = indexScratch[slot]
        if (cur != null) return cur
        val fresh = LongArray(size)
        indexScratch[slot] = fresh
        return fresh
    }

    override fun returnIndices() {
        if (indexScratchDepth > 0) indexScratchDepth--
    }

    // ------------------------------------------------------------------
    // 「变量名 → 定义」绑定缓存（PERF 2026-10-26 第二轮 / 2026-10-27 定稿）
    // ------------------------------------------------------------------

    /**
     * 每个变量访问原本要付**四次哈希查表**：`userVar` 的 `privateVars[funcKey]`、
     * `userVarsRaw[name]`、`upperOf(name)` 的 `upperNames[name]`、`userVars[upper]`；
     * 未命中用户变量再走 `registry.def(name)`。JFR（`perf3.jfr`，1462 条应用采样）实测
     * `lookupUserVar` 15.5% + `VariableRegistry.def` 5.6% + `upperOf` 5.0% ≈ **26%**，
     * 叶子帧 `StringLatin1.hashCode` 16.1% / `HashMap.getNode` 15.0% 基本都花在这里。
     *
     * 缓存把这一串压成「一次数组下标 + 两次引用比较」：`bindNames[slot] === name`
     * （`VarRef` 的名字是稳定字段，同一实例恒命中）+ 作用域判据（见下）。
     *
     * ## 作用域判据：只有「可能私有」的名字才绑当前帧
     *
     * 首版把 `bindPriv[slot] === currentPrivMap` 当无条件判据。`currentPrivMap` 在每次
     * `enterFunction`/`leaveFunction` 都可能换身份（没有私有变量的函数是 null），所以这个
     * 判据理论上会让热变量的缓存在每次跨调用时作废。
     *
     * **但实测它不是主因**（2026-10-27，`t61.jfr` 同窗口的计数器读数，2.75 亿次访问）：
     * `hit=33.45%`、`missScope=86,383`（**0.03%**）、`missEmpty=67`、
     * `missCollide=183,259,220`（**66.5%**）。真病根是**槽位冲突**：语料的热名字集合远大于
     * 512 槽，任何「按名字直接映射」的表都会被冲掉；而加大表会撞 L1 局部性这堵墙（4096 槽
     * 实测慢 12%，见 [BIND_SLOTS]）。所以按名字的表无论怎么调参都救不回来 —— 命中率的那一
     * 跳来自**按访问点绑定**（`VarRef` 站点字段，结构上零冲突），本表的 33% → 99% 是它的
     * 副产物（`VarRef` 命中后不再走到这里）。
     *
     * [SCOPE_ANY] 的作用因此不是「提升命中率」，而是**把这 0.03% 也消掉**，并且让命名语义
     * 与站点缓存对齐：[privateNames] 是「语料里**任何**函数声明过私有变量的名字」的集合
     * （声明期维护，`#DIM` 全在装配期落地），名字不在其中 ⇒ 无论当前帧是谁解析结果都一样 ⇒
     * 判据里不必比较作用域；名字在其中 ⇒ 保留严格判据（`bindPriv` 与 `currentPrivMap` 比
     * 身份）。两个分支都不会给错结果，差别只在命中率。
     *
     * 判据由 [nameScopedOf] 统一提供，读侧的 `VarRef` 站点缓存调
     * [VariableHandles.nameScoped] 拿的是同一个答案 —— 两条缓存路径不分叉。
     *
     * ## 为什么用「名字实例身份」而不是 equals
     * `name` 来自语句/表达式树的字段，全程是同一个 String 对象；身份比较既避开了
     * `String.equals` 的逐字符，也避开了 `hashCode()` 的重复计算。身份不匹配
     * （不同实例、同内容）只是退化成一次重查，**不会给错结果**。
     *
     * ## 失效
     * - 帧切换：不整表失效（那会把跨调用的热变量全冲掉），靠上面的判据自动区分。
     * - 声明新变量：[declareUserVar] 等声明点调 [invalidateBindCache] 整表清空（装载期行为，
     *   不在热路径上）。
     */
    private val bindNames = arrayOfNulls<String>(BIND_SLOTS)

    /**
     * 解析该条目时的作用域，或 [SCOPE_ANY]（该名字的作用域无关）。
     *
     * 用一个哨兵而不是另一个 `BooleanArray`：热路径每多碰一个平行数组就多一条缓存行，
     * 512 槽 × 3 个数组已经占满 L1 的一半，第 4 个数组的边际成本远大于它省下的那次比较。
     */
    private val bindPriv = arrayOfNulls<Any>(BIND_SLOTS)
    private val bindVals = arrayOfNulls<Any>(BIND_SLOTS)

    /**
     * 语料里**被某个函数声明为私有**的变量名（大写）。
     *
     * 只增不减：`privateVars` 本身没有删除路径（重名声明直接抛错），所以这张表能安全地
     * 当「该名字的作用域是否敏感」的判据。为空时（纯系统变量脚本）[bindOf] 完全跳过大写
     * 记忆表探测。
     */
    private val privateNames = HashSet<String>(64)

    /**
     * 当前帧的私有用户变量表（`privateVars[currentFuncKey]`，没有就是 null）。
     *
     * 只在 [enterFunction] / [leaveFunction] / 私有变量声明三处刷新 —— 那几处本来就是
     * 作用域变化点。有了它，作用域敏感的名字判断「当前帧解析成什么」只需一次引用比较，
     * 不必再查 `privateVars`（那本身又是一次哈希查表）。
     */
    private var currentPrivMap: MutableMap<String, UserVar>? = null

    /** 声明点整表清空（[declareUserVar] 等）；装载期行为，热路径不调用。 */
    private fun invalidateBindCache() {
        bindNames.fill(null)
    }

    /**
     * 解析一次「名字 → 用户变量 / 系统变量定义」，结果进绑定缓存。
     *
     * 语义与原来的 `userVar(name) ?: registry.def(name)` 逐字相同（私有变量优先当前帧，
     * 其次全局用户变量，最后系统变量），只是把结果记住了。查不到返回 null。
     */
    private fun bindOf(name: String): Any? {
        val slot = name.hashCode() and BIND_MASK
        if (bindNames[slot] === name) {
            val sc = bindPriv[slot]
            if (sc === currentPrivMap || sc === SCOPE_ANY) {
                val v = bindVals[slot]
                return if (v === BIND_MISSING) null else v
            }
        }
        val found: Any? = lookupUserVar(name) ?: registry.def(name)
        bindNames[slot] = name
        // 只有「某个函数声明过同名私有变量」时，解析结果才随作用域变化。
        bindPriv[slot] = if (nameScopedOf(name)) currentPrivMap else SCOPE_ANY
        bindVals[slot] = found ?: BIND_MISSING
        return found
    }

    /** 整型读的**唯一**函数体（按名入口与 [readIntH] 共用）。 */
    private fun readIntEntry(e: Any, indices: LongArray): Long {
        if (e is UserVar) {
            val slot = checkAndSlot(e, indices)
            return e.readInt((slot ushr 32).toInt(), slot.toInt())
        }
        val def = e as VarDef
        if (def.type != VarType.INT) throw CodeEE("变量 ${def.name} 是字符串变量，不能当整数读取")
        if (def.storage == Storage.PSEUDO) return pseudoInt(def, indices)
        if (def.storage == Storage.L_INT) {
            // 局部变量：长度按函数声明决定（可能大于默认 1000），[localInt] 自带 Long 口径
            // 判界；保留原路径（不是热路径的主要形态）。
            checkElement(def, indices)
            return localInt(def, indices[0])
        }
        val elem = checkFlat(def, indices)
        return when (def.storage) {
            Storage.G_INT1 -> gInt1[def.index]?.get(elem) ?: 0L
            Storage.G_INT2 -> gInt2[def.index]?.get(elem) ?: 0L
            Storage.G_INT3 -> gInt3[def.index]?.get(elem) ?: 0L
            Storage.C_INT0 -> characters.int0[def.index].get(indices[0].toInt(), 0)
            Storage.C_INT1 -> characters.int1[def.index].get(indices[0].toInt(), elem)
            Storage.C_INT2 -> characters.int2[def.index].get(indices[0].toInt(), elem)
            else -> throw CodeEE("变量 ${def.name} 不是整数数组")
        }
    }

    /** 字符串读的**唯一**函数体（按名入口与 [readStrH] 共用）。 */
    private fun readStrEntry(e: Any, indices: LongArray): String {
        if (e is UserVar) {
            val slot = checkAndSlot(e, indices)
            return e.readStr((slot ushr 32).toInt(), slot.toInt())
        }
        val def = e as VarDef
        if (def.type != VarType.STR) throw CodeEE("变量 ${def.name} 是整数变量，不能当字符串读取")
        if (def.storage == Storage.PSEUDO) return pseudoStr(def)
        if (def.storage == Storage.L_STR) {
            checkElement(def, indices)
            return localStr(def, indices[0])
        }
        val elem = checkFlat(def, indices)
        return when (def.storage) {
            Storage.G_STR0 -> pool.lookup(gStr0[0])
            Storage.G_STR1 -> pool.lookup(gStr1[def.index][elem])
            Storage.C_STR0 -> pool.lookup(characters.str0[def.index].get(indices[0].toInt(), 0))
            Storage.C_STR1 -> pool.lookup(characters.str1[def.index].get(indices[0].toInt(), elem))
            Storage.CSV_CONST -> registry.csvTables[def.index][elem]
            else -> throw CodeEE("变量 ${def.name} 不是字符串数组")
        }
    }

    /** `++`/`--` 的**唯一**函数体（按名入口与 [plusIntH] 共用）。 */
    private fun plusIntEntry(e: Any, name: String, indices: LongArray, delta: Long): Long {
        if (e is UserVar) {
            val slot = checkAndSlot(e, indices)
            val charPos = (slot ushr 32).toInt()
            val elem = slot.toInt()
            val nv = e.readInt(charPos, elem) + delta
            e.writeInt(charPos, elem, nv)
            return nv
        }
        val def = e as VarDef
        if (def.storage == Storage.PSEUDO && def.pseudo != PseudoKind.WINDOW_TITLE) {
            throw pseudoAssignError(def, wantStr = false, arrayForm = false)
        }
        if (!def.isWritable) throw CodeEE("变量 ${def.name} 是常量，不能赋值")
        val cur = readIntEntry(e, indices)
        writeIntEntry(e, name, indices, cur + delta)
        return cur + delta
    }

    override fun keywordToInteger(varName: String, dim: Int, keyword: String): Long {
        if (varName.uppercase() == "RELATION") {
            if (dim != 1) throw CodeEE("变量 RELATION 的第 ${dim + 1} 个参数不能用字符串指定")
            val tmpl = templatesByName[keyword]
                ?: throw CodeEE("未定义角色名 \"$keyword\"（chara*.csv 中不存在）")
            return tmpl.no
        }
        if (userVar(varName) != null) {
            // ERD 用户变量关键字表（spec: ConstantData.cs:1090-1190 的 VAR*/CVAR* 分支）：
            // 文件 = `<变量名>.csv`（1D）或 `<变量名>@<维>.csv`（2D/3D），装载点见
            // `UserKeywordTables.install`；这里只查表。
            val table = userKeywordTable(varName, dim)
                ?: throw CodeEE(
                    "变量 $varName 的第 ${dim + 1} 个参数不能用字符串指定" +
                        "（未找到 ERD 表 ${varName.uppercase()}.csv）",
                )
            // 空关键词先于查表报错（VariableStrArgTerm.GetIntValue 的 KeyWordCanNotEmpty）。
            if (keyword.isEmpty()) throw CodeEE("关键字不能为空")
            return table[keyword]?.toLong()
                ?: throw CodeEE("${varName.uppercase()}.csv 中未定义关键字 \"$keyword\"")
        }
        return registry.keywordIndex(varName, dim, keyword).toLong()
    }

    // ------------------------------------------------------------------
    // ERD 用户变量关键字表（spec: ErhLoader.cs:344-392 + ConstantData.cs:758-803）
    // ------------------------------------------------------------------

    /** 用户变量名（大写）+ 0 基下标位置 → 关键字表。装载点 = `UserKeywordTables.install`。 */
    private val userKeywordTables = HashMap<Pair<String, Int>, Map<String, Int>>()

    /** 挂一张 ERD 表到 `(变量名, 下标位置)`；同键后到者覆盖（同一变量多处私有声明共享文件）。 */
    fun registerKeywordTable(varName: String, dim: Int, table: Map<String, Int>) {
        userKeywordTables[varName.uppercase() to dim] = table
    }

    /** 查 ERD 表；null = 该变量在这个下标位置没有表（不是用户变量也返回 null）。 */
    fun userKeywordTable(varName: String, dim: Int): Map<String, Int>? =
        userKeywordTables[varName.uppercase() to dim]

    /**
     * `GETNUM` / `ERDNAME` 的维度后缀查表（原版 `TryKeywordToInteger` /
     * `TryIntegerToKeyword`，ConstantData.cs:849-895）。
     *
     * 原版把第三参**直接拼进键名**（`FunctionMethodCreator.cs:4819` Getnum / `:8397` ErdName）：
     * 省略 ⇒ **裸变量名**；显式 ⇒ `名字@<第三参>`。而 ERD 表的注册键（`ErhLoader.cs:273-311`）是
     * 1 维变量 = 裸名（`:279`），多维变量 = `名字@1` / `名字@2` / `名字@3`（`:291` / `:308`）。
     * ⇒ 两个键空间**不可互换**：省略后缀只有 **1 维**变量能命中，显式后缀只有 **多维**
     * 变量的合法维度号能命中；交叉形态在原版一律查表失败（Getnum 折 `-1`、ErdName 折空串，
     * `ConstantData.cs:866` / `:884` 的 `TryGetValue` 落空）。
     *
     * 本层把「键」投影成**完整下标位**（角色变量第 0 位是角色维，注册侧见
     * `UserKeywordTables`），故后缀必须按同一投影换算；旧实现把 `null` 当成 `@1` 再换算，
     * 于是「多维 + 省略后缀」会误命中 `名字@1` 表、「1 维 + 显式后缀」会误命中裸名表，
     * 均与原版相反（语料无此二形态，纯对齐）。
     */
    fun userKeywordTableBySuffix(varName: String, dimSuffix: Long?): Map<String, Int>? {
        val v = userVar(varName) ?: return null
        val dims = v.spec.dims.size
        val charaOffset = if (v.spec.isCharaData) 1 else 0
        if (dimSuffix == null) {
            // 省略第三参 ⇒ 裸名键。只有 1 维变量的 ERD 表以裸名挂载（其余维数只有 `名字@n`）。
            if (dims != 1) return null
            return userKeywordTable(varName, charaOffset)
        }
        // 显式第三参 ⇒ `名字@n` 键。1 维变量没有 `名字@n` 条目，不许回落裸名表。
        if (dims < 2 || dimSuffix < 1 || dimSuffix > dims) return null
        val pos = (dimSuffix - 1).toInt() + charaOffset
        return userKeywordTable(varName, pos)
    }

    /** [userKeywordTableBySuffix] 的反查（`TryIntegerToKeyword`）；未命中返回 null。 */
    fun userKeywordNameBySuffix(varName: String, dimSuffix: Long?, value: Long): String? {
        val table = userKeywordTableBySuffix(varName, dimSuffix) ?: return null
        if (value < 0 || value > Int.MAX_VALUE) return null
        return table.entries.firstOrNull { it.value.toInt() == value.toInt() }?.key
    }

    // ==================================================================
    // 写
    // ==================================================================

    override fun writeInt(name: String, indices: LongArray, value: Long) =
        writeIntEntry(resolveEntry(name) ?: throw CodeEE("未定义的变量 $name"), name, indices, value)

    override fun writeStr(name: String, indices: LongArray, value: String) =
        writeStrEntry(resolveEntry(name) ?: throw CodeEE("未定义的变量 $name"), name, indices, value)

    /**
     * 整型写的**唯一**函数体（按名入口与 [writeIntH] 共用）。
     *
     * [name] 只喂写观察口：`VarWriteProbe.watches` 是大小写敏感的名单判定，传源拼写才与
     * 改造前的调试断点行为逐字一致（存储定位只认 [e]）。
     */
    private fun writeIntEntry(e: Any, name: String, indices: LongArray, value: Long) {
        // DBG4 P3 观察口（入口、校验前）：miss 短路零分配；命中才进 runCatching 吞口。
        // 校验前挂点意味着校验失败的写入也会发事件（new = 请求值），KDoc 已声明。
        val p = writeProbe
        if (p != null && p.watches(name)) runCatching { p.onWriteInt(name, indices, value) }
        if (e is UserVar) {
            val slot = checkAndSlot(e, indices)
            e.writeInt((slot ushr 32).toInt(), slot.toInt(), value)
            return
        }
        val def = e as VarDef
        if (def.type != VarType.INT) throw CodeEE("变量 ${def.name} 是字符串变量，不能写入整数")
        if (def.storage == Storage.PSEUDO) throw pseudoAssignError(def, wantStr = false, arrayForm = false)
        if (!def.isWritable) throw CodeEE("变量 ${def.name} 是常量，不能赋值")
        if (def.storage == Storage.L_INT) {
            checkElement(def, indices)
            setLocalInt(def, indices[0], value)
            return
        }
        val elem = checkFlat(def, indices)
        when (def.storage) {
            Storage.G_INT1 -> intCol1(def.index)[elem] = value
            Storage.G_INT2 -> intCol2(def.index)[elem] = value
            Storage.G_INT3 -> intCol3(def.index)[elem] = value
            Storage.C_INT0 -> characters.int0[def.index].set(indices[0].toInt(), 0, value)
            Storage.C_INT1 -> characters.int1[def.index].set(indices[0].toInt(), elem, value)
            Storage.C_INT2 -> characters.int2[def.index].set(indices[0].toInt(), elem, value)
            // 整数伪变量全部只读：上面 `pseudoAssignError` 已无条件抛出，本分支不可达。
            // 保留是为了与 writeStrEntry 的 PSEUDO 分支（那条是活路径）对称，别误删。
            Storage.PSEUDO -> setPseudo(def, value)
            else -> throw CodeEE("变量 ${def.name} 不能写入整数")
        }
    }

    /** 字符串写的**唯一**函数体（按名入口与 [writeStrH] 共用）；[name] 口径同上。 */
    private fun writeStrEntry(e: Any, name: String, indices: LongArray, value: String) {
        // DBG4 P3 观察口（入口、校验前），同 writeInt 的守卫判据
        val p = writeProbe
        if (p != null && p.watches(name)) runCatching { p.onWriteStr(name, indices, value) }
        if (e is UserVar) {
            val slot = checkAndSlot(e, indices)
            e.writeStr((slot ushr 32).toInt(), slot.toInt(), value)
            return
        }
        val def = e as VarDef
        if (def.type != VarType.STR) throw CodeEE("变量 ${def.name} 是整数变量，不能写入字符串")
        if (!def.isWritable) throw CodeEE("变量 ${def.name} 是常量，不能赋值")
        if (def.storage == Storage.L_STR) {
            checkElement(def, indices)
            setLocalStr(def, indices[0], pool.intern(value))
            return
        }
        val elem = checkFlat(def, indices)
        val id = pool.intern(value)
        when (def.storage) {
            Storage.G_STR0 -> gStr0[0] = id
            Storage.G_STR1 -> gStr1[def.index][elem] = id
            Storage.C_STR0 -> characters.str0[def.index].set(indices[0].toInt(), 0, id)
            Storage.C_STR1 -> characters.str1[def.index].set(indices[0].toInt(), elem, id)
            // 字符串伪变量（WINDOW_TITLE 可写，其余在 setPseudoStr 里抛「不能赋值」）：
            // 与 writeIntEntry 不同，这里**没有**前置的 pseudoAssignError 拦截，分支是活路径。
            Storage.PSEUDO -> setPseudoStr(def, value)
            else -> throw CodeEE("变量 ${def.name} 不能写入字符串")
        }
    }

    // ==================================================================
    // 句柄化存取（T2-11 的句柄层；T2-23 的装期绑定消费它）
    //
    // 句柄 = 本 store 私有的小整数下标，指向 [handleEntries] 里的**既有**条目
    // （`UserVar` 或 `VarDef` 本体，不是复制品）。因此按句柄访问与按名访问走的是同一份
    // 函数体（readIntEntry 一族），差别只在「名字 → 条目」这一步有没有做。
    //
    // 私有变量的句柄带隐含前提：解析时的栈顶帧 == 使用时执行帧。调用方（VM）的缓存键
    // 因此必须是 `(函数, 变量名串池下标)`，见 `Vm.varHandle`。
    //
    // 句柄长期有效的前提是「条目实例不被替换」：本类的 [declareUserVar] 重名即抛
    // （不覆盖）、重置走 `setDefault()` 原地清零、读档走原地回填 ⇒ `UserVar` 与
    // `VarDef` 一经注册就不换实例，句柄不会指向陈旧对象。若将来引入「热重载替换变量
    // 实例」，必须同时让 VM 的 `varHandleTables` 作废（换 store 那条判据救不了同池换实例）。
    // ==================================================================

    /** 句柄 → 条目。下标 0 是 [VariableHandles.NONE] 的占位（永远不读）。 */
    private val handleEntries: ArrayList<Any> = ArrayList<Any>(64).apply { add(Any()) }

    /** 条目 → 句柄（`VarDef`/`UserVar` 都未覆写 equals ⇒ 天然身份语义）。 */
    private val handleSlots: HashMap<Any, Int> = HashMap(64)

    /**
     * 取句柄对应的条目。
     *
     * 越界与哨兵一律 [CodeEE]：句柄是本层分配的，坏句柄只可能来自「跨 store 复用」
     * 或调用方自己算错。裸 `IndexOutOfBoundsException` 没有变量名也没有行号，
     * 与「字节码不完整必须 CodeEE」的全局口径冲突。
     */
    private fun entryOf(handle: Int): Any {
        if (handle <= VariableHandles.NONE || handle >= handleEntries.size) {
            throw CodeEE("变量句柄 $handle 无效（句柄只在分配它的变量池内有效）")
        }
        return handleEntries[handle]
    }

    private fun internHandle(e: Any): Int {
        handleSlots[e]?.let { return it }
        val h = handleEntries.size
        handleEntries.add(e)
        handleSlots[e] = h
        return h
    }

    /**
     * 解析整型变量句柄。
     *
     * **不做类型过滤**：字符串变量也会拿到合法句柄，`readIntH` 再按与按名路径**同一句**
     * 报错（用户变量走 `UserVar.readInt` 的「不是整数变量」，系统变量走「是字符串变量，
     * 不能当整数读取」）。在这里提前过滤会让两条路径的报错文案分叉。
     */
    override fun resolveInt(name: String): Int =
        resolveEntry(name)?.let { internHandle(it) } ?: VariableHandles.NONE

    /** 解析字符串变量句柄；口径同 [resolveInt]。 */
    override fun resolveStr(name: String): Int =
        resolveEntry(name)?.let { internHandle(it) } ?: VariableHandles.NONE

    override fun readIntH(handle: Int, indices: LongArray): Long = readIntEntry(entryOf(handle), indices)

    override fun readStrH(handle: Int, indices: LongArray): String = readStrEntry(entryOf(handle), indices)

    override fun plusIntH(handle: Int, indices: LongArray, delta: Long): Long =
        plusIntEntry(entryOf(handle), handleNameForProbe(handle), indices, delta)

    override fun writeIntH(handle: Int, name: String, indices: LongArray, value: Long) =
        writeIntEntry(entryOf(handle), name, indices, value)

    override fun writeStrH(handle: Int, name: String, indices: LongArray, value: String) =
        writeStrEntry(entryOf(handle), name, indices, value)

    /**
     * 该名字的解析结果是否随私有作用域变化（[VariableHandles.nameScoped] 的实现）。
     *
     * 唯一判据是 [privateNames]：里面的名字在语料某个函数里被声明过私有 `#DIM`，
     * 于是「当前帧是谁」会影响解析结果；不在里面的名字（绝大多数系统变量与全局用户
     * 变量）无论从哪个函数访问都解析成同一条目。
     *
     * 与 [bindOf] **共用**这一个函数：读侧的 `VarRef` 站点缓存与写侧的按名字缓存
     * 用同一套语义决定「要不要绑作用域」，两边不可能分叉。`#DIM` 全在装配期落地
     * （见 `GameSession` 的启动序列），执行期集合不再变化。
     */
    private fun nameScopedOf(name: String): Boolean =
        privateNames.isNotEmpty() && privateNames.contains(upperOf(name))

    override fun nameScoped(name: String): Boolean = nameScopedOf(name)

    /**
     * 当前私有作用域的身份 = 当前帧的私有变量表本体。
     *
     * 用表**身份**而不是函数名：没有私有变量的函数（以及顶层）都返回 null，它们的解析
     * 结果相同（只看全局/系统），共用同一个键正合适；有私有变量的函数各自一张表，
     * 身份互不相同。声明新私有变量走 `getOrPut` 复用既有那张表（身份不变）并调
     * [invalidateBindCache]，读侧缓存由 `VarRef` 的 `(reader, scopeKey)` 键与
     * [resolveInt] 的 `bindOf` 各自兜住。
     */
    override fun scopeKey(): Any? = currentPrivMap

    /**
     * [VariableHandles.plusIntH] 的观察口名字。
     *
     * `plusInt` 的 `++/--` 在**系统变量**分支内部会走一次 `writeInt`（观察口随之触发），
     * 而按句柄入口没有源拼写可传。这里退回变量表里的规范名（与按名路径的 `def.name`
     * 同一字符串）—— 大小写敏感名单在 `++/--` 上本来就要写规范名才能命中，
     * 行为与改造前的 `writeInt(name=调用方拼写)` 只在「调用方用了非规范拼写」时有差异，
     * 而那条差异在原实现里同样存在（`watches` 大小写敏感 ⇒ 非规范拼写本就不命中）。
     */
    private fun handleNameForProbe(handle: Int): String =
        when (val e = entryOf(handle)) {
            is UserVar -> e.def.name
            else -> (e as VarDef).name
        }

    // ==================================================================
    // 数组语义
    // ==================================================================

    /** VARSIZE。spec: VariableData.cs:62-74,154-166,202-210；REF 另见 ReferenceToken.cs:32-56 */
    fun getLength(name: String, dimension: Int = -1): Int {
        userVar(name)?.let { v ->
            val rank = v.def.dims.size
            if (v.spec.isRef) {
                // spec: ReferenceToken.cs:32-56 —— 未绑定目标抛 EmptyRefVar，已绑定返回目标数组长度。
                // 旧实现直接返回声明尺寸（REF 恒为 0），VARSIZE 会静默得到 0。
                val ref = v as RefUserVar
                val len = ref.boundLength()
                    ?: throw CodeEE("引用变量 ${v.def.name} 未绑定目标（EmptyRefVar）")
                if (dimension < 0) {
                    if (rank != 1) throw CodeEE("变量 $name 是 $rank 维，必须指定维度")
                    return len
                }
                if (dimension >= rank) {
                    throw CodeEE("变量 $name 不存在第 ${dimension + 1} 维（维度 $rank）")
                }
                // spec: ReferenceToken.cs:45-56 —— 每维长度 = `array.GetLength(dimension)`，
                // 即**目标数组的形状**（不是声明尺寸，REF 声明期恒 0）。形状由绑定点
                // （ProcessRuntime.bindRef → RefUserVar.bindInt/bindStr）随目标一起记录；
                // 原版目标 rank 恒等于声明 rank（MatchType，:163-167），形状与 rank 不符
                // 即绑定不信任，不许按错位形状给值。手工绑定只给了压平数组时，1 维仍按
                // 目标长度返回（旧口径，:32-43 GetLength() = array.Length），多维没有形状
                // 可查 ⇒ 报「长度未知」而不是静默给一个 0。
                val dims = ref.targetDims
                if (dims != null && dims.size == rank) return dims[dimension]
                if (rank == 1) return len
                throw CodeEE(
                    "变量 $name 的第 ${dimension + 1} 维长度未知：引用目标的各维长度未随绑定记录" +
                        "（仅手工绑定未提供形状时可见）",
                )
            }
            if (dimension < 0) {
                if (rank != 1) throw CodeEE("变量 $name 是 $rank 维，必须指定维度")
                return v.def.dims[0]
            }
            return v.def.length(dimension)
        }
        val def = registry.def(name) ?: throw CodeEE("未定义的变量 $name")
        if (def.storage == Storage.PSEUDO) throw CodeEE("伪变量 ${def.name} 没有尺寸")
        if (def.storage == Storage.CSV_CONST) {
            if (dimension > 0) throw CodeEE("变量 ${def.name} 不存在第 ${dimension + 1} 维")
            return def.dims[0]
        }
        if (def.storage == Storage.L_INT || def.storage == Storage.L_STR) {
            val f = topFrame() ?: throw CodeEE("变量 ${def.name} 只能在函数内使用")
            return localSizeOf(f, def)
        }
        // 0 长度数组（IsForbid）的 GetLength 仍返回 0：C# 各 token 的 GetLength 直接返回
        // array.Length（spec: VariableData.cs:25 记 IsForbid = Length == 0，:62-65/:154-166
        // 的 GetLength 本身不抛），元素访问才由 CheckElement 判越界。旧实现抛 CodeEE 会让
        // VARSIZE 与 VARSET 的缺省区间（end = GetLength()）在禁用数组上直接失败。
        if (dimension < 0) {
            if (def.dims.size != 1) throw CodeEE("变量 ${def.name} 是 ${def.dims.size} 维，必须指定维度")
            return def.dims[0]
        }
        // spec: CharaVariableToken.GetLength(dimension) —— dimension 直接索引元素尺寸（不含角色位置）
        return def.length(dimension)
    }

    /** 区间赋值合法性：端点允许等于长度。spec: VariableData.cs:89-100 */
    fun isArrayRangeValid(
        name: String,
        indices: LongArray,
        index1: Long,
        index2: Long,
        funcName: String = "SET",
    ) {
        val def = registry.def(name) ?: throw CodeEE("未定义的变量 $name")
        checkElement(def, indices)
        // 角色变量的区间按第 1 个元素维判定（spec: CharaVariableToken.cs:72-83 用的是 sizes[0]）；
        // 全局 2D/3D 按最后一维（spec: VariableData.cs:185-196 / :289-300）；
        // 局部变量按函数声明的实际长度（spec: LocalVariableToken.cs:47-58）。
        val len = when {
            def.isCharacterData -> def.dims.firstOrNull() ?: throw CodeEE("变量 ${def.name} 不是数组，没有区间")
            def.storage == Storage.L_INT || def.storage == Storage.L_STR -> {
                val f = topFrame() ?: throw CodeEE("变量 ${def.name} 只能在函数内使用")
                localSizeOf(f, def)
            }
            else -> def.dims.last()
        }
        if (index1 < 0 || index1 > len) {
            throw CodeEE("$funcName 的第 1 个区间参数 $index1 越界（变量 ${def.name} 长度 $len）")
        }
        if (index2 < 0 || index2 > len) {
            throw CodeEE("$funcName 的第 2 个区间参数 $index2 越界（变量 ${def.name} 长度 $len）")
        }
    }

    /**
     * 批量赋值：从 indices 指定的位置起连续覆盖。spec: VariableData.cs:38-46
     *
     * **先查 userVar 再查 registry**（A15-E2E 任务 2；spec: e2e diagnosis §1）：与标量
     * 写入口 [writeInt]（:168-173）同序。旧实现只查 `registry.def`，私有/全局 `#DIM`
     * 数组的批量形态（`ARR = 1,2,3`，经 sem StatementExecutor.kt:1212/1216 调入）
     * 一律误报"未定义的变量"。未命中 registry 的报错文案与标量路径保持同口径。
     * 命中 userVar 时逐元素复用 [writeInt] —— 下标个数/越界/REF/常量的检查语义
     * 全部由它自带的 userVar 分支覆盖（checkAndSlot/refElem），行为与原
     * registry 分支对私有变量等价。
     */
    fun setValues(name: String, indices: LongArray, values: LongArray) {
        if (userVar(name) != null) {
            val lastDim = if (indices.isEmpty()) 0 else indices.size - 1
            val base = if (indices.isEmpty()) longArrayOf(0L) else indices
            val start = base[lastDim]
            for (i in values.indices) {
                val idx = base.copyOf()
                idx[lastDim] = start + i
                writeInt(name, idx, values[i])
            }
            return
        }
        val def = registry.def(name) ?: throw CodeEE("未定义的变量 $name")
        if (def.type != VarType.INT) throw CodeEE("变量 ${def.name} 不是整数数组")
        // 原版的数组形态 SetValue(long[]) 只由数组 token 覆写（VariableData.cs:38/125/221/461/572/
        // 1166/1346/…），非数组目标落基类 VariableToken.cs:177-180。整条拒绝发生在任何写入之前，
        // 不留「0 号位先落盘、后续位才报越界」的半写态。
        if (def.storage == Storage.PSEUDO) throw pseudoAssignError(def, wantStr = false, arrayForm = true)
        if (def.storage == Storage.C_INT0) {
            // C_INT0（ISASSI/NO）的 CharaIntVariableToken 未覆写 SetValue(long[])
            // （spec: VariableData.cs:412-441 无该覆写）⇒ 基类 CallNDStrAsInt
            // （zhs xml:352）。旧实现逐元素写：0 号位先落盘、第 2 位起才报越界。
            throw CodeEE("非数值数组变量\"${def.name}\"被当作数值调用")
        }
        if (!def.isWritable) throw CodeEE("变量 ${def.name} 是常量，不能赋值")
        checkElement(def, indices)
        val lastDim = indices.size - 1
        // 起始下标保持 Long 直传：先 toInt 会把 2^32 回绕成 0，后续逐元素 writeInt
        // 的越界检查（LocalVariableToken.cs:39-45 的 long 口径）就永远看不到原始下标。
        val start = indices[lastDim]
        for (i in values.indices) {
            val idx = indices.copyOf()
            idx[lastDim] = start + i
            writeInt(def.name, idx, values[i])
        }
    }

    /** 批量赋值（字符串形态）。userVar 回落口径同 [setValues] 整数重载，复用 [writeStr]。 */
    fun setValues(name: String, indices: LongArray, values: Array<String>) {
        if (userVar(name) != null) {
            val lastDim = if (indices.isEmpty()) 0 else indices.size - 1
            val base = if (indices.isEmpty()) longArrayOf(0L) else indices
            val start = base[lastDim]
            for (i in values.indices) {
                val idx = base.copyOf()
                idx[lastDim] = start + i
                writeStr(name, idx, values[i])
            }
            return
        }
        val def = registry.def(name) ?: throw CodeEE("未定义的变量 $name")
        if (def.type != VarType.STR) throw CodeEE("变量 ${def.name} 不是字符串数组")
        // 原版的数组形态 SetValue(string[]) 只由数组 token 覆写（VariableData.cs:353/532/1253/…）。
        // G_STR0（StrVariableToken）/C_STR0（CharaStrVariableToken）/伪变量（WINDOW_TITLE 等）
        // 都没有数组形态 ⇒ 基类 VariableToken.cs:182-185 抛 CallNDIntAsStr（zhs xml:353）。
        // 旧实现这里逐元素写：0 维目标（SAVEDATA_TEXT/WINDOW_TITLE）的 indices 为空，
        // indices[size-1] 直接 IndexOutOfBoundsException —— ERB `SAVEDATA_TEXT '= "a", "b"`
        // 实测崩溃（2026-10-25）；C_STR0 则是 0 号位先落盘的半写态。整条前移拒绝。
        if (def.storage == Storage.PSEUDO) throw pseudoAssignError(def, wantStr = true, arrayForm = true)
        if (def.storage == Storage.G_STR0 || def.storage == Storage.C_STR0) {
            throw CodeEE("非字符串数组变量\"${def.name}\"被当作字符串调用")
        }
        if (!def.isWritable) throw CodeEE("变量 ${def.name} 是常量，不能赋值")
        checkElement(def, indices)
        val lastDim = indices.size - 1
        // 同整数重载：起始下标不许先 toInt（2^32 回绕成 0）。
        val start = indices[lastDim]
        for (i in values.indices) {
            val idx = indices.copyOf()
            idx[lastDim] = start + i
            writeStr(def.name, idx, values[i])
        }
    }

    /**
     * 伪变量赋值的报错分派表。
     *
     * 原版的「伪变量」由三类 token 组成，赋值报错按 token 类虚派发，本层把三类压进同一个
     * [Storage.PSEUDO] 桶后按 [PseudoKind] 还原：
     * - **ConstantToken** 家族（GAMEBASE_*，spec: VariableData.cs:613-635）→ AssignToConst
     *   （`试图赋值给只读变量"{0}"`，zhs xml:307）；
     * - **直继 VariableToken** 的 WINDOW_TITLE/MONEYLABEL/DRAWLINESTR（spec:
     *   VariableData.cs:943-985）未覆写 SetValue/PlusValue ⇒ 落基类 VariableToken.cs:166-194，
     *   标量形态（[arrayForm] = false）CallStrAsInt/CallIntAsStr（zhs xml:350/:351）、
     *   数组形态（true）CallNDStrAsInt/CallNDIntAsStr（zhs xml:352/:353），按 [wantStr] 分通道；
     * - **PseudoVariableToken** 家族（RAND、CHARANUM、LASTLOAD 族、LINECOUNT、ISTIMEOUT、
     *   INT_MAX 与 INT_MIN、EMUERA_VERSION、FILE 族，spec: VariableData.cs:802-812 的
     *   各 SetValue 与 PlusValue 覆写）→ AssignToPseudoVar（`试图赋值给伪变量"{0}"`，zhs xml:373）。
     *
     * 可达性：本分派**不经 ERB 赋值语句到达** —— 伪变量族的 `VariableCode` 全部带
     * `__UNCHANGEABLE__`(0x400000)（`VariableCode.cs:11`；`CHARANUM = 12713985`、`LINECOUNT =
     * 29491208`、`ISTIMEOUT = 29491211`、`RAND = 13238272` 逐条含该位，见 :155/:208/:209/:154），
     * 因此 `IsConst` 为真（`VariableToken.cs:44`），`SP_SET_ArgumentBuilder` 在**解析期**先报
     * `LeftIsConst`（`ArgumentParser.cs:903-907` + `ArgumentBuilder.cs:19-28` 的 level≥2 ⇒
     * `line.IsError`），`CHARANUM = 5` 一族到不了这里。本守卫服务的是宿主 API / 测试直调
     * 写入口（`setValues` / `writeInt` / `plusInt`），按 `PseudoVariableToken.SetValue`
     * 覆写抛 AssignToPseudoVar 保持原版虚派发口径。
     * WINDOW_TITLE 是唯一可写伪变量：
     * 字符串标量写在 [writeStr] 走 [setPseudoStr]，不进本分派（[plusInt] 的豁免与 [writeInt]
     * 的类型检查各拦一道）；数组写在 [setValues] 落 CallNDIntAsStr 桶（其 token 未覆写数组形态，
     * spec: VariableData.cs:943-958）。旧文案「是常量，不能赋值」「不能进行增量赋值」是本文件
     * 自创口径，与原版虚派发结果不符（SetAddConstTest 的 KDoc 早已引用 AssignToPseudoVar）。
     */
    private fun pseudoAssignError(def: VarDef, wantStr: Boolean, arrayForm: Boolean): CodeEE {
        if (def.pseudo == PseudoKind.GAMEBASE_INT || def.pseudo == PseudoKind.GAMEBASE_STR) {
            return CodeEE("试图赋值给只读变量\"${def.name}\"")
        }
        val windowTitleFamily = def.pseudo == PseudoKind.WINDOW_TITLE ||
            def.pseudo == PseudoKind.MONEYLABEL || def.pseudo == PseudoKind.DRAWLINESTR
        if (windowTitleFamily) {
            if (wantStr) {
                return if (arrayForm) CodeEE("非字符串数组变量\"${def.name}\"被当作字符串调用")
                else CodeEE("非字符串变量\"${def.name}\"被当作字符串调用")
            }
            return if (arrayForm) CodeEE("非数值数组变量\"${def.name}\"被当作数值调用")
            else CodeEE("非数值变量\"${def.name}\"被当作数值调用")
        }
        return CodeEE("试图赋值给伪变量\"${def.name}\"")
    }

    /**
     * 全体赋值。1D 用 [start],[end) 区间；2D/3D 忽略区间、覆盖全部元素
     * （spec: VariableData.cs:48-54,135-146）。角色变量需给 [charaPos]。
     *
     * REF 用户变量的「长度」是**绑定目标**的长度，不是声明尺寸（声明期恒 0）：
     * - 1D：`SetValueAll` 直接按 [start],[end) 写目标数组，end 的缺省值来自
     *   `VariableDest.GetLength()` = 目标数组长度（spec: FunctionIdentifier.cs:1640-1647）；
     * - 2D/3D：`IsArray1D` 为假 ⇒ VARSET 的 end 恒 0，但 SetValueAll 忽略区间、
     *   整块覆盖（spec: VariableData.cs:2421-2436 / :2490-2509）；
     * - 未绑定目标：任何一条路径都先抛 EmptyRefVar（spec: ReferenceToken.cs:32-43）。
     * 旧实现拿声明尺寸 0 当 total：1D 带 end 的 VARSET 必抛区间越界，2D 静默不清零。
     */
    fun setAll(name: String, value: Long, start: Int = 0, end: Int = -1, charaPos: Int = -1) {
        userVar(name)?.let { v ->
            val def = v.def
            if (def.type != VarType.INT) throw CodeEE("变量 $name 不是整数变量")
            if (v.spec.isConst) throw CodeEE("变量 $name 是常量，不能赋值")
            if (v is RefUserVar) {
                // VARSET/VARSETEX 落在 REF 上要**写穿目标数组**（spec: VariableData.cs:2357-2367），
                // 未绑定先抛 EmptyRefVar（:2361）。1D 的 end 缺省 = VariableDest.GetLength()
                // = 绑定目标长度（spec: FunctionIdentifier.cs:1640-1647 + ReferenceToken.cs:32-43），
                // 区间端点按目标长度判界、端点可等于长度
                // （spec: ReferenceToken.cs:78-89 IsArrayRangeValid + VariableEvaluator.cs:271-276）；
                // 2D/3D 的 IsArray1D 为假 ⇒ end 恒 0、跳过区间校验，SetValueAll 忽略 start/end
                // 整块覆盖目标（spec: VariableData.cs:2421-2436 / :2490-2509）。
                // 这里不许改回 getLength(name)：它 dimension<0 时走 ReferenceToken.GetLength()，
                // 多维 REF 会先抛「必须指定维度」（:38-41），与上面的整块覆盖口径冲突。
                val len = v.boundLength()
                    ?: throw CodeEE("引用变量 $name 未绑定目标（EmptyRefVar）")
                if (def.dims.size == 1) {
                    val to = if (end >= 0) end else len
                    checkRangeEndpoints(def, len, start, to)
                    for (e in start until to) v.writeInt(0, e, value)
                } else {
                    for (e in 0 until len) v.writeInt(0, e, value)
                }
                return
            }
            val total = def.elementCount
            val from = if (def.dims.size == 1) start else 0
            val to = if (def.dims.size == 1 && end >= 0) end else total
            // 端点判界同 [checkRangeEndpoints] 一套口径（spec: UserDefinedVariableToken.cs:90-102 ——
            // 仅当端点 ∉ [0, sizes[Dimension-1]] 抛 CodeEE；不比较两端，空区间由 SetValueAll
            // 的 for 循环天然空转，spec: VariableData.cs:48-54）。2D/3D 忽略区间、整块覆盖，
            // from=0/to=total 恒过守卫。
            checkRangeEndpoints(def, total, from, to)
            val charPos = if (def.isCharacterData) {
                if (charaPos < 0 || charaPos >= characters.count) {
                    throw CodeEE("变量 $name 的角色位置 $charaPos 越界（角色数 ${characters.count}）")
                }
                charaPos
            } else {
                0
            }
            for (e in from until to) v.writeInt(charPos, e, value)
            return
        }
        val def = registry.def(name) ?: throw CodeEE("未定义的变量 $name")
        if (def.storage == Storage.PSEUDO) {
            // spec: VariableEvaluator.cs:265-273 —— VARSET 对 IsCalc 变量（伪变量/常量）**静默返回**，
            // 连 WINDOW_TITLE 的整数形态也不动作（字符串形态见 [setAllStr]）。
            return
        }
        if (def.type != VarType.INT) throw CodeEE("变量 ${def.name} 不是整数变量")
        if (!def.isWritable) throw CodeEE("变量 ${def.name} 是常量，不能赋值")
        when (def.storage) {
            Storage.G_INT1 -> {
                val a = intCol1(def.index)
                // 区间守卫与 C_INT1/L_INT/G_STR1 同走 [checkRangeEndpoints]：原版只按端点 ∈
                // [0, array.Length] 判界（spec: VariableData.cs:89-100），不比较两端大小；
                // start>end 在语句层已交换（spec: FunctionIdentifier.cs:1651-1655），token 层
                // SetValueAll 的 `for i = start; i < end` 对空区间天然空转（:48-54）。
                // 旧内联守卫的 `start > to ⇒ 抛` 是本文件独有口径，此处抹平（交换复刻属语句层新工作项）。
                val to = if (end >= 0) end else a.size
                checkRangeEndpoints(def, a.size, start, to)
                if (start < to) a.fill(value, start, to)
            }
            Storage.G_INT2 -> intCol2(def.index).fill(value)
            Storage.G_INT3 -> intCol3(def.index).fill(value)
            Storage.C_INT0 -> {
                if (charaPos < 0 || charaPos >= characters.count) {
                    throw CodeEE("变量 ${def.name} 的角色位置 $charaPos 越界（角色数 ${characters.count}）")
                }
                characters.int0[def.index].set(charaPos, 0, value)
            }
            Storage.C_INT1 -> {
                if (charaPos < 0 || charaPos >= characters.count) {
                    throw CodeEE("变量 ${def.name} 的角色位置 $charaPos 越界（角色数 ${characters.count}）")
                }
                val col = characters.int1[def.index]
                val len = col.sizes[0]
                // spec: VariableEvaluator.cs:271-286 + CharaVariableToken.cs:72-83 ——
                // 区间端点必须在 [0, len] 内（端点可等于长度）；旧实现静默截断、负下界还会漏成
                // IndexOutOfBoundsException，而原版是 CodeEE(OoRInstructionArg)。
                checkRangeEndpoints(def, len, start, end)
                val to = if (end >= 0) end else len
                for (e in start until to) col.set(charaPos, e, value)
            }
            Storage.C_INT2 -> {
                if (charaPos < 0 || charaPos >= characters.count) {
                    throw CodeEE("变量 ${def.name} 的角色位置 $charaPos 越界（角色数 ${characters.count}）")
                }
                characters.int2[def.index].fill(charaPos, value)
            }
            Storage.L_INT -> {
                // 整组写入必须是**首次触碰** LOCAL 的常见形态（`VARSET LOCAL` 开函数）：
                // currentLocal 只查已建的局部表，会把它误判成「不在函数内」。
                val lv = localFor(def)
                val arr = lv.ints ?: LongArray(lv.size).also { lv.ints = it }
                val to = if (end >= 0) end else lv.size
                // 区间端点必须在 [0, len]（端点可等于长度，spec: LocalVariableToken.cs:47-58）；
                // 旧实现直接 arr.fill，负下界/超上界漏成 IndexOutOfBoundsException 而非 CodeEE。
                checkRangeEndpoints(def, lv.size, start, to)
                if (start < to) arr.fill(value, start, to)
            }
            else -> throw CodeEE("变量 ${def.name} 不能整体赋值")
        }
    }

    fun setAllStr(name: String, value: String, start: Int = 0, end: Int = -1, charaPos: Int = -1) {
        userVar(name)?.let { v ->
            if (v.def.type != VarType.STR) throw CodeEE("变量 $name 不是字符串变量")
            if (v.spec.isConst) throw CodeEE("变量 $name 是常量，不能赋值")
            // REF 的区间/全量口径同 [setAll]：1D 用绑定长度收窄区间，2D/3D 整块覆盖
            // （spec: VariableData.cs:2563-2573 / :2617-2631 / :2676-2694，未绑定 :2567 抛
            // EmptyRefVar；end 缺省取绑定长度 spec: FunctionIdentifier.cs:1640-1647 +
            // VariableEvaluator.cs:289-314；端点判界 spec: ReferenceToken.cs:78-89）。
            if (v is RefUserVar) {
                val len = v.boundLength()
                    ?: throw CodeEE("引用变量 $name 未绑定目标（EmptyRefVar）")
                if (v.def.dims.size == 1) {
                    val to = if (end >= 0) end else len
                    checkRangeEndpoints(v.def, len, start, to)
                    for (e in start until to) v.writeStr(0, e, value)
                } else {
                    for (e in 0 until len) v.writeStr(0, e, value)
                }
                return
            }
            val total = v.def.elementCount
            val from = if (v.def.dims.size == 1) start else 0
            val to = if (v.def.dims.size == 1 && end >= 0) end else total
            // 同 [setAll] 非 REF 分支：端点判界走 [checkRangeEndpoints] 一套口径
            // （spec: UserDefinedVariableToken.cs:90-102 + VariableData.cs:48-54，空区间空转）。
            checkRangeEndpoints(v.def, total, from, to)
            val pos = if (v.def.isCharacterData) {
                if (charaPos < 0 || charaPos >= characters.count) {
                    throw CodeEE("变量 $name 的角色位置 $charaPos 越界（角色数 ${characters.count}）")
                }
                charaPos
            } else {
                0
            }
            for (e in from until to) v.writeStr(pos, e, value)
            return
        }
        val def = registry.def(name) ?: throw CodeEE("未定义的变量 $name")
        if (def.storage == Storage.PSEUDO) {
            // spec: VariableEvaluator.cs:289-296 —— IsCalc 变量只对 WINDOW_TITLE 生效（设置窗口标题），
            // 其余（MONEYLABEL/DRAWLINESTR/EMUERA_VERSION/…）**静默返回**而不是抛错。
            if (def.pseudo == PseudoKind.WINDOW_TITLE) setPseudoStr(def, value)
            return
        }
        if (def.type != VarType.STR) throw CodeEE("变量 ${def.name} 不是字符串变量")
        if (!def.isWritable) throw CodeEE("变量 ${def.name} 是常量，不能赋值")
        val id = pool.intern(value)
        when (def.storage) {
            Storage.G_STR0 -> gStr0[0] = id
            Storage.G_STR1 -> {
                val a = gStr1[def.index]
                // spec: VariableEvaluator.cs:299-314 —— 非整组写入先过 IsArrayRangeValid 再 SetValueAll；
                // Str1D 的 SetValueAll 按下标直写（VariableData.cs:363-369），越界必须在此之前抛 CodeEE。
                checkRangeEndpoints(def, a.size, start, end)
                val to = if (end >= 0) end else a.size
                if (start < to) a.fill(id, start, to)
            }
            Storage.C_STR0 -> {
                if (charaPos < 0 || charaPos >= characters.count) {
                    throw CodeEE("变量 ${def.name} 的角色位置 $charaPos 越界（角色数 ${characters.count}）")
                }
                characters.str0[def.index].set(charaPos, 0, id)
            }
            Storage.C_STR1 -> {
                if (charaPos < 0 || charaPos >= characters.count) {
                    throw CodeEE("变量 ${def.name} 的角色位置 $charaPos 越界（角色数 ${characters.count}）")
                }
                val col = characters.str1[def.index]
                val len = col.sizes[0]
                // 同 [setAll]：spec: VariableEvaluator.cs:289-314 + CharaVariableToken.cs:72-83
                checkRangeEndpoints(def, len, start, end)
                val to = if (end >= 0) end else len
                for (e in start until to) col.set(charaPos, e, id)
            }
            Storage.L_STR -> {
                // 同 setAll：VARSET/VARSETS 可能是函数内首次触碰 LOCAL/LOCALS。
                val lv = localFor(def)
                val arr = lv.strs ?: IntArray(lv.size).also { lv.strs = it }
                val to = if (end >= 0) end else lv.size
                // 同整数侧：端点按 [0, len] 判界并抛 CodeEE（spec: LocalVariableToken.cs:47-58）。
                checkRangeEndpoints(def, lv.size, start, to)
                if (start < to) arr.fill(id, start, to)
            }
            else -> throw CodeEE("变量 ${def.name} 不能整体赋值")
        }
    }

    /**
     * 角色变量 1 维区间端点检查（端点可等于长度）。
     * spec: VariableEvaluator.cs:271-314 的 `IsArrayRangeValid` + CharaVariableToken.cs:72-83。
     */
    private fun checkRangeEndpoints(def: VarDef, len: Int, start: Int, end: Int) {
        if (start < 0 || start > len) {
            throw CodeEE("变量 ${def.name} 的区间起点 $start 越界（长度 $len）")
        }
        if (end > len) {
            throw CodeEE("变量 ${def.name} 的区间终点 $end 越界（长度 $len）")
        }
    }

    // ==================================================================
    // 角色库
    // ==================================================================

    /**
     * 注册角色模板（CSV 装载期）。同 NO 重复时保留**首次**出现的定义：
     * 原版 `GetCharacterTemplate` 对 CharacterTmplList 线性扫描、命中即返回
     * （spec: ConstantData.cs:1218-1228），即列表里第一条。
     */
    fun registerTemplate(template: CharacterTemplate) {
        templatesByNo.putIfAbsent(template.no, template)
        if (template.name.isNotEmpty()) templatesByName.putIfAbsent(template.name, template)
        if (template.callname.isNotEmpty()) templatesByName.putIfAbsent(template.callname, template)
        if (template.nickname.isNotEmpty()) templatesByName.putIfAbsent(template.nickname, template)
    }

    fun clearTemplates() {
        templatesByNo.clear()
        templatesByName.clear()
    }

    fun template(no: Long): CharacterTemplate? = templatesByNo[no]

    /**
     * 按名字查角色模板（`name` / `callname` / `nickname` 三个键，先到先得，
     * 见 [registerTemplate]）。消费者是 `RELATION:MASTER:名字` 的关键字反查
     * （运行期 [keywordToInteger]，装载期符号审计用它判「模板名是否存在」）。
     */
    fun templateByName(name: String): CharacterTemplate? = templatesByName[name]

    /** ADDCHARA：按 NO 查模板，未命中则 CodeEE。spec: VariableEvaluator.cs:1201-1210 */
    fun addCharacter(no: Long): Int {
        val tmpl = templatesByNo[no] ?: throw CodeEE("ADDCHARA 失败：未定义的角色 NO $no")
        return addCharacter(tmpl)
    }

    /** ADDCHARA 的 CSV 号变体：未命中则伪角色。spec: VariableEvaluator.cs:1223-1232 */
    fun addCharacterFromCsvNo(csvNo: Long): Int {
        val tmpl = templatesByNo.values.firstOrNull { it.csvNo == csvNo } ?: CharacterTemplate.pseudo(csvNo)
        return addCharacter(tmpl)
    }

    /** 追加伪角色（ADDVOIDCHARA）。 */
    fun addPseudoCharacter(): Int = addCharacter(CharacterTemplate.pseudo())

    /**
     * 按模板新建角色。字段拷贝顺序照 `CharacterData(constant, tmpl, varData)`
     * （spec: CharacterData.cs:128-192）：Maxbase 双写 BASE/MAXBASE，RELATION 先全填默认再覆盖。
     */
    fun addCharacter(template: CharacterTemplate): Int {
        val pos = characters.addBare()
        val c = characters

        c.int0[VariableCode.lowIndex(VariableCode.NO_VAR)].set(pos, 0, template.no)
        c.str0[0].set(pos, 0, pool.intern(template.name))
        c.str0[1].set(pos, 0, pool.intern(template.callname))
        c.str0[2].set(pos, 0, pool.intern(template.nickname))
        c.str0[3].set(pos, 0, pool.intern(template.mastername))

        val base = c.int1[VariableCode.lowIndex(VariableCode.BASE)]
        val maxbase = c.int1[VariableCode.lowIndex(VariableCode.MAXBASE)]
        for ((k, v) in template.maxbase) {
            base.set(pos, k, v)
            maxbase.set(pos, k, v)
        }
        for ((k, v) in template.mark) c.int1[VariableCode.lowIndex(VariableCode.MARK)].set(pos, k, v)
        for ((k, v) in template.exp) c.int1[VariableCode.lowIndex(VariableCode.EXP)].set(pos, k, v)
        for ((k, v) in template.abl) c.int1[VariableCode.lowIndex(VariableCode.ABL)].set(pos, k, v)
        for ((k, v) in template.talent) c.int1[VariableCode.lowIndex(VariableCode.TALENT)].set(pos, k, v)

        c.fillRelation(pos)
        for ((k, v) in template.relation) c.int1[VariableCode.lowIndex(VariableCode.RELATION)].set(pos, k, v)

        for ((k, v) in template.cflag) c.int1[VariableCode.lowIndex(VariableCode.CFLAG)].set(pos, k, v)
        for ((k, v) in template.equip) c.int1[VariableCode.lowIndex(VariableCode.EQUIP)].set(pos, k, v)
        for ((k, v) in template.juel) c.int1[VariableCode.lowIndex(VariableCode.JUEL)].set(pos, k, v)
        for ((k, v) in template.cstr) {
            c.str1[VariableCode.lowIndex(VariableCode.CSTR)].set(pos, k, pool.intern(v))
        }
        return pos
    }

    /**
     * DELCHARA 的**单实参**重载：越界报**具体编号**，随后该位置起整体左移。
     *
     * spec: VariableEvaluator.cs:1241-1249（方法体三句：`:1243` 越界判定 →
     * `:1247` `CharacterList[(int)charaNo].Dispose()` → `:1248` `RemoveAt((int)charaNo)`）。
     * 选型在**语句层**（FunctionIdentifier.cs:1387-1390 `array.Length == 1`），
     * 见 `core/sem/StatementExecutor.kt` 的 DELCHARA 分支；本入口只承载单参语义。
     *
     * 文案原文 = `Lang.Error.OoRDelChara`（注册 Lang.cs:1438；zhs 表形
     * `MinorShift.Emuera.Properties.lang.emuera-zhs.xml:314` =
     * `试图删除不存在的注册角色({0})`），`:1245` 的 format 实参是
     * `charaNo.ToString()` ⇒ **带越界的那个编号本身**（与数组参重载 [deleteCharacters]
     * 的读数不同，那条编号丢失）。
     *
     * `:1247` 的 `Dispose()`（CharacterData.cs:756-773）只把**本实例**的
     * dataIntegerArray / dataStringArray / dataIntegerArray2D 置 null，不写任何共享状态、
     * 也不动 CharacterList；移植侧角色数据是 SoA 列存（CharacterTable），一行角色没有
     * 「实例私有数组」可置 null ⇒ 这条 Dispose 在成功路径无可对应动作，删行即其全部效果。
     */
    fun deleteCharacter(pos: Long) {
        if (pos < 0 || pos >= characters.count) {
            // spec: VariableEvaluator.cs:1245 + lang.emuera-zhs.xml:314；{0} = charaNo.ToString()
            throw CodeEE("试图删除不存在的注册角色(" + pos.toString() + ")")
        }
        characters.removeAt(pos.toInt())
    }

    /**
     * DELCHARA 的**数组实参**重载（单一语义入口：0 个或 ≥2 个实参）。
     *
     * ## 分流在语句层，不在本函数（FunctionIdentifier.cs:1344-1397，ADDCHARA/ADDSPCHARA/
     * DELCHARA 同一个类，三个注册位 `:3943/:3944/:3947`，DELCHARA = `flagDel: true`）
     * - `:1352` `ArgBuilder = INT_ANY`、`:1353` `flag = 4` ⇒ 实参任意个数，全部收成
     *   `ExpressionArrayArgument.TermList`；
     * - isDel 分支 `:1371-1375` 只求值收集进 `array`，**不即时删**；
     * - `:1385-1394` 按**实参个数**选型：`array.Length == 1` → `DelCharacter(array[0])`（单参），
     *   否则（含 0 个）→ `DelCharacter(array)`（本函数）。
     * 这个分岔原版就在语句层 ⇒ 移植侧同层复刻（`core/sem/StatementExecutor.kt` 的 DELCHARA
     * 分支）。本函数**只**承载数组参语义：即便传进来的数组只有 1 个元素，也按
     * `VariableEvaluator.cs:1251-1273` 的数组参路径走（越界丢编号），不会自己转成单参。
     * 单参语义见 [deleteCharacter]。
     *
     * ## 官方 bug 复刻（ADR-005，禁止顺手修好）
     * 原版数组参重载越界那行 `VariableEvaluator.cs:1259` 的 format 实参写的是
     * **`charaNoList.ToString()`（数组对象本身）**，不是出错的那个 `num` ⇒
     * CLR 上数组的 `ToString()` 只返回类型全名 ⇒ 上屏
     * `试图删除不存在的注册角色(System.Int64[])`，**编号丢失**。
     * 对照 `:1245` 单参版传 `charaNo.ToString()` ⇒ 两条重载的文案**不同**。
     * Kotlin 侧 `LongArray.toString()` 渲染成 `[J@<hash>`，与 .NET 不同形 ⇒ 直接写死
     * .NET 的等价固定串 `System.Int64[]`（long 数组的类型全名，与元素个数、内容无关）。
     *
     * ## 判重依据：按**对象**，不是按位置（`VariableEvaluator.cs:1262-1265`）
     * `if (list.Contains(characterData))` 判的是「同一个 CharacterData 实例被二次指定」。
     * `CharacterData` 未覆写 `Equals`/`GetHashCode`（该类只有 `Dispose` 于 `:756`，全类无
     * Equals 覆写）⇒ `List.Contains` 走引用相等。原版 CharacterList 的每个元素都由
     * `new CharacterData(...)` 产生（`:1209/:1220/:1231/:1238/:2315(经 :2310 new)`/
     * `:2449/:2634`；`COPYCHARA` 是 `CopyTo` 值拷贝 :1366，不搬实例），**可达状态里不存在
     * 两个位置同实例** ⇒ 移植侧继续按位置判重（SoA 表没有实例可比），在可达输入上与原版
     * 读数一致；差异登记于此 + `TODO(verify)`（见下）。
     * 文案 = `Lang.Error.DuplicateDelChara`（Lang.cs:1442；zhs 表形
     * `lang.emuera-zhs.xml:315` = `相同的注册角色编号({0})被重复指定`），`:1264` 的
     * format 实参是 `num.ToString()` ⇒ **带编号**（与越界那条不同，这条原版没写坏）。
     *
     * ## 校验/删除次序（`:1253-1273` 读完的结论）
     * 原版是「**边校验边 Dispose、校验全过后才 Remove**」：
     * `:1253` 建空 `list` → `:1254-1268` 逐元素〔`:1257` 越界（对**删除前**的 `Count`）→
     * `:1261` 取实例 → `:1262` 判重 → `:1266` 入 `list` → `:1267` `Dispose()〕` →
     * `:1269-1272` `foreach (item in list) CharacterList.Remove(item)`。
     * 由此：
     * 1. **成功路径两侧等价**——`Remove` 按实参顺序逐个摘除（各元素互异，`Remove` 摘到的
     *    就是那个实例当前所在的下标），本侧按位置**降序** `removeAt`；从同一个列表里摘掉
     *    同一组互异元素，**余下元素的相对顺序与摘除次序无关** ⇒ 终态逐行相同。
     *    「删一半时列表被别处读到」的中间态不可观测（本函数内无回调、无求值），
     *    故不需要把摘除次序改成实参顺序。
     * 2. **失败路径：半死态取「不可观测」处置（B），不改表示**——原版在第 k 个元素抛
     *    CodeEE 时，前 k 个元素**已经 Dispose 但仍在表内**（`Remove` 循环在 `:1269`，
     *    抛错时不可达），后续读这些角色会踩被置 null 的私有数组（NullReferenceException）。
     *    但这条半死态在原版**没有任何可观测出口**：
     *    - `Dispose`（CharacterData.cs:756-770）只把本实例的 dataIntegerArray /
     *      dataStringArray / dataIntegerArray2D 元素置 null，**不写任何共享状态**、
     *      不动 `CharacterList` 的 Count 与顺序 ⇒ 异常本身携带的信息（:1259/:1264 的文案）
     *      与抛错瞬间的 CHARANUM 都与「未 Dispose」完全一致；
     *    - 异常从 `DoInstruction`（Process.cs:1047）一路穿出 `runScriptProc`，由
     *      `DoScript` 的顶层 catch（Process.cs:462-476）交给 `handleException`
     *      （Process.cs:595-643）打印；`console.ThrowError`（EmueraConsole.cs:757-768）
     *      把状态置 `ConsoleState.Error`，而 `RunEmueraProgram`（EmueraConsole.cs:1090-1100）
     *      只在 `state != ConsoleState.Error` 时才再调 `DoScript` ⇒ **同一会话里不会再有
     *      下一条语句去读那半个表**；
     *    - 回标题走 `GotoTitle`（EmueraConsole.cs:2570-2578）→ `BeginTitle`
     *      （Process.cs:480-485）→ `vEvaluator.ResetData()`，半死表整体丢弃。
     *    故本侧「校验期零副作用」（抛错后角色完好、照常可读）在可达路径上与原版**逐项同读**。
     *    TODO(verify): 仅 `Program.DebugMode` 的调试命令行（EmueraConsole.cs:2166-2252）
     *    能在 Error 态单条求值、可能踩到被置 null 的数组；该面本移植未实现，若日后接线
     *    调试控制台需重评（届时按 A/C 处置）。
     * 3. `:1257` 的越界判定用 long 与 `Count` 比（不先 `(int)` 截断），本侧同形；
     *    `(int)` 截断只发生在**通过判定之后**取元素时（`:1261`）。
     *
     * 空数组（DELCHARA 零实参）：`:1254` 循环零次、`:1269` 空转 ⇒ **整体 no-op**，本侧同。
     */
    fun deleteCharacters(positions: LongArray) {
        // 对应原版 :1253 的 List<CharacterData> list；本侧按位置收集（判重口径差异见 KDoc）
        val seen = ArrayList<Int>(positions.size)
        for (p in positions) {
            if (p < 0 || p >= characters.count) {
                // 官方 bug 复刻：VariableEvaluator.cs:1259 传 charaNoList.ToString()
                // ⇒ .NET 上屏固定串 System.Int64[]、编号丢失（对照 :1245 单参版带编号）。
                // 文案骨架 = lang.emuera-zhs.xml:314。ADR-005：不许「顺手修好」。
                throw CodeEE("试图删除不存在的注册角色(System.Int64[])")
            }
            val i = p.toInt()
            if (seen.contains(i)) {
                // spec: VariableEvaluator.cs:1262-1265 + lang.emuera-zhs.xml:315；{0} = num.ToString()
                throw CodeEE("相同的注册角色编号(" + p.toString() + ")被重复指定")
            }
            seen.add(i)
        }
        // 降序按位删除；与原版「先整体 Dispose 再按对象 Remove」的成功路径等价性论证见 KDoc
        for (i in seen.sortedDescending()) characters.removeAt(i)
    }

    fun deleteAllCharacters() = characters.clear()

    /**
     * COPYCHARA。spec: VariableEvaluator.cs:1355-1366 —— `CopyChara(x, y)` 是
     * `CharacterList[x].CopyTo(CharacterList[y])`：**x = 源（from）**，y = 目标（to）。
     *
     * 形参名与内部调用名相反是历史噪声，方向以本注释为准：
     * `CharacterTable.copy(dst, src)`（CharacterTable.kt:170）首形参是 **目标**，
     * 故本函数的 `characters.copy(to, from)` = 「把 from 复制到 to」，与
     * [copyCharacter] 的 `from`/`to` 命名一致、也与原版一致。
     * 两句校验都用 long 与 CharacterList.Count 比较（:1357-1364），先 toInt 会把 2^32
     * 回绕成 0 静默自拷贝；文案 = Lang.Error.NotExistFromCopyChara / NotExistToCopyChara
     * （lang.emuera-zhs.xml:316-317）。ADDCOPYCHARA 走 [addCopyCharacter]，不复用本函数。
     */
    fun copyCharacter(from: Long, to: Long) {
        if (from < 0 || from >= characters.count) {
            throw CodeEE("复制的原角色不存在")
        }
        if (to < 0 || to >= characters.count) {
            throw CodeEE("复制的目标角色不存在")
        }
        characters.copy(to.toInt(), from.toInt())
    }

    /**
     * ADDCOPYCHARA。spec: VariableEvaluator.cs:1368-1376 —— `AddCopyChara(x)` 的 x 是
     * **源**下标：先按 long 判界（NotExistFromCopyChara，文案同 [copyCharacter] 的 from 侧，
     * lang.emuera-zhs.xml:316），**再** AddPseudoCharacter 追加伪角色，最后把 x 号复制到
     * 追加出的末位（:1375 `CharacterList[x].CopyTo(CharacterList[Count-1])`）。
     * 源越界时抛错且**不追加**任何角色（判界在 :1374 的追加之前）。
     */
    fun addCopyCharacter(src: Long): Int {
        if (src < 0 || src >= characters.count) {
            throw CodeEE("复制的原角色不存在")
        }
        val pos = addPseudoCharacter()
        characters.copy(pos, src.toInt())
        return pos
    }

    /**
     * SWAPCHARA。spec: VariableEvaluator.cs:1378-1395 —— 两个位置用同一条越界判定
     * 一起按 long 判界（:1380，先判后截），`x == y` 时不动（:1384）。
     * 文案 = Lang.Error.OoRSwapChara（lang.emuera-zhs.xml:318）。
     */
    fun swapCharacters(a: Long, b: Long) {
        if (a < 0 || a >= characters.count || b < 0 || b >= characters.count) {
            throw CodeEE("试图替换一个不存在的注册角色")
        }
        characters.swap(a.toInt(), b.toInt())
    }

    // ---- 角色指针（MASTER/TARGET/ASSI）----

    /**
     * 角色指针读取。数组被 VariableSize.CSV 禁成 0 长时返回 **-1** 而不是抛错。
     *
     * 原版 MASTER/TARGET/ASSI 的求值器属性走 `get_Variable_canforbid`
     * （spec: VariableEvaluator.cs:2728-2736：`array.Length == 0` → return -1），
     * SORTCHARA（:1414-1426）与 PICKUPCHARA（:1290-1293）读的就是这条属性；
     * 脚本层直接写 `TARGET:0` 才走 token 的 CheckElement 抛 OoR。本侧
     * [sortCharacters] / [pickUpCharacters] 对应原版求值器内读法，故同口径。
     */
    internal fun charaPointer(name: String): Long {
        if (registry.mustDef(name).isForbid) return -1L
        return readInt(name, longArrayOf(0L))
    }

    /** 角色指针写入。数组禁用（0 长）时**静默丢弃**。spec: VariableEvaluator.cs:2738-2745 */
    internal fun setCharaPointer(name: String, value: Long) {
        if (registry.mustDef(name).isForbid) return
        writeInt(name, longArrayOf(0L), value)
    }

    /**
     * PICKUPCHARA 的实体操作：把 [noList] 里的角色按给定顺序挪到表头，其余删除；
     * MASTER/TARGET/ASSI 重绑到各自角色对象的新位置，没被选中的指针归 -1。
     *
     * spec: VariableEvaluator.cs:1288-1353（PickUpChara），语句层实参校验在
     * Process.cs:1267-1283。逐句对应：
     * - `:1290-1293` 先把三个指针的**旧值**抄下来，随即全部写 -1 —— 所以没被选中的
     *   角色即使原本被 TARGET 指着，PICKUPCHARA 之后指针也是 -1（不是保持旧值）；
     * - `:1295-1301` 收集待选下标：**去重**（`list.Contains`）且**跳过负数**
     *   （`NoList[i] >= 0`）—— 语句层豁免的 MASTER/ASSI/TARGET 引用可以合法取 -1；
     * - `:1303-1317` 逐位交换就位（`SwapChara(list[j], j)` + 把后面等于 j 的表项改写成
     *   被换出去的位置），并按「表项值 == 指针旧值」重绑三个指针（各自只绑一次，
     *   `TARGET < 0` 保证；指针旧值本身为 -1 时永不命中）；
     * - `:1319-1326` 余下角色从表尾**降序**逐个删除（等价于整段截断）。
     *
     * 与原版的表示差异（登记）：`:1290-1292` 抄的是三个独立变量，本侧依次读同一数组；
     * `:1307` `list.IndexOf(j)` 找**首个**等于 j 的表项，本侧 [List.indexOf] 同形。
     */
    fun pickUpCharacters(noList: LongArray) {
        val oldTarget = charaPointer("TARGET")
        val oldAssi = charaPointer("ASSI")
        val oldMaster = charaPointer("MASTER")
        setCharaPointer("TARGET", -1L)
        setCharaPointer("ASSI", -1L)
        setCharaPointer("MASTER", -1L)
        // :1295-1301 的 List<long> list —— 去重 + 跳负数
        val picked = ArrayList<Long>(noList.size)
        for (v in noList) {
            if (v >= 0 && !picked.contains(v)) picked.add(v)
        }
        // :1303-1317 交换就位 + 指针重绑
        for (j in picked.indices) {
            if (picked[j] != j.toLong()) {
                swapCharacters(picked[j], j.toLong())
                val k = picked.indexOf(j.toLong())
                if (k > j) picked[k] = picked[j]
            }
            if (charaPointer("TARGET") < 0 && picked[j] == oldTarget) setCharaPointer("TARGET", j.toLong())
            if (charaPointer("ASSI") < 0 && picked[j] == oldAssi) setCharaPointer("ASSI", j.toLong())
            if (charaPointer("MASTER") < 0 && picked[j] == oldMaster) setCharaPointer("MASTER", j.toLong())
        }
        // :1319-1326 尾段降序删除
        if (picked.size < characters.count) {
            for (num in characters.count - 1 downTo picked.size) {
                deleteCharacter(num.toLong())
            }
        }
    }

    /**
     * SORTCHARA（int 键）：键相同保持原顺序（稳定）。
     * spec: CharacterData.cs:772-790（Asc/DescCharacterComparison：先比 temp_SortKey，
     * 相等再比 temp_CurrentOrder → 稳定）、VariableEvaluator.cs:1397-1467（SortChara）。
     *
     * @param elementIndices 除角色位置外的下标（如 CFLAG 的 flag 号）
     */
    fun sortCharactersByInt(name: String, elementIndices: LongArray, ascending: Boolean = true) {
        sortCharacters({ i -> readInt(name, longArrayOf(i.toLong()) + elementIndices) }, ascending)
    }

    /** 按字符串变量排序（CALLNAME 等）。同 [sortCharactersByInt]。 */
    fun sortCharactersByStr(name: String, elementIndices: LongArray, ascending: Boolean = true) {
        sortCharacters({ i -> readStr(name, longArrayOf(i.toLong()) + elementIndices) }, ascending)
    }

    /**
     * SORTCHARA 共用骨架。spec: VariableEvaluator.cs:1397-1467。
     *
     * 关键点（原版 `fixMaster: true`，唯一调用点在 FunctionIdentifier.cs:1487）：
     * - MASTER 合法（`0 <= MASTER < CHARANUM`）时把主人公角色**摘出**、只排序其余角色、
     *   再插回 MASTER 位置（:1414-1417 / :1431-1450）→ 主人公位置不动；
     * - 此时若 CHARANUM <= 2 直接返回（:1433-1436）；
     * - 排序后 TARGET / ASSI 重新指向各自角色对象的新位置（:1451-1466）；
     *   MASTER 因 fixMaster 不参与重定位（:1455-1458 的 `!fixMaster` 分支）。
     *
     * MASTER 的合法性判定留在 **Long 域**（:1414 `MASTER >= 0 && MASTER < Count`，
     * 两侧都是 long）：先 toInt 会把 2^32 回绕成 0，把「无主人公」（超大负/正占位值）
     * 误判成钉住 0 号位 —— 与 :1459-1466 的 TARGET/ASSI 重定位同一口径（见
     * [remapGlobalPosition]）。
     */
    private fun <K : Comparable<K>> sortCharacters(keyOf: (Int) -> K, ascending: Boolean) {
        val n = characters.count
        if (n <= 1) return
        val masterPos = charaPointer("MASTER")
        val pin = masterPos in 0 until n
        if (pin && n <= 2) return
        val keys = List(n) { keyOf(it) }
        val cmp = Comparator<Int> { a, b ->
            val c = keys[a].compareTo(keys[b])
            if (c != 0) (if (ascending) c else -c) else a.compareTo(b) // temp_CurrentOrder → 稳定
        }
        val rest = (0 until n).filter { !pin || it.toLong() != masterPos }.sortedWith(cmp)
        // order[新位置] = 旧位置：MASTER 钉在 masterPos，其余按排序结果依次填。
        val order = IntArray(n)
        var src = 0
        for (pos in 0 until n) {
            order[pos] = if (pin && pos.toLong() == masterPos) masterPos.toInt() else rest[src++]
        }
        characters.reorder(order)
        remapGlobalPosition("TARGET", order)
        remapGlobalPosition("ASSI", order)
    }

    /**
     * 排序后把全局角色位置变量重新指向同一角色对象的新位置。spec: VariableEvaluator.cs:1459-1466
     * （`characterData2 != null` 才写 —— 即旧值落在 `0 <= TARGET < Count` 才重定位，
     * 长口径见 :1419-1426；数组禁用时 [charaPointer] 的 -1 同样不命中）。
     */
    private fun remapGlobalPosition(name: String, order: IntArray) {
        val old = charaPointer(name)
        if (old < 0 || old >= order.size) return
        for (newPos in order.indices) {
            if (order[newPos].toLong() == old) {
                setCharaPointer(name, newPos.toLong())
                return
            }
        }
    }

    // ==================================================================
    // 用户变量（#DIM）
    // ==================================================================

    /**
     * 声明用户变量。私有变量（函数内 #DIM）以 [functionKey] 隔离实例。
     *
     * [functionKey] 约定用符号表的 `LabelDef.key`（IGNORE CASE 时已是 uppercase）：
     * 原版 `FunctionLabelLine.privateVar` 用 `Config.StrComper`（spec: FunctionLabelLine.cs:17），
     * 即 `@Foo` 与 `@FOO` 必须是同一个私有作用域。
     *
     * 重名标签：**只给胜者定义注册**（装载期被覆盖的定义不会被 CALL 到）；
     * 同一 key 下同名再声明会抛"重复定义"，不要灌入被遮蔽定义的 #DIM。
     *
     * ## 调用时机：必须在 VM 执行前完成（执行期冻结）
     *
     * 本方法（经 `GameSession` / `SystemVariableBootstrap` / `BootMetadata`）只发生在启动
     * 序列里，VM 起跑后 `privateNames` 与全部句柄条目即冻结。两级缓存的正确性都踩在这条
     * 不变式上：`bindOf` 的 [nameScopedOf] 判据与读侧 `VarRef` 站点缓存的 `nameScoped`
     * 答案都是**绑定当时**取的 —— 若执行期再声明同名私有变量（先按全局解析缓存、后进
     * 私有帧），站点缓存不会重读判据，会静默读到全局实例。测试假体手工模拟声明时同样
     * 必须守「先声明、后求值」的顺序（`VarBindScopeTest` 的用例全部如此排列）。
     */
    fun declareUserVar(spec: DimSpec, functionKey: String? = null): UserVar {
        val key = spec.name.uppercase()
        if (registry.def(key) != null) throw CodeEE("变量名 ${spec.name} 与系统变量重名")
        checkUserVarShape(spec)
        if (spec.isRef && !spec.isPrivate) {
            // 原版对非私有 REF #DIM 的可观测行为是装载失败：ERH 装载对任何 Reference 声明
            // 直接抛 NotImplCodeEE（ErhLoader.cs:262-265，先于 :267 的建表调用）；REF 与
            // GLOBAL 关键字在解析期即互斥（UserDefinedVariableData.cs:99-102）。全局建表处
            // VariableData.cs:3246 CreateUserDefVariable 不查 Reference，其对 REF 理论上的
            // Static 0 长形态（REF 声明尺寸恒 0，UserDefinedVariableData.cs:309-311）是不可达
            // 死代码。NotImplCodeEE 文案 =「该功能不能在现版本使用」（NotImplCodeEE.cs:5-15，
            // lang.emuera-zhs.xml:824；specs/variables.yaml scoping.ref 同口径）。
            throw CodeEE("该功能不能在现版本使用")
        }
        if (spec.isPrivate) {
            val fk = (functionKey ?: throw CodeEE("函数内变量 ${spec.name} 缺少所属函数")).uppercase()
            val map = privateVars.getOrPut(fk) { LinkedHashMap() }
            if (map.containsKey(key)) throw CodeEE("变量名 ${spec.name} 在函数 $fk 内重复定义")
            val def = spec.toVarDef(0, chara = false, code = userCode(spec, chara = false))
            val v = createUserVar(spec, def, chara = false)
            map[key] = v
            // 这个名字从此「可能私有」⇒ 绑定缓存里它的解析结果必须绑作用域（见 bindOf）。
            privateNames.add(key)
            // 新私有变量可能把「此前解析为不存在」的名字变成存在 ⇒ 作废单条记忆与绑定缓存。
            invalidateUserVarMemo()
            invalidateBindCache()
            refreshPrivMap()
            return v
        }
        if (userVars.containsKey(key)) throw CodeEE("变量名 ${spec.name} 重复定义")

        val chara = spec.isCharaData
        val def: VarDef
        val v: UserVar
        if (chara) {
            val dims = spec.dims
            if (spec.isStr) {
                val col = characters.appendStrColumn(dims)
                def = spec.toVarDef(col, chara = true, code = userCode(spec, chara = true))
                v = CharaStrUserVar(spec, def, characters, col, pool)
            } else {
                val col = characters.appendIntColumn(dims)
                def = spec.toVarDef(col, chara = true, code = userCode(spec, chara = true))
                v = CharaIntUserVar(spec, def, characters, col)
            }
        } else {
            def = spec.toVarDef(0, chara = false, code = userCode(spec, chara = false))
            v = createUserVar(spec, def, chara = false)
        }
        userVars[key] = v
        // 原文名索引（见 userVarsRaw 的 KDoc）：让「按声明写法访问」只查一次表。
        userVarsRaw[def.name] = v
        invalidateUserVarMemo()
        invalidateBindCache()
        if (spec.isGlobal) globalUserVars[key] = v
        if (spec.isSavedata) savedUserVars[key] = v
        return v
    }

    private fun createUserVar(spec: DimSpec, def: VarDef, chara: Boolean): UserVar = when {
        spec.isRef -> RefUserVar(spec, def, pool)
        spec.isStr && (!spec.isStatic) -> DynamicStrUserVar(spec, def, pool)
        spec.isStr -> StaticStrUserVar(spec, def, pool)
        !spec.isStatic -> DynamicIntUserVar(spec, def)
        else -> StaticIntUserVar(spec, def)
    }

    /**
     * 声明期形状守卫：各维在 `Long` 上预乘，超过可分配上限即 CodeEE。
     *
     * 用户变量的实际分配用的是 `DimSpec.totalSize`（`UserVariables.kt`），那里仍是
     * Int 折叠 —— 一旦乘积越过 2^31，`LongArray(n)` 会先拿到负数（`NegativeArraySizeException`）
     * 或偏小的正数（后续行主序寻址 AIOOBE），两种都不是 CodeEE。ERB 源的声明已由
     * `DimParser` 卡在 1..1000000（spec: UserDefinedVariableData.cs:337-340），
     * 本守卫兜住**编译期元数据**（BootMetadata 的 private-dim）与手工构造的 DimSpec。
     */
    private fun checkUserVarShape(spec: DimSpec) {
        if (spec.isRef || spec.dims.isEmpty()) return // REF 声明尺寸恒 0；标量按 1 个元素
        dimProduct(spec.name, spec.dims)
    }

    private fun userCode(spec: DimSpec, chara: Boolean): Int {
        val d = spec.dims.size
        if (spec.isRef) {
            return when {
                chara -> if (spec.isStr) {
                    if (d == 2) VariableCode.CVARS2D else VariableCode.CVARS
                } else {
                    if (d == 2) VariableCode.CVAR2D else VariableCode.CVAR
                }
                spec.isStr -> when (d) {
                    2 -> VariableCode.REFS2D
                    3 -> VariableCode.REFS3D
                    else -> VariableCode.REFS
                }
                else -> when (d) {
                    2 -> VariableCode.REF2D
                    3 -> VariableCode.REF3D
                    else -> VariableCode.REF
                }
            }
        }
        if (chara) {
            return if (spec.isStr) {
                if (d == 2) VariableCode.CVARS2D else VariableCode.CVARS
            } else {
                if (d == 2) VariableCode.CVAR2D else VariableCode.CVAR
            }
        }
        return if (spec.isStr) {
            when (d) {
                2 -> VariableCode.VARS2D
                3 -> VariableCode.VARS3D
                else -> VariableCode.VARS
            }
        } else {
            when (d) {
                2 -> VariableCode.VAR2D
                3 -> VariableCode.VAR3D
                else -> VariableCode.VAR
            }
        }
    }

    /**
     * 变量名大写化记忆（**真机性能热点，2026-10-23**）。
     *
     * 读侧每个变量访问都要把名字归一到大写（编译期存的是大写键）；`String.uppercase()` 在
     * ART 上走 ICU CaseMapper（逐字符 `String.charAt` + `Character.toUpperCaseImpl`），
     * 世界初始化那一页实测占 VM 线程采样 9% 并逐次分配。变量名集合有限（全量语料 2 万余个），
     * 用一张名字表记忆归一结果；命中路径只剩一次 String.hashCode（JDK/ART 自带缓存）+ equals。
     */
    private val upperNames = HashMap<String, String>(4096)

    /**
     * [upperOf] 的**单槽身份备忘**（PERF 2026-10-27）。
     *
     * `upperOf` 的最大调用方是 [enterFunction]：每次 `_CALL` 一次（一轮 69.8 万次），
     * 传进来的永远是 `FuncMeta.key` —— **同一个 `String` 实例**。于是按引用比较
     * 一次就能短路，连 `upperNames` 那次 `hashCode` + 桶查找都省掉。
     *
     * 判据是 `===`（引用），不是 `==`：同一个实例的 `uppercase()` 结果恒等，
     * 所以命中即正确；未命中只是回到原来那条路，不会错。
     *
     * 为什么单槽够用：`upperOf` 的其余调用点（[isPrivateName]、[userVar] 的回落、
     * [localFor] 的 REF 分支）都在冷路径上，偶尔把槽冲掉也只是让 `enterFunction`
     * 重走一次哈希表 —— 方向不会反。
     *
     * JFR 里 `upperOf ← enterFunction` 占 `getNode` 采样的 5.5%。
     */
    private var lastUpperIn: String? = null
    private var lastUpperOut: String = ""

    /** [upperOf] 单槽备忘的开关（缺省开）；`-Deratw.perf.noUpperMemo=true` 关掉，只为同窗口 A/B。 */
    private val useUpperMemo: Boolean = !java.lang.Boolean.getBoolean("eratw.perf.noUpperMemo")

    private fun upperOf(name: String): String {
        if (useUpperMemo && name === lastUpperIn) return lastUpperOut
        val hit = upperNames[name]
        if (hit != null) {
            lastUpperIn = name
            lastUpperOut = hit
            return hit
        }
        val u = name.uppercase()
        upperNames[name] = u
        lastUpperIn = name
        lastUpperOut = u
        return u
    }

    /**
     * 用户变量的**原文名**单跳索引（PERF 2026-10-26）：与 [userVars] 同一批实例，
     * 只是键换成声明时写的那个名字。
     *
     * 语料里的变量名本来就用声明的大小写书写（`GMF_约会中同行转换` 之类），
     * `upperOf(name) → userVars[key]` 的两跳里第一跳纯属白付。真机采样中
     * `upperOf`+`userVar`+`userVars` 三级合计占 19%（`jfr-turn2.jfr`）。
     * 写入点只有 [declareUserVar]（与 [userVars] 同一行旁边），不存在运行期漂移；
     * 未命中（语料大小写与声明不一致）仍回落 [upperOf] 那条原路径，语义不变。
     */
    private val userVarsRaw = HashMap<String, UserVar>()

    /**
     * [userVar] 的**单条记忆**（PERF 2026-10-26）。
     *
     * 变量访问在脚本里高度重复：`FOR LOCAL, 1, CHARANUM` 环体里同一个 `CFLAG:LOCAL:X`
     * 要被读上百万次，每次都要过「私有作用域表 → 全局用户变量表」两张 `HashMap`
     * （真机采样：`userVar` 入栈 31%、`HashMap.getNode` 独占 40%）。
     * 名字串来自编译产物里的同一个 `VarRef`，比较走引用快路径。
     *
     * **判据是「命中值自己的名字与请求相同」**（只读一个引用字段，并发下最坏是未命中
     * 而重算，绝不会把 A 变量当成 B 返回）。作用域/声明一变就必须作废，见
     * [invalidateUserVarMemo] 的调用点。查不到（null）不记忆。
     */
    private var lastUserVar: UserVar? = null

    /** 作废 [userVar] 的单条记忆。作用域/声明一变就必须调。 */
    private fun invalidateUserVarMemo() {
        lastUserVar = null
    }

    /** 运行期按名字找用户变量：私有变量优先当前函数作用域。 */
    fun userVar(name: String): UserVar? {
        val memo = lastUserVar
        if (memo != null) {
            val d = memo.def
            if (d.name == name || d.upperName == name) return memo
        }
        val found = lookupUserVar(name)
        if (found != null) lastUserVar = found
        return found
    }

    private fun lookupUserVar(name: String): UserVar? {
        topFrame()?.let { f ->
            val m = privateVars[f.funcKey]
            if (m != null) {
                val v = m[name] ?: m[upperOf(name)]
                if (v != null) return v
            }
        }
        return userVarsRaw[name] ?: userVars[upperOf(name)]
    }

    /**
     * 按函数归属查用户变量（解析期用：此时还没有运行期帧）。
     * [functionKey] 为 null 时等价于只看全局用户变量。
     */
    fun userVar(name: String, functionKey: String?): UserVar? {
        if (functionKey != null) {
            val m = privateVars[upperOf(functionKey)]
            if (m != null) {
                val v = m[name] ?: m[upperOf(name)]
                if (v != null) return v
            }
        }
        return userVarsRaw[name] ?: userVars[upperOf(name)]
    }

    /**
     * REF 绑定源（[com.eratw.port.core.proc.ProcessRuntime] 用）：系统数组 / 局部数组解析出的
     * 「元信息 + 底层数组（**别名**）」。
     *
     * 原版 REF 绑的是变量 token 交出的数组对象本身（spec: UserDefinedFunctionArgument.cs:45-58
     * `TransporterRef[i] = Identifier.GetArray()` → ReferenceToken.cs:125-128 `array = refArray`），
     * 而实参的变量解析与普通表达式走同一条链 `IdentifierDictionary.GetVariableToken`：
     * 调用方私有变量 → 局部变量（LOCAL/ARGS 族以 subKey = 调用方函数名取实例）
     * → 系统变量（spec: IdentifierDictionary.cs:429-495，局部 :448-473、系统 :474-489）。
     * 用户变量那一档由 [com.eratw.port.core.proc.ProcessRuntime] 先用 [userVar] 解析；本结构补齐局部 / 系统两档。
     */
    internal class RefBindTarget internal constructor(
        val def: VarDef,
        /** 整型底层数组；与 [strs] 互斥。null = 该目标类型不提供数组（角色/伪变量/CSV 常量/0 维）。 */
        val ints: LongArray?,
        /** 字符串底层数组（**StringTable 驻留下标**，与 [com.eratw.port.core.vars.RefUserVar] 一致）。 */
        val strs: IntArray?,
        /**
         * 元素维长度。局部变量取**当前实例**长度（可能大于默认声明尺寸，
         * spec: VariableLocal.cs:40-90 `SetLength` / [resolveLocalSize]），其余取 [VarDef.dims]。
         */
        val dims: IntArray,
    )

    /**
     * REF 绑定用：按名字解析**系统 / 局部**变量的底层数组（别名）。
     *
     * @param functionKey 实参所在的**调用方**函数键。REF 绑定发生在被调已 enter 之后
     *   （见 `ProcessRuntime.onFunctionEnter`），[topFrame] 此刻已是被调方，不能拿来取
     *   调用方的 LOCAL 实例 —— 原版实参 token 的 subKey 就是调用方函数名
     *   （spec: IdentifierDictionary.cs:448-473）。
     *   null = 调用方函数未知（根级帧）⇒ 局部变量不解析。
     * @return null = 既不是局部也不是系统变量（名字不存在 / 用户变量，后者由调用方先查 [userVar]）。
     *
     * TODO(verify)：`IsForbid`（尺寸 0）的系统/局部变量在原版**解析期**就报 UsedProhibitedVar
     * （spec: IdentifierDictionary.cs:448-453 / :476-483），本侧没有解析期校验，这里会交出
     * 0 长数组，越界读时才报 OoRVarArg —— 文案与时点都不同。卡点：禁止使用表未进编译面。
     */
    internal fun refBindTarget(name: String, functionKey: String?): RefBindTarget? {
        val def = registry.def(name) ?: return null
        return when (def.storage) {
            Storage.G_INT1 -> RefBindTarget(def, intCol1(def.index), null, def.dims)
            Storage.G_INT2 -> RefBindTarget(def, intCol2(def.index), null, def.dims)
            Storage.G_INT3 -> RefBindTarget(def, intCol3(def.index), null, def.dims)
            Storage.G_STR1 -> RefBindTarget(def, null, gStr1[def.index], def.dims)
            Storage.G_STR0 -> RefBindTarget(def, null, gStr0, def.dims)
            // 局部 1 维：惰性分配口径同原版（`GetArray()` 在 null 时现分配，
            // spec: VariableData.cs:1202-1209 整数 / :1279-1285 字符串）
            Storage.L_INT -> {
                val key = functionKey ?: return null
                val lv = localFor(def, key)
                RefBindTarget(def, lv.ints ?: LongArray(lv.size).also { lv.ints = it }, null, intArrayOf(lv.size))
            }
            Storage.L_STR -> {
                val key = functionKey ?: return null
                val lv = localFor(def, key)
                RefBindTarget(def, null, lv.strs ?: IntArray(lv.size).also { lv.strs = it }, intArrayOf(lv.size))
            }
            // 用户变量只在**调用方作用域**下有效（私有优先、其次全局）：这里拿到的是别的函数的
            // 私有变量，原版解析期就看不到（IdentifierDictionary.cs:432-447）⇒ 不解析。
            Storage.USER -> null
            // 角色 / 伪变量 / CSV 常量：只交元信息，由 bindRef 按 ReferenceToken.MatchType
            // 的分支拒绝（spec: ReferenceToken.cs:138-167），此处不提供数组。
            else -> RefBindTarget(def, null, null, def.dims)
        }
    }

    fun allUserVars(): Collection<UserVar> = userVars.values

    /** 全部函数私有用户变量（ERD 表装载用；同一名字可能在多个函数各有一份）。 */
    fun allPrivateUserVars(): Collection<UserVar> =
        privateVars.values.flatMap { it.values }

    fun privateUserVars(functionKey: String): Collection<UserVar> =
        privateVars[functionKey]?.values ?: emptyList()

    fun globalUserVarList(): Collection<UserVar> = globalUserVars.values

    fun savedUserVarList(): Collection<UserVar> = savedUserVars.values

    // ==================================================================
    // 函数帧 / 局部变量
    // ==================================================================

    /** 函数声明中的 LOCAL/LOCALS/ARG/ARGS 长度（由参数声明决定）。 */
    data class LocalLengths(
        val local: Int = 0,
        val locals: Int = 0,
        val arg: Int = 0,
        val args: Int = 0,
    )

    fun defineLocalLengths(funcKey: String, lengths: LocalLengths) {
        localLengths[funcKey.uppercase()] = lengths
    }

    /**
     * Capacity hint for the per-function local-length table. Purely an
     * optimization: it changes bucket count only, never keys, values, lookup
     * results or any iteration product (`localLengths` is private and has no
     * iteration reader in production).
     *
     * ProcessRuntime registers ~110,072 function entries during assembly; a
     * default HashMap grows from 16 buckets through repeated full-table copies.
     * Rebuild only while the table is empty, so existing registrations
     * (tests / repeated assembly) are never discarded.
     */
    internal fun reserveLocalLengths(expectedSize: Int) {
        if (localLengths.isEmpty() && expectedSize > 0) {
            localLengths = HashMap(expectedSize + (expectedSize shr 1) + 1)
        }
    }

    val currentFunction: String? get() = topFrame()?.funcKey

    /**
     * 进入函数：为私有动态变量开新作用域（栈式嵌套）。
     * spec: ProcessState.cs:438-446（IntoFunction → TopLabel.ScopeIn）。
     *
     * **由 VM 调用**：`_CALL` 经 `VmHost.onFunctionEnter` 驱动（m2-bytecode 负责），
     * 语句层与脚本层不直接调用。调用时序：实参求值之后、执行被调函数体之前。
     *
     * [resetLocals]：原版 1.824 **没有**"每次函数调用清零 LOCAL"的调用点——
     * `SetDefaultLocalValue` 只被 RESETDATA 与两个存档装载函数调用
     * （spec: VariableEvaluator.cs:1339, 2441, 2626），故默认 false。
     */
    fun enterFunction(funcKey: String, resetLocals: Boolean = false) {
        // PERF（2026-10-26）：走 [upperOf] 名字大写记忆表，替代每次 `_CALL` 一次的
        // `funcKey.uppercase()`（ART/ICU 逐字符分配）。命中路径零分配；输入的键即便
        // 已是大写（FuncMeta.key）也只有首次一次 uppercase，之后恒命中。语义不变。
        val key = upperOf(funcKey)
        // 建帧时把「该函数的局部实例表」与「私有变量表」各取一次挂到帧上（PERF 2026-10-27）：
        // 旧实现这两次哈希摊在**每次局部变量访问**（`localFor` 两次 + `checkElement` 两次）
        // 与**每次进/出函数**（`refreshPrivMap`）上；一次取好之后热路径只剩字段读。
        // 语义不变：`localsByFunc.getOrPut` 的实例与 `privateVars[key]` 的引用原样搬进帧。
        val privMap = privateVars[key]
        val state = localsByFunc.getOrPut(key) { LocalState() }
        // 槽位镜像按函数复用（见 [LocalState]）：首次进这个函数时建一次，之后每次
        // `_CALL` 只是把同一份数组挂到新帧上，**零分配**。
        if (useLocalSlots && state.bySlot == null) state.bySlot = arrayOfNulls(LOCAL_SLOT_INITIAL)
        // 帧从池里取（见 [framePool]）：稳态零分配。
        val frame = if (framePool.isEmpty()) Frame() else framePool.removeAt(framePool.size - 1)
        frame.bind(key, state.locals, privMap, state)
        frames.add(frame)
        // 作用域换了 ⇒ userVar 的私有变量优先序变了（见 invalidateUserVarMemo）。
        invalidateUserVarMemo()
        // 绑定缓存的判据是「当前帧私有变量表的身份」，这里跟着换（见 bindOf）。
        refreshPrivMap()
        // 原子性：ScopeIn 中途失败时回滚已压栈的动态变量并弹出帧，
        // 保证 VM 侧"enter 抛错则该帧不出现于 exit 序列"的假设成立。
        //
        // PERF（2026-10-27 第六批）：快照改用**复用缓冲**。旧写法 `privMap.values.toList()`
        // 每次 `_CALL` 造一个 `ArrayList` + 它的 `Object[]`，JFR 实测这一族在
        // `enterFunction` 上合计 7.00 MiB（`ArrayList` 3.00 + `Collections$1` 3.00 +
        // 数组分摊），而一轮 69.8 万次 `_CALL`。缓冲只在**本方法内**使用，`scopeIn()`
        // 是纯数据结构操作（压一层值）不会重入 `enterFunction` ⇒ 单实例安全。
        val vars: List<UserVar>?
        if (privMap == null || privMap.isEmpty()) {
            vars = null
        } else {
            scopeInBuf.clear()
            scopeInBuf.addAll(privMap.values)
            vars = scopeInBuf
        }
        var entered = 0
        if (vars != null) {
            try {
                for (v in vars) {
                    v.scopeIn()
                    entered++
                }
            } catch (t: Throwable) {
                for (i in entered - 1 downTo 0) vars[i].scopeOut()
                // 池化闭环（PERF 2026-10-27 第三批）：回滚也要把帧还池，否则这条冷路径
                // 每抛一次就漏一个 `Frame`（虽然几乎不会走到，但池化改造必须自洽）。
                // 顺序：先出栈（`refreshPrivMap` 读的是新栈顶）→ 再断引用 → 最后还池。
                val f = frames.removeAt(frames.size - 1)
                f.release()
                framePool.add(f)
                // 回滚等于「这次 enter 没发生过」⇒ 两个缓存都要跟着回到父帧的视角。
                invalidateUserVarMemo()
                refreshPrivMap()
                throw t
            }
        }
        if (resetLocals) resetLocalsFor(key)
    }

    /**
     * 离开函数：动态变量弹栈恢复。spec: Process.cs:531-536 (ScopeOut in finally)。
     * **由 VM 调用**（`_RET` 经 `VmHost.onFunctionExit`），必须与 [enterFunction] 严格配对。
     *
     * TODO(verify)（2026-10-27 第六批验收时登记，**存量问题、非该批引入**；销账条目见
     * specs 的 verify-notes §21）：出场遍历的是**实时 map**，不是 [enterFunction] 那份
     * 快照 —— 两者不对称。若私有动态变量是在函数体内才声明的（[refreshPrivMap] 的
     * 兜底注释承认这一顺序存在），它从未 scopeIn 过，出场却会对它跑 scopeOut
     * （空栈分支 → data 置 null）。这可能是正确语义（动态私有本就随调用作废），
     * 也可能是与原版分歧。卡点：未对质 C# 侧 ScopeOut 的遍历对象（Process.cs:531-536
     * 到底是按调用时记录的变量清单还是函数当前变量表），且未量过语料里
     * 「函数体内部声明 DYNAMIC 私有 `#DIM`」是否真实可达。判定前不改行为。
     */
    fun leaveFunction() {
        val f = frames.removeLastOrNull() ?: throw CodeEE("leaveFunction 与 enterFunction 不配对")
        // 帧上已存好私有变量表（[Frame.privMap]）⇒ 不再查 `privateVars`（PERF 2026-10-27）。
        // 兜底那次 `?:` 只服务「帧建立后才声明私有变量」的极端顺序（见 [refreshPrivMap]）。
        (f.privMap ?: privateVars[f.funcKey])?.values?.forEach { it.scopeOut() }
        // 归还池：先断开引用（`funcKey` 在上面那次兜底查表之后才清）。
        f.release()
        framePool.add(f)
        invalidateUserVarMemo()
        refreshPrivMap()
    }

    /**
     * 刷新 [currentPrivMap]：当前帧（`frames` 栈顶）的私有用户变量表。
     *
     * 帧切换与私有变量声明都会让「当前作用域里这个名字解析成什么」变化；绑定缓存
     * （[bindOf]）用这张表的**身份**当判据，所以这三个点必须同步刷新。
     * 栈空时是 null —— 顶层（`EVENTTRAIN` 之前）本来就没有私有作用域。
     */
    private fun refreshPrivMap() {
        // 帧上已存好（[Frame.privMap]，建帧时取一次）⇒ 这里只剩字段读，不再查 `privateVars`。
        // 本函数在每次 `enterFunction`/`leaveFunction` 都跑（一轮 69.8 万次 ×2）。
        val f = topFrame()
        if (f == null) {
            currentPrivMap = null
            return
        }
        var m = f.privMap
        if (m == null) {
            // 帧建立之后才声明私有变量（`#DIM …, isPrivate` 写在过程体内）时，
            // 建帧那一刻 `privateVars[key]` 还不存在。这里回查一次并回填，
            // 之后（含 [leaveFunction]）都走字段读。语义与旧实现「每次现查」一致。
            m = privateVars[f.funcKey]
            f.privMap = m
        }
        currentPrivMap = m
    }

    fun callDepth(): Int = frames.size

    /**
     * 帧局部变量导出（只读取证口）。
     *
     * ## 为什么需要绕开 [topFrame]
     *
     * 常规读口（[readInt]）的 LOCAL/ARG 只能按**当前帧**解析（[localFor] → [topFrame]），
     * 而真机取证总在撞闸之后：`Vm.run` 的 finally 已经把调用栈回卷，`frames` 空
     * ⇒ 宿主 `var LOCAL:1` 一律报「变量 LOCAL 只能在函数内使用」。2026-10-25 红 J
     * （`ABL.ERB:88` 的 `FOR LOCAL:1,0,100` 烧穿 5,000,001 步）就卡在这个缺口：
     * 「FOR 计数器到底有没有写回」无法取证。本函数直接按帧栈顺序导出各帧现值，
     * 不受回卷影响（配合 `StepLimitExceeded.frameSnapshot` 在**抛点**取一次）。
     *
     * ## 输出格式（每帧两行以内）
     *
     * `#<i> <FUNC> LOCAL[0..n]=… LOCALS[0..n]=… ARG[0..n]=… ARGS[0..n]=…`
     * —— `#0` 是入口帧，末行是当前帧；`n = min(maxElems, 该变量长度)`。
     * 后面紧跟该帧的私有用户变量（`#DIM`/`#DIMS`，动态作用域栈顶值）：
     * `#<i> <FUNC> 私有 <NAME>[0..n]=…`。
     *
     * 惰性分配的局部数组（原版 `LocalVariableToken.GetArray()` 首次触碰才分配，
     * spec: VariableData.cs:1202-1209 整数 / :1279-1285 字符串）尚未分配时该段打
     * `=-`（= 全零未触碰，与「值是 0」区分开）。字符串局部按驻留池取回原文，
     * 超过 24 字符截断。
     *
     * 纯只读：不分配数组、不改任何值、不推进作用域栈 —— 未触碰的 LOCAL 不会因为
     * 被取证而变成「已触碰」。
     *
     * @param maxElems 每个变量最多导出多少个元素（默认 8）
     */
    fun frameSnapshotLines(maxElems: Int = 8): List<String> {
        if (frames.isEmpty()) return listOf("帧快照: 调用栈空（VM 已回卷）")
        val out = ArrayList<String>(frames.size * 2)
        for (i in frames.indices) {
            val key = frames[i].funcKey
            val locals = localsByFunc[key]?.locals
            out += buildString {
                append('#').append(i).append(' ').append(key)
                for (name in LOCAL_NAME_ORDER) {
                    append(' ').append(name)
                    val lv = locals?.get(name)
                    if (lv == null) {
                        append("=-")
                        continue
                    }
                    val n = minOf(maxElems, lv.size)
                    append('[').append(n).append("]=")
                    val ints = lv.ints
                    val strs = lv.strs
                    if (ints == null && strs == null) {
                        append('-') // 未触碰（惰性数组还没分配）
                    } else if (ints != null) {
                        for (e in 0 until minOf(n, ints.size)) {
                            if (e > 0) append(',')
                            append(ints[e])
                        }
                    } else if (strs != null) {
                        for (e in 0 until minOf(n, strs.size)) {
                            if (e > 0) append(',')
                            val s = pool.lookup(strs[e])
                            append(if (s.length > 24) s.take(24) + "…" else s)
                        }
                    }
                }
            }
            privateVars[key]?.values?.forEach { v ->
                val n = minOf(maxElems, v.spec.totalSize)
                out += buildString {
                    append('#').append(i).append(' ').append(key).append(" 私有 ").append(v.spec.name)
                    append('[').append(n).append("]=")
                    for (e in 0 until n) {
                        if (e > 0) append(',')
                        val text = runCatching {
                            if (v.spec.isStr) {
                                v.readStr(0, e).let { if (it.length > 24) it.take(24) + "…" else it }
                            } else {
                                v.readInt(0, e).toString()
                            }
                        }.getOrElse { "?" }
                        append(text)
                    }
                }
            }
        }
        return out
    }

    private fun resetLocalsFor(funcKey: String) {
        localsByFunc[funcKey]?.locals?.values?.forEach {
            it.ints?.fill(0)
            it.strs?.fill(0)
        }
    }

    private fun currentLocal(name: String): Local1D? =
        topFrame()?.funcKey?.let { localsByFunc[it] }?.locals?.get(name.uppercase())

    /**
     * 当前帧的局部实例（**热路径**）。
     *
     * PERF（2026-10-27）：旧实现每次访问要付**四次哈希查表** —— 这里
     * `localsByFunc[funcKey]` + `[upperName]` 两次，[checkElement] 的局部支再两次。
     * JFR 运行期归因（1863 条 app 叶采样，已按栈滤掉装载期）实测
     * `HashMap.getNode` 14.49% + `hashCode` 11.38% = **25.87%**，其中
     * `localFor` 占 23%、`checkElement` 占 39%。
     *
     * 现在：[VarDef.localSlot] 是稳定小整数，帧侧 [Frame.bySlot] 直接下标取 ⇒
     * **命中路径零哈希**（一次数组读 + 一次引用比较）。未命中才回落 [localForBind]。
     * 引用比较 `v.def === def` 兼作撞号保护（见 [VarDef.localSlot] 的 KDoc）。
     */
    private fun localFor(def: VarDef): Local1D {
        val f = topFrame() ?: throw CodeEE("变量 ${def.name} 只能在函数内使用")
        if (useLocalSlots) {
            val slot = def.localSlot
            if (slot >= 0) {
                val a = f.bySlot
                if (a != null && slot < a.size) {
                    val v = a[slot]
                    if (v != null && v.def === def) return v
                }
            }
        }
        return localForBind(f, def)
    }

    /**
     * [localFor] 的未命中路径：从 [Frame.locals]（**权威表**）取或建实例，再回填 [Frame.bySlot]。
     *
     * 权威表就是 `localsByFunc[funcKey]` 那一份（跨递归帧共享，
     * spec: VariableLocal.cs:15,26-33），[Frame.bySlot] 只是它的密集镜像 ——
     * 元素恒为同一实例，所以「镜像有、权威表没有」这种不一致不可能发生。
     */
    private fun localForBind(f: Frame, def: VarDef): Local1D {
        val key = f.funcKey
        val name = def.upperName
        val v = f.locals.getOrPut(name) {
            Local1D(def, resolveLocalSize(key, name, def))
        }
        if (useLocalSlots) {
            val slot = if (def.localSlot >= 0) def.localSlot else assignLocalSlot(def)
            val a = f.bySlot
            if (a == null || slot >= a.size) {
                val grown = arrayOfNulls<Local1D>(maxOf(slot + 1, LOCAL_SLOT_INITIAL))
                if (a != null) a.copyInto(grown)
                grown[slot] = v
                f.bySlot = grown
            } else {
                a[slot] = v
            }
        }
        return v
    }

    /**
     * 当前帧里某局部变量的**长度**（只读，不建实例）。
     *
     * 与 [localFor] 的区别是**语义**：`GetLength` / `IsArrayRangeValid` / `checkElement`
     * 只问「多长」，未触碰的 LOCAL 不该因为被问了一句就变成「已触碰」——
     * [frameSnapshotLines] 里 `LOCAL=-`（未建）与 `LOCAL[n]=-`（已建但未分配数组）
     * 是两种不同的显示，所以这里**不许**走 `getOrPut`。
     *
     * 因此命中 [Frame.bySlot] 时零哈希；未命中回落 [Frame.locals] 的 `get`（一次哈希，
     * 比旧实现的 `localsByFunc[funcKey]` + `[name]` 少一次），再不行才现算
     * [resolveLocalSize]（纯算术，无副作用）。
     */
    private fun localSizeOf(f: Frame, def: VarDef): Int {
        if (useLocalSlots) {
            val slot = def.localSlot
            if (slot >= 0) {
                val a = f.bySlot
                if (a != null && slot < a.size) {
                    val v = a[slot]
                    if (v != null && v.def === def) return v.size
                }
            }
        }
        val key = def.upperName
        return f.locals[key]?.size ?: resolveLocalSize(f.funcKey, key, def)
    }

    /**
     * [functionKey] 非 null 时取**该函数**的局部实例（REF 绑定要给调用方的 LOCAL，
     * 而绑定点上 [topFrame] 已是被调方，spec: IdentifierDictionary.cs:448-473）；
     * null 时沿用当前帧。
     */
    private fun localFor(def: VarDef, functionKey: String?): Local1D {
        // PERF（2026-10-27）：`functionKey == null` 就是「当前帧」，直接复用热路径
        // （[localFor] 的 bySlot 直取），不再自己走一遍 `localsByFunc.getOrPut`。
        if (functionKey == null) return localFor(def)
        // PERF（2026-10-26）：两处 `uppercase()` 换成 `upperOf` 记忆与 [VarDef.upperName]
        // 预计算 —— 局部变量取实例在 `FOR LOCAL` 环体里是每迭代一次的高频路径，
        // ART 上的 ICU 大写逐字符分配在这里纯属白付（真机采样 1.7% + 逐次垃圾）。
        val key = upperOf(functionKey)
        val name = def.upperName
        // spec: VariableLocal.cs:15,26-33 —— 局部变量以 subKey（函数名）为键，递归共用同一份。
        val map = localsByFunc.getOrPut(key) { LocalState() }.locals
        return map.getOrPut(name) {
            Local1D(def, resolveLocalSize(key, name, def))
        }
    }

    /**
     * 局部变量尺寸：函数声明里给了长度且更大时用它，否则用默认值
     * （ARG/ARGS 取 max(声明, 默认)，LOCAL/LOCALS 声明优先）。spec: VariableLocal.cs:40-90
     */
    private fun resolveLocalSize(funcKey: String, name: String, def: VarDef): Int {
        val len = localLengths[funcKey]
        val declared = when (name) {
            "LOCAL" -> len?.local ?: 0
            "LOCALS" -> len?.locals ?: 0
            "ARG" -> len?.arg ?: 0
            "ARGS" -> len?.args ?: 0
            else -> 0
        }
        val defSize = def.dims[0]
        return when (name) {
            "ARG", "ARGS" -> if (declared > defSize) declared else defSize
            else -> if (declared > 0) declared else defSize
        }
    }

    /**
     * LOCAL/ARG 的读入口：**下标先按 Long 比较长度、再截断**。
     *
     * 原版 `LocalVariableToken.CheckElement` 用 `arguments[0] < 0 || arguments[0] >= size`
     * 判越界（spec: LocalVariableToken.cs:39-45，arguments 是 long），随后才取元素。
     * 旧实现先 `index.toInt()` 再判：`LOCAL[2^32]` 截断成 0，会静默读写 0 号元素。
     */
    private fun localInt(def: VarDef, index: Long): Long {
        val lv = localFor(def)
        // spec: LocalVariableToken.cs:39-45 —— 先用 long 与长度比较再转 Int；
        // 先 toInt 会把 2^32 回绕成 0 静默读写第 0 个元素。
        if (index < 0 || index >= lv.size) {
            throw CodeEE("变量 ${def.name} 的下标 $index 越界（长度 ${lv.size}）")
        }
        val i = index.toInt()
        val arr = lv.ints ?: LongArray(lv.size).also { lv.ints = it }
        return arr[index.toInt()]
    }

    /** 同 [localInt]：Long 比较后再落 Int 下标。 */
    private fun setLocalInt(def: VarDef, index: Long, value: Long) {
        val lv = localFor(def)
        if (index < 0 || index >= lv.size) {
            throw CodeEE("变量 ${def.name} 的下标 $index 越界（长度 ${lv.size}）")
        }
        val arr = lv.ints ?: LongArray(lv.size).also { lv.ints = it }
        arr[index.toInt()] = value
    }

    /** 同 [localInt]。 */
    private fun localStr(def: VarDef, index: Long): String {
        val lv = localFor(def)
        if (index < 0 || index >= lv.size) {
            throw CodeEE("变量 ${def.name} 的下标 $index 越界（长度 ${lv.size}）")
        }
        val arr = lv.strs ?: IntArray(lv.size).also { lv.strs = it }
        return pool.lookup(arr[index.toInt()])
    }

    /** 同 [localInt]。 */
    private fun setLocalStr(def: VarDef, index: Long, id: Int) {
        val lv = localFor(def)
        if (index < 0 || index >= lv.size) {
            throw CodeEE("变量 ${def.name} 的下标 $index 越界（长度 ${lv.size}）")
        }
        val arr = lv.strs ?: IntArray(lv.size).also { lv.strs = it }
        arr[index.toInt()] = id
    }

    // ==================================================================
    // 伪变量
    // ==================================================================

    private fun pseudoInt(def: VarDef, indices: LongArray): Long = when (def.pseudo) {
        PseudoKind.RAND -> random(indices)
        PseudoKind.CHARANUM -> characters.count.toLong()
        PseudoKind.LASTLOAD_VERSION -> lastLoadVersion
        PseudoKind.LASTLOAD_NO -> lastLoadNo
        PseudoKind.LINECOUNT -> lineCountSource?.invoke() ?: lineCount
        PseudoKind.ISTIMEOUT -> if (isTimeout) 1L else 0L
        PseudoKind.INT_MAX -> Long.MAX_VALUE
        PseudoKind.INT_MIN -> Long.MIN_VALUE
        PseudoKind.GAMEBASE_INT -> gameBaseInt(def.name)
        PseudoKind.DEBUG_LINE -> if (config.debugMode) debugLine else 0L
        else -> throw CodeEE("变量 ${def.name} 不是整数变量")
    }

    private fun pseudoStr(def: VarDef): String = when (def.pseudo) {
        PseudoKind.LASTLOAD_TEXT -> lastLoadText
        PseudoKind.EMUERA_VERSION -> "1.824.0.0" // spec: VariableData.cs:1127,3164
        PseudoKind.WINDOW_TITLE -> windowTitle
        PseudoKind.MONEYLABEL -> moneyLabel
        PseudoKind.DRAWLINESTR -> drawLineStr
        PseudoKind.GAMEBASE_STR -> gameBaseStr(def.name)
        PseudoKind.DEBUG_FILE -> if (config.debugMode) debugFile else ""
        PseudoKind.DEBUG_FUNCTION -> if (config.debugMode) debugFunction else ""
        else -> throw CodeEE("变量 ${def.name} 不是字符串变量")
    }

    private fun setPseudo(def: VarDef, value: Long) {
        if (def.pseudo != PseudoKind.WINDOW_TITLE) throw CodeEE("变量 ${def.name} 不能赋值")
        throw CodeEE("变量 ${def.name} 不是整数变量")
    }

    private fun setPseudoStr(def: VarDef, value: String) {
        if (def.pseudo != PseudoKind.WINDOW_TITLE) throw CodeEE("变量 ${def.name} 不能赋值")
        windowTitle = value
    }

    private fun gameBaseInt(name: String): Long = when (name.uppercase()) {
        "GAMEBASE_GAMECODE" -> registry.gameBase.gameCode
        "GAMEBASE_VERSION" -> registry.gameBase.version
        "GAMEBASE_ALLOWVERSION" -> registry.gameBase.allowVersion
        "GAMEBASE_DEFAULTCHARA" -> registry.gameBase.defaultChara
        "GAMEBASE_NOITEM" -> registry.gameBase.noItem
        else -> throw CodeEE("未知的 GAMEBASE 常量 $name")
    }

    private fun gameBaseStr(name: String): String = when (name.uppercase()) {
        "GAMEBASE_AUTHOR", "GAMEBASE_AUTHER" -> registry.gameBase.author
        "GAMEBASE_INFO" -> registry.gameBase.info
        "GAMEBASE_YEAR" -> registry.gameBase.year
        "GAMEBASE_TITLE" -> registry.gameBase.title
        "GAMEBASE_URL" -> registry.gameBase.url
        "GAMEBASE_VERSIONNAME" -> registry.gameBase.versionName
        else -> throw CodeEE("未知的 GAMEBASE 常量 $name")
    }

    /** RAND 伪变量。spec: VariableData.cs:838-876 */
    private fun random(indices: LongArray): Long {
        if (indices.size != 1) throw CodeEE("RAND 需要 1 个参数")
        val rng = randProvider ?: throw CodeEE("RAND 需要注入 RandProvider（M2 第二批接 SFMT19937）")
        if (config.compatiRand) {
            val n = indices[0]
            if (n == 0L) return 0L
            val bound = if (n < 0) -n else n
            return rng.next(32768L) % bound
        }
        val bound = indices[0]
        if (bound <= 0) throw CodeEE("RAND 的参数必须为正数（实际 $bound）")
        return rng.next(bound)
    }

    // ==================================================================
    // 下标检查 / 索引
    // ==================================================================

    /**
     * 系统变量的下标校验（冷路径入口：批量/区间赋值）。
     *
     * 热路径（[readIntEntry] 一族）直接调 [checkFlat]，一次遍历完成「校验 + 压平」；
     * 本函数只留作「只要校验副作用」的调用点，数组部分**委托同一个** [checkFlat]
     * —— 两条路径共用一份文案与判定，不可能分叉。
     *
     * 局部变量（`L_INT`/`L_STR`）保留原分支：长度由函数声明决定（可能大于默认 1000），
     * 且 [localInt] 族自带 Long 口径的越界检查，热路径另有分流（见 [readIntEntry]）。
     */
    private fun checkElement(def: VarDef, indices: LongArray) {
        if (def.storage == Storage.L_INT || def.storage == Storage.L_STR) {
            if (def.isForbid) throw CodeEE("变量 ${def.name} 的尺寸为 0（IsForbid），不可使用")
            if (indices.size != def.argCount) {
                throw CodeEE("变量 ${def.name} 需要 ${def.argCount} 个下标（实际 ${indices.size}）")
            }
            // 局部变量的长度由函数声明决定（可能大于默认 1000），在实际数组中检查。
            // 判定必须留在 Long 域（spec: LocalVariableToken.cs:39-45 —— arguments[0] 直接与 int size 比），
            // 先 toInt 会把 LOCAL:2^32+5 截成 LOCAL:5 静默别名。
            if (indices.size != 1) throw CodeEE("变量 ${def.name} 需要 1 个下标")
            val f = topFrame()
            if (f != null) {
                val len = localSizeOf(f, def)
                val x = indices[0]
                if (x < 0 || x >= len) {
                    throw CodeEE("变量 ${def.name} 的下标 $x 越界（长度 $len）")
                }
            }
            return
        }
        checkFlat(def, indices)
    }

    /**
     * 系统数组的「一遍遍历」校验 + 压平（T2-62）：返回元素下标。
     *
     * ## 为什么合并
     *
     * 旧实现是 `checkElement`（逐维校验）+ `flat`（再遍历一遍压平）两遍走同一份 `dims`，
     * 角色变量还被 `checkElement` 与 `checkUserElementImpl` 各查一次位置、`argCount` 查两次。
     * JFR（T2-61 之后的 `t62-base.jfr`，2190 条应用采样）实测这条链合计 **10.5%** 自耗时：
     * `flat` 3.1% + `checkUserElement` 2.6% + `VarDef.isForbid` 1.8% +
     * `checkUserElementImpl` 1.7% + `checkElement` 1.3%（另有 `readIntEntry`/`readIntH` 的
     * 分派成本 4.3%，不在本项范围）。合并后每次访问只遍历一遍 dims、只判一次前置条件。
     *
     * ## 红线：文案与触发条件逐字不变
     *
     * 每个 `throw` 的字符串与旧实现逐字相同，且**第一次越界的那句**仍是同一句
     * （判定顺序 = 旧 `checkElement` 的顺序：isForbid → argCount → 角色位置 → 逐维）。
     * 旧 `checkUserElementImpl` 里第二次角色位置检查与第二次 argCount 检查在旧路径上
     * 也必然通过（同一份 indices、两步之间无副作用），删掉不改变可观测行为。
     *
     * 只服务**非局部变量**（`L_INT`/`L_STR` 由 [checkElement] 的原分支处理）。
     */
    private fun checkFlat(def: VarDef, indices: LongArray): Int {
        if (def.isForbid) throw CodeEE("变量 ${def.name} 的尺寸为 0（IsForbid），不可使用")
        if (indices.size != def.argCount) {
            throw CodeEE("变量 ${def.name} 需要 ${def.argCount} 个下标（实际 ${indices.size}）")
        }
        val dims = def.dims
        if (def.isCharacterData) {
            val pos = indices[0]
            if (pos < 0 || pos >= characters.count) {
                throw CodeEE("角色变量 ${def.name} 的第 1 参数 $pos 越界（角色数 ${characters.count}）")
            }
            if (dims.isEmpty()) return 0
            var elem = 0
            var d = 0
            while (d < dims.size) {
                val v = indices[d + 1]
                val n = dims[d]
                if (v < 0 || v >= n) {
                    throw CodeEE("变量 ${def.name} 的第 ${d + 2} 维下标 $v 越界（长度 $n）")
                }
                elem = elem * n + v.toInt()
                d++
            }
            return elem
        }
        if (dims.isEmpty()) {
            if (indices.isNotEmpty()) throw CodeEE("变量 ${def.name} 是 0 维变量，不带下标")
            return 0
        }
        var elem = 0
        var d = 0
        while (d < dims.size) {
            val v = indices[d]
            val n = dims[d]
            if (v < 0 || v >= n) {
                throw CodeEE("变量 ${def.name} 的第 ${d + 1} 维下标 $v 越界（长度 $n）")
            }
            elem = elem * n + v.toInt()
            d++
        }
        return elem
    }

    /**
     * 引用变量的元素下标（压平后）。
     *
     * - 1D：目标数组本身就是行主序压平的一维，下标原样使用
     *   （spec: VariableData.cs:2325-2341 `((long[])array)[arguments[0]]`）；
     *   比较用 Long（目标长度是 Int，`2^32` 之类不能先 toInt 再判）。
     * - 2D/3D：原版按各维取元素（spec: :2389-2417 / :2458-2488 的 `array[i, j]` /
     *   `array[i, j, k]`），`CheckElement` 逐维问 `array.GetLength(d)`
     *   （spec: ReferenceToken.cs:58-76）。本移植的目标是行主序压平的一维数组，
     *   各维长度由绑定点随目标写入 [RefUserVar.targetDims]（见 bindRef/bindInt/bindStr），
     *   这里逐维校验后按同一行主序公式压平，与全局多维数组的 [flat] 同口径。
     * - 没有形状（`targetDims == null`，只有手工 `bindInt(array)` 的老调用点会这样）：
     *   1D 行为不变；多维只有「所有下标为 0」（行主序压平恒为 0 号元素）可证明正确，
     *   其余一律抛 CodeEE —— 宁可报错，也不许静默落到错误元素。
     */
    private fun refElem(ref: RefUserVar, indices: LongArray): Int {
        val len = ref.boundLength()
            ?: throw CodeEE("引用变量 ${ref.def.name} 未绑定目标（EmptyRefVar）")
        val rank = ref.spec.dims.size
        if (rank <= 0) throw CodeEE("引用变量 ${ref.def.name} 的下标个数非法（$rank）")
        if (indices.size != rank) {
            throw CodeEE("引用变量 ${ref.def.name} 需要 $rank 个下标（实际 ${indices.size}）")
        }
        val dims = ref.targetDims
        if (dims == null || dims.size != rank) {
            if (rank == 1) {
                val i = indices[0]
                if (i < 0 || i >= len) {
                    throw CodeEE("引用变量 ${ref.def.name} 的下标 $i 越界（目标长度 $len）")
                }
                return i.toInt()
            }
            if (indices.all { it == 0L }) {
                if (len <= 0) throw CodeEE("引用变量 ${ref.def.name} 的下标 0 越界（目标长度 $len）")
                return 0
            }
            throw CodeEE(
                "引用变量 ${ref.def.name} 是多维引用变量（$rank 维），目标各维长度未随绑定记录，" +
                    "无法校验/压平下标 ${indices.joinToString(",")}（TODO(verify)，禁止静默写错）",
            )
        }
        // spec: ReferenceToken.cs:58-76 —— 逐维 `arguments[d] < 0 || >= array.GetLength(d)`，
        // 报错文案按维号（1 起）与长度。下标比较用 Long（目标维度是 Int，先 toInt 会截断）。
        var idx = 0
        for (d in dims.indices) {
            val v = indices[d]
            if (v < 0 || v >= dims[d]) {
                throw CodeEE(
                    "引用变量 ${ref.def.name} 的第 ${d + 1} 维下标 $v 越界（长度 ${dims[d]}）",
                )
            }
            idx = idx * dims[d] + v.toInt()
        }
        return idx
    }

    /**
     * 用户变量的「一遍遍历」校验 + 落点（T2-62）：返回 `(charPos << 32) | elem`。
     *
     * 旧实现 `checkUserElement`（校验）+ `userSlot`（落点）各判一次 `is RefUserVar`，
     * 非 REF 又各遍历一遍 `dims`（校验一遍、`flat` 再一遍）；REF 的 `refElem` 也被算两次
     * （校验一次丢弃结果、落点一次）。本函数只判一次、只遍历一次、`refElem` 只算一次。
     *
     * ## 红线：文案与触发条件逐字不变
     *
     * 每个 `throw` 的字符串与旧 `checkUserElementImpl` 逐字相同，判定顺序也相同
     * （dims 空 → argCount → 角色位置 → 逐维）。`RefUserVar` 一条仍走 [refElem]：
     * 未绑定抛 `EmptyRefVar`、已绑定逐维校验目标长度（spec: ReferenceToken.cs:58-76，
     * 下标比较用 Long，先 toInt 会让 2^32 回绕成 0）。
     *
     * 注意：本函数**不做** `isForbid` 检查 —— 用户变量的声明期尺寸守卫在
     * [checkUserVarShape]（只查乘积溢出），旧路径同样不查，保持一致。
     */
    private fun checkAndSlot(v: UserVar, indices: LongArray): Long {
        if (v is RefUserVar) return packSlot(0, refElem(v, indices))
        val def = v.def
        val dims = def.dims
        if (dims.isEmpty()) {
            if (def.isCharacterData) {
                if (indices.size != 1) throw CodeEE("变量 ${def.name} 需要 1 个下标（角色位置）")
                val pos = indices[0]
                if (pos < 0 || pos >= characters.count) {
                    throw CodeEE("角色变量 ${def.name} 的角色位置 $pos 越界（角色数 ${characters.count}）")
                }
                return packSlot(pos.toInt(), 0)
            }
            if (indices.isNotEmpty()) throw CodeEE("变量 ${def.name} 是 0 维变量，不带下标")
            return packSlot(0, 0)
        }
        if (indices.size != def.argCount) {
            throw CodeEE("变量 ${def.name} 需要 ${def.argCount} 个下标（实际 ${indices.size}）")
        }
        var off = 0
        var pos = 0
        if (def.isCharacterData) {
            val p = indices[0]
            if (p < 0 || p >= characters.count) {
                throw CodeEE("角色变量 ${def.name} 的第 1 参数 $p 越界（角色数 ${characters.count}）")
            }
            pos = p.toInt()
            off = 1
        }
        var elem = 0
        var d = 0
        while (d < dims.size) {
            val x = indices[d + off]
            val n = dims[d]
            if (x < 0 || x >= n) {
                throw CodeEE("变量 ${def.name} 的第 ${d + off + 1} 维下标 $x 越界（长度 $n）")
            }
            elem = elem * n + x.toInt()
            d++
        }
        return packSlot(pos, elem)
    }

    private fun packSlot(charPos: Int, elem: Int): Long =
        (charPos.toLong() shl 32) or (elem.toLong() and 0xFFFF_FFFFL)

    // ==================================================================
    // 默认值 / 重置
    // ==================================================================

    /**
     * 系统数组与伪变量的默认值（原版 `SetDefaultValue`）。
     * spec: VariableData.cs:3385-3514 —— 只复位系统数组/伪变量，用户 #DIM 不在其中
     * （见 [resetLocalValues] 的 KDoc）。
     */
    fun resetValues() {
        val globalIndex = VariableCode.lowIndex(VariableCode.GLOBAL_VAR)
        val priceIndex = VariableCode.lowIndex(VariableCode.ITEMPRICE)
        for (i in gInt1.indices) {
            // GLOBAL(63) 不在 SetDefaultValue 的清理范围内（spec: VariableData.cs:3398-3399）
            if (i == globalIndex) continue
            // 惰性列（mem6）：null = 从没写过 = 已经全是 0，**不**为「清零」而分配；
            // 已分配的列必须原地 fill —— REF 绑的是同一个数组对象（ReferenceToken.cs:125-128），
            // 换对象会让已绑定的 REF 看不见这次复位（那是行为改动）。
            gInt1[i]?.fill(0)
        }
        // ITEMPRICE 复制常量表。spec: VariableData.cs:3395-3397
        val price = intCol1(priceIndex)
        itemPriceSource.copyInto(price, 0, 0, minOf(price.size, itemPriceSource.size))

        for (i in gInt2.indices) gInt2[i]?.fill(0)
        for (i in gInt3.indices) gInt3[i]?.fill(0)

        for (i in gStr1.indices) {
            if (i == VariableCode.lowIndex(VariableCode.GLOBALS_VAR)) continue // GLOBALS 不清
            gStr1[i].fill(0)
        }
        gStr0[0] = 0

        // STR 用 STR.CSV 的名字表填充以兼容旧脚本。spec: VariableData.cs:3414-3416
        val strCol = gStr1[VariableCode.lowIndex(VariableCode.STR)]
        val csvStr = registry.csvTables[10]
        for (i in 0 until minOf(strCol.size, csvStr.size)) {
            val name = csvStr[i]
            if (name.isNotEmpty()) strCol[i] = pool.intern(name)
        }

        // PALAMLV / EXPLV。spec: VariableData.cs:3485-3490
        val palamLv = intCol1(VariableCode.lowIndex(VariableCode.PALAMLV))
        config.palamLvDef.copyInto(palamLv, 0, 0, minOf(palamLv.size, config.palamLvDef.size))
        val expLv = intCol1(VariableCode.lowIndex(VariableCode.EXPLV))
        config.expLvDef.copyInto(expLv, 0, 0, minOf(expLv.size, config.expLvDef.size))
        // ASSI[0] = -1, TARGET[0] = 1, PBAND[0] = 4, EJAC[0] = 10000
        // VariableSize.CSV 把 CanForbid 数组禁用成 0 长度时，C# 先判 Length != 0 再写
        // （spec: VariableData.cs:3491-3510）；直接 [0]= 会在 boot 期 ArrayIndexOutOfBounds。
        val assi = intCol1(VariableCode.lowIndex(VariableCode.ASSI))
        if (assi.isNotEmpty()) assi[0] = -1L
        val target = intCol1(VariableCode.lowIndex(VariableCode.TARGET))
        if (target.isNotEmpty()) target[0] = 1L
        val pband = intCol1(VariableCode.lowIndex(VariableCode.PBAND))
        if (pband.isNotEmpty()) pband[0] = config.pbandDef
        val ejac = intCol1(VariableCode.lowIndex(VariableCode.EJAC))
        if (ejac.isNotEmpty()) ejac[0] = 10000L

        lastLoadVersion = -1L
        lastLoadNo = -1L
        lastLoadText = ""
        // 注意：原版 SetDefaultValue 只复位系统数组与伪变量，**不碰用户 #DIM**
        // （spec: VariableData.cs:3385-3514）；用户静态变量由 SetDefaultLocalValue 负责
        // （:3365-3375），全局用户变量由 SetDefaultGlobalValue 负责（:3347-3363）。
    }

    /**
     * RESETGLOBAL 的第三步：`SetDefaultGlobalValue()`。
     *
     * spec: VariableData.cs:3347-3363 —— 三件事，且**只**这三件事：
     * ① `dataIntegerArray[63]`（下标 63 那一列 = GLOBAL 整表，长度按 VariableSize）全部置 0
     * （:3349-3354）；
     * ② `dataStringArray[5]`（下标 5 那一列 = GLOBALS）全部置 **null**，不是空串
     * （:3355-3358，:3357）；
     * ③ `userDefinedGlobalVarList` 逐个 `SetDefault()`（:3359-3362）。
     *
     * ②的 null 口径：本工程全局字符串槽是 `StringTable` 下标（IntArray），
     * 下标 0 出厂即空串（StringTable.kt:19-22），移植侧**没有**「null 与空串」的可观测
     * 区分（`ISINITIALNULLSTR` 一类判空语义在本仓不存在）。故这里以 fill(0) 落地，
     * 与 [resetValues] 复位其余全局字符串列的写法同源。TODO(verify)：若将来要复刻
     * null/空串二分，需要先给 StringTable 加一个「未赋值」哨兵，属跨层接线。
     *
     * ③的 `SetDefault()` 是「回到 `#DIM`/`DEFAULT` 声明的初值」而**不是归零**：
     * 整数列 `StaticInt1DVariableToken.SetDefault`（:1323-1332）先清零再回填 `defArray`
     * （`defArray = data.DefaultInt`，构造 :1303-1309）；字符串列
     * `StaticStr1DVariableToken.SetDefault`（:1595-1606）在 `defArray` 非空时**只**回填
     * `defArray` 那一段、其后的元素保持原值（与整数列不对称，官方怪癖照抄，ADR-005）。
     * 两族口径移植侧已落在 `UserVar.setDefault`（UserVariables.kt:1216-1220、
     * :1248-1259），本函数只负责选对复位集。
     *
     * 复位集边界（原版 `userDefinedGlobalVarList` 的入表面 = `CreateUserDefVariable`，
     * :3246-3268 ⇒ 只有 `#DIM GLOBAL`；`CreateSaveVariable`（:3276-3290）与
     * `CreatePrivateVariable`（:3292-3345）进的是 `userDefinedGlobalSaveVarList` /
     * `userDefinedStaticVarList`，**不**由本函数复位）：
     * 移植侧同名集合是 [globalUserVars]（`declareUserVar` 里按 `spec.isGlobal` 入表），
     * 逐元素 `setDefault()`。`#DIM GLOBAL SAVEDATA` 也在复位集里：装载期**所有**非
     * CHARADATA 的 `#DIM` 一律只走 `CreateUserDefVariable`
     * （spec: ErhLoader.cs:261-267 是唯一调用点，`!CharaData` 分支），故经 :3265-3268
     * 进 `userDefinedGlobalVarList`；SAVEGLOBAL 侧也是遍历同一份表再按 `IsSavedata`
     * 过滤（:3971-3974），两侧同源。
     *
     * 与 [resetValues]（RESETDATA 的系统数组面）互不重叠：那边显式**跳过** GLOBAL
     * 与 GLOBALS（:3398-3399、:3417-3418），本函数只碰这两列 + GLOBAL 用户变量。
     * 两者都不碰角色表、局部变量、STATIC 用户变量。
     */
    fun resetGlobalValues() {
        // 惰性列：null 已经是「全 0」，不为清零而分配；在场则原地 fill（REF 别名同 [resetValues]）。
        gInt1[VariableCode.lowIndex(VariableCode.GLOBAL_VAR)]?.fill(0)
        gStr1[VariableCode.lowIndex(VariableCode.GLOBALS_VAR)].fill(0)
        // CHARADATA 用户变量不入 `userDefinedGlobalVarList`（原版另走
        // `CreateUserDefCharaVariable`，只进 `UserDefinedCharaVarList`，
        // VariableData.cs:3220-3244）⇒ 复位时按 isCharacterData 排除。
        for (v in globalUserVars.values) {
            if (!v.def.isCharacterData) v.setDefault()
        }
    }

    /**
     * 函数局部 + 静态用户变量复位（RESETDATA 的一部分）。
     *
     * spec: VariableData.cs:3365-3375 —— `localvarTokenDic` 的全部函数局部实例
     * + `userDefinedStaticVarList`（= 函数内 STATIC 与**非 GLOBAL** 的全局静态 #DIM；
     * 进表见 :3270-3272 与 :3342）。CHARADATA 用户变量不在任何一份表里
     * （:3220-3244 只进 UserDefinedCharaVarList），GLOBAL 用户变量进的是
     * `userDefinedGlobalVarList`（:3265-3268），都不由本方法复位。
     */
    fun resetLocalValues() {
        for (st in localsByFunc.values) {
            for (lv in st.locals.values) {
                lv.ints?.fill(0)
                lv.strs?.fill(0)
            }
        }
        for (v in userVars.values) {
            if (v.spec.isStatic && !v.spec.isConst && !v.spec.isGlobal && !v.def.isCharacterData) {
                v.setDefault()
            }
        }
        for (map in privateVars.values) {
            for (v in map.values) if (v.spec.isStatic && !v.spec.isConst) v.setDefault()
        }
    }

    /** 丢弃所有局部变量实例（换文件时）。spec: VariableData.cs:3377-3383 */
    fun clearLocalValues() {
        localsByFunc.clear()
    }

    /** RESETDATA：局部 + 全局 + 清空角色。spec: VariableEvaluator.cs:1336-1346 */
    fun resetData() {
        resetLocalValues()
        resetValues()
        deleteAllCharacters()
    }

    /** 读入 ITEM.CSV 的第三列（常量数组）。spec: ConstantData.cs:1803-1813 */
    fun loadItemPrice(values: LongArray) {
        val price = intCol1(VariableCode.lowIndex(VariableCode.ITEMPRICE))
        itemPriceSource = values.copyOf(price.size)
        itemPriceSource.copyInto(price, 0, 0, itemPriceSource.size)
    }

    /** CSV 装载完成后，用 STR.CSV 重刷 STR 初始值（只在开局调用）。 */
    fun reloadStrNames() {
        val strCol = gStr1[VariableCode.lowIndex(VariableCode.STR)]
        val csvStr = registry.csvTables[10]
        for (i in 0 until minOf(strCol.size, csvStr.size)) {
            val name = csvStr[i]
            strCol[i] = if (name.isEmpty()) 0 else pool.intern(name)
        }
    }

    // ==================================================================
    // 给解析层 / 语句层的元信息
    // ==================================================================

    /**
     * 名称解析（系统 + 用户），供 IdentifierResolver 委托。
     *
     * [subKey] 对应源码里的 `变量@函数`（原版 `IdentifierDictionary.GetVariableToken(key, subKey, …)`，
     * `IdentifierDictionary.cs:429-495`），只影响**绑定是否成立**，不改写解析目标：
     * - 私有 / 全局用户变量命中且 [subKey] 非 null ⇒ 原版 `:440-443` UsedAtForPrivVar、
     *   `:484-487` UsedAtForGlobalVar 都在**解析期**抛 CodeEE ⇒ 这里返回 null，让审计按
     *   「运行期必红」报出（detail 由审计侧区分「非法 @」与「名字不存在」）。
     * - LOCAL/ARG/LOCALS/ARGS 命中且 [subKey] 非 null ⇒ 合法（`:448-473` 按 subKey 建独立
     *   local token，长度取**当前函数**声明，不是 subKey 指向的函数）⇒ 照旧返回元信息。
     * - 其余（系统变量带 @）按同一 CodeEE 口径返回 null。
     *
     * 默认 null ⇒ 全部既有调用点行为不变；只有 `SymbolLinkAudit` 传 subKey。
     */
    fun resolveVarInfo(
        name: String,
        functionKey: String? = null,
        subKey: String? = null,
    ): VarInfo? {
        val resolved = if (functionKey == null) userVar(name) else userVar(name, functionKey)
        resolved?.let { v ->
            if (subKey != null) return null
            return VarInfo(
                name = v.def.name,
                operandType = if (v.def.type == VarType.INT) OperandType.INT else OperandType.STR,
                dimension = v.def.dimension,
                isCharacterData = v.def.isCharacterData,
                isConst = v.spec.isConst,
            )
        }
        val def = registry.def(name) ?: return null
        if (subKey != null && def.storage != Storage.L_INT && def.storage != Storage.L_STR) return null
        return def.toVarInfo()
    }

    /** 存档遍历用：系统变量里 IsSavedata 的那些。 */
    fun savedataDefs(): List<VarDef> = registry.all().filter { it.isSavedata }

    /**
     * 观测用：全局整数列总字节数（1D+2D+3D）。
     *
     * 列改成按需分配后这里按**声明尺寸**算（与改前的实际分配总量同值）：它是「这一列有多大」
     * 的口径，不是「现在占了多少堆」——后者由堆探针自己量（`perf/HeapCompositionProbeTest` §5）。
     */
    fun estimateGlobalIntBytes(): Long =
        (sizes.int1D.sumOf { it.toLong() } + gInt2Len.sumOf { it.toLong() } + gInt3Len.sumOf { it.toLong() }) * 8L

    /** 观测用：单个角色占用的整数字节数（系统 84 列 + 用户列）。 */
    fun estimateBytesPerCharacter(): Long =
        (characters.int0 + characters.int1 + characters.int2).sumOf { it.blockSize.toLong() } * 8L
}

/**
 * 变量写观察口（DBG4 P3 log 事件流；挂点见 [VariableStore.writeProbe]）。
 *
 * - [watches] 是热路径守卫的名单判定：写路径百万级/秒，实现必须 O(1) 且 **miss 零分配**
 *   （`name.uppercase()` 在 miss 路径会分配，禁用；建议装配期把每个监视名的原形与其
 *   大写形同时入集，判定只做一次 [Set.contains]）。
 * - [onWriteInt] / [onWriteStr] 在写入**入口、校验前**回调（VariableStore.writeInt 的
 *   守卫位）：校验失败的写入也发事件，`value` = 请求值；old→new 由实现方自行回读
 *   （命令层持有 store 引用，在回调里读旧值，本接口不搬运）。
 * - 实现不得阻塞、不得抛（探针异常由 store 的 runCatching 吞口吞弃，不反噬写入语义）。
 */
interface VarWriteProbe {

    /** 名单判定：true 才回调 [onWriteInt] / [onWriteStr]。 */
    fun watches(name: String): Boolean

    /** 整数变量写入事件（[VariableStore.writeInt] 入口）。 */
    fun onWriteInt(name: String, indices: LongArray, value: Long) {}

    /** 字符串变量写入事件（[VariableStore.writeStr] 入口）。 */
    fun onWriteStr(name: String, indices: LongArray, value: String) {}
}
