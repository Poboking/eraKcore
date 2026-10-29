package com.eratw.port.uipreview

import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.TimerSink
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.RootedFileOps
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveFileIo
import com.eratw.port.core.save.SaveFolderIo
import com.eratw.port.core.sem.SystemOps
import java.nio.file.Files
import java.nio.file.Path

/**
 * 会话装配（CONTRACT-UP1 §4.2）：`--erbc` / `--game` 参数优先，缺省读
 * `ERATW_ERBC_DIR` / `ERATW_GAMEDIR`（与 core-vm/build.gradle.kts 的 `erbcBootProfile`
 * 旋钮同名同义，不发明第三套名字）。
 *
 * 装配链 = `ProgramLoader.loadMapped(program.erbc)` + `BootMetadata.read(program.meta)` +
 * `GameSession(...)`（GameSession.kt:136）。与 [com.eratw.port.vmdbg.Assembler] 的差别只有三处
 * （该类是语义来源，本路不依赖 vmdbg 模块、按同款口径自写）：
 * - **inputSink 不换**：交互式服务直接用 `GameSession.inputSink` 的
 *   [com.eratw.port.core.proc.QueueInputSink]（HTTP 线程只负责 `submit` 入队，VM 线程阻塞消费；
 *   vmdbg 传 `inputSinkOverride` 的立即应答 sink 是清扫装置专用，语义相反）；
 * - `frameGateEnabled` 用缺省 **true**：本工具的泵线程就是帧读者，与 app 生产装配同口径
 *   （vmdbg 置 false 是因为 headless 没有帧节拍读者）；
 * - **墙钟防卡死**装在 [GuardTimerSink]（见其 KDoc）。
 *
 * ## 钉死的装配参数
 *
 * - `randSeed` = [Cli.RAND_SEED]（20260226，与 vmdbg `Cli.RAND_SEED` / GameBootSmoke 装置常量
 *   同值）——画面可复现需要会话确定性。
 * - `maxStepsPerRun` = `--steps N`（缺省 = 产品 GameSession.DEFAULT_MAX_STEPS_PER_RUN
 *   20,000,000，GameSession.kt:718 / :119-120 —— 与 app 生产同口径，避免重载模组
 *   INIT_STARTINGLV 被低闸误杀）。
 * - `writeRoot` 保持 null（GameSession.kt:178 的缺省）⇒ SAVETEXT / OUTPUTLOG 拒写。
 * - `systemOps` 显式传 [DeferredSandboxSystemOps]（存档沙箱，见其 KDoc）——
 *   **语料只读铁律的落地点，不得省**。
 */
object Assembler {

    /** 装配结果：会话 + 沙箱目录句柄。 */
    class Assembled(
        val session: GameSession,
        val sandboxDir: Path,
    )

    /**
     * 装配会话。任何文件层失败折成 [CliError]（退出码 1，不带堆栈）。
     *
     * 存档沙箱目录 = `<work>/save-sandbox-<进程毫秒>-<随机>`；`--work` 缺省
     * `<系统临时目录>/eratw-uipreview`。
     */
    fun assemble(a: Args): Assembled {
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

        // 存档沙箱接线（与 vmdbg Assembler.kt:108-115 同构）：DeferredSandboxSystemOps 在首次
        // 存档语句执行时才建真 FileSystemOps（要引用会话的 store/saveConfig，两者都在
        // GameSession 构造期才存在），构造期只把沙箱根与游戏根交给它。
        var session: GameSession? = null
        val systemOps = DeferredSandboxSystemOps(gameRoot, sandboxDir) {
            requireNotNull(session) { "会话尚未装配完成（存档语句不可能在装配期被调用）" }
        }
        val s = try {
            GameSession(
                gameRoot = gameRoot,
                program = program,
                metadata = metadata,
                consoleWidth = a.intOr("--columns", Cli.DEFAULT_COLUMNS),
                maxStepsPerRun = a.longOr("--steps", Cli.DEFAULT_STEPS),
                randSeed = Cli.RAND_SEED,
                systemOps = systemOps,
            )
        } catch (e: Exception) {
            throw CliError("会话装配失败: ${e.message}", e)
        }
        session = s
        if (System.getenv("ERATW_UIPV_TRACE") == "1") FlowTrace.install(s)
        return Assembled(s, sandboxDir)
    }

    /** `--work` 缺省：系统临时目录下的固定名。 */
    private fun defaultWorkDir(): String =
        Path.of(System.getProperty("java.io.tmpdir"), "eratw-uipreview").toString()
}

/**
 * 存档沙箱的 [SaveFileIo]（与 vmdbg `SandboxedSaveFileIo` 同款口径，出处见该类 KDoc；
 * 语义来源 `tools/vmdbg/Assembler.kt:162-188`）：
 *
 * - **写/删全部落沙箱根**（`<work>/save-sandbox-*`）：SAVEDATA / SAVEGLOBAL / SAVECHARA /
 *   旧档迁移产出的每个字节都进临时目录，游戏根一个字节都不动 —— 语料只读铁律的落地点；
 * - **读与存在性照旧走游戏根**（`exists`/`read`/`isReadOnly` 委托给根 = 游戏根的
 *   [RootedSaveFileIo]）。
 *
 * `SaveFolderIo`（旧档迁移的目录面）整体指向**沙箱**：迁移会 move 文件，绝不允许落在游戏根。
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
 * 全部方法逐一直转发。与 vmdbg `DeferredSandboxSystemOps`（tools/vmdbg/Assembler.kt:201-244）
 * 同构；`lazy` 线程安全（默认 synchronized），VM 单线程执行不会竞争。
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
 * 墙钟防卡死的预算（与 vmdbg `WallBudget` 同思路，检查点形状不同）：
 * 口径 = **单次 `resumeSystem()` 调用内**的墙钟上限。VM 线程每从
 * `GameSession.resumeSystem()` 返回一次就调 [progress] 重置起点（回到输入等待是期望态，
 * 不该算挂死）；`AWAIT` 族是让出边界（步数闸在此重算）又不经输入 sink
 * —— 挂在 [GuardTimerSink] 的 [TimerSink.delay] 上补这个缺口（vmdbg
 * `Assembler.kt:136-139` 实锤的第三类无界点）。
 *
 * 超限抛 [WallLimitExceeded]（非 CodeEE：不能被脚本 TRYC 捕获吞掉），VM 循环折成失败终局。
 * `wallMs <= 0` = 关闭检查。纯 CPU 死循环不经过检查点，由步数闸（`--steps`）兜住。
 */
class WallGuard(private val wallMs: Long) {

    @Volatile
    private var lastProgressNs = System.nanoTime()

    /** 记一次进度（VM 线程在每次 `resumeSystem` 返回后调）。 */
    fun progress() {
        lastProgressNs = System.nanoTime()
    }

    /** 超预算抛 [WallLimitExceeded]；`wallMs <= 0` = 关闭。 */
    fun check() {
        if (wallMs <= 0) return
        val elapsedMs = (System.nanoTime() - lastProgressNs) / 1_000_000L
        if (elapsedMs > wallMs) throw WallLimitExceeded(wallMs, elapsedMs)
    }
}

/** 墙钟预算超限（非 CodeEE：不能被脚本 TRYC 捕获吞掉）。 */
class WallLimitExceeded(val wallMs: Long, val elapsedMs: Long) :
    RuntimeException("uipreview 函数挂起保护：单次推进墙钟 ${elapsedMs}ms 超过预算 ${wallMs}ms")

/**
 * [TimerSink] 实现：`AWAIT` 语句走 `TimerSink.delay`（StatementExecutor 的 AWAIT 分支），
 * 是让出边界又不经输入 sink —— 预算检查挂在这里（vmdbg `WallTimerSink` 同款缺口）。
 * 装配点 = `session.runtime.execContext.timer`（`ExecContext.kt:157` 的 var 注入点）。
 */
class GuardTimerSink(private val guard: WallGuard) : TimerSink {
    override fun delay(millis: Long) {
        guard.check()
    }
}
