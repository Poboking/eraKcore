package com.eratw.port.vmdbg

import com.eratw.port.core.code.CallStackOverflow
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.FuncMeta
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 单函数子命令：call / where / var / vars / disasm（CONTRACT-DBG1 路2 W1 集合）。
 * trace 依赖路1 探针，单独成文件（Trace.kt）。
 */
object CmdCall {

    /**
     * `call <函数名> [--args 0,1,...] [--steps N]`。
     *
     * `--args` 的每个逗号分段：能解析成 Long 进 intArgs（桶内保序），否则进 strArgs ——
     * 两桶独立保序，交叉顺序不保留（callFunction 的形参按 `(intArgs, strArgs)` 两桶绑定，
     * 混合顺序本就无从表达；KDoc 明示给 agent）。
     *
     * NOT_FOUND 预检：vmdbg 自持的 [Assembled.metadata] 里没有该 key（大写归一）时，
     * 不进运行期直接报 NOT_FOUND（callFunction 对未知名同样抛 CodeEE，预检只是把状态
     * 归类得更准、退出码区分开）。事件名/方法名照常进运行期，由 callFunction 的
     * 原版口径报错（ProcessRuntime.kt:1415-1424：事件名/方法名 CodeEE）。
     */
    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val name = a.positional.getOrNull(1) ?: throw CliError("用法: call <函数名> [--args 0,1,...] [--steps N]")
        val assembled = Assembler.assemble(a)
        val (intArgs, strArgs) = parseArgs(a.value("--args"))
        return invokeAndReport(assembled, name, intArgs, strArgs, a, out = out)
    }

    /** `--args` 分桶：数字 → int 桶，其余 → str 桶。 */
    fun parseArgs(raw: String?): Pair<LongArray, Array<String>> {
        if (raw.isNullOrEmpty()) return LongArray(0) to emptyArray()
        val ints = ArrayList<Long>()
        val strs = ArrayList<String>()
        for (tok in raw.split(',')) {
            val t = tok.trim()
            if (t.isEmpty()) continue
            t.toLongOrNull()?.let { ints.add(it) } ?: strs.add(t)
        }
        return ints.toLongArray() to strs.toTypedArray()
    }

    /**
     * call/trace 共用的「调用 + 报告」主体：执行、分类、输出 key=value 段、写 last-error.json、
     * 给出退出码。[extraOutput] 是 trace 的轨迹落点（Trace.kt 装好探针后传「输出环形缓冲」
     * 的闭包；call 恒 null —— 本文件不引用任何探针类型，保持无探针时全模块可编译）。
     */
    fun invokeAndReport(
        assembled: Assembler.Assembled,
        name: String,
        intArgs: LongArray,
        strArgs: Array<String>,
        a: Args,
        out: PrintStream,
        extraOutput: (() -> Unit)? = null,
    ): Int {
        val rt = assembled.session.runtime
        val keyU = name.uppercase()
        val meta: FuncMeta? = assembled.metadata.functions.firstOrNull { it.key == keyU }
        if (meta == null) {
            out.println("status=NOT_FOUND")
            out.println("target=$name")
            out.println("message=函数表中没有该函数（大写归一后未命中 metadata.functions）")
            return 2
        }
        val t0 = System.nanoTime()
        var error: Throwable? = null
        try {
            rt.callFunction(meta.key, intArgs, strArgs)
        } catch (t: Throwable) {
            error = t
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
        val steps = StepReader.executedSteps(rt)
        val status = Status.classify(error)

        out.println("status=$status")
        out.println("target=${meta.name}")
        out.println("location=${Status.location(error)}")
        out.println("message=${error?.message ?: ""}")
        out.println("steps=${steps ?: -1L}")
        out.println("elapsedMs=$elapsedMs")
        if (error is StepLimitExceeded) out.println("callChain=${error.callChain ?: ""}")

        val tail = assembled.session.console.snapshot()
            .takeLast(Cli.CONSOLE_TAIL_LINES)
            .map { assembled.session.console.textOf(it) }
        out.println("--- console tail (${tail.size} lines) ---")
        for (line in tail) out.println(line)

        if (extraOutput != null) extraOutput()
        writeLastError(a, status, error, steps, elapsedMs)
        return if (status == "PASS") 0 else if (status == "NOT_FOUND") 2 else 3
    }

    /**
     * `where` 的数据源：把最近一次错误现场落 `<work>/last-error.json`（无错误也写，
     * status=PASS 覆盖旧现场）。写临时文件后原子改名，防 where 读到半行。
     */
    private fun writeLastError(
        a: Args,
        status: String,
        error: Throwable?,
        steps: Long?,
        elapsedMs: Long,
    ) {
        val work = Path.of(a.valueOr("--work", defaultWorkDir())).toAbsolutePath().normalize()
        val tmp = work.resolve("last-error.json.tmp")
        val target = work.resolve("last-error.json")
        val json = Jsonl.obj(
            listOf(
                "status" to status,
                "funcName" to (error as? CodeEE)?.funcName,
                "line" to ((error as? CodeEE)?.line ?: -1),
                "pc" to ((error as? CodeEE)?.pc ?: -1),
                "callChain" to (error as? StepLimitExceeded)?.callChain,
                "message" to (error?.message ?: ""),
                "steps" to (steps ?: -1L),
                "elapsedMs" to elapsedMs,
            ),
        )
        Files.write(tmp, (json + "\n").toByteArray(Charsets.UTF_8))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun defaultWorkDir(): String =
        Path.of(System.getProperty("java.io.tmpdir"), "eratw-vmdbg").toString()
}

/** 运行结果的状态分类与位置提取（call / trace / sweep 共用）。 */
object Status {

    /**
     * Throwable → 状态串。StepLimitExceeded / CallStackOverflow 都是 CodeEE 子类
     * （Errors.kt:25/:44），必须**先**于 CodeEE 判断；WallLimitExceeded 是普通
     * RuntimeException（故意不继承 CodeEE，防 TRYC 吞掉），最先判。
     */
    fun classify(t: Throwable?): String = when (t) {
        null -> "PASS"
        is WallLimitExceeded -> "WALL_LIMIT"
        is StepLimitExceeded -> "STEP_LIMIT"
        is CallStackOverflow -> "STACK_OVERFLOW"
        is CodeEE -> "CODEEE"
        else -> "ERROR"
    }

    /** `函数名:行号:pc`（CodeEE 结构化字段，err/CodeEE.kt:20-25）；无现场 = `-`。 */
    fun location(t: Throwable?): String {
        val e = t as? CodeEE ?: return "-"
        return "${e.funcName ?: "-"}:${e.line}:${e.pc}"
    }
}

/** `where`：读 call/trace 落下的 last-error.json，输出最近一次错误的现场。 */
object CmdWhere {

    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val work = Path.of(a.valueOr("--work", defaultWorkDir())).toAbsolutePath().normalize()
        val target = work.resolve("last-error.json")
        if (!Files.isRegularFile(target)) {
            out.println("status=NO_ERROR")
            out.println("message=尚无 call/trace 写下的错误现场（$target 不存在）")
            return 0
        }
        val rec = Jsonl.parseLine(Files.readString(target, Charsets.UTF_8).lineSequence().firstOrNull() ?: "")
            ?: throw CliError("last-error.json 不是可解析的平面对象: $target")
        for (k in listOf("status", "funcName", "line", "pc", "callChain", "message", "steps", "elapsedMs")) {
            out.println("$k=${rec[k] ?: ""}")
        }
        return 0
    }

    private fun defaultWorkDir(): String =
        Path.of(System.getProperty("java.io.tmpdir"), "eratw-vmdbg").toString()
}

/** `var <名字>[i,j]`：读单变量（值取指定下标，缺省 [0]）。 */
object CmdVar {

    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val spec = a.positional.getOrNull(1) ?: throw CliError("用法: var <名字>[i,j]")
        val assembled = Assembler.assemble(a)
        val store = assembled.session.store
        // 名字[下标表]：下标为逗号分隔整数；缺省 [0]（CONTRACT-DBG1 路2：值取 [0] 或指定下标）
        val open = spec.indexOf('[')
        val name = if (open >= 0) spec.substring(0, open) else spec
        val indices: LongArray = if (open >= 0) {
            val close = spec.indexOf(']', open)
            if (close < 0) throw CliError("var 下标缺闭括号: $spec")
            spec.substring(open + 1, close).split(',').mapNotNull { it.trim().toLongOrNull() }.toLongArray()
        } else {
            longArrayOf(0L)
        }
        var value: String? = null
        var readError: Throwable? = null
        try {
            value = store.readInt(name, indices).toString()
        } catch (e: CodeEE) {
            readError = e
        }
        if (value == null) {
            try {
                value = store.readStr(name, indices)
            } catch (e: CodeEE) {
                readError = e
            }
        }
        if (value == null) {
            out.println("status=NOT_FOUND")
            out.println("message=${readError?.message ?: "读不到 $spec"}")
            return 2
        }
        out.println("status=PASS")
        val idx = indices.joinToString(",", prefix = "[", postfix = "]")
        out.println("${name.uppercase()}$idx=$value")
        return 0
    }
}

/** `vars <glob>`：枚举变量名（registry.all + allUserVars，glob 过滤）。 */
object CmdVars {

    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val glob = a.positional.getOrNull(1) ?: "*"
        val assembled = Assembler.assemble(a)
        val store = assembled.session.store
        val rx = globToRegex(glob)
        val names = LinkedHashSet<String>()
        for (d in store.registry.all()) if (rx.matches(d.name.uppercase())) names.add(d.name.uppercase())
        for (u in store.allUserVars()) if (rx.matches(u.spec.name.uppercase())) names.add(u.spec.name.uppercase())
        for (n in names.sorted()) out.println(n)
        return 0
    }

    /** glob → regex：星号 = 任意串、问号 = 单字符，其余字符字面匹配（对大写归一名）。 */
    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob.uppercase()) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return Regex(sb.toString())
    }
}

/** `disasm <pc> [-n 条数]`：反汇编指令区间（op/a/b/c/lineOf + Disassembler 文本）。 */
object CmdDisasm {

    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val pcRaw = a.positional.getOrNull(1) ?: throw CliError("用法: disasm <pc> [-n 条数]")
        val pc = pcRaw.toIntOrNull() ?: throw CliError("pc 不是整数: $pcRaw")
        val count = a.longOr("-n", 16L).toInt()
        if (count <= 0) throw CliError("-n 必须为正整数")
        val assembled = Assembler.assemble(a)
        val program = assembled.program
        if (pc < 0 || pc >= program.instructionCount) {
            throw CliError("pc 越界: $pc（指令总数 ${program.instructionCount}）")
        }
        out.println("pc=$pc instructionCount=${program.instructionCount} count=$count")
        for (row in DisasmSlice.slice(program, pc, count)) {
            out.println(
                "pc=${row.pc} line=${row.line} words=[${row.words[0]},${row.words[1]},${row.words[2]},${row.words[3]}] ${row.text}",
            )
        }
        return 0
    }
}
