package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.FuncMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 4.981 浮点**声明面**：`#DIMF` 初值（`defaultsFloat`）、`#LOCALFSIZE`（[FuncMeta.floatLocalSize]
 * → `LocalLengths.floatLocal`）与存储层的落地。
 *
 * 语义出处：spec 的 variables.yaml 里 float_vars 一段（本机 reference 是 1.824、整份没有浮点
 * ⇒ fork-src 行号不可对拍，逐条 TODO(verify)）：
 * - 初值 `DefaultFloat` 单值、接受整数常量按 double 存：fork-src `UserDefinedVariableData.cs:21,317-338`；
 * - `#DIMF` 只建 1 维：fork-src `VariableData.cs:469-474` / `:553-561` / `:586-593`；
 * - `#LOCALFSIZE` 逐函数覆盖默认 10：fork-src `LogicalLineParser.cs:257-266`、`ConstantData.cs:211-217`；
 * - `LOCALF`/`ARGF` 下标判界在 Long 域：spec: LocalVariableToken.cs:39-45。
 *
 * 本文件**不起 VM**：声明面只需真 [VariableStore]（+ 帧）就能钉住「解析出什么、存进去什么、
 * 复位回到什么」；执行链（表达式读写）在 FloatVarRuntimeTest。
 */
class FloatDeclFaceTest {

    private fun dimf(body: String, isPrivate: Boolean = false): DimSpec =
        DimParser.parse("#DIMF", body, isPrivate)

    private fun storeWith(spec: DimSpec, functionKey: String? = null): VariableStore =
        VariableStore().also { s ->
            if (functionKey == null) s.declareUserVar(spec) else s.declareUserVar(spec, functionKey)
        }

    // ------------------------------------------------------------------
    // DimSpec.defaultsFloat：解析面
    // ------------------------------------------------------------------

    /** `#DIMF X = 1.5`：1 维（无尺寸段 ⇒ [1]）、初值 1.5。 */
    @Test
    fun `DIMF标量初值解析`() {
        val spec = dimf("MYRATE = 1.5")
        assertTrue(spec.isFloat, "#DIMF 必须是浮点声明")
        assertEquals(listOf(1), spec.dims.toList(), "无尺寸段 ⇒ 原版补 1 维长度 1")
        assertEquals(1.5, spec.defaultsFloat)
        assertNull(spec.defaultsInt, "浮点声明不得落整数初值表")
        assertNull(spec.defaultsStr)
    }

    /** `#DIMF X, 5 = 1.5`：5 格、单值广播（`DefaultFloat` 只有一个槽）。 */
    @Test
    fun `DIMF数组初值广播到全格`() {
        val spec = dimf("MYRATE, 5 = 1.5")
        assertEquals(listOf(5), spec.dims.toList())
        assertEquals(1.5, spec.defaultsFloat)

        val store = storeWith(spec)
        for (i in 0 until 5) {
            assertEquals(1.5, store.readFloat("MYRATE", longArrayOf(i.toLong())), "第 $i 格必须是广播初值")
        }
    }

    /** 整数初值按 double 存：`#DIMF X = 1` ⇒ 1.0（fork-src `:317-338`）。 */
    @Test
    fun `DIMF整数初值按double存`() {
        val spec = dimf("MYRATE = 1")
        assertEquals(1.0, spec.defaultsFloat, "整数常量必须提升成 double")
        assertNull(spec.defaultsInt, "浮点声明不得落整数初值表")

        val store = storeWith(spec)
        assertEquals(1.0, store.readFloat("MYRATE", longArrayOf(0)))
    }

    /** 一元负号与常量表达式都在浮点域折叠：`= -1.5`、`= 1 + 2.5`。 */
    @Test
    fun `DIMF负号与表达式初值`() {
        assertEquals(-1.5, dimf("X = -1.5").defaultsFloat)
        assertEquals(3.5, dimf("X = 1 + 2.5").defaultsFloat)
        assertEquals(2.5, dimf("X = (1 + 1) * 1.25").defaultsFloat)
        assertEquals(1.0, dimf("X = 4 / 4").defaultsFloat, "浮点除法不截断：4 / 4 = 1.0")
        assertEquals(0.5, dimf("X = 5 / 10").defaultsFloat, "整数常量参与时也按 double 除")
    }

    /** 多值没有第二个槽可放 ⇒ 声明期拒绝（不静默丢弃）。 */
    @Test
    fun `DIMF多值初值被拒绝`() {
        val e = assertFailsWith<CodeEE> { dimf("X, 5 = 1.5, 2.5") }
        assertTrue(e.message.orEmpty().contains("只能有一个浮点初值"), "文案须指出单值限制；实得 ${e.message}")
    }

    /** 拖尾逗号沿用整数侧宽松口径：`= 1.5,`（行尾注释前）仍成立。 */
    @Test
    fun `DIMF初值拖尾逗号不报错`() {
        assertEquals(1.5, dimf("X = 1.5, ; 注释").defaultsFloat)
    }

    /** 多维浮点仍被拒（fork-src `VariableData.cs:469-474` 等：2D/3D 一律 AbnormalVarDeclaration）。 */
    @Test
    fun `DIMF多维仍被拒绝`() {
        assertFailsWith<CodeEE> { dimf("X, 2, 3") }
        assertFailsWith<CodeEE> { dimf("X, 2, 3 = 1.5") }
    }

    /**
     * ADR-005 的 fork quirk：`#DIMF CHARADATA` 掉进整数分支 ⇒ isFloat=false。
     * 另按整数侧规则，CHARADATA 不允许初值（原样保留的失败方向）。
     */
    @Test
    fun `DIMF_CHARADATA仍是整数分支且不允许初值`() {
        val spec = dimf("CHARADATA X, 5")
        assertEquals(false, spec.isFloat, "CHARADATA 浮点声明在 fork 里落整数分支")
        assertNull(spec.defaultsFloat)
        assertEquals(listOf(5), spec.dims.toList())
        assertFailsWith<CodeEE> { dimf("CHARADATA X, 5 = 1") }
    }

    /** 普通 `#DIM`/`#DIMS` 的初值面不受影响。 */
    @Test
    fun `整数与字符串声明初值面不变`() {
        val intSpec = DimParser.parse("#DIM", "X, 5 = 1", isPrivate = false)
        assertNull(intSpec.defaultsFloat)
        assertEquals(listOf(1L), intSpec.defaultsInt?.toList())

        val strSpec = DimParser.parse("#DIMS", "S, 2 = \"a\"", isPrivate = false)
        assertNull(strSpec.defaultsFloat)
        assertEquals(listOf("a"), strSpec.defaultsStr)
    }

    /** 私有 `#DIMF`（函数体内）同样带初值；`isPrivate` 标记原样保留。 */
    @Test
    fun `DIMF私有声明带初值`() {
        val spec = dimf("DYNAMIC RATE, 3 = 0.5", isPrivate = true)
        assertTrue(spec.isPrivate)
        assertEquals(false, spec.isStatic)
        assertEquals(0.5, spec.defaultsFloat)
    }

    // ------------------------------------------------------------------
    // 存储层：初值落地与复位
    // ------------------------------------------------------------------

    /** 静态 `#DIMF`：建列即初值；写入后 RESETDATA 面（[VariableStore.resetLocalValues]）回到初值。 */
    @Test
    fun `静态浮点变量复位回到DIMF初值`() {
        val store = storeWith(dimf("RATE, 3 = 1.5"))
        assertEquals(1.5, store.readFloat("RATE", longArrayOf(2)), "建列即初值（懒分配也按初值建）")
        store.writeFloat("RATE", longArrayOf(1), 9.0)
        assertEquals(9.0, store.readFloat("RATE", longArrayOf(1)))

        store.resetLocalValues()
        assertEquals(1.5, store.readFloat("RATE", longArrayOf(0)), "复位回到 #DIMF 初值而不是 0.0")
        assertEquals(1.5, store.readFloat("RATE", longArrayOf(1)))
        assertEquals(1.5, store.readFloat("RATE", longArrayOf(2)))
    }

    /** 没有初值的 `#DIMF` 仍按 0.0 建列（fork-src `VariableData.cs:668-669`）。 */
    @Test
    fun `无初值浮点变量按零建列`() {
        val spec = dimf("ZERO, 2")
        assertNull(spec.defaultsFloat, "没写初值 ⇒ 不落 defaultsFloat")
        val store = storeWith(spec)
        assertEquals(0.0, store.readFloat("ZERO", longArrayOf(1)))
    }

    /** 私有动态 `#DIMF`：每次进作用域新建的列都带初值（栈弹后重进仍是初值）。 */
    @Test
    fun `动态浮点变量每次进作用域回到初值`() {
        val store = storeWith(dimf("DYNAMIC RATE, 2 = 0.25", isPrivate = true), functionKey = "T")
        store.enterFunction("T")
        try {
            assertEquals(0.25, store.readFloat("RATE", longArrayOf(1)), "第一次进作用域即初值")
            store.writeFloat("RATE", longArrayOf(1), 7.0)
            assertEquals(7.0, store.readFloat("RATE", longArrayOf(1)))
        } finally {
            store.leaveFunction()
        }
        store.enterFunction("T")
        try {
            assertEquals(0.25, store.readFloat("RATE", longArrayOf(1)), "重新进入作用域必须回到初值")
            assertEquals(0.25, store.readFloat("RATE", longArrayOf(0)))
        } finally {
            store.leaveFunction()
        }
    }

    // ------------------------------------------------------------------
    // #LOCALFSIZE：FuncMeta.floatLocalSize → LocalLengths.floatLocal → 实例长度
    // ------------------------------------------------------------------

    /** 默认长度 10：`LOCALF:9` 可读写，`LOCALF:10` 越界；VARSIZE 报 10。 */
    @Test
    fun `LOCALF默认长度十`() {
        val store = VariableStore()
        store.enterFunction("T")
        try {
            store.writeFloat("LOCALF", longArrayOf(9), 1.5)
            assertEquals(1.5, store.readFloat("LOCALF", longArrayOf(9)))
            assertEquals(10, store.getLength("LOCALF"))
            assertEquals(10, store.getLength("ARGF"))
            assertFailsWith<CodeEE> { store.readFloat("LOCALF", longArrayOf(10)) }
            assertFailsWith<CodeEE> { store.writeFloat("ARGF", longArrayOf(10), 1.0) }
        } finally {
            store.leaveFunction()
        }
    }

    /** `#LOCALFSIZE 16`：LOCALF / ARGF 都长到 16，15 号可读写、16 号越界，VARSIZE=16。 */
    @Test
    fun `LOCALFSIZE十六生效于LOCALF与ARGF`() {
        val store = VariableStore()
        store.defineLocalLengths("T", VariableStore.LocalLengths(floatLocal = 16))
        store.enterFunction("T")
        try {
            store.writeFloat("LOCALF", longArrayOf(15), 2.5)
            assertEquals(2.5, store.readFloat("LOCALF", longArrayOf(15)), "LOCALF:15 必须可写")
            assertEquals(16, store.getLength("LOCALF"), "VARSIZE(\"LOCALF\") 必须跟随 #LOCALFSIZE")

            store.writeFloat("ARGF", longArrayOf(15), 0.5)
            assertEquals(0.5, store.readFloat("ARGF", longArrayOf(15)), "ARGF:15 必须可写")
            assertEquals(16, store.getLength("ARGF"), "VARSIZE(\"ARGF\") 必须跟随 #LOCALFSIZE")

            assertFailsWith<CodeEE> { store.readFloat("LOCALF", longArrayOf(16)) }
            assertFailsWith<CodeEE> { store.writeFloat("ARGF", longArrayOf(16), 1.0) }
        } finally {
            store.leaveFunction()
        }
    }

    /** 声明只增不减：`#LOCALFSIZE 4` 不缩到 4（max(默认 10, 声明)）；ARGF 的声明长取上界。 */
    @Test
    fun `LOCALFSIZE小于默认不缩短且ARG声明只增`() {
        val store = VariableStore()
        store.defineLocalLengths("T", VariableStore.LocalLengths(floatLocal = 4, floatArg = 20))
        store.enterFunction("T")
        try {
            assertEquals(10, store.getLength("LOCALF"), "#LOCALFSIZE 4 < 默认 10 ⇒ 仍 10")
            assertEquals(20, store.getLength("ARGF"), "形参声明长出的浮点局部长度取上界")
            store.writeFloat("ARGF", longArrayOf(19), 3.0)
            assertEquals(3.0, store.readFloat("ARGF", longArrayOf(19)))
        } finally {
            store.leaveFunction()
        }
    }

    /** 浮点局部只在函数内可达（与 LOCAL 同文案方向）。 */
    @Test
    fun `浮点局部在函数外不可访问`() {
        val store = VariableStore()
        assertFailsWith<CodeEE> { store.readFloat("LOCALF", longArrayOf(0)) }
        assertFailsWith<CodeEE> { store.getLength("LOCALF") }
    }

    /** 函数帧复位（RESETDATA 的局部面）把 LOCALF/ARGF 清回 0.0（它们没有 #DIMF 初值）。 */
    @Test
    fun `局部浮点复位回零`() {
        val store = VariableStore()
        store.defineLocalLengths("T", VariableStore.LocalLengths(floatLocal = 16))
        store.enterFunction("T")
        try {
            store.writeFloat("LOCALF", longArrayOf(15), 4.0)
            store.resetLocalValues()
            assertEquals(0.0, store.readFloat("LOCALF", longArrayOf(15)))
        } finally {
            store.leaveFunction()
        }
    }

    // ------------------------------------------------------------------
    // FuncMeta.floatLocalSize：位域、值语义与上界
    // ------------------------------------------------------------------

    /** `floatLocalSize` 是可读属性、参与 equals/hashCode/toString/copy（值语义不变）。 */
    @Test
    fun `FuncMeta浮点局部长度参与值语义`() {
        val a = FuncMeta(index = 3, key = "T", floatLocalSize = 16)
        assertEquals(16, a.floatLocalSize)
        assertTrue(a.toString().contains("floatLocalSize=16"), "toString 必须带上新字段: $a")

        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(b, FuncMeta(index = 3, key = "T", floatLocalSize = 16))

        val c = a.copy(floatLocalSize = 32)
        assertEquals(32, c.floatLocalSize)
        assertTrue(a != c, "floatLocalSize 不同的两条 meta 不得相等")
    }

    /** 16 位域上界：越界在构造期炸（与 #LOCALSIZE/#LOCALSSIZE 同口径）。 */
    @Test
    fun `FuncMeta浮点局部长度越16位域被拒`() {
        assertFailsWith<IllegalArgumentException> {
            FuncMeta(index = 0, key = "T", floatLocalSize = 0x1_0000)
        }
        // 边界值本身合法
        assertEquals(0xFFFF, FuncMeta(index = 0, key = "T", floatLocalSize = 0xFFFF).floatLocalSize)
    }
}
