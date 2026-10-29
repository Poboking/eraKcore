package com.eratw.port.erbcc

import com.eratw.port.core.lexer.CharStream
import com.eratw.port.erbcc.audit.Finding
import com.eratw.port.erbcc.audit.FindingKind
import com.eratw.port.erbcc.audit.SymbolLinkAudit
import com.eratw.port.erbcc.compile.ArtifactFreshness
import com.eratw.port.erbcc.compile.CorpusCompile
import com.eratw.port.erbcc.compile.ErbcArtifacts
import com.eratw.port.erbcc.compile.ErhLoadAudit
import com.erbcc.bridge.droppedByUnresolvableDim
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.LexEndWith
import com.eratw.port.core.lexer.LexAnalyzeFlag
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LexWarning
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.lexer.OperatorCode
import com.eratw.port.core.lexer.Token
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.system.exitProcess

// erbcc —— EraBasic 编译器 CLI
// M1 阶段：scan 子命令 —— 全量语料词法扫描与异常报告
//
// 扫描模型对齐原版装载期两级结构：
//   1. EraStreamReader.ReadEnabledLine（逻辑行/预处理/置換名替换）→ LogicalLineReader
//   2. LogicalLineParser.ParseLine 第一级分派（LogicalLineParser.cs:385-570）：
//      行首标识符命中 funcDic（指令/方法）→ 指令行，参数延后到语句层（M2）；
//      否则走赋值两段式 Analyse(Operator, None) + ReadAssignmentOperator。
//      该路径上抛出的 CodeEE 在原版同样是装载期错误（ParseLine 捕获后记为 InvalidLine）。
//
// 编译输入判定（定稿，spec: ErbLoader.cs:256、Process.cs:801-870，见 specs/loader.md 附录 C）：
//   erbcc 编译输入 = 全量目录扫描的全部 .ERB（ERH 另走 ERH 规约），与 lazyloadingfiles.dat 完全解耦。
//   索引只决定「急切装载 vs 运行期懒装载」：索引内文件被 ErbLoader.cs:256 的
//   !LazyLoadingFiles.Contains() 排除在急切装载之外，但懒文件按 lazyloading.dat 的
//   函数名→文件表在运行期取回并完整解析（Process.cs:711）。因此全量编译的判定不变。
//   scan 默认策略为 all；index/union/engine 三策略仅作报告对比数据保留。

private const val USAGE = """erbcc - EraBasic 编译器 (eratw-android)

用法:
  erbcc scan --corpus <ERB目录> [--use-index <lazyloadingfiles.dat>] [--rename <_Rename.csv>] [--out <报告目录>]
  erbcc symbols --corpus <ERB目录> [--json <输出文件>]
  erbcc compile --corpus <ERB目录> --out <产物目录> [--limit <N>]
  erbcc check --corpus <ERB目录> [--out <产物目录>]
  erbcc audit --corpus <ERB目录> [--game-root <游戏根>] [--out <TSV文件>] [--limit <N>] [--strict] [--allow <白名单>]
  erbcc audit --corpus <ERB目录> [--game-root <游戏根>] --roundtrip [--artifact-dir <产物目录>] [--limit <N>]
  erbcc --help
  erbcc --version

symbols 选项（@标签符号表，规约见 specs/loader.md 附录 D）:
  --corpus          游戏数据根（ERB 语料所在目录）
  --json            追加写出机器可读的符号表摘要
  --collator-check  比较器自检：ja-JP Collator 与序数比较各跑一遍，对比重名胜者与事件执行序
  --ordinal         改用序数文件名排序（默认 ja-JP Collator，见 ADR-006）

compile 选项（全量语料 → .erbc 产物 + 确定性 manifest，规约见 specs/erbc-format.md）:
  --corpus      游戏数据根（ERB 语料所在目录）
  --out         产物输出目录（默认 tools/erbcc/erbc-out/；落成 <out>/erbc.v1/{program.erbc,program.meta,manifest.txt}）
  --limit       只编译前 N 个文件（调试用；manifest 会注明，正式产物必须全量）
  退出码        0 = 成功并落盘；1 = 编译/构建失败（stderr 逐条给 文件:行号，不落盘）；2 = 参数错误
  侧车          program.meta 是装载期元数据（函数元数据 / #DIM / 标签 / 数据节 / 宏表），
                与 program.erbc 成对分发；装载侧用 ProgramLoader + BootMetadata.read 一起读。

check 选项（产物新鲜度：清单登记的源文件摘要 × 磁盘现状，见 ArtifactFreshness）:
  --corpus      语料根（与编译时同一路径；清单里的相对路径相对它）
  --out         产物目录（`compile --out` 传的那个；缺省 tools/erbcc/erbc-out）
  退出码        0 = 容器/侧车/源文件全对齐；1 = 有新鲜度问题（逐条 stderr，含「改源未重编」）；2 = 参数错误

audit 选项（装载期符号链接审计：解析期使用点 × 运行期真实命名空间）:
  --corpus      语料根（通常 <gameRoot>/ERB）
  --game-root   游戏根（含 CSV/ 与 emuera.config；缺省 = corpus 的父目录）
  --out         未绑定符号 TSV（缺省 tools/erbcc/symbol-audit/unresolved-symbols.tsv）
  --limit       只切片前 N 个文件（调试用）
  --strict      有硬差异时退出码 1（未绑定变量/关键字/#DIM 注册失败等；动态关键字下标不计）
  --allow       白名单 TSV（每行 `<kind>\t<name>` 或 `<kind>\t<functionKey>\t<name>`；# 开头为注释）
  退出码        0 = 审计完成且（--strict 时）无硬差异；1 = 编译失败或 --strict 下有硬差异

audit --roundtrip（CMP-79：落盘后再装载对账，审计口径改走 .erbc + program.meta 真装载链）:
  --roundtrip      编译 → 落盘 → ProgramLoader + BootMetadata.read 重建会话 → 与内存产物对账；
                   绿的判据 = 侧车模型逐字段 + runtimeGlobals/privateVars/systemVars 三读数 +
                   使用点名字集合哈希 + 容器语句/指令条数全部相等
  --artifact-dir   产物落盘目录（缺省临时目录：绿了自删、红了保留路径供复现）
  --out            对账明细 TSV（缺省 tools/erbcc/symbol-audit/roundtrip.tsv）
  退出码           0 = 对账全绿；1 = 任一项不等（或编译失败，不落盘）

scan 选项:
  --corpus      游戏数据根（ERB 语料所在目录，索引路径相对此目录）
  --use-index   lazyloadingfiles.dat 装载索引（提供 index/union 策略）
  --rename      _Rename.csv 置換名辞書（默认自动探测 <corpus>/../CSV/_Rename.csv）
  --out         报告输出目录（默认 tools/erbcc/scan-report/）

文件集策略（一次运行内全部执行并对比）:
  all    磁盘上全部 *.erb/*.erh
  index  仅 lazyloadingfiles.dat 索引内文件
  union  磁盘与索引的并集
"""

fun main(args: Array<String>) {
    val code = when {
        args.isEmpty() -> { println(USAGE); 0 }
        args[0] == "--help" -> { println(USAGE); 0 }
        args[0] == "--version" -> { println("erbcc ${com.eratw.port.core.Version.VERSION}"); 0 }
        args[0] == "scan" -> { cmdScan(args.drop(1)); 0 }
        args[0] == "symbols" -> { cmdSymbols(args.drop(1)); 0 }
        args[0] == "compile" -> cmdCompile(args.drop(1))
        args[0] == "check" -> cmdCheck(args.drop(1))
        args[0] == "audit" -> cmdAudit(args.drop(1))
        else -> {
            System.err.println("错误: 无法识别的参数: ${args.joinToString(" ")}")
            System.err.println("使用 --help 查看用法。")
            2
        }
    }
    exitProcess(code)
}

// ---------------------------------------------------------------------------
// scan 子命令
// ---------------------------------------------------------------------------

private fun cmdScan(args: List<String>) {
    var corpus: Path? = null
    var index: Path? = null
    var rename: Path? = null
    var out = Path("tools/erbcc/scan-report")
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--corpus" -> corpus = Path(args.getOrNull(++i) ?: err("--corpus 需要参数"))
            "--use-index" -> index = Path(args.getOrNull(++i) ?: err("--use-index 需要参数"))
            "--rename" -> rename = Path(args.getOrNull(++i) ?: err("--rename 需要参数"))
            "--out" -> out = Path(args.getOrNull(++i) ?: err("--out 需要参数"))
            else -> err("无法识别的 scan 参数: ${args[i]}")
        }
        i++
    }
    val corpusDir = corpus ?: err("缺少 --corpus 参数")
    if (!Files.isDirectory(corpusDir)) err("corpus 不是目录: $corpusDir")
    if (index != null && !index.isRegularFile()) err("use-index 不是文件: $index")
    if (rename == null) {
        val guess = corpusDir.toAbsolutePath().normalize().parent?.resolve("CSV/_Rename.csv")
        if (guess != null && guess.isRegularFile()) rename = guess
    }
    val renameDic = rename?.let { loadRenameDic(it) } ?: emptyMap()
    if (rename != null) System.err.println("置換名辞書: ${rename!!.fileName}（${renameDic.size} 项）")

    val report = ScanRunner(corpusDir, index, renameDic).run()
    writeReports(report, out)
    printSummary(report)
}

private fun err(msg: String): Nothing {
    System.err.println("错误: $msg")
    exitProcess(2)
}

// ---------------------------------------------------------------------------
// symbols 子命令：@标签符号表（specs/loader.md 附录 D）
// ---------------------------------------------------------------------------

private fun cmdSymbols(args: List<String>) {
    var corpus: Path? = null
    var jsonOut: Path? = null
    var collatorCheck = false
    var config = SymbolConfig()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--corpus" -> corpus = Path(args.getOrNull(++i) ?: err("--corpus 需要参数"))
            "--json" -> jsonOut = Path(args.getOrNull(++i) ?: err("--json 需要参数"))
            "--collator-check" -> collatorCheck = true
            "--ordinal" -> config = config.copy(collator = CollatorOption.ORDINAL)
            else -> err("无法识别的 symbols 参数: ${args[i]}")
        }
        i++
    }
    val corpusDir = corpus ?: err("缺少 --corpus 参数")
    if (!Files.isDirectory(corpusDir)) err("corpus 不是目录: $corpusDir")

    val table = SymbolTable.build(corpusDir, config)
    val st = table.stats()
    println("=== 符号表 ===")
    println("配置: IgnoreCase=${table.config.ignoreCase} SortFilenames=${table.config.sortFilenames} " +
        "DebugMode=${table.config.debugMode} Collator=${table.config.collator} " +
        "宏 ${table.config.macros.size} 个（含展开体） ERH #DIM CONST 声明 ${table.erhDimConsts.size} 条")

    // 尺寸常量收集前后对比：同一批函数体 #DIM 声明里「因尺寸不可求值而丢弃」的条数
    // （口径见 com.erbcc.bridge.droppedByUnresolvableDim；收集前 = 空常量表）
    val sizeConsts = com.erbcc.bridge.sizeConsts(table)
    var dropBefore = 0
    var dropAfter = 0
    val droppedNow = ArrayList<String>()
    for (key in table.nameKeys) {
        for (d in table.defsOf(key)) {
            for (s in d.dimSpecs) {
                if (s.droppedByUnresolvableDim(emptyMap())) dropBefore++
                if (s.droppedByUnresolvableDim(sizeConsts)) {
                    dropAfter++
                    if (droppedNow.size < 20) droppedNow.add("${d.file}:${d.lineNo} #DIM ${s.name} ${s.dims}")
                }
            }
        }
    }
    println("尺寸可解常量 ${sizeConsts.size} 个  不可求值丢弃声明: 收集前 $dropBefore → 收集后 $dropAfter")
    if (droppedNow.isNotEmpty()) {
        println("  仍不可求值（前 ${droppedNow.size} 条）:")
        droppedNow.forEach { println("    $it") }
    }

    println("定义 ${st.totalDefs}  唯一名 ${st.uniqueNames}  重名 ${st.duplicateNames}" +
        "（非事件 ${st.nonEventDuplicates} / 事件 ${st.eventDuplicates}）  被遮蔽 ${st.shadowedDefs}  方法 ${st.methodDefs}")

    val dups = table.duplicateGroups()
    if (dups.isNotEmpty()) {
        println()
        println("=== 重名组 ===")
        for (g in dups) {
            println("[${if (g.isEvent) "事件" else "非事件"}] @${g.key}  ${g.defs.size} 处")
            val order = if (g.isEvent) table.eventCallOrder(g.key) else listOf(g.winner)
            order.forEachIndexed { k, d ->
                val flags = buildString {
                    if (d.isMethod) append("#FUNCTION ")
                    if (d.isOnly) append("#ONLY ")
                    if (d.isPri) append("#PRI ")
                    if (d.isLater) append("#LATER ")
                    if (d.isSingle) append("#SINGLE ")
                }
                println("  ${k + 1}. ${d.file}:${d.lineNo} (fileIndex=${d.fileIndex}) $flags")
            }
            if (!g.isEvent) println("  生效: ${g.winner.file}:${g.winner.lineNo}")
        }
    }

    if (collatorCheck) {
        // spec: Config.cs:681 culture 排序的近似性自检（ADR-006）
        val c = SymbolTable.collatorCheck(corpusDir, config)
        println()
        println("=== 比较器自检（ja-JP Collator vs ORDINAL）===")
        println("文件数 ${c.files}  绝对位置位移 ${c.shiftedFiles}（含被动位移，仅供观察）")
        c.firstDivergence?.let { println("  首个位置差异: ${corpusDir.toAbsolutePath().normalize().relativize(Path(it))}") }
        println("重名定义文件 ${c.duplicateDefFiles.size} 个，两两相对次序翻转 ${c.duplicateDefInversions} / ${c.duplicateDefPairs} 对")
        println("重名组 ${c.duplicateGroups}  定义集合/次序翻转 ${c.winnerFlips.size}  事件执行序翻转 ${c.eventOrderFlips.size}")
        if (c.winnerFlips.isNotEmpty()) println("  翻转组(定义): ${c.winnerFlips.joinToString()}")
        if (c.eventOrderFlips.isNotEmpty()) println("  翻转组(事件序): ${c.eventOrderFlips.joinToString()}")
        println("结论: ${if (c.passed) "PASS（两比较器对本语料的重名解析结论一致）" else "FAIL（需人工核对上面的翻转组）"}")
    }

    if (jsonOut != null) {
        val sb = StringBuilder()
        sb.append("{\n  \"stats\": {\"totalDefs\": ${st.totalDefs}, \"uniqueNames\": ${st.uniqueNames}, ")
        sb.append("\"duplicateNames\": ${st.duplicateNames}, \"nonEventDuplicates\": ${st.nonEventDuplicates}, ")
        sb.append("\"eventDuplicates\": ${st.eventDuplicates}, \"shadowedDefs\": ${st.shadowedDefs}, ")
        sb.append("\"methodDefs\": ${st.methodDefs}, \"collator\": \"${table.config.collator}\"},\n  \"duplicates\": [\n")
        dups.forEachIndexed { gi, g ->
            sb.append("    {\"key\": ${jstr(g.key)}, \"isEvent\": ${g.isEvent}, \"count\": ${g.defs.size}, \"defs\": [")
            g.defs.forEachIndexed { di, d ->
                if (di > 0) sb.append(", ")
                sb.append("{\"file\": ${jstr(d.file)}, \"line\": ${d.lineNo}, \"fileIndex\": ${d.fileIndex}")
                sb.append(", \"argCount\": ${d.argCount}, \"argIntCount\": ${d.argIntCount}, \"argStrCount\": ${d.argStrCount}, \"localSize\": ${d.localSize}, \"localsSize\": ${d.localsSize}")
                if (d.dimSpecs.isNotEmpty()) {
                    sb.append(", \"dimSpecs\": [")
                    d.dimSpecs.forEachIndexed { si, s ->
                        if (si > 0) sb.append(", ")
                        sb.append("{\"name\": ${jstr(s.name)}, \"isStr\": ${s.isStr}, \"dims\": [")
                        s.dims.forEachIndexed { di2, dim ->
                            if (di2 > 0) sb.append(", ")
                            sb.append(jstr(dim))
                        }
                        sb.append("], \"keywords\": [")
                        s.keywords.forEachIndexed { ki, k ->
                            if (ki > 0) sb.append(", ")
                            sb.append("\"$k\"")
                        }
                        sb.append("]")
                        if (s.initializer != null) sb.append(", \"initializer\": ${jstr(s.initializer)}")
                        sb.append("}")
                    }
                    sb.append("]")
                }
                if (d.isMethod) sb.append(", \"method\": true")
                if (d.isOnly) sb.append(", \"only\": true")
                if (d.isPri) sb.append(", \"pri\": true")
                if (d.isLater) sb.append(", \"later\": true")
                if (d.isSingle) sb.append(", \"single\": true")
                sb.append("}")
            }
            sb.append("]}")
            sb.append(if (gi == dups.size - 1) "\n" else ",\n")
        }
        sb.append("  ]\n}\n")
        Files.createDirectories(jsonOut.parent ?: Path("."))
        Files.writeString(jsonOut, sb.toString())
        println("\n符号表摘要: ${jsonOut.toAbsolutePath()}")
    }
}

// ---------------------------------------------------------------------------
// compile 子命令：全量语料 → .erbc 产物 + 确定性 manifest（specs/erbc-format.md）
// ---------------------------------------------------------------------------

/**
 * 全量编译并落盘。返回进程退出码：0 成功；1 编译失败（不落盘，stderr 逐条
 * `文件:行号`）；参数错误走 [err]（退出码 2）。
 *
 * internal（而非 private）仅供本模块测试源集直接验证退出码契约；CLI 入口仍只有 [main]。
 */
internal fun cmdCompile(args: List<String>): Int {
    var corpus: Path? = null
    var out = Path("tools/erbcc/erbc-out")
    var limit: Int? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--corpus" -> corpus = Path(args.getOrNull(++i) ?: err("--corpus 需要参数"))
            "--out" -> out = Path(args.getOrNull(++i) ?: err("--out 需要参数"))
            "--limit" -> limit = (args.getOrNull(++i) ?: err("--limit 需要参数"))
                .toIntOrNull()?.takeIf { it > 0 } ?: err("--limit 需要正整数")
            else -> err("无法识别的 compile 参数: ${args[i]}")
        }
        i++
    }
    val corpusDir = corpus ?: err("缺少 --corpus 参数")
    if (!Files.isDirectory(corpusDir)) err("corpus 不是目录: $corpusDir")
    val root = corpusDir.toAbsolutePath().normalize()
    if (limit != null) System.err.println("降级模式：只编译前 $limit 个文件（正式产物必须全量）")

    val t0 = System.currentTimeMillis()
    val result = CorpusCompile.compile(root, limit)
    val st = result.stats
    System.err.println(
        "编译：文件 ${st.corpusFiles}  切片 ${st.slices}  成功 ${st.compiled}  " +
            "失败 ${st.compileFailures}  解析失败切片 ${st.parseFailures}  事件入口 ${st.eventEntries}  " +
            "（${System.currentTimeMillis() - t0} ms）",
    )
    // ERH 装载拓扑审计（`#INCLUDE`/`#INCLUDEFROM` 行、跨文件 #DEFINE/#DIM 重名、同一文件进两次清单）。
    // 原版这些全是 level 2 中止点，erbcc 只留痕、不改退出码（口径同下一条 dimWarnings）。
    val erhIssues = ErhLoadAudit.audit(
        CorpusCompile.listCorpusFiles(root).filter { it.fileName.toString().endsWith(".erh", ignoreCase = true) },
    )
    if (erhIssues.isNotEmpty()) {
        System.err.println("ERH 装载拓扑审计 ${erhIssues.size} 条（原版 level 2，至多列 50 条）:")
        erhIssues.take(50).forEach { System.err.println("  $it") }
    }
    if (!result.ok) {
        System.err.println("编译失败 ${result.failures.size} 条（至多列 50 条，格式 文件:行号）:")
        result.failures.take(50).forEach { System.err.println("  $it") }
        return 1
    }
    // #DIM 解析告警（level 2，对齐原版 ParserMediator.Warn；spec: ErhLoader.cs:324-331、
    // LogicalLineParser.cs:303-307）。不改变退出码——原版告警同样不中断装载。
    if (result.dimWarnings.isNotEmpty()) {
        System.err.println("#DIM 解析告警 ${result.dimWarnings.size} 条（至多列 50 条）:")
        result.dimWarnings.take(50).forEach { System.err.println("  $it") }
    }
    // 装载/解析期告警（原版 ParserMediator.Warn 族；level 1，不中断装载、不改退出码）。
    // 文案与行号口径对齐 PC 基准 `.workbuddy/golden-pc-loadwarnings.txt` 的前两列
    // （`文件:行 | 文案`），见 CompileResult.warnings 的 KDoc。
    if (result.warnings.isNotEmpty()) {
        System.err.println("装载期告警 ${result.warnings.size} 条（至多列 50 条）:")
        result.warnings.take(50).forEach { System.err.println("  ${it.file}:${it.line} | ${it.message}") }
    }
    val report = ErbcArtifacts.write(out, result.program!!, root, result.sourceFiles, st, result.bootMetadata)
    println("=== 落盘完成 ===")
    println("指令 ${st.instructions}  语句 ${st.statements}  函数 ${st.functions}  " +
        "串池 ${st.strings}  常量 ${st.constants}  表达式 ${st.expressions}  " +
        "全局槽 ${st.globalSlots}  入口 ${st.entryFunction}")
    println("产物: ${report.artifactPath}（${report.artifact.bytes} 字节, sha256 ${report.artifact.sha256}）")
    println("清单: ${report.manifestPath}（源文件 ${report.sources.size} 条）")
    report.metaPath?.let { p ->
        val meta = report.meta
        println("侧车: $p（${meta?.bytes ?: 0} 字节, sha256 ${meta?.sha256 ?: "-"}）")
        println(
            "侧车内容: 函数 ${result.bootMetadata.functions.size}  标签函数 ${result.bootMetadata.labels.size}" +
                "（${result.bootMetadata.labels.values.sumOf { it.size }} 条）  数据节 ${result.bootMetadata.dataSections.size}  " +
                "全局 #DIM ${result.bootMetadata.globalDims.size}  私有 #DIM ${result.bootMetadata.privateDims.size}",
        )
    }
    return 0
}

// ---------------------------------------------------------------------------
// check 子命令：产物新鲜度（源改了没重编 ⇒ 当场检出）
// ---------------------------------------------------------------------------

/**
 * 产物新鲜度检查。返回退出码：0 = 全对齐；1 = 有新鲜度问题（逐条 stderr）；参数错误走 [err]。
 *
 * 判据全部在 [ArtifactFreshness]（可执行代码）：容器字节数/摘要/段表、每个源文件摘要、
 * `program.meta` 的 `program-sha256`、`program.stmt.idx` 的语句条数、以及**编译器指纹**
 * `compiler-digest` × 当前 `core-vm/src/main/kotlin` 源码树（清单无此行或不等 ⇒ 旧产物）。**不重编**。
 */
internal fun cmdCheck(args: List<String>): Int {
    var out = Path("tools/erbcc/erbc-out")
    var corpus: Path? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--out" -> out = Path(args.getOrNull(++i) ?: err("--out 需要参数"))
            "--corpus" -> corpus = Path(args.getOrNull(++i) ?: err("--corpus 需要参数"))
            else -> err("无法识别的 check 参数: ${args[i]}")
        }
        i++
    }
    val corpusDir = corpus ?: err("缺少 --corpus 参数")
    if (!Files.isDirectory(corpusDir)) err("corpus 不是目录: $corpusDir")
    val report = ArtifactFreshness.check(out, corpusDir)
    println(report.summary())
    return if (report.ok) 0 else 1
}

// ---------------------------------------------------------------------------
// audit 子命令：装载期符号链接审计（解析期使用点 × 运行期真实命名空间）
// ---------------------------------------------------------------------------

/**
 * 全量符号链接审计并落 TSV。返回进程退出码：0 无硬差异（或非 strict）；
 * 1 = 编译失败，或 `--strict` 下存在未被白名单放行的硬差异。
 *
 * `--roundtrip` 走另一条路（CMP-79）：把同一份语料落盘成 `program.erbc` + `program.meta`，
 * 再用**真装载链**（[ProgramLoader] + `BootMetadata.read`）重建会话，与内存产物逐字段对账；
 * 此时不写符号 TSV，改写对账明细，退出码 0 = 全绿 / 1 = 任一项不等。
 */
internal fun cmdAudit(args: List<String>): Int {
    var corpus: Path? = null
    var gameRoot: Path? = null
    var out = Path("tools/erbcc/symbol-audit/unresolved-symbols.tsv")
    var outGiven = false
    var limit: Int? = null
    var strict = false
    var allow: Path? = null
    var roundtrip = false
    var artifactDir: Path? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--corpus" -> corpus = Path(args.getOrNull(++i) ?: err("--corpus 需要参数"))
            "--game-root" -> gameRoot = Path(args.getOrNull(++i) ?: err("--game-root 需要参数"))
            "--out" -> {
                out = Path(args.getOrNull(++i) ?: err("--out 需要参数"))
                outGiven = true
            }
            "--limit" -> limit = (args.getOrNull(++i) ?: err("--limit 需要参数"))
                .toIntOrNull()?.takeIf { it > 0 } ?: err("--limit 需要正整数")
            "--strict" -> strict = true
            "--allow" -> allow = Path(args.getOrNull(++i) ?: err("--allow 需要参数"))
            "--roundtrip" -> roundtrip = true
            "--artifact-dir" -> artifactDir = Path(args.getOrNull(++i) ?: err("--artifact-dir 需要参数"))
            else -> err("无法识别的 audit 参数: ${args[i]}")
        }
        i++
    }
    val corpusDir = corpus ?: err("缺少 --corpus 参数")
    if (!Files.isDirectory(corpusDir)) err("corpus 不是目录: $corpusDir")
    val root = corpusDir.toAbsolutePath().normalize()
    val game = (gameRoot ?: root.parent)?.toAbsolutePath()?.normalize()
        ?: err("无法从 corpus 推断游戏根，请显式传 --game-root")
    if (!Files.isDirectory(game.resolve("CSV"))) {
        err("游戏根缺少 CSV 目录: $game/CSV（关键字表与角色模板都从这里来，缺表时审计会满屏假红）")
    }
    if (limit != null) System.err.println("降级模式：只审计前 $limit 个文件（正式闸必须全量）")

    if (roundtrip) return cmdAuditRoundTrip(root, game, artifactDir, if (outGiven) out else null, limit)

    val report = SymbolLinkAudit.audit(root, game, limit)
    val st = report.result.stats
    println("=== 符号链接审计 ===")
    println(
        "编译：文件 ${st.corpusFiles}  切片 ${st.slices}  成功 ${st.compiled}  " +
            "失败 ${st.compileFailures}  解析失败切片 ${st.parseFailures}  指令 ${st.instructions}" +
            "（${report.elapsedMs} ms）",
    )
    if (!report.result.ok) {
        System.err.println("编译失败 ${report.result.failures.size} 条（至多列 50 条）:")
        report.result.failures.take(50).forEach { System.err.println("  $it") }
        return 1
    }
    println(
        "符号使用：语句 ${report.statementsWalked}  VarRef ${report.varRefsSeen} 次 / " +
            "${report.varUseSites} 处  关键字 ${report.keywordRefsSeen} 次 / ${report.keywordUseSites} 处" +
            "（静态 ${report.keywordUseSites - report.dynamicKeywordSites} / 动态 ${report.dynamicKeywordSites}）",
    )
    println(
        "运行期命名空间：全局用户变量 ${report.runtimeGlobals}  注册私有 #DIM ${report.runtimePrivateVars}  " +
            "系统变量 ${report.runtimeSystemVars}",
    )

    val allowed = allow?.let { readAllowList(it) } ?: AllowList.EMPTY
    val blocking = report.blocking.filterNot { allowed.matches(it) }
    val suppressed = report.blocking.size - blocking.size

    val redKinds = FindingKind.entries.filter { it.blocking && it != FindingKind.COMPILE_DIM_WARNING }
    println()
    println("硬差异：${blocking.size} 处" + if (suppressed > 0) "（另有 $suppressed 处按白名单放行）" else "")
    for (kind in redKinds) {
        val list = blocking.filter { it.kind == kind }
        if (list.isEmpty()) continue
        println("  ${kindLabel(kind)} ${list.size} 处")
        printGroups(list, indent = "    ", limit = 15)
    }
    val warnings = report.findings.filter { it.kind == FindingKind.COMPILE_DIM_WARNING }
    if (warnings.isNotEmpty()) {
        println("编译期 #DIM 告警（level 2，非红）：${warnings.size} 处")
        printGroups(warnings, indent = "    ", limit = 10)
    }
    report.writeTsv(out)
    println()
    println("明细：${out.toAbsolutePath().normalize()}（${report.findings.size} 行）")
    if (strict && blocking.isNotEmpty()) {
        System.err.println("审计未通过：--strict 下仍有 ${blocking.size} 处硬差异。")
        return 1
    }
    println("结论：" + if (blocking.isEmpty()) "PASS（解析期使用点全部落在运行期命名空间内）" else "报告模式（加 --strict 可作闸）")
    return 0
}

// ---------------------------------------------------------------------------
// audit --roundtrip：落盘后再装载对账（CMP-79）
// ---------------------------------------------------------------------------

/**
 * `audit --roundtrip` 的 CLI 面。
 *
 * 判据全在 [SymbolLinkAudit.roundTrip] 里；本函数只负责打印与退出码：任何一项读数不等
 * 即 1（对账本身是闸，不另设 `--strict`），并保留产物目录供复现。
 */
private fun cmdAuditRoundTrip(
    corpusRoot: Path,
    game: Path,
    artifactDir: Path?,
    out: Path?,
    limit: Int?,
): Int {
    val report = try {
        SymbolLinkAudit.roundTrip(corpusRoot, game, artifactDir, limit)
    } catch (e: IllegalStateException) {
        System.err.println("对账失败：${e.message}")
        return 1
    }
    println("=== 落盘后再装载对账（CMP-79）===")
    println("产物：${report.artifactPath}")
    println("侧车：${report.metaPath}")
    println("使用点名字集合：${report.nameSetSize} 个名字（sha256 ${report.nameSetHash}）")
    println("对拍 ${report.checks.size} 项（${report.elapsedMs} ms）：")
    for (c in report.checks) {
        val mark = if (c.equal) "OK  " else "DIFF"
        println("  $mark  ${c.name}\t内存=${c.inMemory}\t装载=${c.reloaded}")
        if (!c.equal && c.detail.isNotEmpty()) println("        ${c.detail}")
    }
    val outPath = out ?: Path("tools/erbcc/symbol-audit/roundtrip.tsv")
    report.writeTsv(outPath)
    println("明细：${outPath.toAbsolutePath().normalize()}（${report.checks.size} 行）")
    if (!report.ok) {
        System.err.println(
            "对账未通过：${report.mismatches.size} 项不等（${report.mismatches.joinToString("、") { it.name }}）；" +
                "产物保留在 ${report.artifactPath.parent}",
        )
        return 1
    }
    println("结论：PASS（内存产物与落盘再装载逐字段一致）")
    return 0
}

private fun kindLabel(kind: FindingKind): String = when (kind) {
    FindingKind.VAR_UNBOUND -> "未绑定变量"
    FindingKind.VAR_TYPE_MISMATCH -> "变量类型不符"
    FindingKind.VAR_DIM_MISMATCH -> "变量维度不符"
    FindingKind.KEYWORD_UNBOUND -> "未绑定关键字"
    FindingKind.KEYWORD_USER_VAR -> "用户变量关键字缺 ERD 表"
    FindingKind.PRIVATE_DIM_REJECTED -> "私有 #DIM 注册被拒"
    FindingKind.GLOBAL_DIM_REJECTED -> "全局 #DIM 安装被拒"
    FindingKind.COMPILE_DIM_WARNING -> "编译期 #DIM 告警"
}

private fun printGroups(list: List<Finding>, indent: String, limit: Int) {
    val groups = list.groupBy { it.name }
        .toList()
        .sortedWith(compareByDescending<Pair<String, List<Finding>>> { p -> p.second.sumOf { it.occurrences } }
            .thenByDescending { p -> p.second.size }
            .thenBy { p -> p.first })
    for ((name, rows) in groups.take(limit)) {
        val first = rows.first()
        val sites = rows.size
        val occurrences = rows.sumOf { it.occurrences }
        val loc = buildString {
            if (first.file.isNotEmpty()) append(first.file)
            if (first.line >= 0) append(':').append(first.line)
            if (first.functionKey.isNotEmpty()) append(" @").append(first.functionKey)
        }
        println("$indent${sites} 处 / ${occurrences} 次  $name" + if (loc.isNotEmpty()) "  （例：$loc）" else "")
        val detail = first.detail.take(160)
        if (detail.isNotEmpty()) println("$indent        $detail")
    }
    if (groups.size > limit) println("$indent… 另 ${groups.size - limit} 个名字未列出")
}

/** `--allow` 白名单：`kind<TAB>name` 或 `kind<TAB>functionKey<TAB>name`，`#` 开头为注释。 */
private class AllowList(
    private val byName: Set<String>,
    private val byFunction: Set<String>,
) {
    fun matches(f: Finding): Boolean =
        "${f.kind.name}\t${f.name}" in byName ||
            "${f.kind.name}\t${f.functionKey}\t${f.name}" in byFunction

    companion object {
        val EMPTY = AllowList(emptySet(), emptySet())
    }
}

private fun readAllowList(path: Path): AllowList {
    if (!path.isRegularFile()) {
        System.err.println("白名单文件不存在（按空白名单处理）: $path")
        return AllowList.EMPTY
    }
    val byName = HashSet<String>()
    val byFunction = HashSet<String>()
    for (raw in path.readText().split('\n', '\r')) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val cols = line.split('\t')
        when (cols.size) {
            2 -> byName.add("${cols[0]}\t${cols[1]}")
            3 -> byFunction.add("${cols[0]}\t${cols[1]}\t${cols[2]}")
            else -> System.err.println("白名单行格式不对（跳过）: $line")
        }
    }
    return AllowList(byName, byFunction)
}

/** spec: ParserMediator.LoadEraExRenameFile —— `置換後 , [[置換前]]`，逗号恰两列 */
private fun loadRenameDic(file: Path): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (line in file.readText().split('\n', '\r')) {
        val t = line.trimStart('\uFEFF').trim()
        if (t.isEmpty() || t.startsWith(";")) continue
        val parts = t.split(',')
        if (parts.size == 2) {
            val key = "[[${parts[1].trim()}]]"
            map[key] = parts[0].trim()
        }
    }
    return map
}

// ---------------------------------------------------------------------------
// 扫描核心
// ---------------------------------------------------------------------------

/** 诊断的分类（判定「原版同样报错」还是「待查」） */
private const val ROUTE_LABEL = "label"              // @/$ 行解析
private const val ROUTE_SHARP = "sharp"             // # 行解析
private const val ROUTE_CONT = "continuation"       // {} 块 / 预处理
private const val ROUTE_PLUSMINUS = "plusminus"     // +/- 开头非自增减（原版 InvalidLine）
private const val ROUTE_FIRST_IDENT = "first-ident" // 行首非标识符（原版 InvalidLine）
private const val ROUTE_INSTR_TAIL = "instr-tail"   // 指令后跟非法字符（原版 InvalidLine）
private const val ROUTE_ASSIGN = "assign"           // 赋值两段式（原版 CodeEE→InvalidLine）

private class Diag(val lineNo: Int, val route: String, val msg: String)

/** 单文件扫描结果 */
private class FileResult(val relPath: String) {
    var binary = false
    var encoding = "UTF-8"          // UTF-8 / Shift-JIS
    var physicalLines = 0
    var logicalLines = 0
    var tokens = 0
    var stmtLines = 0               // 指令行（参数延后 M2）
    var decodeReplacement = false   // Shift-JIS 解码后仍含 U+FFFD
    val errors = mutableListOf<Diag>()
    val warnings = mutableListOf<LexWarning>()
}

/** 全量扫描报告 */
private class ScanReport {
    val files = mutableListOf<FileResult>()
    var diskCount = 0
    var indexCount = 0
    var indexMissingOnDisk = 0
    var diskNotInIndex = 0
    var indexRelSet: Set<String> = emptySet()
}

/** 文件集策略 */
private enum class Strategy { ALL, INDEX, UNION, ENGINE }private class ScanRunner(
    private val corpusDir: Path,
    private val indexFile: Path?,
    private val renameDic: Map<String, String>,
) {

    fun run(): ScanReport {
        val report = ScanReport()

        val diskFiles = walkDisk()
        report.diskCount = diskFiles.size

        val indexRelPaths = indexFile?.let { readIndex(it) } ?: emptyList()
        val indexFiles = indexRelPaths.mapNotNull { rel ->
            val p = corpusDir.resolve(rel)
            if (p.isRegularFile()) p else null
        }
        report.indexCount = indexRelPaths.size
        report.indexMissingOnDisk = indexRelPaths.size - indexFiles.size
        val indexSet = indexFiles.map { it.toAbsolutePath().normalize() }.toSet()
        report.diskNotInIndex = diskFiles.count { it.toAbsolutePath().normalize() !in indexSet }
        report.indexRelSet = indexFiles.map {
            corpusDir.toAbsolutePath().normalize().relativize(it.toAbsolutePath().normalize())
                .toString().replace('\\', '/')
        }.toSet()

        val allFiles = LinkedHashSet(diskFiles.map { it.toAbsolutePath().normalize() })
        for (p in indexFiles) allFiles.add(p.toAbsolutePath().normalize())

        System.err.println("磁盘文件: ${diskFiles.size}  索引文件: ${indexFiles.size}/${indexRelPaths.size}  并集: ${allFiles.size}")

        var done = 0
        val macroNames = collectMacroNames(allFiles)
        for (p in allFiles) {
            val rel = corpusDir.toAbsolutePath().normalize()
                .relativize(p).toString().replace('\\', '/')
            val r = FileResult(rel)
            try {
                scanFile(p, r, macroNames)
            } catch (e: Exception) {
                r.errors.add(Diag(-1, "scanner", "扫描器内部异常: ${e::class.simpleName}: ${e.message}"))
            }
            report.files.add(r)
            done++
            if (done % 500 == 0) System.err.println("  ... $done/${allFiles.size}")
        }
        return report
    }

    /**
     * ERH `#DEFINE` 宏名（大写归一）—— `[IF 宏]` / `[ELSEIF 宏]` 判定用
     * （spec: ErbLoader.cs:94/109；ERH 全量先于 ERB 装载 :40-75，故先扫一遍）。
     * 口径与 `CorpusScan.scan` 的宏名收集同源：`#DEFINE 名 [体]`，名 = 首个空白前 token。
     */
    private fun collectMacroNames(files: Collection<Path>): Set<String> {
        val out = HashSet<String>()
        for (p in files) {
            if (!p.fileName.toString().endsWith(".erh", ignoreCase = true)) continue
            val lines = try {
                LogicalLineReader.readAllLines(Files.readAllBytes(p))
            } catch (e: Exception) {
                continue
            }
            for (raw in lines) {
                val line = raw.trim()
                if (!line.startsWith("#")) continue
                val body = line.substring(1).trim()
                val kw = body.takeWhile { !it.isWhitespace() }.uppercase(Locale.ROOT)
                if (kw == "DEFINE") {
                    val name = body.substring(kw.length).trim().takeWhile { !it.isWhitespace() }
                    if (name.isNotEmpty()) out.add(name.uppercase(Locale.ROOT))
                }
            }
        }
        return out
    }

    private fun walkDisk(): List<Path> {
        if (!Files.isDirectory(corpusDir)) return emptyList()
        Files.walk(corpusDir).use { stream ->
            return stream.filter { it.isRegularFile() }
                .filter { p -> p.name.endsWith(".erb", ignoreCase = true) || p.name.endsWith(".erh", ignoreCase = true) }
                .sorted()
                .toList()
        }
    }

    /** lazyloadingfiles.dat：每行 `相对路径<TAB>FILETIME`，路径相对 corpus 目录，'\' 分隔 */
    private fun readIndex(file: Path): List<String> {
        val text = file.readText()
        return text.split('\n', '\r')
            .map { it.trimStart('\uFEFF') }
            .filter { it.isNotBlank() }
            .map { it.substringBefore('\t').replace('\\', '/') }
            .filter { it.endsWith(".erb", ignoreCase = true) || it.endsWith(".erh", ignoreCase = true) }
    }

    // -- 编码探测（与 LogicalLineReader.readAllLines 一致的 UTF-8 严格校验 → Shift-JIS 回退）--
    private fun detectEncoding(bytes: ByteArray): Pair<String, Boolean> {
        val hasBom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(body))
            "UTF-8" to hasBom
        } catch (e: CharacterCodingException) {
            val text = String(body, charset("Shift_JIS"))
            "Shift-JIS" to text.contains('\uFFFD')
        }
    }

    private fun scanFile(path: Path, r: FileResult, macroNames: Set<String>) {
        val bytes = Files.readAllBytes(path)

        // 二进制探测：出现 NUL 字节即按二进制归类，不做词法分析
        if (bytes.indexOf(0) in 0 until minOf(bytes.size, 8192)) {
            r.binary = true
            r.encoding = detectEncoding(bytes).first
            return
        }

        val (enc, replacement) = detectEncoding(bytes)
        r.encoding = enc
        r.decodeReplacement = replacement

        val physical = LogicalLineReader.readAllLines(bytes)
        r.physicalLines = physical.size

        // TODO(verify): 本装置（ScanRunner，cmdScan 链路）不读游戏根（scan 子命令无
        // --game-root），没有可喂 LexerConfig.fromConfigStore 的 ConfigStore ——
        // reader 仍吃 LexerConfig 出厂默认（SystemAllowFullSpace=true）。
        // 2026-10-26 fixcore 波 A1 未接线，留待组长定补源方案。
        val reader = LogicalLineReader(
            physical,
            r.relPath,
            renameDic = renameDic.ifEmpty { null },
            macroNames = macroNames,
        )
        val lexer = ErbLexer()
        while (true) {
            val line = try {
                reader.readLogicalLine() ?: break
            } catch (e: LexError) {
                r.errors.add(Diag(e.lineNo, ROUTE_CONT, e.message ?: ""))
                continue
            }
            r.logicalLines++
            try {
                when (line.kind) {
                    LineKind.CODE -> routeCodeLine(lexer, line.content, line.lineNo, r)
                    LineKind.FUNCTION_LABEL, LineKind.GOTO_LABEL -> {
                        lexer.parseLabelLine(line.content, line.lineNo, line.filename)
                    }
                    LineKind.SHARP -> lexer.parseSharpLine(line.content, line.lineNo, line.filename)
                }
            } catch (e: LexError) {
                val route = when (line.kind) {
                    LineKind.SHARP -> ROUTE_SHARP
                    LineKind.CODE -> "unrouted" // routeCodeLine 内部已捕获，不应到达
                    else -> ROUTE_LABEL
                }
                r.errors.add(Diag(e.lineNo, route, e.message ?: ""))
            }
        }
        try {
            reader.fileEnd()
        } catch (_: LexError) {
        }
        r.warnings += reader.warnings
        r.warnings += lexer.warnings
    }

    /**
     * 复刻 LogicalLineParser.ParseLine（spec: LogicalLineParser.cs:385-570）第一级分派。
     * 路径上抛出的 LexError 与原版 CodeEE 等价（原版捕获后记 InvalidLine）。
     */
    private fun routeCodeLine(lexer: ErbLexer, content: String, lineNo: Int, r: FileResult) {
        if (content.isEmpty()) return // {} 块合并后全空白：原版 ParseLine 的 SkipWhiteSpace+EOS 返回 null
        var route = ROUTE_ASSIGN
        try {
            // + / - 开头：仅 ++/-- 合法 —— spec: LogicalLineParser.cs:397-410
            val first = content[0]
            if (first == '+' || first == '-') {
                route = ROUTE_PLUSMINUS
                val st = CharStream(content)
                val tokens = lexer.analyse(st, LexEndWith.EoL, LexAnalyzeFlag.NONE, lineNo)
                r.tokens += tokens.size
                val op = (tokens.firstOrNull() as? Token.Operator)?.code
                if (op == OperatorCode.Increment || op == OperatorCode.Decrement) {
                    r.stmtLines++
                } else {
                    r.errors.add(Diag(lineNo, route, "行首为'$first'但不是自增/自减（原版 InvalidLine）"))
                }
                return
            }

            // 行首标识符 —— spec: LogicalLineParser.cs:412-414 ReadFirstIdentifierWord
            val id = lexer.readSingleIdentifier(CharStream(content))
            if (id.isEmpty()) {
                r.errors.add(Diag(lineNo, ROUTE_FIRST_IDENT, "不正な文字で行が始まっています"))
                return
            }

            // 指令/方法命中 → 参数延后语句层 —— spec: LogicalLineParser.cs:415-475
            if (id.uppercase(Locale.ROOT) in ERB_INSTRUCTIONS) {
                route = ROUTE_INSTR_TAIL
                val next = CharStream(content).let { st ->
                    lexer.readSingleIdentifier(st)
                    if (!st.eos) st.current else CharStream.END_OF_STRING
                }
                if (next == CharStream.END_OF_STRING || next == ';' || next == ' ' || next == '\t' || next == '\u3000') {
                    r.stmtLines++
                } else {
                    r.errors.add(Diag(lineNo, route, "指令'$id'之后跟随非法字符（原版 InvalidLine）"))
                }
                return
            }

            // 赋值两段式 —— spec: LogicalLineParser.cs:536-556
            route = ROUTE_ASSIGN
            val st = CharStream(content)
            r.tokens += lexer.analyse(st, LexEndWith.Operator, LexAnalyzeFlag.NONE, lineNo).size
            lexer.readAssignmentOperator(st, lineNo)
        } catch (e: LexError) {
            r.errors.add(Diag(if (e.lineNo >= 0) e.lineNo else lineNo, route, e.message ?: ""))
        }
    }

    private fun ByteArray.indexOf(b: Byte): Int {
        for (i in indices) if (this[i] == b) return i
        return -1
    }
}

// ---------------------------------------------------------------------------
// 报告输出
// ---------------------------------------------------------------------------

private class StrategyAgg(val name: String) {
    var files = 0
    var errors = 0
    var warnings = 0
    var logicalLines = 0L
    var tokens = 0L
    var stmtLines = 0L
    var binary = 0
}

private fun printSummary(report: ScanReport) {
    val (all, index, union, engine) = aggregate(report)
    println()
    println("=== 策略对比 ===")
    println("策略     文件数   二进制  逻辑行        token        指令行        异常   警告")
    for (s in listOf(all, index, union, engine)) {
        println(
            "%-7s  %6d  %6d  %12d  %12d  %12d  %5d  %5d".format(
                s.name, s.files, s.binary, s.logicalLines, s.tokens, s.stmtLines, s.errors, s.warnings
            )
        )
    }
    println()
    println("磁盘不在索引内的文件: ${report.diskNotInIndex}   索引内缺失于磁盘: ${report.indexMissingOnDisk}")
    println("编码统计: " + report.files.groupingBy { it.encoding }.eachCount())
}

/** 按四种策略聚合（同一文件的异常在各策略内重复计数） */
private fun aggregate(report: ScanReport): StrategyAggs {
    val all = StrategyAgg("all")
    val index = StrategyAgg("index")
    val union = StrategyAgg("union")
    val engine = StrategyAgg("engine")
    fun aggInto(s: StrategyAgg, f: FileResult) {
        s.files++
        s.errors += f.errors.size
        s.warnings += f.warnings.size
        s.logicalLines += f.logicalLines
        s.tokens += f.tokens
        s.stmtLines += f.stmtLines
        if (f.binary) s.binary++
    }
    val idx = report.indexRelSet
    for (f in report.files) {
        aggInto(all, f)
        aggInto(union, f)
        if (f.relPath in idx) aggInto(index, f)
        // engine（报告参考用，非最终编译输入）：急切装载集的近似 = 磁盘 - 索引。
        // 定稿口径：erbcc 编译输入 = 全量目录扫描（all），索引内文件运行期懒装载仍会完整解析
        // （spec: ErbLoader.cs:256 !LazyLoadingFiles.Contains()、Process.cs:711、Process.cs:801-870）。
        if (f.relPath !in idx) aggInto(engine, f)
    }
    return StrategyAggs(all, index, union, engine)
}

private data class StrategyAggs(val all: StrategyAgg, val index: StrategyAgg, val union: StrategyAgg, val engine: StrategyAgg)

private fun writeReports(report: ScanReport, outDir: Path) {
    Files.createDirectories(outDir)

    val json = StringBuilder()
    json.append("{\n")

    // 异常按「消息+路由」分组（先写，便于阅读）
    json.append("  \"errorGroups\": [\n")
    appendGroups(json, report.files.flatMap { f -> f.errors.map { f.relPath to it } })
    json.append("  ],\n")

    json.append("  \"warningGroups\": [\n")
    appendWarningGroups(json, report.files.flatMap { f -> f.warnings.map { f.relPath to it } })
    json.append("  ],\n")

    // TOP 50 出错文件
    json.append("  \"topErrorFiles\": [\n")
    val errFiles = report.files.filter { it.errors.isNotEmpty() }
        .sortedWith(compareByDescending<FileResult> { it.errors.size }.thenBy { it.relPath })
        .take(50)
    errFiles.forEachIndexed { i, f ->
        json.append("    {\"file\": ${jstr(f.relPath)}, \"errors\": ${f.errors.size}, \"warnings\": ${f.warnings.size}}")
        json.append(if (i == errFiles.size - 1) "\n" else ",\n")
    }
    json.append("  ],\n")

    // 文件明细
    json.append("  \"files\": [\n")
    val total = report.files.size
    report.files.forEachIndexed { fi, f ->
        json.append("    {\"file\": ${jstr(f.relPath)}, \"encoding\": \"${f.encoding}\"")
        json.append(", \"binary\": ${f.binary}")
        json.append(", \"physicalLines\": ${f.physicalLines}, \"logicalLines\": ${f.logicalLines}, \"tokens\": ${f.tokens}, \"stmtLines\": ${f.stmtLines}")
        if (f.decodeReplacement) json.append(", \"decodeReplacement\": true")
        json.append(", \"errors\": ${f.errors.size}, \"warnings\": ${f.warnings.size}")
        if (f.errors.isNotEmpty() || f.warnings.isNotEmpty()) {
            json.append(", \"diagnostics\": [")
            val diags = (f.errors.map { Triple(it.lineNo, "ERROR", "${it.route}|${it.msg}") } +
                f.warnings.map { Triple(it.lineNo, "WARN", it.message) })
                .sortedBy { it.first }
            diags.forEachIndexed { di, (lineNo, level, msg) ->
                if (di > 0) json.append(", ")
                json.append("{\"line\": $lineNo, \"level\": \"$level\", \"msg\": ${jstr(msg)}}")
            }
            json.append("]")
        }
        json.append("}")
        if (fi != total - 1) json.append(",")
        json.append("\n")
    }
    json.append("  ]\n")
    json.append("}\n")

    val outFile = outDir.resolve("report.json")
    Files.writeString(outFile, json.toString())
    println("报告已写入: ${outFile.toAbsolutePath()}")

    // TSV 摘要：每文件一行
    val tsv = StringBuilder("file\tencoding\tbinary\tphysicalLines\tlogicalLines\ttokens\tstmtLines\terrors\twarnings\n")
    for (f in report.files) {
        tsv.append(f.relPath).append('\t').append(f.encoding).append('\t')
            .append(if (f.binary) 1 else 0).append('\t')
            .append(f.physicalLines).append('\t').append(f.logicalLines).append('\t')
            .append(f.tokens).append('\t').append(f.stmtLines).append('\t')
            .append(f.errors.size).append('\t').append(f.warnings.size).append('\n')
    }
    val tsvFile = outDir.resolve("files.tsv")
    Files.writeString(tsvFile, tsv)
    println("文件清单已写入: ${tsvFile.toAbsolutePath()}")
}

private fun appendGroups(json: StringBuilder, items: List<Pair<String, Diag>>) {
    data class Diag2(val file: String, val lineNo: Int, val route: String, val msg: String)
    val ds = items.map { (f, d) -> Diag2(f, d.lineNo, d.route, d.msg) }
    val groups = ds.groupBy { "${it.route}|${it.msg}" }
    groups.entries.sortedByDescending { it.value.size }.forEachIndexed { gi, (key, list) ->
        val route = list[0].route
        val msg = list[0].msg
        json.append("    {\"route\": ${jstr(route)}, \"msg\": ${jstr(msg)}, \"count\": ${list.size}, \"examples\": [")
        list.take(5).forEachIndexed { i, d ->
            if (i > 0) json.append(", ")
            json.append("{\"file\": ${jstr(d.file)}, \"line\": ${d.lineNo}}")
        }
        json.append("]}")
        json.append(if (gi == groups.size - 1) "\n" else ",\n")
    }
}

private fun appendWarningGroups(json: StringBuilder, items: List<Pair<String, LexWarning>>) {
    data class W(val file: String, val lineNo: Int, val msg: String)
    val ds = items.map { (f, w) -> W(f, w.lineNo, w.message) }
    val groups = ds.groupBy { it.msg }
    groups.entries.sortedByDescending { it.value.size }.forEachIndexed { gi, (msg, list) ->
        json.append("    {\"msg\": ${jstr(msg)}, \"count\": ${list.size}, \"examples\": [")
        list.take(5).forEachIndexed { i, d ->
            if (i > 0) json.append(", ")
            json.append("{\"file\": ${jstr(d.file)}, \"line\": ${d.lineNo}}")
        }
        json.append("]}")
        json.append(if (gi == groups.size - 1) "\n" else ",\n")
    }
}

private fun jstr(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append("\"")
    return sb.toString()
}
