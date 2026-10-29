package com.eratw.port.erbcc.compile

import com.eratw.port.core.loader.BootMetadata
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * ERH 字符串常量 → 函数内 `#DIMS CONST` 折叠 → 侧车往返（BOOT-STRCONST）。
 *
 * 语料实形：
 * - `ステータス表示関連/グラフィック特殊効果.ERH:19`
 *   `#DIMS CONST EFFNAME_SPLIT = ","`；
 * - 同目录 `.ERB:64`（函数体内）`#DIMS CONST C_SPLITWORD = EFFNAME_SPLIT`。
 *
 * 原版 ERH 先于 ERB 装载，`analyzeSharpDimLines` 的多轮重试把字符串常量先注册进
 * ConstantData，ERB 侧的初值段再经 `Restructure` 折成 `SingleStrTerm`
 * （spec: `ErhLoader.cs:249-338`、`UserDefinedVariableData.cs:405-416`）。
 * 生产 `CorpusCompile` 若只把整型常量放进 `DimParseConfig`，私有声明在**编译期**
 * 就按 `InitialValueOnlyConst` 失败、侧车不留声明，运行到 `C_SPLITWORD` 才报
 * 「未定义的变量」。
 */
class StrConstantSidecarTest {

    /**
     * 合成语料：ERH 里先写别名、后写实体（验证不动点重试），ERB 函数体内再引用别名。
     * 全局整型常量一并放入，钉住「字符串折叠不挤掉整型表」。
     */
    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        Files.write(
            root.resolve("STRCONST.ERH"),
            """
            ; 字符串常量：别名先于实体出现，装载期要重试
            #DIMS CONST SEP_ALIAS = SEP
            #DIMS CONST SEP = ","
            #DIM CONST PAD_WIDTH = 3
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        Files.write(
            root.resolve("MAIN.ERB"),
            """
            @MINI_STRCONST
            #DIMS CONST C_SEP = SEP_ALIAS
            PRINTL C_SEP
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun `侧车携带 ERH 字符串常量并折出函数内 CONST`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = CorpusCompile.compile(corpus)
        assertTrue(result.ok, "合成语料必须编译通过；失败 ${result.failures.take(5)}")

        val cfg = result.bootMetadata.dimParseConfig
        assertEquals(",", cfg.strConstants["SEP"], "ERH 字面量常量必须进侧车字符串常量表")
        assertEquals(",", cfg.strConstants["SEP_ALIAS"], "前向引用的别名经不动点折出")
        assertTrue("PAD_WIDTH" in cfg.intConstants, "整型常量表不受字符串折叠影响")

        val priv = result.bootMetadata.privateDims.singleOrNull { it.spec.name == "C_SEP" }
        assertNotNull(
            priv,
            "私有 #DIMS CONST 未进侧车：${result.bootMetadata.privateDims.map { it.spec.name }}",
        )
        assertEquals("MINI_STRCONST", priv.functionKey, "所属函数键")
        assertEquals(listOf(","), priv.spec.defaultsStr, "初值必须折成字符串值（不是标识符原文）")
        assertTrue(priv.spec.isStr, "字符串类型位")
        assertTrue(priv.spec.isConst, "CONST 位")

        // 侧车往返：字符串常量表与折叠后的私有 spec 都必须原样读回
        val text = result.bootMetadata.encodeText()
        val back = BootMetadata.parse(text)
        assertEquals(cfg.strConstants, back.dimParseConfig.strConstants, "字符串常量表往返")
        assertEquals(
            priv.spec.defaultsStr,
            back.privateDims.single { it.spec.name == "C_SEP" }.spec.defaultsStr,
            "私有 CONST 的折叠结果往返",
        )
        assertEquals(text, back.encodeText(), "侧车文本闭环")
    }
}
