package com.eratw.port.e2e

import com.eratw.port.boot.CorpusProfile
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 语句参数子树规范化去重（`ArgInterner`）的**端到端等价**钉死。
 *
 * 判据：同一份金标管线产物（`fixtures/golden`，15 节 / 610 行）在
 * 「去重前」「去重后」两次执行里，控制台原文**逐字节相同**；且去重后的原文与
 * PC 基准 `pc-golden-root.txt` 逐行一致（不是只跟自己去重前比 —— 那样两边一起错也绿）。
 *
 * 为什么单独一个装置而不是改 `E2EGoldenTest`：那条闸跑的是「未去重」路径，
 * 本条是它的差分对照；两条都绿才说明去重没有改变任何可观测行为。
 *
 * 与 `PerfGoldenInternedEquivalenceTest` 共用 [PcGoldenColumn] 的**按根两列**：
 * `pc-golden-root.txt` 钉的是 4.981 读数，§5 闸的 `ERATW_GAMEDIR` 是 4.980 ——
 * 三行语料派生行按根二选一（定值，不是放宽），其余 607 行仍逐字严格。
 */
class InternedArgsE2ETest {

    private val fixtureDir: Path =
        sequenceOf(File("fixtures/golden"), File("../fixtures/golden"))
            .first { it.isDirectory }.toPath().toAbsolutePath().normalize()

    private fun runAll(model: GoldenModel): String {
        val console = GoldenPipeline.newConsole()
        val rt = GoldenPipeline.newRuntime(model, console, stepCap = 20_000_000)
        rt.execute()
        return console.capturedText()
    }

    @Test
    fun `去重前后全量执行输出逐字节相同`() {
        GoldenPipeline.requireGameCsvDir()
        val model = GoldenPipeline.build(fixtureDir)
        assertEquals(0, model.compileFailures.size, "切片编译失败: ${model.compileFailures}")

        val before = runAll(model)
        val statementsBefore = model.program.statementCount
        val stats = model.program.internStatementArgs()
        val after = runAll(model)

        assertEquals(before, after, "去重前后控制台原文必须逐字节相同（长度 ${before.length} vs ${after.length}）")
        assertEquals(statementsBefore, model.program.statementCount, "语句池下标/条目数不得变化")
        assertTrue(stats.merged > 0, "金标产物里也应存在可合并的同形结点（实测 ${stats.merged}）")

        // 去重后的输出直接对 PC 基准（610 行）—— 反向证明不是「两边一起错」。
        // 基准按语料根两列取（见 [PcGoldenColumn]）：本机 §5 闸喂 4.980 根，而
        // pc-golden-root.txt 钉的是 4.981 读数，三行语料派生行按根替换后仍逐行严格。
        val baseline = fixtureDir.resolve("baseline").resolve(GoldenNormalizer.PC_BASELINE)
        assertTrue(baseline.toFile().exists(), "PC 基准缺失: $baseline")
        val expected = PcGoldenColumn.forCurrentRoot(GoldenNormalizer.readBaseline(baseline.toFile()))
        val actual = GoldenNormalizer.normalize(after)
        val diff = GoldenNormalizer.diff(expected, actual)
        assertTrue(
            diff.identical,
            "去重后输出与 PC 基准不一致（${PcGoldenColumn.describeCurrentRoot()}；基准 ${expected.size} 行 / " +
                "实际 ${actual.size} 行），首差 ${diff.firstMismatch}:\n${diff.preview}",
        )
    }
}

/**
 * PC 基准 `pc-golden-root.txt` 的**按根两列**（语料根口径差；corpusroot 波路线①，不是放宽断言）。
 *
 * 为什么需要：基准文件在 `db76d203`（2026-11-01，u981-golden，组长决策「(a) 接受并白名单登记」）
 * 随 4.981 语料重落，其中三行是**运行期从语料读入**的内容，不由引擎算法决定：
 *
 * - 第 348 行 `Ｃ感觉LV5 技巧LV2 `（归一化口径；物理第 427 行）——`PRINT_ABL` 打印
 *   `CSV/Abl.csv` 第 0 项名：4.980 文件第 2 行 `0,Ｃ感覚`、4.981 同位置 `0,Ｃ感觉`
 *   （语句实现 [com.eratw.port.core.sem.PrintCharaData.characterDataString]，名字表经
 *   `store.registry.csvTable("ABL")` 由 [GoldenPipeline.gameCsvDir] 指向的根装载）；
 * - 第 349 行 `[性别]`（物理第 428 行）——`PRINT_TALENT` 打印 `CSV/Talent.csv` 第 2 项：
 *   4.980 `2,性別`、4.981 `2,性别`；
 * - 第 454 行 `ENUMVARENDSWITH=92`（物理第 544 行）——`ENUMVAR*` 的 VarKeys 候选名：
 *   系统 31 个 + 游戏 ERH 顶层 `NAME` 结尾 DIM（4.980 实测 52 名 ⇒ 83；4.981 61 名 ⇒ 92，
 *   九名增量逐个取证见 [GoldenPipeline.buildEnumVarNameTable] KDoc）。
 *
 * 这三行的 4.980 列值在同一张表里**写死**（不靠「两根都放过」）：命中 4.980 根时先核
 * 基准该行仍是 4.981 列原文，再换成 4.980 列原文后逐行对拍 —— 基准被重钉而本表没跟上时
 * 直接判红（消息写明两列不一致），不会被静默吞掉；任何第四行漂移照常红。
 *
 * 为什么不像 `E2EGoldenTest.全量执行与基准对拍` 那样按根显式跳过：本装置是去重差分对照与
 * M6 正确性闸，跳掉就同时失去「610 行硬对拍」这层覆盖；两列定值比跳过强，且 §5 闸的
 * `ERATW_GAMEDIR` 必须指 4.980（语料闸 3,971 文件口径），golden 侧无法靠换根取绿。
 *
 * 口径变更登记：BOARD「报红·L1 面待修」那一行仍写着「golden 四类必须把 ERATW_GAMEDIR
 * 指到 4.981」，与本两列表并存 —— 两者语义等价（都只认 4.981 的严格列），差别在于
 * 本表让 4.980 闸下也对拍到底而不是跳过。基准文件本身的归属仍是组长。
 */
internal object PcGoldenColumn {

    /** 一行两列：基准行号（1 起，[GoldenNormalizer.normalize] 口径）+ 4.981 / 4.980 两版原文。 */
    private class Row(val lineNo: Int, val from4981: String, val from4980: String)

    /**
     * 两列定值表。4.980 列取自语料实文件（[GoldenPipeline] 装载同一份 CSV / ERH），
     * 行号取 [GoldenNormalizer] 归一化后的下标（RNG- 段整段剔除后与物理行号不同：
     * 427/428/544 物理 → 348/349/454 归一化）。
     */
    private val ROWS = listOf(
        Row(348, "Ｃ感觉LV5 技巧LV2 ", "Ｃ感覚LV5 技巧LV2 "),
        Row(349, "[性别]", "[性別]"),
        Row(454, "ENUMVARENDSWITH=92", "ENUMVARENDSWITH=83"),
    )

    /** 当前语料根档；判不出根（CI 无游戏数据 / 未登记版本）⇒ null，按基准原文逐行严格。 */
    private fun profileOrNull(): CorpusProfile? =
        runCatching { CorpusProfile.detect(GoldenPipeline.gameCsvDir()) }.getOrNull()

    /**
     * 取当前语料根对应的期望列。4.981 / 无根 ⇒ 原样返回基准（逐行严格）；4.980 ⇒
     * 三行换成 4.980 列值（换前核对基准仍是 4.981 列，不一致即抛 AssertionError）。
     */
    fun forCurrentRoot(expected: List<String>): List<String> {
        if (profileOrNull() != CorpusProfile.V4_980) return expected
        val out = expected.toMutableList()
        for (row in ROWS) {
            val i = row.lineNo - 1
            if (i !in out.indices) {
                throw AssertionError(
                    "基准只有 ${expected.size} 行，两列表第 ${row.lineNo} 行越界：基准结构已变，本表须重导（不许静默放过）",
                )
            }
            if (out[i] != row.from4981) {
                throw AssertionError(
                    "基准第 ${row.lineNo} 行已不是 4.981 列原文（实际「${out[i]}」，本表记「${row.from4981}」）：" +
                        "基准被重钉而两列表没跟上，先重导列值再放闸",
                )
            }
            out[i] = row.from4980
        }
        return out
    }

    /** 断言消息里的列口径自述（出红时一眼看出跑的是哪一列）。 */
    fun describeCurrentRoot(): String = when (profileOrNull()) {
        CorpusProfile.V4_980 -> "语料根 4.980 列：348/349/454 三行按 4.980 语料定值，其余逐行严格"
        CorpusProfile.V4_981 -> "语料根 4.981 列：基准原文，610 行逐行严格"
        null -> "未判出语料根（无游戏数据 / 未登记版本）：基准原文逐行严格"
    }
}
