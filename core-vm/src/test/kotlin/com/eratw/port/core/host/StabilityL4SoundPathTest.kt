package com.eratw.port.core.host

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L4 稳定性：Win32 路径分量末尾的空白与点归一（`SoundResourcePaths` 第三条查找路径）。
 *
 * 原版 `File.Exists(Path.GetFullPath(".\\sound\\" + name))`
 * （`FunctionMethodCreator.cs:8214-8218` / `FunctionIdentifier.cs:2622-2628`、`:2691-2696`）
 * 跑在 Win32 上，而 Win32 在每个路径分量末尾剥掉空格与点：`"x.mp3 "` / `"x.mp3."`
 * 命中的是磁盘上的 `x.mp3`。Android/Linux 按字面名匹配，不补这一步就漏判。
 *
 * 加这条查找是**纯追加**：原名（精确 + 大小写回落）能命中的调用结果一字不变，
 * 所以既有 `SoundResourcePathsTest` 的断言全部仍然成立。
 */
class StabilityL4SoundPathTest {

    private fun root(): File = createTempDirectory("eratw-l4-soundpath").toFile()

    @Test
    fun `文件名尾随点命中磁盘真名`() {
        val sound = root()
        val hit = File(sound, "x.mp3").apply { writeText("x") }
        assertEquals(hit.canonicalFile, SoundResourcePaths.resolve(sound, "x.mp3.")?.canonicalFile)
    }

    @Test
    fun `文件名尾随空格命中磁盘真名`() {
        val sound = root()
        val hit = File(sound, "x.mp3").apply { writeText("x") }
        assertEquals(hit.canonicalFile, SoundResourcePaths.resolve(sound, "x.mp3 ")?.canonicalFile)
        // 点与空格混排：Win32 逐字符从末尾剥，直到不是点/空格为止
        assertEquals(hit.canonicalFile, SoundResourcePaths.resolve(sound, "x.mp3. . ")?.canonicalFile)
    }

    @Test
    fun `目录段尾随点与大小写同时回落`() {
        val sound = root()
        val hit = File(File(sound, "Library"), "sfx.ogg").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        assertEquals(hit.canonicalFile, SoundResourcePaths.resolve(sound, "library/SFX.OGG. ")?.canonicalFile)
    }

    @Test
    fun `归一回空的分量判不存在`() {
        val sound = root()
        File(sound, "x.mp3").writeText("x")
        // "..." 归一后是空分量：Win32 上也不是正常文件名（原版 File.Exists 同样 false）
        assertNull(SoundResourcePaths.resolve(sound, "..."))
        assertNull(SoundResourcePaths.resolve(sound, "sub/..."))
    }

    @Test
    fun `原名与非存在名读数不变`() {
        val sound = root()
        val hit = File(sound, "y.ogg").apply { writeText("x") }
        assertEquals(hit.canonicalFile, SoundResourcePaths.resolve(sound, "y.ogg")?.canonicalFile)
        assertNull(SoundResourcePaths.resolve(sound, "missing.ogg "))
        // NUL 仍在 normalize 一开始就被拒，不走归一分支
        assertNull(SoundResourcePaths.resolve(sound, "y.ogg\u0000"))
    }
}
