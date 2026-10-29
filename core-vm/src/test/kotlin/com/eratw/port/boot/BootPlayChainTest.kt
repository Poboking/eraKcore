package com.eratw.port.boot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M10-PLAY 判据闸（**默认不执行**，避免常规 `:core-vm:test` 变红/变慢）。
 *
 * 跑法（HOMEJIA，模块目录 cwd）：
 *
 * ```text
 * ERATW_CORPUS=<共享根>/eratw-画蛇添足版eratw4.980/ERB ERATW_BOOT_PLAY=1 \
 *   ./gradlew --configure-on-demand :core-vm:test --tests "*BootPlayChain*"
 * ```
 *
 * `ERATW_BOOT_PLAY=1` 时执行 [BootPlayChain.run] 并断言三件事：
 * 1. 全程**无 CodeEE**、无其它异常（等待-提交-推进链不卡死、不吞输入、不报错）；
 * 2. 「等待 → 提交 → 推进」往返 ≥ `ERATW_BOOT_ROUNDS`（缺省 30）；
 * 3. 报告已落盘（`build/boot-play-report.md`），首行区能读出停在哪个状态。
 *
 * 输入通道与 `bootSmoke` 同一条脚本口径（`ERATW_BOOT_INPUTS`），差别在**投递形态**：
 * 这里经生产 `QueueInputSink` 阻塞队列（真机 `GameHost` 用的就是它），
 * `ScriptedInputSink` 只是立即应答的测试替身。
 */
class BootPlayChainTest {

    @Test
    fun `真机形态开局流连续推进三十回合无CodeEE`() {
        if (System.getenv("ERATW_BOOT_PLAY") != "1") return
        val outcome = BootPlayChain.run()

        assertNull(outcome.error, "推进链异常退出：${outcome.stoppedBy}")
        assertNull(
            outcome.codeEe,
            "CodeEE：${outcome.codeEe?.message} 函数=${outcome.codeEe?.funcName} " +
                "行=${outcome.codeEe?.line} pc=${outcome.codeEe?.pc}（报告 build/boot-play-report.md）",
        )
        assertTrue(
            outcome.roundTrips >= BootPlayChain.targetRounds,
            "往返不足：${outcome.roundTrips} < ${BootPlayChain.targetRounds}（收口原因 ${outcome.stoppedBy}；" +
                "状态序列 ${outcome.statesVisited.takeLast(8)}；尾部行 ${outcome.lastLines.takeLast(3)})",
        )
        assertEquals(true, outcome.passed, "outcome.passed 与分项断言必须一致")
    }
}
