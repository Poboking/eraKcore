package com.eratw.port.core.compile

import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.patch.PatchArtifacts
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParseConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 补丁编译器的 4.981 **浮点面**（`#FUNCTIONF` / `RETURNF` / `#LOCALFSIZE` / 私有 `#DIMF`）。
 *
 * ## 为什么这一组要单独立账
 *
 * 全量链路（tools/erbcc 的 CorpusScan + CorpusCompile + [com.eratw.port.core.compile.ErbProgramCompiler]）
 * 早就按 `MethodType` 三态走：`FUNCTIONF -> OperandType.FLOAT`（CorpusScan.kt:180）、
 * `returnsFloat = funcType == FLOAT`（CorpusCompile.kt:646）、浮点方法**绝不进**降级桶
 * （erbcc 桥 ExprCalls.kt:72-78）。设备端补丁编译器是**第二条独立实现**（R7-C1），
 * 它自己解析 `@`/`#` 头，升版时这五条判据逐条独立跟进：
 *
 * 1~2. `#FUNCTIONF` 被 `up.startsWith("#FUNCTION")` 吞掉 ⇒ 按**整型方法**登记，
 *    表达式位调用降级成 `#EXPR_RET#<k>` 读整型帧槽（浮点值从不进帧槽 ⇒ **恒读 0**），
 *    且 `FuncMeta.returnsFloat` 不透传 ⇒ 运行期重入通道也按整型取帧返回值。
 *    这两条已由主线 cb4ce10b 补口（[PatchCompilerFloatReturnDeclTest] 钉「不误登记 + 不降级为
 *    `_CALL`」）；本文件第 1 用例**保留**，因为它钉的是另一半：`_RETF` 的**条数**与
 *    「整型/字符串两类哨兵一个都不出现」。
 * 3. `#LOCALFSIZE` 在采集处 `else -> Unit` 丢掉 ⇒ 补丁函数的 LOCALF/ARGF 长度**静默退回默认 10**
 *    （spec: LogicalLineParser.cs:257-266、variables.yaml:99；FuncMeta.floatLocalSize 的消费点
 *    ProcessRuntime.kt:375）。
 * 4. 私有 `#DIMF` 的解析期名字按 `if (isStr) STR else INT` 折成**整型** ⇒ 赋值落点、
 *    表达式求值都走整型通道（全量侧同字段是 FLOAT：CorpusScan.kt:242）。
 * 5. `.patch` 产物的 `private-dims` 记录形（补丁格式 v1）里没有 `isFloat` / `defaultsFloat`
 *    两个原子 ⇒ 会话内编译对了、**落盘再装载就静默退回整型声明**（补丁私有变量按整数列建，
 *    浮点读写落错列）。修法 = 补丁格式升 v2（[com.eratw.port.core.patch.PatchHeader] 类 KDoc
 *    「版本策略」：夹带必需位属不兼容改动，必须升版本号），读侧 v1/v2 都认 —— 装机后 `mods/`
 *    里的旧产物跨 app 版本存活，把老产物判成坏档会让口上突然不生效。
 *
 * 判据同 [ReturnfFloatTest]：浮点方法的返回通道由**声明**决定，`_RETF` 的 `a` 是表达式池下标。
 */
class PatchFloatFaceTest {

    /** 空 base（补丁自带全部函数）——浮点面与 base 侧车无关，夹具最小化。 */
    private fun compilePatch(
        source: String,
        entry: String,
        sym: SymbolTable,
    ): PatchCompiler.PatchResult {
        val meta = BootMetadata(
            functions = emptyList<FuncMeta>(),
            methodTypes = emptyMap(),
            paramDefaults = emptyMap(),
            dimParseConfig = DimParseConfig(),
        )
        val counts = PatchCompiler.BaseCounts(0, 0, 0, 0, 0, 0)
        return PatchCompiler(meta, counts, sym).compile(
            listOf(PatchCompiler.SourceFile("mods/float.ERB", source)),
            entry,
        )
    }

    private fun opCount(p: Program, op: Int): Int =
        (0 until p.instructionCount).count { p.instructionAt(it).op == op }

    /** 语句池 + 表达式池里的全部 `FuncCall` 名字（降级产物是 `#EXPR_RET#…` 哨兵）。 */
    private fun callNames(p: Program): List<String> = calls(p).map { it.name }

    /** 语句池 + 表达式池里的全部 `FuncCall` 节点（要断言解析期类型位时用）。 */
    private fun calls(p: Program): List<FuncCall> {
        val out = ArrayList<FuncCall>()
        fun walk(e: Expr?) {
            when (e) {
                null -> Unit
                is FuncCall -> { out.add(e); e.args.forEach { a -> walk(a) } }
                is Binary -> { walk(e.left); walk(e.right) }
                is VarRef -> e.indices.forEach { walk(it) }
                else -> Unit
            }
        }
        for (i in 0 until p.statementCount) {
            val s = p.statement(i)
            if (s is AssignStmt) walk((s.args as? SpSetArgument)?.term)
        }
        for (i in 0 until p.exprCount) walk(p.expr(i))
        return out
    }

    @Test
    fun `补丁 FUNCTIONF 走浮点通道不误登记成整型方法`() {
        val sym = SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareFunction("B_RATE", OperandType.FLOAT)
        val src = """
            @B_RATE
            #FUNCTIONF
            RETURNF 1.25

            @B_USE
            A = TOINT(B_RATE() * 100)
            RETURN 0
        """.trimIndent()
        val r = compilePatch(src, "B_USE", sym)

        val rate = r.funcMetas.first { it.key == "B_RATE" }
        assertTrue(rate.isMethod, "#FUNCTIONF 仍是方法")
        assertTrue(rate.returnsFloat, "#FUNCTIONF 必须透传浮点返回位（运行期重入按它选 _RETF）")
        assertFalse(rate.returnsStr, "三种 MethodType 互斥")

        // 发射形状：`RETURNF 1.25` 一条 `_RETF` + 浮点方法掉尾兜底一条 `_RETF NONE`；
        // 整型/字符串通道一条都不许出现（旧实现这里发的是 `_RET`）。
        assertEquals(2, opCount(r.program, Opcode._RETF), "浮点方法的 _RETF 条数（RETURNF + 掉尾兜底）")
        assertEquals(0, opCount(r.program, Opcode._RETS), "浮点方法不碰字符串通道")

        // 调用点**不降级**：补丁里对 B_RATE 的表达式位调用保持原节点，
        // 一旦落进 intMethods 就会生成 `#EXPR_RET#<k>` 哨兵（读整型帧槽 ⇒ 浮点恒 0）。
        val names = callNames(r.program)
        assertTrue(names.any { it.equals("B_RATE", ignoreCase = true) }, "调用点保持原节点：$names")
        assertTrue(names.none { it.startsWith(EXPR_TEMP_PREFIX) }, "不得整型降级：$names")
        assertTrue(names.none { it.startsWith(EXPR_STR_TEMP_PREFIX) }, "不得字符串降级：$names")
    }

    @Test
    fun `包内 FUNCTIONF 互调的解析期类型走浮点通道`() {
        // 与上一用例的关键差：B_RATE **不预声明**进 sym —— 目标只活在补丁内，
        // 解析期走 PatchSourceResolver 的 patchHeads 兜底（ModImportController 只预声明
        // base 侧车方法，玩家口上的包内互调全是这个形态）。旧实现的兜底 when 漏了
        // returnsFloat 分支 ⇒ FuncCall.operandType 被钉成 INT，运行期 FuncCall.getIntValue
        // 对 FloatV 抛「函数 B_RATE 返回了非整数结果」（Expr.kt:553-556）。
        val sym = SymbolTable().declareVariable("A", OperandType.INT, 1)
        val src = """
            @B_RATE
            #FUNCTIONF
            RETURNF 1.25

            @B_USE
            A = TOINT(B_RATE() * 100)
            RETURN 0
        """.trimIndent()
        val r = compilePatch(src, "B_USE", sym)

        val call = calls(r.program).single { it.name.equals("B_RATE", ignoreCase = true) }
        assertEquals(
            OperandType.FLOAT,
            call.operandType,
            "包内 #FUNCTIONF 互调的解析期类型位必须认出浮点（与 ProcessRuntime.kt:914-916 三分同源）",
        )
        // 调用点照旧不降级：保持 FuncCall 原节点交运行期重入通道。
        assertTrue(callNames(r.program).none { it.startsWith(EXPR_TEMP_PREFIX) }, "不得整型降级")
    }

    @Test
    fun `补丁 LOCALFSIZE 透传进 FuncMeta`() {
        val sym = SymbolTable().declareVariable("A", OperandType.INT, 1)
        val src = """
            @B_FL
            #FUNCTIONF
            #LOCALFSIZE 16
            RETURNF 0.5
        """.trimIndent()
        val r = compilePatch(src, "B_FL", sym)
        val m = r.funcMetas.single()
        assertEquals(16, m.floatLocalSize, "#LOCALFSIZE 必须进 FuncMeta（否则 LOCALF/ARGF 静默退回默认 10）")

        // 侧车 function 段的落盘面：与导入链同款（functions 尾段追加后 encode/parse）
        val meta = BootMetadata(functions = r.funcMetas)
        val back = BootMetadata.parse(meta.encodeText()).functions.single()
        assertEquals(16, back.floatLocalSize, "floatLocalSize 经侧车格式往返保真")
        assertTrue(back.returnsFloat, "returnsFloat 经侧车格式往返保真")
    }

    @Test
    fun `补丁私有 DIMF 的名字按浮点解析`() {
        val sym = SymbolTable().declareVariable("A", OperandType.INT, 1)
        val src = """
            @B_DIMF
            #FUNCTION
            #DIMF 倍率 = 0.0
            倍率 = 1.5
            RETURN 0
        """.trimIndent()
        val r = compilePatch(src, "B_DIMF", sym)

        // 声明面的浮点标记（侧车 private-dim 的 isFloat 原子）
        assertTrue(r.privateDims.single().spec.isFloat, "私有 #DIMF 的声明必须带浮点标记")

        // 解析期的名字类型：赋值落点 VarRef 的 operandType（旧实现折成 INT ⇒ 写整型列、读回 1）
        val p = r.program
        val dest = (0 until p.statementCount)
            .map { p.statement(it) }
            .filterIsInstance<AssignStmt>()
            .first { (it.dest as? VarRef)?.name?.equals("倍率", ignoreCase = true) == true }
            .dest
        assertEquals(OperandType.FLOAT, dest.operandType, "私有 #DIMF 在补丁里必须按浮点名解析（与 CorpusScan.kt:242 同源）")
    }

    @Test
    fun `补丁私有 DIMF 经 patch 产物落盘读回仍是浮点`() {
        // 会话内解析对了还不够：`.patch` 的 private-dims 记录形（v1）没有 isFloat 原子,
        // 旧产物读回来会**静默退回整型声明**（VariableStore 按整数列建,运行期读恒 0）。
        // v2 起记录带 isFloat + 浮点初值,写 → 读 → 写逐字节相同（I8）。
        val sym = SymbolTable().declareVariable("A", OperandType.INT, 1)
        val src = """
            @B_DIMF
            #FUNCTION
            #DIMF 倍率 = 1.5
            A = 1
            RETURN 0
        """.trimIndent()
        val r = compilePatch(src, "B_DIMF", sym)
        val counts = PatchCompiler.BaseCounts(0, 0, 0, 0, 0, 0)
        val header = PatchCompiler.headerOf(r, counts, "a".repeat(64), "B_DIMF")
        val art = PatchArtifacts.of(header, ErbcWriter.encode(r.program))

        val back = PatchArtifacts.read(art.write()).header.privateDims.single().spec
        assertTrue(back.isFloat, "私有 #DIMF 经产物往返必须保住浮点标记")
        assertEquals(1.5, back.defaultsFloat, "浮点初值经产物往返保真")
        assertContentEquals(art.write(), PatchArtifacts.read(art.write()).write(), "含浮点私有声明的产物逐字节往返")
    }

    @Test
    fun `补丁函数浮点元数据经 function-metas 段落盘读回保真`() {
        // 与上一用例同构的「落盘半边」：#FUNCTIONF / #LOCALFSIZE 的声明面在
        // PatchResult.funcMetas 里编对了还不够 —— 装载侧（ModPatchLoader /
        // GameSession.buildPatchFunctionMetas）是**从产物重建** FuncMeta 的,头里
        // 不带 function-metas 段时 returnsFloat/floatLocalSize 恒缺省 ⇒ _RETF 通道
        // 选不上、LOCALF 帧长退默认 10（静默错值家族,PatchFloatFaceTest 类 KDoc ③⑤）。
        // 头可选段（空表整段省略;六项全缺省的函数不进段）把这两位固化进产物。
        val sym = SymbolTable().declareVariable("A", OperandType.INT, 1)
        val src = """
            @B_FL
            #FUNCTIONF
            #LOCALFSIZE 16
            RETURNF 0.5

            @B_USE
            A = 1
            RETURN 0
        """.trimIndent()
        val r = compilePatch(src, "B_USE", sym)
        val counts = PatchCompiler.BaseCounts(0, 0, 0, 0, 0, 0)
        val header = PatchCompiler.headerOf(r, counts, "a".repeat(64), "B_USE")
        val art = PatchArtifacts.of(header, ErbcWriter.encode(r.program))

        val metas = PatchArtifacts.read(art.write()).header.functionMetas
        val fl = metas.single { it.name == "B_FL" }
        assertTrue(fl.isMethod, "#FUNCTIONF 的方法位经产物往返保真")
        assertTrue(fl.returnsFloat, "浮点返回位经产物往返保真（运行期重入按它选 _RETF）")
        assertEquals(16, fl.floatLocalSize, "#LOCALFSIZE 经产物往返保真")
        // B_USE 六项全缺省 ⇒ 不进段（稀疏形态,头文本保持最小）。
        assertTrue(metas.none { it.name == "B_USE" }, "全缺省函数不进 function-metas 段")
        assertContentEquals(art.write(), PatchArtifacts.read(art.write()).write(), "含浮点函数元数据的产物逐字节往返")
    }
}
