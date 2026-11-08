package com.eratw.port.core.diag

import com.eratw.port.core.code.Program
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.SpriteEvictScratch
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.vars.VariableStore

/**
 * Java 堆的**结构账面**（L5 heap-census，端点复盘 EP1 波）。
 *
 * ## 为什么需要这一面（取证）
 *
 * 0.2.35 HONOR ALI-AN00 那份 `OutOfMemoryError`：
 * `Failed to allocate a 56 byte allocation with 4630976 free bytes ... giving up on allocation
 * because <1% of heap free after GC` —— **GC 之后还不到 1%**，说明 512 MiB 里的大头是**活对象**，
 * 不是来不及回收的垃圾。而报告此前只会写一个 `used/max` 总数（上一路 `wave/heap-budget` 已把
 * **native** 侧两块账面补上，**Java 堆侧至今零分项**），说不清那 500 MiB 里
 * 脚本程序、字符串池、变量池、行缓冲、图形 Java 像素、字形池**各占多少**。
 * 本类从我们自己持有的结构逐项计数、换算成字节，给报告一行分项。
 *
 * ## 每个分项的「判性」（EP3/L10 逐字段与类直方图对账后写死）
 *
 * 桌面 512m 臂（`devtools/heap_phase_probe.py --histo`，取证 `.local/heap-phase/READOUT.md`）把每一项
 * 归进五类之一，**判性就写在字段 KDoc 里**；读账面时必须连判性一起读，否则又会把「这一项有多大」
 * 当成「这一项占了多少堆」。五类是：
 * - **真读数**：公式与持有者的实际数组长度逐字相同（`列本体长度 × 元素宽`），没有乘错也没有乘漏；
 * - **声明尺寸高估**：按 `VariableSizes`/段头声明值算「这一列有多大」，而列是**按需分配**的
 *   ⇒ 未写的列一个字节都不占（[globalIntBytes] 与 [globalIntResidentBytes] 就是这一对）；
 * - **少乘少报**（病分类，本路只留 [charaIntPerCharBytes] 这一枚「每角色成本」读数）：
 *   上一波账面把**单角色**口径的读数当总量入账（原 `charaIntBytes`），整簇少报 ≈38 MiB；
 * - **结构性 0**：公式对、条件不成立 ⇒ 0 是真 0 但**读不出结论**（行缓冲四枚：装载臂既没还原
 *   console 也没推进打印，见 [displayLineCount]）；
 * - **桌面取不到(-1)**：null 接收者折 -1（图形两段在桌面 headless 恒 -1，只有真机能答）。
 *
 * 另有**「只有条数、没有字节项」**这一类（[functionCount] / [instructionCount] / [exprPoolEntries] /
 * [statementPoolEntries] / [strTableEntries]）：条数进不了求和，那一簇的字节**整笔在账外**
 * （直方图点名的 `FuncMeta` 4.2 MiB、串本体 `byte[]`+`String` 17.6 MiB 全在这里）。
 * 本路把三枚**已经存在**的物化读数（[materializedStatements] / [materializedFunctions] /
 * [flatResidentSegments]）请进账面，把「惰性池到底建了多少」从反射面挪到账面面。
 *
 * **和的口径**：探针自己的求和把 [charaReserveBytes] 也加进去了，而它在新口径下是
 * [charaIntBytes] + [charaStrBytes] 的**尾部子集**（重复计，实测 ≈5 MiB）⇒ 本类另给
 * [bookedSumBytes] 这把不重复计的和。两个数都在交付里出现，不许只贴好看的那个。
 *
 * ## 本臂对账结果（EP3/L10 复跑，同一份 `soak512/seed.dbg`、同一挡位；取证 `.local/heap-phase/`）
 *
 * `restored` 相位 post-GC live = 139 MiB（与上一波逐字同读数的臂）：
 * - 探针口径的和 **61 MiB ⇒ 43.9%**（改前 23 MiB ⇒ 17.7%），抬起来的 38 MiB 全是 `charaIntBytes`
 *   从「单角色宽」改成「容量行」这一枚；
 * - 去重后的 [bookedSumBytes] **56 MiB ⇒ 40.8%**（扣掉 5.06 MiB 重复计的容量冗余）；
 * - `assembled` 相位**必须并列读实占**：那里全局整数列实付 **16,800 B**、声明 16,765,800 B，
 *   所以按 [globalIntResidentBytes] 读只有 **1.9 MiB ⇒ 占 live 的 2.5%**（探针那个 17 MiB
 *   里 99.9% 是「声明尺寸」这笔还没付的账）；同一相位 `restored` 后是 16,697,800 B（99.6% 已付）
 *   ⇒ **声明一把尺在装载/还原两个相位差三个数量级**，这就是本类为什么把两枚都留在账上。
 *
 * **归不了因的残差**（139 − 56 ≈ 82 MiB，逐项点名自 `jcmd GC.class_histogram`）：
 * Map/Entry 容器壳四件套 27.0 MiB（诚实条款声明不计）、串本体 `byte[]`+`String` 17.6 MiB
 * （账面只入了串池估算 0.42 MiB）、`FuncMeta` 4.2 MiB（账面只给条数）、`short[]` 3.3 MiB
 * （行号表账面 4,840 B，来源未定名）、`int[]` 残差 5.0 MiB、`long[]` 残差 2.0 MiB、
 * 结构体/背衬数组（`Object[]`/`VarDef`/`DimSpec`/`ArrayList`）约 5.8 MiB、Top-14 之后长尾约 18 MiB。
 * 前四簇里 **11.75 万个 `LinkedHashMap` 的持有者与 7 条 `short[]` 要 hprof dominator** 才能定名
 * `TODO(verify)`；图形两段在桌面恒 **-1**（没有读数，不是 0）。
 *
 * ## 它回答什么、不回答什么（诚实条款，必读）
 *
 * 这是**估算口径的结构账面，不是 dominator tree**：
 * - 每个读数按「数组本体长度 × 元素宽」这种可判公式算，**对象头 / 8 字节对齐 / 引用装箱 /
 *   HashMap 背衬预留**一律不算 —— 所以它**系统性低估**真实驻留（少算了每个数组约 16 B 头与
 *   对齐、每个 `String` 的对象头与 `byte[]`/`char[]` 本体开销、池实例自身的壳）。
 * - 各分项来自不同持有者，**相加不是精确总量**（例如串表条目被变量槽引用，槽字节与串字节分列
 *   两处，相加会重复计到同一批 `String` 实例 —— 所以账面只给「这一项有多大」，不给「总量」硬账）。
 * - 真正的归因需要 **HPROF 直方图**（`am dumpheap` + 分析），那一步不在本车道，写进交付的
 *   「需组长接线/后续」，本类**不碰 hprof**。
 *
 * ## 只读、禁物化（三条铁律）
 *
 * 1. **纯 JVM**：本类在 core-vm，禁 Android API（`Debug.getNativeHeapAllocatedSize` 这类只在
 *    app 侧用）。
 * 2. **禁物化惰性表**：读数一律取 `*Count` / `estimate*Bytes` / `List.size` 这类**已驻留的标量**
 *    （惰性语句/表达式/函数表走段头声明值，不触发解码，见 `Program.statementCount` /
 *    `exprCount` / `MappedFunctionTable.size`）。**绝不**为取读数去遍历 `.erbc` 惰性表或
 *    `ErbcStream` 的无锁解码缓冲（`GameHost.wirePageFunctionTrail` KDoc 的教训原文）——
 *    报告线程踩它会把 VM 线程正在跑的解码踩坏。
 *    唯一的 O(n) 扫描是**装载侧字符串池字符数**（[stringPoolChars]），它走的是**已在堆内物化的
 *    `ConstantPool.strings` 逐条 `.length`**（装载末段 `for (s in strings) pool.addString(s)`
 *    一次性灌好的普通 `ArrayList<String>`，不是惰性表、不是无锁映射缓冲），且只在
 *    [fullScan] = true 时才做（崩溃报告的终点路径），页中钉发（VM 线程）一律 [fullScan] = false
 *    给 -1，绝不在热路径上扫十几万条串。
 *    第二条扫描线是本路新增的**全局整数列实占**（[globalIntResidentBytes]）：只走
 *    `Array<LongArray?>` 的**列槽位**（百余格）读引用与 `length`，元素一个都不碰、不分配，
 *    上界 = 列数（与角色数/语句数无关）⇒ 恒取、不分档。
 * 3. **禁抛**：每个读数 `runCatching`，取不到给 **-1**（不是 0，0 表示「确为零」）；
 *    诊断永远不许把游戏拖崩，最坏退化成「这一项没读数」。
 *
 * ## 线程模型
 *
 * [census] 只读活结构，与 `graphicsPoolReportLine` / `imageBookkeepingReportLine` 同一口径：
 * 崩溃/失败线程上允许近似（`GraphicsPool` 原子读、`SpritePool` 池内锁、`LineBufferConsoleSink`
 * 行表锁），任一步失败该项给 -1、整行仍可成文。页中钉发在 VM 线程（同线程直读，逐字确定）。
 * 禁静态可变：本 object 无可变状态，`census` 现算现回，入参出参之外不落进程级东西。
 */
object LiveSetCensus {

    private const val BYTES_PER_MIB = 1024L * 1024L
    /** UTF-16：一个 `String` 的 char 逻辑占 2 B（JDK 紧凑字符串实际是 latin1 1 B/char，账面按 2 B **高估**串本体，与「不算对象头」的低估方向相反，逐项注明）。 */
    private const val BYTES_PER_CHAR = 2L

    /**
     * 一份 Java 堆分项账面。每个字段是**字节**（`Long`）或**条数**（`Long`）；
     * **-1 = 取不到**（结构未装配 / 读抛出），**0 = 确实为零**。单位在成文时统一折 MiB。
     */
    data class Census(
        // ---- 装载侧（Program）----
        /**
         * 函数数：`program.functions.size`（惰性函数表取的是 `recordOffsets.size`，不触发解码）。
         *
         * 判性 = **只有条数、没有字节项**：直方图在 `restored` 相位点名 `FuncMeta` 110,072 实例
         * / 4.2 MiB，而这笔的持有者是**会话装配期建好的函数表**（上一波 OFFBOOK 行同相位读
         * `materializedFunctions=0` 就是证据：两者不同持有者）⇒ 4.2 MiB **在账外**。
         * 单实例浅尺寸两处不一致（直方图折 40 B vs `FuncMeta.kt:22` 记 56 B）⇒ 不据此下结论，
         * `TODO(verify)`（卡点：要 hprof 的实例尺寸分布，桌面直方图只给总数）。
         */
        val functionCount: Long,
        /**
         * 指令条数：绝对口径，`program.instructionCount`。
         * 判性 = **只有条数、没有字节项，但字节项在同侧**：4,888,249 条 × 4 B ≈ 18.6 MiB 本体走
         * 只读映射窗口，堆内的账由 [codeResidentBytes] 承担（映射档 = 0，真 0）⇒ 这一条不欠账。
         */
        val instructionCount: Long,
        /**
         * 全局槽总数：`program.globalSlots`（`G0..G(n-1)` 的格子数）。
         * 判性 = **只有条数、没有字节项**；格子本体在运行侧 [globalIntBytes] / [globalStrSlotBytes]。
         */
        val globalSlots: Long,
        /**
         * 表达式池条数：`program.exprCount`（惰性档读段头 `nodeCount`，不物化）。
         * 判性 = **只有条数、没有字节项**：兑现面是 [flatResidentSegments]（表达式根结点在片内），
         * 整池压扁实测 396 MiB 里「807k 个表达式根结点」那一半属本簇（`SegmentedFlatStmt.kt:12-15`）。
         */
        val exprPoolEntries: Long,
        /**
         * 语句池条数：`program.statementCount`（惰性档读段头 `totalCount`，不物化）。
         * 判性 = **只有条数、没有字节项**；「建了多少」见 [materializedStatements] 与 [flatBuiltSegments]。
         */
        val statementPoolEntries: Long,
        /**
         * 已物化的**语句树**条数：`program.materializedStatementCount`（`Program.kt:1179`，公开面）。
         *
         * 判性 = **真读数（条数）**：惰性装载完 = 0（`LazyStatementDecodeTest:179` 钉住），之后每
         * 第一次按索引取用一条才 +1。上一波只能反射取（探针的 `OFFBOOK` 行），本路请进账面。
         * **与片缓存是两条路**：树走本字段、扁平片走 [flatResidentSegments]，同一相位可以一读 0
         * 一读非 0 —— 不合并、不互相折算；每棵语句树的浅尺寸没有在册口径 ⇒ **不折字节**。
         */
        val materializedStatements: Long,
        /**
         * 已物化的函数元数据条数：`program.materializedFunctionCount`（`Program.kt:1267`，公开面）。
         * 判性 = **真读数（条数）**；与 [functionCount] 之比 = 惰性函数表的兑现度。
         * 别把它读成 [functionCount] 那一簇 4.2 MiB 的字节账（不同持有者，见该字段）。
         */
        val materializedFunctions: Long,
        /**
         * 分片惰性扁平源的**驻留片数**：`program.flatResidentSegmentCount`（本体 `SegmentedFlatStmt.kt:77`
         * 的 `internal val residentSegments`，本路经 `Program` 开放公开读面）。
         *
         * 判性 = **真读数（条数）**：LRU 表 `blocks.size`，装载臂实测 0 = 一片未建。
         * 上限判式在册：4,096 条/片 ≈0.94 MiB、缺省 64 片 ≈ 60 MiB（`SegmentedFlatStmt.kt:48-62`），
         * 但真机实测「开池增量 ≈114 MiB、调参无效」⇒ **片数不折字节**：那一簇多少由堆 dump 归因
         * （`TODO(verify)`，交付「需组长接线」第 4 条）。
         */
        val flatResidentSegments: Long,
        /**
         * 分片惰性扁平源的**累计已建片数**（含已被 LRU 淘汰的）：`program.flatBuiltSegmentCount`
         * （本体 `SegmentedFlatStmt.kt:73` 的 `internal var builtSegments`）。
         * 判性 = **真读数（条数）**；与 [flatResidentSegments] 之差 = 已淘汰片数（建过但已归还，不在堆里）。
         */
        val flatBuiltSegments: Long,
        /**
         * 指令流**堆内**常驻字节：`program.codeResidentBytes`。
         * 判性 = **结构性 0（真 0，不是估算写死）**：映射装载档 `residentCode == null` 时该属性直接
         * 返回 0（`Program.kt:990`），指令流本体在只读映射窗口里；内存档（编译器/测试）走同一条
         * 公式给正数 ⇒ 0 与正数都是真账，读 0 不许读成「漏读数」。
         */
        val codeResidentBytes: Long,
        /**
         * 行号表常驻字节：`program.lineTableResidentBytes`（`Program.kt:993` → `LineTable.bytes`
         * 的 `Program.kt:1653-1666`）。
         *
         * 判性 = **真读数 + 整除折 0 陷阱**：分块（稀疏）形态装载后堆上只剩块位表 + 已灌的块
         * （4,888,249 条 ⇒ 1,193 槽 ≈ **4,840 B**），那张 19.5 MB 的 u16 主表从不物化；
         * 折成 MiB 显示 0 ⇒ 读这一项要看字节原值（探针 `RAW` 行），**别把显示 0 读成没有**。
         */
        val lineTableResidentBytes: Long,
        /**
         * 调用点实参索引两张数组本体字节：`program.callArgIndexResidentBytes`（`Program.kt:1258`
         * → `CallArgIndex.residentBytes` `Program.kt:2340-2341`）。
         *
         * 判性 = **真读数但明欠一笔**：公式只有 `2×头 + pcs.size×4 + vals.size×4`（实测 1,208,992 B
         * = 1.15 MiB），实参表本体与 `CallArg` 实例按该属性 KDoc 自述「另按对象图计」= **明确在账外**
         * ⇒ 这一项是下界。
         */
        val callArgIndexResidentBytes: Long,
        /** 字符串池条目数：`program.stringCount`（绝对口径，池 `ArrayList.size`，不物化惰性）。判性 = **真读数（条数）**，字节在 [stringPoolChars]。 */
        val stringPoolEntries: Long,
        /**
         * 字符串池字符总数（**仅 [fullScan] 时非 -1**）：逐条 `program.string(i).length` 累加，
         * 读的是已物化的 `ConstantPool.strings`（普通 ArrayList，非惰性表/非无锁缓冲）。
         *
         * 判性 = **两向偏差相抵的本体估算**：字节按 ×2 折（[BYTES_PER_CHAR]）——JDK 9+ 紧凑串里
         * latin1 串实际 1 B/char（⇒ ×2 **高估**），而 ERB 语料串多为 CJK、coder 是 UTF16（⇒ ×2 **判准**）；
         * 两个方向都漏的是每条 `String` 的壳 + `byte[]` 头（≈40 B/条，17,474 条 ≈ 0.7 MiB）。
         * coder 分布未实测 ⇒ `TODO(verify)`（卡点：本车道不做串池逐条 coder 采样）。
         * [fullScan] = false 给 -1 是**热路径不取**，不是取不到。
         */
        val stringPoolChars: Long,

        // ---- 运行侧（VariableStore）----
        /**
         * 系统全局整数列**声明尺寸**字节：`store.estimateGlobalIntBytes()`（1D+2D+3D × 8 B）。
         *
         * 判性 = **声明尺寸高估**：三张 `Array<LongArray?>` 是**按需分配**的（`VariableStore.kt:93-134`，
         * null = 「这一列还没人写过」，读等价于读一个刚 new 的全 0 数组）⇒ 这一项读的是
         * 「这一列最大能到多少」，不是「现在占了多少堆」；真驻留见 [globalIntResidentBytes]。
         * **高估幅度实测三个数量级**（同臂 `NEWFIELDS` 行）：`assembled` 相位声明 16,765,800 B 而
         * 实付 **16,800 B**（存档未还原，只有 `resetValues()` 填过的那几列付了）；`restored` 相位
         * 实付 16,697,800 B（99.6% 已付，差 68,000 B）⇒ **装载相位这一项几乎全是「还没付的账」**，
         * censusSum 在两个相位不许用同一个信心读。直方图对账（`restored`）：`long[]` 整簇
         * 59,091,016 B = 角色整数格 40,292,224 + 本项实付 16,697,800 + 残差 2,100,992（**96.4%** 已归因）。
         */
        val globalIntBytes: Long,
        /**
         * 系统全局整数列**实际已分配**字节：`store.globalIntResidentBytes()`
         * （非 null 列的 `LongArray.length` 求和 × 8 B；O(列数) 扫描，**不碰元素、不分配、不物化**）。
         *
         * 判性 = **真读数（数组本体，不含头/对齐 ⇒ 轻微低估）**：与 [globalIntBytes] 并列读才知道
         * 「声明 16 MiB 里实际付了几 MiB」；两者之差 = 没人写过的列（本臂装载相位那一笔 =
         * 16,749,000 B，还原后 = 68,000 B —— 同一份产物的两个相位差三个数量级）。
         * [fullScan] 无关（本项恒取，代价是百余次引用读）。
         */
        val globalIntResidentBytes: Long,
        /**
         * 系统全局字符串列格子数：`(Σ str1D[i] + 1) × 4 B`（每格一个池下标 Int，`+1` 是 `gStr0`）。
         * 判性 = **真读数**：`gStr1`/`gStr0` 是构造期一次分满的 `IntArray`（`VariableStore.kt:144-145`）
         * ⇒ 声明即实占，与整数列的按需分配**不同形**；**串本体在账外**（走驻留池，见 [strTableEntries]）。
         */
        val globalStrSlotBytes: Long,
        /** 角色数：`store.characters.count`（活跃角色行）。判性 = **真读数**。 */
        val charaCount: Long,
        /**
         * 角色表已分配容量：`store.characters.allocatedCapacity`（≥ [charaCount]）。
         * 判性 = **真读数**，而且是 [charaIntBytes]/[charaStrBytes] 的**乘数**：列数组按容量行长分配
         * （`CharacterTable.kt:75` `LongArray(columnElements(capacity, blockSize))`、`:78-82` 扩容同形）
         * ⇒ 按 count 乘就会少报 `(capacity − count)` 行（本臂 193 vs 168 = 少 25 行 ≈ 5 MiB）。
         */
        val charaCapacity: Long,
        /**
         * 角色表整行字节（五族列定义求和，不含容量冗余）：`store.characters.rowBytes()`
         * （`CharacterTable.kt:356-364`，整数列 `blockSize×8` + 串列 `blockSize×4`）。
         * 判性 = **真读数（每行宽度）**；本臂 212,388 B/行。
         */
        val charaRowBytes: Long,
        /**
         * 角色表整数列**本体**字节：`store.characterIntResidentBytes()`（int0/int1/int2 逐列
         * `data.size × 8 B` 求和）—— 读**实际数组长度**，不是「每行宽 × 猜的行数」。
         *
         * **本路修的账（EP3/L10）**：改前这里是 `store read { estimateBytesPerCharacter() }`，而那枚
         * 函数的 KDoc 自述是「**单个角色**占用的整数字节数」（`VariableStore.kt:3173-3175`）
         * ⇒ 账面把「一行宽」当「整簇总量」入账，五类里的 **少乘少报**：本臂实测 208,768 B vs
         * 应计 40,292,224 B（193 行），少报 ≈38.4 MiB —— 正是直方图 `long[]` 56.3 MiB 与账面
         * 16.2 MiB 之间那口气（取数：`.local/heap-phase/probe-20261102-161404.census.txt`）。
         *
         * **乘容量而不是乘 count**（上一波接线建议的是 count，这里按实测改判），三条依据都不靠猜：
         * 1. 结构：`LongCol.data = LongArray(columnElements(capacity, blockSize))`（`CharacterTable.kt:75`），
         *    `grow()`/`permute()` 也按容量整列重分配（`:78-82`、`:102-108`）⇒ 堆上那条 `long[]`
         *    的长度就是**容量行**；count 行之上的空尾照样占着堆（占位不是垃圾，只有 `clear()`/
         *    再扩容才归还）；
         * 2. 等式：本读数 ≡ `estimateBytesPerCharacter() × allocatedCapacity`（`LiveSetCensusTest`
         *    逐值钉住），而 × `count` 少 `(capacity − count)` 行（本臂 25 行 ≈ 5 MiB）；
         * 3. 直方图对账（同臂复跑，post-GC live 139 MiB）：`long[]` 单项 59,091,016 B；
         *    **容量口径 + 全局实付 = 40,292,224 + 16,697,800 = 56,990,024 B（96.4% 归因）**，
         *    count 口径 = 35,073,024 + 16,697,800 = 51,770,824 B（87.6%，多出的 7.3 MiB 说不清）。
         *
         * 判性 = **真读数（数组本体，不含 17,296 条数组各约 16 B 头 ⇒ 轻微低估）**。
         * **与 [charaReserveBytes] 重叠**：容量入账后空尾已在内 ⇒ 不重复计的和见 [bookedSumBytes]。
         */
        val charaIntBytes: Long,
        /**
         * **单个角色**的整数格字节（改前的旧口径原值：`store.estimateBytesPerCharacter()`）。
         *
         * 判性 = **每角色成本，不入和**：留着它读「加一只角色要多少堆」（本臂 208,768 B ≈ 0.2 MiB）
         * 与「容量 193 / count 168 ⇒ 25 行 ≈ 5 MiB 是占位不是活数据」。它是 [charaIntBytes] 的
         * **被乘数**，再进求和就是重复计 ⇒ [bookedSumBytes] 不含它。
         */
        val charaIntPerCharBytes: Long,
        /**
         * CSTR/角色字符串列**本体**字节：`(Σ str0.blockSize + Σ str1.blockSize) × capacity × 4 B`
         * （每格一个驻留池下标 Int，非串本体；串本体在 [stringPoolEntries]/运行串表另一列）。
         * 判性 = **真读数（列本体）**：`StrCol.data = IntArray(columnElements(capacity, blockSize))`
         * （`CharacterTable.kt:113`）⇒ 乘容量与实际数组长度逐字相同；直方图那 17.6 MiB
         * （`byte[]`+`String`）是串本体簇，**明确在账外**。与 [charaReserveBytes] 同样重叠尾部。
         */
        val charaStrBytes: Long,
        /**
         * 容量冗余字节：`(capacity - count) × rowBytes`（**移植侧独有的一笔**，原版按角色逐条建、
         * 没有为不存在角色预留的整行；见 `CharacterTable.RESERVE_TAIL_FLOOR_ROWS` 的判性）。
         * 这是「贴顶的活对象」里最可疑的一块，负数按 0。
         *
         * 判性 = **真读数但已被上两列含住（诊断读数，不入和）**：`rowBytes` 恰是 [charaIntBytes]
         * 与 [charaStrBytes] 的每行宽（`CharacterTable.kt:356-364`），所以这 `(capacity − count)` 行
         * 在容量口径下**已经计过一次**（本臂重复计 5,309,700 B ≈ 5.06 MiB）⇒ 本字段回答的是
         * 「这些堆里有多少在为不存在的角色占位」，求和请用 [bookedSumBytes]
         * （探针自己的 `censusSumMiB` 仍把它加进去了，交付两数并列）。
         */
        val charaReserveBytes: Long,
        /**
         * 运行期字符串驻留池条目数：`store.pool.distinctCount`（含下标 0 空串；串本体字节读不到，
         * 见类 KDoc，故不给串池字节项）。
         * 判性 = **只有条数、没有字节项**：本臂 3,925 条，直方图 `byte[]` 11.9 + `String` 5.7 MiB
         * 这一簇的主要来源之一，整笔在账外（装载侧串池只入了 [stringPoolChars] 那 0.42 MiB）。
         */
        val strTableEntries: Long,

        // ---- 行缓冲（LineBufferConsoleSink）----
        /**
         * 显示行数：`console.displayLineCount`（含临时行/折行续行，池内锁快照）。
         * 判性 = 桌面取证臂上是 **结构性 0**：`SnapShots.restore` 走 loadData 语义、console 明确
         * 不还原（`tools/vmdbg` 的 `Explore.kt:69`），泵又一发未推进（`passes=0`）⇒ 读数期间没有任何打印。
         * **0 不许读成「行缓冲不是大头」**——那一问在本臂既证不实也证不伪。
         */
        val displayLineCount: Long,
        /** 逻辑行数：`console.lineCount`（LINECOUNT 口径）。判性同 [displayLineCount]（结构性 0）。 */
        val logicalLineCount: Long,
        /**
         * 行内段（Segment）总数（**仅 [fullScan]**）：`console.lines()` 快照后逐行 `segments.size`
         * 累加；`lines()` 是 `synchronized` 内的一次 `toList()`（分配一份引用快照），热路径不做 ⇒
         * [fullScan] = false 给 -1。
         * 判性 = **fullScan=false 时「热路径不取」(-1)**；装载臂上同 [displayLineCount] 属结构性 0。
         */
        val displaySegmentCount: Long,
        /**
         * 浮岛（HTML island）条数：`console.htmlIslands().size`（同步快照，行数有限，恒取）。
         * 「待发的打印缓冲」只有 `printingBufferEmpty` 一个布尔读数（无公开计数口，见交付「需组长接线」）——
         * 这里给 0（空）/1（非空，但条数字节数未知，故不折字节）。
         * 判性 = **布尔读数、不是字节**：0/1 两态，非空时也说不清多少 ⇒ **不参与求和**（探针也不折它）。
         */
        val pendingPrintBufferNonEmpty: Long,

        // ---- 图形 Java 侧（正负两段像素，复用既有尺，不另造）----
        /**
         * 正号段驻留字节：`GraphicsPool.residentBytes()`（原子读、跨线程安全）。
         * 2026-11-06（账本口径修正，见 `GraphicsBuffer.publishSurfaceLocked`）起它答的是
         * **这一代真实占有的 Java 堆字节** = 已解码像素（4 B/像素）+ 降级代仍被闭包强引用的
         * PNG 载荷 —— 不再是纯像素字节，堆 dump 的 LOS 与本列对账不会再被账外载荷污染。
         * 与负号段互不重叠。
         * 判性 = **桌面取不到(-1)**：探针与 headless 会话传 null 接收者 ⇒ 按「null ⇒ -1」口径折 -1，
         * **不是 0、更不许折成 0 充数**。「正号段像素是不是大头」只有真机能答（宿主从 `store.aux`
         * 取真池喂进来，`GameHost.kt:3068-3080`）⇒ 真机侧一律 `TODO(verify)`（卡点：本机无设备）。
         */
        val gfxResidentPixelBytes: Long,
        /**
         * 负号段 CSV 父图像素驻留字节：`SpritePool.resourceResidentPixelBytes()`（**复用这个口径，
         * 别另造一把尺**，见 heap-budget 车道与 `graphicsPoolReportLine`）。池内锁 O(资源 sprite 数)，
         * 崩溃路径可接受；[fullScan] = false 时页中钉发也照取（本表最贴顶的一项）。
         * 判性 = **桌面取不到(-1)**，同 [gfxResidentPixelBytes]。
         */
        val csvResidentPixelBytes: Long,
    ) {
        /** 字节 → MiB（负数原样返回 = 「无读数」哨兵，不折 0）。 */
        fun miB(bytes: Long): Long = if (bytes < 0L) bytes else bytes / BYTES_PER_MIB

        /**
         * 成文：单行、逐项 `名=<值>MiB`（计数项不带 MiB）；-1 项写成 `名=-`（缺读数不缺整行）。
         *
         * 串里**不含换行**（调用方当一行塞进报告行表，口径同 `graphicsPoolReportLine`）。
         * 字节项折 MiB、计数项原样；每条自带误差方向的关键限定词（「声明」「实占」「容量行」
         * 「已含上两列」），免得读者把结构账面读成 dominator tree 的定论。
         *
         * **两处面宽度约束（改动前先看这条）**：
         * - 行首 `Java 堆结构账面` 与 `｜装载：函数 <n> 指令 <n>` 的形状被复盘脚本钉住
         *   （`devtools/triage_reports.py` 的 `HEAPCENSUS_RE` + `HEAP_CENSUS_REPORT_PREFIX`），
         *   加项只许**往后追加**或改中段的限定词，动这两处字面要先过 `--selftest`；
         * - 轨迹行没有单行截断（`RunWatch` 只按 `MAX_LINES` 64 行滚动，`RunWatch.kt:400`），
         *   但这一行进的是 64 行的环形表 ⇒ 别把它写成 1 KiB 的散文，新增项一律「名 数」短形。
         */
        fun render(): String = buildString {
            append("Java 堆结构账面（估算口径，非 dominator tree；对象头/对齐不计=低估，串按 UTF-16=高估串本体）")
            append(" ｜装载：函数 ").append(v(functionCount))
            append(" 指令 ").append(v(instructionCount))
            append(" 全局槽 ").append(v(globalSlots))
            append(" 表达式 ").append(v(exprPoolEntries))
            append(" 语句 ").append(v(statementPoolEntries))
            append(" 物化语句 ").append(v(materializedStatements))
            append(" 物化函数 ").append(v(materializedFunctions))
            append(" 扁平片驻/建 ").append(v(flatResidentSegments)).append("/").append(v(flatBuiltSegments))
            append(" ｜指令流常驻 ").append(mibTag(codeResidentBytes)).append("MiB")
            append(" 行表 ").append(mibTag(lineTableResidentBytes)).append("MiB")
            append(" 实参索引 ").append(mibTag(callArgIndexResidentBytes)).append("MiB")
            append(" 串池 ").append(v(stringPoolEntries)).append(" 条")
            if (stringPoolChars >= 0L) {
                append("/约 ").append(miB(stringPoolChars * BYTES_PER_CHAR)).append("MiB")
            }
            append(" ｜运行：全局整数(声明) ").append(mibTag(globalIntBytes)).append("MiB")
            append(" 全局整数(实占) ").append(mibTag(globalIntResidentBytes)).append("MiB")
            append(" 全局串格 ").append(mibTag(globalStrSlotBytes)).append("MiB")
            append(" 角色 ").append(v(charaCount)).append("/容 ").append(v(charaCapacity))
            append(" 整数(容量行) ").append(mibTag(charaIntBytes)).append("MiB")
            append(" CSTR ").append(mibTag(charaStrBytes)).append("MiB")
            append(" 每角色整数 ").append(rawTag(charaIntPerCharBytes)).append("B")
            append(" 容量冗余(已含上两列) ").append(mibTag(charaReserveBytes)).append("MiB")
            append(" 驻留串表 ").append(v(strTableEntries)).append(" 条")
            append(" ｜行缓冲：显示 ").append(v(displayLineCount)).append(" 逻辑 ").append(v(logicalLineCount))
            if (displaySegmentCount >= 0L) append(" 段 ").append(v(displaySegmentCount))
            if (pendingPrintBufferNonEmpty >= 0L) {
                append(" 待发缓冲 ").append(if (pendingPrintBufferNonEmpty == 0L) "空" else "非空")
            }
            append(" ｜图形 Java：正号段像素 ").append(mibTag(gfxResidentPixelBytes)).append("MiB")
            append(" 负号段 CSV ").append(mibTag(csvResidentPixelBytes)).append("MiB")
        }

        /**
         * 不重复计的分项和（**字节**）：口径见 [byteFields]。
         *
         * 与探针自己那行 `censusSumMiB` 的**两处**差别（交付里两个数并列给，不许只贴好看的那个）：
         * 1. 探针另把 [charaReserveBytes] 加了进去 —— 新口径下那是 [charaIntBytes]+[charaStrBytes]
         *    的尾部子集（本臂重复计 ≈5.06 MiB）；
         * 2. 探针用的全局整数是 [globalIntBytes]（声明尺寸、按需分配 ⇒ 高估），本和用
         *    [globalIntResidentBytes]（实占）。
         *
         * 取不到（-1）的项**跳过不折 0**，缺几项由 [bookedMissingFieldCount] 说明 ⇒ 只要那一项
         * 大于 0，这个和就是**下界**，没资格被读成「总量」。
         */
        fun bookedSumBytes(): Long {
            var total = 0L
            for (b in byteFields()) if (b >= 0L) total += b
            return total
        }

        /** [bookedSumBytes] 里被跳过的 -1 项个数（>0 ⇒ 和是下界）。 */
        fun bookedMissingFieldCount(): Int = byteFields().count { it < 0L }

        /**
         * 进 [bookedSumBytes] 的字节项（去重口径）：装载侧四项 + 串池本体（[stringPoolChars] ×2，
         * 取不到随它给 -1）+ 运行侧**实占**全局整数 / 全局串格 / 角色整数（容量行）/ 角色串格 +
         * 图形两段。
         *
         * **不入和**的三枚，逐个理由：[charaReserveBytes]（上两列的尾部子集，重复计）、
         * [charaIntPerCharBytes]（被乘数，只作每角色成本）、[globalIntBytes]（声明尺寸，与
         * [globalIntResidentBytes] 是同一批数组的两种口径，取实占）。
         * [pendingPrintBufferNonEmpty] 是布尔读数，本来就没有字节口径。
         */
        private fun byteFields(): List<Long> = listOf(
            codeResidentBytes,
            lineTableResidentBytes,
            callArgIndexResidentBytes,
            if (stringPoolChars < 0L) -1L else stringPoolChars * BYTES_PER_CHAR,
            globalIntResidentBytes,
            globalStrSlotBytes,
            charaIntBytes,
            charaStrBytes,
            gfxResidentPixelBytes,
            csvResidentPixelBytes,
        )

        /** 计数/未知统一成文：-1 写成 `-`。 */
        private fun v(n: Long): String = if (n < 0L) "-" else n.toString()

        /** 字节折 MiB 的读数：-1 写成 `-`（缺项），非负给整除 MiB。 */
        private fun mibTag(bytes: Long): String =
            if (bytes < 0L) "-" else (bytes / BYTES_PER_MIB).toString()

        /** 亚 MiB 的字节读数：按**字节原值**成文（整除折 MiB 会把 208,768 B 读成 0），-1 仍写 `-`。 */
        private fun rawTag(bytes: Long): String = if (bytes < 0L) "-" else bytes.toString()
    }

    /**
     * 现算一份分项账面。任一持有者为 null ⇒ 它负责的每一项给 -1（会话没建 / 未装配），**绝不抛**。
     * 每个读数走 [read]（`runCatching` 同语义：抛出给 -1，不半报一个假数）。
     *
     * @param program  执行用 Program（null = 未装载）
     * @param store    变量池（null = 无会话）
     * @param console  行缓冲 sink（null = 不追踪显示行的宿主）
     * @param gfxPool  正号段图形池（null = 图形通路未装）
     * @param spritePool 负号段精灵池（null = 精灵池未装）
     * @param fullScan 是否做 O(n) 扫描（字符串池字符数、行段数）：崩溃报告终点路径传 true，
     *                 页中钉发（VM 线程）传 false —— 见类 KDoc「只读、禁物化」第 2 条
     * @param residentFace 宿主持有的长生命周期身份去重面（[SpriteEvictScratch]），透传给
     *                 [SpritePool.resourceResidentPixelBytes] 的**面臂**（census3 波）。
     *                 **null（缺省）= 旧形状逐字不变**：那一支在池锁内现搭 `HashSet`，
     *                 按资源表登记数长（真语料 38,861 条实测 610,672 B/发，见
     *                 `ResourceResidentSnapScratchTest` 的「量·资源表规模曲线」）。本类的两个
     *                 生产消费点（`GameHost.censusTrailLine` 的堆 ≥90% 贴顶钉发、
     *                 `GameHost.memoryCensusReportLine` 的崩溃报告行）恰好都跑在**唯一不能
     *                 分配的时刻**，故开这一口给宿主透传它那块常驻面
     *                 （`GameHost.reportResidentScratch`，与 `graphicsPoolReportLine` 同一块）。
     *                 线程口径由池锁给出：面只在该池监视器内被摸，两线程不会同时在面里
     *                 （`snapshotResourceResidentBytesWithFaceLocked` 的「线程口径」一节）；
     *                 面归调用方所有、每次进池先 `clear()`，本类不缓存不外借。
     *
     * ## 新增的四枚物化读数与 [fullScan] 无关（本路补的账都在这上面）
     *
     * [Census.materializedStatements] / [Census.materializedFunctions] / [Census.flatResidentSegments]
     * / [Census.flatBuiltSegments] 与 [Census.globalIntResidentBytes] 全是**已驻留标量**
     * （计数器 / `LinkedHashMap.size` / 列数组的 `length`），既不解码也不建片 ⇒ 热路径钉发
     * （[fullScan] = false）照取，不需要接线分档。特别地：读 `flatStmtSource` 走的是
     * `Program.flatResidentSegmentCount` 那条**只读**门面，源未构建时给 0，**绝不**调
     * `ensureFlatStmtSource()` 为取读数去建一个惰性源（那会把 60 MiB 的片缓存装进报告线程）。
     */
    fun census(
        program: Program?,
        store: VariableStore?,
        console: LineBufferConsoleSink?,
        gfxPool: GraphicsPool?,
        spritePool: SpritePool?,
        fullScan: Boolean,
        residentFace: SpriteEvictScratch? = null,
    ): Census = Census(
        functionCount = program read { functions.size.toLong() },
        instructionCount = program read { instructionCount.toLong() },
        globalSlots = program read { globalSlots.toLong() },
        exprPoolEntries = program read { exprCount.toLong() },
        statementPoolEntries = program read { statementCount.toLong() },
        materializedStatements = program read { materializedStatementCount.toLong() },
        materializedFunctions = program read { materializedFunctionCount.toLong() },
        flatResidentSegments = program read { flatResidentSegmentCount.toLong() },
        flatBuiltSegments = program read { flatBuiltSegmentCount.toLong() },
        codeResidentBytes = program read { codeResidentBytes },
        lineTableResidentBytes = program read { lineTableResidentBytes },
        callArgIndexResidentBytes = program read { callArgIndexResidentBytes },
        stringPoolEntries = program read { stringCount.toLong() },
        stringPoolChars = if (program == null || !fullScan) -1L else countStringChars(program),

        globalIntBytes = store read { estimateGlobalIntBytes() },
        globalIntResidentBytes = store read { globalIntResidentBytes() },
        globalStrSlotBytes = store read {
            (registry.sizes.str1D.sumOf { it.toLong() } + 1L) * 4L
        },
        charaCount = store read { characters.count.toLong() },
        charaCapacity = store read { characters.allocatedCapacity.toLong() },
        charaRowBytes = store read { characters.rowBytes() },
        // 总量口径：**读实际列数组长度**（`store.characterIntResidentBytes()`），它与
        // `estimateBytesPerCharacter() × allocatedCapacity` 逐字相等、与乘 count 不相等
        // —— 选边证据在字段 KDoc，等式由 LiveSetCensusTest 钉住。
        charaIntBytes = store read { characterIntResidentBytes() },
        charaIntPerCharBytes = store read { estimateBytesPerCharacter() },
        charaStrBytes = store read {
            val perRow = (characters.str0.sumOf { it.blockSize.toLong() } +
                characters.str1.sumOf { it.blockSize.toLong() })
            perRow * characters.allocatedCapacity.toLong() * 4L
        },
        charaReserveBytes = store read {
            val spare = characters.allocatedCapacity.toLong() - characters.count.toLong()
            if (spare <= 0L) 0L else spare * characters.rowBytes()
        },
        strTableEntries = store read { pool.distinctCount.toLong() },

        displayLineCount = console read { displayLineCount.toLong() },
        logicalLineCount = console read { lineCount },
        displaySegmentCount = if (console == null || !fullScan) -1L else countSegments(console),
        pendingPrintBufferNonEmpty = console read { if (printingBufferEmpty) 0L else 1L },

        gfxResidentPixelBytes = gfxPool read { residentBytes() },
        csvResidentPixelBytes = spritePool read { resourceResidentPixelBytes(residentFace) },
    )

    /**
     * 装载侧字符串池字符数：遍历 `ConstantPool.strings` 的逐条 `.length`（堆内已物化，非惰性表）。
     * 越界/异常给 -1（整串不报，不半报一个假数）。
     */
    private fun countStringChars(program: Program): Long {
        val n = runCatching { program.stringCount }.getOrDefault(-1)
        if (n < 0) return -1L
        var total = 0L
        for (i in 0 until n) {
            val len = runCatching { program.string(i).length }.getOrNull() ?: return -1L
            total += len.toLong()
        }
        return total
    }

    /** 行段总数：一次 `lines()` 快照（synchronized 内 toList），逐行 `segments.size` 累加；抛则 -1。 */
    private fun countSegments(console: LineBufferConsoleSink): Long = runCatching {
        console.lines().sumOf { it.segments.size.toLong() }.toLong()
    }.getOrDefault(-1L)

    /** `x read { ... }` 的读数糖：null 接收者或抛出都给 -1L（`runCatching` 同语义，不遮蔽 stdlib 名）。 */
    private inline infix fun <T : Any> T?.read(block: T.() -> Long): Long =
        if (this == null) -1L else try {
            block()
        } catch (_: Throwable) {
            -1L
        }
}
