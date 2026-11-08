package com.eratw.port.core.fn

import com.eratw.port.boot.GameRoot981
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * 簇 `7babe9eb`（0.2.39「函数参数类型错误：需要数值 @ SOURCE_DOWNBASE 行625」）的
 * **编译链 + `.erbc` 容器**自证闸 —— 起点 = `wave/report-downbase`（worker downbase，2026-11-05）。
 *
 * ## 本闸补的是哪一段
 *
 * `PowerFloatRealCorpusTest`（lane v039，`80ce64df`）已经把「真语料那一行解析成表达式树、
 * 现场求值不抛且得 85」钉住了，但它走的是**内存里的解析结果**。真机上跑的却是随包
 * `.erbc` 里的语句池：`ErbcWriter` 把每个 `FuncCall` 的**静态类型**写进容器
 * （`ErbcWriter.kt` 的 FUNC_CALL 分支 `out.u8(operandTypeWire(e.operandType))`，
 * `ErbcReader.kt` 的 FUNC_CALL 分支原样读回），而浮点晋升（`CanReturnFloat`）恰好是
 * **解析期**决定的。⇒ 只验内存树，证明不了「重编出来的产物」带 FLOAT。
 * 本闸把真语料那一行喂进**同一条编译链**（`LineParser` → `ErbProgramCompiler` →
 * `ErbcWriter.encode` → `ErbcReader.decode`），断言往返后的静态类型、行号与读数。
 *
 * ## 行号口径（上报的 625 到底是哪一行）
 *
 * 基线 `34e4af08` 的上报行号是 **0 基**（与原版 `ScriptPosition.LineNo` 同口径，
 * spec: `lexer/EraStreamReader` 侧的 `curNo`；移植侧写死在同一处：
 * `LogicalLine.lineNo` 的 KDoc「逻辑行的 0 基行号」+ `lexer/LexError.kt` 的
 * 「原版 ScriptPosition.LineNo 为 0 基」）。`proc/ProcessRuntime.stampPosition` 取的是
 * `StmtEnv.line` = `Program.lineOf(pc)`，而行表填的就是 `LogicalLine.lineNo` ⇒
 * 报告里的「行625」= 物理第 626 行。本闸第一个用例直接读真文件量这件事，不靠注释。
 *
 * ## 语料侧事实（4.981 `ERB/ステータス計算関連/SOURCE/SOURCE_EXTRA.ERB`）
 *
 * `@SOURCE_DOWNBASE(ARG)` 起于物理 584 行，体内只有两行带 `POWER(0.95, …)`：
 * 物理 626 / 627（0 基 625 / 626），形如
 * `DOWNBASE:ARG:体力 = TOINT(POWER(0.95,ITEM:清扫魔法) * DOWNBASE:ARG:体力)`。
 * 4.980 的同名函数（`SOURCE_EXTRA.ERB:572` 起）**没有** MAGIC_DLC 那一段，全文件只有
 * 588 行 ⇒ 连 0 基 625 这一行都不存在，与该簇「老包一份没有」的读数一致。
 *
 * ## 为什么这一簇在 0.2.39 上「仍活着」但代码侧已修
 *
 * 修复 = `872b5934`（2026-11-04 03:35，POWER 按 `CanReturnFloat` 分流：解析期晋升 FLOAT、
 * 运行期 `dv()` 取参）。两份上报正文的构建标识都是 `4899130-dirty`
 * （= `48991308`，2026-11-03 20:06），`git merge-base --is-ancestor 872b5934 48991308`
 * 返回假 ⇒ 包里没有这一改。修复前的 `POWER` 是
 * `int("POWER", 2, 2) { a -> val b = iv(a[0]!!); … }`（`git show 872b5934^` 的
 * `fn/PureMethods.kt:1305-1306`），`iv(Value.FloatV)` 抛的正是上报原文。
 *
 * 最后一节断言的是**接线风险**：新引擎 + 旧产物（静态 INT 的 POWER）不会回到原文案，
 * 而是 `FuncCall.getIntValue` 抛「函数 POWER 返回了非整数结果」。所以出包必须重跑
 * `:tools:erbcc:compileCorpus` 并重指 4.981 语料根，`buildSrc` 的 CMP-66
 * `compiler-digest` 对账（`ErbcFreshness.kt`）就是挡这件事的闸。
 *
 * 注释正文不写块注释定界符字符对（AGENTS.md 铁律 5）。
 */
class SourceDownbasePowerWireTest {

    private val erbRoot: Path? = GameRoot981.erb()
    private val sourceRel = "ステータス計算関連/SOURCE/SOURCE_EXTRA.ERB"

    private companion object {
        /** 上报原文里的行号（0 基）与它对应的物理行号（1 基）。 */
        const val REPORTED_LINE = 625
        const val PHYSICAL_LINE = 626
        const val RAW_626 = "DOWNBASE:ARG:体力 = TOINT(POWER(0.95,ITEM:清扫魔法) * DOWNBASE:ARG:体力)"
        const val RAW_627 = "DOWNBASE:ARG:気力 = TOINT(POWER(0.95,ITEM:清扫魔法) * DOWNBASE:ARG:気力)"

        /** 赋值语句 marker 的操作码区间（契约 C3：内部指令 0..28、语句 29..197）。 */
        fun isStatementMarker(op: Int): Boolean = op in 29..197
    }

    /** 真文件的逻辑行全集（与装载链同一个 reader：编码探测、BOM、{} 合并都同一条路）。 */
    private fun logicalLines(): List<LogicalLine> {
        val bytes = Files.readAllBytes(erbRoot!!.resolve(sourceRel))
        val reader = LogicalLineReader(LogicalLineReader.readAllLines(bytes), "SOURCE_EXTRA.ERB")
        val out = ArrayList<LogicalLine>()
        while (true) {
            val line = reader.readLogicalLine() ?: break
            out.add(line)
        }
        return out
    }

    /** 真语料那一行的原文（按 0 基行号取，不是按副本取）。 */
    private fun realLine(lineNo: Int): LogicalLine {
        val hit = logicalLines().filter { it.kind == LineKind.CODE && it.lineNo == lineNo }
        assertEquals(1, hit.size, "0 基 $lineNo 行应当恰好命中一条代码逻辑行，实际 ${hit.map { it.content }}")
        return hit.single()
    }

    /** 受控读口：`ITEM` 读持有数、`DOWNBASE` 读扣减值（同 v039 的桩口径）。 */
    private class StubReader(
        var itemOwned: Long = 3L,
        var downBase: Long = 100L,
    ) : VariableReader {
        override fun isConst(name: String): Boolean = false

        override fun readInt(name: String, indices: LongArray): Long = when (name) {
            "ITEM" -> itemOwned
            "DOWNBASE" -> downBase
            else -> 0L
        }

        override fun readStr(name: String, indices: LongArray): String = ""

        override fun keywordToInteger(varName: String, dim: Int, keyword: String): Long = 77L

        override fun plusInt(name: String, indices: LongArray, delta: Long): Long = 0L
    }

    private class StubContext(override val variables: VariableReader) : EvalContext {
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw AssertionError("内建 POWER/TOINT 应由 BuiltinEvalContext 承接：$name")
    }

    private fun evaluate(term: Expr, reader: StubReader = StubReader()): Long =
        term.getIntValue(BuiltinEvalContext(StubContext(reader), VariableStore(), object : BuiltinHost {}))

    /**
     * 把真语料那一行编成 `SOURCE_DOWNBASE` 函数并过一遍 `.erbc` 容器。
     *
     * 左值换成内建 1 维整型 `A`（右值一个字符不动，同 v039）：`DOWNBASE` 是角色变量、
     * 其关键字下标要真 CSV 表才解得出，而本闸的判据全在右值与容器位面上。
     */
    private fun wireStatement(): Pair<Program, AssignStmt> {
        val raw = realLine(REPORTED_LINE).content
        assertEquals(RAW_626, raw.trim(), "真文件 0 基 625 行的原文")
        val text = "A =" + raw.substringAfter('=')
        val stmt = LineParser(SymbolTable().declareVariable("A", OperandType.INT, 1))
            .parseLogicalLine(LogicalLine(LineKind.CODE, "SOURCE_EXTRA.ERB", REPORTED_LINE, text))!!
        val assign = stmt as AssignStmt
        val compiler = ErbProgramCompiler()
        compiler.compileFunction(FuncSig("SOURCE_DOWNBASE", 1), listOf(assign))
        val decoded = ErbcReader.decode(ErbcWriter.encode(compiler.build("SOURCE_DOWNBASE")))
        val pc = requireNotNull(
            (0 until decoded.instructionCount).firstOrNull {
                isStatementMarker(decoded.op(it)) && decoded.statement(decoded.a(it)).code == FunctionCode.SET
            },
        ) { "往返后的容器里必须还有那条赋值语句 marker" }
        return decoded to decoded.statement(decoded.a(pc)) as AssignStmt
    }

    private fun findFirst(e: Expr?, pred: (Expr) -> Boolean): Expr? {
        if (e == null) return null
        if (pred(e)) return e
        val kids: List<Expr> = when (e) {
            is Binary -> listOfNotNull(e.left, e.right)
            is FuncCall -> e.args.filterNotNull()
            else -> emptyList()
        }
        for (k in kids) findFirst(k, pred)?.let { return it }
        return null
    }

    // ------------------------------------------------------------------
    // 1) 行号口径：上报的「行625」就是真文件里 POWER 那一行（0 基）
    // ------------------------------------------------------------------

    @Test
    fun `上报行号 625 对应真文件里带 POWER 的那一行`() {
        val root = erbRoot
        Assumptions.assumeTrue(root != null, "缺 4.981 语料根（ERATW_GAMEDIR / ERATW_CORPUS 都没指到 4.981）：跳过")
        val codes = logicalLines().filter { it.kind == LineKind.CODE }
        val powerLines = codes.filter { it.content.contains("POWER(0.95") }
        assertEquals(listOf(REPORTED_LINE, REPORTED_LINE + 1), powerLines.map { it.lineNo }, "体内两条 POWER 行的 0 基行号")
        assertEquals(RAW_626, powerLines[0].content.trim())
        assertEquals(RAW_627, powerLines[1].content.trim())
        // 上报口径的直接复现：lineNo 是 0 基，物理行号 = lineNo + 1
        assertEquals(PHYSICAL_LINE, powerLines[0].lineNo + 1, "「行625」= 物理 626 行")
        assertEquals(rawOf(PHYSICAL_LINE), powerLines[0].content.trim(), "reader 的行内容与物理行原文一致（未被 {} 合并挪位）")
        // 全文只有这两处 POWER(0.95：该簇的现场就是这两行之一，不存在第三个候选
        assertEquals(2, powerLines.size, "SOURCE_DOWNBASE 体内恰两条 POWER 行（0 基 625/626）")
    }

    /** 物理行原文（`readAllLines` 口径，用来交叉验证上面的 lineNo 换算）。 */
    private fun rawOf(physicalLine: Int): String {
        val lines = LogicalLineReader.readAllLines(Files.readAllBytes(erbRoot!!.resolve(sourceRel)))
        return lines[physicalLine - 1].trim()
    }

    // ------------------------------------------------------------------
    // 2) 编译链 + 容器：FLOAT 晋升与行号都得活过 .erbc 往返
    // ------------------------------------------------------------------

    @Test
    fun `真语料行进容器再读回 POWER 的静态类型仍是 FLOAT`() {
        val root = erbRoot
        Assumptions.assumeTrue(root != null, "缺 4.981 语料根：跳过")
        val (program, assign) = wireStatement()
        val term = (assign.args as? SpSetArgument)?.term
        assertNotNull(term, "往返后的赋值语句必须还带右值表达式树")
        val power = findFirst(term) { it is FuncCall && it.name == "POWER" }
        assertNotNull(power, "往返后的树里必须有 POWER 结点")
        assertEquals(OperandType.FLOAT, power.operandType, "静态晋升必须活过容器；退回 INT 就是上报的形态")
        val mul = findFirst(term) { it is Binary }
        assertNotNull(mul)
        assertEquals(OperandType.FLOAT, mul.operandType, "FLOAT × INT ⇒ FLOAT 的晋升链也得在容器里")
        assertEquals(OperandType.INT, term.operandType, "TOINT 仍按整型返回（写回 INT 左值）")
    }

    @Test
    fun `容器里的行号与上报口径一致且往返回来的右值读数不变`() {
        val root = erbRoot
        Assumptions.assumeTrue(root != null, "缺 4.981 语料根：跳过")
        val (program, _) = wireStatement()
        val pc = (0 until program.instructionCount)
            .first { isStatementMarker(program.op(it)) && program.statement(program.a(it)).code == FunctionCode.SET }
        assertEquals(REPORTED_LINE, program.lineOf(pc), "容器行表读回的就是上报里那个 0 基行号")
        val term = ((program.statement(program.a(pc)) as AssignStmt).args as SpSetArgument).term
        assertNotNull(term)
        assertEquals(85L, evaluate(term), "trunc(0.95^3 × 100) = 85（往返后的树，不是内存里的原件）")
        assertEquals(95L, evaluate(term, StubReader(itemOwned = 1)), "0.95^1 × 100 = 95")
        assertEquals(0L, evaluate(term, StubReader(itemOwned = 3, downBase = 0L)), "扣减基数 0 ⇒ 0")
    }

    // ------------------------------------------------------------------
    // 3) 接线风险：旧产物 + 新引擎不会回到原文案，而是另一条
    // ------------------------------------------------------------------

    @Test
    fun `修复前静态形态（POWER 钉 INT）在新引擎上抛的是非整数结果而非上报原文`() {
        val root = erbRoot
        Assumptions.assumeTrue(root != null, "缺 4.981 语料根：跳过")
        val raw = realLine(REPORTED_LINE).content
        val text = "A =" + raw.substringAfter('=')
        // 复现修复前的解析期形态：把 POWER 当**用户 #FUNCTION** 声明，SymbolTable.canReturnFloat
        // 对用户声明不晋升（ParseSupport.kt 的 canReturnFloat 判据），得到的就是旧容器里的
        // `FuncCall(POWER, INT)` 形状 —— 等价于「产物没重编」。
        val stmt = LineParser(SymbolTable().declareVariable("A", OperandType.INT, 1).declareFunction("POWER", OperandType.INT))
            .parseLogicalLine(LogicalLine(LineKind.CODE, "SOURCE_EXTRA.ERB", REPORTED_LINE, text))!!
        val term = ((stmt as AssignStmt).args as SpSetArgument).term
        assertNotNull(term)
        val power = findFirst(term) { it is FuncCall && it.name == "POWER" }
        assertNotNull(power)
        assertEquals(OperandType.INT, power.operandType, "本用例造的正是修复前产物里的那种静态类型")
        val e = assertFailsWith<ExprEvalException> { evaluate(term) }
        assertEquals("函数 POWER 返回了非整数结果", e.message, "旧产物 + 新引擎的现场文案（不是上报原文）")
    }
}
