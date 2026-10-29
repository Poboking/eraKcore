package com.eratw.port.core.diag

import com.eratw.port.core.err.CodeEE
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
 * ## 截断口径
 *
 * 真机失败现场的日志可能很大（屏上原文、栈），而报告要走剪贴板与 IM 分享，
 * 过大既传不动也读不动。三段各自限行数（[CONSOLE_TAIL_MAX_LINES] /
 * [STACK_MAX_LINES] / [EXTRA_MAX_LINES]），单行超长截断到 [LINE_MAX_CHARS]；
 * 截断在**尾部保留**（屏上原文取最后 N 行——最近的内容才是现场）。
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
    /** 错误类型（异常类全名；无异常对象时宿主给「（无异常对象）」一类说明）。 */
    val errorType: String,
    /** 错误消息原样。 */
    val errorMessage: String,
    /** 脚本位置（如「GET_TALENTNAME 行369」）；null = 本错误不指向某行脚本。 */
    val scriptLocation: String? = null,
    /** ERB 调用栈 + VM 线程 Java 栈顶（宿主采集，最内帧在前）。 */
    val vmStackLines: List<String> = emptyList(),
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
        sb.appendLine(sep)
        sb.appendLine("[错误]")
        sb.appendLine("类型：${clip(errorType)}")
        if (errorMessage.isNotEmpty()) sb.appendLine("信息：${clip(errorMessage)}")
        scriptLocation?.takeIf { it.isNotBlank() }?.let { sb.appendLine("脚本位置：${clip(it)}") }
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

        /** 补充现场小节的行数上限（尾部保留）。 */
        const val EXTRA_MAX_LINES = 40

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
         */
        fun scriptLocationOf(t: Throwable?): String? {
            var node: Throwable? = t
            var depth = 0
            while (node != null && depth < 16) {
                if (node is CodeEE) {
                    val parts = ArrayList<String>(2)
                    node.funcName?.takeIf { it.isNotBlank() }?.let { parts += it }
                    if (node.line >= 0) parts += "行${node.line}"
                    return if (parts.isEmpty()) null else parts.joinToString(" ")
                }
                node = node.cause
                depth++
            }
            return null
        }
    }
}
