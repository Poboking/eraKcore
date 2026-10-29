package com.eratw.port.boot

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.sem.DualPathEnv
import com.eratw.port.core.sem.DualPathObs
import com.eratw.port.core.sem.FlatStmtSource
import com.eratw.port.core.sem.executeTreePath
import com.eratw.port.core.sem.firstDiff
import com.eratw.port.core.sem.observe
import com.eratw.port.core.sem.sampleState
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * T2-64 波次 C（lane G）：**真语料双路等价对拍**（`:core-vm:dualPathAudit`）。
 *
 * 装载 `program.erbc` → `ensureFlatStmtSource()`（生产同一条扁平池）→ 从语句池 0 号起逐条
 * 对**同一语句**双跑：
 *
 * - 树路径：`StatementExecutor.execute(stmt, ctx)`
 * - 扁平路径：`StatementExecutor.execute(source, index, ctx)`
 *
 * 两侧各持一份**干净语境**（独立 `VariableStore` / sink / `ExecContext`），并**锁步**推进：
 * 第 i 条语句在 A 侧（树）与 B 侧（扁平）各执行一次，比较 [observe] 的四个轴
 * （控制台 / `ExecResult` / 异常文案+位置 / 变量写面）+ 三个辅助读数（抽样终态、
 * 宿主钩子调用、让出边界计数）。任何一处不等即 FAIL 并停在**首次差异**处（状态一旦分叉，
 * 后续每一条都会连带不等 —— 首差才是可归因的信号）。
 *
 * 判据：`diff = 0`。退出码 0 = 全过；1 = 有差异；2 = 产物缺失；3 = OOM。
 *
 * ## 为什么两侧要锁步而不是「逐条重置」
 *
 * 逐条重建两份全量声明变量池（真语料 `#DIM` 万余条）在本装置的量级上不可行；锁步的前提是
 * 「若两路等价，则两侧状态恒等」，因此任何分叉都会在发生点被抓住。这也是 sweep 的同一口径
 * （前序语句的状态残留是**共同输入**，不是噪声源）。
 *
 * ## 「可安全执行」判定表（跳过族与理由）
 *
 * | 族 | 语句 | 理由 |
 * |---|---|---|
 * | 输入/等待 | INPUT / INPUTS / INPUTANY / INPUTMOUSEKEY / ONEINPUT / ONEINPUTS / BINPUT / BINPUTS / ONEBINPUT / ONEBINPUTS / TINPUT / TINPUTS / TONEINPUT / TONEINPUTS / WAIT / WAITANYKEY / FORCEWAIT / TWAIT | 真实宿主会**阻塞**（本装置只装了立即作答的假 sink 也会掩盖等待语义差异，且语句层等待是阻塞式） |
 * | 帧让出 | AWAIT | 依赖宿主定时器；本装置用记录型 sink 会让它「不睡」而失真，且它的让出边界计数已在单测覆盖 |
 * | 存档/系统宿主 | SAVEDATA / LOADDATA / DELDATA / SAVEGAME / LOADGAME / SAVEGLOBAL / LOADGLOBAL / SAVECHARA / LOADCHARA / UPDATECHECK / CALLTRAIN / STOPCALLTRAIN / DOTRAIN / UPCHECK / CUPCHECK | 需要真实宿主能力（写盘 / 存档界面 / 网络），本装置给假宿主等价于把语义替换成桩 |
 * | 变量文件 | LOADVAR / SAVEVAR | 写盘；且读侧要 proc 的变量还原通道 |
 * | 状态机切换 | BEGIN / FORCE_BEGIN | 切 BEGIN 入口属 proc 状态机，单条执行只产 `ExecResult.Begin` 载荷，真语料里它后面必然跟着整段流程（跳过不掉语义，见单测） |
 * | 宿主原生 | CALLSHARP | 需要按名字注册的原生方法；语料实测零处使用 |
 * | 宿主时钟 | GETTIME | `BuiltinHost.clockMillis` 两次调用可能跨毫秒边界 ⇒ 假差异源 |
 *
 * 其余语句（含 PRINT 家族 / SET / BAR / 数组族 / 角色表族 / DATA 族 / QUIT 族 / RANDOMIZE /
 * 图形音频族）全部执行：它们只依赖 `VariableStore` 与 Noop sink，两侧输入相同即语义相同。
 * **PRINTDATA / STRDATA 在本装置里是"无数据节"形态**（`ctx.dataSections = null`）：数据节键
 * 当前是语句实例（proc 的 `SidecarTables`），本装置不装配 proc 侧车，故这两条走「空供给即
 * no-op」分支；有数据节的一支由 `DualPathParityTest` 覆盖。
 *
 * ## 环境变量
 *
 * - `ERATW_ERBC` / `ERATW_ERBC_DIR`：产物路径（后者要求目录里有 `program.erbc`；
 *   注意 `erbc.v1` 有内层同名目录）。
 * - `ERATW_DUAL_LIMIT`：最多扫多少条**可执行**语句，缺省 200000。
 * - `ERATW_DUAL_SECONDS`：墙钟预算（秒），缺省 240；到点收口并报已扫条数（仍要求 diff=0）。
 * - `ERATW_DUAL_MAPPED=1`：走 `ProgramLoader.loadMapped`（惰性档）。注意扁平池构建会
 *   整池物化语句（`ensureFlatStmtSource` 的语义），惰性档的装载收益在本装置上被吃掉。
 *
 * 输出全 ASCII：语料字符串几乎全是中文，宿主 PowerShell 按 GBK 解码 stdout 会花 ——
 * 差异详情里的非 ASCII 一律转义成 `\uXXXX`。
 */
fun main() {
    val erbc = resolveErbc() ?: run {
        System.err.println(
            "[dual-audit] usage: ERATW_ERBC=<program.erbc> or ERATW_ERBC_DIR=<erbc.v1 dir> " +
                "gradle :core-vm:dualPathAudit",
        )
        exitProcess(2)
    }
    if (!Files.isRegularFile(erbc)) {
        System.err.println("[dual-audit] FAIL: erbc not found: $erbc")
        exitProcess(2)
    }

    val limit = System.getenv("ERATW_DUAL_LIMIT")?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_LIMIT
    val budgetMs = (System.getenv("ERATW_DUAL_SECONDS")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        ?: DEFAULT_SECONDS) * 1000L
    val mapped = System.getenv("ERATW_DUAL_MAPPED") == "1"

    val t0 = System.currentTimeMillis()
    println("[dual-audit] load (" + (if (mapped) "lazy/mmap" else "eager") + "): $erbc")
    val program = try {
        if (mapped) ProgramLoader.loadMapped(erbc) else ProgramLoader.loadFile(erbc)
    } catch (e: OutOfMemoryError) {
        System.err.println("[dual-audit] OOM while loading (add heap or use ERATW_DUAL_MAPPED=1)")
        exitProcess(3)
    }
    val tLoad = System.currentTimeMillis()
    println(
        "[dual-audit] loaded in ${tLoad - t0} ms; stmts=${program.statementCount} " +
            "exprs=${program.exprCount}",
    )

    val metadata = readMetadata(erbc)
    // 计数包装：波次 C 的判据是「直读语句一次都不许回落 statementAt」，所以要数真实调用。
    val source = CountingFlatSource(program.ensureFlatStmtSource())
    val tFlat = System.currentTimeMillis()
    println("[dual-audit] flat pool built in ${tFlat - tLoad} ms")

    val tree = DualPathEnv(newStore(metadata)).also { env ->
        println("[dual-audit] store: " + storeShape(env.store))
    }
    val flat = DualPathEnv(newStore(metadata))
    val startState = sampleState(tree.store)
    if (startState != sampleState(flat.store)) {
        System.err.println("[dual-audit] FAIL: the two fresh stores are not identical (harness premise)")
        exitProcess(1)
    }

    var scanned = 0
    var skipped = 0
    var directReads = 0
    var fallbacks = 0
    var i = 0
    val total = program.statementCount
    var stopBy = "END_OF_POOL"
    var diffField: String? = null
    var diffIndex = -1
    var diffCode = ""
    while (i < total) {
        val stmt: Statement = program.statement(i)
        if (stmt.code in SKIP_CODES) {
            skipped++
            i++
            continue
        }
        val a = observe(tree, stmt, i, probeSeed = i) { executeTreePath(tree.executor, stmt, tree.ctx) }
        val callsBefore = source.flatStatementAtCalls
        val b = observe(flat, stmt, i, probeSeed = i) { flat.executor.execute(source, i, flat.ctx) }
        // T2-64 波次 C：判据从「按 tag 静态猜」改成**数实际回落次数** —— 直读语句一次都不许调 statementAt。
        if (source.flatStatementAtCalls == callsBefore) directReads++ else fallbacks++
        val field = a.firstDiff(b)
        if (field != null) {
            diffField = field
            diffIndex = i
            diffCode = stmt.code.name
            printDiff(field, a, b)
            break
        }
        scanned++
        i++
        if (scanned >= limit) {
            stopBy = "LIMIT"
            break
        }
        if (System.currentTimeMillis() - t0 > budgetMs) {
            stopBy = "BUDGET"
            break
        }
        if (scanned % 50000 == 0) {
            println(
                "[dual-audit] ...scanned=$scanned skipped=$skipped cursor=$i " +
                    "elapsedMs=${System.currentTimeMillis() - t0}",
            )
        }
    }
    val elapsed = System.currentTimeMillis() - t0

    if (diffField != null) {
        println(
            "[dual-audit] FAIL: first diff at stmt=$diffIndex code=$diffCode field=$diffField " +
                "(scanned=$scanned skipped=$skipped elapsedMs=$elapsed)",
        )
        exitProcess(1)
    }
    println(
        "[dual-audit] PASS: scanned=$scanned skipped=$skipped stopBy=$stopBy " +
            "direct=$directReads fallback=$fallbacks statementAtCalls=${source.flatStatementAtCalls} " +
            "totalStatements=$total streamInts=${source.argStreamBase().size} elapsedMs=$elapsed",
    )
    if (source.flatStatementAtCalls != 0) {
        System.err.println(
            "[dual-audit] FAIL: 直读执行器仍回落了 statementAt ${source.flatStatementAtCalls} 次" +
                "（波次 C 要求 37 个 tag 全量直读）",
        )
        exitProcess(1)
    }
    exitProcess(0)
}

/**
 * 扁平源计数包装：数 `statementAt` 的真实调用次数（波次 C 起应为 0 —— 执行器不再有回落支）。
 */
private class CountingFlatSource(private val delegate: FlatStmtSource) : FlatStmtSource by delegate {
    var flatStatementAtCalls: Int = 0
        private set

    override fun statementAt(index: Int): Statement {
        flatStatementAtCalls++
        return delegate.statementAt(index)
    }
}

private const val DEFAULT_LIMIT = 200_000
private const val DEFAULT_SECONDS = 240L

/** 无 CSV 时种下的裸角色数（与 `GameSession.BARE_CHARACTERS` 同值）。 */
private const val BARE_CHARACTERS = 4

/**
 * 「可安全执行」判定表的代码面（理由见文件 KDoc 的同名表）。
 *
 * 全部按 [FunctionCode] 精确匹配：**不做名字前缀匹配**，避免 `PRINT` 家族被误伤。
 */
private val SKIP_CODES: Set<FunctionCode> = setOf(
    // 输入 / 等待（阻塞）
    FunctionCode.INPUT, FunctionCode.INPUTS, FunctionCode.INPUTANY, FunctionCode.INPUTMOUSEKEY,
    FunctionCode.ONEINPUT, FunctionCode.ONEINPUTS, FunctionCode.BINPUT, FunctionCode.BINPUTS,
    FunctionCode.ONEBINPUT, FunctionCode.ONEBINPUTS, FunctionCode.TINPUT, FunctionCode.TINPUTS,
    FunctionCode.TONEINPUT, FunctionCode.TONEINPUTS,
    FunctionCode.WAIT, FunctionCode.WAITANYKEY, FunctionCode.FORCEWAIT, FunctionCode.TWAIT,
    // 帧让出（宿主定时器）
    FunctionCode.AWAIT,
    // 存档 / 系统宿主能力
    FunctionCode.SAVEDATA, FunctionCode.LOADDATA, FunctionCode.DELDATA,
    FunctionCode.SAVEGAME, FunctionCode.LOADGAME,
    FunctionCode.SAVEGLOBAL, FunctionCode.LOADGLOBAL,
    FunctionCode.SAVECHARA, FunctionCode.LOADCHARA,
    FunctionCode.UPDATECHECK, FunctionCode.CALLTRAIN, FunctionCode.STOPCALLTRAIN,
    FunctionCode.DOTRAIN, FunctionCode.UPCHECK, FunctionCode.CUPCHECK,
    // 变量文件读写
    FunctionCode.LOADVAR, FunctionCode.SAVEVAR,
    // BEGIN 状态机切换
    FunctionCode.BEGIN, FunctionCode.FORCE_BEGIN,
    // 宿主原生方法
    FunctionCode.CALLSHARP,
    // 宿主时钟（两次调用可能跨毫秒）
    FunctionCode.GETTIME,
)

private fun resolveErbc(): Path? {
    val direct = System.getenv("ERATW_ERBC")
    if (!direct.isNullOrBlank()) return Path.of(direct)
    val dir = System.getenv("ERATW_ERBC_DIR") ?: return null
    if (dir.isBlank()) return null
    val d = Path.of(dir)
    val p = d.resolve("program.erbc")
    return when {
        Files.isRegularFile(p) -> p
        Files.isRegularFile(d) -> d
        else -> null
    }
}

/** 侧车 `program.meta`（缺失 = 裸变量池，`#DIM` 全走「未定义的变量」分支，两路仍可比）。 */
private fun readMetadata(erbc: Path): BootMetadata? {
    val meta = erbc.resolveSibling("program.meta")
    if (!Files.isRegularFile(meta)) {
        println("[dual-audit] meta: (absent) $meta -> bare store")
        return null
    }
    return try {
        BootMetadata.read(meta).also {
            println(
                "[dual-audit] meta: globalDims=${it.globalDims.size} " +
                    "privateDims=${it.privateDims.size} functions=${it.functions.size}",
            )
        }
    } catch (t: Throwable) {
        System.err.println("[dual-audit] meta read failed (${t.javaClass.simpleName}); bare store")
        null
    }
}

/**
 * 建一份与产线同形的变量池：系统变量（缺省注册表）+ 侧车里的全局 `#DIM` + 私有 `#DIM` +
 * 裸角色。CSV 不在本装置的环境契约里（`ERATW_GAMEDIR` 未导出），所以角色模板为空，
 * 与 `GameSession` 无 CSV 分支同形（`BARE_CHARACTERS`）。
 */
private fun newStore(metadata: BootMetadata?): VariableStore {
    val store = VariableStore()
    if (metadata == null) {
        repeat(BARE_CHARACTERS) { store.characters.addBare() }
        return store
    }
    val decls = metadata.globalDims.map {
        SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line)
    }
    SystemVariableBootstrap.install(store, decls, metadata.dimParseConfig)
    for (d in metadata.privateDims) {
        runCatching { store.declareUserVar(d.spec, d.functionKey) }
    }
    repeat(BARE_CHARACTERS) { store.characters.addBare() }
    return store
}

/** 变量池形状（ASCII 读数）。 */
private fun storeShape(store: VariableStore): String = buildString {
    append("charas=").append(store.characters.count)
    append(" registryDefs=").append(store.registry.all().size)
    append(" userVars=").append(store.allUserVars().size)
}

private fun printDiff(field: String, a: DualPathObs, b: DualPathObs) {
    println("[dual-audit]   field=$field")
    when (field) {
        "console" -> printListDiff("console", a.console, b.console)
        "input" -> printListDiff("input", a.input, b.input)
        "host" -> printListDiff("host", a.host, b.host)
        "writes" -> printListDiff("writes", a.writes, b.writes)
        "state" -> printListDiff("state", a.state, b.state)
        "result" -> println("[dual-audit]   tree=${a.result} flat=${b.result}")
        "error" -> println("[dual-audit]   tree=${ascii(a.error)} flat=${ascii(b.error)}")
        "yieldBoundary" ->
            println("[dual-audit]   tree=${a.yieldBoundary} flat=${b.yieldBoundary}")
    }
}

private fun printListDiff(label: String, a: List<String>, b: List<String>) {
    val n = minOf(a.size, b.size)
    var idx = -1
    for (k in 0 until n) {
        if (a[k] != b[k]) {
            idx = k
            break
        }
    }
    if (idx < 0) {
        // 只有长度不同（前缀一致）
        println("[dual-audit]   $label: sizes ${a.size} vs ${b.size}, prefix equal")
        return
    }
    println("[dual-audit]   $label[$idx]: tree=${ascii(a[idx])} flat=${ascii(b[idx])}")
}

/** 非 ASCII 一律转义（宿主 PowerShell 按 GBK 解码 stdout）。 */
private fun ascii(s: String?): String {
    if (s == null) return "<null>"
    val sb = StringBuilder(s.length)
    for (c in s) {
        if (c.code in 0x20..0x7E) sb.append(c) else sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
    }
    return sb.toString()
}
