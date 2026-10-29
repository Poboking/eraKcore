package com.eratw.port.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 审计波 vars 修复的聚焦用例（逐条对拍 C#）：
 * 1. VARSET/VARSETS 作用于 REF 时用**绑定长度**，不再拿声明尺寸 0 当 total；
 * 2. REF 多维寻址不得静默落到错误元素；
 * 3. COPYCHARA/SWAPCHARA 越界按 Long 判定、文案与 zhs 表一致；
 * 4. LOCAL/LOCALS 下标按 Long 判定（不先截断）；
 * 5. 维度乘积 Long 预乘，溢出抛 CodeEE。
 */
class VarsRefAndIndexAuditTest {

    private fun idx(vararg v: Long) = v

    private fun storeWithRef(keyword: String, body: String, key: String = "F"): Pair<VariableStore, RefUserVar> {
        val s = VariableStore()
        s.declareUserVar(DimParser.parse(keyword, body, isPrivate = true), key)
        s.enterFunction(key)
        val name = body.removePrefix("REF ").substringBefore(",").trim()
        return s to (s.userVar(name) as RefUserVar)
    }

    // ---------------- 1. VARSET / VARSETS 与 REF ----------------

    @Test
    fun `VARSET 1 维 REF 按绑定长度清零`() {
        val (s, ref) = storeWithRef("#DIM", "REF UR, 0")
        val target = LongArray(5) { 7 }
        ref.bindInt(target)
        // VARSET 的 end 缺省 = VariableDest.GetLength()（spec: FunctionIdentifier.cs:1644-1647）
        // ⇒ REF 取绑定长度；旧实现拿声明尺寸 0 当 total，带 end 的 VARSET 必抛区间越界。
        assertEquals(5, s.getLength("UR"))
        s.setAll("UR", 0, 0, s.getLength("UR"))
        assertTrue(target.all { it == 0L }, "目标数组应被整块清零，实际 ${target.toList()}")
        s.leaveFunction()
    }

    @Test
    fun `VARSET 1 维 REF 的显式区间只写区间`() {
        val (s, ref) = storeWithRef("#DIM", "REF UR, 0")
        val target = LongArray(5) { 7 }
        ref.bindInt(target)
        s.setAll("UR", 9, 1, 3)
        assertEquals(listOf(7L, 9L, 9L, 7L, 7L), target.toList())
        // 端点可等于长度（spec: ReferenceToken.cs:78-89 IsArrayRangeValid），超过即 CodeEE
        s.setAll("UR", 1, 0, 5)
        assertFailsWith<CodeEE> { s.setAll("UR", 1, 0, 6) }
        assertFailsWith<CodeEE> { s.setAll("UR", 1, -1, 2) }
        s.leaveFunction()
    }

    @Test
    fun `VARSETS 1 维 REF 按绑定长度覆盖`() {
        val (s, ref) = storeWithRef("#DIMS", "REF US, 0")
        val target = IntArray(4)
        ref.bindStr(target)
        assertEquals(4, s.getLength("US"))
        s.setAllStr("US", "x", 0, s.getLength("US"))
        assertTrue(target.all { s.pool.lookup(it) == "x" })
        s.leaveFunction()
    }

    @Test
    fun `VARSET 2 维 REF 整块覆盖目标`() {
        val (s, ref) = storeWithRef("#DIM", "REF MAT, 0, 0")
        val target = LongArray(25) { 7 }
        ref.bindInt(target)
        // 2D/3D 的 SetValueAll 忽略区间、整块覆盖（spec: VariableData.cs:2421-2436 / :2490-2509）；
        // 旧实现 total = 声明尺寸 0 ⇒ 静默不写。这里故意给 start/end 证明它们被忽略。
        s.setAll("MAT", 0, start = 2, end = 3)
        assertTrue(target.all { it == 0L }, "多维 REF 的 VARSET 必须整块清零，实际 ${target.toList()}")
        s.leaveFunction()
    }

    @Test
    fun `VARSET 未绑定的 REF 抛 EmptyRefVar`() {
        val s = VariableStore()
        s.declareUserVar(DimParser.parse("#DIM", "REF UR, 0", isPrivate = true), "F")
        s.declareUserVar(DimParser.parse("#DIMS", "REF US, 0", isPrivate = true), "F")
        s.enterFunction("F")
        // spec: ReferenceToken.cs:32-43 —— array == null 一律 EmptyRefVar
        val e = assertFailsWith<CodeEE> { s.setAll("UR", 0) }
        assertTrue(e.message!!.contains("EmptyRefVar"), e.message ?: "")
        val e2 = assertFailsWith<CodeEE> { s.setAllStr("US", "") }
        assertTrue(e2.message!!.contains("EmptyRefVar"), e2.message ?: "")
        val e3 = assertFailsWith<CodeEE> { s.getLength("UR") }
        assertTrue(e3.message!!.contains("EmptyRefVar"), e3.message ?: "")
        s.leaveFunction()
    }

    // ---------------- 2. REF 多维寻址 ----------------

    @Test
    fun `2 维 REF 非全零下标抛 CodeEE 而不是静默落到错误元素`() {
        val (s, ref) = storeWithRef("#DIM", "REF MAT, 0, 0")
        val target = LongArray(25) { 7 }
        target[1] = 42
        ref.bindInt(target)
        // 全 0 下标行主序压平恒为 0 号元素 ⇒ 可证明正确，保持可用。
        assertEquals(7L, s.readInt("MAT", idx(0, 0)))
        // 旧实现把 indices[0] 当压平下标：MAT:0:1 会读成 1 号元素（= 42），静默读错。
        val e = assertFailsWith<CodeEE> { s.readInt("MAT", idx(0, 1)) }
        assertTrue(e.message!!.contains("多维引用变量"), e.message ?: "")
        assertFailsWith<CodeEE> { s.writeInt("MAT", idx(1, 0), 1) }
        assertEquals(42L, target[1], "报错路径不得改动目标数组")
        // 下标个数必须等于声明维数
        assertFailsWith<CodeEE> { s.readInt("MAT", idx(0)) }
        s.leaveFunction()
    }

    @Test
    fun `2 维 REF 按目标形状逐维校验并压平`() {
        val (s, ref) = storeWithRef("#DIM", "REF MAT, 0, 0")
        val target = LongArray(25)
        // 形状随绑定一起记录：原版 REF 绑的是 CLR long[,]，形状由数组本体自带
        // （spec: ReferenceToken.cs:125-128）；本移植是压平存储，必须在绑定点携带。
        ref.bindInt(target, intArrayOf(5, 5))
        // VARSIZE / GetLength(dimension) 取目标各维（spec: ReferenceToken.cs:45-56）
        assertEquals(5, s.getLength("MAT", 0))
        assertEquals(5, s.getLength("MAT", 1))
        assertFailsWith<CodeEE> { s.getLength("MAT", 2) }

        s.writeInt("MAT", idx(3, 3), 256)
        assertEquals(256L, s.readInt("MAT", idx(3, 3)))
        // 行主序压平 = 3*5+3 = 18（spec: VariableData.cs:2389-2417 的 array[i, j]）
        assertEquals(256L, target[18])
        assertEquals(0L, s.readInt("MAT", idx(0, 3)), "别的格子不得被连带写")
        assertEquals(0L, target[3])
        // 逐维越界（Long 比较；文案按维号 + 该维长度，spec: ReferenceToken.cs:58-76）
        val e = assertFailsWith<CodeEE> { s.readInt("MAT", idx(0, 20)) }
        assertTrue(e.message!!.contains("第 2 维下标 20"), e.message ?: "")
        assertFailsWith<CodeEE> { s.writeInt("MAT", idx(5, 0), 1) }
        assertFailsWith<CodeEE> { s.writeInt("MAT", idx(-1, 0), 1) }
        assertEquals(0L, target[5], "越界写不得落到 1 号行")
        s.leaveFunction()
    }

    @Test
    fun `2 维 REF 的绑定形状随作用域进出保存与恢复`() {
        val (s, ref) = storeWithRef("#DIM", "REF MAT, 0, 0")
        val outer = LongArray(25) { 1 }
        ref.bindInt(outer, intArrayOf(5, 5))
        // 再次进入同一函数 = 嵌套作用域：ScopeIn 保存外层绑定（含形状）并清空
        // （spec: ReferenceToken.cs:91-114 的 arrayList 只存 array，C# 的形状随数组对象走）
        s.enterFunction("F")
        assertFailsWith<CodeEE> { s.readInt("MAT", idx(0, 0)) }
        val inner = LongArray(25)
        ref.bindInt(inner, intArrayOf(5, 5))
        s.writeInt("MAT", idx(1, 2), 7)
        assertEquals(7L, inner[1 * 5 + 2])
        s.leaveFunction() // ScopeOut 恢复外层绑定与形状
        assertEquals(5, s.getLength("MAT", 1))
        assertEquals(1L, s.readInt("MAT", idx(3, 3)))
        s.writeInt("MAT", idx(4, 4), 9)
        assertEquals(9L, outer[24])
        s.leaveFunction()
    }

    @Test
    fun `VARSET 带形状的 2 维 REF 整块清零`() {
        val (s, ref) = storeWithRef("#DIM", "REF MAT, 0, 0")
        val target = LongArray(25) { 7 }
        ref.bindInt(target, intArrayOf(5, 5))
        // 2D/3D 的 SetValueAll 忽略区间、整块覆盖（spec: VariableData.cs:2421-2436）；
        // 2D 的 VARSET end 缺省恒 0（IsArray1D 为假，spec: FunctionIdentifier.cs:1640-1647）。
        s.setAll("MAT", 0, start = 2, end = 3)
        assertTrue(target.all { it == 0L }, "多维 REF 的 VARSET 必须整块清零，实际 ${target.toList()}")
        s.leaveFunction()
    }

    @Test
    fun `1 维 REF 下标按 Long 判定不截断`() {
        val (s, ref) = storeWithRef("#DIM", "REF UR, 0")
        val target = LongArray(5) { 7 }
        ref.bindInt(target)
        val huge = 1L shl 32 // 截断成 Int 后是 0
        assertFailsWith<CodeEE> { s.readInt("UR", longArrayOf(huge)) }
        assertFailsWith<CodeEE> { s.writeInt("UR", longArrayOf(huge), 1) }
        assertEquals(7L, target[0], "越界写不得落到 0 号元素")
        assertEquals(7L, s.readInt("UR", idx(4)))
        s.leaveFunction()
    }

    // ---------------- 3. COPYCHARA / SWAPCHARA ----------------

    @Test
    fun `COPYCHARA 越界先判 Long 再截断且文案与原文一致`() {
        val s = VariableStore()
        s.addPseudoCharacter()
        s.addPseudoCharacter()
        // spec: VariableEvaluator.cs:1355-1366 + lang.emuera-zhs.xml:316/317
        val from = assertFailsWith<CodeEE> { s.copyCharacter(2, 0) }
        assertEquals("复制的原角色不存在", from.message)
        val to = assertFailsWith<CodeEE> { s.copyCharacter(0, 2) }
        assertEquals("复制的目标角色不存在", to.message)
        val big = 1L shl 32 // 旧实现 toInt() 截断成 0，静默复制 0 号角色
        assertFailsWith<CodeEE> { s.copyCharacter(0, big) }
        assertFailsWith<CodeEE> { s.copyCharacter(big, 0) }
        assertFailsWith<CodeEE> { s.copyCharacter(-1, 0) }
        assertEquals(0L, s.readInt("NO", idx(0)), "越界实参不得改动角色表")
    }

    @Test
    fun `SWAPCHARA 越界文案与原文一致`() {
        val s = VariableStore()
        s.addPseudoCharacter()
        s.addPseudoCharacter()
        // spec: VariableEvaluator.cs:1378-1383 + lang.emuera-zhs.xml:318
        val e = assertFailsWith<CodeEE> { s.swapCharacters(0, 1L shl 32) }
        assertEquals("试图替换一个不存在的注册角色", e.message)
        val e2 = assertFailsWith<CodeEE> { s.swapCharacters(-1, 0) }
        assertEquals("试图替换一个不存在的注册角色", e2.message)
    }

    // ---------------- 4. LOCAL / LOCALS 的 Long 下标 ----------------

    @Test
    fun `LOCAL 与 LOCALS 下标按 Long 判定不截断`() {
        val s = VariableStore()
        s.enterFunction("F")
        val huge = 1L shl 32 // 旧实现 toInt() 截断成 0
        assertFailsWith<CodeEE> { s.writeInt("LOCAL", longArrayOf(huge), 1) }
        assertFailsWith<CodeEE> { s.readInt("LOCAL", longArrayOf(huge)) }
        assertFailsWith<CodeEE> { s.writeStr("LOCALS", longArrayOf(huge), "x") }
        assertFailsWith<CodeEE> { s.readStr("LOCALS", longArrayOf(huge)) }
        assertEquals(0L, s.readInt("LOCAL", idx(0)), "越界写不得落到 0 号元素")
        // 正常路径不受影响（LOCAL 默认长度 1000）
        s.writeInt("LOCAL", idx(999), 5)
        assertEquals(5L, s.readInt("LOCAL", idx(999)))
        s.leaveFunction()
    }

    // ---------------- 5. 维度乘积溢出 ----------------

    @Test
    fun `维度乘积溢出抛 CodeEE`() {
        // VarDef.totalSize：1000000 x 1000000 = 1e12 > Int.MAX
        val def = VarDef(
            name = "BIG",
            code = 0,
            type = VarType.INT,
            storage = Storage.USER,
            index = 0,
            dims = intArrayOf(1_000_000, 1_000_000),
            isConst = false,
            isSavedata = false,
            isGlobal = false,
        )
        val e = assertFailsWith<CodeEE> { def.totalSize }
        assertTrue(e.message!!.contains("超出可分配上限"), e.message ?: "")

        // 全局 2 维列分配（VARIABLESIZE.CSV 的极端尺寸）
        val sizes = VariableSizes()
        sizes.int2D[0] = intArrayOf(50_000, 50_000) // 2.5e9 > Int.MAX
        val e2 = assertFailsWith<CodeEE> { VariableStore(registry = VariableRegistry(sizes)) }
        assertTrue(e2.message!!.contains("超出可分配上限"), e2.message ?: "")

        // 用户变量声明（编译期元数据可能绕过 DimParser 的 1..1000000 总元素数关卡）
        val spec = DimSpec("BIGVAR", false, intArrayOf(1_000_000, 1_000_000))
        val e3 = assertFailsWith<CodeEE> { VariableStore().declareUserVar(spec) }
        assertTrue(e3.message!!.contains("超出可分配上限"), e3.message ?: "")
    }

    @Test
    fun `正常尺寸的乘积照旧`() {
        val def = VarDef(
            name = "NORMAL",
            code = 0,
            type = VarType.INT,
            storage = Storage.USER,
            index = 0,
            dims = intArrayOf(10, 100, 10),
            isConst = false,
            isSavedata = false,
            isGlobal = false,
        )
        assertEquals(10_000, def.totalSize)
        assertEquals(1, VarDef("S", 0, VarType.INT, Storage.USER, 0, IntArray(0), false, false, false).elementCount)
    }
}
