package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.langCodecOf
import com.eratw.port.core.expr.strLenLang
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputRequest.InputType
import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.fn.BuiltinContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.ColorNames
import com.eratw.port.core.fn.EmDataPools
import com.eratw.port.core.fn.NativeParam
import com.eratw.port.core.fn.SP_CHARA_CONFIG_IS_OFF
import com.eratw.port.core.fn.dtColumnOptionsDefault
import com.eratw.port.core.fn.nativeMethods
import com.eratw.port.core.fn.spCharaEnabled
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.BitArgument
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.SpDtColumnOptions
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.ExpressionsArgument
import com.eratw.port.core.stmt.FlowStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.IntAsignArgument
import com.eratw.port.core.stmt.MethodArgument
import com.eratw.port.core.stmt.PrintDataArgument
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.ReturnFStmt
import com.eratw.port.core.stmt.ReturnStmt
import com.eratw.port.core.stmt.SortOrder
import com.eratw.port.core.stmt.SpArrayControlArgument
import com.eratw.port.core.stmt.SpArrayShiftArgument
import com.eratw.port.core.stmt.SpArraySortArgument
import com.eratw.port.core.stmt.SpBarArgument
import com.eratw.port.core.stmt.SpCallSharpArgument
import com.eratw.port.core.stmt.SpButtonArgument
import com.eratw.port.core.stmt.SpColorArgument
import com.eratw.port.core.stmt.SpCopyArrayArgument
import com.eratw.port.core.stmt.SpCVarSetArgument
import com.eratw.port.core.stmt.SpGetIntArgument
import com.eratw.port.core.stmt.SpHtmlPrint
import com.eratw.port.core.stmt.SpHtmlSplitArgument
import com.eratw.port.core.stmt.SpInputsArgument
import com.eratw.port.core.stmt.SpPowerArgument
import com.eratw.port.core.stmt.SpPrintImgArgument
import com.eratw.port.core.stmt.SpPrintShapeArgument
import com.eratw.port.core.stmt.SpPrintVArgument
import com.eratw.port.core.stmt.SpSaveDataArgument
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SpSetArrayArgument
import com.eratw.port.core.stmt.SpSortcharaArgument
import com.eratw.port.core.stmt.SpSplitArgument
import com.eratw.port.core.stmt.SpSwapCharaArgument
import com.eratw.port.core.stmt.SpSwapVarArgument
import com.eratw.port.core.stmt.SpTInputsArgument
import com.eratw.port.core.stmt.SpTimesArgument
import com.eratw.port.core.stmt.SpVarSetArgument
import com.eratw.port.core.stmt.SpVarsizeArgument
import com.eratw.port.core.stmt.StrAsignArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.StrDataArgument
import com.eratw.port.core.stmt.VarDeclStmt
import com.eratw.port.core.save.SaveWiring
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.sem.FlatArgs
import com.eratw.port.core.sem.FlatStmtSource

/**
 * PICKUPCHARA 实参判界的豁免名单：实参本身是这三个变量之一的引用时不判界
 * （Process.cs:1277-1280，`Identifier.Code` 按 VariableCode 比；本侧变量名大小写
 * 不敏感口径对齐全局解析，且用户变量不可能与系统变量重名 —— declareUserVar 拒绝）。
 * 注意豁免的是**变量引用**而非取值：取 -1（未设置）是合法输入，交 PickUpChara 跳过。
 */
private val PICKUP_CHARA_EXEMPT_VARS = setOf("MASTER", "ASSI", "TARGET")

/** 语句执行结果。控制流（CALL/GOTO/循环）由编译后的 VM 指令处理，不在此层。 */
sealed class ExecResult {
    object Normal : ExecResult()

    /** QUIT 家族。[restart]=QUIT_AND_RESTART；[force]=FORCE_QUIT。spec: Process.cs:1307-1320 */
    data class Quit(val restart: Boolean = false, val force: Boolean = false) : ExecResult()

    /**
     * BEGIN/FORCE_BEGIN：切换 BEGIN 入口（执行流切换由 proc 运行时完成）。
     *
     * `FORCE_BEGIN` **不是** `BEGIN` 的别名，也没有任何额外行为 —— 主源裁定：
     * `BEGIN_Instruction.DoInstruction`（spec: `FunctionIdentifier.cs:2930-2936`）与
     * `FORCE_BEGIN_Instruction.DoInstruction`（`:2947-2953`）**方法体逐句相同**
     * （`state.SetBegin(constStr, force: true)` → `state.Return(0L)` → `Console.ResetStyle()`），
     * 两个实现类各自独立注册（`:3950` / `:4111`），`flag` 都是 1（`:2927` / `:2944`）。
     * `SetBegin` 的 `force` 形参只服务于 `__CAN_BEGIN__` 守卫
     * （spec: `ProcessState.cs:169-188`，非 force 且状态位不齐全时抛 CanNotUseInstruction），
     * 而两条语句**都**硬编 `force: true` ⇒ 该守卫对二者皆不可达，"强制"只存在于名字里。
     * `isa.yaml:1682-1698` 写的「当前实现行为一致」据此确认，不做「顺手统一」也不臆造差异。
     * 结论：本层单条分派 + 同一 [Begin] 载荷即完备，不需要 force 位（`proc/BeginType.kt:52-56` 同源）。
     *
     * 缺口已收口（M4-I i-consolereset）：原版第 3 句 `Console.ResetStyle()` 对应 `host/ConsoleSink.resetStyle()`，
     * 调用点在 `proc/ProcessRuntime.kt` 消费 [Begin] 的分支里（SetBegin 成功之后、挂起入口之前）。
     */
    data class Begin(val target: String) : ExecResult()

    /** RETURN/RETURNFORM：RESULT 数组已写入，[value] = RESULT:0。 */
    data class Return(val value: Long) : ExecResult()
}

/**
 * 语句执行器 —— 169 条语句的运行期语义（原版 Runtime.Script.Statements 各 *_Instruction
 * 的 DoInstruction + Process.doNormalFunction 分派的移植）。
 *
 * 纪律（team-lead 分工 2026-10-17）：
 * - 控制流语句（IF/SELECTCASE/FOR/WHILE/CALL/GOTO/…）已由编译器降为 _JUMP/_CALL，不在此实现；
 *   块标记语句（ENDIF/CASE/NEXT 等）运行期无行为，按 no-op 处理。
 * - 表达式内建函数归 m3-builtins；本层只经 [ExecContext.eval] 调用表达式求值。
 * - 错误文案取自原版 Lang 资源 zhs 版（spec: MinorShift.Emuera.Properties.lang.emuera-zhs.xml）。
 * - 原版怪行为按 ADR-005 原样复刻；已在怪行为处就地注明。
 */
class StatementExecutor {

    /**
     * 累计跨越的**让出边界**次数（M10-RUNB · M10-AWAIT）：两类落点各 +1 ——
     * ① [waitInput] / [readAnyKey] 从一次输入等待返回；② `AWAIT` 执行完一次帧让出。
     * VM 主循环经 `VmHost.yieldBoundaryCount`（proc 的 ProcessRuntime 转发本值）轮询它，
     * 读数变化即把死循环保护的段落计数从 0 重新起算。
     *
     * 口径逐条对着原版：
     * - 输入类：原版 `WAIT`/`TWAIT` 不睡线程，而是置 `ConsoleState.WaitInput` 后**结束整个
     *   turn**（`FunctionIdentifier.cs:781/:810-823` → `EmueraConsole.cs:893`，退出条件
     *   `Process.cs:1081`），下一轮从 `DoScript` 重新进来时清 `lineCount`
     *   （spec: Process.cs:438-441/:458-470）。本移植等待是阻塞式，故边界挪到等待返回处。
     * - `AWAIT`：原版 `EmueraConsole.Await`（`:840-856`）依次做「强制重绘 → `state = Sleep`
     *   → **`process.UpdateCheckInfiniteLoopState()`** → `Application.DoEvents()` →
     *   `Thread.Sleep(time)`」，而 `UpdateCheckInfiniteLoopState()`
     *   （`Process.cs:487-491`）**在全工程里只有这一个调用点** —— `AWAIT` 就是原版唯一的
     *   循环保护复位点。
     * - 原版没有每-run 指令上限：只有「脚本行 + 墙钟毫秒」的软判据
     *   （`Process.cs:1012` 每 10,000 行采样，`:495-496` 与 `Config.InfiniteLoopAlertTime`
     *   比较，默认 5000 ms、0 = 关闭，`ConfigData.cs:75`），触发后弹框问用户、选继续就清零
     *   （`:505-510`），不是抛错。
     *
     * 因此**不把 `REDRAW`/`PRINT`/`GFXUPDATE` 计为边界** —— 原版在这些地方既不交还控制台也不
     * 复位（`REDRAW` 只置字段，`Process.cs:1553-1555`；`PrintFlush` 在 `DoScript` 之后才跑，
     * `EmueraConsole.cs:1107`），加了就会让我们在原版照样会报的地方不报。
     * 阈值数值不变；不跨越任何边界的脚本闸行为与改造前一致。
     *
     * 实例字段（非静态可变全局，AGENTS.md 铁律 4）；只有 VM 线程读写（ADR-003 单线程）。
     */
    var yieldBoundaryCount: Long = 0L
        private set

    /**
     * 旧对象树入口（T2-64 波次 C 起**仅供双路对拍装置对照**；运行期主路径走
     * [execute] 的扁平直读）。语义、KDoc 与 `spec:` 出处全部原样保留 —— 它是「直读复刻是否
     * 逐字一致」的唯一参照系。收尾提交由负责人整批删除（连同本文件里同标记的 `execXxx` 一族）。
     */
    @Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    fun execute(stmt: Statement, ctx: ExecContext): ExecResult = when (stmt) {
        is PrintStmt -> {
            execPrint(stmt, ctx); ExecResult.Normal
        }
        is AssignStmt -> {
            execAssign(stmt, ctx); ExecResult.Normal
        }
        is VarDeclStmt -> {
            execVarDecl(stmt, ctx); ExecResult.Normal
        }
        is DataStmt -> {
            // STRDATA 与 DATA/DATAFORM/DATALIST 同为 DataStmt（LineParser.kt:322-324），
            // 但 STRDATA 有运行期行为（Process.cs:1801-1829）；其余由 PRINTDATA 消费，自身无行为。
            if (stmt.code == FunctionCode.STRDATA) execStrData(stmt, ctx)
            ExecResult.Normal
        }
        is FlowStmt -> execFlow(stmt.code, ctx)
        is ReturnStmt -> execReturn(stmt, ctx)  // 编译器降为 marker（契约 R10 API 1）
        is ReturnFStmt ->
            // RETURNF 已由编译器编成 _RET，不会到 sem；保留 Normal 兜底。
            if (stmt.code == FunctionCode.RETURNFORM) execReturnForm(stmt, ctx) else ExecResult.Normal
        is InstructionStmt -> execInstruction(stmt.code, stmt, ctx)
        // 块结构 / 标签 / 预处理 / SIF / IF 等：运行期无独立行为（编译期已降级）
        else -> ExecResult.Normal
    }

    // ==================================================================
    // T2-64 波次 C：扁平池全量直读主入口（旧 execute(stmt, ctx) 仅供双路对拍，收尾删除）
    // ==================================================================

    /**
     * 语句执行主入口（扁平池通道，契约 T2-64 §3.3）——**波次 C：37 个 `StmtTag` 全量直读**。
     *
     * 外层按**语句类标签**（参数流槽 0，与 encode 的 `StmtTag` 同值）tableswitch；`op`（槽 1）
     * 只在「族内共享同一记录布局」时做第二级 `when`。为什么外层是 tag 而不是 op：PRINT 家族是
     * 77 个 FunctionCode（`ArgType.PRINT` 全集）共享同一个 `StmtTag.PRINT` 记录形态，tag 才是
     * 记录布局的权威（编码器按语句类写 tag）；枚举 77 个 op 分支只会更难对齐。
     *
     * 记录形态（[com.eratw.port.core.code.FlatStatements.encode] 逐字对偶）：
     *
     * ```text
     * 槽 0 = StmtTag，槽 1 = FunctionCode.ordinal，槽 2.. = 语句类专属字段，
     * 之后 = 参数记录 [ArgTag][header][载荷 3 槽（header bit1 时）][形态字段...]
     * ```
     *
     * 语句类专属字段按**固定槽位**从 [FlatStmtSource.argStreamBase] 直读（每个 tag 的宽度恒定）；
     * 三个变长专属字段的 tag（CASE 的 caseList、FUNC_LABEL 的 paramNames、SHARP 的 values）
     * 运行期无行为，不读记录。参数记录一律经 [FlatArgs] 按 encode 顺序读，槽位偏移不再手写。
     *
     * **形态校验统一口径**：记录里的 ArgTag 与 `FunctionCode.argType` 的约定不符时抛
     * [com.eratw.port.core.code.ErbcDecodeException]（[openTag] / [argTerm] / [argTerms] 一族）。
     * 理由：那是「语句池记录与 op 不自洽」的**容器级**错误，不是脚本错误 —— `CodeEE` 是给 ERB
     * 作者看的运行期语义错误（proc 桥会补位置章与行号），形态错没有可归因的脚本行；
     * 静默错读槽位是这个口径要排除的唯一结果。旧树路径没有这一支（Argument 已是具体类）。
     *
     * **不再有兜底支**：本入口不调用 [FlatStmtSource.statementAt]，左右两支之外没有第三支。
     * 语义逐字照抄对应 `execXxx`（旧入口 [execute] 原样保留到收尾提交删除，供双路对拍装置对照）。
     */
    fun execute(source: FlatStmtSource, index: Int, ctx: ExecContext): ExecResult {
        // T2-64 波次 D：分片惰性源要求**先** headAt（定位到该语句所在的片）再取数组；
        // 反过来会拿到上一片的数组（整池形态不会暴露这个问题）。
        val head = source.headAt(index)
        val stream = source.argStreamBase()
        val tag = stream[head]
        val op = stream[head + 1]
        val codeCount = FunctionCode.entries.size
        if (op < 0 || op >= codeCount) {
            throw com.eratw.port.core.code.ErbcDecodeException(
                head + 1,
                "扁平语句流第 $index 条的 op 越界: $op（共 $codeCount 个 FunctionCode）",
            )
        }
        return when (tag) {
            // ---- 有专属语义 + 专属参数记录的语句类 ----
            ErbcFormat.StmtTag.PRINT -> execPrintFlat(source, index, ctx)
            ErbcFormat.StmtTag.ASSIGN -> execSetFlat(source, index, ctx)
            ErbcFormat.StmtTag.PRINT_DATA -> execPrintDataFlat(source, index, FunctionCode.entries[op], ctx)
            ErbcFormat.StmtTag.VAR_DECL -> execVarDeclFlat(source, index, FunctionCode.entries[op], ctx)
            ErbcFormat.StmtTag.DATA ->
                if (op == FunctionCode.STRDATA.ordinal) execStrDataFlat(source, index, ctx) else ExecResult.Normal
            ErbcFormat.StmtTag.FLOW -> execFlow(FunctionCode.entries[op], ctx)
            ErbcFormat.StmtTag.RETURN -> execReturnFlat(source, index, ctx)
            ErbcFormat.StmtTag.RETURN_F ->
                if (op == FunctionCode.RETURNFORM.ordinal) execReturnFormFlat(source, index, ctx)
                else ExecResult.Normal

            // ---- 语句类专属字段 0 槽：参数记录紧跟 [tag][op] ----
            ErbcFormat.StmtTag.INSTRUCTION, ErbcFormat.StmtTag.CALL_SHARP ->
                execInstructionFlat(op, FlatArgs(source, head + 2), ctx, source, index)

            // ---- 专属字段 1 槽：参数记录从 head+3 起 ----
            ErbcFormat.StmtTag.METHOD, ErbcFormat.StmtTag.JUMP, ErbcFormat.StmtTag.CALL,
            ErbcFormat.StmtTag.CALL_F, ErbcFormat.StmtTag.IF, ErbcFormat.StmtTag.ELSE_IF,
            ErbcFormat.StmtTag.SELECT_CASE, ErbcFormat.StmtTag.WHILE, ErbcFormat.StmtTag.LOOP,
            -> execInstructionFlat(op, FlatArgs(source, head + 3), ctx, source, index)

            // ---- 块标记 / 标签类：运行期无行为（旧路径同样落 execInstruction 的 no-op 分支）----
            ErbcFormat.StmtTag.ELSE, ErbcFormat.StmtTag.END_IF, ErbcFormat.StmtTag.CASE_ELSE,
            ErbcFormat.StmtTag.END_SELECT, ErbcFormat.StmtTag.FOR, ErbcFormat.StmtTag.NEXT,
            ErbcFormat.StmtTag.REPEAT, ErbcFormat.StmtTag.REND, ErbcFormat.StmtTag.WEND,
            ErbcFormat.StmtTag.DO, ErbcFormat.StmtTag.CATCH, ErbcFormat.StmtTag.END_CATCH,
            ErbcFormat.StmtTag.DATA_LIST, ErbcFormat.StmtTag.END_BLOCK,
            -> execInstructionFlat(op, FlatArgs(source, head + 2), ctx, source, index)

            // 变长专属字段（caseList / paramNames / values）+ 运行期无行为：不读记录（见方法 KDoc）
            ErbcFormat.StmtTag.CASE, ErbcFormat.StmtTag.FUNC_LABEL, ErbcFormat.StmtTag.GOTO_LABEL,
            ErbcFormat.StmtTag.SHARP,
            -> ExecResult.Normal

            else -> throw com.eratw.port.core.code.ErbcDecodeException(head, "扁平语句流未知语句类标签: $tag")
        }
    }

    // ==================================================================
    // T2-64 波次 C：INSTRUCTION 族的 op 二级分派（[execInstruction] 的直读复刻）
    // ==================================================================

    /**
     * INSTRUCTION / CALL_SHARP / METHOD / 块标记类语句的 op 二级分派 —— 旧 [execInstruction] 的
     * 直读复刻：同一份 123 例 `when`、同一份 no-op 名单、同一句未实现文案。
     *
     * [a] 构造在**参数记录起点**（槽 0 = ArgTag），由各支按 encode 顺序消费。无参语句
     * （`ArgType.VOID`）不碰记录；块标记语句的 op 全部落在 no-op 名单里，故也不读。
     */
    private fun execInstructionFlat(
        op: Int,
        a: FlatArgs,
        ctx: ExecContext,
        source: FlatStmtSource,
        index: Int,
    ): ExecResult {
        val code = FunctionCode.entries[op]
        return when (code) {
            // PRINTDATA 家族：正常语料里是 PrintDataStmt（`StmtTag.PRINT_DATA`，外层已分派到这里），
            // 但「generic InstructionStmt + PRINTDATA op」在旧 `execInstruction` 里也有对应分支、
            // 同样能跑。直读侧必须同容错，否则同一形状两路分叉（双路对拍装置正是用这种构造钉等价）。
            // 两条 tag 的记录布局一致（`[tag][op][argument]`），直接复用同一支。
            FunctionCode.PRINTDATA, FunctionCode.PRINTDATAL, FunctionCode.PRINTDATAW,
            FunctionCode.PRINTDATAK, FunctionCode.PRINTDATAKL, FunctionCode.PRINTDATAKW,
            FunctionCode.PRINTDATAD, FunctionCode.PRINTDATADL, FunctionCode.PRINTDATADW,
            -> execPrintDataFlat(source, index, code, ctx)
            // ---- 显示 ----
            FunctionCode.PRINTBUTTON, FunctionCode.PRINTBUTTONC, FunctionCode.PRINTBUTTONLC ->
                execPrintButtonFlat(code, a, ctx)
            FunctionCode.PRINTPLAIN, FunctionCode.PRINTPLAINFORM -> {
                // 原版求值前显式复位两条开关（spec: Process.cs:1149-1150），清除可能残留的 D 系窗口。
                val term = argTerm(a, code)
                if (!ctx.skipPrint) {
                    ctx.console.setUseUserStyle(true)
                    ctx.console.setUseSetColorStyle(true)
                    ctx.console.printPlain(evalStr(term, ctx))
                }
                ExecResult.Normal
            }
            FunctionCode.DRAWLINE -> {
                if (!ctx.skipPrint) { // spec: Process.cs:1155-1161 —— PrintBar + NewLine
                    ctx.console.printBar(); ctx.console.newLine()
                }
                ExecResult.Normal
            }
            FunctionCode.DRAWLINEFORM, FunctionCode.CUSTOMDRAWLINE -> {
                // spec: Process.cs:1162-1170 / FunctionIdentifier.cs:506-538 —— printCustomBar + NewLine
                val term = argTerm(a, code)
                if (!ctx.skipPrint) {
                    ctx.console.printCustomBar(evalStr(term, ctx)); ctx.console.newLine()
                }
                ExecResult.Normal
            }
            FunctionCode.PRINT_ABL, FunctionCode.PRINT_TALENT, FunctionCode.PRINT_MARK, FunctionCode.PRINT_EXP -> {
                // spec: Process.cs:1171-1181 —— 取角色位置参数，GetCharacterDataString 一次 Print + NewLine；
                // skipPrint 时整条抑制（连 NewLine 一起跳，Process.cs:1175 的 if (!skipPrint) 包住两步）。
                val term = argTerm(a, code)
                if (!ctx.skipPrint) {
                    val target = term?.getIntValue(ctx.eval) ?: 0L
                    ctx.console.print(PrintCharaData.characterDataString(ctx, target, code))
                    ctx.console.newLine()
                }
                ExecResult.Normal
            }
            FunctionCode.PRINT_PALAM -> {
                // spec: Process.cs:1182-1206 —— 0..99 逐项 GetCharacterParamString，非 null 项
                // PrintC（右对齐），每 PrintCPerLine 项 PrintFlush(force:false) 分行，收尾再 flush 一次。
                val term = argTerm(a, code)
                if (!ctx.skipPrint) {
                    val target = term?.getIntValue(ctx.eval) ?: 0L
                    val perLine = ctx.config.printCPerLine
                    var printed = 0
                    for (i in 0 until 100) { // 硬编码 100：spec: Process.cs:1190
                        val cell = PrintCharaData.characterParamString(ctx, target, i) ?: continue
                        ctx.console.printC(cell, alignRight = true) // spec: Process.cs:1195（alignmentRight: true）
                        printed++
                        if (perLine > 0 && printed % perLine == 0) { // spec: Process.cs:1197-1200
                            // PrintFlush(force:false) 在缓冲非空时与 NewLine 等价（Print 都已入缓冲、
                            // lineEnd=true）；此处仅在 printed>0 时触发，缓冲必非空。
                            ctx.console.newLine()
                        }
                    }
                    // 收尾 PrintFlush(force:false)（spec: Process.cs:1203）：缓冲空（printed==0，或
                    // printed 恰为 perLine 整倍数已被上面的分行 flush 清空）时原版为 no-op，同理不补行。
                    val dirty = if (perLine > 0) printed % perLine != 0 else printed > 0
                    if (dirty) ctx.console.newLine()
                    // RefreshStrings(force_Paint:false)（Process.cs:1204）是重绘动作，不入文本流，不移植。
                }
                ExecResult.Normal
            }
            FunctionCode.REUSELASTLINE -> {
                val term = argTerm(a, code)
                ctx.console.printTemporaryLine(evalStr(term, ctx)); ExecResult.Normal // spec: FunctionIdentifier.cs:668-681
            }
            FunctionCode.CLEARLINE -> {
                val term = argTerm(a, code)
                ctx.console.deleteLine((term?.getIntValue(ctx.eval) ?: 0L).toInt()); ExecResult.Normal // spec: FunctionIdentifier.cs:683-697
            }
            FunctionCode.BAR, FunctionCode.BARL -> {
                // spec: FunctionIdentifier.cs:1289-1312 + ExpressionMediator.cs:130-159 CreateBar
                openTag(a, code, ErbcFormat.ArgTag.SP_BAR)
                val v = a.expr()?.getIntValue(ctx.eval) ?: 0L
                val max = a.expr()?.getIntValue(ctx.eval) ?: 0L
                val len = a.expr()?.getIntValue(ctx.eval) ?: 0L
                ctx.console.print(createBar(v, max, len))
                if (code == FunctionCode.BARL) ctx.console.newLine()
                ExecResult.Normal
            }
            FunctionCode.SETCOLOR -> {
                val (r, g, b) = evalColorFlat(a, code, ctx)
                ctx.console.setForeColor(r, g, b); ExecResult.Normal
            }
            FunctionCode.SETBGCOLOR -> {
                val (r, g, b) = evalColorFlat(a, code, ctx)
                ctx.console.setBackColor(r, g, b); ExecResult.Normal
            }
            FunctionCode.SETCOLORBYNAME -> {
                val (r, g, b) = colorByName(evalStr(argTerm(a, code), ctx))
                ctx.console.setForeColor(r, g, b); ExecResult.Normal
            }
            FunctionCode.SETBGCOLORBYNAME -> {
                val (r, g, b) = colorByName(evalStr(argTerm(a, code), ctx))
                ctx.console.setBackColor(r, g, b); ExecResult.Normal
            }
            FunctionCode.RESETCOLOR -> {
                ctx.console.resetForeColor(); ExecResult.Normal // spec: FunctionIdentifier.cs:1491-1503
            }
            FunctionCode.RESETBGCOLOR -> {
                ctx.console.resetBackColor(); ExecResult.Normal // spec: FunctionIdentifier.cs:1505-1517
            }
            FunctionCode.FONTBOLD -> {
                // spec: FunctionIdentifier.cs:1574-1589——StringStyle.FontStyle | Bold（OR 语义，
                // 与 FONTITALIC 叠加得 3；PC 基准 STYLE=1,3 锁死，不得改成绝对置值）
                ctx.console.setFontStyle(ctx.console.fontStyle or 1); ExecResult.Normal
            }
            FunctionCode.FONTITALIC -> {
                // spec: FunctionIdentifier.cs:1591-1606——FontStyle | Italic
                ctx.console.setFontStyle(ctx.console.fontStyle or 2); ExecResult.Normal
            }
            FunctionCode.FONTREGULAR -> {
                ctx.console.setFontStyle(0); ExecResult.Normal // spec: FunctionIdentifier.cs:1608-1623
            }
            FunctionCode.FONTSTYLE -> {
                val term = argTerm(a, code)
                ctx.console.setFontStyle((term?.getIntValue(ctx.eval) ?: 0L).toInt()); ExecResult.Normal // spec: Process.cs:1508-1530
            }
            FunctionCode.SETFONT -> {
                val term = argTerm(a, code)
                ctx.console.setFont(evalStr(term, ctx)); ExecResult.Normal // spec: Process.cs:1531-1534
            }
            FunctionCode.ALIGNMENT -> {
                val s = evalStr(argTerm(a, code), ctx)
                val kind = when (s.uppercase()) {
                    "LEFT" -> Alignment.LEFT
                    "CENTER" -> Alignment.CENTER
                    "RIGHT" -> Alignment.RIGHT
                    else -> throw CodeEE("没有定义ALIGNMENT的关键字\"$s\"") // spec: Lang InvalidAlignment
                }
                ctx.console.setAlignment(kind); ExecResult.Normal // spec: Process.cs:1535-1552
            }
            FunctionCode.REDRAW -> {
                val term = argTerm(a, code)
                ctx.console.setRedraw((term?.getIntValue(ctx.eval) ?: 0L).toInt()); ExecResult.Normal // spec: Process.cs:1553-1556
            }

            // ---- 随机数状态（RANDOMIZE/INITRAND/DUMPRAND）----
            // spec: FunctionIdentifier.cs:1734-1799 + VariableEvaluator.cs:217-230。
            // UseNewRandom=true 时原版仅告警不动作（本游戏 setting.json UseNewRandom=false，
            // 走经典 MTRandom 通道；RANDOMIZE 省略参数按 0 处理——ArgumentParser.cs:1194-1196）。
            FunctionCode.RANDOMIZE -> {
                val seed = argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L
                if (!ctx.config.useNewRandom) {
                    val rng = ctx.variables.randProvider
                        ?: throw CodeEE("RANDOMIZE 需要注入 RandProvider（ADR-001）")
                    rng.reseed(seed) // 原版新建 MTRandom(seed) ≡ init_gen_rand + idx=624
                }
                ExecResult.Normal
            }
            FunctionCode.DUMPRAND -> {
                if (!ctx.config.useNewRandom) {
                    val rng = ctx.variables.randProvider
                        ?: throw CodeEE("DUMPRAND 需要注入 RandProvider（ADR-001）")
                    val st = rng.state() // GetRand(RANDDATA)：MTRandom.cs:69-80
                    val len = ctx.variables.getLength(RANDDATA, -1)
                    for (i in 0 until minOf(st.size, len)) {
                        ctx.variables.writeInt(RANDDATA, longArrayOf(i.toLong()), st[i])
                    }
                }
                ExecResult.Normal
            }
            FunctionCode.INITRAND -> {
                if (!ctx.config.useNewRandom) {
                    val rng = ctx.variables.randProvider
                        ?: throw CodeEE("INITRAND 需要注入 RandProvider（ADR-001）")
                    val len = ctx.variables.getLength(RANDDATA, -1)
                    val data = LongArray(len) { ctx.variables.readInt(RANDDATA, longArrayOf(it.toLong())) }
                    rng.loadState(data) // SetRand(RANDDATA)：MTRandom.cs:56-67
                }
                ExecResult.Normal
            }
            FunctionCode.HTML_PRINT -> {
                openTag(a, code, ErbcFormat.ArgTag.SP_HTML_PRINT)
                val strTerm = a.expr()
                val optTerm = a.expr()
                if (!ctx.skipPrint) {
                    val opt = optTerm?.getIntValue(ctx.eval) ?: 0L
                    ctx.console.printHtml(evalStr(strTerm, ctx), opt != 0L)
                }
                ExecResult.Normal
            }
            FunctionCode.HTML_PRINT_ISLAND -> {
                openTag(a, code, ErbcFormat.ArgTag.SP_HTML_PRINT)
                val strTerm = a.expr()
                a.expr() // opt 槽（本语句不消费，仍按 encode 顺序跳过）
                if (!ctx.skipPrint) ctx.console.addHtmlIsland(evalStr(strTerm, ctx))
                ExecResult.Normal
            }
            FunctionCode.HTML_PRINT_ISLAND_CLEAR -> {
                ctx.console.clearHtmlIslands(); ExecResult.Normal
            }
            FunctionCode.HTML_TAGSPLIT -> {
                execHtmlTagSplitFlat(a, code, ctx); ExecResult.Normal
            }
            FunctionCode.PRINT_IMG -> execPrintImgFlat(source, index, code, ctx)
            FunctionCode.PRINT_RECT, FunctionCode.PRINT_SPACE -> {
                openTag(a, code, ErbcFormat.ArgTag.SP_PRINT_SHAPE)
                val cell = a.mixedList()
                if (!ctx.skipPrint) {
                    val params = cell.map { (it.num?.getIntValue(ctx.eval) ?: 0L) to it.isPx }
                    // 原版按执行时点的 userStyle.Color 取色（EmueraConsole.cs:3197-3199），
                    // 未 SETCOLOR 时回落 Config.ForeColor = 192,192,192（ConfigData.cs:68）；
                    // ConsoleSink.foregroundColor 为 null 正是「未显式设置」（ConsoleSink.kt:248-253）。
                    if (code == FunctionCode.PRINT_RECT) {
                        ctx.graphics.printRect(params, ctx.console.foregroundColor ?: 0xC0C0C0)
                    } else {
                        ctx.graphics.printSpace(params)
                    }
                }
                ExecResult.Normal
            }
            FunctionCode.SETBGIMAGE -> {
                // spec: FunctionIdentifier.cs:1519-1543（FORM_STR_ANY：文件名[,深度,不透明度]）
                // TODO(verify): 原版 depth/opacity 是 `long.Parse(Term.GetStrValue(exm))`
                // （:1535/:1539 —— 先按字符串求值再十进制解析，非数字串抛 FormatException），
                // 本实现按整数表达式求值（getIntValue）。裸整数两口径同值；带引号数字串
                // （"5"）与非数值串的报错形态有差。语料未出现这两种写法，先保持现状，
                // 改动需连带 ArgBuilders 的 FORM_STR_ANY 求值口径一起核对。
                val terms = argTerms(a, code)
                ctx.graphics.setBgImage(
                    name = terms.getOrNull(0)?.let { evalStr(it, ctx) } ?: "",
                    depth = terms.getOrNull(1)?.let { it.getIntValue(ctx.eval) },
                    opacity = terms.getOrNull(2)?.let { it.getIntValue(ctx.eval) },
                )
                ExecResult.Normal
            }
            FunctionCode.REMOVEBGIMAGE -> {
                val terms = argTerms(a, code)
                ctx.graphics.removeBgImage(terms.getOrNull(0)?.let { evalStr(it, ctx) } ?: "")
                ExecResult.Normal
            }
            FunctionCode.CLEARBGIMAGE -> {
                ctx.graphics.clearBgImages(); ExecResult.Normal
            }
            FunctionCode.CLEARTEXTBOX -> {
                ctx.console.clearText(); ExecResult.Normal // spec: Process.cs:1798-1800
            }

            // ---- 输入 ----
            FunctionCode.WAIT -> {
                // MesSkip 中 WAIT 由输入循环直接跑过（EmueraConsole.cs:1451-1455：NeedValue=false
                // 且未 StopMesskip 时 while 继续），不发起等待。
                // 原版输入循环吞掉无值等待后无条件 MesSkip = false（EmueraConsole.cs:1456）——
                // 不清标志会让下一个 INPUT(canSkip) 快路径静默写默认值（2026-10-26 fixcore 审查 C4）。
                if (ctx.mesSkip) ctx.mesSkip = false else readAnyKey(ctx); ExecResult.Normal // spec: FunctionIdentifier.cs:762-784
            }
            FunctionCode.FORCEWAIT -> {
                readAnyKey(ctx, anyKey = false, stopMesSkip = true); ExecResult.Normal
            }
            FunctionCode.WAITANYKEY -> {
                // 同 C4：吞掉后无条件清 MesSkip（EmueraConsole.cs:1456）。
                if (ctx.mesSkip) ctx.mesSkip = false else readAnyKey(ctx, anyKey = true); ExecResult.Normal // spec: FunctionIdentifier.cs:786-798
            }
            FunctionCode.TWAIT -> {
                /*
                 * spec: FunctionIdentifier.cs:808-824。原文明明写着两次调用，但只有一次是真等待：
                 * 1. `Console.ReadAnyKey()`（:810）只是把控制台的 `inputReq`/`state` 置成
                 *    EnterKey 等待然后**立刻返回**（EmueraConsole.cs:876-895 全无阻塞），
                 *    `DoInstruction` 还没走完就被第 2 步整个覆盖掉（:864-865）⇒ 用户看不到、
                 *    也答不了这一次「等待」；它留下的只有 `process.NeedWaitToEventComEnd = false`
                 *    （:894，本移植未接那条 `setWait` 支路，见 BeginFlow.kt:350-357 的登记）。
                 * 2. **一次** `Console.WaitInput(req)`（:823）：`Timelimit = 第1参`，形态是
                 *    `第2参 == 0 ? EnterKey : Void`（:814-821）；`WaitInput` 的返回值原版丢弃。
                 *
                 * 所以 TWAIT = 一次限时等待，交付按形态决定（EnterKey ⇒ 提交即走/超时照走，
                 * 都不写 RESULT；Void ⇒ 键盘提交完全无效，只有超时推进，EmueraConsole.cs:1417-1420）。
                 * 旧实现把这次等待塌缩成 INT 限时等待，还额外真等了一次按键：
                 * 语料里 207 处 TWAIT（其中 51 处第 2 参非 0）全是「按一次键跳过/纯延时」的用法，
                 * 塌缩后 `TWAIT 1500, 0` 会先死等一次回车、`TWAIT 40, 1` 会被早到的提交打断。
                 */
                openTag(a, code, ErbcFormat.ArgTag.SP_SWAP_CHARA)
                // 第 1、2 参都要在等待之前求值，且顺序固定（RAND 等副作用，:812-813）。
                val time = a.expr()?.getIntValue(ctx.eval) ?: 0L
                val voidForm = (a.expr()?.getIntValue(ctx.eval) ?: 0L) != 0L
                val req = InputRequest(
                    // 非取值形态：type 只是宿主选键盘类型的提示，交付不写 RESULT/RESULTS。
                    type = InputType.STR,
                    form = if (voidForm) InputRequest.RequestForm.VOID else InputRequest.RequestForm.ENTER_KEY,
                    timeLimit = time,
                )
                // 原版 WaitInput 无条件发生（:823），跳过态下也不例外：`presetTimer`（:866-873）
                // 照样挂计时器、`setTimer` 照样清零 isTimeout，只是随后被输入循环跑过
                // （:1451-1455 的 NeedValue=false 自动推进）。
                armIsTimeout(ctx, req)
                if (ctx.mesSkip && !req.needValue) {
                    // 同 C4：TWAIT 的无值等待被 MesSkip 吞掉后无条件清标志（EmueraConsole.cs:1456）。
                    ctx.mesSkip = false
                } else {
                    val outcome = waitInput(ctx, req)
                    updateIsTimeout(ctx, req, outcome)
                }
                ExecResult.Normal
            }
            FunctionCode.INPUT, FunctionCode.INPUTS, FunctionCode.ONEINPUT, FunctionCode.ONEINPUTS -> {
                execInputsFlat(code, a, ctx); ExecResult.Normal
            }
            FunctionCode.TINPUT, FunctionCode.TONEINPUT, FunctionCode.TINPUTS, FunctionCode.TONEINPUTS -> {
                execTInputsFlat(code, a, ctx); ExecResult.Normal
            }
            FunctionCode.BINPUT, FunctionCode.BINPUTS, FunctionCode.ONEBINPUT, FunctionCode.ONEBINPUTS -> {
                execBInputsFlat(code, a, ctx); ExecResult.Normal
            }
            FunctionCode.INPUTANY -> {
                // spec: FunctionIdentifier.cs:2095-2111 —— `InputType.AnyValue` 的交付在
                // `doInputToEmueraProgram` 的 AnyValue 分支（EmueraConsole.cs:1256-1274）：
                // `long.TryParse` 成功 → 只 `InputInteger`（写 RESULT），失败 → 只 `InputString`
                // （写 RESULTS），**二选一、从不双写**；该分支不看 HasDefValue（INPUTANY 无默认值参数）。
                val req = InputRequest(type = InputType.INT, form = InputRequest.RequestForm.ANY_VALUE)
                val o = waitInput(ctx, req)
                updateIsTimeout(ctx, req, o)
                // 单通道：宿主按能否解析成整数决定 `writesInt`，两条交付互斥。
                if (o.writesInt) ctx.variables.writeInt(RESULT, longArrayOf(0), o.intValue) else
                    ctx.variables.writeStr(RESULTS, longArrayOf(0), o.strValue)
                ExecResult.Normal
            }
            FunctionCode.INPUTMOUSEKEY -> {
                // spec: FunctionIdentifier.cs:2067-2093 —— `InputType.PrimitiveMouseKey`，
                // 第 1 参 >0 才挂时限（:2087-2090，0/缺省 = 不限时）。
                val timeLimit = argExpressions(a, code).getOrNull(0)?.getIntValue(ctx.eval) ?: -1L
                val req = InputRequest(
                    type = InputType.INT,
                    form = InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY,
                    timeLimit = timeLimit,
                )
                val o = waitInput(ctx, req)
                updateIsTimeout(ctx, req, o)
                if (o.isTimeout) {
                    // 原版超时走 `endTimer` 的 PrimitiveMouseKey 分支（EmueraConsole.cs:1044-1048）：
                    // `InputMouseKey(4, 0, 0, 0, 0, 0)` → `process.InputResult5` 整组写
                    // RESULT:0..5 = 4,0,0,0,0,0（Process.cs:393-401，r0=4 是「超时交付」类型位）。
                    // 本移植等待面把交付抽象成整数提交，超时前不可能有部分写入，照抄整组清零。
                    for (i in 0L..5L) ctx.variables.writeInt(RESULT, longArrayOf(i), if (i == 0L) 4L else 0L)
                } else if (o.writesInt) {
                    // 一条文本提交：原版 switch 没有 PrimitiveMouseKey 的 case（:1112-1288），
                    // 于是既不写 RESULT 也不写 RESULTS，只是推进 ⇒ 宿主回报 writesInt=false。
                    // 真鼠标/按键事件的 RESULT:1..5（坐标/按钮/字符码）属宿主事件接线，见本类
                    // 输入族文档与本路交付报告的 TODO(verify)。
                    ctx.variables.writeInt(RESULT, longArrayOf(0), o.intValue)
                }
                ExecResult.Normal
            }

            // ---- 变量操作 ----
            FunctionCode.SETBIT, FunctionCode.CLEARBIT, FunctionCode.INVERTBIT -> {
                execBitFlat(code, a, ctx); ExecResult.Normal
            }
            FunctionCode.SWAP -> {
                execSwapFlat(a, code, ctx); ExecResult.Normal
            }
            FunctionCode.VARSET -> {
                execVarSetFlat(a, code, ctx); ExecResult.Normal
            }
            FunctionCode.CVARSET -> {
                execCVarSetFlat(a, code, ctx); ExecResult.Normal
            }
            FunctionCode.VARSIZE -> {
                execVarSizeFlat(a, code, ctx); ExecResult.Normal
            }
            FunctionCode.POWER -> {
                execPowerFlat(a, code, ctx); ExecResult.Normal
            }
            FunctionCode.TIMES -> execTimesFlat(a, code, ctx)
            FunctionCode.ENCODETOUNI -> {
                execEncodeToUniFlat(a, code, ctx); ExecResult.Normal
            }
            FunctionCode.SPLIT -> {
                execSplitFlat(a, code, ctx); ExecResult.Normal
            }
            FunctionCode.ARRAYSHIFT -> {
                execArrayShiftFlat(a, code, ctx); ExecResult.Normal
            }
            FunctionCode.ARRAYREMOVE -> {
                execArrayRemoveFlat(a, code, ctx); ExecResult.Normal
            }
            FunctionCode.ARRAYSORT -> {
                execArraySortFlat(a, code, ctx); ExecResult.Normal
            }
            FunctionCode.ARRAYCOPY -> {
                execArrayCopyFlat(a, code, ctx); ExecResult.Normal
            }
            FunctionCode.STRLEN, FunctionCode.STRLENFORM, FunctionCode.STRLENU, FunctionCode.STRLENFORMU -> {
                // spec: FunctionIdentifier.cs:699-729
                val text = evalStr(argTerm(a, code), ctx)
                val len = if (code == FunctionCode.STRLENU || code == FunctionCode.STRLENFORMU) {
                    text.length
                } else {
                    strLenLang(text, langCodecOf(ctx.eval))
                }
                ctx.variables.writeInt(RESULT, longArrayOf(0), len.toLong())
                ExecResult.Normal
            }
            FunctionCode.PRINTCPERLINE -> {
                openTag(a, code, ErbcFormat.ArgTag.SP_GET_INT)
                writeVarRef(a.varRefNN(), ctx.config.printCPerLine.toLong(), ctx) // spec: Process.cs:1577-1579
                ExecResult.Normal
            }
            FunctionCode.SAVENOS -> {
                openTag(a, code, ErbcFormat.ArgTag.SP_GET_INT)
                writeVarRef(a.varRefNN(), ctx.config.saveDataNos.toLong(), ctx) // spec: Process.cs:1580-1582
                ExecResult.Normal
            }
            FunctionCode.METHOD -> {
                // spec: FunctionIdentifier.cs:584-604 —— 整型写 RESULT，字符串写 RESULTS
                val tag = a.open()
                if (tag != ErbcFormat.ArgTag.METHOD) throw shapeError(code, ErbcFormat.ArgTag.METHOD, tag)
                val term = a.expr()
                if (term != null) {
                    if (term.operandType == OperandType.INT) {
                        ctx.variables.writeInt(RESULT, longArrayOf(0), term.getIntValue(ctx.eval))
                    } else {
                        ctx.variables.writeStr(RESULTS, longArrayOf(0), term.getStrValue(ctx.eval))
                    }
                }
                ExecResult.Normal
            }
            // ---- 角色库 ----
            FunctionCode.ADDCHARA, FunctionCode.ADDSPCHARA -> {
                // spec: FunctionIdentifier.cs:1344-1397 —— ADDCHARA / ADDSPCHARA / DELCHARA 共用一个
                // 实现类，靠构造期的 isSp / isDel 位区分（注册：:3943 ADDCHARA、:3944 ADDSPCHARA、
                // :3947 DELCHARA；三条都把参数形态钉成 INT_ANY，:1352）。
                // ① SP 关闭且本条是 SP 变体 → 抛错，且**发生在任何实参求值之前**
                //   （:1360-1363 在 :1368 的循环之前）⇒ `ADDSPCHARA 未定义NO` 报的是 SP 错，不是角色错。
                if (code == FunctionCode.ADDSPCHARA && !spCharaEnabled(semHost(ctx))) {
                    throw CodeEE(SP_CHARA_CONFIG_IS_OFF)
                }
                // ② 逐参数求值 + 追加（:1368-1384）。SP **开启**时 ADDCHARA 与 ADDSPCHARA 都走
                //   `AddCharacter_UseSp`（:1376-1379），而 `GetCharacterTemplate_UseSp` 的 `sp` 形参
                //   **全方法体未被读取**（ConstantData.cs:1230-1238）⇒ 原版按 NO 查表、SP 身份不参与
                //   筛选，两条语句同一条路（ADR-005 原样复刻，不"顺手修好"）。
                //   SP 关闭时 ADDCHARA 走 `AddCharacter`（:1380-1383）= 按 NO 线性首命中
                //   （ConstantData.cs:1218-1228）。移植侧两条路都落到 templatesByNo 查表
                //   （VariableStore.kt:481-485，未命中 CodeEE）；原版 `_UseSp` 用的是排好序列表上的
                //   BinarySearch（排序点 ConstantData.cs:1503），命中集与线性首命中一致 ⇒ 无观测差。
                //   TODO(verify): 装载期若未走到 :1503 的排序，原版 BinarySearch 在未序列表上的
                //   命中/未命中不确定；本工程无该场景读数，暂按 map 精确命中口径。
                for (t in argTerms(a, code)) ctx.variables.addCharacter(t.getIntValue(ctx.eval))
                ExecResult.Normal
            }
            FunctionCode.ADDCOPYCHARA -> {
                // spec: FunctionIdentifier.cs:1447-1463（指令体 :1455-1462 逐实参调
                //   `exm.VEvaluator.AddCopyChara(aExpression.GetIntValue(exm))`，:1460；参数形态
                //   FunctionArgType.INT_ANY，:1451 ⇒ 实参只有**源位置**一个）→
                //   VariableEvaluator.cs:1368-1376 的 `AddCopyChara(x)`，次序是硬性的：
                //   ① 边界校验 `x < 0 || x >= CharacterList.Count` ⇒ 抛 NotExistFromCopyChara
                //     （:1370-1373，中文文案 lang.emuera-zhs.xml:316「复制的原角色不存在」）；
                //   ② 校验通过后**才** `AddPseudoCharacter()`（:1374）；
                //   ③ `CharacterList[x].CopyTo(CharacterList[Count-1])`（:1375）
                //     ⇒ **源 = 实参 x，目标 = 新追加的尾元素**。
                // 移植侧两处历史分歧（先追加后校验 / 源目标反号）已收进
                // VariableStore.addCopyCharacter（判界先于追加、方向单点收口）；
                // 方向用例见 ResetGlobalAuditTest。
                for (t in argTerms(a, code)) ctx.variables.addCopyCharacter(t.getIntValue(ctx.eval))
                ExecResult.Normal
            }
            FunctionCode.ADDVOIDCHARA -> {
                ctx.variables.addPseudoCharacter(); ExecResult.Normal // spec: FunctionIdentifier.cs:1399-1411
            }
            FunctionCode.ADDDEFCHARA -> {
                // spec: Process.cs:1284-1294 —— CSV 0 号 + GameBase 指定的默认角色
                // （SYSTEM_TITLE 之外不可用的检查依赖父标签信息，属 proc 层）
                ctx.variables.addCharacterFromCsvNo(0L)
                val defNo = ctx.variables.registry.gameBase.defaultChara
                if (defNo > 0) ctx.variables.addCharacterFromCsvNo(defNo)
                ExecResult.Normal
            }
            FunctionCode.DELCHARA -> {
                // spec: FunctionIdentifier.cs:1385-1395 —— isDel 的**语句层**分流（原版就在这里，
                // 不在求值器里）：先把全部实参求值收进 array（:1368-1375，isDel 分支只收集不删），
                // 再按**实参个数**二选一：
                // - `:1387-1390` `array.Length == 1` → `DelCharacter(array[0])`：单参重载，
                //   越界带编号（VariableEvaluator.cs:1245）；
                // - `:1391-1394` 否则（含 0 个实参）→ `DelCharacter(array)`：数组参重载，
                //   越界丢编号（:1259，官方 bug，ADR-005 原样复刻）。
                // 求值必须先全部完成再分流：原版循环在 :1368-1384，分流在 :1385 之后。
                val ps = argTerms(a, code).map { it.getIntValue(ctx.eval) }
                if (ps.size == 1) {
                    ctx.variables.deleteCharacter(ps[0])
                } else {
                    ctx.variables.deleteCharacters(ps.toLongArray())
                }
                ExecResult.Normal
            }
            FunctionCode.DELALLCHARA -> {
                ctx.variables.deleteAllCharacters(); ExecResult.Normal // spec: Process.cs:1264-1266
            }
            FunctionCode.SWAPCHARA -> {
                openTag(a, code, ErbcFormat.ArgTag.SP_SWAP_CHARA)
                ctx.variables.swapCharacters(
                    a.exprNN().getIntValue(ctx.eval),
                    a.exprNN().getIntValue(ctx.eval),
                )
                ExecResult.Normal
            }
            FunctionCode.COPYCHARA -> {
                openTag(a, code, ErbcFormat.ArgTag.SP_SWAP_CHARA)
                ctx.variables.copyCharacter(
                    a.exprNN().getIntValue(ctx.eval),
                    a.exprNN().getIntValue(ctx.eval),
                )
                ExecResult.Normal
            }
            FunctionCode.SORTCHARA -> {
                openTag(a, code, ErbcFormat.ArgTag.SP_SORTCHARA)
                val sortKey = a.varRefNN()
                val order = a.sortOrder()
                val name = sortKey.name
                val idx = resolveIndices(sortKey, ctx)
                // 字符串判定要**先查用户变量再查系统表**（resolveVarInfo 同 userVar 优先序）：
                // 用户 #DIM CHARADATA 字符串列（CVARS）不在 registry.def 里，只查 registry
                // 会把它误判成整数键，排序时 readInt 直接报「字符串变量不能当整数读取」。
                // 原版按 token 的 IsString 分流（VariableEvaluator.cs:1408 SetSortKey 内部分支）。
                val isStr = ctx.variables.resolveVarInfo(name)?.operandType == OperandType.STR
                val asc = order != SortOrder.DESENDING
                if (isStr) {
                    ctx.variables.sortCharactersByStr(name, idx.drop(1).toLongArray(), asc)
                } else {
                    ctx.variables.sortCharactersByInt(name, idx.drop(1).toLongArray(), asc)
                }
                ExecResult.Normal
            }
            FunctionCode.PICKUPCHARA -> {
                // spec: Process.cs:1267-1283（逐实参「求值→判界」交错；豁免 = 实参本身是
                // MASTER/ASSI/TARGET 变量引用 —— 这三者可合法取 -1，Process.cs:1277-1280
                // 的 `aExpression2 is VariableTerm && Identifier.Code ∈ {MASTER,ASSI,TARGET}`）+
                // VariableEvaluator.cs:1288-1353（PickUpChara：去重跳负 / 交换就位 / 指针重绑）。
                val terms = argTerms(a, code)
                val n = ctx.variables.characters.count
                val nos = LongArray(terms.size)
                for ((i, term) in terms.withIndex()) {
                    nos[i] = term.getIntValue(ctx.eval)
                    val exempt = term is VarRef && term.name.uppercase() in PICKUP_CHARA_EXEMPT_VARS
                    if (!exempt && (nos[i] < 0 || nos[i] >= n)) {
                        throw CodeEE("命令PICKUPCHARA的第${i + 1}个参数的值(${nos[i]})超过角色列表的范围")
                    }
                }
                ctx.variables.pickUpCharacters(nos)
                ExecResult.Normal
            }
            FunctionCode.RESET_STAIN -> {
                // spec: Process.cs:1557-1560 + VariableEvaluator.cs:1903-1922（默认表外元素清零）
                val target = argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L
                // spec: VariableEvaluator.cs:1923-1930 `SetDefaultStain(long no)`：越界抛
                // `Lang.Error.RefUndefinedChara` —— 文案照抄官方 zhs
                // （lang/emuera-zhs.xml:319 = `试图引用一个尚未定义的角色`），
                // 与 OoRCharaNum（`试图引用一个不存在的注册角色`，:312）是两条不同键，不得混用。
                if (target < 0 || target >= ctx.variables.characters.count) {
                    throw CodeEE("试图引用一个尚未定义的角色")
                }
                val def = ctx.config.stainDefault
                val cap = ctx.variables.getLength(STAIN, 0)
                for (e in 0 until cap) {
                    ctx.variables.writeInt(STAIN, longArrayOf(target, e.toLong()), if (e < def.size) def[e] else 0L)
                }
                ExecResult.Normal
            }

            // ---- 进程状态 ----
            FunctionCode.SKIPDISP -> {
                val v = (argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L) != 0L // spec: Process.cs:1587-1592
                ctx.skipPrint = v
                ctx.variables.writeInt(RESULT, longArrayOf(0), if (v) 1L else 0L)
                ExecResult.Normal
            }
            FunctionCode.NOSKIP -> {
                // spec: Process.cs:1593-1603（ENDNOSKIP 配对缺失检查依赖块结构，属解析/proc 层）
                ctx.saveSkip = ctx.skipPrint
                if (ctx.skipPrint) ctx.skipPrint = false
                ExecResult.Normal
            }
            FunctionCode.ENDNOSKIP -> {
                if (ctx.saveSkip) ctx.skipPrint = true // spec: Process.cs:1604-1613
                ExecResult.Normal
            }
            FunctionCode.SKIPLOG -> {
                ctx.mesSkip = (argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L) != 0L; ExecResult.Normal // spec: Process.cs:1830-1833
            }
            FunctionCode.FORCEKANA -> {
                // spec: Process.cs:1583-1586 + ExpressionMediator.cs:34-43
                val v = argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L
                if (v < 0 || v > 3) throw CodeEE("命令FORCEKANA的参数超过可指定范围(0～3)")
                ExecResult.Normal // 假名转换依赖 VB StrConv，宿主层暂无效果（TODO(verify) M5）
            }
            FunctionCode.GETTIME -> {
                // spec: Process.cs:1396-1408 —— 逐字段拼 yyyymmddhhmmssmmm；RESULTS 给 "yyyy/MM/dd HH:mm:ss"。
                // 时钟只有一个出口：宿主注入点 BuiltinHost.clockMillis（Builtins.kt:30 声明，
                // BootHosts.kt:87-88 的 KDoc 明写它就是「对应 GETTIME 家族」的注入点；表达式位的
                // GETTIME / GETTIMES 方法本就吃它，HostMethods.kt:117-129 是同一条路子的现成写法）。
                // 本语句此前直接读 java.time.LocalDateTime.now()，绕过注入点 —— 后果不是「值不对」而是
                // 「宿主钉不住」：E2E / L3 装置无法把时钟固定，两次运行的 RESULT 数值、RESULTS 原文
                // （经 VariableStore.kt:202-214 的 pool.intern 进驻留池）以及由它折算的 DT 行主键
                // （MapDataTableMethods.kt:494-501，写进 RESULT:1 起的格）全部漂移，L3 链必分叉。
                // 语境拿不到 BuiltinContext 的情况 = 语句层裸假体（SemTestEnv.TestEvalContext 一类单测），
                // 回落 BuiltinHost.DEFAULT，其 clockMillis 就是 System.currentTimeMillis 本身（Builtins.kt:30）；
                // 产线 ProcessRuntime 的 execContext.eval 恒为 BuiltinEvalContext（ProcessRuntime.kt:256-262、
                // :360-362），未注入 builtinHost 时用 SemConfigHost（ProcessRuntime.kt:1226）——它没覆写
                // clockMillis，取到的仍是同一个 System.currentTimeMillis，故产线行为逐字节不变。
                // 时区口径：原版 DateTime.Now 是本地墙上时间，「本地」走宿主注入点
                // （BuiltinHost.zoneId，与 clockMillis 同协议）——注册期/写死 systemDefault 会让
                // 装置钉不住时区，L3 链 #10 起跨环境漂移。
                val host = (ctx.eval as? BuiltinContext)?.host ?: BuiltinHost.DEFAULT
                val now = java.time.Instant.ofEpochMilli(host.clockMillis())
                    .atZone(host.zoneId())
                    .toLocalDateTime()
                var num = now.year.toLong()
                num = num * 100 + now.monthValue
                num = num * 100 + now.dayOfMonth
                num = num * 100 + now.hour
                num = num * 100 + now.minute
                num = num * 100 + now.second
                num = num * 1000 + (now.nano / 1_000_000)
                ctx.variables.writeInt(RESULT, longArrayOf(0), num)
                ctx.variables.writeStr(
                    RESULTS, longArrayOf(0),
                    "%04d/%02d/%02d %02d:%02d:%02d".format(java.util.Locale.ROOT, now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second),
                )
                ExecResult.Normal
            }
            FunctionCode.RESETDATA -> {
                // spec: FunctionIdentifier.cs:1844-1848（DoInstruction 方法体恰两句）——
                // :1846 `exm.VEvaluator.ResetData();` → :1847 `exm.Console.ResetStyle();`。
                // 复位顺序按原版：样式复位紧随 resetData 之后。
                // 复位集恰四项（前景/样式位/字体名/对齐，EmueraConsole.cs:2838-2842），
                // 不含 bgColor（:200）/redraw（:455）/打印缓冲 —— 由 ConsoleSink.resetStyle 保证。
                // 反向防误接：RESETGLOBAL（FunctionIdentifier.cs:1851-1863）原版不复位样式。
                // C# ResetData 的第一步是 RemoveEMSaveData（VariableEvaluator.cs:1338 →
                // VariableData.cs:4006-4029：按 SaveMaps/SaveXmls/SaveDTs 清上一局 SAVE 命名
                // MAP/XML/DT）；此前只有 LOADDATA 接了这一步（FileSystemOps.loadData），
                // RESETDATA/回标题后旧局扩展池残留、XML_EXIST/MAP_GETKEYS/DT_CELL_GET 读到旧数据
                // （2026-10-26 fixcore 审查 B1；语料 TITLE.ERB:115 的 RESETDATA 可达）。
                SaveWiring.of(ctx.variables)?.emSets?.let { em -> EmDataPools.clear(ctx.variables, em.save) }
                ctx.variables.resetData()
                ctx.console.resetStyle()
                ctx.skipPrint = false
                ctx.mesSkip = false
                ctx.saveSkip = false
                ExecResult.Normal
            }
            FunctionCode.RESETGLOBAL -> {
                // spec: FunctionIdentifier.cs:1851-1863（指令体 :1859-1862 只一句
                //   `exm.VEvaluator.ResetGlobalData();`，:1861）⇒ 复位面在求值层
                //   VariableEvaluator.cs:1348-1353 的 **三步**：
                //   ① `RemoveEMGlobalData()`（VariableData.cs:4031-4054）：按 `GlobalSaveMaps`
                //     清 MAP（表留着、内容清空）、按 `GlobalSaveXmls` **移除** XML、
                //     按 `GlobalSaveDTs` 清 DT 行 ⇒ 移植侧 `EmDataPools.clear(store, emSets.global)`。
                //   ② `RemoveEMStaticData()`（:4056-4079）：同型三遍，走 `StaticMaps/StaticXmls/StaticDTs`
                //     （注册处 ConstantData.cs:1389-1405+ 的 `STATIC_XMLS`/`STATIC_MAPS`/`STATIC_DTS`）。
                //     本工程 `EmSaveSets.parse`（core/save/EmSaveSets.kt:83-89）只收 `SAVE_*`/`GLOBAL_*`
                //     六条指令，`STATIC_*` 三表**未解析** ⇒ 本步接不上，登记为需组长接线，
                //     未接前本语句复位不到 STATIC 命名的池（名字表为空 ⇒ 与不执行等价，不会误清）。
                //   ③ `SetDefaultGlobalValue()`（:3347-3363）：`dataIntegerArray[63]`（GLOBAL 整表）
                //     置 0、`dataStringArray[5]`（GLOBALS）**置 null 而不是空串**（:3357），
                //     再对 `userDefinedGlobalVarList` 逐个 `SetDefault()`（:3359-3362）。
                //     `SetDefault()` 的口径是「回到 `#DIM`/`DEFAULT` 声明的初值」而非归零：
                //     `StaticInt1DVariableToken.SetDefault`（:1323-1332）先清零再回填 `defArray`
                //     （`defArray = data.DefaultInt`，构造 :1308）；`StaticStr1DVariableToken.SetDefault`
                //     （:1595-1606）在 `defArray` 非空时**只回填前段、不动其后元素**（官方怪癖，
                //     ADR-005 原样复刻，移植侧 `StaticStrUserVar.setDefault` 已照抄）。
                //     GLOBAL 用户变量的入表面是 `CreateUserDefVariable`（:3246-3268 的
                //     `userDefinedGlobalVarList.Add`），移植侧同名集合 = `globalUserVars`。
                //     注意 GLOBALS 的 null 语义：本工程字符串槽以驻留池下标 0 表示「未赋值」，
                //     而 `StringTable` 的 0 号槽就是空串（StringTable.kt:19-22 的 init），
                //     即移植侧**不存在** null 与空串的可观测区分 ⇒ 以 fill(0) 落地（口径差异登记）。
                // 反向防误接：RESETGLOBAL 原版**不**复位样式（样式复位只在 RESETDATA，
                //   EmueraConsole.cs:2838-2842），也**不**清角色表、不动 STATIC 用户变量、
                //   不动局部变量 —— 与 RESETDATA（:1336-1346）是两个互不重叠的复位面。
                ctx.variables.resetGlobalValues()
                // 组长接线（M4-N）：① RemoveEMGlobalData + ② RemoveEMStaticData 两次同型清空。
                // 名字表来自 `wave/n-saveaudit` 落地的 `EmSaveSets.staticSets`（`ConstantData.cs:1388-1408`
                // 的 STATIC_XMLS/STATIC_MAPS/STATIC_DTS ⇒ `VariableData.cs:4056-4079`）。
                // **不进 loadAccept**：原版存盘侧没有 static 支、读档接受集只判 `Save* || Global*`
                // （`:4099`/`:4109`/`:4119`）。未装配会话 = 空表 = 空操作。
                SaveWiring.of(ctx.variables)?.emSets?.let { em ->
                    EmDataPools.clear(ctx.variables, em.global)
                    EmDataPools.clear(ctx.variables, em.staticSets)
                }
                ExecResult.Normal
            }
            FunctionCode.PUTFORM -> {
                // spec: Process.cs:1295-1306 —— 追加目标就是系统字符串变量 SAVEDATA_TEXT 本体
                // （VariableEvaluator.cs:119-129：SAVEDATA_TEXT 属性即 varData.DataString[0]，
                // PUTFORM 直接 `SAVEDATA_TEXT += text`；RESETDATA 经变量复位清空，不经旁路状态）。
                val text = evalStr(argTerm(a, code), ctx)
                val cur = ctx.variables.readStr(SAVEDATA_TEXT, longArrayOf())
                ctx.variables.writeStr(SAVEDATA_TEXT, longArrayOf(), cur + text)
                ExecResult.Normal
            }
            FunctionCode.ASSERT -> {
                if ((argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L) == 0L) {
                    throw CodeEE("ASSERT的参数为0") // spec: Process.cs:1790-1795
                }
                ExecResult.Normal
            }
            FunctionCode.THROW -> {
                throw CodeEE(evalStr(argTerm(a, code), ctx)) // spec: Process.cs:1796-1797（可被 TRYC 捕获）
            }

            // ---- 调试 ----
            FunctionCode.DEBUGPRINT, FunctionCode.DEBUGPRINTFORM -> {
                ctx.debug.print(evalStr(argTerm(a, code), ctx)); ExecResult.Normal // spec: FunctionIdentifier.cs:540-568
            }
            FunctionCode.DEBUGPRINTL, FunctionCode.DEBUGPRINTFORML -> {
                ctx.debug.printLine(evalStr(argTerm(a, code), ctx)); ExecResult.Normal
            }
            FunctionCode.DEBUGCLEAR -> {
                ctx.debug.clear(); ExecResult.Normal // spec: FunctionIdentifier.cs:570-582
            }

            // ---- 声音 ----
            FunctionCode.PLAYSOUND -> {
                openTag(a, code, ErbcFormat.ArgTag.SP_HTML_PRINT)
                val strTerm = a.expr()
                // spec: FunctionIdentifier.cs:2625 —— Opt 为 null 取 1，否则 Math.Max(实参,1L) 在 long 域
                // 求值后按 unchecked (int) 截断：4294967295 截成 -1（Sound.cs 据此进 LoopStream）。
                // 截断后再 coerceAtLeast(1) 会把 -1 吃成 1，与原版相反。
                val count = a.expr()?.getIntValue(ctx.eval)?.let { if (it < 1L) 1L else it }?.toInt() ?: 1
                ctx.sound.playSound(evalStr(strTerm, ctx), count); ExecResult.Normal
            }
            FunctionCode.STOPSOUND -> {
                ctx.sound.stopSound(); ExecResult.Normal
            }
            FunctionCode.PLAYBGM -> {
                ctx.sound.playBgm(evalStr(argTerm(a, code), ctx)); ExecResult.Normal
            }
            FunctionCode.STOPBGM -> {
                ctx.sound.stopBgm(); ExecResult.Normal
            }
            FunctionCode.SETSOUNDVOLUME -> {
                ctx.sound.setSoundVolume((argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L).toInt()); ExecResult.Normal
            }
            FunctionCode.SETBGMVOLUME -> {
                ctx.sound.setBgmVolume((argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L).toInt()); ExecResult.Normal
            }
            FunctionCode.AWAIT -> {
                // spec: FunctionIdentifier.cs:2581-2606 → EmueraConsole.cs:840-856
                // 无参时原版取 -1，而 `if (time > 0) Thread.Sleep(time)` ⇒ 无参 AWAIT 只让出不睡；
                // 本侧缺省求值给 0（evalIntArg），与「不睡」同观感。
                val v = argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L
                if (v < 0) throw CodeEE("AWAIT命令:指定了一个负值($v)")
                // 文案照抄官方 zhs：`Lang.Error.AwaitArgIsOver10Seconds`（lang/emuera-zhs.xml:529）
                // = `AWAIT命令:指定了一个超过10秒的值({0}ms)`，占位符是**毫秒原值**；
                // C# 抛点 `FunctionIdentifier.cs:2599-2602`（num.ToString() 不换算单位）。
                if (v > 10000) throw CodeEE("AWAIT命令:指定了一个超过10秒的值(${v}ms)")
                // 帧发布（CONSOLE-FRAMEGATE）：原版 Await 的第一步就是 RefreshStrings(force_Paint: true)
                // （EmueraConsole.cs:847）——让出边界是唯一的整帧上屏点。先发布本段积攒的完整帧，
                // 再睡；睡醒冻结，让下一个脚本段的变化积累成一帧（原版 DoEvents 模型的等价落点）。
                ctx.console.publishFrame()
                ctx.timer.delay(v)
                ctx.console.holdFrame()
                // 原版死循环保护的唯一复位调用点就在这里（Process.cs:487-491 只被
                // EmueraConsole.cs:849 调用一次）⇒ AWAIT 必须计成让出边界，否则像
                // `@BADAPPLE`（TITLE.ERB:123，FOR count,1,6573 + 每帧 AWAIT/AWAIT 16，
                // TITLE.ERB:152-156）这种「整段只靠 AWAIT 逐帧让出」的动画会在一次 run 里
                // 攒满段落计数、撞死循环闸。
                yieldBoundaryCount++
                ExecResult.Normal
            }

            // ---- 系统钩子（SystemOps）----
            FunctionCode.SAVEDATA -> {
                // spec: Process.cs:1327-1349
                openTag(a, code, ErbcFormat.ArgTag.SP_SAVE_DATA)
                val target = a.expr()
                val strExpression = a.expr()
                val no = target?.getIntValue(ctx.eval) ?: -1L
                if (no < 0) throw CodeEE("对SAVEDATA的参数使用负值($no)")
                if (no > Int.MAX_VALUE) throw CodeEE("SAVEDATA的参数值过大($no)")
                val text = strExpression?.let { evalStr(it, ctx) } ?: ""
                if (text.contains('\n')) {
                    throw CodeEE("SAVEDATA对的提示文本使用了换行符号（由于会导致存档损坏，不能使用换行符）")
                }
                if (!ops(ctx).saveData(no.toInt(), text)) {
                    ctx.console.printError("SAVEDATA命令在存档时发生了意料之外的错误")
                }
                ExecResult.Normal
            }
            FunctionCode.LOADDATA -> {
                // spec: Process.cs:1841-1863（doFlowControlFunction 的 LOADDATA 分支）
                val no = argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L
                // :1844-1847；文案 Lang.Error.LoaddataArgIsNegative（lang/emuera.zhs.xml:441）
                if (no < 0) throw CodeEE("LOADDATA的参数被指定为负数($no)")
                // :1848-1851；文案 Lang.Error.TooLargeLoaddataArg（lang/emuera.zhs.xml:672）
                if (no > Int.MAX_VALUE) throw CodeEE("LOADDATA的参数值($no)过大")
                // :1852-1859（CheckData → LoadCorruptedData / LoadFrom → UnexpectedErrorInLoaddata）
                ops(ctx).loadData(no.toInt())
                // :1860-1862 —— `state.ClearFunctionList(); state.SystemState =
                // SystemStateCode.LoadData_DataLoaded; return false;`
                //
                // 本仓的等价落点（不许新造状态枚举）：
                // - `ClearFunctionList` 的可执行等价是「立即结束当前脚本帧」——原版清空函数栈后
                //   `runScriptProc` 直接返回、`DoScript` 转 `runSystemProc`（Process.cs:449-452）；
                //   本层能表达该语义的唯一出口是 [ExecResult.Quit]（`ProcessRuntime.kt:468`
                //   映射成 `StmtOutcome.Stop(0)`；`Normal` 会让下一条语句继续跑，与清栈相反）。
                // - 状态跳转由 `FileSystemOps.loadData` 在 `LASTLOAD_NO` 之后经
                //   [com.eratw.port.core.host.SaveLoadHandoff] 记账（顺序照 :1860→:1861），
                //   proc 侧消费点见该类 KDoc（ProcessRuntime/BeginFlow 不在本路文件面内）。
                ExecResult.Quit()
            }
            FunctionCode.DELDATA -> {
                // spec: FunctionIdentifier.cs:1944-1957 —— 参数过 toUInt32inArg(value, "DELDATA", 1)
                // （:4320-4331：先负值、后 > Int.MAX_VALUE），文案 Lang.Error.ArgIsNegative
                // / ArgIsTooLarge（lang/emuera.zhs.xml:31 / :61）⇒「函数DELDATA: 第1个参数…」。
                val no = argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L
                if (no < 0) throw CodeEE("函数DELDATA: 第1个参数被是负值($no)")
                if (no > Int.MAX_VALUE) throw CodeEE("函数DELDATA: 第1个参数($no)过大")
                ops(ctx).delData(no.toInt())
                ExecResult.Normal
            }
            FunctionCode.SAVEGAME -> execSaveLoadGame(ctx, save = true)
            FunctionCode.LOADGAME -> execSaveLoadGame(ctx, save = false)
            FunctionCode.SAVEGLOBAL -> {
                ops(ctx).saveGlobal(); ExecResult.Normal
            }
            FunctionCode.LOADGLOBAL -> {
                val ok = ops(ctx).loadGlobal() // spec: FunctionIdentifier.cs:1815-1834
                ctx.variables.writeInt(RESULT, longArrayOf(0), if (ok) 1L else 0L)
                ExecResult.Normal
            }
            FunctionCode.SAVECHARA -> {
                // spec: FunctionIdentifier.cs:1869（SP_SAVECHARA_ArgumentBuilder：[string, string, long],
                // minArg=3）—— 参数序 = (文件名, 描述, 角色号...)；nos 从 arguments[3] 起（:1883
                // `array[i] = toUInt32inArg(intValue, "SAVECHARA", i + 3)`）。旧实现取 dropLast(2)
                // 与原版相反，曾致 SAVECHARA 静默写不出文件（E2E IO-CHARAFILE 卡 AFTER-LOAD）。
                val terms = argTerms(a, code)
                val filename = evalStr(terms[0], ctx)
                val text = evalStr(terms[1], ctx)
                val nos = terms.drop(2).map { it.getIntValue(ctx.eval) }
                ops(ctx).saveChara(nos, filename, text)
                ExecResult.Normal
            }
            FunctionCode.LOADCHARA -> {
                ops(ctx).loadChara(evalStr(argTerm(a, code), ctx)); ExecResult.Normal
            }
            FunctionCode.UPDATECHECK -> {
                ctx.variables.writeInt(RESULT, longArrayOf(0), ops(ctx).updateCheck()); ExecResult.Normal
            }
            FunctionCode.CALLTRAIN -> {
                ops(ctx).callTrain(argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L); ExecResult.Normal
            }
            FunctionCode.STOPCALLTRAIN -> {
                ops(ctx).stopCallTrain(); ExecResult.Normal
            }
            FunctionCode.DOTRAIN -> {
                // 进程内状态机语句（状态门 + doTrainSelectCom + Train_DoTrain），不是宿主能力：
                // proc 层注入的钩子优先；纯 sem 装置（无 proc）保留 SystemOps 的「未实现」口径。
                val no = argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L
                val hook = ctx.doTrainHook
                if (hook != null) hook(no) else ops(ctx).doTrain(no)
                ExecResult.Normal
            }
            FunctionCode.UPCHECK -> {
                ops(ctx).upcheck(); ExecResult.Normal
            }
            FunctionCode.CUPCHECK -> {
                ops(ctx).cupcheck(argTerm(a, code)?.getIntValue(ctx.eval) ?: 0L); ExecResult.Normal
            }

            FunctionCode.PRINT_ITEM -> {
                // spec: Process.cs:1207-1212 —— GetHavingItemsString（VariableEvaluator.cs:1037-1063）
                if (!ctx.skipPrint) {
                    ctx.console.print(havingItemsString(ctx))
                    ctx.console.newLine()
                }
                ExecResult.Normal
            }
            FunctionCode.PRINT_SHOPITEM -> {
                // spec: Process.cs:1214-1262 —— min(ITEMSALES, ITEMNAME, ITEMPRICE) 内 ItemSales(k)
                // 非 0 项 PrintC "[{k}] {名}({价}{币})"，每 PrintCPerLine 项 flush 分行，收尾再 flush。
                if (!ctx.skipPrint) {
                    val salesLen = ctx.variables.getLength("ITEMSALES", -1)
                    val nameLen = ctx.variables.registry.csvTable("ITEM").size
                    val priceLen = ctx.variables.getLength("ITEMPRICE", -1)
                    var bound = minOf(salesLen, nameLen)
                    if (bound > priceLen) bound = priceLen
                    var printed = 0
                    for (k in 0 until bound) {
                        if (ctx.variables.readInt("ITEMSALES", longArrayOf(k.toLong())) == 0L) continue
                        val name = ctx.variables.registry.csvTable("ITEM")[k]
                        val price = ctx.variables.readInt("ITEMPRICE", longArrayOf(k.toLong()))
                        // MoneyLabel 取原版缺省 "$"（ConfigData.cs:159；基准 CHARA-SHOP 节无在售项不触发，TODO(M4): 接 ConfigState）
                        ctx.console.printC("[$k] $name($price$)", alignRight = false)
                        printed++
                        if (ctx.config.printCPerLine > 0 && printed % ctx.config.printCPerLine == 0) {
                            ctx.console.newLine()
                        }
                    }
                    val dirty = if (ctx.config.printCPerLine > 0) printed % ctx.config.printCPerLine != 0 else printed > 0
                    if (dirty) ctx.console.newLine()
                    // RefreshStrings（:1252）重绘动作不移植
                }
                ExecResult.Normal
            }
            FunctionCode.DT_COLUMN_OPTIONS -> execDtColumnOptionsFlat(a, code, ctx)

            // ---- 始终抛未实现（原版占位语句）----
            FunctionCode.REF, FunctionCode.REFBYNAME, FunctionCode.SAVEVAR, FunctionCode.LOADVAR,
            -> throw CodeEE("语句 $code 尚未实现（NotImplCodeEE）")

            // ---- CALLSHARP：本地方法调用 + 变量实参回写 ----
            FunctionCode.CALLSHARP -> {
                execCallSharpFlat(a, code, ctx); ExecResult.Normal
            }

            // ---- BEGIN ----
            FunctionCode.BEGIN, FunctionCode.FORCE_BEGIN -> {
                ExecResult.Begin(evalStr(argTerm(a, code), ctx))
            }

            // ---- 低频 UI（Windows 专属，M4 接管）----
            FunctionCode.TOOLTIP_SETCOLOR, FunctionCode.TOOLTIP_SETDELAY, FunctionCode.TOOLTIP_SETDURATION,
            FunctionCode.TOOLTIP_SETFONT, FunctionCode.TOOLTIP_SETFONTSIZE, FunctionCode.TOOLTIP_CUSTOM,
            FunctionCode.TOOLTIP_FORMAT, FunctionCode.TOOLTIP_IMG,
            -> ExecResult.Normal // TODO(M4): 工具提示 UI 参数收集

            // ---- 控制流已编译为 VM 指令：运行期到达即 no-op；块标记语句无行为 ----
            FunctionCode.IF, FunctionCode.ELSEIF, FunctionCode.ELSE, FunctionCode.ENDIF,
            FunctionCode.SIF, FunctionCode.SELECTCASE, FunctionCode.CASE, FunctionCode.CASEELSE,
            FunctionCode.ENDSELECT, FunctionCode.FOR, FunctionCode.NEXT, FunctionCode.REPEAT,
            FunctionCode.REND, FunctionCode.WHILE, FunctionCode.WEND, FunctionCode.DO,
            FunctionCode.LOOP, FunctionCode.BREAK, FunctionCode.CONTINUE, FunctionCode.RESTART,
            FunctionCode.RETURNF,
            FunctionCode.GOTO, FunctionCode.GOTOFORM, FunctionCode.TRYGOTO, FunctionCode.TRYGOTOFORM,
            FunctionCode.TRYCGOTO, FunctionCode.TRYCGOTOFORM, FunctionCode.CALL, FunctionCode.CALLFORM,
            FunctionCode.TRYCALL, FunctionCode.TRYCALLFORM, FunctionCode.TRYCCALL, FunctionCode.TRYCCALLFORM,
            FunctionCode.TRYCJUMP, FunctionCode.TRYCJUMPFORM, FunctionCode.TRYJUMP, FunctionCode.TRYJUMPFORM,
            FunctionCode.JUMP, FunctionCode.JUMPFORM, FunctionCode.TRYJUMPLIST,
            FunctionCode.TRYCALLLIST, FunctionCode.TRYGOTOLIST,
            FunctionCode.CATCH, FunctionCode.ENDCATCH, FunctionCode.ENDFUNC, FunctionCode.FUNC,
            FunctionCode.CALLEVENT, FunctionCode.CALLF, FunctionCode.CALLFORMF,
            FunctionCode.TRYCALLF, FunctionCode.TRYCALLFORMF,
            FunctionCode.DATALIST, FunctionCode.ENDLIST, FunctionCode.ENDDATA,
            -> ExecResult.Normal

            else -> throw CodeEE("语句 $code 尚未实现（StatementExecutor）")
        }
    }

    /**
     * 参数记录形态校验失败时的统一出口（见 [execute] KDoc「形态校验统一口径」）。
     *
     * 抛 [com.eratw.port.core.code.ErbcDecodeException] 而不是 `CodeEE`：这是语句池记录与 `op`
     * 不自洽的容器级错误，没有可归因的脚本行；`CodeEE` 专指 ERB 作者可见的运行期语义错误。
     */
    private fun shapeError(code: FunctionCode, expected: Int, actual: Int): Nothing =
        throw com.eratw.port.core.code.ErbcDecodeException(
            0,
            "语句 $code 的参数记录形态应为 ArgTag=$expected，实为 $actual（语句池记录与 op 不自洽）",
        )

    /** 开参数记录并校验 ArgTag（不匹配按 [shapeError] 抛）。 */
    private fun openTag(a: FlatArgs, code: FunctionCode, expected: Int): FlatArgs {
        val tag = a.open()
        if (tag != expected) shapeError(code, expected, tag)
        return a
    }

    /**
     * 读一个**单表达式形态**的参数记录（`ArgType.STR` / `FORM_STR` / `STR_EXPRESSION` /
     * `EXPRESSION` / `INT_EXPRESSION` 及其 NULLABLE 变体全部落到 `ExpressionArgument`），
     * 返回其 term —— 旧 [evalStrArg] / [evalIntArg] 的直读等价物。
     *
     * 口径差异登记：旧 [evalIntArg] 的 `else -> arg.constInt` 分支只在「参数类不是
     * ExpressionArgument」时走，而那些 op 的 `argType` 在本表里不存在（VOID 形态的语句一律不
     * 调 [evalIntArg]），故此处按 term 求值与旧路径逐字同值。
     */
    private fun argTerm(a: FlatArgs, code: FunctionCode): Expr? {
        openTag(a, code, ErbcFormat.ArgTag.EXPRESSION)
        return a.expr()
    }

    /**
     * 读**表达式数组形态**的参数记录（`ArgType.INT_ANY` / `FORM_STR_ANY` / `SP_SAVECHARA` 都落到
     * `ExpressionArrayArgument`），返回非空项 —— 旧 [argTerms] 的直读等价物。
     */
    private fun argTerms(a: FlatArgs, code: FunctionCode): List<Expr> {
        openTag(a, code, ErbcFormat.ArgTag.EXPRESSION_ARRAY)
        return a.exprOptList().filterNotNull()
    }

    /** 读 `ArgType.EXPRESSIONS` 形态的记录（每项 = 类型槽 + 表达式槽），返回非空项。 */
    private fun argExpressions(a: FlatArgs, code: FunctionCode): List<Expr> {
        openTag(a, code, ErbcFormat.ArgTag.EXPRESSIONS)
        val n = a.i32()
        if (n < 0) {
            throw com.eratw.port.core.code.ErbcDecodeException(0, "语句 $code 的表达式列表长度为负: $n")
        }
        val out = ArrayList<Expr>(n)
        repeat(n) {
            a.i32() // ArgValueType 线值（执行层不消费，只按 encode 顺序跳过）
            a.expr()?.let { out.add(it) }
        }
        return out
    }

    /** SETCOLOR/SETBGCOLOR 参数（逐字复刻 [evalColor]）。spec: Process.cs:1409-1439 */
    private fun evalColorFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext): Triple<Int, Int, Int> {
        openTag(a, code, ErbcFormat.ArgTag.SP_COLOR)
        val r = a.expr()
        val g = a.expr()
        val b = a.expr()
        val rgb = a.expr()
        return if (rgb != null) {
            val v = rgb.getIntValue(ctx.eval)
            Triple(((v and 0xFF0000) shr 16).toInt(), ((v and 0xFF00) shr 8).toInt(), (v and 0xFF).toInt())
        } else {
            val rv = r?.getIntValue(ctx.eval) ?: 0L
            val gv = g?.getIntValue(ctx.eval) ?: 0L
            val bv = b?.getIntValue(ctx.eval) ?: 0L
            if (rv < 0 || gv < 0 || bv < 0) throw CodeEE("SETCOLOR的参数指定了小于0的值")
            if (rv > 255 || gv > 255 || bv > 255) throw CodeEE("SETCOLOR的参数指定了超过0的值")
            Triple(rv.toInt(), gv.toInt(), bv.toInt())
        }
    }

    /**
     * SET（赋值）直读 —— [execAssign] 逐字复刻（`SpSetArgument` / `SpSetArrayArgument` 两支）。
     *
     * 槽序：`[ASSIGN][op][dest][assignOp]` + 参数记录：
     *
     * - `[SP_SET][header][dest][term][addConst]`
     * - `[SP_SET_ARRAY][header][dest][terms][constIntList][constStrList]`
     *
     * （header bit1 时在 ArgTag 之后插 constStr/constInt 载荷 3 槽；`assignOp` 槽按旧路径语义
     * **不消费** —— 执行层不得按 `AssignStmt.op` 再套一次运算，见 [execAssign] KDoc 的
     * `X += 2` / `X *= 2` 例）。
     */
    private fun execSetFlat(source: FlatStmtSource, index: Int, ctx: ExecContext): ExecResult {
        // T2-64 波次 D：分片惰性源要求**先** headAt（定位到该语句所在的片）再取数组；
        // 反过来会拿到上一片的数组（整池形态不会暴露这个问题）。
        val head = source.headAt(index)
        val stream = source.argStreamBase()
        val destSlot = stream[head + 2]
        val dest = (if (destSlot == ErbcFormat.NONE_REF) null else source.exprAt(destSlot)) as? VarRef
            ?: throw com.eratw.port.core.code.ErbcDecodeException(head + 2, "SET 语句 #$index 的目标槽不是变量引用")
        val a = FlatArgs(source, head + 4) // [tag][op][dest][assignOp]
        when (a.open()) {
            ErbcFormat.ArgTag.SP_SET -> {
                // spec: FunctionIdentifier.cs:640-657（SET_Instruction.DoInstruction）——
                // 取值 = `IsConst ? ConstInt : Term.GetIntValue(exm)`；`AddConst` 走
                // `ChangeValue(value)`（带符号增量），否则 `SetValue(value)`。
                // 常量 `VAR += N` / `-= N` / `++` / `--` 在解析期被折成
                // `Term=null, IsConst, AddConst, ConstInt=±N`（ArgumentParser.cs:1073-1090），
                // 非常量复合赋值则由 `ReduceBinaryTerm` 把**整式**（`dest op rhs`）放进 Term
                // （ArgumentParser.cs:1092-1096；移植侧同形，ArgBuilders.kt:1384）。
                a.varRefNN() // dest 槽（开头已直读；槽序占位必须跳过：[dest][term][addConst]）
                val term = a.expr()
                val addConst = a.i32() != 0
                val isConst = a.isConst
                // PERF（2026-10-27）：落点改走 `VarRef.setIntValue/addIntValue/setStrValue`，
                // 复用读路径的访问点句柄与下标借还池 —— 这是语料里 99.9% 的语句执行，
                // 旧实现每次现分配一个 `LongArray` 再按名查一次表。
                if (dest.operandType == OperandType.INT) {
                    val value = if (isConst) a.constInt else term?.getIntValue(ctx.eval) ?: 0L
                    if (addConst) dest.addIntValue(ctx.eval, value)
                    else dest.setIntValue(ctx.eval, value)
                } else {
                    val value = if (isConst) a.constStr else term?.getStrValue(ctx.eval) ?: ""
                    dest.setStrValue(ctx.eval, value)
                }
            }
            ErbcFormat.ArgTag.SP_SET_ARRAY -> {
                // 逗号分隔的批量赋值（语料里极少：`SET X, 1, 2, 3`）。
                // 这条分支仍自带下标数组 —— `setValues` 的形态与单点写不同，接池要另验，
                // 而它在热路径上的占比可以忽略（见上方单点写的 PERF 注记）。
                // 两个常量列表是可空形态（encode 里 null 写 NONE_REF），由 FlatArgs 的可空读面覆盖
                // （契约 T2-64-C §2.1 追加读面 l64ListOpt / strListOpt）。
                val c = FlatArgs(source, head + 4, source.endAt(index))
                c.open() // ArgTag.SP_SET_ARRAY
                c.varRefNN() // dest 槽（同上，不消费）
                val terms = c.exprOptList()
                val constIntList = c.l64ListOpt()
                val constStrList = c.strListOpt()
                val name = dest.name
                val idx = resolveIndices(dest, ctx)
                if (dest.operandType == OperandType.INT) {
                    val vals = if (constIntList != null) constIntList
                    else terms.map { it?.getIntValue(ctx.eval) ?: 0L }.toLongArray()
                    ctx.variables.setValues(name, idx, vals)
                } else {
                    val vals = if (constStrList != null) constStrList
                    else terms.map { it?.getStrValue(ctx.eval) ?: "" }.toTypedArray()
                    ctx.variables.setValues(name, idx, vals)
                }
            }
            else -> shapeError(FunctionCode.SET, ErbcFormat.ArgTag.SP_SET, stream[head + 4])
        }
        return ExecResult.Normal
    }

    /**
     * PRINT 家族直读 —— [execPrint] 逐字复刻（77 个 PRINT 系 FunctionCode 共享本支，
     * 行为差异全部由 spec 三槽 argType/bits/flag 决定，与 PrintSpec 同源）。
     *
     * 槽序：`[PRINT][op][argType][printBits][flag]` + 参数记录。参数按 tag 分流：
     * SP_PRINT_V（PRINTV 家族的 terms 列表）与 EXPRESSION（其余全族的单元项）直读。
     * 字符串求值口径 = [evalStrArg] 的直读等价：`ExpressionArgument` 取 term。
     */
    private fun execPrintFlat(source: FlatStmtSource, index: Int, ctx: ExecContext): ExecResult {
        if (ctx.skipPrint) return ExecResult.Normal
        // T2-64 波次 D：分片惰性源要求**先** headAt（定位到该语句所在的片）再取数组；
        // 反过来会拿到上一片的数组（整池形态不会暴露这个问题）。
        val head = source.headAt(index)
        val stream = source.argStreamBase()
        // 槽 2..4 与 FlatStatements.readPrintSpec 的读取序一致（argType、bits、flag）。
        val argType = ArgType.entries[stream[head + 2]]
        val bits = stream[head + 3]
        val flag = stream[head + 4]
        // —— 打印窗口起点（与 execPrint 同序：UseUserStyle 先于 UseSetColorStyle）——
        // 原版在求值之前置位，FunctionIdentifier.cs:179-180 → :182-210：先写 UseUserStyle=true
        // （:179），再写 UseSetColorStyle（:180）。前者是**粘滞**开关，只有这五处 PRINT 系语句会把
        // 它写回真 —— 系统行/错误行写过假之后，要靠下一条 PRINT 才能恢复用户样式
        // （ConsoleSink.setUseUserStyle 的 KDoc）。D 系（PRINTD/PRINTVD/PRINTSD 及全部 D 后缀形态）
        // 窗口内取色回落默认前景色（EmueraConsole.cs:471-489 Style getter；
        // ConsoleSink.setUseSetColorStyle 的 KDoc）。
        ctx.console.setUseUserStyle(true)
        ctx.console.setUseSetColorStyle((bits and 0x100) == 0) // isD 位取反
        val a = FlatArgs(source, head + 5)
        val argTag = a.open()
        var str: String = if (argType == ArgType.SP_PRINTV) {
            val terms: List<Expr> = if (argTag == ErbcFormat.ArgTag.SP_PRINT_V) a.exprList() else emptyList()
            buildString {
                for (t in terms) {
                    if (t.operandType == OperandType.INT) append(t.getIntValue(ctx.eval))
                    else append(t.getStrValue(ctx.eval))
                }
            }
        } else if (argTag == ErbcFormat.ArgTag.EXPRESSION) {
            a.expr()?.getStrValue(ctx.eval) ?: ""
        } else {
            shapeError(FunctionCode.PRINT, ErbcFormat.ArgTag.EXPRESSION, argTag)
        }
        // *FORMS 语义：字符串求值后再按 FORM 解析一次（CheckEscape + AnalyseFormattedString，
        // spec: FunctionIdentifier.cs:203-209）。解析期无法预知求值结果，故经 ctx.runtimeForm 注入。
        if ((bits and 0x010) != 0) ctx.runtimeForm?.let { str = it(str) } // isForms
        // K 转换（假名/全半角）依赖 Windows VB StrConv，宿主层未提供：原样透传。
        // TODO(verify): ConvertStringType 的 StrConv 等价实现在 M5 文字流层补（spec: ExpressionMediator.cs:71-90）
        if (flag and 1024 != 0) {
            ctx.console.printSingleLine(str) // PRINTSINGLE：单行独立显示
            // PRINTSINGLE 在原版经 OutputToConsole，复位在其末尾（ExpressionMediator.cs:68）。
            ctx.console.setUseSetColorStyle(true)
            return ExecResult.Normal
        }
        if ((bits and 0x020) != 0) { // isC
            ctx.console.printC(str, alignRight = true)
        } else if ((bits and 0x040) != 0) { // isLC
            ctx.console.printC(str, alignRight = false)
        } else {
            // 行结束标记随段传递（PRINTN → false）：标记在宿主 flush 时决定日志断行与 LINECOUNT，
            // 之后 L/W 位仍触发 NewLine/ReadAnyKey（spec: ExpressionMediator.cs:59-66）。
            ctx.console.print(str, (bits and 0x001) != 0) // isLineEnd
            // spec: ExpressionMediator.cs:59-66（OutputToConsole）—— IsNewLine()||IsWaitInput() → NewLine()；
            // IsWaitInput() → ReadAnyKey()。位义：256=PRINT_NEWLINE（FunctionIdentifier.cs:3739，L 后缀）、
            // 512=PRINT_WAITINPUT（:3741，W 后缀 768=256|512）。
            // R5 勘误（A9）：**N 后缀同样置 512**（FunctionIdentifier.cs:146-151），即 PRINTN=换行且等待按键，
            // isa.yaml 原文"不换行"是错的；本实现按 flag 位走，天然覆盖 PRINTN/PRINTKW/PRINTDATAW 等全部 W/N 形态。
            // 原版怪行为（PRINTN 语义）按 ADR-005 原样复刻。
            if (flag and 256 != 0 || flag and 512 != 0) {
                ctx.console.newLine()
                // MesSkip 短路：原版 ReadAnyKey 本身无条件进 WaitInput（spec: EmueraConsole.cs:876-895），
                // 跳过实际发生在控制台输入循环——MesSkip 为真时把 NeedValue=false 且 StopMesskip=false 的
                // 等待自动吞掉（spec: EmueraConsole.cs:1451-1455）。PRINT 系等待恒为 stopMesskip=false，
                // sem 层等价语义 = mesSkip 为真时不发起等待（换行不受影响）。等待只经 ExecContext.input 表达。
                // 同 C4：PRINT 系 W 位吞掉后无条件清 MesSkip（EmueraConsole.cs:1456）。
                if (flag and 512 != 0) { if (ctx.mesSkip) ctx.mesSkip = false else readAnyKey(ctx) }
            }
        }
        // 窗口终点。spec: FunctionIdentifier.cs:227（OutputToConsole 内 ExpressionMediator.cs:68 的
        // 复位被 :227 覆盖，两处同值；非 C/LC/PRINTSINGLE 路径的 NewLine/ReadAnyKey 在窗口内）。
        ctx.console.setUseSetColorStyle(true)
        return ExecResult.Normal
    }

    /**
     * PRINTDATA 家族直读 —— [execPrintData] 逐字复刻（数据节键改**语句池下标**，契约 T2-64-C §2.2）。
     * spec: FunctionIdentifier.cs:270-320
     *
     * 参数记录：`[PRINT_DATA][header][dest 变量引用（可空）]`。
     */
    private fun execPrintDataFlat(
        source: FlatStmtSource,
        index: Int,
        code: FunctionCode,
        ctx: ExecContext,
    ): ExecResult {
        if (ctx.skipPrint) return ExecResult.Normal
        // 打印窗口起点（spec: FunctionIdentifier.cs:276-277，置位先于数据组查找——与原版同序）。
        // PRINTDATAD/PRINTDATADL/PRINTDATADW 的 D 后缀窗口内取色回落默认前景色。
        // D 位判定按 PRINT_DATA_Instruction 构造的后缀序重放（spec: FunctionIdentifier.cs:244-248：
        // 先 K 再 D，命中置 flag |= 2050；IsPrintDFunction() = (flag & 0x800) == 2048，:4240-4243）。
        // 不能复用 PrintSpec.parse：它只覆盖 PRINT_Instruction 一族（PRINTDATA 名里的
        // "DATA" 会在错误位置命中 D，且残余字符使解析返回 null）。
        val dataRest = code.name.removePrefix("PRINTDATA")
        val isD = (if (dataRest.startsWith("K")) dataRest.drop(1) else dataRest).startsWith("D")
        // 与 PRINT 家族同序：UseUserStyle=true（:276）先于 UseSetColorStyle（:277）。
        // 空数据节的 `return`（:278-282）只跳过 :317 的 UseSetColorStyle 复位，
        // **不**回滚 :276 写下的 UseUserStyle —— 后者已经生效，粘滞口径见
        // ConsoleSink.setUseUserStyle 的 KDoc。
        ctx.console.setUseUserStyle(true)
        ctx.console.setUseSetColorStyle(!isD)
        val groups = ctx.dataSections?.groupsOf(index) ?: return ExecResult.Normal
        if (groups.isEmpty()) return ExecResult.Normal
        // 原版 dataSection.Count == 0 时直接 JumpTo 返回、**不复位**取色开关
        // （FunctionIdentifier.cs:278-282 的 return 跳过了 :317），false 泄漏到下一条打印语句
        // 的窗口起点——按 ADR-005 原样复刻，上面的 ?: return 不补复位正是这个泄漏。
        val idx = nextRandom(ctx, groups.size.toLong()).toInt()
        val a = FlatArgs(source, source.headAt(index) + 2) // [tag][op] 后即参数记录
        openTag(a, code, ErbcFormat.ArgTag.PRINT_DATA)
        a.varRefOpt()?.let { writeVarRef(it, idx.toLong(), ctx) }
        val list = groups[idx]
        for ((i, e) in list.withIndex()) {
            ctx.console.print(e.getStrValue(ctx.eval))
            if (i < list.size - 1) ctx.console.newLine()
        }
        // 语句名后缀 L/W：换行 / 换行并等待按键（spec: FunctionIdentifier.cs:309-316；
        // PRINTDATAW/PRINTDATAKW/PRINTDATADW 等 W 结尾形态同路径）。
        // MesSkip 为真时等待被控制台吞掉（spec: EmueraConsole.cs:1451-1455），sem 等价：不发起等待，换行不受影响。
        val name = code.name
        if (name.endsWith("W") || name.endsWith("L")) {
            ctx.console.newLine()
            if (name.endsWith("W") && !ctx.mesSkip) readAnyKey(ctx)
        }
        // 窗口终点。spec: FunctionIdentifier.cs:317
        ctx.console.setUseSetColorStyle(true)
        return ExecResult.Normal
    }

    /** STRDATA 直读 —— [execStrData] 逐字复刻。spec: Process.cs:1801-1829 */
    private fun execStrDataFlat(source: FlatStmtSource, index: Int, ctx: ExecContext): ExecResult {
        val groups = ctx.dataSections?.groupsOf(index) ?: return ExecResult.Normal
        if (groups.isEmpty()) return ExecResult.Normal
        val idx = nextRandom(ctx, groups.size.toLong()).toInt()
        val joined = groups[idx].joinToString("\n") { it.getStrValue(ctx.eval) }
        val a = FlatArgs(source, source.headAt(index) + 3) // [tag][op][text] 后即参数记录
        openTag(a, FunctionCode.STRDATA, ErbcFormat.ArgTag.STR_DATA)
        val dest = a.varRefOpt()
        if (dest != null) writeVarRefStr(dest, joined, ctx)
        return ExecResult.Normal
    }

    /**
     * VARI/VARS 运行期直读 —— [execVarDecl] 逐字复刻：标量时以参数赋初值。
     * spec: FunctionIdentifier.cs:33-71
     */
    private fun execVarDeclFlat(
        source: FlatStmtSource,
        index: Int,
        code: FunctionCode,
        ctx: ExecContext,
    ): ExecResult {
        // 本游戏 setting.json `UseScopedVariableInstruction:false`：原版在**标识符解析期**即抛
        // CodeEE（CanNotUseVAR，spec: IdentifierDictionary.cs:624-627），语料零使用 ⇒ 语义按禁用处理。
        // 解析期门控已落地（`StmtParseConfig.useScopedVariableInstruction`，LineParser 构造参数，
        // M3-A A3）；本处是执行层兜底，覆盖"语句由其它来源构造/未走解析器门控"的路径。
        if (!ctx.config.useScopedVariableInstruction) {
            throw CodeEE("VARI/VARS 在当前设置中不可用（UseScopedVariableInstruction 已禁用）")
        }
        // 作用域进出由 VM 的 enterFunction/leaveFunction 驱动（VariableStore 注释）；
        // 语句自身只补标量初值。TODO(verify): 原版 VARI 每次执行 ScopeIn，与函数级 ScopeIn
        // 的叠加语义需对齐（重复压栈会泄漏，见 UserVar.scopeIn/Out 注释）。
        val a = FlatArgs(source, source.headAt(index) + 2)
        when (code) {
            FunctionCode.VARI -> {
                openTag(a, code, ErbcFormat.ArgTag.INT_ASIGN)
                val name = a.str()
                a.intArray() // lengths：执行层不消费，按 encode 顺序跳过
                val exp = a.expr()
                val v = ctx.variables.userVar(name) ?: return ExecResult.Normal
                if (v.spec.totalSize == 1 && exp != null) v.writeInt(0, 0, exp.getIntValue(ctx.eval))
            }
            FunctionCode.VARS -> {
                openTag(a, code, ErbcFormat.ArgTag.STR_ASIGN)
                val name = a.str()
                a.intArray() // lengths：同上
                val value = a.strOpt()
                val v = ctx.variables.userVar(name) ?: return ExecResult.Normal
                if (v.spec.totalSize == 1 && value != null) v.writeStr(0, 0, value)
            }
            else -> {}
        }
        return ExecResult.Normal
    }

    /**
     * RETURN 直读 —— [execReturn] 逐字复刻：按书写顺序求值全部项，SetResultX 只写
     * RESULT:0..n-1，不清除更高下标。无参（或空列表）写 RESULT:0=0。
     * spec: FunctionIdentifier.cs:3250-3271、VariableEvaluator.cs:2010-2016
     */
    private fun execReturnFlat(source: FlatStmtSource, index: Int, ctx: ExecContext): ExecResult {
        // 槽序：`[RETURN][op][values: n + n 槽 childOptList][参数记录]`。
        // T2-64 波次 D：分片惰性源要求**先** headAt（定位到该语句所在的片）再取数组；
        // 反过来会拿到上一片的数组（整池形态不会暴露这个问题）。
        val head = source.headAt(index)
        val stream = source.argStreamBase()
        val n = stream[head + 2]
        if (n < 0) {
            throw com.eratw.port.core.code.ErbcDecodeException(head + 2, "RETURN 语句 #$index 的项数为负: $n")
        }
        // 空列表理论不发生（ArgBuilders 已补 0），仍按原版兜底（spec: FunctionIdentifier.cs:3265-3268）。
        val values = ArrayList<Long>(n)
        for (i in 0 until n) {
            val slot = stream[head + 3 + i]
            values.add(if (slot == ErbcFormat.NONE_REF) 0L else source.exprAt(slot)!!.getIntValue(ctx.eval))
        }
        setResultX(ctx, values.ifEmpty { listOf(0L) })
        // 原版 `RETURN_Instruction.DoInstruction` 的顺序是「先 SetResultX，再
        // `state.Return(RESULT:0)`」（spec: FunctionIdentifier.cs:3265-3271）；而
        // `ProcessState.Return` 的**第一条**是 `if (IsFunctionMethod) { ReturnF(null); return; }`
        // （spec: ProcessState.cs:289-295）—— 方法帧内 `RETURN x` 的方法返回值是 **null**
        // （消费侧 `SuperUserDefinedMethodTerm.GetIntValue` 得 0，`…#Term.cs:24-31`），
        // 不是 RESULT:0 里的 x。RESULT 数组本身照旧被上面写满，只有「方法返回值」这一路被清。
        if (ctx.isFunctionMethodFrame()) return ExecResult.Return(0L)
        // 普通函数帧：原版 state.Return(exm.VEvaluator.RESULT)——写后读 RESULT:0（:3270）
        return ExecResult.Return(ctx.variables.readInt(RESULT, longArrayOf(0L)))
    }

    /**
     * RETURNFORM 直读 —— [execReturnForm] 逐字复刻：FORM 串已展开为逗号分隔的整数表达式
     * （如 "11, 3"），逐段求值后同 SetResultX。spec: FunctionIdentifier.cs:3221-3238
     */
    private fun execReturnFormFlat(source: FlatStmtSource, index: Int, ctx: ExecContext): ExecResult {
        val head = source.headAt(index)
        val stream = source.argStreamBase()
        val slot = stream[head + 2]
        val text = if (slot == ErbcFormat.NONE_REF) "" else source.exprAt(slot)?.getStrValue(ctx.eval) ?: ""
        val parse = ctx.runtimeIntList
            ?: throw CodeEE("RETURNFORM 需要宿主提供运行期整数列表解析（ExecContext.runtimeIntList）")
        // 空串/空列表补 0（spec: FunctionIdentifier.cs:3232-3235）。
        val values = parse(text).ifEmpty { listOf(0L) }
        setResultX(ctx, values)
        return ExecResult.Return(ctx.variables.readInt(RESULT, longArrayOf(0L)))
    }

    // ==================================================================
    // T2-64 波次 C：INSTRUCTION 族各语句的直读实现（旧 execXxx 的逐字复刻）
    // ==================================================================

    /**
     * PRINTBUTTON 直读 —— [execPrintButton] 逐字复刻。spec: Process.cs:1107-1144
     *
     * 槽序：`[SP_BUTTON][header][printStrTerm][buttonWord]`。
     */
    private fun execPrintButtonFlat(code: FunctionCode, a: FlatArgs, ctx: ExecContext): ExecResult {
        if (ctx.skipPrint) return ExecResult.Normal
        // 原版在求值前显式把两条开关都复位为 true（spec: Process.cs:1110-1111 / :1129-1130）：
        // 这会把上一条异常泄漏出来的 D 系窗口（false）就地清除，按钮不吃 D 配色回落；
        // UseUserStyle 同点写真，按钮因此是「系统行之后第一条恢复用户样式的语句」之一。
        ctx.console.setUseUserStyle(true)
        ctx.console.setUseSetColorStyle(true)
        openTag(a, code, ErbcFormat.ArgTag.SP_BUTTON)
        val printStrTerm = a.expr()
        val word = a.expr()
        val text = evalStr(printStrTerm, ctx).replace("\n", "")
        when (code) {
            FunctionCode.PRINTBUTTON ->
                if (word == null || word.operandType == OperandType.INT) {
                    ctx.console.printButton(text, word?.getIntValue(ctx.eval) ?: 0L)
                } else {
                    ctx.console.printButton(text, word.getStrValue(ctx.eval))
                }
            else -> {
                val right = code == FunctionCode.PRINTBUTTONC
                if (word == null || word.operandType == OperandType.INT) {
                    ctx.console.printButtonC(text, word?.getIntValue(ctx.eval) ?: 0L, right)
                } else {
                    ctx.console.printButtonC(text, word.getStrValue(ctx.eval), right)
                }
            }
        }
        return ExecResult.Normal
    }

    /** INPUT/INPUTS/ONEINPUT/ONEINPUTS 直读 —— [execInputs] 逐字复刻。spec: FunctionIdentifier.cs:827-1001 */
    private fun execInputsFlat(code: FunctionCode, a: FlatArgs, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_INPUTS)
        val def = a.expr()
        val mouseTerm = a.expr()
        val canSkip = a.expr()
        val isStr = code == FunctionCode.INPUTS || code == FunctionCode.ONEINPUTS
        // CanSkip 且消息跳过中：直接采用默认值（spec: FunctionIdentifier.cs:853-863）。
        // 原版此处 Mouse==null 会 NPE（ARG 缺陷）；按 ADR-005 例外条款（进程崩溃类）取 0 处理。
        if (canSkip != null && ctx.mesSkip) {
            val mouse = mouseTerm?.getIntValue(ctx.eval) ?: 0L
            val slot = longArrayOf(if (mouse == 0L) 0L else 1L)
            if (isStr) ctx.variables.writeStr(RESULTS, slot, def?.getStrValue(ctx.eval) ?: "")
            else ctx.variables.writeInt(RESULT, slot, def?.getIntValue(ctx.eval) ?: 0L)
            return
        }
        val req = InputRequest(
            type = if (isStr) InputType.STR else InputType.INT,
            oneInput = code == FunctionCode.ONEINPUT || code == FunctionCode.ONEINPUTS,
            hasDef = def != null,
            defInt = def?.getIntValue(ctx.eval) ?: 0L,
            defStr = if (isStr) def?.getStrValue(ctx.eval) ?: "" else "",
            mouseInput = mouseTerm?.getIntValue(ctx.eval) != 0L,
        )
        val o = waitInput(ctx, req)
        updateIsTimeout(ctx, req, o)
        if (isStr) ctx.variables.writeStr(RESULTS, longArrayOf(0), o.strValue)
        else ctx.variables.writeInt(RESULT, longArrayOf(0), o.intValue)
    }

    /** TINPUT/TINPUTS/TONEINPUT/TONEINPUTS 直读 —— [execTInputs] 逐字复刻。spec: FunctionIdentifier.cs:1003-1099 */
    private fun execTInputsFlat(code: FunctionCode, a: FlatArgs, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_TINPUTS)
        val timeTerm = a.expr()
        val def = a.expr()
        val disp = a.expr()
        val timeout = a.expr()
        val mouseTerm = a.expr()
        val canSkip = a.expr()
        val isStr = code == FunctionCode.TINPUTS || code == FunctionCode.TONEINPUTS
        if (canSkip != null && ctx.mesSkip) {
            val mouse = mouseTerm?.getIntValue(ctx.eval) ?: 0L
            val slot = longArrayOf(if (mouse == 0L) 0L else 1L)
            if (isStr) ctx.variables.writeStr(RESULTS, slot, def?.getStrValue(ctx.eval) ?: "")
            else ctx.variables.writeInt(RESULT, slot, def?.getIntValue(ctx.eval) ?: 0L)
            return
        }
        val req = InputRequest(
            type = if (isStr) InputType.STR else InputType.INT,
            oneInput = code == FunctionCode.TONEINPUT || code == FunctionCode.TONEINPUTS,
            hasDef = true,
            defInt = def?.getIntValue(ctx.eval) ?: 0L,
            defStr = if (isStr) def?.getStrValue(ctx.eval) ?: "" else "",
            timeLimit = timeTerm?.getIntValue(ctx.eval) ?: 0L,
            displayTime = (disp?.getIntValue(ctx.eval) ?: 1L) != 0L,
            timeUpMessage = timeout?.let { evalStr(it, ctx) } ?: ctx.config.timeupLabel,
            mouseInput = mouseTerm?.getIntValue(ctx.eval) == 1L,
        )
        val o = waitInput(ctx, req)
        updateIsTimeout(ctx, req, o)
        if (isStr) ctx.variables.writeStr(RESULTS, longArrayOf(0), o.strValue)
        else ctx.variables.writeInt(RESULT, longArrayOf(0), o.intValue)
    }

    /**
     * BINPUT/BINPUTS/ONEBINPUT/ONEBINPUTS 直读 —— [execBInputs] 逐字复刻。
     * spec: FunctionIdentifier.cs:2113-2529，四语句同构
     * （BINPUT :2121-2214、BINPUTS :2225-2318、ONEBINPUT :2329-2423、ONEBINPUTS :2434-2527）。
     * 1. 仅当打印缓冲有待决内容时先换行：if (!PrintBuffer.IsEmpty) NewLine()
     *    （:2123-2126/:2227-2230/:2331-2334/:2436-2439）；空态由宿主上报
     *    （[ConsoleSink.printingBufferEmpty]）。
     * 2. MesSkip 快速路径：CanSkip 且消息跳过中，不等待输入，按 mouse 选槽直接写默认值
     *    （:2145-2155/:2249-2259/:2354-2364/:2459-2469）：mouse==0 写 slot 0（RESULT/RESULTS），
     *    否则写 slot 1（RESULT_ARRAY[1]/RESULTS_ARRAY[1]）。C# 此处对 Mouse/Def 无条件解引用
     *    （缺参即 NRE 的 ARG 缺陷）；按 ADR-005 例外条款（进程崩溃类）取 0/"" 处理。
     * 3. 其余路径 C# 按控制台按钮扫描决定 WaitInput 或 num==0 兜底写默认值（:2156-2213 等）；
     *    按钮布局检测依赖控制台显示状态（M4 文字流层），当前统一走输入等待。
     *    RefreshStrings(:2127)/ApplyTextBoxChanges(:2143) 为绘制层动作，VM 侧不建模。
     * TODO(verify): 无按钮且有/无默认值的分支需要 console 按钮状态。
     */
    private fun execBInputsFlat(code: FunctionCode, a: FlatArgs, ctx: ExecContext) {
        // 条件换行（spec: FunctionIdentifier.cs:2123-2126）：缓冲已 flush 时不补空行。
        if (!ctx.console.printingBufferEmpty) ctx.console.newLine()
        openTag(a, code, ErbcFormat.ArgTag.SP_INPUTS)
        val def = a.expr()
        val mouseTerm = a.expr()
        val canSkip = a.expr()
        val isStr = code == FunctionCode.BINPUTS || code == FunctionCode.ONEBINPUTS
        // CanSkip 且消息跳过中：直接采用默认值（spec: FunctionIdentifier.cs:2145-2155）。
        if (canSkip != null && ctx.mesSkip) {
            val mouse = mouseTerm?.getIntValue(ctx.eval) ?: 0L
            val slot = longArrayOf(if (mouse == 0L) 0L else 1L)
            if (isStr) ctx.variables.writeStr(RESULTS, slot, def?.getStrValue(ctx.eval) ?: "")
            else ctx.variables.writeInt(RESULT, slot, def?.getIntValue(ctx.eval) ?: 0L)
            return
        }
        val req = InputRequest(
            type = if (isStr) InputType.STR else InputType.INT,
            oneInput = code == FunctionCode.ONEBINPUT || code == FunctionCode.ONEBINPUTS,
            hasDef = def != null,
            defInt = def?.getIntValue(ctx.eval) ?: 0L,
            defStr = if (isStr) def?.getStrValue(ctx.eval) ?: "" else "",
            mouseInput = mouseTerm?.getIntValue(ctx.eval) != 0L,
        )
        val o = waitInput(ctx, req)
        updateIsTimeout(ctx, req, o)
        if (isStr) ctx.variables.writeStr(RESULTS, longArrayOf(0), o.strValue)
        else ctx.variables.writeInt(RESULT, longArrayOf(0), o.intValue)
    }

    /** SETBIT/CLEARBIT/INVERTBIT 直读 —— [execBit] 逐字复刻。spec: FunctionIdentifier.cs:731-760 */
    private fun execBitFlat(code: FunctionCode, a: FlatArgs, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.BIT)
        val dest = a.varRefNN()
        val terms = a.exprList()
        val op = when (code) {
            FunctionCode.SETBIT -> 1
            FunctionCode.CLEARBIT -> 0
            else -> 2
        }
        val name = dest.name
        val idx = resolveIndices(dest, ctx)
        for (t in terms) {
            val bit = t.getIntValue(ctx.eval)
            if (bit < 0 || bit > 63) throw CodeEE("第2参数($bit)超出范围(0-63)")
            var v = ctx.variables.readInt(name, idx)
            val mask = 1L shl bit.toInt()
            v = when (op) {
                1 -> v or mask
                0 -> v and mask.inv()
                else -> v xor mask
            }
            ctx.variables.writeInt(name, idx, v)
        }
    }

    /** SWAP 直读 —— [execSwap] 逐字复刻。spec: Process.cs:1371-1395 */
    private fun execSwapFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_SWAP_VAR)
        val var1 = a.varRefNN()
        val var2 = a.varRefNN()
        val n1 = var1.name
        val i1 = resolveIndices(var1, ctx)
        val n2 = var2.name
        val i2 = resolveIndices(var2, ctx)
        if (var1.operandType != var2.operandType) throw CodeEE("需要替换的变量的类型不相同")
        if (var1.operandType == OperandType.INT) {
            val v = ctx.variables.readInt(n1, i1)
            ctx.variables.writeInt(n1, i1, ctx.variables.readInt(n2, i2))
            ctx.variables.writeInt(n2, i2, v)
        } else {
            val v = ctx.variables.readStr(n1, i1)
            ctx.variables.writeStr(n1, i1, ctx.variables.readStr(n2, i2))
            ctx.variables.writeStr(n2, i2, v)
        }
    }

    /** VARSET 直读 —— [execVarSet] 逐字复刻。spec: FunctionIdentifier.cs:1625-1669 */
    private fun execVarSetFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_VAR_SET)
        val dest = a.varRefNN()
        val term = a.expr()
        val startTerm = a.expr()
        val endTerm = a.expr()
        val name = dest.name
        val idx = resolveIndices(dest, ctx)
        val info = ctx.variables.resolveVarInfo(name)
        val isStr = dest.operandType == OperandType.STR
        var start = startTerm?.getIntValue(ctx.eval)?.toInt() ?: 0
        var end = when {
            endTerm != null -> endTerm.getIntValue(ctx.eval).toInt()
            (info?.dimension ?: 1) == 1 -> ctx.variables.getLength(name)
            else -> 0
        }
        // C# 只在 Start != null 时才比较/交换（FunctionIdentifier.cs:1642-1656）：负 END + 缺 START
        // 以 (0, 负值) 进 SetValueAll ⇒ 循环不执行 = no-op。此前无条件交换会把负 END 换成负起点
        // （2026-10-26 fixcore 审查 C6；语料无负 END 用法，纯对齐）。
        if (startTerm != null && start > end) {
            val t = start; start = end; end = t
        }
        val charaPos = if (info?.isCharacterData == true && idx.isNotEmpty()) idx[0].toInt() else -1
        if (isStr) {
            ctx.variables.setAllStr(name, term?.getStrValue(ctx.eval) ?: "", start, end, charaPos)
        } else {
            ctx.variables.setAll(name, term?.getIntValue(ctx.eval) ?: 0L, start, end, charaPos)
        }
    }

    /**
     * CVARSET 直读 —— [execCVarSet] 逐字复刻。spec: FunctionIdentifier.cs:1671-1732 + VariableEvaluator.cs:317-378。
     *
     * 与 VARSET 的唯一差别是**写哪个元素**：第 2 参是元素下标，对角色区间内每个角色写
     * `[角色, 元素]`（`SetValueAllEachChara` 的 `new long[2] { i, num }`，
     * VariableEvaluator.cs:337-343、:377-378）。旧实现把元素下标塞进 `idx` 后只保留
     * 第 1 位，角色变量因此永远只得到 1 个下标而被 `checkElement` 拒绝。
     *
     * 语义逐条对齐原文（**区间是半开 `[start, end)`**，不是 VARSET 的闭区间）：
     * - `start` 缺省 0，显式时必须在 `[0, CHARANUM)` 内（FunctionIdentifier.cs:1684-1692）；
     * - `end` 缺省 CHARANUM，显式时必须在 `[0, CHARANUM]` 内（:1693-1704，端点可等于角色数）；
     * - `start > end` 交换（:1707-1712）；
     * - 1 维角色变量的元素下标是字符串时走 `keywordToInteger`（VariableEvaluator.cs:331-336、
     *   :368-374），并做 `0 <= elem < 数组长度` 检查（OoRCharaVar）；
     * - 0 维角色变量的下标原版**被忽略**（`SetValue` 只读 `arguments[0]`，
     *   VariableData.cs:425-429、:510-513），所以这里只传角色位置。
     *
     * 原文 bug 一并复刻：`end` 越界的报错文案回填的是 `start`（FunctionIdentifier.cs:1700
     * 的第二个占位符写的是 `num2`）。
     */
    private fun execCVarSetFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_CVAR_SET)
        val dest = a.varRefNN()
        val indexTerm = a.expr()
        val term = a.expr()
        val startTerm = a.expr()
        val endTerm = a.expr()
        // 原版 GetFixedVariableTerm 会求值目标变量的固定下标；CVARSET 不使用该结果
        // （SetValueAllEachChara 只按 i 与元素下标写），这里保留求值副作用。
        val name = dest.name
        val info = ctx.variables.resolveVarInfo(name)
            ?: throw CodeEE("未定义的变量 $name")

        // 原版先 `Index.GetValue(exm)` 再取 CHARANUM、校验 start/end（FunctionIdentifier.cs:1683-1706）；
        // 0 维变量只忽略结果，不跳过求值（可能含 RAND 等副作用）。
        val indexIsStr = indexTerm != null && indexTerm.operandType == OperandType.STR
        val indexInt = if (indexTerm != null && !indexIsStr) indexTerm.getIntValue(ctx.eval) else 0L
        val indexStr = if (indexTerm != null && indexIsStr) indexTerm.getStrValue(ctx.eval) else ""

        val charaNum = ctx.variables.characters.count
        var start = 0
        if (startTerm != null) {
            start = startTerm.getIntValue(ctx.eval).toInt()
            if (start < 0 || start >= charaNum) {
                throw CodeEE("CVARSET命令的第4参数($start)超出角色范围")
            }
        }
        var end = charaNum
        if (endTerm != null) {
            end = endTerm.getIntValue(ctx.eval).toInt()
            if (end < 0 || end > charaNum) {
                // 原文 bug：这里填的是 start（num2），不是刚求出的 end（num3）。
                throw CodeEE("CVARSET命令的第5参数($start)超出角色范围")
            }
        }
        if (start > end) {
            val t = start
            start = end
            end = t
        }

        if (!info.isCharacterData) throw CodeEE("CVARSET命令使用了一个非角色变量")

        // 写入值只求一次（原版在 Instruction 里先求 Term，再交给 SetValueAllEachChara）。
        val isStr = dest.operandType == OperandType.STR
        val intValue = if (isStr) 0L else term?.getIntValue(ctx.eval) ?: 0L
        val strValue = if (isStr) term?.getStrValue(ctx.eval) ?: "" else ""

        // SetValueAllEachChara 在 CharacterList.Count == 0 时提前返回（VariableEvaluator.cs:327-330），
        // 连元素下标都不校验；只有显式 start 会先撞上上面的区间检查。
        if (charaNum == 0) return

        // 只有 1 维角色变量才解析/校验元素下标；0 维变量原版忽略它（见 KDoc）。
        val hasElement = info.dimension == 1
        var elem = 0
        if (hasElement) {
            elem = if (indexIsStr) {
                ctx.variables.keywordToInteger(name, 1, indexStr).toInt()
            } else {
                indexInt.toInt()
            }
            val len = ctx.variables.getLength(name)
            if (elem < 0 || elem >= len) {
                throw CodeEE("角色数组变量\"$name\"的第2参数($elem)超出数组范围")
            }
        }

        val full = LongArray(if (hasElement) 2 else 1)
        if (hasElement) full[1] = elem.toLong()
        for (c in start until end) {
            full[0] = c.toLong()
            if (isStr) ctx.variables.writeStr(name, full.copyOf(), strValue)
            else ctx.variables.writeInt(name, full.copyOf(), intValue)
        }
    }

    /** VARSIZE 直读 —— [execVarSize] 逐字复刻。spec: Process.cs:1321-1326 + VariableEvaluator.cs:1932-1950 */
    private fun execVarSizeFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_VARSIZE)
        val name = a.str()
        val info = ctx.variables.resolveVarInfo(name) ?: throw CodeEE("未定义的变量 $name")
        if (info.dimension >= 2) {
            for (d in 0 until info.dimension) {
                ctx.variables.writeInt(RESULT, longArrayOf(d.toLong()), ctx.variables.getLength(name, d).toLong())
            }
        } else {
            ctx.variables.writeInt(RESULT, longArrayOf(0), ctx.variables.getLength(name).toLong())
        }
    }

    /** POWER 直读 —— [execPower] 逐字复刻。spec: Process.cs:1350-1370 */
    private fun execPowerFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_POWER)
        val dest = a.varRefNN()
        val xTerm = a.expr()
        val yTerm = a.expr()
        val x = xTerm?.getIntValue(ctx.eval)?.toDouble() ?: 0.0
        val y = yTerm?.getIntValue(ctx.eval)?.toDouble() ?: 0.0
        val r = Math.pow(x, y)
        if (r.isNaN()) throw CodeEE("幂运算结果不是数值")
        if (r.isInfinite()) throw CodeEE("幂运算结果无限大")
        if (r >= 9.223372036854776E18 || r <= -9.223372036854776E18) {
            throw CodeEE("幂运算结果($r)超过64位带符号整数的有效范围")
        }
        writeVarRef(dest, r.toLong(), ctx)
    }

    /**
     * TIMES 直读 —— [execInstruction] 的 TIMES 分支逐字复刻。spec: FunctionIdentifier.cs:1314-1342
     *
     * 槽序：`[SP_TIMES][header][dest][doubleValue（2 槽 raw bits）]`。
     */
    private fun execTimesFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext): ExecResult {
        openTag(a, code, ErbcFormat.ArgTag.SP_TIMES)
        val dest = a.varRefNN()
        val doubleValue = a.f64()
        // 双路径按 Config.TimesNotRigorousCalculation 分派（ConfigData.cs:111 出厂 false，
        // 本游戏 emuera.config「IMITATE BEHAVIOR FOR TIMES:NO」同值）：
        // - 默认（:1326-1338）：`(decimal)IntValue * (decimal)DoubleValue` —— decimal 携带
        //   double 的**精确二进制值**（.NET Decimal(Double) 保留到 28 位有效数字），
        //   乘积落在 long 域内向零截断；这正是 |IntValue| > 2^53 时与 double 乘路径
        //   可观测分叉的来源（double 只有 53 位尾数，(double)IntValue 本身先失真）。
        // - eramaker 模拟（:1322-1325）：`(double)IntValue * DoubleValue` 后 `(long)` 截断。
        // `(long)num2` 越界分支的 `(long)(double)num2`（:1339-1341）在 C# x64 产「整数不定值」
        // = Long.MIN_VALUE（cvttsd2si；JLS 5.1.3 的饱和语义正溢出给 MAX_VALUE，两侧分叉）——
        // 两个分支都按 MIN_VALUE 复刻（2026-10-26 fixcore 审查 B4，替代旧「沿饱和」TODO）。
        val name = dest.name
        val idx = resolveIndices(dest, ctx)
        val cur = ctx.variables.readInt(name, idx)
        if (ctx.config.timesNotRigorousCalculation) {
            val r = cur.toDouble() * doubleValue
            // eramaker 路径的 `(long)num` 同为 x64 不定值口径：NaN / ≥2^63 一律 Long.MIN_VALUE
            // （负溢出 JVM 饱和本就给 MIN_VALUE，与 x64 一致，无需特判）。
            ctx.variables.writeInt(
                name, idx,
                if (r.isNaN() || r >= 9.223372036854776E18) Long.MIN_VALUE else r.toLong(),
            )
        } else {
            // (decimal)DoubleValue：.NET Decimal(Double) 把 double 的精确值舍入到
            // 28 位有效数字（banker's rounding）⇒ BigDecimal(exact).round(28, HALF_EVEN)。
            // TODO(verify): 乘积超过 28 位有效数字时 .NET decimal 乘法的舍入方向
            // （HALF_EVEN vs HALF_UP）未逐行取证，仅在 |IntValue| ≳ 1e10 且倍数含长尾
            // 时才可能触到该第 28 位，语料 TIMES（3143 处）无此量级读数。
            val d = java.math.BigDecimal(doubleValue)
                .round(java.math.MathContext(28, java.math.RoundingMode.HALF_EVEN))
            val product = java.math.BigDecimal(cur).multiply(d)
            val r = if (product <= java.math.BigDecimal(java.lang.Long.MAX_VALUE) &&
                product >= java.math.BigDecimal(java.lang.Long.MIN_VALUE)
            ) {
                product.setScale(0, java.math.RoundingMode.DOWN).longValueExact()
            } else {
                // C# x64 `(long)(double)` 越界 = cvttsd2si 的「整数不定值」= Long.MIN_VALUE
                // （正溢出也得 MIN_VALUE；JLS 5.1.3 饱和语义正溢出给 MAX_VALUE —— 两侧不同，
                // 2026-10-26 fixcore 审查 B4 钉死）。
                Long.MIN_VALUE
            }
            ctx.variables.writeInt(name, idx, r)
        }
        return ExecResult.Normal
    }

    /** ENCODETOUNI 直读 —— [execEncodeToUni] 逐字复刻。spec: Process.cs:1773-1789（写 RESULT 数组） */
    private fun execEncodeToUniFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext) {
        val text = evalStr(argTerm(a, code), ctx)
        val cap = ctx.variables.getLength(RESULT)
        if (text.length > cap - 1) {
            throw CodeEE("ENCODETOUNI的参数过长（目前共${text.length}字符。最大${cap - 1}字符）")
        }
        var i = 0
        var off = 0
        while (off < text.length) {
            val cp = text.codePointAt(off)
            // RESULT[0] = 字符数，码点从 RESULT[1] 起（spec: VariableEvaluator.cs:1990-1998）
            ctx.variables.writeInt(RESULT, longArrayOf((i + 1).toLong()), cp.toLong())
            off += Character.charCount(cp)
            i++
        }
        ctx.variables.writeInt(RESULT, longArrayOf(0L), i.toLong())
    }

    /** SPLIT 直读 —— [execSplit] 逐字复刻。spec: Process.cs:1561-1576 */
    private fun execSplitFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_SPLIT)
        val targetTerm = a.expr()
        val splitTerm = a.expr()
        val destVarName = a.str()
        val numTerm = a.varRefOpt()
        val target = evalStr(targetTerm, ctx)
        val sep = evalStr(splitTerm, ctx)
        val parts = if (sep.isEmpty()) listOf(target) else target.split(sep)
        numTerm?.let { writeVarRef(it, parts.size.toLong(), ctx) }
        val info = ctx.variables.resolveVarInfo(destVarName)
            ?: throw CodeEE("未定义的变量 $destVarName")
        val dim = maxOf(info.dimension, 1)
        val idx = LongArray(dim)
        val cap = ctx.variables.getLength(destVarName)
        val n = minOf(parts.size, cap)
        for (i in 0 until n) {
            idx[dim - 1] = i.toLong()
            ctx.variables.writeStr(destVarName, idx.copyOf(), parts[i])
        }
    }

    /** ARRAYSHIFT 直读 —— [execArrayShift] 逐字复刻。spec: Process.cs:1614-1660 + VariableEvaluator.cs:707-773 */
    private fun execArrayShiftFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_ARRAY_SHIFT)
        val dest = a.varRefNN()
        val num1 = a.expr()
        val num2 = a.expr()
        val num3 = a.expr()
        val num4 = a.expr()
        val name = dest.name
        val idx = resolveIndices(dest, ctx)
        val isStr = dest.operandType == OperandType.STR
        // spec: Process.cs:1617-1620 —— 运行期先判 `VarToken.Identifier.IsArray1D`，不成立抛
        // `IsUsableOnly1DVar`（lang/emuera.zhs.xml:723 = `{0}仅对1位数组以及数组型角色变量有效`，
        // {0} 原位填 "ARRAYSHIFT"）。常规装载链上该形态已被 SP_SHIFT_ARRAY_ArgumentBuilder
        // （ArgumentParser.cs:2131）以装载告警挡下（语句不生成），本守卫覆盖「无符号表」的裸构造
        // 执行路径（单测/降级装载），与 C# 运行期判据同层。
        val info = ctx.variables.resolveVarInfo(name)
        if (info != null && info.dimension != 1) {
            throw CodeEE("ARRAYSHIFT仅对1位数组以及数组型角色变量有效")
        }
        val shift = num1?.getIntValue(ctx.eval)?.toInt() ?: 0
        // 原版 Math.Abs(shift)（VariableEvaluator.cs:722/:732）对 Int32.MinValue 抛
        // OverflowException 停机；java.lang.Math.abs 不抛（回绕为 MIN_VALUE）→ 补同款崩溃面，
        // 守卫口径参照 ABS 函数（FMC.cs:4147-4150 ↔ PureMethods ABS MinInt64）
        // （2026-10-26 fixcore 审查 B5）。
        if (shift == Int.MIN_VALUE) throw CodeEE("ARRAYSHIFT函数: 第2个参数溢出（$shift）")
        if (shift == 0) return
        val start = num3?.getIntValue(ctx.eval)?.toInt() ?: 0
        if (start < 0) throw CodeEE("ARRAYSHIFT函数: 第4个参数被是负值($start)")
        val end: Int = if (num4 != null) {
            val e = num4.getIntValue(ctx.eval).toInt()
            if (e < 0) throw CodeEE("ARRAYSHIFT函数: 第5个参数被是负值($e)")
            if (e == 0) return
            e
        } else {
            -1
        }
        val arr = array1D(ctx, name, idx, isStr)
        val len = arr.size
        // 文案照抄官方 zhs：`Lang.Error.OoRArrayShift`（lang/emuera-zhs.xml:309）
        // = `命令ARRAYSHIFT的第四个参数({0})超出了数组"{1}"的范围`；C# 抛点
        // VariableEvaluator.cs:710-713（整数）/ :779-782（字符串），占位符 = (start, 变量名)。
        if (start >= len) throw CodeEE("命令ARRAYSHIFT的第四个参数($start)超出了数组\"$name\"的范围")
        var num = if (end == -1) len - start else end
        if (start + num > len) num = len - start
        val fill = if (isStr) (num2?.getStrValue(ctx.eval) ?: "") else (num2?.getIntValue(ctx.eval) ?: 0L)
        if (Math.abs(shift) >= len && start == 0 && num >= len) {
            for (i in 0 until len) arr[i] = fill
            writeBack1D(ctx, name, idx, arr, isStr)
            return
        }
        var srcOff = 0
        var dstOff = start + shift
        val keep = num - Math.abs(shift)
        if (shift < 0) {
            srcOff = -shift
            dstOff = start
        }
        val copy = arr.subList(start, start + num).toMutableList()
        if (shift > 0) {
            if (keep <= 0) {
                for (j in start until start + num) arr[j] = fill
                writeBack1D(ctx, name, idx, arr, isStr)
                return
            }
            for (k in start until start + shift) arr[k] = fill
        } else {
            if (keep <= 0) {
                for (l in start until start + num) arr[l] = fill
                writeBack1D(ctx, name, idx, arr, isStr)
                return
            }
            for (m in start + keep until start + num) arr[m] = fill
        }
        if (keep > 0) {
            for (i in 0 until keep) arr[dstOff + i] = copy[srcOff + i]
        }
        writeBack1D(ctx, name, idx, arr, isStr)
    }

    /** ARRAYREMOVE 直读 —— [execArrayRemove] 逐字复刻。spec: Process.cs:1661-1677 + VariableEvaluator.cs:845-887 */
    private fun execArrayRemoveFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_ARRAY_CONTROL)
        val dest = a.varRefNN()
        val num1 = a.expr()
        val num2 = a.expr()
        val name = dest.name
        val idx = resolveIndices(dest, ctx)
        val isStr = dest.operandType == OperandType.STR
        // spec: Process.cs:1663-1668 —— ARRAYREMOVE 的装载期 SP_CONTROL_ARRAY_ArgumentBuilder
        // （ArgumentParser.cs:2076-2110）**不做** 1 维校验（与 ARRAYSHIFT/ARRAYSORT 不同），
        // 因此 C# 运行期的 `IsArray1D` 拒否是**语料可达**的：非 1 维（0 维标量/2 维/3 维）
        // 抛 `IsUsableOnly1DVar`（zhs:723）。旧实现直接按 1 维口径读写，二维变量会被静默错读。
        val info = ctx.variables.resolveVarInfo(name)
        if (info != null && info.dimension != 1) {
            throw CodeEE("ARRAYREMOVE仅对1位数组以及数组型角色变量有效")
        }
        val start = num1?.getIntValue(ctx.eval)?.toInt() ?: 0
        if (start < 0) throw CodeEE("ARRAYREMOVE函数: 第2个参数被是负值($start)")
        val arr = array1D(ctx, name, idx, isStr)
        val len = arr.size
        // 文案照抄官方 zhs：`Lang.Error.OoRArrayRemove`（lang/emuera-zhs.xml:310）
        // = `命令ARRAYREMOVE的第二个参数({0})超出了数组"{1}"的范围`；C# 抛点
        // VariableEvaluator.cs:850-853 —— **只在整数分支**判 start 越界（字符串分支不判，
        // 直接按 num<=0→num=length 走 BlockCopy），故本侧同样 `!isStr` 才判。
        if (!isStr && start >= len) throw CodeEE("命令ARRAYREMOVE的第二个参数($start)超出了数组\"$name\"的范围")
        var num = num2?.getIntValue(ctx.eval)?.toInt() ?: 0
        if (num <= 0) num = len
        val out = ArrayList<Any>(len)
        for (i in 0 until start) out.add(arr[i])
        for (i in start + num until len) out.add(arr[i])
        while (out.size < len) out.add(if (isStr) "" else 0L)
        writeBack1D(ctx, name, idx, out, isStr)
    }

    /** ARRAYSORT 直读 —— [execArraySort] 逐字复刻。spec: Process.cs:1678-1710 + VariableEvaluator.cs:889-958 */
    private fun execArraySortFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_ARRAY_SORT)
        val dest = a.varRefNN()
        val order = a.sortOrder()
        val num1 = a.expr()
        val num2 = a.expr()
        val name = dest.name
        val idx = resolveIndices(dest, ctx)
        val isStr = dest.operandType == OperandType.STR
        // spec: Process.cs:1680-1685 —— 运行期 `IsArray1D` 拒否（装载期 ArgumentParser.cs:645
        // 已挡下，此处为无符号表裸构造路径的兜底），文案 IsUsableOnly1DVar（zhs:723）。
        val info = ctx.variables.resolveVarInfo(name)
        if (info != null && info.dimension != 1) {
            throw CodeEE("ARRAYSORT仅对1位数组以及数组型角色变量有效")
        }
        val start = num1?.getIntValue(ctx.eval)?.toInt() ?: 0
        if (start < 0) throw CodeEE("ARRAYSORT函数: 第3个参数被是负值($start)")
        val arr = array1D(ctx, name, idx, isStr)
        val len = arr.size
        // 第 4 参缺省 = -1（SortArray 视作「排到数组末尾」）；显式 0 原版直接 break，
        // 显式负值抛 ArgIsNegative（Process.cs:1691-1707）。
        var count = num2?.getIntValue(ctx.eval)?.toInt() ?: -1
        if (num2 != null) {
            if (count < 0) {
                // ADR-005：原版这条消息把 start（num8）当负值印出来（Process.cs:1697），原样复刻。
                throw CodeEE("ARRAYSORT函数: 第4个参数被是负值($start)")
            }
            // 显式 0 在范围检查之前就跳过整条语句（Process.cs:1699-1702）。
            if (count == 0) return
        }
        // 文案照抄官方 zhs：`Lang.Error.OoRArraySort`（lang/emuera-zhs.xml:311）
        // = `命令ARRAYSORT的第三参数({0})超出了数组"{1}"的范围`；C# 抛点
        // VariableEvaluator.cs:898-901（整数）/ :923-926（字符串），占位符 = (start, 变量名)。
        if (start >= len) throw CodeEE("命令ARRAYSORT的第三参数($start)超出了数组\"$name\"的范围")
        if (count <= 0) count = len - start
        if (start + count > len) {
            // C# 抛点 VariableEvaluator.cs:908-910 / :933-935 用 `Lang.Error.OoRSumArray`，
            // 但 zhs 语言包**没有**该键（`grep OoRSumArray lang/emuera-zhs.xml` 0 命中）
            // ⇒ 官方 zhs 运行时回落 Lang.cs:3622 的日文默认串（TranslatableString 无译文即
            // 保留默认）。照抄纪律 ADR-005：此处也照抄该日文默认（占位符 = start, count, 变量名）。
            throw CodeEE("命令ARRAYSORTの第３引数($start)と第4変数($count)の和が配列\"$name\"の範囲を超えています")
        }
        val region = arr.subList(start, start + count)
        if (isStr) {
            region.sortWith(compareBy { it as String })
            if (order == SortOrder.DESENDING) region.reverse()
        } else {
            region.sortWith(compareBy { it as Long })
            if (order == SortOrder.DESENDING) region.reverse()
        }
        writeBack1D(ctx, name, idx, arr, isStr)
    }

    /**
     * ARRAYCOPY 直读 —— [execArrayCopy] 逐字复刻。
     * spec: Process.cs:1711-1772 + VariableEvaluator.cs:947-990。
     * 数据流向：第 1 参数数组 → 第 2 参数数组（CopyArray(var1, var2)：array2[i] = array[i]）。
     * TODO(verify): isa.yaml 记为「第二个复制到第一个」，与源码相反；按源码实现。
     */
    private fun execArrayCopyFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_COPY_ARRAY)
        val name1Term = a.expr()
        val name2Term = a.expr()
        val n1 = evalStr(name1Term, ctx)
        val n2 = evalStr(name2Term, ctx)
        // spec: Process.cs:1724-1756（非常量名路径的判序与文案逐条对齐官方 zhs）：
        // 存在性 → `NotVariableName`（lang/emuera-zhs.xml:912 = `{0}函数: 第{1}个参数("{2}")不是变量名`，
        // C# 实参 = "ARRAYCOPY", 序号, 原文）；
        // 非数组 → `ArraycopyArgIsNotArray`（:511 = `ARRAYCOPY命令的第{0}参数"{1}"不是一个数组变量`，
        // C# 用 IsArray1D/2D/3D 三判，等价于本侧 dimension < 1）；
        // 角色变量 → `ArraycopyArgIsCharaVar`（:512）；维度 → :514；类型 → :515。
        // **判序**必须是 数组 → 角色（Process.cs:1728-1737）——旧实现先判角色，角色型 0 维变量
        // 会报错到错误的那条键上。
        val i1 = ctx.variables.resolveVarInfo(n1)
            ?: throw CodeEE("ARRAYCOPY函数: 第1个参数(\"$n1\")不是变量名")
        if (i1.dimension < 1) throw CodeEE("ARRAYCOPY命令的第1参数\"$n1\"不是一个数组变量")
        if (i1.isCharacterData) throw CodeEE("ARRAYCOPY命令的第1参数\"$n1\"是一个角色变量(不支持)")
        val i2 = ctx.variables.resolveVarInfo(n2)
            ?: throw CodeEE("ARRAYCOPY函数: 第2个参数(\"$n2\")不是变量名")
        if (i2.dimension < 1) throw CodeEE("ARRAYCOPY命令的第2参数\"$n2\"不是一个数组变量")
        if (i2.isCharacterData) throw CodeEE("ARRAYCOPY命令的第2参数\"$n2\"是一个角色变量(不支持)")
        if (i1.dimension != i2.dimension) throw CodeEE("ARRAYCOPY命令的两个参数维度不一致")
        if (i1.operandType != i2.operandType) throw CodeEE("ARRAYCOPY命令的两个参数的数组变量类型不一致")
        // 登记残差：Process.cs:1749-1752 的非常量名路径还有一条 `ArraycopyArgIsConst`（第 2 参数
        // 为只读变量，zhs:513），以及常量名路径（Process.cs:1755-1762）**只查类型不一致**、
        // 不查数组/角色/只读。语料实测 `ARRAYCOPY` 共 50 处，其中 48 处两个参数都是字符串常量名
        // （装载期 `copyArray` 的 `checkArrayName`，ArgBuilders.kt:1257-1269，已在符号层覆盖常量名
        // 的数组/角色判定），2 处第 1 参为运行期名（`ARG_VARNAME` 与 FORM 串 `@"特效组{ARG}"`）。
        // 移植侧统一走上面这条更严的判序，两条残差不影响语料可达读数
        // （TODO(verify)：无符号表的裸构造路径可达时需补 const 分支）。
        val isStr = i1.operandType == OperandType.STR
        if (i1.dimension <= 1) {
            val n = minOf(ctx.variables.getLength(n1), ctx.variables.getLength(n2))
            for (i in 0 until n) {
                val at = longArrayOf(i.toLong())
                if (isStr) ctx.variables.writeStr(n2, at, ctx.variables.readStr(n1, at))
                else ctx.variables.writeInt(n2, at, ctx.variables.readInt(n1, at))
            }
            return
        }
        // 多维：逐维取 min 尺寸复制（spec: CopyArray 2D/3D 分支）
        val dims = (0 until i1.dimension).map { d ->
            minOf(ctx.variables.getLength(n1, d), ctx.variables.getLength(n2, d))
        }
        val idx = IntArray(i1.dimension)
        fun rec(d: Int) {
            if (d == i1.dimension) {
                val at = LongArray(i1.dimension) { idx[it].toLong() }
                if (isStr) ctx.variables.writeStr(n2, at, ctx.variables.readStr(n1, at))
                else ctx.variables.writeInt(n2, at, ctx.variables.readInt(n1, at))
                return
            }
            for (i in 0 until dims[d]) {
                idx[d] = i
                rec(d + 1)
            }
        }
        rec(0)
    }

    /** HTML_TAGSPLIT 直读 —— [execHtmlTagSplit] 逐字复刻。spec: FunctionIdentifier.cs:347-369 + HtmlManager.cs:486-515 */
    private fun execHtmlTagSplitFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_HTML_SPLIT)
        val targetTerm = a.expr()
        val destVarName = a.str()
        val numTerm = a.varRefOpt()
        val parts = htmlTagSplit(evalStr(targetTerm, ctx))
        if (parts == null) {
            numTerm?.let { writeVarRef(it, -1L, ctx) }
            return
        }
        numTerm?.let { writeVarRef(it, parts.size.toLong(), ctx) }
        val info = ctx.variables.resolveVarInfo(destVarName)
            ?: throw CodeEE("未定义的变量 $destVarName")
        val dim = maxOf(info.dimension, 1)
        val idx = LongArray(dim)
        val cap = ctx.variables.getLength(destVarName)
        val n = minOf(parts.size, cap)
        for (i in 0 until n) {
            idx[dim - 1] = i.toLong()
            ctx.variables.writeStr(destVarName, idx.copyOf(), parts[i])
        }
    }

    /**
     * PRINT_IMG 直读 —— [execInstruction] 的 PRINT_IMG 分支逐字复刻。
     * spec: FunctionIdentifier.cs:404-442（参数校验与 px/百分比解析按原版 ImageHolder 对齐）
     *
     * 槽序：`[SP_PRINT_IMG][header][name][nameb][namem][param: n + 每项（表达式 + isPx）]`。
     * `param` 是可空列表（`ArgBuilders.printImg` 的 `params.ifEmpty { null }`，encode 写 `NONE_REF`），
     * 由 `FlatArgs.mixedListOpt()`（契约 T2-64-C §2.1 追加读面）读取。
     */
    private fun execPrintImgFlat(
        source: FlatStmtSource,
        index: Int,
        code: FunctionCode,
        ctx: ExecContext,
    ): ExecResult {
        val head = source.headAt(index)
        // 槽序：[tag][op][SP_PRINT_IMG 记录] —— INSTRUCTION 族的专属字段为 0 槽。
        val c = FlatArgs(source, head + 2, source.endAt(index))
        val tag = c.open()
        if (tag != ErbcFormat.ArgTag.SP_PRINT_IMG) {
            shapeError(code, ErbcFormat.ArgTag.SP_PRINT_IMG, tag)
        }
        val name = c.expr()
        val nameb = c.expr()
        val namem = c.expr()
        val param = c.mixedListOpt()
        if (!ctx.skipPrint) {
            // TODO(M4): 参数校验与 px/百分比解析按原版 ImageHolder 对齐（spec: FunctionIdentifier.cs:404-442）
            ctx.graphics.printImage(
                name = evalStr(name, ctx),
                nameb = nameb?.let { evalStr(it, ctx) },
                namem = namem?.let { evalStr(it, ctx) },
                params = param?.map { (it.num?.getIntValue(ctx.eval) ?: 0L) to it.isPx } ?: emptyList(),
            )
        }
        return ExecResult.Normal
    }

    /**
     * DT_COLUMN_OPTIONS 直读 —— [execInstruction] 的 DT_COLUMN_OPTIONS 分支逐字复刻。
     * spec: FunctionIdentifier.cs:2531-2578 + ArgumentParser.cs:233-324（SpDtColumnOptions）。
     * 成功路径不写 RESULT（:2554-2577）；缺表/缺列由 dtColumnOptionsDefault 写 -1/0。
     */
    private fun execDtColumnOptionsFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext): ExecResult {
        openTag(a, code, ErbcFormat.ArgTag.SP_DT_COLUMN_OPTIONS)
        val dtTerm = a.expr()
        val columnTerm = a.expr()
        val optionCount = a.i32()
        if (optionCount < 0) {
            throw com.eratw.port.core.code.ErbcDecodeException(0, "DT_COLUMN_OPTIONS 的选项个数为负: $optionCount")
        }
        val options = IntArray(optionCount) {
            val wire = a.i32()
            if (wire != ErbcFormat.WireEnum.DT_DEFAULT) {
                throw com.eratw.port.core.code.ErbcDecodeException(0, "扁平语句流未知 DTOption 线值: $wire")
            }
            wire
        }
        val values = a.exprOptList()
        val table = evalStr(dtTerm, ctx)
        val column = evalStr(columnTerm, ctx)
        val n = minOf(options.size, values.size)
        for (i in 0 until n) {
            when (options[i]) {
                ErbcFormat.WireEnum.DT_DEFAULT ->
                    dtColumnOptionsDefault(ctx.variables, table, column, values[i]?.toValue(ctx.eval))
            }
        }
        return ExecResult.Normal
    }

    /**
     * CALLSHARP 直读 —— [execCallSharp] 逐字复刻。
     * spec: `FunctionIdentifier.cs:1212-1233`。四步逐句对齐原版：
     *
     * 1. 名字：装载期由 `SetJumpTo` 把常量名绑成 `CallFunc`，非常量名置 `useCallForm`
     *    （`:1191-1208`）；查不到方法时是 `ParserMediator.Warn(..., isError: true)` =
     *    **装载期**错误（`:1204`），文案原文 `No native method {name} found`。
     *    移植侧没有装载期的插件表（DLL 加载属宿主/M5），故把校验挪到执行期、
     *    未命中直接 CodeEE —— 差异登记：报错时机由装载期变运行期，文案同源。
     *    非常量名一侧原版保持 `CallFunc == null`，运行期即 NRE（`:1217` 无空检查）；
     *    移植侧改为「现求现查」，属**不复刻崩溃**的有意偏离，见交付报告 TODO(verify)。
     * 2. 实参 → 槽：`IsString` 走 `strValue`，否则 `intValue`
     *    （`:1216` + `PluginMethodParameterBuilder.cs:8-15`）。
     * 3. `Execute(array)`：返回值不存在，出参全靠改槽（`:1217`，`IPluginMethod.cs:13`）。
     * 4. **回写**：`RowArgs` 逐个看是不是变量项，是才写回；判定用接收侧
     *    `variableTerm.IsString`（**不是**槽自己的 isString，`:1219-1231`），
     *    非变量实参（字面量/表达式）求值后丢弃，不报错。
     *    这条就是 `isa.yaml:1340-1347` 记的「变量实参回写」，也是
     *    `:1218-1233` 注释段里 CALL 家族唯一有出参通道的语句
     *    （对比 CALLF 求值后丢弃、CALL 不写 RESULT）。
     *
     * 位置参数保持下标对齐：原版 `RowArgs[i]` 与 `array[i]` 同序（`:1216`/`:1220`），
     * 空位（省略的实参）在本工程 AST 里是 null 槽 —— 原版此时 `ConvertTerm(null)` 直接
     * NRE，移植侧给该槽补 `intValue = 0` 并**跳过回写**（无变量身份可写）。
     * 语料零处 CALLSHARP ⇒ 无可比读数，只登记口径。
     */
    private fun execCallSharpFlat(a: FlatArgs, code: FunctionCode, ctx: ExecContext) {
        openTag(a, code, ErbcFormat.ArgTag.SP_CALL_SHARP)
        val funcNameTerm = a.expr()
        a.exprOptList() // subNames：执行层不消费，按 encode 顺序跳过
        val rowArgs = a.exprOptList()
        val name = if (a.isConst) a.constStr else funcNameTerm?.getStrValue(ctx.eval) ?: ""
        val method = nativeMethods(ctx.variables.aux).find(name)
            ?: throw CodeEE("No native method $name found")
        val params = Array(rowArgs.size) { i -> toNativeParam(rowArgs[i], ctx) }
        method.execute(params)
        for (i in rowArgs.indices) {
            val ref = rowArgs[i] as? VarRef ?: continue
            if (ref.operandType == OperandType.STR) writeVarRefStr(ref, params[i].strValue, ctx)
            else writeVarRef(ref, params[i].intValue, ctx)
        }
    }

    // ==================================================================
    // PRINT 家族
    // ==================================================================

    /** spec: FunctionIdentifier.cs:173-229 PRINT_Instruction.DoInstruction + ExpressionMediator.cs:50-69 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execPrint(stmt: PrintStmt, ctx: ExecContext) {
        if (ctx.skipPrint) return
        val spec = stmt.print
        // 打印窗口起点（原版在求值之前置位，FunctionIdentifier.cs:179-180 → :182-210）：
        // 先写 UseUserStyle=true（:179），再写 UseSetColorStyle（:180）。前者是**粘滞**开关，
        // 只有这五处 PRINT 系语句会把它写回真 —— 系统行/错误行写过假之后，要靠下一条
        // PRINT 才能恢复用户样式（ConsoleSink.setUseUserStyle 的 KDoc）。
        // D 系（PRINTD/PRINTVD/PRINTSD 及全部 D 后缀形态）窗口内取色回落默认前景色
        // （EmueraConsole.cs:471-489 Style getter；ConsoleSink.setUseSetColorStyle 的 KDoc）。
        ctx.console.setUseUserStyle(true)
        ctx.console.setUseSetColorStyle(!spec.isD)
        var str: String = when (spec.argType) {
            ArgType.SP_PRINTV -> {
                val terms = (stmt.args as? SpPrintVArgument)?.terms ?: emptyList()
                buildString {
                    for (t in terms) {
                        if (t.operandType == OperandType.INT) append(t.getIntValue(ctx.eval))
                        else append(t.getStrValue(ctx.eval))
                    }
                }
            }
            // STR_EXPRESSION（PRINTS/PRINTFORMS 的参数）与 FORM/STR 共用 ExpressionArgument
            else -> evalStrArg(stmt.args, ctx)
        }
        // *FORMS 语义：字符串求值后再按 FORM 解析一次（CheckEscape + AnalyseFormattedString，
        // spec: FunctionIdentifier.cs:203-209）。解析期无法预知求值结果，故经 ctx.runtimeForm 注入。
        if (spec.isForms) ctx.runtimeForm?.let { str = it(str) }
        // K 转换（假名/全半角）依赖 Windows VB StrConv，宿主层未提供：原样透传。
        // TODO(verify): ConvertStringType 的 StrConv 等价实现在 M5 文字流层补（spec: ExpressionMediator.cs:71-90）
        if (spec.flag and 1024 != 0) {
            ctx.console.printSingleLine(str) // PRINTSINGLE：单行独立显示
            // PRINTSINGLE 在原版经 OutputToConsole，复位在其末尾（ExpressionMediator.cs:68）。
            ctx.console.setUseSetColorStyle(true)
            return
        }
        if (spec.isC) {
            ctx.console.printC(str, alignRight = true)
        } else if (spec.isLC) {
            ctx.console.printC(str, alignRight = false)
        } else {
            // 行结束标记随段传递（PRINTN → false）：标记在宿主 flush 时决定日志断行与 LINECOUNT，
            // 之后 L/W 位仍触发 NewLine/ReadAnyKey（spec: ExpressionMediator.cs:59-66）。
            ctx.console.print(str, spec.isLineEnd)
            // spec: ExpressionMediator.cs:59-66（OutputToConsole）—— IsNewLine()||IsWaitInput() → NewLine()；
            // IsWaitInput() → ReadAnyKey()。位义：256=PRINT_NEWLINE（FunctionIdentifier.cs:3739，L 后缀）、
            // 512=PRINT_WAITINPUT（:3741，W 后缀 768=256|512）。
            // R5 勘误（A9）：**N 后缀同样置 512**（FunctionIdentifier.cs:146-151），即 PRINTN=换行且等待按键，
            // isa.yaml 原文"不换行"是错的；本实现按 flag 位走，天然覆盖 PRINTN/PRINTKW/PRINTDATAW 等全部 W/N 形态。
            // 原版怪行为（PRINTN 语义）按 ADR-005 原样复刻。
            if (spec.flag and 256 != 0 || spec.flag and 512 != 0) {
                ctx.console.newLine()
                // MesSkip 短路：原版 ReadAnyKey 本身无条件进 WaitInput（spec: EmueraConsole.cs:876-895），
                // 跳过实际发生在控制台输入循环——MesSkip 为真时把 NeedValue=false 且 StopMesskip=false 的
                // 等待自动吞掉（spec: EmueraConsole.cs:1451-1455）。PRINT 系等待恒为 stopMesskip=false，
                // sem 层等价语义 = mesSkip 为真时不发起等待（换行不受影响）。等待只经 ExecContext.input 表达。
                // 同 C4：PRINT 系 W 位吞掉后无条件清 MesSkip（EmueraConsole.cs:1456）。
                if (spec.flag and 512 != 0) { if (ctx.mesSkip) ctx.mesSkip = false else readAnyKey(ctx) }
            }
        }
        // 窗口终点。spec: FunctionIdentifier.cs:227（OutputToConsole 内 ExpressionMediator.cs:68 的
        // 复位被 :227 覆盖，两处同值；非 C/LC/PRINTSINGLE 路径的 NewLine/ReadAnyKey 在窗口内）。
        ctx.console.setUseSetColorStyle(true)
    }

    // ==================================================================
    // 通用指令分派
    // ==================================================================

@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execInstruction(code: FunctionCode, stmt: Statement, ctx: ExecContext): ExecResult = when (code) {
        // ---- 显示 ----
        FunctionCode.PRINTBUTTON, FunctionCode.PRINTBUTTONC, FunctionCode.PRINTBUTTONLC -> {
            execPrintButton(code, stmt, ctx); ExecResult.Normal
        }
        FunctionCode.PRINTPLAIN, FunctionCode.PRINTPLAINFORM -> {
            // 原版求值前显式复位两条开关（spec: Process.cs:1149-1150），清除可能残留的 D 系窗口。
            if (!ctx.skipPrint) {
                ctx.console.setUseUserStyle(true)
                ctx.console.setUseSetColorStyle(true)
                ctx.console.printPlain(evalStrArg(stmt.args, ctx))
            }
            ExecResult.Normal
        }
        FunctionCode.DRAWLINE -> {
            if (!ctx.skipPrint) { // spec: Process.cs:1155-1161 —— PrintBar + NewLine
                ctx.console.printBar(); ctx.console.newLine()
            }
            ExecResult.Normal
        }
        FunctionCode.DRAWLINEFORM, FunctionCode.CUSTOMDRAWLINE -> {
            // spec: Process.cs:1162-1170 / FunctionIdentifier.cs:506-538 —— printCustomBar + NewLine
            if (!ctx.skipPrint) {
                ctx.console.printCustomBar(evalStrArg(stmt.args, ctx)); ctx.console.newLine()
            }
            ExecResult.Normal
        }
        FunctionCode.PRINT_ABL, FunctionCode.PRINT_TALENT, FunctionCode.PRINT_MARK, FunctionCode.PRINT_EXP -> {
            // spec: Process.cs:1171-1181 —— 取角色位置参数，GetCharacterDataString 一次 Print + NewLine；
            // skipPrint 时整条抑制（连 NewLine 一起跳，Process.cs:1175 的 if (!skipPrint) 包住两步）。
            if (!ctx.skipPrint) {
                val target = evalIntArg(stmt.args, ctx)
                ctx.console.print(PrintCharaData.characterDataString(ctx, target, code))
                ctx.console.newLine()
            }
            ExecResult.Normal
        }
        FunctionCode.PRINT_PALAM -> {
            // spec: Process.cs:1182-1206 —— 0..99 逐项 GetCharacterParamString，非 null 项
            // PrintC（右对齐），每 PrintCPerLine 项 PrintFlush(force:false) 分行，收尾再 flush 一次。
            if (!ctx.skipPrint) {
                val target = evalIntArg(stmt.args, ctx)
                val perLine = ctx.config.printCPerLine
                var printed = 0
                for (i in 0 until 100) { // 硬编码 100：spec: Process.cs:1190
                    val cell = PrintCharaData.characterParamString(ctx, target, i) ?: continue
                    ctx.console.printC(cell, alignRight = true) // spec: Process.cs:1195（alignmentRight: true）
                    printed++
                    if (perLine > 0 && printed % perLine == 0) { // spec: Process.cs:1197-1200
                        // PrintFlush(force:false) 在缓冲非空时与 NewLine 等价（Print 都已入缓冲、
                        // lineEnd=true）；此处仅在 printed>0 时触发，缓冲必非空。
                        ctx.console.newLine()
                    }
                }
                // 收尾 PrintFlush(force:false)（spec: Process.cs:1203）：缓冲空（printed==0，或
                // printed 恰为 perLine 整倍数已被上面的分行 flush 清空）时原版为 no-op，同理不补行。
                val dirty = if (perLine > 0) printed % perLine != 0 else printed > 0
                if (dirty) ctx.console.newLine()
                // RefreshStrings(force_Paint:false)（Process.cs:1204）是重绘动作，不入文本流，不移植。
            }
            ExecResult.Normal
        }
        FunctionCode.REUSELASTLINE -> {
            ctx.console.printTemporaryLine(evalStrArg(stmt.args, ctx)); ExecResult.Normal // spec: FunctionIdentifier.cs:668-681
        }
        FunctionCode.CLEARLINE -> {
            ctx.console.deleteLine(evalIntArg(stmt.args, ctx).toInt()); ExecResult.Normal // spec: FunctionIdentifier.cs:683-697
        }
        FunctionCode.BAR, FunctionCode.BARL -> {
            // spec: FunctionIdentifier.cs:1289-1312 + ExpressionMediator.cs:130-159 CreateBar
            val arg = stmt.args as SpBarArgument
            val v = arg.value?.getIntValue(ctx.eval) ?: 0L
            val max = arg.max?.getIntValue(ctx.eval) ?: 0L
            val len = arg.length?.getIntValue(ctx.eval) ?: 0L
            ctx.console.print(createBar(v, max, len))
            if (code == FunctionCode.BARL) ctx.console.newLine()
            ExecResult.Normal
        }
        FunctionCode.SETCOLOR -> {
            val (r, g, b) = evalColor(stmt.args, ctx)
            ctx.console.setForeColor(r, g, b); ExecResult.Normal
        }
        FunctionCode.SETBGCOLOR -> {
            val (r, g, b) = evalColor(stmt.args, ctx)
            ctx.console.setBackColor(r, g, b); ExecResult.Normal
        }
        FunctionCode.SETCOLORBYNAME -> {
            val (r, g, b) = colorByName(evalStrArg(stmt.args, ctx))
            ctx.console.setForeColor(r, g, b); ExecResult.Normal
        }
        FunctionCode.SETBGCOLORBYNAME -> {
            val (r, g, b) = colorByName(evalStrArg(stmt.args, ctx))
            ctx.console.setBackColor(r, g, b); ExecResult.Normal
        }
        FunctionCode.RESETCOLOR -> {
            ctx.console.resetForeColor(); ExecResult.Normal // spec: FunctionIdentifier.cs:1491-1503
        }
        FunctionCode.RESETBGCOLOR -> {
            ctx.console.resetBackColor(); ExecResult.Normal // spec: FunctionIdentifier.cs:1505-1517
        }
        FunctionCode.FONTBOLD -> {
            // spec: FunctionIdentifier.cs:1574-1589——StringStyle.FontStyle | Bold（OR 语义，
            // 与 FONTITALIC 叠加得 3；PC 基准 STYLE=1,3 锁死，不得改成绝对置值）
            ctx.console.setFontStyle(ctx.console.fontStyle or 1); ExecResult.Normal
        }
        FunctionCode.FONTITALIC -> {
            // spec: FunctionIdentifier.cs:1591-1606——FontStyle | Italic
            ctx.console.setFontStyle(ctx.console.fontStyle or 2); ExecResult.Normal
        }
        FunctionCode.FONTREGULAR -> {
            ctx.console.setFontStyle(0); ExecResult.Normal // spec: FunctionIdentifier.cs:1608-1623
        }
        FunctionCode.FONTSTYLE -> {
            ctx.console.setFontStyle(evalIntArg(stmt.args, ctx).toInt()); ExecResult.Normal // spec: Process.cs:1508-1530
        }
        FunctionCode.SETFONT -> {
            ctx.console.setFont(evalStrArg(stmt.args, ctx)); ExecResult.Normal // spec: Process.cs:1531-1534
        }
        FunctionCode.ALIGNMENT -> {
            val s = evalStrArg(stmt.args, ctx)
            val kind = when (s.uppercase()) {
                "LEFT" -> Alignment.LEFT
                "CENTER" -> Alignment.CENTER
                "RIGHT" -> Alignment.RIGHT
                else -> throw CodeEE("没有定义ALIGNMENT的关键字\"$s\"") // spec: Lang InvalidAlignment
            }
            ctx.console.setAlignment(kind); ExecResult.Normal // spec: Process.cs:1535-1552
        }
        FunctionCode.REDRAW -> {
            ctx.console.setRedraw(evalIntArg(stmt.args, ctx).toInt()); ExecResult.Normal // spec: Process.cs:1553-1556
        }

        // ---- 随机数状态（RANDOMIZE/INITRAND/DUMPRAND）----
        // spec: FunctionIdentifier.cs:1734-1799 + VariableEvaluator.cs:217-230。
        // UseNewRandom=true 时原版仅告警不动作（本游戏 setting.json UseNewRandom=false，
        // 走经典 MTRandom 通道；RANDOMIZE 省略参数按 0 处理——ArgumentParser.cs:1194-1196）。
        FunctionCode.RANDOMIZE -> {
            val seed = evalIntArg(stmt.args, ctx)
            if (!ctx.config.useNewRandom) {
                val rng = ctx.variables.randProvider
                    ?: throw CodeEE("RANDOMIZE 需要注入 RandProvider（ADR-001）")
                rng.reseed(seed) // 原版新建 MTRandom(seed) ≡ init_gen_rand + idx=624
            }
            ExecResult.Normal
        }
        FunctionCode.DUMPRAND -> {
            if (!ctx.config.useNewRandom) {
                val rng = ctx.variables.randProvider
                    ?: throw CodeEE("DUMPRAND 需要注入 RandProvider（ADR-001）")
                val st = rng.state() // GetRand(RANDDATA)：MTRandom.cs:69-80
                val len = ctx.variables.getLength(RANDDATA, -1)
                for (i in 0 until minOf(st.size, len)) {
                    ctx.variables.writeInt(RANDDATA, longArrayOf(i.toLong()), st[i])
                }
            }
            ExecResult.Normal
        }
        FunctionCode.INITRAND -> {
            if (!ctx.config.useNewRandom) {
                val rng = ctx.variables.randProvider
                    ?: throw CodeEE("INITRAND 需要注入 RandProvider（ADR-001）")
                val len = ctx.variables.getLength(RANDDATA, -1)
                val data = LongArray(len) { ctx.variables.readInt(RANDDATA, longArrayOf(it.toLong())) }
                rng.loadState(data) // SetRand(RANDDATA)：MTRandom.cs:56-67
            }
            ExecResult.Normal
        }
        FunctionCode.HTML_PRINT -> {
            val arg = stmt.args as SpHtmlPrint
            if (!ctx.skipPrint) {
                val opt = arg.opt?.getIntValue(ctx.eval) ?: 0L
                ctx.console.printHtml(evalStr(arg.str, ctx), opt != 0L)
            }
            ExecResult.Normal
        }
        FunctionCode.HTML_PRINT_ISLAND -> {
            val arg = stmt.args as SpHtmlPrint
            if (!ctx.skipPrint) ctx.console.addHtmlIsland(evalStr(arg.str, ctx))
            ExecResult.Normal
        }
        FunctionCode.HTML_PRINT_ISLAND_CLEAR -> {
            ctx.console.clearHtmlIslands(); ExecResult.Normal
        }
        FunctionCode.HTML_TAGSPLIT -> {
            execHtmlTagSplit(stmt, ctx); ExecResult.Normal
        }
        FunctionCode.PRINT_IMG -> {
            val arg = stmt.args as SpPrintImgArgument
            if (!ctx.skipPrint) {
                // TODO(M4): 参数校验与 px/百分比解析按原版 ImageHolder 对齐（spec: FunctionIdentifier.cs:404-442）
                ctx.graphics.printImage(
                    name = evalStr(arg.name, ctx),
                    nameb = arg.nameb?.let { evalStr(it, ctx) },
                    namem = arg.namem?.let { evalStr(it, ctx) },
                    params = arg.param?.map { (it.num?.getIntValue(ctx.eval) ?: 0L) to it.isPx } ?: emptyList(),
                )
            }
            ExecResult.Normal
        }
        FunctionCode.PRINT_RECT, FunctionCode.PRINT_SPACE -> {
            val arg = stmt.args as SpPrintShapeArgument
            if (!ctx.skipPrint) {
                val params = arg.param.map { (it.num?.getIntValue(ctx.eval) ?: 0L) to it.isPx }
                // 原版按执行时点的 userStyle.Color 取色（EmueraConsole.cs:3197-3199），
                // 未 SETCOLOR 时回落 Config.ForeColor = 192,192,192（ConfigData.cs:68）；
                // ConsoleSink.foregroundColor 为 null 正是「未显式设置」（ConsoleSink.kt:248-253）。
                if (code == FunctionCode.PRINT_RECT) {
                    ctx.graphics.printRect(params, ctx.console.foregroundColor ?: 0xC0C0C0)
                } else {
                    ctx.graphics.printSpace(params)
                }
            }
            ExecResult.Normal
        }
        FunctionCode.SETBGIMAGE -> {
            // spec: FunctionIdentifier.cs:1519-1543（FORM_STR_ANY：文件名[,深度,不透明度]）
            // TODO(verify): 原版 depth/opacity 是 `long.Parse(Term.GetStrValue(exm))`
            // （:1535/:1539 —— 先按字符串求值再十进制解析，非数字串抛 FormatException），
            // 本实现按整数表达式求值（getIntValue）。裸整数两口径同值；带引号数字串
            // （"5"）与非数值串的报错形态有差。语料未出现这两种写法，先保持现状，
            // 改动需连带 ArgBuilders 的 FORM_STR_ANY 求值口径一起核对。
            val terms = argTerms(stmt.args)
            ctx.graphics.setBgImage(
                name = terms.getOrNull(0)?.let { evalStr(it, ctx) } ?: "",
                depth = terms.getOrNull(1)?.let { it.getIntValue(ctx.eval) },
                opacity = terms.getOrNull(2)?.let { it.getIntValue(ctx.eval) },
            )
            ExecResult.Normal
        }
        FunctionCode.REMOVEBGIMAGE -> {
            ctx.graphics.removeBgImage(argTerms(stmt.args).getOrNull(0)?.let { evalStr(it, ctx) } ?: "")
            ExecResult.Normal
        }
        FunctionCode.CLEARBGIMAGE -> {
            ctx.graphics.clearBgImages(); ExecResult.Normal
        }
        FunctionCode.CLEARTEXTBOX -> {
            ctx.console.clearText(); ExecResult.Normal // spec: Process.cs:1798-1800
        }

        // ---- 输入 ----
        FunctionCode.WAIT -> {
            // MesSkip 中 WAIT 由输入循环直接跑过（EmueraConsole.cs:1451-1455：NeedValue=false
            // 且未 StopMesskip 时 while 继续），不发起等待。
            // 原版输入循环吞掉无值等待后无条件 MesSkip = false（EmueraConsole.cs:1456）——
            // 不清标志会让下一个 INPUT(canSkip) 快路径静默写默认值（2026-10-26 fixcore 审查 C4）。
            if (ctx.mesSkip) ctx.mesSkip = false else readAnyKey(ctx); ExecResult.Normal // spec: FunctionIdentifier.cs:762-784
        }
        FunctionCode.FORCEWAIT -> {
            readAnyKey(ctx, anyKey = false, stopMesSkip = true); ExecResult.Normal
        }
        FunctionCode.WAITANYKEY -> {
            // 同 C4：吞掉后无条件清 MesSkip（EmueraConsole.cs:1456）。
            if (ctx.mesSkip) ctx.mesSkip = false else readAnyKey(ctx, anyKey = true); ExecResult.Normal // spec: FunctionIdentifier.cs:786-798
        }
        FunctionCode.TWAIT -> {
            /*
             * spec: FunctionIdentifier.cs:808-824。原文明明写着两次调用，但只有一次是真等待：
             * 1. `Console.ReadAnyKey()`（:810）只是把控制台的 `inputReq`/`state` 置成
             *    EnterKey 等待然后**立刻返回**（EmueraConsole.cs:876-895 全无阻塞），
             *    `DoInstruction` 还没走完就被第 2 步整个覆盖掉（:864-865）⇒ 用户看不到、
             *    也答不了这一次「等待」；它留下的只有 `process.NeedWaitToEventComEnd = false`
             *    （:894，本移植未接那条 `setWait` 支路，见 BeginFlow.kt:350-357 的登记）。
             * 2. **一次** `Console.WaitInput(req)`（:823）：`Timelimit = 第1参`，形态是
             *    `第2参 == 0 ? EnterKey : Void`（:814-821）；`WaitInput` 的返回值原版丢弃。
             *
             * 所以 TWAIT = 一次限时等待，交付按形态决定（EnterKey ⇒ 提交即走/超时照走，
             * 都不写 RESULT；Void ⇒ 键盘提交完全无效，只有超时推进，EmueraConsole.cs:1417-1420）。
             * 旧实现把这次等待塌缩成 INT 限时等待，还额外真等了一次按键：
             * 语料里 207 处 TWAIT（其中 51 处第 2 参非 0）全是「按一次键跳过/纯延时」的用法，
             * 塌缩后 `TWAIT 1500, 0` 会先死等一次回车、`TWAIT 40, 1` 会被早到的提交打断。
             */
            val arg = stmt.args as SpSwapCharaArgument
            // 第 1、2 参都要在等待之前求值，且顺序固定（RAND 等副作用，:812-813）。
            val time = arg.x?.getIntValue(ctx.eval) ?: 0L
            val voidForm = (arg.y?.getIntValue(ctx.eval) ?: 0L) != 0L
            val req = InputRequest(
                // 非取值形态：type 只是宿主选键盘类型的提示，交付不写 RESULT/RESULTS。
                type = InputType.STR,
                form = if (voidForm) InputRequest.RequestForm.VOID else InputRequest.RequestForm.ENTER_KEY,
                timeLimit = time,
            )
            // 原版 WaitInput 无条件发生（:823），跳过态下也不例外：`presetTimer`（:866-873）
            // 照样挂计时器、`setTimer` 照样清零 isTimeout，只是随后被输入循环跑过
            // （:1451-1455 的 NeedValue=false 自动推进）。
            armIsTimeout(ctx, req)
            if (ctx.mesSkip && !req.needValue) {
                // 同 C4：TWAIT 的无值等待被 MesSkip 吞掉后无条件清标志（EmueraConsole.cs:1456）。
                ctx.mesSkip = false
            } else {
                val outcome = waitInput(ctx, req)
                updateIsTimeout(ctx, req, outcome)
            }
            ExecResult.Normal
        }
        FunctionCode.INPUT, FunctionCode.INPUTS, FunctionCode.ONEINPUT, FunctionCode.ONEINPUTS -> {
            execInputs(code, stmt, ctx); ExecResult.Normal
        }
        FunctionCode.TINPUT, FunctionCode.TONEINPUT, FunctionCode.TINPUTS, FunctionCode.TONEINPUTS -> {
            execTInputs(code, stmt, ctx); ExecResult.Normal
        }
        FunctionCode.BINPUT, FunctionCode.BINPUTS, FunctionCode.ONEBINPUT, FunctionCode.ONEBINPUTS -> {
            execBInputs(code, stmt, ctx); ExecResult.Normal
        }
        FunctionCode.INPUTANY -> {
            // spec: FunctionIdentifier.cs:2095-2111 —— `InputType.AnyValue` 的交付在
            // `doInputToEmueraProgram` 的 AnyValue 分支（EmueraConsole.cs:1256-1274）：
            // `long.TryParse` 成功 → 只 `InputInteger`（写 RESULT），失败 → 只 `InputString`
            // （写 RESULTS），**二选一、从不双写**；该分支不看 HasDefValue（INPUTANY 无默认值参数）。
            val req = InputRequest(type = InputType.INT, form = InputRequest.RequestForm.ANY_VALUE)
            val o = waitInput(ctx, req)
            updateIsTimeout(ctx, req, o)
            // 单通道：宿主按能否解析成整数决定 `writesInt`，两条交付互斥。
            if (o.writesInt) ctx.variables.writeInt(RESULT, longArrayOf(0), o.intValue) else
                ctx.variables.writeStr(RESULTS, longArrayOf(0), o.strValue)
            ExecResult.Normal
        }
        FunctionCode.INPUTMOUSEKEY -> {
            // spec: FunctionIdentifier.cs:2067-2093 —— `InputType.PrimitiveMouseKey`，
            // 第 1 参 >0 才挂时限（:2087-2090，0/缺省 = 不限时）。
            val timeLimit = argTerms(stmt.args).getOrNull(0)?.getIntValue(ctx.eval) ?: -1L
            val req = InputRequest(
                type = InputType.INT,
                form = InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY,
                timeLimit = timeLimit,
            )
            val o = waitInput(ctx, req)
            updateIsTimeout(ctx, req, o)
            if (o.isTimeout) {
                // 原版超时走 `endTimer` 的 PrimitiveMouseKey 分支（EmueraConsole.cs:1044-1048）：
                // `InputMouseKey(4, 0, 0, 0, 0, 0)` → `process.InputResult5` 整组写
                // RESULT:0..5 = 4,0,0,0,0,0（Process.cs:393-401，r0=4 是「超时交付」类型位）。
                // 本移植等待面把交付抽象成整数提交，超时前不可能有部分写入，照抄整组清零。
                for (i in 0L..5L) ctx.variables.writeInt(RESULT, longArrayOf(i), if (i == 0L) 4L else 0L)
            } else if (o.writesInt) {
                // 一条文本提交：原版 switch 没有 PrimitiveMouseKey 的 case（:1112-1288），
                // 于是既不写 RESULT 也不写 RESULTS，只是推进 ⇒ 宿主回报 writesInt=false。
                // 真鼠标/按键事件的 RESULT:1..5（坐标/按钮/字符码）属宿主事件接线，见本类
                // 输入族文档与本路交付报告的 TODO(verify)。
                ctx.variables.writeInt(RESULT, longArrayOf(0), o.intValue)
            }
            ExecResult.Normal
        }

        // ---- 变量操作 ----
        FunctionCode.SETBIT, FunctionCode.CLEARBIT, FunctionCode.INVERTBIT -> {
            execBit(code, stmt, ctx); ExecResult.Normal
        }
        FunctionCode.SWAP -> {
            execSwap(stmt, ctx); ExecResult.Normal
        }
        FunctionCode.VARSET -> {
            execVarSet(stmt, ctx); ExecResult.Normal
        }
        FunctionCode.CVARSET -> {
            execCVarSet(stmt, ctx); ExecResult.Normal
        }
        FunctionCode.VARSIZE -> {
            execVarSize(stmt, ctx); ExecResult.Normal
        }
        FunctionCode.POWER -> {
            execPower(stmt, ctx); ExecResult.Normal
        }
        FunctionCode.TIMES -> {
            val arg = stmt.args as SpTimesArgument
            // spec: FunctionIdentifier.cs:1314-1342 TIMES_Instruction.DoInstruction ——
            // 双路径按 Config.TimesNotRigorousCalculation 分派（ConfigData.cs:111 出厂 false，
            // 本游戏 emuera.config「IMITATE BEHAVIOR FOR TIMES:NO」同值）：
            // - 默认（:1326-1338）：`(decimal)IntValue * (decimal)DoubleValue` —— decimal 携带
            //   double 的**精确二进制值**（.NET Decimal(Double) 保留到 28 位有效数字），
            //   乘积落在 long 域内向零截断；这正是 |IntValue| > 2^53 时与 double 乘路径
            //   可观测分叉的来源（double 只有 53 位尾数，(double)IntValue 本身先失真）。
            // - eramaker 模拟（:1322-1325）：`(double)IntValue * DoubleValue` 后 `(long)` 截断。
            // `(long)num2` 越界分支的 `(long)(double)num2`（:1339-1341）在 C# x64 产「整数不定值」
            // = Long.MIN_VALUE（cvttsd2si；JLS 5.1.3 的饱和语义正溢出给 MAX_VALUE，两侧分叉）——
            // 两个分支都按 MIN_VALUE 复刻（2026-10-26 fixcore 审查 B4，替代旧「沿饱和」TODO）。
            val name = arg.dest.name
            val idx = resolveIndices(arg.dest, ctx)
            val cur = ctx.variables.readInt(name, idx)
            if (ctx.config.timesNotRigorousCalculation) {
                val r = cur.toDouble() * arg.doubleValue
                // eramaker 路径的 `(long)num` 同为 x64 不定值口径：NaN / ≥2^63 一律 Long.MIN_VALUE
                // （负溢出 JVM 饱和本就给 MIN_VALUE，与 x64 一致，无需特判）。
                ctx.variables.writeInt(
                    name, idx,
                    if (r.isNaN() || r >= 9.223372036854776E18) Long.MIN_VALUE else r.toLong(),
                )
            } else {
                // (decimal)DoubleValue：.NET Decimal(Double) 把 double 的精确值舍入到
                // 28 位有效数字（banker's rounding）⇒ BigDecimal(exact).round(28, HALF_EVEN)。
                // TODO(verify): 乘积超过 28 位有效数字时 .NET decimal 乘法的舍入方向
                // （HALF_EVEN vs HALF_UP）未逐行取证，仅在 |IntValue| ≳ 1e10 且倍数含长尾
                // 时才可能触到该第 28 位，语料 TIMES（3143 处）无此量级读数。
                val d = java.math.BigDecimal(arg.doubleValue)
                    .round(java.math.MathContext(28, java.math.RoundingMode.HALF_EVEN))
                val product = java.math.BigDecimal(cur).multiply(d)
                val r = if (product <= java.math.BigDecimal(java.lang.Long.MAX_VALUE) &&
                    product >= java.math.BigDecimal(java.lang.Long.MIN_VALUE)
                ) {
                    product.setScale(0, java.math.RoundingMode.DOWN).longValueExact()
                } else {
                    // C# x64 `(long)(double)` 越界 = cvttsd2si 的「整数不定值」= Long.MIN_VALUE
                    // （正溢出也得 MIN_VALUE；JLS 5.1.3 饱和语义正溢出给 MAX_VALUE —— 两侧不同，
                    // 2026-10-26 fixcore 审查 B4 钉死）。
                    Long.MIN_VALUE
                }
                ctx.variables.writeInt(name, idx, r)
            }
            ExecResult.Normal
        }
        FunctionCode.ENCODETOUNI -> {
            execEncodeToUni(stmt, ctx); ExecResult.Normal
        }
        FunctionCode.SPLIT -> {
            execSplit(stmt, ctx); ExecResult.Normal
        }
        FunctionCode.ARRAYSHIFT -> {
            execArrayShift(stmt, ctx); ExecResult.Normal
        }
        FunctionCode.ARRAYREMOVE -> {
            execArrayRemove(stmt, ctx); ExecResult.Normal
        }
        FunctionCode.ARRAYSORT -> {
            execArraySort(stmt, ctx); ExecResult.Normal
        }
        FunctionCode.ARRAYCOPY -> {
            execArrayCopy(stmt, ctx); ExecResult.Normal
        }
        FunctionCode.STRLEN, FunctionCode.STRLENFORM, FunctionCode.STRLENU, FunctionCode.STRLENFORMU -> {
            // spec: FunctionIdentifier.cs:699-729
            val text = evalStrArg(stmt.args, ctx)
            val len = if (code == FunctionCode.STRLENU || code == FunctionCode.STRLENFORMU) {
                text.length
            } else {
                strLenLang(text, langCodecOf(ctx.eval))
            }
            ctx.variables.writeInt(RESULT, longArrayOf(0), len.toLong())
            ExecResult.Normal
        }
        FunctionCode.PRINTCPERLINE -> {
            writeVarRef((stmt.args as SpGetIntArgument).dest, ctx.config.printCPerLine.toLong(), ctx) // spec: Process.cs:1577-1579
            ExecResult.Normal
        }
        FunctionCode.SAVENOS -> {
            writeVarRef((stmt.args as SpGetIntArgument).dest, ctx.config.saveDataNos.toLong(), ctx) // spec: Process.cs:1580-1582
            ExecResult.Normal
        }
        FunctionCode.METHOD -> {
            // spec: FunctionIdentifier.cs:584-604 —— 整型写 RESULT，字符串写 RESULTS
            val term = (stmt.args as? MethodArgument)?.methodTerm
            if (term != null) {
                if (term.operandType == OperandType.INT) {
                    ctx.variables.writeInt(RESULT, longArrayOf(0), term.getIntValue(ctx.eval))
                } else {
                    ctx.variables.writeStr(RESULTS, longArrayOf(0), term.getStrValue(ctx.eval))
                }
            }
            ExecResult.Normal
        }

        // ---- 角色库 ----
        FunctionCode.ADDCHARA, FunctionCode.ADDSPCHARA -> {
            // spec: FunctionIdentifier.cs:1344-1397 —— ADDCHARA / ADDSPCHARA / DELCHARA 共用一个
            // 实现类，靠构造期的 isSp / isDel 位区分（注册：:3943 ADDCHARA、:3944 ADDSPCHARA、
            // :3947 DELCHARA；三条都把参数形态钉成 INT_ANY，:1352）。
            // ① SP 关闭且本条是 SP 变体 → 抛错，且**发生在任何实参求值之前**
            //   （:1360-1363 在 :1368 的循环之前）⇒ `ADDSPCHARA 未定义NO` 报的是 SP 错，不是角色错。
            if (stmt.code == FunctionCode.ADDSPCHARA && !spCharaEnabled(semHost(ctx))) {
                throw CodeEE(SP_CHARA_CONFIG_IS_OFF)
            }
            // ② 逐参数求值 + 追加（:1368-1384）。SP **开启**时 ADDCHARA 与 ADDSPCHARA 都走
            //   `AddCharacter_UseSp`（:1376-1379），而 `GetCharacterTemplate_UseSp` 的 `sp` 形参
            //   **全方法体未被读取**（ConstantData.cs:1230-1238）⇒ 原版按 NO 查表、SP 身份不参与
            //   筛选，两条语句同一条路（ADR-005 原样复刻，不"顺手修好"）。
            //   SP 关闭时 ADDCHARA 走 `AddCharacter`（:1380-1383）= 按 NO 线性首命中
            //   （ConstantData.cs:1218-1228）。移植侧两条路都落到 templatesByNo 查表
            //   （VariableStore.kt:481-485，未命中 CodeEE）；原版 `_UseSp` 用的是排好序列表上的
            //   BinarySearch（排序点 ConstantData.cs:1503），命中集与线性首命中一致 ⇒ 无观测差。
            //   TODO(verify): 装载期若未走到 :1503 的排序，原版 BinarySearch 在未序列表上的
            //   命中/未命中不确定；本工程无该场景读数，暂按 map 精确命中口径。
            for (t in argTerms(stmt.args)) ctx.variables.addCharacter(t.getIntValue(ctx.eval))
            ExecResult.Normal
        }
        FunctionCode.ADDCOPYCHARA -> {
            // spec: FunctionIdentifier.cs:1447-1463（指令体 :1455-1462 逐实参调
            //   `exm.VEvaluator.AddCopyChara(aExpression.GetIntValue(exm))`，:1460；参数形态
            //   FunctionArgType.INT_ANY，:1451 ⇒ 实参只有**源位置**一个）→
            //   VariableEvaluator.cs:1368-1376 的 `AddCopyChara(x)`，次序是硬性的：
            //   ① 边界校验 `x < 0 || x >= CharacterList.Count` ⇒ 抛 NotExistFromCopyChara
            //     （:1370-1373，中文文案 lang.emuera-zhs.xml:316「复制的原角色不存在」）；
            //   ② 校验通过后**才** `AddPseudoCharacter()`（:1374）；
            //   ③ `CharacterList[x].CopyTo(CharacterList[Count-1])`（:1375）
            //     ⇒ **源 = 实参 x，目标 = 新追加的尾元素**。
            // 移植侧两处历史分歧（先追加后校验 / 源目标反号）已收进
            // VariableStore.addCopyCharacter（判界先于追加、方向单点收口）；
            // 方向用例见 ResetGlobalAuditTest。
            for (t in argTerms(stmt.args)) ctx.variables.addCopyCharacter(t.getIntValue(ctx.eval))
            ExecResult.Normal
        }
        FunctionCode.ADDVOIDCHARA -> {
            ctx.variables.addPseudoCharacter(); ExecResult.Normal // spec: FunctionIdentifier.cs:1399-1411
        }
        FunctionCode.ADDDEFCHARA -> {
            // spec: Process.cs:1284-1294 —— CSV 0 号 + GameBase 指定的默认角色
            // （SYSTEM_TITLE 之外不可用的检查依赖父标签信息，属 proc 层）
            ctx.variables.addCharacterFromCsvNo(0L)
            val defNo = ctx.variables.registry.gameBase.defaultChara
            if (defNo > 0) ctx.variables.addCharacterFromCsvNo(defNo)
            ExecResult.Normal
        }
        FunctionCode.DELCHARA -> {
            // spec: FunctionIdentifier.cs:1385-1395 —— isDel 的**语句层**分流（原版就在这里，
            // 不在求值器里）：先把全部实参求值收进 array（:1368-1375，isDel 分支只收集不删），
            // 再按**实参个数**二选一：
            // - `:1387-1390` `array.Length == 1` → `DelCharacter(array[0])`：单参重载，
            //   越界带编号（VariableEvaluator.cs:1245）；
            // - `:1391-1394` 否则（含 0 个实参）→ `DelCharacter(array)`：数组参重载，
            //   越界丢编号（:1259，官方 bug，ADR-005 原样复刻）。
            // 求值必须先全部完成再分流：原版循环在 :1368-1384，分流在 :1385 之后。
            val ps = argTerms(stmt.args).map { it.getIntValue(ctx.eval) }
            if (ps.size == 1) {
                ctx.variables.deleteCharacter(ps[0])
            } else {
                ctx.variables.deleteCharacters(ps.toLongArray())
            }
            ExecResult.Normal
        }
        FunctionCode.DELALLCHARA -> {
            ctx.variables.deleteAllCharacters(); ExecResult.Normal // spec: Process.cs:1264-1266
        }
        FunctionCode.SWAPCHARA -> {
            val arg = stmt.args as SpSwapCharaArgument
            ctx.variables.swapCharacters(arg.x!!.getIntValue(ctx.eval), arg.y!!.getIntValue(ctx.eval))
            ExecResult.Normal
        }
        FunctionCode.COPYCHARA -> {
            val arg = stmt.args as SpSwapCharaArgument
            ctx.variables.copyCharacter(arg.x!!.getIntValue(ctx.eval), arg.y!!.getIntValue(ctx.eval))
            ExecResult.Normal
        }
        FunctionCode.SORTCHARA -> {
            val arg = stmt.args as SpSortcharaArgument
            val name = arg.sortKey.name
            val idx = resolveIndices(arg.sortKey, ctx)
            // 字符串判定要**先查用户变量再查系统表**（resolveVarInfo 同 userVar 优先序）：
            // 用户 #DIM CHARADATA 字符串列（CVARS）不在 registry.def 里，只查 registry
            // 会把它误判成整数键，排序时 readInt 直接报「字符串变量不能当整数读取」。
            // 原版按 token 的 IsString 分流（VariableEvaluator.cs:1408 SetSortKey 内部分支）。
            val isStr = ctx.variables.resolveVarInfo(name)?.operandType == OperandType.STR
            val asc = arg.sortOrder != SortOrder.DESENDING
            if (isStr) {
                ctx.variables.sortCharactersByStr(name, idx.drop(1).toLongArray(), asc)
            } else {
                ctx.variables.sortCharactersByInt(name, idx.drop(1).toLongArray(), asc)
            }
            ExecResult.Normal
        }
        FunctionCode.PICKUPCHARA -> {
            // spec: Process.cs:1267-1283（逐实参「求值→判界」交错；豁免 = 实参本身是
            // MASTER/ASSI/TARGET 变量引用 —— 这三者可合法取 -1，Process.cs:1277-1280
            // 的 `aExpression2 is VariableTerm && Identifier.Code ∈ {MASTER,ASSI,TARGET}`）+
            // VariableEvaluator.cs:1288-1353（PickUpChara：去重跳负 / 交换就位 / 指针重绑）。
            val terms = argTerms(stmt.args)
            val n = ctx.variables.characters.count
            val nos = LongArray(terms.size)
            for ((i, term) in terms.withIndex()) {
                nos[i] = term.getIntValue(ctx.eval)
                val exempt = term is VarRef && term.name.uppercase() in PICKUP_CHARA_EXEMPT_VARS
                if (!exempt && (nos[i] < 0 || nos[i] >= n)) {
                    throw CodeEE("命令PICKUPCHARA的第${i + 1}个参数的值(${nos[i]})超过角色列表的范围")
                }
            }
            ctx.variables.pickUpCharacters(nos)
            ExecResult.Normal
        }
        FunctionCode.RESET_STAIN -> {
            // spec: Process.cs:1557-1560 + VariableEvaluator.cs:1903-1922（默认表外元素清零）
            val target = evalIntArg(stmt.args, ctx)
            // spec: VariableEvaluator.cs:1923-1930 `SetDefaultStain(long no)`：越界抛
            // `Lang.Error.RefUndefinedChara` —— 文案照抄官方 zhs
            // （lang/emuera-zhs.xml:319 = `试图引用一个尚未定义的角色`），
            // 与 OoRCharaNum（`试图引用一个不存在的注册角色`，:312）是两条不同键，不得混用。
            if (target < 0 || target >= ctx.variables.characters.count) {
                throw CodeEE("试图引用一个尚未定义的角色")
            }
            val def = ctx.config.stainDefault
            val cap = ctx.variables.getLength(STAIN, 0)
            for (e in 0 until cap) {
                ctx.variables.writeInt(STAIN, longArrayOf(target, e.toLong()), if (e < def.size) def[e] else 0L)
            }
            ExecResult.Normal
        }

        // ---- 进程状态 ----
        FunctionCode.SKIPDISP -> {
            val v = evalIntArg(stmt.args, ctx) != 0L // spec: Process.cs:1587-1592
            ctx.skipPrint = v
            ctx.variables.writeInt(RESULT, longArrayOf(0), if (v) 1L else 0L)
            ExecResult.Normal
        }
        FunctionCode.NOSKIP -> {
            // spec: Process.cs:1593-1603（ENDNOSKIP 配对缺失检查依赖块结构，属解析/proc 层）
            ctx.saveSkip = ctx.skipPrint
            if (ctx.skipPrint) ctx.skipPrint = false
            ExecResult.Normal
        }
        FunctionCode.ENDNOSKIP -> {
            if (ctx.saveSkip) ctx.skipPrint = true // spec: Process.cs:1604-1613
            ExecResult.Normal
        }
        FunctionCode.SKIPLOG -> {
            ctx.mesSkip = evalIntArg(stmt.args, ctx) != 0L; ExecResult.Normal // spec: Process.cs:1830-1833
        }
        FunctionCode.FORCEKANA -> {
            // spec: Process.cs:1583-1586 + ExpressionMediator.cs:34-43
            val v = evalIntArg(stmt.args, ctx)
            if (v < 0 || v > 3) throw CodeEE("命令FORCEKANA的参数超过可指定范围(0～3)")
            ExecResult.Normal // 假名转换依赖 VB StrConv，宿主层暂无效果（TODO(verify) M5）
        }
        FunctionCode.GETTIME -> {
            // spec: Process.cs:1396-1408 —— 逐字段拼 yyyymmddhhmmssmmm；RESULTS 给 "yyyy/MM/dd HH:mm:ss"。
            // 时钟只有一个出口：宿主注入点 BuiltinHost.clockMillis（Builtins.kt:30 声明，
            // BootHosts.kt:87-88 的 KDoc 明写它就是「对应 GETTIME 家族」的注入点；表达式位的
            // GETTIME / GETTIMES 方法本就吃它，HostMethods.kt:117-129 是同一条路子的现成写法）。
            // 本语句此前直接读 java.time.LocalDateTime.now()，绕过注入点 —— 后果不是「值不对」而是
            // 「宿主钉不住」：E2E / L3 装置无法把时钟固定，两次运行的 RESULT 数值、RESULTS 原文
            // （经 VariableStore.kt:202-214 的 pool.intern 进驻留池）以及由它折算的 DT 行主键
            // （MapDataTableMethods.kt:494-501，写进 RESULT:1 起的格）全部漂移，L3 链必分叉。
            // 语境拿不到 BuiltinContext 的情况 = 语句层裸假体（SemTestEnv.TestEvalContext 一类单测），
            // 回落 BuiltinHost.DEFAULT，其 clockMillis 就是 System.currentTimeMillis 本身（Builtins.kt:30）；
            // 产线 ProcessRuntime 的 execContext.eval 恒为 BuiltinEvalContext（ProcessRuntime.kt:256-262、
            // :360-362），未注入 builtinHost 时用 SemConfigHost（ProcessRuntime.kt:1226）——它没覆写
            // clockMillis，取到的仍是同一个 System.currentTimeMillis，故产线行为逐字节不变。
            // 时区口径：原版 DateTime.Now 是本地墙上时间，「本地」走宿主注入点
            // （BuiltinHost.zoneId，与 clockMillis 同协议）——注册期/写死 systemDefault 会让
            // 装置钉不住时区，L3 链 #10 起跨环境漂移。
            val host = (ctx.eval as? BuiltinContext)?.host ?: BuiltinHost.DEFAULT
            val now = java.time.Instant.ofEpochMilli(host.clockMillis())
                .atZone(host.zoneId())
                .toLocalDateTime()
            var num = now.year.toLong()
            num = num * 100 + now.monthValue
            num = num * 100 + now.dayOfMonth
            num = num * 100 + now.hour
            num = num * 100 + now.minute
            num = num * 100 + now.second
            num = num * 1000 + (now.nano / 1_000_000)
            ctx.variables.writeInt(RESULT, longArrayOf(0), num)
            ctx.variables.writeStr(
                RESULTS, longArrayOf(0),
                "%04d/%02d/%02d %02d:%02d:%02d".format(java.util.Locale.ROOT, now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second),
            )
            ExecResult.Normal
        }
        FunctionCode.RESETDATA -> {
            // spec: FunctionIdentifier.cs:1844-1848（DoInstruction 方法体恰两句）——
            // :1846 `exm.VEvaluator.ResetData();` → :1847 `exm.Console.ResetStyle();`。
            // 复位顺序按原版：样式复位紧随 resetData 之后。
            // 复位集恰四项（前景/样式位/字体名/对齐，EmueraConsole.cs:2838-2842），
            // 不含 bgColor（:200）/redraw（:455）/打印缓冲 —— 由 ConsoleSink.resetStyle 保证。
            // 反向防误接：RESETGLOBAL（FunctionIdentifier.cs:1851-1863）原版不复位样式。
            // C# ResetData 的第一步是 RemoveEMSaveData（VariableEvaluator.cs:1338 →
            // VariableData.cs:4006-4029：按 SaveMaps/SaveXmls/SaveDTs 清上一局 SAVE 命名
            // MAP/XML/DT）；此前只有 LOADDATA 接了这一步（FileSystemOps.loadData），
            // RESETDATA/回标题后旧局扩展池残留、XML_EXIST/MAP_GETKEYS/DT_CELL_GET 读到旧数据
            // （2026-10-26 fixcore 审查 B1；语料 TITLE.ERB:115 的 RESETDATA 可达）。
            SaveWiring.of(ctx.variables)?.emSets?.let { em -> EmDataPools.clear(ctx.variables, em.save) }
            ctx.variables.resetData()
            ctx.console.resetStyle()
            ctx.skipPrint = false
            ctx.mesSkip = false
            ctx.saveSkip = false
            ExecResult.Normal
        }
        FunctionCode.RESETGLOBAL -> {
            // spec: FunctionIdentifier.cs:1851-1863（指令体 :1859-1862 只一句
            //   `exm.VEvaluator.ResetGlobalData();`，:1861）⇒ 复位面在求值层
            //   VariableEvaluator.cs:1348-1353 的 **三步**：
            //   ① `RemoveEMGlobalData()`（VariableData.cs:4031-4054）：按 `GlobalSaveMaps`
            //     清 MAP（表留着、内容清空）、按 `GlobalSaveXmls` **移除** XML、
            //     按 `GlobalSaveDTs` 清 DT 行 ⇒ 移植侧 `EmDataPools.clear(store, emSets.global)`。
            //   ② `RemoveEMStaticData()`（:4056-4079）：同型三遍，走 `StaticMaps/StaticXmls/StaticDTs`
            //     （注册处 ConstantData.cs:1389-1405+ 的 `STATIC_XMLS`/`STATIC_MAPS`/`STATIC_DTS`）。
            //     本工程 `EmSaveSets.parse`（core/save/EmSaveSets.kt:83-89）只收 `SAVE_*`/`GLOBAL_*`
            //     六条指令，`STATIC_*` 三表**未解析** ⇒ 本步接不上，登记为需组长接线，
            //     未接前本语句复位不到 STATIC 命名的池（名字表为空 ⇒ 与不执行等价，不会误清）。
            //   ③ `SetDefaultGlobalValue()`（:3347-3363）：`dataIntegerArray[63]`（GLOBAL 整表）
            //     置 0、`dataStringArray[5]`（GLOBALS）**置 null 而不是空串**（:3357），
            //     再对 `userDefinedGlobalVarList` 逐个 `SetDefault()`（:3359-3362）。
            //     `SetDefault()` 的口径是「回到 `#DIM`/`DEFAULT` 声明的初值」而非归零：
            //     `StaticInt1DVariableToken.SetDefault`（:1323-1332）先清零再回填 `defArray`
            //     （`defArray = data.DefaultInt`，构造 :1308）；`StaticStr1DVariableToken.SetDefault`
            //     （:1595-1606）在 `defArray` 非空时**只回填前段、不动其后元素**（官方怪癖，
            //     ADR-005 原样复刻，移植侧 `StaticStrUserVar.setDefault` 已照抄）。
            //     GLOBAL 用户变量的入表面是 `CreateUserDefVariable`（:3246-3268 的
            //     `userDefinedGlobalVarList.Add`），移植侧同名集合 = `globalUserVars`。
            //     注意 GLOBALS 的 null 语义：本工程字符串槽以驻留池下标 0 表示「未赋值」，
            //     而 `StringTable` 的 0 号槽就是空串（StringTable.kt:19-22 的 init），
            //     即移植侧**不存在** null 与空串的可观测区分 ⇒ 以 fill(0) 落地（口径差异登记）。
            // 反向防误接：RESETGLOBAL 原版**不**复位样式（样式复位只在 RESETDATA，
            //   EmueraConsole.cs:2838-2842），也**不**清角色表、不动 STATIC 用户变量、
            //   不动局部变量 —— 与 RESETDATA（:1336-1346）是两个互不重叠的复位面。
            ctx.variables.resetGlobalValues()
            // 组长接线（M4-N）：① RemoveEMGlobalData + ② RemoveEMStaticData 两次同型清空。
            // 名字表来自 `wave/n-saveaudit` 落地的 `EmSaveSets.staticSets`（`ConstantData.cs:1388-1408`
            // 的 STATIC_XMLS/STATIC_MAPS/STATIC_DTS ⇒ `VariableData.cs:4056-4079`）。
            // **不进 loadAccept**：原版存盘侧没有 static 支、读档接受集只判 `Save* || Global*`
            // （`:4099`/`:4109`/`:4119`）。未装配会话 = 空表 = 空操作。
            SaveWiring.of(ctx.variables)?.emSets?.let { em ->
                EmDataPools.clear(ctx.variables, em.global)
                EmDataPools.clear(ctx.variables, em.staticSets)
            }
            ExecResult.Normal
        }
        FunctionCode.PUTFORM -> {
            // spec: Process.cs:1295-1306 —— 追加目标就是系统字符串变量 SAVEDATA_TEXT 本体
            // （VariableEvaluator.cs:119-129：SAVEDATA_TEXT 属性即 varData.DataString[0]，
            // PUTFORM 直接 `SAVEDATA_TEXT += text`；RESETDATA 经变量复位清空，不经旁路状态）。
            val text = evalStrArg(stmt.args, ctx)
            val cur = ctx.variables.readStr(SAVEDATA_TEXT, longArrayOf())
            ctx.variables.writeStr(SAVEDATA_TEXT, longArrayOf(), cur + text)
            ExecResult.Normal
        }
        FunctionCode.ASSERT -> {
            if (evalIntArg(stmt.args, ctx) == 0L) throw CodeEE("ASSERT的参数为0") // spec: Process.cs:1790-1795
            ExecResult.Normal
        }
        FunctionCode.THROW -> {
            throw CodeEE(evalStrArg(stmt.args, ctx)) // spec: Process.cs:1796-1797（可被 TRYC 捕获）
        }

        // ---- 调试 ----
        FunctionCode.DEBUGPRINT, FunctionCode.DEBUGPRINTFORM -> {
            ctx.debug.print(evalStrArg(stmt.args, ctx)); ExecResult.Normal // spec: FunctionIdentifier.cs:540-568
        }
        FunctionCode.DEBUGPRINTL, FunctionCode.DEBUGPRINTFORML -> {
            ctx.debug.printLine(evalStrArg(stmt.args, ctx)); ExecResult.Normal
        }
        FunctionCode.DEBUGCLEAR -> {
            ctx.debug.clear(); ExecResult.Normal // spec: FunctionIdentifier.cs:570-582
        }

        // ---- 数据节 ----
        FunctionCode.PRINTDATA, FunctionCode.PRINTDATAL, FunctionCode.PRINTDATAW,
        FunctionCode.PRINTDATAK, FunctionCode.PRINTDATAKL, FunctionCode.PRINTDATAKW,
        FunctionCode.PRINTDATAD, FunctionCode.PRINTDATADL, FunctionCode.PRINTDATADW,
        -> {
            execPrintData(stmt, ctx, code); ExecResult.Normal
        }
        FunctionCode.STRDATA -> {
            execStrData(stmt, ctx); ExecResult.Normal
        }

        // ---- 声音 ----
        FunctionCode.PLAYSOUND -> {
            val arg = stmt.args as SpHtmlPrint
            // spec: FunctionIdentifier.cs:2625 —— Opt 为 null 取 1，否则 Math.Max(实参,1L) 在 long 域
            // 求值后按 unchecked (int) 截断：4294967295 截成 -1（Sound.cs 据此进 LoopStream）。
            // 截断后再 coerceAtLeast(1) 会把 -1 吃成 1，与原版相反。
            val count = arg.opt?.getIntValue(ctx.eval)?.let { if (it < 1L) 1L else it }?.toInt() ?: 1
            ctx.sound.playSound(evalStr(arg.str, ctx), count); ExecResult.Normal
        }
        FunctionCode.STOPSOUND -> {
            ctx.sound.stopSound(); ExecResult.Normal
        }
        FunctionCode.PLAYBGM -> {
            ctx.sound.playBgm(evalStrArg(stmt.args, ctx)); ExecResult.Normal
        }
        FunctionCode.STOPBGM -> {
            ctx.sound.stopBgm(); ExecResult.Normal
        }
        FunctionCode.SETSOUNDVOLUME -> {
            ctx.sound.setSoundVolume(evalIntArg(stmt.args, ctx).toInt()); ExecResult.Normal
        }
        FunctionCode.SETBGMVOLUME -> {
            ctx.sound.setBgmVolume(evalIntArg(stmt.args, ctx).toInt()); ExecResult.Normal
        }
        FunctionCode.AWAIT -> {
            // spec: FunctionIdentifier.cs:2581-2606 → EmueraConsole.cs:840-856
            // 无参时原版取 -1，而 `if (time > 0) Thread.Sleep(time)` ⇒ 无参 AWAIT 只让出不睡；
            // 本侧缺省求值给 0（evalIntArg），与「不睡」同观感。
            val v = evalIntArg(stmt.args, ctx)
            if (v < 0) throw CodeEE("AWAIT命令:指定了一个负值($v)")
            // 文案照抄官方 zhs：`Lang.Error.AwaitArgIsOver10Seconds`（lang/emuera-zhs.xml:529）
            // = `AWAIT命令:指定了一个超过10秒的值({0}ms)`，占位符是**毫秒原值**；
            // C# 抛点 `FunctionIdentifier.cs:2599-2602`（num.ToString() 不换算单位）。
            if (v > 10000) throw CodeEE("AWAIT命令:指定了一个超过10秒的值(${v}ms)")
            // 帧发布（CONSOLE-FRAMEGATE）：原版 Await 的第一步就是 RefreshStrings(force_Paint: true)
            // （EmueraConsole.cs:847）——让出边界是唯一的整帧上屏点。先发布本段积攒的完整帧，
            // 再睡；睡醒冻结，让下一个脚本段的变化积累成一帧（原版 DoEvents 模型的等价落点）。
            ctx.console.publishFrame()
            ctx.timer.delay(v)
            ctx.console.holdFrame()
            // 原版死循环保护的唯一复位调用点就在这里（Process.cs:487-491 只被
            // EmueraConsole.cs:849 调用一次）⇒ AWAIT 必须计成让出边界，否则像
            // `@BADAPPLE`（TITLE.ERB:123，FOR count,1,6573 + 每帧 AWAIT/AWAIT 16，
            // TITLE.ERB:152-156）这种「整段只靠 AWAIT 逐帧让出」的动画会在一次 run 里
            // 攒满段落计数、撞死循环闸。
            yieldBoundaryCount++
            ExecResult.Normal
        }

        // ---- 系统钩子（SystemOps）----
        FunctionCode.SAVEDATA -> {
            // spec: Process.cs:1327-1349
            val arg = stmt.args as SpSaveDataArgument
            val no = arg.target?.getIntValue(ctx.eval) ?: -1L
            if (no < 0) throw CodeEE("对SAVEDATA的参数使用负值($no)")
            if (no > Int.MAX_VALUE) throw CodeEE("SAVEDATA的参数值过大($no)")
            val text = arg.strExpression?.let { evalStr(it, ctx) } ?: ""
            if (text.contains('\n')) {
                throw CodeEE("SAVEDATA对的提示文本使用了换行符号（由于会导致存档损坏，不能使用换行符）")
            }
            if (!ops(ctx).saveData(no.toInt(), text)) {
                ctx.console.printError("SAVEDATA命令在存档时发生了意料之外的错误")
            }
            ExecResult.Normal
        }
        FunctionCode.LOADDATA -> {
            // spec: Process.cs:1841-1863（doFlowControlFunction 的 LOADDATA 分支）
            val no = evalIntArg(stmt.args, ctx)
            // :1844-1847；文案 Lang.Error.LoaddataArgIsNegative（lang/emuera.zhs.xml:441）
            if (no < 0) throw CodeEE("LOADDATA的参数被指定为负数($no)")
            // :1848-1851；文案 Lang.Error.TooLargeLoaddataArg（lang/emuera.zhs.xml:672）
            if (no > Int.MAX_VALUE) throw CodeEE("LOADDATA的参数值($no)过大")
            // :1852-1859（CheckData → LoadCorruptedData / LoadFrom → UnexpectedErrorInLoaddata）
            ops(ctx).loadData(no.toInt())
            // :1860-1862 —— `state.ClearFunctionList(); state.SystemState =
            // SystemStateCode.LoadData_DataLoaded; return false;`
            //
            // 本仓的等价落点（不许新造状态枚举）：
            // - `ClearFunctionList` 的可执行等价是「立即结束当前脚本帧」——原版清空函数栈后
            //   `runScriptProc` 直接返回、`DoScript` 转 `runSystemProc`（Process.cs:449-452）；
            //   本层能表达该语义的唯一出口是 [ExecResult.Quit]（`ProcessRuntime.kt:468`
            //   映射成 `StmtOutcome.Stop(0)`；`Normal` 会让下一条语句继续跑，与清栈相反）。
            // - 状态跳转由 `FileSystemOps.loadData` 在 `LASTLOAD_NO` 之后经
            //   [com.eratw.port.core.host.SaveLoadHandoff] 记账（顺序照 :1860→:1861），
            //   proc 侧消费点见该类 KDoc（ProcessRuntime/BeginFlow 不在本路文件面内）。
            ExecResult.Quit()
        }
        FunctionCode.DELDATA -> {
            // spec: FunctionIdentifier.cs:1944-1957 —— 参数过 toUInt32inArg(value, "DELDATA", 1)
            // （:4320-4331：先负值、后 > Int.MAX_VALUE），文案 Lang.Error.ArgIsNegative
            // / ArgIsTooLarge（lang/emuera.zhs.xml:31 / :61）⇒「函数DELDATA: 第1个参数…」。
            val no = evalIntArg(stmt.args, ctx)
            if (no < 0) throw CodeEE("函数DELDATA: 第1个参数被是负值($no)")
            if (no > Int.MAX_VALUE) throw CodeEE("函数DELDATA: 第1个参数($no)过大")
            ops(ctx).delData(no.toInt())
            ExecResult.Normal
        }
        FunctionCode.SAVEGAME -> execSaveLoadGame(ctx, save = true)
        FunctionCode.LOADGAME -> execSaveLoadGame(ctx, save = false)
        FunctionCode.SAVEGLOBAL -> {
            ops(ctx).saveGlobal(); ExecResult.Normal
        }
        FunctionCode.LOADGLOBAL -> {
            val ok = ops(ctx).loadGlobal() // spec: FunctionIdentifier.cs:1815-1834
            ctx.variables.writeInt(RESULT, longArrayOf(0), if (ok) 1L else 0L)
            ExecResult.Normal
        }
        FunctionCode.SAVECHARA -> {
            // spec: FunctionIdentifier.cs:1869（SP_SAVECHARA_ArgumentBuilder：[string, string, long],
            // minArg=3）—— 参数序 = (文件名, 描述, 角色号...)；nos 从 arguments[3] 起（:1883
            // `array[i] = toUInt32inArg(intValue, "SAVECHARA", i + 3)`）。旧实现取 dropLast(2)
            // 与原版相反，曾致 SAVECHARA 静默写不出文件（E2E IO-CHARAFILE 卡 AFTER-LOAD）。
            val terms = argTerms(stmt.args)
            val filename = evalStr(terms[0], ctx)
            val text = evalStr(terms[1], ctx)
            val nos = terms.drop(2).map { it.getIntValue(ctx.eval) }
            ops(ctx).saveChara(nos, filename, text)
            ExecResult.Normal
        }
        FunctionCode.LOADCHARA -> {
            ops(ctx).loadChara(evalStrArg(stmt.args, ctx)); ExecResult.Normal
        }
        FunctionCode.UPDATECHECK -> {
            ctx.variables.writeInt(RESULT, longArrayOf(0), ops(ctx).updateCheck()); ExecResult.Normal
        }
        FunctionCode.CALLTRAIN -> {
            ops(ctx).callTrain(evalIntArg(stmt.args, ctx)); ExecResult.Normal
        }
        FunctionCode.STOPCALLTRAIN -> {
            ops(ctx).stopCallTrain(); ExecResult.Normal
        }
        FunctionCode.DOTRAIN -> {
            // 进程内状态机语句（状态门 + doTrainSelectCom + Train_DoTrain），不是宿主能力：
            // proc 层注入的钩子优先；纯 sem 装置（无 proc）保留 SystemOps 的「未实现」口径。
            val no = evalIntArg(stmt.args, ctx)
            val hook = ctx.doTrainHook
            if (hook != null) hook(no) else ops(ctx).doTrain(no)
            ExecResult.Normal
        }
        FunctionCode.UPCHECK -> {
            ops(ctx).upcheck(); ExecResult.Normal
        }
        FunctionCode.CUPCHECK -> {
            ops(ctx).cupcheck(evalIntArg(stmt.args, ctx)); ExecResult.Normal
        }

        FunctionCode.PRINT_ITEM -> {
            // spec: Process.cs:1207-1212 —— GetHavingItemsString（VariableEvaluator.cs:1037-1063）
            if (!ctx.skipPrint) {
                ctx.console.print(havingItemsString(ctx))
                ctx.console.newLine()
            }
            ExecResult.Normal
        }
        FunctionCode.PRINT_SHOPITEM -> {
            // spec: Process.cs:1214-1262 —— min(ITEMSALES, ITEMNAME, ITEMPRICE) 内 ItemSales(k)
            // 非 0 项 PrintC "[{k}] {名}({价}{币})"，每 PrintCPerLine 项 flush 分行，收尾再 flush。
            if (!ctx.skipPrint) {
                val salesLen = ctx.variables.getLength("ITEMSALES", -1)
                val nameLen = ctx.variables.registry.csvTable("ITEM").size
                val priceLen = ctx.variables.getLength("ITEMPRICE", -1)
                var bound = minOf(salesLen, nameLen)
                if (bound > priceLen) bound = priceLen
                var printed = 0
                for (k in 0 until bound) {
                    if (ctx.variables.readInt("ITEMSALES", longArrayOf(k.toLong())) == 0L) continue
                    val name = ctx.variables.registry.csvTable("ITEM")[k]
                    val price = ctx.variables.readInt("ITEMPRICE", longArrayOf(k.toLong()))
                    // MoneyLabel 取原版缺省 "$"（ConfigData.cs:159；基准 CHARA-SHOP 节无在售项不触发，TODO(M4): 接 ConfigState）
                    ctx.console.printC("[$k] $name($price$)", alignRight = false)
                    printed++
                    if (ctx.config.printCPerLine > 0 && printed % ctx.config.printCPerLine == 0) {
                        ctx.console.newLine()
                    }
                }
                val dirty = if (ctx.config.printCPerLine > 0) printed % ctx.config.printCPerLine != 0 else printed > 0
                if (dirty) ctx.console.newLine()
                // RefreshStrings（:1252）重绘动作不移植
            }
            ExecResult.Normal
        }
        FunctionCode.DT_COLUMN_OPTIONS -> {
            // spec: FunctionIdentifier.cs:2531-2578 + ArgumentParser.cs:233-324（SpDtColumnOptions）。
            // 成功路径不写 RESULT（:2554-2577）；缺表/缺列由 dtColumnOptionsDefault 写 -1/0。
            val arg = stmt.args as SpDtColumnOptions
            val table = evalStr(arg.dt, ctx)
            val column = evalStr(arg.column, ctx)
            for ((opt, value) in arg.options.zip(arg.values)) {
                when (opt) {
                    SpDtColumnOptions.DTOption.DEFAULT ->
                        dtColumnOptionsDefault(ctx.variables, table, column, value?.toValue(ctx.eval))
                }
            }
            ExecResult.Normal
        }

        // ---- 始终抛未实现（原版占位语句）----
        FunctionCode.REF, FunctionCode.REFBYNAME, FunctionCode.SAVEVAR, FunctionCode.LOADVAR,
        -> throw CodeEE("语句 $code 尚未实现（NotImplCodeEE）")

        // ---- CALLSHARP：本地方法调用 + 变量实参回写 ----
        FunctionCode.CALLSHARP -> {
            execCallSharp(stmt, ctx)
            ExecResult.Normal
        }

        // ---- BEGIN ----
        FunctionCode.BEGIN, FunctionCode.FORCE_BEGIN -> {
            ExecResult.Begin(evalStrArg(stmt.args, ctx))
        }

        // ---- 低频 UI（Windows 专属，M4 接管）----
        FunctionCode.TOOLTIP_SETCOLOR, FunctionCode.TOOLTIP_SETDELAY, FunctionCode.TOOLTIP_SETDURATION,
        FunctionCode.TOOLTIP_SETFONT, FunctionCode.TOOLTIP_SETFONTSIZE, FunctionCode.TOOLTIP_CUSTOM,
        FunctionCode.TOOLTIP_FORMAT, FunctionCode.TOOLTIP_IMG,
        -> ExecResult.Normal // TODO(M4): 工具提示 UI 参数收集

        // ---- 控制流已编译为 VM 指令：运行期到达即 no-op；块标记语句无行为 ----
        FunctionCode.IF, FunctionCode.ELSEIF, FunctionCode.ELSE, FunctionCode.ENDIF,
        FunctionCode.SIF, FunctionCode.SELECTCASE, FunctionCode.CASE, FunctionCode.CASEELSE,
        FunctionCode.ENDSELECT, FunctionCode.FOR, FunctionCode.NEXT, FunctionCode.REPEAT,
        FunctionCode.REND, FunctionCode.WHILE, FunctionCode.WEND, FunctionCode.DO,
        FunctionCode.LOOP, FunctionCode.BREAK, FunctionCode.CONTINUE, FunctionCode.RESTART,
        FunctionCode.RETURNF,
        FunctionCode.GOTO, FunctionCode.GOTOFORM, FunctionCode.TRYGOTO, FunctionCode.TRYGOTOFORM,
        FunctionCode.TRYCGOTO, FunctionCode.TRYCGOTOFORM, FunctionCode.CALL, FunctionCode.CALLFORM,
        FunctionCode.TRYCALL, FunctionCode.TRYCALLFORM, FunctionCode.TRYCCALL, FunctionCode.TRYCCALLFORM,
        FunctionCode.TRYCJUMP, FunctionCode.TRYCJUMPFORM, FunctionCode.TRYJUMP, FunctionCode.TRYJUMPFORM,
        FunctionCode.JUMP, FunctionCode.JUMPFORM, FunctionCode.TRYJUMPLIST,
        FunctionCode.TRYCALLLIST, FunctionCode.TRYGOTOLIST,
        FunctionCode.CATCH, FunctionCode.ENDCATCH, FunctionCode.ENDFUNC, FunctionCode.FUNC,
        FunctionCode.CALLEVENT, FunctionCode.CALLF, FunctionCode.CALLFORMF,
        FunctionCode.TRYCALLF, FunctionCode.TRYCALLFORMF,
        FunctionCode.DATALIST, FunctionCode.ENDLIST, FunctionCode.ENDDATA,
        -> ExecResult.Normal

        else -> throw CodeEE("语句 $code 尚未实现（StatementExecutor）")
    }

    private fun execFlow(code: FunctionCode, ctx: ExecContext): ExecResult = when (code) {
        FunctionCode.QUIT -> ExecResult.Quit()
        FunctionCode.QUIT_AND_RESTART -> ExecResult.Quit(restart = true)
        FunctionCode.FORCE_QUIT -> ExecResult.Quit(force = true)
        FunctionCode.FORCE_QUIT_AND_RESTART -> ExecResult.Quit(restart = true, force = true)
        else -> ExecResult.Normal // BREAK/CONTINUE/RESTART 等由 VM 指令处理
    }

    // ==================================================================
    // RETURN 家族
    // ==================================================================

    /**
     * RETURN：按书写顺序求值全部项，SetResultX 只写 RESULT:0..n-1，不清除更高下标。
     * 无参（或空列表）写 RESULT:0=0。spec: FunctionIdentifier.cs:3250-3271、VariableEvaluator.cs:2010-2016
     */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execReturn(stmt: ReturnStmt, ctx: ExecContext): ExecResult {
        // 空列表理论不发生（ArgBuilders 已补 0），仍按原版兜底（spec: FunctionIdentifier.cs:3265-3268）。
        val values = stmt.values.map { it?.getIntValue(ctx.eval) ?: 0L }.ifEmpty { listOf(0L) }
        setResultX(ctx, values)
        // 原版 `RETURN_Instruction.DoInstruction` 的顺序是「先 SetResultX，再
        // `state.Return(RESULT:0)`」（spec: FunctionIdentifier.cs:3265-3271）；而
        // `ProcessState.Return` 的**第一条**是 `if (IsFunctionMethod) { ReturnF(null); return; }`
        // （spec: ProcessState.cs:289-295）—— 方法帧内 `RETURN x` 的方法返回值是 **null**
        // （消费侧 `SuperUserDefinedMethodTerm.GetIntValue` 得 0，`…#Term.cs:24-31`），
        // 不是 RESULT:0 里的 x。RESULT 数组本身照旧被上面写满，只有「方法返回值」这一路被清。
        if (ctx.isFunctionMethodFrame()) return ExecResult.Return(0L)
        // 普通函数帧：原版 state.Return(exm.VEvaluator.RESULT)——写后读 RESULT:0（:3270）
        return ExecResult.Return(ctx.variables.readInt(RESULT, longArrayOf(0L)))
    }

    /**
     * RETURNFORM：FORM 串已展开为逗号分隔的整数表达式（如 "11, 3"），逐段求值后同 SetResultX。
     * spec: FunctionIdentifier.cs:3221-3238
     */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execReturnForm(stmt: ReturnFStmt, ctx: ExecContext): ExecResult {
        val text = stmt.value?.getStrValue(ctx.eval) ?: ""
        val parse = ctx.runtimeIntList
            ?: throw CodeEE("RETURNFORM 需要宿主提供运行期整数列表解析（ExecContext.runtimeIntList）")
        // 空串/空列表补 0（spec: FunctionIdentifier.cs:3232-3235）。
        val values = parse(text).ifEmpty { listOf(0L) }
        setResultX(ctx, values)
        return ExecResult.Return(ctx.variables.readInt(RESULT, longArrayOf(0L)))
    }

    /**
     * SetResultX：只写 `min(values.size, RESULT 数组长度)` 个元素，不清除更高下标
     * （spec: VariableEvaluator.cs:2010-2016；越界截断而非抛错）。
     */
    private fun setResultX(ctx: ExecContext, values: List<Long>) {
        val len = ctx.variables.getLength(RESULT, -1).toLong()
        for (i in 0 until minOf(values.size.toLong(), len)) {
            ctx.variables.writeInt(RESULT, longArrayOf(i), values[i.toInt()])
        }
    }

    // ==================================================================
    // 分派辅助：PRINTBUTTON / INPUT 家族
    // ==================================================================

    /** spec: Process.cs:1107-1144 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execPrintButton(code: FunctionCode, stmt: Statement, ctx: ExecContext) {
        if (ctx.skipPrint) return
        // 原版在求值前显式把两条开关都复位为 true（spec: Process.cs:1110-1111 / :1129-1130）：
        // 这会把上一条异常泄漏出来的 D 系窗口（false）就地清除，按钮不吃 D 配色回落；
        // UseUserStyle 同点写真，按钮因此是「系统行之后第一条恢复用户样式的语句」之一。
        ctx.console.setUseUserStyle(true)
        ctx.console.setUseSetColorStyle(true)
        val arg = stmt.args as SpButtonArgument
        val text = evalStr(arg.printStrTerm, ctx).replace("\n", "")
        val word = arg.buttonWord
        when (code) {
            FunctionCode.PRINTBUTTON ->
                if (word == null || word.operandType == OperandType.INT) {
                    ctx.console.printButton(text, word?.getIntValue(ctx.eval) ?: 0L)
                } else {
                    ctx.console.printButton(text, word.getStrValue(ctx.eval))
                }
            else -> {
                val right = code == FunctionCode.PRINTBUTTONC
                if (word == null || word.operandType == OperandType.INT) {
                    ctx.console.printButtonC(text, word?.getIntValue(ctx.eval) ?: 0L, right)
                } else {
                    ctx.console.printButtonC(text, word.getStrValue(ctx.eval), right)
                }
            }
        }
    }

    /**
     * 消费一次取值输入，并把这次提交的 `keySkip` 落到 [ExecContext.mesSkip]。
     *
     * 原版顺序（`EmueraConsole.cs:1371-1456`）：`PressEnterKey` 入口先 `MesSkip = keySkip`（:1373）；
     * 本次提交跑完后若停在 `NeedValue` 输入上，输入循环清零（:1451-1456 的 while 后 `MesSkip = false`）。
     * 本移植的等待是阻塞式，等价落点 = **进入取值等待前清零**（上一条提交带来的跳过到此为止），
     * **消费后按本次提交置位**（后续 WAIT/CanSkip 输入继续被跳过）。标志由
     * [com.eratw.port.core.host.InputSink.consumeMesSkip] 一次性取出。
     */
    private fun waitInput(ctx: ExecContext, request: InputRequest): InputOutcome {
        // 脚本行身份（原版 process.getCurrentLine）：控制台世代推进的判据之一，
        // 见 EmueraConsole.newGeneration（EmueraConsole.cs:789-797）与
        // InputRequest.scriptLine 的 KDoc。未接线（纯 sem 单测）时保持 -1。
        val req = ctx.scriptLineToken?.let { request.copy(scriptLine = it()) } ?: request
        ctx.mesSkip = false
        // 帧发布（CONSOLE-FRAMEGATE）：输入等待前把本段积攒的内容整帧上屏 ——
        // 原版 WAIT 置 state=WaitInput 后主循环退到消息循环，paint 有机会跑
        // （RefreshStrings 的 state != Running 分支不再被 16ms 节流挡住）。
        ctx.console.publishFrame()
        // 选项快照：只在这一处（以及 readAnyKey）冻结当前页按钮。控制台按请求形态过滤，
        // 非取值等待（WAIT/ENTER/VOID）立即清空 —— 故事正文里的 [N] 不会进右侧栏。
        ctx.console.noteInputWait(req)
        armIsTimeout(ctx, req)
        val outcome = ctx.input.waitInput(req)
        yieldBoundaryCount++
        // 让出返回即重新冻结：本段（到下一个让出边界为止）的变化积累成一帧。
        ctx.console.holdFrame()
        if (ctx.input.consumeMesSkip()) ctx.mesSkip = true
        return outcome
    }

    /**
     * 武装限时等待：`isTimeout` 清零。原版 `setTimer()`（EmueraConsole.cs:994-1001）在
     * 每次**限时**等待真正开跑时清 `isTimeout`，而挂它的 `presetTimer()`（:982-992）只在
     * `WaitInput` 收到 `Timelimit > 0` 的请求时才走（:866-873）——非限时等待与
     * `ReadAnyKey`（:876-895 新建的请求 `Timelimit` 取默认 -1）都不碰这一位。
     *
     * 单独成函数是因为有一条「不等待但仍要武装」的路径：TWAIT 在消息跳过中照样调用了
     * `WaitInput`（FunctionIdentifier.cs:823），只是随后被输入循环跑过
     * （EmueraConsole.cs:1451-1455），清零照做。TINPUT 系的 CanSkip 快速路径不在此列
     * （原版 :1034-1048 直接跳过 `WaitInput`，不清零）。
     */
    private fun armIsTimeout(ctx: ExecContext, request: InputRequest) {
        if (request.timeLimit > 0) ctx.variables.isTimeout = false
    }

    /**
     * `ISTIMEOUT` 的回写纪律：**只有限时等待**（`Timelimit > 0`）才改写。
     * 原版 `isTimeout` 字段（EmueraConsole.cs:138）只有两个写点：
     * `setTimer()` 在限时等待开始时清零（:994-996，经 `presetTimer` 挂起，而 `presetTimer`
     * 只在 `WaitInput` 收到 `Timelimit > 0` 的请求时才走，:866-873）；
     * `endTimer()` 超时置位（:1043）。非限时等待（INPUT 系 / INPUTANY / TWAIT 第1参=0）
     * 两者都不触发 ⇒ ISTIMEOUT 保持**上一次**限时等待留下的旧值
     * （读点 = 伪变量 `ISTIMEOUT` → `Console.IsTimeOut`，VariableData.cs:1075-1085）。
     *
     * 配合 [armIsTimeout] 与宿主的**粘滞** `isTimeout` 回报（见
     * [com.eratw.port.core.proc.QueueInputSink] 第 6 条）覆盖第三种情形：
     * 限时整数等待无默认值时超时**不推进**（:1119-1128 的死等），后来靠提交推进 ——
     * 原版 `stopTimer()`（:1035-1038）不清 `isTimeout`，所以这一位仍是真。
     */
    private fun updateIsTimeout(ctx: ExecContext, request: InputRequest, outcome: InputOutcome) {
        if (request.timeLimit > 0) ctx.variables.isTimeout = outcome.isTimeout
    }

    /**
     * 消费一次任意键等待。`stopMesSkip`（FORCEWAIT）按原版在本次提交后清零跳过态
     * （`EmueraConsole.cs:1451` 的 while 条件含 `!inputReq.StopMesskip`，循环后 :1456 清零）；
     * 普通 WAIT/WAITANYKEY 则沿用本次提交的 `keySkip`。
     */
    private fun readAnyKey(ctx: ExecContext, anyKey: Boolean = false, stopMesSkip: Boolean = false) {
        // 帧发布（CONSOLE-FRAMEGATE）：同 waitInput —— 等待前整帧上屏，返回后重新冻结。
        ctx.console.publishFrame()
        // 任意键/回车等待没有取值选项：让控制台清空上一页的选项快照。
        ctx.console.noteInputWait(
            InputRequest(
                type = InputType.STR,
                form = if (anyKey) InputRequest.RequestForm.ANY_KEY else InputRequest.RequestForm.ENTER_KEY,
            ),
        )
        ctx.input.readAnyKey(anyKey, stopMesSkip)
        yieldBoundaryCount++
        ctx.console.holdFrame()
        ctx.mesSkip = if (stopMesSkip) false else ctx.input.consumeMesSkip()
    }

    /** INPUT/INPUTS/ONEINPUT/ONEINPUTS。spec: FunctionIdentifier.cs:827-1001 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execInputs(code: FunctionCode, stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as SpInputsArgument
        val isStr = code == FunctionCode.INPUTS || code == FunctionCode.ONEINPUTS
        // CanSkip 且消息跳过中：直接采用默认值（spec: FunctionIdentifier.cs:853-863）。
        // 原版此处 Mouse==null 会 NPE（ARG 缺陷）；按 ADR-005 例外条款（进程崩溃类）取 0 处理。
        if (arg.canSkip != null && ctx.mesSkip) {
            val mouse = arg.mouse?.getIntValue(ctx.eval) ?: 0L
            val slot = longArrayOf(if (mouse == 0L) 0L else 1L)
            if (isStr) ctx.variables.writeStr(RESULTS, slot, arg.def?.getStrValue(ctx.eval) ?: "")
            else ctx.variables.writeInt(RESULT, slot, arg.def?.getIntValue(ctx.eval) ?: 0L)
            return
        }
        val req = InputRequest(
            type = if (isStr) InputType.STR else InputType.INT,
            oneInput = code == FunctionCode.ONEINPUT || code == FunctionCode.ONEINPUTS,
            hasDef = arg.def != null,
            defInt = arg.def?.getIntValue(ctx.eval) ?: 0L,
            defStr = if (isStr) arg.def?.getStrValue(ctx.eval) ?: "" else "",
            mouseInput = arg.mouse?.getIntValue(ctx.eval) != 0L,
        )
        val o = waitInput(ctx, req)
        updateIsTimeout(ctx, req, o)
        if (isStr) ctx.variables.writeStr(RESULTS, longArrayOf(0), o.strValue)
        else ctx.variables.writeInt(RESULT, longArrayOf(0), o.intValue)
    }

    /** TINPUT/TINPUTS/TONEINPUT/TONEINPUTS。spec: FunctionIdentifier.cs:1003-1099 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execTInputs(code: FunctionCode, stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as SpTInputsArgument
        val isStr = code == FunctionCode.TINPUTS || code == FunctionCode.TONEINPUTS
        if (arg.canSkip != null && ctx.mesSkip) {
            val mouse = arg.mouse?.getIntValue(ctx.eval) ?: 0L
            val slot = longArrayOf(if (mouse == 0L) 0L else 1L)
            if (isStr) ctx.variables.writeStr(RESULTS, slot, arg.def?.getStrValue(ctx.eval) ?: "")
            else ctx.variables.writeInt(RESULT, slot, arg.def?.getIntValue(ctx.eval) ?: 0L)
            return
        }
        val req = InputRequest(
            type = if (isStr) InputType.STR else InputType.INT,
            oneInput = code == FunctionCode.TONEINPUT || code == FunctionCode.TONEINPUTS,
            hasDef = true,
            defInt = arg.def?.getIntValue(ctx.eval) ?: 0L,
            defStr = if (isStr) arg.def?.getStrValue(ctx.eval) ?: "" else "",
            timeLimit = arg.time?.getIntValue(ctx.eval) ?: 0L,
            displayTime = (arg.disp?.getIntValue(ctx.eval) ?: 1L) != 0L,
            timeUpMessage = arg.timeout?.let { evalStr(it, ctx) } ?: ctx.config.timeupLabel,
            mouseInput = arg.mouse?.getIntValue(ctx.eval) == 1L,
        )
        val o = waitInput(ctx, req)
        updateIsTimeout(ctx, req, o)
        if (isStr) ctx.variables.writeStr(RESULTS, longArrayOf(0), o.strValue)
        else ctx.variables.writeInt(RESULT, longArrayOf(0), o.intValue)
    }

    /**
     * BINPUT/BINPUTS/ONEBINPUT/ONEBINPUTS。spec: FunctionIdentifier.cs:2113-2529，四语句同构
     * （BINPUT :2121-2214、BINPUTS :2225-2318、ONEBINPUT :2329-2423、ONEBINPUTS :2434-2527）。
     * 1. 仅当打印缓冲有待决内容时先换行：if (!PrintBuffer.IsEmpty) NewLine()
     *    （:2123-2126/:2227-2230/:2331-2334/:2436-2439）；空态由宿主上报
     *    （[ConsoleSink.printingBufferEmpty]）。
     * 2. MesSkip 快速路径：CanSkip 且消息跳过中，不等待输入，按 mouse 选槽直接写默认值
     *    （:2145-2155/:2249-2259/:2354-2364/:2459-2469）：mouse==0 写 slot 0（RESULT/RESULTS），
     *    否则写 slot 1（RESULT_ARRAY[1]/RESULTS_ARRAY[1]）。C# 此处对 Mouse/Def 无条件解引用
     *    （缺参即 NRE 的 ARG 缺陷）；按 ADR-005 例外条款（进程崩溃类）取 0/"" 处理。
     * 3. 其余路径 C# 按控制台按钮扫描决定 WaitInput 或 num==0 兜底写默认值（:2156-2213 等）；
     *    按钮布局检测依赖控制台显示状态（M4 文字流层），当前统一走输入等待。
     *    RefreshStrings(:2127)/ApplyTextBoxChanges(:2143) 为绘制层动作，VM 侧不建模。
     * TODO(verify): 无按钮且有/无默认值的分支需要 console 按钮状态。
     */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execBInputs(code: FunctionCode, stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as SpInputsArgument
        val isStr = code == FunctionCode.BINPUTS || code == FunctionCode.ONEBINPUTS
        // 条件换行（spec: FunctionIdentifier.cs:2123-2126）：缓冲已 flush 时不补空行。
        if (!ctx.console.printingBufferEmpty) ctx.console.newLine()
        // CanSkip 且消息跳过中：直接采用默认值（spec: FunctionIdentifier.cs:2145-2155）。
        if (arg.canSkip != null && ctx.mesSkip) {
            val mouse = arg.mouse?.getIntValue(ctx.eval) ?: 0L
            val slot = longArrayOf(if (mouse == 0L) 0L else 1L)
            if (isStr) ctx.variables.writeStr(RESULTS, slot, arg.def?.getStrValue(ctx.eval) ?: "")
            else ctx.variables.writeInt(RESULT, slot, arg.def?.getIntValue(ctx.eval) ?: 0L)
            return
        }
        val req = InputRequest(
            type = if (isStr) InputType.STR else InputType.INT,
            oneInput = code == FunctionCode.ONEBINPUT || code == FunctionCode.ONEBINPUTS,
            hasDef = arg.def != null,
            defInt = arg.def?.getIntValue(ctx.eval) ?: 0L,
            defStr = if (isStr) arg.def?.getStrValue(ctx.eval) ?: "" else "",
            mouseInput = arg.mouse?.getIntValue(ctx.eval) != 0L,
        )
        val o = waitInput(ctx, req)
        updateIsTimeout(ctx, req, o)
        if (isStr) ctx.variables.writeStr(RESULTS, longArrayOf(0), o.strValue)
        else ctx.variables.writeInt(RESULT, longArrayOf(0), o.intValue)
    }

    // ==================================================================
    // 变量操作
    // ==================================================================

    /** SETBIT/CLEARBIT/INVERTBIT。spec: FunctionIdentifier.cs:731-760 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execBit(code: FunctionCode, stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as BitArgument
        val op = when (code) {
            FunctionCode.SETBIT -> 1
            FunctionCode.CLEARBIT -> 0
            else -> 2
        }
        val name = arg.dest.name
        val idx = resolveIndices(arg.dest, ctx)
        for (t in arg.terms) {
            val bit = t.getIntValue(ctx.eval)
            if (bit < 0 || bit > 63) throw CodeEE("第2参数($bit)超出范围(0-63)")
            var v = ctx.variables.readInt(name, idx)
            val mask = 1L shl bit.toInt()
            v = when (op) {
                1 -> v or mask
                0 -> v and mask.inv()
                else -> v xor mask
            }
            ctx.variables.writeInt(name, idx, v)
        }
    }

    /** SWAP。spec: Process.cs:1371-1395 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execSwap(stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as SpSwapVarArgument
        val n1 = arg.var1.name
        val i1 = resolveIndices(arg.var1, ctx)
        val n2 = arg.var2.name
        val i2 = resolveIndices(arg.var2, ctx)
        if (arg.var1.operandType != arg.var2.operandType) throw CodeEE("需要替换的变量的类型不相同")
        if (arg.var1.operandType == OperandType.INT) {
            val a = ctx.variables.readInt(n1, i1)
            ctx.variables.writeInt(n1, i1, ctx.variables.readInt(n2, i2))
            ctx.variables.writeInt(n2, i2, a)
        } else {
            val a = ctx.variables.readStr(n1, i1)
            ctx.variables.writeStr(n1, i1, ctx.variables.readStr(n2, i2))
            ctx.variables.writeStr(n2, i2, a)
        }
    }

    /** VARSET。spec: FunctionIdentifier.cs:1625-1669 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execVarSet(stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as SpVarSetArgument
        val name = arg.dest.name
        val idx = resolveIndices(arg.dest, ctx)
        val info = ctx.variables.resolveVarInfo(name)
        val isStr = arg.dest.operandType == OperandType.STR
        var start = arg.start?.getIntValue(ctx.eval)?.toInt() ?: 0
        var end = when {
            arg.end != null -> arg.end.getIntValue(ctx.eval).toInt()
            (info?.dimension ?: 1) == 1 -> ctx.variables.getLength(name)
            else -> 0
        }
        // C# 只在 Start != null 时才比较/交换（FunctionIdentifier.cs:1642-1656）：负 END + 缺 START
        // 以 (0, 负值) 进 SetValueAll ⇒ 循环不执行 = no-op。此前无条件交换会把负 END 换成负起点
        // （2026-10-26 fixcore 审查 C6；语料无负 END 用法，纯对齐）。
        if (arg.start != null && start > end) {
            val t = start; start = end; end = t
        }
        val charaPos = if (info?.isCharacterData == true && idx.isNotEmpty()) idx[0].toInt() else -1
        if (isStr) {
            ctx.variables.setAllStr(name, arg.term?.getStrValue(ctx.eval) ?: "", start, end, charaPos)
        } else {
            ctx.variables.setAll(name, arg.term?.getIntValue(ctx.eval) ?: 0L, start, end, charaPos)
        }
    }

    /**
     * CVARSET。spec: FunctionIdentifier.cs:1671-1732 + VariableEvaluator.cs:317-378。
     *
     * 与 VARSET 的唯一差别是**写哪个元素**：第 2 参是元素下标，对角色区间内每个角色写
     * `[角色, 元素]`（`SetValueAllEachChara` 的 `new long[2] { i, num }`，
     * VariableEvaluator.cs:337-343、:377-378）。旧实现把元素下标塞进 `idx` 后只保留
     * 第 1 位，角色变量因此永远只得到 1 个下标而被 `checkElement` 拒绝。
     *
     * 语义逐条对齐原文（**区间是半开 `[start, end)`**，不是 VARSET 的闭区间）：
     * - `start` 缺省 0，显式时必须在 `[0, CHARANUM)` 内（FunctionIdentifier.cs:1684-1692）；
     * - `end` 缺省 CHARANUM，显式时必须在 `[0, CHARANUM]` 内（:1693-1704，端点可等于角色数）；
     * - `start > end` 交换（:1707-1712）；
     * - 1 维角色变量的元素下标是字符串时走 `keywordToInteger`（VariableEvaluator.cs:331-336、
     *   :368-374），并做 `0 <= elem < 数组长度` 检查（OoRCharaVar）；
     * - 0 维角色变量的下标原版**被忽略**（`SetValue` 只读 `arguments[0]`，
     *   VariableData.cs:425-429、:510-513），所以这里只传角色位置。
     *
     * 原文 bug 一并复刻：`end` 越界的报错文案回填的是 `start`（FunctionIdentifier.cs:1700
     * 的第二个占位符写的是 `num2`）。
     */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execCVarSet(stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as SpCVarSetArgument
        // 原版 GetFixedVariableTerm 会求值目标变量的固定下标；CVARSET 不使用该结果
        // （SetValueAllEachChara 只按 i 与元素下标写），这里保留求值副作用。
        val name = arg.dest.name
        val info = ctx.variables.resolveVarInfo(name)
            ?: throw CodeEE("未定义的变量 $name")

        // 原版先 `Index.GetValue(exm)` 再取 CHARANUM、校验 start/end（FunctionIdentifier.cs:1683-1706）；
        // 0 维变量只忽略结果，不跳过求值（可能含 RAND 等副作用）。
        val indexExpr = arg.index
        val indexIsStr = indexExpr != null && indexExpr.operandType == OperandType.STR
        val indexInt = if (indexExpr != null && !indexIsStr) indexExpr.getIntValue(ctx.eval) else 0L
        val indexStr = if (indexExpr != null && indexIsStr) indexExpr.getStrValue(ctx.eval) else ""

        val charaNum = ctx.variables.characters.count
        var start = 0
        if (arg.start != null) {
            start = arg.start.getIntValue(ctx.eval).toInt()
            if (start < 0 || start >= charaNum) {
                throw CodeEE("CVARSET命令的第4参数($start)超出角色范围")
            }
        }
        var end = charaNum
        if (arg.end != null) {
            end = arg.end.getIntValue(ctx.eval).toInt()
            if (end < 0 || end > charaNum) {
                // 原文 bug：这里填的是 start（num2），不是刚求出的 end（num3）。
                throw CodeEE("CVARSET命令的第5参数($start)超出角色范围")
            }
        }
        if (start > end) {
            val t = start
            start = end
            end = t
        }

        if (!info.isCharacterData) throw CodeEE("CVARSET命令使用了一个非角色变量")

        // 写入值只求一次（原版在 Instruction 里先求 Term，再交给 SetValueAllEachChara）。
        val isStr = arg.dest.operandType == OperandType.STR
        val intValue = if (isStr) 0L else arg.term?.getIntValue(ctx.eval) ?: 0L
        val strValue = if (isStr) arg.term?.getStrValue(ctx.eval) ?: "" else ""

        // SetValueAllEachChara 在 CharacterList.Count == 0 时提前返回（VariableEvaluator.cs:327-330），
        // 连元素下标都不校验；只有显式 start 会先撞上上面的区间检查。
        if (charaNum == 0) return

        // 只有 1 维角色变量才解析/校验元素下标；0 维变量原版忽略它（见 KDoc）。
        val hasElement = info.dimension == 1
        var elem = 0
        if (hasElement) {
            elem = if (indexIsStr) {
                ctx.variables.keywordToInteger(name, 1, indexStr).toInt()
            } else {
                indexInt.toInt()
            }
            val len = ctx.variables.getLength(name)
            if (elem < 0 || elem >= len) {
                throw CodeEE("角色数组变量\"$name\"的第2参数($elem)超出数组范围")
            }
        }

        val full = LongArray(if (hasElement) 2 else 1)
        if (hasElement) full[1] = elem.toLong()
        for (c in start until end) {
            full[0] = c.toLong()
            if (isStr) ctx.variables.writeStr(name, full.copyOf(), strValue)
            else ctx.variables.writeInt(name, full.copyOf(), intValue)
        }
    }

    /** VARSIZE。spec: Process.cs:1321-1326 + VariableEvaluator.cs:1932-1950（写 RESULT 数组） */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execVarSize(stmt: Statement, ctx: ExecContext) {
        val name = (stmt.args as SpVarsizeArgument).varName
        val info = ctx.variables.resolveVarInfo(name) ?: throw CodeEE("未定义的变量 $name")
        if (info.dimension >= 2) {
            for (d in 0 until info.dimension) {
                ctx.variables.writeInt(RESULT, longArrayOf(d.toLong()), ctx.variables.getLength(name, d).toLong())
            }
        } else {
            ctx.variables.writeInt(RESULT, longArrayOf(0), ctx.variables.getLength(name).toLong())
        }
    }

    /** POWER。spec: Process.cs:1350-1370 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execPower(stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as SpPowerArgument
        val x = arg.x?.getIntValue(ctx.eval)?.toDouble() ?: 0.0
        val y = arg.y?.getIntValue(ctx.eval)?.toDouble() ?: 0.0
        val r = Math.pow(x, y)
        if (r.isNaN()) throw CodeEE("幂运算结果不是数值")
        if (r.isInfinite()) throw CodeEE("幂运算结果无限大")
        if (r >= 9.223372036854776E18 || r <= -9.223372036854776E18) {
            throw CodeEE("幂运算结果($r)超过64位带符号整数的有效范围")
        }
        writeVarRef(arg.dest, r.toLong(), ctx)
    }

    /** ENCODETOUNI。spec: Process.cs:1773-1789（写 RESULT 数组） */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execEncodeToUni(stmt: Statement, ctx: ExecContext) {
        val text = evalStrArg(stmt.args, ctx)
        val cap = ctx.variables.getLength(RESULT)
        if (text.length > cap - 1) {
            throw CodeEE("ENCODETOUNI的参数过长（目前共${text.length}字符。最大${cap - 1}字符）")
        }
        var i = 0
        var off = 0
        while (off < text.length) {
            val cp = text.codePointAt(off)
            // RESULT[0] = 字符数，码点从 RESULT[1] 起（spec: VariableEvaluator.cs:1990-1998）
            ctx.variables.writeInt(RESULT, longArrayOf((i + 1).toLong()), cp.toLong())
            off += Character.charCount(cp)
            i++
        }
        ctx.variables.writeInt(RESULT, longArrayOf(0L), i.toLong())
    }

    /** SPLIT。spec: Process.cs:1561-1576 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execSplit(stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as SpSplitArgument
        val target = evalStr(arg.targetStr, ctx)
        val sep = evalStr(arg.split, ctx)
        val parts = if (sep.isEmpty()) listOf(target) else target.split(sep)
        arg.num?.let { writeVarRef(it, parts.size.toLong(), ctx) }
        val info = ctx.variables.resolveVarInfo(arg.destVarName)
            ?: throw CodeEE("未定义的变量 ${arg.destVarName}")
        val dim = maxOf(info.dimension, 1)
        val idx = LongArray(dim)
        val cap = ctx.variables.getLength(arg.destVarName)
        val n = minOf(parts.size, cap)
        for (i in 0 until n) {
            idx[dim - 1] = i.toLong()
            ctx.variables.writeStr(arg.destVarName, idx.copyOf(), parts[i])
        }
    }

    // ---- 数组控制 ----

    /** 读出一维数组切片（支持角色变量：idx[0] 为角色位置，其余为元素下标前缀）。 */
    private fun array1D(ctx: ExecContext, name: String, idx: LongArray, isStr: Boolean): MutableList<Any> {
        val len = ctx.variables.getLength(name, 0)
        val out = ArrayList<Any>(len)
        for (i in 0 until len) {
            val full = if (idx.size > 1) longArrayOf(idx[0], i.toLong()) else longArrayOf(i.toLong())
            out.add(if (isStr) ctx.variables.readStr(name, full) else ctx.variables.readInt(name, full))
        }
        return out
    }

    private fun writeBack1D(ctx: ExecContext, name: String, idx: LongArray, arr: List<Any>, isStr: Boolean) {
        for (i in arr.indices) {
            val full = if (idx.size > 1) longArrayOf(idx[0], i.toLong()) else longArrayOf(i.toLong())
            if (isStr) ctx.variables.writeStr(name, full, arr[i] as String)
            else ctx.variables.writeInt(name, full, arr[i] as Long)
        }
    }

    /** ARRAYSHIFT。spec: Process.cs:1614-1660 + VariableEvaluator.cs:707-773 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execArrayShift(stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as SpArrayShiftArgument
        val name = arg.dest.name
        val idx = resolveIndices(arg.dest, ctx)
        val isStr = arg.dest.operandType == OperandType.STR
        // spec: Process.cs:1617-1620 —— 运行期先判 `VarToken.Identifier.IsArray1D`，不成立抛
        // `IsUsableOnly1DVar`（lang/emuera.zhs.xml:723 = `{0}仅对1位数组以及数组型角色变量有效`，
        // {0} 原位填 "ARRAYSHIFT"）。常规装载链上该形态已被 SP_SHIFT_ARRAY_ArgumentBuilder
        // （ArgumentParser.cs:2131）以装载告警挡下（语句不生成），本守卫覆盖「无符号表」的裸构造
        // 执行路径（单测/降级装载），与 C# 运行期判据同层。
        val info = ctx.variables.resolveVarInfo(name)
        if (info != null && info.dimension != 1) {
            throw CodeEE("ARRAYSHIFT仅对1位数组以及数组型角色变量有效")
        }
        val shift = arg.num1?.getIntValue(ctx.eval)?.toInt() ?: 0
        // 原版 Math.Abs(shift)（VariableEvaluator.cs:722/:732）对 Int32.MinValue 抛
        // OverflowException 停机；java.lang.Math.abs 不抛（回绕为 MIN_VALUE）→ 补同款崩溃面，
        // 守卫口径参照 ABS 函数（FMC.cs:4147-4150 ↔ PureMethods ABS MinInt64）
        // （2026-10-26 fixcore 审查 B5）。
        if (shift == Int.MIN_VALUE) throw CodeEE("ARRAYSHIFT函数: 第2个参数溢出（$shift）")
        if (shift == 0) return
        val start = arg.num3?.getIntValue(ctx.eval)?.toInt() ?: 0
        if (start < 0) throw CodeEE("ARRAYSHIFT函数: 第4个参数被是负值($start)")
        val end: Int = if (arg.num4 != null) {
            val e = arg.num4.getIntValue(ctx.eval).toInt()
            if (e < 0) throw CodeEE("ARRAYSHIFT函数: 第5个参数被是负值($e)")
            if (e == 0) return
            e
        } else {
            -1
        }
        val arr = array1D(ctx, name, idx, isStr)
        val len = arr.size
        // 文案照抄官方 zhs：`Lang.Error.OoRArrayShift`（lang/emuera-zhs.xml:309）
        // = `命令ARRAYSHIFT的第四个参数({0})超出了数组"{1}"的范围`；C# 抛点
        // VariableEvaluator.cs:710-713（整数）/ :779-782（字符串），占位符 = (start, 变量名)。
        if (start >= len) throw CodeEE("命令ARRAYSHIFT的第四个参数($start)超出了数组\"$name\"的范围")
        var num = if (end == -1) len - start else end
        if (start + num > len) num = len - start
        val fill = fillValue(arg, ctx, isStr)
        if (Math.abs(shift) >= len && start == 0 && num >= len) {
            for (i in 0 until len) arr[i] = fill
            writeBack1D(ctx, name, idx, arr, isStr)
            return
        }
        var srcOff = 0
        var dstOff = start + shift
        val keep = num - Math.abs(shift)
        if (shift < 0) {
            srcOff = -shift
            dstOff = start
        }
        val copy = arr.subList(start, start + num).toMutableList()
        if (shift > 0) {
            if (keep <= 0) {
                for (j in start until start + num) arr[j] = fill
                writeBack1D(ctx, name, idx, arr, isStr)
                return
            }
            for (k in start until start + shift) arr[k] = fill
        } else {
            if (keep <= 0) {
                for (l in start until start + num) arr[l] = fill
                writeBack1D(ctx, name, idx, arr, isStr)
                return
            }
            for (m in start + keep until start + num) arr[m] = fill
        }
        if (keep > 0) {
            for (i in 0 until keep) arr[dstOff + i] = copy[srcOff + i]
        }
        writeBack1D(ctx, name, idx, arr, isStr)
    }

@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun fillValue(arg: SpArrayShiftArgument, ctx: ExecContext, isStr: Boolean): Any =
        if (isStr) (arg.num2?.getStrValue(ctx.eval) ?: "") else (arg.num2?.getIntValue(ctx.eval) ?: 0L)

    /** ARRAYREMOVE。spec: Process.cs:1661-1677 + VariableEvaluator.cs:845-887 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execArrayRemove(stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as SpArrayControlArgument
        val name = arg.dest.name
        val idx = resolveIndices(arg.dest, ctx)
        val isStr = arg.dest.operandType == OperandType.STR
        // spec: Process.cs:1663-1668 —— ARRAYREMOVE 的装载期 SP_CONTROL_ARRAY_ArgumentBuilder
        // （ArgumentParser.cs:2076-2110）**不做** 1 维校验（与 ARRAYSHIFT/ARRAYSORT 不同），
        // 因此 C# 运行期的 `IsArray1D` 拒否是**语料可达**的：非 1 维（0 维标量/2 维/3 维）
        // 抛 `IsUsableOnly1DVar`（zhs:723）。旧实现直接按 1 维口径读写，二维变量会被静默错读。
        val info = ctx.variables.resolveVarInfo(name)
        if (info != null && info.dimension != 1) {
            throw CodeEE("ARRAYREMOVE仅对1位数组以及数组型角色变量有效")
        }
        val start = arg.num1?.getIntValue(ctx.eval)?.toInt() ?: 0
        if (start < 0) throw CodeEE("ARRAYREMOVE函数: 第2个参数被是负值($start)")
        val arr = array1D(ctx, name, idx, isStr)
        val len = arr.size
        // 文案照抄官方 zhs：`Lang.Error.OoRArrayRemove`（lang/emuera-zhs.xml:310）
        // = `命令ARRAYREMOVE的第二个参数({0})超出了数组"{1}"的范围`；C# 抛点
        // VariableEvaluator.cs:850-853 —— **只在整数分支**判 start 越界（字符串分支不判，
        // 直接按 num<=0→num=length 走 BlockCopy），故本侧同样 `!isStr` 才判。
        if (!isStr && start >= len) throw CodeEE("命令ARRAYREMOVE的第二个参数($start)超出了数组\"$name\"的范围")
        var num = arg.num2?.getIntValue(ctx.eval)?.toInt() ?: 0
        if (num <= 0) num = len
        val out = ArrayList<Any>(len)
        for (i in 0 until start) out.add(arr[i])
        for (i in start + num until len) out.add(arr[i])
        while (out.size < len) out.add(if (isStr) "" else 0L)
        writeBack1D(ctx, name, idx, out, isStr)
    }

    /** ARRAYSORT。spec: Process.cs:1678-1710 + VariableEvaluator.cs:889-958 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execArraySort(stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as SpArraySortArgument
        val name = arg.dest.name
        val idx = resolveIndices(arg.dest, ctx)
        val isStr = arg.dest.operandType == OperandType.STR
        // spec: Process.cs:1680-1685 —— 运行期 `IsArray1D` 拒否（装载期 ArgumentParser.cs:645
        // 已挡下，此处为无符号表裸构造路径的兜底），文案 IsUsableOnly1DVar（zhs:723）。
        val info = ctx.variables.resolveVarInfo(name)
        if (info != null && info.dimension != 1) {
            throw CodeEE("ARRAYSORT仅对1位数组以及数组型角色变量有效")
        }
        val start = arg.num1?.getIntValue(ctx.eval)?.toInt() ?: 0
        if (start < 0) throw CodeEE("ARRAYSORT函数: 第3个参数被是负值($start)")
        val arr = array1D(ctx, name, idx, isStr)
        val len = arr.size
        // 第 4 参缺省 = -1（SortArray 视作「排到数组末尾」）；显式 0 原版直接 break，
        // 显式负值抛 ArgIsNegative（Process.cs:1691-1707）。
        var count = arg.num2?.getIntValue(ctx.eval)?.toInt() ?: -1
        if (arg.num2 != null) {
            if (count < 0) {
                // ADR-005：原版这条消息把 start（num8）当负值印出来（Process.cs:1697），原样复刻。
                throw CodeEE("ARRAYSORT函数: 第4个参数被是负值($start)")
            }
            // 显式 0 在范围检查之前就跳过整条语句（Process.cs:1699-1702）。
            if (count == 0) return
        }
        // 文案照抄官方 zhs：`Lang.Error.OoRArraySort`（lang/emuera-zhs.xml:311）
        // = `命令ARRAYSORT的第三参数({0})超出了数组"{1}"的范围`；C# 抛点
        // VariableEvaluator.cs:898-901（整数）/ :923-926（字符串），占位符 = (start, 变量名)。
        if (start >= len) throw CodeEE("命令ARRAYSORT的第三参数($start)超出了数组\"$name\"的范围")
        if (count <= 0) count = len - start
        if (start + count > len) {
            // C# 抛点 VariableEvaluator.cs:908-910 / :933-935 用 `Lang.Error.OoRSumArray`，
            // 但 zhs 语言包**没有**该键（`grep OoRSumArray lang/emuera-zhs.xml` 0 命中）
            // ⇒ 官方 zhs 运行时回落 Lang.cs:3622 的日文默认串（TranslatableString 无译文即
            // 保留默认）。照抄纪律 ADR-005：此处也照抄该日文默认（占位符 = start, count, 变量名）。
            throw CodeEE("命令ARRAYSORTの第３引数($start)と第4変数($count)の和が配列\"$name\"の範囲を超えています")
        }
        val region = arr.subList(start, start + count)
        if (isStr) {
            region.sortWith(compareBy { it as String })
            if (arg.order == SortOrder.DESENDING) region.reverse()
        } else {
            region.sortWith(compareBy { it as Long })
            if (arg.order == SortOrder.DESENDING) region.reverse()
        }
        writeBack1D(ctx, name, idx, arr, isStr)
    }

    /**
     * ARRAYCOPY。spec: Process.cs:1711-1772 + VariableEvaluator.cs:947-990。
     * 数据流向：第 1 参数数组 → 第 2 参数数组（CopyArray(var1, var2)：array2[i] = array[i]）。
     * TODO(verify): isa.yaml 记为「第二个复制到第一个」，与源码相反；按源码实现。
     */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execArrayCopy(stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as SpCopyArrayArgument
        val n1 = evalStr(arg.varName1, ctx)
        val n2 = evalStr(arg.varName2, ctx)
        // spec: Process.cs:1724-1756（非常量名路径的判序与文案逐条对齐官方 zhs）：
        // 存在性 → `NotVariableName`（lang/emuera-zhs.xml:912 = `{0}函数: 第{1}个参数("{2}")不是变量名`，
        // C# 实参 = "ARRAYCOPY", 序号, 原文）；
        // 非数组 → `ArraycopyArgIsNotArray`（:511 = `ARRAYCOPY命令的第{0}参数"{1}"不是一个数组变量`，
        // C# 用 IsArray1D/2D/3D 三判，等价于本侧 dimension < 1）；
        // 角色变量 → `ArraycopyArgIsCharaVar`（:512）；维度 → :514；类型 → :515。
        // **判序**必须是 数组 → 角色（Process.cs:1728-1737）——旧实现先判角色，角色型 0 维变量
        // 会报错到错误的那条键上。
        val i1 = ctx.variables.resolveVarInfo(n1)
            ?: throw CodeEE("ARRAYCOPY函数: 第1个参数(\"$n1\")不是变量名")
        if (i1.dimension < 1) throw CodeEE("ARRAYCOPY命令的第1参数\"$n1\"不是一个数组变量")
        if (i1.isCharacterData) throw CodeEE("ARRAYCOPY命令的第1参数\"$n1\"是一个角色变量(不支持)")
        val i2 = ctx.variables.resolveVarInfo(n2)
            ?: throw CodeEE("ARRAYCOPY函数: 第2个参数(\"$n2\")不是变量名")
        if (i2.dimension < 1) throw CodeEE("ARRAYCOPY命令的第2参数\"$n2\"不是一个数组变量")
        if (i2.isCharacterData) throw CodeEE("ARRAYCOPY命令的第2参数\"$n2\"是一个角色变量(不支持)")
        if (i1.dimension != i2.dimension) throw CodeEE("ARRAYCOPY命令的两个参数维度不一致")
        if (i1.operandType != i2.operandType) throw CodeEE("ARRAYCOPY命令的两个参数的数组变量类型不一致")
        // 登记残差：Process.cs:1749-1752 的非常量名路径还有一条 `ArraycopyArgIsConst`（第 2 参数
        // 为只读变量，zhs:513），以及常量名路径（Process.cs:1755-1762）**只查类型不一致**、
        // 不查数组/角色/只读。语料实测 `ARRAYCOPY` 共 50 处，其中 48 处两个参数都是字符串常量名
        // （装载期 `copyArray` 的 `checkArrayName`，ArgBuilders.kt:1257-1269，已在符号层覆盖常量名
        // 的数组/角色判定），2 处第 1 参为运行期名（`ARG_VARNAME` 与 FORM 串 `@"特效组{ARG}"`）。
        // 移植侧统一走上面这条更严的判序，两条残差不影响语料可达读数
        // （TODO(verify)：无符号表的裸构造路径可达时需补 const 分支）。
        val isStr = i1.operandType == OperandType.STR
        if (i1.dimension <= 1) {
            val n = minOf(ctx.variables.getLength(n1), ctx.variables.getLength(n2))
            for (i in 0 until n) {
                val at = longArrayOf(i.toLong())
                if (isStr) ctx.variables.writeStr(n2, at, ctx.variables.readStr(n1, at))
                else ctx.variables.writeInt(n2, at, ctx.variables.readInt(n1, at))
            }
            return
        }
        // 多维：逐维取 min 尺寸复制（spec: CopyArray 2D/3D 分支）
        val dims = (0 until i1.dimension).map { d ->
            minOf(ctx.variables.getLength(n1, d), ctx.variables.getLength(n2, d))
        }
        val idx = IntArray(i1.dimension)
        fun rec(d: Int) {
            if (d == i1.dimension) {
                val at = LongArray(i1.dimension) { idx[it].toLong() }
                if (isStr) ctx.variables.writeStr(n2, at, ctx.variables.readStr(n1, at))
                else ctx.variables.writeInt(n2, at, ctx.variables.readInt(n1, at))
                return
            }
            for (i in 0 until dims[d]) {
                idx[d] = i
                rec(d + 1)
            }
        }
        rec(0)
    }

    // ==================================================================
    // 其他
    // ==================================================================

    /** 赋值语句（SET）。spec: FunctionIdentifier.cs:606-666 + LogicalLineParser.cs:536-556 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execAssign(stmt: AssignStmt, ctx: ExecContext) {
        when (val arg = stmt.args) {
            is SpSetArrayArgument -> {
                // 逗号分隔的批量赋值（语料里极少：`SET X, 1, 2, 3`）。
                // 这条分支仍自带下标数组 —— `setValues` 的形态与单点写不同，接池要另验，
                // 而它在热路径上的占比可以忽略（见下方单点写的 PERF 注记）。
                val name = stmt.dest.name
                val idx = resolveIndices(stmt.dest, ctx)
                if (stmt.dest.operandType == OperandType.INT) {
                    val vals = if (arg.constIntList != null) arg.constIntList
                    else arg.terms.map { it?.getIntValue(ctx.eval) ?: 0L }.toLongArray()
                    ctx.variables.setValues(name, idx, vals)
                } else {
                    val vals = if (arg.constStrList != null) arg.constStrList
                    else arg.terms.map { it?.getStrValue(ctx.eval) ?: "" }.toTypedArray()
                    ctx.variables.setValues(name, idx, vals)
                }
            }
            is SpSetArgument -> {
                // spec: FunctionIdentifier.cs:640-657（SET_Instruction.DoInstruction）——
                // 取值 = `IsConst ? ConstInt : Term.GetIntValue(exm)`；`AddConst` 走
                // `ChangeValue(value)`（带符号增量），否则 `SetValue(value)`。
                // 常量 `VAR += N` / `-= N` / `++` / `--` 在解析期被折成
                // `Term=null, IsConst, AddConst, ConstInt=±N`（ArgumentParser.cs:1073-1090），
                // 非常量复合赋值则由 `ReduceBinaryTerm` 把**整式**（`dest op rhs`）放进 Term
                // （ArgumentParser.cs:1092-1096；移植侧同形，ArgBuilders.kt:1384）。
                // 因此执行层**不得**按 [AssignStmt.op] 再套一次运算：那会把
                // `X += 2` 执行成 no-op、`X *= 2` 执行成 `X*(X*2)`。
                //
                // PERF（2026-10-27）：落点改走 `VarRef.setIntValue/addIntValue/setStrValue`，
                // 复用读路径的访问点句柄与下标借还池 —— 这是语料里 99.9% 的语句执行，
                // 旧实现每次现分配一个 `LongArray` 再按名查一次表。
                if (stmt.dest.operandType == OperandType.INT) {
                    val value = if (arg.isConst) arg.constInt else arg.term?.getIntValue(ctx.eval) ?: 0L
                    if (arg.addConst) stmt.dest.addIntValue(ctx.eval, value)
                    else stmt.dest.setIntValue(ctx.eval, value)
                } else {
                    val value = if (arg.isConst) arg.constStr else arg.term?.getStrValue(ctx.eval) ?: ""
                    stmt.dest.setStrValue(ctx.eval, value)
                }
            }
            else -> throw CodeEE("SET 语句的参数形态不支持：${arg.shape}")
        }
    }

    /** VARI/VARS 运行期：标量时以参数赋初值。spec: FunctionIdentifier.cs:33-71 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execVarDecl(stmt: VarDeclStmt, ctx: ExecContext) {
        // 本游戏 setting.json `UseScopedVariableInstruction:false`：原版在**标识符解析期**即抛
        // CodeEE（CanNotUseVAR，spec: IdentifierDictionary.cs:624-627），语料零使用 ⇒ 语义按禁用处理。
        // 解析期门控已落地（`StmtParseConfig.useScopedVariableInstruction`，LineParser 构造参数，
        // M3-A A3）；本处是执行层兜底，覆盖"语句由其它来源构造/未走解析器门控"的路径。
        if (!ctx.config.useScopedVariableInstruction) {
            throw CodeEE("VARI/VARS 在当前设置中不可用（UseScopedVariableInstruction 已禁用）")
        }
        // 作用域进出由 VM 的 enterFunction/leaveFunction 驱动（VariableStore 注释）；
        // 语句自身只补标量初值。TODO(verify): 原版 VARI 每次执行 ScopeIn，与函数级 ScopeIn
        // 的叠加语义需对齐（重复压栈会泄漏，见 UserVar.scopeIn/Out 注释）。
        when (stmt.code) {
            FunctionCode.VARI -> {
                val a = stmt.args as IntAsignArgument
                val v = ctx.variables.userVar(a.name) ?: return
                if (v.spec.totalSize == 1 && a.exp != null) v.writeInt(0, 0, a.exp.getIntValue(ctx.eval))
            }
            FunctionCode.VARS -> {
                val a = stmt.args as StrAsignArgument
                val v = ctx.variables.userVar(a.name) ?: return
                if (v.spec.totalSize == 1 && a.value != null) v.writeStr(0, 0, a.value)
            }
            else -> {}
        }
    }

    /** PRINTDATA 家族。spec: FunctionIdentifier.cs:270-320 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execPrintData(stmt: Statement, ctx: ExecContext, code: FunctionCode) {
        if (ctx.skipPrint) return
        // 打印窗口起点（spec: FunctionIdentifier.cs:276-277，置位先于数据组查找——与原版同序）。
        // PRINTDATAD/PRINTDATADL/PRINTDATADW 的 D 后缀窗口内取色回落默认前景色。
        // D 位判定按 PRINT_DATA_Instruction 构造的后缀序重放（spec: FunctionIdentifier.cs:244-248：
        // 先 K 再 D，命中置 flag |= 2050；IsPrintDFunction() = (flag & 0x800) == 2048，:4240-4243）。
        // 不能复用 PrintSpec.parse：它只覆盖 PRINT_Instruction 一族（PRINTDATA 名里的
        // "DATA" 会在错误位置命中 D，且残余字符使解析返回 null）。
        val dataRest = code.name.removePrefix("PRINTDATA")
        val isD = (if (dataRest.startsWith("K")) dataRest.drop(1) else dataRest).startsWith("D")
        // 与 PRINT 家族同序：UseUserStyle=true（:276）先于 UseSetColorStyle（:277）。
        // 空数据节的 `return`（:278-282）只跳过 :317 的 UseSetColorStyle 复位，
        // **不**回滚 :276 写下的 UseUserStyle —— 后者已经生效，粘滞口径见
        // ConsoleSink.setUseUserStyle 的 KDoc。
        ctx.console.setUseUserStyle(true)
        ctx.console.setUseSetColorStyle(!isD)
        val groups = ctx.dataSections?.groupsOf(stmt) ?: return
        if (groups.isEmpty()) return
        // 原版 dataSection.Count == 0 时直接 JumpTo 返回、**不复位**取色开关
        // （FunctionIdentifier.cs:278-282 的 return 跳过了 :317），false 泄漏到下一条打印语句
        // 的窗口起点——按 ADR-005 原样复刻，上面的 ?: return 不补复位正是这个泄漏。
        val idx = nextRandom(ctx, groups.size.toLong()).toInt()
        (stmt.args as? PrintDataArgument)?.dest?.let { writeVarRef(it, idx.toLong(), ctx) }
        val list = groups[idx]
        for ((i, e) in list.withIndex()) {
            ctx.console.print(e.getStrValue(ctx.eval))
            if (i < list.size - 1) ctx.console.newLine()
        }
        // 语句名后缀 L/W：换行 / 换行并等待按键（spec: FunctionIdentifier.cs:309-316；
        // PRINTDATAW/PRINTDATAKW/PRINTDATADW 等 W 结尾形态同路径）。
        // MesSkip 为真时等待被控制台吞掉（spec: EmueraConsole.cs:1451-1455），sem 等价：不发起等待，换行不受影响。
        val name = code.name
        if (name.endsWith("W") || name.endsWith("L")) {
            ctx.console.newLine()
            if (name.endsWith("W") && !ctx.mesSkip) readAnyKey(ctx)
        }
        // 窗口终点。spec: FunctionIdentifier.cs:317
        ctx.console.setUseSetColorStyle(true)
    }

    /** STRDATA。spec: Process.cs:1801-1829 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execStrData(stmt: Statement, ctx: ExecContext) {
        val groups = ctx.dataSections?.groupsOf(stmt) ?: return
        if (groups.isEmpty()) return
        val idx = nextRandom(ctx, groups.size.toLong()).toInt()
        val joined = groups[idx].joinToString("\n") { it.getStrValue(ctx.eval) }
        val dest = (stmt.args as StrDataArgument).dest
        if (dest != null) writeVarRefStr(dest, joined, ctx)
    }

    /** HTML_TAGSPLIT。spec: FunctionIdentifier.cs:347-369 + HtmlManager.cs:486-515 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execHtmlTagSplit(stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as SpHtmlSplitArgument
        val parts = htmlTagSplit(evalStr(arg.targetStr, ctx))
        if (parts == null) {
            arg.num?.let { writeVarRef(it, -1L, ctx) }
            return
        }
        arg.num?.let { writeVarRef(it, parts.size.toLong(), ctx) }
        val info = ctx.variables.resolveVarInfo(arg.destVarName)
            ?: throw CodeEE("未定义的变量 ${arg.destVarName}")
        val dim = maxOf(info.dimension, 1)
        val idx = LongArray(dim)
        val cap = ctx.variables.getLength(arg.destVarName)
        val n = minOf(parts.size, cap)
        for (i in 0 until n) {
            idx[dim - 1] = i.toLong()
            ctx.variables.writeStr(arg.destVarName, idx.copyOf(), parts[i])
        }
    }

    // ==================================================================
    // 工具函数
    // ==================================================================

    private fun ops(ctx: ExecContext): SystemOps =
        ctx.systemOps ?: throw CodeEE("宿主未提供 SystemOps，系统级语句不可用")

    /**
     * `SAVEGAME` / `LOADGAME`（`FunctionIdentifier.cs:2967-2980` 的 `DoInstruction` 逐句）。
     *
     * 原版只有三句，逐条对位：
     * 1. `if ((state.SystemState & __CAN_SAVE__) != __CAN_SAVE__) throw CodeEE(不能使用…命令)`
     *    （`:2969-2977`）⇒ [checkCanSaveGate]。
     * 2. `GlobalStatic.Process.saveCurrentState(single: true)`（`:2978` → `Process.cs:1959-1966`）：
     *    `prevStateList.Add(state)` + `state = state.Clone()`。`Clone()` 造的新实例
     *    **functionList 为空**（`ProcessState.cs:475-484`；字段在 `:20` 初始化），
     *    故 `ScriptEnd`（`:34`）立刻为真 —— **本次脚本运行到此结束**。移植侧的等价出口是
     *    整栈丢弃 `StmtOutcome.Unwind`（由 `ProcessRuntime.executeStatement` 依据
     *    [SaveLoadHandoff] 的 `functionListCleared` 信号产生）。
     *    注意：`single` 形参在方法体内**从未被读**（`Process.cs:1959-1966` 只有两句）——
     *    官方死参数，照抄，不据此推断「single=false 时行为不同」。
     * 3. `GlobalStatic.Process.getCurrentState.SaveLoadData(isSave)`（`:2979` →
     *    `ProcessState.cs:190-200`）：`sysStateCode = SaveGame_Begin(256)` /
     *    `LoadGame_Begin(272)`，下一次 `runSystemProc()` 进 `beginSaveGame` / `beginLoadGame`
     *    （`Process.cs:2685-2697`，注册表 `:2023` / `:2027`）。
     *
     * 移植侧第 2/3 句合起来就是 [SaveLoadHandoff] 的一次写（顺序照原版：先脚本栈信号，
     * 后状态位）。**未落地**：`prevStateList` 快照栈本身 —— 原版靠它在存档/读档界面选
     * 「戻る」时 `loadPrevState()`（`Process.cs:1968-1976`，调用点 `:2821` / `:2888` /
     * `:2901`）把脚本状态原样接回；移植侧 VM 帧栈不是可克隆对象，故当前 SAVEGAME/LOADGAME
     * 之后脚本**不可恢复**。卡点与最小 patch 见交付报告的「需组长接线」。
     *
     * 宿主前置闸（[SystemOps.saveGame] / [SystemOps.loadGame]）是移植侧扩展，原版无此调用；
     * 放在状态迁移之前：宿主拒绝时不留半进入状态。
     *
     * @return 恒为 [ExecResult.Normal]（原版 DoInstruction 无返回值、不写 RESULT）；
     *   整栈丢弃由 proc 侧按 [SaveLoadHandoff] 信号改写成 `StmtOutcome.Unwind`。
     */
    private fun execSaveLoadGame(ctx: ExecContext, save: Boolean): ExecResult {
        checkCanSaveGate(ctx) // FunctionIdentifier.cs:2969-2977
        if (save) ctx.systemOps?.saveGame() else ctx.systemOps?.loadGame()
        if (save) {
            SaveLoadHandoff.requestSaveGame(ctx.variables) // Process.cs:1963-1964 + ProcessState.cs:194
        } else {
            SaveLoadHandoff.requestLoadGame(ctx.variables) // Process.cs:1963-1964 + ProcessState.cs:198
        }
        return ExecResult.Normal
    }

    /**
     * `SAVEGAME` / `LOADGAME` 的 `__CAN_SAVE__` 门（`FunctionIdentifier.cs:2969-2977`）：
     * 系统状态不带该位时抛 `CodeEE("不能使用{当前函数名}命令")`。
     *
     * - 文案模板 `Lang.Error.CanNotUseInstruction`（lang/emuera.zhs.xml:785）只有一个 `{0}`，
     *   填的是 `state.Scope`（`ProcessState.cs:88-97`：函数栈顶的函数名，空栈为 null → 空串）；
     *   第二实参 `"SAVEGAME/LOADGAME"` 被 string.Format 丢弃 —— 官方怪癖，ADR-005 原样复刻。
     * - `{0}` 取 [VariableStore.currentFunction]（原版 FunctionName 的移植口径；本侧函数键
     *   统一大写，与原版保留书写大小写在大小写不敏感的报文观感下等价）。
     * - 真值来源见 [ExecContext.canSave] 的 KDoc（proc 运行时逐语句写入）。
     */
    private fun checkCanSaveGate(ctx: ExecContext) {
        if (!ctx.canSave) {
            val scope = ctx.variables.currentFunction ?: ""
            throw CodeEE("不能使用${scope}命令")
        }
    }

    /** PRINT_ITEM 的所持物品串。spec: VariableEvaluator.cs:1037-1063。 */
    private fun havingItemsString(ctx: ExecContext): String {
        val store = ctx.variables
        val num = minOf(store.getLength("ITEM", -1), store.registry.csvTable("ITEM").size)
        val sb = StringBuilder(100)
        sb.append("所持アイテム：")
        var count = 0
        for (i in 0 until num) {
            val v = store.readInt("ITEM", longArrayOf(i.toLong()))
            if (v == 0L) continue
            count++
            val name = store.registry.csvTable("ITEM")[i]
            if (name.isNotEmpty()) sb.append(name)
            sb.append('(').append(v).append(") ")
        }
        if (count == 0) sb.append("なし")
        return sb.toString()
    }

    private fun nextRandom(ctx: ExecContext, bound: Long): Long =
        ctx.variables.randProvider?.next(bound)
            ?: throw CodeEE("随机数需要注入 RandProvider（ADR-001；禁止非确定性兜底）")

    /** 求值 VarRef 的下标并返回（name, indices）。 */
    /**
     * 求 [ref] 的**下标数组**（PERF 2026-10-26）。
     *
     * 旧实现返回 `Pair<String, LongArray>`：赋值是语料里 97% 的语句（`execAssign` 占
     * `CHARA_MOVEMENT` 的语句采样 98%），每个赋值都要额外新建一个 Pair 和两个装箱 Integer。
     * 名字本来就是 `ref.name`，调用方自取即可 —— 一次分配直接消失。
     */
    private fun resolveIndices(ref: VarRef, ctx: ExecContext): LongArray {
        val idx = LongArray(ref.indices.size)
        for (i in idx.indices) idx[i] = ref.indices[i].getIntValue(ctx.eval)
        return idx
    }

    /**
     * 写一个 [VarRef]（PERF 2026-10-27）。
     *
     * 旧实现自己 `resolveIndices` 现分配 `LongArray` 再 `writeInt(name, …)` 按名查表；
     * 现在委托 [VarRef.setIntValue] —— 那里复用**读路径那一套**访问点句柄缓存与下标借还池，
     * 于是这条路径稳态**零分配、零哈希**（理由与红线见该函数的 KDoc）。
     */
    private fun writeVarRef(ref: VarRef, value: Long, ctx: ExecContext) {
        ref.setIntValue(ctx.eval, value)
    }

    private fun writeVarRefStr(ref: VarRef, value: String, ctx: ExecContext) {
        ref.setStrValue(ctx.eval, value)
    }

    /**
     * 语句层拿宿主：有 [BuiltinContext]（产线 ProcessRuntime 恒成立）就用它的 host，
     * 裸语句假体回落 [BuiltinHost.DEFAULT]（与 GETTIME 分支同一条口径，:668）。
     */
    private fun semHost(ctx: ExecContext): BuiltinHost =
        (ctx.eval as? BuiltinContext)?.host ?: BuiltinHost.DEFAULT

    /**
     * CALLSHARP —— 调宿主注册的本地方法，并把**变量实参**回写。
     * spec: `FunctionIdentifier.cs:1212-1233`。四步逐句对齐原版：
     *
     * 1. 名字：装载期由 `SetJumpTo` 把常量名绑成 `CallFunc`，非常量名置 `useCallForm`
     *    （`:1191-1208`）；查不到方法时是 `ParserMediator.Warn(..., isError: true)` =
     *    **装载期**错误（`:1204`），文案原文 `No native method {name} found`。
     *    移植侧没有装载期的插件表（DLL 加载属宿主/M5），故把校验挪到执行期、
     *    未命中直接 CodeEE —— 差异登记：报错时机由装载期变运行期，文案同源。
     *    非常量名一侧原版保持 `CallFunc == null`，运行期即 NRE（`:1217` 无空检查）；
     *    移植侧改为「现求现查」，属**不复刻崩溃**的有意偏离，见交付报告 TODO(verify)。
     * 2. 实参 → 槽：`IsString` 走 `strValue`，否则 `intValue`
     *    （`:1216` + `PluginMethodParameterBuilder.cs:8-15`）。
     * 3. `Execute(array)`：返回值不存在，出参全靠改槽（`:1217`，`IPluginMethod.cs:13`）。
     * 4. **回写**：`RowArgs` 逐个看是不是变量项，是才写回；判定用接收侧
     *    `variableTerm.IsString`（**不是**槽自己的 isString，`:1219-1231`），
     *    非变量实参（字面量/表达式）求值后丢弃，不报错。
     *    这条就是 `isa.yaml:1340-1347` 记的「变量实参回写」，也是
     *    `:1218-1233` 注释段里 CALL 家族唯一有出参通道的语句
     *    （对比 CALLF 求值后丢弃、CALL 不写 RESULT）。
     *
     * 位置参数保持下标对齐：原版 `RowArgs[i]` 与 `array[i]` 同序（`:1216`/`:1220`），
     * 空位（省略的实参）在本工程 AST 里是 null 槽 —— 原版此时 `ConvertTerm(null)` 直接
     * NRE，移植侧给该槽补 `intValue = 0` 并**跳过回写**（无变量身份可写）。
     * 语料零处 CALLSHARP ⇒ 无可比读数，只登记口径。
     */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun execCallSharp(stmt: Statement, ctx: ExecContext) {
        val arg = stmt.args as? SpCallSharpArgument
            ?: throw CodeEE("CALLSHARP 参数形态错误")
        val name = if (arg.isConst) arg.constStr else arg.funcNameTerm?.getStrValue(ctx.eval) ?: ""
        val method = nativeMethods(ctx.variables.aux).find(name)
            ?: throw CodeEE("No native method $name found")
        val rowArgs = arg.rowArgs
        val params = Array(rowArgs.size) { i -> toNativeParam(rowArgs[i], ctx) }
        method.execute(params)
        for (i in rowArgs.indices) {
            val ref = rowArgs[i] as? VarRef ?: continue
            if (ref.operandType == OperandType.STR) writeVarRefStr(ref, params[i].strValue, ctx)
            else writeVarRef(ref, params[i].intValue, ctx)
        }
    }

    /** 实参 → 参数槽。spec: `PluginMethodParameterBuilder.cs:8-15` + `PluginMethodParameter.cs:5-24`。 */
    private fun toNativeParam(term: Expr?, ctx: ExecContext): NativeParam = when {
        term == null -> NativeParam(isString = false)
        term.operandType == OperandType.STR -> NativeParam(isString = true, strValue = term.getStrValue(ctx.eval))
        else -> NativeParam(isString = false, intValue = term.getIntValue(ctx.eval))
    }

    private fun evalStr(e: Expr?, ctx: ExecContext): String = e?.getStrValue(ctx.eval) ?: ""

@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun evalStrArg(arg: Argument, ctx: ExecContext): String = when (arg) {
        is ExpressionArgument -> evalStr(arg.term, ctx)
        else -> arg.constStr
    }

@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun evalIntArg(arg: Argument, ctx: ExecContext): Long = when (arg) {
        is ExpressionArgument -> arg.term?.getIntValue(ctx.eval) ?: 0L
        else -> arg.constInt
    }

    /** FORM_STR_ANY / INT_ANY 之类多参数形态的项列表。 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun argTerms(arg: Argument): List<Expr> = when (arg) {
        is ExpressionArrayArgument -> arg.terms.filterNotNull()
        is ExpressionsArgument -> arg.terms.filterNotNull()
        is ExpressionArgument -> listOfNotNull(arg.term)
        else -> emptyList()
    }

    /** SETCOLOR/SETBGCOLOR 参数。spec: Process.cs:1409-1439 */
@Deprecated("T2-64 波次 C 收尾删除：旧对象树路径，仅供双路对拍装置对照")
    private fun evalColor(arg: Argument, ctx: ExecContext): Triple<Int, Int, Int> {
        val a = arg as SpColorArgument
        return if (a.rgb != null) {
            val rgb = a.rgb.getIntValue(ctx.eval)
            Triple(((rgb and 0xFF0000) shr 16).toInt(), ((rgb and 0xFF00) shr 8).toInt(), (rgb and 0xFF).toInt())
        } else {
            val r = a.r?.getIntValue(ctx.eval) ?: 0L
            val g = a.g?.getIntValue(ctx.eval) ?: 0L
            val b = a.b?.getIntValue(ctx.eval) ?: 0L
            if (r < 0 || g < 0 || b < 0) throw CodeEE("SETCOLOR的参数指定了小于0的值")
            if (r > 255 || g > 255 || b > 255) throw CodeEE("SETCOLOR的参数指定了超过0的值")
            Triple(r.toInt(), g.toInt(), b.toInt())
        }
    }

    /** spec: ExpressionMediator.cs:130-159 CreateBar（文案 spec: Lang zhs；填充字符归 M4 样式层）。 */
    private fun createBar(v: Long, max: Long, length: Long): String {
        if (max <= 0) throw CodeEE("BAR的最大值不为正值")
        if (length <= 0) throw CodeEE("BAR的长度不为正值")
        if (length >= 100) throw CodeEE("BAR过长")
        var filled = (v * length / max).toInt()
        if (filled < 0) filled = 0
        if (filled > length) filled = length.toInt()
        val sb = StringBuilder()
        sb.append('[')
        repeat(filled) { sb.append('*') }
        repeat((length - filled).toInt()) { sb.append('.') }
        sb.append(']')
        return sb.toString()
    }

    /**
     * `SETCOLORBYNAME` / `SETBGCOLORBYNAME` 的取色（spec: Process.cs:1440-1454、:1493-1507）。
     *
     * 名单唯一权威是 ColorNames（原版把名字交给 System.Drawing.Color.FromName，按 A > 0 判定认不认得；
     * COLOR-SYNC 收口前本文件自带一份 46 名私有副本，含 grey 系框架实测 A == 0 的自造别名，
     * 已按 ADR-005 去掉）。
     */
    private fun colorByName(name: String): IntArray {
        if (ColorNames.isTransparent(name)) {
            throw CodeEE("不能将透明(Transparent)指定为颜色")
        }
        return ColorNames.components(name) ?: throw CodeEE("指定的颜色名\"$name\"无效")
    }

    /** spec: HtmlManager.cs:486-515 —— 拆成 文本/`<tag>` 交替段；`<` 未闭合返回 null。 */
    private fun htmlTagSplit(str: String): List<String>? {
        val out = ArrayList<String>()
        var pos = 0
        while (pos < str.length) {
            val lt = str.indexOf('<', pos)
            if (lt < 0) {
                out.add(str.substring(pos))
                break
            }
            if (lt > pos) out.add(str.substring(pos, lt))
            val gt = str.indexOf('>', lt)
            if (gt < 0) return null
            out.add(str.substring(lt, gt + 1))
            pos = gt + 1
        }
        return out
    }

    private companion object {
        // RESULT 与原版 RESULT_ARRAY 是同一数组（spec: VariableEvaluator.cs:38-50）
        const val RESULT = "RESULT"

        /** 随机数状态全局数组（625 元素）。spec: VariableEvaluator.cs:117 RANDDATA、variables.yaml:60 */
        const val RANDDATA = "RANDDATA"
        const val RESULTS = "RESULTS"
        const val SAVEDATA_TEXT = "SAVEDATA_TEXT"
        const val STAIN = "STAIN"

        /** `Lang.Error.NotExistFromCopyChara.Text`（spec: lang.emuera-zhs.xml:316）。 */
        const val COPY_CHARA_SOURCE_NOT_EXIST = "复制的原角色不存在"
    }
}
