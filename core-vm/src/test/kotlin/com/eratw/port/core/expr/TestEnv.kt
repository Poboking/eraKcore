package com.eratw.port.core.expr

import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.FormStrEndWith

/**
 * 测试环境：Token 构造器、假 VariableReader / IdentifierResolver / EvalContext。
 */
fun i(v: Long) = ExprToken(ExprTokenKind.INT_LITERAL, v.toString(), v, 1, 1)
fun s(v: String) = ExprToken(ExprTokenKind.STR_LITERAL, v, 0, 1, 1)

/**
 * 由形式串原文构造 FORM_STRING Token —— 走真实 ErbLexer.analyseFormattedString
 * （模拟集成适配层把词法器 Token.FormStr 映射为 ExprToken 的路径）。
 */
fun f(v: String) = ExprToken(
    ExprTokenKind.FORM_STRING, v, 0L, 1, 1,
    ErbLexer().analyseFormattedString(CharStream(v), FormStrEndWith.DoubleQuotation, false, 1),
)
fun id(name: String) = ExprToken(ExprTokenKind.IDENTIFIER, name, 0, 1, 1)
fun op(text: String) = ExprToken(ExprTokenKind.OPERATOR, text, 0, 1, 1)
fun lp() = ExprToken(ExprTokenKind.LPAREN, "(")
fun rp() = ExprToken(ExprTokenKind.RPAREN, ")")
fun comma() = ExprToken(ExprTokenKind.COMMA, ",")
fun colon() = ExprToken(ExprTokenKind.COLON, ":")
fun at() = ExprToken(ExprTokenKind.AT, "@")

fun parse(
    tokens: List<ExprToken>,
    resolver: IdentifierResolver? = null,
    config: ParserConfig = ParserConfig(),
): Pair<Expr?, ExprParser> {
    val parser = ExprParser(tokens, resolver, config)
    return parser.reduceExpressionTerm() to parser
}

fun parseOrThrow(tokens: List<ExprToken>, resolver: IdentifierResolver? = null): Expr = parse(tokens, resolver).first!!

fun var0D(name: String, type: OperandType) = VarInfo(name, type, 0, isCharacterData = false)

fun resolverWith(vararg infos: VarInfo) = FakeResolver().apply {
    infos.forEach { vars[it.name] = it }
}

fun evalInt(expr: Expr, ctx: EvalContext = TestContext()): Long = expr.getIntValue(ctx)

fun evalStr(expr: Expr, ctx: EvalContext = TestContext()): String = expr.getStrValue(ctx)

class FakeVariables : VariableReader {
    val ints = mutableMapOf<String, Long>()
    val strs = mutableMapOf<String, String>()
    val keywords = mutableMapOf<Triple<String, Int, String>, Long>()
    val plusCalls = mutableListOf<String>()
    val constNames = mutableSetOf<String>()

    override fun isConst(name: String): Boolean = name in constNames

    private fun key(name: String, indices: LongArray): String =
        if (indices.isEmpty()) name else name + indices.joinToString(":", prefix = ":")

    override fun readInt(name: String, indices: LongArray): Long =
        ints[key(name, indices)] ?: 0L

    override fun readStr(name: String, indices: LongArray): String =
        strs[key(name, indices)] ?: ""

    override fun plusInt(name: String, indices: LongArray, delta: Long): Long {
        val k = key(name, indices)
        val nv = (ints[k] ?: 0L) + delta
        ints[k] = nv
        plusCalls += "$k+=($delta)"
        return nv
    }

    override fun keywordToInteger(varName: String, dim: Int, keyword: String): Long =
        keywords[Triple(varName, dim, keyword)] ?: throw ExprEvalException("未知关键字 $keyword")
}

class FakeResolver : IdentifierResolver {
    val vars = mutableMapOf<String, VarInfo>()
    val funcs = mutableMapOf<String, OperandType>()

    override fun resolveVariable(name: String, subKey: String?): VarInfo? = vars[name]

    override fun resolveFunction(name: String): OperandType? = funcs[name]
}

open class TestContext(
    override val variables: FakeVariables = FakeVariables(),
) : EvalContext {
    val funcs = mutableMapOf<String, (List<Value?>) -> Value>()
    val funcCalls = mutableListOf<String>()
    val systemLines = mutableListOf<String>()

    override fun callFunction(name: String, args: List<Value?>): Value {
        funcCalls += "$name(${args.joinToString(",") { it.toString() }})"
        return funcs[name]?.invoke(args) ?: throw ExprEvalException("未绑定函数 $name")
    }

    override fun printSystemLine(message: String) {
        systemLines += message
    }
}
