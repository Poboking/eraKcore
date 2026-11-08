package com.eratw.port.core.diag

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.FileReadAttempt
import com.eratw.port.core.vars.VariableStore
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 错误报告的纯渲染层（方案①「本地报告包 + 玩家手动分享」，2026-10-27 定稿）。
 *
 * ## 为什么放在 core-vm
 *
 * 报告的**内容与格式**是可测的纯逻辑：分节结构、脚本位置提取、截断口径、文件名规则，
 * 全都不需要 Android API。宿主（app 壳）只负责采集两侧现场（版本/设备/控制台尾部、
 * ERB 调用栈反射读数）与落盘/分享，把拼好的字段交给本类渲染。这样铁律 4（core 禁
 * Android API）不被破坏，报告格式也永远有定向单测钉住。
 *
 * ## 构建指纹（2026-10-28 加，必带）
 *
 * 同一个 `versionName (versionCode)` 一小时内出过三个互不相同的包（2026-10 实测），
 * 光看「app 版本」分不清崩的是哪个。所以每份报告都渲染一节「[构建与产物标识]」：
 * 构建标识（宿主把 gradle 注入的 BuildConfig 值传进 [buildStamp]）与
 * 产物指纹（随包 `erbc.v1/package.report` 的 `program-sha256` 前
 * [PROGRAM_SHA_HEAD_CHARS] 位，宿主传进 [programSha256]）。
 * 取值只有一行文本、不含任何游戏内容，不触碰隐私口径；任一缺失渲染成 [UNKNOWN_MARK]，
 * 报告照常生成。core 不读 BuildConfig、不碰 assets——纯 JVM 铁律 4，值全部由宿主传入。
 *
 * ## 隐私口径（写给每一位接线的人，不许放松）
 *
 * 报告**默认只带**：应用版本、设备/系统型号、错误类型与消息、脚本位置（ERB 函数名 +
 * 行号）、ERB 函数调用栈、VM 线程 Java 栈顶、以及「屏上最后的内容」（即玩家眼前那段
 * 游戏文本——报红格式里的「屏上原样文案」的等价物）。
 *
 * 报告**不带**：变量取值（含 `frameSnapshotLines` 那种 LOCAL/LOCALS/ARG 全量帧导出）、
 * 存档数据、玩家输入历史、文件系统路径之外的个人信息。eraTW 口上内容是三方语料，
 * 「屏上最后的内容」按玩家勾选分享时自愿披露处理，文件头部要给一句说明让玩家可以
 * 自行删节。谁要是把变量 dump 接进来，先回去改这条口径。
 *
 * 「[exitForensicsLines] 系统取证」小节同这条口径：只有内存水位标量与**系统自己写的**
 * 退出判词（原因码 / 重要度 / 截断到宿主上限的 description），不含玩家内容，也不读
 * `ApplicationExitInfo` 的 trace 原文（那里面是别的进程与系统的日志，量又大）。
 *
 * ## 截断口径
 *
 * 真机失败现场的日志可能很大（屏上原文、栈），而报告要走剪贴板与 IM 分享，
 * 过大既传不动也读不动。各段各自限行数（[CONSOLE_TAIL_MAX_LINES] /
 * [STACK_MAX_LINES] / [EXTRA_MAX_LINES] / [EXIT_FORENSICS_MAX_LINES]），单行超长截断到
 * [LINE_MAX_CHARS]；截断在**尾部保留**（屏上原文取最后 N 行——最近的内容才是现场）。
 *
 * KDoc 注意：正文不出现块注释定界符字符对（见 AGENTS.md 铁律 5）。
 */
class ErrorReport(
    /** 报告类型（宿主起名：「脚本运行报错」「启动失败」「应用崩溃」…）。 */
    val kind: String,
    /** 报告时间的**已格式化**文本；由宿主给（宿主才知道玩家时区），core 不再碰时钟。 */
    val timestampText: String,
    /** 版本与设备小节，每行一条（宿主采集：app 版本、机型、系统版本）。 */
    val headerLines: List<String> = emptyList(),
    /**
     * 构建标识（gradle 注入的 BuildConfig 值，出包工具经 `-PeratwBuildStamp=<git sha>` 传入；
     * null/空白 = 宿主没给 → 渲染成 [UNKNOWN_MARK]）。
     *
     * 为什么必须带：同一个 versionName/versionCode 一小时内可以出三个不同的包
     * （2026-10 实测：一个没 INTERNET 权限、一个没重启修复、一个两者都有），
     * 光看「app 版本：0.2.0 (2)」根本分不清崩的是哪个 APK。
     */
    val buildStamp: String? = null,
    /**
     * 产物指纹（随包 `erbc.v1/package.report` 里的 `program-sha256` 值，宿主原样传入；
     * null/空白 = 读不到 → 渲染成 [UNKNOWN_MARK]）。core 只取前 [PROGRAM_SHA_HEAD_CHARS] 位，
     * 「缺行不缺报告」的兜底在宿主侧（读取绝不抛），这里只管口径。
     */
    val programSha256: String? = null,
    /**
     * 上报通道读数（gradle 注入的 `BuildConfig.eratwReportChannelStamp`，宿主原样传入；
     * null = 宿主没接（L4 之前的包）→ 渲染成 [UNKNOWN_MARK]）。host 级形状
     * `v1;endpoint=<host|off>;token=on|off;mirrors=...`，不含机密（token 只报 on/off）。
     * OBS2-L7 判因闭合后的接线（2026-11-05）：装机后从任何一份上报即可直读
     * 「报不报、往哪个 host 报」，不用再 aapt2 拆包。
     */
    val channelStamp: String? = null,
    /** 错误类型（异常类全名；无异常对象时宿主给「（无异常对象）」一类说明）。 */
    val errorType: String,
    /** 错误消息原样。 */
    val errorMessage: String,
    /**
     * 根因一行（宿主经 [rootCauseOf] 从因果链最深处取「类名：消息」）；null = 无因果链
     * （根因就是它自己，不重复占行）。为什么单独成行：`ExceptionInInitializerError` 这类
     * 包装异常的 message 恒空，只看「类型」永远猜不到里面是谁（2026-10-30 vivo 崩溃排障
     * 实际吃过这个亏）——最深一层的类名与消息才是能定位的那句话。
     */
    val rootCause: String? = null,
    /**
     * 脚本位置（如「GET_TALENTNAME 行370」）；null = 本错误不指向某行脚本。
     *
     * 行号是 **1 基**（与原版屏上一致，判据见 [scriptLocationOf]）；生产侧唯一入口是
     * [CodeEE.displayLine]，本字段只搬运、不再换算。
     */
    val scriptLocation: String? = null,
    /**
     * 异常自身的栈（含因果链，宿主逐层展平：每层一行「第N层：类: 消息」+ 帧）。
     * 与 [vmStackLines] 是两回事：那边是 VM 线程当时的现场，这边是**抛出点的栈**。
     */
    val errorStackLines: List<String> = emptyList(),
    /** ERB 调用栈 + VM 线程 Java 栈顶（宿主采集，最内帧在前）。 */
    val vmStackLines: List<String> = emptyList(),
    /**
     * 系统侧取证（宿主采集，2026-10-31）：本轮进程的 PSS/RSS 足迹 + 系统记下的**上一轮退出**
     * （原因/重要度/当时的 PSS·RSS/截断备注）。
     *
     * 为什么单独成节而不是塞进 [extraSectionLines]：「上次异常退出、没有任何 throwable」那
     * 一类反馈（2026-10 玩家上报里 14 条）里，异常侧什么都没有，唯一能定性的是**内核与
     * 系统服务自己的判词**——lmkd 收进程 / 用户划掉 / 包更新 / native 崩（信号）与 Java OOM
     * 在这份读数上是四个不同的原因码。缺这一节，那 14 条就永远只能是「没报错」。
     *
     * 采集侧的分配纪律（报告可能在堆将尽时生成）由宿主守住：行数与单行长度都有上限。
     */
    val exitForensicsLines: List<String> = emptyList(),
    /** 屏上最后的内容（宿主从控制台快照取尾部，原样）。 */
    val consoleTailLines: List<String> = emptyList(),
    /** 补充现场（宿主自定的读数行，如装载轨迹）。 */
    val extraSectionLines: List<String> = emptyList(),
) {

    /** 渲染成完整报告文本（UTF-8 纯文本，发送端不再加工）。 */
    fun render(): String {
        val sb = StringBuilder()
        val bar = "=".repeat(REPORT_BAR_WIDTH)
        val sep = "-".repeat(REPORT_BAR_WIDTH)
        sb.appendLine(bar)
        sb.appendLine("eraTW 安卓移植版 错误报告")
        sb.appendLine(bar)
        sb.appendLine("报告类型：$kind")
        sb.appendLine("报告时间：$timestampText")
        sb.appendLine("把本文件完整发给开发者（QQ 群 / Issue 附件）就是一次有效报错。")
        sb.appendLine("口径：本报告不含存档数据与变量取值；「屏上最后的内容」是游戏画面")
        sb.appendLine("文本，如涉隐私可自行删节后再发。")
        if (headerLines.isNotEmpty()) {
            sb.appendLine(sep)
            sb.appendLine("[版本与设备]")
            headerLines.forEach { sb.appendLine(clip(it)) }
        }
        sb.appendLine(sep)
        sb.appendLine("[构建与产物标识]")
        sb.appendLine(clip("构建标识：${displayOrUnknown(buildStamp)}"))
        sb.appendLine(clip("产物指纹：${programShaHeadOrUnknown(programSha256)}"))
        sb.appendLine(clip("上报通道：${displayOrUnknown(channelStamp)}"))
        if (exitForensicsLines.isNotEmpty()) {
            sb.appendLine(sep)
            sb.appendLine("[系统取证：足迹与上次退出]（最多 $EXIT_FORENSICS_MAX_LINES 行；退出原因码是内核/系统的判词）")
            exitForensicsLines.takeLast(EXIT_FORENSICS_MAX_LINES).forEach { sb.appendLine(clip(it)) }
        }
        sb.appendLine(sep)
        sb.appendLine("[错误]")
        sb.appendLine("类型：${clip(errorType)}")
        if (errorMessage.isNotEmpty()) sb.appendLine("信息：${clip(errorMessage)}")
        rootCause?.takeIf { it.isNotBlank() }?.let { sb.appendLine("根因：${clip(it)}") }
        scriptLocation?.takeIf { it.isNotBlank() }?.let { sb.appendLine("脚本位置：${clip(it)}") }
        if (errorStackLines.isNotEmpty()) {
            sb.appendLine(sep)
            sb.appendLine("[异常栈（含因果链）]（最多 $ERROR_STACK_MAX_LINES 行，超限保尾部——最深层是根因）")
            errorStackLines.takeLast(ERROR_STACK_MAX_LINES).forEach { sb.appendLine(clip(it)) }
        }
        if (vmStackLines.isNotEmpty()) {
            sb.appendLine(sep)
            sb.appendLine("[ERB 调用栈与 VM 现场]（最内帧在前，最多 $STACK_MAX_LINES 行）")
            vmStackLines.takeLast(STACK_MAX_LINES).forEach { sb.appendLine(clip(it)) }
        }
        if (consoleTailLines.isNotEmpty()) {
            sb.appendLine(sep)
            sb.appendLine("[屏上最后的内容（原样）]（最多 $CONSOLE_TAIL_MAX_LINES 行）")
            consoleTailLines.takeLast(CONSOLE_TAIL_MAX_LINES).forEach { sb.appendLine(clip(it)) }
        }
        if (extraSectionLines.isNotEmpty()) {
            sb.appendLine(sep)
            sb.appendLine("[补充现场]")
            extraSectionLines.takeLast(EXTRA_MAX_LINES).forEach { sb.appendLine(clip(it)) }
        }
        sb.appendLine(bar)
        sb.appendLine("报告结束")
        return sb.toString()
    }

    private fun clip(line: String): String =
        if (line.length <= LINE_MAX_CHARS) line else line.take(LINE_MAX_CHARS) + "…（超长截断）"

    /** 空白/缺失一律渲染 [UNKNOWN_MARK]——「缺行不缺报告」的口径在 core 侧也兜住。 */
    private fun displayOrUnknown(value: String?): String =
        value?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_MARK

    /** 产物指纹只展示前 [PROGRAM_SHA_HEAD_CHARS] 位：够对账、不淹没报告。 */
    private fun programShaHeadOrUnknown(value: String?): String =
        value?.trim()?.takeIf { it.isNotEmpty() }?.take(PROGRAM_SHA_HEAD_CHARS) ?: UNKNOWN_MARK

    companion object {

        /** 屏上原文小节的行数上限（尾部保留）。 */
        const val CONSOLE_TAIL_MAX_LINES = 80

        /** 调用栈小节的行数上限（尾部保留）。 */
        const val STACK_MAX_LINES = 60

        /**
         * 补充现场小节的行数上限（尾部保留）。
         *
         * 40 → 80（2026-11-02，EP1 波组长接线）：这一节从「内存 + 心跳」两小块长成了
         * 「内存读数（含 Java 堆分项一行）→ 约会现场 → 存档留痕 → 线程心跳 → 主线程冻结现场」
         * 五小块，最重的冻结现场按 `StallJudgment.SCENE_MAX_LINES` 可占 31 行。窗口留在 40 时
         * 尾部保留会把**最前面**的内存读数剪掉，而 OOM 归因靠的就是那几行（2026-10-31 两份
         * OOM 报告缺这节的教训原文在 `ErrorReportCollector.memoryLines`）。
         * 体积代价：单行另有超长截断，整节满档约 10 KB，端点单份上限 512 KB（Worker 侧判），
         * 不在一个量级上。
         */
        const val EXTRA_MAX_LINES = 80

        /**
         * 系统取证小节的行数上限：本轮足迹两行 + 最多三条退出记录 + 判读一行，
         * 留一点余量。这一节的行数由采集侧自己封顶（报告可能在堆将尽时生成），
         * 这里只是第二道闸。
         */
        const val EXIT_FORENSICS_MAX_LINES = 12

        /** 异常栈小节（含因果链）的行数上限，尾部保留——链越深越要看最里层。 */
        const val ERROR_STACK_MAX_LINES = 160

        /** 单行字符上限：剪贴板/IM 分享口径下超长行只会淹没关键信息。 */
        const val LINE_MAX_CHARS = 400

        /** 构建标识/产物指纹读不到时的占位文本（「缺行不缺报告」，见类头隐私口径）。 */
        const val UNKNOWN_MARK = "未知"

        /** 产物指纹在报告里展示的前缀位数（64 位十六进制取前 12 位，够人工对账）。 */
        const val PROGRAM_SHA_HEAD_CHARS = 12

        private const val REPORT_BAR_WIDTH = 64

        private val FILE_NAME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /**
         * 报告文件名：`eratw-report-<yyyyMMdd-HHmmss>.txt`。
         *
         * 时区由调用方给，缺省 UTC——文件名只承担「唯一 + 人眼可读」，宿主想用本地
         * 时区就显式传 [ZoneId.systemDefault]；core 缺省值必须确定性（可单测）。
         */
        fun fileNameAt(epochMillis: Long, zone: ZoneId = ZoneOffset.UTC): String {
            val text = FILE_NAME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
            return "eratw-report-$text.txt"
        }

        /**
         * 从异常因果链（cause 链，最多 16 层）里找第一个带位置信息的 [CodeEE]，
         * 返回「函数名 行N」形式的脚本位置；找不到返回 null。
         *
         * 与 app 侧既有 `GameHost.scriptLocation` 同判据（那里的产出上屏，这里的进报告）：
         * [CodeEE] 一路都带 funcName/line，裸穿到宿主时这是唯一的脚本定位。
         *
         * 行号取 [CodeEE.displayLine]（**1 基，与原版对齐**）——[CodeEE.line] 是内部 0 基
         * 下标，直接打它会比原版少一行；换算只在 [CodeEE.displayLine] 一处，本函数不加一
         * （spec: ScriptPosition.cs:15-17 —— 原版在位置对象自己身上归一，打印点一个 `+1` 都不写）。
         */
        fun scriptLocationOf(t: Throwable?): String? {
            var node: Throwable? = t
            var depth = 0
            while (node != null && depth < 16) {
                if (node is CodeEE) {
                    val parts = ArrayList<String>(2)
                    node.funcName?.takeIf { it.isNotBlank() }?.let { parts += it }
                    if (node.line >= 0) parts += "行${node.displayLine}"
                    return if (parts.isEmpty()) null else parts.joinToString(" ")
                }
                node = node.cause
                depth++
            }
            return null
        }

        /**
         * 因果链**最深层**异常的「类名：消息」；无链（cause 为 null 或就是自己）返回 null。
         *
         * 与 [scriptLocationOf] 同走链、同 16 层上限（防自引用 cause 死循环）。返回 null 而不是
         * 顶层自己：顶层类名报告「类型」行已有，重复一行只会淹没增量信息。
         */
        fun rootCauseOf(t: Throwable?): String? {
            if (t == null) return null
            var node: Throwable = t
            var depth = 0
            while (depth < 16) {
                val next = node.cause ?: break
                if (next === node || next === t) break // 自引用/回环：就地收口
                node = next
                depth++
            }
            if (node === t) return null
            return node.javaClass.name + (node.message?.let { "：$it" } ?: "")
        }
    }
}

/**
 * 「找不到文件 X」族的**读轴留痕**（2026-11-05 report-sqlroot；屏上文案一字不动）。
 *
 * ## 要修的判读面缺陷
 *
 * 真机上报里 `SQL_IMPORT_MAP_XML：找不到文件 plugins/tw_csv_chs.xml` 这一族永远看不出
 * **引擎试的是哪个根**：移植侧相对路径只按宿主根解析、出根即 null
 * （core/proc/BootHosts.kt 的 resolvePath），而报错只带相对路径。2026-11-04 那一红
 * （装置把宿主根挪出游戏根、没镜像 plugins/）全靠把 RealCorpus.load / 沙盒根 /
 * RootedBuiltinHost 构造点 / SqlMethods 抛点 / GameSession 装配 / GameHost 读配置
 * 全部串起来才反推出「它其实试的是沙盒根」。本类把这一手反推压成一行读数。
 *
 * ## 通道选择（判据来自波次契约）
 *
 * (a) 扩 CodeEE 消息本身 = 改玩家屏上原文：官方口径不可核（该函数是 eraTW fork 专有，
 * reference/emuera-cs 全库 0 命中，fork-src 不在本机），按 ADR-005 不动 —— 不选。
 * (b) 本通道：屏上文案原样，读数走**报告正文小节**（真机 Origin OS 丢应用 logcat，
 * 报告头口径是端点收得到的唯一通道，同 SaveDiagnostics 那条的先例）+ `System.err`
 * 一行（logcat `-s System.err` 在 release 包也可 adb，取证第二通道）。
 * (c) 只在装置暴露不够：真机上报缺的正是这一眼 —— 不选。
 *
 * ## 状态与隐私
 *
 * 状态挂 `store.aux` 槽（铁律 4：禁静态可变全局；RESTART/读档 `RuntimeAux.clear()`
 * 即随会话重建，「新一局」不留上一局的痕迹）。环形缓冲 [RING] 条、只记**失败**的相对读。
 * 行内含**宿主根绝对路径**：这是本读数的全部价值所在（两根末段可能同名，截段即不可判）。
 * Android 真机上宿主根在应用私有数据目录（`/data/data/<包名>/…`），不含玩家可识别信息；
 * 桌面装置上会带出盘符与用户名。口径留一个开关给组长裁：[lines] 的 [fullRoot]=false 退化为
 * 「…/倒数第二段/末段」两节掩码（同 SaveDiagnostics 的「不取绝对路径」口径），
 * 接进报告时翻这一枚布尔即可，core 不替产品做决定。
 *
 * 自我要求同 `core/save/SaveDiagnostics`：[record] 跑在抛点前夜，**永远不抛**，做不到就静默。
 */
class FileReadTrail {

    /** 一条失败读的留痕（结构化存储，渲染时才定脱敏口径）。 */
    data class Entry(val function: String, val attempt: FileReadAttempt)

    private val entries = ArrayDeque<Entry>()

    fun record(function: String, attempt: FileReadAttempt) {
        runCatching {
            entries.addLast(Entry(function, attempt))
            while (entries.size > RING) entries.removeFirst()
            // logcat 第二通道：release 包 `adb logcat -s System.err` 可读（SaveDiagnostics 同口径）。
            System.err.println("$PREFIX $function ${renderLine(attempt, fullRoot = true)}")
        }
    }

    /** 最近失败读（旧在前）；空 = 本会话没发生过失败的相对读。 */
    fun snapshot(): List<Entry> = entries.toList()

    /** 报告行（旧在前）；[fullRoot]=false 时根与落点只保留末两节（隐私退化口径）。 */
    fun lines(fullRoot: Boolean = true): List<String> =
        entries.map { renderLine(it.attempt, fullRoot).let { line -> "${it.function} $line" } }

    private fun renderLine(attempt: FileReadAttempt, fullRoot: Boolean): String {
        val rootText = attempt.root?.let { displayRoot(it, fullRoot) } ?: NO_ROOT_MARK
        val landing = when {
            attempt.resolved != null -> displayRoot(attempt.resolved, fullRoot) +
                if (attempt.regularFile) "（常规文件=是）" else
                    if (attempt.isDirectory) "（是目录，非文件）" else "（常规文件=否）"
            attempt.rejectedOutOfRoot -> "出根被拒（越界闸拦截，normalize 后不在根内）"
            else -> "路径无效（空串或非法）"
        }
        return "请求=${attempt.request} 试的根=$rootText 解析落点=$landing"
    }

    companion object {
        /** `store.aux` 槽名（约定：能力家族全小写前缀，见 RuntimeAux KDoc）。 */
        const val AUX_KEY = "file-read-trail"

        /** logcat / 报告行的可 grep 前缀。 */
        const val PREFIX = "[eratw][file]"

        /** FileOps 不是按根解析的实现（自定义宿主桩）时，根位的读数文案。 */
        const val NO_ROOT_MARK = "（宿主未提供根读数）"

        /** 环形缓冲深度：够看清「同一会话里连吃几次同型读失败」。 */
        private const val RING = 16

        /** 绝对路径的末两节（`/` 或 `\` 分隔都取尾）；掩码口径用。 */
        private fun tail2(path: String): String {
            val segs = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            return when {
                segs.size <= 2 -> segs.joinToString("/")
                else -> "…/" + segs.takeLast(2).joinToString("/")
            }
        }

        private fun displayRoot(path: String, fullRoot: Boolean): String =
            if (fullRoot) path else tail2(path)

        /**
         * 宿主接线入口（一行）：从会话 store 取本会话的失败读留痕，渲染成可进
         * [ErrorReport.extraSectionLines] 的行；空留痕返回空表 ⇒ 报告整节不写，
         * 与「本会话没失败过」逐字同形，不动既有报告一个字。
         */
        fun trailLines(store: VariableStore?, fullRoot: Boolean = true): List<String> {
            val trail = store?.aux?.peek<FileReadTrail>(AUX_KEY) ?: return emptyList()
            val snap = trail.snapshot()
            if (snap.isEmpty()) return emptyList()
            return listOf("文件读取现场（最近 ${snap.size} 条失败读，旧在前；相对路径只按宿主根解析、出根即拒）：") +
                trail.lines(fullRoot)
        }
    }
}
