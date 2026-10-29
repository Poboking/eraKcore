package com.eratw.port.core.expr

/** 操作数静态类型。对应原版 GetOperandType() 的 typeof(long)/typeof(string)。 */
enum class OperandType {
    INT,
    STR,
}

/**
 * 表达式 AST。求值方法直接挂在节点上（对应原版 AExpression 的虚方法
 * GetIntValue/GetStrValue，spec: AExpression.cs:23-40）。
 *
 * 静态类型不变量：所有节点在**解析期**即确定 operandType；类型不匹配的组合
 * 在解析期报错（对应 OperatorMethodManager.ReduceXxxTerm 的检查，M1 不做运行期二次检查）。
 */
sealed interface Expr {
    val operandType: OperandType
    val line: Int
    val col: Int

    fun getIntValue(ctx: EvalContext): Long
    fun getStrValue(ctx: EvalContext): String

    fun toValue(ctx: EvalContext): Value =
        // 小整数走缓存（见 [Value.intV] 的 KDoc）：本方法是「表达式结果 → Value」的唯一
        // 默认出口，JFR 实测在这里分配 26.99 MiB 的 `Value$IntV`。
        if (operandType == OperandType.INT) Value.intV(getIntValue(ctx))
        else Value.StrV(getStrValue(ctx))
}

// ---------------------------------------------------------------------------
// 字面量
// ---------------------------------------------------------------------------

/** spec: SingleLongTerm.cs（ExpressionParser.cs:513-515 入栈） */
data class IntLiteral(
    val value: Long,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override val operandType get() = OperandType.INT
    override fun getIntValue(ctx: EvalContext) = value

    /** 原版未覆写 GetStrValue，默认返回 ""（AExpression.cs:28-31）；静态类型守卫下不会被调用。 */
    override fun getStrValue(ctx: EvalContext) = ""
}

/** spec: SingleStrTerm.cs（ExpressionParser.cs:509-511 入栈） */
data class StrLiteral(
    val value: String,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override val operandType get() = OperandType.STR
    override fun getIntValue(ctx: EvalContext) = 0L
    override fun getStrValue(ctx: EvalContext) = value
}

/** #FUNCTION 声明参数的省略默认值占位。 spec: NullTerm.cs */
data class NullTerm(
    val isInt: Boolean,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override val operandType get() = if (isInt) OperandType.INT else OperandType.STR
    override fun getIntValue(ctx: EvalContext) = 0L
    override fun getStrValue(ctx: EvalContext) = ""
}

// ---------------------------------------------------------------------------
// 变量引用
// ---------------------------------------------------------------------------

/**
 * 变量引用（含 `V:1` 下标形态与省略下标补全后的形态）。
 * 省略规则与 TARGET/0 的填充见 ExprParser.buildVariableTerm。
 *
 * [isNoArg] 对应原版 VariableNoArgTerm（全部下标省略、SystemNoTarget 等），spec: VariableParser.cs:76,92,113,130,141。
 *
 * ⚠ 原版 VariableNoArgTerm **不是**「按下标 0 / 当前 TARGET 取值」，而是错误哨兵：
 * `GetIntValue` / `GetStrValue` / `GetValue` / `SetValue` / `ChangeValue` / `GetFixedVariableTerm`
 * **逐一抛 `CodeEE(MissingVarArg)`**（VariableNoArgTerm.cs:17-70）。本模型不带该拒绝节点，
 * 由存储层 [VariableReader] 的「下标个数必须等于变量声明的槽位数」校验承担同一拒绝
 * （VariableStore 的 checkElement / checkUserElementImpl：空下标数组在 2D/3D 与角色变量上
 * 都落 `indices.size != argCount` 分支）⇒ 求值结果同样是硬错误，只是文案不同。
 */
data class VarRef(
    val name: String,
    val indices: List<Expr>,
    val isNoArg: Boolean = false,
    override val operandType: OperandType,
    val isConst: Boolean = false,
    val subKey: String? = null,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override fun getIntValue(ctx: EvalContext): Long {
        // 0 下标变量（绝大多数全局/角色变量读）复用同一份空数组：每个 VarRef 求值都 new 一个
        // `LongArray(0)` 会在世界初始化这种百万级访问里制造无谓的短命对象。
        val n = indexArray.size
        if (n == 0) {
            val reader = ctx.variables
            val h = bindHandle(reader, wantStr = false)
            if (h != VariableHandles.NONE) return (reader as VariableHandles).readIntH(h, NO_INDICES)
            return reader.readInt(name, NO_INDICES)
        }
        // 下标缓冲向读取方**借**（见 VariableReader.borrowIndices）：带下标的访问是环体里的
        // 高频路径，逐次 new LongArray 会把 GC 喂饱；借还按深度栈式复用，可重入。
        val reader = ctx.variables
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = indexArray[i].getIntValue(ctx)
            val h = bindHandle(reader, wantStr = false)
            if (h != VariableHandles.NONE) return (reader as VariableHandles).readIntH(h, idx)
            return reader.readInt(name, idx)
        } finally {
            reader.returnIndices()
        }
    }

    override fun getStrValue(ctx: EvalContext): String {
        val n = indexArray.size
        if (n == 0) {
            val reader = ctx.variables
            val h = bindHandle(reader, wantStr = true)
            if (h != VariableHandles.NONE) return (reader as VariableHandles).readStrH(h, NO_INDICES)
            return reader.readStr(name, NO_INDICES)
        }
        val reader = ctx.variables
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = indexArray[i].getIntValue(ctx)
            val h = bindHandle(reader, wantStr = true)
            if (h != VariableHandles.NONE) return (reader as VariableHandles).readStrH(h, idx)
            return reader.readStr(name, idx)
        } finally {
            reader.returnIndices()
        }
    }

    // ------------------------------------------------------------------
    // 写（PERF 2026-10-27）：语句层写路径接站点句柄 + 下标借还池
    // ------------------------------------------------------------------

    /**
     * 把 [value] 写进本访问点（`SET X = …` 的落点）。
     *
     * ## 为什么放在 `VarRef` 上，而不是让语句层自己拼
     *
     * 读路径（[getIntValue]）在 T2-61 就拿到了两样东西：**访问点自己的句柄缓存**
     * （[bindInt]，结构上零冲突）与**下标借还池**（[VariableReader.borrowIndices]）。
     * 语句层写路径两样都没用 —— 它走 `ctx.variables.writeInt(name, idx, value)`，
     * 其中 `name` 每次都要过一次 `bindOf` 的 512 槽直映（真产物实测冲突率 66.5%），
     * `idx` 每次 `LongArray(n)` 现分配（JFR 分配画像里 `resolveIndices` 83.7 MB，
     * **全应用第 5 大分配点**，而 `SET` 占这个负载语句执行的 99.9%）。
     *
     * 搬到 [VarRef] 上之后，句柄缓存与借还池**自动复用读路径那一套**，
     * 语句层只剩一次虚调用。
     *
     * ## 语义红线
     *
     * - 句柄解析不出（[VariableHandles.NONE]）或 [isNoArg]：**原样回落按名路径**
     *   —— 迟到的 `#DIM` 必须能被后续写看见（`bindHandle` 在 NONE 时不缓存），
     *   `isNoArg` 的错误文案也必须照旧。
     * - 按句柄写与按名写共用同一个函数体（`VariableStore.writeIntEntry`），
     *   写观察口收到的名字仍是 [name] 的**原文拼写**（`VarWriteProbe.watches` 大小写敏感）。
     */
    fun setIntValue(ctx: EvalContext, value: Long) {
        val n = indexArray.size
        val reader = ctx.variables
        if (n == 0) {
            val h = bindHandle(reader, wantStr = false)
            if (h != VariableHandles.NONE) {
                (reader as VariableHandles).writeIntH(h, name, NO_INDICES, value)
            } else {
                writerOf(reader).writeInt(name, NO_INDICES, value)
            }
            return
        }
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = indexArray[i].getIntValue(ctx)
            val h = bindHandle(reader, wantStr = false)
            if (h != VariableHandles.NONE) (reader as VariableHandles).writeIntH(h, name, idx, value)
            else writerOf(reader).writeInt(name, idx, value)
        } finally {
            reader.returnIndices()
        }
    }

    /**
     * `SET X += delta` / `++` / `--` 的落点；返回写入后的新值（与 `plusInt` 一致）。
     *
     * 与 [setIntValue] 同一套句柄/借还口径。注意 `+=` 的**语义**在语句层已经被解析期
     * 折成「整式放进 Term」或「IsConst + AddConst」，这里只负责把算好的增量落进变量。
     */
    fun addIntValue(ctx: EvalContext, delta: Long): Long {
        val n = indexArray.size
        val reader = ctx.variables
        if (n == 0) {
            val h = bindHandle(reader, wantStr = false)
            if (h != VariableHandles.NONE) return (reader as VariableHandles).plusIntH(h, NO_INDICES, delta)
            return reader.plusInt(name, NO_INDICES, delta)
        }
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = indexArray[i].getIntValue(ctx)
            val h = bindHandle(reader, wantStr = false)
            if (h != VariableHandles.NONE) return (reader as VariableHandles).plusIntH(h, idx, delta)
            return reader.plusInt(name, idx, delta)
        } finally {
            reader.returnIndices()
        }
    }

    /** 同 [setIntValue] 的字符串侧。 */
    fun setStrValue(ctx: EvalContext, value: String) {
        val n = indexArray.size
        val reader = ctx.variables
        if (n == 0) {
            val h = bindHandle(reader, wantStr = true)
            if (h != VariableHandles.NONE) {
                (reader as VariableHandles).writeStrH(h, name, NO_INDICES, value)
            } else {
                writerOf(reader).writeStr(name, NO_INDICES, value)
            }
            return
        }
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = indexArray[i].getIntValue(ctx)
            val h = bindHandle(reader, wantStr = true)
            if (h != VariableHandles.NONE) (reader as VariableHandles).writeStrH(h, name, idx, value)
            else writerOf(reader).writeStr(name, idx, value)
        } finally {
            reader.returnIndices()
        }
    }

    /**
     * 回落路径的写通道探测：读写是**独立能力**（[VariableWriter]，T2-23 的口径），
     * 拿不到就显式失败，绝不猜。这条路径只在「句柄解析不出」时走到
     * （`isNoArg` 错误哨兵 / 迟到的 `#DIM` / 只读宿主），所以探测本身不在热路径上。
     */
    private fun writerOf(reader: VariableReader): VariableWriter =
        reader as? VariableWriter
            ?: throw com.eratw.port.core.err.CodeEE(
                "变量池不支持写入（未实现 VariableWriter）：$name",
            )

    // ------------------------------------------------------------------
    // 装期绑定（T2-61）：访问点自己记住「名字 → 句柄」
    // ------------------------------------------------------------------

    /**
     * 该访问点缓存的整型 / 字符串句柄，键 = `(读取方身份, 作用域身份)`。
     *
     * ## 为什么按访问点缓存，而不是继续用按名字的全局表
     *
     * 全局表（`VariableStore.bindOf`，512 槽直接映射）在真产物实测里**命中率只有 33.45%**：
     * `t61.jfr` 一轮取证 2.75 亿次变量访问，其中 1.83 亿次（66.5%）是「同一槽里是另一个
     * 名字」—— 语料的热名字集合比 512 大得多，任何按名字直接映射的表都会被冲干净，而加大
     * 表又会让随机下标跳出 L1（4096 槽实测慢 12%）。按访问点缓存没有这个问题：每个
     * `VarRef` 自己就是一个槽，结构上不存在冲突。
     *
     * ## 键为什么还要带作用域
     *
     * 私有 `#DIM` 变量的句柄只在声明它的函数里有效。[VariableHandles.nameScoped] 告诉我们
     * 这个名字是否绑作用域 —— 全局/系统变量不绑（跨调用恒命中；实测作用域键只贡献 0.03%
     * 的未命中），只有「某处声明过同名私有 `#DIM`」的名字才比较 [VariableReader.scopeKey]。
     *
     * ## 不可缓存的形态
     *
     * - `isNoArg`（原版 `VariableNoArgTerm` 的错误哨兵，含依赖当前 `TARGET` 的形态，
     *   语义红线 §4 第 7 条）：一律回退按名路径，保证报错文案与触发条件不变。
     * - 解析不出（[VariableHandles.NONE]）：不缓存 —— 迟到的 `#DIM` 必须能被后续求值看见。
     *
     * ## 并发前提
     *
     * 这些字段是 `VarRef` 上的**可变**状态，只在 VM 线程上读写（ADR-003 单 VM 线程；
     * 脚本无法在调用内并行）。与它同一前提的还有 `VariableStore` 的 `frames` /
     * `currentPrivMap` / 绑定缓存本身 —— 这一层不是新增的并发要求，而是既有的。
     *
     * ## 为什么全部 `@Transient`
     *
     * 它们是**求值期状态**，不是 AST 模型的一部分。模型有四处反射遍历会看到实例字段：
     * `ArgInterner.buildShape`（装载期折叠判等）、`ProgramDiagnostics` 的 shape 探针
     * （真机 pool-audit 输出契约）、`ArgSignatureCheck.fieldsOf`（符号审计下降）、
     * `data class` 的 `equals`/`toString`（后者不进字段遍历，但同样只认构造参数）。
     * 前三处已按 `Modifier.isTransient` 跳过（与 `KeywordIndexArg` 的
     * `memoKeyword`/`memoValue`/`memoReader` 同一档：**运期缓存不属于结点结构**）。
     *
     * 不跳过的后果不是「测试要改期望」那么轻：`ArgInterner` 会把缓存里的
     * `VariableStore` 当成结点子图继续下降（实测在 e2e 全量执行后触发
     * `InaccessibleObjectException`，反射进 `java.util.HashSet` 的内部字段），
     * 且「同一结点在不同求值阶段」会被判成不同形态 ⇒ 去重漏合并。
     */
    @Transient private var bindReader: Any? = null
    @Transient private var bindScope: Any? = null
    @Transient private var bindScopeSensitive: Boolean = false
    @Transient private var bindInt: Int = VariableHandles.NONE
    @Transient private var bindStr: Int = VariableHandles.NONE

    private fun bindHandle(reader: VariableReader, wantStr: Boolean): Int {
        if (isNoArg) return VariableHandles.NONE
        val handles = reader as? VariableHandles ?: return VariableHandles.NONE
        val cached = if (wantStr) bindStr else bindInt
        if (bindReader === reader && cached != VariableHandles.NONE) {
            if (!bindScopeSensitive || bindScope === reader.scopeKey()) return cached
            // 作用域换了：两条句柄都可能指向上一条作用域的私有实例，一起作废。
            bindInt = VariableHandles.NONE
            bindStr = VariableHandles.NONE
        }
        if (bindReader !== reader) {
            // 读取方换了（「同一个 AST 配不同变量池」是设计内场景：测试夹具、宿主换 store
            // 重跑 —— `Vm.varHandleOwner` 的同款口径）。句柄是**变量池私有**的下标，跨池
            // 无意义；两个方向必须一起作废 —— 只重绑当前方向的话，另一方向还挂着旧池的
            // 句柄号，之后在同一池命中它就是拿旧池的下标读新池（同号时是静默错值，越界时
            // 才退化成「句柄无效」）。
            bindInt = VariableHandles.NONE
            bindStr = VariableHandles.NONE
            bindScopeSensitive = false
            bindScope = null
        }
        val h = if (wantStr) handles.resolveStr(name) else handles.resolveInt(name)
        if (h == VariableHandles.NONE) return VariableHandles.NONE
        bindReader = reader
        bindScopeSensitive = handles.nameScoped(name)
        bindScope = if (bindScopeSensitive) reader.scopeKey() else null
        if (wantStr) bindStr = h else bindInt = h
        return h
    }

    /**
     * 下标的数组形态（构造期一次转换）。
     *
     * `indices` 是 `List<Expr>`（编译期形态），热路径上 `ArrayList.get` + `Objects.checkIndex`
     * 在真机采样里占 `VarRef` 求值的相当一块；数组下标省掉边界检查的虚调用与装箱边界。
     * 列表本身仍保留在构造参数里（数据类契约/相等性不变）。
     */
    private val indexArray: Array<Expr> = indices.toTypedArray()

    private companion object {
        /** 0 下标共享空数组（只读；存储层只读不写）。 */
        private val NO_INDICES = LongArray(0)
    }
}

/**
 * 字符串关键字下标包装（`ITEM:药` 之类）。spec: VariableParser.cs:182-188（VariableStrArgTerm 包装）。
 * 求值时经 VariableReader.keywordToInteger 转成整数下标。
 *
 * ## 为什么带一层记忆（PERF 2026-10-26）
 *
 * 关键字表（`ConstantData` 的 CSV 名表 / ERD 表 / 角色名表）**装载完成后就是只读的**，
 * 而同一个 [KeywordIndexArg] 结点在整局里被求值上百万次（`CFLAG:LOCAL:约会中` 这种下标
 * 在每次 `FOR LOCAL, 1, CHARANUM` 环体里都要重算）。原实现每次求值都走
 * `keywordToInteger` → `VariableRegistry.keywordIndex` → `csvTable`（线性扫 29 张表 +
 * 每次 `substringBefore('.')` 新建 String + `equals(ignoreCase)`），真机采样里这条链
 * 独占 44% 的脚本时间（`jfr-turn.jfr`：877/1989 样本入栈）。
 *
 * 记忆判据用「关键字串 == 上次的关键字串 && 读取方 == 上次的读取方」：
 * - 关键字串：绝大多数语料是字面量 ⇒ 同一 String 实例，`==` 先走引用比较，命中即返回；
 *   非常量关键字（`ITEM:GETSTR(...)`）退化成一个内容比较后重算，行为不变。
 * - 读取方：`VariableReader` 实例 = 一份变量存储。同一结点被两份存储求值（装置里
 *   把一份 Program 喂给两个 `VariableStore`）时不会串值。
 * - **解析失败不记忆**：`keywordToInteger` 抛出的 `CodeEE` 每次都照抛，错误时机与文案不变
 *   （折叠到装载期会把「运行期才报的未定义关键字」变成装载失败，属语义变更，不做）。
 *
 * 两个记忆字段是**派生状态**，不进结构判等/去重：`ArgInterner.buildShape` 按名跳过
 * （与 `VarRef.indexArray` 同款处置，见该处 KDoc）。
 */
data class KeywordIndexArg(
    val expr: Expr,
    val varName: String,
    val dim: Int,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    /** 上次解析成功时的关键字串；null = 尚未解析过。 */
    @Volatile
    private var memoKeyword: String? = null

    /** 上次解析成功时的结果。 */
    @Volatile
    private var memoValue: Long = 0L

    /** 上次解析成功时的读取方（`VariableReader` 实例身份）。 */
    @Volatile
    private var memoReader: Any? = null

    override val operandType get() = OperandType.INT

    override fun getIntValue(ctx: EvalContext): Long {
        val reader = ctx.variables
        val keyword = expr.getStrValue(ctx)
        if (keyword == memoKeyword && reader === memoReader) return memoValue
        val v = reader.keywordToInteger(varName, dim, keyword)
        memoKeyword = keyword
        memoValue = v
        memoReader = reader
        return v
    }

    override fun getStrValue(ctx: EvalContext) = ""
}

// ---------------------------------------------------------------------------
// 函数调用
// ---------------------------------------------------------------------------

/**
 * 函数调用。解析期由 [IdentifierResolver.resolveFunction] 判定存在性与返回类型：
 * 未命中时解析器按原版 `IdentifierDictionary.ThrowException` 报错（spec: ExpressionParser.cs:376-392）；
 * 命中即用该类型参与静态类型检查（原版 `GetFunctionMethod` 在解析期返回带类型的对象）。
 * TODO(verify): 参数个数/类型检查（C# `CheckArgumentType`，spec: IdentifierDictionary.cs:580-584）仍属绑定层。
 *
 * args 允许 null 元素（参数空位），见 reduceArguments。
 */
data class FuncCall(
    val name: String,
    val args: List<Expr?>,
    override val operandType: OperandType,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override fun getIntValue(ctx: EvalContext): Long {
        val r = ctx.callFunctionRef(name, args)
        return (r as? Value.IntV)?.v ?: throw ExprEvalException("函数 $name 返回了非整数结果")
    }

    override fun getStrValue(ctx: EvalContext): String {
        val r = ctx.callFunctionRef(name, args)
        return (r as? Value.StrV)?.v ?: throw ExprEvalException("函数 $name 返回了非字符串结果")
    }
}

// ---------------------------------------------------------------------------
// 一元 / 后置一元
// ---------------------------------------------------------------------------

/** 前置一元：- ! ~ ++ --（+ 在解析期被消除，直接返回操作数）。spec: OperatorMethodManager.cs:537-599 */
data class Unary(
    val op: OperatorCode,
    val operand: Expr,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    init {
        require(op == OperatorCode.MINUS || op == OperatorCode.NOT || op == OperatorCode.BIT_NOT ||
            op == OperatorCode.INCREMENT || op == OperatorCode.DECREMENT)
    }

    override val operandType get() = OperandType.INT

    override fun getIntValue(ctx: EvalContext): Long {
        return when (op) {
            OperatorCode.MINUS -> {
                // spec: OperatorMethodManager.cs:559-566 —— MinusInt 先取一次操作数判 Long.MIN_VALUE
                // 打系统行，再第二次求值并取负；副作用（RAND 抽号等）随双倍，原样复刻。
                // （wave/audit-vm 曾按单求值登记「已知偏离·待复刻」，此处收口；语料 13 处
                // -RAND 的 RNG 序列随原版口径移动，golden E2E 不含 CASINO 无回归。）
                if (operand.getIntValue(ctx) == Long.MIN_VALUE) ctx.printSystemLine("警告：对 -9223372036854775808 取负不生效")
                -operand.getIntValue(ctx)
            }
            OperatorCode.NOT -> if (operand.getIntValue(ctx) == 0L) 1L else 0L
            OperatorCode.BIT_NOT -> operand.getIntValue(ctx).inv()
            OperatorCode.INCREMENT -> changeValue(ctx, 1L)
            OperatorCode.DECREMENT -> changeValue(ctx, -1L)
            else -> throw ExprEvalException("非法一元运算符 $op")
        }
    }

    private fun changeValue(ctx: EvalContext, delta: Long): Long {
        // spec: OperatorMethodManager.cs:609-627 → VariableTerm.ChangeValue（VariableTerm.cs:202-223）
        // —— 下标只求值一遍，PlusValue 返回加完的新值；旧值不单独读。
        // 下标缓冲走借还（VariableReader.borrowIndices），不逐次 new LongArray。
        val vr = operand as VarRef
        val n = vr.indices.size
        val reader = ctx.variables
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = vr.indices[i].getIntValue(ctx)
            return reader.plusInt(vr.name, idx, delta)
        } finally {
            reader.returnIndices()
        }
    }

    override fun getStrValue(ctx: EvalContext) = ""
}

/** 后置 ++/--。spec: OperatorMethodManager.cs:629-655 —— 返回自增/自减前的旧值 */
data class UnaryAfter(
    val op: OperatorCode,
    val operand: Expr,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    init {
        require(op == OperatorCode.INCREMENT || op == OperatorCode.DECREMENT)
    }

    override val operandType get() = OperandType.INT

    override fun getIntValue(ctx: EvalContext): Long {
        val vr = operand as VarRef
        val n = vr.indices.size
        val reader = ctx.variables
        val idx = reader.borrowIndices(n)
        val newVal = try {
            for (i in 0 until n) idx[i] = vr.indices[i].getIntValue(ctx)
            reader.plusInt(vr.name, idx, if (op == OperatorCode.INCREMENT) 1L else -1L)
        } finally {
            reader.returnIndices()
        }
        return if (op == OperatorCode.INCREMENT) newVal - 1 else newVal + 1
    }

    override fun getStrValue(ctx: EvalContext) = ""
}

// ---------------------------------------------------------------------------
// 二元 / 三目
// ---------------------------------------------------------------------------

data class Binary(
    val op: OperatorCode,
    val left: Expr,
    val right: Expr,
    override val operandType: OperandType,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override fun getIntValue(ctx: EvalContext): Long {
        // 字符串比较（== != > < >= <=）：spec: OperatorMethodManager.cs:159-373
        // 注意：比较结果的 operandType 是 INT，必须按**操作数**类型分派
        if (left.operandType == OperandType.STR && right.operandType == OperandType.STR) {
            return strCompare(op, ctx, left, right)
        }
        return when (op) {
            // && 短路：原版 if (arg0 != 0 && arg1 != 0)，arg0==0 时 arg1 不求值。
            // spec: OperatorMethodManager.cs:383-391
            OperatorCode.AND ->
                if (left.getIntValue(ctx) == 0L) 0L else if (right.getIntValue(ctx) != 0L) 1L else 0L
            // || 短路：spec: OperatorMethodManager.cs:393-409
            OperatorCode.OR ->
                if (left.getIntValue(ctx) != 0L) 1L else if (right.getIntValue(ctx) != 0L) 1L else 0L
            // 除/余先求右操作数（原版先取除数判 0）。spec: OperatorMethodManager.cs:121-157
            OperatorCode.DIV, OperatorCode.MOD -> {
                val r = right.getIntValue(ctx)
                if (r == 0L) throw ExprEvalException("除数为 0")
                val l = left.getIntValue(ctx)
                // C# 整除向零取整、% 符号随被除数，与 Kotlin Long./ 与 Long.% 语义一致
                if (op == OperatorCode.DIV) {
                    // `long.MinValue / -1` 是 .NET 唯一会溢出的整除：C# 抛 OverflowException，
                    // JVM 的 ldiv 静默回绕成 MIN_VALUE。按原版抛错，不回绕。
                    // spec: OperatorMethodManager.cs:136（arguments[0].GetIntValue(exm) / intValue）
                    if (l == Long.MIN_VALUE && r == -1L) {
                        throw ExprEvalException("除算溢出：-9223372036854775808 / -1")
                    }
                    l / r
                } else {
                    // `long.MinValue % -1` 在 C# 与 JVM 都得 0（ECMA 除余只在**除法**上抛溢出），不复刻
                    l % r
                }
            }
            // 与非/或非：原版是 C# 的 `arg0 == 0 || arg1 == 0` / `arg0 == 0 && arg1 == 0`，
            // **左操作数已决定结果时右侧不求值**（`&&`/`||` 的短路）——副作用与 RAND 消耗随之跳过。
            // spec: OperatorMethodManager.cs:439-446（NandIntInt，判据在 :441）、
            //       同文件 :457-464（NorIntInt，判据在 :459）
            OperatorCode.NAND ->
                if (left.getIntValue(ctx) == 0L) 1L else if (right.getIntValue(ctx) == 0L) 1L else 0L
            OperatorCode.NOR ->
                if (left.getIntValue(ctx) != 0L) 0L else if (right.getIntValue(ctx) == 0L) 1L else 0L
            else -> {
                val l = left.getIntValue(ctx)
                val r = right.getIntValue(ctx)
                when (op) {
                    OperatorCode.PLUS -> l + r
                    OperatorCode.MINUS -> l - r
                    OperatorCode.MULT -> l * r
                    OperatorCode.EQUAL -> if (l == r) 1L else 0L
                    OperatorCode.NOT_EQUAL -> if (l != r) 1L else 0L
                    OperatorCode.GREATER -> if (l > r) 1L else 0L
                    OperatorCode.LESS -> if (l < r) 1L else 0L
                    OperatorCode.GREATER_EQUAL -> if (l >= r) 1L else 0L
                    OperatorCode.LESS_EQUAL -> if (l <= r) 1L else 0L
                    // ^^ 原版先把两个操作数取进局部变量，**不**短路（两侧都求值）。
                    // spec: OperatorMethodManager.cs:411-429
                    OperatorCode.XOR -> if ((l == 0L) != (r == 0L)) 1L else 0L
                    OperatorCode.BIT_AND -> l and r
                    OperatorCode.BIT_OR -> l or r
                    OperatorCode.BIT_XOR -> l xor r
                    // C# 对 64 位操作数的移位计数取低 6 位；JVM 同样按 63 取模，行为一致。
                    OperatorCode.RIGHT_SHIFT -> l shr r.toInt()
                    OperatorCode.LEFT_SHIFT -> l shl r.toInt()
                    else -> throw ExprEvalException("非法二元整数运算符 ${OperatorCode.toOperatorString(op)}")
                }
            }
        }
    }

    override fun getStrValue(ctx: EvalContext): String = when (op) {
        // 字符串语境的 `+` 重载：str+str 拼接。spec: OperatorMethodManager.cs:28-45
        OperatorCode.PLUS -> left.getStrValue(ctx) + right.getStrValue(ctx)
        // spec: OperatorMethodManager.cs:75-118 —— str*int（或 int*str）重复字符串
        OperatorCode.MULT -> {
            val intValue: Long
            val strValue: String
            if (left.operandType == OperandType.INT) {
                intValue = left.getIntValue(ctx)
                strValue = right.getStrValue(ctx)
            } else {
                strValue = left.getStrValue(ctx)
                intValue = right.getIntValue(ctx)
            }
            if (intValue < 0) throw ExprEvalException("负数($intValue)不能与字符串相乘")
            if (intValue >= 10000) throw ExprEvalException("重复次数($intValue)不能大于等于 10000")
            if (strValue.isEmpty() || intValue == 0L) ""
            else strValue.repeat(intValue.toInt())
        }
        else -> throw ExprEvalException("非法二元字符串运算符 ${OperatorCode.toOperatorString(op)}")
    }
}

/**
 * 字符串比较求值（>= 与 <= 的字符串版本在原版中都是 `c < 0`——两处官方 bug，详见 specs/verify-notes.md §1）。
 * spec: OperatorMethodManager.cs:331(GreaterEqualStrStr 应为 c>=0 实为 c<0)、
 *       同文件 LessEqualStrStr 应为 c<=0 实为 c<0（相等泄漏）。
 * 已由 m1-verify1824 对照官方源码 github.com/0x00000FF/Emuera@301ce4a 确认为官方 bug，按原样复刻。
 *
 * **不得**把这里的 GREATER_EQUAL / LESS_EQUAL 拿去装配 `SELECTCASE` 的 `CASE lo TO hi`
 * 区间判定（CORE-01，2026-10-23）：原版的 TO 形态根本不走运算符重载，是 CaseExpression 里
 * 一段独立的 `string.Compare(...) <= 0` 判定（spec: CaseExpression.cs:62-70），只有源码里
 * 真写了 `>=` / `<=` 的表达式位才受这两处 bug 影响。区间判定请用 [strCaseRange]。
 */
internal fun strCompare(op: OperatorCode, ctx: EvalContext, left: Expr, right: Expr): Long {
    val l = left.getStrValue(ctx)
    val r = right.getStrValue(ctx)
    val c = l.compareTo(r)  // String.Compare(..., StringComparison.Ordinal) ≙ Kotlin String.compareTo
    return when (op) {
        OperatorCode.EQUAL -> if (c == 0) 1L else 0L
        OperatorCode.NOT_EQUAL -> if (c != 0) 1L else 0L
        OperatorCode.GREATER -> if (c > 0) 1L else 0L
        OperatorCode.LESS -> if (c < 0) 1L else 0L
        OperatorCode.GREATER_EQUAL -> if (c < 0) 1L else 0L  // spec: 官方 bug，原样复刻（verify-notes.md §1）
        OperatorCode.LESS_EQUAL -> if (c < 0) 1L else 0L     // spec: 官方 bug，相等泄漏（verify-notes.md §1）
        else -> throw ExprEvalException("非法字符串比较运算符")
    }
}

/**
 * 字符串 `SELECTCASE … CASE lo TO hi` 的区间判定装配（CORE-01，2026-10-23）。
 *
 * 原版 TO 形态**不走运算符重载**，而是 CaseExpression 上独立的区间判定
 * （spec: CaseExpression.cs:62-70 字符串版；整数版同文件 :45-60 同构，编译器侧走
 * `_GE`/`_LE` 整型指令不受影响）：
 * `string.Compare(LeftTerm, Is, Ordinal) <= 0` 成立时再判 `string.Compare(Is, RightTerm, Ordinal) <= 0`，
 * 下界不成立直接 `return false`（RightTerm 连求值都不求）。
 *
 * 移植侧旧实现写成 `target GREATER_EQUAL lo && target LESS_EQUAL hi`，把 [strCompare] 的
 * 两个官方 bug（`>=` 完全反向、`<=` 相等泄漏）接进了区间判定，命中集合整体取反。
 * bug 本体不许修（ADR-005：源码里真写 `>=`/`<=` 的表达式位必须照旧），所以这里**绕开运算符**，
 * 用不带 bug 的 `>` 表达同一个「小于等于」：`compare(a, b) <= 0` ⇔ `!(a > b)`
 * （GreaterStrStr / LessStrStr 均正常，specs/verify-notes.md 附表的复刻纪律那条）。
 *
 * 装配成 `AND( NOT(lo > target), NOT(target > hi) )`，同时保住原版的**求值顺序与短路**：
 * [Binary] 的 `&&` 分支左假即跳过右侧（本文件 AND 分支的 OperatorMethodManager.cs:383-391
 * 口径）⇒ 上界表达式只在下界成立时求值，与 CaseExpression.cs:66-69 一致。
 *
 * 比较基准：原版此处显式用 `StringComparison.Ordinal`（不受 Config.SCExpression 影响），
 * [strCompare] 侧同为 Ordinal 口径 ⇒ 两者一致。
 *
 * 形态说明：两条边界各是一条 `Unary(NOT, Binary(GREATER, …))`，**不是**新的 Expr 节点 ——
 * `Expr` 是 sealed 接口，新增节点要同步改 `ExprCanonicalizer` / `ArgInterner` / `BootMetadata`
 * / `ErbcWriter` 四处 exhaustive `when`（都不在本路文件所有权内）。降级遍历也不受影响：
 * `ErbProgramCompiler.lowerExpr` 的 Unary 分支照常递归操作数，上界那条仍落在 `&&` 右侧的
 * **短路位**（与改前同一口径：可能不执行的调用不前移，运行期在真正走到的一刻求值）。
 *
 * @param target SELECTCASE 目标（编译器侧是只读一次的目标槽哨兵，见 ErbProgramCompiler.selectTargetRead）
 * @param lo CASE 的 `TO` 左端（对应原版 LeftTerm）
 * @param hi CASE 的 `TO` 右端（对应原版 RightTerm）
 */
internal fun strCaseRange(target: Expr, lo: Expr, hi: Expr, line: Int): Expr {
    val lowerOk = Unary(
        OperatorCode.NOT,
        Binary(OperatorCode.GREATER, lo, target, OperandType.INT, line),
        line,
    )   // spec: CaseExpression.cs:66 —— compare(lo, target) <= 0
    val upperOk = Unary(
        OperatorCode.NOT,
        Binary(OperatorCode.GREATER, target, hi, OperandType.INT, line),
        line,
    )   // spec: CaseExpression.cs:68 —— compare(target, hi) <= 0
    return Binary(OperatorCode.AND, lowerOk, upperOk, OperandType.INT, line)
}

/** 三目 cond ? t : f（ERB 写作 cond ? t # f）。spec: OperatorMethodManager.cs:657-691 */
data class Ternary(
    val cond: Expr,
    val ifTrue: Expr,
    val ifFalse: Expr,
    override val operandType: OperandType,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override fun getIntValue(ctx: EvalContext): Long =
        if (cond.getIntValue(ctx) == 0L) ifFalse.getIntValue(ctx) else ifTrue.getIntValue(ctx)

    override fun getStrValue(ctx: EvalContext): String =
        if (cond.getIntValue(ctx) == 0L) ifFalse.getStrValue(ctx) else ifTrue.getStrValue(ctx)
}
