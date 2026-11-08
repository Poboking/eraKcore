package com.eratw.port.core.diag

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.err.erbDisplayLine
import com.eratw.port.core.stmt.StmtParseException
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
            channelStamp = "v1;endpoint=eratw-android.asia;token=on;mirrors=off",
            errorType = "E",
            errorMessage = "",
        ).render()
        assertTrue(text.contains("[构建与产物标识]"))
        assertTrue(text.contains("构建标识：deadbeef1234"))
        assertTrue(text.contains("产物指纹：${sha.take(ErrorReport.PROGRAM_SHA_HEAD_CHARS)}"))
        // OBS2-L7 接线：上报通道读数进节，装机后直读「报不报、往哪个 host 报」。
        assertTrue(text.contains("上报通道：v1;endpoint=eratw-android.asia;token=on;mirrors=off"))
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
        assertTrue(text.contains("上报通道：${ErrorReport.UNKNOWN_MARK}"), "L4 之前的包没接 stamp，照常渲染成未知")
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

    /**
     * 「行N」必须是 **1 基**（与原版屏上/报告对齐；组长裁定 2026-11-04）。
     *
     * 钉法：喂的是内部 0 基下标 369（= `Program.lines` 的存值 = 物理第 370 行），
     * 报告要写「行370」。旧实现直接把 0 基下标打出去，读成「行369」——那比原版少一行，
     * 也正是端点上 `GMF_METHOD_RESTORE 行89` 实际指向 `幻想乡地图重修.ERB:90` 的原因。
     * 归一只有一个入口 [CodeEE.displayLine]，本函数不许再加一。
     */
    @Test
    fun 脚本位置从因果链内层CodeEE提取() {
        val inner = CodeEE("越界", funcName = "GET_TALENTNAME", line = 369, pc = 682671)
        val outer = IllegalStateException("装载失败", inner)
        assertEquals("GET_TALENTNAME 行370", ErrorReport.scriptLocationOf(outer))
        // 键面不动：字段仍是那条 0 基下标（产物行号表 / pc↔行号对照用的就是它）。
        assertEquals(369, inner.line)
    }

    /**
     * 单一归一点自证：[CodeEE.displayLine] 是全工程唯一一处 0 基 → 1 基的换算，
     * 报告这一侧只是搬运。负数（无位置）必须原样为负，否则「没有位置」会被伪造成「第 0 行」。
     */
    @Test
    fun 行号归一只在displayLine一处且无位置仍为负() {
        assertEquals(1, CodeEE("首行", line = 0).displayLine, "0 基下标 0（物理第 1 行）→ 行1")
        assertEquals(70, CodeEE("越界", line = 69).displayLine, "端点最大簇的下标 69 → 行70")
        assertEquals(-1, CodeEE("空位置").displayLine, "line 缺省 -1 ⇒ 不许折成 0")
        assertEquals(-1, CodeEE("空位置", line = -1, pc = 4).displayLine)
        // 有名无行 → 只出名，不补一个假行号；有行无名 → 只出行号。
        assertEquals("ONLY_NAME", ErrorReport.scriptLocationOf(CodeEE("x", funcName = "ONLY_NAME")))
        assertEquals("行1", ErrorReport.scriptLocationOf(CodeEE("x", line = 0)))
    }

    /**
     * 解析失败文案（`core/stmt/ParseSupport.kt` 的「语句解析错误(行:N,列:M)」前缀）与
     * 运行期错误面**同一条**归一：两边都只经 [com.eratw.port.core.err.erbDisplayLine] 一次。
     *
     * 钉的三个数是 2026-11-05 用真语料对拍出来的（4.981 根 `eraTW4.981-261017-EAGLOV3/ERB`、
     * 4.980 根 `eratw-画蛇添足版eratw4.980/ERB`，逐行核对见交付报告的普查表）：
     * - 下标 89 → `幻想乡地图重修.ERB` 物理 90 行 `FOR LOCAL, 0, CHARANUM`（屏上旧读「行89」）
     * - 下标 625 → `SOURCE_EXTRA.ERB` 物理 626 行 `DOWNBASE:ARG:体力 = TOINT(POWER(...))`
     * - 下标 256 → `ResourceManager.ERB` 物理 257 行 `THROW 資源不足`
     */
    @Test
    fun 解析失败文案与运行期行号同一条归一() {
        val parse = StmtParseException(line = 89, col = 3, statementName = "FOR", message = "参数不足")
        assertEquals(90, parse.displayLine, "解析面 0 基 89 → 1 基 90")
        assertTrue(parse.message!!.startsWith("语句解析错误(行:90,列:3)FOR"), "前缀必须带 1 基行号：${parse.message}")
        // 键面仍是 0 基下标（erbcc 的降级清单按它记账）。
        assertEquals(89, parse.line)
        // 同一句脚本从两条路读出来必须同一个数：运行期面走 CodeEE.displayLine。
        assertEquals(parse.displayLine, CodeEE("x", funcName = "FOR", line = parse.line).displayLine)
        // 无位置：解析面给 -1 时文案与属性都不许折成 0 行。
        assertEquals(-1, StmtParseException(-1, 0, "", "无位置").displayLine)
        assertTrue(StmtParseException(-1, 0, "", "无位置").message!!.contains("行:-1"))
    }

    /**
     * 普查里那三条真语料对拍读数（组长裁定 2026-11-04：屏上/报告一律 1 基）。
     * 这里只钉「下标 → 显示行」的算术，语料本体那侧由上面 KDoc 登记的三个现场行号保证。
     */
    @Test
    fun 普查三条对拍读数的换算() {
        for ((fed, shown) in listOf(89 to 90, 625 to 626, 256 to 257)) {
            assertEquals(shown, erbDisplayLine(fed), "0 基下标 $fed → 行$shown")
            assertEquals(shown, CodeEE("x", line = fed).displayLine)
        }
        // 首行与越界哨兵。
        assertEquals(1, erbDisplayLine(0))
        assertEquals(-1, erbDisplayLine(-1))
    }

    @Test
    fun 无位置信息的异常返回null() {
        assertNull(ErrorReport.scriptLocationOf(IllegalStateException("纯 Java 错")))
        // CodeEE 但既无名又无行 → 不伪造位置。
        assertNull(ErrorReport.scriptLocationOf(CodeEE("空位置")))
        assertNull(ErrorReport.scriptLocationOf(null))
    }

    // ---- rootCauseOf：因果链最深层 --------------------------------------

    @Test
    fun 根因取因果链最深层的类与消息() {
        val inner = NoSuchMethodError("android.icu.util.ULocale")
        val mid = ExceptionInInitializerError(inner)
        val outer = RuntimeException("wrapper", mid)
        assertEquals("java.lang.NoSuchMethodError：android.icu.util.ULocale", ErrorReport.rootCauseOf(outer))
    }

    @Test
    fun 无因果链时根因为null且不重复类型行() {
        assertNull(ErrorReport.rootCauseOf(IllegalStateException("裸异常")))
        assertNull(ErrorReport.rootCauseOf(null))
    }

    @Test
    fun 自引用与回环cause不无限走() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b) // a→b→a 回环
        val root = ErrorReport.rootCauseOf(a)
        // 回环里最远走到 b 即停；不死循环、不抛
        assertEquals("java.lang.RuntimeException：b", root)
    }

    @Test
    fun 报告渲染根因行与异常栈节() {
        val report = ErrorReport(
            kind = "应用崩溃",
            timestampText = "2026-10-30 20:42:38",
            errorType = "java.lang.ExceptionInInitializerError",
            errorMessage = "",
            rootCause = ErrorReport.rootCauseOf(
                ExceptionInInitializerError(NoSuchMethodError("boot chart failure")),
            ),
            errorStackLines = listOf(
                "--- 异常：java.lang.ExceptionInInitializerError ---",
                "  at com.eratw.port.android.SettingsActivity.<clinit>",
                "--- 第1 层：java.lang.NoSuchMethodError：boot chart failure ---",
                "  at core.config.ConfigSpecTable.<clinit>",
            ),
        ).render()
        assertTrue(report.contains("根因：java.lang.NoSuchMethodError：boot chart failure"), "根因行必须上屏（vivo 排障缺口）")
        assertTrue(report.contains("[异常栈（含因果链）]"))
        assertTrue(report.contains("  at core.config.ConfigSpecTable.<clinit>"))
    }

    @Test
    fun 异常栈超限尾部保留最深层() {
        val lines = (1..(ErrorReport.ERROR_STACK_MAX_LINES + 40)).map { "帧$it" }
        val rendered = ErrorReport(
            kind = "应用崩溃", timestampText = "t",
            errorType = "X", errorMessage = "", errorStackLines = lines,
        ).render()
        assertTrue(rendered.contains("帧${ErrorReport.ERROR_STACK_MAX_LINES + 40}"), "超限切头保尾（最深层是根因）")
        assertFalse(rendered.contains("帧1\n"), "前 40 行应被截掉")
        assertTrue(!ErrorReport(
            kind = "启动失败", timestampText = "t", errorType = "X", errorMessage = "",
        ).render().contains("[异常栈"), "空 errorStackLines 不产出空节")
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

    // ---- 系统取证（2026-10-31：14 条「上次异常退出但没 throwable」的唯一判词来源）----

    @Test
    fun 系统取证成节且排在错误之前() {
        val rendered = ErrorReport(
            kind = "上次异常退出",
            timestampText = "t",
            errorType = "（无异常对象，见补充现场）",
            errorMessage = "",
            exitForensicsLines = listOf(
                "本轮足迹：PSS=500MiB RSS=625MiB",
                "退出#0 2026-10-31 10:00:00（5分钟前） 原因=LOW_MEMORY(3)",
                "判读：系统按内存压力回收（lmkd）",
            ),
        ).render()
        assertTrue(rendered.contains("[系统取证：足迹与上次退出]"))
        assertTrue(rendered.contains("原因=LOW_MEMORY(3)"))
        assertTrue(rendered.contains("判读：系统按内存压力回收"))
        assertTrue(
            rendered.indexOf("[系统取证") < rendered.indexOf("[错误]"),
            "取证节要在错误节之前（没有 throwable 的那一类，取证就是全篇最要紧的一段）",
        )
    }

    @Test
    fun 取证缺省时不产出空节且超限时尾部保留() {
        assertFalse(
            ErrorReport(kind = "应用崩溃", timestampText = "t", errorType = "X", errorMessage = "").render()
                .contains("[系统取证"),
            "宿主一行都没采到就不该有空标题",
        )
        val lines = (1..(ErrorReport.EXIT_FORENSICS_MAX_LINES + 6)).map { "取证$it" }
        val rendered = ErrorReport(
            kind = "应用崩溃", timestampText = "t", errorType = "X", errorMessage = "",
            exitForensicsLines = lines,
        ).render()
        assertTrue(rendered.contains("取证${ErrorReport.EXIT_FORENSICS_MAX_LINES + 6}"))
        assertFalse(rendered.contains("\n取证1\n"))
    }
}
