package com.eratw.port.boot

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C5：4.981 语料档的**显式判据**闸（装载相位，P2）。
 *
 * 前提证伪链：`4c710f48d` 曾在 `GameSession.isSkiaEngineRoot` 用 `emuera.config` 原始行
 * `contains("RENDERING BACKEND", ignoreCase = true)` 的**键存在性**代理「这是 4.981 Skia
 * 引擎」，再切 `VariableStore.perCallLocalReset` 的 LOCAL 生命周期（报告 §4-C6 / 清单 §149）。
 * `b98b5b211`（2026-11-05）以 skia 反编译树定案「skia 未改 LOCAL 生命周期」
 * （`VariableData.cs:3365` 的 SetDefaultLocalValue、`:3377`/`:4215` 的 ClearLocalValue，
 * 调用点仅 `VariableEvaluator.cs:1339` ResetData、`:2441` 文本读档、`:2626` 二进制读档），
 * 把该自造门连同判据函数连根删除，LOCAL 回归 `LocalSemanticsTest` 7 例钉住的单一口径。
 * 因此本路**不再造生产判据**（那会引入原版不存在的新状态），只把测试面残留的
 * `U981Config981GateTest.gameRoot981OrNull` 的键行 contains 判档改成与
 * `ConfigChainTest.corpusRoot()` 同一把尺。
 *
 * 显式判据 = [CorpusProfile.detect]：就近读 `ERB/DIM.ERH` 的 `#DIM CONST NOW_VERSION`
 * （4.980=20260326、4.981=20261017；未在册或未读到判红不降级）。这与主源 1.824 装载期
 * 配置项的裁决形态同形 —— 靠注册条目 + 精确查表 + 值解析 + 显式分支，不靠「某键字符串
 * 是否出现」：`ConfigData.cs:55`（注册 `ConfigItem<TextDrawingMode>`、缺省实参 TEXTRENDERER）、
 * `:529-543`（跳注释空行、按冒号切、`array[0].Trim()` 精确查表、查不到 continue）、
 * `:262-284`（`GetConfigItem` 大写后相等匹配 Name/Text/EngText，非 contains）、
 * `ConfigItem.cs:174-178`（`Enum.TryParse(ignoreCase)`，非法值抛 CodeEE →
 * `ConfigData.cs:579-581` 上屏）、`TextDrawingMode.cs:3-8`、`ConfigDialog.cs:996-1005`
 * （按枚举值 switch 显式分支）。缺省由注册实参裁决，非法值上屏，零静默兜底。
 *
 * 本类 6 例**全部不依赖外部语料根**：用临时目录造 `ERB/DIM.ERH` 与 `emuera.config`，
 * 在桌面/headless 环境也能证伪判据（组长 C5 裁决 b 条）。反向证明：把 [is981Root] 改回
 * `text.contains("SKIASHARP IMAGE QUALITY")` 的旧嗅探形态 ⇒ 前 5 例判红，恢复后全绿。
 */
internal fun is981Root(root: Path): Boolean =
    CorpusProfile.detect(root) == CorpusProfile.V4_981

class U981ExplicitRootCriterionTest {

    /** 造一个最小语料根：ERB/DIM.ERH 写 NOW_VERSION 行，emuera.config 写给定原文行。 */
    private fun syntheticRoot(dimErhLine: String?, vararg configLines: String): Path {
        val root = Files.createTempDirectory("eratw-u981-criterion")
        Files.createDirectories(root.resolve("ERB"))
        if (dimErhLine != null) {
            Files.write(
                root.resolve("ERB").resolve("DIM.ERH"),
                dimErhLine.toByteArray(Charsets.ISO_8859_1),
            )
        }
        Files.write(
            root.resolve("emuera.config"),
            configLines.joinToString("\n").toByteArray(Charsets.UTF_8),
        )
        return root
    }

    @Test
    fun `合法值 NOW_VERSION 4_981 判为 4_981 且不依赖 emuera_config 键行`() {
        val root = syntheticRoot("#DIM CONST NOW_VERSION = 20261017\n", "IGNORE CASE:YES")
        assertTrue(
            is981Root(root),
            "4.981 档只由 NOW_VERSION=20261017 决定；emuera.config 无 SKIASHARP / RENDERING BACKEND 键行照样命中",
        )
    }

    @Test
    fun `非法值 未登记的 NOW_VERSION 判红不降级`() {
        val root = syntheticRoot("#DIM CONST NOW_VERSION = 99999999\n", "RENDERING BACKEND:Auto")
        assertFailsWith<AssertionError> { is981Root(root) }
    }

    @Test
    fun `缺省 就近找不到 DIM_ERH 判红不降级`() {
        val root = syntheticRoot(null, "RENDERING BACKEND:Auto")
        assertFailsWith<AssertionError> { is981Root(root) }
    }

    @Test
    fun `大小写与空格边界 显式判据仍认 4_981`() {
        val root = syntheticRoot("  #dim   const   now_version=20261017  \n", "RENDERING BACKEND:Auto")
        assertTrue(is981Root(root), "NOW_VERSION_LINE 用 IGNORE_CASE 与空白通配，find 不锚行首")
    }

    @Test
    fun `不该命中却命中 4_980 根即便 emuera_config 含 SKIASHARP 与 RENDERING BACKEND 也不判 4_981`() {
        // 旧 contains 嗅探只要 emuera.config 出现标记行就判 4.981，会把 4.980 根误判成 4.981；
        // 显式判据按 NOW_VERSION 裁决，不受配置键行干扰。
        val root = syntheticRoot(
            "#DIM CONST NOW_VERSION = 20260326\n",
            "SKIASHARP IMAGE QUALITY:High",
            "RENDERING BACKEND:Auto",
        )
        assertFalse(is981Root(root), "非 4.981 根不会被误判为 4.981")
    }

    @Test
    fun `非 4_981 根基线 干净配置的 4_980 根不判 4_981`() {
        val root = syntheticRoot("#DIM CONST NOW_VERSION = 20260326\n", "IGNORE CASE:YES")
        assertFalse(is981Root(root), "4.980 的 NOW_VERSION=20260326 显式落 V4_980")
    }
}
