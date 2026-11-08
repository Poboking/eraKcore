package com.eratw.port.core.save

import java.util.Locale

/**
 * 存档事务的**恢复判定核心**（ERA Runtime 2.0 §8；ABI-6 的决策面）。
 *
 * ## 为什么这一层必须由架构师落、且必须是纯函数
 *
 * `saveNN.sav` + `saveNN.sav.rt2` 是**一个逻辑存档**：两个文件要么一起是新版本、要么一起是
 * 旧版本，绝不允许「sav 新 / rt2 旧」这种半配对被当成正常存档读进去（那会让扩展实体状态
 * 静默滞后或丢失，玩家看到的是「孩子没了」而不是报错）。崩溃可能落在任意两个写操作之间，
 * 所以「该向前补完还是整对回滚」是一条**判定规则**，不是 I/O 技巧。
 *
 * 规则落在这里（纯函数、无文件访问、可逐断电点单测）；真正的 journal 落盘 / fsync / 换名 /
 * .bak 轮转 / 三语 RestoreReport 属 M1-5 脏活，按本文件的 [RecoveryPlan] 执行即可，
 * **不得自行另判一套**——两套判定就是「架构变质」的开始。
 *
 * ## 顺序不变量（BACKED_UP 的存在理由）
 *
 * 写序固定为：两个 `.part` → SHA 校验 → fsync → **PREPARED** journal → 写 `.bak` 对 +
 * 按旧 SHA 校验 + fsync → **BACKED_UP** journal → 定序替换目标 → 校验新配对 →
 * **COMMITTED** → 清理。
 *
 * 于是「任何目标被替换之前，完整旧 `.bak` 对已落盘并校验」成为**可在恢复期判定的事实**：
 * journal 到了 BACKED_UP 就证明旧对可回滚；只到 PREPARED 就证明目标一个都没动。
 * 这条前提若只写在散文里，实现者迟早会把它优化掉（「先换 sav 再备份 rt2 省一次拷贝」），
 * 故在此钉成状态机语义。
 *
 * ## 每成员三候选（修 .part-only 漏洞）
 *
 * 崩溃可能发生在「sav 已替换、rt2 未替换」之间：此时 `sav.part` 已不存在、`sav` 本身才是
 * 新版本。**只查 .part 会把「本可向前补完」误判成失败并回滚掉已经写好的新档**。
 * 正确判定 = 每个成员的新版本可能位于 `.part` **或**（已替换的）目标文件，分别与 journal
 * 的 newSha256 比对：
 *
 * - 两个成员都找到有效新副本 ⇒ **向前补完**（把剩余 .part 换名到位；目标坏而 .part 好则
 *   以 .part 为准）；
 * - 任一成员没有有效新副本 ⇒ **整对从 .bak 回滚**（不是只回滚坏的那个——半配对不可读）；
 * - 回滚期任一 `.bak` 缺失或 SHA 不符 ⇒ **介质损坏，停止并报告**（[RecoveryPlan.Halt]），
 *   绝不静默销毁、绝不拿坏副本凑数。
 *
 * ## 六断电点矩阵（§8 原表，逐行对应单测）
 *
 * | 断电时机 | commitState | 本函数产出 |
 * |---|---|---|
 * | 两个 .part 未完成 / PREPARED 未写 | 无 journal | 不调用本函数（旧档原样，残 .part 由清理面忽略） |
 * | PREPARED 已写、BACKED_UP 未写 | PREPARED | [RecoveryPlan.KeepOld]（目标未被改，丢残件） |
 * | BACKED_UP 已写、未替换 | BACKED_UP | [RecoveryPlan.RollForward]（两 .part 均有效） |
 * | sav 已替换、rt2 未替换 | BACKED_UP | [RecoveryPlan.RollForward]（只换 rt2 一件） |
 * | 双替换完成、COMMITTED 未写 | BACKED_UP | [RecoveryPlan.RollForward]（moves 空，补写 COMMITTED） |
 * | COMMITTED 已写、清理未完成 | COMMITTED | [RecoveryPlan.RollForward]（moves 空，只清理） |
 *
 * 成员数 = 1（无扩展数据时无 .rt2）或 2，**同一协议**——单成员不是特例分支。
 *
 * 本文件不碰文件系统、不持可变全局（AGENTS §2.4）；输入全部由调用方观测后喂入。
 */
object SaveRecovery {

    /** journal 的提交状态；语义 = 「已经完成了哪一步」，不是「打算做哪一步」。 */
    enum class CommitState {
        /** 两个 .part 已落盘并校验；**目标一个都没动，.bak 对未必完整**。 */
        PREPARED,

        /** 旧 .bak 对已落盘并按旧 SHA 校验；目标可能已被部分替换。 */
        BACKED_UP,

        /** 两个目标都已替换为新版本并校验；只剩清理。 */
        COMMITTED,
    }

    /**
     * 一个事务成员（sav 或 rt2）的路径与新旧指纹。
     *
     * 三个路径必须互不相同（否则「换名到位」会自己覆盖自己）；SHA 一律 64 位十六进制，
     * 构造期归一为小写（[normalizeSha]），大小写不同的同一指纹不得判成两个版本。
     */
    class MemberJournal(
        val target: String,
        val part: String,
        val bak: String,
        oldSha256: String,
        newSha256: String,
    ) {
        val oldSha256: String = normalizeSha(oldSha256, "$target 旧指纹")
        val newSha256: String = normalizeSha(newSha256, "$target 新指纹")

        init {
            require(target.isNotBlank()) { "成员 target 路径为空" }
            require(part.isNotBlank()) { "成员 part 路径为空" }
            require(bak.isNotBlank()) { "成员 bak 路径为空" }
            require(target != part && target != bak && part != bak) {
                "成员三路径必须互不相同: target=$target part=$part bak=$bak"
            }
        }
    }

    /**
     * 事务 journal。成员数恒 1 或 2（§8：无扩展数据时只有 sav，同一协议）。
     *
     * [oldPairGeneration]/[newPairGeneration] 是配对世代（单调递增），供诊断与
     * 「这份 journal 属于哪一次保存」对账；恢复判定本身只依赖 commitState 与逐成员 SHA。
     */
    class Journal(
        val transactionId: String,
        val oldPairGeneration: Long,
        val newPairGeneration: Long,
        members: List<MemberJournal>,
        val commitState: CommitState,
    ) {
        val members: List<MemberJournal> = members.toList()

        init {
            require(transactionId.isNotBlank()) { "transactionId 为空" }
            require(members.size in 1..2) { "存档事务成员数必须是 1 或 2，实为 ${members.size}" }
            require(newPairGeneration > oldPairGeneration) {
                "配对世代必须单调递增: old=$oldPairGeneration new=$newPairGeneration"
            }
            val targets = members.map { it.target }
            require(targets.distinct().size == targets.size) { "事务内 target 路径重复: $targets" }
        }
    }

    /**
     * 调用方对某成员三个候选文件的**观测结果**：存在则给实测 SHA-256，不存在/读不出给 null。
     *
     * 「读不出」与「不存在」在判定上等价（都拿不到有效副本），故统一用 null 表达；
     * 具体错误文案由 I/O 层负责，本层只关心「有没有一份指纹对得上的字节」。
     */
    class MemberObservation(
        val target: String,
        val partSha256: String?,
        val targetSha256: String?,
        val bakSha256: String?,
    ) {
        val partSha: String? = partSha256?.let { normalizeSha(it, "$target .part 指纹") }
        val targetSha: String? = targetSha256?.let { normalizeSha(it, "$target 目标指纹") }
        val bakSha: String? = bakSha256?.let { normalizeSha(it, "$target .bak 指纹") }
    }

    /** 一次换名/回滚动作：把 [from] 的字节放到 [to]（执行方式由 I/O 层决定）。 */
    data class Move(val from: String, val to: String)

    /** 恢复判定产出。执行者只做动作，不再判断。 */
    sealed interface RecoveryPlan {
        /**
         * PREPARED 但未 BACKED_UP：顺序不变量保证目标未被改 ⇒ 保留旧档、丢弃残件。
         * [discard] = 全部 .part 与 .bak 路径（存在与否由执行者自行忽略）。
         */
        data class KeepOld(val discard: List<String>) : RecoveryPlan

        /**
         * 全成员都有有效新副本 ⇒ 向前补完。
         *
         * [moves] 只列**还需要换名**的成员（目标已是新 SHA 的不产生动作，故双替换完成时
         * moves 为空）；执行完毕后补写 COMMITTED（journal 已是 COMMITTED 则幂等跳过），
         * 再清理 [cleanup]。
         */
        data class RollForward(val moves: List<Move>, val cleanup: List<String>) : RecoveryPlan

        /**
         * 任一成员无有效新副本 ⇒ **整对**从 .bak 回滚（半配对不可读，不能只回滚坏的一件）。
         * 全部 .bak 已按旧 SHA 校验通过才产出本计划；执行后丢弃 journal 与残件、保留旧档。
         */
        data class RollBack(val restores: List<Move>, val cleanup: List<String>) : RecoveryPlan

        /**
         * 介质损坏/ journal 与磁盘事实矛盾到无法安全恢复：**停止并报告**，不静默销毁。
         * 典型 = 需要回滚但某个 .bak 缺失或指纹不符（BACKED_UP 不变量被外力破坏）。
         */
        data class Halt(val reason: String) : RecoveryPlan
    }

    /**
     * 判定一次崩溃后的恢复动作（纯函数）。
     *
     * @param journal 磁盘上读到的事务 journal（读不到 journal = 不属于本函数管辖，旧档原样）
     * @param observations 逐成员观测；必须与 [Journal.members] 一一对应（按 target 匹配，
     *   顺序无关），缺一/重复/多出一律 [IllegalArgumentException]——观测面不完整时宁可拒绝
     *   判定，也不拿缺失当「文件不存在」去回滚好档。
     */
    fun plan(journal: Journal, observations: List<MemberObservation>): RecoveryPlan {
        val byTarget = HashMap<String, MemberObservation>(observations.size * 2)
        for (o in observations) {
            require(byTarget.put(o.target, o) == null) { "观测面 target 重复: ${o.target}" }
        }
        require(byTarget.size == journal.members.size) {
            "观测面成员数 ${byTarget.size} 与 journal ${journal.members.size} 不符"
        }
        for (m in journal.members) {
            require(m.target in byTarget) { "journal 成员 ${m.target} 没有对应观测" }
        }

        val residue = journal.members.flatMap { listOf(it.part, it.bak) }

        // PREPARED：顺序不变量 ⇒ 目标一个都没动。即便某个 .part 指纹正好，也不向前补完
        // （旧 .bak 对尚未校验完成，回滚路不存在，此时唯一安全动作是保留旧档）。
        if (journal.commitState == CommitState.PREPARED) {
            return RecoveryPlan.KeepOld(residue)
        }

        // BACKED_UP / COMMITTED：每成员三候选（.part 或已替换的目标）。
        val moves = ArrayList<Move>(journal.members.size)
        var allNew = true
        for (m in journal.members) {
            val o = byTarget.getValue(m.target)
            val targetIsNew = o.targetSha == m.newSha256
            val partIsNew = o.partSha == m.newSha256
            if (!targetIsNew && !partIsNew) {
                allNew = false
                break
            }
            // 目标已是新版本 ⇒ 无需动作；否则以 .part 为准（目标坏而 .part 好也走这支）。
            if (!targetIsNew) moves += Move(m.part, m.target)
        }
        if (allNew) return RecoveryPlan.RollForward(moves, residue)

        // 整对回滚：先证全部 .bak 有效，再产出动作（任一不符 = 介质损坏，停止）。
        for (m in journal.members) {
            val o = byTarget.getValue(m.target)
            if (o.bakSha == null) {
                return RecoveryPlan.Halt("回滚所需 .bak 缺失: ${m.bak}（BACKED_UP 不变量已被破坏，停止并报告）")
            }
            if (o.bakSha != m.oldSha256) {
                return RecoveryPlan.Halt(
                    "回滚所需 .bak 指纹不符: ${m.bak} 期望 ${m.oldSha256} 实测 ${o.bakSha}（介质损坏，停止并报告）",
                )
            }
        }
        return RecoveryPlan.RollBack(journal.members.map { Move(it.bak, it.target) }, residue)
    }

    /**
     * 空间预算（§8 v6.4-c）：事务峰值 = 旧对 + .bak 对 + .part 对 ≈ 3N。
     *
     * 启动保存前检查，不足 = 保存失败并明确报错，**不静默降级为非事务写**（配对完整性优先）。
     * 返回 false 时调用方必须放弃本次保存；既有 SaveFileIo「快路失败退原地截断」的退路
     * 只在单成员存档时沿用，不得用于配对写。
     *
     * @param memberBytes 各成员新版本字节数（1 或 2 个）
     * @param availableBytes 目标卷可用字节
     */
    fun hasSpaceBudget(memberBytes: List<Long>, availableBytes: Long): Boolean {
        require(memberBytes.size in 1..2) { "存档事务成员数必须是 1 或 2，实为 ${memberBytes.size}" }
        require(memberBytes.all { it >= 0 }) { "成员字节数为负: $memberBytes" }
        require(availableBytes >= 0) { "可用字节为负: $availableBytes" }
        var peak = 0L
        for (n in memberBytes) {
            if (n > Long.MAX_VALUE / 3) return false
            val triple = n * 3
            if (peak > Long.MAX_VALUE - triple) return false
            peak += triple
        }
        return availableBytes >= peak
    }

    /** SHA-256 文本归一：64 位十六进制、小写。形态不符当场拒（不静默截断/补零）。 */
    fun normalizeSha(raw: String, what: String): String {
        require(raw.length == 64) { "$what 长度不是 64: ${raw.length}" }
        for (ch in raw) {
            require(ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F') { "$what 含非十六进制字符: $ch" }
        }
        return raw.lowercase(Locale.ROOT)
    }
}