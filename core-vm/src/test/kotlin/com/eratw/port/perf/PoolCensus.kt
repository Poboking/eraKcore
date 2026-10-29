package com.eratw.port.perf

import com.eratw.port.boot.BootModel
import com.eratw.port.core.code.Program
import java.util.IdentityHashMap

/** 池分解表的一行：分组 / 项 / 计数 / 估算字节 / 口径说明。 */
internal class CensusRow(
    val group: String,
    val name: String,
    val count: String,
    val bytes: Long,
    val note: String,
)

/** 池分解结果：表格行 + 两类直方图（写报告脚注用，说明池里都是什么）。 */
internal class Census(
    val rows: List<CensusRow>,
    val exprTop: String,
    val stmtTop: String,
)

/**
 * `Program` 各池逐项计数 + 占用估算（只测量，不改语义）。
 *
 * 计数全部**精确**（直接读 `Program` 的公开 count / 下标访问）；字节是 [SizeModel]
 * 的估算。难点与处置：
 *
 * - `exprs` 池只存**树根**，子节点不在池里。故按 stride 抽样求「子树平均额外字节」，
 *   外推到全池（抽样数 = 4,000 或全量，取小者）。抽样用独立 visited 集，不跨样本共享。
 * - `statements` 池每个条目都带 `raw`（整行原文），且 `args` 下挂 Expr 子树。
 *   `raw` 逐条精确求和（`String.length` 是 O(1)）；`args` 子树同样抽样外推。
 * - `statements` 的 `args` 子树与 `exprs` 池**共享**同一批 Expr 实例（池做结构去重），
 *   两行会重复计入。故「合计」是**上限**，以 §1 的 JVM 实测增量为权威。
 * - `FunctionTable`（FuncMeta）与 `BootModel` 附属表用同一个 visited 集精确遍历。
 */
internal object PoolCensus {

    /** 每池抽样条数上限：够出均值，又不至于让反射遍历拖到分钟级。 */
    private const val SAMPLE_TARGET = 4000

    fun census(program: Program, model: BootModel, sizer: SizeModel): Census {
        val rows = ArrayList<CensusRow>()
        val instr = program.instructionCount

        // ---- 指令与行表（精确；映射/紧凑表示下必须读 ResidentBytes，读 code/lines 会物化）----
        rows += CensusRow(
            "指令与行表", "code 指令流", "$instr 条（常驻 ${program.codeResidentBytes} B）",
            program.codeResidentBytes, "映射来源 0 B；数组来源 16B 头 + 4B/字",
        )
        rows += CensusRow(
            "指令与行表", "lines 行号表", "紧凑行表常驻 ${program.lineTableResidentBytes} B",
            program.lineTableResidentBytes, "u16 主表 2B/条 + 溢出表",
        )

        // ---- 常量池 ----
        val constCount = program.constantCount
        rows += CensusRow(
            "池", "longs 常量池", "$constCount 条",
            constCount * 8L, "LongArray，8B/条",
        )

        // ---- 字符串驻留池（精确字符数 + 模型字节）----
        val strCount = program.stringCount
        var strChars = 0L
        var strBytes = 0L
        var strAscii = 0
        for (i in 0 until strCount) {
            val s = program.string(i)
            strChars += s.length
            strBytes += SizeModel.stringBytes(s)
            if (s.none { it.code > 0xFF }) strAscii++
        }
        rows += CensusRow(
            "池", "strings 驻留池", "$strCount 条 / $strChars 字符",
            strBytes, "String 对象 + 底层数组；latin1 ${strAscii}/${strCount} 条（其余 UTF-16，2B/char）",
        )

        // ---- 表达式池（精确浅尺寸 + 抽样外推子树）----
        val exprCount = program.exprCount
        var exprShallow = 0L
        val exprHist = HashMap<String, Int>()
        for (i in 0 until exprCount) {
            val e = program.expr(i)
            exprShallow += sizer.objectSize(e)
            exprHist.merge(e.javaClass.simpleName ?: e.javaClass.name, 1) { a, b -> a + b }
        }
        val exprExtra = estimateExprExtra(program, sizer, exprCount)
        rows += CensusRow(
            "池", "exprs 表达式池（根）", "$exprCount 条",
            exprShallow, "仅树根节点的浅尺寸；子树见下一行",
        )
        rows += CensusRow(
            "池", "exprs 子树（抽样外推）", "抽样 ≤$SAMPLE_TARGET 条",
            exprExtra, "⋍ 每根平均额外字节 × 全池；与 statements.args 共享实例，二者重复计入",
        )

        // ---- 语句池（精确浅尺寸 + 精确 raw 字符 + 抽样外推 args）----
        val stmtCount = program.statementCount
        var stmtShallow = 0L
        var rawChars = 0L
        var rawBytes = 0L
        val stmtHist = HashMap<String, Int>()
        for (i in 0 until stmtCount) {
            val s = program.statement(i)
            stmtShallow += sizer.objectSize(s)
            rawChars += s.raw.length
            rawBytes += SizeModel.stringBytes(s.raw)
            stmtHist.merge(s.javaClass.simpleName ?: s.javaClass.name, 1) { a, b -> a + b }
        }
        val argsExtra = estimateArgsExtra(program, sizer, stmtCount)
        rows += CensusRow(
            "池", "statements 语句池（对象）", "$stmtCount 条",
            stmtShallow, "按身份追加、不去重（Program.kt:47-58）",
        )
        rows += CensusRow(
            "池", "statements.raw 原文", "$rawChars 字符",
            rawBytes, "每条语句保留整行原文（Statement.kt:39）",
        )
        rows += CensusRow(
            "池", "statements.args 子树（抽样外推）", "抽样 ≤$SAMPLE_TARGET 条",
            argsExtra, "⋍ 每语句平均 args 子树 × 全池；与 exprs 池共享实例",
        )

        // ---- 构建期去重索引（随 Program 残留）----
        rows += CensusRow(
            "去重索引", "longIndex HashMap", "$constCount 项",
            constCount * 48L, "ConstantPool 持有；构建后不再使用但仍存活（Program.kt:37,118）",
        )
        rows += CensusRow(
            "去重索引", "stringIndex HashMap", "$strCount 项",
            strCount * 48L, "同上（Program.kt:40）",
        )
        rows += CensusRow(
            "去重索引", "exprIndex HashMap", "$exprCount 项",
            exprCount * 48L, "结构相等去重索引，构建后失效但存活（Program.kt:45）",
        )
        rows += CensusRow(
            "去重索引", "池 ArrayList 背衬数组", "3 个池",
            (constCount + strCount + exprCount + stmtCount) * 4L + 3 * 24L,
            "4 个 ArrayList 的 elementData（引用 4B/槽）",
        )

        // ---- 函数表 / 标签表（精确遍历）----
        var fnBytes = 0L
        var labelCount = 0
        for (f in program.functions) {
            fnBytes += sizer.objectSize(f)
            fnBytes += 16 + f.labelNames.size * 4L
            fnBytes += 16 + f.labelPcs.size * 4L
            for (nm in f.labelNames) fnBytes += SizeModel.stringBytes(nm)
            labelCount += f.labelNames.size
        }
        rows += CensusRow(
            "元数据", "Program.functions + 标签表", "${program.functions.size} 函数 / $labelCount 标签",
            fnBytes, "FunctionInfo 对象 + labelNames/labelPcs 平行数组",
        )

        val metas = model.table.all()
        var metaBytes = 0L
        run {
            val visited = IdentityHashMap<Any, Boolean>()
            for (m in metas) metaBytes += sizer.deepSize(m, visited)
        }
        rows += CensusRow(
            "元数据", "FunctionTable（FuncMeta）", "${metas.size} 条",
            metaBytes, "ProcessRuntime 构造时消费；含 key/name/position 字符串",
        )

        var attachBytes = 0L
        for ((_, inner) in model.labelPcs) attachBytes += 48L * (1 + inner.size)
        attachBytes += model.labelPcs.size * 48L
        rows += CensusRow(
            "元数据", "BootModel.labelPcs", "${model.labelPcs.size} 个函数",
            attachBytes, "每个标签 ≈ 48B（HashMap 节点 + 表槽）；键串与内层 map 未逐条展开",
        )
        rows += CensusRow(
            "元数据", "BootModel.dataSections", "${model.dataSections.size} 节",
            model.dataSections.size * 40L,
            "IdentityHashMap 节点；键是语句池实例（已在上面计入，不重复计量）",
        )

        val dims = model.globalVarDecls.size + model.userVarDecls.size
        var dimBytes = 0L
        run {
            val visited = IdentityHashMap<Any, Boolean>()
            for (d in model.globalVarDecls) dimBytes += sizer.deepSize(d, visited)
            for ((k, d) in model.userVarDecls) {
                dimBytes += sizer.deepSize(d, visited) + SizeModel.stringBytes(k)
            }
        }
        rows += CensusRow(
            "元数据", "#DIM 声明（DimSpec）", "$dims 条",
            dimBytes, "全局 ${model.globalVarDecls.size} + 私有 ${model.userVarDecls.size}",
        )

        return Census(
            rows = rows,
            exprTop = topHist(exprHist),
            stmtTop = topHist(stmtHist),
        )
    }

    /** 表达式池子树额外字节：stride 抽样，独立 visited 集。 */
    private fun estimateExprExtra(program: Program, sizer: SizeModel, count: Int): Long {
        if (count <= 0) return 0
        val stride = maxOf(1, count / SAMPLE_TARGET)
        var sampled = 0
        var extra = 0L
        var i = 0
        while (i < count) {
            val e = program.expr(i)
            val visited = IdentityHashMap<Any, Boolean>()
            val deep = sizer.deepSize(e, visited)
            extra += (deep - sizer.objectSize(e)).coerceAtLeast(0L)
            sampled++
            i += stride
        }
        return if (sampled == 0) 0L else extra * count / sampled
    }

    /** 语句 `args` 子树字节：stride 抽样；`raw` 不计入（已单独精确计量）。 */
    private fun estimateArgsExtra(program: Program, sizer: SizeModel, count: Int): Long {
        if (count <= 0) return 0
        val stride = maxOf(1, count / SAMPLE_TARGET)
        var sampled = 0
        var sum = 0L
        var i = 0
        while (i < count) {
            val s = program.statement(i)
            val visited = IdentityHashMap<Any, Boolean>()
            // raw 与 args 若有共享（同一 String 实例），不让 raw 在 args 侧再计一次
            visited.put(s.raw, true)
            sum += sizer.deepSize(s.args, visited)
            sampled++
            i += stride
        }
        return if (sampled == 0) 0L else sum * count / sampled
    }

    /** 直方图转「名前 8 项」文本，写进报告脚注。 */
    fun topHist(hist: Map<String, Int>, n: Int = 8): String =
        hist.entries.sortedByDescending { it.value }.take(n)
            .joinToString("，") { "${it.key} ${it.value}" }
}
