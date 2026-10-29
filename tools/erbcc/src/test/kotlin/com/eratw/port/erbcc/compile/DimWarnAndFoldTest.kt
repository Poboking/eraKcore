package com.eratw.port.erbcc.compile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * erbcc 编译路径的 `#DIM` 失败告警 + 纯静态折叠接线（BOOT 修缺陷面）。
 *
 * ## 两个缺陷在此锁死
 *
 * 1. **折叠回调接上**：装载期可折叠函数（UNICODE/POWER 一类，spec:
 *    `FunctionMethodTerm.cs:35-56`）此前在 erbcc 解析配置上缺席——带这类调用的
 *    `#DIM` 按非 SingleTerm 方向失败、整条声明消失；现在解析期配置挂
 *    `withPureStaticDimFolders`，侧车（strConstants / privateDims）与源码路径同折。
 * 2. **ERB 函数外 `#DIM` 丢弃并留痕（CMP-89）**：原版 `ErbLoader.cs:413-423` 对
 *    「前一行不是 FunctionLabelLine」的 `#` 行按 `InvalidSharp`（level 1）告警后丢弃，
 *    全局用户变量只从 **ERH** 装载（`Process.cs:309/:313`）。此前本侧把它收进全局声明，
 *    命名空间成原版超集；现在按原版丢弃，并把原因进 notes + loadWarnings 同一可见面。
 *
 * > 历史：本文件原先把「全局 `#DIMS CONST`」写在 ERB 里并断言语义生效 —— 那正是 CMP-89
 * > 的缺陷形态（原版会丢弃），CMP-89 接线后按原版口径改钉。
 */
class DimWarnAndFoldTest {

    @TempDir
    lateinit var dir: Path

    /** 两条 ERB 函数外【非法】全局声明 + 一条可折叠私有声明。 */
    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        Files.write(
            root.resolve("01_dim.ERB"),
            """
            ; 合成小语料：#DIM 告警与折叠
            #DIMS CONST LETTER_A = UNICODE(65)
            #DIMS CONST REF_A = LETTER_A
            #DIM EXTRA JUNK
            #DIM CONST NEED_DEFINE = NO_SUCH_CONST_HERE

            @FOLD_FN
            #DIM PRIV, POWER(2, 3)
            PRINTL OK
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun `ERB 函数外 DIM 按原版丢弃并留痕`() {
        writeCorpus(dir)
        val result = CorpusCompile.compile(dir)
        assertTrue(result.ok, "丢弃是告警语义，不翻转成败（原版 InvalidSharp = warn 1）: ${result.failures}")
        // 四条 ERB 函数外声明全部丢弃：全局声明表为空（原版全局只从 ERH 装载）。
        assertTrue(
            result.bootMetadata.globalDims.isEmpty(),
            "ERB 函数外 #DIM 不得进全局声明: ${result.bootMetadata.globalDims}",
        )
        // 逐条留痕（notes 与 loadWarnings 同一可见面）。
        val dropped = result.notes.filter { it.contains("ERB 函数外 #DIM 无效") }
        assertEquals(4, dropped.size, "4 条声明各留一条痕；实际: ${result.notes}")
        assertTrue(dropped.any { it.contains("LETTER_A") }, dropped.toString())
        val warned = result.warnings.filter { it.message.contains("ERB 函数外 #DIM 无效") }
        assertEquals(4, warned.size, "装载告警通道同源；实际: ${result.warnings}")
        assertEquals("01_dim.ERB", warned.first().file)
    }

    @Test
    fun `纯静态折叠在 erbc 编译路径生效`() {
        writeCorpus(dir)
        val result = CorpusCompile.compile(dir)
        // 私有 #DIM：POWER(2, 3) 折成维度 8（无回调时该声明按「含函数调用」失败、不进侧车）。
        val priv = result.bootMetadata.privateDims.firstOrNull { it.spec.name == "PRIV" }
        assertTrue(priv != null, "私有 #DIM PRIV 应进侧车: ${result.bootMetadata.privateDims}")
        assertEquals(listOf(8), priv!!.spec.dims.toList())
        // 折叠回调本身不可序列化：侧车 dim-config 仍只落数据字段（回调缺席 = 默认 null）。
        assertEquals(null, result.bootMetadata.dimParseConfig.functionFolder)
    }
}
