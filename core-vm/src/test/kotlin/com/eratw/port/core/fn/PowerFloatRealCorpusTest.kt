package com.eratw.port.core.fn

import com.eratw.port.boot.GameRoot981
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 簇 2（0.2.39「函数参数类型错误：需要数值 @ SOURCE_DOWNBASE 行625」）的**真语料**复现闸
 * —— 起点 = main@e208ced5（worker lane v039）。
 *
 * 上报读数：8 份 / 4 台 / app 0.2.39（11-04 00:15–17:27），抛点
 * `ProcessRuntime.stampPosition` ← `executeStatementRaw`，ERB 帧链 `SOURCE_DOWNBASE ← SOURCE_CHECK`。
 * 「行625」是 0 基行号，对应物理行 626（本闸直接读真文件那两行，不抄副本）。
 *
 * 语料原文（4.981 `ERB/ステータス計算関連/SOURCE/SOURCE_EXTRA.ERB`，函数 `@SOURCE_DOWNBASE(ARG)`
 * 定义起于 :584）：
 * - `:626` `DOWNBASE:ARG:体力 = TOINT(POWER(0.95,ITEM:清扫魔法) * DOWNBASE:ARG:体力)`
 * - `:627` `DOWNBASE:ARG:気力 = TOINT(POWER(0.95,ITEM:清扫魔法) * DOWNBASE:ARG:気力)`
 * 这两行是全语料仅有的 `POWER(0.95,…)` 形态：4.980 的同名文件更短、无此二行
 * （`grep -rn "POWER(0.95" 4.980/ERB` 实测 0 命中）。
 *
 * 语义出处：
 * - 静态面 `CanReturnFloat` —— 任一实参 FLOAT ⇒ 内建方法的返回类型升为 FLOAT
 *   （spec: FunctionMethodTerm.cs:44-56；移植侧 `expr/Parser.kt` 的 functionCall +
 *   `stmt/SymbolTable.canReturnFloat`，用户 `#FUNCTION` 不参与晋升）。
 * - 运行期面 —— POWER 用 fork 的 ToDouble 取参（`GetEraType() != Integer` 走 GetFloatValue，
 *   spec: FunctionMethodCreator.cs:4169/:4172-4190 + :13424-13432），任一实参 FloatV ⇒ 返回 FloatV
 *   （移植侧 `fn/PureMethods.kt` 的 POWER + `dv()`）；`TOINT` 收 FloatV 按 `(long)double` 向零截断
 *   （spec: FunctionMethodCreator.cs:5500-5557；4.981 语料另两条同形实证
 *   `COMMON.ERB:605` `回復量 = TOINT(回復量 * 0.7)`、`DLC/魔法DLC/使用魔法.ERB:1256` `TOINT(LOCALF)`）。
 * - INT 形参的 `iv()` 保持严格（原版 `CheckArgumentType`，spec: FunctionMethod.cs:580-584）：
 *   语料里除 TOINT 外零处「浮点式进 INT 形参」（实测 grep `LIMIT|SQRT|LOG|GETBIT|SETBIT|SWITCH`
 *   带小数字面量实参 = 0 命中），故不放宽、也不新增。
 *
 * ## 断言口径的两处补记（2026-11-05 本路复核）
 *
 * ① 「626 / 627 结构一致」这条钉的是**归一化掉行号之后**的同构，不是裸 toString 相等：
 *   两行的行号戳本就不同（626 / 627），裸比较永远红。归一化前先把差异面各自钉死
 *   （行号戳全集、逐结点列位点相等、关键词下标原文 = 清扫魔法 + 体力 / 気力），
 *   实测两棵树除这两轴外逐字符相同 ⇒ **解析没偏，不需要动 main**；
 *   上一轮的红是拿裸结构串比较造成的，属断言钉错，不是引擎缺陷。
 * ② `POWER(<小数字面量>` 在全语料共 3 行：本二行 + `DLC/魔法DLC/使用魔法.ERB:766`
 *   （`TOINT(魔法知识(...) * POWER(1.25, ...) ...)`）。766 行含用户函数调用，
 *   本闸的 [StubContext] 不承接 ⇒ TODO(verify)：该行的 POWER 浮点面未在真语料闸里覆盖，
 *   要覆盖得挂真会话（E2E 侧），卡点是用户函数 `魔法知识` / `法强计算` 需要角色上下文。
 *
 * ③ 取证卡点：本节引用的 `CanReturnFloat` / `HasFloatArg` / `GetEraType` 与
 *   `FunctionMethodTerm.cs:44-56`、`FunctionMethodCreator.cs:5418-5436`、`:13424-13432`
 *   都是 eraTW 4.981 fork 的**产物反编译**行号（同 `specs/verify-notes.md` 的
 *   「产物 FunctionMethodCreator.cs :13137…」口径）。本机 `reference/emuera-cs` 只有上游
 *   Emuera 反编译：`FunctionMethodTerm.cs` 全文 61 行、`:44-56` 是 Restructure 循环，
 *   全树 `grep CanReturnFloat|HasFloatArg|GetEraType` **零命中**，`FunctionMethodCreator.cs`
 *   只到 9,476 行。上游侧可核对的是同族两处：`PowerMethod`（:4159-4191，
 *   `ReturnType = typeof(long)` + 两参 `typeof(long)` + `(long)Math.Pow(...)`，
 *   即上游 POWER 无浮点面，晋升是 fork 新增）与 `ToIntMethod`（:5500-5557，
 *   实参 `typeof(string)`，:5529-5544 读整数后允许尾随小数并弃掉 ⇒ 向零截断）。
 *   `spec: FunctionMethod.cs:580-584` 同属产物行号：本机该文件 366 行，
 *   `CheckArgumentType` 在 :302-306 / `CheckArgumentTypeEx` 在 :174，调用点
 *   `IdentifierDictionary.cs:580`。⇒ fork 面无法在本机逐行复核，按上游 + 真语料读数判定。
 *
 * 断言的是**具体读数**（85 = trunc(0.95³×100)），不是「不抛」。
 */
class PowerFloatRealCorpusTest {

    private val erbRoot: Path? = GameRoot981.erb()
    private val sourceRel = "ステータス計算関連/SOURCE/SOURCE_EXTRA.ERB"

    private companion object {
        /** 真文件 1 基物理行号（上报「行625」= 0 基的 625）。 */
        const val LINE_626 = 626
        const val LINE_627 = 627

        /** 原文快照：与本闸读取的真行做逐字比较，防止装置读错行。 */
        const val RAW_626 = "DOWNBASE:ARG:体力 = TOINT(POWER(0.95,ITEM:清扫魔法) * DOWNBASE:ARG:体力)"
        const val RAW_627 = "DOWNBASE:ARG:気力 = TOINT(POWER(0.95,ITEM:清扫魔法) * DOWNBASE:ARG:気力)"
    }

    /** 真文件某物理行的原文（编码探测与拆行走装载链同一套 [LogicalLineReader.readAllLines]）。 */
    private fun realRaw(physicalLine: Int): String {
        val file = erbRoot!!.resolve(sourceRel)
        val lines = LogicalLineReader.readAllLines(Files.readAllBytes(file))
        return lines[physicalLine - 1].trim()
    }

    /** 真行的右值（左值换成内建 1 维整型 `A`，右值一个字符不动）解析成表达式树。 */
    private fun realRhs(physicalLine: Int): Expr {
        val raw = realRaw(physicalLine)
        val text = "A =" + raw.substringAfter('=')
        val stmt = LineParser(SymbolTable()).parseLogicalLine(
            LogicalLine(LineKind.CODE, sourceRel.substringAfterLast('/'), physicalLine, text),
        )
        val assign = stmt as? AssignStmt ?: error("真语料行不是赋值：$raw")
        return (assign.args as? SpSetArgument)?.term ?: error("右值被折成常量形态：$raw")
    }

    private fun findFirst(e: Expr, pred: (Expr) -> Boolean): Expr? {
        if (pred(e)) return e
        val kids: List<Expr> = when (e) {
            is Binary -> listOfNotNull(e.left, e.right)
            is FuncCall -> e.args.filterNotNull()
            else -> emptyList()
        }
        for (k in kids) findFirst(k, pred)?.let { return it }
        return null
    }

    /** 受控读口：`ITEM:清扫魔法` 读持有数、`DOWNBASE:*` 读扣减值，其余 0。 */
    private class StubReader(
        var itemOwned: Long = 3L,
        var downBase: Long = 100L,
    ) : VariableReader {
        val reads = ArrayList<String>()

        override fun isConst(name: String): Boolean = false

        override fun readInt(name: String, indices: LongArray): Long {
            reads.add(name)
            return when (name) {
                "ITEM" -> itemOwned
                "DOWNBASE" -> downBase
                else -> 0L
            }
        }

        override fun readStr(name: String, indices: LongArray): String = ""

        override fun keywordToInteger(varName: String, dim: Int, keyword: String): Long = 77L

        override fun plusInt(name: String, indices: LongArray, delta: Long): Long = 0L
    }

    private class StubContext(override val variables: VariableReader) : EvalContext {
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw AssertionError("本形态应由 BuiltinEvalContext 的引用通道承接：$name")
    }

    /** 求值一条右值（真语料表达式树 + 受控变量读数），不经过任何手写副本。 */
    private fun run(term: Expr, reader: StubReader): Long {
        val store = VariableStore()
        val ctx = BuiltinEvalContext(StubContext(reader), store, object : BuiltinHost {})
        return term.getIntValue(ctx)
    }

    @Test
    fun `真语料 626 行原文与上报形态逐字一致`() {
        val root = erbRoot
        Assumptions.assumeTrue(root != null, "缺 4.981 语料根（ERATW_GAMEDIR / ERATW_CORPUS 都没指到 4.981）：跳过")
        assertEquals(RAW_626, realRaw(LINE_626))
        assertEquals(RAW_627, realRaw(LINE_627))
    }

    @Test
    fun `真语料 626 行 POWER 静态晋升为浮点且求值不抛`() {
        val root = erbRoot
        Assumptions.assumeTrue(root != null, "缺 4.981 语料根：跳过")
        val term = realRhs(LINE_626)
        val power = findFirst(term) { it is FuncCall && it.name == "POWER" }
        assertNotNull(power, "真语料行里必须有 POWER 调用节点")
        assertEquals(
            OperandType.FLOAT, power.operandType,
            "POWER(0.95, …) 的静态返回类型必须是 FLOAT：否则 FloatV 进 TOINT 的整型通道 ⇒ 上报的「需要数值」",
        )
        val mul = findFirst(term) { it is Binary }
        assertNotNull(mul, "POWER 的结果要乘 DOWNBASE")
        assertEquals(OperandType.FLOAT, mul.operandType, "FLOAT × INT ⇒ FLOAT")
        assertEquals(OperandType.INT, term.operandType, "TOINT 仍按整型返回（写回 INT 左值）")

        val reader = StubReader()
        assertEquals(85L, run(term, reader), "trunc(0.95^3 × 100) = trunc(85.73749999999998) = 85")
        assertTrue("ITEM" in reader.reads, "实参确实读了真变量 ITEM：${reader.reads}")
        assertTrue("DOWNBASE" in reader.reads, "实参确实读了真变量 DOWNBASE：${reader.reads}")
    }

    @Test
    fun `真语料 626 行的读数随幂与扣减基数变化`() {
        val root = erbRoot
        Assumptions.assumeTrue(root != null, "缺 4.981 语料根：跳过")
        val term = realRhs(LINE_626)
        // POWER 若被钉成 INT，0.95^k 一律截成 0 ⇒ 整式恒 0；这三档读数把「贯通」与「不抛」同时钉住
        assertEquals(100L, run(term, StubReader(itemOwned = 0)), "0.95^0 × 100 = 100")
        assertEquals(95L, run(term, StubReader(itemOwned = 1)), "0.95^1 × 100 = 95")
        assertEquals(90L, run(term, StubReader(itemOwned = 2)), "0.95^2 × 100 = 90.25 → 90")
        assertEquals(85L, run(term, StubReader(itemOwned = 3)), "0.95^3 × 100 → 85")
        assertEquals(0L, run(term, StubReader(itemOwned = 3, downBase = 0)), "扣减基数 0 ⇒ 0")
    }

    @Test
    fun `真语料 627 行与 626 行是同一条式的两次实例化`() {
        val root = erbRoot
        Assumptions.assumeTrue(root != null, "缺 4.981 语料根：跳过")
        val t626 = realRhs(LINE_626)
        val t627 = realRhs(LINE_627)
        val power627 = findFirst(t627) { it is FuncCall && it.name == "POWER" }
        assertNotNull(power627)
        assertEquals(OperandType.FLOAT, power627.operandType, "気力行同样晋升浮点")
        assertEquals(85L, run(t627, StubReader()), "気力行同式同读数")
        // 两行右值的结构差异**只**允许落在两处：① 行号戳（语料就是两条相邻物理行）；
        // ② `DOWNBASE` 的关键词下标原文（体力 / 気力）。归一化前先把这两处各自钉死，
        // 这样 shape() 的归一化不致退化成「放宽断言换绿」：列位点、结点类型、operandType
        // 晋升链全部逐字符相同，任一偏移都会在这里红。
        assertEquals(setOf(LINE_626, -1), preorder(t626).map { it.line }.toSet(), "626 行树里的行号戳")
        assertEquals(setOf(LINE_627, -1), preorder(t627).map { it.line }.toSet(), "627 行树里的行号戳")
        assertEquals(
            preorder(t626).map { it.col },
            preorder(t627).map { it.col },
            "同一条式的列位点必须逐结点相同（本闸归一化掉的只有行号）",
        )
        assertEquals(listOf("清扫魔法", "体力"), preorder(t626).filterIsInstance<KeywordIndexArg>().map(::keywordText), "626 行的关键词下标原文")
        assertEquals(listOf("清扫魔法", "気力"), preorder(t627).filterIsInstance<KeywordIndexArg>().map(::keywordText), "627 行的关键词下标原文")
        // 归一化后两行必须是同一棵树（POWER 晋升 FLOAT、TOINT 收 INT 的每一层 operandType 都算在内）
        assertEquals(
            shape(t626).replace("体力", "ELEM"),
            shape(t627).replace("気力", "ELEM"),
            "626/627 结构必须一致（差异只许是行号与元素名，上面四条已各自钉死）",
        )
    }

    /** 去掉行号位点的结构串（同一条式在两个物理行实例化，行号本就该不同）。 */
    private fun shape(e: Expr): String = e.toString()
        .replace(Regex("line=-?\\d+"), "line=N")
        .replace(Regex("col=-?\\d+"), "col=N")

    /** 子结点：本形态只有 二元 / 调用 / 变量 / 关键词下标 四类容器，余下是字面量。 */
    private fun children(e: Expr): List<Expr> = when (e) {
        is Binary -> listOfNotNull(e.left, e.right)
        is FuncCall -> e.args.filterNotNull()
        is VarRef -> e.indices
        is KeywordIndexArg -> listOf(e.expr)
        else -> emptyList()
    }

    /** 前序遍历的结点全集（行号戳、列位点、关键词原文都从这里取，不走 toString 正则）。 */
    private fun preorder(e: Expr): List<Expr> {
        val out = ArrayList<Expr>()
        fun go(x: Expr) {
            out.add(x)
            children(x).forEach(::go)
        }
        go(e)
        return out
    }

    /** 关键词下标的原文（`VAR:ARG:元素` 里的那个元素名，语料侧是字符串字面量）。 */
    private fun keywordText(k: KeywordIndexArg): String =
        (k.expr as? StrLiteral)?.value ?: error("关键词下标不是字符串字面量：$k")
}
