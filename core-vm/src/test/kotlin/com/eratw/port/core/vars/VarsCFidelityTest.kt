package com.eratw.port.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * VARS-AUDIT 的逐条对拍回归（CONTRACT-M6-B §2.1）。
 *
 * 每个用例锁一条与 C# 参考实现分歧的语义，KDoc 里给出 `文件:行号`；
 * 用例名按「原版行为」命名，改回旧实现即转红。
 */
class VarsCFidelityTest {

    private fun idx(vararg v: Long) = v

    private fun dim(body: String, isPrivate: Boolean = false) =
        DimParser.parse("#DIM", body, isPrivate)

    private fun dims(body: String, isPrivate: Boolean = false) =
        DimParser.parse("#DIMS", body, isPrivate)

    // ------------------------------------------------------------------
    // RESETDATA 的用户变量范围
    // ------------------------------------------------------------------

    /**
     * SetDefaultLocalValue 覆盖函数内 STATIC 与**非 GLOBAL** 的全局静态 #DIM。
     * spec: VariableData.cs:3365-3375（`localvarTokenDic` + `userDefinedStaticVarList`）、
     * :3270-3272 / :3342（两处入表）、:3265-3268（GLOBAL 走另一份表）。
     */
    @Test
    fun `resetLocalValues 复位函数内 STATIC 用户变量`() {
        val s = VariableStore()
        s.declareUserVar(dim("ST, 2 = 4", isPrivate = true), "F")
        s.enterFunction("F")
        s.writeInt("ST", idx(0), 99)
        s.leaveFunction()

        s.resetLocalValues()

        s.enterFunction("F")
        assertEquals(4L, s.readInt("ST", idx(0))) // 旧实现只遍历 userVars ⇒ 私有静态漏复位
        s.leaveFunction()
    }

    /**
     * GLOBAL 用户变量只由 SetDefaultGlobalValue 复位（RESETGLOBAL），
     * 不在 SetDefaultLocalValue 里。spec: VariableData.cs:3265-3268,3347-3363。
     */
    @Test
    fun `resetLocalValues 不复位 GLOBAL 用户变量`() {
        val s = VariableStore()
        s.declareUserVar(dim("GLOBAL GG, 2 = 2"))
        s.writeInt("GG", idx(0), 9)

        s.resetLocalValues()

        assertEquals(9L, s.readInt("GG", idx(0)))
        // 对照：非 GLOBAL 的同形变量会被复位
        s.declareUserVar(dim("PLAIN, 2 = 2"))
        s.writeInt("PLAIN", idx(0), 9)
        s.resetLocalValues()
        assertEquals(2L, s.readInt("PLAIN", idx(0)))
    }

    /**
     * CHARADATA 用户变量不进任何静态表，只随角色生命周期变化。
     * spec: VariableData.cs:3220-3244（只 UserDefinedCharaVarList）、:85-125（每角色数组）。
     */
    @Test
    fun `resetLocalValues 不复位 CHARADATA 用户变量`() {
        val s = VariableStore()
        s.declareUserVar(dim("CHARADATA CV, 3"))
        s.addPseudoCharacter()
        s.writeInt("CV", idx(0, 1), 7)

        s.resetLocalValues()

        assertEquals(7L, s.readInt("CV", idx(0, 1)))
    }

    /**
     * SetDefaultValue 只碰系统数组/伪变量，用户 #DIM 一个都不动。
     * spec: VariableData.cs:3385-3514（全函数无 userDefined* 引用）。
     */
    @Test
    fun `resetValues 不复位用户静态变量`() {
        val s = VariableStore()
        s.declareUserVar(dim("US, 2 = 3"))
        s.writeInt("US", idx(0), 88)

        s.resetValues()

        assertEquals(88L, s.readInt("US", idx(0)))
        assertEquals(0L, s.readInt("DAY", idx(0))) // 系统数组仍清零
    }

    /**
     * StaticStr1DVariableToken.SetDefault 在 defArray 非空时只回填前缀，不清尾段
     * （同族 StaticInt1D 是先清零）。spec: VariableData.cs:1595-1606 vs :1323-1332。
     */
    @Test
    fun `静态字符串变量复位保留初值段之后的旧值`() {
        val s = VariableStore()
        s.declareUserVar(dims("SS, 5 = \"a\""))
        s.declareUserVar(dim("SI, 5 = 7"))
        s.writeStr("SS", idx(0), "x")
        s.writeStr("SS", idx(3), "z")
        s.writeInt("SI", idx(3), 9)

        s.resetLocalValues()

        assertEquals("a", s.readStr("SS", idx(0))) // 初值段回填
        assertEquals("z", s.readStr("SS", idx(3))) // 尾段保留（官方怪癖）
        assertEquals(0L, s.readInt("SI", idx(3)))  // 整数侧清零
    }

    // ------------------------------------------------------------------
    // LOCAL 的作用域粒度
    // ------------------------------------------------------------------

    /**
     * 局部变量以函数名为 subKey、单份实例，没有 ScopeIn/ScopeOut ⇒
     * 递归调用共享同一数组、返回后不回滚。
     * spec: VariableLocal.cs:15,26-33,40-90；LocalVariableToken.cs:7-58；
     * IdentifierDictionary.cs:454-471（subKey = ParentLabelLine.LabelName）。
     */
    @Test
    fun `LOCAL 在递归调用间共享同一份`() {
        val s = VariableStore()
        s.enterFunction("F")
        s.writeInt("LOCAL", idx(0), 1)

        s.enterFunction("F") // 递归
        assertEquals(1L, s.readInt("LOCAL", idx(0))) // 旧实现会给新帧一片零数组
        s.writeInt("LOCAL", idx(0), 2)
        s.leaveFunction()

        assertEquals(2L, s.readInt("LOCAL", idx(0))) // 旧实现会恢复成 1
        s.leaveFunction()

        s.enterFunction("F")
        assertEquals(2L, s.readInt("LOCAL", idx(0))) // 值随函数实例保留
        s.leaveFunction()
    }

    /**
     * ClearLocalValue 清空 `localvarTokenDic` 里**所有函数**的局部实例。
     * spec: VariableData.cs:3377-3383 + VariableLocal.cs:121-124。
     */
    @Test
    fun `clearLocalValues 清掉所有函数的局部数据`() {
        val s = VariableStore()
        s.enterFunction("F")
        s.writeInt("LOCAL", idx(0), 5)
        s.leaveFunction()
        s.enterFunction("G")
        s.writeInt("LOCAL", idx(0), 7)
        s.leaveFunction()

        s.clearLocalValues()

        s.enterFunction("F")
        assertEquals(0L, s.readInt("LOCAL", idx(0)))
        s.leaveFunction()
        s.enterFunction("G")
        assertEquals(0L, s.readInt("LOCAL", idx(0)))
        s.leaveFunction()
    }

    // ------------------------------------------------------------------
    // REF 的 VARSIZE
    // ------------------------------------------------------------------

    /**
     * ReferenceToken.GetLength：未绑定抛 EmptyRefVar，已绑定返回目标数组长度，
     * 维度越界抛 GetSizeNonExistDim。spec: ReferenceToken.cs:32-56。
     */
    @Test
    fun `REF 的 getLength 返回目标长度而未绑定时报 EmptyRefVar`() {
        val s = VariableStore()
        s.declareUserVar(dim("REF UR, 0", isPrivate = true), "F")
        s.enterFunction("F")

        val unbound = assertFailsWith<CodeEE> { s.getLength("UR") }
        assertTrue(unbound.message!!.contains("EmptyRefVar"), unbound.message ?: "")

        val target = LongArray(5)
        (s.userVar("UR") as RefUserVar).bindInt(target)
        assertEquals(5, s.getLength("UR"))
        assertEquals(5, s.getLength("UR", 0))
        assertFailsWith<CodeEE> { s.getLength("UR", 1) }

        s.leaveFunction()
        assertFailsWith<CodeEE> { s.getLength("UR") } // ScopeOut 清空引用
    }

    // ------------------------------------------------------------------
    // VARSET 对伪变量/常量的静默返回
    // ------------------------------------------------------------------

    /**
     * VARSET 对 IsCalc 变量静默返回；字符串形态只对 WINDOW_TITLE 生效。
     * spec: VariableEvaluator.cs:265-273（整数）、:289-296（字符串）。
     */
    @Test
    fun `VARSET 伪变量静默返回而 WINDOW_TITLE 仍可写`() {
        val s = VariableStore()

        s.setAll("CHARANUM", 5) // 旧实现抛"常量不能赋值"
        assertEquals(0L, s.readInt("CHARANUM", idx()))

        s.setAll("__INT_MAX__", 1) // 伪变量没被写成 1
        assertEquals(Long.MAX_VALUE, s.readInt("__INT_MAX__", idx()))

        s.setAllStr("MONEYLABEL", "x") // 旧实现抛错
        assertEquals("$", s.readStr("MONEYLABEL", idx()))

        s.setAllStr("WINDOW_TITLE", "eratw")
        assertEquals("eratw", s.readStr("WINDOW_TITLE", idx()))

        // CSV 常量不是 IsCalc，原版照样抛常量赋值错（ConstantToken）
        assertFailsWith<CodeEE> { s.setAllStr("ABLNAME", "x") }
    }

    /**
     * `VARSET LOCAL` 常常是函数内**首次**触碰 LOCAL（语料 `MAP_MANAGE.ERB:433` 的
     * `@物件データ` 就是 `VARSET LOCAL` 开函数）。整组写入必须按 #LOCALSIZE 建表，
     * 不能因为局部表尚未建立就误报「只能在函数内使用」（bootSmoke 实测）。
     */
    @Test
    fun `VARSET LOCAL 作为首次触碰按声明长度建表`() {
        val s = VariableStore()
        s.defineLocalLengths("F", VariableStore.LocalLengths(local = 4, locals = 2))
        s.enterFunction("F")
        s.setAll("LOCAL", -1)
        s.setAllStr("LOCALS", "q")
        assertEquals(-1L, s.readInt("LOCAL", idx(3)), "长度取 #LOCALSIZE 4")
        assertEquals("q", s.readStr("LOCALS", idx(1)))
        s.setAll("LOCAL", 7, 1, 3)
        assertEquals(7L, s.readInt("LOCAL", idx(2)))
        assertEquals(-1L, s.readInt("LOCAL", idx(0)), "区间外的元素不受影响")
        s.leaveFunction()

        assertFailsWith<CodeEE> { s.setAll("LOCAL", 1) }
    }

    // ------------------------------------------------------------------
    // 角色 1 维变量的区间端点
    // ------------------------------------------------------------------

    /**
     * VARSET 的区间端点走 IsArrayRangeValid：必须在 [0, len]，越界 CodeEE，
     * 不许静默截断或漏成 IndexOutOfBounds。
     * spec: VariableEvaluator.cs:271-286 + CharaVariableToken.cs:72-83。
     */
    @Test
    fun `角色变量区间端点越界抛 CodeEE`() {
        val s = VariableStore()
        s.addPseudoCharacter()

        s.setAll("CFLAG", 3, 0, 1000, 0) // 端点等于长度合法
        assertEquals(3L, s.readInt("CFLAG", idx(0, 999)))

        assertFailsWith<CodeEE> { s.setAll("CFLAG", 3, 0, 1001, 0) }
        assertFailsWith<CodeEE> { s.setAll("CFLAG", 3, -1, 10, 0) }
        assertFailsWith<CodeEE> { s.setAllStr("CSTR", "q", 0, 101, 0) }
        assertFailsWith<CodeEE> { s.setAllStr("CSTR", "q", -1, 10, 0) }
    }

    /**
     * 角色变量的区间按第 1 个元素维（sizes[0]）判定，而不是最后一维。
     * spec: CharaVariableToken.cs:72-83；对照全局 2D 用最后一维（VariableData.cs:185-196）。
     */
    @Test
    fun `角色 2 维变量的区间按第 1 维判定`() {
        val sizes = VariableSizes()
        sizes.charaInt2D[0] = intArrayOf(2, 5) // CDFLAG = 2 x 5
        val s = VariableStore(registry = VariableRegistry(sizes))
        s.addPseudoCharacter()

        s.isArrayRangeValid("CDFLAG", idx(0, 0, 0), 0, 2) // 2 <= sizes[0]
        assertFailsWith<CodeEE> { s.isArrayRangeValid("CDFLAG", idx(0, 0, 0), 0, 3) }

        // 全局 2D 仍是最后一维：DA 默认 100 x 100
        s.isArrayRangeValid("DA", idx(0, 0), 0, 100)
        assertFailsWith<CodeEE> { s.isArrayRangeValid("DA", idx(0, 0), 0, 101) }
    }

    // ------------------------------------------------------------------
    // 角色模板 / 数组区间
    // ------------------------------------------------------------------

    /**
     * 同一 NO 的模板重复注册时保留**首次**定义：原版 GetCharacterTemplate 线性扫描
     * CharacterTmplList、命中即返回。spec: ConstantData.cs:1218-1228。
     */
    @Test
    fun `同名角色编号保留首次定义`() {
        val s = VariableStore()
        s.registerTemplate(CharacterTemplate(no = 7, name = "first"))
        s.registerTemplate(CharacterTemplate(no = 7, name = "second"))
        s.addCharacter(7)
        assertEquals("first", s.readStr("NAME", idx(0)))
        assertEquals("first", s.template(7)?.name)
    }

    /**
     * 局部变量的区间按函数声明的实际长度判定（ARG 可被参数声明拉大）。
     * spec: LocalVariableToken.cs:47-58。
     */
    @Test
    fun `局部变量的区间按声明长度判定`() {
        val s = VariableStore()
        s.defineLocalLengths("H", VariableStore.LocalLengths(arg = 2000))
        s.enterFunction("H")
        s.isArrayRangeValid("ARG", idx(0), 0, 2000)
        assertFailsWith<CodeEE> { s.isArrayRangeValid("ARG", idx(0), 0, 2001) }
        s.leaveFunction()
        assertFailsWith<CodeEE> { s.isArrayRangeValid("ARG", idx(0), 0, 10) } // 函数外
    }

    // ------------------------------------------------------------------
    // #DIM 的 REF 空维与尺寸尾逗号
    // ------------------------------------------------------------------

    /**
     * REF 的每个逗号记一维 0，末维可为空 ⇒ `REF X,,` 是 2 维。
     * spec: UserDefinedVariableData.cs:300-343（:309-320 按逗号 Add(0)，
     * :321-324 只在非 REF 分支检查"逗号后缺表达式"）。
     * 语料实况：集合.ERB:121/186/245 `#DIM REF 集合,,`、Add_Misc.ERB:1324 `#DIM REF MOVEMENT_INFO,,`。
     */
    @Test
    fun `REF 的空维按逗号计数`() {
        assertEquals(2, dim("REF R,,", isPrivate = true).dimension)
        assertEquals(3, dim("REF R,,,", isPrivate = true).dimension)
        assertEquals(2, dim("REF R, , ", isPrivate = true).dimension)
        assertEquals(1, dim("REF R,", isPrivate = true).dimension)
        assertEquals(1, dim("REF R, 0", isPrivate = true).dimension)
        assertEquals(2, dim("REF R, 0, 0", isPrivate = true).dimension)
        assertEquals(2, dim("REF R, 0,", isPrivate = true).dimension)
        // 声明结果进 VarDef 后维度/下标个数同步
        val d = dim("REF R,,", isPrivate = true)
        assertEquals(2, d.toVarDef(0, chara = false, code = VariableCode.REF2D).dimension)
    }

    /**
     * 非 REF 的尺寸列表尾随逗号是 HasNotExpressionAfterComma（原版报错），
     * 旧实现静默接受。spec: UserDefinedVariableData.cs:321-324。
     */
    @Test
    fun `非 REF 尺寸列表尾随逗号报错`() {
        assertFailsWith<CodeEE> { dim("X, 3,") }
        assertFailsWith<CodeEE> { dim("X, 3, 4,") }
        assertFailsWith<CodeEE> { dims("S, 3,", isPrivate = false) }
        // 正常形态不受影响
        assertEquals(2, dim("X, 3, 4").dimension)
    }

    // ------------------------------------------------------------------
    // 下标按 64 位判界（2^32 不许回绕）
    // ------------------------------------------------------------------

    /**
     * LOCAL/LOCALS 的旧实现先 `index.toInt()` 再比长度，2^32 回绕成 0 后静默读写
     * 第 0 个元素。C# `LocalVariableToken.CheckElement` 用 long 与 size 比较后抛
     * OoRVarArg（spec: LocalVariableToken.cs:39-45）。
     */
    @Test
    fun `LOCAL 与 LOCALS 的 2 的 32 次方下标抛 CodeEE 而不是回绕`() {
        val s = VariableStore()
        val big = 4294967296L // 2^32：旧实现 toInt() == 0
        s.enterFunction("F")
        s.writeInt("LOCAL", idx(0), 7)
        s.writeStr("LOCALS", idx(0), "keep")

        val e1 = assertFailsWith<CodeEE> { s.readInt("LOCAL", idx(big)) }
        assertTrue(e1.message!!.contains("4294967296"), e1.message ?: "")
        val e2 = assertFailsWith<CodeEE> { s.writeInt("LOCAL", idx(big), 9) }
        assertTrue(e2.message!!.contains("4294967296"), e2.message ?: "")
        assertEquals(7L, s.readInt("LOCAL", idx(0)), "第 0 个元素不得被回绕写入")

        val e3 = assertFailsWith<CodeEE> { s.readStr("LOCALS", idx(big)) }
        assertTrue(e3.message!!.contains("4294967296"), e3.message ?: "")
        val e4 = assertFailsWith<CodeEE> { s.writeStr("LOCALS", idx(big), "x") }
        assertTrue(e4.message!!.contains("4294967296"), e4.message ?: "")
        assertEquals("keep", s.readStr("LOCALS", idx(0)))
        s.leaveFunction()
    }

    /**
     * REF 的旧实现把 indices[0] 先 toInt 再与目标长度比（splitPos 与 RefUserVar
     * 收 Int），2^32 回绕成 0。C# `ReferenceToken.CheckElement` 直接用 long 与
     * array.GetLength(0) 比较（spec: ReferenceToken.cs:58-76）。
     */
    @Test
    fun `REF 的 2 的 32 次方下标按目标长度判界`() {
        val s = VariableStore()
        val big = 4294967296L
        s.declareUserVar(dim("REF UR, 0", isPrivate = true), "F")
        s.enterFunction("F")
        val target = LongArray(4)
        (s.userVar("UR") as RefUserVar).bindInt(target)

        val e1 = assertFailsWith<CodeEE> { s.readInt("UR", idx(big)) }
        assertTrue(e1.message!!.contains("4294967296"), e1.message ?: "")
        assertFailsWith<CodeEE> { s.writeInt("UR", idx(big), 5) }
        assertEquals(0L, target[0], "旧实现把 2^32 截成 0 写进目标第 0 个元素")
        s.leaveFunction()
    }

    /**
     * 批量赋值的起始下标也不许先 toInt：否则 2^32 回绕后从第 0 个元素起静默覆盖。
     * 原版 SetValue 的写入前下标检查同样用 long（spec: LocalVariableToken.cs:39-45）。
     */
    @Test
    fun `批量赋值的 2 的 32 次方起始下标不回绕`() {
        val s = VariableStore()
        val big = 4294967296L
        s.enterFunction("F")
        assertFailsWith<CodeEE> { s.setValues("LOCAL", idx(big), longArrayOf(1)) }
        assertFailsWith<CodeEE> { s.setValues("LOCALS", idx(big), arrayOf("x")) }
        assertEquals(0L, s.readInt("LOCAL", idx(0)))
        assertEquals("", s.readStr("LOCALS", idx(0)))
        s.leaveFunction()
    }

    // ------------------------------------------------------------------
    // 0 长度（VariableSize.CSV 禁用）数组
    // ------------------------------------------------------------------

    /**
     * SetDefaultValue 对 ASSI/TARGET/PBAND/EJAC 的 `[0] =` 都有 `Length != 0` 守卫
     * （spec: VariableData.cs:3491-3510）。VariableSize.CSV 把 CanForbid 数组禁用成
     * 0 长度后，旧实现 boot 期直接 ArrayIndexOutOfBounds；GetLength 应返回 0 而不是
     * 抛错（spec: VariableData.cs:25 IsForbid = Length == 0，:62-65 不抛）。
     */
    @Test
    fun `禁用成 0 长度的系统数组在 boot 期不炸且 VARSIZE 返回 0`() {
        val sizes = VariableSizes()
        // 负数尺寸在 VariableSize 装载层被改写成 0（spec: ConstantData.cs:352-356）；
        // 这里直接落写入层形态，等价于禁用 ASSI/PBAND/EJAC。
        sizes.applySize(VariableCode.ASSI, intArrayOf(0))
        sizes.applySize(VariableCode.PBAND, intArrayOf(0))
        sizes.applySize(VariableCode.EJAC, intArrayOf(0))
        val s = VariableStore(registry = VariableRegistry(sizes)) // 旧实现构造期即崩

        assertEquals(0, s.getLength("ASSI"))
        assertEquals(0, s.getLength("PBAND"))
        assertEquals(0, s.getLength("EJAC"))
        s.setAll("ASSI", 5) // 0 长度区间是 no-op（SetValueAll 空循环）
        assertFailsWith<CodeEE> { s.readInt("ASSI", idx(0)) }
        assertFailsWith<CodeEE> { s.writeInt("PBAND", idx(0), 1) }
    }

    // ------------------------------------------------------------------
    // COPYCHARA / SWAPCHARA 的角色位置判界
    // ------------------------------------------------------------------

    /**
     * CopyChara/SwapChara 先用 long 与 CharacterList.Count 比较再取元素
     * （spec: VariableEvaluator.cs:1355-1366 / :1378-1384）；旧实现先 toInt，
     * 2^32 回绕成 0 静默自拷贝/自交换。
     */
    @Test
    fun `COPYCHARA 与 SWAPCHARA 的大下标按 long 判界`() {
        val s = VariableStore()
        val big = 4294967296L
        s.addPseudoCharacter()
        s.writeInt("NO", idx(0), 11)

        assertFailsWith<CodeEE> { s.copyCharacter(big, 0) }
        assertFailsWith<CodeEE> { s.copyCharacter(0, big) }
        assertFailsWith<CodeEE> { s.swapCharacters(big, 0) }
        assertFailsWith<CodeEE> { s.swapCharacters(0, big) }
        assertEquals(11L, s.readInt("NO", idx(0)), "角色行不得被回绕操作静默改写")
    }

    // ------------------------------------------------------------------
    // REF 上的 VARSET / VARSETS
    // ------------------------------------------------------------------

    /**
     * VARSET/VARSETS 打在 REF 上要写穿目标数组（spec: VariableData.cs:2357-2367 与
     * :2563-2573），未绑定抛 EmptyRefVar；旧实现按声明尺寸（全 0）算出 total=0，
     * 静默 no-op。区间端点仍按目标长度判界（spec: VariableEvaluator.cs:265-315）。
     */
    @Test
    fun `VARSET 与 VARSETS 在 REF 上写穿目标数组`() {
        val s = VariableStore()
        s.declareUserVar(dim("REF UR, 0", isPrivate = true), "F")
        s.declareUserVar(dims("REF US, 0", isPrivate = true), "F")
        s.enterFunction("F")

        // 未绑定：C# SetValueAll 先抛 EmptyRefVar（spec: VariableData.cs:2361 / :2567）
        val e = assertFailsWith<CodeEE> { s.setAll("UR", 5) }
        assertTrue(e.message!!.contains("EmptyRefVar"), e.message ?: "")
        assertFailsWith<CodeEE> { s.setAllStr("US", "x") }

        val ti = LongArray(4)
        (s.userVar("UR") as RefUserVar).bindInt(ti)
        (s.userVar("US") as RefUserVar).bindStr(IntArray(3))

        s.setAll("UR", 7) // 全元素写穿
        assertEquals(listOf(7L, 7L, 7L, 7L), ti.toList())
        s.setAll("UR", 9, 1, 3) // 区间写穿：只有 [1,3)
        assertEquals(9L, s.readInt("UR", idx(1)))
        assertEquals(9L, s.readInt("UR", idx(2)))
        assertEquals(7L, s.readInt("UR", idx(3)))

        s.setAllStr("US", "hi")
        assertEquals("hi", s.readStr("US", idx(2)))
        s.setAllStr("US", "yo", 0, 1)
        assertEquals("yo", s.readStr("US", idx(0)))
        assertEquals("hi", s.readStr("US", idx(1)))

        // 端点按目标长度判界（不是静默截断）
        assertFailsWith<CodeEE> { s.setAll("UR", 1, 0, 5) }
        assertFailsWith<CodeEE> { s.setAllStr("US", "x", 0, 4) }
        s.leaveFunction()
    }

    // ------------------------------------------------------------------
    // LOCAL/LOCALS 区间端点
    // ------------------------------------------------------------------

    /**
     * 局部变量区间端点必须在 [0, size]（端点可等于长度），越界抛 CodeEE(OoRInstructionArg)
     * 而不是 IndexOutOfBoundsException（spec: LocalVariableToken.cs:47-58 +
     * VariableEvaluator.cs:265-315）。
     */
    @Test
    fun `LOCAL 与 LOCALS 的区间越界抛 CodeEE 而不是 IndexOutOfBounds`() {
        val s = VariableStore()
        s.enterFunction("F")
        assertFailsWith<CodeEE> { s.setAll("LOCAL", 1, -1, 10) }
        assertFailsWith<CodeEE> { s.setAll("LOCAL", 1, 0, 1001) }
        assertFailsWith<CodeEE> { s.setAll("LOCAL", 1, 1001, 2000) }
        assertFailsWith<CodeEE> { s.setAllStr("LOCALS", "x", 0, 101) }
        assertFailsWith<CodeEE> { s.setAllStr("LOCALS", "x", -1, 1) }

        // 端点等于长度合法（空区间）
        s.setAll("LOCAL", 1, 1000, 1000)
        s.setAllStr("LOCALS", "x", 100, 100)
        assertEquals(0L, s.readInt("LOCAL", idx(999)))
        assertEquals("", s.readStr("LOCALS", idx(99)))
        s.leaveFunction()
    }
}
