package com.eratw.port.core.proc

import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.save.SaveCodec
import com.eratw.port.core.stmt.Statement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag

/**
 * 红 K（GET_MAPID-SPIN）回归：`CHARA_MOVEMENT` **不是死循环**，是合法重负载被 5,000,000 闸误杀。
 *
 * 设备取证（`play-evidence/rc-live/r18/r22-redk-logcat.txt`）显示闸在 5,000,001 步抛出，
 * 调用链 `CHARA_MOVEMENT → MOVEMENT共通処理 → GMF_MAP_METHOD → GMF_METHOD_RESTORE`；
 * 环体/步进在 trace 里正常交替，`FOR` 计数实际在推进（BOARD 2026-10-25 三维度排除结论）。
 *
 * 本用例用真产物 + `fixtures/saves/save03.sav` 变量面（`CHARANUM=168`）在桌面锁定成本：
 * 该函数单次执行 **12,080,416 条 VM 指令**（5,140,444 条语句），其中
 * `GMF_METHOD_UPDATE` 2.71M + `GMF_METHOD_RESTORE` 2.32M 占 98% —— 二者各自按
 * `FOR 1..CHARANUM` 扫三遍（语料 `DLC/GENSOKYO_MAP_FIX/幻想乡地图重修.ERB:50-92`），
 * 属合法 O(N) 扫描，与 `CHARANUM` 成正比。
 *
 * 因此单次调用的闸口径必须是 [GameSession.DEFAULT_MAX_STEPS_PER_RUN]（20,000,000），
 * 与红 J 抬闸后的装置缺省同值；5,000,000 的装置缺省（`PlayChainTest` / `BootPlayChain`）
 * 才是误报来源（已同步为装置缺省）。
 *
 * 2026-10-30 补充（玩家报「开地图重修插件后启动失败」）：生产闸判的不是**单次函数**，
 * 而是**起床链整条** —— `EVENTTRAIN` 的 `WHILE` 环从当前 `TIME` 每 +30 分钟调一次
 * `CHARA_MOVEMENT`（第 47 日 `TIME=1345` 档桌面实测：一次派发连调 **24 次**、
 * 123,496,792 条语句 ≈ **2.90 亿条指令**）。玩家机的报告直接证明**单个段落**（未到让出点、
 * 墙钟闸就是在这段上抛的）就能跑到 133,496,832 步仍未结束 —— 即一整条链必然远超 60 秒。
 * 旧生产闸（60 秒墙钟）在 2.22M 指令/秒 的玩家机上只够 1.33 亿条 ⇒ 必误杀；
 * 现生产闸 = [GameSession.PRODUCTION_MAX_STEPS_PER_RUN]（10 亿步 = 整条链的 3.4 倍），
 * 墙钟生产恒关 [GameSession.PRODUCTION_MAX_RUN_MILLIS]（0）。
 *
 * 语料/产物由环境变量给出（`ERATW_GAMEDIR` / `ERATW_ERBC_DIR`），缺失即跳过。
 * 手工测量可用 `ERATW_REDK_CAP` 覆盖闸值。
 */
// realArtifacts 家标记（realarttag 路 2026-11-06）：产物门控钉（ERATW_GAMEDIR + ERATW_ERBC_DIR + save03 门），供 gateRealArtifacts 圈定；判定逻辑一字不动。
@Tag("realArtifacts")
class RedKCharaMovementReproTest {

    @Test
    fun `CHARA_MOVEMENT 走真存档不撞步数闸`() {
        val gameRoot = gameRoot()
        val erbcDir = erbcDir()
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(gameRoot != null && erbcDir != null && Files.exists(save), "缺 ERATW_GAMEDIR / ERATW_ERBC_DIR / save03，跳过")
        val program = ProgramLoader.loadMapped(erbcDir!!.resolve("program.erbc")) { }
        val metadata = BootMetadata.read(erbcDir.resolve("program.meta"))
        val cap = System.getenv("ERATW_REDK_CAP")?.toLongOrNull() ?: GameSession.DEFAULT_MAX_STEPS_PER_RUN
        val session = GameSession(
            gameRoot = gameRoot!!,
            program = program,
            metadata = metadata,
            maxStepsPerRun = cap,
            randSeed = 20260226L,
        )
        val bytes = Files.readAllBytes(save)
        val data = SaveCodec.read(bytes, session.store.registry, SaveFileKind.NORMAL)
        data.applyTo(session.store, restoreCharacters = true, setLastLoad = true)

        // 失败时打印末 160 条语句，便于判定「环不前进」还是「只是走得远」。
        val ring = ArrayDeque<String>()
        session.runtime.statementProbe = object : StatementProbe {
            override fun onStatement(
                op: Int,
                index: Int,
                env: StmtEnv,
                outcome: StmtOutcome?,
                error: Throwable?,
                elapsedNs: Long,
            ) {
                // 波次 C 起探针只给 op/index；尾迹要语句原文，故按 index 现取一次池。
                val stmt = if (index >= 0) program.statement(index) else null
                ring.addLast("p${env.pc} ${env.functionName} L${stmt?.line ?: env.line} ${stmt?.raw ?: ""}")
                if (ring.size > 160) ring.removeFirst()
            }
        }

        val charaNum = session.store.readInt("CHARANUM", LongArray(0))
        try {
            session.runtime.callFunction("CHARA_MOVEMENT")
        } catch (e: StepLimitExceeded) {
            fail(
                "红 K 复现：CHARANUM=$charaNum 仍撞步数闸（cap=$cap）\n" +
                    "调用链=" + e.callChain + "\n" +
                    "帧快照=" + e.frameSnapshot.joinToString("\n") + "\n" +
                    "语句尾迹=\n" + ring.joinToString("\n"),
            )
        }
        val instr = executedSteps(session.runtime)
        val msg = "红K：CHARANUM=$charaNum CHARA_MOVEMENT 完成，指令数=$instr cap=$cap"
        println(msg)
        Files.writeString(Path.of("build", "redk-steps.txt"), msg)
        assertTrue(instr in 1 until cap, "实测指令数必须落在步数闸内：$instr !in 1..<$cap")
        // 生产判据（2026-10-30）：玩家报「开地图重修后启动失败」时，撞闸的不是单次函数，
        // 而是整条起床链 —— `EVENTTRAIN` 一次派发连调 24 次本函数（桌面实测，第 47 日
        // TIME=1345 档，共 123,496,792 条语句 ≈ 2.90 亿条指令）。生产步数闸必须覆盖整条链。
        val worstDayStart = instr * 24L
        assertTrue(
            GameSession.PRODUCTION_MAX_STEPS_PER_RUN >= worstDayStart,
            "生产步数闸必须覆盖起床链整段：实测 $worstDayStart 指令 vs 闸 " +
                "${GameSession.PRODUCTION_MAX_STEPS_PER_RUN}",
        )
    }

    /** 反射读 `ProcessRuntime.vm.executedSteps`（本次 run 实际执行的指令数；`callFunction` 只回函数返回值）。 */
    private fun executedSteps(rt: ProcessRuntime): Long = runCatching {
        val f = ProcessRuntime::class.java.getDeclaredField("vm")
        f.isAccessible = true
        val vm = f.get(rt) ?: return@runCatching 0L
        val getter = vm.javaClass.getMethod("getExecutedSteps")
        (getter.invoke(vm) as Number).toLong()
    }.getOrDefault(0L)

    private fun gameRoot(): Path? = System.getenv("ERATW_GAMEDIR")
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it) }

    private fun erbcDir(): Path? = System.getenv("ERATW_ERBC_DIR")
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it) }
}
