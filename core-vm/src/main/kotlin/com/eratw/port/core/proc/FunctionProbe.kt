package com.eratw.port.core.proc

/**
 * 函数级观测口（DBG4 P3）：函数帧进入后 / 离开时由 [ProcessRuntime] 在 VM 线程**同步**
 * 回调。接线点 = [ProcessRuntime.onFunctionEnter] / [ProcessRuntime.onFunctionExit] 的
 * 既有 override 体（VmHost 契约的两个实现处），覆盖所有帧
 * 生命周期路径（callInto / jumpInto / leaveFrame / unwindScopes 的 finally 兜底）。
 *
 * 生产路径 [ProcessRuntime.functionProbe] 缺省为 null = 零行为（守卫结构与
 * [StatementProbe] 同款：先判空再整块跳过；null 路径零分配、不多一次虚调用）。
 * @Volatile 单写多读（宿主线程赋值、VM 线程消费，ADR-003 单线程执行）。
 *
 * **[meta] 可为 null**：无元数据的函数帧（[FunctionTable.byFunctionIndex] 未命中）照样
 * 进出，观测口照发，负载侧记 null。
 *
 * **[onFunctionExit] 的 outcome 语义（诚实边界）**：[VmHost.onFunctionExit] 的宿主契约
 * （Vm.kt:166-175）不携带离场原因——正常返回（leaveFrame）、尾跳换帧（jumpInto）、
 * 异常 / `_HALT` / Unwind（unwindScopes finally）三条路径共用一个钩子，宿主层**不可分辨**，
 * 本波不改 Vm（文件不在所有权内）。因此运行期恒传 [FunctionExitKind.EXIT]；「函数死于
 * 哪条语句」由语句级 [StatementProbe] 的 error 回调互证。枚举保留单值是为接线面稳定
 * ——若 Vm 将来把离场原因传进宿主钩子（需组长接线），在此扩值即可，API 形状不变。
 *
 * 回调实现**不得阻塞、不得抛**：探针异常由 runtime 用 runCatching 吞弃（观测口故障
 * 不得反噬执行流），与 [StatementProbe] 的吞口约定同源。
 */
fun interface FunctionProbe {

    /** 函数帧进入后回调（实参绑定**之后**、被调函数第一条指令之前，与 VmHost 契约同点）。 */
    fun onFunctionEnter(meta: FuncMeta?)

    /** 函数帧离开时回调；帧已弹出、变量层 leaveFunction 之后（与 VmHost 契约同点）。 */
    fun onFunctionExit(meta: FuncMeta?, outcome: FunctionExitKind) {}
}

/** 函数离场方式（见 [FunctionProbe] 的 outcome 语义节：宿主层现只能给出 EXIT）。 */
enum class FunctionExitKind {

    /** 帧离开（正常返回 / 尾跳 / 异常 / _HALT / Unwind 在宿主层不可分辨，统一此值）。 */
    EXIT,
}
