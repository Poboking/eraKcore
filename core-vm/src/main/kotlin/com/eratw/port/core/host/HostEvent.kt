package com.eratw.port.core.host

/**
 * 结构化宿主事件（R3 第一波）：一行一条、可落盘、可跨进程回读的诊断事件。
 *
 * ## 为什么不是又一套日志
 *
 * 真机（vivo / OriginOS）会把应用 logcat 整条丢掉，现有的 `trail.txt`（纯文本）与
 * DevTools 的 `log --tags`（debug 手动开）各写一套形状，桌面工具与真机无法共用同一条
 * 读取路径。本格式把「运行中发生了什么」压成**有 schema 的单行 JSON**：版本号钉死解码器
 * 行为，字段顺序钉死字节形态，离线分析器与 DevTools 读的是同一行。
 *
 * ## 字段
 *
 * ```
 * {"v":1,"seq":N,"t":ms,"type":"<type>","payload":{"k":"v",...}}
 * ```
 *
 * - `seq`：在所属 [HostEventRing] 内单调递增、从 1 起；
 * - `t`：调用方注入的**单调**毫秒（app 用 `System.nanoTime` 折算；不读墙钟，避免时区/校时回拨）；
 * - `type`：事件类型（点分小写，如 `turn.end`），非空；
 * - `payload`：扁平 string→string；值已是字符串化后的读数，本层不做类型系统。
 *
 * ## 兼容口径
 *
 * 解码器只认 [HostEventCodec.SCHEMA_VERSION]；未知 `type` 放行（新事件类型不该让旧读取器
 * 失声），未知顶层键忽略。缺必需字段、版本不符、payload 值非字符串一律拒收并给出可读原因。
 */
data class HostEvent(
    /** Ring 内单调序号（从 1 起）。 */
    val seq: Long,
    /** 单调毫秒读数（口径由调用方定义，同一 Ring 内可比）。 */
    val atMs: Long,
    /** 事件类型（点分小写）。 */
    val type: String,
    /** 扁平载荷；键序即编码序（LinkedHashMap 保序）。 */
    val payload: Map<String, String>,
)

/** 事件行不符合 v1 schema（消息面向开发者，直接进 DevTools / 分析器输出）。 */
class HostEventFormatException(message: String) : Exception(message)

/**
 * [HostEvent] 的单行 JSON 编解码（v1）。
 *
 * 只做本仓自己的形状：不做通用 JSON（数字只认十进制整数、对象只认顶层 payload 对象）。
 * 编码键序固定为 `v,seq,t,type,payload`，同一事件在任何平台编出的字节相同。
 */
object HostEventCodec {

    /** 当前 schema 版本；解码器拒绝其它值。 */
    const val SCHEMA_VERSION = 1

    /** 类型名上限（超过视为调用方 bug；编码侧不做截断，直接拒收）。 */
    const val MAX_TYPE_LENGTH = 64

    /** 编码：[event] 的字段按固定键序拼成一行（不含换行）。 */
    fun encode(event: HostEvent): String {
        require(event.type.isNotBlank()) { "事件类型不能为空" }
        require(event.type.length <= MAX_TYPE_LENGTH) { "事件类型过长：${event.type.length}" }
        val out = StringBuilder(96)
        out.append("{\"v\":").append(SCHEMA_VERSION)
        out.append(",\"seq\":").append(event.seq)
        out.append(",\"t\":").append(event.atMs)
        out.append(",\"type\":\"").append(escape(event.type)).append('"')
        out.append(",\"payload\":{")
        var first = true
        for ((k, v) in event.payload) {
            if (!first) out.append(',')
            first = false
            out.append('"').append(escape(k)).append("\":\"").append(escape(v)).append('"')
        }
        out.append("}}")
        return out.toString()
    }

    /**
     * 解码一行；不符合 v1 schema 抛 [HostEventFormatException]。
     *
     * 未知顶层键与未知 `type` 都放行（前向兼容）；`seq`/`t` 必须是十进制整数。
     */
    fun decode(line: String): HostEvent {
        val s = line.trim()
        if (s.length < 2 || s.first() != '{' || s.last() != '}') {
            throw HostEventFormatException("不是 JSON 对象: $line")
        }
        val p = Parser(s, 1, s.length - 1)
        var version: Int? = null
        var seq: Long? = null
        var atMs: Long? = null
        var type: String? = null
        var payload: Map<String, String> = emptyMap()
        while (true) {
            p.skipWs()
            if (p.atEnd()) break
            val key = p.readString("顶层键")
            p.expect(':')
            p.skipWs()
            when (key) {
                "v" -> version = p.readInt("v")
                "seq" -> seq = p.readLong("seq")
                "t" -> atMs = p.readLong("t")
                "type" -> type = p.readString("type")
                "payload" -> payload = p.readPayload()
                else -> p.skipValue()
            }
            p.skipWs()
            if (p.atEnd()) break
            p.expect(',')
        }
        val ver = version ?: throw HostEventFormatException("缺 schemaVersion(v) 字段")
        if (ver != SCHEMA_VERSION) {
            throw HostEventFormatException("不支持的 schemaVersion=$ver（当前 $SCHEMA_VERSION）")
        }
        val q = seq ?: throw HostEventFormatException("缺 seq 字段")
        val t = atMs ?: throw HostEventFormatException("缺 t 字段")
        val ty = type
        if (ty.isNullOrBlank()) throw HostEventFormatException("缺 type 字段或为空")
        if (ty.length > MAX_TYPE_LENGTH) throw HostEventFormatException("type 过长：${ty.length}")
        return HostEvent(q, t, ty, payload)
    }

    /** JSON 字符串转义（控制字符走 `\u00XX`，非 ASCII 原样保留）。 */
    private fun escape(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') {
                    out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }

    /** 位置式扫描器：只服务本文件的自有形状，越界一律转成格式异常。 */
    private class Parser(private val s: String, private var i: Int, private val endExclusive: Int) {

        fun atEnd(): Boolean = i >= endExclusive

        fun skipWs() {
            while (i < endExclusive && s[i].isWhitespace()) i++
        }

        fun expect(c: Char) {
            if (i >= endExclusive || s[i] != c) {
                throw HostEventFormatException("期望 '$c'（位置 $i）: ${s.substring(0, i.coerceAtMost(s.length))}…")
            }
            i++
        }

        fun readString(what: String): String {
            if (i >= endExclusive || s[i] != '"') throw HostEventFormatException("$what 不是字符串（位置 $i）")
            i++
            val out = StringBuilder()
            while (i < endExclusive) {
                val ch = s[i++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= endExclusive) throw HostEventFormatException("$what 转义不完整")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (i + 4 > endExclusive) throw HostEventFormatException("$what 的 \\u 转义不完整")
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw HostEventFormatException("$what 的 \\u 转义非法: $hex")
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> throw HostEventFormatException("$what 的转义字符非法: \\$e")
                        }
                    }
                    else -> out.append(ch)
                }
            }
            throw HostEventFormatException("$what 字符串未闭合")
        }

        fun readInt(what: String): Int {
            val v = readLong(what)
            if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw HostEventFormatException("$what 超出 Int 范围: $v")
            return v.toInt()
        }

        fun readLong(what: String): Long {
            val start = i
            if (i < endExclusive && (s[i] == '-' || s[i] == '+')) i++
            while (i < endExclusive && s[i].isDigit()) i++
            if (i == start) throw HostEventFormatException("$what 不是整数（位置 $start）")
            return s.substring(start, i).toLongOrNull()
                ?: throw HostEventFormatException("$what 不是合法整数: ${s.substring(start, i)}")
        }

        /** payload：键值都必须是字符串；空对象合法。 */
        fun readPayload(): Map<String, String> {
            expect('{')
            val map = LinkedHashMap<String, String>()
            skipWs()
            if (i < endExclusive && s[i] == '}') {
                i++
                return map
            }
            while (true) {
                skipWs()
                val k = readString("payload 键")
                skipWs()
                expect(':')
                skipWs()
                val v = readString("payload 值（必须是字符串）")
                map[k] = v
                skipWs()
                if (i < endExclusive && s[i] == ',') {
                    i++
                    continue
                }
                expect('}')
                return map
            }
        }

        /** 未知顶层键的值：按 JSON 结构跳过（字符串感知），不解释内容。 */
        fun skipValue() {
            if (i >= endExclusive) throw HostEventFormatException("未知键缺少值")
            when (s[i]) {
                '"' -> readString("未知键的值")
                '{', '[' -> skipNested()
                else -> {
                    val start = i
                    while (i < endExclusive && s[i] != ',' && s[i] != '}') i++
                    if (i == start) throw HostEventFormatException("未知键的值无法跳过（位置 $start）")
                }
            }
        }

        private fun skipNested() {
            var depth = 0
            var inString = false
            var escaped = false
            while (i < endExclusive) {
                val ch = s[i++]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == '"' -> inString = false
                    }
                    continue
                }
                when (ch) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) return
                    }
                }
            }
            throw HostEventFormatException("未知键的嵌套值未闭合")
        }
    }
}

/**
 * 进程内事件环（R3）：有界、线程安全、序号单调。
 *
 * 只存 [HostEvent] 引用（一条 ≤ 数百字节），容量由 app 侧按内存预算选；超容丢最旧并累计
 * [dropped]，供 DevTools 判断「更早的因果链已被覆盖」。快照返回副本，调用方可随意持有。
 */
class HostEventRing(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "容量必须为正: $capacity" }
    }

    private val lock = Any()
    private val ring = ArrayDeque<HostEvent>(capacity)
    private var nextSeq = 0L
    private var droppedCount = 0L

    /** 发一条事件并返回它（[atMs] 由调用方给单调毫秒）。 */
    fun emit(atMs: Long, type: String, payload: Map<String, String> = emptyMap()): HostEvent {
        val event: HostEvent
        synchronized(lock) {
            nextSeq += 1
            event = HostEvent(
                seq = nextSeq,
                atMs = atMs,
                type = type,
                payload = if (payload.isEmpty()) emptyMap() else LinkedHashMap(payload),
            )
            if (ring.size >= capacity) {
                ring.removeFirst()
                droppedCount += 1
            }
            ring.addLast(event)
        }
        return event
    }

    /** 环内快照（旧→新；副本，不消费）。 */
    fun snapshot(): List<HostEvent> = synchronized(lock) { ArrayList(ring) }

    /** 尾 [n] 条（旧→新；[n] ≤ 0 返回空表）。 */
    fun tail(n: Int): List<HostEvent> {
        if (n <= 0) return emptyList()
        return synchronized(lock) {
            val from = (ring.size - n).coerceAtLeast(0)
            ring.drop(from)
        }
    }

    /** 已发出的事件总数（含被环形覆盖的）。 */
    val emitted: Long get() = synchronized(lock) { nextSeq }

    /** 因超容被覆盖掉的条数。 */
    val dropped: Long get() = synchronized(lock) { droppedCount }

    /** 清空（只用于诊断通道的显式复位；序号继续单调，不复位）。 */
    fun clear() {
        synchronized(lock) {
            droppedCount += ring.size
            ring.clear()
        }
    }

    companion object {
        /** 默认容量（512 条 × 一条 ~200 B ≈ 100 KiB 量级，真机常驻可忽略）。 */
        const val DEFAULT_CAPACITY = 512
    }
}
