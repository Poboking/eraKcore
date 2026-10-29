package com.eratw.port.core.perf

import com.eratw.port.boot.BootModel
import com.eratw.port.boot.BootStats
import com.eratw.port.boot.GlobalDimDecl
import com.eratw.port.boot.RealCorpus
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.data.csv.GameData
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.NoSaveSystemOps
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.vars.DimFoldSnapshot
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.e2e.LineConsoleSink
import com.eratw.port.perf.Census
import com.eratw.port.perf.CensusRow
import com.eratw.port.perf.HeapMeter
import com.eratw.port.perf.PoolCensus
import com.eratw.port.perf.SizeModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

/**
 * `.erbc` 全量装载后的逐池常驻复测（只测量；产品零改动）。
 *
 * ## 与既有两个装置的关系
 *
 * - `:core-vm:perfBaseline` 的 S2b（[PoolDecomp]）测的是**源码直编**路径：
 *   `RealCorpusCompile` 在 6g 堆里编出 `Program`，再走同一套分解。
 * - 本测试测的是**装机后的真实路径**：`ProgramLoader.loadMapped`（按段映射解码，不把整份
 *   产物读进 `ByteArray`）+ `BootMetadata.read`（侧车），再做同一套分解。
 *   两边因此可比：同一个 [ResidentWalker] 身份去重、同一个 [SizeModel] 布局模型、
 *   同一套桶归属顺序、同一个 [HeapMeter] 读数口径（连打 N 轮 `System.gc()` 取最小值）。
 *
 * 复用 [PoolDecomp.measure] 而不是另写一份分解：表怎么算只有一处实现，
 * 复测与 S2b 的差异只来自装载路径（raw 何时释放、侧车 vs 编译期 `BootModel`）。
 *
 * ## 装置侧输入
 *
 * - 产物：`erbc.v1/program.erbc` + `erbc.v1/program.meta`。查找顺序：
 *   `ERATW_ERBC_DIR` / `ERATW_ERBC` 环境变量 → `<cwd>/build/erbc-out/erbc.v1` →
 *   `<cwd>/build/erbc.v1` → `<cwd>/../tools/erbcc/build/erbc-out/erbc.v1`。
 *   找不到时用子 JVM（`-Xmx6g`）跑一次 `com.eratw.port.erbcc.MainKt compile`：
 *   全量编译峰值约 3.4 GiB，超过 test worker 的 3g，只能另起进程；
 *   设 `ERATW_PERF_BUILD_ERBC=0` 可禁用这条兜底（缺产物即跳过）。
 * - 语料根：`ERATW_GAMEDIR`，缺省取 `ERATW_CORPUS` 的上一级（CSV/resources/sav 所在层）。
 * - 报告：`build/perf-decomp.md`（测试 JVM 的 cwd = `core-vm/` 模块目录）。
 *
 * ## 读数口径的两条硬约定
 *
 * 1. **互斥可加**：先到者拥有共享实例（语句 `args` 先于 `exprs` 池遍历），所以各行能相加；
 *    这是「内存花在哪」的分解，不是「删掉某池能省多少」（后者会重复计共享部分）。
 * 2. **扣除失效索引**：[PoolCensus] 的 §2 表仍按 48 B/条计入 `longIndex` / `stringIndex` /
 *    `exprIndex` 三张构建期去重索引，而 `Program.init -> ConstantPool.seal()` 之后它们已置
 *    null（[PoolDecompResult.staleIndexBytes]）。本测试把这三行定位出来、
 *    断言其和等于 `(constantCount + stringCount + exprCount) × 48`，并在
 *    「§2 口径」合计里显式扣掉——本表（互斥口径）本来就不含它们。
 *
 * ## 断言的是不变量，不是某个 MiB
 *
 * 读数随 JVM/GC/堆上限浮动，故只锁：已归属落在合理量级、残差不为大负数、
 * seal 后三张索引实测为 null、失效索引三行之和与公式一致、遍历后复读没有明显污染。
 * 具体 MiB 全在报告与控制台里。
 */
class PoolDecompAuditTest {

    private val sizer: SizeModel = SizeModel(SizeModel.detectRefWidth())

    @Test
    fun `erbc 全量装载后逐池复测并落 perf-decomp`() {
        val reportPath = Path.of("build", "perf-decomp.md").toAbsolutePath().normalize()
        val artifacts = resolveArtifacts(cwd())
        assumeTrue(
            artifacts != null,
            "未找到 erbc.v1 产物，且无法自建（缺 ERATW_CORPUS 或子 JVM 编译失败）。" +
                "先跑 :tools:erbcc:compileCorpus，或设 ERATW_ERBC_DIR 指向 erbc.v1 目录。",
        )
        val art = artifacts!!

        val stamp = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val programBytes = Files.size(art.program)
        val metaBytes = Files.size(art.meta)
        val programSha = sha256(art.program)

        // ---- S0：装置基线（test worker，已加载 JUnit/Kotlin 运行时）----
        val (s0min, s0max, s0all) = HeapMeter.settle()
        println("[perf-decomp] S0 test-worker 常驻 ${mibOf(s0min)}（${HeapMeter.rounds} 轮最小值，展布 ${mibOf(s0max - s0min)}）")

        // ---- L1：.erbc 全量装载（只解 Program，不建运行期）----
        val probe = ProgramLoader.probeMapped(art.program)
        val tLoad = System.nanoTime()
        val program = ProgramLoader.loadMapped(art.program)
        val loadMs = msSince(tLoad)
        println(
            "[perf-decomp] .erbc 装载 ${loadMs}ms：${program.instructionCount} 指令 / " +
                "${program.statementCount} 语句 / ${program.functions.size} 函数",
        )
        // 原文账单必须在建 ProcessRuntime 之前采（构造期 releaseStatementRaws）
        val rawBill = PoolDecomp.rawBill(program)
        val loadResident = HeapMeter.settled()

        // ---- L1m：侧车（BootMetadata）----
        val tMeta = System.nanoTime()
        val metadata = BootMetadata.read(art.meta)
        val metaMs = msSince(tMeta)
        val metaResident = HeapMeter.settled()
        println("[perf-decomp] 侧车装载 ${metaMs}ms：函数 ${metadata.functions.size} / 标签组 ${metadata.labels.size} / 数据节 ${metadata.dataSections.size}")

        // ---- S3：运行期装配（口径 = perfBaseline 的 S3 段）----
        val tBoot = System.nanoTime()
        val gameRoot = gameRoot()
        val gameData: GameData? = gameRoot?.let { GameDataLoader.load(it.resolve("CSV")) }
        val store = VariableStore(gameData?.registry ?: VariableRegistry())
        if (gameData != null) {
            for (t in gameData.templates) store.registerTemplate(t)
            store.loadItemPrice(gameData.itemPrices)
            store.reloadStrNames()
        }
        if (gameData != null && store.template(0L) != null) store.addCharacter(0L)
        else repeat(4) { store.characters.addBare() }

        val dimInstall = SystemVariableBootstrap.install(
            store,
            metadata.globalDims.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
            metadata.dimParseConfig,
        )
        var privateOk = 0
        val privateFail = ArrayList<String>()
        for (p in metadata.privateDims) {
            runCatching { store.declareUserVar(p.spec, p.functionKey) }
                .onSuccess { privateOk++ }
                .onFailure { privateFail += "${p.functionKey}/${p.spec.name}: ${it.message}" }
        }

        val table = FunctionTable(metadata.functions)
        val console = LineConsoleSink(barWidth = 199, printCLength = 25)
        val runtime = ProcessRuntime(
            program,
            store,
            table,
            VmConfig(maxStepsPerRun = 5_000_000L),
            consoleSink = console,
            semConfig = SemConfig(printCPerLine = 5),
            systemOps = NoSaveSystemOps,
            randSeed = 20260226L,
        )
        runtime.installLabelTable(
            object : ProcessRuntime.LabelTable {
                override fun labelPc(functionKey: String, label: String): Int? =
                    metadata.labelPc(functionKey, label)
            },
        )
        // 数据节：侧车按语句池下标存；本装置仍保留实例快照（bootModel 要按实例口径量常驻），
        // 故注入点把下标换回实例再查（T2-64 波次 C：数据节键 = 语句池下标）。
        val byStatement = IdentityHashMap<Statement, List<List<Expr>>>()
        for ((index, groups) in metadata.dataSections) {
            if (index in 0 until program.statementCount) byStatement[program.statement(index)] = groups
        }
        runtime.installDataSections(ProcessRuntime.DataSectionSource { index -> byStatement[program.statement(index)] })
        val bootMs = msSince(tBoot)
        val s3 = HeapMeter.settled()
        println(
            "[perf-decomp] S3 运行期 ${mibOf(s3)}（装载 ${bootMs}ms；#DIM 全局 ${dimInstall.declared} / " +
                "失败 ${dimInstall.failures.size}，私有 $privateOk / 失败 ${privateFail.size}）",
        )

        // ---- 互斥口径分解（同一实现：PoolDecomp.measure）----
        val corpus = RealCorpus.load()
        assumeTrue(corpus != null, "找不到真实语料（ERATW_CORPUS），无法构造与 perfBaseline 同形的 BootModel")
        val model = bootModel(program, metadata, table, byStatement, corpus!!)

        val tDec = System.nanoTime()
        val result = PoolDecomp.measure(program, model, store, gameData, runtime, sizer, s3, s0min, rawBill)
        result.postWalkUsed = HeapMeter.settled()
        val decMs = msSince(tDec)
        result.logLines().forEach { println(it) }

        // ---- §2 上限口径（PoolCensus）与失效索引扣除 ----
        val census = PoolCensus.census(program, model, sizer)
        val censusTotal = census.rows.sumOf { it.bytes }
        val indexRows = census.rows.filter { it.group == "去重索引" && it.name.endsWith("HashMap") }
        val indexSum = indexRows.sumOf { it.bytes }
        val formula = (program.constantCount + program.stringCount + program.exprCount) * 48L

        val pool = readField(program, "pool")
        val sealProof = "pool.longIndex=${readField(pool, "longIndex")}，" +
            "stringIndex=${readField(pool, "stringIndex")}，exprIndex=${readField(pool, "exprIndex")}"

        val adjusted = censusTotal - indexSum
        println(
            "[perf-decomp] §2 上限口径 ${mibOf(censusTotal)} − 失效去重索引 ${mibOf(indexSum)} = " +
                "${mibOf(adjusted)}；seal 后 $sealProof",
        )
        println(
            "[perf-decomp] 互斥口径已归属 ${mibOf(result.attributed)}（${result.objects} 对象，遍历 ${result.walkMs}ms），" +
                "S3 ${mibOf(s3)}，残差 ${signedOf(result.residual)}",
        )

        // ---- 不变量 ----
        assertEquals(indexSum, result.staleIndexBytes, "失效索引三行之和必须等于 measure 的 staleIndexBytes")
        assertEquals(formula, indexSum, "失效索引 =（常量 + 字符串 + 表达式）条数 × 48B")
        assertTrue(sealProof.contains("null"), "seal 后三张去重索引必须为 null：$sealProof")
        assertTrue(result.attributed in 256L * MIB..1280L * MIB, "已归属量级异常：${mibOf(result.attributed)}")
        assertTrue(result.residual > -64L * MIB, "残差为大负数，说明做过归属的对象在读数时已不可达：${signedOf(result.residual)}")
        assertTrue(
            kotlin.math.abs(result.postWalkUsed - s3) < 96L * MIB,
            "遍历后复读（${mibOf(result.postWalkUsed)}）与 S3（${mibOf(s3)}）差超过 96 MiB，装置污染了读数",
        )
        assertTrue(decMs in 0..900_000L, "分解耗时异常：${decMs}ms")

        // ---- 报告 ----
        val head = Probe(
            stamp = stamp,
            artifact = art,
            programBytes = programBytes,
            metaBytes = metaBytes,
            programSha = programSha,
            probeVersion = probe.version,
            probeSections = probe.sectionCount,
            instructions = program.instructionCount,
            functions = program.functions.size,
            strings = program.stringCount,
            constants = program.constantCount,
            expressions = program.exprCount,
            loadMs = loadMs,
            metaMs = metaMs,
            bootMs = bootMs,
            decMs = decMs,
            s0 = s0min,
            s0Spread = s0all.joinToString("/") { mibOf(it) },
            s0Max = s0max,
            loadResident = loadResident,
            metaResident = metaResident,
            s3 = s3,
            rawBill = rawBill,
            globalDeclared = dimInstall.declared,
            globalFailures = dimInstall.failures.size,
            privateOk = privateOk,
            privateFail = privateFail.size,
            gameDataLoaded = gameData != null,
            gameDataTemplates = gameData?.templates?.size ?: 0,
            engineTag = metadata.engineTag,
            metaProgramSha = metadata.programSha256,
            census = census,
            censusTotal = censusTotal,
            indexRows = indexRows,
            indexSum = indexSum,
            formula = formula,
            adjusted = adjusted,
            sealProof = sealProof,
        )
        val text = render(head, result)
        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath, text)
        println("[perf-decomp] 报告：$reportPath（${text.length} 字符）")
        println("[perf-decomp] 下一刀最值钱的三块：" + topThree(result).joinToString("；") {
            "${it.rank}. ${it.row.name} ${mibOf(it.row.bytes)}"
        })
        assertTrue(Files.size(reportPath) > 2000, "报告过短，疑似没写内容：$reportPath")
    }

    // ------------------------------------------------------------------
    // 装置侧输入
    // ------------------------------------------------------------------

    private class Artifacts(val program: Path, val meta: Path, val source: String)

    private fun cwd(): Path = Path.of("").toAbsolutePath().normalize()

    private fun resolveArtifacts(root: Path): Artifacts? {
        val envDir = System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }
        val envFile = System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }
        val explicit = ArrayList<Path>()
        if (envFile != null) Path.of(envFile).toAbsolutePath().normalize().parent?.let { explicit.add(it) }
        if (envDir != null) explicit.add(Path.of(envDir).toAbsolutePath().normalize())
        val candidates = explicit + listOf(
            root.resolve("build").resolve("erbc-out").resolve("erbc.v1"),
            root.resolve("build").resolve("erbc.v1"),
            root.resolve("build").resolve("erbc-out"),
            root.resolve("..").resolve("tools").resolve("erbcc").resolve("build").resolve("erbc-out").resolve("erbc.v1"),
        )
        for (c in candidates) {
            hit(c)?.let { return it }
        }
        if (System.getenv("ERATW_PERF_BUILD_ERBC") == "0") return null
        // 兜底：子 JVM 用 6g 编一份（test worker 只有 3g，全量编译约 3.4 GiB 峰值）
        val out = root.resolve("build").resolve("erbc-out")
        println("[perf-decomp] 未找到产物，尝试子 JVM 编译：-Xmx6g → $out")
        if (!buildArtifacts(out, root)) return null
        return hit(out.resolve("erbc.v1"))
    }

    private fun hit(dir: Path): Artifacts? {
        if (!Files.isDirectory(dir)) return null
        val direct = dir.resolve("program.erbc")
        if (Files.isRegularFile(direct)) {
            val meta = dir.resolve("program.meta")
            if (Files.isRegularFile(meta)) return Artifacts(direct, meta, dir.toString())
        }
        // erbc-out 这类父目录：最多向下找三层
        return runCatching {
            Files.walk(dir, 3).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString() == "program.erbc" }
                    .map { it.parent }
                    .filter { Files.isRegularFile(it.resolve("program.meta")) }
                    .sorted()
                    .findFirst().orElse(null)
            }
        }.getOrNull()?.let { Artifacts(it.resolve("program.erbc"), it.resolve("program.meta"), it.toString()) }
    }

    /**
     * 子 JVM 编产物：`MainKt compile --corpus <ERB> --out <dir>`。
     *
     * 为什么不在本进程编：`:core-vm:test` 的 worker 上限 3g，全量编译峰值约 3.4 GiB
     * （`tools/erbcc/build.gradle.kts` 的 `compileCorpus` 也用 6g）。
     * 失败返回 false，由调用方跳过（不把测量缺件变成红闸）。
     */
    private fun buildArtifacts(out: Path, root: Path): Boolean {
        val corpus = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val javaBin = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java",
        )
        if (!Files.isRegularFile(javaBin)) return false
        val log = root.resolve("build").resolve("erbc-compile.log")
        Files.createDirectories(log.parent)
        val pb = ProcessBuilder(
            javaBin.toString(),
            "-Xmx6g",
            "-Dfile.encoding=UTF-8",
            "-cp",
            System.getProperty("java.class.path"),
            "com.eratw.port.erbcc.MainKt",
            "compile",
            "--corpus",
            corpus,
            "--out",
            out.toString(),
        )
        pb.redirectErrorStream(true)
        pb.redirectOutput(log.toFile())
        val p = pb.start()
        val done = p.waitFor(20, TimeUnit.MINUTES)
        if (!done) p.destroyForcibly()
        val code = if (done) p.exitValue() else -1
        println("[perf-decomp] 子 JVM 编译退出码 $code；日志 $log")
        if (code != 0) {
            runCatching {
                Files.readAllLines(log).takeLast(15).forEach { println("[perf-decomp]   $it") }
            }
        }
        return code == 0
    }

    /** 游戏根（CSV/resources/sav 那层）：`ERATW_GAMEDIR`，缺省取语料 ERB 的上一级。 */
    private fun gameRoot(): Path? {
        val env = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }
        if (env != null) return Path.of(env).toAbsolutePath().normalize()
        val corpus = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Path.of(corpus).toAbsolutePath().normalize().parent
    }

    /**
     * 用 `.erbc` 路径的产物拼一个与 perfBaseline 同形的 [BootModel]。
     *
     * 只填 **侧车能给出** 的字段：编译期专有的 `globalVarDecls`（预解析 `DimSpec`）、
     * 失败清单与统计壳在装机路径上并不存在，故留空/清零并在报告里标注
     * 「装置侧，真机不计」；全局 `#DIM` 的解析结果由 `SystemVariableBootstrap.install`
     * 放进 `store`，因此落在变量桶而不是元数据桶（与 S2b 的口径差就在这里）。
     */
    private fun bootModel(
        program: Program,
        metadata: BootMetadata,
        table: FunctionTable,
        dataSections: IdentityHashMap<Statement, List<List<Expr>>>,
        corpus: RealCorpus,
    ): BootModel = BootModel(
        corpus = corpus,
        program = program,
        buildError = null,
        table = table,
        labelPcs = metadata.labels,
        dataSections = dataSections,
        fileBySigKey = metadata.fileByKey,
        globalDimDecls = metadata.globalDims.map { GlobalDimDecl(it.raw, it.file, it.line) },
        globalVarDecls = emptyList(),
        userVarDecls = metadata.privateDims.map { it.functionKey to it.spec },
        compileFailures = emptyList(),
        parseFailures = emptyList(),
        dimParseFailures = emptyList(),
        dimParseConfig = metadata.dimParseConfig,
        // 装机路径的折叠面来自游戏目录的 `CSV/VariableSize.csv`（与运行期
        // `SystemVariableBootstrap.install` 同一份来源），审计这里照实取。
        foldSnapshot = DimFoldSnapshot.load(corpus.root.parent?.resolve("CSV")),
        notes = emptyList(),
        // 装机路径没有编译期统计：条数类字段给「不适用」的 0，规模读数列照实填（只占一个对象壳）
        stats = BootStats(
            corpusFiles = 0,
            erbFiles = 0,
            erhFiles = 0,
            loadedFiles = 0,
            totalDefs = 0,
            uniqueNames = 0,
            duplicateNames = 0,
            eventNames = 0,
            slices = 0,
            compiled = 0,
            compileFailures = 0,
            parseFailures = 0,
            eventEntries = 0,
            instructions = program.instructionCount,
            statements = program.statementCount,
            globalVarDecls = 0,
            globalVarParseFailures = 0,
            privateVarDecls = 0,
            privateVarParseFailures = 0,
            elapsedMs = 0L,
        ),
    )

    // ------------------------------------------------------------------
    // 报告
    // ------------------------------------------------------------------

    private class Probe(
        val stamp: LocalDateTime,
        val artifact: Artifacts,
        val programBytes: Long,
        val metaBytes: Long,
        val programSha: String,
        val probeVersion: Int,
        val probeSections: Int,
        val instructions: Int,
        val functions: Int,
        val strings: Int,
        val constants: Int,
        val expressions: Int,
        val loadMs: Long,
        val metaMs: Long,
        val bootMs: Long,
        val decMs: Long,
        val s0: Long,
        val s0Spread: String,
        val s0Max: Long,
        val loadResident: Long,
        val metaResident: Long,
        val s3: Long,
        val rawBill: RawBill,
        val globalDeclared: Int,
        val globalFailures: Int,
        val privateOk: Int,
        val privateFail: Int,
        val gameDataLoaded: Boolean,
        val gameDataTemplates: Int,
        val engineTag: String,
        val metaProgramSha: String?,
        val census: Census,
        val censusTotal: Long,
        val indexRows: List<CensusRow>,
        val indexSum: Long,
        val formula: Long,
        val adjusted: Long,
        val sealProof: String,
    )

    private class Cut(val rank: Int, val row: CensusRow, val note: String)

    /**
     * 按 MiB 排序取前三块（排除装置侧对象：`RealCorpus` 文件清单与统计诊断壳
     * 只在桌面装置里存在，真机由 `erbc.v1/manifest.txt` 提供，不进 Java 堆）。
     */
    private fun topThree(r: PoolDecompResult): List<Cut> {
        val deviceSide = setOf(
            "RealCorpus（根 + 文件清单）",
            "统计与诊断清单（stats/notes/failures）",
            "BootModel 外壳",
        )
        val rows = r.rows
            .filter { it.bytes >= MIB && it.name !in deviceSide }
            .sortedByDescending { it.bytes }
        return rows.take(3).mapIndexed { i, row -> Cut(i + 1, row, cutNote(row.name, r)) }
    }

    private fun cutNote(name: String, r: PoolDecompResult): String = when {
        name.startsWith("语句 args") ->
            "语句池最大单项。`.erbc` 装载按池下标重建，已有 ${r.exprSharedRoots} 个 `exprs` 根与本桶共享" +
                "（先到者在此）；剩下的是独占的 Argument/Expr 子树。下一刀只能是按语句惰性重建 args" +
                "（执行到该语句才建）或换更紧凑的操作数编码。"
        name.startsWith("exprs 根") ->
            "表达式池的独占部分（共享根已在语句桶计过）；与语句 `args` 合并/共享是唯一不牺牲语义的回收面" +
                "（不改变池下标与语句对象）。"
        name.startsWith("Program.code") ->
            "字节码已在产物里，`ProgramLoader` 也能按段映射；卡点在 `Program.code` 仍解成 IntArray。" +
                "改 VM 取指路径（按偏移读 mmap 窗口）可把这一整行移出 Java 堆，风险集中在取指热路径。"
        name.startsWith("Program.lines") ->
            "行号表只服务报错定位，与 code 一样走 mmap 即可；也可换分块/差分编码，收益小于 code 但零语义风险。"
        name.startsWith("Program.functions") ->
            "每个 FunctionInfo 带 labelNames/labelPcs 平行数组与名字串；惰性化或共享空表" +
                "（与 w7-progfuncs 的函数表壳惰性化同一条路），先看空数组壳的占比。"
        name.startsWith("FunctionTable") ->
            "metas + byIndex/nonEventByKey/eventMembersByKey 三张 HashMap 与侧车 FuncMeta 重复持同一批键；" +
                "键可共享、索引可惰性建，收益随函数数线性增长。"
        name.startsWith("BootModel.labelPcs") ->
            "侧车标签表与 Program.functions 的 labelNames/labelPcs 是第二份；键串可共享、内层 map 可只在命中时建。"
        name.startsWith("VariableStore") ->
            "变量平铺数组 + 角色表 + CSV 模板；按需分配（模板/名字表惰性）比整体优化更值。"
        name.startsWith("GameData") ->
            "CSV/.als 产物与 store.registry 同源；模板可惰性，注意别把名字字典拆成第二份。"
        name.startsWith("statements 池") ->
            "池本身的背衬数组不大；它的价值主要是定住语句条数，不是落刀点。"
        name.startsWith("语句对象外壳") ->
            "语句对象本身是语句池的固定成本；落刀只能改成列式/紧凑编码（类型码 + 操作数索引），" +
                "改动面覆盖 VM 取语句的每条路径，收益大但风险最高。"
        name.startsWith("Program.callArgs") ->
            "调用点实参表：每调用点一个 ArrayList + CallArg（含形参名串）；可改成平行数组或共享形参名，零语义风险。"
        else -> "见该行「口径」列；本行未单列方案。"
    }

    private fun render(p: Probe, r: PoolDecompResult): String {
        val sb = StringBuilder()
        val groups = r.groups.sortedByDescending { it.bytes }
        val top3 = topThree(r)

        sb.appendLine("# `.erbc` 全量装载后常驻分池复测（W7-PERFCENSUS）")
        sb.appendLine()
        sb.appendLine("## 0. 读数元信息")
        sb.appendLine()
        sb.appendLine("| 项 | 值 |")
        sb.appendLine("|---|---|")
        sb.appendLine("| 读数时间戳 | ${p.stamp} |")
        sb.appendLine("| 产物目录 | `${p.artifact.source}` |")
        sb.appendLine("| `program.erbc` | ${p.programBytes} B（${mibOf(p.programBytes)}），sha256 `${p.programSha}` |")
        sb.appendLine("| `program.meta` | ${p.metaBytes} B（${mibOf(p.metaBytes)}） |")
        sb.appendLine("| 侧车 `engine` | `${p.engineTag}` |")
        sb.appendLine("| 侧车 `program-sha256` | `${p.metaProgramSha ?: "-"}`（${if (p.metaProgramSha == p.programSha) "与产物一致" else "与产物不一致或缺失"}） |")
        sb.appendLine("| 装载路径 | `ProgramLoader.loadMapped` + `BootMetadata.read`（= 装机路径 / `GameHost`） |")
        sb.appendLine("| 产物文件头 | v${p.probeVersion}，${p.probeSections} 段 |")
        sb.appendLine("| 程序规模 | ${p.instructions} 指令 / ${r.rawBill.statements} 语句 / ${p.functions} 函数 / ${p.strings} 串 / ${p.constants} 常量 / ${p.expressions} 表达式 |")
        sb.appendLine("| JVM | ${HeapMeter.jvmName()} |")
        sb.appendLine("| 堆 | max ${mibOf(HeapMeter.max())}；args `${HeapMeter.inputArgs().joinToString(" ")}` |")
        sb.appendLine("| GC | ${HeapMeter.gcNames()}；每读数 ${HeapMeter.rounds} 轮取最小值 |")
        sb.appendLine("| 机器 | ${HeapMeter.osName()} |")
        sb.appendLine("| 已加载类 | ${HeapMeter.loadedClasses()} |")
        sb.appendLine("| 口径 | 与 `:core-vm:perfBaseline` S2b 同一装置：`HeapMeter` + `SizeModel` + `ResidentWalker` + `PoolDecomp.measure` |")
        sb.appendLine()
        sb.appendLine("> `used = Runtime.totalMemory() - freeMemory()`，每段连打 ${HeapMeter.rounds} 轮 `System.gc()` 取**最小值**。")
        sb.appendLine("> 分解是**身份去重**遍历：共享实例归先到者，因此各行互斥、可相加。")
        sb.appendLine()

        sb.appendLine("## 1. 分段常驻（`.erbc` 装载链，post-GC used）")
        sb.appendLine()
        sb.appendLine("| 段 | 时点 | 常驻 | 增量 | 净耗时 | 备注 |")
        sb.appendLine("|---|---|---:|---:|---:|---|")
        sb.appendLine("| S0 | test worker 基线（JUnit/Kotlin 已加载） | ${mibOf(p.s0)} | — | — | ${HeapMeter.rounds} 次读数：${p.s0Spread} |")
        sb.appendLine("| L1 | `.erbc` 装载后（仅 `Program`） | ${mibOf(p.loadResident)} | ${signedOf(p.loadResident - p.s0)} | ${p.loadMs} ms | `ProgramLoader.loadMapped`，按段解码；未建运行期 |")
        sb.appendLine("| L1m | 侧车 `BootMetadata.read` 后 | ${mibOf(p.metaResident)} | ${signedOf(p.metaResident - p.loadResident)} | ${p.metaMs} ms | 函数元数据 / 标签 / 数据节 / `#DIM` 原文 |")
        sb.appendLine("| S3 | 运行期装配后 | ${mibOf(p.s3)} | ${signedOf(p.s3 - p.metaResident)} | ${p.bootMs} ms | CSV + store + `#DIM`（全局 ${p.globalDeclared} 成功 / ${p.globalFailures} 失败，私有 ${p.privateOk} 成功 / ${p.privateFail} 失败）+ `ProcessRuntime` + 装表；CSV ${if (p.gameDataLoaded) "已装载（模板 ${p.gameDataTemplates} 条）" else "未装载"}；**含 `releaseStatementRaws` 释放 ${mibOf(p.rawBill.bytes)}** |")
        sb.appendLine()
        sb.appendLine("- **与 perfBaseline 的时点对齐**：本表 `S3` 即 S2b 的 `S3`（运行期建立后、`begin` 之前）。")
        sb.appendLine("- **S0 不可跨装置直接比**：perfBaseline 是独立 `main`，S0 ≈ 1.7 MiB；本测试跑在 test worker 里，S0 已含 JUnit/Kotlin 运行时。")
        sb.appendLine("  因此残差（`used − S0 − 已归属`）只用于自检装置有没有漏记，不与 S2b 的 38–39 MiB 逐位对拍。")
        sb.appendLine("- `L1m → S3` 为负是预期：语句原文在 `ProcessRuntime` 构造期整批释放，装配新增（store/CSV/运行期）抵不过这一段。")
        sb.appendLine("- 语句原文（`raw`，`ProcessRuntime` 构造期由 `releaseStatementRaws` 释放，`ProcessRuntime.kt:420`）：" +
            "${p.rawBill.statements} 条 / ${p.rawBill.chars} 字符 = ${mibOf(p.rawBill.bytes)}" +
            "（latin1 ${p.rawBill.latin1} / UTF-16 ${p.rawBill.utf16} 条）。它只在 `L1`/`L1m` 存在，`S3` 前已释放，不在下表内。")
        sb.appendLine()

        sb.appendLine("## 2. 互斥口径逐池分解（装载后 = S3）")
        sb.appendLine()
        sb.appendLine("> 桶顺序 = 归属顺序：① code/lines → ② 语句池 → ③ 表达式池 → ⑤ 字符串/常量 →")
        sb.appendLine("> ④ 函数表 → ⑦ 侧车元数据 → ⑥ 变量与角色 → ⑧ 其他。共享实例归先到者。")
        sb.appendLine("> 分解耗时 ${p.decMs} ms；遍历后复读 ${mibOf(r.postWalkUsed)}（分解前 ${mibOf(p.s3)}）。")
        sb.appendLine()
        sb.appendLine("| 桶 | 项 | 计数 | 估算 | 占已归属 | 口径 |")
        sb.appendLine("|---|---|---|---:|---:|---|")
        for (row in r.rows) {
            sb.appendLine(
                "| ${row.group} | ${row.name} | ${row.count} | ${mibOf(row.bytes)} | " +
                    "${pctOf(row.bytes, r.attributed)} | ${row.note} |",
            )
        }
        sb.appendLine("| | **已归属合计** | ${r.objects} 对象 | **${mibOf(r.attributed)}** | 100% | 遍历 ${r.walkMs} ms |")
        sb.appendLine("| | 装置基线 S0 | — | **${mibOf(p.s0)}** | — | test worker，含 JUnit |")
        sb.appendLine("| | 未归属（残差） | — | **${signedOf(r.residual)}** | — | 建模误差（String 每条少 8B、HashMap.EntrySet 未计等）+ 堆内非对象开销 |")
        sb.appendLine("| | **= JVM 实测常驻 S3** | — | **${mibOf(p.s3)}** | — | `total − free`，${HeapMeter.rounds} 轮 GC 最小值 |")
        sb.appendLine()
        sb.appendLine("**桶小计（按 MiB 排序）**：" + groups.joinToString("；") { "${it.name} ${mibOf(it.bytes)}（${it.rows} 行）" })
        sb.appendLine()
        sb.appendLine("**逐类模型账单（前 15）** —— 可与 `jcmd <pid> GC.class_histogram` 逐类对拍：")
        sb.appendLine()
        sb.appendLine("| 类 | 实例 | 估算 |")
        sb.appendLine("|---|---:|---:|")
        for ((name, count, bytes) in r.classes.take(15)) {
            sb.appendLine("| `${name}` | $count | ${mibOf(bytes)} |")
        }
        sb.appendLine()
        sb.appendLine(
            "- 共享读数：`exprs` 池有 ${r.exprSharedRoots} 个根与语句 `args` 是**同一实例**" +
                (if (r.exprSharedRoots == 0) "——0 表示两套表达式对象图完全不重叠，表达式池是语句树之外的第二份" else "") +
                "；字符串池有 ${r.stringShared} 条与语句/函数表共享。",
        )
        sb.appendLine("- seal 实测：${p.sealProof}（`Program.kt:273-277` 的 `init` 调 `pool.seal()`，本体 `Program.kt:199-203` 把三张索引置 null）。")
        sb.appendLine()

        sb.appendLine("## 2b. 扣除 `PoolCensus` §2 的失效去重索引（读数修正）")
        sb.appendLine()
        sb.appendLine("> `PoolCensus`（§2 表）仍按 48 B/条把三张**构建期去重索引**计入占用，")
        sb.appendLine("> 但它们在 `Program.init -> ConstantPool.seal()` 之后已经是 `null`。")
        sb.appendLine("> 本表（§2）是互斥口径、本来就不含它们；引用 §2 的上限合计时必须先扣掉这一段。")
        sb.appendLine()
        sb.appendLine("| 项 | 读数 |")
        sb.appendLine("|---|---:|")
        sb.appendLine("| `PoolCensus` §2 上限合计（含重复计入 `statements.args` 与 `exprs` 池共享部分） | ${mibOf(p.censusTotal)} |")
        for (row in p.indexRows) {
            sb.appendLine("| 失效索引：${row.name}（${row.count}） | −${mibOf(row.bytes)} |")
        }
        sb.appendLine("| **失效索引合计** | **−${mibOf(p.indexSum)}**（= ${mibOf(p.formula)} 公式值，三行之和已断言一致） |")
        sb.appendLine("| 扣除后（仍含共享重复计入，故是上限而非实测） | **${mibOf(p.adjusted)}** |")
        sb.appendLine("| 本表互斥口径已归属（实测遍历） | **${mibOf(r.attributed)}** |")
        sb.appendLine("| JVM 实测 S3 常驻 | **${mibOf(p.s3)}** |")
        sb.appendLine()
        sb.appendLine(
            "- 结论：三张索引按 48 B/条虚增 **${mibOf(p.indexSum)}**（`PoolCensus` 的「去重索引」分组共 " +
                "${p.census.rows.count { it.group == "去重索引" }} 行，其中 3 行是构建期 HashMap，" +
                "第 4 行「池 ArrayList 背衬数组」是真实常驻、未扣）；" +
                "seal 之后这些字节**不存在**，任何把 §2 合计直接当常驻预算的读数都偏高这一段。",
        )
        sb.appendLine()

        sb.appendLine("## 3. 与 `perfBaseline` S2b 的对照（同装置、不同装载路径）")
        sb.appendLine()
        sb.appendLine("| 桶 | 本表（`.erbc` 装载） | S2b（源码直编，2026-10-20 读数） | 差异原因 |")
        sb.appendLine("|---|---:|---:|---|")
        sb.appendLine("| ② 语句池 | ${mibOf(groupOf(r, PoolDecomp.G_STMT))} | 375.9 MiB | `.erbc` 记录流按池下标重建：语句 `args` 与 `exprs` 池共享 ${r.exprSharedRoots} 个根，同一批表达式只算一次；直编路径是两套独立对象图（共享 0） |")
        sb.appendLine("| ③ 表达式池 | ${mibOf(groupOf(r, PoolDecomp.G_EXPR))} | 94.8 MiB | 同上：共享根 ${r.exprSharedRoots}/${p.expressions} 已计入语句桶，本桶只剩其余根与它们的子树 |")
        sb.appendLine("| ① code/lines | ${mibOf(groupOf(r, PoolDecomp.G_CODE))} | 91.6 MiB | `loadMapped` 仍解成 IntArray（`ProgramLoader` KDoc 已注明），故与直编同量级 |")
        sb.appendLine("| ④ 函数表 | ${mibOf(groupOf(r, PoolDecomp.G_FN))} | 66.3 MiB | 侧车的 `functions`/`labels`/`fileByKey` 三张 110,072 键表各自解出一份键串，直编期 `BootModel` 里这些键与 `FuncMeta.key` 共享 |")
        sb.appendLine("| ⑥ 变量与角色 | ${mibOf(groupOf(r, PoolDecomp.G_VAR))} | 33.7 MiB | 本表全局 `#DIM` 走 `SystemVariableBootstrap.install`（生产口径），解析结果落在这里 |")
        sb.appendLine("| ⑦ 侧车元数据 | ${mibOf(groupOf(r, PoolDecomp.G_META))} | 5.4 MiB | 本表无 `globalVarDecls`（侧车只存 `#DIM` 原文）、无失败清单/统计；但多了 `RealCorpus` 文件清单（装置侧） |")
        sb.appendLine("| ⑤ 字符串/常量 | ${mibOf(groupOf(r, PoolDecomp.G_STR))} | 1.1 MiB | 同口径 |")
        sb.appendLine("| ⑧ 其他 | ${mibOf(groupOf(r, PoolDecomp.G_OTHER))} | 0.0 MiB | `ProcessRuntime` 外壳；大部分容器挂在变量/程序侧 |")
        sb.appendLine()
        sb.appendLine("- 装置侧项（真机不计，桌面装置为定位/统计而持有）：`RealCorpus` 文件清单 " +
            "${mibOf(rowBytes(r, "RealCorpus（根 + 文件清单）"))}、统计诊断壳 " +
            "${mibOf(rowBytes(r, "统计与诊断清单（stats/notes/failures）"))}、`BootModel` 外壳 " +
            "${mibOf(rowBytes(r, "BootModel 外壳"))}。")
        sb.appendLine()

        sb.appendLine("## 4. 下一刀最值钱的三块（按 MiB 排序）")
        sb.appendLine()
        sb.appendLine("> 排序只含程序/运行期侧的实测行（排除上节「装置侧项」），数值取自本表 §2。")
        sb.appendLine()
        sb.appendLine("| # | 项 | MiB | 占已归属 | 为什么值钱（读数支撑） | 落刀面与风险 |")
        sb.appendLine("|---|---|---:|---:|---|---|")
        for (c in top3) {
            sb.appendLine(
                "| ${c.rank} | ${c.row.name} | ${mibOf(c.row.bytes)} | ${pctOf(c.row.bytes, r.attributed)} | " +
                    "${c.row.count}；桶 ${c.row.group} | ${c.note} |",
            )
        }
        sb.appendLine()
        sb.appendLine("**排序算式**（互斥口径，前 3 名之和 / 已归属）：" +
            "${top3.joinToString(" + ") { mibOf(it.row.bytes) }} = " +
            "${mibOf(top3.sumOf { it.row.bytes })}，占 ${pctOf(top3.sumOf { it.row.bytes }, r.attributed)}。")
        sb.appendLine()
        val bucketTop = groups.take(3)
        sb.appendLine("**按桶排（同一批读数的另一种切法）**：" + bucketTop.joinToString("；") {
            "${it.name} ${mibOf(it.bytes)}"
        } + "；三者合计 ${mibOf(bucketTop.sumOf { it.bytes })}，占已归属 " +
            "${pctOf(bucketTop.sumOf { it.bytes }, r.attributed)}。")
        sb.appendLine()
        val fnKeys = rowBytes(r, "FunctionTable（FuncMeta）") +
            rowBytes(r, "BootModel.labelPcs") + rowBytes(r, "BootModel.fileBySigKey")
        sb.appendLine(
            "**结构性观察（本次复测新增）**：④ 函数表桶里 `FunctionTable` + 标签表 + `fileBySigKey` " +
                "三行合计 **${mibOf(fnKeys)}**，三者都以同一批 ${p.functions} 个函数键为键、" +
                "各自解出一份键串（侧车记录流不共享 String 实例）。这是单点最大的可共享面：" +
                "装载侧做一次键归一（或让三张表共用同一份 `String` 池）即可回收其中两份。" +
                "同一读数也说明 `Program.functions` 的 labelNames/labelPcs 是**第三份**标签数据。",
        )
        sb.appendLine()

        sb.appendLine("## 5. 误差来源与局限")
        sb.appendLine()
        sb.appendLine("- 浅尺寸按 HotSpot 布局建模（12B 头、8 对齐、压缩指针 4B、compact strings），不是 `Instrumentation.getObjectSize`；")
        sb.appendLine("- `String` 每条约少算 8B（模型 32B 对象壳 vs 实测 24B 对象 + 16B 数组头）；全量 2M 条量级 ⇒ 十几 MiB；")
        sb.appendLine("- `HashMap` 对象模型 40B、实测 48B；每张 map 的 `HashMap.EntrySet` 未计；")
        sb.appendLine("- `ArrayList` 容量按 `size` 下界估；`HashMap` 表容量按 `tableSizeFor(ceil(n/0.75))` 反推，树化桶未单列；")
        sb.appendLine("- `java.base` 的 `Path`/`Pattern`/`StringBuilder` 内部字段不可反射，只计浅尺寸；")
        sb.appendLine("- dex/ART 元数据、mmap 段、native 映射不在 Java 堆读数内（真机另计）；")
        sb.appendLine("- 本测试跑在 3g 的 test worker 里，**不测编译峰值**（那需要 6g，见 `:core-vm:perfBaseline`）。")
        sb.appendLine()

        sb.appendLine("## 6. 复现")
        sb.appendLine()
        sb.appendLine("```powershell")
        sb.appendLine("\u0024env:JAVA_HOME='D:\\DSH\\eratw-安卓移植计划\\.tooling\\jdk-17.0.20.1+1'")
        sb.appendLine("\u0024env:GRADLE_USER_HOME='D:\\DSH\\eratw-安卓移植计划\\.tooling\\gradle-home'")
        sb.appendLine("\u0024env:ERATW_CORPUS='D:\\DSH\\eratw-安卓移植计划\\eratw-画蛇添足版eratw4.980\\ERB'")
        sb.appendLine("\u0024env:ERATW_GAMEDIR='D:\\DSH\\eratw-安卓移植计划\\eratw-画蛇添足版eratw4.980'")
        sb.appendLine("cd <worktree>/eratw-android")
        sb.appendLine("# 产物（一次即可；峰值约 3.4 GiB，必须 6g）：")
        sb.appendLine(".\u005cgradlew --configure-on-demand :tools:erbcc:compileCorpus -Pcorpus=\u0024env:ERATW_CORPUS -Pout=<worktree>/eratw-android/core-vm/build/erbc-out")
        sb.appendLine("# 复测：")
        sb.appendLine(".\u005cgradlew --configure-on-demand :core-vm:test --tests \"\u002aPoolDecompAudit\u002a\" -i")
        sb.appendLine("# 报告：core-vm/build/perf-decomp.md；控制台摘要需 -i（或看 build/test-results/test 下的 system-out）")
        sb.appendLine("```")
        sb.appendLine()
        sb.appendLine("产物路径可用 `ERATW_ERBC_DIR`（`erbc.v1` 目录）或 `ERATW_ERBC`（`program.erbc` 文件）覆盖。")
        sb.appendLine()
        sb.appendLine("缺产物时测试会用子 JVM（`-Xmx6g`）自建一次，日志在 `core-vm/build/erbc-compile.log`；")
        sb.appendLine("设 `ERATW_PERF_BUILD_ERBC=0` 可关闭该兜底（缺产物即 `Assumptions` 跳过，不红闸）。")
        sb.appendLine()
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun groupOf(r: PoolDecompResult, name: String): Long =
        r.groups.firstOrNull { it.name == name }?.bytes ?: 0L

    private fun rowBytes(r: PoolDecompResult, name: String): Long =
        r.rows.firstOrNull { it.name == name }?.bytes ?: 0L

    /** 读实例字段（含私有 Kotlin 字段）；只用于取池句柄做 seal 证明。 */
    private fun readField(target: Any?, name: String): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                return runCatching {
                    f.isAccessible = true
                    f.get(target)
                }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    private fun sha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val out = StringBuilder(64)
        for (b in md.digest()) out.append("%02x".format(b.toInt() and 0xFF))
        return out.toString()
    }

    private fun msSince(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000

    private companion object {
        const val MIB = 1024L * 1024L
    }
}

/** MiB 文本（手写，避免 locale 差异）。 */
private fun mibOf(bytes: Long): String {
    val neg = bytes < 0
    val abs = if (neg) -bytes else bytes
    val tenths = (abs * 10 + 524_288) / 1_048_576
    return "${if (neg) "-" else ""}${tenths / 10}.${tenths % 10} MiB"
}

private fun signedOf(bytes: Long): String =
    (if (bytes < 0) "-" else "+") + mibOf(if (bytes < 0) -bytes else bytes)

private fun pctOf(part: Long, whole: Long): String {
    if (whole <= 0) return "-"
    val tenths = (part * 1000 + whole / 2) / whole
    return "${tenths / 10}.${tenths % 10}%"
}
