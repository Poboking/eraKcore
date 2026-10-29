package com.eratw.port.vmdbg

import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.StatementProbe
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * `trace`：语句级执行轨迹（CONTRACT-DBG1 路2）。依赖路1 的语句探针
 * （StatementProbe，fun interface onStatement(op, index, env, outcome, error, elapsedNs)，
 * T2-64 波次 C 起带池下标）与 `ProcessRuntime.statementProbe` 注入口（@Volatile var，
 * public）—— 该探针由 dbg1-hooks 路并行实现，**本 worktree 未合流前本文件编译阻塞，
 * 属预期状态**；其余命令的单测已在写本文件之前跑绿（读数见交付报告）。
 *
 * ## 时序契约（CONTRACT-DBG1 路2「trace 时序」）
 *
 * - 调用前 `runtime.statementProbe = <环形缓冲探针>`；探针回调在 VM 线程**同步**执行，
 *   每条真实 ERB 语句（op 29..197）执行完毕后一次；error != null 时 outcome == null。
 * - [StmtEnv 是可复用可变对象]（Vm.kt:70-75 的 KDoc）：回调里**同步读**
 *   `env.functionKey/line/pc` 与 [Briefs.stmt] 摘要后**复制**进环形缓冲，禁止存引用。
 * - 回调不得阻塞、不得抛 —— 探针体全部包 runCatching，失败静默丢该条（不干扰执行）。
 * - 环形缓冲定长（缺省 [Cli.DEFAULT_TRACE_CAPACITY]，超限丢最旧）；跑完输出
 *   `行号 语句摘要 耗时` 逐条（`--out 文件` 落盘代替 stdout）。
 */
object CmdTrace {

    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val name = a.positional.getOrNull(1) ?: throw CliError("用法: trace <函数名> [--steps N] [--capacity N] [--out 文件]")
        val assembled = Assembler.assemble(a)
        val capacity = a.longOr("--capacity", Cli.DEFAULT_TRACE_CAPACITY.toLong()).toInt()
        if (capacity <= 0) throw CliError("--capacity 必须为正整数")
        val dump = TraceSupport.install(assembled.session.runtime, capacity)
        val (intArgs, strArgs) = CmdCall.parseArgs(a.value("--args"))
        return CmdCall.invokeAndReport(
            assembled, name, intArgs, strArgs, a,
            out = out,
            extraOutput = { TraceSupport.dump(dump, out, a.value("--out")) },
        )
    }
}

/** 环形缓冲一条轨迹（全是**复制值**：StmtEnv 与 Statement 都不进来）。 */
class TraceEntry(
    val functionKey: String,
    val line: Int,
    val pc: Int,
    val brief: String,
    val outcome: String?,
    val error: String?,
    val elapsedNs: Long,
)

/**
 * 定长环形缓冲（单写单读：探针回调在 VM 线程写、dump 在同线程调用之后读 ——
 * sweep/call 的执行模型保证无并发；仍按环形数组实现，超限丢最旧）。
 */
class TraceRing(private val capacity: Int) {

    private val buf = ArrayList<TraceEntry>(capacity)
    /** 因缓冲满被丢弃的最旧条数（超限丢最旧的可见证据）。 */
    var dropped: Long = 0
        private set

    fun add(e: TraceEntry) {
        if (buf.size >= capacity) {
            buf.removeAt(0)
            dropped++
        }
        buf.add(e)
    }

    fun snapshot(): List<TraceEntry> = buf.toList()
}

/** trace 的装配/输出面（Trace.kt 内聚；CmdCall 经 extraOutput 闭包调用 dump，不依赖本类型）。 */
object TraceSupport {

    /**
     * 装探针：`runtime.statementProbe = StatementProbe { ... }`（CONTRACT-DBG1 §4 路1；
     * T2-64 波次 C 起签名带 op/index，见 [StatementProbe] KDoc）。回调内复制字段进环形缓冲
     * 并映射 outcome/error 为摘要串。
     *
     * trace 要语句摘要（[Briefs.stmt]），故按 `index` 现取一次池：**只有探针在场时付费**，
     * 生产缺省 probe == null 时桥里连这个回调都不进。旧树通道（[StatementProbe.NO_STMT_INDEX]，
     * 没有扁平池的装置）不求值，摘要记 `(null)`。
     */
    fun install(rt: ProcessRuntime, capacity: Int): TraceRing {
        val ring = TraceRing(capacity)
        rt.statementProbe = StatementProbe { _, index, env, outcome, error, elapsedNs ->
            runCatching {
                // StmtEnv 可复用可变：四个字段此刻同步抄进不可变 TraceEntry
                val stmt = if (index >= 0) rt.program.statement(index) else null
                ring.add(
                    TraceEntry(
                        functionKey = env.functionKey,
                        line = env.line,
                        pc = env.pc,
                        brief = Briefs.stmt(stmt),
                        outcome = outcome?.let { outcomeTag(it) },
                        error = error?.let { "${it.javaClass.simpleName}: ${it.message ?: ""}" },
                        elapsedNs = elapsedNs,
                    ),
                )
            }
        }
        return ring
    }

    /** StmtOutcome → 短标签（Normal/Transfer(pc)/Stop(value)/Unwind(value)，Vm.kt:47-68）。 */
    private fun outcomeTag(o: StmtOutcome): String = when (o) {
        is StmtOutcome.Normal -> "Normal"
        is StmtOutcome.Transfer -> "Transfer(${o.pc})"
        is StmtOutcome.Stop -> "Stop(${o.value})"
        is StmtOutcome.Unwind -> "Unwind(${o.value})"
    }

    /** 输出 `行号 语句摘要 耗时` 逐条；[file] 非 null 落盘代替 stdout。 */
    fun dump(ring: TraceRing, out: PrintStream, file: String?) {
        val entries = ring.snapshot()
        val lines = ArrayList<String>(entries.size + 2)
        lines.add("--- trace (${entries.size} entries, dropped-oldest ${ring.dropped}) ---")
        for (e in entries) {
            val tail = buildString {
                append(e.elapsedNs).append("ns")
                if (e.error != null) append(" ERROR=").append(e.error)
            }
            lines.add("${e.line} ${e.brief} $tail")
        }
        if (file != null) {
            val p = Path.of(file).toAbsolutePath().normalize()
            p.parent?.let { Files.createDirectories(it) }
            Files.write(p, lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            out.println("trace-file=$p")
        } else {
            for (l in lines) out.println(l)
        }
    }
}
