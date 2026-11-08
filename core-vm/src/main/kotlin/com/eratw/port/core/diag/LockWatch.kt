package com.eratw.port.core.diag

/**
 * 锁持有 / 等待时长采样器（CONTRACT-FEEL1-3LANE §2 L3 建、CONTRACT-OBS2-3LANE §2 L2 扩）：
 * 纯 JVM、**缺省关**，按「挂载点 + 线程名」聚合「最长一次持有、累计等待、样本数」。
 * 观测面，不参与任何判定。
 *
 * OBS2-L2（2026-11-05）结的两格：① 补上两片还在黑箱的锁面（[ID_SINK_DISPLAY] 的
 * `displayLines` 监视器、[ID_ERBC_MATERIALIZE_LOCK] 的 erbc `materializeLock`）；
 * ② 给 release 包一个能把观测打开的出口 —— `app/.../DevToolsHost.kt` 的 `lockwatch` 命令
 * （走本类的 [attach] / [detach] / [readout] / [reset]；该通道在 release 包里打不打得到，
 * 见下一节末的「谁能把它打开」与 `DevToolsHost.kt:579` 的 debuggable 闸）。
 *
 * ## 它要结清的病（取证出处，逐条本仓 文件:行号）
 *
 * 真机上「像死锁的卡住」今天全是黑箱 —— 全仓同步原语 ≈600 处（core-vm ≈300、app ≈170、
 * platform ≈60、render+render-canvas ≈34，组长本轮读数），但没有任何一处知道
 * 「哪把锁被谁持了多久、谁在它后面排队」。头号面**不是环路而是单监视器排队**：
 * - [com.eratw.port.core.host.GraphicsBuffer] 的 `pixels` getter 解码全程持本实例监视器
 *   （`core/host/GraphicsBuffer.kt:245` 注释自认「解码全程持锁」），UI 线程解一张可达
 *   122 MiB 的位图期间，VM 侧的 `GCREATE`/`GDISPOSE`/回收全在同一个 monitor 排队；
 * - [com.eratw.port.core.host.SpritePool.evictDeferredPixels] 在同一把池监视器**内遍历全表**
 *   （`core/host/GraphicsBuffer.kt:1887`），每退一张又进该缓冲自己的监视器
 *   （`GraphicsBuffer.kt:463`）；
 * - [com.eratw.port.core.loader.MappedProgramSource]（erbc 句柄，定义在同一文件）物化时整池解码收在
 *   `synchronized(this)` 内（`core/loader/MappedErbc.kt:843`），`close` 同锁互等（`:873`）；
 * - [com.eratw.port.core.proc.QueueInputSink] 的 `submit`（UI 线程，`core/proc/QueueInputSink.kt:664`）
 *   与 `readAnyKey` 的 `arrived.await()`（VM 线程，`:795`）共用一把 [java.util.concurrent.locks.ReentrantLock]。
 *
 * `android.os.StrictMode.detectLocks` 在 compileSdk 36 上不存在
 * （`app/src/main/kotlin/com/eratw/port/android/StrictModeGuard.kt:135-139`）⇒ 观测必须自建。
 *
 * ## 为什么必须是进程级 object（铁律 4 的口径与依据）
 *
 * 铁律 4 禁「静态可变全局」的本意是：**VM 语义状态**必须一 VM 一套（测试并行、读档重建、
 * 未来多开不串味，见 `core/host/RuntimeAux.kt:6-8`）。本表装的**只有观测读数**：
 * 不进出任何判式、不影响任何返回值，因此与语义状态不同类。而挂载点的构造期装配全在
 * 本波不许碰的文件里：图形池由 `core/fn/GraphicsMethods.kt:1880-1881` 建（挂在 `store.aux`
 * 槽上）、erbc 句柄由装载层建（`core/loader/ProgramLoader.kt:627-628` 的 `MappedProgramSource(...)`、
 * 对外的 `open` 在 `:732`）、输入队列由
 * [com.eratw.port.core.proc.GameSession] 的字段初始化建（`core/proc/GameSession.kt:395`）。
 * 「进程级诊断面 + 加短监视器 + 有界上限 + 缺省关」是同问题的既有解法，口径照
 * `core/stmt/ArgInterner.kt:1054` 的 `FoldAuditState`（理由原文见 `:1046-1052`）。
 *
 * **实例态这条路不只是「不许碰那几个文件」，它根本走不通**（决定性证据）：
 * 测试装置的 `l3.StateHash`（`core-vm/src/test/kotlin/com/eratw/port/l3/StateHash.kt`）把
 * `store.aux` 的 gfx/sprite 簇按**字段名**反射混进 L3 链值，
 * 给 `GraphicsBuffer`/`SpritePool` 加一根探针字段就会顶掉已登记的链尾 ——
 * 前科原文在 `core-vm/src/test/kotlin/com/eratw/port/l3/L3HashChainTest.kt:740-746`
 * （2026-10-30 那落只因为两枚诊断计数器就重落了链尾），`SpritePool` 自己也把这条写成了硬纪律
 * （`core/host/GraphicsBuffer.kt:1912`「不新增实例字段是本池的硬纪律」）。本波是**观测波**，
 * 零语义位移是判据，重落 L3 链等于自己制造一条需要重登的语义位移 ⇒ 本类**不给那两个类加任何
 * 实例字段**，采样态只活在本 object 的定长数组里。
 * 与它同形的三点纪律：
 * 1. **缺省关**：[enabled] = false；关时挂载点只付一次 volatile 布尔读（[handleOrNull]），
 *   不取时钟、不进本监视器、零分配 —— 与 `ProcessRuntime.statementProbe`
 *   （`core/proc/ProcessRuntime.kt:288`、守卫结构 `:1138`）同口径。
 * 2. **有界**：见下面「容量与算术」一节；`FoldAuditState` 也自带 `MAX_LOG = 200` 上限。
 * 3. **可复位**：[reset] 供装置与单测隔离；`enabled` 一旦有人打开就是全进程一档，
 *   与 `StmtInternAudit` 同风险面，已在交付里向组长点明。
 *
 * 开关的**初值**读一次 `System.getProperty([PROPERTY_ENABLED])`（[PROPERTY_ENABLED]）：
 * 与 `GraphicsBuffer.sharedPixelBudgetBytes()`（`core/host/GraphicsBuffer.kt:1696`）
 * 共用「性质即旋钮」这条既有口径，但**每读现取**那条纪律不适用于此处 —— 预算是判据，
 * 现取才不被缓存顶掉；本性质只是「装不装探针」的开关，热路径每发现取就要付
 * `System.getProperty` 的属性表线性扫（同一文件 `:1396` 刚把这笔代价登记在册），
 * 所以这里在 object 初始化时读一次、之后由 [enabled] 这个 volatile 位承担。
 *
 * ## 谁能把它打开（真机与 release 的出口口径）
 *
 * 三条路，只有两条现在真的存在：
 * 1. **桌面闸**：`-Plockwatch=true` → `core-vm/build.gradle.kts:131` 把它透传成 test JVM 的
 *    `eratw.lockwatch` 性质 ⇒ 走 [enabled] 的**初值**，不经 [attach]。
 * 2. **真机 debug 包**：DevTools 通道的 `lockwatch on|off|readout|reset` 命令
 *    （`app/.../DevToolsHost.kt` 的 `LockWatchCommand`，OBS2-L2 新增）⇒ 走 [attach] / [detach]。
 *    这一档是 OBS2 补的那格「release 包里没人能把观测打开」的**半成品**：命令确实接上了，
 *    但通道本身在 `DevToolsHost.kt:579` 被 `FLAG_DEBUGGABLE` 闸住，**release 包里连轮询线程都
 *    不会起**，所以玩家/作者拿不到 —— 差的那一格与接线方案写在 OBS2-L2 交付的
 *    「需组长接线」里，本波不越界去动装配面。
 * 3. **release 的装配期挂载**（按存档/QOL 开关决定挂不挂）：不存在，本波也不新增
 *    （新增就是「玩家可见变化」，与零语义位移的判据冲突）。
 *
 * ## 挂载点（全仓唯一八处；只加探针，一处锁的粒度/顺序/语义都没动）
 *
 * 行号是 2026-11-05 在 `main@ec3e4585` 上逐条重核过的读数（FEEL1 落 main 后 app/core 两侧
 * 都有文件被别的波次插过行，原表里的行号有相当一部分已经飘了 —— 飘了就在这里改，
 * 别拿旧表当盘面；每次改锁面文件都要回这一节核一遍）。
 *
 * | id 常数 | 读数名 | 被观察的锁 | 挂载文件:行（挂载后源码，守卫口 → 记录口） |
 * |---|---|---|---|
 * | [ID_GFX_PIXELS_DECODE] | `gfx.pixels.decode` | GraphicsBuffer 实例监视器（解码段全程） | `core/host/GraphicsBuffer.kt` 的 `pixels` getter：守卫 `:238` → 监视器 `:242` → 记录 `:283` |
 * | [ID_SPRITE_POOL_EVICT] | `spritepool.evict` | SpritePool 实例监视器（锁内遍历全表） | `core/host/GraphicsBuffer.kt` 的 `evictDeferredPixels`：守卫 `:1884` → 监视器 `:1887` → 记录 `:1903` |
 * | [ID_ERBC_MATERIALIZE] | `erbc.materialize` | `MappedProgramSource`（erbc 句柄）实例监视器（整池解码段） | `core/loader/MappedErbc.kt` 的 `program(mark)`（`:831`）：守卫 `:839` → 监视器 `:843` → 记录 `:856` |
 * | [ID_ERBC_CLOSE] | `erbc.close` | 同一把 `MappedProgramSource` 监视器（与物化段互等那一支） | `core/loader/MappedErbc.kt` 的 `close`（`:863`）：守卫 `:868` → 监视器 `:873` → 记录 `:891` |
 * | [ID_SINK_SUBMIT] | `sink.submit` | QueueInputSink 的 ReentrantLock | `core/proc/QueueInputSink.kt` 的 `submit`（`:664`）：守卫 `:667` → `lock()` `:669` → `unlock()` `:683` → 记录 `:688` |
 * | [ID_SINK_ANYKEY_AWAIT] | `sink.anykey.await` | QueueInputSink 条件变量 `arrived` | `core/proc/QueueInputSink.kt` 的 `readAnyKey`（`:751`）：守卫 `:755` → `await()` `:795` → 记录 `:807` |
 * | [ID_SINK_DISPLAY] | `sink.display` | `LineBufferConsoleSink` 唯一的 `displayLines` 监视器（**写侧入列段 + 读侧快照段共用这一个 id**，靠线程名分槽） | `core/host/LineBufferConsoleSink.kt`：读侧 `lines()`（`:474`）守卫 `:475` → 监视器 `:479` → 记录 `:486`；写侧 `appendGroup`（`:3326`）守卫 `:3339` → 监视器 `:3343` → 记录 `:3428` |
 * | [ID_ERBC_MATERIALIZE_LOCK] | `erbc.materialize.lock` | `LazyStatementRecords` 的 `materializeLock`（CMP-56 那条串行解码锁；**逐条物化段 + 建片趟压扁段共用这一个 id**） | `core/loader/ErbcStream.kt`：`statementAt`（`:1837`）守卫 `:1847` → 监视器 `:1851` → 记录 `:1866`；`buildFlatStatementsRange`（`:1888`）守卫 `:1896` → 监视器 `:1900` → 记录 `:1923` |
 *
 * 后两个挂载点是 OBS2-L2（2026-11-05）补的两片黑箱锁面，各取**两个代表性临界区**而不是全表：
 * - `sink.display` 只钉「写侧入列」与「读侧快照」这一对（本类共 14 处 `synchronized(displayLines)`
 *   临界区，2026-11-05 逐行数得；余下 12 处钉上去只会把同一件事记十几遍，而这一对正是
 *   「UI 线程拷贝上千行、VM 线程在后面排队」的那个病灶，见下面的锁序表第 5 条）；
 * - `erbc.materialize.lock` 只钉 `statementAt`（运行期首次取用 = 排队面）与
 *   `buildFlatStatementsRange`（建片趟整段 = 最长持有）。**没钉的两处**：`releaseRaws`
 *   （`:1932`，装载后一次的状态位 + 已物化时的一次遍历）、`seedInterning`（`:1944`，
 *   装载期一次）—— 后者的时长本来就落在 `erbc.materialize` 那段里（同一次句柄监视器持有，
 *   见锁序表第 8 条），钉了只会重复记账。**契约里「materializeLock 的四处覆盖物化 + close」
 *   的 close 一支在本仓不存在**：`releaseMmap`（`:1974`）取的是 `window.closeUnderlying()`，
 *   不进 `materializeLock`（同文件只有 `:1851/:1900/:1932/:1944` 四处 `synchronized`），
 *   句柄的 close 面已由 [ID_ERBC_CLOSE] 覆盖 ⇒ 本挂载点交的是「逐条物化 + 建片趟」。
 *
 * `tag` 列（[noteSegment] 的第 5 参）只记「最长那一次」的身份，用来回答「是不是那一张
 * 8000x4000」：解码段 = 本代像素点数（`宽 × 高`）、回收段 = 本次退回的像素点数之和、
 * 物化段 = 本份产物的字节数、close = 走的是哪一支（1 = 通道留给惰性语句池、2 = 通道当场关掉）、
 * `submit` = 提交文本长度、`sink.display` = **行数**（写侧 = 本次入列的行数 / 读侧 = 本次
 * 拷出的行数，两者都答「这一次到底搬了多少行」）、`erbc.materialize.lock` = 锁内解码的
 * **语句条数**（逐条物化恒 1、建片趟 = 本趟条数）、`sink.anykey.await` = 0（等待点没有身份读数）。
 *
 * 两个共用一个 id 的挂载点靠**线程名**分槽读数（[ID_SINK_DISPLAY] = VM 线程 `eratw-vm` 写侧 +
 * UI 线程 `main` 读侧；[ID_ERBC_MATERIALIZE_LOCK] = 装载工作线程 `erbc-intern-seed`
 * （线程名在 `core/loader/ErbcStream.kt:2844`）与建片趟所在的 VM 线程）——
 * 「同一条监视器、两个方向」正是要看的对，合成两个 id 反而会把排队关系拆散。
 *
 * ## 锁序表（全仓唯一声明处；散落注释一律指到这里）
 *
 * **本表只列既有边**；FEEL1 波未新增边，CLICKQ 波（2026-11-04）新增第 7 条**叶锁边**，
 * OBS2 波（2026-11-05）新增第 8、9 两条（erbc 装载链）并把第 5 条的 `displayLines` 面接上探针。
 * 要判断「是不是死锁」必须先有锁序图，而此前这张图只活在
 * 零散注释里（`app/.../GameHost.kt:612`、`:615`、`:1116`）。既有边逐条带 文件:行号
 * （**行号是 2026-11-05 在 `main@ec3e4585` 上逐条重核的**：FEEL1 落 main 后 GameHost /
 * QueueInputSink / GraphicsBuffer 都被别的波次插过行，原表飘了的一批已就地改正；
 * app 侧那几个文件一改就要回这里核一遍）：
 *
 * 1. `GameHost.pendingLock → GameHost.pauseLock`
 *    —— `app/src/main/kotlin/com/eratw/port/android/GameHost.kt:612-613`（成文约定，
 *    「只有 [attachSession] 同时持有两把」`:613`；声明 pendingLock `:501`、pauseLock `:619`）、
 *    `:1108` 取 pendingLock 后 `:1117` 取 pauseLock（实现点，约定原文也出现在 `:1116`）。
 * 2. `GameHost.pendingLock → QueueInputSink 的 ReentrantLock`（跨模块嵌套）
 *    —— `GameHost.kt:1041` 取 pendingLock 后 `:1044` 调 [deliver]，[deliver] 内 `:1072` 调
 *    [com.eratw.port.core.proc.QueueInputSink.submit]（`core/proc/QueueInputSink.kt:664` 起，
 *    `lock.lock()` 在 `:669`）；抢跑文本在同一次 pendingLock 里入队（`GameHost.kt:1046`），
 *    装配后补交走的还是这条边（`:1108` 取 pendingLock → 同一处 [deliver]）。
 * 3. `GameHost.pauseLock → GameSession.backgroundPauseLock`
 *    —— `GameHost.kt:1899` 取 pauseLock 后 `:1902` 调 `session.setBackgroundPaused`
 *    （`core/proc/GameSession.kt:1137`，锁声明 `:421`）；装配期就绪发布同锁
 *    （`core/proc/GameSession.kt:969`）；换会话补应用也在 pendingLock 里取它
 *    （`GameHost.kt:1117` → `GameSession.kt:1137`）⇒ 这是 pendingLock → pauseLock → backgroundPauseLock
 *    的一条链，不是三条并列边。
 * 4. `SpritePool 监视器 → GraphicsBuffer 监视器`（池 → 条目，单向）
 *    —— `core/host/GraphicsBuffer.kt:1887` 的锁内遍历逐张调
 *    [com.eratw.port.core.host.GraphicsBuffer.evictDeferred]（`GraphicsBuffer.kt:458` 进条目监视器）；
 *    同形边还有 `GraphicsPool` 的 `@Synchronized` 回收面（`GraphicsBuffer.kt:1457-1458` 逐张
 *    `buffer.evictDeferred()`、`:1487-1488` 预算档同形）。**反向（条目监视器 → 池监视器）未找到取锁点**。
 * 5. `LineBufferConsoleSink` 只有一把监视器，临界区互不嵌套
 *    —— `core/host/LineBufferConsoleSink.kt:49`（口径原文「谁都不在对方持锁时调用」）、
 *    口径原文 `:43`「锁内只做纯列表/排版操作，不允许调用任何外部回调」、唯一监视器声明 `:2483`
 *    （`private val displayLines = ArrayDeque<DisplayLine>()`）。OBS2 把 [ID_SINK_DISPLAY] 接在
 *    这一对的两侧：**写侧** `appendGroup`（`:3343`，VM 线程）与**读侧** `lines()`（`:479`，UI 线程）
 *    ⇒ 「UI 线程在锁里 `toList` 上千行、VM 线程的下一次 flush 排在同一条监视器后面」这一档从此
 *    有读数。这一条**不是**新边（同一把锁的两个方向），要新增边必须先回这一节改口径。
 *    取锁点全表读数：读侧 UI 线程 `render/src/main/kotlin/com/eratw/port/render/adapter/ConsoleFeed.kt:354`
 *    （`frameSnapshot` → `lines()`，该类**无任何 `synchronized`**）、
 *    `app/.../MainActivity.kt:2597`（竖屏 HUD 尾窗，取用点无锁）；
 *    读侧 VM 线程 `core/proc/ConsoleBeginHost.kt:145`、`:157`；
 *    读侧 devtools daemon 线程 `app/.../DevToolsHost.kt:540` → `GameHost.kt:1847`（`tailText`）；
 *    读侧崩溃报告线程 `app/.../ErrorReportCollector.kt:486`（同一个出口）。
 *    **这些取锁点没有一个在别的锁里**（逐个查过宿主函数体：`GameHost.currentOptions`
 *    `:1200-1208`、`isCurrentGeneration` `:1227-1234` 都不取锁；DevToolsHost 全文无 `synchronized`），
 *    且 `QueueInputSink` 全文不引用控制台 ⇒ 本波**没有**「别的锁 → displayLines」这条入边。
 * 6. **反向取锁证据：未找到**（组长本轮取证原话，本波不改锁、也不修任何疑似环）。
 * 7. `GameHost.pendingLock → KeyEventInputState.intentLock`（CLICKQ，2026-11-04；**叶锁**）
 *    —— 执行期点击意图槽：`GameHost.kt:1041` 取 pendingLock 后 `:1044` 调 [deliver]，
 *    `:1066` 调 [com.eratw.port.core.fn.KeyEventInputState.noteClickIntent]
 *    （`core/fn/KeyEventInputState.kt:126` 取 intentLock，声明 `:117`）；换局清槽同锁内
 *    （`GameHost.kt:1119`）。**intentLock 必须保持叶锁**：临界区只做文本框端口的 volatile
 *    读/写（`core/proc/ConsoleBuiltinHost.kt:324` 的 `text`（`@Volatile` 声明 `:323`）/
 *    `:334` 的 `setText`，两者不取锁），
 *    不得回调输入主机、不得取 [QueueInputSink][com.eratw.port.core.proc.QueueInputSink]
 *    的锁。反向边不存在：`submitInternal` 的 `clearClickIntent`（`GameHost.kt:1036`）取在
 *    pendingLock **之前**；`QueueInputSink.onWaitEntered`（声明 `core/proc/QueueInputSink.kt:360`，
 *    调用点 `:753`、`:819`）在 sink 的 ReentrantLock **之外**调用同一把清槽 ⇒ 无 sinkLock → intentLock 边。
 *    读侧（VM 线程 `keyState` → `resolveClickIntent`，`core/fn/KeyEventInputState.kt:160`、`:188`）
 *    只走这条叶锁，不与任何业务锁交叉。
 * 8. `MappedProgramSource 监视器（erbc 句柄）→ LazyStatementRecords.materializeLock`
 *    （OBS2，2026-11-05；装载期种子去重）
 *    —— `core/loader/MappedErbc.kt:843` 取句柄监视器后 `:847` 调 `file?.toProgram(...)` →
 *    `MappedErbc.kt:500` `ProgramLoader.decodeMapped(...)` → `ProgramLoader.kt:663`
 *    `ErbcStreamDecoder.decode(...)` → `core/loader/ErbcStream.kt:2368` `decodeProgram()` →
 *    装载趟内 `:2834`（兼容档，主装载线程）或 `:2840`（并行档，`erbc-intern-seed` 线程，
 *    线程名 `:2844`）调 `program.internStatementArgs()` → `core/code/Program.kt:1450`
 *    `lazy.seedInterning(...)` → `core/loader/ErbcStream.kt:1944` 取 materializeLock。
 *    **读数含义**：这一条边让「句柄监视器的持有时长」把整趟种子去重（真机实测 5.8 s，
 *    `core/stmt/ArgInterner.kt:130` 记着）也算进去 ⇒ `erbc.materialize` 那一行长不等于
 *    materializeLock 自己长；要看后者只看 `erbc.materialize.lock` 行。
 *    并行档下这条边是「主线程持句柄监视器 + 工作线程抢 materializeLock」的**跨线程等待**，
 *    不是嵌套（`:2838-2844`）；两档都只在装载期发生一次。
 *    **反向边未找到**：materializeLock 的四处临界区
 *    （`ErbcStream.kt:1851`、`:1900`、`:1932`、`:1944` = 该文件全部 `synchronized`）只调
 *    `RecordSource`/`SectionWindow`/`ArgInterner`，全程不回句柄监视器（同文件除这四处无同步原语）。
 * 9. `LazyStatementRecords.materializeLock → FoldAuditState.lock`（OBS2，2026-11-05；
 *    **仅折叠审计开启时存在**；叶锁）
 *    —— materializeLock 段内调 `interner.run(...)`（`core/loader/ErbcStream.kt:1857` 逐条物化、
 *    `:1912` 建片趟）→ `core/stmt/ArgInterner.kt:536` `FoldAuditState.fold(o, rep)`
 *    （`:286` 字符串归一同口）→ `core/stmt/ArgInterner.kt:1086` 取 `FoldAuditState.lock`
 *    （声明 `:1075`）。**这一条只在 `StmtInternAudit.enabled` = true 时存在**（`:536` 的判据
 *    就是它），常规装载不取。反向边不存在：`fold` 的临界区只做计数与追加字符串
 *    （`:1086-1096`，摘要构造 `keyOf` 在锁外 `:1084-1085`），不回调、不取别的锁 ⇒ 叶锁。
 *
 * 探针自身引入的**唯一**嵌套（仅挂载时存在）：挂载点监视器 → 本 object 的 [lock]。
 * 这条边不构成环的充要条件是 [lock] 为**叶锁**：本类临界区内只读写构造期定长的数组、
 * 至多造本类自己的只读快照（[snapshotLocked]），不取任何其他锁、不回调外部代码、不做 IO ——
 * 报告行的字符串拼装与落盘都在锁外
 * （形状照 `app/.../RunWatch.kt:76-82` 的 `TrailWriteGate`「锁内只取快照、磁盘写挪出观察锁」，
 * 回调锁外口径照 `core/proc/QueueInputSink.kt:43-45`）。**任何人要在本类临界区里
 * 调外部代码 ⇒ 必须先回来看这一节。**
 *
 * ## 容量与算术（§0 第 10 条有界性硬约束；前科是 42 MB 无界台账）
 *
 * - [LOCK_COUNT] = 8：**用满，零余量**。算式 = 挂载点上表现用 id 数 8（0..7）⇒
 *   「刚够盖住用量的 2 的幂」= 2³ = 8，白占的常驻槽 = `8 - 8` × [THREAD_SLOTS] = **0 槽**。
 *   FEEL1 当初留的 2 格余量正是「同形挂载点成对新增」那一格，OBS2-L2（2026-11-05）把它花掉：
 *   新增 [ID_SINK_DISPLAY] 与 [ID_ERBC_MATERIALIZE_LOCK] 两个 id，8 = 盖住 8 的最小 2 的幂 ⇒
 *   **常数不变、表长不变**，改的是 `LOCK_NAMES` 的最后两格（原 `unused#6/7`）与这条算式。
 *   下一笔新增（例如 FEEL-12 把 `GraphicsBuffer` 的监视器拆成两把 ⇒ +2 个 id ⇒ 用量 10）
 *   必须把 [LOCK_COUNT] 抬到 **16**（盖住 10 的最小 2 的幂），常驻随之从 32 槽涨到 64 槽
 *   （≈1.8 KiB → ≈3.6 KiB，仍远低于 `PageFunctionTrail` 那条「KiB 级不构成开关理由」的下限），
 *   并同步 [LOCK_NAMES] 与 `LockWatchMountPinTest` 的容量钉子 —— **钉子会先红**，
 *   它写死读 `const val LOCK_COUNT = 8` 与「`LOCK_NAMES` 里不许再有 `unused#`」两条。
 *   越界 id 不丢账：进 [outOfRangeSamples] 计数（所以忘了扩容量最多是让新挂载点落进兜底行，
 *   不会静默丢读数，也不会抛）。
 * - [THREAD_SLOTS] = 4（同一挂载点下按线程名分槽）：本仓实测并发线程名 ≤ 3 ——
 *   `eratw-vm` 脚本线程（`app/.../GameHost.kt:4100` 的 `VM_THREAD_NAME`，另有
 *   `app/.../StallSentinel.kt:172` 同一份常量的只读副本）、`main` UI 线程、
 *   `eratw-devtools` 调试轮询（`app/.../DevToolsHost.kt:564`），加上 ADR-003 的单 VM 线程模型；
 *   第 3 槽给装载期的工作线程（`core/stmt/ArgInterner.kt:1040-1044` 记有 2-3 条，
 *   OBS2 新挂载点里它就是 `erbc-intern-seed`，`core/loader/ErbcStream.kt:2844`），第 4 槽
 *   固定作**其他线程合并桶**（[MERGED_SLOT]）：满了不再新增线程名，只把样本并进这一槽
 *   并逐条计进 [slotMergedSamples] ⇒ 粒度降级、总量不丢、表恒 4 槽。
 *   **OBS2 的两个新 id 都靠这一节分槽**：`sink.display` = `eratw-vm`（写侧）+ `main`（读侧），
 *   `erbc.materialize.lock` = `erbc-intern-seed` / 装载主线程 + `eratw-vm`（建片趟），
 *   各自 ≤ 3 条线程名 ⇒ 4 槽仍然够用，不必扩。
 * - 常驻 = `LOCK_COUNT × THREAD_SLOTS` = 32 槽 × 每槽
 *   1 引用 + 7 个 long（样本数 + 4 个时长累计 + 2 个 tag）≈ 1.8 KiB（压缩 oops 口径）。
 *   对照 512 MiB 堆挡位（`GraphicsBuffer.kt:185` 的 `largeHeap` 账）= 0.00034%，
 *   与 `PageFunctionTrail` 的「KiB 级常驻不构成开关理由」（`core/diag/PageFunctionTrail.kt:30-33`）
 *   同口径。**没有按样本增长的任何结构**：每张表都是构造期一次分配的定长数组。
 *
 * ## 线程与读侧纪律（LoadTrace 的 CME 事故不许在这里复活）
 *
 * 写侧任意线程（挂载点所在的锁的**外侧**，见下一节）；读侧另一条线程（壳层采样、
 * DevTools 通道、崩溃报告拼装）。前科：提交 `49490501` —— `LoadTrace` 写侧挂 monitor、
 * 读侧裸迭代采样表，UI 线程与 VM 线程相撞把一次正常启动判成启动失败。本类的口径：
 * - 写侧与读侧**同一把** [lock] 互斥（`TurnPercentiles` 的公开方法全同步同口径，
 *   `app/.../TurnPercentiles.kt:63-76`）；
 * - 读侧临界区内**只读那几张定长数组**并造出只读快照（[snapshotLocked]），
 *   报告行的字符串拼装都在锁外（[readout] 的 StringBuilder 段）；
 *   [totalSamples] / [hasSamples] 也走同一把 [lock]，没有一处裸迭代写侧的表；
 * - 读侧**禁抛**：[readout] / [snapshot] 整体兜底，取不到就返回降级行
 *   （纪律照 `core/diag/PageFunctionTrail.kt:128-152` 的「读侧禁抛降级」）；
 * - 本表只用于人工定位，**绝不参与判定**。
 *
 * ## 计时与分配
 *
 * 全部用 `System.nanoTime()`（单调时钟），且只在 [enabled] 为真时才取。
 * 写路径零分配：入参是 `Int` id + 三个 `Long` 时标（基本类型，不进框），
 * 线程名走 `Thread.currentThread().name` —— Thread 的 name 是既有字段，读它不分配。
 *
 * ## 等待与持有怎么分开（读数解释必读）
 *
 * `synchronized` 挂载点在 monitorenter 之后、monitorexit 之前各取一次时标不可行
 * （要在不改锁结构的前提下拿到「获锁时刻」，只能把时标写进外层局部变量），
 * 本类用 inline 的 [handleOrNull] + 外层 `var` 承接获锁时标，因此
 * [noteSegment] 能把 `enter → acquired` 记成**排队等待**、`acquired → exit` 记成**持有**。
 * 记录点一律在被观察锁的**外侧**：`pixels` / `evictDeferredPixels` / `program` / `close` 在块后，
 * `submit` 在 `finally` 里 `unlock()` 之后，OBS2 新加的 `sink.display`（`lines()` 与 `appendGroup`）
 * 与 `erbc.materialize.lock`（`statementAt` 与 `buildFlatStatementsRange`）同样在块后 ——
 * 后两组都是「把原来的表达式体/`return` 拆成交出块值」，锁的**范围一字未动**，只是让记录点
 * 落在监视器外面。例外只有 [ID_SINK_ANYKEY_AWAIT]：
 * `await()` 每次返回即一条样本，必须在持锁段内记（否则逐次等待的分布就没了），
 * 承担它的就是上面那条「LockWatch 的 monitor 是叶锁」不变式。
 *
 * 注意早退路径**不记样本**（`pixels` 的「已被 dispose / 无延迟源」、`program` 的
 * 「别的线程已物化」、`appendGroup` 的「重排后本组空了」（`LineBufferConsoleSink.kt:3372`
 * `if (incoming.isEmpty()) return`）、`statementAt` 的双检命中（`ErbcStream.kt:1854`））：
 * 那些路径压根没在锁里干活，记了只会稀释读数。
 *
 * @see handleOrNull
 */
object LockWatch {

    /** 开关性质的名字（object 初始化时读一次；见类 KDoc「为什么必须是进程级 object」）。 */
    const val PROPERTY_ENABLED = "eratw.lockwatch"

    // ---- 挂载点 id（写死的连续编号；扩它必须同步扩 LOCK_COUNT 并改类 KDoc 的挂载点表） ----

    /** GraphicsBuffer.pixels 的解码段（本实例监视器全程）。 */
    const val ID_GFX_PIXELS_DECODE = 0

    /** SpritePool.evictDeferredPixels（池监视器内遍历全表）。 */
    const val ID_SPRITE_POOL_EVICT = 1

    /** [com.eratw.port.core.loader.MappedProgramSource].program（句柄监视器内的整池解码段）。 */
    const val ID_ERBC_MATERIALIZE = 2

    /** [com.eratw.port.core.loader.MappedProgramSource].close（与物化段同一把句柄监视器；`MappedErbc.kt:843` 与 `:872` 互等那一支）。 */
    const val ID_ERBC_CLOSE = 3

    /** QueueInputSink.submit（输入 ReentrantLock）。 */
    const val ID_SINK_SUBMIT = 4

    /** QueueInputSink.readAnyKey 的 arrived.await（条件变量等待）。 */
    const val ID_SINK_ANYKEY_AWAIT = 5

    /** [com.eratw.port.core.host.LineBufferConsoleSink] 的 `displayLines` 监视器（写侧入列段 + 读侧快照段）。 */
    const val ID_SINK_DISPLAY = 6

    /** [com.eratw.port.core.loader.LazyStatementRecords] 的 materializeLock（逐条物化段 + 建片趟压扁段）。 */
    const val ID_ERBC_MATERIALIZE_LOCK = 7

    // ---- 容量（写死常数；算术见类 KDoc「容量与算术」） ----

    /** 可登记的挂载点个数上限（现用满 8 个，见类 KDoc 的挂载点表；扩它先改那里的算式）。 */
    const val LOCK_COUNT = 8

    /** 每个挂载点下的线程槽数：3 条具名 + 1 个合并桶（算式见类 KDoc）。 */
    const val THREAD_SLOTS = 4

    /** 合并桶的下标（本挂载点用满 [THREAD_SLOTS] 条线程名之外的样本都并进这一槽）。 */
    const val MERGED_SLOT = THREAD_SLOTS - 1

    /** 槽总数 = [LOCK_COUNT] × [THREAD_SLOTS]（构造期一次分配，之后不增长）。 */
    const val SLOT_COUNT = LOCK_COUNT * THREAD_SLOTS

    /** 读数行的挂载点名（下标 = id；与类 KDoc 挂载点表逐行对应，长度必须等于 [LOCK_COUNT]）。 */
    private val LOCK_NAMES = arrayOf(
        "gfx.pixels.decode",
        "spritepool.evict",
        "erbc.materialize",
        "erbc.close",
        "sink.submit",
        "sink.anykey.await",
        "sink.display",
        "erbc.materialize.lock",
    )

    /**
     * 总开关（缺省 false = 未挂载 = 全仓零行为零开销）。
     *
     * 初值来自 [PROPERTY_ENABLED]（object 初始化时读一次）；运行时由 [attach] / [detach]
     * 开关（OBS2 起的真机出口 = DevTools 的 `lockwatch on|off`，
     * `app/.../DevToolsHost.kt` 的 `LockWatchCommand`；core 侧不许反向依赖 app，
     * 所以装配面一律由 app 侧调这两个方法，本类不自己挂）。
     * 关时挂载点连 `System.nanoTime()` 都不取（守卫见 [handleOrNull] 与类 KDoc）。
     */
    @Volatile
    var enabled: Boolean = System.getProperty(PROPERTY_ENABLED)?.trim()?.toBoolean() == true

    /** 线程名（下标 = 槽号）；null = 该槽未占用。 */
    private val slotThread = arrayOfNulls<String>(SLOT_COUNT)

    /** 样本数（一次获锁+持锁 = 一条；[noteWait] 也算一条）。 */
    private val slotSamples = LongArray(SLOT_COUNT)

    /** 最长一次持有时长（纳秒）。 */
    private val slotMaxHoldNs = LongArray(SLOT_COUNT)

    /** 累计持有时长（纳秒）。 */
    private val slotTotalHoldNs = LongArray(SLOT_COUNT)

    /** 最长一次排队等待（纳秒）。 */
    private val slotMaxWaitNs = LongArray(SLOT_COUNT)

    /** 累计排队等待（纳秒）。 */
    private val slotTotalWaitNs = LongArray(SLOT_COUNT)

    /** 最长持有那一次的 tag（身份读数，见类 KDoc 挂载点表的 tag 列）。 */
    private val slotMaxHoldTag = LongArray(SLOT_COUNT)

    /** 最长等待那一次的 tag。 */
    private val slotMaxWaitTag = LongArray(SLOT_COUNT)

    /**
     * 并进合并桶的**样本数**（不是线程名个数：同一条线程多次落桶各计一条）。
     *
     * 桶只存一个线程名，存不下「进来过几个名字」这张表 ⇒ 读数诚实的口径只能是样本数；
     * 它回答的是「这一槽里有多少条读数丢了线程名粒度」，不假装知道是谁。
     */
    private val slotMergedSamples = IntArray(SLOT_COUNT)

    /** 越界 id 的样本数（禁抛兜底：宁可不分层也不丢账，见 [clip] 与 [slotFor]）。 */
    @Volatile
    private var outOfRangeSamples: Long = 0L

    /** 本类自己的临界区锁（**叶锁**，见类 KDoc 锁序表末段）。 */
    private val lock = Any()

    /**
     * 挂载点唯一入口：**未挂载 = null**，调用方据此整块跳过（不取时钟、不进 [lock]、零分配）。
     *
     * `inline` 的意义就在这条守卫 —— 它是「一次 volatile 布尔读 + 一个分支」，
     * 关时 JIT 可把整段探针当死代码摘掉（口径同 `ProcessRuntime.statementProbe`
     * 的守卫结构，`core/proc/ProcessRuntime.kt:1126-1129`）。
     */
    // inline 是刻意的（不是「顺手」）：守卫要能在关时被 JIT 当死代码摘掉，
    // 编译器那条「inlining 收益不显著」的提示正是本方法想要的形状 —— 它没有函数参数，
    // 收益体现在**调用方**（八处挂载点）而不是本函数自身。
    @Suppress("NOTHING_TO_INLINE")
    inline fun handleOrNull(): LockWatch? = if (enabled) this else null

    /**
     * 挂载（壳层调试档用；core 侧不自行挂载）。
     *
     * 现成调用点只有两处：桌面 `-Plockwatch=true`（走 [PROPERTY_ENABLED] 的初值，不经这里）与
     * DevTools 的 `lockwatch on`（`app/.../DevToolsHost.kt` 的 `LockWatchCommand`，OBS2 新增）。
     * 它是**全进程一档**：打开后所有挂载点同时开始记 —— 与 `StmtInternAudit` 同风险面，
     * 所以缺省关、且只在调试通道里给玩家/作者开。
     */
    fun attach() {
        enabled = true
    }

    /** 摘除（此后挂载点回到零行为；表内读数保留到下一次 [reset]）。 */
    fun detach() {
        enabled = false
    }

    /**
     * 记一条「排队 + 持有」样本（三个时标都由挂载点在锁的两侧取，本方法只算差）。
     *
     * @param enterNs 尝试取锁前
     * @param acquiredNs 取得锁后第一条语句
     * @param exitNs 释放锁后
     * @param tag 身份读数（像素数 / 条数等；无则 0）
     *
     * 单调时钟的差值理论上非负；出现负值（时钟异常/时标传错）按 0 折，**不丢样本**。
     * 本方法**禁抛**（`submit` 把它放在 `finally` 里，抛出会吞掉返回值）。
     */
    fun noteSegment(lockId: Int, enterNs: Long, acquiredNs: Long, exitNs: Long, tag: Long = 0L) {
        try {
            val waitNs = clip(acquiredNs - enterNs)
            val holdNs = clip(exitNs - acquiredNs)
            synchronized(lock) {
                val slot = slotFor(lockId, Thread.currentThread().name)
                if (slot < 0) {
                    outOfRangeSamples++
                    return
                }
                slotSamples[slot]++
                val w = slotMaxWaitNs[slot]
                if (waitNs > w) {
                    slotMaxWaitNs[slot] = waitNs
                    slotMaxWaitTag[slot] = tag
                }
                slotTotalWaitNs[slot] += waitNs
                val h = slotMaxHoldNs[slot]
                if (holdNs > h) {
                    slotMaxHoldNs[slot] = holdNs
                    slotMaxHoldTag[slot] = tag
                }
                slotTotalHoldNs[slot] += holdNs
            }
        } catch (_: Throwable) {
            // 观察面绝不反噬被观察的代码（StatementProbe 的吞口口径，
            // core/proc/StatementProbe.kt:17-19）：丢这一条读数，不改变任何语义。
        }
    }

    /**
     * 记一条「纯等待」样本（条件变量：等待本身就是读数，没有对应的持有时长）。
     * 用法与禁抛要求同 [noteSegment]。
     */
    fun noteWait(lockId: Int, fromNs: Long, toNs: Long, tag: Long = 0L) {
        try {
            val waitNs = clip(toNs - fromNs)
            synchronized(lock) {
                val slot = slotFor(lockId, Thread.currentThread().name)
                if (slot < 0) {
                    outOfRangeSamples++
                    return
                }
                slotSamples[slot]++
                if (waitNs > slotMaxWaitNs[slot]) {
                    slotMaxWaitNs[slot] = waitNs
                    slotMaxWaitTag[slot] = tag
                }
                slotTotalWaitNs[slot] += waitNs
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 快照（**读侧唯一取数口**）：临界区内只读那几张定长数组、造出只读快照，
     * 字符串拼装留给调用方在锁外做。
     *
     * 返回按 id、槽序排列的**非空**槽读数；空槽（样本数 0）不进列表。
     * 这是提交 `49490501` 的教训落成的形状：读侧不许裸迭代写侧的采样表。
     */
    fun snapshot(): List<LockSample> = try {
        synchronized(lock) { snapshotLocked() }
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * 报告行（多行，一行一个「挂载点 + 线程」）；供崩溃报告/DevTools 通道直接贴。
     *
     * **禁抛**：内部异常一律降级成一行 `[lockwatch] 读数不可用`
     * （纪律照 `core/diag/PageFunctionTrail.kt` 的读侧降级）。时长给 ns 原值 + 整除 ms
     * 两列，不引入浮点与 locale（`core/code/LabelKeyLocaleTest.kt` 钉过源码面默认 locale 的坑）。
     */
    fun readout(): String = try {
        val samples = synchronized(lock) { snapshotLocked() }
        val lost = outOfRangeSamples
        val sb = StringBuilder(64 + samples.size * 128)
        sb.append("[lockwatch] 挂载=").append(if (enabled) "on" else "off")
            .append(" 槽=").append(SLOT_COUNT).append("(定长)").append('\n')
        if (samples.isEmpty()) sb.append("  （无样本）\n")
        for (s in samples) {
            sb.append(s.lockName).append(' ').append(s.threadLabel)
                .append(" 样本=").append(s.samples)
                .append(" 最长持有_ns=").append(s.maxHoldNs).append('(').append(s.maxHoldNs / MS_NS).append("ms)")
                .append(" 累计持有_ns=").append(s.totalHoldNs)
                .append(" 最长等待_ns=").append(s.maxWaitNs).append('(').append(s.maxWaitNs / MS_NS).append("ms)")
                .append(" 累计等待_ns=").append(s.totalWaitNs)
                .append(" 持有峰值tag=").append(s.maxHoldTag)
                .append(" 等待峰值tag=").append(s.maxWaitTag)
            if (s.mergedSamples > 0) sb.append(" 并入本桶样本=").append(s.mergedSamples)
            sb.append('\n')
        }
        if (lost > 0L) sb.append("  越界 id 样本=").append(lost).append('\n')
        sb.toString().trimEnd()
    } catch (_: Throwable) {
        "[lockwatch] 读数不可用"
    }

    /** 清空全部读数（**装置/单测专用**）：不动 [enabled]。 */
    fun reset() {
        synchronized(lock) {
            slotThread.fill(null)
            slotSamples.fill(0L)
            slotMaxHoldNs.fill(0L)
            slotTotalHoldNs.fill(0L)
            slotMaxWaitNs.fill(0L)
            slotTotalWaitNs.fill(0L)
            slotMaxHoldTag.fill(0L)
            slotMaxWaitTag.fill(0L)
            slotMergedSamples.fill(0)
            outOfRangeSamples = 0L
        }
    }

    /**
     * 当前样本总数（跨全部槽；用于「探针真的在记」这种判据）。
     *
     * 与 [snapshot] 同一条读侧纪律：读数走 [lock]，不裸迭代写侧的表（提交 `49490501` 的教训）。
     * 本方法只把定长数组求和，不构造对象，读侧线程之外零分配。
     */
    fun totalSamples(): Long = try {
        synchronized(lock) {
            var n = 0L
            for (v in slotSamples) n += v
            n + outOfRangeSamples
        }
    } catch (_: Throwable) {
        -1L
    }

    /**
     * 挂载点是否已登记样本（id 越界恒 false）。加锁口径同 [totalSamples]。
     *
     * 越界样本不进这一判据（它们没有挂载点身份），要读它们请用 [totalSamples]。
     */
    fun hasSamples(lockId: Int): Boolean {
        if (lockId < 0 || lockId >= LOCK_COUNT) return false
        return try {
            synchronized(lock) {
                for (i in 0 until THREAD_SLOTS) {
                    if (slotSamples[lockId * THREAD_SLOTS + i] > 0L) return true
                }
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 挂载点显示名（越界给 `unknown#<id>`，绝不抛）。 */
    fun nameOf(lockId: Int): String =
        if (lockId in 0 until LOCK_COUNT) LOCK_NAMES[lockId] else "unknown#$lockId"

    private fun clip(ns: Long): Long = if (ns < 0L) 0L else ns

    /**
     * 找槽：同挂载点内按线程名线性探（槽数 ≤ [THREAD_SLOTS]，常数级）；
     * 具名槽满则落合并桶（[MERGED_SLOT]，粒度降级、总量不丢）。
     *
     * 合并桶里存的名字只是「第一个落进来的名字」，不代表这一槽的归属；所以桶行的
     * `thread=` 一律写 `*(N 条样本并入)`（[mergedSamples] = 并进本桶的样本数）——
     * 读数不许假装知道是谁。
     * @return 槽号；id 越界返回 -1（调用方计进 [outOfRangeSamples]）。
     */
    private fun slotFor(lockId: Int, threadName: String?): Int {
        if (lockId < 0 || lockId >= LOCK_COUNT) return -1
        val base = lockId * THREAD_SLOTS
        val name = threadName ?: "<unknown-thread>"
        for (i in 0 until MERGED_SLOT) {
            val slot = base + i
            val held = slotThread[slot]
            if (held == null) {
                slotThread[slot] = name
                return slot
            }
            if (held == name) return slot
        }
        val merged = base + MERGED_SLOT
        if (slotThread[merged] == null) slotThread[merged] = name
        slotMergedSamples[merged]++
        return merged
    }

    /** 临界区内只复制数组（返回的是新建的只读快照，拼装留给锁外）。 */
    private fun snapshotLocked(): List<LockSample> {
        val out = ArrayList<LockSample>(SLOT_COUNT)
        for (id in 0 until LOCK_COUNT) {
            val name = LOCK_NAMES[id]
            for (i in 0 until THREAD_SLOTS) {
                val slot = id * THREAD_SLOTS + i
                val n = slotSamples[slot]
                if (n <= 0L) continue
                val thread = slotThread[slot] ?: "<unknown-thread>"
                out += LockSample(
                    lockId = id,
                    lockName = name,
                    threadName = thread,
                    mergedSamples = if (i == MERGED_SLOT) slotMergedSamples[slot] else 0,
                    samples = n,
                    maxHoldNs = slotMaxHoldNs[slot],
                    totalHoldNs = slotTotalHoldNs[slot],
                    maxWaitNs = slotMaxWaitNs[slot],
                    totalWaitNs = slotTotalWaitNs[slot],
                    maxHoldTag = slotMaxHoldTag[slot],
                    maxWaitTag = slotMaxWaitTag[slot],
                )
            }
        }
        return out
    }

    /** ns → ms 的整除基数（读数面唯一换算，不用浮点、不读 locale）。 */
    private const val MS_NS = 1_000_000L

    /**
     * 一行聚合读数（挂载点 + 线程）。
     *
     * 合并桶行（[mergedSamples] > 0）的 [threadLabel] 带 `*(N 条样本并入)` 标注
     * —— 那一槽是若干条没登记下线程名的样本之和。
     */
    class LockSample internal constructor(
        val lockId: Int,
        val lockName: String,
        val threadName: String,
        val mergedSamples: Int,
        val samples: Long,
        val maxHoldNs: Long,
        val totalHoldNs: Long,
        val maxWaitNs: Long,
        val totalWaitNs: Long,
        val maxHoldTag: Long,
        val maxWaitTag: Long,
    ) {
        /** 读数行的线程列（合并桶加降级标注）。 */
        val threadLabel: String
            get() = if (mergedSamples > 0) "thread=*($mergedSamples 条样本并入)" else "thread=$threadName"

        override fun toString(): String = "$lockName $threadLabel samples=$samples"
    }
}
