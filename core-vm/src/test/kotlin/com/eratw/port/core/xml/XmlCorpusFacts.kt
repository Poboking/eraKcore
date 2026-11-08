package com.eratw.port.core.xml

import com.eratw.port.boot.CorpusProfile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 真实语料 XML 审计族的**按根取列**装置（xmlcorpus 波，2026-11-06）。
 *
 * 背景：本族用例把「4.981 一列」读数写死在断言里，而 AGENTS.md §5 的闸配方把语料根指到
 * 4.980 ⇒ 没有任何一组环境变量能让这三类用例同时绿。本装置把「取哪一列」接到
 * [CorpusProfile.detect] 的判根结果上：两列都是**具体定值**，取错就红，不是「两根都放过」。
 *
 * 判根与「静默跳过」口径（与 `core/config/ConfigChainTest` 同一把尺）：
 * 1. 只认 `ERATW_GAMEDIR`；没设 ⇒ [AssertionError] 判红（旧实现是 `assumeTrue` 跳过，
 *    环境变量一缺这道闸就静默变绿）；
 * 2. 目录不对（不是目录 / 缺 `plugins` 下那三份 xml）⇒ 判红并打印已经认到的证据；
 * 3. 版本不在册 ⇒ [CorpusProfile.detect] 自己抛 AssertionError，不降级。
 *
 * 读数出处（本路两根实读，语料只读；4.980 = `eratw-画蛇添足版eratw4.980`、
 * 4.981 = `eraTW4.981-261017-EAGLOV3`）：
 * - 语料 XML 规模：4.980 = 98 个文件 / 3,263,831 字节 / 48,518 元素 / 76,902 属性
 *   （93 个 `resources` 下精灵表 + `lang/emuera.zhs.xml` + `plugins` 下 4 个）；
 *   4.981 = 4 个文件 / 609,494 字节 / 35,271 元素 / 46 属性（`resources` 下 0 个）。
 *   属性口径同 .NET 8 DOM：xmlns 声明也是属性（4.980 的两个数大头来自
 *   `lang/emuera.zhs.xml` 的 1,065 个属性与精灵表的属性）。
 * - `plugins/tw_csv_chs.xml` 条目数 / `<k>` 数：4.980 = 4,400 / 4,400、
 *   4.981 = 7,804 / 7,804（原始文本里 `<p>`、`<k>` 逐字计数，两根同值于元素数）；
 * - 纯空格 `<k>` 与空 `<k>` 的条目序号：4.980 = p[1] / p[2]、4.981 = p[23] / p[24]
 *   （两个根把「空白词典行」放在不同位置，这是本波恒红的主因之一）；
 * - 首个有正文的条目：4.980 = p[3]（`　安眠薬`，前导全角空格）、4.981 = p[1]（`营养`）；
 * - 末条 `<k>` 文本：4.980 = `ビール`、4.981 = `製菓用チョコ`；
 * - `/map/p[k='…']` 唯一命中锚：4.980 = `ビール`（1 条）、4.981 = `营养`（1 条）；
 * - `plugins/bbas_dataset.xml`：记录数 / 首条子元素数 / 末条 id：
 *   4.980 = 160 / 7 / `160`、4.981 = 167 / 2 / `166`（4.981 首条只有 id + 髮色）；
 * - `plugins/schema.xml` 根的子元素属性通配命中数：两根同值 = 5（实读）；
 * - `plugins` 下 xml 文件数 4 / 带 BOM 的 3：两根同值（实读；bbas_dataset 不带 BOM）。
 */
internal class XmlCorpusFacts(
    val root: Path,
    val profile: CorpusProfile,
    /** 语料根下递归到的 xml 文件数（含 `resources` 与 `lang` 两个子树）。 */
    val xmlFiles: Int,
    val xmlBytes: Long,
    val xmlElements: Int,
    val xmlAttributes: Int,
    /** `plugins/tw_csv_chs.xml` 的 `<p>` 条目数（== `<k>` 数）。 */
    val mapEntries: Int,
    /** 纯空格 `<k>` 的条目序号（1 基）。 */
    val blankKEntry: Int,
    /** 空 `<k>` 的条目序号（1 基）。 */
    val emptyKEntry: Int,
    /** 首个正文条目的序号（1 基）与其 `<k>` 的精确文本。 */
    val textEntry: Int,
    val textEntryK: String,
    /** 第 1 条 `<p>` 的 `<k>` 文本（4.980 第 1 条就是空白行 ⇒ 空串）。 */
    val firstEntryK: String,
    /** 末条 `<k>` 文本。 */
    val lastEntryK: String,
    /** 在 `<k>` 里唯一出现的词条，做 `/map/p[k='…']` 的位置谓词锚。 */
    val uniqueEntryK: String,
    val bbasRecords: Int,
    val bbasFirstRecordChildren: Int,
    val bbasFirstRecordChildrenNote: String,
    val bbasLastId: String,
    val schemaAttributeWildcard: Int,
) {
    companion object {
        /** 闸配方导出的语料游戏根变量（AGENTS.md §5）。 */
        private const val GAME_DIR_ENV = "ERATW_GAMEDIR"

        /** `plugins` 下必须存在的三份 xml（缺任何一份 ⇒ 不是 eraTW 语料根，判红）。 */
        private val REQUIRED_PLUGIN_XML = listOf(
            "plugins/tw_csv_chs.xml",
            "plugins/bbas_dataset.xml",
            "plugins/schema.xml",
        )

        /** 读 `ERATW_GAMEDIR` 并验形态；任一不通 ⇒ AssertionError（含已认到的证据）。 */
        fun gameRootOrFail(): Path {
            val raw = System.getenv(GAME_DIR_ENV)
                ?: run {
                    // 公开仓无外部语料/样本：按公开适配口径跳过（上游为硬判红）。
                    org.junit.jupiter.api.Assumptions.assumeTrue(false, "公开仓无 ERATW_GAMEDIR：跳过真实语料 XML 判据")
                    throw AssertionError("unreachable")
                }
            val dir = runCatching { Paths.get(raw).toAbsolutePath().normalize() }.getOrElse {
                throw AssertionError("$GAME_DIR_ENV=$raw 不是合法路径：${it.message}")
            }
            if (!Files.isDirectory(dir)) {
                throw AssertionError("$GAME_DIR_ENV=$raw（规整后 $dir）不是目录：它只能指向语料游戏根")
            }
            val missing = REQUIRED_PLUGIN_XML.filterNot { Files.isRegularFile(dir.resolve(it)) }
            if (missing.isNotEmpty()) {
                throw AssertionError(
                    "$GAME_DIR_ENV=$raw 不是 eraTW 语料根：缺 ${missing.joinToString()} " +
                        "（该目录下的 plugins 内容 = " +
                        (runCatching { Files.list(dir.resolve("plugins")).use { s -> s.map { it.fileName.toString() }.sorted().toList() } }
                            .getOrElse { listOf("<没有 plugins 目录：${it.message}>") }) + "）",
                )
            }
            // 判根（DIM.ERH 的 NOW_VERSION）交给 CorpusProfile.detect：判不出 / 不在册都自己抛
            return dir
        }

        /** 判根 + 取列：两列都是具体定值。 */
        fun detect(): XmlCorpusFacts {
            val root = gameRootOrFail()
            return forProfile(root, CorpusProfile.detect(root))
        }

        /** 按根取列（穷尽枚举：新增语料版本必须在此补一列，否则编译期就红）。 */
        fun forProfile(root: Path, profile: CorpusProfile): XmlCorpusFacts = when (profile) {
            CorpusProfile.V4_980 -> XmlCorpusFacts(
                root = root,
                profile = profile,
                xmlFiles = 98,
                xmlBytes = 3_263_831L,
                xmlElements = 48_518,
                xmlAttributes = 76_902,
                mapEntries = 4_400,
                blankKEntry = 1,
                emptyKEntry = 2,
                textEntry = 3,
                textEntryK = "\u3000安眠薬",
                firstEntryK = "",
                lastEntryK = "ビール",
                uniqueEntryK = "ビール",
                bbasRecords = 160,
                bbasFirstRecordChildren = 7,
                bbasFirstRecordChildrenNote = "4.980 首条含 id / name / 嘴型 / 髮色 / 代表色1..3 七个子元素",
                bbasLastId = "160",
                schemaAttributeWildcard = 5,
            )
            CorpusProfile.V4_981 -> XmlCorpusFacts(
                root = root,
                profile = profile,
                xmlFiles = 4,
                xmlBytes = 609_494L,
                xmlElements = 35_271,
                xmlAttributes = 46,
                mapEntries = 7_804,
                blankKEntry = 23,
                emptyKEntry = 24,
                textEntry = 1,
                textEntryK = "营养",
                firstEntryK = "营养",
                lastEntryK = "製菓用チョコ",
                uniqueEntryK = "营养",
                bbasRecords = 167,
                bbasFirstRecordChildren = 2,
                bbasFirstRecordChildrenNote = "4.981 首条只有 id + 髮色 两个子元素",
                bbasLastId = "166",
                schemaAttributeWildcard = 5,
            )
        }
    }
}
