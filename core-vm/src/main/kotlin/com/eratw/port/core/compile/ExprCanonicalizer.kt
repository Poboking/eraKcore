package com.eratw.port.core.compile

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.NullTerm
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.BitArgument
import com.eratw.port.core.stmt.CallFStmt
import com.eratw.port.core.stmt.CallSharpStmt
import com.eratw.port.core.stmt.CallStmt
import com.eratw.port.core.stmt.CaseArgument
import com.eratw.port.core.stmt.CaseExpression
import com.eratw.port.core.stmt.CaseStmt
import com.eratw.port.core.stmt.CatchStmt
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
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.RefArgument
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.RepeatStmt
import com.eratw.port.core.stmt.ReturnFStmt
import com.eratw.port.core.stmt.ReturnStmt
import com.eratw.port.core.stmt.SelectCaseStmt
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
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.StrAsignArgument
import com.eratw.port.core.stmt.StrDataArgument
import com.eratw.port.core.stmt.StrDoubleArgument
import com.eratw.port.core.stmt.VarDeclStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.stmt.WendStmt
import com.eratw.port.core.stmt.WhileStmt

/**
 * 语句入池前的表达式规范折叠（MEM-OPT，ADR-002 常驻预算）。
 *
 * ## 问题账单
 *
 * PERF-1 全量语料 S2 实测：`statements.args` 子树 ≈ 485.9 MiB、`ConstantPool.exprs`
 * ≈ 115.0 MiB（两处共享实例，存在重复计入）。解析器为每条语句新建 Expr 节点，
 * 重复形态（`IntLiteral(0)`、`VarRef(RESULT, [0])` 等）在几十万语句的语料里
 * 以百万计，每个节点 32B 起，纯驻留浪费。
 *
 * ## 做法
 *
 * 语句 marker 入池前（[ErbProgramCompiler.emitStatementMarker]）对整棵语句树做
 * **自底向上规范折叠**：
 * 1. 每个节点的子节点先折叠成规范实例，再以「子节点全规范 + line/col 归一为 -1」
 *    的形态查 [canonical] 表，命中即复用池里的**同一实例**，未命中则登记为该形态的
 *    规范代表。
 * 2. line/col 归一：Expr 的行列号在运行期零读者（求值、sem、Vm 均不读），只在
 *    编译期错误定位用——而编译发生在折叠**之前**（见下），归一不损失任何信息。
 * 3. 语句本身**不**进折叠表：契约 C2（语句池按身份追加、严禁去重）原样保持 ——
 *    每条 marker 重建至多一个新语句实例、恒新开池条目；结构相等的两条语句仍是
 *    两个条目、两个实例。
 *
 * ## 语义保持
 *
 * - Expr 全部是不可变 data class（Expr.kt），共享实例与私有副本在求值上无差别；
 *   sem/proc/Vm 对 Expr **没有身份依赖**（数据节键是 Statement 实例，不是 Expr）。
 * - Argument/Statement 的可变状态（isConst/constStr/constInt/addConst、CaseExpression
 *   的 var 字段）逐字段随拷贝迁移，见 [withConst]。
 * - **数据节家族语句豁免折叠**（[PrintDataStmt] / [DataStmt] 恒原实例）：数据节以
 *   Statement 实例为键（sem/ExecContext.kt:96-100），宿主在 AST 上注册、VM 从池内
 *   取指回查，两侧必须同一实例；折叠其 args 会让语句重建、身份链断裂（E2E
 *   `SDV=` 首差实证）。详见 [canonicalizeStatement] 两分支注释。
 * - 编译期读 Expr 行列号的点（ErbProgramCompiler 的 FuncCall 降级、解析期报错）
 *   全部发生在本折叠**之前**：折叠只在 marker 发射时执行，该语句的编译已结束。
 *   probe 遍（第一遍探测 maxArgc）直接跳过折叠。
 * - ErbcWriter 会把语句树的 line/col 写进 .erbc（规约 §5.4）：折叠后语句树节点
 *   以 -1 落盘，格式仍合法、往返自洽；池 exprs 段（_EVAL_ 用的那部分）不经本类，
 *   不受影响。
 *
 * ## 代价与释放
 *
 * 规范表 [canonical] 是编译期一次性开销（HashMap 条目 ≈ 40B × 不同形态节点数，
 * 全量语料量级下估计 100 MiB 上下的**峰值**增量），随 [ErbProgramCompiler.build]
 * 里的 [release] 丢弃；换来的常驻收益是把语句树里的重复节点收敛为单实例。
 */
internal class ExprCanonicalizer {

    /** 结构相等（含 line/col = -1 归一）→ 规范实例。仅 Expr（全部不可变 data class）。 */
    private val canonical = HashMap<Expr, Expr>()

    /** 编译收尾释放规范表（build() 调用）。已共享出去的实例由语句树自身持有，不受影响。 */
    fun release() {
        canonical.clear()
    }

    // ------------------------------------------------------------------
    // 语句层入口
    // ------------------------------------------------------------------

    /**
     * 折叠一条语句的表达式树，返回原实例（无表达式或全未变）或等价重建副本。
     * 每次调用至多产出一个新实例；**绝不**把两条语句折叠成同一实例（契约 C2）。
     */
    fun canonicalizeStatement(s: Statement): Statement = when (s) {
        is AssignStmt -> {
            val dest = canonExpr(s.dest) as VarRef
            val args = canonArg(s.args)
            if (dest === s.dest && args === s.args) s
            else AssignStmt(dest, s.op, args, s.line, s.raw)
        }
        is PrintStmt -> {
            val args = canonArg(s.args)
            if (args === s.args) s else PrintStmt(s.print, args, s.line, s.raw)
        }
        is PrintDataStmt -> {
            // 数据节消费者（PRINTDATA/STRDATA 头语句）**恒原实例**，跳过折叠。
            // 契约：数据节以 Statement 实例为键（sem/ExecContext.kt:96-100「键为该
            // PRINTDATA 语句实例」；proc/ProcessRuntime.kt DataSectionSource /
            // installDataSections；sem/StatementExecutor.execPrintData / execStrData
            // 的 groupsOf(stmt)）。宿主在 AST 语句树上注册数据节（E2E
            // GoldenPipeline / boot RealCorpusCompile / GameBootSmoke 的
            // IdentityHashMap），VM 从语句池取指回查——两侧必须是**同一实例**。
            // 本类若折叠其 args（STRDATA 的 DEST VarRef 经 canonExpr 做 line/col
            // 归一必然换新实例 → StrDataArgument 重建 → args !== s.args），语句
            // 随之重建为新实例入池，身份链断裂 → groupsOf 查空 → SDV 未写入
            // （E2E 首差 `SDV=`（空）vs 基准 `SDV=SDA` 实证；L3 链尾常量随之不符）。
            // 折叠收益权衡：头语句 args 至多一个 DEST VarRef，全语料可折叠增益
            // 近零，为它破坏 C2 身份链不值——语义优先（ADR-005 同款裁决逻辑）。
            s
        }
        is JumpStmt -> {
            val args = canonArg(s.args)
            if (args === s.args) s else JumpStmt(s.code, s.label, args, s.line, s.raw)
        }
        is CallStmt -> {
            val args = canonArg(s.args)
            if (args === s.args) s else CallStmt(s.code, s.label, args, s.line, s.raw)
        }
        is CallFStmt -> {
            val args = canonArg(s.args)
            if (args === s.args) s else CallFStmt(s.functionName, args, s.line, s.raw, s.code)
        }
        is CallSharpStmt -> {
            val args = canonArg(s.args)
            if (args === s.args) s else CallSharpStmt(args, s.line, s.raw)
        }
        is MethodStmt -> {
            val args = canonArg(s.args)
            if (args === s.args) s else MethodStmt(s.methodName, args, s.line, s.raw)
        }
        is VarDeclStmt -> {
            val args = canonArg(s.args)
            if (args === s.args) s else VarDeclStmt(s.code, args, s.line, s.raw)
        }
        is IfStmt -> {
            val cond = canonExpr(s.condition)
            val args = canonArg(s.args)
            if (cond === s.condition && args === s.args) s
            else IfStmt(cond, args, s.line, s.raw, s.single)
        }
        is ElseIfStmt -> {
            val cond = canonExpr(s.condition)
            val args = canonArg(s.args)
            if (cond === s.condition && args === s.args) s
            else ElseIfStmt(cond, args, s.line, s.raw)
        }
        is SelectCaseStmt -> {
            val target = canonExpr(s.target)
            val args = canonArg(s.args)
            if (target === s.target && args === s.args) s
            else SelectCaseStmt(target, args, s.line, s.raw)
        }
        is CaseStmt -> {
            val cases = canonCases(s.cases)
            val args = canonArg(s.args)
            if (cases === s.cases && args === s.args) s
            else CaseStmt(cases, args, s.line, s.raw)
        }
        is ForStmt -> {
            val args = canonArg(s.args)
            if (args === s.args) s else ForStmt(s.code, args, s.line, s.raw)
        }
        is NextStmt -> {
            val args = canonArg(s.args)
            if (args === s.args) s else NextStmt(args, s.line, s.raw, s.code)
        }
        is RepeatStmt -> {
            val args = canonArg(s.args)
            if (args === s.args) s else RepeatStmt(args, s.line, s.raw)
        }
        is RendStmt -> {
            val args = canonArg(s.args)
            if (args === s.args) s else RendStmt(args, s.line, s.raw)
        }
        is WhileStmt -> {
            val cond = canonExpr(s.condition)
            val args = canonArg(s.args)
            if (cond === s.condition && args === s.args) s
            else WhileStmt(cond, args, s.line, s.raw)
        }
        is LoopStmt -> {
            val cond = expr(s.condition)
            val args = canonArg(s.args)
            if (cond === s.condition && args === s.args) s
            else LoopStmt(cond, args, s.line, s.raw)
        }
        is ReturnStmt -> {
            val values = exprListNullable(s.values)
            val args = canonArg(s.args)
            if (values === s.values && args === s.args) s
            else ReturnStmt(values, args, s.line, s.raw)
        }
        is ReturnFStmt -> {
            val value = expr(s.value)
            val args = canonArg(s.args)
            if (value === s.value && args === s.args) s
            else ReturnFStmt(value, args, s.line, s.raw, s.code)
        }
        is DataStmt -> {
            // 数据行（DATA/DATAFORM/STRDATA…）恒原实例，跳过折叠。与上方
            // PrintDataStmt 同一身份链口径：单行 `STRDATA <dest>` 形态本身就是
            // 数据节键（GoldenPipeline.harvestDataSections 的
            // `s is DataStmt && s.code == STRDATA` 消费者判定），数据文本由
            // [DataStmt.text] 直接持有、args 多为 Void——折叠无收益、身份有契约。
            s
        }
        // 具名子类（DoStmt/ElseStmt/EndIfStmt/CaseElseStmt/EndSelectStmt/WendStmt/
        // CatchStmt/EndCatchStmt/DataListStmt/EndBlockStmt/FlowStmt 等）落到本分支时**保留
        // 原实例**：语句身份决定 sem 分派（ErbProgramCompiler「子类身份保真」契约），
        // 而按基类 InstructionStmt 重建会**静默丢子类类型**、让 sem 落到 else 兜底分支。
        // 这些子类的 args 在当前解析器下恒为 VoidArgument（canonArg 恒返回原实例、本就不
        // 重建），故保留分支不损失折叠；它是对「将来某子类携带可折叠表达式」的契约级防御。
        // 只有 InstructionStmt 的**直接实例**（169 条 isa 语句共用的通用指令行）才重建。
        is InstructionStmt -> {
            if (s.javaClass != InstructionStmt::class.java) s
            else {
                val args = canonArg(s.args)
                if (args === s.args) s else InstructionStmt(s.code, args, s.line, s.raw)
            }
        }
        // FuncLabelStmt / GotoLabelStmt / SharpStmt：无表达式字段，原样保留。
        else -> s
    }

    // ------------------------------------------------------------------
    // Expr 折叠
    // ------------------------------------------------------------------

    private fun expr(e: Expr?): Expr? = e?.let(::canonExpr)

    /**
     * 每个分支**无条件重建**为 line/col = -1 的形态再查表（不在分支里对已归一节点
     * 短路 —— 否则第二个同形节点同样短路、双双绕过规范表，去重失效）。查表返回
     * 规范实例：若 [e] 本身已是规范实例，getOrPut 命中的就是它自己，实例不换。
     */
    private fun canonExpr(e: Expr): Expr {
        val rebuilt: Expr = when (e) {
            is IntLiteral -> IntLiteral(e.value, -1, -1)
            is StrLiteral -> StrLiteral(e.value, -1, -1)
            is NullTerm -> NullTerm(e.isInt, -1, -1)
            is VarRef -> VarRef(e.name, exprList(e.indices), e.isNoArg, e.operandType, e.isConst, e.subKey, -1, -1)
            is KeywordIndexArg -> KeywordIndexArg(canonExpr(e.expr), e.varName, e.dim, -1, -1)
            is FuncCall -> FuncCall(e.name, exprListNullable(e.args), e.operandType, -1, -1)
            is Unary -> Unary(e.op, canonExpr(e.operand), -1, -1)
            is UnaryAfter -> UnaryAfter(e.op, canonExpr(e.operand), -1, -1)
            is Binary -> Binary(e.op, canonExpr(e.left), canonExpr(e.right), e.operandType, -1, -1)
            is Ternary -> Ternary(canonExpr(e.cond), canonExpr(e.ifTrue), canonExpr(e.ifFalse), e.operandType, -1, -1)
            is FormStringTerm -> FormStringTerm(
                e.parts.map { p ->
                    when (p) {
                        is FormPart.Text -> p
                        is FormPart.Embedded -> FormPart.Embedded(canonExpr(p.ast), canonConversion(p.conversion))
                    }
                },
                -1,
                -1,
            )
        }
        return canonical.getOrPut(rebuilt) { rebuilt }
    }

    private fun canonConversion(c: FormConversion): FormConversion = when (c) {
        is FormConversion.Curly -> FormConversion.Curly(expr(c.width), c.alignLeft)
        is FormConversion.Percent -> FormConversion.Percent(expr(c.width), c.alignLeft)
        is FormConversion.YenAt -> FormConversion.YenAt(canonExpr(c.ifTrue), canonExpr(c.ifFalse))
    }

    /** 非空元素列表：全未变时原样返回原列表实例。 */
    private fun exprList(list: List<Expr>): List<Expr> {
        var changed = false
        val out = ArrayList<Expr>(list.size)
        for (x in list) {
            val c = canonExpr(x)
            if (c !== x) changed = true
            out.add(c)
        }
        return if (changed) out else list
    }

    /** 可含 null 的元素列表（FuncCall 实参空位 / RETURN 空位）。 */
    private fun exprListNullable(list: List<Expr?>): List<Expr?> {
        var changed = false
        val out = ArrayList<Expr?>(list.size)
        for (x in list) {
            val c = expr(x)
            if (c !== x) changed = true
            out.add(c)
        }
        return if (changed) out else list
    }

    /** CASE 条件式：CaseExpression 可变，不进规范表，只做字段级迁移。 */
    private fun canonCases(list: List<CaseExpression>): List<CaseExpression> {
        var changed = false
        val out = ArrayList<CaseExpression>(list.size)
        for (c in list) {
            val left = expr(c.leftTerm)
            val right = expr(c.rightTerm)
            if (left !== c.leftTerm || right !== c.rightTerm) changed = true
            out.add(CaseExpression(c.caseType, left, right, c.operator))
        }
        return if (changed) out else list
    }

    // ------------------------------------------------------------------
    // Argument 折叠
    // ------------------------------------------------------------------

    /** 把原参数的可变常量缓存状态（spec: Argument.cs:7-11）迁移到重建副本上。 */
    private fun <A : Argument> A.withConst(from: Argument): A {
        isConst = from.isConst
        constStr = from.constStr
        constInt = from.constInt
        return this
    }

    private fun canonArg(a: Argument): Argument = when (a) {
        is VoidArgument -> a
        is ExpressionArgument -> {
            val t = expr(a.term)
            if (t === a.term) a else ExpressionArgument(t).withConst(a)
        }
        is ExpressionArrayArgument -> {
            val ts = exprListNullable(a.terms)
            if (ts === a.terms) a else ExpressionArrayArgument(ts).withConst(a)
        }
        is ExpressionsArgument -> {
            val ts = exprListNullable(a.terms)
            if (ts === a.terms) a else ExpressionsArgument(a.types, ts).withConst(a)
        }
        is IntAsignArgument -> {
            val e2 = expr(a.exp)
            if (e2 === a.exp) a else IntAsignArgument(a.name, a.lengths, e2).withConst(a)
        }
        is StrAsignArgument -> a
        is BitArgument -> {
            val dest = canonExpr(a.dest) as VarRef
            val ts = exprList(a.terms)
            if (dest === a.dest && ts === a.terms) a else BitArgument(dest, ts).withConst(a)
        }
        is CaseArgument -> {
            val cs = canonCases(a.caseExps)
            if (cs === a.caseExps) a else CaseArgument(cs).withConst(a)
        }
        is MethodArgument -> {
            val t = expr(a.methodTerm)
            if (t === a.methodTerm) a else MethodArgument(t).withConst(a)
        }
        is PrintDataArgument -> {
            val d = expr(a.dest)
            if (d === a.dest) a else PrintDataArgument(d as VarRef?).withConst(a)
        }
        is RefArgument -> {
            val t = expr(a.srcTerm)
            if (t === a.srcTerm) a else RefArgument(a.refName, a.srcName, t, a.refByName).withConst(a)
        }
        is StrDataArgument -> {
            val d = expr(a.dest)
            if (d === a.dest) a else StrDataArgument(d as VarRef?).withConst(a)
        }
        is SpArrayControlArgument -> {
            val dest = canonExpr(a.dest) as VarRef
            val n1 = expr(a.num1)
            val n2 = expr(a.num2)
            if (dest === a.dest && n1 === a.num1 && n2 === a.num2) a
            else SpArrayControlArgument(dest, n1, n2).withConst(a)
        }
        is SpArrayShiftArgument -> {
            val dest = canonExpr(a.dest) as VarRef
            val n1 = expr(a.num1)
            val n2 = expr(a.num2)
            val n3 = expr(a.num3)
            val n4 = expr(a.num4)
            if (dest === a.dest && n1 === a.num1 && n2 === a.num2 && n3 === a.num3 && n4 === a.num4) a
            else SpArrayShiftArgument(dest, n1, n2, n3, n4).withConst(a)
        }
        is SpArraySortArgument -> {
            val dest = canonExpr(a.dest) as VarRef
            val n1 = expr(a.num1)
            val n2 = expr(a.num2)
            if (dest === a.dest && n1 === a.num1 && n2 === a.num2) a
            else SpArraySortArgument(dest, a.order, n1, n2).withConst(a)
        }
        is SpBarArgument -> {
            val v = expr(a.value)
            val m = expr(a.max)
            val l = expr(a.length)
            if (v === a.value && m === a.max && l === a.length) a
            else SpBarArgument(v, m, l).withConst(a)
        }
        is SpButtonArgument -> {
            val s = expr(a.printStrTerm)
            val w = expr(a.buttonWord)
            if (s === a.printStrTerm && w === a.buttonWord) a
            else SpButtonArgument(s, w).withConst(a)
        }
        is SpCVarSetArgument -> {
            val dest = canonExpr(a.dest) as VarRef
            val i = expr(a.index)
            val t = expr(a.term)
            val st = expr(a.start)
            val en = expr(a.end)
            if (dest === a.dest && i === a.index && t === a.term && st === a.start && en === a.end) a
            else SpCVarSetArgument(dest, i, t, st, en).withConst(a)
        }
        is SpCallArgument -> {
            val f = expr(a.funcNameTerm)
            val sub = exprListNullable(a.subNames)
            val row = exprListNullable(a.rowArgs)
            if (f === a.funcNameTerm && sub === a.subNames && row === a.rowArgs) a
            else SpCallArgument(f, sub, row).withConst(a)
        }
        is SpCallFArgument -> {
            val f = expr(a.funcNameTerm)
            val sub = exprListNullable(a.subNames)
            val row = exprListNullable(a.rowArgs)
            if (f === a.funcNameTerm && sub === a.subNames && row === a.rowArgs) a
            else SpCallFArgument(f, sub, row).withConst(a)
        }
        is SpCallSharpArgument -> {
            val f = expr(a.funcNameTerm)
            val sub = exprListNullable(a.subNames)
            val row = exprListNullable(a.rowArgs)
            if (f === a.funcNameTerm && sub === a.subNames && row === a.rowArgs) a
            else SpCallSharpArgument(f, sub, row).withConst(a)
        }
        is SpColorArgument -> {
            val r = expr(a.r)
            val g = expr(a.g)
            val b2 = expr(a.b)
            val rgb = expr(a.rgb)
            if (r === a.r && g === a.g && b2 === a.b && rgb === a.rgb) a
            else if (a.rgb != null) SpColorArgument(rgb).withConst(a)
            else SpColorArgument(r, g, b2).withConst(a)
        }
        is SpCopyArrayArgument -> {
            val v1 = expr(a.varName1)
            val v2 = expr(a.varName2)
            if (v1 === a.varName1 && v2 === a.varName2) a
            else SpCopyArrayArgument(v1, v2).withConst(a)
        }
        is SpDtColumnOptions -> {
            val d = expr(a.dt)
            val col = expr(a.column)
            val vs = exprListNullable(a.values)
            if (d === a.dt && col === a.column && vs === a.values) a
            else SpDtColumnOptions(d, col, a.options, vs).withConst(a)
        }
        is SpForNextArgument -> {
            val cnt = canonExpr(a.cnt) as VarRef
            val st = expr(a.start)
            val en = expr(a.end)
            val sp = expr(a.step)
            if (cnt === a.cnt && st === a.start && en === a.end && sp === a.step) a
            else SpForNextArgument(cnt, st, en, sp).withConst(a)
        }
        is SpGetIntArgument -> {
            val d = canonExpr(a.dest) as VarRef
            if (d === a.dest) a else SpGetIntArgument(d).withConst(a)
        }
        is SpHtmlPrint -> {
            val s = expr(a.str)
            val o = expr(a.opt)
            if (s === a.str && o === a.opt) a
            else SpHtmlPrint(s, o).withConst(a)
        }
        is SpHtmlSplitArgument -> {
            val t = expr(a.targetStr)
            val n = expr(a.num)
            if (t === a.targetStr && n === a.num) a
            else SpHtmlSplitArgument(t, a.destVarName, n as VarRef?).withConst(a)
        }
        is SpInputsArgument -> {
            val d = expr(a.def)
            val m = expr(a.mouse)
            val c = expr(a.canSkip)
            if (d === a.def && m === a.mouse && c === a.canSkip) a
            else SpInputsArgument(d, m, c).withConst(a)
        }
        is SpPowerArgument -> {
            val dest = canonExpr(a.dest) as VarRef
            val x = expr(a.x)
            val y = expr(a.y)
            if (dest === a.dest && x === a.x && y === a.y) a
            else SpPowerArgument(dest, x, y).withConst(a)
        }
        is SpPrintImgArgument -> {
            val n = expr(a.name)
            val nb = expr(a.nameb)
            val nm = expr(a.namem)
            val param = a.param?.let { list ->
                var ch = false
                val out = ArrayList<MixedIntegerExprTerm>(list.size)
                for (t in list) {
                    val v = expr(t.num)
                    if (v !== t.num) ch = true
                    out.add(MixedIntegerExprTerm(v, t.isPx))
                }
                if (ch) out else list
            }
            if (n === a.name && nb === a.nameb && nm === a.namem && param === a.param) a
            else SpPrintImgArgument(n, nb, nm, param).withConst(a)
        }
        is SpPrintShapeArgument -> {
            var ch = false
            val out = ArrayList<MixedIntegerExprTerm>(a.param.size)
            for (t in a.param) {
                val v = expr(t.num)
                if (v !== t.num) ch = true
                out.add(MixedIntegerExprTerm(v, t.isPx))
            }
            if (!ch) a else SpPrintShapeArgument(out).withConst(a)
        }
        is SpPrintVArgument -> {
            val ts = exprList(a.terms)
            if (ts === a.terms) a else SpPrintVArgument(ts).withConst(a)
        }
        is SpSaveDataArgument -> {
            val t = expr(a.target)
            val s = expr(a.strExpression)
            if (t === a.target && s === a.strExpression) a
            else SpSaveDataArgument(t, s).withConst(a)
        }
        is SpSaveVarArgument -> {
            val t = expr(a.term)
            val m = expr(a.savMes)
            if (t === a.term && m === a.savMes) a
            else SpSaveVarArgument(t, m, a.varNames).withConst(a)
        }
        is SpSetArgument -> {
            val dest = canonExpr(a.dest) as VarRef
            val t = expr(a.term)
            if (dest === a.dest && t === a.term) a
            else SpSetArgument(dest, t).withConst(a).also { it.addConst = a.addConst }
        }
        is SpSetArrayArgument -> {
            val dest = canonExpr(a.dest) as VarRef
            val ts = exprListNullable(a.terms)
            if (dest === a.dest && ts === a.terms) a
            else SpSetArrayArgument(dest, ts, a.constIntList, a.constStrList).withConst(a)
        }
        is SpSortcharaArgument -> {
            val k = canonExpr(a.sortKey) as VarRef
            if (k === a.sortKey) a else SpSortcharaArgument(k, a.sortOrder).withConst(a)
        }
        is SpSplitArgument -> {
            val t = expr(a.targetStr)
            val s = expr(a.split)
            val n = expr(a.num)
            if (t === a.targetStr && s === a.split && n === a.num) a
            else SpSplitArgument(t, s, a.destVarName, n as VarRef?).withConst(a)
        }
        is SpSwapCharaArgument -> {
            val x = expr(a.x)
            val y = expr(a.y)
            if (x === a.x && y === a.y) a else SpSwapCharaArgument(x, y).withConst(a)
        }
        is SpSwapVarArgument -> {
            val v1 = canonExpr(a.var1) as VarRef
            val v2 = canonExpr(a.var2) as VarRef
            if (v1 === a.var1 && v2 === a.var2) a else SpSwapVarArgument(v1, v2).withConst(a)
        }
        is SpTInputsArgument -> {
            val time = expr(a.time)
            val d = expr(a.def)
            val disp = expr(a.disp)
            val to = expr(a.timeout)
            val m = expr(a.mouse)
            val c = expr(a.canSkip)
            if (time === a.time && d === a.def && disp === a.disp && to === a.timeout && m === a.mouse && c === a.canSkip) a
            else SpTInputsArgument(time, d, disp, to, m, c).withConst(a)
        }
        // 旧写法直接返回原实例、`dest` 不参与折叠：同一表达式树里 `TIMES A, x` 的 `A`
        // 与别处折叠后的 `VarRef(A)` 因行号不同而**不共享实例**（去重遗漏，非语义错）。
        // 与 [SpVarSetArgument]/[SpForNextArgument] 等同类 Action 口径一致，折叠 dest。
        is SpTimesArgument -> {
            val dest = canonExpr(a.dest) as VarRef
            if (dest === a.dest) a else SpTimesArgument(dest, a.doubleValue)
        }
        is SpVarSetArgument -> {
            val dest = canonExpr(a.dest) as VarRef
            val t = expr(a.term)
            val st = expr(a.start)
            val en = expr(a.end)
            if (dest === a.dest && t === a.term && st === a.start && en === a.end) a
            else SpVarSetArgument(dest, t, st, en).withConst(a)
        }
        is SpVarsizeArgument -> a
        is StrDoubleArgument -> {
            val t = expr(a.term)
            if (t === a.term) a else StrDoubleArgument(t, a.doubleValue).withConst(a)
        }
        is HtmlPrintArgument -> {
            val t = expr(a.term)
            val l = expr(a.lineEnd)
            if (t === a.term && l === a.lineEnd) a else HtmlPrintArgument(t, l).withConst(a)
        }
    }
}
