package com.eratw.port.boot

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.ConsoleBeginHost
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.core.proc.RootedBuiltinHost
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
import com.eratw.port.core.data.csv.UserKeywordTables

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * OPTION 补丁页面（@OPTION_DLC / @DLCOPTIONPRINT / @Reline）真语料取证探针。
 *
 * 语料依赖用例：`ERATW_CORPUS` 指向 ERB 子树时才生效，缺失即静默跳过（AGENTS §5 口径）。
 * 切片集 = 补丁页链路的 6 个文件（OPTIONDLC / OPTIONPRINT / DLC.ERH / Misc / 两份 COLORMESSAGE），
 * 其余定义仍由全量符号表落空体桩（RealCorpusCompile.sliceFilter 口径）。
 *
 * 产物：`build/option-patch-probe.md` —— 每条 DisplayLine 的行号 / isLineEnd / isLogicalLine /
 * 文本，供人工比对「翻页重绘后是否有残留行 / 按钮列是否对齐」。
 */
class OptionPatchPageProbeTest {

    @Test
    fun probeOptionDlcPageRedraw() {
        val corpus = RealCorpus.load() ?: return
        val gameRoot = corpus.root.parent ?: corpus.root
        val bootOverrides = loadConfigOverrides(gameRoot)
        val saveInBinary = SystemVariableBootstrap.systemSaveInBinaryFrom(bootOverrides)
        val bootParserConfig = RealCorpusCompile.parserConfigFromOverrides(bootOverrides)
        // 全量切片：Pt01 的 `#DIM C_List, VARSIZE("DLC_Toggle")` 需要 ERH 声明已注册的
        // 折叠上下文（生产 erbcc 全量装载可折），切片装置的单条 DimParser 折不了。
        val model = RealCorpusCompile.compile(
            corpus,
            fileLimit = null,
            systemSaveInBinary = saveInBinary,
            parserConfig = bootParserConfig,
        )
        val program = model.program ?: return

        val console = LineBufferConsoleSink(
            // 真机口径：sink.clientWidth 与画布列数一致（CanvasConsoleView.applyPageFit 反推），
            // 默认取 136（ScreenLayout.DEFAULT_COLUMNS）；ERATW_PROBE_WIDTH 可覆盖。
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
        SystemVariableBootstrap.install(
            store,
            model.globalDimDecls.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
            model.dimParseConfig.copy(systemSaveInBinary = saveInBinary),
        )
        for ((fk, spec) in model.userVarDecls) {
            runCatching { store.declareUserVar(spec, fk) }
        }
        if (effective.bool(ConfigKey.UseERD)) UserKeywordTables.install(store, gameRoot)
        val saveRoot = Path.of("build", "option-patch-probe-save").toAbsolutePath().normalize()
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
            model.table,
            VmConfig(maxStepsPerRun = 300_000),
            consoleSink = console,
            semConfig = SemConfig(printCPerLine = 5, saveDataNos = effective.saveDataNos),
            systemOps = FileSystemOps(store, RootedSaveFileIo(saveRoot, saveRooted.fileOps), saveConfig),
            builtinHost = host,
            randSeed = 20261103L,
            beginHost = beginHost,
            inputSink = inputSink,
        )
        rt.installLabelTable(object : ProcessRuntime.LabelTable {
            override fun labelPc(functionKey: String, label: String): Int? =
                model.labelPcs[functionKey.uppercase()]?.get(label.uppercase())
        })
        rt.installDataSections(ProcessRuntime.DataSectionSource { index -> model.dataSections[program.statement(index)] })

        // UI 代理：第一个 INT 等待喂 1002（下一页），其后一律喂 0（返回）——
        // 覆盖「翻页重绘」与「退出清行」两路 RELINE(5)；全喂 1002 会无限翻页。
        val feeder = Thread({
            var fedNext = false
            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (inputSink.isAwaiting && inputSink.pendingCount == 0) {
                        val req = inputSink.awaiting
                        val text = when {
                            req == null || inputSink.awaitingAnyKey -> ""
                            req.type == com.eratw.port.core.host.InputRequest.InputType.INT ->
                                if (!fedNext) {
                                    fedNext = true
                                    "1002"
                                } else "0"
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
        }, "option-probe-feeder").apply { isDaemon = true }
        feeder.start()

        var error: Throwable? = null
        try {
            rt.callFunction("OPTION_DLC")
        } catch (t: Throwable) {
            error = t
        } finally {
            feeder.interrupt()
            feeder.join(5_000)
        }

        val lines = console.snapshot()
        Files.createDirectories(Path.of("build"))
        Files.writeString(Path.of("build", "option-patch-snapshot.tsv"), serializeSnapshot(lines))
        val report = buildString {
            appendLine("# OPTION 补丁页面探针")
            appendLine()
            appendLine("- 语料: $corpus")
            appendLine("- 收口: ${error?.let { "异常 ${it::class.simpleName}: ${it.message}\n${it.stackTrace.take(12).joinToString("\\n")}" } ?: "正常返回"}")
            appendLine("- 行数: ${lines.size}")
            appendLine()
            appendLine("```text")
            lines.forEachIndexed { i, l ->
                val text = l.segments.joinToString("") { it.text }
                appendLine(
                    "%4d | end=%-5s log=%-5s | %s".format(
                        i,
                        l.isLineEnd,
                        l.isLogicalLine,
                        text.replace("\t", "\\t"),
                    ),
                )
            }
            appendLine("```")
        }
        Files.writeString(Path.of("build", "option-patch-probe.md"), report)
        error?.let { throw it }
    }

    /**
     * 快照序列化（render 侧 OptionPatchRenderShapeTest 的输入，跨模块文件中转 ——
     * :core-vm 不得反向依赖 :render，架构单向约束下用 TSV 交接）。
     * 格式：行块 = `DL <isLineEnd> <isLogicalLine> <alignment> <noBreak>` 开头，
     * 其后每段一条 `SEG <kind> <htmlOption> <text>`，按钮一条 `BTN <startCol> <endCol> <value>`，
     * `END` 收行。text 中反斜杠/制表/换行转义。
     */
    private fun serializeSnapshot(lines: List<LineBufferConsoleSink.DisplayLine>): String = buildString {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
        for (l in lines) {
            appendLine("DL ${l.isLineEnd} ${l.isLogicalLine} ${l.alignment} ${l.noBreak}")
            for (s in l.segments) {
                appendLine("SEG ${s.kind} ${s.htmlOption} ${esc(s.text)}")
            }
            for (b in l.buttons) {
                appendLine("BTN ${b.startCol} ${b.endCol} ${esc(b.value.toString())}")
            }
            appendLine("END")
        }
    }
}