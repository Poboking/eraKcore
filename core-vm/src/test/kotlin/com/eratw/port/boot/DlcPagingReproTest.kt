package com.eratw.port.boot

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 玩家反馈复现（2026-11-04）：补丁（DLC）设置菜单「返回即换页 → 三次后空白 → 往前翻不回去」。
 *
 * 根因在语料而不是前后翻页 UI：`ERB/DLC/OPTIONPRINT.ERB` 的 `@DLCOPTIONPRINT(ARG)` 把
 * **跨调用持久**的 `LOCAL` 当 DLC 枚举下标，入口没有归零。Emuera 语义：`LOCAL`/`LOCALS`/
 * `ARG`/`ARGS` 按函数一份、跨 CALL 持久，只有 `ResetData`/读档才 `SetDefaultLocalValue`
 * （reference `VariableData.cs:3365-3377`；调用点仅 `VariableEvaluator.cs:1336-1339`、
 * `:2428-2441`、`:2609-2626`）；`ClearLocalValue` 只在 `Dispose()`（`VariableData.cs:4213-4215`）；
 * `FunctionLabelLine.Service.cs:119-136` 的 `ScopeIn/ScopeOut` 只处理私有 `#DIM DYNAMIC`。
 * 本 VM 同口径（`VariableStore.enterFunction(..., resetLocals = false)`）。
 *
 * 判据：**同一会话**里连续两次渲染第 1 页（`DLCOPTIONPRINT(0)`），控制台末 30 行必须完全相同。
 * - 修前：第一次枚举 0~29，第二次从 LOCAL=30 继续 ⇒ 30~59（红）；
 * - 修后：入口 `LOCAL = 0` ⇒ 两次都是 0~29（绿）。
 *
 * 装载链走**生产同款** erbc（`ProgramLoader.loadMapped` + `BootMetadata.read` + [GameSession]），
 * 与出包产物逐字节同源；不从源码重编，避免装置侧 `#DIM` 折叠面与 erbcc 分叉。
 * erbc 目录解析顺序（见 [erbcDir]）：`ERATW_ERBC_DIR` 显式值 → 共享根
 * `.tooling/erbc/dlc-erbc.v1`（`ERATW_ROOT` 环境变量优先，其次两台主机的已知共享根；
 * `.tooling` 已 gitignore，产物不入库）。还依赖 `ERATW_GAMEDIR`（4.981 根）；erbc 目录或
 * 游戏根缺失、不是 4.981 口径（缺 `DLC注册/Addition_DLC_70.ERB`）整体跳过。
 */
class DlcPagingReproTest {

    private companion object {
        /** 每页固定 30 行（`WHILE 行数统计 < 30*ARG + 30`，每轮必增）。 */
        private const val PAGE_LINES = 30
    }

    @Test
    fun `补丁菜单同一页连续两次渲染内容必须一致`() {
        val session = assemble()

        val first = render(session)
        val second = render(session)
        val firstTail = first.takeLast(PAGE_LINES)
        val secondTail = second.takeLast(PAGE_LINES)

        assertTrue(
            firstTail.any { it.isNotBlank() },
            "第一次渲染末 $PAGE_LINES 行全空，装置没读到条目（erbc/语料没跑起来）：\n$first",
        )
        assertEquals(
            firstTail,
            secondTail,
            "同一页第二次渲染的末 $PAGE_LINES 行变了 —— LOCAL 未清零导致枚举从上次的位置继续：\n" +
                "第一次：$firstTail\n第二次：$secondTail\n--- 全量 ---\n$second",
        )
    }

    /** 调一次 `@DLCOPTIONPRINT(0)` 并返回当时的控制台行（函数不含 INPUT，无等待面）。 */
    private fun render(session: GameSession): List<String> {
        session.runtime.callFunction("DLCOPTIONPRINT", longArrayOf(0L))
        return session.console.snapshot().map { line ->
            line.segments.joinToString("") { it.text }
        }
    }

    /**
     * DLC erbc 产物目录：`ERATW_ERBC_DIR` 显式值优先，缺省回落共享根 `.tooling/erbc/dlc-erbc.v1`。
     *
     * 为什么要有回落：产物此前只在某一个 worktree 的 `build/dlc-erbc/erbc.v1` 下（build 目录
     * 换机/清构建即消失，用例会静默退化成 `assumeTrue` 假绿）。`.tooling` 是两台主机共用的
     * 工具链根（已 gitignore，不进库），把产物放这里换机仍可复现。
     * 共享根按 `ERATW_ROOT` 优先，其次两台主机的已知绝对路径（AGENTS §1 的主机表）。
     */
    private fun erbcDir(): Path? {
        System.getenv("ERATW_ERBC_DIR")
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return Path.of(it) }
        val roots = listOfNotNull(
            System.getenv("ERATW_ROOT")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) },
        )
        return roots
            .map { it.resolve(".tooling/erbc/dlc-erbc.v1") }
            .firstOrNull {
                Files.exists(it.resolve("program.erbc")) && Files.exists(it.resolve("program.meta"))
            }
    }

    /** 生产装载链（`ProgramLoader.loadMapped` + `BootMetadata.read` + [GameSession]），只读语料。 */
    private fun assemble(): GameSession {
        val erbcDir = erbcDir()
        assumeTrue(
            erbcDir != null,
            "缺 DLC erbc 产物（ERATW_ERBC_DIR 或共享根 .tooling/erbc/dlc-erbc.v1），跳过",
        )
        assumeTrue(
            Files.exists(erbcDir!!.resolve("program.erbc")) && Files.exists(erbcDir.resolve("program.meta")),
            "erbc 产物目录下缺 program.erbc / program.meta，跳过",
        )
        val gameRoot = System.getenv("ERATW_GAMEDIR")
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(it) }
        assumeTrue(gameRoot != null, "缺 ERATW_GAMEDIR（须指到 4.981 打包根），跳过")
        // 只钉 4.981 口径：4.980 没有 DLC 注册表，那条菜单不在本用例判据内。
        assumeTrue(
            Files.exists(gameRoot!!.resolve("ERB/DLC/DLC注册/Addition_DLC_70.ERB")),
            "当前语料不是 4.981 打包根（缺 Addition_DLC_70.ERB），跳过",
        )

        val program = ProgramLoader.loadMapped(erbcDir.resolve("program.erbc"))
        val metadata = BootMetadata.read(erbcDir.resolve("program.meta"))
        return GameSession(
            gameRoot = gameRoot,
            program = program,
            metadata = metadata,
            consoleWidth = 1800,
            printCWidth = 25,
            maxLines = 2000,
            randSeed = 20260226L,
        )
    }
}
