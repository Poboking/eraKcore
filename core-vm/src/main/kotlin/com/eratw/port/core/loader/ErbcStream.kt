package com.eratw.port.core.loader

import com.eratw.port.core.code.CallArg
import com.eratw.port.core.code.CallArgIndex
import com.eratw.port.core.code.CallArgSource
import com.eratw.port.core.code.ConstantPool
import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcFormat.ArgTag
import com.eratw.port.core.code.ErbcFormat.ExprTag
import com.eratw.port.core.code.ErbcFormatException
import com.eratw.port.core.code.LazyExprSource
import com.eratw.port.core.code.ErbcFormat.NONE_REF
import com.eratw.port.core.code.ErbcFormat.StmtTag
import com.eratw.port.core.code.ErbcFormat.WireEnum
import com.eratw.port.core.code.ErbcHeader
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.FlatStatements
import com.eratw.port.core.code.FunctionInfo
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.LazyFunctionTable
import com.eratw.port.core.code.LazyStatementPool
import com.eratw.port.core.code.LineBlockSource
import com.eratw.port.core.code.LineTable
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
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
import com.eratw.port.core.stmt.ArgInterner
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.ArgValueType
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.BitArgument
import com.eratw.port.core.stmt.CallFStmt
import com.eratw.port.core.stmt.CallSharpStmt
import com.eratw.port.core.stmt.CallStmt
import com.eratw.port.core.stmt.CaseArgument
import com.eratw.port.core.stmt.CaseElseStmt
import com.eratw.port.core.stmt.CaseExpression
import com.eratw.port.core.stmt.CaseStmt
import com.eratw.port.core.stmt.CatchStmt
import com.eratw.port.core.stmt.DataListStmt
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.DoStmt
import com.eratw.port.core.stmt.ElseIfStmt
import com.eratw.port.core.stmt.ElseStmt
import com.eratw.port.core.stmt.EndBlockStmt
import com.eratw.port.core.stmt.EndCatchStmt
import com.eratw.port.core.stmt.EndIfStmt
import com.eratw.port.core.stmt.EndSelectStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.ExpressionsArgument
import com.eratw.port.core.stmt.FlowStmt
import com.eratw.port.core.stmt.ForStmt
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.GotoLabelStmt
import com.eratw.port.core.stmt.HtmlPrintArgument
import com.eratw.port.core.stmt.IfStmt
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.IntAsignArgument
import com.eratw.port.core.stmt.JumpStmt
import com.eratw.port.core.stmt.LoopStmt
import com.eratw.port.core.stmt.MethodArgument
import com.eratw.port.core.stmt.MethodStmt
import com.eratw.port.core.stmt.MixedIntegerExprTerm
import com.eratw.port.core.stmt.NextStmt
import com.eratw.port.core.stmt.PrintDataArgument
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.RefArgument
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.RepeatStmt
import com.eratw.port.core.stmt.ReturnFStmt
import com.eratw.port.core.stmt.ReturnStmt
import com.eratw.port.core.stmt.SelectCaseStmt
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.SortOrder
import com.eratw.port.core.stmt.SpArrayControlArgument
import com.eratw.port.core.stmt.SpArrayShiftArgument
import com.eratw.port.core.stmt.SpArraySortArgument
import com.eratw.port.core.stmt.SpBarArgument
import com.eratw.port.core.stmt.SpButtonArgument
import com.eratw.port.core.stmt.SpCVarSetArgument
import com.eratw.port.core.stmt.SpCallArgument
import com.eratw.port.core.stmt.SpCallFArgument
import com.eratw.port.core.stmt.SpCallSharpArgument
import com.eratw.port.core.stmt.SpColorArgument
import com.eratw.port.core.stmt.SpCopyArrayArgument
import com.eratw.port.core.stmt.SpDtColumnOptions
import com.eratw.port.core.stmt.SpForNextArgument
import com.eratw.port.core.stmt.SpGetIntArgument
import com.eratw.port.core.stmt.SpHtmlPrint
import com.eratw.port.core.stmt.SpHtmlSplitArgument
import com.eratw.port.core.stmt.SpInputsArgument
import com.eratw.port.core.stmt.SpPowerArgument
import com.eratw.port.core.stmt.SpPrintImgArgument
import com.eratw.port.core.stmt.SpPrintShapeArgument
import com.eratw.port.core.stmt.SpPrintVArgument
import com.eratw.port.core.stmt.SpSaveDataArgument
import com.eratw.port.core.stmt.SpSaveVarArgument
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SpSetArrayArgument
import com.eratw.port.core.stmt.SpSortcharaArgument
import com.eratw.port.core.stmt.SpSplitArgument
import com.eratw.port.core.stmt.SpSwapCharaArgument
import com.eratw.port.core.stmt.SpSwapVarArgument
import com.eratw.port.core.stmt.SpTInputsArgument
import com.eratw.port.core.stmt.SpTimesArgument
import com.eratw.port.core.stmt.SpVarSetArgument
import com.eratw.port.core.stmt.SpVarsizeArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.StrAsignArgument
import com.eratw.port.core.stmt.StrDataArgument
import com.eratw.port.core.stmt.StrDoubleArgument
import com.eratw.port.core.stmt.VarDeclStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.stmt.WendStmt
import com.eratw.port.core.stmt.WhileStmt
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * 按段流式解码 `.erbc` → [Program]：段数据只按窗口映射，堆上不留整份产物字节。
 *
 * ## 为什么存在（判据 = CONTRACT-M6-A §2.1）
 *
 * 旧链路 [MappedErbc.toProgram] 走 `fullBytes()`，把整份产物顺序拷进一个 `ByteArray`
 * 再交给 [ErbcReader]：323,711,756 B（全量语料，≈ 308.7 MiB）在装载期整份躺在堆上。
 * 本类把字节源换成**按窗口映射**的 [SectionWindow]：游标仍按文件绝对偏移逐字段读，
 * 段解完窗口即可回收，堆上只剩解码结果本身（`lines` 行表 + 各池对象；段 5 的指令流自
 * M8-COD-EMAP 起是**只读映射页**（`MappedCodeSource`），堆内 0 常驻）。
 *
 * ## 与 [ErbcReader] 的关系：不是「相似」，是**逐字副本**
 *
 * 本文件的 `decodeProgram` / `validate` / `requirePool` / `RecordSource` 四段是
 * `core/code/ErbcReader.kt` 同名字段的**机械复制**（按行切片后只做类型名替换），
 * 目的正是让「解码语义」不可能出现第二套实现。全部差异只有四处：
 * - `ErbcCursor` → [StreamCursor]：字节来源从连续 `ByteArray` 换成 [SectionWindow]，
 *   `pos` 仍是文件绝对偏移、`remaining` 仍算到**文件尾**、越界消息逐字相同；
 * - `ErbcStringView` → [StreamStringTable]：字符串表数据块不再 `copyOfRange` 复制，
 *   第 i 项按 `offsets[i]..offsets[i+1]` 现切（严格 UTF-8，两条报错同 `ErbcFormat` 的视图）；
 * - `needsStatementPool(op)` → `ErbcReader.needsStatementPool(op)`（原式在 object 内可见）；
 * - 段边界上的 `stage(...)` 计量钩子（[LoadStageSink]，生产路径传 null ⇒ 一次空判，
 *   不改变任何读取/校验路径；只服务装载期分配归因，见 `ErbcStreamAllocProfileTest`）。
 *
 * `core/code` 本波禁改（契约 §2.1），而 `ErbcReader.decode` 只吃连续 `ByteArray`，
 * 所以「换字节源」只能落成一份副本。**改 `ErbcReader` 时必须同步本文件**；
 * `ErbcStreamEquivalenceTest` 在真实产物上比计数/哈希、在小产物上做逐字节变异差分
 * （同一批畸形字节走两条路径，异常类型与消息必须逐字相同）来守住这条约束。
 *
 * ## 读取边界（畸形产物也不放宽）
 *
 * 正常产物每段自描述、不越段读；畸形产物可以让记录读进下一段（全读路径允许，因为字节连续）。
 * [SectionWindow] 因此不假设「读到段尾就结束」：越出当前窗口就从新的绝对偏移再映射一块，
 * 读到的字节与全量路径逐字相同；只有 `StreamCursor.need` 的越界判定（文件尾）生效。
 * 地址空间峰值 = max(记录流窗口 [WINDOW_BYTES], 单张字符串表数据块 ≤ [MAX_TABLE_WINDOW_BYTES])；
 * 全量语料上最大的一块是语句池局部表 104,923,457 B（≈ 100.1 MiB），堆仍是 0。
 */

/** 段窗口大小：一次映射覆盖一段里的连续 8 MiB（顺序读摊到每 8 MiB 一次 mmap）。 */
internal const val WINDOW_BYTES: Int = 8 shl 20

/** 装载分段 mark 的段内块数（索引趟每 1/8 条带报一次；与 [RecordSource.buildRecordOffsets] 同源）。 */
internal const val BLOCK_MARKS: Int = 8

/**
 * 装载期并行校验趟的线程上限（引用范围校验的指令条带 / 函数记录条带）。
 *
 * 只在这两趟里用：它们纯只读、条带互不相交，且都在 `program` 建好之后（此时没有别的
 * 装载线程在跑）。上限 6 = 不占满 8 核，给 ART 的 GC/JIT 线程留位。
 */
internal const val MAX_VALIDATE_WORKERS: Int = 6

/** 每条校验线程至少分到的函数记录数：低于它就退回单线程（小产物不值得起线程）。 */
internal const val RECORDS_PER_VALIDATE_WORKER: Int = 4096

/**
 * 惰性节点重建的递归上限（M10-MEM；CMP-21 起**可配**且默认值回到 ART 安全区）。
 *
 * 递归方向恒为「子下标 < 父下标」⇒ 深度 = 表达式树的嵌套层数；本仓编译出的语料最深也只有
 * 几十层（`ExprCanonicalizer` 的后序折叠），超过这个上限就改走 `RecordSource` 的升序退路
 * （`buildPrefixThrough`），结果逐字段相同、只是不再省内存。
 *
 * ## 为什么从 2048 降到 256（CMP-21）
 *
 * 2048 是「桌面 HotSpot 主线程默认栈」量出来的，不是 ART 的：每层至少 3 帧
 * （`lazyEnsure` → `readNode` → `child`/`childOpt`），ART 的 VM 线程栈还比主线程小
 * ⇒ 递归会**先于**上限打穿栈。`StackOverflowError` 是 `Error`，旧装载收口只接
 * `RuntimeException`/`IOException`，失败屏于是裸报 `StackOverflowError`。
 * 256 对「几十层」的语料仍有 8× 余量，且 256×3 帧在 ART 的最小栈上也装得下。
 *
 * 需要更大上限的调用方走 `ErbcStreamDecoder.decode(..., maxEnsureDepth = …)` /
 * [ProgramLoader.decodeMapped] 的显式形参（测试用它把退路压到一两条就触发）。
 */
internal const val MAX_ENSURE_DEPTH: Int = 256

/**
 * 单张字符串表数据块的映射上限（128 MiB）。
 *
 * 字符串表是**随机访问**（同一条原文被上千条语句共用），按 8 MiB 分块会让几乎每次取值都
 * 是一次 mmap/munmap —— 全量产物的语句池局部表数据 104,923,457 B（1,679,631 项），实测
 * 装载 123 s、多分配 ~485 MiB。所以表数据**整块映射**：地址空间 = 表大小，堆 = 0。
 * 超过上限（本语料没有）时退回 8 MiB 分块（[WINDOW_BYTES]）：变慢但不会因为地址空间不足
 * 直接失败 —— 分块大小与 [MAX_TABLE_WINDOW_BYTES] 的「整块/分块」判定都收在
 * [stringTableWindowChunk] 一处（CMP-22：注释与实现必须同式）。
 */
internal const val MAX_TABLE_WINDOW_BYTES: Int = 128 shl 20

/**
 * 单张字符串表数据块的**映射块大小**（CMP-22 的注释与实现对齐点）。
 *
 * - `dataBytes <= MAX_TABLE_WINDOW_BYTES`：整块映射（随机访问不换块；全量语料语句段局部表
 *   104,923,457 B 走这条）；
 * - 超过上限：退回 [WINDOW_BYTES]（8 MiB）分块，地址空间峰值不再等于表大小。
 *
 * 旧实现这里传的是 `minOf(dataLen, MAX_TABLE_WINDOW_BYTES)` ⇒ 超限时窗口仍是 128 MiB，
 * 与 KDoc 承诺的「8 MiB 分块」相反（BOARD CMP-22 的第二半）。
 */
internal fun stringTableWindowChunk(dataBytes: Int): Int =
    if (dataBytes <= MAX_TABLE_WINDOW_BYTES) dataBytes else WINDOW_BYTES

/**
 * 「这张串表的数据块可以整块 pin 进一份映射吗」（CMP-19 的释放前提）。
 *
 * 只有整块 pin 住的表才能在**关掉产物通道之后**继续随机取值：窗口不在当前映射块里就要
 * 再 `channel.map` 一次，而通道已关 ⇒ `映射已关闭` 异常（惰性解码当场断）。
 * 超过 [MAX_TABLE_WINDOW_BYTES] 时窗口按 [WINDOW_BYTES]（8 MiB）换块（地址空间策略，见
 * [MAX_TABLE_WINDOW_BYTES] 与 [stringTableWindowChunk]），此时**不** pin、也不许关通道
 * —— 宁可多留一个 fd。
 *
 * 空表不算 pin：它没有任何字节要读，本来就不碰通道（[LazyStatementRecords.releaseMmap]
 * 把这一档当「无回读」处理）。
 */
internal fun canPinStringTable(dataBytes: Int): Boolean = dataBytes > 0 && dataBytes <= MAX_TABLE_WINDOW_BYTES

/**
 * 一段的只读映射视图：`[start, end)` 是段区间，实际映射按 [chunk] 分块，
 * 读到块外就从该绝对偏移重映射（不缓存、不池化，块引用随时可被 GC 回收）。
 *
 * 为什么按块而不是「整段一次映射」：全量语料的语句池单段 196,193,040 B，
 * 整段映射在 32 位进程里会白占一半地址空间；按块后地址空间峰值与段大小解耦。
 * 顺序读的记录流用 [WINDOW_BYTES]；随机读的字符串表数据区用整块（见
 * [MAX_TABLE_WINDOW_BYTES] 与 [sibling]）。
 *
 * ## 读序陷阱（2026-10-20 实际踩过，全量产物第一处 8 MiB 重映射就炸）
 *
 * **不能写 `buf.get(offsetIn(pos, n), ...)`**：Kotlin 先取接收者 `buf`、再求参数，
 * 于是 `buffer` 拿到的是**重映射之前**的那个块，而 `offsetIn` 返回的是**新块**里的下标 0
 * —— 读到的字节来自旧窗口。小样本装得进一个块时完全看不出来；真实产物（表达式段 25 MB、
 * 语句段 196 MB）第一次跨块就解出乱字节（表现为「字符串表第 N 项不是合法 UTF-8」）。
 * 所以每个方法都先把 `offsetIn` 的结果落进局部变量，再读 [buf]。
 */
internal class SectionWindow private constructor(
    private val file: MappedErbc,
    private val start: Int,
    private val end: Int,
    private val chunk: Int,
    /**
     * **整段预映射**的只读缓冲（M10-MEM 惰性语句池用）；null = 按 [chunk] 换块。
     *
     * 传了它就不再换块：[base]/[limit] 直接铺满这份缓冲覆盖的偏移区间
     * （[pinnedFrom] 起），任何落在其中的绝对偏移都走命中快路径。惰性池要的就是这个 ——
     * 按需解码的下标是跳着的，每次跳回文件都换一块 8 MiB 窗口会把 mmap 塞进取指路径；
     * `MappedCodeSource` 早就验证过「映射与 Program 同寿、通道可以关」这条路（M8-COD-EMAP）。
     * 堆内常驻 = 0，代价是一段地址空间（全量语料的语句段 196,193,040 B ≈ 187 MiB），
     * 见 [LazyStatementRecords] 类末的 TODO(verify)。
     */
    private val pinned: ByteBuffer?,
    private val pinnedFrom: Int,
) {
    constructor(
        file: MappedErbc,
        start: Int,
        end: Int,
        chunk: Int = WINDOW_BYTES,
    ) : this(file, start, end, chunk, null, 0)

    /** 用一份已有的整段映射建窗口（[from] = 该缓冲第 0 字节对应的绝对偏移）。 */
    constructor(
        file: MappedErbc,
        start: Int,
        end: Int,
        pinned: ByteBuffer,
        pinnedFrom: Int,
    ) : this(file, start, end, WINDOW_BYTES, pinned, pinnedFrom)

    /**
     * 同一段区间上的**另一个独立窗口**（游标与换块状态互不干扰）。
     *
     * 只给「同一份只读产物上的并行装载趟」用（装载期引用范围校验的函数记录条带）：
     * 每个线程拿一份自己的 fork，[buf]/[view]/[base]/[limit] 全部实例私有；
     * 共享的只有 [pinned] 那份只读映射（只读、无游标），因此不会互相踩。
     * 非 pinned 档各 fork 各自按 [chunk] 映射，地址空间开销与线程数成正比（条带数 ≤ 8）。
     */
    internal fun fork(): SectionWindow =
        if (pinned != null) SectionWindow(file, start, end, pinned, pinnedFrom)
        else SectionWindow(file, start, end, chunk)

    /**
     * 本窗口的**整段 pin** 是否覆盖 `[from, from + 4 × u32Count)`（MEM7 刀 C 的判据：
     * 一张只读偏移表能不能就地改从映射页直取）。
     *
     * 非整段 pin（按 [chunk] 换块的窗口）恒 false —— 那种窗口每次跳读都可能换一块，
     * 把运行期的随机取数面接到它上面等于把 mmap 塞进取指路径，而且 [closeUnderlying]
     * 之后就再也换不了块。覆盖判定用 `pinnedFrom + limit`（pin 的真实区间），
     * 不是本窗口的 `[start,end)` —— 惰性档的段 pin 从段首铺起，而串池偏移表在记录区之前。
     */
    internal fun pinnedCovers(
        from: Int,
        u32Count: Int,
    ): Boolean {
        val buf = pinned ?: return false
        if (u32Count <= 0 || from < pinnedFrom) return false
        return from.toLong() + u32Count.toLong() * 4L <=
            pinnedFrom.toLong() + buf.limit().toLong()
    }

    private var base = if (pinned == null) 0 else pinnedFrom
    private var limit = if (pinned == null) 0 else pinnedFrom + pinned.limit()
    private var buf: ByteBuffer = pinned ?: ByteBuffer.allocate(0)

    /**
     * [buf] 的独立游标副本，供 [readInto] 的「按起始位置批量读」使用。
     *
     * Android 的 `ByteBuffer` 没有 Java 13 才加的 `get(int, byte[], int, int)`（真机实测抛
     * `NoSuchMethodError`），所以走 `duplicate() + position() + 相对 get`。副本只在换块时重建，
     * 装载链是单线程，游标无并发写。
     */
    private var view: ByteBuffer = buf.duplicate()

    /**
     * 保证 `[pos, pos + n)` 落在当前块里；不在就从 [pos] 起重映射一块够长的。
     * 整段预映射时（见构造参数）永远命中，走不到换块分支。
     */
    private fun offsetIn(pos: Int, n: Int): Int {
        if (pos >= base && pos.toLong() + n <= limit.toLong()) return pos - base
        val want = maxOf(chunk.toLong(), n.toLong())
        val to = minOf(end.toLong(), pos.toLong() + want, file.byteSize.toLong()).toInt()
        buf = file.mapWindow(pos, maxOf(to, pos + n))
        view = buf.duplicate()
        base = pos
        limit = base + buf.limit()
        return pos - base
    }

    fun byteAt(pos: Int): Int {
        val i = offsetIn(pos, 1)
        return buf.get(i).toInt() and 0xFF
    }

    fun u16At(pos: Int): Int {
        val i = offsetIn(pos, 2)
        return (buf.get(i).toInt() and 0xFF) or ((buf.get(i + 1).toInt() and 0xFF) shl 8)
    }

    fun u32At(pos: Int): Int {
        val i = offsetIn(pos, 4)
        return (buf.get(i).toInt() and 0xFF) or
            ((buf.get(i + 1).toInt() and 0xFF) shl 8) or
            ((buf.get(i + 2).toInt() and 0xFF) shl 16) or
            ((buf.get(i + 3).toInt() and 0xFF) shl 24)
    }

    fun i64At(pos: Int): Long {
        val i = offsetIn(pos, 8)
        var x = 0L
        for (k in 7 downTo 0) x = (x shl 8) or (buf.get(i + k).toLong() and 0xFFL)
        return x
    }

    /**
     * 把 `[pos, pos + len)` 读进调用方的复用缓冲（[StreamStringTable] 的逐条解码走这里）。
     *
     * 为什么不每次新分配 `ByteArray`：全量产物要取上千万次字符串（语句原文 + 变量名 + 运算符名），
     * 每次一个新数组实测让装载期总分配多出 ~950 MiB、装载时间从秒级涨到 114 s
     * （2026-10-20 实测）。缓冲由调用方持有，读到的字节与逐次分配逐字相同。
     */
    fun readInto(
        pos: Int,
        len: Int,
        dst: ByteArray,
    ) {
        if (len == 0) return
        val i = offsetIn(pos, len)
        // 经父类 Buffer 调 position(int)：Kotlin/JDK17 编译下 `view.position(i)` 的调用点
        // 签名是协变版 `ByteBuffer.position(I)Ljava/nio/ByteBuffer;`（Java 9+ 才有）——
        // android.jar 编译面没有它，minSdk 21 的老 ART（OpenJDK 7 底座）运行时解析即抛
        // NoSuchMethodError。平台 API 兼容闸（AndroidApiCompatTest）首跑抓获，2026-10-22。
        (view as java.nio.Buffer).position(i)
        view.get(dst, 0, len)
    }

    /**
     * 同一段区间里的**另一个独立窗口**（窗口游标互不干扰）。
     *
     * 为什么必须分开：一张字符串表的数据区在段首附近，而记录游标一路向段尾推进；共用一个窗口时
     * 每读一条字符串就要把记录游标刚映射的块换掉、读完再换回来 —— 实测全量产物上变成
     * 「每条记录两次 mmap/munmap」，装载 943 s、分配 7.5 GiB（2026-10-20 实测）。
     * 拆成两个窗口后两边各自推进。
     *
     * @param from 新窗口的起点（字符串表数据区首字节）
     * @param end 新窗口的上界（数据区末字节）
     * @param chunk 单次映射的字节数；字符串表传 [stringTableWindowChunk] 的结果（≤128 MiB 整块，
     *   超过则 8 MiB 分块），记录流用默认 [WINDOW_BYTES]
     */
    fun sibling(
        from: Int,
        end: Int = this.end,
        chunk: Int = WINDOW_BYTES,
    ): SectionWindow {
        val shared = pinned
        // 整段预映射的表共享同一份缓冲（base 仍从 pinnedFrom 起算）：串池随机取值
        // 因此也永远不会换块。
        if (shared != null) return SectionWindow(file, from, end, shared, pinnedFrom)
        return SectionWindow(file, from, end, chunk)
    }

    /**
     * 同一段区间里**整块预映射**的窗口（CMP-19 的 pin 动作），建不出来返回 null。
     *
     * 只给「关通道之后还要回读」的那一张表用（惰性档的语句段局部串表）：一次 `mapWindow`
     * 把 `[from, from + len)` 全铺上，此后无论正读、回读还是跳读都不会再换块，所以
     * [MappedErbc.close] 之后照样能取值。
     *
     * 拒绝条件两条，任一成立就返回 null（调用方据此**不**关通道）：
     * - 数据块超过 [MAX_TABLE_WINDOW_BYTES]（地址空间策略，见 [canPinStringTable]）；
     * - `mapWindow` 抛异常（通道已关 / 段越界）。
     */
    fun pinnedSibling(
        from: Int,
        len: Int,
    ): SectionWindow? {
        if (!canPinStringTable(len)) return null
        return try {
            SectionWindow(file, from, from + len, file.mapWindow(from, from + len), from)
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * 关掉本窗口底下的产物通道（CMP-19 的释放动作；幂等）。
     *
     * 只允许在「本段所有回读都落在 pin 住的映射里」时调用 —— 关通道不 unmap，
     * 已建映射继续可读（JVM 语义，见 [MappedErbc.close]），但没有 pin 住的部分之后
     * 再读就会撞 `映射已关闭`。
     */
    fun closeUnderlying() {
        file.close()
    }
}

/**
 * 与 `core/code/ErbcFormat.kt` 的 `ErbcCursor` **逐方法同构**：读序、越界消息
 * （「X 需要 n 字节，文件只剩 R 字节（总长 N）」里的 N 取**文件**长度）、
 * `count()` 的负值与「大于剩余字节数」两条判定全部同口径。
 *
 * 唯一有意的差异：判界用 Long 运算（`pos + n` 不会在 2 GiB 附近被 Int 溢出吞掉）。
 */
internal class StreamCursor(
    private val win: SectionWindow,
    start: Int,
    private val fileSize: Int,
) {
    var pos: Int = start
        private set

    /** 本游标所在的段窗口（字符串表切片用）。 */
    val window: SectionWindow get() = win

    val remaining: Int get() = fileSize - pos

    private fun need(n: Int, what: String) {
        if (n < 0 || pos.toLong() + n > fileSize.toLong()) {
            throw ErbcDecodeException(pos, "$what 需要 $n 字节，文件只剩 $remaining 字节（总长 $fileSize）")
        }
    }

    fun u8(): Int {
        need(1, "u8")
        val v = win.byteAt(pos)
        pos += 1
        return v
    }

    fun bool(): Boolean = u8() != 0

    fun u16(): Int {
        need(2, "u16")
        val v = win.u16At(pos)
        pos += 2
        return v
    }

    /** u32 按 Int 原样取回（可为负；无符号语义由调用方判）。 */
    fun u32(): Int {
        need(4, "u32")
        val v = win.u32At(pos)
        pos += 4
        return v
    }

    /**
     * 批量读 [count] 个小端 u32（偏移表 / 下标表专用，2026-10-24 装载提速）。
     *
     * 逐项 [u32] 在真机上是「每项一次 `SectionWindow.u32At` = 一次换块判定 + 4 次映射读」；
     * 全量产物段 2 的局部串池偏移表有 167 万项，逐项读实测 ~270 ms。这里一次
     * [SectionWindow.readInto] 把整块搬进堆内 `ByteArray`，再用手写小端循环解释
     * （与 [CodeSource] 的页批量拷贝同一招），量级降到几十毫秒。
     *
     * 判界口径与逐项读**逐字相同**：先按 `count * 4` 卡一次（溢出用 Long），不够就由同一条
     * [need] 抛「u32 需要 4 字节，文件只剩 N 字节」；调用方（如 `readStringTable`）本来就会
     * 在这之前用更具体的文案挡一次截断，所以畸形产物的报错点/文案不变。
     */
    fun u32Array(count: Int): IntArray {
        if (count < 0) need(4, "u32")
        val bytes = count.toLong() * 4L
        if (bytes > remaining.toLong()) need(4, "u32")
        val n = bytes.toInt()
        if (n == 0) return IntArray(0)
        val raw = ByteArray(n)
        win.readInto(pos, n, raw)
        val out = IntArray(count)
        var b = 0
        var i = 0
        while (i < count) {
            out[i] = (raw[b].toInt() and 0xFF) or
                ((raw[b + 1].toInt() and 0xFF) shl 8) or
                ((raw[b + 2].toInt() and 0xFF) shl 16) or
                ((raw[b + 3].toInt() and 0xFF) shl 24)
            b += 4
            i++
        }
        pos += n
        return out
    }

    fun i32(): Int = u32()

    /**
     * 计数类字段：u32 落在负半轴即畸形；大于「到文件尾的剩余字节数」同样畸形。
     *
     * [minBytesPerItem] 与 `ErbcFormat.ErbcCursor.count` 同口径：按元素最小字节宽度
     * 在**分配之前**卡死上界（LongArray 8 / IntArray 4 / 串表项 4 …），畸形计数
     * 不会撑出远超文件大小的数组。
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
        val v = win.i64At(pos)
        pos += 8
        return v
    }

    fun f64(): Double = Double.fromBits(i64())

    fun skip(n: Int) {
        need(n, "skip")
        pos += n
    }

    /** 跳过 [n] 字节并返回该段起点（同 `ErbcCursor.view`：判界后只前进）。 */
    fun view(
        n: Int,
        what: String,
    ): Int {
        need(n, what)
        val from = pos
        pos += n
        return from
    }
}

/**
 * 段内局部字符串表（布局见规约 §5.1）：堆上只放 `count+1` 个偏移，条目现取现切。
 *
 * 与 `ErbcFormat.kt` 的 `ErbcStringView` 同两条报错（下标越界报 -1、非法 UTF-8 报
 * 表内相对偏移），区别只是数据块留在映射窗口里而不是先复制成 `ByteArray`。
 * [win] 是**数据区自己的窗口**（[SectionWindow.sibling]），不与记录游标抢同一个块。
 */
internal class StreamStringTable(
    private val win: SectionWindow,
    private val dataFrom: Int,
    /**
     * 堆上那张 `count + 1` 偏移表；**null = 偏移表已改成映射页的稀疏分块 [BlockU32s]**（MEM7 刀 C，
     * 见 [useMappedOffsets]）。构造时恒非 null（装载期的单调性/首末偏移校验要逐条读），
     * 只有「偏移表正好落在一张已经整段 pin 住的映射页上」的表才会在收尾把这张数组弃掉。
     */
    private var offsets: IntArray?,
    /** 计量计数器（生产路径为 null）；只记解码次数/字节，不参与取值。 */
    private val stats: DecodeStats? = null,
    /**
     * 是否备忘（默认是）。
     *
     * 惰性语句池（M10-MEM）把这一张表的 [cache] 关掉：备忘表会把**每一条被解过的原文**钉住，
     * 而全量产物的语句段局部池正是那 131.0 MiB「statements.raw 原文」
     * （perf-report §2）；索引趟解完即弃语句，若还留着原文，按需解码就省不出内存。
     * 关掉之后同一条原文会被重复解码（实测平均每项 3.65 次），换来的是「只有真跑到的语句
     * 才为它的字符串付内存」—— 取值语义逐字不变（[StrictUtf8] 的解码结果与两条报错同口径）。
     */
    private val memoize: Boolean = true,
    /**
     * 本表的数据块是否已经**整块 pin 在一份映射里**（CMP-19）。
     *
     * true ⇒ 关掉产物通道之后仍可随机取值（不会换块）；false ⇒ 运行期还可能 `channel.map`，
     * 宿主据此拒绝释放通道。判定与构造同源：见 [SectionWindow.pinnedSibling] 与
     * [canPinStringTable]。
     */
    val channelFree: Boolean = false,
    /**
     * 偏移表区在产物文件里的绝对起点与项数（MEM7 刀 C 的接线坐标；项数 = [count] + 1）。
     * 由 [readStringTable] 一次性算好，[useMappedOffsets] 据此在映射页上定位。
     */
    val indexFrom: Int = 0,
    val indexEntries: Int = 0,
    /** 数据块字节数（诊断/装置读数；原形态可由偏移表末项推出，映射形态推不出 ⇒ 显式带上）。 */
    val dataBytes: Int = 0,
) {
    /** 条目数（= 偏移表项数 − 1）；构造期定死，之后偏移表换形态也不变。 */
    private val entryCount: Int = (offsets?.size ?: indexEntries) - 1

    val count: Int get() = entryCount

    /** 偏移表改走分块映射直取后的读数面（null = 还在用堆上那张数组）。 */
    private var offsetBlocks: BlockU32s? = null

    /** 灌块用的窗口（[threadView] 要各线程一份游标，故把窗口本体留着）。 */
    private var indexWin: SectionWindow? = null

    /** 逐条解码的复用缓冲（只有超过它的那条字符串才扩一次）。实例字段，不是静态可变全局。 */
    private var scratch = ByteArray(0)

    /**
     * 第 [i] 个偏移。**判界一律在取数之前**（与 [get] 的下标判定同口径）：堆上数组与分块表
     * 越界都抛 [IndexOutOfBoundsException]，两条都不许把「越界读」吞成 0。
     */
    private fun offAt(i: Int): Int {
        offsets?.let { return it[i] }
        return offsetBlocks?.get(i)
            ?: throw ErbcDecodeException(-1, "字符串表偏移表未驻留也未接映射块表（内部接线错误）")
    }

    /**
     * 把堆上那张偏移表换成**分块映射直取**（MEM7 刀 C）。
     *
     * 只在 [window] 是整段 pin、且 pin 覆盖 `[indexFrom, indexFrom + 4 × indexEntries)` 时生效 ——
     * 生效即 `offsets = null`（那张 `count + 1` 的 IntArray 随即成为垃圾），此后取值走
     * [BlockU32s]：块第一次被碰到时从同一条映射页一次灌满（**不新增映射、不新增 fd、
     * 不改容器格式**），之后就是数组直取。
     * 条件不成立（换块窗口 / 覆盖不到）返回 false 并原样保留堆上数组。
     *
     * **幂等只看 [offsetBlocks]**（真产物首跑抓出来的接线洞，2026-10-28）：装载期的并行校验趟
     * 用 [threadView]，视图是**带着 `offsets = null` 构造**出来的 —— 若这里再看一眼
     * `offsets == null` 就当「已经接过」返回 true，视图就既没有堆上数组也没有块表，
     * 取值直接撞 [offAt] 的「内部接线错误」。合成样本量不到这一条（校验趟只在真实产物的
     * 并行分带上跑），所以判据只钉在 `offsetBlocks` 这一个真状态上。
     *
     * @return true = 偏移表已改成稀疏分块（装载后堆上只剩块位表）
     */
    internal fun useMappedOffsets(window: SectionWindow): Boolean {
        if (offsetBlocks != null) return true
        if (indexEntries <= 0 || !window.pinnedCovers(indexFrom, indexEntries)) return false
        indexWin = window
        offsetBlocks = BlockU32s(window.fork(), indexFrom, indexEntries)
        offsets = null
        return true
    }

    /** 分块表的已灌块数（perf 装置与判据用例经 [offsetBlockTable] 取；不参与任何取值）。 */
    internal fun offsetBlockTable(): BlockU32s? = offsetBlocks

    /**
     * 条目 → 已解码 `String` 的备忘表（懒分配，一项 4 字节的引用槽）。
     *
     * 为什么值这一张表：语句段局部池 1,679,631 项在装载期被取 6,140,654 次
     * （`[erbc-alloc]` 实测），也就是说**平均每项被取 3.65 次** —— 函数名 / 运算符名 /
     * 变量名 / 高频语句原文都是同一项被上千条语句反复引用。以前每次取值都重新切字节
     * 再解码，重复出来的 `String` 实例还要各自常驻；备忘后同一下标恒返回同一实例。
     *
     * 为什么不担心「把无人引用的串也钉住」：这些条目本来就是写侧从 `Program` 的
     * 语句/表达式字段 intern 出来的，每个条目至少有一个落点，备忘只增加一个引用槽，
     * 不增加字符串本体。
     *
     * **例外**（[memoize] = false）：惰性语句池的表只服务索引趟与按需解码，钉住即前功尽弃，
     * 见构造参数。
     */
    private var cache: Array<String?>? = null

    /** 只做下标判界（与 [get] 的第一句同一条文案与位置），**不解码** —— 边界扫描趟用。 */
    internal fun checkIndex(i: Int) {
        if (i < 0 || i >= count) throw ErbcDecodeException(-1, "字符串表下标越界: $i（共 $count 项）")
    }

    operator fun get(i: Int): String {
        if (i < 0 || i >= count) throw ErbcDecodeException(-1, "字符串表下标越界: $i（共 $count 项）")
        val memo = if (!memoize) null else cache ?: arrayOfNulls<String>(count).also { cache = it }
        memo?.get(i)?.let {
            stats?.onStringHit()
            return it
        }
        val from = offAt(i)
        val len = offAt(i + 1) - from
        val s = if (len == 0) {
            stats?.onStringDecode(0)
            ""
        } else {
            if (scratch.size < len) scratch = ByteArray(maxOf(len, scratch.size * 2))
            win.readInto(dataFrom + from, len, scratch)
            stats?.onStringDecode(len)
            try {
                // 严格 UTF-8：手写结构校验 + `String` 快路径（每次省掉 CharsetDecoder/CharBuffer，
                // 见 [StrictUtf8]）；非法序列的异常由 JDK 解码器原样产出，与上游逐字相同。
                StrictUtf8.decode(scratch, 0, len)
            } catch (e: CharacterCodingException) {
                // 与 `ErbcFormat.kt` 的 ErbcStringView 同一收口：严格解码抛的是
                // CharacterCodingException（IOException 支），必须在装载层包成
                // ErbcDecodeException —— 否则它越过 ProgramLoader 的异常收口，
                // BootFailures.classify 会把畸形产物误判成 OTHER。
                throw ErbcDecodeException(from, "字符串表第 $i 项不是合法 UTF-8", e)
            }
        }
        memo?.set(i, s)
        return s
    }

    /**
     * 同一张表上的**线程私有视图**：共享偏移表与数据窗口区间（只读），各自一份
     * [scratch] 解码缓冲（[get] 的 `scratch` 是实例字段，跨线程共用会把字节混掉）。
     *
     * 只给装载期的并行校验趟用；备忘表关掉（校验趟解完即弃，钉住只会白占堆）。
     * 偏移表在分块直取形态时（MEM7 刀 C）视图**自己**开一块 `BlockU32s`：块位表与灌满的块
     * 都是视图私有的（`BlockU32s` 内部 fork 窗口 ⇒ 每条线程一份游标），共享的只有那份只读 pin。
     */
    internal fun threadView(): StreamStringTable {
        val view = StreamStringTable(
            win.fork(),
            dataFrom,
            // 堆上数组形态：只读共享（视图只读，不改表）；分块形态传 null，由下面的
            // useMappedOffsets 在视图自己的 fork 上另开一张 BlockU32s。
            offsets,
            null,
            memoize = false,
            channelFree,
            indexFrom,
            indexEntries,
            dataBytes,
        )
        if (offsets == null) {
            val w = indexWin ?: return view
            view.useMappedOffsets(w)
        }
        return view
    }
}

/**
 * 读一张字符串表（游标停在表首）；判定与 `ErbcFormat.kt` 的同名函数逐条一致，
 * 只多一条**偏移表字节数**的前置判界（见下）。
 *
 * `count` 在 [StreamCursor.count] 里只按「不超过到文件尾的剩余字节」判界，而这里随即
 * `IntArray(count + 1)` 是 4 B/槽：1 MB 的剩余字节可以声明 1,000,000 项、先申请 4 MB，
 * 之后 `u32()` 才逐个撞见文件结束。先要求 `(count + 1) * 4 <= remaining`，把分配
 * 压回「不超过实际存在的偏移表字节数」，畸形表在分配前就被拒。
 *
 * 越限时的报错**位置与文案**复刻「逐个 `u32()` 读到文件尾」的必然结果
 * （`StreamCursor.need` 的措辞），差分测试要求两条装载路径逐字一致，故不另起新文案。
 */
private fun readStringTable(
    cur: StreamCursor,
    stats: DecodeStats? = null,
    memoize: Boolean = true,
    /**
     * 是否在装载期把数据块**整块 pin 住**（CMP-19）：惰性档的语句段局部串表要这么干 ——
     * 这张表在第一次 `CALL` 之后仍会被回读，不 pin 就不敢关产物通道。
     * 其余表（全局串池 / 表达式段局部表）在装载期就被读成堆内列表，pin 了也没人再用。
     */
    pinWhole: Boolean = false,
): StreamStringTable {
    // 每项至少有 1 个 u32 偏移（count+1 张偏移表），先按 4 字节/项卡分配上界。
    val count = cur.count("字符串表项数", 4)
    val dataLen = cur.count("字符串表数据长度")
    val offsetBytes = (count.toLong() + 1L) * 4L
    if (offsetBytes > cur.remaining.toLong()) {
        val lastPos = cur.pos + (cur.remaining / 4) * 4
        throw ErbcDecodeException(
            lastPos,
            "u32 需要 4 字节，文件只剩 ${cur.remaining % 4} 字节（总长 ${cur.pos + cur.remaining}）",
        )
    }
    // 偏移表批量读（2026-10-24）：逐项 `cur.u32()` 在真机上是 ~270 ms 的长杆（167 万项），
    // 一次搬字节 + 堆内小端解释即可；截断判界由上面那条 `offsetBytes > remaining` 先挡。
    // MEM7 刀 C：这张数组只是**装载期的临时形态**（下面三条判定要逐条读），
    // 表落在已 pin 的整段映射页上时，构造完就地弃掉（`useMappedOffsets`）。
    val indexFrom = cur.pos
    val indexEntries = count + 1
    val offsets = cur.u32Array(indexEntries)
    if (count == 0) {
        if (dataLen != 0) throw ErbcDecodeException(cur.pos - 4, "空字符串表的数据长度应为 0，实为 $dataLen")
        // 空表没有字节要读 ⇒ 运行期不碰通道，按「无回读」放行（channelFree = true）
        return StreamStringTable(
            cur.window.sibling(cur.pos, cur.pos),
            cur.pos,
            offsets,
            stats,
            memoize,
            channelFree = true,
            indexFrom = indexFrom,
            indexEntries = indexEntries,
            dataBytes = 0,
        )
    }
    if (offsets[0] != 0) throw ErbcDecodeException(cur.pos - 4, "字符串表首偏移应为 0，实为 ${offsets[0]}")
    if (offsets[count] != dataLen) {
        throw ErbcDecodeException(cur.pos - 4, "字符串表末偏移(${offsets[count]})与数据长度($dataLen)不符")
    }
    for (i in 1..count) {
        if (offsets[i] < offsets[i - 1]) throw ErbcDecodeException(cur.pos - 4, "字符串表偏移非单调: $i")
    }
    val from = cur.view(dataLen, "字符串表数据")
    // CMP-19：惰性档的语句段局部串表在装载期整块 pin（见参数注释）。pin 不成就退回原形态，
    // 并把「运行期还会换块」如实报给宿主（channelFree = false ⇒ 不许关通道）。
    val pinned = if (pinWhole) cur.window.pinnedSibling(from, dataLen) else null
    val table = StreamStringTable(
        pinned ?: cur.window.sibling(from, from + dataLen, stringTableWindowChunk(dataLen)),
        from,
        offsets,
        stats,
        memoize,
        channelFree = pinned != null,
        indexFrom = indexFrom,
        indexEntries = indexEntries,
        dataBytes = dataLen,
    )
    // 游标本就在**整段 pin** 的窗口上（段 4 函数名表、段 8 形参名表都是这种）⇒ 偏移表就地转映射页。
    // 段 2 / 段 3 的偏移表在装载期用的游标是换块窗口，pin 是**之后**由解码器在主线程铺的
    // （`decodeProgram` 的 `mapWindow(sectionFrom, sectionEnd)`），那两处各自补一次
    // [StreamStringTable.useMappedOffsets]。
    table.useMappedOffsets(cur.window)
    return table
}

/**
 * 装载分段 mark 的公开接缝（M10-MEM）。
 *
 * ## 为什么要有它（不是又一套计量装置）
 *
 * 装载期埋点原本只有宿主那五段（`preflight` / `program` / `meta` / `session` / `ready`），
 * 而 M8-DEVICE-BOOT 实测的 OOM 恰好发生在 `load.program` **段内部**
 * （`ErbcStreamDecoder.decodeProgram`），失败屏的峰值行因此只能显示上一段的读数。
 * 本接缝让解码器在**每个段边界**与**索引趟的每个大块**回调宿主：宿主在自己的 mark 实现里
 * 采样（`app/LoadTrace.mark` 已经是这个形状），峰值归属就落在真正出事的那一段上。
 *
 * core 层只报段名、**不采样**（采样口径归宿主，两处采样会互相打脸），
 * 也不改变任何读取/校验路径：生产路径不传 ⇒ 每次调用只是一次空判。
 * 与内部的 [LoadStageSink]（分配剖析装置）分工不同：那个带计数、只给 perf 测试用，
 * 这个只带段名、给装机装载链用。
 */
fun interface LoadStageMark {
    /** 段名（[LoadStages] 的常量 + `program.` 前缀；索引趟按大块追加 `k/8` 序号）。 */
    fun onStage(stage: String)
}

/**
 * 流式解码器：持有一份 [MappedErbc]（段表已解析、段布局已由调用方校验），
 * 每段各自开窗口解码，解码结果与 [ErbcReader] 对同一份字节逐字段相同。
 */
/**
 * 流式副本：段 7 调用点实参表。与 `ErbcReader.kt:readCallArgs` 逐条判定一致
 * （`ErbcCursor` → `StreamCursor`，`ErbcStringView` → `StreamStringTable`）。
 *
 * **MEM9 刀 1：建的是 [CallArgIndex.Builder]，不是 `LinkedHashMap`。**
 * 判定/报错/读序与 `ErbcReader` 那份逐字相同（每条 `cur.*` 的顺序、位置、文案一个字节没动），
 * 换掉的只是「登记容器」：旧写法把 151,120 个站点登记进 `LinkedHashMap<Int, List<CallArg>>`
 * （节点 40 B + 装箱 `Integer` 键 16 B + 桶数组 4 B/槽 ≈ 9.1 MiB），`Program` 构造时
 * [canonicalCallArgs] 再去重成**第二张** LinkedHashMap、`callArgsPcs`/`callArgsVals`
 * 再复制**第三份索引**（mem7 探针 §1 的「段7 桶 10.7 MiB」）。
 * 现在解码趟边读边归一（`CallArg` 结构 / 实参表序列，同一套「首个到达者当代表」），
 * 出来的就是那两张平行数组 —— 装载期与稳态都不再有任何 Map 壳。
 */
private fun readCallArgs(
    cur: StreamCursor,
    instructionCount: Int,
    stats: DecodeStats? = null,
): CallArgIndex {
    val siteCount = cur.count("调用点条数", 8)
    val totalArgCount = cur.count("实参总数")
    val names = readStringTable(cur, stats)
    val out = CallArgIndex.Builder()
    var seenArgs = 0
    var prevPc = -1
    for (i in 0 until siteCount) {
        val at = cur.pos
        val pc = cur.u32()
        if (pc < 0 || pc >= instructionCount) {
            throw ErbcDecodeException(at, "第 $i 个调用点 pc=$pc 越界（指令数 $instructionCount）")
        }
        if (pc <= prevPc) {
            throw ErbcDecodeException(at, "调用点 pc 未严格升序：$pc 紧跟 $prevPc（同一 pc 只能有一份实参表）")
        }
        prevPc = pc
        // 每条实参记录 = position 4 + bucket 4 + flags 1 + name 4 = 13 字节。
        val argCount = cur.count("调用点 pc=$pc 的实参个数", 13)
        if (argCount == 0) {
            throw ErbcDecodeException(cur.pos - 4, "调用点 pc=$pc 的实参个数为 0（v2 只登记非空表）")
        }
        seenArgs += argCount
        val args = ArrayList<CallArg>(argCount)
        var prevPos = -1
        for (j in 0 until argCount) {
            val atArg = cur.pos
            val position = cur.u32()
            if (position < 0) throw ErbcDecodeException(atArg, "调用点 pc=$pc 的第 $j 个实参位为负: $position")
            if (position <= prevPos) {
                throw ErbcDecodeException(atArg, "调用点 pc=$pc 的实参位未严格升序：$position 紧跟 $prevPos")
            }
            prevPos = position
            val bucket = cur.u32()
            if (bucket < 0) throw ErbcDecodeException(atArg + 4, "调用点 pc=$pc 的实参桶号为负: $bucket")
            val flags = cur.u8()
            if ((flags and ErbcFormat.CallArgFlags.MASK.inv()) != 0) {
                throw ErbcDecodeException(atArg + 8, "调用点 pc=$pc 的实参 flags 含未定义位: 0x${flags.toString(16)}")
            }
            val nameIdx = cur.u32()
            val name = if (nameIdx == NONE_REF) null else names[nameIdx]
            args.add(
                CallArg(
                    position = position,
                    bucket = bucket,
                    isStr = (flags and ErbcFormat.CallArgFlags.IS_STR) != 0,
                    name = name,
                    present = (flags and ErbcFormat.CallArgFlags.PRESENT) != 0,
                ),
            )
        }
        out.add(pc, args)
    }
    if (seenArgs != totalArgCount) {
        throw ErbcDecodeException(0, "实参总数声明 $totalArgCount，逐站求和为 $seenArgs（文件被截断或计数被篡改）")
    }
    return out.build()
}

/**
 * 流式副本：段 8 函数头部形参名表（与 `ErbcReader.kt:readParamNames` 逐条判定一致）。
 *
 * M11-MEM：装载期只留「每个函数形参块的偏移 + 条数」两张 `IntArray`，名字本体按需解码
 * （[LazyParamNames]）。索引趟仍逐条把名字读一遍（含严格 UTF-8 校验）⇒ 畸形产物的报错
 * 时机、类型与字节位置与整池解码逐字相同；串池备忘关掉后这些临时 `String` 解完即弃。
 */
private fun readParamNameTable(
    cur: StreamCursor,
    expectedFuncCount: Int,
    stats: DecodeStats? = null,
): ParamNameTable {
    val funcCount = cur.count("形参名段函数个数", 4)
    if (funcCount != expectedFuncCount) {
        throw ErbcDecodeException(
            cur.pos - 4,
            "形参名段函数个数($funcCount)与函数表($expectedFuncCount)不符",
        )
    }
    val names = readStringTable(cur, stats, memoize = false)
    val counts = IntArray(funcCount)
    val dataFrom = IntArray(funcCount)
    for (i in 0 until funcCount) {
        // 每个函数至少一个 u32 paramCount。
        val n = cur.count("函数 $i 的形参个数", 4)
        counts[i] = n
        if (n == 0) continue
        dataFrom[i] = cur.pos
        repeat(n) { names[cur.u32()] }
    }
    return ParamNameTable(cur.window, names, counts, dataFrom)
}

/**
 * 段 8 的惰性形参名索引（M11-MEM）。
 *
 * 读面只有两件事：[namesOf] 给出某函数的只读名表（空表复用全局单例），[hasAny] 回答
 * 「有没有任一函数带形参名」（`Program.bindsCallArgsPositionally` 用，不物化任何函数）。
 */
private class ParamNameTable(
    private val window: SectionWindow,
    private val strings: StreamStringTable,
    private val counts: IntArray,
    private val dataFrom: IntArray,
) {
    fun namesOf(funcIndex: Int): List<String> {
        val n = counts[funcIndex]
        return if (n == 0) emptyList() else LazyParamNames(window, strings, dataFrom[funcIndex], n)
    }

    fun hasAny(): Boolean {
        for (c in counts) if (c > 0) return true
        return false
    }
}

/**
 * 单个函数的形参名只读视图：第 i 项 = 段 8 里该函数块的第 i 个 u32 名字下标。
 *
 * 索引趟已经校验过下标范围与 UTF-8，故取值只可能是「表内合法项」；`AbstractList` 提供
 * `toList`/`iterator`/`equals`，对外与旧的 `ArrayList<String>` 同口径。
 */
private class LazyParamNames(
    private val window: SectionWindow,
    private val strings: StreamStringTable,
    private val dataFrom: Int,
    private val count: Int,
) : AbstractList<String>() {
    /**
     * 逐项备忘（只有真被取过的项才落槽）：`ProcessRuntime.onFunctionEnter` 每次调用都会读
     * 一遍形参名，不备忘就会把「每条调用的每个形参」都重新走一次 UTF-8 解码。
     * 槽位与 [FunctionInfo] 同寿，规模 = 该函数的形参数（不是全表）。
     */
    private val memo = arrayOfNulls<String>(count)

    override val size: Int get() = count

    override fun get(index: Int): String {
        if (index < 0 || index >= count) {
            throw IndexOutOfBoundsException("形参名下标越界: $index（共 $count 项）")
        }
        memo[index]?.let { return it }
        val s = strings[window.u32At(dataFrom + index * 4)]
        memo[index] = s
        return s
    }
}

/**
 * 段 4 的**惰性函数表**（M11-MEM）。
 *
 * ## 换掉了什么
 *
 * 改造前 `decodeProgram` 把全部函数记录一次解进 `ArrayList<FunctionInfo>`：全量语料
 * 110,072 个条目，每个条目持有函数名 `String`、形参名表、以及（少数）标签平行表。
 * 现在装载只付三样：
 *
 * 1. **记录偏移表** `IntArray(funcCount)`（4 B/条 ≈ 0.44 MiB）—— 索引趟顺走每条记录、
 *    只读定长头与标签表长度，不构造 `FunctionInfo`；
 * 2. **条目缓存** `Array<FunctionInfo?>`（4 B/槽 ≈ 0.44 MiB）—— 同一下标恒返回同一实例；
 * 3. 函数名/形参名的**段窗口整段映射**（堆内 0 常驻），名字在条目第一次被取用时才解码。
 *
 * ## 为什么语义不变
 *
 * 取值走的是**同一个**记录起始偏移、同一段字节、同一套读序；索引趟已经走完每条记录，
 * 所以重复函数名 / 非零标志位 / 标签计数越界仍然在装载期、在同一个字节位置抛出同一句话。
 * `validateRecords` 在 `Program` 构造后逐条重放一遍定长字段与标签 pc 的判界（不缓存），
 * 报错顺序与改造前 `validate` 的 `for (f in program.functions)` 完全一致。
 *
 * 依据：原版函数元数据是**逐条自描述**的 ——
 * `reference/emuera-cs/MinorShift.Emuera.Runtime.Script.Statements/FunctionLabelLine.cs:19-53`
 * 的 `LabelName`/`ArgLength`/`LocalLength`/`Index` 都挂在单个函数行上；函数下标即声明序，
 * 由 `reference/emuera-cs/MinorShift.Emuera.Runtime.Script.Data/LabelDictionary.cs:318-322`
 * 的 `point.Index = count; count++` 逐条登记，不存在需要全表聚合的字段。
 */
private class MappedFunctionTable(
    private val window: SectionWindow,
    private val fileSize: Int,
    private val recordOffsets: IntArray,
    private val strings: StreamStringTable,
    private val paramNames: ParamNameTable?,
) : AbstractList<FunctionInfo>(), LazyFunctionTable {
    private val cache = arrayOfNulls<FunctionInfo>(recordOffsets.size)

    override val size: Int get() = recordOffsets.size

    override var materializedCount: Int = 0
        private set

    override fun get(index: Int): FunctionInfo {
        if (index < 0 || index >= recordOffsets.size) {
            throw IndexOutOfBoundsException("函数下标越界: $index（共 ${recordOffsets.size} 个）")
        }
        cache[index]?.let { return it }
        val f = decode(index)
        cache[index] = f
        materializedCount++
        return f
    }

    /** 从记录自己的起始偏移重放一条函数记录（读序与索引趟、与 `ErbcReader` 逐字相同）。 */
    private fun decode(index: Int): FunctionInfo = decodeInto(index, window, strings)

    /**
     * [decode] 的**指定窗口/串表**版本：解析与报错逐字相同，只是不认成员字段。
     *
     * 并行校验趟每个条带持一份自己的 [SectionWindow]（[SectionWindow.fork]）与
     * [StreamStringTable]（[StreamStringTable.threadView]）—— 两者的换块游标/解码缓冲
     * 都是实例字段，不能跨线程共用；记录偏移表与产物字节仍然共享（只读）。
     */
    private fun decodeInto(
        index: Int,
        win: SectionWindow,
        str: StreamStringTable,
    ): FunctionInfo {
        val cur = StreamCursor(win, recordOffsets[index], fileSize)
        val name = str[cur.u32()]
        val entryPc = cur.i32()
        val arity = cur.i32()
        val slotCount = cur.i32()
        val funcFlags = cur.u32()
        if (funcFlags != 0) {
            throw ErbcDecodeException(
                cur.pos - 4,
                "函数 $name 带标志位(0x${funcFlags.toString(16)})，但 FunctionInfo 无对应字段（规约 §5.5 / §9）",
            )
        }
        // 每条标签记录 = u32 名字下标 + i32 pc。
        val labelCount = cur.count("函数 $name 的标签数", 8)
        val info = FunctionInfo(name, entryPc, arity, slotCount, paramNames?.namesOf(index) ?: emptyList())
        for (j in 0 until labelCount) {
            // 求值序不可换：先读名字下标、再读 pc，与容器里的「名字, pc」成对序一致。
            info.addLabel(str[cur.u32()], cur.i32())
        }
        return info
    }

    /**
     * 只读记录里的 `entryPc`（名字下标只跳过、**不解串**）。
     *
     * 每条函数记录至少 24 字节（见 [validateRecordOffsets]），前 8 字节固定是
     * `u32 名字下标 + i32 entryPc` ⇒ 定长读不会越界，也不需要 `FunctionInfo` 分配。
     */
    private fun entryPcAt(index: Int): Int {
        val cur = StreamCursor(window, recordOffsets[index], fileSize)
        cur.u32()
        return cur.i32()
    }

    /** 只在**报错路径**取函数名（解一条子串）；正常装载零调用。 */
    fun nameAt(index: Int): String = strings[StreamCursor(window, recordOffsets[index], fileSize).u32()]

    /**
     * 只读记录里的标签 pc 数组（函数名下标与标签名下标都跳过、不解串）。
     *
     * 返回 null = 记录区本身畸形（标签数越界）：这类畸形归 [validateRecords] 在跳转校验
     * **之前**统一报，`_JUMP_DYN` 扫描不得抢先抛出另一句话。
     */
    fun labelPcsAt(index: Int): IntArray? =
        try {
            val cur = StreamCursor(window, recordOffsets[index], fileSize)
            cur.u32() // 函数名下标
            cur.i32() // entryPc
            cur.i32() // arity
            cur.i32() // slotCount
            cur.u32() // flags
            val labelCount = cur.count("函数标签数", 8)
            val pcs = IntArray(labelCount)
            for (j in 0 until labelCount) {
                cur.u32() // 标签名下标
                pcs[j] = cur.i32()
            }
            pcs
        } catch (_: ErbcDecodeException) {
            null
        }

    /**
     * 建跳转归属扫描器（CMP-55 映射路接线）：只读每条记录的 `entryPc`，按 entryPc 排序后
     * 求区间端点。**不物化**任何 [FunctionInfo] —— 常驻是三个 `IntArray(size)`（110k 时各
     * 440 KiB），打包排序用的 `LongArray` 用完即弃。
     */
    fun jumpScan(instructionCount: Int): JumpScan {
        val n = recordOffsets.size
        val entry = IntArray(n)
        for (i in 0 until n) entry[i] = entryPcAt(i)
        // 打包 (entryPc, index) 排序：避免 110k 个 Integer 装箱。负 entryPc（记录区畸形）
        // 按无符号排到末尾 —— 那种产物会被 validateRecords 先拒，这里只需要一个不会崩的顺序。
        val packed = LongArray(n) { ((entry[it].toLong() and 0xFFFF_FFFFL) shl 32) or it.toLong() }
        java.util.Arrays.sort(packed)
        val order = IntArray(n)
        val starts = IntArray(n)
        for (k in 0 until n) {
            val fi = (packed[k] and 0xFFFF_FFFFL).toInt()
            order[k] = fi
            starts[k] = entry[fi]
        }
        val ends = IntArray(n)
        for (k in 0 until n) ends[k] = if (k + 1 < n) starts[k + 1] else instructionCount
        return JumpScan(this, order, starts, ends)
    }

    override fun validateRecords(instructionCount: Int) {
        val n = recordOffsets.size
        // 并行条带：110,072 条函数记录在真机上单线程 ~1.9 s（每条都要重放记录 + 取名字串），
        // 记录之间完全独立。条带**连续升序** + 主线程按升序 join ⇒ 最先抛出的必然是
        // 最小函数下标的那条错误，与串行首差同一条文案。
        val workers = minOf(
            MAX_VALIDATE_WORKERS,
            Runtime.getRuntime().availableProcessors() - 1,
            n / RECORDS_PER_VALIDATE_WORKER,
        ).coerceAtLeast(1)
        if (workers <= 1) {
            for (i in 0 until n) {
                // 不写回缓存：校验趟的对象解完即弃，装载完 materializedCount 仍是 0。
                validateFunctionRecord(cache[i] ?: decode(i), instructionCount)
            }
            return
        }
        val errors = arrayOfNulls<Throwable>(workers)
        val threads = arrayOfNulls<Thread>(workers)
        for (w in 0 until workers) {
            val lo = (n.toLong() * w / workers).toInt()
            val hi = (n.toLong() * (w + 1) / workers).toInt()
            val t = Thread({
                try {
                    val win = window.fork()
                    val str = strings.threadView()
                    for (i in lo until hi) {
                        validateFunctionRecord(cache[i] ?: decodeInto(i, win, str), instructionCount)
                    }
                } catch (t: Throwable) {
                    errors[w] = t
                }
            }, "eratw-fnvalidate-$w")
            t.isDaemon = true
            threads[w] = t
        }
        for (t in threads) t?.start()
        // 先 join 干净再抛：绝不允许带着活线程离开（调用方下一帧就 close 映射，CMP-11）。
        var first: Throwable? = null
        for (w in threads.indices) {
            threads[w]?.join()
            val e = errors[w] ?: continue
            val prev = first
            if (prev == null) first = e else prev.addSuppressed(e)
        }
        first?.let { throw it }
    }

    override fun hasAnyParamNames(): Boolean = paramNames?.hasAny() ?: false

    companion object {
        /**
         * 索引趟：逐条走完函数记录，只留每条记录的起始偏移。
         *
         * 与逐条解码的唯一差别是**不构造 `FunctionInfo`**：重复名、标志位、标签计数与标签
         * 名字的读取/校验全部照做，所以畸形产物的报错点不变。
         */
        fun index(
            cur: StreamCursor,
            funcCount: Int,
            strings: StreamStringTable,
            paramNames: ParamNameTable?,
            fileSize: Int,
        ): MappedFunctionTable {
            val offsets = IntArray(funcCount)
            val seen = HashSet<String>(funcCount)
            for (i in 0 until funcCount) {
                offsets[i] = cur.pos
                val name = strings[cur.u32()]
                if (!seen.add(name)) throw ErbcDecodeException(cur.pos - 4, "函数重复声明: $name")
                cur.i32() // entryPc
                cur.i32() // arity
                cur.i32() // slotCount
                val flags = cur.u32()
                if (flags != 0) {
                    throw ErbcDecodeException(
                        cur.pos - 4,
                        "函数 $name 带标志位(0x${flags.toString(16)})，但 FunctionInfo 无对应字段（规约 §5.5 / §9）",
                    )
                }
                // 每条标签记录 = u32 名字下标 + i32 pc。
                val labelCount = cur.count("函数 $name 的标签数", 8)
                for (j in 0 until labelCount) {
                    strings[cur.u32()]
                    cur.i32()
                }
            }
            return MappedFunctionTable(cur.window, fileSize, offsets, strings, paramNames)
        }
    }
}

/**
 * 跳转归属的**无物化**扫描器（CMP-55 的映射路接线，2026-10-26）。
 *
 * 判据与文案和 [ErbcReader.requireJumpTargets] 逐条相同，只是区间端点来自记录里的
 * `entryPc`（[MappedFunctionTable.jumpScan]），不把 110,072 条 `FunctionInfo` 物化 ——
 * 后者的常驻代价是装载峰值的主要嫌疑项（每次 `program.functions[i]` 都会解函数名子串
 * 并永久缓存）。
 *
 * **错误优先级**照字节路：那边把 `requireJumpTargets` 放在函数记录校验**之后**，所以
 * 「同一份产物既有记录区畸形又有跨函数跳」时报的是记录区。这里把指令趟里发现的越界记成
 * 「(函数下标, pc) 最小」的那一条候选，等 `validateRecords` 通过后再抛 —— 既不多走一遍
 * 480 万条指令，报出的又是字节路会先报的那一条。
 */
private class JumpScan(
    private val table: MappedFunctionTable,
    private val order: IntArray,
    private val starts: IntArray,
    private val ends: IntArray,
) {
    private var cursor = 0
    private var labelFunc = -1
    private var labelPcs: IntArray? = null

    private var pendingFunc = Int.MAX_VALUE
    private var pendingPc = Int.MAX_VALUE
    private var pendingMes: String? = null

    /** 指令趟逐条喂（pc 必须严格递增）。 */
    fun observe(pc: Int, op: Int, a: Int, b: Int) {
        // 推进到「entryPc ≤ pc 的最后一个函数」：重复 entryPc 时前者区间为空（end == start），
        // 与字节路的 `if (end <= start) continue` 同口径，由后者独占该区间。
        while (cursor + 1 < order.size && starts[cursor + 1] <= pc) cursor++
        if (starts[cursor] > pc) return
        val start = starts[cursor]
        val end = ends[cursor]
        if (pc >= end) return
        when (op) {
            Opcode._JUMP -> consider(start, end, pc, op, pc.toLong() + 1 + a)
            Opcode._JUMP_IF_FALSE, Opcode._JUMP_IF_TRUE -> consider(start, end, pc, op, pc.toLong() + 1 + b)
            Opcode._JUMP_DYN -> {
                val labels = labelsOf(order[cursor]) ?: return
                if (a < 0 || a >= labels.size) {
                    note(order[cursor], pc) {
                        ErbcReader.jumpDynLabelIndexMes(pc, a, table.nameAt(order[cursor]), labels.size)
                    }
                } else {
                    consider(start, end, pc, op, labels[a].toLong())
                }
            }
        }
    }

    /** 记录校验通过之后调用：有候选即抛（顺序与字节路一致）。 */
    fun throwIfPending() {
        pendingMes?.let { throw ErbcDecodeException(0, it) }
    }

    private fun consider(start: Int, end: Int, pc: Int, op: Int, target: Long) {
        if (target >= start && target < end) return
        note(order[cursor], pc) {
            ErbcReader.jumpOutOfRangeMes(pc, op, target, table.nameAt(order[cursor]), start, end)
        }
    }

    /** 只留「按字节路优先序会更早报」的那一条；`(函数下标, pc)` 严格更小才替换。 */
    private fun note(funcIndex: Int, pc: Int, mes: () -> String) {
        if (funcIndex > pendingFunc || (funcIndex == pendingFunc && pc >= pendingPc)) return
        pendingFunc = funcIndex
        pendingPc = pc
        pendingMes = mes()
    }

    /** 该函数的标签 pc 表（一函数缓存）；记录区畸形返回 null（由 validateRecords 统一报）。 */
    private fun labelsOf(funcIndex: Int): IntArray? {
        if (funcIndex == labelFunc) return labelPcs
        val pcs = table.labelPcsAt(funcIndex)
        labelFunc = funcIndex
        labelPcs = pcs
        return pcs
    }
}

/** 函数记录的装载期判界（整池档与惰性档共用同一句话，M11-MEM）。 */
private fun validateFunctionRecord(
    f: FunctionInfo,
    instructionCount: Int,
) {
    if (f.entryPc !in 0 until instructionCount) {
        throw ErbcDecodeException(0, "函数 ${f.name} 的 entryPc=${f.entryPc} 越界（指令数 $instructionCount）")
    }
    if (f.arity < 0 || f.slotCount < 0) {
        throw ErbcDecodeException(0, "函数 ${f.name} 的 arity/slotCount 为负: ${f.arity}/${f.slotCount}")
    }
    // 跨字段不变量（与 ErbcReader 逐字同款）：arity 是被调帧 R0..R(arity-1) 的实参落位数，
    // 必须 <= 帧槽数 slotCount；否则 VM 建帧时实参复制会越出被调帧。整池档与惰性档必须同判，
    // 否则「同一畸形产物两条装载路径读出的异常」会出现分叉（见 ErbcStreamEquivalenceTest）。
    if (f.arity > f.slotCount) {
        throw ErbcDecodeException(
            0,
            "函数 ${f.name} 的 arity(${f.arity}) 大于 slotCount(${f.slotCount})：" +
                "实参复制会越出被调帧槽区间（畸形产物）",
        )
    }
    for (i in 0 until f.labelCount) {
        val pc = f.labelPcs[i]
        if (pc !in 0 until instructionCount) {
            throw ErbcDecodeException(0, "函数 ${f.name} 的标签 \$${f.labelNames[i]} 的 pc=$pc 越界（指令数 $instructionCount）")
        }
    }
}

/**
 * 装载分阶段计量钩子（只服务装载期分配剖析，生产路径不传）。
 *
 * 存在的理由：装载期 churn 的归因必须落到**段**上（头/字符串表/常量池/表达式池/语句池/
 * 函数表/指令流/行号表），否则「1966 MiB 分配」只能整体看，看不出该先动哪一段。
 * 钩子由调用方以参数传入（不是静态可变全局，AGENTS §2 铁律 4），`null` 时每次
 * 调用只是一次 `?.invoke`，`decodeProgram` 的分支预测不受影响。
 *
 * 边界语义：每次 [onStage] 恰好落在「某段的解码结束、下一段开始之前」，因此
 * `alloc(第 k+1 次) - alloc(第 k 次)` 就是该段的一次性分配量（含该段局部串池与节点表）。
 * [LoadStage] 同时带上**本段内的字符串解码计数**（`StreamStringTable.get` 的调用次数与
 * 解码字节数）：JVM 没有精确的对象分配计数器，而「一次 `get` = 一个 `String` +
 * 一个紧凑字节数组」是唯一能逐条点清的构造点 —— 它也是装载 churn 的主要来源。
 */
internal fun interface LoadStageSink {
    fun onStage(stage: LoadStage)
}

/** 一次段边界的统计快照（只在 [LoadStageSink] 非空时创建，一段一个对象）。 */
internal class LoadStage(
    val name: String,
    val stringDecodes: Long,
    val stringBytes: Long,
    val stringHits: Long,
)

/** 解码期计数（只挂在解码器实例上，无静态可变状态）。 */
internal class DecodeStats {
    var stringDecodes: Long = 0
    var stringBytes: Long = 0
    var stringHits: Long = 0

    fun onStringDecode(len: Int) {
        stringDecodes++
        stringBytes += len.toLong()
    }

    fun onStringHit() {
        stringHits++
        stringDecodes++
    }
}

/** 分阶段计量用一个常量表：阶段的**发射顺序**就是这张表，读数按它对齐。 */
internal object LoadStages {
    /** 读容器头（`MappedErbc.open`：magic/版本/标志位/段表）。 */
    const val HEADER = "读容器头"

    /** 段偏移表校验（`checkSectionLayout`：段起点/段长/CODE 与行表计数）。 */
    const val SECTION_TABLE = "段偏移表校验"

    /** 段 3 语句偏移侧车（`program.stmt.idx`；缺失也算一段，读数为 0）。 */
    const val STMT_SIDECAR = "语句偏移侧车"

    const val STRINGS = "段0 字符串池"
    const val CONSTANTS = "段1 常量池"
    const val EXPR_STRINGS = "段2 局部串池"
    const val EXPR_NODES = "段2 节点表"
    const val EXPR_ROOTS = "段2 池根表"
    const val STMT_STRINGS = "段3 局部串池"
    const val STMT_NODES = "段3 节点表"
    const val STMT_RECORDS = "段3 语句记录"
    const val FUNCTIONS = "段4 函数表（含段8 形参名）"
    const val CODE = "段5 指令流"
    const val LINES = "段6 行号表"
    const val CALL_ARGS = "段7 调用点实参表"
    const val POOL_REFILL = "池重灌与下标保真"
    const val VALIDATE = "引用范围校验"

    /**
     * 装载后的规范化去重（`Program.internStatementArgs`，M6-PERFINT）。
     *
     * 之前这一步**没有埋点**，全量产物上真机 7.5 s 全部落进 `program` 与上一段之间的空白里
     * （2026-10-23 性能：`引用范围校验` 报点 14.1 s、`program` 报点 21.6 s）。单独报一段，
     * 失败屏/loadtrace 才能说清这 7.5 秒花在哪儿。
     */
    const val INTERN = "语句实参规范化去重"

}

/**
 * 稀疏引用表（MEM7 刀 2）：[size] 个可空引用槽，**只有被写过的那一块才分配**。
 *
 * ## 为什么存在
 *
 * 惰性池的条目槽原来是「条数 × 4 B 的一整张数组」：段 3 的 1,716,636 槽 = 6.5 MiB、
 * 段 3 / 段 2 的节点表 827,992 / 898,111 槽 = 3.2 / 3.4 MiB。mem56 报告 §1 实测这三张表
 * **装载后的非空槽都是 0** —— 为一条都没被取用的条目常驻了 13.1 MiB，而标题页稳态就是这笔。
 * 改成 4,096 槽一块的分块表后：常驻 = 块表本体（1,716,636 槽 → 419 个块位 ≈ 1.7 KiB）
 * 加上**真被物化到的那些块**；取用面多一次块定位（一次数组读 + 一次位运算）。
 *
 * ## 语义边界
 *
 * - 判界与文案由调用方负责（与改造前的 `Array` / `AtomicReferenceArray` 一样：本类不做
 *   越界友好化，越界就是 [IndexOutOfBoundsException]，取用面的显式下标判定一条不动）；
 * - 「同一下标恒同一实例」靠块内元素仍是 `AtomicReferenceArray`（元素读写带 volatile 语义，
 *   与 [LazyStatementRecords] 的 CMP-56 口径一致）；块本身的安装走 `compareAndSet`，
 *   并发首次写同一块时不会有人拿到一块**没发布出去的**空块而当 miss 用（两个块实例并存
 *   只会让同一下标解出两个实例，那种并发本就不被承诺）。
 */
internal class BlockRefs<T : Any>(val size: Int) {

    private val blocks = AtomicReferenceArray<Any?>(if (size <= 0) 0 else ((size - 1) shr BLOCK_SHIFT) + 1)

    /** 块位数（= 表长换算出来的块数；perf 装置按「块位数 × 4 B」算块表本体常驻）。 */
    val blockCount: Int get() = blocks.length()

    operator fun get(index: Int): T? {
        val b = blocks.get(index shr BLOCK_SHIFT) ?: return null
        return castBlock(b).get(index and BLOCK_MASK)
    }

    operator fun set(
        index: Int,
        value: T,
    ) {
        val bi = index shr BLOCK_SHIFT
        var b = blocks.get(bi)
        if (b == null) {
            val fresh = AtomicReferenceArray<T?>(blockLength(bi))
            b = if (blocks.compareAndSet(bi, null, fresh)) fresh else blocks.get(bi) ?: fresh
        }
        castBlock(b).set(index and BLOCK_MASK, value)
    }

    /** 整表清空（[RecordSource.enableLazyNodes] / [RecordSource.discardBuiltNodes]）：块一并弃掉。 */
    fun clear() {
        for (i in 0 until blocks.length()) blocks.set(i, null)
    }

    /** 升序遍历**已物化**的条目（释放原文一类批处理；不再白扫 171 万个空槽）。 */
    fun forEachActive(block: (T) -> Unit) {
        for (i in 0 until blocks.length()) {
            val b = blocks.get(i) ?: continue
            val arr = castBlock(b)
            for (j in 0 until arr.length()) arr.get(j)?.let(block)
        }
    }

    /** 已物化条数（诊断/perf 装置读数；不参与任何取值）。 */
    fun nonNullEntries(): Int {
        var n = 0
        forEachActive { n++ }
        return n
    }

    /** 已分配的块数（perf 装置按「块数 × 块宽」算常驻）。 */
    fun allocatedBlocks(): Int {
        var n = 0
        for (i in 0 until blocks.length()) if (blocks.get(i) != null) n++
        return n
    }

    /** 第 [blockIndex] 块的槽数（末块可能不足一块）。 */
    fun blockLength(blockIndex: Int): Int =
        minOf(BLOCK.toLong(), size.toLong() - blockIndex.toLong() * BLOCK).toInt()

    @Suppress("UNCHECKED_CAST")
    fun castBlock(b: Any): AtomicReferenceArray<T?> = b as AtomicReferenceArray<T?>

    private companion object {
        /** 块宽 4,096 槽 = 16 KiB 背衬：全量语料三张表各 202～419 个块位，块表本体 ≤ 2 KiB。 */
        const val BLOCK_SHIFT = 12
        const val BLOCK = 1 shl BLOCK_SHIFT
        const val BLOCK_MASK = BLOCK - 1
    }
}

/**
 * 稀疏 u32 索引表（MEM7 刀 C/刀 D；契约里写作「BlockInts」，落地名沿用 `BlockU32s` ——
 * 它装的是产物里那一段段小端 u32，与 [BlockRefs] 的「引用槽」不是一回事）：
 * `size` 个**从映射页取出的 u32**，块（[IBLOCK] = 4,096 项）在被第一次取到时一次性灌满
 * （一次批量读 + 堆内小端解释），之后取值就是「块定位 + `IntArray` 直取」。
 *
 * ## 为什么不是「每次都读映射页」
 *
 * 同一份真产物上的窄基准（`perf/SkeletonFetchBenchTest`，2 轮取最小）：
 * 逐次 `SectionWindow.u32At` 的形态把 `functions[i].name` 从 292.8 ns/op 拖到 464.9、
 * `expr(k)` 从 949.3 拖到 1,390.9 —— 一次 u32 读要走一遍区间判定 + 4 次带界的
 * `ByteBuffer.get`，而一张偏移表每取一条字符串要读**相邻两个** u32。
 * 分块灌满之后取用面回到数组直读，而装载期常驻仍然只有块位表
 * （全量语料五张表合计 543 个块位 ≈ 2.2 KiB）。
 *
 * ## 块宽为什么与 [BlockRefs] 同取 4,096
 *
 * 常驻只在「这一块被取到过」时付，块宽只改变付的**粒度**：段 3 偏移表 1,680,720 项在
 * 4,096 项/块下是 411 个块位（块位表本体 ≈ 1.7 KiB），一块 16 KiB。取用面每次灌块只发生
 * 在**首次**碰这一块时，灌满后与整张 `IntArray` 同一读数。
 *
 * ## 语义边界
 *
 * - 值与「整张 IntArray」逐位相同：灌块走的就是那 `[from, from + 4×size)` 个字节的小端解释，
 *   与 `StreamCursor.u32Array` 同一条换算；
 * - 判界仍在**取数之前**：调用方（[StreamStringTable.get] / [MappedExprSource.node]）
 *   先按下标判界，本类的 `[index]` 只接合法下标；越界就是 [IndexOutOfBoundsException]，
 *   与旧的 `IntArray` 同一形态，绝不允许把越界读吞成 0；
 * - 块的安装走 `compareAndSet`，且**每次灌块自带一份窗口游标**（[fill] 里 `win.fork()`）：
 *   `SectionWindow.readInto` 走的是实例私有的 `duplicate()` 游标，两条线程共用一份游标时
 *   「置位 + 批量取」会交错 ⇒ 灌出来的块内容错位。fork 一份就各走各的游标，共享的只有
 *   那份只读映射 ⇒ 并发首次灌同一块时两边算出的内容逐字节相同，谁胜出都不影响取值。
 * - 块内容是从映射页**拷进堆**的 `IntArray`，之后与映射生命周期解耦：通道关掉、窗口换掉
 *   都不影响已灌的那一块（判据用例见 `ProgramMmapReleaseTest` 的「稀疏块与映射生命周期解耦」）。
 */
internal class BlockU32s(
    private val win: SectionWindow,
    private val from: Int,
    val size: Int,
) {
    private val blocks = AtomicReferenceArray<IntArray>(if (size <= 0) 0 else ((size - 1) shr IBLOCK_SHIFT) + 1)

    /** 块位数（perf 装置按「块位数 × 4 B」算块表本体常驻）。 */
    val blockCount: Int get() = blocks.length()

    /** 已灌满的块数（装载后 = 0；每条被取到的项只给自己那一块付一次 16 KiB）。 */
    fun filledBlocks(): Int {
        var n = 0
        for (i in 0 until blocks.length()) if (blocks.get(i) != null) n++
        return n
    }

    /** 第 [blockIndex] 块的项数（末块可能不足一块）。 */
    fun blockLength(blockIndex: Int): Int =
        minOf(IBLOCK.toLong(), size.toLong() - blockIndex.toLong() * IBLOCK).toInt()

    operator fun get(index: Int): Int {
        val bi = index shr IBLOCK_SHIFT
        var b = blocks.get(bi)
        if (b == null) {
            val fresh = fill(bi)
            b = if (blocks.compareAndSet(bi, null, fresh)) fresh else blocks.get(bi) ?: fresh
        }
        return b[index and IBLOCK_MASK]
    }

    /**
     * 一次性把一块灌满：整块字节一次批量读，再按小端逐字解释（与 `u32Array` 同一换算）。
     *
     * 游标用的是 `win.fork()` 出来的**这一份**：[SectionWindow.readInto] 的批量读要走实例私有的
     * `duplicate()` 游标，多线程共用同一个窗口时「置位 + 批量取」会交错，把灌出来的块内容错位；
     * fork 只拷一个缓冲游标（整段 pin 时连映射都不重建），代价落在「每块一次」上。
     */
    private fun fill(blockIndex: Int): IntArray {
        val n = blockLength(blockIndex)
        val bytes = n * 4
        val raw = ByteArray(bytes)
        win.fork().readInto(from + blockIndex * IBLOCK * 4, bytes, raw)
        val out = IntArray(n)
        var j = 0
        var i = 0
        while (i < n) {
            out[i] = (raw[j].toInt() and 0xFF) or
                ((raw[j + 1].toInt() and 0xFF) shl 8) or
                ((raw[j + 2].toInt() and 0xFF) shl 16) or
                ((raw[j + 3].toInt() and 0xFF) shl 24)
            j += 4
            i++
        }
        return out
    }

    private companion object {
        /** 块宽 4,096 项 = 16 KiB 背衬（与 [BlockRefs] 同一条）：段 3 偏移表 411 个块位。 */
        const val IBLOCK_SHIFT = 12
        const val IBLOCK = 1 shl IBLOCK_SHIFT
        const val IBLOCK_MASK = IBLOCK - 1
    }
}

/**
 * 段 3 的**惰性语句池**（M10-MEM：函数体/语句记录按需解码）。
 *
 * ## 它换掉了什么
 *
 * 改造前 `decodeProgram` 把段 3 的全部记录一次解进堆：全量产物 1,716,637 条语句
 * （perf-report §2：外壳 62.6 MiB + args 子树 + 原文 131.0 MiB），装载完就常驻，
 * 真机 512 MiB 挡位在解码中途 OOM。现在装载只付三样：
 *
 * 1. **记录偏移表** `IntArray(recordCount)`（4 B/条 ≈ 6.7 MiB）—— 由 [RecordSource.buildRecordOffsets]
 *    的索引趟产出，那一趟按原顺序把每条记录解出来**当场丢弃**，所以畸形产物仍在
 *    装载期、仍在同一个字节位置抛出同一句话；
 * 2. **条目缓存**（MEM7 刀 2 起是分块表，只有被物化到的块才占槽 ≈ 6.7 MiB → 装载后 1.7 KiB）
 *    —— 同一个下标恒返回同一实例，
 *    契约 C2 的身份表口径不变（数据节仍以语句实例为键）；
 * 3. 节点表本身也是**扫出来的**（M-MEM1，口径同段 2 的 M12-EXPRLAZY）：工作线程只留「每条
 *    节点记录的起始偏移」，装载期回放摸到的少数节点在收尾一并弃掉
 *    （[RecordSource.discardBuiltNodes]）；之后每条语句只补
 *    **它自己引用到的那些节点**：节点表也带一张「每条节点记录的起始偏移」索引（稀疏索引），
 *    [RecordSource.lazyEnsure] 按那个偏移重放一条节点记录，子节点没建就按后序性递归补。
 *    前缀形态（[RecordSource.buildPrefixThrough]）只留作递归超深的退路 —— 2026-10-21 实测
 *    取用 1,000 条高下标语句，前缀形态要 +138.5 MiB 常驻，稀疏索引形态只要 +0.2 MiB
 *    （`LazyStatementDecodeTest.真实产物：取用 1000 条语句只付 1000 条的内存` 钉住 ≤ 24 MiB）。
 *
 * 于是「开局常驻」与**真跑到的语句数**成正比，与语料规模无关。
 *
 * ## 为什么语义不会变
 *
 * 取值走的是**同一个** [RecordSource.readStatement]、同一个段窗口、同一条记录起始偏移，
 * 读序与改造前的顺序解码逐字段相同；`remaining` 类判界用「到文件尾」的同一口径
 * （[StreamCursor]），所以同一条记录在哪个位置解都得到同样的结果与同样的报错。
 * 规范化去重（[ArgInterner]）推迟到每条物化时按条做，表是同一张、种子同一个
 * （[seedInterning] 灌表达式池根，与 [com.eratw.port.core.code.Program.internStatementArgs]
 * 的种子段同口径）⇒ **按下标升序整池取用**（`.erbc` 再编码、对拍装置）时代表实例的
 * 选中次序与改造前逐条相同。
 *
 * ## 生命周期
 *
 * 本类持有段 3 的**整段只读映射**（[SectionWindow] 的 pinned 形态，堆内 0 常驻），
 * 与 `Program` 同寿：文件通道可以在装载返回前关掉（关通道不 unmap，M8-COD-EMAP 同一口径），
 * 但产物文件在 Program 存活期间不能替换/删除。
 *
 * TODO(verify)：真机（ART）上这份整段映射的地址空间占用与 RSS 回落时机 ——
 * 全量语料语句段 196,193,040 B ≈ 187 MiB 的地址空间窗口；64 位 APK 无压力，
 * **32 位进程**（地址空间约 3 GiB，同一条产物还要给 CODE 段 73 MiB 与串池留位）
 * 是否装得下需要组长在 32 位机型上取一次数；如果不行，退路是回到按 [WINDOW_BYTES]
 * 换块的窗口（语义不变，只是每次跳下标多一次 mmap）。
 *
 * ## 波次 C：压扁即弃（T2-64）
 *
 * 波次 B 在 proc 桥构建期把整池物化一遍再压成扁平流（[FlatStatements]）：装载多付 ~7.8 s
 * 解码 + ~1.9 s 压扁，**且语句树整池常驻 ≈390 MB**（CONTRACT-T2-64 §9.5）。波次 C 把这一步
 * 前移进装载链、并换成 [buildFlatStatements] 的流式形态：逐条记录「解码 → 规范化 → 写入 →
 * 弃树」，条目**不进** [cached]，所以 `materializedCount` 装载后仍是 0、语句树不再整池驻留。
 *
 * 读面契约不变：[statementAt] 仍是「同一下标恒同一实例」（首次取用解码并缓存），
 * 因为数据节以语句实例为键（`ProcessRuntime.DataSectionSource`，契约 C2）、`Vm` 的 L1 备忘
 * 与探针实现都按实例认语句；「压扁即弃」只让**装载趟**绕过这份缓存，不改变诊断/兜底路径
 * 的语义。装载趟之后的节点引用（[RecordSource] 的节点表）反而全在，重解码一条语句不必
 * 再碰映射里的节点字节。
 *
 * ## 并发口径（CMP-56）
 *
 * `Program.statement()` 是 public：任何第二线程接进来都会与 VM 线程抢同一条惰性解码路径。
 * 取用面因此分成两层 —— 条目槽是 `AtomicReferenceArray`（锁外命中的元素读有 volatile
 * 语义），未命中才进 [materializeLock] 串行解码；共享的 `RecordSource.nodes`/ensureDepth、
 * 段 3 窗口与串表 `scratch` 全在锁内，保证「同一下标恒同一实例」且不会把两条记录的
 * 串字节混成一条。三个生产事实不变：单线程 VM 下锁接近零竞争、装载期仍然零物化、
 * 按升序整池取用时的代表实例次序与整池档逐条相同。
 */
internal class LazyStatementRecords(
    private val arena: RecordSource,
    private val window: SectionWindow,
    private val fileSize: Int,
    private val recordOffsets: RecordOffsets,
) : LazyStatementPool {
    /**
     * 条目槽（CMP-56 + MEM7 刀 2）：分块引用表，**只有被物化到的块才分配**。
     *
     * 取用面是 public（`Program.statement`），任何第二线程接进来都和 VM 线程抢同一条
     * 惰性解码路径；元素读取要具备 volatile 语义（发布）才能走锁外快路径 —— 块内元素仍是
     * `AtomicReferenceArray`，与改造前逐槽等价，只是 1,716,636 个「永远空着的槽」不再常驻。
     */
    private val cached = BlockRefs<Statement>(recordOffsets.size)

    /**
     * 物化慢路径的实例锁（CMP-56）。锁住的不只是条目表，还有三样**共享可变状态**：
     * [RecordSource.nodes]（惰性节点表 + ensureDepth）、段 3 的 [window]（`SectionWindow`
     * 的 buf/view 是实例字段）、串表的 `scratch` 复用缓冲。任一并发访问都会让同一下标
     * 解出两个实例、或把两条记录的串字节混成一条 ⇒ 必须整段串行。
     *
     * 快路径（[cached] 命中）不拿锁；生产 VM 单线程时锁只在首次物化时短暂竞争。
     */
    private val materializeLock = Any()

    private val interner = ArgInterner()

    /** 原文是否已被释放（`Program.releaseStatementRaws` 转发过来）。 */
    private var rawsReleased = false

    /** 已物化条数（诊断/perf 装置读数用；不参与任何取值）。 */
    @Volatile
    override var materializedCount: Int = 0
        private set

    override val totalCount: Int get() = recordOffsets.size

    override fun statementAt(index: Int): Statement {
        if (index < 0 || index >= recordOffsets.size) {
            throw IndexOutOfBoundsException("语句池下标越界: $index（共 ${recordOffsets.size} 条）")
        }
        cached.get(index)?.let { return it }
        synchronized(materializeLock) {
            // 双检：并发的第二个线程在锁外可能也看到 null，这里必须再查一次（CMP-56）。
            cached.get(index)?.let { return it }
            val s = arena.readStatement(StreamCursor(window, recordOffsets.get(index), fileSize))
            // 按条规范化去重：与改造前「整池一次遍历」共用同一张表，只是把每条的工作挪到物化时
            interner.run(listOf(s))
            if (rawsReleased) s.releaseRaw()
            cached.set(index, s)
            materializedCount++
            return s
        }
    }

    /**
     * T2-64 波次 C：装载期「压扁即弃」—— 逐条「解码 → 规范化去重 → 写入扁平流 → 当场弃树」。
     *
     * 与 [statementAt] 的关系：那条路把结果写进 `cached`（`Program.statement(i)` 的实例身份契约、
     * 数据节/探针要用），装载期要的恰恰相反 —— 条目**只走一趟**：1,716,636 条解完即弃，
     * 不进缓存、不驻留语句树（桥构建期压扁要先把整池拖回堆里，实测 ≈+390 MB）。
     *
     * 为什么压扁结果与 [statementAt] 逐条同：同一个 [RecordSource]、同一个 [window]、同一张
     * 记录偏移表、同一个 [StreamCursor] 游标 ⇒ 解出的对象图一样（`FlatStmtRoundTripTest` 与
     * 整池 encode 逐槽对拍钉住）；规范化去重也走同一个 [interner]，且在写入**之前**完成
     * （`ArgInterner` 就地改写参数结点，晚了会把非规范实例写进流）。
     *
     * 节点重建走**迭代**补链（[RecordSource.setLoadReplayIterative]）：装载线程的栈尺寸
     * 不受 core 控制，递归下降在 128 KiB 小栈上会 StackOverflowError（`StreamEnsureDepthTest`）。
     */
    override fun buildFlatStatementsRange(from: Int, until: Int): FlatStatements =
        synchronized(materializeLock) {
            val builder = FlatStatements.Builder(initialStatements = until - from)
            // 复用同一个元素表，避免 171.7 万次 List 分配（`interner.run` 只吃 Iterable）
            val one = ArrayList<Statement>(1)
            arena.setLoadReplayIterative(true)
            try {
                for (i in from until until) {
                    val s = arena.readStatement(StreamCursor(window, recordOffsets.get(i), fileSize))
                    if (rawsReleased) s.releaseRaw()
                    one.clear()
                    one.add(s)
                    interner.run(one)
                    builder.append(s)
                }
            } finally {
                arena.setLoadReplayIterative(false)
            }
            return builder.build()
        }

    /** 已物化的当场释放；未物化的由 [statementAt] 在解出来时立刻释放（口径同整池遍历）。 */
    override fun releaseRaws() {
        synchronized(materializeLock) {
            rawsReleased = true
            // 生产装配时惰性池通常尚未物化任何语句（materializedCount == 0）：此时只需立起
            // rawsReleased —— 之后首次 statementAt 解出条目时会在物化路径上当场 releaseRaw，
            // 无需白扫条目槽（2026-10-24 装载提速；MEM7 刀 2 后连「白扫」的那张表本身都不在）。
            // 判据与 cached[index] = s 在同一把 materializeLock 下维护，锁内看到 0 就不可能漏条目。
            if (materializedCount == 0) return
            cached.forEachActive { it.releaseRaw() }
        }
    }

    override fun seedInterning(roots: List<Expr?>): ArgInterner.Stats {
        synchronized(materializeLock) {
            // 种子行：池外临时语句，整段表达式池当作它的 args（与 Program.expressionPoolSeed 同一口径）
            val seed = InstructionStmt(FunctionCode.__NULL__, ExpressionArrayArgument(roots), -1, "")
            val stats = interner.run(listOf(seed))
            // 串池读数必须跟着交出去（`perfBaseline` 的「串池命中率」只有这一个来源）；
            // 惰性池的串表与后续逐条物化共用同一个 `interner.strings`，所以这里是累计值。
            return ArgInterner.Stats(
                0, stats.merged, stats.forms, stats.nodes, stats.ms,
                strings = interner.strings.snapshot(),
            )
        }
    }

    /**
     * 释放本池对产物文件的依赖（CMP-19；宿主 `GameSession.close()` → `Program.releaseMmap()`）。
     *
     * ## 为什么这里能关通道
     *
     * 运行期回读只有两条路，两条都必须落在**装载期就 pin 好的映射**上：
     * 1. 语句记录与表达式节点 —— 走 [window]，装载期 `mapWindow(sectionFrom, sectionEnd)`
     *    整段 pin（`ErbcStreamDecoder.decodeProgram` 的段 3 收尾）；
     * 2. 语句段的局部串表 —— 走 [RecordSource] 里那张 [StreamStringTable]，装载期
     *    `readStringTable(pinWhole = true)` 把数据块整块 pin（数据块 ≤ 128 MiB 时）。
     *
     * 第 2 条不成立（>128 MiB 的串池）时**不关通道**：宁可多留 1 fd，也不许把惰性解码
     * 变成「跑到某一条语句才炸」。这条返回 false 的读数由宿主落日志（见 `GameHost` 的
     * TODO(verify)）。
     *
     * 关通道不 unmap（JVM 语义）：已建映射继续有效，所以「回到标题」复用同一会话照常工作。
     */
    override fun releaseMmap(): Boolean {
        if (!arena.stringsChannelFree()) return false
        window.closeUnderlying()
        return true
    }
}

internal class ErbcStreamDecoder private constructor(
    private val file: MappedErbc,
    private val stages: LoadStageSink?,
    private val mark: LoadStageMark?,
    private val lazyStatements: Boolean,
    /**
     * 段 3 语句记录的**预计算偏移表**（相对记录区起点，来自 `program.stmt.idx` 侧车）。
     *
     * null = 没有侧车（旧产物）⇒ 走原来的逐条扫描。非 null 也会被校验
     * （单调、区间内、末条记录正好收在段尾），任一不成立即回落扫描，绝不静默生效。
     */
    private val stmtRecordOffsetsHint: SidecarOffsets? = null,
    /**
     * 惰性节点重建的递归上限（CMP-21 的可配旋钮）。
     *
     * 默认 [MAX_ENSURE_DEPTH]（ART 安全值）；测试/装置可以在装载时压小它，强制
     * [RecordSource.buildPrefixThrough] 的升序退路，从而在合成样本上验证「超过上限仍
     * 逐字段一致」而不必造几千层深的产物。
     */
    private val maxEnsureDepth: Int = MAX_ENSURE_DEPTH,
    /**
     * 兼容装载档（2026-10-28 群友 512 MiB 机型 OOM）：去重与校验**串行**跑，不再起
     * `erbc-intern-seed` 并行线程。代价是装载变慢（两段原本重叠，真机实测 4.4 s + 1.8 s），
     * 换来的是校验趟期间少一条线程同时分配 —— 低端机上「校验 + 去重 + 并行 meta 解析」
     * 三路叠加正是顶穿堆上限的窗口。语义与并行档逐字段一致（含异常类型/文案/位置），
     * 由宿主失败屏的「兼容模式重试」传入 true。
     */
    private val serialPhases: Boolean = false,
) {
    private val header: ErbcHeader = file.header
    private val fileSize: Int = file.byteSize

    /** 计量用的计数器；生产路径（[stages] 为 null）恒为 null，`get` 里只是一次空判。 */
    private val stats: DecodeStats? = if (stages == null) null else DecodeStats()

    companion object {
        /** 解码入口：调用方负责先 [MappedErbc.checkSectionLayout]（与 `ProgramLoader.load` 的次序一致）。 */
        fun decode(file: MappedErbc): Program = ErbcStreamDecoder(file, null, null, true).decodeProgram()

        /**
         * 带分阶段钩子的解码入口（**只给装载期分配剖析用**，语义/异常与 [decode] 逐字相同：
         * 钩子只在段边界被调用，不改变任何读取路径）。
         */
        fun decode(
            file: MappedErbc,
            stages: LoadStageSink?,
        ): Program = ErbcStreamDecoder(file, stages, null, true).decodeProgram()

        /**
         * 生产入口（M10-MEM）：除分配剖析钩子外再带一个分段 [mark] 与惰性开关。
         *
         * [lazyStatements] = false 即回到改造前的整池顺序解码 ——
         * `LazyStatementDecodeTest` 用这一档与惰性档做逐条差分，证明惰性化没改任何判定与报错；
         * 生产装载链恒为 true（宿主不需要 opting in）。
         */
        fun decode(
            file: MappedErbc,
            stages: LoadStageSink?,
            mark: LoadStageMark?,
            lazyStatements: Boolean,
        ): Program = ErbcStreamDecoder(file, stages, mark, lazyStatements).decodeProgram()

        /**
         * 生产入口 + **语句偏移表侧车**（2026-10-23 装载提速）。
         *
         * [stmtOffsets] 来自 `program.stmt.idx`（[StmtOffsetSidecar]），是相对段 3 记录区
         * 起点的偏移。给了就跳过「逐条反序列化 171 万条语句只为拿偏移」的索引趟
         * （真机实测 9.5 s → 百毫秒级）；表不可信时自动回落扫描。
         */
        fun decode(
            file: MappedErbc,
            stages: LoadStageSink?,
            mark: LoadStageMark?,
            lazyStatements: Boolean,
            stmtOffsets: SidecarOffsets?,
            maxEnsureDepth: Int = MAX_ENSURE_DEPTH,
            serialPhases: Boolean = false,
        ): Program =
            ErbcStreamDecoder(file, stages, mark, lazyStatements, stmtOffsets, maxEnsureDepth, serialPhases)
                .decodeProgram()
    }

    private fun stage(name: String) {
        mark?.onStage("program.$name")
        val sink = stages ?: return
        val s = stats!!
        sink.onStage(LoadStage(name, s.stringDecodes, s.stringBytes, s.stringHits))
    }

    /** 段内大块的 mark（索引趟/校验趟每 1/8 条带一次；不传 [mark] 时只是一次空判）。 */
    private fun markBlock(
        name: String,
        index: Int,
    ) {
        mark?.onStage("program.$name ${index.coerceAtLeast(1)}/$BLOCK_MARKS")
    }

    /**
     * 段 3 语句偏移表（`program.stmt.idx` 侧车）的可信性校验。
     *
     * CMP-01：旧判据只有「首条为 0 + 严格单调 + 落在记录区内 + **末条**收在段尾」，
     * 中段偏移整体平移 1 字节时四条全成立（末条还对齐）⇒ 按错位边界解出**一条合法但
     * 错位的语句**，`valueOf` 不失败、没有任何日志。现在分两层内容级校验：
     *
     * 1. **产物配对**：侧车头必须是 v2（带 `recordBytes` + 记录区 CRC32），
     *    `recordBytes` 等于解码出来的真实记录区长度，整段记录区 CRC32 等于头里的值
     *    ⇒ 「侧车与产物不是一对」（重编 erbc 忘重写侧车 / 只换其一）在这里被拒；
     * 2. **边界一致性抽查**：首两条、末两条 + 每个 1/8 条带的起点各回放一条记录，
     *    要求它正好收在下一条偏移（末条则正好收在段尾）⇒ 「指纹对得上、但表本身被
     *    平移/改坏」在这里被拒（指纹只覆盖产物字节、不覆盖表，这一层不能省）。
     *
     * 两条都过才返回 true；任一不过就回落逐条扫描。**残留面**：单个中段偏移被改坏、
     * 且正好不落在抽样下标上时，本轮判据发现不了（见 [recordSampleIndices]）；
     * 完整证明必须逐条回放 —— 那就是要省掉的那 9.5 s，所以按契约取「配对 + 抽查」。
     * 代价：第 1 层要顺序读一遍记录区（全量产物 66,015,227 B ≈ 63 MiB；桌面 JDK 17 页缓存
     * 已热时 CRC 实测 62–66 ms、整次加速档装载 2.35–2.51 s / 扫描档 4.06–4.39 s），
     * 第 2 层只解十几条记录；相比回落扫描（真机 9.5 s，`StmtOffsetSidecar` 类 KDoc）
     * 仍是数量级更优。真机上的绝对读数 `TODO(verify)`。
     */
    /**
     * 段 6 行号表（主线程与段 3 预取线程共用**同一份实现**，保证异常类型/文案/位置逐字一致）。
     *
     * @param instructionCount 段 5 声明的指令条数（长度必须与它相等）
     */
    /** 段 4（函数表 + 段 8 形参名）的解码结果（主线程与预取线程共用 [decodeFunctions]）。 */
    private class FunctionsBundle(
        val functions: List<FunctionInfo>,
        val entryFunction: Int,
        val globalSlots: Int,
    )

    /**
     * 段 4 函数表 + 段 8 形参名的解码（主线程与预取线程**共用这一份实现**，异常类型/文案/位置
     * 逐字一致）。预取线程失败时主线程走这里，抛出的错误与串行路径完全相同。
     */
    private fun decodeFunctions(stats: DecodeStats?): FunctionsBundle {
        if (!header.hasSection(ErbcFormat.SECTION_FUNCTIONS)) {
            throw ErbcDecodeException(0, "函数表缺失：v1 产物必须携带 SECTION_FUNCTIONS")
        }
        // M11-MEM：函数段与形参名段各整段映射一次（堆内 0 常驻、通道可关），
        // 惰性函数表按记录偏移回读；索引趟走完即只留偏移表。
        // 索引趟**不**构造 FunctionInfo，但重复名/标志位/标签计数与标签名字的读取校验全部照做
        //（见 [MappedFunctionTable.index]），所以畸形产物的报错点与整池档逐字相同。
        val funcFrom = header.offsets[ErbcFormat.SECTION_FUNCTIONS]
        val funcEnd = file.endOf(ErbcFormat.SECTION_FUNCTIONS)
        val funcPinned = file.mapWindow(funcFrom, funcEnd)
        val funcCur = StreamCursor(
            SectionWindow(file, funcFrom, funcEnd, funcPinned, funcFrom),
            funcFrom,
            fileSize,
        )
        // 每条函数记录至少 24 字节（name/entryPc/arity/slotCount/flags/labelCount 各 4）。
        val funcCount = funcCur.count("函数个数", 24)
        val entryFunction = funcCur.u32()
        val globalSlots = funcCur.u32()
        if (globalSlots < 0) throw ErbcDecodeException(funcCur.pos - 4, "全局槽数为负: $globalSlots")
        // ---- 段 8：函数头部形参名（v2；缺段 ⇒ 空表 = 声明「不带」，绑定退化为类型桶）----
        val paramNames = if (header.hasSection(ErbcFormat.SECTION_PARAM_NAMES)) {
            val pnFrom = header.offsets[ErbcFormat.SECTION_PARAM_NAMES]
            val pnEnd = file.endOf(ErbcFormat.SECTION_PARAM_NAMES)
            val pnPinned = file.mapWindow(pnFrom, pnEnd)
            readParamNameTable(
                StreamCursor(
                    SectionWindow(file, pnFrom, pnEnd, pnPinned, pnFrom),
                    pnFrom,
                    fileSize,
                ),
                funcCount,
                stats,
            )
        } else {
            null
        }
        // 函数名表关掉备忘：索引趟为查重会把每个名字解一遍，备忘会把 110,072 个名字
        // 全钉住，惰性化就白做了（真正的取值只发生在被调到的函数上）。
        val funcStrings = readStringTable(funcCur, stats, memoize = false)
        val functions: List<FunctionInfo> = MappedFunctionTable.index(
            funcCur,
            funcCount,
            funcStrings,
            paramNames,
            fileSize,
        )
        return FunctionsBundle(functions, entryFunction, globalSlots)
    }
    /**
     * 段 6 → 行号表。MEM9 刀 2：**稀疏分块**，装载后堆上只剩块位表。
     *
     * 改造前这里逐条 `i32` 压实成一张 `count × 2 B` 的 `ShortArray` 并**永久驻留**
     * （mem7 探针 §1：4,888,249 条指令 ⇒ **9.3 MiB，装载即付、之后永不释放**），
     * 而它的唯一读者是报错定位与 marker 指令的行号（`Vm.kt:1927`）—— 标题页一条都不读。
     *
     * 现在：
     * 1. 段 6 整段 pin 一份只读映射（堆外地址空间 19.5 MiB，形态同段 3/段 4；P5 单列一行）；
     * 2. **仍按旧顺序逐条 `cur.i32()` 读一遍**（[LineTable.ofPaged] 的 `next`）：
     *    溢出表（行号 > 65533 / < -1）要在这一趟建，畸形产物的报错位置与文案也才能逐字不变；
     *    只是这一趟的落点不再是堆内整表，而是「读完即弃」；
     * 3. 第 i 块（4,096 条）第一次被取到时由 [MappedLineBlocks] 从那份 pin 里批量回读原始 i32，
     *    走 [LineTable] 里**同一条**换算压实（`codeOf`）⇒ 取值与整表形态逐位相同。
     *
     * **不许静默把「越界读」吞成 0**：分块形态没有那张天然抛 AIOOBE 的大数组，
     * 越界面全部由 `LineTable.lineOf` 首行的显式判界承担（回落 -1，与旧 `getOrElse` 同口径）；
     * 而「能不能分块」的判据是 [SectionWindow.pinnedCovers] —— 声明的行号区越过本段末
     * （畸形、但旧实现的 `count` 判界放得过去，因为它是按**文件剩余字节**判的）时
     * 整段 pin 覆盖不到，此时**退回整表压实**（与改造前逐字同形态），绝不把取用面接到
     * 一份会换块的窗口上（那等于把 mmap 塞进取指路径，见 [BlockU32s] 的同一判据）。
     * 这条退路有正面钉：`ProgramMmapReleaseTest.段6 分块形态：正常产物走分块、覆盖不到才退回整表`。
     */
    private fun decodeLineTable(instructionCount: Int): LineTable {
        if (!header.hasSection(ErbcFormat.SECTION_LINES)) {
            // -1 = 未知（Program.kt:117 的既有口径）
            return LineTable.ofStreaming(instructionCount) { -1 }
        }
        val linesFrom = header.offsets[ErbcFormat.SECTION_LINES]
        val linesEnd = file.endOf(ErbcFormat.SECTION_LINES)
        val linesPinned = file.mapWindow(linesFrom, linesEnd)
        val cur = StreamCursor(
            SectionWindow(file, linesFrom, linesEnd, linesPinned, linesFrom),
            linesFrom,
            fileSize,
        )
        val fileCount = cur.count("源文件表长度")
        if (fileCount != 0) {
            throw ErbcDecodeException(
                cur.pos - 4,
                "行号表带源文件 id($fileCount)，但 Program.lines 只有行号（规约 §5.7 / §9）",
            )
        }
        val n = cur.count("行号表长度", 4)
        if (n != instructionCount) {
            throw ErbcDecodeException(cur.pos - 4, "行号表长度($n)与指令条数($instructionCount)不符")
        }
        val dataFrom = cur.pos
        val win = cur.window
        if (!win.pinnedCovers(dataFrom, n)) {
            return LineTable.ofStreaming(n) { cur.i32() }
        }
        return LineTable.ofPaged(n, { cur.i32() }, MappedLineBlocks(win.fork(), dataFrom))
    }

    /**
     * 段 6 的块供给（MEM9 刀 2）：第 i 块的原始 i32 从**整段 pin** 里一次批量读
     * （[SectionWindow.readInto] + 手写小端循环，与 [BlockU32s.fill] 同一招、同一换算）。
     *
     * 每次灌块自带一份 [SectionWindow.fork] 的游标：`readInto` 的批量读走实例私有的
     * `duplicate()` 游标，两条线程共用一份游标时「置位 + 批量取」会交错，灌出来的块内容错位
     * （[BlockU32s] 的同一告警，2026-10-27 踩过）；fork 只拷一个游标，共享的只有那份只读映射，
     * 于是并发首次灌同一块时两边算出的内容逐字节相同，谁胜出都不影响取值。
     */
    private class MappedLineBlocks(
        private val win: SectionWindow,
        private val dataFrom: Int,
    ) : LineBlockSource {
        override fun read(blockIndex: Int, out: IntArray, length: Int) {
            val bytes = length * 4
            val raw = ByteArray(bytes)
            win.fork().readInto(dataFrom + blockIndex * LineTable.BLOCK * 4, bytes, raw)
            var j = 0
            var i = 0
            while (i < length) {
                out[i] = (raw[j].toInt() and 0xFF) or
                    ((raw[j + 1].toInt() and 0xFF) shl 8) or
                    ((raw[j + 2].toInt() and 0xFF) shl 16) or
                    ((raw[j + 3].toInt() and 0xFF) shl 24)
                j += 4
                i++
            }
        }
    }


    /**
     * 段 7 调用点实参表（缺段 = 声明「不带」⇒ 空表）。同上：两处共用一份实现。
     *
     * MEM9 刀 1：返回的是 [CallArgIndex]（两张平行数组），不再是 `LinkedHashMap` ——
     * 空段时给 [CallArgIndex.EMPTY]（与 `emptyMap()` 内容相等，`Program.callArgs`
     * 的 `isEmpty()` 读数不变）。
     */
    private fun decodeCallArgsTable(instructionCount: Int, stats: DecodeStats?): CallArgIndex =
        if (header.hasSection(ErbcFormat.SECTION_CALL_ARGS)) {
            readCallArgs(cursorAt(ErbcFormat.SECTION_CALL_ARGS), instructionCount, stats)
        } else {
            CallArgIndex.EMPTY
        }

    private fun validateRecordOffsets(
        offsets: SidecarOffsets,
        regionBytes: Int,
        recordRegionFrom: Int,
        sectionEnd: Int,
        arena: RecordSource,
    ): Boolean {
        val header = StmtOffsetSidecar.readHeader(file.path) ?: return false
        // v1 侧车没有产物配对凭证（CMP-01 的真身）⇒ 一律不可信
        if (header.version < StmtOffsetSidecar.VERSION_PAIRED) return false
        if (header.stmtCount != offsets.count) return false
        // 写侧对记录区长度的自述必须等于解码出来的真实长度（A4：真值+1 也必被拒）
        if (header.recordBytes != regionBytes) return false
        if (offsets.count == 0 || offsets.rel(0) != 0) return false
        var prev = 0
        for (i in 1 until offsets.count) {
            val v = offsets.rel(i)
            if (v <= prev || v >= regionBytes) return false
            prev = v
        }
        val lastPos = recordRegionFrom + offsets.rel(offsets.count - 1)
        if (lastPos >= sectionEnd) return false
        // 内容级判据①：整段记录区的 CRC32 与侧车头里的值必须一致
        val crc = try {
            StmtOffsetSidecar.regionCrc32(file, recordRegionFrom, sectionEnd)
        } catch (_: Exception) {
            return false
        }
        if (crc != header.recordCrc32) return false
        // 内容级判据②：确定性抽查回放（边界必须严格落在下一条偏移/段尾上）
        return try {
            for (i in recordSampleIndices(offsets.count)) {
                val pos = recordRegionFrom + offsets.rel(i)
                if (pos < recordRegionFrom || pos >= sectionEnd) return false
                val cur = StreamCursor(SectionWindow(file, pos, sectionEnd), pos, fileSize)
                arena.readStatement(cur)
                val expectedEnd = if (i + 1 < offsets.count) {
                    recordRegionFrom + offsets.rel(i + 1)
                } else {
                    sectionEnd
                }
                if (cur.pos != expectedEnd) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 抽查回放的样本下标（确定性，不用随机源）：首两条、末两条 + 每个 1/8 条带的起点。
     *
     * 小池（如单测的 8 条）会自动覆盖到全部下标；全量产物（171 万条）解 12 条，
     * 成本可忽略。抽样的残留面见 [validateRecordOffsets] 的 KDoc：单条中段偏移被
     * 改坏且正好落在未抽到的下标上时，本轮判据发现不了（完整证明要逐条回放，
     * 那就是要省掉的那 9.5 s）。
     */
    private fun recordSampleIndices(count: Int): IntArray {
        if (count <= 0) return IntArray(0)
        val picked = LinkedHashSet<Int>(4 + BLOCK_MARKS)
        picked.add(0)
        if (count > 1) picked.add(1)
        if (count > 2) picked.add(count - 2)
        picked.add(count - 1)
        for (k in 1..BLOCK_MARKS) {
            val i = (count.toLong() * k / BLOCK_MARKS).toInt().coerceIn(0, count - 1)
            picked.add(i)
        }
        return picked.toIntArray()
    }

    /** 段游标：段不存在时的消息与 `ErbcReader.cursorAt` 逐字一致。 */
    private fun cursorAt(section: Int): StreamCursor {
        val off = header.offsets[section]
        if (off == 0) throw ErbcDecodeException(0, "段 ${ErbcFormat.SECTION_NAMES[section]} 不存在")
        return StreamCursor(SectionWindow(file, off, file.endOf(section)), off, fileSize)
    }

    // ------------------------------------------------------------------
    // 以下到文件尾是 `core/code/ErbcReader.kt` 的逐字副本（类型名替换见类 KDoc）。
    // 副本顺序：decodeProgram → validate/requirePool → RecordSource。
    // 缩进保持上游原样（那三个成员在 Decoder 里就是 8 空格），这样对上游做逐行 diff 时零噪声。
    // ------------------------------------------------------------------

        fun decodeProgram(): Program {
            val flags = header.flags
            val offsets = header.offsets

            // ---- 段 0：字符串池（index 即下标）------------------------------------
            val strings: List<String> = if (offsets[ErbcFormat.SECTION_STRINGS] == 0) {
                emptyList()
            } else {
                // 全局串池是**顺序读一遍**（每项恰好取一次）：备忘表只会白占 count 个引用槽，
                // 命中率恒 0（M11-MEM 的零风险清理）。
                val view = readStringTable(cursorAt(ErbcFormat.SECTION_STRINGS), stats, memoize = false)
                ArrayList<String>(view.count).apply { for (i in 0 until view.count) add(view[i]) }
            }
            stage(LoadStages.STRINGS)

            // ---- 段 1：常量池（i64；f64 位 v1 恒空）------------------------------
            val longs = if (offsets[ErbcFormat.SECTION_CONSTANTS] == 0) {
                LongArray(0)
            } else {
                val cur = cursorAt(ErbcFormat.SECTION_CONSTANTS)
                // 每项是 i64：先按 8 字节/项卡分配上界（与 ErbcReader 同口径）。
                val n = cur.count("常量池长度", 8)
                val doubleCount = cur.count("f64 常量数")
                if (doubleCount != 0) {
                    throw ErbcDecodeException(
                        cur.pos,
                        "f64 常量池未定稿（ConstantPool 只有 Long 池，规约 §5.2 / §9）：文件声明 $doubleCount 个",
                    )
                }
                LongArray(n) { cur.i64() }
            }
            stage(LoadStages.CONSTANTS)

            // ---- 段 3 的「局部串池 + 节点表」先甩到工作线程（与段 2 并行）----------
            //
            // 2026-10-23 装载提速：段 2 与段 3 各自 `cursorAt` + 各自的 `RecordSource`，读的是
            // 不同段、互不依赖；两者的节点表扫描真机上各要 3.2 s / 2.7 s，串行做等于白等一段。
            //
            // 线程安全边界（三处都必须守住）：
            // ① 映射读取每段一个独立 `SectionWindow`（各自 `channel.map`），没有共享游标；
            // ② 统计口径用**线程私有**的 [DecodeStats]，join 后并回主实例 —— 那几个计数器是
            //    非原子的 `++`，两边同时写会丢数；
            // ③ 段 3 的 stage 埋点留到 join 之后由主线程发（见下方），绝不从两条线程同时回调宿主。
            var stmt3Arena: RecordSource? = null
            var stmt3Cur: StreamCursor? = null
            var stmt3NodeOffsets: NodeOffsets? = null
            var stmt3Counts: IntArray? = null
            var stmt3Failure: Throwable? = null
            // 段 4/6/7 预取（独立线程算，主线程 join 后取用；null = 没预取成功，主线程自己解）
            var prefetchedFunctions: FunctionsBundle? = null
            var prefetchFailure: Throwable? = null
            var stmt6Lines: LineTable? = null
            var stmt7CallArgs: Map<Int, List<CallArg>>? = null
            var stmt6Count: Int = -1
            val prefetchStats = DecodeStats()

            val stmt3Stats = DecodeStats()
            val stmt3Thread: Thread? = if (!header.hasSection(ErbcFormat.SECTION_STATEMENTS)) {
                null
            } else {
                Thread(object : Runnable {
                    override fun run() {
                        try {
                            val cur = cursorAt(ErbcFormat.SECTION_STATEMENTS)
                            // 节点记录最短 10 字节；语句记录最短 15 字节（INSTRUCTION + VoidArgument）。
                            val nodeCount = cur.count("语句段节点数", 10)
                            val stmtCount = cur.count("语句池长度", 15)
                            // 惰性档关掉串池备忘：备忘表会把整池原文（131.0 MiB）钉住。
                            // pinWhole（CMP-19）：惰性档要在这场装载里把数据块整块映射好，
                            // 关掉产物通道之后按需解码才不用再 map。
                            val strings3 = readStringTable(
                                cur,
                                stmt3Stats,
                                memoize = !lazyStatements,
                                pinWhole = lazyStatements,
                            )
                            val arena = RecordSource(strings3, nodeCount, maxEnsureDepth)
                            // 段 3 节点表（M-MEM1，与段 2 的边界扫描同型）：惰性档装载期**一个 Expr 都不建**。
                            // 真机装载 trace 里最大的一坨就是这里（`program.段3 局部串池 dalloc=262`，512 MiB
                            // 挡位当场 OOM）：旧写法把 827,992 个节点先整表建进 `nodes[]`、等主线程
                            // `enableLazyNodes` 再清空 —— 引用被表钉着，GC 只能等它们**全部**长住之后才扔，
                            // 峰值正好落在装载中途。扫描趟只留一张 IntArray，节点等首次访问时按偏移重放
                            // （[RecordSource.enableLazyNodes] 的既有路径，段 2 已按同一口径上线）。
                            // 整池对照档仍走 [RecordSource.readNodesIndexed]：那一档的记录**当场**要用建好的节点表。
                            val nodeOffsets = if (lazyStatements) {
                                scanStmtNodeOffsets(cur, strings3, nodeCount, stmtCount)
                            } else {
                                // 整池对照档：索引趟顺手产出的稠密表压成同一形态（节点与记录
                                // 当场要用，压缩只为让两条档的字段类型一致）。
                                NodeOffsets.of(arena.readNodesIndexed(cur))
                            }
                            stmt3Arena = arena
                            stmt3Cur = cur
                            stmt3NodeOffsets = nodeOffsets
                            stmt3Counts = intArrayOf(nodeCount, stmtCount)

                        } catch (t: Throwable) {
                            stmt3Failure = t
                        }
                    }
                }, "erbc-stmt3").apply {
                    isDaemon = true
                    start()
                }
            }

            // 段 4/6/7 的**独立预取线程**（2026-10-24）：主线程段 2 解码要 3.4 s，是装载的主长杆；
            // 段 4（1.4 s）+ 段 6（0.65 s）+ 段 7（1.0 s）与段 2/3/5 互不依赖，整段塞进这个窗口。
            // 之前把段 6/7 挂在段 3 线程上反而没赚：段 3 线程被撑到 4.65 s > 主线程 3.4 s，
            // 主线程在 join 处多等 —— 所以必须**单独一条**线程，且线程内跑满但不超主线程窗口。
            // 异常只记录不抛：错误口径与行号位置归主线程（join 处原样抛出，与串行逐字相同）。
            val prefetchThread = Thread(object : Runnable {
                override fun run() {
                    try {
                        val codeCount = if (header.hasSection(ErbcFormat.SECTION_CODE)) {
                            cursorAt(ErbcFormat.SECTION_CODE).count("指令条数")
                        } else {
                            -1
                        }
                        if (codeCount > 0) {
                            stmt6Lines = decodeLineTable(codeCount)
                            stmt7CallArgs = decodeCallArgsTable(codeCount, prefetchStats)
                            stmt6Count = codeCount
                        }
                        prefetchedFunctions = decodeFunctions(prefetchStats)
                    } catch (t: Throwable) {
                        prefetchFailure = t
                    }
                }
            }, "erbc-prefetch").apply {
                isDaemon = true
                start()
            }
            // ---- 段 2：表达式池（节点表 + 根下标）--------------------------------
            //
            // 失败路径的线程收口（CMP-11）：段 3 的工作线程此刻还活着，而调用方一拿到异常
            // 就 `MappedErbc.close()`。异常绝不许带着活线程离开本方法 —— 见下方 catch。
            var lazyExprSource: LazyExprSource? = null
            val exprRoots: List<Expr> = if (!header.hasSection(ErbcFormat.SECTION_EXPRESSIONS)) {
                emptyList()
            } else {
                try {
                    val cur = cursorAt(ErbcFormat.SECTION_EXPRESSIONS)
                    // 节点记录最短 10 字节（NULL_TERM）；池根每项一个 u32。
                    val nodeCount = cur.count("表达式节点数", 10)
                    val poolCount = cur.count("表达式池长度", 4)
                    // 流池整块 pin（M12-EXPRLAZY：之后按偏移重放不能再换块）。
                    val strings2 = readStringTable(cur, stats, pinWhole = true)
                    stage(LoadStages.EXPR_STRINGS)
                    // ---- 不建对象的边界扫描 ----
                    // 真机实测（2026-10-24）：整池 `readNodes` 在 898,118 个结点上要 5045 ms，
                    // 其中 **2703 ms 是 9 次全量 GC**、分配 351 MiB。而这些结点里约 44%
                    // 是同形重复实例，且运行期真正被 `_EVAL_*` 取到的池根只占少数。
                    // 因此这里只记「每条记录的起始偏移」（不建 Expr、不解 String），
                    // 结点等 `expr(k)` 首次访问时用 [RecordSource.enableLazyNodes] 的同一套重放。
                    val sectionFrom = header.offsets[ErbcFormat.SECTION_EXPRESSIONS]
                    val sectionEnd = file.endOf(ErbcFormat.SECTION_EXPRESSIONS)
                    // 自检：正确的边界扫描必然停在「池根表起点」（= 段尾 - poolCount*4）。
                    // 对不上就说明扫描器与记录格式脱节，当场报出错位点而不是拿一张错表去重放。
                    val rootsStart = sectionEnd - poolCount * 4
                    // 扫描与按需重放共用一张整段 pin 窗口：一次 mapWindow 铺满整个表达式段
                    // （24.4 MB < 128 MB 上限），之后所有读都是 buf.get(pos - sectionFrom)，不再发生换块重映射。
                    val pinned = file.mapWindow(sectionFrom, sectionEnd)
                    val win = SectionWindow(file, sectionFrom, sectionEnd, pinned, sectionFrom)
                    // MEM7 刀 C：这张整段 pin 也铺得住段 2 局部串池的偏移表 ⇒ 就地改映射页直取。
                    strings2.useMappedOffsets(win)
                    val nodeOffsets = scanNodeOffsets(
                        StreamCursor(win, cur.pos, fileSize),
                        strings2,
                        nodeCount,
                        rootsStart,
                    )
                    stage(LoadStages.EXPR_NODES)
                    // 池根下标表紧跟在节点表之后（每项一个 u32）。
                    // 装载期就把池根越界判掉（O(poolCount)，真机 ~2 ms）：
                    // 惰性档不建节点，但「池根指向不存在的节点」是产物坏掉的硬判据，
                    // 不该拖到运行期才爆。
                    // 注意：扫描走的是**另一个**游标（上面新建的），`cur` 仍停在节点表起点；
                    // 池根表必须从算好的 `rootsStart` 读（扫描自检已保证它就是正确边界）。
                    val roots = StreamCursor(win, rootsStart, fileSize).u32Array(poolCount)
                    for (k in roots.indices) {
                        val r = roots[k]
                        if (r < 0 || r >= nodeCount) {
                            throw ErbcDecodeException(0, "表达式根下标越界: $r（节点表长 $nodeCount）")
                        }
                    }
                    val arena = RecordSource(strings2, nodeCount, maxEnsureDepth)
                    arena.enableLazyNodes(win, nodeOffsets, fileSize)
                    // MEM7 刀 D：上面那张池根表只服务装载期的逐条判界，**不交给运行期** ——
                    // 它本来就是产物里 `[rootsStart, rootsStart + 4×poolCount)` 的一段连续 u32，
                    // 而这段已经在 [win] 的整段 pin 里 ⇒ `expr(k)` 按绝对偏移直取
                    // （328,509 × 4 = 1.3 MiB 常驻 → 0）。
                    lazyExprSource = MappedExprSource(arena, win, rootsStart, poolCount, file.path.toString())
                    emptyList()
                } catch (t: Throwable) {
                    // 收口口径（顺序不可换）：
                    // 1. interrupt 只是协作式信号 —— 段 3 的索引趟是纯映射读（`SectionWindow.readInto`），
                    //    不会因中断醒来，但给了将来任何可中断点一个统一的入口；
                    // 2. join **必须无超时**：索引趟是有限步（固定条数的串池 + 节点表循环），
                    //    必然返回；带超时返回后线程仍在读同一份映射，而 close 就在下一帧，
                    //    那就是 CMP-11 的原始缺陷（只是把遗弃推迟了）。宁可让失败屏多等一趟索引；
                    // 3. `stmt3Failure` 不许丢：段 2 与段 3 同时报错时，段 3 的异常挂成 suppressed
                    //    （失败屏仍报段 2 的主异常，但栈里能查到段 3 的那条）。
                    val stmt3 = stmt3Thread
                    if (stmt3 != null) {
                        stmt3.interrupt()
                        stmt3.join()
                    }
                    stmt3Failure?.let { t.addSuppressed(it) }
                    throw t
                }
            }
            stage(LoadStages.EXPR_ROOTS)

            // ---- 段 3：语句池（身份表：一条记录 = 一个实例，严禁合并）--------------
            //
            // 两条形态（M10-MEM；M-MEM1 起惰性档连节点表也不建）：
            //  - 惰性（生产）：节点表只走无分配边界扫描，记录侧只建「记录起始偏移表」，
            //    条目与节点都等第一次被取用才解；
            //  - 整池（对照档）：改造前的 `repeat(stmtCount) { arena.readStatement(cur) }`，
            //    节点表当场用 [RecordSource.readNodesIndexed] 建好。
            // 两者共用同一个 [RecordSource]、同一个游标、同一个读序，异常与消息逐字相同；
            // `LazyStatementDecodeTest` 拿同一批字节（含变异畸形件）在两条档之间做差分。
            // 段 3 的工作线程在段 2 解码期间已经跑完/在跑：这里 join 收口。
            stmt3Thread?.join()
            stmt3Failure?.let { throw it }
            // 线程私有统计并回主口径（两边不同时写 ⇒ 合并等价于串行累加）。
            stats?.let { s ->
                s.stringDecodes += stmt3Stats.stringDecodes
                s.stringBytes += stmt3Stats.stringBytes
                s.stringHits += stmt3Stats.stringHits
            }
            stage(LoadStages.STMT_STRINGS)
            var lazyPool: LazyStatementPool? = null
            val stmts: List<Statement> = if (!header.hasSection(ErbcFormat.SECTION_STATEMENTS)) {
                emptyList()
            } else {
                val cur = stmt3Cur!!
                val arena = stmt3Arena!!
                val nodeOffsets = stmt3NodeOffsets!!
                val stmtCount = stmt3Counts!![1]
                stage(LoadStages.STMT_NODES)
                if (lazyStatements) {
                    val sectionFrom = header.offsets[ErbcFormat.SECTION_STATEMENTS]
                    val sectionEnd = file.endOf(ErbcFormat.SECTION_STATEMENTS)
                    val recordRegionFrom = cur.pos
                    val regionBytes = sectionEnd - recordRegionFrom
                    // 整段一次映射（堆内 0、通道可关）先立起来，随即开启惰性节点重放：
                    // 节点表来自工作线程的边界扫描（M-MEM1），**表是空的** —— 下面两条回放路
                    // 的节点都由 [RecordSource.lazyEnsure] 按偏移现补：侧车档只补抽查 12 条的
                    // 子树，回落档才会扫到全部可达节点（终态在收尾 [RecordSource.discardBuiltNodes] 归零）。
                    val pinned = file.mapWindow(sectionFrom, sectionEnd)
                    val win = SectionWindow(file, recordRegionFrom, sectionEnd, pinned, sectionFrom)
                    arena.enableLazyNodes(win, nodeOffsets, fileSize)
                    // MEM7 刀 C：整段 pin 已经铺好 ⇒ 段 3 局部串池那张 1,680,720 项的偏移表
                    // 就地改接映射页的稀疏分块（6.4 MiB 常驻 → 0），取值路径与数据块同源。
                    arena.mapStringOffsetsFrom(win)
                    val hinted = stmtRecordOffsetsHint
                    // 侧车放行判据（CMP-01/CMP-03）：v2 头配对（recordBytes + 记录区 CRC32）
                    // + 单调/首条为 0/末条收在段尾 + 首尾与 1/8 条带起点的回放抽查。
                    // 任一不成立 ⇒ 回落逐条扫描（错表绝不静默生效），并打「不可信」分段标记。
                    val trusted = hinted != null && hinted.count == stmtCount && stmtCount > 0 &&
                        validateRecordOffsets(hinted, regionBytes, recordRegionFrom, sectionEnd, arena)
                    val recordOffsets = if (trusted) {
                        // 打断点标记照发（8 次几乎零成本），logcat 的分段口径保持不变。
                        for (k in 1..BLOCK_MARKS) markBlock(LoadStages.STMT_RECORDS, k)
                        // M13-MEM10 刀 E：不再把 171 万条偏移拷成 IntArray（6.5 MiB 堆），
                        // 直接持侧车映射 + 记录区起点，取用时一次 4 B 映射读。
                        hinted!!.absolute(recordRegionFrom)
                    } else {
                        if (hinted != null) {
                            mark?.onStage("program.段3 语句偏移表不可信（回落扫描）")
                        }
                        // 回落逐条扫描在**调用线程**上迭代补链（M-MEM1）：装载线程的栈尺寸不由
                        // core 决定，守卫递归在小栈上会先于上限打穿（判据见 loadReplayIterative
                        // 字段 KDoc）。这一档终态会把全部可达节点物化 —— 与改造前 eager 整表同
                        // 形状、不新增峰值；稠密前缀扫描（buildPrefixThrough）把它保持在 O(N)，
                        // 装载收尾统一弃掉。
                        arena.setLoadReplayIterative(true)
                        try {
                            RecordOffsets.heap(
                                arena.buildRecordOffsets(cur, stmtCount) { index, _, _ ->
                                    markBlock(LoadStages.STMT_RECORDS, index)
                                },
                            )
                        } finally {
                            arena.setLoadReplayIterative(false)
                        }
                    }
                    // 装载返回前把回放摸过的节点（抽查 12 条 / 回落扫描的全部可达节点）
                    // 从 `nodes[]` 弃掉 —— 终态与改造前「先建全表、enableLazyNodes 清空」一致：
                    // 之后 child() 按节点自己的偏移重放、statementAt() 按记录偏移重放。
                    arena.discardBuiltNodes()
                    lazyPool = LazyStatementRecords(arena, win, fileSize, recordOffsets)
                    emptyList()
                } else {
                    ArrayList<Statement>(stmtCount).apply {
                        repeat(stmtCount) { add(arena.readStatement(cur)) }
                    }
                }
            }
            stage(LoadStages.STMT_RECORDS)

            // ---- 段 4：函数表 ----------------------------------------------------
            // 预取线程（[prefetchThread]）已经把段 4/6/7 算好了：这里 join 收口，异常在主线程抛出
            // （错误口径与串行路径逐字相同）；预取失败/没起线程则用**同一实现**在本线程再解一遍。
            prefetchThread?.join()
            prefetchFailure?.let { throw it }
            stats?.let { s ->
                s.stringDecodes += prefetchStats.stringDecodes
                s.stringBytes += prefetchStats.stringBytes
                s.stringHits += prefetchStats.stringHits
            }
            // 段 4 的**内容**在 [decodeFunctions] 里（惰性函数表，M11-MEM）；预取线程只负责把它
            // 在后台算出来，主线程这里 join 取结果 —— 两条设计不冲突：预取解决「什么时候算」，
            // 惰性表解决「算完常驻多少」。
            val funcsBundle = prefetchedFunctions ?: decodeFunctions(stats)
            val functions = funcsBundle.functions
            val entryFunction = funcsBundle.entryFunction
            val globalSlots = funcsBundle.globalSlots
            stage(LoadStages.FUNCTIONS)


            // ---- 段 5：指令流 ----------------------------------------------------
            if (!header.hasSection(ErbcFormat.SECTION_CODE)) {
                throw ErbcDecodeException(0, "指令流缺失：v1 产物必须携带 SECTION_CODE")
            }
            val codeCur = cursorAt(ErbcFormat.SECTION_CODE)
            val instructionCount = codeCur.count("指令条数")
            if (instructionCount == 0) throw ErbcDecodeException(codeCur.pos - 4, "空程序：指令条数为 0")
            if (instructionCount > codeCur.remaining / Instruction.WORDS) {
                throw ErbcDecodeException(
                    codeCur.pos - 4,
                    "指令条数($instructionCount)超出本段可用字节(${codeCur.remaining})，文件被截断",
                )
            }
            // 不装配 IntArray（M8-COD-EMAP）：段首 u32 之后是 4 字节小端字流，
            // 把 [off + 4, off + 4 + 16n) 的只读窗口交给 Program（VM 按页取指，堆内 0 常驻）。
            // 段长判定与 readCode 同源（同一句报错），此处不会再放宽/收紧任何边界。
            val codeSource = file.codeSource(instructionCount)
            stage(LoadStages.CODE)

            // ---- 段 6：行号表 --------------------------------------------------
            // 预取命中（段 3 线程已经算好且指令数一致）就直接取，否则本线程按同一实现再解。
            val prefetched = stmt6Count == instructionCount
            val lineTable = if (prefetched && stmt6Lines != null) stmt6Lines!! else decodeLineTable(instructionCount)
            stage(LoadStages.LINES)

            // ---- 段 7：调用点实参表（v2；缺段 ⇒ 空表 = 声明「不带」）----------------
            val callArgs = if (prefetched && stmt7CallArgs != null) {
                stmt7CallArgs!!
            } else {
                decodeCallArgsTable(instructionCount, stats)
            }

            stage(LoadStages.CALL_ARGS)

            // ---- 池重灌 + 下标保真校验 ----------------------------------------
            val pool = ConstantPool()
            for (s in strings) pool.addString(s)
            for (l in longs) pool.addLong(l)
            val lazyExprs = lazyExprSource
            if (lazyExprs != null) {
                // 惰性档：整池不物化，直接把「偏移 + 池根」交给常量池。
                pool.attachLazyExpressions(lazyExprs)
            } else {
                for (e in exprRoots) pool.addExpr(e)
            }
            val lazyStmt = lazyPool
            if (lazyStmt != null) {
                // 惰性池不重灌：条目由索引表按下标供给（addStatement 在惰性池上是硬禁的）
                pool.attachLazyStatementPool(lazyStmt)
            } else {
                for (s in stmts) pool.addStatement(s)
            }
            if (pool.stringCount != strings.size) {
                throw ErbcDecodeException(0, "字符串池重灌后下标漂移（${strings.size} → ${pool.stringCount}）")
            }
            if (pool.longCount != longs.size) {
                throw ErbcDecodeException(0, "常量池重灌后下标漂移（${longs.size} → ${pool.longCount}）")
            }
            val expectedExprCount = lazyExprs?.nodeCount ?: exprRoots.size
            if (pool.exprCount != expectedExprCount) {
                throw ErbcDecodeException(
                    0,
                    "表达式池重灌后下标漂移（$expectedExprCount → ${pool.exprCount}）：文件里有结构相等的重复表达式，" +
                        "v1 的池下标契约要求它们本就同一条目",
                )
            }
            // 惰性档的这条判定恒真（表长就是段头声明的 stmtCount，索引趟逐条解到过），
            // 保留给整池档 —— 两条档共用同一段代码，不另立判据。
            if (lazyStmt == null && pool.statementCount != stmts.size) {
                throw ErbcDecodeException(0, "语句池重灌后下标漂移（${stmts.size} → ${pool.statementCount}）")
            }
            stage(LoadStages.POOL_REFILL)

            val program = Program(
                codeSource = codeSource,
                lineTable = lineTable,
                pool = pool,
                functions = functions,
                entryFunction = entryFunction,
                globalSlots = globalSlots,
                callArgs = callArgs,
                callArgSource = if (header.version >= ErbcFormat.VERSION_2) {
                    CallArgSource.ERBC_V2
                } else {
                    CallArgSource.ERBC_V1
                },
            )
            // ---- 语句实参规范化去重与引用范围校验**重叠**（2026-10-24 装载提速）----
            //
            // 两段互不相交，可以整段并行：
            // - 去重（seedInterning → ArgInterner 的种子段）只读表达式池结点 + 自己新建的
            //   规范表/私有串池，写的是 `LazyStatementRecords.interner`（只有它自己用）；
            // - 校验只读 CODE 段的映射页与函数记录，从不碰语句池（`statementCount` 只读
            //   段头声明的条数）⇒ 不会去抢 `materializeLock`。
            // 于是把去重挪到这条线程，主线程继续跑校验，join 之后才发 INTERN mark。
            // 收益：真机实测这两段原本串行 4.4 s + 1.8 s，重叠后只剩较长的那一段。
            //
            // 异常收口与 Bootstrap 前几段同口径：去重线程只记录不抛，主线程 join 完再原样
            // 抛出（StackOverflowError 由 ProgramLoader.decodeMapped 的「嵌套过深」收口接住）。
            // 绝不带着活线程离开 —— 下一帧调用方就会 close 映射（CMP-11）。
            if (serialPhases) {
                // 兼容装载档：校验与去重**串行**（2026-10-28 低端机兼容）。结果与并行档
                // 逐字段一致，异常也沿同一条路径上抛 —— StackOverflowError 在主线程抛出，
                // 照样落进 decodeMapped 的「嵌套过深」收口；mark 顺序不变，失败屏口径不受影响。
                validate(program, flags)
                stage(LoadStages.VALIDATE)
                program.internStatementArgs()
                stage(LoadStages.INTERN)
            } else {
                var internFailure: Throwable? = null
                val internThread = Thread({
                    try {
                        program.internStatementArgs()
                    } catch (t: Throwable) {
                        internFailure = t
                    }
                }, "erbc-intern-seed")
                internThread.isDaemon = true
                internThread.start()
                validate(program, flags)
                stage(LoadStages.VALIDATE)
                internThread.join()
                internFailure?.let { throw it }
                stage(LoadStages.INTERN)
            }
            // T2-64 波次 D：装载期**不建**扁平池（分片惰性源，首次执行才建片），故无 FLAT 段。
            return program
        }

        /**
         * 引用范围校验（装载期一次扫完，运行期不再判）。
         *
         * marker 区间（op 29..197）与 `_EVAL_*` 是「缺段即静默失效」的两个入口，
         * 这里给的是**指名道姓**的错误，而不是让 VM 跑到那一行才炸。
         *
         * 取指面：只走 [Program.op]/[Program.a]/[Program.b]/[Program.c] ⇒ `CodeSource.word`
         * ⇒ 页视图，**不碰** [Program.code] 的整段物化（M8-COD-EMAP 后 Program 的指令流
         * 是映射页，物化会把整段拖回堆内，等于把本波的收益吃回去）。
         */
        private fun validate(
            program: Program,
            flags: Int,
        ) {
            if (program.functions.isEmpty()) throw ErbcDecodeException(0, "函数表为空")
            if (program.entryFunction !in program.functions.indices) {
                throw ErbcDecodeException(0, "入口函数下标越界: ${program.entryFunction}（共 ${program.functions.size} 个）")
            }
            val count = program.instructionCount
            val hasExpr = (flags and ErbcFormat.FLAG_HAS_EXPRESSIONS) != 0
            val hasStmt = (flags and ErbcFormat.FLAG_HAS_STATEMENTS) != 0
            // 指令趟保持**串行**（2026-10-24 实测：把它切成 8 条带并行反而没赚 —— 480 万条
            // 指令的取指面主要是 CODE 段映射页的首次触碰/缺页，8 条线程一起抢 8 个核会把
            // 并发跑去重的那条线程（`erbc-intern-seed`）饿到验证结束才跑，净收益为负；
            // 单线程时两者正好一人一核，重叠才是真重叠。函数记录趟仍然并行（见
            // [MappedFunctionTable.validateRecords]，那段是真 CPU 活）。
            // 分段 mark（M10-MEM）：这一趟要过 480 万条指令、把 CODE 段的映射页全部碰一遍，
            // 是装载期最后一个「峰值可能落在这里」的长段；按 1/8 条带报一次，
            // 失败屏的峰值行才说得出到底卡在哪一段（M8-DEVICE-BOOT 的痛点）。
            var nextBlock = 1
            // 跳转归属（CMP-55 映射路）：惰性函数表建一个**无物化**扫描器，随指令趟一起判 ——
            // 不额外多走一遍 480 万条指令，也不把 110,072 条 FunctionInfo 拖进堆。
            val jumpScan = (program.functions as? MappedFunctionTable)?.jumpScan(count)
            for (pc in 0 until count) {
                if (nextBlock <= BLOCK_MARKS && pc >= (count.toLong() * nextBlock / BLOCK_MARKS).toInt()) {
                    markBlock(LoadStages.VALIDATE, nextBlock)
                    nextBlock++
                }
                val op = program.op(pc)
                val a = program.a(pc)
                val b = program.b(pc)
                jumpScan?.observe(pc, op, a, b)
                when (op) {
                    Opcode._SET_INT -> requirePool(op, b, program.constantCount, pc, "常量池")
                    Opcode._SET_STR -> requirePool(op, b, program.stringCount, pc, "字符串池")
                    Opcode._PRINT_LITERAL -> requirePool(op, a, program.stringCount, pc, "字符串池")
                    Opcode._EVAL_INT, Opcode._EVAL_STR -> {
                        if (!hasExpr) {
                            throw ErbcDecodeException(
                                0,
                                "本文件声明不含表达式池，但 pc=$pc 的 ${Opcode.nameOf(op)} 需要表达式下标 $a",
                            )
                        }
                        requirePool(op, a, program.exprCount, pc, "表达式池")
                    }

                    Opcode._CALL -> {
                        if (a !in program.functions.indices) {
                            throw ErbcDecodeException(0, "pc=$pc 的 _CALL 引用函数下标 $a，越界（共 ${program.functions.size} 个）")
                        }
                    }

                    else -> if (Opcode.isStackOp(op)) {
                        // 栈码组：操作数直接是池下标（T2-21），判据只有
                        // ErbcReader.requireStackOpPool 一份（两条装载路必须是同一判据）。
                        ErbcReader.requireStackOpPool(op, a, program.constantCount, program.stringCount, pc)
                    } else if (ErbcReader.needsStatementPool(op)) {
                        if (!hasStmt) {
                            throw ErbcDecodeException(
                                0,
                                "本文件声明不含语句池，但 pc=$pc 的语句 marker ${Opcode.nameOf(op)} 需要语句下标 $a" +
                                    "（数据节以 Statement 实例为键，缺池即静默失效）",
                            )
                        }
                        requirePool(op, a, program.statementCount, pc, "语句池")
                    }
                }
            }
            val fns = program.functions
            if (fns is LazyFunctionTable) {
                // 惰性表：走一遍不缓存的校验趟（与下面整池档共用同一个判界函数）。
                fns.validateRecords(count)
            } else {
                for (f in fns) validateFunctionRecord(f, count)
                // 整池档：与字节路**同一份**实现（跳转归属）。
                ErbcReader.requireJumpTargets(program)
            }
            // 惰性档的跳转归属已在指令趟里攒成候选（见 [JumpScan]），记录校验过了才抛。
            jumpScan?.throwIfPending()
        }

        /**
         * 表达式节点表的**无分配边界扫描**（M12-EXPRLAZY）：只记每条记录的起始偏移，
         * 不建 `Expr`、不解 `String`。读字节的顺序与 [RecordSource.readNode] **逐字节相同**，
         * 所以截断/未知标签/子下标越界与后序性违例都在**同一字节位置**上抛出同一句话。
         *
         * 差异（已知且可接受）：运算符名的**枚举译码**延到物化时（这里只查串表下标越界），
         * 因此「合法 UTF-8 但不是已知运算符」的畑形产物会在首次 `expr(k)` 时才报错。
         */
        private fun scanNodeOffsets(
            cur: StreamCursor,
            strings: StreamStringTable,
            nodeCount: Int,
            rootsStart: Int,
        ): NodeOffsets {
            val offsets = NodeOffsets.Builder(nodeCount)
            for (i in 0 until nodeCount) {
                offsets.add(cur.pos)
                // 这里**不包**诊断前缀：原异常已带字节偏移，而
                // `ErbcStreamEquivalenceTest` 要求「流式 vs 全读两条路径的异常文案逐字相同」。
                skipNode(cur, strings, i, nodeCount)
            }
            if (cur.pos != rootsStart) {
                throw ErbcDecodeException(
                    0,
                    "段 2 边界扫描错位：扫完 $nodeCount 条后 pos=${cur.pos}，期望池根表起点 $rootsStart" +
                        "（差 ${cur.pos - rootsStart} 字节）",
                )
            }
            return offsets.build()
        }

        /**
         * 段 3 节点表的**无分配边界扫描**（M-MEM1）：与 [scanNodeOffsets] 共用同一套 [skipNode]，
         * 字节读序与 [RecordSource.readNodesIndexed] 逐字节相同 —— 截断、未知标签、串表下标越界、
         * 子下标越界与后序性违例仍在**同一字节位置**抛出同一句话。
         *
         * 收尾自检与段 2 不同：段 2 扫完后紧跟「池根表起点」（段尾减 poolCount×4，可先验锚定）；
         * 段 3 节点表后面直接是语句记录区，没有可从段头先验算出的锚点 ⇒ 取下界
         * 「记录区剩余字节 ≥ stmtCount×15（一条语句记录的最短编码：INSTRUCTION + VoidArgument）」。
         * 扫描器与记录格式脱节（多吞字节）在全量产物上会按每条 ~23 B 的量级把剩余区压穿，当场爆；
         * 少吞字节这一残留面由记录侧兜底：侧车可信时的 12 条抽样回放与不可信时的逐条回放
         * （都走 [RecordSource.readStatement]，见 [validateRecordOffsets]）。
         *
         * 差异（已知且可接受，口径与 [scanNodeOffsets] 逐字相同）：运算符/变量名的**枚举译码**与
         * String 内容解码延到物化时（这里只查串表下标越界），「合法 UTF-8 但不是已知运算符」的
         * 畑形产物要到首次访问该节点才报错 —— 段 2（M12-EXPRLAZY）已按同一口径上线。
         */
        private fun scanStmtNodeOffsets(
            cur: StreamCursor,
            strings: StreamStringTable,
            nodeCount: Int,
            stmtCount: Int,
        ): NodeOffsets {
            val offsets = NodeOffsets.Builder(nodeCount)
            for (i in 0 until nodeCount) {
                offsets.add(cur.pos)
                // 与 scanNodeOffsets 同口径：这里**不包**诊断前缀，异常自带字节偏移。
                skipNode(cur, strings, i, nodeCount)
            }
            val recordBytes = file.endOf(ErbcFormat.SECTION_STATEMENTS) - cur.pos.toLong()
            if (recordBytes < stmtCount.toLong() * 15) {
                throw ErbcDecodeException(
                    cur.pos,
                    "段 3 边界扫描错位：扫完 $nodeCount 条节点后记录区只剩 $recordBytes 字节，" +
                        "不足 stmtCount($stmtCount)×15（一条语句记录的最短编码）",
                )
            }
            return offsets.build()
        }

        /** 跳过一条节点记录（字节长度与 [RecordSource.readNode] 逐字节对齐）。 */
        private fun skipNode(
            cur: StreamCursor,
            strings: StreamStringTable,
            self: Int,
            nodeCount: Int,
        ) {
            when (val tag = cur.u8()) {
                ExprTag.INT_LITERAL -> {
                    cur.i64(); cur.i32(); cur.i32()
                }

                ExprTag.STR_LITERAL -> {
                    strings.checkIndex(cur.u32()); cur.i32(); cur.i32()
                }

                ExprTag.NULL_TERM -> {
                    cur.bool(); cur.i32(); cur.i32()
                }

                ExprTag.VAR_REF -> {
                    strings.checkIndex(cur.u32())
                    val subKey = cur.u32()
                    if (subKey != NONE_REF) strings.checkIndex(subKey)
                    cur.u8() // operandType
                    cur.u8() // flags
                    skipChildList(cur, strings, self, nodeCount)
                    cur.i32(); cur.i32()
                }

                ExprTag.KEYWORD_INDEX_ARG -> {
                    skipChild(cur, self, nodeCount)
                    strings.checkIndex(cur.u32())
                    cur.i32(); cur.i32(); cur.i32()
                }

                ExprTag.FUNC_CALL -> {
                    strings.checkIndex(cur.u32())
                    cur.u8() // operandType
                    // 参数表是 `readChildOptList`：允许 NONE_REF 空参占位（`childOpt`）。
                    val n = cur.count("表达式下标表", 4)
                    repeat(n) {
                        val idx = cur.u32()
                        if (idx != NONE_REF) skipChildIndex(idx, self, nodeCount)
                    }
                    cur.i32(); cur.i32()
                }

                ExprTag.UNARY, ExprTag.UNARY_AFTER -> {
                    strings.checkIndex(cur.u32()) // operatorName
                    skipChild(cur, self, nodeCount)
                    cur.i32(); cur.i32()
                }

                ExprTag.BINARY -> {
                    strings.checkIndex(cur.u32())
                    skipChild(cur, self, nodeCount)
                    skipChild(cur, self, nodeCount)
                    cur.u8()
                    cur.i32(); cur.i32()
                }

                ExprTag.TERNARY -> {
                    skipChild(cur, self, nodeCount)
                    skipChild(cur, self, nodeCount)
                    skipChild(cur, self, nodeCount)
                    cur.u8()
                    cur.i32(); cur.i32()
                }

                ExprTag.FORM_STRING -> {
                    val n = cur.count("FormStringTerm 部件数", 5)
                    repeat(n) {
                        when (val partTag = cur.u8()) {
                            ErbcFormat.FormPartTag.TEXT -> strings.checkIndex(cur.u32())
                            ErbcFormat.FormPartTag.EMBEDDED -> {
                                skipChild(cur, self, nodeCount)
                                skipConversion(cur, strings, self, nodeCount)
                            }

                            else -> throw ErbcDecodeException(cur.pos - 1, "未知 FormPart 标签: $partTag")
                        }
                    }
                    cur.i32(); cur.i32()
                }

                else -> throw ErbcDecodeException(cur.pos - 1, "未知表达式节点标签: $tag")
            }
        }

        private fun skipConversion(
            cur: StreamCursor,
            strings: StreamStringTable,
            self: Int,
            nodeCount: Int,
        ) {
            when (val tag = cur.u8()) {
                ErbcFormat.FormConversionTag.CURLY, ErbcFormat.FormConversionTag.PERCENT -> {
                    // 宽度下标已由上一句读出，这里**不能**再调 skipChild（那会再读一个 u32，
                    // 每个 CURLY/PERCENT 多吞 4 字节 ⇒ 整个节点表错位；2026-10-24 真机均在第 1108 条爆的就是这个）。
                    val w = cur.u32()
                    if (w != NONE_REF) skipChildIndex(w, self, nodeCount)
                    cur.bool()
                }

                ErbcFormat.FormConversionTag.YEN_AT -> {
                    skipChild(cur, self, nodeCount)
                    skipChild(cur, self, nodeCount)
                }

                else -> throw ErbcDecodeException(cur.pos - 1, "未知 FormConversion 标签: $tag")
            }
        }

        private fun skipChildList(
            cur: StreamCursor,
            strings: StreamStringTable,
            self: Int,
            nodeCount: Int,
        ) {
            val n = cur.count("表达式下标表", 4)
            repeat(n) { skipChild(cur, self, nodeCount) }
        }


        /** 与 [RecordSource.child] 同一句判界（越界 + 后序性）。 */
        private fun skipChild(cur: StreamCursor, self: Int, nodeCount: Int) {
            skipChildIndex(cur.u32(), self, nodeCount)
        }

        private fun skipChildIndex(index: Int, self: Int, nodeCount: Int) {
            if (index < 0 || index >= nodeCount) {
                throw ErbcDecodeException(0, "节点下标越界: $index（表长 $nodeCount）")
            }
            if (self >= 0 && index >= self) {
                throw ErbcDecodeException(
                    0,
                    "节点 $self 引用了不合法的后继下标 $index（节点表要求 引用下标 < 自身下标）",
                )
            }
        }

        private fun requirePool(
            op: Int,
            index: Int,
            size: Int,
            pc: Int,
            what: String,
        ) {
            if (index < 0 || index >= size) {
                throw ErbcDecodeException(0, "pc=$pc 的 ${Opcode.nameOf(op)} 引用$what 下标 $index，越界（共 $size 条）")
            }
        }
}

// ======================================================================
// 逐字副本：ErbcReader.kt 的 RecordSource（ErbcCursor → StreamCursor，
// ErbcStringView → StreamStringTable；其余一字未改）。
// ======================================================================

/**
 * 表达式池的惰性供给（M12-EXPRLAZY）：只持「记录偏移 + 池根下标」，节点按需重放。
 *
 * 与段 3 语句池同一套机制（[RecordSource.enableLazyNodes] / [RecordSource.nodeAt]）：
 * 节点表的后序性保证「子下标 < 父下标」，重放一条记录时子节点已在表里
 * 或按递归补齐；同一下标恒同一实例（[RecordSource] 的 `nodes[]` 缓存）。
 */
private class MappedExprSource(
    private val arena: RecordSource,
    /**
     * 段 2 的**整段 pin** 窗口（MEM7 刀 D）：池根下标表就排在段尾
     * `[rootsFrom, rootsFrom + 4 × poolCount)`，所以取一根 = 从这张稀疏分块表里读一个 Int，
     * 块第一次被碰到时从映射页一次灌满，堆上不再留整张 `IntArray`。
     * 判界区间由构造方保证（`decodeProgram` 的 `rootsStart = sectionEnd - poolCount × 4`，
     * 与扫描自检停在同一条边界上）⇒ 灌块恒走 pinned 快路径，不会换块、也不会在通道关掉后再 map。
     */
    win: SectionWindow,
    rootsFrom: Int,
    private val poolCount: Int,
    /** 产物标识（与装载层 `withSource` 同一个口径）。 */
    private val source: String,
) : LazyExprSource {
    private val roots = BlockU32s(win.fork(), rootsFrom, poolCount)

    override val nodeCount: Int get() = poolCount

    override fun node(index: Int): Expr {
        // 判界在取数之前（与改造前的 `roots.size` 判定同一条文案），块表读不承担越界语义
        if (index < 0 || index >= poolCount) {
            throw ErbcDecodeException(0, "表达式池下标越界: $index（共 $poolCount 条）")
        }
        return try {
            arena.nodeAt(roots[index])
        } catch (e: ErbcFormatException) {
            // 惰性档的解码发生在运行期首次访问：在这里补上装载层口径
            // （产物路径 + `ErbcLoadException`），让「装载期就坏」与「首次访问才发现坏」
            // 对外是**同一句话**——`ErbcStreamEquivalenceTest` 的逐字节对拍就靠这一条。
            throw ErbcLoadException("$source: ${e.message}", e)
        }
    }
}

/**
 * 节点记录起始偏移的紧凑表（M13-MEM10 刀 G）。
 *
 * ## 为什么压缩
 *
 * 段 2 / 段 3 的惰性档只需要回答「第 i 条节点记录从哪个字节开始」。旧形态是整张
 * `IntArray`（898,111 + 827,992 项 = 6.6 MiB 堆常驻），而这张表由**升序边界扫描**产出
 * ——偏移单调不减、平均每条 ~27 B，块内相对偏移必然装得进 u16。
 *
 * 形态：块基偏移 `IntArray` + 块内 u16 相对偏移 `ShortArray`。块宽 [SHIFT] = 9（512 条），
 * 段 2/段 3 平均块跨度 ~14 KiB；块跨度越 65535 的块退化成 [overflow] 里的绝对偏移
 * `IntArray`（语料实测 0 块 —— 判据在装配点，退化路径同样正确）。常驻
 * = count×2 B + count/512×4 B：段 2+段 3 合计 6.6 → 3.3 MiB。
 *
 * 取用 = 两次数组读 + 一次加法（[get]），只在按需重放一条节点记录时调用，
 * 不是表达式求值热路径。
 */
internal class NodeOffsets private constructor(
    val size: Int,
    private val bases: IntArray,
    private val deltas: ShortArray,
    private val overflow: HashMap<Int, IntArray>?,
) {

    fun get(index: Int): Int {
        val c = index ushr SHIFT
        val base = bases[c]
        if (base < 0) return overflow!!.getValue(c)[index and MASK]
        return base + (deltas[index].toInt() and 0xFFFF)
    }

    /** 升序装配器：[add] 必须按 index = 0, 1, … 顺序调用（边界扫描本来就是这个顺序）。 */
    class Builder(private val count: Int) {
        private val bases = IntArray((count + MASK) ushr SHIFT)
        private val deltas = ShortArray(count)
        private var overflow: HashMap<Int, IntArray>? = null
        private val chunk = IntArray(CHUNK)
        private var next = 0
        private var last = -1

        fun add(offset: Int) {
            check(next < count) { "节点偏移装配超出 $count 条" }
            check(offset >= last) { "节点偏移必须单调不减：第 $next 条 $offset < 上一条 $last" }
            chunk[next and MASK] = offset
            last = offset
            next++
            if ((next and MASK) == 0) closeChunk(next - 1, CHUNK)
        }

        fun build(): NodeOffsets {
            val leftover = next and MASK
            if (leftover != 0) closeChunk(next - 1, leftover)
            check(next == count) { "节点偏移装配不足：$next / $count" }
            return NodeOffsets(count, bases, deltas, overflow)
        }

        private fun closeChunk(startIndex: Int, width: Int) {
            val c = startIndex ushr SHIFT
            val base = chunk[0]
            val span = chunk[width - 1] - base
            if (span in 0..0xFFFF) {
                bases[c] = base
                val from = c shl SHIFT
                for (k in 0 until width) deltas[from + k] = (chunk[k] - base).toShort()
            } else {
                bases[c] = -1
                (overflow ?: HashMap<Int, IntArray>(4).also { overflow = it })[c] = chunk.copyOf(width)
            }
        }
    }

    companion object {
        /** 块宽 512 条：段 2/段 3 平均块跨度 ~14 KiB（u16 上限 65535）。 */
        private const val SHIFT = 9
        private const val CHUNK = 1 shl SHIFT
        private const val MASK = CHUNK - 1

        /** 稠密 `IntArray` 的适配（段 3 整池对照档：[RecordSource.readNodesIndexed] 的产物）。 */
        fun of(offsets: IntArray): NodeOffsets {
            val b = Builder(offsets.size)
            for (o in offsets) b.add(o)
            return b.build()
        }
    }
}

/**
 * 记录解码器（节点表 + 语句/参数记录），与 `ErbcWriter.RecordSink` 逐字段互为逆函数。
 *
 * 节点表按「子节点下标 < 父节点下标」的后序性质单遍构建，因此读侧零递归：
 * 深表达式树不会撑爆栈，这是「节点表」相对「内联递归」的实质收益（规约 §5.3）。
 */
internal class RecordSource(
    private val strings: StreamStringTable,
    nodeCount: Int,
    /**
     * 惰性节点重建的递归上限（CMP-21）：超过就走 [buildPrefixThrough] 的升序退路。
     * 默认取 ART 安全值 [MAX_ENSURE_DEPTH]，装载入口可显式覆盖。
     */
    private val maxEnsureDepth: Int = MAX_ENSURE_DEPTH,
) {
    /**
     * 节点表（MEM7 刀 2：分块引用表）。段 3 / 段 2 各一张，条数 827,992 / 898,111，
     * 惰性档装载完**非空槽为 0**（mem56 报告 §1）—— 整表常驻 3.2 / 3.4 MiB 纯白付，
     * 分块后装载期只付块表本体，块等真建出节点时才分配。[size] 就是构造时的 nodeCount，
     * 判界文案里的「表长」读数与改造前逐字相同。
     */
    private val nodes = BlockRefs<Expr>(nodeCount)

    // ---- 惰性节点重建（M10-MEM）----------------------------------------------------
    //
    // 索引趟解完语句记录后会把 [nodes] 整表清空（那些 Expr 已经没有任何语句引用了），
    // 之后每条按需解码的语句**只补它自己引用到的那些节点**：节点表有「子下标 < 父下标」的
    // 后序性质，所以补一个节点 = 从它自己的起始偏移重放那一条节点记录，子节点还没建就递归补
    // （递归方向恒为下标递减 ⇒ 一定终止）。重建走的是同一个 [readNode]、同一段字节 ⇒
    // 解出的节点与索引趟逐字段相同（同一批 `String` 会重新解码，因为串池备忘此时已关闭，
    // 见 [StreamStringTable.memoize]）。
    //
    // 为什么不是「顺序补到第 k 个」的前缀形态：真机取语句下标是跨文件跳的，一次跳到位
    // 就会把 0..k 整张节点表拖回堆里 —— 2026-10-21 实测取用 1,000 条高下标语句要
    // +138.5 MiB 常驻，等于把惰性化原样吃回去。前缀只留作**递归超深时的退路**
    // （[buildPrefixThrough]）：退路逐字段同样正确，只是会把没引用的节点也建出来。
    private var lazyWin: SectionWindow? = null
    private var lazyFileSize = 0
    private var nodeOffsets: NodeOffsets? = null
    private var ensureDepth = 0
    private var ascendingBuild = false
    /**
     * 稠密前缀游标（M-MEM1）：`0..prefixBuiltThrough` 保证全部非空，[buildPrefixThrough]
     * 从它后面接着扫 —— 装载侧迭代档每条新引用都走升序补链，不省这一段就是 O(N²)。
     * 只在整表被弃掉的两处归零（[enableLazyNodes] / [discardBuiltNodes]）。
     */
    private var prefixBuiltThrough = -1

    /**
     * 装载侧回放的迭代开关（M-MEM1）。
     *
     * 段 3 的惰性档把「节点在装载期整表物化」换成了「回放摸到哪条补哪条」。不可信回落的
     * **逐条扫描**发生在调用线程上，而调用线程的栈尺寸不由 core 决定（合成装置压到 128 KiB；
     * ART 的后台线程也只有默认栈）—— 深表达式链会在 [maxEnsureDepth] 层守卫递归**先于上限**
     * 打穿小栈（CMP-21 同款事故，只是换了线程）。置 true 后 [lazyEnsure] 不在调用栈上递归，
     * 直接走 [buildPrefixThrough] 的升序迭代（稠密前缀游标 ⇒ 整趟仍 O(N)）。
     * 只在回落扫描外围开、扫完即关：侧车抽查的 12 条回放与**运行期**首次取用仍走
     * 「递归 + 退路」的稀疏形态 —— 迭代档会把 0..抽样下标 的整段前缀物化，
     * 那正是本波要省掉的那一坨（2026-10-21 实测前缀形态取 1,000 条高下标语句 +138.5 MiB）。
     *
     * T2-64 波次 C 起多一个使用点：**装载期的「压扁即弃」趟**（`LazyStatementRecords.buildFlatStatements`）
     * 也开它 —— 那一趟同为装载期回放、且要触碰全部语句，用递归会把「守卫深度 × 栈帧」重新
     * 压到调用线程头上（`StreamEnsureDepthTest` 用 128 KiB 小栈钉这条）；它反正要摸完所有结点，
     * 升序补链的代价与稀疏递归相同。
     */
    private var loadReplayIterative = false

    /** 装载侧回放的迭代开关：解码器在 enableLazyNodes 后开、装载收尾关（见字段 KDoc）。 */
    fun setLoadReplayIterative(on: Boolean) {
        loadReplayIterative = on
    }

    /**
     * 开启惰性节点重建：记下本段窗口与**每条节点记录的起始偏移**，并清空已构建的节点表。
     * [offsets] 两条来源：段 2 / 段 3 惰性档由**无分配边界扫描**产出（表本来就是空的，
     * 这句只做登记 + 空清空）；段 3 整池对照档由 [readNodesIndexed] 的同趟顺序读产出，
     * 这句才真正弃掉建好的整表。
     */
    fun enableLazyNodes(
        win: SectionWindow,
        offsets: NodeOffsets,
        fileSize: Int,
    ) {
        check(offsets.size == nodes.size) { "节点偏移表长 ${offsets.size} 与节点表长 ${nodes.size} 不符" }
        nodes.clear()
        prefixBuiltThrough = -1
        lazyWin = win
        nodeOffsets = offsets
        lazyFileSize = fileSize
    }

    /**
     * 弃掉装载期回放摸进 `nodes[]` 的节点（M-MEM1，惰性档收尾专用）。
     *
     * 扫描趟不建节点，但侧车抽查的 12 条回放与不可信回落的逐条扫描会经 [lazyEnsure] 把被引用
     * 的节点补进表；终态要与改造前「先建全表、[enableLazyNodes] 清空」一致 —— 装载返回后
     * 表是空的，节点等首次取用再重放（登记字段不动，稠密前缀游标同场归零）。
     */
    fun discardBuiltNodes() {
        nodes.clear()
        prefixBuiltThrough = -1
    }

    /**
     * 索引趟的节点区：顺序解出每个节点（子节点必已在表里），同时记下每条记录的起始偏移。
     * 与 [readNodes] 的读序、判界、异常逐字相同，多出来的只有一张 `IntArray`。
     *
     * 现在只剩段 3 **整池对照档**走这里（惰性档改无分配边界扫描，见
     * [ErbcStreamDecoder.scanStmtNodeOffsets]）：对照档的记录当场要用建好的节点表。
     */
    fun readNodesIndexed(cur: StreamCursor): IntArray {
        val offsets = IntArray(nodes.size)
        for (i in 0 until nodes.size) {
            offsets[i] = cur.pos
            nodes[i] = readNode(cur, i)
        }
        return offsets
    }

    /**
     * 取第 [index] 个节点：已构建即返回；惰性模式已开就按记录偏移重放那一条节点记录；
     * 两者都没有 ⇒ 抛出与改造前**逐字相同**的「尚未构建」。
     */
    private fun lazyEnsure(index: Int): Expr {
        val offsets = nodeOffsets ?: throw ErbcDecodeException(0, "节点 $index 尚未构建")
        val win = lazyWin ?: throw ErbcDecodeException(0, "节点 $index 尚未构建")
        // 升序退路正在进行时绝不递归：后序性由索引趟校验过，被引用的子节点此时必已就位
        if (ascendingBuild) return nodes[index] ?: throw ErbcDecodeException(0, "节点 $index 尚未构建")
        // 装载侧回放（M-MEM1）：调用线程栈尺寸不由 core 决定，迭代补链、不递归（见字段 KDoc）
        if (loadReplayIterative || ensureDepth >= maxEnsureDepth) return buildPrefixThrough(index)
        ensureDepth++
        try {
            val built = readNode(StreamCursor(win, offsets.get(index), lazyFileSize), index)
            nodes[index] = built
            return built
        } finally {
            ensureDepth--
        }
    }

    /**
     * 退路：按下标升序把 0..[index] 补齐（不递归 ⇒ 与栈深无关）。
     *
     * 只有「表达式树嵌套超过 [maxEnsureDepth] 层」的产物才会走到这里，
     * 本仓的编译器产生不出这种节点表（节点由 `ExprCanonicalizer` 的后序折叠生成）。
     */
    private fun buildPrefixThrough(index: Int): Expr {
        val offsets = nodeOffsets ?: throw ErbcDecodeException(0, "节点 $index 尚未构建")
        val win = lazyWin ?: throw ErbcDecodeException(0, "节点 $index 尚未构建")
        ascendingBuild = true
        try {
            // 稠密前缀（M-MEM1）：0..prefixBuiltThrough 必已非空，从游标后接着扫。
            for (j in (prefixBuiltThrough + 1).coerceAtLeast(0)..index) {
                if (nodes[j] != null) continue
                nodes[j] = readNode(StreamCursor(win, offsets.get(j), lazyFileSize), j)
            }
            if (index > prefixBuiltThrough) prefixBuiltThrough = index
        } finally {
            ascendingBuild = false
        }
        return nodes[index] ?: throw ErbcDecodeException(0, "节点 $index 尚未构建")
    }

    fun str(i: Int): String = strings[i]

    fun strOpt(i: Int): String? = if (i == NONE_REF) null else strings[i]

    /**
     * 本记录源用到的串表是否已经整块 pin 住（CMP-19 的释放前提之一）。
     *
     * 段 3 的记录/节点窗口在装载期就整段 pin，真正决定「能不能关通道」的是这张**局部串表**：
     * 它是运行期随机取值的热路径，没 pin 住就还会 `channel.map`。
     */
    fun stringsChannelFree(): Boolean = strings.channelFree

    /**
     * 把本记录源那张局部串池的**偏移表**改接到 [win] 的整段 pin 上（MEM7 刀 C）。
     *
     * 段 3 的串池偏移表（1,680,720 项 = 6.4 MiB）与记录区在同一段里，而整段 pin 是解码器
     * 在本源建好之后才铺的 ⇒ 只能事后接一次。接不上（pin 覆盖不到）就保持堆上数组，
     * 取值路径逐字不变。
     */
    fun mapStringOffsetsFrom(win: SectionWindow): Boolean = strings.useMappedOffsets(win)

    fun nodeAt(index: Int): Expr {
        if (index < 0 || index >= nodes.size) {
            throw ErbcDecodeException(0, "表达式根下标越界: $index（节点表长 ${nodes.size}）")
        }
        return nodes[index] ?: lazyEnsure(index)
    }

    /**
     * 取子节点。
     *
     * [self] >= 0 表示「节点表内部引用」，除越界外还要检查后序性（引用必须早于自身）；
     * [self] < 0 表示「记录作用域」——此时节点表已整体构建完毕，任何已构建节点都可引用。
     *
     * 惰性模式下「尚未构建」不再报错而是把前缀补上（[lazyEnsure]）；判界与后序性检查
     * 一条不动，所以畸形产物抛的还是同一句话。
     */
    private fun child(
        self: Int,
        index: Int,
    ): Expr {
        if (index < 0 || index >= nodes.size) {
            throw ErbcDecodeException(0, "节点下标越界: $index（表长 ${nodes.size}）")
        }
        if (self >= 0 && index >= self) {
            throw ErbcDecodeException(0, "节点 $self 引用了不合法的后继下标 $index（节点表要求 引用下标 < 自身下标）")
        }
        return nodes[index] ?: lazyEnsure(index)
    }

    private fun childOpt(
        self: Int,
        index: Int,
    ): Expr? = if (index == NONE_REF) null else child(self, index)

    private fun varRef(
        self: Int,
        index: Int,
    ): VarRef {
        val e = child(self, index)
        return e as? VarRef
            ?: throw ErbcDecodeException(0, "节点 $index 应为 VarRef，实为 ${e.javaClass.simpleName}")
    }

    private fun varRefOpt(
        self: Int,
        index: Int,
    ): VarRef? = if (index == NONE_REF) null else varRef(self, index)

    // ------------------------------------------------------------------
    // 节点表
    // ------------------------------------------------------------------

    fun readNodes(cur: StreamCursor) {
        for (i in 0 until nodes.size) nodes[i] = readNode(cur, i)
    }

    /**
     * 索引趟（M10-MEM）：按**顺序**把 [recordCount] 条语句记录逐条解出来、当场丢弃，
     * 只留下每条记录的起始绝对偏移。
     *
     * 这不是「另一套解码」——读序、判界、抛出的异常类型与消息与改造前的
     * `repeat(stmtCount) { arena.readStatement(cur) }` 逐字相同（同一个 [readStatement]、
     * 同一个游标、同一个窗口），唯一的区别是结果不进池。所以：
     * - 畸形产物仍然在**装载期**、在**同一个字节位置**上抛出；
     * - 记录条数与段头声明不符（截断）时，越界判定仍然由 [StreamCursor.need] 给出同一句话；
     * - 语句对象解完即弃 ⇒ 索引趟的堆峰值只到「节点表 + 一条记录的参数树」，
     *   而不是全池的 62.6 MiB 外壳 + 199.6 MiB 子树 + 131.0 MiB 原文（perf-report §2b）。
     *
     * [blockMark] 每完成 1/8 条带调一次（参数 = 已完成条数、总条数），供装载分段 mark 用。
     */
    fun buildRecordOffsets(
        cur: StreamCursor,
        recordCount: Int,
        blockMark: (index: Int, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): IntArray {
        val offsets = IntArray(recordCount)
        var nextBlock = 1
        for (i in 0 until recordCount) {
            offsets[i] = cur.pos
            readStatement(cur)
            if (nextBlock <= BLOCK_MARKS && i >= (recordCount.toLong() * nextBlock / BLOCK_MARKS).toInt()) {
                blockMark(nextBlock, i + 1, recordCount)
                nextBlock++
            }
        }
        // 尾块补齐：条带整除时最后一个 1/8 会落在循环外，装载最后一段的峰值就没归属了
        if (recordCount > 0 && nextBlock <= BLOCK_MARKS) blockMark(nextBlock, recordCount, recordCount)
        return offsets
    }

    private fun readNode(
        cur: StreamCursor,
        self: Int,
    ): Expr {
        val tag = cur.u8()
        val e: Expr = when (tag) {
            ExprTag.INT_LITERAL -> IntLiteral(cur.i64(), cur.i32(), cur.i32())
            ExprTag.STR_LITERAL -> StrLiteral(str(cur.u32()), cur.i32(), cur.i32())
            ExprTag.NULL_TERM -> NullTerm(cur.bool(), cur.i32(), cur.i32())
            ExprTag.VAR_REF -> {
                val name = str(cur.u32())
                val subKey = strOpt(cur.u32())
                val type = operandType(cur.u8())
                val flags = cur.u8()
                val indices = readChildList(cur, self)
                VarRef(
                    name = name,
                    indices = indices,
                    isNoArg = (flags and 1) != 0,
                    operandType = type,
                    isConst = (flags and 2) != 0,
                    subKey = subKey,
                    line = cur.i32(),
                    col = cur.i32(),
                )
            }

            ExprTag.KEYWORD_INDEX_ARG -> KeywordIndexArg(
                child(self, cur.u32()),
                str(cur.u32()),
                cur.i32(),
                cur.i32(),
                cur.i32(),
            )

            ExprTag.FUNC_CALL -> {
                val name = str(cur.u32())
                val type = operandType(cur.u8())
                val args = readChildOptList(cur, self)
                FuncCall(name, args, type, cur.i32(), cur.i32())
            }

            ExprTag.UNARY -> Unary(operatorName(cur.u32()), child(self, cur.u32()), cur.i32(), cur.i32())
            ExprTag.UNARY_AFTER -> UnaryAfter(operatorName(cur.u32()), child(self, cur.u32()), cur.i32(), cur.i32())
            ExprTag.BINARY -> {
                val op = operatorName(cur.u32())
                val left = child(self, cur.u32())
                val right = child(self, cur.u32())
                val type = operandType(cur.u8())
                Binary(op, left, right, type, cur.i32(), cur.i32())
            }

            ExprTag.TERNARY -> {
                val cond = child(self, cur.u32())
                val ifTrue = child(self, cur.u32())
                val ifFalse = child(self, cur.u32())
                val type = operandType(cur.u8())
                Ternary(cond, ifTrue, ifFalse, type, cur.i32(), cur.i32())
            }

            ExprTag.FORM_STRING -> {
                // TEXT 部件最少 5 字节（u8 标签 + u32 串下标）。
                val n = cur.count("FormStringTerm 部件数", 5)
                val parts = ArrayList<FormPart>(n)
                repeat(n) {
                    when (val partTag = cur.u8()) {
                        ErbcFormat.FormPartTag.TEXT -> parts.add(FormPart.Text(str(cur.u32())))
                        ErbcFormat.FormPartTag.EMBEDDED -> {
                            val ast = child(self, cur.u32())
                            parts.add(FormPart.Embedded(ast, readConversion(cur, self)))
                        }

                        else -> throw ErbcDecodeException(cur.pos - 1, "未知 FormPart 标签: $partTag")
                    }
                }
                FormStringTerm(parts, cur.i32(), cur.i32())
            }

            else -> throw ErbcDecodeException(cur.pos - 1, "未知表达式节点标签: $tag")
        }
        return e
    }

    private fun readConversion(
        cur: StreamCursor,
        self: Int,
    ): FormConversion {
        val tag = cur.u8()
        return when (tag) {
            ErbcFormat.FormConversionTag.CURLY -> FormConversion.Curly(childOpt(self, cur.u32()), cur.bool())
            ErbcFormat.FormConversionTag.PERCENT -> FormConversion.Percent(childOpt(self, cur.u32()), cur.bool())
            ErbcFormat.FormConversionTag.YEN_AT -> FormConversion.YenAt(child(self, cur.u32()), child(self, cur.u32()))
            else -> throw ErbcDecodeException(cur.pos - 1, "未知 FormConversion 标签: $tag")
        }
    }

    private fun readChildList(
        cur: StreamCursor,
        self: Int,
    ): List<Expr> {
        val n = cur.count("表达式下标表", 4)
        // 0 项复用全局空表单例：每条空 List 是 24 B，语句段 83 万节点上全是白花；
        // `List` 的读者（Vm / 语义层 / ErbcWriter）只读不写，空表语义与 ArrayList(0) 等价。
        if (n == 0) return emptyList()
        return ArrayList<Expr>(n).apply { repeat(n) { add(child(self, cur.u32())) } }
    }

    private fun readChildOptList(
        cur: StreamCursor,
        self: Int,
    ): List<Expr?> {
        val n = cur.count("表达式下标表", 4)
        if (n == 0) return emptyList()
        return ArrayList<Expr?>(n).apply { repeat(n) { add(childOpt(self, cur.u32())) } }
    }

    private fun operandType(wire: Int): OperandType = when (wire) {
        WireEnum.OPERAND_INT -> OperandType.INT
        WireEnum.OPERAND_STR -> OperandType.STR
        else -> throw ErbcDecodeException(0, "未知 OperandType 线值: $wire")
    }

    private fun operatorName(index: Int): OperatorCode {
        val name = strings[index]
        return try {
            OperatorCode.valueOf(name)
        } catch (e: IllegalArgumentException) {
            throw ErbcDecodeException(0, "未知 OperatorCode 名: $name", e)
        }
    }

    private fun sortOrder(wire: Int): SortOrder = when (wire) {
        WireEnum.SORT_UNDEF -> SortOrder.UNDEF
        WireEnum.SORT_ASCENDING -> SortOrder.ASCENDING
        WireEnum.SORT_DESCENDING -> SortOrder.DESENDING
        else -> throw ErbcDecodeException(0, "未知 SortOrder 线值: $wire")
    }

    private fun argValueType(wire: Int): ArgValueType? = when (wire) {
        WireEnum.TYPE_NULL -> null
        WireEnum.TYPE_INT -> ArgValueType.INT
        WireEnum.TYPE_STR -> ArgValueType.STR
        WireEnum.TYPE_VOID -> ArgValueType.VOID
        else -> throw ErbcDecodeException(0, "未知 ArgValueType 线值: $wire")
    }

    // ------------------------------------------------------------------
    // 参数
    // ------------------------------------------------------------------

    /** 真实参数记录（`NONE` 标签只允许出现在 args 由类推导的三处，由 [derivedArgument] 把关）。 */
    fun argument(cur: StreamCursor): Argument {
        val tag = cur.u8()
        if (tag == ArgTag.NONE) throw ErbcDecodeException(cur.pos - 1, "该语句必须带参数记录，但读到 ArgTag.NONE")
        return readArgument(cur, tag)
    }

    /** args 由类推导的三类（`FuncLabelStmt`/`GotoLabelStmt`/`SharpStmt`）：必须且只能读到 NONE。 */
    fun derivedArgument(cur: StreamCursor) {
        val tag = cur.u8()
        if (tag != ArgTag.NONE) throw ErbcDecodeException(cur.pos - 1, "该语句的 args 由类推导，不该出现参数记录(tag=$tag)")
    }

    private fun readArgument(
        cur: StreamCursor,
        tag: Int,
    ): Argument {
        val header = cur.u8()
        if ((header and 0xFC) != 0) throw ErbcDecodeException(cur.pos - 1, "参数头含未定义位: 0x${header.toString(16)}")
        val constStr = if ((header and 2) != 0) str(cur.u32()) else ""
        val constInt = if ((header and 2) != 0) cur.i64() else 0L
        val a: Argument = when (tag) {
            ArgTag.VOID -> VoidArgument()
            ArgTag.EXPRESSION -> ExpressionArgument(childOpt(-1, cur.u32()))
            ArgTag.EXPRESSION_ARRAY -> ExpressionArrayArgument(readChildOptList(cur, -1))
            ArgTag.EXPRESSIONS -> {
                val n = cur.count("ExpressionsArgument 项数", 5)
                val types = ArrayList<ArgValueType?>(n)
                val terms = ArrayList<Expr?>(n)
                repeat(n) {
                    types.add(argValueType(cur.u8()))
                    terms.add(childOpt(-1, cur.u32()))
                }
                ExpressionsArgument(types, terms)
            }

            ArgTag.INT_ASIGN -> IntAsignArgument(str(cur.u32()), intArray(cur), childOpt(-1, cur.u32()))
            ArgTag.STR_ASIGN -> StrAsignArgument(str(cur.u32()), intArray(cur), strOpt(cur.u32()))
            ArgTag.BIT -> BitArgument(varRef(-1, cur.u32()), readChildList(cur, -1))
            ArgTag.CASE -> CaseArgument(readCaseList(cur))
            ArgTag.METHOD -> MethodArgument(childOpt(-1, cur.u32()))
            ArgTag.PRINT_DATA -> PrintDataArgument(varRefOpt(-1, cur.u32()))
            ArgTag.REF -> RefArgument(str(cur.u32()), strOpt(cur.u32()), childOpt(-1, cur.u32()), cur.bool())
            ArgTag.STR_DATA -> StrDataArgument(varRefOpt(-1, cur.u32()))
            ArgTag.SP_ARRAY_CONTROL -> SpArrayControlArgument(
                varRef(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_ARRAY_SHIFT -> SpArrayShiftArgument(
                varRef(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_ARRAY_SORT -> SpArraySortArgument(
                varRef(-1, cur.u32()),
                sortOrder(cur.u8()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_BAR -> SpBarArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_BUTTON -> SpButtonArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_CVAR_SET -> SpCVarSetArgument(
                varRef(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_CALL -> SpCallArgument(childOpt(-1, cur.u32()), readChildOptList(cur, -1), readChildOptList(cur, -1))
            ArgTag.SP_CALLF -> SpCallFArgument(childOpt(-1, cur.u32()), readChildOptList(cur, -1), readChildOptList(cur, -1))
            ArgTag.SP_CALL_SHARP -> SpCallSharpArgument(
                childOpt(-1, cur.u32()),
                readChildOptList(cur, -1),
                readChildOptList(cur, -1),
            )

            ArgTag.SP_COLOR -> {
                val r = childOpt(-1, cur.u32())
                val g = childOpt(-1, cur.u32())
                val b = childOpt(-1, cur.u32())
                val rgb = childOpt(-1, cur.u32())
                if (rgb != null) SpColorArgument(rgb) else SpColorArgument(r, g, b)
            }

            ArgTag.SP_COPY_ARRAY -> SpCopyArrayArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_DT_COLUMN_OPTIONS -> {
                val dt = childOpt(-1, cur.u32())
                val column = childOpt(-1, cur.u32())
                val optionCount = cur.count("DTOption 数", 4)
                // minBytesPerItem = 4（CMP-22）：线上每项只有 1 B（u8），但紧跟的分配是
                // `ArrayList<DTOption>(optionCount)`，每个槽是一个 4 B 引用 —— 只按 1 B/项
                // 判界时，畸形计数能把预留放大到剩余字节的 4 倍（全量产物 ≈1.3 GB），
                // 抛 OutOfMemoryError ⇒ 失败屏误报「堆不足」。4 B/项 与分配槽宽同式，
                // 在分配之前就把撒谎计数拒成 ErbcDecodeException（CORRUPT_ARTIFACT）。
                val options = ArrayList<SpDtColumnOptions.DTOption>(optionCount)
                repeat(optionCount) {
                    val wire = cur.u8()
                    options.add(
                        when (wire) {
                            WireEnum.DT_DEFAULT -> SpDtColumnOptions.DTOption.DEFAULT
                            else -> throw ErbcDecodeException(cur.pos - 1, "未知 DTOption 线值: $wire")
                        },
                    )
                }
                SpDtColumnOptions(dt, column, options, readChildOptList(cur, -1))
            }

            ArgTag.SP_FOR_NEXT -> SpForNextArgument(
                varRef(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_GET_INT -> SpGetIntArgument(varRef(-1, cur.u32()))
            ArgTag.SP_HTML_PRINT -> SpHtmlPrint(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_HTML_SPLIT -> SpHtmlSplitArgument(childOpt(-1, cur.u32()), str(cur.u32()), varRefOpt(-1, cur.u32()))
            ArgTag.SP_INPUTS -> SpInputsArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_POWER -> SpPowerArgument(varRef(-1, cur.u32()), childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_PRINT_IMG -> SpPrintImgArgument(
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                mixedListOpt(cur),
            )

            ArgTag.SP_PRINT_SHAPE -> SpPrintShapeArgument(mixedList(cur))
            ArgTag.SP_PRINT_V -> SpPrintVArgument(readChildList(cur, -1))
            ArgTag.SP_SAVE_DATA -> SpSaveDataArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_SAVE_VAR -> SpSaveVarArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()), stringList(cur))
            ArgTag.SP_SET -> {
                val dest = varRef(-1, cur.u32())
                val term = childOpt(-1, cur.u32())
                val add = cur.bool()
                SpSetArgument(dest, term).apply { addConst = add }
            }

            ArgTag.SP_SET_ARRAY -> {
                val dest = varRef(-1, cur.u32())
                val terms = readChildOptList(cur, -1)
                val ci = longListOpt(cur)
                val cs = stringListOpt(cur)
                SpSetArrayArgument(dest, terms, ci, cs)
            }

            ArgTag.SP_SORTCHARA -> SpSortcharaArgument(varRef(-1, cur.u32()), sortOrder(cur.u8()))
            ArgTag.SP_SPLIT -> SpSplitArgument(
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                str(cur.u32()),
                varRefOpt(-1, cur.u32()),
            )

            ArgTag.SP_SWAP_CHARA -> SpSwapCharaArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            ArgTag.SP_SWAP_VAR -> SpSwapVarArgument(varRef(-1, cur.u32()), varRef(-1, cur.u32()))
            ArgTag.SP_TINPUTS -> SpTInputsArgument(
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_TIMES -> SpTimesArgument(varRef(-1, cur.u32()), cur.f64())
            ArgTag.SP_VAR_SET -> SpVarSetArgument(
                varRef(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
                childOpt(-1, cur.u32()),
            )

            ArgTag.SP_VARSIZE -> SpVarsizeArgument(str(cur.u32()))
            ArgTag.STR_DOUBLE -> StrDoubleArgument(childOpt(-1, cur.u32()), cur.f64())
            ArgTag.HTML_PRINT_LINE_END -> HtmlPrintArgument(childOpt(-1, cur.u32()), childOpt(-1, cur.u32()))
            else -> throw ErbcDecodeException(cur.pos - 2, "未知参数标签: $tag")
        }
        if ((header and 1) != 0) a.isConst = true
        a.constStr = constStr
        a.constInt = constInt
        return a
    }

    private fun readCaseList(cur: StreamCursor): List<CaseExpression> {
        val n = cur.count("CASE 分支数", 13)
        return ArrayList<CaseExpression>(n).apply {
            repeat(n) {
                val typeWire = cur.u8()
                val type = when (typeWire) {
                    WireEnum.CASE_NORMAL -> CaseExpression.CaseType.NORMAL
                    WireEnum.CASE_TO -> CaseExpression.CaseType.TO
                    WireEnum.CASE_IS -> CaseExpression.CaseType.IS
                    else -> throw ErbcDecodeException(cur.pos - 1, "未知 CaseType 线值: $typeWire")
                }
                val left = childOpt(-1, cur.u32())
                val right = childOpt(-1, cur.u32())
                add(CaseExpression(type, left, right, operatorName(cur.u32())))
            }
        }
    }

    private fun mixedList(cur: StreamCursor): List<MixedIntegerExprTerm> {
        val n = cur.count("px 参数表", 5)
        return ArrayList<MixedIntegerExprTerm>(n).apply {
            repeat(n) { add(MixedIntegerExprTerm(childOpt(-1, cur.u32()), cur.bool())) }
        }
    }

    private fun mixedListOpt(cur: StreamCursor): List<MixedIntegerExprTerm>? {
        val n = cur.u32()
        if (n == NONE_REF) return null
        if (n < 0 || n.toLong() * 5L > cur.remaining) {
            throw ErbcDecodeException(cur.pos - 4, "px 参数表长度 $n 至少需要 ${n.toLong() * 5L} 字节（剩余 ${cur.remaining}）")
        }
        val out = ArrayList<MixedIntegerExprTerm>(n)
        repeat(n) { out.add(MixedIntegerExprTerm(childOpt(-1, cur.u32()), cur.bool())) }
        return out
    }

    private fun intArray(cur: StreamCursor): IntArray {
        val n = cur.count("维度表", 4)
        return IntArray(n) { cur.i32() }
    }

    private fun stringList(cur: StreamCursor): List<String> {
        val n = cur.count("字符串表", 4)
        return ArrayList<String>(n).apply { repeat(n) { add(str(cur.u32())) } }
    }

    private fun stringListOpt(cur: StreamCursor): Array<String>? {
        val n = cur.u32()
        if (n == NONE_REF) return null
        // 与 ErbcFormat.kt 的 count() 同口径：先按「每项 4 字节」卡上界再分配，
        // 否则畸形产物里一个 0x7FFFFFFE 的计数就能让 Array(n) 抛 OutOfMemoryError ——
        // 那是 Error 不是 RuntimeException，越过 ProgramLoader.kt:100 的收口（:67-68 的承诺）。
        if (n < 0 || n.toLong() * 4L > cur.remaining) {
            throw ErbcDecodeException(cur.pos - 4, "常量字符串列表长度 $n 至少需要 ${n.toLong() * 4L} 字节（剩余 ${cur.remaining}）")
        }
        return Array(n) { str(cur.u32()) }
    }

    private fun longListOpt(cur: StreamCursor): LongArray? {
        val n = cur.u32()
        if (n == NONE_REF) return null
        // 同上：计数字段先按每项 8 字节卡上界，再进 LongArray(n)。
        if (n < 0 || n.toLong() * 8L > cur.remaining) {
            throw ErbcDecodeException(cur.pos - 4, "常量整数列表长度 $n 至少需要 ${n.toLong() * 8L} 字节（剩余 ${cur.remaining}）")
        }
        return LongArray(n) { cur.i64() }
    }

    // ------------------------------------------------------------------
    // 语句
    // ------------------------------------------------------------------

    fun readStatement(cur: StreamCursor): Statement {
        val tag = cur.u8()
        val line = cur.i32()
        val raw = str(cur.u32())
        val codeName = str(cur.u32())
        val code = functionCode(codeName)
        val s: Statement = when (tag) {
            StmtTag.PRINT -> PrintStmt(readPrintSpec(cur, code), argument(cur), line, raw)
            StmtTag.ASSIGN -> AssignStmt(
                dest = varRef(-1, cur.u32()),
                op = operatorName(cur.u32()),
                args = argument(cur),
                line = line,
                raw = raw,
            )

            StmtTag.JUMP -> JumpStmt(code, strOpt(cur.u32()), argument(cur), line, raw)
            StmtTag.CALL -> CallStmt(code, strOpt(cur.u32()), argument(cur), line, raw)
            StmtTag.CALL_F -> CallFStmt(functionName = strOpt(cur.u32()), args = argument(cur), line = line, raw = raw, code = code)
            StmtTag.CALL_SHARP -> CallSharpStmt(argument(cur), line, raw)
            StmtTag.METHOD -> MethodStmt(str(cur.u32()), argument(cur), line, raw)
            StmtTag.IF -> {
                // 读序必须与写序逐字一致：condition、single、然后才是参数记录
                val condition = child(-1, cur.u32())
                val single = cur.bool()
                IfStmt(condition, argument(cur), line, raw, single)
            }
            StmtTag.ELSE_IF -> ElseIfStmt(child(-1, cur.u32()), argument(cur), line, raw)
            StmtTag.ELSE -> ElseStmt(argument(cur), line, raw)
            StmtTag.END_IF -> EndIfStmt(argument(cur), line, raw)
            StmtTag.SELECT_CASE -> SelectCaseStmt(child(-1, cur.u32()), argument(cur), line, raw)
            StmtTag.CASE -> CaseStmt(readCaseList(cur), argument(cur), line, raw)
            StmtTag.CASE_ELSE -> CaseElseStmt(argument(cur), line, raw)
            StmtTag.END_SELECT -> EndSelectStmt(argument(cur), line, raw)
            StmtTag.WHILE -> WhileStmt(child(-1, cur.u32()), argument(cur), line, raw)
            StmtTag.WEND -> WendStmt(argument(cur), line, raw)
            StmtTag.DO -> DoStmt(argument(cur), line, raw)
            StmtTag.LOOP -> LoopStmt(childOpt(-1, cur.u32()), argument(cur), line, raw)
            StmtTag.CATCH -> CatchStmt(argument(cur), line, raw)
            StmtTag.END_CATCH -> EndCatchStmt(argument(cur), line, raw)
            StmtTag.RETURN -> ReturnStmt(readChildOptList(cur, -1), argument(cur), line, raw)
            StmtTag.RETURN_F -> ReturnFStmt(value = childOpt(-1, cur.u32()), args = argument(cur), line = line, raw = raw, code = code)
            StmtTag.FUNC_LABEL -> {
                val name = str(cur.u32())
                val params = stringList(cur)
                derivedArgument(cur)
                FuncLabelStmt(name, params, line, raw)
            }

            StmtTag.GOTO_LABEL -> {
                val name = str(cur.u32())
                derivedArgument(cur)
                GotoLabelStmt(name, line, raw)
            }

            StmtTag.SHARP -> {
                val keyword = str(cur.u32())
                val values = longList(cur)
                val name = strOpt(cur.u32())
                val isString = cur.bool()
                derivedArgument(cur)
                SharpStmt(keyword = keyword, values = values, name = name, isString = isString, line = line, raw = raw)
            }

            StmtTag.DATA -> DataStmt(code, str(cur.u32()), argument(cur), line, raw)
            StmtTag.PRINT_DATA -> PrintDataStmt(code, argument(cur), line, raw)
            StmtTag.VAR_DECL -> VarDeclStmt(code, argument(cur), line, raw)
            StmtTag.FOR -> ForStmt(code, argument(cur), line, raw)
            StmtTag.NEXT -> NextStmt(argument(cur), line, raw, code)
            StmtTag.REPEAT -> RepeatStmt(argument(cur), line, raw)
            StmtTag.REND -> RendStmt(argument(cur), line, raw)
            StmtTag.DATA_LIST -> DataListStmt(code, argument(cur), line, raw)
            StmtTag.END_BLOCK -> EndBlockStmt(code, argument(cur), line, raw)
            StmtTag.FLOW -> FlowStmt(code, argument(cur), line, raw)
            StmtTag.INSTRUCTION -> InstructionStmt(code, argument(cur), line, raw)
            else -> throw ErbcDecodeException(cur.pos - 1, "未知语句类标签: $tag")
        }
        // 类表与语句身份的一致性校验（派生 code 的类靠这一步兜住）
        if (s.code.name != codeName) {
            throw ErbcDecodeException(0, "语句 ${s.javaClass.simpleName} 的 code(${s.code.name}) 与记录里的 $codeName 不符")
        }
        if (s.raw != raw) {
            throw ErbcDecodeException(0, "语句 ${s.javaClass.simpleName} 的 raw 与记录不一致")
        }
        return s
    }

    private fun longList(cur: StreamCursor): List<Long> {
        val n = cur.count("#行数值表", 8)
        return ArrayList<Long>(n).apply { repeat(n) { add(cur.i64()) } }
    }

    private fun readPrintSpec(
        cur: StreamCursor,
        code: FunctionCode,
    ): PrintSpec {
        val argTypeName = str(cur.u32())
        val argType = try {
            ArgType.valueOf(argTypeName)
        } catch (e: IllegalArgumentException) {
            throw ErbcDecodeException(0, "未知 ArgType 名: $argTypeName", e)
        }
        val bits = cur.u16()
        val flag = cur.i32()
        return PrintSpec(
            code = code,
            name = code.name,
            argType = argType,
            isLineEnd = (bits and 0x001) != 0,
            isSingleLine = (bits and 0x002) != 0,
            isPrintV = (bits and 0x004) != 0,
            isS = (bits and 0x008) != 0,
            isForms = (bits and 0x010) != 0,
            isC = (bits and 0x020) != 0,
            isLC = (bits and 0x040) != 0,
            isK = (bits and 0x080) != 0,
            isD = (bits and 0x100) != 0,
            isN = (bits and 0x200) != 0,
            flag = flag,
        )
    }

    private fun functionCode(name: String): FunctionCode = try {
        FunctionCode.valueOf(name)
    } catch (e: IllegalArgumentException) {
        throw ErbcDecodeException(0, "未知 FunctionCode 名: $name", e)
    }
}
