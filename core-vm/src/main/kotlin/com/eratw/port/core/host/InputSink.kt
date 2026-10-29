package com.eratw.port.core.host

/**
 * 输入请求描述 —— 逐字段移植原版 InputRequest（spec: MinorShift.Emuera.Runtime/InputRequest.cs:3-47）。
 * 语句层只负责按原版 DoInstruction 填字段，等待与 UI 呈现归宿主。
 *
 * ## 两个正交轴：等待形态 [RequestForm] × 取值类型 [InputType]
 *
 * 原版用**一个** 9 项枚举 `InputType`（InputType.cs:3-14）同时表达两件不同的事：
 * 「这次等待吃什么、超时之后推不推进」（形态）和「交付写进 RESULT 还是 RESULTS」（取值类型）。
 * 本移植把它拆成两个轴：[RequestForm] 承接形态、[InputType] 承接值通道。
 * 拆开的直接后果是原版按形态分派的三条纪律能被逐条实现：
 * 1. **超时是否推进**（`endTimer` → `RunEmueraProgram("")`，EmueraConsole.cs:1040-1082）：
 *    形态不取值的（EnterKey/AnyKey/Void）与取字符串的（StrValue/StrButton/AnyValue）**照常推进**；
 *    取整数且**无默认值**的不推进（空文本在 :1119-1128 落到 `long.TryParse` 失败分支，
 *    `doInputToEmueraProgram` 返回 false ⇒ `RunEmueraProgram` 于 :1094 直接不收尾，
 *    等待原地继续 ⇒ 原版「INT 无默认值时超时死等」）。
 * 2. **提交是否被消费**：Void 形态下键盘提交整体早退（:1417-1420），既不推进也不出队。
 * 3. **交付写哪个槽**（`NeedValue` 与 `doInputToEmueraProgram` 的 switch，:1112-1288）：
 *    非取值形态什么都不写（switch 里没有对应 case，直接落到 :1278 的 `stopTimer` + :1287 `return true`），
 *    AnyValue 按能否解析成整数**二选一**写 RESULT 或 RESULTS（:1256-1274）。
 *
 * 与原版 9 项 `InputType` 的映射（形态位 + 值通道位）：
 * - `IntValue` / `StrValue` → [RequestForm.VALUE] + [InputType.INT] / [InputType.STR]
 *   （INPUT/INPUTS/TINPUT/TINPUTS 系，FunctionIdentifier.cs:827-1099）；
 * - `IntButton` / `StrButton` → [RequestForm.VALUE] + INT/STR（BINPUT 系，:2113-2529；
 *   按钮候选筛选属绘制层，本层与 INT/STR 共用等待路径）；
 * - `EnterKey` → [RequestForm.ENTER_KEY]（EmueraConsole.cs:885；TWAIT 第 2 参为 0 的第二次等待，
 *   FunctionIdentifier.cs:814-823）；WAIT 系仍走 [InputSink.readAnyKey]；
 * - `AnyKey` → [RequestForm.ANY_KEY]（:889，WAITANYKEY 与 PRINT 家族 W 后缀经 [InputSink.readAnyKey]）；
 * - `Void` → [RequestForm.VOID]（:1417-1420 的早退；TWAIT 第 2 参非 0 时用，FunctionIdentifier.cs:818-821）；
 * - `AnyValue` → [RequestForm.ANY_VALUE]（INPUTANY，FunctionIdentifier.cs:2095-2110 + 交付分支
 *   EmueraConsole.cs:1256-1274）；
 * - `PrimitiveMouseKey` → [RequestForm.PRIMITIVE_MOUSE_KEY]（INPUTMOUSEKEY，
 *   FunctionIdentifier.cs:2067-2093 + 超时交付 EmueraConsole.cs:1044-1056）；
 * - `IsSystemInput`（InputRequest.cs:15）不占形态位：系统流程的整数等待由
 *   `core/proc/ConsoleBeginHost.kt` 的 `await*` 走 [RequestForm.VALUE] + INT，
 *   原版分流点是 `doInputToEmueraProgram` 的 :1129-1132 / :1199-1201（`InputSystemInteger`）。
 *
 * TODO(verify)：`PRIMITIVE_MOUSE_KEY` 的原版推进来源是鼠标/按键原始事件
 * （EmueraConsole.cs:1290-1346 的 `MouseWheel`/`MouseDown`/`PressPrimitiveKey` → `InputMouseKey`），
 * 本移植的提交队列只有「一条文本」这一维，事件 → 推进的接线仍缺（现场状态钩子见 [InputSink.mouseState]）。
 * 另：原版 Void 下**鼠标改写**的提交（`changedByMouse == true`）会推进并把文本换成空串后 `i--` 重放
 * （:1406-1408 + :1444-1448），键盘提交则完全忽略（:1417-1420）；[com.eratw.port.core.proc.QueueInputSink]
 * 的队列元素没有 `changedByMouse` 这一维，故本层按键盘路径统一「忽略且不消费」——
 * 需要复刻鼠标那一路时得先给提交加维度（属宿主接线，见本路交付报告）。
 */
data class InputRequest(
    /** 输入目标类型：整数（INPUT/TINPUT）、字符串（INPUTS/TINPUTS）、回车（WAIT 系）。 */
    val type: InputType,
    /**
     * 等待形态（原版 `InputType` 的形态位）；缺省 [RequestForm.VALUE] = 按 [type] 取值，
     * 与既有 INPUT/TINPUT/BINPUT 系构造点口径一致。
     */
    val form: RequestForm = RequestForm.VALUE,
    /** 单键输入（ONEINPUT/TONEINPUT/ONEINPUTS/TONEINPUTS）。 */
    val oneInput: Boolean = false,
    /** 是否提供默认值。 */
    val hasDef: Boolean = false,
    val defInt: Long = 0L,
    val defStr: String = "",
    /** 限时毫秒（TINPUT 系；-1 = 不限时）。 */
    val timeLimit: Long = -1L,
    /** 是否显示剩余时间（TINPUT 第 3 参数）。 */
    val displayTime: Boolean = false,
    /** 超时提示语（TINPUT 第 5 参数，缺省用配置 TimeupLabel）。 */
    val timeUpMessage: String = "",
    /** 是否接受鼠标输入（第 2 参数）。 */
    val mouseInput: Boolean = false,
    /**
     * 发起这次等待的**脚本行标识**（原版 `Process.getCurrentLine` 的身份位）。
     *
     * `EmueraConsole.newGeneration()` 用 `process.getCurrentLine != lastInputLine`
     * 判断「脚本是否走到了另一行」（EmueraConsole.cs:789-797）；按钮世代推进依赖它，
     * 所以等待请求必须把当前脚本行带下来。约定：同一函数内稳定、换行即变、`-1` = 未知
     * （未知时由控制台退回行水位近似，见 `LineBufferConsoleSink.noteInputWait`）。
     */
    val scriptLine: Long = -1L,
) {
    enum class InputType { INT, STR }

    /**
     * 等待形态 —— 原版 `InputType`（InputType.cs:3-14）里「等什么东西」那一半。
     * 宿主按它决定：超时推不推进、提交消不消费、交付写哪个槽。
     */
    enum class RequestForm {
        /** IntValue / StrValue / IntButton / StrButton：按 [InputRequest.type] 取值并交付。 */
        VALUE,

        /** EnterKey：等一次回车，文本不取值（EmueraConsole.cs:885、:1421-1424）。 */
        ENTER_KEY,

        /** AnyKey：等任意一次按键（在本移植的队列模型里同样是一条提交），文本不取值（:889）。 */
        ANY_KEY,

        /** Void：只等时限，键盘提交既不推进也不消费（:1417-1420）。 */
        VOID,

        /** AnyValue（INPUTANY）：解析得动就写 RESULT，写不动就写 RESULTS（:1256-1274）。 */
        ANY_VALUE,

        /** PrimitiveMouseKey（INPUTMOUSEKEY）：超时以事件类型 4 交付（:1044-1056）。 */
        PRIMITIVE_MOUSE_KEY,
    }

    /**
     * 原版 `InputRequest.NeedValue`（InputRequest.cs:31-41）：只有取值形态需要值。
     * 消息跳过期的自动推进 while（EmueraConsole.cs:1451-1455）与超时后的鼠标复位（:1069-1079）
     * 都按这一位分派；语句层用它决定「跳过态下这次等待要不要真等」。
     */
    val needValue: Boolean
        get() = when (form) {
            RequestForm.VALUE, RequestForm.ANY_VALUE, RequestForm.PRIMITIVE_MOUSE_KEY -> true
            RequestForm.ENTER_KEY, RequestForm.ANY_KEY, RequestForm.VOID -> false
        }
}

/**
 * 输入结果。原版由 EmueraConsole.WaitInput 直接写 VEvaluator.RESULT/RESULTS；
 * 本层改为显式返回值，由语句层写入变量（保持 sink 无变量依赖）。
 *
 * 两个 `writes*` 位对应原版 `doInputToEmueraProgram` 各形态到底调不调
 * `process.InputInteger` / `process.InputString`：非取值形态两个都不写
 * （EmueraConsole.cs:1112-1288 的 switch 里没有对应 case），
 * AnyValue 二选一（:1256-1274），其余取值形态按 [InputRequest.type] 写一个。
 * 缺省两个都为 true，使既有「INT 请求只读 [intValue]、STR 请求只读 [strValue]」的宿主实现
 * 与测试桩不必跟着改。
 */
data class InputOutcome(
    val intValue: Long = 0L,
    val strValue: String = "",
    /**
     * 限时输入超时（ISTIMEOUT 伪变量取值来源）。spec: `endTimer` 置 `isTimeout`
     * （EmueraConsole.cs:1043）→ 伪变量 `ISTIMEOUT` 读 `Console.IsTimeOut`
     * （VariableData.cs:1075-1087 + EmueraConsole.cs:441）。
     * **粘滞**：一次限时等待内计时器一旦越过截止点，之后即使靠提交推进，本位仍为真
     * （原版 `isTimeout` 字段在 :1278 的 `stopTimer` 里不清零）。
     */
    val isTimeout: Boolean = false,
    /** 本次交付是否写 RESULT（原版 `process.InputInteger`，Process.cs:404-408）。 */
    val writesInt: Boolean = true,
    /** 本次交付是否写 RESULTS（原版 `process.InputString`，Process.cs:432-436）。 */
    val writesStr: Boolean = true,
)

/**
 * 输入宿主接口。WAIT/INPUT 家族的全部等待动作。
 *
 * [readAnyKey] 对应 EmueraConsole.ReadAnyKey(anykey, stopMesskip)：
 * - WAIT/FORCEWAIT：anykey=false，FORCEWAIT 额外 stopMesskip=true（禁止消息跳过中断）；
 * - WAITANYKEY：anykey=true；
 * - PRINTW/PRINTDATAW 的 W 后缀等待也走这里。
 */
interface InputSink {
    fun readAnyKey(anyKey: Boolean = false, stopMesSkip: Boolean = false)

    /** 阻塞等待一次输入并返回结果。 */
    fun waitInput(request: InputRequest): InputOutcome

    /**
     * 鼠标现场宿主钩子（**新增于 M4-I i-mouse，带缺省实现，既有实现方无需改动**）。
     *
     * 用途：让输入侧（`INPUT` / `WAITANYKEY` 的 `mouseInput` 分支、平台的指针事件）
     * 与 MOUSE 族函数共用**同一份**现场状态，避免出现「事件写一套坐标、函数读另一套」。
     *
     * 约定：返回的实例必须与 `store.aux` 的 [MOUSE_AUX_SLOT] 槽是同一个对象
     * （用 [mouseStateOf] 取即可，它幂等）。fn 层只能经 aux 槽读到活状态，
     * 所以宿主接线时**先把同一实例挂进 aux 槽**，本钩子只是给 sem/平台侧的取用入口。
     *
     * 缺省 null = 该宿主不提供鼠标现场。原版在没有窗口时
     * `GetMousePosition()` 返回 (0,0)（spec: GameView/EmueraConsole.cs:2259-2262），
     * MOUSE 族按 aux 槽的缺省值给出同一读数，不抛错。
     */
    fun mouseState(): MouseState? = null

    /**
     * 取出**最近一次被消费的提交**的 `keySkip` 标志（`PressEnterKey(keySkip, ...)` 的第一参，
     * `EmueraConsole.cs:1371-1373`），读过即清；没有待取标志时返回 false。
     *
     * 语义归属在 sem 层：提交被消费后把标志落到 `ExecContext.mesSkip`（跳过消息），
     * 到达需要取值的输入前再清零（`EmueraConsole.cs:1451-1456` 的输入循环）。
     * 缺省实现返回 false——不区分提交来源的宿主（无宏/无 Esc 的测试装置）天然无跳过。
     */
    fun consumeMesSkip(): Boolean = false
}

/** 无操作输入宿主：等待立即返回默认值。 */
object NoopInputSink : InputSink {
    override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) {}
    override fun waitInput(request: InputRequest): InputOutcome = InputOutcome()
}
