package com.eratw.port.core.loader

import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.frontend.RenameDictionary
import com.eratw.port.core.frontend.sizeConsts
import com.eratw.port.core.loader.ModPatchLoader.PATCH_EXTENSION
import com.eratw.port.core.patch.PatchArtifacts
import com.eratw.port.core.patch.PatchBaseSnapshot
import com.eratw.port.core.patch.PatchHeader
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * R7-D3 阶段 2：真语料口上文件的 **C5 等价导入链冒烟**（桌面 JVM 全链，真机验收的桌面替代；
 * CONTRACT-R7-D §2/D3 阶段 2）。本类只新增测试与读数，禁碰任何 main 源文件；发现红只报接线。
 *
 * ## 链路（C5 `ModImportController.import` 的 core 面等价，逐字同源处逐条标注）
 *
 * 文件名序排序 → `PatchCompiler.compile(sources, entry)`（侧车 `BootMetadata` 从
 * `ERATW_ERBC_DIR/program.meta` 读，base 六计数从 `program.erbc` 映射装载读）→
 * `PatchHeader` + `PatchArtifacts.of` → `checkAgainst`（I3 身份预检）→ 落 `mods/` 的
 * `.patch` → `ModPatchLoader.load` → `GameSession` 起会话（`begin(TITLE)`）。
 * `entry` 取法与符号表灌法分别是 `ModImportController.firstFunctionNameOf`（:355-366）
 * 与 `buildPatchSymbolTable`（:388-428）的逐字复刻（CSV 全集 + base 全局 `#DIM` +
 * 方法三分）——`ModImportController` 在 app 模块，core 测试面只能复刻其 core 可达面。
 *
 * ## 三层读数（分桶口径：I6 保留事件/全局DIM/同名/省略实参、I3、宏、其他）
 *
 * - **B 层（失败清单来源）**：每个样本文件独立走完整链（独立 mods 目录、独立装载），
 *   失败归因到单文件；桶 = 按异常类型 + 消息关键串。
 * - **A 层（C5 原样形态）**：3 源按文件名序一次 `import` 等价调用（单次 `compile`
 *   多源，entry = 首源首函数头）——这是契约链路的字面形态，全或无（I5）。
 * - **C 层（会话健康对照，无 mods 的 I1 口径）**：`GameSession` 在真语料 base +
 *   真侧车 + 真 CSV 上装配并 `begin(TITLE)`。它与补丁面无关：红了才是 base/侧车/CSV
 *   装配面的问题（硬断言），绿了给「失败只落在补丁编译闸」提供隔离证据。
 *
 * ## 判定口径（契约 D3 阶段 2）
 *
 * 成功 ≥1 条 = 端到端在真语料成立；全部因同一根因失败（如 I6 同名闸——真口上文件的
 * 函数本就是 base 全量语料的一部分，v1 禁 override 是设计闸）**照实报**，测试本体不因
 * 样本全拒而红（否则读数落不了盘），那是后续清账/override 立项的输入。
 */
// realArtifacts 家标记（realarttag 路 2026-11-06）：产物门控钉（ERATW_GAMEDIR + ERATW_ERBC_DIR 配对门），供 gateRealArtifacts 圈定；判定逻辑一字不动。
@Tag("realArtifacts")
class RealCorpusPatchSmokeTest {

    companion object {

        /** 口上样本（契约 D3：小文件优先，3 个真实口上文件；合计 12.4 KB）。 */
        val SAMPLE_FILES = listOf(
            "COMMON_KOJO.ERB",
            "STAND_COM_IMAGE.ERB",
            "TINKO_TYPE.ERB",
        )

        const val KOJO_DIR_REL = "ERB/口上・メッセージ関連"

        /** 产物目录门：`program.erbc` + `program.meta` 配对齐备才可用（I3 需要 sha，编译需要侧车）。 */
        fun artifactDir(): Path? {
            val raw = System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val p = Path.of(raw)
            if (!Files.isDirectory(p)) return null
            val erbc = p.resolve("program.erbc")
            val meta = p.resolve("program.meta")
            return if (Files.isRegularFile(erbc) && Files.isRegularFile(meta)) p else null
        }

        /** 游戏根门：口上样本目录与 CSV 目录齐备（编译符号表灌 CSV、会话装配吃游戏根）。 */
        fun gameRoot(): Path? {
            val raw = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val p = Path.of(raw)
            val kojo = p.resolve(KOJO_DIR_REL)
            return if (Files.isDirectory(kojo) && Files.isDirectory(p.resolve("CSV"))) p else null
        }

        /**
         * 流式 SHA-256（C5 `ProductionBaseCompileContextSource.fileSha256Hex`（:115-131）的
         * 逐字复刻：1 MiB 缓冲逐块喂，329 MB 的产物不整份进堆）。
         */
        fun fileSha256Hex(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val hex = "0123456789abcdef"
            val sb = StringBuilder(64)
            for (b in digest.digest()) {
                val v = b.toInt() and 0xFF
                sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
            }
            return sb.toString()
        }

        /** base 编译上下文（C5 `ProductionBaseCompileContextSource.read`（:86-109）的 core 等价）。 */
        class BaseContext(
            val sha256: String,
            val base: Program,
            val counts: PatchCompiler.BaseCounts,
            val metadata: BootMetadata,
            val symbolTable: SymbolTable,
            /**
             * C5 原样符号表预检的首例失败（strict 模式 = 逐字复刻 C5，遇错即抛）。
             * 非 null = C5 生产链在当前产物上导入**任何**补丁都会在这步整链死
             * （buildPatchSymbolTable 先于 compile 对全量 globalDims 逐条严格解析）。
             */
            val c5PreflightError: String?,
            /** 容忍模式下被跳过的侧车 global-dims 条目（原文前缀；读数与分桶解释用）。 */
            val skippedGlobalDims: List<String>,
            /**
             * `CSV/_Rename.csv` 的 `[[替换名]]` 字典（与 erbcc 全量同源，
             * 装置侧直接吃 core 的 [com.eratw.port.core.frontend.RenameDictionary]）。
             * 口上包里的 `CASE [[比基尼套装]]` 要靠它在读行层替换，否则词法器见 `[[` 即炸。
             */
            val renameDic: Map<String, String>?,
        )

        /**
         * 类级单例：base 上下文整个测试类只读一次（mmap 装载 + 49 MB 侧车 + CSV 灌表）。
         * 门缺失 → null（各用例 [assumeTrue] 跳过）；装载/解析失败 → 异常原样翻出（红）。
         *
         * 符号表走**双层**：先按 C5 原样（strict）预检取证（首例失败进 [BaseContext.c5PreflightError]，
         * 冒烟发现照实报）；再以容忍模式重灌（失败条目记名单跳过，其余照常）——容忍模式是
         * **装置的取样偏离**（C5 生产链在预检失败时不产生任何读数），目的 = 让 B/A/C 层的
         * 口上文件分桶读数得以产生；偏离面在读数与交付里显式标注。
         */
        val ctx: BaseContext? by lazy {
            val dir = artifactDir() ?: return@lazy null
            val root = gameRoot() ?: return@lazy null
            val sha = fileSha256Hex(dir.resolve("program.erbc"))
            // C5 用 mmap 读六计数后即弃 Program；本装置的 base 留给 ModPatchLoader.load
            // 当真 base（映射档轻载：指令流零堆驻留、池惰性），通道归它到 GC。
            val program = ProgramLoader.loadMapped(dir.resolve("program.erbc"))
            val counts = PatchCompiler.BaseCounts(
                instructionCount = program.instructionCount,
                functionCount = program.functions.size,
                statementCount = program.statementCount,
                stringCount = program.stringCount,
                longCount = program.constantCount,
                exprCount = program.exprCount,
            )
            val metadata = BootMetadata.read(dir.resolve("program.meta"))
            println(
                "[r7d3-smoke] base 上下文：sha=$sha instr=${counts.instructionCount} " +
                    "functions=${counts.functionCount} statements=${counts.statementCount} " +
                    "strings=${counts.stringCount} longs=${counts.longCount} exprs=${counts.exprCount}",
            )
            // 双层符号表：strict = C5 原样（取证）；tolerant = 装置取样面（继续冒烟）
            val preflight = try {
                buildPatchSymbolTable(metadata, root.resolve("CSV"))
                null
            } catch (e: PatchCompiler.PatchCompileException) {
                "[C5 原样预检失败] ${e.message}"
            } catch (e: Exception) {
                "[C5 原样预检失败] ${e::class.simpleName}: ${e.message}"
            }
            val (symbolTable, skipped) = buildPatchSymbolTableTolerant(metadata, root.resolve("CSV"))
            println("[r7d3-smoke] $preflight")
            println("[r7d3-smoke] 容忍模式跳过的 global-dims 条目 ${skipped.size} 条：${skipped.take(5)}")
            BaseContext(sha, program, counts, metadata, symbolTable, preflight, skipped, RenameDictionary.load(root.resolve("CSV")))
        }

        /**
         * C5 `ModImportController.buildPatchSymbolTable`（:388-428）的逐字复刻
         * （`File` → `Path` 适配）：CSV 全集（`GameDataLoader`）→ base 全局 `#DIM`
         * （侧车 `global-dims` 原文重解析，`DimParser` 同口径）→ base 方法三分
         * （`knownMethodTypes` 的 I/S 桶）。
         */
        fun buildPatchSymbolTable(metadata: BootMetadata, csvDir: Path?): SymbolTable =
            buildPatchSymbolTableTolerant(metadata, csvDir, strict = true).first

        /**
         * [buildPatchSymbolTable] 的容忍变体（**装置取样面，非生产形态**）：
         * `strict = true` = C5 原样（global-dims 解析失败整链抛）；`strict = false` =
         * 失败条目记名单跳过（跳过名单随读数上报），让后续链路分桶读数得以产生。
         * CSV 与方法三分两段不受 strict 影响（它们在真产物上本就通过）。
         */
        fun buildPatchSymbolTableTolerant(
            metadata: BootMetadata,
            csvDir: Path?,
            strict: Boolean = false,
        ): Pair<SymbolTable, List<String>> {
            val sym = SymbolTable()
            // ERH `#DIM CONST` 的名字（侧车 const / str-const 两段）先登记为标量常量，
            // 口上里 `CASE 桜花道的鳥居` 这类裸常量名才认得出；CSV 与 global-dims 随后的
            // 同名条目以更精确 spec 覆盖（与 C5 生产面 ModImportController 同口径）。
            val sidecarConsts = metadata.dimParseConfig
            for (name in sidecarConsts.intConstants.keys) {
                sym.declareVariable(name, OperandType.INT, 0, isCharacterData = false, isConst = true)
            }
            for (name in sidecarConsts.strConstants.keys) {
                sym.declareVariable(name, OperandType.STR, 0, isCharacterData = false, isConst = true)
            }
            if (csvDir != null) {
                val data = GameDataLoader.load(csvDir)
                if (data != null) {
                    for (def in data.registry.all()) {
                        val info = def.toVarInfo()
                        sym.declareVariable(info.name, info.operandType, info.dimension, info.isCharacterData, info.isConst)
                    }
                }
            }
            val dimCfg = metadata.dimParseConfig
            val consts = sizeConsts(dimCfg.macros, dimCfg.intConstants)
            // 只重算尺寸常量折叠表，其余解析口径原样继承侧车 dim-config（systemSaveInBinary
            // 丢了会退成 false ⇒ base 的 #DIM CHARADATA SAVEDATA X 全被假跳过，见 C5 同处注释）。
            val cfg = dimCfg.copy(intConstants = consts)
            val skipped = ArrayList<String>()
            for (raw in metadata.globalDims) {
                try {
                    val keyword = raw.raw.substringBefore(' ', raw.raw).uppercase()
                    val spec = DimParser.parse(keyword, raw.raw.substringAfter(' ', "").trim(), isPrivate = false, config = cfg)
                    sym.declareVariable(
                        spec.name,
                        if (spec.isStr) OperandType.STR else OperandType.INT,
                        spec.dimension,
                        // CHARADATA 位原样带给符号表（与 erbcc 全量预扫
                        // CorpusScan.global.declareVariable 同口径）：口上里
                        // `CHILDNAME:16:ARG` 这类「下标+角色」二维取用要靠它才解析得过。
                        isCharacterData = spec.isCharaData,
                        isConst = spec.isConst,
                    )
                } catch (e: Exception) {
                    val msg = "侧车 global-dims 段解析失败（${raw.file}:${raw.line}）${raw.raw.take(80)} —— ${e.message ?: e::class.simpleName}"
                    if (strict) throw PatchCompiler.PatchCompileException(msg, e)
                    skipped.add("${raw.file}:${raw.line} ${raw.raw.take(60)} || ${(e.message ?: e::class.simpleName)?.take(160)}")
                }
            }
            for ((name, type) in metadata.knownMethodTypes()) {
                when (type) {
                    OperandType.INT -> sym.declareFunction(name, OperandType.INT)
                    OperandType.STR -> sym.declareFunction(name, OperandType.STR)
                    else -> Unit // UNKNOWN：不声明 = 表达式位调用保守明确失败（禁静默降级）
                }
            }
            // base 普通函数（非方法、非事件）补进表达式位存在性表（2026-11 蕾米莉亚包实测）：
            // LineParser 在 SET/IF 表达式位调用 `@IS_HERE(TARGET)` 时经 resolveFunction 判存在，
            // 不声明 = 「未定义的标识符」整包死。类型钉 INT：ExprCallTable 把它们归
            // nonMethodLabels（不降级、保持 FuncCall 节点交运行期重入通道），解析期类型位
            // 不参与编译产物——与补丁内同名多定义的包内互调同一口径。
            val methodKeys = metadata.knownMethodTypes().keys
            for (m in metadata.functions) {
                if (m.isEvent || m.isMethod || m.key in methodKeys) continue
                sym.declareFunction(m.key, OperandType.INT)
            }
            return sym to skipped
        }
    }

    /**
     * C5 `ModImportController.firstFunctionNameOf`（:355-366）的逐字复刻 —— **含现状口径**：
     * 只 trim 空格/tab 不剥 BOM（U+FEFF 开头的行不命中 `startsWith("@")`）。样本里
     * COMMON_KOJO.ERB 首行带 BOM，本冒烟会如实复现这一口径的取名偏移（见读数）。
     */
    private fun firstFunctionNameOf(text: String): String? {
        for (rawLine in text.lineSequence()) {
            val content = rawLine.trimStart(' ', '\t')
            if (!content.startsWith("@")) continue
            val body = content.substring(1)
            var i = 0
            while (i < body.length && body[i] !in " \t(,") i++
            val name = body.substring(0, i).trim()
            if (name.isNotEmpty()) return name
        }
        return null
    }

    /** 文件内全部 `@` 函数名（剥前导 BOM 后提取；预处理读数：与 base 侧车函数键对撞）。 */
    private fun allFunctionNames(text: String): List<String> {
        val out = ArrayList<String>()
        for (rawLine in text.lineSequence()) {
            val content = rawLine.trimStart('\uFEFF', ' ', '\t')
            if (!content.startsWith("@")) continue
            val body = content.substring(1)
            var i = 0
            while (i < body.length && body[i] !in " \t(,") i++
            val name = body.substring(0, i).trim()
            if (name.isNotEmpty()) out.add(name)
        }
        return out
    }

    /** 冒烟一行读数（成功 = bucket ok + 读数；失败 = bucket 归类 + 错误原文）。 */
    private data class SmokeRow(
        val file: String,
        val stage: String,
        val bucket: String,
        val detail: String,
    )

    /**
     * 桶分类（任务口径）：I6（保留事件 / 全局DIM / 同名 / 省略实参）、I3（身份）、宏、其他。
     * 匹配串 = 各闸消息的定点子串（`PatchCompiler.kt:241/250/377/408/529` 与
     * `ModPatchLoader.kt:201`）；分类器只服务读数，错误原文永远完整保留在 [SmokeRow.detail]。
     */
    private fun bucketOf(kind: String, message: String): String {
        if (kind == "IDENTITY") return "I3-身份"
        return when {
            message.contains("保留事件函数") -> "I6-保留事件"
            message.contains("全局 #DIM") -> "I6-全局DIM"
            message.contains("同名函数") || message.contains("重名") -> "I6-同名"
            message.contains("省略了实参") -> "I6-省略实参"
            message.contains("宏") -> "宏"
            else -> "其他"
        }
    }

    // ------------------------------------------------------------------
    // 链路分段（B/A 两层共用）：编译 → 产物 + I3 → 落盘 → 装载
    // ------------------------------------------------------------------

    /** 编译单步：成功返回 [PatchCompiler.PatchResult]，失败写 [rows] 并返回 null（分桶在行里）。 */
    private fun compileStep(
        rows: MutableList<SmokeRow>,
        file: String,
        sources: List<PatchCompiler.SourceFile>,
        entry: String,
    ): PatchCompiler.PatchResult? {
        val c = ctx!!
        return try {
            PatchCompiler(c.metadata, c.counts, c.symbolTable).compile(sources, entry)
        } catch (e: PatchCompiler.PatchCompileException) {
            val msg = e.message ?: ""
            rows.add(SmokeRow(file, "COMPILE", bucketOf("COMPILE", msg), msg))
            null
        } catch (e: Exception) {
            val msg = "${e::class.simpleName}: ${e.message}"
            rows.add(SmokeRow(file, "COMPILE", bucketOf("COMPILE", msg), msg))
            null
        }
    }

    /**
     * 产物 + I3 + 落盘 + 装载单步（C5 `import` 步骤 ④⑤ 与 `ModPatchLoader.load` 的 core
     * 等价）：成功返回装载读数串，失败写 [rows] 并返回 null。
     */
    private fun loadStep(
        rows: MutableList<SmokeRow>,
        file: String,
        entry: String,
        result: PatchCompiler.PatchResult,
        modsDir: Path,
    ): String? {
        val c = ctx!!
        val program = result.program
        val counts = c.counts
        val header = PatchHeader(
            baseInstructionCount = counts.instructionCount,
            baseFunctionCount = counts.functionCount,
            baseStatementCount = counts.statementCount,
            baseStringCount = counts.stringCount,
            baseLongCount = counts.longCount,
            baseExprCount = counts.exprCount,
            baseSha256 = c.sha256,
            compiler = PatchArtifacts.compilerSummary(),
            patchFunctionCount = program.functions.size,
            patchStatementCount = program.statementCount - counts.statementCount,
            patchStringCount = program.stringCount - counts.stringCount,
            patchLongCount = program.constantCount - counts.longCount,
            patchExprCount = program.exprCount - counts.exprCount,
            entryFunction = entry,
        )
        val artifact = try {
            PatchArtifacts.of(header, ErbcWriter.encode(program))
        } catch (e: Exception) {
            val msg = "补丁产物编码失败：${e.message ?: e::class.simpleName}"
            rows.add(SmokeRow(file, "COMPILE", bucketOf("COMPILE", msg), msg))
            return null
        }
        try {
            artifact.checkAgainst(
                PatchBaseSnapshot(
                    counts.instructionCount, counts.functionCount, counts.statementCount,
                    counts.stringCount, counts.longCount, counts.exprCount, c.sha256,
                ),
            )
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            rows.add(SmokeRow(file, "IDENTITY", bucketOf("IDENTITY", msg), msg))
            return null
        }
        val patchName = file.substringBeforeLast('.') + PATCH_EXTENSION
        try {
            Files.write(modsDir.resolve(patchName), artifact.write())
        } catch (e: Exception) {
            val msg = "落盘失败：${e.message ?: e::class.simpleName}"
            rows.add(SmokeRow(file, "STORAGE", bucketOf("STORAGE", msg), msg))
            return null
        }
        return try {
            val load = ModPatchLoader.load(modsDir, c.base, c.sha256)
            if (load.rolledBack || load.failures.isNotEmpty()) {
                val msg = load.failures.joinToString("；")
                rows.add(SmokeRow(file, "LOAD", bucketOf("LOAD", msg), msg))
                null
            } else {
                val patch = load.loadedPatches.singleOrNull()
                "装载成功：函数 ${patch?.functionCount ?: load.loadedPatches.sumOf { it.functionCount }} 个，" +
                    "指令 ${patch?.patchInstructionCount ?: 0} 条，键 ${patch?.functionKeys ?: emptyList()}"
            }
        } catch (e: Exception) {
            val msg = "${e::class.simpleName}: ${e.message}"
            rows.add(SmokeRow(file, "LOAD", bucketOf("LOAD", msg), msg))
            null
        }
    }

    // ------------------------------------------------------------------
    // 会话装置（C 层对照与 A 层端到端共用）
    // ------------------------------------------------------------------

    /**
     * 会话健康对照的 begin 开关：真语料 TITLE 页是执行面已知深水区（标题输入 park、
     * 桌面图片解码分叉下 SYSTEM_TITLE 的历史空转红，`GameBootSmoke.kt:478-482`）——
     * 本冒烟只测「会话能否装配」，begin 是可选加项（`ERATW_SMOKE_BEGIN=1` 显式开启）。
     */
    private fun beginRequested(): Boolean = System.getenv("ERATW_SMOKE_BEGIN") == "1"

    /**
     * 构造带执行闸的会话并按需 begin：
     * - `maxStepsPerRun = 2_000_000`（段落步数闸；缺省 20M 是给「重载模组」的，冒烟用不着）；
     * - `maxRunMillis = 30_000`（单段落墙钟闸；0 = 不限，真语料上等于可能跑飞 —— 上轮
     *   30 分钟超时的直接教训）；
     * - `stageMark` 分段回调：装配卡点定位读数。
     * begin 撞闸抛出的异常由调用方接（读数记录，不是装置缺陷 —— 那是语料执行面的既有边界）。
     */
    private fun assembleSession(
        root: Path,
        base: Program,
        metadata: BootMetadata,
        sha: String,
        modsDirectory: Path?,
        label: String,
        rows: MutableList<SmokeRow>,
    ): Pair<GameSession, Boolean> {
        var assembled = false
        val s = GameSession(
            gameRoot = root,
            program = base,
            metadata = metadata,
            consoleWidth = 199,
            randSeed = 20260226L,
            maxStepsPerRun = 2_000_000L,
            maxRunMillis = 30_000L,
            modsDirectory = modsDirectory,
            baseProgramSha256 = sha,
            stageMark = { mark -> println("[r7d3-smoke] [$label] 会话段 $mark") },
        )
        try {
            assembled = true
            if (!beginRequested()) {
                return s to true
            }
            val t0 = System.currentTimeMillis()
            val ok = s.begin(BeginType.TITLE)
            rows.add(
                SmokeRow(
                    label, "SESSION", "ok",
                    "begin(TITLE)=$ok，耗时 ${System.currentTimeMillis() - t0} ms（带 2M 步 / 30s 段落闸）",
                ),
            )
        } catch (e: Exception) {
            rows.add(SmokeRow(label, "SESSION", "其他", "begin(TITLE) 异常（带闸）：${e::class.simpleName}: ${e.message}"))
        }
        return s to assembled
    }

    // ------------------------------------------------------------------
    // 冒烟主用例
    // ------------------------------------------------------------------

    @Test
    fun `真语料口上文件经C5等价导入链端到端冒烟`(@TempDir dir: Path) {
        val root = gameRoot()
        assumeTrue(root != null, "未设置 ERATW_GAMEDIR（游戏根，含口上样本与 CSV）：跳过冒烟用例")
        val c = ctx
        assumeTrue(c != null, "未设置 ERATW_ERBC_DIR（产物目录，program.erbc + program.meta 配对）：跳过冒烟用例")

        val kojoDir = root!!.resolve(KOJO_DIR_REL)
        val baseKeys = HashSet<String>(c!!.metadata.functions.size * 2)
        for (m in c.metadata.functions) baseKeys.add(m.key)

        val rows = ArrayList<SmokeRow>()
        val loadedFiles = ArrayList<String>()

        // ---------- 前置面读数：C5 原样预检 + 容忍模式偏离声明 ----------
        rows.add(
            SmokeRow(
                "(前置)", "PREFLIGHT", if (c.c5PreflightError == null) "ok" else "其他",
                c.c5PreflightError ?: "C5 原样符号表预检通过",
            ),
        )
        rows.add(
            SmokeRow(
                "(前置)", "PREFLIGHT", "装置偏离",
                "C5 原样预检${if (c.c5PreflightError == null) "通过，无偏离" else "失败（见上一行），本冒烟以容忍模式继续取样；" +
                    "跳过条目 ${c.skippedGlobalDims.size} 条（名单见 base 上下文读数）。跳过的常量若被口上源引用，" +
                    "会以「未定义标识符」类解析失败落「其他」桶，与真实编译缺陷区分时对照本名单"}",
            ),
        )

        // ---------- B 层：每文件独立链（失败清单与分桶的来源；文件名序） ----------
        for ((idx, name) in SAMPLE_FILES.sorted().withIndex()) {
            val text = Files.readString(kojoDir.resolve(name))
            val bom = text.startsWith("\uFEFF")
            val names = allFunctionNames(text)
            val hits = names.count { it.uppercase() in baseKeys }
            rows.add(
                SmokeRow(
                    name, "PRESCAN", "ok",
                    "BOM=$bom 函数 ${names.size} 个，与 base 侧车同名 $hits 个；" +
                        "首函数（BOM 剥后）=${names.firstOrNull()}",
                ),
            )
            // C5 entry 口径（逐字同源，不剥 BOM）——BOM 文件的取名偏移是本冒烟的取证点之一
            val entry = firstFunctionNameOf(text)
            rows.add(SmokeRow(name, "ENTRY", "ok", "C5 firstFunctionNameOf ⇒ $entry"))
            if (entry == null) {
                rows.add(SmokeRow(name, "COMPILE", "其他", "补丁里没有任何 @函数定义（C5 firstFunctionNameOf 口径）"))
                continue
            }
            val result = compileStep(rows, name, listOf(PatchCompiler.SourceFile(relPath = name, text = text)), entry)
                ?: continue
            val modsDir = Files.createDirectories(dir.resolve("mods-$idx"))
            val loadReading = loadStep(rows, name, entry, result, modsDir) ?: continue
            rows.add(SmokeRow(name, "LOAD", "ok", loadReading))
            loadedFiles.add(name)
        }

        // ---------- A 层：C5 原样形态（3 源按文件名序一次导入，全或无 I5） ----------
        val mergedName = "(合并3源)"
        val sortedSources = SAMPLE_FILES.sorted().map { name ->
            PatchCompiler.SourceFile(relPath = name, text = Files.readString(kojoDir.resolve(name)))
        }
        val entryA = firstFunctionNameOf(sortedSources.first().text)
        rows.add(SmokeRow(mergedName, "ENTRY", "ok", "C5 firstFunctionNameOf(首源) ⇒ $entryA"))
        if (entryA == null) {
            rows.add(SmokeRow(mergedName, "COMPILE", "其他", "补丁里没有任何 @函数定义（C5 firstFunctionNameOf 口径）"))
        } else {
            val result = compileStep(rows, mergedName, sortedSources, entryA)
            if (result != null) {
                val mergedMods = Files.createDirectories(dir.resolve("mods-merged"))
                val loadReading = loadStep(rows, mergedName, entryA, result, mergedMods)
                if (loadReading != null) {
                    rows.add(SmokeRow(mergedName, "LOAD", "ok", loadReading))
                    // 端到端最后一环：会话带着合并 mods 装配（GameSession 构造内部自己跑
                    // ModPatchLoader.load —— 补丁产物已在目录里）；begin 仅在开关显式要求时跑
                    try {
                        val (s, assembled) = assembleSession(
                            root, c.base, c.metadata, c.sha256, mergedMods, "(合并3源·会话)", rows,
                        )
                        try {
                            rows.add(
                                SmokeRow(mergedName, "SESSION", "ok",
                                    "会话装配=$assembled；补丁清单=${s.loadedPatches.size} 回退=${s.patchRolledBack}"),
                            )
                        } finally {
                            s.close()
                        }
                    } catch (e: Exception) {
                        rows.add(SmokeRow(mergedName, "SESSION", "其他", "${e::class.simpleName}: ${e.message}"))
                    }
                }
            }
        }

        // ---------- C 层：会话健康对照（无 mods，I1 口径；与补丁面隔离） ----------
        var sessionOk = false
        var sessionNote = ""
        try {
            val (s, assembled) = assembleSession(root, c.base, c.metadata, c.sha256, null, "(会话对照·无补丁)", rows)
            try {
                sessionOk = assembled
            } finally {
                s.close()
            }
        } catch (e: Exception) {
            sessionNote = "${e::class.simpleName}: ${e.message}"
        }
        rows.add(
            SmokeRow(
                "(会话对照·无补丁)", "SESSION", if (sessionOk) "ok" else "其他",
                "GameSession 装配=$sessionOk" + (if (sessionNote.isEmpty()) "" else "；$sessionNote"),
            ),
        )

        // ---------- 汇总读数（smoke.md 的原料） ----------
        for (r in rows) {
            println("[r7d3-smoke] FILE ${r.file} stage=${r.stage} bucket=${r.bucket} :: ${r.detail.take(400)}")
        }
        val failures = rows.filter { it.bucket != "ok" }
        val buckets = failures.groupingBy { "${it.stage}/${it.bucket}" }.eachCount()
        println(
            "[r7d3-smoke] SUMMARY 样本=${SAMPLE_FILES.size} 装载成功=${loadedFiles.size} " +
                "失败行=${failures.size} 桶分布=$buckets 会话装配=$sessionOk",
        )

        // 断言只钉装置前置面：会话健康对照必须装配成功（那是 base/侧车/CSV 面，
        // 与补丁无关——它红了才是真正的接线问题）。补丁面允许全拒照实报（契约判定口径）。
        assertTrue(sessionOk, "GameSession 在真语料 base + 真侧车 + 真 CSV 上必须能装配（会话健康对照）：$sessionNote")
    }
}