package com.eratw.port.erbcc

import java.nio.file.Files
import java.nio.file.Path

/**
 * erbcc 侧语料版本档（corpusroot 波，2026-11-05；与 core-vm 测试源集的
 * com.eratw.port.boot.CorpusProfile 同源复制 —— 测试源集互不可见，两模块各留一份，
 * 新增根/换列时两处一起改）。
 *
 * 判根：读语料根下 DIM.ERH 的 `#DIM CONST NOW_VERSION` 值（两根实测：
 * 4.980=20260326、4.981=20261017，各在 DIM.ERH:2）。判不出/不在册 ⇒ AssertionError 判红：
 * 语料被换/被改必须让闸报红，不许退成静默 skip。
 *
 * 钉位出处：
 * - 标签基线：specs/loader.md 附录 D.3 + SymbolsTest KDoc 的两根实测
 *   （4.980=110,066/110,042/6 组；4.981=117,055/117,025/7 组；2026-11-05 本路 A/B 实跑复核）；
 * - PRINT_COLORBAR 生效行：两根 魔改内容/PRINT系/UI_Components_&_Tables.ERB 实读（959 / 1010）；
 * - NOW_VERSION 折叠读数：DimErhFamilyConstTest 两根实跑（其余 ERH 常量两根同值）。
 */
enum class ErbccCorpusProfile(
    val nowVersion: Long,
    val labelsTotal: Int,
    val labelsUnique: Int,
    val dupGroups: Int,
    val dupGroupCounts: Map<String, Int>,
    val colorBarLine: Int,
) {
    V4_980(
        nowVersion = 20260326L,
        labelsTotal = 110_066,
        labelsUnique = 110_042,
        dupGroups = 6,
        dupGroupCounts = mapOf(
            "EVENTFIRST" to 2,
            "EVENTLOAD" to 9,
            "EVENTTURNEND" to 2,
            "EVENTCOMEND" to 6,
            "EVENTTRAIN" to 7,
            "EVENTCOM" to 4,
        ),
        colorBarLine = 959,
    ),
    V4_981(
        nowVersion = 20261017L,
        labelsTotal = 117_055,
        labelsUnique = 117_025,
        dupGroups = 7,
        dupGroupCounts = mapOf(
            "EVENTFIRST" to 2,
            "EVENTLOAD" to 8,
            "EVENTTURNEND" to 2,
            "EVENTEND" to 2,
            "EVENTCOMEND" to 10,
            "EVENTTRAIN" to 9,
            "EVENTCOM" to 4,
        ),
        colorBarLine = 1010,
    );

    companion object {
        private val NOW_VERSION_LINE = Regex("#DIM\\s+CONST\\s+NOW_VERSION\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

        fun fromNowVersion(v: Long): ErbccCorpusProfile? = entries.firstOrNull { it.nowVersion == v }

        /** 从语料目录（ERB 或游戏根，向上至多 4 段）就近找 DIM.ERH 判根；判不了即 AssertionError。 */
        fun detect(start: Path?): ErbccCorpusProfile {
            val dir = start?.toAbsolutePath()?.normalize()
                ?: throw AssertionError("拿不到语料根：无法判定语料版本")
            var cur: Path? = dir
            repeat(4) {
                val d = cur ?: return@repeat
                for (cand in listOf(d.resolve("DIM.ERH"), d.resolve("ERB").resolve("DIM.ERH"))) {
                    if (Files.isRegularFile(cand)) {
                        val text = String(Files.readAllBytes(cand), Charsets.ISO_8859_1)
                        val v = NOW_VERSION_LINE.find(text)?.groupValues?.get(1)?.toLongOrNull()
                            ?: throw AssertionError("$cand 里读不到 NOW_VERSION 常量：语料根形态不符 eraTW 规约")
                        return fromNowVersion(v)
                            ?: throw AssertionError(
                                "未登记的语料版本 NOW_VERSION=$v（$cand）：先给 ErbccCorpusProfile 新增一列并按波契约重落，再放闸",
                            )
                    }
                }
                cur = d.parent
            }
            throw AssertionError("$dir 就近（含上溯 4 段）找不到 DIM.ERH：语料根不完整，判红不降级")
        }
    }
}
