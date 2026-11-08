package com.eratw.port.core.diag

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LiveSetCensus] 定向单测（L5 heap-census，契约 CONTRACT-EP1-3LANE §2/L5 验收口径）。
 *
 * 两条主形（契约点名）：
 * 1. **没有会话时全部给 -1/0、绝不抛** —— [census] 五参全 null：装载/运行/行缓冲每一项都给 -1
 *    （未装配 = 无读数，不是 0），图形两段也是 -1，[LiveSetCensus.render] 不抛。
 * 2. **装载 N 条指令后条目数与 N 一致** —— 汇编恰好 N 条指令，`instructionCount` 项 = N，
 *    且账面里那句「指令 N」逐字出现。
 *
 * 另钉几条形状：
 * - 变量池建好（未灌角色）⇒ 角色数 0、容量起步、驻留串表 1 条（只有下标 0 空串）。
 * - 灌 K 个角色 ⇒ 角色数 = K（`store.characters.count` 直读，非估算）。
 * - [LiveSetCensus.render] 单行、不含换行（调用方按一行塞进报告行表）。
 *
 * **EP3/L10（census-histo）新增四族**，全部对着 L7 的取证基准钉（基准行见各用例 KDoc）：
 * 1. `charaIntBytes` 的**少乘少报**修好后必须等于「每行宽 × 列数组实际行数」，且**不等于**
 *    「每行宽 × count」（差 = 空尾行 × 每行宽）；
 * 2. 全局整数列的**声明 vs 实占**两笔（按需分配 ⇒ 没写过时实占 0、声明为正）；
 * 3. 三枚**现成物化读数**进账面，并且取读数**不建**惰性扁平源；
 * 4. `render()` 的既有标签与新增标签逐字在册（复盘脚本 `triage_reports.py` 在钉旧行）。
 * 另有 [LiveSetCensus.Census.bookedSumBytes] 的去重口径：`charaReserveBytes`（尾部子集）与
 * `charaIntPerCharBytes`（被乘数）不入和 —— 探针自己那份 `censusSumMiB` 含冗余，交付两数并列。
 *
 * 纯 JVM：只喂 [Assembler] 现编的内存 Program + 新建 [VariableStore]（真语料不进场，
 * 本类不吃 `.erbc`，也就没有「物化惰性表」的踩点风险；fullScan 路径单测另证）。
 */
class LiveSetCensusTest {

    private fun programWithInstructions(n: Int): com.eratw.port.core.code.Program {
        // .func main + (n-1) 条 _NOP + 末尾 _HALT = 恰好 n 条指令（首条不计，函数头不产生指令）。
        val body = buildString {
            append(".func main slots=1\n")
            repeat(n - 1) { append("  _NOP\n") }
            append("  _HALT\n")
        }
        return Assembler.assemble(body)
    }

    /**
     * 现编一条**语句池非空**的程序（形状同 `StabilityL2FlatCacheTest.sampleProgram`）：
     * [statementCount] 条 `WAIT` 语句 + 一条 `_HALT`。扁平分片源只按**语句**建片，
     * 而 `Assembler` 的 `_NOP`/`_HALT` 不产生语句 ⇒ 测片数必须走这条fixture。
     */
    private fun programWithStatements(statements: Int): com.eratw.port.core.code.Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val ids = ArrayList<Int>(statements)
        for (i in 0 until statements) {
            ids.add(b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), i + 1, "")))
        }
        for (k in ids.indices) b.emit(Opcode.WAIT, ids[k], 0, SlotAddr.NONE, k + 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    // ---- 形 1：无会话全 -1/0 且绝不抛 ----

    @Test
    fun `全空入参每一项都是无读数哨兵`() {
        val c = LiveSetCensus.census(null, null, null, null, null, fullScan = false)
        // 装载侧
        assertEquals(-1L, c.functionCount)
        assertEquals(-1L, c.instructionCount)
        assertEquals(-1L, c.globalSlots)
        assertEquals(-1L, c.exprPoolEntries)
        assertEquals(-1L, c.statementPoolEntries)
        // 本路新增的三枚物化读数：无 Program ⇒ 同样给哨兵，不许给 0（0 = 「一片没建」是真读数）
        assertEquals(-1L, c.materializedStatements)
        assertEquals(-1L, c.materializedFunctions)
        assertEquals(-1L, c.flatResidentSegments)
        assertEquals(-1L, c.flatBuiltSegments)
        assertEquals(-1L, c.codeResidentBytes)
        assertEquals(-1L, c.lineTableResidentBytes)
        assertEquals(-1L, c.callArgIndexResidentBytes)
        assertEquals(-1L, c.stringPoolEntries)
        assertEquals(-1L, c.stringPoolChars)
        // 运行侧
        assertEquals(-1L, c.globalIntBytes)
        assertEquals(-1L, c.globalIntResidentBytes)
        assertEquals(-1L, c.globalStrSlotBytes)
        assertEquals(-1L, c.charaCount)
        assertEquals(-1L, c.charaCapacity)
        assertEquals(-1L, c.charaRowBytes)
        assertEquals(-1L, c.charaIntBytes)
        assertEquals(-1L, c.charaIntPerCharBytes)
        assertEquals(-1L, c.charaStrBytes)
        assertEquals(-1L, c.charaReserveBytes)
        assertEquals(-1L, c.strTableEntries)
        // 行缓冲
        assertEquals(-1L, c.displayLineCount)
        assertEquals(-1L, c.logicalLineCount)
        assertEquals(-1L, c.displaySegmentCount)
        assertEquals(-1L, c.pendingPrintBufferNonEmpty)
        // 图形 Java 侧
        assertEquals(-1L, c.gfxResidentPixelBytes)
        assertEquals(-1L, c.csvResidentPixelBytes)
        // 成文不抛、每缺项写成 `-`
        val line = c.render()
        assertTrue("函数 -" in line, "缺项必须以 `-` 出现：$line")
        // 十枚字节项全取不到 ⇒ 和是 0 但**缺 10 项**：只读 `bookedSumBytes` 会读成「堆是空的」
        assertEquals(0L, c.bookedSumBytes())
        assertEquals(10, c.bookedMissingFieldCount(), "字节项全缺（含串池/图形两段）⇒ 和只能读作下界")
        assertTrue("-" in c.render())
    }

    // ---- 形 2：装载 N 条指令后条目数与 N 一致 ----

    @Test
    fun `装载若干条指令后指令数逐字等于装配条数`() {
        val n = 7
        val p = programWithInstructions(n)
        // 交叉验证：先确认 Assembler 真产 n 条，再让账面读同一个数。
        assertEquals(n.toLong(), p.instructionCount.toLong(), "fixture 自身应当恰好 n 条指令")
        val c = LiveSetCensus.census(p, null, null, null, null, fullScan = false)
        assertEquals(n.toLong(), c.instructionCount, "账面指令数必须等于装载条数")
        // 函数表只 .func main 一个（入口隐式函数不计成额外条目）
        assertTrue(c.functionCount >= 1L, "至少一个 main 函数，实际 ${c.functionCount}")
        // 内存 Program 的 codeResidentBytes 非 0（IntArray 来源，指令流常驻堆）
        assertTrue(c.codeResidentBytes > 0L, "内存档指令流应给正常驻字节，实际 ${c.codeResidentBytes}")
        assertTrue("指令 $n" in c.render(), "render 里指令数必须逐字为 n：${c.render()}")
    }

    // ---- fullScan 开关：串池字符数只在全量扫时非 -1 ----

    @Test
    fun `fullScan 关闭时串池字符数给哨兵开启时非负`() {
        val p = programWithInstructions(3)
        val light = LiveSetCensus.census(p, null, null, null, null, fullScan = false)
        assertEquals(-1L, light.stringPoolChars, "热路径不扫字符串池")
        val full = LiveSetCensus.census(p, null, null, null, null, fullScan = true)
        assertTrue(full.stringPoolChars >= 0L, "全量扫应给非负字符数，实际 ${full.stringPoolChars}")
    }

    // ---- 运行侧：新建 store（未灌角色）的读数 ----

    @Test
    fun `空变量池角色数为零且驻留串表只有空串一条`() {
        val store = VariableStore()
        val c = LiveSetCensus.census(null, store, null, null, null, fullScan = false)
        assertEquals(0L, c.charaCount, "新建 store 没有角色")
        assertTrue(c.charaCapacity >= 1L, "起步容量至少 1，实际 ${c.charaCapacity}")
        assertEquals(1L, c.strTableEntries, "驻留池只含下标 0 空串")
        assertTrue(c.globalIntBytes > 0L, "全局整数列按声明尺寸给正字节，实际 ${c.globalIntBytes}")
    }

    // ---- EP3/L10 刀 2：角色整数格的总量口径（改前的「少乘少报」）----

    /**
     * 断言的是 L7 基准的哪几行：`probe-20261102-161404.census.txt` 的
     * `RAW ... charaIntBytes=208768 charaStrBytes=698660 charaReserveBytes=5309700` 与
     * `RUN ... charaCount/Cap=168/193`，以及同文件 `HISTO` 行的 `long[] 59,091,016 B`。
     *
     * 改前账面入账的就是那个 **208,768**（单角色宽，`VariableStore.estimateBytesPerCharacter`
     * 的 KDoc 自述），本用例把它钉成「**每行宽 × 列数组实际行数**」：
     * - 等式 `charaIntBytes == charaIntPerCharBytes × charaCapacity` 就是「乘容量」这条判式；
     * - 反例 `charaIntBytes != perChar × count`（空尾 1 行时也必须多出一整行）钉死「不许乘 count」；
     * - `characterIntResidentBytes()` 读的是 `LongCol.data` 自己的 `size`（`CharacterTable.kt:75`），
     *   与账面逐字相等 ⇒ 公式没有跑在实际数组之外（结构证据，不是选边猜）。
     */
    @Test
    fun `角色整数列按列数组实际长度入账且与每角色成本成倍`() {
        val store = VariableStore()
        val perChar = store.estimateBytesPerCharacter()
        // 起步档（count=0、capacity=1）：账面已经是一整条列数组，不是「零只角色」也不是「一个角色宽当总量」
        val fresh = LiveSetCensus.census(null, store, null, null, null, fullScan = false)
        assertEquals(perChar, fresh.charaIntPerCharBytes, "每角色成本 = estimateBytesPerCharacter 原值")
        assertEquals(perChar, fresh.charaIntBytes, "容量 1 ⇒ 总量 = 一行宽（列数组就是 1 行长）")

        repeat(3) { store.characters.addBare() }
        val c = LiveSetCensus.census(null, store, null, null, null, fullScan = false)
        assertEquals(3L, c.charaCount, "3 只角色")
        assertEquals(4L, c.charaCapacity, "起步 1 → 2 → 4 的翻倍阶梯（CharacterTable.ensureCapacity）")
        assertEquals(c.charaIntPerCharBytes * c.charaCapacity, c.charaIntBytes, "总量 = 每行宽 × 容量行")
        assertEquals(store.characterIntResidentBytes(), c.charaIntBytes, "账面 = 逐列 data.size 求和（实际驻留）")
        // 少报那一笔的确切形状：乘 count 会少 (capacity − count) 行 = 空尾，本臂 1 行
        assertEquals(
            (c.charaCapacity - c.charaCount) * c.charaIntPerCharBytes,
            c.charaIntBytes - c.charaIntPerCharBytes * c.charaCount,
            "少报量 = 空尾行 × 每行宽（L7 判的 25 行 ≈5 MiB 就是这一项）",
        )
        assertTrue(
            c.charaIntBytes > c.charaIntPerCharBytes * c.charaCount,
            "空尾占着堆：按 count 入账就是把活对象读少了",
        )
        // 容量冗余（= 五族列的空尾）现在已在整数/CSTR 两列里 ⇒ 只作诊断、不进和
        assertTrue(c.charaReserveBytes > 0L, "cap-count=1 行 ⇒ 冗余读数为正：${c.charaReserveBytes}")
    }

    /**
     * [LiveSetCensus.bookedSumBytes] 的去重口径（交付里两个数并列的那条依据）：
     * 和 = 装载四项 + 运行侧**实占**全局整数/串格/角色整数/角色串；**不含** `charaReserveBytes`
     * （上两列的尾部子集）与 `charaIntPerCharBytes`（被乘数），缺读数（-1）跳过不折 0。
     */
    @Test
    fun `不重复计的和排除容量冗余与每角色成本`() {
        val store = VariableStore()
        repeat(3) { store.characters.addBare() }
        val c = LiveSetCensus.census(
            programWithInstructions(4), store, null, null, null, fullScan = false,
        )
        val expected = c.codeResidentBytes + c.lineTableResidentBytes + c.callArgIndexResidentBytes +
            c.globalIntResidentBytes + c.globalStrSlotBytes + c.charaIntBytes + c.charaStrBytes
        assertEquals(expected, c.bookedSumBytes(), "和里既没有冗余也没有每角色成本")
        assertEquals(3, c.bookedMissingFieldCount(), "fullScan=false 的串池 + 图形两段共 3 项缺读数")
        assertTrue(c.bookedSumBytes() < expected + c.charaReserveBytes, "冗余一旦入和就是重复计")
    }

    // ---- EP3/L10 刀 1：全局整数「声明 vs 实占」是两笔（按需分配）----

    /**
     * 判性在册：[LiveSetCensus.Census.globalIntBytes] 是**声明尺寸高估**，
     * [LiveSetCensus.Census.globalIntResidentBytes] 才是实际付掉的堆。
     *
     * 实测形状（出厂档 `VariableSizes`）：新建 `VariableStore()` 的 `init { resetValues() }`
     *（`VariableStore.kt:366-368`）只把**有初值**的全局列现分配 ⇒ 实占 448,000 B、声明 17,072,000 B，
     * 账面那对「声明 / 实占」必须并列才读得懂（基准臂 `RAW ... globalIntBytes=16765800` 是声明口径）。
     * 另钉**幂等**：重复写同一列不追加分配 —— 实占这把尺量的是数组长度，不是写入次数。
     */
    @Test
    fun `全局整数列只付写过的那几列且重复写不追加分配`() {
        val store = VariableStore()
        val declared = store.estimateGlobalIntBytes()
        val base = store.globalIntResidentBytes()
        assertTrue(declared > 0L, "声明尺寸恒正：$declared")
        assertTrue(base in 1L until declared, "按需分配 ⇒ 实占 $base 必须落在 (0, 声明 $declared) 内")
        val c = LiveSetCensus.census(null, store, null, null, null, fullScan = false)
        assertEquals(declared, c.globalIntBytes, "账面声明 = estimateGlobalIntBytes")
        assertEquals(base, c.globalIntResidentBytes, "账面实占 = globalIntResidentBytes")

        for (name in listOf("A", "B", "C", "M", "X", "Y", "Z")) store.writeInt(name, longArrayOf(0), 1L)
        val after = LiveSetCensus.census(null, store, null, null, null, fullScan = false)
        assertTrue(
            after.globalIntResidentBytes > base && after.globalIntResidentBytes <= declared,
            "写过 A-Z 后实占应增长且不越声明：$base → ${after.globalIntResidentBytes} / $declared",
        )
        assertEquals(declared, after.globalIntBytes, "声明尺寸不随写入变化")
        store.writeInt("A", longArrayOf(1), 2L)
        assertEquals(
            after.globalIntResidentBytes,
            LiveSetCensus.census(null, store, null, null, null, fullScan = false).globalIntResidentBytes,
            "同一列再写不追加分配 ⇒ 实占是数组长度、不是写次数",
        )
    }

    // ---- EP3/L10 刀 3：三枚现成读数进账面（不再是反射面）----

    /**
     * 上一波（L7）只能按名字前缀反射取这几枚（探针 `OFFBOOK` 行，`-1 = 取不到`）；本路把
     * `Program.materializedStatementCount`（`Program.kt:1179`）/`materializedFunctionCount`
     * （`Program.kt:1267`）与分片源的 `residentSegments`/`builtSegments`（`SegmentedFlatStmt.kt:73/:77`，
     * 经新增的 `Program.flatResidentSegmentCount`/`flatBuiltSegmentCount` 公开面）请进账面。
     *
     * 钉两件事：
     * 1. 账面读数 = Program 自己的读数（**同一条面**，探针不再需要反射）；
     * 2. 取读数**不建片**：census 之后 `flatStmtSource` 仍为 null（类头「只读、禁物化」第 2 条）。
     */
    @Test
    fun `物化读数进账面且取读数不建扁平源`() {
        val p = programWithInstructions(6)
        val c = LiveSetCensus.census(p, null, null, null, null, fullScan = false)
        assertEquals(p.materializedStatementCount.toLong(), c.materializedStatements, "语句树物化数同面")
        assertEquals(p.materializedFunctionCount.toLong(), c.materializedFunctions, "函数物化数同面")
        assertEquals(0L, c.flatResidentSegments, "未构建扁平源 ⇒ 驻留 0 片（真读数，不是缺读数）")
        assertEquals(0L, c.flatBuiltSegments, "未构建扁平源 ⇒ 已建 0 片")
        assertNull(p.flatStmtSource, "census 不许为取读数调 ensureFlatStmtSource（会把片缓存装进报告线程）")
        assertTrue("扁平片驻/建 0/0" in c.render(), "0/0 要成文为两个零，不许写 '-'：${c.render()}")
    }

    /** 建片之后：公开面跟着 LRU 表走（`blocks.size`），账面与源内读数一致。 */
    @Test
    fun `建片后公开读数跟着常驻片数走`() {
        // 用 CodeBuilder 现编一条**有语句**的程序：`_NOP`/`_HALT` 这类无语句指令不进语句池
        // （`programWithInstructions` 的语句数是 0，建不出一片，测不到本读数）。
        val p = programWithStatements(6)
        assertTrue(p.statementCount > 0, "fixture 至少要有一条语句，实际 ${p.statementCount}")
        val src = p.ensureFlatStmtSource()
        assertEquals(0, p.flatResidentSegmentCount, "刚构建的惰性源一片未建")
        src.opAt(0)
        assertEquals(1, p.flatResidentSegmentCount, "第一次取用建立所在片")
        assertEquals(1, p.flatBuiltSegmentCount, "累计已建 = 1")
        val c = LiveSetCensus.census(p, null, null, null, null, fullScan = false)
        assertEquals(1L, c.flatResidentSegments, "账面读的是同一条公开面")
        assertEquals(1L, c.flatBuiltSegments)
        assertTrue("扁平片驻/建 1/1" in c.render(), "建片后成文要给真读数：${c.render()}")
    }

    // ---- EP3/L10 刀 1 的另一半：成文字面不许漂（复盘脚本在钉旧行）----

    /**
     * `devtools/triage_reports.py` 用 `HEAPCENSUS_RE`（行首 `heapcensus used=<n>MiB/<max>`）与
     * `HEAP_CENSUS_REPORT_PREFIX`（「Java 堆结构账面」）钉旧行，本路加字段只许**追加**、
     * 不许把既有标签改名。这里把既有标签与新增标签一起钉住：改名 = 当场红。
     */
    @Test
    fun `账面行的既有与新增标签逐字在册`() {
        val store = VariableStore()
        val line = LiveSetCensus
            .census(programWithInstructions(2), store, null, null, null, fullScan = false)
            .render()
        assertTrue(
            line.startsWith("Java 堆结构账面（估算口径，非 dominator tree"),
            "行首前缀漂了（triage_reports 的 HEAP_CENSUS_REPORT_PREFIX 会读成缺行）：$line",
        )
        for (frag in listOf(
            // 既有：装载 / 运行 / 行缓冲 / 图形四段一个都不许改名
            "｜装载：函数 ", " 指令 ", " 全局槽 ", " 表达式 ", " 语句 ",
            "｜指令流常驻 ", "MiB 行表 ", " 实参索引 ", " 串池 ",
            "｜运行：全局整数(声明) ", " 全局串格 ", " 角色 ", "/容 ", " CSTR ", " 容量冗余", " 驻留串表 ",
            "｜行缓冲：显示 ", " 逻辑 ", "｜图形 Java：正号段像素 ", " 负号段 CSV ",
        )) {
            assertTrue(frag in line, "既有标签被改名/删除：`$frag` → $line")
        }
        for (frag in listOf(
            // 新增：三枚物化读数 + 实占口径 + 每角色成本 + 去重注记
            " 物化语句 ", " 物化函数 ", " 扁平片驻/建 ",
            " 全局整数(实占) ", " 整数(容量行) ", " 每角色整数 ", "(已含上两列)",
        )) {
            assertTrue(frag in line, "新增标签没进账面：`$frag` → $line")
        }
        assertTrue(line.none { it == '\n' }, "仍然单行")
    }

    @Test
    fun `render 是单行不含换行`() {
        val store = VariableStore()
        val line = LiveSetCensus.census(programWithInstructions(2), store, null, null, null, fullScan = false)
            .render()
        assertTrue(line.none { it == '\n' }, "账面必须一行成文（报告行表按单行塞）")
    }

    @Test
    fun `miB 对负哨兵原样返回不折零`() {
        val c = LiveSetCensus.census(null, null, null, null, null, fullScan = false)
        assertEquals(-1L, c.miB(-1L), "无读数不能被折成 0")
        assertEquals(2L, c.miB(2L * 1024 * 1024), "2 MiB 整除")
    }
}
