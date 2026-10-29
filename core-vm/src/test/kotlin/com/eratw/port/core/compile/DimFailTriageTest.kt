package com.eratw.port.core.compile

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.vars.DimFunctionFolders
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import org.junit.jupiter.api.Assumptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * bootSmoke「#DIM 装置预解析失败」12 条三态分诊（M4-L `l-dimtriage`，起点 = main@8fd3fb3）。
 *
 * 三态判据（CONTRACT-M4-L §2.8）：①装置局限 = 原版能装载、移植侧装置解不了但运行期注册成功；
 * ②真缺口 = 运行期注册也失败、变量真的少；③官方也失败 = 原版同样不注册（ADR-005 不修）。
 *
 * **分诊结论：12 条全部为②真缺口**（0 条装置局限、0 条官方也失败）。boot-report
 * 里「这里的 #DIM 失败只影响静态统计、不是真实缺口」这句对 12 条全部证伪：
 * - 全局 5 条：`SystemVariableBootstrap.install` 运行期注册同样失败
 *   （boot-report 运行期备注「用户变量注册：全局 7196/5」与装置失败清单一一对应）；
 * - 私有 7 条：注册循环只消费解析成功的 `userVarDecls`（GameBootSmoke.kt:242-249），
 *   生产侧 `collectPrivateDims`（tools/erbcc CorpusCompile.kt:494-509）用的是同一个
 *   `DimParser` 且「失败的那条不进侧车」⇒ 两条路径下这 7 条变量都不在册。
 *
 * 原版为什么能注册（每条语义证据，行号已复核）：
 * - 初值/尺寸段折叠总口径：`UserDefinedVariableData.cs:325`（尺寸段
 *   `ReduceIntegerTerm(wc, TermEndWith.Comma_Assignment).Restructure(null)` 必须
 *   `SingleLongTerm`）、`:375 + :405`（初值段 `ReduceArguments` 逐项
 *   `Restructure(GlobalStatic.EMediator)`）、`:406-408`（非 `SingleTerm` 才报
 *   InitialValueOnlyConst）、`:428-431`（行尾多余 → WrongFormat）、`:54-264`（修饰符关键字跳过循环）。
 * - `GlobalStatic.EMediator` 在 ErhLoader 构造之前就已装配（Process.cs:304-305，
 *   ConstantData :295、VEvaluator :298-299、IdentifierDictionary :301 同批），
 *   所以「装载期 exm 不可用」不成立。
 * - 装载期可折叠函数方法（`FunctionMethodTerm.cs:35-56`：`CanRestructure` 且实参全
 *   `SingleTerm` 即折成 `GetValue(exm)`；`FunctionMethod.cs:170/:172` 两标志、`:356` UniqueRestructure 钩子）：
 *   VARSIZE `FunctionMethodCreator.cs:3437-3438`（GetIntValue :3441-3456 只走
 *   IdentifierDictionary，不碰运行态）、COLOR_FROMNAME :3814、POWER :4169、
 *   GETNUM :4810-4811（GetIntValue :4814-4827 走 `exm.VEvaluator.Constant.TryKeywordToInteger`）、
 *   STRLENSU :5281、UNICODE :5697、GETLINESTR :5900（GetStrValue :5902-5908 走 `exm.Console.getStBar`）。
 * - 字符串 `+`：PlusStrStr `OperatorMethodManager.cs:28-33`（CanRestructure=true，
 *   GetStrValue :41-44 直接拼接；注册 :712-717 区段内）；
 *   位移 `<<`：LeftShiftIntInt `OperatorMethodManager.cs:523-527`（注册 :741），
 *   `>>`：RightShiftIntInt :509-513（注册 :740）。
 * - CONST 标识符本身可折叠：`UserDefinedVariableToken.cs:35`（`CanRestructure = isConst`）。
 * - `@"%UNICODE(...)%"` 子式折叠：`StrFormTerm.cs:27-29` → `StrForm.cs:364-376`
 *   （子式折成 `SingleTerm` 后并入字面段）。
 * - 原版私有 `#DIM` 在装载期就走同一个 `UserDefinedVariableData.Create(isPrivate=true)`
 *   （`LogicalLineParser.cs:279-291`），且 ERH 侧失败也只是 Warn level 2
 *   （`ErhLoader.cs:315-331`，不动点重试 :249-341）——但本 12 条在原版全部折叠成功、
 *   全部注册，无一落入「官方也失败」。
 *
 * 12 条三态表（装置原文 = 直接调用 main 编好的 `RealCorpusCompile.compile` 复跑所得，
 * 读数 全局 7196/5、私有 19748/7、总数 12 与 main 的 core-vm/build/boot-report.md 完全一致；
 * 语料物理行号为实测行号，装置行号是逻辑行末行口径，略有出入）：
 *
 * | # | 出处（语料物理行） | 原文（截断处用 … ） | 三态 | 原版折叠依据 |
 * |---|---|---|---|---|
 * | G1 | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH:24 | `#DIM CONST LIST_COLOR_DEFAULT = 0x44000000 + COLOR_FROMNAME("dimgray") , 0xFF000000` | 真缺口 | FunctionMethodCreator.cs:3814 + OperatorMethodManager.cs:721 + LexicalAnalyzer.cs:36-45 |
 * | G2 | DIM.ERH:704-708 | `#DIMS CONST CARD_SUIT, 5 = "？", @"%UNICODE(0x2660)%", …` | 真缺口 | FunctionMethodCreator.cs:5697 + StrForm.cs:364-376 |
 * | G3 | fromEN/Addition/Add_HateMarkForgiveness.ERH:2 | `#DIM CHARADATA addGemDateRecord, VARSIZE("PALAM")` | 真缺口 | FunctionMethodCreator.cs:3437-3441 |
 * | G4 | ステータス表示関連/CN_GRAPHIC/Main.ERH:7-10 | `#DIMS CONST CN_COLORMATRIX_DIC = "" + "[青:0]…" + …` | 真缺口 | OperatorMethodManager.cs:28-33 |
 * | G5 | ステータス表示関連/CN_GRAPHIC/Main.ERH:27-301 | `#DIMS CONST CN_DICTIONARY_RESOURCES = "" + "[前髪_お嬢様:…]…` | 真缺口 | 同 G4 |
 * | P1 | CHARALIST_DT/TEMP_CHARA_LIST_BY_DT_FUNC.ERB:14 | `#DIM CONST C_G_END = POWER(2 , 32) - 1` | 真缺口 | FunctionMethodCreator.cs:4169 |
 * | P2 | 同文件:2027-2034（`{` 续行块） | `#DIM CONST C_REF_TALENT = GETNUM(TALENT, "人类") , …` | 真缺口 | FunctionMethodCreator.cs:4810-4827 + Process.cs:304-305 |
 * | P3 | 同文件:2063-2064 | `#DIM CONST C_REF_TALENT = GETNUM(TALENT, "追加種族") ,` | 真缺口 | 同 P2 |
 * | P4 | fromEN/QOL/QoL_ShowRelation.ERB:3 | `#DIM SORT_VALUE,  VARSIZE("RELATION")` | 真缺口 | FunctionMethodCreator.cs:3437-3441 |
 * | P5 | 同文件:4 | `#DIM CHARA_TABLE, VARSIZE("RELATION")` | 真缺口 | 同 P4 |
 * | P6 | カラム機能/カラム機能(div).ERB:21 | `#DIM DYNAMIC 行文字数 = STRLENSU(GETLINESTR("─"))` | 真缺口 | FunctionMethodCreator.cs:5281 + :5900 + Process.cs:304-305 |
 * | P7 | 口上・メッセージ関連/個人口上/017 Alice [アリス]/アリス/追加ファイル/M_KOJO_K17_喘ぎ.ERB:978 | `#DIM CONST 既读面罩 = (1<<K17C_受向妄想数)-1,(1<<K17C_攻向妄想数)-1` | 真缺口 | OperatorMethodManager.cs:523-527/:741 + M_KOJO_K17_追加ライブラリ.ERH:185-186（CONST 折叠 UserDefinedVariableToken.cs:35） |
 *
 * 本路零产品改动；修复所需的装载期折叠面写进交付「需组长接线」。
 *
 * ## 【M4-M `m-dimfold` 改钉记录】（起点 = main@a67c3d5）
 *
 * 12 条里 **8 条已修**（G1/G2/G3/G4/G5/P1/P4/P5/P7 中除 G5 内联外的全部；G5 由语料锚点用例覆盖）：
 * - 无条件生效（Folder 折叠面，无需注入）：G4/G5 字符串 `+`
 *   （spec: OperatorMethodManager.cs:28-33/:41-44）、P7 位移 `<<`/`>>`
 *   （:523-534 注册 :741 / :509-520 注册 :740；CONST 标识符折叠
 *   spec: UserDefinedVariableToken.cs:35，K17C_受向妄想数 = K17C_攻向妄想数 = 7，
 *   语料 M_KOJO_K17_追加ライブラリ.ERH:185-186 实测）；
 * - 经 `DimParseConfig.functionFolder` 注入生效（spec: FunctionMethodTerm.cs:35-56 +
 *   GlobalStatic.EMediator 前置装配 Process.cs:304-305）：G1 COLOR_FROMNAME
 *   （FunctionMethodCreator.cs:3814/:3817-3835）、G2 UNICODE 子式（:5697/:5700-5720 +
 *   StrFormTerm.cs:27-29 → StrForm.cs:364-376）、G3/P4/P5 VARSIZE
 *   （:3437-3454/:3456-3473，尺寸表注入 = 装载时点 IdentifierDictionary 快照）、
 *   P1 POWER（:4169/:4172-4190）。
 * ## 【M4-N 改钉记录】（2026-10-23，符号链接审计清零）
 *
 * 余下 4 条（P2/P3/P6 + G5 的语料锚点）全部收口，**12 条全部为「能折叠且折对」**：
 * - P6 STRLENSU+GETLINESTR：`FunctionMethodCreator.cs:5281` + `:5900-5908`，
 *   宽度取 PC 基准可绘制宽 199 格（`erbcc CorpusCompile.FOLD_CONSOLE_WIDTH_CELLS`，
 *   运行期同尺），`─`（2 格/字）→ 99 字；语料侧车已烤入 `行文字数 = 99`；
 * - P2/P3 GETNUM：`:4814-4832`，关键字表 = 29 槽 CSV 名字表
 *   （`VariableRegistry.systemKeywordTables()`；TALENT 人类..人形 = 190..196）。
 *
 * 「表缺席仍保持失败方向」的红线继续由 `foldedCfg`（无关键字表）钉住：
 * 生产折叠面另用 `productionCfg` 表达。具体值的独立用例见 `core/vars/DimFoldTest.kt`
 * （com.eratw.port.core.vars 包）、ERD 表装载见 `data/csv/UserKeywordTablesTest.kt`。
 */
class DimFailTriageTest {

    // ------------------------------------------------------------------
    // 装置原文清单（与 RealCorpusCompile.dimParseFailures 逐字对应；
    // G5 与语料锚点用例同源，其余 11 条原文足够短，内联保证不依赖环境变量也能跑）
    // ------------------------------------------------------------------

    private class Triage(
        val tag: String,
        val rel: String,
        val declPrefix: String,
        val raw: String?,
        val isPrivate: Boolean,
        val varName: String,
        val msgFragment: String,
        /** 同文件同前缀的第几条（0 基）：P2/P3 是不同函数里的同名声明。 */
        val nth: Int = 0,
    )

    private val cfg = DimParseConfig(systemSaveInBinary = true)

    /**
     * M4-M 注入面：纯静态函数 + VARSIZE 尺寸表。本文件只用**合成表**（500/20，刻意不等于
     * 语料 `VariableSize.csv:99 PALAM,200` / `:104 RELATION,200`，折错即红）钉「能折叠且折对」。
     * 真实快照由 `DimVarSizes.systemSnapshot` 提供，生产接入点 = `core/proc/GameSession.kt`
     * 的全局 `#DIM` 装载 + 装置 `boot/RealCorpusCompile.kt`，语料级读数见 bootSmoke 报告
     * （M4-N 收口后：全局 7201/0、私有 19756/0）。
     * P7 需要 ERH 前置 CONST（语料 M_KOJO_K17_追加ライブラリ.ERH:185-186 实测均 = 7）。
     */
    private val varSizes = mapOf("PALAM" to longArrayOf(500), "RELATION" to longArrayOf(20))
    /** 无关键字表/默认宽度：钉「数据源缺席时回调返回 null」的失败方向。 */
    private val foldedCfg = DimParseConfig(
        systemSaveInBinary = true,
        intConstants = mapOf("K17C_受向妄想数" to 7L, "K17C_攻向妄想数" to 7L),
        functionFolder = DimFunctionFolders.pureStatic(varSizes),
    )

    /**
     * 生产折叠面（`CorpusCompile.compile` 的同形配置）：TALENT 关键字表来自
     * `CSV/TALENT.csv`（人类..追加種族 = 190..197），GETLINESTR 宽度 = PC 基准 199 格。
     */
    private val productionCfg = DimParseConfig(
        systemSaveInBinary = true,
        intConstants = foldedCfg.intConstants,
        functionFolder = DimFunctionFolders.pureStatic(
            varSizes,
            keywordTables = mapOf(
                "TALENT" to mapOf(
                    "人类" to 190, "妖怪" to 191, "妖精" to 192, "神霊" to 193,
                    "幽霊" to 194, "付喪神" to 195, "人形" to 196, "追加種族" to 197,
                ),
            ),
            consoleWidth = 199,
        ),
    )

    /** M4-M 已修条目 + M4-N 收口的 P2/P3/P6；本文件没有仍失败的条目。 */
    private val fixedTags = setOf("G1", "G2", "G3", "G4", "G5", "P1", "P2", "P3", "P4", "P5", "P6", "P7")
    private val stillFailingTags = emptySet<String>()

    private val globalEntries = listOf(
        Triage(
            "G1", "CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERH", "#DIM CONST LIST_COLOR_DEFAULT",
            "#DIM CONST LIST_COLOR_DEFAULT = 0x44000000 + COLOR_FROMNAME(\"dimgray\") , 0xFF000000",
            false, "LIST_COLOR_DEFAULT", "函数调用 COLOR_FROMNAME",
        ),
        Triage(
            "G2", "DIM.ERH", "#DIMS CONST CARD_SUIT",
            "#DIMS CONST CARD_SUIT, 5  = \"？\",  @\"%UNICODE(0x2660)%\",  @\"%UNICODE(0x2663)%\",  @\"♥\",  @\"%UNICODE(0x2666)%\"",
            false, "CARD_SUIT", "初值含函数调用",
        ),
        Triage(
            "G3", "fromEN/Addition/Add_HateMarkForgiveness.ERH", "#DIM CHARADATA addGemDateRecord",
            "#DIM CHARADATA addGemDateRecord, VARSIZE(\"PALAM\")",
            false, "addGemDateRecord", "函数调用 VARSIZE",
        ),
        Triage(
            "G4", "ステータス表示関連/CN_GRAPHIC/Main.ERH", "#DIMS CONST CN_COLORMATRIX_DIC",
            "#DIMS CONST CN_COLORMATRIX_DIC = \"\" + " +
                "\"[青:0][紫:1][赤:2][黄:3][緑:4][水:5][白:6][灰:7][黒:8]\" + " +
                "\"[栗:11][桃:12][橙:13]\" + " +
                "\"[普通:31][褐色:32][黄色:33][日焼:34][不健康:35][悪魔:36]\"",
            false, "CN_COLORMATRIX_DIC", "声明末尾有多余内容",
        ),
        // G5 原文 = 274 个字符串段的 + 链，物理上不内联；由语料锚点用例覆盖（无 ERATW_CORPUS 时跳过）。
        Triage(
            "G5", "ステータス表示関連/CN_GRAPHIC/Main.ERH", "#DIMS CONST CN_DICTIONARY_RESOURCES",
            null, false, "CN_DICTIONARY_RESOURCES", "声明末尾有多余内容",
        ),
    )

    private val privateEntries = listOf(
        Triage(
            "P1", "CHARALIST_DT/TEMP_CHARA_LIST_BY_DT_FUNC.ERB", "#DIM CONST C_G_END",
            "#DIM CONST C_G_END = POWER(2 , 32) - 1",
            true, "C_G_END", "函数调用 POWER",
        ),
        Triage(
            "P2", "CHARALIST_DT/TEMP_CHARA_LIST_BY_DT_FUNC.ERB", "#DIM CONST C_REF_TALENT",
            "#DIM CONST C_REF_TALENT =  " +
                "GETNUM(TALENT, \"人类\") ,  GETNUM(TALENT, \"妖怪\") ,  GETNUM(TALENT, \"妖精\") ,  " +
                "GETNUM(TALENT, \"神霊\") ,  GETNUM(TALENT, \"幽霊\") ,  GETNUM(TALENT, \"付喪神\") ,  " +
                "GETNUM(TALENT, \"人形\")",
            true, "C_REF_TALENT", "函数调用 GETNUM",
        ),
        Triage(
            "P3", "CHARALIST_DT/TEMP_CHARA_LIST_BY_DT_FUNC.ERB", "#DIM CONST C_REF_TALENT",
            "#DIM CONST C_REF_TALENT =  GETNUM(TALENT, \"追加種族\") ,",
            true, "C_REF_TALENT", "函数调用 GETNUM", nth = 1,
        ),
        Triage(
            "P4", "fromEN/QOL/QoL_ShowRelation.ERB", "#DIM SORT_VALUE",
            "#DIM SORT_VALUE,  VARSIZE(\"RELATION\")",
            true, "SORT_VALUE", "函数调用 VARSIZE",
        ),
        Triage(
            "P5", "fromEN/QOL/QoL_ShowRelation.ERB", "#DIM CHARA_TABLE",
            "#DIM CHARA_TABLE, VARSIZE(\"RELATION\")",
            true, "CHARA_TABLE", "函数调用 VARSIZE",
        ),
        Triage(
            "P6", "カラム機能/カラム機能(div).ERB", "#DIM DYNAMIC 行文字数",
            "#DIM DYNAMIC 行文字数 = STRLENSU(GETLINESTR(\"─\"))",
            true, "行文字数", "函数调用 STRLENSU",
        ),
        Triage(
            "P7",
            "口上・メッセージ関連/個人口上/017 Alice [アリス]/アリス/追加ファイル/M_KOJO_K17_喘ぎ.ERB",
            "#DIM CONST 既读面罩",
            "#DIM CONST 既读面罩 = (1<<K17C_受向妄想数)-1,(1<<K17C_攻向妄想数)-1",
            true, "既读面罩", "缺少右括号",
        ),
    )

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 装置同款入口：剥行内注释 → 切关键字 → DimParser（RealCorpusCompile.parseDim 同构）。 */
    private fun parseDim(raw: String, isPrivate: Boolean, c: DimParseConfig = cfg): DimSpec {
        val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw))
            ?: throw AssertionError("不是 #DIM/#DIMS 声明: $raw")
        return DimParser.parse(split.first, split.second, isPrivate, c)
    }

    /** 真缺口复现口径：解析失败返回 null（装置、运行期注册、erbcc 侧车三处同一条判据）。 */
    private fun parseDimOrNull(raw: String, isPrivate: Boolean, c: DimParseConfig = cfg): DimSpec? =
        runCatching { parseDim(raw, isPrivate, c) }.getOrNull()

    private fun expectCodeEE(entry: Triage, c: DimParseConfig = cfg): CodeEE {
        val e = assertFailsWith<CodeEE> { parseDim(entry.raw!!, entry.isPrivate, c) }
        assertTrue(
            (e.message ?: "").contains(entry.msgFragment),
            "${entry.tag} 期望报错含「${entry.msgFragment}」，实际：${e.message}",
        )
        return e
    }

    private fun entry(tag: String): Triage = (globalEntries + privateEntries).first { it.tag == tag }

    private fun corpusRoot(): File {
        val corpus = System.getenv("ERATW_CORPUS")
        Assumptions.assumeTrue(
            corpus != null && File(corpus).isDirectory,
            "未设 ERATW_CORPUS：跳过语料锚点用例",
        )
        return File(corpus)
    }

    /**
     * 从语料按声明前缀取出**逻辑行**（`{` 续行块合并、`;` 注释剥离后的原文形态，
     * 与装置喂给 DimParser 的 raw 同构；空白差异不影响解析）。
     */
    private fun logicalDimLine(corpus: File, rel: String, prefix: String, nth: Int = 0): String {
        val lines = File(corpus, rel).readText().removePrefix("\uFEFF").split('\n').map { it.removeSuffix("\r") }
        val matches = lines.indices.filter { lines[it].trim().startsWith(prefix) }
        assertTrue(matches.size > nth, "语料缺少以「$prefix」开头的第 ${nth + 1} 条声明：$rel")
        val idx = matches[nth]
        var acc = lines[idx].trim()
        var j = idx + 1
        while (j < lines.size) {
            val t = lines[j].trim()
            if (t == "}") break
            if (t.isEmpty()) { j++; continue }
            val cont = acc.endsWith(",") || acc.endsWith("+") || acc.endsWith("=") || t.startsWith("+")
            if (!cont) break
            acc = "$acc $t"
            j++
        }
        return SystemVariableBootstrap.stripComment(acc)
    }

    // ------------------------------------------------------------------
    // ① M4-M 改钉：函数调用条目在「未注入」时保持分诊原形态；
    //    注入后 8 条折叠成功且值正确；3 条外部数据源条目保持失败
    // ------------------------------------------------------------------

    @Test
    fun 未注入回调时函数调用条目仍按分诊原形态失败() {
        // 红线：`DimParseConfig.functionFolder` 缺席时口径与分诊时一字不差。
        // G4（字符串 `+`）与 P7（位移）是 Folder 无条件面，已不在此列。
        listOf("G1", "G2", "G3", "P1", "P2", "P3", "P4", "P5", "P6").forEach { tag ->
            expectCodeEE(entry(tag))
        }
        // P7 未注入对照仍失败，但形态已从「缺少右括号」变为常量表未含 K17C 的
        // 「不能求值为整数常量」——位移面已接，缺的是前置 ERH CONST（foldedCfg 提供）。
        val p7 = assertFailsWith<CodeEE> { parseDim(entry("P7").raw!!, true) }
        assertTrue(
            (p7.message ?: "").contains("不能求值为整数常量"),
            "P7 未注入 K17C 常量表时应报常量缺失，实际：${p7.message}",
        )
    }

    @Test
    fun 改钉全局三条注入后解析成功且值正确() {
        val g1 = parseDim(entry("G1").raw!!, false, foldedCfg)
        // 0x44000000 + 0x696969 = 0x44696969（ColorNames dimgray；原版 FunctionMethodCreator.cs:3824）
        assertEquals(0x44696969L, g1.defaultsInt!![0])
        assertEquals(0xFF000000L, g1.defaultsInt[1])
        assertEquals(2, g1.dims[0])
        val g2 = parseDim(entry("G2").raw!!, false, foldedCfg)
        assertEquals(listOf("？", "♠", "♣", "♥", "♦"), g2.defaultsStr)
        assertEquals(5, g2.dims[0])
        val g3 = parseDim(entry("G3").raw!!, false, foldedCfg)
        assertEquals(500, g3.dims[0])
        assertTrue(g3.isCharaData)
    }

    @Test
    fun 改钉字符串加号与位移无注入即折叠() {
        // G4：OperatorMethodManager.cs:28-33/:41-44 —— 纯 Folder 面，不依赖回调。
        val g4 = parseDim(entry("G4").raw!!, false)
        assertEquals(
            listOf(
                "[青:0][紫:1][赤:2][黄:3][緑:4][水:5][白:6][灰:7][黒:8]" +
                    "[栗:11][桃:12][橙:13]" +
                    "[普通:31][褐色:32][黄色:33][日焼:34][不健康:35][悪魔:36]",
            ),
            g4.defaultsStr,
        )
        // P7：位移 :523-534/:509-520 + CONST 标识符 UserDefinedVariableToken.cs:35；
        // K17C_受向/攻向妄想数 = 7（语料 M_KOJO_K17_追加ライブラリ.ERH:185-186）。
        val p7 = parseDim(entry("P7").raw!!, true, foldedCfg)
        assertContentEquals(listOf(127L, 127L), p7.defaultsInt!!.toList())
    }

    @Test
    fun 改钉私有折叠四条注入后解析成功且值正确() {
        assertEquals(4294967295L, parseDim(entry("P1").raw!!, true, foldedCfg).defaultsInt!![0]) // POWER(2,32)-1
        assertEquals(20, parseDim(entry("P4").raw!!, true, foldedCfg).dims[0]) // VARSIZE("RELATION")
        assertEquals(20, parseDim(entry("P5").raw!!, true, foldedCfg).dims[0])
        // P6：STRLENSU(GETLINESTR("─"))，生产宽度 199 格 ⇒ 99 字。
        assertEquals(99L, parseDim(entry("P6").raw!!, true, productionCfg).defaultsInt!![0])
    }

    @Test
    fun 外部数据源两条注入前后都保持失败形态() {
        // P2/P3 GETNUM（FunctionMethodCreator.cs:4810-4827，需 TALENT 关键字→索引表）：
        // `foldedCfg` 没有表 ⇒ 回调返回 null，未注入与注入两种配置的报错文案一致
        // （红线：不许把失败变静默成功）。生产面有表时的值由
        // `语料锚点十二条改钉后逐条复核` 钉住。
        for (tag in listOf("P2", "P3")) {
            val e = entry(tag)
            expectCodeEE(e, cfg)
            expectCodeEE(e, foldedCfg)
        }
    }

    // ------------------------------------------------------------------
    // ② 运行期注册读数改钉：注入后条目真实在册；未注入维持缺口读数
    // ------------------------------------------------------------------

    @Test
    fun 全局条目注入后运行期注册全部成功() {
        val store = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))
        val decls = globalEntries.filter { it.raw != null }.map {
            SystemVariableBootstrap.RawDeclaration(it.raw!!, it.rel, 0)
        } + SystemVariableBootstrap.RawDeclaration("#DIM CONST TRIAGE_OK_CONST = 7", "TRIAGE", 0)
        val result = SystemVariableBootstrap.install(store, decls, foldedCfg)
        assertEquals(0, result.failures.size, "注入后不应有失败：${result.failures.map { it.message }}")
        assertEquals(5, result.declared)
        assertTrue(store.userVar("TRIAGE_OK_CONST") != null)
        for (e in globalEntries.filter { it.raw != null }) {
            assertNotNull(store.userVar(e.varName), "${e.tag} ${e.varName} 注入后应在册")
        }
        // rememberConstant 口径：只有标量 CONST 进常量表。LIST_COLOR_DEFAULT 是 2 元素、
        // CARD_SUIT 是 5 元素数组 ⇒ 都不进标量表（SystemVariableBootstrap.rememberConstant
        // 的 dims==1 && totalSize==1 判据，不是失踪）。
        assertTrue(result.constants.keys.none { it == "LIST_COLOR_DEFAULT" || it == "CARD_SUIT" })
    }

    @Test
    fun 全局条目未注入时仍只注册字符串加号一条() {
        val store = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))
        val decls = globalEntries.filter { it.raw != null }.map {
            SystemVariableBootstrap.RawDeclaration(it.raw!!, it.rel, 0)
        } + SystemVariableBootstrap.RawDeclaration("#DIM CONST TRIAGE_OK_CONST = 7", "TRIAGE", 0)
        val result = SystemVariableBootstrap.install(store, decls, cfg)
        // G4 的字符串 `+` 是 Folder 无条件面 ⇒ 注册成功；G1/G2/G3 未注入保持失败。
        assertEquals(2, result.declared)
        assertEquals(3, result.failures.size)
        assertNotNull(store.userVar("CN_COLORMATRIX_DIC"))
        for (tag in listOf("G1", "G2", "G3")) {
            assertNull(store.userVar(entry(tag).varName), "$tag 未注入应不在册")
        }
    }

    @Test
    fun 私有注册循环十二条全部在册() {
        val store = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))
        for (e in privateEntries) {
            // 与 GameBootSmoke.kt:242-249 / CorpusCompile.kt:494-509 相同口径：
            // 解析成功 → 进注册循环；失败 → 不进（M4-N 后无失败条目）。
            val spec = parseDimOrNull(e.raw!!, e.isPrivate, productionCfg)
            assertNotNull(spec, "${e.tag} 生产折叠面下应解析成功")
            // P2/P3 是不同函数里的同名私有声明：函数键必须逐条唯一，否则撞「重复声明」。
            val fk = "${e.tag}@${e.rel}".uppercase()
            store.declareUserVar(spec, fk)
            if (e.tag in fixedTags) {
                assertNotNull(
                    store.userVar(e.varName, fk),
                    "${e.tag} ${e.varName} 注入后应进注册清单",
                )
            } else {
                assertNull(
                    store.userVar(e.varName, fk),
                    "${e.tag} ${e.varName} 应保持解析失败不注册",
                )
            }
        }
        // 对照组：可解析的私有声明能注册（在册判据不是 declareUserVar 本身坏）。
        val ok = parseDimOrNull("#DIM SORT_VALUE, 10", isPrivate = true)
        require(ok != null)
        store.declareUserVar(ok, "TRIAGE_FK")
        assertTrue(store.userVar("SORT_VALUE", "TRIAGE_FK") != null)
    }

    // ------------------------------------------------------------------
    // ③ 语料锚点：12 条原文逐条复核（含 G5）——生产折叠面下全部成功
    // ------------------------------------------------------------------

    @Test
    fun 语料锚点十二条改钉后逐条复核() {
        val corpus = corpusRoot()
        (globalEntries + privateEntries).forEach { e ->
            val raw = logicalDimLine(corpus, e.rel, e.declPrefix, e.nth)
            assertTrue(raw.startsWith(e.declPrefix), "${e.tag} 逻辑行拼接异常：${raw.take(60)}")
            if (e.tag in stillFailingTags) {
                val err = assertFailsWith<CodeEE> { parseDim(raw, e.isPrivate, foldedCfg) }
                assertTrue(
                    (err.message ?: "").contains(e.msgFragment),
                    "${e.tag} 语料原文报错形态漂移，期望含「${e.msgFragment}」，实际：${err.message}",
                )
            } else {
                val spec = parseDim(raw, e.isPrivate, productionCfg)
                assertNotNull(spec, "${e.tag} 语料原文注入后应折叠成功")
                if (e.tag == "G5") {
                    // G5 = 274 段的 + 链，值不逐字内联；锚定「折成 1 项且含首段前缀」。
                    assertEquals(1, spec.defaultsStr!!.size)
                    assertTrue(
                        spec.defaultsStr[0].contains("[前髪_お嬢様"),
                        "G5 拼接结果异常：${spec.defaultsStr[0].take(60)}",
                    )
                }
                if (e.tag == "P7") {
                    assertContentEquals(listOf(127L, 127L), spec.defaultsInt!!.toList())
                }
                if (e.tag == "P2") {
                    // TALENT.csv：人类..人形 = 190..196（顺序即源文顺序）。
                    assertContentEquals(
                        (190L..196L).toList(),
                        spec.defaultsInt!!.toList(),
                        "P2 的 GETNUM 下标必须与 TALENT.csv 一致",
                    )
                }
                if (e.tag == "P3") {
                    assertEquals(listOf(197L), spec.defaultsInt!!.toList(), "追加種族 = 197")
                }
                if (e.tag == "P6") {
                    // `─` 2 格/字，199 格 → 99 字；语料侧车 `行文字数 = 99`。
                    assertEquals(listOf(99L), spec.defaultsInt!!.toList())
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // ④ 缺口形态定位（M4-M 后）：未注入对照仍全部成立（红线读数）；
    //    已修面与仍缺面的分界见 DimFoldTest 与上面 ①②
    // ------------------------------------------------------------------

    @Test
    fun 已落地形态对照组全部解析成功() {
        // 十六进制面值（LexicalAnalyzer.cs:36-45 口径）
        parseDim("#DIM CONST TRI_H = 0x44000000 , 0xFF000000", false)
        // 整型 + 折叠（PlusIntInt，OperatorMethodManager.cs:18-26 口径）
        parseDim("#DIM CONST TRI_I = 0x10 + 2", false)
        // 纯字符串初值与 @"..." 全字面量（G2 只差 UNICODE 折叠）
        parseDim("#DIMS CONST TRI_S = \"？\", \"♥\"", false)
        parseDim("#DIMS CONST TRI_F = @\"♪\"", false)
        // 十进制尺寸 + 行尾注释（G3 只差 VARSIZE 折叠）
        parseDim("#DIM TRI_C, 10 ;注释尾巴", false)
    }

    @Test
    fun 缺口形态最小复现逐点钉住() {
        // 装载期可折叠函数调用：未注入回调时仍全部失败（原版 CanRestructure，
        // FunctionMethodTerm.cs:35-56；红线「未注入时必须保持现状失败口径」的钉桩）。
        for (f in listOf("VARSIZE", "COLOR_FROMNAME", "UNICODE", "POWER", "GETNUM", "STRLENSU", "GETLINESTR")) {
            val form = when (f) {
                "VARSIZE" -> "#DIM TRI_X, VARSIZE(\"PALAM\")"
                "UNICODE" -> "#DIMS CONST TRI_X = @\"%UNICODE(0x2660)%\""
                "COLOR_FROMNAME" -> "#DIM CONST TRI_X = 0x44000000 + COLOR_FROMNAME(\"dimgray\")"
                else -> "#DIM CONST TRI_X = $f(1 , 2)"
            }
            val e = assertFailsWith<CodeEE> { parseDim(form, false) }
            assertTrue(
                (e.message ?: "").contains("函数调用"),
                "$f 应报「含函数调用，不是编译期常量」，实际：${e.message}",
            )
        }
        // 注入后：字符串 + / 位移折叠成具体值（M4-M 改钉；旧形态在此翻面）。
        assertEquals(listOf("ab"), parseDim("#DIMS CONST TRI_Y = \"a\" + \"b\"", false).defaultsStr)
        assertEquals(7L, parseDim("#DIM CONST TRI_Z = (1<<3)-1", false).defaultsInt!![0])
        // 注入纯静态回调后外部数据源三条仍报「函数调用」（GETNUM/STRLENSU/GETLINESTR）。
        for (f in listOf("GETNUM", "STRLENSU", "GETLINESTR")) {
            val e = assertFailsWith<CodeEE> {
                parseDim("#DIM CONST TRI_X = $f(1 , 2)", false, foldedCfg)
            }
            assertTrue((e.message ?: "").contains("函数调用"), "$f 注入后应仍失败，实际：${e.message}")
        }
    }
}
