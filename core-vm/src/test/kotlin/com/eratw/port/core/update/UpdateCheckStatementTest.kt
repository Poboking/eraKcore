package com.eratw.port.core.update

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveFileIo
import com.eratw.port.core.sem.SemTestEnv
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.instr
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `UPDATECHECK` 语句接真（CONTRACT-UPDATE §4 U-F）：决策表逐条 + `FileSystemOps` 编排 +
 * 语句分派面「只写 RESULT、不抛」。
 *
 * ## 语义证据（逐条，全出自 `UPDATECHECK_Instruction.DoInstruction`）
 *
 * `reference/emuera-cs/MinorShift.Emuera.GameProc.Function/FunctionIdentifier.cs:2757-2839`：
 * - `:2767-2771` `Config.ForbidUpdateCheck` ⇒ RESULT=4（**方法体第一件事**，此时连 `WebClient` 都还没 new）；
 * - `:2772-2776` `!NetworkInterface.GetIsNetworkAvailable()` ⇒ RESULT=5；
 * - `:2777-2783` `GameBaseData.UpdateCheckURL` 为 null 或空串 ⇒ RESULT=3（`OpenRead` 在 `:2786`，故这一支不发请求）；
 * - `:2786-2791` `WebClient.OpenRead` + `StreamReader.ReadLine()` 两次 = 远端版本名、下载地址；
 * - `:2792-2799` 任一行 null 或空串 ⇒ RESULT=3；
 * - `:2800`+`:2820-2825` 第一行 == `VersionName` ⇒ RESULT=0；
 * - `:2802-2812` 不等 ⇒ `MessageBox.Show(YesNo)`，选「是」⇒ RESULT=2 并 `Process.Start` 打开第二行；
 * - `:2813-2818` 选「否」⇒ RESULT=1；
 * - `:2827-2837` 任何异常 ⇒ RESULT=3（`Process.Start` 抛出时**覆盖已写过的 2**）。
 *
 * URL 与版本名的来源：`reference/emuera-cs/MinorShift.Emuera.Runtime.Script.Data/GameBase.cs:182-187`
 * （`バージョン情報URL` → `UpdateCheckURL`、`バージョン名` → `VersionName`；两者的出厂值都是
 * 空串，`GameBase.cs:31` / `:33`）。
 *
 * ## 本语料的实况（契约 §0 的第 7 件事为什么会「报错」）
 *
 * `eratw-画蛇添足版eratw4.980/CSV/GameBase.csv` 实测**只有 7 行**（コード / バージョン / タイトル /
 * 製作者名 / 製作年 / バージョン違い認める / 追加情報），**没有** `バージョン情報URL` 与 `バージョン名`
 * ⇒ 装载侧 `GameBaseLoader` 交出空串 ⇒ 原版在这一支落 **RESULT=3**。所以真机上 `UPDATECHECK`
 * 的正确行为是 3，不是 CodeEE（用例 [noUrlYieldsResultThreeNotCodeEE] 钉住这一条）。
 */
class UpdateCheckStatementTest {

    // ------------------------------------------------------------------
    // 假体
    // ------------------------------------------------------------------

    /** 记录调用的宿主假体：四件能力都可控，且能主动抛（原版的异常支就是这么进的 catch）。 */
    private class FakeHost(
        var forbidden: Boolean = false,
        var network: Boolean = true,
        var remote: UpdateCheckDecision.RemoteInfo? = null,
        var accepted: Boolean? = false,
        var fetchThrows: Boolean = false,
        var askThrows: Boolean = false,
        var openThrows: Boolean = false,
    ) : UpdateCheckHost {
        val calls = mutableListOf<String>()
        var openedUrl: String? = null

        override fun forbidUpdateCheck(): Boolean {
            calls.add("forbid")
            return forbidden
        }

        override fun isNetworkAvailable(): Boolean {
            calls.add("network")
            return network
        }

        override fun fetchVersionInfo(url: String): UpdateCheckDecision.RemoteInfo? {
            calls.add("fetch($url)")
            if (fetchThrows) throw java.io.IOException("连不上")
            return remote
        }

        override fun askOpenLink(title: String, message: String): Boolean? {
            calls.add("ask($title|$message)")
            if (askThrows) throw IllegalStateException("框弹不出来")
            return accepted
        }

        override fun openDownloadLink(url: String) {
            calls.add("open($url)")
            openedUrl = url
            if (openThrows) throw IllegalStateException("没有能打开链接的应用")
        }
    }

    /** 只够 `FileSystemOps` 构造的存档 IO（本路不碰存档文件）。 */
    private class NoIo : SaveFileIo {
        override fun exists(path: String): Boolean = false
        override fun read(path: String): ByteArray? = null
        override fun write(path: String, bytes: ByteArray): Boolean = true
        override fun delete(path: String): Boolean = true
    }

    private fun ops(
        host: UpdateCheckHost?,
        url: String = "",
        versionName: String = "",
    ): FileSystemOps {
        val store = VariableStore(VariableRegistry(gameBase = GameBaseData(url = url, versionName = versionName)))
        return FileSystemOps(store, NoIo(), SaveConfig.DEFAULT, updateCheckHost = host)
    }

    private fun remote(first: String?, second: String?) = UpdateCheckDecision.RemoteInfo(first, second)

    // ------------------------------------------------------------------
    // 一、纯决策表：RESULT 0-5 逐条钉
    // ------------------------------------------------------------------

    @Test
    fun forbiddenGivesFourAndBeatsEverythingElse() {
        // 原版 :2767-2771 是方法体第一段 ⇒ 后三道闸无论怎么写都不参与。
        assertEquals(4L, UpdateCheckDecision.decide(true, false, null, null, "", true))
        assertEquals(
            4L,
            UpdateCheckDecision.decide(
                forbidUpdateCheck = true,
                networkAvailable = true,
                updateCheckUrl = "https://eratw-android.asia/updates/version.txt",
                remote = remote("新版本", "https://example.invalid/dl"),
                localVersionName = "旧版本",
                userAccepted = true,
            ),
        )
    }

    @Test
    fun noNetworkGivesFiveEvenWhenUrlMissing() {
        // :2772 排在 :2779 之前 ⇒ 「没网 + 没配 URL」是 5 不是 3（顺序就是语义）。
        assertEquals(5L, UpdateCheckDecision.decide(false, false, "", null, "", null))
        assertEquals(5L, UpdateCheckDecision.decide(false, false, null, remote("新", "https://x"), "旧", true))
    }

    @Test
    fun blankUrlGivesThree() {
        assertEquals(3L, UpdateCheckDecision.decide(false, true, "", remote("新", "https://x"), "旧", true))
        assertEquals(3L, UpdateCheckDecision.decide(false, true, null, remote("新", "https://x"), "旧", true))
    }

    @Test
    fun urlIsNotTrimmed_officialQuirk() {
        // 原版判的是 `== null || == ""`（:2779），一个空格算「有 URL」⇒ 会去发请求，
        // 失败后由 catch 落 3；本表同形：precheck 放行、取回 null 才落 3。
        val pre = UpdateCheckDecision.precheck(false, true, " ")
        assertTrue(pre is UpdateCheckDecision.Precheck.Fetch, "空格 URL 在原版算非空，不该判成缺席")
        assertEquals(3L, UpdateCheckDecision.decide(false, true, " ", null, "", null))
    }

    @Test
    fun fetchFailureGivesThree() {
        // :2786 的 OpenRead 抛出 → :2834-2837 的 catch ⇒ 3。
        assertEquals(3L, UpdateCheckDecision.decide(false, true, "https://x", null, "旧", true))
    }

    @Test
    fun incompleteRemoteLinesGiveThree() {
        // :2792-2799：两行任意为 null（EOF）或空串都算取回内容不对。
        assertEquals(3L, UpdateCheckDecision.decide(false, true, "u", remote(null, "l"), "旧", true))
        assertEquals(3L, UpdateCheckDecision.decide(false, true, "u", remote("", "l"), "旧", true))
        assertEquals(3L, UpdateCheckDecision.decide(false, true, "u", remote("v", null), "旧", true))
        assertEquals(3L, UpdateCheckDecision.decide(false, true, "u", remote("v", ""), "旧", true))
    }

    @Test
    fun sameVersionNameGivesZeroAndAsksNobody() {
        // :2800 + :2820-2825：相等直接 0，玩家表态与远端第二行都不再被消费。
        assertEquals(0L, UpdateCheckDecision.RESULT_UP_TO_DATE)
        assertEquals(
            0L,
            UpdateCheckDecision.decide(false, true, "u", remote("4.980", "https://x"), "4.980", false),
        )
        // 空串本地名（GameBase.cs:33 出厂值）不等于远端版本名 ⇒ 走询问支，不是 0。
        assertTrue(
            UpdateCheckDecision.afterFetch(remote("4.980", "https://x"), "") is UpdateCheckDecision.FetchOutcome.Ask,
        )
    }

    @Test
    fun newerVersionAskBranchesGiveTwoOneThree() {
        val r = remote("eraThe World 新版", "https://eratw-android.asia/updates/")
        assertEquals(2L, UpdateCheckDecision.decide(false, true, "u", r, "旧版", true))
        assertEquals(1L, UpdateCheckDecision.decide(false, true, "u", r, "旧版", false))
        // 没问到（超时 / 框弹不出来）⇒ 原版的 MessageBox.Show 抛出即落 catch ⇒ 3（:2827-2832）。
        assertEquals(3L, UpdateCheckDecision.decide(false, true, "u", r, "旧版", null))
    }

    @Test
    fun precheckAndAfterFetchArePrefixesOfTheTable() {
        // 分阶段 API（编排方在用）必须与一次性表逐条同解，否则「不发请求」与「RESULT」会分叉。
        val urls = listOf(null, "", "u")
        val remotes = listOf<UpdateCheckDecision.RemoteInfo?>(null, remote(null, null), remote("", "l"), remote("v", ""), remote("v", "l"))
        val locals = listOf("", "v", "旧")
        val accepts = listOf<Boolean?>(true, false, null)
        for (forbidden in listOf(false, true)) {
            for (network in listOf(false, true)) {
                for (url in urls) {
                    val pre = UpdateCheckDecision.precheck(forbidden, network, url)
                    if (pre is UpdateCheckDecision.Precheck.Settled) {
                        for (remote in remotes) {
                            for (local in locals) {
                                for (accept in accepts) {
                                    assertEquals(
                                        pre.result,
                                        UpdateCheckDecision.decide(forbidden, network, url, remote, local, accept),
                                        "precheck 定案后 decide 必须给同一个读数（forbid=$forbidden net=$network url=$url）",
                                    )
                                }
                            }
                        }
                        continue
                    }
                    for (remote in remotes) {
                        for (local in locals) {
                            val post = UpdateCheckDecision.afterFetch(remote, local)
                            if (post is UpdateCheckDecision.FetchOutcome.Settled) {
                                for (accept in accepts) {
                                    assertEquals(
                                        post.result,
                                        UpdateCheckDecision.decide(forbidden, network, url, remote, local, accept),
                                        "取回内容定案后 decide 必须给同一个读数（url=$url local=$local）",
                                    )
                                }
                            } else {
                                for (accept in accepts) {
                                    val expected = when (accept) {
                                        true -> 2L
                                        false -> 1L
                                        null -> 3L
                                    }
                                    assertEquals(
                                        expected,
                                        UpdateCheckDecision.decide(forbidden, network, url, remote, local, accept),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun promptTextMatchesOriginalFormatSlots() {
        // 原版 string.Format(Lang.MessageBox.NewVersionAvailable, text, text2)（:2802）：
        // {0} = 远端第一行、{1} = 远端第二行；zhs 串见 lang.emuera-zhs.xml:963-964。
        val message = UpdateCheckDecision.askMessage("新版1.2", "https://eratw-android.asia/updates/")
        assertEquals("新版本（新版1.2）已经发布。打开URL吗？\n链接地址:https://eratw-android.asia/updates/", message)
        // 标题 = Lang.MessageBox.UpdateCheck 的 zhs 串（lang.emuera-zhs.xml:962）。
        assertEquals("更新检查", UpdateCheckDecision.ASK_TITLE)
    }

    // ------------------------------------------------------------------
    // 二、FileSystemOps 编排：不发多余请求、异常不外抛
    // ------------------------------------------------------------------

    @Test
    fun forbiddenSkipsEveryHostProbeButTheConfigOne() {
        val host = FakeHost(forbidden = true, network = true, remote = remote("新", "链接"), accepted = true)
        val result = ops(host, url = "https://eratw-android.asia/updates/version.txt", versionName = "旧").updateCheck()
        assertEquals(4L, result)
        // 原版在 :2769 就 return —— 网络探针与取流都不该被调（`:2772` 在它之后）。
        assertEquals(listOf("forbid"), host.calls)
    }

    @Test
    fun noNetworkSkipsFetch() {
        val host = FakeHost(network = false, remote = remote("新", "链接"), accepted = true)
        assertEquals(5L, ops(host, url = "https://x", versionName = "旧").updateCheck())
        assertEquals(listOf("forbid", "network"), host.calls)
    }

    /**
     * 契约 §0 第 7 条的正解：**本语料（无 `バージョン情報URL`）跑 UPDATECHECK ⇒ RESULT=3，不抛 CodeEE**。
     *
     * 事实来源 = `eratw-画蛇添足版eratw4.980/CSV/GameBase.csv`（7 行，无该键）⇒ `GameBaseLoader`
     * 交出空串（`core/data/csv/GameBaseLoader.kt:79-80`）⇒ 原版 `:2779-2783` 落 3。
     */
    @Test
    fun noUrlYieldsResultThreeNotCodeEE() {
        val host = FakeHost()
        val result = ops(host).updateCheck() // url = "" / versionName = ""，与真机装载读数同形
        assertEquals(3L, result)
        assertEquals(listOf("forbid", "network"), host.calls, "URL 判空在取流之前，一次请求都不该发")
    }

    @Test
    fun unwiredHostStillReportsUnimplemented() {
        // 没注入宿主 = 装配缺陷，不该被伪装成语义读数 3（文案与 SystemOps 缺省实现同源）。
        val error = assertFailsWith<CodeEE> { ops(null).updateCheck() }
        assertTrue(error.message!!.contains("UPDATECHECK"), error.message!!)
    }

    @Test
    fun sameVersionDoesNotAskNobody() {
        val host = FakeHost(remote = remote("eraThe World 蛇添足版", "链接"), accepted = true)
        assertEquals(0L, ops(host, url = "https://x", versionName = "eraThe World 蛇添足版").updateCheck())
        assertEquals(listOf("forbid", "network", "fetch(https://x)"), host.calls)
    }

    @Test
    fun yesOpensTheRemoteLinkAndGivesTwo() {
        val host = FakeHost(remote = remote("新版", "https://eratw-android.asia/updates/"), accepted = true)
        assertEquals(2L, ops(host, url = "https://x", versionName = "旧版").updateCheck())
        // 原版把 `Process.Start` 的 FileName 写成第二行原文（:2805-2809）。
        assertEquals("https://eratw-android.asia/updates/", host.openedUrl)
        assertTrue(host.calls.last().startsWith("open("), host.calls.toString())
    }

    @Test
    fun noDoesNotOpenAnythingAndGivesOne() {
        val host = FakeHost(remote = remote("新版", "https://eratw-android.asia/updates/"), accepted = false)
        assertEquals(1L, ops(host, url = "https://x", versionName = "旧版").updateCheck())
        assertEquals(null, host.openedUrl)
    }

    @Test
    fun askTimeoutGivesThreeAndOpensNothing() {
        val host = FakeHost(remote = remote("新版", "链接"), accepted = null)
        assertEquals(3L, ops(host, url = "https://x", versionName = "旧版").updateCheck())
        assertEquals(null, host.openedUrl)
    }

    @Test
    fun hostExceptionsCollapseToThreeNotCrash() {
        // 原版两个 catch（:2827-2832 / :2834-2837）：取流与询问抛出去都只落成 3，VM 线程不许看到异常。
        assertEquals(3L, ops(FakeHost(fetchThrows = true), url = "https://x", versionName = "旧版").updateCheck())
        assertEquals(3L, ops(FakeHost(askThrows = true), url = "https://x", versionName = "旧版").updateCheck())
    }

    @Test
    fun failingLinkOpenOverwritesTwoWithThree() {
        // :2804 先写 RESULT=2，:2805 的 Process.Start 抛出后被同一个 inner try 的 catch（:2829）改写成 3。
        val host = FakeHost(remote = remote("新版", "链接"), accepted = true, openThrows = true)
        assertEquals(3L, ops(host, url = "https://x", versionName = "旧版").updateCheck())
        assertEquals("链接", host.openedUrl)
    }

    // ------------------------------------------------------------------
    // 三、语句分派面：两条通道都只写 RESULT、不抛
    // （StatementExecutor.kt:3776 对象树入口（Android 生产默认）/ :1242 扁平直读入口（归因/装置通道））
    // ------------------------------------------------------------------

    /** 把 [SemTestEnv] 的语境换成「带 GameBase 读数 + 真 FileSystemOps」的语境。 */
    private fun envWith(host: UpdateCheckHost?, url: String, versionName: String): Pair<SemTestEnv, VariableStore> {
        val store = VariableStore(VariableRegistry(gameBase = GameBaseData(url = url, versionName = versionName)))
        store.randProvider = com.eratw.port.core.rand.Sfmt19937(0L)
        val env = SemTestEnv()
        env.ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = env.console,
            input = env.input,
            systemOps = ops(host, url, versionName),
        )
        return env to store
    }

    private fun resultOf(store: VariableStore): Long = store.readInt("RESULT", longArrayOf(0L))

    @Test
    fun dispatchWritesResultOnBothPathsWithoutThrowing() {
        val host = FakeHost(remote = remote("新版", "链接"), accepted = true)
        val (env, store) = envWith(host, url = "https://x", versionName = "旧版")
        val stmt = instr(FunctionCode.UPDATECHECK)
        // 对象树入口（`execute(stmt, ctx)`，Android 生产默认；分派在 StatementExecutor.kt:3776）。
        @Suppress("DEPRECATION")
        env.run(stmt)
        assertEquals(2L, resultOf(store))
        // 扁平直读入口（归因/装置通道，分派在 :1242）：同一份初始状态、同一个读数。
        env.runFlat(stmt)
        assertEquals(2L, resultOf(store))
    }

    @Test
    fun dispatchOfThisCorpusShapeGivesThreeNotError() {
        val (env, store) = envWith(FakeHost(), url = "", versionName = "")
        @Suppress("DEPRECATION")
        val outcome = env.run(instr(FunctionCode.UPDATECHECK))
        assertEquals(3L, resultOf(store))
        assertTrue(outcome is com.eratw.port.core.sem.ExecResult.Normal, outcome.toString())
    }
}
