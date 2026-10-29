package com.eratw.port.erbcc

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 符号表回归测试。
 *
 * 1. [goldenFixture] —— 把 `fixtures/golden/GOLDEN_12_SYMBOLS.ERB` 的编译期断言固化（规约见 specs/loader.md 附录 D），
 *    覆盖：同文件重复定义的 last-wins、事件四桶执行序、与系统语句名无冲突、`#LOCALSIZE`/形参个数。
 * 2. [fullCorpusSmoke] —— 真实语料的 6 组重名统计（语料不在仓库内，路径由环境变量
 *    `ERATW_CORPUS` 或系统属性 `-Deratw.corpus` 提供，缺失则 skip）。
 *
 * 运行：`gradle :tools:erbcc:test`
 */
class SymbolsTest {

    private fun repoRoot(): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (dir != null) {
            if (dir.resolve("fixtures/golden/GOLDEN_12_SYMBOLS.ERB").let { Files.exists(it) }) return dir
            dir = dir.parent
        }
        error("找不到仓库根（从 ${System.getProperty("user.dir")} 向上未发现 fixtures/golden/GOLDEN_12_SYMBOLS.ERB）")
    }

    @Test
    fun goldenFixture() {
        val corpus = repoRoot().resolve("fixtures/golden")
        val table = SymbolTable.build(corpus)
        val st = table.stats()

        // 4 组重名：G12_FUNC(2) / G12_PLAIN(3) 为同文件非事件重复；EVENTTURNEND(3) 为事件；
        // EVENTFIRST(2) 来自既有 GOLDEN_03_FLOW.ERB。
        assertEquals(4, st.duplicateNames, "重名组总数")
        assertEquals(2, st.nonEventDuplicates, "非事件重名组")
        assertEquals(2, st.eventDuplicates, "事件重名组")

        // spec: LabelDictionary.cs:60-68 —— 同文件内后定义者胜
        val fn = assertNotNull(table.resolve("G12_FUNC"), "@G12_FUNC 应存在")
        assertEquals(43, fn.lineNo, "@G12_FUNC 生效行（第二个定义）")
        assertTrue(fn.isMethod, "@G12_FUNC 应为 #FUNCTION")

        val plain = assertNotNull(table.resolve("G12_PLAIN"), "@G12_PLAIN 应存在")
        assertEquals(56, plain.lineNo, "@G12_PLAIN 生效行（第三个定义）")
        assertTrue(!plain.isMethod, "@G12_PLAIN 是普通标签，不应是方法")

        // spec: CalledFunction.cs:228-243 —— 桶序 PRI(1) → 普通(2) → LATER(3)，与定义序不同
        val eventOrder = table.eventCallOrder("EVENTTURNEND").map { it.lineNo }
        assertEquals(listOf(65, 61, 70), eventOrder, "@EVENTTURNEND 执行序应为 PRI→NORMAL→LATER")

        // 标签名不得与系统语句名冲突（`BAR` 之类的名字会让原版多打一条装载告警）
        val conflicts = table.nameKeys.filter { it in ERB_INSTRUCTIONS }
        assertEquals(emptyList(), conflicts, "golden 语料内不应有与系统语句同名的标签")

        // 形参个数（ErbLoader.cs:753-754）：GOLDEN_SYMBOLS 无括号 → 0
        assertEquals(0, assertNotNull(table.resolve("GOLDEN_SYMBOLS")).argCount)
        assertEquals(0, fn.argCount)

        // 该 fixture 未声明 #LOCALSIZE
        assertEquals(0, plain.localSize)
        assertEquals(0, plain.localsSize)
    }

    @Test
    fun scanLabelsParsesArgsAndLocalSize() {
        // 直接喂文本，避免依赖语料：形参含默认值/嵌套括号/字符串内逗号
        val text = """
            @FUNC_A(A, B, C = 3)
            #LOCALSIZE 7
            #LOCALSSIZE 9
            	RETURN
            @FUNC_B(REF X, ARGS = "a,b")
            #LOCALSIZE 0
            	RETURN
            @FUNC_C()
            	RETURN
            @FUNC_D
            	RETURN
        """.trimIndent()
        val labels = SymbolTable.scanLabels(text)
        assertEquals(listOf("FUNC_A", "FUNC_B", "FUNC_C", "FUNC_D"), labels.map { it.name })
        assertEquals(3, labels[0].argCount)
        assertEquals(3, labels[0].argIntCount, "FUNC_A 全为无默认值整型形参")
        assertEquals(0, labels[0].argStrCount)
        assertEquals(7, labels[0].localSize)
        assertEquals(9, labels[0].localsSize)
        assertEquals(2, labels[1].argCount)          // 字符串里的逗号不算分隔符
        assertEquals(1, labels[1].argIntCount, "REF X 无 $ 且无默认值 → 整型")
        assertEquals(1, labels[1].argStrCount, "ARGS 形参 → 字符串桶（ErbLoader.cs:710-714）")
        assertEquals(0, labels[1].localSize)         // 非法值（<=0）按未声明处理
        assertEquals(0, labels[2].argCount)          // 空括号
        assertEquals(0, labels[3].argCount)          // 无括号
    }

    @Test
    fun scanLabelsClassifiesArgTypesByDefaultValue() {
        // 本语料事实规则（见 countTypedArgs KDoc）：类型由默认值决定，$ 后缀风格 0 处使用
        val text = """
            @F_MIX(A, B${'$'} = "x", C = "s", D = -1)
            	RETURN
            @F_STRONLY(NAME = "")
            	RETURN
        """.trimIndent()
        val labels = SymbolTable.scanLabels(text)
        assertEquals(4, labels[0].argCount)
        assertEquals(2, labels[0].argIntCount, "A（无默认）与 D（整数默认）→ 整型")
        assertEquals(2, labels[0].argStrCount, "B\$（$ 后缀）与 C（字符串默认）→ 字符串")
        assertEquals(1, labels[1].argStrCount, "NAME = \"\" → 字符串（语料 176 处同形态）")
        assertEquals(0, labels[1].argIntCount)
    }

    @Test
    fun scanLabelsCollectsDimSpecs() {
        // 任务：erbcc 收集每函数 #DIM 声明（未求值）。三条主路径：
        // 1) 尺寸为标识符/表达式（不求值，保留原文）；2) 关键字 + 初值原文；3) TAB 分隔 / REF 空段 / 重名胜者
        val text = """
            @SYUGO
            #DIM 集合, 人物数量上限, 3 ;集合:X:Y
            	RETURN
            @UPDATE_STATE(C_ID, OP)
            #LOCALSIZE 1
            #DIMS CONST DISP_NAME = "主页", "个别UPDATE"
            #DIM DYNAMIC	OPERATE_MODE
            #DIM REF 集合, ,
            #DIMS	REF		COLOR_STR
            	RETURN
            @DUP
            #DIM X, 10
            #DIM X, 20
            #DIMS X
            	RETURN
        """.trimIndent()
        val labels = SymbolTable.scanLabels(text)
        assertEquals(listOf("SYUGO", "UPDATE_STATE", "DUP"), labels.map { it.name })

        // 1) `#DIM 集合, 人物数量上限, 3`：尺寸「人物数量上限」是 ERH #DIM CONST 标识符
        //    （DIM.ERH:209）——erbcc 不求值，原版求值时序见 UserDefinedVariableData.cs:325-341
        val dims1 = labels[0].dimSpecs.single()
        assertEquals("集合", dims1.name)
        assertEquals(false, dims1.isStr)
        assertEquals(listOf("人物数量上限", "3"), dims1.dims)
        assertEquals(emptySet(), dims1.keywords)
        assertEquals(null, dims1.initializer)

        // 2) 关键字 + 初值原文（#DIMS CONST；TAB 分隔的 DYNAMIC；REF 空段）
        val specs = labels[1].dimSpecs
        assertEquals(4, specs.size)
        assertEquals("DISP_NAME", specs[0].name)
        assertEquals(true, specs[0].isStr)
        assertEquals(setOf(DimKeyword.CONST), specs[0].keywords)
        assertEquals(emptyList(), specs[0].dims)
        assertEquals("\"主页\", \"个别UPDATE\"", specs[0].initializer)
        assertEquals("OPERATE_MODE", specs[1].name)
        assertEquals(setOf(DimKeyword.DYNAMIC), specs[1].keywords)
        // REF 空段 = 每个 ',' 贡献一个 0 尺寸（UserDefinedVariableData.cs:309-319）→ 2 维
        assertEquals("集合", specs[2].name)
        assertEquals(setOf(DimKeyword.REF), specs[2].keywords)
        assertEquals(listOf("", ""), specs[2].dims)
        // TAB 分隔的 `#DIMS\tREF\t\tCOLOR_STR`
        assertEquals("COLOR_STR", specs[3].name)
        assertEquals(true, specs[3].isStr)
        assertEquals(setOf(DimKeyword.REF), specs[3].keywords)

        // 3) 同函数重名：先到者胜（spec: FunctionLabelLine.cs:98-108 AddPrivateVariable
        //    ContainsKey → false；键为 StrComper=OrdinalIgnoreCase，FunctionLabelLine.cs:17）
        val dup = labels[2].dimSpecs
        assertEquals(1, dup.size)
        assertEquals("X", dup[0].name)
        assertEquals(listOf("10"), dup[0].dims, "重名只收先到者")
        assertEquals(false, dup[0].isStr)
    }

    @Test
    fun scanLabelsRejectsMethodDeclOnEventSystemAndDigitLabels() {
        // spec: LogicalLineParser.cs:151-213 —— #FUNCTION/#FUNCTIONS 三类声明无效：
        //   ① 事件/系统标签（label.Depth==0，ParseLabelLine.cs:352-362 对事件/系统标签置位）
        //      → UseSharpInSystemFunc 告警 level 2，IsMethod 不置位（:178-182）；
        //   ② 函数名数字开头 → CanNotDeclaredBeginNumberFunction + IsError（:153-158）；
        //   ③ 接受声明时（:183-192）作废先行的 #PRI/#LATER/#SINGLE/#ONLY（:193-212）。
        val labels = SymbolTable.scanLabels(
            """
            @EVENTFIRST
            #FUNCTION
            	RETURN
            @COM0
            #FUNCTIONS
            	RETURN
            @COM_ABLE3
            #FUNCTION
            	RETURN
            @1BAD
            #FUNCTION
            	RETURN
            @BADPRI
            #PRI
            #FUNCTION
            	RETURN
            @ONLYFN
            #ONLY
            #FUNCTIONS
            	RETURN
            @DUPFN
            #FUNCTIONS
            #FUNCTION
            	RETURN
            """.trimIndent(),
        )
        assertFalse(labels[0].isMethod, "事件标签（EVENTFIRST）不得声明 #FUNCTION（UseSharpInSystemFunc）")
        assertFalse(labels[1].isMethod, "系统标签（COM0，^COM[0-9]+$）不得声明 #FUNCTIONS")
        assertFalse(labels[2].isMethod, "系统标签（COM_ABLE3，^COM_ABLE[0-9]+$）不得声明 #FUNCTION")
        assertFalse(labels[3].isMethod, "数字开头的函数名声明无效（CanNotDeclaredBeginNumberFunction + IsError）")
        assertTrue(labels[4].isMethod, "普通标签接受 #FUNCTION")
        assertFalse(labels[4].isPri, "接受 #FUNCTION 时作废先行的 #PRI（UsePriUserFunc，cs:193-197）")
        assertTrue(labels[5].isMethod, "普通标签接受 #FUNCTIONS")
        assertFalse(labels[5].isOnly, "接受 #FUNCTIONS 时作废先行的 #ONLY（UseOnlyUserFunc，cs:208-212）")
        assertTrue(labels[6].isMethod, "同型重复声明幂等（RawLabel 无 MethodType，返回类型表在 CorpusScan.funcTypes）")
    }

    @Test
    fun collectErhMacrosKeepsBodiesFirstWinsAndRejectsFuncMacros() {
        // 宏名 → 展开体原文（erbcc 不求值）。规则证据：
        //   体原文保留：ErhLoader.cs:213-218（剩余 token 原样入 DefineMacro.Statement）
        //   重名先到者胜：ErhLoader.cs:155-164 + IdentifierDictionary.cs:324-327（第二次不到 AddMacro）
        //   参数化宏不收集：ErhLoader.cs:241-244（CanNotDeclaredFuncMacro，本版本禁声明）
        //   注释剥离：LexicalAnalyzer.cs:662-676（词法 ';' → EOS）
        //   续行块合并：EraStreamReader.cs:136-184 + emuera.config:72（连接串 = " "）
        val text = """
            #DEFINE MAXSIZE 300
            #DEFINE MAXSIZE 999
            #DEFINE FLAG_ONLY
            #DEFINE foo 7
            #DEFINE LABEL "文字列" ;行末コメント
            #DEFINE CHAIN MAXSIZE
            #DEFINE MYF(X) X+1
            {
            #DEFINE MULTI 1 +
            2
            }
            	RETURN
        """.trimIndent()
        val erh = SymbolTable.collectErhSymbols(text)
        assertEquals(
            mapOf(
                "MAXSIZE" to "300",           // 先到者胜（999 被拒）
                "FLAG_ONLY" to "",            // 无替换式宏（ErhLoader.cs:167-171 IsNull）
                "FOO" to "7",                 // 大写归一（IGNORE CASE / OrdinalIgnoreCase 哈希）
                "LABEL" to "\"文字列\"",       // ';' 注释剥离，其余原文
                "CHAIN" to "MAXSIZE",         // 原文保留，不求值
                "MULTI" to "1 + 2",           // {...} 续行块以单空格拼接
            ),
            erh.macros,
            "F(X) 参数化宏不收集（原版禁声明）",
        )
        // 兼容入口：collectMacros 只给名集
        assertEquals(erh.macros.keys, SymbolTable.collectMacros(text))
    }

    @Test
    fun collectErhDimConstsAsRawSpecs() {
        // ERH #DIM CONST 收集（原文未求值；求值/纳入常量表归桥侧 sizeConsts）
        val text = """
            #DIM CONST 人物数量上限 = 200
            #DIM CONST ARR = 0, 100, 500
            #DIM CONST EXPR = 1 + 2
            #DIM CONST HEX = 0xD5DEDF
            #DIM CONST TABSPECS = 5 ;これはコメント
            #DIMS CONST STR_C = "主页", "个别"
            #DIM CONST NOINIT
            #DIM 普通全局 = 7
            #DIM CONST DUP = 1
            #DIM CONST DUP = 2
            #DIMS	CONST	TAB_KW	=	9
        """.trimIndent()
        val specs = SymbolTable.collectErhSymbols(text).dimConsts
        assertEquals(
            listOf("人物数量上限", "ARR", "EXPR", "HEX", "TABSPECS", "STR_C", "DUP", "TAB_KW"),
            specs.map { it.name },
            "CONST 必须带初值（UserDefinedVariableData.cs:294-297）→ NOINIT 不收；非 CONST 全局不收",
        )
        assertEquals("200", specs[0].initializer)
        assertEquals("0, 100, 500", specs[1].initializer)
        assertTrue(specs[5].isStr, "#DIMS CONST 收集为 isStr 原文（桥侧只纳整型）")
        assertEquals("1", specs[6].initializer, "重名 #DIM CONST：先到者胜（同 D.8.2 同函数 #DIM 口径）")
        assertEquals("9", specs[7].initializer, "TAB 分隔关键字")
    }

    @Test
    fun scanLabelsResolvesLocalSizeFromConstTable() {
        // #LOCALSIZE 的值不是裸整数而是常量表达式（宏/ERH CONST 均可当值）：
        // spec: LogicalLineParser.cs:224-234 —— Analyse token 流 → ReduceIntegerTerm
        // → Restructure(null) 须为 SingleLongTerm。语料实形 fromEN/MISC.ERB:1246
        // `#LOCALSIZE 人物数量上限`（DIM.ERH:209 `#DIM CONST 人物数量上限 = 200`），
        // 旧实现 toIntOrNull 只认字面量 → localSize=0，运行期 LOCAL 退回默认长度。
        val cfg = SymbolConfig(constValues = mapOf("人物数量上限" to 200L, "LS8" to 8L))
        val labels = SymbolTable.scanLabels(
            """
            @A
            #LOCALSIZE 人物数量上限
            	RETURN
            @B
            #LOCALSSIZE LS8 + 1
            	RETURN
            @C
            #LOCALSIZE -1
            #LOCALSIZE 0
            	RETURN
            @D
            #LOCALSIZE 没有这个常量
            	RETURN
            @EVENTFIRST
            #LOCALSIZE 人物数量上限
            	RETURN
            """.trimIndent(),
            cfg,
        )
        // 1) CONST 标识符 → 200
        assertEquals(200, labels[0].localSize, "ERH CONST 标识符形态（LogicalLineParser.cs:224-234）")
        // 2) 宏常量表达式 → 9（构建侧由 sizeConsts 定点折出整表，此处直喂结果）
        assertEquals(9, labels[1].localsSize, "表达式形态走 DimSizeEvaluator")
        // 3) <=0 告警后不生效（LocalsizeLessThan1，:236-239）→ 保持 0
        assertEquals(0, labels[2].localSize, "非正值不生效")
        // 4) 不可解 → SharpHasNotValidValue 不生效（:234）→ 保持 0
        assertEquals(0, labels[3].localSize, "未知标识符回落 0（与旧口径一致）")
        // 5) 事件函数整个分支被跳过（EventFuncIgnoreSpecified，:226-233）→ 不写 LocalLength
        assertEquals(0, labels[4].localSize, "事件函数忽略 #LOCALSIZE")
    }

    @Test
    fun scanLabelsKeepsSemicolonInsideDimStringInitializer() {
        // #DIM 值段的 token 流由 Analyse 产出，';' 注释在词法层剥离且引号内不算
        // （spec: LexicalAnalyzer.cs:919-929 + ReadString 引号扫描 :330）；
        // 旧实现 substringBefore(';') 对引号不设防，把 "a;b" 截成 "a。
        val labels = SymbolTable.scanLabels(
            """
            @F
            #DIMS X = "a;b"
            #DIM Y = 3 ;コメント
            #DIMS Z = "x;y", "u"
            	RETURN
            """.trimIndent(),
        )
        val specs = labels[0].dimSpecs
        assertEquals("\"a;b\"", specs[0].initializer, "引号内的 ';' 是字符串内容")
        assertEquals("3", specs[1].initializer, "引号外的 ';' 起注释剥离")
        assertEquals("\"x;y\", \"u\"", specs[2].initializer, "多元素初值中的 ';' 不切断")
    }

    @Test
    fun collectErhDefineBodyTerminatesAtCaretMarker() {
        // #DEFINE 展开体 token 流同样由 Analyse 产出（ErhLoader.cs:166）：
        // Analyse 侧只有 `;!;`（与 DebugMode 的 `;#;`）继续，`;^;` 即注释开始
        // （spec: LexicalAnalyzer.cs:919-929）；`;^;` 的「继续」只存在于行首
        // SkipWhiteSpace 侧（LexicalAnalyzer.cs:668-671）。旧实现把 `;^;` 当继续标记，
        // `#DEFINE A 1 ;^; 2` 的体多收了 " 2"。
        val erh = SymbolTable.collectErhSymbols(
            """
            #DEFINE MY_A 1 ;^; 2
            #DEFINE MY_B 1 ;!; 2
            """.trimIndent() + "\n",
        )
        assertEquals("1", erh.macros["MY_A"], "`;^;` 在体中间即注释开始")
    }

    @Test
    fun fullCorpusSmoke() {
        val raw = System.getenv("ERATW_CORPUS") ?: System.getProperty("eratw.corpus")
        assumeTrue(!raw.isNullOrBlank(), "未提供语料路径（ERATW_CORPUS / -Deratw.corpus），跳过全量冒烟")
        val corpus = Path.of(raw!!)
        assumeTrue(corpus.isDirectory(), "语料目录不存在: $corpus")

        val table = SymbolTable.build(corpus)

        // 基线口径（2026-10-17，specs/loader.md 附录 D.3）只覆盖游戏本体文件；
        // E2E 线（2026-10-18 起）把 fixtures/golden 的 13 个 GOLDEN_*.ERB 复制进了语料目录，
        // 统计时剔除这些复制件，保持基线语义稳定。
        fun gameOnly(defs: List<LabelDef>) = defs.filter { !it.file.startsWith("GOLDEN_") }
        val goldenDefs = table.nameKeys.sumOf { k -> table.defsOf(k).count { it.file.startsWith("GOLDEN_") } }
        val byKeyGame = table.nameKeys.associateWith { gameOnly(table.defsOf(it)) }
        val totalGame = byKeyGame.values.sumOf { it.size }
        val uniqueGame = byKeyGame.count { it.value.isNotEmpty() }
        val dupKeysGame = byKeyGame.filter { it.value.size > 1 }
        val nonEventDupGame = dupKeysGame.count { !it.value.first().isEvent }
        val eventDupGame = dupKeysGame.count { it.value.first().isEvent }

        // 全量语料基线（2026-10-17，见 specs/loader.md 附录 D.3）
        assertEquals(110_066, totalGame, "@标签定义总数（不含 GOLDEN 复制件）")
        assertEquals(110_042, uniqueGame, "去重名字数")
        assertEquals(6, dupKeysGame.size, "重名组数")
        assertEquals(0, nonEventDupGame, "非事件重名必须为 0")
        assertEquals(6, eventDupGame, "事件重名组数（基线：6 组全部为事件函数）")
        println("（信息）GOLDEN 复制件定义数 = $goldenDefs，不计入基线")

        val groups = dupKeysGame.mapValues { it.value.size }
        assertEquals(
            mapOf(
                "EVENTFIRST" to 2,
                "EVENTLOAD" to 9,
                "EVENTTURNEND" to 2,
                "EVENTCOMEND" to 6,
                "EVENTTRAIN" to 7,
                "EVENTCOM" to 4,
            ),
            groups,
            "6 组事件函数重名的定义数",
        )

        // 抽样：形参个数与 #LOCALSIZE/#LOCALSSIZE（三处均已对照源文件行）
        val casino = assertNotNull(table.resolve("FUNC_CASINO"))
        assertEquals(3, casino.argCount, "FUNC_CASINO(TYPE, ARG, ARG:1)")
        assertEquals(3, casino.argIntCount, "TYPE 无默认 + ARG + ARG:1 → 全整型")
        assertEquals(0, casino.argStrCount)
        assertEquals(1, casino.localSize, "CASINO/CASINO.ERB:1168 #LOCALSIZE 1")
        assertEquals(1, casino.localsSize, "CASINO/CASINO.ERB:1169 #LOCALSSIZE 1")

        val mobColor = assertNotNull(table.resolve("GET_MOB_ELEMENT_COLORNAME"))
        assertEquals(1, mobColor.argCount, "GET_MOB_ELEMENT_COLORNAME(strPart=\"\")")
        assertEquals(0, mobColor.argIntCount)
        assertEquals(1, mobColor.argStrCount, "字符串默认值 → 字符串形参（本语料事实规则）")
        assertEquals(1, mobColor.localSize, "BODY_INFO/MOB_FUNCTION.ERB:2 #LOCALSIZE 1")

        // 该名字有 3 处定义：COLOR.ERB 整文件在 [SKIPSTART](1-295)、PANIMATION.ERB:102 在 [SKIPSTART](1-355)，
        // 唯一生效者是 UI_Components:959（这条正是被本测试纠正过的易错点）
        val colorBar = assertNotNull(table.resolve("PRINT_COLORBAR"))
        assertEquals(959, colorBar.lineNo, "PRINT_COLORBAR 生效行")
        assertEquals(7, colorBar.argCount, "7 个形参（含默认值）")
        assertEquals(5, colorBar.argIntCount, "VAL/MAX_VAL/L_LEN=20/COLOR_FG=-1/COLOR_BG=-1")
        assertEquals(2, colorBar.argStrCount, "CHAR_FG=\"*\" 与 CHAR_BG=\".\"")
        assertEquals(1, colorBar.localSize, "#LOCALSIZE 1")
        assertEquals(1, colorBar.localsSize, "#LOCALSSIZE 1")
    }

    // ==================================================================
    // 装载期告警与 ERH 诊断（ErbPreprocessor 统一后新增的留痕面）
    // ==================================================================

    @Test
    fun scanLabelsWarnsOnUnclosedPreprocessRegion() {
        // spec: ErbLoader.cs:513 + PPState.FileEnd :169-180 —— 文件结束仍未闭合的
        // [SKIPSTART] 原版 Warn level 1（「～がありません」），只报最内层一次；
        // ELSEIF 报成 ENDIF。erbcc 不中断装载，但必须留痕。
        val warnings = ArrayList<LoadWarning>()
        val labels = SymbolTable.scanLabels(
            """
            @VISIBLE
            	RETURN
            [SKIPSTART]
            @SWALLOWED
            	RETURN
            """.trimIndent(),
            file = "X.ERB",
            warnings = warnings,
        )
        assertEquals(listOf("VISIBLE"), labels.map { it.name }, "[SKIPSTART] 之后的标签不产出")
        assertEquals(1, warnings.size, "只报最内层未闭合区间一次（PPState.FileEnd）")
        assertEquals("X.ERB", warnings[0].file)
        assertTrue(warnings[0].message.contains("SKIPEND"), "报缺少的闭合指令名，实得：${warnings[0].message}")
    }

    @Test
    fun erhLogicalLinesWithLinesTracksStartLineNumber() {
        // spec: ErhLoader.cs:94 —— 装载期报错定位 ScriptPosition(filename, LineNo)，
        // 行号是逻辑行**起始**的 1 基物理行号（{} 块取 `{` 所在行）。
        val lines = SymbolTable.erhLogicalLinesWithLines(
            """
            #DEFINE A 1
            {
            #DEFINE B
            2
            }
            """.trimIndent(),
        )
        assertEquals(listOf(1 to "#DEFINE A 1", 2 to "#DEFINE B 2"), lines)
    }

    @Test
    fun collectErhSymbolsDiagsCoverEveryDowngrade() {
        // ERH 装载里原版会报错（多数 level 2 → 中止整条 ERH 链）的行，erbcc 不中断
        // 整批但逐条留痕。口径证据：
        //   非 # 行       ErhLoader.cs:96-99 NotStartedSharpLineInHeader
        //   # 后无名      :101-106 CanNotInterpretSharpLine
        //   宏名重复      :155-164 + IdentifierDictionary.cs:324-327（先到者胜）
        //   参数化宏      :165 + :241-244 CanNotDeclaredFuncMacro
        //   缺宏名        :148-152 MissingReplacementSource
        //   #FUNCTION     :114-119 → analyzeSharpFunction :390-393 NotImplCodeEE
        //   未知指令      :127 UnknownPreprocessorInSharpLine
        //   CONST 缺初值  UserDefinedVariableData.cs:294-297/344-350
        val text = """
            普通行
            #
            #DEFINE DUP 1
            #DEFINE DUP 2
            #DEFINE MYF(X) X
            #DEFINE
            #FUNCTION
            #UNKNOWN
            #DIM CONST NOINIT
        """.trimIndent()
        val erh = SymbolTable.collectErhSymbols(text, "T.ERH")
        assertEquals("1", erh.macros["DUP"], "重名宏先到者胜")
        assertTrue(erh.dimConsts.isEmpty(), "CONST 缺初值不注册")
        assertEquals(8, erh.diag.size, "8 条降级逐条留痕，实得：${erh.diag.joinToString("\n")}")
        assertEquals(
            listOf(1, 2, 4, 5, 6, 7, 8, 9),
            erh.diag.map { it.line },
            "行号 = 逻辑行起始的 1 基物理行号（spec: ErhLoader.cs:94）",
        )
        assertEquals("T.ERH", erh.diag[0].file)
        assertTrue(erh.diag[0].message.contains("非 #"), "实得：${erh.diag[0].message}")
        assertTrue(erh.diag[1].message.contains("无指令名"), "实得：${erh.diag[1].message}")
        assertTrue(erh.diag[2].message.contains("已定义的宏"), "实得：${erh.diag[2].message}")
        assertTrue(erh.diag[3].message.contains("参数化宏"), "实得：${erh.diag[3].message}")
        assertTrue(erh.diag[4].message.contains("缺宏名"), "实得：${erh.diag[4].message}")
        assertTrue(erh.diag[5].message.contains("FUNCTION"), "实得：${erh.diag[5].message}")
        assertTrue(erh.diag[6].message.contains("未知指令"), "实得：${erh.diag[6].message}")
        // CONST 缺初值在 erbcc 走 parseDimSpec 的解析失败路径（原版 ConstHasNotInitialValue
        // 同样是「变量不注册」，spec: UserDefinedVariableData.cs:294-297/344-350）
        assertTrue(erh.diag[7].message.contains("无法解析"), "实得：${erh.diag[7].message}")
        assertTrue(erh.diag[7].message.contains("NOINIT"), "实得：${erh.diag[7].message}")
    }
}
