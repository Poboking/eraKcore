package com.eratw.port.core.lexer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `specs/error-texts.yaml` 的 `used_at` **反向核对闸**（2026-11-05 lane usedat）。
 *
 * ## 为什么要这一道
 * `used_at` 写的是「键在产品侧的抛出点」，但本仓从来没有闸核对过它：全仓 `used_at` 只命中
 * 本表与 [LangText] 的一句注释。于是它成了唯一一个「看着精确、实际必然漂」的字段——收口前
 * 97 条里 70 条带 `文件.kt:行号`（共 100 处行号），实测 **67 处已指不到对应抛点**、其中
 * 20 处指在注释行上；条目级 50 条漂移 / 20 条仍准。旧族 [LangText] 注释里写的
 * 「产品行漂移由 drift 闸的 needle 当场抓红」只覆盖第九节那 33 枚，其余 60 余条无人管。
 *
 * ## 本闸钉什么、不钉什么
 * - **钉**：每条 `used_at` 里「`.kt` 文件 + 「」needle」这一对锚点 —— needle 必须在
 *   那个文件的**非注释行**上逐字命中（文件级 `contains` 是本仓已实测过的假闸，注释里的
 *   引用不算数，口径同 sem/StatementFamilyErrorTextDriftTest）。
 * - **不钉行号**：行号每次重构必漂，钉行号 = 再造一个必然漂的闸。反过来，本闸第 2 个用例
 *   禁止 `used_at` 里再出现 `文件.kt:数字` 这种形态，免得读数悄悄复活。
 *
 * ## 锚点约定（写在 specs 的表头，这里只做机器侧的复述）
 * `used_at: <相对 core 的路径> <函数/符号> 的「<needle>」`，多个抛点用 `+` 串接，
 * **每个「」绑定到它左边最近的那个 `.kt`**。needle 三档取法：已走通道的键写调用式
 * （`LangKey.MissingArg`）、判「不改-同形」的键写屏上原文的静态前缀、尚未接线的键写
 * 将来落点的函数名（`private fun changeable`）。
 * 不含「」的条目 = 本条无可核锚点（无消费点 / 未移植 / 待接线），**跳过但在报告里点名**，
 * 让人一眼看到还有多少条核不了。
 */
class LangKeyUsedAtAnchorTest {

    private val specFile: File by lazy {
        listOf(
            File("../specs/error-texts.yaml"),
            File("specs/error-texts.yaml"),
            File("../../specs/error-texts.yaml"),
        ).firstOrNull { it.isFile }
            ?: error("找不到 specs/error-texts.yaml（工作目录 ${File(".").absolutePath}）")
    }

    private fun coreRoot(): File {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/core"),
            File("../core-vm/src/main/kotlin/com/eratw/port/core"),
            File("../../core-vm/src/main/kotlin/com/eratw/port/core"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 core-vm main 源码根（工作目录 ${File(".").absolutePath}）")
    }

    /** 一条 `used_at` 解析出来的锚点：文件相对路径 + 该文件里必须逐字命中的 needle 清单。 */
    private data class Anchor(val id: String, val rel: String, val needles: List<String>)

    private data class Parsed(
        val entryCount: Int,
        val usedAtCount: Int,
        /** 有「」锚点的条目。 */
        val anchors: List<Anchor>,
        /** used_at 里出现过 `.kt` 但整条没有任何「」的条目（核不了，需点名）。 */
        val noNeedle: List<String>,
        /** needle 之前一个 `.kt` 都没有 = 锚点写法不合规。 */
        val orphanNeedles: List<String>,
        /** 仍带 `文件.kt:数字` 行号读数的条目。 */
        val withLineNumbers: List<String>,
    )

    /** 文件锚与 needle 锚按出现顺序一次性扫出来，保证「needle 绑左边最近的 `.kt`」这一约定。 */
    private val anchorToken = Regex("""([A-Za-z][A-Za-z0-9_/]*\.kt)|「([^」]+)」""")

    /** 禁止复活的行号读数形态。 */
    private val lineNumber = Regex("""\.kt:\d""")

    private val parsed: Parsed by lazy { parse(specFile.readText()) }

    // ------------------------------------------------------------------
    // 一、解析自证：不许「解析器静默失效看着绿」
    // ------------------------------------------------------------------

    @Test
    fun `每条键都有 used_at 且解析条数与表内键数一致`() {
        val p = parsed
        assertTrue(p.entryCount > 0, "specs/error-texts.yaml 解析出 0 条键 —— 解析口径与表格式脱钩了")
        assertEquals(
            p.entryCount,
            p.usedAtCount,
            "`- id:` 条数与 used_at 条数不等 ⇒ 有键漏了 used_at，或有 used_at 没挂上键",
        )
        assertTrue(
            p.anchors.isNotEmpty(),
            "没有任何一条 used_at 带「」锚点 —— 锚点形态被改掉了？",
        )
    }

    @Test
    fun `每个 needle 都必须先有一个 kt 文件锚`() {
        assertTrue(
            parsed.orphanNeedles.isEmpty(),
            "以下条目的「」needle 左边没有 `.kt` 文件锚，闸无法判定它该落在哪个文件：" +
                parsed.orphanNeedles.joinToString(),
        )
    }

    // ------------------------------------------------------------------
    // 二、禁止行号读数复活
    // ------------------------------------------------------------------

    @Test
    fun `used_at 不得再携带易漂的文件行号读数`() {
        assertTrue(
            parsed.withLineNumbers.isEmpty(),
            "used_at 又写回 `文件.kt:行号` 了（这些读数没有闸核对，重构一次就漂）：" +
                parsed.withLineNumbers.joinToString(),
        )
    }

    // ------------------------------------------------------------------
    // 三、反向核对主体
    // ------------------------------------------------------------------

    @Test
    fun `used_at 的每个 needle 都在其绑定的产品文件非注释行逐字命中`() {
        val misses = ArrayList<String>()
        for (a in parsed.anchors) {
            val lines = codeLines(a.rel, allowMissing = false)
            for (needle in a.needles) {
                if (lines.none { it.contains(needle) }) {
                    misses += "${a.id}: 「$needle」 在 ${a.rel} 的非注释行里 0 命中"
                }
            }
        }
        assertTrue(misses.isEmpty(), misses.joinToString("\n"))
    }

    @Test
    fun `used_at 里点名的产品文件必须真实存在`() {
        val absent = parsed.anchors.filter { codeLines(it.rel, allowMissing = true).isEmpty() }
        assertTrue(
            absent.isEmpty(),
            "used_at 指的产品文件不存在，或路径不是相对 core-vm 的 com/eratw/port/core/：" +
                absent.joinToString { "${it.id} -> ${it.rel}" },
        )
    }

    @Test
    fun `打印无锚点清单`() {
        val p = parsed
        val units = p.anchors.sumOf { it.needles.size }
        println(
            "[used_at 锚点普查] 条目 ${p.entryCount} · 有锚条目 ${p.entryCount - p.noNeedle.size} · " +
                "锚点单元(条目×文件) ${p.anchors.size} · 已核 needle $units · 无锚 ${p.noNeedle.size}",
        )
        if (p.noNeedle.isNotEmpty()) {
            println("  无锚条目（无消费点 / 未移植 / 待接线，本闸核不了）：")
            p.noNeedle.forEach { println("    - $it") }
        }
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun parse(text: String): Parsed {
        val entries = ArrayList<String>()
        val values = LinkedHashMap<String, String>()
        var usedAtCount = 0
        var cur: String? = null
        for (line in text.split("\n")) {
            val idm = Regex("^  - id: (\\S+)\\s*$").find(line)
            if (idm != null) {
                cur = idm.groupValues[1]
                entries.add(cur)
                continue
            }
            if (line.startsWith("    used_at:")) {
                usedAtCount++
                if (cur != null) {
                    val v = line.removePrefix("    used_at:").trim()
                    assertTrue(v.isNotEmpty(), "$cur 的 used_at 是空的")
                    values[cur] = v
                    cur = null
                }
            }
        }

        val anchors = ArrayList<Anchor>()
        val noNeedle = ArrayList<String>()
        val orphan = ArrayList<String>()
        val numbered = ArrayList<String>()
        for ((id, v) in values) {
            if (lineNumber.containsMatchIn(v)) numbered.add(id)
            var pending: String? = null
            var seenFile = false
            val byFile = LinkedHashMap<String, MutableList<String>>()
            for (m in anchorToken.findAll(v)) {
                val f = m.groupValues[1]
                if (f.isNotEmpty()) {
                    pending = f
                    seenFile = true
                    byFile.getOrPut(f) { ArrayList() }
                } else {
                    val needle = m.groupValues[2]
                    val key = pending
                    if (key == null) {
                        if (id !in orphan) orphan.add(id)
                    } else {
                        byFile.getOrPut(key) { ArrayList() }.add(needle)
                    }
                }
            }
            if (!seenFile) {
                if (id !in noNeedle) noNeedle.add(id)
                continue
            }
            if (byFile.values.all { it.isEmpty() }) {
                if (id !in noNeedle) noNeedle.add(id)
                continue
            }
            for ((rel, needles) in byFile) {
                if (needles.isNotEmpty()) anchors.add(Anchor(id, rel, needles))
            }
        }
        return Parsed(entries.size, usedAtCount, anchors, noNeedle, orphan, numbered)
    }

    private val cache = HashMap<String, List<String>>()

    /** 非注释行；`allowMissing` 为真时文件缺失返回空表（给「文件必须存在」那个用例用）。 */
    /** 仓根（含 `core-vm/` 与 `tools/` 的那层）；`used_at` 允许写仓根相对的产品路径。 */
    private fun repoRoot(): File? =
        listOf(File(".."), File("."), File("../.."))
            .firstOrNull { File(it, "core-vm/src/main/kotlin").isDirectory }

    /**
     * 解析 `used_at` 里的产品文件：先按 core-vm 的 `com/eratw/port/core/` 相对路径找，
     * 找不到再按**仓根相对路径**找（`tools/erbcc/...` 这类跨模块产品文件走这一档）。
     */
    private fun resolveCodeFile(rel: String): File? =
        File(coreRoot(), rel).takeIf { it.isFile }
            ?: repoRoot()?.resolve(rel)?.takeIf { it.isFile }

    private fun codeLines(rel: String, allowMissing: Boolean): List<String> =
        cache.getOrPut("$rel#$allowMissing") {
            val f = resolveCodeFile(rel)
            if (f == null) {
                assertTrue(allowMissing, "used_at 指的文件不存在（core-vm 内与仓根两处都找过）：$rel")
                return@getOrPut emptyList()
            }
            f.readText().split("\n").filterNot { ln ->
                val t = ln.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
        }
}
