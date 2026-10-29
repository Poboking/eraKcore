package com.eratw.port.core.loader

import com.eratw.port.core.Version
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.NullTerm
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimSpec
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/**
 * 侧车格式错误（不是 `CodeEE`：与脚本语义无关，属产物/装载契约）。
 *
 * 与 [ErbcLoadException] 的分工：那个管 `.erbc` 容器本身，本类管与它同目录的
 * `.meta` 侧车（缺段、计数不符、语法不合法、版本过新）。
 */
class BootMetaFormatException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * `program.erbc` 的**装载期元数据侧车**（`.erbc` v1 容器不含这些信息）。
 *
 * ## 为什么要有侧车
 *
 * `.erbc` v1 只装得下「指令流 + 四个池 + 函数表（名字/入口/arity/槽数/`$标签`）」：
 * - `SECTION_FUNCTIONS` 的函数标志 v1 恒 0（`ErbcWriter.kt` 的 `rec.u32(0)`，规约 §5.5），
 *   所以「事件 / `#FUNCTION` 方法 / `#ONLY|#PRI|#LATER|#SINGLE` / 返回类型 / 形参分桶 /
 *   `#LOCALSIZE` / 装载位置」这些 [FuncMeta] 字段**在容器里不存在**；
 * - 全局与私有 `#DIM` 声明不产生指令（`SharpStmt` 不发射），容器里一条也不会留；
 * - PRINTDATA / STRDATA 的数据节（`DATA` / `DATAFORM` 行）是语句**块结构**，编译器把
 *   PRINTDATA 语句本身投进语句池，但「这棵数据树属于哪个语句实例」只在编译期 AST 里成立；
 * - `$标签 → pc` 虽然容器里有（`FunctionInfo.labelNames/labelPcs`），但那是**函数表内**
 *   的平行表，运行期 [com.eratw.port.core.proc.ProcessRuntime.installLabelTable] 要的是
 *   「函数键 → 标签名 → pc」的按名索引，含事件合成入口。
 *
 * 原版这些信息全在进程内存里（`IdentifierDictionary` 的变量池、
 * `LabelDictionary.GetLabel`、`FunctionLabelLine.privateVar`、
 * `FunctionIdentifier.PrintData` 的块归属），不存在「序列化再读回」这件事。本移植把它们
 * 固化成侧车，判据只有一条：**同一份元数据经写—读往返后，`BEGIN TITLE` 的行为与源码
 * 直编一致**（见 `BootMetaRoundTripTest` 的两路对拍）。
 *
 * ## 字段与权威出处
 *
 * | 字段 | 运行期消费点 | C# 出处 |
 * |---|---|---|
 * | [labels] | `ProcessRuntime.installLabelTable`（GOTOFORM/TRYGOTOFORM） | LabelDictionary.cs:382-389 |
 * | [dataSections] | `ProcessRuntime.installDataSections`（PRINTDATA/STRDATA） | FunctionIdentifier.cs:4285 |
 * | [globalDims] | `SystemVariableBootstrap.install` | ErhLoader.cs:249-338 |
 * | [privateDims] | `VariableStore.declareUserVar(spec, functionKey)` | UserDefinedVariableData.cs:44-477 |
 * | [dimParseConfig] | `DimParser`（宏表 + 整型常量表 + 字符串常量表） | UserDefinedVariableData.cs:300-343 / :405-416 |
 * | [functions] | `FunctionTable`（调用/事件分桶） | LabelDictionary.cs:60-105 |
 * | [fileByKey] | 报错定位（`GameBootSmoke` 首错函数→源文件） | —（装置口径） |
 * | [loadWarnings] | 设备侧读回装载告警清单（本波仅落盘/读回，透出待组长接线） | 原版不持久化 —— 装载告警只进控制台/日志（EmueraConsole.cs:3064, 3076；消费点 ParserMediator.cs:148），原版每次启动从源码重装载、无编译产物可带 |
 *
 * ## 侧车格式（UTF-8、LF、确定性）
 *
 * 纯文本、逐行记录，`关键字 载荷` 两段。载荷由三种记号组成：`"引号串"`（JSON 风格转义）、
 * 整数、以及由括号包起来的记号列表（只有表达式树与列表字段用它）。**所有字符串字段一律
 * 带引号**，关键字与标签名（`int`/`str`/`var`/`call`…、`OperatorCode` 枚举名）一律裸写，
 * 因此记号流无歧义。
 *
 * 记录序固定（先声明计数、后逐条），`end` 收尾；读侧校验「声明计数 == 实际条数」并拒绝
 * 未知关键字与多余内容，截断文件因此一定会报错而不是「静默少一半声明」。
 * `str-constants` 与 `load-warnings` 是 `end` 之前的两个**可选**段（都是尾部追加）：
 * `str-constants` 缺段读侧按空表处理、写出侧恒写（含 `str-constants 0`）；
 * `load-warnings` 缺段读侧按 null 处理（= 写出方没有告警通道），写出侧仅在模型非 null 时写
 * （空清单也写 `load-warnings 0`）。因此「无告警通道的元数据」经新写出端得到的文本与旧版
 * **逐字节相同**（新写出·旧读不炸），而旧侧车经新读侧得到 null 清单（旧写出·新读不炸，
 * 消费口径见 [loadWarningsOrEmpty]）。
 * 同一份 [BootMetadata] 两次 [encodeText] 逐字节相同（所有 Map 按键排序、函数按 index 排序）。
 *
 * 读侧的**实例共享**：`labels` / `fileByKey` / `functions` 三段里逐字相同的函数键、
 * 以及 `fileByKey` 的值（源文件路径）与 `globalDims` 的 `file`，都经由解析期的同一张
 * 驻留池归一成**同一个 `String` 实例**（池不驻留）。这是身份层面的优化：内容、等值语义、
 * [encodeText] 的输出都逐字节不变，只让「同一批 110,072 个函数键」不再各解一份。
 *
 * ## 读侧的流式形态（mem4）
 *
 * 读侧只保留**表示形态与生命周期**上的优化，格式与行为一个字都没动：
 * - 记录不再整表物化：`BootMetaCodec.Cursor` 一次给一条记录，关键字与载荷都只是行源上的
 *   区间（`records` 列表 + 每行三个 String 的旧形态实测占 688.7 MiB 瞬时分配里的大头）；
 *   段计数判界要的「后续还剩多少条」由前置的计数趟（`countRecords`）给出，是同一个数。
 * - 行源抽象成 `CharSrc`：整文入口（[parse]）走真 String，文件入口（[read]）走**复用**的
 *   char 数组 —— 368,806 行不再各拷一份 String（实测 79.6 MiB）。
 * - 裸记号不落地：整数位 / 0-1 位 / `-` / 运算符名 / 表达式标签 / CSV 段都只在源上比区间，
 *   真要取值（进模型或拼错误消息）才 `slice` 成 String。
 *
 * 判据不是「看起来更省」而是**模型逐字节不变**：`BootMetadataModelSnapshotTest` 对同一份
 * 真产物侧车落一份逐字段快照（含驻留池的身份 census），改前改后必须字节恒等。
 *
 * ## 读侧的值域与重复闸（CMP-82）
 *
 * 侧车是外部输入（装机后存储位腐、手改产物都在可达路径上），读侧对**写出侧不可能产出的
 * 形态**一律当场拒绝，不静默折中：
 * - 位字段只认 0/1：`atomBool`（私有 `#DIM` 的 8 个标志位、`dim-config`）、表达式 `typeAt`
 *   与 FORM 转换的 `alignAt`。旧实现里布尔走 `!= 0` 宽松口径、类型位与对齐位是「非 0 即真」，
 *   与表达式侧 `boolAt` 的严卡 0/1 是两套尺；写出侧 `bit()` 恒写 0/1 故不会误伤。
 * - `function flags` 只认 `0..127`（写出侧只置 7 位；高位旧实现静默丢，负值则全位为真）。
 * - `function index` 只认 `0..2^20`（`FunctionTable.byIndex` 按 `max + 1` 开数组）。
 * - 其余计数字段（`defIndex` / `argIntCount` / `argStrCount` / `localSize` / `localsSize` /
 *   `datasec` 下标 / `dims` 各项）只认非负 —— 它们最终都是数组长度。
 * - 各段的键出现重复即拒绝：`labelset` 函数键、`label` 名（段内）、`file` 函数键、`datasec`
 *   语句下标、`function index`、`global-dim` 记录、`private-dim` 声明名、`macro` / `const` /
 *   `str-const` 名。旧实现在 labels/files/datasec 上是**后者覆盖**、在 function/global-dim
 *   上是**照单追加**，两种都无声。`function` 段不以 `key` 判重：事件成员与合成入口**合法**
 *   共用事件名（全量语料 6 个事件名合计 36 条记录同键）。
 *
 * `format-version` 只在**不兼容改动**时才升；读侧遇到更高版本直接拒绝（不猜）。
 */
class BootMetadata(
    /**
     * 函数键（大写）→ `$标签名`（大写）→ 函数内 pc。
     *
     * 键口径 = 编译期的 `FuncSig.name` 大写（非事件 = 符号表 key；事件成员 =
     * `KEY#defIndex`；事件合成入口 = 事件名本身），与
     * `ProcessRuntime.tryResolveLabel` 的查表键一致。
     */
    val labels: Map<String, Map<String, Int>> = emptyMap(),
    /**
     * 语句池下标 → PRINTDATA/STRDATA 的数据节（组列表；组内元素是 DATA/DATAFORM 表达式）。
     *
     * **为什么按语句池下标而不是按 `Statement` 实例**：容器装载会重建全部语句实例
     * （`ErbcReader` 逐条 `readStatement`），实例身份跨进程不可能稳定；语句池下标则被
     * `ErbcWriter`（追加序）与 `ErbcReader`（读回序）共同保证稳定，且 marker 指令的
     * `a` 操作数就是这个下标（契约 C3）。装载侧用 `program.statement(i)` 还原成实例。
     */
    val dataSections: Map<Int, List<List<Expr>>> = emptyMap(),
    /** 函数外（全局）`#DIM`/`#DIMS` 原文，交 `SystemVariableBootstrap.install` 走原版装载语义。 */
    val globalDims: List<RawDim> = emptyList(),
    /** 函数内（私有）`#DIM` 的解析结果，按所属函数键交 `VariableStore.declareUserVar`。 */
    val privateDims: List<PrivateDim> = emptyList(),
    /**
     * 运行期函数元数据（`FunctionTable` 的输入）。
     *
     * 容器里的函数表只有「名字/入口/arity/槽数/`$标签`」，事件位与方法位 v1 恒 0，
     * 所以这一份必须随侧车走，否则装载出来的程序**调不动 SYSTEM_TITLE**
     * （`FunctionTable.classify` 对未登记名字返回 NOT_FOUND）。
     */
    val functions: List<FuncMeta> = emptyList(),
    /** 函数键（大写）→ 源文件相对路径（报错定位；`FuncMeta.position` 的 `文件:行号` 也可反推）。 */
    val fileByKey: Map<String, String> = emptyMap(),
    /**
     * `#DIM` 解析配置（`#DEFINE` 宏表 + 可折叠整型常量表 + 字符串常量表）。
     *
     * 字符串常量表与整型同源（ERH/ERB 全局 `#DIM CONST` 装载结果），但两者在侧车里
     * **分开记录**：`str-constants` 段只是尾部追加，旧侧车（无该段）读出空表，语义不变。
     *
     * [DimParseConfig.functionFolder]（装载期函数折叠回调）**不序列化**——回调无法落进
     * 文本侧车，装载侧必须在消费点重新挂：`GameSession` 的 install 配置处调
     * `withPureStaticDimFolders`（erbcc 编译期解析私有 `#DIM` 与折叠常量表时也在
     * `CorpusCompile` 的解析配置上挂同一回调），否则源码路径能折的纯静态声明在
     * `.erbc` 路径会按「非 SingleTerm」失败（spec: `FunctionMethodTerm.cs:35-56`）。
     */
    val dimParseConfig: DimParseConfig = DimParseConfig(),
    /** 写出侧车的一方标识（`engine` 行；只作诊断，不参与语义）。 */
    val engineTag: String = DEFAULT_ENGINE_TAG,
    /** 配对的 `program.erbc` 的 SHA-256（小写十六进制）；null = 写出时未提供。 */
    val programSha256: String? = null,
    /**
     * 装载/解析期告警清单（`load-warnings` 段；M11-E warnmeta 落盘，供设备侧读回）。
     *
     * **本段为移植侧扩展，原版无对应物**：原版装载告警只走控制台
     * （`ParserMediator.Warn` → `EmueraConsole.PrintWarning`，spec: EmueraConsole.cs:3064-3076、
     * 消费点 ParserMediator.cs:148），且原版每次启动从源码重新装载、不存在「编译产物侧车」，
     * 因此没有任何持久化告警可对照。本段的三字段内容 = erbcc save-15a 的
     * `LoadWarning(file, line, message)`（1 基行号，口径同
     * `.workbuddy/golden-pc-loadwarnings.txt` 前两列），保序直存、不去重不排序。
     *
     * **null 与空清单严格区分**（CMP-82/83/84 教训：严格往返、禁静默容错）：
     * - null = 写出方没有告警通道（旧调用点 / 手构元数据）⇒ 编码**省略整段**，
     *   文本与引入本段之前逐字节相同 ⇒ 新写出端对「无通道模型」的输出，旧读侧照单全收；
     * - emptyList = 有通道但零告警 ⇒ 写出 `load-warnings 0`；读回为 `emptyList()`。
     * 旧侧车（无该段）读回为 null，消费方要「总是拿到清单」用 [loadWarningsOrEmpty]。
     */
    val loadWarnings: List<LoadWarning>? = null,
) {

    /** 一条装载期告警（`文件:行 | 文案`；行 1 基，字段与 erbcc `LoadWarning` 一一对应）。 */
    data class LoadWarning(val file: String, val line: Int, val message: String)

    /** 一条函数外 `#DIM/#DIMS` 原文（`#` 与关键字之间的空白、BOM、行尾注释都可原样带）。 */
    data class RawDim(val raw: String, val file: String, val line: Int)

    /** 一条函数内 `#DIM/#DIMS` 的解析结果 + 所属函数键。 */
    data class PrivateDim(val functionKey: String, val spec: DimSpec)

    /**
     * [loadWarnings] 的消费口径：null（写出方没有告警通道 / 旧侧车）与空清单统一成
     * 空表返回；有清单则原样（保序）。区分「通道缺失」与「零告警」请直接看 [loadWarnings]。
     */
    fun loadWarningsOrEmpty(): List<LoadWarning> = loadWarnings ?: emptyList()

    /** `$标签` 运行期解析（`ProcessRuntime.LabelTable` 的同款语义；两侧键都大写归一）。 */
    fun labelPc(functionKey: String, label: String): Int? =
        labels[functionKey.uppercase()]?.get(label.uppercase())

    /** 函数键 → 源文件相对路径（大写归一）；未登记返回 null。 */
    fun fileOf(functionKey: String): String? = fileByKey[functionKey.uppercase()]

    /** 语句池下标对应的数据节；未登记返回 null。 */
    fun dataSectionAt(statementIndex: Int): List<List<Expr>>? = dataSections[statementIndex]

    /**
     * 换掉侧车的两行头部（`engine` / `program-sha256`）后的副本；其余字段原样共享。
     *
     * 存在理由：`program.erbc` 的 SHA-256 只有落盘时才知道（写侧车的一方手里有产物字节），
     * 而模型在编译期就构造好了。用副本把它补进去，读侧仍能用 [encodeText] 原样回放出同一份文本。
     */
    fun withHeader(
        engineTag: String = this.engineTag,
        programSha256: String? = this.programSha256,
    ): BootMetadata = BootMetadata(
        labels = labels,
        dataSections = dataSections,
        globalDims = globalDims,
        privateDims = privateDims,
        functions = functions,
        fileByKey = fileByKey,
        dimParseConfig = dimParseConfig,
        engineTag = engineTag,
        programSha256 = programSha256,
        loadWarnings = loadWarnings,
    )

    /** 侧车文本（UTF-8 编码前的形态；LF 换行）。确定性见类 KDoc。 */
    fun encodeText(): String = BootMetaCodec.encode(this)

    /** 写出 UTF-8 字节（无 BOM，LF；与 [encodeText] 的字符流一一对应）。 */
    fun encodeBytes(): ByteArray = encodeText().toByteArray(Charsets.UTF_8)

    override fun toString(): String =
        "BootMetadata(functions=${functions.size}, labels=${labels.size} 键 / " +
            "${labels.values.sumOf { it.size }} 条, dataSections=${dataSections.size}, " +
            "globalDims=${globalDims.size}, privateDims=${privateDims.size}, " +
            "macros=${dimParseConfig.macros.size}, constants=${dimParseConfig.intConstants.size}, " +
            "strConstants=${dimParseConfig.strConstants.size}, " +
            "loadWarnings=${loadWarnings?.size ?: "-"})"

    companion object {
        /** 侧车格式版本（与 `.erbc` 容器的 `ErbcFormat.VERSION_1` 无关，各自演进）。 */
        const val FORMAT_VERSION = 1

        /** 默认 `engine` 标签（erbcc 写出时会带自己的版本，见 `BootMetaWriter`）。 */
        const val DEFAULT_ENGINE_TAG: String = Version.NAME + " " + Version.VERSION

        /** 从文本解析（异常见 [BootMetaFormatException]）。 */
        fun parse(text: String): BootMetadata = BootMetaCodec.parse(text)

        /**
         * 从文件读取（严格 UTF-8；允许多余的尾部空白行）。
         *
         * **两趟按行流式**（[BootMetaCodec.countRecords] + [BootMetaCodec.parseReader]），
         * 既不把整份侧车读成一个 String，也不先把每行切成记录表攒起来：第一趟只数「有多少条
         * 记录」（段计数判界 `checkCount` 要的分母），第二趟逐行喂状态机。全量语料侧车
         * 49,119,511 B / 368,806 行，旧的记录表形态实测要为它花掉 688.7 MiB 的瞬时分配
         * （真产物装载期实测读数，见 `LoadChurnProbeTest` 的 `meta` 段）。解析结果与 [parse]
         * 对同一份文本逐字段相同（切行口径也是同一个：只按 `\n` 切、行尾 `\r` 剥掉、空行跳过
         * 但**计入行号**）。
         *
         * **严格 UTF-8（CMP-07）**：解码器显式 `.onMalformedInput(REPORT)` +
         * `.onUnmappableCharacter(REPORT)`，与 erbc 侧的 `StrictUtf8.kt:51-53` 同一口径 ——
         * 不依赖 `CharsetDecoder` 的默认动作，也绝不把坏字节换成 U+FFFD 静默进模型
         * （存储位腐在装机后是真实可达路径）。失败消息指名「严格 UTF-8」，便于从失败屏
         * 直接区分「文件缺/读不动」与「字节坏了」。两趟各开一条流，坏字节在哪趟撞上都是
         * 同一个失败口径。
         *
         * @throws BootMetaFormatException 文件不存在之外的任何格式问题（含非法 UTF-8）
         */
        fun read(path: Path): BootMetadata = try {
            val total = strictReader(path).use { BootMetaCodec.countRecords(it) }
            strictReader(path).use { reader -> BootMetaCodec.parseReader(reader, total) }
        } catch (e: CharacterCodingException) {
            // 严格解码失败：MalformedInputException / UnmappableCharacterException
            throw BootMetaFormatException(
                "$path: 严格 UTF-8 解码失败（${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        } catch (e: java.io.IOException) {
            // 保留 cause：宿主按 cause 链区分「文件缺失 / 映射失败 / 格式坏」（BootFailures）。
            throw BootMetaFormatException("$path: 读取失败（${e.javaClass.simpleName}: ${e.message}）", e)
        }

        /**
         * 严格 UTF-8 的字符流（每次调用开一条新流，解码器各自一份）。
         *
         * 与 erbc 侧的 `StrictUtf8.kt:51-53` 同一口径：显式 `.onMalformedInput(REPORT)` +
         * `.onUnmappableCharacter(REPORT)`，不依赖 `CharsetDecoder` 的默认动作，也绝不把坏
         * 字节换成 U+FFFD 静默进模型。计数趟与解析趟各开一条流，两趟看到的是同一份字节。
         */
        private fun strictReader(path: Path): Reader {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return InputStreamReader(Files.newInputStream(path), decoder)
        }
    }
}

// ======================================================================
// 文本编解码（唯一实现；写侧在 erbcc 的 BootMetaWriter，读侧在此）
// ======================================================================

/**
 * 侧车表达式/括号的嵌套深度上限。
 *
 * 原版这些结构来自进程内的 AST（`FunctionIdentifier.PrintData`），深度受 ERB 源码嵌套限制；
 * 侧车是外部输入，`Scanner.node` 与 `decodeExpr` 的递归都直接跟着括号走，一棵
 * `((((…))))` 的微小文件就能把栈打穿（`StackOverflowError` 是 `Error`，读档链的
 * `catch` 接不住）。256 层远高于真实 PRINTDATA/DATAFORM 表达式的深度。
 */
private const val MAX_EXPR_DEPTH = 256

/**
 * 按侧车计数预分配的容量上限。
 *
 * 真实的记录条数仍逐条由 `next()` 核对；容量只是加速，不承担语义。
 * 计数声明 1e9 时 `ArrayList(1e9)` 会先按引用槽（4~8 B/槽）申请数 GB，而实际记录可能一条都没有。
 */
private const val PREALLOC_CAP = 1024

/**
 * `function index` 的合法上界（CMP-82）。
 *
 * `FuncMeta.index` 直通 `FunctionTable.byIndex` 的 `arrayOfNulls(max + 1)`（`FuncMeta.kt:85-88`），
 * 而侧车是外部输入：一条几十字节的 `function 2147483647 …` 就让 `max + 1` 回绕成负长度，
 * `NegativeArraySizeException` 是 `Error`，装载链的 `catch (RuntimeException)` 接不住。
 *
 * 取 2^20：真实语料 110,072 个函数（最大 index 110,071，BOARD 闸门基线），留 ~9.5x 余量；
 * 同时把「一个 meta 文件能逼出的下游数组」封在 8 MiB 引用槽（`FuncMeta?[2^20]`）以内。
 * 稀疏 index 是既有合法形态（测试里有 `index = 10_000` 的合成件），所以不能按「稠密 0..N-1」卡。
 */
private const val MAX_FUNCTION_INDEX = 1 shl 20

/**
 * `function flags` 的合法上界（CMP-82）。
 *
 * 写出侧 [BootMetaCodec.flagsOf] 只置 7 位（`isEvent`=1 … `returnsStr`=64），所以合法值恒在
 * `0..127`。读侧此前对 flags 不判界：`flags and 128` 这类高位既不报错也不进 [FuncMeta]
 * （`isEvent = flags and 1 != 0` 等只有低 7 位的消费者），存储位腐/手改产物就此**静默丢标志**
 * —— 事件分桶（`FunctionTable.eventBucket`）与返回类型会跟着错，却没有一条装载期判据。
 *
 * 负数同理：`atomInt` 收 `-1` 时 `and 1`/`and 64` 全为真，一份坏档能把普通函数变成
 * 「事件 + 方法 + 返回字符串」。故读侧严卡 `0..127`，与表达式侧的 0/1 位口径对齐。
 */
private const val FUNC_FLAGS_MASK = 0x7F

/**
 * 侧车文本编解码。
 *
 * 记录一览（`<...>` = 裸整数，`"..."` = 引号串，`(...)` = 记号列表）：
 *
 * ```
 * format-version <1>
 * engine "<tag>"
 * program-sha256 "<hex>" | -
 * labels <F>                       # 以下 labelset 行数
 * labelset "<FuncKey>" <L>         # 紧跟 L 条 label
 * label "<Name>" <pc>
 * files <N>                        # 函数键 → 源文件
 * file "<FuncKey>" "<relPath>"
 * data-sections <S>                # 以下 datasec 行数
 * datasec <stmtIndex> <groups>     # groups = ((expr...) (expr...))
 * functions <N>
 * function <index> <defIndex> "<key>" "<name>" <flags> <argInt> <argStr> <localSize> <localsSize> "<position>"
 * global-dims <N>
 * global-dim "<file>" <line> "<raw>"
 * private-dims <N>
 * private-dim "<funcKey>" "<name>" <isStr> <isConst> <isRef> <isGlobal> <isSavedata> <isCharaData> <isStatic> <isPrivate> <dimsCsv> (<defaultsIntCsv>|-|"") (<defaultsStrList>|-)
 * dim-config <systemSaveInBinary 0|1>
 * macros <N>
 * macro "<NAME>" "<body>"
 * constants <N>
 * const "<NAME>" <value>
 * str-constants <N>                # 可选段：旧侧车（无该段）读出空表
 * str-const "<NAME>" "<value>"
 * load-warnings <N>                # 可选段：写出方无告警通道（模型 null）时整段省略
 * load-warn "<file>" <line> "<message>"
 * end
 * ```
 *
 * 表达式树（`ExprTag` 的数值**不复用**：那是 `.erbc` 容器内部码，侧车用自描述记号）：
 *
 * ```
 * (int <v> <line> <col>)
 * (str "<v>" <line> <col>)
 * (null <isInt> <line> <col>)
 * (var "<name>" "<subKey>"|- <type 0int|1str> <isNoArg> <isConst> <line> <col> (<idx>...))
 * (kidx "<varName>" <dim> <line> <col> <expr>)
 * (call "<name>" <type> <line> <col> (<arg>|-)...)
 * (unary <OP> <line> <col> <expr>)
 * (unarya <OP> <line> <col> <expr>)
 * (binary <OP> <type> <line> <col> <left> <right>)
 * (tern <type> <line> <col> <cond> <ifTrue> <ifFalse>)
 * (form <line> <col> (<part>...))       part = ("t" "<text>") | ("e" <expr> <conv>)
 *                                       conv = (curly <width>|- <align>) | (percent <width>|- <align>) | (yenat <ifTrue> <ifFalse>)
 * ```
 */
private object BootMetaCodec {

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    fun encode(m: BootMetadata): String {
        val sb = StringBuilder(1 shl 16)
        sb.append("format-version ").append(BootMetadata.FORMAT_VERSION).append('\n')
        sb.append("engine ").append(quote(m.engineTag)).append('\n')
        sb.append("program-sha256 ").append(m.programSha256?.let(::quote) ?: "-").append('\n')

        sb.append("labels ").append(m.labels.size).append('\n')
        for (key in m.labels.keys.sorted()) {
            val table = m.labels.getValue(key)
            sb.append("labelset ").append(quote(key)).append(' ').append(table.size).append('\n')
            for (name in table.keys.sorted()) {
                sb.append("label ").append(quote(name)).append(' ').append(table.getValue(name)).append('\n')
            }
        }

        sb.append("files ").append(m.fileByKey.size).append('\n')
        for (k in m.fileByKey.keys.sorted()) {
            sb.append("file ").append(quote(k)).append(' ').append(quote(m.fileByKey.getValue(k))).append('\n')
        }

        sb.append("data-sections ").append(m.dataSections.size).append('\n')
        for (idx in m.dataSections.keys.sorted()) {
            sb.append("datasec ").append(idx).append(' ')
            appendGroups(sb, m.dataSections.getValue(idx))
            sb.append('\n')
        }

        val functions = m.functions.sortedBy { it.index }
        sb.append("functions ").append(functions.size).append('\n')
        for (f in functions) {
            sb.append("function ").append(f.index).append(' ').append(f.defIndex).append(' ')
                .append(quote(f.key)).append(' ').append(quote(f.name)).append(' ')
                .append(flagsOf(f)).append(' ')
                .append(f.argIntCount).append(' ').append(f.argStrCount).append(' ')
                .append(f.localSize).append(' ').append(f.localsSize).append(' ')
                .append(quote(f.position)).append('\n')
        }

        sb.append("global-dims ").append(m.globalDims.size).append('\n')
        for (d in m.globalDims) {
            sb.append("global-dim ").append(quote(d.file)).append(' ').append(d.line).append(' ')
                .append(quote(d.raw)).append('\n')
        }

        sb.append("private-dims ").append(m.privateDims.size).append('\n')
        for (p in m.privateDims) {
            val s = p.spec
            sb.append("private-dim ").append(quote(p.functionKey)).append(' ').append(quote(s.name))
                .append(' ').append(bit(s.isStr)).append(' ').append(bit(s.isConst))
                .append(' ').append(bit(s.isRef)).append(' ').append(bit(s.isGlobal))
                .append(' ').append(bit(s.isSavedata)).append(' ').append(bit(s.isCharaData))
                .append(' ').append(bit(s.isStatic)).append(' ').append(bit(s.isPrivate))
                .append(' ').append(if (s.dims.isEmpty()) "-" else s.dims.joinToString(",")).append(' ')
                // 空数组（非 null）用空引号串写出：旧写法写空缺格会让读侧字段整体左移（CMP-83）
                .append(s.defaultsInt?.let { if (it.isEmpty()) "\"\"" else it.joinToString(",") } ?: "-")
                .append(' ')
                .append(s.defaultsStr?.let { list(it.map(::quote)) } ?: "-").append('\n')
        }

        val cfg = m.dimParseConfig
        sb.append("dim-config ").append(bit(cfg.systemSaveInBinary)).append('\n')

        sb.append("macros ").append(cfg.macros.size).append('\n')
        for (k in cfg.macros.keys.sorted()) {
            sb.append("macro ").append(quote(k)).append(' ').append(quote(cfg.macros.getValue(k))).append('\n')
        }

        sb.append("constants ").append(cfg.intConstants.size).append('\n')
        for (k in cfg.intConstants.keys.sorted()) {
            sb.append("const ").append(quote(k)).append(' ').append(cfg.intConstants.getValue(k)).append('\n')
        }

        // 字符串常量（`#DIMS CONST`）：值一律引号串（转义同 quote），键排序保证确定性。
        sb.append("str-constants ").append(cfg.strConstants.size).append('\n')
        for (k in cfg.strConstants.keys.sorted()) {
            sb.append("str-const ").append(quote(k)).append(' ').append(quote(cfg.strConstants.getValue(k)))
                .append('\n')
        }

        // 装载期告警（M11-E warnmeta）：段尾追加、保序直存（不去重不排序，清单本身就是
        // 「按发生序的流水」）。null = 写出方没有告警通道 ⇒ 整段省略，文本与旧版逐字节相同
        // （新写出·旧读不炸的通道）；空清单照写 `load-warnings 0`（与 null 区分明确）。
        val warnings = m.loadWarnings
        if (warnings != null) {
            sb.append("load-warnings ").append(warnings.size).append('\n')
            for (w in warnings) {
                sb.append("load-warn ").append(quote(w.file)).append(' ').append(w.line).append(' ')
                    .append(quote(w.message)).append('\n')
            }
        }

        sb.append("end\n")
        return sb.toString()
    }

    private fun appendGroups(sb: StringBuilder, groups: List<List<Expr>>) {
        sb.append('(')
        for ((i, g) in groups.withIndex()) {
            if (i > 0) sb.append(' ')
            sb.append('(')
            for ((j, e) in g.withIndex()) {
                if (j > 0) sb.append(' ')
                sb.append(encodeExpr(e))
            }
            sb.append(')')
        }
        sb.append(')')
    }

    /** 表达式 → 单行记号串（`when` 作表达式用，新增 [Expr] 实现时编译期失败）。 */
    private fun encodeExpr(e: Expr): String = when (e) {
        is IntLiteral -> "(int ${e.value} ${e.line} ${e.col})"
        is StrLiteral -> "(str ${quote(e.value)} ${e.line} ${e.col})"
        is NullTerm -> "(null ${bit(e.isInt)} ${e.line} ${e.col})"
        is VarRef -> "(var ${quote(e.name)} ${e.subKey?.let(::quote) ?: "-"} ${typeOf(e.operandType)} " +
            "${bit(e.isNoArg)} ${bit(e.isConst)} ${e.line} ${e.col} ${list(e.indices.map(::encodeExpr))})"
        is KeywordIndexArg -> "(kidx ${quote(e.varName)} ${e.dim} ${e.line} ${e.col} ${encodeExpr(e.expr)})"
        is FuncCall -> "(call ${quote(e.name)} ${typeOf(e.operandType)} ${e.line} ${e.col} " +
            "${list(e.args.map { a -> a?.let(::encodeExpr) ?: "-" })})"
        is Unary -> "(unary ${e.op.name} ${e.line} ${e.col} ${encodeExpr(e.operand)})"
        is UnaryAfter -> "(unarya ${e.op.name} ${e.line} ${e.col} ${encodeExpr(e.operand)})"
        is Binary -> "(binary ${e.op.name} ${typeOf(e.operandType)} ${e.line} ${e.col} " +
            "${encodeExpr(e.left)} ${encodeExpr(e.right)})"
        is Ternary -> "(tern ${typeOf(e.operandType)} ${e.line} ${e.col} " +
            "${encodeExpr(e.cond)} ${encodeExpr(e.ifTrue)} ${encodeExpr(e.ifFalse)})"
        is FormStringTerm -> "(form ${e.line} ${e.col} ${list(e.parts.map(::encodePart))})"
    }

    private fun encodePart(p: FormPart): String = when (p) {
        is FormPart.Text -> "(\"t\" ${quote(p.str)})"
        is FormPart.Embedded -> "(\"e\" ${encodeExpr(p.ast)} ${encodeConversion(p.conversion)})"
    }

    private fun encodeConversion(c: FormConversion): String = when (c) {
        is FormConversion.Curly -> "(curly ${c.width?.let(::encodeExpr) ?: "-"} ${bit(c.alignLeft)})"
        is FormConversion.Percent -> "(percent ${c.width?.let(::encodeExpr) ?: "-"} ${bit(c.alignLeft)})"
        is FormConversion.YenAt -> "(yenat ${encodeExpr(c.ifTrue)} ${encodeExpr(c.ifFalse)})"
    }

    private fun flagsOf(f: FuncMeta): Int {
        var v = 0
        if (f.isEvent) v = v or 1
        if (f.isMethod) v = v or 2
        if (f.isOnly) v = v or 4
        if (f.isPri) v = v or 8
        if (f.isLater) v = v or 16
        if (f.isSingle) v = v or 32
        if (f.returnsStr) v = v or 64
        return v
    }

    private fun bit(b: Boolean): String = if (b) "1" else "0"

    private fun typeOf(t: OperandType): String = if (t == OperandType.INT) "0" else "1"

    private fun list(items: List<String>): String = items.joinToString(" ", "(", ")")

    /**
     * 引号串（UTF-8 原文 + JSON 风格转义）。
     *
     * 除 `"` `\` 与 `\n` `\r` `\t` 外，**所有 C0 控制字符都转成 `\uXXXX`** —— 这样一条
     * 记录永远落在一行里（文本按 LF 切行解析，字符串里的原始换行会破坏记录边界）。
     */
    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    private sealed class Node {
        class Lst(val items: List<Node>) : Node()

        sealed class Atom : Node() {
            abstract val text: String
        }

        /** 引号串：值在扫描时就地物化（模型要用的就是它）。 */
        class Quoted(override val text: String) : Atom()

        /**
         * 裸记号：只带源文本上的一段区间（mem4）。
         *
         * 旧实现在这里 `s.substring(from, i)` 造一个 String。真产物 368,806 条记录里的裸记号
         * 是**百万级**（只 `function` 段的 7 个整数位就 110,072 × 7 = 77 万个，再加 labelset
         * 计数、私有 `#DIM` 的 8 个位与两个 CSV、datasec 表达式的行列/类型/0-1 位、`const`
         * 值），它们全是**过渡值**：要么当场折成整数，要么与固定字面量比一比就丢。
         * 区间形态下只有真要取值（进模型、或拼错误消息）才经 [text] 落地成 String。
         */
        class Bare(val src: CharSrc, val from: Int, val until: Int) : Atom() {
            override val text: String get() = src.slice(from, until)

            /** 与字面量等值（先判长度再逐字符比），语义等同 `text == s`，不分配。 */
            fun eq(s: String): Boolean = until - from == s.length && src.matchesAt(from, s)

            fun intOrNull(): Int? = BootMetaCodec.bareIntOrNull(src, from, until)

            fun longOrNull(): Long? = BootMetaCodec.bareLongOrNull(src, from, until)
        }
    }

    /**
     * 记录行的只读字符源（mem4）。
     *
     * 两个实现：[StringCharSrc] 就是一段真 String（整文入口 [parse]）；[LineBuf] 复用一只
     * char 数组当行缓冲（流式入口 [BootMetadata.read]）。旧实现每条记录都要 `line.toString()`
     * 拷一份 String 出来喂 Scanner —— 真产物 368,806 行 / 36,034,478 个字符，其中 267,758 行
     * （31,528,576 个字符）含非 Latin-1 字符，String 的 UTF-16 背衬按 2 B/字符算，静态估 79.6 MiB；
     * 换成复用缓冲后 `LoadChurnProbeTest` 的 meta 窗实测再降 111.2 MiB（差额是行背衬
     * `StringBuilder` 遇到非 Latin-1 字符时的膨胀换底 + 逐行扩容）。缓冲形态下只有**真要取值**
     * 的记号（进模型、或拼错误消息）才经 [slice] 落地成 String。
     */
    private interface CharSrc {
        fun charAt(i: Int): Char

        fun length(): Int

        /** `[from, to)` 落地成 String。 */
        fun slice(from: Int, to: Int): String

        /** `[from, from + t.length)` 与 [t] 逐字相等；越界按不等。 */
        fun matchesAt(from: Int, t: String): Boolean

        /** `[from, to)` 是否只剩空白。 */
        fun blank(from: Int, to: Int): Boolean

        /** `[from, to)` 里第一个 [c] 的下标；没有返回 -1。 */
        fun indexOf(c: Char, from: Int, to: Int): Int
    }

    /** 整文源：所有区间都指在同一个 String 上。 */
    private class StringCharSrc(private val s: String) : CharSrc {
        override fun charAt(i: Int): Char = s[i]

        override fun length(): Int = s.length

        override fun slice(from: Int, to: Int): String = s.substring(from, to)

        override fun matchesAt(from: Int, t: String): Boolean =
            from >= 0 && from + t.length <= s.length && s.regionMatches(from, t, 0, t.length)

        override fun blank(from: Int, to: Int): Boolean = blankRegion(s, from, to)

        override fun indexOf(c: Char, from: Int, to: Int): Int = indexOfChar(s, c, from, to)
    }

    /** 空源：只用于 [Scanner] 的初始态（没有 reset 到任何行之前，读它一律得到越界）。 */
    private val EMPTY_SRC: CharSrc = StringCharSrc("")

    /**
     * 复用行缓冲（[CharSrc] 的流式实现）。
     *
     * 只在「当前记录处理完之前」有效 —— [Cursor] 单槽 + 状态机不跨记录引用记号，
     * 所以进模型的字符串一律已经过 [slice] 拷走。
     */
    private class LineBuf : CharSrc {
        var chars = CharArray(1 shl 12)
        var size = 0

        fun clear() {
            size = 0
        }

        fun appendAll(src: CharArray, from: Int, to: Int) {
            val n = to - from
            if (n <= 0) return
            if (size + n > chars.size) {
                var cap = chars.size
                while (cap < size + n) cap = cap shl 1
                chars = chars.copyOf(cap)
            }
            src.copyInto(chars, size, from, to)
            size += n
        }

        override fun charAt(i: Int): Char = chars[i]

        override fun length(): Int = size

        override fun slice(from: Int, to: Int): String = String(chars, from, to - from)

        override fun matchesAt(from: Int, t: String): Boolean {
            if (from < 0 || from + t.length > size) return false
            for (i in t.indices) {
                if (chars[from + i] != t[i]) return false
            }
            return true
        }

        override fun blank(from: Int, to: Int): Boolean {
            var i = from
            while (i < to) {
                if (!chars[i].isWhitespace()) return false
                i++
            }
            return true
        }

        override fun indexOf(c: Char, from: Int, to: Int): Int {
            var i = from
            while (i < to) {
                if (chars[i] == c) return i
                i++
            }
            return -1
        }
    }

    /**
     * 区间上的十进制 Int 解析，判据与 `text.toIntOrNull()` 逐字相同。
     *
     * 做法是**快路径 + 退回**：只有「可带一个前导减号、其后全是数字、总长不超过 18 位」
     * 走就地累加（结果按 Int 值域判界）；形态一旦超出（带加号前缀、含非数字字符、19 位以上）
     * 就退回 `substring(...).toIntOrNull()`，让 JDK 自己判 —— 于是 `"+5"`、`"-0"`、
     * `"00000000000000000000005"`、`"99999999999999999999"` 这类罕见写法不可能与旧实现分叉。
     */
    private fun bareIntOrNull(s: CharSrc, from: Int, until: Int): Int? {
        val len = until - from
        if (len in 1..18 && s.charAt(from) != '+') {
            val negative = s.charAt(from) == '-'
            var i = if (negative) from + 1 else from
            if (i < until) {
                var v = 0L
                var ok = true
                while (i < until) {
                    val d = s.charAt(i) - '0'
                    if (d < 0 || d > 9) {
                        ok = false
                        break
                    }
                    v = v * 10 + d
                    i++
                }
                if (ok) {
                    return if (negative) {
                        if (v > 2_147_483_648L) null else -v.toInt()
                    } else if (v > 2_147_483_647L) null else v.toInt()
                }
            }
        }
        return s.slice(from, until).toIntOrNull()
    }

    /** [bareIntOrNull] 的 Long 版（快路径同样限 18 位，超出退回 JDK 判溢出）。 */
    private fun bareLongOrNull(s: CharSrc, from: Int, until: Int): Long? {
        val len = until - from
        if (len in 1..18 && s.charAt(from) != '+') {
            val negative = s.charAt(from) == '-'
            var i = if (negative) from + 1 else from
            if (i < until) {
                var v = 0L
                var ok = true
                while (i < until) {
                    val d = s.charAt(i) - '0'
                    if (d < 0 || d > 9) {
                        ok = false
                        break
                    }
                    v = v * 10 + d
                    i++
                }
                if (ok) {
                    // 18 位十进制上界 999_999_999_999_999_999 稳稳落在 Long 值域内；19 位以上
                    // （含 Long.MIN_VALUE 的字面写法）一律落到外面的退回分支，由 JDK 判溢出。
                    return if (negative) -v else v
                }
            }
        }
        return s.slice(from, until).toLongOrNull()
    }

    /** 区间版 `String.isBlank()`。 */
    private fun blankRegion(s: String, from: Int, to: Int): Boolean {
        var i = from
        while (i < to) {
            if (!s[i].isWhitespace()) return false
            i++
        }
        return true
    }

    private fun indexOfChar(s: String, c: Char, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            if (s[i] == c) return i
            i++
        }
        return -1
    }

    /**
     * 记号扫描器（一条记录的载荷区间；越界即格式错误）。
     *
     * mem4：改成**区间**语义（不再要求先把载荷 substring 出来），并由解析状态机持有唯一
     * 实例反复 reset —— 旧实现每条记录 new 一个 Scanner，真产物 368,806 个。
     */
    private class Scanner {
        private var s: CharSrc = EMPTY_SRC
        private var until = 0
        var i = 0

        /** [tryNumber] 命中时的记号区间。 */
        var tokFrom = 0
        var tokTo = 0

        fun reset(src: CharSrc, from: Int, to: Int) {
            s = src
            i = from
            until = to
        }

        fun ws() {
            while (i < until && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++
        }

        fun atEnd(): Boolean {
            ws()
            return i >= until
        }

        /** 就地扫到一个裸记号的末尾（分界字符集与 [node] 的裸记号分支逐字相同）。 */
        private fun scanBareEnd() {
            while (i < until && s.charAt(i) != ' ' && s.charAt(i) != '\t' &&
                s.charAt(i) != '(' && s.charAt(i) != ')' && s.charAt(i) != '"'
            ) i++
        }

        /**
         * 数值字段就地区间（mem4）：先跳空白，首字符是数字或正负号 ⇒ 扫完这个记号，把区间
         * 记在 [tokFrom] / [tokTo] 并返回 true；其它首字符（引号、括号、字母、已到末尾）**不动
         * 游标**返回 false，由调用方退回 [node] 走原口径报错。
         *
         * 为什么这样切就够：引号串一定以 `"` 起、列表一定以 `(` 起，所以「数字或符号开头」
         * 已经排除了 [Node.Quoted] 与 [Node.Lst] 两种失败形态，退回分支只需负责报错文本；
         * 而 `12abc`、`-`、`+5` 这些**数字打头但非法**的形态仍由 [tokFrom]/[tokTo] 的区间
         * 走 [numberText] + JDK 的 toIntOrNull 判据，报错文本与旧实现逐字相同。
         */
        fun tryNumber(): Boolean {
            ws()
            if (i >= until) return false
            val c = s.charAt(i)
            if (c != '-' && c != '+' && (c < '0' || c > '9')) return false
            tokFrom = i
            scanBareEnd()
            tokTo = i
            return true
        }

        /** 引号串字段就地判定（只认首字符，真正的解码仍交给 [quoted]）。 */
        fun tryQuote(): Boolean {
            ws()
            return i < until && s.charAt(i) == '"'
        }

        /** 区间落地：只有错误消息需要原文。 */
        fun numberText(): String = s.slice(tokFrom, tokTo)

        fun numberInt(): Int? = bareIntOrNull(s, tokFrom, tokTo)

        fun numberLong(): Long? = bareLongOrNull(s, tokFrom, tokTo)

        /** 当前游标就在开引号上：解出值（[tryQuote] 命中后调用）。 */
        fun quotedHere(): String = quoted()

        /**
         * 解析一个记号；[depth] 只用于限制括号嵌套（侧车是外部输入，见 [MAX_EXPR_DEPTH]）。
         */
        fun node(depth: Int = 0): Node {
            if (depth > MAX_EXPR_DEPTH) fail("括号嵌套超过 $MAX_EXPR_DEPTH 层")
            ws()
            if (i >= until) fail("载荷意外结束")
            return when (val c = s.charAt(i)) {
                '(' -> {
                    i++
                    val items = ArrayList<Node>()
                    while (true) {
                        ws()
                        if (i >= until) fail("括号未闭合")
                        if (s.charAt(i) == ')') {
                            i++
                            break
                        }
                        items.add(node(depth + 1))
                    }
                    Node.Lst(items)
                }
                '"' -> Node.Quoted(quoted())
                ')' -> fail("多余的右括号")
                else -> {
                    val from = i
                    scanBareEnd()
                    if (from == i) fail("无法识别的字符 '$c'")
                    Node.Bare(s, from, i)
                }
            }
        }

        /**
         * 引号串。
         *
         * 先**无分配地**扫到闭合引号：途中没有反斜杠就只 `substring` 一次（旧实现无论有没有
         * 转义都要走 StringBuilder 逐字符 append + toString = 两次拷贝 + 一个壳）。
         * 含转义时把游标放回串首，交给 [quotedEscaped] —— 那是旧实现的原样循环。
         */
        private fun quoted(): String {
            i++ // 开引号
            val start = i
            var escaped = false
            while (true) {
                if (i >= until) fail("引号串未闭合")
                when (s.charAt(i)) {
                    '"' -> {
                        val end = i
                        if (!escaped) {
                            i = end + 1
                            return s.slice(start, end)
                        }
                        // 慢路径要从不转义的游标重放旧循环，先把游标放回串首。
                        i = start
                        return quotedEscaped()
                    }
                    '\\' -> escaped = true
                }
                i++
            }
        }

        /**
         * 含反斜杠的慢路径：与旧实现**逐字同一个循环**（游标由 [quoted] 放回串首）。
         *
         * 判界（`引号串未闭合` / `转义符后意外结束` / `\u 转义需要 4 位十六进制`）按
         * **整个载荷**的 [until] 算，不是按预扫发现的闭合引号算 —— 旧实现里 `\u` 的十六进制
         * 窗口可以越过闭合引号读进后面的载荷，这条口径必须原样留着。
         */
        private fun quotedEscaped(): String {
            val sb = StringBuilder()
            while (true) {
                if (i >= until) fail("引号串未闭合")
                val c = s.charAt(i)
                if (c == '"') {
                    i++
                    return sb.toString()
                }
                if (c != '\\') {
                    sb.append(c)
                    i++
                    continue
                }
                i++
                if (i >= until) fail("转义符后意外结束")
                when (val n = s.charAt(i)) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 >= until) fail("\\u 转义需要 4 位十六进制")
                        val hex = s.slice(i + 1, i + 5)
                        val v = hex.toIntOrNull(16) ?: fail("\\u 转义不是十六进制: $hex")
                        sb.append(v.toChar())
                        i += 4
                    }
                    else -> fail("未知转义 \\$n")
                }
                i++
            }
        }

        fun fail(message: String): Nothing = throw BootMetaFormatException(message)
    }

    /**
     * 一条记录的**区间**形态：行号 + 关键字区间 + 载荷区间（都在 [src] 上）。
     *
     * 旧实现是 `Record(lineNo, raw.substring(0, sp), raw.substring(sp + 1))`，整本侧车的
     * 「raw + 两个子串 + Record 壳」全量物化之后才开始消费（368,806 行 ≈ 四份文件体积）。
     * 这里关键字只在错误消息里经 [keyword] 落地。
     *
     * **单槽**：游标只持有一个 [Rec]，靠「状态机处理完一条记录之前不再取下一条」这条既有
     * 纪律成立（labels 段的 `labelset` 头三个字段全部读完后才进 `repeat(count)`）。
     */
    private class Rec {
        var lineNo = 0
        var src: CharSrc = EMPTY_SRC
        var kFrom = 0
        var kTo = 0
        var pFrom = 0
        var pTo = 0

        fun keyword(): String = src.slice(kFrom, kTo)

        fun matches(kw: String): Boolean = kTo - kFrom == kw.length && src.matchesAt(kFrom, kw)

        /** 载荷是否只剩空白（旧 `rest.isBlank()` 的区间版）。 */
        fun payloadBlank(): Boolean = src.blank(pFrom, pTo)

        /** 载荷去首尾空白后的内容（旧 `rest.trim()`）。 */
        fun payloadTrimmed(): String = src.slice(pFrom, pTo).trim()
    }

    /**
     * 流式记录游标：一次给一条记录，行号连续，空行跳过但**计入行号** —— 切行口径与旧的
     * 整表实现逐字相同（只按 `\n` 切、行尾一个 `\r` 剥掉）。
     *
     * [total] 是前置计数趟给出的**记录总数**，只服务 `checkCount` 的判界
     * （「后续只剩 M 条」= total - 已消费，与旧实现 `records.size - p` 同一个数）。
     * 计数趟必须与这里用同一套空行判据，否则分母会漂。
     */
    private sealed class Cursor(val total: Int) {
        val rec = Rec()

        private var valid = false

        /** 推进到下一条**记录**（内部自己推进行号、跳过空行）；false = 没有更多记录。 */
        abstract fun fill(): Boolean

        /** 窥下一条记录（不消费）；可选段的位次判据用它，等价于旧实现的 `records[p].keyword`。 */
        fun peek(): Boolean {
            if (!valid) valid = fill()
            return valid
        }

        /** 取下一条记录并把所有权交给调用方；处理完它之前不得再推进。 */
        fun next(): Boolean {
            if (!valid) valid = fill()
            val ok = valid
            valid = false
            return ok
        }
    }

    /** [parse] 的整文源：行区间直接在原 String 上推进，一条记录都不落地成子串。 */
    private class StringCursor(private val text: String, total: Int) : Cursor(total) {
        private val chars = StringCharSrc(text)
        private var pos = 0
        private var lineNo = 0

        override fun fill(): Boolean {
            while (pos <= text.length) {
                val from = pos
                val nl = text.indexOf('\n', from)
                val end = if (nl < 0) text.length else nl
                if (nl < 0 && end == from) return false // 旧实现：无换行结尾的尾行为空时不 emit
                lineNo++
                val hit = end > from && !chars.blank(from, end)
                pos = if (nl < 0) text.length + 1 else nl + 1
                if (hit) {
                    BootMetaCodec.bind(rec, lineNo, chars, from, end)
                    return true
                }
            }
            return false
        }
    }

    /** [BootMetadata.read] 的流式源：8 KiB 字符块 + 复用的行背衬，一行一次 toString。 */
    private class ReaderCursor(private val reader: Reader, total: Int) : Cursor(total) {
        private val buf = CharArray(READ_CHUNK)
        private val line = LineBuf()
        private var pos = 0
        private var len = 0
        private var eof = false
        private var lineNo = 0

        /** 读入一行（不含 LF）到 [line]；EOF 且无残留返回 false。 */
        private fun readLine(): Boolean {
            line.clear()
            while (true) {
                if (pos >= len) {
                    if (eof) return line.size > 0
                    len = reader.read(buf)
                    pos = 0
                    if (len < 0) {
                        eof = true
                        len = 0
                        return line.size > 0
                    }
                }
                val start = pos
                while (pos < len && buf[pos] != '\n') pos++
                line.appendAll(buf, start, pos)
                if (pos < len) {
                    pos++ // 吃掉 LF
                    return true
                }
            }
        }

        override fun fill(): Boolean {
            while (true) {
                if (!readLine()) return false
                lineNo++
                if (line.blank(0, line.size)) continue // 空行：计入行号、不成记录、不落地
                BootMetaCodec.bind(rec, lineNo, line, 0, line.size)
                return true
            }
        }
    }

    /**
     * 把一行原始文本绑成记录（[from]..[to) 含可能的行尾 CR）。
     *
     * 与旧 `emit` 的三步逐字同判据：`raw = line.removeSuffix("\r")`（只剥**一个**尾部 CR）
     * → 无空格则 `keyword = raw.trim()` 且载荷为空 → 有空格则 `keyword = raw.substring(0, sp)`、
     * `payload = raw.substring(sp + 1)`（载荷保留内部与尾部空白，判界由下游负责）。
     */
    private fun bind(rec: Rec, lineNo: Int, src: CharSrc, from: Int, to: Int) {
        var rawTo = to
        if (rawTo > from && src.charAt(rawTo - 1) == '\r') rawTo--
        rec.lineNo = lineNo
        rec.src = src
        val sp = src.indexOf(' ', from, rawTo)
        if (sp < 0) {
            var k0 = from
            while (k0 < rawTo && src.charAt(k0).isWhitespace()) k0++
            var k1 = rawTo
            while (k1 > k0 && src.charAt(k1 - 1).isWhitespace()) k1--
            rec.kFrom = k0
            rec.kTo = k1
            rec.pFrom = k1
            rec.pTo = k1
        } else {
            rec.kFrom = from
            rec.kTo = sp
            rec.pFrom = sp + 1
            rec.pTo = rawTo
        }
    }

    /** 整文源记录数（[parse] 用）；与 [StringCursor] 同一套「非空且非全空白」判据。 */
    private fun countRecords(text: String): Int {
        var total = 0
        var pos = 0
        while (pos <= text.length) {
            val nl = text.indexOf('\n', pos)
            val end = if (nl < 0) text.length else nl
            if (end > pos && !blankRegion(text, pos, end)) total++
            if (nl < 0) break
            pos = nl + 1
        }
        return total
    }

    /**
     * 流式源记录数（[BootMetadata.read] 的第一趟）。
     *
     * 只数行、不落地任何 String：一趟 46.8 MiB 的解码 + 逐字符空白判定，换来第二趟不必
     * 把整本侧车物化成记录表（`checkCount` 的判界分母仍然精确）。
     */
    fun countRecords(reader: Reader): Int {
        val buf = CharArray(READ_CHUNK)
        var total = 0
        var blank = true
        var pending = false
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            for (k in 0 until n) {
                val c = buf[k]
                if (c == '\n') {
                    if (!blank) total++
                    blank = true
                    pending = false
                } else {
                    pending = true
                    if (!c.isWhitespace()) blank = false
                }
            }
        }
        if (pending && !blank) total++
        return total
    }

    fun parse(text: String): BootMetadata = run(StringCursor(text, countRecords(text)))

    /**
     * 逐行解析（[BootMetadata.read] 的入口；[parse] 走同一台状态机 [run]）。
     *
     * 切行口径**就是** `text.split('\n')` 的口径：只按 `\n` 切，行尾的 `\r` 剥掉，
     * 空行跳过但计入行号（错误消息里的「第 N 行」因此与整文实现逐字相同）。
     * 这里不接受 `BufferedReader.lineSequence()` 之类的现成切行：它会额外按孤立 `\r` 切，
     * 而侧车文本的行结构必须以本函数为准（CRC/行号都挂在它上面）。
     *
     * mem4：[total] 由调用方的计数趟给出（文件入口开两次流），解析趟本身不再攒记录表。
     */
    fun parseReader(reader: Reader, total: Int): BootMetadata = run(ReaderCursor(reader, total))

    // ------------------------------------------------------------------
    // 状态机（整文与流式共用）
    // ------------------------------------------------------------------

    private fun run(cursor: Cursor): BootMetadata {
        val sc = Scanner()
        var consumed = 0

        // 装载期驻留池（同一份侧车里内容相同的串只留一个实例）。池只活到本次解析结束，
        // 不进 [BootMetadata]，因此不改变侧车字节、也不新增常驻表。
        //
        // 为什么必须做：`labels` / `files` / `functions` 三段各自 atomString 一次，
        // 同一批 110,072 个函数键会解出 3 份独立 String（全量产物实测 330,216 个实例 /
        // 22.2 MiB，重复率 100%）；源文件路径同理（`files` 的 110,072 个值只对应 3,707 个
        // 不同路径，16.0 MiB）。`labels` 段先出现，故函数键的首个实例由它建立，
        // `files` / `functions` / 私有 `#DIM` 的函数键随后命中同一实例
        // （`FunctionTable` 的索引键因此与侧车表、`FuncMeta.key` 同实例）。
        val keyPool = HashMap<String, String>(1 shl 18)
        val pathPool = HashMap<String, String>(1 shl 13)

        fun internKey(s: String): String {
            val hit = keyPool[s]
            if (hit != null) return hit
            keyPool[s] = s
            return s
        }

        fun internPath(s: String): String {
            val hit = pathPool[s]
            if (hit != null) return hit
            pathPool[s] = s
            return s
        }

        /** 把复用扫描器绑到这条记录的载荷区间上（旧实现是 `Scanner(r.rest)`）。 */
        fun use(rec: Rec) = sc.reset(rec.src, rec.pFrom, rec.pTo)

        fun next(expect: String): Rec {
            if (!cursor.next()) throw BootMetaFormatException("侧车在 $expect 处提前结束")
            val r = cursor.rec
            if (!r.matches(expect)) {
                throw BootMetaFormatException("第 ${r.lineNo} 行：期望 $expect，实际 ${r.keyword()}")
            }
            consumed++
            return r
        }

        /**
         * 段计数的前置校验：非负，且不得超过**后续记录行数**。
         *
         * 侧车是外部输入，`repeat(count)` 与 `ArrayList(count)` 都直接吃这个裸整数：
         * 一个几十字节的 `.meta` 把 functions 计数写成 2,000,000,000，旧实现会先按计数
         * 申请数 GB 的 `ArrayList`（`OutOfMemoryError` 是 `Error`，装载链的 catch 接不住），
         * 之后才在第一次 `next("function")` 撞见文件结束。先按真实行数判界，坏档在分配前
         * 就变成 [BootMetaFormatException]；预分配容量另由 [PREALLOC_CAP] 截断。
         *
         * 「后续记录行数」= 计数趟的总数 - 已消费条数，与旧实现 `records.size - p` 同一个数。
         */
        fun checkCount(count: Int, what: String) {
            if (count < 0) throw BootMetaFormatException("$what 为负：$count")
            val remaining = cursor.total - consumed
            if (count > remaining) {
                throw BootMetaFormatException("$what 声明 $count 条，但后续只剩 $remaining 条记录")
            }
        }

        if (cursor.total == 0) throw BootMetaFormatException("侧车为空")
        if (!cursor.next()) throw BootMetaFormatException("侧车为空")
        val versionRec = cursor.rec
        consumed++
        if (!versionRec.matches("format-version")) {
            throw BootMetaFormatException("第 ${versionRec.lineNo} 行：首条记录必须是 format-version")
        }
        // 旧实现：`versionRec.rest.trim().toIntOrNull()`（不是走 Scanner），口径原样保留。
        val version = versionRec.payloadTrimmed().toIntOrNull()
            ?: throw BootMetaFormatException("第 ${versionRec.lineNo} 行：format-version 不是整数")
        if (version > BootMetadata.FORMAT_VERSION) {
            throw BootMetaFormatException(
                "侧车格式版本 $version 高于本引擎支持的 ${BootMetadata.FORMAT_VERSION}（由更新的 erbcc 写出）",
            )
        }
        if (version < 1) throw BootMetaFormatException("侧车格式版本非法: $version")

        val engineTag = atomString(next("engine"), "engine")
        // 写侧无配对哈希时写裸短横线（见 encode），非空时写引号十六进制串；两种都要认。
        val shaRec = next("program-sha256")
        val shaRest = shaRec.payloadTrimmed()
        val sha = if (shaRest == DASH) null else atomString(shaRec, "program-sha256")

        // ---- labels ----
        val labelCount = atomInt(next("labels"), "labels 计数")
        checkCount(labelCount, "labels 计数")
        val labels = LinkedHashMap<String, Map<String, Int>>()
        var currentKey: String? = null
        var currentKeyLine = -1
        var currentTable = LinkedHashMap<String, Int>()

        /**
         * 收一段 `labelset`。
         *
         * CMP-82：同一函数键出现两段 `labelset` 时旧实现是**后者覆盖前者**（且丢掉前一段的
         * 全部标签）—— 存储位腐或手改产物由此静默少一半 `$标签`，运行到 `GOTO` 才报
         * 「标签不存在」。读侧改为当场拒绝（写侧 `labelPcs` 是 Map，同一键只可能写一段）。
         */
        fun closeLabelset() {
            val key = currentKey ?: return
            if (labels.put(key, currentTable) != null) {
                throw failAt(currentKeyLine, "labelset 函数键重复: $key")
            }
            currentKey = null
            currentTable = LinkedHashMap()
        }
        repeat(labelCount) {
            val head = next("labelset")
            closeLabelset()
            use(head)
            currentKey = internKey(atomString(sc, "labelset 函数键", head.lineNo))
            currentKeyLine = head.lineNo
            val count = atomInt(sc, "labelset 条数", head.lineNo)
            checkCount(count, "第 ${head.lineNo} 行 labelset 条数")
            if (!sc.atEnd()) throw BootMetaFormatException("第 ${head.lineNo} 行：labelset 有多余内容")
            repeat(count) {
                val r = next("label")
                use(r)
                val name = atomString(sc, "label 名", r.lineNo)
                val pc = atomInt(sc, "label pc", r.lineNo)
                if (!sc.atEnd()) throw BootMetaFormatException("第 ${r.lineNo} 行：label 有多余内容")
                // CMP-82：同段内同名标签旧实现静默覆盖 pc（后写者生效），与上面的段重复同病。
                if (currentTable.put(name, pc) != null) {
                    throw failAt(r.lineNo, "label 名重复: $name（函数 $currentKey）")
                }
            }
            if (count > 0) closeLabelset()
        }
        closeLabelset()

        // ---- files（函数键 → 源文件）----
        val fileCount = atomInt(next("files"), "files 计数")
        checkCount(fileCount, "files 计数")
        val fileByKey = LinkedHashMap<String, String>()
        repeat(fileCount) {
            val r = next("file")
            use(r)
            val key = internKey(atomString(sc, "file 函数键", r.lineNo))
            val path = internPath(atomString(sc, "file 路径", r.lineNo))
            if (!sc.atEnd()) throw BootMetaFormatException("第 ${r.lineNo} 行：file 有多余内容")
            // CMP-82：同一函数键两行 `file` 旧实现后者覆盖（前一条源文件定位静默丢失）。
            if (fileByKey.put(key, path) != null) throw failAt(r.lineNo, "file 函数键重复: $key")
        }

        // ---- data-sections ----
        val sectionCount = atomInt(next("data-sections"), "data-sections 计数")
        checkCount(sectionCount, "data-sections 计数")
        val dataSections = LinkedHashMap<Int, List<List<Expr>>>()
        repeat(sectionCount) {
            val r = next("datasec")
            use(r)
            val idx = atomInt(sc, "datasec 语句下标", r.lineNo)
            // 值域闸（CMP-82）：下标要拿去 `program.statement(i)` 取消费者语句，负值必然越界；
            // 上界只能对真语句池判，装载侧由 GameSession 的数据节惰性索引负责。
            if (idx < 0) throw failAt(r.lineNo, "datasec 语句下标为负: $idx")
            val groups = decodeGroups(sc, r.lineNo)
            if (!sc.atEnd()) throw BootMetaFormatException("第 ${r.lineNo} 行：datasec 有多余内容")
            // CMP-82：同一语句下标两节数据旧实现后者覆盖（PRINTDATA 的选项静默换组）。
            if (dataSections.put(idx, groups) != null) throw failAt(r.lineNo, "datasec 语句下标重复: $idx")
        }

        // ---- functions ----
        val funcCount = atomInt(next("functions"), "functions 计数")
        checkCount(funcCount, "functions 计数")
        val functions = ArrayList<FuncMeta>(minOf(funcCount, PREALLOC_CAP))
        // CMP-82：`index` 是 `FunctionTable.byIndex` 的槽位，重复即后者覆盖（前一条函数
        // 在运行期「不存在」）；`key` 不能当键 —— 事件成员与合成入口**合法共用**事件名
        // （全量语料实测 30 组，如 EVENTLOAD 10 条成员同键，BOARD 闸门基线）。
        val seenFuncIndex = HashSet<Int>(minOf(funcCount, PREALLOC_CAP))
        repeat(funcCount) {
            val r = next("function")
            use(r)
            val index = atomInt(sc, "function index", r.lineNo)
            // 值域闸（CMP-82）：负值会被 byIndex 静默丢弃，近 Int.MAX 会让 max+1 回绕成负长度数组
            if (index < 0 || index > MAX_FUNCTION_INDEX) {
                throw failAt(r.lineNo, "function index 越界: $index（合法范围 0..$MAX_FUNCTION_INDEX）")
            }
            if (!seenFuncIndex.add(index)) throw failAt(r.lineNo, "function index 重复: $index")
            val defIndex = atomInt(sc, "function defIndex", r.lineNo)
            // 装载序号只用于事件同桶排序，负值没有语义来源（写出侧恒 >= 1）。
            if (defIndex < 0) throw failAt(r.lineNo, "function defIndex 为负: $defIndex")
            val key = internKey(atomString(sc, "function key", r.lineNo))
            // 名字与键在语料里 97.3% 逐字相同（110,072 条里 107,059 条），走同一个池即可共享实例；
            // 大小写不同的 3,013 条仍是各自的串（内容不同，identity 不能合并）。
            val name = internKey(atomString(sc, "function name", r.lineNo))
            val flags = atomInt(sc, "function flags", r.lineNo)
            // 位宽闸（CMP-82）：高位（>=128）没有消费者，旧实现静默丢标志；负值则全位为真。
            if (flags < 0 || flags > FUNC_FLAGS_MASK) {
                throw failAt(r.lineNo, "function flags 越界: $flags（合法范围 0..$FUNC_FLAGS_MASK）")
            }
            val argInt = atomInt(sc, "function argIntCount", r.lineNo)
            val argStr = atomInt(sc, "function argStrCount", r.lineNo)
            val localSize = atomInt(sc, "function localSize", r.lineNo)
            val localsSize = atomInt(sc, "function localsSize", r.lineNo)
            // 四个计数都是数组长度（形参桶 / LOCAL / LOCALS）：负值会让下游
            // `NegativeArraySizeException`（Error，装载链的 catch 接不住），此处先拦。
            if (argInt < 0) throw failAt(r.lineNo, "function argIntCount 为负: $argInt")
            if (argStr < 0) throw failAt(r.lineNo, "function argStrCount 为负: $argStr")
            if (localSize < 0) throw failAt(r.lineNo, "function localSize 为负: $localSize")
            if (localsSize < 0) throw failAt(r.lineNo, "function localsSize 为负: $localsSize")
            val position = atomString(sc, "function position", r.lineNo)
            if (!sc.atEnd()) throw BootMetaFormatException("第 ${r.lineNo} 行：function 有多余内容")
            functions.add(
                FuncMeta(
                    index = index,
                    key = key,
                    name = name,
                    isEvent = flags and 1 != 0,
                    isMethod = flags and 2 != 0,
                    isOnly = flags and 4 != 0,
                    isPri = flags and 8 != 0,
                    isLater = flags and 16 != 0,
                    isSingle = flags and 32 != 0,
                    returnsStr = flags and 64 != 0,
                    argIntCount = argInt,
                    argStrCount = argStr,
                    localSize = localSize,
                    localsSize = localsSize,
                    defIndex = defIndex,
                    position = position,
                ),
            )
        }

        // ---- global dims ----
        val globalCount = atomInt(next("global-dims"), "global-dims 计数")
        checkCount(globalCount, "global-dims 计数")
        val globalDims = ArrayList<BootMetadata.RawDim>(minOf(globalCount, PREALLOC_CAP))
        // CMP-82：`global-dims` 是**列表**，旧实现重复记录照单追加（同一条声明装两遍）。
        // 写侧逐条登记「文件 + 行 + 原文」，三元组相同只可能来自坏档 ⇒ 读侧当场拒绝。
        val seenGlobalDim = HashSet<String>(minOf(globalCount, PREALLOC_CAP))
        repeat(globalCount) {
            val r = next("global-dim")
            use(r)
            val file = internPath(atomString(sc, "global-dim file", r.lineNo))
            val line = atomInt(sc, "global-dim line", r.lineNo)
            val raw = atomString(sc, "global-dim raw", r.lineNo)
            if (!sc.atEnd()) throw BootMetaFormatException("第 ${r.lineNo} 行：global-dim 有多余内容")
            if (!seenGlobalDim.add("$file\u0000$line\u0000$raw")) {
                throw failAt(r.lineNo, "global-dim 记录重复: $file:$line")
            }
            globalDims.add(BootMetadata.RawDim(raw, file, line))
        }

        // ---- private dims ----
        val privateCount = atomInt(next("private-dims"), "private-dims 计数")
        checkCount(privateCount, "private-dims 计数")
        val privateDims = ArrayList<BootMetadata.PrivateDim>(minOf(privateCount, PREALLOC_CAP))
        // CMP-82：`private-dims` 同样是列表，旧实现重复声明照单追加（第二条在装配期被
        // `declareUserVar` 拒掉，降级成 PRIVATE_DIM_REJECTED 告警）。读侧按「函数键 + 变量名」
        // 严卡重复（全量语料 19,757 条实测 0 重复）。
        val seenPrivateDim = HashSet<String>(minOf(privateCount, PREALLOC_CAP))
        repeat(privateCount) {
            val r = next("private-dim")
            use(r)
            val funcKey = internKey(atomString(sc, "private-dim 函数键", r.lineNo))
            val name = atomString(sc, "private-dim 变量名", r.lineNo)
            if (!seenPrivateDim.add("$funcKey\u0000$name")) {
                throw failAt(r.lineNo, "private-dim 声明重复: $funcKey/$name")
            }
            val isStr = atomBool(sc, "isStr", r.lineNo)
            val isConst = atomBool(sc, "isConst", r.lineNo)
            val isRef = atomBool(sc, "isRef", r.lineNo)
            val isGlobal = atomBool(sc, "isGlobal", r.lineNo)
            val isSavedata = atomBool(sc, "isSavedata", r.lineNo)
            val isCharaData = atomBool(sc, "isCharaData", r.lineNo)
            val isStatic = atomBool(sc, "isStatic", r.lineNo)
            val isPrivate = atomBool(sc, "isPrivate", r.lineNo)
            val dims = intCsv(atomBare(sc, "private-dim dims", r.lineNo), r.lineNo)
            val defaultsInt = decodeDefaultsInt(sc, r.lineNo)
            val defaultsStr = decodeStrListOpt(sc, r.lineNo)
            if (!sc.atEnd()) throw BootMetaFormatException("第 ${r.lineNo} 行：private-dim 有多余内容")
            privateDims.add(
                BootMetadata.PrivateDim(
                    funcKey,
                    DimSpec(
                        name = name,
                        isStr = isStr,
                        dims = dims,
                        isConst = isConst,
                        isRef = isRef,
                        isGlobal = isGlobal,
                        isSavedata = isSavedata,
                        isCharaData = isCharaData,
                        isStatic = isStatic,
                        isPrivate = isPrivate,
                        defaultsInt = defaultsInt,
                        defaultsStr = defaultsStr,
                    ),
                ),
            )
        }

        // ---- dim parse config ----
        val cfgRec = next("dim-config")
        // 位口径统一（CMP-82）：旧实现 `atomInt(...) != 0` 与表达式侧 `boolAt` 严卡 0/1 是两套尺。
        val systemSaveInBinary = atomBool(cfgRec, "dim-config")

        val macroCount = atomInt(next("macros"), "macros 计数")
        checkCount(macroCount, "macros 计数")
        val macros = LinkedHashMap<String, String>()
        repeat(macroCount) {
            val r = next("macro")
            use(r)
            val k = atomString(sc, "macro 名", r.lineNo)
            val v = atomString(sc, "macro 体", r.lineNo)
            if (!sc.atEnd()) throw BootMetaFormatException("第 ${r.lineNo} 行：macro 有多余内容")
            // CMP-82：同名宏旧实现后者覆盖（`#DEFINE` 表是 Map，重复只可能来自坏档）。
            if (macros.put(k, v) != null) throw failAt(r.lineNo, "macro 名重复: $k")
        }

        val constCount = atomInt(next("constants"), "constants 计数")
        checkCount(constCount, "constants 计数")
        val consts = LinkedHashMap<String, Long>()
        repeat(constCount) {
            val r = next("const")
            use(r)
            val k = atomString(sc, "const 名", r.lineNo)
            val v = atomLong(sc, "const 值", r.lineNo)
            if (!sc.atEnd()) throw BootMetaFormatException("第 ${r.lineNo} 行：const 有多余内容")
            if (consts.put(k, v) != null) throw failAt(r.lineNo, "const 名重复: $k")
        }

        // `str-constants` 段可选：旧侧车在 `const` 之后直接 `end`（本节为尾部追加）。
        // 其余未知关键字仍交给下面的 next("end") 报「期望 end，实际 X」。
        val strConsts = LinkedHashMap<String, String>()
        if (cursor.peek() && cursor.rec.matches("str-constants")) {
            val strConstCount = atomInt(next("str-constants"), "str-constants 计数")
            // 与其余段同一口径（CMP-82）：先按真实剩余记录数判界，坏档在第一条 str-const 之前就失败
            checkCount(strConstCount, "str-constants 计数")
            repeat(strConstCount) {
                val r = next("str-const")
                use(r)
                val k = atomString(sc, "str-const 名", r.lineNo)
                val v = atomString(sc, "str-const 值", r.lineNo)
                if (!sc.atEnd()) throw BootMetaFormatException("第 ${r.lineNo} 行：str-const 有多余内容")
                if (strConsts.put(k, v) != null) throw failAt(r.lineNo, "str-const 名重复: $k")
            }
        }

        // `load-warnings` 段可选（段序在 `str-constants` 之后、`end` 之前）。缺段 = 写出方
        // 没有告警通道 ⇒ null（与 `load-warnings 0` 的空清单严格区分，见 [BootMetadata.loadWarnings] KDoc）。
        // 计数必须过 checkCount —— CMP-82 点名 str-constants 是「唯一没调 checkCount 的段」，
        // 本段不复制这个洞：裸整数先判非负、再判不超过后续记录行数，之后才进 repeat。
        // 条目序 = 写出序（不去重不排序），file 走与源文件同池的驻留（告警定位就是源路径）。
        val loadWarnings: List<BootMetadata.LoadWarning>? =
            if (cursor.peek() && cursor.rec.matches("load-warnings")) {
                val warnCount = atomInt(next("load-warnings"), "load-warnings 计数")
                checkCount(warnCount, "load-warnings 计数")
                val list = ArrayList<BootMetadata.LoadWarning>(minOf(warnCount, PREALLOC_CAP))
                repeat(warnCount) {
                    val r = next("load-warn")
                    use(r)
                    val file = internPath(atomString(sc, "load-warn file", r.lineNo))
                    val warnLine = atomInt(sc, "load-warn line", r.lineNo)
                    val message = atomString(sc, "load-warn message", r.lineNo)
                    if (!sc.atEnd()) throw BootMetaFormatException("第 ${r.lineNo} 行：load-warn 有多余内容")
                    list.add(BootMetadata.LoadWarning(file, warnLine, message))
                }
                list
            } else {
                null
            }

        val end = next("end")
        if (!end.payloadBlank()) throw BootMetaFormatException("第 ${end.lineNo} 行：end 后不应有内容")
        if (cursor.peek()) {
            throw BootMetaFormatException("第 ${cursor.rec.lineNo} 行：end 之后还有记录（${cursor.rec.keyword()}）")
        }

        return BootMetadata(
            labels = labels,
            dataSections = dataSections,
            globalDims = globalDims,
            privateDims = privateDims,
            functions = functions,
            fileByKey = fileByKey,
            dimParseConfig = DimParseConfig(
                systemSaveInBinary = systemSaveInBinary,
                macros = macros,
                intConstants = consts,
                strConstants = strConsts,
            ),
            engineTag = engineTag,
            programSha256 = sha,
            loadWarnings = loadWarnings,
        )
    }

    // ---- 记录内字段 ----

    /**
     * 整条记录只有一个引号串字段（`engine` / `program-sha256`）。
     *
     * 行号一律传 -1：旧实现的单字段整行入口 `atomString(rest, what)` 就是这个口径
     * （`atomString(sc, what, -1)` ⇒ [failAt] 不加「第 N 行：」前缀），错误消息必须逐字保留。
     */
    private fun atomString(rec: Rec, what: String): String {
        val sc = Scanner()
        sc.reset(rec.src, rec.pFrom, rec.pTo)
        val s = atomString(sc, what, -1)
        if (!sc.atEnd()) throw BootMetaFormatException("$what 有多余内容")
        return s
    }

    /** 整条记录只有一个整数字段（各段计数）；行号口径同 [atomString]。 */
    private fun atomInt(rec: Rec, what: String): Int {
        val sc = Scanner()
        sc.reset(rec.src, rec.pFrom, rec.pTo)
        val v = atomInt(sc, what, -1)
        if (!sc.atEnd()) throw BootMetaFormatException("$what 有多余内容")
        return v
    }

    /** 整条记录只有一个 0/1 位字段（`dim-config`）；错误消息带行号，口径同旧整行版。 */
    private fun atomBool(rec: Rec, what: String): Boolean {
        val sc = Scanner()
        sc.reset(rec.src, rec.pFrom, rec.pTo)
        val v = atomBool(sc, what, rec.lineNo)
        if (!sc.atEnd()) throw failAt(rec.lineNo, "$what 有多余内容")
        return v
    }

    private fun atomString(sc: Scanner, what: String, line: Int): String {
        if (sc.tryQuote()) return sc.quotedHere()
        val n = sc.node()
        if (n !is Node.Quoted) throw failAt(line, "$what 必须是引号串")
        return n.text
    }

    /** 裸记号（整数 / `-` / 逗号分隔的 CSV 等写侧不加引号的字段）；返回源串上的区间。 */
    private fun atomBare(sc: Scanner, what: String, line: Int): Node.Bare {
        val n = sc.node()
        if (n !is Node.Bare) throw failAt(line, "$what 必须是裸记号")
        return n
    }

    private fun atomInt(sc: Scanner, what: String, line: Int): Int {
        if (sc.tryNumber()) {
            return sc.numberInt() ?: throw failAt(line, "$what 不是整数: ${sc.numberText()}")
        }
        val n = sc.node()
        if (n !is Node.Bare) throw failAt(line, "$what 必须是整数")
        return n.intOrNull() ?: throw failAt(line, "$what 不是整数: ${n.text}")
    }

    private fun atomLong(sc: Scanner, what: String, line: Int): Long {
        if (sc.tryNumber()) {
            return sc.numberLong() ?: throw failAt(line, "$what 不是整数: ${sc.numberText()}")
        }
        val n = sc.node()
        if (n !is Node.Bare) throw failAt(line, "$what 必须是整数")
        return n.longOrNull() ?: throw failAt(line, "$what 不是整数: ${n.text}")
    }

    private fun atomBool(sc: Scanner, what: String, line: Int): Boolean {
        val v = atomInt(sc, what, line)
        // CMP-82：位口径只有一套 —— 写出侧恒写 0/1，读侧旧实现 `!= 0` 把 2、-1 都当 true，
        // 表达式侧 `boolAt` 却严卡 0/1。统一成严卡（存储位腐 / 手改产物当场拒绝）。
        if (v != 0 && v != 1) throw failAt(line, "$what 应为 0/1，实为 $v")
        return v == 1
    }

    private fun failAt(line: Int, message: String): Nothing =
        throw BootMetaFormatException(if (line > 0) "第 $line 行：$message" else message)

    /**
     * 逗号分隔整数表（`private-dim` 的 `dims`）。
     *
     * mem4：旧实现 `text.split(',').map{}` ⇒ 每处一个 String[] 加一堆短命 String；这里在
     * 源串上就地找逗号分段。`split` 不 trim、不丢空段的口径原样保留（`"1,,2"` 的中间空段
     * 仍旧在错误消息里以原文出现）。
     *
     * 值域闸（CMP-82）：`dims` 各项是 `DimSpec.elements` 的乘数、也是下游数组长度。
     * REF 变量的「全 0」是合法形态（`DimSpec` 注释：原版 return 前不写 Lengths），故只拦负值。
     */
    private fun intCsv(bare: Node.Bare, line: Int): IntArray {
        val s = bare.src
        if (bare.eq(DASH)) return IntArray(0)
        val out = ArrayList<Int>(4)
        var p = bare.from
        while (true) {
            val comma = s.indexOf(',', p, bare.until)
            val end = if (comma < 0) bare.until else comma
            val v = bareIntOrNull(s, p, end) ?: throw failAt(line, "dims 段不是整数: ${s.slice(p, end)}")
            if (v < 0) throw failAt(line, "dims 段出现负长度: $v")
            out.add(v)
            if (comma < 0) return out.toIntArray()
            p = comma + 1
        }
    }

    /** [intCsv] 的 Long 版（`defaultsInt` 初值表）；同样不 trim、不丢空段。 */
    private fun longCsv(bare: Node.Bare, line: Int): LongArray {
        val s = bare.src
        val out = ArrayList<Long>(4)
        var p = bare.from
        while (true) {
            val comma = s.indexOf(',', p, bare.until)
            val end = if (comma < 0) bare.until else comma
            out.add(bareLongOrNull(s, p, end) ?: throw failAt(line, "初值不是整数: ${s.slice(p, end)}"))
            if (comma < 0) return out.toLongArray()
            p = comma + 1
        }
    }

    /**
     * `defaultsInt` 段：`-` = null；空引号串 `""` = **空数组（非 null）**；其余是逗号分隔整数。
     *
     * CMP-83：此前空数组被写成「空缺格」（两个空格），读侧字段整体左移报「载荷意外结束」
     * ⇒ 模型 → 文本 → 模型不是互逆的。空数组在语料里不可达（`DimParser` 禁空初值），
     * 但 `DimSpec` 是公开构造面，往返必须互逆。
     *
     * 兼容性：旧写出侧从不产 `""`（空数组不可达），旧读侧遇到它会报
     * 「defaultsInt 必须是裸记号或空引号串」而不是静默错读 ⇒ 不需要升 `FORMAT_VERSION`。
     */
    private fun decodeDefaultsInt(sc: Scanner, line: Int): LongArray? {
        val n = sc.node()
        if (n !is Node.Atom) throw failAt(line, "defaultsInt 必须是裸记号或空引号串")
        if (n is Node.Quoted) {
            if (n.text.isNotEmpty()) throw failAt(line, "defaultsInt 引号串只能为空（空数组记号）: ${n.text}")
            return LongArray(0)
        }
        val bare = n as Node.Bare
        if (bare.eq(DASH)) return null
        return longCsv(bare, line)
    }

    private fun decodeStrListOpt(sc: Scanner, line: Int): List<String>? {
        val n = sc.node()
        if (n is Node.Bare && n.eq(DASH)) return null
        if (n !is Node.Lst) throw failAt(line, "defaultsStr 必须是列表或 -")
        return n.items.map { if (it is Node.Quoted) it.text else throw failAt(line, "defaultsStr 元素必须是引号串") }
    }

    private fun decodeGroups(sc: Scanner, line: Int): List<List<Expr>> {
        val n = sc.node()
        if (n !is Node.Lst) throw failAt(line, "datasec 载荷必须是组列表")
        return n.items.map { g ->
            if (g !is Node.Lst) throw failAt(line, "数据节的一组必须是列表")
            g.items.map { decodeExpr(it, line) }
        }
    }

    // ---- 表达式树 ----

    private fun decodeExpr(node: Node, line: Int, depth: Int = 0): Expr {
        if (depth > MAX_EXPR_DEPTH) throw failAt(line, "表达式嵌套超过 $MAX_EXPR_DEPTH 层")
        if (node !is Node.Lst || node.items.isEmpty()) throw failAt(line, "表达式节点必须是列表")
        val head = node.items[0]
        val tag = (head as? Node.Bare) ?: throw failAt(line, "表达式节点缺少标签")
        val a = node.items
        fun need(n: Int) {
            if (a.size != n) throw failAt(line, "表达式 ${tag.text} 需要 ${n - 1} 个字段，实为 ${a.size - 1}")
        }
        fun intAt(i: Int): Int {
            val n = a[i]
            if (n is Node.Bare) {
                return n.intOrNull() ?: throw failAt(line, "表达式 ${tag.text} 第 $i 字段不是整数: ${n.text}")
            }
            if (n is Node.Quoted) throw failAt(line, "表达式 ${tag.text} 第 $i 字段不应是引号串")
            throw failAt(line, "表达式 ${tag.text} 第 $i 字段不是原子")
        }
        fun longAt(i: Int): Long {
            val n = a[i]
            if (n is Node.Bare) {
                return n.longOrNull() ?: throw failAt(line, "表达式 ${tag.text} 第 $i 字段不是整数: ${n.text}")
            }
            if (n is Node.Quoted) throw failAt(line, "表达式 ${tag.text} 第 $i 字段不应是引号串")
            throw failAt(line, "表达式 ${tag.text} 第 $i 字段不是原子")
        }
        fun strAt(i: Int): String {
            val atom = a[i] as? Node.Atom ?: throw failAt(line, "表达式 ${tag.text} 第 $i 字段不是原子")
            if (atom !is Node.Quoted) throw failAt(line, "表达式 ${tag.text} 第 $i 字段必须是引号串")
            return atom.text
        }
        fun optStrAt(i: Int): String? {
            val n = a[i]
            if (n is Node.Quoted) return n.text
            if (n is Node.Bare) {
                if (n.eq(DASH)) return null
                throw failAt(line, "表达式 ${tag.text} 第 $i 字段必须是引号串或 -")
            }
            throw failAt(line, "表达式 ${tag.text} 第 $i 字段不是原子")
        }
        fun typeAt(i: Int): OperandType = when (val v = intAt(i)) {
            // CMP-82：类型位写出侧只写 0/1；旧实现 `== 0 -> INT else STR` 把 2、-1 静默折成 STR。
            0 -> OperandType.INT
            1 -> OperandType.STR
            else -> throw failAt(line, "表达式 ${tag.text} 第 $i 字段类型位应为 0/1，实为 $v")
        }
        fun boolAt(i: Int): Boolean {
            val v = intAt(i)
            if (v != 0 && v != 1) throw failAt(line, "表达式 ${tag.text} 第 $i 字段应是 0/1")
            return v != 0
        }
        fun listAt(i: Int): List<Node> {
            val l = a[i] as? Node.Lst ?: throw failAt(line, "表达式 ${tag.text} 第 $i 字段必须是列表")
            return l.items
        }
        fun opAt(i: Int): OperatorCode {
            val atom = a[i] as? Node.Atom ?: throw failAt(line, "表达式 ${tag.text} 第 $i 字段不是原子")
            val text = atom.text
            return OperatorCode.entries.firstOrNull { it.name == text }
                ?: throw failAt(line, "未知运算符 $text")
        }

        // `when (tag)` 的旧写法要为分支判定先物化标签串；换成区间等值比较（11 个分支 +
        // 落进 else 才取 tag.text 拼错误消息）。
        return when {
            tag.eq("int") -> {
                need(4)
                IntLiteral(longAt(1), intAt(2), intAt(3))
            }
            tag.eq("str") -> {
                need(4)
                StrLiteral(strAt(1), intAt(2), intAt(3))
            }
            tag.eq("null") -> {
                need(4)
                NullTerm(boolAt(1), intAt(2), intAt(3))
            }
            tag.eq("var") -> {
                need(9)
                VarRef(
                    name = strAt(1),
                    indices = listAt(8).map { decodeExpr(it, line, depth + 1) },
                    isNoArg = boolAt(4),
                    operandType = typeAt(3),
                    isConst = boolAt(5),
                    subKey = optStrAt(2),
                    line = intAt(6),
                    col = intAt(7),
                )
            }
            tag.eq("kidx") -> {
                need(6)
                KeywordIndexArg(
                    expr = decodeExpr(a[5], line, depth + 1),
                    varName = strAt(1),
                    dim = intAt(2),
                    line = intAt(3),
                    col = intAt(4),
                )
            }
            tag.eq("call") -> {
                need(6)
                FuncCall(
                    name = strAt(1),
                    args = listAt(5).map { arg ->
                        if (arg is Node.Bare && arg.eq(DASH)) {
                            null
                        } else {
                            decodeExpr(arg, line, depth + 1)
                        }
                    },
                    operandType = typeAt(2),
                    line = intAt(3),
                    col = intAt(4),
                )
            }
            tag.eq("unary") -> {
                need(5)
                Unary(opAt(1), decodeExpr(a[4], line, depth + 1), intAt(2), intAt(3))
            }
            tag.eq("unarya") -> {
                need(5)
                UnaryAfter(opAt(1), decodeExpr(a[4], line, depth + 1), intAt(2), intAt(3))
            }
            tag.eq("binary") -> {
                need(7)
                Binary(
                    opAt(1),
                    decodeExpr(a[5], line, depth + 1),
                    decodeExpr(a[6], line, depth + 1),
                    typeAt(2),
                    intAt(3),
                    intAt(4),
                )
            }
            tag.eq("tern") -> {
                need(7)
                Ternary(
                    cond = decodeExpr(a[4], line, depth + 1),
                    ifTrue = decodeExpr(a[5], line, depth + 1),
                    ifFalse = decodeExpr(a[6], line, depth + 1),
                    operandType = typeAt(1),
                    line = intAt(2),
                    col = intAt(3),
                )
            }
            tag.eq("form") -> {
                need(4)
                val parts = listAt(3).map { decodePart(it, line, depth + 1) }
                FormStringTerm(parts, intAt(1), intAt(2))
            }
            else -> throw failAt(line, "未知表达式标签: ${tag.text}")
        }
    }

    private fun decodePart(node: Node, line: Int, depth: Int = 0): FormPart {
        if (node !is Node.Lst || node.items.size < 2) throw failAt(line, "form 段必须是列表")
        val tagAtom = node.items[0] as? Node.Atom ?: throw failAt(line, "form 段缺少标签")
        val payload = node.items[1]
        return when (tagAtom.text) {
            "t" -> {
                if (node.items.size != 2) throw failAt(line, "form 文本段只带一个字段")
                FormPart.Text(
                    (payload as? Node.Quoted ?: throw failAt(line, "form 文本段必须是引号串")).text,
                )
            }
            "e" -> {
                if (node.items.size != 3) throw failAt(line, "form 内嵌段需要 expr + 转换")
                FormPart.Embedded(
                    decodeExpr(payload, line, depth + 1),
                    decodeConversion(node.items[2], line, depth + 1),
                )
            }
            else -> throw failAt(line, "未知 form 段标签: ${tagAtom.text}")
        }
    }

    private fun decodeConversion(node: Node, line: Int, depth: Int = 0): FormConversion {
        if (node !is Node.Lst || node.items.isEmpty()) throw failAt(line, "form 转换必须是列表")
        val tagAtom = node.items[0] as? Node.Atom ?: throw failAt(line, "form 转换缺少标签")
        val tag = tagAtom.text
        fun widthAt(i: Int): Expr? {
            val n = node.items[i]
            if (n is Node.Bare && n.eq(DASH)) return null
            return decodeExpr(n, line, depth + 1)
        }
        fun alignAt(i: Int): Boolean {
            val n = node.items[i] as? Node.Atom ?: throw failAt(line, "对齐位不是原子")
            // CMP-82：写出侧 `bit()` 恒写 0/1；旧实现 `text != "0"` 把 2、-1、"true" 都当对齐。
            val bit = n is Node.Bare && n.until - n.from == 1 &&
                (n.src.charAt(n.from) == '0' || n.src.charAt(n.from) == '1')
            if (!bit) throw failAt(line, "form 转换对齐位应为 0/1，实为 ${n.text}")
            return (n as Node.Bare).src.charAt(n.from) == '1'
        }
        return when (tag) {
            "curly" -> {
                if (node.items.size != 3) throw failAt(line, "curly 转换需要 2 个字段")
                FormConversion.Curly(widthAt(1), alignAt(2))
            }
            "percent" -> {
                if (node.items.size != 3) throw failAt(line, "percent 转换需要 2 个字段")
                FormConversion.Percent(widthAt(1), alignAt(2))
            }
            "yenat" -> {
                if (node.items.size != 3) throw failAt(line, "yenat 转换需要 2 个字段")
                FormConversion.YenAt(
                    decodeExpr(node.items[1], line, depth + 1),
                    decodeExpr(node.items[2], line, depth + 1),
                )
            }
            else -> throw failAt(line, "未知 form 转换: $tag")
        }
    }
}

/** 侧车里的「缺省」裸记号（可选字段的空位写法）。 */
private const val DASH = "-"

/** 流式读的字符块（8 KiB：够摊掉 read 调用，又不给堆添压力）。 */
private const val READ_CHUNK = 8 * 1024
