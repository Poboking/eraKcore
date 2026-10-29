package com.eratw.port.core.compile

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.CallArg
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.StatementAliases
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.strCaseRange
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.fn.Builtins
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.BlockRole
import com.eratw.port.core.stmt.BuiltinSymbols
import com.eratw.port.core.stmt.CallFStmt
import com.eratw.port.core.stmt.CallStmt
import com.eratw.port.core.stmt.CaseElseStmt
import com.eratw.port.core.stmt.CaseStmt
import com.eratw.port.core.stmt.CaseExpression
import com.eratw.port.core.stmt.CatchStmt
import com.eratw.port.core.stmt.DoStmt
import com.eratw.port.core.stmt.ElseIfStmt
import com.eratw.port.core.stmt.ElseStmt
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
import com.eratw.port.core.stmt.IfStmt
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.JumpStmt
import com.eratw.port.core.stmt.LoopStmt
import com.eratw.port.core.stmt.MethodStmt
import com.eratw.port.core.stmt.NextStmt
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.ReturnFStmt
import com.eratw.port.core.stmt.ReturnStmt
import com.eratw.port.core.stmt.SelectCaseStmt
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SpCallArgument
import com.eratw.port.core.stmt.BitArgument
import com.eratw.port.core.stmt.CallSharpStmt
import com.eratw.port.core.stmt.DataListStmt
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.EndBlockStmt
import com.eratw.port.core.stmt.HtmlPrintArgument
import com.eratw.port.core.stmt.MixedIntegerExprTerm
import com.eratw.port.core.stmt.MethodArgument
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.RepeatStmt
import com.eratw.port.core.stmt.SpArrayControlArgument
import com.eratw.port.core.stmt.SpArrayShiftArgument
import com.eratw.port.core.stmt.SpArraySortArgument
import com.eratw.port.core.stmt.SpBarArgument
import com.eratw.port.core.stmt.SpButtonArgument
import com.eratw.port.core.stmt.SpCallSharpArgument
import com.eratw.port.core.stmt.SpCopyArrayArgument
import com.eratw.port.core.stmt.SpCVarSetArgument
import com.eratw.port.core.stmt.SpDtColumnOptions
import com.eratw.port.core.stmt.SpHtmlPrint
import com.eratw.port.core.stmt.SpHtmlSplitArgument
import com.eratw.port.core.stmt.SpInputsArgument
import com.eratw.port.core.stmt.SpPowerArgument
import com.eratw.port.core.stmt.SpPrintImgArgument
import com.eratw.port.core.stmt.SpPrintShapeArgument
import com.eratw.port.core.stmt.SpPrintVArgument
import com.eratw.port.core.stmt.SpSaveDataArgument
import com.eratw.port.core.stmt.SpSaveVarArgument
import com.eratw.port.core.stmt.SpSplitArgument
import com.eratw.port.core.stmt.SpSwapCharaArgument
import com.eratw.port.core.stmt.SpTInputsArgument
import com.eratw.port.core.stmt.SpVarSetArgument
import com.eratw.port.core.stmt.SpCallFArgument
import com.eratw.port.core.stmt.SpForNextArgument
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SpSetArrayArgument
import com.eratw.port.core.stmt.StrDoubleArgument
import com.eratw.port.core.stmt.VarDeclStmt
import com.eratw.port.core.stmt.WendStmt
import com.eratw.port.core.stmt.WhileStmt

/**
 * [ErbProgramCompiler] 内部 `stageArgs` 的产物：调用位实参数 + 该调用位的逐位实参元数据
 * （具名形参 / REF 绑定；调用点 pc 在指令发射后登记进 [Program.callArgs]）。
 */
private class StagedArgs(val argc: Int, val args: List<CallArg>)

/**
 * 一个「裸变量实参 → REF 形参位」的待回填点（[ErbProgramCompiler.stageArgs] 登记，
 * [ErbProgramCompiler.build] 回填）。
 *
 * 为什么不能在被调函数编译时就定：调用方可能先于被调方编译（语料
 * `BODY_INFO/SHOW_PANTIES.ERB:90` 的 `CALL SETCOLORMATRIX(...)` 就早于 `:100` 的
 * `@SETCOLORMATRIX`），而 REF 位信息在 [LocalLayout.refParamNamesOf]（`DimSpecLayout`
 * 的 `LocalDim.ref`）里与编译顺序无关 —— 位序则要等被调签名（`FuncSig.paramNames`）
 * 进 [ErbProgramCompiler.signatures]。故先记「值指令 pc」，`build()` 时再决定是否回填。
 *
 * @property target 被调函数名（原样，查表大写归一）
 * @property position 实参位（0 起，= 被调头部形参位）
 * @property evalPc 该实参的值求值指令 pc（`_EVAL_INT`/`_EVAL_STR`；`lowerExpr` 可能在它
 *   之前发射表达式位方法调用的前导 `_CALL`，见 `stageArgs` 的表达式降级说明）
 * @property isStr 字符串通道（回填成空串常量而不是 0）
 */
private class VarArgSite(val target: String, val position: Int, val evalPc: Int, val isStr: Boolean)

/**
 * 语句 → 字节码编译器（ERB @函数 为编译单元）。
 *
 * ## 单一变量真相（契约 C1）
 *
 * **具名变量一律不在 VM 槽里**：它们只存在于 `core.vars.VariableStore`（列式池，带下标），
 * 由语句层（`sem.StatementExecutor`）读写。本编译器发射的指令里，凡操作数是槽，
 * 就一定是**匿名帧内临时值**：`evalTemp` 的中间结果、实参暂存区 `R0..R(maxArgc-1)`、
 * `_RET` 的取值槽。具名变量的读写只有两条路——
 * * 读：`_EVAL_INT/_EVAL_STR` 把 `VarRef` 投进表达式池，运行期经 `EvalContext.variables` 求值
 *   （FOR 的循环条件就是这么读的）；
 * * 写：向语句池投一条语句（marker），运行期交 `execAssign`
 *   （spec: StatementExecutor.kt:1196-1230 `writeInt/plusInt/writeStr/setValues`）。
 *
 * [LocalLayout] / [GlobalNameLayout] 仍保留（名字表导出、测试用），但主路径不再查询它。
 *
 * ## 降级分工（契约 C3）
 *
 * - **控制流留字节码**：IF/ELSEIF/ELSE/ENDIF、SELECTCASE/CASE/CASEELSE/ENDSELECT、
 *   WHILE/WEND、DO/LOOP、FOR/NEXT 的**循环结构**（比较/回边/出口）、BREAK/CONTINUE、
 *   GOTO 静态标签、CALL 静态名（CALL/JUMP 之别 = `_CALL` 的 bit30，契约 R10/API 3）、
 *   RETURNF、RESTART（同帧回跳函数入口）、SIF 补跳、事件入口合成、
 *   TRY{CALL,JUMP,GOTO}LIST 的**目标选择**（FUNC/ENDFUNC 本身不产生指令）。
 * - **数据面进语句池**：赋值（`AssignStmt`）、`SET`/`SETS`、PRINT 家族（**含字面量 PRINT**，
 *   保证只有一条文字流出口）、INPUT 系、数组操作、颜色/字体、系统语句、
 *   `CALLEVENT` 之外的 InstructionStmt、**RETURN/RETURNFORM**（marker：RESULT 写入与
 *   返回调用者的信号归语句层，契约 R10/API 1/4），以及 FOR 循环变量的**初始化与步进**。
 * - **marker 编码**（[marker]）：`op = 语句操作码（29..197）`、`a = 语句池下标`
 *   （[CodeBuilder.statement]，按身份追加、严禁去重——契约 C2）、`b = 0`、`c = SlotAddr.NONE`。
 *   运行期由 VM 主循环（契约 C4）取回**同一个 `Statement` 实例**交给语句层。
 * - **掉尾 RESULT 清零**：合成一条 `RESULT = 0` 赋值语句投进池（spec: Process.cs:1060-1063），
 *   `_RET` 本身仍从 VM 临时槽取值。
 *
 * ## 其余模型
 *
 * - **函数表** = 调用方依装载序提供的非事件函数全集 + [compileEventEntry] 合成的事件入口
 *   （每个事件名一个"按 eventCallOrder 依序 _CALL"的入口函数，spec: CalledFunction.cs:228-243）。
 * - **表达式**：复用 `core.expr` 的 Expr AST，整体放入表达式池（[CodeBuilder.expr]），
 *   以 `_EVAL_INT`/`_EVAL_STR`（表达式池下标 → 目标槽）求值；常量折叠是后续 agent 的工作。
 * - **调用**：`_CALL` 按位置复制调用者 R0..R(argc-1) 到被调帧（spec: Vm.kt 帧模型）。
 *   实参先求值到临时槽（≥ tempBase），再依序 `_MOV_*` 进 R0..R(argc-1)——tempBase 取
 *   本函数最大实参数（两遍编译：第一遍探测），保证暂存区与临时槽不相交，嵌套调用
 *   （`f(g(x), y)`）的内层暂存不会破坏外层已求值实参。
 * - **CALLFORM/JUMPFORM**：函数名为表达式 → `_EVAL_STR` + `_CALL_BY_NAME`（契约 C5：
 *   `a`=名字槽、`b`=实参数且 **bit31 = try 标志**、**bit30 = JUMP 尾跳标志**、`c`=返回槽）。TRY 变位由运行期
 *   `resolveFunctionIndex` 未命中时"静默跳过本行"（spec: FunctionIdentifier.cs:3577-3588）。
 * - **GOTOFORM 系**：`_JUMP_BY_NAME`（199，编译器产物，VM 按当前函数标签表解析；
 *   `b` 的 bit31 = try 标志，未命中时 TRY 跳过本行、非 TRY 抛 CodeEE）。
 * - **TRY 系（静态名）**：目标静态可知时按"存在 → 正常发射 / 不存在 → 不发射"实现
 *   "查不到则跳过"（[compileFunction] 的 knownFunctions 参数来自符号表全集；
 *   spec: FunctionIdentifier.cs:3688-3691 GOTO 的 `JumpTo == null → return`）。
 *   **TRYC 系**（TRYCCALL/TRYCJUMP/TRYCGOTO 及其 FORM 变体）的失败目标是**配对的 CATCH 行**，
 *   不是"下一行"：spec: FunctionIdentifier.cs:4148-4153（`funcMatch[TRYCCALL] = "CATCH"`）、
 *   ErbLoader.cs:1339-1348（`TRYC.JumpToEndCatch = CATCH 行`）、
 *   FunctionIdentifier.cs:3583-3586 / 3705-3707（失败 → `state.JumpTo(JumpToEndCatch)`）。
 *   **例外**：TRYCGOTO 的**常量** `$标签` 缺失走 GOTO_Instruction 的运行期早退
 *   （`JumpTo == null → return`，FunctionIdentifier.cs:3685-3693）——跳过本行、不跳 catch；
 *   装载期同样无条件开块（ErbLoader.cs:1169-1178），故仍压 TrycScope 供 CATCH 配对。
 *   详见 [compileJumpLike] 的 TRYC 注释。
 *
 * ## 行号表
 *
 * 每条指令带源码行号（[CodeBuilder.emit] 的 line 参数），进 Program.lines，CodeEE 报错定位用。
 */
class ErbProgramCompiler(
    /**
     * 变量名 → 槽 布局。契约 C1 后**主路径不再使用**（具名变量一律走语句池 + VariableStore）；
     * 保留是为了（a）既有调用方签名兼容（erbcc 桥、语料门按位置参数传入），
     * （b）名字表导出（[GlobalNameLayout.intSlots]/[strSlots]）与测试。
     */
    val layout: LocalLayout = GlobalNameLayout(),
    /**
     * 用户函数缺省实参默认值表（见 [CallDefaults]）。**空表 = 旧行为**：
     * 省略位原样留 null，运行期按 `Arguments[i] == null` 跳过不写
     * （spec: ProcessState.cs:437），即被调形参保留既有值。
     *
     * 装载侧必须**全部函数切片解析完之后**再建编译器（默认值来自被调 @行 的头部），
     * 见 `RealCorpusCompile` / `GoldenPipeline` / `CorpusCompile` 的装配点。
     */
    val callDefaults: CallDefaults = emptyMap(),
) {

    private val b = CodeBuilder()

    /**
     * 语句树规范折叠器（MEM-OPT）。折叠发生在 [emitStatementMarker]（唯一入池口），
     * probe 遍（第一遍 maxArgc 探测，写临时 CodeBuilder）跳过；规范表在 [build] 释放。
     */
    private val canonicalizer = ExprCanonicalizer()
    private val compiled = ArrayList<FunctionBytecode>()
    private val declaredNames = LinkedHashSet<String>()

    /**
     * `build()` 时按 [VarArgSite] 的目标收集的被调签名（大写键 → [FuncSig]）。
     * 用来把 REF 实参的**位序**落到站点上：`FuncSig.paramNames` 是头部形参序
     * （`ARG:1` 这种带下标项取冒号前的名字），与 `LocalLayout.refParamNamesOf` 的
     * REF 名字集合求交即得 REF 位。语料 63/539 个 REF 形参的 `#DIM` 声明序 ≠ 头部位序，
     * 不能用声明序替代。
     *
     * **只收站点用到的目标**（站点在登记时已按「目标有 REF」过滤），不给 11 万函数
     * 建第二张签名表（内存敏感；`FunctionBytecode.sig` 已持同一实例）。
     */
    private val signatures = HashMap<String, FuncSig>()

    /** 无 REF 形参的被调（大写键）缓存 —— 避免每次调用都问布局。 */
    private val refTargets = HashMap<String, Boolean>()

    /** 全部「裸变量实参」调用位（仅正式发射遍登记；probe 遍的 pc 属临时 builder，不入账）。 */
    private val varArgSites = ArrayList<VarArgSite>()

    /** 已编译函数（按编译序 = 函数表序）。 */
    val functions: List<FunctionBytecode> get() = compiled

    /** 已声明函数名（大写键），可作为下一次 [compileFunction] 的 knownFunctions 来源。 */
    val knownFunctionNames: Set<String> get() = declaredNames

    /**
     * 编译一个 @函数。两遍：第一遍在临时 CodeBuilder 上探测最大实参数（决定实参暂存区
     * R0..R(maxArgc-1) 与临时槽的分界），第二遍正式发射。
     *
     * **登记晚于发射（CMP-26）**：旧实现先把名字写进 `declaredNames` + CodeBuilder 函数表，
     * 再跑 pass2；pass2 一抛错就留下
     * - **无尾 `_RET` 的截断体**（入口 pc 已登记、函数体只发射了一半，执行到该入口会顺延
     *   跑进下一个函数的首条指令）；
     * - **名字已占**：调用方的空体兜底（`CorpusCompile` 的 `catch (CompileException)` 重编
     *   `emptyList()`，`CorpusCompile.kt:564-574`）直接撞「函数重复声明」，安全桩落不了，
     *   `compiled` 缺条目 ⇒ 任何 `_CALL` 都在 [build] 以「未定义的函数」炸掉整个 Program。
     *
     * 原版是**行级**错误（装载照常完成，执行到错误行才抛：spec: ErbLoader.cs:1249-1255
     * 弹栈续装、Process.cs:1017-1019 `currentLine.IsError → throw CodeEE`），本侧以函数为
     * 装载单元，对齐方式只能是「失败不留半截注册」：pass2 的指令先发进共享 builder
     * （失败时留在原处也**不可达** —— 前一个函数必以 `_RET` 收尾），全部成功后才
     * `declareFunction`/`declaredNames.add`。失败路径只多出不可达残段，产物字节只在
     * 「本来就有编译失败」的输入上变化（当前语料 0 失败）。
     *
     * @param statements 函数体语句（@行 与其后的 #行 归属本函数；[FuncLabelStmt]/[SharpStmt]
     *   在函数体内出现时被忽略——@行 由调用方切片，#DIM 等元数据不产生指令）
     * @param knownFunctions 全程序函数名集合（大写键），TRY 系判断"目标存在"用；
     *   传空集则 TRY 一律视为不存在（跳过）
     * @param exprCalls 表达式位调用的编译期分类表（[ExprCallTable]，A15 任务 3）。
     *   **null（默认）= 旧行为**：表达式里的 `FuncCall` 原样入池、不降级、不校验
     *   （语料 100% 编译绿灯依赖这一路径不变）；非 null 时启用
     *   [契约 C 的表达式位用户方法调用降级](specs 契约 C1/C3 + CONTRACT-M2-E2E §3 A3/A13
     *   与 A14 诊断 §3)，并对"表达式位调用普通 @label"复刻原版 UsedNonMethodFunc
     *   （spec: IdentifierDictionary.cs:566-569）。接线方（erbcc 桥/组长）据符号表
     *   `LabelDef.isMethod`/`#FUNCTION`、`#FUNCTIONS` 构造本表。
     */
    fun compileFunction(
        sig: FuncSig,
        statements: List<Statement>,
        knownFunctions: Set<String> = emptySet(),
        exprCalls: ExprCallTable? = null,
    ): FunctionBytecode {
        val key = sig.name.uppercase()
        if (key in declaredNames) {
            throw CompileException(sig.name, -1, "函数重复声明: ${sig.name}")
        }
        // pass 1：探测 maxArgc（暂存区大小）
        val probe = FunctionPass(sig, statements, knownFunctions, tempBase = 0, out = CodeBuilder(), exprCalls = exprCalls)
        probe.run()
        // pass 2：正式发射（先发射、成功后才登记 —— CMP-26）
        val entryPc = b.here()
        // 局部布局不再参与发射（契约 C1：具名变量只在 VariableStore）；临时槽基址 = 暂存区之上。
        // 标签只在 pass 内记账，函数表条目建立后再由 `pass.labels` 统一升格导出
        // （CMP-26：失败不得留下半个函数/半张标签表）。
        val pass = FunctionPass(sig, statements, knownFunctions, tempBase = probe.maxArgc, out = b, exprCalls = exprCalls, entryPc = entryPc)
        pass.run()
        // 到这里才登记：pass2 之前的任何抛出都不会留下占位名或半截函数条目。
        val index = b.declareFunction(sig.name, entryPc, sig.arity, sig.arity, sig.paramNames)
        declaredNames.add(key)
        varArgSites.addAll(pass.varArgSites)
        // spec: Process.cs:1060-1063 —— 掉尾（无 RETURN/RETURNF）的函数先把 RESULT 清 0 再 _RET。
        // 契约 C1/C3：RESULT 是具名变量，不得落到 VM 槽 —— 投一条合成的 `RESULT = 0`
        // 赋值语句进语句池，由语句层 execAssign 写 VariableStore；`_RET` 仍从帧内临时槽取值。
        emitStatementMarker(b, synthAssign(resultRef(), OperatorCode.ASSIGNMENT, IntLiteral(0), -1, "RESULT = 0"))
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, -1)   // 落到函数尾的兜底返回
        val slotCount = maxOf(sig.arity, pass.maxTemp + 1)
        b.setFunctionSlots(index, slotCount)
        // 标签表升格导出：name → (下标, pc)。下标 = _JUMP_DYN 的 a 操作数；pc 供
        // ProcessRuntime.LabelTable（resolveLabel→pc）与 VM 换算（FunctionInfo.labelPcs）。
        val labelIndexes = pass.labels.mapValues { (name, pc) -> b.declareLabel(index, name, pc) }
        val fb = FunctionBytecode(
            sig = sig,
            index = index,
            entryPc = entryPc,
            instructionCount = b.here() - entryPc,
            slotCount = slotCount,
            labels = pass.labels,
            labelIndexes = labelIndexes,
            callRefs = pass.callRefs,
        )
        compiled.add(fb)
        layout.endFunction()
        return fb
    }

    /**
     * 事件合成入口：把同名事件函数按 eventCallOrder 编成"依序 _CALL 列表"。
     * spec: loader.md 附录 D —— ONLY → PRI → 普通 → LATER，桶内按装载序；#ONLY 命中即止
     * 的截断语义由调用方决定传入哪些函数体（全量传入 = 全部执行，运行期层处理 ONLY）。
     *
     * 各函数体必须已通过 [compileFunction] 编译（名字 = 调用方给的唯一键）；
     * [name]（事件名本身，如 EVENTTURNEND）作为 CALLEVENT/CALL 的解析目标。
     */
    fun compileEventEntry(name: String, orderedBodies: List<String>): FunctionBytecode {
        val key = name.uppercase()
        if (key in declaredNames) {
            throw CompileException(name, -1, "函数重复声明（事件入口）: $name")
        }
        val entryPc = b.here()
        // CMP-26：先把全部被引用函数体解析完（这一步会抛），再登记 + 发射 —— 失败时
        // 名字保持空闲，同一事件入口可以补齐函数体后重编，不会留下只有入口的无尾体。
        val bodyIndexes = orderedBodies.map { body ->
            val idx = b.functionIndex(body)
            if (idx < 0) throw CompileException(name, -1, "事件入口引用了未编译的函数体: $body")
            idx
        }
        val index = b.declareFunction(name, entryPc, 0, 0)
        declaredNames.add(key)
        for (idx in bodyIndexes) b.emit(Opcode._CALL, idx, 0, SlotAddr.NONE, -1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, -1)
        b.setFunctionSlots(index, 0)
        val fb = FunctionBytecode(
            sig = FuncSig(name), index = index, entryPc = entryPc,
            instructionCount = b.here() - entryPc, slotCount = 0,
            labels = emptyMap(), labelIndexes = emptyMap(), callRefs = emptyList(),
        )
        compiled.add(fb)
        return fb
    }

    /**
     * 回填全部 [_CALL] 引用并冻结为 [Program]。
     * @param entryFunction 入口函数名；null = 函数表第一个。
     */
    fun build(entryFunction: String? = null): Program {
        // MEM-OPT：全部函数已编译完，规范折叠表是一次性编译期开销，立即释放
        // （已共享出去的规范实例由语句树自身持有，不受影响）。
        canonicalizer.release()
        for (fb in compiled) {
            for (r in fb.callRefs) {
                val idx = b.functionIndex(r.target)
                if (idx < 0) {
                    throw CompileException(fb.sig.name, r.line, "未定义的函数: ${r.target}")
                }
                b.patch(r.pc, 1, idx)
            }
        }
        val entry = if (entryFunction != null) {
            val i = b.functionIndex(entryFunction)
            if (i < 0) throw CompileException(null, -1, "入口函数不存在: $entryFunction")
            i
        } else {
            0
        }
        patchRefArgEvals()
        // 装载期实参签名校验（CORE-05）：显式传缺省口径（宽松，无静态全局可设）；
        // 告警经返回值交调用方，本钩子不接（邻波接线装载返回值 / BootMetadata 时在此挂出）。
        return b.build(entry).also { ArgSignatureCheck.checkProgram(it, ArgSignatureCheck.Options()) }
    }

    // ======================================================================
    // REF 形参位的实参求值回填
    // ======================================================================

    /**
     * 把「REF 形参位」的实参值求值指令回填成常量（int → 0、str → ""）。
     *
     * 原版 REF 位**不读实参的值**：装载期 `ConvertArg` 只要求实参是 `VariableTerm` 且
     * 与被调 `ReferenceToken` 同型同维（spec: CalledFunction.cs:154-159），求值期
     * `SetTransporter` 对 REF 位直接取 `variableTerm.Identifier.GetArray()`
     * （spec: UserDefinedFunctionArgument.cs:45-58），进入被调帧时
     * `SetRef(TransporterRef[i])` 绑的是**数组本体**（spec: ProcessState.cs:439-441 →
     * ReferenceToken.SetRef:125-128）。裸多维数组实参（`CALL F(MAT)`，MAT 为 2D）在
     * 原版因此合法；本移植若照常 `_EVAL_INT`，`VarRef` 造 0 长下标 → ReadInt 的
     * `checkElement` 抛「变量 MAT 需要 2 个下标（实际 0）」，`_CALL` 都到不了。
     *
     * 所以这里在**全部函数编译完之后**（`build()`，被调签名与 REF 名集合都齐）把该位的
     * 值指令的表达式操作数换成常量：REF 绑定走 `Program.callArgs` 的变量名
     * （[com.eratw.port.core.proc.ProcessRuntime.bindCallArgs] 的 `param.spec.isRef` 分支），
     * 槽里的值本就被丢弃，常量不会进入任何变量。非 REF 位一律不动 —— 裸多维数组按值
     * 传参仍在原处抛「需要 N 个下标」，与旧行为逐字一致。
     *
     * 只回填**变量实参**（[VarArgSite] 只在 `VarRef` 实参登记，见 [stageArgs]）：
     * 原版 REF 位的实参必须是 `VariableTerm`（spec: CalledFunction.cs:154），非变量实参
     * 原版装载期就拒绝，本侧维持按值求值（旧行为）。
     * * 裸多维数组（`MAT`）不再触发 ReadInt 的「需要 N 个下标」。
     * * 带下标数组（`MAT:1:2`）的**下标表达式也不求值**（原版 `GetArray()` 忽略 term 的
     *   arguments，spec: CalledFunction.cs:154-159 → ReferenceToken.SetRef:125-128）；
     *   下标里的表达式位方法调用属 `lowerExpr` 在值指令**之前**发射的前导 `_CALL`，
     *   本遍不去除（TODO(verify)：原版连它也不执行）。
     * * 裸 1D 变量会被表达式解析器补成 `X:0`（spec: VariableParser 省略下标规则）——它
     *   仍是 VarRef，同样回填；只有「未绑定的 1D REF 实参转发」在 `X:0` 求值期会提前
     *   EmptyRefVar 的旧差异随之消失（绑定按名字整数组，`GetArray` 不读值）。
     * * `CALLFORM` 等**运行期定名**的调用点 `target == null`，不登记站点、不回填，
     *   仍按值求值（裸多维数组实参照旧报「需要 N 个下标」）—— TODO(verify)：需要
     *   运行期 `resolveFunctionIndex` 之后按被调 `#DIM REF` 再分派，属容器/运行期面。
     */
    private fun patchRefArgEvals() {
        if (varArgSites.isEmpty()) return
        // 只把**回填会用到的**被调签名收进表：站点已按「目标有 REF」过滤，条目数 = 数百，
        // 不给 11 万函数建第二张签名表（FunctionBytecode.sig 已持同一实例）。
        val needed = HashSet<String>(varArgSites.size * 2)
        for (s in varArgSites) needed.add(s.target.uppercase())
        for (fb in compiled) {
            val k = fb.sig.name.uppercase()
            if (k in needed) signatures[k] = fb.sig
        }
        var intConst = -1
        var strConst = -1
        for (site in varArgSites) {
            val flags = refParamFlags(site.target) ?: continue
            if (site.position !in flags.indices || !flags[site.position]) continue
            val poolIndex = if (site.isStr) {
                if (strConst < 0) strConst = b.expr(StrLiteral(""))
                strConst
            } else {
                if (intConst < 0) intConst = b.expr(IntLiteral(0))
                intConst
            }
            // _EVAL_INT/_EVAL_STR 的 a 操作数 = 表达式池下标（emit 的 operand 1）。
            b.patch(site.evalPc, 1, poolIndex)
        }
    }

    /**
     * 被调函数逐位 REF 标志；null = 拿不到完整信息（布局不供 REF / 被调签名未编译到 /
     * 无形参名），调用方保持旧行为（按值求值，不做任何回填）。
     *
     * 位序 = [FuncSig.paramNames]（头部形参序）；REF 名字集合 =
     * [LocalLayout.refParamNamesOf]。头部形参项可能带 `:n` 下标（`@F(ARG:1)`）与
     * 字符串后缀 `$`（`@F(B$)`），归一后再与 `#DIM REF` 的名字比对。
     */
    private fun refParamFlags(target: String): BooleanArray? {
        val key = target.uppercase()
        val refNames = layout.refParamNamesOf(key) ?: return null
        if (refNames.isEmpty()) return BooleanArray(0)
        val sig = signatures[key] ?: return null
        if (sig.paramNames.isEmpty()) return null
        return BooleanArray(sig.paramNames.size) { i ->
            sig.paramNames[i].substringBefore(':').trim().removeSuffix("$").uppercase() in refNames
        }
    }

    /**
     * 被调函数是否有 REF 形参（大写键缓存）。
     *
     * 用途是**省内存**：无 REF 的被调不登记 [VarArgSite] —— 全语料 11 万函数里 REF
     * 形参只 539 个，而裸变量实参在 15 万调用点里很常见；不设这道门会让 `.erbc` 编译
     * 白建一张大表。
     *
     * 信息源是 [LocalLayout.refParamNamesOf]：生产 erbcc（`CorpusCompile`）与 boot/E2E
     * 装置都必须传**带 REF 声明的**布局（[DimSpecLayout]，`LabelDef.dimSpecs` →
     * [LocalDim.ref]）。早期生产档传 [GlobalNameLayout]（不持声明、恒返回 null）⇒ 本方法
     * 恒 false、[patchRefArgEvals] 空转，裸多维数组实参在 REF 位照常求值并在运行期抛
     * 「需要 N 个下标」—— CMP-25 已把生产接线改到 [DimSpecLayout]，
     * `ProdLayoutRefArgRedTest` 锁住这条接线（布局不供 REF 信息时本方法必须退回旧行为）。
     */
    private fun hasRefParams(target: String): Boolean {
        val key = target.uppercase()
        refTargets[key]?.let { return it }
        val has = !layout.refParamNamesOf(key).isNullOrEmpty()
        refTargets[key] = has
        return has
    }

    // ======================================================================
    // 语句池 marker（契约 C2/C3）
    // ======================================================================

    /**
     * 发一条 marker：`op = 语句操作码（29..197）`、`a = 语句池下标`、`b = 0`、`c = NONE`。
     * `out.statement(s)` **按身份追加**（契约 C2：严禁去重 —— sem 的 PRINTDATA/STRDATA
     * 数据节以 Statement 实例为键），运行期 VM 用 `program.statement(a)` 取回的就是
     * 这里投入的**同一个实例**。
     */
    private fun emitStatementMarker(out: CodeBuilder, s: Statement, functionName: String? = null) {
        // METHOD 在 isa.yaml 只有规范名、没有 ERB 书写别名（别名是"所有函数方法名"的集合），
        // 故 ALIASES 查不到时回落到规范名表。
        val op = StatementAliases.resolve(s.code.name) ?: Opcode.BY_NAME[s.code.name]
            ?: throw CompileException(functionName, s.line, "未知语句: ${s.code}")
        // MEM-OPT：入池前做表达式规范折叠。只在正式发射（out === b）时做 —— probe 遍
        // 的临时 builder 直接丢弃，折叠是白烧 CPU。语句实例按 C2 恒新开条目，折叠
        // 只收敛 Expr 节点、绝不合并语句条目。
        val s3 = if (out === b) canonicalizer.canonicalizeStatement(s) else s
        out.emit(op, out.statement(s3), 0, SlotAddr.NONE, s3.line)
    }

    /**
     * 合成的赋值语句（`dest op= rhs` 形态）。
     *
     * 复合运算符必须与源码赋值语句同形：解析期 [com.eratw.port.core.stmt.ArgBuilders] 把
     * 非常量复合赋值收成「整式」`Term = dest op rhs`（ArgumentParser.cs:1092-1096），执行层
     * 按原版 SET_Instruction 直接取 Term 值落库（FunctionIdentifier.cs:640-657）。合成的
     * `FOR` 步进若保留原始右值 + op，执行层会把整式语义落成 `dest = rhs`——循环变量被写成
     * 步长本身。故这里统一折成同一形态；常量步长照原版走 `AddConst`，省一条表达式求值。
     */
    private fun synthAssign(dest: VarRef, op: OperatorCode, rhs: Expr, line: Int, raw: String): AssignStmt {
        val arg = when {
            op == OperatorCode.ASSIGNMENT -> SpSetArgument(dest, rhs)
            rhs is IntLiteral && (op == OperatorCode.PLUS || op == OperatorCode.MINUS) ->
                SpSetArgument(dest, null).apply {
                    isConst = true
                    addConst = true
                    constInt = if (op == OperatorCode.PLUS) rhs.value else -rhs.value
                }
            else -> SpSetArgument(dest, com.eratw.port.core.expr.reduceBinaryTerm(op, dest, rhs))
        }
        return AssignStmt(dest, op, arg, line, raw)
    }

    /**
     * 系统变量 RESULT 的引用（掉尾清零用）。spec: Process.cs:1060-1063
     *
     * **必须带 1 个下标**（A15-E2E 任务 1；spec: diagnosis §2）：RESULT 在 registry 是
     * 1 维整数数组（SystemLayout.kt:25 `int1D` → Storage.G_INT1，VarDef.kt:115
     * `argCount = dims.size + 0 = 1`）。零下标 VarRef 经 VarRef.getIntValue 造出
     * 0 长 LongArray（Expr.kt:90），writeInt 的 checkElement（VariableStore.kt:916-918）
     * 必抛"变量 RESULT 需要 1 个下标（实际 0）"——任何落尾函数都会崩。
     * 补全口径与解析期省略下标一致：一般 1D 省略 → 0（spec: ExprParser.buildVariableTerm，
     * VariableParser.cs:65-190 的"一般 1D：省略→0"分支）。
     */
    private fun resultRef(): VarRef =
        VarRef("RESULT", listOf(IntLiteral(0)), operandType = OperandType.INT)

    /**
     * 系统变量 RESULTS 的引用（行首 `#FUNCTIONS` 方法调用的结果落点）。
     * spec: FunctionIdentifier.cs:592-603（METHOD_Instruction：字符串返回写 RESULTS）+
     * SystemLayout.kt 的 `str1D`（1 维字符串数组，与 RESULT 同口径必须带 1 个下标）。
     */
    private fun resultsRef(): VarRef =
        VarRef("RESULTS", listOf(IntLiteral(0)), operandType = OperandType.STR)

    // ======================================================================
    // 单函数编译状态机
    // ======================================================================

    /** 跳转回填项：[label] != null 表示 GOTO 目标（函数尾统一解析），否则为结构跳转（立即回填）。 */
    private class Fixup(val pc: Int, val operand: Int, val label: String? = null)

    private sealed class Scope

    /** IF/ELSEIF/ELSE/ENDIF。spec: isa.yaml IF —— 依序求值条件，跳到第一个为真的分支体。 */
    private class IfScope : Scope() {
        val endifJumps = ArrayList<Fixup>()       // 各分支体尾 → ENDIF 之后
        var pending: MutableList<Fixup>? = mutableListOf()   // 条件失败链 → 下一分支/ENDIF
    }

    /**
     * TRYC/CATCH/ENDCATCH 块（契约 R3，CONTRACT-M2-E2E §5）：结构同 IF/ELSE ——
     * **TRYC 语句自身就是 try 块头**，与 TRY 的唯一差异 = 目标缺失时跳 **CATCH 体首行**
     * （TRY 只落下本行下一条）。CATCH 双重角色 = ELSE（既是 TRYC 的闭合词又是 ENDCATCH
     * 的开启词，spec: FunctionIdentifier.cs:4148-4153 `funcMatch[TRYCCALL]="CATCH"` +
     * core/stmt BlockChecker 的 CATCH 双重角色口径；FunctionCode.openToClose 已登记
     * TRYC*→CATCH、CATCH→ENDCATCH）。
     */
    private class TrycScope : Scope() {
        /** "目标缺失"跳转 → **catch 体首 pc**（CATCH 行处回填；R1：= CATCH 行编译出的第一条指令之后一条）。 */
        val missJumps = ArrayList<Fixup>()
        /** try 体尾跳转（CATCH 行发射，越过 catch 体）→ ENDCATCH 之后。 */
        val endJumps = ArrayList<Fixup>()
        /** 已进入 catch 体（防止双 CATCH / ENDCATCH 缺失时错位）。 */
        var inCatch = false
    }

    private class LoopScope(
        val headPc: Int,
        val kind: Kind,
        /** 条件失败跳出口（DO 没有：出口在 LOOP 之后）。 */
        val exitJumps: List<Fixup> = emptyList(),
        /**
         * FOR：循环变量的**步进语句**（合成的 `cnt = cnt + step` 形态 `AssignStmt`）。
         * 契约 C1/C3：具名变量不得进 VM 槽，故 NEXT 处只发一条 marker，运行期由语句层
         * `execAssign` 的 `OperatorCode.PLUS → ctx.variables.plusInt` 落库
         * （spec: StatementExecutor.kt:1211-1223）。null = 非 FOR 循环。
         */
        val stepStmt: Statement? = null,
        /**
         * FOR：循环变量引用。BREAK 跳出前要先加一步（原版怪行为，ADR-005 复刻，
         * spec: FunctionIdentifier.cs:3312-3317），需要它来构造步进语句；非 FOR 循环为 null。
         */
        val cnt: VarRef? = null,
        /**
         * FOR：循环期内不得被临时分配复用的槽 —— 终值槽、**非常量步长的 FOR 行快照槽**
         * （CORE-03：步进语句与入口判定逐轮读它）、以及非常量路径的零值基准槽。
         */
        val holdSlots: List<Int> = emptyList(),
    ) : Scope() {
        enum class Kind { FOR, REPEAT, WHILE, DO }
        val breakJumps = ArrayList<Fixup>()
        val contJumps = ArrayList<Fixup>()
    }

    /** SELECTCASE。spec: isa.yaml SELECTCASE —— 对选择值依次匹配各 CASE 行。 */
    private class SelectScope(        val targetSlot: Int,
        val isString: Boolean,
    ) : Scope() {
        val exitJumps = ArrayList<Fixup>()   // 各 case 体尾 → ENDSELECT 之后
        val failJumps = ArrayList<Fixup>()   // 本 CASE **最后一个** caseExp 的失配链（→ 下一 CASE / CASEELSE / 出口）
        val hitJumps = ArrayList<Fixup>()    // 当前 CASE 的命中链 → 体首
        var bodyOpen = false
    }

    /**
     * 函数级「上一条循环锚点」—— **ADR-005 复刻官方 bug**（CMP-33）。
     *
     * 原版把 `InstructionLine instructionLine` 声明在扫描 `while (true)` **之外**且永不重置
     * （spec: ErbLoader.cs:1049）；BREAK/CONTINUE 只在块栈里找到循环时才给它赋值
     * （:1182-1205），循环闭合（NEXT/WEND/LOOP）也把该循环头赋给它（:1335）。于是同一函数里
     * **先有循环、后有循环外** BREAK/CONTINUE 时，后者沿用**上一条循环锚点**静默错跳：
     * 没有告警、没有 THROW，控制流直接跳到那个已结束循环的出口/回边处。
     *
     * 本类把「在环内 BREAK/CONTINUE 会跳到的位置」提前算出来，供循环外语句复用：
     * [breakPc] = 该循环闭合后的第一条指令（BREAK 出口）、[contPc] = 该循环 CONTINUE 的
     * 回填目标、[stepStmt] = FOR 类锚点跳出前要补的一步
     * （spec: FunctionIdentifier.cs:3312-3317，WHILE/DO 为 null）。
     */
    private class StaleLoopAnchor(
        val breakPc: Int,
        val contPc: Int,
        val stepStmt: Statement?,
    )

    private inner class FunctionPass(
        val sig: FuncSig,
        val statements: List<Statement>,
        val known: Set<String>,
        val tempBase: Int,
        val out: CodeBuilder,
        /** 表达式位调用降级表（null = 旧行为，不降级不校验；见 [compileFunction]）。 */
        val exprCalls: ExprCallTable? = null,
        /**
         * 本函数在共享 CodeBuilder 里的入口 pc —— RESTART 的同帧回跳目标
         * （契约 R10/API 4：**同帧跳回函数头、变量保留**，spec: FunctionIdentifier.cs:3288-3298）。
         * 探测遍（probe）不发射真指令，用默认 0。
         */
        val entryPc: Int = 0,
    ) {
        private var nextTemp = tempBase
        private var maxTempSeen = tempBase - 1
        private val freeTemps = ArrayDeque<Int>()
        private val scopes = ArrayList<Scope>()
        private val labelFixups = ArrayList<Fixup>()
        private var sifFixup: Fixup? = null

        /**
         * 函数级「上一条循环锚点」（CMP-33，ADR-005 复刻官方 bug）。
         *
         * 每次循环闭合（NEXT/WEND/LOOP）时刷新；**不随循环关闭而清空** —— 原版的
         * `instructionLine` 正是这样泄漏到循环外（spec: ErbLoader.cs:1049 vs :1182-1205）。
         * TODO(verify)：原版同一个变量还被 CATCH/ENDCATCH 的 `stack.Pop()` 覆写
         * （ErbLoader.cs:1346/:1356）成 TRYC 语句；那种锚点会让 BREAK 读
         * `LoopCounter`（未初始化，NRE 面）—— 按 BOARD 只复刻「循环锚点」这一条，
         * 非循环锚点仍走 [emitDeferredLoopError]。
         */
        private var staleLoop: StaleLoopAnchor? = null

        /**
         * 本次表达式降级产生的**返回值临时槽**（sentinel `#EXPR_RET#<k>` 的 k）。
         * 槽在消费指令（_EVAL_* / marker）发射后统一归还 [freeTemps]（[drainRetTemps]）：
         * 指令流线性执行，消费点之后调用帧里再无人读它。
         */
        private val pendingRets = ArrayList<Int>()

        /**
         * 函数内 `$标签` 表（大写名 → pc）。用 [LinkedHashMap] 保持**语句序**：正式发射遍
         * 不再在 [defineLabel] 里预登记进 CodeBuilder（CMP-26 登记晚于发射），标签表的
         * 插入序改由本表的迭代序决定；沿用旧实现（随扫描序注册）的产物字节序。
         */
        val labels = LinkedHashMap<String, Int>()
        val callRefs = ArrayList<CallRef>()

        /**
         * 本遍登记的「裸变量实参」调用位（[VarArgSite]）。只有正式发射遍的列表会被
         * [ErbProgramCompiler.compileFunction] 收进编译器级表；probe 遍的 pc 属临时
         * [CodeBuilder]，收进去会回填到错误指令上。
         */
        val varArgSites = ArrayList<VarArgSite>()

        var maxArgc = 0
            private set

        /** 本遍见过的最大临时槽下标（[tempBase] - 1 起，即帧内最高用到的槽）。 */
        val maxTemp: Int get() = maxTempSeen

        /**
         * 本函数**可定义**的 `$标签` 名集合（大写）—— TRY 系「标签不存在则跳过」的编译期判定。
         *
         * 预扫描必须与 [defineLabel] 的实际覆盖集合一致（CMP-28）：TRY{CALL,JUMP,GOTO}LIST 是
         * 自带 `FUNC … ENDFUNC` 的子块，由 [compileTryList] 整体消费，块内 `$标签` 永远
         * 走不到 [defineLabel]。旧实现用 `filterIsInstance<GotoLabelStmt>()` 把块内标签也算进
         * 「存在」，于是 TRYGOTOLIST 的常量目标判定命中、发射 `_JUMP` 并登记 labelFixup，
         * 而回填表里没有该标签 ⇒ `run()` 末尾抛「GOTO 目标不存在」⇒ 整函数编译失败（变空体桩）。
         *
         * 原版把 TRY*LIST 块内的 `GotoLabelLine` 判为装载期错误行
         * （`CanNotLabelDefineInSyntax`，spec: ErbLoader.cs:1060-1067）；常量 GOTO 查不到
         * 合法目标时 TRY 系运行期直接跳过本行（`JumpTo == null → return`，
         * spec: FunctionIdentifier.cs:3685-3693）。故块内标签必须从「存在」里剔除。
         */
        private val labelNames: Set<String> = run {
            val names = HashSet<String>()
            var i = 0
            while (i < statements.size) {
                val s = statements[i]
                if (s is InstructionStmt && s.code in TRY_LIST_CODES) {
                    // 跳过整个 FUNC…ENDFUNC 子块（块内嵌套 TRY*LIST 由 compileTryList 另报错）
                    i++
                    while (i < statements.size && statements[i].code != FunctionCode.ENDFUNC) i++
                    i++
                    continue
                }
                if (s is GotoLabelStmt) names.add(s.name.uppercase())
                i++
            }
            names
        }

        // ---- 槽分配 -------------------------------------------------------

        private fun alloc(): Int {
            val reused = freeTemps.removeFirstOrNull()
            if (reused != null) return reused
            val t = nextTemp++
            if (t > maxTempSeen) maxTempSeen = t
            return t
        }

        private fun free(t: Int) {
            freeTemps.addLast(t)
        }

        private fun local(t: Int): Int = SlotAddr.local(t)

        // ---- 发射辅助 -----------------------------------------------------

        private fun emitJumpAlways(line: Int): Fixup {
            val pc = out.emit(Opcode._JUMP, 0, 0, 0, line)
            return Fixup(pc, 1)
        }

        private fun emitJumpIfFalse(slotRaw: Int, line: Int): Fixup {
            val pc = out.emit(Opcode._JUMP_IF_FALSE, slotRaw, 0, 0, line)
            return Fixup(pc, 2)
        }

        private fun emitJumpIfTrue(slotRaw: Int, line: Int): Fixup {
            val pc = out.emit(Opcode._JUMP_IF_TRUE, slotRaw, 0, 0, line)
            return Fixup(pc, 2)
        }

        /** GOTO 到函数内 `$标签`（前向引用允许，run() 结束统一回填）。 */
        private fun emitGoto(label: String, line: Int): Fixup {
            val pc = out.emit(Opcode._JUMP, 0, 0, 0, line)
            val f = Fixup(pc, 1, label)
            labelFixups.add(f)
            return f
        }

        private fun patch(fixups: List<Fixup>, targetPc: Int) {
            for (f in fixups) out.patch(f.pc, f.operand, targetPc - (f.pc + 1))
        }

        /**
         * 表达式 → 槽。字面量走 `_SET_INT/_SET_STR`（免 EvalContext），其余入表达式池
         * 走 `_EVAL_INT/_EVAL_STR`。
         *
         * **表达式位调用降级在此生效**（A15 任务 3）：先 [lowerExpr]（用户方法调用的
         * 实参求值 + `_CALL` 前导指令即在此发射），再把降级后的树交给字面量/池分派；
         * 本指令发射后把**本次降级新增**的返回槽归还空闲表（drain 到入口标记 ——
         * 更外层消费者尚未读完的返回槽必须保持存活，见 [pendingRets]）。
         *
         * @return 值指令的 pc（`_SET_*`/`_EVAL_*`；REF 实参回填按它定位，见 [VarArgSite]）
         */
        private fun evalExprTo(e: Expr, dstRaw: Int, line: Int): Int {
            val mark = pendingRets.size
            val lowered = lowerExpr(e)
            val pc = when (lowered) {
                is IntLiteral -> out.emit(Opcode._SET_INT, dstRaw, out.constant(lowered.value), 0, line)
                is StrLiteral -> out.emit(Opcode._SET_STR, dstRaw, out.string(lowered.value), 0, line)
                else -> when (lowered.operandType) {
                    OperandType.INT -> out.emit(Opcode._EVAL_INT, out.expr(lowered), dstRaw, 0, line)
                    OperandType.STR -> out.emit(Opcode._EVAL_STR, out.expr(lowered), dstRaw, 0, line)
                }
            }
            drainRetTemps(mark)
            return pc
        }

        private fun evalTemp(e: Expr, line: Int): Int {
            val t = alloc()
            evalExprTo(e, local(t), line)
            return t
        }

        /** [evalTemp] + 值指令 pc（REF 实参回填用；见 [VarArgSite]）。 */
        private fun evalTempPc(e: Expr, line: Int): Pair<Int, Int> {
            val t = alloc()
            return t to evalExprTo(e, local(t), line)
        }

        // ---- 主循环 -------------------------------------------------------

        fun run() {
            // 索引循环（契约 R10-D）：TRY*LIST 是自带 `FUNC … ENDFUNC` 的子块，
            // 由 [compileTryList] 整体消费并返回 ENDFUNC 之后的下标。
            var i = 0
            while (i < statements.size) {
                val s = statements[i]
                val isTryList = s is InstructionStmt && s.code in TRY_LIST_CODES
                if (sifFixup != null) {
                    // SIF 只跳过紧随的一条语句；块结构语句不可被 SIF 修饰。
                    // 原版装载期判据是 Function.IsPartial()（flag & 0x10，spec:
                    // FunctionIdentifier.cs:3049-3052 → FuncCanNotAfterSif、:4265-4268），
                    // 命中即**装载期报错**：ELSE/ELSEIF/CASE/CASEELSE/IF/SIF=53、ENDIF/ENDSELECT/
                    // DO/ENDCATCH/ENDFUNC=49、SELECTCASE/LOOP=55、WHILE/WEND/CATCH=23、
                    // FOR/REPEAT/REND/NEXT=21 —— 全含 0x10（见各自的 *_Instruction 构造函数）。
                    // 本侧没有 flag 表，用 BlockRole != NONE（OPEN/BRANCH/CLOSE）等价覆盖块族；
                    // 嵌套 SIF（flag=53，role 仍是 NONE）必须另行拒绝 —— 否则它会把
                    // sifFixup 覆盖掉、丢掉外层的补跳（`SIF a / SIF b / x` 编译期静默错位）。
                    // TODO(verify): PARTIAL 里还有 DATALIST/NOSKIP 等数据节边界（flag=22），
                    // 它们在本侧 role=OPEN/CLOSE，已由上面的 role 判据覆盖。
                    if (s.role != BlockRole.NONE || isTryList || (s is IfStmt && s.single)) {
                        throw CompileException(
                            sig.name, s.line,
                            "SIF 之后不能是 ${s.code} 语句（SIF 的目标行不能是块语句/分支语句，" +
                                "spec: FunctionIdentifier.cs:3049-3052）",
                        )
                    }
                    // 补齐三类原版装载期报错缺口（SIF_Instruction.SetJumpTo，
                    // spec: FunctionIdentifier.cs:3038-3070）：
                    // - :3058-3061 LabelCanNotAfterSif —— 紧随 GOTO 标签行（GotoLabelLine）；
                    // - :3041-3046 NothingAfterSif —— 紧随函数标签行（FunctionLabelLine），
                    //   或 SIF 是函数最后一条语句（nextLine 越过函数末尾落到下一条
                    //   FunctionLabelLine / 文件尾 NullLine，两者都命中 :3041-3046 判空）；
                    //   SIF 后恰有一条语句（函数末条）时原版不报错：运行期 JumpTo 落到
                    //   FunctionLabelLine/NullLine 即 Return(0)（Process.cs:1058-1063
                    //   runScriptProc），等价于「跳过该条后收尾」，与本侧补跳口径一致。
                    // - :3049-3052 FuncCanNotAfterSif（IsPartial() = flag & 0x10，
                    //   :4265-4268）在 role==NONE 下的缺口只有 DATA/DATAFORM（flag=22，
                    //   spec: FunctionIdentifier.cs:4018-4019）。
                    // 注意 # 元数据行在原版不进逻辑行链（ErbLoader.cs:413-422 解析后直接
                    // continue，不 addLine），故本侧 SharpStmt 亦按透明行处理：编译但不
                    // 消费 SIF 的补跳，SIF 的目标仍是其后第一条可执行语句。
                    when {
                        s is SharpStmt -> {
                            compileOne(s)
                            i++
                            continue
                        }
                        s is GotoLabelStmt || s is FuncLabelStmt -> throw CompileException(
                            sig.name, s.line,
                            "SIF 之后不能是标签行（NothingAfterSif/LabelCanNotAfterSif，" +
                                "spec: FunctionIdentifier.cs:3041-3046、:3058-3061）",
                        )
                        s is InstructionStmt &&
                            (s.code == FunctionCode.DATA || s.code == FunctionCode.DATAFORM) ->
                            throw CompileException(
                                sig.name, s.line,
                                "SIF 之后不能是 ${s.code} 语句（FuncCanNotAfterSif，" +
                                    "spec: FunctionIdentifier.cs:3049-3052、:4018-4019）",
                            )
                    }
                    compileOne(s)
                    patch(listOf(sifFixup!!), out.here())
                    sifFixup = null
                } else if (isTryList) {
                    i = compileTryList(i)
                    continue
                } else {
                    compileOne(s)
                }
                i++
            }
            if (sifFixup != null) {
                // SIF 是函数最后一条语句：原版 SetJumpTo 里 nextLine 越过函数末尾
                // （下一条 FunctionLabelLine / 文件尾 NullLine）命中
                // NothingAfterSif 报错（spec: FunctionIdentifier.cs:3041-3046）。
                val lastLine = statements.lastOrNull()?.line ?: -1
                throw CompileException(
                    sig.name, lastLine,
                    "SIF 之后没有可执行语句（NothingAfterSif，spec: FunctionIdentifier.cs:3041-3046）",
                )
            }
            if (scopes.isNotEmpty()) {
                val top = scopes.last()
                val line = statements.lastOrNull()?.line ?: -1
                throw CompileException(sig.name, line, "块未闭合：剩余 ${scopes.size} 层（栈顶 $top）")
            }
            // GOTO 标签回填。`emitGoto` 只在 `f.label in labelNames` 时登记（常量标签缺失已由
            // [emitStaticTargetNotFound] 编 THROW 哨兵），故下面的 `?: throw` 是不可达防御。
            for (f in labelFixups) {
                val target = labels[f.label]
                    ?: throw CompileException(sig.name, -1, "GOTO 目标不存在: \$${f.label}")
                out.patch(f.pc, f.operand, target - (f.pc + 1))
            }
        }

        private fun compileOne(s: Statement) {
            when (s) {
                is SharpStmt, is FuncLabelStmt -> {}              // 元数据行：不产生指令
                is GotoLabelStmt -> defineLabel(s)
                is IfStmt -> if (s.single) compileSif(s) else compileIf(s)
                is ElseIfStmt -> compileElseIf(s)
                is ElseStmt -> compileElse(s)
                is EndIfStmt -> compileEndIf(s)
                is SelectCaseStmt -> compileSelect(s)
                is CaseStmt -> compileCase(s)
                is CaseElseStmt -> compileCaseElse(s)
                is EndSelectStmt -> compileEndSelect(s)
                // FOR 与 REPEAT 都解析为 ForStmt：REPEAT 已被 ArgBuilders 改写成
                // "FOR COUNT, 0, n, 1" 形态（ArgBuilders.kt:241），统一走 FOR 路径
                is ForStmt -> compileFor(s)
                // LineParser 把 NEXT/REND 都解析为 NextStmt（LineParser.kt:305）；
                // REND 的循环（REPEAT→FOR 形态）也按步进+回边处理
                is NextStmt -> compileNext(s)
                is WhileStmt -> compileWhile(s)
                is WendStmt -> compileWend(s)
                is DoStmt -> {
                    scopes.add(LoopScope(headPc = out.here(), kind = LoopScope.Kind.DO))
                }
                is LoopStmt -> compileLoop(s)
                // 契约 R3：CATCH/ENDCATCH 是块结构，不入语句池、不发 marker。
                //（CatchStmt/EndCatchStmt 都是 InstructionStmt 子类，分支必须排在
                //  `is InstructionStmt ->` 兜底之前，否则会被 compilePlainInstruction 吞掉）
                is CatchStmt -> compileCatch(s)
                is EndCatchStmt -> compileEndCatch(s)
                is FlowStmt -> when (s.code) {
                    FunctionCode.BREAK -> compileBreak(s)
                    FunctionCode.CONTINUE -> compileContinue(s)
                    // 契约 R10/API 4：RESTART = 同帧跳回本函数入口（变量保留，不建新帧）
                    // spec: FunctionIdentifier.cs:3288-3298。
                    FunctionCode.RESTART -> backJump(entryPc, s.line)
                    else -> marker(s)
                }
                is JumpStmt -> compileJumpLike(s.code, s.label, s.args, s.line, isJump = true)
                is CallStmt -> compileJumpLike(s.code, s.label, s.args, s.line, isJump = false)
                is CallFStmt -> compileCallF(s)
                is MethodStmt -> compileMethod(s)
                // 赋值是数据面语句（契约 C3）：一律入池，运行期交 sem 的 execAssign
                // ——具名变量不得落到 VM 槽（契约 C1）。
                // 契约 R6：`SET` **从来不是注册语句名**（spec: FunctionIdentifier.cs:3829 只构造
                // setFunc、无 addFunction；语料 `SET x, y` 形态 0 处），旧编译器"SET 当通用赋值"
                // 特判分支在原版语义下不可达，已删除。AssignStmt.code=FunctionCode.SET 只是
                // 解析器给赋值产出的伪码（Statement.kt:116，非用户可书写语句）。
                is AssignStmt -> marker(s)
                // 契约 R10/API 4：RETURN **只入池**（不再发 `_RET`）——RESULT 数组的写入与
                // "返回调用者"由语句层（ExecResult.Return）完成，spec: FunctionIdentifier.cs:3250-3271。
                is ReturnStmt -> marker(s)
                is ReturnFStmt ->
                    if (s.code == FunctionCode.RETURNF) {
                        // CMP-31：RETURNF 只在 #FUNCTION/#FUNCTIONS 内合法 —— 原版装载期
                        // `if (!parentLabelLine.IsMethod) Warn(CanNotUseReturnf, isError:true)`
                        // （spec: FunctionIdentifier.cs:3455-3458）。本侧旧实现无条件发 `_RET`：
                        // 落在 depth 0 的非方法帧会把整局执行返回掉。语料 18,077 处实测全在
                        // 方法内 ⇒ 补校验不动语料。`#FUNCTIONS` 由 isMethod 一并覆盖。
                        if (!sig.isMethod) {
                            throw CompileException(
                                sig.name, s.line,
                                "RETURNF 只能在 #FUNCTION/#FUNCTIONS 内使用" +
                                    "（spec: FunctionIdentifier.cs:3455-3458 CanNotUseReturnf）",
                            )
                        }
                        // 字符串临时（STR 表达式自然落字符串槽；INT 表达式落整数槽，
                        // `_RETS` 读 getStr 得 "" —— 与原版 ReturnF(longTerm) 后消费侧
                        // GetStrValue→"" 同口径，SuperUserDefinedMethodTerm.cs:33-38）。
                        val t = s.value?.let { evalTemp(it, s.line) }
                        val src = t?.let { local(it) } ?: SlotAddr.NONE
                        if (sig.returnsStr) {
                            // #FUNCTIONS：字符串返回通道（合同 M3-A §3 A7，_RETS=201）。
                            out.emit(Opcode._RETS, src, 0, 0, s.line)
                        } else {
                            out.emit(Opcode._RET, src, 0, 0, s.line)
                        }
                        if (t != null) free(t)
                    } else {
                        marker(s)   // RETURNFORM：FORM 串展开归语句层（契约 C3 明确入池）
                    }
                // PRINT 家族**全量**入池（含纯字面量 PRINTL "x"）：文字流出口只有一条
                //（契约 C3/C6：ExecContext.console 是唯一出口，_PRINT_LITERAL 不再是编译器产物）
                is PrintStmt -> marker(s)
                is InstructionStmt -> compilePlainInstruction(s)
            }
        }

        // ---- 标签 ----------------------------------------------------------

        private fun defineLabel(s: GotoLabelStmt) {
            val key = s.name.uppercase()
            if (key in labels) {
                // 原版同函数重复 $标签 = LabelDictionary.AddLabelDollar 的 TryAdd：**首个生效**、
                // 后续仅 level-2 警告、装载照常完成（LabelDictionary.cs:342-350 + ErbLoader.cs:463-468；
                // 查找面 GetLabelDollar 返回字典里那一条 = 首个，:380-385），GOTO 一律落第一处。
                // 此前这里抛 CompileException，会被 CorpusCompile 的兜底换成 0 参空体桩 ——
                // 语料实证可达：情事発覚.ERB @AFFAIR_DISCLOSURE 的 $INPUT_EMBRACE（:67/:210）双定义
                // ⇒ 催眠情事事件整链静默失效（2026-10-26 fixcore 审查 A1）。
                return
            }
            labels[key] = out.here()
            // 只记本遍表。升格进 CodeBuilder（FunctionInfo.labelNames/labelPcs，
            // = _JUMP_DYN 下标与 ProcessRuntime.LabelTable 的 pc 来源）由
            // [compileFunction] 在**正式发射成功、函数表条目建立之后**统一做（CMP-26：
            // 失败不得在 CodeBuilder 里留下半个函数/半张标签表）；探测遍的 pc 属临时 builder。
        }

        // ---- IF 家族 --------------------------------------------------------

        private fun compileIf(s: IfStmt) {
            val c = evalTemp(s.condition, s.line)
            val fix = emitJumpIfFalse(local(c), s.line)
            free(c)
            scopes.add(IfScope().apply { pending!!.add(fix) })
        }

        /** SIF：条件为假跳过紧随的一条语句（主循环负责补跳）。spec: isa.yaml SIF。 */
        private fun compileSif(s: IfStmt) {
            val c = evalTemp(s.condition, s.line)
            sifFixup = emitJumpIfFalse(local(c), s.line)
            free(c)
        }

        private fun compileElseIf(s: ElseIfStmt) {
            val sc = topScope<IfScope>("ELSEIF", s.line)
            sc.endifJumps.add(emitJumpAlways(s.line))       // 上一分支体尾 → ENDIF 之后
            patch(sc.pending ?: emptyList(), out.here())
            sc.pending?.clear()
            // ELSE 之后再出现 ELSE/ELSEIF：原版只 Warn(InvalidElseAfterElse) 并**照常**把
            // 该行加进 IfCaseList（spec: ErbLoader.cs:1219-1223）；运行期 IF 依序求值各 case，
            // 遇到 ELSE 即 break（spec: FunctionIdentifier.cs:3123-3127）——所以 ELSE 之后的
            // 分支是死代码，但装载/编译**不得失败**。移植层没有装载期告警通道，这里静默继续；
            // 旧实现把 pending 置 null 后 `error("unreachable")` 抛 IllegalStateException，
            // 会让整段函数编译中断（审计 finding 1）。
            val pending = sc.pending ?: ArrayList<Fixup>().also { sc.pending = it }
            val c = evalTemp(s.condition, s.line)
            pending.add(emitJumpIfFalse(local(c), s.line))
            free(c)
        }

        private fun compileElse(s: ElseStmt) {
            val sc = topScope<IfScope>("ELSE", s.line)
            sc.endifJumps.add(emitJumpAlways(s.line))        // IF/ELSEIF 体尾 → ENDIF 之后（否则落入 ELSE 体）
            patch(sc.pending ?: emptyList(), out.here())
            sc.pending = null                                // ELSE 体无条件执行，无失败链
        }

        private fun compileEndIf(s: EndIfStmt) {
            val sc = popScope<IfScope>("ENDIF", s.line)
            patch(sc.pending ?: emptyList(), out.here())
            patch(sc.endifJumps, out.here())
        }

        // ---- TRYC 家族（契约 R3：TRYC 头在 compileJumpLike 发射并压栈，这里配对收尾） ----

        /**
         * TRYC 块开启 + 缺失跳转登记（契约 R3）。
         *
         * 原版装载期对六种 TRYC 语句**一律**压栈开块（ErbLoader.cs:1169-1178），CATCH 要求栈顶
         * 是这六者之一并与之配对（ErbLoader.cs:1339-1348），因此每种 TRYC 都自带
         * CATCH…ENDCATCH，不再有"区内转移/绑外层"的区分。
         *
         * missJump：目标缺失时要跳到 catch 体首的 `_JUMP`（动态 TRYC 与静态 CALL 系缺失）；
         * 常量 GOTO 缺失（TRYCGOTO）原版直接跳过、不跳 catch（FunctionIdentifier.cs:3685-3693），
         * 传 null。
         */
        private fun bindTrycMiss(code: FunctionCode, line: Int, missJump: Fixup?) {
            if (code !in TRYC_CODES) {
                throw CompileException(sig.name, line, "$code 不是 TRYC 块语句")
            }
            val sc = TrycScope()
            if (missJump != null) sc.missJumps.add(missJump)
            scopes.add(sc)
        }

        /**
         * CATCH 行（双重角色 = ELSE）：try 体尾无条件跳 ENDCATCH 之后
         * （spec: FunctionIdentifier.cs:3274-3285 —— CATCH 自身无条件跳 EndCatch）；
         * 并把 TRYC 的"缺失跳转"回填到 **catch 体首 pc**（R1 口径：CATCH 头行本身
         * 产生的唯一指令就是这条跳越跳转，跳到它之后 = 从 CATCH 的下一行继续执行）。
         */
        private fun compileCatch(s: CatchStmt) {
            val sc = topScope<TrycScope>("CATCH", s.line)
            if (sc.inCatch) {
                throw CompileException(sig.name, s.line, "CATCH 重复（TRYC 块缺少 ENDCATCH）")
            }
            sc.endJumps.add(emitJumpAlways(s.line))
            patch(sc.missJumps, out.here())
            sc.missJumps.clear()
            sc.inCatch = true
        }

        private fun compileEndCatch(s: EndCatchStmt) {
            val sc = popScope<TrycScope>("ENDCATCH", s.line)
            if (!sc.inCatch) {
                throw CompileException(sig.name, s.line, "ENDCATCH 缺少 CATCH")
            }
            patch(sc.endJumps, out.here())
        }

        // ---- SELECTCASE -----------------------------------------------------

        private fun compileSelect(s: SelectCaseStmt) {
            val isStr = s.target.operandType == OperandType.STR
            val t = alloc()
            evalExprTo(s.target, local(t), s.line)
            scopes.add(SelectScope(t, isStr))
        }

        /**
         * CASE 行：本行内**每个 caseExp 逐个试配**，任一命中即进体；全部失配才继续
         * 下一个 CASE/CASEELSE/ENDSELECT（spec: FunctionIdentifier.cs:3185-3193 —
         * `for (int i = 0; i < caseExps.Length; i++) if (caseExps[i].GetBool(...)) { line = ifCase; break; }`，
         * 未命中则 `continue` 到下一个 ifCase；字符串分支 :3196-3208 同构）。
         *
         * 因此失配链分两层：**本 CASE 内**各 caseExp 之间用局部回填（[Fixup] 落在下一个
         * caseExp 的比较起点），只有**最后一个** caseExp 的失配才进 [SelectScope.failJumps]
         * （跨 CASE 回填到下一 CASE 的比较起点 / CASEELSE 体首 / ENDSELECT 之后）。
         * 旧实现把所有 caseExp 的失配都塞进 failJumps ⇒ 首个失配直接跳到下一个 CASE，
         * `CASE 1, 2` 的第二值起全是死代码（等价 `CASE 1`，TO/IS 同样）。
         */
        private fun compileCase(s: CaseStmt) {
            // CMP-31：CASE 零条件 = 原版装载期错误行（spec: ErbLoader.cs:1309-1312
            // `caseExps.Length == 0` → `Warn(MissingArg, isError: true)`），运行期 SELECTCASE
            // 跳过 IsError 的 ifCase（:3169-3172），于是该 CASE 恒不命中；旧实现不校验，
            // 失配链会直接落进它的体（恒命中）。本侧装载单元是函数，按既有口径升级为
            // CompileException。装载器解析期已先拦一次（`ArgBuilders.case`），本条守容器面。
            if (s.cases.isEmpty()) {
                throw CompileException(
                    sig.name, s.line,
                    "CASE 缺少条件（spec: ErbLoader.cs:1309-1312 MissingArg）",
                )
            }
            val sc = topScope<SelectScope>("CASE", s.line)
            if (sc.bodyOpen) sc.exitJumps.add(emitJumpAlways(s.line))
            sc.bodyOpen = false
            patch(sc.failJumps, out.here())                  // 上一 CASE 失配 → 本 CASE 比较起点
            sc.failJumps.clear()
            for ((i, ce) in s.cases.withIndex()) {
                // 本 caseExp 自己的失配跳转（TO 有下界/上界两处）
                val fails = ArrayList<Fixup>()
                when (ce.caseType) {
                    CaseExpression.CaseType.NORMAL -> compileCaseNormal(sc, ce, s.line, fails)
                    CaseExpression.CaseType.TO -> compileCaseTo(sc, ce, s.line, fails)
                    CaseExpression.CaseType.IS -> compileCaseIs(sc, ce, s.line, fails)
                }
                if (i == s.cases.lastIndex) {
                    sc.failJumps.addAll(fails)               // 全部失配 → 下一 CASE / 出口
                } else {
                    patch(fails, out.here())                 // 本值失配 → 继续试下一个值
                }
            }
            patch(sc.hitJumps, out.here())                   // 命中 → 体首
            sc.hitJumps.clear()
            sc.bodyOpen = true
        }

        /** @param fails 本 caseExp 的失配跳转出口（由 [compileCase] 决定回填到下一个值还是下一 CASE）。 */
        private fun compileCaseNormal(sc: SelectScope, ce: CaseExpression, line: Int, fails: MutableList<Fixup>) {
            val v = ce.leftTerm ?: return
            if (!sc.isString) {
                val vt = evalTemp(v, line)
                val c = alloc()
                out.emit(Opcode._EQ, local(c), local(sc.targetSlot), local(vt), line)
                free(vt)
                sc.hitJumps.add(emitJumpIfTrue(local(c), line))
                fails.add(emitJumpIfFalse(local(c), line))   // 失配 → 本 CASE 的下一个值 / 下一 CASE
                free(c)
            } else {
                // 原版 SELECTCASE 在块头对目标表达式**只求值一次**（spec: FunctionIdentifier.cs:3156-3165
                // 先取 term 再按 IsInteger 分流，字符串值 text 在进入 ifCase 循环前已定）；
                // compileSelect 已把目标求值进 [SelectScope.targetSlot]，字符串比较经
                // [selectTargetRead] 哨兵读该槽 —— 不得内嵌 targetExpr（那会对每个 caseExp
                // 重新求值目标、副作用方法被反复触发）。
                val c = evalTemp(stringCompare(OperatorCode.EQUAL, selectTargetRead(sc, line), v, line), line)
                sc.hitJumps.add(emitJumpIfTrue(local(c), line))
                fails.add(emitJumpIfFalse(local(c), line))
                free(c)
            }
        }

        /** @param fails 见 [compileCaseNormal]。 */
        private fun compileCaseTo(sc: SelectScope, ce: CaseExpression, line: Int, fails: MutableList<Fixup>) {
            val l = ce.leftTerm
            val r = ce.rightTerm
            if (l == null || r == null) throw CompileException(sig.name, line, "CASE TO 缺少端点")
            if (!sc.isString) {
                val lt = evalTemp(l, line)
                val c1 = alloc()
                out.emit(Opcode._GE, local(c1), local(sc.targetSlot), local(lt), line)
                free(lt)
                // 下界失配 → 下一个值（上界不求值：原版 CaseExpression.cs:47-54 先判下界、
                // 只有下界成立才求右端点）
                fails.add(emitJumpIfFalse(local(c1), line))
                free(c1)
                val rt = evalTemp(r, line)
                val c2 = alloc()
                out.emit(Opcode._LE, local(c2), local(sc.targetSlot), local(rt), line)
                free(rt)
                sc.hitJumps.add(emitJumpIfTrue(local(c2), line))
                // 上界失配必须显式跳过本 CASE：命中跳转之后紧邻的就是 CASE 体，
                // 不发射这条 false 分支时 `CASE 1 TO 500` 会变成「只判下界」。
                fails.add(emitJumpIfFalse(local(c2), line))
                free(c2)
            } else {
                // CORE-01：字符串 TO **不走**运算符重载。原版 CaseExpression.cs:62-70 是一段独立的
                // 区间判定（`compare(lo, Is) <= 0` 成立才判 `compare(Is, hi) <= 0`），而运算符版的
                // 字符串 `>=`/`<=` 带两处官方 bug（Expr.kt strCompare / specs/verify-notes.md §1：
                // `>=` 完全反向、`<=` 相等泄漏）。旧实现把这两个运算符接进区间判定 ⇒ 命中集合
                // 整体取反（`CASE "a" TO "c"` 配 "b" 永不命中，`CASE "m" TO "z"` 配 "a" 反命中）。
                // bug 本体按 ADR-005 原样保留，区间判定改走 [strCaseRange]（`!(a > b)` 表达 `<=`，
                // 短路保序与原版一致）。
                // 目标只求值一次（见 [compileCaseNormal] 的字符串分支说明）。
                val c = evalTemp(strCaseRange(selectTargetRead(sc, line), l, r, line), line)
                sc.hitJumps.add(emitJumpIfTrue(local(c), line))
                fails.add(emitJumpIfFalse(local(c), line))
                free(c)
            }
        }

        /** @param fails 见 [compileCaseNormal]。 */
        private fun compileCaseIs(sc: SelectScope, ce: CaseExpression, line: Int, fails: MutableList<Fixup>) {
            // spec: CaseExpression.cs —— IS 形态的值在 leftTerm，比较符在 operator
            val v = ce.leftTerm ?: throw CompileException(sig.name, line, "CASE IS 缺少比较值")
            val op = ce.operator
            if (!sc.isString) {
                val cmp = when (op) {
                    OperatorCode.EQUAL -> Opcode._EQ
                    OperatorCode.NOT_EQUAL -> Opcode._NE
                    OperatorCode.LESS -> Opcode._LT
                    OperatorCode.LESS_EQUAL -> Opcode._LE
                    OperatorCode.GREATER -> Opcode._GT
                    OperatorCode.GREATER_EQUAL -> Opcode._GE
                    else -> throw CompileException(sig.name, line, "CASE IS 不支持运算符 $op")
                }
                val vt = evalTemp(v, line)
                val c = alloc()
                out.emit(cmp, local(c), local(sc.targetSlot), local(vt), line)
                free(vt)
                sc.hitJumps.add(emitJumpIfTrue(local(c), line))
                fails.add(emitJumpIfFalse(local(c), line))   // 失配 → 本 CASE 的下一个值 / 下一 CASE
                free(c)
            } else {
                val c = evalTemp(stringCompare(op, selectTargetRead(sc, line), v, line), line)
                sc.hitJumps.add(emitJumpIfTrue(local(c), line))
                fails.add(emitJumpIfFalse(local(c), line))
                free(c)
            }
        }

        /**
         * 字符串 CASE 的目标读取节点 —— 零参哨兵 `#EXPR_RETS#<targetSlot>`
         * （[EXPR_STR_TEMP_PREFIX] 约定，ProcessRuntime.evalContext.exprSentinelValue 读
         * 调用方帧局部槽的字符串值）。槽由 [compileSelect] 在块头求值一次写入、
         * [compileEndSelect] 释放 —— 与原版「字符串目标进入 ifCase 循环前已定」同口径
         * （spec: FunctionIdentifier.cs:3156-3165）。
         */
        private fun selectTargetRead(sc: SelectScope, line: Int): Expr =
            FuncCall(EXPR_STR_TEMP_PREFIX + sc.targetSlot, emptyList(), OperandType.STR, line)

        /** 字符串比较经表达式池：Binary(cmp, target, value)（比较结果是 INT）。 */
        private fun stringCompare(op: OperatorCode, target: Expr, value: Expr, line: Int): Expr =
            Binary(op, target, value, OperandType.INT, line)

        private fun compileCaseElse(s: CaseElseStmt) {
            val sc = topScope<SelectScope>("CASEELSE", s.line)
            if (sc.bodyOpen) sc.exitJumps.add(emitJumpAlways(s.line))
            sc.bodyOpen = false
            patch(sc.failJumps, out.here())                  // 全部未命中 → ELSE 体
            sc.failJumps.clear()
            sc.bodyOpen = true
        }

        private fun compileEndSelect(s: EndSelectStmt) {
            val sc = popScope<SelectScope>("ENDSELECT", s.line)
            if (sc.bodyOpen) sc.exitJumps.add(emitJumpAlways(s.line))
            patch(sc.failJumps, out.here())                  // 无 CASEELSE 且全部未命中 → 出口
            sc.failJumps.clear()
            patch(sc.exitJumps, out.here())
            free(sc.targetSlot)
        }

        // ---- 循环 -----------------------------------------------------------

        /**
         * FOR / REPEAT（REPEAT 已被改写成 `FOR COUNT, 0, n, 1` 形态，ArgBuilders.kt:241）。
         *
         * 分工（契约 C3）：**循环结构**（入口判定 / 出口 / 回边）留字节码；循环变量的
         * **初始化**与**每步 +step** 走语句池（合成 `AssignStmt`，语义 = `SET dest, +term`）。
         * 具名变量一律不进 VM 槽（契约 C1）：读经 `_EVAL_INT` 求值 `VarRef`（→ EvalContext
         * → VariableStore），写经语句池（→ sem `execAssign`，spec: StatementExecutor.kt:1211-1223）。
         *
         * 原版语义（ADR-005 原样复刻，全部带行号）：
         * - `InstructionLine.JumpTo` 的约定是"跳到该行、主循环先 ShiftNextLine 再执行"
         *   （spec: Process.cs:1011-1016 `runScriptProc` 先 `state.ShiftNextLine()`；
         *   ProcessState.cs:123-133 `ShiftNextLine`/`JumpTo`），即 `JumpTo(X)` = **从 X 的下一行继续**。
         * - FOR 行（spec: FunctionIdentifier.cs:2999-3010 `REPEAT_Instruction(fornext:true)`，
         *   NEXT/REND 回边跳回 FOR 行**不会**重新初始化，因为 `JumpTo(FOR 行)` 是从其下一行继续）：
         *   `LoopCounter.SetValue(Start)`（:3003）/ `LoopEnd = End`（:3004）/ `LoopStep = Step`（:3005）
         *   各求值**一次**；入口判定 `if ((step<=0 || end<=cnt) && (step>=0 || end>=cnt)) 出循环`
         *   （:3006）⇒ 零步长无条件出体。
         * - NEXT/REND（spec: FunctionIdentifier.cs:3393-3407）：先 `cnt += step`（:3401），
         *   再 `(step>0 && end>cnt) || (step<0 && end<cnt)` 决定回边（:3403）；
         *   CONTINUE 同一条判定（:3340-3349）。
         *   即**原版边界是严格的**（`FOR A, 0, 2` 跑 A=0、1 两轮），且原版 REPEAT 改写处
         *   **不**减一（spec: ArgumentParser.cs:1223-1228 →
         *   `new SpForNextArgment(var, SingleLongTerm(0), aExpression, SingleLongTerm(1))`）。
         *   **R4 定稿（CONTRACT-M2-E2E §5）**：本实现已改为排他判定 `_LT`/`_GT`，与
         *   ArgBuilders 的 REPEAT `n-1` 补偿**同时**撤销（该补偿只是含端口径下的补丁）。
         * - BREAK（spec: FunctionIdentifier.cs:3310-3319）：**跳出前给循环变量加一步 LoopStep**
         *   （:3312-3317，母块是 WHILE/DO 时不加）——原版的可见怪行为，循环外的 `A` 比
         *   "没进循环"时大一个步长；本处同样经语句池发一条步进语句再跳出（ADR-005 复刻）。
         *
         * CORE-03 收口（2026-10-23）：终值与步长**都**只在 FOR 行求值一次，各自折进帧内
         * 匿名临时槽并由循环持有（[LoopScope.holdSlots]，逐轮读同一槽）——与原版
         * `LoopEnd`/`LoopStep` 两个 long 快照同口径（spec: FunctionIdentifier.cs:3004-3005）。
         * 旧实现只有常量步长等价：非常量步长把原表达式留在步进语句里、并把求值点放在**回边
         * 目标之后**，于是每轮 NEXT 与入口判定各重求一次（体内改写步长变量、或步长带副作用时
         * 与原版读数不同）。步进语句因此改读快照槽（返回值哨兵 [EXPR_TEMP_PREFIX]，与
         * [compileMethod] 写 RESULT 同一形态），非常量步长的循环体从此看不到步长的二次求值。
         *
         * 非常量步长的入口判定按**运行期**符号/零值分派（原版 :3006 的成形判定
         * `(step<=0 || end<=cnt) && (step>=0 || end>=cnt)`）：零步长直接出体，正步长
         * `cnt < end`（`_LT`），负步长 `cnt > end`（`_GT`）。回边跳回同一判定区，但读的是
         * FOR 行的步长快照槽（不是重求表达式）⇒ 分派结果与原版一样在整个循环期内固定
         * （原版 NEXT 侧 :3403 读的同样是 `instructionLine.LoopStep` 快照）。
         */
        private fun compileFor(s: ForStmt) {
            val fa = s.args as? SpForNextArgument
                ?: throw CompileException(sig.name, s.line, "FOR 参数形态异常")
            val cnt = fa.cnt
            val start = fa.start ?: IntLiteral(0, s.line)
            val step = fa.step ?: IntLiteral(1, s.line)
            // 初始化：`cnt = start` 入池（spec: FunctionIdentifier.cs:3003）
            marker(synthAssign(cnt, OperatorCode.ASSIGNMENT, start, s.line, "${s.raw} [FOR INIT]"))
            // 终值：求到帧内**匿名**临时槽并由循环持有（spec: FunctionIdentifier.cs:3004 一次求值）
            val endT = evalTemp(fa.end ?: IntLiteral(0, s.line), s.line)
            // 步长形态判定：常量折成字面量（原版 :3005 的快照在编译期已知，步进语句直接走
            // 常量增量快路径）；非常量在下一行求值进快照槽。
            // "FOR A, 2, 0, -1" 的 -1 解析为 Unary(MINUS, 1)。
            val stepConst = when {
                step is IntLiteral -> step.value
                step is Unary && step.op == OperatorCode.MINUS && step.operand is IntLiteral ->
                    -(step.operand as IntLiteral).value
                else -> null
            }
            // CORE-03：非常量步长同样**只在 FOR 行求值一次**（spec: FunctionIdentifier.cs:3005
            // `func.LoopStep = spForNextArgment.Step.GetIntValue(exm)`；NEXT :3401 / CONTINUE :3340 /
            // BREAK :3316 读的都是这个 long 快照）。求值点必须落在**回边目标 headPc 之前**，
            // 并由循环持有该槽（[LoopScope.holdSlots]），否则每轮重求 = 每轮重取步长。
            val stepSlot: Int? = if (stepConst == null) evalTemp(step, s.line) else null
            // 步进语句的右值：常量走 `addConst` 快路径（同 ArgumentParser.cs:1073-1090 的
            // 常量增量形态，勿改回 term + op）；非常量读 FOR 行的步长快照槽
            // （[EXPR_TEMP_PREFIX] 哨兵，形态与 [compileMethod] 写 RESULT 的那条一致）。
            val stepTerm: Expr = if (stepConst != null) {
                IntLiteral(stepConst, s.line)
            } else {
                FuncCall(EXPR_TEMP_PREFIX + checkNotNull(stepSlot), emptyList(), OperandType.INT, s.line)
            }
            val stepStmt = synthAssign(cnt, OperatorCode.PLUS, stepTerm, s.line, "${s.raw} [FOR STEP]")
            // 非常量步长的零值判定基准：常量 0 留在持有槽里，循环期内不得被临时分配复用
            //（[LoopScope.holdSlots]）；只在非常量路径分配。
            val zeroT = if (stepConst == null) evalTemp(IntLiteral(0, s.line), s.line) else null
            val headPc = out.here()
            val exits = ArrayList<Fixup>()
            if (stepConst == 0L) {
                // 零步长：原版无条件出体（spec: FunctionIdentifier.cs:3006 —— 两个析取同时为真）；
                // 且计数器永不前进，判定（无论排他与否）都会死循环，故这里直接发一条无条件跳转。
                exits.add(emitJumpAlways(s.line))
            } else if (stepConst != null) {
                // 契约 R4：**排他界**（原版续循环 `end > cnt` / `end < cnt`，counter 已在 NEXT 步进，
                // spec: FunctionIdentifier.cs:3401-3403；入口判定 `end <= cnt` 即出体，:3006）。
                // 正步长 → cnt < end（_LT）；负步长 → cnt > end（_GT）。含等号的旧口径（_LE/_GE）
                // 会让 FOR A,0,5 多跑一轮，已废。
                val cmpOp = if (stepConst < 0) Opcode._GT else Opcode._LT
                val cntT = evalTemp(cnt, s.line)          // _EVAL_INT 读 VariableStore
                val c = alloc()
                out.emit(cmpOp, local(c), local(cntT), local(endT), s.line)
                free(cntT)
                exits.add(emitJumpIfFalse(local(c), s.line))
                free(c)
            } else {
                // 非常量步长（审计 finding 2）：符号/零值**运行期**才知道，但读的是 FOR 行的
                // 步长快照槽（[stepSlot]，CORE-03）而不是重求步长表达式。
                //   step == 0        → 出体（原版 :3006 两个析取同时为真 ⇒ 直接跳过循环体）
                //   step >  0        → 续循环当且仅当 cnt < end（_LT）
                //   step <  0        → 续循环当且仅当 cnt > end（_GT）
                // 回边（NEXT）跳回 headPc 重新分派：快照不变 ⇒ 分派结果逐轮一致，
                // 与原版 NEXT 侧读 `instructionLine.LoopStep`（spec: FunctionIdentifier.cs:3403
                // `(step>0 && end>cnt) || (step<0 && end<cnt)`）同口径。
                val zero = zeroT!!
                val stepT = checkNotNull(stepSlot)
                val cZero = alloc()
                out.emit(Opcode._EQ, local(cZero), local(stepT), local(zero), s.line)
                exits.add(emitJumpIfTrue(local(cZero), s.line))
                free(cZero)
                val cPos = alloc()
                out.emit(Opcode._GT, local(cPos), local(stepT), local(zero), s.line)
                val toNeg = emitJumpIfFalse(local(cPos), s.line)   // 非正且非零 ⇒ 负
                free(cPos)
                // 正步长：cnt < end
                val cntPos = evalTemp(cnt, s.line)
                val cPosCmp = alloc()
                out.emit(Opcode._LT, local(cPosCmp), local(cntPos), local(endT), s.line)
                free(cntPos)
                exits.add(emitJumpIfFalse(local(cPosCmp), s.line))
                free(cPosCmp)
                val toDone = emitJumpAlways(s.line)
                // 负步长：cnt > end
                patch(listOf(toNeg), out.here())
                val cntNeg = evalTemp(cnt, s.line)
                val cNegCmp = alloc()
                out.emit(Opcode._GT, local(cNegCmp), local(cntNeg), local(endT), s.line)
                free(cntNeg)
                exits.add(emitJumpIfFalse(local(cNegCmp), s.line))
                free(cNegCmp)
                patch(listOf(toDone), out.here())
                // stepT 不在此归还：它是 FOR 行的步长快照，存活期 = 整个循环，
                // 由 NEXT/REND 处随 [LoopScope.holdSlots] 统一释放（CORE-03）。
            }
            scopes.add(
                LoopScope(
                    headPc, LoopScope.Kind.FOR, exits,
                    stepStmt = stepStmt, cnt = cnt,
                    holdSlots = listOfNotNull(endT, stepSlot, zeroT),
                ),
            )
        }

        private fun compileNext(s: NextStmt) {
            val sc = popLoop("NEXT", s.line)
            val contPc = out.here()
            patch(sc.contJumps, contPc)
            sc.stepStmt?.let { marker(it) }      // 步进经语句池（契约 C1/C3）
            backJump(sc.headPc, s.line)
            val exitPc = out.here()
            patch(sc.breakJumps, exitPc)
            patch(sc.exitJumps, exitPc)
            for (h in sc.holdSlots) free(h)
            // CMP-33：循环闭合处刷新函数级锚点（spec: ErbLoader.cs:1335 对 NEXT/WEND/LOOP 同样
            // 把弹出的循环头赋给循环外的 `instructionLine`）。
            staleLoop = StaleLoopAnchor(exitPc, contPc, sc.stepStmt)
        }

        private fun compileWhile(s: WhileStmt) {
            val headPc = out.here()
            val c = evalTemp(s.condition, s.line)
            val exit = emitJumpIfFalse(local(c), s.line)
            free(c)
            scopes.add(LoopScope(headPc, LoopScope.Kind.WHILE, listOf(exit)))
        }

        private fun compileWend(s: WendStmt) {
            val sc = popLoop("WEND", s.line)
            patch(sc.contJumps, sc.headPc)                    // CONTINUE → 重新判条件
            backJump(sc.headPc, s.line)
            val exitPc = out.here()
            patch(sc.breakJumps, exitPc)
            patch(sc.exitJumps, exitPc)
            // CMP-33：WHILE 锚点（无步进 —— 原版 BREAK 只对 FOR/REPEAT 加 LoopStep）。
            staleLoop = StaleLoopAnchor(exitPc, sc.headPc, null)
        }

        private fun compileLoop(s: LoopStmt) {
            val sc = popLoop("LOOP", s.line)
            val contPc = out.here()
            patch(sc.contJumps, contPc)                       // CONTINUE → LOOP 条件判断处
            val cond = s.condition
            if (cond != null) {
                val c = evalTemp(cond, s.line)
                val pc = out.emit(Opcode._JUMP_IF_TRUE, local(c), 0, 0, s.line)
                out.patch(pc, 2, sc.headPc - (pc + 1))        // 回边
                free(c)
            } else {
                backJump(sc.headPc, s.line)
            }
            val exitPc = out.here()
            patch(sc.breakJumps, exitPc)
            patch(sc.exitJumps, exitPc)
            // CMP-33：DO/LOOP 锚点（无步进）。
            staleLoop = StaleLoopAnchor(exitPc, contPc, null)
        }

        private fun compileBreak(s: FlowStmt) {
            val sc = scopes.lastOrNull { it is LoopScope } as? LoopScope
            if (sc == null) {
                // CMP-33（ADR-005 复刻官方 bug）：块栈里没有循环时，原版沿用函数级
                // `instructionLine` 的**旧循环锚点**静默错跳（spec: ErbLoader.cs:1049 声明在
                // 扫描 while 之外、:1194 只在找到循环时赋值、:1198 只判 null）——
                // 只有该变量仍为 null（函数里此前从无循环闭合/从无 BREAK-CONTINUE 命中）时
                // 才 Warn(InvalidLoopInstruction, isError)，运行期到该行抛 CodeEE。
                val anchor = staleLoop
                if (anchor == null) { emitDeferredLoopError("BREAK", s.line); return }
                anchor.stepStmt?.let { marker(it) }   // FOR/REPEAT 锚点：跳出前补一步（:3312-3317）
                patch(listOf(emitJumpAlways(s.line)), anchor.breakPc)
                return
            }
            // 原版怪行为（ADR-005 原样复刻）：BREAK 跳出 FOR/REPEAT 前先把循环变量加一个
            // LoopStep（spec: FunctionIdentifier.cs:3312-3317 —— 仅当母块不是 WHILE/DO），
            // 所以 `FOR A,0,10 … BREAK … NEXT` 之后的 A 比"没进循环"时大一步长。
            // 写具名变量必须经语句池（契约 C1）；WHILE/DO 的 stepStmt 为 null → 不发步进。
            sc.stepStmt?.let { marker(it) }
            sc.breakJumps.add(emitJumpAlways(s.line))
        }

        private fun compileContinue(s: FlowStmt) {
            val sc = scopes.lastOrNull { it is LoopScope } as? LoopScope
            if (sc == null) {
                // CMP-33：同 BREAK —— 沿用旧循环锚点的 CONTINUE 回边目标（原版
                // CONTINUE_Instruction 读的 `func.JumpTo` 就是那个旧锚点，:1182-1205）。
                val anchor = staleLoop
                if (anchor == null) { emitDeferredLoopError("CONTINUE", s.line); return }
                patch(listOf(emitJumpAlways(s.line)), anchor.contPc)
                return
            }
            sc.contJumps.add(emitJumpAlways(s.line))
        }

        /**
         * 循环外的 BREAK/CONTINUE，**函数里先前没有任何循环锚点**时的形态：原版是
         * **行级错误**而非装载失败 —— 装载照常完成，只有执行到该行才抛 CodeEE
         * （spec: ErbLoader.cs:1198-1205 只 Warn(isError)；ParserMediator.cs:127-131 仅设置
         * line.IsError；Process.cs:1017-1019 执行时抛）。编译成一条 THROW marker 复刻该时序
         * （可被 TRYC 捕获，与原版一致）。
         *
         * CMP-33：同一函数里**已有**循环锚点时原版不报错、而是沿用旧锚点静默错跳
         * （ErbLoader.cs:1049 的 `instructionLine` 不重置）—— 那条走 [staleLoop]，
         * 只有 `staleLoop == null` 才落到这里。
         */
        private fun emitDeferredLoopError(what: String, line: Int) {
            val msg = "在REPEAT, FOR, WHILE, DO语法以外使用了\"$what\""
            marker(InstructionStmt(FunctionCode.THROW, ExpressionArgument(StrLiteral(msg)), line, what))
        }

        /**
         * 非 TRY 的**静态目标缺失**（常量 `GOTO $标签` 标签不存在 / 常量 `CALL`·`CALLF`
         * 函数不存在）：编一条 THROW marker 哨兵，复刻原版的**行级错误**时序。
         *
         * 原版装载期把这类缺失记为 IsError 行、装载照常完成，只有执行到该行才抛 CodeEE：
         * - 判据与置错：`GOTO_Instruction.SetJumpTo`（spec: FunctionIdentifier.cs:3664-3669）、
         *   `CALL_Instruction.SetJumpTo`（:3532-3535）、`CALLF_Instruction.SetJumpTo`
         *   （:1133-1143）→ ErbLoader 的 `FunctionoNotFoundName` 通道（:1615-1625）或直接
         *   `ParserMediator.Warn(..., isError: true)` → `line.IsError = true`（:937-955）；
         * - 运行期抛点：`Process.cs:1017-1019` 的 `currentLine.IsError → throw CodeEE`；
         *   同形兜底 `GOTO_Instruction.DoInstruction` :3703、`CALL_Instruction.DoInstruction`
         *   :3581、`CALLF_Instruction.DoInstruction` :1163。
         *
         * 本侧若在此直接抛 [CompileException]：GOTO 会让整个 @函数被外层兜底成空体桩
         * （CorpusCompile 的 `catch (CompileException)` 重编 `emptyList()`），CALL 走
         * `build()` 更会以「未定义的函数」炸掉整个 Program —— 两者都远超原版"单行不可用"。
         * 语料可达 2 处常量 GOTO 缺失：`COMF410 掃除.ERB:51` @COM410 的 `CANCEL_LOOP`、
         * `M_KOJO_K17_口上固有追加イベント.ERB:2396` @K17_ONABARE 的 `GOOUT`
         * —— 旧实现令这两个函数整体失效。
         *
         * 文案照抄官方 zhs（spec: lang.emuera-zhs.xml:520 / :540，即
         * `NotDefinedFunc` = 没有找到指定的函数"@名"、
         * `NotDefinedLabelName` = 当前函数中不存在指定的标签名"$名"）。
         * THROW 在语句层抛 CodeEE（StatementExecutor.kt:975-977），**可被 TRYC 捕获**，与原版一致。
         */
        private fun emitStaticTargetNotFound(msg: String, line: Int, rawCode: FunctionCode) {
            marker(InstructionStmt(FunctionCode.THROW, ExpressionArgument(StrLiteral(msg)), line, rawCode.name))
        }

        private fun backJump(targetPc: Int, line: Int) {
            val pc = out.emit(Opcode._JUMP, targetPc - (out.here() + 1), 0, 0, line)
            check(pc + 1 + (targetPc - (pc + 1)) == targetPc)
        }

        // ---- 调用 / 跳转 -----------------------------------------------------

        /**
         * CALL/JUMP/GOTO 及全部 FORM/TRY/TRYC 变体、表达式位降级调用的统一实参暂存。
         * 实参暂存：求值到临时槽（≥ tempBase），再 `_MOV_*` 进被调帧的形参桶 ——
         * **int 实参按出现序进 int 单元格 R0.. 、str 实参按出现序进 str 单元格 R0..**
         * （每槽 int/str 两套单元格同号，故两套桶可共用槽号）。
         *
         * 为什么按桶而不是按位置：被调侧绑定读的就是桶序 —— 普通 `_CALL` 走
         * `ProcessRuntime.bindArgs`（`ARG:i ← 槽 i 的 int 单元格`、`ARGS:j ← 槽 j 的 str 单元格`），
         * 重入/按名调用走 `rootBindOf` + `bindBuckets`（同样按类型分桶），`_CALL_BY_NAME` 的
         * `b` 也按"int/str 两套共用槽号、取 max"解释（Vm.kt callInto KDoc）。按位置写、按桶读
         * 只在实参类型不交错时侥幸等价；一旦交错（语料真实形态
         * `GETMETH_INT(0, "酒データ", LOCAL, "醸造数")` = int/str/int/str）就会整体错位读到脏槽。
         * 原版是按位置 + 逐位类型分派绑定（spec: UserDefinedFunctionArgument.cs:35-69 记
         * TransporterInt[i]/TransporterStr[i]，ProcessState.cs:435-452 按 `Arg[i]` 的位置写回），
         * 与"两侧都按类型分桶"在实参类型与形参类型一致时**等价**；不一致（原写法 0/""）与
         * null 实参占位（原版跳过该形参不写，本侧沿既有口径记入 int 桶）两处差异登记为
         * TODO(verify)。
         *
         * [maxArgc] 仍按**实参总数**推进：它是临时槽起点（tempBase），桶号恒 < 总数 ⇒ 桶区
         * 与临时槽区不重叠。
         *
         * 返回 [StagedArgs]：`argc` 给 `_CALL`，`args` 是该调用位逐位实参元数据（[CallArg]）：
         * 位置 / 类型桶号 / 裸变量名（若有）/ 是否省略位。裸 `VarRef` 记名 —— 原版 REF 形参位
         * 要求实参是 `VariableTerm`（spec: CalledFunction.cs:154），而 `Identifier.GetArray()`
         * **不看**该 term 的下标（:159 → ReferenceToken.SetRef :441），故 `X` 与 `X:1` 都算；
         * 真正的类型/维度校验在绑定点按被调形参做（`ReferenceToken.MatchType` :130-169）。
         *
         * **REF 位不求值**：`stageArgs` 不在编译期判定 REF 位（`FuncLabelStmt.paramRefs`
         * 只是形参项原文，**不含** REF 位信息；REF 来自被调 `#DIM REF` 的
         * `LocalDim.ref`，见 [LocalLayout.refParamNamesOf]），而是把每个**变量实参**
         * （`VarRef`）的值指令 pc 记进 [varArgSites]（仅「被调有 REF 形参」的调用点），
         * 等 `build()` 时按被调签名回填
         * （[patchRefArgEvals]）—— 调用方先于被调方编译（`SHOW_PANTIES.ERB:90` 早于
         * `:100`）也不受影响。非 REF 位不动：裸多维数组按值传参仍在原处抛「需要 N 个下标」。
         */
        private fun stageArgs(rowArgs: List<Expr?>, line: Int, target: String? = null): StagedArgs {
            val row = padOmittedArgs(target, rowArgs)
            // 只有「被调有 REF 形参」的调用点才需要记值指令 pc（省内存/省 Pair 分配）；
            // 其余调用点走普通 evalTemp，行为与旧实现逐字一致。
            val recordRef = target != null && hasRefParams(target)
            val temps = IntArray(row.size)
            val evalPcs = IntArray(row.size) { -1 }
            for ((i, a) in row.withIndex()) {
                temps[i] = if (a == null) {
                    val t = alloc()
                    out.emit(Opcode._SET_INT, local(t), out.constant(0L), 0, line)
                    t
                } else if (a is VarRef && recordRef) {
                    // REF 形参位的实参不求值（原版只取 Identifier.GetArray()）：记下值指令 pc，
                    // build() 时按被调 REF 位回填成常量（见 [VarArgSite] / [patchRefArgEvals]）。
                    val (t, pc) = evalTempPc(a, line)
                    evalPcs[i] = pc
                    t
                } else {
                    evalTemp(a, line)
                }
            }
            if (recordRef) {
                for (i in row.indices) {
                    if (evalPcs[i] < 0) continue
                    varArgSites.add(VarArgSite(target!!, i, evalPcs[i], row[i]!!.operandType == OperandType.STR))
                }
            }
            var intBucket = 0
            var strBucket = 0
            val meta = ArrayList<CallArg>(row.size)
            for (i in temps.indices) {
                val t = temps[i]
                val isStr = row[i] != null && row[i]!!.operandType == OperandType.STR
                val bucket = if (isStr) strBucket++ else intBucket++
                if (t != bucket) {
                    out.emit(
                        if (isStr) Opcode._MOV_STR else Opcode._MOV_INT,
                        SlotAddr.local(bucket), local(t), 0, line,
                    )
                }
                free(t)
                val e = row[i]
                meta.add(
                    CallArg(
                        position = i,
                        bucket = bucket,
                        isStr = isStr,
                        name = (e as? VarRef)?.name,
                        present = e != null,
                    ),
                )
            }
            if (row.size > maxArgc) maxArgc = row.size
            return StagedArgs(temps.size, meta)
        }

        /**
         * 缺省实参补位（spec: CalledFunction.cs:128-202 `ConvertArg`）。
         *
         * 原版在**调用点**把被省略的实参换成被调 `TopLabel.Def[i]`（:167）后再统一求值
         * （`UserDefinedFunctionArgument.cs:35-69`），于是被调侧 `Arguments[i]` 非 null、
         * `IntoFunction` 照常按位写 `ARG`/`ARGS`（spec: ProcessState.cs:435-452）。
         * 本侧在编译期做同一件事：把实参表补到「最后一个有默认值的形参位」，
         * 无默认值的位置**保持 null** —— 运行期 `bindCallArgs` 对 `present=false` 的位
         * 不写，仍是原版 `Arguments[i] == null` 的行为（:437，`:168-172` 的
         * CanNotOmitArg 本侧不新增报错，维持既有宽松口径）。
         *
         * 只补**编译期能定名**的调用点：静态 `CALL`/`JUMP`/`TRYCALL` 家族、`CALLF` 常量名、
         * TRY*LIST 的 `FUNC` 常量名、表达式位用户方法调用（严格求值位在 [stageArgs] 补；
         * 惰性求值位在 [padRuntimeCalls] 把默认值顶替进 `FuncCall.args`）。
         * `CALLFORM` 家族与 TRYCCALLFORM 等的名字是运行期表达式，本遍拿不到被调定义 ⇒ 不补
         * （TODO(verify)：与运行期 `bindCallArgs` 同源的表要进 `FuncMeta`/侧车，属容器面）。
         * `exprCalls == null` 的旧装置只补「**没有同名内建**」的名字（见 [padRuntimeCalls]）：
         * 有内建同名时无法区分用户方法与内建，补位会改内建实参形状。
         */
        private fun padOmittedArgs(target: String?, rowArgs: List<Expr?>): List<Expr?> {
            if (target == null) return rowArgs
            if (callDefaults.isEmpty()) return rowArgs
            val defs = callDefaults[target.uppercase()] ?: return rowArgs
            var lastDefault = -1
            for (i in defs.indices) if (defs[i] != null) lastDefault = i
            if (lastDefault < 0) return rowArgs
            val size = maxOf(rowArgs.size, lastDefault + 1)
            if (size == rowArgs.size && rowArgs.none { it == null }) return rowArgs
            val out = ArrayList<Expr?>(size)
            for (i in 0 until size) {
                val given = if (i < rowArgs.size) rowArgs[i] else null
                out.add(given ?: defs.getOrNull(i))
            }
            return out
        }

        /**
         * `_CALL`：`b` = 实参数，**bit30 = JUMP（尾跳）标志**（契约 R10/API 3），
         * **bit28 = 字符串返回标志**（合同 M3-A §3 A7，`#FUNCTIONS` 调用置位）。
         * bit30=1 时 VM 走 `jumpInto`：不加帧深、不改 retPc/retDst，返回直达调用者的调用者
         * （spec: ProcessState.cs:289-311）；否则 `callInto` 常规调用。
         */
        private fun emitCall(
            target: String,
            argc: Int,
            line: Int,
            retDst: Int = SlotAddr.NONE,
            isJump: Boolean = false,
            strRet: Boolean = false,
            callArgs: List<CallArg> = emptyList(),
        ) {
            var b = argc
            if (isJump) b = b or JUMP_BIT
            if (strRet) b = b or STR_RET_BIT
            val pc = out.emit(Opcode._CALL, 0, b, retDst, line)
            callRefs.add(CallRef(pc, target.uppercase(), line))
            out.declareCallArgs(pc, callArgs)
        }

        // ---- 表达式位用户方法调用的编译期降级（A15 任务 3；契约 C1/C3/C5/R6） --------------
        //
        // 原版语义（reference/emuera-cs 定稿，逐行）：
        // * 求值路径：表达式里的用户方法编译成 `UserDefinedMethodTerm`
        //   （spec: IdentifierDictionary.cs:561-564 → UserDefinedMethodTerm.cs:17-26，
        //   Create = CreateCalledFunctionMethod + ConvertArg，errMes 非空即 CodeEE）；
        //   运行期 `Process.GetValue` 进入被调帧并取方法返回值（spec: Process.cs:514-540，
        //   调用栈深 100 上限 :516-520 —— 我们由 VmConfig.maxCallDepth 等价守护）。
        // * 实参绑定：调用方**升序**求值全部实参（spec: UserDefinedFunctionArgument.cs:35-69，
        //   null 位 continue 不求值 :39-42）；被调帧**升序**写 ARG，null 位**跳过不写**
        //   （spec: ProcessState.cs:435-452，`Arguments[i] != null` 守卫在 :437）。
        //   我们经 `_CALL` 帧模型：null 位沿既有 stageArgs 口径置 0（默认值替换 Def[i]
        //   属符号层缺口，见 A15 报告），分桶绑定在 proc 层（ProcessRuntime.bindArgs）。
        // * **非方法 @label 在表达式位必须抛**（spec: IdentifierDictionary.cs:566-569，
        //   UsedNonMethodFunc"对为被标记为#FUNCTION的函数({0}:第{1}行)进行了行内函数形式的调用"，
        //   lang.emuera-zhs.xml）；同名内建存在时**静默走内建**（:566 的
        //   `!methodDic.ContainsKey(codeStr)` 判据 + :576-585 回落）。不许悄悄支持。
        // * 内建遮蔽优先级：用户 #FUNCTION **覆盖**同名内建（:548-565 先查 labelDic）。
        // * 返回值通道：`#FUNCTION`（int）经 `_CALL` 的 c 槽（`_RET`/leaveFrame 写回，
        //   spec: Vm.kt:438-446/570-580）。`#FUNCTIONS`（str）走 `_RETS` 字符串通道
        //   （合同 M3-A §3 A7：调用位 b bit28 = STR_RET_FLAG，VM `_RETS`=201）；
        //   消费侧对非字符串统一得 `""`/`0`（spec: SuperUserDefinedMethodTerm.cs:26-38），
        //   故编译期类型不符（int 表达式 RETURNF 进 #FUNCTIONS）不报错、运行期同口径容错。
        //   **类型未知**的方法（methodTypes 缺声明）不降级（ExprCallKind.METHOD_UNKNOWN，
        //   保持节点 → 运行期按被调声明走 [com.eratw.port.core.proc.ProcessRuntime.invokeUserMethod]
        //   重入通道；消费通道与被调声明错配时明确抛"返回了非整数/非字符串结果"，
        //   不静默读错通道）。

        /** 归还 [pendingRets] 中索引 >= [mark] 的新增返回槽。 */
        private fun drainRetTemps(mark: Int) {
            while (pendingRets.size > mark) free(pendingRets.removeAt(pendingRets.size - 1))
        }

        /**
         * 递归降级表达式树中的 `FuncCall`（表为 null 时只做缺省补位、不降级不校验 = 旧行为）。
         * **遍历顺序 = [com.eratw.port.core.expr.Expr] 求值器的实际求值顺序**（前导指令
         * 必须按原版求值序发射）：`&&`/`||` 与 `!&`/`!|`（NAND/NOR）右侧、三目分支、
         * `\@a?b#c\@` 分支是**短路位**，本遍**不**降级 —— 把可能不执行的调用提到无条件前导
         * = 伪造副作用（原版 `&&`/`||` 确实短路，spec: OperatorMethodManager.cs:383-409；
         * NAND/NOR 见同文件 `:439-446` / `:457-464`；本侧 `Expr.kt:257-263` / `:283-290`
         * 同口径）。这类调用点保持 `FuncCall` 原节点，运行期由
         * `ProcessRuntime.evalContext` 的**重入被调帧**通道在真正走到的一刻执行
         * （spec: Process.cs:514-540 `GetValue(SuperUserDefinedMethodTerm)`；
         * [com.eratw.port.core.proc.ProcessRuntime.invokeUserMethod] 与 GETMETH/GETMETHS 同一条通道）；
         * 但缺省实参补位不依赖「是否执行」，要在节点上做（[padRuntimeCalls]，CMP-27）。
         * DIV/MOD 先右后左、STR 语境 MULT 的左右序与 Expr.kt:240-294 对齐。
         */
        private fun lowerExpr(e: Expr): Expr = when (e) {
            is FuncCall -> lowerFuncCall(e)
            is Binary -> lowerBinary(e)
            is Ternary -> {
                val c = lowerExpr(e.cond)
                // 两分支是短路位：不前移，但缺省实参补位要落到节点上（CMP-27）
                val t = padRuntimeCalls(e.ifTrue)
                val f = padRuntimeCalls(e.ifFalse)
                if (c === e.cond && t === e.ifTrue && f === e.ifFalse) e
                else e.copy(cond = c, ifTrue = t, ifFalse = f)
            }
            is Unary -> {
                val o = lowerExpr(e.operand)
                if (o === e.operand) e else e.copy(operand = o)
            }
            is KeywordIndexArg -> {
                val x = lowerExpr(e.expr)
                if (x === e.expr) e else e.copy(expr = x)
            }
            is VarRef -> lowerVarRef(e)
            is FormStringTerm -> lowerFormString(e)
            else -> e   // 字面量 / NullTerm / UnaryAfter（操作数必为 VarRef 自增位）
        }

        private fun lowerVarRef(v: VarRef): VarRef {
            var changed = false
            val idx = v.indices.map { l0 -> val l = lowerExpr(l0); if (l !== l0) changed = true; l }
            return if (changed) v.copy(indices = idx) else v
        }

        /**
         * 短路位：`&&`/`||` 与 `!&`/`!|`（NAND/NOR）的右侧、三目分支、`\@…?\@` 分支。
         *
         * 这四个二元算子的右侧在求值器里都**可能一次不执行**（`Expr.kt:257-263` 的
         * AND/OR、`:283-290` 的 NAND/NOR；C# 出处 `OperatorMethodManager.cs:383-409` /
         * `:439-446` / `:457-464`）⇒ 本遍不前移这些调用（伪造副作用），但**仍要做缺省实参
         * 补位**（[padRuntimeCalls]，CMP-27）：原版在**解析期**就把 `Def[i]` 顶替进实参表
         * （spec: CalledFunction.cs:137/:165-173，表达式位由 UserDefinedMethodTerm.Create
         * → ConvertArg 同路，spec: UserDefinedMethodTerm.cs:17-20），与调用是否真被执行无关。
         */
        private fun lowerBinary(e: Binary): Expr {
            if (e.op in SHORT_CIRCUIT_BINARY_OPS) {
                val l = lowerExpr(e.left)
                val r = padRuntimeCalls(e.right)
                return if (l === e.left && r === e.right) e else e.copy(left = l, right = r)
            }
            // 与 Binary.getIntValue/getStrValue 的实际求值序对齐（spec: Expr.kt:239-245, 279-292）
            val rightFirst = e.op == OperatorCode.DIV || e.op == OperatorCode.MOD ||
                (e.operandType == OperandType.STR && e.op == OperatorCode.MULT &&
                    e.left.operandType == OperandType.STR)
            if (rightFirst) {
                val r = lowerExpr(e.right)
                val l = lowerExpr(e.left)
                return if (r === e.right && l === e.left) e else e.copy(left = l, right = r)
            }
            val l = lowerExpr(e.left)
            val r = lowerExpr(e.right)
            return if (l === e.left && r === e.right) e else e.copy(left = l, right = r)
        }

        /**
         * **运行期解析调用点的缺省实参补位**（CMP-27）：把 `Def[i]` 顶替进 `FuncCall.args`，
         * 不发射任何指令（只改 AST）。
         *
         * ## 为什么需要
         *
         * 缺省补位此前只发生在 `stageArgs` → [padOmittedArgs]（编译期降级成功的调用点）。
         * 落在**惰性求值位**的调用（短路右位、三目分支、`\@…\@` 分支、返回类型未知的方法）
         * 保持 `FuncCall` 原节点、运行期由 [com.eratw.port.core.proc.ProcessRuntime.invokeUserMethod]
         * 重入被调帧 —— 那条路拿到的实参表就是节点上的 `args`，省略位不会补 ⇒ 被调形参
         * 保留旧值/装载期隐式 0（原版 `ProcessState.cs:437` 只跳过 null 位，而原版的 null 在
         * **解析期**就已被 `Def[i]` 顶替：spec: CalledFunction.cs:137/:165-173，
         * 表达式位同路：UserDefinedMethodTerm.cs:17-20 → ConvertArg）。
         *
         * ## 只在**确定会被用户函数消费**时才补
         *
         * 运行期求值语境按「哨兵 → 用户方法 → 内建」分派（`ProcessRuntime.kt:407-411`
         * `isUnloweredUserMethod`）。给同名**内建**补参数会改内建调用形状，故：
         * * `exprCalls` 在位：只补 `METHOD_INT`/`METHOD_STR`/`METHOD_UNKNOWN` 三类
         *   （分类口径 = `IdentifierDictionary.cs:548-586`，用户方法遮蔽同名内建）；
         * * `exprCalls` 缺席（旧装置，不降级不校验）：只补「**没有同名内建**、且
         *   `callDefaults` 有该名」的调用 —— 这类名字在运行期只可能是用户方法，
         *   补位不会落到内建表上。
         *
         * 非调用结点只递归（可能含嵌套调用）；不补位的结点原样返回（身份不变）。
         */
        private fun padRuntimeCalls(e: Expr): Expr = when (e) {
            is FuncCall -> {
                var changed = false
                val args = e.args.map { a ->
                    if (a == null) null else {
                        val p = padRuntimeCalls(a)
                        if (p !== a) changed = true
                        p
                    }
                }
                val table = exprCalls
                val userConsumed = if (table != null) {
                    when (table.classify(e.name)) {
                        ExprCallKind.METHOD_INT,
                        ExprCallKind.METHOD_STR,
                        ExprCallKind.METHOD_UNKNOWN,
                        -> true
                        else -> false
                    }
                } else {
                    // 表缺席：同名内建存在时补位会改内建实参形状 ⇒ 只认无内建同名的名字。
                    Builtins.registry.returnType(e.name) == null &&
                        callDefaults.containsKey(e.name.uppercase())
                }
                val base = if (changed) e.copy(args = args) else e
                if (!userConsumed) {
                    base
                } else {
                    val padded = padOmittedArgs(e.name, args)
                    if (padded === args) base else base.copy(args = padded)
                }
            }
            is Binary -> {
                val l = padRuntimeCalls(e.left)
                val r = padRuntimeCalls(e.right)
                if (l === e.left && r === e.right) e else e.copy(left = l, right = r)
            }
            is Ternary -> {
                val c = padRuntimeCalls(e.cond)
                val t = padRuntimeCalls(e.ifTrue)
                val f = padRuntimeCalls(e.ifFalse)
                if (c === e.cond && t === e.ifTrue && f === e.ifFalse) e
                else e.copy(cond = c, ifTrue = t, ifFalse = f)
            }
            is Unary -> {
                val o = padRuntimeCalls(e.operand)
                if (o === e.operand) e else e.copy(operand = o)
            }
            is UnaryAfter -> {
                val o = padRuntimeCalls(e.operand)
                if (o === e.operand) e else e.copy(operand = o)
            }
            is KeywordIndexArg -> {
                val x = padRuntimeCalls(e.expr)
                if (x === e.expr) e else e.copy(expr = x)
            }
            is VarRef -> {
                var changed = false
                val idx = e.indices.map { l0 ->
                    val l = padRuntimeCalls(l0)
                    if (l !== l0) changed = true
                    l
                }
                if (changed) e.copy(indices = idx) else e
            }
            is FormStringTerm -> {
                var changed = false
                val parts = e.parts.map { p ->
                    when (p) {
                        is FormPart.Text -> p
                        is FormPart.Embedded -> {
                            val ast = padRuntimeCalls(p.ast)
                            val c0 = p.conversion
                            val c1 = when (c0) {
                                is FormConversion.Curly -> {
                                    val w = c0.width
                                    if (w == null) c0 else {
                                        val x = padRuntimeCalls(w)
                                        if (x === w) c0 else { changed = true; c0.copy(width = x) }
                                    }
                                }
                                is FormConversion.Percent -> {
                                    val w = c0.width
                                    if (w == null) c0 else {
                                        val x = padRuntimeCalls(w)
                                        if (x === w) c0 else { changed = true; c0.copy(width = x) }
                                    }
                                }
                                is FormConversion.YenAt -> {
                                    val t = padRuntimeCalls(c0.ifTrue)
                                    val f = padRuntimeCalls(c0.ifFalse)
                                    if (t === c0.ifTrue && f === c0.ifFalse) c0
                                    else { changed = true; FormConversion.YenAt(t, f) }
                                }
                            }
                            if (ast === p.ast && c1 === c0) p else {
                                changed = true
                                FormPart.Embedded(ast, c1)
                            }
                        }
                    }
                }
                if (changed) e.copy(parts = parts) else e
            }
            else -> e
        }

        /**
         * FORM 串按部件顺序降级（spec: FormStringTerm.getStrValue / StrForm.cs:400-414
         * 顺序求值）：Embedded.ast 严格求值 → 其转换的 width 随后；`\@?#\@` 的两分支是
         * 条件位不降级。
         */
        private fun lowerFormString(e: FormStringTerm): Expr {
            var changed = false
            val parts = e.parts.map { p ->
                when (p) {
                    is FormPart.Text -> p
                    is FormPart.Embedded -> {
                        val ast = lowerExpr(p.ast)
                        val conv = when (val c = p.conversion) {
                            is FormConversion.Curly -> {
                                val w = c.width
                                if (w == null) c else { val l = lowerExpr(w); if (l !== w) changed = true; if (l === w) c else c.copy(width = l) }
                            }
                            is FormConversion.Percent -> {
                                val w = c.width
                                if (w == null) c else { val l = lowerExpr(w); if (l !== w) changed = true; if (l === w) c else c.copy(width = l) }
                            }
                            is FormConversion.YenAt -> {
                                // 条件位：分支不降级（不前移），但缺省实参补位落到节点上（CMP-27）
                                val t = padRuntimeCalls(c.ifTrue)
                                val f = padRuntimeCalls(c.ifFalse)
                                if (t === c.ifTrue && f === c.ifFalse) c else {
                                    changed = true
                                    FormConversion.YenAt(t, f)
                                }
                            }
                        }
                        if (ast === p.ast && conv === p.conversion) p
                        else { changed = true; FormPart.Embedded(ast, conv) }
                    }
                }
            }
            return if (changed) e.copy(parts = parts) else e
        }

        private fun lowerFuncCall(fc: FuncCall): Expr {
            val table = exprCalls ?: return padRuntimeCalls(fc)
            val kind = table.classify(fc.name)
            if (kind == ExprCallKind.NON_METHOD_LABEL) {
                // spec: IdentifierDictionary.cs:566-569（UsedNonMethodFunc）—— 复刻，不许悄悄支持。
                throw CompileException(
                    sig.name, fc.line,
                    "对为被标记为#FUNCTION的函数(${fc.name})进行了行内函数形式的调用" +
                        "（UsedNonMethodFunc 复刻；spec: IdentifierDictionary.cs:566-569）",
                )
            }
            // 实参**升序**降级（内层调用前导先发射；spec: UserDefinedFunctionArgument.cs:35-69）
            var argsChanged = false
            val args = fc.args.map { a ->
                if (a == null) null else { val l = lowerExpr(a); if (l !== a) argsChanged = true; l }
            }
            if (kind == ExprCallKind.METHOD_STR && fc.operandType == OperandType.STR) {
                // ---- #FUNCTIONS（str）方法：实参求值 + _CALL(STR_RET bit) + 字符串哨兵（A7）----
                // 表达式位与语句位走同一条补位逻辑（缺省实参 = 被调 Def[i]，CalledFunction.cs:167）
                val staged = stageArgs(args, fc.line, fc.name)   // 升序求值到临时槽→_MOV 进 R0..；maxArgc 更新
                val ret = alloc()                     // 调用方帧的匿名返回临时槽（契约 C1）
                emitCall(fc.name, staged.argc, fc.line, local(ret), strRet = true, callArgs = staged.args)
                pendingRets += ret
                return FuncCall(EXPR_STR_TEMP_PREFIX + ret, emptyList(), OperandType.STR, fc.line, fc.col)
            }
            if (kind != ExprCallKind.METHOD_INT || fc.operandType != OperandType.INT) {
                // BUILTIN/UNKNOWN：保持节点（运行期内建桥，A4）。
                // METHOD_UNKNOWN（返回类型未声明的方法）：保持节点 → 运行期按被调帧声明走
                // 重入通道（ProcessRuntime.evalContext ②），int 通道取帧返回值；
                // 不猜测 str 通道（误判会静默读错值），见上方注释块末段。
                // 保持节点的三种形态在运行期都可能落到用户方法重入（CMP-27）：缺省实参补位
                // 必须在**节点**上做（bounded by classify = METHOD_*；内建/未知名不补）。
                val kept = if (argsChanged) fc.copy(args = args) else fc
                return padRuntimeCalls(kept)
            }
            // ---- #FUNCTION（int）方法：实参求值 + _CALL + 返回哨兵 ----
            val staged = stageArgs(args, fc.line, fc.name)   // 升序求值到临时槽→_MOV 进 R0..；maxArgc 更新
            val ret = alloc()                     // 调用方帧的匿名返回临时槽（契约 C1）
            emitCall(fc.name, staged.argc, fc.line, local(ret), callArgs = staged.args)
            pendingRets += ret
            return FuncCall(EXPR_TEMP_PREFIX + ret, emptyList(), OperandType.INT, fc.line, fc.col)
        }

        /**
         * 语句池 marker 的入池前降级：赋值右值/下标、PRINT 系词项（真实语料最高频的
         * `{METHOD()}` 形态），以及**其余全部 marker 入池的指令行参数位**
         * （`HTML_PRINT` / `BAR` / `SAVEVAR` / `THROW` / `CALLSHARP` … 见
         * [lowerArgument]）——原版的表达式位调用不区分宿主语句，凡严格求值位都要在解析期
         * 绑成求值节点（spec: IdentifierDictionary.cs:548-586）。
         * **无调用的语句实例原样入池**（契约 C2 按身份追加）；数据节家族
         * （[PrintDataStmt] / [DataStmt] / [DataListStmt]）与无法保真重建子类的语句形态
         * **整条豁免**（宁可不降级、由运行期重入通道按原版求值点执行，也不伪造副作用或破坏身份链）。
         * 返回槽由 [marker] 在 marker 指令发射后统一 drain。
         */
        private fun lowerStatementForPool(s: Statement): Statement = when (s) {
            is AssignStmt -> {
                val dest2 = lowerVarRef(s.dest)
                val args2 = when (val a = s.args) {
                    is SpSetArgument -> {
                        val t = a.term?.let(::lowerExpr)
                        if (t === a.term && dest2 === s.dest) a
                        else SpSetArgument(dest2, t).apply {
                            addConst = a.addConst
                            // 常量增量在解析期折成 `IsConst=true / ConstInt=±N / Term=null`
                            // （spec: ArgumentParser.cs:1004-1015；`-=` 的负号已带上，:1011），
                            // 执行期按 `IsConst ? ConstInt : Term` 取值（FunctionIdentifier.cs:647-658）。
                            // 重建副本漏抄这份可变缓存 ⇒ `.erbc` 池里 `CNT += 1` 退化成 `CNT = 0`。
                            isConst = a.isConst
                            constStr = a.constStr
                            constInt = a.constInt
                        }
                    }
                    is SpSetArrayArgument -> {
                        var ch = false
                        val ts = a.terms.map { x -> if (x == null) null else { val l = lowerExpr(x); if (l !== x) ch = true; l } }
                        if (!ch && dest2 === s.dest) a else SpSetArrayArgument(dest2, ts, a.constIntList, a.constStrList)
                    }
                    else -> a
                }
                if (dest2 === s.dest && args2 === s.args) s else AssignStmt(dest2, s.op, args2, s.line, s.raw)
            }
            is PrintStmt -> {
                val a2 = when (val a = s.args) {
                    is ExpressionArgument -> {
                        val t = a.term?.let(::lowerExpr)
                        if (t === a.term) a else ExpressionArgument(t)
                    }
                    is ExpressionArrayArgument -> {
                        var ch = false
                        val ts = a.terms.map { x -> if (x == null) null else { val l = lowerExpr(x); if (l !== x) ch = true; l } }
                        if (ch) ExpressionArrayArgument(ts) else a
                    }
                    is ExpressionsArgument -> {
                        var ch = false
                        val ts = a.terms.map { x -> if (x == null) null else { val l = lowerExpr(x); if (l !== x) ch = true; l } }
                        if (ch) ExpressionsArgument(a.types, ts) else a
                    }
                    else -> a
                }
                if (a2 === s.args) s else PrintStmt(s.print, a2, s.line, s.raw)
            }
            // 契约 R10/API 4：RETURN 已降为 marker（不再发 `_RET`），但语句层要求值 RESULT，
            // 表达式位的用户方法调用必须先降级 —— 否则 `#EXPR_RET#` 哨兵在 sem 求值时不可达。
            is ReturnStmt -> {
                var ch = false
                val vs = s.values.map { x ->
                    if (x == null) null else { val l = lowerExpr(x); if (l !== x) ch = true; l }
                }
                if (ch) ReturnStmt(vs, s.args, s.line, s.raw) else s
            }
            // RETURNFORM 同理（RETURNF 仍编 `_RET`，其表达式在编译期已求值，不必重写）。
            is ReturnFStmt -> {
                val v0 = s.value
                if (s.code == FunctionCode.RETURNFORM && v0 != null) {
                    val v = lowerExpr(v0)
                    if (v === v0) s else ReturnFStmt(v, s.args, s.line, s.raw, s.code)
                } else {
                    s
                }
            }
            // 通用指令行（HTML_PRINT / PRINT_BUTTON / BAR / SPLIT / THROW / SAVEVAR … 全部
            // 走这条）：参数层的表达式位调用降级。本分支必须排在 PrintDataStmt / DataStmt /
            // DataListStmt 的豁免之后（见 [lowerPoolInstruction]）。
            is InstructionStmt -> lowerPoolInstruction(s)
            else -> s
        }

        /**
         * [lowerStatementForPool] 的通用指令行分支。
         *
         * 三条纪律：
         * 1. **先判可重建、再降级**。[lowerExpr] 会发射前导 `_CALL`（副作用已落地），
         *    若事后才发现语句类型不可原样重建而丢弃副本，就会留下"多调用一次"的伪造副作用。
         *    故类型不在 [poolInstructionRebuilder] 覆盖内时**整条原样返回、不碰表达式**。
         * 2. **数据节家族豁免**（[PrintDataStmt] / [DataStmt] / [DataListStmt]）：与
         *    [ExprCanonicalizer.canonicalizeStatement] 同一身份链契约（数据节以 Statement
         *    实例为键，宿主在 AST 上注册、VM 从池内取指回查），重建即断链。
         * 3. **子类身份保真**：语句身份决定 sem 分派（StatementExecutor 的
         *    `is VarDeclStmt` 等分支），所以重建一律回到**同一个具体类**，不把子类压成
         *    父类 [InstructionStmt] 空壳。
         *
         * 引用形参位不受影响：[lowerExpr] 只替换 `FuncCall` 节点，`VarRef` 原样透传，
         * 故 CALLSHARP 的"变量实参回写"位仍指向原变量（specs/isa.yaml 的 CALLSHARP 段，
         * spec: FunctionIdentifier.cs:1169-1235）。
         */
        private fun lowerPoolInstruction(s: InstructionStmt): Statement {
            if (s is PrintDataStmt || s is DataStmt || s is DataListStmt) return s
            val rebuild = poolInstructionRebuilder(s) ?: return s
            val args = lowerArgument(s.args)
            return if (args === s.args) s else rebuild(args)
        }

        /**
         * 语句 marker 的参数层降级：逐形态把参数内所有表达式位 `FuncCall` 换成
         * 「前导 `_CALL` + 返回哨兵读槽」。返回**原实例**表示无需改动（契约 C2 恒新开
         * 池条目，但不做无谓重建）。
         *
         * 不降的形态（各有理由，非遗漏）：
         * - [SpCallArgument] / [SpCallFArgument]：CALL/JUMP/CALLF 家族不进 marker，
         *   其实参由 [stageArgs] 升序求值（内部走 [evalTemp] → [lowerExpr]，已降级）；
         *   在此再降一遍会**双重发射**。
         * - [SpForNextArgument]：FOR/REPEAT 的循环参数同上，由 [compileFor] 求值。
         * - [CaseExpression] 集合（CASE）：由 [compileCase] 逐位求值。
         * - 纯 [VarRef] / 名字 / 数值形态（SWAP / SORTCHARA / VARSIZE / TIMES / REF /
         *   PRINTDATA 的 DEST …）：无表达式位可降。
         */
        private fun lowerArgument(a: Argument): Argument = when (a) {
            is ExpressionArgument -> {
                val t = a.term?.let(::lowerExpr)
                if (t === a.term) a else ExpressionArgument(t)
            }
            is ExpressionArrayArgument -> {
                val ts = lowerExprList(a.terms)
                if (ts === a.terms) a else ExpressionArrayArgument(ts)
            }
            is ExpressionsArgument -> {
                val ts = lowerExprList(a.terms)
                if (ts === a.terms) a else ExpressionsArgument(a.types, ts)
            }
            is SpSetArgument -> {
                val t = a.term?.let(::lowerExpr)
                if (t === a.term) a else SpSetArgument(a.dest, t).apply {
                    addConst = a.addConst
                    // 同 [lowerStatementForPool]：常量折态（spec: ArgumentParser.cs:1004-1015）
                    // 必须随重建副本迁移，否则 `X += N` 在语句池里丢增量语义。
                    isConst = a.isConst
                    constStr = a.constStr
                    constInt = a.constInt
                }
            }
            is SpSetArrayArgument -> {
                val ts = lowerExprList(a.terms)
                if (ts === a.terms) a else SpSetArrayArgument(a.dest, ts, a.constIntList, a.constStrList)
            }
            // HTML_PRINT / PLAYSOUND（spec: SpHtmlPrint.cs:6-16）—— 本波首差形态
            is SpHtmlPrint -> {
                val str = a.str?.let(::lowerExpr)
                val opt = a.opt?.let(::lowerExpr)
                if (str === a.str && opt === a.opt) a else SpHtmlPrint(str, opt)
            }
            is HtmlPrintArgument -> {
                val t = a.term?.let(::lowerExpr)
                val e = a.lineEnd?.let(::lowerExpr)
                if (t === a.term && e === a.lineEnd) a else HtmlPrintArgument(t, e)
            }
            is SpButtonArgument -> {
                val p = a.printStrTerm?.let(::lowerExpr)
                val b = a.buttonWord?.let(::lowerExpr)
                if (p === a.printStrTerm && b === a.buttonWord) a else SpButtonArgument(p, b)
            }
            is SpBarArgument -> {
                val v = a.value?.let(::lowerExpr)
                val m = a.max?.let(::lowerExpr)
                val l = a.length?.let(::lowerExpr)
                if (v === a.value && m === a.max && l === a.length) a else SpBarArgument(v, m, l)
            }
            is SpInputsArgument -> {
                val d = a.def?.let(::lowerExpr)
                val mo = a.mouse?.let(::lowerExpr)
                val cs = a.canSkip?.let(::lowerExpr)
                if (d === a.def && mo === a.mouse && cs === a.canSkip) a else SpInputsArgument(d, mo, cs)
            }
            is SpTInputsArgument -> {
                val t = a.time?.let(::lowerExpr)
                val d = a.def?.let(::lowerExpr)
                val dp = a.disp?.let(::lowerExpr)
                val to = a.timeout?.let(::lowerExpr)
                val mo = a.mouse?.let(::lowerExpr)
                val cs = a.canSkip?.let(::lowerExpr)
                if (t === a.time && d === a.def && dp === a.disp && to === a.timeout && mo === a.mouse && cs === a.canSkip) {
                    a
                } else {
                    SpTInputsArgument(t, d, dp, to, mo, cs)
                }
            }
            is SpPowerArgument -> {
                val x = a.x?.let(::lowerExpr)
                val y = a.y?.let(::lowerExpr)
                if (x === a.x && y === a.y) a else SpPowerArgument(a.dest, x, y)
            }
            is SpSaveDataArgument -> {
                val tg = a.target?.let(::lowerExpr)
                val sx = a.strExpression?.let(::lowerExpr)
                if (tg === a.target && sx === a.strExpression) a else SpSaveDataArgument(tg, sx)
            }
            is SpSaveVarArgument -> {
                val t = a.term?.let(::lowerExpr)
                val m = a.savMes?.let(::lowerExpr)
                if (t === a.term && m === a.savMes) a else SpSaveVarArgument(t, m, a.varNames)
            }
            is SpVarSetArgument -> {
                val t = a.term?.let(::lowerExpr)
                val st = a.start?.let(::lowerExpr)
                val en = a.end?.let(::lowerExpr)
                if (t === a.term && st === a.start && en === a.end) a else SpVarSetArgument(a.dest, t, st, en)
            }
            is SpCVarSetArgument -> {
                val i = a.index?.let(::lowerExpr)
                val t = a.term?.let(::lowerExpr)
                val st = a.start?.let(::lowerExpr)
                val en = a.end?.let(::lowerExpr)
                if (i === a.index && t === a.term && st === a.start && en === a.end) a else
                    SpCVarSetArgument(a.dest, i, t, st, en)
            }
            is SpArrayControlArgument -> {
                val n1 = a.num1?.let(::lowerExpr)
                val n2 = a.num2?.let(::lowerExpr)
                if (n1 === a.num1 && n2 === a.num2) a else SpArrayControlArgument(a.dest, n1, n2)
            }
            is SpArrayShiftArgument -> {
                val n1 = a.num1?.let(::lowerExpr)
                val n2 = a.num2?.let(::lowerExpr)
                val n3 = a.num3?.let(::lowerExpr)
                val n4 = a.num4?.let(::lowerExpr)
                if (n1 === a.num1 && n2 === a.num2 && n3 === a.num3 && n4 === a.num4) a else
                    SpArrayShiftArgument(a.dest, n1, n2, n3, n4)
            }
            is SpArraySortArgument -> {
                val n1 = a.num1?.let(::lowerExpr)
                val n2 = a.num2?.let(::lowerExpr)
                if (n1 === a.num1 && n2 === a.num2) a else SpArraySortArgument(a.dest, a.order, n1, n2)
            }
            is SpSplitArgument -> {
                val ts = a.targetStr?.let(::lowerExpr)
                val sp = a.split?.let(::lowerExpr)
                if (ts === a.targetStr && sp === a.split) a else SpSplitArgument(ts, sp, a.destVarName, a.num)
            }
            is SpHtmlSplitArgument -> {
                val ts = a.targetStr?.let(::lowerExpr)
                if (ts === a.targetStr) a else SpHtmlSplitArgument(ts, a.destVarName, a.num)
            }
            is SpCopyArrayArgument -> {
                val v1 = a.varName1?.let(::lowerExpr)
                val v2 = a.varName2?.let(::lowerExpr)
                if (v1 === a.varName1 && v2 === a.varName2) a else SpCopyArrayArgument(v1, v2)
            }
            is SpSwapCharaArgument -> {
                val x = a.x?.let(::lowerExpr)
                val y = a.y?.let(::lowerExpr)
                if (x === a.x && y === a.y) a else SpSwapCharaArgument(x, y)
            }
            is SpPrintImgArgument -> {
                val n = a.name?.let(::lowerExpr)
                val nb = a.nameb?.let(::lowerExpr)
                val nm = a.namem?.let(::lowerExpr)
                val pm = a.param?.let(::lowerMixedIntList)
                if (n === a.name && nb === a.nameb && nm === a.namem && pm === a.param) a else
                    SpPrintImgArgument(n, nb, nm, pm)
            }
            is SpPrintShapeArgument -> {
                val pm = lowerMixedIntList(a.param)
                if (pm === a.param) a else SpPrintShapeArgument(pm)
            }
            is SpPrintVArgument -> {
                var ch = false
                val ts = a.terms.map { x -> val l = lowerExpr(x); if (l !== x) ch = true; l }
                if (!ch) a else SpPrintVArgument(ts)
            }
            is SpDtColumnOptions -> {
                val dt = a.dt?.let(::lowerExpr)
                val col = a.column?.let(::lowerExpr)
                val vs = lowerExprList(a.values)
                if (dt === a.dt && col === a.column && vs === a.values) a else SpDtColumnOptions(dt, col, a.options, vs)
            }
            is BitArgument -> {
                var ch = false
                val ts = a.terms.map { x -> val l = lowerExpr(x); if (l !== x) ch = true; l }
                if (!ch) a else BitArgument(a.dest, ts)
            }
            is StrDoubleArgument -> {
                val t = a.term?.let(::lowerExpr)
                if (t === a.term) a else StrDoubleArgument(t, a.doubleValue)
            }
            // 行首方法名调用的实参位（MethodStmt 走 compileMethod，不进 marker；
            // 保留分支只为语义完整，降级后仍由语句层求值）
            is MethodArgument -> {
                val t = a.methodTerm?.let(::lowerExpr)
                if (t === a.methodTerm) a else MethodArgument(t)
            }
            // CALL/JUMP/CALLF/FOR/CASE 家族：由各自的编译路径求值（见 KDoc）
            is SpCallArgument, is SpCallFArgument, is SpForNextArgument -> a
            // CALLSHARP：只降实参位，函数名表达式是**插件方法名**、不是值实参
            // （specs/isa.yaml CALLSHARP：spec: FunctionIdentifier.cs:1169-1235）
            is SpCallSharpArgument -> {
                val rs = lowerExprList(a.rowArgs)
                if (rs === a.rowArgs) a else SpCallSharpArgument(a.funcNameTerm, a.subNames, rs)
            }
            // 其余形态无可降表达式（VOID / 具名变量 / 名字表 / 常量列表 / REF / PRINTDATA…）
            else -> a
        }

        /** PRINT_IMG / PRINT_RECT 的带 px 标记整数位；无变化时**原列表实例**返回。 */
        private fun lowerMixedIntList(
            terms: List<MixedIntegerExprTerm>,
        ): List<MixedIntegerExprTerm> {
            var changed = false
            val res = terms.map { m ->
                val n = m.num?.let(::lowerExpr)
                if (n === m.num) m else { changed = true; MixedIntegerExprTerm(n, m.isPx) }
            }
            return if (changed) res else terms
        }

        /** 可空表达式列表；**全未变时返回原列表实例**（调用方以身份判定是否重建）。 */
        private fun lowerExprList(terms: List<Expr?>): List<Expr?> {
            var changed = false
            val res = terms.map { x -> if (x == null) null else { val l = lowerExpr(x); if (l !== x) changed = true; l } }
            return if (changed) res else terms
        }

        /**
         * marker 可达的 [InstructionStmt] 子类 → 同类型重建函数；null = 本遍不降级
         * （宁可不降、保持运行期明确红，也不肯把子类压成父类空壳而改变 sem 分派）。
         * 键 = 实际会落到 [marker] 的具体类（控制流类由各自路径处理，不在此列）。
         */
        private fun poolInstructionRebuilder(s: InstructionStmt): ((Argument) -> Statement)? = when {
            s::class == InstructionStmt::class -> { a: Argument -> InstructionStmt(s.code, a, s.line, s.raw) }
            s is PrintStmt -> { a: Argument -> PrintStmt(s.print, a, s.line, s.raw) }
            s is VarDeclStmt -> { a: Argument -> VarDeclStmt(s.code, a, s.line, s.raw) }
            s is FlowStmt -> { a: Argument -> FlowStmt(s.code, a, s.line, s.raw) }
            s is CallSharpStmt -> { a: Argument -> CallSharpStmt(a, s.line, s.raw) }
            // 语句位**内建**方法（compileMethod 对 BuiltinSymbols 走 marker）：
            // 其 MethodArgument 里嵌套的用户方法调用同样需要降级
            s is MethodStmt -> { a: Argument -> MethodStmt(s.methodName, a, s.line, s.raw) }
            s is ForStmt -> { a: Argument -> ForStmt(s.code, a, s.line, s.raw) }
            s is RepeatStmt -> { a: Argument -> RepeatStmt(a, s.line, s.raw) }
            s is NextStmt -> { a: Argument -> NextStmt(a, s.line, s.raw, s.code) }
            s is RendStmt -> { a: Argument -> RendStmt(a, s.line, s.raw) }
            s is WendStmt -> { a: Argument -> WendStmt(a, s.line, s.raw) }
            s is DoStmt -> { a: Argument -> DoStmt(a, s.line, s.raw) }
            s is ElseStmt -> { a: Argument -> ElseStmt(a, s.line, s.raw) }
            s is EndIfStmt -> { a: Argument -> EndIfStmt(a, s.line, s.raw) }
            s is CaseElseStmt -> { a: Argument -> CaseElseStmt(a, s.line, s.raw) }
            s is EndSelectStmt -> { a: Argument -> EndSelectStmt(a, s.line, s.raw) }
            s is EndBlockStmt -> { a: Argument -> EndBlockStmt(s.code, a, s.line, s.raw) }
            s is ReturnFStmt -> { a: Argument -> ReturnFStmt(s.value, a, s.line, s.raw, s.code) }
            else -> null
        }

        /**
         * `_CALL_BY_NAME`（契约 C5）：`a` = 名字槽（帧内字符串临时，已由 `_EVAL_STR` 写好）、
         * `b` = 实参数（**帧内实参区槽数** = `max(ni, ns)`；本编译器的实参按位置占 R0..R(argc-1)，
         * int/str 两套数组同标号，故 argc = 实参总数）且 **bit31 = try 标志**、**bit30 = JUMP（尾跳）标志**、
         * **bit29 = CALLF（CALLF 家族运行期方法解析）标志**、`c` = 返回槽。
         * 命中且 bit30=1 → VM 走 `jumpInto`（契约 R10/API 3）。
         *
         * try 位语义（VM 已实现，spec: Vm.kt 的 `_CALL_BY_NAME` 分支）：
         * 未命中 + try=1 → `pc++` 静默跳过本行；未命中 + try=0 → CodeEE
         * （spec: FunctionIdentifier.cs:3577-3588，3581 抛错 / 3587 直接 return）。
         *
         * [callf]（契约 R11/API 5）：置 bit29，让 VM 走 `resolveMethodIndex`（只认用户
         * `#FUNCTION`/`#FUNCTIONS` 方法，spec: IdentifierDictionary.cs:538-586）；
         * 仅 `CALLF/TRYCALLF/CALLFORMF/TRYCALLFORMF` 的动态名调用置位，普通 CALLFORM 不置位。
         */
        private fun emitCallByName(
            nameTerm: Expr,
            argc: Int,
            line: Int,
            isTry: Boolean,
            isJump: Boolean = false,
            callf: Boolean = false,
            callArgs: List<CallArg> = emptyList(),
        ) {
            val nameT = evalTemp(nameTerm, line)
            var b = argc
            if (isTry) b = b or TRY_BIT
            if (isJump) b = b or JUMP_BIT
            if (callf) b = b or CALLF_BIT
            val pc = out.emit(Opcode._CALL_BY_NAME, local(nameT), b, SlotAddr.NONE, line)
            out.declareCallArgs(pc, callArgs)
            free(nameT)
        }

        /**
         * CALL/JUMP/GOTO 及全部 FORM/TRY/TRYC 变体的统一入口。
         *
         * [isJump]（契约 R10/API 4）：`JumpStmt` 家族（JUMP/JUMPFORM/TRYJUMP/TRYCJUMP…）= true，
         * `CallStmt` 家族 = false；只在**函数名路径**（FORM 与静态函数名）上置 `_CALL`/`_CALL_BY_NAME`
         * 的 bit30（VM 走 `jumpInto` 尾跳）。GOTO 系（标签）不受影响。
         *
         * **TRYC 家族（契约 R3）**：TRYC 语句自身 = try 块头，发射后压 [TrycScope]，
         * 由 compileCatch/compileEndCatch 配对收尾。静态名**编译期判名**：缺失直接发
         * `_JUMP → catch 体首 pc`；动态 TRYCCALLFORM/TRYCJUMPFORM 用契约 R3 的
         * `_CALL_BY_NAME c=hitFlag` + `_JUMP_IF_TRUE`/`_JUMP` 两步式（禁止新增 opcode）。
         */
        private fun compileJumpLike(code: FunctionCode, label: String?, args: com.eratw.port.core.stmt.Argument, line: Int, isJump: Boolean) {
            val rowArgs = (args as? SpCallArgument)?.rowArgs ?: emptyList()
            val isTry = code in TRY_CODES
            val isTryc = code in TRYC_CODES
            // 契约 R3 附带（TODO(verify)，归 A4/A5b）："CALL/JUMP 在 #FUNCTION 方法内一律 CodeEE"
            // 是**运行期**错误（spec: CalledFunction.cs:106-109，装载期合法、执行才抛）——
            // 编译期直接拒绝会误杀原版可装载的语料切片，故此处不 throw；由 sem/proc 层
            // 在执行 CALL/JUMP 语句时按当前帧 isMethod 抛 CodeEE。PARTIAL(16) 方法体/SIF 后
            // 禁用同理属语句层（spec: EmueraConsole.cs:2216-2219、:3049-3052）。
            when {
                // ---- 静态 $标签（GOTO / TRYGOTO / TRYCGOTO）：函数内绝对跳转 ----
                label != null && code in STATIC_LABEL_CODES -> {
                    // GOTO 实参可能是 "$TAIL"（词法原样）；$标签行 的 name 已被 lexer 剥过 $，
                    // 这里统一归一化后对表
                    val labelKey = label.uppercase().trimStart('$')
                    // 静态目标已知性判定（spec: FunctionIdentifier.cs:3655-3679 SetJumpTo：标签查不到时，
                    // 非 TRY 才报错；TRY 系运行期 `JumpTo == null → return` 即跳过本行，
                    // spec: :3688-3691）。GOTO_Instruction 的常量路径在 runtime **先**做
                    // `JumpTo == null → return`，早于 JumpToEndCatch 判断（:3685-3693），因此
                    // TRYCGOTO 的**常量**标签缺失同样是"跳过"，不跳 catch —— PC 原版基准
                    // fixtures/golden/baseline/pc-golden-root.txt:362 的 NEVER-AFTER-TRYCGOTO 即此行为。
                    // 动态 form 缺失（TRYCGOTOFORM）才跳 catch，见下方 DYNAMIC_LABEL_CODES 分支。
                    if (labelKey in labelNames) {
                        emitGoto(labelKey, line)
                        // TRYC 语句自身在装载期无条件开块（ErbLoader.cs:1169-1178），即使跳走也要配对 CATCH。
                        if (isTryc) bindTrycMiss(code, line, null)
                    } else if (code == FunctionCode.GOTO) {
                        // **非 TRY 的常量 GOTO 缺失**：原版是行级错误（见 [emitStaticTargetNotFound]），
                        // 这里编 THROW 哨兵，绝不再让它把整函数拖成空体桩。
                        emitStaticTargetNotFound(
                            "当前函数中不存在指定的标签名\"\$${label.trimStart('$')}\"",
                            line,
                            code,
                        )
                    } else if (isTryc) {
                        // 常量标签缺失：只开块、不发射跳转 = 原版 runtime 早退（跳过本行）。
                        bindTrycMiss(code, line, null)
                    }
                }

                // ---- 动态 $标签（GOTOFORM / TRYGOTOFORM / TRYCGOTOFORM 的非常量名，
                //      以及 GOTO / TRYGOTO / TRYCGOTO 的非常量目标）----
                //
                // CMP-29：分流判据是**语句种类**，不是 `label == null`。`JumpStmt.label` 只是
                // `SpCallArgument.label`（funcNameTerm 为裸 StrLiteral 时的常量快照）；
                // GOTO 系的非常量目标在原版同样走 `SpCallArgment.FuncnameTerm.GetStrValue`
                // → `CurrentCalled.CallLabel` 的标签表查找（spec: FunctionIdentifier.cs:3681-3698
                // 的 `IsConst` 二分，非常量支在 :3694-3698），而不是 `CalledFunction.CallFunction`
                // 的函数名解析。语料 3037 处 GOTO 全为裸标识符 ⇒ 源码不可达（erbcc 容器/手写 AST 面）。
                label == null && code in LABEL_CODES -> {
                    val nameTerm = (args as? SpCallArgument)?.funcNameTerm
                        ?: throw CompileException(sig.name, line, "$code 缺少标签表达式")
                    val nameT = evalTemp(nameTerm, line)
                    // b bit31 = try 位：命中 → 跳标签；未命中 → TRY 系 pc++（跳过本行）、
                    // GOTOFORM 抛 CodeEE（Vm.kt `_JUMP_BY_NAME`）。
                    out.emit(Opcode._JUMP_BY_NAME, local(nameT), if (isTry) TRY_BIT else 0, SlotAddr.NONE, line)
                    free(nameT)
                    if (isTryc) {
                        // TRYCGOTOFORM：miss 时顺序落入下一条 `_JUMP → catch 体首`
                        // （spec: FunctionIdentifier.cs:3699-3709 —— isTry 且 JumpToEndCatch 非 null）。
                        // 两步式禁止新增 opcode；命中路径已在 _JUMP_BY_NAME 内跳走。
                        bindTrycMiss(code, line, emitJumpAlways(line))
                    }
                }

                // ---- FORM 系（CALLFORM/JUMPFORM/TRYCALLFORM/TRYCJUMPFORM/…）：运行期按名调用 ----
                label == null -> {
                    val nameTerm = (args as? SpCallArgument)?.funcNameTerm
                        ?: throw CompileException(sig.name, line, "$code 缺少函数名表达式")
                    val staged = stageArgs(rowArgs, line)
                    if (!isTryc) {
                        emitCallByName(nameTerm, staged.argc, line, isTry, isJump, callArgs = staged.args)
                    } else {
                        // 契约 R3 两步式（禁止新增 opcode；c=SlotAddr.NONE 时标志丢弃=纯 TRY 行为）：
                        //   _CALL_BY_NAME nameSlot, (argc|bit31 try|bit30 jump), c=hitFlag
                        //   _JUMP_IF_TRUE hitFlag → try 体首（换算=跳越跳转之后那条，用 out.here()
                        //     取，契约 R1 严禁硬编码 +1）
                        //   _JUMP → catch 体首（missJumps，CATCH 行回填）
                        // 命中标志已由 VM 实现（Vm.kt `_CALL_BY_NAME` :418-435）：try=1 且 c≠NONE 时
                        //   命中 setInt(c,1) 且 retDst 改传 SlotAddr.NONE（ERB 调用结果归 RESULT，不占槽），
                        //   未命中 setInt(c,0) 后 pc++。
                        val nameT = evalTemp(nameTerm, line)
                        val flag = alloc()
                        val hitPc = out.emit(Opcode._CALL_BY_NAME, local(nameT), staged.argc or TRY_BIT or (if (isJump) JUMP_BIT else 0), local(flag), line)
                        out.declareCallArgs(hitPc, staged.args)
                        free(nameT)
                        val hit = emitJumpIfTrue(local(flag), line)
                        free(flag)
                        val missJump = emitJumpAlways(line)
                        patch(listOf(hit), out.here())   // try 体首 = 跳越跳转之后的第一条指令
                        bindTrycMiss(code, line, missJump)   // 六种 TRYC 各自开块并登记 miss
                    }
                }

                // ---- 静态函数名（CALL / JUMP / TRYCALL / TRYJUMP / TRYCCALL / TRYCJUMP）----
                else -> {
                    val target = label.uppercase()
                    if (!isTryc) {
                        if (!isTry) {
                            // **非 TRY 的常量函数名缺失**：原版行级 NotDefinedFunc
                            // （spec: FunctionIdentifier.cs:3532-3535 + ErbLoader.cs:1615-1625），
                            // 执行到该行才抛 CodeEE —— 编哨兵（见 [emitStaticTargetNotFound]）。
                            // `known` 为空 = 调用方未提供函数全集（旧装置 / 装置特权），
                            // 无从判定缺失，维持旧行为（无条件发射，由 build() 兜底）。
                            if (known.isEmpty() || target in known) {
                                val staged = stageArgs(rowArgs, line, target)
                                emitCall(target, staged.argc, line, isJump = isJump, callArgs = staged.args)
                            } else {
                                emitStaticTargetNotFound("没有找到指定的函数\"@$label\"", line, code)
                            }
                        } else if (target in known) {
                            // TRY 系（非 TRYC）：查不到 = 跳过本行，编译期判名后"不发射"即为
                            // 正确降级（净效果与原版 `isTry → return` 相同，
                            // spec: FunctionIdentifier.cs:3577-3588）。
                            val staged = stageArgs(rowArgs, line, target)
                            emitCall(target, staged.argc, line, isJump = isJump, callArgs = staged.args)
                        }
                    } else {
                        // 契约 R3：TRYC 与 TRY 的唯一差异 = 目标缺失时跳 **catch 体首行**，
                        // 而非下一条语句。CALL_Instruction 没有 GOTO 那样的常量早退（跳转目标在
                        // 装载期查不到，运行期再查 `calledFunction == null`，spec:
                        // FunctionIdentifier.cs:3577-3588），故静态名缺失时 TRYC 发 `_JUMP → catch 体`。
                        // 六种 TRYC 各自开块（ErbLoader.cs:1169-1178）：命中时同样要压 TrycScope，
                        // 否则配对的 CATCH 找不到所属块。
                        if (target in known) {
                            val staged = stageArgs(rowArgs, line, target)
                            emitCall(target, staged.argc, line, isJump = isJump, callArgs = staged.args)
                            if (code in TRYC_OPENERS) scopes.add(TrycScope())
                        } else {
                            bindTrycMiss(code, line, emitJumpAlways(line))
                        }
                    }
                }
            }
        }

        /**
         * TRY{CALL,JUMP,GOTO}LIST（契约 R10-D）。语句自身是块头，后面紧跟 `FUNC …` 行直到
         * `ENDFUNC`；块内**不允许**再出现 TRY*LIST（禁止嵌套）。
         *
         * 语义（spec: Process.cs:1864-1916）：列表逐项取**第一个存在**的目标 ——
         * CALL/JUMP 命中即 `IntoFunction`（IsJump 由语句种类决定：JUMP 系尾跳、CALL 系常规调用），
         * GOTO 命中即 `JumpTo` 该标签；**全部不存在 = 不发射**，顺序落到 ENDFUNC 之后
         * （原版 miss 时 `func.JumpTo = ENDFUNC`，而 FUNC/ENDFUNC 不产生指令）。
         * 因此 FUNC/ENDFUNC 在编译产物里没有任何指令，本方法也只发射那一个目标。
         *
         * 目标名必须是常量（`FUNC` 的 `funcNameTerm` 为字符串字面量）：语料 0 处、golden 仅常量；
         * 非常量（form 目标）本遍不支持 → 直接抛（无法编译期判名）。
         *
         * @param start 语句表中 TRY*LIST 行的下标
         * @return `ENDFUNC` 下标 + 1（[run] 从那里继续编译）
         */
        private fun compileTryList(start: Int): Int {
            val opener = statements[start] as InstructionStmt
            val code = opener.code
            val targets = ArrayList<InstructionStmt>()
            var end = start + 1
            var endFunc = -1
            while (end < statements.size) {
                val cur = statements[end]
                if (cur.code == FunctionCode.ENDFUNC) {
                    endFunc = end
                    break
                }
                if (cur is InstructionStmt && cur.code in TRY_LIST_CODES) {
                    throw CompileException(sig.name, cur.line, "TRY*LIST 不能嵌套（$code 块内出现 ${cur.code}）")
                }
                // 原版装载期：TRY*LIST 体内只允许 FUNC/ENDFUNC 指令行，其余一律
                // InvalidInstructionInSyntax（isError=true，spec: ErbLoader.cs:1090-1096）。
                // 标签行（$ 行）、# 元数据行不参与该检查（nestCheck 对非指令行直接
                // continue，spec: ErbLoader.cs:1058-1068），此处照常放行。
                val structural = cur is GotoLabelStmt || cur is SharpStmt || cur is FuncLabelStmt
                if (!structural && !(cur is InstructionStmt && cur.code == FunctionCode.FUNC)) {
                    throw CompileException(
                        sig.name, cur.line,
                        "$code 块内只允许 FUNC 语句（出现 ${cur.code}，" +
                            "spec: ErbLoader.cs:1090-1096 InvalidInstructionInSyntax）",
                    )
                }
                if (cur.code == FunctionCode.FUNC) targets.add(cur as InstructionStmt)
                end++
            }
            if (endFunc < 0) {
                throw CompileException(sig.name, opener.line, "$code 缺少 ENDFUNC")
            }
            when (code) {
                FunctionCode.TRYCALLLIST, FunctionCode.TRYJUMPLIST -> {
                    for (t in targets) {
                        val arg = t.args as? SpCallArgument
                            ?: throw CompileException(sig.name, t.line, "$code 的 FUNC 目标参数形态异常")
                        val name = constFuncName(arg) ?: throw CompileException(
                            sig.name, t.line, "TRY*LIST 的 FUNC 目标必须是常量名（form 目标未支持）",
                        )
                        if (name.uppercase() in known) {
                            // 只有命中的那一条求值实参并发射（原版 miss 的目标不求值实参）
                            val staged = stageArgs(arg.rowArgs, t.line, name)
                            emitCall(
                                name, staged.argc, t.line,
                                isJump = code == FunctionCode.TRYJUMPLIST,
                                callArgs = staged.args,
                            )
                            break
                        }
                    }
                }
                FunctionCode.TRYGOTOLIST -> {
                    for (t in targets) {
                        val arg = t.args as? SpCallArgument
                            ?: throw CompileException(sig.name, t.line, "$code 的 FUNC 目标参数形态异常")
                        val name = constFuncName(arg) ?: throw CompileException(
                            sig.name, t.line, "TRY*LIST 的 FUNC 目标必须是常量名（form 目标未支持）",
                        )
                        // 与 GOTO 常量标签同一归一化口径（词法原样可能带 `$`，标签表名已剥）
                        val key = name.uppercase().trimStart('$')
                        if (key in labelNames) {
                            emitGoto(key, t.line)
                            break
                        }
                    }
                }
                else -> throw CompileException(sig.name, opener.line, "$code 不是 TRY*LIST 语句")
            }
            return endFunc + 1
        }

        /**
         * `FUNC` 目标名（常量）提取。
         *
         * `FUNC` 的参数形态是 `ArgType.SP_CALLFORM`，`funcNameTerm` 恒为 [FormStringTerm]
         * （ArgBuilders.call(form = true)），而 `SpCallArgument.label` 只认裸 [StrLiteral]，
         * 对 FUNC **恒为 null** —— 因此这里按"纯文本 FORM 串 = 常量"补一条判定，
         * 与 [FormStringTerm.getStrValue] 的常量快路径（`parts.size == 1 && Text`）同口径。
         * 含 `{表达式}`/`%%`/`\@` 插值的 FORM 名 = 运行期目标，本遍不支持（返回 null → 抛）。
         */
        private fun constFuncName(arg: SpCallArgument): String? {
            arg.label?.let { return it }
            val term = arg.funcNameTerm
            if (term is FormStringTerm && term.parts.size == 1) {
                val p = term.parts[0]
                if (p is FormPart.Text) return p.str
            }
            return null
        }

        private fun compileCallF(s: CallFStmt) {
            val fa = s.args as? SpCallFArgument
            val rowArgs = fa?.rowArgs ?: emptyList()
            val name = s.functionName
            val isTry = s.code in TRY_CODES
            if (name != null) {
                // spec: isa.yaml CALLF —— 求值但不跳转、结果不写入 RESULT
                val key = name.uppercase()
                when {
                    // TRYCALLF/TRYCALLFORMF 常量名缺失：原版 `TRYCALLF_Instruction.SetJumpTo`
                    // 解析失败即 return、运行期 `FuncTerm == null` 又 null-safe 跳过
                    // （spec: FunctionIdentifier.cs:1252-1269、:1285）⇒ 静默跳过本行。
                    isTry && key !in known -> return
                    // 非 TRY 的常量 CALLF 缺失：原版装载期 Warn(NotDefinedFunc, isError: true)
                    // （spec: FunctionIdentifier.cs:1133-1143）⇒ 行级，执行到该行才抛 CodeEE
                    // （Process.cs:1017-1019）—— 同 CALL 编哨兵。
                    !isTry && known.isNotEmpty() && key !in known ->
                        emitStaticTargetNotFound("没有找到指定的函数\"@$name\"", s.line, s.code)
                    else -> {
                        val staged = stageArgs(rowArgs, s.line, name)
                        emitCall(name, staged.argc, s.line, callArgs = staged.args)
                    }
                }
            } else {
                val nameTerm = fa?.funcNameTerm
                    ?: throw CompileException(sig.name, s.line, "${s.code} 缺少函数名表达式")
                val staged = stageArgs(rowArgs, s.line)
                // 契约 R11/API 7：CALLF 家族的动态名必须走方法解析入口（bit29），
                // 否则 `CALLFORMF GOLDEN_FF{...}` 会在 resolveFunctionIndex 里
                // 误把 #FUNCTION 方法判为"不能调用"。
                emitCallByName(nameTerm, staged.argc, s.line, isTry, callf = true, callArgs = staged.args)
            }
        }

        /**
         * 行首方法调用（`METHOD` 语句）。
         *
         * **内建方法**是 funcDic 里的 methodInstruction，是**语句**而非可调用函数：执行后整型写
         * RESULT、字符串写 RESULTS（spec: FunctionIdentifier.cs:4135-4142 注册 + METHOD_Instruction
         * :584-604；sem 的 FunctionCode.METHOD 已按此实现）。它们入语句池；编成 `_CALL` 会把
         * 它们当用户函数，build() 时以"未定义的函数"炸掉整个 Program（语料 GETMILLISECOND 即此）。
         *
         * **行首用户方法**走同一个 METHOD_Instruction：原版**求值整个 methodTerm** —— 实参按声明
         * 顺序求值、建立被调帧、取回返回值 —— 再按返回类型落 RESULT / RESULTS
         * （spec: FunctionIdentifier.cs:592-603：`methodTerm.GetOperandType() == typeof(long)`
         * → `exm.VEvaluator.RESULT = methodTerm.GetIntValue(exm)`，否则
         * `exm.VEvaluator.RESULTS = methodTerm.GetStrValue(exm)`）。因此这里：
         * 1. [stageArgs] 按 methodTerm 的实参表升序求值 → `_MOV_*` 进 R0..（与被调侧桶序一致）；
         * 2. `_CALL` 的 c = 帧内返回槽（str 方法另置 bit28 走 `_RETS` 字符串通道）；
         * 3. 投一条合成赋值 `RESULT = <int 哨兵>` / `RESULTS = <str 哨兵>` 入语句池 —— 契约 C1：
         *    具名变量只经 sem 的 execAssign 写（与落尾 `RESULT = 0` 同一条路径）。
         *
         * 旧实现只发 `emitCall(s.methodName, 0, …)`：丢全部实参、不登记 [Program.callArgs]、
         * 结果不写 RESULT/RESULTS（`_CALL` 的 c=NONE 即丢弃），与上面的原版口径不符。
         */
        private fun compileMethod(s: MethodStmt) {
            if (s.methodName.uppercase() in BuiltinSymbols.methods) { marker(s); return }
            val fc = (s.args as? MethodArgument)?.methodTerm as? FuncCall
            if (fc == null) {
                // 参数形态缺失（仅手写 AST 可达）：维持旧行为，不求值实参、不写 RESULT。
                emitCall(s.methodName, 0, s.line)
                return
            }
            val isStr = fc.operandType == OperandType.STR
            val staged = stageArgs(fc.args, s.line, s.methodName)
            val ret = alloc()                       // 调用方帧的匿名返回槽（契约 C1）
            emitCall(s.methodName, staged.argc, s.line, local(ret), strRet = isStr, callArgs = staged.args)
            val sentinel = if (isStr) {
                FuncCall(EXPR_STR_TEMP_PREFIX + ret, emptyList(), OperandType.STR, s.line, 0)
            } else {
                FuncCall(EXPR_TEMP_PREFIX + ret, emptyList(), OperandType.INT, s.line, 0)
            }
            marker(
                synthAssign(
                    if (isStr) resultsRef() else resultRef(),
                    OperatorCode.ASSIGNMENT, sentinel, s.line, s.raw,
                ),
            )
            free(ret)
        }

        // ---- 普通指令 ---------------------------------------------------------

        /**
         * 数据面语句的 marker（契约 C3）。PRINT 家族、除 `CALLEVENT`（静态名走 `_CALL`）
         * 之外的 InstructionStmt、INPUT 系、数组/颜色/系统语句等**全部**走这里：
         * 语句实例原样进池，运行期由 VM（契约 C4）交给 sem 的 StatementExecutor。
         *
         * 这里**不做**任何"具名变量 → VM 槽"的降级：赋值（含 SET/SETS）的落库路径是
         * `execAssign` → `VariableStore`（spec: StatementExecutor.kt:1196-1230），
         * 编译器若再把它们编成 `_SET_INT/_SET_STR` 就造出第二份真相（契约 C1）。
         */
        private fun compilePlainInstruction(s: InstructionStmt) {
            if (s.code == FunctionCode.CALLEVENT) {
                // spec: isa.yaml CALLEVENT —— 依次执行指定事件函数组（= 合成入口）；
                // 控制流（调用），事件名为静态字面量时留在字节码
                val name = (s.args as? com.eratw.port.core.stmt.ExpressionArgument)?.literalStr
                if (name != null) {
                    emitCall(name, 0, s.line)
                    return
                }
            }
            marker(s)
        }

        /**
         * marker（契约 C3）：`op = 语句操作码（29..197）`、`a = 语句池下标`、`b = 0`、
         * `c = SlotAddr.NONE`。`b/c` 留给以后 `.erbc` 的真操作数编码，本轮不填。
         *
         * A15 任务 3：表在位时先 [lowerStatementForPool]（前导指令发射在 marker 之前，
         * 运行期哨兵消费点即这条 marker），发射后 drain 本条语句新增的返回槽。
         */
        private fun marker(s: Statement) {
            val mark = pendingRets.size
            val s2 = if (exprCalls != null) lowerStatementForPool(s) else s
            emitStatementMarker(out, s2, sig.name)
            drainRetTemps(mark)
        }

        // ---- 作用域栈工具 -----------------------------------------------------

        private inline fun <reified T : Scope> topScope(what: String, line: Int): T {
            val top = scopes.lastOrNull()
            if (top !is T) {
                throw CompileException(sig.name, line, "$what 与所属块不匹配（栈顶 ${top?.let { it::class.simpleName }}）")
            }
            return top
        }

        private inline fun <reified T : Scope> popScope(what: String, line: Int): T {
            val top = topScope<T>(what, line)
            scopes.removeAt(scopes.size - 1)
            return top
        }

        private fun popLoop(what: String, line: Int): LoopScope = popScope<LoopScope>(what, line)
    }
}

/** 静态 `$标签` 跳转（函数内绝对跳转，编译期解析）。spec: FunctionIdentifier.cs:3655-3679。 */
private val STATIC_LABEL_CODES = setOf(
    FunctionCode.GOTO, FunctionCode.TRYGOTO, FunctionCode.TRYCGOTO,
)

/** 动态 `$标签` 跳转（`_JUMP_BY_NAME`，运行期查当前函数标签表）。 */
private val DYNAMIC_LABEL_CODES = setOf(
    FunctionCode.GOTOFORM, FunctionCode.TRYGOTOFORM, FunctionCode.TRYCGOTOFORM,
)

/**
 * 全部 `$标签` 跳转（静态 + 动态）—— `compileJumpLike` 的分流判据（CMP-29）。
 *
 * 判据必须是**语句种类**而不是 `JumpStmt.label == null`：非常量目标的 GOTO 系在原版同样是
 * 标签跳转（`GOTO_Instruction.DoInstruction` 的 `!IsConst` 支走
 * `SpCallArgment.FuncnameTerm.GetStrValue` → `CurrentCalled.CallLabel`，
 * spec: FunctionIdentifier.cs:3681-3698，非常量支在 :3694-3698），不是函数名解析。
 */
private val LABEL_CODES: Set<FunctionCode> = STATIC_LABEL_CODES + DYNAMIC_LABEL_CODES

/**
 * 求值器里**右侧可能不执行**的二元算子（短路位）。
 *
 * `&&`/`||` 见 `Expr.kt:257-263`（C# 出处 `OperatorMethodManager.cs:383-409`）；
 * `!&`/`!|`（NAND/NOR）见 `Expr.kt:283-290`（C# 出处同文件 `:439-446` / `:457-464`，
 * 判据分别是 `left == 0 ||` / `left != 0 &&`）。旧实现的短路位集合只有 AND/OR，于是
 * `!&`/`!|` 右位的用户调用被前移成无条件 `_CALL`（副作用与 RAND 消耗多跑一次，CMP-30）。
 * 编译器据此把这四者的右位留作运行期节点，但仍补缺省实参（CMP-27，[ErbProgramCompiler] 内
 * `padRuntimeCalls`）。
 */
private val SHORT_CIRCUIT_BINARY_OPS = setOf(
    OperatorCode.AND, OperatorCode.OR, OperatorCode.NAND, OperatorCode.NOR,
)

/**
 * TRY 系（含 TRYC）：目标查不到时"跳过本行 / 转 CATCH"，FORM 变体据此置
 * `_CALL_BY_NAME` 的 bit31 try 位。
 * spec: FunctionIdentifier.cs:4040-4062（CALL_Instruction/GOTO_Instruction 的 isTry/isTryCatch 注册表）、
 * :4148-4153（`funcMatch[TRYCCALL] = "CATCH"` —— TRYC 的母块闭合词是 CATCH）。
 */
private val TRY_CODES = setOf(
    FunctionCode.TRYCALL, FunctionCode.TRYJUMP, FunctionCode.TRYGOTO,
    FunctionCode.TRYCALLFORM, FunctionCode.TRYJUMPFORM, FunctionCode.TRYGOTOFORM,
    FunctionCode.TRYCCALL, FunctionCode.TRYCJUMP, FunctionCode.TRYCGOTO,
    FunctionCode.TRYCCALLFORM, FunctionCode.TRYCJUMPFORM, FunctionCode.TRYCGOTOFORM,
    FunctionCode.TRYCALLF, FunctionCode.TRYCALLFORMF,
)

/**
 * TRYC 家族（契约 R3）：**语句自身是 try 块头**，目标缺失 → 配对 CATCH 的体首行
 * （与 TRY 的"跳下一行"唯一差异）。语料形态 TRYC…/CATCH/…/ENDCATCH
 * （实测裸 TRY 0 处、CATCH=ENDCATCH=157、TRYCCALLFORM 153、其余 TRYC 变体 0）。
 * spec: FunctionIdentifier.cs:4148-4153（funcMatch[TRYCCALL]="CATCH"）+
 * FunctionCode.openToClose（TRYC*→CATCH、CATCH→ENDCATCH）。
 */
private val TRYC_CODES = setOf(
    FunctionCode.TRYCCALL, FunctionCode.TRYCJUMP, FunctionCode.TRYCGOTO,
    FunctionCode.TRYCCALLFORM, FunctionCode.TRYCJUMPFORM, FunctionCode.TRYCGOTOFORM,
)

/**
 * 会**开块**（压 TrycScope、等配对的 CATCH/ENDCATCH）的语句集合 = [TRYC_CODES] 全体。
 *
 * 依据：原版装载期 nestCheck 对六种 TRYC 语句一律 `stack.Push`（ErbLoader.cs:1169-1178），
 * CATCH 要求栈顶必须是这六者之一并与之配对（ErbLoader.cs:1339-1348），因此不存在
 * "区内转移"形态 —— 每条 TRYC 都自带 `CATCH…ENDCATCH`（金标
 * fixtures/golden/GOLDEN_03_FLOW.ERB:158-166 即 `TRYCGOTO` 自带内层 CATCH 的嵌套装法）。
 */
private val TRYC_OPENERS: Set<FunctionCode> = TRYC_CODES

/**
 * TRY{CALL,JUMP,GOTO}LIST 块头（契约 R10-D）：后接 `FUNC …` 行直至 `ENDFUNC`，禁止嵌套。
 * 目标按书写顺序取**第一个存在者**，只发射那一条；全部不存在则整个块不产生指令
 * （顺序落到 ENDFUNC 之后）。spec: Process.cs:1864-1916。
 */
private val TRY_LIST_CODES = setOf(
    FunctionCode.TRYCALLLIST, FunctionCode.TRYJUMPLIST, FunctionCode.TRYGOTOLIST,
)

/** `_CALL_BY_NAME`/`_JUMP_BY_NAME` 的 b 操作数 bit31 = try 标志（契约 C5/R3；同 Vm.kt 的 TRY_FLAG）。 */
private const val TRY_BIT = Int.MIN_VALUE

/**
 * `_CALL`/`_CALL_BY_NAME` 的 b 操作数 bit30 = JUMP（尾跳）标志（契约 R10/API 3；
 * 同 Vm.kt 的 `JUMP_FLAG`：命中后 `jumpInto` 不加帧深，返回直达调用者的调用者）。
 */
private const val JUMP_BIT = 0x40000000

/** `_CALL_BY_NAME` 的 b 操作数 bit29 = CALLF 家族标志（契约 R11/API 5；同 Vm.kt 的 CALLF_FLAG）。 */
private const val CALLF_BIT = 0x20000000

/**
 * `_CALL`/`_CALL_BY_NAME` 的 b 操作数 bit28 = 字符串返回标志（合同 M3-A §3 A7；
 * 同 Vm.kt 的 `STR_RET_FLAG`）：`#FUNCTIONS` 方法调用（语句位丢弃型 / 表达式位
 * METHOD_STR 降级）置位，被调帧经 `_RETS` 返回、leaveFrame 走 `setStr` 写回。
 */
private const val STR_RET_BIT = 0x10000000

// ==========================================================================
// 表达式位调用的编译期降级（A15 任务 3）
// ==========================================================================

/** 表达式位 `FuncCall` 的编译期分类（口径 = 原版 IdentifierDictionary.GetFunctionMethod）。 */
enum class ExprCallKind {
    /** `#FUNCTION` 用户方法（int 返回）—— 本遍降级为 `_CALL` + 返回哨兵。 */
    METHOD_INT,

    /**
     * `#FUNCTIONS` 用户方法（str 返回，类型**已确证**）—— 降级为
     * `_CALL`(b bit28 = STR_RET_FLAG) + `_RETS` 字符串通道 + 字符串哨兵（合同 M3-A §3 A7）。
     */
    METHOD_STR,

    /**
     * 用户方法但返回类型**未知**（methodTypes 缺声明）—— 保持原节点，运行期桥显式红。
     * 与 [METHOD_STR] 分桶的理由：把可能实为 `#FUNCTION` 的方法误降级成字符串通道会
     * **静默读错值**，而保守不降级只是明确失败（ExprCalls 桥同口径）。
     */
    METHOD_UNKNOWN,

    /** 普通 @label、无同名内建 —— 编译期抛 UsedNonMethodFunc 复刻（spec: IdentifierDictionary.cs:566-569）。 */
    NON_METHOD_LABEL,

    /** 内建方法 —— 保持原节点，运行期内建桥（BuiltinEvalContext，A4 接线）。 */
    BUILTIN,

    /** 既非用户函数也非内建 —— 保持原节点（原版在解析期报"未定义标识符"，本侧维持现状）。 */
    UNKNOWN,
}

/**
 * 表达式位调用分类表（[compileFunction] 的 `exprCalls` 参数）。集合键一律大写
 * （spec: Config.cs:33 `StrComper = OrdinalIgnoreCase`）；构造方 = 符号表/erbcc 桥
 * （LabelDef.isMethod + `#FUNCTION`/`#FUNCTIONS` 之辨 + 普通 @label 全集）。
 *
 * 优先级复刻 spec: IdentifierDictionary.cs:548-585：用户方法 **遮蔽** 同名内建（先查
 * methods）；普通 @label 与内建同名 → 静默走内建（:566 判据）；普通 @label 无内建同名
 * → [ExprCallKind.NON_METHOD_LABEL]（抛）。REF 方法（:550-553/:542-545）本遍未支持，
 * 调用方暂不要放进 methods 集（实参需引用传递，值暂存形态编不出来；见 A15 报告）。
 */
class ExprCallTable(
    val intMethods: Set<String> = emptySet(),
    val strMethods: Set<String> = emptySet(),
    val nonMethodLabels: Set<String> = emptySet(),
    /**
     * 类型未知的用户方法（isMethod 但 methodTypes 无声明）—— 不降级（见
     * [ExprCallKind.METHOD_UNKNOWN]）。与 strMethods 分桶，避免误判通道。
     */
    val unknownMethods: Set<String> = emptySet(),
) {
    fun classify(name: String): ExprCallKind {
        val key = name.uppercase()
        if (key in intMethods) return ExprCallKind.METHOD_INT
        if (key in strMethods) return ExprCallKind.METHOD_STR
        if (key in unknownMethods) return ExprCallKind.METHOD_UNKNOWN
        val isBuiltin = Builtins.registry.returnType(name) != null
        if (key in nonMethodLabels) return if (isBuiltin) ExprCallKind.BUILTIN else ExprCallKind.NON_METHOD_LABEL
        return if (isBuiltin) ExprCallKind.BUILTIN else ExprCallKind.UNKNOWN
    }
}

/**
 * 表达式位方法调用**返回值哨兵**的名字前缀：降级产物是零参 `FuncCall("$EXPR_TEMP_PREFIX<k>")`，
 * 运行期由求值语境识别前缀并读**调用方帧**的第 k 号局部槽（值由 `_CALL` 的 c=retDst 槽经
 * `_RET`/leaveFrame 写入，spec: Vm.kt:438-446/570-580）。
 *
 * `#` 不是 ERB 标识符字符（词法层不可能产出同名函数），与用户/内建名无碰撞。
 * 运行期桥（约 6 行，ProcessRuntime.evalContext.callFunction 的前置分支，A4 文件）：
 * ```kotlin
 * if (name.startsWith(EXPR_TEMP_PREFIX)) {
 *     val k = name.substring(EXPR_TEMP_PREFIX.length).toInt()
 *     return Value.IntV(vm.getInt(SlotAddr.local(k)))
 * }
 * ```
 * 求值发生在调用方帧内（前导 `_CALL` 已返回），`vm.getInt(local(k))` 即当前帧槽。
 */
const val EXPR_TEMP_PREFIX = "#EXPR_RET#"

/** 哨兵名 → 返回槽号；不是哨兵返回 null（运行期桥/测试用）。 */
fun exprTempSlotOf(name: String): Int? =
    if (name.startsWith(EXPR_TEMP_PREFIX)) name.substring(EXPR_TEMP_PREFIX.length).toIntOrNull() else null

/**
 * [EXPR_TEMP_PREFIX] 的**字符串通道**版（合同 M3-A §3 A7）：`#FUNCTIONS`（str 方法）
 * 表达式位降级产物是零参 `FuncCall("$EXPR_STR_TEMP_PREFIX<k>", operandType = STR)`，
 * 运行期由求值语境识别前缀并读**调用方帧**第 k 号局部槽的**字符串**值
 * （值由 `_CALL`(b bit28) 的 c=retDst 槽经 `_RETS`/leaveFrameStr 写入，spec: Vm.kt
 * `_RETS` 分支）。与 [EXPR_TEMP_PREFIX] 并列互不误判（第 10 字符 `S` vs `#`）。
 *
 * 运行期桥（ProcessRuntime.evalContext.callFunction 的前置分支，与 int 哨兵同址）：
 * ```kotlin
 * if (name.startsWith(EXPR_STR_TEMP_PREFIX)) {
 *     val k = name.substring(EXPR_STR_TEMP_PREFIX.length).toInt()
 *     return Value.StrV(vm.getStr(SlotAddr.local(k)))
 * }
 * ```
 */
const val EXPR_STR_TEMP_PREFIX = "#EXPR_RETS#"

/** 字符串哨兵名 → 返回槽号；不是哨兵返回 null（运行期桥/测试用）。 */
fun exprStrTempSlotOf(name: String): Int? =
    if (name.startsWith(EXPR_STR_TEMP_PREFIX)) name.substring(EXPR_STR_TEMP_PREFIX.length).toIntOrNull() else null
