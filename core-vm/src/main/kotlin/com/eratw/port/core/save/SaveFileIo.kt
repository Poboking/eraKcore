package com.eratw.port.core.save

import com.eratw.port.core.host.FileOps
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 存档层的二进制文件能力（`SAVEGLOBAL`/`LOADGLOBAL`/`SAVEDATA`/`LOADDATA`/`DELDATA`/角色档）。
 *
 * 为什么不是 [FileOps] 本身：`FileOps` 只声明了文本读写（`readText`/`writeText`），
 * 而存档是二进制容器（魔数 + GZip，spec: EraBinaryDataWriter.cs:35-51）。这里把
 * 「存在 / 读字节 / 写字节 / 删除 / 只读」收成最小的一个接口，core 层只依赖它，
 * 路径映射由宿主实现负责（与 [FileOps] 同一纪律：core 不假定工作目录）。
 *
 * 实现选择：
 * - [RootedSaveFileIo]：生产用，注入 `<gameRoot>`，NIO 落盘并拒绝越界路径；
 * - [FileOpsSaveIo]：宿主已有 [FileOps] 且愿意补二进制能力时用（把 `readFileBytes`
 *   一类函数传进来）；缺口在报告里列给组长。
 */
interface SaveFileIo {
    fun exists(path: String): Boolean

    /** 读全部字节；不存在 / 读失败返回 null。 */
    fun read(path: String): ByteArray?

    /**
     * 覆盖写；父目录不存在时由实现创建。返回 false = 失败（实现把 IO 一类 Exception 折成 false）。
     *
     * `Error`（堆贴顶的 `OutOfMemoryError`）**不折成 false**，原样上抛 —— 没有内存是写不进去的
     * 根本原因，静默 false 会让调用方的回收/重试链（`FileSystemOps.saveData`）永远接不到。
     */
    fun write(path: String, bytes: ByteArray): Boolean

    /**
     * 流式写（OOM-B 波，2026-11-08）：字节由 [producer] 现产，实现负责落盘。
     *
     * 默认实现先把字节收进内存再走 [write] —— 既有实现者（测试假体 / [FileOpsSaveIo]）
     * 不必改，但那样等于没省内存；只有生产实现 [RootedSaveFileIo] 真流式（临时文件
     * 边产边写），省掉「整份容器 ByteArray」这一份。
     *
     * 契约与 [write] 同口径：返回 false = 失败；`Error`（OOM）原样上抛。另一条特别约定：
     * [producer] **自身**抛 Exception（序列化失败）⇒ 返回 false，且**不回落原地截断写** ——
     * 原地写会先把旧档截掉，为一个注定失败的序列化把好档搭进去。
     */
    fun write(path: String, producer: (OutputStream) -> Unit): Boolean {
        val buf = java.io.ByteArrayOutputStream()
        try {
            producer(buf)
        } catch (e: Throwable) {
            if (e is Error) throw e
            return false
        }
        return write(path, buf.toByteArray())
    }

    /** 删除；文件本来不存在也算成功。 */
    fun delete(path: String): Boolean

    /**
     * 是否只读（`DELDATA` 的前置判定）。spec: VariableEvaluator.cs:2710-2721 ——
     * 原版先查 `FileAttributes.ReadOnly`，命中抛 `DelReadOnlyFile`。默认 false =
     * 宿主不支持属性查询（删除失败会由 [delete] 的返回值暴露）。
     *
     * **探测失败按 false 处理（2026-10-22 审查钉死的契约）**：属性查询抛异常（权限/
     * IO 问题）时「查不到 ≠ 只读」——返回 false 放行到 [delete]，删除真失败另有兜底
     * （[FileSystemOps.delData] 把删除失败收敛到同一句 DelReadOnlyFile 文案）。这与
     * 原版一致：`FileAttributes` 读取失败时原版也直接走 `File.Delete` 路径。
     * 调用方**不得**把本方法当 canWrite 用做写前置 —— 它不是「可写判定」的反面，
     * 拿它拦 `SAVEDATA` 会把「探测失败」误判成「拒绝保存」。
     */
    fun isReadOnly(path: String): Boolean = false
}

/**
 * 以注入根目录为基准的生产实现（原版 `Program.ExeDir` 口径）。
 *
 * 路径规则与 `RootedFileOps` 一致：相对路径 `base.resolve`，绝对路径必须仍在 [base] 内，
 * `\` 与 `/` 都接受；越界一律当「不存在 / 写失败」，不抛异常。
 *
 * 同时实现 [SaveFolderIo]：本类是**唯一**持有真实根路径的实现，所以 `UseSaveFolder`
 * 旧档迁移（SAVE-01，[SaveFolderMigration]）的目录判定与 `File.Move` 落在这里，
 * 口径与 `RootedFileOps` 的应用可写根同源（同一个 `resolve`，越界拒绝）。
 *
 * @param ops 存在性判定优先委托给它（生产 = `RootedFileOps`），null 时用 `Files.exists`。
 */
class RootedSaveFileIo(
    root: Path,
    private val ops: FileOps? = null,
) : SaveFileIo, SaveFolderIo {

    private val base: Path = root.toAbsolutePath().normalize()

    /** 注入后的绝对根（原版 `Program.ExeDir` 的移植侧对应物）；迁移用例用它核对「没落到根外」。 */
    val rootedPath: Path get() = base

    override fun exists(path: String): Boolean =
        ops?.exists(path) ?: resolve(path)?.let { Files.exists(it) } ?: false

    override fun read(path: String): ByteArray? {
        val f = resolve(path) ?: return null
        if (!Files.isRegularFile(f)) return null
        return runCatching { Files.readAllBytes(f) }.getOrNull()
    }

    /**
     * 覆盖写：**快路**「同目录临时文件 → 刷盘 → 换名」的原子替换，**退路**原地截断覆盖。
     *
     * **快路是有意偏离原版实现**：原版 `SAVEDATA` / `SAVEGLOBAL` / `SAVECHARA` 都用
     * `new FileStream(path, FileMode.Create, FileAccess.Write)` 原地截断覆盖
     * （VariableEvaluator.cs:2258、:2480、:2653），PC 上不存在跨崩溃的事务保证。
     * 安卓上进程被 LMK 在任意点杀掉的概率远高于 PC，原地覆盖写到一半就留下截断档，
     * 读档会落成 LoadCorruptedData 且不可回滚；这里改成「先写同目录临时文件、
     * `force(true)` 刷盘、再换名到目标」，被杀时目标要么是旧档、要么是新档，
     * 不会出现半截文件。
     *
     * **退路是 2026-11-01 补的**（玩家「不能覆盖旧存档」）：快路的峰值占用是
     * 旧档 + 临时文件 **同时存在**，覆盖一份 N 字节的档要 2N 空间；原版只要 N。
     * 存储吃紧的设备上快路会抛 IOException，旧实现直接折成 false ⇒ 屏上
     * `SAVEDATA命令在存档时发生了意料之外的错误`，而存到**空槽**照样成功（只有一份占用）——
     * 正是玩家报的两条现象。故快路失败时退到原地截断，把「存不上」换成「这一次没有
     * 崩溃安全保证」；快路成功时行为与本条补丁之前逐字相同。
     *
     * 对外可观测语义：返回 false = 目标只读（显式拒绝）或两条路都以 Exception 失败；成功时读回字节 == [bytes]；
     * `Error`（OOM）从这条路**上抛**，由调用方的求援/重试链接管
     * （见 [writeByAtomicReplace] 的 KDoc）；越界路径仍由 [resolve]
     * 拒绝并返回 false；父目录不存在会先建出来。
     * 与原地覆盖仅有的两处差异都不影响读档：换名后目标文件的属主/权限位继承临时文件
     * （同 uid 可读写）；换名不 fsync 父目录，掉电场景下新档可能尚未持久 —— 但进程被杀
     * 这一场景不再截断。
     */
    override fun write(path: String, bytes: ByteArray): Boolean {
        val f = resolve(path) ?: return false
        // 只读档显式拒绝：POSIX（Android/Linux）上「临时文件 + rename」可以覆盖只读目标，
        // 光靠 OS 的 AccessDenied 拦不住；这里与 DELDATA 的只读口径同源，跨平台一致。
        if (isReadOnly(path)) return false
        val parent = f.parent ?: return false
        // 快路：同目录临时文件 → 原子换名（见上一条 KDoc 的崩溃安全理由）。
        if (writeByAtomicReplace(parent, f, bytes)) return true
        // 退路：原地截断覆盖，与原版 `FileMode.Create` 逐字同语义。
        //
        // 为什么必须有这条退路（2026-11-01 玩家「不能覆盖旧存档」）：快路的峰值占用是
        // **旧档 + 临时文件同时存在**，也就是覆盖一份 N 字节的档要 2N 的空间；原版原地
        // 截断只要 N。玩家机器存储吃紧时，快路抛 IOException ⇒ 旧实现直接折成 false ⇒
        // 屏上「SAVEDATA命令在存档时发生了意料之外的错误」，而**存到空槽却照样成功**
        // （空槽只有临时文件一份占用）—— 恰好就是玩家报的两条现象。
        // 退路只在快路已经失败时走，因此对正常设备的行为零改动；代价是这一次覆盖失去
        // 「被杀不截断」的保证，退化成原版语义 —— 比「存不上」好。
        val ok = writeInPlace(f, bytes)
        if (ok) {
            SaveDiagnostics.note("WRITE", path, "原子换名失败，已退原地截断覆盖（本次无崩溃安全保证）")
        }
        return ok
    }

    /**
     * 流式写（OOM-B 波，2026-11-08）：字节边产边落盘，不再物化整份容器 ByteArray。
     *
     * 路线与 [write] 完整同构：同目录临时文件（[producer] 直写）→ `force(true)` → 原子换名；
     * 快路任何 **Exception** ⇒ 退 [writeInPlace] 原地截断（存储吃紧时可写）。
     *
     * 唯一新增分支是「[producer] 自己抛异常」：它意味着序列化失败，重跑一遍只会同样失败，
     * 还会先把旧档截断 —— 故直接返回 false（Exception）或上抛（`Error`），**不走退路**。
     * 区分靠 [GuardedOutputStream]：只有底层通道写失败才标 [GuardedOutputStream.ioFailed]，
     * 那才是值得退原地重写的 IO 失败。
     */
    override fun write(path: String, producer: (OutputStream) -> Unit): Boolean {
        val f = resolve(path) ?: return false
        // 同 [write]：只读档显式拒绝，不依赖 POSIX rename 的权限差异。
        if (isReadOnly(path)) return false
        val parent = f.parent ?: return false
        return when (writeByAtomicReplace(parent, f, producer)) {
            StreamWriteOutcome.OK -> true
            StreamWriteOutcome.PRODUCER_FAILED -> false
            StreamWriteOutcome.IO_FAILED -> {
                val ok = writeInPlace(f, producer)
                if (ok) {
                    SaveDiagnostics.note("WRITE", path, "原子换名失败，已退原地截断覆盖（本次无崩溃安全保证）")
                }
                ok
            }
        }
    }

    /**
     * 快路（流式口）：同目录临时文件 → `force(true)` → 换名，返回三态。
     *
     * Exception 分两类（见 [write] 的流式口 KDoc）：producer 自身抛的 = 序列化失败，
     * 不可退路；其余（建目录 / 建临时 / 通道写 / 换名失败）= IO 失败，可退原地写。
     * `Error`（[OutOfMemoryError]）一律上抛，不截断任何既有文件（口径同 [writeByAtomicReplace]）。
     */
    private fun writeByAtomicReplace(
        parent: Path,
        target: Path,
        producer: (OutputStream) -> Unit,
    ): StreamWriteOutcome {
        var tmp: Path? = null
        var moved = false
        var guarded: GuardedOutputStream? = null
        var producerFailed = false
        return try {
            Files.createDirectories(parent)
            tmp = Files.createTempFile(parent, TMP_PREFIX, TMP_SUFFIX)
            FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
                val g = GuardedOutputStream(Channels.newOutputStream(ch))
                guarded = g
                try {
                    producer(g)
                } catch (e: Throwable) {
                    producerFailed = true
                    throw e
                }
                g.flush()
                ch.force(true)
            }
            replaceByMove(tmp, target)
            moved = true
            StreamWriteOutcome.OK
        } catch (e: Throwable) {
            if (e is Error) throw e
            // 通道本身写失败（磁盘满 / 通道被拒）即使是从 producer 的 write 调用里冒出来，
            // 也归 IO 失败：退原地截断重写正是为它准备的（玩家「不能覆盖旧存档」那一类）。
            if (producerFailed && guarded?.ioFailed != true) StreamWriteOutcome.PRODUCER_FAILED
            else StreamWriteOutcome.IO_FAILED
        } finally {
            if (!moved) tmp?.let { p -> runCatching { Files.deleteIfExists(p) } }
        }
    }

    /**
     * 快路（字节口）：同目录临时文件 → `force(true)` → 换名。返回 false = 这条路走不通（调用方退原地写）。
     *
     * 任何 **Exception**（建目录 / 建临时文件 / 写满 / 换名被拒 / 空间不足）都收敛成 false，不抛 ——
     * 退路仍可能成功，把异常抛给 [write] 的调用方会让退路根本没机会跑。
     *
     * `Error`（堆贴顶的 [OutOfMemoryError]）**不再吞掉**，原样上抛（2026-11-02 修）：
     * - 退路 `writeInPlace` 的第一句就是 `TRUNCATE_EXISTING` —— 为一次注定再 OOM 的写去截断
     *   玩家已有的好档，是把「存不上」升级成「档没了」；
     * - `FileSystemOps.saveData` 的求援重试链（提交 0452e8dd）只接得住从 `io.write` **抛出**的
     *   OOM；在这里折成 false，那条链对写入阶段永远不触发，等于白建。
     *   见 `SaveOomReclaimTest` —— 假体本来就按「write 抛 OOM」建模，生产实现此前与假体口径不一致。
     */
    private fun writeByAtomicReplace(parent: Path, target: Path, bytes: ByteArray): Boolean {
        var tmp: Path? = null
        var moved = false
        return try {
            Files.createDirectories(parent)
            // 与目标同目录才能保证 ATOMIC_MOVE 不跨卷；createTempFile 负责同秒并发写的唯一性。
            tmp = Files.createTempFile(parent, TMP_PREFIX, TMP_SUFFIX)
            FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
                val buf = ByteBuffer.wrap(bytes)
                // write 可能部分写入，必须循环到缓冲耗尽。
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true)
            }
            replaceByMove(tmp, target)
            moved = true
            true
        } catch (_: Exception) {
            false
        } finally {
            // 只有失败路径要清场：move 成功后 tmp 已不存在，且 moved 标记拦住重复删除。
            if (!moved) tmp?.let { p -> runCatching { Files.deleteIfExists(p) } }
        }
    }

    /**
     * 退路：原地截断覆盖（原版 `new FileStream(path, FileMode.Create, FileAccess.Write)`）。
     *
     * 同 [writeByAtomicReplace]：Exception 收敛成 false，[OutOfMemoryError] 一类 `Error` 上抛
     * （截断已经发生，让调用方的重试链去接管比静默失败多一条活路）。
     */
    internal fun writeInPlace(target: Path, bytes: ByteArray): Boolean = try {
        FileChannel.open(
            target,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { ch ->
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 退路（流式口）：原地截断覆盖，[producer] 直接写目标文件。
     *
     * 与字节版 [writeInPlace] 同口径：Exception 收敛成 false，`Error`（OOM）上抛 ——
     * 截断已经发生时，让调用方的求援重试链接管比静默失败多一条活路。
     */
    internal fun writeInPlace(target: Path, producer: (OutputStream) -> Unit): Boolean = try {
        FileChannel.open(
            target,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { ch ->
            val out = GuardedOutputStream(Channels.newOutputStream(ch))
            producer(out)
            out.flush()
            ch.force(true)
        }
        true
    } catch (e: Throwable) {
        if (e is Error) throw e
        false
    }

    /** 快路（流式口）的三种结局，见 [write] 的流式口 KDoc。 */
    private enum class StreamWriteOutcome { OK, PRODUCER_FAILED, IO_FAILED }

    /**
     * 把「底层通道写失败」与「[producer] 自己抛异常」分开：前者可退原地重写，
     * 后者不能（重跑只会再失败、还会先截断旧档）。判据见 [write] 的流式口 KDoc。
     */
    private class GuardedOutputStream(private val raw: OutputStream) : OutputStream() {
        var ioFailed: Boolean = false
            private set

        override fun write(b: Int) = guard { raw.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) = guard { raw.write(b, off, len) }

        override fun flush() = guard { raw.flush() }

        private inline fun guard(block: () -> Unit) {
            try {
                block()
            } catch (e: Throwable) {
                ioFailed = true
                throw e
            }
        }
    }

    /**
     * 把 [tmp] 换名到 [target]。
     *
     * 首选 `ATOMIC_MOVE` + `REPLACE_EXISTING`；两类平台差异退回普通 `REPLACE_EXISTING`
     * move（仍不会留截断档，只是失去「换名瞬间可见」的原子性）：
     * - 文件系统不支持原子换名 → [AtomicMoveNotSupportedException]；
     * - 实现把 `REPLACE_EXISTING` 与 `ATOMIC_MOVE` 互斥、目标已存在时拒绝 → [FileAlreadyExistsException]。
     *
     * 真正的失败（权限、跨卷、只读）照旧冒泡给 [write] 的 runCatching 收敛成 false。
     */
    private fun replaceByMove(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: FileAlreadyExistsException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun delete(path: String): Boolean = runCatching {
        val f = resolve(path) ?: return false
        Files.deleteIfExists(f)
        true
    }.getOrDefault(false)

    override fun isReadOnly(path: String): Boolean {
        val f = resolve(path) ?: return false
        if (!Files.isRegularFile(f)) return false
        // Windows 的 ReadOnly 属性与 POSIX 的可写位都反映在 isWritable 上。
        // 探测异常按 false（非只读）放行到 delete 的兜底 —— 见接口 KDoc 的契约段。
        return runCatching { !Files.isWritable(f) }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // SaveFolderIo —— 旧档迁移的目录面（SAVE-01；Config.cs:482-485 + :551-590）
    // ------------------------------------------------------------------

    /** 目录名固定 `sav`（原版 `Config.cs:470` / `:474-476` 写死的 `"sav"`，与 [SaveConfig.saveFolderPrefix] 同源）。 */
    private val savDir: Path get() = base.resolve(SaveFolderMigration.SAV_FOLDER)

    /** 原版 `Directory.Exists(Config.SavDir)`（Config.cs:482）：`sav` 是**普通文件**时算不存在。 */
    override fun saveFolderExists(): Boolean = Files.isDirectory(savDir)

    /**
     * 原版 `Directory.CreateDirectory(SavDir)`（Config.cs:555）：已存在 = 成功；
     * 任何异常（含 `sav` 已是普通文件、根只读）收敛成 false ⇒ 调用方走 Config.cs:557-561 的弹框出口。
     */
    override fun createSaveFolder(): Boolean = runCatching {
        Files.createDirectories(savDir)
        true
    }.getOrDefault(false)

    /**
     * 原版 `Directory.GetFiles(ExeDir, "save*.sav", TopDirectoryOnly)`（Config.cs:563 / :579）。
     *
     * 只列根的**直接子项**且只认普通文件；名字按 [SaveFolderMigration.matchesSavePattern]
     * 判定（大小写不敏感，同 Windows）；升序仅为可复现（原版序由文件系统决定）。
     * 枚举本身失败（根不可读）给空表 ⇒ 迁移按「无东西可搬」静默跳过，不抛。
     */
    override fun rootSaveFileNames(): List<String> = runCatching {
        Files.list(base).use { stream ->
            stream
                .filter { p -> Files.isRegularFile(p) && SaveFolderMigration.matchesSavePattern(p.fileName.toString()) }
                .map { p -> p.fileName.toString() }
                .sorted()
                .collect(java.util.stream.Collectors.toList())
        }
    }.getOrDefault(emptyList())

    /**
     * 原版 `File.Move(src, dst)`（Config.cs:577 / :583）：两端都必须落在 [base] 内（越界 = false），
     * 目标已存在时**不覆盖**（.NET 的 `File.Move` 抛 `IOException` ⇒ false ⇒ 整批迁移中止）。
     */
    override fun moveToFile(fromRel: String, toRel: String): Boolean = runCatching {
        val src = resolve(fromRel) ?: return false
        val dst = resolve(toRel) ?: return false
        if (!Files.isRegularFile(src)) return false
        dst.parent?.let { Files.createDirectories(it) }
        Files.move(src, dst)
        true
    }.getOrDefault(false)

    /** 路径归一 + 越界拒绝（与 `RootedFileOps` 同口径）。 */
    private fun resolve(path: String): Path? {
        val rel = path.replace('\\', '/')
        if (rel.isEmpty()) return null
        val p = runCatching { Path.of(rel) }.getOrNull() ?: return null
        val abs = if (p.isAbsolute) p.normalize() else base.resolve(p).normalize()
        return if (abs.startsWith(base)) abs else null
    }

    private companion object {
        /**
         * 临时文件名前缀/后缀（[write] 用）。点开头 + `.tmp` 后缀不会被
         * [rootSaveFileNames] 的 `save*.sav` 判定当成存档；`.eratw-save-` 是排障时一眼可认的标记。
         */
        const val TMP_PREFIX: String = ".eratw-save-"
        const val TMP_SUFFIX: String = ".tmp"
    }
}

/**
 * [FileOps] + 三个二进制钩子的适配器（宿主迁移期用）。
 *
 * 缺省钩子全部返回「失败 / 不存在」，因此**误接线不会伪造成功**：写会落成
 * `SAVEDATA` 的 false（语句层打印错误行），读会落成 `LOADDATA` 的 LoadCorruptedData。
 */
class FileOpsSaveIo(
    private val ops: FileOps,
    private val readBytes: (String) -> ByteArray? = { null },
    private val writeBytes: (String, ByteArray) -> Boolean = { _, _ -> false },
    private val deleteFile: (String) -> Boolean = { false },
    private val readOnly: (String) -> Boolean = { false },
) : SaveFileIo {

    override fun exists(path: String): Boolean = ops.exists(path)

    override fun read(path: String): ByteArray? = readBytes(path)

    override fun write(path: String, bytes: ByteArray): Boolean = writeBytes(path, bytes)

    override fun delete(path: String): Boolean = deleteFile(path)

    override fun isReadOnly(path: String): Boolean = readOnly(path)
}
