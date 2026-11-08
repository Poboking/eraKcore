package com.eratw.port.e2e.replay

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.InputSinkClosedException
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.e2e.GoldenModel
import com.eratw.port.e2e.GoldenNormalizer
import com.eratw.port.e2e.GoldenPipeline
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 输入轨迹重放的驱动器：装配一个**生产** runtime + 生产 [QueueInputSink]，
 * 在第二条线程上按原版 UI 代理的纪律投喂，然后对拍控制台增量与变量。
 *
 * ## 为什么要两条线程
 * 本移植的等待是阻塞式：`QueueInputSink.waitInput`（QueueInputSink.kt:1053）与
 * `readAnyKey`（:987）在 VM 线程上 `Condition.await`，直到有提交入队或限时到点。
 * 所以驱动必须把「执行脚本」与「投喂提交」拆成两条线程 —— 形态模板就是既有
 * `BootPlayChain.Feeder`（BootPlayChain.kt:350-397）。
 *
 * ## 投喂纪律（逐条照 Feeder）
 * 1. **有等待且队列空**才投下一条（`isAwaiting && pendingCount == 0`）：队列里至多一条待发，
 *    「消费」与「完成一次等待」一一对应；
 * 2. 每条投完等 `pendingCount == 0`（被等待方吃掉）再投下一条；
 * 3. `pause <ms>` 只是本线程 sleep，不碰队列。
 *
 * ## 与基准的边界（写进交付回执）
 * - **不接键入回显**：原版 `doInputToEmueraProgram` 末尾的 `Print(str)`（EmueraConsole.cs:1280）
 *   属显示层，[QueueInputSink.onEchoInput] 缺省 null = 不产出行。重放装置只钉「等待 → 提交 →
 *   推进」这条生产通道，故 `expect-console` 里不含回显行。
 * - 每条用例**新建** console + runtime + [QueueInputSink]（契约 §3.1-5：用例之间零状态泄漏）。
 */
class ReplayRunner(private val model: GoldenModel) {

    /** 一条用例的执行结果；[failures] 为空即通过。 */
    data class Result(
        val case: ReplayCase,
        val failures: List<String>,
        val blocked: Boolean,
        val consoleDelta: List<String>,
        val vmError: String?,
    ) {
        val passed: Boolean get() = failures.isEmpty()
    }

    /**
     * 执行一条用例。
     *
     * 顺序：记 `logBefore`（装载期预置行数）→ 起 VM 线程跑 `callF(target)` →
     * 投喂线程按纪律投指令 → 指令耗尽后最多等 [DRAIN_MS] → 关 sink 并 join →
     * 控制台增量与变量对拍。
     *
     * 目标函数一律声明为 `#FUNCTION`，故用 `callF`（CALLF 家族）调用：方法帧掉尾**保留**
     * 函数体内最后一次 RESULT 写入，`expect-vars` 读到的才是脚本真写过的东西。普通函数
     * 掉尾会先合成 `RESULT = 0`（Process.cs:1059-1065；Kotlin 侧 ErbProgramCompiler.kt:363-372），
     * 显式 `RETURN` 则把 RESULT:0 覆写成返回值（FunctionIdentifier.cs:3265-3271）。
     * 调用方式不参与断言口径：两条路径都是同一台 VM 线程、同一个生产 sink。
     */
    fun run(case: ReplayCase): Result {
        val console = GoldenPipeline.newConsole()
        val sink = QueueInputSink()
        val rt: ProcessRuntime = GoldenPipeline.newRuntime(
            model, console, stepCap = STEP_CAP,
            inputSink = sink,
        )
        val logBefore = console.snapshot().size
        val failures = ArrayList<String>()
        val vmErrorRef = AtomicReference<Throwable?>(null)
        val vmDone = AtomicBoolean(false)
        val feederFailures = ArrayList<String>()

        val vm = Thread({
            try {
                rt.callF(case.target)
            } catch (_: InputSinkClosedException) {
                // 收尾路径：由本装置 close() 打断等待（阻塞用例与超时收口都走这条）。
            } catch (t: Throwable) {
                vmErrorRef.set(t)
            } finally {
                vmDone.set(true)
            }
        }, "replay-vm-${case.name}")

        val feeder = Thread({
            feed(case, sink, vmDone, feederFailures)
        }, "replay-feeder-${case.name}")

        vm.isDaemon = true
        feeder.isDaemon = true
        vm.start()
        feeder.start()

        // 指令耗尽后的收敛窗口：函数要跑完剩余的 PRINT 并返回。
        val feederFinished = joinFor(feeder, FEED_BUDGET_MS)
        if (!feederFinished) {
            feederFailures.add("投喂线程在 ${FEED_BUDGET_MS}ms 内未完成（有指令没投出去或被卡住）")
            feeder.interrupt()
        }
        failures.addAll(feederFailures)

        val finishedInTime = joinFor(vm, DRAIN_MS)
        val blocked = !finishedInTime && sink.isAwaiting
        if (!finishedInTime && !case.expectBlocked) {
            failures.add(
                "STILL_AWAITING：指令耗尽后 ${DRAIN_MS}ms 函数仍在等待" +
                    "（isAwaiting=${sink.isAwaiting} pendingCount=${sink.pendingCount}）" +
                    "——用例未声明 expect: blocked",
            )
        }
        if (finishedInTime && case.expectBlocked) {
            failures.add("用例声明 expect: blocked，但函数在指令耗尽前就返回了（等待没有被钉住）")
        }

        // 无论成败都要收口：关队列 → VM 线程的等待抛 InputSinkClosedException → join。
        sink.close()
        if (!joinFor(vm, DRAIN_MS)) {
            failures.add("VM 线程在 close() 后 ${DRAIN_MS}ms 内未退出（等待未被唤醒）")
            vm.interrupt()
        }
        if (feeder.isAlive) {
            feeder.interrupt()
            joinFor(feeder, DRAIN_MS)
        }

        val thrown = vmErrorRef.get()
        val vmError = thrown?.let { GoldenPipeline.describeFailure(model.program, model, it) }
        if (thrown != null) {
            val kind = when (thrown) {
                is CodeEE -> "CodeEE"
                else -> thrown::class.simpleName ?: "Throwable"
            }
            failures.add("VM 线程抛 $kind: $vmError")
        }

        val delta = console.snapshot().drop(minOf(logBefore, console.snapshot().size))
        val deltaNorm = GoldenNormalizer.normalize(delta.joinToString("\n"))
        val expectedNorm = GoldenNormalizer.normalize(case.expectConsole.joinToString("\n"))
        if (deltaNorm != expectedNorm) {
            val d = GoldenNormalizer.diff(expectedNorm, deltaNorm)
            failures.add("控制台增量不符（logBefore=$logBefore）:\n${d.preview}")
        }

        failures.addAll(checkVars(case, rt))

        return Result(
            case = case,
            failures = failures,
            blocked = blocked,
            consoleDelta = deltaNorm,
            vmError = vmError,
        )
    }

    /** 变量对拍：读取口径与既有测试同源（`store.readInt` / `store.readStr`，BootWiringTest.kt:51）。 */
    private fun checkVars(case: ReplayCase, rt: ProcessRuntime): List<String> {
        val out = ArrayList<String>()
        for (e in case.expectVars) {
            try {
                if (e.isStr) {
                    val actual = rt.store.readStr(e.name, e.indices)
                    if (actual != e.strValue) out.add("变量 ${e.raw} 不符：实际=「$actual」")
                } else {
                    val actual = rt.store.readInt(e.name, e.indices)
                    if (actual != e.intValue) out.add("变量 ${e.raw} 不符：实际=$actual")
                }
            } catch (t: Throwable) {
                out.add("变量 ${e.raw} 读取失败：${t::class.simpleName}: ${t.message}")
            }
        }
        return out
    }

    /**
     * 投喂线程主体。失败只记录（不抛）：VM 侧的异常由主线程统一报告。
     */
    private fun feed(
        case: ReplayCase,
        sink: QueueInputSink,
        vmDone: AtomicBoolean,
        failures: MutableList<String>,
    ) {
        for ((idx, ins) in case.inputs.withIndex()) {
            val n = idx + 1
            when (ins) {
                is ReplayInput.Pause -> {
                    try {
                        Thread.sleep(ins.millis)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                is ReplayInput.Submission -> {
                    if (!awaitConsumable(sink, vmDone, n, failures)) return
                    if (!sink.submit(ins.text, ins.mesSkip)) {
                        failures.add("指令 $n submit 入队失败（队列已关闭）")
                        return
                    }
                    if (!awaitPendingDrained(sink, vmDone, n, failures)) return
                }
                is ReplayInput.Click -> {
                    if (!awaitConsumable(sink, vmDone, n, failures)) return
                    if (!sink.submitButtonClick(ins.text, ins.button)) {
                        failures.add("指令 $n click 入队失败（队列已关闭）")
                        return
                    }
                    if (!awaitPendingDrained(sink, vmDone, n, failures)) return
                }
            }
        }
    }

    /** 等到「有等待且队列空」这个投喂窗口；超时或 VM 已返回 ⇒ 记失败。 */
    private fun awaitConsumable(
        sink: QueueInputSink,
        vmDone: AtomicBoolean,
        n: Int,
        failures: MutableList<String>,
    ): Boolean {
        val deadline = System.nanoTime() + DRAIN_MS * 1_000_000L
        while (true) {
            if (sink.isAwaiting && sink.pendingCount == 0) return true
            if (vmDone.get()) {
                failures.add("指令 $n 没有可用等待点：函数在投喂前已返回（剩余指令未被消费）")
                return false
            }
            if (System.nanoTime() - deadline >= 0L) {
                failures.add("指令 $n 等待投喂窗口超时（${DRAIN_MS}ms 内未出现 isAwaiting && pendingCount==0）")
                return false
            }
            try {
                Thread.sleep(1L)
            } catch (_: InterruptedException) {
                return false
            }
        }
    }

    /** 等这条提交被等待方吃掉（`pendingCount` 回 0）。 */
    private fun awaitPendingDrained(
        sink: QueueInputSink,
        vmDone: AtomicBoolean,
        n: Int,
        failures: MutableList<String>,
    ): Boolean {
        val deadline = System.nanoTime() + DRAIN_MS * 1_000_000L
        while (true) {
            if (sink.pendingCount == 0) return true
            if (vmDone.get() && sink.pendingCount != 0) {
                failures.add("指令 $n 的提交未被消费（函数已返回，pendingCount=${sink.pendingCount}）")
                return false
            }
            if (System.nanoTime() - deadline >= 0L) {
                failures.add("指令 $n 的提交 ${DRAIN_MS}ms 内未被消费（pendingCount=${sink.pendingCount}）")
                return false
            }
            try {
                Thread.sleep(1L)
            } catch (_: InterruptedException) {
                return false
            }
        }
    }

    private fun joinFor(t: Thread, ms: Long): Boolean {
        t.join(ms)
        return !t.isAlive
    }

    companion object {
        /** 指令耗尽后等函数返回的窗口（契约 §3.2-3）。 */
        const val DRAIN_MS = 2000L

        /** 投喂线程的总预算：单条用例的输入条数个位数量级，2s 已远超需要。 */
        const val FEED_BUDGET_MS = 2000L

        /** 单次 `callFunction` 的步数上限：重放用例只做几条语句 + 若干 PRINT。 */
        const val STEP_CAP = 2_000_000L

        /** 读用例目录下全部 `.replay`（文件名排序，测试名稳定）。 */
        fun loadAll(dir: java.nio.file.Path): List<ReplayCase> {
            val files = (dir.toFile().listFiles { f -> f.isFile && f.name.endsWith(".replay") } ?: emptyArray())
                .sortedBy { it.name }
            return files.map { f ->
                ReplayCaseParser.parse(f.name, f.readText(Charsets.UTF_8))
            }
        }
    }
}
