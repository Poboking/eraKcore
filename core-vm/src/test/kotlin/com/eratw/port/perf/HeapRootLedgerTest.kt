package com.eratw.port.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **账面算术自证用例**（OBS-HEAPROOT 出口判据）：钉住探针的恒等式
 * 「入账面合计 + 未归类 = live 总额（差额全进未归类，不许抹平）」与三种判定
 * （BALANCED / OVERCOUNT / BIG_GAP）。
 *
 * 这里只测 [RootLedger] 的算术与判定 —— 产物相关的恒等式由探针本体在真跑时
 * 自检（OVERCOUNT ⇒ `:core-vm:heapRootAttribution` exit 1），互不重复。
 * 定向运行：`./gradlew --configure-on-demand :core-vm:test --tests "*HeapRootLedger*"`
 */
class HeapRootLedgerTest {

    private val mib = 1024L * 1024L

    @Test
    fun `恒等式 入账合计 加 未归类 恒等于 live 总额`() {
        val ledger = RootLedger(liveBytes = 280L * mib)
        ledger.add("指令/code 段", "页缓存", bytes = 1L * mib)
        ledger.add("语句对象树", "骨架", bytes = 120L * mib)
        ledger.add("变量池", "全局列", bytes = 40L * mib)
        // 交叉核对行不入账
        ledger.add("jcmd 交叉核对", "live 总量", bytes = 275L * mib, source = "jcmd直方图", counted = false)
        // 负数行（堆外标记 -1）不扣账：coerceAtLeast(0)
        ledger.add("段0 全局串表", "数据块映射", bytes = -1L, source = "地址空间")

        assertEquals(161L * mib, ledger.countedSum())
        assertEquals(280L * mib - 161L * mib, ledger.unclassified())
        assertEquals(280L * mib, ledger.countedSum() + ledger.unclassified())
    }

    @Test
    fun `入账合计越过 live 加容差 判 OVERCOUNT`() {
        val ledger = RootLedger(liveBytes = 100L * mib)
        ledger.add("语句对象树", "重复计账行", bytes = 111L * mib)
        assertEquals(RootLedger.Verdict.OVERCOUNT, ledger.verdict(tolBytes = 10L * mib))
        // 容差内（GC 噪声 + 建模误差标定 ±10 MiB）仍判 BALANCED
        val ok = RootLedger(liveBytes = 100L * mib)
        ok.add("语句对象树", "微超", bytes = 108L * mib)
        assertEquals(RootLedger.Verdict.BALANCED, ok.verdict(tolBytes = 10L * mib))
    }

    @Test
    fun `未归类超过警戒占比 判 BIG_GAP 且差额逐 MiB 可见`() {
        val ledger = RootLedger(liveBytes = 200L * mib, gapWarnRatio = 0.25)
        ledger.add("变量池", "只点了一半", bytes = 100L * mib)
        assertEquals(RootLedger.Verdict.BIG_GAP, ledger.verdict(tolBytes = 10L * mib))
        assertEquals(100L * mib, ledger.unclassified())
        assertTrue(ledger.unclassified() > 0L, "未归类必须逐 MiB 存在，不许摊平进各面")
    }

    @Test
    fun `各面合计只聚合入账行`() {
        val ledger = RootLedger(liveBytes = 50L * mib)
        ledger.add("CSV 表", "整桶", bytes = 10L * mib)
        ledger.add("CSV 表", "口径行不入账", bytes = 99L * mib, source = "GC差(不入账)", counted = false)
        ledger.add("控制台行表", "行壳", bytes = 2L * mib)
        val totals = ledger.surfaceTotals()
        assertEquals(10L * mib, totals["CSV 表"])
        assertEquals(2L * mib, totals["控制台行表"])
        assertEquals(2, ledger.rows.count { it.counted })
    }
}
