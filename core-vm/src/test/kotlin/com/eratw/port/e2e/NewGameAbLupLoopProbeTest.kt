package com.eratw.port.e2e

import com.eratw.port.core.proc.ProcessRuntime
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 红 J（NEWGAME-STEP-SPIN）最小复刻：`@SHOW_ABLUP_SELECT` 的 `$AGAIN` / `GOTO AGAIN` 回边不收敛。
 *
 * 真机冷启新游戏时，`ABL.ERB:79-131` 的外层 `FOR LOCAL,0,4` 内层是一个 `$AGAIN` 回边：
 * 每轮用 `JUEL_DEMAND` 求一次需求、扣 JUEL、`ABL += 1`，再 `GOTO AGAIN` 继续给同一项升级；
 * 只有需求不足（CONTINUE，跳到 `NEXT` 的步进）或 `-2`（BREAK）才离开回边。真机在
 * 14s 撞 5,000,000 步闸，采样 pc 落在 `JUEL_DEMAND` 派发器与 `JUEL_DEMAND_17` 内。
 *
 * 本装置把这条链缩成 3 个方法 + 一段同构循环，用来判定「方法调用作赋值右值 / 嵌套 RETURNF /
 * SIF 条件里的方法调用」这一串返回值传递是否与原版一致（原版语义出处见各断言消息）：
 *  - `@DEMAND(ABL_ID,ARG:1,ARG:2,ARG:3)`  —— ABL_UP_DATA.ERB:2-53（IF/ELSEIF 派发，:50-52 返回）
 *  - `@DEMAND_0(ARG,ARG:1,ARG:2)`          —— ABL_UP_DATA.ERB:114-158（`SIF ARG>0` 早退、`VARSET LOCAL`、`RETURNF LOCAL:n`）
 *  - `@CALC(ID,NEED)`                     —— ABL_UP_DATA.ERB:2768-2777（`CALC_ABL_DEMAND` 的 `MAX(1, 需求*比率/比率)`）
 *
 * 复现原理：返回值链任何一环丢失（赋值右值拿到的是临时槽下标/旧值、`RETURNF` 未把被调值带回、
 * 派发器在不该返回 0 时返回 0），`JUEL_DEMAND` 就恒为 0；此时循环既不扣 JUEL 也不触发
 * 「需求不足」，`ABL += 1` 永远成功，`GOTO AGAIN` 便成了死循环 —— 与真机撞 5M 步闸同形。
 *
 * 局限：
 *  - 这是**语义同构**的最小语料，不是真机语料；`ABL` 换成 `GLOBAL`/私有变量、JUEL 换成 `GLOBAL:30..69`、
 *    省略了 ABL.ERB:99-106 的经验值分支。若真机红来自本装置覆盖不到的因素（例如设备侧反射/
 *    产物差异），本装置可以是绿的。
 *  - `ABL.ERB:88-97` 的 `FOR LOCAL:1,0,100` 在这里缩成 0..40（JUEL 池大小），结构不变。
 */
class NewGameAbLupLoopProbeTest {

    companion object {
        /**
         * 真机步数闸是 5,000,000；正确路径本装置不到 2,000 步，这里留 10,000 步的余量。
         * 死循环在本装置里会在 4 轮左右进入 `GOTO AGAIN` 无限回边，因此 10,000 步足够判红。
         */
        private const val STEP_CAP = 10_000L

        private val fixtureDir: Path by lazy { writeFixture() }

        private val model: GoldenModel by lazy { GoldenPipeline.build(fixtureDir) }

        private val runtime: ProcessRuntime by lazy {
            GoldenPipeline.newRuntime(model, GoldenPipeline.newConsole(), STEP_CAP)
        }

        /** 只跑一次：MAIN 结束时返回 null，撞步闸/CodeEE 时返回异常。 */
        private val outcome: Throwable? by lazy {
            runCatching { runtime.execute() }.exceptionOrNull()
        }

        private fun readGlobal(index: Int): Long =
            runtime.store.readInt("GLOBAL", longArrayOf(index.toLong()))

        private fun failureText(e: Throwable?): String =
            if (e == null) "" else GoldenPipeline.describeFailure(model.program, model, e)

        private fun writeFixture(): Path {
            val dir = Files.createTempDirectory("eratw-nl-ablup-")
            Files.writeString(dir.resolve("NL_PROBE_MAIN.ERB"), MAIN_ERB, Charsets.UTF_8)
            Files.writeString(dir.resolve("NL_PROBE_ABL_UP_DATA.ERB"), ABL_UP_DATA_ERB, Charsets.UTF_8)
            return dir
        }

        /**
         * `@MAIN` 复刻 ABL.ERB:79-131 的结构：外层 `FOR LOCAL,0,4`、`$AGAIN`（:80）、`-2 → BREAK`
         * （:83-84）、`<0 → CONTINUE`（:85-86）、找珠不足 → `CONTINUE`（:96-97）、
         * `JUEL -= 需求`（:109-111）、`ABL += 1`（:125）、`GOTO AGAIN`（:130）。
         *
         * 观测面（`GLOBAL`，100 槽；用系统变量是为了不依赖夹具里的全局 #DIM 注册）：
         *  - 0 `DEMAND(0,0,0,0)`  正向派发结果（经 CALC）
         *  - 1 `DEMAND_0(0,0,0)`  派发目标原值
         *  - 2 `CALC(0, GLOBAL:1)` 期望的派发结果
         *  - 3 `DEMAND(0,1,0,0)`  负分支派发结果
         *  - 4 `DEMAND_0(1,0,0)`  负分支目标原值
         *  - 5 最终 LVUPS（模拟 ABL 已提升的级数，每轮 +1）
         *  - 6 回边轮数（每轮 +1）
         *  - 7 `FOR LOCAL` 终值
         *  - 8 JUEL 池余量（`GLOBAL:30`）
         *  - 9 失配标志：任何一轮 LVUPS != ROUNDS 即置 1
         */
        private val MAIN_ERB = """
            ; 红 J 最小复刻（NEWGAME-STEP-SPIN）：ABL.ERB:79-131 的 ${'$'}AGAIN / GOTO AGAIN 回边
            @GOLDEN_ROOT
            CALL MAIN

            @MAIN
            #DIM LVUPS
            #DIM ROUNDS
            #DIM NEED_JEWEL
            #DIM DEMAND_V

            GLOBAL:0 = DEMAND(0,0,0,0)
            GLOBAL:1 = DEMAND_0(0,0,0)
            GLOBAL:2 = CALC(0,GLOBAL:1)
            GLOBAL:3 = DEMAND(0,1,0,0)
            GLOBAL:4 = DEMAND_0(1,0,0)
            GLOBAL:9 = 0
            GLOBAL:30 = 100
            LVUPS = 0
            ROUNDS = 0
            FOR LOCAL,0,4
                ${'$'}AGAIN
                NEED_JEWEL = 0
                DEMAND_V = DEMAND(0,LOCAL,0,0)
                SIF DEMAND_V == -2
                    BREAK
                SIF DEMAND_V < 0
                    CONTINUE
                FOR LOCAL:1,0,40
                    IF DEMAND(0,LOCAL,LOCAL:1,0) > 0
                        IF GLOBAL:(30 + LOCAL:1) < DEMAND(0,LOCAL,LOCAL:1,0)
                            NEED_JEWEL ++
                            BREAK
                        ENDIF
                    ENDIF
                NEXT
                SIF NEED_JEWEL
                    CONTINUE
                FOR LOCAL:1,0,40
                    GLOBAL:(30 + LOCAL:1) -= DEMAND(0,LOCAL,LOCAL:1,0)
                NEXT
                LVUPS += 1
                ROUNDS += 1
                SIF LVUPS != ROUNDS
                    GLOBAL:9 = 1
                GOTO AGAIN
            NEXT
            GLOBAL:5 = LVUPS
            GLOBAL:6 = ROUNDS
            GLOBAL:7 = LOCAL:0
            GLOBAL:8 = GLOBAL:30
        """.trimIndent()

        /** 派发器与派发目标。参数名与形参口径照抄原版：`@JUEL_DEMAND(ABL_ID,ARG:1,ARG:2,ARG:3)`。 */
        private val ABL_UP_DATA_ERB = """
            @DEMAND(ABL_ID,ARG:1,ARG:2,ARG:3)
            #FUNCTION
            #DIM ABL_ID
            #DIM DEMAND_RESULT

            IF ABL_ID == 0
                DEMAND_RESULT = DEMAND_0(ARG:1,ARG:2,ARG:3)
            ELSEIF ABL_ID == 1
                DEMAND_RESULT = DEMAND_0(ARG:1,ARG:2,ARG:3)
            ENDIF
            SIF DEMAND_RESULT < 0
                RETURNF DEMAND_RESULT
            RETURNF CALC(ABL_ID,DEMAND_RESULT)

            @DEMAND_0(ARG,ARG:1,ARG:2)
            #FUNCTION

            SIF ARG > 0
                RETURNF -1
            VARSET LOCAL
            SIF ARG:1 == 0
                LOCAL:0 = 5
            RETURNF LOCAL:0

            @CALC(ID,NEED)
            #FUNCTION
            #DIM ID
            #DIM NEED

            SIF NEED == 0
                RETURNF 0
            RETURNF MAX(1,NEED * 10 / 1)
        """.trimIndent()
    }

    /** 夹具本身必须编译干净：任何切片落空体桩都会让下面的断言失去意义。 */
    @Test
    fun `夹具编译零失败`() {
        assertTrue(
            model.compileFailures.isEmpty(),
            "夹具切片编译失败（断言不可信）：${model.compileFailures.joinToString(" | ")}",
        )
        assertTrue(
            model.program.functions.any { it.name.uppercase() == "MAIN" },
            "MAIN 未进入程序函数表：${model.program.functions.map { it.name }}",
        )
        // 触发装置构建：私有 #DIM 注册失败会在运行期退化成 CodeEE，先在这里钉死。
        runtime
        assertTrue(
            GoldenPipeline.lastRegisterFailures.isEmpty(),
            "私有变量注册失败：${GoldenPipeline.lastRegisterFailures.joinToString(" | ")}",
        )
    }

    /**
     * 返回值链（ABL_UP_DATA.ERB:50-52 负分支直穿、:8 赋值右值、:52 嵌套 RETURNF）。
     *
     * 原版 `JUEL_DEMAND` 对被调值的处理只有两条路：`<0` 原样返回（派发器把被调值**原封**带回），
     * 否则过 `CALC_ABL_DEMAND`。两条路都必须建立在 `DEMAND_RESULT = DEMAND_0(...)` 真正拿到
     * 被调函数返回值之上 —— 拿到临时槽下标/旧值时，等式立刻破。
     */
    @Test
    fun `派发器返回值等于派发目标返回值`() {
        val err = outcome
        assertTrue(err == null, "MAIN 未能在 $STEP_CAP 步内终止：${failureText(err)}")

        assertEquals(5L, readGlobal(1), "DEMAND_0(0,0,0) 应返回 LOCAL:0=5（ABL_UP_DATA.ERB:158 形态）")
        assertEquals(50L, readGlobal(2), "CALC(0,5) 应为 MAX(1,5*10/1)=50（ABL_UP_DATA.ERB:2775-2777）")
        assertEquals(
            readGlobal(2), readGlobal(0),
            "DEMAND(0,0,0,0) != CALC(0, DEMAND_0(0,0,0))：赋值右值链丢了被调值（ABL_UP_DATA.ERB:8/52）",
        )
        assertEquals(-1L, readGlobal(4), "DEMAND_0(1,0,0) 应因 SIF ARG>0 早退返回 -1（ABL_UP_DATA.ERB:116-117）")
        assertEquals(
            readGlobal(4), readGlobal(3),
            "DEMAND(0,1,0,0) != DEMAND_0(1,0,0)：负分支未把被调值原样带回（ABL_UP_DATA.ERB:50-51）",
        )
        assertTrue(readGlobal(0) > 0, "派发器返回 ${readGlobal(0)}，应 > 0（需求为 0 会让回边永不退出）")
    }

    /**
     * `$AGAIN` 回边（ABL.ERB:79-131）：万步内收敛、每轮恰好升一级、JUEL 真被扣、FOR 计数器不被内层重置。
     *
     * 语料常量：JUEL 池 100、每级需求 50 ⇒ 第 1、2 轮回边各扣 50，第 3 轮「需求不足」→ CONTINUE →
     * LOCAL=1..3 皆因 `JUEL_DEMAND<0` 继续 CONTINUE → `NEXT` 把 LOCAL 推到 4 退出。
     * 因此退化解是 LVUPS=2、回边轮数=2、LOCAL=4、JUEL 余量=0。
     */
    @Test
    fun `回边万步内收敛且每轮只升一级`() {
        val err = outcome
        assertTrue(
            err == null,
            "MAIN 未能在 $STEP_CAP 步内终止（\$AGAIN / GOTO AGAIN 不收敛）：${failureText(err)}",
        )

        assertEquals(0L, readGlobal(9), "存在某轮 LVUPS != ROUNDS：ABL += 1 的写丢失或被内层调用重置")
        assertEquals(2L, readGlobal(6), "回边轮数应为 2（JUEL 100 / 每级 50），实际 ${readGlobal(6)}")
        assertEquals(2L, readGlobal(5), "每轮回边应恰好升一级，最终 LVUPS 应为 2，实际 ${readGlobal(5)}")
        assertEquals(
            4L, readGlobal(7),
            "FOR LOCAL,0,4 的计数器未走到 4：被内层 DEMAND_0 的 VARSET LOCAL 重置（ABL_UP_DATA.ERB:118）",
        )
        assertEquals(0L, readGlobal(8), "JUEL -= DEMAND(...) 未递减（ABL.ERB:109-111），池余 ${readGlobal(8)}")
    }
}
