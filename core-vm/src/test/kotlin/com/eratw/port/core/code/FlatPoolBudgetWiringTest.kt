package com.eratw.port.core.code

import com.eratw.port.core.host.MemoryTier
import com.eratw.port.core.sem.FlatStmtSource
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [Program.ensureFlatStmtSource] 与 [FlatPoolBudget] 的**接线读数**：构建时用的片数上限必须就是
 * 预算判式按「本进程堆 + 本进程档位」给出的那个数（[Program.flatSegmentLimit] 是唯一读面）。
 *
 * ## 为什么用「对拍函数结果」而不是写死 32 / 64
 *
 * 测试 JVM 的 `-Xmx` 由构建脚本决定，写死片数等于把用例绑到某台机的堆挡位上。这里一律用
 * **本 JVM 真实的** `Runtime.getRuntime().maxMemory()` 与 `MemoryTier.isGenerous(同一个读数)`
 * 现算期望值，再与 `flatSegmentLimit` 对拍 —— 堆够 512 MiB 时它会自然钉到 32 / 64，
 * 堆不够时钉到降级的 1 片，两种形态都不假设数值。
 *
 * ## 降级用例为什么走「注入预算」而不是伪造堆
 *
 * `Runtime.maxMemory()` 是进程事实，`MemoryTier` 又只读一个 API 级别性质（[MemoryTier.SDK_INT_PROPERTY]），
 * 测试无法在一台大堆 JVM 上伪造「256 MiB 挡」。于是把「预算」做成
 * `ensureFlatStmtSource(budget)` 这条可注入的内部入口（生产另有无参那条，读真实堆），
 * 用一份**合成的 disabled 预算**（片数 0 + 片容量 2 条，好让 40 余条语句真的跨很多片）
 * 钉住「降级到 1 片」这条判式本身。取这条而不是拿 `buildSegmentedFlatSource(4096, 1)`
 * 从旁边绕：后者测的是诊断口，测不到 `ensureFlatStmtSource` 的降级分支。
 *
 * 性质一律 `try/finally` 清掉，避免把 SDK 挡位泄漏给同 JVM 的其他用例。
 */
class FlatPoolBudgetWiringTest {

    private fun sampleProgram(statements: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val ids = ArrayList<Int>(statements)
        for (i in 0 until statements) {
            ids.add(b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), i + 1, "")))
        }
        for (k in ids.indices) b.emit(Opcode.WAIT, ids[k], 0, SlotAddr.NONE, k + 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    /** 预算落到构建点的片数：enabled 用 [FlatPoolBudget.maxSegments]，disabled 降级到 1 片。 */
    private fun expectedLimit(budget: FlatPoolBudget): Int = if (budget.enabled) budget.maxSegments else 1

    @Test
    fun `片数上限 = 堆预算判式（放开档性质落下）`() {
        val program = sampleProgram(8)
        assertEquals(0, program.flatSegmentLimit, "未构建时读面必须是 0（不替调用方建池）")

        val maxMemoryBytes = Runtime.getRuntime().maxMemory()
        try {
            System.setProperty(MemoryTier.SDK_INT_PROPERTY, "34")
            val budget = FlatPoolBudget.forHeap(maxMemoryBytes, MemoryTier.isGenerous(maxMemoryBytes))
            program.ensureFlatStmtSource()

            assertEquals(
                expectedLimit(budget),
                program.flatSegmentLimit,
                "构建点必须用 forHeap(Runtime.maxMemory(), isGenerous(同一个读数)) 的判式：预算=$budget",
            )
            // 堆 >= 512 MiB 的构建机上这就是放开档的 64 片；小堆机上则是降级的 1 片。
            assertEquals(
                if (budget.enabled) FlatPoolBudget.GENEROUS_MAX_SEGMENTS else 1,
                program.flatSegmentLimit,
            )
            assertEquals(0, program.flatResidentSegmentCount, "分片源是惰性的：刚构建一片未建")
            assertEquals(0, program.flatBuiltSegmentCount)
        } finally {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
        }
    }

    @Test
    fun `片数上限 = 堆预算判式（保守档：性质缺失）`() {
        val program = sampleProgram(8)
        val maxMemoryBytes = Runtime.getRuntime().maxMemory()
        try {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
            val budget = FlatPoolBudget.forHeap(maxMemoryBytes, MemoryTier.isGenerous(maxMemoryBytes))
            program.ensureFlatStmtSource()

            assertEquals(expectedLimit(budget), program.flatSegmentLimit, "保守档判式：预算=$budget")
            assertEquals(
                if (budget.enabled) FlatPoolBudget.CONSERVATIVE_MAX_SEGMENTS else 1,
                program.flatSegmentLimit,
            )
            assertEquals(0, program.flatResidentSegmentCount, "惰性：构建不等建片")
        } finally {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
        }
    }

    @Test
    fun `预算 disabled 但显式开 flat 时降到 1 片而不是拒绝构建`() {
        val program = sampleProgram(41)
        // 合成 disabled 预算：真实 DISABLED 的片容量是 4,096 条，41 条语句挤在 1 片里就看不出
        // 「上限 1 片」有没有生效；片容量改成 2 条 ⇒ 跨 21 片，LRU 上限必须是 1。
        val disabledTiny = FlatPoolBudget(
            maxSegments = 0,
            segmentStatements = 2,
            residentBytes = 0L,
            reserveBytes = 0L,
        )
        val src = program.ensureFlatStmtSource(disabledTiny)
        assertFalse(disabledTiny.enabled)
        assertEquals(1, program.flatSegmentLimit, "预算 disabled ⇒ 1 片（既有诊断口 trim() 的下限口径）")

        // 逐条读一遍：正确性不受影响（RHS 只为触发建片），常驻恒 1 片、累计建片 = 全部片数。
        for (i in 0 until program.statementCount) src.opAt(i)
        val segments = (program.statementCount + 1) / 2
        assertTrue(segments > 1, "夹具必须真的跨多片，否则这条用例量不出上限：$segments 片")
        assertEquals(1, program.flatResidentSegmentCount, "disabled 降级后常驻片数必须卡在 1")
        assertEquals(segments, program.flatBuiltSegmentCount, "每片都被建过一次（淘汰不影响正确性）")

        assertSame(src, program.ensureFlatStmtSource(), "幂等：注入预算形态也不会二次构建")
        assertSame(src, program.ensureFlatStmtSource(FlatPoolBudget.forHeap(Long.MAX_VALUE, true)), "先构建者胜")
        assertEquals(1, program.flatSegmentLimit, "后到的预算不许改写已在用的片数上限")
    }

    @Test
    fun `正常预算下驻留片数随访问增长且不超过上限`() {
        val program = sampleProgram(41)
        // 片容量 2 条、上限 4 片：访问到第 9 片时驻留必须停在 4（预算给的 maxSegments 真的被用上）。
        val budget = FlatPoolBudget.forHeap(FlatPoolBudget.MIN_HEAP_BYTES, generous = true)
            .copy(segmentStatements = 2, maxSegments = 4)
        val src: FlatStmtSource = program.ensureFlatStmtSource(budget)
        assertEquals(4, program.flatSegmentLimit)

        for (i in 0 until program.statementCount) src.opAt(i)
        assertEquals(4, program.flatResidentSegmentCount, "驻留片数必须卡在预算的 maxSegments")
        assertTrue(program.flatBuiltSegmentCount > 4, "累计建片应超过上限（被淘汰的也算）")
    }
}
