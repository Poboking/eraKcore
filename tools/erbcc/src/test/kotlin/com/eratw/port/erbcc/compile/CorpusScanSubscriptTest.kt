package com.eratw.port.erbcc.compile

import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.LineParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

/**
 * 预扫描**不产生幽灵变量**：裸字符串值与赋值左值都不登记。
 *
 * 原版没有「裸用即声明」：reduceIdentifier 先查变量表（spec: ExpressionParser.cs:395-403），
 * 不中再查该变量的关键字/常量表（:409-412 ConstantData.isDefined、:413-443 isUserDefined），
 * 两处都不中即 IdentifierDictionary.ThrowException（:444-445）。且字符串变量的 `=` 赋值
 * 右侧整个是 FORM 串（spec: ArgumentParser.cs:1024-1041 AnalyseFormattedString →
 * ToStrFormTerm），裸文本里的「名:值」冒号不是下标 —— 旧实现把
 * `LOCALS = 氷精:Strongest Fairy:…`（魔改内容/AnonTW搬运口上函数库.ERB:151）里的
 * 「氷精」登记成幽灵全局变量，`TALENT:ARG:氷精` 的第三下标在解析期命中变量表
 * （ExpressionParser.cs:395-403 优先于关键字表 :409-412）退化成 VarRef，运行期注册表
 * 没有该变量即报「未定义的变量 氷精」（2026-10-22 真机开局红 A）。
 *
 * **赋值左值同样不登记**（2026-10-23 link audit 收口）：旧 `registerDest` 把函数内的
 * `口:0 =` / `集合:人数:0 =` 登记成全局 1D/3D 变量，随后 `STAIN:…:口` /
 * `TALENT:…:集合` 的关键字位被变量表抢走（同一优先序），运行期两处都是
 * 「未定义的变量」。原版对未声明左值就是在装载期抛「未定义的标识符」。
 *
 * 与 core-vm 装置 CorpusValidator.prescan 的同名口径对齐（core-vm 侧由
 * SubscriptKeywordTest 钉住）；本组钉住 erbcc CLI 侧的 CorpusScan。
 */
class CorpusScanSubscriptTest {

    private fun scanOne(dir: Path, name: String, text: String): CorpusScan {
        val f = dir.resolve(name)
        f.writeText(text)
        return CorpusScan.scan(listOf(f))
    }

    private fun parseLine(p: LineParser, path: Path, lineNo: Int, text: String) =
        p.parseLogicalLine(
            LogicalLine(
                when {
                    text.startsWith("@") -> LineKind.FUNCTION_LABEL
                    text.startsWith("$") -> LineKind.GOTO_LABEL
                    text.startsWith("#") -> LineKind.SHARP
                    else -> LineKind.CODE
                },
                path.toString(),
                lineNo,
                text,
            ),
        )

    @Test
    fun 裸字符串表不产生幽灵登记(@TempDir dir: Path) {
        val scan = scanOne(
            dir,
            "a.ERB",
            """
            @F
            	LOCALS = 氷精:Strongest Fairy:Unnatural Chill:Ice Spirit of the Lake
            	TALENT:ARG:氷精 = 0
            	IF ZZGHOST == QQGHOST:1
            	ENDIF
            """.trimIndent() + "\n",
        )
        // `氷精:` 只是赋值右侧的裸字符串值，原版不构成声明点（ExpressionParser.cs:444-445 才是归宿）
        assertNull(scan.global.resolveVariable("氷精", null), "裸字符串值里的「名:」被误登记成变量")
        assertNull(scan.global.resolveVariable("Strongest", null), "裸字符串值里的其它裸词同理")
        assertNull(scan.global.resolveVariable("Fairy", null), "「Fairy:Unnatural」的冒号同样不是下标（旧实现会登记）")
        assertNull(scan.global.resolveVariable("QQGHOST", null), "比较运算 == 右侧的下标名不构成声明点")
    }

    @Test
    fun 下标关键字保持KeywordIndexArg(@TempDir dir: Path) {
        val f = dir.resolve("b.ERB")
        f.writeText(
            """
            @F
            	LOCALS = 氷精:Strongest Fairy:Unnatural Chill
            	TALENT:ARG:氷精 = 0
            """.trimIndent() + "\n",
        )
        val scan = CorpusScan.scan(listOf(f))
        val parser = LineParser(CompileResolver(scan))
        parseLine(parser, f, 0, "@F")
        val stmt = assertIs<AssignStmt>(parseLine(parser, f, 2, "\tTALENT:ARG:氷精 = 0"))

        // TALENT 是 1 维角色变量（BuiltinSymbols），完整下标 = [角色, 元素]
        // spec: VariableParser.cs:65-190（下标补全）+ :182-188（字符串下标包装）
        assertEquals("TALENT", stmt.dest.name)
        assertEquals(2, stmt.dest.indices.size)
        assertIs<VarRef>(stmt.dest.indices[0])
        val kw = assertIs<KeywordIndexArg>(stmt.dest.indices[1], "第三下标必须保留关键字形态而非 VarRef")
        assertEquals("TALENT", kw.varName)
        assertEquals(1, kw.dim)
        assertEquals("氷精", assertIs<StrLiteral>(kw.expr).value)
    }

    @Test
    fun 未声明赋值目标不再补登记(@TempDir dir: Path) {
        // 原版口径：未声明左值 = 装载期「未定义的标识符」，不是静默声明点。
        val f = dir.resolve("c.ERB")
        f.writeText(
            """
            @F
            	UNK_SYSVAR:1 = 3
            """.trimIndent() + "\n",
        )
        val scan = CorpusScan.scan(listOf(f))
        assertNull(scan.global.resolveVariable("UNK_SYSVAR", null), "赋值目标不得再被兜底登记")

        val parser = LineParser(CompileResolver(scan))
        parseLine(parser, f, 0, "@F")
        assertFailsWith<com.eratw.port.core.stmt.StmtParseException> {
            parseLine(parser, f, 1, "\tUNK_SYSVAR:1 = 3")
        }
    }

    @Test
    fun 语料实况_裸字符串表不登记氷精() {
        // 语料门同款环境变量；缺省时跳过（与其它语料依赖用例口径一致）
        val corpus = System.getenv("ERATW_CORPUS") ?: return
        val f = Path.of(corpus, "魔改内容", "AnonTW搬运口上函数库.ERB")
        if (!Files.isRegularFile(f)) return
        val scan = CorpusScan.scan(listOf(f))
        assertNull(scan.global.resolveVariable("氷精", null), "151 行的裸字符串表被误登记成全局变量")
    }
}
