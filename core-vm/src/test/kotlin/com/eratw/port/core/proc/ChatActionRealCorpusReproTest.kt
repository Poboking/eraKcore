package com.eratw.port.core.proc

import com.eratw.port.boot.GameRoot981
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.fn.NativeParam
import com.eratw.port.core.fn.nativeMethods
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 簇 9bcc9efd「No native method ModelSet found（CHAT_ACTION 行34）」的**真语料端到端钉**。
 *
 * ## 与既有两钉的分工
 *
 * - [AiChatSharpStubTest]：把语料形状**手抄**进夹具跑 flat 生产链（能改切片、跑得快，
 *   但钉的是抄本，不是 `对话调用.erb` 本体）。
 * - [CallSharpCorpusCensusTest]：扫真语料的逻辑行，钉「可达原生名只有 ModelSet 与 Chat」
 *   与「抛点行号还是上报那一条」，但**不执行**。
 * - 本钉：把真文件的 `@CHAT_ACTION` 事件本体逐行喂进真装载链（[LogicalLineReader] →
 *   [LineParser] → [ErbProgramCompiler] → [ProcessRuntime]）跑到底，变量声明也吃**真语料的
 *   `#DIM` 原文**（`AI返回参数.ERH` 12 行 + 事件头 5 行 `#DIMS DYNAMIC` + `DIM.ERH:701`
 *   的 `#DIM CONST C_RED`）。上报的三要素——文案、事件名、行号——全部由真语料本身产出，
 *   不是抄本：
 *   * 无桩一侧 = 收口前（0.2.39 / 构建 4899130）真机形态：`CodeEE.message`
 *     逐字 = `No native method ModelSet found`，`funcName` = `CHAT_ACTION`，`line` = 34
 *     （0 基逻辑行号，= 物理 `对话调用.erb:35`，与上报「行34」同口径）。
 *   * 有桩一侧 = 收口后（d06d55b2 的宿主桩语义）：整段事件跑完不抛，降级提示经
 *     `CALLSHARP Chat` 的变量实参回写（spec: FunctionIdentifier.cs:1219-1231）落到
 *     `返回对话`，并由真语料的 `PRINTFORML %返回对话%`（物理 :38）打在屏上。
 *     **这一侧同时是本钉抓出的第二发缺陷面**：d06d55b2 装上桩后真语料仍然炸在同一句 ——
 *     `ModelSet` 的三个实参在 `AI返回参数.ERH:6-8` 是 `#DIMS CONST`，官方回写环对常量
 *     不豁免（FunctionIdentifier.cs:1219-1231 → VariableTerm.cs:108-152 → VariableData.cs:1617-1621
 *     无常量闸），而移植侧存储层有闸（UserVariables.kt:1925/:1957）⇒ 报错只从
 *     「No native method ModelSet found」换成「变量 模型API 是常量，不能赋值」。
 *     手抄夹具的 [AiChatSharpStubTest] 把这三个量建成了**非 const** 用户变量，因此照不到；
 *     修点 = 存储层常量闸的相位（B5 已删，见 `StatementExecutor.writeBackNativeArg` 的 KDoc：
 *     官方存储层无常量闸、拦截在解析期）。
 *     上报的抛点行号在上报构建（0.2.39 = 4899130-dirty，早于 d06d55b2）里就是「No native method」
 *     那一发；本钉按 **0 基逻辑行号** 断言 `CodeEE.line == 34`（= 物理 :35），若后续波次把
 *     上报行号换成 1 基口径，`REPORTED_LINE` 要跟着挪。
 *
 * ## 两处有意简化（越界处不假装）
 *
 * 1. **被调的两个口上函数用最小桩**：`@GET_CURRENT_ACTION_INFO(CHARA)`（真定义 = 同文件
 *    物理 :68，体内吃 TALENT/口上全局族）与 `@SET_KOJO_COLOR(ARG = -1)`（真定义 =
 *    `ERB/口上・メッセージ関連/COMMON_KOJO.ERB:55`，本钉按零参建桩，语料调用点 `:37`
 *    正好不带实参）。两者的本体都不是本簇的报错面（抛点是 `CALLSHARP` 那两句），
 *    把它们接真就要拖进整库符号表；故只按真签名建空函数体。
 * 2. **事件头的 `#DIMS DYNAMIC` 剥掉修饰符后按全局登记**：真链里它们是函数私有动态量（spec:
 *    `core/compile/LocalLayout.kt:75`，走 erbcc 侧车 `buildFuncDims`），本钉没有侧车。
 *    直接喂原文给 [SystemVariableBootstrap.install] 会被维表解析器拒绝（本钉实测读数：
 *    五条全部报「DYNAMIC 只能用于函数内的变量声明」），故只剥 `DYNAMIC ` 一个词，
 *    名字/类型/维度逐字保留。对本簇的判据无影响 —— `CALLSHARP` 的实参是**按名解析的
 *    变量项**，私有与全局走的回写通道是同一条（`StatementExecutor` 的 `writeVarRefStr`），
 *    而本钉要钉的那一发在**常量闸**上，常量位是真语料的 `#DIMS CONST`，不是 DYNAMIC。
 *
 * 语料缺失（`ERATW_CORPUS` / `ERATW_GAMEDIR` 没指到 4.981）时 `assumeTrue` 可见跳过 ——
 * 口径同 [CallSharpCorpusCensusTest]，禁止让「没跑」看起来像「跑绿」。
 */
class ChatActionRealCorpusReproTest {

    private companion object {
        /** 上报抛点所在文件（4.981 语料，UTF-8）。 */
        const val ERB_REL = "DLC/AI对话补丁/对话调用.erb"

        /** 事件用到的全局量的真声明文件（模型API / 模型Key / 模型名称 等 12 行）。 */
        const val PARAM_ERH_REL = "DLC/AI对话补丁/AI返回参数.ERH"

        /** `#DIM CONST C_RED = 0xFF0000` 的真声明文件（实测在 :701）。 */
        const val DIM_ERH_REL = "DIM.ERH"

        /** 上报原文（app 版本 0.2.39 / 构建 4899130-dirty 的两份正文逐字相同）。 */
        const val REPORTED_MESSAGE = "No native method ModelSet found"
        const val REPORTED_EVENT = "CHAT_ACTION"
        const val REPORTED_LINE = 34

        /** 宿主桩的降级文案 = app `ShellFailureCopy.kt:263` 与 [AiChatSharpStubTest] 同源。 */
        const val AI_REPLY_UNAVAILABLE =
            "（AI对话功能在本版不可用：移植版未内置AI插件，无法调用模型接口）"
    }

    // ------------------------------------------------------------------
    // 真语料读取
    // ------------------------------------------------------------------

    /** 真文件的逻辑行序列（走真装载链的预处理器：`[IF_DEBUG]` / `[SKIPSTART]` 段不产行）。 */
    private fun realLogicalLines(erbRoot: Path, relative: String): List<LogicalLine> {
        val file = erbRoot.resolve(relative)
        assertTrue(Files.isRegularFile(file), "真语料文件缺失：$file")
        val bytes = Files.readAllBytes(file)
        val physical = LogicalLineReader.readAllLines(bytes)
        val reader = LogicalLineReader(physical, file.fileName.toString())
        val out = ArrayList<LogicalLine>()
        while (true) {
            val line = reader.readLogicalLine() ?: break
            out.add(line)
        }
        reader.fileEnd()
        return out
    }

    /** `@CHAT_ACTION` 事件本体 = 从它的 `@` 行到下一个 `@` 行（不含）。 */
    private fun chatActionEventLines(all: List<LogicalLine>): List<LogicalLine> {
        val start = all.indexOfFirst {
            it.kind == LineKind.FUNCTION_LABEL && it.content.trim() == "@$REPORTED_EVENT"
        }
        assertTrue(start >= 0, "真语料里找不到 @$REPORTED_EVENT 事件定义")
        val end = (start + 1 until all.size).firstOrNull { all[it].kind == LineKind.FUNCTION_LABEL }
            ?: all.size
        return all.subList(start, end)
    }

    /** 真 `#DIM` 原文行（ERH 全文去掉空行/注释行；`install` 自己剥 `;` 行内注释）。 */
    private fun realDimLines(root: Path, relative: String): List<Pair<String, Int>> {
        val file = root.resolve(relative)
        assertTrue(Files.isRegularFile(file), "真语料声明文件缺失：$file")
        return Files.readAllLines(file, Charsets.UTF_8).mapIndexed { i, text -> text.trim() to i + 1 }
            .filter { (text, _) -> text.startsWith("#DIM") }
    }

    /** `DIM.ERH` 里真声明的 `#DIM CONST C_RED`（本钉只要 C_RED 一条，SETCOLOR 用）。 */
    private fun redConstLine(root: Path): Pair<String, Int> {
        val hit = realDimLines(root, DIM_ERH_REL).firstOrNull { it.first.contains("C_RED") }
        return requireNotNull(hit) { "DIM.ERH 里没有 #DIM CONST C_RED 这一条" }
    }

    // ------------------------------------------------------------------
    // 编译 / 运行
    // ------------------------------------------------------------------

    /**
     * 解析侧符号表：语料全局量按 **dimension = 1** 登记 —— 移植侧 `#DIM` / `#DIMS` 落到
     * 变量池都是「1 维、1 格」（本钉实测读数：按 0 下标读 `模型API` 报「变量 模型API 需要
     * 1 个下标（实际 0）」），脚本侧写 `模型API` 不带下标时由表达式层补隐式 0
     * （spec: `core/expr/Parser.kt:592`/`:627` 的 `dimension == 1` 分支）。
     */
    private fun symbolTable(): SymbolTable = SymbolTable()
        // 事件体里出现的语料全局量（真名 + 真类型 + **真 const 位**；类型与 const 来源 =
        // AI返回参数.ERH:2/:6-8/:9-11 的 #DIM/#DIMS 原文，`#DIMS CONST` 的三个模型参数与
        // DIM.ERH:701 的 `#DIM CONST C_RED` 都带 CONST ⇒ isConst = true，与生产侧 erbcc
        // 从维表烤进 ERBC 的 flags bit2（ErbcWriter.kt:781 / ErbcReader.kt:972）同口径。）
        .declareVariable("AI对话返回信息", OperandType.STR, 1)
        .declareVariable("模型API", OperandType.STR, 1, isConst = true)
        .declareVariable("模型Key", OperandType.STR, 1, isConst = true)
        .declareVariable("模型名称", OperandType.STR, 1, isConst = true)
        .declareVariable("COUNTER输出信息", OperandType.STR, 1)
        .declareVariable("口上输出信息", OperandType.STR, 1)
        .declareVariable("AI对话开关", OperandType.INT, 1)
        .declareVariable("C_RED", OperandType.INT, 1, isConst = true)
        // 事件头的 #DIMS DYNAMIC 在本钉按全局登记（理由见类文档「有意简化」第 2 条）
        .declareVariable("返回对话", OperandType.STR, 1)

    /** 真事件行 → 语句：只喂 CODE 与 `@` 标签行，`#` 行走 [SystemVariableBootstrap] 登记。 */
    private fun realStatements(eventLines: List<LogicalLine>): List<Statement> {
        val parser = LineParser(symbolTable())
        return eventLines
            .filter { it.kind == LineKind.CODE || it.kind == LineKind.FUNCTION_LABEL }
            .mapNotNull { parser.parseLogicalLine(it) }
    }

    /** 最小桩函数体（手抄文本，非语料；见类文档「有意简化」第 1 条）。 */
    private fun stubStatements(vararg lines: String): List<Statement> {
        val parser = LineParser(SymbolTable().declareVariable("CHARA", OperandType.INT, 0))
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            parser.parseLogicalLine(LogicalLine(kind, "stub.erb", i + 1, text))
        }.filterNotNull()
    }

    private fun compile(eventLines: List<LogicalLine>): Program {
        val c = ErbProgramCompiler()
        // 真签名：`CALL GET_CURRENT_ACTION_INFO(TARGET)`（对话调用.erb:27）与
        // `CALL SET_KOJO_COLOR`（:37）——本体是主库/同文件的口上函数，本钉只占位。
        c.compileFunction(
            FuncSig("GET_CURRENT_ACTION_INFO", 1),
            stubStatements("@GET_CURRENT_ACTION_INFO(CHARA)", "RETURN"),
            setOf("CHAT_ACTION", "GET_CURRENT_ACTION_INFO", "SET_KOJO_COLOR"),
            ExprCallTable(),
        )
        c.compileFunction(
            FuncSig("SET_KOJO_COLOR", 0),
            stubStatements("@SET_KOJO_COLOR", "RETURN"),
            setOf("CHAT_ACTION", "GET_CURRENT_ACTION_INFO", "SET_KOJO_COLOR"),
            ExprCallTable(),
        )
        c.compileFunction(
            FuncSig(REPORTED_EVENT, 0),
            realStatements(eventLines),
            setOf("CHAT_ACTION", "GET_CURRENT_ACTION_INFO", "SET_KOJO_COLOR"),
            ExprCallTable(),
        )
        return c.build(REPORTED_EVENT)
    }

    /** 真语料声明登记进池子：ERH 12 行 + 事件头 5 行动态量 + C_RED。 */
    private fun installCorpusDeclarations(store: VariableStore, root: Path, eventLines: List<LogicalLine>) {
        val decls = ArrayList<SystemVariableBootstrap.RawDeclaration>()
        realDimLines(root, PARAM_ERH_REL).forEach { (raw, line) ->
            decls.add(SystemVariableBootstrap.RawDeclaration(raw, PARAM_ERH_REL, line))
        }
        // 事件头的 `#DIMS DYNAMIC …`：DYNAMIC 是**函数内**专属修饰（本钉实测读数 =
        // 移植侧 DimParser 报「DYNAMIC 只能用于函数内的变量声明」），全局登记必须剥掉它，
        // 名字/类型/维度逐字保留。理由见类文档「有意简化」第 2 条。
        eventLines.filter { it.kind == LineKind.SHARP }.forEach { ll ->
            val raw = ll.content.trim().replace("DYNAMIC ", "")
            decls.add(SystemVariableBootstrap.RawDeclaration(raw, ERB_REL, ll.lineNo + 1))
        }
        val red = redConstLine(root)
        decls.add(SystemVariableBootstrap.RawDeclaration(red.first, DIM_ERH_REL, red.second))
        val result = SystemVariableBootstrap.install(store, decls)
        assertEquals(emptyList(), result.failures, "真语料 #DIM 原文必须全部登记成功：${result.failures}")
        assertTrue(result.declared >= 12, "登记条数异常（实得 ${result.declared}）")
    }

    /** app `GameHost.wireCorpusNativeMethods`（:2197-2209）的逐字复刻。 */
    private fun registerHostStubs(store: VariableStore) {
        val registry = nativeMethods(store.aux)
        registry.register("ModelSet") { _: Array<NativeParam> -> }
        registry.register("Chat") { params: Array<NativeParam> ->
            for (p in params) {
                p.intValue = 0
                p.strValue = AI_REPLY_UNAVAILABLE
            }
        }
    }

    private fun runtime(store: VariableStore, program: Program, console: LineBufferConsoleSink) =
        ProcessRuntime(
            program,
            store,
            FunctionTable(emptyList()),
            VmConfig(maxStepsPerRun = 100_000),
            consoleSink = console,
        )

    private fun freshStore(root: Path, eventLines: List<LogicalLine>): VariableStore {
        val store = VariableStore()
        store.characters.addBare()
        installCorpusDeclarations(store, root, eventLines)
        return store
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    fun `真语料 CHAT_ACTION 跑到行34 撞上报原文（收口前形态）`() {
        val root = GameRoot981.erb()
        Assumptions.assumeTrue(root != null, "缺 4.981 语料根（ERATW_GAMEDIR / ERATW_CORPUS 都没指到 4.981）：跳过")
        val eventLines = chatActionEventLines(realLogicalLines(root!!, ERB_REL))
        // 抛点自证：事件里第 34 号逻辑行就是上报的那一句（0 基，= 物理 :35）。
        val throwLine = eventLines.firstOrNull { it.lineNo == REPORTED_LINE }
        assertTrue(
            throwLine != null && throwLine.content.startsWith("CALLSHARP ModelSet("),
            "行号口径漂移：${eventLines.map { "${it.lineNo}:${it.content}" }}",
        )

        val store = freshStore(root, eventLines)
        // 真语料里 模型API / 模型Key / 模型名称 是 ERH 的 CONST，出厂即非空
        // ⇒ `SIF 模型API == ""`（物理 :32-33）为假，直落 CALLSHARP，与上报现场一致。
        // （`#DIMS` 在本池是「1 维字符串数组」，按名读必须显式给 0 号下标 —— 脚本侧的
        // `模型API` 由表达式层补隐式 0，见 core/expr/Parser.kt 的 int1D/str1D 口径。）
        assertTrue(store.readStr("模型API", longArrayOf(0)).isNotEmpty(), "CONST 初值没吃到：语料口径变了")
        val rt = runtime(store, compile(eventLines), LineBufferConsoleSink(136, 25, 900))
        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals(REPORTED_MESSAGE, e.message, "上报文案必须由真语料 + 真链原样产出")
        assertEquals(REPORTED_EVENT, e.funcName, "事件名")
        assertEquals(REPORTED_LINE, e.line, "行号（0 基逻辑行，与上报「行34」同口径）")
    }

    @Test
    fun `宿主桩语义下真语料 CHAT_ACTION 整段跑完且提示上屏`() {
        val root = GameRoot981.erb()
        Assumptions.assumeTrue(root != null, "缺 4.981 语料根（ERATW_GAMEDIR / ERATW_CORPUS 都没指到 4.981）：跳过")
        val eventLines = chatActionEventLines(realLogicalLines(root!!, ERB_REL))
        val store = freshStore(root, eventLines)
        registerHostStubs(store)
        // 本钉的第二发缺陷面（d06d55b2 只修了一半）：`CALLSHARP ModelSet(模型API, 模型Key, 模型名称)`
        // 的三个实参在真语料里是 `#DIMS CONST`（AI返回参数.ERH:6-8），而官方回写环对常量不豁免
        // （spec: FunctionIdentifier.cs:1219-1231 → VariableTerm.cs:108-152 → VariableData.cs:1617-1621
        // 移植侧存储层曾有常量闸（UserVariables.kt 的 StaticInt/StaticStrUserVar 写口），照抄回写会
        // 当场抛「变量 模型API 是常量，不能赋值」⇒ 宿主桩装上后补丁页照样在每次行动结束炸。
        // B5（2026-11-07）删掉了那道闸（官方存储层本就无常量闸，拦截在解析期）：现在回写照官方逐字执行，
        // 本用例的三槽因此由「跳过的常量位」改为「宿主方法本体不改常量槽」保持原值 ——
        // 常量位可写这件事本身由 `CallSharpConstWriteBackTest` 用故意改写的本地方法钉住。
        val modelApiBefore = store.readStr("模型API", longArrayOf(0))
        assertTrue(modelApiBefore.isNotEmpty(), "CONST 初值没吃到：语料口径变了")
        val console = LineBufferConsoleSink(136, 25, 900)
        runtime(store, compile(eventLines), console).execute()

        // 回写通道：Chat 的出参落进 返回对话（真事件头 #DIMS DYNAMIC 登记的那一条）。
        assertEquals(AI_REPLY_UNAVAILABLE, store.readStr("返回对话", longArrayOf(0)))
        assertEquals(modelApiBefore, store.readStr("模型API", longArrayOf(0)), "ModelSet 本体不改这三个常量槽 ⇒ 回写后原值")
        // 屏面：真语料的 PRINTFORML %返回对话%（物理 :38）把提示打出来 —— 玩家看得见的收口。
        val printed = console.lines().map { console.textOf(it) }
        assertTrue(
            printed.any { AI_REPLY_UNAVAILABLE in it },
            "降级提示没上屏，屏面：$printed",
        )
    }
}
