package com.eratw.port.core.host

/**
 * 调试窗口宿主（DEBUGPRINT 家族 / DEBUGCLEAR）。
 * 输出**不进入**主文字流。spec: FunctionIdentifier.cs:540-582。
 *
 * 本接口刻意**不带**时钟或内存读数钩子（CONTRACT-M4-I §2 i-time 复核结论）：
 * 反编译侧的调试窗口只转发 `EmueraConsole` 的文本流，没有任何
 * `DateTime.Now` / `Process.WorkingSet64` 的格式化输出（全仓 grep
 * `WorkingSet64` 只命中 `FunctionMethodCreator.cs:8319`、`:8335`、`:8338` 三处，
 * 全部在 GETMEMORYUSAGE / CLEARMEMORY 的函数体内，与调试窗口无关）。
 * 时间与内存的唯一取数口是 `core/fn/TimeMethods.kt` + `BuiltinHost.clockMillis()`；
 * 在这里再加一份墙钟只会造出第二真相。
 */
interface DebugSink {
    fun print(text: String)

    fun printLine(text: String)

    fun clear()
}

object NoopDebugSink : DebugSink {
    override fun print(text: String) {}
    override fun printLine(text: String) {}
    override fun clear() {}
}
