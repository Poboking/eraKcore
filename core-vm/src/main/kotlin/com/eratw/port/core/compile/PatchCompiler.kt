package com.eratw.port.core.compile

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.frontend.ErbPreprocessor
import com.eratw.port.core.frontend.Symbols
import com.eratw.port.core.frontend.sizeConsts
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.patch.FunctionMetaRecord
import com.eratw.port.core.patch.PatchArtifacts
import com.eratw.port.core.patch.PatchHeader
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.stmt.DefinitionScopedResolver
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser

/**
 * 设备端补丁编译器（R7-C1，CONTRACT-R7-C §2/C1）：把玩家导入的口上 `.ERB` 编译成
 * **绝对下标**的补丁 [Program]，再经既有 [com.eratw.port.core.patch.PatchArtifacts] 落盘。
 *
 * ## 符号上下文 = 侧车（禁重扫语料）
 *
 * 桌面全量编译的符号表来自扫描 3,971 个文件（CONTRACT-R7.md §1.5）；补丁编译发生在
 * 玩家手机的导入动作里，**不允许**碰语料。全部编译期符号来自 [metadata]（侧车
 * `BootMetadata`）+ base 基线计数（[BaseCounts]）：
 *
 * | 编译期需要 | 来源 | 缺失时的行为 |
 * |---|---|---|
 * | base 函数名 → 绝对下标（`_CALL` 目标） | `functions` 段 | 未知名 → 编译期拒绝（I6 同族） |
 * | base 方法返回类型三分 | `method-types` 段（[BootMetadata.knownMethodTypes]） | 未知类型 → 保持节点不降级（保守明确失败） |
 * | base 普通函数名（表达式位调用 = UsedNonMethodFunc） | `functions` 段排除方法 | — |
 * | base 函数的 REF 私有名 | `private-dims` 段 | 无 REF 信息 → 按值传参（旧行为，明确失败方向） |
 * | 形参默认值（编译期补位） | `param-defaults` 段 | **空段 + 省略实参 → 编译期拒绝**（不变量 I6，见 [compile] 步骤 9） |
 * | 宏 / 已求值常量 | `dim-config` / `constants` 段（[com.eratw.port.core.frontend.sizeConsts]） | 常量折不出 → 该 `#DIM` 声明失败方向 |
 * | 正文 `#DEFINE` 宏引用的词法展开（R7-D2 TODO①） | `dim-config` 段宏表 → [LexerConfig.macros]（与 erbcc 全量同源：同一份配置喂 LineParser 与 LogicalLineReader，CorpusCompile.kt:313-320 同款） | 未定义名 → 解析失败方向（不静默当 0） |
 * | `#LOCALSIZE` 折常量（T5） | 同上，喂 [LineParser] 的 `dimConsts` | 缺表 → 告警不静默折 0 |
 * | `#LOCALSIZE`/`#LOCALSSIZE` → FuncMeta | 切片遍 [SharpStmt] 采集（R7-D2 TODO②） | 折不出 → 保持 0（该行不生效口径） |
 *
 * ## 基线预置（波 A A1「独立 builder 续编」模式）
 *
 * [BaseCounts] 的六个计数直接落进 [CodeBuilder] 的六个基线参数：补丁的 pc / 池下标 /
 * 函数下标从 `base + 新增` 起算，装载期零改写。`_CALL` 到 base 函数经
 * [ErbProgramCompiler] 的 `externalFunctionIndex` 回调解析（查找顺序「先内后外」，见其
 * KDoc）。出口判据 1（A1 同尺对拍）：同一批 ERB，「全量编译 A+B」与「全量编译 A 后用
 * 本编译器以 A 的产物计数 + 侧车为上下文续编 B」的补丁段指令/池条目**逐字节一致**
 * （[com.eratw.port.core.compile.PatchCompilerTest] 钉住）。
 *
 * ## I6 编译期拒绝（CONTRACT-R7.md §1.6，全部带函数名 + 文件:行）
 *
 * 1. 补丁声明全局 `#DIM`（函数外 `#DIM`/`#DIMS`，或函数体内的 GLOBAL/SAVEDATA/CHARADATA
 *    全局侧声明）——`Program.globalSlots` 构造期定死，扩全局槽牵动存档列。
 * 2. 补丁定义 base 已有同名**非事件**函数——覆盖是 override 语义，另立项（铁律：先查再拒，
 *    不静默遮蔽）。事件名（[Symbols.isEventLabelName]）放行：同名事件函数是叠加语义
 *    （见「事件函数成员」节）。
 * 3. 空侧车（`param-defaults` 段缺失）+ 补丁调用 base 函数省略实参——PC 全量编译会
 *    补位而补丁不会，同一行 ERB 两种行为是「静默错值」最典型的形态。表达式短路位
 *    （`&&` 右侧等）与返回类型未知的严格位保持 FuncCall 节点、实参省略不经过 callArgs，
 *    由 [ErbProgramCompiler] 的 keptUserCallSink 上报后在步骤 12 做同款判据（R7-D2 TODO③）。
 *
 * ## 包内同名重复定义 = 后定义者生效（wave/mod-import-event-members）
 *
 * 原版对同名**非事件**函数是「后装载者留下」：`noneventLabelDic[labelName] = label`
 * 直接赋值（spec: LabelDictionary.cs:60-68），装载期仅按配置告警不拒绝
 * （spec: ErbLoader.cs:443-450，WarnNormalFunctionOverloading 出厂默认关，
 * spec: ConfigData.cs:94）。社区口上包普遍依赖该语义做「同函数多版本、后文件盖前文件」，
 * 补丁预扫因此把同键重复定义从拒绝改为**后到者覆盖**（步骤 3）+ 一条 parseWarnings
 * 告警（level 1 语义，不拒绝）；被覆盖定义的私有 `#DIM` 随其 FuncHead 一并废弃
 * （私有 DIM 解析在全部源预扫完后只对胜者定义做，见步骤 3.5），切片阶段跳过非胜者
 * 函数体（见 [slice]）。事件名的重复定义在本编译器里同键覆盖（最后成员胜出）——
 * 原版事件名是多成员并存（spec: LabelDictionary.cs:70-104 四桶追加），差异登记在
 * 类尾 TODO(verify) 4。
 *
 * ## 事件函数成员与补丁合成事件入口（wave/mod-import-event-members）
 *
 * 社区口上的标准挂钩方式就是给 `@EVENTLOAD`/`@EVENTTRAIN` 等事件名添加成员：原版语义是
 * **同名事件函数全部执行**（CALLEVENT 派发按 ONLY → PRI → NORMAL → LATER 四桶逐成员推进，
 * 同桶按装载序，spec: CalledFunction.cs:228-243 ShiftNext）。补丁由此放开事件成员
 * （原 I6-① 撤销），并为每个「补丁定义了成员的」事件名追加一个**补丁合成事件入口**
 * （步骤 11.5）：
 *
 * - **Program 命名**：事件成员编成 `<事件名>#<绝对函数下标>`（与 erbcc 全量链路的成员
 *   命名形状同源，CorpusCompile.kt:474），事件名本身留给合成入口——[CodeBuilder] 对同名
 *   declareFunction 有 require 闸，成员与入口不能同键登记。
 * - **入口体**：先 `_CALL` base 同名合成入口（下标 = externalFunctionIndex(事件名)；
 *   base 侧车对事件 key 是「成员 + 合成入口」多条同键记录，最后一条 = 入口——全量链路
 *   把入口 meta 追加在成员之后，CorpusCompile 的登记序），再依装载序 `_CALL` 补丁成员，
 *   `_RET` 收尾。base 缺该事件名（externalFunctionIndex = -1）时入口只调补丁成员。
 *   这是 [ErbProgramCompiler.compileEventEntry] 的内联等价形：它经 `b.functionIndex`
 *   只能解析补丁内已编译名，base 入口的绝对下标无法经 orderedBodies 表达，故在
 *   PatchCompiler 直接发射；入口无 callRefs/标签，不经 FunctionBytecode 登记
 *   （指令已进补丁 [Program]，build 的 callRef 回填与不变量 I2 自检不受影响）。
 * - **装载后语义**：nonEventByKey[事件名] = 补丁入口（后装载者覆盖 base 入口，
 *   FuncMeta.kt:469-472）；eventMembersByKey[事件名] = base 成员 + 补丁成员（
 *   FuncMeta.kt:465-468，成员 meta isEvent = true、defIndex 续接 base ⇒ NORMAL 桶
 *   装载序追加在全部 base 成员之后）。base 行为逐位保留：base 成员链经 base 入口
 *   原样执行。TODO(verify)：装载路（GameSession.buildPatchFunctionMetas）当前按
 *   Program 函数名重建补丁 FuncMeta 且事件位恒 false——事件成员要落进
 *   eventMembersByKey 需该重建面同步识别事件名（组长接线，见交付报告）。
 * - **I6-② 同名闸**对事件名放行（步骤 5）；`#DIM` 全局闸等预扫逻辑对事件成员照常适用。
 *
 * ## v1 边界（TODO(verify) 全部登记在类尾）
 *
 * 解析失败的**函数级**作废已复刻（原版逐 label 捕获的粒度，切片步逐函数续切），
 * **行级**续装仍未（类尾 TODO(verify) 3）。词法层 `#DEFINE` 宏展开
 * （R7-D2 TODO①）与
 * `#LOCALSIZE`/`#LOCALSSIZE` 的 FuncMeta 透传（R7-D2 TODO②）已接：宏展开走
 * [LexerConfig.macros] 喂 core/lexer 的 expandMacros 单源通道（与 erbcc 全量同源）。
 *
 * @property metadata 侧车符号上下文（base 的函数表 / 私有 DIM / 宏 / 常量 /
 *   形参默认值 / 方法类型）。
 * @property symbolTable 语句层解析的符号表基座（[SymbolTable]：调用方可预灌 CSV 用户
 *   变量与 base 用户方法名；内建系统变量经 [com.eratw.port.core.stmt.BuiltinSymbols]
 *   兜底）。导入链（C5）用 GameDataLoader 灌 CSV 全集后传入。
 */
class PatchCompiler(
    val metadata: BootMetadata,
    val baseCounts: BaseCounts,
    val symbolTable: SymbolTable = SymbolTable(),
    /**
     * `[[替换名]]` 字典（键 = `[[原名]]` 整串，值 = 新名；与 erbcc 全量
     * `CorpusCompile.loadRenameDic`（CSV/_Rename.csv）同一份数据源，由调用方注入）。
     *
     * 存在理由：口上包大量写 `CASE [[比基尼套装]]` 这种改名形态，替换在**读行层**完成
     * （[com.eratw.port.core.lexer.LogicalLineReader] 的 renameDic，spec:
     * EraStreamReader.cs:106-119）；不喂字典时 `[[` 会一路漏到词法器，撞
     * LexicalAnalyzer.cs:809-813 的 `RenameDic == null` 分支（「字句解析中に予期しない
     * 文字'[['」）整包解析失败。缺省 null = 旧行为逐字节不变。
     */
    val renameDic: Map<String, String>? = null,
) {

    /** 补丁源文件（相对路径用于报错定位与 `position` 字段；文本按 UTF-8 解码后的形态）。 */
    data class SourceFile(val relPath: String, val text: String)

    /**
     * base 产物的基线计数（[Program] 的六个绝对口径读数；导入链从已装载的 base
     * [Program] 直接取，与补丁头 `base-*` 字段同源）。
     */
    data class BaseCounts(
        val instructionCount: Int,
        val functionCount: Int,
        val statementCount: Int,
        val stringCount: Int,
        val longCount: Int,
        val exprCount: Int,
    ) {
        /** base 指令流的字数（[CodeBuilder.codeBase] 的单位是字，不是指令条数）。 */
        val codeWords: Int get() = instructionCount * Instruction.WORDS
    }

    /** 补丁编译失败（I6 四闸 / 解析失败 / 链接失败）。消息带函数名 + 文件:行（不变量 I7）。 */
    class PatchCompileException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    /**
     * 编译结果：带基线预置的补丁 [Program] + 装配面（C4 会话接线）需要的全部追加数据。
     *
     * @property program 补丁段（段内本体只含新增条目，六个基线如实暴露）。
     * @property functions 补丁函数的编译产物（按编译序 = 函数表序；合成事件入口不经
     *   FunctionBytecode 登记——无 callRefs/标签，指令已进 [program]，见类 KDoc「事件
     *   函数成员」节 ⇒ 本表条目数可以小于 program.functions 的补丁段函数数）。
     * @property funcMetas 补丁函数的运行期元数据（绝对下标从 [BaseCounts.functionCount]
     *   起算；事件成员 isEvent = true、键 = 裸事件名；合成事件入口 meta 追加在成员之后；
     *   C4 的 `FunctionTable(metadata.functions + patch)` 直接尾段追加）。
     * @property privateDims 补丁函数内 `#DIM` 的解析结果（C4 的 `metadata.privateDims`
     *   追加面；键 = 补丁函数键）。
     * @property sizeConsts 本轮实际使用的尺寸常量表（宏折叠 + 侧车常量；诊断用）。
     * @property parseWarnings 解析期非致命告警（level 1 语义；不参与成败判定）。
     * @property entryFunction **实际生效**的入口名（定位用信息，无运行语义）：调用方给的
     *   `entryFunction` 若不在编译出的函数里（整段被 `[SKIPSTART]`/`[IF 宏]` 包住、或导入链
     *   的预扫与编译器宏表判定不一致），回退成装载序首个编译出的函数并出告警——一个纯诊断
     *   字段不该毒化整包（2026-11 蕾米莉亚包实测：5 个源栽在「入口函数不存在」上）。
     */
    class PatchResult(
        val program: Program,
        val functions: List<FunctionBytecode>,
        val funcMetas: List<FuncMeta>,
        val privateDims: List<BootMetadata.PrivateDim>,
        val sizeConsts: Map<String, Long>,
        val parseWarnings: List<String>,
        val entryFunction: String = "",
    )

    /** 预扫产物：一个 @函数 的静态信息（第二遍切片与 FuncMeta 构造都用它）。 */
    private class FuncHead(
        val name: String,
        val file: String,
        /** reader 的 0 基行号（[LogicalLineReader] 口径，spec: EraStreamReader.cs:30）；
         *  只作 [PatchSourceResolver] 的定义身份键。 */
        val lineNo: Int,
    ) {
        /** 人读行号（1 基，与 golden/侧车 position 口径一致；I7 可定位）。
         *  [LineParser.parseLabelLine] 调 [DefinitionScopedResolver.enterFunctionDefinition]
         *  时传的是同一个 0 基值，故键面必须用 [lineNo]，消息面一律用本属性。 */
        val displayLine: Int get() = lineNo + 1
        var isMethod = false
        var returnsStr = false

        /**
         * `#FUNCTIONF`（4.981 fork 的第三种方法返回类型）置位。
         *
         * 与 [returnsStr] 互斥：原版一个方法只有一个 `MethodType`，fork-src
         * `LogicalLineParser.cs:141-183` 的 `#FUNCTION` / `#FUNCTIONS` / `#FUNCTIONF` 三选一。
         * 决定本函数 `FuncSig.returnsFloat`（补丁 `RETURNF` 编 `_RETF` 浮点通道）与
         * `FuncMeta.returnsFloat`（运行期取 `VirtualMachine.floatResult`），与全量路的
         * `CorpusCompile.kt:646`（`returnsFloat = funcType == OperandType.FLOAT`）同口径。
         */
        var returnsFloat = false
        val dimLines = ArrayList<Pair<Int, String>>()          // 人读行号（1 基）→ `#DIM` 行原文
        var rawLine: String = ""

        /**
         * `#LOCALSIZE` / `#LOCALSSIZE` 的折常量值（R7-D2 TODO②；未声明 = 0，运行期走
         * 注册表默认，spec: LogicalLineParser.cs:218-273）。采集在切片遍的 [SharpStmt]
         * 上（LineParser 已按 dimConsts 折好；折不出 = 该行不生效保持 0，同 spec
         * :236-239），后到者覆盖（与符号层扫描通道 Symbols.kt:962-969 同款语义）。
         */
        var localSize = 0
        var localsSize = 0

        /** `#LOCALFSIZE n`（4.981 fork，浮点局部/形参长度；未声明 = 0，运行期取默认 10）。
         *  spec: LogicalLineParser.cs:257-266、variables.yaml:99；全量侧同字段见 Symbols.kt:980。 */
        var floatLocalSize = 0
    }

    /** 预扫/切片共用的私名视图：按定义（文件, 行号）切换的私有变量解析面。 */
    private class PatchSourceResolver(
        private val inner: SymbolTable,
        private val privateByDefinition: Map<Pair<String, Int>, List<VarInfo>>,
        /** 包内函数头（大写键 → 头）：解析期表达式位调用包内函数（含前向互调、裸名零参形态）的存在性来源。 */
        private val patchHeads: Map<String, FuncHead>,
    ) : DefinitionScopedResolver {
        private var active: List<VarInfo> = emptyList()

        override fun resolveVariable(name: String, subKey: String?): VarInfo? =
            active.firstOrNull { it.name == name.uppercase() } ?: inner.resolveVariable(name, subKey)

        override fun resolveFunction(name: String): OperandType? {
            inner.resolveFunction(name)?.let { return it }
            val h = patchHeads[name.uppercase()] ?: return null
            // 包内方法按声明返回类型；普通 @label 钉 INT——ExprCallTable 把它们归
            // nonMethodLabels（不降级、保持 FuncCall 节点交运行期重入通道），解析期类型位
            // 不进编译产物（2026-11 蕾米莉亚包实测：包内互调 IS_HERE 形态在解析期就要认）。
            // float 分支与运行期解析器同序（ProcessRuntime.kt:914-916 的三分：float→str→int）；
            // 缺了它，包内 #FUNCTIONF 互调（目标只进 patchHeads、不进共享 SymbolTable ——
            // ModImportController.buildPatchSymbolTable 只预声明 base 侧车方法）的 FuncCall
            // 在解析期被钉成 INT，运行期 FuncCall.getIntValue 对 FloatV 抛
            // 「函数 X 返回了非整数结果」（Expr.kt:553-556）——赋值/TOINT/比较/RETURNF 全形态。
            return when {
                !h.isMethod -> OperandType.INT
                h.returnsFloat -> OperandType.FLOAT
                h.returnsStr -> OperandType.STR
                else -> OperandType.INT
            }
        }

        override fun canBeReferencedBare(name: String): Boolean = inner.canBeReferencedBare(name)

        /** `POWER` 一类实参浮点晋升（spec: FunctionMethodTerm.cs:44-56）：内层符号表口径。 */
        override fun canReturnFloat(name: String): Boolean = inner.canReturnFloat(name)

        override fun isUserMethod(name: String): Boolean = inner.isUserMethod(name)

        override fun enterFunctionDefinition(functionName: String?, filename: String, line: Int) {
            // @ 行解析内的作用域切换（LineParser.parseLabelLine 在形参提取之前调用）：
            // 形参默认值引用私有名在此刻就可见。
            active = privateByDefinition[filename to line] ?: emptyList()
        }
    }

    /**
     * 编译补丁：**两级**容错包装层（2026-11 蕾米莉亚色情特化 v1.3 实测）。
     *
     * 社区口上包普遍夹带**示例/模板文件**（如 `M_KOJO_K16_5_CUSTOM_EVENTS.ERB` 引用
     * 根本不存在的 `Add_CULPRIT`、`依頼.ERB` 用 V4.980 已废弃的 `IRAI_STR:CHK_SLOT`）。
     * 原版的容错粒度是**函数**：函数体解析失败 ⇒ 该函数标错不可调用，同文件其余函数照常装载
     * （spec: ErbLoader.cs:995-1013 ParseFunctionWithCatch 逐 label 捕获）。
     * - 主粒度在 [compileLocked] 的切片步里落：逐函数作废 + 逐条告警（I7 带原文与首错），
     *   引用了作废函数的调用方连带作废（与原版「未定义标识符」同级联）；
     * - 本包装层是**兜底**：坏在函数体外（@ 行之前的正文、reader 词法错误）时按整文件丢弃续编。
     * 一个坏文件/坏函数毒化整包 = 「导入 100 个包没有一个成功」的直接成因。
     *
     * 边界（宁可拒也不静默）：
     * - 只按**报错消息里出现的 relPath** 归因；归因不到（链接期/未预期异常）⇒ 原样抛；
     * - 归因出的文件不在本次源清单里、或全部文件都被丢完 ⇒ 原样抛最后一次失败；
     * - 丢弃数上限 [MAX_DROPPED_SOURCES]（防「整包全是坏文件」时退化成逐文件死循环）。
     */
    fun compile(sources: List<SourceFile>, entryFunction: String): PatchResult {
        val dropped = ArrayList<String>()
        var active = sources
        while (true) {
            try {
                val result = compileLocked(active, entryFunction)
                if (dropped.isEmpty()) return result
                return PatchResult(
                    program = result.program,
                    functions = result.functions,
                    funcMetas = result.funcMetas,
                    privateDims = result.privateDims,
                    sizeConsts = result.sizeConsts,
                    parseWarnings = result.parseWarnings + dropped,
                    entryFunction = result.entryFunction,
                )
            } catch (e: Exception) {
                // 只容**解析/词法**这一级（原版也是这一级按行弹栈续装）；I6 各闸
                // （全局 #DIM / 撞 base 同名 / 省略实参 / 存档列）是设计性硬拒，不容——
                // 容下来就变成「补丁少了一小块但没人知道」，与禁静默错值同族。
                val m = e.message
                if (m == null || !(m.contains("解析失败") || m.contains("词法错误"))) throw e
                // 空面（全部函数逐个作废）已经是「带原文的最终归因」，按文件再丢一轮只会
                // 把同一失败重编 N 遍，且报给玩家的是最后一个文件——直接原样抛。
                if (m.contains("没有留下任何可装载的函数")) throw e
                val culprit = attributedSource(m, active) ?: throw e
                if (dropped.size >= MAX_DROPPED_SOURCES) throw e
                active = active.filterNot { it === culprit }
                if (active.isEmpty()) throw e
                dropped += "已跳过解析失败的源 ${culprit.relPath}（首错：${e.message?.take(300)}）" +
                    "——坏在该文件函数体之外（@ 行前的正文或词法错误），只能按整文件粒度作废：" +
                    "函数体内的坏行由函数级容错处理（spec: ErbLoader.cs:995-1013），" +
                    "该文件的其余函数不会生效"
            }
        }
    }

    /** 报错消息 → 肇事源（消息里最早出现的 relPath）；归因不到返回 null（调用方原样抛）。 */
    private fun attributedSource(message: String?, sources: List<SourceFile>): SourceFile? {
        if (message.isNullOrEmpty()) return null
        var best: SourceFile? = null
        var bestAt = Int.MAX_VALUE
        for (s in sources) {
            val at = message.indexOf(s.relPath)
            if (at >= 0 && at < bestAt) {
                bestAt = at
                best = s
            }
        }
        return best
    }

    /**
     * 一次编译（[compile] 的容错包装层在内，本函数自身仍是全或无）：任何失败在返回前抛出，
     * 调用方拿不到半成品、不落盘（不变量 I5）。
     *
     * @param sources 通过容错筛选后的补丁源（≥1 个；相对路径进报错定位）。
     * @param entryFunction 补丁入口函数名（补丁头 `entry-function` 字段；缺失时按装载序
     *   回退到首个编译出的函数，见 [PatchResult.entryFunction]）。
     */
    private fun compileLocked(sources: List<SourceFile>, entryFunction: String): PatchResult {
        require(sources.isNotEmpty()) { "补丁源为空：至少要有一个 ERB 文件" }
        require(entryFunction.isNotBlank()) { "entry-function 不能为空" }

        // ---- 步骤 1：尺寸常量表（侧车 macros + constants，两端同解）----
        val dimCfg = metadata.dimParseConfig
        val consts = sizeConsts(dimCfg.macros, dimCfg.intConstants)

        // ---- 步骤 1.5：词法宏表（R7-D2 TODO①，与 erbcc 全量同源接法）----
        // 桌面全量把同一份 LexerConfig 喂 LineParser 与 LogicalLineReader（CorpusCompile.kt:313-320
        // 的 A1 接线先例；宏展开在 ErbLexer.analyse 的 token 流末尾 expandMacros，
        // spec: LexicalAnalyzer.cs:942-1006），本编译器走同一条通道，不建第二实现：
        // - 宏表 = 侧车 dim-config 段（与步骤 1 的 sizeConsts 同一数据源）；空体宏按侧车
        //   原样保留（符号层收集口径：体 = 空串也进表，spec: ErhLoader.cs:167-171）——
        //   引用处展开为空 token 流，随后的解析失败方向与桌面一致（不能当数值用）；
        //   尺寸求值侧的「空体不进表」由步骤 1 的 foldSizeConstants 负责，两表各司其职。
        // - macroIgnoreCase = true：查表按 Locale.ROOT 大写归一（expandMacros 同款），
        //   与 ErbPreprocessor 宏名口径（构造于下方 prescan/slice 的同表 keys）一致。
        // - 其余词法开关维持出厂默认（现状口径，零回退；与全量 fromConfigStore 的
        //   System 两键差异是既有面，见类尾 TODO(verify)）。
        val lexerCfg = LexerConfig(macros = dimCfg.macros, macroIgnoreCase = true)

        // ---- 步骤 2：base 侧符号面 ----
        // 函数名（大写键）→ 绝对下标。同名事件成员/合成入口在侧车是**合法同键多记录**
        // （BootMetadata 的 function 段不以 key 判重），取最后一条 = 装载序的胜者口径。
        val baseIndexByName = HashMap<String, Int>()
        for (m in metadata.functions) baseIndexByName[m.key] = m.index
        val baseKeys = baseIndexByName.keys
        val methodTypes = metadata.knownMethodTypes()
        val baseRefDims = HashMap<String, MutableList<LocalDim>>()
        for (pd in metadata.privateDims) {
            val spec = pd.spec
            if (spec.isRef) {
                baseRefDims.getOrPut(pd.functionKey) { ArrayList() }
                    .add(LocalDim(spec.name, spec.isStr, slots = 1, ref = true))
            }
        }

        // ---- 步骤 3：预扫（第一遍）——函数头 / 方法声明 / `#DIM` 原文，带 I6 前三闸 ----
        // 包内同键重复定义 = 后定义者覆盖（原版 LabelDictionary.cs:60-68 语义），覆盖时
        // 塞一条 parseWarnings 告警（ErbLoader.cs:443-450 的 level-1 告警口径）。
        val heads = LinkedHashMap<String, FuncHead>()          // 大写键 → 胜者定义（后到者覆盖）
        val privateByDefinition = HashMap<Pair<String, Int>, List<VarInfo>>()
        val privateDimsOut = ArrayList<BootMetadata.PrivateDim>()
        val warnings = ArrayList<String>()
        for (src in sources) {
            prescan(src, heads, warnings, lexerCfg)
        }
        require(heads.isNotEmpty()) { "补丁源里没有任何 @函数定义" }

        // ---- 步骤 3.5：私有 `#DIM` 解析（全部源预扫完后做，只解析胜者定义）----
        // 被覆盖定义的 dimLines 随其 FuncHead 一并废弃 ⇒ 旧函数的私有条目天然不会混进
        // privateDimsOut / privateByDefinition（覆盖语义的符号面口径，类 KDoc「包内同名」节）。
        // I6-① 的全局侧拒绝（GLOBAL/SAVEDATA/CHARADATA）仍在这里落，逐定义带 文件:行。
        // 尺寸常量折叠表在这里重算（宏 + 侧车常量），其余解析口径**原样继承侧车 dim-config**
        // ——尤其 systemSaveInBinary：丢掉它会退成 false，把 base 合法的
        // `#DIM CHARADATA SAVEDATA X, n` 判成「非二进制存档下 SAVEDATA 不能是 CHARADATA」
        // （2026-11 蕾米莉亚包实测：73/78 条侧车 global-dims 假跳过即此因）。
        val dimCfgFull = dimCfg.copy(intConstants = consts)
        for ((key, h) in heads) {
            val infos = ArrayList<VarInfo>()
            for ((lineNo, raw) in h.dimLines) {
                val spec = parsePrivateDim(raw, h.file, lineNo, dimCfgFull)
                // 全局侧关键字在函数体内出现 = 原版装载错误（ERB 内无 GLOBAL/SAVEDATA/
                // CHARADATA，spec: UserDefinedVariableData.cs:171-175 + frontend Symbols 的
                // parseDimSpec 同口径），补丁编译从严拒绝——它与「全局 #DIM」是同一条
                // 「不许新增全局槽/存档列」铁律的函数体内形态。
                if (spec.isGlobal || spec.isSavedata || spec.isCharaData) {
                    throw PatchCompileException(
                        "补丁的函数内 #DIM 不允许 GLOBAL/SAVEDATA/CHARADATA（全局侧声明；" +
                            "R7 铁律 3 / 不变量 I6）：${h.file}:$lineNo ${raw.take(80)}",
                    )
                }
                privateDimsOut.add(BootMetadata.PrivateDim(key, spec))
                infos.add(
                    VarInfo(
                        name = spec.name.uppercase(),
                        // 三态判序与全量预扫描同源（CorpusScan.kt:242 `kw == "DIMF" && !isChara -> FLOAT`）：
                        // `#DIMF`（4.981 浮点声明）必须落 FLOAT，落进 INT 会让表达式位按整型通道
                        // 读浮点变量（恒 0），落 STR 更会读空串。isFloat 必须先判 —— DimSpec 的
                        // 两个布尔位「都 false 不代表整数」（UserVariables.kt:72）。
                        operandType = when {
                            spec.isFloat -> OperandType.FLOAT
                            spec.isStr -> OperandType.STR
                            else -> OperandType.INT
                        },
                        dimension = spec.dimension,
                        isCharacterData = false,
                    ),
                )
            }
            if (infos.isNotEmpty()) privateByDefinition[h.file to h.lineNo] = infos
        }

        // ---- 步骤 5：I6-② base 已有同名函数（事件名放行——同名事件是叠加语义）----
        for (h in heads.values) {
            if (Symbols.isEventLabelName(h.name)) continue
            val dup = baseKeys.firstOrNull { it == h.name }
            if (dup != null) {
                throw PatchCompileException(
                    "补丁不能定义 base 已有的同名函数（覆盖是 override 语义，另立项；" +
                        "事件函数成员是支持的——同名事件全部执行，spec: CalledFunction.cs:228-243；" +
                        "非事件覆盖需要改写 base 调用点，另立项。R7 铁律 4 / 不变量 I6）：函数 ${h.name}" +
                        "（补丁 ${h.file}:${h.displayLine}，base 函数表下标 ${baseIndexByName[dup]}）",
                )
            }
        }

        // ---- 步骤 6：补丁内函数头信息 ----
        val patchKeys = heads.keys
        val knownFunctions = HashSet<String>(baseKeys.size + patchKeys.size)
        knownFunctions.addAll(baseKeys)
        knownFunctions.addAll(patchKeys)

        // ---- 步骤 7：表达式位调用分类表（base 方法三分 + base 非方法全集 + 补丁内方法）----
        // 补丁内 #FUNCTION/#FUNCTIONS 必须并进 intMethods/strMethods：表达式位对它们的
        // 调用要与全量编译走同一条降级路径（emitCall），否则 FuncCall 节点保持原样，
        // 补丁段与全量档 B 段字节分叉（A1 同尺对拍的隐含前提）。
        // #FUNCTIONF（浮点方法，4.981 fork）**不进任何一桶**：帧槽只有整型列/字符串列，
        // 降级成哨兵读槽会静默读错值；保持 FuncCall 原节点交运行期重入通道 —— 与全量路
        // `com.erbcc.bridge.buildExprCallTable` 的 FLOAT→unknownMethods 逐字同口径。
        val intMethods = HashSet<String>()
        val strMethods = HashSet<String>()
        val patchFloatMethods = HashSet<String>()
        for ((k, t) in methodTypes) {
            when (t) {
                OperandType.INT -> intMethods.add(k)
                OperandType.STR -> strMethods.add(k)
                // 浮点方法（侧车 `method-types` 三态暂未含 FLOAT 时会被 [knownMethodTypes]
                // 滤成缺键；这里显式兜住，将来侧车补上 FLOAT 也不会被误并进字符串桶）。
                OperandType.FLOAT -> Unit
            }
        }
        for (h in heads.values) {
            if (!h.isMethod) continue
            // 浮点方法单独收名，喂给下面 ExprCallTable 的 unknownMethods：它是**补丁内方法**，
            // 身份与进 intMethods/strMethods 的同族平级；[ExprCallTable.classify] 的判序里
            // unknownMethods 先于 builtin，因此与内置函数同名（如 `#FUNCTIONF TOINT`）时
            // 按「本段覆盖」保持 FuncCall 原节点，而不是被当成内置实现内联。
            val key = h.name.uppercase()
            when {
                h.returnsFloat -> patchFloatMethods.add(key)
                h.returnsStr -> strMethods.add(key)
                else -> intMethods.add(key)
            }
        }
        val nonMethodLabels = HashSet<String>(baseKeys)
        nonMethodLabels.removeAll(methodTypes.keys)
        val exprCalls = ExprCallTable(
            intMethods = intMethods,
            strMethods = strMethods,
            nonMethodLabels = nonMethodLabels,
            unknownMethods = metadata.unknownMethodKeys() + patchFloatMethods,
        )

        // ---- 步骤 8：切片（第二遍）——语句树 + 形参默认值收集 ----
        // 函数级容错（2026-11 蕾米莉亚包实测，粒度对齐原版）：函数体解析失败时原版废弃的是
        // **那一个函数**（其余函数照常装载，spec: ErbLoader.cs:995-1013 ParseFunctionWithCatch
        // 逐 label 捕获并标 ErrMes），不是整个文件。这里同粒度：slice 里遇到语句解析失败
        // ⇒ 该函数记进 poison、函数体作废，同文件从下一个 @ 行继续切。
        // 引用面同步收缩：poison 的键从本轮解析面的 liveHeads 摘除 ⇒ 之后调用它的函数解析到
        // 「未知的标识符」连带被毒（原版同款级联）。前向引用（调用者定义在被调者之后）一轮
        // 内跑完；反向引用要再切一轮，故做到「本轮零新增毒」为止（上限 [MAX_POISON_ROUNDS]，
        // 超限就停手：剩下的坏引用交给步骤 11 报错，由 [compile] 的文件级包装层兜住）。
        val poison = LinkedHashSet<String>()
        val slices = LinkedHashMap<String, Pair<FuncHead, List<Statement>>>()
        var rounds = 0
        while (true) {
            rounds++
            val liveHeads = LinkedHashMap<String, FuncHead>(heads)
            liveHeads.keys.removeAll(poison)
            val before = poison.size
            // resolver/parser 每轮重建：解析面（补丁内可引用函数名）随 poison 收缩，
            // 引用了被毒函数的调用点必须在这一轮的解析层就失败，才能同粒度废弃调用方。
            val resolver = PatchSourceResolver(symbolTable, privateByDefinition, liveHeads)
            val parser = LineParser(resolver, lexerConfig = lexerCfg, dimConsts = consts)
            val parsed = LinkedHashMap<String, Pair<FuncHead, List<Statement>>>()
            for (src in sources) {
                slice(src, liveHeads, parser, parsed, lexerCfg, poison, warnings)
            }
            val grown = poison.size > before
            slices.clear()
            slices.putAll(parsed)
            if (!grown || rounds >= MAX_POISON_ROUNDS) break
        }
        if (slices.isEmpty()) {
            // 空面 = 硬失败，但要把首条作废告警带出去（玩家看到的报错必须点名坏在哪一行，
            // 只说「一个函数都没剩下」等于让人去猜）。包装层对本消息直接原样抛，不再续编。
            throw PatchCompileException(
                "补丁源里没有留下任何可装载的函数（${poison.size} 个函数被逐个作废）；" +
                    "首个作废：${warnings.firstOrNull()?.take(400) ?: "无"}",
            )
        }

        // 补丁内函数头的形参默认值（`@F(A, B = 2)`）——与 base 的侧车表合并后喂编译器。
        val defaults = CallDefaultsCollector()
        for ((key, pair) in slices) {
            val (head, stmts) = pair
            val label = stmts.filterIsInstance<FuncLabelStmt>().firstOrNull()
            if (label != null && label.paramDefaults.isNotEmpty()) {
                defaults.add(key, label.paramDefaults)
            }
        }
        val mergedDefaults = HashMap<String, List<Expr?>>(metadata.paramDefaults)
        for ((k, v) in defaults.build()) mergedDefaults[k] = v

        // ---- 步骤 9：FuncSig 构造 ----
        // 事件成员的 Program 函数名用 `<事件名>#<绝对函数下标>`（CorpusCompile.kt:474 同款
        // 形状）：事件名本身留给步骤 11.5 的合成入口登记（CodeBuilder 对同名 declareFunction
        // 有 require 闸）；FuncMeta.key 仍用裸事件名（步骤 13），运行期事件成员表按键聚合。
        val sigs = ArrayList<Pair<FuncHead, FuncSig>>(slices.size)
        for ((i, entry) in slices.entries.withIndex()) {
            val (key, pair) = entry
            val (head, stmts) = pair
            val label = stmts.filterIsInstance<FuncLabelStmt>().firstOrNull()
            val paramNames = label?.paramNames ?: emptyList()
            val programName =
                if (Symbols.isEventLabelName(key)) "$key#${baseCounts.functionCount + i}" else key
            sigs.add(
                head to FuncSig(
                    name = programName,
                    arity = paramNames.size,
                    isMethod = head.isMethod,
                    returnsStr = head.returnsStr,
                    paramNames = paramNames,
                    // #FUNCTIONF（4.981 fork 第三态）：补丁 RETURNF 编 _RETF 浮点通道，
                    // 与全量路 CorpusCompile.kt:646 的 returnsFloat 逐位同口径。
                    returnsFloat = head.returnsFloat,
                ),
            )
        }

        // ---- 步骤 10：带基线预置的 builder + 编译器（A1「独立 builder 续编」模式）----
        val builder = CodeBuilder(
            codeBase = baseCounts.codeWords,
            statementBase = baseCounts.statementCount,
            stringBase = baseCounts.stringCount,
            longBase = baseCounts.longCount,
            exprBase = baseCounts.exprCount,
            functionBase = baseCounts.functionCount,
        )
        val funcDims = HashMap<String, List<LocalDim>>(baseRefDims)
        for ((key, pair) in slices) {
            funcDims[key] = privateDimsOut.filter { it.functionKey == key }
                .map { pd -> localDimOf(pd.spec) }
        }
        val layout = DimSpecLayout(GlobalNameLayout(), funcDims)
        // R7-D2：保持节点（短路位 / 返回类型未知）的用户方法调用上报面 —— I6-④ 的
        // 空侧车判据在步骤 12 对这批调用做与 _CALL 位同款的省略检查（步骤 12 内注释）。
        val omittedLazyCalls = ArrayList<Pair<FuncCall, FuncSig>>()
        val compiler = ErbProgramCompiler(
            layout = layout,
            callDefaults = mergedDefaults,
            codeBuilder = builder,
            externalFunctionIndex = { name -> baseIndexByName[name] ?: -1 },
            keptUserCallSink = { call, caller -> omittedLazyCalls.add(call to caller) },
        )

        // ---- 步骤 11：逐函数编译 + 冻结 ----
        // 语句按切片键（裸函数名/事件名）取——事件成员的 sig.name 是带 #下标 的 Program 名，
        // 不能用它查切片表。成员声明序 = sigs 序（编译序），绝对下标 = functionBase + i。
        val memberIndexes = ArrayList<Int>(sigs.size)
        for ((head, sig) in sigs) {
            val stmts = slices.getValue(head.name.uppercase()).second
            val fb = compiler.compileFunction(sig, stmts, knownFunctions, exprCalls)
            memberIndexes.add(fb.index)
        }

        // ---- 步骤 11.5：补丁合成事件入口（事件成员的按名派发点）----
        // 对每个「补丁定义了成员的」事件名（装载序 = slices/sigs 序），在补丁段末尾编一个
        // 0 参入口（Program 名 = 裸事件名、isEvent = false）：先 `_CALL` base 同名合成入口
        //（下标 = baseIndexByName 的最后一条记录——base 侧车对事件 key 是「成员 + 入口」
        // 多条同键记录，入口在成员之后追加，CorpusCompile 登记序），再依装载序 `_CALL`
        // 补丁成员，`_RET` 收尾。这是 ErbProgramCompiler.compileEventEntry 的内联等价形
        //（它的 orderedBodies 只能解析补丁内已编译名，base 入口的绝对下标无法表达，见类
        // KDoc「事件函数成员」节）；入口无 callRefs/标签，不经 FunctionBytecode 登记
        //（指令已进补丁 Program，build 的 callRef 回填与 I2 自检不受影响）。
        val entryMetas = ArrayList<FuncMeta>()
        var entrySeq = baseCounts.functionCount + sigs.size
        for ((pair, memberIndex) in sigs.zip(memberIndexes)) {
            val head = pair.first
            if (!Symbols.isEventLabelName(head.name)) continue
            val eventKey = head.name.uppercase()
            val baseEntryIndex = baseIndexByName[eventKey] ?: -1
            val entryPc = builder.here()
            if (baseEntryIndex >= 0) {
                builder.emit(Opcode._CALL, baseEntryIndex, 0, SlotAddr.NONE, -1)
            }
            builder.emit(Opcode._CALL, memberIndex, 0, SlotAddr.NONE, -1)
            builder.emit(Opcode._RET, SlotAddr.NONE, 0, 0, -1)
            val entryIndex = builder.declareFunction(eventKey, entryPc, 0, 0)
            builder.setFunctionSlots(entryIndex, 0)
            entryMetas.add(
                FuncMeta(
                    index = entryIndex,
                    key = eventKey,
                    name = eventKey,
                    // isEvent = false：入口走 nonEventByKey（按名派发的解析目标），不是事件
                    // 成员；事件成员位在成员 meta 上（步骤 13）。
                    defIndex = entrySeq++,
                    // 合成标记 position：FunctionTable.fileOfPosition 判 `·` 前缀返回 null，
                    // 与全量路 CorpusCompile 的入口 position 同款（无源码行可定位）。
                    position = "·event-entry·",
                ),
            )
        }
        // 入口名回退（2026-11 蕾米莉亚包实测）：调用方给的 entry 可能整段落在
        // [SKIPSTART]/[IF 宏] 里（预扫与编译器的宏表判定不同源），它只是补丁头的定位信息
        // （entry-function 无运行语义，入口恒为 base 的），毒化整包不值得——回退到装载序
        // 首个编译出的函数并出告警。
        val entryKey = entryFunction.uppercase()
        val effectiveEntry = if (slices.containsKey(entryKey)) {
            entryKey
        } else {
            val fallback = slices.keys.first()
            warnings.add(
                "入口函数 $entryFunction 不在编译出的补丁函数里（整段被 [SKIPSTART]/[IF 宏] " +
                    "包住，或预扫与编译器的宏表判定不同源）；改用装载序首个函数 $fallback " +
                    "作定位用入口（entry-function 无运行语义，不影响执行）",
            )
            fallback
        }
        val program = compiler.build(effectiveEntry)

        // ---- 步骤 12：I6-④ 空侧车 + base 目标省略实参 → 编译期拒绝 ----
        // 「静默错值」的精确判据：全量编译会补位（target 在全量的 CallDefaults 表 = 侧车
        // paramDefaults 有键）而补丁不会。侧车**非空**时，表里的键由 padOmittedArgs 自动补位
        // （present 满），表外的键全量编译同样不补（CallDefaults 只登记有默认值的函数）⇒
        // 行为同源，不拒。侧车**空表**时无法区分「base 函数真没默认值」与「数据缺失」，
        // 按契约保守拒绝。省略证据 = 调用位实参位数 < 被调形参数（base 口径 =
        // FuncMeta.argIntCount + argStrCount），或补位后的 present=false 残位。
        if (metadata.paramDefaults.isEmpty()) {
            val baseArgcByName = HashMap<String, Int>()
            for (m in metadata.functions) baseArgcByName[m.key] = m.argIntCount + m.argStrCount
            val fileByKey = HashMap<String, String>()
            for ((head, sig) in sigs) fileByKey[sig.name.uppercase()] = head.file
            for (fb in compiler.functions) {
                for (ref in fb.callRefs) {
                    if (ref.target in patchKeys) continue
                    val arity = baseArgcByName[ref.target] ?: continue
                    val args = program.callArgsAt(ref.pc)
                    val missing = args?.indexOfFirst { !it.present } ?: -1
                    val omitted = (args?.size ?: 0) < arity || missing >= 0
                    if (omitted) {
                        throw PatchCompileException(
                            "补丁调用 base 函数 ${ref.target} 省略了实参" +
                                (if (missing >= 0) "（第 ${missing + 1} 位无默认值）" else "") +
                                "，而本侧车没有 param-defaults 段（无法编译期补位；PC 全量编译会补上" +
                                "而补丁不会——同一行 ERB 两种行为即静默错值，R7 不变量 I6）：调用方 " +
                                "${fb.sig.name}（${fileByKey[fb.sig.name] ?: "?"}:${ref.line + 1}），" +
                                "被调 ${ref.target}（形参 $arity 个）",
                        )
                    }
                }
            }

            // ---- 短路位 / 保持节点形态的等价判据（R7-D2 TODO③）----
            // 表达式位调用落在短路位（&&/||/!&/!| 右侧、三目分支、@…?…#… 分支）或返回类型
            // 未知的严格位时保持 FuncCall 节点、运行期重入（ErbProgramCompiler.lowerExpr 的
            // 短路保序口径），实参省略不产生 callArgs 记录 —— 上面的 callRefs 扫描覆盖不到。
            // 这类省略位在运行期重入绑定时按原版 Arguments[i] == null 跳过
            // （spec: ProcessState.cs:437），而全量路径（callDefaults 全）在编译期就由
            // padRuntimeCalls 把默认值顶替进节点 —— 同一行 ERB 两种行为，与 _CALL 位
            // 同一条 I6-④ 静默错值链。形态可达性：补丁源在 && 右侧写 base 方法调用并
            // 省略实参即命中（证据用例：I6 四之短路口）。判据与 _CALL 位同款：
            // 空侧车 + base 目标 + 省略形态（实参位数 < 形参数，或显式空参残位）。
            // keptUserCallSink 的报告点在 padRuntimeCalls 的 userConsumed 分支
            // （METHOD_INT/STR/UNKNOWN；内建与未知名不报），补位成功的调用点（补丁内
            // 目标有默认值可补）在此已无省略形态，天然不命中。
            for ((call, caller) in omittedLazyCalls) {
                val targetKey = call.name.uppercase()
                if (targetKey in patchKeys) continue
                val arity = baseArgcByName[targetKey] ?: continue
                val missingPos = call.args.indexOfFirst { it == null }
                val omitted = call.args.size < arity || missingPos >= 0
                if (omitted) {
                    throw PatchCompileException(
                        "补丁调用 base 函数 ${call.name} 省略了实参" +
                            (if (missingPos >= 0) "（第 ${missingPos + 1} 位为空参残位）" else "") +
                            "（表达式短路位/惰性求值位，运行期重入绑定不补位），而本侧车没有 " +
                            "param-defaults 段（全量编译会把默认值顶替进调用节点而补丁不会" +
                            "——同一行 ERB 两种行为即静默错值，R7 不变量 I6）：调用方 " +
                            "${caller.name}（${fileByKey[caller.name.uppercase()] ?: "?"}:${call.line + 1}），" +
                            "被调 ${call.name}（形参 $arity 个）",
                    )
                }
            }
        }

        // ---- 步骤 13：FuncMeta（绝对下标，C4 函数表尾段追加）----
        // 事件成员：key = 裸事件名（运行期 eventMembersByKey 按键聚合，FuncMeta.kt:465-468）、
        // isEvent = true、事件桶位全缺省 = NORMAL 桶（#ONLY/#PRI/#LATER 不采集，见类尾
        // TODO(verify) 4）。合成事件入口 meta 追加在成员之后（defIndex 续接 ⇒ 装载序成员
        // 在前、入口在后；入口自身 isEvent = false 走 nonEventByKey 覆盖 base 入口）。
        val metas = ArrayList<FuncMeta>(sigs.size + entryMetas.size)
        for ((i, pair) in sigs.withIndex()) {
            val (head, sig) = pair
            val (intCnt, strCnt) = typedArgCounts(head)
            metas.add(
                FuncMeta(
                    index = baseCounts.functionCount + i,
                    key = head.name.uppercase(),
                    name = head.name,
                    isEvent = Symbols.isEventLabelName(head.name),
                    isMethod = head.isMethod,
                    argIntCount = intCnt,
                    argStrCount = strCnt,
                    returnsStr = head.returnsStr,
                    // #FUNCTIONF（4.981 fork 第三态）：运行期据此取 VirtualMachine.floatResult
                    //（ProcessRuntime.invokeUserMethod 第三通道），与全量路 FuncMeta 同口径。
                    // floatLocalSize：`#LOCALFSIZE` 只经这条透传（ProcessRuntime.kt:375 在首次
                    // LOCALF/ARGF 访问前读它）；不透传则补丁函数的浮点帧长度静默退回默认 10。
                    returnsFloat = head.returnsFloat,
                    floatLocalSize = head.floatLocalSize,
                    // #LOCALSIZE/#LOCALSSIZE（R7-D2 TODO②）：值来自补丁源声明，经 LineParser
                    // 的 dimConsts 折常量（slice 的 SharpStmt 采集）；与全量链路同源——
                    // 全量侧 FuncMeta.localSize 的采集通道是符号层扫描（Symbols.kt:962-969
                    // evalSharpInt），其求值真源与本侧 dimSizeEvaluator 同为 D.8.1 求值器。
                    localSize = head.localSize,
                    localsSize = head.localsSize,
                    defIndex = baseCounts.functionCount + i,
                    position = "${head.file}:${head.displayLine}",
                ),
            )
        }
        metas.addAll(entryMetas)

        return PatchResult(
            program = program,
            functions = compiler.functions.toList(),
            funcMetas = metas,
            privateDims = privateDimsOut.filterNot { it.functionKey in poison },
            sizeConsts = consts,
            parseWarnings = warnings,
            entryFunction = effectiveEntry,
        )
    }

    // ======================================================================
    // 预扫（第一遍）：函数头、方法声明、`#DIM` 原文、I6-② 全局 DIM 闸
    // ======================================================================

    /**
     * 预扫一个源文件：只认 `@` 行（函数头）与 `#` 行（`#FUNCTION`/`#FUNCTIONS`/`#FUNCTIONF`/`#DIM`），
     * 不做语句级解析。函数内 `#DIM` 只收原文，解析统一在 compile 步骤 3.5 对**胜者定义**
     * 做（覆盖语义：被覆盖定义的私有声明随其 FuncHead 一并废弃）。
     *
     * 包内同键重复定义 = **后定义者覆盖**（原版对同名非事件函数是「后装载者留下」，
     * spec: LabelDictionary.cs:60-68 直接赋值；装载期只告警不拒绝，spec: ErbLoader.cs:443-450，
     * WarnNormalFunctionOverloading 出厂默认关，spec: ConfigData.cs:94），覆盖时塞一条
     * [warnings] 告警（level 1 语义，不拒绝）。事件名同键覆盖（最后成员胜出）——原版
     * 事件名是多成员并存（LabelDictionary.cs:70-104），差异见类尾 TODO(verify) 4。
     */
    private fun prescan(
        src: SourceFile,
        heads: LinkedHashMap<String, FuncHead>,
        warnings: MutableList<String>,
        lexerCfg: LexerConfig,
    ) {
        val rel = src.relPath
        var cur: FuncHead? = null
        // [SKIPSTART]/[IF 宏] 是跨行状态机：每文件一个实例，跨行复用（不得逐行新建）。
        // （[ 行由下方 reader 的内层预处理状态机消费，此层收到的正文行不含未转义 [ 行；
        //   保留作转义形态与未来 reader 行为变化的兜底。）
        val pp = ErbPreprocessor(false, metadata.dimParseConfig.macros.keys, rel)
        // 同一份 lexerCfg 喂 reader（erbcc 全量 CorpusCompile.kt:313-320 的 A1 同款）；
        // macroNames 接同表宏名（spec: ErbLoader.cs:94 的 [IF 宏] 判定口径）——此前缺省
        // 空表会让补丁源里「[IF 已定义宏]」区间被内层误判禁用（R7-D2 TODO① 一并接线）。
        val reader = LogicalLineReader(
            LogicalLineReader.readAllLines(src.text.toByteArray(Charsets.UTF_8)),
            rel,
            config = lexerCfg,
            macroNames = metadata.dimParseConfig.macros.keys,
            renameDic = renameDic,
        )
        while (true) {
            val line = try {
                reader.readLogicalLine()
            } catch (e: Exception) {
                throw PatchCompileException("$rel 词法错误：${e.message ?: e::class.simpleName}")
            } ?: break
            val content = line.content.trimStart(' ', '\t')
            if (content.isEmpty() || content.startsWith(";")) continue
            if (pp.process(content, line.lineNo)) {
                if (pp.disabled) continue
            }
            when {
                content.startsWith("@") -> {
                    val name = functionNameOf(content)
                        ?: throw PatchCompileException("$rel:${line.lineNo + 1} 无法识别函数名：${content.take(80)}")
                    val key = name.uppercase()
                    val old = heads.put(key, FuncHead(name, rel, line.lineNo).apply { rawLine = content })
                    if (old != null) {
                        // 后定义者生效；被覆盖定义的 dimLines 挂在旧 FuncHead 上随替换废弃
                        //（步骤 3.5 只解析 heads 里的胜者定义）。告警口径 = 原版
                        // FuncIsAlreadyDefined 的 level-1（ErbLoader.cs:448）。
                        warnings.add(
                            "函数 $key 重复定义，后定义者生效：$rel:${line.lineNo + 1} 覆盖 " +
                                "${old.file}:${old.displayLine}（原版对同名函数覆盖只告警不拒绝，" +
                                "spec: LabelDictionary.cs:60-68 + ConfigData.cs:94）",
                        )
                    }
                    cur = heads.getValue(key)
                }
                content.startsWith("#") -> {
                    val up = content.uppercase()
                    val h = cur
                    when {
                        up.startsWith("#FUNCTIONS") -> h?.let { it.isMethod = true; it.returnsStr = true }
                        // `#FUNCTIONF`（4.981 fork）必须先于下面的 `#FUNCTION` 判定：字符串前缀
                        // `"#FUNCTIONF".startsWith("#FUNCTION")` 为真，落在整数方法支会让浮点方法
                        // 退回整型通道（补丁 `RETURNF <浮点表达式>` 撞 `evalExprTo` 的 FLOAT 守卫 → 误报）。
                        up.startsWith("#FUNCTIONF") -> h?.let { it.isMethod = true; it.returnsFloat = true }
                        up.startsWith("#FUNCTION") -> h?.let { it.isMethod = true }
                        up.startsWith("#DIM") -> {
                            if (h == null) {
                                throw PatchCompileException(
                                    "补丁不能声明全局 #DIM（Program.globalSlots 构造期定死，扩全局槽" +
                                        "牵动 VariableStore 与存档列；R7 铁律 3 / 不变量 I6）：$rel:${line.lineNo + 1} " +
                                        content.take(80),
                                )
                            }
                            h.dimLines.add(line.lineNo + 1 to content)
                        }
                        else -> Unit     // #LOCALSIZE 等其余元数据行：第二遍由 LineParser 处理
                    }
                }
                else -> Unit
            }
        }
        // base REF 布局的并集在这里不碰：baseRefDims 只按 base 键存取，补丁键由 compile 组装。
    }

    /** 解析一条函数内 `#DIM`/`#DIMS` 原文；解析失败 = 补丁编译失败（带 文件:行）。 */
    private fun parsePrivateDim(raw: String, file: String, lineNo: Int, cfg: DimParseConfig) =
        try {
            val keyword = raw.substringBefore(' ', raw).uppercase()
            DimParser.parse(keyword, raw.substringAfter(' ', "").trim(), isPrivate = true, config = cfg)
        } catch (e: CodeEE) {
            throw PatchCompileException("#DIM 解析失败（原版 CodeEE）：$file:$lineNo ${raw.take(80)} —— ${e.message}", e)
        }

    /** `@` 行的函数名（`@` 之后到首个空白/`(` 的标识符段）。 */
    private fun functionNameOf(content: String): String? {
        val body = content.substring(1)
        var i = 0
        while (i < body.length && body[i] !in " \t(,") i++
        val name = body.substring(0, i).trim()
        return name.ifEmpty { null }
    }

    /** FuncMeta 的 (argIntCount, argStrCount)：口径 = [com.eratw.port.core.frontend.LabelTable] 的
     *  countTypedArgs（符号层单源，形参类型判定规则四条同源）。 */
    private fun typedArgCounts(head: FuncHead): Pair<Int, Int> {
        val open = head.rawLine.indexOf('(')
        if (open < 0) return 0 to 0
        val counts = com.eratw.port.core.frontend.LabelTable.countTypedArgs(head.rawLine, open)
        return counts.second to counts.third
    }

    /** core.vars 的已解析声明 → 编译面 [LocalDim]（REF 恒 1 槽，非 REF = 元素总数）。 */
    private fun localDimOf(spec: com.eratw.port.core.vars.DimSpec): LocalDim =
        if (spec.isRef) {
            LocalDim(spec.name, spec.isStr, slots = 1, ref = true)
        } else {
            LocalDim(spec.name, spec.isStr, slots = maxOf(spec.totalSize, 1), ref = false)
        }

    // ======================================================================
    // 切片（第二遍）：语句树
    // ======================================================================

    /**
     * 切片一个源文件：同一 [LineParser] 逐逻辑行解析，按 [FuncLabelStmt] 归属函数体。
     *
     * 函数级容错（对齐原版 [ParseFunctionWithCatch] 的废弃粒度，spec: ErbLoader.cs:995-1013）：
     * 函数体内某语句解析失败 ⇒ 把该函数键记进 [poison]、丢掉它的函数体，从下一个 @ 行继续切
     * 本文件（后续行不再送进 parser——它引用的标识符可能同因失败，归因会飘成「文件级」）。
     * 归因不到函数的失败（@ 行之前的正文、reader 的词法错误）原样抛出，交给 [compile] 的
     * 文件级包装层。私名作用域 / #LOCALSIZE 采集只对留下的函数生效，被毒函数一并作废。
     */
    private fun slice(
        src: SourceFile,
        heads: LinkedHashMap<String, FuncHead>,
        parser: LineParser,
        out: MutableMap<String, Pair<FuncHead, List<Statement>>>,
        lexerCfg: LexerConfig,
        poison: MutableSet<String>,
        warnings: MutableList<String>,
    ) {
        val rel = src.relPath
        var curKey: String? = null
        var abandonToLabel = false
        val body = ArrayList<Statement>()
        fun flush() {
            val key = curKey ?: run { body.clear(); return }
            val head = heads.getValue(key)
            out[key] = head to body.toList()
            body.clear()
        }
        // 同预扫：预处理状态机每文件一个实例；reader 接同一份 lexerCfg + 宏名表。
        val pp = ErbPreprocessor(false, metadata.dimParseConfig.macros.keys, rel)
        val reader = LogicalLineReader(
            LogicalLineReader.readAllLines(src.text.toByteArray(Charsets.UTF_8)),
            rel,
            config = lexerCfg,
            macroNames = metadata.dimParseConfig.macros.keys,
            renameDic = renameDic,
        )
        while (true) {
            val line = try {
                reader.readLogicalLine()
            } catch (e: Exception) {
                throw PatchCompileException("$rel 词法错误：${e.message ?: e::class.simpleName}")
            } ?: break
            val content = line.content.trimStart(' ', '\t')
            if (content.isEmpty() || content.startsWith(";")) continue
            if (pp.process(content, line.lineNo)) {
                if (pp.disabled) continue
            }
            if (abandonToLabel) {
                // 被毒函数的剩余语句：不再送进 parser，等下一个 @ 行重新开窗。
                if (!content.startsWith("@")) continue
                abandonToLabel = false
            }
            val stmt = try {
                parser.parseLogicalLine(line)
            } catch (e: Exception) {
                val failedKey = curKey
                if (failedKey == null) {
                    throw PatchCompileException(
                        "$rel:${line.lineNo + 1} 解析失败：${e.message ?: e::class.simpleName}" +
                            " | ${content.take(120)}",
                        e,
                    )
                }
                // 函数级废弃：同一个函数只记一条告警（多轮重切不重复上报）。
                if (poison.add(failedKey)) {
                    // 立刻摘出本轮解析面 ⇒ 同文件/后续文件里调用它的函数在这一轮就解析失败。
                    heads.remove(failedKey)
                    warnings.add(
                        "已跳过解析失败的函数 @$failedKey（$rel:${line.lineNo + 1}，首错：" +
                            "${e.message?.take(300)} | ${content.take(120)}）——原版按函数粒度废弃" +
                            "（spec: ErbLoader.cs:995-1013），同文件其余函数照常装载；调用它的补丁函数" +
                            "会连带被跳过",
                    )
                }
                body.clear()
                curKey = null
                abandonToLabel = true
                continue
            } ?: continue
            when (stmt) {
                is FuncLabelStmt -> {
                    flush()
                    val key = stmt.name.uppercase()
                    // 包内同名覆盖（后定义者生效）：heads 里是胜者定义，函数体按定义身份
                    //（文件, 0 基行号）比对——非胜者的 @ 行整段丢弃（连函数头带语句体），
                    // 私名作用域与 #LOCALSIZE 采集都只对胜者生效。spec: LabelDictionary.cs:60-68。
                    val winner = heads[key]
                    // 更早轮次已废弃的函数（不在本轮解析面里）：整段作废，等下一个 @ 行。
                    if (winner == null) {
                        curKey = null
                        abandonToLabel = true
                    } else if (winner.file == rel && winner.lineNo == line.lineNo) {
                        curKey = key
                        // 函数头保留在体内：FunctionPass.compileOne 对 FuncLabelStmt 无操作，
                        // 而 paramDefaults / paramNames 的收集要靠它（RealCorpusCompile 的
                        // Slice 携带同款字段，这里直接让切片产物自含）。
                        body.add(stmt)
                    } else {
                        curKey = null
                    }
                }
                is SharpStmt -> {
                    // #FUNCTION/#DIM 等元数据行：不进函数体（同 RealCorpusCompile 口径）。
                    // #LOCALSIZE/#LOCALSSIZE/#LOCALFSIZE 在此顺手采集（R7-D2 TODO②；
                    // 浮点侧为 4.981 fork 新增）：LineParser 的
                    // dimConsts 通道已折好常量（values[0]）。归属判定对齐符号层扫描通道
                    // （@ 行之前的 # 行不归属任何函数，Symbols.kt:921 curPos < 0 continue
                    // 的同款）；后到者覆盖。值域口径 = 原版 <=0 或 >= int.MaxValue 告警后
                    // 不生效（spec: LogicalLineParser.cs:236-244）：越界值不写、保持 0
                    // （不静默截断）；16 位域上限由 FuncMeta 构造期 require 兜底（明确失败）。
                    val key = curKey
                    if (key != null) {
                        val head = heads.getValue(key)
                        val v = stmt.values.firstOrNull()
                        if (v != null && v in 1..Int.MAX_VALUE.toLong()) {
                            when (stmt.keyword.uppercase()) {
                                "LOCALSIZE" -> head.localSize = v.toInt()
                                "LOCALSSIZE" -> head.localsSize = v.toInt()
                                // 4.981 fork 的浮点侧同形关键字（LineParser.kt:909 已把
                                // LOCALFSIZE 与另两条同支收集；全量侧 Symbols.kt:980 同口径）。
                                "LOCALFSIZE" -> head.floatLocalSize = v.toInt()
                                else -> Unit
                            }
                        }
                    }
                }
                else -> if (curKey != null) body.add(stmt)
            }
        }
        try {
            reader.fileEnd()
        } catch (_: Exception) {
        }
        flush()
    }

    companion object {
        /** 文件级容错的丢弃上限：超过即认为整包不可用，原样抛最后一次失败。 */
        private const val MAX_DROPPED_SOURCES = 64

        /**
         * 函数级容错的重切轮次上限：每轮至少推进一个作废函数（反向引用链一轮只剥一层），
         * 到上限仍未收敛就停手——剩下的坏引用交给步骤 11，由文件级包装层兜住。
         */
        private const val MAX_POISON_ROUNDS = 8

        /**
         * 补丁头组装的**权威口径**（R7-E1 写头）：六个 `base-*` 字段取自 [baseCounts]，
         * `patch-*` 五项取自 [result] 的绝对计数差（与导入链就地组装的口径逐字段相同），
         * `entry-function` 用编译入口名，私有 `#DIM` 从 [PatchResult.privateDims] 进
         * 可选段 `private-dims`、per-function 元数据从 [PatchResult.funcMetas] 进
         * 可选段 `function-metas`（[PatchHeader] 的空表整段省略 ⇒ 无私有声明、全部函数
         * 元数据缺省的补丁产物与 15 字段形**逐字节相同**）。
         *
         * C5 导入链（`ModImportController`）的写头已改走本函数（`headerOf` E1 权威口径）；
         * 测试面与本函数同源 —— 「测试写的头」与「导入链写的头」必须是同一套口径，
         * 不许两处各写一遍漂移。
         */
        fun headerOf(
            result: PatchResult,
            baseCounts: BaseCounts,
            baseSha256: String,
            entryFunction: String,
        ): PatchHeader {
            val program = result.program
            return PatchHeader(
                baseInstructionCount = baseCounts.instructionCount,
                baseFunctionCount = baseCounts.functionCount,
                baseStatementCount = baseCounts.statementCount,
                baseStringCount = baseCounts.stringCount,
                baseLongCount = baseCounts.longCount,
                baseExprCount = baseCounts.exprCount,
                baseSha256 = baseSha256,
                compiler = PatchArtifacts.compilerSummary(),
                patchFunctionCount = program.functions.size,
                patchStatementCount = program.statementCount - baseCounts.statementCount,
                patchStringCount = program.stringCount - baseCounts.stringCount,
                patchLongCount = program.constantCount - baseCounts.longCount,
                patchExprCount = program.exprCount - baseCounts.exprCount,
                entryFunction = entryFunction,
                privateDims = result.privateDims,
                functionMetas = functionMetaRecords(result, baseCounts),
            )
        }

        /**
         * `function-metas` 可选段的载荷（[FunctionMetaRecord]）：
         * 步骤 13 的 `funcMetas` 里**六项任一非缺省**的函数（`#FUNCTION`/`#FUNCTIONS`/
         * `#FUNCTIONF` 方法位、`#LOCALSIZE`/`#LOCALSSIZE`/`#LOCALFSIZE` 声明）。
         *
         * 为什么必须落盘：这些声明只活在编译期的 `FuncMeta` 上（`.erbc` 容器函数记录
         * 不承载,补丁又没有 `.meta` 侧车）——不落盘时装载侧按缺省重建,
         * `#FUNCTIONF` 返回恒 0、`#FUNCTIONS` 恒空串、`#LOCALFSIZE` 退默认 10
         * （静默错值家族,见 [PatchHeader] 类 KDoc「可选段 function-metas」）。
         *
         * `offset` = `FuncMeta.index - baseFunctionCount`（补丁窗口内函数序,步骤 13 的
         * `index = baseCounts.functionCount + i` 构造保证）;
         * `name` 取补丁 Program 同序位的函数名 —— 装载侧（`ModPatchLoader`）按这两把
         * 钥匙与补丁体互证,对不上 = 产物损坏,装载期拒绝。
         */
        private fun functionMetaRecords(
            result: PatchResult,
            baseCounts: BaseCounts,
        ): List<FunctionMetaRecord> {
            val out = ArrayList<FunctionMetaRecord>(result.funcMetas.size)
            for (m in result.funcMetas) {
                if (!(m.isMethod || m.returnsStr || m.returnsFloat ||
                        m.floatLocalSize != 0 || m.localSize != 0 || m.localsSize != 0)
                ) {
                    continue
                }
                val offset = m.index - baseCounts.functionCount
                val programName = result.program.functions.getOrNull(offset)?.name
                    ?: throw PatchCompileException(
                        "补丁函数 ${m.key} 的 FuncMeta 下标（${m.index}）落在补丁函数表之外" +
                            "（patch-functions = ${result.program.functions.size}）——编译器内部不一致",
                    )
                out.add(
                    FunctionMetaRecord(
                        offset = offset,
                        name = programName,
                        isMethod = m.isMethod,
                        returnsStr = m.returnsStr,
                        returnsFloat = m.returnsFloat,
                        floatLocalSize = m.floatLocalSize,
                        localSize = m.localSize,
                        localsSize = m.localsSize,
                    ),
                )
            }
            return out
        }
    }
}

// TODO(verify)（v1 边界，全部不许静默）：
//
// 1. 【已清，R7-D2 TODO①】词法层 `#DEFINE` 宏展开已接：侧车 dim-config 段宏表经
//    [LexerConfig.macros] 喂 core/lexer 的 expandMacros 单源通道（与 erbcc 全量
//    CorpusCompile.kt:313-320 同源；同一份配置喂 LineParser 与 LogicalLineReader，
//    后者的 macroNames 同时修复了补丁源「[IF 已定义宏]」区间被内层空表误判禁用的
//    既有缺口）。未定义宏引用仍走「未声明名 → 解析失败」的明确失败方向，不静默当 0。
//    原版 spec: LexicalAnalyzer.cs:285-307（标识符读宏、循环展开、上限 100）。
//    残留：其余词法开关（SystemAllowFullSpace 等）维持出厂默认，与全量 fromConfigStore
//    的本作 emuera.config 口径有既有差异——导入链若有 ConfigStore 源可一并接线（组长裁定）。
// 2. 【已清，R7-D2 TODO②】`#LOCALSIZE`/`#LOCALSSIZE` 已从切片遍的 [SharpStmt] 采集
//    （LineParser 的 dimConsts 通道折常量，与全量链路 D.8.1 求值器同源）并透传
//    FuncMeta.localSize/localsSize；折不出 = 该行不生效保持 0（spec: LogicalLineParser.cs:236-239）。
// 3. 【已清一半，2026-11 蕾米莉亚包】解析失败的**函数级**作废已复刻：函数体内任一语句
//    解析失败 ⇒ 该函数整体作废 + 逐条告警，同文件其余函数照常装载（原版按 label 逐个
//    try/catch 并标 ErrMes，spec: ErbLoader.cs:995-1013 ParseFunctionWithCatch）；引用了
//    作废函数的调用方连带作废（与原版「未定义标识符」同一级联）。
//    **行级**续装仍未：坏一行会带走它所在的整个函数（原版 InstructionLine 逐行构造失败
//    只丢那一行，spec: ErbLoader.cs:1215-1255 结构不匹配时告警弹栈续装），函数体外
//    （@ 行之前的正文、reader 词法错误）的失败退到文件级由 [compile] 包装层兜。
// 4. 【改写，wave/mod-import-event-members】补丁事件成员已放开（原 I6-① 撤销）：
//    成员 FuncMeta 的事件桶位（#ONLY/#PRI/#LATER/#SINGLE）不采集，恒 NORMAL 桶
//    （isOnly/isPri/isLater 全 false ⇒ eventBucket = 2）、#SINGLE 恒 false。装载序语义 =
//    「补丁成员追加在全部 base 成员之后」（defIndex 续接 base 计数），覆盖社区口上
//    「裸 @EVENTxxx 挂钩」的主流形态；带 #PRI/#LATER 的事件成员会被静默当 NORMAL 桶
//    成员执行（原版会插桶，spec: CalledFunction.cs:228-243），需要采集时从切片遍的
//    [SharpStmt] 补（与 #LOCALSIZE 同款通道），组长裁定后再接。两个残余差异：
//    （a）同名事件名的包内重复定义在本编译器是「最后成员胜出」，而原版是多成员并存
//    （spec: LabelDictionary.cs:70-104 四桶追加）——社区包对同一事件名的多定义靠
//    同名覆盖叠打的形态会被吞成员，待采集多成员语义时一并处理；（b）装载路
//    （GameSession.buildPatchFunctionMetas）按 Program 函数名重建补丁 FuncMeta 且
//    事件位恒 false，事件成员要进 eventMembersByKey 需该重建面识别事件名（组长接线）。
// 5. 【已清，R7-D2 TODO③】短路位（`&&` 右侧等）/ 返回类型未知严格位的 FuncCall 保持
//    节点、实参省略不经过 callArgs 的 I6-④ 残余缺口已补判据：ErbProgramCompiler 的
//    keptUserCallSink（缺省 null = 旧行为）在 padRuntimeCalls 的 userConsumed 分支上报，
//    步骤 12 对空侧车 + base 目标 + 省略形态编译期拒绝（与 _CALL 位同款）。
//    勘察结论：U 桶（methodTypes 缺声明）保持节点的「运行期显式红」只覆盖返回值通道
//    错配（ExprCallKind.METHOD_UNKNOWN KDoc），省略实参的重入绑定仍是静默跳过
//    （spec: ProcessState.cs:437）⇒ 判据不分桶，U 桶一并拒绝。
// 6. 多文件补丁的装载序 = 调用方传入的 sources 顺序：导入链（C5）必须按文件名排序后
//    传入，才能与原版「按文件名排序装载」的函数表序一致（本编译器不自行排序）。