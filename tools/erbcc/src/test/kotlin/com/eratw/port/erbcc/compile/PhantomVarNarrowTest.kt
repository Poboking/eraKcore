package com.eratw.port.erbcc.compile

import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.LineParser
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

/**
 * BOARD 红 A 回归钉子（CLI 侧）：幽灵变量抢下标 —— 预扫描登记面的收窄史。
 *
 * 根因（2026-10-22 真机开局红 A）：旧预扫描把任何含 `:` 的行里 `IDENT:` 形态
 * 补登记成全局 INT 变量。两语料例：
 * ① `LOCALS = 氷精:Strongest Fairy:…`（魔改内容/AnonTW搬运口上函数库.ERB:151）
 *    —— STR 赋值右值整段是 FORM 串（spec: ArgumentParser.cs:1024-1041
 *    AnalyseFormattedString → ToStrFormTerm），冒号不是下标，`氷精` 却被登记；
 * ② `PRINTFORML ❤积攒度:{CFLAG:ARG:积攒度,4,right}❤…`
 *    （コマンド関連/COMF/外出系/NASIKUZUSI.ERB:78）—— FORM 串插值位同样不是声明点，
 *    `积攒度` 被登记后抢走 `CFLAG:59:积攒度 = MAX(…)` 的第三下标
 *    （口上・メッセージ関連/個人口上/059 Koakuma [小悪魔]/小悪魔長髪短髪切り替え/
 *    M_KOJO_K59_1_イベント.ERB:73）。
 * 幽灵变量在解析期命中变量表（spec: ExpressionParser.cs:395-403 优先于关键字表
 * :409-412），把关键字下标退化成 VarRef，运行期注册表没有该变量即报
 * 「未定义的变量 氷精 / 积攒度」。
 *
 * 收窄史（本文件钉终态）：
 * - 60f8ed7 —— STR 裸文本 RHS 不再预扫冒号（isBareStringAssignment）；
 * - abf4bb45 —— 兜底登记收窄为语句级赋值左侧（assignmentDest/registerDest）；
 * - e802b8ba —— 兜底登记整体删除：原版没有「裸用即声明」，唯一登记入口是
 *   `#DIM`（spec: ErhLoader.cs:249-268 analyzeSharpDimLines →
 *   IdentifierDictionary.cs:394-399 AddUseDefinedVariable）；查找侧
 *   GetVariableToken 是纯查（spec: IdentifierDictionary.cs:429-495，不中返回 null），
 *   未定义标识符的归宿是 ThrowException（spec: ExpressionParser.cs:444-445）。
 *
 * 与 CorpusScanPhantomTest / CorpusScanSubscriptTest 的分工：那两个文件各钉一例，
 * 本文件扩大覆盖面 —— 赋值左值形态、纯读取/字符串位形态、第二例的关键字位保持，
 * 以及「显式 #DIM 正路不受收窄影响」的对照钉。core-vm 同口径由 SubscriptKeywordTest
 * 钉住（CorpusValidator.prescan）。
 */
class PhantomVarNarrowTest {

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
    fun `赋值左值形态也不登记幽灵变量`(@TempDir dir: Path) {
        // 赋值左侧是「最像声明」的形态：abf4bb45 曾只保留这一路兜底，e802b8ba 起整体删除。
        // 旧实现（60f8ed7 之前）对这三种形态全部补登记为 INT 全局变量。
        val f = dir.resolve("dest.ERB")
        f.writeText(
            """
            @F
            	口:0 = 1
            	集合:人数:0 = 9
            	UNK_SYSVAR:1 = 3
            """.trimIndent() + "\n",
        )
        val scan = CorpusScan.scan(listOf(f))

        // 原版口径：未声明左值 = 装载期「未定义的标识符」（spec: ExpressionParser.cs:444-445），
        // 不是静默声明点；注册表唯一入口是 #DIM（spec: ErhLoader.cs:249-268）。
        assertNull(scan.global.resolveVariable("口", null), "函数内私有名 口 不得登记成全局变量")
        assertNull(scan.global.resolveVariable("集合", null), "多级下标左值 集合:人数:0 同理")
        assertNull(scan.global.resolveVariable("人数", null), "左值下标链中间的名同样不是声明点")
        assertNull(scan.global.resolveVariable("UNK_SYSVAR", null), "未声明系统变量名不得兜底登记")

        // 装载期解析侧同口径报错：未定义左值过不了 reduceIdentifier（ExprParseException）。
        val parser = LineParser(CompileResolver(scan))
        parseLine(parser, f, 0, "@F")
        assertFailsWith<com.eratw.port.core.stmt.StmtParseException>(
            "未声明的多级下标左值应在装载期解析失败（原版 CodeEE 未定义的标识符）",
        ) {
            parseLine(parser, f, 2, "\t集合:人数:0 = 9")
        }
    }

    @Test
    fun `纯读取与字符串位的冒号不是下标`(@TempDir dir: Path) {
        // 红 A 两语料例的登记源形状 + abf4bb45 注释点名的比较表达式形态。
        val scan = scanOne(
            dir,
            "read.ERB",
            """
            @F
            	LOCALS = 氷精:Strongest Fairy:Unnatural Chill:Ice Spirit of the Lake
            	IF ZZGHOST == QQGHOST:1
            	ENDIF
            	PRINTFORML ❤积攒度:{CFLAG:59:积攒度,4,right}❤绝顶:{EXP:ARG:絶頂経験}
            	TALENT:ARG:氷精 = 0
            """.trimIndent() + "\n",
        )

        // ① STR 赋值 RHS 整段是 FORM 串（spec: ArgumentParser.cs:1024-1041），
        // 裸文本里的「名:值」冒号不是下标（AnonTW搬运口上函数库.ERB:151）。
        assertNull(scan.global.resolveVariable("氷精", null), "STR 裸文本 RHS 的冒号前名被误登记")
        assertNull(scan.global.resolveVariable("Fairy", null), "「Fairy:Unnatural」的冒号同样不是下标")
        // ② 比较表达式里的冒号不是下标（abf4bb45 点名的 `IF A == B:1` 形态）。
        assertNull(scan.global.resolveVariable("QQGHOST", null), "比较运算右侧的下标名不构成声明点")
        assertNull(scan.global.resolveVariable("ZZGHOST", null), "比较运算左侧同理")
        // ③ FORM 串插值位（NASIKUZUSI.ERB:78）—— 消费点 M_KOJO_K59_1_イベント.ERB:73。
        assertNull(scan.global.resolveVariable("积攒度", null), "FORM 串插值位不是声明点")
        assertNull(scan.global.resolveVariable("絶頂経験", null), "FORM 串插值位同理")
    }

    @Test
    fun `第二例_积攒度关键字位保持KeywordIndexArg`(@TempDir dir: Path) {
        // 幽灵变量缺席时，CFLAG 的第二下标按原版回落序走到关键字表
        // （spec: ExpressionParser.cs:409-412 → VariableParser.cs:182-188 字符串下标包装），
        // 保持 KeywordIndexArg 形态；若 积攒度 被幽灵登记，变量表优先序
        // （spec: ExpressionParser.cs:395-403）会把它退化成 VarRef，运行期「未定义的变量」。
        val f = dir.resolve("cf.ERB")
        f.writeText(
            """
            @F
            	CFLAG:59:积攒度 = 0
            """.trimIndent() + "\n",
        )
        val scan = CorpusScan.scan(listOf(f))
        assertNull(scan.global.resolveVariable("积攒度", null), "幽灵变量缺席才有后面的关键字位")

        val parser = LineParser(CompileResolver(scan))
        parseLine(parser, f, 0, "@F")
        val stmt = assertIs<AssignStmt>(parseLine(parser, f, 1, "\tCFLAG:59:积攒度 = 0"))

        // CFLAG 是 1 维角色变量（BuiltinSymbols），完整下标 = [角色, 元素]
        assertEquals("CFLAG", stmt.dest.name)
        assertEquals(2, stmt.dest.indices.size)
        val kw = assertIs<KeywordIndexArg>(stmt.dest.indices[1], "元素下标必须保留关键字形态而非 VarRef")
        assertEquals("CFLAG", kw.varName)
        assertEquals(1, kw.dim)
        assertEquals("积攒度", assertIs<StrLiteral>(kw.expr).value)
    }

    @Test
    fun `显式井DIM声明照旧登记`(@TempDir dir: Path) {
        // 对照钉：收窄只砍幽灵兜底，没砍 #DIM 正路 —— 原版唯一登记入口
        // （spec: ErhLoader.cs:249-268 analyzeSharpDimLines → IdentifierDictionary.cs:394-399）。
        val scan = scanOne(
            dir,
            "dim.ERB",
            """
            #DIM MYVAR, 2, 3
            @F
            	MYVAR:0:1 = 3
            """.trimIndent() + "\n",
        )

        val myvar = assertNotNull(scan.global.resolveVariable("MYVAR", null), "#DIM 正路登记不受收窄影响")
        assertEquals(OperandType.INT, myvar.operandType)
        assertEquals(2, myvar.dimension, "两个逗号后尺寸 = 2 维")
    }
}
