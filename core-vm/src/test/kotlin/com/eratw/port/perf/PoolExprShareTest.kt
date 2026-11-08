package com.eratw.port.perf

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * M6-MEMCODE 的**表达式池与语句 args 交叉去重**契约（`Program.internStatementArgs` 的种子段）。
 *
 * 锁死四件事，任何一条破了都是语义或装载差分回归：
 *
 * 1. **方向**：池根是规范表的首批代表，语句侧让位。故 `program.expr(k)` 的实例与
 *    data class 值（含 `line`/`col`）在规范化前后**逐字段不变**：装载差分测试
 *    （`ErbcStreamEquivalenceTest` / `MmapLoaderTest`）就是靠这条逐下标对拍。
 *    反例守卫：种子段一旦挪到语句段之后，语句侧的 `line`/`col` 会污染池根，池侧对拍当场红。
 * 2. **折叠判据 = 逐字段全等（含 `line`/`col`，INTERN-STMT）**：只有与池内形态**且行列号**
 *    全等的语句参数结点才换成池实例（`===`）；只差行列号的两侧各自保真。
 *    旧口径（本文件 2026-10 版本）在这里钉的是「同形态即折叠」，于是语句侧落盘的
 *    `line`/`col` 被换成池内代表的值 —— 规约 `specs/erbc-format.md` §5.3 把「两条只差行列号的
 *    表达式被错误合并」直接写成缺陷口径，`MappedCodeSourceTest` 的真实产物用例
 *    （映射装载 vs 全读装载的 `.erbc` 再编码逐字节相同）据此判红。
 * 3. **幂等**：第二次调用返回同一份读数，池根与语句参数身份都不再变。
 * 4. **边界**：`ArgInterner` 只能就地改写**列表字段**；`val` 单值字段（`ExpressionArgument.term`、
 *    `SpSetArgument.term` 等）只能下降登记、无法写回 —— 这类参数结点本波仍各存一份。
 *    这条测试把边界钉死，别把「已共享」误解成「全量共享」。
 */
class PoolExprShareTest {

    /** 列表形态参数（PRINT 家族等）：语句侧让位，池侧不动。 */
    @Test
    fun `列表参数并入表达式池且池根身份不变`() {
        val b = CodeBuilder()
        // 语句参数里的字面量：line/col = 3/4（与池根故意不同，验证行列号参与结构比较、不跨行列折叠）
        val argStmt = InstructionStmt(
            FunctionCode.PRINT,
            ExpressionArrayArgument(arrayListOf(IntLiteral(7L, 3, 4))),
            41,
            "PRINT 7",
        )
        b.statement(argStmt)
        // 池根：结构相同、line/col = 9/9
        val poolIndex = b.expr(IntLiteral(7L, 9, 9))
        b.emit(Opcode._EVAL_INT, poolIndex, SlotAddr.local(0), 0, 9)
        val p = build(b)

        val before = p.expr(poolIndex)
        val stats = p.internStatementArgs()
        val after = p.expr(poolIndex)

        // 1) 池根身份与值不变（含 line/col）
        assertSame(before, after, "池根实例不该被换掉（种子段必须先入表）")
        assertEquals(IntLiteral(7L, 9, 9), after, "池根 data class 值（含 line/col）必须逐字段不变")
        // 2) 语句侧**不再**折进池根：两条只差 `line`/`col` 的表达式必须各自保真
        //    （INTERN-STMT 改的正是这一条。旧口径在这里 assertSame「同形态的语句参数结点应换成
        //    池内代表实例」，代价是把池根的 9/9 写进本该落 3/4 的槽位，`.erbc` 再编码随之漂移：
        //    `specs/erbc-format.md` §5.3「`line`/`col` 必须逐字往返……两条只差行列号的表达式会被
        //    错误合并」把这种并合直接点名为缺陷口径，`MappedCodeSourceTest` 的真实产物用例按
        //    「映射装载 vs 全读装载再编码逐字节相同」判红。）
        val term = (p.statement(0).args as ExpressionArrayArgument).terms[0]
        assertNotSame(after, term, "只差行列号的语句参数结点不得折进池根代表实例")
        assertEquals(IntLiteral(7L, 3, 4), term, "语句侧结点的 line/col 必须原样读回")
        // 3) 读数：语句数不含种子行；本次没有任何跨行列号的合并可折 ⇒ merged 为 0
        assertEquals(1, stats.statements, "读数里的语句数应扣掉池种子行")
        assertEquals(0, stats.merged, "同形异行列号不再折叠后，本产物不该有合并读数，实际 ${stats.merged}")
    }

    /** 整棵子树共享：`Binary(1 + 2)` 两侧各一份 → 语句侧整棵让位，池根行号不被覆盖。 */
    @Test
    fun `表达式子树整体并入池根`() {
        val b = CodeBuilder()
        val stmtTree = Binary(
            OperatorCode.PLUS, IntLiteral(1L, 5, 5), IntLiteral(2L, 5, 7), OperandType.INT, 5, 5,
        )
        val argStmt = InstructionStmt(
            FunctionCode.PRINT, ExpressionArrayArgument(arrayListOf<com.eratw.port.core.expr.Expr?>(stmtTree)), 5, "PRINT 1+2",
        )
        b.statement(argStmt)
        val poolIndex = b.expr(
            Binary(OperatorCode.PLUS, IntLiteral(1L, 8, 8), IntLiteral(2L, 8, 10), OperandType.INT, 8, 8),
        )
        b.emit(Opcode._EVAL_INT, poolIndex, SlotAddr.local(0), 0, 8)
        val p = build(b)

        p.internStatementArgs()

        val pool = p.expr(poolIndex)
        val stmtTerm = (p.statement(0).args as ExpressionArrayArgument).terms[0]!!
        // INTERN-STMT：整棵子树同样不再折进池根（子树两侧只差行列号）；旧口径在这里 assertSame。
        assertNotSame(pool, stmtTerm, "只差行列号的子树不得整体折进池根实例")
        assertEquals(stmtTree, stmtTerm, "语句侧子树逐字段原样读回（含 line/col）")
        assertEquals(8, pool.line, "池根行号仍是自己的（未被语句侧覆盖）")
        assertEquals(5, stmtTerm.line, "语句侧子树保留自己的行号 5；旧口径把它换成池根的 8，落盘即漂移")
    }

    /**
     * 边界：`val` 单值字段写不回去。
     *
     * `ArgInterner.canonicalizeListFields` 只改写列表字段（`Argument.kt` 的 `term` 是 `val`），
     * 所以 `ExpressionArgument.term` 与池根**只共享到「登记」这一层**，实例仍是两份。
     * 剩余可回收上限 = `PoolDecomp` §2b 的「表达式池（独占）」16.4 MiB。
     *
     * TODO(verify)：要吃掉这一块得让 `ArgInterner` 支持重建 `Argument`（`rebuildArgument`），
     * 属 `core/stmt/ArgInterner.kt` 的接线面，本波文件所有权外。
     */
    @Test
    fun `单值参数字段不共享是已知边界`() {
        val b = CodeBuilder()
        val stmtExpr = IntLiteral(7L, 3, 4)
        b.statement(InstructionStmt(FunctionCode.PRINT, ExpressionArgument(stmtExpr), 41, "PRINT 7"))
        val poolIndex = b.expr(IntLiteral(7L, 9, 9))
        b.emit(Opcode._EVAL_INT, poolIndex, SlotAddr.local(0), 0, 9)
        val p = build(b)

        p.internStatementArgs()

        val term = (p.statement(0).args as ExpressionArgument).term
        assertEquals(IntLiteral(7L, 9, 9), p.expr(poolIndex), "池根不受影响")
        assertNotSame(p.expr(poolIndex), term, "已知边界：val 单值字段写不回，仍是两份实例")
        assertEquals(stmtExpr, term, "值不变（结构相等，只是没合并身份）")
    }

    /** 幂等：第二次调用返回同一份读数，池根与语句参数身份都不再变。 */
    @Test
    fun `规范化幂等`() {
        val b = CodeBuilder()
        b.statement(
            InstructionStmt(FunctionCode.PRINT, ExpressionArrayArgument(arrayListOf(IntLiteral(3L, 1, 1))), 2, "PRINT 3"),
        )
        val poolIndex = b.expr(IntLiteral(3L, 2, 2))
        b.emit(Opcode._EVAL_INT, poolIndex, SlotAddr.local(0), 0, 2)
        val p = build(b)

        val first = p.internStatementArgs()
        val poolAfterFirst = p.expr(poolIndex)
        val argsAfterFirst = p.statement(0).args
        val second = p.internStatementArgs()

        assertSame(first, second, "第二次调用应直接返回首次读数")
        assertSame(poolAfterFirst, p.expr(poolIndex), "池根身份不该在第二次调用里变化")
        assertSame(argsAfterFirst, p.statement(0).args, "语句参数实例不该在第二次调用里变化")
    }

    /** 池为空（无 `_EVAL_*` 产物）时种子段退化为空表，语句池照常去重。 */
    @Test
    fun `空表达式池不影响语句去重`() {
        val b = CodeBuilder()
        b.statement(InstructionStmt(FunctionCode.PRINT, ExpressionArgument(IntLiteral(1L, 1, 1)), 1, "PRINT 1"))
        b.statement(InstructionStmt(FunctionCode.PRINT, ExpressionArgument(IntLiteral(1L, 2, 2)), 2, "PRINT 2"))
        // 第三条与第二条**逐字段全等**（含行列号）：INTERN-STMT 之后这是唯一还允许合并的形态
        b.statement(InstructionStmt(FunctionCode.PRINT, ExpressionArgument(IntLiteral(1L, 2, 2)), 2, "PRINT 2"))
        b.emit(Opcode._EVAL_INT, 0, SlotAddr.local(0), 0, 1)
        val p = build(b)

        val stats = p.internStatementArgs()

        assertEquals(0, p.exprCount, "本产物没有表达式池")
        assertEquals(3, stats.statements, "种子行不该算进语句数")
        // INTERN-STMT：旧口径这里 assertSame(statement(0).args, statement(1).args)——
        // 两条只差行列号的参数被折成同一实例，落盘的 line/col 随代表实例漂移。
        assertNotSame(p.statement(0).args, p.statement(1).args, "只差行列号的参数不再共享")
        assertSame(p.statement(1).args, p.statement(2).args, "逐字段全等（含行列号）的参数仍照常共享同一实例")
    }

    private fun build(b: CodeBuilder): Program {
        val f = b.declareFunction("MAIN", 0, 0)
        return b.build(f)
    }
}
