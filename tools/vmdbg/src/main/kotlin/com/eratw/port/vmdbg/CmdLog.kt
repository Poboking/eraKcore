package com.eratw.port.vmdbg

import com.eratw.port.core.proc.FunctionExitKind
import com.eratw.port.core.proc.FunctionProbe
import com.eratw.port.core.rand.RngProbe
import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.core.vars.VarWriteProbe
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * `log`：结构化事件流（DBG4 P3）—— 定位「是什么把执行带到那」的黑匣子。
 *
 * ```
 * vmdbg log --tags event,rng[,var] [--watch N1,N2] [--max N] [--out <文件>] -- call <FN> [--args ...]
 * ```
 *
 * `--` 之后的 token **原样转发给 call**（本命令只包装 call，sweep 不包）：转发段独立
 * 重新解析，`--args` / `--steps` 的语义与 [CmdCall] 相同；`--steps` 生效位在会话装配期
 * （全局段与转发段都接受，转发段优先），装配完成后不再改闸。
 *
 * 输出：`--out`（缺省 `<work>/dbg-log.jsonl`）JSONL，每行
 * `{"seq":n,"tag":"...","event":"...","payload":{...}}`。三个挂点全部走 core 侧
 * 可空观察口（null/缺省 = 零行为，生产路径零变化）：
 * - `event`：[ProcessRuntime.functionProbe]（函数进出，meta 可为 null；离场恒
 *   [FunctionExitKind.EXIT]，语义边界见 [FunctionProbe] KDoc）；
 * - `rng`：[Sfmt19937.drawProbe]（每次 nextUInt32 出号：值 + 挂口后累计序号）；
 * - `var`：[com.eratw.port.core.vars.VariableStore.writeProbe]（watch 名单命中的写入，
 *   入口/校验前回调，old 由记录器回读、new = 请求值）。
 *
 * `--max` 缺省 1,000,000 条，到达后停止记录（truncated=true 进摘要），执行本身照常跑完。
 * VM 单线程回调（ADR-003），记录器无锁；约每 10,000 行 flush 一次，硬崩最多丢一段缓冲。
 */
object CmdLog {

    /** 可用 tag 集合（规格 §5：event / rng / var）。 */
    private val KNOWN_TAGS = setOf("event", "rng", "var")

    /** 缺省截断条数（规格 §5：--max 缺省 1,000,000）。 */
    const val DEFAULT_MAX_EVENTS = 1_000_000L

    /** flush 间隔（行数）：硬崩最多丢这段缓冲。 */
    internal const val FLUSH_EVERY = 10_000L

    fun run(rawArgs: Array<String>, out: PrintStream, err: PrintStream): Int {
        val dd = rawArgs.indexOf("--")
        if (dd < 0) throw CliError("log 需要 '-- call <FN> ...' 的转发段（-- 之后原样转发给 call）")
        val prefixRaw = rawArgs.take(dd).toList()
        val tail = rawArgs.drop(dd + 1)
        if (tail.isEmpty() || tail[0] != "call") {
            throw CliError("'--' 之后只支持 call（log 只包装 call）: ${tail.firstOrNull() ?: "<空>"}")
        }
        val prefix = Args.parse(prefixRaw)
        val callParsed = Args.parse(tail.drop(1))

        // tags 校验
        val tags = prefix.valueOr("--tags", "event").split(',')
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val unknown = tags - KNOWN_TAGS
        if (unknown.isNotEmpty()) throw CliError("未知 --tags 值: $unknown（可用 event,rng,var）")
        val watch = prefix.value("--watch")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if ("var" in tags && watch.isEmpty()) throw CliError("--tags var 需要 --watch 名单（--watch NAME1,NAME2）")
        val max = prefix.longOr("--max", DEFAULT_MAX_EVENTS)
        if (max <= 0) throw CliError("--max 必须为正整数: $max")

        // 转发段的 --steps 升格为会话步数闸（装配期生效，见类 KDoc）
        val stepsFromTail = callParsed.value("--steps")
        val assembleArgs = if (stepsFromTail != null && prefix.value("--steps") == null) {
            prefixRaw + listOf("--steps", stepsFromTail)
        } else {
            prefixRaw
        }
        val assembled = Assembler.assemble(Args.parse(assembleArgs))

        val outPath = Path.of(prefix.valueOr("--out", defaultLogPath(prefix))).toAbsolutePath().normalize()
        Files.createDirectories(outPath.parent)
        val recorder = LogRecorder(outPath, max, tags, watch, assembled.session.store)

        // 挂观察口（未选的 tag 不挂：缺省零行为从装配面就成立）
        if ("event" in tags) assembled.session.runtime.functionProbe = recorder
        if ("rng" in tags) {
            val rng = assembled.session.store.randProvider as? Sfmt19937
                ?: throw CliError("会话 RNG 不是 Sfmt19937，无法挂出号观察口")
            rng.drawProbe = recorder
        }
        if ("var" in tags) assembled.session.store.writeProbe = recorder

        val fnName = callParsed.positional.firstOrNull()
            ?: throw CliError("转发段缺函数名: log ... -- call <FN> [--args ...]")
        val (intArgs, strArgs) = CmdCall.parseArgs(callParsed.value("--args"))
        val code = try {
            CmdCall.invokeAndReport(assembled, fnName, intArgs, strArgs, prefix, out)
        } finally {
            recorder.close()
        }
        out.println("--- log summary ---")
        out.println("events=${recorder.count}")
        out.println("truncated=${recorder.truncated}")
        out.println("out=$outPath")
        return code
    }

    /** `--out` 缺省：`<work>/dbg-log.jsonl`（与 last-error.json / snapshot.dbg 同一 --work 口径）。 */
    private fun defaultLogPath(a: Args): String =
        Path.of(a.valueOr("--work", defaultWorkDir())).resolve("dbg-log.jsonl").toString()

    private fun defaultWorkDir(): String =
        Path.of(System.getProperty("java.io.tmpdir"), "eratw-vmdbg").toString()
}

/**
 * 事件记录器：[FunctionProbe] / [RngProbe] / [VarWriteProbe] 三面合体（未选 tag 不挂对应
 * 接口，见 [CmdLog]）。行格式 `{"seq":n,"tag":"...","event":"...","payload":{...}}`；
 * JSON 手拼（payload 带嵌套对象与数字数组，Jsonl.obj 只出平面对象；字符串转义仍复用
 * [Jsonl.escape]）。
 */
internal class LogRecorder(
    outPath: Path,
    private val max: Long,
    private val tags: Set<String>,
    watch: List<String>,
    private val store: com.eratw.port.core.vars.VariableStore,
) : FunctionProbe, RngProbe, VarWriteProbe {

    /** 监视名单：每个名字的原形 + 大写形同入集（VarWriteProbe KDoc 的零分配 miss 形态）。 */
    private val watchSet = HashSet<String>()

    private val writer = Files.newBufferedWriter(outPath, Charsets.UTF_8)

    /** 已发出的事件数（--max 的截断计数）。 */
    var count: Long = 0L
        private set

    /** 是否因 [max] 截断（true = 有事件被丢弃）。 */
    var truncated: Boolean = false
        private set

    private var seq = 0L

    init {
        for (n in watch) {
            watchSet.add(n)
            watchSet.add(n.uppercase())
        }
    }

    fun close() {
        runCatching { writer.flush() }
        runCatching { writer.close() }
    }

    // ------------------------------------------------------------------
    // event
    // ------------------------------------------------------------------

    override fun onFunctionEnter(meta: com.eratw.port.core.proc.FuncMeta?) {
        emit("event", "func-enter", funcPayload(meta))
    }

    override fun onFunctionExit(meta: com.eratw.port.core.proc.FuncMeta?, outcome: FunctionExitKind) {
        emit("event", "func-exit", funcPayload(meta) + ",\"outcome\":\"" + outcome.name + "\"")
    }

    private fun funcPayload(meta: com.eratw.port.core.proc.FuncMeta?): String =
        if (meta == null) {
            "\"fn\":null,\"key\":null,\"index\":null"
        } else {
            "\"fn\":\"" + Jsonl.escape(meta.name) + "\",\"key\":\"" + Jsonl.escape(meta.key) +
                "\",\"index\":" + meta.index
        }

    // ------------------------------------------------------------------
    // rng
    // ------------------------------------------------------------------

    override fun onDraw(value: Long, seq: Long) {
        emit("rng", "draw", "\"value\":$value,\"seq\":$seq")
    }

    // ------------------------------------------------------------------
    // var
    // ------------------------------------------------------------------

    override fun watches(name: String): Boolean = name in watchSet

    override fun onWriteInt(name: String, indices: LongArray, value: Long) {
        emit("var", "write-int", varPayload(name, indices, oldStr = readAnyOld(name, indices), new = value.toString()))
    }

    override fun onWriteStr(name: String, indices: LongArray, value: String) {
        emit("var", "write-str", varPayload(name, indices, oldStr = readAnyOld(name, indices), new = value))
    }

    /** old 值回读（先整数后字符串，与 CmdSet 同一套回退）；不可读 = null。 */
    private fun readAnyOld(name: String, indices: LongArray): String? =
        try {
            store.readInt(name, indices).toString()
        } catch (_: com.eratw.port.core.err.CodeEE) {
            try {
                store.readStr(name, indices)
            } catch (_: com.eratw.port.core.err.CodeEE) {
                null
            }
        }

    private fun varPayload(name: String, indices: LongArray, oldStr: String?, new: String): String {
        val fn: String = try {
            store.currentFunction ?: ""
        } catch (_: Throwable) {
            ""
        }
        return "\"name\":\"" + Jsonl.escape(name) + "\",\"idx\":[" + indices.joinToString(",") + "]" +
            ",\"old\":" + (if (oldStr == null) "null" else "\"" + Jsonl.escape(oldStr) + "\"") +
            ",\"new\":\"" + Jsonl.escape(new) + "\"" +
            ",\"fn\":" + (if (fn.isEmpty()) "null" else "\"" + Jsonl.escape(fn) + "\"")
    }

    // ------------------------------------------------------------------
    // 行发射
    // ------------------------------------------------------------------

    /** 发一行；[max] 截断（置 truncated 并停止记录）返回 false。 */
    private fun emit(tag: String, event: String, payload: String): Boolean {
        if (count >= max) {
            truncated = true
            return false
        }
        count++
        seq++
        writer.write("{\"seq\":$seq,\"tag\":\"$tag\",\"event\":\"$event\",\"payload\":{$payload}}")
        writer.write("\n")
        if (seq % CmdLog.FLUSH_EVERY == 0L) writer.flush()
        return true
    }
}
