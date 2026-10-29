package com.eratw.port.core.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * deep-expr 深审回归：把「运算符类型矩阵 / 求值次序 / 关键字下标槽位 / 无参形态」四张表
 * 按 C# 权威逐格钉死。补既有 OperatorAuditTest / ExprAuditTest 里按用例散点覆盖、
 * 未成表的空档；不复制它们已有的断言。
 *
 * 语义权威（全部 C# 文件:行号）：
 * - `MinorShift.Emuera.Runtime.Script.Statements.Expression/OperatorMethodManager.cs`
 *   :813-848 ReduceBinaryTerm 三类分派（int-int 全表 / str-str 七条 / 仅 Mult 的混型）、
 *   :754-811 ReduceUnaryTerm / ReduceUnaryAfterTerm；
 * - `:22-24` PlusIntInt、`:55-58` MinusIntInt、`:69-72` MultIntInt —— arguments[0] 先求值；
 * - `:129-137` DivIntInt 与 `:148-156` ModIntInt —— **arguments[1] 先求值**（先判 0）；
 * - `:159-373` 六个比较族的参数次序同 arguments[0] 先；
 * - `:375-465` And/Or/Nand/Nor 的短路判据（C# `&&`/`||` 写法本身，见各方法体）；
 * - `VariableParser.cs:182-188` → 关键字下标包装 `index = 数组位置(0 基)`；
 *   `ConstantData.cs:914` `GetKeywordDictionary(..., index, ...)` 同口径；
 * - `VariableParser.cs:74-181` 下标省略规则（2D/3D 全省略 → VariableNoArgTerm；1D 全省略 → 下标 0）。
 */
class DeepExprMatrixTest {

    private fun call(name: String): List<ExprToken> = listOf(id(name), lp(), rp())

    private fun fnResolver(): IdentifierResolver =
        resolverWith().also {
            it.funcs["F1"] = OperandType.INT
            it.funcs["F2"] = OperandType.INT
        }

    // ------------------------------------------------------------------
    // 1. 二元运算符 × 操作数类型矩阵（21 个二元 op × 4 种类型对）
    // ------------------------------------------------------------------

    @Test
    fun `二元运算符类型矩阵`() {
        // 全 21 个二元运算符（OperatorCode.cs 的 binary 集合）。
        val allBinary = listOf(
            "+", "-", "*", "/", "%", "==", "!=", ">", "<", ">=", "<=",
            "&&", "||", "^^", "!&", "!|", "&", "|", "^", "<<", ">>",
        )
        // binaryStrStrDic（OperatorMethodManager.cs:742-748）只有这七条。
        val strStrAllowed = setOf("+", "==", "!=", ">", "<", ">=", "<=")
        // str+str 返回 STR；其余六条比较返回 INT（ReturnType typeof(long)，如 :181-182）。
        val strStrResultStr = setOf("+")

        for (o in allBinary) {
            // int × int：全 21 条注册在 binaryIntIntDic（:721-741）⇒ 必过，结果 INT。
            assertEquals(
                OperandType.INT,
                parseOrThrow(listOf(i(1), op(o), i(2))).operandType,
                "INT $o INT 的结果类型",
            )
            // str × str
            if (o in strStrAllowed) {
                assertEquals(
                    if (o in strStrResultStr) OperandType.STR else OperandType.INT,
                    parseOrThrow(listOf(s("a"), op(o), s("b"))).operandType,
                    "STR $o STR 的结果类型",
                )
            } else {
                assertFailsWith<RuntimeException>("STR $o STR 解析期应拒") {
                    parseOrThrow(listOf(s("a"), op(o), s("b")))
                }
            }
            // int × str 与 str × int：只有 Mult 有方法（:830-833）
            if (o == "*") {
                assertEquals(OperandType.STR, parseOrThrow(listOf(i(2), op(o), s("a"))).operandType, "INT * STR")
                assertEquals(OperandType.STR, parseOrThrow(listOf(s("a"), op(o), i(2))).operandType, "STR * INT")
            } else {
                assertFailsWith<RuntimeException>("INT $o STR 解析期应拒") {
                    parseOrThrow(listOf(i(2), op(o), s("a")))
                }
                assertFailsWith<RuntimeException>("STR $o INT 解析期应拒") {
                    parseOrThrow(listOf(s("a"), op(o), i(2)))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. 一元运算符 × 操作数类型矩阵
    // ------------------------------------------------------------------

    @Test
    fun `一元运算符类型矩阵`() {
        // 前置一元 dic 只登记 long 版（:713-718）：!, ~, - 对 INT 成立、对 STR 一律 :783-784 拒。
        for (o in listOf("!", "~", "-")) {
            assertEquals(OperandType.INT, parseOrThrow(listOf(op(o), i(5))).operandType, "$o INT")
            assertFailsWith<RuntimeException>("$o STR 应拒") { parseOrThrow(listOf(op(o), s("a"))) }
        }
        // 一元 + 只对 INT，且直接返回操作数本体（:763-766），不建 Unary 节点。
        assertTrue(parseOrThrow(listOf(op("+"), i(5))) is IntLiteral, "一元 + 不建节点")
        assertFailsWith<RuntimeException>("一元 + STR 应拒") { parseOrThrow(listOf(op("+"), s("a"))) }

        // ++/-- 只接受 INT 且必须是变量（:757-760 的类型/变量检查 + :763 unaryAfterDic 只在 long）。
        val rv = resolverWith(var0D("V", OperandType.INT), var0D("SV", OperandType.STR))
        assertTrue(parseOrThrow(listOf(op("++"), id("V")), rv) is Unary, "前置 ++ 变量")
        assertTrue(parseOrThrow(listOf(id("V"), op("--")), rv) is UnaryAfter, "后置 -- 变量")
        assertFailsWith<RuntimeException>("++ 非变量应拒") { parseOrThrow(listOf(op("++"), i(5))) }
        assertFailsWith<RuntimeException>("++ 字符串变量应拒") { parseOrThrow(listOf(op("++"), id("SV")), rv) }
        assertFailsWith<RuntimeException>("\$SV ++ 应拒") { parseOrThrow(listOf(id("SV"), op("++")), rv) }
        // 常量变量（IsConst）在解析期即拒（:757-759 IncrementConst）
        val cv = resolverWith(VarInfo("C", OperandType.INT, 0, isCharacterData = false, isConst = true))
        assertFailsWith<RuntimeException>("++ 常量应拒") { parseOrThrow(listOf(op("++"), id("C")), cv) }
    }

    // ------------------------------------------------------------------
    // 3. 二元参数求值次序（唯一例外：Div / Mod 先右后左）
    // ------------------------------------------------------------------

    @Test
    fun `二元参数求值次序`() {
        fun run(o: String, f1val: Long): List<String> {
            val ctx = TestContext().apply {
                funcs["F1"] = { Value.IntV(f1val) }
                funcs["F2"] = { Value.IntV(2L) }
            }
            evalInt(parseOrThrow(call("F1") + op(o) + call("F2"), fnResolver()), ctx)
            return ctx.funcCalls
        }

        // 不短路/无次序例外者：左先右后。左取非零以免 && / !& 提前返回。
        for (o in listOf("+", "-", "*", "==", "!=", ">", "<", ">=", "<=", "^^", "&", "|", "^", "<<", ">>", "&&", "!&")) {
            assertEquals(listOf("F1()", "F2()"), run(o, 6L), "$o 次序")
        }
        // || / !|：左非零即返回 ⇒ 要观测右侧须让左为零（仍是「先左后右」）。
        assertEquals(listOf("F1()", "F2()"), run("||", 0L), "|| 次序")
        assertEquals(listOf("F1()", "F2()"), run("!|", 0L), "!| 次序")

        // Div / Mod 是全族唯一例外：先求 arguments[1] 判 0 再求 arguments[0]（:131/:136、:150/:155）。
        for (o in listOf("/", "%")) {
            assertEquals(listOf("F2()", "F1()"), run(o, 6L), "$o 次序（先右后左）")
        }
    }

    // ------------------------------------------------------------------
    // 4. 关键字下标的槽位口径（0 基，直接喂 reader）
    // ------------------------------------------------------------------

    @Test
    fun `关键字下标槽位按0基传给reader`() {
        // TALENT:氷精 → 角色 1D 省略角色位 → [TARGET, KeywordIndexArg(slot=1)]
        // spec: VariableParser.cs:94-103（补 TARGET）+ :182-188（按数组位置 i 包装）
        val resolver = resolverWith(VarInfo("TALENT", OperandType.INT, 1, isCharacterData = true))
        val ref = parseOrThrow(listOf(id("TALENT"), colon(), id("氷精")), resolver) as VarRef
        assertEquals(2, ref.indices.size)
        val arg = ref.indices[1] as KeywordIndexArg
        assertEquals("TALENT", arg.varName)
        assertEquals(1, arg.dim, "槽位是 0 基的数组位置（角色位 0 之后的第 1 槽）")

        val ctx = TestContext()
        ctx.variables.ints["TARGET:0"] = 3L
        ctx.variables.ints["TALENT:3:5"] = 77L
        ctx.variables.keywords[Triple("TALENT", 1, "氷精")] = 5L
        // reader 必须收到 dim == 1（不是 2），否则关键字表查不到。
        assertEquals(77L, evalInt(ref, ctx))
    }

    // ------------------------------------------------------------------
    // 5. 下标全省略的两态：2D/3D → 无参形态；1D → 补 0
    // ------------------------------------------------------------------

    @Test
    fun `下标全省略两态`() {
        val resolver = resolverWith(
            VarInfo("DA", OperandType.INT, 2, isCharacterData = false),
            VarInfo("TA", OperandType.INT, 3, isCharacterData = false),
            VarInfo("GEN", OperandType.INT, 1, isCharacterData = false),
        )
        // spec: VariableParser.cs:128-131（3D）、:138-143（2D）→ VariableNoArgTerm
        for (n in listOf("DA", "TA")) {
            val r = parseOrThrow(listOf(id(n)), resolver) as VarRef
            assertTrue(r.isNoArg, "$n 全省略应为无参形态")
            assertTrue(r.indices.isEmpty(), "$n 无参形态下标为空")
        }
        // spec: VariableParser.cs:154-173（1D）→ ZeroTerm，不是无参形态
        val g = parseOrThrow(listOf(id("GEN")), resolver) as VarRef
        assertTrue(!g.isNoArg, "1D 全省略不是无参形态")
        assertEquals(1, g.indices.size)
        assertEquals(0L, (g.indices[0] as IntLiteral).value, "1D 省略补下标 0")
    }

    // ------------------------------------------------------------------
    // 6. 码页配置值的整数段：收严到 ASCII 十进制（Enum.TryParse 口径）
    // ------------------------------------------------------------------

    @Test
    fun `码页配置整数段只认ASCII十进制`() {
        val key = "DEFAULT ANSI ENCODING"
        // enum 值顺序 = UseLanguage.cs（JAPANESE=0 / KOREAN=1 / CHINESE_HANS=2 / CHINESE_HANT=3）
        assertEquals(LangCodec.JAPANESE, LangCodec.fromConfig(mapOf(key to "0")))
        assertEquals(LangCodec.CHINESE_HANS, LangCodec.fromConfig(mapOf(key to "2")))
        assertEquals(LangCodec.CHINESE_HANS, LangCodec.fromConfig(mapOf(key to " 2 ")), "trim 后再判")
        assertEquals(LangCodec.CHINESE_HANS, LangCodec.fromConfig(mapOf(key to "+2")), "可带前导符号")
        // 全角数字：.NET 的 Int32 解析拒不接受 ⇒ 原版 ConfigWarn 保留默认；
        // Kotlin 的 toIntOrNull 经 Character.digit 本会解成 2 —— 必须挡掉。
        assertEquals(LangCodec.DEFAULT, LangCodec.fromConfig(mapOf(key to "２")))
        assertEquals(LangCodec.DEFAULT, LangCodec.fromConfig(mapOf(key to "０２")))
        // 越界数字（原版会 NRE）与写歪：本层一律回落 DEFAULT（已登记 TODO(verify)）
        assertEquals(LangCodec.DEFAULT, LangCodec.fromConfig(mapOf(key to "9")))
        assertEquals(LangCodec.DEFAULT, LangCodec.fromConfig(mapOf(key to "1.0")))
    }
}