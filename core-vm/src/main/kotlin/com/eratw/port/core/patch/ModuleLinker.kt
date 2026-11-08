package com.eratw.port.core.patch

import com.eratw.port.core.code.CallArgIndex
import com.eratw.port.core.code.CompositeCodeSource
import com.eratw.port.core.code.CompositeFunctionTable
import com.eratw.port.core.code.ConstantPool
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.FunctionInfo
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.LazyFunctionTable
import com.eratw.port.core.code.LineTable
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.OpcodeRelocation
import com.eratw.port.core.code.OperandRelocation
import com.eratw.port.core.code.PoolRef
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.code.checkPoolIndexBounds
import com.eratw.port.core.proc.FuncMeta

/**
 * 链接产物（M2-1 单位 C 的出口形状，era-arch-v61u.md §1）。
 *
 * - [program]：拼接结果，全量绝对口径（六基线 = 0），可交给 VM 直接执行；
 *   入口恒为 base 的入口（铁律：模块只追加函数，不改入口）；
 * - [catalog]：全部函数（base + 各模块）的 LSC（ABI-7）——名字通道与 dispatch/
 *   superChain 的唯一真源；运行期 FunctionTable 从 [catalog] 的候选元数据装配；
 * - [imports]：外部导入的回填记录（诊断与 ABI-8 指纹用；运行期不可见）；
 * - [moduleOrder]：链接序 packageId（base 不在其中）；
 * - [hasOverrides]：`HAS_OVERRIDES` 旗标（catalog 判据透传，P1-2 的最终 dispatch
 *   非恒等判据）——false 时 VM 走物理下标直调，base-only golden 零变化的前提。
 */
class LinkedProgram(
    val program: Program,
    val catalog: SymbolCatalog,
    val imports: ExternalImportTable,
    val moduleOrder: List<String>,
) {
    /** 最终 dispatch 存在非恒等项（不是「装了 Mod」，base 内部重名也算）。 */
    val hasOverrides: Boolean get() = catalog.hasOverrides
}

/**
 * 装载期链接器（M2-1 单位 C；era-arch-v61u.md §4）。
 *
 * 把零基线 Module（[LinkableModule]，已按 [ModuleLinkOrder.plan] 可定序的任意集合
 * 输入，序由本类内部裁决）拼到 base 之后，产出 [LinkedProgram]。这是**链接器的并行
 * 拼装路**，不是 [com.eratw.port.core.code.ProgramOverlay] 的直拼路：overlay 要求
 * 带基线补丁的六个基线正好接在 base 计数之后，而零基线 Module 对任何 base 都对不上
 * 那道窗口闸 —— 模块按 base=0 编译，绝对下标空间由本类分配：
 *
 * - **函数窗口**：[LinkCandidateAssembler.functionStarts]（base 恒第 0，窗口稠密）；
 * - **池/指令窗口**：按链接序累加 base 与前面模块的计数（同稠密、无空洞）；
 * - **指令改写**只碰两类操作数：`_CALL.a`（[CallRelocation.resolve] 四支决议，
 *   super 命中置 [CallSentinels.DIRECT_CALL_FLAG]）与池引用的**机械平移**（加各池
 *   基线，分类面 = [OpcodeRelocation] 机器表，与 [checkPoolIndexBounds] 同源）。
 *   跳转是函数内相对偏移
 *   （Opcode.kt `_JUMP` 注释），指令流拼接不改变函数内相对距离，**不需要 rebase**；
 * - **元数据 rebase**：[FunctionInfo.entryPc]/labelPcs/callArgs 键加指令偏移；
 *   [FuncMeta.index]/defIndex 由 [LinkCandidateAssembler] 改写（defIndex 随绝对
 *   下标走 = 跨模块事件桶序按链接序落定）。
 *
 * ## fail-closed 自检（三层，任一命中 = 整组拒绝，不返回半装载产物）
 *
 * 1. **模块级**：[LinkableModule.validate] 形状 + [checkPoolIndexBounds]（按模块
 *    自身计数）+ [ErbcReader.requireJumpTargets]（跳转归属）—— 改写前把模块产物
 *    自身的病态拦在源头，避免「负池引用平移后落进 base 段」这类静默错值；
 * 2. **出口级**：拼接产物全指令流重跑 [checkPoolIndexBounds]（I2 同款，含
 *    `_CALL.a` 判界 —— 残留哨兵必为负下标，同点拦截）+
 *    [ErbcReader.requireJumpTargets]（base 与全部模块拼后仍逐函数合法）+
 *    [LazyFunctionTable.validateRecords]（tail 段 entryPc/arity/labels 判界）；
 * 3. **catalog 级**：[SymbolCatalog.build] 内建的分派幂等自检。
 *
 * ## 不在本类的事
 *
 * 容器字节读写（M2-2）、链接产物缓存指纹（ABI-8 的十因子，M2 后段）、globalSlots
 * 扩容的存档列兼容（模块引用超 base 槽空间时本类只放宽计数上界；扩槽的持久化
 * 语义归声明事务与存档域 —— global-dims 载体，M1-3/M4）。legacy profile 的
 * base-only golden 不经过本类：无模块 = 用 base 原产物，入口直接拒绝空模块集。
 */
object ModuleLinker {

    /** base 在 LSC 候选归属里的名字（合成标记风格，与包名空间不交集的约定）。 */
    const val BASE_OWNER_ID: String = "·base·"

    /**
     * 链接 [modules]（任意输入序，内部定序）到 [base] 之后。
     *
     * @param base 已装载的 base 产物（全量口径；入口函数即链接产物入口）。
     * @param baseMetas base 的运行期元数据（装载序；[FuncMeta.index] = 物理下标，
     *   必须与 [base] 函数表同序同长、稠密 0 基 —— 装载侧 [com.eratw.port.core.proc.FunctionTable]
     *   的 `all()` 满足）。base 候选签名恒空（完整签名导出落地前，MODERN 下覆盖
     *   base 函数被签名闸拒绝 —— fail-closed，见 [LinkCandidateAssembler.baseCandidates]）。
     * @param baseSignatureOf base 侧完整签名指纹查询（M2-5 erbcc 导出接缝）：MODERN
     *   显式覆盖 base 函数时要求两侧指纹非空且相等。缺省恒空串 = 接缝落地前的
     *   fail-closed 行为（覆盖 base 一律被签名闸拒），与接缝引入前逐位一致。
     * @param modules 模块集（[LinkableModule]；链接序由内部 [ModuleLinkOrder.plan]
     *   裁决，输入顺序不影响产物）。
     * @param policy LSC 解析政策（带模块组合的默认口径是 MODERN；LEGACY 用于
     *   M2-3 组合矩阵对拍）。
     * @throws IllegalArgumentException 模块集为空 / base 元数据不对齐 / 模块形状或
     *   指令流病态（三层自检任一命中）。
     * @throws LinkOrderException 依赖缺失 / 包重名 / 依赖环。
     * @throws com.eratw.port.core.code.SymbolConflictException MODERN 下未声明重名。
     * @throws com.eratw.port.core.code.OverrideSignatureException 覆盖签名不可验证。
     * @throws ImportResolutionException 导入无法解析（缺提供者 / 重复 / 事件名）。
     * @throws RelocationException `_CALL.a` 无法决议（super 缺失 / 导入越域）。
     */
    fun link(
        base: Program,
        baseMetas: List<FuncMeta>,
        modules: List<LinkableModule>,
        policy: ResolverPolicy,
        baseSignatureOf: (String) -> String = { "" },
    ): LinkedProgram {
        require(modules.isNotEmpty()) {
            "模块集为空——base-only 组合不走链接器（直接用 base 原产物，golden 恒等）"
        }
        require(baseMetas.size == base.functions.size) {
            "base 元数据（${baseMetas.size} 条）与 base 函数表（${base.functions.size} 条）不同长"
        }
        for ((i, m) in baseMetas.withIndex()) {
            require(m.index == i) {
                "base 元数据[$i].index=${m.index} 不是物理下标（期望 $i；装载序序列的 index 必须稠密 0 基）"
            }
        }
        for (m in modules) LinkableModule.validate(m)

        // 定序：plan 只收描述符，按 packageId 映射回模块（稳定拓扑保输入序，输入已序
        // 时 plan 结果恒等 —— ABI-8 缓存指纹的确定性前提）。
        val ordered = ModuleLinkOrder.plan(modules.map { it.descriptor })
        val byPkg = modules.associateBy { it.descriptor.packageId }
        val sorted = ordered.map { byPkg.getValue(it.packageId) }

        // 候选与 LSC（base 候选在前 = 候选列表序即链接序）。
        val starts = LinkCandidateAssembler.functionStarts(base.functions.size, sorted)
        val candidates = LinkCandidateAssembler.baseCandidates(baseMetas, BASE_OWNER_ID, baseSignatureOf) +
            LinkCandidateAssembler.assemble(sorted, starts)
        val catalog = SymbolCatalog.build(candidates, policy)
        val imports = ExternalImportTable.resolve(sorted.map { it.descriptor }, catalog)

        // 窗口分配：指令 pc/字与四池基线按链接序累加（稠密无空洞；Long 判溢出）。
        val count = sorted.size
        val instrOffsets = IntArray(count)
        val longBases = IntArray(count)
        val stringBases = IntArray(count)
        val exprBases = IntArray(count)
        val stmtBases = IntArray(count)
        var wordOff = base.codeSource.wordCount.toLong()
        var instrOff = base.instructionCount.toLong()
        var longBase = base.constantCount.toLong()
        var stringBase = base.stringCount.toLong()
        var exprBase = base.exprCount.toLong()
        var stmtBase = base.statementCount.toLong()
        var globalSlots = base.globalSlots
        for ((mi, m) in sorted.withIndex()) {
            val p = m.program
            instrOffsets[mi] = instrOff.toInt()
            longBases[mi] = longBase.toInt()
            stringBases[mi] = stringBase.toInt()
            exprBases[mi] = exprBase.toInt()
            stmtBases[mi] = stmtBase.toInt()
            wordOff += p.codeSource.wordCount
            instrOff += p.instructionCount
            longBase += p.constantCount
            stringBase += p.stringCount
            exprBase += p.exprCount
            stmtBase += p.statementCount
            if (p.globalSlots > globalSlots) globalSlots = p.globalSlots
        }
        require(wordOff <= Int.MAX_VALUE && instrOff <= Int.MAX_VALUE.toLong()) {
            "链接产物指令字数溢出 Int：$wordOff 字 / $instrOff 条"
        }
        require(longBase <= Int.MAX_VALUE && stringBase <= Int.MAX_VALUE &&
            exprBase <= Int.MAX_VALUE && stmtBase <= Int.MAX_VALUE) {
            "链接产物池计数溢出 Int：long=$longBase string=$stringBase expr=$exprBase stmt=$stmtBase"
        }

        // 模块级自检 + 指令改写 + tail 拼接。
        val tailWords = IntArray(wordOff.toInt() - base.codeSource.wordCount)
        var tailPos = 0
        for ((mi, m) in sorted.withIndex()) {
            val p = m.program
            // 模块级自检：_CALL 负哨兵是未链接形态的合法状态（allowCallSentinels），
            // 池面仍按模块自身计数判——负池引用在这里拦，不留到平移后落 base 段。
            checkPoolIndexBounds(p, allowCallSentinels = true)
            ErbcReader.requireJumpTargets(p)
            val words = p.codeSource.toIntArray()
            rewriteModuleInstructions(
                words,
                m,
                funcStart = starts[mi],
                longBase = longBases[mi],
                stringBase = stringBases[mi],
                exprBase = exprBases[mi],
                stmtBase = stmtBases[mi],
                imports = imports.importsOf(m.descriptor.packageId),
                catalog = catalog,
            )
            words.copyInto(tailWords, tailPos)
            tailPos += words.size
        }

        // 四池：组合池 + 各模块条目逐位追加（append*Direct 不去重，与补丁续段同口径）。
        val pool = ConstantPool(
            longBase = base.constantCount,
            stringBase = base.stringCount,
            exprBase = base.exprCount,
            statementBase = base.statementCount,
            basePool = base.overlayPoolRef,
        )
        for (m in sorted) {
            val mp = m.program.overlayPoolRef
            for (v in mp.longArray) pool.appendLongDirect(v)
            for (s in mp.stringArray) pool.appendStringDirect(s)
            for (e in mp.exprList) pool.appendExprDirect(e)
            for (st in mp.statementList) pool.addStatement(st)
        }

        // 函数表 rebase（tail 段新建实例；base 段零物化走惰性组合表）。
        val tailFns = ArrayList<FunctionInfo>(sorted.sumOf { it.program.functions.size })
        for ((mi, m) in sorted.withIndex()) {
            val off = instrOffsets[mi]
            for (f in m.program.functions) {
                val nf = FunctionInfo(f.name, f.entryPc + off, f.arity, f.slotCount, f.paramNames)
                for (li in 0 until f.labelCount) {
                    nf.addLabel(f.labelNames[li], f.labelPcs[li] + off)
                }
                tailFns += nf
            }
        }
        val functions: List<FunctionInfo> = CompositeFunctionTable(base.functions, tailFns)

        // 行号表：嵌套组合（组合层做 tail 段下标折回，模块行号表保持 0 基原样）。
        var lineTable = base.overlayLineTableRef
        for (m in sorted) {
            lineTable = LineTable.composite(lineTable, m.program.overlayLineTableRef)
        }

        // 调用点表：base 原样 + 各模块键加指令偏移（升序无冲突，判据同 overlay 合并）。
        val callArgs = mergeCallArgs(base, sorted, instrOffsets)

        val linked = Program(
            codeSource = CompositeCodeSource(base.codeSource, tailWords),
            lineTable = lineTable,
            pool = pool,
            functions = functions,
            entryFunction = base.entryFunction,
            globalSlots = globalSlots,
            callArgs = callArgs,
            callArgSource = base.callArgSource,
        )

        // 出口级自检：全指令流判界（_CALL.a 的残留哨兵 = 负下标，同点拦截）+
        // 跳转归属（拼后仍逐函数合法）+ tail 段函数记录判界 + callArgs 键界。
        checkPoolIndexBounds(linked)
        ErbcReader.requireJumpTargets(linked)
        (linked.functions as? LazyFunctionTable)?.validateRecords(linked.instructionCount)
        val linkedIndex = linked.callArgs as? CallArgIndex
        if (linkedIndex != null) {
            for (pc in linkedIndex.pcs) {
                if (pc < 0 || pc >= linked.instructionCount) {
                    throw IllegalArgumentException(
                        "调用点 pc=$pc 越出指令区间 [0, ${linked.instructionCount})" +
                            "——callArgs 键必须是绝对 pc",
                    )
                }
            }
        }

        return LinkedProgram(
            linked,
            catalog,
            imports,
            sorted.map { it.descriptor.packageId },
        )
    }

    // ------------------------------------------------------------------
    // 指令改写：_CALL 决议 + 池引用机械平移
    // ------------------------------------------------------------------

    /**
     * 就地改写一个模块的指令字（[words] 是模块产物的物化拷贝）。
     *
     * 平移面 = 判据面，一条不多一条不少：两边都只问 [OpcodeRelocation]
     * （specs/erbc-format.md §5.6b 的机器表，[checkPoolIndexBounds] 是它的判界侧消费方）。
     * 表认为是池引用的槽就平移，其余操作数（槽号/函数内相对跳转/标签下标/名字槽/标志位/
     * 维数/增量）拼接后语义不变，一律不动。`_CALL.a` 是唯一例外：表里登记为 FUNCTION，
     * 但函数下标要先经 CallRelocation 语义决议，不走机械平移。
     *
     * 函数区间判据：模块产物函数表按 entryPc **严格递增**且首函数从 pc 0 起 ——
     * 零基线模块的函数体必须连续覆盖整条指令流，否则有指令不属于任何函数
     * （`_CALL` 决议的 containingFunction 无从谈起），拒绝。
     *
     * NoSuperNop（TRYCALL_SUPER 无前实现）：整条折 [Opcode._NOP]（a/b/c 全 0，
     * 不写任何返回槽，同官方 TRYCALL 缺目标语义）。
     *
     * @throws RelocationException `_CALL.a` 无法决议。
     * @throws IllegalArgumentException 函数区间断裂 / 平移操作数为负（病态产物）。
     */
    private fun rewriteModuleInstructions(
        words: IntArray,
        module: LinkableModule,
        funcStart: Int,
        longBase: Int,
        stringBase: Int,
        exprBase: Int,
        stmtBase: Int,
        imports: ExternalImportTable.PackageImports?,
        catalog: SymbolCatalog,
    ) {
        val pkg = module.descriptor.packageId
        val fns = module.program.functions
        val instrCount = module.program.instructionCount
        if (fns.isEmpty()) {
            require(instrCount == 0) {
                "模块 [$pkg] 没有函数却有 $instrCount 条指令——函数体必须连续覆盖指令流"
            }
            return
        }
        require(fns[0].entryPc == 0) {
            "模块 [$pkg] 首函数 entryPc=${fns[0].entryPc} 不为 0——零基线模块的函数体必须从 pc 0 连续覆盖"
        }
        for (i in 1 until fns.size) {
            require(fns[i].entryPc > fns[i - 1].entryPc) {
                "模块 [$pkg] 函数表 entryPc 非严格递增（#${i - 1}=${fns[i - 1].entryPc}" +
                    " #${i}=${fns[i].entryPc}）——函数体必须连续覆盖指令流"
            }
        }
        for (fi in fns.indices) {
            val entry = fns[fi].entryPc
            val until = if (fi + 1 < fns.size) fns[fi + 1].entryPc else instrCount
            // super 决议的对象 = 本函数的绝对物理下标（catalog 的 superChain 坐标域）。
            val containing = funcStart + fi
            val whereFn = "$pkg/${fns[fi].name}"
            for (pc in entry until until) {
                val w = pc * Instruction.WORDS
                val op = words[w]
                val a = words[w + 1]
                val b = words[w + 2]
                val c = words[w + 3]
                var na = a
                var nb = b
                var nc = c
                if (op == Opcode._CALL) {
                    // 语义决议：四支判序在 CallRelocation（super → 折 NOP → 导入 → 净偏移）。
                    // `_CALL.a` 在表里是 FUNCTION，但函数下标**不是机械平移面**（负哨兵要
                    // 先决议成绝对下标），故单独走这一支，不进下面的三槽循环。
                    val where = "$whereFn:pc$pc"
                    when (val outcome = CallRelocation.resolve(a, containing, funcStart, imports, catalog, where)) {
                        is CallRelocation.Outcome.Internal -> na = outcome.absoluteIndex
                        is CallRelocation.Outcome.External -> na = outcome.absoluteIndex
                        is CallRelocation.Outcome.SuperCall -> {
                            na = outcome.absoluteIndex
                            // DIRECT 位：直调物理下标绕过 dispatch，防 super 链二次跳转成环。
                            nb = b or CallSentinels.DIRECT_CALL_FLAG
                        }
                        // 整条折 NOP：不写任何返回槽（RESULT/RESULTS/RESULTF 保持调用前值）。
                        CallRelocation.Outcome.NoSuperNop -> {
                            words[w] = Opcode._NOP
                            words[w + 1] = 0
                            words[w + 2] = 0
                            words[w + 3] = 0
                            continue
                        }
                    }
                } else {
                    // 机械平移面：分类只问 [OpcodeRelocation]（与 checkPoolIndexBounds 同一张表，
                    // 平移面 = 判据面，一条不多一条不少）。三槽一视同仁 —— 0..246 全域的 c 目前
                    // 都是 NONE，将来追加带 c 池引用的操作码不需要改这里。
                    val rel = OpcodeRelocation.of(op)
                    if (rel !== OperandRelocation.EMPTY) {
                        for (slot in 0..2) {
                            val ref = rel.at(slot)
                            if (ref == PoolRef.NONE) continue
                            val raw = when (slot) {
                                0 -> a
                                1 -> b
                                else -> c
                            }
                            val shifted = shiftPoolRef(
                                ref, raw, longBase, stringBase, exprBase, stmtBase,
                                "$pkg $whereFn:pc$pc ${"abc"[slot]}",
                            )
                            when (slot) {
                                0 -> na = shifted
                                1 -> nb = shifted
                                else -> nc = shifted
                            }
                        }
                    }
                }
                words[w + 1] = na
                words[w + 2] = nb
                words[w + 3] = nc
            }
        }
    }

    /**
     * 按表分类做机械平移（[PoolRef.NONE] 原样返回：槽号/函数内偏移/标签下标/标志位/维数
     * 拼接后语义不变，一个字都不许动）。
     *
     * 池引用必须**原始非负**（[shiftIndex]）—— 判平移后的值会被大 base 计数掩盖
     * （-1 + 基线落进 base 段静默错值）。唯一的例外是 [PoolRef.EXPR_NULLABLE] 的
     * [ErbcFormat.NONE_REF]：那是「没有这一项」的哨兵，**不平移**，否则 `_RETF` 无返回值
     * 会变成求值 base 段末条表达式。
     */
    private fun shiftPoolRef(
        ref: PoolRef,
        raw: Int,
        longBase: Int,
        stringBase: Int,
        exprBase: Int,
        stmtBase: Int,
        where: String,
    ): Int = when (ref) {
        PoolRef.NONE -> raw
        PoolRef.CONSTANT -> shiftIndex(raw, longBase, where, ref.poolName)
        PoolRef.STRING -> shiftIndex(raw, stringBase, where, ref.poolName)
        PoolRef.EXPR -> shiftIndex(raw, exprBase, where, ref.poolName)
        PoolRef.EXPR_NULLABLE ->
            if (raw == ErbcFormat.NONE_REF) raw else shiftIndex(raw, exprBase, where, ref.poolName)
        PoolRef.STATEMENT -> shiftIndex(raw, stmtBase, where, ref.poolName)
        PoolRef.FUNCTION -> error("函数下标不是机械平移面：_CALL 走 CallRelocation 语义决议（$where）")
    }

    /**
     * 池引用机械平移：原始操作数必须非负（负哨兵只有两处 —— `_CALL.a` 走语义决议、
     * `_RETF.a` 的 NONE_REF 由 [shiftPoolRef] 原样放行；此外的池引用负值 = 病态产物，
     * 平移后落 base 段的静默错值在这里拦），平移结果越 Int 正域当场拒。
     */
    private fun shiftIndex(raw: Int, base: Int, where: String, what: String): Int {
        require(raw >= 0) { "模块 $where 的 $what 引用下标 $raw 为负——病态产物" }
        val shifted = raw.toLong() + base.toLong()
        require(shifted <= Int.MAX_VALUE.toLong()) {
            "模块 $where 的 $what 平移后越 Int 正域：$raw + $base = $shifted"
        }
        return shifted.toInt()
    }

    // ------------------------------------------------------------------
    // 调用点表合并
    // ------------------------------------------------------------------

    /**
     * base + 各模块的调用点表按绝对 pc 升序合并（判据同 ProgramOverlay 的合并：
     * 模块键必须落在本模块区间，与既有键冲突即拒）。
     */
    private fun mergeCallArgs(
        base: Program,
        sorted: List<LinkableModule>,
        instrOffsets: IntArray,
    ): CallArgIndex {
        val builder = CallArgIndex.Builder()
        var last = Int.MIN_VALUE
        val baseIndex = base.callArgs as? CallArgIndex ?: CallArgIndex.of(base.callArgs)
        for (i in 0 until baseIndex.size) {
            val pc = baseIndex.pcs[i]
            builder.add(pc, requireNotNull(baseIndex.vals[i]) { "调用点 pc=$pc 的实参表为空" })
            last = pc
        }
        for ((mi, m) in sorted.withIndex()) {
            val p = m.program
            val mIndex = p.callArgs as? CallArgIndex ?: CallArgIndex.of(p.callArgs)
            val off = instrOffsets[mi]
            val end = off + p.instructionCount
            for (i in 0 until mIndex.size) {
                val pc = mIndex.pcs[i] + off
                if (pc < off || pc >= end) {
                    throw IllegalArgumentException(
                        "模块 [${m.descriptor.packageId}] 调用点 pc=${mIndex.pcs[i]}" +
                            "（平移后 $pc）落在本模块区间 [$off, $end) 之外" +
                            "——调用点键是模块内口径，平移后必须落回本模块",
                    )
                }
                if (pc <= last) {
                    throw IllegalArgumentException(
                        "调用点 pc=$pc 与既有键 $last 冲突或非升序——窗口分配错位",
                    )
                }
                builder.add(pc, requireNotNull(mIndex.vals[i]) { "调用点 pc=$pc 的实参表为空" })
                last = pc
            }
        }
        return builder.build()
    }
}
