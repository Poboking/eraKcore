package com.eratw.port.core.loader

import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcFormatException
import com.eratw.port.core.code.ErbcHeader
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.Program
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 装载期错误（路径 + 原因）。继承 [ErbcFormatException]，调用方可以只捕基类。
 *
 * 与 [ErbcDecodeException] 的分工：那个是**格式层**（偏移 + 字节级原因，由 `ErbcReader` 抛），
 * 本类是**装载层**（产物路径 + 段布局校验 + 对格式层异常的收口）。
 */
class ErbcLoadException(
    message: String,
    cause: Throwable? = null,
) : ErbcFormatException(message, cause)

/**
 * 一个已装载的产物。
 *
 * @param relativePath 相对装载根目录的路径，分隔符统一为 `/`；单文件装载时为文件名。
 * @param byteSize 产物字节数（诊断/manifest 对账用）。
 */
data class LoadedProgram(
    val relativePath: String,
    val byteSize: Int,
    val program: Program,
)

/**
 * `.erbc` 产物装载器（规约 `eratw-android/specs/erbc-format.md`；格式层实现 = `core/code` 的
 * `ErbcFormat` / `ErbcWriter` / `ErbcReader`，本类**只消费**，不重复实现编解码）。
 *
 * ## 性能口径（全读 vs 按段流式）
 *
 * **装载链只走映射 + 按段流式**：宿主（`GameHost`）装载 `program.erbc` 用的是 [loadMapped]，
 * 全量产物在装载期**没有任何「整份产物」的 `ByteArray`**。四个入口的分工：
 *
 * | 入口 | 字节从哪来 | 用途 |
 * |---|---|---|
 * | [loadMapped] / [open] | `FileChannel.map` 窗口（堆外）+ [ErbcStreamDecoder] | 生产（宿主） |
 * | [mapped] / [probeMapped] / [deferredMapped] | 只读通道 + 段表 | 清单对账 / 预检 |
 * | [load]（字节） | 调用方给的 `ByteArray` | 小样本往返测试 |
 * | [loadFile] / [loadDirectory] | `Files.readAllBytes` 整份进堆 | 旧入口，留测试与离线工具 |
 *
 * 读数（本机 HotSpot，全量产物 330,238,214 B / 4,800,579 指令，`ErbcStreamPeakTest` 与
 * `ErbcStreamAllocProfileTest` 同一把尺子：线程分配计数 `getThreadAllocatedBytes` +
 * `HeapMeter` 的段内峰值；M8-COD-EMAP 之后复测）：
 *
 * | 路径 | 装载期分配 | 段内峰值 | 常驻增量 |
 * |---|---|---|---|
 * | 旧 mapped（[MappedErbc.fullBytes] + [load]） | 3763.8 MiB | 1994.7 MiB | +577.4 MiB |
 * | 流式 mapped · 整池对照档（段 3 惰性化之前） | 1222.0 MiB | 991.0 MiB | +403.8 MiB |
 * | 流式 mapped · 惰性档（[loadMapped]，波次 C 之前的形状：装载期不建扁平池） | 213.7 MiB | 485.1 MiB | **+141.4 MiB** |
 * | 流式 mapped · 惰性档 + 波次 C「压扁即弃」（[loadMapped] 的 FLAT 段，**现生产档**） | 2,143.0 MiB | 1,144.4 MiB | **+437.5 MiB** |
 * | 同上，再把语句树整池物化（= 波次 B 稳态多出来的那一份；同配置同产物，只差树在不在池里） | 1,284.9 MiB | — | +223.4 MiB |
 * | 波次 B 的进程稳态（树 + 扁平池并存，压扁即弃之前） | — | — | ≈ +660.9 MiB |
 *
 * 后两行是 2026-10-24 同一 JVM、同一份全量产物（329,320,658 B ≈ 314.1 MiB / 4,888,254 指令 /
 * 1,716,636 语句）上由 `LazyStatementDecodeTest` 的 `ERATW_ERBC` 用例成对测的；分配与常驻
 * 两条读数三次复跑的展布在 ±10 MiB 内，段内峰值（`HeapMeter.peak()` = G1 `peakUsage` 之和）
 * 复跑展布 ±200 MiB —— 它被索引趟那批「解完即弃」的短命对象灌满，跟 GC 时机强相关，
 * **不能当装机判据**；判据取 post-GC 常驻，峰值归属交给分段 mark（催过 GC 的 live 高水位：
 * 旧标定 266.9 MiB 系段 3 eager 节点表并发所抬；M-MEM1 扫描化后本机复测 195.1 → 92.9 MiB，
 * 逐行见 [estimatedHeapBytes] 的标定表）。
 *
 * 波次 C 那三行是 2026-10-28 同源装置的新读数：常驻 +437.5 MiB 是扁平池本体（int 流 67 MB +
 * 语句引用到的表达式结点 + 规范表 + 串表）；在同一份 Program 上把语句树整池物化再 +223.4 MiB
 * （= 波次 B 稳态里多出来的那一份，也是「压扁即弃」省下的那一份）；两份相加就是波次 B 的
 * 进程稳态 ≈ +660.9 MiB（2.1×产物 —— 512 MiB 挡位装不下的原因，只是波次 B 时它没进装载账）。
 * **装载期分配从 213.7 涨到 2,143.0 MiB 是同一个 1.7M 条记录的流式解码 + 规范化 + 编码**
 * （波次 B 把这笔放在 proc 桥构建期付，现在放回装载链、且不再整池驻留）。
 *
 * 惰性档的分配从旧口径 1594.5 MiB 掉到 213.7 MiB，是 3462364c 的段 3 偏移表侧车
 * （`program.stmt.idx`）把「逐条完整反序列化只为拿起始偏移」那一趟去掉了；常驻从 +100.4
 * 抬到 +141.4 MiB 则是 CMP-15 池根保真的成本（旧读数建立在去重塌陷上，不可回退，
 * 详见 [estimatedHeapBytes] 的重标说明）。波次 C 之后装载期分配回到 2,143.0 MiB：
 * 侧车那笔红利还在（索引趟仍然跳过了），多出来的是**扁平池的流式解码 + 规范化 + 写入**
 * —— 波次 B 把同样的活放在 proc 桥构建期做，只是那时没有装载分段读数。
 *
 * 读数随 GC 时机浮动（段内峰值一条会差出 ±200 MiB），表里取**同一趟** `LazyStatementDecodeTest`
 * 的惰性/整池成对读数；常驻/分配两条的复测展布在 ±10 MiB 内。
 *
 * 消掉的正是那一次整文件拷贝（314.9 MiB）+ 解码期在 `ErbcReader` 里按池 `copyOfRange`
 * 复制串表数据块的临时量。**剩下的三块不在本类手里**：
 *
 * - `lines` 的紧凑行表 9.2 MiB（`LineTable`，2 B/条）仍随 Program 常驻；`code` 的 73.3 MiB
 *   自 M8-COD-EMAP 起**不再进堆**——`ErbcStreamDecoder` 把 `SECTION_CODE` 的只读映射窗口
 *   （段首 u32 之后）交给 `MappedCodeSource`，由 VM 按页取指，`program.codeResidentBytes == 0`
 *   （[MappedErbc.codeSource]；读数见 `MappedCodeSourceTest`：映射装载常驻增量 +389.9 MiB，
 *   同会话把 `code` 物化回数组要多分配 146.6 MiB（目标数组 73.3 + 逐页装配的同量临时页）
 *   ⇒ 改前口径 ≈ +463.2 MiB）。物化只发生在 `.erbc` 落盘/反汇编等冷路径；
 * - 装载后 `internStatementArgs()` 的规范化去重瞬时约 590 MiB（`open().program()` 1637.3 −
 *   纯解码 1046.8 MiB，`core/stmt/ArgInterner`），本类只是调用方，换实现属 `core/stmt`
 *   的文件面。**惰性档不再付这一笔**：`internStatementArgs` 见惰性池就只灌表达式池种子
 *   （实测 95.5 MiB），语句侧的去重挪到每条物化时由同一张规范表做；
 * - **段 3 的节点表也已改成无分配边界扫描**（M-MEM1 2026-10-28，形状同段 2 的 M12-EXPRLAZY）：此前
 *   `erbc-stmt3` 工作线程把 827,992 个节点整表建进 `nodes[]`，等记录索引趟收完才由
 *   `enableLazyNodes` 清空 —— 引用被表钉着，GC 只能眼看它们整段堆满再回收；真机装载 trace 的
 *   `program.段3 局部串池 dalloc=262` 就是这一坨（512 MiB 挡位最大的一笔涨幅）。现在扫描趟只留
 *   「每条节点记录的起始偏移」IntArray（3.2 MiB），节点等首次取用走 `RecordSource.lazyEnsure`
 *   的稀疏重放现补。本机复测（全量产物 314.1 MiB / 4,888,249 指令 / 1,716,636 语句，装置同源）：
 *   **催过 GC 的 live 高水位 195.1 → 92.9 MiB**（0.62× → 0.30× 产物；改前高台就是段 3 eager
 *   节点表，改后归属挪到池重灌）、**段内峰值 402.3 → 221.7 MiB**、惰性解码 1.62 → 0.85 s
 *   （`ErbcStreamPeakTest` / `LazyStatementDecodeTest` 的 `ERATW_ERBC` 用例读数）。
 *   **口径更新（旧文案说这一刀「属规格层动作，不在本波」，本波把它落了）**：段 3 节点表的坏字节
 *   分两级 —— 标签/串表下标/子下标越界/后序性/记录长度仍由边界扫描在**同一字节位置**抛出同一
 *   句话（skipNode 与 readNode 字节读序逐字节相同）；运算符/变量名的枚举译码与 String 内容解码
 *   **延到物化时**（同段 2）：「只坏在节点内容」的产物在该节点首次被取到时才报，报的还是同一句
 *   话，差别只在**什么时候发现**，不在**发现什么**。兜底两重：打包后产物被改动时记录区任意一字
 *   节都会打穿 CRC32 配对 ⇒ 回落逐条扫描（该趟为迭代前缀补链，与调用线程栈大小无关，仍在装载
 *   期全部报出）；预检估算系数**不动** —— ART 的 footprint 涨阶与挡位没按新口径复测（见文末
 *   TODO(verify)），降预检是复测之后的动作。
 *
 * 映射窗口的生命周期：`Program` 持有 CODE 段的映射块 ⇒ 与 Program 同寿，[loadMapped] 关闭
 * 通道后照常可读（JVM 语义：关通道不 unmap），但产物文件在 Program 存活期间不能替换
 * （Windows 实测报「另一个程序正在使用此文件」，见 [MappedErbc] 类末 TODO(verify)）。
 *
 * Android 侧直读 assets 仍需先把产物解包成可 mmap 的文件（`AssetFileDescriptor` 属平台层
 * 接线，本层只提供 `Path` 入口，见交付报告的「需组长接线」）。
 *
 * ## 校验分层（不静默降级）
 *
 * 1. **格式层**：`ErbcReader.probe` / `decode` 负责 magic、version、flags⇔段表、逐字段读界、
 *    池下标/函数下标/pc 判界。
 * 2. **装载层**（本类补充，reader 不做的两件）：
 *    - 段起点必须落在**文件头 + 段表之后**（v1 = 38 字节、v2 = 46 字节，按
 *      `ErbcHeader.firstSectionOffset()` 取）。`reader` 只判 `offset >= 10`，
 *      偏移落在段表里时会把段表字节当段正文读；
 *    - 段**自身区间**的长度：用「下一个更大的段起点」或文件尾作为该段上界，
 *      再核对 CODE 段声明的指令条数、LINE_TABLE 声明的行号条数能否装下。
 *      `reader` 的剩余字节数是「到文件尾」，一段被截断时可能读进下一段的字节。
 * 3. **异常收口**：任何非 [ErbcFormatException] 的运行期异常（`IndexOutOfBoundsException`、
 *    `NegativeArraySizeException` 等）与解码侧的 [IOException]（严格 UTF-8 的
 *    `CharacterCodingException` 走这一支）一律包成 [ErbcLoadException]，不让裸异常冒泡。
 *    坏产物因此都落在 `BootFailures` 的「段损坏」分支，而不是 `OTHER`。
 */
object ProgramLoader {

    /** 产物后缀。目录装载只认这个后缀，续片（`.erbc.p1`）不参与。 */
    const val ARTIFACT_SUFFIX = ".erbc"

    // 段偏移的合法下界不是常量：v1 = 10 + 7×4 = 38、v2 = 10 + 9×4 = 46，
    // 一律用 `ErbcHeader.firstSectionOffset()`（判界必须跟着产物版本走）。

    // ------------------------------------------------------------------
    // 字节入口
    // ------------------------------------------------------------------

    /** 从内存字节装载。异常消息不带路径前缀。 */
    fun load(bytes: ByteArray): Program = load(bytes, null)

    /**
     * 从内存字节装载，[source] 仅用于异常消息（通常是文件名或调用方标识）。
     *
     * @throws ErbcFormatException magic/版本/段表/段长/引用越界任一不合法
     */
    fun load(
        bytes: ByteArray,
        source: String?,
    ): Program {
        val header = probe(bytes, source)
        validateSections(header, bytes, source)
        val program = try {
            ErbcReader.decode(bytes)
        } catch (e: ErbcFormatException) {
            throw withSource(e, source)
        } catch (e: IOException) {
            // 严格 UTF-8 解码抛 CharacterCodingException（IOException 支，不是 RuntimeException）：
            // 类内三处字符串表视图已把它包成 ErbcDecodeException，这里再兜一层防新增解码路径漏网。
            throw ErbcLoadException(
                "${prefix(source)}解码中断（${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        } catch (e: RuntimeException) {
            throw ErbcLoadException(
                "${prefix(source)}解码中断（${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        } catch (e: StackOverflowError) {
            // CMP-21：StackOverflowError 是 Error，旧收口只接 RuntimeException/IOException
            // ⇒ 嵌套过深的产物在失败屏裸报 StackOverflowError（BootFailures 归 OTHER）。
            throw ErbcLoadException("${prefix(source)}嵌套过深（表达式/参数记录递归超过栈容量）", e)
        }
        if (program.instructionCount <= 0) {
            throw ErbcLoadException("${prefix(source)}装载结果没有指令（instructionCount=0）")
        }
        // 装载后规范化去重（M6-PERFINT）：结构相等的参数结点归一，池下标与语句实例不变。
        // CMP-21：这一步本身是递归下降（ArgInterner.canonicalize），深表达式树也会打穿栈；
        // 与解码段同一条收口，别让它以裸 Error 冒到宿主。
        try {
            program.internStatementArgs()
        } catch (e: StackOverflowError) {
            throw ErbcLoadException("${prefix(source)}嵌套过深（表达式/参数记录递归超过栈容量）", e)
        }
        return program
    }

    /** 只读文件头（分片/mmap 决策与 manifest 对账用），不做段长校验。 */
    fun probe(
        bytes: ByteArray,
        source: String?,
    ): ErbcHeader = try {
        ErbcReader.probe(bytes)
    } catch (e: ErbcFormatException) {
        throw withSource(e, source)
    } catch (e: RuntimeException) {
        throw ErbcLoadException(
            "${prefix(source)}文件头无法解析（${e.javaClass.simpleName}: ${e.message}）",
            e,
        )
    }

    // ------------------------------------------------------------------
    // 文件入口
    // ------------------------------------------------------------------

    /** 单文件装载（一次读全量字节）。 */
    fun loadFile(path: Path): Program = load(fileBytes(path), path.toString())

    /** 单文件只读文件头。 */
    fun probeFile(path: Path): ErbcHeader = probe(fileBytes(path), path.toString())

    // ------------------------------------------------------------------
    // 装载前堆预检（M6-GAMEROOT）
    // ------------------------------------------------------------------

    /**
     * 装载前堆预检的结果（[ProgramLoader.estimateHeap]），宿主只读 [sufficient] 与两个 MiB 读数。
     *
     * @param artifactBytes `program.erbc` 字节数（[MappedErbc.byteSize]）。
     * @param metaBytes `program.meta` 字节数（`Files.size`）。
     * @param instructionCount CODE 段声明条数（只读段表 + 一个 u32，不解码）。
     * @param requiredBytes 估算的常驻堆需求（[ProgramLoader.estimatedHeapBytes]）。
     * @param maxMemoryBytes 判定用的堆上限：`Runtime.maxMemory()`，测试用 `maxMemoryOverride` 注入。
     */
    data class HeapEstimate(
        val artifactBytes: Long,
        val metaBytes: Long,
        val instructionCount: Int,
        val requiredBytes: Long,
        val maxMemoryBytes: Long,
    ) {
        /** 堆是否够装载：`maxMemory >= requiredBytes`。 */
        val sufficient: Boolean get() = maxMemoryBytes >= requiredBytes

        val requiredMiB: Long get() = requiredBytes / MIB

        val maxMemoryMiB: Long get() = maxMemoryBytes / MIB

        override fun toString(): String =
            "HeapEstimate(erbc=$artifactBytes B, meta=$metaBytes B, 指令=$instructionCount, " +
                "需=$requiredMiB MiB, 上限=$maxMemoryMiB MiB, sufficient=$sufficient)"

        private companion object {
            const val MIB = 1024L * 1024L
        }
    }

    /**
     * 装载前堆预检：读 `.erbc` 文件头（段表 + CODE 条数）与 `program.meta` 大小，
     * 按经验比例估算常驻堆需求，对着 `Runtime.maxMemory()` 判一次。
     *
     * **与真实装载同式（CMP-23）**：先跑 [MappedErbc.checkSectionLayout]（= [load] /
     * [loadMapped] 在解码前跑的同一条 `validateSections`），再取 CODE 条数。旧实现只判
     * `instructionCount() > 0`，一个撒谎的 CODE 计数（`Int.MAX_VALUE`）会按「条数 × 20 B」
     * 把需求报到 **42.9 GB**，失败屏于是误报「堆不足」——真相是产物段长不符。
     *
     * **不做的事**：不解码、不读侧车正文；段布局校验只映射各段首的几个 u32（段表 + CODE/
     * LINE 计数），产物正文一个字节都不进堆。所以预检不足时直接出文案，堆还没被解码打碎；
     * 预检通过也只说明「下限够」，真 OOM 仍由 [BootFailures.classify] 兜到同一条文案。
     *
     * 异常口径与装载逐条同源：文件缺失 / 打不开 / 头畸形由 [MappedErbc.open] 抛出，
     * 段布局不符由 [MappedErbc.checkSectionLayout] 抛出（与 `loadMapped` 同一句报错），
     * 宿主 `BootFailures.classify` 原样分类（缺失 / 映射失败 / 段损坏），不新增判据。
     *
     * TODO(verify)：ART 上 `Runtime.maxMemory()`（largeHeap 挡位）与
     * `ActivityManager.getMemoryClass()` 的真实读数需真机；产物项 1.1× / 侧车 0.15× 只在
     * HotSpot 上按分段 mark 的实时高水位标定过（口径与重标理由见 [estimatedHeapBytes]），
     * 真机常驻比值若偏离需重标。本机无设备，卡点在 M4-SKEL-RT。
     *
     * @param maxMemoryOverride 测试注入的堆上限；null = `Runtime.getRuntime().maxMemory()`。
     */
    fun estimateHeap(
        programPath: Path,
        metaPath: Path,
        maxMemoryOverride: Long? = null,
    ): HeapEstimate {
        val metaBytes = Files.size(metaPath)
        val mapped = MappedErbc.open(programPath)
        return try {
            // CMP-23：与装载同式。段布局不符（含撒谎的 CODE 计数）在这里就抛
            // ErbcDecodeException（ErbcFormatException 族）⇒ CORRUPT_ARTIFACT，
            // 不会走到「按条数估需求」那一步。
            mapped.checkSectionLayout()
            val instructions =
                if (mapped.isPresent(ErbcFormat.SECTION_CODE)) mapped.instructionCount() else 0
            HeapEstimate(
                artifactBytes = mapped.byteSize.toLong(),
                metaBytes = metaBytes,
                instructionCount = instructions,
                requiredBytes = estimatedHeapBytes(mapped.byteSize.toLong(), metaBytes, instructions),
                maxMemoryBytes = maxMemoryOverride ?: Runtime.getRuntime().maxMemory(),
            )
        } finally {
            mapped.close()
        }
    }

    /**
     * 常驻堆需求的经验估算（唯一实现，[estimateHeap] 与单测共用）。
     *
     * ## 产物项取的是**解码内部催过 GC 的实时高水位**，不是装载后的常驻
     *
     * 标定读数（2026-10-24 合流后本机 HotSpot，全量产物 329,320,658 B ≈ 314.1 MiB /
     * 4,888,254 指令 / 语句池 1,716,636 条；装置 = `LazyStatementDecodeTest` 的
     * `真实产物：催过 GC 的解码实时高水位被预检估算包住`，逐段读数在同用例输出里）：
     *
     * | 读数（惰性装载 = 生产档） | 字节 | ×产物 |
     * |---|---|---|
     * | 解码内部实时高水位（每个 mark 点 `HeapMeter.settled()` 的最大值，落在「段2 节点表」——那笔高台实为段 3 工作线程**并发**建的 eager 节点表，段 2 自身在 M12-EXPRLAZY 起就是扫描形态） | **266.9 MiB** | 0.850× |
     * | └ 索引趟结束（`段3 语句记录`，段 3 节点表清空后） | 160.7 MiB | 0.512× |
     * | M-MEM1 改前 HEAD 复测（2026-10-28，高台落在 `段3 语句记录 8/8`，同形异代读数） | 195.1 MiB | 0.621× |
     * | M-MEM1 段 3 节点表扫描化后（2026-10-28，归属挪到 `池重灌`；段 3 局部串池/节点表/记录条带全程高台 75.4 MiB） | **92.9 MiB** | 0.296× |
     * | 装载后 post-GC 常驻增量（CMP-15 池根保真之后） | +141.4 MiB | 0.450× |
     * | 整池对照档（改造前形态）常驻增量 | +403.8 MiB | 1.286× |
     * | **T2-64 波次 C「压扁即弃」之后**（2026-10-28，高台挪到 `语句池压扁即弃`，逐段读数见 `LoadTimingProbe` 与 `LazyStatementDecodeTest`） | **444.8 MiB** | 1.416× |
     * | └ 装载后 post-GC 常驻增量（扁平流 67 MB + 语句引用到的表达式结点 + 规范表 + 串表） | +437.5 MiB | 1.393× |
     * | └ 同一份 Program 上把语句树整池物化（= 波次 B 稳态多出来的那一份，也是本波省下的那份） | +223.4 MiB | 0.711× |
     * | 波次 B 的进程稳态（树 + 扁平池并存；上面两行相加） | ≈ +660.9 MiB | 2.104× |
     *
     * 波次 C 那一行是**装载形状变了**，不是回归：波次 B 把扁平池建成推迟到 proc 桥构建期，
     * 所以 352.5 MiB 的估算只覆盖了"装载"那一段，而进程**稳态**（桥建完扁平池之后）本来就
     * 是 ≈+660.9 MiB（扁平池 + 语句树）。波次 C 把压扁也挪进装载链、树就地弃置
     * （`ErbcStreamDecoder` 的 FLAT 段），装载期高水位因此 = 新稳态 +444.8 MiB，预检必须按它
     * 标定，否则就是"少报"（旧 1.1× 的 352.5 MiB 比实测少 92 MiB）。
     * 压扁即弃省下的就是那整份语句树（外壳 + Argument 容器 + 语句引用的表达式结点容器）：
     * 同配置同产物实测 +223.4 MiB ≈ 0.711×产物；§9.5 里"语句树 ≈390 MB"含编译期的 raw 原文，
     * erbc 装载档的语句树只有这一份（raw 恒 ""）。
     *
     * 常驻那两行是 2026-10-24 N8 重标：CMP-15（Commit 96664b02）把形状掩码纳入 `line`/`col`、
     * 池根「只登记不替换」之后，表达式池里「同形异行」的根与子树不再被归并 —— 旧读数
     * +100.4 MiB 是**去重塌陷**口径（`program.expr(k)` 的 line/col 会被改掉、`.erbc` 再编码
     * 少节点记录，`MmapLoaderTest` 真语料差分钉过），不是可回退的目标。多出来的 ~41 MiB
     * 是池根保真的成本：+40.1 MiB 惰性档 / +37.5 MiB 整池档，两条档同幅（见交付报告分解）。
     *
     * ⚠️ 别拿 `total − free` 那一路的读数标定：索引趟「解完即弃」把垃圾算进去了，
     * 同一份产物两次跑实测 342.6 / 388.3 / 725.5 MiB，纯 GC 时机噪声。
     *
     * 于是产物项取 **1.45×**（= 实测 live 高水位 1.416× 之上再留 ~2.4% 余量，462 vs 445 MiB）。
     * 波次 B 的 1.1× 是**装载形状**的标定（那时装载期不建扁平池），拿它套波次 C 就是少报。
     *
     * ⚠️ 512 MiB 挡位的余量因此从 159 MiB 收到 **~50 MiB**（侧车 `program.meta` 一份就 46.8 MiB）：
     * 估算仍判 `sufficient=true`（M10-MEM 的出口判据形式上还成立），但这一挡实际上已经贴边。
     * 要说清责任归属：波次 B 的**进程稳态**是 ≈+660.9 MiB（树 + 扁平池），本来就不是 512 MiB 装得下的；
     * 只是那时扁平池在 proc 桥里建，装载预检看不见它。本波把压扁挪进装载链后，预检终于如实把它算进来。
     * 要不要把 512 MiB 标成"需要 force_load"、或者把扁平流/表达式结点再做一层惰性化，
     * 是产品口径的决定，不在本路（见交付消息「需组长接线」）。ART 的 footprint 涨阶与真实
     * `load.peak` 也没在设备上复测过（本机无设备，M4-SKEL-RT）。
     *
     * 预检**不许少报**由那条用例钉死；反过来多报的代价是产品行为 —— 旧口径 `2.0×`
     * （整池时代 628.6 MiB / 313.6 MiB 标定）在段 3 惰性化之后仍把需求报到 634 MiB
     * ⇒ 512 MiB 挡位恒判 `sufficient=false` ⇒ 默认行为永远是失败屏，
     * M10-MEM 的出口判据（真机不再需要 `force_load`）就打不到。
     *
     * ## 独立下界（CMP-23：用真实常驻常量，不再用旧口径）
     *
     * `lines` 是 u16 紧凑表（2 B/条，见 `Program.LineTable`），M8-COD-EMAP 之后 `code` 是
     * 只读映射（堆内 0 常驻）⇒ 每条指令的堆常驻下界 = **2 B**。旧口径 20 B/条把两件已不
     * 成立的事算了进去（`code` 4 字 × 4 B = 16 B 已进映射页、`lines` 4 B/条已压成 2 B/条）。
     * 头里的 CODE 计数与产物字节互不替代：下界只防「产物字节小、指令条数大」的形态。
     *
     * 两者取大：全量语料 ⇒ `max(9.3 MiB, 455.4 MiB + 7.0 MiB) ≈ 462.4 MiB`（报 462 MiB；512 MiB
     * 判够，但只剩 ~50 MiB 给侧车/session/runtime）。撒谎的 CODE 计数**不再参与估算**：预检先跑
     * [MappedErbc.checkSectionLayout]，与段长不符的计数在那一步就按装载口径被拒
     * （CMP-23；旧实现会把它报到 42.9 GB 后误报「堆不足」）。
     *
     * TODO(verify)：1.45× 只在 HotSpot/G1 + 桌面产物上标定过；ART 的 footprint 涨阶、
     * `Runtime.maxMemory()` 的实际挡位与 GC 时机是另一套口径。真机复跑 `load.peak` 之后
     * 按屏上读数收口：`峰值 ÷ 产物字节 > 1.45` 就再往上抬，`LazyStatementDecodeTest` 那条
     * 「不许少报」若被真机证伪，以真机为准重标 —— 卡点同旧版（本机无设备，M4-SKEL-RT）。
     */
    internal fun estimatedHeapBytes(
        artifactBytes: Long,
        metaBytes: Long,
        instructionCount: Int,
    ): Long {
        val codeAndLines = instructionCount.coerceAtLeast(0).toLong() * BYTES_PER_INSTRUCTION
        val decoded = artifactBytes.coerceAtLeast(0L) * ARTIFACT_RESIDENT_NUMERATOR / ARTIFACT_RESIDENT_DENOMINATOR
        val meta = metaBytes.coerceAtLeast(0L) * META_RESIDENT_NUMERATOR / META_RESIDENT_DENOMINATOR
        return maxOf(codeAndLines, decoded + meta)
    }

    /**
     * 每条指令的堆常驻下界：`lines` 的 u16 紧凑表 2 B/条（`Program.LineTable`）；
     * `code` 是只读映射、堆内 0 常驻（M8-COD-EMAP）。旧值 20 B 是映射化之前的
     * 「code 4 字 × 4 B + lines 4 B」，两项都已不成立（CMP-23）。
     */
    private const val BYTES_PER_INSTRUCTION = 2L

    /**
     * 产物字节 → 解码内部实时高水位的实测比例。
     *
     * 沿革：2.0（整池顺序解码时代）→ 1.1（M10-MEM 段 3 惰性化之后，实测 0.850×）→
     * **1.45**（T2-64 波次 C「压扁即弃」把扁平池构建挪进装载链之后：实测 live 高水位
     * 444.8 MiB / 314.1 MiB = **1.416×**，落在 `语句池压扁即弃` 段）。
     *
     * 波次 C 的数字不是装载变胖，而是**原本就在进程里**的那份扁平池（波次 B 在 proc 桥构建期
     * 建、建完一直常驻）现在算进了装载期高水位；旧 1.1× 只覆盖了「装载」那一段。
     * 详见 [estimatedHeapBytes] 的表与 512 MiB 挡位余量的警告。
     */
    private const val ARTIFACT_RESIDENT_NUMERATOR = 29L
    private const val ARTIFACT_RESIDENT_DENOMINATOR = 20L

    /** 侧车字节 → 常驻堆：5.4 MiB / 46.9 MiB ≈ 0.115，向上取 0.15 留余量。 */
    private const val META_RESIDENT_NUMERATOR = 3L
    private const val META_RESIDENT_DENOMINATOR = 20L

    // ------------------------------------------------------------------
    // 映射入口（FileChannel.map；实现见 MappedErbc）
    // ------------------------------------------------------------------

    /**
     * 以只读映射打开一份产物，调用方负责关闭（`use` / [MappedErbc.close]）。
     *
     * 打开只读文件头那 38 字节：段表读数（[MappedErbc.instructionCount] 等）
     * 全部按段现取现映射，不把整份文件读进堆。
     */
    fun mapped(path: Path): MappedErbc = MappedErbc.open(path)

    /**
     * 映射口径的 [probeFile]：只读文件头 + 段表，堆成本 = 38 字节 + 一个 [ErbcHeader]。
     *
     * 与 [probeFile] 的差别**只在字节从哪来**：段表判定与异常消息逐字对齐
     * `core/code/ErbcReader.kt:307-351`（`MmapLoaderTest` 用畸形输入面做差分）。
     * 与 [probeFile] 一样**不做**段长校验 —— 要那层判定用 [MappedErbc.checkSectionLayout]。
     */
    fun probeMapped(path: Path): ErbcHeader = withProbeSource(path) {
        MappedErbc.open(path).use { it.header }
    }

    /**
     * 映射口径的 [loadFile]：解出与 [loadFile] **逐字段一致**的 [Program]。
     *
     * 段布局校验通过后由 [ErbcStreamDecoder] **按段读取**（记录流按窗口推进、字符串表数据块整块映射，
     * 解完即丢），不再把整份产物读成一个 `ByteArray`；`SECTION_CODE` 也不装配成 `IntArray`，
     * 而是把只读映射窗口交给 `MappedCodeSource`（M8-COD-EMAP，`program.codeResidentBytes == 0`）；
     * 段 3 的语句池自 M10-MEM 起是**惰性**的（只建记录偏移索引，条目第一次被取用才解，
     * 见 [LazyStatementRecords]）⇒ 装载常驻与「真跑到的语句数」成正比，不再与语料规模成正比。
     * 解码语义/异常口径与 `ErbcReader` 逐条一致（见 [ErbcStreamDecoder] 的类 KDoc 与
     * `MmapLoaderTest` / `LazyStatementDecodeTest` 的差分矩阵）。
     *
     * 返回值里的映射由 `Program` 自己持有：本方法返回前关闭的只是文件通道，
     * 映射仍然有效（关通道不 unmap）。**例外**：惰性语句池要回读段 3，所以它把段 3 的
     * 整段映射接了过去，通道也交给 `Program` 留着（[close] 之前不 unmap、不关通道 ⇒
     * 产物文件在 Program 存活期间不能替换/删除）。
     *
     * 异常口径逐条对齐 [loadFile]：文件头层走 [withProbeSource]（与 [probe] 同一个收口），
     * 段布局层保持**不加前缀**（[load] 里的 `validateSections` 同样不加），
     * 解码层与 [load] 共用同一条 `withSource` / 「解码中断」收口（[decodeMapped]）。
     */
    fun loadMapped(path: Path): Program = loadMapped(path, compat = false)

    /** 兼容档引入前的双参形态（既有测试/装置按第二位置传 mark 的调用点走这条）。 */
    fun loadMapped(
        path: Path,
        mark: LoadStageMark?,
    ): Program = loadMapped(path, compat = false, mark = mark)

    /**
     * 带**装载分段 mark** 的 [loadMapped]（M10-MEM 判据③）。
     *
     * 宿主的埋点原本只有 `preflight/program/meta/session/ready` 五段，而真机的 OOM 恰好落在
     * `load.program` 段**内部**，失败屏的峰值行因此显示的是上一段读数。把 [mark] 交进来，
     * 解码器就会在每个段边界与索引趟的每个 1/8 条带回调一次（`program.段3 语句记录 3/8` 这种
     * 段名）；采样口径仍归宿主，core 层不碰堆计数。
     *
     * [compat] 排在 [mark] **之前**是有意的：`mark` 保持参数表末位，宿主既有的
     * `loadMapped(f) { stage -> … }` 尾随 lambda 调用点才能原样编译（2026-10-28 加
     * 兼容档时踩过：compat 放末位会打碎全部尾随 lambda 调用点）。
     */
    fun loadMapped(
        path: Path,
        /**
         * 兼容装载档（2026-10-28 低端机 OOM）：true = 解码期去重与校验串行（不起
         * `erbc-intern-seed` 并行线程），装载变慢但校验趟期间的并发分配少一路。
         * 语义/异常口径与默认档逐字段一致；宿主失败屏的「兼容模式重试」传 true。
         */
        compat: Boolean = false,
        /**
         * 是否启用扁平语句池（T2-64）。波次 D 起它是**分片惰性**的：装载期零建片，执行到才建 4,096 条
         * 一片（LRU 上限 64 片），装载后常驻与关闭时同读数 ⇒ 默认 true（含 Android）。
         * false 只作 A/B 与退路：VM marker 改走对象树。
         */
        flatStatements: Boolean = true,
        mark: LoadStageMark? = null,
    ): Program {
        val program = loadMappedHandle(withProbeSource(path) { MappedErbc.open(path) }, mark, compat)
        program.flatStatementsEnabled = flatStatements
        return program
    }

    /**
     * [loadMapped] 的实现主体（internal 只为测试能在**失败路径**上断言映射已关闭）：
     * 段布局校验与解码都由 [MappedErbc.toProgram] 完成。
     *
     * 生命周期纪律：只有解出的 Program **确实带惰性语句池**时才把通道留给它（段 3 的
     * 读数路径归 Program 所有）；其余一切路径 —— 包括 `toProgram` 抛异常 —— 都在
     * `finally` 里关通道。旧写法在异常路径上直接漏掉了 [MappedErbc.close]，
     * 畸形产物会把文件句柄一直挂到 GC。
     */
    internal fun loadMappedHandle(
        mapped: MappedErbc,
        mark: LoadStageMark?,
        compat: Boolean = false,
    ): Program {
        var keepChannel = false
        try {
            // 分段 mark（M11-MEM）：容器头 + 段表在 open 里已经读完，这里是它结束的观测点。
            mark?.onStage("program.${LoadStages.HEADER}")
            val program = mapped.toProgram(mark, serialPhases = compat)
            // 任一池走惰性（语句 / 表达式，M12-EXPRLAZY）都要把产物通道留给 Program。
            keepChannel = program.holdsLazyAnyPool
            return program
        } finally {
            if (!keepChannel) mapped.close()
        }
    }

    /**
     * 对照档（M10-MEM 差分装置专用，**不进生产路径**）：映射装载但段 3 整池顺序解码，
     * 即改造前的形态。`LazyStatementDecodeTest` 用它逐条核对「惰性化没改任何判定与报错」。
     */
    internal fun loadMappedEager(path: Path): Program = withProbeSource(path) { MappedErbc.open(path) }.use { m ->
        m.toProgram(lazyStatements = false)
    }

    /** 对照档的延迟句柄（同 [loadMappedEager]，走 [MappedProgramSource] 生命周期）。 */
    internal fun deferredMappedEager(path: Path): MappedProgramSource =
        MappedProgramSource(withProbeSource(path) { MappedErbc.open(path) }, eagerStatements = true)

    /**
     * 解码层收口（[MappedErbc.toProgram] 与 [load] 共用同一套包装）：格式异常加来源前缀，
     * 其它运行期异常包成「解码中断」，最后核对指令数非零。
     */
    internal fun decodeMapped(
        m: MappedErbc,
        mark: LoadStageMark? = null,
        lazyStatements: Boolean = true,
        /**
         * 惰性节点重建的递归上限（CMP-21 的可配旋钮）：默认 [MAX_ENSURE_DEPTH]。
         * 测试/装置可压小它，强制升序退路（`RecordSource.buildPrefixThrough`）。
         */
        maxEnsureDepth: Int = MAX_ENSURE_DEPTH,
        /** 兼容装载档：true = 去重与校验串行（见 [ErbcStreamDecoder] 的 `serialPhases`）。 */
        serialPhases: Boolean = false,
    ): Program {
        val source = m.path.toString()
        // 语句记录偏移表侧车（可选）：有就直接用，省掉「逐条反序列化 171 万条语句只为拿
        // 偏移」的索引趟（真机实测 9.5 s → 百毫秒级）。侧车缺失/不可信时解码器自动回落扫描。
        // M13-MEM10 刀 E：侧车改成**只读映射**取用（171 万条偏移不再进 Java 堆，
        // 每取一条语句一次 4 B 映射读；§1 的 6.5 MiB 整行消失）。
        val stmtOffsets = StmtOffsetSidecar.openMapped(m.path)
        // 分段 mark（M11-MEM）：侧车曾是段 3 之前的独立大分配（171 万条 → 6.8 MB `IntArray`；
        // 刀 E 之后改只读映射，这一窗只剩头部 CRC 校验与建映射），
        // 之前没有任何读数；失败屏因此只能把这一段算进上一段。
        mark?.onStage("program.${LoadStages.STMT_SIDECAR}")
        val program = try {
            ErbcStreamDecoder.decode(m, null, mark, lazyStatements, stmtOffsets, maxEnsureDepth, serialPhases)
        } catch (e: ErbcFormatException) {
            throw withSource(e, source)
        } catch (e: IOException) {
            // 与 [load] 同一条收口：严格 UTF-8 的 CharacterCodingException 是 IOException 支，
            // 若哪条解码路径没包成 ErbcDecodeException，也不许以裸 IOException 冒到宿主。
            throw ErbcLoadException(
                "${prefix(source)}解码中断（${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        } catch (e: RuntimeException) {
            throw ErbcLoadException(
                "${prefix(source)}解码中断（${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        } catch (e: StackOverflowError) {
            // CMP-21：旧收口只接 RuntimeException/IOException，Error 越过它 ⇒ 失败屏裸报
            // StackOverflowError。嵌套过深是**产物**问题（畸形/手写深度），归
            // CORRUPT_ARTIFACT；文案给「嵌套过深」，不把 JVM 类名落屏。
            throw ErbcLoadException("${prefix(source)}嵌套过深（表达式/参数记录递归超过栈容量）", e)
        }
        if (program.instructionCount <= 0) {
            throw ErbcLoadException("${prefix(source)}装载结果没有指令（instructionCount=0）")
        }
        // 装载后规范化去重（M6-PERFINT）：与 [load] 同一口径（映射装载与字节装载逐字段等价）。
        // 惰性池走 seed + 逐条物化时去重（Program.internStatementArgs 内分派），不整池遍历。
        // 埋点（2026-10-23 性能）：这一步在全量产物上是真机 ~7.3 s 的黑箱，之前没有任何分段读数。
        // 注意**不能**靠「跳过它」省时间：`argsInternedSlot` 的惰性语义会把这一趟推迟到语句首次
        // 物化时（实测：关掉后 program 21.1s→13.7s、session 3.5s→10.7s，总时长不变）。
        // CMP-21：这趟去重本身是递归下降（canonicalize/seedInterning），深表达式树会打穿栈；
        // 与解码段同一条「嵌套过深」收口 —— 旧代码把这一句放在 try 之外，Error 直接外泄。
        // 映射档已经在 `ErbcStreamDecoder.decodeProgram` 里与引用范围校验**重叠**跑完
        // （那里有自己的 INTERN mark，所以下面这条 mark 也要跳过，免得记出一条 0 ms 的假段）。
        // 这里只兜底字节档（`load()` 不经 decodeProgram）与任何没跑过的路径。
        val internedInline = program.argsInterned != null
        try {
            if (!internedInline) program.internStatementArgs()
        } catch (e: StackOverflowError) {
            throw ErbcLoadException("${prefix(source)}嵌套过深（表达式/参数记录递归超过栈容量）", e)
        }
        // 埋点顺序（CMP-16）：`LoadTimingProbe` 的记账法是「新段名 = 距上一段的耗时」，所以
        // INTERN 必须打在**这一趟结束之后**。旧写法打在调用之前，去重耗时反而记到下一段
        //（program/session），两台 JVM 探针都把去重读成 ≈0 ms。
        // 惰性档（生产）这里只是种子段（池根入表，毫秒级）：语句侧的按条去重推迟到每条
        // `statementAt` 首取时，那段耗时归执行期，不在本区间的账上。
        // 段名带 `program.` 前缀：[LoadStageMark] 的契约是「[LoadStages] 常量 + program. 前缀」，
        // 成功屏的分段读数与 `LazyStatementDecodeTest` 的「峰值归属必须是解码内部阶段」都以它判。
        if (!internedInline) mark?.onStage("program.${LoadStages.INTERN}")
        return program
    }

    /**
     * 流式装载入口（本波新增，等价于 [deferredMapped]，多一层命名语义）：
     * **打开即拿段表，[MappedProgramSource.program] 第一次被调用时按段流式解码**，
     * 产物字节全程留在只读映射窗口里、不进堆。
     *
     * 与 [loadMapped] 的取舍：
     * - [open] 先不付解码开销（打开只读文件头 + 段表：v1 38 B / v2 46 B，外加段界数组），
     *   适合「先对清单、后物化」与「装载前判该不该装」；句柄持有只读通道，用完必须
     *   [MappedProgramSource.close]。
     * - [loadMapped] 一次给到 [Program] 并自己关通道，宿主单产物启动用这个。
     *
     * 生命周期：物化出的 [Program]，各池与行表是堆对象、指令流是 `Program` 自己持有的只读
     * 映射（M8-COD-EMAP），[MappedProgramSource.close] 之后照样能跑（关通道不 unmap，VM 读的是
     * 映射块而不是文件通道）；反过来 close 之后句柄的任何段读数都会抛 [ErbcLoadException]，
     * 不会读到悬空映射。同一句柄重复 `program()` 恒返回同一实例（语句池以实例为键）。
     *
     * @throws ErbcFormatException 文件缺失以外的头/段表畸形（与 [probeMapped] 同一收口）
     */
    fun open(path: Path): MappedProgramSource = deferredMapped(path)

    /**
     * 延迟物化句柄：打开即有段表，[MappedProgramSource.program] 第一次被调用时才真的解码。
     *
     * 这是「装机后只对清单、不把所有 Program 拉进堆」的入口（规约 §7 的 probe 口子）。
     * 调用方负责 [MappedProgramSource.close]。
     */
    fun deferredMapped(path: Path): MappedProgramSource =
        MappedProgramSource(withProbeSource(path) { MappedErbc.open(path) })

    /**
     * 目录里全部产物的延迟句柄（清单对账用，顺序与 [artifactFiles] 一致）。
     * 每个句柄只花 38 字节的头 + 段界数组；未物化的产物不进堆。
     */
    fun deferredDirectory(dir: Path): List<MappedProgramSource> {
        val root = dir.toAbsolutePath().normalize()
        return artifactFiles(root).map { p -> deferredMapped(p) }
    }

    /**
     * 文件头层的收口，与 [probe] 对 [ErbcReader.probe] 做的事逐字相同：
     * 格式异常加来源前缀（已经是装载层异常则原样透传，避免双重前缀），
     * 其它运行期异常包成 [ErbcLoadException]。
     */
    private fun <R> withProbeSource(
        path: Path,
        block: () -> R,
    ): R {
        val source = path.toString()
        try {
            return block()
        } catch (e: ErbcFormatException) {
            throw if (e is ErbcLoadException) e else withSource(e, source)
        } catch (e: RuntimeException) {
            throw ErbcLoadException(
                "${prefix(source)}文件头无法解析（${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        }
    }

    /**
     * 目录装载：递归收集 [ARTIFACT_SUFFIX] 结尾的文件，按相对路径（`/` 分隔）排序后逐个装载。
     *
     * 排序是稳定输出的一部分：调用方拿到的列表顺序只由路径决定，与文件系统遍历顺序无关。
     * 续片（`.erbc.p1`）不以 [ARTIFACT_SUFFIX] 结尾，天然被排除。
     */
    fun loadDirectory(dir: Path): List<LoadedProgram> {
        val root = dir.toAbsolutePath().normalize()
        val paths = if (Files.isDirectory(root)) artifactFiles(root) else emptyList()
        return paths.map { p ->
            val rel = root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/')
            val bytes = fileBytes(p)
            LoadedProgram(rel, bytes.size, load(bytes, "$root/$rel"))
        }
    }

    /** 目录下的产物清单（递归、按相对路径排序；不读内容）。 */
    fun artifactFiles(dir: Path): List<Path> {
        val root = dir.toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) return emptyList()
        val out = ArrayList<Path>()
        Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().lowercase().endsWith(ARTIFACT_SUFFIX) }
                .forEach { out.add(it.toAbsolutePath().normalize()) }
        }
        out.sortBy { root.relativize(it).toString().replace('\\', '/') }
        return out
    }

    // ------------------------------------------------------------------
    // 段布局校验（补 reader 的剩余长度只到文件尾这一层）
    // ------------------------------------------------------------------

    private fun validateSections(
        header: ErbcHeader,
        bytes: ByteArray,
        source: String?,
    ) = validateSections(header, bytes.size, { off -> u32(bytes, off) }, source)

    /**
     * 每段的上界（不含）：段表里**下一个更大的段起点**，没有则是文件尾。
     *
     * 规约 §1.3 明确「v1 不做填充对齐，段与段紧挨着排」，所以下一个段起点就是本段能用的
     * 尽头；段表被改坏（段起点乱序 / 两段同起点）时这个上界仍然保守，不会把下一段的字节
     * 当本段正文放过。[sectionEnds] 是唯一的算法，[MappedErbc] 的段区间与下面的段长校验同源。
     */
    internal fun sectionEnds(
        header: ErbcHeader,
        size: Int,
    ): IntArray {
        val starts = ArrayList<Int>(header.sectionCount)
        for (i in 0 until header.sectionCount) {
            val off = header.offsets[i]
            if (off != 0) starts.add(off)
        }
        starts.sort()
        val ends = IntArray(header.sectionCount)
        for (i in 0 until header.sectionCount) {
            val off = header.offsets[i]
            ends[i] = if (off == 0) 0 else (starts.firstOrNull { it > off } ?: size)
        }
        return ends
    }

    /**
     * 段布局校验的两个字节来源共用这一份实现：
     * 全量读时 `u32At` 走 [ByteArray]，映射时走 [MappedErbc.u32At]（只映射 4 字节窗口）。
     * 报错消息与判定次序原样不动，映射路径因此不可能引入第二套错误口径。
     */
    internal fun validateSections(
        header: ErbcHeader,
        size: Int,
        u32At: (Int) -> Int,
        source: String?,
    ) {
        val firstSectionOffset = header.firstSectionOffset()
        val present = ArrayList<Int>()
        val seen = HashMap<Int, Int>()
        for (i in 0 until header.sectionCount) {
            val off = header.offsets[i]
            if (off == 0) continue
            present.add(i)
            if (off < firstSectionOffset) {
                throw ErbcDecodeException(
                    off,
                    "段 ${nameOf(i)} 偏移($off) 落在文件头+段表($firstSectionOffset 字节)内",
                )
            }
            if (off >= size) {
                throw ErbcDecodeException(off, "段 ${nameOf(i)} 偏移($off) 越过文件尾($size)")
            }
            val prev = seen.put(off, i)
            if (prev != null) {
                throw ErbcDecodeException(off, "段 ${nameOf(prev)} 与段 ${nameOf(i)} 声明了同一偏移($off)")
            }
        }
        val ends = sectionEnds(header, size)
        // 段起点 → 段号 → 该段上界；与 MappedErbc.endOf 完全同一个数组
        fun endOf(off: Int): Int {
            val id = header.offsets.indexOf(off)
            return if (id >= 0 && ends[id] != 0) ends[id] else size
        }

        for (i in present) {
            val off = header.offsets[i]
            val len = endOf(off) - off
            // 每个段正文至少有一个计数/长度字；短于此必是段表被改坏。
            if (len < 4) {
                throw ErbcDecodeException(off, "段 ${nameOf(i)} 的区间长度($len)不足 4 字节")
            }
        }

        val codeOff = header.offsets[ErbcFormat.SECTION_CODE]
        var instrCount = -1
        if (codeOff != 0) {
            val codeEnd = endOf(codeOff)
            val available = codeEnd - codeOff - 4
            val n = u32At(codeOff)
            if (n <= 0) throw ErbcDecodeException(codeOff, "CODE 段指令条数($n)必须为正")
            if (n.toLong() * 16L > available) {
                throw ErbcDecodeException(
                    codeOff,
                    "CODE 段声明 $n 条指令（需 ${n.toLong() * 16L} 字节），本段区间只剩 $available 字节",
                )
            }
            instrCount = n
        }

        val linesOff = header.offsets[ErbcFormat.SECTION_LINES]
        if (linesOff != 0) {
            val linesEnd = endOf(linesOff)
            val fileCount = u32At(linesOff)
            if (fileCount != 0) {
                throw ErbcDecodeException(
                    linesOff,
                    "行号表带源文件 id($fileCount)，但 Program.lines 只有行号（规约 §5.7）",
                )
            }
            val n = u32At(linesOff + 4)
            if (instrCount >= 0 && n != instrCount) {
                throw ErbcDecodeException(
                    linesOff + 4,
                    "行号表长度($n)与 CODE 段指令条数($instrCount)不符",
                )
            }
            val need = 8L + n.toLong() * 4L
            val available = (linesEnd - linesOff).toLong()
            if (n < 0 || need > available) {
                throw ErbcDecodeException(
                    linesOff,
                    "LINE_TABLE 声明 $n 行（需 $need 字节），本段区间只有 $available 字节",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun fileBytes(path: Path): ByteArray = try {
        Files.readAllBytes(path)
    } catch (e: java.io.IOException) {
        throw ErbcLoadException("$path: 读取失败（${e.javaClass.simpleName}: ${e.message}）", e)
    }

    private fun u32(bytes: ByteArray, at: Int): Int {
        // CMP-10 余项：`at + 4` 走 Long，避免 Int 回绕后判界放行。
        if (at < 0 || at.toLong() + 4L > bytes.size.toLong()) {
            throw ErbcDecodeException(at, "读取 u32 越界（文件长 ${bytes.size}）")
        }
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }

    private fun nameOf(section: Int): String =
        ErbcFormat.SECTION_NAMES.getOrElse(section) { "SECTION$section" }

    private fun prefix(source: String?): String = if (source == null) "" else "$source: "

    private fun withSource(
        e: ErbcFormatException,
        source: String?,
    ): ErbcFormatException = if (source == null) e else ErbcLoadException("$source: ${e.message}", e)
}
