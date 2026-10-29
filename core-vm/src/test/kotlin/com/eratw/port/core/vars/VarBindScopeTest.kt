package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.sem.iVar
import com.eratw.port.core.sem.sVar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 绑定缓存的**作用域判据**语义钉子（存储层 + 表达式层）。
 *
 * ## 为什么需要这组用例
 *
 * `bindOf` 的历史形态把 `currentPrivMap` 当无条件判据：名字解析结果一律绑当前帧的私有
 * 变量表身份。问题在于那张表在**每次** `enterFunction`/`leaveFunction` 都会换身份，
 * 于是每跨一次调用，热变量的缓存就整片作废、退化成 4 次哈希查表 —— `t61.jfr` 实测
 * `lookupUserVar` 仍占哈希采样的 31.9%。
 *
 * 现在只有「在**某个**函数里被声明为私有」的名字才绑作用域（`privateNames` +
 * `nameScopedOf`），其余名字跨调用恒命中。这组用例钉的就是这条判据的**正确性边界**：
 * - 判据放宽后，同名全局/私有仍然按当前帧分流（不能因为「不绑作用域」而串味）；
 * - 私有变量的解析结果不能跨函数泄漏；
 * - 迟到的私有声明必须让此前「解析成全局 / 解析成不存在」的缓存失效；
 * - 完全没有私有声明时，跨调用来回进出不得改变任何值。
 *
 * ## 为什么分两层
 *
 * 同一条判据有两个消费者：存储层的按名字缓存（[VariableStore.bindOf]）与表达式层的
 * **访问点**缓存（`VarRef` 的站点字段）。两者的失效时机不同 —— 前者在声明点整表清空，
 * 后者靠 `(读取方, 作用域)` 键自校验 —— 所以必须**分别**钉。仅测存储层会让站点缓存的
 * 判据漏洞（见 `同一访问点先全局绑定再进私有作用域必须重解析`）静默通过。
 *
 * 值语义的端到端形态（经 VM 执行 `_STK_*` 指令）见
 * [com.eratw.port.core.code.VmVarBindingTest]；本文件只压解析判据。
 */
class VarBindScopeTest {

    private fun globalInt(store: VariableStore, name: String, value: Long) {
        store.declareUserVar(DimParser.parse("#DIM", name, isPrivate = false))
        store.writeInt(name, longArrayOf(0), value)
    }

    /**
     * 声明一份私有整数变量并写入初值。
     *
     * 写入**必须在 `functionKey` 帧内**：`writeInt` 按当前帧解析名字，帧外会解析到全局
     * （或「未定义」），私有实例仍是 0 —— 这正是本文件要压的那条判据，辅助函数不能绕过它。
     */
    private fun privateInt(store: VariableStore, name: String, functionKey: String, value: Long) {
        store.declareUserVar(DimParser.parse("#DIM", name, isPrivate = true), functionKey = functionKey)
        store.enterFunction(functionKey)
        store.writeInt(name, longArrayOf(0), value)
        store.leaveFunction()
    }

    /**
     * 全局 `MIXG` 与 `F1` 的私有 `MIXG` 同名：进出 `F1` 多次，两种作用域各自稳定。
     *
     * 若缓存把「私有解析结果」当成了作用域无关的结果，`F1` 之外的读会拿到 11；
     * 若缓存把「全局解析结果」当成绑 `F1` 的，回到 `F1` 会拿到 22。两种串味都会红。
     */
    @Test
    fun `同名全局与私有变量反复进出作用域都不串味`() {
        val store = VariableStore()
        globalInt(store, "MIXG", 22)
        privateInt(store, "MIXG", "F1", 11)

        repeat(3) { round ->
            store.enterFunction("F1")
            assertEquals(11L, store.readInt("MIXG", longArrayOf(0)), "第 $round 轮 F1 内应读私有")
            store.leaveFunction()
            assertEquals(22L, store.readInt("MIXG", longArrayOf(0)), "第 $round 轮 F1 外应读全局")
        }
        // 私有实例自身也没有被外部写污染。
        assertEquals(11L, store.userVar("MIXG", "F1")!!.readInt(0, 0))
    }

    /**
     * 三个函数各有一份同名私有 `MULTI`，外加一份全局：四种作用域轮流读。
     *
     * 这是「缓存键必须含作用域」的最强反证形态 —— 任意两份被混同，期望值立刻对不上。
     */
    @Test
    fun `三份同名私有变量与全局互不可见`() {
        val store = VariableStore()
        globalInt(store, "MULTI", 100)
        privateInt(store, "MULTI", "FA", 1)
        privateInt(store, "MULTI", "FB", 2)
        privateInt(store, "MULTI", "FC", 3)

        fun readIn(fn: String, want: Long) {
            store.enterFunction(fn)
            assertEquals(want, store.readInt("MULTI", longArrayOf(0)), "函数 $fn 内的 MULTI")
            store.leaveFunction()
        }

        // 顺序刻意交错：让缓存不断在四个作用域之间切换。
        readIn("FA", 1)
        assertEquals(100L, store.readInt("MULTI", longArrayOf(0)), "全局")
        readIn("FC", 3)
        readIn("FA", 1)
        readIn("FB", 2)
        assertEquals(100L, store.readInt("MULTI", longArrayOf(0)), "全局")
        readIn("FC", 3)
    }

    /**
     * 迟到声明：名字先解析成「未定义」，随后在同一函数里被声明为私有。
     *
     * `bindOf` 会把「确实不存在」也缓存（`BIND_MISSING`）。若声明点不作废缓存，这里会
     * 永远抛「未定义的变量」。`privateNames` 同时必须在这条路径上登记，否则后续跨调用
     * 的缓存会把该名字钉成「作用域无关的不存在」。
     */
    @Test
    fun `先解析为未定义再声明为私有必须立刻可见`() {
        val store = VariableStore()
        store.enterFunction("LATE_FN")

        val first = assertFailsWith<CodeEE> { store.readInt("LATEP", longArrayOf(0)) }
        assertTrue("未定义的变量 LATEP" in first.message!!, "首次必须报未定义，实际: ${first.message}")

        store.declareUserVar(DimParser.parse("#DIM", "LATEP", isPrivate = true), functionKey = "LATE_FN")
        store.writeInt("LATEP", longArrayOf(0), 5)
        assertEquals(5L, store.readInt("LATEP", longArrayOf(0)), "声明后立即可见")

        store.leaveFunction()
        // 离开声明函数后这个名字仍然不存在（私有变量不外泄）。
        assertFailsWith<CodeEE> { store.readInt("LATEP", longArrayOf(0)) }
        store.enterFunction("LATE_FN")
        assertEquals(5L, store.readInt("LATEP", longArrayOf(0)), "再次进入仍读私有实例")
    }

    /**
     * 迟到声明：名字先按**全局**解析并写入，随后声明**私有**同名变量。
     *
     * 与上一条的区别是缓存里已有一条「成功解析成全局」的条目 —— 它必须被声明点作废，
     * 否则私有作用域里会读到全局的值（静默错值，比抛错更难发现）。
     */
    @Test
    fun `先按全局解析再声明私有必须切到私有实例`() {
        val store = VariableStore()
        globalInt(store, "SWITCHV", 70)
        assertEquals(70L, store.readInt("SWITCHV", longArrayOf(0)), "先按全局读一次（填缓存）")

        store.declareUserVar(DimParser.parse("#DIM", "SWITCHV", isPrivate = true), functionKey = "FS")
        store.enterFunction("FS")
        store.writeInt("SWITCHV", longArrayOf(0), 7)
        assertEquals(7L, store.readInt("SWITCHV", longArrayOf(0)), "私有作用域必须读私有")
        store.leaveFunction()
        assertEquals(70L, store.readInt("SWITCHV", longArrayOf(0)), "全局仍是 70")
        assertEquals(70L, store.readInt("SWITCHV", longArrayOf(0)), "重复读走缓存也不能变")
    }

    /**
     * 完全没有私有声明时，跨调用来回进出不得影响任何值（放宽作用域判据的正面用例）。
     *
     * 这条同时覆盖「空 `privateNames` 短路」：纯系统/全局变量的脚本连大写记忆表都不碰。
     */
    @Test
    fun `无私有声明时跨调用反复进出值稳定`() {
        val store = VariableStore()
        globalInt(store, "PLAINV", 123)

        var expect = 123L
        repeat(50) { i ->
            store.enterFunction("CALLER$i")
            assertEquals(expect, store.readInt("PLAINV", longArrayOf(0)), "第 $i 次进入后读")
            expect = 123L + i
            store.writeInt("PLAINV", longArrayOf(0), expect)
            assertEquals(expect, store.readInt("PLAINV", longArrayOf(0)), "第 $i 次写后读")
            store.leaveFunction()
            assertEquals(expect, store.readInt("PLAINV", longArrayOf(0)), "第 $i 次离开后读")
        }
    }

    /**
     * 系统变量（`MONEY`）在私有作用域里也必须解析成系统变量 —— 它永远不可能私有
     * （`declareUserVar` 对系统变量名直接抛「与系统变量重名」），所以走的是
     * 「作用域无关」那一支。
     */
    @Test
    fun `系统变量在私有作用域内不受影响`() {
        val store = VariableStore()
        privateInt(store, "PRIVSYS", "FP", 1)
        store.writeInt("MONEY", longArrayOf(0), 4321)

        store.enterFunction("FP")
        assertEquals(4321L, store.readInt("MONEY", longArrayOf(0)), "私有帧内读系统变量")
        assertEquals(1L, store.readInt("PRIVSYS", longArrayOf(0)), "同名私有照旧")
        store.leaveFunction()
        assertEquals(4321L, store.readInt("MONEY", longArrayOf(0)), "离开后读系统变量")
    }

    // ---------------------------------------------------------------------
    // 表达式层：`VarRef` 访问点缓存的同一套判据
    // ---------------------------------------------------------------------

    /**
     * **同一访问点**先在全局作用域绑定、再进私有作用域求值。
     *
     * 这是 `nameScoped` 判据唯一的不可替代用例。首版按「解析出的句柄指向的条目是否私有」
     * 取判据（`UserVar.spec.isPrivate`），错在拿**解析结果**的属性回答**名字**的问题：
     * 全局作用域下 `SITEV` 解析到全局实例 ⇒ 判据 false ⇒ 站点把句柄当作跨作用域可复用
     * 缓存；随后同一个站点在 `F1` 里求值就会把全局实例当成 `F1` 的私有实例读（**静默
     * 错值**，不抛错）。
     *
     * 顺序刻意是「先全局、后私有」—— 反过来两条实现都会通过，测不出差别。
     */
    @Test
    fun `同一访问点先全局绑定再进私有作用域必须重解析`() {
        val store = VariableStore()
        globalInt(store, "SITEV", 22)
        privateInt(store, "SITEV", "F1", 11)
        val ctx = TestEvalContext(store)
        val site = iVar("SITEV", 0)

        assertEquals(22L, site.getIntValue(ctx), "全局作用域首次求值（站点在这里绑定）")
        store.enterFunction("F1")
        assertEquals(11L, site.getIntValue(ctx), "进入 F1 后必须读私有实例")
        store.leaveFunction()
        assertEquals(22L, site.getIntValue(ctx), "离开 F1 后必须回到全局实例")
        repeat(4) {
            store.enterFunction("F1")
            assertEquals(11L, site.getIntValue(ctx), "第 2+ 轮 F1 内")
            store.leaveFunction()
            assertEquals(22L, site.getIntValue(ctx), "第 2+ 轮 F1 外")
        }
    }

    /**
     * 同一个站点在三份同名私有变量 + 全局之间轮流求值，任意两份混同都会立刻对不上。
     *
     * 站点缓存的键是 `(读取方, 作用域)`；这条用例把「键里少一维」的退化形态全部排除。
     */
    @Test
    fun `同一访问点在三份同名私有变量间轮流求值`() {
        val store = VariableStore()
        globalInt(store, "SITEM", 100)
        privateInt(store, "SITEM", "SA", 1)
        privateInt(store, "SITEM", "SB", 2)
        privateInt(store, "SITEM", "SC", 3)
        val ctx = TestEvalContext(store)
        val site = iVar("SITEM", 0)

        fun inFn(fn: String, want: Long) {
            store.enterFunction(fn)
            assertEquals(want, site.getIntValue(ctx), "函数 $fn 内")
            store.leaveFunction()
        }

        assertEquals(100L, site.getIntValue(ctx), "全局")
        inFn("SA", 1)
        inFn("SC", 3)
        assertEquals(100L, site.getIntValue(ctx), "全局（穿插一次）")
        inFn("SB", 2)
        inFn("SA", 1)
        inFn("SA", 1)
        inFn("SC", 3)
        assertEquals(100L, site.getIntValue(ctx), "全局（结尾）")
    }

    /**
     * 字符串变量走另一条缓存槽（`bindStr`），作用域判据必须一致。
     *
     * `VarRef` 的整型与字符串句柄是两个独立字段，任一字段的判据写错都只影响一半访问。
     */
    @Test
    fun `字符串访问点的作用域判据与整型一致`() {
        val store = VariableStore()
        store.declareUserVar(DimParser.parse("#DIMS", "SITESTR", isPrivate = false))
        store.writeStr("SITESTR", longArrayOf(0), "全局串")
        store.declareUserVar(
            DimParser.parse("#DIMS", "SITESTR", isPrivate = true),
            functionKey = "SF",
        )
        val ctx = TestEvalContext(store)
        val site = sVar("SITESTR", 0)

        assertEquals("全局串", site.getStrValue(ctx), "全局作用域首次求值")
        store.enterFunction("SF")
        store.writeStr("SITESTR", longArrayOf(0), "私有串")
        assertEquals("私有串", site.getStrValue(ctx), "私有帧内必须读私有实例")
        store.leaveFunction()
        assertEquals("全局串", site.getStrValue(ctx), "离开后必须回到全局实例")
    }

    /**
     * 名字**没被任何函数**声明为私有时，同一站点跨 60 个不同函数键反复进出值不变。
     *
     * 这是判据放宽的正面用例：若站点把作用域无条件写进键，每换一个函数键就重解析一次
     * （结果仍正确，只是白付 4 次哈希查表）；本用例保证值语义在「完全不绑作用域」的
     * 分支上也是对的。
     */
    @Test
    fun `无私有声明的名字跨任意函数键求值都稳定`() {
        val store = VariableStore()
        globalInt(store, "SITEP", 77)
        val ctx = TestEvalContext(store)
        val site = iVar("SITEP", 0)

        repeat(60) { i ->
            store.enterFunction("FN$i")
            assertEquals(77L, site.getIntValue(ctx), "第 $i 个函数内")
            store.leaveFunction()
            assertEquals(77L, site.getIntValue(ctx), "第 $i 个函数外")
        }
    }

    /**
     * 站点缓存与按名路径的报错**逐字相同**（契约要求：越界/缺下标的文案不因走哪条路而变）。
     *
     * 0 下标形态（`SITEZ` 不带下标）在 1D 变量上是「下标个数不对」的硬错误；按名路径由
     * `checkUserElement` 抛出，站点缓存路径必须在 `readIntH` 里给出同一句话 —— 否则
     * 同一条 ERB 语句会因为「名字是否已绑定」而显示两种错误。
     */
    @Test
    fun `缺少下标时站点缓存与按名路径报错逐字相同`() {
        val store = VariableStore()
        globalInt(store, "SITEZ", 5)
        val ctx = TestEvalContext(store)

        val byName = assertFailsWith<CodeEE> { store.readInt("SITEZ", longArrayOf()) }
        val bySite = assertFailsWith<CodeEE> { iVar("SITEZ").getIntValue(ctx) }
        assertEquals(byName.message, bySite.message, "两条路径的报错文案必须一致")

        // 第二次走站点缓存（句柄已绑定）仍必须是同一句话，不能退化成别的异常。
        val again = assertFailsWith<CodeEE> { iVar("SITEZ").getIntValue(ctx) }
        assertEquals(byName.message, again.message, "已绑定句柄的路径文案同样不变")
    }

    /**
     * 同一访问点跨**变量池**复用时，换池必须把两个方向的缓存一起作废。
     *
     * 「同一个 AST 配不同变量池」是设计内场景（`Vm.varHandleOwner` 的注释：测试夹具、
     * 宿主换 store 重跑）——句柄是**变量池私有**的下标（`VariableStore.handleEntries`），
     * 跨池无意义。这条用例构造出最刁钻的序列：换池重绑发生在一个方向、缓存命中发生在
     * 另一个方向：
     *
     *  1. 池 A：str 变量 `CROSS` 的 int 视图先求值 —— 类型错，但句柄已进站点缓存；
     *  2. 池 B：同一站点按 str 读 —— 换池重绑，str 槽正确更新；
     *  3. 池 B：再按 int 读 —— 若换池只重绑了 str 槽，int 槽里还挂着**池 A 的句柄号**，
     *     恰好撞上池 B 先 intern 的 `PADV`，静默返回 999（不抛错，比抛错更难发现）。
     *
     * 正确行为：换池两个方向一起作废，int 槽重绑后与按名路径同样抛「是字符串变量」。
     */
    @Test
    fun `同一访问点跨变量池重绑不得残留旧池句柄`() {
        val storeA = VariableStore()
        storeA.declareUserVar(DimParser.parse("#DIMS", "CROSS", isPrivate = false))
        storeA.writeStr("CROSS", longArrayOf(0), "A池")

        val storeB = VariableStore()
        storeB.declareUserVar(DimParser.parse("#DIM", "PADV", isPrivate = false))
        storeB.writeInt("PADV", longArrayOf(0), 999)
        storeB.declareUserVar(DimParser.parse("#DIMS", "CROSS", isPrivate = false))
        storeB.writeStr("CROSS", longArrayOf(0), "B池")
        // 抢占池 B 的句柄号：让「池 A 里 CROSS 的句柄号」与「池 B 里 PADV 的句柄号」相同，
        // 旧实现残留的跨池句柄会精确读成 PADV 的值（而不是靠越界报错暴露）。
        storeB.resolveInt("PADV")

        val ctxA = TestEvalContext(storeA)
        val ctxB = TestEvalContext(storeB)
        val site = iVar("CROSS", 0)

        // 1：池 A 按 int 读 → 类型错（句柄进站点缓存）。
        val aByName = assertFailsWith<CodeEE> { storeA.readInt("CROSS", longArrayOf(0)) }
        val aBySite = assertFailsWith<CodeEE> { site.getIntValue(ctxA) }
        assertEquals(aByName.message, aBySite.message, "池 A 按名/站点报错一致")

        // 2：池 B 按 str 读（换池重绑；正确实现与「只重绑当前方向」的实现这一步都返回 B 池的值）。
        assertEquals("B池", site.getStrValue(ctxB), "换池后 str 方向必须重绑到池 B")

        // 3：池 B 按 int 读 —— 键完备的缓存必须重绑并抛类型错；
        //    残留旧池句柄的实现会读成 PADV 的 999、不抛错。
        val bByName = assertFailsWith<CodeEE> { storeB.readInt("CROSS", longArrayOf(0)) }
        val bBySite = assertFailsWith<CodeEE> { site.getIntValue(ctxB) }
        assertEquals(
            bByName.message, bBySite.message,
            "池 B 按名/站点报错一致（若残留旧池句柄会静默读到 PADV=999）",
        )
        assertEquals(999L, storeB.readInt("PADV", longArrayOf(0)), "PADV 本身未被动过")
    }
}
