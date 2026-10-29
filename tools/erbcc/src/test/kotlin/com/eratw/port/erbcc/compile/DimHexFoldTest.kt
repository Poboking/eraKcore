package com.eratw.port.erbcc.compile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 私有 `#DIM/#DIMS CONST` 初值引用 `0x` 全局常量时必须进侧车（红 `C_RANK10`）。
 *
 * 根因：erbcc 的 [com.erbcc.bridge.sizeConsts] 用 core-vm `DimSizeEvaluator`
 * 折常量表，而后者原先只认十进制；`DIM.ERH:595/618` 的
 * `#DIM CONST C_WHITE = 0xC0C0C0` / `C_P_PURPLE = 0xC070C0` 折不出来，
 * `collectPrivateDims` 解析 `#DIM CONST C_RANK10, 10 = C_P_PURPLE, …` 时
 * 常量表缺键 ⇒ 整条声明丢弃 ⇒ 运行到 `C_RANK10:RANK` 才报「未定义的变量」。
 *
 * 语料实形：`魔改内容/PRINT系/Print_&_Input.ERB:24-29`（`{}` 块内续行）与
 * `COMMON.ERB:2166`（同函数被遮蔽的字符串版 `#DIMS`）。本用例同时钉单行与块内
 * 两种物理形态，且要求**无** level 2 告警。
 */
class DimHexFoldTest {

    @TempDir
    lateinit var dir: Path

    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        // 真实语料的色值常量在 ERH（DIM.ERH:595/618）；[sizeConsts] 只收 ERH 的
        // #DIM CONST，ERB 函数外的声明不在尺寸常量表里。
        Files.write(
            root.resolve("DIM.ERH"),
            """
            #DIM CONST C_P_PURPLE = 0xC070C0
            #DIM CONST C_WHITE = 0xC0C0C0
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        Files.write(
            root.resolve("01_hex.ERB"),
            """
            ; 合成小语料：hex 全局常量 → 私有 #DIM CONST 初值

            @SINGLE_LINE(RANK)
            #DIM RANK
            #DIM CONST C_RANK7, 3 = C_P_PURPLE, C_WHITE, C_WHITE
            PRINTL OK

            @BLOCK(RANK)
            #DIM RANK
            {
            #DIM  CONST C_RANK10, 3 =
            	 C_P_PURPLE,  C_WHITE,  C_WHITE
            }
            PRINTL OK
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun `hex 全局常量折进尺寸常量表`() {
        writeCorpus(dir)
        val result = CorpusCompile.compile(dir)
        assertTrue(result.ok, "合成语料应编译成功: ${result.failures}")
        val consts = result.bootMetadata.dimParseConfig.intConstants
        assertEquals(0xC070C0L, consts["C_P_PURPLE"], "十六进制常量必须可折: $consts")
        assertEquals(0xC0C0C0L, consts["C_WHITE"], "十六进制常量必须可折: $consts")
    }

    @Test
    fun `单行与块内 hex 初值私有 DIM 都进侧车`() {
        writeCorpus(dir)
        val result = CorpusCompile.compile(dir)
        assertTrue(result.ok, "合成语料应编译成功: ${result.failures}")
        assertTrue(
            result.dimWarnings.none { it.message.contains("C_RANK7") || it.message.contains("C_RANK10") },
            "hex 初值不得再触发 #DIM 解析告警: ${result.dimWarnings}",
        )

        val single = result.bootMetadata.privateDims.firstOrNull { it.spec.name == "C_RANK7" }
        assertTrue(single != null, "单行 C_RANK7 应进侧车: ${result.bootMetadata.privateDims.map { it.spec.name }}")
        assertEquals(listOf(3), single!!.spec.dims.toList())
        assertEquals(
            listOf(0xC070C0L, 0xC0C0C0L, 0xC0C0C0L),
            single.spec.defaultsInt?.toList(),
        )

        val block = result.bootMetadata.privateDims.firstOrNull { it.spec.name == "C_RANK10" }
        assertTrue(block != null, "块内续行 C_RANK10 应进侧车: ${result.bootMetadata.privateDims.map { it.spec.name }}")
        assertEquals(listOf(3), block!!.spec.dims.toList())
        assertEquals(
            listOf(0xC070C0L, 0xC0C0C0L, 0xC0C0C0L),
            block.spec.defaultsInt?.toList(),
        )
        assertTrue(
            block.functionKey.equals("BLOCK", ignoreCase = true),
            "私有声明归属块所在函数: ${block.functionKey}",
        )
    }
}
