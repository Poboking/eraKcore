package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FINDELEMENT / FINDLASTELEMENT 的**返回值与实参绑定**逐位对照（w8-finderef）。
 *
 * 与 [FindElementOmitTest] 的分工：那一份钉「5 槽 + OmitStart=2 + 第 5 位是 isExact」的
 * 个数/省略位契约；本文件钉**值语义**，重点是三处按 C# 原文修正过的口径：
 * 1. `isExact` 的判据是「`Regex.Match` 的**首个**命中恰好覆盖整串」
 *    （`text.Length == match.Length`，spec: VariableEvaluator.cs:601-605、:620-624），
 *    **不是**锚定整串匹配 —— 模式 `a|abc` 对 `abc` 原版判不命中（首个命中是 `a`）；
 * 2. 引用自身的下标即使末位随后被扫描值替换，也要**求值并逐维校验**
 *    （:4946 `GetFixedVariableTerm` → :4947 `IsArrayRangeValid` 首个动作是 `CheckElement(transporter)`，
 *    spec: VariableData.cs:89-100、CharaVariableToken.cs:56-70）；
 * 3. 扫描值替换的是**最后一个下标**：1 维非角色数组扫整条（:565 `GetArray()`），
 *    角色 1 维只扫该角色那一行（:595 `GetArrayChara(Index1)`）。
 *
 * 其余边界：端点闭区间 [0, 长度]（VariableData.cs:92-99）、空区间先返回 -1
 * （VariableEvaluator.cs:561-564）、2D/3D 省略 end 时 `GetLength()` 直接抛维度错误
 * （VariableData.cs:154-157、:254-257）。全部用例假体变量，不读语料。
 */
class FindElementSemanticsTest {

    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) = Unit
    }

    private fun env(): Pair<BuiltinEvalContext, VariableStore> {
        val store = VariableStore()
        val base = BaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store) to store
    }

    /** 引用路径调用：**保留 null 占位**（空实参在 Expr 层就是 null 槽）。 */
    private fun callRef(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
        ctx.callFunctionRef(
            name,
            args.map { a ->
                when (a) {
                    null -> null
                    is VarRef -> a
                    is Int -> IntLiteral(a.toLong())
                    is Long -> IntLiteral(a)
                    is String -> StrLiteral(a)
                    else -> throw IllegalArgumentException("$a")
                }
            },
        )

    private fun intOf(v: Value): Long = (v as Value.IntV).v

    private fun iRef(name: String, vararg indices: Long) =
        VarRef(name, indices.map { IntLiteral(it) }, operandType = OperandType.INT)

    private fun sRef(name: String, vararg indices: Long) =
        VarRef(name, indices.map { IntLiteral(it) }, operandType = OperandType.STR)

    /** FLAG[0]=5 FLAG[1]=7 FLAG[2]=5 FLAG[3]=9（系统 1 维整数数组，长度 10000）。 */
    private fun flagEnv(): Pair<BuiltinEvalContext, VariableStore> {
        val (ctx, store) = env()
        store.writeInt("FLAG", longArrayOf(0), 5)
        store.writeInt("FLAG", longArrayOf(1), 7)
        store.writeInt("FLAG", longArrayOf(2), 5)
        store.writeInt("FLAG", longArrayOf(3), 9)
        return ctx to store
    }

    /** 两个假体角色（CFLAG 的长度是每个角色 1000 个元素）。 */
    private fun charaEnv(): Pair<BuiltinEvalContext, VariableStore> {
        val (ctx, store) = env()
        store.addPseudoCharacter()
        store.addPseudoCharacter()
        return ctx to store
    }

    // ------------------------------------------------------------------
    // 1. 返回值：命中下标 / -1；正向取首个、反向取末个
    // ------------------------------------------------------------------

    @Test
    fun `1 维整数数组返回命中下标与 -1`() {
        val (ctx, _) = flagEnv()
        assertEquals(1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 7L)))
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 8L)))
        // 重复值：FINDELEMENT 取首个、FINDLASTELEMENT 取末个（VariableEvaluator.cs:566-586）
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L)))
        assertEquals(2L, intOf(callRef(ctx, "FINDLASTELEMENT", iRef("FLAG"), 5L)))
        // 区间限制后末个随之左移：[0,2) 里只有 0 号位的 5
        assertEquals(0L, intOf(callRef(ctx, "FINDLASTELEMENT", iRef("FLAG"), 5L, 0L, 2L)))
    }

    // ------------------------------------------------------------------
    // 2. 区间：end 独占、端点可等于长度、空区间先返回 -1
    // ------------------------------------------------------------------

    @Test
    fun `区间半开且端点允许等于长度`() {
        val (ctx, store) = flagEnv()
        val len = store.getLength("FLAG", -1).toLong()
        // 7 在 1 号位：end 独占 ⇒ [0,1) 不含它
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 7L, 0L, 1L)))
        assertEquals(1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 7L, 1L, 2L)))
        // 端点等于长度合法（IsArrayRangeValid 用 > 判越界，VariableData.cs:92-99）
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, len, len)))
        assertEquals(len - 1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 0L, len - 1L, len)))
        // 空区间 start >= end 在触碰数组数据之前就返回 -1（:561-564 / :591-594）
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, 3L, 3L)))
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, 4L, 2L)))
    }

    // ------------------------------------------------------------------
    // 3. isExact 只在字符串分支生效
    // ------------------------------------------------------------------

    @Test
    fun `isExact 在整数分支被忽略`() {
        val (ctx, _) = flagEnv()
        // VariableEvaluator.cs:559-587 的整数重载不读该形参
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, null, null, 1L)))
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, null, null, 0L)))
        assertEquals(
            intOf(callRef(ctx, "FINDLASTELEMENT", iRef("FLAG"), 5L)),
            intOf(callRef(ctx, "FINDLASTELEMENT", iRef("FLAG"), 5L, null, null, 1L)),
        )
    }

    // ------------------------------------------------------------------
    // 4. 字符串数组：第二参是正则；非精确 = 任意位置命中
    // ------------------------------------------------------------------

    @Test
    fun `字符串数组非精确按正则任意位置命中`() {
        val (ctx, store) = env()
        store.writeStr("STR", longArrayOf(0), "abc")
        store.writeStr("STR", longArrayOf(1), "xabc")
        // 非精确走 IsMatch（:609 / :628）：从 start 起任意位置命中即可
        assertEquals(1L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "abc", 1L)))
        // 第二参是正则不是字面量：`^x` 只命中 1 号位的 "xabc"
        assertEquals(1L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "^x")))
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "^abc")))
    }

    // ------------------------------------------------------------------
    // 5. isExact 的口径：首个命中必须覆盖整串（不是锚定整串匹配）
    // ------------------------------------------------------------------

    @Test
    fun `isExact 取首个命中且必须覆盖整串`() {
        val (ctx, store) = env()
        store.writeStr("STR", longArrayOf(0), "abc")
        // 首个命中恰是整串 ⇒ 命中
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "abc", null, null, 1L)))
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "abc|a", null, null, 1L)))
        // 首个命中只是前缀（长度 1 ≠ 3）⇒ 原版判**不命中**；锚定整串匹配会为吃满整串
        // 回溯到第二个分支 "abc" 而错判成命中（本次修正点）
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "a|abc", null, null, 1L)))
        // 惰性量词同理：最左命中取最短的 "a"
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), ".+?", null, null, 1L)))
        // 贪婪量词的首个命中就是整串 ⇒ 命中
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), ".*", null, null, 1L)))
    }

    @Test
    fun `isExact 逐个元素判定并继续向后扫`() {
        val (ctx, store) = env()
        store.writeStr("STR", longArrayOf(0), "xabc")
        store.writeStr("STR", longArrayOf(1), "abc")
        assertEquals(1L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "abc", null, null, 1L)))
        // 区间只覆盖 0 号位时，整串判定对它不成立
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "abc", 0L, 1L, 1L)))
        assertEquals(1L, intOf(callRef(ctx, "FINDLASTELEMENT", sRef("STR"), "abc", null, null, 1L)))
    }

    @Test
    fun `空串元素在精确模式下按首命中长度判定`() {
        val (ctx, store) = env()
        store.writeStr("STR", longArrayOf(1), "x")
        // STR[0] 是空串：Match("") 成功且长度 0 == 0 ⇒ 命中
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "", null, null, 1L)))
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "a*", null, null, 1L)))
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "a+", null, null, 1L)))
        // 非精确时空模式命中一切（IsMatch 恒真）
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "")))
    }

    // ------------------------------------------------------------------
    // 6. 引用自身的下标：求值 + 校验，但不参与扫描
    // ------------------------------------------------------------------

    @Test
    fun `引用自身下标按 CheckElement 校验且不参与扫描`() {
        val (ctx, store) = flagEnv()
        val len = store.getLength("FLAG", -1)
        // 末位下标会被扫描值替换，但原版 :4946-4947 仍先 CheckElement(transporter)
        // （VariableData.cs:81-87：第 1 个参数越界报 OoRVarArg）
        val e = assertFailsWith<CodeEE> {
            callRef(ctx, "FINDELEMENT", iRef("FLAG", len.toLong()), 5L)
        }
        assertTrue(e.message!!.contains("第 1 维下标 $len 越界"), e.message!!)
        // 合法下标不限制扫描范围：`FLAG:7` 扫的仍是整条数组（:565 GetArray()），
        // 7 第一次出现在 1 号位而不是 7 号位
        assertEquals(1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG", 7L), 7L)))
    }

    // ------------------------------------------------------------------
    // 7. 角色 1 维数组：只扫该角色那一行
    // ------------------------------------------------------------------

    @Test
    fun `角色 1 维变量扫该角色那一行`() {
        val (ctx, store) = charaEnv()
        store.writeInt("CFLAG", longArrayOf(0, 5), 9)
        store.writeInt("CFLAG", longArrayOf(1, 5), 0)
        // 原版 GetArrayChara(Index1) 取的是该角色那一行（VariableEvaluator.cs:565 / :595）
        assertEquals(5L, intOf(callRef(ctx, "FINDELEMENT", iRef("CFLAG", 0L, 0L), 9L)))
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", iRef("CFLAG", 1L, 0L), 9L)))
        // 被替换的末位下标（这里是 5）不影响区间：整行 1000 个元素都扫
        assertEquals(5L, intOf(callRef(ctx, "FINDLASTELEMENT", iRef("CFLAG", 0L, 5L), 9L)))
    }

    // ------------------------------------------------------------------
    // 8. 2D / 3D：end 缺省取 GetLength()，多维变量直接抛维度错误
    // ------------------------------------------------------------------

    @Test
    fun `多维数组省略 end 报维度错误`() {
        val (ctx, store) = env()
        store.declareUserVar(DimSpec("V2D", false, intArrayOf(2, 3), isGlobal = true), "MAIN")
        store.declareUserVar(DimSpec("V3D", false, intArrayOf(2, 3, 4), isGlobal = true), "MAIN")
        // 原版 :4941 的 end 缺省走 variableTerm.GetLength()，2D/3D 的 GetLength() 抛
        // GetSizeDimError（VariableData.cs:154-157、:254-257）；
        // 移植侧同一处由 store.getLength(name, -1) 报
        val e2 = assertFailsWith<CodeEE> { callRef(ctx, "FINDELEMENT", iRef("V2D", 0L, 0L), 1L) }
        assertTrue(e2.message!!.contains("2 维"), e2.message!!)
        val e3 = assertFailsWith<CodeEE> { callRef(ctx, "FINDELEMENT", iRef("V3D", 0L, 0L, 0L), 1L) }
        assertTrue(e3.message!!.contains("3 维"), e3.message!!)
    }

    // ------------------------------------------------------------------
    // 9. 语料形态：5 槽、中间两位省略、末位 isExact（数值字符串的整串判定）
    // ------------------------------------------------------------------

    @Test
    fun `语料形态 中间两位省略末位 isExact`() {
        val (ctx, store) = env()
        store.writeStr("STR", longArrayOf(0), "0")
        store.writeStr("STR", longArrayOf(1), "10")
        store.writeStr("STR", longArrayOf(2), "1")
        // ERB/PRINT_TALENTNAME.ERB:370 同形：FINDELEMENT(L_SPLITSTR, TOSTR(ARG:1), , , 1)
        assertEquals(1L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "10", null, null, 1L)))
        // 精确模式下 "1" 不命中 "10"，只命中 2 号位
        assertEquals(2L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "1", null, null, 1L)))
        // 同一份数据去掉 isExact：非精确按任意位置命中，"1" 先在 1 号位的 "10" 里命中
        assertEquals(1L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "1")))
    }
}
