package com.eratw.port.core.save

/**
 * 存档写失败的留痕口（**只诊断，不改语义**）。
 *
 * ## 为什么需要
 *
 * 原版 `SaveTo` 把整段落盘包在 `catch (Exception) { return false; }` 里
 * （VariableEvaluator.cs:2666-2669），移植侧 [FileSystemOps.saveData] 照抄了这个口径 ——
 * 语句层随后只能把它折成一句 `SAVEDATA命令在存档时发生了意料之外的错误`
 * （Process.cs:1343-1347）。也就是说**异常本体在移植侧没有任何出口**：
 *
 * - 2026-11-01 玩家反馈「不能覆盖旧存档，报错显示 SAVEDATA命令在存档时发生了意料之外的错误」，
 *   同一份语料 + 同一份存档在桌面（JVM）上覆盖、新建、删档三条链全绿，
 *   说明分叉在 Android 侧的落盘（文件系统 / 存储根 / 权限），而现场没有一条读数可用；
 * - 错误报告通道（`filesDir/eratw-reports/`）在 release 包里不可 `run-as`，
 *   DevTools 通道又只对 debuggable 包开放 —— 玩家机器上两条都够不着。
 *
 * ## 出口为什么是 `System.err`
 *
 * Android 的 zygote 会把 `System.err` 接到 `AndroidHandler` 上，以 **`System.err`** 这个
 * logcat 标签落 ERROR 级日志，**与 debuggable 无关**。于是：
 *
 * ```
 * adb logcat -s System.err
 * ```
 *
 * 在玩家的 release 包上也能读到（这也是本类唯一被验证过的现场取证通道）。
 * 桌面 / 单测里它落标准错误流，不影响任何断言。
 *
 * ## 不改什么
 *
 * - 调用方**仍然**只拿到 `false`（原版语义不变，语句层文案不变）；
 * - 不抛异常、不改控制台输出、不动返回值；
 * - 环形缓冲只是给「同一次会话里连续失败」留上下文，不落盘、不上报。
 */
object SaveDiagnostics {

    /** logcat / 测试输出里一眼可认的前缀。 */
    const val PREFIX: String = "[eratw][save]"

    /** 环形缓冲深度：够看清「同一会话里连续几次失败」，又不至于无限长。 */
    private const val RING = 16

    private val last: ArrayDeque<String> = ArrayDeque()

    /**
     * 记一次写失败。
     *
     * @param op 语句/函数名（`SAVEDATA` / `SAVEGLOBAL` / `SAVECHARA`）
     * @param path 落盘相对路径（[SaveConfig.normalPath] 一类，不是绝对路径 —— 不泄漏玩家目录）
     * @param stage 失败阶段：`capture`（抓变量池）/ `encode`（序列化）/ `write`（落盘）
     * @param error 被吞掉的那个异常
     * @param context 落盘现场快照（目录口径 / 存在性 / 目录内已有条目），见
     *   `FileSystemOps.saveDirContext`；空串 = 调用方没有目录面
     */
    fun report(op: String, path: String, stage: String, error: Throwable, context: String = "") {
        // 整段兜住：本函数跑在**失败的 catch 块里**，其中就包括 OutOfMemoryError。
        // 拼字符串 / 取完整栈 / 环形缓冲入队都要分配内存，在这里再抛一次会把
        // 「存档失败（玩家还能继续玩）」升级成「进程崩溃」—— 比没有现场更糟。
        // 所以留痕的自我要求是：**永远不抛**，做不到就静默。
        runCatching {
            val line = "$PREFIX $op 失败 stage=$stage path=$path " +
                "${error.javaClass.name}: ${error.message}"
            synchronized(last) {
                last.addLast(line)
                while (last.size > RING) last.removeFirst()
            }
            // 三行：可 grep 的摘要 → 现场快照 → 完整栈（`printStackTrace` 默认走 stderr）。
            System.err.println(line)
            if (context.isNotEmpty()) System.err.println("$PREFIX 现场 $context")
            System.err.print(stackOf(error))
        }
    }

    /**
     * 记一条**不是失败**但值得留痕的现场（例如「原子换名失败、已退原地覆盖」）。
     *
     * 与 [report] 分开的理由：那一条的语义是「这次存档没了」，这一条是「这次存档成功了，
     * 但走的是退路」。混进同一类会让「玩家报的失败」和「降级成功」在报告里分不开 ——
     * 而后者正是判断「设备存储吃紧 / 文件系统不支持换名」的唯一证据。
     *
     * 只进环形缓冲 + `System.err`，不抛、不改返回值。
     */
    fun note(op: String, path: String, detail: String) {
        // 与 [report] 同一条自我要求：跑在 OOM 之后，永远不抛，做不到就静默。
        runCatching {
            val line = "$PREFIX $op 退路 path=$path $detail"
            synchronized(last) {
                last.addLast(line)
                while (last.size > RING) last.removeFirst()
            }
            System.err.println(line)
        }
    }

    /** 最近的失败摘要（最新在后）；空 = 本会话没失败过。给单测与将来的 DevTools 命令读。 */
    fun snapshot(): List<String> = synchronized(last) { last.toList() }

    /** 清空环形缓冲（单测用；生产不需要）。 */
    fun clear() {
        synchronized(last) { last.clear() }
    }

    private fun stackOf(error: Throwable): String {
        val sw = java.io.StringWriter()
        error.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }
}
