package com.eratw.port.core.vars

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 4.981 浮点变量族的**运行期**用例 —— 真实执行（编译 + [ProcessRuntime]），不是只看编译。
 *
 * 覆盖 [com.eratw.port.core.compile.FloatVarTest] 明确留白的另一半：那条只做「解析 + 入池」，
 * 本条把同一批形态**跑起来**并断言浮点值本身。两条口径边界（本文件的装置直接抄 FloatLiteralTest
 * 的那一套：真 [VariableStore] + 真 [ProcessRuntime]，整型侧用内建裸字母 A/B）：
 * - `LOCALF`/`ARGF`/`RESULTF` 是存储层合成的内建浮点（`VariableStore.builtinFloatOf`），
 *   系统变量表里没有这三名 ⇒ 断言一律经 `store.readFloat(...)`；
 * - 语料实证只有两条触发点（`DLC/魔法DLC/使用魔法.ERB:1248-1256`、
 *   `DLC/魔法DLC/魔法相关函数.ERB:3190-3219`），本文件的用例逐条对得上号。
 *
 * 语义出处（`specs/variables.yaml` 的 float_vars 一段；该段转引 **fork-src** 的
 * VariableData.cs 行号。本机 `reference/emuera-cs/` 是 1.824 反编译、整份没有浮点 ⇒
 * 行号不可对拍，逐条 TODO(verify) 见 `VariableStore.floatScalars` 的 KDoc）：
 * - 默认值 0.0：fork-src `VariableData.cs:668-669`；
 * - `LOCALF`/`ARGF` 1 维、出厂长 10：spec: variables.yaml:99-100；
 * - `RESULTF` 0 维标量、`dataFloat[1]`：fork-src `VariableData.cs:182`；
 * - `#DIMF` 只允许 1 维：fork-src `VariableData.cs:469-474` / `:553-559` / `:585-591`。
 */
class FloatVarRuntimeTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 与 FloatVarTest / FloatLiteralTest 同一套行分类（`@` 标签 / `#` 声明 / 其余代码）。 */
    private fun kindOf(text: String): LineKind = when {
        text.startsWith("@") -> LineKind.FUNCTION_LABEL
        text.startsWith("$") -> LineKind.GOTO_LABEL
        text.startsWith("#") -> LineKind.SHARP
        else -> LineKind.CODE
    }

    private fun parseWith(parser: LineParser, vararg lines: String) =
        lines.mapIndexedNotNull { i, text ->
            parser.parseLogicalLine(LogicalLine(kindOf(text), "float-rt.erb", i + 1, text))
        }

    private fun compilerFor(parser: LineParser, vararg lines: String): Pair<Program, ErbProgramCompiler> {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("T"), parseWith(parser, *lines), setOf("T"))
        return c.build("T") to c
    }

    /**
     * 编译 + 执行；[predeclare] 在建好 store 之后、执行之前跑一次（`#DIMF` 用户变量的
     * 声明面属于装载期，原版由 `ErhLoader` 的第一遍扫描落地，不在语义执行里）。
     */
    private fun run(
        vararg lines: String,
        symbols: SymbolTable = SymbolTable(),
        predeclare: (VariableStore) -> Unit = {},
        floatLocalSize: Int = 0,
    ): VariableStore {
        val (program, _) = compilerFor(LineParser(symbols), *lines)
        val store = VariableStore()
        predeclare(store)
        ProcessRuntime(
            program,
            store,
            FunctionTable(listOf(FuncMeta(index = 0, key = "T", defIndex = 0, floatLocalSize = floatLocalSize))),
            VmConfig(maxStepsPerRun = 50_000L),
        ).execute()
        return store
    }

    private fun floatOf(store: VariableStore, name: String, index: Long = 0): Double =
        store.readFloat(name, longArrayOf(index))

    /**
     * 程序跑完之后再读 `LOCALF` —— 必须**重新进一次那个函数**。
     *
     * 弹帧后按名访问浮点局部是「只能在函数内使用」（与 `LOCAL` 同口径）；而浮点局部实例
     * 本身不随弹帧销毁（`floatLocalsByFunc` 以函数键存，同 `localsByFunc`），所以重新
     * enter 一次就能读到上一次调用留下的值 —— 这既是断言手段，也顺手钉住「不清零」那半边。
     */
    private fun floatInT(store: VariableStore, name: String, index: Long = 0): Double {
        store.enterFunction("T")
        try {
            return store.readFloat(name, longArrayOf(index))
        } finally {
            store.leaveFunction()
        }
    }

    private fun intOf(store: VariableStore, name: String): Long =
        store.readInt(name, longArrayOf(0))

    // ------------------------------------------------------------------
    // LOCALF：直写 / 累加 / TOINT 回读
    // ------------------------------------------------------------------

    /**
     * `LOCALF = 0.0` 后读回 0.0。
     *
     * 两个断言面：走执行层的 `writeFloat` 落库，以及**从没写过的槽位**（`LOCALF:5`）
     * 默认也是 0.0（fork-src `VariableData.cs:668-669`：`dataFloat` 出厂即 0.0；
     * 移植侧的数组是懒分配，读未分配 == 读全 0）。
     */
    @Test
    fun `LOCALF直写零并读回`() {
        val store = run("@T", "LOCALF = 0.0")
        assertEquals(0.0, floatInT(store, "LOCALF"), "直写 0.0 必须原样读回")
        assertEquals(0.0, floatInT(store, "LOCALF", 5), "未触碰的槽位默认 0.0")
    }

    /**
     * 语料原型 `使用魔法.ERB:1248-1256`：`LOCALF = 0.0` → `LOCALF += 值 * 0.01` → `TOINT(LOCALF)`。
     *
     * 这里把 `YOGORE:(LOCAL)` 换成 `A = 125`，于是累加项 = 125 * 0.01。断言两件事：
     * 1. `LOCALF` **精确**等于 1.25 —— 这一条才是「执行正确」的判据：修复前浮点左值掉进
     *    字符串写通道，值根本不是 1.25；而若只把浮点折进整数列（截断口径），读回会是 1.0；
     * 2. `TOINT(LOCALF) == 1` —— 走的是「`FuncCall` 实参经 `Expr.toValue` 得 `FloatV` →
     *    内建 TOINT 收 FloatV」这条链（`PureMethods.kt:1540` 早就接了 FloatV 支，
     *    缺的是 `VarRef` 那一头的浮点读）。
     */
    @Test
    fun `LOCALF累加百分之一并TOINT回读`() {
        val store = run(
            "@T",
            "LOCALF = 0.0",
            "A = 125",
            "LOCALF += A * 0.01",
            "B = TOINT(LOCALF)",
        )
        assertEquals(1.25, floatInT(store, "LOCALF"), "0.0 + 125*0.01 必须精确等于 1.25")
        assertEquals(1L, intOf(store, "B"), "TOINT(1.25) = 1（向零截断）")
    }

    /**
     * 从**非零**起累加：`LOCALF = 1.0` 后 `+= A * 0.01`（A=125）⇒ 2.25。
     *
     * 这一条钉的是「复合赋值的左值自读」：解析期把 `LOCALF += 右值` 折成整式
     * `LOCALF + A * 0.01`（`reduceBinaryTerm`，与整数支同构），整式里的那个 `LOCALF`
     * 结点必须按浮点读。若它回落到默认的「整型通道宽化」，`LOCALF` 会被读成 1.0 之外
     * 的形态（整数列读 ⇒ 未定义变量报错 / 或截断值），结果就不是 2.25。
     */
    @Test
    fun `LOCALF从非零起累加`() {
        val store = run(
            "@T",
            "LOCALF = 1.0",
            "A = 125",
            "LOCALF += A * 0.01",
        )
        assertEquals(2.25, floatInT(store, "LOCALF"), "1.0 + 125*0.01 = 2.25（左值自读走浮点通道）")
    }

    /** `*= /= -=` 三条复合赋值与整数路径同构（整式落库，不二次套用运算符）。 */
    @Test
    fun `浮点乘等除等减等`() {
        val mul = run("@T", "LOCALF = 1.0", "LOCALF *= 1.2")
        assertEquals(1.2, floatInT(mul, "LOCALF"), 1e-12)

        val div = run("@T", "LOCALF = 1.0", "A = 8", "LOCALF /= A")
        assertEquals(0.125, floatInT(div, "LOCALF"), "1.0 / 8 = 0.125（二进制精确）")

        val sub = run("@T", "LOCALF = 2.5", "LOCALF -= 1.0")
        assertEquals(1.5, floatInT(sub, "LOCALF"))
    }

    /** 整数右值自动提升：`LOCALF = A`（A 是整型）按 double 落库。 */
    @Test
    fun `整数右值自动提升进浮点`() {
        val store = run("@T", "A = 7", "LOCALF = A")
        assertEquals(7.0, floatInT(store, "LOCALF"))
    }

    /** `++` / `--`（fork 是否对浮点开放未取证，本侧按「与整数支同构」放行 ±1.0）。 */
    @Test
    fun `浮点自增自减`() {
        val up = run("@T", "LOCALF = 1.5", "LOCALF++")
        assertEquals(2.5, floatInT(up, "LOCALF"))
        val down = run("@T", "LOCALF = 1.5", "LOCALF--")
        assertEquals(0.5, floatInT(down, "LOCALF"))
    }

    // ------------------------------------------------------------------
    // RESULTF：内建浮点结果寄存器（写 + 读）
    // ------------------------------------------------------------------

    /**
     * 内建方法调用（METHOD 语句）的浮点支写 `RESULTF`，随后 `LOCALF = RESULTF` 读回一致。
     *
     * 写入口依据 = specs/variables.yaml float_vars.scalars.RESULTF 那句「由 METHOD 语句按
     * 表达式类型写入」（转引 fork-src `Instraction.Child.cs:613-629`，浮点分支 `:629` 前的
     * 那一支）；移植侧的 METHOD 在 `StatementExecutor`（1.824 同位置 `FunctionIdentifier.cs:584-604`
     * 只有 RESULT/RESULTS 两支，浮点是第三支）。
     *
     * 为什么写 `TOFLOAT 1.5` 而不是字面的 `METHOD …`：ERB 里没有 `METHOD` 关键字，
     * **行首的内建方法名**就是 METHOD 语句（`LineParser` 的分派，移植侧
     * `ErbProgramCompilerTest` 的「行首内建方法是 METHOD marker」钉着同一条）。
     * `TOFLOAT` 在 methods 表里且返回型是 FLOAT（`BuiltinSymbols.kt:470,775`）。
     */
    @Test
    fun `方法调用写RESULTF后LOCALF读回`() {
        // RESULTF 的**解析面**在这里手工登记（dimension 0 = 0 维标量，
        // spec: variables.yaml float_vars.scalars.RESULTF）。真源应在 `stmt/BuiltinSymbols`
        // 的变量表里（`RESULT` 就登记在那儿），该文件不归本路 ⇒ 见交付报告「需组长接线」；
        // 登记缺失时的现象就是本用例解析不过（`未定义的标识符 'RESULTF'`），不会静默降级。
        val symbols = SymbolTable().declareVariable("RESULTF", OperandType.FLOAT, 0)
        val store = run("@T", "TOFLOAT 1.5", "LOCALF = RESULTF", symbols = symbols)
        assertEquals(1.5, store.readFloat("RESULTF", LongArray(0)), "FLOAT 返回的内建方法必须落进 RESULTF")
        assertEquals(1.5, floatInT(store, "LOCALF"), "RESULTF → LOCALF 逐值一致")
    }

    /**
     * `RESULTF` 是 0 维：写入口经存储层、读入口经表达式链，且**带下标即报错**。
     *
     * 口径与 [com.eratw.port.core.vars.VariableStore] 里整数/字符串 0 维同源
     * （都走 `VarErrorText.zeroDVarHasArg` = 官方 `Error.ZeroDVarHasArg`，
     * Lang.cs:1566 / 语料根 emuera.zhs.xml:774 / reference 表 :346；
     * 原版抛出点 VariableParser.cs:178，实参 = 变量名）。
     */
    @Test
    fun `RESULTF是零维`() {
        val store = VariableStore()
        store.writeFloat("RESULTF", LongArray(0), 3.75)
        assertEquals(3.75, store.readFloat("RESULTF", LongArray(0)))
        val e = assertFailsWith<CodeEE> { store.readFloat("RESULTF", longArrayOf(0)) }
        assertEquals("变量\"RESULTF\"不是数组却带了一个参数", e.message, "RESULTF 带下标应报 ZeroDVarHasArg")
    }

    // ------------------------------------------------------------------
    // #DIMF 用户浮点变量
    // ------------------------------------------------------------------

    /**
     * `#DIMF` 静态用户变量的完整执行链（语料 `魔法相关函数.ERB:3190-3199` 的形状）：
     * `倍率 = 1.0` → `倍率 *= 1.2` → `IF 倍率 > 1.1` → `A = TOINT(倍率)`。
     *
     * 声明面为什么在测试里手工 `declareUserVar`：原版 `#DIMF` 由装载期第一遍扫描建表
     * （`ErhLoader.cs:413-423`），移植侧的对应入口是 `SystemVariableBootstrap`，
     * 都不在单条语句执行里。此处只把 `DimSpec(isFloat = true)` 交给存储层，
     * 走的正是本波新增的 `StaticFloatUserVar` 分支。
     */
    @Test
    fun `DIMF静态浮点变量执行链`() {
        val symbols = SymbolTable().declareVariable("倍率", OperandType.FLOAT, 1)
        val store = run(
            "@T",
            "倍率 = 1.0",
            "倍率 *= 1.2",
            "IF 倍率 > 1.1",
            "A = TOINT(倍率) + 5",
            "ELSE",
            "A = 0",
            "ENDIF",
            symbols = symbols,
            predeclare = { s ->
                s.declareUserVar(DimSpec("倍率", isStr = false, dims = intArrayOf(1), isFloat = true))
            },
        )
        assertEquals(1.2, store.readFloat("倍率", longArrayOf(0)), 1e-12, "1.0 *= 1.2")
        // 1.2 > 1.1 ⇒ 真分支：TOINT(1.2)=1，+5 ⇒ 6。假分支会写 0。
        assertEquals(6L, intOf(store, "A"), "浮点比较走表达式链，IF 必须取真分支")
    }

    /**
     * 私有动态（`#DIMF DYNAMIC`）浮点变量：作用域内可读写。
     *
     * 语料实况 `魔法相关函数.ERB:3190` `#DIMF DYNAMIC 倍率` 是全语料唯一一条 `#DIMF`。
     * `isPrivate = true` ⇒ 走 [VariableStore.DynamicFloatUserVar]，`enterFunction("T")`
     * 由 [ProcessRuntime] 驱动（不是手工帧），因此这条同时钉住了「动态浮点列的作用域栈」
     * 与「执行层的按名解析」（私有变量优先当前帧）。
     *
     * 为什么断言要经一条**静态**浮点列把值抄出来：动态变量随调用作废（`scopeOut` 丢数组、
     * 下次 `scopeIn` 新建 —— 与 `DynamicIntUserVar` 同口径，spec: VariableData.cs:1820-1901），
     * 程序跑完后再读它就是「作用域外访问」。抄出来这一步本身也是被测面：
     * 私有动态浮点必须能进表达式链被读。
     */
    @Test
    fun `DIMF私有动态浮点变量执行`() {
        val symbols = SymbolTable()
            .declareVariable("倍率D", OperandType.FLOAT, 1)
            .declareVariable("浮标", OperandType.FLOAT, 1)
        val store = run(
            "@T",
            "倍率D = 0.5",
            "倍率D += 0.25",
            "浮标 = 倍率D",
            symbols = symbols,
            predeclare = { s ->
                s.declareUserVar(DimSpec("浮标", isStr = false, dims = intArrayOf(1), isFloat = true))
                s.declareUserVar(
                    DimSpec(
                        "倍率D", isStr = false, dims = intArrayOf(1),
                        isStatic = false, isPrivate = true, isFloat = true,
                    ),
                    functionKey = "T",
                )
            },
        )
        assertEquals(0.75, store.readFloat("浮标", longArrayOf(0)), "私有动态浮点列的 += 必须生效")
    }

    /**
     * 存储层的 `#DIMF` 维度守卫：2D/3D/0 维一律拒绝。
     *
     * 声明期 `DimParser` 已挡掉「显式写尺寸段」的多维形态；这里是**第二个入口**
     * （编译期元数据 / 手工 DimSpec）的同一条判据，依据 fork-src `VariableData.cs:469-474`、
     * `:553-559`、`:585-591` 三处「非 1 维即 ExeEE(AbnormalVarDeclaration)」。
     */
    @Test
    fun `DIMF多维在存储层被拒`() {
        val store = VariableStore()
        for (dims in listOf(intArrayOf(3, 4), intArrayOf(2, 2, 2), IntArray(0))) {
            val e = assertFailsWith<CodeEE> {
                store.declareUserVar(DimSpec("浮列", isStr = false, dims = dims, isFloat = true))
            }
            assertTrue(e.message.orEmpty().contains("1 维"), "报错应指向 1 维限制；实得 ${e.message}")
        }
    }

    /** 1D 多维批量写（`SET 倍率, 1.0, 2.0` 的形态）逐元素落库；0 维 `RESULTF` 拒绝数组形态。 */
    @Test
    fun `浮点批量写与零维拒绝数组`() {
        val store = VariableStore()
        store.declareUserVar(DimSpec("浮列", isStr = false, dims = intArrayOf(3), isFloat = true))
        store.setValues("浮列", longArrayOf(0), doubleArrayOf(1.5, 2.5, 3.5))
        for (i in 0 until 3) {
            assertEquals(i + 1.5, store.readFloat("浮列", longArrayOf(i.toLong())), "第 $i 格")
        }
        val e = assertFailsWith<CodeEE> {
            store.setValues("RESULTF", LongArray(0), doubleArrayOf(1.0, 2.0))
        }
        assertTrue(e.message.orEmpty().contains("数组"), "0 维浮点不该吃数组形态；实得 ${e.message}")
    }

    /**
     * 批量写越界 ⇒ **一格都不落**。
     *
     * 判界原来发生在逐元素写的第 N 格：`#DIMF 浮列,3` 上 `SET 浮列, 1,2,3,4`（起点 1、三值）
     * 会先把 1、2 两格改成新值，才在越界那格报错 ⇒ TRYC 接住之后池里是半新半旧的态，玩家
     * 直接看得见。整数/字符串重载早有「整条前移拒绝」，浮点重载是 4.981 新加的，补同一口径。
     */
    @Test
    fun `浮点批量写越界不留半写态`() {
        val store = VariableStore()
        store.declareUserVar(DimSpec("浮列", isStr = false, dims = intArrayOf(3), isFloat = true))
        store.setValues("浮列", longArrayOf(0), doubleArrayOf(1.0, 2.0, 3.0))

        assertFailsWith<CodeEE> {
            store.setValues("浮列", longArrayOf(1), doubleArrayOf(9.0, 9.0, 9.0))
        }
        for (i in 0 until 3) {
            assertEquals(
                (i + 1).toDouble(),
                store.readFloat("浮列", longArrayOf(i.toLong())),
                "第 $i 格必须还是改写前的值",
            )
        }
    }

    // ------------------------------------------------------------------
    // 错误路径
    // ------------------------------------------------------------------

    /**
     * `LOCALF` 越界：长度 10（spec: variables.yaml:99-100），第 10 格即越界。
     *
     * 判界必须留在 **Long 域**（`LocalVariableToken.cs:39-45` 的口径）：先 toInt 会把
     * `2^32` 回绕成 0 静默别名到 0 号格 —— 这条同时钉住「不许静默别名」。
     * 文案 = 官方 `Error.OoRVarArg` 逐字（Lang.cs:1638 / 语料根 emuera.zhs.xml:585 /
     * reference 表 :364）；官方串只带 变量名/维号/下标，**不带长度**
     * （`LocalVariableToken.cs:43` 的实参就是这三个）。
     */
    @Test
    fun `浮点局部越界报错`() {
        val store = VariableStore()
        store.enterFunction("T")
        val e = assertFailsWith<CodeEE> { store.writeFloat("LOCALF", longArrayOf(10), 1.0) }
        assertEquals("数组变量\"LOCALF\"的第1参数(10)超出数组范围", e.message, "应报 OoRVarArg")
        val big = assertFailsWith<CodeEE> { store.readFloat("LOCALF", longArrayOf(1L shl 32)) }
        assertEquals(
            "数组变量\"LOCALF\"的第1参数(4294967296)超出数组范围",
            big.message,
            "2^32 不得回绕成 0 号格（Long 域判界）；实得 ${big.message}",
        )
        store.leaveFunction()
    }

    /** 函数外访问 `LOCALF`：与 `LOCAL` 同口径的「只能在函数内使用」。 */
    @Test
    fun `函数外访问浮点局部报错`() {
        val store = VariableStore()
        val e = assertFailsWith<CodeEE> { store.readFloat("LOCALF", longArrayOf(0)) }
        assertTrue(e.message.orEmpty().contains("函数内"), "实得 ${e.message}")
    }

    /**
     * 未声明 / 类型不符的访问走既定错误路径：
     * - 名字彻底不存在 ⇒ `未定义的变量`；
     * - 名字存在但**不是浮点** ⇒ `不是浮点变量`（整数列、字符串列各一条）。
     *
     * 两句必须分开：前者是「查不到」，后者是「查到了但通道不对」，混成一句会让挖虫时
     * 分不清声明缺失还是类型走错。
     */
    @Test
    fun `未声明与类型不符的浮点访问`() {
        val store = VariableStore()
        val missing = assertFailsWith<CodeEE> { store.readFloat("NEVER_DECLARED_F", longArrayOf(0)) }
        assertTrue(missing.message.orEmpty().contains("未定义的变量"), "实得 ${missing.message}")

        store.writeInt("DAY", longArrayOf(0), 1L)
        val wrongType = assertFailsWith<CodeEE> { store.readFloat("DAY", longArrayOf(0)) }
        assertTrue(wrongType.message.orEmpty().contains("不是浮点变量"), "实得 ${wrongType.message}")

        // 字符串侧用一个全局字符串列（角色变量 NAME 需要角色在场，与本断言无关）。
        store.declareUserVar(DimSpec("字符串列", isStr = true, dims = intArrayOf(1)))
        store.writeStr("字符串列", longArrayOf(0), "x")
        val wrongStr = assertFailsWith<CodeEE> { store.readFloat("字符串列", longArrayOf(0)) }
        assertTrue(wrongStr.message.orEmpty().contains("不是浮点变量"), "实得 ${wrongStr.message}")
    }

    /** 浮点列**不吃**整数/字符串通道：`倍率` 是浮点 ⇒ 按 `readInt` 访问报「是浮点变量」。 */
    @Test
    fun `浮点变量的整数与字符串通道被拒`() {
        val store = VariableStore()
        store.declareUserVar(DimSpec("浮列", isStr = false, dims = intArrayOf(1), isFloat = true))
        val e = assertFailsWith<CodeEE> { store.readInt("浮列", longArrayOf(0)) }
        assertTrue(e.message.orEmpty().contains("浮点变量"), "实得 ${e.message}")
        val s = assertFailsWith<CodeEE> { store.writeStr("浮列", longArrayOf(0), "x") }
        assertTrue(s.message.orEmpty().contains("浮点变量"), "实得 ${s.message}")
    }

    /** `VARSIZE` 问得到的浮点长度：`LOCALF` = 10；`RESULTF` 是 0 维、没有尺寸。 */
    @Test
    fun `浮点变量的长度口径`() {
        val store = VariableStore()
        store.enterFunction("T")
        assertEquals(10, store.getLength("LOCALF"), "出厂长 10（spec: variables.yaml:99-100）")
        val e = assertFailsWith<CodeEE> { store.getLength("RESULTF") }
        assertTrue(e.message.orEmpty().contains("没有尺寸"), "0 维标量不该有尺寸；实得 ${e.message}")
        store.leaveFunction()
    }

    /**
     * `#LOCALFSIZE` 的生产接线：`FuncMeta.floatLocalSize` → `ProcessRuntime` 组装期的
     * `defineLocalLengths` → `LOCALF`/`ARGF` 的有效长度。
     *
     * 这是 `FloatDeclFaceTest` 直调 `defineLocalLengths` 之外的另一半：没有这条，
     * 侧车/函数元数据里的值到不了运行期（声明面只有测试装置能驱动）。
     */
    @Test
    fun `LOCALFSIZE经函数元数据放大浮点局部长度`() {
        val store = run("@T", "RETURN", floatLocalSize = 16)
        store.enterFunction("T")
        try {
            assertEquals(16, store.getLength("LOCALF"), "#LOCALFSIZE 16 必须覆盖出厂 10")
            assertEquals(16, store.getLength("ARGF"), "ARGF 与 LOCALF 同受 #LOCALFSIZE")
        } finally {
            store.leaveFunction()
        }
    }

    /** 复位面：RESETDATA 清 `RESULTF` 与浮点局部，`#DIMF` 静态列原地回 0.0。 */
    @Test
    fun `浮点复位`() {
        val store = VariableStore()
        store.declareUserVar(DimSpec("浮列", isStr = false, dims = intArrayOf(2), isFloat = true))
        store.enterFunction("T")
        store.writeFloat("RESULTF", LongArray(0), 9.5)
        store.writeFloat("LOCALF", longArrayOf(3), 8.5)
        store.writeFloat("浮列", longArrayOf(1), 7.5)
        store.resetValues()
        assertEquals(0.0, store.readFloat("RESULTF", LongArray(0)), "dataFloat 由 SetDefaultValue 清 0.0")
        assertEquals(7.5, store.readFloat("浮列", longArrayOf(1)), "系统复位不碰用户 #DIM（fork-src :3385-3514 同边界）")
        store.resetLocalValues()
        assertEquals(0.0, store.readFloat("LOCALF", longArrayOf(3)), "RESETDATA 复位函数局部")
        assertEquals(0.0, store.readFloat("浮列", longArrayOf(1)), "静态用户浮点由 SetDefaultLocalValue 复位")
        store.leaveFunction()
    }

    /**
     * 帧隔离：`LOCALF` 按函数分实例（与 `LOCAL` 同口径，spec: VariableLocal.cs:15,26-33），
     * 且**离开帧不清值** —— 原版没有「每次调用清零 LOCAL」的调用点
     * （`SetDefaultLocalValue` 只由 RESETDATA 与读档调用，spec: VariableEvaluator.cs:1339）。
     */
    @Test
    fun `浮点局部按函数分实例`() {
        val store = VariableStore()
        store.enterFunction("A1")
        store.writeFloat("LOCALF", longArrayOf(0), 1.5)
        store.leaveFunction()
        store.enterFunction("B2")
        assertEquals(0.0, store.readFloat("LOCALF", longArrayOf(0)), "另一个函数的 LOCALF 是独立实例，出厂 0.0")
        store.writeFloat("LOCALF", longArrayOf(0), 2.5)
        store.leaveFunction()
        store.enterFunction("A1")
        assertEquals(1.5, store.readFloat("LOCALF", longArrayOf(0)), "回原函数仍看得见上次写的值（不清零）")
        store.leaveFunction()
        store.enterFunction("B2")
        assertEquals(2.5, store.readFloat("LOCALF", longArrayOf(0)), "两份实例互不串味")
        store.leaveFunction()
    }

    /**
     * `ARGF` 与 `LOCALF` 是**两份独立**的浮点局部（fork-src `VariableData.cs:376-379` 注册两名，
     * spec: variables.yaml:99-100 各长 10），同函数内互不覆盖。
     *
     * 语料只用 `LOCALF`（`使用魔法.ERB:1248-1256`），本条钉的是另一名的存储面不撞车。
     */
    @Test
    fun `ARGF与LOCALF各自一份`() {
        val store = VariableStore()
        store.enterFunction("T")
        store.writeFloat("LOCALF", longArrayOf(0), 1.25)
        store.writeFloat("ARGF", longArrayOf(0), -3.5)
        assertEquals(1.25, store.readFloat("LOCALF", longArrayOf(0)))
        assertEquals(-3.5, store.readFloat("ARGF", longArrayOf(0)), "ARGF 不得覆盖 LOCALF 的同下标格")
        store.leaveFunction()
    }
}
