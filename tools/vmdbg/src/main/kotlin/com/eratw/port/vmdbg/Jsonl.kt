package com.eratw.port.vmdbg

/**
 * sweep.jsonl / last-error.json 的极简 JSON 面：只有平面对象（string/number/bool），
 * 手写转义与逐行解析（依赖面最小化 —— core-vm 没有可复用的 JSON 编解码器，引入
 * 第三方序列化库只为两条日志文件不值得）。
 */
object Jsonl {

    /** 字符串 JSON 转义（引号、反斜杠、控制字符统一成 合法的转义或 uXXXX）。 */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else ->
                    if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code))
                    else sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 一条 JSON 对象行（保序；调用方决定字段次序，sweep.jsonl 的字段序因此稳定）。 */
    fun obj(fields: List<Pair<String, Any?>>): String =
        fields.joinToString(
            ",",
            "{",
            "}",
        ) { (k, v) ->
            "\"" + escape(k) + "\":" + value(v)
        }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + escape(v) + "\""
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        else -> "\"" + escape(v.toString()) + "\""
    }

    /**
     * 逐行解析 JSON 对象为扁平 map（只认 `"key":value` 平面对象；不支持的行返回 null 并
     * 跳过）。value 只识 string / number / true / false / null —— sweep.jsonl 与
     * last-error.json 都是这个形状。字符串内的转义按 [escape] 的逆还原。
     *
     * **行首 BOM 容忍**：Windows 写侧（PowerShell 5.1 `Out-File -Encoding utf8` 等）
     * 落盘带 EF BB BF，而 Java 的 UTF-8 解码器**不剥 BOM** —— 2026-10-25 实测：ledger
     * diff 的 base 台账带 BOM 时首条记录被静默丢弃、错报一条「新增」（同款读侧还有 sweep
     * `--resume` 预清与 `where`，三处都汇到本函数）。`String.trim()` 不剥 U+FEFF（它不是
     * 空白字符），故解析前显式剥掉行首的 BOM 与空白；字符串**值内**的 U+FEFF 不在行首、
     * 原样保留。
     */
    fun parseLine(line: String): Map<String, String>? {
        val t = line.trimStart { it == '\uFEFF' || it.isWhitespace() }
            .trimEnd { it == '\uFEFF' || it.isWhitespace() }
        if (!t.startsWith("{") || !t.endsWith("}")) return null
        val body = t.substring(1, t.length - 1)
        val out = HashMap<String, String>()
        var i = 0
        val n = body.length
        while (i < n) {
            // 跳过逗号与空白
            while (i < n && (body[i] == ',' || body[i].isWhitespace())) i++
            if (i >= n) break
            if (body[i] != '"') return null
            val keyEnd = scanStringEnd(body, i) ?: return null
            val key = unescape(body.substring(i + 1, keyEnd))
            i = keyEnd + 1
            if (i >= n || body[i] != ':') return null
            i++
            while (i < n && body[i].isWhitespace()) i++
            if (i >= n) return null
            val v: String = if (body[i] == '"') {
                val end = scanStringEnd(body, i) ?: return null
                val s = unescape(body.substring(i + 1, end))
                i = end + 1
                s
            } else {
                val start = i
                while (i < n && body[i] != ',') i++
                body.substring(start, i).trim()
            }
            out[key] = v
        }
        return out
    }

    /** [from] 指向开引号；返回闭引号下标（处理转义）。 */
    private fun scanStringEnd(s: String, from: Int): Int? {
        var i = from + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
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
            when (val d = s[i + 1]) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'b' -> sb.append('\b')
                'f' -> sb.append(0x0C.toChar())
                'u' -> {
                    val hex = s.substring(i + 2, (i + 6).coerceAtMost(s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 4
                    } else {
                        sb.append(d)
                    }
                }
                else -> sb.append(d)
            }
            i += 2
        }
        return sb.toString()
    }
}
