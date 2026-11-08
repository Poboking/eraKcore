package com.eratw.port.core.loader

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.NullTerm
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 侧车文本编解码单测（`BootMetadata.encodeText` / `parse` / `read`）。
 *
 * 判据只有一条：**模型 → 文本 → 模型 → 文本** 必须逐字节闭环，且覆盖到的字段全部经得起
 * 往返（表达式树含全部 11 种节点、`#DIM` 全字段、函数元数据全位、宏/常量表）。
 * 真实语料上的量级与「与 CompileResult 一致」的读数在 erbcc 的 `BootMetaRoundTripTest`。
 */
class BootMetadataTest {

    // ------------------------------------------------------------------
    // 样例：一份把侧车每个字段都填满、表达式树覆盖全部节点的元数据
    // ------------------------------------------------------------------

    private fun sample(): BootMetadata {
        val varRef = VarRef(
            name = "TALENT",
            indices = listOf(IntLiteral(0L, 1, 2), IntLiteral(3L, 1, 4)),
            operandType = OperandType.INT,
            line = 10,
            col = 20,
        )
        val counted = VarRef(
            name = "COUNT",
            indices = listOf(VarRef("TARGET", emptyList(), isNoArg = true, operandType = OperandType.INT)),
            operandType = OperandType.INT,
            isConst = false,
            line = 11,
            col = 21,
        )
        // CMP-84：`subKey`（`变量@函数` 形，`SubKey` 段是**引号串**）此前在样例里 0 实例，
        // 写了从不对拍 —— 编译器一旦开始产 subKey 就没有回归面。这里固定一个带 `@` 的引用。
        val withSubKey = VarRef(
            name = "LOCAL",
            subKey = "GOOD_SUB",
            indices = emptyList(),
            operandType = OperandType.INT,
            line = 12,
            col = 22,
        )
        val form = FormStringTerm(
            parts = listOf(
                FormPart.Text("前置"),
                FormPart.Embedded(IntLiteral(7L, 3, 1), FormConversion.Curly(IntLiteral(3L, 3, 2), alignLeft = true)),
                FormPart.Text("`中间`"),
                FormPart.Embedded(StrLiteral("q\""), FormConversion.Percent(null, alignLeft = false)),
                FormPart.Embedded(
                    IntLiteral(1L),
                    FormConversion.YenAt(StrLiteral("是"), StrLiteral("否")),
                ),
            ),
            line = 30,
            col = 40,
        )
        val richGroup: List<Expr> = listOf(
            IntLiteral(42L, 1, 1),
            StrLiteral("字 符\"串\\反斜杠\t制表\n换行", 2, 2),
            NullTerm(isInt = true, line = 3, col = 3),
            varRef,
            counted,
            withSubKey,
            KeywordIndexArg(StrLiteral("薬"), "ITEM", 0, 4, 4),
            FuncCall(
                name = "TOSTR",
                args = listOf(IntLiteral(1L), null, StrLiteral("s")),
                operandType = OperandType.STR,
                line = 5,
                col = 5,
            ),
            Unary(OperatorCode.MINUS, IntLiteral(9L), 6, 6),
            UnaryAfter(OperatorCode.INCREMENT, varRef, 7, 7),
            Binary(OperatorCode.PLUS, varRef, IntLiteral(1L), OperandType.INT, 8, 8),
            Ternary(IntLiteral(0L), StrLiteral("t"), StrLiteral("f"), OperandType.STR, 9, 9),
            form,
        )
        return BootMetadata(
            labels = mapOf(
                "SYSTEM_TITLE" to mapOf("LOOP" to 12, "TAIL" to 40),
                "EVENTFIRST#3" to emptyMap(),
            ),
            dataSections = mapOf(
                5 to listOf(richGroup, listOf(StrLiteral("单元素组", 1, 1)), emptyList()),
                17 to listOf(emptyList()),
            ),
            globalDims = listOf(
                BootMetadata.RawDim("#DIM CONST RM_TITLE = 100000 ; タイトル画面の画像ID", "リソース管理/ResourceManager.ERH", 15),
                BootMetadata.RawDim("#DIMS GLOBAL NAME, 10", "DIM.ERH", 209),
            ),
            privateDims = listOf(
                BootMetadata.PrivateDim("SYSTEM_TITLE", DimSpec("LOC_OK", isStr = false, dims = intArrayOf(1))),
                BootMetadata.PrivateDim(
                    "MAKE_TALENT_MAP",
                    DimSpec(
                        name = "LOC_NAMES",
                        isStr = true,
                        dims = intArrayOf(3, 4),
                        isConst = false,
                        isRef = false,
                        isGlobal = false,
                        isSavedata = false,
                        isCharaData = false,
                        isStatic = false,
                        isPrivate = true,
                        defaultsStr = listOf("a b", "c\"d", ""),
                    ),
                ),
                BootMetadata.PrivateDim(
                    "COM0",
                    DimSpec(
                        name = "TABLE",
                        isStr = false,
                        dims = intArrayOf(2),
                        isConst = true,
                        isPrivate = true,
                        defaultsInt = longArrayOf(1L, -2L),
                    ),
                ),
                // 4.981 fork 的 `#DIMF`（浮点私有变量）；v2 起随侧车往返。
                // v3 起带浮点初值 `defaultsFloat`（单值广播，见 DimSpec 的 KDoc）。
                BootMetadata.PrivateDim(
                    "RATE_UPDATE",
                    DimSpec(
                        name = "倍率",
                        isStr = false,
                        dims = intArrayOf(1),
                        isFloat = true,
                        isStatic = false,
                        isPrivate = true,
                        defaultsFloat = 0.25,
                    ),
                ),
            ),
            functions = listOf(
                FuncMeta(
                    index = 0, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE",
                    argIntCount = 0, argStrCount = 0, defIndex = 12, position = "TITLE.ERB:3",
                ),
                FuncMeta(
                    index = 7, key = "EVENTFIRST#3", name = "EVENTFIRST",
                    isEvent = true, isOnly = true, isSingle = true, returnsStr = true,
                    argIntCount = 1, argStrCount = 2, localSize = 10, localsSize = 5,
                    // v3 可选原子：`#LOCALFSIZE 16`（>0 才写出，见 FORMAT_VERSION 的 KDoc）。
                    floatLocalSize = 16,
                    defIndex = 3, position = "COMMON.ERB:900",
                ),
                FuncMeta(
                    index = 9, key = "MINI_ECHO", name = "MINI_ECHO",
                    isMethod = true, returnsStr = true, argIntCount = 1,
                    defIndex = 30, position = "·event-entry·",
                ),
                // CMP-84：`isPri` / `isLater` 两个位此前**没有任何实例为 true** ⇒ 写出侧的
                // bit 8 / bit 16 与读侧的两条 `and` 从来没被任何断言走过。
                FuncMeta(
                    index = 11, key = "EVENTTRAIN#5", name = "EVENTTRAIN",
                    isEvent = true, isPri = true, isLater = true,
                    defIndex = 21, position = "COMMON.ERB:901",
                ),
                // 4.981 `#FUNCTIONF`（第三种 MethodType）：floatLocalSize = 0 ⇒ 侧车写
                // `0 1` 成对原子（见 FORMAT_VERSION 的 KDoc），读侧据此无歧义还原。
                FuncMeta(
                    index = 12, key = "FLT_METHOD", name = "FLT_METHOD",
                    isMethod = true, returnsFloat = true,
                    defIndex = 31, position = "COMMON.ERB:902",
                ),
            ),
            fileByKey = mapOf(
                "SYSTEM_TITLE" to "TITLE.ERB",
                "EVENTFIRST#3" to "COMMON.ERB",
                // CMP-84：真实产物把**合成事件入口**的源文件登记成哨兵串（`CorpusCompile`
                // 的 `EVENT_ENTRY_POSITION`），不是路径。样例必须覆盖它，否则这条「值不是
                // 路径」的形态在往返测试里 0 实例。
                "EVENTFIRST" to "·event-entry·",
            ),
            dimParseConfig = DimParseConfig(
                systemSaveInBinary = true,
                macros = mapOf("人物数量上限" to "200", "FLAG" to "1p1"),
                intConstants = mapOf("C_WHITE" to 0xFFFFFFL, "NULLC" to 0L),
                strConstants = mapOf(
                    "EFFNAME_SPLIT" to ",",
                    "含引号与换行" to "a\"b\nc\td",
                ),
            ),
            engineTag = "erbcc 0.1.0-SNAPSHOT",
            programSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )
    }

    // ------------------------------------------------------------------
    // 往返
    // ------------------------------------------------------------------

    @Test
    fun `模型到文本再回模型 逐字段相等且二次编码逐字节相同`() {
        val original = sample()
        val text = original.encodeText()
        val decoded = BootMetadata.parse(text)

        assertEquals(text, decoded.encodeText(), "解码后再编码必须与首次逐字节相同")

        assertEquals(original.labels, decoded.labels, "标签表")
        assertEquals(original.dataSections.keys, decoded.dataSections.keys, "数据节下标集合")
        for ((idx, groups) in original.dataSections) {
            assertEquals(groups, decoded.dataSections[idx], "数据节 $idx 的组（Expr 是 data class，结构相等即全覆盖）")
        }
        assertEquals(original.globalDims, decoded.globalDims, "全局 #DIM 原文")
        assertEquals(original.privateDims.size, decoded.privateDims.size, "私有 #DIM 条数")
        for ((i, p) in original.privateDims.withIndex()) {
            val d = decoded.privateDims[i]
            assertEquals(p.functionKey, d.functionKey, "私有 #DIM[$i] 函数键")
            assertEquals(p.spec.name, d.spec.name, "私有 #DIM[$i] 名")
            assertEquals(p.spec.isStr, d.spec.isStr, "私有 #DIM[$i] isStr")
            assertEquals(p.spec.isConst, d.spec.isConst, "私有 #DIM[$i] isConst")
            assertEquals(p.spec.isRef, d.spec.isRef, "私有 #DIM[$i] isRef")
            assertEquals(p.spec.isGlobal, d.spec.isGlobal, "私有 #DIM[$i] isGlobal")
            assertEquals(p.spec.isSavedata, d.spec.isSavedata, "私有 #DIM[$i] isSavedata")
            assertEquals(p.spec.isCharaData, d.spec.isCharaData, "私有 #DIM[$i] isCharaData")
            assertEquals(p.spec.isStatic, d.spec.isStatic, "私有 #DIM[$i] isStatic")
            assertEquals(p.spec.isPrivate, d.spec.isPrivate, "私有 #DIM[$i] isPrivate")
            assertEquals(p.spec.isFloat, d.spec.isFloat, "私有 #DIM[$i] isFloat（#DIMF）")
            assertEquals(p.spec.defaultsFloat, d.spec.defaultsFloat, "私有 #DIM[$i] defaultsFloat（#DIMF 初值）")
            assertEquals(p.spec.dims.toList(), d.spec.dims.toList(), "私有 #DIM[$i] dims")
            assertEquals(p.spec.defaultsInt?.toList(), d.spec.defaultsInt?.toList(), "私有 #DIM[$i] defaultsInt")
            assertEquals(p.spec.defaultsStr, d.spec.defaultsStr, "私有 #DIM[$i] defaultsStr")
        }
        assertEquals(original.functions, decoded.functions, "函数元数据（data class 全字段）")
        assertEquals(original.fileByKey, decoded.fileByKey, "函数键 → 源文件")
        assertEquals(original.dimParseConfig, decoded.dimParseConfig, "DimParseConfig")
        assertEquals(original.engineTag, decoded.engineTag, "engine 头")
        assertEquals(original.programSha256, decoded.programSha256, "program-sha256 头")
    }

    @Test
    fun `表达式树的每一种节点都经得起往返`() {
        val original = sample()
        val decoded = BootMetadata.parse(original.encodeText())
        val group = decoded.dataSectionAt(5)!!.first()
        assertEquals(13, group.size, "样例组应含 13 个表达式（覆盖全部节点类型 + subKey 变量）")
        val expected = original.dataSectionAt(5)!!.first()
        assertEquals(expected, group, "整组表达式结构相等")
        // 单独再钉两类嵌套：FORM 的三种转换 + 带下标的变量引用
        val form = group.last() as FormStringTerm
        assertEquals(5, form.parts.size, "FORM 段数")
        assertTrue((form.parts[1] as FormPart.Embedded).conversion is FormConversion.Curly)
        assertTrue((form.parts[3] as FormPart.Embedded).conversion is FormConversion.Percent)
        assertTrue((form.parts[4] as FormPart.Embedded).conversion is FormConversion.YenAt)
        val v = group[3] as VarRef
        assertEquals("TALENT", v.name)
        assertEquals(listOf<Expr>(IntLiteral(0L, 1, 2), IntLiteral(3L, 1, 4)), v.indices)
    }

    /**
     * CMP-84：`VarRef.subKey`（`变量@函数`）的**引号串**形态往返。
     *
     * 侧车把 subKey 写成 `(var "<name>" "<subKey>" …)`；`subKey` 为 null 时写裸 `-`
     * （样例里 `TALENT` / `COUNT` 已覆盖 null 侧）。此前样例里 0 个非 null 实例，
     * 这一段编解码写了从不对拍。
     */
    @Test
    fun `VarRef 的 subKey 引号形经得起往返`() {
        val original = sample()
        val text = original.encodeText()
        assertTrue(
            text.contains("(var \"LOCAL\" \"GOOD_SUB\" 0 0 0 12 22 ())"),
            "subKey 必须写成引号串而不是裸记号: $text",
        )
        val decoded = BootMetadata.parse(text)
        val ref = decoded.dataSectionAt(5)!!.first().filterIsInstance<VarRef>().single { it.name == "LOCAL" }
        assertEquals("GOOD_SUB", ref.subKey, "subKey 往返")
        assertEquals(null, (decoded.dataSectionAt(5)!!.first()[3] as VarRef).subKey, "null subKey 必须读回 null（写 - ）")
    }

    /**
     * CMP-84：`fileByKey` 的值允许是**非路径哨兵**（`·event-entry·`）。
     *
     * 取舍：哨兵保留、不清洗。它是 `CorpusCompile` 对「合成事件入口」的定位标记，运行期
     * 经 `fileOf()` 只用于报错定位（不会有文件操作拿它当路径）；若在写侧改写/过滤，全量
     * 真实产物的 `program.meta` 字节会变（全量语料实测 6 条 `file` 记录是哨兵），
     * 而清洗本身不带来语义收益。
     * 因此这里钉的是「哨兵原样往返」+「`fileOf()` 大小写归一后照旧返回它」。
     */
    @Test
    fun `fileByKey 的非路径哨兵原样往返`() {
        val original = sample()
        val decoded = BootMetadata.parse(original.encodeText())
        assertEquals("·event-entry·", decoded.fileOf("eventfirst"), "合成事件入口的源文件哨兵必须原样读回")
        assertEquals(original.fileByKey, decoded.fileByKey, "fileByKey 全表往返")
    }

    /**
     * CMP-84：7 个 `FuncMeta` 标志位逐位往返（`isEvent`/`isMethod`/`isOnly`/`isPri`/
     * `isLater`/`isSingle`/`returnsStr`）。
     *
     * 此前样例只有 5 个位出现过 true，`isPri` / `isLater` 的 bit 8 / bit 16 无任何断言。
     * 这里给每一位单独造一条记录（互不干扰），再全量 `assertEquals(List<FuncMeta>)`。
     */
    @Test
    fun `函数元数据七位标志逐位往返`() {
        val bits = listOf<Pair<String, FuncMeta>>(
            "isEvent" to FuncMeta(index = 0, key = "A", name = "A", isEvent = true),
            "isMethod" to FuncMeta(index = 1, key = "B", name = "B", isMethod = true),
            "isOnly" to FuncMeta(index = 2, key = "C", name = "C", isOnly = true),
            "isPri" to FuncMeta(index = 3, key = "D", name = "D", isPri = true),
            "isLater" to FuncMeta(index = 4, key = "E", name = "E", isLater = true),
            "isSingle" to FuncMeta(index = 5, key = "F", name = "F", isSingle = true),
            "returnsStr" to FuncMeta(index = 6, key = "G", name = "G", returnsStr = true),
        )
        val original = BootMetadata(functions = bits.map { it.second })
        val decoded = BootMetadata.parse(original.encodeText())
        assertEquals(original.functions, decoded.functions, "7 个标志位必须逐位往返")
        // 逐位点名（不是只靠 data class 相等：读侧某个 `and` 写错位也能凑巧相等）
        for ((i, e) in bits.withIndex()) {
            val got = decoded.functions[i]
            assertEquals(e.first == "isEvent", got.isEvent, "位 isEvent @${e.first}")
            assertEquals(e.first == "isMethod", got.isMethod, "位 isMethod @${e.first}")
            assertEquals(e.first == "isOnly", got.isOnly, "位 isOnly @${e.first}")
            assertEquals(e.first == "isPri", got.isPri, "位 isPri @${e.first}")
            assertEquals(e.first == "isLater", got.isLater, "位 isLater @${e.first}")
            assertEquals(e.first == "isSingle", got.isSingle, "位 isSingle @${e.first}")
            assertEquals(e.first == "returnsStr", got.returnsStr, "位 returnsStr @${e.first}")
        }
        // 样例里的组合位（PRI + LATER 同时为真）也单独钉一次
        val combo = sample().functions.single { it.key == "EVENTTRAIN#5" }
        val comboBack = BootMetadata.parse(sample().encodeText()).functions.single { it.key == "EVENTTRAIN#5" }
        assertTrue(combo.isPri && combo.isLater, "夹具自检：样例必须含 isPri+isLater 真值")
        assertEquals(combo, comboBack, "isPri+isLater 组合往返")
    }

    // ------------------------------------------------------------------
    // v3：`#DIMF` 初值（defaultsFloat）与 `#LOCALFSIZE`（floatLocalSize）
    // ------------------------------------------------------------------

    /**
     * v3 两个可选原子：`private-dim` 行尾的浮点初值、`function` 行 `localsSize` 之后的
     * `floatLocalSize`。
     *
     * 写侧「非默认才写」：样例里 `倍率 = 0.25` 与 `#LOCALFSIZE 16` 各产生一个原子，
     * 其余行与 v2 逐字节相同（真语料全部是后者，产物字节不受影响）。
     */
    @Test
    fun `v3 浮点初值与 LOCALFSIZE 原子写出并逐字段读回`() {
        val original = sample()
        val text = original.encodeText()
        assertTrue(text.contains("format-version 3\n"), "格式版本必须升到 3: $text")
        assertTrue(
            text.contains("private-dim \"RATE_UPDATE\" \"倍率\" 0 0 0 0 0 0 0 1 1 1 - - 0.25\n"),
            "浮点初值必须以裸浮点写在 private-dim 行尾: $text",
        )
        assertTrue(
            text.contains("function 7 3 \"EVENTFIRST#3\" \"EVENTFIRST\" 101 1 2 10 5 16 \"COMMON.ERB:900\"\n"),
            "floatLocalSize 必须写在 localsSize 之后、position 之前: $text",
        )
        // 没有浮点初值 / #LOCALFSIZE 的声明：行尾与 v2 相同（不写 `-` 占位）
        assertTrue(
            text.contains("private-dim \"SYSTEM_TITLE\" \"LOC_OK\" 0 0 0 0 0 0 1 0 0 1 - -\n"),
            "无浮点初值的声明不得多出原子: $text",
        )

        val decoded = BootMetadata.parse(text)
        assertEquals(0.25, decoded.privateDims.single { it.spec.name == "倍率" }.spec.defaultsFloat, "float 初值往返")
        assertEquals(
            null,
            decoded.privateDims.single { it.spec.name == "LOC_OK" }.spec.defaultsFloat,
            "没写初值必须读回 null",
        )
        assertEquals(16, decoded.functions.single { it.key == "EVENTFIRST#3" }.floatLocalSize, "floatLocalSize 往返")
        assertEquals(0, decoded.functions.single { it.key == "SYSTEM_TITLE" }.floatLocalSize, "未声明 #LOCALFSIZE = 0")
        assertEquals(original.functions, decoded.functions, "函数元数据全字段往返（含 floatLocalSize）")
        assertEquals(text, decoded.encodeText(), "v3 二次编码逐字节闭环")
    }

    /**
     * 旧侧车（v2）与「v3 头但没写新原子」（erbcc 尚未接线的形态）都必须可读：
     * 浮点初值读成 null、`floatLocalSize` 读成 0，其余字段照旧。
     */
    @Test
    fun `v2 侧车与缺省原子的 v3 侧车按零值读入`() {
        // 手写的最小 v2 侧车：function 行没有 floatLocalSize，private-dim 行尾只有 defaultsStr。
        val v2 = """
            |format-version 2
            |engine "erbcc test"
            |program-sha256 -
            |labels 0
            |files 0
            |data-sections 0
            |functions 1
            |function 7 3 "EVENTFIRST" "EVENTFIRST" 1 1 2 10 5 "COMMON.ERB:900"
            |global-dims 0
            |private-dims 1
            |private-dim "F" "RATE" 0 0 0 0 0 0 0 1 1 4 - -
            |dim-config 1
            |macros 0
            |constants 0
            |str-constants 0
            |end
            |
        """.trimMargin()
        val old = BootMetadata.parse(v2)
        assertEquals(0, old.functions.single().floatLocalSize, "v2 function 行无 floatLocalSize ⇒ 0")
        val oldSpec = old.privateDims.single().spec
        assertTrue(oldSpec.isFloat, "v2 的 isFloat 位仍要读")
        assertEquals(null, oldSpec.defaultsFloat, "v2 private-dim 无浮点初值 ⇒ null")
        assertEquals(listOf(4), oldSpec.dims.toList(), "dims 不受新原子影响")

        // 同一个文件只把版本号改成 3：v3 头 + 缺省原子（erbcc 接线前的真实产物形态）。
        val v3NoAtoms = v2.replaceFirst("format-version 2", "format-version 3")
        val next = BootMetadata.parse(v3NoAtoms)
        assertEquals(0, next.functions.single().floatLocalSize, "v3 缺省 floatLocalSize ⇒ 0")
        assertEquals(null, next.privateDims.single().spec.defaultsFloat, "v3 缺省浮点初值 ⇒ null")
        assertEquals(next.functions, BootMetadata.parse(next.encodeText()).functions, "读出后再编码仍闭环")
    }

    @Test
    fun `控制字符与非 ASCII 不破坏行结构`() {
        val meta = BootMetadata(
            globalDims = listOf(
                BootMetadata.RawDim("行1\u0001行2\ttab\r回车;尾注 — 全角", "dir/ファイル.ERH", 1),
                BootMetadata.RawDim("含 \"引号\" 与 \\ 反斜杠", "a.ERH", 2),
            ),
        )
        val text = meta.encodeText()
        assertTrue(text.endsWith("\n"), "以 LF 结尾")
        assertTrue(!text.contains('\r'), "文本里不得出现裸 CR（必须被转义）")
        // 每条记录都落在一行：global-dim 行数 == 声明条数
        val globalLines = text.lineSequence().filter { it.startsWith("global-dim ") }.toList()
        assertEquals(2, globalLines.size, "两条声明必须各占一行")
        val decoded = BootMetadata.parse(text)
        assertEquals(meta.globalDims, decoded.globalDims, "控制字符/引号/反斜杠往返")
    }

    @Test
    fun `同一模型两次编码逐字节相同且与插入序无关`() {
        val a = sample()
        // 同样的内容，Map 用相反的插入序构造
        val b = BootMetadata(
            labels = a.labels.entries.reversed().associate { it.key to it.value.entries.reversed().associate { e -> e.key to e.value } },
            dataSections = a.dataSections.entries.reversed().associate { it.key to it.value },
            globalDims = a.globalDims,
            privateDims = a.privateDims,
            functions = a.functions.reversed(),
            fileByKey = a.fileByKey.entries.reversed().associate { it.key to it.value },
            dimParseConfig = DimParseConfig(
                systemSaveInBinary = a.dimParseConfig.systemSaveInBinary,
                macros = a.dimParseConfig.macros.entries.reversed().associate { it.key to it.value },
                intConstants = a.dimParseConfig.intConstants.entries.reversed().associate { it.key to it.value },
                strConstants = a.dimParseConfig.strConstants.entries.reversed().associate { it.key to it.value },
            ),
            engineTag = a.engineTag,
            programSha256 = a.programSha256,
        )
        assertEquals(a.encodeText(), a.encodeText(), "同一实例两次编码相同")
        assertEquals(a.encodeText(), b.encodeText(), "插入序不得影响写出字节（键排序 + 函数按 index 排序）")
    }

    // ------------------------------------------------------------------
    // 字符串常量段（`str-constants`）
    // ------------------------------------------------------------------

    /**
     * `strConstants` 是 `#DIMS CONST` 的折叠依据（spec: UserDefinedVariableData.cs:405-416），
     * 侧车必须逐字带上；段位在 `constants` 之后、`end` 之前，值一律引号串。
     */
    @Test
    fun `字符串常量经侧车往返且按名排序`() {
        val original = sample()
        val text = original.encodeText()
        assertTrue(text.contains("str-constants 2\n"), "写出侧必须声明字符串常量段: $text")
        assertTrue(text.contains("str-const \"EFFNAME_SPLIT\" \",\"\n"), "字面量常量须引号串写出: $text")
        // 含引号/换行/制表的常量不得破行（走同一 quote 转义）
        assertTrue(
            text.contains("str-const \"含引号与换行\" \"a\\\"b\\nc\\td\"\n"),
            "特殊字符必须转义成单行记号: $text",
        )

        val decoded = BootMetadata.parse(text)
        assertEquals(original.dimParseConfig.strConstants, decoded.dimParseConfig.strConstants, "字符串常量表往返")
        assertEquals(",", decoded.dimParseConfig.strConstants["EFFNAME_SPLIT"])
        assertEquals(original.dimParseConfig.intConstants, decoded.dimParseConfig.intConstants, "整型常量表不受新段影响")
        assertEquals(text, decoded.encodeText(), "解码后再编码逐字节闭环")
    }

    /**
     * 旧侧车（`constants` 之后直接 `end`，没有 `str-constants` 段）必须仍可读，
     * 读出空表；再次编码时新段以 `str-constants 0` 补出（写出侧恒写，读侧可选）。
     */
    @Test
    fun `旧侧车缺字符串常量段时读成空表`() {
        val text = sample().encodeText()
        val old = text.lineSequence()
            .filterNot { it.startsWith("str-const ") || it.startsWith("str-constants ") }
            .joinToString("\n")
        assertTrue(old.contains("end"), "裁剪后仍以 end 收尾")
        assertTrue(!old.contains("str-const"), "旧侧车文本里不得残留新段: $old")

        val decoded = BootMetadata.parse(old)
        assertTrue(decoded.dimParseConfig.strConstants.isEmpty(), "缺段应读成空表")
        assertEquals(
            sample().dimParseConfig.intConstants,
            decoded.dimParseConfig.intConstants,
            "整型常量表与旧侧车一致",
        )
        assertEquals(sample().dimParseConfig.macros, decoded.dimParseConfig.macros, "宏表与旧侧车一致")
        // 新的写出侧恒写该段（含计数 0），故模型 → 文本 → 模型 → 文本 仍在第二次编码闭合
        val expected = old.removeSuffix("end\n") + "str-constants 0\nend\n"
        assertEquals(expected, decoded.encodeText(), "旧文本 + 空段 = 新编码")
    }

    /** 段内计数与实际条数不符必须报错（与其余段同一校验口径）。 */
    @Test
    fun `字符串常量计数不符被拒绝`() {
        val text = sample().encodeText().replaceFirst("str-constants 2", "str-constants 3")
        assertFailsWith<BootMetaFormatException> { BootMetadata.parse(text) }
    }

    // ------------------------------------------------------------------
    // 文件入口
    // ------------------------------------------------------------------

    @Test
    fun `写文件再读回 与内存模型一致且无 BOM`(@TempDir dir: Path) {
        val original = sample()
        val path = dir.resolve("program.meta")
        Files.write(path, original.encodeBytes())
        val bytes = Files.readAllBytes(path)
        assertTrue(
            bytes.size < 3 || !(bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()),
            "UTF-8 不得带 BOM",
        )
        val decoded = BootMetadata.read(path)
        assertEquals(original.encodeText(), decoded.encodeText(), "读回后文本相同")
        assertEquals(original.functions, decoded.functions, "读回后函数元数据相同")
    }

    // ------------------------------------------------------------------
    // 版本与畸形输入
    // ------------------------------------------------------------------

    @Test
    fun `更高版本被拒绝`() {
        val text = sample().encodeText().replaceFirst(
            "format-version ${BootMetadata.FORMAT_VERSION}",
            "format-version ${BootMetadata.FORMAT_VERSION + 1}",
        )
        val e = assertFailsWith<BootMetaFormatException> { BootMetadata.parse(text) }
        assertTrue(e.message!!.contains("格式版本"), "错误消息应说明版本: ${e.message}")
    }

    @Test
    fun `缺失 end 的截断文件被拒绝`() {
        val lines = sample().encodeText().lines()
        val truncated = lines.dropLast(2).joinToString("\n") // 去掉 end 与一个空尾
        val e = assertFailsWith<BootMetaFormatException> { BootMetadata.parse(truncated) }
        assertTrue(e.message!!.contains("提前结束") || e.message!!.contains("end"), "错误消息: ${e.message}")
    }

    @Test
    fun `声明计数与实际条数不符被拒绝`() {
        val text = sample().encodeText().replaceFirst("global-dims 2", "global-dims 3")
        assertFailsWith<BootMetaFormatException> { BootMetadata.parse(text) }
    }

    @Test
    fun `未知关键字被拒绝`() {
        val text = sample().encodeText().replaceFirst("dim-config 1", "dim-configx 1")
        assertFailsWith<BootMetaFormatException> { BootMetadata.parse(text) }
    }

    @Test
    fun `空文本被拒绝`() {
        assertFailsWith<BootMetaFormatException> { BootMetadata.parse("") }
        assertFailsWith<BootMetaFormatException> { BootMetadata.parse("\n\n") }
    }

    // ------------------------------------------------------------------
    // 查询面
    // ------------------------------------------------------------------

    @Test
    fun `标签与源文件查询大小写不敏感`() {
        val meta = sample()
        assertEquals(12, meta.labelPc("system_title", "loop"), "函数键/标签名都大写归一")
        assertEquals(40, meta.labelPc("SYSTEM_TITLE", "Tail"))
        assertNull(meta.labelPc("SYSTEM_TITLE", "缺失标签"))
        assertNull(meta.labelPc("不存在", "LOOP"))
        assertEquals("TITLE.ERB", meta.fileOf("system_title"), "函数键大写归一")
        assertNull(meta.fileOf("nope"))
        assertNull(meta.dataSectionAt(9999), "未登记的数据节返回 null")
    }

    @Test
    fun `program-sha256 缺省写短横线并读回 null`() {
        val meta = sample().withHeader(programSha256 = null)
        val text = meta.encodeText()
        assertTrue(text.contains("program-sha256 -\n"), "缺省头应为 -: $text")
        assertNull(BootMetadata.parse(text).programSha256, "读回应为 null")
    }

    // ------------------------------------------------------------------
    // v3 function 行可选原子：floatLocalSize / returnsFloat
    // ------------------------------------------------------------------

    @Test
    fun `浮点方法 returnsFloat 往返且非浮点行字节不变`() {
        val original = BootMetadata(
            functions = listOf(
                // 非浮点、无 #LOCALFSIZE：行必须与 v2 逐字节相同（position 紧跟 localsSize）。
                FuncMeta(index = 0, key = "PLAIN", name = "PLAIN", defIndex = 1, position = "A.ERB:1"),
                // 浮点方法、floatLocalSize = 0：成对写 `0 1`。
                FuncMeta(
                    index = 1, key = "FLT", name = "FLT", isMethod = true, returnsFloat = true,
                    defIndex = 2, position = "A.ERB:2",
                ),
                // 浮点方法 + #LOCALFSIZE 12：`12 1`。
                FuncMeta(
                    index = 2, key = "FLT_LOCAL", name = "FLT_LOCAL", isMethod = true,
                    returnsFloat = true, floatLocalSize = 12, defIndex = 3, position = "A.ERB:3",
                ),
            ),
        )
        val text = original.encodeText()
        assertTrue(
            text.contains("function 0 1 \"PLAIN\" \"PLAIN\" 0 0 0 0 0 \"A.ERB:1\"\n"),
            "非浮点行必须与 v2 相同（无可选原子）: $text",
        )
        assertTrue(text.contains("function 1 2 \"FLT\" \"FLT\" 2 0 0 0 0 0 1 \"A.ERB:2\"\n"), "成对原子 0 1: $text")
        assertTrue(
            text.contains("function 2 3 \"FLT_LOCAL\" \"FLT_LOCAL\" 2 0 0 0 0 12 1 \"A.ERB:3\"\n"),
            "floatLocalSize 与 returnsFloat 成对写: $text",
        )
        val decoded = BootMetadata.parse(text)
        assertEquals(original.functions, decoded.functions, "三行都必须逐字段往返")
        assertTrue(decoded.functions[1].returnsFloat && !decoded.functions[1].returnsStr)
        assertEquals(12, decoded.functions[2].floatLocalSize)
        assertTrue(decoded.functions[2].returnsFloat)
        assertEquals(text, decoded.encodeText(), "模型 → 文本 → 模型 → 文本 逐字节闭环")
    }

    @Test
    fun `v1v2 侧车无浮点原子 读回默认值`() {
        // 真 v2 行没有 function 可选原子：用只含普通函数的模型编码，再把版本头改成 2。
        val v2Model = BootMetadata(
            functions = listOf(
                FuncMeta(index = 0, key = "PLAIN", name = "PLAIN", defIndex = 1, position = "A.ERB:1"),
            ),
        )
        val v2 = v2Model.encodeText()
            .lineSequence()
            .map { if (it.startsWith("format-version ")) "format-version 2" else it }
            .joinToString("\n")
        val decoded = BootMetadata.parse(v2)
        assertEquals(v2Model.functions, decoded.functions, "v2 无该原子 ⇒ 两者默认值")
        assertTrue(decoded.functions.none { it.returnsFloat })
        assertEquals(0, decoded.functions.single().floatLocalSize)
    }

    @Test
    fun `v3 旧侧车单个裸整数仍按 floatLocalSize 读`() {
        // 旧 v3 写出端只产 floatLocalSize（没有 returnsFloat）；读侧必须兼容单原子形态。
        val oldV3 = BootMetadata(
            functions = listOf(
                FuncMeta(
                    index = 0, key = "BIG_LOCAL", name = "BIG_LOCAL", isMethod = true,
                    floatLocalSize = 16, defIndex = 1, position = "A.ERB:1",
                ),
            ),
        ).encodeText()
        assertTrue(
            oldV3.contains("function 0 1 \"BIG_LOCAL\" \"BIG_LOCAL\" 2 0 0 0 0 16 \"A.ERB:1\"\n"),
            "单原子形态: $oldV3",
        )
        val decoded = BootMetadata.parse(oldV3).functions.single()
        assertEquals(16, decoded.floatLocalSize, "单裸整数 = floatLocalSize")
        assertTrue(!decoded.returnsFloat, "只有一个裸整数时不得把 16 误读成 returnsFloat")
    }

    @Test
    fun `非方法带 returnsFloat 位被拒绝`() {
        // 写出侧不拦（FuncMeta 是公开构造面），读侧按原版语义严卡：MethodType 只存在于方法。
        val text = BootMetadata(
            functions = listOf(
                FuncMeta(index = 0, key = "NOT_METHOD", name = "NOT_METHOD", returnsFloat = true, position = "A.ERB:1"),
            ),
        ).encodeText()
        assertTrue(
            text.contains("function 0 0 \"NOT_METHOD\" \"NOT_METHOD\" 0 0 0 0 0 0 1 \"A.ERB:1\"\n"),
            "成对原子 0 1: $text",
        )
        val e = assertFailsWith<BootMetaFormatException> { BootMetadata.parse(text) }
        assertTrue(e.message!!.contains("returnsFloat"), "错误消息: ${e.message}")
    }
}
