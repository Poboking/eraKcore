package com.eratw.port.core.proc

import com.eratw.port.boot.GameRoot981
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.lexer.LogicalLineReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 簇 3（0.2.39「No native method ModelSet found @ CHAT_ACTION 行34」）的**同族普查闸**
 * —— 起点 = main@e208ced5（worker lane v039）。
 *
 * 上报读数：3 份 / 3 台 / app 0.2.39，ERB 帧链 `CHAT_ACTION ← EVENTCOMEND`。
 * 当时的执行期桩（`register("ModelSet")` / `register("Chat")`，d06d55b2 起）已由 A1
 * （2026-11-06）拆除：本版不内置 .NET 插件 DLL，可达的两处 CALLSHARP 由引擎**装载期裁决**
 * （`prefillSharpLinks`，core/fn/PluginMethods.kt）拒载并统一上屏，不再设桩。app 层不归本路，
 * 本闸不重跑接线，只钉住**下一炸的边界**：真语料里到底还有哪些 CALLSHARP 原生名，以及
 * 「未注册名」的文案口径。
 *
 * 普查口径（文本面与编译面一起钉，缺一面都会漏）：
 * - 文本面：4.981 语料 ERB 子树全量（实测 4,258 个 `.ERB`/`.ERH`）里 `callsharp`
 *   子串（大小写不敏感）只命中 1 个文件、3 处
 *   （`ERB/DLC/AI对话补丁/对话调用.erb:35/:36/:54`）；4.980 同子树 0 处（实测）。
 * - 编译面：三处里 `:54` 的 9 参 `Chat` 整段被 `[SKIPSTART]`/`[SKIPEND]` 包住
 *   （`对话调用.erb:45`–`:56`），预处理期整段不产逻辑行
 *   （spec: ErbLoader.cs:408-411 + EraStreamReader.cs:130；移植侧 `LogicalLineReader` 的
 *   disabled 分支）⇒ 运行期可达的原生名只有 `ModelSet` 与 `Chat`。
 *
 * 报错口径：`CodeEE("No native method $name found")`（移植侧 `StatementExecutor.kt` 的执行期兜底；
 * spec: FunctionIdentifier.cs:1204 —— 原版是装载期 `ParserMediator.Warn(isError = true)` 记行错、
 * 装载完统一上屏（ParserMediator.cs:125-136 / :143-158），执行到该行才抛
 * Process.cs:1017-1019；移植侧的装载期相位由 `prefillSharpLinks` 在会话装配段复刻，
 * 未预填的行才退到首执行裁决）。
 *
 * TODO(verify)：宿主装配段「注册终表 → prefillSharpLinks → drive()」的严格序本闸证不了 ——
 * 本机定向闸不含 AGP 配置，app 侧由 `GameHostSharpWireTest` 的源码哨兵钉；本闸只锁
 * 「语料里不会冒出第三个名字」这条边界。
 */
class CallSharpCorpusCensusTest {

    private val erbRoot: Path? = GameRoot981.erb()

    /** 语料里可达（非 SKIP 区）的原生名全集 —— 多一个即 `prefillSharpLinks` 会多报一句，本闸红。 */
    private val reachableNames = setOf("ModelSet", "Chat")

    private class Census(
        val files: MutableList<String> = ArrayList(),
        val rawHits: MutableList<String> = ArrayList(),
        val enabledNames: LinkedHashSet<String> = LinkedHashSet(),
        /** 成为逻辑行的 CALLSHARP 原文（`0 基行号 内容`）—— SKIP 区的那条不该出现在这里。 */
        val enabledCallSharp: MutableList<String> = ArrayList(),
        val readFailures: MutableList<String> = ArrayList(),
    )

    /** 逐文件走真装载链（含 `[SKIPSTART]` 判定），收集 CALLSHARP 的文本命中与可达原生名。 */
    private fun census(root: Path): Census {
        val out = Census()
        Files.walk(root).use { stream ->
            val paths = stream.filter { Files.isRegularFile(it) }
                .filter {
                    val n = it.fileName.toString().lowercase()
                    n.endsWith(".erb") || n.endsWith(".erh")
                }
                .toList()
            for (path in paths) {
                // 先用单字节视图粗筛（语料字节里 CALLSHARP 是 ASCII，不受字符集判定影响）
                val bytes = Files.readAllBytes(path)
                val latin = String(bytes, Charsets.ISO_8859_1).lowercase()
                if (!latin.contains("callsharp")) continue
                out.files.add(path.toString())
                val lines = LogicalLineReader.readAllLines(bytes)
                for ((i, raw) in lines.withIndex()) {
                    if (raw.lowercase().contains("callsharp")) out.rawHits.add("${path.fileName}:${i + 1}")
                }
                val reader = runCatching { LogicalLineReader(lines, path.fileName.toString()) }
                val enabled = ArrayList<LogicalLine>()
                val r = reader.getOrNull()
                if (r != null) {
                    // 读到文件尾；抛异常记进 readFailures 后结束本文件。
                    // 不写成 runCatching{}.getOrElse{ break }：break/continue 跨内联 lambda
                    // 是实验特性（Kotlin 报 "The feature break continue in inline lambdas"）。
                    while (true) {
                        val next: LogicalLine? = try {
                            r.readLogicalLine()
                        } catch (t: Throwable) {
                            out.readFailures.add("${path.fileName}: ${t.message}")
                            null
                        }
                        if (next == null) break
                        enabled.add(next)
                    }
                    r.fileEnd()
                }
                for (line in enabled) {
                    val body = line.content.trim()
                    if (!body.startsWith("CALLSHARP", ignoreCase = true)) continue
                    out.enabledCallSharp.add("${line.lineNo} $body")
                    out.enabledNames.add(body.substring("CALLSHARP".length).trim().takeWhile { !it.isWhitespace() && it != '(' })
                }
            }
        }
        return out
    }

    @Test
    fun `真语料可达的 CALLSHARP 原生名只有 ModelSet 与 Chat`() {
        val root = erbRoot
        Assumptions.assumeTrue(root != null, "缺 4.981 语料根（ERATW_GAMEDIR / ERATW_CORPUS 都没指到 4.981）：跳过")
        val c = census(root!!)
        assertEquals(
            reachableNames,
            c.enabledNames,
            "语料里出现了新的可达 CALLSHARP 原生名 ${c.enabledNames - reachableNames}：" +
                "新名字由引擎装载期裁决（prefillSharpLinks）拒载并上屏，不要加桩；" +
                "先按 C# 锚核对是否语料侧问题（FunctionIdentifier.cs:1201-1204）",
        )
        // 文本面读数：三处命中全在 AI 对话补丁那一个文件
        assertEquals(1, c.files.size, "命中 CALLSHARP 的文件数：${c.files}")
        assertEquals(3, c.rawHits.size, "文本面命中处数：${c.rawHits}")
        assertTrue(c.rawHits.all { it.startsWith("对话调用.erb:") }, c.rawHits.toString())
        // 编译面读数：三处里只有两处成为逻辑行 —— `[SKIPSTART]`/`[SKIPEND]`
        // （对话调用.erb:45-56）里的 9 参 Chat（物理 :54，0 基 53）整段不产行，
        // spec: ErbLoader.cs:408-411（pPState.Disabled ⇒ continue）+ EraStreamReader.cs:130。
        // 只比名字集是钉不住这一点的：被跳掉的也是 Chat，名字集两边一样，故必须数条数。
        assertEquals(2, c.enabledCallSharp.size, "成为逻辑行的 CALLSHARP 条数：${c.enabledCallSharp}")
        assertTrue(c.enabledCallSharp.none { "评分管理" in it }, "9 参 Chat 变体不该可达：${c.enabledCallSharp}")
        // 上报的抛点定位：`@CHAT_ACTION 行34` = 逻辑行 0 基 34 = 物理 :35 的 ModelSet
        assertTrue(
            c.enabledCallSharp.any { it.startsWith("34 CALLSHARP ModelSet(") },
            "ModelSet 的调用点行号必须还是上报的那一行：${c.enabledCallSharp}",
        )
        assertEquals(emptyList(), c.readFailures, "逐文件走装载链不该有读失败：${c.readFailures}")
    }

    @Test
    fun `已登记的两个原生名有本机方法即通、未注册名照原版文案报错`() {
        val registry = NativeMethodRegistry()
        for (n in reachableNames) registry.register(n) { }
        assertEquals(listOf("CHAT", "MODELSET"), registry.names(), "注册表只应有这两个名字（大写化后排序）")
        for (n in reachableNames) assertNotNull(registry.find(n), "$n 必须命中注册表")
        // 未注册名 = 语句层的报错口径（spec: FunctionIdentifier.cs:1204）
        val miss = assertFailsWith<CodeEE> {
            registry.find("SomeFuturePatchApi") ?: throw CodeEE("No native method SomeFuturePatchApi found")
        }
        assertEquals("No native method SomeFuturePatchApi found", miss.message)
    }
}
