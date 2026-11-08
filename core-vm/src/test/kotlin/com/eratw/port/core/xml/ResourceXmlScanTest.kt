package com.eratw.port.core.xml

import com.eratw.port.boot.CorpusProfile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 按根两列（拆 bomskip 波遗留的最后 6 枚 `assumeTrue`，2026-11-06）：两列都是**具体定值**，
 * 对不上就红，不是「两根都放过」。新增语料版本时 [CorpusProfile] 的穷尽 `when` 会编译期逼补。
 *
 * 读数出处（本路两根实读，语料只读）：
 * - 4.980 `eratw-画蛇添足版eratw4.980`：`resources` 下 93 个精灵表 xml，扫描链上 `i`（单图）
 *   与 `a`（动画）两种根形态都出现过；
 * - 4.981 `eraTW4.981-261017-EAGLOV3`：`resources` 下 0 个 xml —— 精灵表资源模型整批改成了
 *   webp + csv，在位 xml 只剩 `plugins` 下 4 个与 `lang/emuera.zhs.xml`（与 XmlCorpusFacts
 *   KDoc 同账）。「resources 目录还在、xml 一个没有」是语料级资源模型变化，不是移植侧装载缺陷：
 *   XmlParser / XmlPath 在 4.981 的 4 个 plugins xml 上由 RealCorpusXmlTest 全绿。
 */
private data class ResourcesColumn(
    /** `resources` 下递归到的 xml 文件数。 */
    val xmlCount: Int,
    /** 四步链上应覆盖到的根元素名集合（无扫描对象时为空集）。 */
    val rootKinds: Set<String>,
    /** 本列的版本说明（进断言消息）。 */
    val note: String,
)

private fun resourcesColumn(profile: CorpusProfile): ResourcesColumn = when (profile) {
    CorpusProfile.V4_980 -> ResourcesColumn(
        xmlCount = 93,
        rootKinds = setOf("i", "a"),
        note = "4.980 resources 下 93 个精灵表 xml，i / a 两种形态都该出现（本路实读）",
    )
    CorpusProfile.V4_981 -> ResourcesColumn(
        xmlCount = 0,
        rootKinds = emptySet(),
        note = "4.981 精灵表资源模型改成 webp + csv，resources 下 0 个 xml（本路实读）",
    )
}

/**
 * 真实语料 `resources` 下每个 `.xml` 的整链扫描 —— 复刻 `RM_リソース情報初期化`
 * （`リソース管理/ResourceManager.ERB:46-54`）对一个资源 XML 做的四步：
 *
 * 1. `LOADTEXT(路径)` 读文本（这里直接按 UTF-8 读，BOM 由 [XmlParser] 自己吞）；
 * 2. `XML_DOCUMENT` → [XmlParser.parse]；
 * 3. `XML_GET_BYNAME` 先按 `/sprites` 下的通配（一个星号）计数，再逐项用谓词
 *    `[J+1]` 取该子元素的 **OuterXml**（style=3）；
 * 4. 再把取到的 OuterXml 当新文档解析，用「斜杠 + 通配」取根元素名（style=4）。
 *
 * 为什么钉这条：2026-10-31 有 8 份真机报告在 `RM_リソース情報初期化` 抛
 * `XML_GET函数:""解析错误:缺少根元素（位置 0）`，全部停在标题页 0%。报告里的
 * `行49` 是**0 起行号**（同批 `THROW 判定地点…` 报 390 而文件里在 391 行，
 * `RAND:RESULT` 报 6087 而在 6088 行），所以现场是第 50 行的
 * `XML_DOCUMENT RM_XML_TEMP, LOADTEXT(RESULTS:I)` —— 即 `LOADTEXT` 交回了空串。
 *
 * 本用例守的是**另一半**：语料自己（4.980 为 resources 下 93 个 XML）在桌面侧能不能
 * 走完这条四步链。走不通就是 XPath / 解析器的移植缺陷；走得通就说明真机那条是**读盘侧**
 * 的事（文件是 0 字节的空壳、或读失败），而不是 XML 语义错了 —— 两层的责任不能混。
 *
 * 闸口径（与同族 `xml/XmlBomLoadTest`、`xml/RealCorpusXmlTest`、`xml/XmlFamilyAuditTest`、
 * `core/config/ConfigChainTest` 同一把尺；旧实现的 6 枚 `Assumptions.assumeTrue` 已按下面的
 * 归属逐个拆掉，不再有静默跳过）：
 * 1. `ERATW_GAMEDIR` 未设 ⇒ [XmlCorpusFacts.detect] 抛 [AssertionError] 判红。旧实现还回落到
 *    `ERATW_CORPUS`，而闸配方（AGENTS.md §5）把该变量指到 `ERB` 子树，回落分支取到的
 *    `ERB/resources` 必然不存在 —— 这条回落本身就是假的第二入口，已删；
 * 2. 语料无 `resources` 目录 ⇒ 判红并打印已认到的根目录内容（两根该目录都在位，缺目录不是
 *    已知的语料形态）；
 * 3. `resources` 下无 xml ⇒ **语料版本差异，按根两列**（[resourcesColumn]，4.980 = 93 件 +
 *    i / a 两形态，4.981 = 0 件 + 空形态集）：件数在集合为空时同样断言，对不上即红。
 *
 * 依赖语料：`ERATW_GAMEDIR` 指到 eraTW 语料游戏根（AGENTS.md §5 闸配方）；裸环境判红不跳过。
 */
class ResourceXmlScanTest {

    /**
     * `resources` 下全部 xml（按路径排序）。无 `resources` 目录 ⇒ [AssertionError] 判红
     * 并打印已认到的根目录内容，不退成跳过。
     */
    private fun resourceXmls(facts: XmlCorpusFacts): List<Path> {
        val resources = facts.root.resolve("resources")
        if (!Files.isDirectory(resources)) {
            throw AssertionError(
                "${facts.root}（${facts.profile}）下没有 resources 目录：eraTW 语料根该目录必在位" +
                    "（4.980 = 93 个精灵表 xml，4.981 = webp+csv 资源模型）。已认到的根目录内容 = " +
                    runCatching {
                        Files.list(facts.root).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
                    }.getOrElse { "<列不出：${it.message}>" },
            )
        }
        return Files.walk(resources).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml", true) }
                .sorted()
                .toList()
        }
    }

    @Test
    fun `resources 下每个 xml 都能走完 文档-计数-逐项OuterXml-再解析 四步`() {
        val facts = XmlCorpusFacts.detect()
        val column = resourcesColumn(facts.profile)
        val files = resourceXmls(facts)
        // 按根两列，不是跳过：件数在集合为空时（4.981 列 = 0）同样断言，
        // 旧 assumeTrue(files.isNotEmpty()) 的静默跳过分就此拆掉。
        assertEquals(column.xmlCount, files.size, "resources 下 xml 件数与列不符：${column.note}")

        val failures = ArrayList<String>()
        // `<i>` = 单图资源、`<a>` = 动画资源：脚本第 56 行与第 71 行两条分支各认一种，
        // 所以「根元素名」只允许这两个值 —— 别的名字会让两条分支都不进，
        // 资源被静默丢弃（不报错，只是立绘不见了，比崩更难查）。
        val kinds = LinkedHashSet<String>()
        for (f in files) {
            val rel = facts.root.relativize(f).toString().replace('\\', '/')
            try {
                val text = Files.readString(f, Charsets.UTF_8)
                val doc = XmlParser.parse(text)
                val count = XmlPath.select(doc, "/sprites/*").size
                // 资源 XML 的根不一定都是 sprites；根不是 sprites 时计数为 0 属正常，
                // 但仍要保证「解析-再解析」这条链不炸。
                for (j in 1..count) {
                    val node = XmlPath.select(doc, "/sprites/*[$j]").singleOrNull()
                        ?: throw AssertionError("下标 $j 未命中（count=$count）")
                    val outer = node.outerXml()
                    if (outer.isBlank()) throw AssertionError("下标 $j 的 OuterXml 为空")
                    val again = XmlParser.parse(outer)
                    val name = XmlPath.select(again, "/*").singleOrNull()?.name
                        ?: throw AssertionError("下标 $j 的 OuterXml 再解析后取根元素名无命中：${outer.take(80)}")
                    if (name != "i" && name != "a") {
                        throw AssertionError("下标 $j 的根元素名是 $name（脚本只认 i / a）：${outer.take(80)}")
                    }
                    kinds += name
                }
            } catch (t: Throwable) {
                failures += "$rel -> ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        assertEquals(
            emptyList(), failures,
            "以下资源 XML 在 RM_リソース情報初期化 的同款链路上失败：\n" + failures.joinToString("\n"),
        )
        // 反空转（按根两列）：4.980 列 i / a 两种资源形态都必须出现过，否则这条用例只证明了
        // 「0 个节点也没炸」；4.981 列没有扫描对象，期望即空集，由上面的件数闸兜底。
        assertEquals(column.rootKinds, kinds, "资源形态覆盖与列不符：${column.note}（扫描是否空转？）")
    }

    @Test
    fun `资源 XML 的文件名枚举顺序稳定且不含目录`() {
        val facts = XmlCorpusFacts.detect()
        val column = resourcesColumn(facts.profile)
        val names = resourceXmls(facts)
            .map { facts.root.relativize(it).toString().replace('\\', '/') }
            .sorted()
        // 按根两列（4.981 起 resources 精灵表的资源模型由 XML 改为 webp + csv ⇒ 该列 0 件）：
        // 「无对象可枚举」不再是 assumeTrue 静默跳过，而是件数断言里的一列具体定值。
        // 移植侧 XmlParser / XmlPath 本身在 4.981 plugins 的 4 个 xml 上由 RealCorpusXmlTest
        // 全绿，非资源装载缺陷；4.980 列（93 件）照常执行、不得回退。
        assertEquals(column.xmlCount, names.size, "resources 下 xml 件数与列不符：${column.note}")
        // 排序键是整条相对路径；脚本按名字过滤，但 Android 侧 list() 也是对相对路径排序后
        // 再按文件名过 glob —— 两条口径都必须给出同一批文件名。
        assertEquals(names.sorted(), names, "枚举顺序不是字典序")
        assertTrue(names.all { it.endsWith(".xml") }, "枚举结果混入非 xml：$names")
    }
}
