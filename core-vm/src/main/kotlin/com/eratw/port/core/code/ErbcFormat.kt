package com.eratw.port.core.code

import java.nio.charset.CharacterCodingException

/**
 * `.erbc` 容器常量 —— 规约正文见 `eratw-android/specs/erbc-format.md`（v2 定稿）。
 *
 * 设计依据：ADR-001「erbcc 构建期编译 → `.erbc` → mmap 零解析」、ADR-004「i64 + 字符串驻留 +
 * 指令定长 4×u32」、实施方案 §4 的骨架（头 / 段表 / 字符串池 / 常量池 / 函数表 / 指令流 / 行号表）。
 *
 * ## 物理布局（小端）
 *
 * ```
 * 偏移 0  : "ERBC"                       4 字节 magic
 * 偏移 4  : u16 version                  容器版本，1 或 2
 * 偏移 6  : u16 flags                    段存在性位（见 FLAG_ 常量）
 * 偏移 8  : u16 sectionCount             段表槽数：v1 = 7，v2 = 9
 * 偏移 10 : u32 offset[sectionCount]     段表；槽位下标即段号，0 = 该段不存在
 * 之后     : 各段正文（顺序任意，一律由段表寻址）
 * ```
 *
 * 段号固定（[SECTION_STRINGS] 起）：**新增段只能追加到末尾并升版本**，
 * 已有段的编号与含义永不改变，否则旧产物的段表会静默错位。v2 只追加了
 * [SECTION_CALL_ARGS] / [SECTION_PARAM_NAMES] 两个段，v1 的 0..6 逐字节不变。
 *
 * ## 为什么段表用「固定槽位 + 0 表示不存在」
 *
 * 装载侧（ERBC-B）要按段 mmap，段号必须是编译期常量而不是查表结果；
 * 空池写 offset=0 比写一个零长段更省字节，也不会让 `u32 偏移 × n` 的骨架变形。
 * offset=0 不可能是合法段偏移（文件头 + 段表至少占 38 字节），因此没有歧义。
 */
object ErbcFormat {

    /** magic 四字节（ASCII `ERBC`）。 */
    const val MAGIC_0 = 'E'.code
    const val MAGIC_1 = 'R'.code
    const val MAGIC_2 = 'B'.code
    const val MAGIC_3 = 'C'.code
    const val MAGIC_TEXT = "ERBC"

    /** v1 容器版本（7 段）。reader 仍接受该值，装载后 [Program.callArgSource] = `ERBC_V1`。 */
    const val VERSION_1 = 1

    /**
     * v2 容器版本（9 段）。相对 v1 只**追加**两个段：
     *
     * * [SECTION_CALL_ARGS]（段 7）：调用点实参表，复原 `Program.callArgs`；
     * * [SECTION_PARAM_NAMES]（段 8）：函数头部形参名，复原 `FunctionInfo.paramNames`。
     *
     * v1 的段 0..6 的布局与语义逐字节不变（规约 §8.2「兼容变更 = 追加新段号并升版本」）。
     */
    const val VERSION_2 = 2

    /** 当前最新容器版本（写侧默认值）。 */
    const val LATEST_VERSION = VERSION_2

    /** 文件头字节数（magic 4 + version 2 + flags 2 + sectionCount 2）。 */
    const val HEADER_BYTES = 10

    /** v1 段表槽数（段 0..6）。 */
    const val SECTION_COUNT_V1 = 7

    /** v2 段表槽数（段 0..8）。 */
    const val SECTION_COUNT_V2 = 9

    /** 最新版本的段表槽数（= [SECTION_COUNT_V2]；新增段追加并升版本）。 */
    const val SECTION_COUNT = SECTION_COUNT_V2

    const val SECTION_STRINGS = 0
    const val SECTION_CONSTANTS = 1
    const val SECTION_EXPRESSIONS = 2
    const val SECTION_STATEMENTS = 3
    const val SECTION_FUNCTIONS = 4
    const val SECTION_CODE = 5
    const val SECTION_LINES = 6

    /** v2 段 7：调用点实参表（`pc → List<CallArg>`；布局见规约 §5.8）。 */
    const val SECTION_CALL_ARGS = 7

    /** v2 段 8：函数头部形参名表（布局见规约 §5.9）。 */
    const val SECTION_PARAM_NAMES = 8

    /** 段名（诊断与 probe 报告用；下标即段号）。 */
    val SECTION_NAMES: Array<String> = arrayOf(
        "STRING_POOL", "CONSTANT_POOL", "EXPRESSION_POOL", "STATEMENT_POOL",
        "FUNCTION_TABLE", "CODE", "LINE_TABLE", "CALL_ARGS", "PARAM_NAMES",
    )

    /**
     * 版本 → 段表槽数；未支持的版本返回 -1（调用方负责报「不支持的版本」而不是猜槽数）。
     *
     * 版本与槽数**严格配对**：v1 产物只能有 7 槽，v2 产物只能有 9 槽。
     * 允许「v2 头 + 7 槽」会让段号语义随文件而变，属于必须当场拒绝的畸形文件。
     */
    fun sectionCountFor(version: Int): Int = when (version) {
        VERSION_1 -> SECTION_COUNT_V1
        VERSION_2 -> SECTION_COUNT_V2
        else -> -1
    }

    /** 文件头 + 段表的总字节数（= 首段正文的最小合法偏移）。 */
    fun firstSectionOffset(sectionCount: Int): Int = HEADER_BYTES + sectionCount * 4

    // ------------------------------------------------------------------
    // 标志位：与段表严格等价（置位 ⇔ 该段 offset != 0），不一致即畸形文件
    // ------------------------------------------------------------------

    /** 携带行号表（缺失时 [Program.lineOf] 恒为 -1）。debug 包专属，release 可省。 */
    const val FLAG_HAS_LINES = 0x0001

    /** 携带表达式池（缺失时 `_EVAL_INT`/`_EVAL_STR` 在装载期即报明确错误，绝不静默）。 */
    const val FLAG_HAS_EXPRESSIONS = 0x0002

    /** 携带语句池（缺失时任何 marker（op 29..197）在装载期即报明确错误）。 */
    const val FLAG_HAS_STATEMENTS = 0x0004

    /** v2：携带调用点实参表（段 7）。缺失 ⇒ `Program.callArgs` 为空、绑定退化为类型桶。 */
    const val FLAG_HAS_CALL_ARGS = 0x0008

    /** v2：携带函数头部形参名表（段 8）。缺失 ⇒ `FunctionInfo.paramNames` 为空。 */
    const val FLAG_HAS_PARAM_NAMES = 0x0010

    /** v1 已定义的全部标志位。 */
    const val FLAG_MASK_V1 = FLAG_HAS_LINES or FLAG_HAS_EXPRESSIONS or FLAG_HAS_STATEMENTS

    /** v2 已定义的全部标志位；出现表外位 = 未来版本产物，reader 拒绝而不是忽略。 */
    const val FLAG_MASK = FLAG_MASK_V1 or FLAG_HAS_CALL_ARGS or FLAG_HAS_PARAM_NAMES

    /**
     * 版本 → 允许的标志位集合。
     *
     * 标志位与段表**双向**自洽（置位 ⇔ 该段 offset != 0），因此掩码必须按版本选：
     * v1 产物带 0x0008 是畸形（v1 没有段 7），v2 产物带它是正常声明。
     */
    fun flagMaskFor(version: Int): Int = when (version) {
        VERSION_1 -> FLAG_MASK_V1
        VERSION_2 -> FLAG_MASK
        else -> 0
    }

    /**
     * 单个 `ByteArray` 的容量上限（JVM 数组长度上限再留 8 字节头空间）。
     *
     * 这不是「分片阈值」：切片阈值是规约 §7 的 32MiB **协议**常量，实现方是 app 打包
     * （`-PeratwErbcChunkMb` → `program.partNNN.erbc`）与装载侧（ERBC-B），本模块不读它。
     * 原先这里有一个 `MAX_SHARD_BYTES = 32MiB` 但全仓 0 个读者（只剩一句 KDoc 引用），
     * 已删（CMP-74）：死常量会让人误以为「写到 32MiB 就会自动切片」。
     */
    internal const val MAX_ARRAY_BYTES = Int.MAX_VALUE - 8

    /** 可缺省引用的哨兵（u32 全 1，即 Int 的 -1）：`Expr?` 为 null、`String?` 为 null。 */
    const val NONE_REF = -1

    /** 语句 marker 的操作码区间（契约 C3；与 [Vm] 的分派口径逐字一致）。 */
    const val MARKER_OPCODE_MIN = Opcode.INTERNAL_COUNT

    const val MARKER_OPCODE_MAX = 197

    /**
     * 容器里**合法的操作码上界**（= 栈码组末条 `_STK_POP_SLOT_S`）。
     *
     * 0..243 连续无空洞：VM 内部 0..28（[Opcode.INTERNAL_COUNT]）、isa 语句 29..197
     * （[MARKER_OPCODE_MAX]）、编译器扩展 198..201（含 `_CALL_BY_NAME`/`_JUMP_BY_NAME`/
     * `_JUMP_DYN`/`_RETS`）、栈码组 202..243（T2-21 的 `_STK_*`，操作数直接是池下标，
     * 装载期校验见 `ErbcReader.requireStackOpPool`）。区间外的操作码在 VM 分派里只会落进
     * `else` 分支报「语句 <op:N> 尚未实现」—— 那是**运行期**才炸，且文案与真因（产物损坏）
     * 无关；装载期必须在 [ErbcReader] 里当场拒绝（规约 §1.6「禁止静默降级」）。
     *
     * T2-21 曾漏扩本上界（停在 201）：栈码产物在两条装载路的共同咽喉 `needsStatementPool`
     * 就被拒掉，`requireStackOpPool` 永远执行不到——`Assembler` 直连内存对象绕开容器，
     * 所以栈码单测全绿，缺陷只在真实容器路现形（2026-10-27 合流时由
     * `ErbcCodeBcAuditTest.越界操作码在装载期当场拒载` 的 op=202 用例钉出）。
     */
    const val OPCODE_MAX = Opcode._STK_POP_SLOT_S

    /** [op] 是否是可编码/可解码的操作码（0..[OPCODE_MAX]）。区间外一律畸形字节。 */
    fun isValidOpcode(op: Int): Boolean = op in 0..OPCODE_MAX

    // ------------------------------------------------------------------
    // 记录标签（冻结值；实现与规约表格一一对应，永不复用/漂移）
    // ------------------------------------------------------------------

    /** 表达式节点标签（u8）。0 = 未定义。 */
    object ExprTag {
        const val INT_LITERAL = 1
        const val STR_LITERAL = 2
        const val NULL_TERM = 3
        const val VAR_REF = 4
        const val KEYWORD_INDEX_ARG = 5
        const val FUNC_CALL = 6
        const val UNARY = 7
        const val UNARY_AFTER = 8
        const val BINARY = 9
        const val TERNARY = 10
        const val FORM_STRING = 11
    }

    /** `FormPart` 标签（u8）。 */
    object FormPartTag {
        const val TEXT = 1
        const val EMBEDDED = 2
    }

    /** `FormConversion` 标签（u8）。 */
    object FormConversionTag {
        const val CURLY = 1
        const val PERCENT = 2
        const val YEN_AT = 3
    }

    /** `Argument` 具体类标签（u8；顺序 = `core/stmt/Argument.kt` 的声明序，值冻结）。 */
    object ArgTag {
        const val NONE = 0 // 语句类无 `args` 构造参数（args 由类推导）
        const val VOID = 1
        const val EXPRESSION = 2
        const val EXPRESSION_ARRAY = 3
        const val EXPRESSIONS = 4
        const val INT_ASIGN = 5
        const val STR_ASIGN = 6
        const val BIT = 7
        const val CASE = 8
        const val METHOD = 9
        const val PRINT_DATA = 10
        const val REF = 11
        const val STR_DATA = 12
        const val SP_ARRAY_CONTROL = 13
        const val SP_ARRAY_SHIFT = 14
        const val SP_ARRAY_SORT = 15
        const val SP_BAR = 16
        const val SP_BUTTON = 17
        const val SP_CVAR_SET = 18
        const val SP_CALL = 19
        const val SP_CALLF = 20
        const val SP_CALL_SHARP = 21
        const val SP_COLOR = 22
        const val SP_COPY_ARRAY = 23
        const val SP_DT_COLUMN_OPTIONS = 24
        const val SP_FOR_NEXT = 25
        const val SP_GET_INT = 26
        const val SP_HTML_PRINT = 27
        const val SP_HTML_SPLIT = 28
        const val SP_INPUTS = 29
        const val SP_POWER = 30
        const val SP_PRINT_IMG = 31
        const val SP_PRINT_SHAPE = 32
        const val SP_PRINT_V = 33
        const val SP_SAVE_DATA = 34
        const val SP_SAVE_VAR = 35
        const val SP_SET = 36
        const val SP_SET_ARRAY = 37
        const val SP_SORTCHARA = 38
        const val SP_SPLIT = 39
        const val SP_SWAP_CHARA = 40
        const val SP_SWAP_VAR = 41
        const val SP_TINPUTS = 42
        const val SP_TIMES = 43
        const val SP_VAR_SET = 44
        const val SP_VARSIZE = 45
        const val STR_DOUBLE = 46
        const val HTML_PRINT_LINE_END = 47
    }

    /** `Statement` 具体类标签（u8；顺序 = `core/stmt/Statement.kt` 的声明序，值冻结）。 */
    object StmtTag {
        const val INSTRUCTION = 1
        const val PRINT = 2
        const val PRINT_DATA = 3
        const val ASSIGN = 4
        const val JUMP = 5
        const val CALL = 6
        const val CALL_F = 7
        const val CALL_SHARP = 8
        const val METHOD = 9
        const val VAR_DECL = 10
        const val IF = 11
        const val ELSE_IF = 12
        const val ELSE = 13
        const val END_IF = 14
        const val SELECT_CASE = 15
        const val CASE = 16
        const val CASE_ELSE = 17
        const val END_SELECT = 18
        const val FOR = 19
        const val NEXT = 20
        const val REPEAT = 21
        const val REND = 22
        const val WHILE = 23
        const val WEND = 24
        const val DO = 25
        const val LOOP = 26
        const val CATCH = 27
        const val END_CATCH = 28
        const val DATA_LIST = 29
        const val END_BLOCK = 30
        const val FLOW = 31
        const val RETURN = 32
        const val RETURN_F = 33
        const val FUNC_LABEL = 34
        const val GOTO_LABEL = 35
        const val SHARP = 36
        const val DATA = 37
    }

    /**
     * v2 段 7 实参记录的 flags 位（u8；其余位保留且必须为 0）。
     *
     * spec: `Program.kt:24-30` 的 [CallArg] 四个半字段 —— 位置 / 桶号 / 是否走字符串通道 /
     * 是否省略位；变量名单独走段内串池（可为 null）。
     */
    object CallArgFlags {
        /** 该实参走字符串通道（`Expr.operandType == STR`；原版 `TransporterStr`）。 */
        const val IS_STR = 0x01

        /** 该位有实参；false = 省略位（原版 `Arguments[i] == null`，ProcessState.cs:437 跳过不写）。 */
        const val PRESENT = 0x02

        const val MASK = IS_STR or PRESENT
    }

    /** `OperandType` 线值（0 位，冻结）。 */
    object WireEnum {        const val OPERAND_INT = 0
        const val OPERAND_STR = 1

        const val SORT_UNDEF = 0
        const val SORT_ASCENDING = 1
        const val SORT_DESCENDING = 2

        const val CASE_NORMAL = 0
        const val CASE_TO = 1
        const val CASE_IS = 2

        const val DT_DEFAULT = 0

        const val TYPE_NULL = 0
        const val TYPE_INT = 1
        const val TYPE_STR = 2
        const val TYPE_VOID = 3
    }
}

/** `.erbc` 编解码错误的共同基类（构建期与装载期共用；不是 CodeEE —— 与脚本语义无关）。 */
open class ErbcFormatException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 编码失败：产物会失去语义（池下标漂移、不可序列化构造），因此构建期直接失败而不是写出残次品。 */
class ErbcEncodeException(
    message: String,
) : ErbcFormatException(message)

/** 解码失败：文件畸形、版本不符、声明缺段却仍需该段，或引用越界。 */
class ErbcDecodeException(
    val offset: Int,
    message: String,
    cause: Throwable? = null,
) : ErbcFormatException("偏移 $offset: $message", cause)

/**
 * 编码选项（默认 = 全量 + 最新容器版本）。
 *
 * 四个开关只用于**产出更小/debug 包**，且必须与程序自身内容自洽：
 * 关掉某段而该段其实非空 ⇒ [ErbcWriter] 直接抛 [ErbcEncodeException]，
 * 不会写出「声明不含 X 池但指令流引用 X 池」的文件（规约 §6）。
 * 反向组合（声明不含语句池 + 指令流确有 marker）只能由手写字节构造，
 * 由 [ErbcReader] 在装载期报明确错误 —— 这条路径有单测覆盖。
 */
data class ErbcEncodeOptions(
    /** 行号表（`Program.lines`）；关掉即 release 包，行号全部回落 -1。 */
    val includeLines: Boolean = true,
    /** 表达式池（`_EVAL_*` 的 `$k`）。 */
    val includeExpressions: Boolean = true,
    /** 语句池（marker 的 `a`）。 */
    val includeStatements: Boolean = true,
    /**
     * 容器版本。默认 [ErbcFormat.LATEST_VERSION]（v2）。
     *
     * 显式要 v1 时，程序若带 `Program.callArgs` / `FunctionInfo.paramNames` 会**拒写**：
     * v1 容器没有承载它们的段（规约 §5.8/§5.9），写出来必然丢语义（禁止静默降级）。
     */
    val version: Int = ErbcFormat.LATEST_VERSION,
)

/**
 * 文件头快照（不解码各段；ERBC-B 的装载器据此决定 mmap 区间与分片拼接）。
 *
 * **不是 data class**：主构造里有一张 `IntArray`（段表），data class 自动生成的
 * `equals` / `hashCode` 会按**引用**比较它 —— 两个内容相同的头判不相等，且 `toString`
 * 里少一个闭合括号（CMP-72/75）。这里改为按内容判等 + 显式诊断串。
 *
 * 段表在**进出两个方向**都做隔离：构造时拷贝入参，读 [offsets] 时返回副本。
 * 头是「文件头快照」，能被打包/解码侧的同一个数组改写的快照等于没有快照。
 *
 * @param offsets 段表；下标即段号，0 = 该段不存在。长度 = 该版本的段表槽数（v1 = 7、v2 = 9）。
 *   传入后**立即拷贝**，调用方后续改动该数组不影响本头。
 */
class ErbcHeader(
    val version: Int,
    val flags: Int,
    val sectionCount: Int,
    offsets: IntArray,
) {
    /** 段表内部副本（构造即拷贝）；外部只能经 [offsets] 拿副本读。 */
    private val offsetsBacking: IntArray = offsets.copyOf()

    /**
     * 段表副本；下标即段号，0 = 该段不存在。
     *
     * 每次读都返回独立数组（v2 = 9 个 int，只在探测/装载/诊断路径上读）：
     * 返回内部实例会让「快照」被外部一行下标赋值改写。
     */
    val offsets: IntArray get() = offsetsBacking.copyOf()

    /** 段是否存在（读内部副本，不在热路径上产生临时数组）。 */
    fun hasSection(id: Int): Boolean = id in offsetsBacking.indices && offsetsBacking[id] != 0

    /** 本头对应的「文件头 + 段表」字节数（= 首段正文的最小合法偏移）。 */
    fun firstSectionOffset(): Int = ErbcFormat.firstSectionOffset(sectionCount)

    /** v1 产物：容器没有段 7/8 ⇒ 实参绑定必然退化为类型桶。 */
    val isV1: Boolean get() = version == ErbcFormat.VERSION_1

    /** 按内容判等（`IntArray` 默认引用比较；见类头 KDoc）。 */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ErbcHeader) return false
        return version == other.version &&
            flags == other.flags &&
            sectionCount == other.sectionCount &&
            offsetsBacking.contentEquals(other.offsetsBacking)
    }

    /** 与 [equals] 同一组字段的哈希（保证「相等 ⇒ 同 hash」）。 */
    override fun hashCode(): Int {
        var h = version
        h = h * 31 + flags
        h = h * 31 + sectionCount
        return h * 31 + offsetsBacking.contentHashCode()
    }

    override fun toString(): String =
        "ErbcHeader(version=$version, flags=0x${flags.toString(16)}, " +
            sections().joinToString(", ") + ")"
}

/** 存在的段名（诊断用）。 */
private fun ErbcHeader.sections(): List<String> =
    offsets.mapIndexed { i, off -> if (off != 0) ErbcFormat.SECTION_NAMES.getOrElse(i) { "SECTION$i" } else null }
        .filterNotNull()

// ======================================================================
// 字节原语（小端；core 层纯 JVM，只用 java.lang / kotlin stdlib）
// ======================================================================

/**
 * 扩容目标容量判界（[ErbcSink] 的唯一扩投入口；CMP-10）。
 *
 * 旧形态是 `var cap = data.size; while (cap < size + n) cap *= 2`：`size + n` 越过 2^31
 * 后**回绕成负数**，`cap` 从 2^30 翻到负数后条件恒真 ⇒ 不是「照写越界」而是**死循环**
 * （2GiB 级正文才会碰到，单测造不出来，只能在这里直测）。现在：
 *
 * * `need` 用 `Long` 传，回绕不可能发生；
 * * 目标容量夹在 [ErbcFormat.MAX_ARRAY_BYTES]（JVM 单数组上限）内，超过即报错；
 * * 翻倍用 `Long` 做，绝不会回到负数。
 *
 * @param current 当前缓冲区容量（字节）
 * @param need 写入后至少要容纳的字节数（`Long`；负值 = 上游计数已回绕）
 */
internal fun grownCapacity(current: Int, need: Long): Int {
    if (need < 0L) {
        throw ErbcEncodeException("扩容请求负长度($need)：尺寸计数已回绕，拒绝继续写入")
    }
    if (need <= current) return current
    val limit = ErbcFormat.MAX_ARRAY_BYTES.toLong()
    if (need > limit) {
        throw ErbcEncodeException(
            "容器需要 $need 字节，超过 JVM 单数组上限 $limit：必须分片" +
                "（切片协议见 specs/erbc-format.md 第 7 节，本模块只产出单片容器）",
        )
    }
    var cap = if (current > 0) current.toLong() else 16L
    while (cap < need) cap *= 2
    return cap.coerceAtMost(limit).toInt()
}

/**
 * 增长型字节汇。
 *
 * 只用 `ByteArray` + 位运算，不用 `java.nio.ByteBuffer`：装载侧要按段 mmap，
 * 构建期也要能整段 `copyOf` 直交，缓冲区自己攥着比交给 ByteBuffer 的 limit/position
 * 状态机少一类错位。写入顺序由调用方唯一决定 ⇒ 同一 Program 两次编码逐字节相同。
 */
internal class ErbcSink(capacity: Int = 256) {
    private var data = ByteArray(if (capacity > 0) capacity else 16)

    /** 已写入字节数。 */
    var size: Int = 0
        private set

    private fun ensure(n: Int) {
        if (n < 0) {
            throw ErbcEncodeException("ErbcSink.ensure 请求负长度($n)：尺寸计数已回绕，拒绝继续写入")
        }
        val need = size.toLong() + n.toLong()
        if (need <= data.size) return
        data = data.copyOf(grownCapacity(data.size, need))
    }

    fun u8(v: Int) {
        ensure(1)
        data[size] = (v and 0xFF).toByte()
        size++
    }

    fun bool(v: Boolean) = u8(if (v) 1 else 0)

    fun u16(v: Int) {
        ensure(2)
        data[size] = (v and 0xFF).toByte()
        data[size + 1] = ((v ushr 8) and 0xFF).toByte()
        size += 2
    }

    /** u32 / i32 同宽：负数按补码原样落 4 字节（`SlotAddr.NONE`、行号 -1 都靠它）。 */
    fun u32(v: Int) {
        ensure(4)
        data[size] = (v and 0xFF).toByte()
        data[size + 1] = ((v ushr 8) and 0xFF).toByte()
        data[size + 2] = ((v ushr 16) and 0xFF).toByte()
        data[size + 3] = ((v ushr 24) and 0xFF).toByte()
        size += 4
    }

    /** 有符号 4 字节写入的别名（与 [ErbcCursor.i32] 对称；补码下同宽同字节序）。 */
    fun i32(v: Int) = u32(v)

    /** i64（常量池与 Double 的 raw bits 共用）。 */
    fun i64(v: Long) {
        ensure(8)
        var x = v
        for (i in 0 until 8) {
            data[size + i] = (x and 0xFFL).toByte()
            x = x shr 8
        }
        size += 8
    }

    fun f64(v: Double) = i64(v.toRawBits())

    fun put(src: ErbcSink) {
        ensure(src.size)
        src.data.copyInto(data, size, 0, src.size)
        size += src.size
    }

    fun put(src: ByteArray) {
        ensure(src.size)
        src.copyInto(data, size)
        size += src.size
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
}

/** 字节源游标（[pos] 单调前进；越界一律 [ErbcDecodeException]，不返回脏值）。 */
internal class ErbcCursor(
    private val bytes: ByteArray,
    start: Int = 0,
) {
    var pos: Int = start
        private set

    /** 剩余可读字节数。 */
    val remaining: Int get() = bytes.size - pos

    /**
     * 判界（**Long** 加法；CMP-10）。
     *
     * 旧形态 `pos + n > bytes.size` 是 Int 加法：`pos = 20`、`n = Int.MAX_VALUE` 时
     * `pos + n` 回绕成负数、判界放行，随后 `copyOfRange` 抛 `OutOfMemoryError`
     * （请求的数组长度超过 VM 上限）或 `IllegalArgumentException` —— 两者都不是
     * [ErbcDecodeException]，装载层的「畸形文件」收口与 `BootFailure` 分类都接不住。
     */
    private fun need(n: Int, what: String) {
        if (n < 0 || pos.toLong() + n.toLong() > bytes.size.toLong()) {
            throw ErbcDecodeException(pos, "$what 需要 $n 字节，文件只剩 $remaining 字节（总长 ${bytes.size}）")
        }
    }

    fun u8(): Int {
        need(1, "u8")
        return bytes[pos++].toInt() and 0xFF
    }

    fun bool(): Boolean = u8() != 0

    fun u16(): Int {
        need(2, "u16")
        val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    /** u32 按 Int 原样取回（可为负；无符号语义由调用方判）。 */
    fun u32(): Int {
        need(4, "u32")
        val v = (bytes[pos].toInt() and 0xFF) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun i32(): Int = u32()

    /**
     * 计数类字段：u32 落在负半轴即畸形（v1 不支持 >2GB 的单个计数）。
     *
     * @param minBytesPerItem 该计数后续每一项在本段里**至少**占用的字节数。只按「剩余字节数」
     *   判上界时，一个 `count = remaining` 的畸形字段可以让 `LongArray/IntArray/ArrayList`
     *   按远大于文件的规模分配（8 字节项 → 8×剩余字节，畸形产物在真机上会 OOM 而不是报
     *   [ErbcDecodeException]）。带最小宽度后，计数在**分配之前**就被剩余字节数卡死。
     */
    fun count(
        what: String,
        minBytesPerItem: Int = 1,
    ): Int {
        val v = u32()
        if (v < 0) throw ErbcDecodeException(pos - 4, "$what 计数越界(u32=$v)")
        if (v > remaining) throw ErbcDecodeException(pos - 4, "$what 计数($v)大于剩余字节数($remaining)")
        if (minBytesPerItem > 1) {
            val need = v.toLong() * minBytesPerItem
            if (need > remaining) {
                throw ErbcDecodeException(
                    pos - 4,
                    "$what 计数($v)至少需要 $need 字节（每项 ≥ $minBytesPerItem 字节），剩余 $remaining 字节",
                )
            }
        }
        return v
    }

    fun i64(): Long {
        need(8, "i64")
        var x = 0L
        for (i in 7 downTo 0) x = (x shl 8) or (bytes[pos + i].toLong() and 0xFFL)
        pos += 8
        return x
    }

    fun f64(): Double = Double.fromBits(i64())

    fun take(n: Int, what: String): ByteArray {
        need(n, what)
        val out = bytes.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun skip(n: Int) {
        need(n, "skip")
        pos += n
    }

    /** 就地取一段的只读视图（不复制；调用方不得改动 [bytes]）。 */
    fun view(n: Int, what: String): Int {
        need(n, what)
        val from = pos
        pos += n
        return from
    }

    fun bytesRef(): ByteArray = bytes
}

// ======================================================================
// 字符串表（段的局部池；布局与全局字符串池段完全同构）
// ======================================================================

/**
 * `STRING_TABLE ::= u32 count | u32 dataLen | u32 offset[count+1] | data[dataLen]`
 *
 * 用 count+1 个偏移表达第 i 项的长度（末位 = dataLen），因此不需要第二张长度表；
 * 装载侧要 mmap 时也是「offset[i]..offset[i+1]」直接切，零拷贝零解析。
 *
 * **为什么各段自带局部串池，而不是共用全局字符串池**：全局池的下标是指令操作数
 * （`_SET_STR` 的 b、`_PRINT_LITERAL` 的 a），往里面追加函数名/枚举名/语句原文会让
 * 已编译指令的池下标全部漂移。局部池把「容器自描述」与「指令引用」彻底解耦。
 */
internal class ErbcStringTable {
    private val encoded = ArrayList<ByteArray>()
    private val index = HashMap<String, Int>()

    /** 写入序去重：只用于查表，绝不参与迭代顺序（迭代一律走 [encoded] 的追加序）。 */
    fun intern(s: String): Int {
        index[s]?.let { return it }
        val b = s.toByteArray(Charsets.UTF_8)
        encoded.add(b)
        index[s] = encoded.size - 1
        return encoded.size - 1
    }

    val count: Int get() = encoded.size

    fun writeTo(out: ErbcSink) {
        out.u32(encoded.size)
        var acc = 0
        for (b in encoded) acc += b.size
        out.u32(acc)
        var off = 0
        for (b in encoded) {
            out.u32(off)
            off += b.size
        }
        out.u32(off)
        for (b in encoded) out.put(b)
    }
}

/** 字符串表视图（读侧）。 */
internal class ErbcStringView(
    private val data: ByteArray,
    private val offsets: IntArray,
) {
    val count: Int get() = offsets.size - 1

    operator fun get(i: Int): String {
        if (i < 0 || i >= count) throw ErbcDecodeException(-1, "字符串表下标越界: $i（共 $count 项）")
        val from = offsets[i]
        val to = offsets[i + 1]
        return try {
            // UTF-8 严格解码：非法序列直接报错，不允许悄悄变成替换字符（规约 §5.1）。
            data.decodeToString(from, to, throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            // Kotlin 的严格解码抛 CharacterCodingException（IOException 支）而不是
            // IllegalArgumentException：只捕后者会让非法 UTF-8 原样漏出装载层，
            // ProgramLoader 的「解码中断」收口与 BootFailures.classify 都接不住它。
            throw ErbcDecodeException(from, "字符串表第 $i 项不是合法 UTF-8", e)
        }
    }
}

/** 读一张字符串表（游标停在表首）。 */
internal fun readStringTable(cur: ErbcCursor): ErbcStringView {
    // 每项至少有 1 个 u32 偏移（count+1 张偏移表），先按 4 字节/项卡分配上界。
    val count = cur.count("字符串表项数", 4)
    val dataLen = cur.count("字符串表数据长度")
    val offsets = IntArray(count + 1)
    for (i in 0..count) offsets[i] = cur.u32()
    if (count == 0) {
        if (dataLen != 0) throw ErbcDecodeException(cur.pos - 4, "空字符串表的数据长度应为 0，实为 $dataLen")
        return ErbcStringView(EMPTY_BYTES, offsets)
    }
    if (offsets[0] != 0) throw ErbcDecodeException(cur.pos - 4, "字符串表首偏移应为 0，实为 ${offsets[0]}")
    if (offsets[count] != dataLen) {
        throw ErbcDecodeException(cur.pos - 4, "字符串表末偏移(${offsets[count]})与数据长度($dataLen)不符")
    }
    for (i in 1..count) {
        if (offsets[i] < offsets[i - 1]) throw ErbcDecodeException(cur.pos - 4, "字符串表偏移非单调: $i")
    }
    val from = cur.view(dataLen, "字符串表数据")
    return ErbcStringView(cur.bytesRef().copyOfRange(from, from + dataLen), offsets)
}

private val EMPTY_BYTES = ByteArray(0)

