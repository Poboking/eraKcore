package com.eratw.port.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * LOCAL / LOCALS / LOCALF 的生命周期**单一口径**：跨 CALL 持久，只在 ResetData、读档、
 * 换文件（Dispose 面）三处归零。
 *
 * 权威证据（两代引擎同构，不存在「按引擎切换」的第二条口径）：
 * - 官方 1.824：`reference/emuera-cs` 的 `VariableData.cs:3365 SetDefaultLocalValue`、
 *   `:3377 ClearLocalValue`；前者全库只有 3 个调用点（`VariableEvaluator.cs:1339` 的
 *   `ResetData()`、`:2441` 文本读档、`:2626` 二进制读档），后者只有 `VariableData.cs:4215`
 *   的 `Dispose()`。
 * - skia 魔改引擎（4.981 实跑的 Emuera_skia，1.824+v22+EMv18+EEv53）：
 *   `unpacked/skia-decompiled` 的 `VariableData.cs:3362 SetDefaultLocalValue`、
 *   `:3374 ClearLocalValue`；调用点同样只有 `VariableEvaluator.cs:1339`（`ResetData()`，
 *   方法体起于 `:1336`）、`:2441`（`LoadFromStream`，起于 `:2428`）、`:2626`
 *   （`LoadFromStreamBinary`，起于 `:2609`）三处，`ClearLocalValue` 只在
 *   `VariableData.cs:4212` 的 `Dispose()`（起于 `:4210`）。
 * - 存储形态也是同一份：局部实例按**函数名**缓存、命中即复用 ——
 *   `IdentifierDictionary.cs:453-475` 先 `GetExistLocalVariableToken(subKey)`，
 *   缺席才 `GetNewLocalVariableToken`；`subKey = 调用方所在标签行的 LabelName`
 *   （`IdentifierDictionary.cs:467`）。函数返回不重建、不清零 ⇒ 递归帧共享同一份数组。
 *
 * 语料侧的对应钉子见 `boot/DlcPagingReproTest`（4.981 `@DLCOPTIONPRINT` 入口补
 * `LOCAL = 0`，即「持久」口径下玩家现象的正解）。
 */
class LocalSemanticsTest {

    /** LOCAL 的声明长只要盖住用到的下标即可（出厂 1000 会白建一整列）。 */
    private fun store(): VariableStore {
        val store = VariableStore()
        store.defineLocalLengths(
            "F",
            VariableStore.LocalLengths(local = 4, locals = 4, floatLocal = 4),
        )
        return store
    }

    private fun at(i: Long) = longArrayOf(i)

    @Test
    fun `LOCAL 跨 CALL 持久`() {
        val store = store()
        store.enterFunction("F")
        try {
            store.writeInt("LOCAL", at(0), 5L)
        } finally {
            store.leaveFunction()
        }
        store.enterFunction("F")
        try {
            assertEquals(
                5L,
                store.readInt("LOCAL", at(0)),
                "局部实例按函数名缓存、返回不清零（reference VariableLocal.cs:15,26-33；" +
                    "skia IdentifierDictionary.cs:453-475）",
            )
        } finally {
            store.leaveFunction()
        }
    }

    @Test
    fun `LOCALS 跨 CALL 持久`() {
        val store = store()
        store.enterFunction("F")
        try {
            store.writeStr("LOCALS", at(1), "keep")
        } finally {
            store.leaveFunction()
        }
        store.enterFunction("F")
        try {
            assertEquals("keep", store.readStr("LOCALS", at(1)), "字符串局部与整数局部同一条生命周期")
        } finally {
            store.leaveFunction()
        }
    }

    @Test
    fun `LOCALF 跨 CALL 持久`() {
        val store = store()
        store.enterFunction("F")
        try {
            store.writeFloat("LOCALF", at(2), 1.75)
        } finally {
            store.leaveFunction()
        }
        store.enterFunction("F")
        try {
            assertEquals(1.75, store.readFloat("LOCALF", at(2)), "浮点局部同样按函数一份、跨 CALL 存活")
        } finally {
            store.leaveFunction()
        }
    }

    /**
     * 递归：内层与外层是**同一个实例**（原版只有「函数名 → token」这一层表，没有帧表）。
     * 所以内层写的值在外层返回后仍看得见 —— 既不归零，也不回滚外层。
     */
    @Test
    fun `递归帧共享同一份局部实例，返回后不回滚`() {
        val store = store()
        store.enterFunction("F")
        try {
            store.writeInt("LOCAL", at(0), 5L)
            store.enterFunction("F") // 同名递归：官方取到的是同一份实例
            try {
                assertEquals(5L, store.readInt("LOCAL", at(0)), "递归不新建零值上下文")
                store.writeInt("LOCAL", at(0), 7L)
            } finally {
                store.leaveFunction()
            }
            assertEquals(7L, store.readInt("LOCAL", at(0)), "内层写的是外层那一份，返回后不回滚")
        } finally {
            store.leaveFunction()
        }
    }

    /** 归零点①RESETDATA / ②读档：都走 [VariableStore.resetLocalValues]（spec 见其 KDoc）。 */
    @Test
    fun `resetLocalValues 是唯一按函数清零的复位面`() {
        val store = store()
        store.enterFunction("F")
        try {
            store.writeInt("LOCAL", at(0), 5L)
            store.writeStr("LOCALS", at(0), "s")
            store.writeFloat("LOCALF", at(0), 2.5)
        } finally {
            store.leaveFunction()
        }
        store.resetLocalValues()
        store.enterFunction("F")
        try {
            assertEquals(0L, store.readInt("LOCAL", at(0)), "ResetData 面归零整数局部")
            assertEquals("", store.readStr("LOCALS", at(0)), "ResetData 面归零字符串局部")
            assertEquals(0.0, store.readFloat("LOCALF", at(0)), "ResetData 面归零浮点局部")
        } finally {
            store.leaveFunction()
        }
    }

    /** 归零点③换文件（Dispose 面）：[VariableStore.clearLocalValues] 直接丢实例。 */
    @Test
    fun `clearLocalValues 丢掉实例后重建从零开始`() {
        val store = store()
        store.enterFunction("F")
        try {
            store.writeInt("LOCAL", at(0), 5L)
        } finally {
            store.leaveFunction()
        }
        store.clearLocalValues()
        store.enterFunction("F")
        try {
            assertEquals(0L, store.readInt("LOCAL", at(0)), "token 表清空 ⇒ 下次访问新建零值实例")
        } finally {
            store.leaveFunction()
        }
    }

    /**
     * `enterFunction` 的 [resetLocals] 形参是「调用即清零」的**唯一**入口，且生产链
     * 恒传 false（见 `ProcessRuntime` 的调用点注释）—— 门控删掉后，任何 per-call 归零
     * 都必须显式走这个参数，不许再藏在构造期开关里。
     */
    @Test
    fun `只有显式 resetLocals 才在进函数时清零`() {
        val store = store()
        store.enterFunction("F")
        try {
            store.writeInt("LOCAL", at(0), 5L)
        } finally {
            store.leaveFunction()
        }
        store.enterFunction("F", resetLocals = true)
        try {
            assertEquals(0L, store.readInt("LOCAL", at(0)), "显式清零走形参，不是构造期门")
        } finally {
            store.leaveFunction()
        }
        store.enterFunction("F")
        try {
            assertEquals(0L, store.readInt("LOCAL", at(0)), "上一次显式清零的结果留到下一次（持久）")
        } finally {
            store.leaveFunction()
        }
    }
}
