package com.eratw.port.core.expr

import com.eratw.port.core.e2e.ErbSnippetHarness
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 浮点表达式四处面向 fork 4.981 的对齐验收（worker 路次 `g5-expr-float`）。
 *
 * 契约的 4 条逐条落地，每条都断在**具体值**或**具体文案**上（不出现「只断言不抛异常」）：
 * 1. `@""` 的 `{ }` 内插值放行浮点，渲染走浮点通道（fork-src `StrForm.cs:140-144` 判据 +
 *    `:231-248` FormatCurlyBrace）；
 * 2. 浮点字面量的**整型通道**静默向零截断（fork-src `Term.cs:121-124` `(long)fValue`），
 *    差异面落在数组下标位（fork-src `VariableTerm.cs:55` 逐字 `arguments[i].GetIntValue(exm)`）；
 * 3. 浮点取余 `%` 解析期硬拒（fork-src 两张浮点字典 `OperatorMethod.cs:80-89` / `:91-113`
 *    都没有 `Mod`，全文件只有 `ModIntInt` 一条：登记 `:54`、实现 `:378`；上列
 *    `OperatorMethod.cs` 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核），
 *    可达替代依据：本机 `OperatorMethodManager.cs:140-157` 的整型 `ModIntInt` 实现与 `:725` 登记）；
 * 4. 一元运算符落浮点的**官方文案缺陷**复刻（前置支 fork-src `OperatorMethod.cs:157-158`
 *    判到 Float ⇒「浮点型」；后置支 `:183-188` 漏判 ⇒「未知类型」；模板 `emuera-zhs.xml:310`）。
 *    该 `emuera-zhs.xml:310` = fork-src 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）；
 *    本机可达替代依据：`emuera-zhs.xml:281` 的同一键 `Error.CanNotAppliedUnaryOp`。
 *    上列 `OperatorMethod.cs` 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）；
 *    可达替代依据：本仓 `specs/error-texts.yaml` 末段的类型名键登记（`FloatType` / `UnknownType`）。
 *
 * 取证口径：fork 源码一律按 commit `48494165` 取（`git show 48494165:`，工作树是另一
 * commit 不读）；1.824 参考实现（`reference/emuera-cs/`）表达式层无浮点，只作反证。
 * 文案的 zhs 原文与两侧行号同时记在 `specs/error-texts.yaml` 末段的注释里（键登记受
 * `LangKey` 双向一致闸约束，`LangText.kt` 不在本路所有权内）。
 *
 * KDoc 纪律：正文不写通配星号对，避免 Kotlin 块注释嵌套吞掉闭合符。
 */
class FloatExprFaceTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 浮点字面量 Token（TestEnv 的 i/s/id/op 只覆盖整型与字符串位）。 */
    private fun fl(v: Double) = ExprToken(
        ExprTokenKind.FLOAT_LITERAL, v.toString(), 0L, 1, 1, floatValue = v,
    )

    private fun floatRate(resolverDims: Int = 0) = resolverWith(
        VarInfo("倍率", OperandType.FLOAT, resolverDims, isCharacterData = false),
    )

    /** 系统浮点局部 `LOCALF`（1 维）的解析面假声明 —— 第 4 条的四条用例都按它走。 */
    private fun floatLocalf() = resolverWith(
        VarInfo("LOCALF", OperandType.FLOAT, 1, isCharacterData = false),
    )

    private fun floatCtx(value: Double = 1.5) = TestContext().apply {
        variables.floats["倍率"] = value
    }

    private fun evalFloat(expr: Expr, ctx: EvalContext = TestContext()): Double = expr.getFloatValue(ctx)

    /** 系统浮点变量在解析面的预声明（与 `core/e2e` 两路口径一致），外加本路要的 STR / YOGORE。 */
    private fun facePredeclare(): SymbolTable.() -> Unit = {
        declareVariable("LOCALF", OperandType.FLOAT, 1)
        declareVariable("ARGF", OperandType.FLOAT, 1)
        declareVariable("RESULTF", OperandType.FLOAT, 0)
        declareVariable("STR", OperandType.STR, 1)
    }

    private fun intOf(store: VariableStore, name: String): Long = store.readInt(name, longArrayOf(0))

    // ------------------------------------------------------------------
    // 1) `{ }` 内插值：浮点放行并按浮点通道渲染
    // ------------------------------------------------------------------

    /**
     * `前{倍率}后` —— 浮点变量进 `{ }` 现在放行，渲染走 [Expr.getFloatValue]。
     *
     * fork-src `StrForm.cs:140-144` 的 CurlyBrace 支判据是
     * `operand.GetEraType() != EraType.Integer && operand.GetEraType() != EraType.Float` ⇒ 拒的
     * 只有字符串型；1.824 的 `StrForm.cs:310-315` 只判 `!= typeof(long)` ⇒ 浮点被拒，本仓此前
     * 照的是 1.824（契约第 1 条）。读数 `前1.5后`：JVM 的 `Double.toString()` 与 .NET 的
     * `double.ToString()` 在 1.5 上同形（差在整数值的浮点，见下一条）。
     */
    @Test
    fun `花括号内插值收浮点变量并按浮点通道渲染`() {
        val e = parseOrThrow(listOf(f("前{倍率}后")), floatRate())
        assertEquals("前1.5后", evalStr(e, floatCtx(1.5)))
    }

    /**
     * 整数值浮点字面量进 `{ }`：本仓按 JVM 口径给 `1.0`，fork 的 .NET `ToString()` 给 `1`。
     *
     * 这是**格式化面**的差（`TODO(verify)` 已写在 `FormStringTerm.formatCurlyBrace` 的 KDoc），
     * 不是本契约第 1 条的判据：契约要的是「走浮点通道而不是被拒」。本仓其余浮点→字符串出口
     * （`fn/XmlMethods.kt:140`、`proc/ProcessRuntime.kt:1713`、`sql/SqlMethods.kt:191`）都按
     * JVM 形，单独在这一处换成 .NET 形会造成「同一台机器两种浮点文案」，故按现状钉住并写进
     * 交付回执。断在值上：`前1.0后`。
     */
    @Test
    fun `花括号内整值浮点按本仓 JVM 口径渲染`() {
        val e = parseOrThrow(listOf(f("前{1.0}后")))
        assertEquals("前1.0后", evalStr(e))
    }

    /**
     * `{倍率,6}` / `{倍率,6,LEFT}` —— 浮点渲染后仍按原版补齐口径走 PadLeft/PadRight。
     *
     * spec: fork `StrForm.cs:240-246`（`arguments[1] == null` 直接返回；LEFT→PadRight、
     * 否则 PadLeft，宽度取 `(int)GetIntValue`）。读数：右对齐 `"   1.5"`、左对齐 `"1.5   "`。
     */
    @Test
    fun `花括号浮点内插值宽度补齐两向各自准确`() {
        val right = parseOrThrow(listOf(f("{倍率,6}")), floatRate())
        assertEquals("   1.5", evalStr(right, floatCtx(1.5)))
        val left = parseOrThrow(listOf(f("{倍率,6,LEFT}")), floatRate())
        assertEquals("1.5   ", evalStr(left, floatCtx(1.5)))
    }

    /**
     * 宽度位仍是**整型闸**：`{倍率,1.5}` 拒。
     *
     * fork-src `ExpressionParser.cs:165-173` 的 `ReduceIntegerTerm` 判
     * `term.GetEraType() != EraType.Integer` 就抛 `Error.ExpressionResultIsNotNumeric`
     * —— 与契约第 2 条的「下标位按整型通道静默截断」不是同一个口：`{ , }` 的宽度走类型闸，
     * 变量下标不走。本条证明第 2 条的放宽没有渗到宽度位。
     */
    @Test
    fun `花括号宽度位仍要整型结果`() {
        val e = assertFailsWith<ExprParseException> {
            parseOrThrow(listOf(f("{倍率,1.5}")), floatRate())
        }
        assertTrue(
            e.message.orEmpty().contains("表达式结果不是数值"),
            "期望宽度位报「表达式结果不是数值」；实得「${e.message}」",
        )
    }

    /**
     * `{ }` 的字符串型操作数照旧拒 —— 本路将判据从「非整型」改成「非整型且非浮点型」，
     * 拒的集合只少了浮点那一支。spec: fork `StrForm.cs:140-144`（字符串型仍落 `IsNotNumericBrace`）。
     */
    @Test
    fun `花括号内插值仍拒字符串型`() {
        val resolver = resolverWith(VarInfo("S", OperandType.STR, 0, isCharacterData = false))
        val e = assertFailsWith<ExprParseException> { parseOrThrow(listOf(f("{S}")), resolver) }
        assertTrue(
            e.message.orEmpty().endsWith("{ } 内的表达式不是数值型"),
            "期望结尾文案「{ } 内的表达式不是数值型」（前缀是 ExprParseException 的行列定位）；实得「${e.message}」",
        )
    }

    /** 整型内插值逐字不变（对照面，证明改动没有把整型支带偏）。spec: fork `StrForm.cs:239`。 */
    @Test
    fun `花括号内整型内插值读数不变`() {
        val ctx = TestContext().apply { variables.ints["A"] = 42L }
        val resolver = resolverWith(var0D("A", OperandType.INT))
        assertEquals("42", evalStr(parseOrThrow(listOf(f("{A}")), resolver), ctx))
    }

    /**
     * 真实端到端复现：`STR:0 = 前{倍率}后`（契约第 1 条给的复现式）。
     *
     * 走真 [LineParser] + 真 [ErbProgramCompiler] + 真 [VariableStore] + 真 [ProcessRuntime]
     * （装置 `core/e2e/ErbSnippetHarness`，本路只跑不改）。断在 `STR:0` 的实际内容上。
     */
    @Test
    fun `端到端 STR 赋浮点内插值形式串`() {
        val store = ErbSnippetHarness.run(
            lines = listOf("@T", "#DIMF DYNAMIC 倍率", "倍率 = 1.5", "STR:0 = 前{倍率}后"),
            entry = "T",
            predeclare = facePredeclare(),
        )
        assertEquals("前1.5后", store.readStr("STR", longArrayOf(0)))
    }

    // ------------------------------------------------------------------
    // 2) 浮点字面量的整型通道 = 静默向零截断
    // ------------------------------------------------------------------

    /**
     * [FloatLiteral.getIntValue] 向零截断（fork-src `Term.cs:121-124` 的 `(long)fValue`）。
     *
     * 四条读数挑的是**方向**判据：`1.5 → 1` 与 `2.999 → 2` 断「截断不是四舍五入」；
     * `-1.75 → -1` 与 `-0.75 → 0` 断「向零不是向下取整」（向下取整会给 -2 / -1）。
     * 与 `TOINT` 的浮点支（`fn/PureMethods.kt:1540`）同一条 `(long)` 口径。
     */
    @Test
    fun `浮点字面量整型通道向零截断四条方向读数`() {
        val ctx = TestContext()
        assertEquals(1L, FloatLiteral(1.5).getIntValue(ctx), "1.5 → 1")
        assertEquals(2L, FloatLiteral(2.999).getIntValue(ctx), "2.999 → 2（截断，不是四舍五入成 3）")
        assertEquals(-1L, FloatLiteral(-1.75).getIntValue(ctx), "-1.75 → -1（向零；向下取整给 -2）")
        assertEquals(0L, FloatLiteral(-0.75).getIntValue(ctx), "-0.75 → 0（向零；向下取整给 -1）")
    }

    /**
     * 越界口径复用 `core/lexer` 的 `longFromScaledDouble`：`d >= 2^63` 给 [Long.MIN_VALUE]
     * （.NET x64 `cvttsd2si` 的整数不定值），不是 JVM 的饱和 `MAX_VALUE`。
     *
     * 与 `TOINT` / 字面量读入器同一条函数（`lexer/LexicalAnalyzer.kt:18-19` 的 KDoc 给了
     * `DIM.ERH:662` 与 `ResourceManager.ERH:4-13` 两个真实触发点）。
     */
    @Test
    fun `浮点字面量整型通道越界给最小值而非饱和`() {
        val ctx = TestContext()
        assertEquals(Long.MIN_VALUE, FloatLiteral(9.223372036854776E18).getIntValue(ctx), "2^63 恰过界 → MIN_VALUE")
        assertEquals(1L shl 62, FloatLiteral(4.611686018427388E18).getIntValue(ctx), "2^62 仍在范围内照值截断")
    }

    /**
     * NaN 也落「整数不定值」：C# x64 `(long)double.NaN` 与越界同给 `0x8000000000000000`
     * （fork-src `Term.cs:121-124` 的 `cvttsd2si` 口径），JVM `Double.toLong()` 对 NaN 恒给 0。
     * 病态下标（`YOGORE:NaN` 一类）两侧才一致（2026-11-02 审查修正）。
     */
    @Test
    fun `浮点字面量NaN整型通道给整数不定值`() {
        val ctx = TestContext()
        assertEquals(
            Long.MIN_VALUE,
            FloatLiteral(Double.NaN).getIntValue(ctx),
            "(long)NaN = 0x8000000000000000，不是 0",
        )
    }

    /**
     * 差异面：数组下标按整型通道求值 ⇒ `YOGORE:1.5 = 7` 按 **1 号格**落库。
     *
     * fork-src `VariableTerm.cs:49-99` 三条读通道 + `GetElementInt`（:38-43）都用
     * `arguments[i].GetIntValue(exm)` 求下标（逐字见 :55 / :71 / :90），下标表达式上没有
     * 类型闸 ⇒ 浮点字面量在下标位就是 `(long)1.5 = 1`。契约第 2 条给的复现式即本条。
     * 断三格：1 号 = 7、0 号 = 0、2 号 = 0（证明落点确实是 1 而不是四舍五入的 2 或报错）。
     */
    @Test
    fun `端到端 浮点字面量下标按一号格落库`() {
        val store = ErbSnippetHarness.run(
            lines = listOf("#DIM YOGORE, 10", "@T", "YOGORE:1.5 = 7"),
            entry = "T",
            predeclare = facePredeclare(),
        )
        assertEquals(7L, store.readInt("YOGORE", longArrayOf(1)), "1.5 向零截断 ⇒ 命中 1 号格")
        assertEquals(0L, store.readInt("YOGORE", longArrayOf(0)), "0 号格没被写")
        assertEquals(0L, store.readInt("YOGORE", longArrayOf(2)), "2 号格没被写（不是四舍五入）")
    }

    /**
     * 下标位的越界闸没被放宽：`YOGORE:9.5` 截成 9 之后仍按 9 号格合法，
     * `YOGORE:10.5` 截成 10 越界 ⇒ 仍是硬错（`#DIM YOGORE, 10` 的合法下标是 0..9）。
     *
     * 本条是对照面：契约第 2 条只把「浮点能不能当整数用」放开，边界判定不动。
     */
    @Test
    fun `端到端 浮点下标越界仍是硬错`() {
        val store = ErbSnippetHarness.run(
            lines = listOf("#DIM YOGORE, 10", "@T", "YOGORE:9.5 = 3"),
            entry = "T",
            predeclare = facePredeclare(),
        )
        assertEquals(3L, store.readInt("YOGORE", longArrayOf(9)), "9.5 → 9 号格，界内")
        val e = assertFailsWith<RuntimeException> {
            ErbSnippetHarness.run(
                lines = listOf("#DIM YOGORE, 10", "@T", "YOGORE:10.5 = 3"),
                entry = "T",
                predeclare = facePredeclare(),
            )
        }
        assertTrue(
            e.message.orEmpty().contains("越界") || e.message.orEmpty().contains("要素番号") ||
                e.message.orEmpty().contains("范围") || e.message.orEmpty().contains("超"),
            "期望越界硬错；实得「${e.message}」",
        )
    }

    /**
     * **本路未放宽的第二支**（对照 + 卡点）：浮点字面量落进语句位的整型落点仍被拒。
     *
     * 实测拒绝点在**语句层**：`stmt/ArgBuilders.kt:1799` 的 `if (!term.isInteger()) fail(ctx,
     * "不能把字符串赋给整数变量")` —— SET 的右值类型闸只认整型，浮点掉进那条**写着「字符串」的
     * 文案**里（文案本身是第二处不准，文件不在本路所有权）。编译层 `ErbProgramCompiler.kt:817-828`
     * 的 `OperandType.FLOAT` 守卫是第二道，本条形态走不到它。
     *
     * fork 侧这一支按 `Term.cs:121-124` 的 `(long)fValue` 静默截断（`A = 1.5` 把 1 写进 A）
     * ⇒ **残留偏离**，写进交付回执「需组长接线」。本条钉住现状文案，防止有人以为第 2 条
     * 已把整型通道全部放开。
     */
    @Test
    fun `端到端 语句位浮点落整型右值仍被语句层拒`() {
        val e = assertFailsWith<RuntimeException> {
            ErbSnippetHarness.run(
                lines = listOf("@T", "A = 1.5"),
                entry = "T",
                predeclare = facePredeclare(),
            )
        }
        assertTrue(
            e.message.orEmpty().endsWith("SET命令:不能把字符串赋给整数变量"),
            "期望语句层 SET 右值闸；实得「${e.message}」",
        )
    }

    // ------------------------------------------------------------------
    // 3) 浮点取余：解析期硬拒，文案逐字取官方
    // ------------------------------------------------------------------

    /**
     * `7.5 % 2.0` 解析期即拒，文案 = `Error.CanNotAppliedBinaryOp` 展开（本注释所写
     * `zhs xml:311` = fork-src 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）；
     * 本机可达替代依据：`emuera-zhs.xml:282` 的同一键 `Error.CanNotAppliedBinaryOp`）。
     *
     * 判据：fork 两张浮点字典（`OperatorMethod.cs:80-89` float×float、`:91-113` int×float
     * 混合）登记的运算符都是 Plus/Minus/Mult/Div + 六个比较，**没有 Mod**；全文件的取余
     * 只有 `ModIntInt`（登记 :54、实现 :378）⇒ 落 `ReduceBinaryTerm` 的 :225-245，
     * 按 :232-233/:240-241 取「浮点型」，:244 套模板。契约第 3 条要求把 g4 钉住的
     * `7.5 % 2.0 * 10 = 15` 翻成 fork 口径，翻后的读数即本条。
     * 上列 `OperatorMethod.cs` 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）；
     * 可达替代依据：本机 `OperatorMethodManager.cs:140-157` 的整型 `ModIntInt` 实现与 `:725` 登记，
     * 本机全文无浮点 `%`，与上述同型（同 `expr/Expr.kt` 的 `getFloatValue` KDoc）。
     */
    @Test
    fun `浮点取余解析期硬拒且文案逐字等于官方`() {
        val e = assertFailsWith<ExprEvalException> {
            parseOrThrow(listOf(fl(7.5), op("%"), fl(2.0)))
        }
        assertEquals("无法对浮点型和浮点型使用二相运算符\"%\"进行运算", e.message)
    }

    /**
     * 混合两侧的取余同样拒，且类型名按 fork 的 :228-243 分别取（左 int 右 float 与
     * 左 float 右 int 是两条不同读数，不是「统一报浮点型」）。
     */
    @Test
    fun `整浮混合取余按左右次序报类型名`() {
        val leftInt = assertFailsWith<ExprEvalException> {
            parseOrThrow(listOf(i(7), op("%"), fl(2.5)))
        }
        assertEquals("无法对数值型和浮点型使用二相运算符\"%\"进行运算", leftInt.message)
        val leftFloat = assertFailsWith<ExprEvalException> {
            parseOrThrow(listOf(fl(7.5), op("%"), i(2)))
        }
        assertEquals("无法对浮点型和数值型使用二相运算符\"%\"进行运算", leftFloat.message)
    }

    /**
     * 浮点侧其他未登记运算符也走同一条官方文案（fork 是同一条 `else`，不是逐运算符各写一句）。
     * `1.5 & 2` ⇒ 位与在 `binaryMixedFloatDic` 里没有登记。
     */
    @Test
    fun `浮点位运算走同一条官方二元文案`() {
        val e = assertFailsWith<ExprEvalException> { parseOrThrow(listOf(fl(1.5), op("&"), i(2))) }
        assertEquals("无法对浮点型和数值型使用二相运算符\"&\"进行运算", e.message)
    }

    /**
     * 对照面：`/` 仍放行且落浮点通道（`DivFloatFloat`，fork-src `OperatorMethod.cs:975-990`
     * 不可本机取证（TODO(verify)：待 fork-src 就位后复核））。
     *
     * 证明第 3 条只摘掉 MOD，没有把整支浮点算术收紧。读数 `7.5 / 2.0 = 3.75`。
     * （除零那一支已于 2026-11-02 收口：fork 判 0 抛 `CodeEE`，见 `Expr.kt`
     * `Binary.getFloatValue` 的 DIV 支与下一条用例。）
     */
    @Test
    fun `浮点除法照旧放行读值为3_75`() {
        val e = parseOrThrow(listOf(fl(7.5), op("/"), fl(2.0)))
        assertEquals(OperandType.FLOAT, e.operandType, "除法结果必须仍是浮点结点")
        assertEquals(3.75, evalFloat(e), "7.5 / 2.0 = 3.75")
    }

    /**
     * 浮点除零硬错（fork-src `OperatorMethod.cs:983-989` DivFloatFloat 与 `:1132-1138`
     * DivMixedFloat 均 `if (divisor == 0.0) throw CodeEE(trerror.DivideByZero)`；
     * 上列 `OperatorMethod.cs` 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核），
     * 可达文案依据 `emuera-zhs.xml:286`（`Error.DivideByZero` =「除数为0的除法运算」））—— 不落 IEEE ±Infinity/NaN。
     * 2026-11-02 收口（原第 5 条偏离），与本文件整型除零（`Expr.kt:754`）同一口径。
     */
    @Test
    fun `浮点除零抛错不落Infinity`() {
        val e = assertFailsWith<ExprEvalException> {
            evalFloat(parseOrThrow(listOf(fl(1.0), op("/"), fl(0.0))))
        }
        assertEquals("除数为0的除法运算", e.message)
        // 0/0 同一支：fork 先判除数再除，不给 NaN。
        val e2 = assertFailsWith<ExprEvalException> {
            evalFloat(parseOrThrow(listOf(fl(0.0), op("/"), fl(0.0))))
        }
        assertEquals("除数为0的除法运算", e2.message)
    }

    /** 对照面：`*` 与比较的读数逐字不变（g4 的语料真值链不能被第 3 条带偏）。 */
    @Test
    fun `浮点乘与比较读数不变`() {
        val mul = parseOrThrow(listOf(i(10), op("*"), fl(0.7)))
        assertEquals(7.0, evalFloat(mul), "10 * 0.7 = 7.0")
        val cmp = parseOrThrow(listOf(fl(1.5), op(">"), fl(1.0)))
        assertEquals(OperandType.INT, cmp.operandType, "比较结果是整型结点")
        assertEquals(1L, evalInt(cmp), "1.5 > 1.0 → 1")
    }

    /**
     * 端到端复现：契约给的 g4 用例 `A = TOINT(7.5 % 2.0 * 10)` 现在装载期即拒。
     *
     * 这一条是 g4 在 `core/e2e/FloatCorpusE2ETest.浮点取余本仓放行而 fork 未注册`
     * 里钉成 15 的那条的 fork 口径翻面；那条用例文件不在本路所有权内，改写归组长接线
     * （交付回执「需组长接线」第 2 条给出替换文本）。
     */
    @Test
    fun `端到端 浮点取余进TOINT也是装载期硬拒`() {
        val e = assertFailsWith<RuntimeException> {
            ErbSnippetHarness.run(
                lines = listOf("@T", "A = TOINT(7.5 % 2.0 * 10)"),
                entry = "T",
                predeclare = facePredeclare(),
            )
        }
        assertTrue(
            e.message.orEmpty().contains("无法对浮点型和浮点型使用二相运算符\"%\"进行运算"),
            "期望装载期落官方二元文案；实得「${e.message}」",
        )
    }

    // ------------------------------------------------------------------
    // 4) 一元运算符落浮点：官方两支不一致，按 ADR-005 复刻
    // ------------------------------------------------------------------

    /**
     * 后置 `LOCALF++` ⇒ 「无法对**未知类型**使用单相运算符"++"」。
     *
     * fork-src `OperatorMethod.cs:165-191` 的 `ReduceUnaryAfterTerm` 里，类型名 else 链
     * （:183-188）**只判 Integer 与 String**，浮点掉进 `else` 取 `Error.UnknownType`
     * （「未知类型」；本注释所写 `zhs xml:306` = fork-src 行号不可本机取证（TODO(verify)：
     * 待 fork-src 就位后复核），本机可达替代依据 `emuera-zhs.xml:277` 的同一键），
     * 再套 `Error.CanNotAppliedUnaryOp`（本注释所写 `zhs xml:310` 同属 fork-src 行号不可本机取证
     * （TODO(verify)：待 fork-src 就位后复核），本机可达替代依据 `emuera-zhs.xml:281`）。
     * 契约第 4 条要求按 ADR-005 原样复刻这处官方缺陷 ⇒ 本仓照抄「未知类型」。
     * 上列 `OperatorMethod.cs` 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）；
     * 可达替代依据：本仓 `specs/error-texts.yaml` 末段的 `UnknownType` / `CanNotAppliedUnaryOp` 键登记。
     */
    @Test
    fun `后置一元落浮点报未知类型逐字等于官方`() {
        val e = assertFailsWith<ExprEvalException> {
            parseOrThrow(listOf(id("LOCALF"), op("++")), floatLocalf())
        }
        assertEquals("无法对未知类型使用单相运算符\"++\"", e.message)
    }

    /**
     * 前置 `++LOCALF` ⇒ 「无法对**浮点型**使用单相运算符"++"」。
     *
     * 同一件事的前一支：fork-src `OperatorMethod.cs:126-163` 的类型名 else 链在 :157-158
     * **判到了** Float ⇒ 取 `Error.FloatType`（「浮点型」；本注释所写 `zhs xml:305` = fork-src
     * 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核），本机 `emuera-zhs.xml` 无该键）。
     * 两支不一致是官方的，不做「顺手统一」（ADR-005）。
     * 上列 `OperatorMethod.cs` 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）；
     * 可达替代依据：本仓 `specs/error-texts.yaml` 末段的 `FloatType` 键登记（zhs_xml:305）。
     */
    @Test
    fun `前置一元落浮点报浮点型逐字等于官方`() {
        val e = assertFailsWith<ExprEvalException> {
            parseOrThrow(listOf(op("++"), id("LOCALF")), floatLocalf())
        }
        assertEquals("无法对浮点型使用单相运算符\"++\"", e.message)
    }

    /**
     * 前置 `!` 与 `~` 落浮点同样走官方一元文案（fork 的浮点一元支只登记 Plus/Minus，
     * `OperatorMethod.cs:143-149` 不可本机取证（TODO(verify)：待 fork-src 就位后复核））；
     * 类型名仍是「浮点型」，与后置支的「未知类型」不同。
     */
    @Test
    fun `前置非加减一元落浮点各条文案准确`() {
        val not = assertFailsWith<ExprEvalException> {
            parseOrThrow(listOf(op("!"), id("倍率")), floatRate())
        }
        assertEquals("无法对浮点型使用单相运算符\"!\"", not.message)
        val bitNot = assertFailsWith<ExprEvalException> {
            parseOrThrow(listOf(op("~"), id("倍率")), floatRate())
        }
        assertEquals("无法对浮点型使用单相运算符\"~\"", bitNot.message)
    }

    /**
     * 后置 `--` 同样落「未知类型」（fork 的 else 链只看类型，不看具体运算符）。
     * 读数与 `++` 那条同形，只是运算符串换成 `--`。
     */
    @Test
    fun `后置一元减落浮点报未知类型`() {
        val e = assertFailsWith<ExprEvalException> {
            parseOrThrow(listOf(id("LOCALF"), op("--")), floatLocalf())
        }
        assertEquals("无法对未知类型使用单相运算符\"--\"", e.message)
    }

    /**
     * 对照面：一元 `-` / `+` 在浮点上照旧放行（fork 的 `MinusFloat` 与「原样返回操作数」）。
     * 读数 `-倍率 = -1.5`、`+倍率 = 1.5`，结点类型仍是 FLOAT。
     */
    @Test
    fun `浮点一元加减照旧放行读数不变`() {
        val minus = parseOrThrow(listOf(op("-"), id("倍率")), floatRate())
        assertEquals(OperandType.FLOAT, minus.operandType, "取负结果仍是浮点结点")
        assertEquals(-1.5, evalFloat(minus, floatCtx(1.5)), "-倍率 = -1.5")
        val plus = parseOrThrow(listOf(op("+"), id("倍率")), floatRate())
        assertEquals(1.5, evalFloat(plus, floatCtx(1.5)), "+倍率 = 1.5（解析期原样返回操作数）")
    }

    /**
     * 对照面：整型后置一元完全不变（第 4 条只加浮点一支，整型仍落 `unaryAfterDic`）。
     * 读数是原版语义：`A++` 返回**旧值** 5、写回 6（fork-src `OperatorMethod.cs:629-655` 对应
     * 的 IncrementAfterInt；该行号不可本机取证（TODO(verify)：待 fork-src 就位后复核），
     * 可达替代依据：本机 `OperatorMethodManager.cs` 的 `unaryAfterDic` 登记与整型后置实现）。
     */
    @Test
    fun `整型后置一元照旧返回旧值`() {
        val ctx = TestContext().apply { variables.ints["A"] = 5L }
        val resolver = resolverWith(var0D("A", OperandType.INT))
        assertEquals(5L, evalInt(parseOrThrow(listOf(id("A"), op("++")), resolver), ctx), "返回旧值 5")
        assertEquals(6L, ctx.variables.ints["A"], "写回 6")
    }

    /**
     * 端到端复现（契约第 4 条的上屏面）：`A = LOCALF++` 的文案是「未知类型」那条。
     *
     * 同一条从 `core/e2e/FloatCorpusE2ETest` 的两条用例也会跟着翻面（该文件不在本路所有权，
     * 改写文本见交付回执「需组长接线」第 3 条）。
     */
    @Test
    fun `端到端 表达式位后置浮点自增上屏未知类型文案`() {
        val e = assertFailsWith<RuntimeException> {
            ErbSnippetHarness.run(
                lines = listOf("@T", "A = LOCALF++"),
                entry = "T",
                predeclare = facePredeclare(),
            )
        }
        assertTrue(
            e.message.orEmpty().contains("无法对未知类型使用单相运算符\"++\""),
            "期望官方缺陷文案「未知类型」；实得「${e.message}」",
        )
    }

    /**
     * 端到端对照：语句位 `LOCALF++` 仍放行（fork 的 `VariableTerm.ChangeValue(double, exm)`
     * 在 `VariableTerm.cs:225-240` 存在，与表达式位是两条路）。
     *
     * 读数 0.0 → 1.0 ⇒ `TOINT(LOCALF * 10)` = 10。本条守住「第 4 条只动文案、没把语句位收紧」。
     */
    @Test
    fun `端到端 语句位浮点自增仍放行读回十`() {
        val store = ErbSnippetHarness.run(
            lines = listOf("@T", "LOCALF = 0.0", "LOCALF++", "A = TOINT(LOCALF * 10)"),
            entry = "T",
            predeclare = facePredeclare(),
        )
        assertEquals(10L, intOf(store, "A"), "语句位 ++ 折成 LOCALF = LOCALF + 1.0 ⇒ 0.0 → 1.0")
    }

    // ------------------------------------------------------------------
    // 5) 函数调用落浮点通道：静态类型 INT 的调用必须**宽化**，FLOAT 的调用走浮点通道
    //    （worker 路次 T1-v4981；缺陷：FuncCall.getFloatValue 曾无条件要求 FloatV）
    // ------------------------------------------------------------------

    /**
     * 整型返回值的函数调用进**浮点二元**：按静态类型宽化成 double，不许把 int 结果当成
     * 「非浮点」硬错。
     *
     * 输入 → 期望 vs 实际：
     * - 输入 `ADD() * 0.5`（`ADD` 静态 INT、运行期 `Value.IntV(4)`）；
     * - 期望 = `2.0` —— 依据 `Expr.getFloatValue` 的默认实现
     *   `getIntValue(ctx).toDouble()`（`Expr.kt:41`）与 [VarRef]/[Binary] 的
     *   `if (operandType != FLOAT)` 前置判据同口径；fork 侧对应「整型子式进 double 通道」；
     * - 实际（修复前）= 抛 `表达式: 函数 ADD 返回了非浮点结果`。
     */
    @Test
    fun intReturningFunctionCallWidensInFloatBinary() {
        val ctx = TestContext()
        ctx.funcs["ADD"] = { Value.IntV(4L) }
        val resolver = FakeResolver().apply { funcs["ADD"] = OperandType.INT }
        val e = parseOrThrow(listOf(id("ADD"), lp(), rp(), op("*"), fl(0.5)), resolver)
        assertEquals(OperandType.FLOAT, e.operandType, "INT 乘 FLOAT ⇒ 结点静态类型 FLOAT")
        assertEquals(2.0, evalFloat(e, ctx), "ADD() = 4 ⇒ 4 * 0.5 = 2.0（int 结果宽化，非硬错）")
    }

    /**
     * 同一条宽化在**一元取负**下也要成立：`-ADD() * 0.5` 里 `-ADD()` 的浮点读要能落回整型通道。
     *
     * 输入 → 期望 vs 实际：`-ADD() * 0.5`（`ADD` = 4）→ 期望 `-2.0`（[Unary.getFloatValue] 的
     * MINUS 支把 `-operand.getFloatValue` 传给整型 [FuncCall]）vs 修复前抛「非浮点结果」。
     */
    @Test
    fun intReturningFunctionCallWidensUnderFloatUnaryMinus() {
        val ctx = TestContext()
        ctx.funcs["ADD"] = { Value.IntV(4L) }
        val resolver = FakeResolver().apply { funcs["ADD"] = OperandType.INT }
        val e = parseOrThrow(listOf(op("-"), id("ADD"), lp(), rp(), op("*"), fl(0.5)), resolver)
        assertEquals(-2.0, evalFloat(e, ctx), "-ADD() = -4.0，再 * 0.5 = -2.0")
    }

    /**
     * 端到端复现执行层依赖：整型函数结果进**浮点左值**必须宽化。
     *
     * 判据同 `sem/StatementExecutor.kt:4624`（`arg.term?.getFloatValue`）与
     * `:4588-4589`（「逐项 getFloatValue 对 INT 右值自动宽化」）。输入 `LOCALF = TOINT("4")`
     * 后 `LOCALF += TOINT("4") * 0.5` ⇒ 期望 `LOCALF = 6.0`、`A = TOINT(LOCALF*10) = 60`；
     * vs 修复前第一句即抛「函数 TOINT 返回了非浮点结果」。
     */
    @Test
    fun intReturningCallWidensInFloatAssignmentEndToEnd() {
        val store = ErbSnippetHarness.run(
            lines = listOf(
                "@T",
                "LOCALF = TOINT(\"4\")",
                "LOCALF += TOINT(\"4\") * 0.5",
                "A = TOINT(LOCALF * 10)",
            ),
            entry = "T",
            predeclare = facePredeclare(),
        )
        assertEquals(60L, intOf(store, "A"), "4 + 4*0.5 = 6 ⇒ LOCALF*10 = 60（整型函数进浮点左值宽化）")
    }

    /**
     * 对照面：**静态类型 FLOAT** 的调用仍走浮点通道读 `Value.FloatV`。
     *
     * 输入 `TOFLOAT("x")`（静态 FLOAT、运行期 `Value.FloatV(1.75)`）→ 期望 `1.75`。
     * 本条守住修复没有把浮点读通道削弱成「一律整型宽化」。
     */
    @Test
    fun floatTypedFunctionCallReadsFloatChannel() {
        val ctx = TestContext()
        ctx.funcs["TOFLOAT"] = { Value.FloatV(1.75) }
        val resolver = FakeResolver().apply { funcs["TOFLOAT"] = OperandType.FLOAT }
        val e = parseOrThrow(listOf(id("TOFLOAT"), lp(), s("x"), rp()), resolver)
        assertEquals(OperandType.FLOAT, e.operandType, "浮点函数调用结点静态类型 FLOAT")
        assertEquals(1.75, evalFloat(e, ctx), "浮点返回通道读 Value.FloatV")
    }

    /**
     * 对照面：静态 FLOAT 的调用若返回非浮点值，仍是硬错（不静默兜 0.0）。
     *
     * 输入 `F()`（静态 FLOAT、运行期 `Value.IntV(1)`）→ 期望抛
     * `函数 F 返回了非浮点结果`。本条与上一条一起界定修复边界：只有**静态 INT** 才宽化。
     */
    @Test
    fun floatTypedFunctionReturningNonFloatStillHardError() {
        val ctx = TestContext()
        ctx.funcs["F"] = { Value.IntV(1L) }
        val resolver = FakeResolver().apply { funcs["F"] = OperandType.FLOAT }
        val e = parseOrThrow(listOf(id("F"), lp(), rp()), resolver)
        val ex = assertFailsWith<ExprEvalException> { evalFloat(e, ctx) }
        assertEquals("函数 F 返回了非浮点结果", ex.message)
    }

    // ------------------------------------------------------------------
    // 6) 比较 / 三目 / 除零：判定边界逐条钉住
    // ------------------------------------------------------------------

    /**
     * NaN 比较 = 原生 double 运算符语义（`==` 恒 false、`!=` 恒 true、序比较恒 false）。
     *
     * 输入 → 期望 vs 实际：`NaN == NaN → 0`、`NaN != NaN → 1`、`NaN > 1.0 → 0`、
     * `NaN <= 1.0 → 0`。依据 [Binary.getFloatValue] 的 `floatCompareToLong`（`l == r` /
     * `l > r` 等原生 double 运算），与 IEEE-754 及 C# `double` 运算符同形；
     * 若改用 `IComparable`/`CompareTo`（NaN 最小），`!=` 与序比较会翻面。
     */
    @Test
    fun nanComparisonUsesRawDoubleEquality() {
        val ctx = TestContext()
        assertEquals(0L, evalInt(parseOrThrow(listOf(fl(Double.NaN), op("=="), fl(Double.NaN))), ctx), "NaN == NaN → 0")
        assertEquals(1L, evalInt(parseOrThrow(listOf(fl(Double.NaN), op("!="), fl(Double.NaN))), ctx), "NaN != NaN → 1")
        assertEquals(0L, evalInt(parseOrThrow(listOf(fl(Double.NaN), op(">"), fl(1.0))), ctx), "NaN > 1.0 → 0")
        assertEquals(0L, evalInt(parseOrThrow(listOf(fl(Double.NaN), op("<="), fl(1.0))), ctx), "NaN <= 1.0 → 0")
        assertEquals(0L, evalInt(parseOrThrow(listOf(fl(1.0), op(">="), fl(Double.NaN))), ctx), "1.0 >= NaN → 0")
        // 对照：普通浮点比较照旧
        assertEquals(1L, evalInt(parseOrThrow(listOf(fl(1.5), op(">"), fl(1.0))), ctx), "1.5 > 1.0 → 1")
    }

    /**
     * 浮点三目：任一分支为 FLOAT 时结果为 FLOAT，另一分支的**整数值宽化为 double**。
     *
     * 输入 → 期望 vs 实际：`A ? 1.5 # 2`（`A` 为 INT）⇒ `A=1` 时 `1.5`、`A=0` 时 `2.0`；
     * 结点静态类型 FLOAT。依据 [Parser.reduceTernaryTerm] 的浮点支（Parser.kt:890-893）与
     * [Ternary.getFloatValue]（Expr.kt:954-956）。
     */
    @Test
    fun floatTernaryMixedBranchesEvaluateAsFloat() {
        val ctx = TestContext().apply { variables.ints["A"] = 1L }
        val resolver = resolverWith(var0D("A", OperandType.INT))
        val e = parseOrThrow(listOf(id("A"), op("?"), fl(1.5), op("#"), i(2)), resolver)
        assertEquals(OperandType.FLOAT, e.operandType, "任一分支浮点 ⇒ 三目结果 FLOAT")
        assertEquals(1.5, evalFloat(e, ctx), "A=1 ⇒ 真分支 1.5")
        ctx.variables.ints["A"] = 0L
        assertEquals(2.0, evalFloat(e, ctx), "A=0 ⇒ 假分支整数 2 宽化为 2.0")
    }

    /**
     * 浮点三目的求值**惰性**：未选中的分支不求值（含函数副作用）。
     *
     * 输入 `0 ? ADD() # 2.5` → 期望 `2.5` 且 `ADD` 零次调用。依据
     * [Ternary.getFloatValue]（`cond != 0 ? ifTrue : ifFalse`，Expr.kt:955-956）与
     * 三目的通用惰性口径（OperatorMethodManager.cs:657-691）。
     */
    @Test
    fun floatTernaryEvaluatesOnlySelectedBranch() {
        val ctx = TestContext()
        ctx.funcs["ADD"] = { Value.IntV(9L) }
        val resolver = FakeResolver().apply { funcs["ADD"] = OperandType.INT }
        val e = parseOrThrow(listOf(i(0), op("?"), id("ADD"), lp(), rp(), op("#"), fl(2.5)), resolver)
        assertEquals(2.5, evalFloat(e, ctx), "条件 0 ⇒ 取假分支 2.5")
        assertEquals(0, ctx.funcCalls.size, "未选中的真分支（含函数调用）不求值")
    }

    /**
     * 浮点除零对 `-0.0` 除数同样硬错（`-0.0 == 0.0` 在 IEEE 与 C# 都为真）。
     *
     * 输入 → 期望 vs 实际：`1.0 / -0.0 → 抛「除数为0的除法运算」`（不落 `-Infinity`）。
     * 依据 [Binary.getFloatValue] 的 DIV 支 `if (r == 0.0) throw`（Expr.kt:866）与
     * fork `OperatorMethod.cs:983-989` 的 `if (divisor == 0.0)`（该行号不可本机取证
     * （TODO(verify)：待 fork-src 就位后复核）；可达文案依据 `emuera-zhs.xml:286`）。
     */
    @Test
    fun floatDivisionByNegativeZeroStillHardError() {
        val e = parseOrThrow(listOf(fl(1.0), op("/"), fl(-0.0)))
        val ex = assertFailsWith<ExprEvalException> { evalFloat(e) }
        assertEquals("除数为0的除法运算", ex.message)
    }

    /**
     * 不变量对照：浮点算术结点在**整型语境**仍硬拒（整数守卫不被削弱）。
     *
     * 输入 `1.5 * 2.0` 的 `getIntValue` → 期望抛「浮点表达式不能直接用于整型上下文（需 TOINT
     * 转换）」。依据 [Binary.getIntValue] 的浮点支（Expr.kt:719-723 落 `floatCompareToLong`
     * 的 else，Expr.kt:804）。与 [FloatLiteral.getIntValue] 在下标位的静默截断共同界定
     * 「截断只发生在字面量下标，复合浮点式子不许当整数用」。
     */
    @Test
    fun floatArithmeticNodeStillRejectedInIntegerContext() {
        val e = parseOrThrow(listOf(fl(1.5), op("*"), fl(2.0)))
        assertEquals(OperandType.FLOAT, e.operandType, "1.5 * 2.0 ⇒ FLOAT 结点")
        val ex = assertFailsWith<ExprEvalException> { e.getIntValue(TestContext()) }
        assertTrue(
            ex.message.orEmpty().contains("整型上下文"),
            "期望整型语境硬拒；实得「${ex.message}」",
        )
    }
}
