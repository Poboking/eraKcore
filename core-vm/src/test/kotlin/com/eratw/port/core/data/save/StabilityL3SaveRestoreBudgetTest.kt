package com.eratw.port.core.data.save

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * L3 稳定性：存档头声明的角色数与负载大小脱钩。
 *
 * 二进制角色块的最小形态是「立刻 EOC」——每个角色在负载里只占 1 字节，但还原时每个角色
 * 都要在全部角色列（默认 84 x 100 等）上占一行。旧实现直接
 * `repeat(characters.size) { addBare() }`：1e6 个空块（约 1 MiB 档）会要求数十 GB 列内存，
 * `OutOfMemoryError` 打穿读档链。修复后按「角色数 × rowBytes」预算先判，超限抛 CodeEE，
 * 且检查先于 `deleteAllCharacters()`，不会把现有角色清掉。
 */
class StabilityL3SaveRestoreBudgetTest {

    @Test
    fun `超量角色数在扩容前被拒绝且不清空现有角色`() {
        val store = VariableStore()
        store.characters.addBare()
        assertEquals(1, store.characters.count)
        assertTrue(store.characters.rowBytes() > 64_000L)

        // 400000 x 约 67.6 KB ≈ 25 GB，远超 256 MiB 预算。
        val data = SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "",
            characters = List(400_000) { emptyList() },
        )
        val e = assertFailsWith<CodeEE> { data.applyTo(store) }
        assertTrue(e.message!!.contains("超过可还原上限"), e.message)
        assertEquals(1, store.characters.count)
    }

    @Test
    fun `正常角色数不受预算影响`() {
        val store = VariableStore()
        val data = SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "",
            characters = List(2) { emptyList() },
        )
        data.applyTo(store)
        assertEquals(2, store.characters.count)
    }
}
