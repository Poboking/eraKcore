package com.eratw.port.boot

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.CallDefaultsCollector
import com.eratw.port.core.compile.CompileException
import com.eratw.port.core.compile.DimSpecLayout
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.GlobalNameLayout
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.parseBoolLiteral
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.stmt.CorpusValidator
import com.eratw.port.core.stmt.DataListStmt
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.EndBlockStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.ParamDefault
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.StmtResolver
import com.eratw.port.core.vars.DimFoldSnapshot
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.erbcc.LabelDef
import com.eratw.port.erbcc.SymbolTable
import com.eratw.port.erbcc.Symbols
import com.erbcc.bridge.buildExprCallTable
import com.erbcc.bridge.buildFuncDims
import com.erbcc.bridge.sizeConsts
import com.erbcc.bridge.toLocalDim
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap

/** 一条顶层（函数外）`#DIM/#DIMS` 原文，运行期注册前才解析（需要 ERH 常量表）。 */
class GlobalDimDecl(val raw: String, val file: String, val line: Int)

/** 编译规模读数（写报告）。 */
class BootStats(
    val corpusFiles: Int,
    val erbFiles: Int,
    val erhFiles: Int,
    val loadedFiles: Int,
    val totalDefs: Int,
    val uniqueNames: Int,
    val duplicateNames: Int,
    val eventNames: Int,
    val slices: Int,
    val compiled: Int,
    val compileFailures: Int,
    val parseFailures: Int,
    val eventEntries: Int,
    val instructions: Int,
    val statements: Int,
    val globalVarDecls: Int,
    val globalVarParseFailures: Int,
    val privateVarDecls: Int,
    val privateVarParseFailures: Int,
    val elapsedMs: Long,
)

/**
 * 全量真实语料编译产物。
 *
 * [program] 为 null 表示 [ErbProgramCompiler.build] 失败（[buildError] 给全文）；
 * 此时其余统计仍有效，装置只报告「编译规模 + 构建失败」，不伪造 begin 结果。
 */
class BootModel(
    val corpus: RealCorpus,
    val program: Program?,
    val buildError: String?,
    val table: FunctionTable,
    val labelPcs: Map<String, Map<String, Int>>,
    val dataSections: IdentityHashMap<Statement, List<List<Expr>>>,
    /** 函数键（= 编译时 sigKey）→ 源文件相对路径，定位首错用。 */
    val fileBySigKey: Map<String, String>,
    val globalDimDecls: List<GlobalDimDecl>,
    val globalVarDecls: List<DimSpec>,
    val userVarDecls: List<Pair<String, DimSpec>>,
    val compileFailures: List<String>,
    val parseFailures: List<String>,
    val dimParseFailures: List<String>,
    /**
     * 装置为 `#DIM` 解析准备的配置（`#DEFINE` 宏表 + erbcc 收集的尺寸常量
     * + 顶层 CONST 声明的不动点折叠结果，见 [RealCorpusCompile.foldDeclarationConstants]）。
     * 运行期注册（[SystemVariableBootstrap.install]）必须用**同一份**，否则装置
     * 预解析读数与运行期读数会因宏表不同而对不上。
     */
    val dimParseConfig: DimParseConfig,
    /**
     * 装载期折叠面的**一次快照**（varSizes + GETNUM 关键字表 + GETLINESTR 条宽）。
     *
     * [dimParseConfig.functionFolder] 就是这份快照的折叠面（经 [DimFoldSnapshot.apply]
     * 挂上、随 `copy` 透传）：装置预解析、erbcc 侧车（`CorpusCompile.compile` 自建同参
     * 快照）、GameBootSmoke 交给 install 的 cfg 三处消费点同源。暴露出来供运行期备注
     * 自证非空转（varSizes 项数 / 关键字表槽数 / 条宽），不参与任何发射。
     */
    val foldSnapshot: DimFoldSnapshot,
    /**
     * 本次切片实际喂给 [LineParser] 的解析期配置（M11-E bootcfg 遗留项④）。
     * 缺省 [ParserConfig]（三键全出厂值）= 与旧装置口径逐字节一致；
     * 调用方（[GameBootSmoke]/[BootPlayChain]）经 [parserConfigFromOverrides] 从
     * `emuera.config` 覆盖表取值构造，与运行期/配置链路同源。
     */
    val parserConfig: ParserConfig = ParserConfig(),
    val notes: List<String>,
    val stats: BootStats,
)

/**
 * 真实语料 → 单一 [Program] 的全量编译（headless 开局冒烟装置）。
 *
 * 与 `GoldenPipeline.build` 同一条链路（符号表装载序 → 语句切片 → `ErbProgramCompiler`
 * → `FunctionTable`/标签表/数据节），差异只在「尽量不阻断」：
 * - 解析失败切片记录后进**空体桩**（不丢函数名，`_CALL` 仍可链接）；
 * - 编译失败函数同样落空体桩并记录；
 * - `build()` 遇到语料里本来就未定义的 `_CALL` 目标时，现场补 0 参空体桩后重试
 *   （原版这类 Call 只在执行到该行时才报错，属语料/死代码，不应当阻断开局）。
 *
 * 纪律：不修改语料、不缩小全量清单（`ERATW_BOOT_LIMIT` 是 OOM 降级开关，报告会注明）。
 */
object RealCorpusCompile {

    private class Slice(
        val rel: String,
        val keyUpper: String,
        val line: Int,
        val params: List<String>,
        /** 形参项原文（含 `:n` 下标；逐位绑定用，见 `FuncLabelStmt.paramRefs`）。 */
        val paramRefs: List<String>,
        /** 逐位缺省实参默认值（编译期补位用，见 `FuncLabelStmt.paramDefaults`）。 */
        val paramDefaults: List<ParamDefault?> = emptyList(),
        val stmts: List<Statement>,
        val dimLines: List<String>,
        /** 本切片内有解析失败行 → 结构不可信，只进空体桩。 */
        val poisoned: Boolean,
    )

    private class Unit(
        val sigKey: String,
        val def: LabelDef,
        val slice: Slice?,
    )

    /**
     * @param systemSaveInBinary `USE THE BINARY FORMAT FOR SAVING DATA`（spec: ConfigData.cs:107，
     * 判定 `UserDefinedVariableData.cs:465-475`）。必须由调用方（[GameBootSmoke]）从 `emuera.config`
     * 取一次值传进来，与本装置运行期的注册判定同源 —— 否则报告「装置预解析」栏与运行期读数对不上。
     * @param parserConfig 解析期三键（`CompatiRAND` / `SystemNoTarget` / `SystemIgnoreStringSet`，
     * M11-E bootcfg 遗留项④）。调用方经 [parserConfigFromOverrides] 从 `configOverrides`
     * 取值构造，与 [systemSaveInBinary] 的取数同层同源（同一个覆盖表快照）。
     * 缺省 [ParserConfig] = 三条出厂 false（spec: ConfigData.cs:98/:112/:113），
     * 语料现值（`emuera.config:52/:65/:66` 全 NO）即此形态 ⇒ 既有切片读数零回退。
     * @param sliceFilter 只为聚焦取证：非空时**只切片**满足条件的文件（其余定义仍按
     *   [BootModel.table] 落空体桩，符号表/prescan/常量仍走全量口径）。用于「只编译
     *   `ABL/ABL.ERB` + `ABL_UP_DATA.ERB` 两条真实切片」这类最小实证 —— 全量切片在
     *   test worker 的 3g 堆下代价过高。缺省 null = 全量切片，生产口径不变。
     */
    fun compile(
        corpus: RealCorpus,
        fileLimit: Int? = null,
        systemSaveInBinary: Boolean = false,
        parserConfig: ParserConfig = ParserConfig(),
        sliceFilter: ((Path) -> Boolean)? = null,
    ): BootModel {
        val t0 = System.currentTimeMillis()
        val notes = ArrayList<String>()
        val compileFailures = ArrayList<String>()
        val parseFailures = ArrayList<String>()
        val dimParseFailures = ArrayList<String>()
        val globalDimDecls = ArrayList<GlobalDimDecl>()

        val allFiles = corpus.files
        val files = if (fileLimit != null && fileLimit in 1 until allFiles.size) {
            notes.add(
                "**降级模式**：只切片前 ${fileLimit}/${allFiles.size} 个文件（ERATW_BOOT_LIMIT）。" +
                    "未装载文件的函数仍由符号表登记为空体桩，可被 `_CALL` 链接但无行为。",
            )
            allFiles.take(fileLimit)
        } else {
            allFiles
        }

        // 1) 全量符号表（装载序 = SymbolTable.loadOrder；含 ERH 宏/常量）
        val symbolTable = SymbolTable.build(corpus.root)
        val allDefs = symbolTable.nameKeys.flatMap { symbolTable.defsOf(it) }.sortedBy { it.defIndex }
        val stats0 = runCatching { symbolTable.stats() }.getOrNull()
        val eventNames = symbolTable.nameKeys.count { k ->
            val ds = symbolTable.defsOf(k)
            ds.isNotEmpty() && ds.first().isEvent
        }

        // 2) 预扫描：宏名/私有变量/函数返回类型（口径 = CorpusValidator，含 ERH）
        val pre = CorpusValidator.prescan(allFiles)
        val methodNames = pre.funcTypes.keys.mapTo(HashSet()) { it.uppercase() }
        val exprCalls = buildExprCallTable(allDefs, pre.funcTypes)
        val renameDic = CorpusValidator.loadRenameDic(corpus.root)

        // 3) 语句切片：整轮共用一个 LineParser/ScopedResolver（A13 口径）
        val slices = ArrayList<Slice>()
        // #DEFINE 宏与生产 erbcc / CorpusValidator 同一份词法层展开。
        // 解析期三键（ParserConfig）经 [sliceLineParser] 缝进构造（M11-E bootcfg 遗留项④：
        // 此前装置不传 config，boot 侧口径与生产/配置链路不一致）。
        val parser = sliceLineParser(
            CorpusValidator.ScopedResolver(pre),
            pre.macros,
            parserConfig,
        )
        if (parserConfig != ParserConfig()) {
            notes.add(
                "解析期配置（ParserConfig）非出厂：CompatiRAND=${parserConfig.compatiRAND} " +
                    "SystemNoTarget=${parserConfig.systemNoTarget} " +
                    "SystemIgnoreStringSet=${parserConfig.systemIgnoreStringSet}" +
                    "（出厂三条均 false，spec: ConfigData.cs:98/:112/:113）",
            )
        }
        var fileNo = 0
        for (path in files) {
            fileNo++
            if (sliceFilter != null && !sliceFilter(path)) continue
            if (fileNo % 250 == 0) System.err.println("[boot] 切片 $fileNo/${files.size} ${path.fileName}")
            val rel = runCatching { corpus.root.relativize(path).toString().replace('\\', '/') }
                .getOrElse { path.toString() }
            val bytes = try {
                Files.readAllBytes(path)
            } catch (e: Exception) {
                parseFailures.add("$rel:0 读取失败: ${e::class.simpleName}: ${e.message}")
                continue
            }
            // 与语料门同口径：前 8KB 出现 NUL 视为二进制，不解析
            if (bytes.indexOf(0.toByte()) in 0 until minOf(bytes.size, 8192)) continue

            // TODO(verify): 本装置的配置源只有 parserConfig（ParserConfig 三键，不含
            // SystemAllowFullSpace/SystemIgnoreTripleSymbol），没有可喂
            // LexerConfig.fromConfigStore 的 ConfigStore —— reader 仍吃出厂默认。
            // 2026-10-26 fixcore 波 A1 未接线，留待组长定补源方案。
            val reader = LogicalLineReader(LogicalLineReader.readAllLines(bytes), rel, renameDic = renameDic)
            val pp = CorpusValidator.ErbPreprocess()
            val body = ArrayList<Statement>()
            val dimLines = ArrayList<String>()
            var curKey: String? = null
            var curParams: List<String> = emptyList()
            var curParamRefs: List<String> = emptyList()
            var curParamDefaults: List<ParamDefault?> = emptyList()
            var curLine = -1
            var poisoned = false

            fun flush() {
                val key = curKey
                if (key == null) {
                    body.clear(); dimLines.clear(); poisoned = false
                    return
                }
                slices.add(
                    Slice(rel, key, curLine, curParams, curParamRefs, curParamDefaults, body.toList(), dimLines.toList(), poisoned),
                )
                body.clear(); dimLines.clear(); curKey = null; poisoned = false
            }

            while (true) {
                val line = try {
                    reader.readLogicalLine() ?: break
                } catch (e: LexError) {
                    parseFailures.add("$rel:${e.lineNo} 词法错误 ${e.message}")
                    poisoned = true
                    continue
                }
                if (pp.process(line.content, pre.macroNames)) continue
                if (pp.disabled) continue
                val stmt = try {
                    parser.parseLogicalLine(line)
                } catch (e: Exception) {
                    parseFailures.add("$rel:${line.lineNo} ${e.message ?: "解析失败"} | ${line.content.take(120)}")
                    poisoned = true
                    continue
                } ?: continue
                if (stmt is SharpStmt) {
                    val kw = stmt.keyword.uppercase().removePrefix("#")
                    if (kw == "DIM" || kw == "DIMS") {
                        val raw = stmt.raw.trim()
                        if (curKey == null) globalDimDecls.add(GlobalDimDecl(raw, rel, stmt.line))
                        else dimLines.add(raw)
                    }
                    continue
                }
                if (stmt is FuncLabelStmt) {
                    flush()
                    curKey = stmt.name.uppercase()
                    curParams = stmt.paramNames
                    curParamRefs = stmt.paramRefs
                    curParamDefaults = stmt.paramDefaults
                    curLine = stmt.line
                    continue
                }
                if (curKey == null) continue
                body.add(stmt)
            }
            flush()
            try { reader.fileEnd() } catch (_: Exception) {}
        }
        notes.add("切片完成：${files.size} 文件 → ${slices.size} 个 @函数切片；解析失败 ${parseFailures.size} 条")

        // 4) 切片 ↔ 符号定义对齐（(文件,键) 内出现序；行号口径漂移不影响）
        val queues = LinkedHashMap<Pair<String, String>, ArrayDeque<LabelDef>>()
        for (d in allDefs) queues.getOrPut(d.file to d.key) { ArrayDeque() }.addLast(d)

        val expected = LinkedHashMap<String, LabelDef>()
        fun sigKeyOf(d: LabelDef): String {
            val group = symbolTable.defsOf(d.key)
            val winner = group.lastOrNull()
            return when {
                d.isEvent -> "${d.key}#${d.defIndex}"
                group.size > 1 && winner != null && d.defIndex != winner.defIndex -> "${d.key}#${d.defIndex}"
                else -> d.key
            }
        }
        for (d in allDefs) expected.putIfAbsent(sigKeyOf(d), d)

        var syntheticSeq = 10_000_000
        val units = ArrayList<Unit>()
        val covered = HashSet<String>()
        for (s in slices) {
            val def = queues[s.rel to s.keyUpper]?.removeFirstOrNull() ?: run {
                val d = LabelDef(
                    name = s.keyUpper, key = s.keyUpper, file = s.rel, lineNo = s.line,
                    fileIndex = 1, defIndex = syntheticSeq++,
                    isEvent = Symbols.isEventLabelName(s.keyUpper), isSystem = false,
                    isMethod = s.keyUpper in methodNames,
                    isOnly = false, isPri = false, isLater = false, isSingle = false,
                )
                notes.add("符号表未命中，合成定义: ${s.rel}:${s.line} @${s.keyUpper}")
                d
            }
            var sigKey = sigKeyOf(def)
            if (sigKey in covered) {
                sigKey = "$sigKey#dup${def.defIndex}"
                notes.add("同一 sigKey 重复切片，改键: ${def.key}#${def.defIndex} → $sigKey")
            }
            covered.add(sigKey)
            expected.putIfAbsent(sigKey, def)
            units.add(Unit(sigKey, def, s))
        }
        // 符号表里有、但切片阶段缺失（读取失败/毒化后无切片）的定义：空体桩兜底
        for ((key, def) in expected) {
            if (key !in covered) units.add(Unit(key, def, null))
        }
        units.sortBy { it.def.defIndex }

        // 5) 局部声明布局（与 GoldenPipeline 同口径；常数表供尺寸求值）
        val consts = runCatching { sizeConsts(symbolTable) }.getOrDefault(emptyMap<String, Long>())
        // M4-M `m-dimfold-wire` 收口（M11-E）：装载期折叠面 = **一份** [DimFoldSnapshot] ——
        // VARSIZE 尺寸表 + GETNUM 关键字表 + GETLINESTR 条宽三件套**整面**构造，本装置
        // （预解析）、erbcc 侧车（`CorpusCompile.compile`）、GameBootSmoke 交给
        // `SystemVariableBootstrap.install` 的 cfg 三处消费点同源同参，只许经
        // [DimFoldSnapshot.apply] 挂配置。旧接线只传 varSizes、其余吃默认值，同一行
        // `#DIM DYNAMIC 行文字数 = STRLENSU(GETLINESTR("─"))` 在侧车折成 99、在本装置折成
        // 900（默认 1800 格），且 GETNUM 拿不到 TALENT 关键字表直接失败 —— 侧车与装置读数
        // 分叉、P2/P3 在装置侧假失败（DimFoldSnapshot KDoc 的立项依据）。
        // 装载时点 = `VariableSize.csv` 之后、ERH 之前（spec: Process.cs:292-293 建 ConstantData
        // 并 LoadData → :300-301 建 IdentifierDictionary → :309/:313 才 LoadHeaderFiles）。
        // CMP-40/CMP-59 装置侧同口径：布局桥、`#DIM` 声明解析与生产 `CorpusCompile`
        // 用**同一份**折叠回调（VARSIZE 快照 + 内置名表 + ERD 表快照）。折叠器不进布局，
        // VARSIZE("RELATION") 一类私有声明会在布局面被静默丢，与侧车/运行期分叉。
        val csvDir = corpus.root.parent?.resolve("CSV") ?: corpus.root
        val gameRoot = corpus.root.parent ?: corpus.root
        val gameDataForFold = runCatching { GameDataLoader.load(csvDir) }.getOrNull()
        val systemNames = gameDataForFold?.registry?.all()
            ?.mapTo(HashSet()) { it.name.uppercase() } ?: emptySet()
        val keywordTables: Map<String, Map<String, Int>> =
            runCatching { UserKeywordTables.foldSnapshot(gameRoot, systemNames) }
                .getOrDefault(emptyMap()) +
                (gameDataForFold?.registry?.systemKeywordTables() ?: emptyMap())
        // 数据面**只有一份**：快照用上面这份表（含 CMP-59 的 ERD 第二跳），不另建一份 ——
        // 否则 ERD 表在这条路上失效，装置与侧车的 GETNUM 折叠面再次分叉。
        val foldSnapshot = DimFoldSnapshot.load(
            csvDir,
            keywordTables = keywordTables,
            consoleWidth = DimFoldSnapshot.PC_CONSOLE_WIDTH_CELLS,
        )
        val dimCfg = DimParseConfig(
            macros = symbolTable.config.macros,
            intConstants = consts,
            systemSaveInBinary = systemSaveInBinary,
        ).let { foldSnapshot.apply(it) }
        // 声明解析（第 9 步）用的常量表在 erbcc 的 [sizeConsts] 之外再按装载顺序折一轮：
        // 后者自 2026-10-23 起也支持 0x（`DimSizeEvaluator`，LexicalAnalyzer.cs:36-45），
        // 但本装置保留 foldDeclarationConstants 以按顺序重试（并折字符串常量）；
        // 语料所有颜色常量都是 0x 形态
        // （`DIM.ERH:571-620` `#DIM CONST C_GREEN = 0x00FF00` … `C_P_PURPLE = 0xC070C0`）。
        // 原版 `#DIM` 的尺寸段/初值段都由 `ReduceIntegerTerm`/`ReduceArguments` 对
        // ConstantData 折叠（spec: UserDefinedVariableData.cs:325-341 / :375-417），
        // 十六进制面值经 `LexicalAnalyzer.ReadInt64`（spec: LexicalAnalyzer.cs:36-45）读入，
        // 所以 `#DIM CONST C_RANK10, 10 = C_P_PURPLE, …`（Print_&_Input.ERB:25，块内续行）
        // 在原版能注册；装置若用未折叠表就会把整条声明丢成「求值失败」，
        // 运行到 `C_RANK10:RANK`（:42）时报「未定义的变量」。
        val declCfg = foldDeclarationConstants(globalDimDecls, dimCfg)
        val funcDims = LinkedHashMap(buildFuncDims(allDefs, consts, dimCfg.functionFolder))
        for (d in allDefs) {
            if (d.dimSpecs.isEmpty()) continue
            val dims = d.dimSpecs.mapNotNull { it.toLocalDim(consts, dimCfg.functionFolder) }
            if (dims.isNotEmpty()) funcDims["${d.key}#${d.defIndex}"] = dims
        }
        val layout = DimSpecLayout(GlobalNameLayout(), funcDims)

        // 6) 编译：全函数进同一个 Program
        val eventKeys = symbolTable.nameKeys.filter { k ->
            val ds = symbolTable.defsOf(k)
            ds.size > 1 && ds.first().isEvent
        }
        val known = HashSet<String>(expected.keys)
        known.addAll(eventKeys)

        // 6a) 缺省实参默认值表（spec: FunctionLabelLine.cs:49 / CalledFunction.cs:167）：
        // 键 = 可调用名（`CALL` 目标口径）。重名被遮蔽的定义不会被 CALL 命中，故只收胜者
        // （sigKey == def.key）。非字面量默认值（常量名等）只登记、不注入 —— 见 CallDefaultsCollector。
        val callDefaults = CallDefaultsCollector()
        for (u in units) {
            val s = u.slice ?: continue
            if (s.poisoned) continue
            if (u.sigKey != u.def.key) continue
            callDefaults.add(u.def.key, s.paramDefaults)
        }
        if (callDefaults.functionsWithDefaults > 0 || callDefaults.unsupported.isNotEmpty()) {
            notes.add(
                "缺省实参默认值：${callDefaults.functionsWithDefaults} 个函数可编译期补位；" +
                    "非字面量 ${callDefaults.unsupported.size} 处不注入（" +
                    callDefaults.unsupported.take(5).joinToString(" | ") + "）",
            )
        }

        val compiler = ErbProgramCompiler(layout, callDefaults.build())
        val metas = ArrayList<FuncMeta>()
        val labels = HashMap<String, Map<String, Int>>()
        val fileByKey = HashMap<String, String>()
        var entrySeq = allDefs.size + 10_000

        fun compileOne(sigKey: String, def: LabelDef, body: List<Statement>, params: List<String>, paramRefs: List<String> = params) {
            val returnsStr = pre.funcTypes[def.key.uppercase()] == OperandType.STR
            val arity = if (params.isNotEmpty()) params.size else maxOf(def.argCount, def.argIntCount + def.argStrCount)
            val (argInt, argStr) = argCounts(params, def)
            fun meta(index: Int) = FuncMeta(
                index = index, key = def.key, name = def.name,
                isEvent = def.isEvent, isMethod = def.isMethod,
                isOnly = def.isOnly, isPri = def.isPri, isLater = def.isLater, isSingle = def.isSingle,
                argIntCount = argInt, argStrCount = argStr,
                returnsStr = returnsStr,
                localSize = def.localSize, localsSize = def.localsSize,
                defIndex = def.defIndex, position = "${def.file}:${def.lineNo}",
            )
            val fb = try {
                compiler.compileFunction(FuncSig(sigKey, arity, def.isMethod, returnsStr, paramRefs), body, known, exprCalls)
            } catch (e: CompileException) {
                compileFailures.add("$sigKey [${def.file}:${def.lineNo}] ${e.message}")
                try {
                    compiler.compileFunction(FuncSig(sigKey, arity, def.isMethod, returnsStr, paramRefs), emptyList(), known, exprCalls)
                } catch (e2: Exception) {
                    compileFailures.add("$sigKey 空体桩也失败: ${e2::class.simpleName}: ${e2.message}")
                    return
                }
            } catch (e: Exception) {
                compileFailures.add("$sigKey 编译内部错误: ${e::class.simpleName}: ${e.message}")
                return
            }
            metas.add(meta(fb.index))
            labels[sigKey.uppercase()] = fb.labels
            fileByKey[sigKey] = def.file
        }

        for (u in units) {
            val s = u.slice
            if (s == null) {
                compileOne(u.sigKey, u.def, emptyList(), stubParams(u.def))
                continue
            }
            if (s.poisoned) {
                parseFailures.add("${s.rel}:${s.line} @${s.keyUpper} 结构不可信 → 空体桩")
                compileOne(u.sigKey, u.def, emptyList(), stubParams(u.def))
            } else {
                compileOne(u.sigKey, u.def, s.stmts, s.params, s.paramRefs)
            }
        }

        // 7) 事件入口（同名多定义；装载序 = ONLY→PRI→NORMAL→LATER）
        var eventEntries = 0
        for (evKey in eventKeys) {
            val members = runCatching { symbolTable.eventCallOrder(evKey).map { "${it.key}#${it.defIndex}" } }
                .getOrDefault(emptyList<String>())
            if (members.isEmpty()) continue
            val fb = try {
                compiler.compileEventEntry(evKey, members)
            } catch (e: Exception) {
                compileFailures.add("事件入口 $evKey: ${e::class.simpleName}: ${e.message}")
                continue
            }
            eventEntries++
            metas.add(
                FuncMeta(
                    index = fb.index, key = evKey, name = evKey,
                    isEvent = false, isMethod = false, defIndex = entrySeq++,
                    position = "synthetic-event-entry",
                ),
            )
            labels[evKey.uppercase()] = fb.labels
            fileByKey[evKey] = "·event-entry·"
        }

        // 8) 冻结 Program。语料里本来就未定义的 CALL 目标：补 0 参空体桩后重试
        var program: Program? = null
        var buildError: String? = null
        try {
            var guard = 0
            while (true) {
                try {
                    program = compiler.build(null)
                    break
                } catch (e: CompileException) {
                    val target = Regex("未定义的函数:\\s*(.+)$").find(e.message ?: "")
                        ?.groupValues?.get(1)?.trim()
                    if (target.isNullOrEmpty() || guard++ >= 500 ||
                        target.uppercase() in compiler.knownFunctionNames
                    ) {
                        throw e
                    }
                    compileFailures.add("未定义的 CALL 目标 '$target' → 空体桩（原版仅执行到该行才报错）")
                    compiler.compileFunction(FuncSig(target), emptyList(), known, exprCalls)
                }
            }
        } catch (e: Exception) {
            buildError = "${e::class.simpleName}: ${e.message}"
        }

        // 9) #DIM/#DIMS：全局（函数外）+ 私有（函数内，胜者/事件成员）
        val globalVarDecls = ArrayList<DimSpec>()
        for (g in globalDimDecls) {
            val spec = parseDim(g.raw, isPrivate = false, cfg = declCfg)
            if (spec != null) globalVarDecls.add(spec)
            else dimParseFailures.add("${g.file}:${g.line} ${g.raw.take(120)}")
        }
        val userVarDecls = ArrayList<Pair<String, DimSpec>>()
        for (u in units) {
            val s = u.slice ?: continue
            if (s.poisoned) continue
            val shadowedLoser = !u.def.isEvent && u.sigKey != u.def.key
            if (shadowedLoser) continue
            for (raw in s.dimLines) {
                val spec = parseDim(raw, isPrivate = true, cfg = declCfg)
                if (spec != null) userVarDecls.add(u.def.key to spec)
                else dimParseFailures.add("${s.rel}:${s.line} ${raw.take(120)}")
            }
        }

        // 10) PRINTDATA/STRDATA 数据节（键 = 语句实例）
        val dataSections = IdentityHashMap<Statement, List<List<Expr>>>()
        for (s in slices) harvestDataSections(s.stmts, dataSections)

        notes.add(
            "编译口径：全量切片进同一个 Program；失败/毒化切片落空体桩（保留函数名供 _CALL 链接）。" +
                "空体桩只返回默认值，执行到桩内不会打印/赋值。",
        )

        val stats = BootStats(
            corpusFiles = allFiles.size,
            erbFiles = corpus.erbCount,
            erhFiles = corpus.erhCount,
            loadedFiles = files.size,
            totalDefs = stats0?.totalDefs ?: allDefs.size,
            uniqueNames = stats0?.uniqueNames ?: symbolTable.nameKeys.size,
            duplicateNames = stats0?.duplicateNames ?: 0,
            eventNames = eventNames,
            slices = slices.size,
            compiled = metas.count { it.position != "synthetic-event-entry" },
            compileFailures = compileFailures.size,
            parseFailures = parseFailures.size,
            eventEntries = eventEntries,
            instructions = program?.instructionCount ?: -1,
            statements = program?.statementCount ?: -1,
            globalVarDecls = globalVarDecls.size,
            globalVarParseFailures = globalDimDecls.size - globalVarDecls.size,
            privateVarDecls = userVarDecls.size,
            privateVarParseFailures = dimParseFailures.size - (globalDimDecls.size - globalVarDecls.size),
            elapsedMs = System.currentTimeMillis() - t0,
        )

        return BootModel(
            corpus = corpus,
            program = program,
            buildError = buildError,
            table = FunctionTable(metas),
            labelPcs = labels,
            dataSections = dataSections,
            fileBySigKey = fileByKey,
            globalDimDecls = globalDimDecls,
            globalVarDecls = globalVarDecls,
            userVarDecls = userVarDecls,
            compileFailures = compileFailures,
            parseFailures = parseFailures,
            dimParseFailures = dimParseFailures,
            dimParseConfig = declCfg,
            foldSnapshot = foldSnapshot,

            parserConfig = parserConfig,
            notes = notes,
            stats = stats,
        )
    }

    /**
     * 切片用的 [LineParser] 构造缝（M11-E bootcfg 遗留项④的唯一接点）。
     *
     * [compile] 与 [BootParserConfigTest] 走同一个构造：三键经 `config` 形参喂进
     * （[LineParser] 第 2 参，不碰 LineParser.kt 本体，Q2 在飞），宏表继续喂词法层。
     */
    internal fun sliceLineParser(
        resolver: StmtResolver,
        macros: Map<String, String>,
        parserConfig: ParserConfig,
    ): LineParser = LineParser(
        resolver,
        config = parserConfig,
        lexerConfig = LexerConfig(macros = macros),
    )

    /**
     * 从宿主配置覆盖表（`BuiltinHost.configOverrides`，键 = 条目标签的大写英文形，
     * `RootedBuiltinHost.configOverrides` / `loadConfigOverrides` 同一份形状）构造
     * 切片解析期的 [ParserConfig]（M11-E bootcfg 遗留项④）。
     *
     * 三键与取值口径（与 `SystemVariableBootstrap.systemSaveInBinaryFrom` 同款）：
     * - `CompatiRAND`（spec: ConfigData.cs:98 注册、出厂 `t: false`；解析面消费
     *   VariableParser.cs:163-168 —— 关时 `RAND` 省略下标 / `RAND:0` 报错，
     *   开时两者合法（eramaker 口径）；取值面 VariableData.cs:3154 不在本配置里，走
     *   `VarConfig.compatiRand`，同键不同消费点）；
     * - `SystemNoTarget`（spec: ConfigData.cs:112 注册、出厂 `t: false`；消费
     *   VariableParser.cs:90/96/111/117 —— 开时角色变量省略下标不再补 TARGET，
     *   走无参形态或报「下标不能省略」）；
     * - `SystemIgnoreStringSet`（spec: ConfigData.cs:113 注册、出厂 `t: false`；消费
     *   ArgumentParser.cs:1026-1029 —— 开时字符串变量 `=` 赋值
     *   `assignwarn(Lang.Error.StrAssignIsPrihibited, line, 2)` 判 level 2 错误）。
     *
     * 键匹配复用 [com.eratw.port.core.config.ConfigSpec.matchesUpperKey]
     * （spec: ConfigData.cs:262-284 的 Name/Text/EngText 三形比较）；值词法 =
     * [parseBoolLiteral]（spec: ConfigItem.cs:227-250）；键缺失或值解析失败 ⇒
     * 出厂默认 false（原版 `LoadConfig` 对无效值只记警告、保留旧值，
     * spec: ConfigData.cs:563-586、ConfigItem.cs:89-95）—— 与 systemSaveInBinaryFrom 同判。
     *
     * 语料现值（画蛇添足版 4.980，只读实证）：`emuera.config:52` =
     * `IMITATE BEHAVIOR FOR RAND:NO`、`:65` = `DO NOT AUTO-COMPLETE ARGUMENTS FOR
     * CHARACTER VARIABLES:NO`、`:66` = `STRING VARIABLE ASSIGNMENT ON VALID WITH
     * STRING EXPRESSION:NO` ⇒ 全部等于出厂 false ⇒ 返回等于 [ParserConfig] 缺省，
     * boot 既有读数零回退（判据③；`CSV/_default.config` 与 `CSV/_fixed.config`
     * 均不含这三条，`_Replace.csv` 只写 REPLACE 组，够不着 GAME 组）。
     */
    fun parserConfigFromOverrides(overrides: Map<String, String>): ParserConfig = ParserConfig(
        systemNoTarget = boolFromOverrides(overrides, ConfigKey.SystemNoTarget),
        compatiRAND = boolFromOverrides(overrides, ConfigKey.CompatiRAND),
        systemIgnoreStringSet = boolFromOverrides(overrides, ConfigKey.SystemIgnoreStringSet),
    )

    /** 单键取值：覆盖表查不到 / 值解析失败 ⇒ 注册表出厂默认（三条均 false，见上）。 */
    private fun boolFromOverrides(overrides: Map<String, String>, key: ConfigKey): Boolean {
        val spec = ConfigSpecTable.specOf(key) ?: return false
        val factory = (spec.defaultValue as? ConfigValue.Bool)?.value ?: false
        val raw = overrides.entries.firstOrNull {
            spec.matchesUpperKey(it.key.trim().uppercase(), withEnglishLabel = true)
        }?.value?.trim() ?: return factory
        return parseBoolLiteral(raw) ?: factory
    }

    /** 形参计数：优先 erbcc 的括号形参口径；`@NAME, A, B$` 形态按 paramNames 派生。 */
    private fun argCounts(params: List<String>, def: LabelDef): Pair<Int, Int> {
        if (def.argCount > 0) return def.argIntCount to def.argStrCount
        val str = params.count { it.uppercase().startsWith("ARGS") }
        return (params.size - str) to str
    }

    /**
     * 空体桩的形参占位：只用于让 `FuncSig.arity` 与声明一致（VM 按被调帧 arity
     * 复制实参并分配帧槽；arity=0 而 FuncMeta.argCount>0 会读到帧外槽）。
     */
    private fun stubParams(def: LabelDef): List<String> {
        val n = if (def.argCount > 0) def.argCount else def.argIntCount + def.argStrCount
        return List(n) { "ARG$it" }
    }

    /**
     * `#DIM ...` 原文 → [DimSpec]；失败返回 null（原文进报告，不中断编译）。
     *
     * 剥行内注释与关键字切分**不在装置里另立一套**，直接借
     * [SystemVariableBootstrap.stripComment] / [SystemVariableBootstrap.splitDeclaration]：
     * 原版 `;` 是行内注释分隔符（spec: LexicalAnalyzer.cs:919-929），装载器是不动点重试环
     * （spec: ErhLoader.cs:315-337），运行期注册走的正是同一个 `install`（见 GameBootSmoke），
     * 装置预解析与运行期必须同口径，否则两边的失败数无法对齐。
     */
    private fun parseDim(raw: String, isPrivate: Boolean, cfg: DimParseConfig): DimSpec? {
        val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw))
            ?: return null
        return runCatching { DimParser.parse(split.first, split.second, isPrivate, cfg) }.getOrNull()
    }

    /**
     * 顶层 `#DIM/#DIMS CONST` 的不动点折叠 —— 与运行期 [SystemVariableBootstrap.install]
     * 的注册前语义同构（spec: ErhLoader.cs:315-337：标识符未解析的行重新入队、
     * 整轮无进展才放弃），只是**不建变量**、只收常量表。
     *
     * 为什么装置侧还要这一轮：`#DIM` 的尺寸段与初值段在原版都对 ConstantData 折叠
     * （spec: UserDefinedVariableData.cs:325-341 / :375-417），十六进制面值由
     * `LexicalAnalyzer.ReadInt64` 读入（spec: LexicalAnalyzer.cs:36-45），因此
     * `#DIM CONST C_RANK10, 10 = C_P_PURPLE, …` 能注册。erbcc 的 [sizeConsts]
     * 自 2026-10-23 起也支持 0x（`DimSizeEvaluator`）；本装置仍按装载顺序重试折叠，
     * 与 install 的不动点同构，并额外折出字符串常量。直接拿未折叠表解析私有 `#DIM`
     * 会让整条声明消失 —— 语料实形
     * `魔改内容/PRINT系/Print_&_Input.ERB:25`（块内续行）与
     * `CASINO/駒草の賭場.ERB:2279`（单行，同一失败形态）都由此触发。
     *
     * 与 [SystemVariableBootstrap.install] 的差异（保持同一取舍）：同名常量**先到先得**
     * （原版重复声明在注册那步即报错，后续行不生效），故用 putIfAbsent；
     * TODO(verify) 原版 ERB 逐个文件顺序装载，而这里把所有顶层声明一次性折叠完
     * 再解析私有 `#DIM` —— 引用「更晚文件里才声明的常量」的私有声明在这里会通过、
     * 原版会失败，属装置侧偏宽，不是生产口径。
     */
    private fun foldDeclarationConstants(
        decls: List<GlobalDimDecl>,
        cfg: DimParseConfig,
    ): DimParseConfig {
        val ints = LinkedHashMap<String, Long>(cfg.intConstants)
        val strs = LinkedHashMap<String, String>(cfg.strConstants)
        var pending = decls
        while (pending.isNotEmpty()) {
            val round = cfg.copy(intConstants = ints, strConstants = strs)
            val next = ArrayList<GlobalDimDecl>()
            for (d in pending) {
                val split = SystemVariableBootstrap.splitDeclaration(
                    SystemVariableBootstrap.stripComment(d.raw),
                ) ?: continue
                val spec = runCatching {
                    DimParser.parse(split.first, split.second, isPrivate = false, config = round)
                }.getOrNull()
                if (spec == null) {
                    next.add(d)
                    continue
                }
                if (spec.isConst && !spec.isRef && spec.dims.size == 1 && spec.totalSize == 1) {
                    val key = spec.name.uppercase()
                    if (spec.isStr) spec.defaultsStr?.firstOrNull()?.let { strs.putIfAbsent(key, it) }
                    else spec.defaultsInt?.firstOrNull()?.let { ints.putIfAbsent(key, it) }
                }
            }
            if (next.size == pending.size) break // 整轮无进展（spec: ErhLoader.cs:334-337）
            pending = next
        }
        return cfg.copy(intConstants = ints, strConstants = strs)
    }

    // ------------------------------------------------------------------
    // PRINTDATA/STRDATA 数据节收割（与 GoldenPipeline 同口径）
    // ------------------------------------------------------------------

    private fun harvestDataSections(stmts: List<Statement>, sink: IdentityHashMap<Statement, List<List<Expr>>>) {
        var i = 0
        while (i < stmts.size) {
            val s = stmts[i]
            val isConsumer = s is PrintDataStmt || (s is DataStmt && s.code == FunctionCode.STRDATA)
            if (!isConsumer) { i++; continue }
            val groups = ArrayList<List<Expr>>()
            var j = i + 1
            while (j < stmts.size) {
                val t = stmts[j]
                if (t is EndBlockStmt && t.code == FunctionCode.ENDDATA) break
                if (t is DataListStmt && t.code == FunctionCode.DATALIST) {
                    val acc = ArrayList<Expr>()
                    j++
                    while (j < stmts.size) {
                        val u = stmts[j]
                        if (u is DataListStmt && u.code == FunctionCode.ENDLIST) break
                        if (u is DataStmt && (u.code == FunctionCode.DATA || u.code == FunctionCode.DATAFORM)) acc.add(dataExpr(u))
                        j++
                    }
                    groups.add(acc)
                } else if (t is DataStmt && (t.code == FunctionCode.DATA || t.code == FunctionCode.DATAFORM)) {
                    groups.add(listOf(dataExpr(t)))
                }
                j++
            }
            sink[s] = groups
            i = j + 1
        }
    }

    private fun dataExpr(d: DataStmt): Expr =
        (d.args as? ExpressionArgument)?.term
            ?: d.args.literalStr?.let { StrLiteral(it) }
            ?: StrLiteral("")
}
