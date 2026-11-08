package com.eratw.port.erbcc.compile

import com.eratw.port.core.expr.OperandType
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assumptions

/**
 * 簇 1 的**编译侧**半边（真语料）：`COM641_AC_カードタイプ付与BIT配列` 这条 `{ }` 行连续块
 * 必须在 erbcc 的预扫描里同样还原成一条声明并登记进全局符号表。
 *
 * 为什么单独钉这一条：运行侧（`:core-vm` 的 `Com641RealErhDimLoadTest`）与编译侧
 * （本用例走 [CorpusScan]）是两份独立实现的行读取器 —— 侧车 `globalDims` 由编译侧产出，
 * 运行期再按同一份原文重解析。任一侧的块合并缺了，表现都是「编译过、运行到用点报
 * 未定义的变量」（0.2.39 上报 34 份 / 19 台的那条）。
 * 两侧的合并口径同源：`CorpusScan.CONT_JOIN` = `ErbPreprocessor.continuationSeparator()`
 * = 单空格（spec: EraStreamReader.cs:178-179 + Config.cs:386），由 `PreprocessorParityTest` 整体对拍。
 *
 * 原文：`ERB/コマンド関連/COMF/外出系/COMF640-641_月虹市場&アビリティカード/`
 * `COMF641 アビリティカード_カードパラメータ.ERH`（4.981 专属，4.980 无 COMF640-641 目录）
 * 的 `:225` 起块、`:226` 声明、`:227`–`:259` 共 33 个按位元素、`:260` 收块。
 *
 * 门控：`ERATW_CORPUS`（必须指到 `<游戏根>/ERB`）；缺 env 或非 4.981 语料按假设跳过。
 */
class Com641RealCorpusScanTest {

    private companion object {
        const val BIT_ARRAY = "COM641_AC_カードタイプ付与BIT配列"
        const val ERH_REL =
            "コマンド関連/COMF/外出系/COMF640-641_月虹市場&アビリティカード/COMF641 アビリティカード_カードパラメータ.ERH"
    }

    @Test
    fun `erbcc 预扫描把真语料的行连续块登记成全局一维整型变量`() {
        val raw = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() }
        Assumptions.assumeTrue(raw != null, "未设置 ERATW_CORPUS：跳过真语料预扫描闸")
        val root: Path = Paths.get(raw).toAbsolutePath().normalize()
        val erh = root.resolve(ERH_REL)
        Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(erh), "$erh 不存在：当前语料根不是 4.981，跳过")

        val scan = CorpusScan.scan(listOf(erh))
        val info = scan.global.resolveVariable(BIT_ARRAY, null)
        assertNotNull(info, "行连续块必须还原成一条 #DIM 并登记全局：$erh")
        assertEquals(OperandType.INT, info!!.operandType, "卡牌类型 BIT 表是整型数组")
        assertEquals(1, info.dimension, "一维数组")
        assertEquals(false, info.isCharacterData, "非角色变量")
        // 块里其余 32 条同族声明同样应进表（块合并不是只对本条生效的巧合）
        assertNotNull(scan.global.resolveVariable("COM641_AC_カードIDX_特殊効果", null), "同文件的标量 CONST")
        assertNotNull(scan.global.resolveVariable("COM641_AC_カード初期定数_実装状態", null), "ERH:550 起的另一个行连续块")
        assertEquals(emptyList(), scan.warnings.map { "${it.line + 1} ${it.message}" }, "真 ERH 不该触发预处理降级")
    }
}
