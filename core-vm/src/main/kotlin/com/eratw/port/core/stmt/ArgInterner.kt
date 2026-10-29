package com.eratw.port.core.stmt

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.NullTerm
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.loader.StringPool
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 语句参数子树的**规范化去重**（装载期一次摊销）。
 *
 * ## 问题
 *
 * 全量语料实测（`PoolDecomp`，S3）：语句池的 `args`/条件/内嵌值子树是常驻里最大的
 * 单块（**306.5 MiB / 966 万对象**）。原因是解析器为每条语句新建参数结点：同一份
 * 结构（`SpSetArgument(VarRef(RESULT,[0]), IntLiteral(1))`、`VoidArgument()`、
 * `FORM` 串里的 `Text`/`Percent` …）在几十万语句里以百万计地重复出现。
 * 表达式池与语句树上的 `Expr` 已由 `com.eratw.port.core.compile.ExprCanonicalizer`
 * 折叠，**但 `Argument` 家族、容器列表、`FormPart`/转换结点与字符串值没有**。
 *
 * ## 做法（自根向下，三步）
 *
 * 1. **值结点重建**：`Expr` 家族、`FormPart`/`FormConversion`、`CaseExpression`、
 *    `MixedIntegerExprTerm` 都是不可变值对象，子结点可替换时重建一份新实例
 *    （`line`/`col` **原样保留**，保证 `.erbc` 再编码逐字节不变）。
 * 2. **可变列表就地改写**：参数树里的一次性 `ArrayList`（`terms`/`indices`/`parts`/
 *    `cases`/`subNames`…）按元素换成规范化实例——**列表实例本身不动**，所以持有它的
 *    父结点不必重建（这是本类能穿透到「唯一父结点下的重复子结点」的关键）。
 * 3. **结点身份合并**：每个结点算结构哈希（类 + 原子字段 + 子结点哈希），在规范表
 *    （开放寻址 `LongArray + Object[]`，无每结点 Key 分配）里查同一形态；命中即返回
 *    **首次实例**，未命中则把自己登记为代表。
 *
 * 语句级把 `args`（以及 `IfStmt.condition`/`CaseStmt.cases`/`ReturnStmt.values` 等
 * 自带表达式字段）换成规范化后的实例。**语句对象本身不换**：数据节以语句实例为键
 * （`sem/ExecContext.kt:96-100`），池下标也不变 —— 只有结点身份被合并。
 *
 * ## 语义边界
 *
 * - `Expr` 的 `line`/`col` **不参与结构比较**：运行期零读者（求值/sem/Vm 都不读，只有
 *   编译期报错定位与 `.erbc` 落盘用；`ExprCanonicalizer` 已在编译期把它们归一）。
 *   因此合并后结点取**代表实例**的 `line`/`col`；重建（重建走的是同一结点的子结点替换）
 *   则原样保留本结点的 `line`/`col`。落盘路径不受影响：erbcc 在**去重之前**写 `.erbc`，
 *   装载后不再回写；去重后再编码只可能在 `line`/`col` 列上与原件不同。
 * - **池根例外**（CMP-15）：表达式池根（`Program.internStatementArgs` / `LazyStatementRecords`
 *   的种子段）走**只登记、不替换** —— 池根实例与池内子树一个都不换，只让语句侧让位。
 *   不能把 `line`/`col` 塞进全局形状表来修这件事：语句参数在每条语句上带自己的行号，
 *   一旦它们参与比较，跨语句折叠（`PoolExprShareTest` 钉死的 M6-MEMCODE 行为）会整个失效；
 *   而真正需要保真的只有「池根能被 `program.expr(k)` 逐字段读回」这一处。
 *   种子段的「不替换」只是**一半**：语句侧去重会把池内代表写进语句的参数列表，之后
 *   再次走到这些结点时若就地改写列表字段，同样会改掉池内结点。因此 `Expr` 的列表字段
 *   （`VarRef.indices` / `FuncCall.args` / `FormStringTerm.parts`）一律走
 *   [canonicalizeElements] 的**写时复制**；只有语句自己的容器列表（`Argument` 家族等）
 *   才由 [internElements] 就地改写。缺了这条，`MmapLoaderTest` 的真语料差分会在
 *   `program.expr(k)` 的 `line`/`col` 上红（2026-10-24 合流后实测）。
 * - `Argument.isConst/constStr/constInt`、`SpSetArgument.addConst`、`CaseExpression`
 *   的可变字段参与结构比较（`Argument.kt:16-22`）：它们只在编译期/装载期写
 *   （`ArgBuilders.kt`、`ErbcReader.kt:993`、`ErbcStream.kt:1198`），运行期只读，
 *   所以换入等价实例不改变任何读出的值。
 * - 列表按元素比较（实现类不参与比较）；语句自己的容器 `ArrayList` 就地改写，不可变列表
 *   只下降不写回；`Expr` 列表字段写时复制（见「池根例外」，CMP-15）。
 * - 字符串按内容归一（[StringPool]，命中率/节省字节见 [Stats.strings]）——core 层没有
 *   字符串**身份**依赖（无 `===` 字符串比较、无 `IdentityHashMap<String,*>`）。
 *   覆盖：`Expr`/`FormPart` 的文本、语句自带文本字段、`Argument.constStr` 与
 *   `SpSetArrayArgument.constStrList`（都是可写槽）。**不覆盖** `Argument` 的 `val` 文本
 *   （写回要重建参数实例，91e4521 已证会破坏 `.erbc` 再编码恒等）。
 *   编译期残留（`FuncLabelStmt.paramDefaults`：`.erbc` 不落盘，只服务编译期补位）同样不接 ——
 *   串池只管装载后常驻的文本。
 * - 重建出的值结点用 [sameStructure] 自校验（漏抄字段会当场抛错）；这个检查只在
 *   重建发生时才付代价。
 *
 * ## 代价
 *
 * 一次全树遍历 + 每结点一次反射取字段。全量语料（794 万结点 / 171.7 万语句）实测
 * 见 `:core-vm:perfBaseline` §2d。
 *
 * **摊销口径要分清两档**（CMP-16）：整池档（对照装置/字节装载）是装载期一次摊销；
 * 生产用的**惰性语句池档不是** —— 装载期只付种子段（表达式池根入表），语句侧的按条
 * 规范化推迟到 `statementAt` 的**首次取用**（`LazyStatementRecords.statementAt`），
 * 每条付一次、之后读缓存，所以「运行期零成本」只在「该条已被物化过」之后成立。
 * `ProgramLoader.decodeMapped` 的 INTERN 分段 mark 因此只量种子段耗时。
 */
internal class ArgInterner(
    /**
     * 字符串归一流（[StringPool]）。默认自持一份；调用方可注入以便多段装载共用同一张表
     * （`Program` 的种子段 + 语句段、`ErbcStream` 的逐条物化）。
     */
    val strings: StringPool = StringPool(),
) {

    /** 一次装载的读数（写进 `perfBaseline` 报告）。 */
    class Stats(
        /** 遍历的语句数。 */
        val statements: Int,
        /** 被合并掉的结点实例数（命中规范表的次数，含字符串）。 */
        val merged: Int,
        /** 规范表里的形态数（去重后的结点数）。 */
        val forms: Int,
        /** 访问过的结点总数（含重复引用）。 */
        val nodes: Int,
        val ms: Long,
        /** 字符串归一流读数（命中率 / 估算节省字节）；跨段共享池时是**累计值**。 */
        val strings: StringPool.Snapshot = StringPool.Snapshot.EMPTY,
    )

    /** 一个类的字段分类：原子（值比较）与子结点（递归）。 */
    private class Shape(
        val atoms: Array<Field>,
        val kids: Array<Field>,
        /**
         * 与 [atoms] 一一对应的类型标记（见 `K_*`）：建 Shape 时定死，之后原子读取按标记走
         * `Field.getInt`/`getLong` 等**原始 getter**，不再 `Field.get` 装箱。
         *
         * 2026-10-23 装载提速：全量语料 794 万结点 × 每结点若干原子，原来每次 `Field.get` 对
         * 基本类型都要装箱（Boolean/Int/Char 各一次分配）⇒ 几千万次短命分配，是
         * `seedInterning`（真机 5.8 s）的主要分配来源。
         */
        val atomKinds: IntArray,
        /**
         * 子结点是否**保证已被写回规范实例**（该类在 `canonicalize` 里有 rebuild 分支）。
         *
         * true（`Expr`/`FormPart`/`FormConversion`/`CaseExpression`/`MixedIntegerExprTerm`/
         * 列表）⇒ 子结点「结构相等 ⇔ 同一实例」，哈希与比较都可以走引用短路径（O(1)/子结点）。
         * false（如各类 `Argument`：单值 `val` 字段写不回去，只做下降登记）⇒ 必须按真实结构递归。
         *
         * 2026-10-23：这个区分是浅哈希能成立的前提 —— 一刀切用引用会让 `ExpressionArgument`
         * 这类「term 未写回」的父结点永远合并不上（`StmtArgInterningTest` 当场三红）。
         */
        val shallowKids: Boolean,
    )

    private val shapes = HashMap<Class<*>, Shape>()

    // ---- 规范表（开放寻址）：结构哈希 → 代表实例 ----
    private var hashes = LongArray(INITIAL_CAPACITY)
    private var reps = arrayOfNulls<Any?>(INITIAL_CAPACITY)
    private var mask = INITIAL_CAPACITY - 1
    private var size = 0

    private var merged = 0
    private var nodes = 0
    private var statements = 0

    /**
     * 种子段标记（见 [internStatement]）：表达式池根**只登记、不替换**。
     *
     * 这条模式存在的唯一理由：`Expr` 的 `line`/`col` 不进形状表（跨语句折叠必须忽略它们），
     * 而表达式池根的实例身份与逐字段值必须保真（`program.expr(k)` 的 data class 相等 +
     * `.erbc` 再编码逐字节）。两者只能靠「对谁生效」分开 —— 见类 KDoc 的「池根例外」。
     */
    private var seedMode = false

    /**
     * 规范化整池语句（幂等：第二次调用不会再合并任何结点）。
     *
     * 只许在「编译/落盘结束、运行期开始」的窗口调用；见 `Program.internStatementArgs`。
     */
    fun run(all: Iterable<Statement>): Stats {
        val startedAt = System.nanoTime()
        for (s in all) {
            statements++
            internStatement(s)
        }
        return Stats(statements, merged, size, nodes, (System.nanoTime() - startedAt) / 1_000_000, strings.snapshot())
    }

    // ------------------------------------------------------------------
    // 语句层
    // ------------------------------------------------------------------

    private fun internStatement(s: Statement) {
        // 表达式池种子行：池外临时语句，整段表达式池当它的 args。
        val seed = isPoolSeed(s)
        val prev = seedMode
        seedMode = seed
        try {
            internStatementFields(s)
        } finally {
            seedMode = prev
        }
    }

    /**
     * 种子行判定：`FunctionCode.__NULL__` 的 instruction 行、`line == -1`、参数是表达式数组。
     *
     * 全仓只有两处构造这种语句 —— `Program.expressionPoolSeed` 与
     * `LazyStatementRecords.seedInterning`；`FunctionCode.__NULL__` 的其余用法是
     * 标签/预处理语句（[FuncLabelStmt]/[GotoLabelStmt]/[SharpStmt]，都不是 [InstructionStmt]），
     * 不会误判。判不出种子行的代价是「池根被就地顶掉」（CMP-15），所以这条判定与两处
     * 构造是**同一个契约**：改任一处都要同步。
     */
    private fun isPoolSeed(s: Statement): Boolean =
        s is InstructionStmt && s.code == FunctionCode.__NULL__ && s.line < 0 &&
            s.args is ExpressionArrayArgument

    private fun internStatementFields(s: Statement) {
        s.replaceArgs(internNode(s.args) as Argument)
        when (s) {
            is IfStmt -> s.condition = internNode(s.condition) as Expr
            is ElseIfStmt -> s.condition = internNode(s.condition) as Expr
            is WhileStmt -> s.condition = internNode(s.condition) as Expr
            is LoopStmt -> s.condition = s.condition?.let { internNode(it) as Expr }
            is SelectCaseStmt -> s.target = internNode(s.target) as Expr
            is CaseStmt -> s.cases = internNode(s.cases) as List<CaseExpression>
            is ReturnStmt -> s.values = internNode(s.values) as List<Expr?>
            is ReturnFStmt -> s.value = s.value?.let { internNode(it) as Expr }
            is AssignStmt -> s.dest = internNode(s.dest) as VarRef
            is PrintStmt -> s.print = internNode(s.print) as PrintSpec
            // 语句自带文本字段（数据行/被调函数键/方法名/标签/预处理名）：
            // 运行期全部按内容消费，按内容归一后同形文本共享同一实例。
            is DataStmt -> s.text = internString(s.text)
            is JumpStmt -> s.label = s.label?.let { internString(it) }
            is CallStmt -> s.label = s.label?.let { internString(it) }
            is CallFStmt -> s.functionName = s.functionName?.let { internString(it) }
            is MethodStmt -> s.methodName = internString(s.methodName)
            is FuncLabelStmt -> {
                s.name = internString(s.name)
                s.paramNames = internNode(s.paramNames) as List<String>
                s.paramRefs = internNode(s.paramRefs) as List<String>
            }

            is GotoLabelStmt -> s.name = internString(s.name)
            is SharpStmt -> {
                s.keyword = internString(s.keyword)
                s.name = s.name?.let { internString(it) }
            }

            else -> {}
        }
    }

    // ------------------------------------------------------------------
    // 结点层
    // ------------------------------------------------------------------

    /**
     * 返回 [o] 的规范实例：结构相等的首次实例（可能就是 [o] 自己）。
     *
     * 顺序是**后序**：先规范化子结点（值结点重建 + 列表写时复制），再按结构哈希查表。
     */
    private fun internNode(o: Any): Any {
        when (o) {
            is String -> return internString(o)
            is List<*> -> {
                internElements(o)
                return o
            }
            // 自规范值：枚举是单例（引用即身份）、装箱值是值对象（equals 语义）——
            // 去重无收益，且它们是 java.base 的类，进 canonicalize 会让 shapeOf 撞上
            // JDK 内部字段（Enum.name / Integer.value），JDK17 模块系统下 setAccessible
            // 直接抛 InaccessibleObjectException（2026-10-22 闸红实证）。旧代码
            // trySetAccessible 对这些字段静默返回 false ⇒ Shape 空 ⇒ **同一枚举类/装箱类
            // 的不同值互相折叠** —— 真机上 VAR 相关折叠链的死循环根因之一。
            is Enum<*>, is Number, is Boolean, is Char -> return o
        }
        nodes++
        val node = canonicalize(o)
        val rep = register(node)
        // 种子段返回入参本身：池根绝不换成同形态的更早代表（[seedMode]）。
        // 列表就地改写那条路已由 [internElements] 关掉，这里兜住「单值字段/根结点」两条路。
        return if (seedMode) o else rep
    }

    private fun internString(s: String): String {
        nodes++
        val rep = strings.intern(s)
        if (rep !== s) {
            merged++
            // 字符串按内容归一（无「关键摘要可错」的维度），审计只计数不留痕。
            if (FoldAuditState.enabled) FoldAuditState.onStringFold()
        }
        return rep
    }

    /**
     * 列表元素就地规范化（**只给语句自己的容器列表**用）。
     *
     * 只对**确实可写**的实现（`ArrayList`/`LinkedList`）就地改写：Kotlin 的
     * `List`/`MutableList` 在 JVM 上都映射到 `java.util.List`，`is MutableList` 对
     * `SingletonList`/`Arrays$ArrayList`/`unmodifiableList` 同样为 true，靠它判可写会
     * 在 `set` 上抛 `UnsupportedOperationException`。其余实现只下降登记。
     *
     * `Expr` 自己的列表字段（`VarRef.indices` / `FuncCall.args` / `FormStringTerm.parts`）
     * **不走这里**，走 [canonicalizeElements] 的写时复制 —— 那些列表可能属于表达式池结点。
     */
    private fun internElements(list: List<*>) {
        nodes++
        if (list is java.util.ArrayList<*> || list is java.util.LinkedList<*>) {
            @Suppress("UNCHECKED_CAST")
            val m = list as MutableList<Any?>
            for (i in list.indices) {
                val e = m[i] ?: continue
                val c = internNode(e)
                // 种子段不写回：池根列表就是表达式池本体，写回等于就地顶掉池根（CMP-15）。
                if (!seedMode && c !== e) m[i] = c
            }
        } else {
            for (e in list) if (e != null) internNode(e)
        }
    }

    /**
     * 列表元素**写时复制**规范化（只用于 `Expr` 自己的列表字段）。
     *
     * 与 [internElements] 的唯一区别：**绝不改写入参列表**。原因不是洁癖 —— 语句侧去重时
     * `internNode` 会把表达式池里的代表实例写进语句的参数列表，之后同一条（或共享同一
     * 列表实例的）语句再次走到这些池内结点时，`rebuildExpr` 会将它们当作普通结点继续
     * 规范化。若在这里原地改写，`VarRef.indices` / `FuncCall.args` / `FormStringTerm.parts`
     * 就会**直接改掉表达式池的结点**：`program.expr(k)` 的 `line`/`col` 与结构随之漂移，
     * `.erbc` 再编码少节点记录（CMP-15 真回归，`MmapLoaderTest` 真语料用例钉住）。
     * 所以子结点有变化时建一份新 `ArrayList` 交给父结点重建（父结点重建后才是新实例，
     * 池内结点保持只读）；无变化时返回原列表，零分配。
     */
    private fun canonicalizeElements(list: List<*>): List<*> {
        var copy: MutableList<Any?>? = null
        for (i in list.indices) {
            val e = list[i] ?: continue
            val c = internNode(e)
            if (c !== e) {
                if (copy == null) {
                    copy = ArrayList<Any?>(list.size)
                    copy.addAll(list)
                }
                copy[i] = c
            }
        }
        return copy ?: list
    }

    /**
     * 规范化一个非列表、非字符串结点：值结点按类型重建（子结点换成规范实例），
     * 其余类型只把**列表字段**就地改写（`val` 单值字段无法写回，其子结点仅下降登记）。
     *
     * 重建出的是新实例时做一次 [sameStructure] 自校验：漏抄一个字段会当场抛错，
     * 而不是把错值带到运行期。
     */
    private fun canonicalize(o: Any): Any {
        // 种子段：只下降登记，不重建、不写回 —— 池根及其子树的 line/col 必须原样保留，
        // 否则「同形异行列号」的子树会被换成代表实例，段 2 再编码时少一条节点记录（CMP-15）。
        if (seedMode) {
            canonicalizeListFields(o)
            return o
        }
        val rebuilt: Any = when (o) {
            is Expr -> rebuildExpr(o)
            is FormPart -> rebuildPart(o)
            is FormConversion -> rebuildConversion(o)
            is CaseExpression -> rebuildCase(o)
            is MixedIntegerExprTerm -> {
                val n = o.num?.let { internNode(it) as Expr }
                if (n === o.num) o else MixedIntegerExprTerm(n, o.isPx)
            }

            else -> {
                // 参数结点**不重建**（91e4521 的教训：重建 Argument 实例会让 `.erbc`
                // 再编码不再逐字节相同）。这里只写回基类/数组里的**可变文本槽**，
                // 内容一字不改 ⇒ 结构哈希与落盘字节都不动。
                if (o is Argument) internArgumentTexts(o)
                canonicalizeListFields(o)
                o
            }
        }
        // 重建自校验（漏抄字段当场抛错）**只在审计模式跑**（2026-10-23 装载提速）：
        // 它是对「重建逐字段等价」的开发期断言，不是语义要求；而重建在子结点被规范化后极其
        // 常见，一次深比对要递归整棵子树 + 每结点反射取字段 ⇒ 全量语料上真机 ~7.3 s 的
        // `seedInterning` 里，这一趟是主要开销之一。审计模式沿用既有的 `fold-audit.on`
        // 一次性开关（[FoldAuditState]），跑一次对账后自动失效。
        if (rebuilt !== o && FoldAuditState.enabled && !sameStructureDeep(o, rebuilt)) {
            throw IllegalStateException(
                "参数结点重建丢字段（${o.javaClass.simpleName}）：规范化的前提是逐字段等价",
            )
        }
        return rebuilt
    }

    private fun canonicalizeListFields(o: Any) {
        for (f in shapeOf(o.javaClass).kids) {
            val v = f.get(o) ?: continue
            if (v is List<*>) internElements(v) else internNode(v)
        }
    }

    /**
     * `Argument` 家族里**可写**的文本槽就地归一（`constStr` 是基类的 `var`，
     * `SpSetArrayArgument.constStrList` 是可变数组）：只换实例、不换内容、不换参数对象。
     *
     * 空串跳过：`constStr` 的默认值就是空串（`Argument.kt:19`），不跳过会把几十万次
     * 「空串命中空串」刷进命中率读数。代价是装载路径上 `StrictUtf8` 现解出的空串实例
     * （`StrictUtf8.kt:49`，值同实例不同）漏归一个 —— 每条约 40 B，相对 38.9 MiB 可忽略。
     * 其余单值 `val` 文本（`IntAsignArgument.name` / `RefArgument.refName` /
     * `SpSplitArgument.destVarName` …）写回必须重建实例 —— 本轮不做，见类 KDoc。
     */
    private fun internArgumentTexts(a: Argument) {
        if (a.constStr.isNotEmpty()) a.constStr = internString(a.constStr)
        if (a is SpSetArrayArgument) {
            val list = a.constStrList ?: return
            for (i in list.indices) list[i] = internString(list[i])
        }
    }

    // ------------------------------------------------------------------
    // 值结点重建（line/col 原样保留）
    // ------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun rebuildExpr(e: Expr): Expr = when (e) {
        is IntLiteral, is NullTerm -> e
        is StrLiteral -> {
            val v = internString(e.value)
            if (v === e.value) e else StrLiteral(v, e.line, e.col)
        }

        is VarRef -> {
            // 写时复制：[indices] 可能属于表达式池结点，绝不原地改写（见 [canonicalizeElements]）。
            val indices = canonicalizeElements(e.indices) as List<Expr>
            val name = internString(e.name)
            if (indices === e.indices && name === e.name) e
            else VarRef(name, indices, e.isNoArg, e.operandType, e.isConst, e.subKey, e.line, e.col)
        }

        is KeywordIndexArg -> {
            // varName 是 `Expr` 的文本字段：类 KDoc「覆盖：`Expr`/`FormPart` 的文本」要求一并
            // 归一（旧实现漏了它，写回后段落 `interned` 的同一关键词下标仍各持一份 String）。
            val inner = internNode(e.expr) as Expr
            val varName = internString(e.varName)
            if (inner === e.expr && varName === e.varName) e
            else KeywordIndexArg(inner, varName, e.dim, e.line, e.col)
        }

        is FuncCall -> {
            val args = canonicalizeElements(e.args) as List<Expr?>
            val name = internString(e.name)
            if (args === e.args && name === e.name) e
            else FuncCall(name, args, e.operandType, e.line, e.col)
        }

        is Unary -> {
            val x = internNode(e.operand) as Expr
            if (x === e.operand) e else Unary(e.op, x, e.line, e.col)
        }

        is UnaryAfter -> {
            val x = internNode(e.operand) as Expr
            if (x === e.operand) e else UnaryAfter(e.op, x, e.line, e.col)
        }

        is Binary -> {
            val l = internNode(e.left) as Expr
            val r = internNode(e.right) as Expr
            if (l === e.left && r === e.right) e else Binary(e.op, l, r, e.operandType, e.line, e.col)
        }

        is Ternary -> {
            val c = internNode(e.cond) as Expr
            val t = internNode(e.ifTrue) as Expr
            val f = internNode(e.ifFalse) as Expr
            if (c === e.cond && t === e.ifTrue && f === e.ifFalse) e
            else Ternary(c, t, f, e.operandType, e.line, e.col)
        }

        is FormStringTerm -> {
            // 同上：[parts] 可能属于表达式池结点，写时复制。
            val parts = canonicalizeElements(e.parts) as List<FormPart>
            if (parts === e.parts) e else FormStringTerm(parts, e.line, e.col)
        }
    }

    private fun rebuildPart(p: FormPart): FormPart = when (p) {
        is FormPart.Text -> {
            val s = internString(p.str)
            if (s === p.str) p else FormPart.Text(s)
        }

        is FormPart.Embedded -> {
            val ast = internNode(p.ast) as Expr
            val conv = internNode(p.conversion) as FormConversion
            if (ast === p.ast && conv === p.conversion) p else FormPart.Embedded(ast, conv)
        }
    }

    private fun rebuildConversion(c: FormConversion): FormConversion = when (c) {
        is FormConversion.Curly -> {
            val w = c.width?.let { internNode(it) as Expr }
            if (w === c.width) c else FormConversion.Curly(w, c.alignLeft)
        }

        is FormConversion.Percent -> {
            val w = c.width?.let { internNode(it) as Expr }
            if (w === c.width) c else FormConversion.Percent(w, c.alignLeft)
        }

        is FormConversion.YenAt -> {
            val t = internNode(c.ifTrue) as Expr
            val f = internNode(c.ifFalse) as Expr
            if (t === c.ifTrue && f === c.ifFalse) c else FormConversion.YenAt(t, f)
        }
    }

    private fun rebuildCase(c: CaseExpression): CaseExpression {
        val l = c.leftTerm?.let { internNode(it) as Expr }
        val r = c.rightTerm?.let { internNode(it) as Expr }
        if (l === c.leftTerm && r === c.rightTerm) return c
        return CaseExpression(c.caseType, l, r, c.operator)
    }

    // ------------------------------------------------------------------
    // 结构哈希 / 结构相等
    // ------------------------------------------------------------------

    /** 登记 [o] 并返回同形态代表：命中即合并（[merged]++），未命中把 [o] 当代表。 */
    private fun register(o: Any): Any {
        val h = hashOf(o)
        if (size * 2 >= reps.size) grow()
        var i = slotOf(h)
        while (true) {
            val rep = reps[i]
            if (rep == null) break
            if (hashes[i] == h && sameStructure(o, rep)) {
                merged++
                if (FoldAuditState.enabled) FoldAuditState.fold(o, rep)
                return rep
            }
            i = (i + 1) and mask
        }
        reps[i] = o
        hashes[i] = h
        size++
        return o
    }

    /**
     * 结构哈希（**浅**，2026-10-23 装载提速）。
     *
     * 原实现对每个结点递归整棵子树（`hashOf(child)`），而 [internNode] 又对**每个**结点各调
     * 一次 ⇒ 总代价 O(结点数 × 平均深度)。真机装载 `load.program` 尾段 ≈10.1 s 里绝大部
     * 分就是这一趟（`internStatementArgs` 的 `seedInterning`）。
     *
     * 但 interning 是**后序**的：进入 [register] 时所有子结点已经是规范实例，因此「子结点
     * 结构相等」在规范表里等价于「引用相同」——子结点贡献用 `identityHashCode` 即可，
     * O(1)/结点。列表按「元素身份」参与哈希（列表实例本身随父结点新建，不能拿它自己的
     * identity 做键，否则同一结构的兄弟父结点永远撞不到一起）。
     *
     * 正确性：结构相等 ⇒ 子结点已是同一规范实例 ⇒ 哈希相同（**不会漏合并**结构相等的结点）；
     * 反向不成立（identity 有碰撞可能）⇒ [register] 的结构比较照旧兜底。
     */
    private fun hashOf(o: Any): Long {
        if (o is String) return mix(o.hashCode().toLong())
        if (o is List<*>) {
            var h = LIST_SEED
            for (e in o) h = h * 31 + if (e == null) 0L else hashOf(e)
            return mix(h * 31 + o.size)
        }
        // 自规范值（同 internNode 的短路）：枚举按「类名+ordinal」，装箱值按值哈希。
        // 不短路会让 shapeOf 撞上 java.base 内部字段（Enum.name 等），JDK17 模块系统下
        // setAccessible 直接抛 InaccessibleObjectException（2026-10-22 闸红实证）。
        if (o is Enum<*>) return mix(o.javaClass.name.hashCode().toLong() * 31 + o.ordinal)
        if (o is Number || o is Boolean || o is Char) return mix(o.hashCode().toLong())
        var h = o.javaClass.name.hashCode().toLong()
        val shape = shapeOf(o.javaClass)
        // 原子按类型走原始 getter（零装箱，见 [atomHashTyped]）
        val atoms = shape.atoms
        val kinds = shape.atomKinds
        for (i in atoms.indices) h = h * 31 + atomHashTyped(atoms[i], kinds[i], o)
        for (f in shape.kids) {
            val v = f.get(o)
            // 浅安全类型：子结点已是规范实例 ⇒ 引用身份即结构身份（O(1)）；
            // 其余（Argument 等未写回单值 kid 的类型）：按真实结构递归（只在该层付一次）。
            if (v != null) h = h * 31 + if (shape.shallowKids) shallowHash(v) else hashOf(v)
        }
        return mix(h)
    }

    /** 浅安全类型的子结点贡献：字符串按内容、列表按内容（元素已规范化）、其余按引用身份。 */
    private fun shallowHash(v: Any): Long = when {
        v is String -> mix(v.hashCode().toLong())
        v is List<*> -> hashOf(v)
        else -> mix(System.identityHashCode(v).toLong())
    }

    /**
     * 表查找用的结构相等（**混合**，2026-10-23）。
     *
     * 子结点先比**引用**：规范后「结构相等 ⇔ 同一实例」是常态，这一步把绝大多数比较降到
     * O(1)/子结点；引用不同时才下降递归 —— 保住「绝不漏判」的原语义（未规范化的漏网结点
     * 仍能被认出来）。[sameStructureDeep] 是给 [canonicalize] 自校验用的纯深比较版本。
     */
    private fun sameStructure(a: Any, b: Any): Boolean {
        if (a === b) return true
        if (a is String) return b is String && a == b
        // 自规范值：枚举单例比引用，装箱值按 equals（跨类型如 Integer(1) vs Long(1) 不等）。
        if (a is Enum<*> || b is Enum<*>) return a === b
        if (a is Number || a is Boolean || a is Char) return a == b
        if (a is List<*> && b is List<*>) {
            if (a.size != b.size) return false
            for (i in a.indices) {
                val x = a[i]
                val y = b[i]
                if (x == null) {
                    if (y != null) return false
                } else if (y == null || !sameStructure(x, y)) {
                    return false
                }
            }
            return true
        }
        if (a.javaClass != b.javaClass) return false
        val shape = shapeOf(a.javaClass)
        val atoms = shape.atoms
        val kinds = shape.atomKinds
        for (i in atoms.indices) {
            if (!atomSameTyped(atoms[i], kinds[i], a, b)) return false
        }
        for (f in shape.kids) {
            val x = f.get(a)
            val y = f.get(b)
            if (x == null) {
                if (y != null) return false
            } else if (y == null) {
                return false
            } else if (x !== y && !sameStructure(x, y)) {
                return false
            }
        }
        return true
    }

    /**
     * 深结构相等（自校验专用）：[canonicalize] 比较的是「重建前的旧结点」与「重建后的新
     * 结点」，两边子结点不保证是同一实例，所以这里逐层下降、不能走引用短路。
     */
    private fun sameStructureDeep(a: Any, b: Any): Boolean {
        if (a === b) return true
        if (a is String) return b is String && a == b
        if (a is Enum<*> || b is Enum<*>) return a === b
        if (a is Number || a is Boolean || a is Char) return a == b
        if (a is List<*> && b is List<*>) {
            if (a.size != b.size) return false
            for (i in a.indices) {
                val x = a[i]
                val y = b[i]
                if (x == null) {
                    if (y != null) return false
                } else if (y == null || !sameStructureDeep(x, y)) {
                    return false
                }
            }
            return true
        }
        if (a.javaClass != b.javaClass) return false
        val shape = shapeOf(a.javaClass)
        val atoms = shape.atoms
        val kinds = shape.atomKinds
        for (i in atoms.indices) {
            if (!atomSameTyped(atoms[i], kinds[i], a, b)) return false
        }
        for (f in shape.kids) {
            val x = f.get(a)
            val y = f.get(b)
            if (x == null) {
                if (y != null) return false
            } else if (y == null || !sameStructureDeep(x, y)) {
                return false
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // 字段分类
    // ------------------------------------------------------------------

    /**
     * 反射形状表（每类一次）。
     *
     * - 跳过静态字段与 `Statement.rawSlot`（原文槽，与参数树无关）；
     * - `Expr` 的 `line`/`col` 跳过（运行期零读者，见类 KDoc）；
     * - 基本类型 / 装箱 / String / 枚举 / **全部数组** 是原子（[isAtomFieldType]），
     *   其余对象字段是子结点。
     *
     * **建表即自证**（CMP-14）：建完一份 Shape 立刻跑 [validateShape]，违规即抛 ——
     * 数组字段一旦落进 kids 就会得到空 Shape（数组类没有可反射字段）⇒ 该类持有者全判同构，
     * 正是 2026-10-22 真机死循环的同型病（`probeShapes` 是同一套判据的聚合版）。
     */
    private fun shapeOf(c: Class<*>): Shape = shapes.getOrPut(c) {
        // 列表由 [internElements]/[hashOf] 的 List 分支单独处理；这里给的空形状只是兜底。
        if (List::class.java.isAssignableFrom(c)) {
            return@getOrPut Shape(EMPTY_FIELDS, EMPTY_FIELDS, EMPTY_KINDS, true)
        }
        val shape = buildShape(c)
        val violations = validateShape(c, shape)
        if (violations.isNotEmpty()) {
            throw IllegalStateException(
                "形状表自检失败（${violations.size} 条，类 ${c.name}）：\n" +
                    violations.joinToString("\n"),
            )
        }
        shape
    }

    private fun buildShape(c: Class<*>): Shape {
        if (List::class.java.isAssignableFrom(c)) {
            return Shape(EMPTY_FIELDS, EMPTY_FIELDS, EMPTY_KINDS, true)
        }
        val atoms = ArrayList<Field>()
        val kinds = ArrayList<Int>()
        val kids = ArrayList<Field>()
        val isExpr = Expr::class.java.isAssignableFrom(c)
        var cl: Class<*>? = c
        while (cl != null && cl != Any::class.java) {
            for (f in cl.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.name == "rawSlot") continue
                // 派生字段：`VarRef.indexArray` 是 `indices` 的数组视图（随 indices 一起规范化），
                // 不参与结构 —— 否则浅哈希会读到未被写回的旧副本而漏合并（2026-10-23 实测红）。
                if (f.name == "indexArray") continue
                // 派生字段：`KeywordIndexArg` 的关键字→下标记忆（`memoKeyword`/`memoValue`/
                // `memoReader`）是求值期缓存，与结点结构无关。落进 atoms 会让「同一结点在不同
                // 求值阶段」被判成不同形态 ⇒ 去重漏合并、`canonicalize` 自校验红（同 indexArray）。
                if (f.name == "memoKeyword" || f.name == "memoValue" || f.name == "memoReader") continue
                // 通例（同上一族，只是改用显式标记）：`@Transient` 标的是**求值期缓存**，
                // 与结点结构无关 —— 目前是 `VarRef` 的访问点绑定缓存
                // （`bindReader`/`bindScope`/`bindScopeSensitive`/`bindInt`/`bindStr`）。
                // 不跳过会有两个后果：① 同一结点在不同求值阶段被判成不同形态 ⇒ 漏合并；
                // ② `bindReader` 拿着整个 `VariableStore`，会被当成结点子图继续下降
                //    （2026-10-26 实测 e2e 全量执行后反射进 `java.util.HashSet` 内部字段抛
                //    `InaccessibleObjectException`）。新加运期缓存请一律挂 `@Transient`。
                if (Modifier.isTransient(f.modifiers)) continue
                if (isExpr && (f.name == "line" || f.name == "col")) continue
                // isAccessible 是 Java 1.1 API（桌面/ART 全版本可用）。原实现 trySetAccessible()
                // （Java 9+）在 Android 反射面缺失：core-vm 以 JVM 模块编译出的调用在真机上抛
                // NoSuchMethodError，被 runCatching 吞成 false ⇒ **所有字段被静默 continue 跳过**
                // ⇒ Shape 为空 ⇒ 同类节点全部判「同构」⇒ 跨形态误折叠（VarRef(I)↔VarRef(_i)），
                // 真机 FOR 计数器写入丢失、死循环（2026-10-22 真机 pool-audit 实证）。
                // 这里改为失败即抛：判等缺一个字段比装载失败危险得多，绝不静默。
                f.isAccessible = true
                if (isAtomFieldType(f.type)) {
                    atoms.add(f)
                    kinds.add(kindOf(f.type))
                } else {
                    kids.add(f)
                }
            }
            cl = cl.superclass
        }
        // 子结点「保证已写回」的类型：canonicalize 的 rebuild 分支覆盖到它们（sealed 层次穷尽）。
        val shallow = isExpr ||
            FormPart::class.java.isAssignableFrom(c) ||
            FormConversion::class.java.isAssignableFrom(c) ||
            CaseExpression::class.java.isAssignableFrom(c) ||
            MixedIntegerExprTerm::class.java.isAssignableFrom(c)
        return Shape(atoms.toTypedArray(), kids.toTypedArray(), kinds.toIntArray(), shallow)
    }

    /**
     * 形状表自检判据（建表期抛错用，也是 `probeShapes` 的聚合判据）。
     *
     * 三条：
     * 1. 原子表里每一项都必须过 [isAtomFieldType]，且类型标记与字段类型同源；
     * 2. 子结点表里**不许出现数组字段** —— 数组类没有可反射字段，落进 kids 会得到空 Shape，
     *    该类的所有实例互相判「同构」（CMP-14 的塌陷机理）；
     * 3. 声明了实例字段的类不许得到空 Shape（反射可访问性整体失效的 2026-10-21 事故形态）。
     */
    private fun validateShape(c: Class<*>, shape: Shape): List<String> {
        if (shape.atoms.isEmpty() && shape.kids.isEmpty() && hasInstanceFields(c)) {
            return listOf("${c.name}: 声明了实例字段但一个都没进形状表（反射可访问性失效 ⇒ 同类全判同构）")
        }
        val out = ArrayList<String>(0)
        for (i in shape.atoms.indices) {
            val f = shape.atoms[i]
            if (!isAtomFieldType(f.type)) {
                out += "${c.name}.${f.name}: ${f.type.name} 不是原子类型却进了原子表"
            }
            if (shape.atomKinds[i] != kindOf(f.type)) {
                out += "${c.name}.${f.name}: 原子标记 ${shape.atomKinds[i]} 与字段类型 ${f.type.name} 不同源"
            }
        }
        for (f in shape.kids) {
            if (f.type.isArray) {
                out += "${c.name}.${f.name}: 数组字段 ${f.type.name} 被当成子结点" +
                    "（数组类无可反射字段 ⇒ 空 Shape ⇒ 同类结点互相塌陷）"
            } else if (isAtomFieldType(f.type)) {
                out += "${c.name}.${f.name}: ${f.type.name} 是原子类型却进了子结点表"
            }
        }
        return out
    }

    /** [validateShape] 的第 3 条判据用：该类（含父类）是否声明过任何实例字段。 */
    private fun hasInstanceFields(c: Class<*>): Boolean {
        var cl: Class<*>? = c
        while (cl != null && cl != Any::class.java) {
            for (f in cl.declaredFields) if (!Modifier.isStatic(f.modifiers)) return true
            cl = cl.superclass
        }
        return false
    }

    /** 原子字段的类型标记（[Shape.atomKinds]）。 */
    private fun kindOf(t: Class<*>): Int = when (t) {
        java.lang.Boolean.TYPE -> K_BOOL
        java.lang.Byte.TYPE -> K_BYTE
        java.lang.Short.TYPE -> K_SHORT
        java.lang.Integer.TYPE -> K_INT
        java.lang.Long.TYPE -> K_LONG
        java.lang.Character.TYPE -> K_CHAR
        java.lang.Float.TYPE -> K_FLOAT
        java.lang.Double.TYPE -> K_DOUBLE
        else -> if (t.isEnum) K_ENUM else K_OBJ
    }

    /**
     * 原子字段的哈希：按 [Shape.atomKinds] 走原始 getter 直接算（零装箱）。
     *
     * 口径与旧 `atomHash(f.get(o))` 逐字一致：Boolean 用 1231/1237（`Boolean.hashCode`）、
     * 整数用值、Long 用 `v xor (v ushr 32)`（`Long.hashCode`）、Char 用码点、Float/Double 用
     * 位模式、枚举用 `name.hashCode`、其余（String / 装箱数 / 数组）回落 [atomHash]。
     */
    private fun atomHashTyped(f: Field, kind: Int, o: Any): Long = when (kind) {
        K_BOOL -> if (f.getBoolean(o)) 1231L else 1237L
        K_BYTE -> f.getByte(o).toLong()
        K_SHORT -> f.getShort(o).toLong()
        K_INT -> f.getInt(o).toLong()
        K_LONG -> f.getLong(o).let { v -> v xor (v ushr 32) }
        K_CHAR -> f.getChar(o).code.toLong()
        K_FLOAT -> java.lang.Float.floatToIntBits(f.getFloat(o)).toLong()
        K_DOUBLE -> java.lang.Double.doubleToLongBits(f.getDouble(o))
        K_ENUM -> (f.get(o) as? Enum<*>)?.name?.hashCode()?.toLong() ?: 0L
        else -> atomHash(f.get(o))
    }

    /** 原子字段的判等：与 [atomHashTyped] 同一套类型口径。 */
    private fun atomSameTyped(f: Field, kind: Int, x: Any, y: Any): Boolean = when (kind) {
        K_BOOL -> f.getBoolean(x) == f.getBoolean(y)
        K_BYTE -> f.getByte(x) == f.getByte(y)
        K_SHORT -> f.getShort(x) == f.getShort(y)
        K_INT -> f.getInt(x) == f.getInt(y)
        K_LONG -> f.getLong(x) == f.getLong(y)
        K_CHAR -> f.getChar(x) == f.getChar(y)
        K_FLOAT -> java.lang.Float.floatToIntBits(f.getFloat(x)) == java.lang.Float.floatToIntBits(f.getFloat(y))
        K_DOUBLE -> java.lang.Double.doubleToLongBits(f.getDouble(x)) == java.lang.Double.doubleToLongBits(f.getDouble(y))
        K_ENUM -> f.get(x) === f.get(y)
        else -> atomEquals(f.get(x), f.get(y))
    }

    // 字段类型分类的唯一判据在 companion 的 `isAtomFieldType`（建表与自检共用）。

    /**
     * 原子值的哈希（K_OBJ 支与数组字段的唯一实现）。
     *
     * `CharArray`/`ByteArray`/`ShortArray`/`FloatArray` 与 `Array<*>` 必须按**内容**参与
     *（CMP-14）：这些数组类型在 2026-10-23 之前根本没进 [isAtomFieldType]，作为子结点会拿到
     * 空 Shape —— 同类数组全判同构。这里与 [atomEquals] 逐类型对齐，避免「哈希按内容、判等按身份」
     * 或反向的不一致。
     */
    private fun atomHash(v: Any?): Long = when (v) {
        null -> 0L
        is IntArray -> mix(v.contentHashCode().toLong())
        is LongArray -> mix(v.contentHashCode().toLong())
        is DoubleArray -> mix(v.contentHashCode().toLong())
        is BooleanArray -> mix(v.contentHashCode().toLong())
        is CharArray -> mix(v.contentHashCode().toLong())
        is ByteArray -> mix(v.contentHashCode().toLong())
        is ShortArray -> mix(v.contentHashCode().toLong())
        is FloatArray -> mix(v.contentHashCode().toLong())
        is Array<*> -> {
            var h = 7L
            for (e in v) h = h * 31 + atomHash(e)
            mix(h)
        }

        is Enum<*> -> v.name.hashCode().toLong()
        else -> v.hashCode().toLong()
    }

    private fun atomEquals(a: Any?, b: Any?): Boolean = when {
        a === b -> true
        a == null || b == null -> false
        a is IntArray -> b is IntArray && a.contentEquals(b)
        a is LongArray -> b is LongArray && a.contentEquals(b)
        a is DoubleArray -> b is DoubleArray && a.contentEquals(b)
        a is BooleanArray -> b is BooleanArray && a.contentEquals(b)
        a is CharArray -> b is CharArray && a.contentEquals(b)
        a is ByteArray -> b is ByteArray && a.contentEquals(b)
        a is ShortArray -> b is ShortArray && a.contentEquals(b)
        a is FloatArray -> b is FloatArray && a.contentEquals(b)
        // 对象数组逐元素递归：元素本身可能又是数组（Array<IntArray>），
        // `contentEquals` 对元素走 equals ⇒ 内层数组退化成身份比较，与 atomHash 的内容口径不一致。
        a is Array<*> ->
            b is Array<*> && a.size == b.size &&
                (0 until a.size).all { atomEquals(a[it], b[it]) }

        else -> a == b
    }

    // ------------------------------------------------------------------
    // 规范表
    // ------------------------------------------------------------------

    private fun slotOf(h: Long): Int = mix(h * 0x9E3779B97F4A7C15uL.toLong()).toInt() and mask

    private fun mix(v: Long): Long {
        var x = v
        x = (x xor (x ushr 33)) * -0xae502812aa7333L
        x = (x xor (x ushr 33)) * -0x3b314601e57a13adL
        return (x xor (x ushr 33)) or 1L // 0 = 空槽
    }

    private fun grow() {
        val oldHashes = hashes
        val oldReps = reps
        val cap = reps.size shl 1
        hashes = LongArray(cap)
        reps = arrayOfNulls(cap)
        mask = cap - 1
        size = 0
        for (i in oldHashes.indices) {
            val r = oldReps[i] ?: continue
            val h = oldHashes[i]
            var j = slotOf(h)
            while (reps[j] != null) j = (j + 1) and mask
            reps[j] = r
            hashes[j] = h
            size++
        }
    }

    internal companion object {
        const val INITIAL_CAPACITY = 1 shl 12
        const val LIST_SEED = 0x1B5E7AL
        val EMPTY_FIELDS = emptyArray<Field>()
        val EMPTY_KINDS = IntArray(0)

        // 原子字段类型标记（[Shape.atomKinds]）：建 Shape 时定死，读取时走原始 getter。
        const val K_BOOL = 0
        const val K_BYTE = 1
        const val K_SHORT = 2
        const val K_INT = 3
        const val K_LONG = 4
        const val K_CHAR = 5
        const val K_FLOAT = 6
        const val K_DOUBLE = 7
        const val K_ENUM = 8
        const val K_OBJ = 9

        /**
         * 字段类型分类的**唯一判据**：true = 原子（按值比较），false = 子结点（递归）。
         *
         * `t.isArray` 覆盖全部数组（基本类型数组与对象数组，CMP-14）：数组类没有可反射字段，
         * 一旦被当成子结点，[shapeOf] 会给出空 Shape ⇒ 该类持有者的所有实例互相判「同构」。
         * `VarRef.indexArray` 是唯一有意的例外，它在建表时按字段名跳过（派生视图，见 `buildShape`）。
         */
        fun isAtomFieldType(t: Class<*>): Boolean = t.isPrimitive ||
            t == String::class.java ||
            t.isEnum ||
            Number::class.java.isAssignableFrom(t) ||
            t == java.lang.Boolean::class.java || t == java.lang.Character::class.java ||
            t.isArray

        /**
         * 形状表自检（`ArgInterner.probeShapes`）：对 [SHAPE_PROBE_CLASSES] 逐类建表并按
         * [validateShape] 的三条判据核对，返回违规描述（空 = 通过）。
         *
         * 与 `buildShape` 里的建表期抛错是同一套判据：装载撞上塌陷时**当场抛**，而本方法给
         * 闸上/诊断一个「不装载语料也能自证」的入口（2026-10-21 ART 事故的复盘要求）。
         */
        fun probeShapes(): List<String> {
            val probe = ArgInterner()
            val out = ArrayList<String>(0)
            for (c in SHAPE_PROBE_CLASSES) {
                val shape = try {
                    probe.shapeOf(c)
                } catch (e: IllegalStateException) {
                    out += e.message ?: "${c.name}: 形状表自检失败"
                    continue
                }
                out += probe.validateShape(c, shape)
            }
            return out
        }

        /**
         * 自检电池：当前模型里**带数组字段**或数组视图的类，加上各族的代表类。
         * 新增数组字段时把它加进来，`probeShapes` 就会盯住它的分类。
         */
        private val SHAPE_PROBE_CLASSES: List<Class<*>> = listOf(
            VarRef::class.java, // indices + indexArray（派生数组，按名跳过）
            IntAsignArgument::class.java, // lengths: IntArray
            StrAsignArgument::class.java, // lengths: IntArray
            SpSetArrayArgument::class.java, // constIntList: LongArray? / constStrList: Array<String>?
            ExpressionArrayArgument::class.java,
            ExpressionArgument::class.java,
            Binary::class.java,
            FormStringTerm::class.java,
            CaseExpression::class.java,
            MixedIntegerExprTerm::class.java,
        )
    }
}

/**
 * 折叠审计状态（2026-10-22 死循环事件复盘方案 #2；公开门面见 [StmtInternAudit]）。
 *
 * ## 为什么只要「可疑折叠」的 RED
 *
 * 规范化去重的**正常工作**就是海量折叠（全量语料数百万次），全留痕会把日志撑爆；
 * 而判等**健康**时折叠双方结构相等 ⇒ 关键摘要必相同。反过来，摘要不同却折叠了 =
 * `shapeOf`/`sameStructure` 判等不完整 —— 正是 2026-10-22 真机死循环的病态
 * （`VarRef(I)` 折向 `VarRef(_i)`、`IntLiteral(1)` 折成 `IntLiteral(0)`）。所以：
 * 健康折叠只计数，摘要不同的折叠进 RED 明细（上限 200 条防撑爆）。
 *
 * ## 开销与线程口径
 *
 * `enabled` 关闭时折叠路径只有一个 volatile boolean 读（零开销）；开启时每次折叠
 * 两次摘要构造 + 一个短临界区（诊断会话专用，不进常规闸）。
 *
 * **并发口径（CMP-24 起）**：`folds`/`red` 与 `log` 全部在 [lock] 内读写。原来的
 * 「装载单线程（ADR-001），无需加锁」已被证伪 —— `89bb966f`/`02bb8069` 之后装载期已经
 * 有两三条工作线程（段 3 索引、段 4/6/7 预取），BOARD 的下一步更是**分区并行 seed**：
 * 多个 `ArgInterner` 实例会同时折叠。裸 `++` 会丢计数、裸 `ArrayList.add` 并发会抛
 * `ArrayIndexOutOfBoundsException`/丢明细。
 *
 * 为什么选「加锁」而不是「实例态挂 `store.aux`」：`ArgInterner` 由
 * `Program.internStatementArgs()` 在**装载期**创建，那时还没有 `VariableStore`
 * （它由之后的 `GameSession` 才建）；而本对象是宿主/装置在装载**前**开关、装载**后**
 * 取读数的进程级诊断面。挪成实例态要动 `Program`/`GameSession`/`GameHost`，超出本路
 * 文件面，且会改变 `StmtInternAudit.drain()` 的公开语义。加锁只让「审计开启」这一诊断档
 * 付一个短监视器；并行 seed 下每个 `fold()` 与 `drain()` 线性化 —— 计数不丢、RED 不丢、
 * `drain()` 是一致快照（跨分区的先后顺序本就不在审计语义里，它只计数 + 收明细）。
 */
internal object FoldAuditState {
    /** 上限内记 RED 明细，超限只记省略行 —— 审计日志不许反过来打爆诊断通道。 */
    private const val MAX_LOG = 200

    @Volatile
    var enabled: Boolean = false

    /** 折叠/归一次数；只在 [lock] 内写，`drain()` 在锁内读。 */
    private var folds: Long = 0

    /** RED（关键摘要不同）次数；只在 [lock] 内写/读。 */
    private var red: Int = 0

    private val log = ArrayList<String>()

    /**
     * 审计状态的临界区锁（CMP-24）。
     *
     * 粒度为「一次折叠 / 一次 drain」：并行 seed 的各线程只在写计数与追加明细时互斥，
     * 摘要构造（[keyOf]）留在锁外，不把诊断开销放大成串行解析。
     */
    private val lock = Any()

    /** 字符串按内容归一：只计数，无「关键摘要可错」的维度，不进 RED。 */
    fun onStringFold() {
        synchronized(lock) { folds++ }
    }

    /** 折叠发生（[newNode] 被折向先物化的 [rep]）：摘要不同即 RED。 */
    fun fold(newNode: Any, rep: Any) {
        val kNew = keyOf(newNode)
        val kRep = keyOf(rep)
        synchronized(lock) {
            folds++
            if (kNew == kRep) return
            red++
            when {
                log.size < MAX_LOG ->
                    log += "!!折叠 ${newNode.javaClass.simpleName} 新=$kNew ⇒ 代表=$kRep" +
                        "（关键摘要不同 = 判等不完整，2026-10-22 真机死循环同型病）"
                log.size == MAX_LOG -> log += "…（RED 明细超上限 $MAX_LOG，后续省略）"
            }
        }
    }

    /**
     * 折叠双方的关键字段摘要：判等健康时同类同摘要；摘要不同 = 判等必病。
     * 覆盖本案全部主角（VarRef.name / IntLiteral.value / AssignStmt.dest / SpSet 全家），
     * 其余类型只给类名（给不出可靠关键字段的类型宁缺毋滥，避免审计自己制造误报）。
     */
    private fun keyOf(o: Any): String = when (o) {
        is VarRef -> "VarRef(${o.name})"
        is IntLiteral -> "Int(${o.value})"
        is StrLiteral -> "Str(${o.value.take(24)})"
        is AssignStmt -> "Assign(dest=${o.dest.name},op=${o.op})"
        is SpSetArgument -> "SpSet(dest=${o.dest.name},add=${o.addConst})"
        else -> o.javaClass.simpleName
    }

    /**
     * 返回并清空：摘要行在前、RED 明细在后。探针可跨多次装载重复取数。
     *
     * 整个读写都在 [lock] 内 ⇒ 拿到的是**一致快照**：不会把「正在追加的明细」读成半条，
     * 也不会与并行 seed 的计数更新交错（CMP-24）。
     */
    fun drain(): List<String> = synchronized(lock) {
        val lines = ArrayList<String>(log.size + 1)
        lines += "fold-audit: 总折叠 $folds 次，可疑（关键摘要不同）$red 次"
        lines += log
        folds = 0
        red = 0
        log.clear()
        lines
    }
}

/**
 * 折叠审计门面（2026-10-22 死循环事件复盘方案 #2）。
 *
 * [ArgInterner] 是 internal（仅 core-vm 模块可见），宿主（GameHost）与装置（GameBootSmoke）
 * 经本对象开关审计、取读数：
 * - `enabled = true` 必须在**装载前**设置（折叠发生在装载期，事后开无读数）；
 * - `drain()` 返回并清空读数（首行摘要 + RED 明细），可在装载完成后任意时刻取。
 *
 * RED 判定与上限见 [FoldAuditState]。默认关闭 = 零开销（折叠路径一个 volatile 读）。
 */
object StmtInternAudit {
    var enabled: Boolean
        get() = FoldAuditState.enabled
        set(value) {
            FoldAuditState.enabled = value
        }

    fun drain(): List<String> = FoldAuditState.drain()
}
