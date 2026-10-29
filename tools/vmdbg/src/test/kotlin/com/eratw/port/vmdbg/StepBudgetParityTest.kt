package com.eratw.port.vmdbg

import com.eratw.port.core.proc.GameSession
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 步数闸口径一致性：vmdbg `call`/`trace` 的缺省步数闸必须与产品
 * GameSession.DEFAULT_MAX_STEPS_PER_RUN 同值（红 J NEWGAME-STEP-SPIN：重载模组
 * INIT_STARTINGLV→SHOW_ABLUP_SELECT 合法步耗可达 5-10M，低闸会误杀）。
 * sweep 的逐函数缺省（2,000,000）是有意的分片口径，不在此断言（R14 已登记为待核）。
 */
class StepBudgetParityTest {

    @Test
    fun `call缺省步数闸与产品 GameSession 同口径`() {
        assertEquals(
            GameSession.DEFAULT_MAX_STEPS_PER_RUN,
            Cli.DEFAULT_CALL_STEPS,
            "vmdbg call/trace 缺省步数闸必须与产品同口径（20,000,000）",
        )
    }
}
