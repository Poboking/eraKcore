package com.eratw.port.core.code

import com.eratw.port.core.lexer.LangVariant

/**
 * `core.code` 视角的 CodeEE —— 指向 [com.eratw.port.core.err.CodeEE] 的唯一真源。
 *
 * 用 typealias 而不是再声明一个类：全工程 `catch (CodeEE)` 必须能兜住 code / vars / stmt
 * 抛出的所有脚本错误；两份同名类会让跨包 catch 静默漏掉一半（team-lead 裁决 2026-10-17）。
 */
typealias CodeEE = com.eratw.port.core.err.CodeEE

/**
 * VM 运行期错误的共同基类（都是 CodeEE）。
 *
 * 之所以还要这层：宿主有时需要区分"脚本自身错了"（CodeEE）与"VM 保护闸触发"
 * （死循环告警 / 调用栈溢出），而层数上限在本移植里是引擎保护、不一定对应原版语义。
 */
open class VmException(
    message: String,
    line: Int,
    pc: Int,
    funcName: String?,
) : CodeEE(message, funcName, line, pc)

/**
 * 死循环保护的**触发原因**（ENGFIX-3 · 2026-11-05）。
 *
 * ## oracle（两侧同源，只差一个变量名）
 * * skia `MinorShift.Emuera.GameProc/Process.cs:470-489` 的 `checkInfiniteLoop`：
 *   每 10000 **行**（`:989-992` 的 `state.lineCount % 10000 == 0`，且
 *   `Config.InfiniteLoopAlertTime > 0` 才检查）读一次 `startTime.ElapsedMilliseconds`（:472），
 *   不足阈值直接 return（:473-476）；达阈值 ⇒ 标题 `Lang.MessageBox.InfiniteLoop` +
 *   正文 `Lang.MessageBox.TooLongLoop`（四个实参：文件名 / 行号 / 已执行行数 / 墙钟毫秒，:480-481）
 *   交给 `Dialog.ShowPrompt`（:482）；玩家答「是」⇒ 抛 `CodeEE(Lang.Error.SelectExitInfiniteLoopMB.Text)`
 *   （:484）；答「否」⇒ `state.lineCount = 0` + `startTime.Restart()`（:486-487）继续跑；
 *   **没有可用 UI**（`view.Enabled`，:478；`EmueraConsole.cs:256` 的 `Enabled => window.Created`）
 *   ⇒ 整个 if 块跳过：不弹框、不归零、更不中止。
 * * 官方 `reference/emuera-cs/MinorShift.Emuera.GameProc/Process.cs:493-512` 与 `:1012-1014`
 *   逐字同文（只有 `view` 写作 `console`）。
 *
 * ⇒ **原版从不因「跑了多少条指令」中止脚本**；中止只发生在玩家按下「是」那一次。
 *
 * ## 为什么一个异常类型下还有两种成因
 * 移植层此前自造了一条按步数的硬闸（`02d7faaa` + `1c93a898`，四刀调参
 * 5M→20M→60s→10 亿），把合法重负载判成死循环（真机读旧档弹「启动失败」那一类报告）。
 * 本波把官方语义做出来（墙钟 + 可继续），步数/时长退成**装置预算与极端兜底**；
 * 两条路的宿主收口完全不同：
 * * [PlayerChoseExit] 在原版只是一条普通脚本错误 —— `DoScript` 顶层
 *   `catch (Exception exc)` → `handleException`（skia `Process.cs:454-455`）打到屏上、
 *   **会话与进程都留着**，所以宿主该按「可恢复终局」收，不该上「启动失败」屏。
 * * [DeviceBudget] 是移植层保护，现场取证挂在失败收口那条路上（口径同既有的
 *   `isRecoverableScriptError`），不是官方语义，需 ADR-005 例外登记。
 */
enum class LoopGuardCause {
    /** 玩家在墙钟告警里明确选了「退出/是」—— 官方唯一的中止路径（skia `Process.cs:482-484`）。 */
    PlayerChoseExit,

    /** 移植层预算撞线（[VmConfig.maxStepsPerRun] / [VmConfig.maxRunMillis]），与原版判据无关。 */
    DeviceBudget,
}

/**
 * 死循环告警的**现场**（ENGFIX-3）。
 *
 * 逐字段对应 skia `Process.cs:480-481` 那次格式化的四个实参与两个上下文量：
 * `currentLine.Position.Value.Filename` → [sourceName]、`currentLine.Position.Value.LineNo`
 * → [lineNo]、`state.lineCount` → [linesExecuted]、`startTime.ElapsedMilliseconds` → [elapsedMs]；
 * [alertTimeMs] 是本次触发用的阈值（原版 `Config.InfiniteLoopAlertTime`）。
 *
 * 原版把文案在 `Process.cs` 里就地渲染后才交给 `Dialog.ShowPrompt`；本移植把**文案留在
 * core**（[InfiniteLoopTexts]，逐字照抄原文与译文），宿主只负责画框与收答案 ——
 * 这样日文原文与简中译文在全工程只有一份，不会各画各的（ADR-005）。
 *
 * @property sourceName 当前行的源文件显示名（原版 `LinePosition.Filename` 口径）；
 *   null = 装载产物里没有这条信息（`.erbc` v1 / 内存编译的裸 Program），正文回落 [functionName]
 * @property functionName 当前函数书写名；纯取证用，也参与 [sourceName] 缺失时的正文回落
 * @property lineNo 当前语句的源码行号（正文的第 1 位）；**对外 1 基**，与原版一致 ——
 *   原版在 `ScriptPosition` 构造时就 `+1`（`ScriptPosition.cs:15-17`），弹框那一行
 *   （官方 `Process.cs:504`）拿到的已是 1 基；移植侧走全工程唯一出口
 *   [com.eratw.port.core.err.erbDisplayLine]，而 [StepLimitExceeded.line] 字段仍是 0 基键面
 * @property linesExecuted 自段落起点以来执行过的**语句行数**（正文的第 2 位，原版 `state.lineCount`）
 * @property elapsedMs 自段落起点以来的墙钟毫秒（正文的第 3 位，原版 `startTime.ElapsedMilliseconds`）
 * @property alertTimeMs 触发本次告警的阈值（原版 `Config.InfiniteLoopAlertTime`）；
 *   「为什么这一次会问」的事后取证靠它
 * @property textVariant 宿主画框时实际使用的语言档，原样带回给 VM，用于把
 *   「玩家选了退出」那条 `CodeEE` 的文案渲染成**同一种语言**（口径见 [InfiniteLoopTexts]）
 */
data class InfiniteLoopAlert(
    val sourceName: String?,
    val functionName: String?,
    val lineNo: Long,
    val linesExecuted: Long,
    val elapsedMs: Long,
    val alertTimeMs: Long,
    val textVariant: LangVariant = LangVariant.ZHS,
) {
    /** 对话框标题（`Lang.MessageBox.InfiniteLoop`）。 */
    fun title(variant: LangVariant = textVariant): String = InfiniteLoopTexts.title(variant)

    /** 对话框正文（`Lang.MessageBox.TooLongLoop`，四个占位符原位展开）。 */
    fun message(variant: LangVariant = textVariant): String = InfiniteLoopTexts.message(this, variant)

    /** 本告警的文件位显示名：有源文件名就用它（原版口径），否则回落函数书写名。 */
    val displaySource: String get() = sourceName ?: functionName ?: ""
}

/**
 * 宿主对死循环告警的答复（ENGFIX-3 的**回调切口**返回域）。
 *
 * 三支与 skia 的三个分支一一对应（`Process.cs:478-488`）：
 * * [Exit] ≙ `Dialog.ShowPrompt(...)` 返回 true（玩家按「是」，`Dialog.cs:21-28` 的
 *   `MessageBox.Show(text, title, YesNo) == Yes`）⇒ VM 抛异常；
 * * [Continue] ≙ 返回 false（玩家按「否」）⇒ 行数与计时双双归零（:486-487）；
 * * [NoUi] ≙ `view.Enabled == false`（没有可用 UI）⇒ **什么都不做**：既不弹框、也不归零，
 *   更绝不中止 —— 下一个 10000 行边界再按同样判据看一次墙钟。
 *
 * 缺省答案是 [NoUi]（`VmHost.askInfiniteLoop` 的默认实现）：没接线的装置与宿主因此
 * 与改造前逐条一致，且永不因为「跑太久」被打死。
 */
sealed interface InfiniteLoopAnswer {
    /** 玩家选「继续」：行数与计时双双归零接着跑（skia `Process.cs:486-487`）。 */
    object Continue : InfiniteLoopAnswer

    /** 玩家选「退出」：VM 抛 [StepLimitExceeded]（cause = [LoopGuardCause.PlayerChoseExit]）。 */
    data class Exit(val textVariant: LangVariant = LangVariant.ZHS) : InfiniteLoopAnswer

    /** 宿主没有可用 UI / 没有告警通道：按 skia 的 `view.Enabled == false` 分支，什么都不做。 */
    object NoUi : InfiniteLoopAnswer
}

/**
 * 死循环告警的三段官方文案（标题 / 正文 / 退出错误），**逐字照抄**，含双侧出处行号。
 *
 * 语言档与回落链口径同 `core/lexer/LangText.kt` 类头：内核与契约层用日文原文
 * （[LangVariant.JA]），使用者面用简中译文（[LangVariant.ZHS]）；只做「按序号原位替换」，
 * 不 trim、不改引号形态、不修误译（ADR-005）。英文译文（`lang.emuera-eng.xml`）只登记
 * 出处不收录 —— [LangVariant] 目前没有英文档，收录会造出第二套语言枚举。
 *
 * | 文案 | skia Lang.cs | 官方 Lang.cs | zhs 表 | eng 表 |
 * |---|---|---|---|---|
 * | `MessageBox.InfiniteLoop`（标题） | 3147 | 4102 | 970 | 973 |
 * | `MessageBox.TooLongLoop`（正文） | 3150 | 4106 | 971-973 | 974-976 |
 * | `Error.SelectExitInfiniteLoopMB`（退出） | 2270 | 2934 | 688 | 691 |
 *
 * TODO(verify)：这三条不在 `LangKey` 枚举里 —— 本波文件所有权不含 `core/lexer/LangText.kt`，
 * 也不含 specs 目录下的 error-texts 规约与其对账用例。组长若要把它们并进 `LangKey`，
 * 本对象整体删除、调用点改 `LangKey.某键.render(variant, ...)` 即可，模板与实参序逐字不变。
 */
object InfiniteLoopTexts {

    /** `Lang.MessageBox.InfiniteLoop` 的日文原文（skia Lang.cs:3147 / 官方 Lang.cs:4102）。 */
    const val TITLE_JA: String = "無限ループの可能性があります"

    /** `Lang.MessageBox.InfiniteLoop` 的简体中文译文（lang.emuera-zhs.xml:970）。 */
    const val TITLE_ZHS: String = "可能发生了无限循环"

    /**
     * `Lang.MessageBox.TooLongLoop` 的日文原文（skia Lang.cs:3150 / 官方 Lang.cs:4106）。
     * 四个占位符：第 0 位=文件名、第 1 位=行号、第 2 位=段落内已执行行数、第 3 位=墙钟毫秒。
     */
    const val MESSAGE_TEMPLATE_JA: String =
        "現在、{0}の{1}行目を実行中です。\n最後の入力から{3}ミリ秒経過し{2}行が実行されました。\n処理を中断し強制終了しますか？"

    /**
     * `Lang.MessageBox.TooLongLoop` 的简体中文译文（lang.emuera-zhs.xml:971-973 的三行 CDATA
     * 合并；换行位置与日文原文的两个换行逐行对齐）。
     */
    const val MESSAGE_TEMPLATE_ZHS: String =
        "目前正在执行{0}的第{1}行。\n距离最后的输入经过了{3}毫秒并执行了{2}行。\n中断处理并强制结束吗？"

    /**
     * `Lang.Error.SelectExitInfiniteLoopMB` 的日文原文
     * （skia Lang.cs:2270 / 官方 Lang.cs:2934；英文形见 lang.emuera-eng.xml:691）。
     */
    const val EXIT_JA: String = "無限ループの疑いにより強制終了が選択されました"

    /** `Lang.Error.SelectExitInfiniteLoopMB` 的简体中文译文（lang.emuera-zhs.xml:688）。 */
    const val EXIT_ZHS: String = "可能进入了无限循环因此选择强制终止"

    /** 对话框标题（无占位符）。 */
    fun title(variant: LangVariant): String = if (variant == LangVariant.JA) TITLE_JA else TITLE_ZHS

    /** 对话框正文：四个占位符按 skia `Process.cs:481` 的实参序原位替换。 */
    fun message(alert: InfiniteLoopAlert, variant: LangVariant): String {
        val template = if (variant == LangVariant.JA) MESSAGE_TEMPLATE_JA else MESSAGE_TEMPLATE_ZHS
        return fill(
            template,
            arrayOf(
                alert.displaySource, // 第 0 位：文件名（skia 的 currentLine.Position.Value.Filename）
                alert.lineNo, // 第 1 位：行号
                alert.linesExecuted, // 第 2 位：段落内已执行行数
                alert.elapsedMs, // 第 3 位：墙钟毫秒
            ),
        )
    }

    /**
     * 「玩家选择退出」那条 `CodeEE` 的文案（原版 `Lang.Error.SelectExitInfiniteLoopMB.Text`）。
     *
     * 使用者面缺省 [LangVariant.ZHS]（口径同 `core/lexer/LangText.kt` 类头第 2 条）；
     * 告警答复里带回的语言档会覆盖它，保证「玩家看到的框」与「屏上留下的那句」同语言。
     */
    fun exitError(variant: LangVariant = LangVariant.ZHS): String =
        if (variant == LangVariant.JA) EXIT_JA else EXIT_ZHS

    /**
     * C# 格式化的序号原位替换（与 `LangKey` 的私有 fill 同一实现口径）：
     * 花括号里的数字换成对应实参串，越界实参上屏为空串，非数字的花括号原样保留。
     */
    private fun fill(template: String, args: Array<out Any?>): String {
        val sb = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                var j = i + 1
                while (j < template.length && template[j].isDigit()) j++
                if (j > i + 1 && j < template.length && template[j] == '}') {
                    val index = template.substring(i + 1, j).toInt()
                    // C# string.Format 的 null 实参上屏为空串。
                    sb.append(args.getOrNull(index)?.toString() ?: "")
                    i = j + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}

/**
 * VM 保护闸触发的异常。
 *
 * ## 语义（ENGFIX-3 · 2026-11-05 起）
 * **主语义 = 玩家在死循环告警里选了「退出」**（[LoopGuardCause.PlayerChoseExit]）：
 * 这就是原版的 `CodeEE(Lang.Error.SelectExitInfiniteLoopMB.Text)`（skia `Process.cs:484`），
 * 发生在墙钟越过 `Config.InfiniteLoopAlertTime`、每 10000 行一次的采样点上；
 * 玩家选「继续」时**不产生任何异常**，选不了（没有 UI）时同样不产生。
 *
 * 次语义 = 移植层的 [LoopGuardCause.DeviceBudget]（装置预算 / 极端兜底）：
 * 单次 run 的段落指令数超过 [VmConfig.maxStepsPerRun]，或段落墙钟超过 [VmConfig.maxRunMillis]。
 * 这一条**不是官方语义** —— 官方从不按指令数判死；保留它只为「真无限循环把设备钉死」
 * 兜底，量级必须是正常游戏永不触及的（生产取值见 `GameSession.PRODUCTION_MAX_STEPS_PER_RUN`，
 * 需 ADR-005 例外登记，本波交付报告已提）。
 *
 * ## 为什么两类成因共用一个类型名
 * 全工程的 catch 只认类型不认成因（`app` 的失败屏、`core/diag` 的现场转储、`SweepCore`
 * 的台账分类都按本类型分支）。改名会牵动别人独占的文件面；因此本波只把**语义与文案**换到
 * 官方口径，成因用 [guardCause] 区分，由宿主按 [guardCause] 决定「可恢复终局」还是「失败收口」——
 * 逐行接线清单见本波交付报告。
 *
 * 成因字段为什么不叫 `cause`：`Throwable.getCause()` 已占用这个名字
 * （Kotlin 直接报「hides member of supertype」）。本字段是**死循环闸的成因分类**，
 * 与异常链无关，叫 [guardCause] 反而更准。
 */
class StepLimitExceeded(
    /**
     * 段落内已执行的**指令**数（[LoopGuardCause.DeviceBudget] 的步数闸口径）；
     * 玩家退出路径给的是本次 run 的累计指令数（只为台账有个量级，不参与判定）。
     */
    val steps: Long,
    line: Int,
    pc: Int,
    funcName: String?,
    /**
     * 抛点就地还原的脚本调用链（入口帧 → 当前帧，` → ` 连接）；null = 未知（测试/手工构造）。
     *
     * 之所以进 message：宿主的失败屏只透传 [message]（BootFailures.classify 的 OTHER 分支），
     * line/pc/funcName 是结构化字段、屏幕拿不到 —— 2026-10-21 真机首跑撞闸时失败屏
     * 只剩一句「疑似死循环」，循环位置只能靠事后反射猜（BOARD「行7」之争的根源）。
     *
     * 玩家退出路径**不带**这个后缀：那句必须是官方 `CodeEE` 的原文上屏（skia `Process.cs:484`），
     * 现场由 [alert] / [line] / [pc] 这些结构化字段承担。
     */
    val callChain: String? = null,
    /**
     * 抛点就地导出的帧局部变量快照（[com.eratw.port.core.vars.VariableStore.frameSnapshotLines]）；
     * 空表 = 宿主没有变量池（测试宿主 / 纯字节码测试）。
     *
     * **必须在抛点取**：`Vm.run` 的 finally 在异常离栈时回卷调用栈，宿主事后再读
     * LOCAL/ARG 只会拿到「变量 LOCAL 只能在函数内使用」（2026-10-25 红 J 取证缺口）。
     */
    val frameSnapshot: List<String> = emptyList(),
    /**
     * 触发本闸的**墙钟毫秒**（段落口径：自上一次让出边界起）；-1 = 纯步数闸触发（既有路径）。
     *
     * 为什么要有这一维（2026-10-29）：原版根本没有「每段指令上限」这种东西 ——
     * skia `Process.cs:470-489` 每 10000 行看一次**墙钟**，超过
     * `Config.InfiniteLoopAlertTime`（默认 5000 ms）就弹框问「是否中止」，玩家选「否」
     * 就 `state.lineCount = 0; startTime.Restart()` 继续跑。于是原版读长档的恢复演出
     * 只是「卡很久」，从不中止；移植层拿步数硬闸当同一件事，就把合法重负载判成了死循环
     * （BOARD 2026-10-27 的 `EVENTTRAIN → CHARA_MOVEMENT` 2000 万步拦停即此）。
     * 时间预算与步数预算共用本异常类型（宿主失败屏只认 `message`），但文案必须能区分，
     * 否则玩家看到「执行步数超过上限」去数步数，永远对不上「我只是读了个档」。
     */
    val elapsedMs: Long = -1L,
    /** 触发成因；缺省 [LoopGuardCause.DeviceBudget]（既有构造点与既有文案逐条不变）。 */
    val guardCause: LoopGuardCause = LoopGuardCause.DeviceBudget,
    /**
     * 死循环告警的现场（文件名 / 行号 / 行数 / 墙钟毫秒 / 阈值）；
     * null = 非告警路径（装置预算）。宿主的取证与失败报告按这一份说「为什么问、玩家答了什么」。
     */
    val alert: InfiniteLoopAlert? = null,
) : VmException(
    when (guardCause) {
        // 官方唯一中止路径：文案 = Lang.Error.SelectExitInfiniteLoopMB（skia Process.cs:484）。
        LoopGuardCause.PlayerChoseExit -> InfiniteLoopTexts.exitError(
            alert?.textVariant ?: LangVariant.ZHS,
        )

        LoopGuardCause.DeviceBudget -> if (elapsedMs < 0) {
            "执行步数超过上限($steps)，疑似死循环" + if (callChain != null) " @ $callChain" else ""
        } else {
            "执行时间超过上限(${elapsedMs}ms，已执行 $steps 步)，疑似死循环" +
                if (callChain != null) " @ $callChain" else ""
        }
    },
    line, pc, funcName,
)

/** 调用栈超过 [VmConfig.maxCallDepth]。 */
class CallStackOverflow(
    val depth: Int,
    line: Int,
    pc: Int,
    funcName: String?,
    /** 同 [StepLimitExceeded.callChain]：抛点活链，失败屏只认 message。 */
    val callChain: String? = null,
) : VmException(
    "调用栈超过上限($depth)" + if (callChain != null) " @ $callChain" else "",
    line, pc, funcName,
)
