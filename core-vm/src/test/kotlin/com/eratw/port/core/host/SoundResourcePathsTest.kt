package com.eratw.port.core.host

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `sound/` 资源名解析（原版 `File.Exists(Path.GetFullPath(".\\sound\\" + name))`，
 * spec: FunctionMethodCreator.cs:8214-8218 的 EXISTSOUND、
 * FunctionIdentifier.cs:2622-2628 的 PLAYSOUND / :2691-2696 的 PLAYBGM）。
 *
 * 钉两件事：
 * 1. Windows NTFS 大小写不敏感 ⇒ 语料里 `bad-apple-audio.MP3` 对磁盘
 *    `sound/bad-apple-audio.mp3`（`TITLE.ERB:132`）必须命中；
 * 2. 安全边界与 [com.eratw.port.platform.audio.FileSoundSourceResolver] 同源
 *    （逃出根目录 / 绝对路径 / 盘符一律不解析）。
 */
class SoundResourcePathsTest {

    private fun root(): File = createTempDirectory("eratw-soundpath").toFile()

    @Test
    fun `原名精确命中不列目录`() {
        val sound = root()
        val hit = File(sound, "hit.mp3").apply { writeText("x") }
        assertEquals(hit.canonicalFile, SoundResourcePaths.resolve(sound, "hit.mp3")?.canonicalFile)
    }

    @Test
    fun `扩展名大小写不一致时回落到磁盘真名`() {
        val sound = root()
        val hit = File(sound, "bad-apple-audio.mp3").apply { writeText("x") }
        // 语料实形：TITLE.ERB:132 `PLAYSOUND "bad-apple-audio.MP3"`
        assertEquals(hit.canonicalFile, SoundResourcePaths.resolve(sound, "bad-apple-audio.MP3")?.canonicalFile)
    }

    @Test
    fun `目录段与文件段同时回落`() {
        val sound = root()
        val hit = File(File(sound, "小图书馆"), "姆Q.mp3").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        // 语料实形：M_KOJO_K54_2_事件系.ERB:200-231 的 `小图书馆/姆Q.MP3`
        assertEquals(hit.canonicalFile, SoundResourcePaths.resolve(sound, "小图书馆/姆Q.MP3")?.canonicalFile)
        // 目录段大小写不一致同样命中（ASCII 目录名）
        val ascii = File(File(sound, "Library"), "sfx.ogg").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        assertEquals(ascii.canonicalFile, SoundResourcePaths.resolve(sound, "library/SFX.OGG")?.canonicalFile)
    }

    @Test
    fun `真名不存在时仍判不存在`() {
        val sound = root()
        File(sound, "hit.mp3").writeText("x")
        assertNull(SoundResourcePaths.resolve(sound, "nope.mp3"))
        assertNull(SoundResourcePaths.resolve(sound, "HIT.MP3X"))
    }

    @Test
    fun `目录形态不是文件`() {
        val sound = root()
        File(sound, "folder.mp3").mkdirs()
        assertNull(SoundResourcePaths.resolve(sound, "folder.mp3"))
    }

    @Test
    fun `折叠点点但不逃出根目录`() {
        val sound = root()
        val hit = File(sound, "a.mp3").apply { writeText("x") }
        assertEquals(hit.canonicalFile, SoundResourcePaths.resolve(sound, "sub/../a.mp3")?.canonicalFile)
        assertNull(SoundResourcePaths.resolve(sound, "../secret.mp3"))
        assertNull(SoundResourcePaths.resolve(sound, "sub/../../secret.mp3"))
    }

    @Test
    fun `绝对路径盘符与空名一律拒绝`() {
        val sound = root()
        File(sound, "a.mp3").writeText("x")
        assertNull(SoundResourcePaths.resolve(sound, File(sound, "a.mp3").absolutePath))
        assertNull(SoundResourcePaths.resolve(sound, "/etc/passwd"))
        assertNull(SoundResourcePaths.resolve(sound, "C:audio.mp3"))
        assertNull(SoundResourcePaths.resolve(sound, ""))
        assertNull(SoundResourcePaths.resolve(sound, "."))
    }

    @Test
    fun `游戏根基准走 sound 子目录`() {
        val gameRoot = root()
        val hit = File(File(gameRoot, SoundResourcePaths.SOUND_DIR_NAME), "bgm.wav").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        assertEquals(hit.canonicalFile, SoundResourcePaths.resolveInGameRoot(gameRoot, "BGM.WAV")?.canonicalFile)
    }
}
