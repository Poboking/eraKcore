package com.eratw.port.uipreview

import com.eratw.port.core.proc.GameSession
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 步数闸口径一致性：uipreview 的缺省 `--steps` 必须与产品
 * GameSession.DEFAULT_MAX_STEPS_PER_RUN 同值。否则重载模组 INIT_STARTINGLV 这类
 * 合法步耗 5-10M 的流程会被预览装置的低闸误杀成 STEP_LIMIT 终局失败（退出码 3），
 * 把产品本可跑通的流程当成缺陷 —— R14 深审登记的口径不一致回归钉子。
 */
class StepBudgetParityTest {

    @Test
    fun `缺省步数闸与产品 GameSession 同口径`() {
        assertEquals(
            GameSession.DEFAULT_MAX_STEPS_PER_RUN,
            Cli.DEFAULT_STEPS,
            "uipreview 缺省步数闸必须与产品同口径（20,000,000）",
        )
    }
}
