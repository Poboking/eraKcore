package com.eratw.port.core.save

import com.eratw.port.core.host.FileOps
import java.nio.file.Files
import java.nio.file.Path
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

    /** 覆盖写；父目录不存在时由实现创建。返回 false = 失败。 */
    fun write(path: String, bytes: ByteArray): Boolean

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

    override fun write(path: String, bytes: ByteArray): Boolean = runCatching {
        val f = resolve(path) ?: return false
        f.parent?.let { Files.createDirectories(it) }
        Files.write(f, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        true
    }.getOrDefault(false)

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
