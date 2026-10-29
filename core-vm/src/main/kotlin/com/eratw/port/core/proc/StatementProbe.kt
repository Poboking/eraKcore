package com.eratw.port.core.proc

import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.stmt.Statement
import java.util.BitSet

/**
 * 语句级观测口（CONTRACT-DBG1 路1）：每条真实 ERB 语句（op 29..197 的 marker 指令）执行
 * **完毕**后由 [ProcessRuntime] 在 VM 线程**同步**回调一次。生产路径 [ProcessRuntime.statementProbe]
 * 缺省为 null = 零行为（守卫结构见该字段的 KDoc 与 executeStatement 内注释）。
 *
 * 互斥约定：`error != null` 时 [outcome] == null（语句失败，[error] 就是即将上抛给调用方的
 * 同一实例——CodeEE 已由 ProcessRuntime 盖好位置章）；`outcome != null` 时 [error] == null
 * （Normal / Transfer / Stop / Unwind 四种结局之一）。
 *
 * 回调实现**不得阻塞、不得抛**。探针异常由 runtime 用 runCatching 折进这里规定的吞口、
 * 不得影响语句语义：语句本身失败时探针异常折进该异常的 suppressed（原异常的类型与
 * message 原样上抛，留痕随异常可见）；语句本身正常时直接吞弃（观测口故障不得反噬执行流）。
 *
 * [env] 是 VM 内部可复用的可变对象（见 [StmtEnv] KDoc）：每条语句刷新一次字段。回调是
 * 同步的、当场读字段没有问题，但**回调实现不得持有 env 引用**跨语句使用——会读到被后续
 * 语句改写过的值；要留存就在回调内复制它的四个字段。
 *
 * [elapsedNs] 是本条语句从进入到出口的 `System.nanoTime` 差；只在探针非 null 时才取表，
 * 生产缺省路径连时钟都不碰。
 *
 * ## 为什么给 op / index 而不是语句实例（T2-64 波次 C）
 *
 * 波次 C 起运行期主路径是扁平语句池（[com.eratw.port.core.sem.FlatStmtSource]），语句树
 * 在装载期压扁后即弃；探针若是「按实例」的签名，桥里每条回调都得 `Program.statement(index)`
 * 物化一次树（真语料 171.7 万条池），等于把刚弃置的常驻开销按调试开关重新买回来。
 * 只传两个 int 后：**要计数的探针零取用**（`env.pc` 就够），要树的探针自己
 * `program.statement(index)` 按需还原（vmdbg 的 trace 摘要就是这条口径）。
 *
 * [op] = 语句的 `FunctionCode.ordinal`（与 [com.eratw.port.core.sem.FlatStmtSource.opAt]
 * 同口径，见 `code/FlatStatements.kt` 编码表的槽 1）；[index] = 语句池下标。
 * 旧树通道（`VmHost.executeStatement(stmt, env)`，只在没有扁平池的装置里走到）拿不到下标，
 * 按 [NO_STMT_INDEX] 回调。
 */
fun interface StatementProbe {
    fun onStatement(op: Int, index: Int, env: StmtEnv, outcome: StmtOutcome?, error: Throwable?, elapsedNs: Long)

    /**
     * 过渡重载（T2-64 波次 C）：以 `Statement` 实例为第一参数的旧口径。
     *
     * 波次 C 起探针拿到的只有 op/index（见接口 KDoc），桥里不再物化语句树；实现方一律
     * 只实现 op/index 版。本方法留作**编译期过渡壳**（lane D 与宿主侧实现切完之前全仓
     * 能单独编译），没有生产调用点，也不做任何事——收尾由组长删除。
     */
    @Deprecated("T2-64 波次 C：改用 onStatement(op, index, env, outcome, error, elapsedNs)")
    fun onStatement(
        stmt: Statement,
        env: StmtEnv,
        outcome: StmtOutcome?,
        error: Throwable?,
        elapsedNs: Long,
    ) {
        // 过渡壳：空实现。真有人还在用旧签名时它不会替你把回调转发过去，
        // 因此宁可什么都不做（探针是观测面，静默不做比半途尽力更不容易骗读数）。
    }

    companion object {
        /**
         * [onStatement] 的 `index` 哨兵：旧树通道（非扁平池）拿不到语句池下标时使用。
         * 探针实现不得把它当下标去查池（`Program.statement(-1)` 抛越界）。
         */
        const val NO_STMT_INDEX: Int = -1
    }
}

/**
 * 语句执行计数器（CONTRACT-DBG1 增量 2026-10-25：覆盖率仪表的数据面）。装进
 * [ProcessRuntime.statementProbe] 后统计两类读数：
 * - [executions]：回调总次数（同一条语句执行多次会重复计，区间过滤后口径）；
 * - [executedPcs]：执行过的**语句 marker 指令 pc** 位集 —— [distinctStatements] 即
 *   distinct 语句数，与「函数区间内 marker 总数」之比就是该函数的语句覆盖率
 *   （分母的扫描口径 = op 在 [com.eratw.port.core.code.Opcode.INTERNAL_COUNT]..
 *   [com.eratw.port.core.code.Opcode.MAX_STATEMENT_OPCODE] 的指令，工具侧扫描）。
 *
 * 可选区间 [pcFromInclusive, pcToExclusive)：典型 = 被测函数的指令区间（函数在 code 上
 * 连续，区间 = [entryPc, 下一函数 entryPc)）。区间外的回调**直接忽略**——嵌套被调方的
 * pc 落在别的函数区间，不计入本函数的分子；缺省全域。
 *
 * 开销只在被安装时存在（probe == null 的生产路径不经过本类，与探针本身的零开销判据
 * 同源）。单 VM 线程顺序回调（ADR-003），读数不需要同步。pc 口径 = [StmtEnv.pc]
 * （marker 指令的 pc）。本类不折探针异常也不委托其它探针——[ProcessRuntime.statementProbe]
 * 是单槽，要和环形缓冲等并存时由调用方自行包一层（语句失败路径的回调也计入：
 * 「执行过」≠「成功」，error 回调同样落 [executedPcs]）。
 */
class StatementExecutionCounter(
    val pcFromInclusive: Int = Int.MIN_VALUE,
    val pcToExclusive: Int = Int.MAX_VALUE,
) : StatementProbe {

    /** 回调总次数（区间过滤后口径：区间外的回调不计）。 */
    var executions: Long = 0L
        private set

    /** 执行过的 marker pc 位集（按 pc 去重；pc 即语句身份的稳定键）。 */
    val executedPcs = BitSet()

    override fun onStatement(op: Int, index: Int, env: StmtEnv, outcome: StmtOutcome?, error: Throwable?, elapsedNs: Long) {
        val pc = env.pc
        if (pc < pcFromInclusive || pc >= pcToExclusive) return
        executions++
        executedPcs.set(pc)
    }

    /** distinct 语句数（= 至少执行过一次的语句条数）。 */
    fun distinctStatements(): Int = executedPcs.cardinality()
}
