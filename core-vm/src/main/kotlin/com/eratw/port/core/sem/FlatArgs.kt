package com.eratw.port.core.sem

import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcFormat.ArgTag
import com.eratw.port.core.code.ErbcFormat.NONE_REF
import com.eratw.port.core.code.ErbcFormat.WireEnum
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.CaseExpression
import com.eratw.port.core.stmt.MixedIntegerExprTerm
import com.eratw.port.core.stmt.SortOrder
import com.eratw.port.core.stmt.SpDtColumnOptions

/**
 * T2-64 波次 C：一条语句**参数记录**的顺序游标（契约 CONTRACT-T2-64-C §2.1）。
 *
 * 构造于参数记录起点（`[ArgTag][header][const 载荷?][形态字段...]` 的第 0 槽），
 * 区间 `[start, end)` 由构造参数给出（`end` = `FlatStmtSource.endAt(语句下标)`）。
 * 调用方先 [open] 取形态标签与公共头，再**按编码器写槽的同一顺序**逐个调用
 * `expr()/str()/i32()/...` 读掉该形态的全部字段。游标是线性的：一次读完、不回退。
 *
 * ## 槽序 = 编码器写序（逐字对偶）
 *
 * 唯一权威是 `code/FlatStatements.kt` 的 `encode`（其 `Encoder.argument` 各分支 +
 * `addExpr/addExprOpt/addStr/addStrOpt/addLong/addDouble/intArray/childList/
 * childOptList/stringList/mixedList/caseList`）。本类**不发明槽序**，每个 reader 的推进
 * 槽数就是对应写入口的写槽数：
 *
 * | 写入口 | 槽数 | 本类 reader |
 * |---|---|---|
 * | `addExpr` / `addExprOpt` | 1（`-1` = null） | [expr] / [exprNN] |
 * | `addStr` / `addStrOpt` | 1（字符串旁表下标；`-1` = null） | [str] / [strOpt] |
 * | 裸 `sink.add`（bool / 枚举序号 / 位标志 / 计数） | 1 | [i32] / [sortOrder] |
 * | `IntSink.addLong` | 2（lo, hi） | [l64] |
 * | `IntSink.addDouble` | 2（`toRawBits` 的 lo, hi） | [f64] |
 * | `intArray` | `n + n` | [intArray] |
 * | `childList` | `n + n`（项恒非空） | [exprList] |
 * | `childOptList` | `n + n`（项可空） | [exprOptList] |
 * | `stringList` | `n + n` | [strList] |
 * | `stringListOpt`（可空 `n = -1`） | 1（null）或 `n + n` | [strListOpt] |
 * | `longListOpt`（可空 `n = -1`） | 1（null）或 `n + n×2` | [l64ListOpt] |
 * | `mixedList` | `n + 每项（表达式槽 + isPx 槽）` | [mixedList] / [mixedListOpt] |
 * | `caseList` | `n + 每项（type + left + right + operator）` | [caseList] |
 *
 * **`long`/`double` 一律 lo 在前**（`IntSink.addLong` 先写低 32 位、再写 `ushr 32`），
 * `flat` 侧的 `FlatCursor.nextLong` 是它的逆运算 —— 两侧同序，读反了会把
 * `SP_TIMES`/`STR_DOUBLE` 的载荷读成垃圾（波次 B 的 SET 槽序错位是同类教训）。
 *
 * ## 公共头（[open]）
 *
 * 每个参数记录（含 `VoidArgument`）都以 `[ArgTag][header]` 开头，`header` 的判定与
 * erbc 写侧逐字一致：bit0 = `isConst`；bit1 = **有常量载荷**（`isConst || constStr 非空 ||
 * constInt != 0`，见 `Encoder.header`）。bit1 置位时紧随 **3 槽**：
 * `[constStr 的字符串旁表下标][constInt lo][constInt hi]`；`open` 内部消费掉这 3 槽，
 * 调用方看到的游标位置就是「形态字段第 1 槽」。bit0 置位而 bit1 未置位不可能出现
 * （isConst 是 hasPayload 的充分条件），读侧无需为此分支。
 *
 * ## reader 与 Argument 形态的对应
 *
 * | Argument 形态 | 读法 |
 * |---|---|
 * | `VoidArgument` | `open` 后无字段 |
 * | `ExpressionArgument` | [expr]（term 可空） |
 * | `ExpressionArrayArgument` | [exprOptList] |
 * | `ExpressionsArgument` | [expressions]（类型槽丢弃，只回 terms） |
 * | `IntAsignArgument` | [str]（name）+ [intArray]（lengths）+ [expr]（exp） |
 * | `StrAsignArgument` | [str]（name）+ [intArray]（lengths）+ [strOpt]（value） |
 * | `BitArgument` | [varRefNN]（dest）+ [exprList]（terms） |
 * | `CaseArgument` | [caseList] |
 * | `MethodArgument` | [expr] |
 * | `PrintDataArgument` / `StrDataArgument` | [varRefOpt]（dest） |
 * | `RefArgument` | [str]（refName）+ [strOpt]（srcName）+ [expr]（srcTerm）+ [i32]（refByName != 0） |
 * | `SpArrayControlArgument` | [varRefNN] + [expr] + [expr] |
 * | `SpArrayShiftArgument` | [varRefNN] + 4× [expr] |
 * | `SpArraySortArgument` | [varRefNN] + [sortOrder] + 2× [expr] |
 * | `SpBarArgument` | 3× [expr]（value / max / length） |
 * | `SpButtonArgument` | 2× [expr] |
 * | `SpCVarSetArgument` | [varRefNN] + 4× [expr] |
 * | `SpCallArgument` / `SpCallFArgument` / `SpCallSharpArgument` | [expr] + [exprOptList]（subNames）+ [exprOptList]（rowArgs） |
 * | `SpColorArgument` | 4× [expr]（r / g / b / rgb；二形态构造出的缺项槽为 `-1`） |
 * | `SpCopyArrayArgument` | 2× [expr] |
 * | `SpForNextArgument` | [varRefNN]（cnt）+ 3× [expr] |
 * | `SpGetIntArgument` | [varRefNN] |
 * | `SpHtmlPrint` | 2× [expr] |
 * | `SpHtmlSplitArgument` | [expr] + [str]（destVarName）+ [varRefOpt]（num） |
 * | `SpInputsArgument` | 3× [expr] |
 * | `SpPowerArgument` | [varRefNN] + 2× [expr] |
 * | `SpPrintImgArgument` | 3× [expr] + [mixedListOpt]（param 可空） |
 * | `SpPrintShapeArgument` | [mixedList] |
 * | `SpPrintVArgument` | [exprList] |
 * | `SpSaveDataArgument` | 2× [expr] |
 * | `SpSaveVarArgument` | 2× [expr] + [strList]（varNames） |
 * | `SpSetArgument` | [varRefNN]（dest）+ [expr]（term）+ [i32]（addConst != 0） |
 * | `SpSetArrayArgument` | [varRefNN]（dest）+ [exprOptList]（terms）+ [l64ListOpt]（constIntList）+ [strListOpt]（constStrList） |
 * | `SpDtColumnOptions` | [expr]（dt）+ [expr]（column）+ [dtOptions]（options）+ [exprOptList]（values） |
 * | `SpSortcharaArgument` | [varRefNN]（sortKey）+ [sortOrder] |
 * | `SpSplitArgument` | 2× [expr] + [str]（destVarName）+ [varRefOpt]（num） |
 * | `SpSwapCharaArgument` | 2× [expr] |
 * | `SpSwapVarArgument` | 2× [varRefNN] |
 * | `SpTInputsArgument` | 6× [expr] |
 * | `SpTimesArgument` | [varRefNN]（dest）+ [f64]（doubleValue） |
 * | `SpVarSetArgument` | [varRefNN] + 3× [expr] |
 * | `SpVarsizeArgument` | [str]（varName） |
 * | `StrDoubleArgument` | [expr]（term）+ [f64]（doubleValue） |
 *
 * ## 契约 §2.1 之外的追加读面（负责人 2026-10-28 批准，lane D 按此逐字调用）
 *
 * §2.1 的 reader 集合覆盖不了下面四类记录，负责人批准追加 5 个方法（名字即契约，
 * 改动须走契约而不是本类）：
 *
 * 1. [expressions]：`ExpressionsArgument`（`ArgTag.EXPRESSIONS`）。记录是
 *    `n + 每项（类型序号 1 槽 + 表达式槽）`，本方法**跳过类型槽**只回 terms ——
 *    执行面（`StatementExecutor` 的 `argTerms`）本来就只读 `terms`；编译期只有
 *    `INPUTMOUSEKEY` 用该形态且 `types` 恒为 `[INT]`（`ArgBuilders.kt:63` +
 *    `FunctionCode.kt:248`）。
 * 2. [l64ListOpt]：`SpSetArrayArgument.constIntList`。`-1` = null，否则 `n + n×long`（lo, hi）。
 * 3. [strListOpt]：`SpSetArrayArgument.constStrList`。`-1` = null，否则 `n + n×字符串下标`。
 * 4. [mixedListOpt]：`SpPrintImgArgument.param`。`-1` = null，否则同 [mixedList]。
 * 5. [dtOptions]：`SpDtColumnOptions.options`。`n + n×DTOption 线值`。
 *    **它只是 options 段**：调用方随后必须用 [exprOptList] 读 `values` 段（记录尾部的
 *    `childOptList`），顺序不能颠倒，也不能跳段。
 *
 * 这四类形态在波次 B 走的是 `statementAt` 兜底；补上读面后 lane D 才能把它们也直读化。
 *
 * ## 边界
 *
 * 只读 `FlatStmtSource.argStreamBase()/exprAt()/stringAt()` 三个读点，不反射、不构造
 * `Statement`/`Argument`（「压扁即弃」后树不保证常驻）。区间是构造参数 `[start, end)`：
 * `end` 取 `FlatStmtSource.endAt(语句下标)`，越界即在**本语句记录终点**处报错（文案带
 * `start`/`end`/`size`），不会静默借到下一条语句的槽；2 参次构造只在拿不到语句下标时用
 * （见其 KDoc）。
 */
internal class FlatArgs(private val src: FlatStmtSource, start: Int, private val end: Int) {

    /**
     * 退化构造：区间终点取参数流数组长度（`[start, 参数流末尾)`）。
     *
     * **只在拿不到语句下标时用**（例如诊断/临时调试里直接对着 `argStreamBase()` 开游标）；
     * 正式调用点必须给 `end = source.endAt(语句下标)`，否则一条语句多写/少写槽时错了也不会
     * 当场报，而是静默读到相邻语句的记录。
     */
    constructor(src: FlatStmtSource, start: Int) : this(src, start, src.argStreamBase().size)

    /** 参数流本体（构造时取一次；读期间不变）。 */
    private val stream: IntArray = src.argStreamBase()

    /** 记录起点（槽 0 = ArgTag）；只用于错误定位与 [open] 复位。 */
    private val startSlot: Int = start

    /** 顺序游标：下一个待读槽的 [stream] 下标。 */
    private var p: Int = start

    /** 公共头原值（[open] 写入）：bit0 = isConst、bit1 = 有常量载荷。 */
    private var header: Int = 0

    /** 载荷字符串（[open] 写入；无载荷时 `""`）。 */
    private var payloadStr: String = ""

    /** 载荷整数（[open] 写入；无载荷时 `0L`）。 */
    private var payloadInt: Long = 0L

    /** 公共头 bit0：参数整体是编译期常量（对偶 `Argument.isConst`）。 */
    val isConst: Boolean get() = (header and HEAD_BIT_CONST) != 0

    /** 载荷第 1 槽解出的常量字符串（对偶 `Argument.constStr`；无载荷时 `""`）。 */
    val constStr: String get() = payloadStr

    /** 载荷第 2、3 槽解出的常量整数（对偶 `Argument.constInt`；无载荷时 `0L`）。 */
    val constInt: Long get() = payloadInt

    /**
     * 读入 `[ArgTag][header][载荷?]`，把 `isConst`/`constStr`/`constInt` 收进字段并把
     * 游标定到**形态字段第 1 槽**，返回 ArgTag。
     *
     * 可重复调用（每次都从 [startSlot] 重读）。返回值的形态校验留给调用方：执行面按
     * 语句形态预期 tag，不符就该转兜底而不是硬读（波次 B `execSetFlat` 的同款纪律）。
     */
    fun open(): Int {
        p = startSlot
        val tag = next()
        if (tag == ArgTag.NONE) {
            throw ErbcDecodeException(p - 1, "扁平参数记录出现派生参数标签 NONE")
        }
        if (tag < ArgTag.VOID || tag > ArgTag.HTML_PRINT_LINE_END) {
            throw ErbcDecodeException(p - 1, "扁平参数记录未知参数标签: $tag")
        }
        val h = next()
        if ((h and HEAD_UNDEFINED_MASK) != 0) {
            throw ErbcDecodeException(p - 1, "扁平参数头含未定义位: 0x${h.toString(16)}")
        }
        header = h
        payloadStr = ""
        payloadInt = 0L
        if ((h and HEAD_BIT_PAYLOAD) != 0) {
            // 载荷 3 槽：字符串旁表下标 + constInt 的 lo/hi（与 Encoder.header 同序）
            val strIdx = next()
            if (strIdx < 0) {
                throw ErbcDecodeException(p - 1, "扁平参数载荷字符串下标为负: $strIdx")
            }
            payloadStr = src.stringAt(strIdx)
            payloadInt = l64()
        }
        return tag
    }

    /** 1 槽：表达式池下标；`-1` = null（对偶 `addExprOpt`）。 */
    fun expr(): Expr? {
        val idx = next()
        if (idx == NONE_REF) return null
        return exprAt(idx)
    }

    /** 1 槽：表达式池下标，`-1` 按畸形记录拒绝（对偶 `addExpr`）。 */
    fun exprNN(): Expr = expr() ?: throw ErbcDecodeException(p - 1, "扁平参数记录要求非空表达式，槽值为 $NONE_REF")

    /** 1 槽：字符串旁表下标（对偶 `addStr`；`""` 是合法条目，负下标是畸形记录）。 */
    fun str(): String {
        val idx = next()
        if (idx == NONE_REF) {
            throw ErbcDecodeException(p - 1, "扁平参数记录要求非空字符串，槽值为 $NONE_REF")
        }
        return stringAt(idx)
    }

    /** 1 槽：字符串旁表下标；`-1` = null（对偶 `addStrOpt`）。 */
    fun strOpt(): String? {
        val idx = next()
        if (idx == NONE_REF) return null
        return stringAt(idx)
    }

    /** 1 槽：裸 int（bool 的 0/1、枚举序号、位标志、列表计数之后的标量都走它）。 */
    fun i32(): Int = next()

    /** 2 槽：`long` 载荷，lo 在前（对偶 `IntSink.addLong`）。 */
    fun l64(): Long {
        val lo = next().toLong() and 0xffffffffL
        val hi = next().toLong() and 0xffffffffL
        return (hi shl 32) or lo
    }

    /** 2 槽：`double` 载荷，raw bits 的 lo/hi（对偶 `IntSink.addDouble`）。 */
    fun f64(): Double = Double.fromBits(l64())

    /** `n + n` 槽（对偶 `intArray`；`n` 为负或超过剩余槽数均按畸形记录拒绝）。 */
    fun intArray(): IntArray {
        val n = count("intArray")
        return IntArray(n) { next() }
    }

    /** `n + n` 槽，项恒非空（对偶 `childList`，如 `BitArgument.terms` / `SpPrintVArgument.terms`）。 */
    fun exprList(): List<Expr> {
        val n = count("exprList")
        return ArrayList<Expr>(n).apply { repeat(n) { add(exprNN()) } }
    }

    /** `n + n` 槽，项可空（对偶 `childOptList`，如 `ExpressionArrayArgument.terms`）。 */
    fun exprOptList(): List<Expr?> {
        val n = count("exprOptList")
        return ArrayList<Expr?>(n).apply { repeat(n) { add(expr()) } }
    }

    /** `n + n` 槽（对偶 `stringList`，如 `FuncLabelStmt.paramNames` / `SpSaveVarArgument.varNames`）。 */
    fun strList(): List<String> {
        val n = count("strList")
        return ArrayList<String>(n).apply { repeat(n) { add(str()) } }
    }

    /** `n + 每项（表达式槽 + isPx 槽）`（对偶 `mixedList`，`SpPrintShapeArgument.param` 等）。 */
    fun mixedList(): List<MixedIntegerExprTerm> = readMixed(count("mixedList", slotsPerItem = 2))

    /**
     * 可空 `mixedList`（对偶 `Encoder.mixedListOpt`，`SpPrintImgArgument.param`）：
     * `-1` = 整个列表为 null，否则同 [mixedList]。
     */
    fun mixedListOpt(): List<MixedIntegerExprTerm>? {
        val n = next()
        if (n == NONE_REF) return null
        return readMixed(checkCount("mixedListOpt", slotsPerItem = 2, n = n))
    }

    /**
     * 可空字符串表（对偶 `Encoder.stringList` 的 null 形态，`SpSetArrayArgument.constStrList`）：
     * `-1` = null，否则 `n + n×字符串旁表下标`。
     */
    fun strListOpt(): Array<String>? {
        val n = next()
        if (n == NONE_REF) return null
        val size = checkCount("strListOpt", slotsPerItem = 1, n = n)
        return Array(size) { str() }
    }

    /**
     * 可空 `long` 表（对偶 `Encoder` 的 `SpSetArrayArgument.constIntList` 分支）：
     * `-1` = null，否则 `n + n×long`（每项 2 槽，lo 在前）。
     */
    fun l64ListOpt(): LongArray? {
        val n = next()
        if (n == NONE_REF) return null
        val size = checkCount("l64ListOpt", slotsPerItem = 2, n = n)
        return LongArray(size) { l64() }
    }

    /**
     * `ExpressionsArgument` 的表达式列表（对偶 `Encoder.argument` 的 `EXPRESSIONS` 分支）：
     * `n + 每项（类型序号 1 槽 + 表达式槽）`。**类型槽被跳过并丢弃** —— 执行面只用 terms
     * （`StatementExecutor` 的 `argTerms` 同口径），编译期该形态的 `types` 恒为 `[INT]`
     * （只有 `INPUTMOUSEKEY` 用它）。
     */
    fun expressions(): List<Expr?> {
        val n = count("expressions", slotsPerItem = 2)
        return ArrayList<Expr?>(n).apply {
            repeat(n) {
                next() // 类型序号槽（ArgValueType ordinal 或 -1）：丢弃
                add(expr())
            }
        }
    }

    /**
     * `SpDtColumnOptions.options` 段（对偶 `Encoder.argument` 的 `SP_DT_COLUMN_OPTIONS` 分支）：
     * `n + n×DTOption 线值`（当前只有 `WireEnum.DT_DEFAULT`，其它线值按畸形记录拒绝）。
     *
     * **只是 options 段**：记录尾部还有 `values`（`childOptList`），调用方必须紧接着用
     * [exprOptList] 读，不能跳段也不能调换顺序 —— 跳过后游标错位，后续读数是下一条语句的槽。
     */
    fun dtOptions(): List<SpDtColumnOptions.DTOption> {
        val n = count("dtOptions")
        return ArrayList<SpDtColumnOptions.DTOption>(n).apply {
            repeat(n) {
                add(
                    when (val wire = next()) {
                        WireEnum.DT_DEFAULT -> SpDtColumnOptions.DTOption.DEFAULT
                        else -> throw ErbcDecodeException(p - 1, "扁平参数记录未知 DTOption 线值: $wire")
                    },
                )
            }
        }
    }

    /** `n` 项 mixedList 项体（计数已由调用方读并校验）。 */
    private fun readMixed(n: Int): List<MixedIntegerExprTerm> = ArrayList<MixedIntegerExprTerm>(n).apply {
        repeat(n) {
            val num = expr()
            val isPx = next() != 0
            add(MixedIntegerExprTerm(num, isPx))
        }
    }

    /** `n + 每项（type + left + right + operator）`（对偶 `caseList`，`CaseStmt` / `CaseArgument` 专用）。 */
    fun caseList(): List<CaseExpression> {
        val n = count("caseList", slotsPerItem = 4)
        return ArrayList<CaseExpression>(n).apply {
            repeat(n) {
                val type = caseType(next())
                val left = expr()
                val right = expr()
                val op = OperatorCode.entries[next()]
                add(CaseExpression(type, left, right, op))
            }
        }
    }

    /** 1 槽：`SortOrder` 线值（对偶 `Encoder.sortOrderWire`，ErbcFormat.WireEnum 的 SORT_* 三值）。 */
    fun sortOrder(): SortOrder = when (val wire = next()) {
        WireEnum.SORT_UNDEF -> SortOrder.UNDEF
        WireEnum.SORT_ASCENDING -> SortOrder.ASCENDING
        WireEnum.SORT_DESCENDING -> SortOrder.DESENDING
        else -> throw ErbcDecodeException(p - 1, "扁平参数记录未知 SortOrder 线值: $wire")
    }

    /** 1 槽：表达式池下标，必须是 `VarRef`（对偶 `addExpr` + 解码侧 `varRefAt`）。 */
    fun varRefNN(): VarRef {
        val idx = p
        val e = exprNN()
        return e as? VarRef
            ?: throw ErbcDecodeException(idx, "扁平参数记录表达式应为 VarRef，实为 ${e.javaClass.simpleName}")
    }

    /** 1 槽：表达式池下标；`-1` = null，其余必须是 `VarRef`（对偶 `addExprOpt` + `varRefOptAt`）。 */
    fun varRefOpt(): VarRef? {
        val idx = p
        val e = expr() ?: return null
        return e as? VarRef
            ?: throw ErbcDecodeException(idx, "扁平参数记录表达式应为 VarRef，实为 ${e.javaClass.simpleName}")
    }

    // ------------------------------------------------------------------
    // 槽读原语
    // ------------------------------------------------------------------

    /** 读一槽并推进；游标越过**本语句区间** `[start, end)` 或参数流末尾即畸形记录。 */
    private fun next(): Int {
        if (p < 0 || p >= end || p >= stream.size) {
            throw ErbcDecodeException(
                if (p < 0) 0 else p,
                "扁平参数记录越界（start=$startSlot, end=$end, size=${stream.size}）",
            )
        }
        return stream[p++]
    }

    /**
     * 列表/数组的计数槽：负值只可能是畸形记录（可空列表的 `-1` 由各 `xxxOpt` reader 先拦）；
     * 超过**本语句区间剩余槽数**同样当场拒绝 —— 否则一个损坏的计数会先撞上巨型分配/越界读，
     * 报错与真因无关。
     */
    private fun count(what: String, slotsPerItem: Int = 1): Int =
        checkCount(what, slotsPerItem, next())

    /** [count] 的已读版本（`xxxOpt` reader 要先判 `-1` 才能决定是否当计数用）。 */
    private fun checkCount(what: String, slotsPerItem: Int, n: Int): Int {
        if (n < 0) {
            throw ErbcDecodeException(p - 1, "扁平参数记录 $what 的计数为负: $n")
        }
        val remain = (end - p).coerceAtLeast(0)
        if (n.toLong() * slotsPerItem > remain.toLong()) {
            throw ErbcDecodeException(
                p - 1,
                "扁平参数记录 $what 计数 $n 超过区间剩余槽数 $remain（end=$end）",
            )
        }
        return n
    }

    /** 非 `-1` 的表达式槽：池内/旁挂取回结点；取不到说明读面与记录不一致。 */
    private fun exprAt(idx: Int): Expr {
        if (idx < 0) {
            throw ErbcDecodeException(p - 1, "扁平参数记录表达式下标为负: $idx")
        }
        return src.exprAt(idx)
            ?: throw ErbcDecodeException(p - 1, "扁平参数记录表达式下标 $idx 不在池内")
    }

    /** 非 `-1` 的字符串槽：旁表读。 */
    private fun stringAt(idx: Int): String {
        if (idx < 0) {
            throw ErbcDecodeException(p - 1, "扁平参数记录字符串下标为负: $idx")
        }
        return src.stringAt(idx)
    }

    /** `CaseExpression.CaseType` 线值（对偶 `Encoder.caseList` 的 WireEnum.CASE_*）。 */
    private fun caseType(wire: Int): CaseExpression.CaseType = when (wire) {
        WireEnum.CASE_NORMAL -> CaseExpression.CaseType.NORMAL
        WireEnum.CASE_TO -> CaseExpression.CaseType.TO
        WireEnum.CASE_IS -> CaseExpression.CaseType.IS
        else -> throw ErbcDecodeException(p - 1, "扁平参数记录未知 CaseType 线值: $wire")
    }

    private companion object {
        /** 公共头 bit0：`isConst`（对偶 `Encoder.header`）。 */
        const val HEAD_BIT_CONST = 0x1

        /** 公共头 bit1：有 3 槽常量载荷（对偶 `Encoder.header` 的 `hasPayload`）。 */
        const val HEAD_BIT_PAYLOAD = 0x2

        /** 公共头未定义位（bit2..bit7；与 `FlatStatements.argument` 的解码校验同掩码）。 */
        const val HEAD_UNDEFINED_MASK = 0xFC
    }
}
