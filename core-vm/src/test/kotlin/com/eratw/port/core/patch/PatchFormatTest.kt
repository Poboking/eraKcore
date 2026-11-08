package com.eratw.port.core.patch

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 补丁产物格式（`CONTRACT-R7-B` §2/L3，不变量 I3 / I5 / I8）。
 *
 * ## 夹具为什么手写头文本
 *
 * [GOOD_HEADER] 是**照着契约字段表逐行手写**的 16 行文本，不经 [PatchArtifacts.write]。
 * 这样每个用例都同时在验「读侧只认契约那一张表」：写侧和读侧共享同一个私有不变量时，
 * 那种假绿（写什么读什么、两边一起错）在这份夹具下过不去。`ErbcReader` 的
 * 「不依赖 `ErbcWriter`」也是同一条纪律。
 *
 * ## 负向用例是硬要求
 *
 * 每个闸都配一条「故意做错」的用例，并断言**错误文案可定位**（带行号或字段名）：
 * 只断言「抛了异常」等于没验闸 —— 抛错的地方可能是下游的数组越界，而不是这道闸。
 */
class PatchFormatTest {

    // ==================================================================
    // ① 写读往返逐字节（I8）
    // ==================================================================

    @Test
    fun `写读往返逐字节相同`() {
        val art = PatchArtifacts.read(rawFile())
        assertEquals(BODY.size, art.bodySize, "补丁体长度必须原样保留")
        assertContentEquals(BODY, art.bodyBytes(), "补丁体必须逐字节原样保留")

        val once = art.write()
        val twice = PatchArtifacts.read(once).write()
        assertContentEquals(once, twice, "write → read → write 必须逐字节相同（不是字段相等）")
    }

    @Test
    fun `写出来的字节与手写规范文本逐字节相同`() {
        // 写侧 = 契约那张表的唯一规范形：字段序、整数写法、引号串转义、LF、结尾 ---。
        val written = PatchArtifacts.of(header(), BODY).write()
        assertContentEquals(rawFile(), written, "写侧产物必须与手写的契约文本逐字节相同")
    }

    @Test
    fun `引号串转义写读往返保真`() {
        val tricky = "eratw \"quoted\" \\ back\nslash\ttab\rcr\u0001ctl 日本語"
        val art = PatchArtifacts.of(header(compiler = tricky), BODY)
        val text = art.header.encodeText()
        // 一条记录永远落在一行里：转义后不可能出现原始换行/制表符。
        assertEquals(17, text.lines().size, "16 行头 + 尾空行；转义漏了就会多出几行：$text")
        assertTrue(text.contains("\\n"), "换行必须转义：$text")
        assertTrue(text.contains("\\u0001"), "C0 必须转成 \\uXXXX：$text")
        assertFalse(text.contains("\t"), "制表符必须转义：$text")

        val back = PatchArtifacts.read(art.write())
        assertEquals(tricky, back.header.compiler, "转义必须严格互逆")
        assertContentEquals(art.write(), back.write(), "含转义的产物同样要逐字节往返")
    }

    @Test
    fun `补丁体按副本持有`() {
        val src = BODY.copyOf()
        val art = PatchArtifacts.of(header(), src)
        src[0] = 0
        assertEquals(BODY[0], art.bodyBytes()[0], "构造后改入参数组不许影响产物（否则 write 不再确定）")
    }

    // ==================================================================
    // ② 身份校验（I3）
    // ==================================================================

    @Test
    fun `base 身份取自 Program 的只读计数`() {
        // 夹具 Program：5 条指令 / 2 个函数 / 各池各 1 条。
        assertEquals(5, BASE.instructionCount, "base-instructions ← Program.instructionCount")
        assertEquals(2, BASE.functionCount, "base-functions ← Program.functions.size")
        assertEquals(1, BASE.statementCount, "base-statements ← Program.statementCount")
        assertEquals(1, BASE.stringCount, "base-strings ← Program.stringCount")
        assertEquals(1, BASE.longCount, "base-longs ← Program.constantCount")
        assertEquals(1, BASE.exprCount, "base-exprs ← Program.exprCount")
        assertEquals(SHA, BASE.sha256, "base-sha256 由调用方传入")
        assertTrue(isLowerHexSha256(SHA), "sha256Hex 必须产出 64 位小写十六进制：$SHA")
    }

    @Test
    fun `身份逐项相符时不抛`() {
        PatchArtifacts.read(rawFile()).checkAgainst(BASE)
    }

    @Test
    fun `七个 base 计数任一项不符都点名该项`() {
        val art = PatchArtifacts.read(rawFile())
        val cases = listOf(
            "base-instructions" to BASE.copy(instructionCount = BASE.instructionCount + 1),
            "base-functions" to BASE.copy(functionCount = BASE.functionCount + 1),
            "base-statements" to BASE.copy(statementCount = BASE.statementCount + 1),
            "base-strings" to BASE.copy(stringCount = BASE.stringCount + 1),
            "base-longs" to BASE.copy(longCount = BASE.longCount + 1),
            "base-exprs" to BASE.copy(exprCount = BASE.exprCount + 1),
        )
        for ((field, base) in cases) {
            val e = assertFailsWith<PatchIdentityMismatchException>("$field 不符必须拒绝装载") {
                art.checkAgainst(base)
            }
            assertEquals(1, e.mismatches.size, "$field：只该有一项不符，实际 ${e.mismatches}")
            assertEquals(field, e.mismatches[0].field, "必须点名 $field")
            val msg = e.message!!
            assertTrue(msg.contains(field), "文案必须点名 $field：$msg")
            assertTrue(msg.contains("期望 ${e.mismatches[0].expected}"), "文案必须给期望值：$msg")
            assertTrue(msg.contains("实为 ${e.mismatches[0].actual}"), "文案必须给实际值：$msg")
        }
    }

    @Test
    fun `sha 不符点名 base-sha256 并给期望与实际`() {
        val art = PatchArtifacts.read(rawFile())
        val wrong = "0".repeat(64)
        val e = assertFailsWith<PatchIdentityMismatchException>("sha 不符必须拒绝装载") {
            art.checkAgainst(BASE.copy(sha256 = wrong))
        }
        assertEquals(1, e.mismatches.size)
        assertEquals("base-sha256", e.mismatches[0].field)
        assertEquals(SHA, e.mismatches[0].expected, "期望侧必须是补丁头声明的 sha")
        assertEquals(wrong, e.mismatches[0].actual, "实际侧必须是 base 的 sha")
        val msg = e.message!!
        assertTrue(msg.contains("base-sha256"), "文案必须点名字段：$msg")
        assertTrue(msg.contains(SHA), "文案必须带补丁头的 sha：$msg")
        assertTrue(msg.contains(wrong), "文案必须带 base 的 sha：$msg")
    }

    @Test
    fun `多项不符一次报全且按头字段顺序`() {
        val art = PatchArtifacts.read(rawFile())
        val bad = BASE.copy(
            instructionCount = BASE.instructionCount + 1,
            stringCount = BASE.stringCount + 1,
            sha256 = "1".repeat(64),
        )
        val e = assertFailsWith<PatchIdentityMismatchException> { art.checkAgainst(bad) }
        assertEquals(
            listOf("base-instructions", "base-strings", "base-sha256"),
            e.mismatches.map { it.field },
            "必须一次报全，且按头字段顺序（不是 fail-fast 只报第一条）",
        )
    }

    // ==================================================================
    // ③ 全或无（I5）
    // ==================================================================

    @Test
    fun `尾段为空时抛错`() {
        val e = assertFailsWith<PatchFormatException>("--- 之后没有字节必须拒绝") {
            PatchArtifacts.read(rawFile(body = ByteArray(0)))
        }
        assertTrue(e.message!!.contains("补丁体"), "必须点名补丁体：${e.message}")
    }

    @Test
    fun `尾段被截断时抛错`() {
        // 30 字节：文件头 10 + 段表没读完 ⇒ 容器级判据当场拒绝。
        val e = assertFailsWith<PatchFormatException>("截断的补丁体必须拒绝") {
            PatchArtifacts.read(rawFile(body = BODY.copyOfRange(0, 30)))
        }
        assertTrue(e.message!!.contains("补丁体"), "必须点名补丁体：${e.message}")
        assertTrue(e.message!!.contains(".erbc"), "必须说清是 .erbc 容器不合格：${e.message}")
    }

    @Test
    fun `尾段不是 erbc 容器时抛错`() {
        val e = assertFailsWith<PatchFormatException> {
            PatchArtifacts.read(rawFile(body = "NOT-AN-ERBC".toByteArray()))
        }
        assertTrue(e.message!!.contains("magic"), "必须点名 magic：${e.message}")
    }

    @Test
    fun `写侧拒绝非法补丁体`() {
        val e = assertFailsWith<PatchFormatException>("写侧必须先把体校验过（I8 左移）") {
            PatchArtifacts.of(header(), "NOT-AN-ERBC".toByteArray())
        }
        assertTrue(e.message!!.contains("补丁体"), "必须点名补丁体：${e.message}")
        assertFailsWith<PatchFormatException>("空体同样拒绝") {
            PatchArtifacts.of(header(), ByteArray(0))
        }
    }

    // ==================================================================
    // ④ 负向：头字段
    // ==================================================================

    @Test
    fun `版本 3 直接拒绝不猜`() {
        val e = assertFailsWith<PatchFormatException>("更高版本必须直接拒绝") {
            PatchArtifacts.read(rawFile(headerText = goodHeaderText(version = 3)))
        }
        assertTrue(e.message!!.contains("版本 3"), "必须点名实际版本：${e.message}")
        assertTrue(e.message!!.contains("第 1 行"), "必须带行号：${e.message}")
    }

    @Test
    fun `版本 0 按非法版本拒绝`() {
        val e = assertFailsWith<PatchFormatException> {
            PatchArtifacts.read(rawFile(headerText = goodHeaderText(version = 0)))
        }
        assertTrue(e.message!!.contains("非法"), "必须说清是非法版本：${e.message}")
        assertTrue(e.message!!.contains("第 1 行"), "必须带行号：${e.message}")
    }

    @Test
    fun `末项字段缺失时点名缺哪一项`() {
        val e = assertFailsWith<PatchFormatException>("缺一即拒") {
            PatchArtifacts.read(rawFile(headerText = goodHeaderText().replace(ENTRY_LINE, "")))
        }
        assertTrue(e.message!!.contains("entry-function"), "必须点名缺的字段：${e.message}")
        assertTrue(e.message!!.contains("---"), "必须说清是结束标记提前出现：${e.message}")
        assertTrue(e.message!!.contains("第 15 行"), "必须带行号：${e.message}")
    }

    @Test
    fun `首字段缺失时点名缺哪一项`() {
        val e = assertFailsWith<PatchFormatException> {
            PatchArtifacts.read(rawFile(headerText = goodHeaderText().removePrefix("erbc-patch $PATCH_FORMAT_VERSION_2\n")))
        }
        assertTrue(e.message!!.contains("erbc-patch"), "必须点名缺失字段：${e.message}")
        // 按字段名取值后不再有「错位」形态：缺项在 --- 提前出现时一次点名（R7-E1）。
        assertTrue(e.message!!.contains("缺少"), "必须说清是缺项：${e.message}")
        assertTrue(e.message!!.contains("第 15 行"), "必须带 --- 的行号：${e.message}")
    }

    @Test
    fun `字段重复`() {
        val dup = goodHeaderText().replace(LONGS_LINE, LONGS_LINE + LONGS_LINE)
        val e = assertFailsWith<PatchFormatException>("重复字段必须拒绝") {
            PatchArtifacts.read(rawFile(headerText = dup))
        }
        assertTrue(e.message!!.contains("字段重复"), "必须说清是重复：${e.message}")
        assertTrue(e.message!!.contains("base-longs"), "必须点名字段：${e.message}")
        assertTrue(e.message!!.contains("首次出现在第 6 行"), "必须给首次出现的行号：${e.message}")
    }

    @Test
    fun `字段顺序无关——值跟字段名走`() {
        // R7-E1：读侧按字段名取值（跳过未知字段的前提）,「两行写反」不再可能静默错位
        // （值跟名走,不存在按下标错配）;乱序头读回的各字段值必须原样。
        val swapped = goodHeaderText().replace(
            STRINGS_LINE + LONGS_LINE,
            LONGS_LINE + STRINGS_LINE,
        )
        val art = PatchArtifacts.read(rawFile(headerText = swapped))
        assertEquals(BASE.stringCount, art.header.baseStringCount, "值必须跟着 base-strings 名走")
        assertEquals(BASE.longCount, art.header.baseLongCount, "值必须跟着 base-longs 名走")
        // 规范形写回：重编码按权威序输出（写侧恒产唯一规范形）。
        assertEquals(goodHeaderText(), art.header.encodeText(), "乱序头读回后重编码必须回到规范序")
    }

    @Test
    fun `未知字段跳过不影响其它字段`() {
        // R7-E1：未知名跳过（兼容方向 = 波 0 侧车可选段;见类 KDoc「可选段」）。
        // 跳过不许有副作用：已知字段的值必须原样,写回必须回到规范形（未知行不保留）。
        val extra = goodHeaderText().replace(SHA_LINE, "base-color 3\n" + SHA_LINE)
        val art = PatchArtifacts.read(rawFile(headerText = extra))
        assertEquals(SHA, art.header.baseSha256, "未知字段不许影响已知字段的值")
        assertEquals(BASE.instructionCount, art.header.baseInstructionCount)
        assertEquals(goodHeaderText(), art.header.encodeText(), "重编码必须回到规范形（未知行丢弃）")
    }

    @Test
    fun `尾部未知字段同样跳过`() {
        // 旧读侧的「多一即拒」按 R7-E1 放宽为跳过：entry-function 之后的未知行不拒。
        val extra = goodHeaderText().replace(ENTRY_LINE, ENTRY_LINE + "base-color 3\n")
        val art = PatchArtifacts.read(rawFile(headerText = extra))
        assertEquals("MAIN", art.header.entryFunction, "已知字段必须原样读回")
        assertEquals(goodHeaderText(), art.header.encodeText(), "重编码回到规范形（未知行丢弃）")
    }

    @Test
    fun `空行不许出现在头里`() {
        val bad = goodHeaderText().replace(SHA_LINE, "\n" + SHA_LINE)
        val e = assertFailsWith<PatchFormatException>("空行 = 字段缺失，必须报错而不是静默跳过") {
            PatchArtifacts.read(rawFile(headerText = bad))
        }
        assertTrue(e.message!!.contains("空行"), "必须说清是空行：${e.message}")
        assertTrue(e.message!!.contains("第 8 行"), "必须带行号：${e.message}")

    }

    @Test

    fun `--- 缺失`() {
        // 删掉 --- 后,补丁体（.erbc 字节）直接顶进字段区：要么撞上非 UTF-8 字节,
        // 要么读到文件尾,两条路都拒绝（都不再是「多一即拒」形态,R7-E1 按名找）。
        val noSep = goodHeaderText().removeSuffix("---\n")
        val e = assertFailsWith<PatchFormatException>("没有 --- 必须拒绝") {
            PatchArtifacts.read(rawFile(headerText = noSep))
        }
        assertTrue(e.message!!.contains("---"), "两种失败路的文案都必须点名结束标记：${e.message}")
    }

    @Test
    fun `负计数`() {
        val bad = goodHeaderText().replace(
            "base-instructions ${BASE.instructionCount}\n",
            "base-instructions -1\n",
        )
        val e = assertFailsWith<PatchFormatException>("负计数必须拒绝（它们最终都是数组长度）") {
            PatchArtifacts.read(rawFile(headerText = bad))
        }
        assertTrue(e.message!!.contains("base-instructions"), "必须点名字段：${e.message}")
        assertTrue(e.message!!.contains("不能为负"), "必须说清值域：${e.message}")
        assertTrue(e.message!!.contains("第 2 行"), "必须带行号：${e.message}")
    }

    @Test
    fun `非整数计数`() {
        val bad = goodHeaderText().replace("patch-exprs 6\n", "patch-exprs six\n")
        val e = assertFailsWith<PatchFormatException> {
            PatchArtifacts.read(rawFile(headerText = bad))
        }
        assertTrue(e.message!!.contains("patch-exprs"), "必须点名字段：${e.message}")
        assertTrue(e.message!!.contains("不是整数"), "必须说清类型：${e.message}")
        assertTrue(e.message!!.contains("第 14 行"), "必须带行号：${e.message}")
    }

    @Test
    fun `整数必须是规范十进制`() {
        for (bad in listOf("007", "+5", "-0")) {
            val text = goodHeaderText().replace("patch-longs 5\n", "patch-longs $bad\n")
            val e = assertFailsWith<PatchFormatException>("非规范整数 $bad 必须拒绝") {
                PatchArtifacts.read(rawFile(headerText = text))
            }
            assertTrue(e.message!!.contains("patch-longs"), "$bad：必须点名字段：${e.message}")
            assertTrue(e.message!!.contains("规范十进制"), "$bad：必须说清口径：${e.message}")
        }
    }

    @Test
    fun `sha256 形态非法`() {
        // 大写：写侧恒小写，读侧不认（否则同一份产物两种哈希形态）。
        val upper = goodHeaderText().replace(SHA_LINE, "base-sha256 \"${"A".repeat(64)}\"\n")
        val e1 = assertFailsWith<PatchFormatException> {
            PatchArtifacts.read(rawFile(headerText = upper))
        }
        assertTrue(e1.message!!.contains("base-sha256"), "必须点名字段：${e1.message}")
        assertTrue(e1.message!!.contains("64 位小写十六进制"), "必须说清形态：${e1.message}")
        assertTrue(e1.message!!.contains("第 8 行"), "必须带行号：${e1.message}")

        // 长度不对：同样的字段名 + 实际长度。
        val short = goodHeaderText().replace(SHA_LINE, "base-sha256 \"${"a".repeat(63)}\"\n")
        val e2 = assertFailsWith<PatchFormatException> {
            PatchArtifacts.read(rawFile(headerText = short))
        }
        assertTrue(e2.message!!.contains("63"), "必须给出实际长度：${e2.message}")
    }

    @Test
    fun `entry-function 必须大写归一`() {
        val lower = goodHeaderText().replace(ENTRY_LINE, "entry-function \"main\"\n")
        val e = assertFailsWith<PatchFormatException>("小写入口名必须拒绝，不许替调用方猜") {
            PatchArtifacts.read(rawFile(headerText = lower))
        }
        assertTrue(e.message!!.contains("entry-function"), "必须点名字段：${e.message}")
        assertTrue(e.message!!.contains("大写归一"), "必须说清口径：${e.message}")
        assertTrue(e.message!!.contains("第 15 行"), "必须带行号：${e.message}")
    }

    @Test
    fun `引号串字段没带引号`() {
        val bad = goodHeaderText().replace(COMPILER_LINE, "compiler eratw-android\n")
        val e = assertFailsWith<PatchFormatException> {
            PatchArtifacts.read(rawFile(headerText = bad))
        }
        assertTrue(e.message!!.contains("compiler"), "必须点名字段：${e.message}")
        assertTrue(e.message!!.contains("必须是引号串"), "必须说清形态：${e.message}")
        assertTrue(e.message!!.contains("第 9 行"), "必须带行号：${e.message}")
    }

    @Test
    fun `引号串后有多余内容`() {
        val bad = goodHeaderText().replace(COMPILER_LINE, "compiler \"x\" y\n")
        val e = assertFailsWith<PatchFormatException> {
            PatchArtifacts.read(rawFile(headerText = bad))
        }
        assertTrue(e.message!!.contains("多余内容"), "必须说清多余内容：${e.message}")
        assertTrue(e.message!!.contains("compiler"), "必须点名字段：${e.message}")
    }

    @Test
    fun `未知转义`() {
        val bad = goodHeaderText().replace(COMPILER_LINE, "compiler \"a\\qb\"\n")
        val e = assertFailsWith<PatchFormatException> {
            PatchArtifacts.read(rawFile(headerText = bad))
        }
        assertTrue(e.message!!.contains("未知转义"), "必须说清转义不合法：${e.message}")
        assertTrue(e.message!!.contains("\\q"), "必须点名那个转义：${e.message}")
    }

    @Test
    fun `引号串里出现未转义控制字符`() {
        // 写侧恒转义 C0；读侧接受它就等于允许「读得进、写出来不一样」（I8 破功）。
        val bad = goodHeaderText().replace(COMPILER_LINE, "compiler \"a\tb\"\n")
        val e = assertFailsWith<PatchFormatException> {
            PatchArtifacts.read(rawFile(headerText = bad))
        }
        assertTrue(e.message!!.contains("未转义的控制字符"), "必须说清形态：${e.message}")
        assertTrue(e.message!!.contains("第 9 行"), "必须带行号：${e.message}")
    }

    // ==================================================================
    // ④′ 可选段 private-dims（R7-E1）
    // ==================================================================

    @Test
    fun `private-dims 空表整段省略——头文本与 15 字段形逐字节相同`() {
        // 波 0 侧车可选段的同款纪律:不含本数据的模型,文本与引入本段之前逐字节相同
        //（I1 零行为差）。夹具「写出来的字节与手写规范文本逐字节相同」已钉 15 字段形,
        // 这里钉的是「带空段的头不追加任何行」。
        val text = header().encodeText()
        assertEquals(17, text.lines().size, "15 项字段 + --- + 尾空行,不许多出 private-dims 行：$text")
        assertFalse(text.contains("private-dims"), "空表不许写段名：$text")
    }

    @Test
    fun `private-dims 写读往返保真——标量数组默认值与奇字符`() {
        val dims = listOf(
            // 标量(#DIM PCOUNT → 1 维 1 元素;isStatic=true 是 vars.DimSpec 的构造默认,写侧如实编码)
            BootMetadata.PrivateDim("PATCH_0", dimSpec("PCOUNT", dims = intArrayOf(1))),
            // 数组(#DIM PTBL, 3) + 整型默认值
            BootMetadata.PrivateDim(
                "PATCH_0",
                dimSpec("PTBL", dims = intArrayOf(3), defaultsInt = longArrayOf(1, 2)),
            ),
            // 字符串标量 + 字符串默认值 + 关键字位全开
            BootMetadata.PrivateDim(
                "PATCH_1",
                dimSpec(
                    "NAME$", isStr = true, isConst = true, isRef = true, isGlobal = true,
                    isSavedata = true, isCharaData = true, isStatic = false, isPrivate = true,
                    defaultsStr = listOf("あ", "b\"c\\d"),
                ),
            ),
            // 浮点私有声明（4.981 的 #DIMF）+ 浮点初值：v2 记录形才装得下
            BootMetadata.PrivateDim(
                "PATCH_2",
                dimSpec("倍率", dims = intArrayOf(5), isFloat = true, defaultsFloat = 1.5),
            ),
            // 浮点声明但没写初值（defaultsFloat = null ⇒ 线格式写 `-`,读回必须是 null 而不是 0.0）
            BootMetadata.PrivateDim(
                "PATCH_2",
                dimSpec("比率", isFloat = true),
            ),
            // 奇字符:函数键/变量名带引号、反斜杠、换行、C0 —— 两层转义(记录→字段值)都要保真。
            BootMetadata.PrivateDim(
                "PA\"TCH\t2",
                dimSpec("A\nB\u0001C\\D"),
            ),
        )
        val art = PatchArtifacts.of(header(privateDims = dims), BODY)
        // 一条字段一行:转义后值里不可能出现原始换行(记录间的 LF 也转义成字面 \n)。
        val text = art.header.encodeText()
        assertEquals(18, text.lines().size, "15 项字段 + 1 行 private-dims + --- + 尾空行：$text")

        val back = PatchArtifacts.read(art.write()).header
        assertEquals(dims.size, back.privateDims.size, "条数保真")
        for ((i, expect) in dims.withIndex()) {
            val actual = back.privateDims[i]
            assertEquals(expect.functionKey, actual.functionKey, "第 $i 条函数键")
            assertEquals(expect.spec.name, actual.spec.name, "第 $i 条变量名")
            assertEquals(expect.spec.isStr, actual.spec.isStr, "第 $i 条 isStr")
            assertEquals(expect.spec.isConst, actual.spec.isConst, "第 $i 条 isConst")
            assertEquals(expect.spec.isRef, actual.spec.isRef, "第 $i 条 isRef")
            assertEquals(expect.spec.isGlobal, actual.spec.isGlobal, "第 $i 条 isGlobal")
            assertEquals(expect.spec.isSavedata, actual.spec.isSavedata, "第 $i 条 isSavedata")
            assertEquals(expect.spec.isCharaData, actual.spec.isCharaData, "第 $i 条 isCharaData")
            assertEquals(expect.spec.isStatic, actual.spec.isStatic, "第 $i 条 isStatic")
            assertEquals(expect.spec.isPrivate, actual.spec.isPrivate, "第 $i 条 isPrivate")
            assertContentEquals(expect.spec.dims, actual.spec.dims, "第 $i 条维度")
            if (expect.spec.defaultsInt == null) {
                assertEquals(null, actual.spec.defaultsInt, "第 $i 条整型默认值(null 保真)")
            } else {
                assertContentEquals(expect.spec.defaultsInt, actual.spec.defaultsInt, "第 $i 条整型默认值")
            }
            assertEquals(expect.spec.defaultsStr, actual.spec.defaultsStr, "第 $i 条字符串默认值")
            assertEquals(expect.spec.isFloat, actual.spec.isFloat, "第 $i 条 isFloat（4.981 的 #DIMF）")
            assertEquals(expect.spec.defaultsFloat, actual.spec.defaultsFloat, "第 $i 条浮点初值")
        }
        // I8:整份产物(含可选段)写读写逐字节相同。
        assertContentEquals(art.write(), PatchArtifacts.read(art.write()).write(), "含可选段的产物逐字节往返")
    }

    @Test
    fun `v1 旧产物的 private-dims 仍读得进——浮点位按假`() {
        // 装机后 mods/ 里的 .patch 跨 app 版本存活：升 v2 不许把老产物判成坏档（I5 会整体
        // 回退成「无补丁」，玩家看到的是口上突然不生效）。v1 记录里没有浮点原子 ⇒ 按
        // 整数声明读入（isFloat=false / defaultsFloat=null），其余字段逐条照旧。
        val v1Record = PrivateDimsCodec.encode(
            listOf(
                BootMetadata.PrivateDim(
                    "PATCH_0",
                    dimSpec("倍率", dims = intArrayOf(5), isFloat = true, defaultsFloat = 1.5),
                ),
            ),
            version = PATCH_FORMAT_VERSION_1,
        )
        assertEquals(13, Regex("\"[^\"]*\"|\\S+").findAll(v1Record).count(), "v1 记录 = 13 个记号（无 isFloat、无浮点初值）：$v1Record")
        val head = goodHeaderText(version = PATCH_FORMAT_VERSION_1).removeSuffix("---\n") +
            "private-dims ${quoteForFixture(v1Record)}\n" + "---\n"
        val back = PatchArtifacts.read(rawFile(headerText = head)).header
        assertEquals(PATCH_FORMAT_VERSION_1, back.formatVersion, "版本号如实读回（不静默归一）")
        val spec = back.privateDims.single().spec
        assertEquals("倍率", spec.name, "变量名保真")
        assertContentEquals(intArrayOf(5), spec.dims, "维度保真")
        assertFalse(spec.isFloat, "v1 没有这个原子 ⇒ 按整数声明读入")
        assertEquals(null, spec.defaultsFloat, "v1 无浮点初值")
    }

    @Test
    fun `v1 头带浮点私有声明在构造期拒绝`() {
        // 写侧闸（I5 前移）：v1 记录形装不下 isFloat —— 让它写出去等于把浮点位静默丢掉,
        // 读回来按整数声明注册（运行期恒 0）。所以拒,而不是省略。
        val e = assertFailsWith<IllegalArgumentException>("v1 + 浮点声明必须拒绝") {
            header(
                formatVersion = PATCH_FORMAT_VERSION_1,
                privateDims = listOf(BootMetadata.PrivateDim("PATCH_0", dimSpec("倍率", isFloat = true))),
            )
        }
        assertTrue(e.message!!.contains("装不下浮点声明"), "必须说清为什么：${e.message}")
    }

    @Test
    fun `private-dims 重复声明拒绝`() {
        val dims = listOf(
            BootMetadata.PrivateDim("PATCH_0", dimSpec("X")),
            BootMetadata.PrivateDim("PATCH_0", dimSpec("X")),
        )
        val e = assertFailsWith<IllegalArgumentException>("(函数键, 变量名) 重复必须拒绝") {
            header(privateDims = dims)
        }
        assertTrue(e.message!!.contains("声明重复"), "必须说清是重复：${e.message}")
        assertTrue(e.message!!.contains("PATCH_0/X"), "必须点名重复的声明：${e.message}")
    }

    @Test
    fun `private-dims 函数键必须大写归一`() {
        val e = assertFailsWith<IllegalArgumentException>("小写函数键落错函数域是静默错,必须拒") {
            header(privateDims = listOf(BootMetadata.PrivateDim("patch_0", dimSpec("X"))))
        }
        assertTrue(e.message!!.contains("大写归一"), "必须说清口径：${e.message}")
        assertTrue(e.message!!.contains("patch_0"), "必须点名实际值：${e.message}")
    }

    @Test
    fun `private-dims 记录畸形拒绝`() {
        // 手改产物可达的坏形态,逐个钉「可定位」:错误文案必须带 private-dims 与条目序号。
        // good 实形 = "PATCH_0" "PTBL" 0 0 0 0 0 0 1 0 3 - -（isStatic=true 是构造默认）。
        val good = PrivateDimsCodec.encode(
            listOf(BootMetadata.PrivateDim("PATCH_0", dimSpec("PTBL", dims = intArrayOf(3)))),
        )
        val bads = listOf(
            "isStr 位非 0/1" to good.replaceFirst(" \"PTBL\" 0 0", " \"PTBL\" X 0"),
            "维度表不是整数" to good.replace(" 3 - -", " x - -"),
            "维度表为负" to good.replace(" 3 - -", " -3 - -"),
            "整型默认值不是整数" to good.replace(" - -", " a -"),
            "字符串默认值括号未闭合" to good.replace(" - -", " - ( \"a\""),
            "记录有多余内容" to "$good extra",
        )
        for ((label, value) in bads) {
            // 可选段行必须落在 --- 之前（--- 之后是补丁体）;字段值整体引号由 quoteForFixture 负责。
            val head = goodHeaderText().removeSuffix("---\n") +
                "private-dims ${quoteForFixture(value)}\n" + "---\n"
            val e = assertFailsWith<PatchFormatException>("$label 必须拒绝") {
                PatchArtifacts.read(rawFile(headerText = head))
            }
            assertTrue(
                e.message!!.contains("private-dims"),
                "$label：文案必须点名可选段：${e.message}",
            )
        }
    }

    @Test
    fun `private-dims 字段值必须是引号串`() {
        val head = goodHeaderText().removeSuffix("---\n") + "private-dims 3\n" + "---\n"
        val e = assertFailsWith<PatchFormatException> {
            PatchArtifacts.read(rawFile(headerText = head))
        }
        assertTrue(e.message!!.contains("private-dims"), "必须点名字段：${e.message}")
        assertTrue(e.message!!.contains("必须是引号串"), "必须说清形态：${e.message}")
        assertTrue(e.message!!.contains("第 16 行"), "必须带行号：${e.message}")
    }

    @Test
    fun `未知字段与 private-dims 共存`() {
        // 兼容方向的正向钉:未知行跳过与可选段解析互不干扰(R7-E1 的两条裁定一起成立)。
        val dims = listOf(BootMetadata.PrivateDim("PATCH_0", dimSpec("PCOUNT")))
        val head = goodHeaderText()
            .replace(SHA_LINE, "base-color 3\n" + SHA_LINE)
            .replace(ENTRY_LINE, ENTRY_LINE + "legacy-flag 1\n")
            .removeSuffix("---\n") +
            "private-dims ${quoteForFixture(PrivateDimsCodec.encode(dims))}\n" + "---\n"
        val art = PatchArtifacts.read(rawFile(headerText = head))
        assertEquals(SHA, art.header.baseSha256, "未知字段不影响已知字段")
        assertEquals(1, art.header.privateDims.size, "可选段照常解析")
        assertEquals("PCOUNT", art.header.privateDims[0].spec.name)
    }

    @Test
    fun `private-dims 非空时产物字节随新段变化`() {
        // I3 判据③（契约 §4/E1）:新段进产物字节 ⇒ 产物的字节身份被新段覆盖 ——
        // 同 base 同补丁体,头带不带私有 #DIM,写出的 .patch 字节必然不同
        //（对产物做任何哈希/逐字节比对都能发现新段被篡改或丢失）。
        val with = PatchArtifacts.of(
            header(privateDims = listOf(BootMetadata.PrivateDim("PATCH_0", dimSpec("PCOUNT")))),
            BODY,
        ).write()
        val without = PatchArtifacts.of(header(), BODY).write()
        assertFalse(with.contentEquals(without), "可选段非空必须改变产物字节（新段进字节覆盖）")
    }

    // ==================================================================
    // ④' 可选段 function-metas（4.981 浮点族 per-function 元数据）
    // ==================================================================

    /** 两条记录的夹具：普通方法（#FUNCTIONS）+ 事件成员（Program 名带 # 绝对下标形）。 */
    private fun metaRecords(): List<FunctionMetaRecord> = listOf(
        FunctionMetaRecord(
            offset = 0,
            name = "PATCH_FN",
            isMethod = true,
            returnsStr = true,
            returnsFloat = false,
            floatLocalSize = 0,
            localSize = 2,
            localsSize = 0,
        ),
        FunctionMetaRecord(
            offset = 1,
            name = "EVENTFIRST#117062",
            isMethod = true,
            returnsStr = false,
            returnsFloat = true,
            floatLocalSize = 16,
            localSize = 0,
            localsSize = 0,
        ),
    )

    @Test
    fun `function-metas 空表整段省略——头文本与无段形逐字节相同`() {
        val withEmpty = header(functionMetas = emptyList()).encodeText()
        assertEquals(17, withEmpty.lines().size, "无段形 = 15 字段 + 一行 --- + 尾空行")
        assertFalse(withEmpty.contains("function-metas"), "空表不许写段名行")
        // 与引入本段前的旧模型字节一致：同参数构造（缺省空表）同字节。
        assertEquals(header().encodeText(), withEmpty, "缺省参数与显式空表必须同文本")
    }

    @Test
    fun `function-metas 写读往返逐字段保真`() {
        val records = metaRecords()
        val back = PatchArtifacts.read(
            rawFile(headerText = header(functionMetas = records).encodeText()),
        ).header.functionMetas
        assertEquals(records, back, "记录必须逐字段相等（含事件成员名的大小写与 # 下标形）")
    }

    @Test
    fun `function-metas 产物级写读往返逐字节相同`() {
        val once = PatchArtifacts.of(header(functionMetas = metaRecords()), BODY).write()
        val twice = PatchArtifacts.read(once).write()
        assertContentEquals(once, twice, "含 function-metas 的产物 write → read → write 逐字节相同（I8）")
    }

    @Test
    fun `function-metas 非空时产物字节随新段变化`() {
        val with = PatchArtifacts.of(header(functionMetas = metaRecords()), BODY).write()
        val without = PatchArtifacts.of(header(), BODY).write()
        assertFalse(with.contentEquals(without), "可选段非空必须改变产物字节")
    }

    @Test
    fun `function-metas 头构造期闸`() {
        // offset 越窗（夹具 patch-functions = 2）
        val e1 = assertFailsWith<IllegalArgumentException> {
            header(functionMetas = listOf(FunctionMetaRecord(2, "X", true, false, false, 0, 0, 0)))
        }
        assertTrue(e1.message!!.contains("offset 越出补丁函数窗口"), "越窗必须点名：${e1.message}")
        // returnsFloat 只允许方法位
        val e2 = assertFailsWith<IllegalArgumentException> {
            header(functionMetas = listOf(FunctionMetaRecord(0, "X", false, false, true, 0, 0, 0)))
        }
        assertTrue(e2.message!!.contains("returnsFloat 只允许出现在方法上"), "非方法浮点位必须拒：${e2.message}")
        // offset 重复
        val e3 = assertFailsWith<IllegalArgumentException> {
            header(
                functionMetas = listOf(
                    FunctionMetaRecord(0, "A", true, false, false, 0, 0, 0),
                    FunctionMetaRecord(0, "B", false, false, false, 0, 1, 0),
                ),
            )
        }
        assertTrue(e3.message!!.contains("offset 重复"), "撞键必须点名：${e3.message}")
        // 16 位域
        val e4 = assertFailsWith<IllegalArgumentException> {
            header(functionMetas = listOf(FunctionMetaRecord(0, "X", false, false, false, 0x10000, 0, 0)))
        }
        assertTrue(e4.message!!.contains("floatLocalSize 越 16 位域"), "超域必须点名：${e4.message}")
    }

    @Test
    fun `function-metas 记录畸形拒绝`() {
        // 记录实形 = 0 "PATCH_FN" 1 1 0 0 2 0（八原子）;逐个做坏并要求文案点名段与条目。
        val good = FunctionMetasCodec.encode(metaRecords())
        val first = good.substringBefore('\n')
        val bads = listOf(
            "offset 不是整数" to first.replaceFirst("0 \"PATCH_FN\"", "x \"PATCH_FN\""),
            "offset 为负" to first.replaceFirst("0 \"PATCH_FN\"", "-1 \"PATCH_FN\""),
            "函数名没带引号" to first.replaceFirst(" \"PATCH_FN\"", " PATCH_FN"),
            "isMethod 位非 0/1" to first.replaceFirst("\"PATCH_FN\" 1", "\"PATCH_FN\" X"),
            "记录有多余内容" to "$first extra",
        )
        for ((label, value) in bads) {
            val head = goodHeaderText().removeSuffix("---\n") +
                "function-metas ${quoteForFixture(value)}\n" + "---\n"
            val e = assertFailsWith<PatchFormatException>("$label 必须拒绝") {
                PatchArtifacts.read(rawFile(headerText = head))
            }
            assertTrue(
                e.message!!.contains("function-metas"),
                "$label：文案必须点名可选段：${e.message}",
            )
        }
    }

    @Test
    fun `function-metas 字段值必须是引号串`() {
        val head = goodHeaderText().removeSuffix("---\n") + "function-metas 3\n" + "---\n"
        val e = assertFailsWith<PatchFormatException> {
            PatchArtifacts.read(rawFile(headerText = head))
        }
        assertTrue(e.message!!.contains("function-metas"), "必须点名字段：${e.message}")
        assertTrue(e.message!!.contains("必须是引号串"), "必须说清形态：${e.message}")
    }

    @Test
    fun `未知字段与 function-metas 共存`() {
        // 兼容方向正向钉:未知行跳过 + 两个可选段并存（private-dims 与 function-metas）。
        val head = goodHeaderText()
            .replace(SHA_LINE, "base-color 3\n" + SHA_LINE)
            .removeSuffix("---\n") +
            "private-dims ${quoteForFixture(PrivateDimsCodec.encode(listOf(BootMetadata.PrivateDim("PATCH_0", dimSpec("PCOUNT")))))}\n" +
            "function-metas ${quoteForFixture(FunctionMetasCodec.encode(metaRecords()))}\n" + "---\n"
        val art = PatchArtifacts.read(rawFile(headerText = head))
        assertEquals(SHA, art.header.baseSha256, "未知字段不影响已知字段")
        assertEquals(1, art.header.privateDims.size, "private-dims 照常解析")
        assertEquals(2, art.header.functionMetas.size, "function-metas 照常解析")
        assertEquals(16, art.header.functionMetas[1].floatLocalSize)
    }

    // ==================================================================
    // ⑤ 确定性
    // ==================================================================

    @Test
    fun `同一份模型两次 write 逐字节相同`() {
        val a = PatchArtifacts.of(header(), BODY)
        val b = PatchArtifacts.of(header(), BODY)
        val first = a.write()
        val second = a.write()
        assertContentEquals(first, second, "同一个产物两次 write 必须相同")
        assertContentEquals(first, b.write(), "同内容的两个产物 write 必须相同")
    }

    // ==================================================================
    // 字段清单（供组长与 L5 的 specs/patch-format.yaml 逐字段对拍）
    // ==================================================================

    @Test
    fun `头字段顺序与契约字段表逐字一致`() {
        val expected = listOf(
            "erbc-patch",
            "base-instructions",
            "base-functions",
            "base-statements",
            "base-strings",
            "base-longs",
            "base-exprs",
            "base-sha256",
            "compiler",
            "patch-functions",
            "patch-statements",
            "patch-strings",
            "patch-longs",
            "patch-exprs",
            "entry-function",
        )
        assertEquals(expected, PatchHeader.FIELD_NAMES, "字段名与顺序必须与契约表逐字一致")

        val lines = header().encodeText().lines()
        assertEquals(17, lines.size, "15 项字段 + 一行 --- + 尾空行")
        assertEquals(expected, lines.subList(0, 15).map { it.substringBefore(' ') }, "编码序必须等于字段表")
        assertEquals("---", lines[15], "第 16 行必须是头结束标记")
        assertEquals("", lines[16], "头必须以 LF 收尾（--- 之后紧跟 .erbc 字节）")
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private fun header(
        base: PatchBaseIdentity = BASE,
        compiler: String = PatchArtifacts.compilerSummary(),
        entryFunction: String = "main",
        privateDims: List<BootMetadata.PrivateDim> = emptyList(),
        functionMetas: List<FunctionMetaRecord> = emptyList(),
        formatVersion: Int = PATCH_FORMAT_VERSION_2,
    ): PatchHeader = PatchHeader(
        baseInstructionCount = base.instructionCount,
        baseFunctionCount = base.functionCount,
        baseStatementCount = base.statementCount,
        baseStringCount = base.stringCount,
        baseLongCount = base.longCount,
        baseExprCount = base.exprCount,
        baseSha256 = base.sha256,
        compiler = compiler,
        patchFunctionCount = 2,
        patchStatementCount = 3,
        patchStringCount = 4,
        patchLongCount = 5,
        patchExprCount = 6,
        entryFunction = entryFunction,
        privateDims = privateDims,
        functionMetas = functionMetas,
        formatVersion = formatVersion,
    )

    /**
     * [com.eratw.port.core.vars.DimSpec] 夹具（默认值镜像该类的构造默认 —— isStatic = true、
     * 其余位 false;`#DIM` 无尺寸声明归一 1 维 1 元素）。
     */
    private fun dimSpec(
        name: String,
        isStr: Boolean = false,
        dims: IntArray = intArrayOf(1),
        isConst: Boolean = false,
        isRef: Boolean = false,
        isGlobal: Boolean = false,
        isSavedata: Boolean = false,
        isCharaData: Boolean = false,
        isStatic: Boolean = true,
        isPrivate: Boolean = false,
        defaultsInt: LongArray? = null,
        defaultsStr: List<String>? = null,
        isFloat: Boolean = false,
        defaultsFloat: Double? = null,
    ): com.eratw.port.core.vars.DimSpec =
        com.eratw.port.core.vars.DimSpec(
            name = name,
            isStr = isStr,
            dims = dims,
            isConst = isConst,
            isRef = isRef,
            isGlobal = isGlobal,
            isSavedata = isSavedata,
            isCharaData = isCharaData,
            isStatic = isStatic,
            isPrivate = isPrivate,
            defaultsInt = defaultsInt,
            defaultsStr = defaultsStr,
            isFloat = isFloat,
            defaultsFloat = defaultsFloat,
        )

    /** 测试侧把任意字符串 quote 成头字段值（同写侧 [PatchHeaderCodec.quote] 的口径）。 */
    private fun quoteForFixture(s: String): String = PatchHeaderCodec.quote(s)

    /** 照契约字段表手写的 16 行头（不经写侧）。 */
    private fun goodHeaderText(
        base: PatchBaseIdentity = BASE,
        version: Int = PATCH_FORMAT_VERSION_2,
    ): String =
        "erbc-patch $version\n" +
            "base-instructions ${base.instructionCount}\n" +
            "base-functions ${base.functionCount}\n" +
            "base-statements ${base.statementCount}\n" +
            "base-strings ${base.stringCount}\n" +
            "base-longs ${base.longCount}\n" +
            "base-exprs ${base.exprCount}\n" +
            "base-sha256 \"${base.sha256}\"\n" +
            "compiler \"${PatchArtifacts.compilerSummary()}\"\n" +
            "patch-functions 2\n" +
            "patch-statements 3\n" +
            "patch-strings 4\n" +
            "patch-longs 5\n" +
            "patch-exprs 6\n" +
            "entry-function \"MAIN\"\n" +
            "---\n"

    private fun rawFile(
        headerText: String = goodHeaderText(),
        body: ByteArray = BODY,
    ): ByteArray = headerText.toByteArray(Charsets.UTF_8) + body

    private companion object {
        val PROGRAM: Program = sampleProgram()
        val BODY: ByteArray = ErbcWriter.encode(PROGRAM)
        val SHA: String = PatchArtifacts.sha256Hex(BODY)
        val BASE: PatchBaseSnapshot = PROGRAM.patchBaseIdentity(SHA)

        val STRINGS_LINE = "base-strings ${BASE.stringCount}\n"
        val LONGS_LINE = "base-longs ${BASE.longCount}\n"
        val SHA_LINE = "base-sha256 \"$SHA\"\n"
        val COMPILER_LINE = "compiler \"${PatchArtifacts.compilerSummary()}\"\n"
        val ENTRY_LINE = "entry-function \"MAIN\"\n"
    }
}

/** 一份最小但各池都非空的 Program：5 条指令 / 2 个函数 / 四个池各 1 条。 */
private fun sampleProgram(): Program {
    val b = CodeBuilder()
    b.declareFunction("MAIN", 0, 0, 2)
    val k = b.constant(42L)
    val s = b.string("こんにちは")
    val e = b.expr(IntLiteral(7L, 1, 1))
    val st = b.statement(
        InstructionStmt(FunctionCode.PRINT, VoidArgument(), line = 4, raw = "PRINT こんにちは"),
    )
    b.emit(Opcode._SET_INT, SlotAddr.local(0), k, 0, 1)
    b.emit(Opcode._SET_STR, SlotAddr.local(1), s, 0, 2)
    b.emit(Opcode._EVAL_INT, e, SlotAddr.local(0), 0, 3)
    b.emit(Opcode.PRINT, st, 0, SlotAddr.NONE, 4)
    b.emit(Opcode._HALT, 0, 0, 0, 5)
    b.declareFunction("SUB", b.here() - 1, 0, 1)
    return b.build(0)
}
