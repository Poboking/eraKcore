package com.eratw.port.boot

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.expr.Value
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.proc.loadConfigOverrides
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.e2e.LineConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 红 J（NEWGAME-STEP-SPIN）**真实语料**取证：`ABL.ERB:79-131` 的 `$AGAIN` 回边与
 * `ABL_UP_DATA.ERB` 的需求链。
 *
 * 装置口径（与 `GameBootSmoke` 同源，只切 `ステータス計算関連/ABL/` 目录）：
 * - [RealCorpusCompile.compile] 的 `sliceFilter` 只切该目录（含 `ABL_UP_DATA.ERH` 的顶层
 *   `#DIM GLOBAL`）；其余定义落空体桩。符号表 / prescan / `#DEFINE` / 常量仍走全量口径；
 * - 运行期装配 = `VariableStore`(CSV 注册表) + 全局/私有 `#DIM` 注册 + 标签表 + 数据节
 *   （`GOTO AGAIN` 依赖 [ProcessRuntime.installLabelTable]）；
 * - 语料缺失（CI 无 `ERATW_CORPUS`）时整体跳过。
 *
 * 读数面（每条都对应一条原版语义）：
 * 1. `@JUEL_DEMAND_17` 的返回值 —— `ABL_UP_DATA.ERB:1502-1567`：`VARSET LOCAL`（:1506）后
 *    按 `ARG` 档位（:1508/:1545）写 `LOCAL:11/:12/:0`，`RETURNF LOCAL:(ARG:1)`（:1567）；
 *    档位 > 1 直接 `-1`（:1504-1505）。这是 `能力値不足` 判据的上游（`ABL.ERB:89`）。
 * 2. `@SHOW_ABLUP_SELECT` 的终止性 —— `ABL.ERB:79-131`：JUEL 全空时档 0 的珠位需求必然
 *    不足（`:90`）⇒ `:96-97 CONTINUE` ⇒ `NEXT` 推进档位，档 2/3 由 `:85-86` 的 `< 0` 出体；
 *    资源充足时 `$AGAIN` 会持续升级直到不足，最终仍须收敛（不许出现 5,000,000 步闸）。
 */
class NewGameAbLupRealCorpusProbeTest {

    private companion object {
        /** 回边万步量级必须收敛；真机 5,000,000 步闸是同一判据的放大版。 */
        private const val STEP_CAP = 300_000L

        private val corpus: RealCorpus? by lazy { RealCorpus.load() }

        private val model: BootModel? by lazy {
            val c = corpus ?: return@lazy null
            RealCorpusCompile.compile(
                c,
                systemSaveInBinary = SystemVariableBootstrap.systemSaveInBinaryFrom(
                    loadConfigOverrides(c.root.parent ?: c.root),
                ),
                sliceFilter = { p ->
                    val rel = c.root.relativize(p).toString().replace('\\', '/')
                    // 头文件全部放行：顶层 `#DIM` / `#DIM CONST`（`ABL_UP_DATA.ERH:1` 的
                    // `ABL_UP_RAIT`、别处的 `MAGIC_DLC_*`）都靠本循环收集并注册到运行期；
                    // 函数体只切 ABL 目录（其余落空体桩，不影响本取证链）。
                    rel.endsWith(".erh", ignoreCase = true) ||
                        rel.startsWith("ステータス計算関連/ABL/")
                },
            )
        }

        private class Env(val runtime: ProcessRuntime, val store: VariableStore)

        private fun buildEnv(m: BootModel, c: RealCorpus): Env {
            val program = requireNotNull(m.program) { "Program 构建失败：${m.buildError}" }
            val gameRoot = c.root.parent ?: c.root
            val console = LineConsoleSink(barWidth = 199, printCLength = 25)
            val host = ConsoleBuiltinHost(gameRoot, console)
            val gameData = GameDataLoader.load(gameRoot.resolve("CSV"))
            val st = VariableStore(gameData?.registry ?: VariableRegistry(), VarConfig())
            if (gameData != null) {
                for (t in gameData.templates) st.registerTemplate(t)
                st.loadItemPrice(gameData.itemPrices)
                st.reloadStrNames()
            }
            if (gameData != null && st.template(0L) != null) st.addCharacter(0L)
            else st.characters.addBare()
            SystemVariableBootstrap.install(
                st,
                m.globalDimDecls.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
                m.dimParseConfig,
            )
            for ((fk, spec) in m.userVarDecls) runCatching { st.declareUserVar(spec, fk) }
            val rt = ProcessRuntime(
                program,
                st,
                m.table,
                VmConfig(maxStepsPerRun = STEP_CAP),
                consoleSink = console,
                semConfig = SemConfig(printCPerLine = 5), // 游戏 emuera.config:19
                builtinHost = host,
                randSeed = 20260226L,
            )
            rt.installLabelTable(object : ProcessRuntime.LabelTable {
                override fun labelPc(functionKey: String, label: String): Int? =
                    m.labelPcs[functionKey.uppercase()]?.get(label.uppercase())
            })
            rt.installDataSections(ProcessRuntime.DataSectionSource { index -> m.dataSections[program.statement(index)] })
            return Env(rt, st)
        }

        private fun Env.read(name: String, vararg idx: Long): Long =
            store.readInt(name, LongArray(idx.size) { idx[it] })

        private fun Env.write(name: String, indices: LongArray, value: Long) {
            store.writeInt(name, indices, value)
        }
    }

    @Test
    fun `夹具切片与私有变量注册必须干净`() {
        assumeTrue(corpus != null, "未找到真实语料（设 ERATW_CORPUS 指向 .../ERB）")
        val m = model!!
        assumeTrue(m.program != null, "Program 构建失败：${m.buildError}")
        assertTrue(
            m.fileBySigKey.values.any { it.endsWith("ABL/ABL.ERB") },
            "ABL.ERB 未进程序：${m.fileBySigKey.values}",
        )
        assertTrue(
            m.fileBySigKey.values.any { it.endsWith("ABL/ABL_UP_DATA.ERB") },
            "ABL_UP_DATA.ERB 未进程序",
        )
        assertTrue(
            m.compileFailures.none { it.contains("ABL_UP_DATA") },
            "ABL_UP_DATA 切片编译失败：${m.compileFailures.filter { it.contains("ABL_UP_DATA") }}",
        )
        assertTrue(
            m.compileFailures.none { it.contains("ABL/ABL.ERB") },
            "ABL.ERB 切片编译失败：${m.compileFailures.filter { it.contains("ABL/ABL.ERB") }}",
        )
        val env = buildEnv(m, corpus!!)
        assertTrue(
            env.store.characters.count >= 1,
            "CHARANUM 必须 >= 1（ABL.ERB:9 的 FOR 角色,0,CHARANUM）：${env.store.characters.count}",
        )
    }

    /**
     * `@JUEL_DEMAND_17` 的档位读数（`ABL_UP_DATA.ERB:1502-1567`）。
     *
     * 调用面：该函数形参全是 `ARG` 系（`@JUEL_DEMAND_17(ARG,ARG:1,ARG:2)`），
     * 根级 `invokeUserMethod` 按位写 `ARG:0..2` 与头部形参位一一对应
     * （spec: `ProcessState.cs:435-452`），故口径与 ERB 内调用一致。
     */
    @Test
    fun `JUEL_DEMAND_17 按档位返回 LOCAL 里预置的需求`() {
        assumeTrue(corpus != null, "未找到真实语料（设 ERATW_CORPUS 指向 .../ERB）")
        val m = model!!
        assumeTrue(m.program != null, "Program 构建失败：${m.buildError}")
        val env = buildEnv(m, corpus!!)
        fun demand17(level: Long, pattern: Long): Long {
            val v = env.runtime.invokeUserMethod(
                "JUEL_DEMAND_17",
                listOf(Value.IntV(level), Value.IntV(pattern), Value.IntV(0L)),
            )
            return (v as Value.IntV).v
        }
        // 档 0（:1508-1543）：ABL:0:百合属性 初始 0 ⇒ CASE 0 写 LOCAL:11=200、LOCAL:12=0
        //（:1511-1512），其余位被 VARSET LOCAL（:1506）清 0。
        assertEquals(200L, demand17(0, 11), "档 0 / 珠 11 应返回 LOCAL:11=200（ABL_UP_DATA.ERB:1511）")
        assertEquals(0L, demand17(0, 12), "档 0 / 珠 12 应返回 LOCAL:12=0（ABL_UP_DATA.ERB:1512）")
        assertEquals(0L, demand17(0, 0), "档 0 / 珠 0 不在预置集，应为 0（:1506 的 VARSET LOCAL）")
        // 档 1（:1545-1553）：CASE 0 写 LOCAL:0=1000（:1548）。
        assertEquals(1000L, demand17(1, 0), "档 1 / 珠 0 应返回 LOCAL:0=1000（ABL_UP_DATA.ERB:1548）")
        // 档 > 1 早退 -1（:1504-1505）—— ABL.ERB:85 的 `< 0 → CONTINUE` 出体依赖这条。
        assertEquals(-1L, demand17(2, 0), "档 2 必须早退 -1（ABL_UP_DATA.ERB:1504-1505）")
        assertEquals(-1L, demand17(3, 11), "档 3 必须早退 -1（ABL_UP_DATA.ERB:1504-1505）")
    }

    /**
     * 珠/经验各给 1,000,000 时的收敛读数（与真机 `.erbc` 路径同一输入。
     * 产物路径打转的根因见 [com.eratw.port.core.compile.ForStepErbcRegressionTest]）：
     * 源码直编下 `$AGAIN` 回边会一路升到资源不足再 `CONTINUE` 出体，因此只钉收敛与「确实升过级」。
     */
    @Test
    fun `JUEL 一百万时 SHOW_ABLUP_SELECT 必须收敛且确实升级`() {
        assumeTrue(corpus != null, "未找到真实语料（设 ERATW_CORPUS 指向 .../ERB）")
        val m = model!!
        assumeTrue(m.program != null, "Program 构建失败：${m.buildError}")
        val env = buildEnv(m, corpus!!)
        env.write("ABL", longArrayOf(0L, 0L), 100L)
        env.write("ABL", longArrayOf(0L, 14L), 100L)
        for (i in 0L until 100L) env.write("JUEL", longArrayOf(0L, i), 1_000_000L)
        for (i in 0L until 100L) env.write("EXP", longArrayOf(0L, i), 1_000_000L)
        val err = runCatching { env.runtime.callFunction("SHOW_ABLUP_SELECT") }.exceptionOrNull()
        assertTrue(
            err == null,
            "SHOW_ABLUP_SELECT 未在 $STEP_CAP 步内收敛：${err?.let { "${it::class.simpleName}: ${it.message}" }}；" +
                "ABL:0:30=${env.read("ABL", 0L, 30L)}；JUEL:0:11=${env.read("JUEL", 0L, 11L)}",
        )
        assertTrue(
            env.read("ABL", 0L, 30L) > 0L,
            "资源充足的档 0 必须真的升级（ABL.ERB:125 的 `+= 1` 是常量增量，解析期折成 AddConst）",
        )
        assertTrue(
            env.read("JUEL", 0L, 11L) < 1_000_000L,
            "升级必须真扣珠（ABL.ERB:110 的动态下标减法）",
        )
    }

    /**
     * JUEL 全空：档 0 的珠 11 需求 200 ⇒ `ABL.ERB:90` 命中不足 ⇒ `:97 CONTINUE` ⇒ `NEXT`
     * 推进 LOCAL，档 2/3 由 `:85-86 CONTINUE` 出体。**不许**出现 ABL 每轮 +1 的回边。
     */
    @Test
    fun `JUEL 全空时 SHOW_ABLUP_SELECT 必须收敛`() {
        assumeTrue(corpus != null, "未找到真实语料（设 ERATW_CORPUS 指向 .../ERB）")
        val m = model!!
        assumeTrue(m.program != null, "Program 构建失败：${m.buildError}")
        val env = buildEnv(m, corpus!!)
        // 让 ABL 30（自慰中毒）的前置判定（ABL_UP_DATA.ERB:1764-1768 的 Ｃ感覚 / 露出癖）
        // 通过，否则档 0 直接 RETURNF -2 ⇒ ABL.ERB:83-84 BREAK，测不到不足分支。
        env.write("ABL", longArrayOf(0L, 0L), 100L) // Ｃ感覚
        env.write("ABL", longArrayOf(0L, 14L), 100L) // 露出癖
        for (i in 0L until 100L) env.write("JUEL", longArrayOf(0L, i), 0L)
        for (i in 0L until 100L) env.write("EXP", longArrayOf(0L, i), 1_000_000_000L)
        val err = runCatching { env.runtime.callFunction("SHOW_ABLUP_SELECT") }.exceptionOrNull()
        assertTrue(
            err == null,
            "SHOW_ABLUP_SELECT 未能在 $STEP_CAP 步内收敛（\$AGAIN 回边不退出）：" +
                "${err?.let { "${it::class.simpleName}: ${it.message}" }}；" +
                "ABL:0:30=${env.read("ABL", 0L, 30L)}；JUEL:0:11=${env.read("JUEL", 0L, 11L)}",
        )
        assertEquals(0L, env.read("ABL", 0L, 30L), "JUEL 全空时一个 ABL 都不该升级（ABL.ERB:96-97 CONTINUE）")
        assertEquals(0L, env.read("JUEL", 0L, 11L), "不足路径不许扣珠（ABL.ERB:97 CONTINUE 早于 :110）")
    }
}
