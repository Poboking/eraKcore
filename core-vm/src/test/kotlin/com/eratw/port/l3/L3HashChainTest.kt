package com.eratw.port.l3

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.xml.XmlDocument
import com.eratw.port.core.xml.XmlPool
import com.eratw.port.e2e.GoldenModel
import com.eratw.port.e2e.GoldenPipeline
import com.eratw.port.e2e.LineConsoleSink
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 一个链节点在本测试里的视图（[HashChain.Node] 的拷贝）。
 * 提到文件顶层是为了避开 companion 嵌套类参与类型推断（K2 会在 lambda 的 `it` 上报
 * 「Unable to determine a type」，装置诊断信息比类型糖重要，这里直接扁平化）。
 */
private class ChainNode(
    val index: Int,
    val title: String,
    val status: String,
    val stateHash: Long,
    val chain: Long,
    val digest: StateDigest,
)

/** 判据 (c) 的改写证据。 */
private class ForkProbe(
    val forkIndex: Int,
    val mutation: String,
    val expectCluster: String,
    val before: StateDigest,
    val after: StateDigest,
)

/** 一次完整跑节的结果：链节点 + 诊断清单 + 末态驻留池**原文**快照（判据 (a) 的根因哨兵用）。 */
private class ChainRun(
    val tag: String,
    val nodes: List<ChainNode>,
    val failures: List<String>,
    val registerFailures: List<String>,
    val renderErrors: List<String>,
    val probe: ForkProbe?,
    val rawPool: List<String>,
)

/**
 * L3 状态哈希链回归装置（CONTRACT-M4-A §3.2；实施方案 §6 第三级）。
 *
 * ## 做什么
 *
 * 按 GOLDEN 套件的**分节顺序**（[GoldenModel.units]，与 `E2EGoldenTest.分节弹性执行` 同一口径：
 * ROOT-INFO → 12 个 `@GOLDEN_*` 节 → RUN-END；人工节记 SKIPPED 但仍产链节点）逐节执行，
 * 每节结束用 [StateHash] 对 `rt.store` 的可见状态求一次 64 位哈希，累积成链（[HashChain]）。
 * 三级验证的分工：L2 管「屏上文字逐字节」，本装置管「状态逐节演化」—— L2 看不见的不打印状态
 * （角色各数据列、字符串驻留池、`store.aux` 各池、用户 `#DIM`）在这里有链可对。
 *
 * ## 判据
 *
 * - (a) 同进程两次运行的链逐值相同。也是 [StateHash] 类 KDoc「不纳入项」1/5/6（宿主时钟派生格、
 *   宿主对象图、扩容尾部残留）被破坏时的哨兵。宿主派生值有两处出口：格子（`RESULT:0` / `RESULTS:0`
 *   走 `HOST_DERIVED_MARK`）与**驻留池**（`writeStr` 一律 `pool.intern`，`VariableStore.kt:202-214`，
 *   时钟原文会留在池里）。
 *   **这一条的修法是「时钟只从一个注入点出来」，不是测试侧归一**：引擎侧 GETTIME 语句改吃
 *   `BuiltinHost.clockMillis`（`StatementExecutor.kt:642-674`），装置侧在 `GoldenPipeline` 的
 *   `BuiltinHost` 上把 `clockMillis()` 钉成常量（`E2E_PINNED_CLOCK_MILLIS`；取值与 L2 判据不冲突的
 *   依据写在该常量 KDoc —— 基准 `pc-golden-root.txt:516` 只记长度不记值）。钉钟之后 #9 的池文本与
 *   #10 的 `sys-int-global`（DT 行主键写进 `RESULT:1` 起的格）两轮逐字节相同。
 *   [StateHash.normalizePoolText]（三条规则 R1/R2/R3，取证依据见 StateHash 类 KDoc
 *   「string-pool 的宿主派生内容归一」）**保留为防御层，不删**：归一只改哈希输入文本，不改状态、
 *   不按下标跳过条目、不把整簇降级；命中条数记进 `notes` 供报告核查。钉钟后它照旧会命中（池里那条
 *   时钟文本仍是时钟形状，只是两轮同值），所以命中数不是证据 —— 证据是本判据末尾那条
 *   「未归一驻留池原文两轮逐字节相同」（[StateHash.rawPoolSnapshot]），防的就是有人把时钟放回绕过
 *   注入点的路子、靠归一把链抹绿。
 * - (b) 链头 / 链尾等于登记常量 —— 见 [链头与链尾等于登记常量]：**登记值是装置自产快照，不是 PC 基准**。
 * - (c) 改写任一角色列 → 链从该节点起分叉（施加在第 [FORK_AFTER_NODE] 个节点求值之后，
 *   并把改写次数作为单调血统 [FORK_LINEAGE] 折入其后的每一节；为什么必须由血统而不是状态传导
 *   来保证，见该判据的 KDoc —— 节内 `DELALLCHARA`/`RESETDATA` 会把改写抹平，状态可汇合是预期）。
 * - (d) 逐维度判别力 + 幂等：角色列 / 全局整数 / 全局字符串 / `lastLoadVersion` / XML 池 / Sprite 池 /
 *   图形缓冲每一维都能改变哈希；同一状态重复求值逐位相同。后者同时证明「求哈希是纯观察」——
 *   `RAND` 伪变量是读取即消耗（`VariableStore.kt:966-978`），所以 StateHash 显式排除它。
 * - (e) 默认语言环境无关：同一 store 在 `zh_CN` 与 `tr_TR`（土耳其语 I/İ 陷阱）下重求逐位相同。
 * - (f) A / B / G 三条干净轮**穷举全量**比对（L3-AFTER-11）：逐节标题 / 状态 / 状态哈希 / 链值 +
 *   每节**全部**簇（按簇序逐位比名字与哈希，另做簇名集合对称差）+ 节执行失败清单 + 注册失败清单
 *   + 未归一驻留池原文，两两相同；差异**逐条枚举**，不是「首个相同即过」。与 (a) 的分工见
 *   `三轮逐节链值与逐簇摘要全量相同` 的 KDoc：(a) 的失败信息经 [divergence] 只携带**首个**差异
 *   节点，首差之后的独立分叉既不会出现在消息里、也无法从链值反推（链折前驱，首差起全级联）；
 *   跨轮**逐簇**比对在大批簇上（真实装置每节 21–23 簇）此前根本不存在。G 是**确定性守卫轮**：
 *   物化序排在 A、B、FORK 之后，第三遍同进程执行把「同进程残余」从推演变成读数。
 *   比较器自身由 `穷举对比器对多处植入差异逐条报出` 看住（多处植入必须多处报出，并与首差式
 *   [divergence] 的输出做对照）。
 *
 * ## 与 PC 基准的关系（必读，别把这里当规约）
 *
 * 契约 §3.2(b) 要求登记链头/链尾常量。这些值由本 Kotlin 实现自己跑出来，PC 侧没有等价状态哈希可比，
 * 所以它们**只回答「实现有没有自己变」，不回答「对不对」**。语义正确性的权威仍是
 * `eratw-android/specs` 下的 yaml 规约 + 反编译 C# + L2 逐字节对拍。登记常量要改动时，必须连带说明
 * 是哪一层的语义推进，并复核 L2（610/610 行）不回退。
 *
 * ## 首跑落定流程（本轮 worker 不跑测试，AGENTS §2 铁律 7）
 *
 * 1. 组长跑 `./gradlew :core-vm:test --tests "*L3HashChainTest*"`。此时 [REGISTRY_READY] 为 false，
 *    (b) 判据按 JUnit assumption 报 **SKIPPED**（不通过、也不写恒真断言），(a)(c)(d)(e) 照常拦截。
 * 2. 落定常量用环境变量 `ERATW_L3_UPDATE=1`（`core-vm/build.gradle.kts` 已把 `ERATW_*` 透传给 test JVM）。
 *    该模式下 (b) 只落值不拦截，并把可直接粘贴的三行写进 `core-vm/build/l3-report.md`
 *    的「(b) 登记常量」一节。
 * 3. 把那三行粘进本文件的 [REGISTRY_READY] / [REGISTERED_HEAD] / [REGISTERED_TAIL]，(b) 从此是硬闸。
 *    **禁止**只把 [REGISTRY_READY] 改 true 却不换两个常量（必红），也禁止反过来把常量写成
 *    「永不校验」的形式还留着 true —— 占位态就是 false + SKIPPED，一目了然。
 * 4. **本轮（时钟收口）之后必须重落一次常量**：引擎侧 GETTIME 语句改吃宿主注入点、装置侧钉钟，
 *    两轮状态从此逐字节相同 → 每一节的状态哈希与链值都会变（这是语义推进，不是回退）。
 *    常量仍是 false + SKIPPED（worker 无权跑闸），合并后由组长按第 2/3 步落定。
 *
 * ## Locale 口径
 *
 * 哈希路径不用任何 locale 敏感 API（无 `String.format`、无 `toUpperCase`、无 Collator、
 * 不按 `String.hashCode` 排序），(e) 在进程内直接验证。契约 §3 要求的
 * `./gradlew :core-vm:test -Duser.language=zh` 整轮复跑属组长侧闸门动作，本文件不代跑；
 * 整轮复跑若出现差异，红在引擎（某处运行期行为随 locale 变），不放宽本装置断言。
 *
 * ## 成本
 *
 * 四条链（A / B / G / FORK）= 四遍完整分节执行，另加一个只做开局装载的探针运行时（(d)(e) 共用）。
 * 合计 5 套运行时，与 `E2EGoldenTest` 自身的构造次数同量级。G 是判据 (f) 的确定性守卫轮，
 * 多出的这一遍干净执行是为「同进程残余」从推演变读数付的成本。
 *
 * 报告：`core-vm/build/l3-report.md`（build 目录，不进 git）。
 */
class L3HashChainTest {

    // ------------------------------------------------------------------
    // 判据
    // ------------------------------------------------------------------

    /**
     * (a) 同进程两次运行的链必须逐值相同。
     *
     * 同时兜住两件事：装置自身是否与 locale / 容器迭代序无关；引擎状态演化是否只由 ERB 决定。
     * 不等时诊断信息给到首个差异簇（不只看链值），报告同步落盘。
     *
     * **根因哨兵（本轮加的，别把它当冗余删掉）**：链相等之后，还要证明「相等不是 [StateHash.normalizePoolText]
     * 抹出来的」。时钟已经在引擎侧走 `BuiltinHost.clockMillis` 注入点、装置侧钉成常量
     * （`GoldenPipeline` 的 `E2E_PINNED_CLOCK_MILLIS`），所以**未归一的驻留池原文**两轮就该逐字节相同 ——
     * 这里直接断言那一条（[ChainRun.rawPool]）。归一规则照旧留着、也照旧会命中（池里那条时钟文本
     * 本身仍是时钟形状，只是两轮同值），因此「命中数」不是证据，原始文本相等才是。
     * 真正的修法在引擎侧注入点，不在测试侧归一；谁把时钟放回去，链会照常绿（归一还兜着），
     * 这条断言立刻红。依据见 StateHash 类 KDoc「string-pool 的宿主派生内容归一」。
     *
     * 池簇按 [StateHash.normalizePoolText] 归一宿主派生文本（时钟文本 / 临时根 / 临时目录名 /
     * DT 的 18 位 ticks 主键），依据见 StateHash 类 KDoc；命中条数逐节写进报告的
     * 「(a) 池内容归一命中」一行，命中数为 0 却仍然分叉时，说明差异不是宿主文本，别去放宽归一。
     */
    @Test
    fun `同一进程两次运行链逐值相同`() {
        val a = runA
        val b = runB
        assertTrue(a.nodes.size >= 12, "链节点过少（" + a.nodes.size + "）：分节单元未按预期执行，装置无意义")
        assertEquals(titlesOf(a), titlesOf(b), "两轮节序不同（装置口径漂了）")
        val diag = divergence(a, b)
        appendReport(
            "\n## (a) 两次运行自洽\n\n- 轮 A 节点数=" + a.nodes.size + " 轮 B 节点数=" + b.nodes.size + "\n" +
                "- 池内容归一命中: 轮 A " + poolNormalizationHits(a) + " / 轮 B " + poolNormalizationHits(b) + "\n" +
                "- 未归一驻留池原文: 轮 A " + a.rawPool.size + " 条 / 轮 B " + b.rawPool.size + " 条（下方断言逐字节相同）\n" +
                "- 差异: " + diag + "\n\n### 轮 A 链\n\n" + nodeTable(a) + "\n### 轮 B 链\n\n" + nodeTable(b) + "\n",
        )
        assertEquals(chainsOf(a), chainsOf(b), "两次运行链值不同：" + diag)
        assertEquals(statesOf(a), statesOf(b), "两次运行逐节状态哈希不同：" + diag)
        assertEquals(a.rawPool, b.rawPool, "未归一的驻留池原文两轮不同 → 有宿主派生文本绕过了时钟注入点" +
            "（链现在能相等只是因为归一还兜着，不是根因已修）：" + firstPoolDiff(a, b))
        val errs = ArrayList<String>()
        errs.addAll(a.renderErrors)
        errs.addAll(b.renderErrors)
        assertTrue(errs.isEmpty(), "StateHash 渲染报错（簇内容不可信，装置 bug，需 owner 修）:\n" + errs.joinToString("\n"))
    }

    /**
     * (f) A / B / G 三轮穷举比对：逐节链值 + 逐簇摘要**全量**相同（L3-AFTER-11）。
     *
     * ## 与判据 (a) 的分工（为什么这条不是冗余）
     *
     * (a) 的断言本身已是全列表（`assertEquals(chainsOf/statesOf)` —— 检测是穷举的），但它的
     * **失败信息**经 [divergence] 只携带**首个**差异节点：首差之后的第二条独立分叉既不会出现在
     * 消息里，也无法从链值反推 —— 链折了前驱，首差一旦发生，其后每一节的链值必然全部不同
     * （级联），独立分叉与级联分叉在链值上不可区分（状态可汇合，见判据 (c) 的 stateRejoin 取证）。
     * 并且 (a) 从不做**跨轮逐簇**比对：逐簇摘要只在 (c) 探针的同一 store 前后像之间比过一次；
     * 真实装置每节 21–23 簇（`#1 GOLDEN_BASIC` 起新建 `aux:lang.codec` 槽、`#11 GOLDEN_GVIEW`
     * 起新建 `aux:fontsidecar` 槽 —— 见 build/l3-report.md 的每节簇表），首差落在 #9/#10 的历次
     * 取证（StateHash 类 KDoc：「string-pool 的宿主派生内容归一」）里，#11 之后的 23 簇是否有
     * 第二条独立分叉，消息维度从来不可判定。
     *
     * 本判据对 A/B/G 两两做全量比对：逐节标题 / 状态 / 状态哈希 / 链值 + 每节**全部**簇
     * （簇序逐位名字+哈希、簇名集合对称差）+ 节执行失败清单 + 注册失败清单 + 未归一驻留池原文，
     * 差异**逐条枚举**（一条不空即红，不是「首个相同即过」）。
     *
     * ## 确定性守卫轮 G
     *
     * 物化序（Kotlin 语句序即执行序）：A → B → FORK → G。G 是第三遍**干净**协议执行，且一定跑在
     * A、B 与改写过独立 runtime 的 FORK 轮之后 —— 「同进程残余」（JVM 堆派生值、类加载/JIT 状态、
     * 容器迭代序、静态缓存）若还会渗进后续轮，A/B/G 的两两全量比对会把位点直接报出来，不再依赖
     * 「CLEARMEMORY 落 RESULT:0 已被 [StateHash.HOST_DERIVED_MARK] 掩、容器序已排序」这类推演。
     * G 不参与 (b) 的登记常量比较（(b) 口径不变，仍只看轮 A）。
     */
    @Test
    fun `三轮逐节链值与逐簇摘要全量相同`() {
        GoldenPipeline.requireGameCsvDir()
        val a = runA
        val b = runB
        val f = runFork
        val g = runG

        val mismatches = ArrayList<String>()
        exhaustiveMismatches(a, b, mismatches)
        exhaustiveMismatches(a, g, mismatches)
        exhaustiveMismatches(b, g, mismatches)

        var clusterTotal = 0
        var clusterMin = Int.MAX_VALUE
        var clusterMax = 0
        for (nd in a.nodes) {
            val size = nd.digest.clusters.size
            clusterTotal += size
            if (size < clusterMin) clusterMin = size
            if (size > clusterMax) clusterMax = size
            assertTrue(
                size >= MIN_EXPECTED_CLUSTERS,
                "#" + nd.index + " " + nd.title + " 簇数 " + size + " 低于下限 " + MIN_EXPECTED_CLUSTERS +
                    "（装置退化会让全量比对滑向恒真；查 StateHash 的簇构造）",
            )
        }
        assertTrue(g.renderErrors.isEmpty(), "守卫轮 G 有渲染错误（簇内容不可信，装置 bug）:\n" + g.renderErrors.joinToString("\n"))

        val clusterChecks = clusterTotal * 3
        val nodeChecks = a.nodes.size * 3 * 4
        val poolChecks = 3
        appendReport(
            "\n## (f) 三轮穷举比对（A / B / G · 逐节链值 + 逐簇摘要全量 · L3-AFTER-11）\n\n" +
                "- 轮次: A / B / G；G = 确定性守卫轮，物化于 A、B、FORK（" + f.nodes.size + " 节，其状态被改写过）之后\n" +
                "- 节点数: A=" + a.nodes.size + " B=" + b.nodes.size + " G=" + g.nodes.size + "（分节单元数 " + model.units.size + "）\n" +
                "- 每轮簇数合计 " + clusterTotal + "（每节最少 " + clusterMin + " / 最多 " + clusterMax + "；下限断言 " + MIN_EXPECTED_CLUSTERS + "）\n" +
                "- 判定规模: 簇级 " + clusterChecks + " 项 + 节级（标题/状态/状态哈希/链值）" + nodeChecks + " 项 + 池原文 " + poolChecks + " 对\n" +
                "- 差异清单: " + (if (mismatches.isEmpty()) "空（全量相同）" else mismatches.size.toString() + " 项（逐条见下）") + "\n" +
                (if (mismatches.isEmpty()) "" else asBullets(mismatches)) +
                "- 对照（首差式 divergence 口径，A vs B）: " + divergence(a, b) + "\n" +
                "- 首差掩盖说明: divergence 只回首个差异节点；本清单是全量枚举，首差之后的每条差异（独立或级联）都在列\n",
        )
        assertTrue(
            mismatches.isEmpty(),
            "A/B/G 三轮未做到逐节链值 + 逐簇摘要全量相同（" + mismatches.size + " 项；本判据不是「首个相同即过」）:\n" +
                asBullets(mismatches),
        )
    }

    /**
     * (f) 判别力自证：穷举比较器对**多处植入差异逐条报出**（不是只报第一条），并与首差式
     * [divergence] 的输出做对照 —— 直接演示「首差掩盖后续」这一 L3-AFTER-11 的取证动机。
     *
     * 数据见 [syntheticRounds]（纯数据，零运行时开销；节点编号刻意与真装置对齐：#9/#10 是历次
     * 首差的历史位点、#11 GOLDEN_GVIEW 起多出 aux:fontsidecar 槽）。对照断言：divergence 只回 #9；
     * 穷举清单必须同时点名 #9（状态 + 簇）、#11（独立簇集合差）与驻留池下标。
     */
    @Test
    fun `穷举对比器对多处植入差异逐条报出`() {
        val pair = syntheticRounds()
        val s1 = pair.first
        val s2 = pair.second
        val found = ArrayList<String>()
        exhaustiveMismatches(s1, s2, found)

        val firstOnly = divergence(s1, s2)
        assertTrue(firstOnly.startsWith("首差节点 #9 "), "首差式对照口径变了: " + firstOnly)
        assertTrue(!firstOnly.contains("#11"), "divergence 不应看到 #11（首差对照被破坏）: " + firstOnly)
        assertTrue(found.size >= 4, "穷举清单过少（" + found.size + " 条）:\n" + asBullets(found))
        assertTrue(anyLineContaining(found, "] #9 ", "状态哈希不同"), "首差位点 #9 的状态差异没被点名:\n" + asBullets(found))
        assertTrue(anyLineContaining(found, "] #9 ", "簇 meta 不同"), "首差位点 #9 的簇级差异没被点名:\n" + asBullets(found))
        assertTrue(anyLineContaining(found, "] #11 ", "aux:extra"), "第二条独立分叉 #11 没被点名（被首差掩盖）:\n" + asBullets(found))
        assertTrue(anyLineContaining(found, "未归一驻留池", "下标 5"), "驻留池植入差异没被点名:\n" + asBullets(found))
        var chainDiff = 0
        for (s in found) {
            if (s.contains("链值不同")) chainDiff++
        }
        assertTrue(chainDiff >= 2, "链值级联差异没有全量枚举（实际 " + chainDiff + " 条）:\n" + asBullets(found))

        appendReport(
            "\n## (f) 判别力自证（穷举比较器 vs 首差式 divergence）\n\n" +
                "- 植入: #9 首差（meta 簇 + 状态）/ #11 独立分叉（多簇 aux:extra）/ 驻留池下标 5 / #9 起链值级联\n" +
                "- 穷举清单 " + found.size + " 条:\n" + asBullets(found) +
                "- [divergence] 对照输出: " + firstOnly + "\n" +
                "- 结论: 同一对轮次，divergence 只报 #9；穷举把 #9/#11/池差异全部报出（首差掩盖的直接演示）\n",
        )
    }

    /**
     * (b) 链头 / 链尾等于登记常量。
     *
     * **登记值是装置自产快照，不是 PC 基准**（契约 §3.2(b) 的原文口径；PC 上没有状态哈希可比）。
     * 首跑时 [REGISTRY_READY] 为 false，这里用 JUnit assumption 明确报 SKIPPED ——
     * 不写恒真断言、不静默通过；落定方法见类 KDoc「首跑落定流程」。
     */
    @Test
    fun `链头与链尾等于登记常量`() {
        GoldenPipeline.requireGameCsvDir()
        val run = runA
        val head = run.head()
        val tail = run.tail()
        val update = System.getenv("ERATW_L3_UPDATE") == "1"
        val block = StringBuilder()
        block.append("\n## (b) 登记常量（自产快照，非 PC 基准）\n\n")
        block.append("- 链头 = ").append(StateHash.hex(head)).append('\n')
        block.append("- 链尾 = ").append(StateHash.hex(tail)).append('\n')
        block.append("- 可粘贴文本（替换 L3HashChainTest 里的三个常量）：\n")
        block.append("    private const val REGISTRY_READY = true\n")
        block.append("    private const val REGISTERED_HEAD = ").append(head).append("L\n")
        block.append("    private const val REGISTERED_TAIL = ").append(tail).append("L\n")
        block.append("- 当前源码: REGISTRY_READY=").append(REGISTRY_READY)
            .append(" HEAD=").append(REGISTERED_HEAD)
            .append(" TAIL=").append(REGISTERED_TAIL)
            .append('\n')
        block.append("- ERATW_L3_UPDATE=").append(update).append("（=1 时本判据只落值不拦截）\n")
        appendReport(block.toString())
        if (update) return
        Assumptions.assumeTrue(
            REGISTRY_READY,
            "L3 链头/链尾登记常量仍是占位（首跑未落定）。跑一次 ERATW_L3_UPDATE=1 " +
                "./gradlew :core-vm:test --tests \"*L3HashChainTest*\"，把 build/l3-report.md 的「(b) 登记常量」" +
                "三行粘进 L3HashChainTest。(a)(c)(d)(e) 判据不受影响，照常拦截。",
        )
        assertEquals(REGISTERED_HEAD, head, "链头与登记常量不符（首轮状态演化变了；查 (a) 与报告 (b) 节）")
        assertEquals(REGISTERED_TAIL, tail, "链尾与登记常量不符（末节状态变了；查 (a) 与报告 (b) 节）")
    }

    /**
     * (c) 改写任一角色列 → 链从该节点起分叉，其后不再汇合。
     *
     * 改写施加在第 [FORK_AFTER_NODE] 个节点求值之后，该节点本身是「干净前像」。三段硬闸：
     * - 结构：#0…#k 链值与干净轮逐值相同；**首个不同的链值必须正好是 #k+1**（改写节点的下一个）。
     * - 状态：同一状态两次求值逐位相同（求哈希是纯观察）；改写后状态哈希必须变，且首个差异簇
     *   正好是 `sys-chara`（角色列真的被这一簇看着，不是靠别的簇蒙对）。
     * - 血统：#k+1 及其后每一个链值都必须与干净轮不同。这一条由链叠入的**单调血统计数**
     *   （[HashChain.append] 的 `lineage` 形参，见该类 KDoc）保证，**不靠**「状态差异永久传导」。
     *
     * ## 为什么必须由血统来保证（取证结论；汇合不是 bug，别去「修引擎」）
     *
     * 链只在**节尾**观察状态，而 GOLDEN 套件在节内就把改写抹掉了：紧随其后的 GOLDEN_CHARA 执行
     * `DELALLCHARA`（`fixtures/golden/GOLDEN_04_CHARA.ERB:88`）后再 `ADDCHARA 0`（:91）从模板重建角色，
     * GOLDEN_MISC 又执行 `RESETDATA`（`GOLDEN_09_MISC.ERB:72`）+ `ADDCHARA 0`（:75）。被改的
     * `BASE:0:0` 在这些节结束时已回到模板值 —— 于是 v1 链（只折前驱 + 标题 + 状态 + 状态哈希）
     * 从 #4 起逐节与干净轮**重新汇合**，v1 类注释里「链式累积必然传导」的断言是错的。
     * 这不是实现缺陷，是「观察点在节尾 + 节内整体覆盖」的必然结果：状态序列本身就没有差异，
     * 任何只折状态哈希的链都不可能分叉。
     *
     * 因此这里把「哪些节的**状态哈希**与干净轮汇合」原样测出来（`stateRejoin` 清单），
     * 逐条写进 `build/l3-report.md` 的「(c) 分叉判别力」一节并保留在报告里；覆盖点若被改动，
     * 清单就会变。血统计数只把「装置动过手」这个事实记进链，不参与状态求值，
     * 干净轮的 lineage 恒 0 → 判据 (a) 的两次运行仍逐值相同。
     */
    @Test
    fun `改写角色列后链从该节点起分叉`() {
        val clean = runA
        val forked = runFork
        val p = forked.probe ?: throw AssertionError("分叉探针缺失：forkAfter=" + FORK_AFTER_NODE + " 未命中节点")
        val k = p.forkIndex
        assertTrue(k + 2 < clean.nodes.size, "分叉点后至少还要 2 个节点才谈得上分叉（当前共 " + clean.nodes.size + " 节）")

        val early = ArrayList<String>()
        val rejoin = ArrayList<String>()
        val stateRejoin = ArrayList<String>()
        var firstFork = -1
        var divergedAfter = 0
        for (i in 0 until clean.nodes.size) {
            val sameChain = clean.nodes[i].chain == forked.nodes[i].chain
            val sameState = clean.nodes[i].stateHash == forked.nodes[i].stateHash
            if (firstFork < 0 && !sameChain) firstFork = i
            if (i <= k && !sameChain) early.add("#" + i + " " + clean.nodes[i].title + " 分叉点之前链值就不同")
            if (i > k) {
                if (sameChain) {
                    rejoin.add("#" + i + " " + clean.nodes[i].title + " 链值汇合 " + StateHash.hex(forked.nodes[i].chain))
                } else {
                    divergedAfter++
                }
                if (sameState) stateRejoin.add("#" + i + " " + clean.nodes[i].title)
            }
        }
        assertTrue(early.isEmpty(), "分叉点之前就已不同（装置不干净）：\n" + early.joinToString("\n") + "\n" + divergence(clean, forked))
        assertEquals(k + 1, firstFork, "首个分叉节点不是改写节点的下一个（应为 #" + (k + 1) + "，实际 #" + firstFork + "）")
        assertTrue(
            rejoin.isEmpty(),
            "叠入单调血统计数后 #k 之后仍有链值重新汇合 —— 血统没进链，装置 bug（不是引擎问题）:\n" +
                rejoin.joinToString("\n"),
        )
        assertTrue(divergedAfter >= 2, "分叉节点数不足 2（实际 " + divergedAfter + "）：链长度或施加位置漂了")

        // 求哈希必须是纯观察：同一状态的两次求值逐位相同。
        assertEquals(clean.nodes[k].stateHash, p.before.total, "同一状态两次求值不同（哈希有副作用或读了不稳定源）")
        assertNotEquals(p.before.total, p.after.total, "改写 " + p.mutation + " 后状态哈希没变（哈希对该维度失明）")
        val expectCluster = p.expectCluster
        val actualCluster = p.before.firstDiffCluster(p.after) ?: "(无差异)"
        assertEquals(
            expectCluster,
            actualCluster,
            "改写角色列后首个差异簇不是 " + expectCluster + "（实际 " + actualCluster + "；查 StateHash 分簇口径）",
        )
        appendReport(
            "\n## (c) 分叉判别力\n\n- 施加节点: #" + k + " " + clean.nodes[k].title + "\n- 改写: " + p.mutation + "\n" +
                "- 期望差异簇: " + expectCluster + "\n- 实际首个差异簇: " + actualCluster + "\n" +
                "- 首个链值分叉节点: #" + firstFork + "（应为 #" + (k + 1) + "）\n" +
                "- 分叉后节点数: " + (clean.nodes.size - k - 1) + "，其中链值不同 " + divergedAfter + " 个\n" +
                "- 血统计数: 干净轮 0 / 分叉轮 " + FORK_LINEAGE + "（链公式 v2 的第二路输入，见 HashChain KDoc）\n" +
                "- 状态哈希与干净轮汇合的节点（**预期行为，非 bug**：节内 DELALLCHARA/RESETDATA 覆盖了改写）: " +
                (if (stateRejoin.isEmpty()) "无" else stateRejoin.joinToString("; ")) + "\n\n" +
                "### 分叉轮链\n\n" + nodeTable(forked) + "\n" +
                "- 改写前簇表: " + clusterTable(p.before) + "\n- 改写后簇表: " + clusterTable(p.after) + "\n",
        )
    }

    /**
     * (d) 逐维度判别力 + 幂等（开局态，不跑节）。
     *
     * 每一维改写都必须改变总哈希，且任意状态下重复求值逐位相同。维度按 [StateHash] 的簇名点验：
     * 改角色列点 `sys-chara`，改全局串会同时动 `sys-str-global` 与 `string-pool`（新驻留），
     * 改 aux 点对应 `aux:*` 簇。
     */
    @Test
    fun `哈希逐维度敏感且幂等`() {
        val store = probeStore
        val keys = probeKeys
        val labels = ArrayList<String>()
        val mutations = ArrayList<() -> Unit>()

        fun dim(label: String, mutate: () -> Unit) {
            labels.add(label)
            mutations.add(mutate)
        }

        dim("sys-chara（BASE:1:0）") {
            if (store.characters.count > 1) store.writeInt("BASE", longArrayOf(1L, 0L), 12345L)
            else store.writeInt("BASE", longArrayOf(0L, 0L), 12345L)
        }
        dim("sys-int-global（MONEY:0）") { store.writeInt("MONEY", longArrayOf(0L), 777777L) }
        dim("sys-str-global 与 string-pool（STR:5）") { store.writeStr("STR", longArrayOf(5L), "L3-UNIQUE-STR") }
        dim("lastload（lastLoadVersion）") { store.lastLoadVersion = 987654321L }
        dim("aux:xml（新文档键）") { store.aux.slot("xml") { XmlPool() }.put("L3-DOC", XmlDocument()) }
        dim("aux:sprite（新 Sprite）") {
            store.aux.slot("sprite") { SpritePool() }.put(SpritePool.Sprite(name = "L3-SPRITE", width = 3, height = 4))
        }
        dim("aux:gfx（缓冲创建 + 像素）") {
            val g = store.aux.slot("gfx") { GraphicsPool() }
            g.get(7).create(2, 2)
            g.get(7).setPixel(1, 1, 0x00FF00)
        }

        val report = StringBuilder("\n## (d) 逐维度判别力（开局态，不跑节）\n\n")
        var prev = StateHash.digest(store, keys)
        assertEquals(prev.total, StateHash.digest(store, keys).total, "未改任何状态时两次求值不同（哈希不稳定）")
        assertTrue(prev.errors.isEmpty(), "开局态求值就有渲染错误，簇内容不可信:\n" + prev.errors.joinToString("\n"))
        report.append("- 基线总哈希: ").append(StateHash.hex(prev.total)).append('\n')
        for (i in labels.indices) {
            mutations[i]()
            val now = StateHash.digest(store, keys)
            val again = StateHash.digest(store, keys)
            assertEquals(now.total, again.total, labels[i] + " 改写后两次求值不同（哈希读了不稳定源）")
            val cluster = prev.firstDiffCluster(now) ?: "(无差异)"
            assertNotEquals(prev.total, now.total, labels[i] + " 改写后总哈希没变（该维度失明）")
            report.append("- ").append(labels[i]).append(" → 总哈希 ").append(StateHash.hex(now.total))
                .append("（首个差异簇: ").append(cluster).append("）\n")
            prev = now
        }
        appendReport(report.toString())
    }

    /**
     * (e) 默认语言环境无关。
     *
     * 对**同一个** store 实例换 `Locale.getDefault()` 重求哈希必须逐位相同（含逐簇）。
     * 只审装置自身，不重跑节，因此不与引擎的 locale 行为耦合；契约 §3 要求的
     * `-Duser.language=zh` 整轮复跑归组长闸门（见类 KDoc）。
     */
    @Test
    fun `哈希不随默认语言环境改变`() {
        val store = probeStore
        val keys = probeKeys

        fun digestUnder(loc: Locale): StateDigest {
            val before = Locale.getDefault()
            try {
                Locale.setDefault(loc)
                return StateHash.digest(store, keys)
            } finally {
                Locale.setDefault(before)
            }
        }

        val baseline = StateHash.digest(store, keys)
        val zh = digestUnder(Locale.SIMPLIFIED_CHINESE)
        val tr = digestUnder(Locale("tr", "TR"))
        assertEquals(baseline.total, zh.total, "默认 locale 换成 zh_CN 后哈希变了 → StateHash 里有 locale 泄漏")
        assertEquals(baseline.total, tr.total, "默认 locale 换成 tr_TR 后哈希变了 → StateHash 里有 locale 泄漏（大小写折叠）")
        assertEquals(clusterPairs(baseline), clusterPairs(zh), "locale=zh_CN 下逐簇哈希不同（定位泄漏簇）")
        appendReport(
            "\n## (e) locale 无关\n\n- default: " + StateHash.hex(baseline.total) + "\n" +
                "- zh_CN: " + StateHash.hex(zh.total) + "\n- tr_TR: " + StateHash.hex(tr.total) + "\n",
        )
    }

    /**
     * 驻留池归一的纯函数口径（不起运行时，静态可判定；判据 (a) 的**防御层**在这里点验）。
     *
     * **定位先说清**：这里锁的是「normalizePoolText 别宽到吃真实状态」，不是判据 (a) 的修法。
     * 真正的修法在引擎侧注入点（GETTIME 语句改吃 `BuiltinHost.clockMillis`，
     * `StatementExecutor.kt:642-674`）加装置侧钉钟（`GoldenPipeline` 的 `E2E_PINNED_CLOCK_MILLIS`），
     * 不在测试侧归一 —— 钉钟之后归一是否还命中都不影响链相等，被依赖的是
     * [StateHash.rawPoolSnapshot] 那条「未归一原文两轮相同」的哨兵（见判据 (a)）。
     *
     * 正向：三条宿主派生文本各自把「只有宿主不同」的两条文本收敛成同一串。
     * 反向（判别力）：相对路径尾巴、普通游戏文案、连字符日期（引擎不产该形态）、数字位数不同的串
     * 必须原样保留 —— 归一若宽到把真实状态也抹平，(c)(d) 就白做了，所以这里双向都断言。
     * 幂等：归一一次与再归一一次同值，否则「同一状态重复求值逐位相同」的前提会从这里破。
     */
    @Test
    fun `驻留池归一只抹宿主派生文本不动真实状态`() {
        val pathA = "C:/Users/someone/AppData/Local/Temp/eratw-e2e-io1111111111/ERB/A.ERB"
        val pathB = "C:/Users/someone/AppData/Local/Temp/eratw-e2e-io2222222222/ERB/A.ERB"
        assertEquals(
            StateHash.normalizePoolText(pathA),
            StateHash.normalizePoolText(pathB),
            "同结构不同随机后缀的临时路径没收敛（R1/R2 失效）",
        )
        assertTrue(
            StateHash.normalizePoolText(pathA).endsWith("/ERB/A.ERB"),
            "归一把相对路径尾巴也吃掉了（真实写盘差异会在池里失明）",
        )
        // R3a 的夹具必须是**引擎真实产出的那一形**：GETTIME 语句与 GETTIMES 方法都用
        // 半角斜杠日期 + 空格 + 冒号时分秒（StatementExecutor.kt:669-672、HostMethods.kt:124-129）。
        // 上一轮这里写成连字符日期，而正则按斜杠匹配 → 夹具自己没被吃掉，红的是口径不是引擎（已修）。
        val clockA = "2026/10/19 22:35:12"
        val clockB = "2026/11/02 07:08:09"
        assertEquals(
            StateHash.normalizePoolText(clockA),
            StateHash.normalizePoolText(clockB),
            "GETTIME 写进 RESULTS 的时钟文本没收敛（R3a 失效）",
        )
        assertEquals(
            StateHash.HOST_CLOCK_TOKEN,
            StateHash.normalizePoolText(clockA),
            "R3a 命中后必须整段换成宿主时钟 token（只换半段会让两轮同形文本再分叉）",
        )
        // 判别力：连字符日期引擎不产出（GETTIME 家族两条产出口都是斜杠），池里出现只可能是脚本自己的
        // 文案 ⇒ 属真实状态，不许归一。归一宽到吃它就是「用测试抹平引擎」。
        val hyphenA = StateHash.normalizePoolText("2026-10-19 22:35:12")
        val hyphenB = StateHash.normalizePoolText("2026-11-02 07:08:09")
        assertEquals("2026-10-19 22:35:12", hyphenA, "非引擎形态的日期文本被改写（归一口径过宽）")
        assertEquals("2026-11-02 07:08:09", hyphenB, "非引擎形态的日期文本被改写（归一口径过宽）")
        assertNotEquals(hyphenA, hyphenB, "连字符日期被 R3a 一起吃掉了（真实文案会在池里失明）")
        val xmlA = "<GT><id>638955968000000000</id><NAME>R1</NAME></GT>"
        val xmlB = "<GT><id>638955999999999999</id><NAME>R1</NAME></GT>"
        assertEquals(
            StateHash.normalizePoolText(xmlA),
            StateHash.normalizePoolText(xmlB),
            "DT_TOXML 里的 18 位 ticks 主键没收敛（R3b 失效；钉钟后这条只作防御）",
        )
        assertEquals(
            StateHash.normalizePoolText("GOLDEN-IO-TEST"), "GOLDEN-IO-TEST",
            "无宿主派生片段的文案被改写了（归一口径过宽）",
        )
        assertNotEquals(
            StateHash.normalizePoolText("dat/chara_A.dat"),
            StateHash.normalizePoolText("dat/chara_B.dat"),
            "同长度不同内容的相对路径被归一成同一串（归一口径过宽）",
        )
        assertNotEquals(
            StateHash.normalizePoolText(xmlA),
            StateHash.normalizePoolText(xmlA.replace("R1", "R2")),
            "宿主 ticks 之外的列值差异被归一抹平（判别力丢失）",
        )
        val once = StateHash.normalizePoolText(pathA)
        assertEquals(once, StateHash.normalizePoolText(once), "归一不幂等：同一状态重复求值会漂移")
    }

    /**
     * 链形状自检 + 报告产出。
     *
     * `assertFailsWith` 那条是反向证明：空链取链头必须炸，不会静默给一个默认值
     * （否则 (b) 的常量比较可能撞上「两边都是 0」的假绿）。
     */
    @Test
    fun `链形状与报告产出`() {
        val run = runA
        val titles = titlesOf(run)
        assertTrue(titles.contains("ROOT-INFO"), "链里没有 ROOT-INFO 头节: " + titles)
        assertTrue(titles.contains("RUN-END"), "链里没有 RUN-END 尾节: " + titles)
        assertTrue(titles.contains("GOLDEN_CHARA"), "链里没有角色节 GOLDEN_CHARA，(c) 的角色列改写无从谈起: " + titles)
        assertEquals(model.units.size, run.nodes.size, "链节点数应等于分节单元数")
        assertFailsWith<RuntimeException> { HashChain().head() }

        val block = StringBuilder("\n## 链形状\n\n")
        block.append("- 节点数: ").append(run.nodes.size).append('\n')
        block.append("- 私有作用域键数: ").append(functionKeys.size).append('\n')
        block.append("- 用户变量注册失败数: ").append(run.registerFailures.size).append('\n')
        block.append("- 节执行 FAIL/ERROR/BLOCKED: ").append(run.failures.size).append('\n')
        block.append("- 渲染错误: ").append(run.renderErrors.size).append('\n')
        block.append("- 首节 notes 条数: ").append(run.nodes[0].digest.notes.size).append('\n')
        for (s in run.nodes[0].digest.notes.take(6)) block.append("  - note: ").append(s).append('\n')
        block.append("- 簇清单: ").append(clusterPairs(run.nodes[0].digest).joinToString(",")).append('\n')
        for (f in run.failures.take(8)) block.append("- 失败: ").append(f).append('\n')
        for (f in run.registerFailures.take(8)) block.append("- 注册失败: ").append(f).append('\n')
        block.append("\n### 每节簇表（跨轮定位用）\n")
        for (nd in run.nodes) {
            block.append("- #").append(nd.index).append(' ').append(nd.title).append(" [").append(nd.status)
                .append("] ").append(clusterTable(nd.digest)).append('\n')
        }
        appendReport(block.toString())
    }

    // ------------------------------------------------------------------
    // 装置：状态与纯函数
    // ------------------------------------------------------------------

    private companion object {
        /** test JVM 的 cwd = `core-vm/`，fixtures 在仓库根 —— 与 E2EGoldenTest 同的双路径探测。 */
        private val fixtureDir: java.nio.file.Path = sequenceOf(File("fixtures/golden"), File("../fixtures/golden"))
            .first { f: File -> f.isDirectory }.toPath().toAbsolutePath().normalize()

        private val pipeline: Result<GoldenModel> = runCatching { GoldenPipeline.build(fixtureDir) }

        private val model: GoldenModel
            get() = pipeline.getOrElse { e: Throwable -> throw AssertionError("L3 装置阻塞（GoldenPipeline 上游中间态）: " + e.message, e) }

        /** 报告落在 build 目录（契约 §3.3），不进 git。 */
        private val reportFile: File = File("build/l3-report.md")

        /** 分节步数闸与 E2E 的 `runSections` 同值。 */
        private const val STEP_CAP = 2_000_000L

        /**
         * 判据 (f) 的每节簇数下限：装置实测最小节是 ROOT-INFO（未建 `aux:lang.codec` /
         * `aux:fontsidecar` 两槽），21 簇 = 11 个固定簇 + 10 个 [StateHash.EXPECTED_AUX_SLOTS]。
         * 低于该值说明簇构造退化 —— 全量比对在「两边都是空/缺簇表」上会滑向恒真相等，
         * 先查 StateHash 再谈链。
         */
        private const val MIN_EXPECTED_CLUSTERS = 20

        /** 判据 (c) 的施加位置：第 3 个节点之后改写角色列，其后仍有 10+ 个节点可观察分叉。 */
        private const val FORK_AFTER_NODE = 3

        /**
         * 判据 (c) 折入链的单调血统计数增量：分叉轮在 #FORK_AFTER_NODE 之后每节带 +1，干净轮恒 0。
         * [runChain] 的自增与本常量必须同源 —— (c) 的报告把它写出来，改动这里要一并复核链公式。
         */
        private const val FORK_LINEAGE = 1L

        // ---- 登记常量（自产快照，非 PC 基准；首跑落定流程见类 KDoc）----
        //
        // 落定于 2026-10-20，链公式 v2（叠 lineage）+ 时钟注入点收口之后。
        // 这两个值只回答「装置自己有没有变」；语义权威仍是 specs 下的 yaml 规约、反编译 C# 与 L2 逐字节对拍。
        // 口径注：装置侧时区由 GoldenPipeline 钉 UTC（BuiltinHost.zoneId() 注入点，与 clockMillis
        // 同协议），链常量不再带机器/时区印记。
        // 2026-10-20 g-als 重落：.als 别名装载后 Chara0.csv 的 基礎,情緒,1500 / 基礎,憤怒,1000 生效
        // （Base.als:11/:15），sys-chara 首节起即与旧链不同；非装置回归。
        // 2026-10-20 g-csvsize 再落：VariableSize.csv 全语义生效（STR 20000 / CFLAG 10000 /
        // EXP 200 / SOURCE 250 / 2D 100x100 等），sys-int/sys-chara 可见宽度与值同变；非装置回归。
        // 2026-10-20 h-savebinary 三落：`USE THE BINARY FORMAT FOR SAVING DATA`（emuera.config:60 = YES）
        // 首次喂进 VarConfig.systemSaveInBinary ⇒ 仅 StateHash 的 meta 簇变化（其余 20 簇与 15 节文本一字不动），
        // 同时字符串常量表 0→86 项使若干 #DIM 注册成功。非装置回归。
        // 2026-10-20 m6b-data 落：CHARA 模板改按 CharacterIntArrayLength 具名槽取界
        // （此前误用 CSV 名表槽，3,134 行合法模板值被判越界丢弃）；E2E 仍 610/610。
        // 2026-10-22 j-dispatch 四落：GETMILLISECOND / GETSECOND 补上本地时区偏移
        // （原版 `DateTime.Now.Ticks / 10000`，spec: FunctionMethodCreator.cs:4008 是**本地墙上时间**）
        // 且 DT 行主键 ticks 同源 ⇒ 仅末节（时间/DT 读数所在节）状态变，链头一字不动
        // （16d9789907e8b2a1 保持）。旧值按 UTC 钉，比 PC 少 28 800 000 ms = 8 小时。非装置回归。
        // 2026-10-20 两组长合流五落：m6b-data × j-dispatch 各自钉了一侧，合流后重落
        // 4665fbc6 / bc9b8449 —— 链头与 b-data 单路一致（j-dispatch 预告的「链头一字不动」应验），
        // 链尾再变一次（时区偏移只影响末节时钟/DT 读数）。非装置回归。
        // 2026-10-21 M-L5 measure 六落：文本度量三条 GDI+ 布局规则（按 U+000A 分行取最宽行、
        // 逐行裁尾随空白、`Mn`/`Me` 归零步进）+ `GGETFONT` 回读解析名 + `GDRAWTEXT` 回退字体
        // 来源改取 `Config.FontName` ⇒ 进 hash 的 GFX 尺寸读数变，链头一字不动、链尾重落
        // 5369588855512167998（hex 4a849c9d62b2f63e）。E2E 仍 610/610、corpusSmoke GATE GREEN、
        // bootSmoke 首差一字未动 ⇒ 属**正确的语义演化**，非装置回归。
        // 2026-10-22 origin/main@b7771dc 七落：bug 狩猎波 8 笔（一元负号复刻双求值 ⇒ -RAND 的 RNG
        // 序列随原版口径移动；PICKUPCHARA/SORTCHARA 指针重绑；流程控制装载期边界；限时输入计时）
        // ⇒ 末节状态再变一次，链头 4665fbc6 一字不动（hex 4665fbc6749112ad 仍命中）、链尾重落
        // 7056038957426237280（hex 61ec160c1ab55f60）。合流后闸另有两处改钉：OperatorAuditTest 的
        // EX-3 现状钉桩转正向（副作用 1→2，Expr.kt:168-175 已双求值）。非装置回归。
        // 2026-10-22 wave-3 六落：PICKUPCHARA 指针重绑把未选中的 MASTER/ASSI/TARGET 归 -1
        // （634b9da，spec: VariableEvaluator.cs:1290-1293）⇒ sys-int-global 簇自 #4 GOLDEN_CHARA
        // 起可见（指针槽在 gInt 全局列），#9 GOLDEN_MISC 起被后续节复位收敛（E2E 610/610 屏面不变）。
        // 链头一字不动（5072737385080492717 保持）。非装置回归。
        // 组长实测（main@合流树）：对面树钉的 -6725232533149135591 在本树不成立 —— 本树含
        // origin/main 尚未收编的 measure/j-dispatch 等本地 43 笔，hash 输入面不同；合并树上
        // 实测链尾 9076496448132836263（hex 7df6316e240d6ba7），订正回本树实测值
        // （对面 wave-4 七落钉的 -7359774946179899929 同理不适用本树）。
        // 2026-10-22 wave-4 七落：config 路（96ec096）新增 aux 槽 `config.chain`
        // （ConfigMethods.AUX_SLOT，运行期词法配置 SystemAllowFullSpace/SystemIgnoreTripleSymbol 的
        // 生效载体）⇒ meta 簇的「aux 槽名名单」逐节变 + 新簇 aux:config.chain。
        // 与 origin/main 基线（5082945）逐节逐簇 diff：除 meta 与该新簇外 sys-*/user-*/string-pool/
        // aux 其余簇值一字不动，纯装置面注册变化；E2E 610/610 不变。链头一字不动（5072737385080492717 保持）。
        // 2026-10-22 第六轮合流（origin/main@045d9cb7）后链尾重落：并入帧发布闸/GCREATEFROMFILE
        // ContentDir/正则方言探测后末节状态变了（本机两次运行 09b85b11816d0079 稳定一致）。
        // 对面 BOARD 已明示「L3 链尾跨环境不稳转交」—— 跨机重钉是现状协议（装置自产快照），
        // 根治待把链尾纳入前先做跨环境归一化（嫌疑：aux:fontsidecar / aux:gfx 等装置面簇）。
        // 2026-10-22 合流 origin/main@6332185（81 笔）：末节状态再变，链头一字不动
        // （5072737385080492717 / hex 4665fbc6749112ad 仍命中），链尾重落
        // 4549421489305448735（hex 3f22cabd9b94711f）。本机两轮 A/B 逐节自洽（报告 (a) 节
        // 差异「无差异」）、三轮 G 全量相同 ⇒ 属装置面重钉，非执行回归。附注：首跑时
        // `ADDCHARA 失败：未定义的角色 NO 0` 是我把 Git Bash 风格路径 `/c/...` 传进 JVM
        // （被解析成 `C:\c\...` ⇒ `GameDataLoader` 返回 null ⇒ 无角色模板）的调用姿势问题，
        // 改用 `cygpath -w` 的 Windows 路径后消失，与合并无关。
        // 2026-10-22 八落（跨环境归一化，audit2/tail）：链尾跨机不稳根因坐实为**时区**——
        // GETTIME 族函数、GETTIME 语句、DT 行主键 netTicks 三处从 ZoneId.systemDefault() 取区，
        // 装置输入随机器变。差分实验（同机两轮）：Asia/Shanghai 链尾 09b85b11816d0079 vs
        // America/New_York 9a02b386b289cc4d，链头不变；逐节首差 #10 GOLDEN_XMLMAP
        // （DT 行主键 ticks 的时区折算写进 sys-int-global 的 RESULT 格 + user-private XDATA 文本
        // + string-pool）。修法：BuiltinHost 新增 zoneId() 注入点（默认 systemDefault 保持产线语义），
        // GoldenPipeline 钉 UTC（与 E2E_PINNED_CLOCK_MILLIS 同一套不变式）；core-vm 测试任务另留
        // ERATW_TEST_TZ 旋钮供跨环境取证。本落链头一字不动，链尾重落；
        // ERATW_TEST_TZ=America/New_York 复跑 (b) 硬闸仍绿 ⇒ 链尾不再随时区变。非装置回归。
        // 2026-10-22 合并 origin/main@f8a23dd4（74 笔）后再落：上游 81 笔合流那落钉的
        // 4549421489305448735 与本路时区归一化叠加 ⇒ 链尾重落 ce71c70dd6e6709e，
        // 链头仍一字不动（5072737385080492717 / hex 4665fbc6749112ad）。
        // 2026-10-23 HOST-01 新增 aux:skipState 簇 ⇒ 链头/链尾重落（hex ac717c6f24d067a4 / fd7c20a6e0039150）。
        // 2026-10-24 M11-E `dttoxml` 落地 DT_TOXML 写侧的 .NET 真实组成（去 standalone、补两处
        // msdata:CaseSensitive、主键列去 ReadOnly/AutoIncrement、带 default 的可空列补 minOccurs）
        // ⇒ GOLDEN_XMLMAP 节起的 aux:xml 池内容变。
        // 2026-10-24 合流（收编 origin/main@978d9f07 的 107 笔）后重落：链头 `94d30da083d7c2f5`
        // 与对面登记**逐值相同**（前 10 节没被动过），链尾从对面的 `d9cd2398c725ccd9` 重落到
        // `b2d8192e450ca4f0` —— 分歧首现于 #10 GOLDEN_XMLMAP，正是上面那笔 DT_TOXML 写侧的
        // 预期后果，不是回归。判定力未减：(a)(c)(d)(e)(f) 五条判据全绿，见 l3-report.md
        // （(f) 的 1,179 项簇级 + 192 项节级全量比对「差异清单：空」）。
        // 2026-10-28 T2-64-D 后重落**只有链尾**：链头 `94d30da083d7c2f5` 一字未动，链尾
        // `-5559666053471230736` → `4814083816222933021`（hex 42cf0fb988ecc01d）。原因与既有口径一致：
        // [StateHash.rawPoolSnapshot] 只折进**末态**，而扁平池改成「分片惰性」后驻留池的构成
        // （哪些语句被解码过、串驻留到哪一步）随执行路径变化；游戏可见输出逐字节不变由
        // `E2EGoldenTest`（610 断言）+ `dualPathAudit`（20 万条 0 差异）单独背书。
        private const val REGISTRY_READY = true
        private const val REGISTERED_HEAD = -7722813952952384779L
        private const val REGISTERED_TAIL = 4814083816222933021L

        /** 私有 `#DIM` 的归属函数键（`FuncMeta.key` 大写去重排序），喂给 [StateHash] 的 user-private 簇。 */
        private val functionKeys: List<String> =
            model.table.all().map { m: FuncMeta -> m.key.uppercase() }.distinct().sorted()

        private val runA: ChainRun by lazy { runChain(forkAfter = -1, tag = "A") }
        private val runB: ChainRun by lazy { runChain(forkAfter = -1, tag = "B") }

        /**
         * 判据 (f) 的**确定性守卫轮** G：第三遍干净协议执行。lazy 的物化序由判据 (f) 的测试把住
         * （A → B → FORK → G，见 `三轮逐节链值与逐簇摘要全量相同`），不参与 (b) 的登记常量比较。
         */
        private val runG: ChainRun by lazy { runChain(forkAfter = -1, tag = "G") }

        private val runFork: ChainRun by lazy { runChain(forkAfter = FORK_AFTER_NODE, tag = "FORK") }

        /** 判据 (d)(e) 共用的开局态运行时（装载 CSV + 预种角色 + aux 播种，但不执行任何节）。 */
        private val probeRuntime: ProcessRuntime by lazy {
            GoldenPipeline.newRuntime(model, LineConsoleSink(), stepCap = STEP_CAP)
        }

        private val probeStore: VariableStore
            get() = probeRuntime.store

        private val probeKeys: List<String>
            get() = functionKeys

        init {
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(
                "# L3 状态哈希链报告（CONTRACT-M4-A §3 装置 · build 目录，非仓库文档）\n\n" +
                    "- fixtures: " + fixtureDir + "\n" +
                    "- 链 genesis: " + StateHash.hex(HashChain.GENESIS) + "\n" +
                    "- 登记常量就绪: REGISTRY_READY=" + REGISTRY_READY + "（false 时 (b) 判据报 SKIPPED，需首跑落定）\n",
                Charsets.UTF_8,
            )
        }

        // ------------------------------------------------------------------
        // 跑链
        // ------------------------------------------------------------------

        private fun runChain(forkAfter: Int, tag: String): ChainRun {
            val console = LineConsoleSink()
            val rt: ProcessRuntime = GoldenPipeline.newRuntime(model, console, stepCap = STEP_CAP)
            val keys = functionKeys
            val chain = HashChain()
            val failures = ArrayList<String>()
            val renderErrors = ArrayList<String>()
            var probe: ForkProbe? = null
            var idx = 0
            // 单调血统：装置自己施加过的改写次数。干净轮（forkAfter = -1）恒 0，两轮输入序列逐字节相同，
            // 所以 (a) 的确定性不靠「链公式里没有额外输入」保证，而是靠「额外输入在干净轮里恒 0」保证。
            var lineage = 0L
            for (u in model.units) {
                val status: String
                val compileFail = findCompileFailure(model.compileFailures, u.sigKey)
                if (u.skippable) {
                    status = "SKIPPED"
                } else if (compileFail != null) {
                    status = "BLOCKED_COMPILE"
                    failures.add(tag + "/" + u.title + ": " + compileFail)
                } else {
                    status = try {
                        rt.callFunction(u.sigKey)
                        "PASS"
                    } catch (e: CodeEE) {
                        failures.add(tag + "/" + u.title + ": CodeEE " + (e.message ?: ""))
                        "FAIL"
                    } catch (t: Throwable) {
                        failures.add(tag + "/" + u.title + ": " + t::class.java.simpleName + " " + (t.message ?: ""))
                        "ERROR"
                    }
                }
                val d = StateHash.digest(rt.store, keys)
                chain.append(u.title, status, d, lineage)
                for (err in d.errors) renderErrors.add(tag + "/" + u.title + ": " + err)
                if (idx == forkAfter) {
                    // 同状态再求一次：既验证幂等（求哈希必须是纯观察），又给分叉证据一个干净前像。
                    val before = StateHash.digest(rt.store, keys)
                    val desc = forkMutate(rt.store)
                    val after = StateHash.digest(rt.store, keys)
                    probe = ForkProbe(idx, desc, expectedDivergeCluster(desc), before, after)
                    // 改写只在节点 k 的状态里可见，其后每一节都由装置「动过手」——把这个事实记进血统。
                    lineage += FORK_LINEAGE
                }
                idx++
            }
            val nodes = ArrayList<ChainNode>()
            for (nd in chain.nodes()) {
                nodes.add(ChainNode(nd.index, nd.title, nd.status, nd.stateHash, nd.chain, nd.digest))
            }
            val regFailures = ArrayList<String>()
            regFailures.addAll(GoldenPipeline.lastRegisterFailures)
            // 末态驻留池的**未归一**原文（判据 (a) 的根因哨兵；为什么必须是未归一的口径，
            // 见 StateHash.rawPoolSnapshot KDoc —— 归一只作防御、命中数不能当证据）。
            return ChainRun(tag, nodes, failures, regFailures, renderErrors, probe, StateHash.rawPoolSnapshot(rt.store))
        }

        /**
         * 判据 (c) 的改写：优先动角色数据列（契约点名的「改写任一角色列」）；
         * 该时刻没有角色就退回动全局整数列（描述如实记录，期望簇随之切换）。
         */
        private fun forkMutate(store: VariableStore): String {
            if (store.characters.count > 0) {
                val cur = runCatching { store.readInt("BASE", longArrayOf(0L, 0L)) }.getOrDefault(0L)
                store.writeInt("BASE", longArrayOf(0L, 0L), cur + 1L)
                return "BASE:0:0 = " + (cur + 1L)
            }
            val cur = store.readInt("MONEY", longArrayOf(0L))
            store.writeInt("MONEY", longArrayOf(0L), cur + 1L)
            return "MONEY:0 = " + (cur + 1L)
        }

        private fun expectedDivergeCluster(mutation: String): String =
            if (mutation.startsWith("BASE")) "sys-chara" else "sys-int-global"

        /**
         * 编译失败清单里找该节首条，口径与 `E2EGoldenTest.runSections` 一致
         * （`startsWith("<sigKey> ")`）；写成显式循环是为了避开 K2 在该处对 `it` 的歧义判定。
         */
        private fun findCompileFailure(list: List<String>, sigKey: String): String? {
            val prefix = sigKey + " "
            for (item in list) {
                if (item.startsWith(prefix)) return item
            }
            return null
        }

        // ------------------------------------------------------------------
        // 视图与诊断
        // ------------------------------------------------------------------

        private fun titlesOf(run: ChainRun): List<String> = run.nodes.map { n: ChainNode -> n.title }

        private fun chainsOf(run: ChainRun): List<Long> = run.nodes.map { n: ChainNode -> n.chain }

        private fun statesOf(run: ChainRun): List<Long> = run.nodes.map { n: ChainNode -> n.stateHash }

        private fun clusterPairs(d: StateDigest): List<String> =
            d.clusters.map { c: ClusterDigest -> c.name + "=" + StateHash.hex(c.hash) }

        /** 两轮链首个不等节点 + 首个差异簇（定位「哪一节的哪一簇不稳定」）。 */
        private fun divergence(a: ChainRun, b: ChainRun): String {
            val n = minOf(a.nodes.size, b.nodes.size)
            for (i in 0 until n) {
                val x = a.nodes[i]
                val y = b.nodes[i]
                if (x.chain != y.chain || x.stateHash != y.stateHash) {
                    val cluster = x.digest.firstDiffCluster(y.digest) ?: "(仅链值差/前驱传导)"
                    return "首差节点 #" + i + " " + x.title + "：状态 " + StateHash.hex(x.stateHash) +
                        " vs " + StateHash.hex(y.stateHash) + "，链 " + StateHash.hex(x.chain) +
                        " vs " + StateHash.hex(y.chain) + "，首个差异簇=" + cluster
                }
            }
            if (a.nodes.size != b.nodes.size) return "节点数不同：" + a.nodes.size + " vs " + b.nodes.size
            return "无差异"
        }

        /**
         * 两轮的**全部**差异清单（判据 (f)；不取首差，与 [divergence] 的首差口径相对）：
         * 逐节标题 / 状态 / 状态哈希 / 链值 + 每节**全部**簇（簇序逐位名字+哈希、簇名集合对称差）
         * + 节执行失败清单 + 注册失败清单 + 未归一驻留池原文。
         *
         * 池只报首条差异下标：池是 append-only（StringTable 只增不减），一条插入会让其后全部错位，
         * 全量罗列只会淹没定位信息；「逐条枚举」的判据面是链值与逐簇摘要，池的完整相等由
         * [exhaustiveMismatches] 的条数比较 + 首条差异 + (a) 的全列表断言共同覆盖。
         */
        private fun exhaustiveMismatches(x: ChainRun, y: ChainRun, out: MutableList<String>) {
            val pair = x.tag + " vs " + y.tag
            if (x.nodes.size != y.nodes.size) {
                out.add(pair + " 节点数不同: " + x.nodes.size + " vs " + y.nodes.size)
            }
            val n = minOf(x.nodes.size, y.nodes.size)
            for (i in 0 until n) {
                val cx = x.nodes[i]
                val cy = y.nodes[i]
                val prefix = "[" + pair + "] #" + i + " " + cx.title + ": "
                if (cx.title != cy.title) out.add(prefix + "节名不同 " + cx.title + " vs " + cy.title)
                if (cx.status != cy.status) out.add(prefix + "节状态不同 " + cx.status + " vs " + cy.status)
                if (cx.stateHash != cy.stateHash) {
                    out.add(prefix + "状态哈希不同 " + StateHash.hex(cx.stateHash) + " vs " + StateHash.hex(cy.stateHash))
                }
                if (cx.chain != cy.chain) {
                    out.add(prefix + "链值不同 " + StateHash.hex(cx.chain) + " vs " + StateHash.hex(cy.chain))
                }
                val xs = cx.digest.clusters
                val ys = cy.digest.clusters
                if (xs.size != ys.size) out.add(prefix + "簇数不同 " + xs.size + " vs " + ys.size)
                val m = minOf(xs.size, ys.size)
                for (j in 0 until m) {
                    val l = xs[j]
                    val r = ys[j]
                    if (l.name != r.name) {
                        out.add(prefix + "簇序第 " + j + " 位名字不同 " + l.name + " vs " + r.name)
                    } else if (l.hash != r.hash) {
                        out.add(prefix + "簇 " + l.name + " 不同 " + StateHash.hex(l.hash) + " vs " + StateHash.hex(r.hash))
                    }
                }
                for (lc in xs) {
                    var found = false
                    for (rc in ys) {
                        if (rc.name == lc.name) {
                            found = true
                            break
                        }
                    }
                    if (!found) out.add(prefix + "仅 " + x.tag + " 有簇 " + lc.name)
                }
                for (rc in ys) {
                    var found = false
                    for (lc in xs) {
                        if (lc.name == rc.name) {
                            found = true
                            break
                        }
                    }
                    if (!found) out.add(prefix + "仅 " + y.tag + " 有簇 " + rc.name)
                }
            }
            if (x.failures != y.failures) out.add(pair + " 节执行失败清单不同: " + x.failures + " vs " + y.failures)
            if (x.registerFailures != y.registerFailures) {
                out.add(pair + " 用户变量注册失败清单不同: " + x.registerFailures + " vs " + y.registerFailures)
            }
            if (x.rawPool.size != y.rawPool.size) {
                out.add(pair + " 未归一驻留池条数不同: " + x.rawPool.size + " vs " + y.rawPool.size)
            }
            val p = minOf(x.rawPool.size, y.rawPool.size)
            for (i in 0 until p) {
                if (x.rawPool[i] != y.rawPool[i]) {
                    out.add(
                        pair + " 未归一驻留池首条差异 @下标 " + i + ": " +
                            x.rawPool[i].take(120) + " vs " + y.rawPool[i].take(120),
                    )
                    break
                }
            }
        }

        /** 清单里是否存在同时含两个片段的行（判据 (f) 自证用例的定位断言用）。 */
        private fun anyLineContaining(lines: List<String>, one: String, two: String): Boolean {
            for (s in lines) {
                if (s.contains(one) && s.contains(two)) return true
            }
            return false
        }

        /** 诊断清单渲染成报告/断言消息用的多行子弹串。 */
        private fun asBullets(lines: List<String>): String {
            val sb = StringBuilder()
            for (s in lines) sb.append("- ").append(s).append('\n')
            return sb.toString()
        }

        /**
         * 判据 (f) 判别力自证的**合成轮**：两条 16 节数据轮（纯数据，不起 runtime）。
         *
         * 植入（节点编号与真装置对齐：#9 GOLDEN_MISC / #10 GOLDEN_XMLMAP 是历次首差的历史位点，
         * `#11 GOLDEN_GVIEW` 起新增 aux:fontsidecar 槽 —— 见 StateHash 类 KDoc 与
         * build/l3-report.md 的每节簇表）：
         * - 首差位点 #9：轮 S2 的 `meta` 簇值与总哈希不同 → #9 起状态与链值级联分叉；
         * - 第二条独立分叉位点 #11：轮 S2 多一个簇 `aux:extra`（簇名集合差异）；
         * - 驻留池下标 5：轮 S2 多一条条目（池条数与首条差异都要被点名）。
         */
        private fun syntheticRounds(): Pair<ChainRun, ChainRun> {
            val count = 16

            fun digestOf(node: Int, variant: Int): StateDigest {
                val clusters = ArrayList<ClusterDigest>()
                clusters.add(ClusterDigest("meta", if (variant == 2 && node == 9) 0x9001L else 0x1L))
                clusters.add(ClusterDigest("sys-chara", 0x2L))
                clusters.add(ClusterDigest("string-pool", 0x3L))
                if (variant == 2 && node == 11) clusters.add(ClusterDigest("aux:extra", 0x4L))
                var total = 100L + node.toLong()
                if (variant == 2 && node >= 9) total += 7L
                if (variant == 2 && node == 11) total += 13L
                return StateDigest(clusters, total, ArrayList(), ArrayList())
            }

            fun roundOf(variant: Int): ChainRun {
                val nodes = ArrayList<ChainNode>()
                var prev = 0x5EEDL
                for (i in 0 until count) {
                    val d = digestOf(i, variant)
                    val h = Fnv1a64(prev)
                    h.mixLong(i.toLong())
                    h.mixLong(d.total)
                    prev = h.result()
                    nodes.add(ChainNode(i, "SYN-" + i, "PASS", d.total, prev, d))
                }
                val pool = ArrayList<String>()
                pool.add("p0")
                pool.add("p1")
                pool.add("p2")
                pool.add("p3")
                pool.add("p4")
                if (variant == 2) pool.add("extra")
                pool.add("p5")
                return ChainRun("S" + variant, nodes, ArrayList(), ArrayList(), ArrayList(), null, pool)
            }
            return Pair(roundOf(1), roundOf(2))
        }

        /**
         * 两轮**未归一**驻留池的首个不同条目（下标 + 两边原文截断到 120 字符）。
         * 只给定位信息：一条就够把「哪一刻的时钟文本漏进来了」指出来，全量池会淹掉报告。
         */
        private fun firstPoolDiff(a: ChainRun, b: ChainRun): String {
            val n = minOf(a.rawPool.size, b.rawPool.size)
            for (i in 0 until n) {
                if (a.rawPool[i] != b.rawPool[i]) {
                    return "池下标 " + i + "：" + a.rawPool[i].take(120) + " vs " + b.rawPool[i].take(120)
                }
            }
            if (a.rawPool.size != b.rawPool.size) return "池条数不同：" + a.rawPool.size + " vs " + b.rawPool.size
            return "(无差异)"
        }

        private fun clusterTable(d: StateDigest): String =
            d.clusters.joinToString(" ") { c: ClusterDigest -> c.name + "=" + StateHash.hex(c.hash) }

        /**
         * 逐节的池归一命中条数（[StateHash.digest] 写在 notes 里的 `pool-normalized:N`）。
         * 只读 notes，不进哈希输入；命中为 0 的节不列，报告里一眼能看出「哪一节的池里有宿主文本」。
         */
        private fun poolNormalizationHits(run: ChainRun): String {
            val parts = ArrayList<String>()
            for (nd in run.nodes) {
                var hits = 0
                for (s in nd.digest.notes) {
                    if (s.startsWith("pool-normalized:")) {
                        val v = s.substringAfter(":").toIntOrNull()
                        if (v != null) hits += v
                    }
                }
                if (hits > 0) parts.add("#" + nd.index + "=" + hits)
            }
            return if (parts.isEmpty()) "无命中" else parts.joinToString(" ")
        }

        private fun appendReport(section: String) {
            reportFile.appendText(section, Charsets.UTF_8)
        }

        private fun nodeTable(run: ChainRun): String {
            val sb = StringBuilder()
            sb.append("| # | 节 | 状态 | 状态哈希 | 链值 |\n")
            sb.append("|---|---|---|---|---|\n")
            for (nd in run.nodes) {
                sb.append('|').append(nd.index).append(" | ").append(nd.title).append(" | ").append(nd.status)
                    .append(" | ").append(StateHash.hex(nd.stateHash)).append(" | ").append(StateHash.hex(nd.chain))
                    .append(" |\n")
            }
            return sb.toString()
        }

        private fun ChainRun.head(): Long = this.nodes.first().chain

        private fun ChainRun.tail(): Long = this.nodes.last().chain
    }
}
