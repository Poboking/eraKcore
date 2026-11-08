package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * L3 稳定性：角色表列容量/跨距的 Int 溢出。
 *
 * 旧实现 `blockSize = sizes.fold(1) { a, b -> a * b }`、`LongArray(capacity * blockSize)`
 * 都在 Int 域相乘：
 * - `sizes = [50000, 50000]`（2.5e9 > Int.MAX）⇒ blockSize 变负数 ⇒ 构造直接
 *   `NegativeArraySizeException`；
 * - `blockSize = 9e8`、`capacity = 4`（3.6e9 > Int.MAX）⇒ 分配长度变负数 ⇒ 同上。
 * 两者都不是 ERB 层可捕获的 CodeEE。修复后统一抛 CodeEE，且合法尺寸的读写结果不变。
 */
class StabilityL3CharacterTableTest {

    @Test
    fun `二维列元素数超过 Int 上限时抛 CodeEE`() {
        val sizes = VariableSizes()
        sizes.charaInt2D[0] = intArrayOf(50_000, 50_000) // 2.5e9
        val e = assertFailsWith<CodeEE> { CharacterTable(sizes, VarConfig()) }
        assertTrue(e.message!!.contains("超出可分配上限"), e.message)
    }

    @Test
    fun `行数乘每行元素数溢出时抛 CodeEE`() {
        val e1 = assertFailsWith<CodeEE> {
            CharacterTable.LongCol(intArrayOf(30_000, 30_000), 4) // 4 x 9e8 = 3.6e9
        }
        assertTrue(e1.message!!.contains("容量溢出"), e1.message)
        val e2 = assertFailsWith<CodeEE> {
            CharacterTable.StrCol(intArrayOf(30_000, 30_000), 4)
        }
        assertTrue(e2.message!!.contains("容量溢出"), e2.message)
    }

    @Test
    fun `合法尺寸仍按行主序读写并扩容`() {
        val table = CharacterTable(VariableSizes(), VarConfig(), requestedCapacity = 1)
        table.addBare()
        table.addBare() // 触发 1 -> 2 扩容
        assertEquals(2, table.count)
        assertEquals(2, table.allocatedCapacity)
        val cflag = table.int1[VariableCode.lowIndex(VariableCode.CFLAG)]
        cflag.set(1, 3, 42L)
        assertEquals(42L, cflag.get(1, 3))
        assertEquals(0L, cflag.get(0, 3))
        assertTrue(table.rowBytes() > 0L)
    }
}
