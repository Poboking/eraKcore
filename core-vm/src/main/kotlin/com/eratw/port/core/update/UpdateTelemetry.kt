package com.eratw.port.core.update

/**
 * 更新漏斗遥测基座（P1-4，本波只落基座、不接线）。
 *
 * 回答的问题是「镜像该不该加、差量该不该关、升级成不成，全靠猜」：把更新漏斗的
 * 十二个计数事件（检查 → 弹窗 → 下载 → 安装/应用）攒成匿名小批，交给调用方注入的
 * 发送口整批送走。字段只有事件名、UTC 时间戳（epoch 毫秒）、版本号、大小/类别这类
 * 计数维度 —— **零设备标识**：没有设备号、没有 IP、没有存档内容，聚合侧只按
 * 「事件名 + UTC 日」数数。
 *
 * ## 分工与边界
 *
 * - 本类：事件校验、手写 JSON 行编码、批量（定长缓冲 + 年龄上限）、持久化协调；
 * - 发送（HTTP 管线）归 app 壳：构造时注入 [send]，app 侧纪律见
 *   `UpdateTelemetrySink`（通道未配置 = 一个字节不出网）；
 * - 本类不碰网络、不碰文件系统、不碰 Android API（AGENTS.md 铁律 4）；
 *   持久化经 [Storage] 接口，时钟经 [nowMillis] 注入，单测好钉。
 *
 * ## 批量口径（先到先刷 + 硬上限 + 失败冷却）
 *
 * 缓冲上限 [MAX_BUFFER] 条、单条最长驻留 [MAX_AGE_MILLIS]（48 小时），**哪个先到
 * 就在哪个点刷**：
 * - 第 [MAX_BUFFER] + 1 条事件到达时，先整批刷（发送成功才清空），再容纳新事件；
 * - 事件到达时发现最旧一条已满 48h，先刷旧批，再落新事件 —— 遥测没有定时器可依赖
 *   （core 不起线程），年龄检查搭每次「写入/手动冲刷」的便车，检查间隔 12h
 *   （[UpdatePolicy.CHECK_INTERVAL_MILLIS]）意味着线上最坏多驻留一个检查周期；
 * - 发送失败：整批退回缓冲并保持持久化，下次写入或 [flushNow] 再试；同时进入
 *   [FAILURE_COOLDOWN_MILLIS] 的**冷却窗** —— 窗口内新事件照常入缓冲，但**不再**因
 *   「又满了一批」触发整批 POST（端点长期不可达时不再每条事件打一趟网络）。
 * - 冷却窗只是压重试频率，**不是丢数据**；真正让缓冲有界的是硬上限：缓冲超过
 *   [MAX_BUFFER] 且这批发不出去时，丢掉**最旧**的、只保留最近 [MAX_BUFFER] 条
 *   （[droppedCount] / [lastDropReason] 可观测，只进 logcat/调试面）。没有这一刀，
 *   端点一直不可达时缓冲会无界增长，每条新事件都整批 POST 一次。
 * - 持久化是整批覆盖写（[Storage.save]）；硬上限把条数钉在 32 ⇒ 每次写入最多重写
 *   32 行，代价有界。不为追加式写改 [Storage] 接口（app 侧实现不需要动）。
 *
 * ## 编码
 *
 * 手写 JSON 行（一行一个事件对象），零依赖，风格对齐 `tools/vmdbg` 的 Jsonl：
 * 字段序固定（event、at、version、kind、sizeBytes、force、failure、outcome、status），
 * null 维度整个不出现（不写 `"force":null`）。字符串维度（`kind`/`failure`/`outcome`/
 * `status`）走最小转义。
 * 持久化与发送用同一种行格式；读侧（[restore]）对坏行只跳过不抛 —— 缓冲坏了
 * 不能把更新面一起拖死。
 *
 * KDoc 注意：正文不出现块注释定界符字符对（见 AGENTS.md 铁律 5）。
 */
class UpdateTelemetry(
    private val storage: Storage,
    private val nowMillis: () -> Long,
    private val send: (List<String>) -> Boolean,
) {

    /** 待发事件的持久化口（app 壳给文件实现，单测给内存假件）。 */
    interface Storage {
        /** 读回全部待发行；读不出来（无文件 / IO 错）返回空表。 */
        fun load(): List<String>

        /** 整批覆盖写待发行。 */
        fun save(lines: List<String>)
    }

    /** 一条匿名事件；各维度的取值合法性由 [validate] 按事件名裁定。 */
    data class Event(
        val name: String,
        val atMillis: Long,
        val version: Long? = null,
        val kind: String? = null,
        val sizeBytes: Long? = null,
        val force: Boolean? = null,
        val failure: String? = null,
        /** 终态类别；允许档按事件分两格：`install_result` 收 success / cancelled / failed 三档，
         *  `assets_applied`（第 7 拍）只收 success / failed 两档，其余事件不许带。 */
        val outcome: String? = null,
        /** 原始状态码的短标签（`install_result` 且 outcome 为 failed 时才有；见 [isStatusTag]）。 */
        val status: String? = null,
    )

    private val lock = Any()
    private val buffer = ArrayDeque<Event>()

    /** 下次允许「因新事件触发整批重试」的时刻（早于它的写入只入缓冲，不打出站）。 */
    private var retryNotBeforeMillis = 0L

    /** 观测口：因硬上限丢掉的事件总条数（本实例生命周期内）。 */
    private var droppedEvents = 0L

    /** 观测口：最近一次丢最旧的原因（只进 logcat / 调试面，不进玩家面）。 */
    private var lastDropReason: String? = null

    init {
        restore()
    }

    // ------------------------------------------------------------------
    // 记录
    // ------------------------------------------------------------------

    /**
     * 校验并记录一条事件；缓冲触发上限或年龄上限时先整批冲刷（先到先刷）。
     *
     * 发送失败时缓冲**不会无限长**：超过 [MAX_BUFFER] 就丢最旧的、只留最近
     * [MAX_BUFFER] 条（[droppedCount] 可观测）；失败后的冷却窗内不再因写入触发重试。
     *
     * @return true = 已入缓冲；false = 坏输入被拒（什么都不写、什么都不发）
     */
    fun record(event: Event): Boolean {
        val problem = validate(event)
        if (problem != null) return false
        synchronized(lock) {
            val now = nowMillis()
            if (buffer.isNotEmpty() && now - buffer.first().atMillis >= MAX_AGE_MILLIS) {
                maybeFlushLocked(now)
            }
            buffer.addLast(event)
            if (buffer.size > MAX_BUFFER) {
                maybeFlushLocked(now)
                dropOverflowLocked()
            }
            persistLocked()
        }
        return true
    }

    /** 当前待发条数（观测口；接线后的设置页与调试面可用）。 */
    fun pendingCount(): Int = synchronized(lock) { buffer.size }

    /** 观测口：因硬上限丢掉的事件总条数（>0 说明端点长期不可达且事件量已超出保留窗）。 */
    fun droppedCount(): Long = synchronized(lock) { droppedEvents }

    /** 观测口：最近一次丢最旧的原因；没触发过硬上限时为 null。 */
    fun lastDropReason(): String? = synchronized(lock) { lastDropReason }

    /**
     * 手动冲刷（冷启动补发位）：整批交给 [send]，成功清缓冲与持久化，失败原样保留。
     *
     * **不受失败冷却限制**：这条是低频的生命周期/收尾入口（冷启、检查收尾、下载收尾），
     * 不是「每条事件都打一趟」；冷却只压 [record] 触发的重试。失败时会刷新冷却时刻。
     *
     * @return true = 缓冲已空（含原本就空）；false = 发送失败，待下次再试
     */
    fun flushNow(): Boolean = synchronized(lock) {
        if (buffer.isEmpty()) return true
        flushLocked(nowMillis())
        buffer.isEmpty()
    }

    /** 冷却窗内不因新事件重试；其余情况先刷一批。必须在 [lock] 内调用。 */
    private fun maybeFlushLocked(now: Long) {
        if (now < retryNotBeforeMillis) return
        flushLocked(now)
    }

    /** 必须在 [lock] 内调用：整批送 [send]，成功清缓冲并落盘；失败开一个冷却窗。 */
    private fun flushLocked(now: Long) {
        if (buffer.isEmpty()) return
        val lines = buffer.map { encode(it) }
        if (send(lines)) {
            buffer.clear()
            retryNotBeforeMillis = 0L
            // 成功即清盘：否则进程重启会把已经发出去的事件从盘上再补一次（计数翻倍）。
            persistLocked()
            return
        }
        retryNotBeforeMillis = now + FAILURE_COOLDOWN_MILLIS
    }

    /**
     * 硬上限：发送不出去时丢掉最旧的，只保留最近 [MAX_BUFFER] 条。必须在 [lock] 内调用。
     *
     * 为什么宁可丢最旧也不无界留着：事件是计数维度，保留「最近一小窗」对聚合仍有意义；
     * 而无界增长会把持久化整批重写从「≤32 行」拖成随失败时长增长的 O(n) 写盘，
     * 还会让每条新事件都整批 POST 一次（端点长期不可达时最坏是流量与耗电双输）。
     */
    private fun dropOverflowLocked() {
        if (buffer.size <= MAX_BUFFER) return
        var dropped = 0
        while (buffer.size > MAX_BUFFER) {
            buffer.removeFirst()
            dropped++
        }
        droppedEvents += dropped.toLong()
        lastDropReason = "缓冲超过 $MAX_BUFFER 条且发送失败：丢掉最旧的 $dropped 条（只保留最近 $MAX_BUFFER 条）"
    }

    private fun persistLocked() {
        storage.save(buffer.map { encode(it) })
    }

    /** 启动时从 [storage] 读回待发行；坏行跳过，好行还原进缓冲。 */
    private fun restore() {
        for (line in storage.load()) {
            val event = parseLine(line) ?: continue
            if (validate(event) != null) continue
            buffer.addLast(event)
        }
    }

    // ------------------------------------------------------------------
    // 事件合法性（坏输入拒绝）
    // ------------------------------------------------------------------

    /**
     * 逐事件裁定维度合法性；返回 null = 合法，否则给拒绝原因（单测断言用）。
     *
     * 维度是按事件名**收紧**的：不允许的维度出现即拒（比如 check_started 带 kind），
     * 拼写错误或接线错位当场落不进缓冲，而不是污染聚合表。`kind` 的合法集按事件分三档：
     * `offer_shown`/`download_*` 只收 `apk|assets`，`offer_action` 只收
     * `download|skip|decline`，`assets_applied` 只收数据包档位三档（[KIND_DATA_ARTIFACT]，
     * 经 [dataArtifactKindLabel] 映射，`NONE` 档没有标签 ⇒ 成功拍必缺 kind、整条被拒，
     * 「NONE 不发这一拍」就是在这一格挡住的，app 侧不需要也不许再判一遍）。
     *
     * `outcome` / `status` 是**终态专用**的两格：`install_result` 收三档、
     * `outcome` 必填且 `status` 只在 `outcome = failed` 时出现并必须是短标签
     * （[isStatusTag]）—— 安装回执里能拿到长句（系统原话与摘要），那些一个都不许进漏斗。
     * `assets_applied`（第 7 拍，数据包应用）收**两档** outcome（success / failed；取消对
     * 冷启解包线程不成立），其 `failure` 只在 failed 档必填且走同一条 [isStatusTag] 严判据
     * （应用失败类别是枚举短标签，`problem` 原话可能带路径与文件名，绝不进漏斗）；
     * `status` 仍归 `install_result` 独占。
     */
    fun validate(event: Event): String? {
        if (event.name !in EVENT_NAMES) return "未知事件名 ${event.name}"
        if (event.atMillis <= 0L) return "时间戳非法"
        event.version?.let { if (it <= 0L) return "版本号非法" }
        event.sizeBytes?.let { if (it < 0L) return "大小非法" }
        event.failure?.let {
            if (it.isBlank()) return "失败类别空白"
            if (it.length > MAX_DIM_CHARS) return "失败类别超长"
        }
        val kindsAllowed: Set<String>? = when (event.name) {
            in KIND_ARTIFACT_EVENTS -> KIND_ARTIFACT
            EVENT_OFFER_ACTION -> KIND_ACTION
            else -> null
        }
        val kind = event.kind
        if (kindsAllowed != null) {
            if (kind == null) return "${event.name} 缺 kind"
            if (kind !in kindsAllowed) return "${event.name} kind 非法：$kind"
        } else if (kind != null && event.name != EVENT_ASSETS_APPLIED) {
            return "${event.name} 不允许 kind"
        }
        if (event.force != null && event.name != EVENT_OFFER_SHOWN) return "${event.name} 不允许 force"
        val failureAllowed = event.name == EVENT_CHECK_FAILED || event.name == EVENT_DOWNLOAD_FAILED ||
            (event.name == EVENT_ASSETS_APPLIED && event.outcome == OUTCOME_FAILED)
        if (failureAllowed && event.failure == null) return "${event.name} 缺 failure"
        if (!failureAllowed && event.failure != null) return "${event.name} 不允许 failure"
        val sizeAllowed = event.name == EVENT_DOWNLOAD_FINISHED || event.name == EVENT_DOWNLOAD_FAILED
        if (!sizeAllowed && event.sizeBytes != null) return "${event.name} 不允许 sizeBytes"
        when (event.name) {
            EVENT_INSTALL_RESULT -> {
                // —— U6b 落的三档口径，判据一条不许动（下方 assets_applied 是第 7 拍的新格，两者互借不得）。
                val outcome = event.outcome ?: return "install_result 缺 outcome"
                if (outcome !in OUTCOMES) return "install_result outcome 非法：$outcome"
                val status = event.status
                if (status != null && outcome != OUTCOME_FAILED) return "install_result 只在 failed 档带 status"
                if (status != null && !isStatusTag(status)) return "install_result status 非法：$status"
            }

            EVENT_ASSETS_APPLIED -> {
                val outcome = event.outcome ?: return "assets_applied 缺 outcome"
                if (outcome !in APPLY_OUTCOMES) return "assets_applied outcome 非法：$outcome"
                if (kind != null && kind !in KIND_DATA_ARTIFACT) return "assets_applied kind 非法：$kind"
                // 成功拍必带档位：DataArtifactKind.NONE 经 dataArtifactKindLabel 得 null，
                // 走到这里就是「数据通道不成立却发了应用拍」的接线错位，整条拒。
                if (outcome == OUTCOME_SUCCESS && kind == null) return "assets_applied 成功档缺 kind"
                // 失败档的 failure 必填由上面 failureAllowed 那格先拒（缺 → 「assets_applied 缺 failure」）；
                // 这里只补严判据 —— 与 install_result 的 status 同一档字符集，problem 原话灌不进来。
                val failure = event.failure
                if (outcome == OUTCOME_FAILED && failure != null && !isStatusTag(failure)) {
                    return "assets_applied failure 非法：$failure"
                }
                if (event.status != null) return "assets_applied 不允许 status"
            }

            else -> {
                if (event.outcome != null) return "${event.name} 不允许 outcome"
                if (event.status != null) return "${event.name} 不允许 status"
            }
        }
        return null
    }

    /**
     * `status` 短标签判据：**只收字母数字与点/下划线/连字符**，非空白且不超过 [MAX_DIM_CHARS]。
     *
     * 为什么比 `failure` 那一格更严：状态码是「可枚举的读数」（PackageInstaller 的 0..8 与
     * 未知码），而它旁边的系统原话是自由文本 —— 把字符集钉死，接线错位把长句灌进来时
     * core 直接拒，而不是静默收下一条聚合表永远数不到的脏维（隐私面同一条理由：
     * 自由文本里可能出现路径与文件名）。
     */
    fun isStatusTag(status: String): Boolean =
        status.isNotEmpty() && status.length <= MAX_DIM_CHARS && status.all { it in STATUS_TAG_CHARS }

    // ------------------------------------------------------------------
    // 编码 / 解码（手写 JSON 行，零依赖）
    // ------------------------------------------------------------------

    /** 编码一条事件为 JSON 行；字段序固定，null 维度整个不出现。 */
    fun encode(event: Event): String = buildString {
        append("{\"event\":").append(quote(event.name))
        append(",\"at\":").append(event.atMillis)
        event.version?.let { append(",\"version\":").append(it) }
        event.kind?.let { append(",\"kind\":").append(quote(it)) }
        event.sizeBytes?.let { append(",\"sizeBytes\":").append(it) }
        event.force?.let { append(",\"force\":").append(it) }
        event.failure?.let { append(",\"failure\":").append(quote(it)) }
        event.outcome?.let { append(",\"outcome\":").append(quote(it)) }
        event.status?.let { append(",\"status\":").append(quote(it)) }
        append("}")
    }

    /**
     * 解一行 JSON 还原事件；形状不对 / 缺必填键返回 null（调用方跳过）。
     * 只认本类写出的字段集，未知键整行拒（防脏数据静默混回缓冲）。
     */
    fun parseLine(line: String): Event? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val fields = HashMap<String, String>()
        var i = 1
        val end = trimmed.length - 1
        while (i < end) {
            while (i < end && (trimmed[i] == ',' || trimmed[i].isWhitespace())) i++
            if (i >= end) break
            if (trimmed[i] != '"') return null
            val keyEnd = scanStringEnd(trimmed, i) ?: return null
            val key = unescape(trimmed.substring(i + 1, keyEnd))
            i = keyEnd + 1
            while (i < end && trimmed[i].isWhitespace()) i++
            if (i >= end || trimmed[i] != ':') return null
            i++
            while (i < end && trimmed[i].isWhitespace()) i++
            val value: String
            if (i < end && trimmed[i] == '"') {
                val valueEnd = scanStringEnd(trimmed, i) ?: return null
                value = unescape(trimmed.substring(i + 1, valueEnd))
                i = valueEnd + 1
            } else {
                val start = i
                while (i < end && trimmed[i] != ',') i++
                value = trimmed.substring(start, i).trim()
            }
            if (key !in KNOWN_KEYS) return null
            fields[key] = value
        }
        val name = fields["event"] ?: return null
        val at = fields["at"]?.toLongOrNull() ?: return null
        return Event(
            name = name,
            atMillis = at,
            version = fields["version"]?.toLongOrNull(),
            kind = fields["kind"],
            sizeBytes = fields["sizeBytes"]?.toLongOrNull(),
            force = when (fields["force"]) {
                "true" -> true
                "false" -> false
                else -> null
            },
            failure = fields["failure"],
            outcome = fields["outcome"],
            status = fields["status"],
        )
    }

    /** 最小转义：引号、反斜杠、控制字符（事件维度都是短标签，其余原样）。 */
    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** [from] 指向开引号；返回闭引号下标（处理转义），找不到返回 null。 */
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
                else -> sb.append(d)
            }
            i += 2
        }
        return sb.toString()
    }

    companion object {
        /** 缓冲上限（条）：一两次会话的量，整批 POST 一趟就走。 */
        const val MAX_BUFFER = 32

        /** 单条最长驻留（毫秒）：48 小时，先到先刷的年龄那一档。 */
        const val MAX_AGE_MILLIS = 48L * 60L * 60L * 1000L

        /**
         * 发送失败后的冷却窗（毫秒）：窗口内**新事件**不再触发整批重试（只入缓冲，满上限丢最旧）。
         *
         * 取 5 分钟：端点短期抖动后下一批还能自己好；端点长期不可达时也不会每条事件打一趟网络。
         * [flushNow]（冷启/收尾的低频补发位）不受这条限制。
         */
        const val FAILURE_COOLDOWN_MILLIS = 5L * 60L * 1000L

        /** 类别/失败类别标签的长度上限：计数维度是短标签，不是自由文本。 */
        const val MAX_DIM_CHARS = 40

        const val EVENT_CHECK_STARTED = "check_started"
        const val EVENT_CHECK_OK = "check_ok"
        const val EVENT_CHECK_FAILED = "check_failed"
        const val EVENT_OFFER_SHOWN = "offer_shown"
        const val EVENT_OFFER_ACTION = "offer_action"
        const val EVENT_DOWNLOAD_STARTED = "download_started"
        const val EVENT_DOWNLOAD_FINISHED = "download_finished"
        const val EVENT_DOWNLOAD_CANCELLED = "download_cancelled"
        const val EVENT_DOWNLOAD_FAILED = "download_failed"
        const val EVENT_INSTALL_REQUESTED = "install_requested"

        /** 安装终态（第 6 拍）：PackageInstaller 会话回执落到成功 / 取消 / 失败那一刻。 */
        const val EVENT_INSTALL_RESULT = "install_result"
        const val EVENT_ASSETS_APPLIED = "assets_applied"

        /** 事件名白名单（聚合侧只数这十二个）。 */
        val EVENT_NAMES: List<String> = listOf(
            EVENT_CHECK_STARTED,
            EVENT_CHECK_OK,
            EVENT_CHECK_FAILED,
            EVENT_OFFER_SHOWN,
            EVENT_OFFER_ACTION,
            EVENT_DOWNLOAD_STARTED,
            EVENT_DOWNLOAD_FINISHED,
            EVENT_DOWNLOAD_CANCELLED,
            EVENT_DOWNLOAD_FAILED,
            EVENT_INSTALL_REQUESTED,
            EVENT_INSTALL_RESULT,
            EVENT_ASSETS_APPLIED,
        )

        /** 产物类别：整包 APK / 数据包 zip，两处共用同一套词。 */
        const val KIND_APK = "apk"
        const val KIND_ASSETS = "assets"
        val KIND_ARTIFACT: Set<String> = setOf(KIND_APK, KIND_ASSETS)

        /** 弹窗动作：去下载 / 这次跳过 / 本次不要。 */
        const val ACTION_DOWNLOAD = "download"
        const val ACTION_SKIP = "skip"
        const val ACTION_DECLINE = "decline"
        val KIND_ACTION: Set<String> = setOf(ACTION_DOWNLOAD, ACTION_SKIP, ACTION_DECLINE)

        private val KIND_ARTIFACT_EVENTS: Set<String> = setOf(
            EVENT_OFFER_SHOWN,
            EVENT_DOWNLOAD_STARTED,
            EVENT_DOWNLOAD_FINISHED,
            EVENT_DOWNLOAD_CANCELLED,
            EVENT_DOWNLOAD_FAILED,
        )

        /** 安装终态三档：装成 / 玩家取消或系统中止 / 失败（失败可另带状态码短标签）。 */
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_CANCELLED = "cancelled"
        const val OUTCOME_FAILED = "failed"
        val OUTCOMES: Set<String> = setOf(OUTCOME_SUCCESS, OUTCOME_CANCELLED, OUTCOME_FAILED)

        /**
         * 数据包应用（第 7 拍）两档：应用成 / 应用失败。
         *
         * 没有 cancelled：这一拍跑在冷启解包线程上，玩家没有可取消的形态 ——
         * 硬造一档只会让聚合表出现永远不会命中的桶。
         */
        val APPLY_OUTCOMES: Set<String> = setOf(OUTCOME_SUCCESS, OUTCOME_FAILED)

        /**
         * 数据包档位三档（第 7 拍的 kind 维词表）：与 [DataArtifactKind] 的枚举名**逐字对齐**
         * 的小写形态（见 [dataArtifactKindLabel]）。`NONE` 不在词表里（数据通道不成立没有应用拍）。
         */
        const val KIND_BLOCK_DELTA = "block_delta"
        const val KIND_FILE_DELTA = "file_delta"
        const val KIND_FULL = "full"
        val KIND_DATA_ARTIFACT: Set<String> = setOf(KIND_BLOCK_DELTA, KIND_FILE_DELTA, KIND_FULL)

        /**
         * [DataArtifactKind] → 第 7 拍 kind 标签的**唯一**映射（真相源是枚举本身，词表就是
         * 枚举名的小写形态，逐字对齐由本函数与 [KIND_DATA_ARTIFACT] 一起钉死）。
         *
         * 为什么映射放 core 而不是让 app 侧 `name.lowercase()` 自成一派：档位词表改一个字
         * （比如 FILE_DELTA 换成 file-delta）必须让 core 校验、app 装配、聚合端点**同一拍**换代，
         * app 自己转小写等于埋第二份真相 —— 将来枚举改名时 app 悄悄发出词表外的维，
         * 表现是 record 恒 false 的静默丢计数，正是这一族遥测最难查的病形。
         *
         * [DataArtifactKind.NONE] 与 null 都没有标签：「NONE 不发这一拍」的挡点在
         * [validate]（成功档缺 kind 整条拒），这里只负责给不出标签。
         */
        fun dataArtifactKindLabel(kind: DataArtifactKind?): String? = when (kind) {
            DataArtifactKind.BLOCK_DELTA -> KIND_BLOCK_DELTA
            DataArtifactKind.FILE_DELTA -> KIND_FILE_DELTA
            DataArtifactKind.FULL -> KIND_FULL
            DataArtifactKind.NONE -> null
            null -> null
        }

        /**
         * `status` 短标签允许的字符集：字母数字加 `.`、`_`、`-`（负数状态码要连字符）。
         *
         * 空白、引号、路径分隔符一个都不收 —— 状态码是枚举读数，不是那句系统原话。
         */
        private val STATUS_TAG_CHARS: Set<Char> = ('a'..'z').toSet() + ('A'..'Z') + ('0'..'9') +
            setOf('.', '_', '-')

        private val KNOWN_KEYS: Set<String> = setOf(
            "event", "at", "version", "kind", "sizeBytes", "force", "failure", "outcome", "status",
        )
    }
}
