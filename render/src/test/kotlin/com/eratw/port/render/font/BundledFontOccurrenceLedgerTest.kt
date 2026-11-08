package com.eratw.port.render.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 随包字体族的**字数账面**负向闸（EP3 L12 · 挖虫 rec105 #7）。
 *
 * 病根：[BundledFontFamilies.MISAKI] 曾登记 `occurrenceCoverage = 0.6287`、
 * `occurrenceMissing = 0` —— 前者是**种类**覆盖（5077/8076）抄进了**字数**栏，
 * 后者是「没测过」写成了「一次都没缺」。设置页显示的正是 `occurrenceCoverage`
 * （`app/.../SettingsActivity.kt:1806-1810`），于是美咲在屏上被报成 62.87%，
 * 而它的真字数覆盖是 95.66731%（缺 4,152,314 次）—— 两个口径会给出**相反**的结论，
 * 这正是本仓选族文档反复强调的那条（类文档「候选是怎么筛出来的」节 +
 * `tools/fontsubset/build_font_pack.py` 的「两个覆盖率口径」节）。
 *
 * 下面三条判式都**不测字体**，只测这张表自不自洽：账面值来自 python 侧的
 * `load_corpus_freq` × cmap 实测，本文件钉的是「填了就必须同源、不许留空当 0」。
 */
class BundledFontOccurrenceLedgerTest {

    /**
     * 语料总出现次数（分母）：`render/src/main/resources/fonts/font-pack-manifest.txt`
     * 每一族 `occurrence-coverage` 行括号里的第二个数（如 sarasa `108463733/108464789`）。
     * 2026-11-05 在 4.981 出货语料（`ERATW_GAMEDIR/ERB`）上用 `load_corpus_freq` 重扫得
     * 108,464,789 次 / 7,953 种，与 manifest 逐字一致；自洽判式仍留 1e-4 的余量，
     * 因为登记表只存 5~7 位小数，反推缺字数必有舍入误差。
     */
    private val corpusTotal: Long = 108_464_789L

    /** 自洽余量：登记表只存 5~7 位小数的覆盖率，反推缺字数必然有舍入误差。 */
    private val tolerance: Long = (corpusTotal * 1e-4).toLong() // ≈ 9,584 次

    @Test
    fun `字数覆盖不满的族缺字数读数不得为 0`() {
        for (family in BundledFontFamilies.ALL) {
            assertTrue(
                family.occurrenceCoverage >= 1.0 || family.occurrenceMissing > 0,
                "${family.family} 的字数覆盖 ${family.occurrenceCoverage} 明显不足 1.0，" +
                    "缺字数却登记成 ${family.occurrenceMissing} —— 「没测过」不许写成「不缺字」",
            )
        }
    }

    @Test
    fun `缺字数与字数覆盖率必须同源于同一次测量`() {
        for (family in BundledFontFamilies.ALL) {
            val implied = ((1.0 - family.occurrenceCoverage) * corpusTotal).toLong()
            val delta = kotlin.math.abs(implied - family.occurrenceMissing.toLong())
            assertTrue(
                delta <= tolerance,
                "${family.family}: 字数覆盖 ${family.occurrenceCoverage} 按分母 $corpusTotal 推得缺 " +
                    "$implied 次，账面却写 ${family.occurrenceMissing} 次（实差 $delta > $tolerance）" +
                    "—— 两个数不是同一次测出来的；美咲旧登记在这里的差是 3500 万次量级",
            )
        }
    }

    /**
     * 反「把种类比抄进字数栏」：两个口径必然各测一次。
     * 现役八族的最小间距是 Unifont 的 0.59 个百分点（种类 99.41% vs 字数 99.99954%），
     * 判式取 1e-3（0.1 个百分点）—— 若哪天某个族真的两口径贴到 0.1pp 以内，
     * 那要改的是这条判式的余量并把实测数写进 KDoc，不是把账面抹平成相等。
     */
    @Test
    fun `字数覆盖不许与种类覆盖逐字相同`() {
        for (family in BundledFontFamilies.ALL) {
            assertTrue(
                kotlin.math.abs(family.occurrenceCoverage - family.coverage) > 1e-3,
                "${family.family} 的字数覆盖（${family.occurrenceCoverage}）与种类覆盖" +
                    "（${family.coverage}）几乎相同 —— 这是把 4977/7956 这类种类比抄进 " +
                    "occurrenceCoverage 的形态，两个口径必须分别实测",
            )
        }
    }

    /** 美咲那一条的实测账面本身（2026-11-05，源 = 随包 `fonts/misaki-gothic.ttf`）。 */
    @Test
    fun `美咲的字数账面是实测值而不是种类比`() {
        val misaki = BundledFontFamilies.MISAKI
        assertEquals(4977, misaki.coverageHit, "种类覆盖实测 4977/7956（4.981 语料闭包）")
        assertEquals(0.6255656, misaki.coverage, 1e-6, "种类覆盖 = 62.56%，它不是字数覆盖")
        assertEquals(0.9503072, misaki.occurrenceCoverage, 1e-7, "字数覆盖实测 95.031%")
        assertEquals(5_389_921, misaki.occurrenceMissing, "缺字次数实测 5,389,921 / 108,464,789 次")
        // 排序事实：美咲的字数覆盖低于同为观感档的 Saitamaar（95.300%），
        // 但仍远高于它自己那个 62.56% 的种类数 —— 屏上报哪个数必须说清楚。
        assertTrue(
            misaki.occurrenceCoverage > misaki.coverage &&
                misaki.occurrenceCoverage < BundledFontFamilies.SAITAMAAR.occurrenceCoverage,
            "美咲的字数覆盖应落在 (种类覆盖, Saitamaar 字数覆盖) 区间内",
        )
    }
}
