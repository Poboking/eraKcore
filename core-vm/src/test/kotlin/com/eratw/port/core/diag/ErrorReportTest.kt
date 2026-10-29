package com.eratw.port.core.diag

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.ZoneOffset

/**
 * [ErrorReport] 定向单测（方案① 报错收集）：钉住分节结构、脚本位置提取、
 * 截断口径与文件名规则。报告格式是「玩家 → 开发者」的契约，格式漂移即返工。
 */
class ErrorReportTest {

    // ---- 渲染：分节齐全 -------------------------------------------------

    @Test
    fun 渲染包含类型时间设备错误与结尾栏() {
        val report = ErrorReport(
            kind = "脚本运行报错",
            timestampText = "2026-10-27 12:34:56",
            headerLines = listOf("app 版本：0.1.0-skeleton (1)", "设备：vivo V2199A"),
            errorType = "com.eratw.port.core.err.CodeEE",
            errorMessage = "变量 CFLAG 的第 1 参数 200 越界",
            scriptLocation = "UPDATE_AND_SELECTION_OF_KOJO_MODDING 行358",
        )
        val text = report.render()
        assertTrue(text.contains("报告类型：脚本运行报错"))
        assertTrue(text.contains("报告时间：2026-10-27 12:34:56"))
        assertTrue(text.contains("app 版本：0.1.0-skeleton (1)"))
        assertTrue(text.contains("设备：vivo V2199A"))
        assertTrue(text.contains("类型：com.eratw.port.core.err.CodeEE"))
        assertTrue(text.contains("信息：变量 CFLAG 的第 1 参数 200 越界"))
        assertTrue(text.contains("脚本位置：UPDATE_AND_SELECTION_OF_KOJO_MODDING 行358"))
        assertTrue(text.contains("报告结束"))
        assertTrue(text.contains("不含存档数据与变量取值"))
    }

    @Test
    fun 空小节不产出空标题也不出现null字样() {
        val text = ErrorReport(
            kind = "应用崩溃",
            timestampText = "2026-10-27 00:00:00",
            errorType = "java.lang.OutOfMemoryError",
            errorMessage = "",
        ).render()
        assertFalse(text.contains("[ERB 调用栈与 VM 现场]"))
        assertFalse(text.contains("[屏上最后的内容"))
        assertFalse(text.contains("[补充现场]"))
        assertFalse(text.contains("null"))
    }

    // ---- 截断口径：尾部保留 ---------------------------------------------

    @Test
    fun 屏上原文超限只保留最后N行且单行超长截断() {
        val tail = (1..(ErrorReport.CONSOLE_TAIL_MAX_LINES + 30)).map { "L$it" }
        val longLine = "很".repeat(ErrorReport.LINE_MAX_CHARS + 100)
        val text = ErrorReport(
            kind = "脚本运行报错",
            timestampText = "t",
            errorType = "E",
            errorMessage = "",
            consoleTailLines = tail + listOf(longLine),
        ).render()
        // 尾部保留：最后一行（超长行截断后的残段）在，第一行不在。
        assertTrue(text.contains("（超长截断）"))
        assertFalse(text.contains("\nL1\n"))
        val keptCount = tail.count { text.contains("\n$it\n") }
        assertEquals(ErrorReport.CONSOLE_TAIL_MAX_LINES - 1, keptCount)
        // 长行不整行进报告。
        assertFalse(text.contains("很".repeat(ErrorReport.LINE_MAX_CHARS + 100)))
        val clippedCount = Regex("很").findAll(text).count()
        assertEquals(ErrorReport.LINE_MAX_CHARS, clippedCount)
    }

    @Test
    fun 调用栈与补充现场各自按上限尾部保留() {
        val stack = (1..(ErrorReport.STACK_MAX_LINES + 5)).map { "#$it fn$it" }
        val extra = (1..(ErrorReport.EXTRA_MAX_LINES + 5)).map { "x$it" }
        val text = ErrorReport(
            kind = "启动失败",
            timestampText = "t",
            errorType = "E",
            errorMessage = "",
            vmStackLines = stack,
            extraSectionLines = extra,
        ).render()
        assertFalse(text.contains("\n#1 "))
        assertTrue(text.contains("\n#${ErrorReport.STACK_MAX_LINES + 5} fn${ErrorReport.STACK_MAX_LINES + 5}"))
        assertFalse(text.contains("\nx1\n"))
        assertTrue(text.contains("\nx${ErrorReport.EXTRA_MAX_LINES + 5}"))
    }

    // ---- 构建指纹（2026-10-28：一个 versionName 三个包，报告必须能定位到 APK） ----

    @Test
    fun 构建标识与产物指纹成节且指纹只取前12位() {
        val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val text = ErrorReport(
            kind = "脚本运行报错",
            timestampText = "2026-10-28 09:00:00",
            headerLines = listOf("app 版本：0.2.0 (versionCode 2)"),
            buildStamp = "deadbeef1234",
            programSha256 = sha,
            errorType = "E",
            errorMessage = "",
        ).render()
        assertTrue(text.contains("[构建与产物标识]"))
        assertTrue(text.contains("构建标识：deadbeef1234"))
        assertTrue(text.contains("产物指纹：${sha.take(ErrorReport.PROGRAM_SHA_HEAD_CHARS)}"))
        // 只展示前 12 位：完整 64 位串不得出现在报告里。
        assertFalse(text.contains(sha))
        // 隐私口径不放松：新节不引入变量取值/存档内容，说明行原样在。
        assertTrue(text.contains("不含存档数据与变量取值"))
    }

    @Test
    fun 标识缺失降级为未知且报告照常渲染() {
        val text = ErrorReport(
            kind = "应用崩溃",
            timestampText = "2026-10-28 09:00:00",
            buildStamp = null,
            programSha256 = "   ",
            errorType = "java.lang.OutOfMemoryError",
            errorMessage = "",
        ).render()
        assertTrue(text.contains("构建标识：${ErrorReport.UNKNOWN_MARK}"))
        assertTrue(text.contains("产物指纹：${ErrorReport.UNKNOWN_MARK}"))
        assertTrue(text.contains("报告结束"))
    }

    @Test
    fun 新节不挡住上报端点校验的标记行() {
        // tools/report-endpoint/worker.js 的 REPORT_MARKER 判据是「正文包含该串」；
        // 标记行必须仍在报告最前（第 1 行分隔栏之后的第 2 行），新增小节一律排在它后面。
        val text = ErrorReport(
            kind = "脚本运行报错",
            timestampText = "t",
            headerLines = listOf("app 版本：0.2.0 (versionCode 2)"),
            buildStamp = "abc123",
            programSha256 = "0123456789abcdef0123456789abcdef",
            errorType = "E",
            errorMessage = "",
        ).render()
        val lines = text.lines()
        assertEquals("=".repeat(64), lines[0])
        assertEquals("eraTW 安卓移植版 错误报告", lines[1])
        assertTrue(lines.indexOfFirst { it.startsWith("[构建与产物标识]") } > 1)
    }

    // ---- scriptLocationOf：因果链提取 ------------------------------------

    @Test
    fun 脚本位置从因果链内层CodeEE提取() {
        val inner = CodeEE("越界", funcName = "GET_TALENTNAME", line = 369, pc = 682671)
        val outer = IllegalStateException("装载失败", inner)
        assertEquals("GET_TALENTNAME 行369", ErrorReport.scriptLocationOf(outer))
    }

    @Test
    fun 无位置信息的异常返回null() {
        assertNull(ErrorReport.scriptLocationOf(IllegalStateException("纯 Java 错")))
        // CodeEE 但既无名又无行 → 不伪造位置。
        assertNull(ErrorReport.scriptLocationOf(CodeEE("空位置")))
        assertNull(ErrorReport.scriptLocationOf(null))
    }

    // ---- 文件名 ---------------------------------------------------------

    @Test
    fun 文件名按UTC格式化为固定形状() {
        // 2026-10-27T12:34:56Z = 1793104496000 ms。
        assertEquals(
            "eratw-report-20261027-123456.txt",
            ErrorReport.fileNameAt(1_793_104_496_000L, ZoneOffset.UTC),
        )
    }
}
