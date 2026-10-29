package com.eratw.port.core.code

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.sem.FlatStmtSource
import com.eratw.port.core.stmt.Statement

/**
 * T2-64 波次 D：**分片惰性扁平池** —— 装载期零压扁，只有执行到的语句所在片才解码/压扁。
 *
 * ## 为什么要它
 *
 * 整池压扁（`ensureFlatStmtSource` 的整池形态）实测常驻 ≈396 MiB：int 流 67 MB +
 * ops/heads/lines 各 6.9 MB + **807k 个表达式根结点（含子结点约 200 万节点）**。
 * 512 MiB 堆挡位装不下（`.workbuddy/CONTRACT-T2-64.md` §10.6）。而设备的惰性树基线
 * 只有 64 MiB 量级 —— 差距全在「把没跑到的语句也压了」。
 *
 * 分片把这件事变成按需：语句池按 [segmentSize] 条切段，首次访问某段时才
 * 「解码该段 → 压扁 → 弃树」，常驻只随**真跑到的段数**增长，上限由 [maxSegments] 卡住
 * （LRU 淘汰）。装载期因此回到惰性树的读数，运行期逐步涨到「热代码那几段」。
 *
 * ## 每片一条独立的参数流
 *
 * 每片自带 `ops/heads/stream/strings/extraExprs`（一个 [FlatStatements] 实例，下标在片内从 0 起），
 * 因此 [argStreamBase] 返回的是**当前片**的数组、[headAt] 返回**片内**偏移。这是本类唯一的
 * 使用契约：
 *
 * > 同一条语句的全部读点（`argStreamBase`/`headAt`/`exprAt`/`stringAt`/`endAt`）必须在
 * > 「没有别的语句插进来」的窗口内完成。
 *
 * 成立的理由：语句执行是顺序的，`StatementExecutor` 在一条语句内只读本语句的槽位，
 * 不会嵌套执行另一条语句。**若将来出现嵌套执行（例如某条指令回调里再跑语句），
 * 必须改成「按 index 取记录句柄」的 API** —— 这条写在这里是防止后人踩。
 *
 * ## 线程
 *
 * 与惰性池同口径（ADR-003）：单线程（VM 线程）访问；`Program.statement(i)` 的诊断读面
 * 可能来自别的线程，但那条路不经过本类。并发首访最多重复建一次同一片，语义无损
 * （与 `Program.ensureFlatStmtSource` 的幂等口径一致）。
 */
internal class SegmentedFlatStmtSource(
    private val pool: ConstantPool,
    private val program: Program,
    private val segmentSize: Int = DEFAULT_SEGMENT_STATEMENTS,
    private val maxSegments: Int = DEFAULT_MAX_SEGMENTS,
) : FlatStmtSource {

    private companion object {
        /** 每片语句数。真语料 171.7 万条 ⇒ 420 片；片内平均 ≈0.94 MB（整池 396 MiB 摊薄）。 */
        const val DEFAULT_SEGMENT_STATEMENTS = 4096

        /**
         * 同时驻留的片数上限（LRU）。64 片 = 262,144 条语句 ≈ 池的 7.6%~15%。
         *
         * 2026-10-29 真机实测（vivo V2199A / 512 MiB，干净窗口协议：冷启动 → 点开始游戏 →
         * 确认前台且非标题页 → 连续采样）：关池 141.9–149.5 MiB，开池（64 片）256.2–260.7 MiB
         * ⇒ **增量 ≈114 MiB**，比桌面估算的 ≤60 MiB 高。
         *
         * **调参无效已被实测排除**：32 片 260.7 MiB、64 片 260.5–260.7 MiB（一样）；
         * 1,024 条/片 246.8–255.8 MiB（只低 5~10）。⇒ 这 114 MiB 不是片缓存的量，
         * 而是执行路径上的固定开销（待堆 dump 归因，见 CONTRACT-T2-64 §10.9）。
         * 在归因清楚之前，Android 侧默认关（GameHost 传 false），桌面/测试保持开。         */
        const val DEFAULT_MAX_SEGMENTS = 64

        /** 空参数流（还没建过任何片时的 `argStreamBase` 返回值）。 */
        val NO_SLOTS = IntArray(0)
    }

    /** LRU：accessOrder=true，`blocks[seg]` 的读也算一次访问。 */
    private val blocks = LinkedHashMap<Int, FlatStatements>(16, 0.75f, true)

    private var currentSeg: Int = -1
    private var currentBase: Int = 0
    private var currentBlock: FlatStatements? = null

    /** 已建片数（诊断/探针读数）。 */
    internal var builtSegments: Int = 0
        private set

    /** 当前驻留片数。 */
    internal val residentSegments: Int get() = blocks.size

    private fun blockFor(index: Int): FlatStatements {
        val seg = index / segmentSize
        val cur = currentBlock
        if (seg == currentSeg && cur != null) return cur
        val hit = blocks[seg]
        val block = if (hit != null) {
            hit
        } else {
            val from = seg * segmentSize
            val until = minOf(from + segmentSize, program.statementCount)
            val built = pool.buildFlatStatementsRange(from, until)
            blocks[seg] = built
            builtSegments++
            trim()
            built
        }
        currentSeg = seg
        currentBase = seg * segmentSize
        currentBlock = block
        return block
    }

    /** 超出上限时淘汰最久未用的片（当前片刚被访问，必然最“新”）。 */
    private fun trim() {
        if (maxSegments < 2) return
        while (blocks.size > maxSegments) {
            val eldest = blocks.keys.iterator()
            if (!eldest.hasNext()) return
            eldest.next()
            eldest.remove()
        }
    }

    override fun opAt(index: Int): Int = blockFor(index).opAt(index - currentBase)

    override fun headAt(index: Int): Int = blockFor(index).headAt(index - currentBase)

    override fun endAt(index: Int): Int = blockFor(index).headAt(index - currentBase + 1)

    override fun argStreamBase(): IntArray = currentBlock?.stream ?: NO_SLOTS

    override fun exprAt(poolIdx: Int): Expr? {
        if (poolIdx == ErbcFormat.NONE_REF) return null
        val block = currentBlock ?: return null
        return block.exprAt(poolIdx)
    }

    override fun stringAt(poolIdx: Int): String {
        val block = currentBlock ?: return ""
        return block.strAt(poolIdx)
    }

    /** 兜底还原（探针/诊断/数据节降级）：回装载期物化实例，与整池形态同一实例身份契约。 */
    override fun statementAt(index: Int): Statement = program.statement(index)

    /**
     * **诊断/测试口径**：把 [index] 所在片整片还原成语句（会切片），用于对拍「分片压扁与
     * 整池压扁逐条等价」。生产路径不用它（还原会新建实例，不满足 `statementAt` 的身份契约）。
     */
    internal fun decodeBlock(index: Int): List<Statement> {
        val block = blockFor(index)
        return List(block.count) { block.statementAt(it) }
    }
}
