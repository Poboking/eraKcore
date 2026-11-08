package com.eratw.port.boot

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.ConsoleBeginHost
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.SidecarTables
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.proc.loadConfigOverrides
import com.eratw.port.core.save.EmSaveSets
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveWiring
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.vars.withPureStaticDimFolders
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * OPTION 补丁页面探针（erbc 产物档 = 真机同链）。
 *
 * 输入：`erbcc compileCorpus` 的产物目录（`program.erbc` + `program.meta`；
 * 真机 0.2.39(15) 同一款编译器、同一条装载链）。与 [OptionPatchPageProbeTest]
 * （RealCorpusCompile 切片档）互补：那边便于改切片，这边**与真机逐字同链**
 * （ProgramLoader.loadMapped + BootMetadata + FunctionTable(metadata.functions)）。
 *
 * 产物：`build/option-patch-erbc-probe.md`，与切片档同格式（每条 DisplayLine 的行形状），
 * 另写 `build/option-patch-snapshot.tsv` 供 render 侧 OptionPatchRenderShapeTest 消费。
 *
 * 环境变量：
 * - `ERATW_ERBC_4981`：产物目录；缺省 = `tools/erbcc/build/erbc-4981/erbc.v1`。
 * - `ERATW_PROBE_WIDTH`：控制台宽（半角格）；缺省 136（ScreenLayout.DEFAULT_COLUMNS）。
 * - `ERATW_CORPUS`：语料 ERB 目录（CSV/config 读取用）。
 */
class OptionPatchErbcProbeTest {

    @Test
    fun probeOptionDlcPageRedrawOnErbc() {
        val erbcDir = Path.of(
            System.getenv("ERATW_ERBC_4981")?.takeIf { it.isNotBlank() }
                ?: "../tools/erbcc/build/erbc-4981/erbc.v1",
        ).toAbsolutePath().normalize()
        val erbcFile = erbcDir.resolve("program.erbc")
        val metaFile = erbcDir.resolve("program.meta")
        if (!Files.isRegularFile(erbcFile) || !Files.isRegularFile(metaFile)) {
            System.err.println("[option-probe] 产物缺失：$erbcDir（先跑 :tools:erbcc:compileCorpus -Pout=build/erbc-4981）")
            return
        }
        val corpus = RealCorpus.load() ?: return
        val gameRoot = corpus.root.parent ?: corpus.root

        val program = ProgramLoader.loadMapped(erbcFile)
        val metadata = BootMetadata.read(metaFile)

        val bootOverrides = loadConfigOverrides(gameRoot)
        val saveInBinary = SystemVariableBootstrap.systemSaveInBinaryFrom(bootOverrides)
        val bootParserConfig = RealCorpusCompile.parserConfigFromOverrides(bootOverrides)

        val console = LineBufferConsoleSink(
            clientWidth = System.getenv("ERATW_PROBE_WIDTH")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                ?: 136,
            printCWidth = 25,
            maxLines = 900,
        )
        val inputSink = QueueInputSink()
        val beginHost = ConsoleBeginHost(inputSink, console)
        val host = ConsoleBuiltinHost(gameRoot, console)
        val configStore = EmueraConfigParser.loadChainFromDisk(gameRoot).store
        val effective = EffectiveConfig.of(configStore)
        val gameData = GameDataLoader.load(gameRoot.resolve("CSV"))
        val store = VariableStore(
            gameData?.registry ?: VariableRegistry(),
            VarConfig(systemSaveInBinary = saveInBinary),
        )
        gameData?.let { data ->
            for (t in data.templates) store.registerTemplate(t)
            store.loadItemPrice(data.itemPrices)
            store.reloadStrNames()
        }
        if (gameData != null && store.template(0L) != null) store.addCharacter(0L)
        else repeat(4) { store.characters.addBare() }
        // 4b) VARSIZE 折叠快照并入用户全局（GameSession 装配的同款口径）：
        //     `#DIM C_List, VARSIZE("DLC_Toggle")` 一类声明依赖先注册的用户全局可见。
        val foldKeywordTables: Map<String, Map<String, Int>> =
            (if (effective.bool(ConfigKey.UseERD)) {
                val systemNames = gameData?.registry?.all()
                    ?.mapTo(HashSet()) { it.name.uppercase() } ?: emptySet()
                runCatching { UserKeywordTables.foldSnapshot(gameRoot, systemNames) }
                    .getOrDefault(emptyMap())
            } else {
                emptyMap()
            }) + (gameData?.registry?.systemKeywordTables() ?: emptyMap())
        val foldVarSizes = SystemVariableBootstrap.mergedVarSizes(
            base = gameData?.let { com.eratw.port.core.vars.DimVarSizes.ofRegistry(it.registry) }
                ?: com.eratw.port.core.vars.DimVarSizes.systemSnapshot(null),
            declarations = metadata.globalDims.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
            config = metadata.dimParseConfig.copy(systemSaveInBinary = saveInBinary),
            keywordTables = foldKeywordTables,
            consoleWidth = console.clientWidth,
        )
        SystemVariableBootstrap.install(
            store,
            metadata.globalDims.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
            metadata.dimParseConfig
                .copy(systemSaveInBinary = saveInBinary)
                .withPureStaticDimFolders(
                    varSizes = foldVarSizes,
                    keywordTables = foldKeywordTables,
                    consoleWidth = console.clientWidth,
                ),
        )
        for (p in metadata.privateDims) {
            runCatching { store.declareUserVar(p.spec, p.functionKey) }
        }
        if (effective.bool(ConfigKey.UseERD)) UserKeywordTables.install(store, gameRoot)
        val saveRoot = Path.of("build", "option-patch-erbc-save").toAbsolutePath().normalize()
        runCatching { saveRoot.toFile().deleteRecursively() }
        Files.createDirectories(saveRoot)
        val saveRooted = RootedBuiltinHost(saveRoot)
        val saveConfig = SaveConfig(
            systemSaveInBinary = effective.bool(ConfigKey.SystemSaveInBinary),
            zipSaveData = effective.bool(ConfigKey.ZipSaveData),
            useSaveFolder = effective.bool(ConfigKey.UseSaveFolder),
        )
        SaveWiring.install(
            store,
            saveConfig,
            effective.saveDataNos,
            EmSaveSets.parse(gameRoot.resolve("CSV")),
        )
        val rt = ProcessRuntime(
            program,
            store,
            com.eratw.port.core.proc.FunctionTable(metadata.functions),
            VmConfig(maxStepsPerRun = 300_000),
            consoleSink = console,
            semConfig = SemConfig(printCPerLine = 5, saveDataNos = effective.saveDataNos),
            systemOps = FileSystemOps(store, RootedSaveFileIo(saveRoot, saveRooted.fileOps), saveConfig),
            builtinHost = host,
            randSeed = 20261103L,
            beginHost = beginHost,
            inputSink = inputSink,
        )
        val sidecar = SidecarTables(metadata.labels, metadata.dataSections)
        rt.installLabelTable(object : ProcessRuntime.LabelTable {
            override fun labelPc(functionKey: String, label: String): Int? =
                sidecar.labelPc(functionKey.uppercase(), label.uppercase())
        })
        rt.installDataSections(ProcessRuntime.DataSectionSource { index -> sidecar.groupsOf(index) })
        if (!program.flatStatementsEnabled) {
            rt.installDataSectionStatementIndex(sidecar.dataSectionStatementIndex(program))
        }

        // UI 代理：按脚本喂值（真机操作序：点 [5] 看详情 → 翻页 → 返回），
        // 每次喂值前抓一帧快照（行文本 + LINECOUNT），存进 waitForSnapshots 供报告。
        val waitForSnapshots = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val script = java.util.ArrayDeque<Long>().apply { addLast(1002L); addLast(1002L); addLast(0L) }
        val feeder = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (inputSink.isAwaiting && inputSink.pendingCount == 0) {
                        val req = inputSink.awaiting
                        val next = script.pollFirst()
                        val snap = console.snapshot()
                        val full = snap.joinToString(" ⏎ ") { l ->
                            l.segments.joinToString("") { it.text }.trim().take(70)
                        }
                        // 右栏候选快照（MainActivity.refreshShell → LeanOptionFilter → ShellUi.chips）：
                        // 逐条记 label/value/printsOwnNumber/ordinal，看补丁页往右栏塞了什么。
                        val opts = console.currentOptions()
                        val optText = opts.take(40).joinToString(" | ") { o ->
                            "v=${o.value} label='${o.label.take(24)}' own=${o.printsOwnNumber} ord=${o.ordinal}"
                        }
                        waitForSnapshots.add(
                            "INPUT@喂值=$next LINECOUNT=${console.lineCount} 总行=${snap.size}\n"
                                + "  整屏=[$full]\n"
                                + "  右栏候选 ${opts.size} 条：$optText",
                        )
                        val text = when {
                            next == null -> "0"
                            req == null || inputSink.awaitingAnyKey -> ""
                            req.type == com.eratw.port.core.host.InputRequest.InputType.INT -> next.toString()
                            else -> "0"
                        }
                        inputSink.submit(text)
                    }
                    Thread.sleep(5L)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    return@Thread
                }
            }
        }, "option-erbc-probe-feeder").apply { isDaemon = true }
        feeder.start()

        var error: Throwable? = null
        try {
            // 真机从 OPTION 菜单进入时屏上已有菜单行（LINECOUNT≠0）——RELINE(5,1) 存的
            // 记录值非 0，翻页/返回的 CLEARLINE 链才走「清行」分支。垫 10 行复现该路径。
            repeat(10) {
                console.print("菜单垫行$it")
                console.newLine()
            }
            rt.callFunction("OPTION_DLC")
        } catch (t: Throwable) {
            error = t
        } finally {
            feeder.interrupt()
            feeder.join(5_000)
        }

        val lines = console.snapshot()
        Files.createDirectories(Path.of("build"))
        // 交接文件名按本类 KDoc 与 render 侧 OptionPatchRenderShapeTest 的读取口径（两处写全名）：
        // 快照落 option-patch-snapshot.tsv（render 只认这个名），完整报告仍落 option-patch-erbc-probe.md。
        Files.writeString(Path.of("build", "option-patch-snapshot.tsv"), serializeSnapshot(lines))
        val report = buildString {
            appendLine("# OPTION 补丁页面探针（erbc 真机同链）")
            appendLine()
            appendLine("- 产物: $erbcDir")
            appendLine("- 语料: $corpus")
            appendLine("- 收口: ${error?.let { "异常 ${it::class.simpleName}: ${it.message}\n${it.stackTrace.take(12).joinToString("\\n")}" } ?: "正常返回"}")
        appendLine("- 行数: ${lines.size}")
        appendLine()
        appendLine("## 各 INPUT 等待点的快照水位")
        appendLine()
        waitForSnapshots.forEach { appendLine("- $it") }
        appendLine()
            appendLine("```text")
            lines.forEachIndexed { i, l ->
                val text = l.segments.joinToString("") { it.text }
                appendLine(
                    "%4d | end=%-5s log=%-5s | %s".format(i, l.isLineEnd, l.isLogicalLine, text.replace("\t", "\\t")),
                )
            }
            appendLine("```")
        }
        Files.writeString(Path.of("build", "option-patch-erbc-probe.md"), report)
        error?.let { throw it }
    }

    /** 快照序列化（格式与 OptionPatchPageProbeTest.serializeSnapshot 一致）。 */
    private fun serializeSnapshot(lines: List<LineBufferConsoleSink.DisplayLine>): String = buildString {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
        for (l in lines) {
            appendLine("DL ${l.isLineEnd} ${l.isLogicalLine} ${l.alignment} ${l.noBreak}")
            for (s in l.segments) {
                appendLine("SEG ${s.kind} ${s.htmlOption} ${esc(s.text)}")
            }
            for (btn in l.buttons) {
                appendLine("BTN ${btn.startCol} ${btn.endCol} ${esc(btn.value.toString())}")
            }
            appendLine("END")
        }
    }
}