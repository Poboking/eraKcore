package com.eratw.port.core.save

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RootedSaveFileIo.isReadOnly] 的语义钉子（2026-10-22 审查 C 级收口）。
 *
 * 契约（见 [SaveFileIo.isReadOnly] KDoc）：
 * 1. 只读属性在位 → true（`DELDATA` 据此抛 DelReadOnlyFile，对齐 VariableEvaluator.cs:2710-2721）；
 * 2. 可写恢复 → false；
 * 3. 文件不存在 / 路径越界 → false（「查不到 ≠ 只读」，放行到 delete 的兜底）。
 *
 * 用 [java.io.File.setReadOnly] 置位：Windows 走 DOS 只读属性、POSIX 走 a-w 位，
 * 两端 [java.nio.file.Files.isWritable] 都反映为 false，跨平台成立。
 */
class RootedSaveFileIoTest {

    private fun tempRoot(): Path = createTempDirectory("eratw-saveio")

    @Test
    fun `只读属性在位时判只读`() {
        val root = tempRoot()
        val io = RootedSaveFileIo(root)
        val f = root.resolve("save01.sav").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertFalse(io.isReadOnly("save01.sav"), "初始应为可写")
        assertTrue(f.toFile().setReadOnly(), "setReadOnly 必须生效")
        assertTrue(io.isReadOnly("save01.sav"), "只读属性在位 → isReadOnly=true")

        // 恢复可写后回到 false（同一 io 实例重复查询不缓存）。
        assertTrue(f.toFile().setWritable(true))
        assertFalse(io.isReadOnly("save01.sav"), "恢复可写 → false")
    }

    @Test
    fun `文件不存在与路径越界都判非只读`() {
        val root = tempRoot()
        val io = RootedSaveFileIo(root)

        assertFalse(io.isReadOnly("nope.sav"), "不存在的文件查不到属性 → false")
        assertFalse(io.isReadOnly(""), "空路径 → false")
        assertFalse(io.isReadOnly("../outside.sav"), "越界路径被 resolve 拒绝 → false")
        assertFalse(
            io.isReadOnly(Files.createTempFile("eratw", "x").toString()),
            "绝对路径越界同 reject",
        )
    }

    @Test
    fun `读写删在只读文件上的兜底方向`() {
        val root = tempRoot()
        val io = RootedSaveFileIo(root)
        val f = root.resolve("save01.sav").apply { writeBytes(byteArrayOf(9)) }
        f.toFile().setReadOnly()

        // 写失败（探测与写入是两层：isReadOnly 不拦写，write 自己吃 AccessDenied）。
        assertFalse(io.write("save01.sav", byteArrayOf(1)), "对只读文件 write 必须失败而不是部分写")
        // 删除失败由 delete 返回值暴露（delData 据此收敛到 DelReadOnlyFile 文案）。
        assertTrue(io.isReadOnly("save01.sav"), "只读属性不拦 delete —— 兜底在返回值")
        // POSIX 上删除只取决于目录写权限，可能成功；Windows 上 DOS 只读会拦。
        // 两条路都对，这里只钉「不抛异常」这一条。
        io.delete("save01.sav")
    }
}
