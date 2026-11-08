package com.eratw.port.boot

import java.nio.file.Files
import java.nio.file.Path

/**
 * 语料版本档（corpusroot 波收口装置，2026-11-05）。
 *
 * 背景：本机有两份游戏根（4.980 = `eratw-画蛇添足版eratw4.980`、4.981 =
 * `eraTW4.981-261017-EAGLOV3`）。「真实语料审计族」的断言在 2026-11-01 随语料切换重落成了
 * 4.981 一列，而 §5 闸配方仍把语料根指到 4.980 ⇒ 没有任何一组环境变量能让核心模块全绿。
 * 本装置把「取哪一列期望读数」接到根发现上：从语料目录就近读 DIM.ERH 的
 * `#DIM CONST NOW_VERSION` 值判根，再取对应列。
 *
 * 红线口径（波契约 corpusroot §2，路线①根自适应）：
 * 1. 每一列期望读数仍是**具体定值**（按根二选一），不是「两根都放过」的宽松断言；
 * 2. 未登记的语料版本（NOW_VERSION 可读但不在册）⇒ **直接判红**（AssertionError），
 *    不许退成 skip——「语料被换/被改」必须让闸报红；
 * 3. 判根失败（就近找不到 DIM.ERH）同样判红：环境变量既然显式指了根，根不完整就是口径事件。
 *
 * 读数出处（2026-11-05 本路实测）：
 * - 版本锚：两根 `ERB/DIM.ERH:2`，4.980=20260326、4.981=20261017；
 * - .als 数 / 可装载槽：4.980=19/18、4.981=23/20（AliasLoaderTest 两根实跑 + 目录清点）；
 * - ITEMNAME/ITEM 尺寸：4.980=1000/1000、4.981=1200/1200（CSV/VariableSize.csv:17/:74 两根对照）；
 * - 名字表简体化 5 处：Abl:0 / Exp:0 / Talent:0 / Mark:8 / Cflag:1（两根 CSV 逐格对照，
 *   其余本类断言过的格子两根同值）；
 * - ERD 快照表：4.980 六张 / 4.981 十四张（UserKeywordTablesTest 两根实跑读数）；
 * - smoke 清单与切片：4.980=3,971 文件 / 110,066 切片、4.981=4,258 文件 / 117,055 切片
 *   （`:core-vm:corpusSmoke` 两根单跑统计行）；
 * - `emuera.config` 行数 / 匹配不上条目表的行数：4.980=97/1、4.981=103/7
 *   （configroot 波 2026-11-05 两根单跑三层链逐行打印；差额 6 行正是 4.981 独有的
 *   `SKIASHARP`×3 / `RENDERING BACKEND` / `PLUGIN AVAILABLE WARNING` /
 *   `DISABLE BEFORE_ERROR/THROW EVENTS` 六条新键行，故两根**落条目**的键数同为 96）；
 * - 尾随空格标签行号 / 未注册新键行 / MAXLOG：4.980=86/空/900、4.981=92/6 条/1500
 *   （cfgolden 波 2026-11-06 两根逐行实读 + 规范化行尾后 `diff` 对照。两侧其余行逐字相同，
 *   只有 MAXLOG 与 FPS 两个值不同 —— FPS 4.980=5 恰是注册默认、4.981=3）。
 */
enum class CorpusProfile(
    val nowVersion: Long,
    /** corpusSmoke FULL 清单定值与切片下限（本路实测，见类 KDoc）。 */
    val smokeFileCount: Int,
    val smokeSliceFloor: Int,
    /** 真实语料审计族读数（4.980 | 4.981 两列）。 */
    val alsPhysical: Int,
    val alsLoadableSlots: Int,
    val itemNameSize: Int,
    val itemSize: Int,
    val ablName0: String,
    val expName0: String,
    val talentName0: String,
    val markName8: String,
    val cflagName1: String,
    val erdSnapshotKeys: List<String>,

    /**
     * 语料 `emuera.config` 的行数（ReadLine 口径，两根实测 97 / 103）。
     * configroot 波 2026-11-05 补列：本类 KDoc 早就把这笔读数登记成「按根取列」的内容，
     * 但装载链那一族用例仍把它写死成 4.981 一列 —— 见 `core/config/ConfigChainTest`。
     */
    val emueraConfigLineCount: Int,

    /**
     * 同一份 `emuera.config` 里**匹配不上条目表**的行数（`ConfigLineStatus.UNMATCHED_KEY`）。
     * 4.980 = 1（只有尾随空格标签那行），4.981 = 7（同一行 + 6 条 configArray 之外的新键行）。
     * 出处：两根实跑三层链打印逐行记录，行内容见 `ConfigChainTest` 的 `when (profile)` 分支。
     */
    val emueraConfigUnmatchedKeyCount: Int,

    /**
     * 同一份 `emuera.config` 里尾随空格标签那一行（`CLIPBOARD- ANYKEY WAIT TRIGGER :NO`）的行号。
     * 4.980 = 86、4.981 = 92（差 6 正是 4.981 独有的未注册新键行数）。
     * 出处：两根 `emuera.config` 逐行实读（cfgolden 波 2026-11-06）；该行在 ConfigData.cs:140
     * 注册时标签自带尾随空格、读取侧 `array[0].Trim()`（:539）削掉 ⇒ 永远不命中的官方缺陷行。
     */
    val emueraConfigTrailingSpaceLineNo: Int,

    /**
     * 同一份 `emuera.config` 里**条目表未注册**、因而匹配不上的引擎键盘行，按文件顺序的整行原文。
     * 4.980 = 空（该版压根没有这些键）；4.981 = 6 条，位于 4.981 emuera.config:68-71、:77-78：
     * `SKIASHARP`×3 / `RENDERING BACKEND` / `PLUGIN AVAILABLE WARNING` /
     * `DISABLE BEFORE_ERROR/THROW EVENTS`。
     * 出处：两根实读 + 两个文件规范化行尾后的 `diff`（cfgolden 波 2026-11-06），
     * 两侧其余行逐字相同（只有 :18 MAXLOG 与 :28 FPS 两个值不同）。
     */
    val emueraConfigUnregisteredKeyLines: List<String>,

    /** 语料 `emuera.config` 的 `MAX HISTORY LOG LINES` 值（两根同在第 18 行）：4.980 = 900、4.981 = 1500。 */
    val emueraConfigMaxLog: Int,
) {
    V4_980(
        nowVersion = 20260326L,
        smokeFileCount = 3_971,
        smokeSliceFloor = 110_066,
        alsPhysical = 19,
        alsLoadableSlots = 18,
        itemNameSize = 1_000,
        itemSize = 1_000,
        ablName0 = "Ｃ感覚",
        expName0 = "Ｃ経験",
        talentName0 = "処女",
        markName8 = "成長",
        cflagName1 = "既成事実",
        erdSnapshotKeys = listOf("COLUMNDIV@2", "EI_BASE", "EI_FLAG", "M_CFLAG", "M_FLAG", "M_TFLAG"),
        emueraConfigLineCount = 97,
        emueraConfigUnmatchedKeyCount = 1,
        emueraConfigTrailingSpaceLineNo = 86,
        emueraConfigUnregisteredKeyLines = emptyList(),
        emueraConfigMaxLog = 900,
    ),
    V4_981(
        nowVersion = 20261017L,
        smokeFileCount = 4_258,
        smokeSliceFloor = 117_055,
        alsPhysical = 23,
        alsLoadableSlots = 20,
        itemNameSize = 1_200,
        itemSize = 1_200,
        ablName0 = "Ｃ感觉",
        expName0 = "Ｃ经验",
        talentName0 = "处女",
        markName8 = "成长",
        cflagName1 = "既成事实",
        erdSnapshotKeys = listOf(
            "BUFF", "DISH", "DLC_TOGGLE@1", "EI_BASE", "EI_FLAG", "M_CFLAG", "M_FLAG",
            "M_SPELL@1", "M_SPELL@2", "M_TALENT", "M_TFLAG", "SEMEN_MATRIX@2",
            "SETTING_CFLAG", "SETTING_FLAG",
        ),
        emueraConfigLineCount = 103,
        emueraConfigUnmatchedKeyCount = 7,
        emueraConfigTrailingSpaceLineNo = 92,
        emueraConfigUnregisteredKeyLines = listOf(
            "SKIASHARP IMAGE QUALITY:High",
            "SKIASHARP FONT HINTING:None",
            "SKIASHARP FONT EDGING:SubpixelAntiAlias",
            "RENDERING BACKEND:Auto",
            "PLUGIN AVAILABLE WARNING:YES",
            "DISABLE BEFORE_ERROR/THROW EVENTS:YES",
        ),
        emueraConfigMaxLog = 1_500,
    );

    companion object {
        /** NOW_VERSION 行是纯 ASCII：按 ISO_8859_1 逐字节解码取数，不依赖语料真实编码（防解码面本身成为判据）。 */
        private val NOW_VERSION_LINE = Regex("#DIM\\s+CONST\\s+NOW_VERSION\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

        fun fromNowVersion(v: Long): CorpusProfile? = entries.firstOrNull { it.nowVersion == v }

        /**
         * 从装置给定的目录（CSV / ERB / 游戏根皆可，向上至多 4 段）就近找 DIM.ERH 判根。
         * 判不了 / 不在册 ⇒ AssertionError 判红（红线 2、3）。
         */
        fun detect(start: Path?): CorpusProfile {
            val dir = start?.toAbsolutePath()?.normalize()
                ?: throw AssertionError("拿不到语料根（环境变量未设或路径为空）：无法判定语料版本")
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
                                "未登记的语料版本 NOW_VERSION=$v（$cand）：先给 CorpusProfile 新增一列并按波契约重落，再放闸",
                            )
                    }
                }
                cur = d.parent
            }
            throw AssertionError("$dir 就近（含上溯 4 段）找不到 DIM.ERH：语料根不完整，判红不降级")
        }
    }
}
