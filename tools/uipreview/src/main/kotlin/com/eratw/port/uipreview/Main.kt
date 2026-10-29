package com.eratw.port.uipreview

import java.io.IOException
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * uipreview CLI 入口（CONTRACT-UP1 §4.8）：子命令式、无交互、面向 agent。
 * 风格照 `tools/vmdbg/Main.kt`（选项可在任意位置、`key=value` 机器可断言行、退出码同档）。
 *
 * ## 子命令
 *
 * | 命令 | 语义 |
 * |---|---|
 * | `serve` | 装配会话 + 起泵 + 起 HTTP 服务（只绑 127.0.0.1），阻塞到会话终局 |
 * | `state [--port N]` | GET /state，原样打印 JSON |
 * | `tap <x> <y> [--port N]` | POST /tap（画布像素坐标） |
 * | `type <text...> [--port N]` | POST /submit（位置参数按空格拼回一条提交文本） |
 * | `shot [--out FILE] [--port N]` | GET /frame.png 落盘（缺省 `uipreview-shot.png`），打印文件与帧序号 |
 *
 * ## 退出码（照 vmdbg 的档位语义）
 *
 * - `0` = 成功（serve = 剧本 QUIT 收尾；控制命令 = 服务端 2xx）；
 * - `1` = 用法错误或装配失败（不带堆栈）；
 * - `2` = 目标不存在（HTTP 控制命令连不上 / 服务端 ≥400）；
 * - `3` = 运行期脚本错误（serve 的 BLOCKED / FAILED / 墙钟超限 / VM 异常终局）。
 *
 * serve 的终局行为：会话停转后等 [SERVE_GRACE_MS] 让泵把最后一帧发出去，然后停服退出；
 * `--hold` 改为保持服务（人工看失败帧用，Ctrl+C 退出）。
 */
fun main(args: Array<String>) {
    exitProcess(Cli.run(args, System.out, System.err))
}

/** 进程级常量与用法文本。 */
object Cli {

    /** 会话 RNG 种子（与 vmdbg `Cli.RAND_SEED` / GameBootSmoke 装置常量同值）。 */
    const val RAND_SEED = 20260226L

    /**
     * 单次 run 步数闸缺省，与产品 GameSession.DEFAULT_MAX_STEPS_PER_RUN 同口径
     * （20,000,000，GameSession.kt:718 / :119-120）。旧值 5,000,000 会误伤重载模组的
     * INIT_STARTINGLV→SHOW_ABLUP_SELECT 合法步耗（可达 5-10M），预览装置因此误报
     * STEP_LIMIT 终局失败（退出码 3），把产品本可跑通的流程当成缺陷。
     */
    const val DEFAULT_STEPS = 20_000_000L

    /** `--columns` 缺省（半角格；语义对应 `CanvasConsoleView.fontColumnsOverride` 的手机可读档）。 */
    const val DEFAULT_COLUMNS = 110

    /** `--screen` 缺省（参考真机 V2199A 的 2400x1080 横屏；app 锁 `sensorLandscape`，AndroidManifest.xml:22）。 */
    const val DEFAULT_SCREEN = "2400x1080"

    /** `--port` 缺省（控制命令与 serve 的对接端口）。 */
    const val DEFAULT_PORT = 8765

    /** serve 终局后给泵发完最后一帧的宽限（毫秒）。 */
    const val SERVE_GRACE_MS = 1_000L

    /** 泵周期（毫秒）：revision 不变零成本，30ms 让点按到出帧的延迟压在一帧sleep内。 */
    const val PUMP_INTERVAL_MS = 30L

    const val USAGE = """uipreview —— eratw 桌面服务基座 + 浏览器贴帧（CONTRACT-UP1；无交互、面向 agent）

用法:
  uipreview [--erbc <erbc.v1 目录>] [--game <游戏根>] serve [--port N] [--columns N] [--screen WxH]
            [--device <id>] [--steps N] [--wall-ms N] [--hold]
  uipreview [--port N] state
  uipreview [--port N] tap <x> <y>
  uipreview [--port N] type <text...>
  uipreview [--port N] shot [--out <文件>]

serve 选项:
  --erbc <目录>     erbc.v1 产物目录（含 program.erbc / program.meta）；缺省读 ERATW_ERBC_DIR
  --game <目录>     游戏根（ERB 的上一级）；缺省读 ERATW_GAMEDIR
  --port N          HTTP 端口（只绑 127.0.0.1）；缺省 8765
  --device <id>     设备档案（profiles/devices.yaml 的 id；面板几何取横屏长边×短边，densityDpi 只进 18dp 兜底）
  --columns N       控制台列数（半角格，决定字号反推与折行宽）；缺省 110，设备档案不携带列数
  --screen WxH      画布内部分辨率；给了则覆盖设备档案的宽高；缺省 2400x1080（横屏）
  --steps N         单次 run 步数闸；缺省 20000000（与产品 GameSession 同口径）
  --wall-ms N       单次推进的墙钟预算（AWAIT 风暴兜底）；缺省 120000，0 = 关
  --hold            会话终局后保持服务（人工看失败帧）；缺省等 1 秒后停服退出

控制命令（打 HTTP 端点）:
  state             GET /state，原样打印 JSON
  tap <x> <y>       POST /tap，画布像素坐标（按钮中心可从 /state 的 buttons[].x/y 取）
  type <text...>    POST /submit，位置参数按空格拼回一条提交文本
  shot [--out F]    GET /frame.png 落盘（缺省 uipreview-shot.png）

退出码: 0 成功（serve = QUIT 收尾）· 1 用法/装配错误 · 2 连不上或服务端拒绝 · 3 会话终局失败
"""

    /** CLI 主入口；[out]/[err] 注入以便单测捕获。 */
    fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val parsed = try {
            Args.parse(args.toList())
        } catch (e: CliError) {
            err.println("uipreview: ${e.message}")
            err.println(USAGE)
            return 1
        }
        val cmd = parsed.positional.firstOrNull()
        if (cmd == null || cmd == "help" || parsed.flag("--help")) {
            out.println(USAGE)
            return if (cmd == null && args.isNotEmpty()) 1 else 0
        }
        return try {
            when (cmd) {
                "serve" -> CmdServe.run(parsed, out, err)
                "state" -> CmdState.run(parsed, out, err)
                "tap" -> CmdTap.run(parsed, out, err)
                "type" -> CmdType.run(parsed, out, err)
                "shot" -> CmdShot.run(parsed, out, err)
                else -> {
                    err.println("无法识别的子命令: $cmd")
                    err.println(USAGE)
                    1
                }
            }
        } catch (e: CliError) {
            err.println("uipreview: ${e.message}")
            1
        }
    }
}

/** 用法/装配错误（统一折成退出码 1，不暴露堆栈给 agent）。 */
class CliError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 极简选项解析：`--key value`、位置参数混收；选项可在任意位置。
 * 本工具没有布尔旗标子命令参数，`--hold` 是唯一的旗标。
 */
class Args private constructor(
    val positional: List<String>,
    private val values: Map<String, String>,
    private val flags: Set<String>,
) {

    companion object {
        /** 带值的选项名（其余 `--xxx` 记为布尔旗标）。 */
        private val VALUE_OPTS = setOf(
            "--erbc", "--game", "--work", "--port", "--columns", "--screen", "--device", "--steps",
            "--wall-ms", "--out",
        )

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

    /** 子命令名（第一个位置参数）。 */
    fun command(): String? = positional.firstOrNull()

    /** 除子命令外的位置参数。 */
    fun rest(): List<String> = positional.drop(1)

    fun flag(name: String): Boolean = name in flags

    fun value(name: String): String? = values[name]

    fun valueOr(name: String, def: String): String = values[name] ?: def

    fun intOr(name: String, def: Int): Int {
        val raw = values[name] ?: return def
        return raw.toIntOrNull() ?: throw CliError("选项 $name 不是整数: $raw")
    }

    fun longOr(name: String, def: Long): Long {
        val raw = values[name] ?: return def
        return raw.toLongOrNull() ?: throw CliError("选项 $name 不是整数: $raw")
    }
}

// ----------------------------------------------------------------------
// serve
// ----------------------------------------------------------------------

/** `serve` 子命令：装配 → 泵线程 → HTTP → VM 线程阻塞到终局。 */
object CmdServe {

    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        // 用法级几何先解析（--screen 非法 / --device 未知都折退出码 1，不进重装配）。
        val screenOverride = a.value("--screen")?.let { parseScreen(it) }
        val device = a.value("--device")?.let { id -> DeviceProfiles.loadDefault().require(id) }
        val geometry = ServeGeometry.resolve(
            device = device,
            screenOverride = screenOverride,
            columns = a.intOr("--columns", Cli.DEFAULT_COLUMNS),
            defaultScreen = parseScreen(Cli.DEFAULT_SCREEN),
        )
        val assembled = Assembler.assemble(a)
        val renderer = PreviewRenderer(
            session = assembled.session,
            columns = geometry.columns,
            screenW = geometry.screenW,
            screenH = geometry.screenH,
            // 档案密度贯通进 ScreenLayout 的 18dp 兜底（组长接线；裸 --screen 无档案 = 160）。
            densityDpi = geometry.densityDpi ?: com.eratw.port.render.view.ScreenLayout.DENSITY_BASE_DPI,
            wallMs = a.longOr("--wall-ms", PreviewRenderer.DEFAULT_WALL_MS),
        )
        val server = PreviewServer(renderer, a.intOr("--port", Cli.DEFAULT_PORT))
        server.start()
        renderer.startPump(Cli.PUMP_INTERVAL_MS)
        out.println(
            "uipreview serve: http://127.0.0.1:${server.port}/ " +
                "(${geometry.describe()}, canvas ${renderer.composer.canvasWidthPx}x${renderer.composer.canvasHeightPx}, " +
                "font ${renderer.fontPixelSize}px line ${renderer.metrics.lineHeight}px ${renderer.heightLines} rows)",
        )
        // 真机口径读数（ScreenLayout 与 CanvasConsoleView 同公式，CONTRACT-UP3 §4.5）。
        // 组长接线后预览与 device-layout 走同一份 fixedColumns ⇒ 字号恒同值；
        // 若出现差值属回归，按闸门报红处理。
        geometry.deviceLayout()?.let { layout ->
            val diff = if (layout.fontPixelSize == renderer.fontPixelSize) {
                ""
            } else {
                "（!! 与预览 font ${renderer.fontPixelSize}px 不同——同公式出现分叉，报红）"
            }
            out.println(
                "device-layout: font ${layout.fontPixelSize}px line ${layout.lineHeight}px " +
                    "columns ${layout.columns} canvas ${layout.canvasWidthPx}x${geometry.screenH} " +
                    "${layout.heightLinesFor(geometry.screenH)} rows$diff",
            )
        }
        out.println("按 Ctrl+C 退出。")
        var terminal: Terminal? = null
        val vm = Thread({ terminal = runSessionLoop(assembled.session, renderer) }, "uipreview-vm")
        vm.start()
        vm.join()
        // 终局宽限：让泵把最后一帧（失败屏/收尾帧）发出去再收摊。
        Thread.sleep(Cli.SERVE_GRACE_MS)
        val kind = terminal?.kind ?: TerminalKind.QUIT
        if (a.flag("--hold")) {
            out.println("会话终局（${kind.name}）：${renderer.failure ?: "QUIT"}")
            out.println("--hold 生效：服务保持运行，Ctrl+C 退出。")
            CountDownLatch(1).await()
            return 0
        }
        server.stop(0)
        renderer.close()
        return when (kind) {
            TerminalKind.QUIT -> 0
            else -> 3
        }
    }

    /** `WxH` → (w, h)；非法折成用法错误。 */
    internal fun parseScreen(raw: String): Pair<Int, Int> {
        val parts = raw.lowercase().split('x')
        val w = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val h = parts.getOrNull(1)?.trim()?.toIntOrNull()
        if (w == null || h == null || w < 1 || h < 1 || parts.size != 2) {
            throw CliError("--screen 要 WxH 形式（如 2400x1080），实到: $raw")
        }
        return w to h
    }
}

// ----------------------------------------------------------------------
// 控制命令（打 HTTP 端点）
// ----------------------------------------------------------------------

/** 「目标不存在」档的异常（连接失败）：控制命令捕到按退出码 2 收，不与用法错误的 1 混。 */
internal class HttpTargetError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** HTTP 控制命令的公共骨架：连不上 → [HttpTargetError]（退出码 2）；HTTP ≥400 由调用方按需处置。 */
internal object CmdHttp {

    /** GET [path]，返回 (status, body)。 */
    fun get(port: Int, path: String): Pair<Int, ByteArray> {
        val url = URL("http://127.0.0.1:$port$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        return try {
            val status = conn.responseCode
            val body = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            status to body
        } catch (e: IOException) {
            throw HttpTargetError("连不上 http://127.0.0.1:$port（服务没起？）：${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    /** POST [path]，body 为 JSON 文本；返回 (status, body)。 */
    fun post(port: Int, path: String, json: String): Pair<Int, ByteArray> {
        val url = URL("http://127.0.0.1:$port$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        return try {
            conn.setFixedLengthStreamingMode(json.toByteArray(Charsets.UTF_8).size)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val body = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            status to body
        } catch (e: IOException) {
            throw HttpTargetError("连不上 http://127.0.0.1:$port（服务没起？）：${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }
}

/** 控制命令的公共收口：[CmdHttp] 抛的 [HttpTargetError] 统一按退出码 2 落 stderr。 */
internal inline fun httpCmd(err: PrintStream, block: () -> Int): Int =
    try {
        block()
    } catch (e: HttpTargetError) {
        err.println(e.message)
        2
    }

/** `state`：GET /state，原样打印 JSON。 */
object CmdState {
    fun run(a: Args, out: PrintStream, err: PrintStream): Int = httpCmd(err) {
        val port = a.intOr("--port", Cli.DEFAULT_PORT)
        val (status, body) = CmdHttp.get(port, "/state")
        if (status != 200) {
            err.println("state 失败: HTTP $status ${body.toString(Charsets.UTF_8)}")
            return@httpCmd 2
        }
        out.println(body.toString(Charsets.UTF_8))
        0
    }
}

/** `tap <x> <y>`：POST /tap（画布像素坐标）。 */
object CmdTap {
    fun run(a: Args, out: PrintStream, err: PrintStream): Int = httpCmd(err) {
        val rest = a.rest()
        val x = rest.getOrNull(0)?.toIntOrNull()
        val y = rest.getOrNull(1)?.toIntOrNull()
        if (x == null || y == null) {
            err.println("用法: uipreview tap <x> <y>（画布像素坐标）")
            return@httpCmd 1
        }
        val port = a.intOr("--port", Cli.DEFAULT_PORT)
        val (status, body) = CmdHttp.post(port, "/tap", """{"x":$x,"y":$y}""")
        if (status != 200) {
            err.println("tap 失败: HTTP $status ${body.toString(Charsets.UTF_8)}")
            return@httpCmd 2
        }
        out.println(body.toString(Charsets.UTF_8))
        0
    }
}

/** `type <text...>`：POST /submit，位置参数按空格拼回一条提交文本。 */
object CmdType {
    fun run(a: Args, out: PrintStream, err: PrintStream): Int = httpCmd(err) {
        val text = a.rest().joinToString(" ")
        if (text.isEmpty()) {
            err.println("用法: uipreview type <text...>")
            return@httpCmd 1
        }
        val port = a.intOr("--port", Cli.DEFAULT_PORT)
        val (status, body) = CmdHttp.post(port, "/submit", """{"text":${JsonString.of(text)}}""")
        if (status != 200) {
            err.println("type 失败: HTTP $status ${body.toString(Charsets.UTF_8)}")
            return@httpCmd 2
        }
        out.println(body.toString(Charsets.UTF_8))
        0
    }
}

/** `shot [--out FILE]`：GET /frame.png 落盘，打印文件与帧序号。 */
object CmdShot {
    fun run(a: Args, out: PrintStream, err: PrintStream): Int = httpCmd(err) {
        val port = a.intOr("--port", Cli.DEFAULT_PORT)
        val (status, body) = CmdHttp.get(port, "/frame.png")
        if (status != 200) {
            err.println("shot 失败: HTTP $status ${body.toString(Charsets.UTF_8)}")
            return@httpCmd 2
        }
        val target = Path.of(a.valueOr("--out", "uipreview-shot.png"))
        try {
            Files.write(target, body)
        } catch (e: IOException) {
            throw CliError("写不进 ${target.toAbsolutePath()}（$e）", e)
        }
        out.println("shot: ${target.toAbsolutePath()} bytes=${body.size}")
        0
    }
}
