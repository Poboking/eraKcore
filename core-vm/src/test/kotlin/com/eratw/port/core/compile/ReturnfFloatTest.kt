package com.eratw.port.core.compile

import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.FloatLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.Value
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `RETURNF` 的**浮点返回值通道**（4.981 升版，路次 [g2-returnf-float]）。
 *
 * ## 原版口径（取证级 A：fork 仓库 qirchen421/emuera.sk@48494165，
 * ## 本机检出 `<work-root>/vendor/emuera-skia`；1.824 快照的 `SingleTerm` 只有 long/str 两支，
 * ## 浮点支只存在于 fork，故本轮号一律指 fork 文件）
 *
 * * **载体是 `MethodReturnValue`，`RETURNF` 不写 `RESULTF`**：
 *   `Process.State.cs:760`（`public SingleTerm MethodReturnValue;`）、
 *   `Process.State.cs:762-789`（`ReturnF(SingleTerm ret)` 只做「弹帧 +
 *   `currentLine = ReturnAddress` + 置载体」，**一个字节都不碰变量层**）。
 *   `RESULTF` 只由 `METHOD` 语句按项类型写入（`Instraction.Child.cs:613-629`，浮点支 `:626`）。
 * * **载体有浮点态**：`Term.cs:108-141` 的 `SingleFloatTerm : SingleTerm`
 *   —— `readonly double fValue`、`GetFloatValue` 直取、`.Float` 访问器。
 *   `RETURNF_Instruction.DoInstruction`（`Instraction.Child.cs:3737-3746`）把
 *   `term.GetValue(exm)` 的**原样项**交给 `ReturnF`，不做任何类型转换。
 * * **消费侧按运行时项类型取值、不符即静默零值**（`UserDefinedMethodTerm.cs:19-38`）：
 *   `GetIntValue` 只认 `SingleLongTerm`（否则 `0`）、`GetStrValue` 只认 `SingleStrTerm`
 *   （否则 `""`）、`GetFloatValue` 只认 `SingleFloatTerm`（否则 `0.0`）；
 *   载体为 null（无 `RETURNF` / 掉尾）时按**声明类型**给默认项（同文件 `:39-49`）。
 * * **掉尾**：`Process.ScriptProc.cs:77-82` 命中函数尾时 `if (!IsFunctionMethod) RESULT = 0;`
 *   再 `Return(0)`；`Process.State.cs:467-475` 的 `Return` 对方法帧直接转 `ReturnF(null)`
 *   ⇒ 浮点方法掉尾 = 消费侧得 0.0。
 * * **类型不符只是解析警告**（`Instraction.Child.cs:3705-3732`）：整数表达式落进浮点函数
 *   那一支甚至写着 `// Int to Float: auto-promote, no warning`；运行期不改值，
 *   由上面「消费侧按项类型」那条静默成 0.0（**不是**将表达式值截断/宽化）。
 * * 无参 `RETURNF`：`Instraction.Child.cs:3700-3702`（`func.Argument == null` 直接 return，
 *   不做类型校验）+ `:3737-3746`（`ret` 留 null）⇒ 与 specs/isa.yaml:1799「无参数返回 0/空串」
 *   同口径，浮点位即 0.0。
 *
 * ## 本侧落点（为什么浮点返回值不走帧槽）
 *
 * 帧槽只有整型列与字符串列（`Vm.kt` 的 `frameInts`/`frameStrs`），浮点值在移植侧只活在
 * 表达式求值器与 [VariableStore] 的浮点列里。所以新码 `_RETF`（244，末尾追加）的 `a` 是
 * **表达式池下标**：在被调帧内求值 → 记进 `VirtualMachine.floatResult` 寄存器 → 拆帧
 * （与 `_RETS`/`stringResult` 同形）。`a = NONE` 表达「无返回值」= 0.0。
 *
 * 由此浮点方法的**消费路只有运行期重入通道**（`ProcessRuntime.invokeUserMethod` 的第三通道，
 * 取派生 VM 的 `floatResult`），编译期降级的哨兵读槽那两条（int/str）不适用；
 * 行首 `FF(…)` 这类 `METHOD` 语句对浮点方法也**不再降级**，保持 `FuncCall` 原节点交语句层，
 * 由 `sem/StatementExecutor.kt:789-800` 的浮点支写 `RESULTF`。
 */
class ReturnfFloatTest {

    // ------------------------------------------------------------------
    // 夹具（解析/编译骨架同 MethodCallExprLowerTest 同源）
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
        .declareVariable("A", OperandType.INT, 1)
        .declareVariable("CNT", OperandType.INT, 1)
        // 浮点声明的三个形态：常量返回 / 读浮点局部 / 掉尾无 RETURNF
        .declareFunction("FF", OperandType.FLOAT)
        .declareFunction("FLOCAL", OperandType.FLOAT)
        .declareFunction("FTAIL", OperandType.FLOAT)
        .declareFunction("FNOARG", OperandType.FLOAT)
        .declareFunction("FINT", OperandType.FLOAT)
        // 不回归对照：整数方法与字符串方法
        .declareFunction("MI", OperandType.INT)
        .declareFunction("SIDE", OperandType.INT)
        .declareFunction("SM", OperandType.STR)
        // 行首 `FF 3` / `SM 3, "abc"` 形态 = METHOD 语句
        .declareMethod("FF")
        .declareMethod("SM"),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "returnf.erb", i + 1, text))
        }.filterNotNull()
    }

    /**
     * 调用点分类表：浮点方法**一个都不登记**。
     *
     * 这是刻意的 —— 登记进 `intMethods`/`strMethods` 会把浮点方法误编成整型/字符串哨兵
     * （读帧槽），而浮点值根本不在槽里。[com.eratw.port.core.compile.ExprCallKind.UNKNOWN]
     * 那一支保持 `FuncCall` 原节点，运行期由重入通道取值。
     */
    private fun callTable() = ExprCallTable(
        intMethods = setOf("MI", "SIDE"),
        strMethods = setOf("SM"),
        nonMethodLabels = emptySet(),
    )

    private data class Callee(
        val name: String,
        val lines: List<String>,
        val arity: Int = 0,
        val argInt: Int = 0,
        val argStr: Int = 0,
        val returnsStr: Boolean = false,
        val returnsFloat: Boolean = false,
    )

    private class Model(val program: Program, val metas: List<FuncMeta>, val store: VariableStore) {
        fun runtime(): ProcessRuntime = ProcessRuntime(program, store, FunctionTable(metas), VmConfig())
    }

    private fun observedStore(): VariableStore = VariableStore().apply {
        declareUserVar(DimSpec("CNT", isStr = false, dims = intArrayOf(1)))
        addPseudoCharacter()
    }

    private fun model(main: List<Statement>, vararg callees: Callee): Model {
        val c = ErbProgramCompiler()
        val tbl = callTable()
        val known = buildSet {
            add("MAIN")
            callees.forEach { add(it.name) }
        }
        c.compileFunction(FuncSig("MAIN", 0), main, known, tbl)
        val metas = ArrayList<FuncMeta>()
        metas.add(FuncMeta(index = c.indexOf("MAIN"), key = "MAIN", name = "MAIN", position = "returnf.erb"))
        for (f in callees) {
            c.compileFunction(
                FuncSig(
                    f.name, f.arity, isMethod = true,
                    returnsStr = f.returnsStr, paramNames = emptyList(), returnsFloat = f.returnsFloat,
                ),
                parse(*f.lines.toTypedArray()),
                known,
                tbl,
            )
            metas.add(
                FuncMeta(
                    index = c.indexOf(f.name), key = f.name, name = f.name, isMethod = true,
                    argIntCount = f.argInt, argStrCount = f.argStr, returnsStr = f.returnsStr,
                    returnsFloat = f.returnsFloat,
                    position = "returnf.erb",
                ),
            )
        }
        return Model(c.build("MAIN"), metas, observedStore())
    }

    private fun ErbProgramCompiler.indexOf(name: String): Int =
        functions.first { it.sig.name.equals(name, ignoreCase = true) }.index

    private fun ins(p: Program, pc: Int) = p.instructionAt(pc)

    private fun pcs(p: Program, op: Int): List<Int> =
        (0 until p.instructionCount).filter { ins(p, it).op == op }

    /** 被调函数体区间内的 `a` 操作数读数（按操作码取，NONE 用 [SlotAddr.isNone] 判）。 */
    private fun operandsof(p: Program, op: Int): List<Int> = pcs(p, op).map { ins(p, it).a }

    // ------------------------------------------------------------------
    // 1) 主链：RETURNF 浮点常量 → 调用侧按浮点取值
    // ------------------------------------------------------------------

    @Test
    fun `RETURNF 浮点常量 调用侧按浮点读回并截断为整数`() {
        val m = model(
            parse("A:0 = TOINT(FF() * 100)"),
            Callee("FF", listOf("#FUNCTION", "RETURNF 1.25"), returnsFloat = true),
        )
        m.runtime().execute()
        assertEquals(125L, m.store.readInt("A", longArrayOf(0)), "TOINT(1.25 * 100) = 125")

        // 发射形状：RETURNF 一条 `_RETF`（a = 表达式池下标）+ 浮点函数掉尾兜底一条 `_RETF NONE`，
        // 整表没有 `_RET`/`_RETS` 混进浮点方法，也没有降级的 `_CALL`。
        val p = m.program
        val retf = pcs(p, Opcode._RETF)
        assertEquals(
            2, retf.size,
            "两条浮点出帧点：RETURNF 表达式 + 掉尾兜底（全表 ${p.instructionCount} 条，" +
                "_RETF=${retf.map { ins(p, it).a }}）",
        )
        val withValue = retf.filterNot { SlotAddr.isNone(ins(p, it).a) }
        val noneOps = retf.filter { SlotAddr.isNone(ins(p, it).a) }
        assertEquals(1, withValue.size, "RETURNF 那条的 a 必须是表达式池下标")
        assertEquals(1, noneOps.size, "掉尾兜底那条的 a 必须是 NONE（原版 ReturnF(null)）")
        assertEquals(0, pcs(p, Opcode._RETS).size, "浮点方法不得编字符串通道")
        assertEquals(
            1, pcs(p, Opcode._RET).size,
            "唯一的 `_RET` 是 MAIN 自己的掉尾兜底：浮点方法的兜底已改编 `_RETF`（整型通道没被冒用）",
        )
        assertEquals(
            0, pcs(p, Opcode._CALL).size,
            "浮点方法在表达式位**不降级**（帧槽无浮点列）⇒ 一条前导 _CALL 都不发，只走运行期重入通道",
        )
        // 表达式池里确实是浮点字面量（而不是被折进整型常量池）
        val e = p.expr(ins(p, withValue.single()).a)
        assertTrue(e is FloatLiteral && e.value == 1.25, "_RETF 的 a 指向浮点字面量，实际=$e")
    }

    @Test
    fun `RETURNF 浮点表达式 在被调帧内求值`() {
        // `ARG * 0.5`：整型局部与浮点字面量相乘 ⇒ 解析期就是 FLOAT 结点（expr/Parser.kt
        // 「任一操作数浮点则结果浮点」），求值点在 `_RETF` 那一刻、仍在被调帧内
        // （原版 DoInstruction 先 GetValue 再 ReturnF，Instraction.Child.cs:3737-3746）。
        val m = model(
            parse("A:0 = TOINT(FF(3) * 10)"),
            Callee("FF", listOf("#FUNCTION", "RETURNF ARG * 0.5"), arity = 1, argInt = 1, returnsFloat = true),
        )
        m.runtime().execute()
        assertEquals(15L, m.store.readInt("A", longArrayOf(0)), "TOINT(1.5 * 10) = 15")
        val e = m.program.expr(operandsof(m.program, Opcode._RETF).first())
        assertTrue(
            e is Binary && e.op == OperatorCode.MULT && e.operandType == OperandType.FLOAT,
            "浮点表达式整棵进表达式池，实际=$e",
        )
    }

    // ------------------------------------------------------------------
    // 2) 浮点局部：RETURNF LOCALF
    // ------------------------------------------------------------------

    @Test
    fun `RETURNF 浮点局部 LOCALF 读被调帧的浮点列`() {
        val m = model(
            parse("A:0 = TOINT(FLOCAL() * 4)"),
            Callee(
                "FLOCAL",
                listOf("#FUNCTION", "LOCALF:0 = 2.5", "RETURNF LOCALF"),
                returnsFloat = true,
            ),
        )
        m.runtime().execute()
        assertEquals(10L, m.store.readInt("A", longArrayOf(0)), "TOINT(2.5 * 4) = 10")
        assertEquals(2, operandsof(m.program, Opcode._RETF).size, "掉尾兜底也发一条 _RETF NONE，加 RETURNF 一条")
    }

    // ------------------------------------------------------------------
    // 3) 容错三分支：无参 / 掉尾 / 类型不符（全部 = 0.0，且副作用不丢）
    // ------------------------------------------------------------------

    @Test
    fun `无参 RETURNF 返回零`() {
        val m = model(
            parse("A:0 = TOINT(FNOARG() * 100) + 1"),
            Callee("FNOARG", listOf("#FUNCTION", "RETURNF"), returnsFloat = true),
        )
        m.runtime().execute()
        assertEquals(1L, m.store.readInt("A", longArrayOf(0)), "0.0 * 100 = 0，+1 证明求值确实发生")
        assertTrue(
            operandsof(m.program, Opcode._RETF).any { SlotAddr.isNone(it) },
            "无参 RETURNF = _RETF NONE（原版 ReturnF(null)，Instraction.Child.cs:3700-3702）",
        )
    }

    @Test
    fun `浮点方法掉尾 无RETURN 得零且兜底走浮点通道`() {
        val m = model(
            parse("A:0 = TOINT(FTAIL() * 100) + CNT:0"),
            Callee("FTAIL", listOf("#FUNCTION", "CNT:0 = CNT:0 + 1"), returnsFloat = true),
        )
        m.runtime().execute()
        assertEquals(1L, m.store.readInt("A", longArrayOf(0)), "掉尾返回 0.0，函数体确实跑过（CNT 进 A）")
        assertEquals(1L, m.store.readInt("CNT", longArrayOf(0)))
        // 掉尾兜底那条必须是 `_RETF NONE`，不能是 `_RET`：原版 `Return(0)` 对方法帧转
        // `ReturnF(null)`（Process.StateProc 那条判据），浮点声明的消费侧要的是浮点默认项。
        assertTrue(
            operandsof(m.program, Opcode._RETF).any { SlotAddr.isNone(it) },
            "掉尾兜底发 _RETF NONE",
        )
        assertEquals(
            1, pcs(m.program, Opcode._RET).size,
            "全表只剩 MAIN 自己的一条掉尾 `_RET`：浮点方法 FTAIL 的兜底已改编 `_RETF`",
        )
    }

    @Test
    fun `整数方法掉尾 兜底仍是RET 不被浮点通道改动`() {
        // 反向证明：`returnsFloat=false` 的函数掉尾码一位未变。
        val m = model(
            parse("A:0 = MI() + 1"),
            Callee("MI", listOf("#FUNCTION", "CNT:0 = CNT:0 + 1")),
        )
        m.runtime().execute()
        assertEquals(1L, m.store.readInt("A", longArrayOf(0)), "整型方法掉尾得 0")
        assertEquals(0, pcs(m.program, Opcode._RETF).size, "非浮点方法一个 _RETF 都不发")
        assertTrue(pcs(m.program, Opcode._RET).size >= 2, "MAIN 掉尾 + MI 掉尾各一条 _RET")
    }

    @Test
    fun `浮点方法RETURNF整数表达式 得零但表达式仍求值`() {
        // 原版：整数表达式落进浮点函数只是**解析警告**（Instraction.Child.cs:3725-3732 的
        // 「auto-promote, no warning」支），运行期载体是 `SingleLongTerm`，
        // 消费侧 `GetFloatValue` 只认 `SingleFloatTerm` ⇒ **静默 0.0**
        // （UserDefinedMethodTerm.cs:31-35），而**不是**把整数宽化成 7.0。
        // 本侧同口径：`_RETF NONE`（0.0），但整数表达式仍编进帧内临时槽 ⇒ 副作用照跑。
        val m = model(
            parse("A:0 = TOINT(FINT() * 100)"),
            Callee(
                "FINT",
                listOf("#FUNCTION", "RETURNF SIDE()"),
                returnsFloat = true,
            ),
            Callee("SIDE", listOf("#FUNCTION", "CNT:0 = CNT:0 + 1", "RETURNF 7")),
        )
        m.runtime().execute()
        assertEquals(1L, m.store.readInt("CNT", longArrayOf(0)), "RETURNF 的整数表达式被求值（被调方法副作用生效）")
        assertEquals(0L, m.store.readInt("A", longArrayOf(0)), "类型不符静默得 0.0，不是 7.0 也不是宽化值")
    }

    // ------------------------------------------------------------------
    // 4) 不回归：整数方法与字符串方法照旧
    // ------------------------------------------------------------------

    @Test
    fun `整数方法RETURNF仍走RET通道`() {
        val m = model(
            parse("A:0 = MI(40, 85)"),
            Callee("MI", listOf("#FUNCTION", "RETURNF ARG + ARG:1"), arity = 2, argInt = 2),
        )
        m.runtime().execute()
        assertEquals(125L, m.store.readInt("A", longArrayOf(0)))
        assertEquals(0, pcs(m.program, Opcode._RETF).size, "整型方法不发 _RETF")
        assertTrue(pcs(m.program, Opcode._CALL).size >= 1, "严格位调用仍编译期降级为 _CALL + 哨兵读槽")
    }

    @Test
    fun `字符串方法RETURNF仍走RETS通道`() {
        val m = model(
            parse("SM 3, \"abc\""),
            Callee(
                "SM",
                listOf("#FUNCTIONS", "RETURNF \"yes\""),
                arity = 2, argInt = 1, argStr = 1, returnsStr = true,
            ),
        )
        m.runtime().execute()
        assertEquals("yes", m.store.readStr("RESULTS", longArrayOf(0)), "str 方法返回值仍落 RESULTS")
        assertEquals(0, pcs(m.program, Opcode._RETF).size, "str 方法不发 _RETF")
        assertTrue(pcs(m.program, Opcode._RETS).isNotEmpty(), "str 通道仍编 _RETS")
    }

    // ------------------------------------------------------------------
    // 5) 帧返回口径：`_RETF` 在非浮点调用位（`_CALL` 的返回槽）
    // ------------------------------------------------------------------

    @Test
    fun `CALLF浮点方法 帧返回不写调用方槽且继续执行`() {
        // `CALLF` = 求值但丢弃返回值（isa.yaml CALLF）⇒ `_CALL` 的 c = NONE，
        // 被调帧从 `_RETF` 出帧（depth>0 那一支）：按原版消费侧容错写 0（这里连槽都不写）。
        val m = model(
            parse("CALLF FF(2)", "A:0 = TOINT(FF(3) * 10)"),
            Callee("FF", listOf("#FUNCTION", "CNT:0 = CNT:0 + 1", "RETURNF ARG * 0.5"),
                arity = 1, argInt = 1, returnsFloat = true),
        )
        m.runtime().execute()
        assertEquals(2L, m.store.readInt("CNT", longArrayOf(0)), "两次调用各执行一次（CALLF 与表达式位）")
        assertEquals(15L, m.store.readInt("A", longArrayOf(0)), "CALLF 之后控制流回到调用方，第二次调用得 1.5")
    }

    // ------------------------------------------------------------------
    // 6) METHOD 语句：浮点方法的返回值写 RESULTF（不降级）
    // ------------------------------------------------------------------

    @Test
    fun `行首浮点方法调用写RESULTF 不写RESULT`() {
        // 原版 `METHOD_Instruction.DoInstruction` 求值整个 methodTerm 后按**项类型**三分
        // （fork-src Instraction.Child.cs:613-629，浮点支 :626 写 RESULTF）。
        // 本侧 `compileMethod` 对 FLOAT 方法**保持原节点**（帧槽无浮点列），
        // 由语句层 `sem/StatementExecutor.kt:789-800` 的浮点支落 RESULTF。
        val m = model(
            parse("FF 3"),
            Callee("FF", listOf("#FUNCTION", "RETURNF ARG * 0.5"), arity = 1, argInt = 1, returnsFloat = true),
        )
        m.runtime().execute()
        assertEquals(
            1.5, m.store.readFloat("RESULTF", LongArray(0)),
            "浮点方法的行首调用把返回值写进 RESULTF（fork 的 METHOD 浮点支）",
        )
        assertEquals(0L, m.store.readInt("RESULT", longArrayOf(0)), "整数落点 RESULT 不得被浮点方法写入")
    }

    // ------------------------------------------------------------------
    // 7) 声明面选道（g7）：运行期 FORM 解析 / 引擎屏蔽
    // ------------------------------------------------------------------

    @Test
    fun `运行期FORM解析 浮点方法报FLOAT`() {
        // PRINTFORMS 的 `{...}` 在**运行期**才用 runtimeFormResolver 解析：若把 FF 报成 INT，
        // `FF() > 1` 会走 FuncCall.getIntValue 并撞上 FloatV（「返回了非整数结果」）；
        // 报成 FLOAT 才能按浮点比较求值（1.5 > 1 → 1）。
        val m = model(
            parse("PRINTFORMS \"{FF() > 1}\""),
            Callee("FF", listOf("#FUNCTION", "RETURNF 1.5"), returnsFloat = true),
        )
        val rt = m.runtime()
        rt.execute()
        assertEquals("1", rt.output.toString().trim(), "FORM 解析按声明把 FF 当 FLOAT（否则抛非整数）")
    }

    @Test
    fun `被屏蔽的浮点方法返回浮点零而不是整数零`() {
        val m = model(
            parse("A:0 = TOINT(FF() * 100) + 1"),
            Callee("FF", listOf("#FUNCTION", "RETURNF 1.5"), returnsFloat = true),
        )
        val rt = ProcessRuntime(
            m.program, m.store, FunctionTable(m.metas), VmConfig(), disabledFunctions = setOf("FF"),
        )
        val r = rt.invokeUserMethod("FF", emptyList())
        assertTrue(r is Value.FloatV, "屏蔽的浮点方法必须交回 FloatV，实际=${r?.javaClass?.simpleName}")
        assertEquals(0.0, (r as Value.FloatV).v, "空体 RETURN 0 的浮点默认项")
        assertEquals(listOf("FF"), rt.stubbedFunctionNames.map { it.uppercase() })
    }

    // ------------------------------------------------------------------
    // 8) `.erbc` 装载往返：含 `_RETF` 的产物读回后同路径读数一致
    // ------------------------------------------------------------------

    /**
     * `_RETF`(244) 的容器往返（组长接线：`ErbcFormat.OPCODE_MAX` 已跟到 `Opcode.MAX_OPCODE`）。
     *
     * 判定咽喉是 `ErbcReader.needsStatementPool`（`code/ErbcReader.kt:157-163`），
     * 且 `loader/ErbcStream.kt` 的映射路必须同口径（`ErbcStreamEquivalenceTest` 锁死两者一致）。
     * 这里断言：写出的字节能解码、`_RETF` 的表达式下标逐条对齐、解码后的程序跑出的
     * 读数与直建路逐字相同（解码器不得丢浮点返回信息）。
     */
    @Test
    fun `erbc装载往返 含RETF的产物读回后同一路径读数一致`() {
        val direct = model(
            parse("A:0 = TOINT(FF(3) * 10)"),
            Callee("FF", listOf("#FUNCTION", "RETURNF ARG * 0.5"), arity = 1, argInt = 1, returnsFloat = true),
        )
        direct.runtime().execute()
        val want = direct.store.readInt("A", longArrayOf(0))
        assertEquals(15L, want, "直建路先绿：浮点返回链本身可执行")

        val bytes = ErbcWriter.encode(direct.program)
        assertTrue(bytes.isNotEmpty(), "编码产物不得为空")

        val after = ErbcReader.decode(bytes)
        assertEquals(direct.program.instructionCount, after.instructionCount, "往返后指令数变了")
        assertEquals(direct.program.exprCount, after.exprCount, "往返后表达式池条目数变了")
        assertEquals(
            operandsof(direct.program, Opcode._RETF), operandsof(after, Opcode._RETF),
            "_RETF 的表达式下标逐条对齐（NONE = 掉尾兜底）",
        )

        val roundTrip = Model(after, direct.metas, observedStore())
        roundTrip.runtime().execute()
        assertEquals(
            want, roundTrip.store.readInt("A", longArrayOf(0)),
            "同一小程序两条路径读数不同 = `.erbc` 丢了浮点返回通道",
        )
    }
}
