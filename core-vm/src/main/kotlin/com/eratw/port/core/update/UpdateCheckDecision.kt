package com.eratw.port.core.update

/**
 * `UPDATECHECK` 语句的**决策表** —— 原版 `UPDATECHECK_Instruction.DoInstruction`
 * （`reference/emuera-cs/MinorShift.Emuera.GameProc.Function/FunctionIdentifier.cs:2757-2839`）
 * 的 0–5 六个 RESULT 读数逐条下沉成纯函数。
 *
 * ## 判序（原版逐行，顺序就是语义）
 *
 * | 次序 | 判定 | 结果 | C# 行号 |
 * |---|---|---|---|
 * | 1 | `Config.ForbidUpdateCheck` | RESULT=4，**不发任何请求** | `:2767-2771` |
 * | 2 | `NetworkInterface.GetIsNetworkAvailable()` 为假 | RESULT=5 | `:2772-2776` |
 * | 3 | `GameBaseData.UpdateCheckURL` 为 null 或空串 | RESULT=3 | `:2777-2783` |
 * | 4 | 取回流的前两行（第一行=版本名、第二行=下载地址），任意为 null 或空串 | RESULT=3 | `:2786-2799` |
 * | 5 | 第一行 **等于** 本游戏 `VersionName` | RESULT=0 | `:2800`+`:2820-2825` |
 * | 6 | 不等 ⇒ 弹询问；玩家选「是」 | RESULT=2 并打开链接 | `:2802-2812` |
 * | 6 | 同上，玩家选「否」（含关框） | RESULT=1 | `:2813-2818` |
 * | — | 以上任何一步抛异常 | RESULT=3 | `:2827-2832`、`:2834-2837` |
 *
 * 三条容易被「顺手改好」的原样细节：
 * - **第 3 步在第 2 步之后**：URL 没配 + 没网 ⇒ RESULT=**5** 而不是 3（`:2772` 排在 `:2779` 之前）。
 * - **第 3 步的比较是 `== null || == ""`**（`:2779`），不 trim：URL 写成一个空格在原版算「有 URL」，
 *   会带着这个空格去 `WebClient.OpenRead`（`:2786`），失败后落 3。本表同样不 trim。
 * - **第 4/5 步同理不 trim**：`ReadLine` 的返回原样进比较（`:2790-2800`）。
 *
 * ## 与原版唯一的口径差（超时，不是「修 bug」）
 *
 * 原版是桌面单进程：`WebClient.OpenRead`（`:2786`）与 `MessageBox.Show`（`:2802`）都是**无上限**
 * 同步阻塞，玩家不点框游戏就停在那儿。移植侧不能把 VM 线程这么停死，于是宿主把两件事都设了
 * 兜底：**没问到**（超时 / 框弹不出来 / 投递失败）在本表落成 `userAccepted = null` ⇒
 * RESULT=3。这不是新语义，而是原版那条 catch（`:2827-2832`：`MessageBox.Show` 抛异常时
 * 同样把 RESULT 写成 3）的等价形状 —— 异常与「拿不到答案」在原版本来就是同一个出口。
 *
 * 另一个同源细节：**打开链接失败 ⇒ 3 而不是 2**。原版在选「是」后先写 `RESULT = 2`
 * （`:2804`）再 `Process.Start`（`:2805-2809`），而 `RESULT` 是活字段、整段在 try 里
 * （`:2788` 起），`Process.Start` 抛异常时 catch 把它**改写回 3**（`:2829`）。
 * 本表不在 `decide` 里表达这一条（它只判「该不该开」），由调用方在打开动作失败时
 * 落成 [RESULT_ERROR]，见 [UpdateCheckHost.openDownloadLink]。
 *
 * 纯 JVM：不碰网络、不碰文件系统、不碰 Android API（AGENTS.md 铁律 4）。
 */
object UpdateCheckDecision {

    /** 已是最新版本（原版 `:2822`）。 */
    const val RESULT_UP_TO_DATE: Long = 0L

    /** 有新版本，玩家选「否」（原版 `:2815`）。 */
    const val RESULT_DECLINED: Long = 1L

    /** 有新版本，玩家选「是」且已把链接交给系统（原版 `:2804`）。 */
    const val RESULT_OPENED: Long = 2L

    /** 无法检查：URL 没配 / 取回内容不对 / 任何异常（原版 `:2781`、`:2794`、`:2798`、`:2829`、`:2836`）。 */
    const val RESULT_ERROR: Long = 3L

    /** 配置禁止 UPDATECHECK（原版 `:2769`，`Config.ForbidUpdateCheck` = 配置项 163）。 */
    const val RESULT_FORBIDDEN: Long = 4L

    /** 没有可用网络（原版 `:2774`）。 */
    const val RESULT_NO_NETWORK: Long = 5L

    /**
     * 从取回的流里读到的**前两行**（原版 `streamReader.ReadLine()` 两次，`:2790-2791`）。
     *
     * @property versionName 第一行 = 远端版本名；null = 流里没有第一行（EOF）
     * @property downloadUrl 第二行 = 下载地址；null = 流里没有第二行
     */
    class RemoteInfo(val versionName: String?, val downloadUrl: String?)

    /** [precheck] 的结果：要么直接有答案（且**一个请求都不发**），要么该去取 URL。 */
    sealed interface Precheck {

        /** 前三道闸都放行：可以去取 [updateCheckUrl] 的内容。 */
        class Fetch(val updateCheckUrl: String) : Precheck

        /** 已经定案（4 / 5 / 3），不许再发请求。 */
        class Settled(val result: Long) : Precheck
    }

    /** [afterFetch] 的结果：要么直接有答案，要么需要问玩家。 */
    sealed interface FetchOutcome {

        /** 已经定案（3 = 内容不对，0 = 版本相同）。 */
        class Settled(val result: Long) : FetchOutcome

        /**
         * 远端版本与本游戏不同 ⇒ 要问玩家。
         *
         * @property newVersionName 远端第一行（原版弹框的 `{0}`）
         * @property downloadUrl 远端第二行（原版弹框的 `{1}`，选「是」后交给系统打开）
         */
        class Ask(val newVersionName: String, val downloadUrl: String) : FetchOutcome
    }

    /**
     * 前三道纯判定（原版 `:2767-2783`）：配置禁令 → 网络可用性 → URL 是否为空。
     *
     * 单独拆出来只有一个理由：**原版在这三步任何一步失败时都没有发过请求**
     * （`WebClient` 对象在 `:2778` 建好但 `OpenRead` 在 `:2786`，即 URL 判空之后），
     * 编排方必须先问这一步再决定要不要取流。[decide] 是完整表，本函数是它的**前缀**。
     */
    fun precheck(
        forbidUpdateCheck: Boolean,
        networkAvailable: Boolean,
        updateCheckUrl: String?,
    ): Precheck {
        if (UpdatePolicy.forbiddenByConfig(forbidUpdateCheck)) return Precheck.Settled(RESULT_FORBIDDEN)
        if (!networkAvailable) return Precheck.Settled(RESULT_NO_NETWORK)
        // 原版 `updateCheckURL == null || updateCheckURL == ""`（:2779）—— 不 trim、不判形状。
        if (updateCheckUrl == null || updateCheckUrl.isEmpty()) return Precheck.Settled(RESULT_ERROR)
        return Precheck.Fetch(updateCheckUrl)
    }

    /**
     * 取回内容之后的判定（原版 `:2790-2825`）：两行齐不齐 → 版本名同不同。
     *
     * @param remote 前两行；null = 取流本身失败（原版 `OpenRead` 抛出 → `:2836` 的 catch 落 3）
     * @param localVersionName 本游戏 `GameBaseData.VersionName`（`GameBase.cs:186`，缺省空串）
     */
    fun afterFetch(remote: RemoteInfo?, localVersionName: String?): FetchOutcome {
        if (remote == null) return FetchOutcome.Settled(RESULT_ERROR)
        val line1 = remote.versionName
        val line2 = remote.downloadUrl
        if (line1 == null || line1.isEmpty()) return FetchOutcome.Settled(RESULT_ERROR) // :2792-2795
        if (line2 == null || line2.isEmpty()) return FetchOutcome.Settled(RESULT_ERROR) // :2796-2799
        // 原版 `text != GlobalStatic.GameBaseData.VersionName`（:2800）：.NET 字符串 `!=` 是**序值比较**，
        // 不比大小写折叠、不 trim；本地 VersionName 缺省是空串（GameBase.cs:33），不是 null。
        if (line1 == (localVersionName ?: "")) return FetchOutcome.Settled(RESULT_UP_TO_DATE)
        return FetchOutcome.Ask(line1, line2)
    }

    /**
     * 完整决策表（原版 `:2767-2837` 的一次性求值）。
     *
     * [precheck] / [afterFetch] 是本函数的**分阶段投影**，两者必须与它逐条同解 ——
     * 编排方（`core/save/FileSystemOps.updateCheck`）走分阶段（因为中间夹着一次取流），
     * 单测走这条一次性表（把六个读数逐个钉死）。
     *
     * @param forbidUpdateCheck 生效配置 `ForbidUpdateCheck`（ConfigKey 163，`ConfigCode.cs:99`）。
     *   判 `UpdatePolicy.forbiddenByConfig`（原版位置最前：为真时**不发任何请求**）。
     * @param networkAvailable 原版 `NetworkInterface.GetIsNetworkAvailable()`（`:2772`）。
     * @param updateCheckUrl `GameBaseData.UpdateCheckURL`（`GameBase.cs:183` ← `バージョン情報URL`）。
     * @param remote 取回的前两行；null = 取流失败或中途异常（`WebClient.OpenRead` / `ReadLine` 抛出）。
     * @param localVersionName `GameBaseData.VersionName`（`GameBase.cs:186` ← `バージョン名`）。
     * @param userAccepted 玩家在询问框的选择：true=是、false=否或关框（原版 `DialogResult.Yes`
     *   是唯一 true 的分支，`MessageBox.Show` 的默认按钮是 Button2 = 「否」）；
     *   **null = 没问到**（超时/弹不出来），按原版异常支落 [RESULT_ERROR]，见类 KDoc。
     *   只在「需要询问」那一格被消费，其余格子里它毫无作用。
     */
    fun decide(
        forbidUpdateCheck: Boolean,
        networkAvailable: Boolean,
        updateCheckUrl: String?,
        remote: RemoteInfo?,
        localVersionName: String?,
        userAccepted: Boolean?,
    ): Long = when (val pre = precheck(forbidUpdateCheck, networkAvailable, updateCheckUrl)) {
        is Precheck.Settled -> pre.result
        is Precheck.Fetch -> when (val post = afterFetch(remote, localVersionName)) {
            is FetchOutcome.Settled -> post.result
            is FetchOutcome.Ask -> when (userAccepted) {
                null -> RESULT_ERROR
                true -> RESULT_OPENED
                false -> RESULT_DECLINED
            }
        }
    }

    /**
     * 询问框标题（原版 `Lang.MessageBox.UpdateCheck`）。
     *
     * spec: `reference/emuera-cs/MinorShift.Emuera.Runtime.Utils.EvilMask/Lang.cs:4082`
     * 出厂日文「アップデートチェック」；本作显示语言走中文串
     * （`MinorShift.Emuera.Properties.lang.emuera-zhs.xml:962`「更新检查」），
     * 与存档层错误文案同源（`FileSystemOps.MES_LOAD_CORRUPTED` 等取 zhs 的同一纪律）。
     */
    const val ASK_TITLE: String = "更新检查"

    /**
     * 询问框正文（原版 `Lang.MessageBox.NewVersionAvailable` 的 zhs 串，`{0}`=远端版本名、
     * `{1}`=远端第二行的下载地址；spec: Lang.cs:4086 的日文原文 + lang.emuera-zhs.xml:963-964）。
     *
     * 原版用 `string.Format` 填这两个槽（`FunctionIdentifier.cs:2802`），第二实参 `text2`
     * 只在 `{1}` 出现，第一实参 `text` 只在 `{0}` 出现。
     */
    fun askMessage(newVersionName: String, downloadUrl: String): String =
        "新版本（$newVersionName）已经发布。打开URL吗？\n链接地址:$downloadUrl"
}

/**
 * `UPDATECHECK` 需要的**宿主能力面**：网络可用性、取回远端前两行、问玩家、打开链接。
 *
 * 为什么是一条注入的缝而不是 core 自己做：这四件事全在 core 的能力边界之外
 * （AGENTS.md 铁律 4 —— core 层纯 JVM，禁 Android API、禁网络与弹框）。原版把它们写在同一条
 * 语句里（`WebClient` `:2786`、`MessageBox.Show` `:2802`、`Process.Start` `:2805-2809`），
 * 移植侧由宿主装配（生产实现见 `platform/update/UpdateCheckTransport.kt` 与
 * `app/.../GameHost.kt`），core 只接受**已经取回的两行文本**。
 *
 * 线程口径：`core/save/FileSystemOps.updateCheck` 在 **VM 线程**同步调用本接口的全部方法，
 * 因此实现方有两条硬约束：
 * - **不许无上限阻塞**：原版 `OpenRead` / `MessageBox.Show` 是桌面级无超时阻塞，
 *   移植侧必须自带超时；拿不到答案时 [askOpenLink] 返回 null（决策表落 3），
 *   取流失败返回 null（同样落 3），**不许抛出去把 VM 线程打死**。
 * - **不许在 UI 线程调用**（会自锁）：调用点是 VM 线程，实现方把作答投到 UI 线程等。
 */
interface UpdateCheckHost {

    /**
     * 生效配置的 `ForbidUpdateCheck`（原版 `Config.ForbidUpdateCheck`，`:2767` 的第一道闸）。
     *
     * 逐次现读：原版读的是静态字段，而本移植的配置读数是装配期条目表 + 热更写入
     * （`core/config/EffectiveConfig.reloadHot`），实现方应现读条目而不是缓存构造期的快照。
     */
    fun forbidUpdateCheck(): Boolean

    /** 网络是否可用（原版 `NetworkInterface.GetIsNetworkAvailable()`，`:2772`）。 */
    fun isNetworkAvailable(): Boolean

    /**
     * 取回 [url] 的内容并交出**前两行**（原版 `:2786-2791`）。
     *
     * 返回 null = 取不到（连不上 / 非 2xx / 读流异常）—— 对应原版的两个 catch 支（`:2829`、`:2836`），
     * 都落 RESULT=3；调用方不会看到异常。
     */
    fun fetchVersionInfo(url: String): UpdateCheckDecision.RemoteInfo?

    /**
     * 问玩家「要不要打开链接」（原版 `MessageBox.Show(..., YesNo, ...)`，`:2802`）。
     *
     * @return true=「是」、false=「否」或玩家关框（原版只有 `DialogResult.Yes` 算 true）、
     *   **null=没问到**（超时 / Activity 已销毁 / 框弹不出来）⇒ 决策表落 RESULT=3
     */
    fun askOpenLink(title: String, message: String): Boolean?

    /**
     * 把下载地址交给系统打开（原版 `Process.Start` + `UseShellExecute = true`，`:2805-2809`）。
     *
     * 只在玩家选「是」之后被调用。实现方抛异常 = 打开失败，调用方按原版的 catch 支
     * 把 RESULT 从 2 改写成 3（见 [UpdateCheckDecision] 类 KDoc 的最后一段）。
     */
    fun openDownloadLink(url: String)
}
