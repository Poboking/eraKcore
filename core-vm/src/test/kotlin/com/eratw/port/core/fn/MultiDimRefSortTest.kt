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
import com.eratw.port.core.vars.RefUserVar
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 多维（2/3 维）REF 参与数组排序（CORE-08）。
 *
 * C# 权威：`FunctionMethodCreator.cs:5139-5246` —— ARRAYMSORT 对后续每个 VariableTerm
 * 按 `Identifier.GetArray()` 拿数组本体重排；`ReferenceToken.cs:116-123` —— 多维 REF 的
 * GetArray() 返回**绑定目标数组**，未绑定抛 EmptyRefVar（:32-56 的 GetLength 同口径）。
 * 即：2D/3D REF 与普通多维数组同一路径，按目标形状逐行（行主序）重排，行数不足返回 0。
 *
 * 语料中多维 REF 参与排序命中 0（契约 §2 L6）⇒ 全部用合成数据，基准文本不动。
 */
private class MrsBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("MrsBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun mrsEnv(): Pair<BuiltinEvalContext, VariableStore> {
    val store = VariableStore()
    val base = MrsBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store) to store
}

private fun mrsCall(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
    ctx.callFunctionRef(
        name,
        args.map {
            when (it) {
                is VarRef -> it
                is Long -> IntLiteral(it)
                is Int -> IntLiteral(it.toLong())
                is String -> StrLiteral(it)
                else -> throw IllegalArgumentException("$it")
            }
        },
    )

private fun mrsIv(v: Value): Long = (v as Value.IntV).v

private fun mrsRef(name: String, vararg indices: Long): VarRef =
    VarRef(name, indices.map { IntLiteral(it) }, operandType = OperandType.INT)

private fun mrsRefS(name: String, vararg indices: Long): VarRef =
    VarRef(name, indices.map { IntLiteral(it) }, operandType = OperandType.STR)

private fun VariableStore.mrsIntOf(name: String, vararg indices: Long): Long =
    readInt(name, indices)

private fun VariableStore.mrsStrOf(name: String, vararg indices: Long): String =
    readStr(name, indices)

/** 声明私有 REF 变量并进入函数作用域（绑定期口径同 VarsRefAndIndexAuditTest）。 */
private fun mrsDeclareRef(
    store: VariableStore,
    name: String,
    rank: Int,
    isStr: Boolean = false,
): RefUserVar {
    store.declareUserVar(
        DimSpec(name, isStr, IntArray(rank) { 0 }, isRef = true, isPrivate = true),
        "F",
    )
    store.enterFunction("F")
    return store.userVar(name) as RefUserVar
}

class MultiDimRefSortTest {

    @Test
    fun `2 维 REF 参与排序按目标行整体重排`() {
        val (ctx, store) = mrsEnv()
        store.writeInt("FLAG", longArrayOf(0), 3)
        store.writeInt("FLAG", longArrayOf(1), 1)
        store.writeInt("FLAG", longArrayOf(2), 2)
        val ref = mrsDeclareRef(store, "MAT", rank = 2)
        // 目标 3x2：行 0=[30,31] 行 1=[10,11] 行 2=[20,21]（行主序压平）
        val target = longArrayOf(30, 31, 10, 11, 20, 21)
        ref.bindInt(target, intArrayOf(3, 2))
        // 同场加一个普通二维数组，钉住 REF 与普通数组可在同一次调用混用
        store.declareUserVar(DimSpec("T2", false, intArrayOf(3, 2)))
        longArrayOf(300, 301, 100, 101, 200, 201).forEachIndexed { i, v ->
            store.writeInt("T2", longArrayOf((i / 2).toLong(), (i % 2).toLong()), v)
        }
        // 键 [3,1,2] ⇒ 序 [1,2,0]：行 1→0 行 2→1 行 0→2
        assertEquals(1L, mrsIv(mrsCall(ctx, "ARRAYMSORT", mrsRef("FLAG"), mrsRef("MAT"), mrsRef("T2"))))
        assertEquals(listOf(1L, 2L, 3L), (0L..2L).map { store.mrsIntOf("FLAG", it) })
        assertEquals(listOf(10L, 11L, 20L, 21L, 30L, 31L), target.toList())
        assertEquals(
            listOf(100L, 101L, 200L, 201L, 300L, 301L),
            (0..5).map { k -> store.mrsIntOf("T2", (k / 2).toLong(), (k % 2).toLong()) },
        )
        store.leaveFunction()
    }

    @Test
    fun `3 维 REF 参与排序按行主序行重排`() {
        val (ctx, store) = mrsEnv()
        store.writeInt("FLAG", longArrayOf(0), 2)
        store.writeInt("FLAG", longArrayOf(1), 1)
        store.writeInt("FLAG", longArrayOf(2), 3)
        val ref = mrsDeclareRef(store, "CUBE", rank = 3)
        // 目标 3x2x2（行宽 4）：行 0=[20..23] 行 1=[10..13] 行 2=[30..33]
        val target = longArrayOf(20, 21, 22, 23, 10, 11, 12, 13, 30, 31, 32, 33)
        ref.bindInt(target, intArrayOf(3, 2, 2))
        // 键 [2,1,3] ⇒ 序 [1,0,2]：行 1→0 行 0→1 行 2→2
        assertEquals(1L, mrsIv(mrsCall(ctx, "ARRAYMSORT", mrsRef("FLAG"), mrsRef("CUBE"))))
        assertEquals(
            listOf(10L, 11L, 12L, 13L, 20L, 21L, 22L, 23L, 30L, 31L, 32L, 33L),
            target.toList(),
        )
        // 经 store 寻址读回：行主序压平口径一致（refElem 与排序的 indices 同一公式）
        assertEquals(13L, store.mrsIntOf("CUBE", 0, 1, 1))
        assertEquals(20L, store.mrsIntOf("CUBE", 1, 0, 0))
        store.leaveFunction()
    }

    @Test
    fun `字符串 2 维 REF 参与排序`() {
        val (ctx, store) = mrsEnv()
        store.declareUserVar(DimSpec("SKA", true, intArrayOf(2)))
        store.writeStr("SKA", longArrayOf(0), "b")
        store.writeStr("SKA", longArrayOf(1), "a")
        val ref = mrsDeclareRef(store, "SREF", rank = 2, isStr = true)
        // 目标 2x2：行 0=["B1","B2"] 行 1=["A1","A2"]
        val target = intArrayOf(
            store.pool.intern("B1"), store.pool.intern("B2"),
            store.pool.intern("A1"), store.pool.intern("A2"),
        )
        ref.bindStr(target, intArrayOf(2, 2))
        assertEquals(1L, mrsIv(mrsCall(ctx, "ARRAYMSORT", mrsRefS("SKA"), mrsRefS("SREF"))))
        assertEquals("a", store.mrsStrOf("SKA", 0))
        assertEquals("A1", store.mrsStrOf("SREF", 0, 0))
        assertEquals("A2", store.mrsStrOf("SREF", 0, 1))
        assertEquals("B1", store.mrsStrOf("SREF", 1, 0))
        assertEquals("B2", store.mrsStrOf("SREF", 1, 1))
        store.leaveFunction()
    }

    @Test
    fun `REF 行数不足时返回 0 且目标不动`() {
        val (ctx, store) = mrsEnv()
        store.writeInt("FLAG", longArrayOf(0), 3)
        store.writeInt("FLAG", longArrayOf(1), 1)
        store.writeInt("FLAG", longArrayOf(2), 2)
        val ref = mrsDeclareRef(store, "MAT", rank = 2)
        // 只有 2 行 < 键非零段 3 个（spec: FunctionMethodCreator.cs:5177-5180 GetLength(0) 判定）
        val target = longArrayOf(1, 2, 3, 4)
        ref.bindInt(target, intArrayOf(2, 2))
        assertEquals(0L, mrsIv(mrsCall(ctx, "ARRAYMSORT", mrsRef("FLAG"), mrsRef("MAT"))))
        assertEquals(listOf(1L, 2L, 3L, 4L), target.toList(), "返回 0 的路径不得改动目标")
        store.leaveFunction()
    }

    @Test
    fun `未绑定的多维 REF 抛 EmptyRefVar`() {
        val (ctx, store) = mrsEnv()
        store.writeInt("FLAG", longArrayOf(0), 1)
        mrsDeclareRef(store, "MAT", rank = 2) // 只声明不绑定
        // spec: ReferenceToken.cs:116-123 —— GetArray() 对未绑定 REF 抛 EmptyRefVar
        val e = assertFailsWith<CodeEE> {
            mrsCall(ctx, "ARRAYMSORT", mrsRef("FLAG"), mrsRef("MAT"))
        }
        assertTrue(e.message!!.contains("EmptyRefVar"), "实际：${e.message}")
        store.leaveFunction()
    }

    @Test
    fun `ARRAYMSORTEX 名单可含多维 REF`() {
        val (ctx, store) = mrsEnv()
        store.writeInt("FLAG", longArrayOf(0), 3)
        store.writeInt("FLAG", longArrayOf(1), 1)
        store.writeInt("FLAG", longArrayOf(2), 2)
        store.declareUserVar(DimSpec("NM", true, intArrayOf(1)))
        store.writeStr("NM", longArrayOf(0), "MAT")
        val ref = mrsDeclareRef(store, "MAT", rank = 2)
        val target = longArrayOf(30, 31, 10, 11, 20, 21)
        ref.bindInt(target, intArrayOf(3, 2))
        assertEquals(1L, mrsIv(mrsCall(ctx, "ARRAYMSORTEX", "FLAG", mrsRefS("NM"))))
        assertEquals(listOf(10L, 11L, 20L, 21L, 30L, 31L), target.toList())
        store.leaveFunction()
    }

    @Test
    fun `键仍必须是 1 维：多维 REF 作键报错`() {
        val (ctx, store) = mrsEnv()
        val ref = mrsDeclareRef(store, "MAT", rank = 2)
        ref.bindInt(longArrayOf(1, 2, 3, 4), intArrayOf(2, 2))
        // spec: FunctionMethodCreator.cs:5085-5096 —— ArgTypes[0] = RefAny1D
        val e = assertFailsWith<CodeEE> {
            mrsCall(ctx, "ARRAYMSORT", mrsRef("MAT"), mrsRef("MAT"))
        }
        assertTrue(e.message!!.contains("1 维数组"), "实际：${e.message}")
        store.leaveFunction()
    }

    @Test
    fun `1 维 REF 参与排序行为不回退`() {
        val (ctx, store) = mrsEnv()
        store.writeInt("FLAG", longArrayOf(0), 2)
        store.writeInt("FLAG", longArrayOf(1), 1)
        val ref = mrsDeclareRef(store, "REFV1", rank = 1)
        // 只重排前 order.size=2 个元素（spec: FunctionMethodCreator.cs:5145-5154）
        val target = longArrayOf(30, 10, 20)
        ref.bindInt(target)
        assertEquals(1L, mrsIv(mrsCall(ctx, "ARRAYMSORT", mrsRef("FLAG"), mrsRef("REFV1"))))
        assertEquals(listOf(10L, 30L, 20L), target.toList())
        store.leaveFunction()
    }
}
