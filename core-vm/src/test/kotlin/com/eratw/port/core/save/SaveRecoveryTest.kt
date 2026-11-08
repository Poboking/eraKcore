package com.eratw.port.core.save

import com.eratw.port.core.save.SaveRecovery.CommitState
import com.eratw.port.core.save.SaveRecovery.Journal
import com.eratw.port.core.save.SaveRecovery.MemberJournal
import com.eratw.port.core.save.SaveRecovery.MemberObservation
import com.eratw.port.core.save.SaveRecovery.Move
import com.eratw.port.core.save.SaveRecovery.RecoveryPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [SaveRecovery] 恢复判定判据（§8 六断电点矩阵逐行 + 介质损坏停止 + 单成员同协议）。
 *
 * 断言面：BACKED_UP 顺序不变量（PREPARED 绝不向前补完）、每成员三候选（.part 或已替换
 * 目标）、任一成员无新副本则**整对**回滚、.bak 失效即 Halt 不静默销毁、空间预算 3N。
 */
class SaveRecoveryTest {

    private fun sha(c: Char): String = c.toString().repeat(64)

    private val savOld = sha('a')
    private val savNew = sha('b')
    private val rt2Old = sha('c')
    private val rt2New = sha('d')

    private fun sav(): MemberJournal = MemberJournal("save01.sav", "save01.sav.part", "save01.sav.bak", savOld, savNew)
    private fun rt2(): MemberJournal = MemberJournal("save01.sav.rt2", "save01.sav.rt2.part", "save01.sav.rt2.bak", rt2Old, rt2New)

    private fun journal(state: CommitState, vararg members: MemberJournal): Journal =
        Journal("tx-1", 7, 8, members.toList(), state)

    private fun obs(m: MemberJournal, part: String?, target: String?, bak: String?): MemberObservation =
        MemberObservation(m.target, part, target, bak)

    /** 两成员旧对完好、.bak 对已校验（BACKED_UP 不变量成立）的观测。 */
    private fun backedUpObs(s: MemberJournal, r: MemberJournal, sPart: String?, sTarget: String?, rPart: String?, rTarget: String?) =
        listOf(obs(s, sPart, sTarget, savOld), obs(r, rPart, rTarget, rt2Old))

    @Test
    fun `断电点2_PREPARED未BACKED_UP_保留旧档丢残件`() {
        val s = sav()
        val r = rt2()
        // 即便两个 .part 指纹都正好是新版本，也不向前补完：旧 .bak 对尚未校验完成，
        // 回滚路不存在，唯一安全动作是保留旧档（顺序不变量）。
        val plan = SaveRecovery.plan(
            journal(CommitState.PREPARED, s, r),
            backedUpObs(s, r, savNew, savOld, rt2New, rt2Old),
        )
        val keep = assertIs<RecoveryPlan.KeepOld>(plan)
        assertEquals(listOf(s.part, s.bak, r.part, r.bak), keep.discard)
    }

    @Test
    fun `断电点3_BACKED_UP未替换_两part向前补完`() {
        val s = sav()
        val r = rt2()
        val plan = SaveRecovery.plan(
            journal(CommitState.BACKED_UP, s, r),
            backedUpObs(s, r, savNew, savOld, rt2New, rt2Old),
        )
        val fwd = assertIs<RecoveryPlan.RollForward>(plan)
        assertEquals(listOf(Move(s.part, s.target), Move(r.part, r.target)), fwd.moves)
        assertEquals(listOf(s.part, s.bak, r.part, r.bak), fwd.cleanup)
    }

    @Test
    fun `断电点4_sav已替换rt2未替换_只补一件不误判回滚`() {
        val s = sav()
        val r = rt2()
        // sav.part 已不存在、sav 目标本身就是新版本——只查 .part 会在此误判失败。
        val plan = SaveRecovery.plan(
            journal(CommitState.BACKED_UP, s, r),
            backedUpObs(s, r, null, savNew, rt2New, rt2Old),
        )
        val fwd = assertIs<RecoveryPlan.RollForward>(plan)
        assertEquals(listOf(Move(r.part, r.target)), fwd.moves)
    }

    @Test
    fun `断电点5_双替换完成COMMITTED未写_moves为空`() {
        val s = sav()
        val r = rt2()
        val plan = SaveRecovery.plan(
            journal(CommitState.BACKED_UP, s, r),
            backedUpObs(s, r, null, savNew, null, rt2New),
        )
        val fwd = assertIs<RecoveryPlan.RollForward>(plan)
        assertTrue(fwd.moves.isEmpty())
        assertEquals(4, fwd.cleanup.size)
    }

    @Test
    fun `断电点6_COMMITTED已写清理未完成_保留新版本只清理`() {
        val s = sav()
        val r = rt2()
        val plan = SaveRecovery.plan(
            journal(CommitState.COMMITTED, s, r),
            backedUpObs(s, r, null, savNew, null, rt2New),
        )
        val fwd = assertIs<RecoveryPlan.RollForward>(plan)
        assertTrue(fwd.moves.isEmpty())
        assertEquals(listOf(s.part, s.bak, r.part, r.bak), fwd.cleanup)
    }

    @Test
    fun `任一成员无有效新副本_整对从bak回滚而非只回滚坏的`() {
        val s = sav()
        val r = rt2()
        // sav 新副本完好，rt2 的 .part 与目标都不是新版本 ⇒ 半配对不可读，整对回滚。
        val plan = SaveRecovery.plan(
            journal(CommitState.BACKED_UP, s, r),
            backedUpObs(s, r, null, savNew, sha('e'), rt2Old),
        )
        val back = assertIs<RecoveryPlan.RollBack>(plan)
        assertEquals(listOf(Move(s.bak, s.target), Move(r.bak, r.target)), back.restores)
    }

    @Test
    fun `回滚所需bak缺失_停止并报告不静默销毁`() {
        val s = sav()
        val r = rt2()
        val plan = SaveRecovery.plan(
            journal(CommitState.BACKED_UP, s, r),
            listOf(obs(s, null, savOld, savOld), obs(r, null, rt2Old, null)),
        )
        val halt = assertIs<RecoveryPlan.Halt>(plan)
        assertTrue(halt.reason.contains(r.bak))
        assertTrue(halt.reason.contains("缺失"))
    }

    @Test
    fun `回滚所需bak指纹不符_介质损坏停止`() {
        val s = sav()
        val r = rt2()
        val plan = SaveRecovery.plan(
            journal(CommitState.BACKED_UP, s, r),
            listOf(obs(s, null, savOld, savOld), obs(r, null, rt2Old, sha('f'))),
        )
        val halt = assertIs<RecoveryPlan.Halt>(plan)
        assertTrue(halt.reason.contains("指纹不符"))
    }

    @Test
    fun `目标坏而part好_以part为准向前补完`() {
        val s = sav()
        val plan = SaveRecovery.plan(
            journal(CommitState.BACKED_UP, s),
            listOf(obs(s, savNew, sha('9'), savOld)),
        )
        val fwd = assertIs<RecoveryPlan.RollForward>(plan)
        assertEquals(listOf(Move(s.part, s.target)), fwd.moves)
    }

    @Test
    fun `单成员无rt2_同一协议不是特例分支`() {
        val s = sav()
        val fwd = assertIs<RecoveryPlan.RollForward>(
            SaveRecovery.plan(journal(CommitState.BACKED_UP, s), listOf(obs(s, savNew, savOld, savOld))),
        )
        assertEquals(listOf(Move(s.part, s.target)), fwd.moves)
        // 单成员也会因无新副本而回滚，判据与双成员逐字相同。
        val back = assertIs<RecoveryPlan.RollBack>(
            SaveRecovery.plan(journal(CommitState.BACKED_UP, s), listOf(obs(s, null, savOld, savOld))),
        )
        assertEquals(listOf(Move(s.bak, s.target)), back.restores)
    }

    @Test
    fun `SHA大小写归一_同一指纹不判成两个版本`() {
        val s = MemberJournal("t", "t.part", "t.bak", savOld.uppercase(), savNew.uppercase())
        val plan = SaveRecovery.plan(
            journal(CommitState.BACKED_UP, s),
            listOf(MemberObservation("t", savNew, savOld, savOld)),
        )
        assertIs<RecoveryPlan.RollForward>(plan)
        assertEquals(savOld, s.oldSha256)
        assertEquals(savNew, s.newSha256)
    }

    @Test
    fun `观测面不完整或重复_拒绝判定不拿缺失当不存在`() {
        val s = sav()
        val r = rt2()
        val j = journal(CommitState.BACKED_UP, s, r)
        // 少一个成员观测。
        assertFailsWith<IllegalArgumentException> { SaveRecovery.plan(j, listOf(obs(s, savNew, savOld, savOld))) }
        // target 重复。
        assertFailsWith<IllegalArgumentException> {
            SaveRecovery.plan(j, listOf(obs(s, savNew, savOld, savOld), obs(s, rt2New, rt2Old, rt2Old)))
        }
        // 多出无关成员。
        assertFailsWith<IllegalArgumentException> {
            SaveRecovery.plan(journal(CommitState.BACKED_UP, s), listOf(obs(s, savNew, savOld, savOld), obs(r, rt2New, rt2Old, rt2Old)))
        }
    }

    @Test
    fun `journal形态闸_成员数世代路径与SHA`() {
        assertFailsWith<IllegalArgumentException> { journal(CommitState.BACKED_UP) }
        assertFailsWith<IllegalArgumentException> {
            Journal("tx", 1, 2, listOf(sav(), rt2(), MemberJournal("x", "x.part", "x.bak", savOld, savNew)), CommitState.BACKED_UP)
        }
        assertFailsWith<IllegalArgumentException> { Journal("tx", 9, 8, listOf(sav()), CommitState.BACKED_UP) }
        assertFailsWith<IllegalArgumentException> {
            Journal("tx", 1, 2, listOf(sav(), sav()), CommitState.BACKED_UP)
        }
        assertFailsWith<IllegalArgumentException> { MemberJournal("t", "t", "t.bak", savOld, savNew) }
        assertFailsWith<IllegalArgumentException> { MemberJournal("t", "t.part", "t.bak", "abc", savNew) }
        assertFailsWith<IllegalArgumentException> { MemberJournal("t", "t.part", "t.bak", savOld, sha('g') + "0") }
        assertFailsWith<IllegalArgumentException> { SaveRecovery.normalizeSha("z".repeat(64), "测试") }
    }

    @Test
    fun `空间预算_峰值3N不足即拒不静默降级`() {
        assertTrue(SaveRecovery.hasSpaceBudget(listOf(100L, 50L), 450L))
        assertFalse(SaveRecovery.hasSpaceBudget(listOf(100L, 50L), 449L))
        assertTrue(SaveRecovery.hasSpaceBudget(listOf(100L), 300L))
        assertFalse(SaveRecovery.hasSpaceBudget(listOf(100L), 299L))
        // 溢出不得回绕成「空间充足」。
        assertFalse(SaveRecovery.hasSpaceBudget(listOf(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2), Long.MAX_VALUE))
        assertFalse(SaveRecovery.hasSpaceBudget(listOf(Long.MAX_VALUE), Long.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> { SaveRecovery.hasSpaceBudget(emptyList(), 100L) }
        assertFailsWith<IllegalArgumentException> { SaveRecovery.hasSpaceBudget(listOf(-1L), 100L) }
    }

    @Test
    fun `journal成员列表 defensive copy`() {
        val mutable = mutableListOf(sav())
        val j = Journal("tx", 1, 2, mutable, CommitState.BACKED_UP)
        mutable.clear()
        assertEquals(1, j.members.size)
    }
}