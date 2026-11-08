package com.eratw.port.core.e2e

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 浮点变量运行期语义的端到端验收（worker f4，独立验收，不实现）。
 *
 * 装置与仓里 `core/compile/FloatLiteralTest` / `proc/ForBackEdgeReproTest` 同源：
 * 真 [LineParser] + 真 [ErbProgramCompiler] + 真 [VariableStore] + 真 [ProcessRuntime]，
 * 不另造引擎。差别只在输入来自 `resources` 的 `e2e` 目录里的小 ERB 片段 —— 片段抄自
 * 真实语料调用点（逐条见各片段头注），不改任何真实语料。
 *
 * 断言一律落在**整型见证变量**上（`TOINT(浮点表达式)` 写进 A..Z 系统整型变量），
 * 这样本文件在基线（运行期浮点存储未落地）上仍能编译、并在真跑时如实报红；
 * 片段头注给出抄自哪个真实语料调用点。
 * 其余三路（f1 运行期浮点、f2/f3 #DIMF 存档通道与编译器侧车）合并后应转绿。
 *
 * 口径：红就是红。基线读数写成「期望 vs 实际」，归因由交付报告逐条给路次，不在本文件里兜底。
 */
class FloatRuntimeE2ETest {

    private val snippet = ErbSnippetHarness.lines("float_runtime.erb")

    private fun intOf(store: VariableStore, name: String): Long = store.readInt(name, longArrayOf(0))

    // ------------------------------------------------------------------
    // 装置自检：只用基线已支持的浮点能力（字面量 + TOINT），基线应绿。
    // 这条绿了才说明下面几条红是「被验收功能缺位」，而不是装置/环境本身坏了。
    // ------------------------------------------------------------------

    @Test
    fun `装置自检 浮点字面量与 TOINT 在基线上已通`() {
        val store = ErbSnippetHarness.run(ErbSnippetHarness.lines("float_sanity.erb"), entry = "SANITY")
        assertEquals(7L, intOf(store, "B"), "10 * 0.7 = 7.0，TOINT 截断应为 7（FloatLiteralTest 同口径）")
    }

    // ------------------------------------------------------------------
    // 真实语法片段 1：`LOCALF = 0.0` + `LOCALF += A * 0.01`（A=125）
    // 实证调用点：DLC/魔法DLC/使用魔法.ERB:1248-1256（`LOCALF = 0.0` / `LOCALF += YOGORE:(LOCAL) * 0.01`
    // / `经验获取量 = TOINT(LOCALF)`）。
    // ------------------------------------------------------------------

    @Test
    fun `LOCALF 从 0 累加到 1_25 且 TOINT 截断为 1`() {
        val store = runEntry("LOCALF_ACCUM")
        assertEquals(1L, intOf(store, "B"), "TOINT(LOCALF) 应按 (long) 向零截断为 1；LOCALF = 0.0 + 125 * 0.01 = 1.25")
        assertEquals(125L, intOf(store, "C"), "LOCALF 应等于 1.25（乘以 100 取证）；A=125, A*0.01=1.25")
    }

    // ------------------------------------------------------------------
    // 真实语法片段 2：`#DIMF DYNAMIC 倍率` + `倍率 = 1.0` + `倍率 *= 2.0`
    // 实证调用点：DLC/魔法DLC/魔法相关函数.ERB:3190（`#DIMF DYNAMIC 倍率`）、
    // :3214（`倍率 = 1.0`）、:3216（`倍率 *= 1.2`；本用例改 2.0 以便整数见证精确对值）。
    // ------------------------------------------------------------------

    @Test
    fun `倍率 从 1_0 自乘 2_0 读回 2_0`() {
        val store = runEntry("RATE_SCALE")
        assertEquals(200L, intOf(store, "B"), "倍率应为 2.0（乘以 100 取证）；1.0 * 2.0")
        assertEquals(1L, intOf(store, "C"), "倍率 > 1.9 应为真 ⇒ 读回值确实大于 1.9")
    }

    // ------------------------------------------------------------------
    // 真实语法片段 3：`ARGF`（1 维浮点系统变量）读写
    // 实证来源：specs/variables.yaml 的 LOCALF/ARGF 段（fork-src VariableData.cs:378-379）。
    // ------------------------------------------------------------------

    @Test
    fun `ARGF 一维浮点读写逐值准确`() {
        val store = runEntry("ARGF_RW")
        assertEquals(25L, intOf(store, "B"), "ARGF:0 应为 0.25")
        assertEquals(-150L, intOf(store, "C"), "ARGF:3 应为 -1.5（负值向零截断后乘 100）")
    }

    private fun runEntry(entry: String): VariableStore =
        ErbSnippetHarness.run(
            lines = snippet,
            entry = entry,
            // LOCALF / ARGF 是系统浮点变量（不在 ERB 里 #DIM），解析面显式声明为 1 维 FLOAT，
            // 与生产链 LineParser 的 fork 浮点补丁层同口径（LineParser.kt 的 FloatLocalResolver）。
            predeclare = {
                declareVariable("LOCALF", OperandType.FLOAT, 1)
                declareVariable("ARGF", OperandType.FLOAT, 1)
            },
        )
}

/**
 * 「小 ERB 片段 → 编译 → 跑 VM」的可复用装置（worker f4 自建，底座全是生产件）。
 *
 * 输入是 `resources` 的 `e2e` 目录里的 ERB 文本行；流程与 `GoldenPipeline.build` 同口径：
 * 装载期声明（`#DIM`/`#DIMS`/`#DIMF`）先注册进 [VariableStore]，再把每个 `@label` 的函数体
 * 交给 [ErbProgramCompiler] 编译，最后用 [ProcessRuntime] 真执行入口函数。
 *
 * 与 `GoldenPipeline` 的差别只有两点，都是为了能在基线上**编译通过、运行期如实报红**：
 * 1. 变量解析面用 [SymbolTable] 手工预声明（生产 GoldenPipeline 走 erbcc 符号表）；
 * 2. 不装配 golden 那套 CSV/角色/宿主，只提供裸系统变量与显式 #DIM 声明。
 *
 * KDoc 纪律：正文不写通配星号对，避免 Kotlin 块注释嵌套吞掉闭合符。
 */
internal object ErbSnippetHarness {

    /** `resources` 的 `e2e` 目录下取一份 UTF-8 ERB 文本，按物理行返回。 */
    fun lines(name: String): List<String> {
        val stream = ErbSnippetHarness::class.java.getResourceAsStream("/e2e/$name")
            ?: error("缺少测试资源 /e2e/$name")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
    }

    /**
     * 从片段里抽出 `#DIM` / `#DIMS` / `#DIMF` 声明（`functionKey` 为 null 表示文件级全局）。
     *
     * 声明体交 [DimParser.parse]（与生产装载同一条解析路径）；`isPrivate` 由「是否位于
     * 某个 `@label` 之后」决定 —— ERB 文件里的 `#DIM` 一律私有，文件头（无 label）的为全局，
     * 与 `SystemVariableBootstrap` 装载 ERH 的口径在测试里分开表达。
     */
    fun declarations(lines: List<String>): List<Pair<String?, DimSpec>> {
        val out = ArrayList<Pair<String?, DimSpec>>()
        var current: String? = null
        for (raw in lines) {
            val text = raw.trimStart()
            if (text.isEmpty() || text.startsWith(";")) continue
            if (text.startsWith("@")) {
                current = labelName(text)
                continue
            }
            val kw = declarationKeyword(text) ?: continue
            val body = text.substring(text.indexOf("#") + 1).trimStart().let {
                it.substring(minOf(kw.length, it.length))
            }
            out.add(current to DimParser.parse("#$kw", body, isPrivate = current != null))
        }
        return out
    }

    /** 把 [VariableStore] 建好并注册全部声明（全局用 null 函数键，私有用所属 label）。 */
    fun buildStore(decls: List<Pair<String?, DimSpec>>): VariableStore {
        val store = VariableStore()
        for ((fn, spec) in decls) store.declareUserVar(spec, fn)
        return store
    }

    /**
     * 编译并执行 [entry] 函数，返回运行后的变量池。
     *
     * @param store 目标变量池；默认新建
     * @param decls 声明表；null = 从 [lines] 现抽
     * @param registerNew true = 先把 [decls] 注册进 [store]（首次跑）；false = 池子已建好
     *        （读档后的第二次跑，避免重复定义）
     * @param predeclare 解析面的额外预声明（系统浮点变量 LOCALF / ARGF 一类）
     */
    fun run(
        lines: List<String>,
        entry: String,
        store: VariableStore = VariableStore(),
        decls: List<Pair<String?, DimSpec>>? = null,
        registerNew: Boolean = true,
        stepCap: Long = 200_000,
        predeclare: SymbolTable.() -> Unit = {},
    ): VariableStore {
        val allDecls = decls ?: declarations(lines)
        // 解析面预声明：所有 #DIM/#DIMF 名先可见，再逐行解析（SymbolTable 不是
        // DefinitionScopedResolver，不会替 #DIMF 即时登记，故这里必须预扫一遍）。
        val symbols = SymbolTable().apply {
            predeclare()
            for ((_, spec) in allDecls) {
                declareVariable(
                    spec.name,
                    when {
                        spec.isFloat -> OperandType.FLOAT
                        spec.isStr -> OperandType.STR
                        else -> OperandType.INT
                    },
                    spec.dimension,
                )
            }
        }
        if (registerNew) for ((fn, spec) in allDecls) store.declareUserVar(spec, fn)

        val parser = LineParser(symbols)
        val functions = LinkedHashMap<String, MutableList<Statement>>()
        val params = HashMap<String, List<String>>()
        var current: String? = null
        lines.forEachIndexed { i, raw ->
            val text = raw.trimEnd()
            val t = text.trimStart()
            if (t.isEmpty() || t.startsWith(";")) return@forEachIndexed
            val kind = when {
                t.startsWith("@") -> LineKind.FUNCTION_LABEL
                t.startsWith("$") -> LineKind.GOTO_LABEL
                t.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            val stmt = parser.parseLogicalLine(LogicalLine(kind, "e2e.erb", i + 1, t))
            when (stmt) {
                null -> {}
                is SharpStmt -> {} // 声明已在上面登记，不进函数体
                is FuncLabelStmt -> {
                    current = stmt.name.uppercase()
                    functions.getOrPut(current!!) { ArrayList() }
                    params[current!!] = stmt.paramNames
                }
                else -> functions.getOrPut(current ?: error("片段里出现函数体外的语句: ${stmt::class.simpleName}")) {
                    ArrayList()
                }.add(stmt)
            }
        }

        val compiler = ErbProgramCompiler()
        val known = functions.keys.toSet()
        for ((key, body) in functions) {
            val ps = params[key].orEmpty()
            compiler.compileFunction(FuncSig(key, ps.size, isMethod = false, returnsStr = false, paramNames = ps), body, known)
        }
        val program: Program = compiler.build(entry.uppercase())
        val table = FunctionTable(
            program.functions.mapIndexed { i, f ->
                FuncMeta(index = i, key = f.name.uppercase(), argIntCount = f.arity, argStrCount = f.arity)
            },
        )
        ProcessRuntime(program, store, table, VmConfig(maxStepsPerRun = stepCap)).execute()
        return store
    }

    /** `@NAME(...)` / `@NAME, ARG` / `@NAME` 的标签名（大写，不含 `@` 与形参）。 */
    private fun labelName(text: String): String =
        text.removePrefix("@")
            .takeWhile { it != '(' && it != ',' && !it.isWhitespace() }
            .uppercase()

    /** 行首声明关键字（DIMF / DIMS / DIM），非声明行返回 null。 */
    private fun declarationKeyword(text: String): String? {
        if (!text.startsWith("#")) return null
        val m = DECL.find(text) ?: return null
        return m.groupValues[1].uppercase()
    }

    private val DECL = Regex("""^#(DIMF|DIMS|DIM)\b""", RegexOption.IGNORE_CASE)
}
