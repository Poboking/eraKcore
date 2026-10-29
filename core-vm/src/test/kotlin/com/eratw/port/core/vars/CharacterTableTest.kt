package com.eratw.port.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 角色库：模板拷贝、增删重排、拷贝交换、排序稳定性、角色 2D 变量。 */
class CharacterTableTest {

    private fun idx(vararg v: Long) = v

    private fun store(vararg templates: CharacterTemplate): VariableStore {
        val s = VariableStore()
        templates.forEach { s.registerTemplate(it) }
        return s
    }

    private fun template(no: Long, name: String) = CharacterTemplate(
        no = no,
        name = name,
        callname = "$name 呼称",
        maxbase = mapOf(5 to 50L),
        cflag = mapOf(0 to 1L),
        relation = mapOf(0 to 9L),
        cstr = mapOf(0 to "cstr-$name"),
    )

    @Test
    fun `ADDCHARA 按模板拷贝字段`() {
        val s = store(template(10, "フラン"))
        assertEquals(0, s.addCharacter(10))
        assertEquals(1L, s.readInt("CHARANUM", idx()))
        assertEquals(10L, s.readInt("NO", idx(0)))
        assertEquals("フラン", s.readStr("NAME", idx(0)))
        assertEquals("フラン 呼称", s.readStr("CALLNAME", idx(0)))
        // Maxbase 双写 BASE/MAXBASE，spec: CharacterData.cs:136-142
        assertEquals(50L, s.readInt("BASE", idx(0, 5)))
        assertEquals(50L, s.readInt("MAXBASE", idx(0, 5)))
        assertEquals(1L, s.readInt("CFLAG", idx(0, 0)))
        assertEquals(9L, s.readInt("RELATION", idx(0, 0)))
        assertEquals("cstr-フラン", s.readStr("CSTR", idx(0, 0)))
    }

    @Test
    fun `RELATION 未指定的元素填 relationDef`() {
        val s = VariableStore(config = VarConfig(relationDef = 2))
        s.addPseudoCharacter()
        assertEquals(2L, s.readInt("RELATION", idx(0, 0)))
        assertEquals(2L, s.readInt("RELATION", idx(0, 99)))
    }

    @Test
    fun `ADDCHARA 未知 NO 报错`() {
        val s = store()
        assertFailsWith<CodeEE> { s.addCharacter(99) }
    }

    @Test
    fun `ADDCHARA 按 CSV 号未命中时用伪角色`() {
        val s = store()
        s.addCharacterFromCsvNo(77)
        assertEquals(1L, s.readInt("CHARANUM", idx()))
        assertEquals(0L, s.readInt("NO", idx(0)))
        assertEquals("", s.readStr("NAME", idx(0)))
    }

    @Test
    fun `角色下标越界逐维报错`() {
        val s = store(template(1, "A"))
        s.addCharacter(1)
        assertFailsWith<CodeEE> { s.readInt("NO", idx(1)) }
        assertFailsWith<CodeEE> { s.readInt("NO", idx(-1)) }
        assertFailsWith<CodeEE> { s.readInt("CFLAG", idx(0, 1000)) }
        assertFailsWith<CodeEE> { s.readInt("CFLAG", idx(0, -1)) }
        // 0D 角色变量只接受 1 个下标
        assertFailsWith<CodeEE> { s.readStr("NAME", idx(0, 0)) }
    }

    @Test
    fun `DELCHARA 后续角色左移`() {
        val s = store(template(1, "A"), template(2, "B"), template(3, "C"))
        s.addCharacter(1); s.addCharacter(2); s.addCharacter(3)
        s.deleteCharacter(1)
        assertEquals(2L, s.readInt("CHARANUM", idx()))
        assertEquals(1L, s.readInt("NO", idx(0)))
        assertEquals(3L, s.readInt("NO", idx(1)))
        assertEquals("C", s.readStr("NAME", idx(1)))
        assertFailsWith<CodeEE> { s.deleteCharacter(2) }
    }

    @Test
    fun `批量删除位置重复报错`() {
        val s = store(template(1, "A"), template(2, "B"))
        s.addCharacter(1); s.addCharacter(2)
        assertFailsWith<CodeEE> { s.deleteCharacters(longArrayOf(0, 0)) }
        s.deleteCharacters(longArrayOf(0, 1))
        assertEquals(0L, s.readInt("CHARANUM", idx()))
    }

    @Test
    fun `COPYCHARA 与 SWAPCHARA`() {
        val s = store(template(1, "A"), template(2, "B"))
        s.addCharacter(1); s.addCharacter(2)
        s.writeInt("CFLAG", idx(0, 3), 42)
        // CopyChara(x, y) = CharacterList[x].CopyTo(CharacterList[y])：x 源、y 目标
        // （VariableEvaluator.cs:1365）→ copyCharacter(0, 1) 把 0 号复制到 1 号。
        s.copyCharacter(0, 1)
        assertEquals(42L, s.readInt("CFLAG", idx(1, 3)))
        assertEquals("A", s.readStr("NAME", idx(1)))

        s.swapCharacters(0, 1)
        assertEquals("A", s.readStr("NAME", idx(0)))
        assertEquals(42L, s.readInt("CFLAG", idx(0, 3)))
    }

    @Test
    fun `SORTCHARA 升序并保持相等键的原有顺序`() {
        val s = store(template(3, "X"), template(4, "Y"), template(5, "Z"))
        s.addCharacter(3); s.addCharacter(4); s.addCharacter(5)
        // MASTER 缺省 0 会被 fixMaster 钉在 0 号位（原版 MASTER 即普通变量、缺省 0，
        // VariableData.cs:3028；SortChara 的 fixMaster 分支 VariableEvaluator.cs:1414-1450），
        // 显式 -1 表示无主人公，验证全量排序语义。
        s.writeInt("MASTER", idx(0), -1)
        s.writeInt("CFLAG", idx(0, 0), 2)
        s.writeInt("CFLAG", idx(1, 0), 1)
        s.writeInt("CFLAG", idx(2, 0), 1)

        s.sortCharactersByInt("CFLAG", idx(0), ascending = true)
        assertEquals(4L, s.readInt("NO", idx(0)))
        assertEquals(5L, s.readInt("NO", idx(1)))
        assertEquals(3L, s.readInt("NO", idx(2)))
    }

    @Test
    fun `SORTCHARA 降序也稳定`() {
        val s = store(template(3, "X"), template(4, "Y"), template(5, "Z"))
        s.addCharacter(3); s.addCharacter(4); s.addCharacter(5)
        // 同上：显式 MASTER=-1 摘掉缺省 0 号位钉住，确保真的在验证降序。
        s.writeInt("MASTER", idx(0), -1)
        s.writeInt("CFLAG", idx(0, 0), 2)
        s.writeInt("CFLAG", idx(1, 0), 1)
        s.writeInt("CFLAG", idx(2, 0), 1)

        s.sortCharactersByInt("CFLAG", idx(0), ascending = false)
        assertEquals(3L, s.readInt("NO", idx(0)))
        assertEquals(4L, s.readInt("NO", idx(1)))
        assertEquals(5L, s.readInt("NO", idx(2)))
    }

    @Test
    fun `按字符串变量排序`() {
        val s = store(template(1, "c"), template(2, "a"), template(3, "b"))
        s.addCharacter(1); s.addCharacter(2); s.addCharacter(3)
        // 同上：显式 MASTER=-1 摘掉缺省 0 号位钉住（VariableEvaluator.cs:1414-1450）。
        s.writeInt("MASTER", idx(0), -1)
        s.sortCharactersByStr("NAME", idx(), ascending = true)
        assertEquals("a", s.readStr("NAME", idx(0)))
        assertEquals("b", s.readStr("NAME", idx(1)))
        assertEquals("c", s.readStr("NAME", idx(2)))
    }

    @Test
    fun `CDFLAG 是每角色 2 维`() {
        val s = store(template(1, "A"))
        s.addCharacter(1)
        s.writeInt("CDFLAG", idx(0, 0, 0), 5)
        assertEquals(5L, s.readInt("CDFLAG", idx(0, 0, 0)))
        assertFailsWith<CodeEE> { s.readInt("CDFLAG", idx(0, 1, 0)) }
        assertFailsWith<CodeEE> { s.readInt("CDFLAG", idx(0, 0)) }
        assertEquals(1, s.getLength("CDFLAG", 0))
    }

    @Test
    fun `CSTR 每角色独立`() {
        val s = store(template(1, "A"), template(2, "B"))
        s.addCharacter(1); s.addCharacter(2)
        s.writeStr("CSTR", idx(0, 1), "only-a")
        assertEquals("only-a", s.readStr("CSTR", idx(0, 1)))
        assertEquals("", s.readStr("CSTR", idx(1, 1)))
    }

    @Test
    fun `扩容后已有角色数据不丢`() {
        val s = VariableStore(initialCharaCapacity = 1)
        for (i in 1..9) {
            s.addPseudoCharacter()
            s.writeInt("NO", idx(i - 1L), i.toLong())
        }
        assertEquals(9L, s.readInt("CHARANUM", idx()))
        for (i in 0 until 9) {
            assertEquals(i + 1L, s.readInt("NO", idx(i.toLong())))
        }
        assertTrue(s.characters.allocatedCapacity >= 9)
    }
}
