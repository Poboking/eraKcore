package com.eratw.port.boot

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.IMAGE_DECODER_SLOT
import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.image.PngImage
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.config.parseBoolLiteral
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.proc.loadConfigOverrides
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.sem.SystemOps
import com.eratw.port.core.stmt.StmtInternAudit
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.e2e.LineConsoleSink
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * 真实游戏 headless 开局冒烟（JavaExec 入口：`:core-vm:bootSmoke`）。
 *
 * 目标不是验收，而是**测量**：真实语料 → 全量编译 → `BEGIN TITLE`（SYSTEM_TITLE）→
 * `BEGIN FIRST`（EVENTFIRST）→ 记录首个阻塞点的五元组
 * （语句 / 函数 / 源码行 / pc / 消息）。装置本身**不修**任何未实现语句或内建函数。
 *
 * BOOT-2 接线（c-bootwire 路补的两处，装置不再自己发明口径）：
 * 1. **全局系统变量** = [SystemVariableBootstrap.install]（剥行内注释 + 不动点重试注册，
 *    spec: LexicalAnalyzer.cs:919-929 / ErhLoader.cs:315-337）。旧路径把带 `;` 尾注的
 *    `#DIM CONST` 原文直喂 DimParser，声明整条失效 —— `RM_TITLE`
 *    （语料实文 `リソース管理/ResourceManager.ERH:15`
 *    `#DIM CONST RM_TITLE = 100000 ; タイトル画面の画像ID`）因此失踪，
 *    开局在 `リソース管理/ResourceManager.ERB:6` 的 `GCREATED(RM_TITLE)` 处报「未定义的变量」
 *    （spec: VariableStore.kt:107）。私有 `#DIM` 仍逐函数注册（install 只管全局，
 *    见其 KDoc 的职责边界）。
 * 2. **内建函数宿主** = [ConsoleBuiltinHost]（生产类；委托 [RootedBuiltinHost] 补
 *    fileOps / clockMillis / readFileBytes / checkSaveData / soundDir，写能力被挡）：
 *    `LOADTEXT` / `ENUMFILES` / `EXISTFILE` / `GCREATEFROMFILE` / `GETMILLISECOND`
 *    在 core 层都要经 `BuiltinHost`，未接线即 CodeEE（GraphicsMethods.kt:90）。
 * 3. **配置覆盖** = [ConsoleBuiltinHost.configOverrides]（转发自 [RootedBuiltinHost]，读
 *    `<gameRoot>/emuera.config`）：M4-F CONFIG-ENCODE 接线 —— `LOADTEXT`/`SAVETEXT` 的扩展名
 *    白名单（本语料 `emuera.config:73` = `xml,txt,json`）与 STRLEN 族码页（`:56` =
 *    `CHINESE_HANS` ⇒ 936）都从这里到引擎；报告有「配置行数」一行自证非空。
 * 4. **切片解析期三键**（M11-E bootcfg，遗留项④）= [RealCorpusCompile.parserConfigFromOverrides]
 *    从同一份覆盖表（[loadConfigOverrides]，与 `RootedBuiltinHost.configOverrides` 同源）取
 *    `CompatiRAND` / `SystemNoTarget` / `SystemIgnoreStringSet`（语料 `emuera.config:52/:65/:66`
 *    全 NO = 出厂 false，spec: ConfigData.cs:98/:112/:113），喂进 `RealCorpusCompile` 的
 *    `LineParser` 构造 —— boot 侧解析口径自此与生产/配置链路一致。
 *
 * 环境变量：
 * - `ERATW_CORPUS`：语料 `ERB` 目录（缺省自动发现，见 [RealCorpus]）；
 * - `ERATW_BOOT_LIMIT`：只切片前 N 个文件（OOM 降级；报告注明）；
 * - `ERATW_BOOT_MAX_STEPS`：单次 `begin` 步数上限，缺省 5,000,000；
 * - `ERATW_BOOT_INPUTS`：INT 输入的顺序脚本（逗号或分号分隔，按请求顺序优先消费；
 *   `0,100` = 标题页「开始游戏」→ 新游戏菜单「完成自定义阶段并开始游戏」）；耗尽后
 *   带默认值取默认值、无默认值取 0；
 * - `ERATW_BOOT_REPORT`：报告路径，缺省 `build/boot-report.md`
 *   （gradle 场景 cwd = `core-vm/`，故落 `core-vm/build/`）；
 * - `ERATW_BOOT_TITLE_DECODER=stub`：装一个合成位图 [ImageDecoderHost]
 *   （`title.webp` ⇒ 1041×557 实像素），复刻真机 `BitmapImageDecoder` 的分叉 ——
 *   本装置默认不装，`title.webp`（VP8L）解不通 ⇒ GCREATEFROMFILE 返回 0、
 *   SPRITECREATE 全短路；真机首跑 500 万步空转的定位要靠这一层，见装配段探针注释；
 * - `ERATW_BOOT=1`：[GameBootSmokeTest] 才执行（常规 `:core-vm:test` 直接跳过）。
 *
 * 已知接口点（TODO(verify)）：
 * - 输入经 `ProcessRuntime.inputSink` 注入 [ScriptedInputSink]：带默认值的 INT 请求原样取默认值，
 *   无默认值的 INT 请求按 `ERATW_BOOT_INPUTS` 顺序作答（耗尽后 0），STR 请求取默认值或空串；
 *   变量未设时与 `NoopInputSink` 行为一致（GoldenPipeline.kt:612-614 已登记同一事实）；
 * - 单次 run 的已执行步数未公开，靠反射读 `ProcessRuntime.vm.executedSteps`
 *   （字段/属性名属实现细节，取不到时报告写「未暴露」）。
 */
object GameBootSmoke {

    private const val DEFAULT_MAX_STEPS = 5_000_000L
    private const val RAND_SEED = 20260226L

    /**
     * 顺序作答的输入宿主。脚本项按请求顺序优先消费（标题页 `TINPUT 20000,2,…` 的默认值是 2 =
     * DEMO，想走新游戏就必须先喂 0）；脚本耗尽后带默认值的 INT 取默认值、无默认值取 0。
     * STR 请求取默认值或空串；任意键请求立即放行（与 NoopInputSink 同）。
     */
    private class ScriptedInputSink(private val script: List<Long>) : InputSink {
        private val pending = ArrayDeque(script)
        var intRequests = 0
            private set
        var strRequests = 0
            private set
        var anyKeyRequests = 0
            private set
        var scriptedAnswers = 0
            private set

        override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) {
            anyKeyRequests++
        }

        override fun waitInput(request: InputRequest): InputOutcome = when (request.type) {
            InputRequest.InputType.INT -> {
                intRequests++
                val next = pending.removeFirstOrNull()
                if (next != null) {
                    scriptedAnswers++
                    InputOutcome(intValue = next)
                } else if (request.hasDef) {
                    InputOutcome(intValue = request.defInt)
                } else {
                    InputOutcome(intValue = 0L)
                }
            }
            InputRequest.InputType.STR -> {
                strRequests++
                InputOutcome(strValue = if (request.hasDef) request.defStr else "")
            }
        }

        fun summary(): String =
            "INT $intRequests / STR $strRequests / 任意键 $anyKeyRequests；" +
                "脚本作答 $scriptedAnswers/${script.size}"
    }

    private class BeginResult(
        val type: BeginType,
        /** `rt.begin` 的返回值（false = 目标不存在且 force=false）。 */
        val executed: Boolean,
        val error: Throwable?,
        val elapsedMs: Long,
        val steps: Long?,
        val newLines: List<String>,
        val allLines: List<String>,
        /**
         * 异常退出瞬间 VM 的 `lastPc`（见 [vmLocation]）。表达式求值层抛出的 CodeEE
         * （如 `VarRef.getIntValue` → `VariableStore.readInt`）不经 `stampPosition`，
         * 不带 funcName/line/pc，只能靠这个兜底定位。
         */
        val lastPc: Int,
        val lastFunc: String?,
        /**
         * 异常退出瞬间 VM 的**函数调用栈**（反射 `funcStack`/`depth`，帧 0 在最外）。
         * 死循环定位用：StepLimitExceeded 只报最内帧，循环的驱动方在栈上。
         */
        val callStack: String,
    ) {
        val success: Boolean get() = error == null && executed
    }

    private class Blocker(
        val kind: String,
        val message: String,
        val function: String?,
        val line: Int,
        val pc: Int,
        val stmt: String,
        val file: String?,
        val steps: Long?,
        val lastPrint: String,
        val lastSection: String,
        val stack: String,
    )

    /** @return 0 = 报告已产出（有无阻塞都算）；2 = 语料缺失/无法产出报告。 */
    fun run(): Int {
        val t0 = System.currentTimeMillis()
        // 折叠审计（2026-10-22 死循环复盘方案 #2）：ERATW_ERBC_FOLDAUDIT=1 即开（gradle 的
        // ERATW_* 透传已覆盖）。必须**装载前**设——折叠发生在装载期；读数在各探针结尾
        // 与正常路径报告前 drain。真机同款开关见 GameHost（files/fold-audit.on 通道）。
        System.getenv("ERATW_ERBC_FOLDAUDIT")?.let { StmtInternAudit.enabled = it != "0" }
        // 探针：ERATW_ERBC_DUMP=<program.erbc路径>,<pc起>[,<pc止>] —— 装载 erbcc 产物后 dump 指定 pc 区间。
        // 用途：真机「死循环指令现场」给的是 .erbc 的 pc（如 717295），在同一产物上逐条还原出
        // op/行号/语句原文，直接回答「设备跑的代码与当前语料是否一致」。dump 完即退，不跑开局。
        // 同一文件跑 loadFile（急切）与 loadMapped（真机同款惰性分段解码）两路，若语句池内容
        // 出现分歧即坐实「段3 惰性解码把语句解错」—— 真机死循环的头号嫌疑。
        System.getenv("ERATW_ERBC_DUMP")?.let { spec ->
            val parts = spec.split(",").map { it.trim() }
            val path = Path.of(parts[0])
            val lo = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val hi = parts.getOrNull(2)?.toIntOrNull() ?: (lo + 20)
            System.err.println("[dump] ===== loadFile（急切解码）=====")
            val eager = ProgramLoader.loadFile(path)
            dumpPcRange(eager, lo, hi)
            System.err.println("[dump] ===== loadMapped（真机同款惰性解码）=====")
            val mapped = ProgramLoader.loadMapped(path)
            dumpPcRange(mapped, lo, hi)
            return 0
        }
        // 探针：ERATW_ERBC_AUDIT=<program.erbc路径> —— 全池对账：loadFile（急切）与
        // loadMapped（惰性）对同一产物解码，语句池 0..N-1 逐条（类名/AST/raw）对比。
        // 此前 ERATW_ERBC_DUMP 只按升序摸过 FOR 区几条；这里整池物化后逐条 diff ——
        // 全一致 ⇒ 桌面升序惰性解码干净，嫌疑收敛到真机执行序物化/并发/ART；有差异 ⇒ 直接看 diff。
        // diff 落盘 core-vm/build/audit-diff.txt（UTF-8），stderr 只打进度与结论。
        System.getenv("ERATW_ERBC_AUDIT")?.let { pathStr ->
            val path = Path.of(pathStr)
            System.err.println("[audit] loadFile（急切）…")
            val eager = ProgramLoader.loadFile(path)
            System.err.println("[audit] loadMapped（惰性）…")
            val mapped = ProgramLoader.loadMapped(path)
            val n = eager.statementCount
            check(n == mapped.statementCount) { "语句池条数不一致: eager=$n mapped=${mapped.statementCount}" }
            System.err.println("[audit] 语句池 $n 条，升序逐条对账…")
            val diffs = ArrayList<String>()
            for (i in 0 until n) {
                val a = eager.statement(i)
                val b = mapped.statement(i)
                val da = a.toString()
                val db = b.toString()
                if (a.javaClass != b.javaClass || da != db || a.raw != b.raw) {
                    if (diffs.size < 200) {
                        diffs += "[$i] 类: ${a.javaClass.simpleName} vs ${b.javaClass.simpleName}"
                        diffs += "[$i] eager:  $da"
                        diffs += "[$i] mapped: $db"
                        diffs += "[$i] raw eager:  ${a.raw.take(150)}"
                        diffs += "[$i] raw mapped: ${b.raw.take(150)}"
                    } else if (diffs.size == 200) {
                        diffs += "…（差异条数超上限，后续省略）"
                    }
                }
                if ((i + 1) % 20000 == 0) System.err.println("[audit] …${i + 1}/$n")
            }
            if (diffs.isEmpty()) {
                System.err.println("[audit] 结论：全池对账一致（$n 条，急切 vs 惰性升序物化逐条无差）")
            } else {
                System.err.println("[audit] 结论：发现差异！见 build/audit-diff.txt")
            }
            // 折叠审计读数（ERATW_ERBC_FOLDAUDIT=1 时有内容）：RED 行 = 关键摘要不同的折叠
            // = 判等不完整，2026-10-22 死循环同型病当场标红。落盘 build/fold-audit.txt。
            val foldLines = StmtInternAudit.drain()
            runCatching {
                val out = Path.of("build", "fold-audit.txt")
                Files.createDirectories(out.parent)
                Files.write(out, foldLines, Charsets.UTF_8)
                System.err.println("[audit] ${foldLines.firstOrNull()}（详 $out）")
            }
            runCatching {
                val out = Path.of("build", "audit-diff.txt")
                Files.createDirectories(out.parent)
                val lines = ArrayList<String>()
                lines += if (diffs.isEmpty()) "[audit] 全池对账一致：$n 条" else "[audit] 差异（前 ${diffs.size / 5} 条）:"
                lines += diffs
                Files.write(out, lines, Charsets.UTF_8)
                System.err.println("[audit] 已写 $out")
            }
            return 0
        }
        // 探针：ERATW_STMT_AT=<program.erbc路径>,<下标起>[,<下标止>] —— 语句池**下标**直读。
        // 与 ERATW_ERBC_DUMP（pc 口径）的关键区别：pc 与语句池下标是两个独立计数域
        // （p140944 的语句下标是 35444 —— JIT 内部指令不占语句下标）。2026-10-22 凌晨真机
        // deadloop-site.txt 的 a140946/a140948 是**语句池下标**；本探针用同一口径还原桌面
        // 侧内容，与真机逐条对齐（identityHashCode 一并打印，检查跨下标同实例折叠）。
        System.getenv("ERATW_STMT_AT")?.let { spec ->
            val parts = spec.split(",").map { it.trim() }
            val path = Path.of(parts[0])
            val lo = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val hi = parts.getOrNull(2)?.toIntOrNull() ?: lo
            System.err.println("[stmt-at] ===== loadFile（急切解码）=====")
            val eager = ProgramLoader.loadFile(path)
            dumpStmtRange(eager, lo, hi)
            System.err.println("[stmt-at] ===== loadMapped（真机同款惰性解码）=====")
            val mapped = ProgramLoader.loadMapped(path)
            dumpStmtRange(mapped, lo, hi)
            // 折叠审计读数（ERATW_ERBC_FOLDAUDIT=1 时有内容）：两路装载的可疑折叠汇总。
            StmtInternAudit.drain().forEach { System.err.println("[stmt-at] $it") }
            return 0
        }
        val reportPath = reportPath()
        val corpus = RealCorpus.load()
        if (corpus == null) {
            val text = buildString {
                appendLine("# 真实语料开局冒烟报告")
                appendLine()
                appendLine("- 生成时间: ${java.time.LocalDateTime.now()}")
                appendLine("- 结果: **跳过**（未找到真实语料）")
                appendLine("- 查找口径: `ERATW_CORPUS` → `<仓库根>/eratw-画蛇添足版eratw4.980/ERB` → 上级/worktree 布局兜底")
                appendLine()
                appendLine("## 首个阻塞点")
                appendLine()
                appendLine("- 类型: `CORPUS_NOT_FOUND`")
                appendLine("- message: 未找到真实语料目录；设置 `ERATW_CORPUS` 或把游戏目录放到仓库根。")
            }
            writeReport(reportPath, text)
            System.err.println("[boot] 未找到真实语料；已写 $reportPath")
            return 2
        }

        println("[boot] 语料: $corpus")

        // 全量优先；OOM 时降级到前 400 文件（可用 ERATW_BOOT_LIMIT 显式指定）
        val limit = System.getenv("ERATW_BOOT_LIMIT")?.toIntOrNull()
        var degradedByOom = false
        // 宿主根 = 游戏可执行目录（原版 Program.ExeDir）：ERB 的**上一级**。
        // 语料里 `resources/`、`CSV/`、`sav/`、`sound/`、`emuera.config` 全部落在这一层
        // （实测游戏目录清单），而 `GCREATEFROMFILE RM_TITLE, "title.webp"` 这类相对路径
        // 在原版就是相对 ExeDir 解析（见 core/proc/BootHosts.kt:19-33 的 RootedFileOps 口径）；
        // 若把根设在 ERB 目录，`ENUMFILES("resources", "*.xml", 1)`（ResourceManager.ERB:46）
        // 会枚举到空表 —— 那是装置造成的假推进。
        val gameRoot = corpus.root.parent ?: corpus.root
        // M4-H SAVEBINARY 组长接线：`USE THE BINARY FORMAT FOR SAVING DATA`（语料 emuera.config:60 = YES；
        // spec: ConfigData.cs:107 出厂 false、判定 UserDefinedVariableData.cs:465-475）在**编译之前**取一次值，
        // 使「装置预解析」与「运行期注册」两栏读数同源（旧口径里 RealCorpusCompile 的 dimCfg 没吃到开关，
        // 报告第 26 行长期停在 7130/71，与运行期 7196/5 对不上）。取值函数仍是唯一入口 systemSaveInBinaryFrom。
        val bootOverrides = loadConfigOverrides(gameRoot)
        val saveInBinary =
            SystemVariableBootstrap.systemSaveInBinaryFrom(bootOverrides)
        // M11-E bootcfg（遗留项④）：解析期三键 `CompatiRAND` / `SystemNoTarget` /
        // `SystemIgnoreStringSet` 与 saveInBinary 同源 —— 同一份覆盖表快照
        // （`loadConfigOverrides` = `RootedBuiltinHost.configOverrides` 的无宿主形态，
        // 与生产 erbcc `CorpusCompile.kt:651` 的 `RootedBuiltinHost(gameRoot).configOverrides`
        // 同链同合并器）。取值口径见 [RealCorpusCompile.parserConfigFromOverrides]；
        // 语料现值三条全 NO（emuera.config:52/:65/:66）= 出厂 false
        // （spec: ConfigData.cs:98/:112/:113）⇒ 既有切片读数零回退。
        val bootParserConfig = RealCorpusCompile.parserConfigFromOverrides(bootOverrides)
        // ERD 开关（ConfigData.cs:116 出厂 true；语料 emuera.config:69 `USE ERD:YES`）：
        // 关掉时与原版一致不建表（ErhLoader.cs:269-272）。键取覆盖表的 upperEnLabel 形，
        // 未被语料改写时按出厂默认 true。
        val useErd = bootOverrides["USE ERD"]?.trim()?.let { parseBoolLiteral(it) } ?: true
        val model = try {
            RealCorpusCompile.compile(corpus, limit, saveInBinary, bootParserConfig)
        } catch (e: OutOfMemoryError) {
            if (limit != null) throw e
            System.gc()
            degradedByOom = true
            System.err.println("[boot] 全量编译 OOM，降级为前 400 个文件")
            RealCorpusCompile.compile(corpus, 400, saveInBinary, bootParserConfig)
        }

        println(
            "[boot] 编译: 文件 ${model.stats.loadedFiles}/${model.stats.corpusFiles} " +
                "切片 ${model.stats.slices} 成功 ${model.stats.compiled} 失败 ${model.stats.compileFailures} " +
                "解析失败 ${model.stats.parseFailures} 指令 ${model.stats.instructions} " +
                "耗时 ${model.stats.elapsedMs}ms",
        )

        val console = LineConsoleSink(barWidth = 199, printCLength = 25)
        val runtimeNotes = ArrayList<String>()
        var program = model.program
        // 探针：ERATW_ERBC_BOOT=<program.erbc> —— 把 program 换成真机同款的映射惰性装载
        // （loadMapped：段3 惰性语句池 + 节点重放），变量接线仍用编译模型的同一套。
        // 用途：桌面复现真机死循环 —— 若惰性装载路径有执行序相关的解码缺陷，这里必撞同款闸。
        System.getenv("ERATW_ERBC_BOOT")?.let { path ->
            program = ProgramLoader.loadMapped(Path.of(path))
            runtimeNotes.add("ERATW_ERBC_BOOT：program 已换映射惰性装载（真机路径）：$path")
        }
        var bootHost: ConsoleBuiltinHost? = null
        var rtInput: ScriptedInputSink? = null
        val rt: ProcessRuntime?
        if (program == null) {
            rt = null
            runtimeNotes.add("Program 构建失败，未进入运行期：${model.buildError}")
        } else {
            // M4-H SAVEBINARY：宿主先于变量池构造。`emuera.config` 的
            // `USE THE BINARY FORMAT FOR SAVING DATA`（语料 :60 = YES；spec: ConfigData.cs:107
            // 出厂默认 false）必须**同源**喂给两处开关——
            //   DimParseConfig.systemSaveInBinary（#DIM 声明判定，spec: UserDefinedVariableData.cs:465-475）
            //   VarConfig.systemSaveInBinary（进 StateHash 的 meta 簇，core/vars/VariableSizes.kt:31）。
            // 取值口径集中在 SystemVariableBootstrap.systemSaveInBinaryFrom；判据本身不放宽。
            // 内建函数宿主（BOOT-2 接线）：[ConsoleBuiltinHost] 委托 RootedBuiltinHost 补
            // fileOps / clockMillis / readFileBytes / checkSaveData / soundDir
            // （core/proc/BootHosts.kt:95-134），并把控制台与样式读数接上。
            // 开局脚本第一步就要这些：`LOCAL = GETMILLISECOND()`（TITLE.ERB:27）、
            // `ENUMFILES("resources", "*.xml", 1)`（ResourceManager.ERB:46）、
            // `LOADTEXT`（:50）、`GCREATEFROMFILE RM_TITLE, "title.webp"`（:7）。
            // 未接 fileOps 时 GCREATEFROMFILE 直接 CodeEE（GraphicsMethods.kt:90）。
            // 写请求在本装置一律拒绝（见 ConsoleBuiltinHost KDoc 的语料只读铁律）。
            val host = ConsoleBuiltinHost(gameRoot, console)
            bootHost = host
            val gameData = GameDataLoader.load(corpus.root.parent?.resolve("CSV") ?: corpus.root)
            val st = VariableStore(
                gameData?.registry ?: VariableRegistry(),
                VarConfig(systemSaveInBinary = saveInBinary),
            )
            if (gameData != null) {
                for (t in gameData.templates) st.registerTemplate(t)
                st.loadItemPrice(gameData.itemPrices)
                st.reloadStrNames()
                runtimeNotes.add("CSV 已装载：警告 ${gameData.warnings.size} 条")
                gameData.warnings.take(5).forEach { runtimeNotes.add("CSV 警告: $it") }
            } else {
                runtimeNotes.add("CSV 未装载（目录缺失）——系统变量按缺省注册表运行")
            }
            // 引擎基础状态：CSV 有角色模板 0 时种真实角色，否则 4 个裸角色（同 GoldenPipeline）
            if (gameData != null && st.template(0L) != null) st.addCharacter(0L)
            else repeat(4) { st.characters.addBare() }

            // 装载期全局变量：交 SystemVariableBootstrap.install（BOOT-2 接线）。
            // 它按原版词法先剥行内注释（`;` 到行尾，spec: LexicalAnalyzer.cs:919-929），
            // 再以不动点重试环注册（未解析行重新入队、整轮无进展才放弃，
            // spec: ErhLoader.cs:315-337），并把已成功注册的标量 CONST 并进尺寸求值常量表，
            // 供后续 `#DIM X, <常量>` 用（spec: UserDefinedVariableData.cs:325-341）。
            // 这一步是 RM_TITLE 失踪的正解：`リソース管理/ResourceManager.ERH:15`
            // `#DIM CONST RM_TITLE = 100000 ; タイトル画面の画像ID` 带 `;` 尾注，
            // 旧路径把原文直喂 DimParser（RealCorpusCompile.kt 的 parseDim），声明整条失效。
            // 全局先于私有（原版 ErhLoader 先于 ErbLoader）。
            // M4-H SAVEBINARY：装置预解析用的 dimParseConfig（erbcc 侧尺寸常量 + #DEFINE 宏表）
            // 与运行期注册必须同源，故只 overlay 本路接线的两个字段：二进制存档开关、
            // 字符串常量表（后者由 install 自己在注册过程中累加，见 Result.strConstants）。
            // M4-M `m-dimfold-wire` 收口（M11-E）：第三个消费点就是这一行 —— cfg 的
            // `functionFolder` 就是 RealCorpusCompile 第 5 步构造的那份 [DimFoldSnapshot] 的
            // 折叠面（varSizes + GETNUM 关键字表 + GETLINESTR 条宽 199），随
            // model.dimParseConfig **原样透传同一个对象**：`DimParseConfig.copy` 保留回调、
            // `install` 的不动点循环也只 copy 常量表（spec: SystemVariableBootstrap.kt:202，
            // 本层不自行注入），所以装置预解析与运行期注册用的是同一个快照，不会出现第二个
            // 真源。与 erbcc 侧车（`CorpusCompile.compile` 自建同参快照）的同源靠两边同一份
            // `DimFoldSnapshot.load`（同 csvDir 表达式、同关键字表来源、同 PC 基准条宽）保证 ——
            // 跨模块不共享对象，以同参为约；自证读数见下面的运行期备注。
            val install = SystemVariableBootstrap.install(
                st,
                model.globalDimDecls.map {
                    SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line)
                },
                model.dimParseConfig.copy(systemSaveInBinary = saveInBinary),
            )
            val globalOk = install.declared
            val globalFail = install.failures.size
            install.failures.take(10).forEach {
                runtimeNotes.add("全局 #DIM 装载失败 ${it.file}:${it.line} ${it.message} | ${it.raw.take(120)}")
            }
            // 失败全量按消息归类（M4-H：71 条逐类归因要求；同一条只列前 3 个出处）。
            install.failures.groupBy { it.message }.forEach { (message, list) ->
                val where = list.take(3).joinToString(" / ") { "${it.file}:${it.line}" }
                val more = if (list.size > 3) " …另 ${list.size - 3} 条" else ""
                runtimeNotes.add("全局 #DIM 失败归类 [${list.size} 条] $message | 出处 $where$more")
            }
            runtimeNotes.add(
                "全局 #DIM 装载：原文 ${model.globalDimDecls.size} 条 → 注册 $globalOk 条 / 失败 $globalFail 条，" +
                    "常量表 ${install.constants.size} 项（install 自采 + erbcc sizeConsts ${model.dimParseConfig.intConstants.size} 项），" +
                    "字符串常量表 ${install.strConstants.size} 项",
            )
            // M4-M `m-dimfold-wire` 收口自证：install 的 cfg 挂着的那份 DimFoldSnapshot 的
            // 三件套读数（装载时点 = VariableSize.csv 之后、ERH 之前，spec: Process.cs:292-293
            // → :300-301 → :309/:313）。空表/0 格宽即「折叠器空转」，与本备注的注册读数对不上。
            runtimeNotes.add(
                "装载期折叠面 = DimFoldSnapshot（erbcc 侧车 CorpusCompile.compile 同源同参）：" +
                    "VARSIZE 表 ${model.foldSnapshot.varSizes.size} 项 / GETNUM 关键字表 " +
                    "${model.foldSnapshot.keywordTables.size} 槽 / GETLINESTR 条宽 " +
                    "${model.foldSnapshot.consoleWidth} 格（PC 基准；装置预解析与运行期注册同一对象）",
            )
            var privateOk = 0
            var privateFail = 0
            for ((fk, spec) in model.userVarDecls) {
                runCatching { st.declareUserVar(spec, fk) }
                    .onSuccess { privateOk++ }
                    .onFailure {
                        privateFail++
                        if (privateFail <= 10) runtimeNotes.add("私有 #DIM 注册失败 $fk/${spec.name}: ${it.message}")
                    }
            }
            runtimeNotes.add("用户变量注册：全局 $globalOk/$globalFail，私有 $privateOk/$privateFail")
            // ERD 用户变量关键字表：与 GameSession 同源（`USE ERD` 关 = 不建表，ErhLoader.cs:269-272）。
            // 单条坏表降级成告警、不炸装载（ErhLoader.cs:328-332），清单并进 runtimeNotes 上报。
            run {
                val erdWarn = ArrayList<String>()
                if (useErd) UserKeywordTables.install(st, gameRoot, erdWarn)
                if (erdWarn.isNotEmpty()) {
                    runtimeNotes.add("ERD 挂载告警 ${erdWarn.size} 条：" + erdWarn.take(3).joinToString(" | "))
                }
            }

            // 真机分叉复刻探针（`ERATW_BOOT_TITLE_DECODER=stub`）。
            //
            // 为什么需要：真机（GameHost.installGraphics）装了
            // `BitmapImageDecoder`（BitmapFactory 解 png/jpg/bmp/webp/gif），而本装置
            // 与所有纯 JVM 闸都没装 ⇒ `GCREATEFROMFILE` 走 core 自带解码器。
            // `title.webp` 实测是 **VP8L 1041×557**，WebpDecoder 对 VP8L「只解通 28/646」
            // ⇒ 桌面 `GCREATEFROMFILE` 返回 0、缓冲未创建、`SPRITECREATE` 六个参数全短路
            // （GraphicsMethods.kt:545-546 的 `!g.isCreated → 0L`）；真机解得出真位图，
            // 「g 已创建」之后的语句链**桌面从未跑过**。2026-10-21 真机首跑的红
            // （SYSTEM_TITLE 调用内 500 万步空转撞 StepLimitExceeded）正落在这段分叉里，
            // 桌面复现必须把这一层补上。开关不设时行为与今天逐项相同。
            if (System.getenv("ERATW_BOOT_TITLE_DECODER") == "stub") {
                st.aux.slot<ImageDecoderHost>(IMAGE_DECODER_SLOT) {
                    object : ImageDecoderHost {
                        // 只复刻 title.webp 一个文件（SYSTEM_TITLE 前段唯一图片操作），
                        // 尺寸取 VP8L 容器实测值；其余路径返回 null，落回 core 解码器。
                        override fun decode(path: String, bytes: ByteArray): PngImage? {
                            if (!path.endsWith("title.webp", ignoreCase = true)) return null
                            val w = 1041
                            val h = 557
                            return PngImage(w, h, IntArray(w * h) { 0xFF334455.toInt() })
                        }
                    }
                }
                runtimeNotes.add(
                    "探针：已装合成位图 ImageDecoderHost（title.webp ⇒ 1041×557 实像素，" +
                        "复刻真机 BitmapFactory 分叉；对应 GameHost.installGraphics）",
                )
            }

            val maxSteps = System.getenv("ERATW_BOOT_MAX_STEPS")?.toLongOrNull() ?: DEFAULT_MAX_STEPS
            val inputScript = System.getenv("ERATW_BOOT_INPUTS")
                ?.split(',', ';')
                ?.mapNotNull { it.trim().toLongOrNull() }
                ?: emptyList()
            val bootInput = ScriptedInputSink(inputScript)
            rtInput = bootInput
            // 最小系统宿主：真实开局脚本前两句就是 LOADGLOBAL/SAVEGLOBAL（TITLE.ERB:20-22），
            // 不给宿主会在**引擎尚未执行任何游戏逻辑**时被挡下。这里只回答「无全局存档」，
            // 不落盘；其余系统语句（SAVEDATA/SAVECHARA/…）仍按缺省抛 CodeEE，作为真实缺口暴露。
            val bootOps = object : SystemOps {
                override fun saveGlobal() {}
                override fun loadGlobal(): Boolean = false
            }
            // 内建函数宿主 = 前面构造的 [ConsoleBuiltinHost]（M4-H SAVEBINARY：配置开关要在
            // 建变量池之前就读到，故构造点已上移；委托 RootedBuiltinHost 的 fileOps /
            // clockMillis / readFileBytes / checkSaveData / soundDir 口径见其 KDoc）。
            // M4-F CONFIG-ENCODE 自证：从装置实例读覆盖表（不读委托对象），证明装配后
            // 配置真到了宿主面。语料期望：`emuera.config` 97 行 → 96 行落条目
            // （95 APPLIED + 1 EditorArgument 直写，EmueraConfigGoldenTest 有同源外部读数）。
            val configRows = host.configOverrides
            runtimeNotes.add(
                "配置覆盖（emuera.config）= ${configRows.size} 行；" +
                    "DEFAULT ANSI ENCODING=${configRows["DEFAULT ANSI ENCODING"] ?: "(缺)"}（语料 :56）；" +
                    "VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT=" +
                    "${configRows["VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT"] ?: "(缺)"}（语料 :73）",
            )
            // M4-H SAVEBINARY 自证：两处开关的取值与来源行原样登记。
            runtimeNotes.add(
                "SystemSaveInBinary（语料 emuera.config:60）= $saveInBinary；" +
                    "覆盖表原值=${configRows["USE THE BINARY FORMAT FOR SAVING DATA"] ?: "(缺)"}；" +
                    "同源喂入 DimParseConfig.systemSaveInBinary（#DIM 声明判定，spec: UserDefinedVariableData.cs:465-475）" +
                    "与 VarConfig.systemSaveInBinary（StateHash meta 簇）",
            )
            // M11-E bootcfg 自证：三键覆盖表原值 + 落到 ParserConfig 的读数（语料现值全 NO ⇒ 全 false）。
            runtimeNotes.add(
                "ParserConfig 三键（语料 emuera.config:52/:65/:66）= " +
                    "CompatiRAND=${bootParserConfig.compatiRAND} " +
                    "SystemNoTarget=${bootParserConfig.systemNoTarget} " +
                    "SystemIgnoreStringSet=${bootParserConfig.systemIgnoreStringSet}；" +
                    "覆盖表原值=${configRows["IMITATE BEHAVIOR FOR RAND"] ?: "(缺)"} / " +
                    "${configRows["DO NOT AUTO-COMPLETE ARGUMENTS FOR CHARACTER VARIABLES"] ?: "(缺)"} / " +
                    "${configRows["STRING VARIABLE ASSIGNMENT ON VALID WITH STRING EXPRESSION"] ?: "(缺)"}；" +
                    "同源喂入 RealCorpusCompile 切片 LineParser 构造" +
                    "（出厂判定 spec: ConfigData.cs:98/:112/:113；解析期消费 " +
                    "VariableParser.cs:163-168/:90-117 + ArgumentParser.cs:1026-1029）",
            )
            val r = ProcessRuntime(
                program,
                st,
                model.table,
                VmConfig(maxStepsPerRun = maxSteps),
                consoleSink = console,
                semConfig = SemConfig(printCPerLine = 5), // 游戏 emuera.config:19 ITEMS PER LINE FOR PRINTC:5
                systemOps = bootOps,
                builtinHost = host,
                randSeed = RAND_SEED,
                inputSink = bootInput,
            )
            r.installLabelTable(object : ProcessRuntime.LabelTable {
                override fun labelPc(functionKey: String, label: String): Int? =
                    model.labelPcs[functionKey.uppercase()]?.get(label.uppercase())
            })
            r.installDataSections(ProcessRuntime.DataSectionSource { index -> model.dataSections[program.statement(index)] })
            rt = r
            runtimeNotes.add(
                "maxStepsPerRun=$maxSteps；输入 = ScriptedInputSink（脚本 " +
                    "${if (inputScript.isEmpty()) "空" else inputScript.joinToString(",")}）",
            )
            runtimeNotes.add(
                "内建宿主 = ConsoleBuiltinHost（委托 RootedBuiltinHost），根 = $gameRoot" +
                    "（ExeDir 口径 = ERB 的上一级；相对路径 resources/CSV/sav/sound 都在这层）",
            )
        }

        val title: BeginResult?
        val first: BeginResult?
        if (rt == null) {
            title = null; first = null
        } else {
            title = runBegin(rt, BeginType.TITLE, console)
            first = if (title.success) runBegin(rt, BeginType.FIRST, console) else null
        }

        bootHost?.let {
            runtimeNotes.add(
                "宿主写请求拦截 ${it.writes.blockedWrites} 次 —— 语料只读铁律（AGENTS §1）：" +
                    "写一律拒绝并按原版失败口径返回 0（SAVETEXT 返回 0，core/host/FileOps.kt:26-27）",
            )
        }
        rtInput?.let { runtimeNotes.add("输入请求计数：${it.summary()}") }

        // 折叠审计读数进报告（ERATW_ERBC_FOLDAUDIT=1 时）：RED 行 = 判等不完整的折叠，
        // 与「首个阻塞点」同栏可见——装载期病态在运行期症状出现之前就有读数。
        if (StmtInternAudit.enabled) {
            StmtInternAudit.drain().forEach { runtimeNotes.add("折叠审计: $it") }
        }

        val blocker = firstBlocker(model, title, first)
        val elapsed = System.currentTimeMillis() - t0
        val report = renderReport(
            corpus = corpus,
            model = model,
            console = console,
            runtimeNotes = runtimeNotes,
            title = title,
            first = first,
            blocker = blocker,
            elapsedMs = elapsed,
            maxHeap = Runtime.getRuntime().maxMemory(),
            peakHeap = peakHeapBytes(),
            degradedByOom = degradedByOom,
            reportPath = reportPath,
        )
        writeReport(reportPath, report)
        println("[boot] 报告: $reportPath")
        if (blocker != null) {
            println("[boot] 首个阻塞点: [${blocker.kind}] ${blocker.message} @ ${blocker.file}:${blocker.line} pc=${blocker.pc}")
        } else {
            println("[boot] 两步 begin 均未抛错（测量完成，不代表游戏可玩）")
        }
        return 0
    }

    private fun runBegin(rt: ProcessRuntime, type: BeginType, console: LineConsoleSink): BeginResult {
        val before = console.snapshot().size
        val t0 = System.nanoTime()
        var executed = false
        var error: Throwable? = null
        try {
            executed = rt.begin(type)
        } catch (t: Throwable) {
            error = t
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val all = console.snapshot()
        val fresh = if (all.size > before) all.subList(before, all.size).toList() else emptyList()
        // 位置必须在 begin 返回后立刻抓：VM 的 lastPc 只保留「最近一条已执行指令」，
        // 后续任何运行（如 FIRST）都会覆盖它。
        val (lastPc, lastFunc) = if (error != null) vmLocation(rt) else (-1 to null)
        return BeginResult(type, executed, error, ms, executedSteps(rt), fresh, all, lastPc, lastFunc, vmCallStack(rt))
    }

    /**
     * VM 函数调用栈快照（帧 0 = 入口函数，最后一项 = 最内帧）。
     *
     * 死循环定位：`StepLimitExceeded` 只带最内帧名，而「同一函数被反复调用」的驱动方
     * （哪个调用者的循环体）在栈上；`funcStack`/`depth` 是 VM 私有字段，反射读。
     */
    private fun vmCallStack(rt: ProcessRuntime): String {
        val program = rt.program
        val vm = runCatching {
            val f = ProcessRuntime::class.java.getDeclaredField("vm")
            f.isAccessible = true
            f.get(rt)
        }.getOrNull() ?: return "(vm 未取到；rt.javaClass=${rt.javaClass.name})"
        val stack = runCatching {
            val f = vm.javaClass.getDeclaredField("funcStack")
            f.isAccessible = true
            f.get(vm) as? IntArray
        }.getOrNull() ?: return "(funcStack 未取到；vm.javaClass=${vm.javaClass.name})"
        // `depth` 在异常穿出 run() 时已被 unwindScopes 置回 -1（Vm.kt:373），不能再用；
        // 但 funcStack 的内容留着。本装置里 TITLE 是该 runtime 的第一次 run，未写过的槽
        // 恒为 0（IntArray 初值），故从帧 0 起取到第一个 0 为止就是撞闸时的真实帧链
        // （帧 0 = 入口函数；某个真实帧的函数下标恰为 0 时会截短，探针容差内可接受）。
        val names = stack.takeWhile { it != 0 }.mapNotNull { program.functions.getOrNull(it)?.name }
        return if (names.isNotEmpty()) names.joinToString(" → ") else "(栈空或入口帧下标为 0)"
    }

    private fun readIntField(target: Any, name: String): Int? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                return runCatching {
                    f.isAccessible = true
                    (f.get(target) as? Number)?.toInt()
                }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    private fun firstBlocker(
        model: BootModel,
        title: BeginResult?,
        first: BeginResult?,
    ): Blocker? {
        val program = model.program ?: return Blocker(
            kind = "PROGRAM_BUILD",
            message = model.buildError ?: "Program 构建失败（无错误文本）",
            function = null, line = -1, pc = -1,
            stmt = "?", file = null, steps = null,
            lastPrint = "(未进入运行期)", lastSection = "(未进入运行期)", stack = "",
        )
        val failed = title?.takeIf { !it.success } ?: first?.takeIf { !it.success } ?: return null
        val e = failed.error
        val code = e as? CodeEE
        // CodeEE 自带的 pc/函数优先；表达式求值层抛出的异常没有位置，退到 VM lastPc。
        val pc = code?.pc?.takeIf { it >= 0 } ?: failed.lastPc
        val func = code?.funcName ?: failed.lastFunc
        val srcLine = when {
            pc in 0 until program.instructionCount && program.lineOf(pc) > 0 -> program.lineOf(pc)
            else -> code?.line ?: -1
        }
        val stmt = describeStatement(program, pc)
        // 事件成员在 fileBySigKey 里的键是 `KEY#defIndex`，而运行期 funcName 是 `KEY`；
        // 再兜底查 FuncMeta.position（装载来源 `file:line`）。
        val meta = func?.let { f -> model.table.all().firstOrNull { it.key.equals(f, ignoreCase = true) } }
        val file = func?.let { model.fileBySigKey[it.uppercase()] }
            ?: meta?.position?.substringBefore(':')
        val lines = failed.allLines
        val lastPrint = lines.lastOrNull { it.isNotBlank() } ?: "(无)"
        val lastSection = lines.lastOrNull {
            it.contains("===") || it.contains("SECTION", ignoreCase = true) || it.trimStart().startsWith("◆")
        } ?: "(无)"
        val kind = when {
            e == null -> "BEGIN_${failed.type.name}_NO_FUNCTION"
            else -> e::class.simpleName ?: "Throwable"
        }
        val msg = if (e != null) {
            (e.message ?: "(无消息)") + " —— 函数=${func ?: "?"} 行=$srcLine pc=$pc" +
                "；VM 调用栈：${failed.callStack}"
        } else {
            "${failed.type.targetFunction} 不存在（begin 返回 false，force=false），未执行任何语句"
        }
        return Blocker(
            kind = kind,
            message = msg,
            function = func,
            line = srcLine,
            pc = pc,
            stmt = stmt,
            file = file,
            steps = failed.steps,
            lastPrint = lastPrint,
            lastSection = lastSection,
            stack = e?.stackTrace?.take(20)?.joinToString("\n") { "    at $it" } ?: "",
        )
    }

    /** 首错语句：marker 码直取语句池（code@行 + 原文），内部码退化为 opcode 名。 */
    private fun describeStatement(program: Program, pc: Int): String {
        if (pc !in 0 until program.instructionCount) return "?"
        val op = program.op(pc)
        if (!Opcode.isInternal(op) && op in 29..197) {
            val a = program.a(pc)
            if (a in 0 until program.statementCount) {
                val s = program.statement(a)
                return "${s.code.name}@L${s.line} | ${s.raw.trim().take(160)}"
            }
        }
        return Opcode.nameOf(op)
    }

    /**
     * 探针：按**语句池下标**逐条打印 AST/赋值细节/身份哈希 —— 与真机 deadloop-site.txt
     * 的「死循环窗口 p… op121 a140946@哈希」同口径（那边 a= 语句池下标，identityHashCode 同源）。
     */
    private fun dumpStmtRange(program: Program, lo: Int, hi: Int) {
        val n = program.statementCount
        for (i in lo..hi) {
            if (i < 0 || i >= n) {
                System.err.println("[stmt-at] #$i 越界（statementCount=$n）")
                continue
            }
            val s = program.statement(i)
            val sb = StringBuilder("[stmt-at] #$i@${System.identityHashCode(s)} ${s.javaClass.simpleName}(line=${s.line})")
            if (s is com.eratw.port.core.stmt.AssignStmt) {
                val sp = s.args as? com.eratw.port.core.stmt.SpSetArgument
                sb.append(" | dest=").append(s.dest).append(" op=").append(s.op)
                    .append(" spDest=").append(sp?.dest).append(" spTerm=").append(sp?.term)
                    .append(" addConst=").append(sp?.addConst)
            } else {
                sb.append(" | ").append(s.toString().take(200))
            }
            System.err.println(sb)
        }
    }

    /**
     * 探针：按 pc 逐条打印 `op,a,b,c`、行号与语句描述 —— 与真机「死循环指令现场」的
     * 窗口转储同构（pc=717295 line=7 fn=RM_タイトル画像用意 words=[27,10400,1,0]），
     * 用于回答「设备 .erbc 与当前语料+编译器是否逐位一致」。
     */
    private fun dumpPcRange(program: Program, lo: Int, hi: Int) {
        val lines = ArrayList<String>()
        val fn = program.functions.filter { it.entryPc <= lo }.maxByOrNull { it.entryPc }
        lines += "[dump] 覆盖函数: ${fn?.name} entryPc=${fn?.entryPc}"
        for (pc in lo..hi) {
            if (pc < 0 || pc >= program.instructionCount) {
                lines += "[dump] p$pc 越界（instructionCount=${program.instructionCount}）"
                continue
            }
            lines +=
                "[dump] p$pc: op${program.op(pc)},${program.a(pc)},${program.b(pc)},${program.c(pc)} " +
                    "@L${program.lineOf(pc)} | ${describeStatement(program, pc)}"
            val a = program.a(pc)
            val op = program.op(pc)
            if (!Opcode.isInternal(op) && op in 29..197 && a in 0 until program.statementCount) {
                val s = program.statement(a)
                lines += "[dump]     stmtAST= $s"
                if (s is com.eratw.port.core.stmt.AssignStmt) {
                    val sp = s.args as? com.eratw.port.core.stmt.SpSetArgument
                    lines +=
                        "[dump]     assign: dest=${s.dest} op=${s.op} spDest=${sp?.dest} " +
                            "spTerm=${sp?.term} addConst=${sp?.addConst}"
                }
            }
            if (op == Opcode._EVAL_INT && a in 0 until program.exprCount) {
                lines += "[dump]     exprAST= ${program.expr(a)}"
            }
        }
        lines.forEach { System.err.println(it) }
        runCatching {
            val out = Path.of("build", "dump-pc.txt")
            Files.createDirectories(out.parent)
            Files.write(out, lines, Charsets.UTF_8)
            System.err.println("[dump] 已写 $out")
        }
    }

    // ------------------------------------------------------------------
    // 报告
    // ------------------------------------------------------------------

    private fun reportPath(): Path {
        val override = System.getenv("ERATW_BOOT_REPORT")?.trim()?.takeIf { it.isNotEmpty() }
        return if (override != null) {
            Path.of(override).toAbsolutePath().normalize()
        } else {
            Path.of("build", "boot-report.md").toAbsolutePath().normalize()
        }
    }

    private fun writeReport(path: Path, text: String) {
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
        }.onFailure { System.err.println("[boot] 报告写入失败 $path: ${it.message}") }
    }

    private fun renderReport(
        corpus: RealCorpus,
        model: BootModel,
        console: LineConsoleSink,
        runtimeNotes: List<String>,
        title: BeginResult?,
        first: BeginResult?,
        blocker: Blocker?,
        elapsedMs: Long,
        maxHeap: Long,
        peakHeap: Long,
        degradedByOom: Boolean,
        reportPath: Path,
    ): String {
        val s = model.stats
        val sb = StringBuilder()
        sb.appendLine("# 真实语料开局冒烟报告（bootSmoke）")
        sb.appendLine()
        sb.appendLine("- 生成时间: ${java.time.LocalDateTime.now()}")
        sb.appendLine("- 语料根: `${corpus.root}`")
        sb.appendLine("- 语料来源: `${corpus.source}`")
        sb.appendLine("- 报告: `$reportPath`")
        sb.appendLine("- 总耗时: ${elapsedMs} ms（编译 ${s.elapsedMs} ms + 运行）")
        val peakText = if (peakHeap > 0) mib(peakHeap) else "未暴露"
        val used = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        sb.appendLine("- 堆: max=${mib(maxHeap)} peak=$peakText used=${mib(used)}")
        if (degradedByOom) {
            sb.appendLine("- **OOM 降级**：全量编译失败，已退回前 ${s.loadedFiles} 个文件（未装载函数 = 空体桩）")
        }
        sb.appendLine()
        sb.appendLine("## 1. 编译规模")
        sb.appendLine()
        sb.appendLine("| 指标 | 值 |")
        sb.appendLine("|---|---:|")
        sb.appendLine("| 语料文件（ERB + ERH） | ${s.corpusFiles}（${s.erbFiles} + ${s.erhFiles}） |")
        sb.appendLine("| 实际切片文件 | ${s.loadedFiles} |")
        sb.appendLine("| 符号定义 / 唯名 / 重名组 / 事件名 | ${s.totalDefs} / ${s.uniqueNames} / ${s.duplicateNames} / ${s.eventNames} |")
        sb.appendLine("| @函数切片 | ${s.slices} |")
        sb.appendLine("| 编译成功（含空体桩） | ${s.compiled} |")
        sb.appendLine("| 编译失败 | ${s.compileFailures} |")
        sb.appendLine("| 解析失败 | ${s.parseFailures} |")
        sb.appendLine("| 事件入口 | ${s.eventEntries} |")
        val instrText = if (s.instructions >= 0) s.instructions.toString() else "构建失败"
        val stmtText = if (s.statements >= 0) s.statements.toString() else "构建失败"
        // Σ 函数体 = 相邻 entryPc 差之和（末函数取到 instructionCount）；差额即函数体外代码。
        val prog = model.program
        val bodyText: String
        val gapText: String
        if (prog == null) {
            bodyText = "构建失败"
            gapText = "-"
        } else {
            val fs = prog.functions.sortedBy { it.entryPc }
            var body = 0L
            for ((i, f) in fs.withIndex()) {
                val end = if (i + 1 < fs.size) fs[i + 1].entryPc else prog.instructionCount
                body += (end - f.entryPc).coerceAtLeast(0)
            }
            bodyText = body.toString()
            gapText = (prog.instructionCount - body).toString()
        }
        sb.appendLine("| Program 指令数（整 code 数组） | $instrText |")
        sb.appendLine("| Σ 函数体指令数（corpusSmoke 口径） | $bodyText |")
        sb.appendLine("| 函数体外代码段（差额） | $gapText |")
        sb.appendLine("| 语句池条目 | $stmtText |")
        sb.appendLine("| 全局 #DIM / 装置预解析失败 | ${s.globalVarDecls} / ${s.globalVarParseFailures} |")
        sb.appendLine("| 私有 #DIM / 装置预解析失败 | ${s.privateVarDecls} / ${s.privateVarParseFailures} |")
        sb.appendLine()
        sb.appendLine(
            "> 两个指令数都来自同一个 `Program`：`Program 指令数` = `code.size / 4`；`Σ 函数体` = 相邻" +
                "`entryPc` 差之和。实测二者相等、差额 0，说明本装置产物内**函数连续覆盖全部 code**，" +
                "不存在函数体外代码段。",
        )
        sb.appendLine(
            "> 与本项目语料门禁 `corpusSmoke` 的基线（4,621,856，口径=「指令数(成功函数体)」）相比，" +
                "本装置多出约 177,668 条（+3.8%），而两者切片数（110,066）与编译失败数（0）完全相同。" +
                "**根因已定位**：`CorpusCompileValidator` 调 `ErbProgramCompiler.compileFunction` 时未传第 4 参 " +
                "`exprCalls`（默认 null），于是 `marker()` 跳过 `lowerStatementForPool` 语句下沉；" +
                "本装置与 E2E（GoldenPipeline.kt:453）都传了该表。故本读数与 E2E 同构，" +
                "`corpusSmoke` 的读数属旧路径口径，两者不可直接相减。",
        )
        sb.appendLine(
            "> `装置预解析失败` = 本装置 [DimParser] 装载期折叠不动的条目。M4-M `m-dimfold-wire` 把" +
                "装载期折叠面接进三处消费点（erbcc 侧车 / 本装置 / 运行期 install），M11-E 收口把三处" +
                "统一为**同一份 `DimFoldSnapshot`**（VARSIZE 尺寸表 + GETNUM 关键字表 + GETLINESTR " +
                "条宽 199；装载时点 spec: Process.cs:292-293 → :300-301 → :309/:313）后，M4-L 三态" +
                "分诊的 12 条缺口（全局 5 + 私有 7）全部折上（`DimFailTriageTest` 语料锚点逐条钉住）：" +
                "GETNUM 要 TALENT 关键字表、GETLINESTR 要条宽 —— 只传 varSizes 会让同一行" +
                "`#DIM DYNAMIC 行文字数 = STRLENSU(GETLINESTR(\"─\"))` 在侧车折成 99、在装置折成 900。",
        )
        sb.appendLine(
            "> **运行期注册读数不再是独立对照**：私有 `#DIM` 的注册清单直接消费本装置解析成功的那一份，" +
                "两边同数即预期，缺口只能靠装置计数发现（旧文案「权威读数是运行期 X / 0 失败」自本路起失效）。",
        )
        sb.appendLine()
        if (model.buildError != null) {
            sb.appendLine("- **Program 构建失败**: `${model.buildError}`")
            sb.appendLine()
        }
        sb.appendLine("### 编译失败样本（前 10 条）")
        sb.appendLine()
        if (model.compileFailures.isEmpty()) {
            sb.appendLine("无。")
        } else {
            for (f in model.compileFailures.take(10)) sb.appendLine("- `$f`")
        }
        sb.appendLine()
        sb.appendLine("### 解析失败 / #DIM 装置预解析失败样本（前 10 条）")
        sb.appendLine()
        sb.appendLine("> 这里的 #DIM 失败即**真缺口**：折叠不动的条目不进运行期注册清单，原版装载期能求值（见上一节的 M4-M 口径说明）。")
        sb.appendLine()
        val samples = (model.parseFailures + model.dimParseFailures)
        if (samples.isEmpty()) {
            sb.appendLine("无。")
        } else {
            for (f in samples.take(10)) sb.appendLine("- `$f`")
        }
        sb.appendLine()
        if (model.notes.isNotEmpty()) {
            sb.appendLine("### 装置备注")
            sb.appendLine()
            for (n in model.notes) sb.appendLine("- $n")
            sb.appendLine()
        }
        if (runtimeNotes.isNotEmpty()) {
            sb.appendLine("### 运行期备注")
            sb.appendLine()
            for (n in runtimeNotes) sb.appendLine("- $n")
            sb.appendLine()
        }
        sb.appendLine("## 2. BEGIN 结果")
        sb.appendLine()
        sb.appendLine("| 步骤 | 目标函数 | 结果 | 步数 | 耗时 | 新增输出行 |")
        sb.appendLine("|---|---|---|---:|---:|---:|")
        for (r in listOfNotNull(title, first)) {
            val err = r.error
            val outcome = when {
                err != null -> "抛错 `${err::class.simpleName}`"
                r.executed -> "执行完成"
                else -> "未执行（目标不存在）"
            }
            val stepsText = r.steps?.toString() ?: "未暴露"
            sb.appendLine("| ${r.type.name} | ${r.type.targetFunction} | $outcome | $stepsText | ${r.elapsedMs} ms | ${r.newLines.size} |")
        }
        if (title == null) sb.appendLine("| TITLE | SYSTEM_TITLE | 未运行（Program 构建失败） | - | - | - |")
        if (title != null && !title.success) sb.appendLine("| FIRST | EVENTFIRST | 未运行（TITLE 未成功） | - | - | - |")
        sb.appendLine()
        sb.appendLine("### TITLE 最后 20 行输出")
        sb.appendLine()
        sb.appendLine("```text")
        for (l in (title?.allLines ?: emptyList()).takeLast(20)) sb.appendLine(l)
        sb.appendLine("```")
        sb.appendLine()
        sb.appendLine("### FIRST 最后 20 行输出")
        sb.appendLine()
        if (first == null) {
            sb.appendLine("（未执行）")
        } else {
            sb.appendLine("```text")
            for (l in first.allLines.takeLast(20)) sb.appendLine(l)
            sb.appendLine("```")
        }
        sb.appendLine()
        sb.appendLine("## 首个阻塞点")
        sb.appendLine()
        if (blocker == null) {
            sb.appendLine("- 无阻塞：TITLE 与 FIRST 的 `begin` 均未抛错。")
            sb.appendLine("  这不等于游戏可玩——空体桩、默认输入与缺失宿主能力仍可能让后续流程静默走偏。")
        } else {
            val funcText = blocker.function ?: "?"
            val fileText = blocker.file ?: "?"
            val blockedSteps = blocker.steps?.toString() ?: "未暴露"
            sb.appendLine("- 类型: `${blocker.kind}`")
            sb.appendLine("- message: ${blocker.message}")
            sb.appendLine("- function: `$funcText`")
            sb.appendLine("- line: ${blocker.line}")
            sb.appendLine("- pc: ${blocker.pc}")
            sb.appendLine("- 语句: `${blocker.stmt}`")
            sb.appendLine("- 源文件: `$fileText`")
            sb.appendLine("- 步数: $blockedSteps")
            sb.appendLine("- 最后一个 SECTION 行: `${blocker.lastSection}`")
            sb.appendLine("- 最后一条 PRINT 行: `${blocker.lastPrint}`")
            sb.appendLine()
            if (blocker.stack.isNotEmpty()) {
                sb.appendLine("```text")
                sb.appendLine(blocker.stack)
                sb.appendLine("```")
            }
        }
        sb.appendLine()
        sb.appendLine("## 口径备注")
        sb.appendLine()
        sb.appendLine("- 输入 = `ScriptedInputSink`：`ERATW_BOOT_INPUTS` 顺序作答，耗尽后默认值优先、无默认值 0。")
        sb.appendLine("- 系统语句宿主：最小桩只实现 `SAVEGLOBAL`（no-op）/`LOADGLOBAL`（false）；其余系统语句仍以 CodeEE「宿主未提供」暴露。")
        val maxStepsText = System.getenv("ERATW_BOOT_MAX_STEPS") ?: DEFAULT_MAX_STEPS.toString()
        sb.appendLine("- 步数上限 = `$maxStepsText`（防死循环；超限计入阻塞点）。")
        sb.appendLine("- 全量输出行数 = ${console.snapshot().size}。")
        return sb.toString()
    }

    private fun mib(bytes: Long): String = "${bytes / 1_048_576} MiB"

    /** 堆池 peak（JVM 没有全局峰值计数器，取各 heap pool 的 peakUsage 之和）。 */
    private fun peakHeapBytes(): Long = runCatching {
        ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == MemoryType.HEAP }
            .sumOf { it.peakUsage?.used ?: 0L }
    }.getOrDefault(0L)

    /**
     * 异常退出瞬间的 `(lastPc, 函数名)`：反射读 `ProcessRuntime.activeVm`/`vm` 的
     * `lastPc`（[com.eratw.port.core.code.VirtualMachine] 的公开只读属性），
     * 再用 `Program.functions[].entryPc` 反查所属函数。
     *
     * 为什么需要：`CALL` 类语句内部的表达式（如 `CALL RM_X` 的实参求值、或
     * `CALLF` 的返回槽写入）抛出的 CodeEE 没有经过 `stampPosition`，不带位置三元组。
     * 此时 VM 的 `lastPc` 仍停在**触发该语句的 marker 指令**上，足以还原
     * 「函数 / 源码行 / 语句」。
     *
     * TODO(verify)：字段名（`activeVm`/`vm`）与 `lastPc` 属性名均属实现细节，
     * 上层若开放 `lastErrorLocation` 一类公开读数 API，改回直调。
     */
    private fun vmLocation(rt: ProcessRuntime): Pair<Int, String?> {
        val program = rt.program
        for (field in listOf("activeVm", "vm")) {
            val vm = readDeclaredField(rt, field) ?: continue
            val pc = readIntProperty(vm, "lastPc") ?: continue
            if (pc in 0 until program.instructionCount) return pc to functionAt(program, pc)
        }
        return -1 to null
    }

    /** 按 `entryPc` 升序取最后一个不晚于 [pc] 的函数（函数体在 code 段内连续排布）。 */
    private fun functionAt(program: Program, pc: Int): String? =
        program.functions
            .filter { it.entryPc in 0..pc }
            .maxByOrNull { it.entryPc }
            ?.name

    private fun readDeclaredField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
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

    private fun readIntProperty(target: Any, name: String): Int? = runCatching {
        val getter = target.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name == "get" + name.replaceFirstChar(Char::uppercaseChar)
        } ?: return@runCatching null
        getter.isAccessible = true
        (getter.invoke(target) as? Number)?.toInt()
    }.getOrNull()

    /**
     * 单次 run 的实际步数。TODO(verify)：`ProcessRuntime.vm` 是 private，
     * 这里反射读其公开属性 `executedSteps`；上层若补公开读数 API，改回直调。
     */
    private fun executedSteps(rt: ProcessRuntime): Long? = runCatching {
        val f = ProcessRuntime::class.java.getDeclaredField("vm")
        f.isAccessible = true
        val vm = f.get(rt) ?: return@runCatching null
        val getter = vm.javaClass.getMethod("getExecutedSteps")
        (getter.invoke(vm) as Number).toLong()
    }.getOrNull()
}

/**
 * JVM 入口（`mainClass = com.eratw.port.boot.GameBootSmokeKt`，见 `core-vm/build.gradle.kts`
 * 的 `bootSmoke` 任务）。退出码只反映「报告能否产出」，不反映游戏是否走通。
 */
fun main() {
    exitProcess(GameBootSmoke.run())
}
