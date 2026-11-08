package com.eratw.port.vmdbg

import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * 快照 sidecar 的**离线**解析 / 校验 / 展开 / 对比（R1）。
 *
 * ## 为什么单独一层
 *
 * 读侧只有 `restore`（[CmdSnap]），它必须装配会话 + 读游戏根才能还原 —— 诊断一条坏快照、
 * 比较两份快照目前只能重跑游戏。本对象只吃**文件字节**：不调 [Assembler]、不读
 * `--erbc/--game`、不建沙箱，因此纯 JVM 可单测、无语料也能用。
 *
 * ## 文档形状（唯一服务对象）
 *
 * sidecar 是 [CmdSnap] 自己产的单行 JSON（`buildSidecar`，CmdSnap.kt:174-207）：
 * `format/createdAt/snapshotText/commit/randSeed/rngState[625]/consoleTail/funcStack/funcDepth`。
 * [Jsonl.parseLine] 只认平面对象（数组会被首个逗号截断，Jsonl.kt:59-92），所以这里带一个
 * 真正的单行 JSON 解析器（字符串/数字/布尔/null/数组/对象 + 转义逆还原），不引第三方库。
 *
 * ## 容器证据
 *
 * 主文件 = 引擎 save 格式，二进制头部两个魔数与版本 1808 见 `specs/save-format.md` §1.2
 * （`EraBinaryDataWriter.cs:35-51`）；文本格式首行 = UniqueCode（§1.4，`EraDataWriter.cs:52-109`）。
 * 离线**不解压** gzip 容器，只报容器类别；版本不符是确定损坏，首行不是数字则只告警
 * （保存编码可由 `SaveEncode` 配置，离线不猜）。
 */
object SnapSidecar {

    /** sidecar 格式号（`buildSidecar` 的 `"format":1`）。 */
    const val FORMAT = 1

    /** Sfmt19937 状态长度（`Sfmt19937.RANDDATA_LEN`，Sfmt19937.kt:90-95）。 */
    const val RNG_STATE_LEN = 625

    /** 主文件二进制魔数（未压缩 / GZip，`EraBinaryDataWriter.cs:37-44`）。 */
    const val BINARY_MAGIC = 727905341820519817L
    const val BINARY_GZIP_MAGIC = 743174590507926921L

    /** 二进制版本号（`EraBinaryDataWriter.cs:45`；读侧只认这一个，`:820-824`）。 */
    const val BINARY_VERSION = 1808

    /** 必需顶层字段（缺一个即 INVALID；未知键放行 = 前向兼容）。 */
    val REQUIRED_FIELDS = listOf("format", "createdAt", "randSeed", "rngState", "consoleTail", "funcStack", "funcDepth")

    // ------------------------------------------------------------------
    // JSON 值模型 + 单行解析
    // ------------------------------------------------------------------

    /** sidecar 的 JSON 值（本对象只用到这六种）。 */
    sealed class JsonValue {
        data class Str(val value: String) : JsonValue()
        data class Num(val raw: String) : JsonValue()
        data object True : JsonValue()
        data object False : JsonValue()
        data object Null : JsonValue()
        data class Arr(val items: List<JsonValue>) : JsonValue()
        data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
    }

    /** sidecar 不可解析（CLI 折成 INVALID / 退出码 3）。 */
    class SnapFormatException(message: String) : Exception(message)

    /**
     * 解析一行 sidecar。行首 BOM / 空白容忍（与 [Jsonl.parseLine] 同一口径：Windows 写侧
     * 带 EF BB BF，Java 的 UTF-8 解码不剥 BOM）。非对象 / 语法错抛 [SnapFormatException]。
     */
    fun parse(line: String): JsonValue.Obj {
        val text = line.trimStart { it == '\uFEFF' || it.isWhitespace() }
            .trimEnd { it == '\uFEFF' || it.isWhitespace() }
        if (text.isEmpty()) throw SnapFormatException("sidecar 为空")
        val parser = Parser(text)
        val value = parser.parseDocument()
        return value as? JsonValue.Obj ?: throw SnapFormatException("sidecar 顶层不是 JSON 对象")
    }

    /** 取字符串字段；类型不符或缺席 = null。 */
    fun JsonValue.Obj.strOf(key: String): String? = (fields[key] as? JsonValue.Str)?.value

    /** 取整数字段（只认无小数点的十进制）；类型不符 / 溢出 / 缺席 = null。 */
    fun JsonValue.Obj.longOf(key: String): Long? = (fields[key] as? JsonValue.Num)?.raw?.toLongOrNull()

    /** 取字符串数组；任一项不是字符串 = null（由 [validate] 报违规）。 */
    fun JsonValue.Obj.strArrayOf(key: String): List<String>? {
        val arr = fields[key] as? JsonValue.Arr ?: return null
        val out = ArrayList<String>(arr.items.size)
        for (item in arr.items) {
            out += (item as? JsonValue.Str)?.value ?: return null
        }
        return out
    }

    /** 取整数数组；任一项不是无小数点整数 = null。 */
    fun JsonValue.Obj.longArrayOf(key: String): LongArray? {
        val arr = fields[key] as? JsonValue.Arr ?: return null
        val out = LongArray(arr.items.size)
        for ((i, item) in arr.items.withIndex()) {
            out[i] = (item as? JsonValue.Num)?.raw?.toLongOrNull() ?: return null
        }
        return out
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): JsonValue {
            skipWs()
            val v = readValue()
            skipWs()
            if (i != s.length) fail("对象结尾之后还有多余字符（偏移 $i）")
            return v
        }

        private fun readValue(): JsonValue {
            if (i >= s.length) fail("意外的结尾（偏移 $i）")
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> {
                    expectWord("true")
                    JsonValue.True
                }
                'f' -> {
                    expectWord("false")
                    JsonValue.False
                }
                'n' -> {
                    expectWord("null")
                    JsonValue.Null
                }
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("非法字符 '$c'（偏移 $i）")
            }
        }

        private fun readObject(): JsonValue.Obj {
            expect('{')
            val out = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') {
                i++
                return JsonValue.Obj(out)
            }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                out[key] = readValue()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonValue.Obj(out)
                    }
                    else -> fail("对象里期望 ',' 或 '}'（偏移 $i）")
                }
            }
        }

        private fun readArray(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWs()
            if (peek() == ']') {
                i++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWs()
                items += readValue()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonValue.Arr(items)
                    }
                    else -> fail("数组里期望 ',' 或 ']'（偏移 $i）")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        i++
                        if (i >= s.length) fail("转义符后意外结尾")
                        when (val d = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append(0x0C.toChar())
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) fail("\\u 转义不完整")
                                val hex = s.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: fail("\\u 转义不是十六进制: $hex")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("不认识的转义 \\$d")
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            fail("字符串没有闭合引号")
        }

        private fun readNumber(): JsonValue.Num {
            val start = i
            if (peek() == '-') i++
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++ else break
            }
            val raw = s.substring(start, i)
            val ok = raw.toLongOrNull() != null || raw.toDoubleOrNull() != null
            if (!ok) fail("不是合法数字: $raw")
            return JsonValue.Num(raw)
        }

        private fun expectWord(word: String) {
            if (!s.startsWith(word, i)) fail("期望 $word（偏移 $i）")
            i += word.length
        }

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) fail("期望 '$c'（偏移 $i）")
            i++
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun fail(message: String): Nothing = throw SnapFormatException(message)
    }

    // ------------------------------------------------------------------
    // 容器探测（主文件）
    // ------------------------------------------------------------------

    /** 主文件容器类别。 */
    enum class Container { BINARY, BINARY_GZIP, TEXT, UNKNOWN }

    /** 容器探测结果；[problems] 非空 = 确定损坏（目前只有「魔数命中但版本不符」）。 */
    data class ContainerProbe(
        val container: Container,
        val version: Int?,
        val problems: List<String>,
        val warnings: List<String>,
        val headHex: String,
    )

    /**
     * 离线容器探测：两个魔数 + 版本 1808（`specs/save-format.md` §1.2）；否则首行整数 = 文本
     * （§1.4）。首行既不是整数也不是魔数 ⇒ UNKNOWN + warning（保存编码可配置，不判损坏）。
     */
    fun detectContainer(bytes: ByteArray): ContainerProbe {
        val headHex = bytes.take(16).joinToString(" ") { "%02x".format(it) }
        if (bytes.size >= 16) {
            val magic = leLong(bytes, 0)
            if (magic == BINARY_MAGIC || magic == BINARY_GZIP_MAGIC) {
                val container = if (magic == BINARY_GZIP_MAGIC) Container.BINARY_GZIP else Container.BINARY
                val version = leInt(bytes, 8)
                val problems = ArrayList<String>()
                val warnings = ArrayList<String>()
                if (version != BINARY_VERSION) {
                    problems += "二进制容器版本=$version（读侧只认 $BINARY_VERSION，EraBinaryDataReader.cs:820-824）"
                }
                val checksums = leInt(bytes, 12)
                if (checksums != 0) {
                    warnings += "校验表数量=$checksums（原版恒写 0，EraBinaryDataWriter.cs:46）"
                }
                return ContainerProbe(container, version, problems, warnings, headHex)
            }
        }
        if (looksLikeTextSaves(bytes)) {
            return ContainerProbe(Container.TEXT, null, emptyList(), emptyList(), headHex)
        }
        return ContainerProbe(
            Container.UNKNOWN,
            null,
            emptyList(),
            listOf("容器既非二进制魔数也非文本首行整数（head=$headHex）；保存编码可配置，离线不判损坏"),
            headHex,
        )
    }

    /** 首行（UTF-8 或 UTF-16LE，去 BOM）能否解析成整数 = 文本存档（`EraDataWriter.cs:52-109`）。 */
    private fun looksLikeTextSaves(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val firstLine = { text: String -> text.substringBefore('\n').substringBefore('\r').trim() }
        val utf8 = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).removePrefix("\uFEFF")
        if (firstLine(utf8).toLongOrNull() != null) return true
        if (bytes.size >= 2 && bytes[0].toInt() and 0xFF != 0 && bytes[1].toInt() == 0) {
            val utf16 = String(bytes, 0, minOf(bytes.size, 8192), Charsets.UTF_16LE)
            return firstLine(utf16).toLongOrNull() != null
        }
        return false
    }

    /** 小端 64 位（越界按 0 补齐不是本函数的用法：调用方已保证长度）。 */
    fun leLong(bytes: ByteArray, at: Int): Long {
        var v = 0L
        for (k in 0 until 8) {
            v = v or ((bytes[at + k].toLong() and 0xFFL) shl (8 * k))
        }
        return v
    }

    /** 小端 32 位。 */
    fun leInt(bytes: ByteArray, at: Int): Int {
        var v = 0
        for (k in 0 until 4) {
            v = v or ((bytes[at + k].toInt() and 0xFF) shl (8 * k))
        }
        return v
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /** sidecar 校验结果；[problems] 非空即 INVALID。 */
    data class Validation(
        val problems: List<String>,
        val warnings: List<String>,
        val format: Int?,
        val createdAt: String?,
        val commit: String?,
        val randSeed: Long?,
        val funcDepth: Int?,
        val rngLen: Int,
        val consoleTailLen: Int,
        val funcStackLen: Int,
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    /** §3.3 的 sidecar 规则（主文件规则在 [detectContainer]）。 */
    fun validate(doc: JsonValue.Obj): Validation {
        val problems = ArrayList<String>()
        val warnings = ArrayList<String>()
        for (key in REQUIRED_FIELDS) {
            if (key !in doc.fields) problems += "缺少必需字段 $key"
        }

        val formatRaw = doc.fields["format"]
        val format = doc.longOf("format")
        when {
            formatRaw == null -> Unit
            formatRaw !is JsonValue.Num -> problems += "format 不是整数（${kind(formatRaw)}）"
            format == null -> problems += "format 不是整数（${(formatRaw as JsonValue.Num).raw}）"
            format != FORMAT.toLong() -> problems += "format=$format（应为 $FORMAT）"
        }

        val createdAtRaw = doc.fields["createdAt"]
        val createdAt = doc.strOf("createdAt")
        when {
            createdAtRaw == null -> Unit
            createdAtRaw !is JsonValue.Str -> problems += "createdAt 不是字符串（${kind(createdAtRaw)}）"
            createdAt != null && runCatching { OffsetDateTime.parse(createdAt) }.isFailure ->
                problems += "createdAt 不是 ISO-8601 时间: $createdAt"
        }

        val randSeedRaw = doc.fields["randSeed"]
        if (randSeedRaw != null && randSeedRaw !is JsonValue.Num) problems += "randSeed 不是整数（${kind(randSeedRaw)}）"
        val randSeed = doc.longOf("randSeed")
        if (randSeedRaw is JsonValue.Num && randSeed == null) problems += "randSeed 不是整数（${randSeedRaw.raw}）"

        val funcDepthRaw = doc.fields["funcDepth"]
        if (funcDepthRaw != null && funcDepthRaw !is JsonValue.Num) problems += "funcDepth 不是整数（${kind(funcDepthRaw)}）"
        val funcDepthLong = doc.longOf("funcDepth")
        val funcDepth = funcDepthLong?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        if (funcDepthRaw is JsonValue.Num && funcDepth == null) problems += "funcDepth 不是整数（${funcDepthRaw.raw}）"
        if (funcDepth != null && funcDepth < -1) problems += "funcDepth=$funcDepth（-1 = 反射不可得，不允许更小）"

        val rngRaw = doc.fields["rngState"]
        val rng = doc.longArrayOf("rngState")
        if (rngRaw != null) {
            if (rngRaw !is JsonValue.Arr) {
                problems += "rngState 不是数组（${kind(rngRaw)}）"
            } else if (rng == null) {
                problems += "rngState 含非整数项"
            } else if (rng.size != 0 && rng.size != RNG_STATE_LEN) {
                problems += "rngState 长度=${rng.size}（应为 0 或 $RNG_STATE_LEN）"
            }
        }

        val consoleRaw = doc.fields["consoleTail"]
        val consoleChain = doc.strArrayOf("consoleTail")
        if (consoleRaw != null && consoleChain == null) problems += "consoleTail 不是字符串数组"

        val stackRaw = doc.fields["funcStack"]
        val stack = doc.strArrayOf("funcStack")
        if (stackRaw != null && stack == null) problems += "funcStack 不是字符串数组"

        val commit = doc.strOf("commit")
        if ("commit" in doc.fields && commit == null) warnings += "commit 不是字符串（忽略）"

        return Validation(
            problems = problems,
            warnings = warnings,
            format = format?.toInt(),
            createdAt = createdAt,
            commit = commit,
            randSeed = randSeed,
            funcDepth = funcDepth,
            rngLen = rng?.size ?: 0,
            consoleTailLen = consoleChain?.size ?: 0,
            funcStackLen = stack?.size ?: 0,
        )
    }

    private fun kind(v: JsonValue): String = when (v) {
        is JsonValue.Str -> "字符串"
        is JsonValue.Num -> "数字"
        is JsonValue.Arr -> "数组"
        is JsonValue.Obj -> "对象"
        JsonValue.Null -> "null"
        JsonValue.True, JsonValue.False -> "布尔"
    }

    // ------------------------------------------------------------------
    // 展开 / 对比
    // ------------------------------------------------------------------

    /** 主文件事实（CLI 读盘后交进来；纯层不碰 IO）。 */
    data class MainInfo(val path: String, val bytes: Long, val sha256: String, val probe: ContainerProbe)

    /**
     * `expand` 的文本行（`key=value` + `---` 分隔段），§3.4。首行 status 按校验结果给：
     * 主文件容器判损或 sidecar 违规 ⇒ `INVALID`（CLI 据此返回退出码 3），报告照常全量产出。
     */
    fun expand(doc: JsonValue.Obj, sidecarPath: String, main: MainInfo): List<String> {
        val v = validate(doc)
        val invalid = v.problems.isNotEmpty() || main.probe.problems.isNotEmpty() || main.bytes == 0L
        val lines = ArrayList<String>()
        lines += "status=${if (invalid) "INVALID" else "PASS"}"
        lines += "main=${main.path}"
        lines += "mainBytes=${main.bytes}"
        lines += "mainSha256=${main.sha256}"
        lines += "container=${containerLabel(main.probe)}"
        lines += "sidecar=$sidecarPath"
        lines += "format=${v.format ?: "?"}"
        lines += "createdAt=${v.createdAt ?: ""}"
        lines += "commit=${v.commit ?: ""}"
        lines += "randSeed=${v.randSeed ?: "?"}"
        lines += "funcDepth=${v.funcDepth ?: "?"}"
        lines += "funcStack.len=${v.funcStackLen}"
        lines += "consoleTail.len=${v.consoleTailLen}"
        val rng = doc.longArrayOf("rngState") ?: LongArray(0)
        lines += "rngState.len=${rng.size}"
        if (rng.isNotEmpty()) {
            lines += "rngState.head=" + rng.take(8).joinToString(",")
            lines += "rngState.digest=${rngDigest(rng)}"
        }
        lines += "vars=not-expanded(offline; use restore)"
        lines += "--- func stack (top first) ---"
        val stack = doc.strArrayOf("funcStack").orEmpty()
        for (key in stack.asReversed()) lines += key
        lines += "--- console tail (oldest first) ---"
        lines.addAll(doc.strArrayOf("consoleTail").orEmpty())
        for (p in main.probe.problems) lines += "problem=$p"
        for (w in main.probe.warnings) lines += "warning=$w"
        for (p in v.problems) lines += "problem=$p"
        for (w in v.warnings) lines += "warning=$w"
        return lines
    }

    /**
     * `diff` 的文本行（§3.5）。任一侧 sidecar 解析失败由 CLI 先折成 INVALID；本函数只比
     * **已解析**的文档，缺 sidecar 的一侧传 null。
     */
    fun diff(aDoc: JsonValue.Obj?, bDoc: JsonValue.Obj?, aMain: MainInfo, bMain: MainInfo): List<String> {
        val a = aDoc?.let { validate(it) }
        val b = bDoc?.let { validate(it) }
        val lines = ArrayList<String>()
        var same = true
        val invalid = aMain.probe.problems.isNotEmpty() || bMain.probe.problems.isNotEmpty() ||
            (a != null && !a.ok) || (b != null && !b.ok)
        lines += "status=${if (invalid) "INVALID" else "PASS"}"
        lines += "main.a=${aMain.path} bytes=${aMain.bytes} sha256=${aMain.sha256} container=${containerLabel(aMain.probe)}"
        lines += "main.b=${bMain.path} bytes=${bMain.bytes} sha256=${bMain.sha256} container=${containerLabel(bMain.probe)}"
        val bytesSame = aMain.sha256 == bMain.sha256
        lines += "main.bytesSame=$bytesSame"
        if (!bytesSame) same = false
        lines += "sidecar.a=${if (aDoc != null) "present" else "absent"}"
        lines += "sidecar.b=${if (bDoc != null) "present" else "absent"}"

        same = scalarDiff("format", a?.format, b?.format, lines, same)
        same = scalarDiff("commit", a?.commit, b?.commit, lines, same)
        same = scalarDiff("randSeed", a?.randSeed, b?.randSeed, lines, same)
        same = scalarDiff("createdAt", a?.createdAt, b?.createdAt, lines, same)
        same = scalarDiff("funcDepth", a?.funcDepth, b?.funcDepth, lines, same)

        val stackA = aDoc?.strArrayOf("funcStack")
        val stackB = bDoc?.strArrayOf("funcStack")
        val stackSame = stackA == stackB
        lines += "funcStack.len.a=${stackA?.size ?: 0} funcStack.len.b=${stackB?.size ?: 0} same=$stackSame"
        if (!stackSame) {
            same = false
            firstDiffIndex(stackA.orEmpty(), stackB.orEmpty())?.let { i ->
                lines += "funcStack.firstDiff=$i a=${stackA?.getOrNull(i) ?: "<absent>"} b=${stackB?.getOrNull(i) ?: "<absent>"}"
            }
        }

        val tailA = aDoc?.strArrayOf("consoleTail")
        val tailB = bDoc?.strArrayOf("consoleTail")
        val tailSame = tailA == tailB
        lines += "consoleTail.len.a=${tailA?.size ?: 0} consoleTail.len.b=${tailB?.size ?: 0} same=$tailSame"
        if (!tailSame) {
            same = false
            firstDiffIndex(tailA.orEmpty(), tailB.orEmpty())?.let { i ->
                lines += "consoleTail.firstDiff=$i a=${tailA?.getOrNull(i) ?: "<absent>"} b=${tailB?.getOrNull(i) ?: "<absent>"}"
            }
        }

        val rngA = aDoc?.longArrayOf("rngState") ?: LongArray(0)
        val rngB = bDoc?.longArrayOf("rngState") ?: LongArray(0)
        val rngSame = rngA.contentEquals(rngB)
        lines += "rngState.len.a=${rngA.size} rngState.len.b=${rngB.size} same=$rngSame"
        if (!rngSame) {
            same = false
            val limit = minOf(rngA.size, rngB.size)
            var diffCount = 0
            var first = -1
            for (i in 0 until limit) {
                if (rngA[i] != rngB[i]) {
                    if (first < 0) first = i
                    diffCount++
                }
            }
            diffCount += kotlin.math.abs(rngA.size - rngB.size)
            if (first < 0) first = limit
            lines += "rngState.diffCount=$diffCount firstDiffIndex=$first" +
                " a=${rngA.getOrNull(first) ?: "<absent>"} b=${rngB.getOrNull(first) ?: "<absent>"}"
        }

        for (p in aMain.probe.problems) lines += "problem=main.a $p"
        for (p in bMain.probe.problems) lines += "problem=main.b $p"
        a?.problems?.forEach { lines += "problem=sidecar.a $it" }
        b?.problems?.forEach { lines += "problem=sidecar.b $it" }
        // 末行恒为 same=（契约 §3.5）；问题行排在它之前，且不影响 same 的纯比较语义。
        lines += "same=$same"
        return lines
    }

    private fun <T> scalarDiff(name: String, a: T?, b: T?, lines: MutableList<String>, same: Boolean): Boolean {
        val eq = a == b
        lines += "$name.a=${a ?: "<absent>"} $name.b=${b ?: "<absent>"} same=$eq"
        return same && eq
    }

    private fun <T> firstDiffIndex(a: List<T>, b: List<T>): Int? {
        val limit = minOf(a.size, b.size)
        for (i in 0 until limit) if (a[i] != b[i]) return i
        return if (a.size != b.size) limit else null
    }

    /** 容器标签（CLI 输出与 diff 文本共用一套拼写）。 */
    fun containerLabel(probe: ContainerProbe): String = when (probe.container) {
        Container.BINARY -> "binary"
        Container.BINARY_GZIP -> "binary-gzip"
        Container.TEXT -> "text"
        Container.UNKNOWN -> "unknown"
    }

    /** SHA-256（小写 hex）。 */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** RNG 状态摘要：把每个 long 按小端 8 字节拼起来再 SHA-256（跨平台定长）。 */
    fun rngDigest(state: LongArray): String {
        val buf = ByteArray(state.size * 8)
        var at = 0
        for (v in state) {
            for (k in 0 until 8) buf[at + k] = ((v ushr (8 * k)) and 0xFFL).toByte()
            at += 8
        }
        return sha256Hex(buf)
    }
}
