package com.eratw.port.erbcc.audit

import com.eratw.port.erbcc.AllowList
import com.eratw.port.erbcc.allowListCharsetRed
import com.eratw.port.erbcc.readAllowList
import com.eratw.port.erbcc.readAllowListText
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * F2 的字符集闸：`audit --allow <非法 UTF-8 白名单>`。
 *
 * 修前口径（本仓实测）：`--allow` 走 `path.readText()`，Kotlin 的默认解码动作是 REPLACE，
 * 非法字节被换成 U+FFFD 后**继续跑完整份审计并落 TSV**（rc=0）——「以为挂了白名单，实际挂的
 * 是一堆乱码」，属于静默放过；同一格 Rust 腿是严格解码报红（rc=1）。修后两侧都在任何产物
 * 落盘之前拒绝，退出码走参数错误档 2（`err()` 那把尺，`Main.kt:163-166`）。
 *
 * 钉的口径（`Main.kt` 的 `readAllowListText` / `allowListCharsetRed` / `readAllowList`）：
 * 1. 坏字节 ⇒ 抛 [IllegalStateException]，消息逐字 = [allowListCharsetRed]，且消息里**不得**
 *    出现替换字符 U+FFFD（不做有损解码）；路径段是 Kotlin 的 `WindowsPath.toString()` 口径。
 * 2. 合法 UTF-8 ⇒ 与旧的 `readText()` **逐字同解**（含 CJK、含 BOM 不剥离），解析形状一字不变：
 *    `#` 注释与空行跳过、2 列 = `kind<TAB>name`、3 列 = 带 functionKey、其它列数「跳过 + 一行告警」、
 *    整行 trim 而列内不 trim、不做大小写折叠、不做前缀匹配。
 * 3. 不是普通文件（缺文件 / 目录）⇒ 不是错误：[readAllowListText] 返回 null，
 *    [readAllowList] 按空白名单处理并打原样那行告警。
 *
 * 退出码 2 本身没法在进程内断言（`err()` 会 `exitProcess`，把跑 JUnit 的 JVM 一起带走），
 * 由 `tools/erbcc-rs/tests/cli_surface_diff.rs` 的 F2 格拿**真 Kotlin 腿**钉住。
 */
class AllowListCharsetTest {

    @TempDir
    lateinit var dir: Path

    // -------------------------------------------------------------------------
    // 1. 坏字节：拒绝，且拒绝得可读
    // -------------------------------------------------------------------------

    @Test
    fun `非UTF-8白名单的报红正文逐字钉住且不含替换字符`() {
        val p = writeBytes("allow-bad.tsv", BAD_ALLOW_BYTES)
        val e = assertFailsWith(IllegalStateException::class) { readAllowListText(p) }
        // 消息 = 单源的报红正文（Rust 侧 cmd_audit::allow_charset_red 与本串逐字相同）
        assertEquals(allowListCharsetRed(p.toString()), e.message, "报红正文必须走单源构造函数")
        val msg = e.message!!
        assertTrue(
            msg.startsWith("--allow 白名单不是合法 UTF-8 文本，已拒绝："),
            "报红必须点名是哪个入参坏了，实为：$msg",
        )
        assertContains(msg, "另存为 UTF-8", ignoreCase = false)
        assertFalse(msg.contains('\uFFFD'), "报红里不许出现有损解码的替换字符")
        // 路径段按 WindowsPath.toString() 上屏：反斜杠、无正斜杠（与 Rust 的 kotlin_path_text 同尺）
        if (File.separator == "\\") {
            val pathText = msg.substringAfter("已拒绝：").substringBefore("（请把")
            assertTrue(pathText.contains('\\'), "Windows 上路径段应归一成反斜杠，实为：$pathText")
            assertFalse(pathText.contains('/'), "Windows 上路径段不该有正斜杠：$pathText")
        }
    }

    @Test
    fun `三类坏字节形状都被拦下（截断与overlong与代理区）`() {
        // 前缀与后缀都是合法中文：证明拦的是中间那串坏字节，不是「见到非 ASCII 就红」。
        val cases = mapOf(
            "截断的多字节序列（E4 B8 少了尾字节）" to byteArrayOf(0xE4.toByte(), 0xB8.toByte()),
            "overlong 编码（C0 80 表示 NUL）" to byteArrayOf(0xC0.toByte(), 0x80.toByte()),
            "代理区编码（ED A0 80 = U+D800）" to byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            "裸 continuation 字节开头" to byteArrayOf(0x80.toByte()),
            "半截在行尾（合法中文后断掉）" to byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte(), 0xF0.toByte()),
        )
        for ((tag, body) in cases) {
            val bytes = "VAR_UNBOUND\t中文名\r\n".toByteArray(Charsets.UTF_8) + body + "\r\n".toByteArray()
            val p = writeBytes("bad-${tag.hashCode().toUInt()}.tsv", bytes)
            assertFailsWith(IllegalStateException::class, "坏字节形状未被拦下：$tag") { readAllowListText(p) }
        }
    }

    @Test
    fun `同一格不再出现一侧放过一侧硬失败`() {
        // 行为面：坏字节 ⇒ 读取阶段就抛，绝不返回「带 U+FFFD 的文本」让上层糊着跑完。
        val p = writeBytes("allow-bad2.tsv", BAD_ALLOW_BYTES)
        val thrown = runCatching { readAllowListText(p) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException, "必须抛 IllegalStateException，实为 $thrown")
        // 报红正文不含 JVM 内部异常串（CharacterCodingException 的 message 不外泄给使用者）
        assertFalse(thrown!!.message!!.contains("CodingException"), "报红要可读，不泄内部异常名")
        assertFalse(thrown.message!!.contains("malformed", ignoreCase = true), "同上")
    }

    // -------------------------------------------------------------------------
    // 2. 合法 UTF-8：一字不变（这是「F2 只收紧坏输入、没动好输入」的证明）
    // -------------------------------------------------------------------------

    @Test
    fun `合法UTF-8与旧readText口径逐字同解`() {
        val texts = listOf(
            "VAR_UNBOUND\tBAD\n",
            "# 已知项\r\nVAR_UNBOUND\t中文变量名\r\nKEYWORD_UNBOUND\t@F1\t口上\r\n",
            "",
            "\n\n",
            "VAR_TYPE_MISMATCH\tA\tB\n",
            "  前导空格的注释  \n",
        )
        val emojisAndCjk = "VAR_UNBOUND\t\u200b零宽空格 😀 表情\r\n"
        for ((idx, t) in (texts + emojisAndCjk).withIndex()) {
            val p = writeBytes("legal-$idx.tsv", t.toByteArray(Charsets.UTF_8))
            // 旧实现 = Kotlin 的 Path.readText()（UTF-8 + REPLACE）；合法字节下与 String(bytes, UTF_8) 恒等
            assertEquals(String(p.readAllBytesAsUtf8(), Charsets.UTF_8), readAllowListText(p), "第 $idx 份合法白名单改变了读法")
        }
    }

    @Test
    fun `BOM 不剥离（与旧 readText 一致）`() {
        val p = writeBytes("bom.tsv", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "# 注释\nVAR_UNBOUND\tBAD\n".toByteArray(Charsets.UTF_8))
        val text = readAllowListText(p)!!
        assertTrue(text.startsWith('\uFEFF'), "BOM 必须原样保留（旧 readText 不剥；剥了就是改好输入的行为）")
        // 第一行带 BOM ⇒ 不再以 # 开头 ⇒ 会被当坏行；这是既有口径，本路不顺手改。
        val err = captureStderr { readAllowList(p, text) }
        assertContains(err, "白名单行格式不对（跳过）", message = "BOM 行为保持原样：注释行被顶成坏行")
    }

    // -------------------------------------------------------------------------
    // 3. 不是普通文件 ⇒ 按空白名单处理（口径不变）
    // -------------------------------------------------------------------------

    @Test
    fun `缺文件返回null并在解析处打原样告警`() {
        val missing = dir.resolve("nope.tsv")
        assertNull(readAllowListText(missing), "缺文件不是错误（Main.kt 原口径：按空白名单处理）")
        var allowed: AllowList? = null
        val err = captureStderr { allowed = readAllowList(missing, null) }
        assertEquals("白名单文件不存在（按空白名单处理）: $missing\n", err, "告警文案一字不变")
        assertFalse(allowed!!.matches(finding(FindingKind.VAR_UNBOUND, "ANY", "")), "空白名单不放行任何东西")
    }

    @Test
    fun `指到目录也按缺文件处理`() {
        val sub = Files.createDirectory(dir.resolve("a-dir"))
        assertNull(readAllowListText(sub), "目录不是普通文件（isRegularFile 口径），不得当成白名单去解码")
        val err = captureStderr { readAllowList(sub, readAllowListText(sub)) }
        assertContains(err, "白名单文件不存在（按空白名单处理）")
    }

    // -------------------------------------------------------------------------
    // 4. 解析形状（2/3 列、注释、坏行、trim、大小写）
    // -------------------------------------------------------------------------

    @Test
    fun `解析形状不变：两列与三列与注释与空行`() {
        val text = "# 注释行\n" +
            "\n" +
            "  \n" +
            "VAR_UNBOUND\tBAD\n" +
            "KEYWORD_UNBOUND\t@F1\t口上\n"
        val p = dir.resolve("shape.tsv")
        var allowed: AllowList? = null
        val err = captureStderr { allowed = readAllowList(p, text) }
        assertEquals("", err, "全是合法形状时不该打任何告警")
        assertTrue(allowed!!.matches(finding(FindingKind.VAR_UNBOUND, "BAD", "")), "2 列按 kind+name 放行")
        assertTrue(allowed.matches(finding(FindingKind.KEYWORD_UNBOUND, "口上", "@F1")), "3 列按 kind+functionKey+name 放行")
        assertFalse(allowed.matches(finding(FindingKind.KEYWORD_UNBOUND, "口上", "@F2")), "functionKey 不符不得放行")
        assertFalse(allowed.matches(finding(FindingKind.VAR_UNBOUND, "BA", "")), "不做前缀匹配")
        assertFalse(allowed.matches(finding(FindingKind.VAR_TYPE_MISMATCH, "BAD", "")), "kind 不符不得放行")
    }

    @Test
    fun `坏行只打一行告警且不影响其它行`() {
        val text = "ONECOL\n" +
            "VAR_UNBOUND\tOK1\n" +
            "A\tB\tC\tD\n" +
            "KEYWORD_UNBOUND\t@F1\tOK2\n"
        var allowed: AllowList? = null
        val err = captureStderr { allowed = readAllowList(dir.resolve("malformed.tsv"), text) }
        val badLines = err.lines().filter { it.startsWith("白名单行格式不对（跳过）") }
        assertEquals(2, badLines.size, "每条坏行恰打一行告警，实为：$err")
        assertEquals("白名单行格式不对（跳过）: ONECOL", badLines[0])
        assertEquals("白名单行格式不对（跳过）: A\tB\tC\tD", badLines[1])
        assertTrue(allowed!!.matches(finding(FindingKind.VAR_UNBOUND, "OK1", "")), "坏行旁边的合法行必须照常入库")
        assertTrue(allowed.matches(finding(FindingKind.KEYWORD_UNBOUND, "OK2", "@F1")))
    }

    @Test
    fun `整行trim但列内不trim且不折叠大小写`() {
        val f = finding(FindingKind.VAR_UNBOUND, "BAD", "")
        assertTrue(readAllow(dir, "  VAR_UNBOUND\tBAD  \n").matches(f), "整行 trim（Main.kt 原口径）")
        assertFalse(readAllow(dir, "VAR_UNBOUND\t BAD\n").matches(f), "列首空格是匹配串的一部分")
        assertFalse(readAllow(dir, "var_unbound\tBAD\n").matches(f), "不做大小写折叠")
    }

    @Test
    fun `三种行尾（CRLF与LF与裸CR）同解`() {
        val f = finding(FindingKind.VAR_UNBOUND, "BAD", "")
        for ((tag, eol) in listOf("CRLF" to "\r\n", "LF" to "\n", "CR" to "\r")) {
            val text = "# 注释$eol" + "VAR_UNBOUND\tBAD$eol"
            assertTrue(readAllow(dir, text).matches(f), "$eol 行尾（$tag）下必须照常放行")
        }
    }

    // -------------------------------------------------------------------------
    // 装置
    // -------------------------------------------------------------------------

    /** 与 Rust 侧 `cli_surface_diff` 的 `allow-nonutf8.tsv` 同一份坏档（合法行 + 非法字节）。 */
    private val BAD_ALLOW_BYTES: ByteArray = run {
        val head = "VAR_UNBOUND\tNOPE\r\n".toByteArray(Charsets.UTF_8)
        val bad = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00.toByte()) +
            "bad\t".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0xFF.toByte()) +
            "\r\n".toByteArray()
        head + bad
    }

    private fun writeBytes(name: String, bytes: ByteArray): Path {
        val p = dir.resolve(name)
        Files.createDirectories(p.parent)
        Files.write(p, bytes)
        return p
    }

    private fun Path.readAllBytesAsUtf8(): ByteArray = Files.readAllBytes(this)

    /** 解析形状用的装置：白名单文本直接喂 [readAllowList]（path 只参与告警文案）。 */
    private fun readAllow(at: Path, text: String): AllowList {
        var allowed: AllowList? = null
        captureStderr { allowed = readAllowList(at, text) }
        return allowed!!
    }

    private fun finding(kind: FindingKind, name: String, functionKey: String): Finding =
        Finding(
            kind = kind,
            file = "A.ERB",
            functionKey = functionKey,
            line = 1,
            name = name,
            detail = "",
            occurrences = 1,
        )

    /** stderr 捕获（UTF-8 显式编码：测试 JVM 的默认字符集在本机是 cp936，不能靠默认值比字节）。 */
    private fun captureStderr(block: () -> Unit): String {
        val saved = System.err
        val buf = ByteArrayOutputStream()
        val ps = PrintStream(buf, true, "UTF-8")
        System.setErr(ps)
        try {
            block()
        } finally {
            ps.flush()
            System.setErr(saved)
        }
        return String(buf.toByteArray(), Charsets.UTF_8).replace("\r\n", "\n")
    }
}
