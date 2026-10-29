package com.eratw.port.perf

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.NullTerm
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.save.SaveCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 游玩卡顿（点选项后 3~4 秒）的桌面取证装置：真产物 + 真存档，量「同样多的 VM 指令要跑多久」。
 *
 * 口径：`CHARA_MOVEMENT` 是每回合（`EVENTTRAIN`）都会跑的重负载（BOARD 红 K 读数
 * 12,080,416 条指令），用它代表「点一下之后脚本侧要算多久」。步数闸故意收紧到
 * [CAP]（默认 6,000,000），只量**同样工作量**的墙钟，不受不同产物总步数漂移影响；
 * 撞闸是预期结局，不算失败。
 *
 * 读数：`ns/instr` 是本装置唯一的可比量 —— 优化前后用同一个 CAP 对拍。
 *
 * 语料/产物由环境变量给出（`ERATW_GAMEDIR` / `ERATW_ERBC_DIR`），缺失即跳过。
 * `ERATW_PERF_CAP` 可覆盖步数闸。
 */
class TurnLatencyProbeTest {

    @Test
    fun `量同样多指令的墙钟`() {
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(gameRoot != null && erbcDir != null && Files.exists(save), "缺环境变量或存档，跳过")

        val cap = System.getenv("ERATW_PERF_CAP")?.toLongOrNull() ?: CAP
        val t0 = System.nanoTime()
        val program = ProgramLoader.loadMapped(erbcDir!!.resolve("program.erbc")) { }
        val metadata = BootMetadata.read(erbcDir.resolve("program.meta"))
        val loadMs = (System.nanoTime() - t0) / 1_000_000L
        val session = GameSession(
            gameRoot = gameRoot!!,
            program = program,
            metadata = metadata,
            maxStepsPerRun = cap,
            randSeed = 20260226L,
        )
        val data = SaveCodec.read(Files.readAllBytes(save), session.store.registry, SaveFileKind.NORMAL)
        data.applyTo(session.store, restoreCharacters = true, setLastLoad = true)
        val charaNum = session.store.readInt("CHARANUM", LongArray(0))

        val rounds = 10
        val out = ArrayList<String>(rounds)
        val stepsOut = ArrayList<String>(rounds)
        val allocOut = ArrayList<String>(rounds)
        var aborted = 0
        // TEMP(perf-probe)：操作码直方图。回答「1208 万条指令里各操作码各占多少」。
        val hist = IntArray(256)
        runCatching { vmOf(session.runtime)?.javaClass?.getField("opHistogram")?.set(vmOf(session.runtime), hist) }
        // 每轮**分配字节**：这个量对机器负载/GC/JIT 时机完全免疫（同样的工作量分配同样的
        // 字节），是比墙钟硬得多的判据 —— 本机墙钟逐轮跨 ±50%，分配量却能对到 0.1%。
        val mx = java.lang.management.ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        repeat(rounds) {
            val b0 = mx.getThreadAllocatedBytes(tid)
            val s = System.nanoTime()
            val reached = try {
                session.runtime.callFunction("CHARA_MOVEMENT")
                -1L
            } catch (e: StepLimitExceeded) {
                e.steps
            } catch (e: Throwable) {
                // `CHARA_MOVEMENT` 单独重复调用会留下 GMF 调用栈（语料
                // `DLC/GENSOKYO_MAP_FIX/幻想乡地图重修.ERB:5-12` 的
                // `GMF_METHOD_STACK_PUSH` 在栈满时抛 `GMF_FATAL_ERROR`）——
                // 那是「脱离 EVENTTRAIN 单独跑」的固有副作用，与性能无关。
                // 本轮已跑的指令数照旧从 executedSteps 取，读数仍然有效。
                aborted++
                -1L
            }
            val ns = System.nanoTime() - s
            val bytes = mx.getThreadAllocatedBytes(tid) - b0
            val steps = if (reached > 0) reached else executedSteps(session.runtime)
            if (steps > 0) {
                out += "${"%.1f".format(ns.toDouble() / steps)}"
                // 逐轮步数必须一起落出来：不同轮的「中止时点」不同（`aborted` 轮执行到
                // 抛错点为止），只比 ns/instr 会把「跑得少」误读成「跑得快」。
                stepsOut += steps.toString()
                // 每百万指令分配多少 MB：跨运行可比，与机器负载无关。
                allocOut += "%.2f".format(bytes / 1_048_576.0 * 1_000_000.0 / steps)
            }
        }
        // 前 3 轮是 JIT 预热（冷启动时 `ns/instr` 逐轮下降），不进稳态读数。
        val nsPerInstr = out.drop(3).map { it.toDouble() }.sorted()
        println(
            "PERF load=${loadMs}ms CHARANUM=$charaNum cap=$cap aborted=$aborted " +
                "每轮ns=${out.joinToString(" ")} | 每轮steps=${stepsOut.joinToString(" ")} | " +
                "每轮分配MB/百万指令=${allocOut.joinToString(" ")} | " +
                "稳态min=${nsPerInstr.first()} median=${nsPerInstr[nsPerInstr.size / 2]}",
        )
        dumpHistogram(hist)
        session.close()
    }

    /**
     * TEMP(perf-probe)：按**动态**执行次数降序打印操作码分布。
     *
     * 静态分布会骗人（热循环体在指令流里只出现一次却执行百万次），所以必须在主循环里
     * 逐指令累加。`<语句>` 一行是 29..197 区间（169 条真实 ERB 语句）的合计 ——
     * 它们都走「marker → 语句层第二次 dispatch」这条最贵的路径。
     */
    private fun dumpHistogram(hist: IntArray) {
        var total = 0L
        for (v in hist) total += v
        if (total <= 0L) {
            println("HIST 空（探针未挂上，检查 opHistogram 字段是否可见）")
            return
        }
        var stmtTotal = 0L
        val rows = ArrayList<Triple<Int, Int, Double>>()
        for (op in hist.indices) {
            val n = hist[op]
            if (n <= 0) continue
            if (op in Opcode.INTERNAL_COUNT..Opcode.MAX_STATEMENT_OPCODE) stmtTotal += n
            rows += Triple(op, n, 100.0 * n / total)
        }
        rows.sortByDescending { it.second }
        val sb = StringBuilder("HIST total=$total 条")
        for ((op, n, pct) in rows.take(28)) {
            sb.append(" | ").append(Opcode.nameOf(op)).append('=')
                .append("%.2f%%".format(pct)).append('(').append(n).append(')')
        }
        sb.append(" | <语句合计>=").append("%.2f%%".format(100.0 * stmtTotal / total))
        println(sb)
    }

    /**
     * 表达式池分类：回答「W3 降级器值不值得做」。
     *
     * 判据链：`_EVAL_INT`/`_EVAL_STR` 的 `a` 是表达式池下标 ⇒ 先按**静态出现次数**给每条
     * 被引用的表达式加权（一条 `_EVAL` 指令在环体里被执行百万次，权重远高于只在初始化
     * 里跑一次的），再看它的根节点是什么。
     *
     * 为什么这个分布决定 W3 的成败：降级器只能把「纯变量/常量/算术比较」的树拆成
     * 栈码或三地址；一旦根是 `FuncCall`（`GETNUM`/`GETVAR`/用户函数）或
     * `KeywordIndexArg`（`CFLAG:LOCAL:0` 这种解析期才知道下标的写法），降级要么做不到，
     * 要么只是把一次虚调用换成多次派发 —— 那正是栈码 ISA 已就位却没人敢发射的原因。
     */
    @Test
    fun `分类表达式池`() {
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        assumeTrue(erbcDir != null, "缺 ERATW_ERBC_DIR，跳过")
        val program = ProgramLoader.loadMapped(erbcDir!!.resolve("program.erbc")) { }
        val src = program.codeSource
        val n = program.instructionCount

        // ① 静态扫描：每条 `_EVAL_*` 指向哪个表达式，各被引用多少次。
        val refs = IntArray(program.exprCount)
        var evalIntOps = 0
        var evalStrOps = 0
        for (pc in 0 until n) {
            val w = pc * 4
            when (src.word(w)) {
                Opcode._EVAL_INT -> { refs[src.word(w + 1)]++; evalIntOps++ }
                Opcode._EVAL_STR -> { refs[src.word(w + 1)]++; evalStrOps++ }
            }
        }

        // ② 按静态引用次数加权分类根节点。
        val byKind = LinkedHashMap<String, Int>()
        var lowerable = 0
        var referenced = 0
        var nodes = 0L
        for (idx in refs.indices) {
            val wgt = refs[idx]
            if (wgt == 0) continue
            referenced += wgt
            val e = program.expr(idx)
            nodes += countNodes(e)
            byKind.merge(kindOf(e), wgt, Int::plus)
            if (isLowerable(e)) lowerable += wgt
        }

        fun pct(v: Int) = "%.1f%%".format(100.0 * v / referenced.coerceAtLeast(1))
        val sb = StringBuilder("EXPR 静态 _EVAL_INT=$evalIntOps 条 / _EVAL_STR=$evalStrOps 条；" +
            "被引用表达式 $referenced 次，平均节点数 ${"%.2f".format(nodes.toDouble() / referenced)}")
        for ((k, v) in byKind.entries.sortedByDescending { it.value }) {
            sb.append(" | ").append(k).append('=').append(pct(v))
        }
        sb.append(" | 可三地址降级=").append(pct(lowerable))
        println(sb)
    }

    /**
     * 邻接对：回答「谁喂给谁」。
     *
     * 直方图只给了每个操作码的**条数**，但条数相等不等于配对（`_EVAL_INT` 23.41% 与
     * `SET` 23.60% 差 0.2%，看着像 1:1，也可能是巧合）。要判断「能不能融合」「能不能
     * 省掉中间槽」，必须知道指令流里真正相邻的是什么。
     *
     * 输出三张表：① 每条 `SET` marker 的**前一条**是什么（`_EVAL_INT` 还是别的）；
     * ② 每条 `_EVAL_INT` 的**后一条**是什么（谁消费它写下的槽）；③ 每条
     * `_JUMP_IF_FALSE` 的前一条是什么（比较结果是不是刚由 `_LT`/`_EQ` 写出来）。
     */
    @Test
    fun `邻接对`() {
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        assumeTrue(erbcDir != null, "缺 ERATW_ERBC_DIR，跳过")
        val program = ProgramLoader.loadMapped(erbcDir!!.resolve("program.erbc")) { }
        val src = program.codeSource
        val n = program.instructionCount
        fun opAt(pc: Int): Int = if (pc < 0 || pc >= n) -1 else src.word(pc * 4)

        fun dump(title: String, cur: Int, dir: Int, top: Int = 5) {
            val m = HashMap<Int, Int>()
            var total = 0
            for (pc in 0 until n) {
                if (opAt(pc) != cur) continue
                total++
                m.merge(opAt(pc + dir), 1, Int::plus)
            }
            if (total == 0) { println("ADJ $title: 0 条"); return }
            val rows = m.entries.sortedByDescending { it.value }.take(top)
            println("ADJ $title（共 $total 条）: " + rows.joinToString(" | ") {
                "${Opcode.nameOf(it.key)}=${"%.1f%%".format(100.0 * it.value / total)}"
            })
        }

        dump("SET 的前一条", Opcode.SET, -1)
        dump("SET 的后一条", Opcode.SET, +1)
        dump("_EVAL_INT 的后一条", Opcode._EVAL_INT, +1)
        dump("_EVAL_INT 的前一条", Opcode._EVAL_INT, -1)
        dump("_JUMP_IF_FALSE 的前一条", Opcode._JUMP_IF_FALSE, -1)
        dump("_JUMP 的前一条", Opcode._JUMP, -1)
        dump("_LT 的前一条", Opcode._LT, -1)
        dump("_MOV_INT 的前一条", Opcode._MOV_INT, -1)

        // 「融合 `_EVAL_INT` + 后一条 `_JUMP_IF_FALSE`」的可行性判据（PERF 2026-10-27）：
        // 要省掉的不只是「一次取指 + 分派」，而是**整轮循环头**（pc 判界、steps++、
        // maxSteps 比较、lastPc、shl、跨页判断、四次数组读、hist 判空、switch）。
        // 若 `_EVAL_INT` 写的槽 b 与 `_JUMP_IF_FALSE` 读的槽 a 相同，还能连槽读写一起省掉。
        var evals = 0
        var followJif = 0
        var sameSlot = 0
        for (pc in 0 until n) {
            if (opAt(pc) != Opcode._EVAL_INT) continue
            evals++
            if (opAt(pc + 1) != Opcode._JUMP_IF_FALSE) continue
            followJif++
            if (src.word(pc * 4 + 2) == src.word((pc + 1) * 4 + 1)) sameSlot++
        }
        if (evals > 0) {
            println(
                "FUSE _EVAL_INT 静态 $evals 条 | 后一条是 _JUMP_IF_FALSE 的 $followJif 条" +
                    "（${"%.1f".format(100.0 * followJif / evals)}%）" +
                    " | 其中 b==a（槽号相同）$sameSlot 条" +
                    "（${"%.1f".format(100.0 * sameSlot / followJif.coerceAtLeast(1))}%）",
            )
        }
    }

    /**
     * 任意构造期开关的**同进程交替 A/B**（缺省测 `eratw.perf.noLocalSlots`）。
     *
     * 为什么不用「改代码 → 重编 → 再测」：跨窗口比较在本项目已被证伪（`CONTRACT-T2`
     * 的 T2-62 回执第 4 条：单轮内 7 个稳态样本自身跨 17%），而机器上常有别的构建在抢核。
     * 这里两条腿在**同一个 JVM、同一份产物、同一份存档**上交替跑，唯一的差别是被测开关
     * （每条腿建 `GameSession` 之前 `System.setProperty` 一次 —— 开关都是构造期读的，
     * 所以逐腿生效）。
     *
     * 门槛：`ERATW_PERF_AB=<对数>`（不设即跳过，不污染常规闸）。奇数对先跑 ON、
     * 偶数对先跑 OFF，抵消热漂移。**每轮步数必须两侧逐轮相同**才算可比 —— 本用例把
     * `每轮steps=` 与 `每轮分配MB` 一起打出来，先核这个再比 `ns/instr`。
     *
     * `ERATW_PERF_AB_KEY=<系统属性名>` 换被测开关（如 `eratw.perf.noEvalJumpFuse`）。
     */
    @Test
    fun `构造期开关 同进程交替 A_B`() {
        val pairs = System.getenv("ERATW_PERF_AB")?.toIntOrNull() ?: 0
        assumeTrue(pairs > 0, "未设 ERATW_PERF_AB=<对数>，跳过")
        val key = System.getenv("ERATW_PERF_AB_KEY")?.takeIf { it.isNotBlank() }
            ?: "eratw.perf.noLocalSlots"
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(gameRoot != null && erbcDir != null && Files.exists(save), "缺环境变量或存档，跳过")
        val cap = System.getenv("ERATW_PERF_CAP")?.toLongOrNull() ?: CAP
        println("AB key=$key")

        val steady = HashMap<String, List<Double>>()
        for (p in 1..pairs) {
            // 奇偶交替顺序：抵消「后跑的那条腿因为 JIT/缓存变快」的系统偏差。
            val legs = if (p % 2 == 1) listOf(false, true) else listOf(true, false)
            for (off in legs) {
                System.setProperty(key, off.toString())
                val tag = "P$p-${if (off) "OFF" else "ON"}"
                steady[tag] = runLeg(tag, gameRoot!!, erbcDir!!, save, cap)
            }
        }
        System.clearProperty(key)
        val allOn = ArrayList<Double>()
        val allOff = ArrayList<Double>()
        for (p in 1..pairs) {
            val on = steady["P$p-ON"] ?: continue
            val off = steady["P$p-OFF"] ?: continue
            if (on.isEmpty() || off.isEmpty()) continue
            allOn += on
            allOff += off
            println(
                "AB P$p ON(n=${on.size}) min=${on.first()} max=${on.last()} | " +
                    "OFF(n=${off.size}) min=${off.first()} max=${off.last()} | " +
                    "min比=${"%.3f".format(off.first() / on.first())}x " +
                    "max比=${"%.3f".format(off.last() / on.last())}x",
            )
        }
        if (allOn.isNotEmpty() && allOff.isNotEmpty()) {
            val on = allOn.sorted()
            val off = allOff.sorted()
            println(
                "AB 合计 n=${on.size}/${off.size} | ON min=${on.first()} median=${on[on.size / 2]} | " +
                    "OFF min=${off.first()} median=${off[off.size / 2]} | " +
                    "min比=${"%.3f".format(off.first() / on.first())}x " +
                    "median比=${"%.3f".format(off[off.size / 2] / on[on.size / 2])}x",
            )
        }
    }

    /**
     * 跑一条腿：独立加载产物 + 独立 `GameSession`（`close()` 会释放 mmap，
     * 所以每条腿都得重新 `loadMapped`），返回稳态（去掉前 3 轮预热）的 `ns/instr` 升序表。
     */
    private fun runLeg(
        tag: String,
        gameRoot: Path,
        erbcDir: Path,
        save: Path,
        cap: Long,
    ): List<Double> {
        val program = ProgramLoader.loadMapped(erbcDir.resolve("program.erbc")) { }
        val metadata = BootMetadata.read(erbcDir.resolve("program.meta"))
        val session = GameSession(
            gameRoot = gameRoot,
            program = program,
            metadata = metadata,
            maxStepsPerRun = cap,
            randSeed = 20260226L,
        )
        try {
            val data = SaveCodec.read(Files.readAllBytes(save), session.store.registry, SaveFileKind.NORMAL)
            data.applyTo(session.store, restoreCharacters = true, setLastLoad = true)
            val rounds = 4
            val out = ArrayList<String>(rounds)
            val stepsOut = ArrayList<String>(rounds)
            val allocOut = ArrayList<String>(rounds)
            val mx = java.lang.management.ManagementFactory.getThreadMXBean()
                as com.sun.management.ThreadMXBean
            val tid = Thread.currentThread().id
            repeat(rounds) {
                val b0 = mx.getThreadAllocatedBytes(tid)
                val s = System.nanoTime()
                val reached = try {
                    session.runtime.callFunction("CHARA_MOVEMENT")
                    -1L
                } catch (e: StepLimitExceeded) {
                    e.steps
                } catch (e: Throwable) {
                    -1L
                }
                val ns = System.nanoTime() - s
                val bytes = mx.getThreadAllocatedBytes(tid) - b0
                val steps = if (reached > 0) reached else executedSteps(session.runtime)
                if (steps > 0) {
                    out += "%.1f".format(ns.toDouble() / steps)
                    stepsOut += steps.toString()
                    allocOut += "%.2f".format(bytes / 1_048_576.0 * 1_000_000.0 / steps)
                }
            }
            println(
                "LEG $tag 每轮ns=${out.joinToString(" ")} | 每轮steps=${stepsOut.joinToString(" ")} | " +
                    "每轮分配MB/百万指令=${allocOut.joinToString(" ")}",
            )
            // 只留**跑满步数闸**的轮次：`CHARA_MOVEMENT` 连续重复调用会在第 5 次左右
            // 因 `GMF` 调用栈满而抛错，之后每轮只剩几十步 —— 那些轮的 ns/instr 被
            // 「固定开销 / 极少步数」放大到几千（实测 6500 vs 稳定态 210），
            // 一旦混进 median 就会把结论翻向任意一侧。**这是上一轮 A/B「方向不一致」的根因。**
            val valid = out.filterIndexed { i, _ -> stepsOut[i].toLong() > cap }
            return valid.drop(2).map { it.toDouble() }.sorted()
        } finally {
            session.close()
        }
    }

    /** 根节点类别名（用于分布表）。 */
    private fun kindOf(e: Expr): String = when (e) {        is IntLiteral -> "常量I"
        is StrLiteral -> "常量S"
        is NullTerm -> "空"
        is VarRef -> if (e.indices.isEmpty()) "变量0下标" else "变量带下标"
        is Binary -> "二元"
        is Unary, is UnaryAfter -> "一元"
        is Ternary -> "三目"
        is FuncCall -> "函数调用"
        is KeywordIndexArg -> "关键字下标"
        else -> e.javaClass.simpleName
    }

    /** 子树节点总数（递归，用于估降级后的指令条数）。 */
    private fun countNodes(e: Expr): Int = when (e) {
        is VarRef -> 1 + e.indices.sumOf { countNodes(it) }
        is Binary -> 1 + countNodes(e.left) + countNodes(e.right)
        is Unary -> 1 + countNodes(e.operand)
        is UnaryAfter -> 1 + countNodes(e.operand)
        is Ternary -> 1 + countNodes(e.cond) + countNodes(e.ifTrue) + countNodes(e.ifFalse)
        is FuncCall -> 1 + e.args.sumOf { it?.let(::countNodes) ?: 0 }
        is KeywordIndexArg -> 1 + countNodes(e.expr)
        else -> 1
    }

    /**
     * 「能不能整棵降级成三地址/栈码」的**保守**判据：只放行纯算术/比较，
     * 且子树里没有任何需要运行期名字解析或副作用的东西。
     */
    private fun isLowerable(e: Expr): Boolean = when (e) {
        is IntLiteral -> true
        is VarRef -> !e.isNoArg && e.indices.all { isLowerable(it) }
        is Binary -> isLowerable(e.left) && isLowerable(e.right) &&
            e.left.operandType != OperandType.STR && e.right.operandType != OperandType.STR
        is Unary -> isLowerable(e.operand)
        else -> false
    }

    private fun vmOf(rt: ProcessRuntime): Any? = runCatching {
        val f = ProcessRuntime::class.java.getDeclaredField("vm")
        f.isAccessible = true
        f.get(rt)
    }.getOrNull()

    private fun executedSteps(rt: ProcessRuntime): Long = runCatching {
        val vm = vmOf(rt) ?: return@runCatching 0L
        (vm.javaClass.getMethod("getExecutedSteps").invoke(vm) as Number).toLong()
    }.getOrDefault(0L)

    private companion object {
        const val CAP = 6_000_000L
    }
}
