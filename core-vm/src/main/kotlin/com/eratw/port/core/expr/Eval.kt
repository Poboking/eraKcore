package com.eratw.port.core.expr

/** 求值期错误。对应原版 CodeEE（ExpressionParser.cs 各处 throw）。 */
class ExprEvalException(message: String) : RuntimeException(message)

/**
 * 求值时变量存取的唯一入口（M2 提供真实实现，测试用假实现注入）。
 *
 * 索引一律为求值后的 Long 数组（字符串关键字下标在解析期已被包装为
 * [KeywordIndexArg]，求值时经 [keywordToInteger] 转成 Long 再传进来）。
 */
interface VariableReader {
    /** 该变量是否为常量（用于 `++`/`--` 拒绝常量）。spec: OperatorMethodManager.cs:757-759 */
    fun isConst(name: String): Boolean

    fun readInt(name: String, indices: LongArray): Long

    /**
     * 借一块下标暂存缓冲（PERF 2026-10-26），用完必须调 [returnIndices]。
     *
     * `VarRef` 求值要在每次访问时拼下标数组：`CFLAG:LOCAL:X` 这种 2 维访问在
     * `FOR LOCAL, 1, CHARANUM` 环体里一局要跑几百万次，旧实现每次 `LongArray(n)`
     * —— 短命小对象把 GC 喂饱（真机 `jfr-turn` 采样：33 秒 54 次 GC 暂停）。
     * 实现方按**借还深度**复用（严格栈式，可重入：下标表达式本身可能又是带下标的变量访问），
     * 缺省实现退回分配 —— 测试假体不必改。
     */
    fun borrowIndices(size: Int): LongArray = LongArray(size)

    /** 归还 [borrowIndices] 借出的缓冲。与借出一一配对（try/finally）。 */
    fun returnIndices() {}

    /** 字符串变量读出 null 时必须返回 ""。spec: VariableTerm.cs:91-96 */
    fun readStr(name: String, indices: LongArray): String

    /**
     * 当前**私有变量作用域**的身份（T2-61 的装期绑定用），没有作用域概念时返回 null。
     *
     * `VarRef` 把「名字 → 句柄」的解析结果缓存在自己的字段里，缓存键就是
     * `(this, scopeKey())`：同一个读取方 + 同一个作用域 ⇒ 解析结果稳定。
     * 默认 null = 该实现方没有作用域概念（只读快照 / 测试假体），绑定结果恒稳定。
     *
     * 实现方要求：**同一作用域内返回值必须是同一个对象**（引用比较用）；作用域一变
     * （进/出函数）必须换成另一个对象，否则缓存会把上一条私有变量的解析结果当成
     * 当前作用域的。对「执行期声明新私有变量」有两种守法，实现方二选一：
     * - 声明后让 [scopeKey] 换身份（强制所有作用域敏感的站点缓存重解析）；
     * - 或者像 [com.eratw.port.core.vars.VariableStore] 那样保证不变式闭环：
     *   句柄条目一经注册不替换、声明点作废**自己的**解析缓存、且声明只发生在
     *   VM 执行前（执行期 `privateNames` 冻结 —— 见 `VariableStore.declareUserVar`）。
     */
    fun scopeKey(): Any? = null

    /**
     * 加上 delta 并返回**加完之后**的新值（前置 ++/-- 的结果即新值，
     * 后置 ++/-- 由求值器再 -1/+1 得旧值）。spec: VariableTerm.cs:202-223,
     * OperatorMethodManager.cs:601-655
     */
    fun plusInt(name: String, indices: LongArray, delta: Long): Long

    /**
     * 字符串关键字下标 → 整数下标（如 `ITEM:药` 按 Item.csv 表转编号）。
     * 对应原版 VariableStrArgTerm 的求值行为。
     *
     * [dim] = 关键字所在的**下标槽位，0 基**，与原版 VariableStrArgTerm 的 `index` 同义，
     * **不是**维度号、也不要 +1：
     * - 构造点 `VariableParser.cs:182-188` 传的就是数组位置 `i`
     *   （`new VariableStrArgTerm(id.Code, array[i], i, id.Name)`）；
     * - 消费点 `ConstantData.GetKeywordDictionary(out errPos, code, index, varname)`
     *   （`ConstantData.cs:914`）对 `index` 同样按 0 基槽位解释，且据此挑 CSV：
     *   `CDFLAG` 的 `index == 1` → cdflag1、`index == 2` → cdflag2（`ConstantData.cs:1049-1068`）；
     *   3D 用户变量按 `varname + "@" + (index + 1)` 拼 ERD 表名（`ConstantData.cs:1173-1178`）。
     * 该 TODO 已由上述行号销账。
     */
    fun keywordToInteger(varName: String, dim: Int, keyword: String): Long
}

/**
 * 变量**写**入口（T2-23）。
 *
 * 为什么单独成接口而不是塞进 [VariableReader]：读口是表达式层的必需品（`VarRef` 求值
 * 只用读），写口只有语句层与栈码需要。测试里的只读假实现（`FakeVariables` 一族）不该被
 * 迫实现一个它永远不支持的写通道 —— 那样只能在实现里抛异常，能力缺失就变成了运行期惊喜。
 * 调用方一律 `as? VariableWriter` 探测能力：拿不到就**显式失败**，绝不猜。
 *
 * 语义权威是 [com.eratw.port.core.vars.VariableStore] 的 `writeInt`/`writeStr`：
 * 校验顺序（未定义 → 类型 → 常量/伪变量 → 下标）与报错文案由它一家定义。
 */
interface VariableWriter {
    fun writeInt(name: String, indices: LongArray, value: Long)

    fun writeStr(name: String, indices: LongArray, value: String)
}

/**
 * 句柄化变量存取（T2-11 的 handle 层，T2-23 的装期绑定消费它）。
 *
 * ## 要解决什么
 *
 * 按名存取一次 = `upperOf(name)` + `privateVars[funcKey]` + `userVars[key]` +
 * `registry.def(name)`（后者内部又一次 `keyOf`）≈ 4～6 次哈希查找 + 一次大写记忆表探测。
 * 句柄把「名字 → 存储位置」的解析结果固化成一个小整数，之后每次访问只剩一次数组下标。
 *
 * ## 句柄是**存储私有**的
 *
 * 句柄由实现方分配，只有同一实例认得。所以：
 * - 不得跨实现方缓存（换 store 必须重新 `resolve`）；
 * - [NONE] 表示**此刻解析不出**（未定义 / 还没有帧），调用方必须回落按名路径 ——
 *   那条路径才会抛出与原生完全一致的 `未定义的变量 X` 文案。
 *
 * ## 私有变量与帧
 *
 * 私有 `#DIM` 变量的实例按**函数**隔离（`privateVars[funcKey]`），而解析用的是执行期的
 * 栈顶帧。所以句柄只在「当前执行函数 == 解析时的函数」时有效，缓存键必须是
 * `(函数, 变量名字符串池下标)` 而不是变量名。
 *
 * ## 生命周期：条目实例不得被替换
 *
 * 句柄指向**条目实例本身**，不是名字也不是下标。实现方必须保证「一经分配，同一条目
 * 不会被换成另一个实例」——重声明同名变量要么复用同一实例、要么显式失败；否则旧句柄
 * 会静默读到另一个变量（而且是永久性的，清缓存也救不回来）。`VariableStore` 走的是
 * 后者（`declareUserVar` 重名即抛「重复定义」、值重置走 `setDefault()` 原地清零），
 * 所以句柄在「池实例 + VM 实例」这一对的生命周期内恒有效。
 */
interface VariableHandles {
    /** 解析整型变量句柄；此刻解析不出返回 [NONE]（不抛错，报文由按名路径负责）。 */
    fun resolveInt(name: String): Int

    /** 解析字符串变量句柄；口径同 [resolveInt]。 */
    fun resolveStr(name: String): Int

    fun readIntH(handle: Int, indices: LongArray): Long

    fun readStrH(handle: Int, indices: LongArray): String

    fun plusIntH(handle: Int, indices: LongArray, delta: Long): Long

    /**
     * 该**名字**的解析结果是否可能依赖当前私有作用域（语料里某处声明过同名私有
     * `#DIM` 时为 true）。
     *
     * 调用方（`VarRef` / VM）用它决定缓存键要不要带 [VariableReader.scopeKey]：
     * 全局用户变量与系统变量的解析结果在任何作用域里都相同，带上作用域反而会在
     * 每次函数进出时白白作废缓存（2026-10-27 实测：作用域键在热路径上贡献 0.03% 的
     * 未命中，而按名字键的槽冲突贡献 66.5% —— 前者可以忽略，但不能倒过来变成主要成本）。
     *
     * ## 为什么判据是「名字」而不是「句柄指向的条目」
     *
     * 首版按 `handleScoped(handle)` 取判据（`UserVar.spec.isPrivate`），错在拿**解析
     * 结果**的属性去回答**名字**的问题：`FOO` 只在函数 A 里是私有的，那么 A 的
     * `VarRef` 解析到私有实例（true），A 之外解析到全局实例（false）—— 可「这个名字
     * 是否可以跨作用域复用缓存」对两处是同一个答案。按条目取值会让 A 之外的站点以
     * `SCOPE_ANY` 缓存全局句柄，一旦该实例在 A 内被求值（表达式树是否跨函数复用属于
     * 实现细节，`VarRef` 又是 `data class`），就会把全局实例当成 A 的私有实例读。
     *
     * 按名字取值还有一个结构上的好处：实现方与自己的解析缓存（`VariableStore.bindOf`）
     * 共用同一个谓词，两条路径不会分叉。
     *
     * 默认 true = 保守（调用方会多比较一次作用域，结果不会错）。
     */
    fun nameScoped(name: String): Boolean = true

    /**
     * 按句柄写整型变量。
     *
     * [name] 只用于写观察口（`VarWriteProbe.watches(name)` 是**大小写敏感**的名单判定），
     * 必须由调用方传**源拼写**，否则按名路径能命中的调试断点在按句柄路径上会静默失效。
     * 存储定位只认 [handle]。
     */
    fun writeIntH(handle: Int, name: String, indices: LongArray, value: Long)

    /** 按句柄写字符串变量；[name] 口径同 [writeIntH]。 */
    fun writeStrH(handle: Int, name: String, indices: LongArray, value: String)

    companion object {
        /** 「此刻解析不出」哨兵。**不是**合法的句柄下标。 */
        const val NONE: Int = 0
    }
}

/** 函数求值结果。 */
sealed class Value {
    data class IntV(val v: Long) : Value()
    data class StrV(val v: String) : Value()

    companion object {
        /**
         * 小整数**直映缓存**的下界（含）。
         *
         * PERF（2026-10-27 第四批）：`IntV` 是热路径上最大的单一分配类型 —— JFR 分配采样
         * 实测 **128.87 MiB / 32.3%**（分母 = 栈上含 `VirtualMachine.execute` 的样本），
         * 三个来源全是「把 `Long` 包成一个 `Value`」：`exprSentinelValue` 69.91（读调用方
         * 帧槽）、`Expr.toValue` 26.99（表达式字面量/求值结果）、`invokeUserMethod` 26.97
         * （方法返回值）。
         *
         * 而这些值里绝大多数是**小整数**（0/1 这类标志位、循环计数、下标）。`Value` 是
         * 不可变值类型（`data class`，`equals`/`hashCode` 按值），全代码库**没有任何**
         * 依赖 `Value` 身份的地方（无 `IdentityHashMap<Value,*>`、无 `===` 比较），
         * 因此共享实例是安全的 —— 与 JDK `Integer.valueOf` 的缓存同一个道理。
         *
         * 范围取 `-128..383`（512 格，常驻 ≈ 12 KB）：覆盖「负数下标哨兵 + 常见计数」，
         * 且 512 格的直映数组在 ART 上也只是一次数组读。
         */
        private const val INT_CACHE_MIN = -128L

        /** 小整数直映缓存的上界（含）。 */
        private const val INT_CACHE_MAX = 383L

        private val intCache: Array<IntV> = Array((INT_CACHE_MAX - INT_CACHE_MIN + 1).toInt()) {
            IntV(INT_CACHE_MIN + it)
        }

        /**
         * `IntV` 的**零分配**入口（小整数命中缓存时）。
         *
         * 语义与 `Value.IntV(v)` 逐字相同（同一个不可变值类型）；命中范围外照常新建。
         * 新代码在热路径上包整数请用这个，而不是构造器。
         */
        fun intV(v: Long): IntV =
            if (v >= INT_CACHE_MIN && v <= INT_CACHE_MAX) {
                intCache[(v - INT_CACHE_MIN).toInt()]
            } else {
                IntV(v)
            }
    }
}

/**
 * 求值语境：对应原版 ExpressionMediator 中表达式真正用到的两件事——
 * 变量存取（VariableReader）与函数调用。函数绑定在 M2 完成，
 * 绑定层负责名称 → 函数体解析，这里只按名字回调。
 */
interface EvalContext {
    val variables: VariableReader

    /**
     * 调用函数。args 与解析期 FuncCall.args 一一对应；
     * `null` 项对应原版参数列表中允许的空位（ReduceExpressionTerm 返回 null）。
     * TODO(verify): 原版对 null 参数的处理在具体 FunctionMethod 内部，M2 绑定层对齐。
     */
    fun callFunction(name: String, args: List<Value?>): Value

    /**
     * 引用参数调用入口：args 保持未求值形态交给内建（MATCH/GETVAR 等 evalRef 需要变量引用身份）。
     * 默认实现退化为值求值，非引用语义的实现方无需改动。
     */
    fun callFunctionRef(name: String, args: List<Expr?>): Value =
        callFunction(name, args.map { it?.toValue(this) })

    /** 系统行输出（仅 `-(-9223372036854775808)` 溢出警告用到）。spec: OperatorMethodManager.cs:561-564 */
    fun printSystemLine(message: String) {}
}
