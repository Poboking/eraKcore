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
 *   原版 `&`/`|` 是逻辑与或（`:375-410`），不是按位——本包未接、也不按位复刻。
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
        assertTrue((tr.message ?: "").contains("transparent"))
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
