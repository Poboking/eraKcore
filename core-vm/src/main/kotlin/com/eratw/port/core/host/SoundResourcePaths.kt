package com.eratw.port.core.host

import java.io.File

/**
 * `sound/` 资源名的解析（Windows 口径），PLAYSOUND / PLAYBGM / EXISTSOUND 共用同一个判定。
 *
 * ## 原版口径
 * 三条语句/函数都是同一句 `File.Exists(Path.GetFullPath(".\\sound\\" + name))`：
 * PLAYSOUND（spec: FunctionIdentifier.cs:2622-2628）、PLAYBGM（:2691-2696）、
 * EXISTSOUND（spec: FunctionMethodCreator.cs:8214-8218）。存在才建解码器
 * （Sound.cs:83-96 按扩展名选 reader），不存在直接静默返回。
 *
 * ## 为什么需要大小写回落
 * Windows 的 NTFS 默认大小写不敏感，`File.Exists("x.MP3")` 对磁盘上的 `x.mp3` 返回 true；
 * Android/Linux 的大小写敏感文件系统则返回 false。语料里两侧不一致是既成事实：
 * `ERB/TITLE.ERB:132` 的 `PLAYSOUND "bad-apple-audio.MP3"` 对应 `sound/bad-apple-audio.mp3`，
 * `ERB/口上・メッセージ関連/054 Patchouli [パチュリー]/小图书馆/M_KOJO_K54_2_事件系.ERB:200-231`
 * 引用的 `*.MP3` 对应 `sound/小图书馆` 下的同名小写文件。判定收到本函数一处，
 * 播放与 EXISTSOUND 不会分叉。
 *
 * ## 安全边界（与 [com.eratw.port.platform.audio.FileSoundSourceResolver] 同源）
 * `.` / `..` 先按 `Path.GetFullPath` 折叠；折叠后仍落在 [soundDir] 之内才解析，
 * 逃出根目录（`../secret.mp3`、根相对路径、盘符路径、NTFS 数据流的 `:`）一律判为不存在。
 */
object SoundResourcePaths {

    /** 原版拼接的前缀 `.\sound\`（FunctionIdentifier.cs:2622、:2691）。 */
    const val SOUND_DIR_NAME: String = "sound"

    /**
     * 名字 → 存在的普通文件；不存在 / 越界 / 非法路径返回 null。
     *
     * 先按原名精确命中（一次 `isFile`，热路径不列目录），落空才逐段做大小写不敏感回落。
     */
    fun resolve(soundDir: File, name: String): File? {
        val segments = normalize(name) ?: return null
        exactFile(soundDir, segments)?.let { return it }
        return caseInsensitiveFile(soundDir, segments)
    }

    /** 原名逐段拼接后的文件；目录或不存在 = null（与 Windows `File.Exists` 同判）。 */
    private fun exactFile(root: File, segments: List<String>): File? {
        var current = root
        for (segment in segments) current = File(current, segment)
        return if (current.isFile) current else null
    }

    /**
     * 逐段大小写不敏感回落：每段先取同名子项，落空再在目录列表里找 `equals(ignoreCase)`
     * 的第一项。任何一段找不到 = null；末段必须是普通文件。
     */
    private fun caseInsensitiveFile(root: File, segments: List<String>): File? {
        var current = root
        for (segment in segments) {
            val direct = File(current, segment)
            val listed = current.list()?.firstOrNull { it.equals(segment, ignoreCase = true) }
                ?: return null
            current = if (direct.exists()) direct else File(current, listed)
        }
        return if (current.isFile) current else null
    }

    /**
     * 名字 → 折叠后的相对段列表；`null` = 不可解析。
     *
     * 两种分隔符都当目录分隔符（Windows 原版的 `/` 与 `\` 都可用）；`.` 段丢弃；
     * `..` 段回退一级，回退越过根就整条判为不存在。绝对路径（`/x`、`\x`）、盘符（`C:`）
     * 与以分隔符结尾的名字（目录形态）直接拒绝 —— 原版是前缀拼接，不解释绝对路径，
     * `File.Exists` 对目录也返回 false。
     */
    private fun normalize(name: String): List<String>? {
        if (name.isEmpty() || name.contains('\u0000')) return null
        if (name.startsWith('/') || name.startsWith('\\')) return null
        if (name.endsWith('/') || name.endsWith('\\')) return null
        val segments = mutableListOf<String>()
        for (segment in name.split('/', '\\')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> {
                    // Windows 保留字符 `:`（盘符或 NTFS 数据流）在语料名字里不会出现，按不可解析处理。
                    if (segment.contains(':')) return null
                    segments += segment
                }
            }
        }
        return segments.ifEmpty { null }
    }

    /** 以游戏根（`Program.ExeDir` 口径）为基准：根下的 `sound/`。 */
    fun resolveInGameRoot(gameRoot: File, name: String): File? =
        resolve(File(gameRoot, SOUND_DIR_NAME), name)
}
