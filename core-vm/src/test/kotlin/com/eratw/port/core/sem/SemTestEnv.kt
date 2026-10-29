package com.eratw.port.core.sem

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.vars.VariableStore

// ---------------------------------------------------------------------------
// 假宿主 sink
// ---------------------------------------------------------------------------

/** 记录全部控制台动作的 Fake sink（事件按发生顺序入 [events]）。 */
open class FakeConsoleSink : ConsoleSink {
    val events = mutableListOf<String>()

    /** 输入等待入口交给控制台的请求（选项快照的冻结依据）。不混进 [events]，保持原顺序断言。 */
    val waitNotes = mutableListOf<InputRequest>()

    private fun ev(name: String, vararg args: String) {
        events.add(if (args.isEmpty()) name else "$name(${args.joinToString(",")})")
    }

    /** 装置不追踪打印缓冲；恒报"非空"使 BINPUT 系固定走补换行分支（覆盖 NewLine 路径）。 */
    override val printingBufferEmpty: Boolean get() = false

    override fun print(text: String) = ev("print", text)
    override fun printLine(text: String) = ev("printLine", text)
    override fun printC(text: String, alignRight: Boolean) = ev("printC", text, alignRight.toString())
    override fun printSingleLine(text: String) = ev("printSingleLine", text)
    override fun printButton(text: String, value: Long) = ev("printButton", text, value.toString())
    override fun printButton(text: String, value: String) = ev("printButton", text, value)
    override fun printButtonC(text: String, value: Long, alignRight: Boolean) =
        ev("printButtonC", text, value.toString(), alignRight.toString())

    override fun printButtonC(text: String, value: String, alignRight: Boolean) =
        ev("printButtonC", text, value, alignRight.toString())

    override fun printPlain(text: String) = ev("printPlain", text)
    override fun printTemporaryLine(text: String) = ev("printTemporaryLine", text)
    override fun printBar() = ev("printBar")
    override fun printCustomBar(text: String) = ev("printCustomBar", text)
    override fun newLine() = ev("newLine")
    override fun deleteLine(count: Int) = ev("deleteLine", count.toString())
    override fun clearText() = ev("clearText")
    override fun noteInputWait(request: InputRequest) {
        waitNotes.add(request)
    }
    override fun setForeColor(r: Int, g: Int, b: Int) = ev("setForeColor", r.toString(), g.toString(), b.toString())
    override fun setBackColor(r: Int, g: Int, b: Int) = ev("setBackColor", r.toString(), g.toString(), b.toString())
    override fun resetForeColor() = ev("resetForeColor")
    override fun resetBackColor() = ev("resetBackColor")
    override fun setFontStyle(mask: Int) = ev("setFontStyle", mask.toString())
    override fun setFont(name: String) = ev("setFont", name)
    override fun setAlignment(kind: Alignment) = ev("setAlignment", kind.name)
    override fun setRedraw(mode: Int) = ev("setRedraw", mode.toString())
    override fun printHtml(text: String, option: Boolean) = ev("printHtml", text, option.toString())
    override fun addHtmlIsland(text: String) = ev("addHtmlIsland", text)
    override fun clearHtmlIslands() = ev("clearHtmlIslands")
    override fun printError(text: String) = ev("printError", text)

    /** print() 文本按顺序拼接（PRINTV 连续 append 断言用）。 */
    val printedText: String get() = events.filter { it.startsWith("print(") }.joinToString("") { it.removeSurrounding("print(", ")") }
}

/** 记录输入请求、返回预设结果的 Fake sink。 */
class FakeInputSink(
    var outcome: InputOutcome = InputOutcome(),
) : InputSink {
    val anyKeyCalls = mutableListOf<Pair<Boolean, Boolean>>()
    val requests = mutableListOf<InputRequest>()

    /** 下一次 [consumeMesSkip] 的返回值（一次性，模拟刚消费掉一条 `keySkip=true` 的提交）。 */
    var nextMesSkip: Boolean = false

    override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) {
        anyKeyCalls.add(anyKey to stopMesSkip)
    }

    override fun waitInput(request: InputRequest): InputOutcome {
        requests.add(request)
        return outcome
    }

    override fun consumeMesSkip(): Boolean {
        val value = nextMesSkip
        nextMesSkip = false
        return value
    }
}

/** 表达式求值语境：变量读写直接走真实 [VariableStore]。 */
class TestEvalContext(override val variables: VariableStore) : EvalContext {
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw ExprEvalException("测试未绑定函数 $name")

    override fun printSystemLine(message: String) {}
}

// ---------------------------------------------------------------------------
// 语句 AST 构造辅助
// ---------------------------------------------------------------------------

fun iVar(name: String, vararg idx: Long): VarRef =
    VarRef(name, idx.map { IntLiteral(it) }, operandType = OperandType.INT)

fun sVar(name: String, vararg idx: Long): VarRef =
    VarRef(name, idx.map { IntLiteral(it) }, operandType = OperandType.STR)

fun intArg(v: Long) = ExpressionArgument(IntLiteral(v))

fun strArg(v: String) = ExpressionArgument(StrLiteral(v))

/** 表达式层字面量（按钮字 / 数据节等 Expr? 参数用）。 */
fun iLit(v: Long) = IntLiteral(v)

fun sLit(v: String) = StrLiteral(v)

fun instr(code: FunctionCode, args: Argument = com.eratw.port.core.stmt.VoidArgument()) =
    InstructionStmt(code, args, 1, code.name)

fun flowStmt(code: FunctionCode) = com.eratw.port.core.stmt.FlowStmt(code, com.eratw.port.core.stmt.VoidArgument(), 1, code.name)

fun printStmt(code: FunctionCode, args: Argument): PrintStmt {
    val spec = PrintSpec.parse(code) ?: throw IllegalArgumentException("非 PRINT 语句 $code")
    return PrintStmt(spec, args, 1, code.name)
}

// ---------------------------------------------------------------------------
// 执行环境
// ---------------------------------------------------------------------------

class SemTestEnv(
    val inputOutcome: InputOutcome = InputOutcome(),
) {
    val store = VariableStore()
    val console = FakeConsoleSink()
    val input = FakeInputSink(inputOutcome)
    var ctx = ExecContext(
        variables = store,
        eval = TestEvalContext(store),
        console = console,
        input = input,
    )
    val executor = StatementExecutor()

    init {
        // ADR-001：RNG 必须走注入的 provider（禁止 kotlin.random 兜底）。
        // 测试装置默认接固定种子 SFMT19937——PRINTDATA 家族按原版语义随机选组
        // （FunctionIdentifier.cs:270-320），无 provider 会显式抛错。
        store.randProvider = com.eratw.port.core.rand.Sfmt19937(0L)
    }

    /**
     * 执行一条语句（**旧对象树入口**）。
     *
     * T2-64 波次 C 的生产路径是 `execute(source, index, ctx)`（扁平直读）；语义用例仍走这条树入口，
     * 因为它是「直读复刻是否逐字一致」的参照系，且大量用例构造的是**编码器不会产出的合成形态**
     * （例如 `InstructionStmt(PRINTDATA, ExpressionArgument)`）——直读侧对形态有严格校验，会拒。
     * 生产路径的覆盖由 `StatementExecutorFlatTest`、`DualPathParityTest` 与 `dualPathAudit` 负责。
     */
    @Suppress("DEPRECATION")
    fun run(stmt: com.eratw.port.core.stmt.Statement): ExecResult = executor.execute(stmt, ctx)

    /** 扁平直读入口（生产路径；需要真语句形态时用）。 */
    fun runFlat(stmt: com.eratw.port.core.stmt.Statement): ExecResult = executor.execute(singleSource(stmt), 0, ctx)

    private fun singleSource(stmt: com.eratw.port.core.stmt.Statement): FlatStmtSource {
        val flat = com.eratw.port.core.code.FlatStatements.encode(listOf(stmt), emptyList())
        return object : FlatStmtSource {
            override fun opAt(index: Int): Int = flat.opAt(index)
            override fun headAt(index: Int): Int = flat.headAt(index)
            override fun argStreamBase(): IntArray = flat.stream
            override fun exprAt(poolIdx: Int): Expr? =
                if (poolIdx == com.eratw.port.core.code.ErbcFormat.NONE_REF) null else flat.exprAt(poolIdx)
            override fun stringAt(poolIdx: Int): String = flat.strAt(poolIdx)
            override fun statementAt(index: Int): com.eratw.port.core.stmt.Statement = stmt
        }
    }
}

/**
 * 数据节**双键**供给（T2-64 波次 C 的测试辅助）：语句池下标键（生产/扁平路径）与语句实例键
 * （旧树入口）都返回同一份组。
 *
 * 为什么需要它：波次 C 把 `DataSections` 的唯一抽象方法换成 `groupsOf(stmtIndex)`，实例键
 * 变成「显式失败」的过渡重载。语义用例仍走旧树入口（见 [SemTestEnv.run] 的 KDoc），所以必须
 * 同时覆写两键，否则 PRINTDATA 一族会撞上过渡重载的 UnsupportedOperationException。
 */
@Suppress("DEPRECATION")
fun dualKeySections(block: (Int) -> List<List<Expr>>?): DataSections = object : DataSections {
    override fun groupsOf(stmtIndex: Int): List<List<Expr>>? = block(stmtIndex)

    override fun groupsOf(stmt: com.eratw.port.core.stmt.Statement): List<List<Expr>>? = block(0)
}
