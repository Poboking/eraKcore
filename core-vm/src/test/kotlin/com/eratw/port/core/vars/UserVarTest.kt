package com.eratw.port.core.vars

import com.eratw.port.core.expr.OperandType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
    fun `常量变量不可写`() {
        val s = VariableStore()
        s.declareUserVar(dim("CONST UC, 3 = 1, 2, 3"))
        assertTrue(s.isConst("UC"))
        assertFailsWith<CodeEE> { s.writeInt("UC", idx(0), 9) }
        assertFailsWith<CodeEE> { s.plusInt("UC", idx(0), 1) }
        assertEquals(1L, s.readInt("UC", idx(0)))
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
}
