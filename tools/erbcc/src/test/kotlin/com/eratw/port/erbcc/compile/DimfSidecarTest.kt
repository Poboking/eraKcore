package com.eratw.port.erbcc.compile

import com.eratw.port.core.expr.OperandType
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `#DIMF`（4.981 fork 浮点声明）在 erbcc 两条链上的收编：
 *
 * 1. **预扫描**（[CorpusScan]）：私有/全局浮点名进 `privateVars` / `global`，类型 FLOAT
 *    —— 否则函数体里的 `倍率 = 1.0` 按「未定义的标识符」掉出切片；
 * 2. **侧车**（[CorpusCompile] 的 `dimLines` → `BootMetadata.privateDims` → `program.meta`）：
 *    运行期私有浮点变量名（语料实形 `倍率`）必须能从侧车取到。
 *
 * 语料实形（只读，不改）：`eraTW4.981-261017-EAGLOV3/ERB/DLC/魔法DLC/魔法相关函数.ERB:3190`
 * `#DIMF DYNAMIC 倍率`，`:3213` 起 `倍率 = 1.0` / `倍率 *= 1.2` / `IF 倍率 > 1.0`。
 * 语义出处：`specs/variables.yaml:105-111`（float_vars.dimf：`#DIMF` 仅 1 维、
 * `#DIMF CHARADATA` 在 fork 里掉进整数分支）。
 */
class DimfSidecarTest {

    @TempDir
    lateinit var dir: Path

    /**
     * 合成小语料：一条私有 `#DIMF DYNAMIC 倍率`；[withGlobal] 时再加一条全局（ERH）声明。
     *
     * 全局 `#DIMF` 只给**预扫描**用例（4.981 语料实测 0 条；语句层 `LineParser` 的
     * DIMF 分支按私有声明解析，作用域感知属跨路接线，见交付报告「需组长接线」）。
     */
    private fun writeCorpus(root: Path, withGlobal: Boolean = true) {
        java.nio.file.Files.createDirectories(root)
        root.resolve("main.ERB").writeText(
            """
            @永恒回归_倍率更新
            #DIMF DYNAMIC 倍率
            倍率 = 1.0
            倍率 *= 1.2
            IF 倍率 > 1.0
            	A = TOINT(倍率) + 5
            ENDIF
            RETURN
            """.trimIndent(),
        )
        if (withGlobal) {
            root.resolve("globals.ERH").writeText(
                """
                #DIMF CONST 全局倍率 = 2.5
                """.trimIndent(),
            )
        }
    }

    /**
     * 预扫描：私有浮点名按定义身份登记、类型 FLOAT、1 维；ERH 全局浮点名进全局符号表。
     *
     * 「无尺寸段补 1 维」与 `DimParser` 同口径（`UserDefinedVariableData.cs:292-299`）。
     */
    @Test
    fun `预扫描登记DIMF私有与全局浮点名`() {
        val root = dir.resolve("corpus")
        writeCorpus(root)
        val files = CorpusCompile.listCorpusFiles(root)
        val scan = CorpusScan.scan(files)

        val priv = scan.privateVars.entries.flatMap { it.value.entries }
            .firstOrNull { it.key == "倍率" }
        assertNotNull(priv, "私有 #DIMF 名必须进 privateVars: ${scan.privateVars}")
        assertEquals(OperandType.FLOAT, priv.value.operandType, "`#DIMF` 建浮点名（spec: variables.yaml:105-111）")
        assertEquals(1, priv.value.dimension, "无尺寸段 ⇒ 补 1 维")

        val global = scan.global.resolveVariable("全局倍率", null)
        assertNotNull(global, "ERH 全局 #DIMF 名必须进全局符号表")
        assertEquals(OperandType.FLOAT, global.operandType, "全局 #DIMF 同样是 FLOAT")
    }

    /**
     * 侧车：`program.meta` 的 `private-dim` 段必须带 `倍率`，且模型里的 spec 是浮点。
     *
     * 断言走 [BootMetaWriter.text]（= CLI 落 `program.meta` 的同一份文本），
     * 因为本路不写盘也能锁死「运行期能按名取到私有浮点变量」。
     */
    @Test
    fun `DIMF私有名进program_meta侧车`() {
        val root = dir.resolve("corpus2")
        writeCorpus(root, withGlobal = false)
        val result = CorpusCompile.compile(root)
        assertTrue(result.ok, "合成语料应编译成功: ${result.failures}")

        val spec = result.bootMetadata.privateDims.firstOrNull { it.spec.name == "倍率" }
        assertNotNull(spec, "私有 #DIMF 必须进 privateDims: ${result.bootMetadata.privateDims.map { it.spec.name }}")
        assertTrue(spec.spec.isFloat, "模型侧必须保留浮点标记（#DIMF）")
        assertEquals(listOf(1), spec.spec.dims.toList())

        val meta = BootMetaWriter.text(result.bootMetadata)
        assertTrue(
            meta.lines().any { it.startsWith("private-dim ") && it.contains("\"倍率\"") },
            "program.meta 的 private-dim 段必须含 倍率；实得:\n" +
                meta.lines().filter { it.startsWith("private-dim ") }.joinToString("\n"),
        )
        // 回归闸：不得把 DIMF 当成解析失败（splitDeclaration 只认 #DIM/#DIMS 时的症状）。
        assertTrue(
            result.dimWarnings.none { it.message.contains("倍率") },
            "DIMF 不应产生 level 2 解析失败: ${result.dimWarnings.map { it.message }}",
        )
    }
}
