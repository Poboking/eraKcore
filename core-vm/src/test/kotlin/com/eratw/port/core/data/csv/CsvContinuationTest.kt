package com.eratw.port.core.data.csv

import com.eratw.port.core.err.CodeEE
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * SAVE-10：CSV 家族的 `{`/`}` 续行与 `[[ ]]` 改名（`EraStreamReader.ReadEnabledLine`，
 * EraStreamReader.cs:96-187）。
 *
 * 原文顺序（缺一不可）：
 * 1. 读物理行 → `useRename` 且命中 `\[\[.*?\]\]` 时替换（:106-119）；
 * 2. 空白行/注释行在**非续行上下文**里被 SkipWhiteSpace 归约后跳过（:124-129）；
 * 3. 行首 `}` → `UnexpectedContinuationEnd`（:130-134）；
 * 4. 行首 `{` 且整行 trim 后恰为 `{` → 进入续行块（:136-141）；
 * 5. 块内：`}` 行 trim 两端后必须恰为 `}`（:163-171）；单独 `{` → `UnexpectedContinuation`（:172-175）；
 *    EOF → `NotCloseLineContinuation`（:145-150）；其余物理行**原样** + 分隔符追加（:178-180）。
 *
 * 分隔符 = `Config.ReplaceContinuationBR` 去掉 `"`；本作 `emuera.config:72` = `" "` ⇒ 一个半角空格，
 * 因此合并结果**带尾随空格**（原版同样如此，后续 `Split(',')` 会把它留在最后一个字段里）。
 * 文案取 `lang.emuera-zhs.xml:835-839`。
 */
class CsvContinuationTest {

    private fun write(text: String): Path {
        val p = Files.createTempFile("eratw-csv-cont", ".csv")
        Files.write(p, text.toByteArray(Charsets.UTF_8))
        return p
    }

    @Test
    fun `行首花括号把后续物理行拼成一条逻辑行`() {
        val p = write(
            """
            0,head
            {
            1,body
            2,tail
            }
            3,after
            """.trimIndent(),
        )
        assertEquals(
            listOf("0,head", "1,body 2,tail ", "3,after"),
            CsvLoader.readEnabledLines(p),
            "块内每行原样拼接并带一个尾随分隔符（EraStreamReader.cs:178-180）",
        )
    }

    @Test
    fun `单独的行首右花括号抛错`() {
        val p = write(
            """
            0,a
            }
            """.trimIndent(),
        )
        val e = assertFailsWith<CodeEE> { CsvLoader.readEnabledLines(p) }
        assertEquals("意料之外的行连接结束符号'}'（第2行）", e.message)
    }

    @Test
    fun `开始行夹带其它文字抛错`() {
        val p = write("{ 0,a")
        val e = assertFailsWith<CodeEE> { CsvLoader.readEnabledLines(p) }
        assertEquals("行连接开始符号'{'所在的行不能包含'{'以外的文字（第1行）", e.message)
    }

    @Test
    fun `结束行夹带其它文字抛错`() {
        val p = write(
            """
            {
            1,a
            } x
            """.trimIndent(),
        )
        val e = assertFailsWith<CodeEE> { CsvLoader.readEnabledLines(p) }
        assertEquals("行连接结束符号'}'所在的行不能包含'}'以外的文字（第3行）", e.message)
    }

    @Test
    fun `块内再次出现单独的花括号开始行抛错`() {
        val p = write(
            """
            {
            1,a
            {
            }
            """.trimIndent(),
        )
        val e = assertFailsWith<CodeEE> { CsvLoader.readEnabledLines(p) }
        assertEquals("意料之外的行连接开始符号'{'（第3行）", e.message)
    }

    @Test
    fun `文件结束仍未闭合抛错`() {
        val p = write(
            """
            {
            1,a
            """.trimIndent(),
        )
        val e = assertFailsWith<CodeEE> { CsvLoader.readEnabledLines(p) }
        assertEquals("使用了行连接开始符号'{'但没有找到结束符号'}'（第2行）", e.message)
    }

    @Test
    fun `结束行两端空白不影响识别`() {
        val p = write(
            """
              {
            1,a
              }   
            """.trimIndent(),
        )
        assertEquals(listOf("1,a "), CsvLoader.readEnabledLines(p))
    }

    @Test
    fun `双括号改名只在 useRename 为真时生效`() {
        val p = write("0,[[OLD]],1")
        assertEquals(listOf("0,[[OLD]],1"), CsvLoader.readEnabledLines(p), "CSV 生产路径 useRename=false")
        assertEquals(
            listOf("0,NEW,1"),
            CsvLoader.readEnabledLines(p, useRename = true, renameDic = mapOf("[[OLD]]" to "NEW")),
        )
    }

    @Test
    fun `续行块内的改名同样生效`() {
        val p = write(
            """
            {
            1,[[OLD]]
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf("1,NEW "),
            CsvLoader.readEnabledLines(p, useRename = true, renameDic = mapOf("[[OLD]]" to "NEW")),
            "块内 readLine 走同一条改名分支（EraStreamReader.cs:152-161）",
        )
    }
}
