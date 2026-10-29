package com.eratw.port.core.fn

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.proc.RootedFileOps
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * FN-08：ENUMFILES 第 4 参（目标字符串数组）的写入位置口径。
 *
 * ## 权威（`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`）
 *
 * - :370 `string[] array2 = arguments.Count != 4 ? exm.VEvaluator.RESULTS_ARRAY
 *   : ((arguments[3] as VariableTerm).Identifier.GetArray() as string[])`
 *   —— 取到的是**整份目标数组**；第 4 参上写的显式下标（`RESULTS:5`）既不缩小取到的数组、
 *   也不参与寻址，连下标表达式本身都不会被求值（原版只碰 `Identifier`，从不求值 `arguments[3]`）。
 * - :371 `int num = Math.Min(array.Length, array2.Length)`
 *   —— `num = min(枚举到的文件数, 目标数组长度)`。
 * - :372 `Array.Copy(array, array2, num)` —— `Array.Copy` 的三参重载恒从源/目标数组下标 **0**
 *   起拷，与第 4 参里写了下标几无关。
 * - :373 `return num`。
 *
 * ## 修复前（`HostMethods.kt` 的 `base + i`）
 *
 * 旧实现把第 4 参的下标当「起始偏移」拼进写入下标：`RESULTS:5` 得到 `base = [5]`，循环里
 * `base + i` 又追加一个元素下标（Kotlin 对 `LongArray` 的 `plus(Long)` 是**追加**），于是
 * 写 `RESULTS:5:0` 这类 2 下标路径 —— 1 维字符串数组在 `VariableStore.checkElement`
 * （`VariableStore.kt:1480` 的 `indices.size != def.argCount`）处直接抛「需要 1 个下标（实际 2）」。
 * 修复后：无论第 4 参带不带下标，都取整份目标数组、从下标 0 起写 `num` 个，返回 `num`。
 *
 * 测试形态（不依赖编译期类型检查，直接构造 `VarRef` 走 `evalRef` 路径）：
 * - `RESULTS:5` 与 `RESULTS` 两形态：3 个文件 ⇒ 返回 3、写 0..2、0 以外未涉及的格原值不动；
 * - 文件数（3）> 数组长（2，经 `declareUserVar` 造的小数组）：返回 2、只写 0..1。
 */
private class EnumFilesHost(override val fileOps: FileOps) : BuiltinHost

private fun enumCtx(ops: FileOps, store: VariableStore): BuiltinEvalContext =
    BuiltinEvalContext(TestEvalContext(store), store, EnumFilesHost(ops))

private fun enumCall(ctx: BuiltinEvalContext, vararg a: Expr?): Long =
    (ctx.callFunctionRef("ENUMFILES", a.toList()) as Value.IntV).v

/** 字符串一维数组引用（`name` 或 `name:idx...`）；[idx] 为空即不带显式下标。 */
private fun strRef(name: String, vararg idx: Long): VarRef =
    VarRef(name, idx.map { IntLiteral(it) }, operandType = OperandType.STR)

private fun cell(store: VariableStore, name: String, index: Long): String =
    store.readStr(name, longArrayOf(index))

/** 临时目录下造 `d/a.txt` `d/b.txt` `d/c.txt` 三个文件；返回宿主给出的排序后期望串。 */
private fun threeFiles(tmp: Path): List<String> {
    Files.createDirectory(tmp.resolve("d"))
    for (n in listOf("a", "b", "c")) {
        Files.write(tmp.resolve("d").resolve("$n.txt"), n.toByteArray())
    }
    return listOf("d/a.txt", "d/b.txt", "d/c.txt")
}

/** 给 RESULTS 的 3/4/5 号格预置哨兵值，用来证明「没被写的格不动」。 */
private fun seedResults(store: VariableStore) {
    store.writeStr("RESULTS", longArrayOf(3), "S3")
    store.writeStr("RESULTS", longArrayOf(4), "S4")
    store.writeStr("RESULTS", longArrayOf(5), "S5")
}

class EnumFilesOffsetTest {

    @Test
    fun `第 4 参带显式下标时仍从数组 0 起写`(@TempDir tmp: Path) {
        val expected = threeFiles(tmp)
        val store = VariableStore()
        seedResults(store)
        val ctx = enumCtx(RootedFileOps(tmp), store)
        // 原版 :370 取整份 RESULTS（不是 RESULTS:5 起始的子段），:372 从 0 起拷。
        assertEquals(
            3L,
            enumCall(ctx, StrLiteral("d"), StrLiteral("*.txt"), IntLiteral(0), strRef("RESULTS", 5)),
        )
        assertEquals(expected, (0L..2L).map { cell(store, "RESULTS", it) })
        // 显式下标 5 自己不被写；3、4 号格同样保持哨兵值（Array.Copy 只碰 0..num-1）。
        assertEquals("S3", cell(store, "RESULTS", 3))
        assertEquals("S4", cell(store, "RESULTS", 4))
        assertEquals("S5", cell(store, "RESULTS", 5))
    }

    @Test
    fun `第 4 参不带下标时与带下标同形`(@TempDir tmp: Path) {
        val expected = threeFiles(tmp)
        val store = VariableStore()
        seedResults(store)
        val ctx = enumCtx(RootedFileOps(tmp), store)
        assertEquals(
            3L,
            enumCall(ctx, StrLiteral("d"), StrLiteral("*.txt"), IntLiteral(0), strRef("RESULTS")),
        )
        assertEquals(expected, (0L..2L).map { cell(store, "RESULTS", it) })
        // 不带下标与带下标两种形态都不碰 3..5（两种形态的写入面完全相同）。
        assertEquals("S3", cell(store, "RESULTS", 3))
        assertEquals("S5", cell(store, "RESULTS", 5))
    }

    @Test
    fun `文件数大于数组长时只写数组长个且从 0 起`(@TempDir tmp: Path) {
        val expected = threeFiles(tmp)
        val store = VariableStore()
        // 2 格字符串数组：num = min(3 个文件, 2 格) = 2（:371）。
        store.declareUserVar(DimSpec("SMALL", isStr = true, dims = intArrayOf(2)))
        store.writeStr("SMALL", longArrayOf(0), "OLD0")
        store.writeStr("SMALL", longArrayOf(1), "OLD1")
        val ctx = enumCtx(RootedFileOps(tmp), store)
        // 显式下标 1 不是起点：两个格都从 0 起被覆写成前两个文件。
        assertEquals(
            2L,
            enumCall(ctx, StrLiteral("d"), StrLiteral("*.txt"), IntLiteral(0), strRef("SMALL", 1)),
        )
        assertEquals(expected.subList(0, 2), (0L..1L).map { cell(store, "SMALL", it) })

        // 不带下标形态读数相同（重新预置哨兵，证明同一条从 0 起写的路径）。
        store.writeStr("SMALL", longArrayOf(0), "OLD0")
        store.writeStr("SMALL", longArrayOf(1), "OLD1")
        assertEquals(
            2L,
            enumCall(ctx, StrLiteral("d"), StrLiteral("*.txt"), IntLiteral(0), strRef("SMALL")),
        )
        assertEquals(expected.subList(0, 2), (0L..1L).map { cell(store, "SMALL", it) })
    }

    @Test
    fun `带显式下标不改变目录不存在返回 -1`(@TempDir tmp: Path) {
        val store = VariableStore()
        seedResults(store)
        val ctx = enumCtx(RootedFileOps(tmp), store)
        // :355-357 的 -1 分支先于第 4 参取数组（:370），与下标形态无关。
        assertEquals(
            -1L,
            enumCall(ctx, StrLiteral("nope"), StrLiteral("*.txt"), IntLiteral(0), strRef("RESULTS", 5)),
        )
        assertEquals("S3", cell(store, "RESULTS", 3))
        assertEquals("S5", cell(store, "RESULTS", 5))
    }
}
