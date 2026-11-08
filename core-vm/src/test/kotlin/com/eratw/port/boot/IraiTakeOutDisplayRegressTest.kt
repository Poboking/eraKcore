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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 玩家反馈「一旦接过一个委托（依頼），所有角色的『带出去』都会变成『依頼中』」的
 * **真实语料**回归钉 —— 2026-11-07 玩家报告，引擎侧根因与 D7（`e9cf9fb2`）同族。
 *
 * ## 语料链（4.981 / 4.980 同形，行号以 4.981 为准）
 *
 * 指令列表每个按钮的名字来自 `ERB/魔改内容/qol/QOL_USERCOM.ERB:64`
 * `TEMP_COM_DISPLAY_NAME '= GETMETHS(@"COM351_DISPLAY", "Null")`：
 * 方法**存在**时取其字符串返回值，非 "Null"/非空串就整个替换按钮名
 * （`QOL_USERCOM.ERB:93-95`）。
 *
 * - `COM351_DISPLAY`（`ERB/コマンド関連/COMF/日常系/COMF351 連れ出す.ERB:5-16`）是
 *   `#FUNCTIONS`，只设 `TEMP_COM_DISPLAY_COLOR`、**无 RETURNF ⇒ 掉尾**；原版掉尾
 *   `ReturnF(null)`，消费侧得空串（`ProcessState.cs:289-295` +
 *   `SuperUserDefinedMethodTerm.cs:33-38`），按钮名回落 `TRAINNAME_TR(351)` = 「帯出去」。
 * - 但它的 IF/ELSEIF 链（`:10/:12`）必经 `COM_351_CONDITION` / `CAN_IRAI_CHARA_GO_TOGATHER`
 *   （`COMF351 連れ出す.ERB:296-316`），后者逐槽调
 *   `CAN_IRAI_SLOT_GO_TOGATHER`（`ERB/コマンド関連/COMF/依頼/FUNC_IRAI.ERB:244-256`），
 *   其第一条判据就是 `IRAI_CHARA_TO_PROGRESS(CLIENT_ID)`（`:250`）——
 *   `#FUNCTIONS`，受託中返回 `OPR_委托中` = **「依頼中」**（`FUNC_IRAI.ERB:397-398`、
 *   `ERB/DIM.ERH:474`）。
 *
 * ## 根因（D7 修复前）
 *
 * 修复前 `_RETS` 在**任意深度**都写 `Vm.stringResult`；`COM351_DISPLAY` 以掉尾方法身份
 * 经 `GETMETHS` 重入（`ProcessRuntime.invokeUserMethod` → 派生 VM `run`），体内内层
 * `IRAI_CHARA_TO_PROGRESS` 的 `_RETS "依頼中"`（depth>0）把寄存器污染成「依頼中」，
 * 尾声 `_RET NONE` 不覆盖 ⇒ `invokeUserMethod` 把它当成 COM351_DISPLAY 自己的返回值
 * ⇒ 按钮名变成「依頼中」。受託只会往 `IRAI_SLOT`（`ERB/DIM.ERH:431`）里放进**一条**
 * 槽，而 `CAN_IRAI_SLOT_GO_TOGATHER` 对**任何**角色都会先读委托人的进度串
 * （`FUNC_IRAI.ERB:250`），所以**所有角色**的 [351] 按钮一起变 —— 与玩家描述逐字吻合。
 * 同机制已有真机先例：448 按钮「显示成上一次调用过的地名」（BOARD D7 行）。
 *
 * D7（`e9cf9fb2`）把 `stringResult` 收成只认顶层帧（depth==0）的 `_RETS`（`Vm.kt`
 * `_RETS` 分支），内层串只走 `leaveFrameStr` 的调用方返回槽。本钉把**真实语料的
 * COM351_DISPLAY 全链**钉住，补 `GetmethsDisplayRegressTest`（合成 448 形状）之外的
 * 两块：语料原生函数面，与「受託一条槽污染所有角色」的玩家形状。
 *
 * ## 装置口径
 *
 * 与 `NewGameAbLupRealCorpusProbeTest` 同源：切片只编 `FUNC_IRAI.ERB` +
 * `COMF351 連れ出す.ERB` + 全部 ERH（`DIM.ERH` 的 `IRAI_SLOT`/`OPR_委托中`/
 * `TEMP_COM_DISPLAY_COLOR` 常量面），其余函数落空体桩（`GETMETH_INT`/`GETMETH_STR`
 * 桩返回 0/空串，只影响 `INT_DATA_IRAI` 支路，不影响本钉的进度串链）。
 * 状态面照语料语义程序化注入：受託 = `IRAI_SLOT:1 = 1005`（`MAKE_IRAI_ID` =
 * 委托人 1000 + 依頼ID，`FUNC_IRAI.ERB:92-96`）+ `CFLAG:1:403 = 2`
 * （403 = 依頼状況，`CSV/CFLAG.als:154`、`CSV/CFLAG.csv:167`；2 = 依頼中，
 * `CSV/CFLAG.csv:169` 档位表）。
 *
 * 门控：`ERATW_CORPUS`（指到语料 ERB 子树）；缺 env ⇒ 假设跳过。
 */
class IraiTakeOutDisplayRegressTest {

    private companion object {
        private const val STEP_CAP = 100_000L

        /** `TEMP_COM_DISPLAY_COLOR` 的 ELSE 兜底色（`DIM.ERH:707` `C_AQUA = 0x66FFFF`）。 */
        private const val C_AQUA = 0x66FFFFL

        /** 依頼状況列（`CSV/CFLAG.als:154`）与档位 2 = 依頼中（`CSV/CFLAG.csv:169` 档位表）。 */
        private const val CFLAG_IRAI_STATUS = 403L

        private val corpus: RealCorpus? by lazy { RealCorpus.load() }

        private val model: BootModel? by lazy {
            val c = corpus ?: return@lazy null
            RealCorpusCompile.compile(
                c,
                fileLimit = null,
                systemSaveInBinary = SystemVariableBootstrap.systemSaveInBinaryFrom(
                    loadConfigOverrides(c.root.parent ?: c.root),
                ),
                sliceFilter = { p ->
                    val rel = c.root.relativize(p).toString().replace('\\', '/')
                    rel.endsWith(".erh", ignoreCase = true) ||
                        rel.startsWith("コマンド関連/COMF/依頼/FUNC_IRAI") ||
                        rel.startsWith("コマンド関連/COMF/日常系/COMF351")
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
            // MASTER(0) + 委托人(1) + 其他角色(2)：模板缺位时退 bare 角色（列面照空）。
            if (gameData != null && st.template(0L) != null) {
                repeat(3) { st.addCharacter(0L) }
            } else {
                repeat(3) { st.characters.addBare() }
            }
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

        /** 语料 GETMETHS 通道本体：`SymbolQueryMethods.callUserFunctionStr` 的同一条重入口。 */
        private fun Env.displayMethod(name: String): String? =
            (runtime.invokeUserMethod(name, emptyList()) as? Value.StrV)?.v

        private fun Env.acceptRequestOf(client: Long) {
            store.writeInt("IRAI_SLOT", longArrayOf(1), client * 1000 + 5)
            store.writeInt("CFLAG", longArrayOf(client, CFLAG_IRAI_STATUS), 2L)
        }

        private fun Env.setPair(master: Long, target: Long) {
            // 本语料 MASTER/TARGET 是 1 维登记位数组（`MASTER:0`/`TARGET:0` 形态）。
            store.writeInt("MASTER", longArrayOf(0), master)
            store.writeInt("TARGET", longArrayOf(0), target)
        }
    }

    @Test
    fun `夹具切片必须带上 COM351_DISPLAY 与 FUNC_IRAI`() {
        assumeTrue(corpus != null, "未找到真实语料（设 ERATW_CORPUS 指向 语料 ERB 子树）")
        val m = model!!
        assumeTrue(m.program != null, "Program 构建失败：${m.buildError}")
        assertTrue(
            m.fileBySigKey.values.any { it.replace('\\', '/').endsWith("COMF/依頼/FUNC_IRAI.ERB") },
            "FUNC_IRAI.ERB 未进程序：${m.fileBySigKey.values}",
        )
        assertTrue(
            m.fileBySigKey.values.any { it.replace('\\', '/').contains("COMF/日常系/COMF351") },
            "COMF351 未进程序",
        )
        assertTrue(
            m.compileFailures.none { it.contains("FUNC_IRAI") || it.contains("COMF351") },
            "切片编译失败：${m.compileFailures}",
        )
    }

    /**
     * 玩家主例：受託后，**委托人自己**的 [351] 按钮名必须是空串（回落「帯出去」），
     * 不得是内层进度串「依頼中」。
     */
    @Test
    fun `受託后委托人的 COM351_DISPLAY 掉尾返回必须为空串`() {
        assumeTrue(corpus != null, "未找到真实语料（设 ERATW_CORPUS 指向 语料 ERB 子树）")
        val m = model!!
        assumeTrue(m.program != null, "Program 构建失败：${m.buildError}")
        val env = buildEnv(m, corpus!!)
        env.acceptRequestOf(1L)
        env.setPair(master = 0L, target = 1L)
        val ret = assertNotNull(env.displayMethod("COM351_DISPLAY"), "COM351_DISPLAY 不在方法表")
        assertEquals("", ret, "掉尾 #FUNCTIONS 经 GETMETHS 通道必须得空串（原版 ReturnF(null)），不得读回内层进度串")
        // 防假绿：体真的跑完了 ELSE 链（`COMF351 連れ出す.ERB:15`），不是被桩短路。
        assertEquals(
            C_AQUA,
            env.store.readInt("TEMP_COM_DISPLAY_COLOR", longArrayOf(0)),
            "COM351_DISPLAY 的 ELSE 兜底色必须已写入（证明体真的执行到掉尾）",
        )
    }

    /**
     * 「所有角色」形状：同一条受託槽下，**其他角色**的 [351] 按钮同样必须保持空串 ——
     * `CAN_IRAI_SLOT_GO_TOGATHER`（`FUNC_IRAI.ERB:250`）对任何角色都会先读委托人进度串，
     * 这是污染不挑角色的原因。
     */
    @Test
    fun `受託后其他角色的 COM351_DISPLAY 掉尾返回同样必须为空串`() {
        assumeTrue(corpus != null, "未找到真实语料（设 ERATW_CORPUS 指向 语料 ERB 子树）")
        val m = model!!
        assumeTrue(m.program != null, "Program 构建失败：${m.buildError}")
        val env = buildEnv(m, corpus!!)
        env.acceptRequestOf(1L)
        env.setPair(master = 0L, target = 2L)
        val ret = assertNotNull(env.displayMethod("COM351_DISPLAY"), "COM351_DISPLAY 不在方法表")
        assertEquals("", ret, "非委托人的掉尾返回同样不得读回内层进度串")
        assertEquals(
            C_AQUA,
            env.store.readInt("TEMP_COM_DISPLAY_COLOR", longArrayOf(0)),
            "ELSE 兜底色必须已写入（证明体真的执行到掉尾）",
        )
    }

    /**
     * 未受託对照：依頼已発生（档 1）但还没接（`IRAI_SLOT` 全空）时，进度串链根本不进
     * （`COUNT_IRAI_SLOT` = 0，`FUNC_IRAI.ERB:145-150`）⇒ 掉尾返回为空串，按钮名不变 ——
     * 钉住玩家「一旦**接过**才坏」的触发条件。
     */
    @Test
    fun `未受託空槽时 COM351_DISPLAY 掉尾返回为空串`() {
        assumeTrue(corpus != null, "未找到真实语料（设 ERATW_CORPUS 指向 语料 ERB 子树）")
        val m = model!!
        assumeTrue(m.program != null, "Program 构建失败：${m.buildError}")
        val env = buildEnv(m, corpus!!)
        env.store.writeInt("CFLAG", longArrayOf(1L, CFLAG_IRAI_STATUS), 1L)
        env.setPair(master = 0L, target = 1L)
        val ret = assertNotNull(env.displayMethod("COM351_DISPLAY"), "COM351_DISPLAY 不在方法表")
        assertEquals("", ret, "无受託槽时链上无进度串可残留，必须为空串")
    }

    /**
     * 污染源对照：`IRAI_CHARA_TO_PROGRESS(1)` 受託中就是返回「依頼中」
     * （`FUNC_IRAI.ERB:397-398` + `DIM.ERH:474`）—— 证明旧实现读到的串真实存在于链上，
     * 本钉不是钉在一个天然为空的链路上。
     */
    @Test
    fun `污染源对照 受託中的进度串就是依頼中`() {
        assumeTrue(corpus != null, "未找到真实语料（设 ERATW_CORPUS 指向 语料 ERB 子树）")
        val m = model!!
        assumeTrue(m.program != null, "Program 构建失败：${m.buildError}")
        val env = buildEnv(m, corpus!!)
        env.acceptRequestOf(1L)
        val ret = assertNotNull(
            env.runtime.invokeUserMethod("IRAI_CHARA_TO_PROGRESS", listOf(Value.IntV(1L))) as? Value.StrV,
            "IRAI_CHARA_TO_PROGRESS 不在方法表",
        )
        assertEquals("依頼中", ret.v, "受託中（CFLAG:依頼状況=2）的进度串必须是「依頼中」（DIM.ERH:474）")
    }
}
