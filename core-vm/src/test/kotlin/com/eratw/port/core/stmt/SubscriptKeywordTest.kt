package com.eratw.port.core.stmt

import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 下标位置裸标识符的归属：**关键字（字符串→索引）**，不是变量。
 *
 * 原版没有「裸用即声明」的三级查找（spec: ExpressionParser.cs:395-445）：
 * 1. 变量表命中 → VariableParser.ReduceVariable（cs:395-403）；
 * 2. 否则查**该变量自己的关键字表** → SingleStrTerm（cs:409-412），运行期
 *    ConstantData.KeywordToInteger 转索引（spec: ConstantData.cs:849-935）；
 * 3. 否则 IdentifierDictionary.ThrowException（cs:444-445，未定义标识符）。
 *
 * 因此 `LOCALS = 氷精:Strongest Fairy:…` 里的裸字符串表**不得**把 `氷精` 登记成变量，
 * 否则 `TALENT:ARG:氷精` 的第三下标会退化成 VarRef，运行期抛「未定义的变量」
 * （2026-10-21 bootSmoke 首差 @ NEWGAME/NEWGAME_CUSTOM.ERB:193）。
 */
class SubscriptKeywordTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("eratw-subscript-keyword")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun write(name: String, content: String): Path {
        val p = dir.resolve(name)
        p.writeText(content)
        return p
    }

    private fun parseLine(p: LineParser, path: Path, lineNo: Int, text: String): Statement? =
        p.parseLogicalLine(LogicalLine(kindOf(text), path.toString(), lineNo, text))

    private fun kindOf(text: String): LineKind = when {
        text.startsWith("@") -> LineKind.FUNCTION_LABEL
        text.startsWith("$") -> LineKind.GOTO_LABEL
        text.startsWith("#") -> LineKind.SHARP
        else -> LineKind.CODE
    }

    @Test
    fun `裸字符串表不得被登记为变量`() {
        // 语料实况（魔法少女/AnonTW…ERB:151，原文件按 Shift-JIS 读入后为同样形态）
        val f = write(
            "NakedStringTable.ERB",
            """
            @F
            LOCALS = 氷精:Strongest Fairy:Unnatural Chill:Ice Spirit of the Lake
            TALENT:ARG:氷精 = 0
            """.trimIndent() + "\n",
        )
        val pre = CorpusValidator.prescan(listOf(f))

        // `氷精:` 只是赋值右侧的裸字符串值，原版不构成声明点（ExpressionParser.cs:444-445 才是它的归宿）
        assertNull(pre.base.resolveVariable("氷精", null), "裸字符串值里的 `名:` 被误登记成变量")
        // 语句值里的其它裸词同理
        assertNull(pre.base.resolveVariable("Strongest Fairy", null))
    }

    @Test
    fun `下标关键字保持 KeywordIndexArg`() {
        val f = write(
            "TalentKeyword.ERB",
            """
            @F
            LOCALS = 氷精:Strongest Fairy:Unnatural Chill
            TALENT:ARG:氷精 = 0
            """.trimIndent() + "\n",
        )
        val pre = CorpusValidator.prescan(listOf(f))
        val parser = LineParser(CorpusValidator.ScopedResolver(pre))

        parseLine(parser, f, 0, "@F")
        val stmt = assertIs<AssignStmt>(parseLine(parser, f, 2, "TALENT:ARG:氷精 = 0"))

        // TALENT 是 1 维角色变量（BuiltinSymbols），完整下标 = [角色, 元素]
        // spec: VariableParser.cs:65-190（下标补全）+ :182-188（字符串下标包装）
        assertEquals("TALENT", stmt.dest.name)
        assertEquals(2, stmt.dest.indices.size)
        assertIs<VarRef>(stmt.dest.indices[0])

        val kw = assertIs<KeywordIndexArg>(stmt.dest.indices[1], "第三下标必须保留关键字形态")
        assertEquals("TALENT", kw.varName)
        assertEquals(1, kw.dim)
        assertEquals("氷精", assertIs<StrLiteral>(kw.expr).value)
    }

    @Test
    fun `语料实况 裸字符串表不登记 氷精`() {
        // 语料门同款环境变量；缺省时跳过（与 CorpusStmtTest 口径一致）
        val corpus = System.getenv("ERATW_CORPUS") ?: return
        val f = Path.of(corpus, "魔改内容", "AnonTW搬运口上函数库.ERB")
        if (!Files.isRegularFile(f)) return
        val pre = CorpusValidator.prescan(listOf(f))
        assertNull(pre.base.resolveVariable("氷精", null), "151 行的裸字符串表被误登记成全局变量")
    }

    @Test
    fun `赋值目标未声明时不再补登记`() {
        // 原版没有「裸用即声明」：`UNK_SYSVAR:1 = 3` 在装载期就是未定义标识符
        // （ExpressionParser.cs:444-445）。旧装置为了扫全语料补登记了左侧名字，
        // 结果是 `口:0 =` 一类函数内私有名抢走 STAIN/TALENT 的关键字表
        // （2026-10-23 全量 audit 的 `口`/`集合` 假红来源）。
        val f = write(
            "UnknownSysVar.ERB",
            """
            @F
            UNK_SYSVAR:1 = 3
            """.trimIndent() + "\n",
        )
        val pre = CorpusValidator.prescan(listOf(f))
        assertNull(
            pre.base.resolveVariable("UNK_SYSVAR", null),
            "赋值左值不得再被补登记成全局变量",
        )

        val parser = LineParser(CorpusValidator.ScopedResolver(pre))
        parseLine(parser, f, 0, "@F")
        val e = assertFailsWith<StmtParseException> {
            parseLine(parser, f, 1, "UNK_SYSVAR:1 = 3")
        }
        assertTrue(
            (e.message ?: "").contains("未定义的标识符"),
            "未声明赋值目标应报未定义标识符，实际：${e.message}",
        )
    }
}
