package com.eratw.port.core.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C7 同源化收口（2026-11-08 波，eng2-c7）：`{}` 续行块内的 `#DIM` 在**两条装载路径**
 * （erbcc 编译期 `LabelTable.scanLabels` 的 `dimSpecs` / ERH 全局 `#DIM CONST` 的
 * [Symbols.collectErhSymbols]）必须同值。
 *
 * 根因（B1 对拍回执登记、本波实读复核仍在）：`scanLabels` 旧实现自带 `cont` 状态机
 * 逐行扫 `LogicalLineReader.splitLines` 并把块内行**整行跳过**，而 ERH 路径走
 * [Symbols.erhLogicalLinesWithLines]（**合并** `{}` 块）——两条装载路径不同源。
 * 原版两侧是同一个 `EraStreamReader.ReadEnabledLine`（spec: EraStreamReader.cs:130-184，
 * ErbLoader.cs:387 与 ErhLoader 均经它取逻辑行）：块内行合并成一条逻辑行后再走同一套
 * `#`/`@`/`$` 分派，块内 `#DIM` 与块外同权。语料实测 60 条块内 `#DIM(S) CONST`
 * （COMMON.ERB:2203-2207、DRAW_COLOREDMAP.ERB:166 等），旧实现收不到、原版可收集。
 *
 * 修复后单一实现：`scanLabels` 委托 `erhLogicalLinesWithLines`，`disabled` 判据传
 * `pp.disabled` 复刻原版 `ReadEnabledLine(pPState.Disabled)` 的「Disabled 期间不吞块」
 * 分支（spec: EraStreamReader.cs:130 `if (!disabled)`）——否则 `[SKIPSTART]` 区间内的
 * `[SKIPEND]` 会被吞进合并行、永远解不开区间（反向用例 3 钉住）。
 */
class BlockContinuationDimParityTest {

    /** 语料同形样本（COMMON.ERB @PRINT_ALPHABET:2197-2211 的浓缩形）。 */
    private val erbSample = """
        @PRINT_RANK(RANK, RANK_NUM, ARG)
        #DIM RANK
        {
        #DIMS  CONST C_RANK10, 10 =
        	"C_P_PURPLE", "C_WHITE", "C_WHITE", "C_PINK", "C_RED",
        	"C_ORANGE", "C_YELLOW", "C_GREEN", "C_AQUA", "C_GRAY"
        }
        #DIMS CONST C_RANK7, 7 = "C_P_PURPLE", "C_WHITE"
    """.trimIndent() + "\n"

    @Test
    fun `ERB 路径 - 块内 DIMS CONST 与块外同权收集`() {
        val labels = LabelTable.scanLabels(erbSample, file = "PARITY.ERB")
        assertEquals(1, labels.size)
        val dims = labels[0].dimSpecs
        // 块外 RANK → 块内 C_RANK10（合并行，初值跨行并入）→ 块后 C_RANK7（# 行不夺归属）
        assertEquals(listOf("RANK", "C_RANK10", "C_RANK7"), dims.map { it.name })
        val c10 = dims.first { it.name == "C_RANK10" }
        assertTrue(c10.isStr)
        assertEquals(listOf("10"), c10.dims)
        // 合并把三行初值并成一条逻辑行：没有合并就没有这个 initializer
        assertTrue(c10.initializer!!.contains("C_P_PURPLE") && c10.initializer.contains("C_GRAY"))
    }

    @Test
    fun `ERH 路径 - 同一块形收集同名常量（两路同值）`() {
        val erh = """
            {
            #DIMS CONST C_RANK10, 10 =
            	"C_P_PURPLE", "C_WHITE", "C_WHITE", "C_PINK", "C_RED",
            	"C_ORANGE", "C_YELLOW", "C_GREEN", "C_AQUA", "C_GRAY"
            }
        """.trimIndent() + "\n"
        val got = LabelTable.collectErhSymbols(erh, "PARITY.ERH")
        assertNull(got.diag.firstOrNull(), "ERH 侧不应有装载诊断：${got.diag}")
        val c10 = got.dimConsts.single { it.name == "C_RANK10" }
        assertTrue(c10.isStr)
        assertEquals(listOf("10"), c10.dims)
        // 与 ERB 路径同值：同一 `{}` 语义（单一实现）下，两条路径对同一声明
        // 产出同 dim 数与同初值原文
        val erbSide = LabelTable.scanLabels(
            "@F\n" + erh.trimEnd() + "\n",
            file = "PARITY.ERB",
        )[0].dimSpecs.single { it.name == "C_RANK10" }
        assertEquals(c10.dims, erbSide.dims)
        assertEquals(c10.initializer, erbSide.initializer)
    }

    @Test
    fun `Disabled 区间内不吞块 - SKIPEND 不被合并行掩埋`() {
        // 原版 ReadEnabledLine(true) 不进入续行处理（EraStreamReader.cs:130 if (!disabled)）：
        // `{`/`X`/`[SKIPEND]`/`}` 逐行到达预处理状态机，区间在 [SKIPEND] 解锁，
        // 其后的 @F 照常登记。若把 `{}` 合并做成无条件，`X [SKIPEND]` 会并成一条
        // 非 `[` 起头的普通逻辑行、[SKIPEND] 永远到不了状态机 ⇒ @F 丢失。
        val text = """
            [SKIPSTART]
            {
            X
            [SKIPEND]
            }
            @F
            #DIM QV
        """.trimIndent() + "\n"
        val labels = LabelTable.scanLabels(text, file = "SKIP.ERB")
        assertEquals(listOf("F"), labels.map { it.name })
        // 名字避开系统变量（`Q` 是 VariableData.cs:3056 varTokenDic 在册的系统变量，
        // 原版 CodeEE 不注册——checkUserPrivateVarName 同名拒绝是正确行为，不是丢收集）
        assertEquals(listOf("QV"), labels[0].dimSpecs.map { it.name })
    }

    @Test
    fun `块内非块外 - 合并行行号取闭合括号行（P6 原版 ScriptPosition 口径）`() {
        // 原版 ReadLine 每读一行推 curNo（EraStreamReader.cs:80-85），合并行返回时
        // LineNo = 闭合 `}` 行。块内 #DIM 撞系统变量名被拒（warnLevel 2）时告警行号
        // 须是 4（} 行），不是 3（{ 行）——行为层钉住两条路径消费同一行号口径。
        val text = """
            @F
            {
            #DIM RESULT
            }
        """.trimIndent() + "\n"
        val warnings = mutableListOf<LoadWarning>()
        LabelTable.scanLabels(text, file = "P6.ERB", warnings = warnings)
        val w = warnings.single { "RESULT" in it.message }
        assertEquals(4, w.line)
    }
}
