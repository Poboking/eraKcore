package com.eratw.port.l3

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.PseudoKind
import com.eratw.port.core.vars.RefUserVar
import com.eratw.port.core.vars.Storage
import com.eratw.port.core.vars.UserVar
import com.eratw.port.core.vars.VarDef
import com.eratw.port.core.vars.VarType
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.xml.XmlNode
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * L3 状态哈希装置（CONTRACT-M4-A §3.1；实施方案 §6「L3 哈希链回归」的第三级）。
 *
 * 只存在于 test 源集：**不修改引擎一行**，全部经 [VariableStore] 的公开读写面
 * （`readInt` / `readStr` / `getLength` / `allUserVars` / `characters` / `pool` / `aux`）
 * 观察状态，因此内部布局重构（ADR-004 列式存储、在办 VM-1）不会影响本装置；
 * 语义口径变化（在办 PARSER-1 的私有作用域）会如实反映到链值上。
 *
 * ## 覆盖项（每条都进哈希输入）
 *
 * | 簇 | 内容 | 依据 |
 * |---|---|---|
 * | `meta` | 调用深度、角色数、驻留池条目数、当前函数、[VarConfig] 三个开关、aux 槽名名单 | 形状自检 |
 * | `sys-int-global` | `Storage.G_INT1/G_INT2/G_INT3` 全部元素（逐下标经 `readInt`） | VariableStore.kt:36-40 |
 * | `sys-str-global` | `G_STR0/G_STR1` 全部元素的**字符串内容**（不是池下标） | VariableStore.kt:41-42, StringTable.kt:28-42 |
 * | `sys-chara` | `C_INT0/C_STR0/C_INT1/C_STR1/C_INT2`：按**角色位置**（0 直到 CHARANUM-1）× 各数据列 × 元素逐格读 | CharacterTable.kt:27-49, VariableRegistry.kt:175-263 |
 * | `sys-csv-const` | 29 组 CSV 名字表（`CSV_CONST` 存储）全部名字 | VariableStore.kt:138, VariableRegistry.kt:353-370 |
 * | `sys-pseudo` | 伪变量里属于**变量层**的那些（CHARANUM / LASTLOAD_* / ISTIMEOUT / WINDOW_TITLE / MONEYLABEL / DRAWLINESTR / EMUERA_VERSION / DEBUG_* / GAMEBASE_*） | VariableStore.kt:910-963 |
 * | `lastload` | `lastLoadVersion` / `lastLoadNo` / `lastLoadText` 独立成簇（读档门禁用，契约点名） | VariableStore.kt:82-84, resetValues :1118-1120 |
 * | `roster` | SAVEDATA / GLOBAL **名单**（系统 def 标记 + 用户 `#DIM SAVEDATA` / `#DIM GLOBAL` 名字），按名排序 | VarDef.kt:99-100, VariableStore.kt:683-684, 763-765 |
 * | `user-global` | `allUserVars()`：静态/动态/CHARADATA/REF 四类用户变量的全部元素内容 | VariableStore.kt:758, UserVariables.kt:426-696 |
 * | `user-private` | 按函数键排序后逐键取 `privateUserVars(key)`，再逐元素；作用域外的动态变量记固定标记 | VariableStore.kt:760-761 |
 * | `string-pool` | 驻留池**按下标序**的全部字符串，逐条先过 [normalizePoolText] 的「宿主派生内容归一」（见下节）+ 条目数 | StringTable.kt:15-56, StatementExecutor.kt:669-672 |
 * | `aux:<槽名>` | `store.aux` 每个槽（XML / Map / DT / 图形 / Sprite / CBG / 按键切换 / FLOWINPUT / CONFIG / 位图缓存），名字表与 map 型容器**按内容排序**后纳入；未接线的期望槽记 0 | RuntimeAux.kt:19-39, XmlPool.kt:21-50, GraphicsBuffer.kt:351-531 |
 *
 * ## 不纳入项与理由（契约 §3.1 要求逐条列明）
 *
 * 1. **宿主时钟派生格：`RESULT` 元素 0 与 `RESULTS` 元素 0**。
 *    每个「裸方法语句」都把返回值写进 `RESULT:0`（字符串方法写 `RESULTS:0`），而 GOLDEN 套件里
 *    存在返回值本身就不是 ERB 语义而是宿主态的方法：`GETTIME` / `GETTIMES` / `GETMILLISECOND` /
 *    `GETSECOND`（`HostMethods.kt:117-138`，取 `BuiltinHost.clockMillis()`，缺省
 *    `System.currentTimeMillis()`，`Builtins.kt:30`）、`DT_ROW_ADD`（返回
 *    `Utils.TimePoint()` 折算的时间戳主键，`MapDataTableMethods.kt:488-501` +
 *    `Utils.cs:464-471`）、`CLEARMEMORY`（返回 JVM 已用堆差值，`Builtins.kt:102-112`）。
 *    这三类值让「同进程两次运行」「zh 与默认 locale 两次运行」的链**必然**不同，而它们又不该被
 *    修正（引擎行为是对的，是宿主源不确定）。故这两格在哈希输入里替换成固定标记
 *    [HOST_DERIVED_MARK]（**只影响哈希输入，不回写状态、不改变脚本可见行为**）。
 *    `RESULT:1` 及以后、`RESULTS:1` 及以后照常纳入；位标记 / RESULT 的其余用法不受影响。
 *    L2（逐字节 golden 文本对拍）继续覆盖这些格的文字化输出，本装置只放弃「时钟/堆派生」这一格。
 *    **口径更新（本轮）**：时钟这一路已在装置侧钉死（`GoldenPipeline` 的 clockMillis 覆写，见下节），
 *    所以该标记对**时钟派生格**退化为防御层，不再是「让两轮相等」的那一层；对**堆派生格**
 *    （`CLEARMEMORY`，JVM 已用堆差值，装置无法钉）它仍是唯一屏障。
 * 2. **打印缓冲 / 控制台文字流**（`ConsoleSink`、`LineConsoleSink`、`store.lineCount` 与
 *    `store.lineCountSource`、`LINECOUNT` 伪变量、HTML 显示行、样式快照）。
 *    理由：这是 L2 的判据面，进 L3 只会让两级验证互相耦合；且 `lineCountSource` 直连宿主 console。
 * 3. **RNG 内部态**（SFMT19937 的 624 字缓冲与索引、`RAND` 伪变量求值）。
 *    理由：`RAND` 是**读取即消耗**的伪变量（`VariableStore.kt:966-978`），哈希阶段求值会推进序列，
 *    破坏「哈希是纯观察」的前提。RNG 的**外部可见状态**已由 `RANDDATA`（`G_INT1` 下标 64，
 *    DUMPRAND 写入，`VariableSizes.kt:80`）覆盖。
 * 4. **函数局部变量 LOCAL/LOCALS/ARG/ARGS（`Storage.L_INT/L_STR`）与 `localLengths` 声明表**。
 *    理由：局部实例存活在调用帧内（`VariableStore.kt:59-64`），节边界处 `callDepth()` 恒为 0
 *    （`ProcessRuntime` 的根级调用在 finally 里弹帧），此时无可读局部；`localLengths` 是函数声明
 *    派生的静态元信息，不随运行演化。帧深一旦非 0，`meta` 簇里的 `callDepth` 就会变，报告可查。
 * 5. **宿主侧对象图**（`BuiltinHost` 实现、`FileOps`/临时目录路径、`SystemOps`、`ConsoleSink`、
 *    `ProcessRuntime`/`VirtualMachine`/`ExecContext`/`Program`）。
 *    理由：E2E 每轮 `Files.createTempDirectory("eratw-e2e-io")` 都给不同路径（
 *    `GoldenPipeline.kt:663`），一旦进入哈希输入，(a) 两次运行自洽立刻不成立。
 *    [RuntimeAux] 的槽里若出现这类对象，通用渲染器按 opaque 策略只记类名标记并登记到
 *    [StateDigest.notes]，不递归、不取内容。
 *    **补充（本轮取证结论）**：这一条原本只管 aux 槽里的宿主对象，管不住「宿主派生文本被
 *    intern 进驻留池」这条路——`writeStr` 一律 `pool.intern(value)`（`VariableStore.kt:203-214`），
 *    于是 `RESULTS:0` 虽然按 [HOST_DERIVED_MARK] 替代了**格子值**，它写入时经过的那份原文
 *    仍留在池里。池簇因此必须自己做按内容归一，规则见「string-pool 的宿主派生内容归一」。
 * 6. **分配性副产品**：角色表 `allocatedCapacity`、各列 `data` 数组的**超出 count 的尾部**、
 *    [com.eratw.port.core.vars.StringTable] 的 `HashMap` 桶数。
 *    理由：它们是扩容历史不是 ERB 可见状态；只哈希 `0 直到 count` 的行（`sys-chara` 走
 *    `readInt/readStr` 的公开面，天然只覆盖合法位置）。
 * 7. **`VarConfig` 里的默认值数组本体**（palamLvDef/expLvDef/pbandDef/relationDef）。
 *    理由：它们的**效果**已落在 PALAMLV/EXPLV/PBAND/RELATION 的实际列里（`resetValues`
 *    `VariableStore.kt:1103-1116`），重复纳入只会掩盖「列被脚本改过」这一信号的反差。
 *
 * ## string-pool 的宿主派生内容归一（**防御层**；真正修法在引擎侧注入点，不在测试侧归一）
 *
 * **现象（首轮取证时）**：同一进程两次跑节，#0…#8 逐值相同，#9 `GOLDEN_MISC` 起分叉，首个差异簇 = `string-pool`；
 * 池归一上线后首差推进到 #10 `GOLDEN_XMLMAP`，首个差异簇 = `sys-int-global`。
 * **取证的链路**（不是「宿主临时目录路径」，是**宿主时钟**；两条都在下面归一掉了）：
 * `GOLDEN_09_MISC.ERB:36` 的 `GETTIME` 语句当时在 `StatementExecutor` 里直接读
 * `java.time.LocalDateTime.now()`，**绕过** [com.eratw.port.core.fn.BuiltinHost.clockMillis] 注入点
 * （`BootHosts.kt:87-88` 的 KDoc 明写那个注入点就是「对应 GETTIME 家族」的），于是宿主侧钉不住时钟；
 * 时钟文本经 `writeStr` → `pool.intern`（`VariableStore.kt:202-214`）进驻留池（格子侧已被
 * [HOST_DERIVED_MARK] 屏蔽，池侧漏）。池是 append-only（`StringTable.kt:16-33`，只增不减），
 * 故 #9 之后每一节都带着这条差异。#10 的 `sys-int-global` 同源：`DT_SELECT` 把 DT 行主键 ids
 * 写进 `RESULT:1` 起的格（`MapDataTableMethods.kt:882-889`），而 ids 由 `clockMillis` 折算
 * （`MapDataTableMethods.kt:494-501`），`RESULT:0` 才走 [HOST_DERIVED_MARK]，`RESULT:1` 起照常进哈希。
 *
 * **已落地的根因修法（本装置之外，owner 是 c-determin 那一轮）**：
 * 1. `StatementExecutor` 的 GETTIME 语句改吃宿主注入点（`StatementExecutor.kt:642-674`）；
 * 2. `GoldenPipeline` 的 `BuiltinHost` 把 `clockMillis()` **钉成常量**
 *    （`GoldenPipeline.kt` 的 `E2E_PINNED_CLOCK_MILLIS`，取值与 L2 判据不受影响的依据写在该常量 KDoc：
 *    基准 `pc-golden-root.txt:516` 只记长度 19 不记值）。
 * 钉钟之后 #9/#10 的两侧派生值两次运行逐字节相同，链在**不归一**的口径下也相等。
 * `Files.createTempDirectory("eratw-e2e-io")`（`GoldenPipeline.kt:663`）造的临时根按同一条款处理：
 * [com.eratw.port.core.host.FileOps] 侧全部只传相对路径、`list` 还按 `root.relativize` 折回相对名
 * （`GoldenPipeline.kt:965`），所以这条路径文本当前**没有**进驻留池（#8 `GOLDEN_IO` 两轮相同是旁证），
 * 归一它属于防御性覆盖，不是本轮分红。
 *
 * **规则（只作用在 `string-pool` 簇的输入文本上，逐条按内容匹配；不按下标跳过、不把整簇降级）**：
 * - R1 进程临时根（`java.io.tmpdir` 的原生 / 正斜杠 / canonical 诸形式）整段替换成
 *   [HOST_TMP_ROOT_TOKEN]。token 由实际根路径推出（同一进程两轮读到的根相同，随机后缀交给 R2）。
 * - R2 装置临时目录名 `eratw-e2e-io` + 随机后缀（`Files.createTempDirectory` 的产物）替换成
 *   [HOST_TMP_NAME_TOKEN]；其后的相对路径（`/ERB/…`、`/dat/…`）**原样保留**，所以「多写了一个文件」
 *   这类真实状态差异仍然改变哈希。
 * - R3 宿主时钟文本：`yyyy/MM/dd HH:mm:ss` 整段（[HOST_CLOCK_TOKEN]）与 18 位 .NET ticks 量级的
 *   十进制串（首位非零的 18 位，当前量级约 6.39e17 = 毫秒 + 62135596800000 再乘 10000，[HOST_TICKS_TOKEN]）。
 *   后者是 `DT_ROW_ADD` 的行主键
 *   （`MapDataTableMethods.kt:494-501`）被 `DT_TOXML` 连主键列一起写进 XML 文本
 *   （`MapDataTableMethods.kt:1038-1053`），再由 `GOLDEN_10_XMLMAP.ERB:157` 存进 `XDATA` 后驻留的路径；
 *   aux 侧的 DT 池本来就有同一条标记（见 [mixDtPool]）。
 *   R3a 的匹配形状**只认引擎真实产出的那一形**（GETTIME 语句与 GETTIMES 方法都用
 *   `yyyy/MM/dd HH:mm:ss`，`StatementExecutor.kt:669-672` / `HostMethods.kt:124-129`）；
 *   连字符日期（`2026-10-19 22:35:12` 那种）**故意不归一** —— 引擎不产该形态，池里出现它只可能是
 *   脚本自己的文案，宽到吃它就是抹真实状态（`L3HashChainTest` 里两头都锁了断言）。
 * - 替换是**文本级、内容驱动、幂等**的：token 本身不匹配任何模式，重复求值逐位相同（判据 (d) 的幂等不变）。
 * - 归一命中的条数登记进 [StateDigest.notes]（`pool-normalized:N`），报告里可查；簇照常产、照常进链。
 *   **钉钟之后命中数不为 0**（池里那条时钟文本本身仍是时钟形状，只是两轮同值），所以「命中数」不能
 *   当哨兵用；哨兵是 [rawPoolSnapshot] —— 判据 (a) 另外断言两轮**未归一**的驻留池原文逐字节相同，
 *   一旦有人把时钟放回绕过注入点的路子，链会照常绿（归一还兜着）但那条断言立刻红。
 *
 * **代价与边界**：池里若出现「字面量恰好像时钟文本 / 18 位 ticks」的脚本字符串会被一起归一
 *   （概率极低，只影响 `string-pool` 这一个维度；R3a 因此刻意只认引擎产出的斜杠形，见上）。
 *   这层归一**只铺 `string-pool` 簇**（契约授权面）—— 逐格字符串读取（`sys-str-global` /
 *   `user-private` 等）没有它，钉钟之前同一份宿主文本确实会从别的簇漏出来：#10 的 `XDATA`
 *   里那段带 ticks 主键的 XML 文本落在 **`user-private`** 簇（`#DIMS XDATA` 是 `@GOLDEN_XMLMAP`
 *   体内的静态声明，`L3HashChainTest` 喂的是全表函数键 → 进 private 簇；上一轮交付里写的
 *   「user-global」是笔误，报告 `build/l3-report.md` 的每节簇表可核：user-global 在 #9/#10 逐值相同、
 *   user-private 从 #9 起就变）。钉钟之后各簇读到的都是同一个常量，这个缺口不再需要第二层归一去补，
 *   **也别去补**（补了就是把引擎不确定性洗成测试容差）。
 *
 * ## 确定性纪律
 *
 * 哈希 = 自写 FNV-1a 64（[Fnv1a64]）。全程不用 `Object.hashCode` 的身份序、不用
 * `HashMap`/`HashSet` 的迭代序（名字表/槽表一律先按键排序）、不做任何大小写折叠或
 * locale 敏感格式化：字符串按 UTF-16 code unit 逐格混入，整型按 8 字节小端混入，
 * 浮点按原始位混入（`doubleToRawLongBits`）。枚举取 `name`，不取 `ordinal`。
 *
 * 官方 bug 原样复刻（ADR-005）：状态里读到「怪值」（超长负数、未清理的旧值、
 * `GAMEBASE_AUTHER` 这种历史拼写）照抄进哈希输入，不做任何归一或修正。
 */
object StateHash {

    /** `RESULT:0` / `RESULTS:0` / DT 主键格这类宿主派生值在哈希输入里的替代标记。 */
    const val HOST_DERIVED_MARK: String = "<<host-derived>>"

    /** 簇名里出现该前缀表示渲染失败（[StateDigest.errors] 同步登记，测试据此报红）。 */
    const val ERROR_MARK: String = "<<render-error>>"

    /** 规则 R1：本进程临时根（`java.io.tmpdir` 的各书写形式）在驻留池文本里的替代 token。 */
    const val HOST_TMP_ROOT_TOKEN: String = "<<host-tmp-root>>"

    /** 规则 R2：装置一次性临时目录名（前缀 + 随机后缀）的替代 token；其后的相对路径原样保留。 */
    const val HOST_TMP_NAME_TOKEN: String = "<<host-tmp-name>>"

    /** 规则 R3a：宿主时钟文本（GETTIME / GETTIMES 的 4-2-2 2-2-2 形式）的替代 token。 */
    const val HOST_CLOCK_TOKEN: String = "<<host-clock>>"

    /** 规则 R3b：18 位 .NET ticks 量级十进制串（DT 行主键）的替代 token。 */
    const val HOST_TICKS_TOKEN: String = "<<host-clock-ticks>>"

    /**
     * 驻留池条目的宿主派生内容归一（规则 R1/R2/R3，依据见类 KDoc
     * 「string-pool 的宿主派生内容归一」）。
     *
     * 三条替换都是**按内容匹配**：不按下标跳过条目、不把整簇降级为不纳入，命中数登记到
     * [StateDigest.notes]。替换文本不含 `$`，所以 `Regex.replace` 的组引用语义不会被触发；
     * token 本身不匹配任何模式，重复求值逐位相同（判据 (d) 的幂等前提不变）。
     */
    fun normalizePoolText(s: String): String {
        var cur = s
        for (root in hostTmpRootForms) {
            if (cur.contains(root)) cur = cur.replace(root, HOST_TMP_ROOT_TOKEN)
        }
        if (hostTmpNamePattern.containsMatchIn(cur)) cur = hostTmpNamePattern.replace(cur, HOST_TMP_NAME_TOKEN)
        if (hostClockPattern.containsMatchIn(cur)) cur = hostClockPattern.replace(cur, HOST_CLOCK_TOKEN)
        if (hostTicksPattern.containsMatchIn(cur)) cur = hostTicksPattern.replace(cur, HOST_TICKS_TOKEN)
        return cur
    }

    /**
     * 本进程临时根的候选书写形式（原生分隔符 / 正斜杠 / 去掉尾分隔符 / canonical），
     * 长者优先 —— 短的先替换会把长形式切成两截。少于 3 字符的形式不收，免得吃掉普通文本里的片段。
     */
    private val hostTmpRootForms: List<String> = buildHostTmpRootForms()

    private fun buildHostTmpRootForms(): List<String> {
        val raw = runCatching { System.getProperty("java.io.tmpdir") }.getOrNull() ?: return emptyList()
        val forms = ArrayList<String>()
        forms.add(raw)
        if (raw.length > 2 && (raw.endsWith("\\") || raw.endsWith("/"))) forms.add(raw.substring(0, raw.length - 1))
        val fwd = raw.replace('\\', '/')
        forms.add(fwd)
        if (fwd.length > 2 && fwd.endsWith("/")) forms.add(fwd.substring(0, fwd.length - 1))
        val canonical = runCatching { java.io.File(raw).canonicalPath }.getOrNull()
        if (canonical != null) forms.add(canonical)
        val out = ArrayList<String>()
        for (f in forms) {
            if (f.length >= 3 && !out.contains(f)) out.add(f)
        }
        out.sortBy { x: String -> -x.length }
        return out
    }

    /** 规则 R2：`Files.createTempDirectory("eratw-e2e-io")`（GoldenPipeline.kt:663）的目录名。 */
    private val hostTmpNamePattern: Regex = Regex("eratw-e2e-io[0-9A-Za-z_-]*")

    /** 规则 R3a：时钟文本（StatementExecutor.kt:669-672 与 HostMethods.kt:124-129 的产出形状）。 */
    private val hostClockPattern: Regex = Regex("[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")

    /**
     * 规则 R3b：18 位十进制（1e17 到 1e18 之间的整数文本，正是 `Utils.TimePoint()` 折算的
     * .NET ticks 位数，MapDataTableMethods.kt:488-501），前后不能再接数字，避免吃掉更长数字串的一片。
     */
    private val hostTicksPattern: Regex = Regex("(?<![0-9])[1-9][0-9]{17}(?![0-9])")

    /** 契约点名的期望槽：缺失（能力未接线）也要产簇，记 0，好让「接没接线」在链上可见。 */
    val EXPECTED_AUX_SLOTS: List<String> = listOf(
        "cbg", "config.state", "dt", "flowinput", "gfx", "gfx.bitmapCacheEnabledForNextLine",
        "keytoggle", "map", "sprite", "xml",
    )

    /**
     * 对 [VariableStore] 的可见状态求确定性 64 位哈希。
     *
     * @param privateVarFunctionKeys 私有 `#DIM` 的归属函数键（`FuncMeta.key` 大写集）；
     *   空集时 `user-private` 簇只体现「零个作用域」，会漏掉私有变量内容 —— L3 装置传全表键。
     */
    fun digest(store: VariableStore, privateVarFunctionKeys: Collection<String> = emptyList()): StateDigest {
        val clusters = ArrayList<ClusterDigest>()
        val notes = ArrayList<String>()
        val errors = ArrayList<String>()

        fun cluster(name: String, body: (Fnv1a64) -> Unit) {
            val h = Fnv1a64()
            h.mixString("eratw-l3/v1/" + name)
            try {
                body(h)
            } catch (t: Throwable) {
                errors.add("$name: ${t::class.java.simpleName}: ${t.message}")
                h.mixString(ERROR_MARK)
            }
            clusters.add(ClusterDigest(name, h.result()))
        }

        val defs = store.registry.all().sortedBy { d: VarDef -> d.name }
        val charaN = store.characters.count

        cluster("meta") { h ->
            h.mixLong(store.callDepth().toLong())
            h.mixLong(charaN.toLong())
            h.mixLong(store.pool.distinctCount.toLong())
            h.mixString(store.currentFunction ?: "")
            h.mixBoolean(store.config.debugMode)
            h.mixBoolean(store.config.compatiRand)
            h.mixBoolean(store.config.systemSaveInBinary)
            for (n in store.aux.slotNames().sorted()) h.mixString(n)
        }

        fun group(names: Set<String>, title: String) {
            cluster(title) { h ->
                for (def in defs) {
                    if (def.storage.name !in names) continue
                    mixDefCells(h, store, def, errors, notes)
                }
            }
        }
        group(setOf("G_INT1", "G_INT2", "G_INT3"), "sys-int-global")
        group(setOf("G_STR0", "G_STR1"), "sys-str-global")
        group(setOf("C_INT0", "C_STR0", "C_INT1", "C_STR1", "C_INT2"), "sys-chara")
        group(setOf("CSV_CONST"), "sys-csv-const")

        cluster("sys-pseudo") { h ->
            for (def in defs) {
                if (def.storage != Storage.PSEUDO) continue
                val kind = def.pseudo
                if (kind == null) continue
                if (kind in EXCLUDED_PSEUDO) continue
                mixDefCells(h, store, def, errors, notes)
            }
        }

        cluster("lastload") { h ->
            h.mixLong(store.lastLoadVersion)
            h.mixLong(store.lastLoadNo)
            h.mixString(store.lastLoadText)
            h.mixString(store.windowTitle)
            h.mixString(store.moneyLabel)
            h.mixString(store.drawLineStr)
            h.mixBoolean(store.isTimeout)
            h.mixString(store.debugFile)
            h.mixString(store.debugFunction)
            h.mixLong(store.debugLine)
        }

        cluster("roster") { h ->
            val sysSave = ArrayList<String>()
            val sysGlobal = ArrayList<String>()
            for (d in defs) {
                if (d.isSavedata) sysSave.add(d.name)
                if (d.isGlobal) sysGlobal.add(d.name)
            }
            val usrSave = ArrayList<String>()
            for (v in store.savedUserVarList()) usrSave.add(v.def.name)
            val usrGlobal = ArrayList<String>()
            for (v in store.globalUserVarList()) usrGlobal.add(v.def.name)
            for (n in sortedNames(sysSave)) h.mixString("sys-save:" + n)
            for (n in sortedNames(sysGlobal)) h.mixString("sys-global:" + n)
            for (n in sortedNames(usrSave)) h.mixString("usr-save:" + n)
            for (n in sortedNames(usrGlobal)) h.mixString("usr-global:" + n)
        }

        cluster("user-global") { h ->
            for (v in sortedVars(store.allUserVars())) mixUserVar(h, store, v, errors, notes)
        }

        cluster("user-private") { h ->
            val fkList = ArrayList<String>()
            for (k in privateVarFunctionKeys) fkList.add(k.uppercase())
            fkList.sort()
            val seenFk = HashSet<String>()
            for (fk in fkList) {
                if (!seenFk.add(fk)) continue
                val vs = store.privateUserVars(fk)
                h.mixString(fk)
                h.mixLong(vs.size.toLong())
                for (v in sortedVars(vs)) mixUserVar(h, store, v, errors, notes)
            }
        }

        cluster("string-pool") { h ->
            val n = store.pool.distinctCount
            h.mixLong(n.toLong())
            var normalized = 0
            for (i in 0 until n) {
                val raw = store.pool.lookup(i)
                val norm = normalizePoolText(raw)
                if (norm != raw) normalized++
                h.mixString(norm)
            }
            if (normalized > 0) notes.add("pool-normalized:" + normalized)
        }

        val slots = java.util.TreeSet<String>()
        slots.addAll(EXPECTED_AUX_SLOTS)
        slots.addAll(store.aux.slotNames())
        for (slot in slots) {
            cluster("aux:$slot") { h ->
                val v = store.aux.peek<Any>(slot)
                if (v == null) {
                    h.mixString("not-wired")
                    h.mixLong(0L)
                } else if (slot == "dt") {
                    mixDtPool(h, v, errors, notes)
                } else {
                    mixAny(h, v, 0, IdentityHashMap(), errors, notes)
                }
            }
        }

        val total = Fnv1a64()
        total.mixString("eratw-l3/v1/total")
        for (c in clusters) {
            total.mixString(c.name)
            total.mixLong(c.hash)
        }
        return StateDigest(clusters, total.result(), notes, errors)
    }

    /** 64 位哈希的 16 进制文本（固定 16 位、小写、无 locale）。 */
    fun hex(v: Long): String = java.lang.Long.toHexString(v).padStart(16, '0')

    /**
     * 驻留池**原文**快照（按 `pool` 下标序，一条字节都不归一）。
     *
     * 为什么存在：判据 (a) 的修法在引擎侧注入点（GETTIME 语句改吃 `BuiltinHost.clockMillis`）
     * 加装置侧钉钟（`GoldenPipeline` 的 `E2E_PINNED_CLOCK_MILLIS`），**不在测试侧归一**。
     * [normalizePoolText] 留在链里只作防御，而钉钟之后它照旧会命中池里那条（两轮相同的）时钟文本，
     * 所以「命中数」证明不了归一没在替引擎擦屁股。唯一能证明的口径是：**未归一的池原文两轮逐字节相同**。
     * 谁把时钟放回绕过注入点的路子，链照常绿（归一还兜着）、这条必红。依据见类 KDoc
     * 「string-pool 的宿主派生内容归一」。
     *
     * 只做观察：读 [com.eratw.port.core.vars.StringTable] 的公开 `lookup` / `distinctCount`
     * （与 `string-pool` 簇同一读取面，`StringTable.kt:15-56`），不 intern、不写入、幂等。
     */
    fun rawPoolSnapshot(store: VariableStore): List<String> {
        val n = store.pool.distinctCount
        val out = ArrayList<String>(n)
        for (i in 0 until n) out.add(store.pool.lookup(i))
        return out
    }

    /** 名单排序（`String.compareTo` 逐 code unit，无 locale）；显式拷贝 + `sort()` 避开链式推断。 */
    private fun sortedNames(list: List<String>): List<String> {
        val out = ArrayList<String>(list.size)
        out.addAll(list)
        out.sort()
        return out
    }

    /** 用户变量按名排序。 */
    private fun sortedVars(c: Collection<UserVar>): List<UserVar> {
        val out = ArrayList<UserVar>(c.size)
        out.addAll(c)
        out.sortBy { v: UserVar -> v.def.name }
        return out
    }

    // ----------------------------------------------------------------------
    // 系统变量：按 def 形状逐格读公开面
    // ----------------------------------------------------------------------

    /** `RESULT:0` / `RESULTS:0` 是宿主时钟/堆派生值的落点，见类 KDoc「不纳入项」第 1 条。 */
    private fun isHostDerivedCell(def: VarDef, flat: Int): Boolean =
        flat == 0 && !def.isCharacterData && (def.name == "RESULT" || def.name == "RESULTS")

    private fun mixDefCells(
        h: Fnv1a64,
        store: VariableStore,
        def: VarDef,
        errors: MutableList<String>,
        notes: MutableList<String>,
    ) {
        h.mixString(def.name)
        if (def.isForbid) {
            h.mixString("forbid")
            return
        }
        for (d in def.dims) h.mixInt(d)
        val elems = def.elementCount
        val positions = if (def.isCharacterData) store.characters.count else 1
        if (def.isCharacterData && positions == 0) {
            h.mixString("no-chara")
            return
        }
        val args = LongArray(if (def.argCount == 0) 1 else def.argCount)
        for (pos in 0 until positions) {
            if (def.isCharacterData) args[0] = pos.toLong()
            val offset = if (def.isCharacterData) 1 else 0
            for (flat in 0 until elems) {
                decompose(flat, def.dims, args, offset)
                if (isHostDerivedCell(def, flat)) {
                    h.mixString(HOST_DERIVED_MARK)
                    continue
                }
                readAndMix(h, store, def, args, errors, notes)
            }
        }
    }

    private fun readAndMix(
        h: Fnv1a64,
        store: VariableStore,
        def: VarDef,
        args: LongArray,
        errors: MutableList<String>,
        notes: MutableList<String>,
    ) {
        val n = def.argCount
        try {
            if (def.type == VarType.INT) {
                h.mixLong(store.readInt(def.name, if (n == 0) EMPTY_ARGS else args))
            } else {
                h.mixString(store.readStr(def.name, if (n == 0) EMPTY_ARGS else args.copyOf(n)))
            }
        } catch (e: CodeEE) {
            notes.add("read-skip:${def.name}=${e.message?.take(80)}")
            h.mixString("read-skip")
        } catch (t: Throwable) {
            errors.add("read:${def.name} ${t::class.java.simpleName}: ${t.message}")
            h.mixString(ERROR_MARK)
        }
    }

    /** 行主序元素下标还原成逐维下标（与 `VariableStore.flat` 反向，`VariableStore.kt:1063-1067`）。 */
    private fun decompose(flat: Int, dims: IntArray, out: LongArray, offset: Int) {
        var v = flat
        for (i in dims.lastIndex downTo 0) {
            val d = dims[i]
            out[offset + i] = (v % d).toLong()
            v /= d
        }
    }

    // ----------------------------------------------------------------------
    // 用户 #DIM 变量
    // ----------------------------------------------------------------------

    private fun mixUserVar(
        h: Fnv1a64,
        store: VariableStore,
        v: UserVar,
        errors: MutableList<String>,
        notes: MutableList<String>,
    ) {
        h.mixString(v.def.name)
        h.mixString(v.spec.toString())
        if (v.spec.isRef) {
            // REF 声明尺寸恒 0（`UserVariables.kt:39`），可观测状态就是它当前绑到哪块数组。
            h.mixString("ref")
            if (v is RefUserVar) {
                val ti = v.targetInt
                val ts = v.targetStr
                h.mixBoolean(ti != null)
                h.mixBoolean(ts != null)
                ti?.let { a -> h.mixInt(a.size); for (x in a) h.mixLong(x) }
                ts?.let { a ->
                    h.mixInt(a.size)
                    for (id in a) h.mixString(runCatching { store.pool.lookup(id) }.getOrDefault("?"))
                }
            }
            return
        }
        val dims = v.def.dims
        val elems = if (dims.isEmpty()) 1 else v.def.totalSize
        val positions = if (v.def.isCharacterData) store.characters.count else 1
        if (v.def.isCharacterData && positions == 0) {
            h.mixString("no-chara")
            return
        }
        for (pos in 0 until positions) {
            for (e in 0 until elems) {
                try {
                    if (v.def.type == VarType.INT) h.mixLong(v.readInt(pos, e)) else h.mixString(v.readStr(pos, e))
                } catch (ce: CodeEE) {
                    // 动态私有变量在作用域外 / REF 目标缺失：状态本身就是「无值」，记固定标记。
                    notes.add("usr-na:${v.def.name}=${ce.message?.take(60)}")
                    h.mixString("unavailable")
                } catch (t: Throwable) {
                    errors.add("usr:${v.def.name} ${t::class.java.simpleName}: ${t.message}")
                    h.mixString(ERROR_MARK)
                }
            }
        }
    }

    // ----------------------------------------------------------------------
    // aux：DT 池特例（时钟派生主键），其余走通用结构渲染
    // ----------------------------------------------------------------------

    /**
     * DT 池。行主键 `id` = `Utils.TimePoint()` 折算的宿主时钟刻度
     * （`MapDataTableMethods.kt:488-501`），`lastRowId` 同源，两者按 [HOST_DERIVED_MARK]
     * 替代；表名/列定义/行数/其余单元值照常纳入。
     */
    private fun mixDtPool(h: Fnv1a64, pool: Any, errors: MutableList<String>, notes: MutableList<String>) {
        h.mixString("dt-pool/v1")
        val tables = fieldValue(pool, "tables") as? Map<*, *>
        if (tables == null) {
            errors.add("dt: 无法解析 tables 字段（${pool.javaClass.name}）")
            h.mixString(ERROR_MARK)
            return
        }
        for (item in sortedEntries(tables, h)) {
            val t = item.second
            h.mixString(item.first)
            val pkCol = fieldValue(t, "pkCol") as? String
            val columns = fieldValue(t, "columns") as? List<*> ?: emptyList<Any?>()
            var pkIndex = -1
            var probe = 0
            while (probe < columns.size) {
                if ((fieldValue(columns[probe], "name") as? String) == pkCol) {
                    pkIndex = probe
                    break
                }
                probe++
            }
            h.mixString(fieldValue(t, "name") as? String ?: "?")
            h.mixString(pkCol ?: "~")
            h.mixBoolean(fieldValue(t, "caseSensitive") as? Boolean ?: false)
            h.mixInt(columns.size)
            for ((ci, col) in columns.withIndex()) {
                h.mixInt(ci)
                mixAny(h, col, 2, IdentityHashMap(), errors, notes)
            }
            val rows = fieldValue(t, "rows") as? List<*> ?: emptyList<Any?>()
            h.mixInt(rows.size)
            for ((ri, row) in rows.withIndex()) {
                h.mixInt(ri)
                val cells = row as List<*>?
                if (cells == null) {
                    h.mixString("row-shape?")
                } else {
                    h.mixInt(cells.size)
                    for ((ci, cell) in cells.withIndex()) {
                        if (ci == pkIndex) h.mixString(HOST_DERIVED_MARK)
                        else mixAny(h, cell, 3, IdentityHashMap(), errors, notes)
                    }
                }
            }
        }
        h.mixString("lastRowId")
        h.mixString(HOST_DERIVED_MARK)
    }

    /**
     * 通用结构渲染：Map / Set 按键（元素）内容排序后纳入；List / 数组保持**下标序**
     * （图层序、XML 子节点序、像素行序本身就是状态）。
     */
    private fun mixAny(
        h: Fnv1a64,
        v: Any?,
        depth: Int,
        seen: IdentityHashMap<Any, Boolean>,
        errors: MutableList<String>,
        notes: MutableList<String>,
    ) {
        when {
            v == null -> h.mixByte(TAG_NULL)
            depth > MAX_DEPTH -> {
                notes.add("depth-cap@${v.javaClass.simpleName}")
                h.mixByte(TAG_DEPTH)
            }
            v is XmlNode -> {
                h.mixByte(TAG_XML)
                h.mixString(v.outerXml())
            }
            v is String -> {
                h.mixByte(TAG_STR)
                h.mixString(v)
            }
            v is Boolean -> {
                h.mixByte(TAG_BOOL)
                h.mixBoolean(v)
            }
            v is Char -> {
                h.mixByte(TAG_CHAR)
                h.mixInt(v.code)
            }
            v is Byte || v is Short || v is Int || v is Long -> {
                h.mixByte(TAG_SINT)
                h.mixString(v.javaClass.simpleName)
                h.mixLong((v as Number).toLong())
            }
            v is Float || v is Double -> {
                h.mixByte(TAG_FPU)
                h.mixString(v.javaClass.simpleName)
                h.mixLong(if (v is Float) v.toRawBits().toLong() else java.lang.Double.doubleToRawLongBits(v as Double))
            }
            v is Enum<*> -> {
                h.mixByte(TAG_ENUM)
                h.mixString(v.javaClass.name)
                h.mixString(v.name)
            }
            v is Map<*, *> -> {
                h.mixByte(TAG_MAP)
                h.mixInt(v.size)
                for (item in sortedEntries(v, h)) {
                    h.mixString(item.first)
                    mixAny(h, item.second, depth + 1, seen, errors, notes)
                }
            }
            v is Set<*> -> {
                h.mixByte(TAG_SET)
                val items = ArrayList<Pair<String, Any?>>(v.size)
                for (e in v) items.add(Pair(scalarKey(e), e))
                items.sortBy { item: Pair<String, Any?> -> item.first }
                h.mixInt(items.size)
                for (item in items) {
                    h.mixString(item.first)
                    mixAny(h, item.second, depth + 1, seen, errors, notes)
                }
            }
            v is List<*> -> {
                h.mixByte(TAG_LIST)
                h.mixInt(v.size)
                for ((i, e) in v.withIndex()) {
                    h.mixInt(i)
                    mixAny(h, e, depth + 1, seen, errors, notes)
                }
            }
            v is IntArray -> {
                h.mixByte(TAG_INTARR)
                h.mixInt(v.size)
                for (x in v) h.mixLong(x.toLong() and 0xFFFFFFFFL)
            }
            v is LongArray -> {
                h.mixByte(TAG_LONGARR)
                h.mixInt(v.size)
                for (x in v) h.mixLong(x)
            }
            v is ShortArray -> {
                h.mixByte(TAG_SHORTARR)
                h.mixInt(v.size)
                for (x in v) h.mixLong(x.toLong())
            }
            v is ByteArray -> {
                h.mixByte(TAG_BYTEARR)
                h.mixInt(v.size)
                for (x in v) h.mixByte(x.toInt() and 0xFF)
            }
            v is BooleanArray -> {
                h.mixByte(TAG_BOOLARR)
                h.mixInt(v.size)
                for (x in v) h.mixBoolean(x)
            }
            v is Array<*> -> {
                h.mixByte(TAG_OBJARR)
                h.mixInt(v.size)
                for ((i, e) in v.withIndex()) {
                    h.mixInt(i)
                    mixAny(h, e, depth + 1, seen, errors, notes)
                }
            }
            v is Iterable<*> -> {
                h.mixByte(TAG_ITER)
                val items = v.toList()
                h.mixInt(items.size)
                for ((i, e) in items.withIndex()) {
                    h.mixInt(i)
                    mixAny(h, e, depth + 1, seen, errors, notes)
                }
            }
            else -> mixObject(h, v, depth, seen, errors, notes)
        }
    }

    private fun mixObject(
        h: Fnv1a64,
        v: Any,
        depth: Int,
        seen: IdentityHashMap<Any, Boolean>,
        errors: MutableList<String>,
        notes: MutableList<String>,
    ) {
        val cls = v.javaClass
        if (!isHashableClass(cls.name)) {
            notes.add("opaque:${cls.name}")
            h.mixByte(TAG_OPAQUE)
            h.mixString(cls.simpleName)
            return
        }
        if (seen.containsKey(v)) {
            h.mixString("cycle")
            return
        }
        seen[v] = true
        try {
            h.mixByte(TAG_OBJ)
            h.mixString(cls.name)
            val fields = instanceFields(cls).sortedBy { f: Field -> f.name }
            h.mixInt(fields.size)
            for (f in fields) {
                h.mixString(f.name)
                var value: Any? = null
                var failed = false
                try {
                    f.isAccessible = true
                    value = f.get(v)
                } catch (t: Throwable) {
                    failed = true
                    errors.add("field:${cls.simpleName}.${f.name} ${t::class.java.simpleName}")
                }
                if (failed) h.mixString(ERROR_MARK)
                else mixAny(h, value, depth + 1, seen, errors, notes)
            }
        } finally {
            seen.remove(v)
        }
    }

    /**
     * map 条目按键文本排序后的 `(键文本, 值)` 列表。
     *
     * 刻意不返回 `List<Map.Entry<*, *>>`：`Map.Entry` 带星投影时既不能
     * `plusAssign`/`addAll`，也不能稳定解构（`ArrayList<Map.Entry<*,*>> += map.entries`
     * 在 Kotlin 2.0.20 直接 overload resolution 失败）。这里先把每条目落成
     * `Pair<String, Any?>`（键文本 + 值），后续只碰 Pair。
     */
    private fun sortedEntries(map: Map<*, *>, h: Fnv1a64): List<Pair<String, Any?>> {
        val keyed = ArrayList<Pair<String, Any?>>(map.size)
        for (entry in map.entries) {
            keyed.add(Pair(scalarKey(entry.key), entry.value))
        }
        keyed.sortBy { item: Pair<String, Any?> -> item.first }
        val seenKeys = HashSet<String>()
        for (item in keyed) {
            if (!seenKeys.add(item.first)) h.mixString("tie-key:" + item.first)
        }
        return keyed
    }

    private fun scalarKey(v: Any?): String = when (v) {
        null -> "~null"
        is String -> "s:$v"
        is Number -> "n:${v.toLong()}:${v::class.java.simpleName}"
        is Boolean -> "b:$v"
        is Char -> "c:${v.code}"
        is Enum<*> -> "e:${v.javaClass.name}:${v.name}"
        else -> "d:${hex(subDigest(v))}"
    }

    private fun subDigest(v: Any?): Long {
        val h = Fnv1a64()
        mixAny(h, v, 0, IdentityHashMap(), ArrayList(), ArrayList())
        return h.result()
    }

    /** 反射取实例字段值（沿父类链找第一个同名字段）；取不到返回 null，由调用方给标记。 */
    private fun fieldValue(target: Any?, name: String): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null) {
            if (c == Any::class.java) break
            for (f in c.declaredFields) {
                if (f.name == name) {
                    f.isAccessible = true
                    return f.get(target)
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun instanceFields(cls: Class<*>): List<Field> {
        val out = ArrayList<Field>()
        var c: Class<*>? = cls
        while (c != null) {
            if (c == Any::class.java) break
            if (!isHashableClass(c.name)) break
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.isSynthetic) continue
                if (f.name.startsWith("$") || f.name == "Companion") continue
                if (f.name in BACKREFERENCE_FIELDS) continue
                out.add(f)
            }
            c = c.superclass
        }
        return out
    }

    /**
     * 可递归的类：本移植的数据侧包，且不命中宿主/运行时标记。
     * 命中不了就按 opaque 处理（宁可少纳入，也不把时钟/路径/lambda 带进链）。
     *
     * 标记表**刻意不含** Pool / Table / Buffer / State：`XmlPool`、`MapPool`、`DtPool`、
     * `DtTable`、`GraphicsPool`、`GraphicsBuffer`、`SpritePool`、`CbgPool`、`ConfigState`
     * 正是契约点名要纳入的池与表，误判成 opaque 就等于漏簇（[StateDigest.notes] 里会留痕，
     * 但链值会悄悄变弱 —— 所以这里用白名单 + 精确黑名单，不用宽口径）。
     */
    private fun isHashableClass(name: String): Boolean {
        if (ALLOW_PACKAGES.none { name.startsWith(it) }) return false
        return OPAQUE_MARKERS.none { name.contains(it) }
    }

    private val ALLOW_PACKAGES = listOf(
        "com.eratw.port.core.fn.",
        "com.eratw.port.core.host.",
        "com.eratw.port.core.xml.",
        "com.eratw.port.core.vars.",
        "com.eratw.port.core.data.",
    )

    /** 宿主对象 / 运行时对象 / 时钟与时区与序列化载体：见到就止步，只留类名标记。 */
    private val OPAQUE_MARKERS = listOf(
        "Sink", "Host", "Store", "Runtime", "Aux", "Context", "Console", "VirtualMachine",
        "Program", "Ops", "Thread", "ClassLoader", "Lambda", "File", "Path", "Timer",
        "Clock", "Date", "TimeZone", "Locale", "Registry",
    )

    /** 反向上引用：结构冗余且必然成环（XML 节点父链、属性宿主）。 */
    private val BACKREFERENCE_FIELDS = setOf("parentNode", "ownerElement", "parent", "owner")

    private val EXCLUDED_PSEUDO = setOf(PseudoKind.RAND, PseudoKind.LINECOUNT, PseudoKind.INT_MAX, PseudoKind.INT_MIN)

    private val EMPTY_ARGS = LongArray(0)
    private const val MAX_DEPTH = 24

    private const val TAG_NULL = 0x01
    private const val TAG_STR = 0x02
    private const val TAG_BOOL = 0x03
    private const val TAG_CHAR = 0x04
    private const val TAG_SINT = 0x05
    private const val TAG_FPU = 0x06
    private const val TAG_ENUM = 0x07
    private const val TAG_MAP = 0x08
    private const val TAG_SET = 0x09
    private const val TAG_LIST = 0x0A
    private const val TAG_OBJ = 0x0B
    private const val TAG_OPAQUE = 0x0C
    private const val TAG_XML = 0x0D
    private const val TAG_ITER = 0x0E
    private const val TAG_DEPTH = 0x0F
    private const val TAG_INTARR = 0x10
    private const val TAG_LONGARR = 0x11
    private const val TAG_SHORTARR = 0x12
    private const val TAG_BYTEARR = 0x13
    private const val TAG_BOOLARR = 0x14
    private const val TAG_OBJARR = 0x15
}

/** 64 位 FNV-1a（自写；CONTRACT-M4-A §3 禁用第三方/标准库哈希）。 */
class Fnv1a64(initial: Long = Fnv1a64.OFFSET_BASIS) {
    private var h: Long = initial

    fun mixByte(b: Int): Fnv1a64 {
        h = (h xor (b.toLong() and 0xFFL)) * PRIME
        return this
    }

    /** 小端 8 字节，覆盖负数与 Long.MIN_VALUE。 */
    fun mixLong(v: Long): Fnv1a64 {
        var x = v
        for (i in 0 until 8) {
            h = (h xor (x and 0xFFL)) * PRIME
            x = x ushr 8
        }
        return this
    }

    fun mixInt(v: Int): Fnv1a64 = mixLong(v.toLong() and 0xFFFFFFFFL)

    fun mixBoolean(v: Boolean): Fnv1a64 = mixByte(if (v) 0xA1 else 0x1A)

    /** 长度前置 + 逐 UTF-16 code unit；无大小写折叠、无 locale、无 Unicode 归一化。 */
    fun mixString(s: String): Fnv1a64 {
        mixLong(s.length.toLong())
        for (i in 0 until s.length) mixInt(s[i].code)
        return this
    }

    fun result(): Long = h

    companion object {
        /** FNV-1a 64 缺省偏移基准（0xCBF29CE484222325 的有符号表示）。 */
        const val OFFSET_BASIS: Long = -3750763034362895579L

        /** FNV-1a 64 素数（0x100000001B3）。 */
        const val PRIME: Long = 1099511628211L
    }
}

/** 一簇的哈希值；[name] 用于跨轮定位差异（报告按簇对齐）。 */
class ClusterDigest(val name: String, val hash: Long)

/**
 * 一次状态快照：分簇值 + 总值 + 渲染说明 + 渲染错误。
 *
 * 类内一律写成显式循环（不用 `firstOrNull` / `associateBy` / 嵌套 `it`）：
 * 本文件的调用方大量在 star-projected 集合上用隐式 `it`，K2 会报
 * 「Cannot infer type」/「Ambiguous reference to extension」，装置诊断信息不该赌推断。
 */
class StateDigest(
    val clusters: List<ClusterDigest>,
    val total: Long,
    val notes: List<String>,
    val errors: List<String>,
) {
    /** 按簇名取值（报告与诊断用）。 */
    fun cluster(name: String): Long? {
        for (c in clusters) {
            if (c.name == name) return c.hash
        }
        return null
    }

    /** 首个与另一份快照不同的簇名；完全相同返回 null。 */
    fun firstDiffCluster(other: StateDigest): String? {
        for (o in other.clusters) {
            var found = false
            for (c in clusters) {
                if (c.name == o.name) {
                    found = true
                    break
                }
            }
            if (!found) return o.name
            val mine = cluster(o.name)
            if (mine != null && mine != o.hash) return o.name
        }
        for (c in clusters) {
            var inOther = false
            for (o in other.clusters) {
                if (o.name == c.name) {
                    inOther = true
                    break
                }
            }
            if (!inOther) return c.name
        }
        return null
    }

    companion object {
        fun hex(v: Long): String = StateHash.hex(v)
    }
}

/**
 * 链：`chain[i] = FNV-1a64(chain[i-1] ++ 节标题 ++ 节状态 ++ 该节状态哈希 ++ 血统计数)`。
 *
 * ## 为什么要多一个「血统计数」（v2，本轮改；v1 只有前驱 + 标题 + 状态 + 状态哈希）
 *
 * 「链必然传导」这个直觉在 GOLDEN 套件上不成立，是取证出来的事实：判据 (c) 在第 3 个节点
 * （`GOLDEN_FLOW`）之后把 `BASE:0:0` 加 1，紧接着的 `GOLDEN_04_CHARA.ERB:88` 就 `DELALLCHARA`、
 * `:91` 再 `ADDCHARA 0` 从模板重建角色 —— 被改写的那一格在该节结束前就被整体覆盖，
 * 于是 #4 的**状态哈希与干净轮相等**，v1 的链在 #4 处**重新汇合**（v1 的类注释断言「不会汇合」，
 * 是错的，本轮按实测证据纠正）。GOLDEN_09 的 `RESETDATA`（`GOLDEN_09_MISC.ERB:72`）+
 * `ADDCHARA 0` 是同一机制的第二次覆盖。
 * 状态观察点在节尾，抹平发生在节内 —— 装置看不见，也就无法靠状态把分叉传下去。
 *
 * 所以链再叠一条**单调血统输入** [lineage]：装置自己施加过的改写次数。它在 #k 之前恒为 0、
 * 之后恒为 1（本装置只施加一次），使「改写过的轮」与「没改写的轮」从 #k+1 起逐节点不可汇合，
 * 而不依赖「引擎有没有在后续节里把状态抹平」这一与被测性质无关的巧合。
 *
 * **判据 (a) 的确定性不受影响**：两条干净轮的 lineage 恒为 0，输入序列逐字节相同 → 链逐值相同；
 * lineage 不读宿主态、不参与状态求值，只是把「装置做过什么」显式记进链。
 *
 * **代价（登记常量口径）**：链公式 v1 到 v2 变了，(b) 的登记链头/链尾必须随之重新落定 ——
 * 当前 `REGISTRY_READY = false`，常量仍是占位，正落在可以改公式的窗口里；一旦落定，
 * 再动这个公式就等于作废登记值（[HashChain.GENESIS] 同理）。
 */
class HashChain {
    class Node(
        val index: Int,
        val title: String,
        val status: String,
        val stateHash: Long,
        val chain: Long,
        val digest: StateDigest,
    )

    private val nodeList = ArrayList<Node>()
    private var prev: Long = GENESIS

    /**
     * 追加一个链节点。
     *
     * @param lineage 单调血统计数（装置累计施加的改写次数）；干净轮恒传 0。
     */
    fun append(title: String, status: String, digest: StateDigest, lineage: Long = 0L): Node {
        val h = Fnv1a64(prev)
        h.mixString("eratw-l3/chain-link/v2")
        h.mixString(title)
        h.mixString(status)
        h.mixLong(digest.total)
        h.mixLong(lineage)
        val c = h.result()
        val n = Node(nodeList.size, title, status, digest.total, c, digest)
        nodeList.add(n)
        prev = c
        return n
    }

    fun nodes(): List<Node> = nodeList

    fun head(): Long = nodeList.first().chain

    fun tail(): Long = nodeList.last().chain

    companion object {
        /** 链头前驱；改这个值等于改整条链，登记常量随之作废（KDoc 明示是装置版本的一部分）。 */
        val GENESIS: Long = Fnv1a64().mixString("eratw-l3/genesis/v1").result()
    }
}
