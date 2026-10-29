package com.eratw.port.vmdbg

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.host.NoopTimerSink
import com.eratw.port.core.host.TimerSink
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.RootedFileOps
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveFileIo
import com.eratw.port.core.save.SaveFolderIo
import com.eratw.port.core.sem.SystemOps
import java.nio.file.Files
import java.nio.file.Path

/**
 * 会话装配（CONTRACT-DBG1 路2；所有子命令共用）。
 *
 * 装配链 = `ProgramLoader.loadMapped(program.erbc)`（ProgramLoader.kt:425）+
 * `BootMetadata.read(program.meta)`（BootMetadata.kt:273）+ `GameSession(...)`
 * （GameSession.kt:135）。`GameSession` 装配后不保留 metadata（GameSession.kt:107 的
 * 「侧车生命周期」），vmdbg 因此自己持有 [Assembled.metadata]，`sweep` 拿
 * `metadata.functions` 当清扫清单。
 *
 * ## 钉死的装配参数
 *
 * - `randSeed` = [Cli.RAND_SEED]（20260226，CONTRACT-DBG1 路2 钉死；与 GameBootSmoke.kt:91
 *   的装置常量同值）——确定性对拍需要会话可复现。
 * - `maxStepsPerRun` = `--steps N`（call/trace 缺省 20,000,000、sweep 缺省 2,000,000，
 *   见 [Cli]；call/trace 与产品 GameSession.DEFAULT_MAX_STEPS_PER_RUN 同口径）。
 * - `writeRoot` 保持 null（GameSession.kt:165 的缺省）⇒ SAVETEXT / OUTPUTLOG 拒写，
 *   与 sweep 规格「SAVETEXT 拒写」一致。
 * - `frameGateEnabled = false`：headless 工具没有帧节拍的读者，控制台变更立即发布，
 *   `call` 的「控制台尾 10 行」不必等帧闸放行。只影响发布时机，不改行内容。
 * - `systemOps` 显式传 [DeferredSandboxSystemOps]（存档沙箱，见其 KDoc）——
 *   **语料只读铁律的落地点，所有子命令共用，不得省**。
 *
 * ## 参数来源
 *
 * `--erbc` / `--game` 优先；缺省读 `ERATW_ERBC_DIR` / `ERATW_GAMEDIR`（与
 * core-vm/build.gradle.kts:196-209 的 `erbcBootProfile` 旋钮同名同义）。
 */
object Assembler {

    /** 装配结果：会话 + vmdbg 自留的侧车（清扫清单）+ 沙箱目录与输入 sink 句柄。 */
    class Assembled(
        val session: GameSession,
        val program: Program,
        val metadata: BootMetadata,
        val inputSink: ImmediateInputSink,
        val sandboxDir: Path,
    )

    /**
     * 装配会话。任何文件层失败折成 [CliError]（退出码 1，不带堆栈）。
     *
     * 存档沙箱目录 = `<work>/save-sandbox-<进程毫秒>-<随机>`：每个进程一个，sweep 与
     * call 各自独立，互不串写。`--work` 目录同时承载 last-error.json（`where` 的数据源），
     * 缺省 `<系统临时目录>/eratw-vmdbg`。
     *
     * @param sinkOverride explore（CONTRACT-DBG6 E1）注入的自定义输入宿主；null（缺省）=
     *   既有行为零变化（[ImmediateInputSink]）。注入时语句输入走 override，[Assembled.inputSink]
     *   仍是缺省 sink 实例（既有消费者类型不变）——AWAIT 的墙钟兜底（[WallTimerSink]）接的
     *   就是缺省实例，explore 把同一 [WallBudget] 挂到两处（见 ExploreDriver.runEpisode）。
     */
    fun assemble(a: Args, sinkOverride: InputSink? = null): Assembled {
        val erbcRaw = a.value("--erbc")
            ?: System.getenv("ERATW_ERBC_DIR")
            ?: throw CliError("缺 --erbc <erbc.v1 目录>，且环境变量 ERATW_ERBC_DIR 未设置")
        val gameRaw = a.value("--game")
            ?: System.getenv("ERATW_GAMEDIR")
            ?: throw CliError("缺 --game <游戏根>，且环境变量 ERATW_GAMEDIR 未设置")
        val erbcDir = Path.of(erbcRaw).toAbsolutePath().normalize()
        val gameRoot = Path.of(gameRaw).toAbsolutePath().normalize()
        if (!Files.isDirectory(erbcDir)) throw CliError("--erbc 不是目录: $erbcDir")
        if (!Files.isDirectory(gameRoot)) throw CliError("--game 不是目录: $gameRoot")
        val programPath = erbcDir.resolve("program.erbc")
        val metaPath = erbcDir.resolve("program.meta")
        if (!Files.isRegularFile(programPath)) throw CliError("产物缺失: $programPath")
        if (!Files.isRegularFile(metaPath)) throw CliError("侧车缺失: $metaPath（正式产物必须带 program.meta）")

        val workDir = Path.of(a.valueOr("--work", defaultWorkDir())).toAbsolutePath().normalize()
        try {
            Files.createDirectories(workDir)
        } catch (e: Exception) {
            throw CliError("状态目录建不出来: $workDir（$e）", e)
        }
        val sandboxDir = try {
            Files.createTempDirectory(workDir, "save-sandbox-")
        } catch (e: Exception) {
            throw CliError("存档沙箱目录建不出来: $workDir（$e）", e)
        }

        val program = try {
            ProgramLoader.loadMapped(programPath)
        } catch (e: Exception) {
            throw CliError("产物装载失败: $programPath（${e.message}）", e)
        }
        val metadata = try {
            BootMetadata.read(metaPath)
        } catch (e: Exception) {
            throw CliError("侧车读取失败: $metaPath（${e.message}）", e)
        }

        // 存档沙箱接线：DeferredSandboxSystemOps 在首次存档语句执行时才建真 FileSystemOps
        // （要引用会话的 store/saveConfig，而两者都在 GameSession 构造期才存在，
        // GameSession.kt:291-294/:536），构造期只把沙箱根与游戏根交给它。
        val sink = ImmediateInputSink()
        val effectiveSink: InputSink = sinkOverride ?: sink
        var session: GameSession? = null
        val systemOps = DeferredSandboxSystemOps(gameRoot, sandboxDir) {
            requireNotNull(session) { "会话尚未装配完成（存档语句不可能在装配期被调用）" }
        }
        val steps = a.longOr("--steps", -1L)
        // 步数闸缺省按子命令分流：sweep 规格 = 2,000,000/函数；call/trace = 装置缺省 20,000,000
        //（与产品 GameSession.DEFAULT_MAX_STEPS_PER_RUN 同口径，GameSession.kt:718）。
        val defaultSteps =
            if (a.positional.firstOrNull() == "sweep") Cli.DEFAULT_SWEEP_STEPS else Cli.DEFAULT_CALL_STEPS
        val s = try {
            GameSession(
                gameRoot = gameRoot,
                program = program,
                metadata = metadata,
                maxStepsPerRun = if (steps > 0) steps else defaultSteps,
                randSeed = Cli.RAND_SEED,
                systemOps = systemOps,
                frameGateEnabled = false,
                inputSinkOverride = effectiveSink,
            )
        } catch (e: Exception) {
            throw CliError("会话装配失败: ${e.message}", e)
        }
        session = s
        // AWAIT 族的墙钟兜底：AWAIT 是让出边界（重置步数闸，StatementExecutor.kt:1022）、
        // 又不经输入 sink —— 不挂这里，`AWAIT` 空转循环就是第三类无界点。call/trace 不装
        // WallBudget（sink.budget 为 null ⇒ 全部空操作），行为与缺省 NoopTimerSink 等价。
        s.runtime.execContext.timer = WallTimerSink(sink)
        return Assembled(s, program, metadata, sink, sandboxDir)
    }

    /** `--work` 缺省：系统临时目录下的固定名（跨进程持久，`where` 才读得到 `call` 的现场）。 */
    private fun defaultWorkDir(): String =
        Path.of(System.getProperty("java.io.tmpdir"), "eratw-vmdbg").toString()
}

/**
 * 存档沙箱的 [SaveFileIo]（CONTRACT-DBG1 路2 sweep 规格）：
 *
 * - **写/删全部落沙箱根**（`<work>/save-sandbox-*`）：SAVEDATA / SAVEGLOBAL / SAVECHARA /
 *   旧档迁移产出的每个字节都进临时目录，游戏根一个字节都不动 —— 语料只读铁律的落地点；
 * - **读与存在性照旧走游戏根**（`exists`/`read`/`isReadOnly` 委托给根 = 游戏根的
 *   [RootedSaveFileIo]）：随游戏发行的全局档等只读数据照常可读。
 *
 * `SaveFolderIo`（旧档迁移的目录面，SaveFolderMigration.kt:16）整体指向**沙箱**：
 * 迁移会 move 文件，绝不允许落在游戏根。
 *
 * 与 GameSession.kt:536 的生产构造（`RootedSaveFileIo(gameRoot, rooted.fileOps)`）同款口径，
 * 仅把「读写同一根」拆成「读根 = 游戏根 / 写根 = 沙箱」。
 */
class SandboxedSaveFileIo(
    gameRoot: Path,
    sandboxRoot: Path,
    readOps: FileOps? = null,
) : SaveFileIo, SaveFolderIo {

    private val readSide = RootedSaveFileIo(gameRoot, readOps)
    private val writeSide = RootedSaveFileIo(sandboxRoot)

    override fun exists(path: String): Boolean = readSide.exists(path)

    override fun read(path: String): ByteArray? = readSide.read(path)

    override fun write(path: String, bytes: ByteArray): Boolean = writeSide.write(path, bytes)

    override fun delete(path: String): Boolean = writeSide.delete(path)

    override fun isReadOnly(path: String): Boolean = readSide.isReadOnly(path)

    override fun saveFolderExists(): Boolean = writeSide.saveFolderExists()

    override fun createSaveFolder(): Boolean = writeSide.createSaveFolder()

    override fun rootSaveFileNames(): List<String> = writeSide.rootSaveFileNames()

    override fun moveToFile(fromRel: String, toRel: String): Boolean = writeSide.moveToFile(fromRel, toRel)
}

/**
 * 存档沙箱的 [SystemOps]：首次调用时才用会话的真实 `store` / `saveConfig` 建
 * [FileSystemOps]（GameSession.kt:536 同款构造，仅 [SandboxedSaveFileIo] 换根），
 * 全部方法逐一直转发。
 *
 * 为什么延迟：`FileSystemOps` 构造要 `VariableStore` 与 `SaveConfig`，两者是
 * `GameSession` 构造期的产物（GameSession.kt:291-294/:342/:477），而 `systemOps`
 * 又必须**在**构造期传进 `GameSession` —— 唯一的次序解是「先装配、首次使用时再建」。
 * 首次使用必然发生在脚本执行期（存档语句被执行时），此时会话早已装配完。
 * `lazy` 线程安全（默认 synchronized），VM 单线程执行不会竞争。
 */
class DeferredSandboxSystemOps(
    gameRoot: Path,
    sandboxDir: Path,
    sessionRef: () -> GameSession,
) : SystemOps {

    private val io = SandboxedSaveFileIo(gameRoot, sandboxDir, RootedFileOps(gameRoot))

    private val delegate: FileSystemOps by lazy {
        val s = sessionRef()
        FileSystemOps(s.store, io, s.saveConfig)
    }

    override fun saveData(no: Int, text: String): Boolean = delegate.saveData(no, text)

    override fun loadData(no: Int) = delegate.loadData(no)

    override fun delData(no: Int) = delegate.delData(no)

    override fun saveGame() = delegate.saveGame()

    override fun loadGame() = delegate.loadGame()

    override fun saveGlobal() = delegate.saveGlobal()

    override fun loadGlobal(): Boolean = delegate.loadGlobal()

    override fun saveChara(nos: List<Long>, filename: String, text: String) =
        delegate.saveChara(nos, filename, text)

    override fun loadChara(filename: String) = delegate.loadChara(filename)

    override fun updateCheck(): Long = delegate.updateCheck()

    override fun callTrain(no: Long) = delegate.callTrain(no)

    override fun stopCallTrain() = delegate.stopCallTrain()

    override fun doTrain(no: Long) = delegate.doTrain(no)

    override fun upcheck() = delegate.upcheck()

    override fun cupcheck(target: Long) = delegate.cupcheck(target)
}

/**
 * sweep 逐函数挂起检测的墙钟预算：游戏函数可能在「立即应答 sink」下永远拿得到输入，
 * 若循环体每圈都带输入等待，[com.eratw.port.core.code.VmConfig.maxStepsPerRun] 的
 * 段落计数会在每个让出点清零（Vm.kt:8-15 的口径）而永不触发 —— 墙钟兜底。
 *
 * 检查点挂在输入 sink 的两个等待入口（`waitInput`/`readAnyKey`）：预算超限时抛
 * [WallLimitExceeded]，沿语句层异常路径原样上抛（TRYC 家族只捕 CodeEE，不会吞它），
 * sweep/call 捕获后折成 `WALL_LIMIT` 状态。纯 CPU 死循环不经过输入口，由步数闸兜住。
 */
/**
 * AWAIT 族的墙钟兜底：`AWAIT` 语句走 [TimerSink.delay]（StatementExecutor AWAIT 分支），
 * 是让出边界（步数闸在此重算）又不经输入 sink，[WallBudget] 只挂输入侧时这里是漏风面。
 * 委托 [sink.budget] 现读——sweep 逐函数换装预算，本类跟着走到当前函数的预算上；
 * budget 为 null（call/trace）时与缺省 [NoopTimerSink] 等价（纯透传）。
 */
private class WallTimerSink(private val sink: ImmediateInputSink) : TimerSink {
    override fun delay(millis: Long) {
        sink.budget?.check()
    }
}

class WallBudget(private val wallMs: Long) {

    private val startNs = System.nanoTime()

    /** 期间应答的输入次数（诊断用：`WALL_LIMIT` 的 message 里带上）。 */
    var inputRequests: Long = 0
        private set

    /** 超预算抛 [WallLimitExceeded]；`wallMs <= 0` = 关闭墙钟检查。 */
    fun check() {
        inputRequests++
        if (wallMs <= 0) return
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        if (elapsedMs > wallMs) throw WallLimitExceeded(wallMs, elapsedMs, inputRequests)
    }
}

/** 墙钟预算超限（非 CodeEE：不能被脚本 TRYC 捕获吞掉）。 */
class WallLimitExceeded(val wallMs: Long, val elapsedMs: Long, val requests: Long) :
    RuntimeException("vmdbg 函数挂起保护：墙钟 ${elapsedMs}ms 超过预算 ${wallMs}ms（期间应答 $requests 次输入）")

/**
 * 立即应答输入 sink（CONTRACT-DBG1 路2；口径 = GameBootSmoke.kt:98 的 ScriptedInputSink
 * 去掉顺序脚本）：INT 请求有默认取默认、无默认取 0；STR 请求取默认或空串；任意键立即放行。
 * 不用阻塞队列 —— sweep/call 的输入永远不能等人。
 *
 * [budget] 由 sweep 逐函数换装（见 [WallBudget]）；call/trace 不装（进程生命周期即预算）。
 */
class ImmediateInputSink : InputSink {

    /** 当前墙钟预算；null = 不检查（call/trace 单函数场景）。 */
    @Volatile
    var budget: WallBudget? = null

    override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) {
        budget?.check()
    }

    override fun waitInput(request: InputRequest): InputOutcome {
        budget?.check()
        return when (request.type) {
            InputRequest.InputType.INT ->
                if (request.hasDef) InputOutcome(intValue = request.defInt) else InputOutcome(intValue = 0L)
            InputRequest.InputType.STR ->
                InputOutcome(strValue = if (request.hasDef) request.defStr else "")
        }
    }
}

/**
 * 单次 run 的已执行步数读取：`ProcessRuntime.vm` 是 private（ProcessRuntime.kt:231），
 * 步数计数 `executedSteps` 在 VM 上（Vm.kt:316，run 开头清零 Vm.kt:387）——
 * 反射读，口径与 GameBootSmoke.kt:85/:1111 相同（该装置注明「单次 run 的已执行步数
 * 未公开，靠反射读 ProcessRuntime.vm.executedSteps」）。取不到（字段改名/JPM 装饰）返回
 * null，输出写 -1，不阻塞主流程。
 */
object StepReader {

    private val vmField = runCatching {
        ProcessRuntime::class.java.getDeclaredField("vm").apply { isAccessible = true }
    }.getOrNull()

    /** 最近一次 [ProcessRuntime] 的单 run 步数；null = 反射不可用。 */
    fun executedSteps(rt: ProcessRuntime): Long? {
        val f = vmField ?: return null
        val vm = runCatching { f.get(rt) as? VirtualMachine }.getOrNull() ?: return null
        return vm.executedSteps
    }
}
