package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParseConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `#FUNCTIONF`（4.981 fork 的浮点返回方法）在**补丁编译路**（R7-C1 `PatchCompiler`）的声明面。
 *
 * 缺陷背景：`FuncSig.returnsFloat`（`compile/FunctionBytecode.kt:59`）与 `FuncMeta.returnsFloat`
 * （`proc/FuncMeta.kt:77`）是 4.981 新增的第三态；全量编译路已接线
 * （`tools/erbcc/.../CorpusCompile.kt:646` 的 `returnsFloat = funcType == OperandType.FLOAT`、
 * `core-vm/.../boot/RealCorpusCompile.kt:474`），但补丁编译路漏接：
 *
 * * 预扫 `PatchCompiler.kt:526-527` 只认 `#FUNCTIONS`/`#FUNCTION`，`#FUNCTIONF` 被
 *   `"#FUNCTIONF".startsWith("#FUNCTION")` 吞进整数方法支（`returnsFloat` 永不置位）；
 * * 步骤 9 的 `FuncSig(...)`（`:314-322`）与步骤 13 的 `FuncMeta(...)`（`:428-446`）
 *   都不传 `returnsFloat`（默认 false）。
 *
 * 后果（复刻 fork 前的错误方向）：
 * 1. 补丁里 `#FUNCTIONF` 的 `RETURNF <浮点表达式>` 落进整型通道分支，`evalExprTo` 的
 *    FLOAT 守卫（`ErbProgramCompiler.kt:869-871`）**误报编译失败**「浮点表达式不能直接
 *    用于整型上下文」——一条合法的 fork 写法被拒。
 * 2. 函数表/FuncMeta 的 `returnsFloat=false` ⇒ 运行期按整型通道取值（fork 应为浮点）。
 *
 * fork 口径（`specs/isa.yaml:2414` 段的浮点字面量 + fork-src `LogicalLineParser.cs:141-183`
 * 三态 `#FUNCTION`/`#FUNCTIONS`/`#FUNCTIONF`）与全量路 `CorpusCompile.kt:645-646` 的
 * `funcType == FLOAT` 一致：补丁路的读数必须与全量路逐字段相同（A1 同尺对拍口径）。
 */
class PatchCompilerFloatReturnDeclTest {

    // ------------------------------------------------------------------
    // 装置：零 base（补丁自足）+ 侧车空表；只需要补丁段的编译产物
    // ------------------------------------------------------------------

    private val zeroBase = PatchCompiler.BaseCounts(
        instructionCount = 0,
        functionCount = 0,
        statementCount = 0,
        stringCount = 0,
        longCount = 0,
        exprCount = 0,
    )

    private fun emptyMeta(): BootMetadata = BootMetadata(dimParseConfig = DimParseConfig())

    private fun compilePatch(source: String, entry: String, sym: SymbolTable = SymbolTable()): PatchCompiler.PatchResult =
        PatchCompiler(emptyMeta(), zeroBase, sym).compile(
            listOf(PatchCompiler.SourceFile("mods/patch.ERB", source)),
            entry,
        )

    private fun opCount(p: Program, op: Int): Int = (0 until p.instructionCount).count { p.op(it) == op }

    // ------------------------------------------------------------------
    // 1）#FUNCTIONF：浮点通道
    // ------------------------------------------------------------------

    @Test
    fun `FUNCTIONF补丁方法经浮点返回通道编译且函数表与FuncMeta都记浮点位`() {
        val src = """
            @B_FF
            #FUNCTIONF
            RETURNF 1.5
        """.trimIndent()
        val sym = SymbolTable().declareVariable("A", OperandType.INT, 1).declareFunction("B_FF", OperandType.FLOAT)
        val result = compilePatch(src, entry = "B_FF", sym = sym)
        val p = result.program

        assertTrue(
            result.functions.single().sig.returnsFloat,
            "补丁 FunctionBytecode.sig.returnsFloat 必须为 true（全量路 CorpusCompile.kt:646 同口径）",
        )
        assertTrue(result.funcMetas.single().returnsFloat, "补丁 FuncMeta.returnsFloat 必须为 true")
        assertEquals(0, opCount(p, Opcode._RET), "#FUNCTIONF 不得编整型通道 _RET")
        assertTrue(opCount(p, Opcode._RETF) >= 2, "RETURNF + 掉尾兜底至少两条 _RETF，实得 ${opCount(p, Opcode._RETF)}")
    }

    @Test
    fun `FUNCTIONF补丁方法RETURNF整数表达式不误报编译失败`() {
        // 原版把「整数表达式落进浮点函数」当解析警告（fork-src Instraction.Child.cs:3725-3732），
        // 补丁路必须复刻：编译通过（返回落 _RETF NONE，表达式仍求值），不得抛
        // 「浮点表达式不能直接用于整型上下文」。
        val src = """
            @B_FFI
            #FUNCTIONF
            RETURNF 0
        """.trimIndent()
        val result = compilePatch(src, entry = "B_FFI")
        assertTrue(result.funcMetas.single().returnsFloat, "整数表达式落进浮点函数不得退回整型声明")
        assertEquals(0, opCount(result.program, Opcode._RET))
        assertTrue(opCount(result.program, Opcode._RETF) >= 1)
    }

    // ------------------------------------------------------------------
    // 2）表达式位调用：浮点方法不降级（保持 FuncCall，走运行期重入通道）
    // ------------------------------------------------------------------

    @Test
    fun `补丁内浮点方法在表达式位不降级为CALL`() {
        val src = """
            @B_FF
            #FUNCTIONF
            RETURNF 0

            @B_CALLER
            A = TOINT(B_FF() * 2)
            RETURN 0
        """.trimIndent()
        val sym = SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareFunction("B_FF", OperandType.FLOAT)
        val result = compilePatch(src, entry = "B_CALLER", sym = sym)
        val p = result.program
        // 浮点方法的返回值不经帧槽（帧槽只有整型列/字符串列），降级成 _CALL + 哨兵读槽会读错值；
        // 必须保持 FuncCall 原节点（ExprCallKind.UNKNOWN 桶）交运行期重入通道。
        assertEquals(0, opCount(p, Opcode._CALL), "浮点方法在表达式位不得降级为 _CALL")
    }

    // ------------------------------------------------------------------
    // 3）不回归：整数 / 字符串方法通道一位未动
    // ------------------------------------------------------------------

    @Test
    fun `FUNCTION补丁方法仍走整型通道`() {
        val src = """
            @B_I
            #FUNCTION
            RETURNF 42
        """.trimIndent()
        val result = compilePatch(src, entry = "B_I")
        assertEquals(0, opCount(result.program, Opcode._RETF), "整数方法不得编 _RETF")
        assertTrue(opCount(result.program, Opcode._RET) >= 2, "RETURNF + 掉尾各一条 _RET")
        assertEquals(false, result.funcMetas.single().returnsFloat)
    }

    @Test
    fun `FUNCTIONS补丁方法仍走字符串通道`() {
        val src = """
            @B_S
            #FUNCTIONS
            RETURNF "x"
        """.trimIndent()
        val sym = SymbolTable().declareVariable("A", OperandType.INT, 1).declareFunction("B_S", OperandType.STR)
        val result = compilePatch(src, entry = "B_S", sym = sym)
        assertEquals(0, opCount(result.program, Opcode._RETF), "字符串方法不得编 _RETF")
        assertTrue(opCount(result.program, Opcode._RETS) >= 1, "字符串方法仍编 _RETS")
        assertTrue(result.funcMetas.single().returnsStr)
    }
}