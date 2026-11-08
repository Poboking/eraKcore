package com.eratw.port.core.code

import java.util.AbstractList

/**
 * R7-C2：把补丁 [Program] 拼到 base 之后，产出一个**可交给 VM 直接执行**的 [Program]
 * （CONTRACT-R7-C §2/C2）。补丁是 C1 的 `PatchCompiler` 产物或合成构造：一个带基线的
 * 窗口 Program（[Program.codeBase] 等六个基线读数标出它在绝对下标空间里的位置），
 * 本类把它按 CONTRACT-R7.md §1 的 8 个访问点接到 base 的计数之后。
 *
 * ## 拼接面（8 个访问点逐项）
 *
 * | 访问点 | 拼接形态 |
 * |---|---|
 * | `codeSource` | [CompositeCodeSource]：base 整页直通（零拷贝）+ 补丁段物化字 |
 * | `instructionCount` | 拼接来源的总字数，天然是绝对口径 |
 * | `functions` | [CompositeFunctionTable]：base 段按下标直通（惰性表不物化）+ 补丁尾段 |
 * | `statement` / `expr` / `string` / `constant` | [ConstantPool] 组合池（basePool 分派）+ 续段逐位追加 |
 * | `lineOf` | [LineTable.composite]：按 pc 分派，两段各自查表（base 段零物化） |
 * | `callArgsAt` | 两张 [CallArgIndex] 按 pc 升序合并（补丁键必须落在补丁区间） |
 * | `entryFunction` | **恒为 base 的**（铁律：补丁只追加函数，不改入口） |
 * | `globalSlots` | **恒为 base 的**；补丁引用超出 base 槽空间的全局槽会被拒绝（铁律 3：存档列不许变） |
 *
 * 取用分派全部收在 [CompositeCodeSource] / [LineTable.composite] / 组合 [ConstantPool]
 * 内部，[Program] 的公开读面一行分支都不加——这是波 B 预铺组合形态时钉死的方向
 * （`ConstantPool` 类 KDoc：「取用分派收在池内部，Program 的六个访问点一行都不用改」）。
 *
 * ## 返回类型为什么必须是 Program
 *
 * VM 取指、`ProcessRuntime` 的函数表与调用点绑定全部按 [Program] 的公开读面消费；
 * 返回 Program 让会话装配点（波 C4）零改型。
 *
 * ## 两道闸（违反即拒绝，不做静默纠错）
 *
 * 1. **窗口对齐**（构造期 require，装载期一次性）：补丁的六个基线必须**正好**接在 base
 *    的对应计数之后——差一点就是绝对下标空间里出现空洞或重叠，取用会静默落到错的段
 *    （某个角色念别人的台词）。这与波 C4 的 I3（补丁身份绑定）同源：那边对的是补丁头
 *    字段，这边对的是编译产物自身的基线读数。
 * 2. **I2 线性自检**（装配后一次性扫拼接结果，CONTRACT-R7.md §1.6）：`_CALL` 的 a <
 *    总函数数；marker 的 a < 总语句数；`_EVAL_*` 的 a < 总表达式数；字符串/常量下标 <
 *    各自总数；补丁函数的 entryPc 落在补丁区间；`callArgs` 键 < 总指令数。分类面与既有
 *    装载校验（`loader.ErbcStream.validate` / `ErbcReader.Decoder.validate`）共用同一张
 *    机器表 [OpcodeRelocation]——不另写第二份「哪个槽是哪个池」，免得「装载路放行、
 *    拼接路拒绝」的判定漂移（4.981 的 `_RETF` 就是四份手写 when 一起漏判的）。
 *
 * ## base 读数零漂移（I4）
 *
 * 拼接只**新增**：base 的池、函数表、行号表、指令流一个字节都不写（组合视图只持引用），
 * base 的四个计数在 overlay 前后逐项不变——`ProgramOverlayTest` 的 I4 用例断言这一条。
 * 语句池尤其要盯：波 A 钉子 A1 证过「语句池按引用共享，续编会反向污染已建 Program」，
 * 本类对语句的追加发生在**新建的组合池**上，与 base 池无关。
 */
object ProgramOverlay {

    /**
     * 把 [patch] 拼到 [base] 之后。
     *
     * @throws IllegalArgumentException 窗口对齐失败（补丁基线与 base 计数不符）、
     *   补丁引用超槽的全局槽、调用点 pc 错位，或 I2 自检命中任一条（含操作码越界：
     *   [OpcodeRelocation.of] 的范围咽喉）。本路的全部拒绝都是这一个异常类型。
     */
    fun overlay(base: Program, patch: Program): Program {
        validateBaselineAlignment(base, patch)

        // 指令流：base 整页直通 + 补丁段物化字。补丁只有几十 KB，物化无虞；
        // 取字走补丁自己的 codeSource（不经 CodeSource.of——那会按补丁长度退化页宽，
        // 波 A 钉子 A3 的坑；CompositeCodeSource 的 tailWords 是裸字数组，无页宽问题）。
        val codeSource = CompositeCodeSource(base.codeSource, patch.codeSource.toIntArray())

        // 行号表：纯分派视图，base 段零物化。
        val lineTable = LineTable.composite(base.overlayLineTableRef, patch.overlayLineTableRef)

        // 四池：组合池 + 续段逐位追加。追加必须发生在 Program 构造之前——
        // 构造期 seal() 会封死追加入口（语句池除外，但顺序统一无例外）。
        val pool = compositePool(base, patch)

        // 函数表：base 段直通 + 补丁尾段（base 是映射档惰性表时不物化任何条目）。
        val functions: List<FunctionInfo> = CompositeFunctionTable(base.functions, patch.functions)

        // 调用点表：按 pc 升序合并，补丁键必须落在补丁区间。
        val callArgs = mergeCallArgs(base, patch)

        val overlay = Program(
            codeSource = codeSource,
            lineTable = lineTable,
            pool = pool,
            functions = functions,
            entryFunction = base.entryFunction,
            globalSlots = base.globalSlots,
            callArgs = callArgs,
            // 诊断口径：base 是运行期既有主体，callArgSource 沿用它的（拼接段各自的
            // 来源已在各自的装载/编译期登记过；该字段只服务诊断，不参与绑定判据）。
            callArgSource = base.callArgSource,
        )

        // I2 线性自检：一次性扫拼接结果，任一判据命中即拒绝（不返回半装载的 overlay，
        // 铁律 I5「禁止半装载」在本类的体现就是：要么返回完整可执行的 Program，要么抛）。
        linearSelfCheck(overlay, base.instructionCount, patch.functions)
        return overlay
    }

    // ------------------------------------------------------------------
    // 闸 1：窗口对齐（六个基线 + 全局槽）
    // ------------------------------------------------------------------

    /**
     * 补丁的六个基线必须**正好**接在 base 的对应计数之后。
     *
     * 这些基线是补丁编译期按「目标 base 的计数」算出的绝对下标起点；拼装时若与实际
     * base 的计数对不上，说明补丁是按另一份 base 编的（或产物被换过）——拼出来的绝对
     * 下标空间有空洞或重叠，取用会静默落到错的段。这与波 C4 的 I3 同源，是它在本层
     * （只有两个 Program、没有补丁头文件）的等价判据。
     */
    private fun validateBaselineAlignment(base: Program, patch: Program) {
        val codeWords = base.codeSource.wordCount
        require(patch.codeBase == codeWords) {
            "补丁 codeBase 必须等于 base 指令流字数：期望 $codeWords，实际 ${patch.codeBase}" +
                "（补丁窗口必须正好接在 base 之后，差一点就是绝对 pc 空间的空洞或重叠）"
        }
        val stmts = base.statementCount
        require(patch.statementBase == stmts) {
            "补丁 statementBase 必须等于 base 语句池计数：期望 $stmts，实际 ${patch.statementBase}"
        }
        val strings = base.stringCount
        require(patch.stringBase == strings) {
            "补丁 stringBase 必须等于 base 字符串池计数：期望 $strings，实际 ${patch.stringBase}"
        }
        val longs = base.constantCount
        require(patch.longBase == longs) {
            "补丁 longBase 必须等于 base 常量池计数：期望 $longs，实际 ${patch.longBase}"
        }
        val exprs = base.exprCount
        require(patch.exprBase == exprs) {
            "补丁 exprBase 必须等于 base 表达式池计数：期望 $exprs，实际 ${patch.exprBase}"
        }
        val fns = base.functions.size
        require(patch.functionBase == fns) {
            "补丁 functionBase 必须等于 base 函数表条数：期望 $fns，实际 ${patch.functionBase}"
        }
        // 铁律 3（R7 §0.1）：补丁不许扩全局槽。补丁引用的全局槽若超出 base 的槽空间，
        // 编译期记出的 globalSlots 会大于 base 的——运行期按 base 的槽数分配，越界读只会
        // 报一句无关的下标错误，所以在这里拦（补丁引用 base 已有的槽不受影响）。
        require(patch.globalSlots <= base.globalSlots) {
            "补丁引用了超出 base 槽空间的全局槽：base ${base.globalSlots} 槽，补丁需要 ${patch.globalSlots}" +
                "（v1 禁止补丁新增全局槽——存档列不许变）"
        }
    }

    // ------------------------------------------------------------------
    // 四池：组合池 + 续段逐位追加
    // ------------------------------------------------------------------

    /**
     * 造组合池并把补丁段的四池条目逐位追加进去。
     *
     * 追加必须**逐位**（`append*Direct` / [ConstantPool.addStatement]），不能走
     * `addLong`/`addString`/`addExpr`：那三个带去重，「与 base 同值」的条目会折回 base
     * 的既有下标、不落新槽——补丁下标处变成空洞或错值（理由详述在各 append 方法的 KDoc）。
     * 语句池本就按身份追加、严禁去重（`addStatement` 的契约），直接复用。
     */
    private fun compositePool(base: Program, patch: Program): ConstantPool {
        val pool = ConstantPool(
            longBase = base.constantCount,
            stringBase = base.stringCount,
            exprBase = base.exprCount,
            statementBase = base.statementCount,
            basePool = base.overlayPoolRef,
        )
        val patchPool = patch.overlayPoolRef
        // 追加序 = 补丁池内序：组合池的续段下标 i 对应补丁池第 i 条，与补丁编译期
        // 「基线 + 段内序」的绝对下标逐条对齐。快照访问器（longArray 等）恒只含本池条目。
        for (v in patchPool.longArray) pool.appendLongDirect(v)
        for (s in patchPool.stringArray) pool.appendStringDirect(s)
        for (e in patchPool.exprList) pool.appendExprDirect(e)
        for (st in patchPool.statementList) pool.addStatement(st)
        return pool
    }

    // ------------------------------------------------------------------
    // 调用点表：按 pc 升序合并
    // ------------------------------------------------------------------

    /**
     * 把 base 与补丁的调用点表按 pc 升序合并成一张 [CallArgIndex]。
     *
     * 键是**绝对** pc：base 的键全在 base 区间（它自己装载/编译时的口径），补丁的键
     * 必须全落在补丁区间（`pc >= base.instructionCount`）——补丁键若落进 base 区间，
     * 说明补丁基线错位，拼出来的表会把补丁的实参元数据挂到 base 的指令上（静默绑错），
     * 在这里拒绝。键冲突（同一 pc 两张表）在升序校验里一并拦掉。
     *
     * 合并走 [CallArgIndex.Builder]：base 的条目已在装载期归一过一次，这里重走一遍
     * Builder 的结构归一是装载期一次性成本（151k 站点量级 ≈ 几十 ms，导入期可接受），
     * 换来的是不给 `CallArgIndex` 加第二个构造入口。
     */
    private fun mergeCallArgs(base: Program, patch: Program): Map<Int, List<CallArg>> {
        val baseIndex = base.callArgs as? CallArgIndex ?: CallArgIndex.of(base.callArgs)
        val patchIndex = patch.callArgs as? CallArgIndex ?: CallArgIndex.of(patch.callArgs)
        if (patchIndex.isEmpty()) return baseIndex

        val builder = CallArgIndex.Builder()
        var last = Int.MIN_VALUE
        for (i in 0 until baseIndex.size) {
            val pc = baseIndex.pcs[i]
            builder.add(pc, requireNotNull(baseIndex.vals[i]) { "调用点 pc=$pc 的实参表为空" })
            last = pc
        }
        val baseEnd = base.instructionCount
        for (i in 0 until patchIndex.size) {
            val pc = patchIndex.pcs[i]
            if (pc < baseEnd) {
                throw IllegalArgumentException(
                    "补丁调用点 pc=$pc 落在 base 区间 [0, $baseEnd)——补丁基线错位" +
                        "（调用点 pc 是绝对口径，必须全部落在补丁区间）",
                )
            }
            if (pc <= last) {
                throw IllegalArgumentException(
                    "调用点 pc=$pc 与既有键 $last 冲突或非升序——同一 pc 不可能有两个调用点表",
                )
            }
            builder.add(pc, requireNotNull(patchIndex.vals[i]) { "调用点 pc=$pc 的实参表为空" })
            last = pc
        }
        return builder.build()
    }

    // ------------------------------------------------------------------
    // 闸 2：I2 线性自检（装配后一次性扫拼接结果）
    // ------------------------------------------------------------------

    /**
     * 扫一遍拼接结果的下标引用面（CONTRACT-R7.md §1.6 I2 的判据列表）。
     *
     * 判据与既有装载校验同口径（`loader.ErbcStream.validate` 的 when 逐条对应）：
     * base 段的指令在它自己的装载期已被同口径校验过，拼上补丁后各池计数只增不减，
     * base 段必然仍通过；把整段一起扫是为「补丁编译期基线算错」与「手工构造的病态
     * base」这两类装载校验覆盖不到的错位兜底——I2 是拼接结果的**最终**自检。
     */
    private fun linearSelfCheck(
        overlay: Program,
        baseInstructionCount: Int,
        patchFunctions: List<FunctionInfo>,
    ) {
        val total = overlay.instructionCount
        // 池引用判界扫描（共享单一真源，[checkPoolIndexBounds]）：overlay 与链接器
        // 两条拼装路对同一张判据表负责，一处改、两处同步。
        checkPoolIndexBounds(overlay)
        // 补丁函数的 entryPc 必须落在补丁区间 [baseInstructionCount, total)：
        // 补丁函数体全在补丁段，入口指向 base 段或指令流之外都是函数表串段。
        for (f in patchFunctions) {
            if (f.entryPc !in baseInstructionCount until total) {
                throw IllegalArgumentException(
                    "补丁函数 ${f.name} 的 entryPc=${f.entryPc} 未落在补丁区间 " +
                        "[$baseInstructionCount, $total)——补丁基线错位或函数表串段",
                )
            }
        }
        // callArgs 键 < 总指令数（键恒为绝对 pc）。
        val mergedIndex = overlay.callArgs as? CallArgIndex
        if (mergedIndex != null) {
            for (pc in mergedIndex.pcs) {
                if (pc < 0 || pc >= total) {
                    throw IllegalArgumentException(
                        "调用点 pc=$pc 越出指令区间 [0, $total)——callArgs 键必须是绝对 pc",
                    )
                }
            }
        }
        // 补丁段函数记录的判界（entryPc / arity / slotCount / 标签 pc；base 段不重验，
        // 理由见 CompositeFunctionTable.validateRecords 的 KDoc）。
        (overlay.functions as? LazyFunctionTable)?.validateRecords(total)
    }
}

/**
 * 全指令流的池引用/函数表判界扫描（I2 的判据表主体；[ProgramOverlay.linearSelfCheck]
 * 与 M2-1 [com.eratw.port.core.patch.ModuleLinker] 的出口自检共用 —— 两条拼装路
 * 一张判据表，判定与文案同源，杜绝「装载路放行、拼接路拒绝」的漂移）。
 *
 * 判据的**分类面**只有一份：[OpcodeRelocation]（specs/erbc-format.md §5.6b 的机器表）。
 * 哪个槽是哪个池由表回答，本函数只负责拼接路的**处置策略**（判界 + 本文案的异常）。
 * [OpcodeRelocation.of] 同时是操作码范围的咽喉：越出 `0..Opcode.MAX_OPCODE` 的脏字节
 * 在这里与装载路同样当场拒。
 *
 * @param allowCallSentinels true = `_CALL.a` 的负哨兵（super / 外部导入，链接期
 *   才回填）放行不判 —— **零基线模块产物**的合法形态；false（默认）= 任何负
 *   `_CALL.a` 都是残留哨兵，当场拒。拼接完成的产物（overlay / Linked）恒走默认。
 */
internal fun checkPoolIndexBounds(program: Program, allowCallSentinels: Boolean = false) {
    val total = program.instructionCount
    val functionCount = program.functions.size
    val constantCount = program.constantCount
    val stringCount = program.stringCount
    val exprCount = program.exprCount
    val statementCount = program.statementCount
    for (pc in 0 until total) {
        val op = program.op(pc)
        val rel = OpcodeRelocation.of(op)
        // 三槽全非池引用的操作码（算术/栈操作/跳转/名字槽）占绝大多数，整条跳过。
        if (rel === OperandRelocation.EMPTY) continue
        for (slot in 0..2) {
            val ref = rel.at(slot)
            if (ref == PoolRef.NONE) continue
            val index = when (slot) {
                0 -> program.a(pc)
                1 -> program.b(pc)
                else -> program.c(pc)
            }
            // 可空池引用的哨兵 = 「没有这一项」，不判界（_RETF 无返回值）。
            if (ref.nullable && index == ErbcFormat.NONE_REF) continue
            if (ref == PoolRef.FUNCTION) {
                if (!(allowCallSentinels && index < 0)) {
                    requirePoolIndex(op, index, functionCount, pc, ref.poolName)
                }
                continue
            }
            val count = when (ref) {
                PoolRef.CONSTANT -> constantCount
                PoolRef.STRING -> stringCount
                PoolRef.EXPR, PoolRef.EXPR_NULLABLE -> exprCount
                PoolRef.STATEMENT -> statementCount
                PoolRef.NONE, PoolRef.FUNCTION -> error("非池计数面: $ref")
            }
            requirePoolIndex(op, index, count, pc, ref.poolName)
        }
    }
}

/** 单条下标引用的判界（与装载路的 `requirePool` 同款文案结构：pc、指令名、下标、总数）。 */
private fun requirePoolIndex(op: Int, index: Int, count: Int, pc: Int, what: String) {
    if (index < 0 || index >= count) {
        throw IllegalArgumentException(
            "overlay 自检失败：pc=$pc 的 ${Opcode.nameOf(op)} 引用${what}下标 $index，" +
                "越界（共 $count 条）——拒绝装载",
        )
    }
}

/**
 * base 段按下标直通 + 补丁段堆内的组合函数表（R7-C2）。
 *
 * 为什么不是 `base.functions + patch.functions`：base 的函数表在映射装载档是
 * [LazyFunctionTable]（M11-MEM，110,072 条按需解码），列表相加会把整表物化成堆内
 * [FunctionInfo]——正好把惰性装载省下的常驻吃回去，撞 R7 内存红线（CONTRACT-R7-C
 * §2/C3 同一条红线）。本视图只拼「下标空间」：`index < base.size` 转给 base（惰性
 * 条目照常按需解码），其余折回补丁段。
 *
 * [materializedCount] 只报续段条数：口径对齐组合池的 `materializedStatementCount`
 * （「base 的物化量归 base 自己，诊断口径不叠加，叠加会诱导整表已物化的误判」）。
 *
 * [validateRecords] 只验补丁段：base 的记录在它自己的装载期已逐条判界过（映射路的
 * `validateRecords` 与字节路的 `validate` 都验），重验等于把 110k 条记录再解码一遍；
 * 补丁段是本次新增的，必须验。判界条目与 `loader.ErbcStream.validateFunctionRecord`
 * 同口径（那份是 loader 私有，这里按同一条判据复写，不引 loader 依赖）。
 */
internal class CompositeFunctionTable(
    private val base: List<FunctionInfo>,
    private val tail: List<FunctionInfo>,
) : AbstractList<FunctionInfo>(), LazyFunctionTable {

    override val size: Int get() = base.size + tail.size

    override fun get(index: Int): FunctionInfo {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("函数下标越界（$index），共 $size 条")
        }
        return if (index < base.size) base[index] else tail[index - base.size]
    }

    override val materializedCount: Int get() = tail.size

    override fun validateRecords(instructionCount: Int) {
        for (f in tail) validateRecord(f, instructionCount)
    }

    override fun hasAnyParamNames(): Boolean =
        if (base is LazyFunctionTable) {
            base.hasAnyParamNames() || tailHasParamNames()
        } else {
            base.any { it.paramNames.isNotEmpty() } || tailHasParamNames()
        }

    // 与 hasAnyParamNames 同一口径：base 是惰性表时绝不为了取形参名去 `get`（那会把整张
    // base 表物化并永久驻留，M11-MEM 的收益由这一条接缝守住）。
    override fun paramNamesAt(index: Int): List<String> = when {
        index < 0 || index >= size -> emptyList()
        index < base.size -> if (base is LazyFunctionTable) {
            base.paramNamesAt(index)
        } else {
            base[index].paramNames
        }
        else -> tail[index - base.size].paramNames
    }

    private fun tailHasParamNames(): Boolean = tail.any { it.paramNames.isNotEmpty() }
}

/**
 * 单条函数记录的判界（与 `loader.ErbcStream.validateFunctionRecord` 同口径：
 * entryPc / arity / slotCount / 标签 pc 四项，文案同构）。文件私有：只服务
 * [CompositeFunctionTable.validateRecords]。
 */
private fun validateRecord(f: FunctionInfo, instructionCount: Int) {
    if (f.entryPc !in 0 until instructionCount) {
        throw IllegalArgumentException(
            "补丁函数 ${f.name} 的 entryPc=${f.entryPc} 越界（指令数 $instructionCount）",
        )
    }
    if (f.arity < 0 || f.slotCount < 0) {
        throw IllegalArgumentException(
            "补丁函数 ${f.name} 的 arity/slotCount 为负: ${f.arity}/${f.slotCount}",
        )
    }
    if (f.arity > f.slotCount) {
        throw IllegalArgumentException(
            "补丁函数 ${f.name} 的 arity(${f.arity}) 大于 slotCount(${f.slotCount})：" +
                "实参复制会越出被调帧槽区间（畸形产物）",
        )
    }
    for (i in 0 until f.labelCount) {
        val pc = f.labelPcs[i]
        if (pc !in 0 until instructionCount) {
            throw IllegalArgumentException(
                "补丁函数 ${f.name} 的标签 \$${f.labelNames[i]} 的 pc=$pc 越界（指令数 $instructionCount）",
            )
        }
    }
}