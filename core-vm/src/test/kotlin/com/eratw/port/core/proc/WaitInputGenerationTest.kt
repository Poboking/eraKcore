package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * OBS2-L5 waitgen —— 重等之后旧按钮「点了没反应」的两笔收口。
 *
 * ## 第一笔：系统菜单等待入口的脚本行身份接线（`GameSession` 的 `scriptLineToken`）
 *
 * 原版的等待身份是**脚本行**：`EmueraConsole.newGeneration()`（`EmueraConsole.cs:783-823`，
 * 判据 `:789-796`）拿 `process.getCurrentLine`（`Process.cs:142` → `ProcessState.currentLine`，
 * `ProcessState.cs:40-48`）与 `lastInputLine` 比身份：
 * - 同一句脚本重问（商店买不成 → `deleteLine(1)` + `PrintTemporaryLine` → `endCallShowShop`
 *   重等，`Process.cs:2624-2632`，**不重印按钮、不置 `updatedGeneration`**）⇒ 身份不变 ⇒
 *   `:795-796` 的 else 支、`lastButtonGeneration` 原地不动 ⇒ 屏上旧按钮**照样可点**；
 * - 脚本走到另一行 ⇒ `:790-791` 前移世代、旧页按钮作废。
 *
 * 移植侧的通道造好没接：[ConsoleBeginHost] 有形参 `scriptLineToken`，两个等待点用它填
 * `scriptLine`（`readAnyKey` / `waitSystemInteger`），但 `GameSession` 构造该宿主时**没传**
 * ⇒ `scriptLine = -1` = 未接线哨兵 ⇒ 身份回落**行水位**（`LineBufferConsoleSink.kt:713-714`）。
 * 报错提示行与键入回显一推进行表，第二次重等的入口世代就前移 ⇒ 屏上旧按钮失配 =
 * 玩家「点了没反应」。OBS2-L1 把这一对读数钉死在 ShopTemporaryLineTest（生产行水位支：
 * 首等 `[0,100]`、二次重等 `[]`）并写明「非那刀引入」。
 *
 * A 组用**真 [GameSession] 装配 + 真编译器产物**把接线后的行为级判据钉住：重等前后同一组
 * 按钮的可点集合不变（`[0,100]` → `[0,100]`），即 L1 那条 `[]` 的生产读数按原版变成可点。
 * B 组钉判据的两侧：未接线（行水位）仍 `[]`；脚本真的推进时身份变更仍作废（接线不是把世代
 * 整块冻住）。
 *
 * **为什么「把现算通道接上」还不够**（A 组第 2 条用例把这条读数钉住）：
 * `ProcessRuntime.kt:1242-1247` 的 `scriptLineTokenNow` 是**现算**的，无帧即 -1；而 BEGIN 状态机
 * 的每次等待都发生在 `vm.run` **返回之后**（`Vm.unwindScopes` 把帧深收回 -1，`Vm.kt:947-955`；
 * 引擎侧 `SHOW_SHOP` 这类调用走 `runEntry` → `vm.run`，`ProcessRuntime.kt:2901-2923`）
 * ⇒ 原样接上恒得 -1、等于没接（本仓另一处独立记载同一事实：`core/diag/PageFunctionTrail.kt:9-14`
 * 「系统队列等待点全部发生在 BeginFlow 的 Kotlin 步、相邻两次 ERB 调用之间 ⇒ 无帧」）。
 * 缺的那一格由最清楚「这一屏是谁印的」的 [BeginFlow] 补：它的脚本调用统一入口 `callScript`
 * 在真的跑过一支函数之后把函数名登记给宿主（[BeginHost.noteWaitPageProducer]），宿主按
 * **运行期同一张** [LineIdentityIds] 表取身份位（`GameSession.beginWaitPageIdentity`），
 * 对位的正是 C# 那个只在执行行时写入、函数返回不清空的**字段**
 * （写点 `FunctionIdentifier.cs:292`/`:3128`/`:3179`、`Process.cs:1814`；
 * 全仓无置 null 的写点）。粒度差（函数级 vs 行级）与「为什么可观测面等价」见
 * [BeginHost.noteWaitPageProducer] 的 KDoc。
 *
 * ## 第二笔：存读档问句从 writeLine 换到 printSingleLine
 *
 * 原版 `Process.cs:2687`/`:2694` 是 `console.PrintSingleLine(...)`（方法体
 * `EmueraConsole.cs:3147-3165`：**非临时行**）—— 先 `PrintFlush(force:false)`（`:3155` →
 * `:3375-3383`）结掉未结缓冲，再 `BufferToSingleLine(force:true)`（`:3158` → `:3339-3353` →
 * `PrintStringBuffer.cs:170-177` 的 `FlushSingleLine`）把本句排成**一条不折行**的显示行。
 * 移植侧 [BeginFlow] 之前走 `host.writeLine`（→ `console.printLine` = `Print` + `NewLine`，
 * `EmueraConsole.cs:3167-3172`），问句与上一句 PRINTN/PRINTC 的未结残段**并进同一段缓冲**
 * 再按宽度一起折行。C 组钉换道、缺省降级与真 sink 的单行读数。
 *
 * 真机手感（触屏上按钮实际亮不亮）：本机 `adb devices` 为空 ⇒ 一律 TODO(verify)，
 * 这里所有读数都是桌面 [LineBufferConsoleSink] 的快照。
 */
class WaitInputGenerationTest {

    // ------------------------------------------------------------------
    // 假体与装置（形制同 ShopTemporaryLineTest / SystemMenuGenerationWiringTest）
    // ------------------------------------------------------------------

    private class FakeCalls : BeginCallPort {
        val functions = HashSet<String>()
        val events = HashSet<String>()
        val called = ArrayList<String>()
        var systemResultValue: Long = 0L
        var pending: BeginType? = null

        override fun callFunction(name: String, force: Boolean, isEvent: Boolean): Boolean {
            val exists = if (isEvent) events.contains(name) else functions.contains(name)
            if (!exists) {
                if (force) throw CodeEE("找不到函数\"@$name\"")
                return false
            }
            called += name
            return true
        }

        override fun result(): Long = 0L
        override fun setResult(value: Long) {}
        override fun systemResult(): Long = systemResultValue
        override fun setSystemResult(value: Long) {
            systemResultValue = value
        }

        override fun nextCom(): Long = -1L
        override fun setNextCom(value: Long) {}
        override fun selectCom(): Long = -1L
        override fun setSelectCom(value: Long) {}
        override fun isCTrain(): Boolean = false
        override fun clearCommands(): Boolean = false
        override fun beginTrainReset() {}
        override fun afterShowUserComReset() {}
        override fun afterInputComReset() {}
        override fun afterSourceCheckReset() {}
        override fun trainName(code: Int): String? = null
        override fun trainNameCount(): Int = 0
        override fun commandsConsumed(): Boolean = true
        override fun pendingBegin(): BeginType? = pending
        override fun takePendingBegin(): BeginType? = pending.also { pending = null }
        override fun clearPendingBegin() {
            pending = null
        }
    }

    /** 售罄模型（`:2613` ItemSales 假）：买不成 ⇒ 两支提示 + 重等，全程不重印按钮。 */
    private class AlwaysOutOfStockModel(val max: Int = 100) : ShopHostModel {
        override fun maxShopItem(): Int = max
        override fun itemSales(itemNo: Int): Boolean = false
        override fun buyItem(itemNo: Int): Boolean = false
        override fun writeResult(value: Long) {}
    }

    /**
     * 生产同形装置：[ConsoleBeginHost] + 真 sink + 真回显接线（`onEchoInput → printLine`，
     * 装配第 7 步之后那一条）。变量是**脚本行身份的来法**，与生产的两条通道一一对应：
     * [token] = 现算通道 `scriptLineToken`（生产 = `ExecContext.scriptLineToken`，BEGIN 等待点
     * 恒 -1，见 A 组第 2 条）；[pageIdentity] = 「本页来源函数」通道（生产 =
     * `GameSession.beginWaitPageIdentity`，取运行期同一张 [LineIdentityIds]）。
     * 两者都不给 = 接线前现状（身份退化行水位）。
     */
    private class HostRig(
        token: (() -> Long)? = null,
        pageIdentity: ((String) -> Long)? = null,
        model: ShopHostModel = AlwaysOutOfStockModel(),
        clientWidth: Int = 80,
    ) {
        val console = LineBufferConsoleSink(clientWidth = clientWidth)
        val input = QueueInputSink()
        val host = ConsoleBeginHost(
            input,
            console,
            shopHostModel = model,
            scriptLineToken = token,
            pageScriptLineIdentity = pageIdentity,
        )

        init {
            input.onEchoInput = { console.printLine(it) }
        }

        /** 商店菜单页：一行两颗按钮（值形制同 L1/`MAPMOVE` 侧读数）。 */
        fun printShopMenu() {
            console.printButton("買う", 0L)
            console.printButton("出る", 100L)
        }

        fun optionValues(): List<Long> = console.currentOptions().map { it.value as Long }

        fun buttonValues(): List<Long> = console.buttons().map { it.value as Long }

        fun lineTexts(): List<String> = console.lines().map { console.textOf(it) }

        /**
         * 连走两次商店等待入口（第一次取 [first]、第二次取 [second]），
         * 返回两次入口冻结的可点集合。第二次是**同一条脚本行的重等**
         * （原版 `Process.cs:2632` → `:2603-2607`，中间没有任何脚本行推进）。
         */
        fun twoShopWaits(first: Long, second: Long): Pair<List<Long>, List<Long>> {
            printShopMenu()
            val flow = BeginFlow(FakeCalls(), host)
            flow.enterState(SystemStateCode.Shop_WaitInput)
            assertTrue(input.submitInt(first), "预喂第一次的商品号 $first")
            flow.step() // 等待入口① → itemSales 假 → deleteLine(1)+临时行 → 回 Shop_WaitInput
            val a = optionValues()
            assertTrue(input.submitInt(second), "预喂第二次的商品号 $second")
            flow.step() // 等待入口②（重等）
            return a to optionValues()
        }
    }

    /**
     * 与生产 `GameSession.beginWaitPageIdentity` 同一口径的身份位提供者：
     * 同一张 [LineIdentityIds] 表（生产是 `store.aux` 的 `proc.lineid` 槽，本装置按会话新建一份）
     * + `pc` 恒 0（函数级粒度，理由见 [BeginHost.noteWaitPageProducer] 的 KDoc）。
     */
    private fun pageIdentityResolver(): (String) -> Long {
        val ids = LineIdentityIds()
        return { key -> ids.tokenOf(key, BeginFlow.PAGE_IDENTITY_PC) }
    }

    // ------------------------------------------------------------------
    // A 组：生产接线（真 GameSession + 真编译产物），行为级判据
    // ------------------------------------------------------------------

    private fun parser(): LineParser =
        LineParser(SymbolTable().declareVariable("RESULT", OperandType.INT, 0))

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "TEST.ERB", i + 1, text))
        }.filterNotNull()
    }

    private class ShopFixture(val program: Program, val metadata: BootMetadata)

    /**
     * 四个函数的最小产物 + 侧车：`@SYSTEM_TITLE`（入口占位）、`@SHOW_SHOP`（把商店那一屏
     * 印出来，对应语料的 SHOW_SHOP；重等入口 `Process.cs:2603-2607` 印完才 setWaitInput）、
     * `@SYSTEM_AUTOSAVE` / `@SAVEINFO`（把 AUTO_SAVE 链两条实存路都堵上：无论
     * `calledWhenNormal` 走哪一支（`Process.cs:2556`/`:2561-2562`），都不会真去落盘、
     * 也不会撞 `ReadAnyKey` 的死等）。
     */
    private fun shopFixture(): ShopFixture {
        val compiler = ErbProgramCompiler()
        val keys = listOf("SYSTEM_TITLE", "SYSTEM_AUTOSAVE", "SAVEINFO", "SHOW_SHOP")
        val known = keys.toSet()
        val bodies = mapOf(
            "SYSTEM_TITLE" to listOf("@SYSTEM_TITLE", "PRINTL TITLE"),
            "SYSTEM_AUTOSAVE" to listOf("@SYSTEM_AUTOSAVE", "PRINTL AUTOSAVE"),
            "SAVEINFO" to listOf("@SAVEINFO", "PRINTL SAVEINFO"),
            "SHOW_SHOP" to listOf("@SHOW_SHOP", "PRINTL 商店菜单"),
        )
        val compiled = LinkedHashMap<String, FunctionBytecode>()
        for (key in keys) {
            compiled[key] = compiler.compileFunction(
                FuncSig(key),
                parse(*bodies.getValue(key).toTypedArray()),
                known,
            )
        }
        val program = compiler.build("SYSTEM_TITLE")
        val metadata = BootMetadata(
            labels = compiled.mapValues { (_, fb) -> fb.labels },
            functions = compiled.entries.mapIndexed { i, (key, fb) ->
                FuncMeta(
                    index = fb.index, key = key, name = key,
                    defIndex = i, position = "TEST.ERB:${i * 2 + 1}",
                )
            },
            fileByKey = compiled.keys.associateWith { "TEST.ERB" },
        )
        return ShopFixture(program, metadata)
    }

    /**
     * **主判据**：真 [GameSession] 装配下，商店买不成重等两次，屏上旧按钮的可点集合不变。
     *
     * 全程走生产链路（`GameSession.begin` → [ProcessRuntime] 的 BEGIN 状态机 →
     * `ConsoleBeginHost.awaitShopInput` → `LineBufferConsoleSink.noteInputWait`），
     * 断言基准 = 原版 `Process.cs:2624-2632`（买不成不重印按钮、不置 `updatedGeneration`）
     * + `EmueraConsole.cs:789-796`（同一条脚本行 ⇒ else 支 ⇒ 世代不前移）。
     * L1 在 ShopTemporaryLineTest 钉的生产读数「二次重等 `[]`」在此按原版变成 `[0,100]`。
     */
    @Test
    fun `生产接线 商店买不成重等两次旧按钮仍可点`(@TempDir dir: Path) {
        val f = shopFixture()
        val s = GameSession(
            gameRoot = dir,
            program = f.program,
            metadata = f.metadata,
            consoleWidth = 199,
            randSeed = 20261105L,
        )
        try {
            val entry = s.runtime.beginStep(BeginType.SHOP)
            assertEquals("beginShop", entry.handler, "Process.cs:2540-2551（EVENTSHOP 缺失 ⇒ NOT_CALLED）")
            // 泵到商店等待入口**之前**那一步：stepShopShow 只换态不取输入（Process.cs:2603-2607），
            // 再往后一步才会真挂起等输入，所以循环必须在状态到位时立刻停。
            val trail = ArrayList<String>()
            while (s.runtime.systemState != SystemStateCode.Shop_WaitInput) {
                val step = s.resumeSystem()
                trail += "${step.from.name}-${step.handler}->${step.to.name}(${step.outcome})"
                assertTrue(
                    trail.size <= 8,
                    "泵不到商店等待入口（AUTO_SAVE/SHOW_SHOP 链走偏）：$trail",
                )
            }
            s.console.printButton("買う", 0L)
            s.console.printButton("出る", 100L)

            assertTrue(
                s.inputSink.submitInt(0L),
                "预喂商品号 0：ITEMSALES 全 0 ⇒ 售罄支（Process.cs:2613/:2629-2630）",
            )
            s.resumeSystem() // 等待入口① + 售罄支（deleteLine(1) + 临时行）→ 回 Shop_WaitInput
            val first = s.console.currentOptions().map { it.value as Long }
            assertEquals(listOf(0L, 100L), first, "首等冻结的可点集合；步序=$trail")

            assertTrue(s.inputSink.submitInt(1L), "预喂商品号 1（第二次重等）")
            s.resumeSystem() // 等待入口②：同一条脚本行 ⇒ 世代不前移
            val second = s.console.currentOptions().map { it.value as Long }
            assertEquals(
                listOf(0L, 100L), second,
                "重等后旧按钮仍可点 —— 接线前（行水位）这里读 [] ；步序=$trail",
            )
            assertEquals(
                listOf(0L, 100L), s.console.buttons().map { it.value as Long },
                "按钮行本体一直在屏上（失配是世代级，不是删行）；步序=$trail",
            )
            val last = s.console.lines().last()
            assertTrue(last.isTemporary, "重等前的提示走临时行（Process.cs:2630）")
            assertEquals("售罄", s.console.textOf(last), "两支失败提示的售罄文案（Lang.cs:3154 / zhs:743）")
        } finally {
            s.close()
        }
    }

    /**
     * 第一手证据（生产链路）：**现算通道在 BEGIN 等待点读到 -1** —— 也就是「把
     * `ProcessRuntime` 的 `scriptLineTokenNow` 原样接进 `GameSession` 的构造点」这一刀
     * 单独做不成立，必须再登记「本页来源函数」。机制：`ProcessRuntime.kt:1243` 的
     * `activeVm.currentFunctionKey ?: return -1L` + `Vm.kt:947-955`（`run` 的 finally 把
     * `depth` 收回 -1）+ `ProcessRuntime.kt:2901-2923`（引擎侧函数走 `runEntry` → `vm.run`，
     * 返回即无帧）；等待点由 `GameSession.resumeSystem` 泵出，正落在这个无帧窗口里。
     * 与上一条主判据合起来读：现算通道给 -1，可点集合仍不变 ⇒ 保住旧按钮的是来源函数登记。
     */
    @Test
    fun `生产链路 现算通道在商店等待点读到负一`(@TempDir dir: Path) {
        val f = shopFixture()
        val s = GameSession(dir, f.program, f.metadata, consoleWidth = 199, randSeed = 20261105L)
        try {
            val readLive = { s.runtime.execContext.scriptLineToken?.invoke() }
            s.runtime.beginStep(BeginType.SHOP)
            val trail = ArrayList<String>()
            while (s.runtime.systemState != SystemStateCode.Shop_WaitInput) {
                val step = s.resumeSystem()
                trail += "${step.from.name}-${step.handler}->${step.to.name}"
                assertTrue(trail.size <= 8, "泵不到商店等待入口：$trail")
            }
            // SHOW_SHOP 已经跑过（印完这一屏），但帧已回卷 ⇒ 现算读数 -1。
            assertEquals(-1L, readLive() ?: -1L, "跑完 SHOW_SHOP 后无帧 ⇒ 现算读数 -1；步序=$trail")
            s.console.printButton("買う", 0L)
            s.console.printButton("出る", 100L)
            assertTrue(s.inputSink.submitInt(0L), "预喂商品号 0")
            s.resumeSystem() // 等待入口①（宿主在等待点现取身份位）
            assertEquals(-1L, readLive() ?: -1L, "等待点同样是无帧时刻 ⇒ 现算读数 -1；步序=$trail")
        } finally {
            s.close()
        }
    }

    /**
     * 接线钉（源码扫描，手法照 `ScriptLineTokenIdentityTest` 对 `scriptLineTokenNow` 的钉法）。
     * 四条缺一不可，本刀第一笔的**故意破坏点**就是把 ① 的 `pageScriptLineIdentity` 摘掉
     * （或把两条都传成 null）⇒ 本案与 A 组主判据同时红：
     * - ① 装配点把**两条**身份通道都交给 [ConsoleBeginHost]（只传现算那条等于没接 ——
     *   BEGIN 等待点无帧、现算读数恒 -1，A 组第 2 条用例就是那第一手证据）；
     * - ② 「本页来源」的身份位取运行期**同一张** [LineIdentityIds] 表，不新造第二套 token；
     * - ③ 该表的 aux 槽名与 `ProcessRuntime` 的私有常量 `LINE_IDENTITY_SLOT` 同串
     *   （两处各写一份才能编译，故用本案守住「改一处必须改另一处」）；
     * - ④ [BeginFlow] 的脚本调用**全部**经 `callScript` 统一入口，且真的跑过函数才登记
     *   （缺函数支一支脚本都没跑，身份位必须留在印上一屏的那支函数上）。
     */
    @Test
    fun `装配钉 两条身份通道与脚本调用统一入口都在位`() {
        val src = readMainSource("GameSession.kt")
        val start = src.indexOf("ConsoleBeginHost(")
        assertTrue(start >= 0, "装配点 ConsoleBeginHost 必须存在")
        val ctor = src.substring(start, minOf(src.length, start + 2500))
        assertTrue(
            ctor.contains("scriptLineToken = ::beginWaitScriptLineToken"),
            "① 现算通道必须接上（ExecContext.scriptLineToken；ProcessRuntime.kt:1093/:1242）—— " +
                "有帧时刻（语料在系统流程里 INPUT）读到的就是真行身份",
        )
        assertTrue(
            ctor.contains("pageScriptLineIdentity = ::beginWaitPageIdentity"),
            "① 「本页来源函数」通道必须接上 —— BEGIN 等待点无帧，只传现算那条恒 -1 = 等于没接",
        )
        val marker = "private fun beginWaitPageIdentity"
        val at = src.indexOf(marker)
        assertTrue(at >= 0, "取数通道 $marker 必须存在")
        val body = src.substring(at, minOf(src.length, at + 900))
        assertTrue(
            body.contains("slot(BEGIN_LINE_IDENTITY_SLOT)") && body.contains("LineIdentityIds"),
            "② 身份位必须取运行期同一张 LineIdentityIds 表（ProcessRuntime.kt:1246 同一实例），不新造 token 域",
        )
        val here = constString(src, "BEGIN_LINE_IDENTITY_SLOT")
        val there = constString(readMainSource("ProcessRuntime.kt"), "LINE_IDENTITY_SLOT")
        assertEquals(
            there, here,
            "③ aux 槽名两处必须同串，否则两条通道落在两个身份域、数值可能相撞（冻住该前移的世代）",
        )

        val flow = readMainSource("BeginFlow.kt")
        assertEquals(
            1,
            Regex("""calls\.callFunction\(""").findAll(flow).count(),
            "④ BeginFlow 里 calls.callFunction( 只允许出现在 callScript 统一入口那一次；" +
                "绕过统一入口的调用点 = 那一屏不登记身份位",
        )
        assertTrue(
            flow.contains("host.noteWaitPageProducer(name)"),
            "④ callScript 必须在真的跑过脚本函数后把函数名登记给宿主（缺函数支不登记）",
        )
    }

    // ------------------------------------------------------------------
    // B 组：判据的两侧（同一条通道，未接线 / 脚本真的推进）
    // ------------------------------------------------------------------

    /**
     * 对照（**未接线**）：不传 `scriptLineToken` ⇒ `scriptLine = -1` ⇒ 行水位
     * （`LineBufferConsoleSink.kt:714`）⇒ 第二次重等入口前移世代、旧按钮失配 = `[]`。
     * A 组摘掉接线后应当落回这一格。
     */
    @Test
    fun `未接线行水位 第二次重等旧按钮失配`() {
        val rig = HostRig(token = null)
        val (first, second) = rig.twoShopWaits(0L, 1L)
        assertEquals(listOf(0L, 100L), first, "首等冻结读数与接线无关")
        assertEquals(
            emptyList(), second,
            "回显+提示把行水位推前 ⇒ 第二次重等把旧按钮整批作废（本刀要治的病）",
        )
        assertEquals(listOf(0L, 100L), rig.buttonValues(), "失配是世代级的，按钮行本体没被删")
    }

    /** 稳定身份（同一条等待语句重等）：常量 token 复现 PC 上「同一句 INPUT 脚本行恒定」。 */
    @Test
    fun `稳定脚本行身份 第二次重等旧按钮仍可点`() {
        val rig = HostRig(token = { 777L })
        val (first, second) = rig.twoShopWaits(0L, 1L)
        assertEquals(listOf(0L, 100L), first)
        assertEquals(listOf(0L, 100L), second, "EmueraConsole.cs:795-796 走 else 支：同一条脚本行 ⇒ 世代不动")
    }

    /**
     * 生产形制的装置复现（**只有**来源函数通道，与真机一致 —— BEGIN 等待点无帧、现算通道读 -1）：
     * 同一屏重问 ⇒ 身份位不变 ⇒ `EmueraConsole.cs:793-796` else 支 ⇒ 旧按钮仍可点。
     * 本刀第一笔的**第二个破坏点**：把 `BeginFlow.callScript` 里的登记摘掉 ⇒ 本案与 A 组主判据红。
     */
    @Test
    fun `来源函数通道 同一屏重问旧按钮仍可点`() {
        val rig = HostRig(pageIdentity = pageIdentityResolver())
        rig.host.noteWaitPageProducer("SHOW_SHOP") // endCallShowShop 之前跑的就是它（Process.cs:2600/:2652）
        val (first, second) = rig.twoShopWaits(0L, 1L)
        assertEquals(listOf(0L, 100L), first, "首等冻结读数")
        assertEquals(listOf(0L, 100L), second, "重等后旧按钮仍可点（身份位同一 ⇒ 世代不前移）")
        assertEquals(listOf(0L, 100L), rig.buttonValues(), "按钮行本体一直在屏上")
    }

    /**
     * 判据的另一侧（本刀不过修）：**来源函数换了** ⇒ 身份位变 ⇒ `EmueraConsole.cs:789-791`
     * 前移 `lastButtonGeneration` ⇒ 旧页按钮照常作废。重印按钮那一支由盖章那条路接管
     * （`LineBufferConsoleSink.stampButtonGeneration` `:753-758`），不经身份位判定。
     */
    @Test
    fun `来源函数换了 第二次重等旧按钮照常作废`() {
        val rig = HostRig(pageIdentity = pageIdentityResolver())
        rig.host.noteWaitPageProducer("SHOW_SHOP")
        val (first, second) = rig.twoShopWaits(0L, 1L)
        assertEquals(listOf(0L, 100L), first)
        assertEquals(listOf(0L, 100L), second, "同一来源 ⇒ 重等保住旧按钮")

        rig.host.noteWaitPageProducer("SHOW_ABLUP_SELECT") // 换一屏（且不重印按钮）
        assertTrue(rig.input.submitInt(2L), "预喂第三次取值")
        val flow = BeginFlow(FakeCalls(), rig.host)
        flow.enterState(SystemStateCode.Shop_WaitInput)
        flow.step()
        assertEquals(
            emptyList(), rig.optionValues(),
            "来源换了 ⇒ 身份位变 ⇒ 旧页按钮作废（`:790-791` 的判据仍生效，接线没把世代冻住）",
        )
        assertEquals(listOf(0L, 100L), rig.buttonValues(), "同样只是世代级失配，不是删行")
    }

    /**
     * 取用次序钉：现算通道**有帧时优先**（读到非负就用它），来源函数通道只在它 -1 时顶上
     * （`ConsoleBeginHost.waitScriptLine`）。观察口 = 等待入口冻结的可点集合：两次等待之间把
     * 现算读数从 777 改成 888（= 脚本真的换行），旧按钮必须作废，哪怕来源仍登记着同一支函数。
     */
    @Test
    fun `取用次序 有帧读数优先于来源函数读数`() {
        var live = 777L
        val rig = HostRig(token = { live }, pageIdentity = pageIdentityResolver())
        rig.host.noteWaitPageProducer("SHOW_SHOP")
        val (first, second) = rig.twoShopWaits(0L, 1L)
        assertEquals(listOf(0L, 100L), first)
        assertEquals(listOf(0L, 100L), second, "现算读数不动 ⇒ 身份位不变 ⇒ 旧按钮保住")

        live = 888L // 脚本换行：来源函数通道仍是 SHOW_SHOP
        assertTrue(rig.input.submitInt(2L), "预喂第三次取值")
        val flow = BeginFlow(FakeCalls(), rig.host)
        flow.enterState(SystemStateCode.Shop_WaitInput)
        flow.step()
        assertEquals(emptyList(), rig.optionValues(), "现算读数优先 ⇒ 身份位变 ⇒ 旧按钮作废")
    }

    /** 记录「本页来源」登记的假体宿主：只覆写 [BeginHost.noteWaitPageProducer] 与商店输入。 */
    private class ProducerRecordingHost(
        var ablupInput: Long? = null,
        var temporaryLine: Boolean = false,
    ) : BeginHost {
        val producers = ArrayList<String>()
        override fun noteWaitPageProducer(functionKey: String) {
            producers += functionKey
        }

        override fun awaitAblupInput(): Long? = ablupInput
        override fun lastLineIsTemporary(): Boolean = temporaryLine
    }

    /**
     * 登记时机钉（跑过才算数）：`callScript` 只在被调方**真的存在**时登记来源 ——
     * 与原版 `ProcessState.currentLine` 同形（没执行行 ⇒ 字段不动）。
     * 反面对照：`endAutoSave` 里 `SHOW_SHOP`（`Process.cs:2600`）跑到了 ⇒ 登记。
     */
    @Test
    fun `缺函数支不登记来源_跑到函数的支才登记`() {
        val missing = ProducerRecordingHost(ablupInput = 50L)
        val missFlow = BeginFlow(FakeCalls(), missing) // 表里没有 ABLUP50
        missFlow.enterState(SystemStateCode.Ablup_WaitInput)
        missFlow.step()
        assertEquals(
            emptyList(), missing.producers,
            "Process.cs:2495 的 ABLUP{N} 缺失 ⇒ 一支脚本都没跑 ⇒ 身份位留在上一屏的来源（不登记）",
        )

        val ran = ProducerRecordingHost(temporaryLine = false)
        val calls = FakeCalls().apply { functions += "SHOW_SHOP" }
        val ranFlow = BeginFlow(calls, ran)
        ranFlow.enterState(SystemStateCode.Shop_CallEventBuy)
        ranFlow.step() // endCallEventBuy 非临时行支 → endAutoSave → SHOW_SHOP（Process.cs:2656/:2600）
        assertEquals(
            listOf("SHOW_SHOP"), ran.producers,
            "真的跑过函数 ⇒ 登记本页来源；called 流水 = ${calls.called}",
        )
    }

    /**
     * 判据的另一侧：脚本**真的走到另一行** ⇒ 身份变更 ⇒ 旧页按钮作废
     * （`EmueraConsole.cs:789-791`）。接线不把世代整块冻住 —— 这条钉的是「不许过修」。
     */
    @Test
    fun `脚本推进到另一行 第二次重等旧按钮照常作废`() {
        var next = 777L
        val rig = HostRig(token = { next++ }) // 每次等待换一个身份 = 脚本换行
        val (first, second) = rig.twoShopWaits(0L, 1L)
        assertEquals(listOf(0L, 100L), first)
        assertEquals(emptyList(), second, "换行 ⇒ lastButtonGeneration 前移（:790-791），旧按钮作废是原版权威")
        assertEquals(listOf(0L, 100L), rig.buttonValues(), "同样只是世代级失配，不是删行")
    }

    // ------------------------------------------------------------------
    // C 组：存读档问句换道（Process.cs:2687 / :2694 的 PrintSingleLine）
    // ------------------------------------------------------------------

    /** 分道记录假体：writeLine / printSingleLine 各记一份，钉「调用点走的是哪条通道」。 */
    private class ChannelHost : BeginHost {
        val written = ArrayList<String>()
        val singleLines = ArrayList<String>()
        val slotScreenCalls = ArrayList<Boolean>()

        override fun writeLine(text: String) {
            written += text
        }

        override fun printSingleLine(text: String) {
            singleLines += text
        }

        override fun printSaveDataSlots(isSave: Boolean) {
            slotScreenCalls += isSave
        }
    }

    /** 只覆写 writeLine 的假体（SaveLoadStateTakeoverTest 的 RecordingHost 同形）。 */
    private class WriteLineOnlyHost : BeginHost {
        val written = ArrayList<String>()

        override fun writeLine(text: String) {
            written += text
        }
    }

    /**
     * 换道钉：`beginSaveGame` / `beginLoadGame` 的问句走 [BeginHost.printSingleLine]
     * （原版 `console.PrintSingleLine`，`Process.cs:2687` / `:2694`），不再走 writeLine。
     */
    @Test
    fun `存读档问句走 printSingleLine 通道`() {
        val host = ChannelHost()
        val save = BeginFlow(FakeCalls(), host)
        save.enterState(SystemStateCode.SaveGame_Begin)
        val saveStep = save.step()
        val load = BeginFlow(FakeCalls(), host)
        load.enterState(SystemStateCode.LoadGame_Begin)
        load.step()

        assertEquals(
            listOf("保存至几号存档？", "读取几号存档？"),
            host.singleLines,
            "Process.cs:2687/:2694 PrintSingleLine（Lang.cs:4334 一族；zhs 译文 xml:1024/:1025）",
        )
        assertTrue(host.written.isEmpty(), "换道后问句不再经 writeLine 落普通行")
        assertEquals(listOf(true, false), host.slotScreenCalls, "同一步的槽位半边照常交宿主（:2689/:2696）")
        assertEquals(StepOutcome.AWAIT_INPUT, saveStep.outcome, "setWaitInput（:2802）不变")
    }

    /**
     * 缺省降级钉：[BeginHost.printSingleLine] 没被覆写时转 writeLine —— 未接线的宿主
     * （含 `BeginHost.NONE`、各测试假体）读数与换道前**逐字一致**（纪律照 printError）。
     * 这条同时解释 [SaveLoadStateTakeoverTest] 的两处问句钉为什么不需要改。
     */
    @Test
    fun `printSingleLine 缺省降级为 writeLine 未覆写宿主行为不变`() {
        val host = WriteLineOnlyHost()
        val flow = BeginFlow(FakeCalls(), host)
        flow.enterState(SystemStateCode.SaveGame_Begin)
        flow.step()

        assertEquals(
            listOf("保存至几号存档？"), host.written,
            "缺省降级 = writeLine：SaveLoadStateTakeoverTest 的 RecordingHost 读数因此不变",
        )
    }

    /**
     * 真宿主 + 真 sink：上一句 PRINTN 残段未结时，问句**自己一条不折行的显示行**
     * （`EmueraConsole.cs:3155-3158` → `PrintStringBuffer.cs:170-177` 的 `FlushSingleLine`），
     * 换道前的 writeLine 路（`console.printLine` = `Print` + `NewLine`）把问句与残段并进
     * **同一段缓冲**再按宽度折行（`EmueraConsole.cs:3167-3172` + `:3375-3383`）。
     */
    @Test
    fun `真宿主 问句独立单行不与未结残段同段折行`() {
        val rig = HostRig(token = { 777L }, clientWidth = 20)
        rig.console.print("上一句未结的残段", lineEnd = false) // PRINTN 形态：缓冲里挂着
        val flow = BeginFlow(FakeCalls(), rig.host)
        flow.enterState(SystemStateCode.SaveGame_Begin)
        flow.step() // beginSaveGame → host.printSingleLine(问句)
        val after = rig.lineTexts()

        val control = HostRig(token = { 777L }, clientWidth = 20)
        control.console.print("上一句未结的残段", lineEnd = false)
        control.host.writeLine("保存至几号存档？") // 换道前的路
        val before = control.lineTexts()

        assertEquals(
            listOf("上一句未结的残段保存至几号存档？"), after,
            "换道后：残段先被 PrintFlush 结掉，问句按 FlushSingleLine 排成不折行的一条并入行（原版同形）",
        )
        assertTrue(
            before.size > 1,
            "换道前：问句与残段并进同一段再折行，被拆成多排显示行；实际=$before",
        )
    }

    // ------------------------------------------------------------------
    // 装配源码读取（A 组的接线钉用）
    // ------------------------------------------------------------------

    /** 读本仓 main 源码（测试工作目录 = 模块目录 `core-vm`，与 Gradle 的 test workingDir 同口径）。 */
    private fun readMainSource(fileName: String): String {
        val candidates = listOf(
            Path.of("src/main/kotlin/com/eratw/port/core/proc", fileName),
            Path.of("core-vm/src/main/kotlin/com/eratw/port/core/proc", fileName),
        )
        val path = candidates.firstOrNull { Files.isReadable(it) }
            ?: throw IllegalStateException("读不到 $fileName 的源码；试过 $candidates")
        return Files.readString(path)
    }

    /** 抠 `const val <name> = "…"` 的字面量（③ 的两侧槽名同串核对用）。 */
    private fun constString(src: String, name: String): String {
        val m = Regex("""const val $name = "([^"]*)"""").find(src)
            ?: throw IllegalStateException("源码里找不到字符串常量 $name")
        return m.groupValues[1]
    }
}
