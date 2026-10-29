package com.eratw.port.core.vars

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableHandles
import com.eratw.port.core.sem.TestEvalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T2-11 句柄层的差分验收（T2-23 的装期绑定消费它）。
 *
 * ## 判据为什么是「逐字相同」而不是「大致相等」
 *
 * 句柄路径不是按名路径的复制品，而是**同一份函数体**的另一个入口
 * （`VariableStore.readIntEntry` 一族）。因此本用例的失败只有三种可能，每一种都是真缺陷：
 * 1. 句柄解析到了别的条目（私有变量跨函数串味、用户变量与系统变量撞号）；
 * 2. 句柄表的分配/回收有洞（同一变量两次解析得到不同句柄，或不同变量得到同一句柄）；
 * 3. 抛错时机与文案分叉（同一畸形访问在两条路径上给不同 `CodeEE`）。
 *
 * ## 覆盖口径
 *
 * 全部系统变量 × 全部已声明用户变量 × int/str 两个读口 × 随机下标（含越界与负数），
 * 直到满 100,000 次对拍。**伪变量（PSEUDO）排除**：`RAND` 一类每次求值本就不同，
 * 两条路径先后调用必然不相等，那不是缺陷。
 */
class VariableHandleTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 线性同余（固定种子）：测试要可复现，禁止 `kotlin.random` 一类无种子随机源。 */
    private class Lcg(private var s: Long = 0x2545F4914F6CDD1DL) {
        fun next(bound: Int): Int {
            s = s * 6364136223846793005L + 1442695040888963407L
            val v = (s ushr 33).toInt()
            return if (bound <= 0) 0 else Math.floorMod(v, bound)
        }
    }

    private fun newStore(): VariableStore = VariableStore().apply {
        randProvider = com.eratw.port.core.rand.Sfmt19937(0L)
    }

    /**
     * 带用户变量的池：1D/2D 整数、1D 字符串、以及两个函数各自同名的私有变量。
     *
     * 私有变量必须在拿到句柄前 `enterFunction` —— `resolveInt` 按**当前帧**解析，
     * 这是私有变量语义（`userVar` 先查 `privateVars[topFrame().funcKey]`）。
     */
    private fun richStore(): VariableStore {
        val store = newStore()
        store.declareUserVar(DimParser.parse("#DIM", "UI, 3", isPrivate = false))
        store.declareUserVar(DimParser.parse("#DIM", "UI2, 2, 3", isPrivate = false))
        store.declareUserVar(DimParser.parse("#DIMS", "US, 3", isPrivate = false))
        store.declareUserVar(DimParser.parse("#DIM", "PV, 3", isPrivate = true), functionKey = "F1")
        store.declareUserVar(DimParser.parse("#DIMS", "PVS, 3", isPrivate = true), functionKey = "F1")
        return store
    }

    /** 求值结果或错误文案的统一字符串（差分只比较这两者）。 */
    private fun outcome(block: () -> Any?): String =
        try {
            "V:" + block()
        } catch (e: CodeEE) {
            "E:" + e.message
        }

    // ------------------------------------------------------------------
    // 1 全量差分
    // ------------------------------------------------------------------

    /**
     * 三方对拍：按名 `readInt` / 按句柄 `readIntH` / **按槽位**（`VarRef` 站点缓存）。
     *
     * 第三条腿是 T2-61 新增的路径：`VarRef` 自己缓存「名字 → 句柄」，命中时等价于一次
     * 数组直读。它与句柄路径共用 `readIntEntry` 同一份函数体，差别只在「名字 → 条目」这
     * 一步走的是站点字段还是 `bindOf`；三方差分因此能同时抓住
     * 1. 站点缓存把另一个名字/另一个作用域的句柄当成本站点的（串味）；
     * 2. 站点缓存没跟着作用域变化重解析（私有变量跨函数泄漏）；
     * 3. 两条路径的校验与报错分叉。
     *
     * 装置上刻意做两件事：
     * - **站点复用**（`sites` 按「名字 + 下标元组 + int/str」做键）：否则每次迭代都新建
     *   `VarRef`，站点缓存永远冷，第三条腿就退化成句柄路径的复制品，测不出东西；
     * - **每三次迭代有一次在 `F1` 帧内**：`PV`/`PVS` 只在 `F1` 里可见，这样同一个站点会
     *   在「有私有作用域」和「无私有作用域」之间来回切换，作用域键被真正压到。
     */
    @Test
    fun `按名按句柄按槽位三方逐值逐错一致_十万次对拍`() {
        val store = richStore()
        val lcg = Lcg()
        val defs = store.registry.all()
            .filter { it.storage != Storage.PSEUDO }
            .sortedBy { it.name }
        val names = ArrayList<String>(defs.size + 8)
        defs.forEach { names.add(it.name) }
        names.add("UI")
        names.add("UI2")
        names.add("US")

        val siteCtx = TestEvalContext(store)
        val sites = HashMap<String, VarRef>(256)
        fun siteOf(name: String, idx: LongArray, wantStr: Boolean): VarRef {
            val key = buildString {
                append(name).append('/').append(if (wantStr) 'S' else 'I')
                for (v in idx) append('/').append(v)
            }
            return sites.getOrPut(key) {
                VarRef(
                    name,
                    idx.map { IntLiteral(it) },
                    operandType = if (wantStr) OperandType.STR else OperandType.INT,
                )
            }
        }

        var probes = 0
        var compared = 0
        while (probes < 100_000) {
            val name = names[lcg.next(names.size)]
            // 下标个数按声明形态给：0 维给空数组，角色变量多一位角色位置。
            val def = store.registry.def(name)
            val n = def?.argCount ?: lcg.next(3)
            // 合法域内为主，掺 1/8 的边界外（-1 / 恰好等于长度）来覆盖校验分支。
            val idx = LongArray(n) { i ->
                val dimLen = when {
                    def == null -> 4
                    def.isCharacterData && i == 0 -> 4
                    else -> def.dims.getOrElse(i - (if (def.isCharacterData) 1 else 0)) { 4 }
                }
                val span = dimLen.coerceAtLeast(1)
                if (lcg.next(8) == 0) {
                    if (lcg.next(2) == 0) -1L else dimLen.toLong()
                } else {
                    lcg.next(span).toLong()
                }
            }

            val inFrame = probes % 3 == 0
            if (inFrame) store.enterFunction("F1")
            try {
                val hInt = store.resolveInt(name)
                val hStr = store.resolveStr(name)
                if (hInt != VariableHandles.NONE) {
                    val byName = outcome { store.readInt(name, idx) }
                    assertEquals(
                        byName,
                        outcome { store.readIntH(hInt, idx) },
                        "readInt 分叉：name=$name idx=${idx.joinToString()} frame=$inFrame",
                    )
                    assertEquals(
                        byName,
                        outcome { siteOf(name, idx, false).getIntValue(siteCtx) },
                        "readInt 按槽位分叉：name=$name idx=${idx.joinToString()} frame=$inFrame",
                    )
                    compared++
                }
                if (hStr != VariableHandles.NONE) {
                    val byName = outcome { store.readStr(name, idx) }
                    assertEquals(
                        byName,
                        outcome { store.readStrH(hStr, idx) },
                        "readStr 分叉：name=$name idx=${idx.joinToString()} frame=$inFrame",
                    )
                    assertEquals(
                        byName,
                        outcome { siteOf(name, idx, true).getStrValue(siteCtx) },
                        "readStr 按槽位分叉：name=$name idx=${idx.joinToString()} frame=$inFrame",
                    )
                    compared++
                }
            } finally {
                if (inFrame) store.leaveFunction()
            }
            probes++
        }
        assertTrue(compared >= 100_000, "对拍次数不足：$compared（探测 $probes）")
        assertTrue(sites.size > 32, "站点数过少（${sites.size}），第三条腿没被复用")
    }

    // ------------------------------------------------------------------
    // 2 句柄本身的性质
    // ------------------------------------------------------------------

    /** 同一变量两次解析必须得到同一句柄（表里不能每次新建条目）。 */
    @Test
    fun `同一变量的句柄稳定`() {
        val store = richStore()
        assertEquals(store.resolveInt("A"), store.resolveInt("A"))
        assertEquals(store.resolveInt("UI"), store.resolveInt("UI"))
        // 两个不同变量不得撞号
        assertNotEquals(store.resolveInt("A"), store.resolveInt("B"))
    }

    /** 未定义名字返回 [VariableHandles.NONE]，**不抛错**（报文归按名路径）。 */
    @Test
    fun `未定义名字解析为 NONE 不抛错`() {
        val store = newStore()
        assertEquals(VariableHandles.NONE, store.resolveInt("NO_SUCH_VAR"))
        assertEquals(VariableHandles.NONE, store.resolveStr("NO_SUCH_VAR"))
    }

    /** 坏句柄（哨兵/越界）显式失败：跨池复用与调用方算错都必须炸在变量层，不能裸 IOOBE。 */
    @Test
    fun `坏句柄显式失败`() {
        val store = newStore()
        for (h in listOf(VariableHandles.NONE, -1, 10_000)) {
            val e = assertFailsWith<CodeEE> { store.readIntH(h, LongArray(0)) }
            assertTrue("变量句柄 $h 无效" in e.message!!, "句柄 $h 的文案：${e.message}")
        }
    }

    /**
     * 私有变量按函数区分：两个函数各声明同名 `PV`，句柄必须指向各自实例。
     *
     * 这条同时是「句柄缓存键必须带函数」的底层证据：句柄本身就已经是函数相关的值。
     */
    @Test
    fun `同名私有变量在不同函数解析到不同句柄`() {
        val store = richStore()
        store.declareUserVar(DimParser.parse("#DIM", "PV, 3", isPrivate = true), functionKey = "F2")

        store.enterFunction("F1")
        val h1 = store.resolveInt("PV")
        store.writeIntH(h1, "PV", longArrayOf(0), 11)
        store.leaveFunction()

        store.enterFunction("F2")
        val h2 = store.resolveInt("PV")
        store.writeIntH(h2, "PV", longArrayOf(0), 22)
        store.leaveFunction()

        assertNotEquals(h1, h2, "同名私有变量在两个函数里必须是两个条目")
        assertEquals(11L, store.userVar("PV", "F1")!!.readInt(0, 0))
        assertEquals(22L, store.userVar("PV", "F2")!!.readInt(0, 0))
    }

    /**
     * 写观察口的名字必须由调用方传**源拼写**。
     *
     * `VarWriteProbe.watches` 是大小写敏感的名单判定（`name in watchSet`）。按句柄写若
     * 擅自改用变量表里的规范名，调试断点会在降级后静默失效 —— 这条用例把「传进来的
     * 拼写被原样使用」钉死。
     */
    @Test
    fun `按句柄写把源拼写交给观察口`() {
        val store = newStore()
        val seen = ArrayList<String>()
        store.writeProbe = object : VarWriteProbe {
            override fun watches(name: String): Boolean = name == "a" || name == "A"
            override fun onWriteInt(name: String, indices: LongArray, value: Long) {
                seen.add(name)
            }
        }
        val h = store.resolveInt("A")
        store.writeIntH(h, "a", longArrayOf(0), 1)
        store.writeIntH(h, "A", longArrayOf(0), 2)
        store.writeInt("A", longArrayOf(0), 3)
        assertEquals(listOf("a", "A", "A"), seen, "观察口必须看到调用方原样传入的拼写")
    }

    /** `plusIntH` 与 `plusInt` 的返回值与副作用必须一致（含 `++/--` 的读改写语义）。 */
    @Test
    fun `按句柄自增与按名自增一致`() {
        val store = newStore()
        val h = store.resolveInt("A")
        store.writeInt("A", longArrayOf(1), 41)
        assertEquals(42L, store.plusIntH(h, longArrayOf(1), 1))
        assertEquals(42L, store.readInt("A", longArrayOf(1)))
        assertEquals(41L, store.plusInt("A", longArrayOf(1), -1))
        assertEquals(41L, store.readIntH(h, longArrayOf(1)))
    }
}
