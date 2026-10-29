package com.eratw.port.core.stmt

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **内建方法（methodList）的符号分类与调用形态（A4 / 合同 §8，#11 P0-2）**
 *
 * ## 结论先行：原版**没有**「函数表 / 方法表」二分
 * `FunctionMethodCreator.methodList`（spec: FunctionMethodCreator.cs:9040-9316）是**唯一**一张内建
 * 函数/方法注册表，实测 **271 条**，与 [BuiltinSymbols.functions] 的键集逐字相同（0 差集）。
 * 这 271 条被**双注册**：
 * - 语句位：`foreach (method in FunctionMethodCreator.GetMethodList()) if (!funcDic.ContainsKey(key))
 *   funcDic.Add(key, new FunctionIdentifier(key, method.Value, methodInstruction));`
 *   （spec: FunctionIdentifier.cs:4140-4148）；
 * - 表达式位：`GetFunctionMethod(..., arguments != null)` 查 `methodDic`
 *   （spec: IdentifierDictionary.cs:566-586 → `FunctionMethodTerm`）。
 *
 * 所以把 [BuiltinSymbols.functions] 与 [BuiltinSymbols.methods] 拆成互斥两半会**同时**破坏语句位
 * 与表达式位，属回归（ADR-001：行为可见的官方语义逐位照抄）。本文件锁的是原版真正区分的
 * **调用形态**：
 *
 * | 形态 | 原版判据 | 结果 |
 * |---|---|---|
 * | `NAME(...)` 有括号 | `GetFunctionMethod(arguments != null)` → `methodDic`（:566-586） | 合法，`FunctionMethodTerm` |
 * | 裸 `NAME` 无括号 | `GetFunctionMethod(arguments == null)` **只**查 `refmethodDic`（:538-547）→ 内建方法返回 null；nameDic 判 `SystemMethod`（:170）→ :607-612 | 抛「用变量的方式使用函数名」 |
 * | 行首 `NAME args` | funcDic 命中（:4140-4148） | METHOD 语句 |
 *
 * 对应缺口台账 P0 `METHOD_AS_VARIABLE`（22 条）：移植版曾把裸内建方法名当零参函数静默取值。
 */
class BuiltinMethodSymbolTest {

    private fun parser(): LineParser = LineParser(SymbolTable())

    private fun parse(text: String): Statement =
        parser().parseLogicalLine(LogicalLine(LineKind.CODE, "A4_METHOD.ERB", 1, text))!!

    /** 取 `PRINTFORML ... {expr} ...` / `... %expr% ...` 里唯一的内插表达式。 */
    private fun exprOf(text: String): Expr {
        val st = assertIs<PrintStmt>(parse(text))
        val form = assertIs<FormStringTerm>((st.args as ExpressionArgument).term)
        return (form.parts.single { it is FormPart.Embedded } as FormPart.Embedded).ast
    }

    // -----------------------------------------------------------------------
    // 1. 名单一致性：271 条，两表必须相等（不许拆）
    // -----------------------------------------------------------------------

    @Test
    fun `内建函数表与方法表必须是同一张 methodList`() {
        // 原版只有一张表 —— 拆表 = 同时破坏 funcDic 与 methodDic 两侧
        assertEquals(
            BuiltinSymbols.functions.keys, BuiltinSymbols.methods,
            "原版 methodList 是单表双注册（FunctionIdentifier.cs:4140-4148 / IdentifierDictionary.cs:566-586），两表必须相等",
        )
        assertEquals(271, BuiltinSymbols.functions.size, "原版 methodList 条目数")
        for ((name, type) in BuiltinSymbols.functions) {
            assertTrue(BuiltinSymbols.isBuiltinMethod(name), "$name 必须是 methodList 成员")
            assertTrue(BuiltinSymbols.isBuiltinFunction(name), "$name 必须有返回类型")
            assertEquals(type, BuiltinSymbols.function(name), "$name 返回类型")
        }
    }

    @Test
    fun `语句名与内建方法名的交集恰为六个`() {
        // 原版 `if (!funcDic.ContainsKey(key))` 跳过这些名字的 METHOD 注册
        // （spec: FunctionIdentifier.cs:4142），语句位由自身 FunctionCode 胜出。
        assertEquals(
            setOf("ENCODETOUNI", "GETTIME", "POWER", "PRINTCPERLINE", "SAVENOS", "VARSIZE"),
            BuiltinSymbols.statementShadowedMethods,
        )
        // 语句位优先级：LineParser.parseCodeLine 先查 FunctionCode.BY_NAME（:105-108），
        // 再查 BuiltinSymbols.methods（:119）——与原版 `if (!funcDic.ContainsKey(key))` 同序。
        for (name in BuiltinSymbols.statementShadowedMethods) {
            assertTrue(FunctionCode.BY_NAME.containsKey(name), "$name 应是语句名")
            assertTrue(BuiltinSymbols.isBuiltinMethod(name), "$name 同时是 methodList 成员")
        }
        // 语句位确实走语句通道：非交集名字才是 METHOD
        assertIs<MethodStmt>(parse("GETCOLOR 1"))
        assertTrue("GETCOLOR" !in BuiltinSymbols.statementShadowedMethods)
    }

    // -----------------------------------------------------------------------
    // 2. 调用形态：有括号合法 / 裸名非法
    // -----------------------------------------------------------------------

    @Test
    fun `带括号的内建方法在表达式中合法`() {
        // methodDic 路径（IdentifierDictionary.cs:566-586）→ FunctionMethodTerm
        val call = assertIs<FuncCall>(exprOf("PRINTFORML X={GETCOLOR(1)}"))
        assertEquals("GETCOLOR", call.name.uppercase())
        assertEquals(OperandType.INT, call.operandType)
        // 返回字符串的方法同样合法
        assertEquals(OperandType.STR, assertIs<FuncCall>(exprOf("PRINTFORML X=%MONEYSTR(1)%")).operandType)
    }

    @Test
    fun `裸内建方法名被拒`() {
        // arguments == null 只查 refmethodDic（:538-547）→ :607-612 UseFuncLikeVar。
        // 语句层把表达式异常包成 StmtParseException（ArgBuilders.kt:40），文案保留原文。
        for (name in listOf("GETCOLOR", "MONEYSTR", "CSVNAME", "CSVCALLNAME", "GETCHARA")) {
            val e = assertFailsWith<Throwable>("裸 $name 必须被拒（原版：用变量的方式使用函数名）") {
                exprOf("PRINTFORML X={$name}")
            }
            assertContains(e.message ?: "", "用变量的方式使用函数名", message = "实际：${e.message}")
        }
        // 裸名也不许出现在赋值右值（同一个解析入口）
        assertFailsWith<Throwable> { parse("COUNT = GETCOLOR") }
    }

    @Test
    fun `裸变量名与用户函数不受影响`() {
        // 变量优先于函数（ExpressionParser.cs:347-358 先查变量）
        assertIs<PrintStmt>(parse("PRINTFORML X={COUNT}"))
        // 普通内建变量同样不受影响（字符串型）
        assertIs<PrintStmt>(parse("PRINTFORML X=%MONEYLABEL%"))
        // 用户声明为函数的名字（#FUNCTION 的符号层注入）允许裸引用
        val sym = SymbolTable().declareFunction("UFUNC", OperandType.INT)
        val st = LineParser(sym).parseLogicalLine(LogicalLine(LineKind.CODE, "A4_METHOD.ERB", 1, "PRINTFORML X={UFUNC}"))
        assertIs<PrintStmt>(st)
    }

    @Test
    fun `内建方法在语句位可用`() {
        // funcDic 的 methodInstruction（FunctionIdentifier.cs:4140-4148）
        val st = assertIs<MethodStmt>(parse("GETCOLOR 1"))
        assertEquals("GETCOLOR", st.methodName.uppercase())
    }
}
