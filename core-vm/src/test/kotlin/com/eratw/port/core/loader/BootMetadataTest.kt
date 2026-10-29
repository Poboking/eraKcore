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
        val text = sample().encodeText().replaceFirst("format-version 1", "format-version 2")
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
}
