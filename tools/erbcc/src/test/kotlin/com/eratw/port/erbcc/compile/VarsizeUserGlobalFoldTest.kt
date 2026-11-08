package com.eratw.port.erbcc.compile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 私有 `#DIM` 的尺寸 `VARSIZE("用户全局")` 必须在编译期折出。
 *
 * 原版 ERH 边装边往 `IdentifierDictionary` 注册（`IdentifierDictionary.cs:429`），
 * 后加载的声明能看到先声明的用户全局；`DimVarSizes.systemSnapshot` 只有
 * `VariableSize.csv` 的系统表，所以折叠快照必须再并入用户全局 `#DIM`
 * （[CorpusCompile] 的 `mergeGlobalUserDimSizes`）。
 *
 * 语料实形：`DLC/DLC.ERH:60` 的 `#DIM SAVEDATA DLC_Toggle, 200, 10` →
 * `NEWGAME/NEWGAME_MAKE.ERB:55` 的 `#DIM DYNAMIC 可选择的DLC,VARSIZE("DLC_Toggle")`。
 * 漏并时该私有声明在布局面与侧车双双被丢弃，运行到「游戏前的选择设定」报
 * 「未定义的变量 可选择的DLC」。
 */
class VarsizeUserGlobalFoldTest {

    @TempDir
    lateinit var dir: Path

    private fun writeCorpus(root: Path, globals: String) {
        Files.createDirectories(root)
        root.resolve("globals.ERH").writeText(globals.trimIndent())
        root.resolve("main.ERB").writeText(
            """
            @游戏前的选择设定
            #DIM DYNAMIC 可选择的DLC,VARSIZE("目标全局")
            RETURN
            """.trimIndent(),
        )
    }

    /** 用户全局 `#DIM` 的尺寸必须进折叠快照：`VARSIZE("目标全局")` = 200。 */
    @Test
    fun `私有大小的VARSIZE能折出用户全局声明`() {
        val root = dir.resolve("corpus")
        writeCorpus(
            root,
            """
            #DIM 目标全局, 200, 10
            """,
        )
        val result = CorpusCompile.compile(root)
        assertTrue(result.ok, "合成语料应编译成功: ${result.failures}")

        val spec = result.bootMetadata.privateDims.firstOrNull { it.spec.name == "可选择的DLC" }
        assertNotNull(
            spec,
            "VARSIZE(用户全局) 必须折出（否则私有 #DIM 被静默丢）: " +
                result.bootMetadata.privateDims.map { it.spec.name },
        )
        assertEquals(listOf(200), spec.spec.dims.toList(), "取的是首维长度")
        assertTrue(
            result.dimWarnings.none { it.message.contains("可选择的DLC") },
            "不应留下 level 2 解析失败: ${result.dimWarnings.map { it.message }}",
        )
    }

    /** 链式引用（全局 A 的尺寸用 VARSIZE(B)）靠多轮 install 收敛。 */
    @Test
    fun `用户全局之间的链式VARSIZE也折得出`() {
        val root = dir.resolve("corpus-chain")
        writeCorpus(
            root,
            """
            #DIM 目标全局, VARSIZE("底层全局"), 10
            #DIM 底层全局, 200
            """,
        )
        val result = CorpusCompile.compile(root)
        assertTrue(result.ok, "合成语料应编译成功: ${result.failures}")

        val spec = result.bootMetadata.privateDims.firstOrNull { it.spec.name == "可选择的DLC" }
        assertNotNull(spec, "链式 VARSIZE 也必须折出: ${result.bootMetadata.privateDims.map { it.spec.name }}")
        assertEquals(listOf(200), spec.spec.dims.toList())
    }
}
