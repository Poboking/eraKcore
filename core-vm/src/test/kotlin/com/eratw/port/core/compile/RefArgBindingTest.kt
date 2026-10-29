package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
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
import kotlin.test.assertFailsWith

/**
 * **REF 形参绑定**（`#DIM REF` 形参位）—— bootSmoke 首差
 * `引用变量 LETTER_TO 未达目标（EmptyRefVar）` 的回归锁。
 *
 * ## 现场
 *
 * `ERB/コマンド関連/COMF/日常系/FUNC_IRAI.ERB:974` 起的
 * `CALL SET_LETTER_TO([[香霖堂]], LETTER_TO)`（`[[…]]` 经 `CSV/_Rename.csv` 换成整数 id）
 * 调 `@SET_LETTER_TO(LETTER_FROM, LETTER_TO)`（同文件 `:1105-1113`：
 * `#DIM REF LETTER_TO`，体内 `ID_LAST = FINDELEMENT(LETTER_TO, 0)`）。
 * 旧实现只把实参**值**搬进 `ARG/ARGS`；REF 形参变量永远没有目标数组 ⇒ 第一次读就抛
 * `引用变量 LETTER_TO 未达目标（EmptyRefVar）`（[com.eratw.port.core.vars.RefUserVar]）。
 *
 * ## 原版口径（逐条 `文件:行号`）
 *
 * * 装载期校验：REF 形参位的实参必须是 `VariableTerm`（`CalledFunction.cs:154`），
 *   维度/类型与被调一致（`:159` → `ReferenceToken.MatchType`：类型 `:158`、维度 `:163`），
 *   常量/伪变量/全局引用局部变量/角色变量各有拒绝分支（`ReferenceToken.cs:130-169`）。
 * * 求值期：`UserDefinedFunctionArgument.SetTransporter`（`UserDefinedFunctionArgument.cs:35-69`）
 *   对 REF 位取 `variableTerm.Identifier.GetArray()`（`:45-58`；CHARADATA 分支 `:46-54`
 *   因 `ConvertArg` 传 `allowChara:false` 对用户函数不可达）。
 * * 进入被调帧：`ProcessState.IntoFunction` `:435-452` 逐**头部形参位** i 写回，
 *   REF 分支 = `((ReferenceToken)call.TopLabel.Arg[i].Identifier).SetRef(srcArgs.TransporterRef[i])`
 *   （`:439-441`）⇒ 绑定的是**数组对象本身**（`ReferenceToken.cs:125-128` `array = refArray`），
 *   被调写入对调用方立即可见（不是按值拷贝 + 回写）。
 * * `Arguments[i] == null` 的位跳过不写（`:437`）⇒ 省略实参不解除已有绑定。
 *
 * ## 本侧为什么必须按**头部形参位**而不是 `#DIM` 声明序
 *
 * `Program.functions[i].paramNames` 来自装载侧 `FuncLabelStmt.paramNames`（头部形参名，
 * 与 `FuncSig.arity` 等长）；`Program.refArgs` 是编译期登记的「调用点 pc → 该位实参是哪个
 * 裸变量」。运行期在 `ProcessRuntime.onFunctionEnter`（`_CALL` 建帧、`VirtualMachine.lastCallPc`
 * 仍在位）按 `position` 取形参名，落到被调私有 [com.eratw.port.core.vars.RefUserVar] 上。
 *
 * REF 形参在 `#DIM` 里的**声明序**与头部形参序不保证一致（全语料 539 个 REF 形参里 63 个不同；
 * 例 `ERB/CHARA_LIST.ERB:4` `@MAKE_CHARA_LIST(LIST_TYPE, L_ID, CHARA_LIST, CHARA_CNT)`
 * 把 `L_ID` 声明在 `C_ID` 之后），故第 4 例锁死「位序 = 头部位序，不是声明序」。
 */
class RefArgBindingTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("SRC_ARR", OperandType.INT, 1)
            .declareVariable("SRC_ARR2", OperandType.INT, 1)
            .declareVariable("SRC_STRS", OperandType.STR, 1)
            .declareVariable("VALUE_IN", OperandType.INT, 1)
            .declareVariable("REF_TGT", OperandType.INT, 1)
            .declareVariable("REF_TGTS", OperandType.STR, 1)
            .declareVariable("LEN_OUT", OperandType.INT, 1)
            .declareVariable("C_ID", OperandType.INT, 1)
            .declareVariable("OUT", OperandType.INT, 1)
            .declareVariable("OUT2", OperandType.INT, 1)
            .declareVariable("MAT_OUT", OperandType.INT, 1)
            // 递归 REF 用例的深度形参
            .declareVariable("LV", OperandType.INT, 1)
            // 多维 REF 用例（MAT 是调用方 5x5，MAT_REF 是被调 #DIM REF 形参）
            .declareVariable("MAT", OperandType.INT, 2)
            .declareVariable("MAT_REF", OperandType.INT, 2)
            // 2D 字符串 REF（#DIMS REF，IntArray 驻留下标通道）
            .declareVariable("SMAT", OperandType.STR, 2)
            .declareVariable("SMAT_REF", OperandType.STR, 2)
            // 报错分支用例的实参面（解析期 SymbolTable 需认识这些名字）
            .declareVariable("CONST_ARR", OperandType.INT, 1)
            .declareVariable("C_LIST", OperandType.INT, 1)
            .declareVariable("SRC_REF", OperandType.INT, 1)
            // 维度不符用例：被调 REF 形参是 2 维，须与既有 1 维 REF_TGT 区名
            .declareVariable("REF_TGT2", OperandType.INT, 2),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "refarg.erb", i + 1, text))
        }
    }

    /** 一个待编译函数：名字 + arity + 行。 */
    private data class Fn(val name: String, val arity: Int, val lines: Array<String>, val params: List<String> = emptyList())

    /**
     * 从测试源行里扫 `#DIM REF NAME` / `#DIMS REF NAME`，折成 [DimSpecLayout] 的函数局部表。
     *
     * REF 位的**编译期**判定源就是它（`LocalDim.ref`）：`FuncLabelStmt.paramRefs` 只是
     * 头部形参项原文，不含 REF 信息；真实语料由装载侧 `LabelDef.dimSpecs → LocalDim`
     * 喂入（`RealCorpusCompile` / `GoldenPipeline` 的 `buildFuncDims`），本测试等价直给。
     */
    private fun layoutOf(fns: Array<out Fn>): DimSpecLayout {
        val dims = HashMap<String, List<LocalDim>>()
        val decl = Regex("""^#\s*DIMS?\s+.*\bREF\b\s+([^\s,;=]+)""", RegexOption.IGNORE_CASE)
        for (f in fns) {
            val refs = f.lines.mapNotNull { text ->
                val m = decl.find(text.trim()) ?: return@mapNotNull null
                LocalDim(m.groupValues[1], isStr = text.trim().startsWith("#DIMS", ignoreCase = true), ref = true)
            }
            if (refs.isNotEmpty()) dims[f.name.uppercase()] = refs
        }
        return DimSpecLayout(GlobalNameLayout(), dims)
    }

    private fun compile(vararg fns: Fn): Pb {
        val c = ErbProgramCompiler(layoutOf(fns))
        val names = fns.map { it.name }.toSet()
        for (f in fns) {
            c.compileFunction(FuncSig(f.name, f.arity, paramNames = f.params), parse(*f.lines), names)
        }
        return Pb(c, c.build(fns.first().name), fns.map { it.name })
    }

    /** 编译产物 + 函数下标查询（`ErbProgramCompiler.functions` 的顺序 == `Program.functions`）。 */
    private class Pb(val compiler: ErbProgramCompiler, val program: Program, val names: List<String>) {
        fun index(name: String): Int = compiler.functions.first { it.sig.name == name }.index
        fun meta(name: String, argInt: Int = 0, argStr: Int = 0): FuncMeta =
            FuncMeta(
                index = index(name), key = name,
                argIntCount = argInt, argStrCount = argStr,
                defIndex = index(name),
            )
    }

    private fun run(pb: Pb, store: VariableStore): ProcessRuntime {
        val metas = pb.names.map { name -> pb.meta(name, argInt = pb.program.functions[pb.index(name)].arity) }
        val rt = ProcessRuntime(pb.program, store, FunctionTable(metas), VmConfig())
        rt.execute()
        return rt
    }

    private fun intVar(name: String, size: Int = 10) = DimSpec(name, false, intArrayOf(size), isPrivate = true)

    private fun strVar(name: String, size: Int = 10) = DimSpec(name, true, intArrayOf(size), isPrivate = true)

    private fun refVar(name: String, isStr: Boolean = false) =
        DimSpec(name, isStr, intArrayOf(0), isRef = true, isPrivate = true)

    // ------------------------------------------------------------------
    // 1. 整型 REF：绑的是数组本体（别名），被调写入对调用方可见
    // ------------------------------------------------------------------

    @Test
    fun `整型 REF 形参绑到调用方数组本体（写入别名可见）`() {
        val pb = compile(
            Fn("MAIN", 1, arrayOf("@MAIN(SRC_ARR)", "CALL TGT_WRITER(SRC_ARR)", "RETURN")),
            Fn(
                "TGT_WRITER", 1,
                arrayOf("@TGT_WRITER(REF_TGT)", "#DIM REF REF_TGT", "REF_TGT:3 = 4242", "RETURN"),
                params = listOf("REF_TGT"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("SRC_ARR"), "MAIN")
        store.declareUserVar(refVar("REF_TGT"), "TGT_WRITER")
        run(pb, store)
        assertEquals(0, store.callDepth(), "帧必须全部退出（enter/leave 配对）")
        store.enterFunction("MAIN")
        assertEquals(
            4242L, store.readInt("SRC_ARR", longArrayOf(3)),
            "REF 必须是别名（ReferenceToken.cs:125-128），不是拷贝回写",
        )
        store.leaveFunction()
    }

    // ------------------------------------------------------------------
    // 2. 字符串通道 REF（`#DIMS REF`）：IntArray（驻留池下标）别名
    // ------------------------------------------------------------------

    @Test
    fun `字符串 REF 形参绑到调用方字符串数组`() {
        val pb = compile(
            Fn("MAIN2", 1, arrayOf("@MAIN2(SRC_STRS)", "CALL S_WRITER(SRC_STRS)", "RETURN")),
            Fn(
                "S_WRITER", 1,
                arrayOf("@S_WRITER(REF_TGTS)", "#DIMS REF REF_TGTS", "#DIM LEN_OUT", "LEN_OUT = STRLENS(REF_TGTS:0)", "RETURN"),
                params = listOf("REF_TGTS"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(strVar("SRC_STRS"), "MAIN2")
        store.declareUserVar(refVar("REF_TGTS", isStr = true), "S_WRITER")
        store.declareUserVar(intVar("LEN_OUT"), "S_WRITER")
        store.enterFunction("MAIN2")
        store.writeStr("SRC_STRS", longArrayOf(0), "hi")
        store.leaveFunction()
        run(pb, store)
        // 被调通过 REF **读到**调用方的字符串数组（StringTable 驻留下标同源）：
        // STRLENS("hi") == 2，证明绑的是调用方数组而不是空目标
        val lenVar = store.userVar("LEN_OUT", "S_WRITER")!!
        assertEquals(2L, lenVar.readInt(0, 0), "REF 字符串实参必须指向调用方数组（ReferenceToken.cs:125-128）")
    }

    // ------------------------------------------------------------------
    // 2b. 局部系统数组作 REF 实参（`LOCAL`/`LOCALS`/`ARG`/`ARGS`）
    // ------------------------------------------------------------------

    /**
     * 原版这四个名字是 `LocalVariableToken`，`GetArray()` 返回所属函数的数组本体
     * （spec: LocalVariableToken.cs:17-37）；REF 绑定与用户变量同一条 `SetRef`
     * （ReferenceToken.cs:125-128）。旧实现只查 `VariableStore.userVar`，把这四个名字
     * 静默跳过 ⇒ 被调首次读就报 `引用变量 … 未绑定目标（EmptyRefVar）`
     * （语料 `qol_skipmove.ERB` 的 `SPLIT_F(…, LOCALS)` 形态）。
     */
    @Test
    fun `REF 形参可以绑到调用方的 LOCALS`() {
        val pb = compile(
            Fn("MAINLOC", 0, arrayOf("@MAINLOC", "#LOCALSSIZE 8", "CALL S_WRITER2(LOCALS)", "RETURN")),
            Fn(
                "S_WRITER2", 1,
                arrayOf("@S_WRITER2(REF_TGTS)", "#DIMS REF REF_TGTS", "REF_TGTS:0 '= \"hi\"", "RETURN"),
                params = listOf("REF_TGTS"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(refVar("REF_TGTS", isStr = true), "S_WRITER2")
        run(pb, store)

        store.enterFunction("MAINLOC")
        assertEquals(
            "hi", store.readStr("LOCALS", longArrayOf(0)),
            "LOCALS 的数组本体必须被别名写入（LocalVariableToken.cs:17-37）",
        )
        store.leaveFunction()
    }

    /**
     * 系统数组（`TARGET`）作 REF 实参：`COMMON.ERB:554`
     * `CALL MAKE_CHARA_LIST("TARGET列表", L_ID, TARGET, TARGET_CNT)` 的形态。
     * 原版 `TARGET` 是 `VariableToken`，`GetArray()` 返回全局列数组
     * （spec: VariableData.cs:2389-2417）；旧实现只查用户变量，`TARGET` 直接漏绑。
     */
    @Test
    fun `REF 形参可以绑到系统数组 TARGET`() {
        val pb = compile(
            Fn("MAINSYS", 0, arrayOf("@MAINSYS", "CALL S_WRITER3(TARGET)", "RETURN")),
            Fn(
                "S_WRITER3", 1,
                arrayOf("@S_WRITER3(REF_TGT)", "#DIM REF REF_TGT", "REF_TGT:0 = 7", "RETURN"),
                params = listOf("REF_TGT"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(refVar("REF_TGT"), "S_WRITER3")
        run(pb, store)

        assertEquals(
            7L, store.readInt("TARGET", longArrayOf(0)),
            "TARGET 的全局列数组必须被 REF 别名写入（VariableData.cs:2389-2417）",
        )
    }

    // ------------------------------------------------------------------
    // 3. 按**位**绑定：值参位的裸变量实参不参与 REF 绑定
    // ------------------------------------------------------------------

    @Test
    fun `按形参位绑定 - 值参位的裸变量实参不参与 REF 绑定`() {
        val pb = compile(
            Fn("MAIN3", 2, arrayOf("@MAIN3(SRC_ARR, SRC_ARR2)", "CALL MIXED(SRC_ARR, SRC_ARR2)", "RETURN")),
            Fn(
                "MIXED", 2,
                arrayOf("@MIXED(ARG, REF_TGT)", "#DIM REF REF_TGT", "REF_TGT:0 = ARG", "RETURN"),
                params = listOf("ARG", "REF_TGT"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("SRC_ARR"), "MAIN3")
        store.declareUserVar(intVar("SRC_ARR2"), "MAIN3")
        store.declareUserVar(refVar("REF_TGT"), "MIXED")
        store.enterFunction("MAIN3")
        store.writeInt("SRC_ARR", longArrayOf(0), 7)
        store.writeInt("SRC_ARR2", longArrayOf(0), 0)
        store.leaveFunction()
        run(pb, store)
        store.enterFunction("MAIN3")
        assertEquals(7L, store.readInt("SRC_ARR2", longArrayOf(0)), "REF 位（1 号）绑 SRC_ARR2；0 号 SRC_ARR 是值参位")
        assertEquals(7L, store.readInt("SRC_ARR", longArrayOf(0)), "值参位只是读值进 ARG，不得被 REF 写")
        store.leaveFunction()
    }

    // ------------------------------------------------------------------
    // 4. 位序 = 头部位序，不是 `#DIM` 声明序
    // ------------------------------------------------------------------

    @Test
    fun `形参位序按头部名而不是 DIM 声明序`() {
        // 头部 (ARG, REF_TGT)，但 #DIM 故意把 REF 声明在值参之前（对应 CHARA_LIST.ERB:4 的形态）
        val pb = compile(
            Fn("MAIN4", 2, arrayOf("@MAIN4(SRC_ARR, SRC_ARR2)", "CALL SHUFFLED(SRC_ARR, SRC_ARR2)", "RETURN")),
            Fn(
                "SHUFFLED", 2,
                arrayOf("@SHUFFLED(ARG, REF_TGT)", "#DIM REF REF_TGT", "#DIM VALUE_IN", "REF_TGT:0 = ARG", "RETURN"),
                params = listOf("ARG", "REF_TGT"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("SRC_ARR"), "MAIN4")
        store.declareUserVar(intVar("SRC_ARR2"), "MAIN4")
        store.declareUserVar(refVar("REF_TGT"), "SHUFFLED")
        store.declareUserVar(intVar("VALUE_IN"), "SHUFFLED")
        store.enterFunction("MAIN4")
        store.writeInt("SRC_ARR", longArrayOf(0), 9)
        store.leaveFunction()
        run(pb, store)
        store.enterFunction("MAIN4")
        assertEquals(9L, store.readInt("SRC_ARR2", longArrayOf(0)))
        assertEquals(9L, store.readInt("SRC_ARR", longArrayOf(0)), "按声明序绑会误写 0 号位 SRC_ARR")
        store.leaveFunction()
    }

    // ------------------------------------------------------------------
    // 4b. 多维 REF：目标各维长度随实参传入，被调按行主序压平
    // ------------------------------------------------------------------

    /**
     * 语料实况：`BODY_INFO/SHOW_PANTIES.ERB:68 #DIM カラーマトリクス, 5, 5` →
     * `:90 CALL SETCOLORMATRIX(カラーマトリクス, ...)`，被调 `:101 #DIM REF カラーマトリクス, 0, 0`
     * 体内 `:126-139` 写 `カラーマトリクス:i:j`。
     *
     * 原版 REF 绑的是 CLR `long[,]` 本体，形状随数组对象走（spec: ReferenceToken.cs:125-128；
     * 读写 `array[i, j]` spec: VariableData.cs:2389-2417；`GetLength(dimension)` spec:
     * ReferenceToken.cs:45-56）。本移植是压平存储 ⇒ 绑定点必须把调用方数组的 dims 一起带上
     * （`bindRef` → `RefUserVar.bindInt(array, dims)`）。
     *
     * 本用例锁 **裸多维数组实参**（`CALL MAT_WRITER(MAT)`）：原版 REF 位只取
     * `VariableTerm.Identifier.GetArray()`、不读值（spec: CalledFunction.cs:154-159 →
     * ReferenceToken.SetRef:125-128），所以裸 2D 变量合法。旧编译器对每个实参都
     * `evalTemp`，`VarRef` 造 0 长下标 ⇒ 运行期抛「变量 MAT 需要 2 个下标（实际 0）」，
     * `_CALL` 都到不了；`ErbProgramCompiler.patchRefArgEvals` 在 build() 把该位的值指令
     * 回填成常量（REF 绑定只认 `Program.callArgs` 的变量名），本条即回归锁。
     */
    @Test
    fun `2 维 REF 形参按调用方数组形状绑定并压平-裸数组实参`() {
        val pb = compile(
            Fn("MAIN2D", 1, arrayOf("@MAIN2D(MAT)", "CALL MAT_WRITER(MAT)", "RETURN")),
            Fn(
                "MAT_WRITER", 1,
                arrayOf("@MAT_WRITER(MAT_REF)", "#DIM REF MAT_REF, 0, 0", "MAT_REF:3:3 = 4242", "RETURN"),
                params = listOf("MAT_REF"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(DimSpec("MAT", false, intArrayOf(5, 5), isPrivate = true), "MAIN2D")
        store.declareUserVar(
            DimSpec("MAT_REF", false, intArrayOf(0, 0), isRef = true, isPrivate = true),
            "MAT_WRITER",
        )
        run(pb, store)
        assertEquals(0, store.callDepth(), "帧必须全部退出（enter/leave 配对）")
        store.enterFunction("MAIN2D")
        // 行主序：:3:3 → 3*5+3 = 18；别的格子必须不受影响
        assertEquals(4242L, store.readInt("MAT", longArrayOf(3, 3)))
        assertEquals(0L, store.readInt("MAT", longArrayOf(0, 3)))
        assertEquals(5, store.getLength("MAT", 1))
        store.leaveFunction()
    }

    /**
     * 带下标数组实参（`MAT:0:0`）同样绑**整个数组**：原版 `Identifier.GetArray()`
     * 不看 term 上的下标（spec: CalledFunction.cs:154-159），所以 `MAT` 与 `MAT:0:0`
     * 在 REF 位等价。本侧 `CallArg.name` 取变量名；REF 位的值指令（连同下标表达式）
     * 在 build() 回填成常量，不读元素也不检查下标。
     */
    @Test
    fun `2 维 REF 形参带下标数组实参仍绑整数组`() {
        val pb = compile(
            Fn("MAIN2DB", 1, arrayOf("@MAIN2DB(MAT)", "CALL MAT_WRITER(MAT:0:0)", "RETURN")),
            Fn(
                "MAT_WRITER", 1,
                arrayOf("@MAT_WRITER(MAT_REF)", "#DIM REF MAT_REF, 0, 0", "MAT_REF:1:2 = 77", "RETURN"),
                params = listOf("MAT_REF"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(DimSpec("MAT", false, intArrayOf(5, 5), isPrivate = true), "MAIN2DB")
        store.declareUserVar(
            DimSpec("MAT_REF", false, intArrayOf(0, 0), isRef = true, isPrivate = true),
            "MAT_WRITER",
        )
        run(pb, store)
        store.enterFunction("MAIN2DB")
        assertEquals(77L, store.readInt("MAT", longArrayOf(1, 2)), "REF 绑的是数组本体，与实参下标无关")
        store.leaveFunction()
    }

    /**
     * REF 位的带下标实参**不求值**：原版 `Identifier.GetArray()` 忽略 term 的 arguments，
     * 越界下标也不会被检查（spec: CalledFunction.cs:154-159）。旧实现对 `MAT:9:9` 先
     * `_EVAL_INT` → 「第 1 维下标 9 越界」；回填之后走 REF 绑定。
     */
    @Test
    fun `REF 位带下标实参不求值-越界下标不报错`() {
        val pb = compile(
            Fn("MAIN2DO", 1, arrayOf("@MAIN2DO(MAT)", "CALL MAT_WRITER(MAT:9:9)", "RETURN")),
            Fn(
                "MAT_WRITER", 1,
                arrayOf("@MAT_WRITER(MAT_REF)", "#DIM REF MAT_REF, 0, 0", "MAT_REF:0:1 = 55", "RETURN"),
                params = listOf("MAT_REF"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(DimSpec("MAT", false, intArrayOf(5, 5), isPrivate = true), "MAIN2DO")
        store.declareUserVar(
            DimSpec("MAT_REF", false, intArrayOf(0, 0), isRef = true, isPrivate = true),
            "MAT_WRITER",
        )
        run(pb, store)
        store.enterFunction("MAIN2DO")
        assertEquals(55L, store.readInt("MAT", longArrayOf(0, 1)))
        store.leaveFunction()
    }

    /**
     * 非 REF 位保持按值：带下标的 2D 实参照常取该元素（旧行为不变）。
     */
    @Test
    fun `非 REF 的 2 维形参按值取下标元素`() {
        val pb = compile(
            Fn("MAIN2DV", 1, arrayOf("@MAIN2DV(MAT)", "CALL MAT_READER(MAT:1:2)", "RETURN")),
            Fn(
                "MAT_READER", 1,
                arrayOf("@MAT_READER(VALUE_IN)", "#DIM VALUE_IN", "MAT_OUT = VALUE_IN", "RETURN"),
                params = listOf("VALUE_IN"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(DimSpec("MAT", false, intArrayOf(5, 5), isPrivate = true), "MAIN2DV")
        store.declareUserVar(intVar("VALUE_IN"), "MAT_READER")
        store.declareUserVar(intVar("MAT_OUT"), "MAT_READER")
        store.enterFunction("MAIN2DV")
        store.writeInt("MAT", longArrayOf(1, 2), 123)
        store.leaveFunction()
        run(pb, store)
        assertEquals(123L, store.userVar("MAT_OUT", "MAT_READER")!!.readInt(0, 0))
    }

    /**
     * 非 REF 位的**裸**多维数组仍按旧行为报错（原版在 `VariableTerm.GetIntValue` 上
     * 同样因缺下标抛 IndexOutOfRange，spec: VariableData.cs:115-118 `array[arguments[0],
     * arguments[1]]`）—— 回填只针对 REF 位，不能顺手把非 REF 位改成 0。
     */
    @Test
    fun `裸多维数组传给非 REF 形参仍报需要下标`() {
        val pb = compile(
            Fn("MAIN2DX", 1, arrayOf("@MAIN2DX(MAT)", "CALL MAT_READER(MAT)", "RETURN")),
            Fn(
                "MAT_READER", 1,
                arrayOf("@MAT_READER(VALUE_IN)", "#DIM VALUE_IN", "MAT_OUT = VALUE_IN", "RETURN"),
                params = listOf("VALUE_IN"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(DimSpec("MAT", false, intArrayOf(5, 5), isPrivate = true), "MAIN2DX")
        store.declareUserVar(intVar("VALUE_IN"), "MAT_READER")
        store.declareUserVar(intVar("MAT_OUT"), "MAT_READER")
        val e = assertFailsWith<CodeEE> { run(pb, store) }
        assertEquals(true, e.message!!.contains("需要 2 个下标"), "实际=${e.message}")
    }

    /**
     * 字符串通道（`#DIMS REF`）的裸 2D 数组同理：值指令是 `_EVAL_STR`，回填成空串常量，
     * 绑定走 `CallArg.name` 的 IntArray（StringTable 驻留下标）别名。
     */
    @Test
    fun `2 维字符串 REF 形参裸数组实参`() {
        val pb = compile(
            Fn("MAINS2D", 1, arrayOf("@MAINS2D(SMAT)", "CALL SMAT_WRITER(SMAT)", "RETURN")),
            Fn(
                "SMAT_WRITER", 1,
                arrayOf("@SMAT_WRITER(SMAT_REF)", "#DIMS REF SMAT_REF, 0, 0", "SMAT_REF:1:2 = \"x\"", "RETURN"),
                params = listOf("SMAT_REF"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(DimSpec("SMAT", true, intArrayOf(3, 4), isPrivate = true), "MAINS2D")
        store.declareUserVar(
            DimSpec("SMAT_REF", true, intArrayOf(0, 0), isRef = true, isPrivate = true),
            "SMAT_WRITER",
        )
        run(pb, store)
        store.enterFunction("MAINS2D")
        // 语句层的字符串字面量在本夹具里保留了外层引号（解析面口径，与本用例无关）；
        // 去掉引号后比对内容，证明被调写入落到调用方数组本体。
        assertEquals("x", store.readStr("SMAT", longArrayOf(1, 2)).trim('"'))
        store.leaveFunction()
    }

    /**
     * 省略 REF 形参位 = **调用点硬错误**：原版 `CalledFunction.ConvertArg` 对被调全部
     * 形参位校验，REF 位缺实参即 CanNotOmitRefArg「@函数的第N个参数是引用形式因此无法
     * 省略」（spec: CalledFunction.cs:150-153，Process.cs:1879-1884 转 CodeEE）——
     * 旧实现只看 `ProcessState.cs:437` 的写回跳过，把错误推迟到被调首次读时的
     * EmptyRefVar，时点与文案都可证偏离。尾部整位被裁掉的省略（实参数不足）同样命中
     * （ConvertArg 循环覆盖 `topLabel.Arg.Length` 全部位）。
     */
    @Test
    fun `省略 REF 形参位在调用点报无法省略`() {
        val pb = compile(
            Fn("MAIN9", 1, arrayOf("@MAIN9(SRC_ARR)", "CALL OMIT_REF(1)", "RETURN")),
            Fn(
                "OMIT_REF", 2,
                arrayOf("@OMIT_REF(C_ID, REF_TGT)", "#DIM C_ID", "#DIM REF REF_TGT", "#DIM OUT", "OUT = REF_TGT:0", "RETURN"),
                params = listOf("C_ID", "REF_TGT"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("SRC_ARR"), "MAIN9")
        store.declareUserVar(intVar("C_ID"), "OMIT_REF")
        store.declareUserVar(refVar("REF_TGT"), "OMIT_REF")
        store.declareUserVar(intVar("OUT"), "OMIT_REF")
        val e = assertFailsWith<CodeEE> { run(pb, store) }
        assertEquals(
            "@OMIT_REF的第2个参数是引用形式因此无法省略",
            e.message,
            "实际=${e.message}",
        )
    }

    /** 非 REF 位的省略保持既有宽松口径（只 REF 位升级为调用点硬错误）。 */
    @Test
    fun `省略非 REF 形参位仍不报错`() {
        val pb = compile(
            Fn("MAIN9B", 1, arrayOf("@MAIN9B(SRC_ARR)", "CALL OMIT_VAL(1)", "RETURN")),
            Fn(
                "OMIT_VAL", 2,
                arrayOf("@OMIT_VAL(C_ID, OUT)", "#DIM C_ID", "#DIM OUT", "OUT = 1", "RETURN"),
                params = listOf("C_ID", "OUT"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("SRC_ARR"), "MAIN9B")
        store.declareUserVar(intVar("C_ID"), "OMIT_VAL")
        store.declareUserVar(intVar("OUT"), "OMIT_VAL")
        run(pb, store)
        assertEquals(0, store.callDepth())
    }

    /**
     * REF 位传**非变量实参**：原版 ConvertArg 要求实参是 `VariableTerm` 且维度 ≥ 1
     * （spec: CalledFunction.cs:154-158），否则 RequireArrayBecauseRefArg「@函数的第N个
     * 参数必须是是引用形式的数组变量」。旧实现静默跳过绑定、推迟到 EmptyRefVar。
     */
    @Test
    fun `非变量实参传给 REF 形参报必须是数组变量`() {
        val pb = compile(
            Fn("MAIN10", 1, arrayOf("@MAIN10(SRC_ARR)", "CALL TGT(1 + 2)", "RETURN")),
            Fn(
                "TGT", 1,
                arrayOf("@TGT(REF_TGT)", "#DIM REF REF_TGT", "REF_TGT:0 = 1", "RETURN"),
                params = listOf("REF_TGT"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("SRC_ARR"), "MAIN10")
        store.declareUserVar(refVar("REF_TGT"), "TGT")
        val e = assertFailsWith<CodeEE> { run(pb, store) }
        assertEquals(
            "@TGT的第1个参数必须是是引用形式的数组变量",
            e.message,
            "实际=${e.message}",
        )
    }

    /**
     * 常量数组作 REF 实参：`MatchType` 的 `rother.IsConst` 分支
     * （spec: ReferenceToken.cs:143-147）→ NumberOfArg 包装「@函数的第N个参数:无法引用常量」。
     */
    @Test
    fun `常量数组传给 REF 形参报无法引用常量`() {
        val pb = compile(
            Fn("MAIN11", 1, arrayOf("@MAIN11(SRC_ARR)", "CALL TGT(CONST_ARR)", "RETURN")),
            Fn(
                "TGT", 1,
                arrayOf("@TGT(REF_TGT)", "#DIM REF REF_TGT", "REF_TGT:0 = 1", "RETURN"),
                params = listOf("REF_TGT"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("SRC_ARR"), "MAIN11")
        store.declareUserVar(DimSpec("CONST_ARR", false, intArrayOf(5), isConst = true), "MAIN11")
        store.declareUserVar(refVar("REF_TGT"), "TGT")
        val e = assertFailsWith<CodeEE> { run(pb, store) }
        assertEquals("@TGT的第1个参数:无法引用常量", e.message, "实际=${e.message}")
    }

    /**
     * 角色变量作 REF 实参：`MatchType` 的 `IsCharacterData && !allowChara` 分支
     * （spec: ReferenceToken.cs:153-157，ConvertArg 恒传 allowChara:false）→
     * 「@函数的第N个参数:无法引用角色变量」。
     */
    @Test
    fun `角色变量传给 REF 形参报无法引用角色变量`() {
        val pb = compile(
            Fn("MAIN12", 1, arrayOf("@MAIN12(SRC_ARR)", "CALL TGT(C_LIST)", "RETURN")),
            Fn(
                "TGT", 1,
                arrayOf("@TGT(REF_TGT)", "#DIM REF REF_TGT", "REF_TGT:0 = 1", "RETURN"),
                params = listOf("REF_TGT"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("SRC_ARR"), "MAIN12")
        store.declareUserVar(DimSpec("C_LIST", false, intArrayOf(3), isCharaData = true))
        store.declareUserVar(refVar("REF_TGT"), "TGT")
        val e = assertFailsWith<CodeEE> { run(pb, store) }
        assertEquals("@TGT的第1个参数:无法引用角色变量", e.message, "实际=${e.message}")
    }

    /**
     * 整型/字符串不符：`MatchType` 的 `IsInteger` 不同分支（spec: ReferenceToken.cs:158-162）
     * →「@函数的第N个参数:无法引用类型不同的变量」。
     */
    @Test
    fun `字符串数组传给整型 REF 形参报类型不同`() {
        val pb = compile(
            Fn("MAIN13", 1, arrayOf("@MAIN13(SRC_STRS)", "CALL TGT(SRC_STRS)", "RETURN")),
            Fn(
                "TGT", 1,
                arrayOf("@TGT(REF_TGT)", "#DIM REF REF_TGT", "REF_TGT:0 = 1", "RETURN"),
                params = listOf("REF_TGT"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(strVar("SRC_STRS"), "MAIN13")
        store.declareUserVar(refVar("REF_TGT"), "TGT")
        val e = assertFailsWith<CodeEE> { run(pb, store) }
        assertEquals("@TGT的第1个参数:无法引用类型不同的变量", e.message, "实际=${e.message}")
    }

    /**
     * 维度不符：`MatchType` 的 `Dimension` 不同分支（spec: ReferenceToken.cs:163-167）
     * →「@函数的第N个参数:无法引用维度不同的数组变量」。旧实现不查维度，把 1 维实参
     * 绑进 2 维 REF 后按目标总长压平访问 —— 静默越出声明形状。
     */
    @Test
    fun `一维数组传给二维 REF 形参报维度不同`() {
        val pb = compile(
            Fn("MAIN14", 1, arrayOf("@MAIN14(SRC_ARR)", "CALL TGT(SRC_ARR)", "RETURN")),
            Fn(
                "TGT", 1,
                arrayOf("@TGT(REF_TGT2)", "#DIM REF REF_TGT2, 0, 0", "REF_TGT2:0:0 = 1", "RETURN"),
                params = listOf("REF_TGT2"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("SRC_ARR"), "MAIN14")
        store.declareUserVar(DimSpec("REF_TGT2", false, intArrayOf(0, 0), isRef = true, isPrivate = true), "TGT")
        val e = assertFailsWith<CodeEE> { run(pb, store) }
        assertEquals("@TGT的第1个参数:无法引用维度不同的数组变量", e.message, "实际=${e.message}")
    }

    /**
     * REF 转发时源 REF 未绑定：原版对源 REF 取 `GetArray()` 未绑即抛 EmptyRefVar
     * （spec: ReferenceToken.cs:116-123，SetTransporter 路径 UserDefinedFunctionArgument.cs:57）
     * →「引用类型变量”SRC“缺少引用对象」。旧实现静默跳过、新 REF 留空。
     */
    @Test
    fun `未绑定的 REF 转发给 REF 形参报缺少引用对象`() {
        val pb = compile(
            Fn("MAIN15", 1, arrayOf("@MAIN15(SRC_REF)", "CALL TGT(SRC_REF)", "RETURN")),
            Fn(
                "TGT", 1,
                arrayOf("@TGT(REF_TGT)", "#DIM REF REF_TGT", "REF_TGT:0 = 1", "RETURN"),
                params = listOf("REF_TGT"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(refVar("SRC_REF"), "MAIN15")
        store.declareUserVar(refVar("REF_TGT"), "TGT")
        val e = assertFailsWith<CodeEE> { run(pb, store) }
        assertEquals(
            "引用类型变量\"SRC_REF\"缺少引用对象",
            e.message,
            "实际=${e.message}",
        )
    }

    // ------------------------------------------------------------------
    // 5b. 同一函数递归 + REF 形参（2026-10-29 真机红）
    // ------------------------------------------------------------------

    /**
     * 真机红：`PLAY_GOMOKU.ERB` 的 `GOMOKU_ALPHABETA` 带 `#DIM REF BOARD` 并用
     * `VAL = GOMOKU_ALPHABETA(BOARD, DEPTH - 1, …)` 自递归 ⇒「引用类型变量"BOARD"缺少引用对象」。
     *
     * 根因：同一函数递归时 caller 与 callee 是**同一个** [com.eratw.port.core.vars.RefUserVar]
     * 实例，`enterFunction` 的 `scopeIn()` 先把 target 清空；旧实现把 REF 实参的源解析
     * 放在进帧之后（`bindCallArgs`），读到的就是空引用。修法：进帧之前解析并留快照
     * （`resolveRefArgsBeforeEnter` / `RefSource.isRefSource`）。
     *
     * 断言：三层递归都把值写进**调用方**那个数组（别名随调用链存活），且帧全部退出。
     */
    @Test
    fun `同一函数递归时 REF 形参仍绑调用方数组`() {
        val pb = compile(
            Fn("MAIN16", 1, arrayOf("@MAIN16(SRC_ARR)", "CALL REC(SRC_ARR, 3)", "RETURN")),
            Fn(
                "REC", 2,
                arrayOf(
                    "@REC(REF_TGT, LV)",
                    "#DIM REF REF_TGT",
                    "#DIM LV",
                    "REF_TGT:0 = REF_TGT:0 + 1",
                    "SIF LV > 1",
                    "\tCALL REC(REF_TGT, LV - 1)",
                    "RETURN",
                ),
                params = listOf("REF_TGT", "LV"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("SRC_ARR"), "MAIN16")
        store.declareUserVar(refVar("REF_TGT"), "REC")
        store.declareUserVar(intVar("LV", 1), "REC")
        run(pb, store)
        assertEquals(0, store.callDepth(), "帧必须全部退出（enter/leave 配对）")
        store.enterFunction("MAIN16")
        assertEquals(
            3L, store.readInt("SRC_ARR", longArrayOf(0)),
            "递归每一层都必须写回同一个数组（ReferenceToken.cs:125-128 的别名语义）",
        )
        store.leaveFunction()
    }

    // ------------------------------------------------------------------
    // 5. 无形参名信息 = 不绑（宁 EmptyRefVar，也不猜）
    // ------------------------------------------------------------------

    @Test
    fun `缺形参名时不绑 REF - 保持 EmptyRefVar`() {
        val pb = compile(
            Fn("MAIN5", 1, arrayOf("@MAIN5(SRC_ARR)", "CALL NOINFO(SRC_ARR)", "RETURN")),
            Fn("NOINFO", 1, arrayOf("@NOINFO(REF_TGT)", "#DIM REF REF_TGT", "REF_TGT:0 = 1", "RETURN")),
            // params 故意不喂
        )
        val store = VariableStore()
        store.declareUserVar(intVar("SRC_ARR"), "MAIN5")
        store.declareUserVar(refVar("REF_TGT"), "NOINFO")
        val rt = ProcessRuntime(pb.program, store, FunctionTable(listOf(pb.meta("NOINFO", argInt = 1), pb.meta("MAIN5", argInt = 1))), VmConfig())
        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals(true, e.message!!.contains("EmptyRefVar"), "实际=${e.message}")
    }

    // ------------------------------------------------------------------
    // 6. 具名形参的**值**绑定（原版 SetValue，ProcessState.cs:443-450）
    // ------------------------------------------------------------------

    @Test
    fun `具名整型形参从实参位写回`() {
        val pb = compile(
            Fn("MAIN7", 1, arrayOf("@MAIN7(SRC_ARR)", "CALL NAMED(SRC_ARR)", "RETURN")),
            Fn(
                "NAMED", 2,
                arrayOf("@NAMED(C_ID, ARG)", "#DIM C_ID", "#DIM OUT", "OUT = C_ID", "RETURN"),
                params = listOf("C_ID", "ARG"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("SRC_ARR"), "MAIN7")
        store.declareUserVar(intVar("C_ID"), "NAMED")
        store.declareUserVar(intVar("OUT"), "NAMED")
        store.enterFunction("MAIN7")
        store.writeInt("SRC_ARR", longArrayOf(0), 33)
        store.leaveFunction()
        run(pb, store)
        assertEquals(33L, store.userVar("OUT", "NAMED")!!.readInt(0, 0), "具名形参 C_ID 必须收到 0 号实参")
    }
    // ------------------------------------------------------------------
    // 6. 省略位写装载期隐式默认（`@F(C_ID, ARG:0)` 只传 1 个实参 ⇒ ARG[0] = 0）
    // ------------------------------------------------------------------

    @Test
    fun `省略的 ARG 位按装载期隐式默认写 0 - 具名位照常写`() {
        val pb = compile(
            Fn("MAIN8", 1, arrayOf("@MAIN8(SRC_ARR)", "CALL TWO(SRC_ARR)", "RETURN")),
            Fn(
                "TWO", 2,
                arrayOf("@TWO(C_ID, ARG:0)", "#DIM C_ID", "#DIM OUT", "#DIM OUT2", "OUT = ARG", "OUT2 = C_ID", "RETURN"),
                params = listOf("C_ID", "ARG:0"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("SRC_ARR"), "MAIN8")
        store.declareUserVar(intVar("C_ID"), "TWO")
        store.declareUserVar(intVar("OUT"), "TWO")
        store.declareUserVar(intVar("OUT2"), "TWO")
        store.enterFunction("MAIN8")
        store.writeInt("SRC_ARR", longArrayOf(0), 33)
        store.leaveFunction()
        store.enterFunction("TWO")
        store.writeInt("ARG", longArrayOf(0), 5)
        store.leaveFunction()
        run(pb, store)
        // 装载期给 ARG/ARGS/私有形参的 NullTerm 补了 0/"" 的 Def[i]
        // （ErbLoader.cs:714-722）；ConvertArg 用 Def[i] 顶替省略位（CalledFunction.cs:165-173），
        // 随后 IntoFunction 照常 SetValue（ProcessState.cs:437-450）⇒ 旧值 5 被覆盖为 0。
        assertEquals(0L, store.userVar("OUT", "TWO")!!.readInt(0, 0), "省略的 ARG 位应写装载期默认 0（ErbLoader.cs:714-722）")
        assertEquals(33L, store.userVar("OUT2", "TWO")!!.readInt(0, 0), "具名位 C_ID 收到 0 号实参")
    }
    // ------------------------------------------------------------------
    // 6. 编译产物自检：调用点登记 + 形参名进函数表
    // ------------------------------------------------------------------
    @Test
    fun `编译期在调用点登记变量实参表`() {
        val pb = compile(
            Fn("MAIN6", 1, arrayOf("@MAIN6(SRC_ARR)", "CALL TGT_WRITER(SRC_ARR)", "RETURN")),
            Fn("TGT_WRITER", 1, arrayOf("@TGT_WRITER(REF_TGT)", "#DIM REF REF_TGT", "REF_TGT:0 = 1", "RETURN"), params = listOf("REF_TGT")),
        )
        val calls = (0 until pb.program.instructionCount).filter { pb.program.op(it) == Opcode._CALL }
        val registered = calls.mapNotNull { pc -> pb.program.callArgsAt(pc) }
        assertEquals(1, registered.size, "本程序只有一个用户函数调用点")
        val argc = registered[0].single()
        assertEquals(0, argc.position)
        assertEquals(0, argc.bucket, "整型实参进 int 桶 0 号槽")
        assertEquals("SRC_ARR", argc.name, "裸变量实参登记变量名（REF 绑定用）")
        assertEquals(listOf("REF_TGT"), pb.program.paramNames(pb.index("TGT_WRITER")))
    }
}
