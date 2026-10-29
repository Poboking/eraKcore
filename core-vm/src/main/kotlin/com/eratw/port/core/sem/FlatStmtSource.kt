package com.eratw.port.core.sem

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.stmt.Statement

/**
 * T2-64 波次 B：语句池扁平化的运行期读面 —— code 层（`Program`）实现、sem 层
 * （[StatementExecutor] 直读通道）与 proc 桥消费的窄接口。契约 T2-64 §3.3。
 *
 * ## 为什么是窄接口
 *
 * 扁平容器本体（`code/FlatStatements.kt`）带着编码器/还原器全套读面；执行期只需要
 * 「取 op、取参数流区间、按下标取参数流槽、取池内表达式/字符串、兜底还原」这六个读点。
 * 收窄成接口后，表达式求值与变量句柄层（T2-61/62 收口的面）不必感知扁平容器，
 * `ExecContext` 也不新增 program 依赖。
 *
 * ## 直读的槽序契约
 *
 * 第 [index] 条语句占参数流区间 `[headAt(index), headAt(index+1))`，槽序与
 * `FlatStatements.encode` 逐字对偶（编码正确性由真语料全量对拍闸背书）：
 *
 * ```text
 * 槽 0 = StmtTag（语句类标签，ErbcFormat.StmtTag）
 * 槽 1 = FunctionCode.ordinal
 * 槽 2.. = 语句类专属字段
 * 之后 = 参数记录：[ArgTag][header][const 载荷（header bit1 时 3 槽）][形态字段...]
 * ```
 *
 * 直读分支按 [argStreamBase] 拿到只读数组后用**槽位偏移**读（零虚调用、零分配）；
 * 表达式与字符串经 [exprAt] / [stringAt]（数组直读以外的唯一虚调用点，实现单体、
 * JIT 可内联）。`long` 载荷占 2 槽，lo 在前（`IntSink.addLong` 同序）。
 *
 * ## statementAt 的实例身份契约（与纯还原的区别）
 *
 * 兜底路径与探针经 [statementAt] 取 `Statement`。**运行期实现必须返回装载期物化的
 * 同一实例**——数据节以语句实例为键（`ProcessRuntime.DataSectionSource` 的 KDoc，
 * 契约 C2「一条记录 = 一个实例」），还原出的新实例会静默丢组。纯扁平还原
 * （每调一次新建实例）是 `FlatStatements.statementAt`，只服务对拍装置与诊断，
 * 不是运行期兜底。波次 C 数据节键改语句池下标后，该约束随 `SidecarTables`
 * 反查层一起消失。
 */
interface FlatStmtSource {
    /** 第 [index] 条语句的 FunctionCode.ordinal。 */
    fun opAt(index: Int): Int

    /** 第 [index] 条语句参数流区间起点（[argStreamBase] 下标）。 */
    fun headAt(index: Int): Int

    /**
     * 第 [index] 条语句参数流区间终点（**不含**；`FlatArgs` 的越界判据用它）。
     *
     * 缺省实现 = `headAt(index + 1)`。这条缺省对**全部合法下标**（`0 until 语句数`）都成立，
     * 不是「除最后一条之外」的近似：扁平容器的 `heads` 是 `n + 1` 槽
     * （`heads[i]` = 第 i 条起点、`heads[n]` = 参数流长度，见 `code/FlatStatements.kt`
     * 的 `encode` 尾部），所以最后一条语句的 `headAt(n)` 取到的就是参数流长度。
     *
     * 实现者只有在「`headAt(语句数)` 不可读」时（例如按需物化、越界即抛的惰性池）才需要覆写，
     * 且返回值必须与 `headAt(index + 1)` 逐值一致 —— 读面语义不变，只是取法不同。
     */
    fun endAt(index: Int): Int = headAt(index + 1)

    /** 参数 int 流本体（只读；直读按槽位偏移取，区间见 [headAt]）。 */
    fun argStreamBase(): IntArray

    /**
     * 表达式槽读：`-1`（`ErbcFormat.NONE_REF`）= null；否则取回该下标的结点。
     * 实现保证同一实例恒映射同一下标，直读取回的实例与对象树路径持有的完全同一。
     */
    fun exprAt(poolIdx: Int): Expr?

    /** 字符串旁表读（只对非 `-1` 槽调用；`""` 是合法条目）。 */
    fun stringAt(poolIdx: Int): String

    /** 兜底还原第 [index] 条语句（实例身份契约见接口 KDoc）。 */
    fun statementAt(index: Int): Statement
}
