package com.eratw.port.core.loader

import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.frontend.ErbPreprocessor
import com.eratw.port.core.patch.PatchArtifacts
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.GameSession
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 口上包验收门（波次 wave/mod-import-acceptance）：玩家指定验收目标
 * `ERATW_KOJO_PKG`（蕾米莉亚色情特化 v1.3：58 个 ERB / 12.8 MB / 全 UTF-8 / 与 base 零撞名 /
 * 包内同名多定义 77 组）必须能被 C5 等价导入链**一次编成一张补丁**。
 *
 * 装置口径：
 * - **整包形态 = 判据**：全部源按文件名序一次 compile（C5 A 层口径、全或无 I5），入口取名
 *   与 app 侧 `ModImportController.firstFunctionNameOf` 同一条预处理状态机（认 `[SKIPSTART]`）。
 *   编不过即红，消息原样上屏——第一抛就是本波要清的闸。
 * - **单文件形态 = 归因**：每源独立 compile，只打印不断言（包内跨文件互调在单文件形态必然
 *   失败，那是要读懂的噪声，不是缺陷）。
 * - 上下文装载 / 符号表灌法与 [RealCorpusPatchSmokeTest] 同源（复用其 companion 门与容忍模式）。
 */
// realArtifacts 家标记（realarttag 路 2026-11-06）：产物门控钉（ERATW_KOJO_PKG 是孤儿 env ⇒ 本闸内恒自证 skip，见 companion 门文案）；判定逻辑一字不动。
@Tag("realArtifacts")
class KojoPackageAcceptanceSmokeTest {

    companion object {
        /** 原样 env（trim 后非空才算「设过」）：区分「未设置」与「给了但不是目录」两个不同病灶。 */
        private val pkgRaw: String? = System.getenv("ERATW_KOJO_PKG")?.trim()?.takeIf { it.isNotEmpty() }

        val pkgRoot: Path? = pkgRaw?.let { p -> Path.of(p).takeIf { Files.isDirectory(it) } }

        /**
         * 口上包门的消息文案。孤儿 env（2026-11-06 记）：`ERATW_KOJO_PKG` 在全仓 build 脚本里
         * 没有值源（env.sh 不导、真产物档 wireRealArtifacts 也不接）⇒ 不显式导出时本类两条
         * 用例在任何口径都停在前置假设上 —— 接线缺口进「需组长接线」，不在用例里硬造值。
         */
        private fun pkgGateMessage(): String =
            if (pkgRaw == null)
                "ERATW_KOJO_PKG 未设置（本仓 build 脚本无该变量的值源：env.sh 不导、wireRealArtifacts 也不接，" +
                    "零接线；验收包「蕾米莉亚色情特化 v1.3，58 ERB / 12.8 MB」按 M_KOJO_K16_5_IMAGE.ERB 特征本机也未找到）" +
                    "⇒ 口上包验收门判不了 —— 需组长接线（给包目录）"
            else
                "ERATW_KOJO_PKG=$pkgRaw 不是目录：跳过"
    }

    /**
     * 第一个**可用**函数头：整文件被 `[SKIPSTART]` 包住的源不提供入口（本包
     * M_KOJO_K16_5_IMAGE.ERB 排最前且全文件被跳过），与 app 侧同名判据同口径
     * （[ErbPreprocessor]，spec: ErbLoader.cs:25-181）。
     */
    private fun firstUsableFunctionName(text: String): String? {
        val pp = ErbPreprocessor(debugMode = false, macroNames = emptySet(), file = "kojo-accept")
        for ((i, raw) in text.lineSequence().withIndex()) {
            val content = raw.trimStart('\uFEFF', ' ', '\t').trimEnd()
            if (content.isEmpty()) continue
            if (pp.process(content, i + 1)) continue
            if (pp.disabled) continue
            if (!content.startsWith("@")) continue
            val body = content.substring(1)
            var end = 0
            while (end < body.length && body[end] !in " \t(,") end++
            val name = body.substring(0, end).trim()
            if (name.isNotEmpty()) return name
        }
        return null
    }

    @Test
    fun `真实口上包整包编译成一张补丁`() {
        val root = RealCorpusPatchSmokeTest.gameRoot()
        assumeTrue(root != null, "未设置 ERATW_GAMEDIR：跳过")
        val c = RealCorpusPatchSmokeTest.ctx
        assumeTrue(c != null, "未设置 ERATW_ERBC_DIR：跳过")
        val pkg = pkgRoot
        assumeTrue(pkg != null, pkgGateMessage())

        val erbs = Files.list(pkg).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().uppercase().endsWith(".ERB") }
                .map { it }.toList()
        }
        println("[kojo-accept] 包内 ERB 文件数=${erbs.size} 总字节=${erbs.sumOf { Files.size(it) }}")
        println("[kojo-accept] 侧车 const 表 size=${c!!.metadata.dimParseConfig.intConstants.size} 含桜花道的鳥居=${c.metadata.dimParseConfig.intConstants.containsKey("桜花道的鳥居")}")
        println("[kojo-accept] global-dims 条数=${c.metadata.globalDims.size}")
        println("[kojo-accept] 侧车 global-dims 容忍跳过 ${c!!.skippedGlobalDims.size} 条")
        for (s in c.skippedGlobalDims.take(3)) println("[kojo-accept]   skip-dim $s")

        val sources = erbs.sortedWith { a, b ->
            a.fileName.toString().uppercase().compareTo(b.fileName.toString().uppercase())
        }.map { p ->
            PatchCompiler.SourceFile(
                relPath = p.fileName.toString(),
                text = Files.readString(p),
            )
        }
        val entryName: String? = sources.firstNotNullOfOrNull { firstUsableFunctionName(it.text) }
        assumeTrue(entryName != null, "包内无可用函数头：跳过")
        println("[kojo-accept] 整包 entry=$entryName")

        // ---- 判据：整包一次编成一张补丁（C5 A 层口径 = 导入链的并集重编形态）----
        val result = PatchCompiler(c.metadata, c.counts, c.symbolTable, renameDic = c.renameDic)
            .compile(sources, entryName!!)
        // 补丁 Program 是**窗口产物**：functions 只含新增段（base 段由装载期基线供），
        // 所以段函数数直接读 program.functions.size，不减 base 计数。
        val added = result.program.functions.size
        val droppedFiles = result.parseWarnings.filter { it.contains("已跳过解析失败的源") }
        val droppedFuncs = result.parseWarnings.filter { it.contains("已跳过解析失败的函数") }
        println(
            "[kojo-accept] 整包编译成功：补丁段函数 $added 个、告警 ${result.parseWarnings.size} 条" +
                "（作废函数 ${droppedFuncs.size} 个 / 作废文件 ${droppedFiles.size} 个）",
        )
        for (w in (droppedFuncs + droppedFiles)) println("[kojo-accept]   dropped ${w.take(220)}")
        check(added > 0) { "整包编出的补丁段没有任何函数（$added）" }

        // ---- 归因面（不断言）：单文件独立 compile ----
        var okCount = 0
        val failed = ArrayList<Pair<String, String>>()
        for (src in sources) {
            val e0 = firstUsableFunctionName(src.text)
            if (e0 == null) {
                failed.add(src.relPath to "无可用函数头（整文件或前段被 [SKIPSTART] 包住）")
                continue
            }
            try {
                PatchCompiler(c.metadata, c.counts, c.symbolTable, renameDic = c.renameDic)
                    .compile(listOf(src), e0)
                okCount++
            } catch (e: Exception) {
                failed.add(src.relPath to "${e::class.simpleName}: ${e.message?.take(300)}")
            }
        }
        println("[kojo-accept] 单文件归因（包内互调在单文件必失败，仅供参考）：独立成功=$okCount / ${sources.size}")
        for ((f, msg) in failed) println("[kojo-accept] 单文件归因 $f :: $msg")
    }

    /**
     * 判据 2：**装载 + 会话装配**（编译成功只证明能编出窗口；板上记的历史红是
     * 「装载 0/3 全落 I6-同名桶」，即真口上装不进会话）。链路逐字照设备端 C5：
     * 产物头走权威口径 [PatchCompiler.headerOf] → [PatchArtifacts] 落盘 mods →
     * [ModPatchLoader.load]（I3 身份 + 窗口对齐 + 重名闸）→ [GameSession] 带 modsDirectory
     * 装配（侧车 private-dims 与补丁函数表在此并入）。
     *
     * `begin(TITLE)` 仍归 `ERATW_SMOKE_BEGIN=1` 门（真语料整段执行是分钟级、且撞既有
     * 语料执行面边界，与「口上能不能装进来」是两件事）。
     */
    @Test
    fun `真实口上包装载并进会话`(@TempDir dir: Path) {
        val root = RealCorpusPatchSmokeTest.gameRoot()
        assumeTrue(root != null, "未设置 ERATW_GAMEDIR：跳过")
        val c = RealCorpusPatchSmokeTest.ctx
        assumeTrue(c != null, "未设置 ERATW_ERBC_DIR：跳过")
        val pkg = pkgRoot
        assumeTrue(pkg != null, pkgGateMessage())

        val sources = packageSources(pkg!!)
        val entry = sources.firstNotNullOfOrNull { firstUsableFunctionName(it.text) }
        assumeTrue(entry != null, "包内无可用函数头：跳过")
        val result = PatchCompiler(c!!.metadata, c.counts, c.symbolTable, renameDic = c.renameDic)
            .compile(sources, entry!!)

        val mods = Files.createDirectories(dir.resolve("mods"))
        val header = PatchCompiler.headerOf(result, c.counts, c.sha256, result.entryFunction)
        Files.write(
            mods.resolve("kojo-imports" + ModPatchLoader.PATCH_EXTENSION),
            PatchArtifacts.of(header, ErbcWriter.encode(result.program)).write(),
        )

        val load = ModPatchLoader.load(mods, c.base, c.sha256)
        assertFalse(load.rolledBack, "真口上包装载不得回退：${load.failures}")
        assertEquals(1, load.loadedPatches.size, "应装载恰好一张补丁")
        val patch = load.loadedPatches.single()
        val members = patch.functionKeys.count { '#' in it }
        println(
            "[kojo-load] 装载成功：补丁段函数 ${patch.functionCount} 个（事件成员 $members 个）、" +
                "私有 #DIM ${load.privateDims.size} 条、装载告警 ${load.warnings.size} 条",
        )
        for (w in load.warnings.take(5)) println("[kojo-load]   warn ${w.take(200)}")
        check(patch.functionCount > 0) { "装载后补丁段没有函数" }

        // 会话侧走设备端同一条路：只给 modsDirectory，装载在 GameSession 构造期内发生
        // （I5 全或无、私有 #DIM 并入装配第 5 步、补丁函数表进事件派发面）。
        val session = GameSession(
            gameRoot = root!!,
            program = c.base,
            metadata = c.metadata,
            consoleWidth = 199,
            randSeed = 20260226L,
            maxStepsPerRun = 2_000_000L,
            maxRunMillis = 30_000L,
            modsDirectory = mods,
            baseProgramSha256 = c.sha256,
            stageMark = { mark -> println("[kojo-load] 会话段 $mark") },
        )
        assertEquals(1, session.loadedPatches.size, "会话内应装载一张补丁（modsDirectory 生效）")
        val metas = session.patchFunctionMetas
        assertEquals(
            patch.functionCount, metas.size,
            "会话登记的补丁函数表条数必须等于装载的函数数（一条不许静默丢）",
        )
        // 本包 1101 个 @ 定义里 0 个是 @EVENTxxx 事件钩子（实测）——它是「被叫的内容包」，
        // 挂钩由同整合集的宿主包按函数名 CALL 进来。所以运行面的判据不是事件成员表，
        // 而是**按名派发能不能落到补丁函数**（事件成员链路另有 PatchCompilerTest /
        // ModPatchLoaderTest 的用例钉）。
        val table = FunctionTable(metas)
        val target = "M_KOJO_色情特化_EVENT_K16_BANQUET"
        val meta = table.nonEvent(target)
        assertNotNull(meta, "口上包的入口函数在会话函数表里按名解析不到（派发会撞「未定义」）")
        println(
            "[kojo-load] GameSession 带真口上装配完成：补丁函数表 ${metas.size} 条" +
                "（事件成员 ${metas.count { it.isEvent }} 条）、私有 #DIM ${load.privateDims.size} 条；" +
                "按名派发取证：$target → index=${meta!!.index} position=${meta.position}",
        )
        assertTrue(
            meta.position.startsWith(ModPatchLoader.PATCH_POSITION_PREFIX),
            "解析到的函数必须挂在补丁位（不是同名 base 函数），实际 position=${meta.position}",
        )

        // 运行面（门 `ERATW_SMOKE_BEGIN=1`，与 RealCorpusPatchSmokeTest 同一条闸）：
        // 装了真口上的会话走一遍 begin(TITLE)。段落闸 2M 步 / 30s 照抄既有口径。
        if (System.getenv("ERATW_SMOKE_BEGIN") == "1") {
            val t0 = System.currentTimeMillis()
            val ok = session.begin(BeginType.TITLE)
            println(
                "[kojo-load] begin(TITLE)=$ok 带真口上（耗时 ${System.currentTimeMillis() - t0} ms）；" +
                    "屏上尾行=" + session.console.tailText(3).joinToString(" ⏎ ").take(200),
            )
        } else {
            println("[kojo-load] begin(TITLE) 未请求（ERATW_SMOKE_BEGIN!=1）：只验到装配面")
        }
    }

    private fun packageSources(pkg: Path): List<PatchCompiler.SourceFile> =
        Files.list(pkg).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().uppercase().endsWith(".ERB") }
                .map { it }.toList()
        }.sortedWith { a, b ->
            a.fileName.toString().uppercase().compareTo(b.fileName.toString().uppercase())
        }.map { p -> PatchCompiler.SourceFile(relPath = p.fileName.toString(), text = Files.readString(p)) }
}
