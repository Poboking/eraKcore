package com.eratw.port.uipreview

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * uipreview 的 HTTP 面（CONTRACT-UP1 §4.6）：JDK 自带 `com.sun.net.httpserver`，
 * **只绑 127.0.0.1**（工具面板，不对外服务）。
 *
 * ## 端点
 *
 * | 方法 | 路径 | 语义 |
 * |---|---|---|
 * | GET | `/` | 静态页（classpath `web/index.html`） |
 * | GET | `/frame.png?rev=N` | 当前帧 PNG；`rev` = 帧序号（`PreviewRenderer.FrameShot.frameRev`），相同回 304，否则 200 + `X-Frame-Rev` |
 * | GET | `/state` | JSON：帧序号/sink 版本/画布几何/columns/heightLines/按钮表/当前 InputRequest/canSkip/failure |
 * | POST | `/tap` | `{"x":画布px,"y":画布px}` → 进泵线程请求队列（命中链在泵线程执行） |
 * | POST | `/submit` | `{"text":"..."}` → `QueueInputSink.submit(text, false)`（内部锁，直接从 HTTP 线程入队） |
 * | POST | `/scroll` | `{"rows":N}`（正=向下翻向新行/贴底方向，负=向上翻历史）→ 泵线程请求队列 |
 *
 * ## 线程
 *
 * handler 跑在守护线程池（2 线程足够：浏览器轮询 + CLI 控制命令）；全部读操作只碰
 * `PreviewRenderer` 的 `AtomicReference` 快照与 `QueueInputSink` 的 `@Volatile` 只读快照，
 * 写操作只走 `submit` 与请求队列 —— 线程契约见 `PreviewRenderer` 的类 KDoc。
 */
class PreviewServer(
    private val renderer: PreviewRenderer,
    port: Int,
) {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)

    /** 实际监听端口（`--port 0` 时由系统分配，测试用）。 */
    val port: Int get() = server.address.port

    init {
        server.executor = Executors.newFixedThreadPool(2, daemonFactory("uipreview-http"))
        server.createContext("/") { ex ->
            when {
                ex.requestMethod == "GET" && ex.requestURI.path == "/" -> serveIndex(ex)
                ex.requestMethod == "GET" && ex.requestURI.path == "/frame.png" -> serveFrame(ex)
                ex.requestMethod == "GET" && ex.requestURI.path == "/state" -> serveState(ex)
                ex.requestMethod == "POST" && ex.requestURI.path == "/tap" -> serveTap(ex)
                ex.requestMethod == "POST" && ex.requestURI.path == "/submit" -> serveSubmit(ex)
                ex.requestMethod == "POST" && ex.requestURI.path == "/scroll" -> serveScroll(ex)
                else -> respondJson(ex, 404, """{"error":"no such endpoint"}""")
            }
        }
    }

    fun start() {
        server.start()
    }

    /** 停服（`delaySeconds` 内排空在飞请求）。 */
    fun stop(delaySeconds: Int = 0) {
        server.stop(delaySeconds)
    }

    // ------------------------------------------------------------------
    // GET 面
    // ------------------------------------------------------------------

    private fun serveIndex(ex: HttpExchange) {
        val bytes = javaClass.classLoader.getResourceAsStream(WEB_INDEX)?.use { it.readBytes() }
        if (bytes == null) {
            respondJson(ex, 500, """{"error":"index.html missing from classpath"}""")
            return
        }
        ex.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun serveFrame(ex: HttpExchange) {
        val shot = renderer.currentFrame
        if (shot == null) {
            // 还没出过帧（会话刚起）：浏览器按 300ms 轮询重试。
            respondJson(ex, 404, """{"error":"no frame yet"}""")
            return
        }
        val requested = ex.requestURI.rawQuery?.split('&')
            ?.mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 && it[0] == "rev" }?.get(1) }
            ?.firstOrNull()?.toLongOrNull()
        if (requested != null && requested == shot.frameRev) {
            ex.sendResponseHeaders(304, -1)
            ex.close()
            return
        }
        ex.responseHeaders.set("Content-Type", "image/png")
        ex.responseHeaders.set("X-Frame-Rev", shot.frameRev.toString())
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, shot.png.size.toLong())
        ex.responseBody.use { it.write(shot.png) }
    }

    private fun serveState(ex: HttpExchange) {
        respondJson(ex, 200, stateJson())
    }

    /**
     * `/state` 的 JSON。`awaiting` 是 `QueueInputSink.awaiting` 的 `@Volatile` 只读快照
     * （跨线程观察是该 KDoc 明示的合法用法）；`buttons` 是泵线程发布的 [PreviewRenderer.UiSnapshot]。
     */
    private fun stateJson(): String {
        val r = renderer
        val ui = r.currentUi
        val sink = r.session.inputSink
        val awaiting = sink.awaiting
        val sb = StringBuilder(512)
        sb.append('{')
        sb.append("\"frameRev\":").append(r.currentFrame?.frameRev ?: -1L)
        sb.append(",\"sinkRev\":").append(ui?.sinkRev ?: r.session.console.revision)
        sb.append(",\"width\":").append(r.composer.canvasWidthPx)
        sb.append(",\"height\":").append(r.composer.canvasHeightPx)
        sb.append(",\"columns\":").append(r.columns)
        sb.append(",\"heightLines\":").append(r.heightLines)
        sb.append(",\"lineHeight\":").append(r.metrics.lineHeight)
        sb.append(",\"fontPixelSize\":").append(r.metrics.fontPixelSize)
        sb.append(",\"cellPixelWidth\":").append(r.metrics.cellPixelWidth)
        sb.append(",\"vmEnded\":").append(r.vmEnded)
        sb.append(",\"quitRequested\":").append(r.session.runtime.quitRequested)
        sb.append(",\"failure\":").append(jsonStringOrNull(r.failure))
        sb.append(",\"closed\":").append(sink.isClosed)
        sb.append(",\"pendingCount\":").append(sink.pendingCount)
        sb.append(",\"awaitingAnyKey\":").append(sink.awaitingAnyKey)
        sb.append(",\"canSkip\":").append(sink.awaitingAnyKey)
        sb.append(",\"hitRows\":").append(ui?.hitRows ?: -1)
        sb.append(",\"visibleRows\":").append(ui?.visibleRows ?: -1)
        if (awaiting == null) {
            sb.append(",\"awaiting\":null")
        } else {
            sb.append(",\"awaiting\":{")
            sb.append("\"form\":\"").append(awaiting.form.name).append('"')
            sb.append(",\"type\":").append(jsonStringOrNull(awaiting.type.name))
            sb.append(",\"oneInput\":").append(awaiting.oneInput)
            sb.append(",\"hasDef\":").append(awaiting.hasDef)
            sb.append(",\"defInt\":").append(awaiting.defInt)
            sb.append(",\"defStr\":").append(jsonString(awaiting.defStr))
            sb.append(",\"timeLimit\":").append(awaiting.timeLimit)
            sb.append(",\"timeUpMessage\":").append(jsonString(awaiting.timeUpMessage))
            sb.append('}')
        }
        sb.append(",\"buttons\":[")
        ui?.buttons?.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            sb.append("{\"hitRow\":").append(b.hitRow)
            sb.append(",\"colStart\":").append(b.colStart)
            sb.append(",\"colEnd\":").append(b.colEnd)
            sb.append(",\"x\":").append(b.xPx)
            sb.append(",\"y\":").append(b.yPx)
            sb.append(",\"label\":").append(jsonString(b.label))
            sb.append(",\"value\":").append(jsonValue(b.value))
            sb.append('}')
        }
        sb.append(']')
        // 引擎侧选项：有等待方用等待快照，无等待方（AWAIT 轮询屏）用当前世代兜底。
        // 与上面的 buttons 不同：buttons 是渲染命中层（只覆盖可见行，含 HTML 展平结果），
        // chips 是引擎按钮对象（含滚出屏幕的行、带显示行号/列号）。验收判据：
        // 每个可见 buttons[].value 都必须能在 chips 里找到同值项；顺序按 (行, 列)。
        sb.append(",\"chips\":[")
        val chips = if (sink.isAwaiting) {
            r.session.console.currentOptions()
        } else {
            r.session.console.displayOptions()
        }
        chips.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append("{\"value\":").append(jsonValue(c.value))
            sb.append(",\"label\":").append(jsonString(c.label))
            sb.append(",\"origin\":\"").append(c.origin.name).append('"')
            sb.append(",\"ordinal\":").append(c.ordinal ?: -1)
            sb.append(",\"generation\":").append(c.generation)
            sb.append(",\"lineSerial\":").append(c.lineSerial)
            sb.append(",\"startCol\":").append(c.startCol)
            sb.append(",\"endCol\":").append(c.endCol)
            sb.append('}')
        }
        sb.append(']')
        sb.append('}')
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // POST 面
    // ------------------------------------------------------------------

    private fun serveTap(ex: HttpExchange) {
        val body = readBody(ex)
        val obj = MiniJson.parse(body)
        val x = (obj["x"] as? Number)?.toInt()
        val y = (obj["y"] as? Number)?.toInt()
        if (x == null || y == null) {
            respondJson(ex, 400, """{"error":"need {\"x\":int,\"y\":int} (canvas pixels)"}""")
            return
        }
        renderer.requestTap(x, y)
        respondJson(ex, 200, """{"queued":true,"x":$x,"y":$y}""")
    }

    private fun serveSubmit(ex: HttpExchange) {
        val body = readBody(ex)
        val obj = MiniJson.parse(body)
        val text = obj["text"] as? String
        if (text == null) {
            respondJson(ex, 400, """{"error":"need {\"text\":\"...\"}"}""")
            return
        }
        val queued = renderer.session.inputSink.submit(text, false)
        respondJson(ex, 200, """{"queued":$queued,"pendingCount":${renderer.session.inputSink.pendingCount}}""")
    }

    private fun serveScroll(ex: HttpExchange) {
        val body = readBody(ex)
        val obj = MiniJson.parse(body)
        val rows = (obj["rows"] as? Number)?.toInt()
        if (rows == null || rows == 0) {
            respondJson(ex, 400, """{"error":"need {\"rows\":int} (nonzero; + = down toward bottom)"}""")
            return
        }
        renderer.requestScroll(rows)
        respondJson(ex, 200, """{"queued":true,"rows":$rows}""")
    }

    private fun readBody(ex: HttpExchange): String =
        ex.requestBody.use { it.readNBytes(MAX_BODY_BYTES).toString(Charsets.UTF_8) }

    // ------------------------------------------------------------------
    // 应答与 JSON 小工具（零第三方依赖）
    // ------------------------------------------------------------------

    private fun respondJson(ex: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** JSON 字符串值（含转义）；null 输入出 `null` 字面量。 */
    internal fun jsonStringOrNull(s: String?): String = s?.let { JsonString.of(it) } ?: "null"

    internal fun jsonString(s: String): String = JsonString.of(s)

    /** 按钮值（Long / String）→ JSON 值。 */
    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Long -> v.toString()
        is Number -> v.toString()
        else -> jsonString(v.toString())
    }

    companion object {
        private const val WEB_INDEX = "web/index.html"

        /** POST 体上限（点击/文本/滚动都是小对象；防呆，不与原版语义相关）。 */
        private const val MAX_BODY_BYTES = 64 * 1024
    }
}

/** JSON 字符串字面量转义（服务端与 CLI 控制命令共用；`\\u%04x` 控制字符兜底）。 */
internal object JsonString {
    fun of(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/** 守护线程工厂（`HttpServer` 缺省线程非守护，会卡住 JVM 退出）。 */
internal fun daemonFactory(prefix: String): ThreadFactory {
    val n = AtomicInteger(0)
    return ThreadFactory { r ->
        Thread(r, "$prefix-${n.incrementAndGet()}").apply { isDaemon = true }
    }
}

/**
 * 微 JSON 解析（零第三方依赖）：只支持顶层对象，值支持字符串（`\` 转义）、数字（long/double）、
 * 布尔与 null。数组/嵌套对象解析出 `null` 占位 —— 本工具的四个端点都不需要（CONTRACT-UP1 §4.6）。
 * 解析失败返回空 map（调用方按缺字段拒绝），不抛。
 */
object MiniJson {

    fun parse(text: String): Map<String, Any?> {
        val s = text.trim()
        if (!s.startsWith("{")) return emptyMap()
        val out = HashMap<String, Any?>()
        var i = 1
        while (i < s.length) {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
            if (i >= s.length || s[i] == '}') break
            if (s[i] != '"') return emptyMap()
            val keyStart = i + 1
            val keyEnd = findStringEnd(s, keyStart) ?: return emptyMap()
            val key = unescape(s.substring(keyStart, keyEnd))
            i = keyEnd + 1
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length || s[i] != ':') return emptyMap()
            i++
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) return emptyMap()
            when {
                s[i] == '"' -> {
                    val vStart = i + 1
                    val vEnd = findStringEnd(s, vStart) ?: return emptyMap()
                    out[key] = unescape(s.substring(vStart, vEnd))
                    i = vEnd + 1
                }
                s.startsWith("true", i) -> {
                    out[key] = true
                    i += 4
                }
                s.startsWith("false", i) -> {
                    out[key] = false
                    i += 5
                }
                s.startsWith("null", i) -> {
                    out[key] = null
                    i += 4
                }
                else -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    val raw = s.substring(start, i)
                    out[key] = raw.toLongOrNull() ?: raw.toDoubleOrNull()
                    if (out[key] == null) return emptyMap()
                }
            }
        }
        return out
    }

    /** [from] 是首个引号之后；返回收尾引号的下标，转义引号跳过。 */
    private fun findStringEnd(s: String, from: Int): Int? {
        var i = from
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return null
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                sb.append(c)
                i++
                continue
            }
            when (val e = s[i + 1]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    val hex = s.substring(i + 2, (i + 6).coerceAtMost(s.length))
                    val code = hex.toIntOrNull(16)
                    if (code == null) {
                        sb.append(e)
                    } else {
                        sb.append(code.toChar())
                        i += 4
                    }
                }
                else -> sb.append(e)
            }
            i += 2
        }
        return sb.toString()
    }
}
