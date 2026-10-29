package com.eratw.port.core.code

import com.eratw.port.core.code.ErbcFormat.ArgTag
import com.eratw.port.core.code.ErbcFormat.ExprTag
import com.eratw.port.core.code.ErbcFormat.NONE_REF
import com.eratw.port.core.code.ErbcFormat.StmtTag
import com.eratw.port.core.code.ErbcFormat.WireEnum
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.NullTerm
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.ArgValueType
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.BitArgument
import com.eratw.port.core.stmt.CaseArgument
import com.eratw.port.core.stmt.CaseElseStmt
import com.eratw.port.core.stmt.CaseExpression
import com.eratw.port.core.stmt.CaseStmt
import com.eratw.port.core.stmt.CatchStmt
import com.eratw.port.core.stmt.CallFStmt
import com.eratw.port.core.stmt.CallSharpStmt
import com.eratw.port.core.stmt.CallStmt
import com.eratw.port.core.stmt.DataListStmt
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.DoStmt
import com.eratw.port.core.stmt.ElseIfStmt
import com.eratw.port.core.stmt.ElseStmt
import com.eratw.port.core.stmt.EndBlockStmt
import com.eratw.port.core.stmt.EndCatchStmt
import com.eratw.port.core.stmt.EndIfStmt
import com.eratw.port.core.stmt.EndSelectStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.ExpressionsArgument
import com.eratw.port.core.stmt.FlowStmt
import com.eratw.port.core.stmt.ForStmt
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.GotoLabelStmt
import com.eratw.port.core.stmt.HtmlPrintArgument
import com.eratw.port.core.stmt.IfStmt
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.IntAsignArgument
import com.eratw.port.core.stmt.JumpStmt
import com.eratw.port.core.stmt.LoopStmt
import com.eratw.port.core.stmt.MethodArgument
import com.eratw.port.core.stmt.MethodStmt
import com.eratw.port.core.stmt.MixedIntegerExprTerm
import com.eratw.port.core.stmt.NextStmt
import com.eratw.port.core.stmt.PrintDataArgument
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.RefArgument
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.RepeatStmt
import com.eratw.port.core.stmt.ReturnFStmt
import com.eratw.port.core.stmt.ReturnStmt
import com.eratw.port.core.stmt.SelectCaseStmt
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.SortOrder
import com.eratw.port.core.stmt.SpArrayControlArgument
import com.eratw.port.core.stmt.SpArrayShiftArgument
import com.eratw.port.core.stmt.SpArraySortArgument
import com.eratw.port.core.stmt.SpBarArgument
import com.eratw.port.core.stmt.SpButtonArgument
import com.eratw.port.core.stmt.SpCallArgument
import com.eratw.port.core.stmt.SpCallFArgument
import com.eratw.port.core.stmt.SpCallSharpArgument
import com.eratw.port.core.stmt.SpColorArgument
import com.eratw.port.core.stmt.SpCopyArrayArgument
import com.eratw.port.core.stmt.SpCVarSetArgument
import com.eratw.port.core.stmt.SpDtColumnOptions
import com.eratw.port.core.stmt.SpForNextArgument
import com.eratw.port.core.stmt.SpGetIntArgument
import com.eratw.port.core.stmt.SpHtmlPrint
import com.eratw.port.core.stmt.SpHtmlSplitArgument
import com.eratw.port.core.stmt.SpInputsArgument
import com.eratw.port.core.stmt.SpPowerArgument
import com.eratw.port.core.stmt.SpPrintImgArgument
import com.eratw.port.core.stmt.SpPrintShapeArgument
import com.eratw.port.core.stmt.SpPrintVArgument
import com.eratw.port.core.stmt.SpSaveDataArgument
import com.eratw.port.core.stmt.SpSaveVarArgument
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SpSetArrayArgument
import com.eratw.port.core.stmt.SpSortcharaArgument
import com.eratw.port.core.stmt.SpSplitArgument
import com.eratw.port.core.stmt.SpSwapCharaArgument
import com.eratw.port.core.stmt.SpSwapVarArgument
import com.eratw.port.core.stmt.SpTimesArgument
import com.eratw.port.core.stmt.SpTInputsArgument
import com.eratw.port.core.stmt.SpVarSetArgument
import com.eratw.port.core.stmt.SpVarsizeArgument
import com.eratw.port.core.stmt.StrAsignArgument
import com.eratw.port.core.stmt.StrDataArgument
import com.eratw.port.core.stmt.StrDoubleArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VarDeclStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.stmt.WendStmt
import com.eratw.port.core.stmt.WhileStmt

/**
 * `.erbc` 解码器（v1/v2）：字节容器 → 内存 [Program]。规约正文 `specs/erbc-format.md`。
 *
 * **不依赖 [ErbcWriter]**：本类只认规约 §5 的字节布局，单测里用一段手写字节直接喂它
 * （契约 §2(c)：reader 必须能独立解出合法文件，防止「读写共享同一个私有不变量」这种假绿）。
 *
 * 装载期做四类校验，全部是**当场报错**而不是回落到可用值：
 * 1. 容器自洽：magic、版本、标志位与段表偏移的一致性；
 * 2. 声明缺段却仍需该段：`FLAG_HAS_STATEMENTS` 未置位但指令流里有 marker（op 29..197）、
    `FLAG_HAS_EXPRESSIONS` 未置位但有 `_EVAL_*` ⇒ 明确异常（契约 §2 的「不许静默降级」）；
 * 3. 引用范围：池下标 / 函数下标 / pc / 标签 pc 全部越界检查；
 * 4. 跳转归属：`_JUMP` 家族的目标必须落在发起跳转的函数区间内（[requireJumpTargets]）。
 */
object ErbcReader {

    /** 只读文件头（不触碰各段正文）：ERBC-B 的分片/mmap 决策入口。 */
    fun probe(bytes: ByteArray): ErbcHeader = Decoder(bytes).header

    /** 解出可执行 [Program]。 */
    fun decode(bytes: ByteArray): Program = Decoder(bytes).decodeProgram()

    /**
     * `else` 分支的操作码判定：**既是分类，也是操作码范围的单一咽喉**。
     *
     * `validate` 的 `when` 只显式处理少数内部指令（`_SET_*` / `_PRINT_LITERAL` / `_EVAL_*` / `_CALL`），
     * 其余操作码 —— 包括 0..201 **之外**的脏字节 —— 全部落进 `else` 分支并经由本函数。
     * 而本函数是两条装载路（字节路的 `Decoder.validate` 与映射路的 `ErbcStreamDecoder.validate`）
     * 唯一的**共同调用点**，因此把「操作码越界 ⇒ 当场拒载」放在这里，两条路径的判定与文案
     * **天然逐字相同**（`ErbcStreamEquivalenceTest` 锁死了这一点：一处改、另一处没改就是红）。
     *
     * 为什么不把范围校验只写进字节路的 `validate`：那是 `ErbcStream.kt` 逐字副本的对侧，
     * 单边改会立刻让两条装载路对同一份损坏产物给出不同结论。规约 §1.6「禁止静默降级」要求
     * `op=500` 这类脏字节在**装载期**被拒，而不是留到 VM 跑到那条才报「语句 <op:500> 尚未实现」。
     *
     * @return true = 该操作码是 ERB 语句 marker（区间见 `ErbcFormat.MARKER_OPCODE_MIN/MAX`）
     * @throws ErbcDecodeException 操作码不在 `0..ErbcFormat.OPCODE_MAX`（损坏字节）
     */
    internal fun needsStatementPool(op: Int): Boolean {
        if (!ErbcFormat.isValidOpcode(op)) {
            throw ErbcDecodeException(
                0,
                "操作码 $op 越出 0..${ErbcFormat.OPCODE_MAX}" +
                    "（VM 内部 0..28 / isa 语句 29..197 / 编译器扩展 198..201 / 栈码 202..243）：字节码损坏",
            )
        }
        return op in ErbcFormat.MARKER_OPCODE_MIN..ErbcFormat.MARKER_OPCODE_MAX
    }

    /**
     * 栈码组（T2-21，202..243）的池引用校验。
     *
     * 栈码把 `_EVAL_*` 的表达式池下标换成**直接操作数**：`_STK_PUSH_IMM` 的 `a` 是
     * 常量池下标，`_STK_PUSH_STR` / `_STK_PUSH_VAR_I/S` / `_STK_PLUS_VAR_I` /
     * `_STK_STORE_VAR_I/S` 的 `a` 是字符串池下标。这些操作数**不进** [needsStatementPool]
     * 的 marker 区间，也不在 `_SET_*` / `_EVAL_*` 的既有分支里 —— 不在这里拦，畸形产物
     * 会一路装进 VM，直到那条指令执行时才以裸 `IndexOutOfBoundsException` 冒出来
     * （无函数/行号，与「装载期一次扫完，运行期不再判」的口径相反）。
     *
     * **单一真源**：`ErbcReader.Decoder.validate` 与 `loader.ErbcStream` 的校验趟是逐字
     * 副本（见 `ErbcStream.kt` 类 KDoc），两处都调本函数 —— 否则「同一份产物、两条装载路」
     * 会出现一档放行一档报错的判定漂移（与 [requireJumpTargets] 同一个理由）。
     *
     * 热路径零分配：只有 `_STK_*`（装载期判 `isStackOp` 先过一道区间比较）才会进这里，
     * 480 万条指令的扫描趟多出的就是这一次区间比较；函数体本身不分配。
     *
     * **分工**：这里只查**池下标**（`a`）。`_STK_*_VAR_*` 的 `b`（维数）不在装载期查 ——
     * 它不是池引用，运行期 [Vm] 的 `popIndices` 用同一个 `0..3` 合法域兜底
     * （CodeBuilder 裸拼装的程序不经过装载校验，运行期守卫是唯一一道）。
     * `_STK_POP_SLOT_*` 的槽由 `setInt/setStr` 的既有槽守卫兜底，同理不在装载期查。
     */
    internal fun requireStackOpPool(
        op: Int,
        a: Int,
        constantCount: Int,
        stringCount: Int,
        pc: Int,
    ) {
        when (op) {
            Opcode._STK_PUSH_IMM -> {
                if (a < 0 || a >= constantCount) {
                    throw ErbcDecodeException(
                        0,
                        "pc=$pc 的 ${Opcode.nameOf(op)} 引用常量池下标 $a，越界（共 $constantCount 条）",
                    )
                }
            }

            Opcode._STK_PUSH_STR,
            Opcode._STK_PUSH_VAR_I,
            Opcode._STK_PUSH_VAR_S,
            Opcode._STK_PLUS_VAR_I,
            Opcode._STK_STORE_VAR_I,
            Opcode._STK_STORE_VAR_S,
            -> {
                if (a < 0 || a >= stringCount) {
                    throw ErbcDecodeException(
                        0,
                        "pc=$pc 的 ${Opcode.nameOf(op)} 引用字符串池下标 $a，越界（共 $stringCount 条）",
                    )
                }
            }
        }
    }

    /**
     * 跳转目标归属校验（装载期，一次扫完；CMP-55 夹具①）。
     *
     * `_JUMP`/`_JUMP_IF_FALSE`/`_JUMP_IF_TRUE` 的偏移单位是**指令条数**（[Instruction]
     * 的 `OFFSET` 模式），`_JUMP_DYN` 的 a 是**当前函数**标签表下标。此前装载期一条
     * 跳转都不判，于是：
     *
     * * 跨函数跳（如 `_JUMP a=1` 从某函数首指令落到下一函数首指令）装载零抛出，
     *   运行期静默执行错函数体 —— 调用栈返回点留在别的函数里，帧模型直接坏掉；
     * * 越界跳（目标 ≥ 指令流长度）要到 VM 跑到那条才抛「指令指针越界」。
     *
     * 这里按「函数按 entryPc 连续排布」的不变量（与 `Vm.transferEnds` 同一推导：
     * 按 entryPc 排序后取后继，最后一个函数以指令流长度为上界）逐函数扫自己的
     * `[entryPc, end)` 区间，任一跳转目标落在区间外即报 [ErbcDecodeException]。
     * 空函数（与后继共享 entryPc）区间为空，跳过扫描。
     *
     * **单一真源**：`ErbcStreamDecoder.validate` 是 `Decoder.validate` 的逐字副本
     * （见 `ErbcStream.kt` 类 KDoc），两处必须给出同一判定。CMP-55 收口时只接了字节路，
     * 2026-10-26 补齐映射路：`MappedFunctionTable.JumpScan` 用同一份判据与**同一组文案**
     * （[jumpOutOfRangeMes] / [jumpDynLabelIndexMes]），但不物化 110,072 条 [FunctionInfo]
     * —— 区间端点只读记录里的 `entryPc`，标签 pc 仅对含 `_JUMP_DYN` 的函数按需重放。
     */
    fun requireJumpTargets(program: Program) {
        val funcs = program.functions
        val count = program.instructionCount
        val order = funcs.indices.sortedBy { funcs[it].entryPc }
        val ends = IntArray(funcs.size)
        for (k in order.indices) {
            ends[order[k]] = if (k + 1 < order.size) funcs[order[k + 1]].entryPc else count
        }
        for (fi in funcs.indices) {
            val f = funcs[fi]
            val start = f.entryPc
            val end = ends[fi]
            if (end <= start) continue
            for (pc in start until end) {
                when (val op = program.op(pc)) {
                    Opcode._JUMP ->
                        requireJumpTarget(f.name, start, end, pc, op, pc.toLong() + 1 + program.a(pc))
                    Opcode._JUMP_IF_FALSE, Opcode._JUMP_IF_TRUE ->
                        requireJumpTarget(f.name, start, end, pc, op, pc.toLong() + 1 + program.b(pc))
                    // 栈码的条件跳（T2-21）与 `_JUMP_IF_*` 同一档：偏移在 a、相对下一条指令。
                    // 不覆盖就会漏掉 CMP-55 要防的那件事 —— 栈码跳进**别的函数体**，
                    // 调用栈的返回点留在另一个函数里，帧模型坏掉（且只在畸形产物上偶发）。
                    Opcode._STK_JZ_POP, Opcode._STK_JNZ_POP, Opcode._STK_JZ_KEEP ->
                        requireJumpTarget(f.name, start, end, pc, op, pc.toLong() + 1 + program.a(pc))
                    Opcode._JUMP_DYN -> {
                        val index = program.a(pc)
                        if (index < 0 || index >= f.labelPcs.size) {
                            throw jumpDynLabelIndexOutOfRange(pc, index, f.name, f.labelPcs.size)
                        }
                        requireJumpTarget(f.name, start, end, pc, op, f.labelPcs[index].toLong())
                    }
                }
            }
        }
    }

    /**
     * 跳转目标越出所属函数的**上屏文案**（单一真源）。
     *
     * 字节路的 [requireJumpTargets] 与映射路的 `MappedFunctionTable.JumpScan` 共用本函数，
     * `ErbcStreamEquivalenceTest` 逐字节变异对拍要求两条路「同一份畸形产物 ⇒ 逐字相同的
     * 异常文案」——文案只许在这里改，不许在任一侧另写一份。
     */
    fun jumpOutOfRangeMes(
        pc: Int,
        op: Int,
        target: Long,
        funcName: String,
        start: Int,
        end: Int,
    ): String =
        "pc=$pc 的 ${Opcode.nameOf(op)} 跳转目标 $target 越出函数 $funcName 的区间 [$start, $end)" +
            "（跨函数或越界跳转：字节码不完整）"

    /** `_JUMP_DYN` 标签下标越界的上屏文案（单一真源，理由同 [jumpOutOfRangeMes]）。 */
    fun jumpDynLabelIndexMes(
        pc: Int,
        index: Int,
        funcName: String,
        labelCount: Int,
    ): String = "pc=$pc 的 _JUMP_DYN 标签下标 $index 越界（函数 $funcName 共 $labelCount 个标签）"

    private fun jumpDynLabelIndexOutOfRange(
        pc: Int,
        index: Int,
        funcName: String,
        labelCount: Int,
    ): ErbcDecodeException = ErbcDecodeException(0, jumpDynLabelIndexMes(pc, index, funcName, labelCount))

    /** 单条跳转的归属判据：目标必须落在**发起跳转的函数**区间内（见 [requireJumpTargets]）。 */
    private fun requireJumpTarget(
        funcName: String,
        start: Int,
        end: Int,
        pc: Int,
        op: Int,
        target: Long,
    ) {
        if (target < start || target >= end) {
            throw ErbcDecodeException(0, jumpOutOfRangeMes(pc, op, target, funcName, start, end))
        }
    }

    private class Decoder(private val bytes: ByteArray) {
        val header: ErbcHeader = parseHeader()

        fun decodeProgram(): Program {
            val flags = header.flags
            val offsets = header.offsets

            // ---- 段 0：字符串池（index 即下标）------------------------------------
            val strings: List<String> = if (offsets[ErbcFormat.SECTION_STRINGS] == 0) {
                emptyList()
            } else {
                val view = readStringTable(cursorAt(ErbcFormat.SECTION_STRINGS))
                ArrayList<String>(view.count).apply { for (i in 0 until view.count) add(view[i]) }
            }

            // ---- 段 1：常量池（i64；f64 位 v1 恒空）------------------------------
            val longs = if (offsets[ErbcFormat.SECTION_CONSTANTS] == 0) {
                LongArray(0)
            } else {
                val cur = cursorAt(ErbcFormat.SECTION_CONSTANTS)
                // 每项是 i64：先按 8 字节/项卡分配上界，避免畸形 count 撑出超大 LongArray。
                val n = cur.count("常量池长度", 8)
                val doubleCount = cur.count("f64 常量数")
                if (doubleCount != 0) {
                    throw ErbcDecodeException(
                        cur.pos,
                        "f64 常量池未定稿（ConstantPool 只有 Long 池，规约 §5.2 / §9）：文件声明 $doubleCount 个",
                    )
                }
                LongArray(n) { cur.i64() }
            }

            // ---- 段 2：表达式池（节点表 + 根下标）--------------------------------
            val exprRoots: List<Expr> = if (!header.hasSection(ErbcFormat.SECTION_EXPRESSIONS)) {
                emptyList()
            } else {
                val cur = cursorAt(ErbcFormat.SECTION_EXPRESSIONS)
                // 节点记录最短是 NULL_TERM（tag + isInt + line + col = 10 字节）；
                // 池根每项一个 u32。先卡上界再分配，畸形 count 不可能撑出巨型数组。
                val nodeCount = cur.count("表达式节点数", 10)
                val poolCount = cur.count("表达式池长度", 4)
                val strings2 = readStringTable(cur)
                val arena = RecordSource(strings2, nodeCount).apply { readNodes(cur) }
                ArrayList<Expr>(poolCount).apply {
                    repeat(poolCount) { add(arena.nodeAt(cur.u32())) }
                }
            }

            // ---- 段 3：语句池（身份表：一条记录 = 一个实例，严禁合并）--------------
            val stmts: List<Statement> = if (!header.hasSection(ErbcFormat.SECTION_STATEMENTS)) {
                emptyList()
            } else {
                val cur = cursorAt(ErbcFormat.SECTION_STATEMENTS)
                // 节点记录同理（最短 10 字节）；语句记录最短是 INSTRUCTION + VoidArgument
                // （tag 1 + line 4 + raw 4 + code 4 + argTag 1 + argHeader 1 = 15 字节）。
                val nodeCount = cur.count("语句段节点数", 10)
                val stmtCount = cur.count("语句池长度", 15)
                val strings3 = readStringTable(cur)
                val arena = RecordSource(strings3, nodeCount).apply { readNodes(cur) }
                ArrayList<Statement>(stmtCount).apply {
                    repeat(stmtCount) { add(arena.readStatement(cur)) }
                }
            }

            // ---- 段 4：函数表 ----------------------------------------------------
            if (!header.hasSection(ErbcFormat.SECTION_FUNCTIONS)) {
                throw ErbcDecodeException(0, "函数表缺失：v1 产物必须携带 SECTION_FUNCTIONS")
            }
            val funcCur = cursorAt(ErbcFormat.SECTION_FUNCTIONS)
            // 每条函数记录至少 24 字节（name/entryPc/arity/slotCount/flags/labelCount 各 4）。
            val funcCount = funcCur.count("函数个数", 24)
            val entryFunction = funcCur.u32()
            val globalSlots = funcCur.u32()
            if (globalSlots < 0) throw ErbcDecodeException(funcCur.pos - 4, "全局槽数为负: $globalSlots")
            // ---- 段 8：函数头部形参名（v2；缺段 ⇒ 空表 = 声明「不带」，绑定退化为类型桶）----
            val paramNames = if (header.hasSection(ErbcFormat.SECTION_PARAM_NAMES)) {
                readParamNames(cursorAt(ErbcFormat.SECTION_PARAM_NAMES), funcCount)
            } else {
                emptyList()
            }
            val funcStrings = readStringTable(funcCur)
            val functions = ArrayList<FunctionInfo>(funcCount)
            val seenNames = HashSet<String>(funcCount)
            for (i in 0 until funcCount) {
                val name = funcStrings[funcCur.u32()]
                if (!seenNames.add(name)) throw ErbcDecodeException(funcCur.pos - 4, "函数重复声明: $name")
                val entryPc = funcCur.i32()
                val arity = funcCur.i32()
                val slotCount = funcCur.i32()
                val funcFlags = funcCur.u32()
                if (funcFlags != 0) {
                    throw ErbcDecodeException(
                        funcCur.pos - 4,
                        "函数 $name 带标志位(0x${funcFlags.toString(16)})，但 FunctionInfo 无对应字段（规约 §5.5 / §9）",
                    )
                }
                // 每条标签记录 = u32 名字下标 + i32 pc。
                val labelCount = funcCur.count("函数 $name 的标签数", 8)
                val info = FunctionInfo(name, entryPc, arity, slotCount, paramNames.getOrElse(i) { emptyList() })
                for (j in 0 until labelCount) {
                    // 求值序不可换：先读名字下标、再读 pc，与容器里的「名字, pc」成对序一致。
                    info.addLabel(funcStrings[funcCur.u32()], funcCur.i32())
                }
                functions.add(info)
            }

            // ---- 段 5：指令流 ----------------------------------------------------
            if (!header.hasSection(ErbcFormat.SECTION_CODE)) {
                throw ErbcDecodeException(0, "指令流缺失：v1 产物必须携带 SECTION_CODE")
            }
            val codeCur = cursorAt(ErbcFormat.SECTION_CODE)
            val instructionCount = codeCur.count("指令条数")
            if (instructionCount == 0) throw ErbcDecodeException(codeCur.pos - 4, "空程序：指令条数为 0")
            if (instructionCount > codeCur.remaining / Instruction.WORDS) {
                throw ErbcDecodeException(
                    codeCur.pos - 4,
                    "指令条数($instructionCount)超出本段可用字节(${codeCur.remaining})，文件被截断",
                )
            }
            val code = IntArray(instructionCount * Instruction.WORDS) { codeCur.u32() }

            // ---- 段 6：行号表 --------------------------------------------------
            val lines = if (!header.hasSection(ErbcFormat.SECTION_LINES)) {
                IntArray(instructionCount) { -1 } // -1 = 未知（Program.kt:117 的既有口径）
            } else {
                val cur = cursorAt(ErbcFormat.SECTION_LINES)
                val fileCount = cur.count("源文件表长度")
                if (fileCount != 0) {
                    throw ErbcDecodeException(
                        cur.pos - 4,
                        "行号表带源文件 id($fileCount)，但 Program.lines 只有行号（规约 §5.7 / §9）",
                    )
                }
                val n = cur.count("行号表长度", 4)
                if (n != instructionCount) {
                    throw ErbcDecodeException(cur.pos - 4, "行号表长度($n)与指令条数($instructionCount)不符")
                }
                IntArray(n) { cur.i32() }
            }

            // ---- 段 7：调用点实参表（v2；缺段 ⇒ 空表 = 声明「不带」）----------------
            val callArgs = if (header.hasSection(ErbcFormat.SECTION_CALL_ARGS)) {
                readCallArgs(cursorAt(ErbcFormat.SECTION_CALL_ARGS), instructionCount)
            } else {
                emptyMap()
            }

            // ---- 池重灌 + 下标保真校验 ----------------------------------------
            val pool = ConstantPool()
            for (s in strings) pool.addString(s)
            for (l in longs) pool.addLong(l)
            for (e in exprRoots) pool.addExpr(e)
            for (s in stmts) pool.addStatement(s)
            if (pool.stringCount != strings.size) {
                throw ErbcDecodeException(0, "字符串池重灌后下标漂移（${strings.size} → ${pool.stringCount}）")
            }
            if (pool.longCount != longs.size) {
                throw ErbcDecodeException(0, "常量池重灌后下标漂移（${longs.size} → ${pool.longCount}）")
            }
            if (pool.exprCount != exprRoots.size) {
                throw ErbcDecodeException(
                    0,
                    "表达式池重灌后下标漂移（${exprRoots.size} → ${pool.exprCount}）：文件里有结构相等的重复表达式，" +
                        "v1 的池下标契约要求它们本就同一条目",
                )
            }
            if (pool.statementCount != stmts.size) {
                throw ErbcDecodeException(0, "语句池重灌后下标漂移（${stmts.size} → ${pool.statementCount}）")
            }

            val program = Program(
                code = code,
                lines = lines,
                pool = pool,
                functions = functions,
                entryFunction = entryFunction,
                globalSlots = globalSlots,
                callArgs = callArgs,
                callArgSource = if (header.version >= ErbcFormat.VERSION_2) {
                    CallArgSource.ERBC_V2
                } else {
                    CallArgSource.ERBC_V1
                },
            )
            validate(program, flags)
            return program
        }

        // ----------------------------------------------------------------
        // 容器校验
        // ----------------------------------------------------------------

        fun parseHeader(): ErbcHeader {
            val cur = ErbcCursor(bytes)
            if (bytes.size < ErbcFormat.HEADER_BYTES) {
                throw ErbcDecodeException(0, "文件不足文件头长度(${bytes.size} 字节)")
            }
            val m0 = cur.u8()
            val m1 = cur.u8()
            val m2 = cur.u8()
            val m3 = cur.u8()
            if (m0 != ErbcFormat.MAGIC_0 || m1 != ErbcFormat.MAGIC_1 ||
                m2 != ErbcFormat.MAGIC_2 || m3 != ErbcFormat.MAGIC_3
            ) {
                val got = listOf(m0, m1, m2, m3).joinToString(" ") { it.toString(16) }
                throw ErbcDecodeException(0, "magic 不符：期望 ${ErbcFormat.MAGIC_TEXT}（45 52 42 43），实为 [$got]")
            }
            val version = cur.u16()
            val expectedSections = ErbcFormat.sectionCountFor(version)
            if (expectedSections < 0) {
                throw ErbcDecodeException(
                    4,
                    "不支持的 .erbc 版本 $version（本引擎只认 v${ErbcFormat.VERSION_1}/v${ErbcFormat.VERSION_2}，" +
                        "不做降级解释）",
                )
            }
            val flags = cur.u16()
            val flagMask = ErbcFormat.flagMaskFor(version)
            if ((flags and flagMask.inv()) != 0) {
                throw ErbcDecodeException(6, "标志位含 v$version 未定义位: 0x${flags.toString(16)}")
            }
            val sectionCount = cur.u16()
            if (sectionCount != expectedSections) {
                throw ErbcDecodeException(
                    8,
                    "段表槽数 $sectionCount 与 v$version 固定的 $expectedSections 不符",
                )
            }
            val offsets = IntArray(sectionCount) { cur.u32() }
            for (i in 0 until sectionCount) {
                val off = offsets[i]
                if (off < 0) throw ErbcDecodeException(cur.pos - 4, "段 $i 偏移为负: $off")
                if (off != 0 && off < ErbcFormat.HEADER_BYTES) {
                    throw ErbcDecodeException(cur.pos - 4, "段 $i 偏移($off)落在文件头/段表内")
                }
                if (off >= bytes.size) throw ErbcDecodeException(cur.pos - 4, "段 $i 偏移($off)越过文件尾(${bytes.size})")
            }
            // 标志位 ⇔ 段存在，双向一致
            requireBit(flags, offsets, ErbcFormat.FLAG_HAS_LINES, ErbcFormat.SECTION_LINES)
            requireBit(flags, offsets, ErbcFormat.FLAG_HAS_EXPRESSIONS, ErbcFormat.SECTION_EXPRESSIONS)
            requireBit(flags, offsets, ErbcFormat.FLAG_HAS_STATEMENTS, ErbcFormat.SECTION_STATEMENTS)
            if (version >= ErbcFormat.VERSION_2) {
                requireBit(flags, offsets, ErbcFormat.FLAG_HAS_CALL_ARGS, ErbcFormat.SECTION_CALL_ARGS)
                requireBit(flags, offsets, ErbcFormat.FLAG_HAS_PARAM_NAMES, ErbcFormat.SECTION_PARAM_NAMES)
            }
            return ErbcHeader(version, flags, sectionCount, offsets)
        }

        private fun requireBit(
            flags: Int,
            offsets: IntArray,
            bit: Int,
            section: Int,
        ) {
            val present = offsets[section] != 0
            val declared = (flags and bit) != 0
            if (present != declared) {
                throw ErbcDecodeException(
                    ErbcFormat.HEADER_BYTES + section * 4,
                    "标志位与段表不一致：${ErbcFormat.SECTION_NAMES[section]} 段表偏移=${offsets[section]}，" +
                        "标志位声明=${if (declared) "存在" else "不存在"}",
                )
            }
        }

        private fun cursorAt(section: Int): ErbcCursor {
            val off = header.offsets[section]
            if (off == 0) throw ErbcDecodeException(0, "段 ${ErbcFormat.SECTION_NAMES[section]} 不存在")
            return ErbcCursor(bytes, off)
        }

        /**
         * 引用范围校验（装载期一次扫完，运行期不再判）。
         *
         * marker 区间（op 29..197）与 `_EVAL_*` 是「缺段即静默失效」的两个入口，
         * 这里给的是**指名道姓**的错误，而不是让 VM 跑到那一行才炸。
         */
        private fun validate(
            program: Program,
            flags: Int,
        ) {
            if (program.functions.isEmpty()) throw ErbcDecodeException(0, "函数表为空")
            if (program.entryFunction !in program.functions.indices) {
                throw ErbcDecodeException(0, "入口函数下标越界: ${program.entryFunction}（共 ${program.functions.size} 个）")
            }
            val count = program.instructionCount
            val hasExpr = (flags and ErbcFormat.FLAG_HAS_EXPRESSIONS) != 0
            val hasStmt = (flags and ErbcFormat.FLAG_HAS_STATEMENTS) != 0
            for (pc in 0 until count) {
                val op = program.op(pc)
                val a = program.a(pc)
                val b = program.b(pc)
                when (op) {
                    Opcode._SET_INT -> requirePool(op, b, program.constantCount, pc, "常量池")
                    Opcode._SET_STR -> requirePool(op, b, program.stringCount, pc, "字符串池")
                    Opcode._PRINT_LITERAL -> requirePool(op, a, program.stringCount, pc, "字符串池")
                    Opcode._EVAL_INT, Opcode._EVAL_STR -> {
                        if (!hasExpr) {
                            throw ErbcDecodeException(
                                0,
                                "本文件声明不含表达式池，但 pc=$pc 的 ${Opcode.nameOf(op)} 需要表达式下标 $a",
                            )
                        }
                        requirePool(op, a, program.exprCount, pc, "表达式池")
                    }

                    Opcode._CALL -> {
                        if (a !in program.functions.indices) {
                            throw ErbcDecodeException(0, "pc=$pc 的 _CALL 引用函数下标 $a，越界（共 ${program.functions.size} 个）")
                        }
                    }

                    else -> if (Opcode.isStackOp(op)) {
                        // 栈码组：操作数直接是池下标（T2-21），判据只有 [requireStackOpPool] 一份。
                        requireStackOpPool(op, a, program.constantCount, program.stringCount, pc)
                    } else if (needsStatementPool(op)) {
                        if (!hasStmt) {
                            throw ErbcDecodeException(
                                0,
                                "本文件声明不含语句池，但 pc=$pc 的语句 marker ${Opcode.nameOf(op)} 需要语句下标 $a" +
                                    "（数据节以 Statement 实例为键，缺池即静默失效）",
                            )
                        }
                        requirePool(op, a, program.statementCount, pc, "语句池")
                    }
                }
            }
            for (f in program.functions) {
                if (f.entryPc !in 0 until count) {
                    throw ErbcDecodeException(0, "函数 ${f.name} 的 entryPc=${f.entryPc} 越界（指令数 $count）")
                }
                if (f.arity < 0 || f.slotCount < 0) {
                    throw ErbcDecodeException(0, "函数 ${f.name} 的 arity/slotCount 为负: ${f.arity}/${f.slotCount}")
                }
                // 跨字段不变量（写侧 ErbcWriter 同款；规约 §1.6「读不懂即报错」）：
                // arity 是被调帧 R0..R(arity-1) 的实参落位数，必须 <= 帧槽数 slotCount。
                // 允许 arity > slotCount 会让 VM 建帧时的实参复制越出被调帧 —— 装载期即拒。
                if (f.arity > f.slotCount) {
                    throw ErbcDecodeException(
                        0,
                        "函数 ${f.name} 的 arity(${f.arity}) 大于 slotCount(${f.slotCount})：" +
                            "实参复制会越出被调帧槽区间（畸形产物）",
                    )
                }
                for (i in 0 until f.labelNames.size) {
                    val pc = f.labelPcs[i]
                    if (pc !in 0 until count) {
                        throw ErbcDecodeException(0, "函数 ${f.name} 的标签 \$${f.labelNames[i]} 的 pc=$pc 越界（指令数 $count）")
                    }
                }
            }
            // 跳转目标归属（CMP-55）：单列一趟，两条装载路共用同一判据。
            requireJumpTargets(program)
        }

        private fun requirePool(
            op: Int,
            index: Int,
            size: Int,
            pc: Int,
            what: String,
        ) {
            if (index < 0 || index >= size) {
                throw ErbcDecodeException(0, "pc=$pc 的 ${Opcode.nameOf(op)} 引用$what 下标 $index，越界（共 $size 条）")
            }
        }
    }
}

/**
 * 读段 7（调用点实参表，规约 §5.8）：
 *
 * ```
 * u32 callSiteCount
 * u32 totalArgCount
 * STRING_TABLE               段内局部串池（实参裸变量名）
 * record site[callSiteCount] 按 pc 严格升序
 *     u32 pc, u32 argCount, record arg[argCount] 按 position 升序
 *         u32 position, u32 bucket, u8 flags, u32 name(NONE_REF = null)
 * ```
 *
 * 站点 pc 与实参位置都要求**严格升序**：写侧已规范化（`ErbcWriter.writeCallArgs` 排序），
 * 读侧把「顺序不合法」当成畸形而不是替它排序 —— 排序会掩盖「同一 pc 写了两份表」这种
 * 语义级损坏。`totalArgCount` 是冗余校验：与逐站求和不等即文件被截断/篡改。
 */
private fun readCallArgs(
    cur: ErbcCursor,
    instructionCount: Int,
): Map<Int, List<CallArg>> {
    val siteCount = cur.count("调用点条数", 8)
    val totalArgCount = cur.count("实参总数")
    val names = readStringTable(cur)
    val out = LinkedHashMap<Int, List<CallArg>>()
    var seenArgs = 0
    var prevPc = -1
    for (i in 0 until siteCount) {
        val at = cur.pos
        val pc = cur.u32()
        if (pc < 0 || pc >= instructionCount) {
            throw ErbcDecodeException(at, "第 $i 个调用点 pc=$pc 越界（指令数 $instructionCount）")
        }
        if (pc <= prevPc) {
            throw ErbcDecodeException(at, "调用点 pc 未严格升序：$pc 紧跟 $prevPc（同一 pc 只能有一份实参表）")
        }
        prevPc = pc
        // 每条实参记录 = position 4 + bucket 4 + flags 1 + name 4 = 13 字节。
        val argCount = cur.count("调用点 pc=$pc 的实参个数", 13)
        if (argCount == 0) {
            throw ErbcDecodeException(cur.pos - 4, "调用点 pc=$pc 的实参个数为 0（v2 只登记非空表）")
        }
        seenArgs += argCount
        val args = ArrayList<CallArg>(argCount)
        var prevPos = -1
        for (j in 0 until argCount) {
            val atArg = cur.pos
            val position = cur.u32()
            if (position < 0) throw ErbcDecodeException(atArg, "调用点 pc=$pc 的第 $j 个实参位为负: $position")
            if (position <= prevPos) {
                throw ErbcDecodeException(atArg, "调用点 pc=$pc 的实参位未严格升序：$position 紧跟 $prevPos")
            }
            prevPos = position
            val bucket = cur.u32()
            if (bucket < 0) throw ErbcDecodeException(atArg + 4, "调用点 pc=$pc 的实参桶号为负: $bucket")
            val flags = cur.u8()
            if ((flags and ErbcFormat.CallArgFlags.MASK.inv()) != 0) {
                throw ErbcDecodeException(atArg + 8, "调用点 pc=$pc 的实参 flags 含未定义位: 0x${flags.toString(16)}")
            }
            val nameIdx = cur.u32()
            val name = if (nameIdx == NONE_REF) null else names[nameIdx]
            args.add(
                CallArg(
                    position = position,
                    bucket = bucket,
                    isStr = (flags and ErbcFormat.CallArgFlags.IS_STR) != 0,
                    name = name,
                    present = (flags and ErbcFormat.CallArgFlags.PRESENT) != 0,
                ),
            )
        }
        out[pc] = args
    }
    if (seenArgs != totalArgCount) {
        throw ErbcDecodeException(0, "实参总数声明 $totalArgCount，逐站求和为 $seenArgs（文件被截断或计数被篡改）")
    }
    return out
}

/**
 * 读段 8（函数头部形参名表，规约 §5.9）：
 *
 * ```
 * u32 funcCount              必须 == 函数表 funcCount
 * STRING_TABLE               段内局部串池（形参名）
 * record func[funcCount]     下标 = 函数下标
 *     u32 paramCount, u32 name[paramCount]
 * ```
 *
 * `funcCount` 与段 4 的对账是本段唯一的结构约束：形参名按**位置**被
 * `ProcessRuntime.bindCallArgs` 消费，记录错位等于 REF 绑到别的形参上。
 */
private fun readParamNames(
    cur: ErbcCursor,
    expectedFuncCount: Int,
): List<List<String>> {
    val funcCount = cur.count("形参名段函数个数", 4)
    if (funcCount != expectedFuncCount) {
        throw ErbcDecodeException(
            cur.pos - 4,
            "形参名段函数个数($funcCount)与函数表($expectedFuncCount)不符",
        )
    }
    val names = readStringTable(cur)
    val out = ArrayList<List<String>>(funcCount)
    for (i in 0 until funcCount) {
        // 每个函数至少一个 u32 paramCount。
        val paramCount = cur.count("函数 $i 的形参个数", 4)
        val list = ArrayList<String>(paramCount)
        for (j in 0 until paramCount) list.add(names[cur.u32()])
        out.add(list)
    }
    return out
}

/**
 * 记录解码器（节点表 + 语句/参数记录），与 `ErbcWriter.RecordSink` 逐字段互为逆函数。
 *
 * 节点表按「子节点下标 < 父节点下标」的后序性质单遍构建，因此读侧零递归：
 * 深表达式树不会撑爆栈，这是「节点表」相对「内联递归」的实质收益（规约 §5.3）。
 */
private class RecordSource(
    private val strings: ErbcStringView,
    nodeCount: Int,
) {
    private val nodes = arrayOfNulls<Expr>(nodeCount)

    fun str(i: Int): String = strings[i]

    fun strOpt(i: Int): String? = if (i == NONE_REF) null else strings[i]

    fun nodeAt(index: Int): Expr {
        if (index < 0 || index >= nodes.size) {
            throw ErbcDecodeException(0, "表达式根下标越界: $index（节点表长 ${nodes.size}）")
        }
        return nodes[index] ?: throw ErbcDecodeException(0, "节点 $index 尚未构建")
    }

    /**
     * 取子节点。
     *
     * [self] >= 0 表示「节点表内部引用」，除越界外还要检查后序性（引用必须早于自身）；
     * [self] < 0 表示「记录作用域」——此时节点表已整体构建完毕，任何已构建节点都可引用。
     */
    private fun child(
        self: Int,
        index: Int,
    ): Expr {
        if (index < 0 || index >= nodes.size) {
            throw ErbcDecodeException(0, "节点下标越界: $index（表长 ${nodes.size}）")
        }
        if (self >= 0 && index >= self) {
            throw ErbcDecodeException(0, "节点 $self 引用了不合法的后继下标 $index（节点表要求 引用下标 < 自身下标）")
        }
        return nodes[index] ?: throw ErbcDecodeException(0, "节点 $index 尚未构建")
    }

    private fun childOpt(
        self: Int,
        index: Int,
    ): Expr? = if (index == NONE_REF) null else child(self, index)

    private fun varRef(
        self: Int,
        index: Int,
    ): VarRef {
        val e = child(self, index)
        return e as? VarRef
            ?: throw ErbcDecodeException(0, "节点 $index 应为 VarRef，实为 ${e.javaClass.simpleName}")
    }

    private fun varRefOpt(
        self: Int,
        index: Int,
    ): VarRef? = if (index == NONE_REF) null else varRef(self, index)

    // ------------------------------------------------------------------
    // 节点表
    // ------------------------------------------------------------------

    fun readNodes(cur: ErbcCursor) {
        for (i in nodes.indices) nodes[i] = readNode(cur, i)
    }

    private fun readNode(
        cur: ErbcCursor,
        self: Int,
    ): Expr {
        val tag = cur.u8()
        val e: Expr = when (tag) {
            ExprTag.INT_LITERAL -> IntLiteral(cur.i64(), cur.i32(), cur.i32())
            ExprTag.STR_LITERAL -> StrLiteral(str(cur.u32()), cur.i32(), cur.i32())
            ExprTag.NULL_TERM -> NullTerm(cur.bool(), cur.i32(), cur.i32())
            ExprTag.VAR_REF -> {
                val name = str(cur.u32())
                val subKey = strOpt(cur.u32())
                val type = operandType(cur.u8())
                val flags = cur.u8()
                val indices = readChildList(cur, self)
                VarRef(
                    name = name,
                    indices = indices,
                    isNoArg = (flags and 1) != 0,
                    operandType = type,
                    isConst = (flags and 2) != 0,
                    subKey = subKey,
                    line = cur.i32(),
                    col = cur.i32(),
                )
            }

            ExprTag.KEYWORD_INDEX_ARG -> KeywordIndexArg(
                child(self, cur.u32()),
                str(cur.u32()),
                cur.i32(),
                cur.i32(),
                cur.i32(),
            )

            ExprTag.FUNC_CALL -> {
                val name = str(cur.u32())
                val type = operandType(cur.u8())
                val args = readChildOptList(cur, self)
                FuncCall(name, args, type, cur.i32(), cur.i32())
            }

            ExprTag.UNARY -> Unary(operatorName(cur.u32()), child(self, cur.u32()), cur.i32(), cur.i32())
            ExprTag.UNARY_AFTER -> UnaryAfter(operatorName(cur.u32()), child(self, cur.u32()), cur.i32(), cur.i32())
            ExprTag.BINARY -> {
                val op = operatorName(cur.u32())
                val left = child(self, cur.u32())
                val right = child(self, cur.u32())
                val type = operandType(cur.u8())
                Binary(op, left, right, type, cur.i32(), cur.i32())
            }

            ExprTag.TERNARY -> {
                val cond = child(self, cur.u32())
                val ifTrue = child(self, cur.u32())
                val ifFalse = child(self, cur.u32())
                val type = operandType(cur.u8())
                Ternary(cond, ifTrue, ifFalse, type, cur.i32(), cur.i32())
            }

            ExprTag.FORM_STRING -> {
                // TEXT 部件最少 5 字节（u8 标签 + u32 串下标），按此先卡分配上界。
                val n = cur.count("FormStringTerm 部件数", 5)
                val parts = ArrayList<FormPart>(n)
                repeat(n) {
                    when (val partTag = cur.u8()) {
                        ErbcFormat.FormPartTag.TEXT -> parts.add(FormPart.Text(str(cur.u32())))
                        ErbcFormat.FormPartTag.EMBEDDED -> {
                            val ast = child(self, cur.u32())
                            parts.add(FormPart.Embedded(ast, readConversion(cur, self)))
                        }

                        else -> throw ErbcDecodeException(cur.pos - 1, "未知 FormPart 标签: $partTag")
                    }
                }
                FormStringTerm(parts, cur.i32(), cur.i32())
            }

            else -> throw ErbcDecodeException(cur.pos - 1, "未知表达式节点标签: $tag")
        }
        return e
    }

    private fun readConversion(
        cur: ErbcCursor,
        self: Int,
    ): FormConversion {
        val tag = cur.u8()
        return when (tag) {
            ErbcFormat.FormConversionTag.CURLY -> FormConversion.Curly(childOpt(self, cur.u32()), cur.bool())
            ErbcFormat.FormConversionTag.PERCENT -> FormConversion.Percent(childOpt(self, cur.u32()), cur.bool())
            ErbcFormat.FormConversionTag.YEN_AT -> FormConversion.YenAt(child(self, cur.u32()), child(self, cur.u32()))
            else -> throw ErbcDecodeException(cur.pos - 1, "未知 FormConversion 标签: $tag")
        }
    }

    private fun readChildList(
        cur: ErbcCursor,
        self: Int,
    ): List<Expr> {
        val n = cur.count("表达式下标表", 4)
        return ArrayList<Expr>(n).apply { repeat(n) { add(child(self, cur.u32())) } }
    }

    private fun readChildOptList(
        cur: ErbcCursor,
        self: Int,
    ): List<Expr?> {
        val n = cur.count("表达式下标表", 4)
        return ArrayList<Expr?>(n).apply { repeat(n) { add(childOpt(self, cur.u32())) } }
    }

    private fun operandType(wire: Int): OperandType = when (wire) {
        WireEnum.OPERAND_INT -> OperandType.INT
        WireEnum.OPERAND_STR -> OperandType.STR
        else -> throw ErbcDecodeException(0, "未知 OperandType 线值: $wire")
    }

    private fun operatorName(index: Int): OperatorCode {
        val name = strings[index]
        return try {
            OperatorCode.valueOf(name)
        } catch (e: IllegalArgumentException) {
            throw ErbcDecodeException(0, "未知 OperatorCode 名: $name", e)
        }
    }

    private fun sortOrder(wire: Int): SortOrder = when (wire) {
        WireEnum.SORT_UNDEF -> SortOrder.UNDEF
        WireEnum.SORT_ASCENDING -> SortOrder.ASCENDING
        WireEnum.SORT_DESCENDING -> SortOrder.DESENDING
        else -> throw ErbcDecodeException(0, "未知 SortOrder 线值: $wire")
    }

    private fun argValueType(wire: Int): ArgValueType? = when (wire) {
        WireEnum.TYPE_NULL -> null
        WireEnum.TYPE_INT -> ArgValueType.INT
        WireEnum.TYPE_STR -> ArgValueType.STR
        WireEnum.TYPE_VOID -> ArgValueType.VOID
        else -> throw ErbcDecodeException(0, "未知 ArgValueType 线值: $wire")
    }

    // ------------------------------------------------------------------
    // 参数
    // ------------------------------------------------------------------

    /** 真实参数记录（`NONE` 标签只允许出现在 args 由类推导的三处，由 [derivedArgument] 把关）。 */
    fun argument(cur: ErbcCursor): Argument {
        val tag = cur.u8()
        if (tag == ArgTag.NONE) throw ErbcDecodeException(cur.pos - 1, "该语句必须带参数记录，但读到 ArgTag.NONE")
        return readArgument(cur, tag)
    }

    /** args 由类推导的三类（`FuncLabelStmt`/`GotoLabelStmt`/`SharpStmt`）：必须且只能读到 NONE。 */
    fun derivedArgument(cur: ErbcCursor) {
        val tag = cur.u8()
        if (tag != ArgTag.NONE) throw ErbcDecodeException(cur.pos - 1, "该语句的 args 由类推导，不该出现参数记录(tag=$tag)")
    }

    private fun readArgument(
        cur: ErbcCursor,
        tag: Int,
    ): Argument {
        val header = cur.u8()
        if ((header and 0xFC) != 0) throw ErbcDecodeException(cur.pos - 1, "参数头含未定义位: 0x${header.toString(16)}")
        val constStr = if ((header and 2) != 0) str(cur.u32()) else ""
        val constInt = if ((header and 2) != 0) cur.i64() else 0L
        val a: Argument = when (tag) {
            ArgTag.VOID -> VoidArgument()
            ArgTag.EXPRESSION -> ExpressionArgument(childOpt(-1, cur.u32()))
            ArgTag.EXPRESSION_ARRAY -> ExpressionArrayArgument(readChildOptList(cur, -1))
            ArgTag.EXPRESSIONS -> {
                // 每项 = u8 类型 + u32 表达式下标。
                val n = cur.count("ExpressionsArgument 项数", 5)
                val types = ArrayList<ArgValueType?>(n)
                val terms = ArrayList<Expr?>(n)
                repeat(n) {
                    types.add(argValueType(cur.u8()))
                    terms.add(childOpt(-1, cur.u32()))
                }
                ExpressionsArgument(types, terms)
            }

            ArgTag.INT_ASIGN -> IntAsignArgument(str(cur.u32()), intArray(cur), childOpt(-1, cur.u32()))
            ArgTag.STR_ASIGN -> StrAsignArgument(str(cur.u32()), intArray(cur), strOpt(cur.u32()))
            ArgTag.BIT -> BitArgument(varRef(-1, cur.u32()), readChildList(cur, -1))
            ArgTag.CASE -> CaseArgument(readCaseList(cur))
            ArgTag.METHOD -> MethodArgument(childOpt(-1, cur.u32()))
            ArgTag.PRINT_DATA -> PrintDataArgument(varRefOpt(-1, cur.u32()))
            ArgTag.REF -> RefArgument(str(cur.u32()), strOpt(cur.u32()), childOpt(-1, cur.u32()), cur.bool())
            ArgTag.STR_DATA -> StrDataArgument(varRefOpt(-1, cur.u32()))
            ArgTag.SP_ARRAY_CONTROL -> SpArrayControlArgument(
                varRef(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_ARRAY_SHIFT -> SpArrayShiftArgument(
                varRef(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_ARRAY_SORT -> SpArraySortArgument(
                varRef(-1, cur.u32()),
                sortOrder(cur.u8()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_BAR -> SpBarArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_BUTTON -> SpButtonArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_CVAR_SET -> SpCVarSetArgument(
                varRef(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_CALL -> SpCallArgument(childOpt(-1, cur.u32()), readChildOptList(cur, -1), readChildOptList(cur, -1))
            ArgTag.SP_CALLF -> SpCallFArgument(childOpt(-1, cur.u32()), readChildOptList(cur, -1), readChildOptList(cur, -1))
            ArgTag.SP_CALL_SHARP -> SpCallSharpArgument(
                childOpt(-1, cur.u32()),
                readChildOptList(cur, -1),
                readChildOptList(cur, -1),
            )

            ArgTag.SP_COLOR -> {
                val r = childOpt(-1, cur.u32())
                val g = childOpt(-1, cur.u32())
                val b = childOpt(-1, cur.u32())
                val rgb = childOpt(-1, cur.u32())
                if (rgb != null) SpColorArgument(rgb) else SpColorArgument(r, g, b)
            }

            ArgTag.SP_COPY_ARRAY -> SpCopyArrayArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_DT_COLUMN_OPTIONS -> {
                val dt = childOpt(-1, cur.u32())
                val column = childOpt(-1, cur.u32())
                val optionCount = cur.count("DTOption 数", 4)
                val options = ArrayList<SpDtColumnOptions.DTOption>(optionCount)
                repeat(optionCount) {
                    val wire = cur.u8()
                    options.add(
                        when (wire) {
                            WireEnum.DT_DEFAULT -> SpDtColumnOptions.DTOption.DEFAULT
                            else -> throw ErbcDecodeException(cur.pos - 1, "未知 DTOption 线值: $wire")
                        },
                    )
                }
                SpDtColumnOptions(dt, column, options, readChildOptList(cur, -1))
            }

            ArgTag.SP_FOR_NEXT -> SpForNextArgument(
                varRef(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_GET_INT -> SpGetIntArgument(varRef(-1, cur.u32()))
            ArgTag.SP_HTML_PRINT -> SpHtmlPrint(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_HTML_SPLIT -> SpHtmlSplitArgument(childOpt(-1, cur.u32()), str(cur.u32()), varRefOpt(-1, cur.u32()))
            ArgTag.SP_INPUTS -> SpInputsArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_POWER -> SpPowerArgument(varRef(-1, cur.u32()), childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_PRINT_IMG -> SpPrintImgArgument(
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                mixedListOpt(cur),
            )

            ArgTag.SP_PRINT_SHAPE -> SpPrintShapeArgument(mixedList(cur))
            ArgTag.SP_PRINT_V -> SpPrintVArgument(readChildList(cur, -1))
            ArgTag.SP_SAVE_DATA -> SpSaveDataArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_SAVE_VAR -> SpSaveVarArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()), stringList(cur))
            ArgTag.SP_SET -> {
                val dest = varRef(-1, cur.u32())
                val term = childOpt(-1, cur.u32())
                val add = cur.bool()
                SpSetArgument(dest, term).apply { addConst = add }
            }

            ArgTag.SP_SET_ARRAY -> {
                val dest = varRef(-1, cur.u32())
                val terms = readChildOptList(cur, -1)
                val ci = longListOpt(cur)
                val cs = stringListOpt(cur)
                SpSetArrayArgument(dest, terms, ci, cs)
            }

            ArgTag.SP_SORTCHARA -> SpSortcharaArgument(varRef(-1, cur.u32()), sortOrder(cur.u8()))
            ArgTag.SP_SPLIT -> SpSplitArgument(
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                str(cur.u32()),
                varRefOpt(-1, cur.u32()),
            )

            ArgTag.SP_SWAP_CHARA -> SpSwapCharaArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_SWAP_VAR -> SpSwapVarArgument(varRef(-1, cur.u32()), varRef(-1, cur.u32()))
            ArgTag.SP_TINPUTS -> SpTInputsArgument(
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_TIMES -> SpTimesArgument(varRef(-1, cur.u32()), cur.f64())
            ArgTag.SP_VAR_SET -> SpVarSetArgument(
                varRef(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_VARSIZE -> SpVarsizeArgument(str(cur.u32()))
            ArgTag.STR_DOUBLE -> StrDoubleArgument(childOpt(-1, cur.u32()), cur.f64())
            ArgTag.HTML_PRINT_LINE_END -> HtmlPrintArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            else -> throw ErbcDecodeException(cur.pos - 2, "未知参数标签: $tag")
        }
        if ((header and 1) != 0) a.isConst = true
        a.constStr = constStr
        a.constInt = constInt
        return a
    }

    private fun readCaseList(cur: ErbcCursor): List<CaseExpression> {
        // 每条 = u8 类型 + left 4 + right 4 + 运算符名 4。
        val n = cur.count("CASE 分支数", 13)
        return ArrayList<CaseExpression>(n).apply {
            repeat(n) {
                val typeWire = cur.u8()
                val type = when (typeWire) {
                    WireEnum.CASE_NORMAL -> CaseExpression.CaseType.NORMAL
                    WireEnum.CASE_TO -> CaseExpression.CaseType.TO
                    WireEnum.CASE_IS -> CaseExpression.CaseType.IS
                    else -> throw ErbcDecodeException(cur.pos - 1, "未知 CaseType 线值: $typeWire")
                }
                val left = childOpt(-1, cur.u32())
                val right = childOpt(-1, cur.u32())
                add(CaseExpression(type, left, right, operatorName(cur.u32())))
            }
        }
    }

    private fun mixedList(cur: ErbcCursor): List<MixedIntegerExprTerm> {
        // 每项 = u32 表达式下标 + u8 isPx。
        val n = cur.count("px 参数表", 5)
        return ArrayList<MixedIntegerExprTerm>(n).apply {
            repeat(n) { add(MixedIntegerExprTerm(childOpt(-1, cur.u32()), cur.bool())) }
        }
    }

    private fun mixedListOpt(cur: ErbcCursor): List<MixedIntegerExprTerm>? {
        val n = cur.u32()
        if (n == NONE_REF) return null
        if (n < 0 || n.toLong() * 5L > cur.remaining) {
            throw ErbcDecodeException(cur.pos - 4, "px 参数表长度 $n 至少需要 ${n.toLong() * 5L} 字节（剩余 ${cur.remaining}）")
        }
        val out = ArrayList<MixedIntegerExprTerm>(n)
        repeat(n) { out.add(MixedIntegerExprTerm(childOpt(-1, cur.u32()), cur.bool())) }
        return out
    }

    private fun intArray(cur: ErbcCursor): IntArray {
        val n = cur.count("维度表", 4)
        return IntArray(n) { cur.i32() }
    }

    private fun stringList(cur: ErbcCursor): List<String> {
        val n = cur.count("字符串表", 4)
        return ArrayList<String>(n).apply { repeat(n) { add(str(cur.u32())) } }
    }

    private fun stringListOpt(cur: ErbcCursor): Array<String>? {
        val n = cur.u32()
        if (n == NONE_REF) return null
        // 与 ErbcFormat.kt 的 count() 同口径：先按「每项 4 字节」卡上界再分配，
        // 否则畸形产物里一个 0x7FFFFFFE 的计数就能让 Array(n) 抛 OutOfMemoryError ——
        // 那是 Error 不是 RuntimeException，越过 ProgramLoader.kt:100 的收口（:67-68 的承诺）。
        if (n < 0 || n.toLong() * 4L > cur.remaining) {
            throw ErbcDecodeException(cur.pos - 4, "常量字符串列表长度 $n 至少需要 ${n.toLong() * 4L} 字节（剩余 ${cur.remaining}）")
        }
        return Array(n) { str(cur.u32()) }
    }

    private fun longListOpt(cur: ErbcCursor): LongArray? {
        val n = cur.u32()
        if (n == NONE_REF) return null
        // 同上：计数字段先按每项 8 字节卡上界，再进 LongArray(n)。
        if (n < 0 || n.toLong() * 8L > cur.remaining) {
            throw ErbcDecodeException(cur.pos - 4, "常量整数列表长度 $n 至少需要 ${n.toLong() * 8L} 字节（剩余 ${cur.remaining}）")
        }
        return LongArray(n) { cur.i64() }
    }

    // ------------------------------------------------------------------
    // 语句
    // ------------------------------------------------------------------

    fun readStatement(cur: ErbcCursor): Statement {
        val tag = cur.u8()
        val line = cur.i32()
        val raw = str(cur.u32())
        val codeName = str(cur.u32())
        val code = functionCode(codeName)
        val s: Statement = when (tag) {
            StmtTag.PRINT -> PrintStmt(readPrintSpec(cur, code), argument(cur), line, raw)
            StmtTag.ASSIGN -> AssignStmt(
                dest = varRef(-1, cur.u32()),
                op = operatorName(cur.u32()),
                args = argument(cur),
                line = line,
                raw = raw,
            )

            StmtTag.JUMP -> JumpStmt(code, strOpt(cur.u32()), argument(cur), line, raw)
            StmtTag.CALL -> CallStmt(code, strOpt(cur.u32()), argument(cur), line, raw)
            StmtTag.CALL_F -> CallFStmt(functionName = strOpt(cur.u32()), args = argument(cur), line = line, raw = raw, code = code)
            StmtTag.CALL_SHARP -> CallSharpStmt(argument(cur), line, raw)
            StmtTag.METHOD -> MethodStmt(str(cur.u32()), argument(cur), line, raw)
            StmtTag.IF -> {
                // 读序必须与写序逐字一致：condition、single、然后才是参数记录
                val condition = child(-1, cur.u32())
                val single = cur.bool()
                IfStmt(condition, argument(cur), line, raw, single)
            }
            StmtTag.ELSE_IF -> ElseIfStmt(child(-1, cur.u32()), argument(cur), line, raw)
            StmtTag.ELSE -> ElseStmt(argument(cur), line, raw)
            StmtTag.END_IF -> EndIfStmt(argument(cur), line, raw)
            StmtTag.SELECT_CASE -> SelectCaseStmt(child(-1, cur.u32()), argument(cur), line, raw)
            StmtTag.CASE -> CaseStmt(readCaseList(cur), argument(cur), line, raw)
            StmtTag.CASE_ELSE -> CaseElseStmt(argument(cur), line, raw)
            StmtTag.END_SELECT -> EndSelectStmt(argument(cur), line, raw)
            StmtTag.WHILE -> WhileStmt(child(-1, cur.u32()), argument(cur), line, raw)
            StmtTag.WEND -> WendStmt(argument(cur), line, raw)
            StmtTag.DO -> DoStmt(argument(cur), line, raw)
            StmtTag.LOOP -> LoopStmt(childOpt(-1, cur.u32()), argument(cur), line, raw)
            StmtTag.CATCH -> CatchStmt(argument(cur), line, raw)
            StmtTag.END_CATCH -> EndCatchStmt(argument(cur), line, raw)
            StmtTag.RETURN -> ReturnStmt(readChildOptList(cur, -1), argument(cur), line, raw)
            StmtTag.RETURN_F -> ReturnFStmt(value = childOpt(-1, cur.u32()), args = argument(cur), line = line, raw = raw, code = code)
            StmtTag.FUNC_LABEL -> {
                val name = str(cur.u32())
                val params = stringList(cur)
                derivedArgument(cur)
                FuncLabelStmt(name, params, line, raw)
            }

            StmtTag.GOTO_LABEL -> {
                val name = str(cur.u32())
                derivedArgument(cur)
                GotoLabelStmt(name, line, raw)
            }

            StmtTag.SHARP -> {
                val keyword = str(cur.u32())
                val values = longList(cur)
                val name = strOpt(cur.u32())
                val isString = cur.bool()
                derivedArgument(cur)
                SharpStmt(keyword = keyword, values = values, name = name, isString = isString, line = line, raw = raw)
            }

            StmtTag.DATA -> DataStmt(code, str(cur.u32()), argument(cur), line, raw)
            StmtTag.PRINT_DATA -> PrintDataStmt(code, argument(cur), line, raw)
            StmtTag.VAR_DECL -> VarDeclStmt(code, argument(cur), line, raw)
            StmtTag.FOR -> ForStmt(code, argument(cur), line, raw)
            StmtTag.NEXT -> NextStmt(argument(cur), line, raw, code)
            StmtTag.REPEAT -> RepeatStmt(argument(cur), line, raw)
            StmtTag.REND -> RendStmt(argument(cur), line, raw)
            StmtTag.DATA_LIST -> DataListStmt(code, argument(cur), line, raw)
            StmtTag.END_BLOCK -> EndBlockStmt(code, argument(cur), line, raw)
            StmtTag.FLOW -> FlowStmt(code, argument(cur), line, raw)
            StmtTag.INSTRUCTION -> InstructionStmt(code, argument(cur), line, raw)
            else -> throw ErbcDecodeException(cur.pos - 1, "未知语句类标签: $tag")
        }
        // 类表与语句身份的一致性校验（派生 code 的类靠这一步兜住）
        if (s.code.name != codeName) {
            throw ErbcDecodeException(0, "语句 ${s.javaClass.simpleName} 的 code(${s.code.name}) 与记录里的 $codeName 不符")
        }
        if (s.raw != raw) {
            throw ErbcDecodeException(0, "语句 ${s.javaClass.simpleName} 的 raw 与记录不一致")
        }
        return s
    }

    private fun longList(cur: ErbcCursor): List<Long> {
        val n = cur.count("#行数值表", 8)
        return ArrayList<Long>(n).apply { repeat(n) { add(cur.i64()) } }
    }

    private fun readPrintSpec(
        cur: ErbcCursor,
        code: FunctionCode,
    ): PrintSpec {
        val argTypeName = str(cur.u32())
        val argType = try {
            ArgType.valueOf(argTypeName)
        } catch (e: IllegalArgumentException) {
            throw ErbcDecodeException(0, "未知 ArgType 名: $argTypeName", e)
        }
        val bits = cur.u16()
        val flag = cur.i32()
        return PrintSpec(
            code = code,
            name = code.name,
            argType = argType,
            isLineEnd = (bits and 0x001) != 0,
            isSingleLine = (bits and 0x002) != 0,
            isPrintV = (bits and 0x004) != 0,
            isS = (bits and 0x008) != 0,
            isForms = (bits and 0x010) != 0,
            isC = (bits and 0x020) != 0,
            isLC = (bits and 0x040) != 0,
            isK = (bits and 0x080) != 0,
            isD = (bits and 0x100) != 0,
            isN = (bits and 0x200) != 0,
            flag = flag,
        )
    }

    private fun functionCode(name: String): FunctionCode = try {
        FunctionCode.valueOf(name)
    } catch (e: IllegalArgumentException) {
        throw ErbcDecodeException(0, "未知 FunctionCode 名: $name", e)
    }
}
