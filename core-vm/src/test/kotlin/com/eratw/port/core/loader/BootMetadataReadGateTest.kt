package com.eratw.port.core.loader

import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.proc.FuncMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CMP-82 读侧「绿着错」四通道里**尚未收口**的三条（另两条 `function index` 值域与
 * `str-constants` 计数闸已在 `BootMetadataHardeningTest` 钉住）：
 *
 * 1. **位/值校验三套口径**：私有 `#DIM` 的 8 个布尔位与 `dim-config` 走 `atomBool` 的
 *    `!= 0` 宽松口径（2、-1 都当 true）；表达式 `typeAt` 是「非 0 即 STR」、FORM 转换
 *    `alignAt` 是「非 '0' 即对齐」；`function flags` 对高位（>=128）与负值不判界。
 *    修法 = 读侧只认写出侧 `bit()` 会产出的形态（0/1；flags 0..127）。
 * 2. **同节键重复两种静默口径**：labels/files/datasec 是「后者覆盖」，function/global-dim
 *    是「照单追加」，另有 macro/const/str-const 与 private-dim 同样无声。修法 = 一律拒绝。
 * 3. **数值字段无值域闸**：`datasec` 下标 / `dims` 各项 / 四个函数计数位为负时，下游是
 *    数组长度或语句池下标（`NegativeArraySizeException` / 越界）。修法 = 只认非负。
 *
 * 合法的「REF 变量 dims 全 0」是既有形态，专门留一条回归钉住它不被新闸误伤。
 */
class BootMetadataReadGateTest {

    // ------------------------------------------------------------------
    // 最小侧车骨架：每个参数 = 「段计数 + 紧随其后的记录行」
    // ------------------------------------------------------------------

    private fun text(
        labels: String = "0",
        files: String = "0",
        dataSections: String = "0",
        functions: String = "0",
        globalDims: String = "0",
        privateDims: String = "0",
        dimConfig: String = "0",
        macros: String = "0",
        constants: String = "0",
        strConstants: String = "0",
    ): String = buildString {
        append("format-version 1\n")
        append("engine \"test\"\n")
        append("program-sha256 -\n")
        append("labels ").append(labels).append('\n')
        append("files ").append(files).append('\n')
        append("data-sections ").append(dataSections).append('\n')
        append("functions ").append(functions).append('\n')
        append("global-dims ").append(globalDims).append('\n')
        append("private-dims ").append(privateDims).append('\n')
        append("dim-config ").append(dimConfig).append('\n')
        append("macros ").append(macros).append('\n')
        append("constants ").append(constants).append('\n')
        append("str-constants ").append(strConstants).append('\n')
        append("end\n")
    }

    /**
     * 一条合法私有 `#DIM` 记录（8 个布尔位全 0/1）。
     *
     * 只返回记录本身；调用方自己写段计数（`privateDims = "1\n" + privateDim()`）。
     */
    private fun privateDim(
        flags: String = "0 0 0 0 0 0 0 1",
        dims: String = "2",
        defInt: String = "-",
        defStr: String = "-",
    ): String = "private-dim \"F\" \"V\" $flags $dims $defInt $defStr"

    /** 一条合法函数记录（flags / 四个计数位都可替换）。 */
    private fun function(
        index: Int = 0,
        key: String = "K",
        flags: Int = 0,
        argInt: Int = 0,
        argStr: Int = 0,
        localSize: Int = 0,
        localsSize: Int = 0,
    ): String = "function $index 0 \"$key\" \"$key\" $flags $argInt $argStr $localSize $localsSize \"p.ERB:1\""

    private fun fails(body: String, messagePart: String) {
        val e = assertFailsWith<BootMetaFormatException> {
            BootMetadata.parse(body)
        }
        assertTrue(
            (e.message ?: "").contains(messagePart),
            "拒绝消息应含「$messagePart」（实际：${e.message}）\n--- 侧车 ---\n$body",
        )
    }

    // ------------------------------------------------------------------
    // 骨架自检：最小合法侧车必须能读
    // ------------------------------------------------------------------

    @Test
    fun `最小合法侧车可读`() {
        val decoded = BootMetadata.parse(text())
        assertEquals(0, decoded.functions.size)
        assertEquals(0, decoded.privateDims.size)
    }

    // ------------------------------------------------------------------
    // 1. 位/值口径统一
    // ------------------------------------------------------------------

    @Test
    fun `私有 DIM 布尔位出现 2 被拒绝`() {
        fails(text(privateDims = "1\n" + privateDim(flags = "2 0 0 0 0 0 0 1")), "应为 0/1")
    }

    @Test
    fun `私有 DIM 布尔位出现 -1 被拒绝`() {
        fails(text(privateDims = "1\n" + privateDim(flags = "0 0 -1 0 0 0 0 1")), "应为 0/1")
    }

    @Test
    fun `dim-config 值 2 被拒绝`() {
        fails(text(dimConfig = "2"), "dim-config 应为 0/1")
    }

    @Test
    fun `function flags 高位 128 被拒绝而不是静默丢标志`() {
        fails(text(functions = "1\n" + function(flags = 128)), "function flags 越界")
    }

    @Test
    fun `function flags 负值被拒绝`() {
        fails(text(functions = "1\n" + function(flags = -1)), "function flags 越界")
    }

    @Test
    fun `表达式类型位出现 2 被拒绝`() {
        val dataSec = "1\ndatasec 0 (((var \"X\" - 2 1 0 1 1 ())))"
        fails(text(dataSections = dataSec), "类型位应为 0/1")
    }

    @Test
    fun `FORM 转换对齐位出现 2 被拒绝`() {
        val form = FormStringTerm(
            parts = listOf(
                FormPart.Embedded(IntLiteral(3L, 1, 1), FormConversion.Curly(IntLiteral(2L, 1, 2), alignLeft = true)),
            ),
            line = 1,
            col = 1,
        )
        val base = BootMetadata(dataSections = mapOf(0 to listOf(listOf(form)))).encodeText()
        assertTrue(base.contains("(curly (int 2 1 2) 1)"), "夹具自检: $base")
        fails(base.replaceFirst("(curly (int 2 1 2) 1)", "(curly (int 2 1 2) 2)"), "对齐位应为 0/1")
    }

    /** 正向回归：写出侧 `bit()` 的 0/1 形态必须照常可读（含带下标的 VarRef 类型位）。 */
    @Test
    fun `合法的 0 与 1 位照常可读`() {
        val meta = BootMetadata(
            dataSections = mapOf(
                0 to listOf(
                    listOf(
                        VarRef(
                            "X", listOf(IntLiteral(1L, 1, 1)),
                            isNoArg = false, operandType = com.eratw.port.core.expr.OperandType.INT,
                            line = 1, col = 1,
                        ),
                    ),
                ),
            ),
            privateDims = listOf(
                BootMetadata.PrivateDim(
                    "F",
                    com.eratw.port.core.vars.DimSpec("V", isStr = true, dims = intArrayOf(2), isConst = true),
                ),
            ),
            dimParseConfig = com.eratw.port.core.vars.DimParseConfig(systemSaveInBinary = true),
        )
        val decoded = BootMetadata.parse(meta.encodeText())
        assertEquals(meta.privateDims.size, decoded.privateDims.size)
        assertTrue(decoded.dimParseConfig.systemSaveInBinary, "dim-config 1 必须读成 true")
        assertEquals(meta.dataSections.keys, decoded.dataSections.keys)
    }

    // ------------------------------------------------------------------
    // 2. 同节键重复：一律拒绝
    // ------------------------------------------------------------------

    @Test
    fun `labelset 函数键重复被拒绝`() {
        fails(
            text(labels = "2\nlabelset \"F\" 1\nlabel \"A\" 1\nlabelset \"F\" 1\nlabel \"B\" 2"),
            "labelset 函数键重复",
        )
    }

    @Test
    fun `同段 label 名重复被拒绝`() {
        fails(text(labels = "1\nlabelset \"F\" 2\nlabel \"A\" 1\nlabel \"A\" 2"), "label 名重复")
    }

    @Test
    fun `file 函数键重复被拒绝`() {
        fails(text(files = "2\nfile \"F\" \"a.ERB\"\nfile \"F\" \"b.ERB\""), "file 函数键重复")
    }

    @Test
    fun `datasec 语句下标重复被拒绝`() {
        fails(
            text(dataSections = "2\ndatasec 5 (((int 1 1 1)))\ndatasec 5 (((int 2 1 1)))"),
            "datasec 语句下标重复",
        )
    }

    @Test
    fun `function index 重复被拒绝`() {
        fails(
            text(functions = "2\n" + function(index = 3, key = "A") + "\n" + function(index = 3, key = "B")),
            "function index 重复",
        )
    }

    /** 事件成员与合成入口**合法**共用事件名：不得按 key 判重。 */
    @Test
    fun `同事件名的多条 function 记录仍可读`() {
        val body = text(
            functions = "2\n" + function(index = 0, key = "EVENTFIRST") + "\n" +
                function(index = 1, key = "EVENTFIRST"),
        )
        assertEquals(2, BootMetadata.parse(body).functions.size, "事件名共用是既有合法形态")
    }

    @Test
    fun `global-dim 记录重复被拒绝`() {
        fails(
            text(
                globalDims = "2\nglobal-dim \"a.ERH\" 1 \"#DIM X, 3\"\nglobal-dim \"a.ERH\" 1 \"#DIM X, 3\"",
            ),
            "global-dim 记录重复",
        )
    }

    @Test
    fun `private-dim 声明重复被拒绝`() {
        fails(text(privateDims = "2\n" + privateDim() + "\n" + privateDim()), "private-dim 声明重复")
    }

    @Test
    fun `macro 名重复被拒绝`() {
        fails(text(macros = "2\nmacro \"A\" \"1\"\nmacro \"A\" \"2\""), "macro 名重复")
    }

    @Test
    fun `const 名重复被拒绝`() {
        fails(text(constants = "2\nconst \"A\" 1\nconst \"A\" 2"), "const 名重复")
    }

    @Test
    fun `str-const 名重复被拒绝`() {
        fails(text(strConstants = "2\nstr-const \"A\" \"1\"\nstr-const \"A\" \"2\""), "str-const 名重复")
    }

    // ------------------------------------------------------------------
    // 3. 数值字段值域
    // ------------------------------------------------------------------

    @Test
    fun `datasec 下标为负被拒绝`() {
        fails(text(dataSections = "1\ndatasec -1 (((int 1 1 1)))"), "datasec 语句下标为负")
    }

    @Test
    fun `dims 出现负长度被拒绝`() {
        fails(text(privateDims = "1\n" + privateDim(dims = "3,-1")), "dims 段出现负长度")
    }

    @Test
    fun `REF 变量 dims 全 0 仍可读`() {
        val decoded = BootMetadata.parse(text(privateDims = "1\n" + privateDim(dims = "0")))
        assertEquals(listOf(0), decoded.privateDims.single().spec.dims.toList(), "REF 的全 0 dims 是既有合法形态")
    }

    @Test
    fun `function 计数位为负被拒绝`() {
        fails(text(functions = "1\n" + function(argInt = -1)), "argIntCount 为负")
        fails(text(functions = "1\n" + function(argStr = -1)), "argStrCount 为负")
        fails(text(functions = "1\n" + function(localSize = -1)), "localSize 为负")
        fails(text(functions = "1\n" + function(localsSize = -1)), "localsSize 为负")
    }

    @Test
    fun `function defIndex 为负被拒绝`() {
        val body = text(functions = "1\nfunction 0 -1 \"K\" \"K\" 0 0 0 0 0 \"p.ERB:1\"")
        fails(body, "function defIndex 为负")
    }

    /** 正向回归：真实写出形态（全 0 的标志位 / 稀疏 index）必须照常读。 */
    @Test
    fun `真实写出形态照常读`() {
        val meta = BootMetadata(
            functions = listOf(
                FuncMeta(index = 0, key = "MAIN", name = "MAIN", position = "01.ERB:1"),
                FuncMeta(index = 10_000, key = "EVENTFIRST", name = "EVENTFIRST", isEvent = true, isPri = true),
            ),
        )
        val decoded = BootMetadata.parse(meta.encodeText())
        assertEquals(meta.functions, decoded.functions, "写出侧形态往返")
    }
}
