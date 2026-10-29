package com.eratw.port.core.diag

/**
 * 错误上报（方案③「Serverless 上报端点」）的纯判定层：app 壳把方案①落盘的报告包
 * **原样** POST 到部署好的 Cloudflare Worker（KV 存储），本对象只承载与平台无关的
 * 判定逻辑，让出口有定向单测钉住（AGENTS.md 铁律 5 的出口读数要求）。
 *
 * ## 分工与边界
 *
 * - HTTP 管线（连接、超时、重试时机）归 app 壳（`ErrorReportUploader`）；
 * - 端点本体（`tools/report-endpoint/worker.js`）归部署者，随仓交付、账号自备；
 * - 本层不碰网络、不碰 Android API。
 *
 * ## 隐私与安全口径（与 [ErrorReport] 类头同源，接线者必读）
 *
 * - 上报内容 = 方案①的脱敏报告全文，**原样**（text/plain 直传，不做 JSON 包裹，
 *   不在传输面加工）；变量取值与存档数据依然不得进入；
 * - 端点只允许 https（[isHttpsEndpoint] 拒明文 http）：报告虽脱敏，也不走明文信道；
 * - 上报是否启用由**出包参数**决定（端点缺省空 = 通道整体关闭）；部署者选择
 *   打开自动上报即在替玩家做隐私披露，runbook 里要提醒这一点。
 * KDoc 注意：正文不出现块注释定界符字符对（见 AGENTS.md 铁律 5）。
 */
object ErrorReportUpload {

    /** 单份报告的体积上限（字节）：超过直接本地拒发，不浪费玩家流量。 */
    const val MAX_BODY_BYTES = 256 * 1024

    /** 共享令牌的请求头（Worker 侧比对 secret；挡掉无凭据的垃圾写入）。 */
    const val TOKEN_HEADER = "X-Eratw-Token"

    /** 报告文件名的请求头（Worker 用来命名 KV 条目；内容只允许报告名形状）。 */
    const val NAME_HEADER = "X-Eratw-Report-Name"

    /** 已上报索引文件名（reportDir 内，一行一个已上报文件名；点前缀躲开报告扫描）。 */
    const val INDEX_FILE_NAME = ".uploaded"

    /** 索引容量上限：滚动保留最近 N 条，避免长期使用后索引无限膨胀。 */
    const val INDEX_CAP = 200

    /** POST 超时（毫秒）：失败路径上的上报不许拖住 VM 线程退出太久。 */
    const val TIMEOUT_MS = 5_000

    /** 崩溃兜底路径的超时（毫秒）：进程将死，最多等这么久就交回系统崩溃处理。 */
    const val CRASH_TIMEOUT_MS = 3_000

    /**
     * 端点是否可用：必须 https 且无空白。明文 http 一律拒（见类头隐私口径）。
     * 空/空白 = 通道未配置，返回 false（调用方整体跳过，零行为）。
     */
    fun isHttpsEndpoint(endpoint: String?): Boolean {
        if (endpoint.isNullOrBlank()) return false
        val trimmed = endpoint.trim()
        return trimmed.startsWith("https://") && trimmed.none { it.isWhitespace() }
    }

    /** 是否报告文件名（与方案① [ErrorReport.fileNameAt] 的产出同形状；索引文件不在此列）。 */
    fun isReportFileName(name: String): Boolean =
        name.startsWith("eratw-report-") && name.endsWith(".txt")

    /**
     * 待上报名单：报告文件里减去已上报索引，按名字升序（时间序）返回。
     * 索引里可能引用已被 prune 删掉的文件——以磁盘实存名单为准，天然自愈。
     */
    fun pendingNames(reportFileNames: List<String>, uploaded: Set<String>): List<String> =
        reportFileNames.filter { isReportFileName(it) && it !in uploaded }.sorted()

    /**
     * 合并已上报索引：旧条目 + 本次新条目，去重、按名字升序、滚动保留最近
     * [INDEX_CAP] 条（尾部保留：最新报告的索引永不丢）。
     */
    fun mergeIndex(existing: Set<String>, newNames: List<String>, cap: Int = INDEX_CAP): List<String> =
        (existing + newNames).sorted().takeLast(cap)
}
