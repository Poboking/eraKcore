package com.eratw.port.core.proc

import com.eratw.port.core.diag.LockWatch
import com.eratw.port.core.diag.PageFunctionTrail
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import java.util.concurrent.locks.ReentrantLock

/** 输入队列已关闭后仍发起等待（宿主销毁/退出游戏时用来打断阻塞的脚本线程）。 */
class InputSinkClosedException(
    message: String = "输入队列已关闭，不再接受等待",
) : IllegalStateException(message)

/**
 * 生产输入宿主：**阻塞队列**实现的 [InputSink]，按**等待形态**分派交付（[InputRequest.RequestForm]）。
 *
 * ## 模型
 *
 * 队列里放的是「一次回车提交的原始文本」——就是原版 `PressEnterKey(keySkip, input, changedByMouse)`
 * 拿到的那一行（`EmueraConsole.cs:1371-1374` 的输入循环入口）。UI/触屏/宏都只需
 * 「把用户敲的这条文本推进来」这一个动作；等待侧的形态判定（吃不吃这条提交、超时推不推进、
 * 交付写哪个槽）全在 [waitInput] 里按原版规则做，UI 不参与解释。
 *
 * 形态分派表（原版两处判定点：`PressEnterKey` 的入口早退 :1412-1448、
 * `doInputToEmueraProgram` 的 switch :1112-1288、`endTimer` 的超时收尾 :1040-1082）：
 *
 * | 形态 | 一条提交 | 超时 | 交付 |
 * |---|---|---|---|
 * | VALUE + INT | 空且非计时中取 `DefIntValue`；解析失败**丢弃继续等** | 有默认值 ⇒ 交付默认值；**无默认值 ⇒ 死等** | RESULT |
 * | VALUE + STR | 空且非计时中取 `DefStrValue`，否则原文（含空串） | 恒推进（默认值或空串） | RESULTS |
 * | ENTER_KEY / ANY_KEY | 恒推进，文本忽略 | 恒推进 | **什么都不写** |
 * | VOID | **丢弃、不推进**（早退 :1417-1420） | 恒推进 | 什么都不写 |
 * | ANY_VALUE | 恒推进；解析得动写 RESULT，否则写 RESULTS | 推进并写空串到 RESULTS | 二选一 |
 * | PRIMITIVE_MOUSE_KEY | 恒推进（switch 无 case ⇒ 不写值） | 以事件类型 4 交付 | RESULT:0 = 4 |
 *
 * ## 遮罩取色投喂（INPUT(6/3,color) 消费面，2026-11-04 W6 接线）
 *
 * [submitMappedColor] 把「长按取色」作为一条**独立提交**入队（不走 [submit] 的文本通道：
 * `oneInput` 截断、`\e` 跳过标记、数值解析对它都无意义）。原版没有「投喂」这一步——
 * PC 上鼠标按下时窗口侧直接写变量（`MainWindow.cs:795` 的 `InputInteger(6L, GetMappedColor)`
 * 与 `:828` 的 `InputInteger(3L, ...)`），写值与推进是同一个事件；本移植的队列只有
 * 「一条提交」这一维，色必须作为一条提交入队才能被等待方吃掉。交付按形态分派
 * （[interpretMappedColor]，逐形态证据见该方法）：
 *
 * | 等待形态 | 色条目的交付 | 原版对应 |
 * |---|---|---|
 * | PRIMITIVE_MOUSE_KEY（INPUTMOUSEKEY） | 推进，色走 RESULT:0 | `MainWindow.cs:795` 写 RESULT:6（忠实槽位归 sem 接线，见交付） |
 * | VALUE + mouseInput（INPUT 系带鼠标） | 推进，INT → RESULT:0 / STR → RESULTS 十进制串 | `MainWindow.cs:828` 写 RESULT:3（同上） |
 * | ENTER_KEY / ANY_KEY（WAIT 系） | 只推进不写值 | `MainWindow.cs:806-821` 左键即回车，色不写 |
 * | 其余（VALUE 无鼠标 / ANY_VALUE / VOID） | 丢弃、不推进、不留给下次等待 | `MainWindow.cs:788-860` 三个分支都要求等待态匹配，无支可落 |
 *
 * 槽位偏差（色走 RESULT:0 而非原版 RESULT:6/3）仍是本刀的**登记项**：色值本身仍走交付
 * 主通道，忠实槽位（RESULT:6/3）要由投喂侧改填 [InputOutcome.resultIntSlots] 的对应下标
 * —— [InputOutcome] 已扩成按槽索引的多槽交付（见该文件 KDoc），通道不再是卡点。
 * 坐标换算半段在 `MappingColorGeometry`（render-canvas，与绘制逐式一致），
 * 手势半段在 MainActivity 长按段。
 *
 * ## 画面按钮点击（带鼠标 INPUT 的 `RESULT:1`，玩家子选单案修复）
 *
 * [submitButtonClick] 把「命中了一个画面按钮」作为一条提交入队。按钮值仍走文本通道
 * （`PressEnterKey(keySkip, selectedString, changedByMouse: true)`，
 * `MainWindow.cs:848`），但原版在同一次鼠标按下里**还写了三个附加槽**：
 * `InputString(1L, selectedString)` → `RESULTS:1`（`:834`）、
 * `InputInteger(3L, 取色)` → `RESULT:3`（`:828`）、`InputInteger(2L, 修饰键)` → `RESULT:2`
 * （`:832`），以及最关键的 `InputInteger(1L, 1L)` → **`RESULT:1 = 1`**（左键，`:847`；
 * 右键 2 / 中键 3，`:837`/`:842`）。空白处点击那一路（`:851-889`）同样写 `RESULT:1`。
 *
 * 语料 `QOL_USERCOM.ERB @COM_OPTION` 的 `INPUT -1, 1` 之后读
 * `MOUSE_BTN = RESULT:1`，只有等于 1 才 `SETVAR @"COM_ALTER_{COM_ID}"`；
 * `KOJO_MESSAGE.ERB:1849` 的同类弹窗同构。旧队列把按钮点击和键盘回车都当作
 * 「一条纯文本」，`RESULT:1` 永远是 0 ⇒ 玩家看到黄框/输入被推进，但子选项不变。
 *
 * 因此 [Entry.mouseButton] 只在这一条形态闸内生效（见交付消费点的 [applyMouseSlots]）：
 * `RequestForm.VALUE` + `mouseInput == true` 且这条提交确实来自画面按钮 ⇒ 交付主值的同时
 * 附加 `RESULT:1` 与 `RESULTS:1`。**键盘提交、不带鼠标的 INPUT、WAIT 系、
 * ANY_VALUE / PRIMITIVE_MOUSE_KEY、超时收尾一律不附加**（原版 `:824`/`:851` 都要求
 * `IsWaintingInputWithMouse`；`:806-821` 的 WAIT 系左键只是一次回车，不写值）。
 * 取色 `RESULT:3` 与修饰键 `RESULT:2` 仍按既有登记项留在投喂侧，本口只补按钮位与按钮文本。
 *
 * ## CBG 按钮值投喂（`InputResult5` 消费面，2026-11-06 W7 接线）
 *
 * [submitCbgButtonValue] 是**取色图**（`CBGSETBMAPG` 设的那张）命中值的入口，与上面的
 * 遮罩取色是两个不同的值域：原版鼠标按下时先用 `cbgButtonMap` 在点击点取色，
 * `A == 255` 才算命中、值 = `pixel.ToArgb() & 0xFFFFFF`（`EmueraConsole.cs:1309-1320`），
 * 随后 `InputMouseKey` → `Process.InputResult5` 整组写 `RESULT:0..5`
 * （`:1327-1337`、`Process.cs:393-401`）。闸口 = `IsWaitingPrimitive`
 * （`:1300-1305`）⇒ **只有 INPUTMOUSEKEY 等待会消费它**，其余形态丢弃并计
 * [dropCbgButtonCount]。槽位偏差与卡点（值落 RESULT:0，原版落 RESULT:4）见
 * [submitCbgButtonValue] 的 KDoc。
 *
 * 线程模型：单生产（UI 线程）/ 单消费（VM 线程），内部用一把 [ReentrantLock] + 一个条件变量，
 * 不做自旋、不读墙钟（超时只用单调时钟 [timeSource]，默认 `System.nanoTime`；
 * 测试注入假计时源即可零墙钟地驱动截止分支）。
 * **锁序表不在本类**：全仓唯一的锁序声明处在 `core/diag/LockWatch.kt` 的类 KDoc
 * （本类的 [ReentrantLock] 是表里的第 2 条边：`GameHost.pendingLock → 本锁`，
 * 由 app 侧 `deliver` 调 [submit] 形成；改任何取锁顺序先去那张表）。
 * 宿主回调 [onTurnReady] 一律在**输入锁之外**触发（[settleTurnStart] 锁内结算、调用方锁外交付）：
 * 回调属于宿主任意代码（落盘、堆采样），若在锁内跑，UI 线程的下一次 [submit] 会被挡在同一把锁上
 * （2026-10-30 玩家 ANR：移动后主线程阻塞超时弹「无响应」）。
 *
 * ## 与原版逐条对齐的行为
 *
 * 1. **无请求方时提交不丢**：原版控制台把输入立刻交给 `doInputToEmueraProgram`
 *    （`EmueraConsole.cs:1108-1260`），没有「排队」概念，因为输入永远发生在等待之后；
 *    本类允许宿主抢跑提交（触屏连点、宏回放），先入队、后到者取。队首就是最早那次回车。
 *    **与原版的一处差异（已在宿主接线收口，2026-10-25）**：原版 `PressEnterKey` 在
 *    `state` 为 `Running`/`Initializing`（脚本执行中）时**直接丢弃**这次按键（:1374-1377）
 *    ⇒ 「脚本跑着的时候按的回车」不会落到下一次等待；本类队列自身没有控制台状态这一维，
 *    供测试/装置先入队后等待（BootPlayChain 的 UI 代理依赖这一形态）。**生产宿主**
 *    （GameHost.submitInternal）现按 [isAwaiting] 预筛：无等待方时文本丢弃、
 *    `keySkip` 走 [noteSkipIntent] 保住跳过意图 —— 与原版「入口置 MesSkip 后早退」同序。
 *    真机教训（未预筛时代）：世界初始化页一跑十几秒，期间玩家输入全部入队、被**下一个
 *    界面**的 INPUT 吃掉，值跨屏错位直接把 qol_update.ERB:358 的 CFLAG:(V-2000)/10 打越界。
 *    预筛仍有 µs 级竞态窗口（isAwaiting 读与 submit 之间等待方可能离开），与原版
 *    「事件到达时 state 恰好翻转」的窗口同构，不追求完全等价。
 *    **两处例外**：① VOID 形态下到达的提交按 :1417-1420 **丢弃**（原版那次
 *    回车根本没进程序，文本只留在输入框里，不会被下一次等待自动吃掉）；② 越过截止点之后
 *    才到达的提交留给**下一次**等待（原版 `endTimer` 已用空文本推进并 `stopTimer`，
 *    后到的按键属于下一次 `WaitInput`，见第 6 条）。
 * 2. **`oneInput` 截断**：`inputReq.OneInput && text.Length > 1` ⇒ 只留第 1 个字符
 *    （`EmueraConsole.cs:1440-1443` 的 `text.Remove(1)`）；截断发生在**取值之前**，
 *    与数值/字符串类型无关。
 * 3. **空行取默认值**：`string.IsNullOrEmpty(str) && inputReq.HasDefValue && !IsRunningTimer`
 *    ⇒ 整数取 `DefIntValue`（:1119-1124）、字符串取 `DefStrValue`（:1190-1194）。
 *    `IsRunningTimer` 为真（限时等待进行中且尚未超时，:371-381）时空行**不**取默认值：
 *    整数解析失败丢弃该次提交继续等（:1125-1128），字符串按空串提交（:1190-1204）。
 * 4. **数值输入解析失败 = 丢弃该次提交并继续等**：原版 `long.TryParse` 失败直接 `return false`
 *    （:1125-1128），控制台**不离开** WaitInput 状态、不改 `RESULT`。这里同样丢弃并重新等待；
 *    队列空了就继续阻塞（不是伪造 0，也不是抛错）。
 * 5. **非取值形态认任何一次提交**：原版 `ReadAnyKey` 只是把 `inputReq.InputType` 置成
 *    `EnterKey`/`AnyKey` 后等下一次 `PressEnterKey`（:874-895），输入文本被忽略；
 *    `doInputToEmueraProgram` 的 switch 没有这两项的 case ⇒ 直接落到 :1278 的 `stopTimer`
 *    与 :1287 的 `return true`（推进但不写值）。因此 ENTER_KEY / ANY_KEY 对 [waitInput]
 *    **不区分提交内容**，出队即返回；[readAnyKey] 是同一条路的无时限版本（WAIT 家族用）。
 *    `anyKey` / `stopMesSkip` 只影响消息跳过与提示，不影响等待条件（:1451-1456 的
 *    `MesSkip` 循环是控制台侧的事）。
 * 6. **限时输入超时**：`Timelimit > 0` 时按毫秒截止；超时把本次等待的 `timerFired` 置真
 *    （对应 `endTimer` 的 `isTimeout = true`，:1043）后按形态决定是否推进 ——
 *    原版 `endTimer` 调 `RunEmueraProgram("")`（:1068），空文本经 `doInputToEmueraProgram`：
 *    整数有默认值 ⇒ 交付 `DefIntValue`（:1119-1124，此时 `IsRunningTimer` 已为假）；
 *    整数**无**默认值 ⇒ `long.TryParse("")` 失败返回 false ⇒ **不推进，原地继续等**；
 *    字符串 ⇒ 交付 `DefStrValue` 或空串（:1190-1204）；非取值形态 ⇒ 无 case ⇒ 推进。
 *    `timerFired` 在一次等待内**粘滞**：死等之后靠提交推进时，[InputOutcome.isTimeout] 仍为真
 *    （原版 `stopTimer`（:1035-1038）不清 `isTimeout`）。
 *
 *    **按键与截止点的先后**：每条提交带按下时刻（[Entry.atNs]）。队首的按下时刻不晚于截止点
 *    ⇒ 原版同一 UI 线程按事件到达序处理，按键先于计时器 tick ⇒ `doInputToEmueraProgram`
 *    收下它并在 :1278 `stopTimer` ⇒ 这一次**不算超时**（`isTimeout` 保持假，直到下一次
 *    `setTimer` 才改写）。反之，截止点先到 ⇒ 先交超时结果（默认值/空串/推进），那条后到的
 *    提交**不出队**，留给下一次等待 —— 原版 `endTimer` 已经推进完，后到的 Enter 落在新的
 *    `WaitInput` 上（:1040-1082 与 :1371-1374 的事件序）。
 * 7. **超时提示语**：`endTimer` 在推进前先按 `DisplayTime` 决定是改写最后一行还是新起一行
 *    打印 `TimeUpMes`（:1058-1065）。那是绘制层动作，本类不打印，只把形态与
 *    [InputRequest.timeUpMessage] 原样留在 [awaiting] 快照里供宿主取用。
 * 8. **关闭唤醒**：原版退出时 `state = ConsoleState.Quit` 并 `window.Close()`
 *    （:1378-1389、:1390-1401），等待方永远不会再被喂输入。本类用 [close] 等价地打断等待：
 *    已入队的文本保留在队列里但不再被消费，等待方抛 [InputSinkClosedException]。
 *    VOID + `Timelimit <= 0`（例如 `TWAIT 0, 1`）在原版**没有任何键盘路径能推进**
 *    （:1417-1420 早退 + 无计时器 ⇒ 只有鼠标改写的提交或关窗能收；语料普查 0 命中），
 *    本类照抄为无限等（ADR-005），只能由 [close] / interrupt 打断。
 * 9. **谁吃、吃几次、多余的去哪**：一次等待只吃**一条**能推进的提交（[readAnyKey] 同样一条）；
 *    被丢弃的提交（数值解析失败、VOID 期间的按键）算「吃掉了但没推进」，不再回到队里；
 *    一次等待结束后队列里剩下的条目属于**后续**等待，不会被当前这次补吃 —— 与原版
 *    「一条 Enter 事件只跑一次 `doInputToEmueraProgram`，推进后 state 变了，后面的按键落到
 *    新的 WaitInput」一致（`EmueraConsole.cs:1456-1470` 的循环在 `state != WaitInput` 时 break）。
 * 10. **输入流水读数（观察面，不是语义）**：队列之外另记一份**只增不减**的流水，用来在真机上
 *    区分「Android MotionEvent → 宿主 submit → 入队 → 等待方消费」这四层断在了哪一层
 *    （release 包拿不到 logcat、调试通道 `DevTools` 只在 debug 包，所以账必须记在 sink 实例
 *    自己身上，谁要读都能读到）。口径：
 *    - **seq**：[submit] 在输入锁内自增的单调编号（从 1 起），随 [Entry] 走完整个生命周期。
 *      队列已关闭时的提交（[submit] 返回 false）不占号；[noteSkipIntent] 不是提交，也不占号。
 *    - [lastSubmittedSeq] / [lastConsumedSeq]：最后一条**入队** / 最后一条**被等待方吃掉并推进**
 *      的编号。`con` 只认推进的那一条：出队后没能交付（整数解析失败）记成 `drop`，不算 `con`。
 *    - [dropVoidCount] + [lastDropVoidSeq]：VOID 形态早退丢弃（`EmueraConsole.cs:1417-1420`，
 *      即 [drainIgnored] 那一路）。
 *    - [dropParseCount] + [lastDropParseSeq]：取值形态的整数解析失败丢弃（`EmueraConsole.cs:1125-1128`）。
 *      两类**分开计数**、各带最后一条的编号；[droppedCount] 只是两者之和（便利读数，不分类）。
 *    - [dropColorCount] + [lastDropColorSeq]：遮罩取色条目被等待形态拒绝丢弃
 *      （[interpretMappedColor] 的丢弃支，见类 KDoc「遮罩取色投喂」节）。
 *    - [dropCbgButtonCount] + [lastDropCbgButtonSeq]：CBG 按钮值条目被等待形态拒绝丢弃
 *      （[interpretCbgButton] 的丢弃支，见类 KDoc「CBG 按钮值投喂」节）。
 *    - [recentInputTrail]：定长 [trailCapacity] 的环形窗口近期摘要，形如
 *      `#12 enq len=3 skip=false` / `#12 con @wait VALUE/INT` / `#13 drop(void)` /
 *      `#13 drop(parse len=3)` / `tmo @wait VALUE/INT`（超时推进没有提交可指 ⇒ 不占 seq），
 *      写满后**新值覆盖最旧**、**永不无限增长** —— 本仓的 OOM 账就是从「没人记账的常驻累加」
 *      开始的，定长这条是硬约束。
 *    这些读数**不参与任何判定**：等待判定、`atNs <= deadline` 的先后、默认值交付、
 *    [consumeMesSkip] 记账、[close] 打断行为与加账之前逐字相同。写账一律在**已有输入锁内**
 *    顺带完成，读账走同一把锁（同 [pendingCount]），不新增锁、不新增线程、不用静态可变量。
 *    **接线点（本类计数之外，下一轮由组长在 app 侧接）**：宿主面 `GameHost.submitInternal`
 *    的执行期闸 —— 无等待方时直接 `return`，复刻原版 `PressEnterKey` 在
 *    `Running`/`Initializing` 的早退（`EmueraConsole.cs:1374-1377`，ADR-005 原样复刻、不许修）
 *    —— 那条路**根本没调到** [submit]，所以既没有 seq 也不计 `drop`。要拿到
 *    「received（触点收到）vs dropped(闸)vs enqueued」的第一层差分，得在宿主自己的
 *    提交入口计数（归 app 车道），本路只交付 core 面 + 这段说明。
 *
 * ## 被 interrupt 时的语义
 *
 * [waitInput] / [readAnyKey] 的阻塞等待被 [Thread.interrupt] 打断时**不是脚本错误**：
 * 本移植里 interrupt 只有一个含义——宿主正在销毁（`GameHost.close` 打断 VM 线程），
 * 若任其冒出 [InterruptedException]，上层 `loadAndRun` 的 catch(Throwable) 会把它
 * 错误地写进失败屏。因此等待侧捕获后先复查 [close] 状态：已关闭则抛
 * [InputSinkClosedException]（宿主销毁的正常收敛路径）；尚未关闭则恢复中断位
 * （[Thread.currentThread].interrupt()，不吞中断）并按虚假唤醒重进等待循环。
 * 锁语义不受影响：`Condition.await` 因中断释放锁后再重新获取，catch 在锁内执行，
 * finally 的 unlock 一次配对不变。
 *
 * TODO(verify)：原版 `Void` 的「鼠标改写提交」那一路会 `i--` 重放一次并以空文本推进
 * （:1406-1408 + :1444-1448），本类按键盘路径（:1417-1420）一律忽略；补齐要在
 * [Entry] 里加 `changedByMouse` 维并让宿主提交点（`app`/`platform`）跟着分流。
 * `PRIMITIVE_MOUSE_KEY` 的真鼠标/按键事件推进（:1290-1346）同样未接，本类只覆盖
 * 「一条提交」与「超时」两个推进源。
 * 另有四处提交预处理/状态维未接（语料 0 命中，不影响本路形态分派）：
 * ① 脚本执行期（`state == Running`，:1374-1377）的按键原版丢弃 —— 生产宿主已按
 * [isAwaiting] 预筛对齐（见第 1 条，2026-10-25），本类自身的队列语义保留给测试/装置；
 * ② `@` 开头的系统命令（:1412-1416，`input.Length > 1 && !OneInput` 才走）；
 * ③ 括号表达式输入 `parseInput`（:1425-1428，实现 :1546）—— 提交文本在原版进
 * `doInputToEmueraProgram` 之前还要过这两道，本类按「整条就是最终文本」收；
 * ④ **多段切分**：原版先把这条提交按 `spliter = { "\\n", "\r\n", "\n", "\r" }`
 * （`EmueraConsole.cs:146`，首项是**字面**反斜杠+n 两字符）切成多段，再逐段
 * `RunEmueraProgram`（:1429-1433）⇒ 一次回车可跨多处等待连推；本类把「一条提交」
 * 当作一个等待的输入、不切分（宏回放/多行输入含字面反斜杠+n 或 CR/LF 时行为分叉）。
 * 段内跳过标记的处理已补上（见 `ESCAPE_SKIP` 与 [interpret]）。
 */
class QueueInputSink(
    /** 单调时钟（纳秒）。默认墙钟；测试注入假时钟以在不真等毫秒的情况下驱动截止/超时分支。 */
    private val timeSource: () -> Long = { System.nanoTime() },
) : InputSink, AutoCloseable {

    /**
     * `PressEnterKey` 段内的跳过标记 `\e`（`EmueraConsole.cs:1435` 的 `"\\e"` 字面量：
     * 反斜杠 + `e` 两个字符，**不是** ESC 控制符 U+001B）。命中即从提交文本里全部剥离，
     * 并把 `MesSkip` 置真（:1437-1438）。C# 逐段独立判定（:1432 的分段循环），
     * 本移植「一条提交 = 一段」等价为整条判定一次；剥离发生在 OneInput 截断**之前**（同 C# 序）。
     */
    private companion object {
        const val ESCAPE_SKIP = "\\e"

        /**
         * 近期流水环形窗口的容量（条）。定长是硬约束：见类 KDoc 第 10 条，
         * 「没人记账的常驻累加」是本仓 OOM 的起点，观察面自己不能变成新的账本。
         */
        const val TRAIL_CAPACITY = 16
    }

    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val queue = ArrayDeque<Entry>()
    private var closed = false

    /** 最近一次被消费的提交带不带 `keySkip`（[consumeMesSkip] 的待取标志）。 */
    private var consumedMesSkip = false

    // ------------------------------------------------------------------
    // 输入流水读数（类 KDoc 第 10 条：观察面，不参与语义；全部挂实例，禁静态可变量）
    //
    // 写：只在输入锁内（[submit] / [readAnyKey] / [waitInput] / [drainIgnored] 的临界区里
    // 顺带完成，与 [queue] 的变更同批、同一次锁持有）。读：同一把锁，不新增锁。
    // 下面的字段名带 cnt/mark 前缀，是为了与对外的读数属性区分；对外口径一律见属性 KDoc。
    // ------------------------------------------------------------------

    /** 已发出的提交编号（[submit] 锁内自增后写；从 1 起 ⇒ 0 = 一条提交都没发过）。 */
    private var seqIssued = 0L

    /** 最后一条**入队**提交的编号。 */
    private var markSubmittedSeq = 0L

    /** 最后一条**被等待方吃掉并推进**的提交的编号（`con`；丢弃的不算）。 */
    private var markConsumedSeq = 0L

    /** VOID 早退丢弃的条数（`EmueraConsole.cs:1417-1420`）。 */
    private var cntDropVoid = 0L

    /** 最后一次 VOID 丢弃那条提交的编号（0 = 没丢过）。 */
    private var markSeqDropVoid = 0L

    /** 整数解析失败丢弃的条数（`EmueraConsole.cs:1125-1128`）。 */
    private var cntDropParse = 0L

    /** 最后一次解析失败丢弃那条提交的编号（0 = 没丢过）。 */
    private var markSeqDropParse = 0L

    /** 遮罩取色条目被等待形态拒绝丢弃的条数（[interpretMappedColor] 的丢弃支）。 */
    private var cntDropColor = 0L

    /** 最后一次取色丢弃那条提交的编号（0 = 没丢过）。 */
    private var markSeqDropColor = 0L

    /** CBG 按钮值条目被等待形态拒绝丢弃的条数（[interpretCbgButton] 的丢弃支）。 */
    private var cntDropCbgButton = 0L

    /** 最后一次 CBG 按钮值丢弃那条提交的编号（0 = 没丢过）。 */
    private var markSeqDropCbgButton = 0L

    /** 环形流水窗口（定长 [TRAIL_CAPACITY]，[trailNext] 为下一个写入位）。 */
    private val trailRing = arrayOfNulls<String>(TRAIL_CAPACITY)

    /** [trailRing] 的写入位（始终落在 `0 until TRAIL_CAPACITY`）。 */
    private var trailNext = 0

    /** [trailRing] 里已有的条数（封顶 [TRAIL_CAPACITY]，只增到满就不再增）。 */
    private var trailSize = 0

    /**
     * 写一条流水（**调用方必须持输入锁**）。
     *
     * 满窗口时覆盖最旧那条 ⇒ 内存占用恒为 [TRAIL_CAPACITY] 个短串，不随游戏时长增长。
     * 计数与编号同样只增不减：观察面不许「消化后清零」，否则读到的瞬时值没法回溯。
     */
    private fun pushTrail(note: String) {
        trailRing[trailNext] = note
        trailNext = (trailNext + 1) % TRAIL_CAPACITY
        if (trailSize < TRAIL_CAPACITY) trailSize++
    }

    /** 记一条入队（[submit] 锁内）。 */
    private fun noteEnqueued(seq: Long, text: String, mesSkip: Boolean) {
        markSubmittedSeq = seq
        pushTrail("#$seq enq len=${text.length} skip=$mesSkip")
    }

    /**
     * 记一条被等待方吃掉并推进（[readAnyKey] / [waitInput] 锁内）。
     * [where] = `@wait VALUE/INT` 这类等待点标识，`@anykey` 表示 WAIT 家族。
     */
    private fun noteConsumed(seq: Long, where: String) {
        markConsumedSeq = seq
        pushTrail("#$seq con $where")
    }

    /** 记一条 VOID 早退丢弃（[drainIgnored] 锁内，`EmueraConsole.cs:1417-1420`）。 */
    private fun noteDroppedVoid(seq: Long) {
        cntDropVoid++
        markSeqDropVoid = seq
        pushTrail("#$seq drop(void)")
    }

    /** 记一条整数解析失败丢弃（[waitInput] 锁内，`EmueraConsole.cs:1125-1128`）。 */
    private fun noteDroppedParse(seq: Long, text: String) {
        cntDropParse++
        markSeqDropParse = seq
        pushTrail("#$seq drop(parse len=${text.length})")
    }

    /** 记一条遮罩取色丢弃（[waitInput] 锁内，[interpretMappedColor] 的丢弃支）。 */
    private fun noteDroppedColor(seq: Long) {
        cntDropColor++
        markSeqDropColor = seq
        pushTrail("#$seq drop(color)")
    }

    /** 记一条 CBG 按钮值丢弃（[waitInput] 锁内，[interpretCbgButton] 的丢弃支）。 */
    private fun noteDroppedCbgButton(seq: Long) {
        cntDropCbgButton++
        markSeqDropCbgButton = seq
        pushTrail("#$seq drop(cbgbtn)")
    }

    /**
     * 等待点标识：取值形态带上值通道（`VALUE/INT`、`VALUE/STR`），其余形态单义，只写形态。
     * 与 [InputRequest.RequestForm] 的枚举名一致，便于和 specs 的形态表对着读。
     */
    private fun awaitTag(request: InputRequest): String =
        if (request.form == InputRequest.RequestForm.VALUE) {
            "@wait ${request.form}/${request.type}"
        } else {
            "@wait ${request.form}"
        }

    /** 一次等待没有提交可吃、靠超时推进时记一行（不占 seq）。 */
    private fun noteTimeoutAdvance(request: InputRequest) {
        pushTrail("tmo ${awaitTag(request)}")
    }

    /**
     * 翻页计时：最近一次 [submit] 的纳秒时刻（0 = 当前没有待测翻页）。
     *
     * 口径 = 「提交 → 下一次进入等待」的墙钟毫秒：脚本被输入唤醒后一直跑到下一页刷完、
     * 落到下一处 [waitInput]/[readAnyKey] 为止，正好是玩家感知的「一页」耗时。
     * 与 `GameHost.drive()` 里按 handler 返回计的口径不同 —— 一个 handler 内可能连等
     * 好几页（自定义角色流），那种口径永远等不到返回；这里按**等待点**计，逐页都有读数。
     *
     * 只在 debug 宿主装了 [onTurnReady] 时有观察者，生产路径只多一次 `nanoTime` 与一个空判。
     */
    @Volatile
    private var lastSubmitNs: Long = 0L

    /** 翻页读数观察者（宿主调试通道用；null = 不测）。参数 = 毫秒。
     *
     * 交付时机 = 等待方**进入**等待点的那一刻（不是提交被消费后）：结算在输入锁内、
     * 交付在锁外，见 [settleTurnStart]。宿主实现自身仍应便宜 —— 它在 VM 线程上同步调。
     */
    var onTurnReady: ((Long) -> Unit)? = null

    /**
     * 等待点进入回调（宿主接线；null = 零行为）：[readAnyKey] / [waitInput] 进入等待时各调一次。
     *
     * 用途 = 执行期点击意图的**寿命边界**（见 `KeyEventInputState` 类文档「执行期点击意图」）。
     * 点击意图只服务不走 sink 的轮询屏（`AWAIT` + `GETTEXTBOX`）；脚本一旦落到真正的输入
     * 等待，原版 `PressEnterKey` 的 Running 早退已经决定了这次点击不算数
     * （`EmueraConsole.cs:1374-1377`），取值等待更不许把旧文本喂进 RESULT/RESULTS
     * （2026-10-25 跨屏串值的根子）。宿主据此作废待交付的点击意图。
     *
     * 时机 = 进入等待、设置 [awaiting] / [awaitingAnyKey] 之前（锁外）；与 [onTurnReady]
     * 一样在 VM 线程上同步调用，实现必须便宜，且**不得**回调 sink 自身（会自锁）。
     */
    @Volatile
    var onWaitEntered: (() -> Unit)? = null

    /**
     * 键入回显的引擎档位（装配期配置项；缺省 [EchoInputMode.REPLICA] = 原版复刻）。
     *
     * 本类在**输入交付点**按档位决定这次交付要不要产生回显行，不在显示层做事后过滤：
     * [EchoInputMode.REPLICA] 把生效文本交给 [onEchoInput] 打成普通行；
     * [EchoInputMode.SUPPRESSED] 不产出行，只经 [onEchoSuppressed] 把「本轮本该有一行回显」
     * 记进控制台的行账本。档位语义与账本折算见 [EchoInputMode]。
     */
    @Volatile
    var echoInputMode: EchoInputMode = EchoInputMode.REPLICA

    /**
     * 键入回显回调（宿主接线；null = 零行为）：一次输入**交付**时，把交付消费点上的生效文本
     * 交给宿主打成一条普通行 —— 原版 `doInputToEmueraProgram` 末尾的 `Print(str)` +
     * `PrintFlush(force: false)`（`EmueraConsole.cs:1280-1282`）。
     *
     * 原版语义：每次取值输入交付（点按钮或键入回车）都把「这次交付的生效文本」打成一条
     * **非临时、无按钮**的普通行 —— 与 PRINT 语句同一条打印路径（进打印缓冲、与 PRINTC
     * 残留行合并、计入 LINECOUNT），空文本经 `Print` 的空串早退（:3167-3172）不回显。
     * 这行是引擎级 deleteLine(1)/(2) 错误清理的删除对象（Process.cs:2194/:2498/:2648 等）；
     * 缺了它，同形 `deleteLine(1)` 会误删屏上真行（2026-11-04 ABLUP 案真机实证并回滚）。
     *
     * 回显**文本** = 交付消费点上的生效 `str`（`doInputToEmueraProgram`，:1112-1288），
     * 形态矩阵见 [echoTextFor]；回调由本类在**输入锁之外**、VM 线程上同步触发
     * （与 [onTurnReady] 同一条锁外纪律 —— 宿主实现会拿控制台的锁，锁内回调会把 UI 线程的
     * 下一次 [submit] 挡在整段打印之后），时机在交付消费点、本方法返回 outcome 之前：
     * 原版 `Print(str)`（:1280）先于 `state = Running`（:1097）的脚本恢复执行。
     * 实现侧 = 走控制台的正常打印路径（`printLine`），**不得**用临时行通道。
     */
    @Volatile
    var onEchoInput: ((String) -> Unit)? = null

    /**
     * [EchoInputMode.SUPPRESSED] 的行账本登记回调（宿主接线；null = 零行为）：与
     * [onEchoInput] 互斥，同一轮交付只会触发其中一个。宿主在这里**不打任何显示行**，
     * 只把「本轮本该有一行回显」记进 LINECOUNT 与删除账本，供后续 `deleteLine` 折算。
     * 触发时机、线程纪律与 [onEchoInput] 完全相同（输入锁外、VM 线程、交付点同步）。
     */
    @Volatile
    var onEchoSuppressed: (() -> Unit)? = null

    /**
     * 输入交付点的回显分派：只有 [echoTextFor] 给出非空生效文本时才真有一行回显
     * （原版空串早退被 `text == null` 挡住，不会凭空多记一笔账）。两档各走一条回调，
     * 都不会在显示层做事后过滤。
     */
    private fun deliverEcho(text: String?) {
        if (text == null) return
        if (echoInputMode == EchoInputMode.SUPPRESSED) {
            onEchoSuppressed?.invoke()
        } else {
            onEchoInput?.invoke(text)
        }
    }

    /**
     * 「本页函数流水」记录面（观察面接线，缺省 null = 零行为）：页边界 = 本类结算
     * `turnMs` 的两个等待点（[readAnyKey] / [waitInput] 的进入段），在交付
     * [onTurnReady] **之后**清零（宿主回调里读的正是刚结束这一页的流水；先交付后清）。
     *
     * 纪律与 [settleTurnStart] 同源：结算在锁内、交付在锁外 —— 清零同样放在**锁外**、
     * 紧跟交付。[PageFunctionTrail.clear] 与 VM 侧的 record 同线程（等待点在本脚本线程上
     * 进入；见 PageFunctionTrail 的线程模型节），不需要输入锁保护，也不新增锁。
     * @Volatile：宿主线程装配、VM 线程消费（与 FunctionProbe 同口径）。
     */
    @Volatile
    var pageTrail: PageFunctionTrail? = null

    /**
     * 结算一次「页面就绪」：算出上次提交到当前等待点的墙钟毫秒，并消费起点（置 0）。
     *
     * **必须在输入锁内调用**：读走并清零 [lastSubmitNs] 要与 [submit] 的写入互斥，
     * 否则「读到旧起点 → submit 写入新起点 → 这里清零」会把下一次的起点弄丢。
     * 交付（触发 [onTurnReady]）由调用方在**锁外**做 —— 宿主回调里的文件 I/O 与堆采样
     * 若在锁内跑，UI 线程的下一次 [submit] 会被挡在同一把锁上（2026-10-30 玩家 ANR 的根因）。
     *
     * 每次等待点只消费一次起点，连点/排队提交只记最新一次。
     *
     * @return 待交付的毫秒数；-1 = 没有待结算的起点（连续第二个等待点，零成本短路）
     */
    private fun settleTurnStart(): Long {
        val start = lastSubmitNs
        if (start == 0L) return -1L
        lastSubmitNs = 0L
        return (System.nanoTime() - start) / 1_000_000L
    }

    /**
     * 队列元素：一次回车提交的原文 + `PressEnterKey` 的 `keySkip`（`EmueraConsole.cs:1371-1373`）
     * + 按下时刻（[timeSource] 纳秒，判「按键与截止点谁先到」用）
     * + 流水编号 [seq]（[submit] 锁内自增，只为观察面服务，不参与任何判定）
     * + 遮罩取色值（[submitMappedColor] 专用；非空 = 这条提交不走文本通道，交付见
     * [interpretMappedColor]）
     * + CBG 按钮值（[submitCbgButtonValue] 专用；非空 = 这条提交不走文本通道，交付见
     * [interpretCbgButton]）。两者互斥：一条条目只带一个附加值。
     *
     * 与上面两类**不同**：[mouseButton] 不是替代文本通道的附加值，而是给**同一条文本提交**
     * 打上的来源标记（`MainWindow.cs:824-849` 的 `changedByMouse` + 鼠标键），
     * 所以它可以与 `text` 共存，且不与 `mappedColor` / `cbgButtonValue` 同现
     * （那两类由各自独立入口入队，恒带空文本）。交付见 [applyMouseSlots]。
     */
    private data class Entry(
        val text: String,
        val mesSkip: Boolean,
        val atNs: Long,
        val seq: Long,
        val mappedColor: Long? = null,
        val cbgButtonValue: Long? = null,
        /** 鼠标按钮位（1 左 / 2 右 / 3 中）；null = 这条不是画面按钮点击（键盘回车等）。 */
        val mouseButton: Int? = null,
    )

    /** 未消费的提交条数（UI 观察用；同锁读）。 */
    val pendingCount: Int
        get() {
            lock.lock()
            try {
                return queue.size
            } finally {
                lock.unlock()
            }
        }

    // ------------------------------------------------------------------
    // 流水读数（类 KDoc 第 10 条）：只读、同锁取，语义一律不受影响
    // ------------------------------------------------------------------

    /** 最后一条**入队**提交的编号（0 = 这个 sink 还没收过任何提交）。 */
    val lastSubmittedSeq: Long
        get() {
            lock.lock()
            try {
                return markSubmittedSeq
            } finally {
                lock.unlock()
            }
        }

    /** 最后一条**被等待方吃掉并推进**的提交编号（0 = 还没有提交被吃掉过）。 */
    val lastConsumedSeq: Long
        get() {
            lock.lock()
            try {
                return markConsumedSeq
            } finally {
                lock.unlock()
            }
        }

    /** VOID 形态早退丢弃的累计条数（`EmueraConsole.cs:1417-1420`）。 */
    val dropVoidCount: Long
        get() {
            lock.lock()
            try {
                return cntDropVoid
            } finally {
                lock.unlock()
            }
        }

    /** 最后一次 VOID 丢弃的提交编号（0 = 没丢过）。 */
    val lastDropVoidSeq: Long
        get() {
            lock.lock()
            try {
                return markSeqDropVoid
            } finally {
                lock.unlock()
            }
        }

    /** 整数解析失败丢弃的累计条数（`EmueraConsole.cs:1125-1128`）。 */
    val dropParseCount: Long
        get() {
            lock.lock()
            try {
                return cntDropParse
            } finally {
                lock.unlock()
            }
        }

    /** 最后一次解析失败丢弃的提交编号（0 = 没丢过）。 */
    val lastDropParseSeq: Long
        get() {
            lock.lock()
            try {
                return markSeqDropParse
            } finally {
                lock.unlock()
            }
        }

    /** 遮罩取色条目被等待形态拒绝丢弃的累计条数（[submitMappedColor] 的交付面）。 */
    val dropColorCount: Long
        get() {
            lock.lock()
            try {
                return cntDropColor
            } finally {
                lock.unlock()
            }
        }

    /** 最后一次取色丢弃的提交编号（0 = 没丢过）。 */
    val lastDropColorSeq: Long
        get() {
            lock.lock()
            try {
                return markSeqDropColor
            } finally {
                lock.unlock()
            }
        }

    /** CBG 按钮值条目被等待形态拒绝丢弃的累计条数（[submitCbgButtonValue] 的交付面）。 */
    val dropCbgButtonCount: Long
        get() {
            lock.lock()
            try {
                return cntDropCbgButton
            } finally {
                lock.unlock()
            }
        }

    /** 最后一次 CBG 按钮值丢弃的提交编号（0 = 没丢过）。 */
    val lastDropCbgButtonSeq: Long
        get() {
            lock.lock()
            try {
                return markSeqDropCbgButton
            } finally {
                lock.unlock()
            }
        }

    /** 两类丢弃之和（便利读数；分类计数看 [dropVoidCount] / [dropParseCount]，两者不合并记账）。 */
    val droppedCount: Long
        get() {
            lock.lock()
            try {
                return cntDropVoid + cntDropParse
            } finally {
                lock.unlock()
            }
        }

    /** 近期流水环形窗口的容量（条）—— 定长，见 [pushTrail] 的 OOM 约束。 */
    val trailCapacity: Int
        get() = TRAIL_CAPACITY

    /**
     * 近期流水摘要，**最旧 → 最新**排序；条数封顶 [trailCapacity]，超出部分已被新值覆盖。
     * 元素形如 `#12 enq len=3 skip=false`、`#12 con @wait VALUE/INT`、`#13 drop(void)`、
     * `#14 drop(parse len=3)`、`tmo @wait VALUE/INT`。给调试通道与崩溃报告直接拼字符串用。
     */
    fun recentInputTrail(): List<String> {
        lock.lock()
        try {
            return recentInputTrailLocked()
        } finally {
            lock.unlock()
        }
    }

    /**
     * 一次性取全套流水读数（调试通道/崩溃报告用，同一次锁持有 ⇒ 各项彼此一致）。
     *
     * [awaitingTag] 是**当前**等待点的快照（null = 不在等待中），配合 `lastSubmittedSeq` 与
     * `lastConsumedSeq` 就能把「玩家点了 N 次、界面只吃了一次、剩下卡在队里」这类现象分层钉死。
     */
    fun trailReadout(): InputTrailReadout {
        lock.lock()
        try {
            return InputTrailReadout(
                lastSubmittedSeq = markSubmittedSeq,
                lastConsumedSeq = markConsumedSeq,
                dropVoidCount = cntDropVoid,
                lastDropVoidSeq = markSeqDropVoid,
                dropParseCount = cntDropParse,
                lastDropParseSeq = markSeqDropParse,
                dropColorCount = cntDropColor,
                lastDropColorSeq = markSeqDropColor,
                dropCbgButtonCount = cntDropCbgButton,
                lastDropCbgButtonSeq = markSeqDropCbgButton,
                droppedCount = cntDropVoid + cntDropParse,
                pendingCount = queue.size,
                awaitingTag = awaiting?.let { awaitTag(it) },
                isAwaiting = awaiting != null || awaitingAnyKey,
                isClosed = closed,
                trail = recentInputTrailLocked(),
            )
        } finally {
            lock.unlock()
        }
    }

    /** 流水读数快照（[trailReadout] 的返回值；不可变，出锁后随便传）。 */
    data class InputTrailReadout(
        val lastSubmittedSeq: Long,
        val lastConsumedSeq: Long,
        val dropVoidCount: Long,
        val lastDropVoidSeq: Long,
        val dropParseCount: Long,
        val lastDropParseSeq: Long,
        /** 遮罩取色条目被等待形态拒绝丢弃的累计条数（[QueueInputSink.submitMappedColor] 的交付面）。 */
        val dropColorCount: Long,
        /** 最后一次取色丢弃的提交编号（0 = 没丢过）。 */
        val lastDropColorSeq: Long,
        /** CBG 按钮值条目被等待形态拒绝丢弃的累计条数（[QueueInputSink.submitCbgButtonValue] 的交付面）。 */
        val dropCbgButtonCount: Long,
        /** 最后一次 CBG 按钮值丢弃的提交编号（0 = 没丢过）。 */
        val lastDropCbgButtonSeq: Long,
        val droppedCount: Long,
        val pendingCount: Int,
        /** 当前等待点标识（`@wait VALUE/INT` 等）；null = 不在等待中。 */
        val awaitingTag: String?,
        val isAwaiting: Boolean,
        val isClosed: Boolean,
        /** 最旧 → 最新的近期流水（条数封顶 [QueueInputSink.trailCapacity]）。 */
        val trail: List<String>,
    )

    /** [recentInputTrail] 的持锁内部版（[trailReadout] 已在锁内，不能再走公开属性）。 */
    private fun recentInputTrailLocked(): List<String> {
        if (trailSize == 0) return emptyList()
        val from = if (trailSize == TRAIL_CAPACITY) trailNext else 0
        val out = ArrayList<String>(trailSize)
        for (i in 0 until trailSize) {
            val slot = (from + i) % TRAIL_CAPACITY
            trailRing[slot]?.let { out.add(it) }
        }
        return out
    }

    /** 是否已关闭（[close] 之后为 true）。 */
    val isClosed: Boolean
        get() {
            lock.lock()
            try {
                return closed
            } finally {
                lock.unlock()
            }
        }

    /**
     * 当前正在等待的输入请求；null = 不在 [waitInput] 里。
     *
     * 只读快照，给 UI 选按钮可点性（`ButtonAcceptance`）与输入框类型用；等待方在
     * [waitInput] 进出时维护，读侧不加锁（单生产单消费之外只允许这种只读快照观察）。
     */
    @Volatile
    var awaiting: InputRequest? = null
        private set

    /** 当前是否在 [readAnyKey] 里（WAIT 系：只等一次回车，不解释文本）。 */
    @Volatile
    var awaitingAnyKey: Boolean = false
        private set

    /** 是否有任一等待方在等（UI 据此点亮提交按钮）。 */
    val isAwaiting: Boolean
        get() = awaiting != null || awaitingAnyKey

    /**
     * 提交一条「回车文本」。
     *
     * @param mesSkip `PressEnterKey` 的 `keySkip`：双击/右键/Esc 跳过时为 true，
     *   普通回车为 false（`EmueraConsole.cs:1371-1373`、`MainWindow.cs:1415-1422`）
     * @return true = 已入队；false = 队列已关闭（丢弃，不抛：这条提交来自正在销毁的 UI，
     *   不该把关闭竞态变成宿主线程上的异常）。已关闭时**不发号**：流水编号只覆盖真正入队的提交。
     */
    fun submit(text: String, mesSkip: Boolean = false): Boolean {
        // FEEL1-L3 探针（只观测，锁语义一字未动）：未挂载 = watch == null ⇒ 不取时钟、
        // 不进 LockWatch、零分配。tag = 本次提交的文本长度（哪一条输入把锁拖长了）。
        val watch = LockWatch.handleOrNull()
        val enterNs = if (watch === null) 0L else System.nanoTime()
        lock.lock()
        val acquiredNs = if (watch === null) 0L else System.nanoTime()
        try {
            if (closed) return false
            // 翻页计时起点：入队即算「玩家已提交」，到下一个等待点结算。
            lastSubmitNs = System.nanoTime()
            // 按下时刻用 [timeSource]（与截止点同一条时间轴；假时钟下可判先后）。
            // 流水编号与入队同批、同一把锁：单调、只增、每条提交恰一个号（观察面，见类 KDoc 第 10 条）。
            val seq = ++seqIssued
            queue.addLast(Entry(text, mesSkip, timeSource(), seq))
            noteEnqueued(seq, text, mesSkip)
            arrived.signalAll()
            return true
        } finally {
            lock.unlock()
            // 记录点在 unlock **之后**：本方法的两个 return 都走这条 finally，
            // 探针因此覆盖「入队」与「已关闭拒收」两条路，且始终在输入锁外侧
            // （类 KDoc 的回调锁外口径）。noteSegment 禁抛，不会吞掉返回值。
            if (watch !== null) {
                watch.noteSegment(
                    LockWatch.ID_SINK_SUBMIT, enterNs, acquiredNs, System.nanoTime(), text.length.toLong(),
                )
            }
        }
    }

    /** [submit] 的整数便捷入口（按钮 [0]/[1]、指令号等）。 */
    fun submitInt(value: Long): Boolean = submit(value.toString())

    /**
     * 提交一笔**画面按钮点击**（`INPUT -1, 1` 之类带鼠标等待的按钮位交付面）。
     *
     * ## 与 [submit] 的差别
     *
     * [submit] 只带「一条回车文本」，对应原版 `PressEnterKey(keySkip, text, changedByMouse: false)`；
     * 画面按钮点击对应 `MainWindow.cs:824-849` 的 `changedByMouse: true` 分支，窗口侧在
     * 调 `PressEnterKey` **之前**还写了 `RESULTS:1 = selectedString`（`:834` 的
     * `InputString(1L, ...)`）与 `RESULT:1 = 1/2/3`（`:837`/`:842`/`:847` 的
     * `InputInteger(1L, ...)`，左/右/中键）。语料据此判定「这次是鼠标左键点了有效选项」：
     * `QOL_USERCOM.ERB @COM_OPTION` 的 `MOUSE_BTN = RESULT:1` → `IF MOUSE_BTN == 1`
     * → `SETVAR @"COM_ALTER_{COM_ID}"`。缺了这一维，按钮值虽能进 `RESULT`，子选单却
     * 永远不切换（玩家报的「点击无反应 / 有黄框但无变化」）。
     *
     * 本口因此**仍走文本通道**（`text` 就是按钮 value，解析规则与 [submit] 逐字相同），
     * 只是在 [Entry] 上补一个来源标记；真正的槽位附加由交付消费点的 [applyMouseSlots]
     * 按等待形态闸决定，不在入队时写任何变量（保持 sink 无变量依赖）。
     *
     * ## 形态闸
     *
     * 只有 `RequestForm.VALUE` + `mouseInput == true` 的等待会拿到附加槽；
     * 其余形态仍按普通文本提交解释，与改动前逐字相同（[interpret] 不感知这一维）。
     * 这样键盘提交、不带鼠标的 INPUT、WAIT 系、`INPUTANY`、`INPUTMOUSEKEY` 与超时收尾
     * 都不会被误标成鼠标点击 —— 原版 `:824`/`:851` 同样只认 `IsWaintingInputWithMouse`。
     *
     * ## 线程与流水
     *
     * 与 [submit] / [submitMappedColor] 同一条：输入锁内入队、发号、signal；
     * 关闭后返回 false 不发号。流水行 = `#<seq> enq btn=<1|2|3> len=<文本长度>`，
     * 调试通道据此能把「玩家点了按钮」和「玩家敲了回车」分开（两者都产生 `enq`）。
     *
     * @param text 命中的按钮 value（`selectedString`）；`RESULTS:1` 记的是这个原文
     * @param mouseButton 鼠标按钮位：1 左键（Android 触摸恒为此值）/ 2 右键 / 3 中键
     * @param mesSkip `PressEnterKey` 的 `keySkip`，与 [submit] 同名同义；缺省 false
     *   （`MainWindow.cs:848` 左键命中传 `keySkip: false`）。宿主若把这一维连同
     *   按钮来源一起投来，不得在这里悄悄丢掉 —— 两条投喂口只差「来源标记」这一维。
     * @return true = 已入队；false = 队列已关闭（同 [submit]：不抛、不发号）
     */
    fun submitButtonClick(text: String, mouseButton: Int = 1, mesSkip: Boolean = false): Boolean {
        lock.lock()
        try {
            if (closed) return false
            // 翻页计时起点与 [submit] 同批：一次按钮点击也是一次玩家提交。
            lastSubmitNs = System.nanoTime()
            val seq = ++seqIssued
            queue.addLast(
                Entry(text = text, mesSkip = mesSkip, atNs = timeSource(), seq = seq, mouseButton = mouseButton),
            )
            markSubmittedSeq = seq
            pushTrail("#$seq enq btn=$mouseButton len=${text.length}")
            arrived.signalAll()
            return true
        } finally {
            lock.unlock()
        }
    }

    /**
     * 投喂一枚**遮罩取色**（INPUT(6/3,color) 消费面的入队半段；手势侧长按 → 取色 → 本口）。
     *
     * 原版没有「投喂」这一步——PC 上鼠标按下时窗口直接写变量（`MainWindow.cs:795` 的
     * `InputInteger(6L, GetMappedColor(e.X, e.Y))` 与 `:828` 的 `InputInteger(3L, ...)`），
     * 写值与推进是同一个鼠标事件；本移植的队列只有「一条提交」这一维，色必须作为一条
     * 提交入队才能被等待方吃掉，于是有了本口。色**不进文本通道**（不走 [submit]）：
     * `oneInput` 截断、`\e` 跳过标记、数值解析对它都无意义，文本侧的预处理一概不沾。
     *
     * 交付按等待形态分派（[interpretMappedColor]）；等待方不收色的形态按
     * 「丢弃、不推进、不留给下一次等待」处理——原版鼠标按下对那些形态同样无支可落
     * （`MainWindow.cs:806`/`:824`/`:851` 三个分支都要求等待态匹配，都不中就什么都不发生）。
     * 「超时之后才到」的色条目与迟到的文本提交同口径：留给下一次等待（类 KDoc 第 1 条例外②）。
     *
     * @return true = 已入队；false = 队列已关闭（同 [submit]：不抛、不发号）。
     */
    fun submitMappedColor(color: Long): Boolean {
        lock.lock()
        try {
            if (closed) return false
            // 翻页计时起点与 [submit] 同批：取色也是一次玩家提交，到下一个等待点结算。
            lastSubmitNs = System.nanoTime()
            val seq = ++seqIssued
            queue.addLast(Entry(text = "", mesSkip = false, atNs = timeSource(), seq = seq, mappedColor = color))
            markSubmittedSeq = seq
            pushTrail("#$seq enq color=${color.toString(16)}")
            arrived.signalAll()
            return true
        } finally {
            lock.unlock()
        }
    }

    /**
     * 投喂一枚**CBG 按钮值**（取色图命中值，`InputResult5` 的 `RESULT:4` 那一格）。
     *
     * ## 原版在哪
     *
     * 鼠标按下时控制台先拿取色图 `cbgButtonMap` 在点击点取色，`A == 255` 才算命中，
     * 命中值 = `pixel.ToArgb() & 0xFFFFFF`（`EmueraConsole.cs:1309-1320`）；随后
     * `InputMouseKey(1, button, x, y, result, ...)`（`:1327`/`:1331`/`:1336`）把六元组
     * 交给 `Process.InputResult5`，整组写 `RESULT:0..5 = (类型, 按钮, x, y, 取色值,
     * selectingButton.Input)`（`Process.cs:393-401`）。**这条路的闸是 `IsWaitingPrimitive`**
     * —— 只有 `INPUTMOUSEKEY` 等待期鼠标按下才会进 `MouseDown`（`EmueraConsole.cs:1300-1305`），
     * 其余等待态连取色都不发生。本口因此只对 `PRIMITIVE_MOUSE_KEY` 交付，其余形态
     * 按「出队、不推进、不留给下一次等待」丢弃（[interpretCbgButton]）。
     *
     * ## 为什么不是 [submitMappedColor]
     *
     * 那个口是**遮罩取色**（`PRINT_IMG` 的 `namem` 遮罩图，`ConsoleImagePart.cs:135-157`），
     * 原版落 `RESULT:6`（`MainWindow.cs:795`）或 `RESULT:3`（`:828`），语义与 CBG 取色图无关：
     * 一个来自行内遮罩段，一个来自整屏按钮图；两者的命中坐标、命中判据、生效等待形态都不同
     * （遮罩色在 INPUT 系带鼠标时也生效，CBG 值只在 INPUTMOUSEKEY）。共用一条口会把
     * 两种值混进同一个队列元素语义里，故另开一个**明确命名**的入口。
     *
     * ## 槽位偏差（登记项）
     *
     * 原版值落 `RESULT:4`，本口仍把它走交付主通道 ⇒ sem 的 `INPUTMOUSEKEY` 分支写进
     * **`RESULT:0`**（与 [submitMappedColor] 的同款偏差：那边原版是 `RESULT:6/3`）。
     * [InputOutcome] 现已扩成按槽索引的多槽交付（[InputOutcome.resultIntSlots]），
     * 「先扩交付契约」这个卡点已解除；但整组写回要动 sem 的 INPUTMOUSEKEY 分支
     * （它现在自行写 `RESULT:0..5` 超时五元组），故本登记项保留：本口只交**值**，
     * `InputResult5` 的类型位（`RESULT:0 = 1` 鼠标按下）与坐标位（`RESULT:2/3`）仍待接线。
     *
     * ## 线程与流水
     *
     * 与 [submit] / [submitMappedColor] 完全同一条：输入锁内入队、发号、signal；
     * 关闭后返回 false 不发号。流水行 = `#<seq> enq cbgbtn=<hex>`。
     *
     * @param value 取色图命中值（`0xRRGGBB`，原版 `& 0xFFFFFF` 之后的值）
     * @return true = 已入队；false = 队列已关闭（同 [submit]：不抛、不发号）
     */
    fun submitCbgButtonValue(value: Long): Boolean {
        lock.lock()
        try {
            if (closed) return false
            // 与文本/取色提交同一条翻页计时起点：一次按钮按下也是一次玩家提交。
            lastSubmitNs = System.nanoTime()
            val seq = ++seqIssued
            queue.addLast(
                Entry(text = "", mesSkip = false, atNs = timeSource(), seq = seq, cbgButtonValue = value),
            )
            markSubmittedSeq = seq
            pushTrail("#$seq enq cbgbtn=${value.toString(16)}")
            arrived.signalAll()
            return true
        } finally {
            lock.unlock()
        }
    }

    /**
     * 只记跳过意图、不落文本（原版 `PressEnterKey` 在脚本执行期的形态：`MesSkip = keySkip`
     * 先行（`EmueraConsole.cs:1373`）、随后 Running/Initializing 早退把文本丢弃（:1374-1377））。
     *
     * 宿主按 [isAwaiting] 预筛「执行期提交」时（见类 KDoc 第 1 条的接线建议，GameHost 已接），
     * `keySkip = true` 的那条提交调这里保住跳过意图；文本按原版丢弃。等待侧的取法不变：
     * 下一次 [consumeMesSkip] 读到真（与真提交同一条读口）。
     *
     * 这里**不是提交**：不发流水编号、不进 [recentInputTrail]，也不计入任何 `drop` 计数 ——
     * 被执行期闸丢掉的那一层归宿主面（类 KDoc 第 10 条的接线点）。
     */
    fun noteSkipIntent() {
        lock.lock()
        try {
            consumedMesSkip = true
        } finally {
            lock.unlock()
        }
    }

    override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) {
        // 执行期点击意图的寿命边界：走 sink 的等待不吃「轮询屏」的点击补写（见 onWaitEntered）。
        onWaitEntered?.invoke()
        // FEEL1-L3 探针（只观测）：本等待点每次 await 的时长 = VM 线程「等一次回车」的样本。
        val watch = LockWatch.handleOrNull()
        // 结算与交付分两段（见 [settleTurnStart]）：结算要锁（与 submit 的写互斥），
        // 交付要在锁外（宿主回调里的文件 I/O / 堆采样不得挡 UI 线程的 submit）。
        lock.lock()
        val turnMs: Long = try {
            settleTurnStart()
        } finally {
            lock.unlock()
        }
        if (turnMs >= 0L) {
            try {
                onTurnReady?.invoke(turnMs)
            } finally {
                // 页边界清流水（观察面）：先交付后清零；null = 零行为。try/finally 不改变
                // onTurnReady 抛错时的传播路径（宿主异常照旧冒出），只保证清零必达。
                pageTrail?.clear()
            }
        }
        // 键入回显文本在锁内只计算、unlock 后交付（与 onTurnReady 同一条锁外纪律，见 onEchoInput）。
        var pendingEcho: String? = null
        lock.lock()
        try {
            awaitingAnyKey = true
            while (true) {
                ensureOpen()
                val entry = queue.removeFirstOrNull()
                if (entry != null) {
                    // 本次提交的 keySkip 与段内 `\e` 跳过标记一齐落标志（EmueraConsole.cs:1373/:1438）。
                    consumedMesSkip = entry.mesSkip || entry.text.contains(ESCAPE_SKIP)
                    // 流水：WAIT 家族的等待点没有请求对象，用 @anykey 标识（类 KDoc 第 10 条）。
                    noteConsumed(entry.seq, "@anykey")
                    // 键入回显（见 onEchoInput）：WAIT 家族一次回车交付 —— 生效文本 =
                    // 剥离 `\e` 后的原文（PressEnterKey :1435-1438；ReadAnyKey 的请求不设
                    // OneInput ⇒ 无截断，:1440-1443 的条件不成立）。空串不回显（Print 空串早退，
                    // :3167-3172）。取色条目 text 恒空 ⇒ 天然不回显（原版变量直写，MainWindow.cs:795）。
                    pendingEcho = entry.text.replace(ESCAPE_SKIP, "").ifEmpty { null }
                    break
                }
                val awaitFromNs = if (watch === null) 0L else System.nanoTime()
                try {
                    arrived.await()
                } catch (_: InterruptedException) {
                    // interrupt = 宿主销毁（见类 KDoc「被 interrupt 时的语义」）：恢复中断位后
                    // 按关闭收敛。continue 重进等待会在持锁状态下热转（await 首行即再抛），
                    // close 拿不到同一把锁就永远置不上 closed。
                    Thread.currentThread().interrupt()
                    ensureOpen()
                    throw InputSinkClosedException("等待被中断：宿主正在销毁，输入队列不再接受等待")
                }
                // 全仓唯一一处「在持锁段内记录」的探针（逐次 await 的分布只有这里读得出来）；
                // 它成立的条件是 LockWatch 的监视器是**叶锁**，口径见 LockWatch 类 KDoc 锁序表末段。
                if (watch !== null) {
                    watch.noteWait(LockWatch.ID_SINK_ANYKEY_AWAIT, awaitFromNs, System.nanoTime())
                }
            }
        } finally {
            awaitingAnyKey = false
            lock.unlock()
        }
        deliverEcho(pendingEcho)
    }

    override fun waitInput(request: InputRequest): InputOutcome {
        // 执行期点击意图的寿命边界（见 onWaitEntered）：取值等待绝不吃轮询屏的点击补写。
        onWaitEntered?.invoke()
        val form = request.form
        val limitMs = request.timeLimit
        val timed = limitMs > 0
        // 毫秒 → 纳秒的换算与加法都必须**饱和**。ERB 整数是 Long，`TWAIT` / `TINPUT` 的
        // TIMES 实参原样落进 `InputRequest.Timelimit`（FunctionIdentifier.cs:822/:1030/:1079），
        // 脚本写 9223372036854775807 这种量级时 `limitMs * 1_000_000L` 与随后的加法会按
        // 2 的 64 次方回绕，截止点变成负数 ⇒ 下面 `timeSource() - deadline >= 0` 当场成立，
        // 限时等待**立刻超时**。原版同一 Long 只与 `_genericTimerStopwatch.ElapsedMilliseconds`
        // 相减（EmueraConsole.cs:1023），两个非负量相减不可能回绕 ⇒ 等价做法是饱和到
        // Long.MAX_VALUE（"永远不到点"），而不是把截止点折回过去时刻。
        val deadline = if (!timed) {
            Long.MIN_VALUE
        } else {
            val now = timeSource()
            val limitNs = if (limitMs > Long.MAX_VALUE / 1_000_000L) {
                Long.MAX_VALUE
            } else {
                limitMs * 1_000_000L
            }
            if (now > Long.MAX_VALUE - limitNs) Long.MAX_VALUE else now + limitNs
        }
        // 本次等待内的 `isTimeout`（原版控制台字段，EmueraConsole.cs:138/:1043）：
        // 一旦越过截止点就置真并**粘滞**到本条等待结束。
        var timerFired = false
        // 结算与交付分两段（同 [readAnyKey]）：结算要锁（与 submit 的写互斥），交付要在锁外 ——
        // 宿主回调里的文件 I/O 与堆采样若在输入锁内跑，UI 线程的下一次 [submit] 被挡整段
        // （2026-10-30 玩家 ANR：移动后弹「无响应」）。
        lock.lock()
        val turnMs: Long = try {
            settleTurnStart()
        } finally {
            lock.unlock()
        }
        if (turnMs >= 0L) {
            try {
                onTurnReady?.invoke(turnMs)
            } finally {
                // 页边界清流水（观察面）：先交付后清零；null = 零行为。try/finally 不改变
                // onTurnReady 抛错时的传播路径（宿主异常照旧冒出），只保证清零必达。
                pageTrail?.clear()
            }
        }
        // 交付结果与键入回显文本都在锁内结算；回显的**交付**（宿主回调）在 unlock 之后 ——
        // 与 onTurnReady 同一条锁外纪律（宿主实现会拿控制台的锁，见 onEchoInput 的 KDoc）。
        var deliveredOutcome: InputOutcome? = null
        var pendingEcho: String? = null
        lock.lock()
        try {
            awaiting = request
            // EnterKey/AnyKey 形态在原版是「按一次键就走、不取值」的等待，与 WAIT 家族同相；
            // 宿主 UI 的键盘类型/按钮可点性按 [awaitingAnyKey] 走（`GameHost.acceptance`）。
            if (form == InputRequest.RequestForm.ENTER_KEY || form == InputRequest.RequestForm.ANY_KEY) {
                awaitingAnyKey = true
            }
            while (true) {
                ensureOpen()
                // VOID：键盘提交在出队之前就早退（:1417-1420）⇒ 丢弃，不留给下一次等待。
                if (form == InputRequest.RequestForm.VOID) drainIgnored()
                // kotlin.collections.ArrayDeque 没有 peekFirst（stdlib 2.0.20 字节码核实），
                // firstOrNull 即「看一眼队首、不出队」。
                val head = if (form == InputRequest.RequestForm.VOID) null else queue.firstOrNull()
                // 队首的按下时刻不晚于截止点 ⇒ 按键先到（原版同一 UI 线程按事件序处理）。
                val inTime = head != null && (!timed || head.atNs <= deadline)
                // 时钟推进到「计时器先到」这一支（原版 `tickTimer` 过点即 `endTimer`，
                // EmueraConsole.cs:1013-1016；`endTimer` 于 :1043 置 isTimeout）。
                val firedNow = timed && !timerFired && !inTime && timeSource() - deadline >= 0L
                if (firedNow) timerFired = true
                val entry = when {
                    head == null -> null
                    inTime -> queue.removeFirst()
                    // 超时先收尾：`endTimer` → `RunEmueraProgram("")`（:1068）用空文本推进，
                    // 后到的这条按键留在队里给下一次等待。
                    firedNow -> null
                    // 超时后仍停在同一处等待 = 只有「整数无默认值」的死等路径（:1119-1128）。
                    else -> queue.removeFirst()
                }
                if (entry != null) {
                    // 跳过标志与取值成败无关：原版在 PressEnterKey 入口就置 MesSkip
                    // （EmueraConsole.cs:1373），提交被丢弃也不回滚。
                    // 段内 `\e` 跳过标记同样置位（:1435-1438）——两者 OR 后供 sem 层一次性取用。
                    consumedMesSkip = entry.mesSkip || entry.text.contains(ESCAPE_SKIP)
                }
                interpret(request, entry, timed, timerFired).let { outcome ->
                    // 流水记账（类 KDoc 第 10 条）：只观察、不改判定 —— 交付内容、推进时机、
                    // 出队序全部与加账前逐字相同，这里只是把「这条提交去了哪一层」落进账本。
                    if (entry != null) {
                        when {
                            outcome != null -> noteConsumed(entry.seq, awaitTag(request))
                            // VOID 的键盘提交在出队前就早退，正常走不到「出队后 VOID」；
                            // 兜底分支照 VOID 口径记账（:1417-1420），保证计数不吞掉真丢弃。
                            form == InputRequest.RequestForm.VOID -> noteDroppedVoid(entry.seq)
                            // 遮罩取色条目被当前形态拒绝（[interpretMappedColor] 的丢弃支）：
                            // 单独计数，不落 parse —— 色条目 text 恒空，落 parse 会把 len=0
                            // 的假账混进「解析失败」这一类。
                            entry.mappedColor != null -> noteDroppedColor(entry.seq)
                            // CBG 按钮值条目被当前形态拒绝（[interpretCbgButton] 的丢弃支）：
                            // 同上单独计数。
                            entry.cbgButtonValue != null -> noteDroppedCbgButton(entry.seq)
                            // 出队了却没交付 = 整数解析失败（:1125-1128）：丢弃、继续等。
                            else -> noteDroppedParse(entry.seq, entry.text)
                        }
                    } else if (outcome != null) {
                        // 手里没有提交却推进了 ⇒ 只可能是超时交付（endTimer :1043/:1068），记 tmo。
                        noteTimeoutAdvance(request)
                    }
                    if (outcome != null) {
                        // 画面按钮点击在原版窗口侧多写的 RESULT:1 / RESULTS:1
                        // （MainWindow.cs:834/:847）：形态闸见 [applyMouseSlots]。
                        deliveredOutcome = applyMouseSlots(request, entry, outcome)
                        // 键入回显文本（echoTextFor 是纯函数，只读本方法局部量）：
                        // 原版 = doInputToEmueraProgram 末尾 `Print(str)`（:1280），
                        // 先于 :1097 `state = Running` 的脚本恢复执行；本类在返回前交付。
                        pendingEcho = echoTextFor(request, entry, timed, timerFired)
                    }
                }
                if (deliveredOutcome != null) break
                // 只有「没有可取的货」才等：interpret 返回 null（丢弃提交）时队列里可能
                // 还有后续条目，直接 await 会错过早已发出的 signal 死等下去。
                val next = if (form == InputRequest.RequestForm.VOID) null else queue.firstOrNull()
                val pending = next != null && (timerFired || !timed || next.atNs <= deadline)
                when {
                    pending -> Unit
                    // 没有截止点可言（不限时 / 已过点还不能推进的死等路径）：无限挂起。
                    timerFired || !timed -> awaitQuietly()
                    else -> {
                        val remain = deadline - timeSource()
                        if (remain > 0L) awaitNanosQuietly(remain)
                    }
                }
            }
        } finally {
            awaiting = null
            awaitingAnyKey = false
            lock.unlock()
        }
        deliverEcho(pendingEcho)
        return deliveredOutcome!!
    }

    /**
     * 一次**已交付**的画面按钮点击，按原版窗口侧的顺序补上两个附加槽：
     * `InputString(1L, selectedString)` → `RESULTS:1`（`MainWindow.cs:834`）、
     * `InputInteger(1L, 1L/2L/3L)` → `RESULT:1`（`:837`/`:842`/`:847`）。
     *
     * 闸口 = `IsWaintingInputWithMouse`（`EmueraConsole.cs:286-293`）∧ 这条提交确实来自
     * 画面按钮：只认 [InputRequest.RequestForm.VALUE] + [InputRequest.mouseInput] 且
     * [Entry.mouseButton] 非 null。WAIT 系左键只是一次回车（`:806-821`，不写值）、
     * 不带鼠标的 INPUT / 键盘提交 / `INPUTANY` / `INPUTMOUSEKEY`（`:824`/`:851` 都不命中）、
     * 以及超时收尾（`entry == null`）一律原样返回 —— 附加槽为空表，既有语义逐字不变。
     *
     * `RESULTS:1` 用 [Entry.text] 原文：原版写的是 `selectedString`，发生在
     * `PressEnterKey` 的 `\e` 剥离 / `OneInput` 截断**之前**，不能用交付消费点上的
     * 生效文本（[deliverText] 的结果）。附加槽不含主槽 0：主值仍由
     * [InputOutcome.intValue] / [InputOutcome.strValue] 与 sem 层写。
     *
     * 取色 `RESULT:3`（`:828`）与修饰键 `RESULT:2`（`:832`）仍按既有登记项留在
     * 投喂侧（[submitMappedColor] / 宿主），本口只补按钮位与按钮文本。
     */
    private fun applyMouseSlots(request: InputRequest, entry: Entry?, outcome: InputOutcome): InputOutcome {
        val button = entry?.mouseButton ?: return outcome
        if (request.form != InputRequest.RequestForm.VALUE || !request.mouseInput) return outcome
        return outcome.copy(
            resultIntSlots = outcome.resultIntSlots + (1 to button.toLong()),
            resultStrSlots = outcome.resultStrSlots + (1 to entry.text),
        )
    }

    /**
     * VOID 等待期间到达的提交：原版 `PressEnterKey` 在出队之前早退（`EmueraConsole.cs:1417-1420`），
     * 那次回车不进程序、也不会被下一次等待自动吃掉（文本只留在输入框里，要再按一次）。
     * `MesSkip` 仍然在入口就置（:1373），所以丢弃时照样记下跳过标志。
     */
    private fun drainIgnored() {
        while (true) {
            val ignored = queue.removeFirstOrNull() ?: return
            consumedMesSkip = ignored.mesSkip
            noteDroppedVoid(ignored.seq)
        }
    }

    /**
     * 提交文本的段内预处理（`PressEnterKey` 段循环里、进 `doInputToEmueraProgram` 之前的两步）：
     * `\e` 跳过标记全部剥离（:1435-1438），再按 `OneInput` 截断到首字符（:1440-1443）。
     * 剥离发生在截断之前（与 C# 同序）。原版按段切分后逐段处理（:1432 的分段循环）；
     * 本移植「一条提交 = 一段」，等价于整条处理一次。
     *
     * 取值解释（[interpret]）与键入回显（[echoTextFor]）吃的是**同一份**生效文本 ——
     * 原版 switch 与 `Print(str)`（:1280）看到的就是同一个 `str`。
     */
    private fun deliverText(request: InputRequest, rawText: String): String {
        val stripped = if (rawText.contains(ESCAPE_SKIP)) rawText.replace(ESCAPE_SKIP, "") else rawText
        return if (request.oneInput && stripped.length > 1) stripped.substring(0, 1) else stripped
    }

    /**
     * 一次**已交付**（[InputOutcome] 非空）交付的键入回显文本（原版 :1280 `Print(str)` 的实参）；
     * null = 这次交付不回显。只在交付消费点调用 —— 解析失败（原版 `return false`，:1125-1128）、
     * VOID 键盘提交（:1417-1420 入口早退）根本交付不了，自然走不到这里。
     * 形态矩阵（原版 = `doInputToEmueraProgram` 的 `str` 在过完 switch 后剩下的值，:1112-1288）：
     *
     * | 等待形态 | 文本提交 | 超时交付 |
     * |---|---|---|
     * | VALUE + INT | 空+默认值+非计时中 ⇒ `DefIntValue.ToString()`（:1120-1123 的 str 替换）；否则原文 | 有默认值 ⇒ `DefIntValue.ToString()`（endTimer :1068 空文本 + IsRunningTimer 已假 :375-377）；无默认值 ⇒ 不交付 |
     * | VALUE + STR | 空+默认值+非计时中 ⇒ `DefStrValue`（:1191-1193）；否则原文（空串 ⇒ 不回显） | 有默认值 ⇒ `DefStrValue`；否则空串 ⇒ 不回显 |
     * | ANY_VALUE | 原文（str 原样，:1256-1274） | 空串 ⇒ 不回显 |
     * | ENTER_KEY / ANY_KEY / PRIMITIVE_MOUSE_KEY | 原文（switch 无 case ⇒ str 直落 :1280） | 空串 ⇒ 不回显 |
     * | VOID | 键盘提交 :1417-1420 入口早退，走不到这里 | 空串 ⇒ 不回显 |
     *
     * 取色条目不回显：原版是变量直写（`MainWindow.cs:795`/`:828`），不经 PressEnterKey 的
     * 文本通道。空串（含默认值替换后仍为空）一律 null —— `Print` 的空串早退（:3167-3172）
     * 等于没打。参数 [timed]/[timerFired] 只为还原 `IsRunningTimer`（计时中的空串不吃默认值，
     * :1120/:1191 的 `!IsRunningTimer` 条件）。
     */
    private fun echoTextFor(
        request: InputRequest,
        entry: Entry?,
        timed: Boolean,
        timerFired: Boolean,
    ): String? {
        if (entry == null) {
            // 超时交付：endTimer → RunEmueraProgram("")（:1068）。isTimeout 已置真（:1043）
            // ⇒ IsRunningTimer 为假 ⇒ 空文本吃默认值（:1120-1123/:1190-1194）；其余形态空串
            // 直落 :1280 ⇒ Print("") 早退 ⇒ 不回显。
            if (request.form != InputRequest.RequestForm.VALUE) return null
            return when (request.type) {
                InputRequest.InputType.INT -> request.defInt.toString().takeIf { request.hasDef }
                InputRequest.InputType.STR -> request.defStr.takeIf { request.hasDef }
            }
        }
        if (entry.mappedColor != null) return null
        if (entry.cbgButtonValue != null) return null
        if (request.form == InputRequest.RequestForm.VOID) return null
        val text = deliverText(request, entry.text)
        // 「空 + 默认值 + 非计时中」替换成默认值串（:1123/:1193）。计时中的空串对 INT 是
        // 解析失败（交付不了，走不到这里）、对 STR 是按空串交付（:1190-1204）⇒ 空串不回显。
        val timerRunning = timed && !timerFired
        val effective = when (request.form) {
            InputRequest.RequestForm.VALUE -> when (request.type) {
                InputRequest.InputType.INT ->
                    if (text.isEmpty() && request.hasDef && !timerRunning) request.defInt.toString() else text
                InputRequest.InputType.STR ->
                    if (text.isEmpty() && request.hasDef && !timerRunning) request.defStr else text
            }
            else -> text
        }
        return effective.ifEmpty { null }
    }

    /**
     * 按形态解释一条提交（`entry == null` 表示「这一次循环手里没有可交付的提交」：
     * 要么队列空，要么计时器先到、那条提交留给下一次等待）。
     *
     * 返回 null = 这次循环什么都不交付、继续等待（原版 `doInputToEmueraProgram` 返回 false
     * ⇒ `RunEmueraProgram` 于 `EmueraConsole.cs:1094` 不收尾）。
     * 逐条对应 EmueraConsole.cs:1112-1288 的 switch 各分支与 :1040-1082 的超时收尾。
     */
    private fun interpret(
        request: InputRequest,
        entry: Entry?,
        timed: Boolean,
        timerFired: Boolean,
    ): InputOutcome? {
        val form = request.form
        // 原版 `IsRunningTimer`：限时等待且尚未超时（EmueraConsole.cs:371-381）。
        // 超时之后 `endTimer` 已把 isTimeout 置真 ⇒ 计时不再进行 ⇒ 空文本可以吃默认值。
        val timerRunning = timed && !timerFired
        if (entry == null) {
            if (!timerFired) return null
            // endTimer → RunEmueraProgram("") 的空文本交付路径（:1068 + :1112-1288）。
            return when (form) {
                InputRequest.RequestForm.VOID,
                InputRequest.RequestForm.ENTER_KEY,
                InputRequest.RequestForm.ANY_KEY,
                -> InputOutcome(isTimeout = true, writesInt = false, writesStr = false)
                InputRequest.RequestForm.VALUE -> when (request.type) {
                    // 整数：有默认值 ⇒ 交 DefaultInt；无默认值 ⇒ TryParse("") 失败 ⇒ 不推进（:1119-1128）
                    InputRequest.InputType.INT ->
                        if (request.hasDef) {
                            InputOutcome(intValue = request.defInt, isTimeout = true, writesStr = false)
                        } else {
                            null
                        }
                    // 字符串：默认值或空串，两种都推进（:1190-1204）
                    InputRequest.InputType.STR ->
                        InputOutcome(strValue = if (request.hasDef) request.defStr else "", isTimeout = true, writesInt = false)
                }
                // AnyValue：空串解析不成整数 ⇒ InputString("")（:1256-1274）
                InputRequest.RequestForm.ANY_VALUE ->
                    InputOutcome(strValue = "", isTimeout = true, writesInt = false)
                // PrimitiveMouseKey：endTimer 直接 InputMouseKey(4,0,0,0,0,0)（:1044-1056）
                // ⇒ RESULT:0 = 4 是「超时交付」的事件类型位；RESULTS 不动。
                InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY ->
                    InputOutcome(intValue = 4L, isTimeout = true, writesStr = false)
            }
        }
        // 遮罩取色条目（[submitMappedColor]）不走文本通道：截断与 `\e` 剥离对它无意义
        // （text 恒为空串），直接按等待形态分派交付，见 [interpretMappedColor]。
        entry.mappedColor?.let { color -> return interpretMappedColor(request, color, timerFired) }
        // CBG 按钮值条目（[submitCbgButtonValue]）同理，交付面见 [interpretCbgButton]。
        entry.cbgButtonValue?.let { value -> return interpretCbgButton(request, value, timerFired) }
        // 原版按段切分后逐段截断（:1440-1443）；本移植的提交不带分隔符，等价于「整条就是一段」。
        val text = deliverText(request, entry.text)
        return when (form) {
            // EnterKey/AnyKey：任何一条提交都推进（:1421-1424 顺带 stopTimer），
            // switch 没有对应 case ⇒ 不写 RESULT/RESULTS（:1276-1287）。
            InputRequest.RequestForm.ENTER_KEY,
            InputRequest.RequestForm.ANY_KEY,
            -> InputOutcome(isTimeout = timerFired, writesInt = false, writesStr = false)
            // Void 走不到这里（上面不出队），保留分支只为穷尽 when。
            InputRequest.RequestForm.VOID -> null
            InputRequest.RequestForm.VALUE -> when (request.type) {
                InputRequest.InputType.INT -> {
                    if (text.isEmpty() && request.hasDef && !timerRunning) {
                        return InputOutcome(intValue = request.defInt, isTimeout = timerFired, writesStr = false)
                    }
                    val v = text.trim().toLongOrNull() ?: return null
                    InputOutcome(intValue = v, isTimeout = timerFired, writesStr = false)
                }
                InputRequest.InputType.STR -> {
                    val s = if (text.isEmpty() && request.hasDef && !timerRunning) request.defStr else text
                    InputOutcome(strValue = s, isTimeout = timerFired, writesInt = false)
                }
            }
            // AnyValue（INPUTANY）：能解析就只写 RESULT，否则只写 RESULTS（:1256-1274）。
            // 注意默认值不参与：原版该分支没有 HasDefValue 判断。
            InputRequest.RequestForm.ANY_VALUE -> {
                val v = text.trim().toLongOrNull()
                if (v != null) {
                    InputOutcome(intValue = v, isTimeout = timerFired, writesStr = false)
                } else {
                    InputOutcome(strValue = text, isTimeout = timerFired, writesInt = false)
                }
            }
            // PrimitiveMouseKey：一条文本提交同样推进（switch 无 case ⇒ 不写值，:1276-1287）。
            InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY ->
                InputOutcome(isTimeout = timerFired, writesInt = false, writesStr = false)
        }
    }

    /**
     * 遮罩取色条目（[Entry.mappedColor] 非空，[submitMappedColor] 入队）按等待形态的交付分派。
     *
     * 形态闸与原版窗口侧的鼠标按下分支一一对应（`MainWindow.cs` `mainPicBox_MouseDown`）：
     * - **INPUTMOUSEKEY**（`IsWaitingPrimitive`，spec: `EmueraConsole.cs:383-392`）：原版每次
     *   鼠标按下都推进等待（`MainWindow.cs:790` → `EmueraConsole.cs:1303-1330` 的
     *   `MouseDown` → `InputMouseKey(1, ...)` → `:1332-1346` 写 RESULT:0..5 后
     *   `RunEmueraProgram(null)`），遮罩色另写 RESULT:6（`MainWindow.cs:795`）。本移植的
     *   事件五元组（坐标/按钮/键码，`Process.cs:393-401` 的 `InputResult5` 形态）未接
     *   （既有 TODO(verify)），色先走交付主通道（sem INPUTMOUSEKEY 支的 `o.writesInt`
     *   分支写 RESULT:0）；忠实槽位（RESULT:6）要随交付扩槽，见交付「需组长接线」。
     * - **INPUT 系带鼠标**（`IsWaintingInputWithMouse`，spec: `EmueraConsole.cs:286-293` 的
     *   `inputReq.MouseInput`）：原版色写 RESULT:3（`MainWindow.cs:828`）、按钮值走
     *   `PressEnterKey`（`:836-846`）；本移植长按只取色不按按钮（手势语义见 MainActivity
     *   长按段），色走交付主通道（INT → RESULT:0 / STR → RESULTS 十进制串，与原版
     *   `InputInteger`/`InputString` 的槽类型同型）。
     * - **WAIT 系**（ENTER_KEY/ANY_KEY）：原版左键按下即一次回车推进
     *   （`MainWindow.cs:806-821` 的 `IsWaitingEnterKey` 支），色不写（`:828` 在带鼠标支里）
     *   ⇒ 推进、不写值，与文本提交的 ENTER_KEY 支同型。
     * - **其余**（VALUE 无鼠标 / ANY_VALUE）：原版鼠标按下对它们无支可落
     *   （`:824`/`:851` 都要求 `IsWaintingInputWithMouse`；INPUTANY 无鼠标参数，
     *   spec: `FunctionIdentifier.cs:2095-2111`）⇒ 返回 null = 丢弃、不推进、
     *   不留给下一次等待（调用方记 `drop(color)`）。VOID 在 [drainIgnored] 已吃、走不到这里，
     *   保留分支只为穷尽 when。
     *
     * 返回 null = 这条色提交被当前形态拒绝（已出队、不交付、继续等）。
     */
    private fun interpretMappedColor(request: InputRequest, color: Long, timerFired: Boolean): InputOutcome? =
        when (request.form) {
            InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY ->
                InputOutcome(intValue = color, isTimeout = timerFired, writesStr = false)
            InputRequest.RequestForm.VALUE ->
                if (request.mouseInput) {
                    when (request.type) {
                        InputRequest.InputType.INT ->
                            InputOutcome(intValue = color, isTimeout = timerFired, writesStr = false)
                        InputRequest.InputType.STR ->
                            InputOutcome(strValue = color.toString(), isTimeout = timerFired, writesInt = false)
                    }
                } else {
                    null
                }
            InputRequest.RequestForm.ENTER_KEY, InputRequest.RequestForm.ANY_KEY ->
                InputOutcome(isTimeout = timerFired, writesInt = false, writesStr = false)
            InputRequest.RequestForm.ANY_VALUE, InputRequest.RequestForm.VOID -> null
        }

    /**
     * CBG 按钮值条目（[Entry.cbgButtonValue] 非空，[submitCbgButtonValue] 入队）按等待形态的交付分派。
     *
     * 闸口是原版的 `IsWaitingPrimitive`：`EmueraConsole.MouseDown` 一进门就
     * `if (!IsWaitingPrimitive) return;`（`EmueraConsole.cs:1300-1305`），取色图采样与
     * `InputMouseKey` 全在这道闸之后（`:1309-1337`）⇒ **只有 INPUTMOUSEKEY 等待会消费
     * 按钮值**。其余形态原版连取色都不做，本移植按「出队、不推进、不留给下一次等待」丢弃
     * （调用方记 `drop(cbgbtn)`），与 [interpretMappedColor] 的丢弃支同一口径。
     *
     * 交付：值走主通道（`writesInt`），sem 的 INPUTMOUSEKEY 分支写 `RESULT:0`
     * —— 原版落 `RESULT:4`，槽位偏差与卡点见 [submitCbgButtonValue] 的 KDoc。
     *
     * @return null = 被当前形态拒绝（已出队、不交付、继续等）
     */
    private fun interpretCbgButton(request: InputRequest, value: Long, timerFired: Boolean): InputOutcome? =
        when (request.form) {
            InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY ->
                InputOutcome(intValue = value, isTimeout = timerFired, writesStr = false)
            else -> null
        }

    /**
     * 无限挂起。interrupt = 宿主销毁（见类文档「被 interrupt 时的语义」）：恢复中断位后
     * 按关闭收敛。这里**不得**回循环重等 —— 持锁状态下 await 首行即再抛会热转，
     * close 拿不到同一把锁就永远置不上 closed（上游 4f9f379 的裁定，见主线上同一裁语）。
     */
    private fun awaitQuietly() {
        try {
            arrived.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ensureOpen()
            throw InputSinkClosedException("等待被中断：宿主正在销毁，输入队列不再接受等待")
        }
    }

    /** 按剩余纳数挂起；中断收敛同 [awaitQuietly]。 */
    private fun awaitNanosQuietly(remainNanos: Long) {
        try {
            arrived.awaitNanos(remainNanos)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ensureOpen()
            throw InputSinkClosedException("等待被中断：宿主正在销毁，输入队列不再接受等待")
        }
    }

    /** [InputSink.consumeMesSkip] 的队列实现：读一次即清。 */
    override fun consumeMesSkip(): Boolean {
        lock.lock()
        try {
            val value = consumedMesSkip
            consumedMesSkip = false
            return value
        } finally {
            lock.unlock()
        }
    }

    /** 关闭队列并唤醒全部等待方（等待方抛 [InputSinkClosedException]）。可重复调用。 */
    override fun close() {
        lock.lock()
        try {
            closed = true
            arrived.signalAll()
        } finally {
            lock.unlock()
        }
    }

    private fun ensureOpen() {
        if (closed) throw InputSinkClosedException()
    }
}
