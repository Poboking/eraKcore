package com.eratw.port.core.loader

import com.eratw.port.core.loader.BootMetadata.LoadWarning
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 侧车 `load-warnings` 段（M11-E warnmeta：装载期告警进 `program.meta`）的编解码判据。
 *
 * 内容 = erbcc save-15a 的 `LoadWarning(file, line, message)`（golden 口径见
 * `.workbuddy/golden-pc-loadwarnings.txt` 前两列）；原版不持久化装载告警
 * （只进控制台：EmueraConsole.cs:3064-3076，消费点 ParserMediator.cs:148），本段为
 * 移植侧扩展，兼容口径按 str-constants 的「尾部可选段」先例收紧：
 * - 段加在 `str-constants` 之后、`end` 之前（段序末尾）；
 * - **null（写出方无告警通道）与空清单（`load-warnings 0`）严格区分**：null ⇒ 整段省略，
 *   文本与引入本段之前逐字节相同（新写出·旧读不炸）；旧侧车缺段 ⇒ 读回 null（旧写出·新读，
 *   消费侧 [BootMetadata.loadWarningsOrEmpty] 给空清单口径）；
 * - 计数过 `checkCount` 同款校验（CMP-82：str-constants 是唯一没调它的段，本段不复制这个洞）；
 * - 严格互逆、禁静默容错（CMP-83 教训：空数组/缺字段不得引发字段左移或静默改写）。
 */
class BootMetaWarningsSectionTest {

    private fun warns(): List<LoadWarning> = listOf(
        // save-15a golden 第 1 行的原形（引号 + 中文文案）
        LoadWarning("GOLDEN_12_SYMBOLS.ERB", 43, "函数\"@G12_FUNC\"已经被定义过(GOLDEN_12_SYMBOLS.ERB的39行)"),
        // 特殊字符全家桶：反斜杠、制表、裸 CR、裸 LF、C0 控制符、日文路径 —— 全部必须被 quote 收进一行
        LoadWarning("状態管理/特殊.ERH", 7, "含\\反斜杠\t制表\r裸CR\n裸换行\u0001控制符"),
        LoadWarning("GOLDEN_06_INPUT_AUTO.ERB", 30, "INPUTS命令:参数过多"),
    )

    /** 只带告警段的最小模型（其余字段全默认，段仍会因 `str-constants 0` 存在而可判位次）。 */
    private fun metaWith(loadWarnings: List<LoadWarning>?) = BootMetadata(loadWarnings = loadWarnings)

    // ------------------------------------------------------------------
    // 1) 往返与段位次
    // ------------------------------------------------------------------

    @Test
    fun `告警清单经侧车往返保序保字段`() {
        val m = metaWith(warns())
        val text = m.encodeText()
        val back = BootMetadata.parse(text)
        assertNotNull(back.loadWarnings)
        assertEquals(m.loadWarnings, back.loadWarnings, "三字段（file/line/message）逐条往返")
        assertEquals(text, back.encodeText(), "文本 → 模型 → 文本 逐字节闭环")
    }

    @Test
    fun `新段位次在 str-constants 之后 end 之前`() {
        val lines = metaWith(warns()).encodeText().split('\n')
        val iStr = lines.indexOfFirst { it.startsWith("str-constants ") }
        val iWarn = lines.indexOfFirst { it.startsWith("load-warnings ") }
        val iEnd = lines.indexOf("end")
        assertTrue(iStr >= 0, "str-constants 段必须存在（写出侧恒写）: $lines")
        assertTrue(iWarn > iStr, "load-warnings 必须排在 str-constants 之后: iStr=$iStr iWarn=$iWarn")
        assertTrue(iEnd > iWarn, "load-warnings 必须在 end 之前: iWarn=$iWarn iEnd=$iEnd")
        assertEquals(lines.size - 2, iEnd, "end 必须是最后一条记录（split 后末元素是尾 LF 后的空串）: $lines")
        // 段头计数 == 条目行数（写出侧自证）
        assertEquals(warns().size, lines.count { it.startsWith("load-warn ") })
    }

    // ------------------------------------------------------------------
    // 2) null 与空清单的区分 + 双向兼容
    // ------------------------------------------------------------------

    @Test
    fun `null 无告警通道时整段省略 文本与旧语法逐字节相同`() {
        val text = metaWith(null).encodeText()
        assertFalse(text.contains("load-warnings"), "null 模型不得写出本段: $text")
        val decoded = BootMetadata.parse(text)
        assertNull(decoded.loadWarnings, "缺段读回 null（= 写出方没有通道），不是空清单")
        assertEquals(emptyList(), decoded.loadWarningsOrEmpty(), "消费口径：缺段当空清单")
    }

    @Test
    fun `空清单写出计数零且与 null 区分明确`() {
        val emptyText = metaWith(emptyList()).encodeText()
        assertTrue(emptyText.contains("load-warnings 0\n"), "空清单必须显式写出计数 0: $emptyText")
        val parsed = BootMetadata.parse(emptyText)
        assertNotNull(parsed.loadWarnings, "读回空清单与缺段（null）区分明确")
        assertTrue(parsed.loadWarnings!!.isEmpty())
        assertNotEquals(metaWith(null).encodeText(), emptyText, "null 与空清单的侧车字节必须不同")
        assertEquals(emptyText, parsed.encodeText(), "空清单闭环")
    }

    @Test
    fun `旧侧车缺段读成 null 且再编码不静默补出空清单`() {
        val text = metaWith(warns()).encodeText()
        // 模拟 save-15e 之前的旧侧车：把本段整段剪掉（段头 + 条目行）。
        // 用 split('\n')（保留尾部 LF 形成的空元素）而不是 lineSequence，
        // 否则拼接结果会少一个结尾换行、和「再编码」的比对变成行尾差异。
        val old = text.split('\n')
            .filterNot { it.startsWith("load-warn ") || it.startsWith("load-warnings ") }
            .joinToString("\n")
        assertTrue(old.contains("end"), "裁剪后仍以 end 收尾")
        assertFalse(old.contains("load-warn"), "旧侧车文本里不得残留本段: $old")

        val decoded = BootMetadata.parse(old)
        assertNull(decoded.loadWarnings, "旧写出·新读：缺段读 null（通道不存在），由消费方决定空清单口径")
        assertEquals(old, decoded.encodeText(), "null 再编码必须原样省略本段（CMP-83：禁止把缺段静默改写成 0 条）")
    }

    // ------------------------------------------------------------------
    // 3) 严格校验（checkCount 同款 + 字段形态）
    // ------------------------------------------------------------------

    @Test
    fun `计数与实际条数不符一律拒绝`() {
        val text = metaWith(warns()).encodeText() // 声明 3、条目 3
        // 超出生存记录数：checkCount 直接拦（CMP-82 点名 str-constants 缺的就是这道闸）
        assertFailsWith<BootMetaFormatException> { BootMetadata.parse(text.replaceFirst("load-warnings 3", "load-warnings 999999")) }
        // 负数计数：checkCount 的非负闸
        assertFailsWith<BootMetaFormatException> { BootMetadata.parse(text.replaceFirst("load-warnings 3", "load-warnings -1")) }
        // 声明 4 > 条目 3，但 remaining 含 end 仍够：repeat 第 3 次 next 撞上 end ⇒ 期望 load-warn 报错
        assertFailsWith<BootMetaFormatException> { BootMetadata.parse(text.replaceFirst("load-warnings 3", "load-warnings 4")) }
        // 声明 2 < 条目 3：end 判定撞见多余 load-warn
        assertFailsWith<BootMetaFormatException> { BootMetadata.parse(text.replaceFirst("load-warnings 3", "load-warnings 2")) }
        // 声明 0 但留了一条条目
        val zero = text.replaceFirst("load-warnings 3", "load-warnings 0")
        assertFailsWith<BootMetaFormatException> { BootMetadata.parse(zero) }
    }

    @Test
    fun `条目字段形态严格 畸形记录一律拒绝`() {
        val base = metaWith(null).encodeText() // 无本段，便于逐条注入畸形记录

        fun withRecord(count: Int, rec: String): String =
            base.removeSuffix("end\n") + "load-warnings $count\n" + rec + "\nend\n"

        // file 未加引号 / line 非整数 / message 未加引号 / 多余内容 / 载荷截断
        val bad = listOf(
            "load-warn 01_dup.ERB 5 \"m\"",
            "load-warn \"f\" 五 \"m\"",
            "load-warn \"f\" 5 m",
            "load-warn \"f\" 5 \"m\" 多余",
            "load-warn \"f\" 5",
            "load-warn \"f\"",
            "load-warn",
        )
        for (r in bad) {
            assertFailsWith<BootMetaFormatException>("畸形记录必须拒绝: $r") {
                BootMetadata.parse(withRecord(1, r))
            }
        }
        // 计数声明的条数与注入条数相符的正例（守门测试，防止上面全被误伤成「永远红」）
        val ok = withRecord(1, "load-warn \"f\" 5 \"m\"")
        assertEquals(1, BootMetadata.parse(ok).loadWarnings?.size)
    }

    @Test
    fun `重复段与截断文件一律拒绝`() {
        val text = metaWith(warns()).encodeText()
        // 重复 load-warnings 段：第一段吃完后 end 判定撞见第二段段头
        val dup = text.removeSuffix("end\n") + "load-warnings 0\nend\n"
        assertFailsWith<BootMetaFormatException> { BootMetadata.parse(dup) }
        // 截断：删掉最后一条条目行，计数仍声明 3
        val lastRec = text.lineSequence().last { it.startsWith("load-warn ") }
        val truncated = text.replace(lastRec + "\n", "")
        assertFailsWith<BootMetaFormatException> { BootMetadata.parse(truncated) }
    }

    // ------------------------------------------------------------------
    // 4) 确定性与头部改写
    // ------------------------------------------------------------------

    @Test
    fun `两次编码逐字节相同 清单保序不排序`() {
        val a = metaWith(warns()).encodeText()
        assertEquals(a, metaWith(warns()).encodeText(), "同一清单两次编码相同")
        val rev = metaWith(warns().reversed()).encodeText()
        assertNotEquals(a, rev, "清单是发生序流水：写出侧不得排序/去重（CorpusCompile 段序判据）")
    }

    @Test
    fun `withHeader 携带告警清单`() {
        val m = metaWith(warns())
        val h = m.withHeader(engineTag = "erbcc test-engine", programSha256 = "ab12cd")
        assertEquals(m.loadWarnings, h.loadWarnings, "换头部不得丢告警清单")
        val text = h.encodeText()
        val back = BootMetadata.parse(text)
        assertEquals("ab12cd", back.programSha256)
        assertEquals("erbcc test-engine", back.engineTag)
        assertEquals(m.loadWarnings, back.loadWarnings, "withHeader 后往返仍一致")
    }

    // ------------------------------------------------------------------
    // 5) 文件流式读（read 与 parse 同一实现，此处走 read 入口）
    // ------------------------------------------------------------------

    @Test
    fun `文件读回与文本解析对同一清单一致`(@TempDir dir: Path) {
        val m = metaWith(warns())
        val text = m.encodeText()
        val path = dir.resolve("program.meta")
        Files.write(path, text.toByteArray(Charsets.UTF_8))
        val read = BootMetadata.read(path)
        assertEquals(m.loadWarnings, read.loadWarnings, "read（流式）与 parse（整文）对告警段口径一致")
        assertEquals(text, read.encodeText(), "文件往返闭环")
        assertTrue(Files.readString(path).none { it == '\r' }, "裸 CR 不得进入侧车字节（message 里的 CR 已被 quote 转义）")
    }
}
