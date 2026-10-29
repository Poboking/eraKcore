package com.eratw.port.core.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 运算符语义逐条对拍（M4-N / n-expraudit）。
 *
 * 三段式证据链：原版 C# 文件:行号 → .NET 框架语义 → 移植侧读数。
 * 原版实现集中在 reference/emuera-cs/MinorShift.Emuera.Runtime.Script.Statements.Expression/
 * 目录的 OperatorMethodManager.cs（下称 OperatorMethodManager）、OperatorCode.cs、
 * OperatorManager.cs，以及 Parser 目录的 LexicalAnalyzer.cs。
 *
 * 分歧编号（详见交付报告）：
 * - EX-1 NAND/NOR 的短路：原版短路、移植侧两侧都求值。语料零真实用例 ⇒ 只钉现状、不改产品。
 * - EX-2 一元负的文案：移植侧自造文案，原版走 Lang.SystemLine.MinusWontWork。
 * - EX-3 一元负对操作数求值两次：原版 OperatorMethodManager.cs:561 与 :565 各一次，
 *   移植侧只求值一次 ⇒ 操作数含 RAND 时 RNG 取数次数不同。语料有用例
 *   （ERB/コマンド関連/COMF/日常系/COMF447 露店を開く.ERB:1305），修复点在
 *   core/expr/Expr.kt（非本路独占面）⇒ diff 写进「需组长接线」。
 *
 * 本文件不改任何产品代码：所有断言都锁「移植侧现读数」，与原版不一致处在注释里写明期望值。
 */
class OperatorAuditTest {

    private fun evi(
        tokens: List<ExprToken>,
        ctx: TestContext = TestContext(),
        resolver: IdentifierResolver? = null,
    ): Long = evalInt(parseOrThrow(tokens, resolver), ctx)

    private fun evs(
        tokens: List<ExprToken>,
        ctx: TestContext = TestContext(),
        resolver: IdentifierResolver? = null,
    ): String = evalStr(parseOrThrow(tokens, resolver), ctx)

    /** 带一个整数实参的函数调用 Token 序列（用于观测求值次数与顺序）。 */
    private fun call(name: String): List<ExprToken> = listOf(id(name), lp(), i(1), rp())

    private fun sideCtx(value: Long = 3L): TestContext =
        TestContext().apply { funcs["SIDE"] = { Value.IntV(value) } }

    // ------------------------------------------------------------------
    // 1. 位编码 / 优先级表：与 OperatorCode.cs 的十进制字面量逐条对齐
    // ------------------------------------------------------------------

    @Test
    fun `运算符位编码与原版枚举十进制值逐条一致`() {
        // spec: OperatorCode.cs:11-39（原版十进制字面量，移植侧十六进制，逐条对拍）
        assertEquals(196992, OperatorCode.PLUS.value)          // OperatorCode.cs:11
        assertEquals(197248, OperatorCode.MINUS.value)         // :12
        assertEquals(131984, OperatorCode.MULT.value)          // :13
        assertEquals(132240, OperatorCode.DIV.value)           // :14
        assertEquals(132496, OperatorCode.MOD.value)           // :15
        assertEquals(132704, OperatorCode.EQUAL.value)         // :16
        assertEquals(132965, OperatorCode.GREATER.value)       // :17
        assertEquals(133221, OperatorCode.LESS.value)          // :18
        assertEquals(133477, OperatorCode.GREATER_EQUAL.value) // :19
        assertEquals(133733, OperatorCode.LESS_EQUAL.value)    // :20
        assertEquals(133984, OperatorCode.NOT_EQUAL.value)     // :21
        assertEquals(598016, OperatorCode.INCREMENT.value)     // :22
        assertEquals(598272, OperatorCode.DECREMENT.value)     // :23
        assertEquals(134208, OperatorCode.AND.value)           // :24
        assertEquals(134464, OperatorCode.OR.value)            // :25
        assertEquals(136512, OperatorCode.XOR.value)           // :26
        assertEquals(136768, OperatorCode.NAND.value)          // :27
        assertEquals(137024, OperatorCode.NOR.value)           // :28
        assertEquals(134736, OperatorCode.BIT_AND.value)       // :29
        assertEquals(134992, OperatorCode.BIT_OR.value)        // :30
        assertEquals(135248, OperatorCode.BIT_XOR.value)       // :31
        assertEquals(69888, OperatorCode.NOT.value)            // :32
        assertEquals(70144, OperatorCode.BIT_NOT.value)        // :33
        assertEquals(136048, OperatorCode.RIGHT_SHIFT.value)   // :34
        assertEquals(136304, OperatorCode.LEFT_SHIFT.value)    // :35
        assertEquals(268293, OperatorCode.TERNARY_A.value)     // :36
        assertEquals(268560, OperatorCode.TERNARY_B.value)     // :37
        assertEquals(510, OperatorCode.ASSIGNMENT.value)       // :38
        assertEquals(766, OperatorCode.ASSIGNMENT_STR.value)   // :39
    }

    @Test
    fun `一元与二元标志位`() {
        // spec: OperatorManager.cs:131-149 以 0x10000 / 0x20000 / 0x40000 / 0x80000 判定
        assertEquals(true, OperatorCode.NOT.isUnary)               // 0x11100
        assertEquals(true, OperatorCode.BIT_NOT.isUnary)           // 0x11200
        assertEquals(true, OperatorCode.INCREMENT.isUnary)         // 0x92000
        assertEquals(true, OperatorCode.INCREMENT.isUnaryAfter)    // 0x92000 同时含 0x80000
        assertEquals(false, OperatorCode.INCREMENT.isBinary)
        assertEquals(true, OperatorCode.PLUS.isBinary)             // 0x30180 = UNARY|BINARY
        assertEquals(true, OperatorCode.PLUS.isUnary)
        assertEquals(true, OperatorCode.TERNARY_A.isTernary)       // 0x41805
        assertEquals(false, OperatorCode.BIT_AND.isUnary)
        assertEquals(false, OperatorCode.ASSIGNMENT.isBinary)      // 0x1FE 无二元标志位
    }

    @Test
    fun `优先级读数与移位低于加减`() {
        // spec: OperatorManager.cs:151-154（低 8 位即优先级）+ OperatorCode.cs:11/34/35
        assertEquals(128, OperatorCode.PLUS.priority)             // 196992 and 255
        assertEquals(128, OperatorCode.MINUS.priority)
        assertEquals(144, OperatorCode.MULT.priority)
        assertEquals(144, OperatorCode.DIV.priority)
        assertEquals(144, OperatorCode.MOD.priority)
        assertEquals(112, OperatorCode.LEFT_SHIFT.priority)       // M4-M 已钉：112
        assertEquals(112, OperatorCode.RIGHT_SHIFT.priority)
        assertEquals(96, OperatorCode.EQUAL.priority)
        assertEquals(96, OperatorCode.NOT_EQUAL.priority)
        assertEquals(101, OperatorCode.GREATER.priority)
        assertEquals(101, OperatorCode.LESS.priority)
        assertEquals(80, OperatorCode.BIT_AND.priority)
        assertEquals(80, OperatorCode.BIT_OR.priority)
        assertEquals(80, OperatorCode.BIT_XOR.priority)
        assertEquals(64, OperatorCode.AND.priority)
        assertEquals(64, OperatorCode.OR.priority)
        assertEquals(64, OperatorCode.XOR.priority)
        assertEquals(64, OperatorCode.NAND.priority)
        assertEquals(64, OperatorCode.NOR.priority)
        assertEquals(5, OperatorCode.TERNARY_A.priority)
        assertEquals(16, OperatorCode.TERNARY_B.priority)
        assertEquals(254, OperatorCode.ASSIGNMENT.priority)
        // 交叉验证 M4-M 钉桩（不推翻）：移位(112) < 加减(128) ⇒ 1<<2+3 折成 1<<(2+3)
        val e = parseOrThrow(listOf(i(1), op("<<"), i(2), op("+"), i(3))) as Binary
        assertEquals(OperatorCode.LEFT_SHIFT, e.op)
        assertEquals(OperatorCode.PLUS, (e.right as Binary).op)
        assertEquals(32L, evalInt(e))
        // 同理 1+2<<3 = (1+2)<<3 = 24；8>>1+1 = 8>>(1+1) = 2
        assertEquals(24L, evi(listOf(i(1), op("+"), i(2), op("<<"), i(3))))
        assertEquals(2L, evi(listOf(i(8), op(">>"), i(1), op("+"), i(1))))
        // 位与(80) < 比较(96) ⇒ 1&2==3 折成 1&(2==3)
        val f = parseOrThrow(listOf(i(1), op("&"), i(2), op("=="), i(3))) as Binary
        assertEquals(OperatorCode.BIT_AND, f.op)
        assertEquals(0L, evalInt(f))
    }

    // ------------------------------------------------------------------
    // 2. 整数除法与取模
    // ------------------------------------------------------------------

    @Test
    fun `整数除法向零截断与除零异常`() {
        // 原版：OperatorMethodManager.cs:121-137 DivIntInt —— 先取右操作数判 0（:131-135），
        //       再 arguments[0] / arguments[1]（:136）作用于 System.Int64。
        // .NET 框架语义：整数除法向零截断（ECMA-335 的 div 指令）⇒ 无行号，框架证据缺口。
        // 移植侧：Expr.kt:240-245（Kotlin Long 的 / 亦向零截断）。
        assertEquals(3L, evi(listOf(i(7), op("/"), i(2))))
        assertEquals(-3L, evi(listOf(i(-7), op("/"), i(2))))
        assertEquals(-3L, evi(listOf(i(7), op("/"), i(-2))))
        assertEquals(3L, evi(listOf(i(-7), op("/"), i(-2))))
        assertEquals(0L, evi(listOf(i(1), op("/"), i(2))))
        assertEquals(0L, evi(listOf(i(-1), op("/"), i(2))))
        // 除零：原版抛 CodeEE(Lang.Error.DivideByZero)（:132-135；文案 lang.emuera-zhs.xml:286）
        assertFailsWith<ExprEvalException> { evi(listOf(i(1), op("/"), i(0))) }
        // 商余恒等式（截断方向的间接证据；两边同构）：(-7/3)*3 + (-7%3) == -7
        assertEquals(-7L, evi(listOf(i(-7), op("/"), i(3), op("*"), i(3), op("+"), i(-7), op("%"), i(3))))
        // TODO(verify): 负商截断方向缺 C# 行号级证据，需 dotnet 复测（样本见交付）
    }

    @Test
    fun `取模符号跟随被除数与模零异常`() {
        // 原版：OperatorMethodManager.cs:140-156 ModIntInt —— 同样先判 0（:150-154），
        //       再 arguments[0] % arguments[1]（:155）。
        // .NET 框架语义：`%` 是余数（rem），符号跟随**被除数**，不是 floorMod ⇒ 无行号。
        // 移植侧：Expr.kt:240-245（Kotlin Long 的 % 同为 rem 语义）。
        assertEquals(1L, evi(listOf(i(7), op("%"), i(2))))
        assertEquals(-1L, evi(listOf(i(-7), op("%"), i(2))))
        assertEquals(1L, evi(listOf(i(7), op("%"), i(-2))))
        assertEquals(-1L, evi(listOf(i(-7), op("%"), i(-2))))
        assertEquals(0L, evi(listOf(i(4), op("%"), i(2))))
        assertFailsWith<ExprEvalException> { evi(listOf(i(1), op("%"), i(0))) }
        // TODO(verify): 负余数符号缺 C# 行号级证据，需 dotnet 复测
    }

    @Test
    fun `除与余先求右操作数再求左`() {
        // 原版：DivIntInt :131 先 arguments[1].GetIntValue，:136 才 arguments[0]；ModIntInt 同型。
        // 移植侧：Expr.kt:241-243 同序 ⇒ 除数为 0 时左操作数的副作用不发动。
        val ctx = sideCtx()
        assertFailsWith<ExprEvalException> {
            evalInt(parseOrThrow(call("SIDE") + op("/") + i(0)), ctx)
        }
        assertEquals(0, ctx.funcCalls.size)   // 左操作数（含副作用）未被求值
        val ctx2 = sideCtx()
        assertEquals(3L, evalInt(parseOrThrow(call("SIDE") + op("/") + i(1)), ctx2))
        assertEquals(1, ctx2.funcCalls.size)
        val ctx3 = sideCtx()
        assertFailsWith<ExprEvalException> {
            evalInt(parseOrThrow(call("SIDE") + op("%") + i(0)), ctx3)
        }
        assertEquals(0, ctx3.funcCalls.size)
    }

    // ------------------------------------------------------------------
    // 3. `&` 与 `|`：位运算还是逻辑运算
    // ------------------------------------------------------------------

    @Test
    fun `单与号是位运算`() {
        // 原版：OperatorManager.cs:61-68 把 "&" / "|" 映射到 BitAnd / BitOr；
        //       LexicalAnalyzer.cs:490-492 与 :483-485 亦按 `&&`→And、`&`→BitAnd 分叉。
        //       BitAndIntInt :467-479 / BitOrIntInt :481-493 直接是 & 与 | 位运算，
        //       注册处 OperatorMethodManager.cs:737-738。
        // 移植侧：LexicalAnalyzer.kt:219-220（同分叉）+ Expr.kt:264-265（l and r / l or r）。
        // ⇒ 契约里「:375-410 是逻辑与或」那一段对应 **&& / ||**（OperatorCode.And/Or），不是单与号。
        assertEquals(8L, evi(listOf(i(12), op("&"), i(10))))      // 1100 & 1010 = 1000
        assertEquals(14L, evi(listOf(i(12), op("|"), i(10))))      // 1100 | 1010 = 1110
        assertEquals(6L, evi(listOf(i(12), op("^"), i(10))))       // BitXorIntInt :495-507
        assertEquals(0L, evi(listOf(i(6), op("&"), i(8))))         // 若被当逻辑与会得 1 —— 这里是 0
        assertEquals(14L, evi(listOf(i(6), op("|"), i(8))))        // 若被当逻辑或会得 1
        assertEquals(-2L, evi(listOf(i(-2), op("&"), i(-1))))      // 负数照常按位，不是「非零即真」
        assertEquals(-1L, evi(listOf(i(-2), op("|"), i(-1))))
    }

    @Test
    fun `双与号是逻辑且返回0或1`() {
        // 原版：AndIntInt :375-391 / OrIntInt :393-409 —— 判 != 0，返回 1L 或 0L；注册 :732-733。
        // 移植侧：Expr.kt:234-238。
        assertEquals(1L, evi(listOf(i(12), op("&&"), i(10))))
        assertEquals(0L, evi(listOf(i(12), op("&&"), i(0))))
        assertEquals(0L, evi(listOf(i(0), op("&&"), i(12))))
        assertEquals(1L, evi(listOf(i(-5), op("&&"), i(7))))       // 非零即真，负数也算真
        assertEquals(1L, evi(listOf(i(12), op("||"), i(10))))
        assertEquals(0L, evi(listOf(i(0), op("||"), i(0))))
        assertEquals(1L, evi(listOf(i(0), op("||"), i(-1))))
        // XorIntInt :411-429（恰一个非零）/ NandIntInt :431-447 / NorIntInt :449-465
        // 组长重钉两条 worker 未跑的读数：`:423` 的条件是
        // `(a==0 && b!=0) || (a!=0 && b==0)` ⇒ 「两操作数是否恰有一个非零」，
        // 所以 `2 ^^ 0` = **1**（恰一个非零）、`0 ^^ 0` = **0**（两个都零 ⇒ 不是异或真）；
        // 移植侧 `Expr.kt` 的 `if ((l == 0L) != (r == 0L))` 与原版同式 ⇒ 原断言写反了两条。
        assertEquals(1L, evi(listOf(i(0), op("^^"), i(3))))
        assertEquals(0L, evi(listOf(i(2), op("^^"), i(3))))
        assertEquals(1L, evi(listOf(i(2), op("^^"), i(0))))
        assertEquals(0L, evi(listOf(i(0), op("^^"), i(0))))
        assertEquals(1L, evi(listOf(i(0), op("!&"), i(9))))
        assertEquals(0L, evi(listOf(i(2), op("!&"), i(9))))
        assertEquals(1L, evi(listOf(i(0), op("!|"), i(0))))
        assertEquals(0L, evi(listOf(i(2), op("!|"), i(0))))
    }

    @Test
    fun `双与双或短路不求值右操作数`() {
        // 原版：AndIntInt :385 写成 C# 的 `&&`、OrIntInt :403 写成 `||`
        //       ⇒ 短路求值，右操作数在结论已定时**完全不求值**（副作用与 RNG 取数都不发动）。
        //       语言层面的短路规则无 C# 行号 ⇒ 框架证据；源码侧证据是写法本身。
        // 移植侧：Expr.kt:234-238 显式短路 ⇒ 一致。语料有用例：
        //       ERB/ANOTHER_TALK.ERB:38（`... >= 2 && !RAND:3`）、COMMON_J.ERB:8 等大量 `&&` / `||`。
        val c1 = sideCtx()
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(0), op("&&")) + call("SIDE")), c1))
        assertEquals(0, c1.funcCalls.size)
        val c2 = sideCtx()
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(1), op("||")) + call("SIDE")), c2))
        assertEquals(0, c2.funcCalls.size)
        val c3 = sideCtx()
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(1), op("&&")) + call("SIDE")), c3))
        assertEquals(1, c3.funcCalls.size)
        val c4 = sideCtx()
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(0), op("||")) + call("SIDE")), c4))
        assertEquals(1, c4.funcCalls.size)
    }

    @Test
    fun `逻辑异或双尖号原版不短路移植侧也不短路`() {
        // 原版：XorIntInt :421-422 先把两个操作数取进局部变量 ⇒ 两侧必然求值。
        // 移植侧：Expr.kt:247-249 的 else 分支同样先取 l、r（:261 判 XOR）⇒ 一致。
        val ctx = sideCtx()
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(0), op("^^")) + call("SIDE")), ctx))
        assertEquals(1, ctx.funcCalls.size)
        val ctx2 = sideCtx()
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(1), op("^^")) + call("SIDE")), ctx2))
        assertEquals(1, ctx2.funcCalls.size)
    }

    @Test
    fun `NAND与NOR按原版短路`() {
        // EX-1（分歧）：原版 NandIntInt :441 写作 `if (arguments[0].GetIntValue(exm) == 0L || arguments[1]...)`
        //   —— 用的是 C# 的 `||` ⇒ a==0 时**不求值**右操作数；
        //   NorIntInt :459 写作 `if (arguments[0].GetIntValue(exm) == 0L && arguments[1]...)`
        //   —— 用的是 C# 的 `&&` ⇒ a!=0 时**不求值**右操作数。
        //   移植侧 Expr.kt:268-271 已按原版短路 ⇒ 本用例锁副作用次数 = 0。
        //   语料在用性：整个 ERB 子树里 `!&` / `!|` 只出现在字符串字面量内
        //   （ERB/口上・メッセージ関連/個人口上/014 Cirno [チルノ]/AA琪露诺/M_KOJO_K14_1_AA（实验性）.ERB:76、
        //     ERB/口上・メッセージ関連/個人口上/011 Marisa [魔理沙]/.../M_KOJO_K11_FUNC.ERB:2508），
        //   IF/SIF 条件式零真实用例 ⇒ 按契约「零用例只登记不修产品」。
        // EX-1 已采纳：NAND/NOR 两侧都按原版短路。
        val c1 = sideCtx()
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(0), op("!&")) + call("SIDE")), c1))
        assertEquals(0, c1.funcCalls.size)
        val c2 = sideCtx()
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(1), op("!|")) + call("SIDE")), c2))
        assertEquals(0, c2.funcCalls.size)
    }

    // ------------------------------------------------------------------
    // 4. 移位
    // ------------------------------------------------------------------

    @Test
    fun `移位量按低六位取模并先按int截断`() {
        // 原版：LeftShiftIntInt :523-534（:533 `arguments[0] << (int)arguments[1]`，注册 :741）、
        //       RightShiftIntInt :509-520（:519 同型，注册 :740）。
        //       源码可见的事实是 (int) 强制截断；「64 位操作数的移位量再按低 6 位取模」是
        //       C# 语言 / IL 规则 ⇒ 无行号，框架证据缺口。
        //       `>>` 作用于有符号 Int64 ⇒ 算术右移（保号），亦属框架规则。
        // 移植侧：Expr.kt:268-269 `l shr r.toInt()` / `l shl r.toInt()`（JVM 同规则，不作证据）。
        assertEquals(Long.MIN_VALUE, evi(listOf(i(1), op("<<"), i(63))))
        assertEquals(1L, evi(listOf(i(1), op("<<"), i(64))))           // 64 and 63 = 0
        assertEquals(2L, evi(listOf(i(1), op("<<"), i(65))))           // 65 and 63 = 1
        assertEquals(8589934592L, evi(listOf(i(1), op("<<"), i(33))))  // 若被误按 &31 会得 2
        assertEquals(Long.MIN_VALUE, evi(listOf(i(1), op("<<"), i(-1)))) // (int)-1 的低 6 位 = 63
        assertEquals(2L, evi(listOf(i(1), op("<<"), i(4294967297))))   // 2^32+1 → (int) 截断 = 1
        assertEquals(1L, evi(listOf(i(1), op(">>"), i(64))))
        assertEquals(0L, evi(listOf(i(1), op(">>"), i(63))))
        assertEquals(0L, evi(listOf(i(1), op(">>"), i(-1))))           // 等价于 >> 63
        assertEquals(-1L, evi(listOf(i(-1), op(">>"), i(63))))         // 算术右移保号
        assertEquals(-4L, evi(listOf(i(-8), op(">>"), i(1))))          // 不是逻辑右移（否则 9e18）
        assertEquals(-2L, evi(listOf(i(-8), op(">>"), i(2))))
        assertEquals(274877906944L, evi(listOf(i(1099511627776), op(">>"), i(2)))) // 2^40 >> 2
        // 组长重钉：移位优先级 112 低于加减 128（`OperatorCode.cs:34-35`，M4-M 已钉的同一口径）
        // ⇒ `1 << 2 + 1` 解析成 `1 << (2+1)` = **8**，不是 4。
        assertEquals(8L, evi(listOf(i(1), op("<<"), i(2), op("+"), i(1))))          // 1<<(2+1)
        // TODO(verify): 移位量取模口径与算术右移缺 C# 行号级证据，需 dotnet 复测（样本见交付）
    }

    @Test
    fun `语料移位用例的读数`() {
        // 语料点：ERB/イベント関連/贈り物関連/贈り物.ERB:651 `RETURNF (1 << MAXHOME) - 1`
        //         ERB/イベント関連/贈り物関連/贈り物.ERB:678 `RESULT ^= (1 << MAXHOME) - 1`
        //         ERB/イベント関連/守矢くじ.ERB:293 `FLAG:999 += 1 << 5`
        //         ERB/口上・メッセージ関連/個人口上/017 Alice [アリス]/アリス/追加ファイル/M_KOJO_K17_喘ぎ.ERB:978
        //           （`#DIM CONST` 折叠口径，见下方对账）
        //         ERB/DIM.ERH:284 `#DIM CONST MAXHOME = 12`
        // ⇒ 语料里的移位量都远小于 63（MAXHOME=12、常量 5、K17C 计数），
        //   取模口径零命中 ⇒ EX 只登记「框架证据缺口 + 零命中」，不动产品。
        val resolver = resolverWith(var0D("MAXHOME", OperandType.INT))
        val ctx = TestContext()
        ctx.variables.ints["MAXHOME"] = 12L
        assertEquals(
            4095L,
            evalInt(parseOrThrow(listOf(lp(), i(1), op("<<"), id("MAXHOME"), rp(), op("-"), i(1)), resolver), ctx),
        )
        assertEquals(32L, evi(listOf(i(1), op("<<"), i(5))))
    }

    // ------------------------------------------------------------------
    // 5. 字符串 `+` 与 `*`
    // ------------------------------------------------------------------

    @Test
    fun `字符串加号是拼接`() {
        // 原版：PlusStrStr :28-45（:43 两操作数 GetStrValue 后 `+` 串接），注册 :742；
        //       ReturnType typeof(string)（:33），操作数类型表 :34-38。
        // .NET 框架语义：string 的 + 为串接；null 视作空串。
        // 移植侧：Expr.kt:278。原版 VariableTerm 读 null 字符串时已归一为 ""
        //         （Eval.kt:18-19，spec VariableTerm.cs:91-96）⇒ null 分支不可达。
        assertEquals("abc", evs(listOf(s("ab"), op("+"), s("c"))))
        assertEquals("ab", evs(listOf(s("ab"), op("+"), s(""))))
        assertEquals("ab", evs(listOf(s(""), op("+"), s("ab"))))
        assertEquals("", evs(listOf(s(""), op("+"), s(""))))
        val resolver = resolverWith(var0D("S", OperandType.STR))
        val ctx = TestContext()
        ctx.variables.strs["S"] = "x"
        assertEquals("xy", evalStr(parseOrThrow(listOf(id("S"), op("+"), s("y")), resolver), ctx))
    }

    @Test
    fun `字符串与整数相乘是重复并有双重上界`() {
        // 原版：MultStrInt :75-118 —— :87 按左操作数类型决定取值顺序；
        //       :97-100 负数抛 CodeEE(MultiplyNegativeToStr)（文案 lang.emuera-zhs.xml:284）；
        //       :101-104 **达到 10000 即错**，抛 CodeEE(Multiply10kToStr)（文案 :285）；
        //       :105-108 空串或 0 次返回 ""；注册 :749，且 :830-833 允许 int*str 与 str*int 两个方向。
        // 移植侧：Expr.kt:280-293 同序同界（`>= 10000`）。
        assertEquals("ababab", evs(listOf(s("ab"), op("*"), i(3))))
        assertEquals("xyxy", evs(listOf(i(2), op("*"), s("xy"))))
        assertEquals("", evs(listOf(s("xy"), op("*"), i(0))))
        assertEquals("", evs(listOf(s(""), op("*"), i(5))))
        assertEquals(9999, evs(listOf(s("a"), op("*"), i(9999))).length)   // 上界为「达到即错」
        assertFailsWith<ExprEvalException> { evs(listOf(s("a"), op("*"), i(10000))) }
        assertFailsWith<ExprEvalException> { evs(listOf(s("a"), op("*"), i(-1))) }
    }

    @Test
    fun `字符串与数值的非法组合在解析期就拒`() {
        // 原版：ReduceBinaryTerm :813-848 —— 内/外混型只有 Mult 有方法（:830-833），
        //       其余落 :847 抛 CodeEE(CanNotAppliedBinaryOp)（文案 lang.emuera-zhs.xml:282）；
        //       字符串两侧只有 binaryStrStrDic 注册的 7 条可用（:742-748）。
        // 移植侧：Parser.kt:612-631 reduceBinaryTerm 同型分派。
        assertFailsWith<ExprEvalException> { evi(listOf(s("a"), op("&"), i(1))) }
        assertFailsWith<ExprEvalException> { evi(listOf(s("a"), op("&&"), s("b"))) }   // 逻辑与无字符串版
        assertFailsWith<ExprEvalException> { evi(listOf(i(1), op("+"), s("a"))) }      // int+str 无方法
        assertFailsWith<ExprEvalException> { evi(listOf(s("a"), op("^^"), s("b"))) }
        assertFailsWith<ExprEvalException> { evi(listOf(s("a"), op("<<"), i(1))) }     // 移位无字符串版
        // 合法者不受影响
        assertEquals(1L, evi(listOf(s("a"), op("=="), s("a"))))
    }

    // ------------------------------------------------------------------
    // 6. 比较运算符的返回值
    // ------------------------------------------------------------------

    @Test
    fun `数值比较返回0或1的整数`() {
        // 原版：GreaterIntInt :231-247、LessIntInt :267-283、GreaterEqualIntInt :303-319、
        //       LessEqualIntInt :339-355、EqualIntInt :159-175、NotEqualIntInt :195-211
        //       —— 全部 `return 1L` / `return 0L`，ReturnType 是 typeof(long)（如 :236）
        //       ⇒ 比较结果是 0/1 整数而不是布尔；注册 :726-731。
        // 移植侧：Expr.kt:254-259（同为 1L/0L）。
        assertEquals(1L, evi(listOf(i(3), op(">"), i(2))))
        assertEquals(0L, evi(listOf(i(2), op(">"), i(3))))
        assertEquals(0L, evi(listOf(i(3), op(">"), i(3))))
        assertEquals(1L, evi(listOf(i(-1), op("<"), i(0))))
        assertEquals(1L, evi(listOf(i(3), op(">="), i(3))))
        assertEquals(1L, evi(listOf(i(3), op("<="), i(3))))
        assertEquals(0L, evi(listOf(i(4), op("<="), i(3))))
        assertEquals(1L, evi(listOf(i(3), op("=="), i(3))))
        assertEquals(1L, evi(listOf(i(3), op("!="), i(4))))
        assertEquals(0L, evi(listOf(i(3), op("!="), i(3))))
        // 比较结果可继续参与算术（正因返回 long）：(3>2)+1 = 2
        assertEquals(2L, evi(listOf(lp(), i(3), op(">"), i(2), rp(), op("+"), i(1))))
        // 比较链在原版只是**告警**不是错误：ExpressionParser.cs:556-559（ComparisonOpContinuous，
        // 文案 lang.emuera-zhs.xml:260）⇒ 移植侧 ExprParser.warnings（Parser.kt:77-78）同口径。
        val p = parse(listOf(i(1), op("<"), i(2), op("<"), i(3)))
        assertEquals(1L, evalInt(p.first!!))
    }

    @Test
    fun `字符串比较按序号而非区域设置`() {
        // 原版：GreaterStrStr :249-265、LessStrStr :285-301 用
        //       string.Compare(a, b, StringComparison.Ordinal)（:259 / :295）；
        //       EqualStrStr :177-193、NotEqualStrStr :213-229 用 == / !=（值相等）；注册 :743-748。
        // .NET 框架语义：CompareOrdinal 逐个比 UTF-16 码元数值 ⇒ 与区域设置无关。
        // 移植侧：Expr.kt:305-318 strCompare 用 Kotlin String.compareTo（同按 Char 码元）。
        assertEquals(1L, evi(listOf(s("Z"), op("<"), s("a"))))       // 65 < 97；区域序会把 a 排到 Z 前
        assertEquals(0L, evi(listOf(s("a"), op("<"), s("Z"))))
        assertEquals(1L, evi(listOf(s("@AB"), op("<"), s("AB"))))     // 64 < 65
        assertEquals(1L, evi(listOf(s("abc"), op("<"), s("abd"))))
        assertEquals(1L, evi(listOf(s(""), op("<"), s("a"))))
        assertEquals(0L, evi(listOf(s("abc"), op("<"), s("ab"))))     // 前缀短者小
        assertEquals(1L, evi(listOf(s("abc"), op(">"), s("ab"))))
        assertEquals(1L, evi(listOf(s("あ"), op("<"), s("い"))))
        assertEquals(0L, evi(listOf(s("a"), op("=="), s("A"))))
        assertEquals(1L, evi(listOf(s("a"), op("!="), s("A"))))
    }

    @Test
    fun `字符串大于等于与小于等于复刻官方bug`() {
        // 原版：GreaterEqualStrStr :321-337 与 LessEqualStrStr :357-373 的判据**都写成** `c < 0`
        //       （:331 / :367）⇒ 「>=」实际是「<」、「<=」实际是「严格小于」（相等时泄漏为 0）。
        //       Expr.kt:299-303 的 KDoc 记录该结论已由 m1-verify1824 对照官方源码确认为
        //       官方 bug（specs/verify-notes.md §1），按 ADR-005 原样复刻。移植侧读数：Expr.kt:314-315。
        // 语料在用性：IF/SIF 条件里未命中「字符串操作数的 >= / <=」
        //   （COMMON.ERB:1827 等命中只是 CASE ">=" 的字符串字面量）⇒ 零命中，只钉桩。
        assertEquals(0L, evi(listOf(s("a"), op(">="), s("a"))))   // 应为 1，原版给 0
        assertEquals(1L, evi(listOf(s("a"), op(">="), s("b"))))   // 应为 0，原版给 1
        assertEquals(0L, evi(listOf(s("b"), op(">="), s("a"))))
        assertEquals(0L, evi(listOf(s("a"), op("<="), s("a"))))   // 应为 1，原版给 0（相等泄漏）
        assertEquals(1L, evi(listOf(s("a"), op("<="), s("b"))))
        assertEquals(0L, evi(listOf(s("b"), op("<="), s("a"))))
        // 数值版不受该 bug 影响（走 :303-319 / :339-355）
        assertEquals(1L, evi(listOf(i(3), op(">="), i(3))))
        assertEquals(1L, evi(listOf(i(3), op("<="), i(3))))
    }

    // ------------------------------------------------------------------
    // 7. 一元运算符
    // ------------------------------------------------------------------

    @Test
    fun `逻辑非按位非与一元负`() {
        // 原版：NotInt :569-585（== 0 → 1 否则 0）、BitNotInt :587-599（`~`）、
        //       MinusInt :551-567（`-`）、PlusInt :537-549（一元 + 取本体）；注册 :713-716。
        // 移植侧：Expr.kt:165-179 + Parser.kt:589-592（一元 + 在解析期消除，不建节点）。
        assertEquals(1L, evi(listOf(op("!"), i(0))))
        assertEquals(0L, evi(listOf(op("!"), i(5))))
        assertEquals(0L, evi(listOf(op("!"), i(-1))))
        assertEquals(-1L, evi(listOf(op("~"), i(0))))
        assertEquals(-6L, evi(listOf(op("~"), i(5))))
        assertEquals(0L, evi(listOf(op("~"), i(-1))))
        assertEquals(-7L, evi(listOf(op("-"), i(7))))
        assertEquals(7L, evi(listOf(op("-"), i(-7))))
        // 一元 + 不产生节点 ⇒ 副作用只发动一次（对比 EX-3 的一元负两次求值）
        val c = sideCtx()
        assertEquals(3L, evalInt(parseOrThrow(listOf(op("+")) + call("SIDE")), c))
        assertEquals(1, c.funcCalls.size)
        // 一元运算符作用于字符串：原版 ReduceUnaryTerm :761-784 只接受 typeof(long)，
        // 否则 :783-784 抛 CodeEE(CanNotAppliedUnaryOp)。移植侧 Parser.kt:594。
        assertFailsWith<ExprEvalException> { evi(listOf(op("!"), s("a"))) }
        assertFailsWith<ExprEvalException> { evi(listOf(op("~"), s("a"))) }
    }

    @Test
    fun `一元负对最小值打印系统行并回绕`() {
        // 原版：MinusInt :561-565 —— 等于 long.MinValue 时
        //       exm.Console.PrintSystemLine(string.Format(Lang.SystemLine.MinusWontWork.Text, long.MinValue))，
        //       文案见 lang.emuera-zhs.xml:1040「对整型最小值(-9223372036854775808)进行-操作其值也不对发生变化」；
        //       随后 :565 `return -arguments[0].GetIntValue(exm)` —— 默认 unchecked ⇒ 仍是 MinValue。
        // 移植侧：Expr.kt:168-172 判定与回绕一致，但**文案自造** ⇒ EX-2（文案对账，非语义分歧）。
        val ctx = TestContext()
        assertEquals(Long.MIN_VALUE, evalInt(parseOrThrow(listOf(op("-"), i(Long.MIN_VALUE))), ctx))
        assertEquals(1, ctx.systemLines.size)
        val ctx2 = TestContext()
        assertEquals(1L, evalInt(parseOrThrow(listOf(op("-"), i(-1L))), ctx2))
        assertEquals(0, ctx2.systemLines.size)   // 非 MinValue 不打印
    }

    @Test
    fun `EX3已复刻一元负对操作数求值两次`() {
        // EX-3（已采纳）：原版 MinusInt 无条件调用 arguments[0].GetIntValue(exm) **两次**
        //   （OperatorMethodManager.cs:561 判 MinValue、:565 取负），而 FunctionMethodTerm.GetIntValue
        //   （FunctionMethodTerm.cs:20-23）每次都重新递归求值、无记忆化
        //   ⇒ 形如 `-MAX(RAND:10, 5)` 的写法在原版消耗**两次** RAND 取数。
        //   语料点：ERB/コマンド関連/COMF/日常系/COMF447 露店を開く.ERB:1305（`增加来客数 = -MAX(RAND:10, 5)`）
        //           ERB/fromEN/QOL/Map Travel/QoL_DANGEROUS_TRAVEL.ERB:30/34/124（`-MAX(...)`）
        // 移植侧已由 origin/main 的 `fix(expr)` 落地双求值（Expr.kt:168-175：先判 MinValue 再二次求值取负）
        // ⇒ 本用例从「现状钉桩」改为正向断言 2 次。
        val ctx = sideCtx()
        assertEquals(-3L, evalInt(parseOrThrow(listOf(op("-")) + call("SIDE")), ctx))
        assertEquals(2, ctx.funcCalls.size)
    }

    @Test
    fun `前置后置自增自减与常量拒绝`() {
        // 原版：IncrementInt :601-613（ChangeValue(1) 的**新值**）、DecrementInt :615-627（-1）、
        //       IncrementAfterInt :629-641（ChangeValue(1) - 1 → **旧值**）、
        //       DecrementAfterInt :643-655（ChangeValue(-1) + 1 → 旧值）；
        //       注册 unaryDic :717-718、unaryAfterDic :719-720（CanRestructure = false，:605/:633）。
        //       常量与非变量校验在 ReduceUnaryTerm :757-760（IncrementNonVar / IncrementConst，
        //       文案 lang.emuera-zhs.xml:273）与 ReduceUnaryAfterTerm :790-792。
        // 移植侧：Expr.kt:151-211（Unary / UnaryAfter）+ Parser.kt:585-588, 599-602。
        val a = VarRef("A", emptyList(), operandType = OperandType.INT)
        val ctx = TestContext()
        assertEquals(1L, Unary(OperatorCode.INCREMENT, a).getIntValue(ctx))      // 0 → 1，返回新值
        assertEquals(2L, Unary(OperatorCode.INCREMENT, a).getIntValue(ctx))      // 状态确实写回
        assertEquals(2L, UnaryAfter(OperatorCode.INCREMENT, a).getIntValue(ctx)) // 返回旧值 2
        assertEquals(3L, ctx.variables.ints.getValue("A"))
        assertEquals(2L, Unary(OperatorCode.DECREMENT, a).getIntValue(ctx))       // 3 → 2，返回新值
        assertEquals(2L, UnaryAfter(OperatorCode.DECREMENT, a).getIntValue(ctx))  // 2 → 1，返回旧值 2
        assertEquals(1L, ctx.variables.ints.getValue("A"))
        assertEquals(
            listOf("A+=(1)", "A+=(1)", "A+=(1)", "A+=(-1)", "A+=(-1)"),
            ctx.variables.plusCalls,
        )
        // 后置形态的加减一修正只发动一次（原版 :639 / :653）
        val b = VarRef("B", emptyList(), operandType = OperandType.INT)
        val ctx2 = TestContext()
        ctx2.variables.ints["B"] = 10L
        assertEquals(10L, UnaryAfter(OperatorCode.INCREMENT, b).getIntValue(ctx2))  // 10 → 11，返回 10
        assertEquals(11L, ctx2.variables.ints.getValue("B"))
        assertEquals(11L, UnaryAfter(OperatorCode.DECREMENT, b).getIntValue(ctx2))  // 11 → 10，返回 11
        assertEquals(10L, ctx2.variables.ints.getValue("B"))
        // 常量与非变量被拒
        val constResolver = resolverWith(VarInfo("C", OperandType.INT, 0, isCharacterData = false, isConst = true))
        assertFailsWith<ExprEvalException> { evi(listOf(op("++"), id("C")), resolver = constResolver) }
        assertFailsWith<ExprEvalException> { evi(listOf(op("++"), i(1))) }    // 非变量
    }

    // ------------------------------------------------------------------
    // 8. 溢出与三目
    // ------------------------------------------------------------------

    @Test
    fun `长整型加减乘环绕除算溢出按原版报错`() {
        // 原版：PlusIntInt :24 / MinusIntInt :57 / MultIntInt :71 直接对 System.Int64 运算；
        //       C# 默认 unchecked ⇒ 加/减/乘环绕（spec: OperatorMethodManager.cs:24/:57/:71）。
        // 移植侧：Expr.kt:276-278（Kotlin Long 天然环绕）。
        assertEquals(Long.MIN_VALUE, evi(listOf(i(Long.MAX_VALUE), op("+"), i(1))))
        assertEquals(Long.MAX_VALUE, evi(listOf(i(Long.MIN_VALUE), op("-"), i(1))))
        assertEquals(-2L, evi(listOf(i(Long.MAX_VALUE), op("*"), i(2))))
        // MinValue / -1：C# 的 long 整除在 .NET 上抛 OverflowException，不是 unchecked 环绕；
        // 移植侧 Expr.kt:255-256 按原版抛 ExprEvalException。
        // spec: OperatorMethodManager.cs:136（arguments[0].GetIntValue(exm) / intValue）。
        assertFailsWith<ExprEvalException> { evi(listOf(i(Long.MIN_VALUE), op("/"), i(-1))) }
        // 余数不触发该溢出：C# 与 JVM 都得 0（Expr.kt:259-261）。
        assertEquals(0L, evi(listOf(i(Long.MIN_VALUE), op("%"), i(-1))))
    }

    @Test
    fun `三目只求值命中的分支`() {
        // 原版：TernaryIntIntInt :657-673（cond==0 → arguments[2]，否则 arguments[1]；另一侧不求值）、
        //       TernaryIntStrStr :675-691 同型；ReduceTernaryTerm :850-877 只允许
        //       (int,int,int) 或 (int,str,str)，否则 :876 抛 CodeEE(InvalidTernaryOp)；注册 :750-751。
        // 移植侧：Expr.kt:321-334 + Parser.kt:634-643。
        val t = Ternary(IntLiteral(0L), FuncCall("SIDE", listOf(IntLiteral(1L)), OperandType.INT), IntLiteral(5L), OperandType.INT)
        val ctx = sideCtx()
        assertEquals(5L, t.getIntValue(ctx))
        assertEquals(0, ctx.funcCalls.size)      // 未命中分支的副作用不发动
        val t2 = Ternary(IntLiteral(7L), FuncCall("SIDE", listOf(IntLiteral(1L)), OperandType.INT), IntLiteral(5L), OperandType.INT)
        val ctx2 = sideCtx(9L)
        assertEquals(9L, t2.getIntValue(ctx2))
        assertEquals(1, ctx2.funcCalls.size)
        val t3 = Ternary(IntLiteral(0L), StrLiteral("t"), StrLiteral("f"), OperandType.STR)
        assertEquals("f", t3.getStrValue(ctx))
        val t4 = Ternary(IntLiteral(1L), StrLiteral("t"), StrLiteral("f"), OperandType.STR)
        assertEquals("t", t4.getStrValue(ctx))
        // 混型三目在解析期即拒（原版 :853-876）
        assertFailsWith<ExprEvalException> { reduceTernaryTerm(IntLiteral(0L), StrLiteral("t"), IntLiteral(1L)) }
    }
}
