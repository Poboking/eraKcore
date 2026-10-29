package com.eratw.port.core.lexer

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.stmt.BlockChecker
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.StmtParseException
import com.eratw.port.core.stmt.SymbolTable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 文案渲染层回归 —— `enum LangKey` / `LexError` 的键构造 / `specs/error-texts.yaml` 对账。
 *
 * 钉住的契约：
 * 1. 每个键的 ja/zhs 模板里的占位符个数 = [LangKey.argCount]，渲染按序号原位替换；
 * 2. 语言口径：内核档 [LangKey.renderJa] 取 `Lang.cs` 日文原文，使用者面 [LangKey.render] 取
 *    zhs 译文（口径见 [LangKey] 类头）；
 * 3. `specs/error-texts.yaml` 与枚举双向一致（id 集合、两侧模板、占位符个数、Lang.cs 与
 *    zhs xml 的行号）—— 表或代码任何一侧漂了都红；
 * 4. `BlockChecker` 的 6 类块错误全部落到官方 zhs 原文，且不再把 `spec: …` 一类的工程线索
 *    写进屏上文案；
 * 5. `ArgBuilders` 的 warn/fail 统一带「语句名 + 命令:」前缀（`ArgumentBuilder.cs:32`）。
 *
 * 本文件只测「文案面」，不重复 `ErrorTextAuditTest`（core/err）对 CodeEE 家族与 zhs 表的对拍。
 */
class ErrorTextRenderTest {

    // ------------------------------------------------------------------
    // 一、模板与渲染
    // ------------------------------------------------------------------

    private val placeholder = Regex("\\{(\\d+)}")

    @Test
    fun `每个键的占位符个数与 argCount 一致`() {
        for (key in LangKey.entries) {
            val indices = placeholder.findAll(key.templateJa)
                .map { it.groupValues[1].toInt() }
                .toSet()
            val indicesZhs = placeholder.findAll(key.templateZhs)
                .map { it.groupValues[1].toInt() }
                .toSet()
            assertEquals(indices, indicesZhs, "${key.name} 的 ja/zhs 占位符下标必须同形")
            assertEquals(
                key.argCount,
                (indices.maxOrNull() ?: -1) + 1,
                "${key.name} 的 argCount=${key.argCount}，模板里的最大占位符是 ${indices.maxOrNull()}",
            )
        }
    }

    @Test
    fun `渲染按序号原位替换且保留原文标点`() {
        assertEquals(
            "缺少\"ENDIF\"对应的\"IF\"",
            LangKey.MissingCorresponding.render("ENDIF", "IF"),
        )
        assertEquals(
            "在SELECTCASE语法的分支以外包含命令\"PRINTL\"",
            LangKey.OutsideSelectcase.render("PRINTL"),
        )
        assertEquals(
            "词法解析中出现意料之外的字符'&'",
            LangKey.UnexpectedCharacter.render('&'),
        )
        // 0 参键不受额外实参影响 —— 但多给实参属于调用点写错，见下一条。
        assertEquals("缺少参数", LangKey.MissingArg.render())
        // 空的 [[]] 里没有占位符，不能被当成 {0}
        assertTrue(LangKey.EmptyTwoSBrackets.render() == "空的[[]]")
    }

    @Test
    fun `实参个数不符直接抛而不是静默补空`() {
        assertFailsWith<IllegalArgumentException> { LangKey.MissingArg.render("多余") }
        assertFailsWith<IllegalArgumentException> { LangKey.CanNotRecognizeArg.render() }
        assertFailsWith<IllegalArgumentException> { LangKey.InstructionNotClosed.render("A", "B") }
    }

    @Test
    fun `内核档取日文原文 使用者面取 zhs`() {
        assertEquals("引数が設定されていません", LangKey.MissingArg.renderJa())
        assertEquals("缺少参数", LangKey.MissingArg.render())
        assertEquals("缺少参数", LangKey.MissingArg.renderZhs())
        assertEquals(
            "第{0}引数に変数以外を指定することはできません".replace("{0}", "1"),
            LangKey.ArgIsNotVariable.renderJa(1),
        )
        // zhs 表的官方误译照抄：日文是「没有对应 IF 的 ENDIF」，中文表写成「缺少IF对应的ENDIF」。
        assertEquals("対応するIFの無いENDIF文です", LangKey.UnexpectedEndif.renderJa())
        assertEquals("缺少IF对应的ENDIF", LangKey.UnexpectedEndif.renderZhs())
    }

    @Test
    fun `ArgumentBuilder 前缀是语句名加命令冒号`() {
        assertEquals("SAVECHARA命令:", instructionPrefix("SAVECHARA"))
        assertEquals("命令:", LangKey.Instruction.renderZhs())
        assertEquals("命令:", LangKey.Instruction.renderJa())
    }

    // ------------------------------------------------------------------
    // 二、LexError / LexWarning 的键构造
    // ------------------------------------------------------------------

    @Test
    fun `LexError 键构造保留 key 与实参且 message 是日文展开`() {
        val e = LexError(LangKey.CanNotRenameKey, "&")
        assertEquals(LangKey.CanNotRenameKey, e.key)
        assertEquals(listOf<Any?>("&"), e.args)
        assertEquals("字句解析中に置換(rename)できない符号&を発見しました", e.message)
        // 主构造（成品串）照旧：key 为 null、message 一字不改。
        val raw = LexError("使用不正确的文字作为行的开始", lineNo = 3, column = 7)
        assertNull(raw.key)
        assertEquals("使用不正确的文字作为行的开始", raw.message)
        assertEquals(3, raw.lineNo)
        assertEquals(7, raw.column)
    }

    @Test
    fun `LexError 带位置的键构造保持 0 基行号`() {
        val e = LexError.at(LangKey.UnexpectedCharacter, "a.erb", 12, 4, '&')
        assertEquals("字句解析中に予期しない文字'&'を発見しました", e.message)
        assertEquals("a.erb", e.filename)
        assertEquals(12, e.lineNo)
        assertEquals(4, e.column)
        assertEquals(LangKey.UnexpectedCharacter, e.key)
    }

    @Test
    fun `LexWarning 键构造走日文原文并存键`() {
        val w = LexWarning.at(LangKey.MacroOverLimit, "a.erb", 5, 1, 32)
        assertEquals("マクロの展開数が1文あたりの上限値32を超えました(自己参照・循環参照のおそれ)", w.message)
        assertEquals(LangKey.MacroOverLimit, w.key)
        assertEquals(listOf<Any?>(32), w.args)
        // 旧的四参构造（调用方成品串）不变。
        val raw = LexWarning("msg", "b.erb", 9, 0)
        assertNull(raw.key)
        assertEquals("msg", raw.message)
    }

    // ------------------------------------------------------------------
    // 三、specs/error-texts.yaml 双向对账
    // ------------------------------------------------------------------

    @Test
    fun `文案表与枚举双向一致`() {
        val rows = loadSpecRows()
        assertEquals(
            LangKey.entries.map { it.name }.toSet(),
            rows.keys,
            "specs/error-texts.yaml 的 id 集合必须与 LangKey 一一对应",
        )
        for (key in LangKey.entries) {
            val row = rows.getValue(key.name)
            assertEquals(key.templateJa, row.ja, "${key.name}.ja 与 Lang.cs:${key.langCsLine} 不一致")
            assertEquals(key.templateZhs, row.zhs, "${key.name}.zhs 与 zhs xml:${key.zhsXmlLine} 不一致")
            assertEquals(key.argCount, row.args, "${key.name}.args")
            assertEquals(key.langCsLine, row.langCs, "${key.name}.lang_cs")
            assertEquals(key.zhsXmlLine, row.zhsXml, "${key.name}.zhs_xml")
            assertTrue(row.status == "wired" || row.status == "registered", "${key.name}.status=${row.status}")
        }
    }

    private data class SpecRow(
        val ja: String,
        val zhs: String,
        val args: Int,
        val langCs: Int,
        val zhsXml: Int,
        val status: String,
    )

    /** 只认本表自己的固定格式（每键一段、块标量单行/多行），不引第三方 YAML 依赖。 */
    private fun loadSpecRows(): Map<String, SpecRow> {
        val file = listOf(
            "../specs/error-texts.yaml", "specs/error-texts.yaml", "../../specs/error-texts.yaml",
        ).map { File(it) }.firstOrNull { it.isFile }
            ?: error("找不到 specs/error-texts.yaml（工作目录 ${File(".").absolutePath}）")

        val records = mutableListOf<MutableMap<String, String>>()
        var cur: MutableMap<String, String>? = null
        var blockKey: String? = null
        val block = StringBuilder()
        fun flushBlock() {
            val k = blockKey ?: return
            cur?.set(k, block.toString())
            blockKey = null
            block.setLength(0)
        }
        for (line in file.readLines()) {
            if (line.startsWith("  - id: ")) {
                flushBlock()
                val m = linkedMapOf<String, String>()
                m["id"] = line.removePrefix("  - id: ").trim()
                records.add(m)
                cur = m
                continue
            }
            if (blockKey != null) {
                if (line.startsWith("      ")) {
                    if (block.isNotEmpty()) block.append('\n')
                    block.append(line.substring(6))
                    continue
                }
                flushBlock()
            }
            val fm = Regex("^    ([a-z_]+): (.*)$").find(line) ?: continue
            val k = fm.groupValues[1]
            val v = fm.groupValues[2]
            if (v == "|-") {
                blockKey = k
            } else {
                cur?.set(k, v)
            }
        }
        flushBlock()

        return records.associate { m ->
            m.getValue("id") to SpecRow(
                ja = m.getValue("ja"),
                zhs = m.getValue("zhs"),
                args = m.getValue("args").toInt(),
                langCs = m.getValue("lang_cs").toInt(),
                zhsXml = m.getValue("zhs_xml").toInt(),
                status = m.getValue("status"),
            )
        }
    }

    // ------------------------------------------------------------------
    // 四、BlockChecker：6 类块错误的官方 zhs 文案（且不泄露工程线索）
    // ------------------------------------------------------------------

    private fun parser(): LineParser =
        LineParser(SymbolTable().declareVariable("A", OperandType.INT, 0))

    private fun stmts(vararg src: String): List<Statement> = src.mapIndexed { i, text ->
        parser().parseLogicalLine(LogicalLine(LineKind.CODE, "block.erb", i, text))!!
    }

    private fun blockerTexts(vararg src: String): List<String> =
        BlockChecker().check(stmts(*src)).map { it.message }

    @Test
    fun `块错误六类文案逐字等于官方 zhs`() {
        // 1) CATCH 无 TRYC 系（ErbLoader.cs:1343）
        assertEquals(listOf("缺少对应的TRYC系统命令"), blockerTexts("CATCH"))
        // 2) SELECTCASE 与第一个 CASE 之间出现普通指令（ErbLoader.cs:1100）
        assertEquals(
            listOf("在SELECTCASE语法的分支以外包含命令\"PRINTL\""),
            blockerTexts("SELECTCASE A", "PRINTL BAD", "CASE 1", "ENDSELECT"),
        )
        // 3) 文件结束时仍有未闭合块（ErbLoader.cs:1568，实参顺序 = 期望的结束语句, 开语句）
        assertEquals(listOf("缺少\"ENDIF\"对应的\"IF\""), blockerTexts("IF A"))
        // 4) ELSE/ELSEIF 无 IF（ErbLoader.cs:1214）
        assertEquals(listOf("在IF-ENDIF以外使用了\"ELSE\""), blockerTexts("ELSE"))
        // 5) 结束语句无母块 / 与栈顶错配（ErbLoader.cs:1229/1272/1353/1332）
        assertEquals(listOf("缺少IF对应的ENDIF"), blockerTexts("ENDIF"))
        // zhs 模板自带半角直引号：缺少"{0}"对应的"{1}"
        assertEquals(listOf("缺少\"REPEAT\"对应的\"REND\""), blockerTexts("REND"))
        assertTrue(
            blockerTexts("SELECTCASE A", "CASE 1", "ENDIF").contains("缺少IF对应的ENDIF"),
            "ENDIF 撞上未闭合的 SELECTCASE 时走 UnexpectedEndif（ErbLoader.cs:1229）",
        )
    }

    @Test
    fun `块错误文案不再把 spec 行号写给用户`() {
        val all = listOf(
            blockerTexts("CATCH"),
            blockerTexts("SELECTCASE A", "PRINTL BAD", "CASE 1", "ENDSELECT"),
            blockerTexts("IF A"),
            blockerTexts("ELSE"),
            blockerTexts("ENDIF"),
            blockerTexts("REND"),
        ).flatten()
        for (msg in all) {
            assertTrue(!msg.contains("spec"), "屏上文案不许出现工程线索 spec：$msg")
            assertTrue(!msg.contains("{0}") && !msg.contains("{1}"), "占位符必须已展开：$msg")
        }
    }

    // ------------------------------------------------------------------
    // 五、ArgBuilders：warn/fail 统一带「语句名 + 命令:」前缀
    // ------------------------------------------------------------------

    private fun argParser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("ARR", OperandType.INT, 1)
            .declareVariable("SCALAR", OperandType.INT, 0),
    )

    private fun parseArg(text: String): Statement? =
        argParser().parseLogicalLine(LogicalLine(LineKind.CODE, "args.erb", 10, text))

    private fun failMessage(text: String): String =
        assertFailsWith<StmtParseException> { parseArg(text) }.message ?: ""

    @Test
    fun `ArgBuilders fail 带命令前缀且文案是官方 zhs`() {
        // 实参不足（ArgumentBuilder.cs:51 → Lang.Error.NotEnoughArguments）
        assertTrue(
            failMessage("SAVECHARA \"a.dat\", \"memo\"").contains("SAVECHARA命令:参数过少"),
            failMessage("SAVECHARA \"a.dat\", \"memo\""),
        )
        // 未登记变量（ArgumentParser.cs:531 → ArgIsNotVariable）
        assertTrue(
            failMessage("VARSIZE NO_SUCH_VAR").contains("VARSIZE命令:第1参数必须为变量"),
            failMessage("VARSIZE NO_SUCH_VAR"),
        )
        // 缺参（ArgumentParser.cs:454/495 → MissingArg；ALIGNMENT 是 ArgType.STR 形态）
        assertTrue(
            failMessage("ALIGNMENT").contains("ALIGNMENT命令:缺少参数"),
            failMessage("ALIGNMENT"),
        )
        // 空参数位（ArgumentBuilder.cs:71 → CanNotRecognizeArg）
        assertTrue(
            failMessage("SAVECHARA \"a\", \"b\", , 0").contains("SAVECHARA命令:无法识别第3参数"),
            failMessage("SAVECHARA \"a\", \"b\", , 0"),
        )
    }

    @Test
    fun `ArgBuilders warn 带命令前缀且文案是官方 zhs`() {
        val omitted = argParser()
        omitted.parseLogicalLine(LogicalLine(LineKind.CODE, "args.erb", 10, "CLEARLINE"))
        assertTrue(
            omitted.warnings.any { it.message.contains("CLEARLINE命令:必要的参数被省略了。Emuera自动补0") },
            omitted.warnings.toString(),
        )

        val missingAfterComma = argParser()
        missingAfterComma.parseLogicalLine(LogicalLine(LineKind.CODE, "args.erb", 11, "SETBGIMAGE A, "))
        assertTrue(
            missingAfterComma.warnings.any { it.message.contains("SETBGIMAGE命令:,'后面必须存在一个参数") },
            missingAfterComma.warnings.toString(),
        )

        val tooMany = argParser()
        tooMany.parseLogicalLine(LogicalLine(LineKind.CODE, "args.erb", 12, "CLEARLINE 1, 2"))
        assertTrue(
            tooMany.warnings.any { it.message.contains("CLEARLINE命令:参数过多") },
            tooMany.warnings.toString(),
        )
    }
}
