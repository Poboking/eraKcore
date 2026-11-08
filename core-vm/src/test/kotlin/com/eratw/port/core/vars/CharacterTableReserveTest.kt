package com.eratw.port.core.vars

import com.eratw.port.l3.StateHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * fix-varcapacity：角色表翻倍预留的**空尾钳制**面。
 *
 * 钉三件事（出口判据 ①② 的角色表面）：
 * 1. 建角色仍走 2× 翻倍（1→2→4→…→128 的完整阶梯一个字节不动）；
 * 2. 翻倍得到的空尾被钳到 `max(64, needed shr 3)` 行内 —— 这是移植侧多出原版的一笔
 *    （原版 `List<CharacterData>` 翻倍只预留引用位，spec: CharacterData.cs:55-126），
 *    旧实现「角色 129 / 容量 256」白占 127 整行；
 * 3. 容量不进 `StateHash`（真跑哈希对照，不是只写断言）：同一可观测终态、**不同扩容历史**
 *    （193 vs 258 的容量）求得的 digest 逐值相等 ⇒ 「缩尾对脚本与存档不可见」的直接证据
 *    （口径依据：StateHash 类 KDoc「不纳入项 6」）。
 */
class CharacterTableReserveTest {

    private fun idx(vararg v: Long) = v

    @Test
    fun `建角色仍是 2 倍翻倍 阶梯 1 到 128 逐字不变`() {
        val s = VariableStore(initialCharaCapacity = 1)
        val caps = ArrayList<Int>()
        repeat(128) {
            s.addPseudoCharacter()
            caps += s.characters.allocatedCapacity
        }
        // 阶梯形状：每次越界恰好 ×2（钳制下限 64 行让容量 ≤128 的整段翻倍不受影响）。
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 64, 128), caps.distinct(), "翻倍阶梯被钳制改形")
        assertEquals(2, caps[1]); assertEquals(4, caps[2]); assertEquals(8, caps[4])
        assertEquals(16, caps[8]); assertEquals(32, caps[16]); assertEquals(64, caps[32])
        assertEquals(128, caps[64])
    }

    @Test
    fun `空尾钳制在第 129 个角色生效 256 变 193`() {
        val s = VariableStore(initialCharaCapacity = 1)
        repeat(129) { s.addPseudoCharacter() }
        val table = s.characters
        assertEquals(129, table.count)
        // 旧实现：翻倍直给 256（空尾 127 行 × 行字节 ≈ 26 MiB 在册量级）；
        // 钳制后：needed(129) + max(FLOOR=64, 129 shr 3 = 16) = 193。
        assertEquals(193, table.allocatedCapacity, "翻倍应被钳到 needed + 空尾上限")
        val tail = table.allocatedCapacity - table.count
        assertEquals(CharacterTable.RESERVE_TAIL_FLOOR_ROWS, tail)
        // 空尾字节数必须远小于旧在册读数 17.8 MiB（P3 实测角色 168 / 容量 256、空尾 88 行）。
        val oldReserveBytes = 18_690_144L // 88 行 × 212,388 B/行（baseline 相位报告 §2b 原样读数）
        assertTrue(
            tail.toLong() * table.rowBytes() < oldReserveBytes,
            "钳制后空尾字节仍不低于旧读数：${tail * table.rowBytes()} vs $oldReserveBytes",
        )
    }

    @Test
    fun `钳制后继续建角色 翻倍按阶梯推进且尾部不越上限`() {
        val s = VariableStore(initialCharaCapacity = 1)
        repeat(194) { s.addPseudoCharacter() }
        // needed=194 时 capacity=193 越界：翻倍 386 被 max(64, 194 shr 3)=64 钳到 258。
        assertEquals(258, s.characters.allocatedCapacity)
        repeat(64) { s.addPseudoCharacter() } // 到 258
        s.addPseudoCharacter()                 // needed=259 → 翻倍 518 钳到 259+64=323
        assertEquals(323, s.characters.allocatedCapacity)
        // 全程空尾不超过 `max(FLOOR, needed shr SHIFT)`：推进到 400 抽查一次。
        while (s.characters.count < 400) s.addPseudoCharacter()
        val cap = s.characters.allocatedCapacity
        val cnt = s.characters.count
        assertTrue(cap - cnt <= maxOf(CharacterTable.RESERVE_TAIL_FLOOR_ROWS, cnt shr CharacterTable.RESERVE_TAIL_SHIFT),
            "空尾 ${cap - cnt} 越比例上限")
    }

    @Test
    fun `钳制扩容后按 角色号与下标 读写逐字不变`() {
        val s = VariableStore(initialCharaCapacity = 1)
        repeat(200) { i ->
            s.addPseudoCharacter()
            s.writeInt("NO", idx(i.toLong()), (i * 3 + 1).toLong())
            s.writeInt("CFLAG", idx(i.toLong(), 3), (i * 7).toLong())
        }
        // 越界扩容（含钳制路径）+ 收缩历史：中间态逐格复核。
        val snapshot = ArrayList<Long>()
        for (i in 0 until 200) snapshot += s.readInt("NO", idx(i.toLong()))
        repeat(100) { s.addPseudoCharacter() }
        repeat(100) { k -> s.deleteCharacter(299L - k) }
        for (i in 0 until 200) {
            assertEquals((i * 3 + 1).toLong(), s.readInt("NO", idx(i.toLong())), "NO:$i 漂移")
            assertEquals(snapshot[i], s.readInt("NO", idx(i.toLong())))
            assertEquals((i * 7).toLong(), s.readInt("CFLAG", idx(i.toLong(), 3)), "CFLAG:$i:3 漂移")
        }
        assertEquals(200, s.characters.count)
        assertTrue(s.characters.allocatedCapacity >= 200)
    }

    @Test
    fun `StateHash 只看得到 count 以内的行 看不到容量`() {
        // 轮 A：130 只角色（扩容历史停在钳制档 193）。
        val a = VariableStore(initialCharaCapacity = 1)
        repeat(130) { i ->
            a.addPseudoCharacter()
            a.writeInt("NO", idx(i.toLong()), (i + 1).toLong())
            a.writeInt("CFLAG", idx(i.toLong(), 2), (i * 5).toLong())
        }
        // 轮 B：同一终态，但多走一段「建 70 只、再删 70 只」的扩容历史（容量被推过钳制档）。
        val b = VariableStore(initialCharaCapacity = 1)
        repeat(130) { i ->
            b.addPseudoCharacter()
            b.writeInt("NO", idx(i.toLong()), (i + 1).toLong())
            b.writeInt("CFLAG", idx(i.toLong(), 2), (i * 5).toLong())
        }
        repeat(70) { b.addPseudoCharacter() }
        repeat(70) { k -> b.deleteCharacter(199L - k) }

        // 前提自证：两轮**容量确实不同**，可观测行逐格相同 —— 否则这条哈希对照什么都没测。
        assertNotEquals(a.characters.allocatedCapacity, b.characters.allocatedCapacity,
            "两轮扩容历史没拉开容量差，对照失效")
        for (i in 0 until 130) {
            assertEquals(a.readInt("NO", idx(i.toLong())), b.readInt("NO", idx(i.toLong())))
            assertEquals(a.readInt("CFLAG", idx(i.toLong(), 2)), b.readInt("CFLAG", idx(i.toLong(), 2)))
        }

        val da = StateHash.digest(a)
        val db = StateHash.digest(b)
        assertEquals(emptyList(), da.errors, "轮 A 渲染报错：${da.errors}")
        assertEquals(emptyList(), db.errors, "轮 B 渲染报错：${db.errors}")
        assertEquals(da.total, db.total, "容量不同必须不动状态哈希（不纳入项 6），实差首簇=" + da.firstDiffCluster(db))
        assertEquals(null, da.firstDiffCluster(db), "出现首个差异簇：" + da.firstDiffCluster(db))
    }
}
