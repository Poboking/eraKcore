package com.eratw.port.core.rand

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.StmtParseException
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.RandProvider
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `Config.CompatiRAND` 的两态分叉（SAVE-09b）。
 *
 * 开关在两处消费，**两处都要**跟着它走：
 *   1. 取值面：`VariableData.cs:3154` 的三元式选 `RandToken` 还是 `CompatiRandToken`
 *      （`RandToken.GetIntValue` :845-853 = `GetNextRand(n)`；`CompatiRandToken` :863-875 =
 *      `n==0 → 0`、`n<0 → -n`、`GetNextRand(32768) % n`）。移植侧等价物 = `VarConfig.compatiRand`
 *      → `VariableStore.random`，本类用**同一颗种子**的两态对拍钉住「走了哪条通道」。
 *   2. 解析面：`VariableParser.cs:160-170` —— 关时 `RAND` 省略下标报 `OmittedRandArg`、
 *      `RAND:0` 报 `RandArgIsZero`；开时两者合法（eramaker 口径）。移植侧 = `ParserConfig.compatiRAND`。
 *
 * 绝对值真值（SFMT 序列、`Long.MIN_VALUE` 边界、RANDDATA 游标）由 `RngParityAuditTest` §8 钉，
 * 本类只管「同一个键的两种取值各走哪条路」。
 * 语料现值 `emuera.config:52` = NO ⇒ 与出厂缺省（`ConfigData.cs:98`）一致，基准读数不变。
 */
class CompatiRandKeyTest {

    /** 两态探针：32768 上界（Compati 通道的固定上界）返回 5，其余上界返回 7。 */
    private fun probe() = RandProvider { bound -> if (bound == 32768L) 5L else 7L }

    private fun store(compatiRand: Boolean, rng: RandProvider): VariableStore =
        VariableStore(config = VarConfig(compatiRand = compatiRand), randProvider = rng)

    private fun rand(s: VariableStore, n: Long): Long = s.readInt("RAND", longArrayOf(n))

    @Test
    fun `开：RAND 走 GetNextRand32768 再取模`() {
        val s = store(compatiRand = true, rng = probe())
        assertEquals(2L, rand(s, 3L), "5 % 3")
        assertEquals(0L, rand(s, 0L), "n==0 直接返回 0（不取数）")
        assertEquals(2L, rand(s, -3L), "n<0 取绝对值后 5 % 3")
    }

    @Test
    fun `关：RAND 直接按上界取数`() {
        val s = store(compatiRand = false, rng = probe())
        assertEquals(7L, rand(s, 3L), "通道上界是 n 本身（stub 只在 32768 上返回 5）")
        assertEquals(7L, rand(s, 1000L))
        // spec: VariableData.cs:846-850 —— 非 Compati 通道 n<=0 直接 CodeEE。
        assertFailsWith<CodeEE> { rand(s, 0L) }
        assertFailsWith<CodeEE> { rand(s, -5L) }
    }

    @Test
    fun `开：真 SFMT 上与 GetNextRand32768 取模一致`() {
        // 与 RngParityAuditTest 同口径：同一颗种子并行跑一个 Sfmt19937 当镜像，
        // 只有走 32768 通道才会逐次对齐（走 64 位通道时上界是 n，镜像取的是 32768）。
        val seed = 20261017L
        val s = store(compatiRand = true, rng = Sfmt19937(seed))
        val mirror = Sfmt19937(seed)
        for (n in listOf(3L, 7L, 1000L)) {
            assertEquals(mirror.next(32768L) % n, rand(s, n), "RAND($n)")
        }
    }

    private fun parser(compatiRAND: Boolean): LineParser = LineParser(
        SymbolTable().declareVariable("A", OperandType.INT, 1),
        ParserConfig(compatiRAND = compatiRAND),
    )

    private fun parse(text: String, compatiRAND: Boolean): Statement? =
        parser(compatiRAND).parseLogicalLine(LogicalLine(LineKind.CODE, "TEST.ERB", 10, text))

    @Test
    fun `关：解析期 RAND 省略下标与 RAND 0 都报错`() {
        val omitted = assertFailsWith<StmtParseException> { parse("A = RAND", compatiRAND = false) }
        assertTrue(omitted.message!!.contains("RAND 不能省略下标"), omitted.message)
        val zero = assertFailsWith<StmtParseException> { parse("A = RAND:0", compatiRAND = false) }
        assertTrue(zero.message!!.contains("RAND 的参数不能为 0"), zero.message)
    }

    @Test
    fun `开：解析期 RAND 省略下标与 RAND 0 都合法`() {
        // 省略下标 → 补 `:0`（VariableParser.cs:160-166 的 ZeroTerm 分支），不报错。
        assertIs<com.eratw.port.core.stmt.AssignStmt>(parse("A = RAND", compatiRAND = true))
        assertIs<com.eratw.port.core.stmt.AssignStmt>(parse("A = RAND:0", compatiRAND = true))
    }
}
