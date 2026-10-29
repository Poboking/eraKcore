package com.eratw.port.core.code

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T2-23「变量指令的装期绑定」+ T2-21 遗留的写通道验收。
 *
 * 与 [StackOpcodeTest] 的分工：那里用只读假变量层验**指令语义**（求值、栈机、往返），
 * 这里接**真变量池**（[VariableStore]）验**绑定结果**——包括只可能出在真池上的四件事：
 *
 * 1. 私有 `#DIM` 变量按函数隔离：同名变量在两个函数里必须是两个实例；
 * 2. 句柄缓存键必须带函数（只按变量名缓存会把 f2 的写落进 f1 的实例）；
 * 3. 写通道落地（`_STK_STORE_VAR_I/_S`），含下标形态；
 * 4. 报错文案与按名路径**逐字相同**（契约 §0.2 第 4 条）。
 *
 * 宿主的 [VmHost.onFunctionEnter]/[VmHost.onFunctionExit] 必须真的驱动
 * `VariableStore.enterFunction/leaveFunction` —— 私有变量的作用域由它开合，
 * 少了这一步，下面所有私有变量的用例都会退化成读全局。
 */
class VmVarBindingTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 把变量池的作用域栈挂到 VM 的帧生命周期上（等价 `ProcessRuntime` 的那两行）。 */
    private class StoreHost(
        private val store: VariableStore,
        private val functionNames: List<String>,
    ) : VmHost {
        override val evalContext: EvalContext = object : EvalContext {
            override val variables: VariableReader get() = store

            override fun callFunction(name: String, args: List<Value?>): Value =
                throw ExprEvalException("测试未绑定函数 $name")
        }

        override fun onFunctionEnter(funcIndex: Int) {
            store.enterFunction(functionNames[funcIndex])
        }

        override fun onFunctionExit(funcIndex: Int) {
            store.leaveFunction()
        }

        override fun print(text: String) {}
    }

    private fun newStore(): VariableStore = VariableStore().apply {
        // ADR-001：RNG 必须走注入的 provider（禁止 kotlin.random 兜底）。
        randProvider = com.eratw.port.core.rand.Sfmt19937(0L)
    }

    private fun runOn(store: VariableStore, src: String): Long {
        val program = Assembler.assemble(src)
        val host = StoreHost(store, program.functions.map { it.name })
        return VirtualMachine(program, host).run()
    }

    /** 同 [runOn]，走字符串返回通道（`_RETS`）。 */
    private fun runStrOn(store: VariableStore, src: String): String {
        val program = Assembler.assemble(src)
        val vm = VirtualMachine(program, StoreHost(store, program.functions.map { it.name }))
        vm.run()
        return vm.stringResult ?: ""
    }

    // ------------------------------------------------------------------
    // 私有变量：同名跨函数
    // ------------------------------------------------------------------

    /**
     * 两个函数各声明一个同名私有 `#DIM PRIV`，互相不得看见对方的实例。
     *
     * 这条用例同时钉住**句柄缓存的键**：若缓存只按变量名/字符串池下标（不带函数），
     * 第二次调用会命中第一次解析出来的私有实例，f2 的 22 会被写进 f1 的 PRIV，
     * 于是 `userVar("PRIV","F1")` 变成 22、`userVar("PRIV","F2")` 还是 0 —— 两处断言都会红。
     *
     * 两处刻意的写法：
     * * 名字避开系统变量：`P`/`Q`/`A`… 都是 [com.eratw.port.core.vars.SystemLayout] 的
     *   单字母 int1D 槽位，拿它们 `#DIM` 会被 `declareUserVar` 以「与系统变量重名」拒掉；
     * * `#DIM NAME` 在本层是**长度 1 的 1 维数组**（`argCount == 1`），所以指令形态是
     *   「压下标 0 → 压/读值，维数 = 1」，不是 0 维。
     */
    @Test
    fun `同名私有变量在两个函数里各写各的`() {
        val store = newStore()
        store.declareUserVar(DimParser.parse("#DIM", "PRIV", isPrivate = true), functionKey = "F1")
        store.declareUserVar(DimParser.parse("#DIM", "PRIV", isPrivate = true), functionKey = "F2")

        val ret = runOn(
            store,
            """
            .func main slots=4
              _CALL f1, 0, R1
              _CALL f2, 0, R2
              _RET R1

            .func f1 arity=0 slots=2
              _STK_PUSH_IMM 0
              _STK_PUSH_IMM 11
              _STK_STORE_VAR_I "PRIV", 1
              _STK_PUSH_IMM 0
              _STK_PUSH_VAR_I "PRIV", 1
              _STK_POP_SLOT_I R0
              _RET R0

            .func f2 arity=0 slots=2
              _STK_PUSH_IMM 0
              _STK_PUSH_IMM 22
              _STK_STORE_VAR_I "PRIV", 1
              _STK_PUSH_IMM 0
              _STK_PUSH_VAR_I "PRIV", 1
              _STK_POP_SLOT_I R0
              _RET R0
            """,
        )

        assertEquals(11L, ret, "f1 返回自己那份 PRIV")
        assertEquals(11L, store.userVar("PRIV", "F1")!!.readInt(0, 0), "f1 的私有 PRIV")
        assertEquals(22L, store.userVar("PRIV", "F2")!!.readInt(0, 0), "f2 的私有 PRIV")
    }

    /**
     * 全局 `#DIM` 与私有 `#DIM` 同名共存：私有优先（`userVar` 先查当前帧），
     * 没有私有声明的函数读到全局那份。
     *
     * 这是「缓存键带函数」的第二种反证形态：若缓存只按名字，f2（无私有声明）会命中
     * f1 解析出来的私有句柄，全局 MIX 永远是 0。
     *
     * f1 的返回值不被使用（main 只回 R2），所以它只 `_RET R0`（R0 恒为 0，不必落槽）。
     */
    @Test
    fun `同名全局与私有变量按当前函数分流`() {
        val store = newStore()
        store.declareUserVar(DimParser.parse("#DIM", "MIX", isPrivate = false))
        store.declareUserVar(DimParser.parse("#DIM", "MIX", isPrivate = true), functionKey = "F1")

        val ret = runOn(
            store,
            """
            .func main slots=4
              _CALL f1, 0, R1
              _CALL f2, 0, R2
              _RET R2

            .func f1 arity=0 slots=2
              _STK_PUSH_IMM 0
              _STK_PUSH_IMM 11
              _STK_STORE_VAR_I "MIX", 1
              _RET R0

            .func f2 arity=0 slots=2
              _STK_PUSH_IMM 0
              _STK_PUSH_IMM 22
              _STK_STORE_VAR_I "MIX", 1
              _STK_PUSH_IMM 0
              _STK_PUSH_VAR_I "MIX", 1
              _STK_POP_SLOT_I R0
              _RET R0
            """,
        )

        assertEquals(22L, ret, "f2 读回的是全局 MIX")
        assertEquals(22L, store.readInt("MIX", longArrayOf(0)), "全局 MIX")
        assertEquals(11L, store.userVar("MIX", "F1")!!.readInt(0, 0), "f1 的私有 MIX")
    }

    // ------------------------------------------------------------------
    // 写通道（T2-21 遗留的转绿项）
    // ------------------------------------------------------------------

    /**
     * `_STK_STORE_VAR_I` 带下标写系统变量，再读回：整条链路（含句柄写）必须闭环。
     *
     * 下标形态是这条用例的重点：值与下标**同在 int 栈**上（值在栈顶），弹出次序写反
     * （先 peek 值再弹下标）会静默写成 `A[7] = 2` 并让读回变成 0 —— `A[7]` 那条断言
     * 就是钉这个的，别删。
     */
    @Test
    fun `整型落变量按下标写入系统数组`() {
        val store = newStore()
        val ret = runOn(
            store,
            """
            .func main slots=2
              _STK_PUSH_IMM 2
              _STK_PUSH_IMM 7
              _STK_STORE_VAR_I "A", 1
              _STK_PUSH_IMM 2
              _STK_PUSH_VAR_I "A", 1
              _STK_POP_SLOT_I R0
              _RET R0
            """,
        )
        assertEquals(7L, ret, "写完立即读回")
        assertEquals(7L, store.readInt("A", longArrayOf(2)), "池里的 A[2]")
        assertEquals(0L, store.readInt("A", longArrayOf(0)), "相邻元素不得被写到")
        assertEquals(0L, store.readInt("A", longArrayOf(7)), "下标不得被当成元素位置")
    }

    /** `_STK_STORE_VAR_S` 写字符串变量（走 `VariableWriter.writeStr` + 句柄），含下标形态。 */
    @Test
    fun `字符串落变量写入用户数组`() {
        val store = newStore()
        store.declareUserVar(DimParser.parse("#DIMS", "TX, 4", isPrivate = false))
        val ret = runStrOn(
            store,
            """
            .func main slots=2
              _STK_PUSH_IMM 0
              _STK_PUSH_STR "hi"
              _STK_STORE_VAR_S "TX", 1
              _STK_PUSH_IMM 2
              _STK_PUSH_STR "yo"
              _STK_STORE_VAR_S "TX", 1
              _STK_PUSH_IMM 2
              _STK_PUSH_VAR_S "TX", 1
              _STK_POP_SLOT_S R0
              _RETS R0
            """,
        )
        assertEquals("yo", ret, "TX[2] 必须等于刚写进去的 yo")
        assertEquals("hi", store.readStr("TX", longArrayOf(0)), "TX[0]")
        assertEquals("yo", store.readStr("TX", longArrayOf(2)), "TX[2]")
        assertEquals("", store.readStr("TX", longArrayOf(1)), "没写过的位置保持空串")
    }

    /** `++/--` 走 `_STK_PLUS_VAR_I`（读改写一体），写在私有变量上也要落到正确实例。 */
    @Test
    fun `读改写指令作用在私有变量上`() {
        val store = newStore()
        store.declareUserVar(DimParser.parse("#DIM", "PCNT", isPrivate = true), functionKey = "MAIN")
        val ret = runOn(
            store,
            """
            .func main slots=2
              _STK_PUSH_IMM 0
              _STK_PUSH_IMM 5
              _STK_STORE_VAR_I "PCNT", 1
              _STK_PUSH_IMM 0
              _STK_PLUS_VAR_I "PCNT", 1, -1
              _STK_POP_SLOT_I R0
              _RET R0
            """,
        )
        assertEquals(4L, ret, "5 + (-1) = 4")
        assertEquals(4L, store.userVar("PCNT", "MAIN")!!.readInt(0, 0), "私有 PCNT 必须被写回")
    }

    // ------------------------------------------------------------------
    // 报错文案：按指令 == 按名（契约 §0.2 第 4 条）
    // ------------------------------------------------------------------

    /**
     * 三条典型错误的文案对拍：未定义、类型不符、常量/伪变量不可写。
     *
     * 判据是**逐字相等**而不是「包含」：降级后的指令是同一份 store 代码的另一个入口，
     * 任何文案分叉都会让 M10 的错误面板与 T2-53 的对拍闸出现"同一错误两种措辞"。
     */
    @Test
    fun `栈码变量的报错文案与按名入口逐字相同`() {
        val store = newStore()

        assertSameError(store, "NOPE", "_STK_PUSH_VAR_I \"NOPE\", 0", "_STK_STORE_VAR_I \"NOPE\", 0")
        assertSameError(store, "STR", "_STK_PUSH_VAR_I \"STR\", 0", "_STK_STORE_VAR_I \"STR\", 0")
        assertSameError(store, "INT_MAX", null, "_STK_STORE_VAR_I \"INT_MAX\", 0")
    }

    /**
     * 对拍一条错误：[name] 是变量名，[readSrc]/[storeSrc] 是两条指令形态（null = 该形态不适用）。
     *
     * 指令形态一律用 `dim = 0`（无下标），所以按名侧的 `indices` 也是空数组 —— 两侧走的是
     * 同一组校验分支才有可比性；带下标的正向用例另见上面三条。
     * 写形态前面的 `_STK_PUSH_IMM 1` 是待写的值，保证走到变量层校验而不是栈下溢。
     */
    private fun assertSameError(store: VariableStore, name: String, readSrc: String?, storeSrc: String?) {
        val none = LongArray(0)
        if (readSrc != null) {
            val byName = assertFailsWith<CodeEE> { store.readInt(name, none) }
            val byOp = assertFailsWith<CodeEE> {
                runOn(store, ".func main slots=2\n  $readSrc\n  _HALT")
            }
            assertEquals(byName.message, byOp.message, "读：$name 的文案必须与按名路径逐字相同")
        }
        if (storeSrc != null) {
            val byName = assertFailsWith<CodeEE> { store.writeInt(name, none, 1) }
            val byOp = assertFailsWith<CodeEE> {
                runOn(store, ".func main slots=2\n  _STK_PUSH_IMM 1\n  $storeSrc\n  _HALT")
            }
            assertEquals(byName.message, byOp.message, "写：$name 的文案必须与按名路径逐字相同")
        }
    }

    /**
     * 未定义变量在**装载后**才被声明，句柄缓存不得把它钉死成「永远解析不出」。
     *
     * 手法：同一个 VM 先跑一次必然失败的读（此时 `resolveInt` 返回 NONE 且不入缓存），
     * 再声明同名变量，然后 `run()` 同一条指令必须读到值。缓存住 NONE（或缓存成
     * 「未定义」这个结论）的话第二次仍是「未定义的变量」。
     */
    @Test
    fun `解析不出的句柄不进缓存_迟到的声明能被看见`() {
        val store = newStore()
        val program = Assembler.assemble(
            """
            .func main slots=2
              _STK_PUSH_IMM 0
              _STK_PUSH_VAR_I "LATE", 1
              _STK_POP_SLOT_I R0
              _RET R0
            """,
        )
        val vm = VirtualMachine(program, StoreHost(store, program.functions.map { it.name }))

        val first = assertFailsWith<CodeEE> { vm.run() }
        assertTrue("未定义的变量 LATE" in first.message!!, "首次必须报未定义，实际: ${first.message}")

        store.declareUserVar(DimParser.parse("#DIM", "LATE", isPrivate = false))
        store.writeInt("LATE", longArrayOf(0), 9)
        assertEquals(9L, vm.run(), "声明之后同一条指令必须读到 9")
    }
}
