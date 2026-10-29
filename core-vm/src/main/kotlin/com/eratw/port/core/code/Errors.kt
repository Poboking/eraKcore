package com.eratw.port.core.code

/**
 * `core.code` 视角的 CodeEE —— 指向 [com.eratw.port.core.err.CodeEE] 的唯一真源。
 *
 * 用 typealias 而不是再声明一个类：全工程 `catch (CodeEE)` 必须能兜住 code / vars / stmt
 * 抛出的所有脚本错误；两份同名类会让跨包 catch 静默漏掉一半（team-lead 裁决 2026-10-17）。
 */
typealias CodeEE = com.eratw.port.core.err.CodeEE

/**
 * VM 运行期错误的共同基类（都是 CodeEE）。
 *
 * 之所以还要这层：宿主有时需要区分"脚本自身错了"（CodeEE）与"VM 保护闸触发"
 * （步数上限 / 调用栈溢出），而层数上限在本移植里是引擎保护、不一定对应原版语义。
 */
open class VmException(
    message: String,
    line: Int,
    pc: Int,
    funcName: String?,
) : CodeEE(message, funcName, line, pc)

/** 单次 run 超过 [VmConfig.maxStepsPerRun]（死循环保护）。 */
class StepLimitExceeded(
    val steps: Long,
    line: Int,
    pc: Int,
    funcName: String?,
    /**
     * 抛点就地还原的脚本调用链（入口帧 → 当前帧，` → ` 连接）；null = 未知（测试/手工构造）。
     *
     * 之所以进 message：宿主的失败屏只透传 [message]（BootFailures.classify 的 OTHER 分支），
     * line/pc/funcName 是结构化字段、屏幕拿不到 —— 2026-10-21 真机首跑撞闸时失败屏
     * 只剩一句「疑似死循环」，循环位置只能靠事后反射猜（BOARD「行7」之争的根源）。
     */
    val callChain: String? = null,
    /**
     * 抛点就地导出的帧局部变量快照（[com.eratw.port.core.vars.VariableStore.frameSnapshotLines]）；
     * 空表 = 宿主没有变量池（测试宿主 / 纯字节码测试）。
     *
     * **必须在抛点取**：`Vm.run` 的 finally 在异常离栈时回卷调用栈，宿主事后再读
     * LOCAL/ARG 只会拿到「变量 LOCAL 只能在函数内使用」（2026-10-25 红 J 取证缺口）。
     */
    val frameSnapshot: List<String> = emptyList(),
    /**
     * 触发本闸的**墙钟毫秒**（段落口径：自上一次让出边界起）；-1 = 步数闸触发（既有路径）。
     *
     * 为什么要有这一维（2026-10-29）：原版根本没有「每段指令上限」这种东西 ——
     * `Process.cs:493-512` 的 `checkInfiniteLoop` 每 10000 行看一次**墙钟**，超过
     * `Config.InfiniteLoopAlertTime`（默认 5000 ms）就弹框问「是否中止」，玩家选「否」
     * 就 `state.lineCount = 0; startTime.Restart()` 继续跑。于是原版读长档的恢复演出
     * 只是「卡很久」，从不中止；移植层拿步数硬闸当同一件事，就把合法重负载判成了死循环
     * （BOARD 2026-10-27 的 `EVENTTRAIN → CHARA_MOVEMENT` 2000 万步拦停即此）。
     * 时间闸与步数闸共用本异常类型（宿主失败屏只认 `message`），但文案必须能区分，
     * 否则玩家看到「执行步数超过上限」去数步数，永远对不上「我只是读了个档」。
     */
    val elapsedMs: Long = -1L,
) : VmException(
    if (elapsedMs < 0) {
        "执行步数超过上限($steps)，疑似死循环" + if (callChain != null) " @ $callChain" else ""
    } else {
        "执行时间超过上限(${elapsedMs}ms，已执行 $steps 步)，疑似死循环" +
            if (callChain != null) " @ $callChain" else ""
    },
    line, pc, funcName,
)

/** 调用栈超过 [VmConfig.maxCallDepth]。 */
class CallStackOverflow(
    val depth: Int,
    line: Int,
    pc: Int,
    funcName: String?,
    /** 同 [StepLimitExceeded.callChain]：抛点活链，失败屏只认 message。 */
    val callChain: String? = null,
) : VmException(
    "调用栈超过上限($depth)" + if (callChain != null) " @ $callChain" else "",
    line, pc, funcName,
)
