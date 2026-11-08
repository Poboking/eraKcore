package com.eratw.port.core.err

import com.eratw.port.core.lexer.LangKey

/**
 * 脚本级运行错误 —— **全工程唯一的 CodeEE 真源**（team-lead 裁决 2026-10-17）。
 *
 * 对应原版 `CodeEE`（`MinorShift.Emuera` 的脚本错误）：ERB 语义错误在运行期抛出，
 * 可被 `TRYC` 家族捕获（捕获语义属语句层 / M2 第二批）。
 *
 * 位置信息是**结构化字段**，不揉进 message：message 保持原版 `Lang.Error` 的文案，
 * 宿主需要"函数:行:pc"时自行拼接或直接读字段。
 *
 * - [funcName]：当前帧的 ERB 函数名；非 VM 场景为 null。
 * - [line]：**内部 0 基行下标**，不是给人读的行号。它的来源链一路都是 0 基 ——
 *   `LogicalLine.lineNo`（[com.eratw.port.core.lexer.LogicalLineReader] 的 `curNo`）→
 *   `Statement.line` → `Program.lines`（ERBC 产物行号表，键面）→ `Vm.envAt` →
 *   `StmtEnv.line` → 本字段（`ProcessRuntime.stampPosition` 原样透传，
 *   `proc/ExprErrorStampTest` 逐字钉住那条透传）。**不许**把它改成 1 基：
 *   那会同时改动产物行号表与 `pc`↔行号的对照面。
 *   本字段原先的 KDoc 写「本工程的汇编器与 erbcc 都用 1 起」——那句对**设备上看的那一路**
 *   是错的（2026-11-05 普查实测：屏上「GMF_METHOD_RESTORE 行89」= 0 基下标 89 = 物理第 90 行
 *   `FOR LOCAL, 0, CHARANUM`，与 `rand/RandZeroArgChainAuditTest.上报行号是零基下标`、
 *   `fn/PowerFloatRealCorpusTest` 的「SOURCE_DOWNBASE 行625 ↔ 物理 626」、
 *   `RM_G_ID 行256` ↔ `4.980/ERB/リソース管理/ResourceManager.ERB` 物理 257 行 `THROW 資源不足`
 *   三条同口径）。唯一确实喂 1 基的生产者是**只存在于测试里**的文本汇编器
 *   [com.eratw.port.core.code.Assembler]（`Assembler.kt:122` 的 `currentLine = idx + 1`，
 *   与 Disassembler 的自对偶口径），全仓 main 侧零调用点，永远走不到设备；
 *   因此本属性按 0 基这条链归一即可，**不许**为了迁就它去动 [Assembler]。
 *   erbcc 侧同理：产物行号表（`ErbcWriter` 落 `Program.lines`、`ErbcStream.kt:2194` 逐条
 *   `i32()` 原样读回）不带任何偏移，仍是 0 基；它自己的 1 基只出现在构建报告的
 *   装载告警面（`CorpusCompile.kt:308`、`:473` 各一次 `w.line + 1`，符号层则在生产点归一：
 *   `Symbols.kt:912` 的 `lineNo = i + 1`）——那是另一条读数族、每条恰好一个归一点。
 * - [displayLine]：**唯一**的对外 1 基行号出口，屏上/报告/签名一律读它。
 * - [pc]：指令下标；非 VM 产生的错误为 -1。
 *
 * 之所以是 `open`：VM 需要区分"脚本自身错了"（CodeEE）与"VM 保护闸触发"
 * （[com.eratw.port.core.code.StepLimitExceeded] / [com.eratw.port.core.code.CallStackOverflow]），
 * 后者继承本类但仍可被 `catch (CodeEE)` 兜住。**不要**再复制第二份实现。
 */
open class CodeEE(
    message: String,
    val funcName: String? = null,
    val line: Int = -1,
    val pc: Int = -1,
    /**
     * 结构化文案键（i18n，2026-11-07）：非 null 时**上屏层**按当前语言重渲染
     * （`langKey.render(variant, *langArgs)`）；[message] 仍是 zhs 定型串，供日志、
     * 错误报告与去重签名 —— 那些是取证面，不随语言变（AGENTS §6）。
     * 670 处自造中文直打串的收口入口见 [keyed]。
     */
    val langKey: LangKey? = null,
    /** [langKey] 的实参表，个数 = `langKey.argCount`（与定型 [message] 同源同值）。 */
    val langArgs: List<Any?> = emptyList(),
) : RuntimeException(message) {

    /**
     * 屏上/报告用的 1 基行号（无位置 = 负数原样返回，调用方继续按 `< 0` 判「没有位置」）。
     *
     * 这是**全工程唯一一处**内部 0 基 → 对外 1 基的换算入口（另一个消费点在解析消息装配处
     * [erbDisplayLine]，那里调的是同一个函数，不是第二处 `+1`）。所有读 [line] 给人看的
     * 面——失败屏、错误报告正文、上报去重签名——都必须走本属性，不许在打印点自己加一。
     */
    val displayLine: Int get() = erbDisplayLine(line)

    companion object {
        /**
         * 带语言键的构造：`message` 按 zhs 定型（既有行为逐字不变），同时留存键/实参，
         * 供上屏按 `SettingsLanguage` 映射的 [com.eratw.port.core.lexer.LangVariant] 重渲染。
         *
         * 为什么不直接把 message 也换成三语：`CodeEE.message` 进日志、错误报告正文与
         * 上报去重签名（取证面），换语言会让签名漂移；上屏才是唯一需要 i18n 的面。
         */
        fun keyed(key: LangKey, vararg args: Any?): CodeEE =
            CodeEE(key.renderZhs(*args), langKey = key, langArgs = args.toList())
    }
}

/**
 * 内部 0 基行下标 → 给人读的 1 基行号：**全工程唯一的一处换算**。
 *
 * 形状照抄原版：原版不在任何打印点补偏移，而是在**位置对象自己身上**归一 ——
 * `ScriptPosition(string srcFile, int srcLineNo)` 的构造体就是 `LineNo = srcLineNo + 1`
 * （spec: reference/emuera-cs/MinorShift.Emuera.Runtime.Utils/ScriptPosition.cs:15-17），
 * 于是 `new ScriptPosition(filename, eraStreamReader.LineNo)`（spec: ErbLoader.cs:389、
 * ErhLoader.cs:94、ParserMediator.cs:84）交出去 0 基的 `curNo`，之后每一个消费点
 * （`Process.cs:504` 的死循环弹框、`:610` 的 `ErrorFileAndLine`、`:628` 的 `ErrorInFunc`、
 * `:636` 的 `ErrorFuncStack`，模板见 `Lang.cs:2954`「{0}の{1}行目で」、`Lang.cs:3334`
 * 「警告Lv{0}:{1}:{2}行目:{3}」）拿到的都是 1 基，一个 `+1` 都不写。
 * `Process.cs:688` 反过来按 `Skip(LineNo - 1)` 回读原文，正是「此处已是 1 基」的反证。
 *
 * 移植侧的对应物就是这**一个函数**：[CodeEE.displayLine] 调它，解析层的消息装配点也调它。
 * 键面（产物行号表 / `PatchCompiler.lineNo` / 切片与存档键 / `dimLines` 键 / 断点匹配）
 * 一律**不调**它——那些地方要的正是 0 基下标。
 *
 * 负数（-1 = 无位置；`ScriptPosition()` 默认构造同样给 -1，spec: ScriptPosition.cs:9-13）
 * 原样返回，所以消费点的 `>= 0` 判据不变。
 *
 * 兄弟消息面的接线进度（2026-11-05 lane errtext4 收口两条；普查口径按装行点逐个列全）：
 * - 已接：`core/compile/CompileError.kt:24`「编译错误(函数:…, 行:N)」（字段 [CodeEE.line]
 *   仍 0 基，只有消息走本函数）、`core/expr/Parser.kt:18`「解析错误(行:N,列:M)」与
 *   `:382` 的比较运算符告警、`core/stmt/ParseSupport.kt:45`「语句解析错误(行:N,列:M)」。
 *   机器闸 = `vars/RuntimeErrorTextWiringTest` 的「带行号前缀的消息装配点都就地调唯一出口」：
 *   判据是**装行级**——打「行:」前缀的那一行自己必须出现本函数名。先前那版按整文件
 *   `contains` 判，会被 import 或本 KDoc 里的函数名喂绿（把装配点改成自己 `line + 1`
 *   照样 BUILD SUCCESSFUL，2026-11-05 实测撤掉的假闸）。
 * - 不同坐标系、**不该**接本出口：`core/code/Assembler.kt:10`「汇编错误(行:N)」打的是文本 asm
 *   自己的行号，`Assembler.kt:122` 的 `currentLine = idx + 1` 在词法侧就已 1 基，与 ERB
 *   `Statement.line` 的 0 基键面无关，再补一次就多一行 ⇒ 在闸里登记为例外名单项。
 * - 未接：`core/proc/ProcessRuntime.kt:1209-1219` 的当前行通道喂给
 *   `core/fn/PureMethods.kt:1861`「注意: 某文件的第N行」（两文件均非本路文件面）。
 *   原版这几面的模板（`Lang.cs:3334` / `:950`）吃的都是归一后的 1 基值，所以未接的那条比屏幕少一行。
 * 编译面的那一处硬耦合（原按消息里的「行:N」反查 `Statement.line`）**A14 已收口**：
 * `core-vm/src/test/.../compile/CorpusCompileValidator.kt` 的 `structuredLineOf` 改读异常的
 * **结构化字段**（`CompileException.line` / `CodeEE.line` / `LexError.lineNo` /
 * `StmtParseException.line` / `ExprParseException.line`，全部 0 基），消息基（1 基 / 0 基 /
 * 整族换文案）**不再参与键面**，只进展示与签名分组。抗漂移用例 =
 * `CorpusCompileAttributionTest` / `CorpusFailureAttributionTest`（直喂五种消息形状逐条读数级断言）。
 * 语料判闸基线是 0 编译失败，该路径在绿态下本就不触发（2026-11-05 全量语料亲验：4,258 文件 /
 * 117,055 切片 / 失败 0，`toFailure` 只在 catch 编译失败的分支里被调）；绿态不进不等于可以留在
 * 旧写法上——一旦有失败切片，旧写法会因基差一而错指一行。
 * `devtools/erbc-degraded-allowlist.tsv` 的键写明「0 基，与 CompileFailure 口径一致」，
 * 键走的是字段不是消息，所以本波不动它。
 */
fun erbDisplayLine(line0: Int): Int = if (line0 >= 0) line0 + 1 else line0
