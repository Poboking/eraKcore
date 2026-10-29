package com.eratw.port.erbcc.compile

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprParser
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IdentifierResolver
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.LexAnalyzeFlag
import com.eratw.port.core.lexer.LexEndWith
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.stmt.toExprToken
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.Locale

/**
 * 一次变量引用（`VarRef`）的去重键。
 *
 * 粒度为「文件 + 函数键 + 语句行 + 变量名」：同一行里同一变量出现多次只算一处
 * （`occurrences` 记次数）。[functionKey] 是运行期私有 `#DIM` 的归属键
 * （`LabelDef.key`，已大写），link audit 拿它查 `VariableStore.userVar(name, functionKey)`。
 */
data class VarUseKey(
    val file: String,
    val functionKey: String,
    val line: Int,
    val nameUpper: String,
    val subKeyUpper: String?,
)

/** 一条去重后的变量使用点 + 解析期已知的形态（供运行期命名空间对拍）。 */
class VarUse(
    val key: VarUseKey,
    /** 源码里的原始拼写（报告可读性；匹配一律走 [VarUseKey.nameUpper]）。 */
    val displayName: String,
    val subKey: String?,
    val operandType: OperandType,
    val isConst: Boolean,
    val isNoArg: Boolean,
    /** 解析器补全省略下标后的下标个数（与 `VarRef.indices.size` 一致）。 */
    val indexCount: Int,
) {
    var occurrences: Int = 1
        internal set
}

/**
 * 一次字符串关键字下标（`ITEM:药` / `TALENT:MASTER:神性`）的去重键。
 *
 * [dim] 是**下标位置**（0 基），与运行期 `KeywordIndexArg.getIntValue` 传给
 * `VariableStore.keywordToInteger(varName, dim, keyword)` 的是同一个数。
 */
data class KeywordUseKey(
    val file: String,
    val functionKey: String,
    val line: Int,
    val varNameUpper: String,
    val dim: Int,
    /** null = 下标表达式不是字符串字面量 ⇒ 装载期无法判定，运行期才见分晓。 */
    val keyword: String?,
)

class KeywordUse(
    val key: KeywordUseKey,
    val displayVarName: String,
) {
    var occurrences: Int = 1
        internal set
}

/**
 * 一次「名字字符串入口」使用点（CMP-77）。
 *
 * 原版这些入口的**首参是变量名字符串**，运行期才 `LexicalAnalyzer` + `ExpressionParser`
 * 现解成 `VariableTerm` 再查表：
 * - `GETVAR` / `GETVARS`：`FunctionMethodCreator.cs:377-405` / `:407-435`（首参 `typeof(string)`，
 *   `ReduceExpressionTerm(LexicalAnalyzer.Analyse(首参.GetStrValue()))`）；
 * - `SETVAR` / `VARSETEX`：同文件 `:736-784` / `:786-928`；
 * - `EXISTVAR` / `VARSIZE` / `GETNUMB`：同文件 `:437-499` / `:3420-3475` / `:4835-4863`；
 *   名字查表一律 `IdentifierDictionary.GetVariableToken`（`IdentifierDictionary.cs:429-495`）。
 *
 * 首参是**常量** `StrLiteral` 时，构建期就能把这串按变量名再解析一次（[SymbolUses.recordNameEntry]），
 * 得到 [displayName] / [subKey] / 下标个数，与真 `VarRef` 同口径参与审计；首参是变量或
 * FORM 串（运行期才成形）时只计数（[SymbolUses.dynamicVarNameSites]），不做断言。
 *
 * [fnName] 是入口名（大写），只用于报告可读性与「哪一族入口」定位。
 */
class DynamicNameUse(
    val key: VarUseKey,
    val fnName: String,
    /** 二次解析出的变量名原文（报告可读性；匹配走 [VarUseKey.nameUpper]）。 */
    val displayName: String,
    val subKey: String?,
    val indexCount: Int,
    val isNoArg: Boolean,
    /** 入口首参的常量串原文（报告里能直接搜回源码的那段）。 */
    val rawText: String,
) {
    var occurrences: Int = 1
        internal set
}


/**
 * 全量符号使用点收集器（装载期 link audit 的输入半边）。
 *
 * **只在 erbcc 构建期使用**：不挂在 `ExprParser` / `LineParser` 热路径上，
 * 也不进 `Program` / `.erbc`。遍历的是 `CorpusCompile` 已经解析出来的语句 AST，
 * 因此与真正发射进语句池的是同一批实例（不会漏掉「解析成功但没进字节码」的行）。
 */
class SymbolUses {
    val varUses = LinkedHashMap<VarUseKey, VarUse>()
    val keywordUses = LinkedHashMap<KeywordUseKey, KeywordUse>()

    /**
     * 名字字符串入口的**常量串**使用点（CMP-77）。键与 [varUses] 同构
     * （文件 + 函数 + 调用点行 + 名字 + subKey），因此同一行同一名字多处只记一次。
     */
    val dynamicNameUses = LinkedHashMap<VarUseKey, DynamicNameUse>()

    var statementsWalked: Long = 0
        private set
    var varRefsSeen: Long = 0
        private set
    var keywordRefsSeen: Long = 0
        private set

    /** 七个名字入口的调用点总数（含首参不是常量串的）。 */
    var nameEntrySites: Long = 0
        private set

    /**
     * 首参**不是**常量 `StrLiteral` 的名字入口数：变量/FORM 串/缺参 ⇒ 名字运行期才成形，
     * 构建期无从断言，只计数（与 `dynamicKeywordSites` 同口径）。
     */
    var dynamicVarNameSites: Long = 0
        private set

    /** 首参是常量串但二次解析不出变量引用（如 `GETVAR("1+2")`）的入口数。 */
    var nameEntryParseFailures: Long = 0
        private set

    internal fun recordVar(
        file: String,
        functionKey: String,
        line: Int,
        ref: VarRef,
    ) {
        varRefsSeen++
        val nameUpper = ref.name.uppercase(Locale.ROOT)
        val key = VarUseKey(file, functionKey, line, nameUpper, ref.subKey?.uppercase(Locale.ROOT))
        val existing = varUses[key]
        if (existing != null) {
            existing.occurrences++
            return
        }
        varUses[key] = VarUse(
            key = key,
            displayName = ref.name,
            subKey = ref.subKey,
            operandType = ref.operandType,
            isConst = ref.isConst,
            isNoArg = ref.isNoArg,
            indexCount = ref.indices.size,
        )
    }

    internal fun recordKeyword(
        file: String,
        functionKey: String,
        line: Int,
        kw: KeywordIndexArg,
    ) {
        keywordRefsSeen++
        val keyword = (kw.expr as? StrLiteral)?.value
        val key = KeywordUseKey(file, functionKey, line, kw.varName.uppercase(Locale.ROOT), kw.dim, keyword)
        val existing = keywordUses[key]
        if (existing != null) {
            existing.occurrences++
            return
        }
        keywordUses[key] = KeywordUse(key, kw.varName)
    }

    internal fun countStatement() {
        statementsWalked++
    }

    /**
     * 名字字符串入口的二次解析（CMP-77）。
     *
     * 三条口径：
     * 1. 只对**常量** `StrLiteral` 做构建期解析；其余（变量 / FORM 串 / 缺参）只记
     *    [dynamicVarNameSites]。
     * 2. 解析用**宽松 resolver**（[LooseNameResolver]）：任何标识符都当变量返回，
     *    否则未知名会在解析期抛 `未定义的标识符`、审计就拿不到 `VAR_UNBOUND`。
     * 3. [line] 由调用方给**调用点**行：入口 AST 自己的 `line`（parser 写的是标识符行列），
     *    绝不取字符串字面量的行号 —— 字面量在源码里通常带 -1，取它就丢了定位。
     */
    internal fun recordNameEntry(call: FuncCall, file: String, functionKey: String, line: Int) {
        val fn = call.name.uppercase(Locale.ROOT)
        if (fn !in NAME_ENTRY_FUNCTIONS) return
        nameEntrySites++
        val literal = call.args.firstOrNull() as? StrLiteral
        if (literal == null) {
            dynamicVarNameSites++
            return
        }
        val ref = parseNameRef(literal.value)
        if (ref == null) {
            nameEntryParseFailures++
            return
        }
        val key = VarUseKey(file, functionKey, line, ref.name.uppercase(Locale.ROOT), ref.subKey?.uppercase(Locale.ROOT))
        val existing = dynamicNameUses[key]
        if (existing != null) {
            existing.occurrences++
            return
        }
        dynamicNameUses[key] = DynamicNameUse(
            key = key,
            fnName = fn,
            displayName = ref.name,
            subKey = ref.subKey,
            indexCount = ref.indices.size,
            isNoArg = ref.isNoArg,
            rawText = literal.value,
        )
    }

    /**
     * 把名字串按变量引用解析（宽松、无符号层）。
     *
     * 维数未知：名字串里 `:` 的个数决定下标个数，故按 0..3 逐档试（`ExprParser` 的省略规则
     * 要求维度与下标个数匹配，维度猜错会抛「下标过多/不能省略」）。第一档能解析成
     * [VarRef] 就用它；解析出别的表达式（`GETVAR("1+2")`）或四档全失败返回 null。
     */
    private fun parseNameRef(text: String): VarRef? {
        if (text.isBlank()) return null
        for (dim in 0..3) {
            val ast = try {
                val tokens = ErbLexer(NAME_ENTRY_LEXER)
                    .analyse(CharStream(text), LexEndWith.EoL, LexAnalyzeFlag.NONE, 1)
                ExprParser(tokens.map { it.toExprToken() }, LooseNameResolver(dim), NAME_ENTRY_PARSER)
                    .reduceExpressionTerm()
            } catch (_: Exception) {
                // 解析期异常只说明「这串不是变量引用」或该维档不匹配，换下一档/记失败。
                continue
            }
            return ast as? VarRef
        }
        return null
    }

    companion object {
        /** 首参为变量名字符串的入口族（RECON-CMP §7 CMP-77；`ERDNAME` 走 refVar 不在此列）。 */
        val NAME_ENTRY_FUNCTIONS: Set<String> =
            setOf("GETVAR", "GETVARS", "SETVAR", "VARSETEX", "EXISTVAR", "VARSIZE", "GETNUMB")

        /** 构建期二次解析用的词法配置：只切变量名，宏/全角空格开关都不参与判定。 */
        private val NAME_ENTRY_LEXER = LexerConfig()

        private val NAME_ENTRY_PARSER = ParserConfig()
    }
}

/**
 * 二次解析用的宽松 resolver：任何标识符都当变量返回（维度用外层试档值）。
 *
 * 只服务 [SymbolUses.parseNameRef]：审计期还没有运行期命名空间（`GameSession` 在编译之后
 * 才装），这里只需要把名字串切成 [VarRef] 拿到名字/subKey/下标个数；绑定判定留给
 * `SymbolLinkAudit` 拿真 `VariableStore` 做。
 */
private class LooseNameResolver(private val dimension: Int) : IdentifierResolver {
    override fun resolveVariable(name: String, subKey: String?): VarInfo = VarInfo(
        name = name,
        operandType = OperandType.INT,
        dimension = dimension,
        isCharacterData = false,
        isConst = false,
    )

    /** 名字串里不该出现函数调用；返回 null 让 `NAME(...)` 形态解析失败而不是当变量。 */
    override fun resolveFunction(name: String): OperandType? = null
}

/**
 * 语句 AST → 符号使用点。
 *
 * 遍历方式 = JVM 反射（构建期工具，不参与运行期）：语句/参数/表达式的字段类型全部落在
 * `com.eratw.port.core.**`，逐个读实例字段即可覆盖 `argsSlot`、`AssignStmt.dest`、
 * 各 `Argument` 家族的 `dest`/`terms`、`CaseExpression` 等所有持有 [Expr] 的位置。
 * 反射缓存按类建一次；每个语句独立一份 `IdentityHashMap`，同一条语句内共享的
 * 表达式实例不会被重复计数，跨语句出现的使用点（共享 `Argument`）仍按各自函数上下文计。
 */
internal object SymbolUseWalker {

    private val fieldCache = HashMap<Class<*>, List<Field>>()

    fun walk(rel: String, stmts: List<com.eratw.port.core.stmt.Statement>, functionKey: String, sink: SymbolUses) {
        for (stmt in stmts) {
            sink.countStatement()
            walk(stmt, rel, functionKey, stmt.line, sink, IdentityHashMap())
        }
    }

    private fun walk(
        value: Any?,
        file: String,
        functionKey: String,
        line: Int,
        sink: SymbolUses,
        seen: IdentityHashMap<Any, Boolean>,
    ) {
        when (value) {
            null, is String, is Number, is Boolean, is Char -> return
            is Enum<*> -> return
            is FuncCall -> {
                // 先用 seen 判重再记：同一语句里共享的 FuncCall 实例只算一处出现
                // （walkFields 随后会 put 同一个对象，这里先判不改变可达性）。
                if (!seen.containsKey(value)) {
                    sink.recordNameEntry(
                        value,
                        file,
                        functionKey,
                        if (value.line >= 0) value.line else line,
                    )
                }
                walkFields(value, file, functionKey, line, sink, seen)
            }
            is VarRef -> {
                sink.recordVar(file, functionKey, if (value.line >= 0) value.line else line, value)
                for (e in value.indices) walk(e, file, functionKey, line, sink, seen)
            }
            is KeywordIndexArg -> {
                sink.recordKeyword(file, functionKey, if (value.line >= 0) value.line else line, value)
                walk(value.expr, file, functionKey, line, sink, seen)
            }
            is Expr, is com.eratw.port.core.stmt.Argument -> walkFields(value, file, functionKey, line, sink, seen)
            is Array<*> -> {
                if (seen.put(value, true) != null) return
                for (e in value) walk(e, file, functionKey, line, sink, seen)
            }
            is Iterable<*> -> {
                if (seen.put(value, true) != null) return
                for (e in value) walk(e, file, functionKey, line, sink, seen)
            }
            else -> {
                // 只下钻本仓库 AST 的类（CaseExpression / MixedIntegerExprTerm / PrintSpec 等）；
                // JDK 容器与普通值类型到此为止，避免误入巨大的外部对象图。
                val name = value.javaClass.name
                if (name.startsWith("com.eratw.port.core.") || name.startsWith("com.erbcc.")) {
                    walkFields(value, file, functionKey, line, sink, seen)
                }
            }
        }
    }

    private fun walkFields(
        value: Any,
        file: String,
        functionKey: String,
        line: Int,
        sink: SymbolUses,
        seen: IdentityHashMap<Any, Boolean>,
    ) {
        if (seen.put(value, true) != null) return
        for (f in fieldsOf(value.javaClass)) {
            val v = try {
                f.get(value)
            } catch (_: Exception) {
                continue
            }
            walk(v, file, functionKey, line, sink, seen)
        }
    }

    private fun fieldsOf(cls: Class<*>): List<Field> = fieldCache.getOrPut(cls) {
        val out = ArrayList<Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                if (!f.trySetAccessible()) continue
                out.add(f)
            }
            c = c.superclass
        }
        out
    }
}
