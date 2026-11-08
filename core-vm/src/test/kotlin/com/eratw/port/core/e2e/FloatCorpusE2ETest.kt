package com.eratw.port.core.e2e

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.stmt.SymbolTable
import org.junit.jupiter.api.Assumptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 浮点表达式的**真实语料端到端**验收 + 表达式通道边界（worker 路次 `g4-expr-e2e`）。
 *
 * 装置是仓里现成的 [ErbSnippetHarness]（真 [com.eratw.port.core.stmt.LineParser] + 真
 * [com.eratw.port.core.compile.ErbProgramCompiler] + 真 [com.eratw.port.core.vars.VariableStore]
 * + 真 [com.eratw.port.core.proc.ProcessRuntime]），本文件**只跑不实现**：不新增引擎件、
 * 不复制共享语料（语料一律按路径只读引用，见 [corpusLine]）。
 *
 * 三段结构：
 * 1. 抄写片段实跑（`resources` 的 e2e 目录里本路自有的两份文件）：无环境依赖，断言具体数值；
 * 2. 语料原文锚点（吃 `ERATW_CORPUS`，未设置时计 skipped）：先按 1 基行号逐字核对原文，
 *    再把**现读出来的原文行**拼进装置真跑 —— 第 1 段证明「抄得像」，这一段证明「抄的是真话」；
 * 3. 表达式通道边界：每条钉住一个具体的值或一条具体的报错文案。
 *
 * 取证口径（第 2 段里那条「全语料无浮点自增自减」判据的证据面）：
 * - 1.824 参考实现（`reference/emuera-cs/`）的表达式层**完全没有浮点**：`AExpression`
 *   只有 long/string 两型（AExpression.cs:5-40），整个 Expression 目录 grep `double` 零命中，
 *   一元登记面只有 `IncrementInt`/`DecrementInt`/`IncrementAfterInt`/`DecrementAfterInt`
 *   （OperatorMethodManager.cs:601-655、注册 :717-720），`VariableTerm.ChangeValue` 只收
 *   `long`（VariableTerm.cs:202-223）；
 * - 4.981 fork 源码（`qirchen421/emuera.sk`@`48494165`，即 specs 里 `fork-src` 前缀的同一
 *   commit）本路**已在线取证**，行号见 `expr/Expr.kt` 里 `Unary.getFloatValue` 的 KDoc 与交付报告：
 *   一元浮点支只登记 Plus/Minus（OperatorMethod.cs:143-149），`Increment`/`Decrement`
 *   不登记 ⇒ 落到 :152-162 抛 `CodeEE`；后置更窄，只有 Integer 支（:175-179）。
 *   上列 `OperatorMethod.cs` 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）；
 *   可达替代依据：本仓 `expr/Expr.kt` 的 `Unary.getFloatValue` KDoc 与 `specs/error-texts.yaml` 末段。
 *   但 `VariableTerm.ChangeValue(double, exm)` 在 fork 里**存在**（VariableTerm.cs:225-240）
 *   ⇒ 语句位浮点自增自减开放、表达式位关闭，与本仓现状逐字同构。
 *
 * KDoc 纪律：正文不写通配星号对，避免 Kotlin 块注释嵌套吞掉闭合符。
 */
class FloatCorpusE2ETest {

    // ------------------------------------------------------------------
    // 1) 抄写片段实跑
    // ------------------------------------------------------------------

    /**
     * 使用魔法.ERB 的 `LOCALF` 清扫累加：10 个房间各 125 点污垢，`* 0.01` 逐间累加。
     *
     * 断言 12 而不是 12.5，钉的是 `TOINT` 的**向零截断**；断言 125 钉的是 `LOCALF`
     * 真值 12.5（若中途回落整数通道，累加会截成 1.0/轮 ⇒ 10，或干脆报「未定义的变量」）。
     * `C`/`D` 顺手钉住同一轮循环里整数写通道没被浮点累加带偏（原文 1254 的清零语句照跑）。
     */
    @Test
    fun `语料抄写 LOCALF 累加十间真值 12 与 12_5`() {
        val store = ErbSnippetHarness.run(
            lines = ErbSnippetHarness.lines("float_corpus_localf.erb"),
            entry = "SWEEP_LOCALF",
            predeclare = floatSystemVars(),
        )
        assertEquals(12L, intOf(store, "A"), "经验获取量 = TOINT(12.5) = 12（向零截断）")
        assertEquals(125L, intOf(store, "B"), "TOINT(LOCALF * 10) = 125 ⇒ LOCALF 精确等于 12.5")
        assertEquals(0L, intOf(store, "C"), "原文 1254 的 YOGORE:(LOCAL) = 0 必须真落库")
        assertEquals(0L, intOf(store, "D"), "末间同样清零，循环走满 0..9")
    }

    /**
     * 魔法相关函数的 `#DIMF DYNAMIC 倍率` 三轮自乘：私有浮点变量 + `WHILE` +
     * 语句位整数 `--` + 浮点比较进 `IF` + `TOINT` 收口，一条链跑穿。
     *
     * `C` 是**一元取负保持浮点**的读数：`-倍率` 必须先按浮点读（`Unary.getFloatValue` 的
     * MINUS 支），再与整数 1000 混合乘；若一元支退化成整型通道，`-倍率` 会先报
     * 「浮点表达式不能直接用于整型上下文」。
     */
    @Test
    fun `语料抄写 倍率 自乘三轮真值 1728 与取负`() {
        val store = ErbSnippetHarness.run(
            lines = ErbSnippetHarness.lines("float_corpus_rate.erb"),
            entry = "RATE_UPDATE",
            predeclare = floatSystemVars(),
        )
        assertEquals(1L, intOf(store, "A"), "倍率 = 1.728 > 1.1 ⇒ 必须走真分支；比较结点 operandType 是 INT")
        assertEquals(1728L, intOf(store, "B"), "TOINT(倍率 * 1000) = 1728（1.0 连乘 1.2 三轮）")
        assertEquals(-1728L, intOf(store, "C"), "TOINT(-倍率 * 1000) = -1728：一元取负保持浮点型")
        assertEquals(0L, intOf(store, "D"), "语句位整数 乘数 -- 走满三轮归零（本路不动它，只借它把轮数做实）")
    }

    /**
     * `TOINT` 的截断**方向**：向零，不是向下取整。
     *
     * 依据 fork-src `Creator.Method.cs:6105-6106`：`TOINT` 的浮点支是
     * `return (long)arguments[0].GetFloatValue(exm)`，C# 的 `(long)` 向零截断。
     * 1.75 → 1、-1.75 → **-1**（向下取整会给 -2）、-0.75 → **0**（向下取整会给 -1）：
     * 后两条才是方向判据，前一条两者同值、单独断不出方向。
     */
    @Test
    fun `语料抄写 TOINT 向零截断而非向下取整`() {
        val store = ErbSnippetHarness.run(
            lines = ErbSnippetHarness.lines("float_corpus_rate.erb"),
            entry = "TOINT_TRUNC",
            predeclare = floatSystemVars(),
        )
        assertEquals(1L, intOf(store, "A"), "TOINT(1.75) = 1")
        assertEquals(-1L, intOf(store, "B"), "TOINT(-1.75) = -1（向零；向下取整会是 -2）")
        assertEquals(0L, intOf(store, "C"), "TOINT(-0.75) = 0（向零；向下取整会是 -1）")
        assertEquals(-125L, intOf(store, "D"), "TOINT(-1.25 * 100) = -125：一元取负 × 混合乘 × 截断")
    }

    // ------------------------------------------------------------------
    // 2) 真实语料原文锚点（吃 ERATW_CORPUS；未设置时按 skipped 计，不静默 PASS）
    // ------------------------------------------------------------------

    /**
     * 逐字核对 魔法相关函数.ERB 的 `倍率` 块（:3188-:3190 声明面、:3214-:3223 浮点块）。
     *
     * 这条是「抄得像」的对账闸：第 1 段的期望值全部由这些行推出，原文一旦改动（升版、
     * 换语料包），这里先红，而不是让下面的实跑用例默默断言一个过期数字。
     */
    @Test
    fun `语料原文 倍率块逐字对账`() {
        require4981Corpus()
        assertCorpusLine(MAGIC_FN, 3188, "@永恒回归_倍率更新")
        assertCorpusLine(MAGIC_FN, 3189, "#DIM DYNAMIC 乘数")
        assertCorpusLine(MAGIC_FN, 3190, "#DIMF DYNAMIC 倍率")
        assertCorpusLine(MAGIC_FN, 3214, "倍率 = 1.0")
        assertCorpusLine(MAGIC_FN, 3215, "WHILE 乘数 > 0")
        assertCorpusLine(MAGIC_FN, 3216, "倍率 *= 1.2")
        assertCorpusLine(MAGIC_FN, 3217, "乘数 --")
        assertCorpusLine(MAGIC_FN, 3218, "WEND")
        assertCorpusLine(MAGIC_FN, 3219, "IF 倍率 > 1.0")
        assertCorpusLine(MAGIC_FN, 3221, "ELSE")
        assertCorpusLine(MAGIC_FN, 3223, "ENDIF")
    }

    /**
     * **真实语料原文逐字实跑**：把 :3188-:3190 与 :3214-:3219、:3221、:3223 现读出来
     * 拼成可执行片段真跑一遍 VM，断言与第 1 段同一组数值。
     *
     * 插进去的装置线只有四条，逐条注明顶替了原文哪一段：
     * - `乘数 = 3`：顶替原文 :3196-:3213（LIMIT/LOG10/M_CFLAG/ABL/MAXBASE 一整套角色池读数）；
     * - `A = 1` / `A = 0`：顶替原文 :3220/:3222 的 `M_FLAG:永恒回归_倍率 = …`（CSV 关键字下标）；
     * - 末三条见证：把浮点读数搬到内建整型变量 A..D 上，好按名读回。
     * 浮点相关语句本身（`倍率 = 1.0` / `倍率 *= 1.2` / `乘数 --` / `WHILE` / `WEND` /
     * `IF 倍率 > 1.0` / `ELSE` / `ENDIF`）**一个字符都没改**，连原文 :3219 行尾的制表符
     * 都在 trim 前保留原样（装置按行喂，行首缩进由解析器忽略）。
     */
    @Test
    fun `语料原文 倍率块逐字实跑真值 1728`() {
        require4981Corpus()
        val program = listOf(
            corpusLine(MAGIC_FN, 3188),   // @永恒回归_倍率更新
            corpusLine(MAGIC_FN, 3189),   // #DIM DYNAMIC 乘数
            corpusLine(MAGIC_FN, 3190),   // #DIMF DYNAMIC 倍率
            "乘数 = 3",                    // 装置线：顶替原文 :3196-:3213
            corpusLine(MAGIC_FN, 3214),   // 倍率 = 1.0
            corpusLine(MAGIC_FN, 3215),   // WHILE 乘数 > 0
            corpusLine(MAGIC_FN, 3216),   // 倍率 *= 1.2
            corpusLine(MAGIC_FN, 3217),   // 乘数 --
            corpusLine(MAGIC_FN, 3218),   // WEND
            corpusLine(MAGIC_FN, 3219),   // IF 倍率 > 1.0
            "A = 1",                       // 装置线：真分支（原文写 M_FLAG:关键字）
            corpusLine(MAGIC_FN, 3221),   // ELSE
            "A = 0",                       // 装置线：假分支
            corpusLine(MAGIC_FN, 3223),   // ENDIF
            "B = TOINT(倍率 * 1000)",      // 见证
            "C = TOINT(-倍率 * 1000)",     // 见证：一元取负
            "D = 乘数",                     // 见证
        )
        val store = ErbSnippetHarness.run(
            lines = program,
            entry = "永恒回归_倍率更新",
            predeclare = floatSystemVars(),
        )
        assertEquals(1L, intOf(store, "A"), "原文阈值 1.0：1.728 > 1.0 必须走真分支")
        assertEquals(1728L, intOf(store, "B"), "TOINT(倍率 * 1000) = 1728")
        assertEquals(-1728L, intOf(store, "C"), "TOINT(-倍率 * 1000) = -1728")
        assertEquals(0L, intOf(store, "D"), "乘数 由 3 走满 `乘数 --` 归零")
    }

    /** 逐字核对 使用魔法.ERB 的 `LOCALF` 块与它依赖的两条声明。 */
    @Test
    fun `语料原文 LOCALF块逐字对账`() {
        require4981Corpus()
        assertCorpusLine(USE_MAGIC, 4, "#DIM DYNAMIC 经验获取量")
        assertCorpusLine(USE_MAGIC, 1248, "LOCALF = 0.0")
        assertCorpusLine(USE_MAGIC, 1250, "LOCALF += YOGORE:(LOCAL) * 0.01")
        // :1254 行尾带中文注释，对账按「注释前的语句本体」比。
        assertTrue(
            corpusLine(USE_MAGIC, 1254).startsWith("YOGORE:(LOCAL) = 0"),
            "语料 $USE_MAGIC:1254 应为 YOGORE 清零句；实得「${corpusLine(USE_MAGIC, 1254)}」",
        )
        assertCorpusLine(USE_MAGIC, 1256, "经验获取量 = TOINT(LOCALF)")
        assertCorpusLine(DIM_ERH, 250, "#DIM SAVEDATA YOGORE, 2000")
    }

    /**
     * **真实语料原文逐字实跑**：`LOCALF = 0.0` / `LOCALF += YOGORE:(LOCAL) * 0.01` /
     * `YOGORE:(LOCAL) = 0` / `经验获取量 = TOINT(LOCALF)` 四句直接取自语料文件，
     * `#DIM` 两句同样取自语料（DIM.ERH:250 与 使用魔法.ERB:4）。
     *
     * 装置线：`FOR LOCAL, 0, 10` 顶替原文 :1249 的 `GET_CURRENT_MAP()` 上下界（`FOR` 终值不含，
     * 预填 `YOGORE:(LOCAL) = 125` 让每轮的加数成为可精确对值的常量。
     */
    @Test
    fun `语料原文 LOCALF块逐字实跑真值 12 与 12_5`() {
        require4981Corpus()
        val program = listOf(
            corpusLine(DIM_ERH, 250),     // #DIM SAVEDATA YOGORE, 2000
            "@SWEEP_LOCALF_REAL",
            corpusLine(USE_MAGIC, 4),     // #DIM DYNAMIC 经验获取量（原文是 @MAGIC_USE 的私有局部）
            "FOR LOCAL, 0, 10",            // 装置线：顶替原文 :1249 的地图上下界（终值不含 ⇒ 10 轮）
            "YOGORE:(LOCAL) = 125",        // 装置线：预填污垢度
            "NEXT",
            corpusLine(USE_MAGIC, 1248),  // LOCALF = 0.0
            "FOR LOCAL, 0, 10",            // 装置线
            corpusLine(USE_MAGIC, 1250),  // LOCALF += YOGORE:(LOCAL) * 0.01
            corpusLine(USE_MAGIC, 1254),  // YOGORE:(LOCAL) = 0  ; 设置房间清洁度为最高…
            "NEXT",
            corpusLine(USE_MAGIC, 1256),  // 经验获取量 = TOINT(LOCALF)
            "A = 经验获取量",               // 见证
            "B = TOINT(LOCALF * 10)",      // 见证
        )
        val store = ErbSnippetHarness.run(
            lines = program,
            entry = "SWEEP_LOCALF_REAL",
            predeclare = floatSystemVars(),
        )
        assertEquals(12L, intOf(store, "A"), "经验获取量 = TOINT(12.5) = 12")
        assertEquals(125L, intOf(store, "B"), "TOINT(LOCALF * 10) = 125 ⇒ LOCALF 精确 12.5")
        assertEquals(0L, store.readInt("YOGORE", longArrayOf(3)), "原文 :1254 的清零句必须真落库")
    }

    /**
     * 全语料扫一遍：**没有任何浮点变量写自增/自减**（表达式位与语句位都没有）。
     *
     * 这是「表达式位浮点 `++`/`--` 不开放」结论的 B 级语料侧证据（A 级在 fork 源码，见类 KDoc）：
     * 放行一条没人走的路，等于凭直觉给语义定形。扫描范围 = 语料树里所有 ERB/ERH 的每一个物理行，
     * 浮点名集合 = 内建三件（LOCALF/ARGF/RESULTF）+ 每一条 `#DIMF` 的末段名字。
     */
    @Test
    fun `全语料无浮点变量的自增自减形态`() {
        val root = corpusRoot()
        val floatNames = linkedSetOf("LOCALF", "ARGF", "RESULTF")
        val hits = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension.uppercase() in setOf("ERB", "ERH") }
            .forEach { file ->
                val lines = file.readText().removePrefix(BOM).split('\n')
                lines.forEach { raw ->
                    val t = raw.trim()
                    if (t.startsWith(";")) return@forEach
                    if (t.uppercase().startsWith("#DIMF")) {
                        val body = t.substringAfter(' ').split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                        body.lastOrNull()?.let { floatNames += it.uppercase() }
                    }
                    if (t.contains("++") || t.contains("--")) {
                        if (floatNames.any { adjacentToIncrement(it, t) }) hits.add("${file.name}: $t")
                    }
                }
            }
        assertTrue(
            hits.isEmpty(),
            "语料里出现浮点变量的 ++/-- 形态，本路「表达式位不开放」的结论需要重新取证：\n${hits.take(10).joinToString("\n")}",
        )
    }

    // ------------------------------------------------------------------
    // 3) 表达式位浮点 ++ / --：本路取证结论 = 保持解析期硬拒，不放行
    // ------------------------------------------------------------------

    /**
     * `A = LOCALF++`（表达式位后置）解析期即拒。
     *
     * fork-src 判据：`OperatorMethod.cs:165-191` 的 `ReduceUnaryAfterTerm` 只在
     * `EraType.Integer` 支取 `unaryAfterDic`（:175-179），而登记面 :44-48 只有
     * Increment/Decrement 的整数四条 ⇒ 浮点必落 :182-190 抛 `CodeEE`。
     *
     * ⚠ 官方文案缺陷，已按 ADR-005 原样复刻（前置/后置上屏文案不一致是 fork 的真实形态）：
     * :183-188 的类型名 else 链只判 Integer/String，浮点掉进 `UnknownType` ⇒
     * fork 上屏的是「无法对**未知类型**使用单相运算符"++"」，而前置那条（:157-158）
     * 判了 Float，上屏「无法对**浮点型**使用单相运算符"++"」。两条不一致。
     * 上列 `OperatorMethod.cs` 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）；
     * 可达替代依据：本仓 `expr/Parser.kt` 的 `reduceUnaryAfterTerm` 浮点支与 `specs/error-texts.yaml` 末段。
     */
    @Test
    fun `表达式位后置 LOCALF++ 解析期硬拒`() {
        val e = assertFailsWith<RuntimeException> {
            ErbSnippetHarness.run(
                lines = listOf("@T", "A = LOCALF++"),
                entry = "T",
                predeclare = floatSystemVars(),
            )
        }
        assertTrue(
            e.message.orEmpty().contains("未知类型") &&
                e.message.orEmpty().contains("单相运算符"),
            "期望解析期拒「无法对未知类型使用单相运算符\"++\"」（fork-src 行号不可本机取证，" +
                "TODO(verify)：待 fork-src 就位后复核；类型名 else 链漏判 Float，ADR-005 原样复刻）；实得「${e.message}」",
        )
    }

    /**
     * `A = ++LOCALF`（表达式位前置）同样解析期即拒。
     *
     * fork-src 判据：`OperatorMethod.cs:126-163` 的浮点支（:143-149）**只**登记
     * Plus（返回操作数本身）与 Minus（`new MinusFloat()`），`Increment`/`Decrement`
     * 不落 `method` ⇒ :150-162 抛 `CodeEE`，文案取 `Error.FloatType`（「浮点型」）。
     * 上列 `OperatorMethod.cs` 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）；
     * 可达替代依据：本仓 `specs/error-texts.yaml` 末段的 `FloatType` 键登记与 `expr/Parser.kt` 抛出点。
     */
    @Test
    fun `表达式位前置 LOCALF++ 解析期硬拒`() {
        val e = assertFailsWith<RuntimeException> {
            ErbSnippetHarness.run(
                lines = listOf("@T", "A = ++LOCALF"),
                entry = "T",
                predeclare = floatSystemVars(),
            )
        }
        assertTrue(
            e.message.orEmpty().contains("无法对浮点型使用单相运算符"),
            "期望解析期拒「无法对浮点型使用单相运算符\"++\"」（fork-src 行号不可本机取证，" +
                "TODO(verify)：待 fork-src 就位后复核）；实得「${e.message}」",
        )
    }

    /**
     * 反面对照：**语句位** `LOCALF++` 仍然放行（fork-src `VariableTerm.cs:225-240`
     * 有 `ChangeValue(double, exm)`，与表达式位是两条路）。
     *
     * 这一条把「拒」钉在正确的位上：若哪天有人把表达式位的拒绝当成「浮点根本不支持自增」
     * 而连语句位一起收紧，这里会先红。读数 0.0 → 1.0，取 `TOINT(LOCALF * 10)` = 10。
     */
    @Test
    fun `语句位 LOCALF++ 仍放行以钉住拒绝位置`() {
        val store = ErbSnippetHarness.run(
            lines = listOf("@T", "LOCALF = 0.0", "LOCALF++", "A = TOINT(LOCALF * 10)"),
            entry = "T",
            predeclare = floatSystemVars(),
        )
        assertEquals(10L, intOf(store, "A"), "语句位 ++ 折成 LOCALF = LOCALF + 1.0 ⇒ 0.0 → 1.0")
    }

    // ------------------------------------------------------------------
    // 4) 表达式通道边界：逐条钉具体值 / 具体报错
    // ------------------------------------------------------------------

    /**
     * 浮点变量进**整型语境**必须报错，不许静默截断。
     *
     * fork-src 判据：`VariableToken.cs:102` 的基类 `GetIntValue` 在 `IsFloat` 时抛
     * `Error.CallFloatAsInt`（zhs 原文「浮点型变量"{0}"被当作整数调用」）—— 原版是**运行期**
     * 拒绝；本仓更严一层，`IF` 这类整型语境在**解析期**的语句参数类型校验上就先拒
     * （「IF命令:第1参数的类型错误」），拒绝点前移、不放宽，仍是硬错误。
     *
     * 走到存储层 `readInt` 的那条runtime路径由 `浮点变量作数组下标报错` 覆盖
     * （下标表达式恒按 `getIntValue` 求值，语句参数位被类型校验挡住、下标位不会）。
     */
    @Test
    fun `浮点变量进整型语境解析期即拒`() {
        val e = assertFailsWith<RuntimeException> {
            ErbSnippetHarness.run(
                lines = listOf("@T", "#DIMF DYNAMIC 倍率", "倍率 = 1.5", "IF 倍率", "A = 1", "ENDIF"),
                entry = "T",
                predeclare = floatSystemVars(),
            )
        }
        assertTrue(
            e.message.orEmpty().contains("类型错误") || e.message.orEmpty().contains("浮点"),
            "期望整型语境拒浮点（类型错误或浮点读通道错误）；实得「${e.message}」",
        )
    }

    /**
     * 浮点作**数组下标**报错（非整数下标）。
     *
     * 下标求值恒走 `getIntValue`（`VarRef.getIntValue` 里 `indexArray[i].getIntValue(ctx)`），
     * 于是浮点变量在 1 维 `YOGORE:(倍率)` 上落到同一条整数读拒绝。
     * 判据与上一条同源（`VariableToken.cs:102`）；fork 在 `VariableTerm.cs:55` 逐字用
     * `arguments[i].GetIntValue(exm)` 求下标，因此拒绝点也在下标表达式本身。
     */
    @Test
    fun `浮点变量作数组下标报错`() {
        val e = assertFailsWith<CodeEE> {
            ErbSnippetHarness.run(
                lines = listOf(
                    "#DIM YOGORE, 10",
                    "@T",
                    "#DIMF DYNAMIC 倍率",
                    "倍率 = 1.0",
                    "YOGORE:(倍率) = 5",
                ),
                entry = "T",
                predeclare = floatSystemVars(),
            )
        }
        assertTrue(
            e.message.orEmpty().contains("浮点"),
            "期望下标位拒浮点；实得「${e.message}」",
        )
    }

    /**
     * 0 维浮点（`RESULTF`）带下标报错。
     *
     * 实测拒绝点在**解析期**的变量形态校验（文案「变量 RESULTF 不带下标」），而不是
     * `VariableStore.checkFloatScalarSlot` 那句「变量 X 是 0 维变量，不带下标」—— 后者
     * 从这条形态走不到（装载期就把形态拦下）。两处口径同向，本条钉住「带下标即硬错误」，
     * 并记录真正的文案来自哪一层。
     *
     * fork 侧 0 维判据：`VariableToken.cs:947-971` 的 FloatScalarVariableToken 只有标量通道，
     * 越界下标由 `VariableToken.cs:105/:112` 的 `Error.CallNonFloatAsFloat` 一族兜住。
     */
    @Test
    fun `零维浮点带下标报错`() {
        val e = assertFailsWith<RuntimeException> {
            ErbSnippetHarness.run(
                lines = listOf("@T", "RESULTF:0 = 1.0"),
                entry = "T",
                predeclare = floatSystemVars(),
            )
        }
        assertTrue(
            e.message.orEmpty().contains("不带下标"),
            "期望 0 维形态拒下标；实得「${e.message}」",
        )
    }

    /**
     * 浮点进字符串形态的 `{ }` 内插值：4.981 fork **放行**，渲染走浮点通道。
     *
     * fork-src `StrForm.cs:140-144` 的 CurlyBrace 支判据是
     * `if (operand.GetEraType() != EraType.Integer && operand.GetEraType() != EraType.Float) throw`
     * ⇒ 浮点放行；`FormatCurlyBrace`（同文件 :232-239）按静态类型分派，
     * 浮点走 `GetFloatValue(exm).ToString()`。1.824 的 `StrForm.cs:310-315` 只判
     * `typeof(long)` ⇒ 拒 —— 移植侧此前照 1.824，路次 g5 已按 fork 放行并补 float 渲染支。
     */
    @Test
    fun `浮点进字符串内插值放行并按浮点渲染`() {
        val store = ErbSnippetHarness.run(
            lines = listOf("@T", "#DIMF DYNAMIC 倍率", "倍率 = 1.5", "STR:0 = 前{倍率}后"),
            entry = "T",
            predeclare = floatSystemVars(),
        )
        assertEquals(
            "前1.5后",
            store.readStr("STR", longArrayOf(0)),
            "fork FormatCurlyBrace 走 GetFloatValue().ToString()；JVM Double.toString(1.5) = \"1.5\"",
        )
    }

    /**
     * 浮点参与**字符串拼接**（`"串" + 倍率`）解析期即拒。
     *
     * 判据 `expr/Parser.kt:807-812`：浮点侧显式排除「一边是 STR」的组合，报
     * 「字符串型 + 浮点型：不合法的组合」。fork 侧 `OperatorMethod.cs:91-119` 的
     * `binaryMixedFloatDic` 只登记 Integer 与 Float 的混合，String×Float 落
     * :226-250 的类型名报错支 ⇒ 两侧口径一致，这条不是偏离。
     * 上列 `OperatorMethod.cs` 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）；
     * 可达替代依据：本仓 `expr/Parser.kt:807-812` 的显式排除分支。
     */
    @Test
    fun `浮点参与字符串拼接解析期硬拒`() {
        val e = assertFailsWith<RuntimeException> {
            ErbSnippetHarness.run(
                lines = listOf("@T", "#DIMF DYNAMIC 倍率", "倍率 = 1.5", "S = \"串\" + 倍率"),
                entry = "T",
                predeclare = floatSystemVars(),
            )
        }
        assertTrue(
            e.message.orEmpty().contains("不合法的组合"),
            "期望「字符串型 + 浮点型：不合法的组合」；实得「${e.message}」",
        )
    }

    /**
     * 浮点左值用 `'=`（`ASSIGNMENT_STR`）赋值明确拒绝，不静默走字符串写口。
     * 判据在本路文件 `stmt/ArgBuilders.kt:1845`。
     */
    @Test
    fun `浮点左值单引号等号赋值被拒`() {
        val e = assertFailsWith<RuntimeException> {
            ErbSnippetHarness.run(
                lines = listOf("@T", "#DIMF DYNAMIC 倍率", "倍率 '= 1.0"),
                entry = "T",
                predeclare = floatSystemVars(),
            )
        }
        assertTrue(
            e.message.orEmpty().contains("不能使用"),
            "期望「浮点变量不能使用 '$' 赋值」；实得「${e.message}」",
        )
    }

    // ------------------------------------------------------------------
    // 5) 本路取证发现的三处**偏离**：已随 g5（表达式面）与 g6（TOFLOAT 字符串实参）对齐，
    //    下列用例从「钉旧现状」翻成「钉 fork 语义」
    // ------------------------------------------------------------------

    /**
     * 浮点取余 `%`：fork **不注册**，解析期硬拒。
     *
     * 判据 fork-src `OperatorMethod.cs:80-89`（binaryFloatFloatDic 只有 Plus/Minus/Mult/Div
     * 加六个比较）与 :91-119（binaryMixedFloatDic 同一组，混 int 侧也没有 Mod），
     * 全文件只有 `ModIntInt`（:378）一条取余实现 ⇒ fork 上 `7.5 % 2.0` 落
     * `ReduceBinaryTerm` 的 :225-245 抛 CodeEE
     * （「无法对浮点型和浮点型使用二相运算符"%"进行运算」）。
     * 移植侧此前把 MOD 一并折成 FLOAT 二元（能算出 1.5），路次 g5 已按 fork 收紧。
     * 上列 `OperatorMethod.cs` 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）；
     * 可达替代依据：本机 `OperatorMethodManager.cs:140-157` 的整型 `ModIntInt` 实现与 `:725` 登记。
     */
    @Test
    fun `浮点取余按 fork 解析期硬拒`() {
        val e = assertFailsWith<RuntimeException> {
            ErbSnippetHarness.run(
                lines = listOf("@T", "A = TOINT(7.5 % 2.0 * 10)"),
                entry = "T",
                predeclare = floatSystemVars(),
            )
        }
        assertTrue(
            e.message.orEmpty().contains("二相运算符") && e.message.orEmpty().contains("%"),
            "期望解析期拒「无法对浮点型和浮点型使用二相运算符\"%\"进行运算」；实得「${e.message}」",
        )
    }

    /**
     * `TOFLOAT` 收字符串实参：fork 只接受字符串，失败恒 0.0。
     *
     * 判据 fork-src `Creator.Method.cs:6145-6164`：`argumentTypeArray = [EraType.String]`、
     * `ReturnType = EraType.Float`，实现体是
     * `if (str == null || string.IsNullOrEmpty(str)) return 0.0;` →
     * `if (str.Length < LangManager.GetStrlenLang(str)) return 0.0;` →
     * `if (double.TryParse(str, out double result)) return result; return 0.0;`
     * ⇒ 空串/长度不足/解析失败一律 **0.0**，不报错。
     * 另注意 `double.TryParse` **不带** `CultureInfo.InvariantCulture`（与字面量侧的
     * `ReadDouble` 用不变文化不同）—— specs/isa.yaml:2410 与 verify-notes.md §23.1 已记该文化差。
     *
     * 移植侧此前报「不支持字符串实参」，路次 g6 已按 fork 落地。
     */
    @Test
    fun `TOFLOAT 收字符串实参按 fork 返回浮点`() {
        val store = ErbSnippetHarness.run(
            lines = listOf("@T", "A = TOINT(TOFLOAT(\"1.75\"))", "B = TOINT(TOFLOAT(\"\"))"),
            entry = "T",
            predeclare = floatSystemVars(),
        )
        assertEquals(1L, intOf(store, "A"), "TOFLOAT(\"1.75\") = 1.75 ⇒ TOINT 向零截断 1")
        assertEquals(0L, intOf(store, "B"), "空串恒 0.0（fork-src 行号不可本机取证，TODO(verify)：待 fork-src 就位后复核）")
    }

    /**
     * 浮点字面量进**整型上下文**：fork **静默向零截断**（ADR-005 原样复刻）。
     *
     * 判据 fork-src `Term.cs:117-120`：`SingleFloatTerm.GetIntValue` 直接
     * `return (long)fValue` ⇒ 语料里 `C:1.5` 这类形态在原版读的是 1 号格，不报错。
     * 移植侧此前 `FloatLiteral.getIntValue` 抛「浮点表达式不能直接用于整型上下文」，
     * 路次 g5 已改为 `longFromScaledDouble`（与 TOINT 的浮点支同一函数）。
     *
     * 影响面**不在赋值位**（那条被 `stmt/ArgBuilders` 与 `ErbProgramCompiler` 的类型闸先挡住），
     * 而在**数组下标位**：下标表达式恒按 `getIntValue` 求值，故 `YOGORE:1.5` 落 1 号格。
     */
    @Test
    fun `浮点字面量作下标按 fork 静默向零截断`() {
        val store = ErbSnippetHarness.run(
            lines = listOf("#DIM YOGORE, 10", "@T", "YOGORE:1.5 = 7"),
            entry = "T",
            predeclare = floatSystemVars(),
        )
        assertEquals(
            7L,
            store.readInt("YOGORE", longArrayOf(1)),
            "(long)1.5 = 1 ⇒ 写入 1 号格（fork-src 行号不可本机取证，TODO(verify)：待 fork-src 就位后复核；向零截断）",
        )
        assertEquals(0L, store.readInt("YOGORE", longArrayOf(0)), "0 号格不受影响，钉住截断方向")
    }

    /**
     * `RESULTF` 由表达式写回后按浮点读精确。
     *
     * 与 `语料抄写 LOCALF 累加十间真值 12 与 12_5` 用同一个乘数（0.01），但走
     * `RESULTF`（0 维标量）通道，钉住「标量浮点列」与「1 维浮点局部」两条存储不是同一条。
     */
    @Test
    fun `RESULTF 标量通道精确读回`() {
        val store = ErbSnippetHarness.run(
            lines = listOf("@T", "RESULTF = 0.0", "A = 125", "RESULTF += A * 0.01", "B = TOINT(RESULTF * 100)"),
            entry = "T",
            predeclare = floatSystemVars(),
        )
        assertEquals(125L, intOf(store, "B"), "125 * 0.01 = 1.25 ⇒ ×100 截断读回 125")
    }

    // ------------------------------------------------------------------
    // 装置与语料只读口
    // ------------------------------------------------------------------

    /**
     * 系统浮点变量的解析面预声明（`LOCALF`/`ARGF`/`RESULTF` 不在 ERB 里 `#DIM`）。
     * 与生产链 `LineParser` 的 fork 浮点补丁层同口径，也和 FloatRuntimeE2ETest 一致。
     */
    private fun floatSystemVars(): SymbolTable.() -> Unit = {
        declareVariable("LOCALF", OperandType.FLOAT, 1)
        declareVariable("ARGF", OperandType.FLOAT, 1)
        declareVariable("RESULTF", OperandType.FLOAT, 0)
    }

    private fun intOf(store: com.eratw.port.core.vars.VariableStore, name: String): Long =
        store.readInt(name, longArrayOf(0))

    /**
     * 四条「语料原文」用例钉的是 4.981 的文件行号与 DLC 声明（`使用魔法.ERB:4` 的
     * `#DIM DYNAMIC`、`魔法相关函数.ERB:3188` 起）。4.980 语料同名文件的行号与内容都不同
     * （AGENTS 现役基准仍是 4.980），故按语料版本显式跳过，避免行号断言误报。
     */
    private fun require4981Corpus() {
        val probe = File(corpusRoot(), USE_MAGIC)
        val fourth = if (probe.isFile) {
            probe.readText().removePrefix(BOM).split('\n').getOrNull(3)?.removeSuffix("\r")?.trim()
        } else {
            null
        }
        Assumptions.assumeTrue(
            fourth == "#DIM DYNAMIC 经验获取量",
            "语料非 4.981（$USE_MAGIC:4 = ${fourth ?: "<缺文件>"}）：跳过 4.981 语料锚点",
        )
    }

    /** 语料根目录；未设 `ERATW_CORPUS` 时按 skipped 计，不静默 PASS（AGENTS §5 口径）。 */
    private fun corpusRoot(): File {
        val corpus = System.getenv("ERATW_CORPUS")
        Assumptions.assumeTrue(
            corpus != null && File(corpus).isDirectory,
            "未设 ERATW_CORPUS：跳过语料原文锚点用例",
        )
        return File(corpus)
    }

    /**
     * 按 1 基行号取真实语料的**物理行**（去 BOM/CR、去首尾空白）。
     *
     * 只读：本文件不写回语料，也不把原文落进 `resources`（抄写片段是另一份，见 resources 头注）。
     */
    private fun corpusLine(rel: String, lineNo: Int): String {
        val file = File(corpusRoot(), rel)
        assertTrue(file.isFile, "语料缺文件 $rel")
        val lines = file.readText().removePrefix(BOM).split('\n').map { it.removeSuffix("\r") }
        assertTrue(lines.size >= lineNo, "$rel 只有 ${lines.size} 行，取不到第 $lineNo 行")
        return lines[lineNo - 1].trim()
    }

    private fun assertCorpusLine(rel: String, lineNo: Int, expected: String) {
        assertEquals(expected, corpusLine(rel, lineNo), "语料 $rel:$lineNo 原文与用例假设不一致")
    }

    /**
     * [name] 在 [line] 里是否紧邻 `++`/`--`（前置或后置皆算），且两边不是标识符字符。
     * 边界判定是为了不把 `LOCALFLAG++` 误当成 `LOCALF` 的自增。
     */
    private fun adjacentToIncrement(name: String, line: String): Boolean {
        val lower = line.lowercase()
        val n = name.lowercase()
        fun identChar(c: Char) = c.isLetterOrDigit() || c == '_'
        var i = lower.indexOf(n)
        while (i >= 0) {
            val after = i + n.length
            val leftOk = i == 0 || !identChar(lower[i - 1])
            val rightOk = after >= lower.length || !identChar(lower[after])
            if (leftOk && rightOk) {
                val tailStart = lower.substring(after).takeWhile { it == ' ' || it == '\t' }.length + after
                if (lower.startsWith("++", tailStart) || lower.startsWith("--", tailStart)) return true
                val head = lower.substring(0, i).trimEnd()
                if (head.endsWith("++") || head.endsWith("--")) return true
            }
            i = lower.indexOf(n, after)
        }
        return false
    }

    private companion object {
        /** UTF-8 BOM（U+FEFF）；语料部分文件带 BOM，第一行会多出这个不可见字符。 */
        val BOM: String = Char(0xFEFF).toString()

        const val USE_MAGIC = "DLC/魔法DLC/使用魔法.ERB"
        const val MAGIC_FN = "DLC/魔法DLC/魔法相关函数.ERB"
        const val DIM_ERH = "DIM.ERH"
    }
}
