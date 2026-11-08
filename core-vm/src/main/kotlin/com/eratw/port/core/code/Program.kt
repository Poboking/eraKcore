package com.eratw.port.core.code

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.host.MemoryTier
import com.eratw.port.core.sem.FlatStmtSource
import com.eratw.port.core.stmt.ArgInterner
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.SpCallSharpArgument
import com.eratw.port.core.stmt.Statement
import java.util.AbstractMap
import java.util.AbstractSet
import java.util.Iterator
import java.util.NoSuchElementException
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * 调用位的一个实参：被调形参位的定位信息（REF 绑定 + 具名形参写回共用）。
 *
 * spec: ProcessState.cs:435-452 —— 逐**头部形参位** i 写回：
 * REF 位 `SetRef(TransporterRef[i])`（:439-441）、int 位 `SetValue(TransporterInt[i])`（:445）、
 * str 位 `SetValue(TransporterStr[i])`（:449），`Arguments[i] == null` 的位跳过（:437）。
 * 实参侧求值见 UserDefinedFunctionArgument.cs:35-69（REF 位记 `TransporterRef` :43-58，
 * 其余按 `GetOperandType()` 分入 `TransporterInt[i]`/`TransporterStr[i]` :60-67）。
 *
 * 本移植侧帧槽只搬值、不搬变量身份，故调用点把每个实参位的「位置 / 类型桶号 / 变量名（若有）」
 * 登记进 [Program.callArgs]，由宿主在进入被调帧时按形参名逐位落库。
 *
 * @property position 实参位（0 起，与 `@F` 头部形参位一一对应）
 * @property bucket 该实参在**类型桶**里的槽号（int 桶或 str 桶，见 `Vm.callInto` 的槽复制）
 * @property isStr 表达式类型（true = 字符串通道）
 * @property name 实参是裸 [com.eratw.port.core.expr.VarRef] 时的变量名（REF 绑定用），否则 null
 * @property present 该位有实参（false = 省略位：原版 `Arguments[i] == null` 跳过不写，:437）
 */
data class CallArg(
    val position: Int,
    val bucket: Int,
    val isStr: Boolean,
    val name: String?,
    val present: Boolean,
)

/**
 * 调用点实参元数据的**来源**（诊断 + 兼容口径；`Program.callArgSource`）。
 *
 * 为什么需要这个标记：`.erbc` v1 容器没有承载 [Program.callArgs] / [FunctionInfo.paramNames]
 * 的段，装载出来的 Program 两者为空 —— 运行期 [com.eratw.port.core.proc.ProcessRuntime]
 * 会退回「类型桶 → ARG:i」的旧绑定（[Program.bindsCallArgsPositionally] = false）。这不是
 * 静默绑错（两条路径都不会把实参写给错误的形参），但 REF/具名形参在 v1 产物上是**不生效**的，
 * 必须能被上层查出来并报警，否则「真机上 REF 全废」会被当成正常输出。
 */
enum class CallArgSource {
    /** 编译期内存产物（erbcc 直跑 / 汇编器）：`callArgs` 由编译器登记。 */
    IN_MEMORY,

    /** v2 容器装载：段 7/8 存在则逐位复原；缺段（声明不带）时两者为空。 */
    ERBC_V2,

    /** v1 容器装载：容器没有这两块数据 ⇒ 绑定退化为类型桶，永不静默绑错。 */
    ERBC_V1,
}

/** 函数原型的常量池条目。 */
class FunctionInfo(
    val name: String,
    /** 入口指令下标。 */
    val entryPc: Int,
    /** 形参数（实参按位置复制到被调帧的 R0..R(arity-1)）。 */
    val arity: Int,
    /** 帧大小（帧内槽数，R0..R(slotCount-1)）。 */
    var slotCount: Int,
    /**
     * 形参名（`@F(A, B$)` → [A, B$]；装载侧 `FuncLabelStmt.paramNames`）。
     *
     * REF 形参绑定需要「实参位 → 被调形参」的**位置映射**，而形参名是唯一能定位被调函数
     * 私有 REF 变量的依据（REF 形参在 `#DIM` 里的声明序**不等于**头部形参序，语料实测
     * 63/539 个 REF 形参两者不同，故不能用声明序顶替）。空 = 装载侧未喂（此时 REF 实参
     * 绑定按"无信息"跳过，宁可 EmptyRefVar 也不猜）。
     */
    val paramNames: List<String> = emptyList(),
) {
    /**
     * 名字的大写归一（口径 = `FuncMeta.key` / `VariableStore` 作用域键，
     * spec: Config.cs:33 `StrComper = OrdinalIgnoreCase`）。
     *
     * PERF（2026-10-26）：[com.eratw.port.core.code.VirtualMachine] 的 `envAt` 在**每条语句**
     * 边界都要这个键（`Vm.kt` 的 marker 分支），旧实现每条语句调一次 `name.uppercase()`——
     * `String.uppercase` 在 ART 上走 ICU CaseMapper 逐字符分配，世界初始化那页实测占 VM 线程
     * 采样可观份额。函数名是构造期常量，按需缓存一次：未执行到的函数不额外占串（惰性），
     * 执行到的函数此后稳态零分配。值与原实现逐位相同（同一个 `name.uppercase()` 结果）。
     */
    private var upperKeySlot: String? = null

    /** 大写归一函数键（首次访问算一次并缓存，之后零分配）。 */
    val upperKey: String
        get() {
            val cached = upperKeySlot
            if (cached != null) return cached
            val u = name.uppercase()
            upperKeySlot = u
            return u
        }

    /**
     * 函数内 `$标签` 表（编译元数据，[CodeBuilder.declareLabel] / 装载侧填充）。
     * [labelNames]/[labelPcs] 平行数组：下标即 `_JUMP_DYN` 的 a 操作数，
     * 名字均为大写归一。erbcc 导出后只读。
     *
     * MEM-OPT（w7-progfuncs）：**惰性**。全量语料 110,072 个函数里绝大多数没有 `$标签`，
     * 旧实现让每个函数常驻两个空 `ArrayList` 壳（对象头 + 独立元素数组，约 24 B/个，
     * 合计约 5.3 MiB 常驻）。空壳与「装载后再编码逐字节相同」无关，纯属白占内存。
     * 现在槽位为 null 即共享 [emptyList]（Kotlin 空表单例，零分配）；
     * 只有真的登记过标签的函数才分配两个 `ArrayList`。
     *
     * 语义依据：原版的标签结构同样「按声明增量登记」——`LabelDictionary.cs:346`
     * `labelDollarList.TryAdd(...)` 只在某函数真的出现 `$标签` 时才建条目
     * （容器本身见 `LabelDictionary.cs:16`），无标签的函数在标签结构上零占用。
     * 读面口径不变：空表 `.size == 0`、`.indexOf(...) < 0`、越界 `[i]` 抛
     * IndexOutOfBoundsException（与旧空 `ArrayList` 一致）；[ErbcWriter] 写出的
     * 「标签数 = 0」字节与原实现完全相同（`ErbcWriter.kt:310`）。
     */
    private var labelNamesSlot: ArrayList<String>? = null
    private var labelPcsSlot: ArrayList<Int>? = null

    /** 标签名表（空 = 共享 [emptyList]；读语义与旧 `ArrayList` 一致）。 */
    val labelNames: List<String> get() = labelNamesSlot ?: emptyList()

    /** 标签 pc 表（与 [labelNames] 平行同长）。 */
    val labelPcs: List<Int> get() = labelPcsSlot ?: emptyList()

    /** 标签条数（平行两表的长度；空 = 0，不触碰列表实例）。 */
    val labelCount: Int get() = labelNamesSlot?.size ?: 0

    /**
     * 追加一条标签（编译期 / 装载期专用）：两表同步增长，下标 = 追加序。
     * 装载侧按容器里的「名字、pc」成对调用，顺序即容器序（`ErbcReader.kt:233-236`）。
     */
    internal fun addLabel(name: String, pc: Int) {
        val names = labelNamesSlot ?: ArrayList<String>().also { labelNamesSlot = it }
        val pcs = labelPcsSlot ?: ArrayList<Int>().also { labelPcsSlot = it }
        names.add(name)
        pcs.add(pc)
    }

    /**
     * 同名标签幂等登记（[CodeBuilder.declareLabel] 口径：同名只更新 pc，不新增条目）。
     * 返回标签下标。写面语义与旧实现的 `indexOf` + 覆写逐句等价。
     */
    internal fun putLabel(name: String, pc: Int): Int {
        val idx = labelNamesSlot?.indexOf(name) ?: -1
        if (idx >= 0) {
            labelPcsSlot!![idx] = pc
            return idx
        }
        addLabel(name, pc)
        return labelCount - 1
    }
}

/**
 * 语句池的**惰性供给**接缝（M10-MEM）。
 *
 * ## 为什么存在
 *
 * 装载侧（`core/loader` 的 `ErbcStreamDecoder`）过去把段 3 的全部语句记录一次解进堆：
 * 全量语料 1,716,637 条语句 = 语句外壳 62.6 MiB + args 子树 + 原文 131.0 MiB
 * （`perf-report` §2「statements 语句池（对象）」/「statements.raw 原文」），
 * 装载期常驻 +390 MiB、真机 512 MiB 挡位直接 OOM。本接口让装载只建**下标 → 文件偏移**的
 * 索引，条目在第一次被取用（`Vm` 的 marker 取指、`Program.statement(i)`）时才解码并缓存，
 * 于是「装载峰值」只与**真跑到的语句**成正比，而与语料规模无关。
 *
 * ## 语义边界（为什么这不改变任何脚本可见行为）
 *
 * - 下标与身份：同一个下标恒返回**同一实例**（实现内部缓存），契约 C2 的「一条记录 = 一个实例」
 *   与身份表口径不变 —— 数据节仍以语句实例为键（`sem/ExecContext.kt:96-100`）。
 * - 条目数：[totalCount] 取段头声明的记录条数，与逐条解码后的 `size` 恒等
 *   （索引趟就是逐条走完这套文法，任何一条解不出来都会在装载期抛出，与改造前同一句话）。
 * - 解码：实现方复用**同一个** `RecordSource`（`ErbcStream.kt` 里那份 `ErbcReader` 逐字副本），
 *   从记录自己的起始偏移重放同一段字节 ⇒ 解出的对象图与顺序解码逐字段相同。
 * - 越界：[statementAt] 越界抛 [IndexOutOfBoundsException]，与 `ArrayList.get` 同口径。
 * - 规范化去重：[seedInterning] 之后，每条物化出来的语句立刻过同一张规范表；
 *   按**下标升序**整池取用（`.erbc` 再编码、对拍装置）时，代表实例的选择与改造前
 *   「整池一次遍历」逐条相同 ⇒ `line`/`col` 与结构相等性都不漂。
 *
 * 实现只在 `core/loader`（装载侧）；编译期产物（`CodeBuilder`）永远不走这条接缝。
 */
internal interface LazyStatementPool {
    /** 段声明的语句条数（不物化任何条目）。 */
    val totalCount: Int

    /** 已物化条数（诊断/perf 装置读数；不参与任何取值）。 */
    val materializedCount: Int

    /** 第 [index] 条语句：未解码则解码并缓存。 */
    fun statementAt(index: Int): Statement

    /** 释放**已物化**语句的原文驻留；未物化的条目由实现方在后续解码时直接不带原文。 */
    fun releaseRaws()

    /**
     * 装载期规范化去重的种子段：把表达式池根灌进本池的规范表，之后的每次物化都按条去重。
     * 与 [Program.internStatementArgs] 的种子段同一口径（`statements` 读数扣掉种子行）。
     */
    fun seedInterning(roots: List<Expr?>): ArgInterner.Stats

    /**
     * 释放支撑惰性解码的映射与产物文件通道（CMP-19 的确定性释放点，见 [Program.releaseMmap]）。
     *
     * **前提**：调用之后运行期所有回读路径都必须已经落在 pin 住的映射里 —— 缺省实现返回
     * false（「本池没有可释放的通道」或「还有回读要走通道」），宿主据此保持现状。
     * 实现方只允许在「段窗口整段 pin + 串表数据块整块 pin」都成立时才关通道。
     *
     * @return true = 本池之后不再回读产物文件（通道已关，或本来就没有通道）
     */
    fun releaseMmap(): Boolean = false

    /**
     * T2-64 波次 D：把语句池的 `[from, until)` **按片流式压扁**成一段扁平流（[FlatStatements]）。
     *
     * 惰性挡逐条「解码 → 规范化去重 → 写入扁平流 → 当场弃树」，语句树不进池缓存
     * （[Program.materializedStatementCount] 不受影响）；这正是分片惰性池的建片动作
     * （`SegmentedFlatStmtSource.blockFor`）。
     *
     * 返回的下标**片内从 0 起**：调用方按 `index - from` 访问。
     */
    fun buildFlatStatementsRange(from: Int, until: Int): FlatStatements

    /**
     * CALLSHARP 装载趟扫描（CSHARP-WIRE C2）：返回本池里「语句记录标签 = CALL_SHARP」的
     * **池内下标**（升序；调用方按 `index + 池基线` 折回绝对下标）。
     *
     * 实现要求：**不物化语句** —— 惰性档逐条读记录首字节判标签（语句记录布局
     * `u8 StmtTag + i32 line + u32 raw + u32 codeName + …`，ErbcWriter.stmtHead 的写序与
     * `RecordSource.readStatement` 的读序逐字同源），命中 CALLSHARP 标签的条目才交给
     * 调用方走 [statementAt] 物化（那里才是身份/规范化的权威路径）。全量语料 1,716,636 条
     * 记录的一趟标签字节扫 ≈ 一次顺序映射读，语句对象零常驻。
     *
     * 缺省空表 = 供给方不认识本扫描时**宁可漏报**：漏报的行退回「首执行裁决」的旧路
     * （`SharpLinkTable` 未命中时在执行期记行错），不会误报；误报（把普通语句当 CALLSHARP
     * 物化出来）才是要防的方向。
     */
    fun sharpStatementIndices(): IntArray = IntArray(0)
}

/**
 * 函数表的**惰性供给**接缝（M11-MEM）。
 *
 * 装载侧过去把段 4 的全部函数记录一次解成 `ArrayList<FunctionInfo>`（全量语料 110,072 个），
 * 每个条目还带着函数名 `String` 与形参名列表。本接口让装载只留「每条记录的起始偏移」，
 * 元数据在 [Program.functions] 第一次按下标取用时才解码并缓存 —— 装载期常驻因此只与
 * 真正被调到的函数成正比。各字段在容器里**逐条自描述**：原版的
 * `reference/emuera-cs/MinorShift.Emuera.Runtime.Script.Statements/FunctionLabelLine.cs:19-53`
 * 里 `LabelName`/`ArgLength`/`LocalLength`/`Index` 都是按函数独立保存的，
 * 没有跨函数聚合才能得到的字段，所以按需解码与整池解码逐字段等价。
 *
 * 读面契约与 [LazyStatementPool] 同口径：同一下标恒返回同一实例；越界抛
 * [IndexOutOfBoundsException]；[validateRecords] 在装载期走一遍**不保留对象**的校验趟，
 * 畸形产物仍在装载期、在同一字节位置抛出同一句话。
 */
internal interface LazyFunctionTable : List<FunctionInfo> {
    /** 已解码条数（诊断读数；不参与取值）。 */
    val materializedCount: Int

    /** 装载期校验趟：逐条检查 entryPc / arity / slotCount / 标签 pc 的判界（不缓存）。 */
    fun validateRecords(instructionCount: Int)

    /** 是否有任一函数带形参名（段 8 声明）；不物化任何 [FunctionInfo]。 */
    fun hasAnyParamNames(): Boolean

    /**
     * 第 [index] 个函数的形参名（[hasAnyParamNames] 的逐条版本，同样**不物化** [FunctionInfo]）。
     *
     * 存在理由：`ProcessRuntime` 的局部长度注册要对全表逐条数 ARGF 形参，走
     * [Program.paramNames] 会把 M11-MEM 刻意按需解码的 110,072 条一次性击穿并**永久驻留**
     * （惰性表 `get` 写缓存）。无段 8 = 空表，与 `FunctionInfo.paramNames` 的缺席读数同值。
     */
    fun paramNamesAt(index: Int): List<String>
}

/**
 * 常量池 + 字符串池 + 表达式池 + 语句池。
 *
 * i64 立即数走 [Long] 池（[Instruction] 只放得下 u32，见该类的模式表），字符串走
 * **驻留池**（ADR-004："字符串走驻留池"），预解析表达式走 [Expr] 池（[_EVAL_INT]），
 * 数据面语句 AST 走**语句池**（[Statement]，契约 C2/C3 的 marker 操作数 `a`）。
 *
 * 去重用的 `HashMap<Long, Int>` 会在编译期产生装箱 —— 这是**构建期**开销
 * （erbcc 在 PC 上跑），不影响 VM 运行时；ADR-001 反对的是运行期装箱。
 *
 * 语句池自 M10-MEM 起可以是**惰性**的（[attachLazyStatementPool]）：此时 [statements] 保持空，
 * 下标读数走 [LazyStatementPool.totalCount]，取值走 [statementAt]。
 *
 * ## 基线（R7-L1 `codeBase` 偏移基座）：池是绝对下标空间上的**窗口**
 *
 * 补丁（`mods/` 里的口上 .ERB）在设备端单独编译，装载期拼在 base 指令流/池之后。为了让补丁的
 * 指令**直接**带绝对下标（marker 的 `a`、`_SET_INT` 的 `b`、`_SET_STR` 的 `b`、`_EVAL_*` 的 `a`），
 * 四个池各有一个**基线**（[longBase] / [stringBase] / [exprBase] / [statementBase]，默认 0）：
 * `add*` 返回 `base + 本池序号`，`*At` 取用与四个 `*Count` 读数都按绝对下标口径。
 *
 * **为什么在编译期偏移，而不是装载期 remap**（ADR-004 的操作数模式表是判决依据）：
 * 同一个 `a`/`b` 位可能是槽号、池下标、函数下标或跳转偏移，语义只由 opcode 决定；
 * 装载期要做等价改写就必须**遍历整段指令流**逐条识别操作数模式，漏判/错判一次就是静默错值
 * （某个角色念了别人的台词），而这条遍历本身也是 O(指令数) 的高风险动作。编译期只在
 * `add*` 的出口做一次加法：O(1)、不可能漏、且编译产物与「一次性全量编译」的同一段字节可比。
 * 反向污染由调用方负责规避：补丁必须用**独立** [ConstantPool]（`CodeBuilder.build()` 之后池被
 * [seal] 封死，且语句池按引用共享给已建 Program —— 见 `IncrementalCompileParityTest`）。
 *
 * 窗口语义（不许静默错读）：本池只持有绝对下标 `[base, base + 本池条数)` 的条目；
 * 取用 `base` 以下的绝对下标是**调用方的错**（那段归 base 池），按数组越界抛
 * [IndexOutOfBoundsException]（`index - base` 为负），不做「回落到 0」之类的纠正。
 * 快照类访问器（[longArray] / [stringArray] / [exprList] / [statementList]）只含本池条目。
 */
/**
 * 表达式池的**惰性供给**（2026-10-24 M12-EXPRLAZY）。
 *
 * 装载期只建「每条节点记录的起始偏移 + 池根下标」，`expr(k)` 首次访问时再按偏移重放那一条
 * 记录（`RecordSource.enableLazyNodes` 的既有机制，段 3 语句池同款）。动机是真机实测：
 * 段 2 节点表 5045 ms 里 **2703 ms 是 GC**、分配 351 MiB —— 898,118 个结点里约 44% 是同形
 * 重复实例；而运行期真正被 `_EVAL_INT/_EVAL_STR` 取到的池根只占少数。
 *
 * **并发口径**：与 `LazyStatementPool` 同一条（单线程 VM；第二线程接进来要自带同步）。
 * `node()` 的返回值必须逐字段等于整池解码的同一结点。
 */
internal interface LazyExprSource {
    /** 池根条数（= 段头声明的表达式池长度）。 */
    val nodeCount: Int

    /** 第 [index] 个池根（按需物化；同一下标恒同一实例由实现保证）。 */
    fun node(index: Int): Expr

    /**
     * 关掉产物文件通道之后，本池还能不能继续取值（CMP-19 的表达式侧判据）。
     *
     * 真 = 运行期取用全部落在装载期就 pin 好的映射里；假 = 还有回读要 `channel.map`，
     * 于是 [ConstantPool.releaseMmap] 不许关通道（宁可多留 1 fd）。缺省真：非映射档
     * （堆内表达式池 / 测试桩）本来就不碰产物文件。
     */
    val channelFreeForRuntime: Boolean get() = true
}

class ConstantPool(
    /**
     * 常量（Long）池基线：本池第 0 条在绝对下标空间里的位置（默认 0 = 无基线）。
     * 只由 [CodeBuilder] 的 `longBase` 喂入；装载链（`ErbcReader`/`ErbcStream`）恒用缺省值。
     */
    private val longBase: Int = 0,
    /** 字符串池基线；语义同 [longBase]。 */
    private val stringBase: Int = 0,
    /** 表达式池基线；语义同 [longBase]（惰性表达式池同样按绝对下标取用）。 */
    private val exprBase: Int = 0,
    /** 语句池基线；语义同 [longBase]（惰性语句池同样按绝对下标取用）。 */
    private val statementBase: Int = 0,
    /**
     * 组合池的 base（R7 波 C）：非 null 时本池是 base 之后的**续段**，
     * 所有 `*At` 与计数先问 base。null = 普通池（既有路径逐位不变）。
     *
     * 为什么要「组合」而不是「合并」：base 的语句池 1,100,720 条 / 表达式池 328,661 条
     * 是**惰性解码**的（装载时不常驻），整池拷进新池等于把几十 MB 重新物化一遍，直接
     * 撞装载峰值闸。于是装载期只把「绝对下标空间」拼起来：`index < base.<x>Count` 归
     * base，其余按本池基线折回。取用分派收在池内部，[Program] 的六个访问点一行都不用改。
     *
     * 本池只作为装载期拼装出来的**续段**：构造期必须为空（此时它是 base 的纯透传视图），
     * 之后只允许往续段追加（补丁那几十 KB），base 段永远不写 —— 所有 `add*` 的返回下标
     * 仍是 `基线 + 本段序号`，因为构造期不变式已经把基线钉在 base 的计数上。
     */
    private val basePool: ConstantPool? = null,
) {
    init {
        require(longBase >= 0) { "longBase 不能为负: $longBase" }
        require(stringBase >= 0) { "stringBase 不能为负: $stringBase" }
        require(exprBase >= 0) { "exprBase 不能为负: $exprBase" }
        require(statementBase >= 0) { "statementBase 不能为负: $statementBase" }
    }

    private val longs = ArrayList<Long>()

    /**
     * 去重索引（MEM-OPT）：只在编译期服务 [addLong]/[addString]/[addExpr] 的查重，
     * Program 构建完成即由 [seal] 置空释放。以全量语料量级估算，每条索引项
     * （HashMap.Node 32B + 装箱 Long 24B + 装箱 Integer 16B ≈ 72B），
     * 释放量 = 72B × (longCount + stringCount + exprCount)，实测值以 PERF-1 装置为准。
     */
    private var longIndex: HashMap<Long, Int>? = HashMap()

    private val strings = ArrayList<String>()
    private var stringIndex: HashMap<String, Int>? = HashMap()

    private val exprs = ArrayList<Expr>()

    /** 结构相等去重索引（data class hashCode 是一次性 O(树) 深度计算；线性扫 + 深度 equals
     *  在几十万表达式的语料规模下是 O(n²·树) 灾难）。同样随 [seal] 释放。 */
    private var exprIndex: HashMap<Expr, Int>? = HashMap()

    private fun checkWritable() {
        check(longIndex != null) {
            "常量池已冻结（Program 构建完成，去重索引已释放），不能再追加条目"
        }
    }

    /**
     * 语句池（契约 C2）：按**身份**追加，**严禁去重**。
     *
     * 依据：sem 的数据节供给以 `Statement` 实例为键 ——
     * `ExecContext.kt:96-100`「键为该 PRINTDATA 语句实例」`DataSections.groupsOf(stmt)`，
     * `ProcessRuntime.kt:488-495` `DataSectionSource`/`installDataSections`，
     * 消费点 `StatementExecutor.kt:1267,1286`。原版对应 FunctionIdentifier.cs:4285
     * `IsPrintData()`（数据节挂在具体语句行对象上，而非其结构值）。
     * 因此结构相等的两条语句必须是两个条目（两个下标）；同一实例重复追加同样**新开条目**，
     * 池不做任何形式的首次-fit 合并。与 [addExpr] 的结构相等去重是两套口径，勿混用。
     */
    private val statements = ArrayList<Statement>()

    /**
     * 组合池的构造期不变式（R7 波 C）：fail-fast，不做静默纠错。
     *
     * 四条基线必须**正好**接在 base 池的绝对计数之后 —— 差一点就是绝对下标空间里出现
     * 空洞或重叠，取用会静默落到错的池（或者干脆越界），而装载期拼出来的池没有第二处
     * 会去校验它。本池在构造期还必须还是空的：那一刻它是 base 的**纯透传视图**，
     * 带着条目进来只可能是「把别人的池接错位置」（基线对不上条目归属），续段条目
     * 应当由拼装方在构造之后追加（补丁那几十 KB）。
     *
     * 位置说明：本 init 必须排在四个列表属性的初始化器**之后**（Kotlin 按声明序执行
     * 初始化器与 init 块），否则读到的是尚未赋值的字段。
     */
    init {
        val base = basePool
        if (base != null) {
            require(longBase == base.longCount) {
                "组合池 longBase 必须等于 base 池的 longCount：期望 ${base.longCount}，实际 $longBase"
            }
            require(stringBase == base.stringCount) {
                "组合池 stringBase 必须等于 base 池的 stringCount：期望 ${base.stringCount}，实际 $stringBase"
            }
            require(exprBase == base.exprCount) {
                "组合池 exprBase 必须等于 base 池的 exprCount：期望 ${base.exprCount}，实际 $exprBase"
            }
            require(statementBase == base.statementCount) {
                "组合池 statementBase 必须等于 base 池的 statementCount：期望 ${base.statementCount}，实际 $statementBase"
            }
            require(longs.isEmpty() && strings.isEmpty() && exprs.isEmpty() && statements.isEmpty()) {
                "组合池构造期本池不许已有条目（续段应在构造之后追加）：" +
                    "long=${longs.size} string=${strings.size} expr=${exprs.size} statement=${statements.size}"
            }
        }
    }

    /** 惰性语句池供给（M10-MEM）；null = 常规池（[statements] 本体）。 */
    private var lazyStatements: LazyStatementPool? = null

    /** 原文是否已被释放（[releaseStatementRaws]）；惰性池的后续条目也要照此处理。 */
    private var rawsReleased = false

    /** 追加常量，返回**绝对**下标（`longBase + 本池序号`）。 */
    fun addLong(value: Long): Int {
        checkWritable()
        val idx = longIndex!!
        return longBase + idx.getOrPut(value) {
            longs.add(value)
            longs.size - 1
        }
    }

    /** 追加字符串，返回**绝对**下标（`stringBase + 本池序号`）。 */
    fun addString(value: String): Int {
        checkWritable()
        val idx = stringIndex!!
        return stringBase + idx.getOrPut(value) {
            strings.add(value)
            strings.size - 1
        }
    }

    /** 追加表达式，返回**绝对**下标（`exprBase + 本池序号`）；结构相等仍复用同一条目。 */
    fun addExpr(value: Expr): Int {
        checkWritable()
        check(lazyExprs == null) { "表达式池是惰性装载的（M12-EXPRLAZY），不能再追加条目" }
        // Expr 是 data class 树，结构相等即可复用；同一表达式在池里出现多次没有意义。
        val idx = exprIndex!!
        val existing = idx[value]
        if (existing != null) return exprBase + existing
        exprs.add(value)
        idx[value] = exprs.size - 1
        return exprBase + exprs.size - 1
    }

    /**
     * 常量取用（绝对下标）。组合池先问 base：`index < basePool.longCount` 归 base，
     * 其余按 `longBase` 折回本池序号；越界仍抛 [IndexOutOfBoundsException]（不静默回落）。
     */
    fun longAt(index: Int): Long {
        val base = basePool
        if (base != null && index < base.longCount) return base.longAt(index)
        return longs[index - longBase]
    }

    /** 字符串取用（绝对下标；组合池分派口径同 [longAt]）。 */
    fun stringAt(index: Int): String {
        val base = basePool
        if (base != null && index < base.stringCount) return base.stringAt(index)
        return strings[index - stringBase]
    }
    /** 惰性表达式供给（M12-EXPRLAZY）；null = 常规池（[exprs] 本体）。 */
    private var lazyExprs: LazyExprSource? = null

    /** 表达式池是否走惰性供给（装载侧据此决定要不要留住宿主文件）。 */
    internal val hasLazyExpressions: Boolean get() = lazyExprs != null

    /**
     * 接上一份惰性表达式供给（只由 `core/loader` 的装载链调用）。
     *
     * 次序要求与语句池同款：**先接惰性池、再建 `Program`** —— [seal] 之后 [addExpr] 不可用，
     * 而 [exprCount] 必须从接上那一刻起就是段头声明的条数（`validate` 的 `_EVAL_*` 判界读它）。
     */
    internal fun attachLazyExpressions(source: LazyExprSource) {
        check(exprs.isEmpty()) { "表达式池已有条目，不能改接惰性供给" }
        check(exprIndex != null) { "常量池已冻结，不能再改接惰性表达式供给" }
        lazyExprs = source
    }

    /**
     * 表达式池取用（绝对下标）：惰性池按 `index - exprBase` 物化，常规池同口径直取；
     * 组合池先把 `index < basePool.exprCount` 的那一段交给 base（不物化本池的任何条目）。
     */
    fun exprAt(index: Int): Expr {
        val base = basePool
        if (base != null && index < base.exprCount) return base.exprAt(index)
        return lazyExprs?.node(index - exprBase) ?: exprs[index - exprBase]
    }

    /**
     * 语句池追加（契约 C2）：恒新开条目并返回其下标；**不去重、不按身份合并**。
     * 下标即 marker 指令的 `a` 操作数（契约 C3：`op=语句操作码 29..197, a=池下标, b=0, c=NONE`）。
     *
     * 惰性池（[attachLazyStatementPool]）不接受追加：条目由段 3 的记录唯一决定，
     * 运行时追加会让「下标 = 容器记录序」这条契约失效。
     */
    fun addStatement(s: Statement): Int {
        check(lazyStatements == null) { "语句池是惰性装载的（段 3 索引表），不能再追加条目" }
        statements.add(s)
        return statementBase + statements.size - 1
    }

    /**
     * 接上一份惰性语句池供给（只由 `core/loader` 的装载链调用）。
     *
     * 接线次序是硬要求：**先接惰性池、再建 `Program`**，因为 [seal] 之后
     * [addStatement] 已不可用；而 [statementCount] 必须从接上那一刻起就是段声明的条数
     * （`ErbcStreamDecoder.validate` 的语句下标判界读它）。
     */
    internal fun attachLazyStatementPool(pool: LazyStatementPool) {
        check(statements.isEmpty()) { "语句池已有条目，不能改接惰性供给" }
        lazyStatements = pool
    }

    /** 语句池是否走惰性供给（装载侧据此决定要不要留住宿主文件）。 */
    internal val hasLazyStatementPool: Boolean get() = lazyStatements != null

    /** 给 [Program.internStatementArgs] 用的惰性池读数（非惰性池 = null）。 */
    internal fun lazyStatementPoolForInterning(): LazyStatementPool? = lazyStatements

    /**
     * 见 [Program.releaseMmap]：非惰性池在装载返回前就由装载层关掉了映射（`keepChannel=false`），
     * 所以这里恒报「不再回读产物文件」。
     *
     * **两条池都要点头才关**（CMP-19 补门）：语句池与表达式池共用一个产物通道，
     * 而 [LazyStatementRecords.releaseMmap] 只知道自己那两条回读路。表达式池在装载期
     * 没能把段 2 整段 pin 住时（串表数据块超 128 MiB，或映射建不出来静默退成分块窗口），
     * 它的运行期取用仍要换块 —— 先前只问语句池就把通道关了，症状是**玩几十分钟后**
     * 第一次取用某个没物化过的节点才炸「映射已关闭」。判据见 [LazyExprSource.channelFreeForRuntime]。
     *
     * **组合池再叠一层**（R7 波 C）：续段与 base 池各管自己的通道，本池不许替 base 决定
     * 关不关，所以先问 base —— 语义是「两者都允许才关」：base 说不行（它的串表太大、
     * 运行期还要换块）就直接 false，本段连自己那条通道都不关。顺序上必须 base 在前：
     * 通道一旦关掉就收不回来（JVM 不 unmap，已建映射仍可读），先关 base 再发现本段还要
     * 通道，就变成「base 已关、本段想开」的不可逆状态；反过来先问 base 则不会有这种状态。
     */
    internal fun releaseMmap(): Boolean {
        val base = basePool
        if (base != null && !base.releaseMmap()) return false
        val exprs = lazyExprs
        if (exprs != null && !exprs.channelFreeForRuntime) return false
        return lazyStatements?.releaseMmap() ?: true
    }

    /**
     * 已物化（= 已留在池缓存里、之后可原地回读）的语句条数（诊断/perf 装置口径）：
     * 常规池 = 池内条目数；惰性池 = 已经真的被**取用并缓存**的条数。
     *
     * T2-64 波次 C 的装载期「压扁即弃」**不经过**这条计数：压扁趟逐条解码后当场弃树，
     * 不进缓存 ⇒ 装载返回时惰性档仍恒为 0。
     *
     * 组合池（R7 波 C）只报**续段**读数：base 的物化量归 base 池自己（诊断口径不叠加，
     * 叠加会诱导「整池已物化」的误判，而 base 恰恰是按需解码的）。
     */
    internal val materializedStatementCount: Int
        get() = lazyStatements?.materializedCount ?: statements.size

    /**
     * T2-64 波次 D：产出 `[from, until)` 这一片的扁平流。惰性挡走按片流式压扁
     * （[LazyStatementPool.buildFlatStatementsRange]），常规挡（编译器/内存装置）语句本就在池里，
     * 直接对子表 encode。
     *
     * 入参是**绝对**下标（与 [statementCount] 同口径），按 `statementBase` 折回本池序号；
     * 返回的下标仍按「片内从 0 起」口径。
     *
     * 组合池（R7 波 C）按分片位置分三支：整片落在 base ⇒ 原样转给 base（零物化）；
     * 整片落在续段 ⇒ 走本池（惰性挡仍按片流式压扁）；**跨边界** ⇒ 只有这一支会把片内
     * 条目逐条取出来拼（片大小 4,096，与既有的「整片物化」同量级，不是整池拷贝）。
     */
    internal fun buildFlatStatementsRange(from: Int, until: Int): FlatStatements {
        val base = basePool
        if (base != null) {
            val boundary = base.statementCount
            if (until <= boundary) return base.buildFlatStatementsRange(from, until)
            if (from < boundary) {
                val slice = ArrayList<Statement>(until - from)
                for (i in from until until) slice.add(statementAt(i))
                return FlatStatements.encode(slice, emptyList())
            }
        }
        val f = from - statementBase
        val u = until - statementBase
        lazyStatements?.let { return it.buildFlatStatementsRange(f, u) }
        return FlatStatements.encode(statements.subList(f, u), emptyList())
    }

    /**
     * 语句池取用（绝对下标）：惰性池按 `index - statementBase` 物化，常规池同口径直取；
     * 组合池先把 `index < basePool.statementCount` 的那一段交给 base —— **本池的惰性供给
     * 一次都不碰**，这正是「取补丁段不触发 base 物化」的判据所在。
     */
    fun statementAt(index: Int): Statement {
        val base = basePool
        if (base != null && index < base.statementCount) return base.statementAt(index)
        val lazy = lazyStatements
        if (lazy == null) return statements[index - statementBase]
        val s = lazy.statementAt(index - statementBase)
        // 释放原文之后才物化的条目同样不落原文（口径与 releaseStatementRaws 一致）
        if (rawsReleased) s.releaseRaw()
        return s
    }

    /**
     * CALLSHARP 装载趟扫描（CSHARP-WIRE C2）：返回**绝对**下标升序的 CALLSHARP 语句候选。
     *
     * 组合池先把 base 段的候选原样并进来（base 返回的是它自己绝对空间里的下标，
     * 拼接后即本池的绝对下标 —— 与 [statementAt] 的 base 分派同一口径）；续段惰性档
     * 走 [LazyStatementPool.sharpStatementIndices]（记录标签字节扫，不物化），常规档
     * 直接按 args 形态识别（语句树已在堆内，`is SpCallSharpArgument` 就是权威判据 ——
     * `CallSharpStmt` 是唯一携带该参数形态的语句，ErbcWriter.writeStatement 的
     * `is CallSharpStmt -> stmtHead(…, StmtTag.CALL_SHARP, …)` 与此一一对应）。
     *
     * 候选 ≠ 裁决：名字是否常量、注册表是否命中都在 [com.eratw.port.core.fn.prefillSharpLinks]
     * （取用方）做 —— 本方法只回答「哪些下标值得物化一眼」。
     */
    internal fun sharpStatementIndices(): IntArray {
        val out = ArrayList<Int>()
        basePool?.let { base -> for (i in base.sharpStatementIndices()) out.add(i) }
        val lazy = lazyStatements
        if (lazy != null) {
            for (i in lazy.sharpStatementIndices()) out.add(statementBase + i)
        } else {
            for (i in statements.indices) {
                if (statements[i].args is SpCallSharpArgument) out.add(statementBase + i)
            }
        }
        return out.toIntArray()
    }

    /**
     * 语句池的只读视图（**不复制**，仅供装载期规范化去重遍历，见
     * [Program.internStatementArgs]）。对外快照仍用 [statementList]。
     *
     * 惰性池不走这里：整池遍历会把「按需解码」变回全量解码，去重由
     * [LazyStatementPool.seedInterning] + 逐条物化时代完成。
     */
    internal fun statementView(): List<Statement> {
        check(lazyStatements == null) { "惰性语句池没有整池视图；去重请走 seedInterning" }
        return statements
    }

    /**
     * 表达式池的**可写**视图（不复制，装载期规范化专用）。
     *
     * 只有 [Program.internStatementArgs] 的种子段会经过它：种子行把它整段交给
     * [ArgInterner]，池内根因此成为规范表的**首批代表**（语句池随后并入），
     * 元素的就地改写只可能换成结构相等的新实例（字符串归一/子树替换），
     * 逐下标的下标访问面与 data class 相等性不变。
     */
    internal fun exprRootsView(): List<Expr?> {
        // 惰性档没有整池视图：物化整池等于把「按需解码」退化回整池，且会立刻打爆堆。
        // 唯一消费者是 `Program.internStatementArgs` 的种子段，那里见到 [hasLazyExpressions] 会直接跳过。
        check(lazyExprs == null) { "表达式池是惰性装载的，没有整池视图" }
        return exprs
    }

    // ------------------------------------------------------------------
    // R7-C2 overlay 的续段逐位追加（只由 ProgramOverlay 在构造 Program 之前调用）
    // ------------------------------------------------------------------

    /**
     * 逐位追加一条常量，返回**绝对**下标（`longBase + 本池序号`）——**不走 [addLong] 的去重**。
     *
     * 只服务 [ProgramOverlay] 的续段拼装：补丁的池条目在绝对下标空间里与 base **位对位**——
     * 补丁指令的操作数是补丁编译期按基线算好的绝对下标，拼接必须让 `longAt(补丁下标)`
     * 精确返回补丁池那一条。若走 [addLong] 的去重，「与 base 同值」的条目会折回 base 的
     * 既有下标、不落新槽：补丁下标处变成空洞或错值，池计数也不再增长。所以这里绕过去重
     * 索引直接 `longs.add`——同值条目在组合池里允许出现两份（base 一份、续段一份），
     * 唯一权威是**下标自洽**（I2 线性自检校验的就是下标界）。
     *
     * 组合池的基线在构造期不变式里已钉在 base 的计数上，因此本方法的返回值与「补丁
     * 编译期算出的绝对下标」逐条对齐。
     */
    internal fun appendLongDirect(value: Long): Int {
        checkWritable()
        longs.add(value)
        return longBase + longs.size - 1
    }

    /** 字符串池的续段逐位追加；语义同 [appendLongDirect]（不走 [addString] 的去重）。 */
    internal fun appendStringDirect(value: String): Int {
        checkWritable()
        strings.add(value)
        return stringBase + strings.size - 1
    }

    /** 表达式池的续段逐位追加；语义同 [appendLongDirect]（不走 [addExpr] 的结构相等去重）。 */
    internal fun appendExprDirect(value: Expr): Int {
        checkWritable()
        check(lazyExprs == null) { "表达式池是惰性装载的（M12-EXPRLAZY），不能再追加条目" }
        exprs.add(value)
        return exprBase + exprs.size - 1
    }

    /**
     * 冻结（MEM-OPT）：释放三个去重索引（[longIndex]/[stringIndex]/[exprIndex]）的引用，
     * 之后任何 add* 抛 IllegalStateException。由 [Program] 构造期调用 —— 池条目
     * （longs/strings/exprs/statements 四个列表）原样保留，只读路径全部不受影响。
     * 语句池本就无索引，addStatement 在冻结后依旧可用（ErbcReader 重灌即依赖这一点）。
     *
     * 组合池（R7 波 C）：**只封本段，不许去 seal base**。base 池早由它自己的 `Program`
     * 构造期封过，续段视图再封一次没有意义，却会把「base 池还能不能被别人用」这件
     * 事替 base 决定掉（base 是共享的只读对象）。
     */
    internal fun seal() {
        longIndex = null
        stringIndex = null
        exprIndex = null
    }

    /**
     * 释放语句池全部原文驻留（MEM-OPT raw 槽化的第二半）：PERF-1 S2 账单
     * 「statements.raw」= 42,780,556 字符 ≈ 131.0 MiB，构造期经
     * [com.eratw.port.core.stmt.Statement.initRaw] 入槽，本方法逐条清空。
     * **调用时机契约**：只许发生在「编译 + `.erbc` 落盘 + 装载链自采原文
     * （SystemVariableBootstrap 的 RawDeclaration）全部结束之后」——运行期生产
     * 路径（Vm 取指 → ProcessRuntime → StatementExecutor）对 raw 零读者，
     * CodeEE 报错定位走 `Program.lines` + StmtEnv（ProcessRuntime.stampPosition）。
     * 由 [com.eratw.port.core.proc.ProcessRuntime] 构造期调用（compile → runtime
     * 交接处）。perf 池普查（PoolCensus/SizeModel）必须在建 ProcessRuntime
     * **之前**完成，之后 raw 一律为 ""。
     *
     * 惰性池（M10-MEM）：只清**已物化**的条目并立起 [rawsReleased] 标记，
     * 之后每条物化的语句在 [statementAt] 里当场清 raw ⇒ 池内语句的 raw 口径与整池遍历
     * 逐条一致，而不会因为「释放原文」这一件装配期小事把 171 万条语句全解码一遍。
     *
     * 组合池（R7 波 C）先转给 base 一次：续段接管运行期之后，base 池的 131 MiB 原文驻留
     * 没有第二个释放点（base 自己的 `Program` 已不再是运行期入口）。幂等 —— 已经清过的
     * 条目再清一遍就是空操作。
     */
    fun releaseStatementRaws() {
        basePool?.releaseStatementRaws()
        rawsReleased = true
        lazyStatements?.let { it.releaseRaws(); return }
        for (i in statements.indices) statements[i].releaseRaw()
    }

    val longArray: LongArray get() = longs.toLongArray()
    val stringArray: Array<String> get() = strings.toTypedArray()
    /**
     * 表达式池快照（`ErbcWriter`/反汇编往返用）：惰性档会**物化整池**，只许在诊断/落盘路径调。
     * 只含本池条目（绝对下标 `[exprBase, exprCount)`）；`exprBase` 恒 0 的装载链逐字不变。
     *
     * 组合池（R7 波 C）同样只吐**续段**：`exprBase until exprCount` 这段区间整体落在 base 之上，
     * base 的条目一条都不会被物化 —— 那正是「不合并池」要避免的拷贝。
     */
    val exprList: List<Expr>
        get() = if (lazyExprs == null) exprs.toList() else ArrayList<Expr>(exprCount - exprBase).apply {
            for (i in exprBase until exprCount) add(exprAt(i))
        }

    /**
     * 语句池快照（Assembler 往返用；顺序 = 追加序，下标稳定）。
     *
     * 惰性池的快照 = 按下标升序整池物化（M10-MEM）：升序物化与改造前的顺序解码同一口径，
     * 但会把这一池都拉进堆 —— 只有真的要整池快照的冷路径（汇编往返）才调它。
     *
     * 组合池（R7 波 C）只含**续段**条目（口径同 [exprList]）：base 的语句一条都不物化。
     */
    val statementList: List<Statement>
        get() {
            val lazy = lazyStatements ?: return statements.toList()
            val total = lazy.totalCount
            val out = ArrayList<Statement>(total)
            for (i in 0 until total) out.add(lazy.statementAt(i))
            return out
        }

    /**
     * 常量池条数（绝对口径：`longBase + 本池条数`）。
     *
     * 组合池下这条公式**不用改**：构造期不变式已经钉住 `longBase == basePool.longCount`，
     * 所以算出来正好是 `base 条数 + 续段条数`。
     */
    val longCount: Int get() = longBase + longs.size
    /** 字符串池条数（绝对口径；组合池口径同 [longCount]）。 */
    val stringCount: Int get() = stringBase + strings.size
    /** 表达式池条数（绝对口径；惰性档读段头声明值；组合池口径同 [longCount]）。 */
    val exprCount: Int get() = exprBase + (lazyExprs?.nodeCount ?: exprs.size)

    /**
     * 语句池条数：惰性池 = 段 3 声明的记录条数（不物化任何条目）；绝对口径。
     * 组合池口径同 [longCount]（`statementBase == basePool.statementCount`）。
     */
    val statementCount: Int get() = statementBase + (lazyStatements?.totalCount ?: statements.size)
}

/**
 * 编译产物：一段定长指令流 + 各池 + 函数表。
 *
 * 不可变（构建后不再写）。运行期取指走 [codeSource] 的**字视图**（VM 按页取指，
 * 见 [VirtualMachine] 的取指循环）：来源可以是堆内 `IntArray`（零拷贝、整段一页）
 * 或只读映射页（堆内常驻 0 字节，装载侧接入，见 [MappedCodeSource]）。
 * [code] / [lines] 保留为**兼容视图**：`.erbc` 落盘（`ErbcWriter`）、反汇编、逐字对拍装置
 * 仍按原签名读整段数组，但它们不再是运行期的取指来源。
 *
 * 内存口径（M6-CODE-VIEW）：`lines` 在构造期压成 2 B/条（[LineTable]），
 * `IntArray` 入参随即被丢弃 —— 4,800,579 条指令时行表 18.3 MiB → 9.2 MiB。
 * `code` 的 73.3 MiB 仍随 `IntArray` 来源常驻，但换成 [MappedCodeSource] 后堆内为 0。
 *
 * TODO(verify): `.erbc` 落盘格式（头/池/指令段）属 M1/M2 交界；映射来源的装载接线
 * 在 `core/loader` 一侧（本类只提供接缝，不读文件）。
 */
class Program private constructor(
    /** 指令流的字视图：VM 取指的唯一来源。 */
    val codeSource: CodeSource,
    /**
     * 已驻留的字数组（`IntArray` 来源 = 入参本体，与 [code] 同一实例）；映射来源为 null。
     */
    private val residentCode: IntArray?,
    /** 行号表（`lines` 的紧凑表示，见 [LineTable]）。 */
    private val lineTable: LineTable,
    private val pool: ConstantPool,
    val functions: List<FunctionInfo>,
    /** 入口函数下标。 */
    val entryFunction: Int,
    /** 全局槽总数（`G0..G(globalSlots-1)`）。 */
    val globalSlots: Int,
    callArgs: Map<Int, List<CallArg>>,

    /**
     * 这两块元数据的来源（[CallArgSource]）：`ERBC_V1` 即「v1 产物装载，逐位绑定已退化」。
     * 内存编译的 Program 恒为 [CallArgSource.IN_MEMORY]。
     */
    val callArgSource: CallArgSource,

    /**
     * 代码基线（R7-L1）：本 Program 的**第一段指令之前**的 base 指令流字数（`CodeSource.wordCount`
     * 同单位）。0 = 普通全量产物（既有路径逐位不变）。
     *
     * 非零时本 Program 是**绝对下标空间上的一个窗口**：`instructionCount` / 四个池计数都报
     * 绝对口径（供装载期算补丁区间 `[base, count)`），而 [code] / [lines] / [codeSource] 与
     * 池本体只持有窗口内的条目。只由 [CodeBuilder] 喂入；装载链恒用缺省 0。
     *
     * **不许直接执行**：pc 是绝对口径而 [codeSource] 只是窗口，取指会落到窗口之外。带基线的
     * Program 只是「补丁编译产物」，装载期必须与 base 拼成 `CompositeCodeSource`（并按基线
     * 合并池/函数表）之后才能交给 VM —— 拼接不在本类，见 R7 波 C 的接线。
     */
    val codeBase: Int = 0,
    /** 语句池基线（绝对下标口径；语义同 [codeBase]）。 */
    val statementBase: Int = 0,
    /** 字符串池基线（绝对下标口径）。 */
    val stringBase: Int = 0,
    /** 常量（Long）池基线（绝对下标口径）。 */
    val longBase: Int = 0,
    /** 表达式池基线（绝对下标口径）。 */
    val exprBase: Int = 0,
    /**
     * 函数表基线（绝对下标口径）。`functions` 本身是 `List`（无法开窗口），只含本段新增条目；
     * 绝对函数下标 = `functionBase + functions` 内序号，`_CALL` 的 `a` 已是绝对口径。
     */
    val functionBase: Int = 0,
) {
    /**
     * [codeBase] 的指令口径（= `codeBase / Instruction.WORDS`）：本段第一条指令的**绝对** pc。
     *
     * 所有收 pc 的读面（[op] / [a] / [b] / [c] / [instructionAt] / [lineOf]）都按绝对 pc 取值，
     * 内部折回窗口序号 `pc - instructionBase`；`base` 以下的 pc 归 base 产物，越界读会抛
     * [IndexOutOfBoundsException]（不静默回落）。
     */
    val instructionBase: Int get() = codeBase / Instruction.WORDS
    /**
     * 堆内指令字来源（编译期产物 / 既有装载链）：**零拷贝**包一层字视图，
     * [code] 仍是入参那个数组本体。行号表在此压实，入参数组不再被引用。
     */
    internal constructor(
        code: IntArray,
        lines: IntArray,
        pool: ConstantPool,
        functions: List<FunctionInfo>,
        entryFunction: Int,
        globalSlots: Int,
        callArgs: Map<Int, List<CallArg>> = emptyMap(),
        callArgSource: CallArgSource = CallArgSource.IN_MEMORY,
        codeBase: Int = 0,
        statementBase: Int = 0,
        stringBase: Int = 0,
        longBase: Int = 0,
        exprBase: Int = 0,
        functionBase: Int = 0,
    ) : this(
        codeSource = IntArrayCodeSource(code),
        residentCode = code,
        lineTable = LineTable.of(lines),
        pool = pool,
        functions = functions,
        entryFunction = entryFunction,
        globalSlots = globalSlots,
        callArgs = callArgs,
        callArgSource = callArgSource,
        codeBase = codeBase,
        statementBase = statementBase,
        stringBase = stringBase,
        longBase = longBase,
        exprBase = exprBase,
        functionBase = functionBase,
    )

    /**
     * 映射来源（只读页）：指令流不落堆，[code] 变成按需物化的兼容视图。
     *
     * 装载侧接入点（`core/loader` 的 mmap 接线，M6-CODE-VIEW 的剩余一半）：
     * 把 `SECTION_CODE` 的映射窗口与已校验的指令条数交给 [MappedCodeSource.ofLittleEndian]，
     * 再用本构造器建 Program ⇒ `code` 的 73.3 MiB 常驻归零。
     */
    internal constructor(
        codeSource: CodeSource,
        lines: IntArray,
        pool: ConstantPool,
        functions: List<FunctionInfo>,
        entryFunction: Int,
        globalSlots: Int,
        callArgs: Map<Int, List<CallArg>> = emptyMap(),
        callArgSource: CallArgSource = CallArgSource.IN_MEMORY,
        codeBase: Int = 0,
        statementBase: Int = 0,
        stringBase: Int = 0,
        longBase: Int = 0,
        exprBase: Int = 0,
        functionBase: Int = 0,
    ) : this(
        codeSource = codeSource,
        lineTable = LineTable.of(lines),
        pool = pool,
        functions = functions,
        entryFunction = entryFunction,
        globalSlots = globalSlots,
        callArgs = callArgs,
        callArgSource = callArgSource,
        codeBase = codeBase,
        statementBase = statementBase,
        stringBase = stringBase,
        longBase = longBase,
        exprBase = exprBase,
        functionBase = functionBase,
    )

    /**
     * 映射来源 + **已压实**的行号表：装载侧流式解码时直接交 `LineTable`，
     * 不再先建 19.6 MiB 的临时 `IntArray`（见 [LineTable.ofStreaming]）。
     */
    internal constructor(
        codeSource: CodeSource,
        lineTable: LineTable,
        pool: ConstantPool,
        functions: List<FunctionInfo>,
        entryFunction: Int,
        globalSlots: Int,
        callArgs: Map<Int, List<CallArg>> = emptyMap(),
        callArgSource: CallArgSource = CallArgSource.IN_MEMORY,
        codeBase: Int = 0,
        statementBase: Int = 0,
        stringBase: Int = 0,
        longBase: Int = 0,
        exprBase: Int = 0,
        functionBase: Int = 0,
    ) : this(
        codeSource = codeSource,
        residentCode = null,
        lineTable = lineTable,
        pool = pool,
        functions = functions,
        entryFunction = entryFunction,
        globalSlots = globalSlots,
        callArgs = callArgs,
        callArgSource = callArgSource,
        codeBase = codeBase,
        statementBase = statementBase,
        stringBase = stringBase,
        longBase = longBase,
        exprBase = exprBase,
        functionBase = functionBase,
    )

    /**
     * 指令字数组（**兼容视图**）：`IntArray` 来源时就是入参本体（身份不变，零拷贝）；
     * 映射来源时首次访问物化一次并缓存。
     *
     * 运行期**不读这里**（VM 取指走 [codeSource]）：物化会把映射来源的 73.3 MiB 重新拖回堆内，
     * 因此只有 `.erbc` 落盘、反汇编、逐字对拍这些冷路径允许碰它。
     *
     * 带基线（[codeBase] ≠ 0）时这里只有**本段**的字（下标 0 = 绝对 pc [instructionBase]），
     * 不含 base 段的零填充 —— 给 base 段补零会让补丁产物凭空多出整段 base 的字节。
     * 落盘/对拍需要全量视图时，按 [instructionBase] 自行拼接（补丁装载期的 `CompositeCodeSource`）。
     */
    val code: IntArray get() = residentCode ?: materializedCode

    private val materializedCode: IntArray by lazy { codeSource.toIntArray() }

    /**
     * 每条指令对应的源码行号（-1 = 未知），用于 CodeEE 报错定位。
     *
     * 兼容视图：由 [LineTable] 物化，整个 Program 生命周期内返回**同一实例**
     * （`ProcessRuntimeTest` 用 `assertSame` 锁「不复制第二份」）。
     * 只读：改它不会影响 [lineOf]（VM 报错定位走紧凑表）。
     * 带基线时同样只含本段条目（见 [code] 的同一条窗口说明）。
     */
    val lines: IntArray get() = materializedLines

    private val materializedLines: IntArray by lazy { lineTable.toIntArray() }

    /** 指令流的常驻堆字节（映射来源 = 0；perf 装置读数用）。 */
    val codeResidentBytes: Long get() = if (residentCode == null) 0L else ARRAY_HEADER + codeSource.wordCount * 4L

    /** 行号表的常驻堆字节（紧凑表示；perf 装置读数用）。 */
    val lineTableResidentBytes: Long get() = lineTable.bytes

    /**
     * 行号表是不是**稀疏分块形态**（MEM9 刀 2 的正面判据：映射装载档装载后堆上只剩块位表）。
     *
     * `false` = 整表形态（编译期产物 / 字节档 / 分块前提不成立时的退回），
     * `ProgramMmapReleaseTest` 拿它钉「不许静默退回全数组形态」。
     */
    internal val lineTableIsPaged: Boolean get() = lineTable.isPaged

    /** 行号表的块位数 / 已灌满的块数（整表形态恒 0；装置与单测读数用）。 */
    internal fun lineTableBlockStats(): Pair<Int, Int> = lineTable.blockCount() to lineTable.filledBlocks()

    // ------------------------------------------------------------------
    // R7-C2 overlay 装配读数（只由 ProgramOverlay 在拼装时取件）
    // ------------------------------------------------------------------

    /**
     * 行号表的引用（[ProgramOverlay] 拼装用）。
     *
     * [lineOf] 的拼接不在访问点上做分支，而是把 [LineTable] 换成 [LineTable.composite]
     * 的组合视图（纯分派，base 段零物化）。组合层需要 base 与补丁**两段**的 [LineTable]
     * 引用，而两者都是 private——这是装配期唯一的取件口；运行期读面（[lineOf] 与
     * [lines]）一个字节都不变。base 的行号表绝不允许物化再重建：真机 base 是
     * 4,884,338 条指令，物化即 19.5 MB（R7 内存红线）。
     */
    internal val overlayLineTableRef: LineTable get() = lineTable

    /**
     * 池的引用（[ProgramOverlay] 拼装用）。
     *
     * 组合池（[ConstantPool] 的 basePool 形态）按「base 段惰性直通 + 续段堆内」分派，
     * 需要 base 池的**引用**——绝不物化：base 的语句池 171 万条、表达式池 32 万条都是
     * 按需解码的（M10-MEM 与 M12-EXPRLAZY 的全部收益），整池物化等于把装载峰值闸撞穿。
     * `pool` 是 private，这是装配期唯一的取件口。
     */
    internal val overlayPoolRef: ConstantPool get() = pool

    /**
     * 调用点 pc → 该调用逐位实参元数据（[CallArg]）。**构造期规范化去重**（见
     * [canonicalCallArgs]）：结构相等的 [CallArg] 与等值实参表归一到同一实例。
     *
     * **v2 段 7 持久化**（`ErbcFormat.SECTION_CALL_ARGS`，规约 §5.8）：编译期由
     * `CodeBuilder.declareCallArgs` 登记，`ErbcWriter` 按 pc 升序写出，`ErbcReader` 逐字段复原。
     * v1 产物没有该段 ⇒ 装载后为空，绑定退化为类型桶（见 [callArgSource] 与
     * [bindsCallArgsPositionally]；不静默绑错，但 REF/具名形参不生效）。
     *
     * 读取面只有值语义：`ProcessRuntime.bindCallArgs` 逐位读 `present/bucket/isStr/name`
     * （`ProcessRuntime.kt:631-660`），无实例身份比较；`ErbcWriter` 只按字段重新编码。
     *
     * MEM9 刀 1：本体是 [CallArgIndex]（升序 pc 表 + 平行实参表引用两张数组），
     * **不再是 `LinkedHashMap`** —— 全量语料 151,120 个调用点的 Map 壳（节点 40 B + 装箱
     * `Integer` 键 16 B + 桶数组）在旧形态里常驻 9.1 MiB，而按结构归一后的独立数据只剩千级。
     * 公开类型 `Map<Int, List<CallArg>>` 与逐字段语义一个字节都没改（详见该类的「语义边界」）。
     */
    val callArgs: Map<Int, List<CallArg>> get() = callArgIndex

    /**
     * [callArgs] 的紧凑本体（MEM9 刀 1）：既是公开的 Map 视图，也是 [callArgsAt] 的索引。
     *
     * 旧形态在这里是**两份**：一张 `LinkedHashMap`（Map 语义）+ 一对二分侧车数组
     * （`callArgsPcs`/`callArgsVals`，取值语义），同一批数据两个副本（mem7 探针 §1 的
     * 「含 pcs/vals 二分侧车」那 10.7 MiB）。现在侧车数组就是 Map 自己的两张数组。
     *
     * **`this.` 不能省**：构造参数与属性同名 `callArgs`，Kotlin 让**构造参数**在属性初始化器
     * 里优先命中 —— 写成裸 `callArgs` 取到的是**去重前**的入参表（`PoolCallArgsDedupeTest`
     * 当场红：等值实参表没有归一）。`this.callArgs` 才是 [canonicalCallArgs] 的产物。
     */
    private val callArgIndex: CallArgIndex = CallArgIndex.of(canonicalCallArgs(callArgs))

    init {
        // MEM-OPT：构建即冻结 —— 编译期去重索引（恒等映射的 HashMap）在产物常驻内存里
        // 是纯死重，构建完成后没有任何读路径经过它们。
        pool.seal()
    }

    /**
     * 指令条数（**绝对**口径 = `instructionBase + 本段条数`）。
     *
     * 无基线时与 `codeSource.wordCount / Instruction.WORDS` 逐位相同。带基线时它报的是
     * 「base + 新增」—— 补丁装载期据此算区间 `[instructionBase, instructionCount)`，
     * `ErbcWriter`/`ErbcReader` 的 entryPc / 标签 pc / 调用点 pc 判界也据此与绝对 pc 对齐。
     * 注意 [code] / [lines] 仍只含本段条目（见 [codeBase] 的窗口语义）。
     */
    val instructionCount: Int get() = instructionBase + codeSource.wordCount / Instruction.WORDS

    /** 取操作数：pc 为**绝对** pc，内部折回窗口序号（无基线时折回量恒 0）。 */
    fun op(pc: Int): Int = codeSource.word((pc - instructionBase) * Instruction.WORDS)

    fun a(pc: Int): Int = codeSource.word((pc - instructionBase) * Instruction.WORDS + 1)

    fun b(pc: Int): Int = codeSource.word((pc - instructionBase) * Instruction.WORDS + 2)

    fun c(pc: Int): Int = codeSource.word((pc - instructionBase) * Instruction.WORDS + 3)

    /**
     * 解码第 [pc] 条指令（冷路径：反汇编/诊断）；[pc] 为**绝对** pc。
     *
     * 走当前页而不是 [code]：映射来源下物化整段会把 73.3 MiB 拖回堆内，
     * 而一条指令的 4 个字按页契约必在同一页内（[CodeSource.pageWords] 是 4 的倍数）。
     */
    fun instructionAt(pc: Int): Instruction {
        val w = (pc - instructionBase) * Instruction.WORDS
        val p = w / codeSource.pageWords
        return Instruction.decodeFrom(codeSource.page(p), w - p * codeSource.pageWords)
    }

    /**
     * 第 [pc] 条指令的源码行号（-1 = 未知）；越界同样回落 -1。[pc] 为**绝对** pc。
     *
     * marker 指令每条都要读（`Vm.envAt` 填 `StmtEnv.line`），故直接查紧凑表
     * （2 B/条、单次数组读），不经过 [lines] 的物化副本。
     * 带基线时行号表只覆盖本段，`base` 以下的 pc 折成负序号后按越界口径回落 -1。
     */
    fun lineOf(pc: Int): Int = lineTable.lineOf(pc - instructionBase)

    /** 常量池取用（绝对下标；带基线时 `index < longBase` 越界，见 [ConstantPool] 的窗口语义）。 */
    fun constant(index: Int): Long = pool.longAt(index)

    /** 字符串池取用（绝对下标）。 */
    fun string(index: Int): String = pool.stringAt(index)

    /** 表达式池取用（绝对下标）。 */
    fun expr(index: Int): Expr = pool.exprAt(index)

    /**
     * 语句池条目（契约 C2）；[index] = marker 指令的 `a` 操作数（**绝对**下标），
     * 越界抛 IndexOutOfBounds。
     */
    fun statement(index: Int): Statement = pool.statementAt(index)

    /**
     * CALLSHARP 装载趟扫描（CSHARP-WIRE C2）：CALLSHARP 语句候选的**绝对**下标（升序）。
     * 语义与成本见 [ConstantPool.sharpStatementIndices]；取用方 = core/fn 的
     * `prefillSharpLinks`（装载期联结裁决），本 accessor 只做池转发。
     */
    internal fun sharpStatementIndices(): IntArray = pool.sharpStatementIndices()

    /**
     * 语句池是否还是**惰性**的（M10-MEM）：装载侧据此决定产物文件要不要留在进程里 ——
     * 惰性池要在第一次取指时才回读段 3 的字节，通道必须还开着。
     */
    internal val holdsLazyStatementPool: Boolean get() = pool.hasLazyStatementPool

    /** 任一池（语句 / 表达式）走惰性供给：装载侧据此保留产物通道（M12-EXPRLAZY 新增表达式一项）。 */
    internal val holdsLazyAnyPool: Boolean
        get() = pool.hasLazyStatementPool || pool.hasLazyExpressions

    /**
     * 释放本 Program 对产物文件的**确定性释放点**（CMP-19；宿主 `close` 链的唯一出口）。
     *
     * ## 为什么需要它
     *
     * 惰性档（生产）装载时把通道留给 Program（`ProgramLoader.kt:413/419` 的
     * `keepChannel = holdsLazyStatementPool`），否则第一次 `CALL` 就撞「映射已关闭」。
     * 但 Program 原来**没有任何释放点**：`GameSession.close()` 只关输入队列 ⇒ 那个
     * `FileChannel`（1 fd）与 CODE ~76.8 MiB、段 3 整块、语句段局部串表 ~104.9 MiB 的映射
     * 跟着 Program 活到进程结束，每次 `Activity.recreate()` 漏一套。
     *
     * ## 前提（RECON §4 的隐情，**不许放宽**）
     *
     * 关通道只在「运行期所有回读路径都已落在 pin 住的映射里」时安全，否则惰性解码会
     * 在某次换块时撞 `映射已关闭`。装载侧因此做了两件事：
     * 1. 段 3 的记录/节点窗口在装载期整段 pin（`ErbcStream.kt` 的 `mapWindow(sectionFrom, sectionEnd)`）；
     * 2. 语句段局部串表的数据块在装载期整块 pin（`readStringTable(pinWhole = true)`），
     *    **前提是数据块 ≤ 128 MiB**（全量语料实测 104,923,457 B，落在区间内）；
     *    超过上限时窗口仍按 128 MiB 换块 ⇒ 本方法返回 false、通道保持原样。
     *
     * 关通道**不** unmap（JVM 语义）：已建映射块继续可读，所以「回到标题」复用同一会话
     * 也照常工作 —— 被释放的只是「还能再 map 一次」的能力，而上面两条保证已经不需要它。
     *
     * TODO(verify)：真机 `/proc/<pid>/fd` 的 fd 计数在 recreate 前后回落多少、以及
     * smaps 里这三块映射的 RSS 何时回收（JVM 单测只能证明通道真的关了；unmap 时机归 GC，
     * 需要装置读 smaps）。
     *
     * @return true = 之后不再回读产物文件（通道已关，或本来就没有通道）；
     *   false = 前提不成立（串表太大），通道保持打开。幂等：第一次的结果就是永久结果。
     */
    fun releaseMmap(): Boolean {
        mmapReleaseResult?.let { return it }
        val released = pool.releaseMmap()
        mmapReleaseResult = released
        return released
    }

    /** [releaseMmap] 的结果缓存（`null` = 还没调过；幂等判据）。 */
    @Volatile
    private var mmapReleaseResult: Boolean? = null

    /**
     * 已物化的语句条数（M10-MEM 的判据读数）：惰性装载完 = 0，
     * 之后每第一次取用一条就 +1。常规池 = 池内条目数（两者相等即「全池已物化」）。
     */
    val materializedStatementCount: Int get() = pool.materializedStatementCount

    val constantCount: Int get() = pool.longCount

    val stringCount: Int get() = pool.stringCount

    val exprCount: Int get() = pool.exprCount

    val statementCount: Int get() = pool.statementCount

    /**
     * T2-64 扁平语句池的运行期读面（[FlatStmtSource]）。null = 尚未构建 ——
     * 此时 VM marker 分支走对象树老路（[statement] + `Vm` 的 L1 备忘），与波次 A 前逐字一致。
     * 构建入口 [ensureFlatStmtSource]（装载链惰性档在装载返回前就建好；其余路径由 proc 桥
     * 构建期调用）；构建后不再变化。
     */
    internal var flatStmtSource: FlatStmtSource? = null
        private set

    /**
     * 分片惰性扁平源的**驻留片数**（EP3/L10 开放的诊断读面；源未构建 = 0）。
     *
     * 为什么要这一对：本体 `SegmentedFlatStmtSource` 是 `internal` 类、`residentSegments`/
     * `builtSegments` 也是 `internal` 面（`SegmentedFlatStmt.kt:73/:77`），模块外的取证探针
     * （`devtools/heap_phase_probe.py` 内嵌那份 Java）只能按名字前缀反射取，core 内的账面
     * （`core/diag/LiveSetCensus`）与 app 侧报告面就没有稳定名字可依赖。
     * **语义零改动**：只读 `blocks.size` 与累计计数器，不解码、不建片、不碰 LRU，
     * 也不代替 [flatStmtSource]（那条 `internal` 面留给同模块的 VM/proc/测试）。
     */
    val flatResidentSegmentCount: Int
        get() = (flatStmtSource as? SegmentedFlatStmtSource)?.residentSegments ?: 0

    /**
     * 分片惰性扁平源的**累计已建片数**（含已被 LRU 淘汰的；开放理由与只读口径同
     * [flatResidentSegmentCount]）。
     */
    val flatBuiltSegmentCount: Int
        get() = (flatStmtSource as? SegmentedFlatStmtSource)?.builtSegments ?: 0

    /** [ensureFlatStmtSource] 构建时实际用的片数上限（未构建 = 0）。 */
    @Volatile
    private var flatSegmentLimitUsed: Int = 0

    /**
     * 分片惰性扁平源的**片数上限**（[FlatPoolBudget.maxSegments] 落到构建点的那个数；
     * 源未构建 = 0）。开放理由与只读口径同 [flatResidentSegmentCount]：诊断/取证面要能看到
     * 「这台机的堆预算给了几片」，不靠反射去掏 [SegmentedFlatStmtSource] 的私有字段。
     *
     * 预算 [FlatPoolBudget.enabled] 为假但调用方显式开了 flat 时，实际构建的是 **1 片**
     * （见 [ensureFlatStmtSource]），这里读到的也是 1 —— 报的是「构建时实际用的值」，
     * 不是「预算判式的值」。诊断口 [buildSegmentedFlatSource] 不装进 [flatStmtSource]，
     * 因此**不写**这条读数（它建的源不是本 Program 的运行期池）。
     */
    val flatSegmentLimit: Int
        get() = flatSegmentLimitUsed

    /**
     * 构建/取回扁平语句池（幂等；单线程假设 ADR-003 —— 并发构建的两份产物语义相同，
     * 最后写入者胜，无正确性后果）。
     *
     * 调用时机：[flatStatementsEnabled] 为真时，由 proc 桥构建期
     * （`ProcessRuntime` 的 init）调用一次；[flatStatementsEnabled] 为假则从不构建，
     * VM marker 走对象树老路。
     *
     * **片数上限由堆预算推导**（[FlatPoolBudget.forHeap]）：`Runtime.maxMemory()` **只读一次**，
     * 同一个读数既喂档位判式（[MemoryTier.isGenerous]）又喂预算判式 —— 读两次会让两个判式
     * 在边界上互相打架（口径同 `ProgramLoader.estimateHeap` 的 `maxMemoryOverride` 注释）。
     * 堆 >= 512 MiB 给 32 片（保守档）/ 64 片（放开档）；更小的挡位预算为
     * [FlatPoolBudget.DISABLED]，此时**降到 1 片**而不是拒绝构建：显式开关
     * （`flat-statements.on` / Intent extra）的语义不能被预算悄悄改掉，只缩缓存容量
     * （LRU 淘汰不改变正确性，`StabilityL2FlatCacheTest` 已钉）。
     *
     * 成本：惰性挡走**流式**压扁（解码+规范化+写入一趟，≈8.9 s，语句树当场弃置）；
     * 常规挡（编译器/内存装置）才走整池 encode。常驻只剩扁平流本体。

     *
     * 表达式池传**空表**（poolSize = 0，语句树 Expr 全走旁挂）：刻意不触发惰性表达式池的
     * 整池物化（M12-EXPRLAZY 已消掉的 5.0 s / 351 MiB 装载成本不回填）；扁平常内下标自洽
     * （encode 用 IdentityHashMap 按身份去重旁挂），直读取回的 Expr 实例与对象树路径持有的
     * 完全同一（身份不换手），求值语义与句柄站点缓存全部照旧。
     */
    internal fun ensureFlatStmtSource(): FlatStmtSource {
        flatStmtSource?.let { return it }
        val maxMemoryBytes = Runtime.getRuntime().maxMemory()
        return ensureFlatStmtSource(FlatPoolBudget.forHeap(maxMemoryBytes, MemoryTier.isGenerous(maxMemoryBytes)))
    }

    /**
     * [ensureFlatStmtSource] 的**可注入预算**形态（定向单测专用）。
     *
     * 为什么需要它：`Runtime.maxMemory()` 是进程事实，测试没法伪造「256 MiB 挡」，而
     * 「预算 disabled ⇒ 降到 1 片」这条降级只能靠注入预算才钉得住（否则只能拿
     * [buildSegmentedFlatSource] 从旁边绕，测不到 [ensureFlatStmtSource] 的判式本身）。
     * 生产只走无参那条；本函数不读任何进程读数。
     */
    internal fun ensureFlatStmtSource(budget: FlatPoolBudget): FlatStmtSource {
        flatStmtSource?.let { return it }
        // 波次 D：**不再整池压扁**。返回一个分片惰性源，装载期零成本；某条语句第一次被执行时
        // 才建它所在的片（segmentStatements 条），片数由 LRU 卡住（见 SegmentedFlatStmtSource）。
        // 预算 disabled（maxSegments = 0）⇒ 1 片，理由见无参那条的 KDoc。
        val limit = if (budget.enabled) budget.maxSegments else 1
        val src = SegmentedFlatStmtSource(pool, this, budget.segmentStatements, limit)
        flatSegmentLimitUsed = limit
        flatStmtSource = src
        return src
    }

    /**
     * 分片惰性扁平源（**诊断/测试口径**：可指定片大小与片数上限，用来钉跨片与 LRU 淘汰）。
     * 生产走 [ensureFlatStmtSource] 的预算档（片大小 [FlatPoolBudget.SEGMENT_STATEMENTS]、
     * 片数按 [FlatPoolBudget.forHeap]）。本函数的签名与行为**逐字不变**（诊断口，
     * 不装进 [flatStmtSource]，也不写 [flatSegmentLimit]）。
     */
    internal fun buildSegmentedFlatSource(
        segmentSize: Int,
        maxSegments: Int,
    ): FlatStmtSource = SegmentedFlatStmtSource(pool, this, segmentSize, maxSegments)

    /**
     * 是否**允许**构建扁平语句池（装载期的 [com.eratw.port.core.loader.ProgramLoader.loadMapped] 的
     * `flatStatements` 参数写入）。false ⇒ 不建池，VM marker 走对象树老路。
     *
     * 这里的 `true` 只是「允许构建」，**不是**生产默认开：默认值由宿主决定 —— Android 侧
     * `GameHost` 读游戏目录旁的文件哨兵 `flat-statements.on`（默认不存在 ⇒ 关）或启动 Intent
     * 的 `eratwFlatStatements` extra，再把读数经 `loadMapped(flatStatements = …)` 写进来
     * （`app/.../GameHost.kt` 的 `loadAndRun` 在堆预检之前读一次、`assembleSession` 按同一个值建池）。
     * 桌面装置、vmdbg 与定向单测没有这个哨兵，读到的就是本字段的 `true`（保持波次 D 起的口径）。
     *
     * 旧 KDoc 写「Android 也默认开」，与 `GameHost` 的哨兵（默认不存在 ⇒ 关）互相矛盾，本刀
     * 就地订正：默认开/关的唯一判据在宿主，core 只提供开关与预算（[FlatPoolBudget]）。
     * 保留这个开关只为「关闭通道做 A/B」与「出问题时退回对象树」两条退路 ——
     * 见 `<internal-workdir>/CONTRACT-T2-64.md` §10.6/§10.8。
     */
    internal var flatStatementsEnabled: Boolean = true

    fun function(name: String): FunctionInfo? = functions.firstOrNull { it.name == name }

    /**
     * 调用点 pc 的逐位实参元数据（无则 null）；键 = `_CALL`/`_CALL_BY_NAME` 的 pc。
     *
     * 二分查找（见 [CallArgIndex]）：与 `callArgs[pc]` 取值语义相同，但**不装箱** `pc`。
     * 病根（PERF 2026-10-27 第二批的原始记录，形态换了、判据没换）：`Map<Int, …>` 每次查表
     * 都要 `Integer.valueOf(pc)`，而 `pc` 恒 > 127 不入 `IntegerCache`；`callArgsAt` 在
     * **每次进函数**都调一次（`ProcessRuntime.onFunctionEnter` → `bindCallArgs`，一轮 69.8 万次），
     * JFR 分配采样里这一处的 `Integer` 占执行循环内分配的 4.2%（43.0 MiB）。
     */
    fun callArgsAt(pc: Int): List<CallArg>? = callArgIndex.at(pc)

    /** 段 7 紧凑本体的常驻字节（两张平行数组；实参表/[CallArg] 另按对象图计）。 */
    val callArgIndexResidentBytes: Long get() = callArgIndex.residentBytes

    /** 函数下标 → 形参名（装载侧未喂 = 空）。 */
    fun paramNames(funcIndex: Int): List<String> = functions.getOrNull(funcIndex)?.paramNames ?: emptyList()

    /**
     * [paramNames] 的**不物化**版本：惰性表走段 8 的只读视图（[LazyFunctionTable.paramNamesAt]），
     * 常规表直接读条目（`get` 不新增常驻）。逐条读数与 [paramNames] 相同。
     */
    internal fun paramNamesPeek(funcIndex: Int): List<String> {
        val fns = functions
        if (fns is LazyFunctionTable) return fns.paramNamesAt(funcIndex)
        return functions.getOrNull(funcIndex)?.paramNames ?: emptyList()
    }

    /**
     * 已解码的函数元数据条数（M11-MEM 的判据读数）：惰性装载完 = 0，
     * 之后每第一次按索引取一个函数就 +1。常规表 = 表长（两者相等即「全表已物化」）。
     */
    val materializedFunctionCount: Int
        get() = (functions as? LazyFunctionTable)?.materializedCount ?: functions.size

    /**
     * 函数表的**只要名字**取用面（M13-MEM5 刀 1）：按 [FunctionInfo.name] 的**值**取名字，
     * 但**不构造 [FunctionInfo]**、不写惰性表缓存。存在理由：屏蔽表装配
     * （`EngineFunctionPolicy` / `StubTable.of`）每开一次「更精简的游戏选项」就要对
     * 110,072 个函数逐个查名，走 `functions[i].name` 会把段 4 整池物化并**永久驻留**
     * （mem3 实测 15.6 MiB 常驻、真机标题页同样付）。
     *
     * 桥形态：字节级读法（「记录起始偏移里的名字下标 + 段 4 局部串池」）在惰性表实现里
     * 已存在 —— `MappedFunctionTable.nameAt`（`loader/ErbcStream.kt:1042`，装载期报错
     * 路径在用）。本路无权把该面声明进 [LazyFunctionTable] 接口（那要求实现侧补一个
     * `override` 关键字，属 loader 文件；需求已按波次契约写进交付「需组长接线」），
     * 过渡期用方法查找接驳：查到 ⇒ 零常驻；查不到 ⇒ [FunctionNamePeek.nameAt] 返回 null，
     * 调用方退回 `functions[i].name`（行为与内存占用与改造前一致）。
     *
     * 返回串与 `FunctionInfo.name` **值相等**（同一条记录偏移、同一张局部串池的解码），
     * 不保证同实例；名字在本容器里逐函数唯一（装载索引趟 `seen.add` 判重），
     * 按值匹配与按实例匹配语义相同。
     */
    internal fun functionNamePeek(): FunctionNamePeek = FunctionNamePeek.of(functions)

    /**
     * 取用面句柄：建一次、表内逐下标复用（方法查找只付一次，不在 110k 循环里做）。
     * 常规（非惰性）表没有物化问题，直接读条目 —— 与惰性表不同，这里 `get` 不新增常驻。
     */
    internal class FunctionNamePeek private constructor(
        private val entries: List<FunctionInfo>?,
        private val table: LazyFunctionTable?,
        private val peekMethod: java.lang.reflect.Method?,
    ) {
        /** 第 [index] 个函数的名字；越界或桥不通 = null（调用方决定退回物化面）。 */
        fun nameAt(index: Int): String? {
            entries?.let { return if (index in it.indices) it[index].name else null }
            val t = table ?: return null
            val m = peekMethod ?: return null
            return runCatching { m.invoke(t, index) as? String }.getOrNull()
        }

        companion object {
            fun of(functions: List<FunctionInfo>): FunctionNamePeek =
                if (functions is LazyFunctionTable) {
                    val method = runCatching {
                        functions.javaClass
                            .getMethod("nameAt", Int::class.javaPrimitiveType)
                            .apply { isAccessible = true }
                    }.getOrNull()
                    FunctionNamePeek(null, functions, method)
                } else {
                    FunctionNamePeek(functions, null, null)
                }
        }
    }

    /**
     * 逐位实参绑定是否有数据可用：调用点表与形参名**两者都非空**才成立
     * （`ProcessRuntime.onFunctionEnter` 的判据同此口径：缺任一即退回类型桶绑定）。
     *
     * v1 产物装载后恒为 false —— 这就是「REF/具名形参在旧产物上失效」的可查标记。
     *
     * 惰性函数表（M11-MEM）：形参名是否非空由段 8 的「每函数条数」表直接回答，
     * 不为了这一条布尔判定把 110,072 个 [FunctionInfo] 全解出来。
     */
    val bindsCallArgsPositionally: Boolean
        get() {
            if (callArgs.isEmpty()) return false
            return hasAnyParamNames()
        }

    /**
     * 容器是否带段 8（有任何函数声明形参名）；惰性表由「每函数条数」表直接回答，
     * **不物化** [FunctionInfo]。与 [bindsCallArgsPositionally] 的区别是不问调用点表。
     */
    fun hasAnyParamNames(): Boolean {
        val fns = functions
        return if (fns is LazyFunctionTable) fns.hasAnyParamNames() else fns.any { it.paramNames.isNotEmpty() }
    }

    /** 表达式池快照（`_EVAL_*` 的 `$k` 操作数在反汇编往返时按此顺序复用）。 */
    fun expressions(): List<Expr> = pool.exprList

    /** 语句池快照（`stmt#k` 操作数在汇编往返时按此顺序复用；下标 = 追加序）。 */
    fun statements(): List<Statement> = pool.statementList

    /**
     * 释放语句池全部原文驻留（MEM-OPT，转发 [ConstantPool.releaseStatementRaws]）：
     * 只许发生在「编译 + `.erbc` 落盘 + 装载链自采原文全部结束之后」——生产运行期
     * 对 [Statement.raw] 零读者。由 [com.eratw.port.core.proc.ProcessRuntime]
     * 构造期调用；之后 [statement]/[statements] 返回的语句 raw 一律为 ""。
     */
    fun releaseStatementRaws() = pool.releaseStatementRaws()

    /**
     * 语句池 + 表达式池的**规范化去重**（M6-PERFINT / M6-MEMCODE）。
     *
     * 两段，共用同一个 [ArgInterner] 规范表（顺序不可换）：
     *
     * 1. **种子段**：表达式池的每个根（`_EVAL_INT`/`_EVAL_STR` 的 `$k`，[ConstantPool.exprRootsView]）
     *    先过一遍表，池内结点成为各形态的**代表实例**。池侧因此只可能被换成
     *    「字符串归一后重建」的同值实例，`.expr(k)` 的 data class 相等性（含 `line`/`col`）
     *    逐字段不变 —— 句柄稳定性由 `ErbcStreamEquivalenceTest`/`MmapLoaderTest` 锁死。
     * 2. **语句段**：语句池的 `args`（`IfStmt.condition` 等自带表达式字段同理）并入同一张表，
     *    与池内**逐字段全等（含 `line`/`col`）**的结点换成池内代表；只相差行列号的结点**不折**
     *    （INTERN-STMT，规约 `specs/erbc-format.md` §5.3「`line`/`col` 必须逐字往返……两条只差
     *    行列号的表达式会被错误合并」）。折叠判据一旦放行行列号差异，被换掉的语句结点会把
     *    池内代表的行列号带进落盘，`.erbc` 再编码随之漂移 ⇒ 本方法的两条装载路径
     *    （[com.eratw.port.core.loader.ProgramLoader.loadFile] 与
     *    [com.eratw.port.core.loader.ProgramLoader.loadMapped]）不再逐字节相同
     *    （`MappedCodeSourceTest` 真实产物用例）。
     *    方向只能是「语句侧让位」：反过来会让池根的 `line`/`col` 漂移，`program.expr(k)` 的
     *    逐字段对拍（装载差分测试）当场红。
     *
     * 为什么值得做（`PoolDecomp` §2b 实测）：两套对象图的**身份重叠为 0**
     * （`PoolDecomp` §2b「exprs 池有 0 个根与语句 args 是同一实例」），同一份表达式在
     * 池里（`_EVAL_*` 求值用）与语句树里（sem 执行用）各存一份；结构相等即语义等价
     * （池根与参数结点只被只读求值，见 `ArgInterner` 的语义边界）。
     *
     * 幂等：第二次调用不会再合并任何结点（[argsInterned] 已置位时直接返回上一次读数）。
     * 只许在「编译/落盘结束、运行期开始」的窗口调用；调用点：
     * [com.eratw.port.core.loader.ProgramLoader.load]/[com.eratw.port.core.loader.ProgramLoader.decodeMapped]
     * （`.erbc` 装载）与 [com.eratw.port.core.proc.GameSession]（源码直编产物）。
     *
     * 种子行是一个池外临时 [InstructionStmt]（`FunctionCode.__NULL__`，不进入任何池）：
     * [ArgInterner] 的入口只收语句，用它把表达式池整段灌进表，语句池本身不被改写池下标。
     *
     * @return 本次（或首次）装载的读数；`statements` 已扣除种子行。分享同一份 `Program`
     *   的调用方应把它当诊断读数。
     */
    internal fun internStatementArgs(): ArgInterner.Stats {
        argsInternedSlot?.let { return it }
        if (pool.hasLazyExpressions) {
            // M12-EXPRLAZY：表达式池不再整池物化 ⇒ 种子段（把池根灌进规范表）没有供给物，直接跳过。
            // 口径变化写清楚：语句侧的规范化去重**照旧**由 `LazyStatementRecords.statementAt`
            // 逐条做（同一张 ArgInterner 表），只是不再把参数结点折叠到「池内代表实例」上。
            // 换掉的是装载期 898,118 个结点的 5.0 s / 351 MiB / 9 次 GC（真机实测）。
            // `PoolExprShareTest` 的两条 `assertSame` 因此改成断言「惰性档不折池」。
            val skipped = ArgInterner.Stats(0, 0, 0, 0, 0L)
            argsInternedSlot = skipped
            return skipped
        }
        // 惰性池（M10-MEM）：只灌种子，语句侧的去重推迟到**每条物化时**由同一张规范表完成。
        // 按下标升序整池取用时，代表实例的选中次序与下面的「整池一次遍历」逐条相同。
        pool.lazyStatementPoolForInterning()?.let { lazy ->
            val stats = lazy.seedInterning(pool.exprRootsView())
            argsInternedSlot = stats
            return stats
        }
        val interner = ArgInterner()
        // 种子段：池根先入表（读数只取耗时，条数由下面的累计读数统一扣减）。
        val seedMs = interner.run(listOf(expressionPoolSeed())).ms
        val body = interner.run(pool.statementView())
        val stats = ArgInterner.Stats(
            statements = (body.statements - 1).coerceAtLeast(0),
            merged = body.merged,
            forms = body.forms,
            nodes = body.nodes,
            ms = body.ms + seedMs,
            // 串池读数必须一起交出去：`perfBaseline` 的「串池命中率」只有这一个来源，
            // 漏传就是 EMPTY（`Stats.strings` 的缺省值），报告里会显示「0 次请求 / 0% 命中」，
            // 看起来像串池没生效。种子段与语句段共用同一个 `interner.strings`，
            // 所以这里取到的就是**累计值**（`StringPool.Snapshot` 的 KDoc 同口径）。
            strings = interner.strings.snapshot(),
        )
        argsInternedSlot = stats
        return stats
    }

    /** 种子行：参数整段 = 表达式池（[ConstantPool.exprRootsView]），装载期临时对象。 */
    private fun expressionPoolSeed(): Statement = InstructionStmt(
        FunctionCode.__NULL__,
        ExpressionArrayArgument(pool.exprRootsView()),
        -1,
        "",
    )

    /** [internStatementArgs] 的首次读数（未跑过 = null）。 */
    internal val argsInterned: ArgInterner.Stats? get() = argsInternedSlot

    /**
     * 诊断：把规范化去重的读数拍成一行（未跑过 = null）。
     *
     * 公开只为一个消费者：宿主把它写进 `load.intern` 日志，用来回答「表达式池到底有多少**不同形态**」
     * —— 这个比值决定「边解析边规范化」能省多少常驻/GC（2026-10-24 段2 分析用）。
     */
    val internDiagnostics: String?
        get() = argsInterned?.let { "forms=${it.forms} merged=${it.merged} nodes=${it.nodes}" }

    private var argsInternedSlot: ArgInterner.Stats? = null

    /** 反汇编（调试/测试用）。 */
    fun disassemble(): String = Disassembler.disassemble(this)

    private companion object {
        /** 单个数组对象的堆头（`Integer[]`/`IntArray` 同类口径，`PoolDecomp` 同此估算）。 */
        const val ARRAY_HEADER = 16L
    }
}

/**
 * 行号表的紧凑表示（M6-CODE-VIEW）：4,800,579 条指令时 18.3 MiB → 9.2 MiB。
 *
 * ## 为什么能压
 *
 * 行号是**小的非负数**：一条指令的源码行号落在本文件的行号范围内（语料最大不过数万行），
 * 且绝大多数 ≥ 0 且 < 65533；`-1` 是编译器给"无行号"的哨兵（`CodeBuilder.emit` 默认值）。
 * 因此 u16 足够表达全部常见值：
 *
 * * `0x0000..0xFFFD`（0..65533）= 行号本身；
 * * `0xFFFE` = 溢出（行号 > 65533 或 <-1，查 [overflowPcs]/[overflowLines]）；
 * * `0xFFFF` = 未知（-1）。
 *
 * 溢出表按 pc 升序（构造期顺序追加），[overflowIndex] 二分定位：语料里行号 > 65533 的
 * 文件极少数，因此溢出表通常长度为 0，常驻就是 `count × 2 B`。
 *
 * ## 语义等价（为什么不变行为）
 *
 * [lineOf] 对**每个下标**返回与入参 `IntArray` 逐字相同的值（含越界回落 -1，与
 * `IntArray.getOrElse` 口径一致），[toIntArray] 则是同一函数的下标枚举 —— 两者互为对拍。
 * 行号只被读（`Program.lines` 的唯一消费者是 `ErbcWriter` 落盘与报错定位），没有写路径。
 *
 * ## 三种形态（MEM9 刀 2：装载即付全表 → 未取用不占；R7 波 C：再加组合）
 *
 * | 形态 | 谁在用 | 装载后常驻 |
 * |---|---|---|
 * | **整表**（[packed] ≠ null） | 编译期产物、`ErbcReader` 字节档（[of] / [ofStreaming]） | `count × 2 B` |
 * | **稀疏分块**（[blocks] ≠ null） | 映射装载档（[ofPaged]，装机唯一路径） | **块位表**（4,888,249 条 ⇒ 1,193 个块位 ≈ 4.7 KiB） |
 * | **组合**（[baseTable] ≠ null） | 装载期把补丁拼到 base 之后（[composite]） | 两个引用（两段常驻各自照旧） |
 *
 * 组合形态是**纯分派视图**，不拼、不物化：`pc < base.count` 归 base，其余折成
 * `pc - base.count` 问 tail。真机 base 4,884,338 条指令，拼接版是一张 19.5 MB 的
 * `IntArray`，每次导入 mod 付一次 —— 这正是组合形态要躲的那笔。它的 [bytes] 是两段相加
 * （自身没有主表/块位表/逃逸表），[toIntArray] 才会物化（冷路径）。
 *
 * 分块形态与 [com.eratw.port.core.loader.BlockU32s]（mem7 刀 C/刀 D）同一套形态与口径：
 * 块宽 [BLOCK] = 4,096 条（8 KiB 背衬），块在**第一次被取到**时由 [source] 回读段 6 的原始
 * i32、按同一条 [codeOf] 换算灌满，之后取值就是「块定位 + `ShortArray` 直取」。
 * 于是「装载即付的 9.3 MiB」变成「真跑到的 pc 才付」——**最坏情况全表灌满 = 9.5 MiB，
 * 与整表形态同量级，不会比改造前更贵**。
 *
 * 装载趟**仍然逐条读一遍**（[ofPaged] 的 [next]）：报错位置/文案与整表档逐字相同，
 * 溢出表也在这一趟建好（不读就不知道有没有 > 65533 的行号）。省下来的只是那张
 * `count × 2 B` 的堆内背衬。
 *
 * 判界口径（mem7 的教训，逐条钉住）：
 * - 整表形态下 `packed[pc]` 靠数组天然抛 AIOOBE；分块形态**没有那张大数组**，
 *   越界面全部由 [lineOf] 首行的 `pc < 0 || pc >= count` 显式挡住（回落 -1，与旧 `getOrElse`
 *   同口径），**绝不把越界读吞成 0**；
 * - 块位表越界（`blocks[bi]`）在两条路径上都不可能发生：[blocks] 的长度由 [count] 换算
 *   （[blockCountFor]），`bi = pc ushr BLOCK_SHIFT` 在 `pc < count` 时恒 `< blocks.length`；
 * - 末块不足一块（`count % 4096 != 0`）时 [blockLength] 给真实槽数，取用面拿不到块外下标；
 * - 组合形态沿用同一条越界判据（[lineOf] 首行显式挡，回落 -1），且 `pc < 0` 在分派**之前**
 *   回落 —— 负下标绝不被折成 base 的段内下标；
 * - 「静默退回全数组形态」有正面钉：`ProgramMmapReleaseTest.段6 分块形态：装载后零块、按 pc 取用才灌`
 *   直接断言映射装载出来的表 `isPaged == true` 且 `filledBlocks() == 0`。
 */
internal class LineTable private constructor(
    val count: Int,
    /** 整表形态的 u16 主表；**分块形态为 null**。 */
    private val packed: ShortArray?,
    /** 分块形态的块位表（每块一个 [ShortArray]，未取用 = null）；整表形态为 null。 */
    private val blocks: AtomicReferenceArray<ShortArray>?,
    /** 分块形态的原始行号供给（装载侧交出段 6 的映射窗口）；整表形态为 null。 */
    private val source: LineBlockSource?,
    private val overflowPcs: IntArray,
    private val overflowLines: IntArray,
    /**
     * **组合形态**的 base 段（R7 波 C）：非 null 时本表是 `base` 之后的续段视图，
     * [lineOf] 按 `pc < base.count` 分派。null = 普通表（整表 / 分块，既有路径逐位不变）。
     */
    private val baseTable: LineTable? = null,
    /** 组合形态的 tail 段（补丁那一段）；[baseTable] 非 null 时恒非 null。 */
    private val tailTable: LineTable? = null,
) {
    /**
     * 构造期不变式（fail-fast，不做静默纠错）。
     *
     * 组合形态是**纯分派视图**：它自己不许带 [packed] / [blocks] / [source]，也不许带逃逸表
     * （两段的逃逸项各归各的，[lineOf] 落到哪一段就由那一段自己查表）。`count` 必须恰好等于
     * `base.count + tail.count` —— 用 Long 相加再比，`Int` 回绕会被当场拒掉，而不是留下
     * 「计数对了但分派走错段」的缝。tail 为空表合法：此时 `count == base.count`，
     * 任何 `pc < count` 都落在 base、`pc >= count` 越界回落。
     *
     * 位置说明：本 init 紧跟构造参数之后、任何带初始化器的属性之前，读到的都是已赋值的
     * 构造参数（本类其余属性全是自定义 getter，没有初始化器与之竞争）。
     */
    init {
        require(count >= 0) { "行号表 count 不能为负: $count" }
        val base = baseTable
        if (base != null) {
            val tail = checkNotNull(tailTable) {
                "组合行号表缺 tail 段（baseTable 非 null 时 tailTable 必须给出）"
            }
            require(packed == null && blocks == null && source == null) {
                "组合行号表不许自带 packed / blocks / source：它是纯分派视图" +
                    "（packed=${packed != null} blocks=${blocks != null} source=${source != null}）"
            }
            require(overflowPcs.isEmpty() && overflowLines.isEmpty()) {
                "组合行号表不许自带逃逸表（两段各管各的）：" +
                    "overflowPcs=${overflowPcs.size} overflowLines=${overflowLines.size}"
            }
            val expect = base.count.toLong() + tail.count.toLong()
            require(count.toLong() == expect) {
                "组合行号表 count 必须等于 base.count + tail.count：期望 $expect，实际 $count"
            }
        } else {
            require(tailTable == null) { "非组合行号表不许带 tail 段" }
        }
    }

    /**
     * true = 稀疏分块形态（装载后堆上只有块位表；判据见类 KDoc 的「三种形态」）。
     *
     * 组合形态**任一段**是分块就算分块：`Program.lineTableIsPaged` 的调用方
     * （`HeapCompositionProbeTest` / `ProgramMmapReleaseTest`）拿它区分「段 6 没常驻」与
     * 「整表 ShortArray 常驻」—— base 是分块而组合层报 false，装置就会把「其实没常驻」
     * 当成「整表常驻」，读数直接反了。
     */
    val isPaged: Boolean
        get() {
            val base = baseTable
            if (base != null) return base.isPaged || tailTable?.isPaged == true
            return blocks != null
        }

    /**
     * 第 [pc] 条指令的行号；越界（pc < 0 或 ≥ [count]）回落 -1（旧 `getOrElse` 口径）。
     *
     * 组合形态按 `pc < base.count` 分派，`pc - base.count` 折回 tail 的段内下标。越界判据
     * 先于分派：`pc < 0` 直接回落，**不转给 base**（否则 -1 之类的负下标会变成 base 内部的
     * 另一个越界，口径就不再是「越界回落 -1」）。
     */
    fun lineOf(pc: Int): Int {
        if (pc < 0 || pc >= count) return UNKNOWN_LINE
        val base = baseTable
        if (base != null) {
            val bc = base.count
            return if (pc < bc) base.lineOf(pc) else checkNotNull(tailTable).lineOf(pc - bc)
        }
        val whole = packed
        val v = if (whole != null) {
            whole[pc].toInt() and 0xFFFF
        } else {
            val table = checkNotNull(blocks) { "LineTable 既无整表也无块位表（构造漏了形态）" }
            val bi = pc ushr BLOCK_SHIFT
            var b = table.get(bi)
            if (b == null) {
                val fresh = fillBlock(bi)
                b = if (table.compareAndSet(bi, null, fresh)) fresh else table.get(bi) ?: fresh
            }
            b[pc and BLOCK_MASK].toInt() and 0xFFFF
        }
        if (v != ESCAPE) return if (v == UNKNOWN) UNKNOWN_LINE else v
        val i = overflowIndex(pc)
        return if (i >= 0) overflowLines[i] else UNKNOWN_LINE
    }

    /**
     * 物化成兼容视图（`Program.lines` / `ErbcWriter` 用；运行期不调用）。
     *
     * 分块形态下这会把**所有块**灌满（≈ 全表 2 B/条）并留驻 —— 只有落盘/对拍这类
     * 冷路径允许付这一笔，运行期取行号一律走 [lineOf]。
     *
     * **组合形态调用它等于物化**：`count` 条一次性落成一张 `IntArray`（真机 base
     * 4,884,338 条 ⇒ 19.5 MB）。这是冷路径，运行期不许调 —— 运行期取行号走 [lineOf]，
     * 那才是零拷贝的分派。两段按下标直接写进同一张输出数组，**不**先各自物化再拼
     * （那会多出一张 base 大小的临时数组，正是本条要躲的那笔）。
     */
    fun toIntArray(): IntArray {
        val base = baseTable
        if (base == null) return IntArray(count) { lineOf(it) }
        val tail = checkNotNull(tailTable)
        val out = IntArray(count)
        val bc = base.count
        for (i in 0 until bc) out[i] = base.lineOf(i)
        for (i in 0 until tail.count) out[bc + i] = tail.lineOf(i)
        return out
    }

    /**
     * 常驻字节（perf 装置读数用）。
     *
     * * 整表形态：3 个数组头 + u16 主表 + 两张溢出表 —— **与改造前逐字同一条公式**
     *   （`CodeSourceViewTest` 钉 `3×16 + n×2`）；
     * * 分块形态：`AtomicReferenceArray` 壳 + 块位表 + **已灌的块** + 两张溢出表
     *   （装载后 = 块位表本体，4,888,249 条 ⇒ 1,193 槽 ≈ 4.8 KiB）；
     * * 组合形态：**两段相加**。它是纯分派视图，自身没有主表/块位表/逃逸表（只有两个
     *   引用字段），所以相加就是真实常驻。绝不能报「拼接后的整表大小」—— 这是装载峰值
     *   闸的读数，虚报会让闸失真，而这正是本形态存在的理由（不物化那张 19.5 MB）。
     */
    val bytes: Long
        get() {
            val base = baseTable
            if (base != null) return base.bytes + checkNotNull(tailTable).bytes
            val whole = packed
            if (whole != null) return 3 * 16L + count * 2L +
                overflowPcs.size * 4L + overflowLines.size * 4L
            val overflow = 2 * 16L + overflowPcs.size * 4L + overflowLines.size * 4L
            val table = blocks ?: return overflow
            // AtomicReferenceArray 壳 16 + 它背衬的 Object[] 头 16 + 块位槽 + 已灌的块
            var total = 32L + table.length() * 4L + overflow
            for (i in 0 until table.length()) table.get(i)?.let { total += 16L + it.size * 2L }
            return total
        }

    /**
     * 块位数（分块形态才有意义；整表形态返回 0）。
     *
     * 组合形态**两段相加**：这是「两张背衬表一共持多少块位」的真话，不是「组合 pc 空间
     * 折算出来的块数」—— 后者在两段交界处与任一段的块几何都对不上（`base.count` 不保证
     * 是 [BLOCK] 的整数倍），报出来会是看起来合理但错的数。调用方
     * （`Program.lineTableBlockStats` → `HeapCompositionProbeTest`）只用它印
     * 「N 块位 / 已灌 M 块」，相加正是它要的口径。
     */
    fun blockCount(): Int {
        val base = baseTable
        if (base != null) return base.blockCount() + checkNotNull(tailTable).blockCount()
        return blocks?.length() ?: 0
    }

    /**
     * 已灌满的块数（装载后应恒为 0；每条被取到的 pc 只给自己那一块付一次 8 KiB）。
     * 组合形态两段相加（口径同 [blockCount]）。
     */
    fun filledBlocks(): Int {
        val base = baseTable
        if (base != null) return base.filledBlocks() + checkNotNull(tailTable).filledBlocks()
        val table = blocks ?: return 0
        var n = 0
        for (i in 0 until table.length()) if (table.get(i) != null) n++
        return n
    }

    /**
     * 第 [blockIndex] 块的槽数（末块可能不足一块）。
     *
     * **组合形态不支持该读数**（明确抛错，不返回近似值）：它问的是「本表第 i 块的几何」，
     * 而组合形态有**两套**互不相同的块几何，且组合 pc 空间的块边界在两段交界处与任何一套
     * 都不重合。按哪一段回答都是错的。生产路径只在 [fillBlock] 里调它，而组合形态没有块位表，
     * 那条路走不到这里；测试面（`ProgramMmapReleaseTest` / `HeapCompositionProbeTest`）
     * 拿它量的是 `BlockU32s`，不是 `LineTable`。
     */
    fun blockLength(blockIndex: Int): Int {
        if (baseTable != null) {
            throw UnsupportedOperationException(
                "组合行号表不支持 blockLength（两段各有各的块几何，组合 pc 空间的块边界与两段都不重合）: " +
                    "blockIndex=$blockIndex",
            )
        }
        return minOf(BLOCK.toLong(), count.toLong() - blockIndex.toLong() * BLOCK).toInt()
    }

    /**
     * 块定位：pc → 块号。
     *
     * **组合形态不支持该读数**（明确抛错）：tail 段的段内块号是
     * `(pc - base.count) ushr BLOCK_SHIFT`，与本式算出的 `pc ushr BLOCK_SHIFT` 在
     * `base.count` 不是 [BLOCK] 整数倍时**不是同一个数** —— 返回后者就是静默错值。
     */
    fun blockIndexOf(pc: Int): Int {
        if (baseTable != null) {
            throw UnsupportedOperationException(
                "组合行号表不支持 blockIndexOf（tail 段块号要按 pc - base.count 折算，" +
                    "与本式在段界处不同）: pc=$pc",
            )
        }
        return pc ushr BLOCK_SHIFT
    }

    private fun fillBlock(blockIndex: Int): ShortArray {
        val src = checkNotNull(source) { "分块形态没有行号供给（source = null）" }
        val n = blockLength(blockIndex)
        val raw = IntArray(n)
        src.read(blockIndex, raw, n)
        val out = ShortArray(n)
        for (i in 0 until n) out[i] = codeOf(raw[i])
        return out
    }

    private fun overflowIndex(pc: Int): Int {
        var lo = 0
        var hi = overflowPcs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val at = overflowPcs[mid]
            when {
                at == pc -> return mid
                at < pc -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }

    companion object {
        /** "无行号"哨兵（与 `CodeBuilder.emit(line = -1)` 同口径）。 */
        const val UNKNOWN_LINE: Int = -1

        private const val UNKNOWN = 0xFFFF
        private const val ESCAPE = 0xFFFE
        private const val DIRECT_MAX = ESCAPE - 1

        /**
         * 块宽 4,096 条 = 8 KiB 背衬：**与 [com.eratw.port.core.loader.BlockRefs]/[BlockU32s]
         * 同一条**（mem7 定的口径，装载后块位表 ≤ 4.7 KiB；全表灌满时也只是回到整表形态的量级）。
         */
        const val BLOCK_SHIFT = 12
        const val BLOCK = 1 shl BLOCK_SHIFT
        const val BLOCK_MASK = BLOCK - 1

        /** [count] 条行号需要几个块位（0 条 ⇒ 0 个块，块位表是零长数组）。 */
        fun blockCountFor(count: Int): Int = if (count <= 0) 0 else ((count - 1) shr BLOCK_SHIFT) + 1

        /**
         * 单个行号 → u16 码（**唯一的换算点**：[of] / [ofStreaming] 的整表压实与
         * [fillBlock] 的分块压实共用这一条，两条形态不可能算出不同的码）。
         */
        private fun codeOf(l: Int): Short = when {
            l == UNKNOWN_LINE -> UNKNOWN.toShort()
            l in 0..DIRECT_MAX -> l.toShort()
            else -> ESCAPE.toShort()
        }

        /** [codeOf] 的溢出判据（进 [overflowPcs]/[overflowLines] 的那一档）。 */
        private fun isOverflow(l: Int): Boolean = l != UNKNOWN_LINE && (l < 0 || l > DIRECT_MAX)

        /**
         * 从**流式读出的行号**直接压实 —— 不先建整段 `IntArray`。
         *
         * 4,888,794 条指令时临时 `IntArray` 是 19.6 MiB（M5-BOOT 的装载峰值余量）；
         * 装载侧逐条 `i32` 读时直接写进 [ShortArray]，溢出项（负数或 > 65533 的行号，
         * 语料实际为 0）才进两张稀疏表。与 [of] 产物逐条等价。
         */
        fun ofStreaming(count: Int, next: () -> Int): LineTable {
            val packed = ShortArray(count)
            var pcs: ArrayList<Int>? = null
            var vals: ArrayList<Int>? = null
            for (i in 0 until count) {
                val l = next()
                packed[i] = codeOf(l)
                if (isOverflow(l)) {
                    val p = pcs ?: ArrayList<Int>().also { pcs = it }
                    val v = vals ?: ArrayList<Int>().also { vals = it }
                    p.add(i)
                    v.add(l)
                }
            }
            return LineTable(
                count,
                packed,
                null,
                null,
                pcs?.toIntArray() ?: IntArray(0),
                vals?.toIntArray() ?: IntArray(0),
            )
        }

        /**
         * **稀疏分块形态**（MEM9 刀 2）：装载趟与 [ofStreaming] 走同一条读序
         * （[next] 逐条 `i32`，报错位置/文案逐字相同）并把溢出表建好，但**不把 u16 主表
         * 留在堆上** —— 之后第 i 块第一次被取到时才由 [source] 回读段 6 的原始 i32、
         * 按同一条 [codeOf] 灌满。
         *
         * 与 [ofStreaming] 的产物逐条等价（`LineTablePagedTest` 拿含溢出/含 -1/含边界的
         * 合成表对拍；真产物面见 `MmapLoaderTest` 的 `lines` 全量差分）。
         */
        fun ofPaged(count: Int, next: () -> Int, source: LineBlockSource): LineTable {
            var pcs: ArrayList<Int>? = null
            var vals: ArrayList<Int>? = null
            for (i in 0 until count) {
                val l = next()
                if (isOverflow(l)) {
                    val p = pcs ?: ArrayList<Int>().also { pcs = it }
                    val v = vals ?: ArrayList<Int>().also { vals = it }
                    p.add(i)
                    v.add(l)
                }
            }
            return LineTable(
                count,
                null,
                AtomicReferenceArray(blockCountFor(count)),
                source,
                pcs?.toIntArray() ?: IntArray(0),
                vals?.toIntArray() ?: IntArray(0),
            )
        }

        /** 从完整行号表压实（入参数组不被保留，构造完即可回收）。 */
        fun of(lines: IntArray): LineTable {
            var escapes = 0
            for (l in lines) if (isOverflow(l)) escapes++
            val pcs = IntArray(escapes)
            val vals = IntArray(escapes)
            val packed = ShortArray(lines.size)
            var e = 0
            for (i in lines.indices) {
                val l = lines[i]
                packed[i] = codeOf(l)
                if (isOverflow(l)) {
                    pcs[e] = i
                    vals[e] = l
                    e++
                }
            }
            return LineTable(lines.size, packed, null, null, pcs, vals)
        }

        /**
         * **组合形态**（R7 波 C）：pc 空间 = base 的全部 pc，其后紧接 tail 的全部 pc。
         *
         * ## 为什么是分派而不是拼接
         *
         * 真机 base 是 4,884,338 条指令 ⇒ 拼成一张 `IntArray` 是 19.5 MB，而且
         * `Program.lines` 是惰性物化（一碰就付）。导入一次 mod 就付一次，正好压在
         * 512 MiB 堆、已实测 422 MiB 的那条线上。组合形态只持两个引用：取行号按
         * `pc < base.count` 分派，零拷贝、零物化。
         *
         * ## 分派与越界口径
         *
         * 越界（`pc < 0` 或 `pc >= count`）仍回落 [UNKNOWN_LINE]，**不抛错** —— 与普通表的
         * `IntArray.getOrElse` 口径逐字相同。`pc < 0` 在分派之前就回落，绝不转给 base
         * （转过去就是 base 内部的另一个越界，口径不再一致）。`pc >= count` 同样先挡：
         * 落进 tail 的下标若靠 tail 自己判界虽然结果相同，但那是两条不同的判界路，
         * 组合层自己钉住才不会被后来的改动撬开。
         *
         * ## 构造期不变式（fail-fast，不做静默纠错）
         *
         * 两段的 `count` 都必须非负；总条数用 Long 相加后再判不溢出 `Int`（`Int` 回绕
         * 会让 `count` 变成一个负数或小数，而它正是分派与越界判据的唯一依据）。
         * 组合视图自身**不许**带 packed / blocks / source 与逃逸表 —— 它是纯分派视图，
         * 两段的逃逸表各管各的（[lineOf] 落到哪一段就由那一段自己查表，组合层不吞）。
         *
         * tail 为空表合法：此时 `count == base.count`，任何 `pc < count` 都落在 base，
         * `pc >= count` 越界回落 —— 不存在「count 对了但 lineOf 走错段」的缝。
         *
         * @param base 已装载产物的行号表（分块/整表形态均可）
         * @param tail 补丁那一段的行号表（段内下标从 0 起，与补丁自己的 pc 空间一致）
         */
        fun composite(base: LineTable, tail: LineTable): LineTable {
            require(base.count >= 0) { "组合行号表 base.count 不能为负: ${base.count}" }
            require(tail.count >= 0) { "组合行号表 tail.count 不能为负: ${tail.count}" }
            val total = base.count.toLong() + tail.count.toLong()
            require(total <= Int.MAX_VALUE.toLong()) {
                "组合行号表总条数溢出 Int：base.count=${base.count} + tail.count=${tail.count} = $total"
            }
            return LineTable(
                count = total.toInt(),
                packed = null,
                blocks = null,
                source = null,
                overflowPcs = NO_ESCAPES,
                overflowLines = NO_ESCAPES,
                baseTable = base,
                tailTable = tail,
            )
        }

        /** 组合形态自身的逃逸表（恒空；两段的逃逸项各在各自表里）。共享一份，不逐次分配。 */
        private val NO_ESCAPES = IntArray(0)
    }
}

/**
 * 段 6 行号的**块供给**（MEM9 刀 2；实现只在 `core/loader` 的 [com.eratw.port.core.loader.ErbcStream] 里，
 * 那里才有映射窗口）：把第 [blockIndex] 块的 [length] 个**原始 i32 行号**（未压实、未换算，
 * 与段 6 落盘逐字相同）写进 [out] 的 `[0, length)`。
 *
 * 压实与溢出判定**不在这里**（见 [LineTable] 的 `codeOf` 是唯一换算点）：本接口只搬原始值，
 * 于是「整表档」与「分块档」读到的字节、算出的码必然同源。
 */
internal fun interface LineBlockSource {
    fun read(blockIndex: Int, out: IntArray, length: Int)
}


/**
 * 指令流构建器。erbcc 与汇编器共用；写完后 [build] 冻结为 [Program]。
 *
 * 数组按 2 倍增长，避免逐条 `ArrayList<Int>` 的装箱（构建期也照 ADR-001 的宗旨来）。
 *
 * ## 基线预置（R7-L1 `codeBase` 偏移基座）
 *
 * 六个基线参数（[codeBase] / [statementBase] / [stringBase] / [longBase] / [exprBase] /
 * [functionBase]，**全部默认 0**）让同一个构建器能编「接在已有产物之后」的一段补丁：
 * 一切**绝对下标**在 [emit] / [constant] / [string] / [expr] / [statement] /
 * [declareFunction] / [functionIndex] / [declareLabel] / [declareCallArgs] 的出口就算成
 * `base + 新增`，补丁产物因此不需要装载期遍历改写（理由见 [ConstantPool] 的类 KDoc：
 * 操作数语义由 opcode 决定，装载期 remap 必须逐条识别模式，漏判即静默错值）。
 *
 * 三条口径必须记清（都是补丁装载期算区间的依据）：
 *
 * 1. [codeBase] 的单位是**字**（与 `CodeSource.wordCount` 同单位），不是指令条数；
 *    故必须是 [Instruction.WORDS] 的整数倍，否则**构造期直接抛错**（不静默纠正）。
 *    [here] / [emit] / [patch] 收发的 pc 都是**绝对** pc（= `codeBase / WORDS + 段内序号`）。
 * 2. 跳转操作数**不受基线影响**：`_JUMP*` / `_GOTO` 的 a 是「相对下一条指令的指令条数」，
 *    起止两端同处绝对 pc 空间，差值不变。带基线的 [here] 因此不会让跳转偏移漂移。
 * 3. 段内数组（[build] 交给 `Program` 的 code/lines、池本体、函数表）**仍然只含新增条目** ——
 *    给 base 段补零会让 4,800,579 条指令的 base 白占 76.8 MiB 常驻，且补丁产物会带上整段
 *    零字节。基线只体现在「计数与下标」上：`Program.instructionCount` / 四个池计数都是
 *    绝对口径，段内本体则从绝对下标 `base` 起算。
 */
class CodeBuilder(
    /**
     * 代码基线：本段第一条指令之前的 base 指令流**字数**（不是条数）。
     * 0 = 无基线（既有路径逐位不变）。
     */
    val codeBase: Int = 0,
    /** 语句池基线：本段第 0 条语句的绝对下标。 */
    val statementBase: Int = 0,
    /** 字符串池基线：本段第 0 条字符串的绝对下标。 */
    val stringBase: Int = 0,
    /** 常量（Long）池基线：本段第 0 条常量的绝对下标。 */
    val longBase: Int = 0,
    /** 表达式池基线：本段第 0 条表达式的绝对下标。 */
    val exprBase: Int = 0,
    /** 函数表基线：本段第 0 个函数的绝对下标（`_CALL` 的 a 用它算）。 */
    val functionBase: Int = 0,
) {
    init {
        require(codeBase >= 0) { "codeBase 不能为负: $codeBase" }
        require(codeBase % Instruction.WORDS == 0) {
            "codeBase 必须是 Instruction.WORDS(${Instruction.WORDS}) 的整数倍" +
                "（单位是字，与 CodeSource.wordCount 同口径）: $codeBase"
        }
        require(statementBase >= 0) { "statementBase 不能为负: $statementBase" }
        require(stringBase >= 0) { "stringBase 不能为负: $stringBase" }
        require(longBase >= 0) { "longBase 不能为负: $longBase" }
        require(exprBase >= 0) { "exprBase 不能为负: $exprBase" }
        require(functionBase >= 0) { "functionBase 不能为负: $functionBase" }
    }

    /** [codeBase] 的指令口径（= `codeBase / Instruction.WORDS`）：段内 pc 的起点。 */
    val instructionBase: Int = codeBase / Instruction.WORDS

    private var code = IntArray(64 * Instruction.WORDS)
    private var lines = IntArray(64)
    private var count = 0

    private val pool = ConstantPool(
        longBase = longBase,
        stringBase = stringBase,
        exprBase = exprBase,
        statementBase = statementBase,
    )
    private val functions = ArrayList<FunctionInfo>()
    private var globalSlots = 0

    /** 当前指令下标（= 下一条要发射的指令的**绝对** pc）。 */
    fun here(): Int = instructionBase + count

    /**
     * 发射一条指令，返回其**绝对** pc（`instructionBase + 段内序号`）。
     * [line] 为源码行号，CodeEE 报错时使用。
     */
    fun emit(op: Int, a: Int = 0, b: Int = 0, c: Int = 0, line: Int = -1): Int {
        if (count == lines.size) {
            lines = lines.copyOf(lines.size * 2)
            code = code.copyOf(code.size * 2)
        }
        val w = count * Instruction.WORDS
        code[w] = op
        code[w + 1] = a
        code[w + 2] = b
        code[w + 3] = c
        lines[count] = line
        return instructionBase + count++
    }

    /**
     * 回填某个操作数（跳转偏移、前向函数下标）。[operand] ∈ 1..3。
     * [pc] 是 [emit] 返回的**绝对** pc（本段区间 `[instructionBase, instructionBase + 已发射条数)`）。
     */
    fun patch(pc: Int, operand: Int, value: Int) {
        require(operand in 1..3) { "operand 必须是 1..3: $operand" }
        require(pc in instructionBase until instructionBase + count) {
            "pc 越界: $pc（本段绝对区间 [$instructionBase, ${instructionBase + count})）"
        }
        code[(pc - instructionBase) * Instruction.WORDS + operand] = value
    }

    /** 追加常量，返回**绝对**下标（`longBase + 段内序号`）；见 [ConstantPool.addLong]。 */
    fun constant(value: Long): Int = pool.addLong(value)

    /** 追加字符串，返回**绝对**下标（`stringBase + 段内序号`）。 */
    fun string(value: String): Int = pool.addString(value)

    /** 追加表达式，返回**绝对**下标（`exprBase + 段内序号`）。 */
    fun expr(value: Expr): Int = pool.addExpr(value)

    /** 语句池追加转发（契约 C2；语义见 ConstantPool.addStatement —— 按身份追加、严禁去重）。 */
    fun statement(s: Statement): Int = pool.addStatement(s)

    /** 名字 → 下标（10 万级函数表的声明/链接都必须 O(1)，线性扫会变成 O(n²)）。 */
    private val nameToIndex = HashMap<String, Int>()

    /**
     * 登记函数原型；返回**绝对**函数下标（`functionBase + 段内序号`）。
     *
     * [entryPc] 由调用方从 [emit] 取得，因此已经是绝对 pc —— 这里原样存进 [FunctionInfo]，
     * 补丁装载期不必再给函数表补偏移（`_CALL` 的 a 与 entryPc 同处绝对空间，逐条自洽）。
     */
    fun declareFunction(
        name: String,
        entryPc: Int,
        arity: Int,
        slotCount: Int = 0,
        paramNames: List<String> = emptyList(),
    ): Int {
        require(nameToIndex.put(name, functions.size) == null) { "函数重复声明: $name" }
        functions.add(FunctionInfo(name, entryPc, arity, slotCount, paramNames))
        return functionBase + functions.size - 1
    }

    /** 调用点 pc → 逐位实参元数据（具名形参/REF 绑定用；只记非空表，保持表小）。 */
    private val callArgs = HashMap<Int, List<CallArg>>()

    /** 登记某个调用点的逐位实参元数据（同一 pc 只会有一条调用指令，后者覆盖前者；[pc] 为绝对 pc）。 */
    fun declareCallArgs(pc: Int, args: List<CallArg>) {
        if (args.isNotEmpty()) callArgs[pc] = args
    }

    /** 函数名 → **绝对**函数下标（未声明返回 -1）。 */
    fun functionIndex(name: String): Int {
        val i = nameToIndex[name] ?: return -1
        return functionBase + i
    }

    /**
     * 登记函数内 `$标签`（编译期；同名幂等并更新 pc）。返回标签下标（= [_JUMP_DYN] 的 a）。
     * 名字大写归一与幂等写面在 [FunctionInfo.putLabel]（惰性槽位）。
     * [funcIndex] 是 [declareFunction] 返回的**绝对**下标，[pc] 是**绝对** pc。
     */
    fun declareLabel(funcIndex: Int, name: String, pc: Int): Int =
        functions[funcIndex - functionBase].putLabel(name.uppercase(), pc)

    /** 标签名 → 下标（未定义返回 -1）。[funcIndex] 为绝对函数下标。 */
    fun labelIndex(funcIndex: Int, name: String): Int =
        functions[funcIndex - functionBase].labelNames.indexOf(name.uppercase())

    /** 函数体解析完后回填帧大小。[index] 为绝对函数下标。 */
    fun setFunctionSlots(index: Int, slotCount: Int) {
        val f = functions[index - functionBase]
        f.slotCount = maxOf(f.slotCount, slotCount)
    }

    /**
     * 记录全局槽用量（`G<n>` 操作数出现时调用）。
     *
     * 判界与 [SlotAddr.global] 同一口径（`0..SlotAddr.MAX_GLOBAL_SLOT`），且必须在这里也拦：
     * 旧实现只写 `if (slot + 1 > globalSlots)`，`slot == Int.MAX_VALUE` 时 `slot + 1` 回绕成
     * [Int.MIN_VALUE] —— 计数**静默不涨**，于是 `Vm` 按偏小的 `globalSlots` 分配
     * `globalInts`/`globalStrs`（`Vm.kt:257-258`），运行期取指时数组越界，报出来的是一句
     * 与真因无关的下标错误。两侧都拦才封住「编码后撞 [SlotAddr.NONE] 哨兵」和「计数回绕」两条路。
     */
    fun noteGlobalSlot(slot: Int) {
        require(slot in 0..SlotAddr.MAX_GLOBAL_SLOT) {
            "全局槽号越界: $slot（合法范围 0..${SlotAddr.MAX_GLOBAL_SLOT}；0x7FFFFFFF 编码后等于 NONE 哨兵）"
        }
        if (slot + 1 > globalSlots) globalSlots = slot + 1
    }

    val globalSlotCount: Int get() = globalSlots

    /**
     * 收尾成 [Program]。[entryFunction] 是**绝对**函数下标（[declareFunction] / [functionIndex]
     * 的返回值）：本段函数表只含新增条目，故落到 `Program.entryFunction` 时折回表内序号
     * （`entryFunction - functionBase`）—— 既有口径「`entryFunction` 是 `functions` 的下标」
     * 因此一个字符都没变。六个基线一并交给 `Program`，供装载期算补丁区间。
     */
    fun build(entryFunction: Int): Program {
        require(entryFunction in functionBase until functionBase + functions.size) {
            "入口函数下标越界: $entryFunction（本段绝对区间 [$functionBase, ${functionBase + functions.size})）"
        }
        require(count > 0) { "空程序" }
        // 跨字段不变量（与 ErbcReader:548 / ErbcWriter:254 / ErbcStream:1135 三路装载校验同款）：
        // 内存 Program 的唯一构建出口再拦一道。arity > slotCount 时 Vm.callInto 的实参复制
        // 上界 min(argcRaw, arity) 会越过被调帧 [0, slotCount) 区间写坏相邻帧/未归属槽。
        // 放 build 而非 declareFunction：两阶段声明（declare 占位 → setFunctionSlots 抬升）的
        // 中间态可合法地 arity > slotCount，唯有 build 时刻的不变量是终态。
        for (f in functions) {
            require(f.arity <= f.slotCount) {
                "函数 ${f.name} 的 arity(${f.arity}) 大于 slotCount(${f.slotCount})：" +
                    "实参复制会越出被调帧（跨字段不变量，与 ErbcReader/ErbcWriter/ErbcStream 同款）"
            }
        }
        return Program(
            code = code.copyOf(count * Instruction.WORDS),
            lines = lines.copyOf(count),
            pool = pool,
            functions = functions.toList(),
            entryFunction = entryFunction - functionBase,
            globalSlots = globalSlots,
            callArgs = callArgs.toMap(),
            codeBase = codeBase,
            statementBase = statementBase,
            stringBase = stringBase,
            longBase = longBase,
            exprBase = exprBase,
            functionBase = functionBase,
        )
    }
}

/**
 * 调用点实参表的**构造期规范化去重**（M6-MEMCODE 第二项）。
 *
 * 全量语料实测（`PoolDecomp` §2b）：150,195 个调用点 / 294,444 个 [CallArg] = 25.0 MiB，
 * 其中 `CallArg` 实例本身 294,444 个（`jcmd` 直方图 9.0 MiB）。这些实例的形态极端重复：
 * 编译期 `ErbProgramCompiler` 逐位填的是 `position`（= 实参位）、`bucket`（**类型桶内的
 * 0 起序号**，故总是 0..argc-1）、`isStr`、`present`，唯一的自由量是 `name`（裸
 * [com.eratw.port.core.expr.VarRef] 实参的变量名，其余为 null，:1101-1108）。
 *
 * 三张规范化表逐级合并：
 *
 * 1. `name` 串按内容归一（表内自洽，不与语句池交叉 —— 那需要 `ArgInterner` 的字符串表）；
 * 2. [CallArg] 按 data class 结构归一（`name` 已归一，故等值即同键）；
 * 3. 实参表（`List<CallArg>`）按元素序列归一。
 *
 * **读取面不受影响**：`ProcessRuntime.bindCallArgs` 逐位读值（`ProcessRuntime.kt:631-660`），
 * `ErbcWriter` 逐字段重新编码（内容相同，字节不变）；`callArgs` 的公开类型仍是
 * `Map<Int, List<CallArg>>`。顺序保持：输出用 `LinkedHashMap`，pc 迭代序 = 入参序。
 *
 * 无收益（全部实例本就唯一）时原样返回入参，不产生第二张表。
 *
 * `internal` 只为审计单测可直接断言这条「无收益 ⇒ 同一张表」的承诺：走 `Program`/`CodeBuilder`
 * 时入参表是 [CodeBuilder.build] 现造的 `toMap()`，身份在外部不可观测。
 */
internal fun canonicalCallArgs(raw: Map<Int, List<CallArg>>): Map<Int, List<CallArg>> {
    if (raw.isEmpty()) return raw
    // MEM9 刀 1：装载侧（段 7 直建）交来的已经是 [CallArgIndex] —— 规范化在解码趟里做过一遍，
    // 这里绝不再复制一张中间 Map（那是 151,120 个 `LinkedHashMap.Entry` + 装箱 `Integer` 键，
    // mem7 探针 §1 的「段7 桶 10.7 MiB」十之八九就是这层壳）。
    if (raw is CallArgIndex) return raw
    val names = HashMap<String, String>()
    val nodes = HashMap<CallArg, CallArg>()
    val lists = HashMap<List<CallArg>, List<CallArg>>()
    val out = LinkedHashMap<Int, List<CallArg>>(raw.size * 4 / 3 + 1)
    var changed = false
    for ((pc, args) in raw) {
        val canon = ArrayList<CallArg>(args.size)
        for (a in args) {
            val rawName = a.name
            val name = if (rawName == null) null else names.getOrPut(rawName) { rawName }
            val node = if (name === rawName) a else a.copy(name = name)
            val rep = nodes.getOrPut(node) { node }
            if (rep !== a) changed = true
            canon.add(rep)
        }
        val shared = lists.getOrPut(canon) { canon }
        // 判据是「本站点这张表有没有并到更早的表上」，不能拿 `args`（入参列表）比：
        // `canon` 恒是新开的 ArrayList，与 `args` 永远不同实例 —— 旧写法让 changed
        // 在任何非空表上都为真，本函数尾部「无收益即原样返回入参、不产生第二张表」
        // 那条承诺（见上面 KDoc）因此从不成立。
        if (shared !== canon) changed = true
        out[pc] = shared
    }
    return if (changed) out else raw
}

/**
 * 段 7 调用点实参表的**紧凑本体**（MEM9 刀 1）：升序 pc 表 + 平行实参表引用，
 * 对外仍是一张 [Map]（`Program.callArgs` 的公开类型一个字节都没改）。
 *
 * ## 换掉了什么
 *
 * `PoolCallArgsDedupeTest` 那套「构造期规范化」把结构相等的 [CallArg] 与等值实参表收敛成
 * 同一实例，全量语料实测（mem7 探针 §1 + §7 计数行）：
 * **151,120 个调用点 / 337,324 个 CallArg，整桶 10.7 MiB**，遍历耗时 93 ms。
 * 而那 10.7 MiB 里几乎没有「数据」——按结构归一之后独立形态只剩千级；吃掉整桶的是
 * **装载即付、之后永不释放的 `LinkedHashMap` 壳**：
 *
 * | 项 | 单价 | 条数 | MiB |
 * |---|---|---:|---:|
 * | `LinkedHashMap.Entry`（hash+key+value+before/after） | 40 B | 151,120 | 5.8 |
 * | 装箱 `Integer` 键（pc 恒 > 127，不入 `IntegerCache`） | 16 B | 151,120 | 2.3 |
 * | 桶数组 `Node[] table`（`tableSizeFor(151120/0.75)=262,144`） | 4 B/槽 | 262,144 | 1.0 |
 * | 二分侧车 `callArgsPcs`（旧：新建一份） | 4 B/项 | 151,120 | 0.6 |
 * | 二分侧车 `callArgsVals`（旧：新建一份） | 4 B/槽 | 151,120 | 0.6 |
 *
 * 前三行（≈9.1 MiB）是**同一批 pc 的第三种索引形态**：Map 的桶数组 + 链表节点 + 装箱键。
 * 本类把这三行全部删掉，只留两张平行数组（键表 + 值表），并且**让二分侧车直接复用这两张
 * 数组**（旧实现是「Map 之外再复制一份 pcs/vals」，同数据两副本）。
 *
 * ## 语义边界（为什么不变行为）
 *
 * - 公开类型仍是 `Map<Int, List<CallArg>>`：本类 [AbstractMap] 的 `equals`/`hashCode`/
 *   `toString`/`keySet`/`values` 全部按**内容**定义，与 `LinkedHashMap` 互相可比 ——
 *   `ErbcStreamEquivalenceTest`/`MmapLoaderTest`/`ProgramLoaderTest` 的
 *   `assertEquals(expected.callArgs, actual.callArgs)` 一条不动照样绿。
 * - **迭代序 = pc 升序**：段 7 的写侧契约本就是「站点按 pc 严格升序落盘、读侧拒绝非升序」
 *   （`ErbcStream.kt:readCallArgs` 的「调用点 pc 未严格升序」判定 + `ErbcWriter.writeCallArgs`
 *   的排序），所以装载侧的入参序恒等于升序；`ErbcV2SectionsTest.装载后按键升序` 钉的就是这一条。
 *   [of] 走的是「显式排序」，与旧 `callArgsPcs` 的注释同口径（不靠上游的隐含顺序）。
 *   编译期档（`CodeBuilder.build` 的 `toMap()` = HashMap，入参序本就任意）由此**收紧成确定序**，
 *   而 [canonicalCallArgs] 自身的「迭代序 = 入参序」契约（`CodeAudit2Test` 钉住）不受影响 ——
 *   本类是它**之后**的一步，不在它的返回值上动刀。
 * - 空表 ⇒ 共享 [EMPTY]（与 `emptyMap()` 内容相等）；[get]/[containsKey] 走二分，
 *   绝不退化成 `AbstractMap` 默认的「遍历全部条目」（那会把 O(log n) 查表变成 O(n)）。
 * - 值槽的类型是 `Array<List<CallArg>?>`（沿用旧侧车类型），但**每个被登记的槽恒非 null**：
 *   `CodeBuilder.declareCallArgs` 只收非空表、段 7 的 `argCount == 0` 在装载期就抛
 *   「调用点 pc=… 的实参个数为 0（v2 只登记非空表）」⇒ [entries] 取值时的判空只是类型妥协。
 * - 实例共享语义不变：等值实参表仍归一到同一实例（[Builder] 与 [canonicalCallArgs] 同一套
 *   「首个到达者当代表」的登记次序，装载趟按 pc 升序走，与旧的 LinkedHashMap 迭代序相同），
 *   `PoolCallArgsDedupeTest` 的两条 `assertSame` 照旧成立。
 *
 * ## 常驻
 *
 * [residentBytes] = 两张数组本体，**不含**实参表与 [CallArg] 实例（那部分本来就唯一、
 * 由 [vals] 的引用指向；探针按对象图身份去重单列）。
 */
internal class CallArgIndex private constructor(
    /** 站点 pc，**严格升序**：[at] 的二分与 [entries] 的迭代序都依赖这条不变量。 */
    val pcs: IntArray,
    /** 与 [pcs] 平行的实参表（结构相等的站点共享同一实例）。 */
    val vals: Array<List<CallArg>?>,
) : AbstractMap<Int, List<CallArg>>() {

    override val size: Int get() = pcs.size

    override fun get(key: Int): List<CallArg>? {
        val i = indexOf(key)
        return if (i < 0) null else vals[i]
    }

    override fun containsKey(key: Int): Boolean = indexOf(key) >= 0

    /** 第 [pc] 站的实参表（无则 null）—— [Program.callArgsAt] 的实现，零装箱。 */
    fun at(pc: Int): List<CallArg>? {
        val i = indexOf(pc)
        return if (i < 0) null else vals[i]
    }

    /** pc → 槽位（二分，找不到返回 -1）。与旧 `Program.callArgsAt` 的循环逐字同形。 */
    fun indexOf(pc: Int): Int {
        var lo = 0
        var hi = pcs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = pcs[mid]
            when {
                k < pc -> lo = mid + 1
                k > pc -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /**
     * 条目视图：**每次取都新建一份**（一个壳，无背衬数组）。
     *
     * 迭代才分配 `SimpleEntry`（24 B/条，用完即弃）——读面只有 `ErbcWriter` 落盘、
     * 装置与单测这些冷路径；运行期的 `Program.callArgsAt` 走 [at]，一条都不分配。
     * 这与 `Program.code`/`Program.lines` 的「兼容视图按需付」是同一条口径。
     */
    override val entries: MutableSet<MutableMap.MutableEntry<Int, List<CallArg>>>
        get() = object : AbstractSet<MutableMap.MutableEntry<Int, List<CallArg>>>() {
            override val size: Int get() = pcs.size
            override fun iterator(): MutableIterator<MutableMap.MutableEntry<Int, List<CallArg>>> =
                object : MutableIterator<MutableMap.MutableEntry<Int, List<CallArg>>> {
                    private var i = 0
                    override fun hasNext(): Boolean = i < pcs.size
                    override fun next(): MutableMap.MutableEntry<Int, List<CallArg>> {
                        val j = i
                        if (j >= pcs.size) throw NoSuchElementException()
                        i = j + 1
                        return AbstractMap.SimpleEntry(
                            pcs[j],
                            requireNotNull(vals[j]) { "调用点 pc=${pcs[j]} 的实参表为空" },
                        )
                    }
                    override fun remove() = throw UnsupportedOperationException("只读视图：装载产物不可改")
                }
        }

    /** 紧凑本体常驻（两张平行数组）；实参表/[CallArg] 实例另按对象图计。 */
    val residentBytes: Long
        get() = ARRAY_HEADER * 2L + pcs.size * 4L + vals.size * 4L

    /** 独立实参表个数（去重读数：`vals` 里不同实例的条数）。 */
    fun distinctListCount(): Int {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        for (v in vals) v?.let { seen.add(it) }
        return seen.size
    }

    /**
     * 段 7 的**直建器**（装载侧专用）：按 pc 升序追加，边追加边做与 [canonicalCallArgs]
     * 同一套逐级归一（`name` 串 / [CallArg] 结构 / 实参表序列），所以解码趟结束后
     * 堆上直接就是两张平行数组 —— 中间那张 151,120 条目的 `LinkedHashMap` 一次都不建。
     *
     * 判界与报错仍归调用方（`ErbcStream.readCallArgs` 保留逐条判定与原文案，一个字节都不动）；
     * 本类只在「已经判定合法」之后登记。
     */
    internal class Builder {
        private var pcs = IntArray(INIT_SLOTS)
        private var vals = arrayOfNulls<List<CallArg>>(INIT_SLOTS)
        private var n = 0
        private val nodes = HashMap<CallArg, CallArg>()
        private val lists = HashMap<List<CallArg>, List<CallArg>>()

        /**
         * 登记一站点（[pc] 必须**严格大于**上一次，与段 7 的写侧契约同源；这里不重复判，
         * 报错点在 `readCallArgs`，位置与文案都不许变）。
         */
        fun add(pc: Int, args: List<CallArg>) {
            if (n == pcs.size) {
                val cap = pcs.size shl 1
                pcs = pcs.copyOf(cap)
                @Suppress("UNCHECKED_CAST")
                vals = vals.copyOf(cap) as Array<List<CallArg>?>
            }
            val canon = ArrayList<CallArg>(args.size)
            for (a in args) canon.add(nodes.getOrPut(a) { a })
            vals[n] = lists.getOrPut(canon) { canon }
            pcs[n] = pc
            n++
        }

        /** 收尾：截到实际条数（0 条 ⇒ 共享 [EMPTY]，与「缺段 = 空表」的旧读数一致）。 */
        fun build(): CallArgIndex {
            if (n == 0) return EMPTY
            return CallArgIndex(pcs.copyOf(n), vals.copyOf(n) as Array<List<CallArg>?>)
        }
    }

    internal companion object {
        private const val INIT_SLOTS = 64
        private const val ARRAY_HEADER = 16L

        /** 空表单例：v1 产物（没有段 7）与「编译期零调用点」共用一份，零常驻。 */
        val EMPTY = CallArgIndex(IntArray(0), arrayOfNulls(0))

        /**
         * 从任意 [Map] 建紧凑本体（编译期档 / `ErbcReader` 的字节档）。
         *
         * 已经是 [CallArgIndex] 就原样返回（装载侧的规范化在解码趟里做过，见
         * [canonicalCallArgs] 的同一条短路口径）。
         */
        fun of(map: Map<Int, List<CallArg>>): CallArgIndex {
            if (map is CallArgIndex) return map
            if (map.isEmpty()) return EMPTY
            val keys = map.keys.toIntArray()
            keys.sort()
            val values = arrayOfNulls<List<CallArg>>(keys.size)
            for (i in keys.indices) values[i] = map[keys[i]]
            @Suppress("UNCHECKED_CAST")
            return CallArgIndex(keys, values as Array<List<CallArg>?>)
        }
    }
}
