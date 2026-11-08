package com.eratw.port.boot

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.err.CodeEE
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * ENGFIX-5 真语料取证：4.981 `CHARALIST_DT` 的空槽是否可达 TRYC 空名链。
 *
 * 判据链（全部落在这份真实语料上，不拿合成切片替代）：
 * 1. `TEMP_CHARA_LIST_BY_DT.ERH:13-14` 的 `#DIMS COLUMN_NAME/COLUMN_VAR, MAX_COLUMN_GROUP, MAX_COLUMN`
 *    = 10×12 固定维；`LIST_BY_DT_COLUMN_INIT` 只为 group 0–5 灌数据（`:696-769` 的
 *    `CASEELSE` 是 group 6–9 的空分支）⇒ 至少 4×12=48 个空槽（本文件第 1 例实读）；
 * 2. 收集循环 `TEMP_CHARA_LIST_BY_DT.ERB:296-333` **没有**初始化循环 `:269` 那样的
 *    `STRLENS(COLUMN_VAR)==0 → CONTINUE` 前置守卫，按 `VARSIZE` 全槽遍历；
 * 3. 空槽的 `LIST_DT_CELL_DISABLE(",")` 落 `CASEELSE RETURNF 0`（`個別処理.ERB:207-208`）
 *    ⇒ 不禁用 ⇒ 走到 `TRYCCALLFORM %L_ELEMENT:0%`（空名）；
 * 4. 官方 `CalledFunction.cs:93-105` 对空名无早退分支，查表失败即 `isTry` 跳
 *    CATCH 体首（`FunctionIdentifier.cs:3577-3588`；装载期 `ErbLoader.cs:1339-1348`
 *    把 TRYC 的 `JumpToEndCatch` 设为 CATCH 行本身，主循环先 `ShiftNextLine` 再执行，
 *    故落点是 CATCH 的下一行=体内首行）⇒ `GET_INT_BY_NAME_ELEM("")` 的
 *    `CASEELSE THROW ARG_VARNAME未定义为变量名`（`TEMP_CHARA_LIST_BY_DT_FUNC.ERB:327-328`）。
 *
 * 结论（本路只取证、不改引擎）：**可达** —— 但仅在 SAVEDATA `高级列表功能` 打开时
 * （`ステータス表示関連/能力表示.ERB:926` 的门；该键 `#DIM SAVEDATA` 缺省 0）。
 * 因此真机报障链是 4.981 语料自身缺陷（初始化/收集两遍的守卫不对称），照 ADR-005
 * 原样复刻，不在引擎加第二层守卫；登记见 `specs/verify-notes.md` §31。
 *
 * **「语料自身缺陷」结论已于 2026-11-06 翻案（见 §31 末尾追记）**：本文件第 2 例直接
 * 调 `GET_INT_BY_NAME_ELEM(0,"",0)`，证明「空名会 THROW」；但真语料里它包在
 * `DT_CELL_SET` 的第 4 实参位，官方 `DataTableCellSetMethod` 是惰性的 —— 空列名
 * ⇒ 列不存在 ⇒ `return -3`，该实参一次都不会被求值。故本文件仍成立（空名确实 THROW），
 * 但推不出「原版也会炸」。真正缺陷在移植侧编译期把语句位内建实参提前降级；玩家链
 * 由 `CharaListDtLazyCellSetTest` 钉住。
 */
class CharaListDtCorpusProbeTest {

    private companion object {
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
                    // 头文件全放行（COLUMN_NAME/COLUMN_VAR 等 10×12 声明在 .ERH）；
                    // 函数体只切 CHARALIST_DT（DEFINE_COLUMN_* 与两个被测函数都在此目录）。
                    rel.endsWith(".erh", ignoreCase = true) || rel.startsWith("CHARALIST_DT/")
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
                VmConfig(maxStepsPerRun = 300_000L),
                consoleSink = console,
                semConfig = SemConfig(printCPerLine = 5),
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
    }

    @Test
    fun `4_981 真语料 DT 列初始化后 group 6-9 全空且维数为 10x12`() {
        assumeTrue(corpus != null, "未找到真实语料（设 ERATW_CORPUS 指向 4.981 .../ERB）")
        val m = model!!
        assumeTrue(m.program != null, "Program 构建失败：${m.buildError}")
        assertTrue(
            m.compileFailures.none { it.contains("TEMP_CHARA_LIST_BY_DT") || it.contains("DEFINE_COLUMN") },
            "CHARALIST_DT 切片编译失败：${m.compileFailures.filter { it.contains("TEMP_CHARA_LIST_BY_DT") || it.contains("DEFINE_COLUMN") }}",
        )
        val env = buildEnv(m, corpus!!)

        // 维数实读：先证 10×12 的最末槽可访问（小一维会在 resolve/越界处抛 CodeEE）。
        assertEquals("", env.store.readStr("COLUMN_VAR", longArrayOf(9L, 11L)), "COLUMN_VAR:9:11 必须可访问且缺省为空")

        env.runtime.callFunction("LIST_BY_DT_COLUMN_INIT", longArrayOf(-1L))

        // group 0 已有动态 DEFINE_COLUMN_* 灌入的列（非空即证明初始化链真的跑了）。
        assertTrue(
            env.store.readStr("COLUMN_VAR", longArrayOf(0L, 0L)).isNotEmpty(),
            "group 0 的第一列必须由 DEFINE_COLUMN_* 灌入非空 COLUMN_VAR",
        )
        val empty = ArrayList<String>()
        for (g in 0L until 10L) {
            for (c in 0L until 12L) {
                if (env.store.readStr("COLUMN_VAR", longArrayOf(g, c)).isEmpty()) empty.add("$g:$c")
            }
        }
        // CASEELSE 覆盖 group 6–9（4×12=48 槽）⇒ 至少 48 个空槽一定会进收集循环。
        assertTrue(
            empty.size >= 48 && (6L..9L).all { g -> (0L until 12L).all { c -> "$g:$c" in empty } },
            "group 6-9 必须整组为空（COLUMN_GROUP_PRESET 空 → 内层 FOR 在 :774 BREAK）；实际空槽=${empty.size}：${empty.take(60)}",
        )
    }

    @Test
    fun `空槽的 disable 判据放行且空名落 GET_INT_BY_NAME_ELEM 的 THROW`() {
        assumeTrue(corpus != null, "未找到真实语料（设 ERATW_CORPUS 指向 4.981 .../ERB）")
        val m = model!!
        assumeTrue(m.program != null, "Program 构建失败：${m.buildError}")
        val env = buildEnv(m, corpus!!)

        // 空槽 L_ELEMENT:0=""、L_ELEMENT:1="" ⇒ ARG_VAR=","（個別処理.ERB:170/:207-208）。
        val disable = env.runtime.invokeUserMethod("LIST_DT_CELL_DISABLE", listOf(Value.StrV(","), Value.IntV(0L)))
        assertEquals(0L, (disable as Value.IntV).v, "空串拼出的 \",\" 必须落 CASEELSE RETURNF 0（不禁用）")

        // 已填槽路径不受影响：GET_INT_BY_NAME_ELEM 命中 BASE 分支。
        env.store.writeInt("BASE", longArrayOf(0L, 0L), 7L)
        val hit = env.runtime.invokeUserMethod(
            "GET_INT_BY_NAME_ELEM",
            listOf(Value.IntV(0L), Value.StrV("BASE"), Value.IntV(0L)),
        )
        assertEquals(7L, (hit as Value.IntV).v, "命中路径必须照常返回 BASE:0:0")

        // 空名 —— 官方 TRYC 未命中会进 CATCH 体，而体内这条直接 THROW。
        val err = assertFailsWith<CodeEE> {
            env.runtime.invokeUserMethod(
                "GET_INT_BY_NAME_ELEM",
                listOf(Value.IntV(0L), Value.StrV(""), Value.IntV(0L)),
            )
        }
        assertTrue(
            err.message.orEmpty().contains("ARG_VARNAME未定义为变量名"),
            "空名必须落 CASEELSE THROW（TEMP_CHARA_LIST_BY_DT_FUNC.ERB:327-328）；实际：${err.message}",
        )
    }
}
