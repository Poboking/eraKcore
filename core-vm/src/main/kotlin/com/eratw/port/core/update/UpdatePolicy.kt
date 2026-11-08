package com.eratw.port.core.update

/**
 * 更新通道的**纯判定层**：镜像候选顺序、地址与文件名合法性、版本比较、检查冷却、空间闸。
 *
 * ## 为什么单独一个对象
 *
 * 与 [ErrorReportUpload] 同构：HTTP 管线、下载、解包都归 app 壳，凡「判错了就会误伤玩家」的
 * 决定全部下沉到这里，用定向单测钉住。最贵的几条判断都在本文件（逐条的代价见下面的表）：
 *
 * - **镜像顺序回退**：国内源经常整体失联（本仓实测 GitHub 443 间歇不可达），所以一条源
 *   取不到清单就必须换下一条，且**顺序由发布者决定、不能被清单自己改写**（否则失联的源可以
 *   靠清单把玩家引流到任意新地址）；
 * - **要不要下载**：版本比较与冷却判定 —— 判错一次就是几百 MiB 的白跑流量；
 * - **能不能装**：字节数够不够 —— 743 MiB 的包下到一半没空间，比不下载恶劣得多。
 *
 * 本对象不碰网络、不碰文件系统、不碰 Android API（AGENTS.md 铁律 4）。
 *
 * ## 判错了分别贵在哪（写死每条判定前先想这一栏）
 *
 * | 判定 | 判错的代价 |
 * |---|---|
 * | [deltaUsable] / [dataArtifactFor] | 该回落全量却走了差量 = 缝出一份内容不对的语料，下次启动一路报错；该走差量却回落 = 白跑 ≈680 MiB |
 * | [resumeOffset] | 认了过期或超长的 `.part` = 拿到一个 sha 必错的缝合文件，玩家每次重试都失败在同一处 |
 * | [retryBackoffMillis] | 档太密 = 拿玩家的流量对着失联的镜像猛怼；档太稀 = 一次网络抖动就整条通道判死 |
 * | [isVersionSuppressed] | 抑制过头 = 修了致命 bug 的新版永远提醒不到；抑制不足 = 同一个版本天天刷屏 |
 * | [forbiddenByConfig] | 配置说禁止却还是发了请求 = 违背原版语义，也违背「不静默」这条硬约束 |
 */
object UpdatePolicy {

    /** 两次自动检查的最小间隔（毫秒）：12 小时。一天两次封顶，不拿玩家的流量当轮询。 */
    const val CHECK_INTERVAL_MILLIS: Long = 12L * 60L * 60L * 1000L

    /**
     * 半截下载（`.part`）的可信年龄上限：**7 天**。
     *
     * 超过它就不再续：产物在桶上是会被同名覆盖重发的（sha 变了），一份躺了一周的 `.part`
     * 前缀到底属于哪一版无从判断，而**拼错了不会立刻发现** —— 它只在最后一次 sha 比对里
     * 才报错，报完还被当成「下载失败」原地重来，白占一周的流量。
     * 7 天取自「一周内同一文件名被换掉的概率足够低」，不是精确边界；真正的正确性兜底永远是 sha。
     */
    const val PART_MAX_AGE_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L

    /**
     * 下载重试的等待档位（毫秒），下标就是 [retryBackoffMillis] 的 `attempt`。
     *
     * 1s → 2s → 5s → 15s → 30s：前几档治「抖动」，后几档治「镜像正在被人拆/带宽打满」。
     * 不做指数无限增长 —— 档位走完就该换镜像或收手，而不是在一条死源上把玩家耗到放弃。
     */
    val RETRY_BACKOFF_MILLIS: List<Long> = listOf(1_000L, 2_000L, 5_000L, 15_000L, 30_000L)

    /** 同时参与回退的镜像上限：再多既没有意义，也给清单留了刷屏的口子。 */
    const val MAX_MIRRORS: Int = 8

    /** 灰度分桶数：bucket 合法取值 0..99，百分比 N 表示选中 bucket 落在 0 到 N-1 的设备。 */
    const val ROLLOUT_BUCKETS: Int = 100

    /** 清单缺席 rollout-percentage（或值写坏了）时的读数：100 = 全量，不挡任何设备。 */
    const val FULL_ROLLOUT_PERCENTAGE: Int = 100

    /** 连续失败达到这个数的内置源判为「近期失联」，降位但不删除。 */
    const val MIRROR_DEMOTE_FAILURES: Int = 3

    /** 最后一次成功距今超过这个时长也算失联（7 天，与 [PART_MAX_AGE_MILLIS] 同量级）。 */
    const val MIRROR_STALE_AFTER_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L

    /** 单条镜像地址长度上限：正常几十字符，超了只可能是拼出来的垃圾。 */
    const val MAX_MIRROR_CHARS: Int = 512

    /** 下载后还要给系统留的余量（字节）：解包器要写同目录临时文件，装 APK 也要腾地方。 */
    const val MIN_FREE_HEADROOM_BYTES: Long = 512L * 1024L * 1024L

    /** 盘上从没写过数据版本时的读数（与 [assetsUpdateNeeded] 的 installedVersion 同口径）。 */
    const val NO_ASSETS_INSTALLED: Long = 0L

    /**
     * https 基址归一：去空白、必须 `https://` 开头、必须带主机名、不许查询串与片段，末尾补斜杠。
     *
     * 明文 http 一律拒（与 [com.eratw.port.core.diag.ErrorReportUpload.isHttpsEndpoint] 同口径）：
     * 更新包是「能替换玩家游戏数据」的字节流，不给降级成明文的口子。国内免费虚拟主机里 http
     * 很常见，那条路请走「自己套一层 https」而不是让客户端开口子。
     *
     * 拒查询串是因为下载地址靠**字符串拼接**得到：`基址 + 文件名` 一旦基址带 `?a=b`，
     * 文件名就落进查询串里，玩家拿到的是一个取不到文件的地址而不是报错，更难查。
     *
     * ## 主机段的收紧（P2，L6）
     *
     * 基址会与清单里的文件名拼成下载 URL、也会被落盘/回读，所以主机段必须是「一个说得清的
     * 域名或 IPv4」：
     * - **拒 userinfo（`@`）**：`https://user@host/` 的 `user` 段在拼接与展示里都是一颗雷
     *   （凭据会跟着 URL 走、host 也可能被读成 userinfo 之后的部分）；
     * - **拒反斜杠 `\`**：不同解析器对 `\` 的处理不一致（有的当路径分隔符），拼接后语义漂移；
     * - 主机只许 `[a-z0-9.-]`（统一小写、去末尾点之后判），域名与 IPv4 都在这套字符里；
     *   下划线/百分号/中文/方括号 IPv6 一律拒 —— 它们要么不是合法 DNS 名、要么会让拼接歧义；
     * - 端口若出现，必须是纯数字（`https://a.example:8443/` 保留；`:abc` / 空端口 / 多冒号拒）；
     * - 主机小写归一、去掉末尾点（`https://A.Example./x` → `https://a.example/x`），
     *   路径段保持原样（路径大小写敏感，不许顺手 lower）。
     *
     * 其它既有口径一字不动：必须 `https://`、无空白/控制字符、无 `?`/`#`、≤ [MAX_MIRROR_CHARS]、
     * 末尾补斜杠。
     *
     * @return 归一后的地址（主机小写、末尾恒有一个斜杠）；不合法返回 null
     */
    fun normalizeMirror(url: String?): String? {
        if (url == null) return null
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_MIRROR_CHARS) return null
        if (trimmed.any { it.isWhitespace() || it.code < 0x21 || it.code == 0x7F }) return null
        if (!trimmed.startsWith("https://")) return null
        // 反斜杠与 userinfo 在拼接/解析里语义会漂：直接拒（见 KDoc 的主机段收紧）。
        if (trimmed.indexOf('\\') >= 0) return null
        val withoutScheme = trimmed.substring("https://".length)
        if (withoutScheme.isEmpty() || withoutScheme[0] == '/') return null
        if (withoutScheme.contains('?') || withoutScheme.contains('#')) return null
        if (withoutScheme.contains('@')) return null
        val authority = withoutScheme.substringBefore('/')
        if (authority.isEmpty()) return null
        val hostRaw = authority.substringBefore(':')
        val port = if (authority.contains(':')) authority.substringAfter(':') else null
        if (port != null && (port.isEmpty() || !port.all { it in '0'..'9' })) return null
        // 主机小写归一、去末尾点；只许 [a-z0-9.-]。
        val host = hostRaw.lowercase().trimEnd('.')
        if (host.isEmpty() || !host.contains('.')) return null
        if (!host.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return null
        val normalizedHost = if (port != null) "$host:$port" else host
        val rest = withoutScheme.substring(authority.length)
        val normalized = "https://$normalizedHost$rest"
        return if (normalized.endsWith("/")) normalized else "$normalized/"
    }

    /**
     * 镜像候选序：**内置表最前**，其次持久化表，最后清单声明的表；逐个归一并去重。
     *
     * 顺序就是这里的优先级，不是清单的优先级 —— 清单能补充新镜像（让「加一个国内源」这件事
     * 不需要重出 APK），但不能把内置源挤到后面去。截断到 [MAX_MIRRORS]。
     */
    fun mirrorOrder(
        builtIn: List<String>,
        persisted: List<String> = emptyList(),
        declared: List<String> = emptyList(),
    ): List<String> {
        val ordered = LinkedHashSet<String>()
        for (group in listOf(builtIn, persisted, declared)) {
            for (raw in group) {
                val normalized = normalizeMirror(raw) ?: continue
                ordered += normalized
            }
        }
        return ordered.take(MAX_MIRRORS).toList()
    }

    /** 把（已归一的）基址与清单里的文件名拼成下载地址。 */
    fun downloadUrl(mirrorBase: String, fileName: String): String =
        normalizeMirror(mirrorBase).orEmpty() + fileName

    /**
     * 下载文件名合法性：只许 `A-Za-z0-9._+-`，且不许出现 `..`。
     *
     * 这个名字会直接落进应用私有目录，并由 FileProvider 按名回读给系统安装器；
     * allowlist 字符集 + 禁 `..` 比「正则黑名单」可靠，也顺手挡掉控制字符、空白、
     * 反斜杠与所有路径分隔符的变体。
     */
    fun isArtifactFileName(name: String): Boolean {
        if (name.isEmpty() || name.length > 200) return false
        if (name.contains("..")) return false
        if (name == "." || name.startsWith("-")) return false
        return name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '+' || it == '-' }
    }

    /** 摘要归一：64 位十六进制，大小写都收，返回小写；形状不对返回 null。 */
    fun normalizeSha(sha: String?): String? {
        val trimmed = sha?.trim()?.lowercase() ?: return null
        if (trimmed.length != 64) return null
        return if (trimmed.all { it in '0'..'9' || it in 'a'..'f' }) trimmed else null
    }

    /**
     * 数据是否需要更新：暂存版本**严格大于**盘上版本。
     *
     * 判「大于」不判「不等于」的两个理由（[assetsUpdateNeeded] 与装载前那拍共用这一条）：
     * 玩家从新镜像退回旧镜像时不该把语料降级；新装的 APK 已经带了同等或更新的数据时，
     * 一份旧暂存包必须被**丢掉**而不是盖回盘上。
     */
    fun dataUpdateNeeded(stagedVersion: Long, installedVersion: Long): Boolean =
        stagedVersion > installedVersion

    /** 整包是否需要更新：清单版本**严格大于**本地版本才算有新版（相等不下载，回退安装也不催）。 */
    fun apkUpdateNeeded(currentVersionCode: Long, info: UpdateInfo): Boolean {
        val apk = info.apk ?: return false
        return apk.versionCode > currentVersionCode
    }

    /**
     * 数据包是否需要更新：清单版本**严格大于**盘上已应用版本。
     *
     * 判「大于」不判「不等于」：玩家从新镜像退回旧镜像时（清单版本比本地低）应当**不动盘**，
     * 否则一次源切换就能把玩家的语料降级，而立绘/CSV 降级的后果是旧产物读新数据一路报错。
     */
    fun assetsUpdateNeeded(installedVersion: Long, info: UpdateInfo): Boolean {
        val assets = info.assets ?: return false
        return dataUpdateNeeded(assets.version, installedVersion)
    }

    /**
     * 该不该做这次自动检查：没查过（[lastCheckMillis] ≤ 0）或距上次超过 [intervalMillis]。
     *
     * 时钟回拨（玩家改系统时间、时区切换）按「该查」处理：宁可多查一次，也不要因为
     * `now - last` 是负数而永远不再检查。
     */
    fun isCheckDue(
        lastCheckMillis: Long,
        nowMillis: Long,
        intervalMillis: Long = CHECK_INTERVAL_MILLIS,
    ): Boolean {
        if (lastCheckMillis <= 0L) return true
        val elapsed = nowMillis - lastCheckMillis
        return elapsed < 0L || elapsed >= intervalMillis
    }

    /**
     * 空间闸：可用字节必须同时装得下 [neededBytes] 并留下 [headroom] 余量。
     *
     * 余量默认 [MIN_FREE_HEADROOM_BYTES]（512 MiB）：数据包的解包要在同目录写 `.tmp`，
     * 整包 APK 的安装要先把旧 dex 优化一遍，两边都不是「下完就完」。
     */
    fun hasEnoughSpace(
        usableBytes: Long,
        neededBytes: Long,
        headroom: Long = MIN_FREE_HEADROOM_BYTES,
    ): Boolean = usableBytes > 0L && usableBytes - neededBytes >= headroom

    /**
     * 网络类型是否允许自动下载大文件：只有 Wi-Fi 与「不计量的以太网」放行。
     *
     * 判据由 app 壳算好传进来（core 层不碰 Android 的 ConnectivityManager），本函数只承载
     * 「哪种判定结果可以自动开跑」这条口径：蜂窝一律要玩家点头。
     */
    fun autoDownloadAllowed(isUnmetered: Boolean): Boolean = isUnmetered

    /**
     * 章不一致是否该拦下这次下载。
     *
     * 缺席（清单没写或本设备读不出自己的章）= **不拦**：这一行是发布者自愿多给的信息，
     * 不能变成「发布者没写就不许更新」的硬条件，否则老清单立刻全线失效。
     * 两边都有值且不相等 = 拦：装了也是 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，
     * 白耗几百 MiB 流量还顺便把玩家的教育成本拉满 —— 该说的是「先卸载（存档会没）」。
     */
    fun certBlocksInstall(localCertSha256: String, declaredCertSha256: String): Boolean {
        val local = normalizeSha(localCertSha256) ?: return false
        val declared = normalizeSha(declaredCertSha256) ?: return false
        return local != declared
    }

    /** 一句话说明「这次要下多少」，用于弹窗正文：MiB 向上取整（不报假精确数字），不足 1 MiB 报 KiB。 */
    fun sizeLabel(bytes: Long): String {
        if (bytes < 0L) return "未知大小"
        val mib = 1024L * 1024L
        if (bytes < mib) return "${(bytes + 1023L) / 1024L} KiB"
        return "${(bytes + mib - 1L) / mib} MiB"
    }

    // ------------------------------------------------------------------
    // 差量数据包：选哪一件、能不能续传、失败怎么退避、玩家跳过算不算（波次 CONTRACT-UPDATE §3）
    // ------------------------------------------------------------------

    /**
     * 差量包能不能用：差量三件齐全，且清单声明的**基线版本恰好等于**盘上已应用版本。
     *
     * 「恰好等于」而不是「小于等于」：差量包只带变更/新增文件 + 一张删除表（契约 §1.3），
     * 它是相对**某一版**语料的 diff。盘上比基线新 ⇒ 差量里的「新增」可能盖掉更新过的文件，
     * 删除表也可能删掉后来才要用的东西；盘上比基线旧 ⇒ 中间那几版的变更整段缺失。
     * 两种情况的共同结果都是「盘上内容跟清单说的版本号不符」，而这种不符**不会报错**，
     * 只会让玩家在下次启动读 XML/CSV 时拿到对不上的内容 —— 几百 MiB 流量事小，
     * 一份自洽却与版本号撒谎的语料事大。
     *
     * [baseVersion] 小于 1（老清单没这个键，解析侧给 -1）恒为假：没基线就没资格谈差量。
     * 盘上从没装过数据（[NO_ASSETS_INSTALLED]）同样为假，只能拿全量。
     *
     * @param delta 差量三件套（缺任一件时解析侧给 null）
     * @param baseVersion 清单的 `assets-base-version`；无键 = -1
     * @param installedVersion 盘上 `data-version` 读数
     * @param diskTrusted 盘上那份数据**是否可信**（由 app 侧拿 `GameAssetInstaller.checkDataDigest`
     *   的体检结果传进来，core 不碰文件系统）。两种情况必须传 false：
     *   ① 盘上没有文件级摘要、或摘要与盘上不符 —— 那等于「版本号说 6，内容却不确定是不是 6」，
     *      而差量包是相对 6 的 diff，盖上去得到的是一份**自洽却与版本号不符**的语料；
     *   ② 同一个基线的差量刚失败过（落不了盘 / 配对不全） —— 不回落全量就会变成
     *      「每 12 小时白下一遍同一份坏差量」的死循环。
     *   缺省 true：老调用点与「没做体检」的调用方行为逐字不变。
     */
    fun deltaUsable(
        delta: Artifact?,
        baseVersion: Long,
        installedVersion: Long,
        diskTrusted: Boolean = true,
    ): Boolean = delta != null && baseVersion >= 1L && baseVersion == installedVersion && diskTrusted

    /**
     * 数据通道实际要下的**全量**件：新键 `assets-full-*` 齐全就用新键，否则回落到现有
     * `assets-*`（已经发出去的 0.2.32 包只认后者，所以线上两份声明必须同时存在、内容一致）。
     *
     * 判错的代价：这里回落到 null 会让「明明有数据包」变成「数据通道不成立」，玩家从此
     * 只收整包更新（≈743 MiB 起）。所以回落顺序是「新键 → 旧键」两级，任一级形状合法都放行。
     */
    fun fullDataArtifact(info: UpdateInfo): Artifact? = info.assetsFull ?: info.assets?.artifact

    /**
     * 数据通道最终要下的那件：差量可用 ⇒ 差量；否则 ⇒ 全量；两者都拿不出来 ⇒ null（通道不成立）。
     *
     * 这条就是契约 §1.2 要求「写进 core、别在 app 里重算」的判定：app 只拿结果去下载，
     * 不再自己比较 `assets-base-version`。判错方向的代价不对称 ——
     * 该回落却选了差量 = 一份内容不对的语料（见 [deltaUsable]）；
     * 该选差量却回落 = 白跑 ≈680 MiB（改一条 ERB 也要下整包，正是差量要治的病）。
     */
    fun dataArtifactFor(
        info: UpdateInfo,
        installedVersion: Long,
        diskTrusted: Boolean = true,
    ): Artifact? =
        if (deltaUsable(info.assetsDelta, info.assetsBaseVersion, installedVersion, diskTrusted)) {
            info.assetsDelta
        } else {
            fullDataArtifact(info)
        }

    /**
     * 块级差量（P2-13，U8）能不能用：**与 [deltaUsable] 同一条闸**，只是把「三件齐全」的那件
     * 换成块级那一件。
     *
     * 判式**故意不另写一份**，而是直接复用 [deltaUsable]，理由在那条函数的 KDoc 里写全了：
     * 块级包同样是「相对某一版语料的 diff」（它的补丁按**旧文件的块号**引用盘上那一份字节），
     * 所以基线不等、盘上没装过、盘上那份不可信这三种形态，坏法和文件级差量一模一样。
     * 两条通道共用清单里的同一个 `assets-base-version`：让发布者维护两套基线，
     * 迟早出现「块级按 6 拼、文件级按 5 删」的分裂形状。
     */
    fun blockDeltaUsable(
        blockDelta: Artifact?,
        baseVersion: Long,
        installedVersion: Long,
        diskTrusted: Boolean = true,
    ): Boolean = deltaUsable(blockDelta, baseVersion, installedVersion, diskTrusted)

    /**
     * 数据通道最终要下的那件，**优先块级**：块级可用 ⇒ 块级；否则 ⇒ [dataArtifactFor]
     * 的既有结论（文件级差量 ⇒ 全量 ⇒ null）。
     *
     * [blockDelta] 由调用方从 [BlockDeltaManifest.parse] 拿（`UpdateInfo` 里还没有这一件 ——
     * 那是清单解析侧的接线，见交付报告「需组长接线」）。传 null 时本函数与
     * [dataArtifactFor] **逐字同结果**，所以老调用点接过来也不会变行为。
     *
     * 回落次序写成「块级 → 文件级 → 全量」而不是反过来，是因为三档的**失败代价相同而字节
     * 递减**：块级包坏在设备上（基线对不上 / 拼完 sha 不符）时，解包侧抛错、app 侧把
     * `diskTrusted` 记成 false，下一拍 [deltaUsable] 与 [blockDeltaUsable] 一起关死，
     * 玩家拿到的就是全量包 —— 半份语料不可能出现（落盘原子性与整对回滚在解包侧，见
     * `GameAssetInstaller` 类头「配对事务」段）。
     */
    fun dataArtifactFor(
        info: UpdateInfo,
        blockDelta: Artifact?,
        installedVersion: Long,
        diskTrusted: Boolean = true,
    ): Artifact? =
        if (blockDeltaUsable(blockDelta, info.assetsBaseVersion, installedVersion, diskTrusted)) {
            blockDelta
        } else {
            dataArtifactFor(info, installedVersion, diskTrusted)
        }

    /**
     * 这次走的哪一档（给日志与遥测用，不参与任何决定）。
     *
     * 单独要这一拍是因为「差量静默失效」是这条通道最难发现的病：玩家每次都拿到全量包、
     * 每次都成功，只有流量账单知道。调用方把结论打进日志，出问题才看得见是哪一档没接上。
     */
    fun dataArtifactKind(
        info: UpdateInfo,
        blockDelta: Artifact?,
        installedVersion: Long,
        diskTrusted: Boolean = true,
    ): DataArtifactKind = when {
        blockDeltaUsable(blockDelta, info.assetsBaseVersion, installedVersion, diskTrusted) ->
            DataArtifactKind.BLOCK_DELTA
        deltaUsable(info.assetsDelta, info.assetsBaseVersion, installedVersion, diskTrusted) ->
            DataArtifactKind.FILE_DELTA
        fullDataArtifact(info) != null -> DataArtifactKind.FULL
        else -> DataArtifactKind.NONE
    }

    /**
     * 断点续传的 Range 起始偏移：`.part` 合法就返回它现有字节数，任何一点不合法都返回 **0**
     * （0 = 整段重来，不是「从 0 续」）。
     *
     * 两条信任判据（契约 §1.4），任一条不满足就重来：
     * - [ageMillis] 不得超过 [maxAgeMillis]（默认 [PART_MAX_AGE_MILLIS]，7 天）——同名产物会被
     *   覆盖重发，一周前的前缀属于哪一版没人知道；时钟回拨导致的负年龄按「新鲜」处理，
     *   时间只用来划界限，正确性由 sha 兜底；
     * - [partBytes] 不得超过 [declaredSize]——比声明还长的部分只可能是旧产物残留或写歪的偏移，
     *   拼上新字节得到的文件 **sha 必错**，而失败点在最末尾，玩家看到的是「每次都差一点就成功」。
     *
     * `partBytes == declaredSize` 返回 [partBytes]（偏移落在文件末尾）：字节数已经齐了，
     * 该由调用方去核 sha —— 核过就能直接用，核不过就该整段重来，而不是再拉一次全量。
     *
     * 调用方还有一条本函数管不到的硬约束：服务端**回 200（不支持 Range）时必须从头重写**，
     * 绝不能在旧 `.part` 后面追加 —— 那得到的正是上面说的那种缝合文件。
     */
    fun resumeOffset(
        partBytes: Long,
        declaredSize: Long,
        ageMillis: Long,
        maxAgeMillis: Long = PART_MAX_AGE_MILLIS,
    ): Long {
        if (partBytes <= 0L) return 0L
        if (declaredSize <= 0L) return 0L
        if (partBytes > declaredSize) return 0L
        if (ageMillis > maxAgeMillis) return 0L
        return partBytes
    }

    /**
     * 第 [attempt] 次失败之后再等多久（毫秒）；返回 null = 档位走完，**换下一条镜像或收手**。
     *
     * 两端都得判准：
     * - 返回 `null` 太早（比如只有 1 档）：国内镜像的抖动常常是几十秒级的，一次抖动就放弃
     *   等于让玩家手动重试三遍；
     * - 返回非 null 太多：对着一个已经失联的源把玩家 Wi-Fi 当轮询打，且整包 APK 的下载
     *   从头再来一次就是几百 MiB —— 所以「换镜像」的权力在 [attempt] 之外（调用方按候选序推进）。
     *
     * 负数与超档一视同仁返回 null：调用方的循环不该靠「传了个奇怪的值」继续跑。
     */
    fun retryBackoffMillis(attempt: Int): Long? =
        if (attempt in RETRY_BACKOFF_MILLIS.indices) RETRY_BACKOFF_MILLIS[attempt] else null

    /**
     * 从本机哪个 versionCode 起**只接受签名清单**（P0-2 的兼容切换点）。
     *
     * 这是「兼容切换点」而不是「服务器开关」：判据在本机 versionCode 上，装 18 及以上版本的包
     * 起强制签名（未签名清单 ⇒ 视为不可用、换下一条镜像）；当前已发出去的 17 及以下老客户端
     * 维持 UNSIGNED 迁移放行。初值 18 = 下一个要发的 versionCode（0.2.42；2026-11 收口时基线
     * `app/build.gradle.kts` 的 versionCode = 17）。
     *
     * 为什么切在「下一个版本」而不是立刻：已发出去的清单没有签名，若本机版本一升就拒，
     * 老镜像上那份未签名清单会让整条更新通道对玩家哑掉；发布侧从 0.2.42 起出签名清单，
     * 装 18 的客户端读到的都是签名过的源。
     */
    const val SIGNED_MANIFEST_REQUIRED_FROM_APK_CODE: Long = 18L

    /**
     * 清单本体验签（契约 CONTRACT-UPD1-3LANE §2 U1 病①）：三态判定，算法与切割都在
     * [ManifestSignature]，这里只是把「判错了很贵」的那条决定挂进纯判定层 ——
     * app 壳（UpdateManager.checkNow）只按结论分派，不自己碰 `java.security`：
     *
     * - **VERIFIED**：签名对得上，这份清单的字节是发布方签的那份；
     * - **INVALID**：带了签名但验不过（字节被改、签名行之后夹带内容、有签名声明却缺签名行、
     *   算法不认识、key id 不在包里）—— 按该镜像「清单不可用」走下一条镜像，**绝不**回落成
     *   未签名放行（签了名还放行等于告诉攻击者「把签名行删了就行」的反向通道：反正验不过也一样过）；
     * - **UNSIGNED**：清单既没有 `manifest-signature` 行也没有签名声明 —— 旧格式。放行与否
     *   还要看 [signedRequired]（见 [SIGNED_MANIFEST_REQUIRED_FROM_APK_CODE]）：false 时放行并
     *   由调用方落一条 warning（迁移口径：先发的清单没签名不是攻击信号，发版人该被提醒）；
     *   true 时按 [ManifestSignature.Verdict.INVALID] 处理（该版本起只接受签名清单）。
     *
     * @param signedRequired 本机是否已到「只接受签名清单」的版本档；调用方按
     *   `currentVersionCode >= SIGNED_MANIFEST_REQUIRED_FROM_APK_CODE` 决定
     */
    fun verifyManifestPolicy(
        manifestText: String,
        signedRequired: Boolean = false,
    ): ManifestSignature.Verdict {
        val verdict = ManifestSignature.verify(manifestText)
        if (signedRequired && verdict == ManifestSignature.Verdict.UNSIGNED) {
            return ManifestSignature.Verdict.INVALID
        }
        return verdict
    }

    /**
     * 强制更新判定（契约 §2 U1 病②）：本机 versionCode 低于清单的 `min-supported-version`
     * 时，这次的整包提醒**必须**送达 —— 不可跳过、不可「以后都不」。
     *
     * 两个入口宁可不拦也不误拦：
     * - [currentVersionCode] ≤ 0（读不出自己的版本，某些 ROM 的 packageInfo 形状）→ false；
     * - [minSupportedVersion] ≤ 0（清单没写或坏值，解析侧给 -1）→ false。
     *
     * 判「小于」不判「不等于」：minSupported 语义是「低于它的停止支持」，等于它的客户端
     * 仍在支持档内，不弹强制。
     */
    fun isForceUpdateRequired(currentVersionCode: Long, minSupportedVersion: Long): Boolean =
        currentVersionCode > 0L && minSupportedVersion > 0L && currentVersionCode < minSupportedVersion

    /**
     * 该版本是否已被玩家跳过：`skipped <= 0`（没跳过过）或不等（跳过的是别的版本）都**不抑制**。
     *
     * 只对**同一个 versionCode** 生效是刻意的（契约 §1.5）：「跳过此版本」是玩家对这一个版本的
     * 表态，不是对更新通道的表态。判成「抑制所有后续版本」的后果最恶劣 —— 玩家在一个
     * 有致命 bug 的版本上点过一次「跳过」，之后所有修复版都永远提醒不到他，
     * 而侧载分发没有应用商店的强制更新兜底。
     *
     * @param versionCode 清单声明的 `apk-version-code`
     * @param skippedVersionCode `UiPrefs` 里的 `update_skipped_version_code`；没写过传 0 或 -1
     */
    fun isVersionSuppressed(versionCode: Long, skippedVersionCode: Long): Boolean =
        skippedVersionCode > 0L && skippedVersionCode == versionCode

    /**
     * 更新面是否被配置禁止 —— 原版 `ForbidUpdateCheck` 的消费点。
     *
     * 原版语义（证据）：配置项声明 `reference/emuera-cs/MinorShift.Emuera.Runtime.Config/ConfigCode.cs:99`
     * （`ForbidUpdateCheck = 163`），读进静态字段在同目录 `Config.cs:231`（属性）与 `Config.cs:382`
     * （从配置表取值）；`UPDATECHECK` 语句在
     * `reference/emuera-cs/MinorShift.Emuera.GameProc.Function/FunctionIdentifier.cs:2767-2771` 的
     * **第一件事**就是查它，为真时直接 `RESULT = 4` 返回 —— 在「网络是否可用」之前、
     * 在任何一次取 URL 之前。所以这条判定的正确形状是「不发任何请求」，而不是「发了再说」。
     *
     * 单列一个函数而不是让调用方直接读布尔值，是为了给「更新面还有别的禁令」留出加判据的地方，
     * 也让 app 层与 ERB 语句（`UPDATECHECK`）共用同一个口径。
     */
    fun forbiddenByConfig(forbidUpdateCheck: Boolean): Boolean = forbidUpdateCheck

    // ------------------------------------------------------------------
    // 灰度发布（P1-5）、强制档优先级、镜像健康降位（P1-7）
    // ------------------------------------------------------------------

    /**
     * 灰度分桶判定：百分比 [percentage]（0..100）选中 bucket 落在 0 到 percentage-1 的设备。
     *
     * bucket 由 app 侧用 SecureRandom 取一次并持久化在 UiPrefs（core 禁随机数、禁 Android API），
     * 同一设备对同一版本恒定；0 号桶在 1% 灰度里就中、在 0% 里也不中，于是发布者把百分比
     * 从 1 调到 50 时，原来那 1% 一定还在名单里（灰度只扩不换人）。
     *
     * 坏值一律往「不误挡」方向回落：百分比越界按 [FULL_ROLLOUT_PERCENTAGE] 全量，
     * bucket 越界按 0 号桶（最不容易被挡）。判错的代价不对称 —— 误挡 = 修了致命 bug 的新版永远
     * 到不了玩家，多推 = 只是多一次下载提示，所以两个方向都取「宁可多推」。
     */
    fun rolloutSelected(percentage: Int, bucket: Int): Boolean {
        val effective = if (percentage in 0..FULL_ROLLOUT_PERCENTAGE) percentage else FULL_ROLLOUT_PERCENTAGE
        val b = if (bucket in 0 until ROLLOUT_BUCKETS) bucket else 0
        return b < effective
    }

    /**
     * 这次更新要不要推给这台设备：**强制档优先于灰度**。
     *
     * 命中 [isForceUpdateRequired] ⇒ 无视灰度百分比直接放行（致命修复必须送达，不能被
     * 10% 灰度挡在门外）；否则走 [rolloutSelected]。这条组合判定留在 core，
     * app 侧只把四个读数喂进来，不许在壳层再拼一遍优先级。
     *
     * 强制档判定的**唯一**入口是 [isForceUpdateRequired]（U1 落地的口径）。这里曾并存过第二个
     * 同形函数，少的是「读不出本机版本就不强制」那道闸 —— 于是 packageInfo 取不到 versionCode
     * 的 ROM（[currentVersionCode] 传 ≤0）会被判成「命中强更」，两份判定在同一台机器上各说各话。
     * 加强更的判据请改 [isForceUpdateRequired]，不要另立。
     *
     * @param minSupportedVersion 清单声明的 min-supported-version；缺席或坏值传 ≤0
     */
    fun updateSelected(
        rolloutPercentage: Int,
        bucket: Int,
        currentVersionCode: Long,
        minSupportedVersion: Long,
    ): Boolean = isForceUpdateRequired(currentVersionCode, minSupportedVersion) ||
        rolloutSelected(rolloutPercentage, bucket)

    /**
     * 这一轮这条内置源算不算「还活着」（P1-7 健康度的唯一判据源；取不到文本那种失败由调用方
     * 自己按失联记，不进本函数）。
     *
     * 只有验签判死算失联：反复被换、或签名行被改坏的源就该降位，别让每轮检查白吃它一次。
     * 清单解析被拒**不算**失联 —— 健康度衡量的是「这条镜像还能不能用」，字节拿到了、连接是好的，
     * 内容坏了会原话进 problems 给玩家和发版人看；[ManifestSignature.Verdict.UNSIGNED]
     * （老格式没签名）也不算，它按放行处理并另落一条 warning。
     */
    fun mirrorFetchHealthy(verdict: ManifestSignature.Verdict): Boolean =
        verdict != ManifestSignature.Verdict.INVALID

    /** 某条内置源近期是否失联：连续失败够多，或成功过但太久没成功。 */
    private fun isMirrorUnhealthy(
        health: MirrorHealth,
        nowMillis: Long,
        failureThreshold: Int,
        staleMillis: Long,
    ): Boolean {
        if (failureThreshold >= 1 && health.consecutiveFailures >= failureThreshold) return true
        // 从没成功过（0）不给按「久未成功」降位：它还没被证明失联，先给它机会。
        if (health.lastSuccessMillis <= 0L) return false
        // 时钟回拨 ⇒ 间隔为负 ⇒ 按新鲜处理（与 isCheckDue 同口径，正确性由失败计数兜底）。
        return nowMillis - health.lastSuccessMillis > staleMillis
    }

    /**
     * 带健康度的镜像候选序：近期失联的**内置源降位但不删除**，其余口径全归 [mirrorOrder]。
     *
     * [health] 以「归一后的基址」为键（与 [mirrorOrder] 的归一结果同形），由 app 侧在
     * 取清单/下载成败后写进 UiPrefs。内置源按 [isMirrorUnhealthy] 分成「健康组 / 降位组」，
     * 两组内部都保持内置表相对顺序，拼成新的内置序再交给 [mirrorOrder] —— 于是三条既有
     * 不变量一字不动：归一去重、[MAX_MIRRORS] 截断、清单声明的源不得挤到内置源之前。
     * 降位不是删除：失联的源仍在候选表里，网络恢复后成功一次即清零失败数、自动回到原位。
     *
     * @param failureThreshold 连续失败达到多少判失联（缺省 [MIRROR_DEMOTE_FAILURES]）
     * @param staleMillis 最后成功距今超过多久判失联（缺省 [MIRROR_STALE_AFTER_MILLIS]）
     */
    fun mirrorOrderWithHealth(
        builtIn: List<String>,
        persisted: List<String> = emptyList(),
        declared: List<String> = emptyList(),
        health: Map<String, MirrorHealth> = emptyMap(),
        nowMillis: Long = 0L,
        failureThreshold: Int = MIRROR_DEMOTE_FAILURES,
        staleMillis: Long = MIRROR_STALE_AFTER_MILLIS,
    ): List<String> {
        val healthy = ArrayList<String>()
        val demoted = ArrayList<String>()
        for (raw in builtIn) {
            val base = normalizeMirror(raw) ?: continue
            val state = health[base]
            if (state != null && isMirrorUnhealthy(state, nowMillis, failureThreshold, staleMillis)) {
                demoted += base
            } else {
                healthy += base
            }
        }
        return mirrorOrder(
            builtIn = healthy + demoted,
            persisted = persisted,
            declared = declared,
        )
    }
}

/**
 * [UpdatePolicy.dataArtifactKind] 的读数：这一版数据通道实际会下哪一档。
 *
 * 只有四档，且互斥 —— 与 [UpdatePolicy.dataArtifactFor] 的回落次序一一对应，
 * 两条判定必须同向，否则日志说「走了块级」、下载的是全量包，排查时第一眼就被带偏。
 */
enum class DataArtifactKind {
    /** 块级差量包（清单带 `assets-blockdelta-*` 且基线、盘上版本、盘的信任度三条全过）。 */
    BLOCK_DELTA,

    /** 文件级差量包（现行通道：只发变化的整条文件 + 删除表）。 */
    FILE_DELTA,

    /** 全量数据包（新键 `assets-full-*`，缺席时回落旧键 `assets-*`）。 */
    FULL,

    /** 数据通道不成立（清单里一件数据包都没有）：只可能有整包更新。 */
    NONE,
}

/**
 * 一条内置源的镜像健康度（core 层纯数据，app 侧负责持久化与写回）。
 *
 * @param consecutiveFailures 近期连续失败数（成功一次清零）
 * @param lastSuccessMillis 最后一次成功时刻（毫秒，0 = 从没成功过）
 */
data class MirrorHealth(
    val consecutiveFailures: Int = 0,
    val lastSuccessMillis: Long = 0L,
)
