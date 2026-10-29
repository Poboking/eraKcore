package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * SAVE-02：用户函数实参绑定的 ConvertArg 兼容语义（`CalledFunction.cs:128-202`）。
 *
 * ## 原版口径（逐条 `文件:行号`，reference/emuera-cs 只读）
 *
 * 进入被调帧之前 `CalledFunction.ConvertArg` 对**全部头部形参位**逐位校验
 * （:136-200）：
 * * str 实参 → int 形参：`CanNotConvertStrToInt`（:176-179），无配置开关；
 * * int 实参 → str 形参：`CompatiFuncArgAutoConvert` 为真时取内建 TOSTR 包一层
 *   `FunctionMethodTerm`（:181-198），为假时 `CanNotConvertIntToStr`（:181-185）；
 * * 省略实参：有默认值取 `TopLabel.Def[i]`（:167）；**`ARG`/`ARGS`/私有形参即使没写
 *   `= ` 也有装载期隐式默认 0/""**（`ErbLoader.cs:714-722`，NullTerm 按形参类型折成
 *   `SingleLongTerm(0)`/`SingleStrTerm("")`）；无默认值（只可能是全局/系统变量形参，
 *   默认值只允许出现在 ARG/ARGS/私有形参上，`ErbLoader.cs:729-732`）且
 *   `CompatiFuncArgOptional` 关 ⇒ `CanNotOmitArgWithMessage`（:168-172），开 ⇒
 *   `Arguments[i] == null` 跳过、形参保留旧值（:437）；
 * * 省略 REF 形参位：`CanNotOmitRefArg`（:150-153），该判在 :147 先于省略分支 :165。
 *
 * 两个开关的取值 = `Config.cs:157/:159` 的进程级字段（`Config.SetConfig` 在
 * :374-375 从条目表回填），出厂默认均 false（`ConfigData.cs:108-109`）；本作
 * `emuera.config` 是 `AUTO TOSTR …:YES`（:62）/ `ALLOW ARGUMENTS OMISSION …:NO`（:61）。
 *
 * ## 本侧落地形态
 *
 * `ProcessRuntime.bindCallArgs` 在 `onFunctionEnter`（VM 切帧前）逐位写回时做同一组分派；
 * 两个开关经 `ConfigMethods.store(store.aux, host)` **现读**（不在注册期捕获），
 * 测试用 `BuiltinHost.configOverrides` 的英文标签形态注入（与 `emuera.config` 落盘同形）。
 *
 * 文案取本作 zhs 语言表：`lang.emuera-zhs.xml:684/685/686`（与既有
 * CanNotOmitRefArg 同源）；`{2}` = `Config.GetConfigName` 的 `Text + "/" + EngText`
 * （`Config.cs:291-294` → `ConfigData.cs:204-214`，两段注册期大写化）。
 */
class FuncArgConvertTest {

    private companion object {
        /** `ConfigData.cs:109` 的英文标签（emuera.config 落盘形态，GAME 组认英文）。 */
        const val AUTO_TOSTR_KEY = "AUTO TOSTR CONVERSION FOR USER FUNCTION ARGUMENTS"

        /** `ConfigData.cs:108` 的英文标签。 */
        const val OMIT_ARGS_KEY = "ALLOW ARGUMENTS OMISSION FOR USER FUNCTIONS"

        /** `Config.cs:291-294` 的双语名（Text + "/" + EngText，注册期大写化）。 */
        const val AUTO_TOSTR_NAME =
            "ユーザー関数の引数に自動的にTOSTRを補完する/AUTO TOSTR CONVERSION FOR USER FUNCTION ARGUMENTS"
        const val OMIT_ARGS_NAME =
            "ユーザー関数の全ての引数の省略を許可する/ALLOW ARGUMENTS OMISSION FOR USER FUNCTIONS"
    }

    // ------------------------------------------------------------------
    // 构建工具（与 Compile 包的 DefaultArgTest / RefArgBindingTest 同款装置）
    // ------------------------------------------------------------------

    /** 一个待编译函数：名字 + 完整切片（首行是 `@行`）。 */
    private data class Fn(val name: String, val lines: Array<String>)

    /** 编译产物 + 函数下标查询（`ErbProgramCompiler.functions` 顺序 == `Program.functions`）。 */
    private class Pb(val program: Program, val names: List<String>) {
        fun index(name: String): Int =
            program.functions.indices.first { program.functions[it].name.equals(name, ignoreCase = true) }
    }

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("M1", OperandType.STR, 1)
            .declareVariable("M2", OperandType.STR, 1)
            .declareVariable("M3", OperandType.STR, 1)
            .declareVariable("NUM", OperandType.INT, 1)
            .declareVariable("GNUM", OperandType.INT, 1)
            .declareVariable("OUT", OperandType.INT, 1)
            .declareVariable("OUTS", OperandType.STR, 1)
            // REF 用例只做调用点校验，形参在体内不读，仍登记以贴近真实声明面
            .declareVariable("C_ID", OperandType.INT, 1)
            .declareVariable("REF_TGT", OperandType.INT, 1),
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
            p.parseLogicalLine(LogicalLine(kind, "funcarg.erb", i + 1, text))
        }
    }

    private fun compile(vararg fns: Fn): Pb {
        val parsed = fns.map { parse(*it.lines) }
        val labels = parsed.map { stmts ->
            stmts.filterIsInstance<FuncLabelStmt>().firstOrNull() ?: error("切片缺 @行")
        }
        val compiler = ErbProgramCompiler()
        val known = fns.mapTo(HashSet()) { it.name.uppercase() }
        for ((i, f) in fns.withIndex()) {
            compiler.compileFunction(
                FuncSig(
                    name = f.name,
                    arity = labels[i].paramNames.size,
                    paramNames = labels[i].paramRefs,
                ),
                parsed[i],
                known,
            )
        }
        return Pb(compiler.build(fns.first().name), fns.map { it.name })
    }

    /**
     * 装配运行时：`configOverrides` 即宿主侧的配置覆盖表（`BuiltinHost` 契约），
     * 与 `emuera.config` 的英文标签/值同形，经 `ConfigMethods.storeFromHost` 落进
     * 本 VM 的条目表（与被测的现读通道同一条路）。
     */
    private fun runtime(
        pb: Pb,
        store: VariableStore,
        overrides: Map<String, String> = emptyMap(),
    ): ProcessRuntime {
        val metas = pb.names.map { name ->
            val index = pb.index(name)
            val arity = pb.program.functions[index].arity
            FuncMeta(
                index = index,
                key = name,
                argIntCount = arity,
                argStrCount = arity,
                defIndex = index,
            )
        }
        val host = object : BuiltinHost {
            override val configOverrides: Map<String, String> = overrides
        }
        return ProcessRuntime(pb.program, store, FunctionTable(metas), VmConfig(), builtinHost = host)
    }

    private fun intVar(name: String) = DimSpec(name, false, intArrayOf(1), isPrivate = true)
    private fun strVar(name: String) = DimSpec(name, true, intArrayOf(1), isPrivate = true)
    private fun refVar(name: String) = DimSpec(name, false, intArrayOf(0), isRef = true, isPrivate = true)

    // ------------------------------------------------------------------
    // 1. int 实参 → str 形参：CompatiFuncArgAutoConvert 为真时自动 TOSTR
    // ------------------------------------------------------------------

    /** 三个字符串形参位分别收 123 / -7 / 0（正数、负数、零的同一条 `Long.toString` 口径）。 */
    private fun convertProgram(): Pb = compile(
        Fn("MAIN", arrayOf("@MAIN", "CALL CALLEE(123, -7, 0)", "RETURN")),
        Fn("CALLEE", arrayOf("@CALLEE(M1, M2, M3)", "RETURN")),
    )

    private fun convertStore(): VariableStore {
        val store = VariableStore()
        for (n in listOf("M1", "M2", "M3")) store.declareUserVar(strVar(n), "CALLEE")
        return store
    }

    @Test
    fun `int 实参自动 TOSTR 到字符串形参 - 正数负数零`() {
        val store = convertStore()
        val rt = runtime(convertProgram(), store, mapOf(AUTO_TOSTR_KEY to "YES"))
        rt.execute()
        assertEquals("123", store.userVar("M1", "CALLEE")!!.readStr(0, 0), "CalledFunction.cs:181-198 自动 TOSTR")
        assertEquals("-7", store.userVar("M2", "CALLEE")!!.readStr(0, 0))
        assertEquals("0", store.userVar("M3", "CALLEE")!!.readStr(0, 0))
        assertEquals(0, store.callDepth(), "帧必须全部退出（enter/leave 配对）")
    }

    /** 关闭自动转换（出厂默认 `ConfigData.cs:109` = false）⇒ 调用点报 CanNotConvertIntToStr。 */
    @Test
    fun `自动 TOSTR 关闭时 int 实参到字符串形参报错`() {
        val store = convertStore()
        val e = assertFailsWith<CodeEE> { runtime(convertProgram(), store).execute() }
        assertEquals(
            "不能将\"@CALLEE\"的第1个参数从整转换为字符串型型" +
                "(此警告可以用兼容性配置「$AUTO_TOSTR_NAME」来无视)",
            e.message,
            "实际=${e.message}",
        )
    }

    /** `ARG`/`ARGS` 形参同样按形参类型分派：`ARGS:0` 收 int 实参 → 自动 TOSTR。 */
    @Test
    fun `ARGS 形参位收 int 实参时自动 TOSTR`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL SYS(42)", "RETURN")),
            Fn("SYS", arrayOf("@SYS(ARGS:0)", "#DIMS OUTS", "OUTS '= ARGS:0", "RETURN")),
        )
        val store = VariableStore()
        store.declareUserVar(strVar("OUTS"), "SYS")
        runtime(pb, store, mapOf(AUTO_TOSTR_KEY to "YES")).execute()
        assertEquals("42", store.userVar("OUTS", "SYS")!!.readStr(0, 0))
    }

    // ------------------------------------------------------------------
    // 2. str 实参 → int 形参：无配置开关，调用点即拒绝（:176-179）
    // ------------------------------------------------------------------

    @Test
    fun `字符串实参到整型形参报错 - 自动 TOSTR 开着也拒绝`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL NUMONLY(\"abc\")", "RETURN")),
            Fn("NUMONLY", arrayOf("@NUMONLY(NUM)", "RETURN")),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("NUM"), "NUMONLY")
        val e = assertFailsWith<CodeEE> {
            runtime(pb, store, mapOf(AUTO_TOSTR_KEY to "YES")).execute()
        }
        assertEquals(
            "不能将\"@NUMONLY\"的第1个参数从字符串型转换为整型",
            e.message,
            "实际=${e.message}",
        )
    }

    // ------------------------------------------------------------------
    // 3. 省略位：隐式默认（ARG/ARGS/私有形参）与 CompatiFuncArgOptional 两种取值
    // ------------------------------------------------------------------

    /**
     * `@OPT(ARG, ARG:1)` 的 1 号位省略：ARG 形参有装载期隐式默认 0（ErbLoader.cs:714-722），
     * 不论 CompatiFuncArgOptional 取值都写 0（不报错、也不沿用旧值）。
     */
    @Test
    fun `省略 ARG 形参写隐式默认 0`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL OPT(1)", "RETURN")),
            Fn("OPT", arrayOf("@OPT(ARG, ARG:1)", "#DIM OUT", "OUT = ARG:1", "RETURN")),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("OUT"), "OPT")
        val rt = runtime(pb, store)
        // 预置 ARG[1] = 42：隐式默认必须把它覆盖成 0（原版 Def[1] = SingleLongTerm(0)）。
        store.enterFunction("OPT")
        store.writeInt("ARG", longArrayOf(1), 42)
        store.leaveFunction()
        rt.execute()
        assertEquals(0L, store.userVar("OUT", "OPT")!!.readInt(0, 0), "省略位必须写隐式默认 0")
        assertEquals(0, store.callDepth())
    }

    /**
     * 全局用户变量形参没有默认值（默认值只允许 ARG/ARGS/私有形参，ErbLoader.cs:729-732）：
     * CompatiFuncArgOptional 关 ⇒ CanNotOmitArgWithMessage（CalledFunction.cs:168-172）。
     */
    private fun globalOmitProgram(): Pb = compile(
        Fn("MAIN", arrayOf("@MAIN", "CALL GOPT(1)", "RETURN")),
        Fn("GOPT", arrayOf("@GOPT(ARG, GNUM)", "#DIM OUT", "OUT = GNUM", "RETURN")),
    )

    private fun globalOmitStore(): VariableStore {
        val store = VariableStore()
        // 全局用户变量（非私有）：形参位上不可能有默认值
        store.declareUserVar(DimSpec("GNUM", false, intArrayOf(1)))
        store.declareUserVar(intVar("OUT"), "GOPT")
        store.writeInt("GNUM", longArrayOf(0), 42)
        return store
    }

    @Test
    fun `省略无默认值形参且开关为关时报不能省略`() {
        val e = assertFailsWith<CodeEE> { runtime(globalOmitProgram(), globalOmitStore()).execute() }
        assertEquals(
            "@GOPT的第2个参数不能省略(此警告可以用兼容性配置「$OMIT_ARGS_NAME」来无视)",
            e.message,
            "实际=${e.message}",
        )
    }

    @Test
    fun `省略无默认值形参且开关为开时沿用旧值`() {
        val store = globalOmitStore()
        runtime(globalOmitProgram(), store, mapOf(OMIT_ARGS_KEY to "YES")).execute()
        assertEquals(42L, store.userVar("OUT", "GOPT")!!.readInt(0, 0), "开关为开时省略位不写，形参保留旧值")
        assertEquals(0, store.callDepth())
    }

    // ------------------------------------------------------------------
    // 4. REF 位：省略仍是硬错误，不受 CompatiFuncArgOptional 影响（:147 先于 :165）
    // ------------------------------------------------------------------

    @Test
    fun `省略 REF 形参位在开关为开时仍报错`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL REFOPT(1)", "RETURN")),
            Fn("REFOPT", arrayOf("@REFOPT(C_ID, REF_TGT)", "RETURN")),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("C_ID"), "REFOPT")
        store.declareUserVar(refVar("REF_TGT"), "REFOPT")
        val e = assertFailsWith<CodeEE> {
            runtime(pb, store, mapOf(OMIT_ARGS_KEY to "YES")).execute()
        }
        assertEquals(
            "@REFOPT的第2个参数是引用形式因此无法省略",
            e.message,
            "实际=${e.message}",
        )
    }

    // ------------------------------------------------------------------
    // 5. 类型一致路径不变：str → str、int → int 照旧逐位写回
    // ------------------------------------------------------------------

    @Test
    fun `类型一致的实参照旧逐位写回`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL CALLEE(\"abc\", 5)", "RETURN")),
            Fn("CALLEE", arrayOf("@CALLEE(M1, NUM)", "RETURN")),
        )
        val store = VariableStore()
        store.declareUserVar(strVar("M1"), "CALLEE")
        store.declareUserVar(intVar("NUM"), "CALLEE")
        runtime(pb, store).execute()
        assertEquals("abc", store.userVar("M1", "CALLEE")!!.readStr(0, 0))
        assertEquals(5L, store.userVar("NUM", "CALLEE")!!.readInt(0, 0))
        assertEquals(0, store.callDepth())
    }

    // ------------------------------------------------------------------
    // 6. CALLF 家族的宿主 API 通道（callF / tryCallF）同样按**位置**绑参
    // ------------------------------------------------------------------

    /**
     * 脚本里的 `CALLF` 编成 `_CALL` + `CALLF_FLAG`，走 [ProcessRuntime.bindCallArgs]
     * 逐位绑定；但 [ProcessRuntime.callF] / [ProcessRuntime.tryCallF] 这条**宿主 API**
     * 通道走的是 [ProcessRuntime.executeMethod] → 根级建帧。
     *
     * 该通道交进来的 `List<Value?>` 位置完整，若只按 int/str **类型桶**绑，实参类型序与
     * 形参类型序交叉时整段错位**且静默**：`callF("MTH", [5, "s"])` 打到 `@MTH(M1, M2)`
     * 会把 `"s"` 写进 M1、M2 留空。原版 `ConvertArg` 只有「形参位 i ↔ `srcArgs[i]`」
     * 一种对位方式（CalledFunction.cs:136-200），没有分桶这一步。
     */
    @Test
    fun `callF 混合类型实参按位置绑定 - 整型实参落到字符串形参位自动 TOSTR`() {
        val pb = compile(
            Fn("MTH", arrayOf("@MTH(M1, M2)", "RETURN")),
        )
        val store = VariableStore()
        store.declareUserVar(strVar("M1"), "MTH")
        store.declareUserVar(strVar("M2"), "MTH")
        methodRuntime(pb, store, mapOf(AUTO_TOSTR_KEY to "YES"))
            .callF("MTH", listOf(Value.IntV(5), Value.StrV("s")))
        assertEquals("5", store.userVar("M1", "MTH")!!.readStr(0, 0), "0 号位实参是 5 ⇒ M1 收 TOSTR(\"5\")")
        assertEquals("s", store.userVar("M2", "MTH")!!.readStr(0, 0), "1 号位实参是 \"s\" ⇒ M2 收 \"s\"")
    }

    /**
     * 交叉类型下按位置绑定会**当场**报 CanNotConvertStrToInt（zhs:685）——
     * 类型桶口径把 str 实参塞给 `ARGS` 位、把 int 实参塞给 `ARG` 位，两个都不报错，
     * 于是错位静默通过（原版按位必报）。
     */
    @Test
    fun `callF 混合类型实参按位置绑定 - 字符串实参落到整型形参位报错`() {
        val pb = compile(
            Fn("MTH", arrayOf("@MTH(ARG, ARGS:0)", "RETURN")),
        )
        val store = VariableStore()
        val e = assertFailsWith<CodeEE> {
            methodRuntime(pb, store).callF("MTH", listOf(Value.StrV("s"), Value.IntV(7)))
        }
        assertEquals(
            "不能将\"@MTH\"的第1个参数从字符串型转换为整型",
            e.message,
            "实际=${e.message}",
        )
    }

    /** 同 [runtime]，但被调标成 `#FUNCTION` 方法（`CALLF` 家族只认方法，CalledFunction.cs:106-109）。 */
    private fun methodRuntime(
        pb: Pb,
        store: VariableStore,
        overrides: Map<String, String> = emptyMap(),
    ): ProcessRuntime {
        val metas = pb.names.map { name ->
            val index = pb.index(name)
            val arity = pb.program.functions[index].arity
            FuncMeta(
                index = index,
                key = name,
                isMethod = true,
                argIntCount = arity,
                argStrCount = arity,
                defIndex = index,
            )
        }
        val host = object : BuiltinHost {
            override val configOverrides: Map<String, String> = overrides
        }
        return ProcessRuntime(pb.program, store, FunctionTable(metas), VmConfig(), builtinHost = host)
    }
}
