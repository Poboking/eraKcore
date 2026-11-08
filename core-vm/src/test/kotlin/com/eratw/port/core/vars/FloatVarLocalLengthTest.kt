package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [g9] ARGF 的**形参声明长**接进 [VariableStore.LocalLengths.floatArg]，以及它与
 * VARIABLESIZE.CSV 浮点档、`#LOCALFSIZE` 三者的取上界关系。
 *
 * ## 证据（fork 4.981 = vendor emuera-skia @48494165）
 * - 形参声明长的来源：`ErbLoader.cs:600-602,709-710,787`
 *   （`maxArgF` 初值 **-1**，逐条 ARGF 形参用 `getEl1forArg + 1` 抬上界 → `label.ArgFloatLength`）；
 * - 生效方式（**只增不减**）：`VariableLocal.cs:46-53`
 *   （`ARGF ⇒ newSize = func.ArgFloatLength`；`newSize > 0` 且 `newSize < size` 且
 *   `varCode ∈ {ARG, ARGS, ARGF}` ⇒ `newSize = size`，`size` 就是 VARIABLESIZE.CSV 那一档）；
 * - `size` 从哪来：`VariableData.cs:376-379`（`new VariableLocal(ARGF, VariableFloatArrayLength[1], …)`），
 *   出厂 10 见 `ConstantData.cs:211-217`、可被 CSV 改写见 `:532-533`。
 *
 * ⚠ **两处与 fork 不同口径**（本路按 g1 契约取 max，逐条实测取证；改动会牵动 g1 已验收的
 * `FloatDeclFaceTest` ⇒ 留给组长裁决，详见交付报告）：
 * 1. fork 的 ARGF 只读 `ArgFloatLength`（`VariableLocal.cs:48-49`），`#LOCALFSIZE`
 *    记在 `LocalFloatLength` 上（`LogicalLineParser.cs:257-266`），**不互相抬界**；
 *    本式把 `floatLocal` 也并进了 ARGF 的上界；
 * 2. fork 对**非事件函数**的 LOCALF 不做下界钳制（钳制名单里没有 LOCALF），
 *    只有事件函数在分组时抬到档位（`LabelDictionary.cs:155-173` / `:226-253`）；
 *    本式对 LOCALF 一律取 max。
 *    对照：本仓整数侧 `resolveLocalSize` 已是 fork 口径（`ARG/ARGS` 取 max、
 *    `LOCAL/LOCALS` 声明优先可缩）——见下面那条同名对照用例。
 *
 * 浮点侧无 1.824 对拍（1.824 没有浮点变量）⇒ 逐条 TODO(verify)；
 * 每个用例都带一条 1.824 侧的 int/str 对照，钉住非浮点面未变。
 */
class FloatVarLocalLengthTest {

    private fun storeWith(
        floatLocal: Int = 0,
        floatArg: Int = 0,
        arg: Int = 0,
        args: Int = 0,
        local: Int = 0,
        locals: Int = 0,
        sizes: VariableSizes = VariableSizes(),
    ): VariableStore {
        val store = VariableStore(VariableRegistry(sizes))
        store.defineLocalLengths(
            "T",
            VariableStore.LocalLengths(
                local = local, locals = locals, arg = arg, args = args,
                floatLocal = floatLocal, floatArg = floatArg,
            ),
        )
        return store
    }

    // ------------------------------------------------------------------
    // 形参声明长的数法（ProcessRuntime 接线的那一步）
    // ------------------------------------------------------------------

    /**
     * `floatArgFromParams`：形参表里 ARGF 的个数（与整数侧 erbcc 的「按个数分桶」同口径，
     * spec: erbcc `CorpusCompile.kt:641-642`）。
     *
     * TODO(verify)：fork 用的是 `max(下标 + 1)`（`ErbLoader.cs:709-710`），移植侧的
     * `FuncLabelStmt.paramNames` 已剥掉下标 ⇒ 跳号形参（`@F(ARGF, ARGF:5)`）本层数成 2、
     * 原版算 6；4.981 语料实测 0 处 ARGF 形参，无对拍样本。
     */
    @Test
    fun `形参表数出ARGF声明长`() {
        fun f(names: List<String>) = VariableStore.LocalLengths.floatArgFromParams(names)
        assertEquals(0, f(emptyList()), "无形参（含 .erbc v1 不带形参名表）⇒ 0，与改造前恒 0 同值")
        assertEquals(0, f(listOf("ARG")), "纯整型形参不算 ARGF")
        assertEquals(1, f(listOf("ARG", "ARGF")), "一条 ARGF ⇒ 1")
        assertEquals(2, f(listOf("ARGF", "ARGF")), "两条 ⇒ 2")
        assertEquals(1, f(listOf("ARGF", "ARG", "ARGS")), "ARG/ARGS 不误计")
        assertEquals(1, f(listOf("argf")), "大小写不敏感（原版 Config.cs:33 OrdinalIgnoreCase）")
        assertEquals(1, f(listOf("ARGF$")), "变长尾标记仍算一条 ARGF")
        assertEquals(0, f(listOf("ARGS", "ARGS$")), "ARGS 与 ARGF 不同名")
        assertEquals(0, f(listOf("A", "B$", "RESULT")), "普通私有形参一律不算")
    }

    /**
     * ARGF 实例长度 = `max(VARSIZE 档, #LOCALFSIZE, 形参声明长)`，且**每函数独立**。
     * 下标判界跟着长度走。
     */
    @Test
    fun `形参声明长生效于ARGF且不牵动LOCALF`() {
        val store = storeWith(floatArg = 25)
        store.enterFunction("T")
        try {
            assertEquals(25, store.getLength("ARGF"), "声明长 25 ⇒ 生效长度 25（fork VariableLocal.cs:48-54）")
            assertEquals(10, store.getLength("LOCALF"), "ARGF 的声明长不抬高 LOCALF")
            store.writeFloat("ARGF", longArrayOf(24), 1.5)
            assertEquals(1.5, store.readFloat("ARGF", longArrayOf(24)))
            assertFailsWith<CodeEE> { store.writeFloat("ARGF", longArrayOf(25), 1.0) }
        } finally {
            store.leaveFunction()
        }
        // 另一个函数没声明 ⇒ 仍出厂 10
        val other = VariableStore()
        other.enterFunction("U")
        try {
            assertEquals(10, other.getLength("ARGF"), "逐函数注册（fork LabelDictionary.cs:116 的 resizeLocalVars 按函数名）")
        } finally {
            other.leaveFunction()
        }
        // 1.824 对照：整型 ARG 的声明长同一生效（max 口径）
        val intStore = storeWith(arg = 1200)
        intStore.enterFunction("T")
        try {
            assertEquals(1200, intStore.getLength("ARG"), "ARG 取 max(默认 1000, 声明 1200)")
            assertEquals(1000, intStore.getLength("LOCAL"), "LOCAL 的声明缺席 ⇒ 出厂 1000")
        } finally {
            intStore.leaveFunction()
        }
    }

    /** **只增不减**：声明比档位小时不得缩短（fork `VariableLocal.cs:52` 的 `newSize = size`）。 */
    @Test
    fun `浮点声明长只增不减`() {
        // 出厂档 10 + 声明 3 ⇒ 10
        val a = storeWith(floatArg = 3)
        a.enterFunction("T")
        try {
            assertEquals(10, a.getLength("ARGF"), "声明 3 < 档位 10 ⇒ 仍 10")
            assertFailsWith<CodeEE> { a.readFloat("ARGF", longArrayOf(10)) }
        } finally {
            a.leaveFunction()
        }
        // VARIABLESIZE.CSV 把档抬到 40，声明 5 ⇒ 40（档位是下界，不是上限）
        val sizes = VariableSizes().apply { setFloat1D("ARGF", 40) }
        val b = storeWith(floatArg = 5, sizes = sizes)
        b.enterFunction("T")
        try {
            assertEquals(40, b.getLength("ARGF"), "声明只增不减 ⇒ 档位 40 生效")
            b.writeFloat("ARGF", longArrayOf(39), 2.0)
            assertEquals(2.0, b.readFloat("ARGF", longArrayOf(39)))
        } finally {
            b.leaveFunction()
        }
        // 1.824 对照：ARG 的同一条下界（默认 1000，声明 5 不缩短）
        val c = storeWith(arg = 5)
        c.enterFunction("T")
        try {
            assertEquals(1000, c.getLength("ARG"), "整数侧同样只增不减（fork VariableLocal.cs:52 同一支）")
        } finally {
            c.leaveFunction()
        }
    }

    /**
     * 三者同现：`VARSIZE 档 20` + `#LOCALFSIZE 30` + 形参声明 12 ⇒ LOCALF/ARGF 都 30。
     *
     * TODO(verify) 口径差（见类 KDoc 第 1 条）：fork 严格算来 ARGF = `max(20, 12)` = 20，
     * 本式把 `#LOCALFSIZE` 也并进来 ⇒ 30。本路按 g1 契约取 max，不动。
     */
    @Test
    fun `档位与LOCALFSIZE与形参声明三者取上界`() {
        val sizes = VariableSizes().apply {
            setFloat1D("LOCALF", 20)
            setFloat1D("ARGF", 20)
        }
        val store = storeWith(floatLocal = 30, floatArg = 12, sizes = sizes)
        store.enterFunction("T")
        try {
            assertEquals(30, store.getLength("LOCALF"))
            assertEquals(30, store.getLength("ARGF"))
            store.writeFloat("LOCALF", longArrayOf(29), 1.0)
            assertFailsWith<CodeEE> { store.readFloat("LOCALF", longArrayOf(30)) }
        } finally {
            store.leaveFunction()
        }
        // 1.824 对照：同一份 CSV 的 LOCAL/LOCALS 行照常（不受浮点面影响）。
        // 按 code 走 applySize：LOCAL 族不在 setInt1D/setStr1D 的名字清单里（那两只只认
        // SystemLayout.int1D / str1D，LOCAL/ARG/LOCALS/ARGS 在 localInt / localStr 里）。
        val s2 = VariableSizes().apply {
            applySize(VariableCode.LOCAL_VAR, intArrayOf(200))
            applySize(VariableCode.LOCALS, intArrayOf(300))
        }
        val store2 = VariableStore(VariableRegistry(s2))
        store2.enterFunction("T")
        try {
            assertEquals(200, store2.getLength("LOCAL"))
            assertEquals(300, store2.getLength("LOCALS"))
        } finally {
            store2.leaveFunction()
        }
    }

    /**
     * 本仓整数侧已是 fork 的**分型口径**（ARG 取 max、LOCAL 声明优先可缩）：
     * spec: fork-src VariableLocal.cs:50-53 的钳制名单只有 ARG/ARGS（+ARGF），
     * LOCAL/LOCALS 声明优先。留这条既作 1.824 面对照，也作浮点侧待裁决的镜像证据。
     */
    @Test
    fun `整数侧LOCAL声明优先可缩而ARG不可缩`() {
        val store = storeWith(local = 4, arg = 4)
        store.enterFunction("T")
        try {
            assertEquals(4, store.getLength("LOCAL"), "LOCAL 声明优先（可缩）")
            assertEquals(1000, store.getLength("ARG"), "ARG 取 max(默认, 声明)")
        } finally {
            store.leaveFunction()
        }
        // 浮点侧当前对 LOCALF 也取 max ⇒ 与上面这条不对称（待组长裁决）
        val f = storeWith(floatLocal = 4)
        f.enterFunction("T")
        try {
            assertEquals(10, f.getLength("LOCALF"), "本路口径：LOCALF 也取 max(10, 4)")
        } finally {
            f.leaveFunction()
        }
    }

    /**
     * 同一函数重复进帧共用一份实例（递归同长度），且 `floatArg` 只在建实例时读一次：
     * 实例长度缓存后即使随后改注册表也不追溯（与 `FloatLocal` 的懒建缓存同判据）。
     */
    @Test
    fun `重复进帧共用同一份浮点局部长度`() {
        val store = storeWith(floatArg = 25)
        repeat(2) {
            store.enterFunction("T")
            try {
                assertEquals(25, store.getLength("ARGF"))
                store.writeFloat("ARGF", longArrayOf(24), 7.0)
            } finally {
                store.leaveFunction()
            }
        }
        store.enterFunction("T")
        try {
            assertEquals(7.0, store.readFloat("ARGF", longArrayOf(24)), "递归共用同一份（fork VariableLocal.cs:21 的 per-subKey 字典）")
        } finally {
            store.leaveFunction()
        }
        // 1.824 对照：整型 ARG 同判据
        val i = storeWith(arg = 1200)
        i.enterFunction("T")
        try {
            i.writeInt("ARG", longArrayOf(1199), 3L)
        } finally {
            i.leaveFunction()
        }
        i.enterFunction("T")
        try {
            assertEquals(3L, i.readInt("ARG", longArrayOf(1199)))
        } finally {
            i.leaveFunction()
        }
    }
}
