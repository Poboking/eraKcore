package com.eratw.port.core.vars

import com.eratw.port.core.expr.OperandType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 用户变量：#DIM 静态/动态/常量/角色/REF、局部变量按函数隔离、作用域嵌套。 */
class UserVarTest {

    private fun idx(vararg v: Long) = v

    private fun dim(body: String, isPrivate: Boolean = false) =
        DimParser.parse("#DIM", body, isPrivate)

    private fun dims(body: String, isPrivate: Boolean = false) =
        DimParser.parse("#DIMS", body, isPrivate)

    // ---------------- 静态 ----------------

    @Test
    fun `静态整数变量与初值`() {
        val s = VariableStore()
        s.declareUserVar(dim("UX, 3 = 1, 2, 3"))
        assertEquals(3L, s.readInt("UX", idx(2)))
        s.writeInt("UX", idx(0), 9)
        assertEquals(9L, s.readInt("UX", idx(0)))
        assertFailsWith<CodeEE> { s.readInt("UX", idx(3)) }
    }

    @Test
    fun `静态字符串变量与初值`() {
        val s = VariableStore()
        s.declareUserVar(dims("US, 3 = \"a\", \"b\""))
        assertEquals("a", s.readStr("US", idx(0)))
        assertEquals("b", s.readStr("US", idx(1)))
        assertEquals("", s.readStr("US", idx(2)))
        s.writeStr("US", idx(2), "c")
        assertEquals("c", s.readStr("US", idx(2)))
    }

    @Test
    fun `常量变量的不可写闸在解析期 存储层照官方不拦`() {
        // B5（2026-11-07）相位搬迁：官方存储层写入口**没有**常量闸 ——
        // `VariableData.cs:1340-1344` 的 StaticInt1D SetValue 只有 `array[arguments[0]] = value`，
        // 全文件唯一的 IsConst 读点 `:4089` 是存档装载白名单。拦截全在解析期：
        // 赋值左值 `ArgumentParser.cs:903-907`（LeftIsConst）、VARSET `:1784-1788`、
        // SAVEVAR 目标 `:2197-2201`、可变实参位 `ArgumentBuilder.cs:92-105`；
        // 移植侧同形闸在 `stmt/ArgBuilders.kt`（set :1768 等），由
        // `proc/CallSharpConstWriteBackTest` 的「常量目标仍在解析期被拒」钉住。
        // 本用例因此改为钉「解析期闸才是那一道、存储层照官方放行」，断言不放宽：
        // 原来断言的两条运行时抛点（writeInt / plusInt）现在都必须**真的写进去**。
        val s = VariableStore()
        s.declareUserVar(dim("CONST UC, 3 = 1, 2, 3"))
        assertTrue(s.isConst("UC"))
        s.writeInt("UC", idx(0), 9)
        assertEquals(9L, s.readInt("UC", idx(0)))
        // `++`/`--` 走同一条存储写口（plusIntEntry 的 UserVar 分支：readInt + writeInt），同口径。
        s.plusInt("UC", idx(0), 1)
        assertEquals(10L, s.readInt("UC", idx(0)))
    }

    @Test
    fun `常量初值支持位运算并遵循原版优先级`() {
        // 语料实形：COMF641 アビリティカード_カードパラメータ.ERH:225-260 的
        // `#DIM CONST …BIT配列 = 1 << A, 1 << B | 1 << C, …`。
        val s = VariableStore()
        s.declareUserVar(dim("CONST UB, 3 = 1 << 2 | 1 << 0, 1 << 3 & 1 << 2, 1 << 2 ^ 1 << 1"))
        assertEquals(5L, s.readInt("UB", idx(0)))
        assertEquals(0L, s.readInt("UB", idx(1)))
        assertEquals(6L, s.readInt("UB", idx(2)))
    }

    @Test
    fun `位运算优先级低于位移且一元取反可用`() {
        val s = VariableStore()
        // `12 << 1 & 4`：位移先结合 ⇒ (12<<1)&4 = 0；若位运算先结合则是 12 << (1&4) = 12。
        s.declareUserVar(dim("CONST USHIFT = 12 << 1 & 4, ~0"))
        assertEquals(2, s.getLength("USHIFT", 0))
        assertEquals(0L, s.readInt("USHIFT", idx(0)))
        assertEquals(-1L, s.readInt("USHIFT", idx(1)))
    }

    @Test
    fun `多维用户变量行主序`() {
        val s = VariableStore()
        s.declareUserVar(dim("UM, 2, 3"))
        s.writeInt("UM", idx(1, 2), 7)
        assertEquals(7L, s.readInt("UM", idx(1, 2)))
        assertEquals(0L, s.readInt("UM", idx(1, 1)))
        assertFailsWith<CodeEE> { s.readInt("UM", idx(2, 0)) }
        assertEquals(3, s.getLength("UM", 1))
    }

    @Test
    fun `重名与系统变量冲突报错`() {
        val s = VariableStore()
        s.declareUserVar(dim("UX, 2"))
        assertFailsWith<CodeEE> { s.declareUserVar(dim("UX, 2")) }
        assertFailsWith<CodeEE> { s.declareUserVar(dim("DAY, 2")) }
    }

    // ---------------- 动态作用域 ----------------

    @Test
    fun `私有动态变量栈式嵌套`() {
        val s = VariableStore()
        s.declareUserVar(dim("DYNAMIC CNT, 3 = 5", isPrivate = true), "F")
        s.enterFunction("F")
        assertEquals(5L, s.readInt("CNT", idx(0))) // ScopeIn 回填初值
        s.writeInt("CNT", idx(0), 1)

        s.enterFunction("F") // 递归：再开一层
        assertEquals(5L, s.readInt("CNT", idx(0)))
        s.writeInt("CNT", idx(0), 2)
        s.leaveFunction()
        assertEquals(1L, s.readInt("CNT", idx(0))) // 恢复外层

        s.leaveFunction()
        assertEquals(0, s.callDepth())
    }

    @Test
    fun `私有静态变量跨调用保持`() {
        val s = VariableStore()
        s.declareUserVar(dim("US, 2", isPrivate = true), "F")
        s.enterFunction("F")
        s.writeInt("US", idx(0), 7)
        s.leaveFunction()
        s.enterFunction("F")
        assertEquals(7L, s.readInt("US", idx(0)))
        s.leaveFunction()
    }

    @Test
    fun `同名私有变量在不同函数中各有一份`() {
        val s = VariableStore()
        s.declareUserVar(dim("UV, 2", isPrivate = true), "F")
        s.declareUserVar(dim("UV, 2", isPrivate = true), "G")

        s.enterFunction("F")
        s.writeInt("UV", idx(0), 1)
        s.leaveFunction()

        s.enterFunction("G")
        assertEquals(0L, s.readInt("UV", idx(0)))
        s.writeInt("UV", idx(0), 2)
        s.leaveFunction()

        s.enterFunction("F")
        assertEquals(1L, s.readInt("UV", idx(0)))
        s.leaveFunction()
    }

    // ---------------- 局部变量 ----------------

    @Test
    fun `LOCAL 按函数隔离`() {
        val s = VariableStore()
        s.enterFunction("F")
        s.writeInt("LOCAL", idx(0), 10)
        s.enterFunction("G")
        assertEquals(0L, s.readInt("LOCAL", idx(0)))
        s.writeInt("LOCAL", idx(0), 20)
        s.leaveFunction()
        assertEquals(10L, s.readInt("LOCAL", idx(0)))
        s.leaveFunction()
    }

    @Test
    fun `LOCAL 默认长度 1000 且可越界`() {
        val s = VariableStore()
        s.enterFunction("F")
        assertEquals(1000, s.getLength("LOCAL"))
        s.writeInt("LOCAL", idx(999), 1)
        assertFailsWith<CodeEE> { s.readInt("LOCAL", idx(1000)) }
        s.leaveFunction()
        assertFailsWith<CodeEE> { s.readInt("LOCAL", idx(0)) } // 函数外不可用
    }

    @Test
    fun `ARG 长度被函数声明拉大`() {
        val s = VariableStore()
        s.defineLocalLengths("H", VariableStore.LocalLengths(arg = 2000, args = 300))
        s.enterFunction("H")
        assertEquals(2000, s.getLength("ARG"))
        s.writeInt("ARG", idx(1999), 5)
        assertEquals(5L, s.readInt("ARG", idx(1999)))
        assertFailsWith<CodeEE> { s.readInt("ARG", idx(2000)) }
        assertEquals(300, s.getLength("ARGS"))
        s.leaveFunction()
    }

    @Test
    fun `LOCALS 字符串局部变量`() {
        val s = VariableStore()
        s.enterFunction("F")
        s.writeStr("LOCALS", idx(0), "tmp")
        assertEquals("tmp", s.readStr("LOCALS", idx(0)))
        s.leaveFunction()
    }

    @Test
    fun `resetLocalValues 清零静态用户变量与局部变量`() {
        val s = VariableStore()
        s.declareUserVar(dim("US, 2 = 4"))
        s.writeInt("US", idx(0), 99)
        s.enterFunction("F")
        s.writeInt("LOCAL", idx(0), 3)
        s.resetLocalValues()
        assertEquals(4L, s.readInt("US", idx(0)))
        assertEquals(0L, s.readInt("LOCAL", idx(0)))
        s.leaveFunction()
    }

    // ---------------- 角色用户变量 ----------------

    @Test
    fun `CHARADATA 每角色一份`() {
        val s = VariableStore()
        s.declareUserVar(dim("CHARADATA CV, 5"))
        s.addPseudoCharacter()
        s.addPseudoCharacter()
        s.writeInt("CV", idx(1, 3), 8)
        assertEquals(8L, s.readInt("CV", idx(1, 3)))
        assertEquals(0L, s.readInt("CV", idx(0, 3)))
        assertFailsWith<CodeEE> { s.readInt("CV", idx(2, 0)) }
        assertFailsWith<CodeEE> { s.readInt("CV", idx(0, 5)) }
    }

    @Test
    fun `CHARADATA 2 维`() {
        val s = VariableStore()
        s.declareUserVar(dim("CHARADATA CV2, 2, 3"))
        s.addPseudoCharacter()
        s.writeInt("CV2", idx(0, 1, 2), 6)
        assertEquals(6L, s.readInt("CV2", idx(0, 1, 2)))
        assertFailsWith<CodeEE> { s.readInt("CV2", idx(0, 2, 0)) }
    }

    @Test
    fun `CHARADATA 字符串`() {
        val s = VariableStore()
        s.declareUserVar(dims("CHARADATA CS, 3"))
        s.addPseudoCharacter()
        s.writeStr("CS", idx(0, 0), "hi")
        assertEquals("hi", s.readStr("CS", idx(0, 0)))
    }

    // ---------------- REF ----------------

    @Test
    fun `REF 绑定目标并在进入作用域时清空`() {
        val s = VariableStore()
        s.declareUserVar(DimParser.parse("#DIM", "REF UR, 0", isPrivate = true), "F")
        val target = LongArray(5)
        s.enterFunction("F")
        val v = s.userVar("UR") as RefUserVar
        v.bindInt(target)
        s.writeInt("UR", idx(2), 11)
        assertEquals(11L, target[2])
        s.leaveFunction()

        s.enterFunction("F") // ScopeIn 清空引用
        assertFailsWith<CodeEE> { s.readInt("UR", idx(0)) }
        s.leaveFunction()
    }

    // ---------------- 元信息 ----------------

    @Test
    fun `存档与全局列表`() {
        val s = VariableStore()
        s.declareUserVar(dim("GLOBAL G1, 2"))
        s.declareUserVar(dim("SAVEDATA S1, 2"))
        assertEquals(1, s.globalUserVarList().size)
        assertEquals(1, s.savedUserVarList().size)
        assertTrue(s.globalUserVarList().single().spec.isGlobal)
        assertTrue(s.savedUserVarList().single().spec.isSavedata)
    }

    @Test
    fun `resolveVarInfo 反映用户变量维度与类型`() {
        val s = VariableStore()
        s.declareUserVar(dim("UU, 4"))
        s.declareUserVar(dims("US, 2"))
        val info = assertNotNull(s.resolveVarInfo("UU"))
        assertEquals(OperandType.INT, info.operandType)
        assertEquals(1, info.dimension)
        val infoStr = assertNotNull(s.resolveVarInfo("US"))
        assertEquals(OperandType.STR, infoStr.operandType)
        assertEquals(1, infoStr.dimension)
    }

    @Test
    fun `用户变量不接受字符串关键字下标`() {
        val s = VariableStore()
        s.declareUserVar(dim("UU, 4"))
        assertFailsWith<CodeEE> { s.keywordToInteger("UU", 0, "x") }
    }

    @Test
    fun `CHARADATA 角色用户变量随角色增删保持布局`() {
        val s = VariableStore()
        s.declareUserVar(dim("CHARADATA CV, 3"))
        s.addPseudoCharacter()
        s.addPseudoCharacter()
        s.writeInt("CV", idx(1, 1), 4)
        s.deleteCharacter(0) // 左移后原 1 号变为 0 号
        assertEquals(4L, s.readInt("CV", idx(0, 1)))
    }

    // ---------------- 批量赋值 userVar 回落（A15-E2E 任务 2；spec: e2e diagnosis §1） ----------------

    @Test
    fun `私有 DIM 数组批量赋值并读回`() {
        // `ARR = 1,2,3` 经 sem 批量形态 → setValues（StatementExecutor.kt:1212）。
        // 旧实现只查 registry.def、从不查 userVar（VariableStore.kt:270/:284），私有
        // #DIM 数组一律误报"未定义的变量 ARR" —— 还原任务 2 后本用例转红（反向证明）。
        val s = VariableStore()
        s.declareUserVar(dim("ARR, 5", isPrivate = true), "F")
        s.enterFunction("F")
        s.setValues("ARR", idx(0), longArrayOf(1, 2, 3))
        assertEquals(1L, s.readInt("ARR", idx(0)))
        assertEquals(2L, s.readInt("ARR", idx(1)))
        assertEquals(3L, s.readInt("ARR", idx(2)))
        assertEquals(0L, s.readInt("ARR", idx(3)))
        // 越界元素由逐元素复用的 writeInt 自带检查（与标量路径同一口径，抛 CodeEE 不是 AIOOBE）
        assertFailsWith<CodeEE> { s.setValues("ARR", idx(3), longArrayOf(1, 2, 3)) }
        s.leaveFunction()
    }

    @Test
    fun `批量赋值字符串重载同样回落 userVar`() {
        // 镜像 writeStr 的 userVar 优先（VariableStore.kt:192-197）→ Array<String> 重载。
        val s = VariableStore()
        s.declareUserVar(dims("SARR, 3", isPrivate = true), "F")
        s.enterFunction("F")
        s.setValues("SARR", idx(0), arrayOf("a", "b"))
        assertEquals("a", s.readStr("SARR", idx(0)))
        assertEquals("b", s.readStr("SARR", idx(1)))
        assertEquals("", s.readStr("SARR", idx(2)))
        s.leaveFunction()
    }

    @Test
    fun `批量与标量同序 userVar 优先再 registry`() {
        // "registry 与 userVar 同名时优先级与标量路径一致"：declareUserVar 本就禁止与
        // 系统变量重名（VariableStore.kt:578），故同名不可构造；可锁的口径是**解析序
        // 一致** —— 标量 writeInt 与批量 setValues 都先 userVar 后 registry，两侧行为
        // 由同一 userVar 解析承接，且双 miss 的报错文案一致（"未定义的变量"）。
        val s = VariableStore()
        s.declareUserVar(dim("GU, 3"))                              // 全局用户变量（无帧也在 userVar 表）
        s.writeInt("GU", idx(0), 9)                                 // 标量路径
        s.setValues("GU", idx(1), longArrayOf(4, 5))                // 批量路径
        assertEquals(9L, s.readInt("GU", idx(0)))
        assertEquals(4L, s.readInt("GU", idx(1)))
        assertEquals(5L, s.readInt("GU", idx(2)))
        s.setValues("DAY", idx(0), longArrayOf(2))                  // registry 命中路径不回退
        assertEquals(2L, s.readInt("DAY", idx(0)))
        val e = assertFailsWith<CodeEE> { s.setValues("NO_SUCH_VAR", idx(0), longArrayOf(1)) }
        assertTrue(e.message!!.contains("未定义的变量"), e.message ?: "")
    }

    @Test
    fun `同名私有数组批量赋值按帧隔离与标量一致`() {
        val s = VariableStore()
        s.declareUserVar(dim("PV, 2", isPrivate = true), "F")
        s.declareUserVar(dim("PV, 2", isPrivate = true), "G")
        s.enterFunction("F")
        s.setValues("PV", idx(0), longArrayOf(7, 8))
        s.leaveFunction()
        s.enterFunction("G")
        assertEquals(0L, s.readInt("PV", idx(0)))                   // G 帧读不到 F 帧的批量写
        s.leaveFunction()
        s.enterFunction("F")
        assertEquals(7L, s.readInt("PV", idx(0)))                   // 私有 STATIC 跨调用保持（与标量路径同表）
        s.leaveFunction()
    }

    // ---------------- 复位链的串表半边（VariableStore.compactStringPool） ----------------

    @Test
    fun `复位压实串表：存活槽逐字不变、无主条目归还、新写落在小下标`() {
        val s = VariableStore()
        s.declareUserVar(dims("TMP, 4"))
        // 400 次覆盖 ⇒ 池里 400 条「没人再持有」的串（TMP 的 4 格里只剩最后 4 条）。
        repeat(400) { i -> s.writeStr("TMP", idx((i % 4).toLong()), "无主串$i") }
        s.writeStr("GLOBALS", idx(5), "跨复活的 GLOBALS")
        val before = s.pool.distinctCount

        s.resetData()
        val after = s.pool.distinctCount

        assertTrue(after * 4 < before, "无主条目应整体归还（$before → $after）")
        // 可观测语义：槽读回的串逐字不变。
        assertEquals(
            "跨复活的 GLOBALS", s.readStr("GLOBALS", idx(5)),
            "GLOBALS 跨复位存活（spec: VariableData.cs:3417-3418）⇒ 其下标必须随压实改写而不是悬空"
        )
        assertEquals("", s.readStr("TMP", idx(0)), "static 非全局用户变量由复位链清成默认（无 DEFAULT ⇒ 空串）")
        assertNull(s.pool.idOf("无主串0"), "无主条目连同反查项一起离开池")
        // 复位后的新写：驻留在存活项之后的小下标，去重照旧成立。
        s.writeStr("TMP", idx(1), "复位后新串")
        val id = s.pool.idOf("复位后新串")
        assertNotNull(id, "新写的串必须可反查")
        assertTrue(id!! < after + 2, "新驻留下标接在存活项之后（$id vs 压实后 $after 条）")
        assertEquals("复位后新串", s.readStr("TMP", idx(1)))
        s.writeStr("TMP", idx(2), "复位后新串")
        assertEquals(id, s.pool.idOf("复位后新串"), "同内容仍同一格（压实不破坏驻留去重）")
    }

    @Test
    fun `复位后用户 GLOBAL 串与非 static 私有串逐字不变`() {
        val s = VariableStore()
        s.declareUserVar(dims("GLOBAL GU, 2 = \"全局初值\""))
        s.declareUserVar(DimParser.parse("#DIMS", "DYNAMIC PV, 2", true), "F")
        s.enterFunction("F")
        s.writeStr("GU", idx(1), "跨复位的用户全局串")
        s.writeStr("PV", idx(0), "跨复位的私有动态串")
        repeat(200) { i -> s.writeStr("PV", idx(1), "无主动态$i") }

        s.resetData()

        // 用户 GLOBAL：resetData 不调 resetGlobalValues（spec: VariableEvaluator.cs:1336-1346）⇒ 内容存活。
        assertEquals("跨复位的用户全局串", s.readStr("GU", idx(1)), "用户 GLOBAL 串槽跨复位存活")
        assertEquals("全局初值", s.readStr("GU", idx(0)), "没写过的格回 DEFAULT（GLOBAL 不被复位链 setDefault）")
        // 非 static 私有：resetLocalValues 只 setDefault static（spec: VariableData.cs:3365-3375）⇒ 内容存活。
        assertEquals("跨复位的私有动态串", s.readStr("PV", idx(0)), "非 static 私有串跨复位存活")
        assertEquals("无主动态199", s.readStr("PV", idx(1)), "同一张数组里的**另一个**存活槽也逐字不变（它持有的是旧下标 ⇒ 必须随压实改写）")
        assertNull(s.pool.idOf("无主动态0"), "被后续写入顶掉、复位后无人再持有的串应离开池")
        s.leaveFunction()
    }

    @Test
    fun `静态串变量的 DEFAULT 下标缓存随压实改写（第二次复位仍读得到原串）`() {
        val s = VariableStore()
        s.declareUserVar(dims("US, 3 = \"a\", \"b\""))
        assertEquals("a", s.readStr("US", idx(0)), "首次读触发 data + defaults 两份下标数组")
        s.resetData()                                            // 第一次压实：data 与 defaults 同批改写
        repeat(300) { i -> s.writeStr("US", idx(0), "无主第二次$i") }  // data[0] 不再持有 "a"
        s.resetData()                                            // 第二次复位由 defaults 抄回初值
        assertEquals("a", s.readStr("US", idx(0)), "defaults 没改写的话这里就是悬空下标（CodeEE）")
        assertEquals("b", s.readStr("US", idx(1)))
    }

    @Test
    fun `复位压实不打断 REF 的数组本体绑定`() {
        val s = VariableStore()
        s.declareUserVar(DimParser.parse("#DIMS", "DYNAMIC PV, 2", true), "F")
        s.declareUserVar(DimParser.parse("#DIMS", "REF SR, 0", true), "F")
        s.enterFunction("F")
        val target = s.userVar("PV", "F")!!.rawArray() as IntArray
        target[0] = s.pool.intern("REF 看得见的串")
        (s.userVar("SR", "F") as RefUserVar).bindStr(target)
        repeat(200) { i -> s.writeStr("PV", idx(1), "无主 REF$i") }

        s.resetData()

        val ref = s.userVar("SR", "F") as RefUserVar
        assertSame(target, ref.targetStr, "REF 绑的就是数组对象本体（spec: ReferenceToken.cs:125-128）⇒ 压实只改元素，不换对象")
        assertEquals("REF 看得见的串", s.readStr("SR", idx(0)), "经 REF 读回逐字不变")
        s.writeStr("SR", idx(0), "经 REF 写回")
        assertEquals("经 REF 写回", s.readStr("PV", idx(0)), "别名双向照旧")
        s.leaveFunction()
    }

    @Test
    fun `复位压实后角色串不复活（旧下标随角色一起作废）`() {
        val s = VariableStore()
        s.declareUserVar(dims("JUNK, 2"))
        s.addPseudoCharacter()
        s.writeStr("NAME", idx(0), "被删掉的名字")             // C_STR0：单个下标 = 角色位置
        repeat(100) { i -> s.writeStr("JUNK", idx((i % 2).toLong()), "无主角色串$i") }
        s.writeStr("GLOBALS", idx(5), "活着的串")

        s.resetData()

        assertNull(s.pool.idOf("被删掉的名字"), "deleteAllCharacters 后角色表没有可达行 ⇒ 不贡献存活项")
        assertEquals("活着的串", s.readStr("GLOBALS", idx(5)))
        assertEquals(0, s.characters.count)
        s.addPseudoCharacter()
        assertNotEquals("被删掉的名字", s.readStr("NAME", idx(0)), "新角色的名字格从模板/空串起步，绝不复现被压实掉的旧名字")
    }
}
