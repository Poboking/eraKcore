package com.eratw.port.core.data.csv

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `;!;` / `;^;` 是 `LexicalAnalyzer.SkipWhiteSpace` 里的**跳过标记**，不是注释
 * （LexicalAnalyzer.cs:662-676：`st.Jump(3); continue;`），其后的内容照常构成本行正文。
 *
 * 移植侧此前只在 `readEnabledLines` 里判「行首 `;` → 整行丢弃」，会把 `;!;1,name` 这类
 * **有效数据行**误判成注释（R7 审查 2026-10-26）。下方逐条钉住与 C# 同解的三态：
 * - 标记前缀 ⇒ 剥掉标记后当正文（连同标记后的空白一起跳过）；
 * - 裸 `;` 前缀 ⇒ 注释，整行丢弃；
 * - 全标记无正文 ⇒ 空行丢弃（C# SkipWhiteSpace 后 EOS）。
 */
class CsvEnabledLineTest {

    private fun write(text: String): Path {
        val p = Files.createTempFile("eratw-csv-marker", ".csv")
        Files.write(p, text.toByteArray(Charsets.UTF_8))
        return p
    }

    @Test
    fun `行首 分号感叹号分号 是跳过标记而非注释`() {
        val p = write(
            """
            0,a
            ;!;1,b
            2,c
            """.trimIndent(),
        )
        assertEquals(
            listOf("0,a", "1,b", "2,c"),
            CsvLoader.readEnabledLines(p),
            ";!;1,b 剥掉标记后是正文（LexicalAnalyzer.cs:664-670）",
        )
    }

    @Test
    fun `行首 分号脱字符分号 同样按正文处理`() {
        val p = write(";^;1,b")
        assertEquals(listOf("1,b"), CsvLoader.readEnabledLines(p))
    }

    @Test
    fun `标记前的空白不影响识别`() {
        val p = write("   ;!;1,b")
        assertEquals(listOf("1,b"), CsvLoader.readEnabledLines(p))
    }

    @Test
    fun `标记之后仍有空白也一并跳过`() {
        val p = write(";!; 1,b")
        assertEquals(listOf("1,b"), CsvLoader.readEnabledLines(p))
    }

    @Test
    fun `裸分号仍是注释整行丢弃`() {
        val p = write(
            """
            0,a
            ;这是注释
              ;缩进的注释
            1,b
            """.trimIndent(),
        )
        assertEquals(listOf("0,a", "1,b"), CsvLoader.readEnabledLines(p))
    }

    @Test
    fun `只有标记没有正文的行丢弃`() {
        val p = write(
            """
            0,a
            ;!;
            ;^;
            1,b
            """.trimIndent(),
        )
        assertEquals(listOf("0,a", "1,b"), CsvLoader.readEnabledLines(p))
    }

    @Test
    fun `标记后只剩一个裸分号等价于注释丢弃`() {
        val p = write(
            """
            0,a
            ;!;;
            1,b
            """.trimIndent(),
        )
        assertEquals(listOf("0,a", "1,b"), CsvLoader.readEnabledLines(p))
    }

    @Test
    fun `正文中的标记字符不被特殊处理`() {
        val p = write("0,a;!;b")
        assertEquals(
            listOf("0,a;!;b"),
            CsvLoader.readEnabledLines(p),
            "标记只在行首空白集里生效，字段内部原样（Split(',') 之后是内容）",
        )
    }
}