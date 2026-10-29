package com.eratw.port.erbcc.compile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 「要么旧产物、要么新产物」的文件落盘：写同目录临时文件再原子改名。
 *
 * 产物目录是跨进程共享的（可能正被装载器/打包/设备拉取读取），直接写目标路径时进程被杀或
 * 磁盘满会留下**半截文件**，而它的前 4 字节已是合法魔数 `ERBC` —— 装载侧只会报「段越界」，
 * 运维分不清「产物坏了」还是「编译坏了」。原子改名把可观测状态收敛成两态：旧文件完整，
 * 或新文件完整；半截只可能停在 `.tmp`（不被装载链按名字查找）。
 *
 * 与 `StmtOffsetSidecar.write` 同一手法（`StmtOffsetSidecar.kt:103-105`），后缀也一致，
 * 便于同一套清理。
 */
object AtomicWrite {

    /** 临时文件后缀（`<name>.tmp`，与段 3 侧车同形）。 */
    const val TEMP_SUFFIX: String = ".tmp"

    /**
     * 原子落盘：先写 `<name>.tmp`，再 `ATOMIC_MOVE + REPLACE_EXISTING` 改名到目标。
     *
     * 任何一步抛错都清理临时文件后原样重抛 —— 目标文件保持旧内容（不存在时保持不存在）。
     */
    fun write(path: Path, bytes: ByteArray) {
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling(path.fileName.toString() + TEMP_SUFFIX)
        try {
            Files.write(tmp, bytes)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        }
    }
}
