package com.eratw.port.vmdbg

import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * vmdbg CLI 入口（CONTRACT-DBG1 路2）：子命令式、无交互、面向 agent。
 *
 * ## 全局约定
 *
 * - **选项可在任意位置**（全局与子命令选项共用一个解析器，见 [Args]）；子命令 = 第一个位置参数。
 * - `--erbc <erbc.v1 目录>` / `--game <游戏根>` 优先；缺省读环境变量 `ERATW_ERBC_DIR` /
 *   `ERATW_GAMEDIR`（与 `erbcBootProfile` 同名同义，core-vm/build.gradle.kts:196-209 的旋钮口径，
 *   不发明第三套名字）。
 * - 退出码：`0` = 命令成功（call/trace = PASS；where = 已读到现场或无错误记录；
 *   sweep/ledger/disasm/var/vars = 正常完成）；`1` = 用法错误或装配失败；`2` = 目标不存在
 *   （call/trace 的 NOT_FOUND、var 的未定义变量）；`3` = 运行期脚本错误（call/trace 的
 *   CODEEE / STEP_LIMIT / STACK_OVERFLOW / WALL_LIMIT / ERROR）。sweep 的失败**不是**工具错误
 *   —— 挖虫结果全部落 `--out`，进程退出码恒 0。
 * - 输出给人读也给脚本读：机器可断言的字段用 `key=value` 行，人看的补充段（控制台尾、
 *   反汇编正文）跟在 `---` 分隔行之后。
 * - **语料只读铁律的落地点在 [Assembler]**：存档层写侧全部重定向到临时沙箱目录，
 *   游戏根只读；`writeRoot` 保持 null（SAVETEXT / OUTPUTLOG 拒写）。
 *
 * ## 子命令（W1 交付集合）
 *
 * | 命令 | 语义 |
 * |---|---|
 * | `call <函数名> [--args 0,1,...] [--steps N]` | runtime.callFunction 调任意函数，输出状态/位置/步数/耗时/控制台尾 10 行 |
 * | `trace <函数名> [--steps N] [--capacity N] [--out 文件]` | 依赖路1 语句探针（StatementProbe），环形缓冲逐语句轨迹 |
 * | `where` | 最近一次错误的现场（call 落盘的 last-error.json，经 `--work` 共享） |
 * | `var <名字>[i,j]` / `vars <glob>` | 读单变量 / 枚举变量名 |
 * | `disasm <pc> [-n 条数]` | Program.op/a/b/c/lineOf + Disassembler 还原指令区间 |
 * | `sweep [--out 目录] [--only regex] [--skip-methods] [--shard i/n] [--resume]` | 全量函数清扫（见 [CmdSweep]） |
 * | `ledger diff --base <sweep.jsonl> --head <sweep.jsonl>` | 失败指纹台账 diff（见 [CmdLedger]） |
 * | `set <名字>[i,j] = <值>` / `cheat <配方>` | 状态注入 + 审计 trail（DBG4 P1，见 [CmdSet]） |
 */
fun main(args: Array<String>) {
    exitProcess(Cli.run(args, System.out, System.err))
}

/** 进程级常量与用法文本。 */
object Cli {

    /** 会话 RNG 种子（CONTRACT-DBG1 路2 钉死；与 GameBootSmoke.RAND_SEED 同值）。 */
    const val RAND_SEED = 20260226L

    /**
     * call/trace 的步数闸缺省 = 产品 GameSession.DEFAULT_MAX_STEPS_PER_RUN
     * （20,000,000，GameSession.kt:718 / :119-120）。红 J（NEWGAME-STEP-SPIN）：重载模组
     * INIT_STARTINGLV→SHOW_ABLUP_SELECT 合法步耗可达 5-10M，旧值 5,000,000 会误杀。
     */
    const val DEFAULT_CALL_STEPS = 20_000_000L

    /** sweep 的逐函数步数闸缺省（CONTRACT-DBG1 路2 sweep 规格）。 */
    const val DEFAULT_SWEEP_STEPS = 2_000_000L

    /** sweep 的逐函数墙钟缺省（毫秒）： ImmediateInputSink 挂起检测的兜底（见 WallBudget）。 */
    const val DEFAULT_WALL_MS = 10_000L

    /** trace 环形缓冲缺省条数（CONTRACT-DBG1 路2 trace 规格：定长缺省 100,000 条，超限丢最旧）。 */
    const val DEFAULT_TRACE_CAPACITY = 100_000

    /** call/trace 输出的控制台尾行数（CONTRACT-DBG1 路2 call 规格：控制台尾 10 行）。 */
    const val CONSOLE_TAIL_LINES = 10

    const val USAGE = """vmdbg —— eratw VM 调试器（CONTRACT-DBG1 路2；无交互、面向 agent）

用法: vmdbg [--erbc <erbc.v1 目录>] [--game <游戏根>] [--work <目录>] <子命令> [参数] [--steps N]

全局选项:
  --erbc <目录>     erbc.v1 产物目录（含 program.erbc / program.meta）；缺省读 ERATW_ERBC_DIR
  --game <目录>     游戏根（ERB 的上一级，含 CSV / emuera.config）；缺省读 ERATW_GAMEDIR
  --work <目录>     状态目录（call 落 last-error.json，where 读它）；缺省 <系统临时目录>/eratw-vmdbg
  --steps N         单次 run 步数闸（VmConfig.maxStepsPerRun）；call/trace 缺省 20000000，sweep 缺省 2000000

子命令:
  call <函数名> [--args 0,1,...] [--steps N]
      调用任意函数。能解析成整数的实参进 intArgs、其余进 strArgs（桶内保序）。
      输出 status/target/location/message/steps/elapsedMs/callChain + 控制台尾 10 行。
      status: PASS / NOT_FOUND / CODEEE / STEP_LIMIT / STACK_OVERFLOW / WALL_LIMIT / ERROR
  trace <函数名> [--steps N] [--capacity N] [--out <文件>]
      依赖路1 语句探针（ProcessRuntime.statementProbe）。逐条输出「行号 语句摘要 耗时ns」，
      环形缓冲定长（缺省 100000）超限丢最旧；--out 落盘代替 stdout。
  where
      最近一次错误的现场（funcName/line/pc/callChain/message）；数据来自 call/trace 写下的
      last-error.json。没有记录时输出 status=NO_ERROR 并以 0 退出。
  var <名字>[i,j]
      读单个变量（缺省下标 [0]；先按整数读，字符串变量自动换 readStr）。
  vars <glob>
      枚举变量名（系统注册表 registry.all + 用户变量 allUserVars，glob 支持 星号 与 问号）。
  disasm <pc> [-n 条数]
      从 pc 起反汇编 n 条（缺省 16）：每条输出 pc/line/words + Disassembler 文本。
  sweep [--out <目录>] [--only <regex>] [--skip-methods] [--shard i/n] [--resume] [--wall-ms N]
      全量函数清扫（跳过事件函数与编译器合成入口）。产 sweep.jsonl（每函数一条终态记录，
      字段 fn/key/status/cls/line/pc/msg/steps/elapsedMs + covered/stmts/coverPct =
      语句覆盖率%（分母 = 函数区间语句 marker 数，分子 = distinct 执行 marker pc，
      -1 = 不适用；「执行过」≠「成功」）；执行前先写 RUNNING 占位并 flush，
      完成后原地覆写 —— 挂死时最后一行 RUNNING 即肇事者。
      summary.md = 状态计数 + 失败指纹 TOP 表 + 语句覆盖率仪表。
  ledger diff --base <sweep.jsonl> --head <sweep.jsonl>
      失败指纹台账 diff：新增 / 修复 / 回归 三类 + 计数表（见 CmdLedger 的口径说明）。
  set <名字>[i,j,...] = <整数 或 "字符串">
      写单变量（写前读旧值、写后立即回读 old→new）。值：首尾成对双引号 = 字符串，
      其余先试整数、失败按原样字符串。每次成功注入追加一行 JSONL 到 <work>/audit.jsonl
      （ts/op/name/idx/old/new）。未定义名 / 维度不符 / 类型不符 → status=CODEEE + 退出码 2。
  cheat money <N> | cheat day <N> | cheat raw <名字>[i]=<值>
      零脑配配方：money/day = MONEY[0] / DAY[0] 设为 N（SystemLayout.kt:15-16）；
      raw 是 set 的别名。eraTW 专用配方待按语料核实后追加（不猜变量名）。
  snapshot [--out <文件>]
      调试快照（DBG4 P2）：变量面走引擎 saveData 全链（落快照专用槽 99、只进 --work 沙箱，
      游戏根零接触），主文件 = 游戏自身 save 格式；sidecar（<主文件>.json）记 RNG 状态 /
      console 尾 200 行 / funcStack-depth 反射快照（只记录）/ commit / randSeed / 墙钟。
      缺省 --out <work>/snapshot.dbg。
  restore <快照>
      新装配会话（同 randSeed）后还原：变量面走引擎 LOADDATA 全链（读侧 = 沙箱的
      FileSystemOps），RNG 按 sidecar setState。调用栈不还原 = 正确语义（原版 LOADDATA
      整栈丢弃）。快照路径缺省 <work>/snapshot.dbg。
  log [--tags event,rng[,var]] [--watch N1,N2] [--max N] [--out <文件>] -- call <FN> [--args ...]
      结构化事件流（DBG4 P3）：-- 之后原样转发给 call（只包装 call）。每行
      {"seq","tag","event","payload"} 落 --out（缺省 <work>/dbg-log.jsonl）。三个挂点
      （函数进出 / RNG 出号 / watch 名单命中写入）全部 core 侧可空观察口，缺省零行为。
      --max 缺省 1000000 条截断；--tags var 必须带 --watch 名单。
  help
"""

    /** CLI 主入口；[out]/[err] 注入以便单测捕获。 */
    fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val parsed = Args.parse(args.toList())
        val cmd = parsed.positional.firstOrNull()
        if (cmd == null || cmd == "help" || cmd == "--help" || parsed.flag("help")) {
            out.println(USAGE)
            return if (cmd == null && !parsed.flag("help") && args.isNotEmpty()) 1 else 0
        }
        return try {
            when (cmd) {
                "call" -> CmdCall.run(parsed, out, err)
                "trace" -> CmdTrace.run(parsed, out, err)
                "where" -> CmdWhere.run(parsed, out, err)
                "var" -> CmdVar.run(parsed, out, err)
                "vars" -> CmdVars.run(parsed, out, err)
                "disasm" -> CmdDisasm.run(parsed, out, err)
                "sweep" -> CmdSweep.run(parsed, out, err)
                "ledger" -> CmdLedger.run(parsed, out, err)
                "set" -> CmdSet.run(parsed, out, err)
                "cheat" -> CmdSet.runCheat(parsed, out, err)
                "snapshot" -> CmdSnap.run(parsed, out, err)
                "restore" -> CmdSnap.runRestore(parsed, out, err)
                "log" -> CmdLog.run(args, out, err)
                "explore" -> run {
                    CmdExplore.policyFactory = CmdExplore.PolicyFactory { name, seed -> createExplorePolicy(name, seed) }
                    CmdExplore.run(parsed, out, err)
                }
                else -> {
                    err.println("无法识别的子命令: $cmd")
                    err.println(USAGE)
                    1
                }
            }
        } catch (e: CliError) {
            err.println("vmdbg: ${e.message}")
            1
        }
    }
}

/** 用法/装配错误（统一折成退出码 1，不暴露堆栈给 agent）。 */
class CliError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 极简选项解析：`--key value`、`--flag`、位置参数混收；选项可在任意位置。
 * `-n 4`（disasm 的条数）按 `-key value` 同形解析。
 */
class Args private constructor(
    val positional: List<String>,
    private val values: Map<String, String>,
    private val flags: Set<String>,
) {

    companion object {
        /** 带值的选项名（其余 `--xxx` 记为布尔旗标）。 */
        private val VALUE_OPTS = setOf(
            "--erbc", "--game", "--work", "--steps", "--args", "--out", "--only",
            "--shard", "--wall-ms", "--capacity", "-n", "--base", "--head", "--from",
            "--tags", "--watch", "--max",
        ) + CmdExplore.VALUE_OPTS_NEEDED

        fun parse(tokens: List<String>): Args {
            val positional = ArrayList<String>()
            val values = HashMap<String, String>()
            val flags = HashSet<String>()
            var i = 0
            while (i < tokens.size) {
                val t = tokens[i]
                if (t in VALUE_OPTS) {
                    val v = tokens.getOrNull(i + 1)
                        ?: throw CliError("选项 $t 缺少参数")
                    values[t] = v
                    i += 2
                } else if (t.startsWith("--")) {
                    flags.add(t)
                    i += 1
                } else {
                    positional.add(t)
                    i += 1
                }
            }
            return Args(positional, values, flags)
        }
    }

    /** 布尔旗标（`--skip-methods` / `--resume` / `--help`）。 */
    fun flag(name: String): Boolean = name in flags

    /** 选项值；未给 = null。 */
    fun value(name: String): String? = values[name]

    /** 选项值；未给 = 缺省。 */
    fun valueOr(name: String, def: String): String = values[name] ?: def

    /** 长整选项；给了但解析不动 = 用法错误。 */
    fun longOr(name: String, def: Long): Long {
        val raw = values[name] ?: return def
        return raw.toLongOrNull() ?: throw CliError("选项 $name 不是整数: $raw")
    }
}
