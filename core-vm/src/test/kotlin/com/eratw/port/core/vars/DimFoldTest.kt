package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.SystemVariableBootstrap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M4-M `m-dimfold`：#DIM 装载期折叠面（起点 = main@a67c3d5）。
 *
 * 靶子 = DimFailTriageTest 的 12 条真缺口里本路修复的部分：
 * 字符串 `+`（G4/G5）、位移 `<<`/`>>`（P7）、装载期折叠函数注入 VARSIZE/POWER/
 * COLOR_FROMNAME/UNICODE（G1/G2/G3/P1/P4/P5）。
 * 断言的是**折叠后的具体值**，不是「不抛」。
 *
 * 原版依据（逐条行号）：
 * - 折叠总口径 `FunctionMethodTerm.cs:35-56`（CanRestructure + 实参全 SingleTerm →
 *   `GetValue(exm)`）；`UserDefinedVariableData.cs:325-326`（尺寸段须 SingleLongTerm）、
 *   `:375/:405-408`（初值段逐项 SingleTerm）。
 * - 字符串 `+`：`OperatorMethodManager.cs:28-33`（PlusStrStr，GetStrValue :41-44）。
 * - 位移：`OperatorMethodManager.cs:523-534`（注册 :741）、`:509-520`（注册 :740）。
 *   按位/逻辑层（4.981 COMF641 波）：单 `&`/`|`/`^` 是**按位**（`:467-507`，注册 :737-739；
 *   旧注「原版是逻辑与或」系把 `&&`/`||` 的 AndIntInt/OrIntInt `:375-410` 误配单字符——
 *   单 `&`/`|` 的词法映射是 BitAnd/BitOr，LexicalAnalyzer.cs:479-491），
 *   `&&`/`||`/`^^`/`!&`/`!|` 才是逻辑（`:375-465`，注册 :732-736）。
 *   4.981 随附原版引擎（1824+v24+EMv18+EEv56+Skiav16）实测 `#DIM CONST` 初值
 *   `1 << 1 | 1 << 2` 折 **6**、`0 | 2` 折 **2**、`5 & 3 | 8` 折 **9**、
 *   `1 << 0 | 1 << 1 | 1 << 2` 折 **7**（emuera.log 取证，2026-11-04）。
 * - VARSIZE `FunctionMethodCreator.cs:3437-3454/:3456-3473`、POWER `:4169/:4172-4190`、
 *   COLOR_FROMNAME `:3814/:3817-3835`、UNICODE `:5697/:5700-5720`。
 * - CONST 标识符自身可折叠：`UserDefinedVariableToken.cs:35`（CanRestructure = isConst），
 *   P7 的 `K17C_受向妄想数` = 语料 M_KOJO_K17_追加ライブラリ.ERH:185（实测原文 `= 7`，
 *   :186 攻向同为 7）。
 *
 * 非空转口径（worker 不跑 gradle，静态推演）：本文件里「注入后可折」的每条用例，
 * 在未注入回调（或改动前）的代码路径都会在 `constInt`/`strOperand` 的函数调用分支
 * 抛 CodeEE——`未注入回调时函数调用保持失败口径` 用例（对照 DimFailTriageTest 的
 * `缺口形态最小复现逐点钉住` 未注入对照组）就是这条判据的现网读数；
 * 位移/字符串 `+` 用例在改动前的 Folder 里分别报「缺少右括号」「声明末尾有多余内容」
 * ——DimFailTriageTest 的 M4-L 旧钉桩（改钉前 git 历史）即为证据。
 */
class DimFoldTest {

    private fun parseDim(raw: String, isPrivate: Boolean, cfg: DimParseConfig): DimSpec {
        val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw))
            ?: throw AssertionError("不是 #DIM/#DIMS 声明: $raw")
        return DimParser.parse(split.first, split.second, isPrivate, cfg)
    }

    private fun folded(varSizes: Map<String, LongArray> = emptyMap()): DimParseConfig =
        DimParseConfig(systemSaveInBinary = true).withPureStaticDimFolders(varSizes)

    // ------------------------------------------------------------------
    // 1. 位移 + 字符串 `+`：不依赖注入，纯 Folder 面
    // ------------------------------------------------------------------

    @Test
    fun `位移折叠成具体值`() {
        assertEquals(7L, parseDim("#DIM CONST TRI = (1<<3)-1", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(1L, parseDim("#DIM CONST TRI = 3>>1", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(1028L, parseDim("#DIM CONST TRI = (1<<10)+(16>>2)", false, DimParseConfig()).defaultsInt!![0])
        // C# 移位计数按低 6 位取值（long <<），Kotlin shl 同语义：1 << 64 == 1 << 0。
        assertEquals(1L, parseDim("#DIM CONST TRI = 1<<64", false, DimParseConfig()).defaultsInt!![0])
        // 优先级按原版而非 C#：移位优先级字节 112 低于加减 128（OperatorCode.cs:6/:11/:34-35），
        // 故 `1<<2+3` 折成 `1<<(2+3)` = 32（C# 原生会是 (1<<2)+3 = 7 —— 不许照 C# 抄）。
        assertEquals(32L, parseDim("#DIM CONST TRI = 1<<2+3", false, DimParseConfig()).defaultsInt!![0])
        // 左结合：`1<<2<<3` = (1<<2)<<3 = 32。
        assertEquals(32L, parseDim("#DIM CONST TRI = 1<<2<<3", false, DimParseConfig()).defaultsInt!![0])
    }

    @Test
    fun `P7 形态私有 CONST 位移折成 127`() {
        // K17C_受向妄想数 / K17C_攻向妄想数 = 7（语料 M_KOJO_K17_追加ライブラリ.ERH:185-186 实测）。
        val cfg = DimParseConfig(
            systemSaveInBinary = true,
            intConstants = mapOf("K17C_受向妄想数" to 7L, "K17C_攻向妄想数" to 7L),
        )
        val spec = parseDim("#DIM CONST 既读面罩 = (1<<K17C_受向妄想数)-1,(1<<K17C_攻向妄想数)-1", true, cfg)
        assertContentEquals(listOf(127L, 127L), spec.defaultsInt!!.toList())
        assertEquals(2, spec.dims[0])
    }

    // ------------------------------------------------------------------
    // 1.5 按位层 + 逻辑层（4.981 COMF641 波）：`&`/`|`/`^` 按位、双字符族逻辑
    // ------------------------------------------------------------------

    @Test
    fun `COMF641 卡片类型表按位或逐元素折叠`() {
        // 4.981 语料 COMF641 アビリティカード_カードパラメータ.ERH:226-259 的 33 元素
        // `#DIM CONST` 初值表缩微：元素文本逐条对齐原文（IDX 常量名换成同值字面映射），
        // 期望值 = 按位折叠（原版引擎对同式实测 6/2/9/7，见文件头）。
        // 改动前该表每个含 `|` 的元素都在 reduceInt 报「尺寸/初值表达式有多余内容」，
        // 整条变量未注册，运行期 GETBIT 处抛 CodeEE「未定义的变量」。
        val cfg = DimParseConfig(
            systemSaveInBinary = true,
            intConstants = mapOf(
                "COM641_AC_カードIDX_ステータス補助" to 0L,
                "COM641_AC_カードIDX_戦闘補助" to 1L,
                "COM641_AC_カードIDX_技能補助" to 2L,
                "COM641_AC_カードIDX_うふふ補助" to 3L,
                "COM641_AC_カードIDX_特殊効果" to 10L,
            ),
        )
        val spec = parseDim(
            "#DIM CONST COM641_AC_カードタイプ付与BIT配列 = " + listOf(
                "1 << COM641_AC_カードIDX_特殊効果",
                "1 << COM641_AC_カードIDX_戦闘補助 | 1 << COM641_AC_カードIDX_技能補助",
                "1 << COM641_AC_カードIDX_戦闘補助 | 1 << COM641_AC_カードIDX_技能補助",
                "1 << COM641_AC_カードIDX_ステータス補助 | 1 << COM641_AC_カードIDX_戦闘補助 | 1 << COM641_AC_カードIDX_技能補助",
                "1 << COM641_AC_カードIDX_ステータス補助 | 1 << COM641_AC_カードIDX_戦闘補助 | 1 << COM641_AC_カードIDX_技能補助",
                "1 << COM641_AC_カードIDX_ステータス補助",
                "1 << COM641_AC_カードIDX_ステータス補助",
                "1 << COM641_AC_カードIDX_戦闘補助",
                "1 << COM641_AC_カードIDX_戦闘補助",
                "1 << COM641_AC_カードIDX_ステータス補助",
                "1 << COM641_AC_カードIDX_ステータス補助",
                "1 << COM641_AC_カードIDX_ステータス補助",
                "1 << COM641_AC_カードIDX_ステータス補助",
                "1 << COM641_AC_カードIDX_ステータス補助",
                "1 << COM641_AC_カードIDX_ステータス補助",
                "1 << COM641_AC_カードIDX_ステータス補助",
                "1 << COM641_AC_カードIDX_戦闘補助",
                "1 << COM641_AC_カードIDX_戦闘補助",
                "1 << COM641_AC_カードIDX_戦闘補助",
                "1 << COM641_AC_カードIDX_ステータス補助",
                "1 << COM641_AC_カードIDX_ステータス補助",
                "1 << COM641_AC_カードIDX_戦闘補助",
                "1 << COM641_AC_カードIDX_戦闘補助",
                "1 << COM641_AC_カードIDX_技能補助",
                "1 << COM641_AC_カードIDX_ステータス補助 | 1 << COM641_AC_カードIDX_特殊効果",
                "1 << COM641_AC_カードIDX_技能補助",
                "1 << COM641_AC_カードIDX_戦闘補助",
                "1 << COM641_AC_カードIDX_戦闘補助 | 1 << COM641_AC_カードIDX_技能補助",
                "1 << COM641_AC_カードIDX_戦闘補助 | 1 << COM641_AC_カードIDX_技能補助",
                "1 << COM641_AC_カードIDX_戦闘補助 | 1 << COM641_AC_カードIDX_うふふ補助",
                "1 << COM641_AC_カードIDX_戦闘補助",
                "1 << COM641_AC_カードIDX_戦闘補助 | 1 << COM641_AC_カードIDX_技能補助",
                "1 << COM641_AC_カードIDX_ステータス補助 | 1 << COM641_AC_カードIDX_技能補助",
            ).joinToString(", "),
            false,
            cfg,
        )
        assertContentEquals(
            listOf(
                1024L, 6L, 6L, 7L, 7L, 1L, 1L, 2L, 2L, 1L, 1L, 1L, 1L, 1L, 1L, 1L,
                2L, 2L, 2L, 1L, 1L, 2L, 2L, 4L, 1025L, 4L, 2L, 6L, 6L, 10L, 2L, 6L, 5L,
            ),
            spec.defaultsInt!!.toList(),
        )
        assertEquals(33, spec.dims[0])
    }

    @Test
    fun `按位与或异或同层左结合`() {
        // 同层（优先级字节 80）左结合，与 C 的 `&` > `^` > `|` 三层不同：
        // `2 | 4 & 5` 折成 (2|4)&5 = 4（C 原生会是 2|(4&5)=6 —— 不许照 C 抄）。
        assertEquals(4L, parseDim("#DIM CONST TRI = 2 | 4 & 5", false, DimParseConfig()).defaultsInt!![0])
        // 以下 3 条 = 原版引擎实测读数（emuera.log 取证，2026-11-04）。
        assertEquals(9L, parseDim("#DIM CONST TRI = 5 & 3 | 8", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(0L, parseDim("#DIM CONST TRI = 1 & 2", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(2L, parseDim("#DIM CONST TRI = 0 | 2", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(8L, parseDim("#DIM CONST TRI = 12 & 10", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(6L, parseDim("#DIM CONST TRI = 12 ^ 10", false, DimParseConfig()).defaultsInt!![0])
        // 位移(112)比按位(80)紧：`12 & 5 << 1` = 12 & (5<<1) = 8；括号内回到最松层。
        assertEquals(8L, parseDim("#DIM CONST TRI = 12 & 5 << 1", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(24L, parseDim("#DIM CONST TRI = (1 | 2) << 3", false, DimParseConfig()).defaultsInt!![0])
        // 负数照常按位（BitAndIntInt/BitOrIntInt 直接 & / |，非「非零即真」）。
        assertEquals(-2L, parseDim("#DIM CONST TRI = -2 & -1", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(-1L, parseDim("#DIM CONST TRI = -2 | -1", false, DimParseConfig()).defaultsInt!![0])
    }

    @Test
    fun `按位与或异或折叠成具体值`() {
        // 并入自并行会话同缺陷修复波（2026-11-04 合流冲突归一）：一元 `~` 与两元素形态钉桩。
        // 4.981 fork 的 BitAnd/BitOr/BitXor：优先级字节都是 80（spec: OperatorCode.cs:6/:29-31），
        // 低于位移 112 ⇒ `1<<2|1<<3` = 4|8 = 12；三者同档、左结合 ⇒ `7^3&1` = (7^3)&1 = 0。
        assertEquals(12L, parseDim("#DIM CONST TRI = 1<<2|1<<3", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(0L, parseDim("#DIM CONST TRI = 7^3&1", false, DimParseConfig()).defaultsInt!![0])
        // 一元 `~`（BitNot 带 __UNARY__ 标记，spec: OperatorCode.cs:7/:33）：`~5 & 3` = (-6) & 3 = 2。
        assertEquals(2L, parseDim("#DIM CONST TRI = ~5&3", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(-1L, parseDim("#DIM CONST TRI = ~0", false, DimParseConfig()).defaultsInt!![0])
        // 括号把优先级整体抬回入口：`1 << (1|2)` = 8。
        assertEquals(8L, parseDim("#DIM CONST TRI = 1<<(1|2)", false, DimParseConfig()).defaultsInt!![0])
        // 语料实况形态（COMF641 アビリティカード_カードパラメータ.ERH:226）：多值 + `<<` + `|`。
        val spec = parseDim("#DIM CONST TRI = 1<<2|1<<3, 1<<0|1<<1", false, DimParseConfig())
        assertContentEquals(listOf(12L, 3L), spec.defaultsInt!!.toList())
        assertEquals(2, spec.dims[0])
    }

    @Test
    fun `逻辑与或非层返回0或1`() {
        // 双字符族 = 逻辑层（优先级 64）：非 0 判真、结果只取 0/1
        // （OperatorMethodManager.cs:375-391 And / :393-409 Or / :411-429 Xor /
        // :431-447 Nand / :449-465 Nor，注册 :732-736）。判值式与 Expr.kt 二元求值同式。
        assertEquals(1L, parseDim("#DIM CONST TRI = 2 && 3", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(0L, parseDim("#DIM CONST TRI = 0 && 3", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(1L, parseDim("#DIM CONST TRI = 0 || 5", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(0L, parseDim("#DIM CONST TRI = 0 || 0", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(1L, parseDim("#DIM CONST TRI = 2 ^^ 0", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(0L, parseDim("#DIM CONST TRI = 2 ^^ 3", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(1L, parseDim("#DIM CONST TRI = 0 !& 9", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(0L, parseDim("#DIM CONST TRI = 2 !& 9", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(1L, parseDim("#DIM CONST TRI = 0 !| 0", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(0L, parseDim("#DIM CONST TRI = 2 !| 0", false, DimParseConfig()).defaultsInt!![0])
        // 分层锁定：`|`(80) 比 `&&`(64) 紧 ⇒ `0 && 1 | 2` = 0 && (1|2) = 0；
        // COMF641 主形态 `1 << 1 | 1 << 2` = 6（逻辑或会得 1，位移先折同见原版引擎实测）。
        assertEquals(0L, parseDim("#DIM CONST TRI = 0 && 1 | 2", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(1L, parseDim("#DIM CONST TRI = 3 && 0 | 2", false, DimParseConfig()).defaultsInt!![0])
        assertEquals(6L, parseDim("#DIM CONST TRI = 1 << 1 | 1 << 2", false, DimParseConfig()).defaultsInt!![0])
    }

    @Test
    fun `单感叹号不进折叠面保持旧失败方向`() {
        // `!`（Not 一元）不在折叠面也不进 SYMS：`!FOO` 仍按 Word 整体进 constInt，
        // 报「不能求值为整数常量」（与改动前同文案）；`!&`/`!|` 双字符才算逻辑层
        // （spec: LexicalAnalyzer.cs:440-452 `!` 分支，双字符支 Nand/Nor、单字符 Not 未接）。
        val e = assertFailsWith<CodeEE> { parseDim("#DIM CONST TRI = !FOO", false, DimParseConfig()) }
        assertTrue((e.message ?: "").contains("!FOO"), "应保留 !FOO 的旧失败文案：${e.message}")
    }

    @Test
    fun `字符串加号链折叠成具体串`() {
        // G4 形态（OperatorMethodManager.cs:28-33/:41-44）。
        val spec = parseDim(
            "#DIMS CONST TRI = \"\" + " +
                "\"[青:0][紫:1]\" + " +
                "\"[普通:31]\" + @\"恶\"",
            false,
            DimParseConfig(),
        )
        assertEquals(listOf("[青:0][紫:1][普通:31]恶"), spec.defaultsStr)
        // 单段 `+` 两侧都要能折（常量标识符走 strConstants，UserDefinedVariableData.cs:405-413）。
        val cfg = DimParseConfig(strConstants = mapOf("K_PRE" to "<b>"))
        val s2 = parseDim("#DIMS CONST TRI = K_PRE + \"x\" + \"\"", false, cfg)
        assertEquals(listOf("<b>x"), s2.defaultsStr)
    }

    @Test
    fun `未注入回调时函数调用保持失败口径`() {
        val plain = DimParseConfig()
        for ((f, form) in listOf(
            "VARSIZE" to "#DIM TRI_X, VARSIZE(\"PALAM\")",
            "UNICODE" to "#DIMS CONST TRI_X = @\"%UNICODE(0x2660)%\"",
            "COLOR_FROMNAME" to "#DIM CONST TRI_X = 0x44000000 + COLOR_FROMNAME(\"dimgray\")",
            "POWER" to "#DIM CONST TRI_X = POWER(1 , 2)",
        )) {
            val e = assertFailsWith<CodeEE> { parseDim(form, false, plain) }
            assertTrue((e.message ?: "").contains("函数调用"), "$f 未注入时应报「函数调用」，实际：${e.message}")
        }
        // 注入后仍不接外部数据源的：关键字表缺席的 GETNUM、整型实参的 GETLINESTR
        // 都保持失败方向（回调返回 null，不许静默变成功）。
        for ((f, form) in listOf(
            "GETNUM" to "#DIM CONST TRI_X = GETNUM(TALENT , 2)",
            "GETLINESTR" to "#DIM DYNAMIC TRI_X = GETLINESTR(1)",
        )) {
            val e = assertFailsWith<CodeEE> { parseDim(form, true, folded()) }
            assertTrue((e.message ?: "").contains("函数调用"), "$f 应仍报「函数调用」，实际：${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 2. 纯静态折叠函数：注入后折成具体值
    // ------------------------------------------------------------------

    @Test
    fun `VARSIZE 注入尺寸表后折叠成维度值`() {
        val cfg = folded(mapOf("PALAM" to longArrayOf(500, 8, 12), "RELATION" to longArrayOf(20)))
        // G3 形态：尺寸段函数调用（FunctionMethodCreator.cs:3437-3454）。
        val g3 = parseDim("#DIM CHARADATA addGemDateRecord, VARSIZE(\"PALAM\")", false, cfg)
        assertEquals(500, g3.dims[0])
        assertTrue(g3.isCharaData)
        // P4/P5 形态。
        assertEquals(20, parseDim("#DIM SORT_VALUE,  VARSIZE(\"RELATION\")", true, cfg).dims[0])
        assertEquals(20, parseDim("#DIM CHARA_TABLE, VARSIZE(\"RELATION\")", true, cfg).dims[0])
        // 第二实参 = 维下标（:3445-3448 GetLength(num)）。
        assertEquals(12, parseDim("#DIM TRI, VARSIZE(\"PALAM\" , 2)", false, cfg).dims[0])
        // 不在册 / REF 不入表 → UniqueRestructure 返回 false 方向（:3465-3469），保持失败。
        val e = assertFailsWith<CodeEE> { parseDim("#DIM TRI, VARSIZE(\"NO_SUCH_VAR\")", false, cfg) }
        assertTrue((e.message ?: "").contains("函数调用 VARSIZE"))
    }

    @Test
    fun `POWER 折叠成具体值并保留三条越界抛`() {
        val cfg = folded()
        // P1：POWER(2 , 32) - 1 = 4294967295（FunctionMethodCreator.cs:4169/:4172-4190）。
        assertEquals(
            4294967295L,
            parseDim("#DIM CONST C_G_END = POWER(2 , 32) - 1", true, cfg).defaultsInt!![0],
        )
        assertEquals(8L, parseDim("#DIM CONST TRI = POWER(2 , 3)", false, cfg).defaultsInt!![0])
        // (long) 截断：POWER(2.5 形态不存在（整参），但负指数结果 <1 → 0（向零截断）。
        assertEquals(0L, parseDim("#DIM CONST TRI = POWER(2 , -1)", false, cfg).defaultsInt!![0])
        val oor = assertFailsWith<CodeEE> { parseDim("#DIM CONST TRI = POWER(10 , 19)", false, cfg) }
        assertTrue((oor.message ?: "").contains("超出 Int64 范围"))
    }

    @Test
    fun `COLOR_FROMNAME 折叠成具体色值`() {
        val cfg = folded()
        // G1：0x44000000 + COLOR_FROMNAME("dimgray") = 0x44696969（:3814/:3817-3835，
        // ColorNames.BY_NAME dimgray=0x696969）。
        val g1 = parseDim(
            "#DIM CONST LIST_COLOR_DEFAULT = 0x44000000 + COLOR_FROMNAME(\"dimgray\") , 0xFF000000",
            false,
            cfg,
        )
        assertEquals(0x44696969L, g1.defaultsInt!![0])
        assertEquals(0xFF000000L, g1.defaultsInt[1])
        // 未知名折 -1（:3832，grey 系拼写框架不认——ColorNames 口径）。
        assertEquals(-1L, parseDim("#DIM CONST TRI = COLOR_FROMNAME(\"dimgrey\")", false, cfg).defaultsInt!![0])
        val tr = assertFailsWith<CodeEE> { parseDim("#DIM CONST TRI = COLOR_FROMNAME(\"transparent\")", false, cfg) }
        // 官方逐字（`Error.TransparentUnsupported`，Lang.cs:1982 / zhs 表 :450；errtext6 波把本支
        // 原来是**自造串**（还把 C# 行号带上屏）换成官方串）。旧 pin 断言 message 含小写
        // "transparent" —— 那是把自造串当正确行为钉死，随本波按官方原文改写。
        assertEquals("不能将透明(Transparent)指定为颜色", tr.message, "TRANSPARENT 支必须抛官方逐字串")
    }

    @Test
    fun `UNICODE 折进插值串子式并保留越界抛`() {
        val cfg = folded()
        // G2 全行（DIM.ERH:704-708）：5 项里 3 项是 @"%UNICODE(...)%"（StrForm.cs:364-376 并入字面段）。
        val g2 = parseDim(
            "#DIMS CONST CARD_SUIT, 5  = \"？\",  @\"%UNICODE(0x2660)%\",  @\"%UNICODE(0x2663)%\",  @\"♥\",  @\"%UNICODE(0x2666)%\"",
            false,
            cfg,
        )
        assertEquals(listOf("？", "♠", "♣", "♥", "♦"), g2.defaultsStr)
        assertEquals(5, g2.dims[0])
        // 函数名大小写不敏感（原版标识符查表口径）。
        assertEquals("♠", parseDim("#DIMS CONST TRI = @\"%unicode(0x2660)%\"", false, cfg).defaultsStr!![0])
        // 越界抛（:5703-5705）。
        val oor = assertFailsWith<CodeEE> { parseDim("#DIMS CONST TRI = @\"%UNICODE(0x10000)%\"", false, cfg) }
        assertTrue((oor.message ?: "").contains("越界"))
        // 控制字符 = 告警 + 空串（:5707-5717），装载期不中断。
        assertEquals(listOf("ab"), parseDim("#DIMS CONST TRI = @\"a%UNICODE(9)%b\"", false, cfg).defaultsStr)
    }

    @Test
    fun `GETNUM 注入关键字表后折叠成下标未命中折 -1`() {
        // spec: FunctionMethodCreator.cs:4814-4832 —— 第一参是变量身份（不取值），
        // 第二参是关键字字面量，命中返回下标、未命中返回 -1（:4827-4831）。
        val cfg = DimParseConfig(systemSaveInBinary = true).withPureStaticDimFolders(
            keywordTables = mapOf("TALENT" to mapOf("人类" to 0, "妖精" to 1)),
        )
        // 语料形态（CHARALIST_DT/TEMP_CHARA_LIST_BY_DT_FUNC.ERB:2027-2034）：
        // `#DIM CONST C_REF_TALENT = GETNUM(TALENT, "人类"), GETNUM(TALENT, "妖精"), …`
        val spec = parseDim(
            "#DIM CONST C_REF_TALENT = GETNUM(TALENT, \"人类\") , GETNUM(TALENT, \"妖精\")",
            true,
            cfg,
        )
        assertContentEquals(listOf(0L, 1L), spec.defaultsInt!!.toList())
        assertEquals(2, spec.dims[0])
        // 未命中 = -1（不是失败）。
        assertEquals(
            -1L,
            parseDim("#DIM CONST TRI = GETNUM(TALENT, \"无此关键字\")", false, cfg).defaultsInt!![0],
        )
        // 三参形态：内置表优先（C# TryKeywordToInteger 先查内置字典），第三参只影响 ERD 回落。
        assertEquals(
            1L,
            parseDim("#DIM CONST TRI = GETNUM(TALENT, \"妖精\", 2)", false, cfg).defaultsInt!![0],
        )
        // 表不存在 = 不折叠（本层无法确认变量存在，保持失败方向而非伪造 -1）。
        val e = assertFailsWith<CodeEE> { parseDim("#DIM CONST TRI = GETNUM(NO_SUCH, \"x\")", false, cfg) }
        assertTrue((e.message ?: "").contains("函数调用"))
    }

    @Test
    fun `STRLENSU 与 GETLINESTR 折叠成具体宽度`() {
        // spec: FunctionMethodCreator.cs:5275-5288（UTF-16 码元数）、
        // :5894-5911 + EmueraConsole.cs:3420-3436 getStBar（重复/裁剪到可绘制宽）。
        val cfg = DimParseConfig(systemSaveInBinary = true).withPureStaticDimFolders(consoleWidth = 199)
        assertEquals(3L, parseDim("#DIM CONST TRI = STRLENSU(\"abc\")", false, cfg).defaultsInt!![0])
        // C# string.Length = UTF-16 码元数，代理对计 2。
        assertEquals(2L, parseDim("#DIM CONST TRI = STRLENSU(\"😀\")", false, cfg).defaultsInt!![0])
        // `─` 每字 2 半角格：199 格容 99 个（100 个 = 200 格，裁回 99）。
        val bar = parseDim("#DIMS CONST TRI = GETLINESTR(\"─\")", false, cfg).defaultsStr!![0]
        assertEquals("─".repeat(99), bar)
        // 语料真实形态（カラム機能(div).ERB:21）：私有 DYNAMIC 标量。
        val lineChars = parseDim("#DIM DYNAMIC 行文字数 = STRLENSU(GETLINESTR(\"─\"))", true, cfg)
        assertEquals(99L, lineChars.defaultsInt!![0])
        // 空串抛（:5906-5909）。
        val e = assertFailsWith<CodeEE> { parseDim("#DIMS CONST TRI = GETLINESTR(\"\")", false, cfg) }
        assertTrue((e.message ?: "").contains("空串"))
    }

    // ------------------------------------------------------------------
    // 3. 运行期注册链：SystemVariableBootstrap.install 透传注入
    // ------------------------------------------------------------------

    @Test
    fun `install 透传折叠回调后变量真实注册`() {
        val store = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))
        val cfg = folded(mapOf("PALAM" to longArrayOf(500)))
        val decls = listOf(
            SystemVariableBootstrap.RawDeclaration(
                "#DIM CONST LIST_COLOR_DEFAULT = 0x44000000 + COLOR_FROMNAME(\"dimgray\") , 0xFF000000",
                "TEMP_CHARA_LIST_BY_DT.ERH", 24,
            ),
            SystemVariableBootstrap.RawDeclaration(
                "#DIMS CONST CARD_SUIT, 5 = \"？\", @\"%UNICODE(0x2660)%\", @\"%UNICODE(0x2663)%\", @\"♥\", @\"%UNICODE(0x2666)%\"",
                "DIM.ERH", 704,
            ),
            SystemVariableBootstrap.RawDeclaration(
                "#DIM CHARADATA addGemDateRecord, VARSIZE(\"PALAM\")",
                "Add_HateMarkForgiveness.ERH", 2,
            ),
            SystemVariableBootstrap.RawDeclaration(
                "#DIMS CONST TRI_PLUS = \"\" + \"[a:0]\" + \"[b:1]\"",
                "Main.ERH", 7,
            ),
            SystemVariableBootstrap.RawDeclaration("#DIM CONST TRI_SHIFT = (1<<7)-1", "Main.ERH", 8),
        )
        val result = SystemVariableBootstrap.install(store, decls, cfg)
        assertEquals(0, result.failures.size, "注入后 5 条应全部注册：${result.failures.map { it.message }}")
        assertEquals(5, result.declared)
        // LIST_COLOR_DEFAULT 是 2 元素数组 CONST → 不进标量常量表；TRI_SHIFT 是标量 → 进表。
        assertEquals(127L, result.constants["TRI_SHIFT"])
        assertTrue(store.userVar("CARD_SUIT") != null)
        assertTrue(store.userVar("addGemDateRecord") != null)
    }

    @Test
    fun `install 未注入时保持现状失败口径`() {
        val store = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))
        val decls = listOf(
            SystemVariableBootstrap.RawDeclaration(
                "#DIM CONST LIST_COLOR_DEFAULT = 0x44000000 + COLOR_FROMNAME(\"dimgray\") , 0xFF000000",
                "TEMP_CHARA_LIST_BY_DT.ERH", 24,
            ),
            SystemVariableBootstrap.RawDeclaration(
                "#DIMS CONST TRI_PLUS = \"\" + \"[a:0]\" + \"[b:1]\"", "Main.ERH", 7,
            ),
        )
        val plain = DimParseConfig(systemSaveInBinary = true)
        val result = SystemVariableBootstrap.install(store, decls, plain)
        // 字符串 `+` 无条件生效（Folder 面）；COLOR_FROMNAME 未注入仍失败。
        assertEquals(1, result.declared)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures[0].message.contains("函数调用"))
        assertTrue(store.userVar("LIST_COLOR_DEFAULT") == null)
        assertTrue(store.userVar("TRI_PLUS") != null)
    }
}
