package com.eratw.port.core.err

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 运算符层文案钉（2026-11-04 lane errtext）—— `core/err/OperatorErrorText.kt` 的真源逐字对账。
 *
 * ## 为什么单独一个文件
 * `ErrorTextRenderTest`（core/lexer）对的是 `specs/error-texts.yaml` ↔ `LangKey`，
 * 那套表只覆盖**词法 / ArgumentBuilder / 块结构**三族；运算符与变量下标这两族是
 * **运行期 CodeEE**，语义规约里另成一篇，所以按 `RandArgMessageTest` 的先例
 * 「成品串 + 官方 `文件:行号`」就地钉死，不另立第二套对账机制。
 *
 * ## 语言口径（specs/error-texts.yaml 头部注释）
 * 运行期 CodeEE 是玩家屏上的报错 ⇒ 取 **zhs 译文**；照抄纪律 = 只做按序号原位替换，
 * 不 trim、不改引号形态、不修误译（ADR-005）。
 *
 * ## 两份官方表（本族的三条串在两表**同文**，所以不受「表间口径之争」影响）
 * | 键 | 日文 `Lang.cs` | 语料根 `eratw-画蛇添足版eratw4.980/lang/emuera.zhs.xml` | reference `MinorShift.Emuera.Properties.lang.emuera-zhs.xml` | 原版抛出点（本机可核） |
 * |---|---|---|---|---|
 * | DivideByZero | :1326 | :236 | :286 | OperatorMethodManager.cs:134（DivIntInt）/ :153（ModIntInt） |
 * | MultiplyNegativeToStr | :1318 | :486 | :284 | OperatorMethodManager.cs:99（MultStrInt） |
 * | Multiply10kToStr | :1322 | :485 | :285 | OperatorMethodManager.cs:103（MultStrInt） |
 *
 * 上报取证（`<work-root>/.tmp/report-20261104/raw-500`，`信息：` 行）：
 * 「除数为 0」4 份（0.2.0×1 / 0.2.34×1 / 0.2.35×2）、
 * 「重复次数(10005)不能大于等于 10000」2 份（0.2.37）。
 */
class OperatorErrorTextTest {

    // ------------------------------------------------------------------
    // 一、成品串逐字 = 官方
    // ------------------------------------------------------------------

    @Test
    fun `除零文案逐字等于官方 DivideByZero`() {
        assertEquals("除数为0的除法运算", DIVIDE_BY_ZERO_TEXT)
        // 官方 0 占位符（OperatorMethodManager.cs:134 直接抛 `.Text`，没有 string.Format）
        assertFalse(DIVIDE_BY_ZERO_TEXT.contains('{'), "DivideByZero 不许有占位符")
        assertFalse(DIVIDE_BY_ZERO_TEXT.contains('}'), "DivideByZero 不许有占位符")
        // 照抄纪律：日文原文是「0による除算が行なわれました」（Lang.cs:1326），
        // 取 zhs 译文 ⇒ 屏上是中文；两条通道（Expr / Vm）共用这同一份常量。
        assertFalse(DIVIDE_BY_ZERO_TEXT == "除数为 0", "退役的自造串（移植侧 0.2.0~0.2.35 上屏原文）不得回潮")
    }

    @Test
    fun `字符串乘法两条模板逐字等于官方且只有一个 0 占位符`() {
        assertEquals("字符串无法与负值({0})进行相乘运算", MULTIPLY_NEGATIVE_TO_STR_TEMPLATE)
        assertEquals("字符串无法与超过10000的值({0})进行相乘运算", MULTIPLY_10K_TO_STR_TEMPLATE)
        for (t in listOf(MULTIPLY_NEGATIVE_TO_STR_TEMPLATE, MULTIPLY_10K_TO_STR_TEMPLATE)) {
            assertEquals(1, Regex("\\{0}").findAll(t).count(), "官方模板只有 {0}：$t")
            assertEquals(0, Regex("\\{1}").findAll(t).count(), "不许出现 {1}：$t")
            assertTrue(t.contains("({0})"), "括号是**半角**且紧贴占位符（照抄，不许加空格）：$t")
        }
    }

    @Test
    fun `原位替换只做序号展开不加工数字与标点`() {
        // 负数支（MultStrInt 的 intValue < 0，OperatorMethodManager.cs:99 喂 intValue.ToString()）
        assertEquals(
            "字符串无法与负值(-16)进行相乘运算",
            renderMultiplyToStrLimit(MULTIPLY_NEGATIVE_TO_STR_TEMPLATE, -16L),
            "真机取证形态：上报里的 0.2.35 负数支",
        )
        // 10000 及以上支（:103）；上报原文读数 10005
        assertEquals(
            "字符串无法与超过10000的值(10005)进行相乘运算",
            renderMultiplyToStrLimit(MULTIPLY_10K_TO_STR_TEMPLATE, 10005L),
        )
        // 边界：官方判式是 `>= 10000`，10000 本身走这一支
        assertEquals(
            "字符串无法与超过10000的值(10000)进行相乘运算",
            renderMultiplyToStrLimit(MULTIPLY_10K_TO_STR_TEMPLATE, 10000L),
        )
        // 数字原样回填：不分组、不加千位分隔、不 trim
        assertFalse(
            renderMultiplyToStrLimit(MULTIPLY_10K_TO_STR_TEMPLATE, 12345678L).contains(','),
            "不许出现千位分隔符",
        )
    }

    // ------------------------------------------------------------------
    // 二、两条通道必须共用同一份真源（结构性钉）
    // ------------------------------------------------------------------

    /**
     * 同一运算符错误有**两条通道**：表达式求值 `core/expr/Expr.kt` 与字节码
     * `core/code/Vm.kt` 的 `_STK_*` / 寄存器支。两路各自抄一份字符串，就等于给
     * 「某一路被顺手改对、另一路仍是旧串」留口子（`ProcessRuntime.intToStrArgValue`
     * 当年就是因为这个收成单一真源的）。这里用源码级断言钉住「两路都取常量」。
     */
    @Test
    fun `表达式与字节码两条通道都引用同一份文案常量`() {
        val expr = readMainSource("expr/Expr.kt")
        val vm = readMainSource("code/Vm.kt")
        for ((name, src) in listOf("Expr.kt" to expr, "Vm.kt" to vm)) {
            assertTrue(src.contains("DIVIDE_BY_ZERO_TEXT"), "$name 必须引用 DIVIDE_BY_ZERO_TEXT（单一真源）")
            assertTrue(src.contains("MULTIPLY_NEGATIVE_TO_STR_TEMPLATE"), "$name 必须引用负值乘字符串模板")
            assertTrue(src.contains("MULTIPLY_10K_TO_STR_TEMPLATE"), "$name 必须引用 10000 上限模板")
        }
    }

    /**
     * 退役串不许在产品源码的**代码行**里复活（KDoc/注释里作为历史取证出现是允许的，
     * 所以按行过滤掉以双斜线、星号、或块注释起始符开头的行）。
     *
     * 扫描范围 = 本波实际改过的抛出点（变量下标 + 运算符 + XML 三族），
     * 逐文件钉比全树扫更准：装载期 CSV 警告与角色表内部守卫至今仍用自造串
     * （偏差清单见交付报告），把它们一起扫进来只会红在自己的「待办」上。
     */
    @Test
    fun `退役的自造串不再出现在产品代码行`() {
        val retired = listOf(
            "除数为 0",
            "不能与字符串相乘",
            "不能大于等于 10000",
            "重复次数(",
            "维下标 ",
            "的下标 \$",
            "越界（目标长度",
            "越界（角色数",
            "是 0 维变量，不带下标",
            "未绑定目标（EmptyRefVar）",
        )
        val files = listOf(
            "code/Vm.kt",
            "expr/Expr.kt",
            "vars/VariableStore.kt",
            "vars/UserVariables.kt",
            "fn/XmlMethods.kt",
        )
        val hits = ArrayList<String>()
        for (rel in files) {
            val lines = readMainSource(rel).lines()
            lines.forEachIndexed { i, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                for (needle in retired) if (line.contains(needle)) hits += "$rel:${i + 1} 命中「$needle」"
            }
        }
        assertTrue(hits.isEmpty(), "以下代码行仍在抛退役串：\n${hits.joinToString("\n")}")
    }

    /** XML_GET 家族走官方 `XmlGetError`（有「的」）；非本家族仍是既有形态（两表不同文，待裁定）。 */
    @Test
    fun `XML 解析错误的前缀按官方键分流`() {
        assertEquals(
            "XML_GET函数:\"<a/>\"的解析错误:缺少根元素（位置 0）",
            com.eratw.port.core.fn.xmlParseErrorMessage("XML_GET", "<a/>", "缺少根元素（位置 0）"),
            "Error.XmlGetError 展开（Lang.cs:1330 / 语料根 emuera.zhs.xml:770 / reference 表 :287）",
        )
        assertEquals(
            "XML_SET函数:\"<a/>\"解析错误:缺少根元素（位置 0）",
            com.eratw.port.core.fn.xmlParseErrorMessage("XML_SET", "<a/>", "缺少根元素（位置 0）"),
            "非 XML_GET 支保留 reference 表形态（{0}函数:\"{1}\"解析错误:{2}），语料根表形不同文 ⇒ 交组长裁定",
        )
    }

    // ------------------------------------------------------------------
    // 三、源码定位（与 ErrorTextRenderTest 的 specs 取法同一套相对路径候选）
    // ------------------------------------------------------------------

    private fun mainCoreRoot(): File {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/core"),
            File("../core-vm/src/main/kotlin/com/eratw/port/core"),
            File("../../core-vm/src/main/kotlin/com/eratw/port/core"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 core-vm main 源码根（工作目录 ${File(".").absolutePath}）")
    }

    private fun readMainSource(relative: String): String {
        val f = File(mainCoreRoot(), relative)
        assertTrue(f.isFile, "缺文件 $f")
        return f.readText()
    }
}
