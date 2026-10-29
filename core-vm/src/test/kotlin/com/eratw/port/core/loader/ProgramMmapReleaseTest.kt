package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcEncodeOptions
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CMP-19 的可测判据（不依赖真机）：惰性档产物的**确定性释放点**。
 *
 * ## 缺陷
 *
 * 生产惰性档 `keepChannel=true` ⇒ `MappedErbc.close()` 从不调用（`ProgramLoader.kt:413/419`），
 * CODE 段 ~76.8 MiB + 段 3 整块 + 串表 ~104.9 MiB 的映射与那个 `FileChannel` 随 `Program`
 * 一起活到进程结束；`GameSession.close()` 又不碰 `Program` ⇒ 每次 `recreate()` 泄漏 1 fd +
 * 一批映射窗口（且被 CMP-17 的静态钉住后 GC 也收不回）。
 *
 * ## 修法与它的前提（RECON §4 的隐情）
 *
 * 直接关通道会**破惰性解码**：语句池的运行期回读走 `SectionWindow`，窗口不在当前映射块里
 * 就再 `channel.map` 一次 —— 通道已关 = `映射已关闭` 异常。所以释放的前提是「运行期所有
 * 回读路径都落在已经 pin 住的映射上」：
 * - 段 3 的记录/节点窗口在装载期就整段 pin（`ErbcStream.kt:1135`）；
 * - 语句段的**局部串表数据块**原来按 `min(dataLen, 128 MiB)` 换块 ⇒ 本路在装载期把它
 *   整块 pin 住（`readStringTable(pinWhole = true)`），才允许关通道。
 *
 * 前两条用例直读这个机制（[SectionWindow] 的两条构造形态），第三条钉 [Program.releaseMmap]，
 * 第四条钉「串表超过 pin 上限时不许关」这条前提本身。
 *
 * 真机项（`/proc/pid/fd` 计数、smaps 的映射体积、recreate 前后 fd 差）见交付报告 TODO(verify)。
 */
class ProgramMmapReleaseTest {

    // ==================================================================
    // 样本：语句池要有原文与字符串实参（否则串表为空，测不出 pin）
    // ==================================================================

    private fun sampleProgram(statements: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val shared = Binary(
            OperatorCode.PLUS,
            IntLiteral(7L, 3, 1),
            VarRef("A", emptyList(), isNoArg = false, operandType = OperandType.INT),
            OperandType.INT,
            3,
            1,
        )
        val poolRoot = b.expr(shared)
        val ids = ArrayList<Int>(statements)
        for (i in 0 until statements) {
            val line = i % 97 + 1
            val s = when (i % 3) {
                0 -> PrintStmt(
                    PrintSpec(
                        code = FunctionCode.PRINTL,
                        name = "PRINTL",
                        argType = ArgType.INT_EXPRESSION,
                        isLineEnd = true,
                        flag = 0,
                    ),
                    ExpressionArgument(StrLiteral("第 $i 行", line, 0)),
                    line,
                    "PRINTL 第 $i 行",
                )

                1 -> AssignStmt(
                    dest = VarRef("B$", emptyList(), isNoArg = false, operandType = OperandType.STR),
                    op = OperatorCode.ASSIGNMENT_STR,
                    args = VoidArgument(),
                    line = line,
                    raw = "B$ = \"\"  #$i",
                )

                else -> InstructionStmt(FunctionCode.WAIT, VoidArgument(), line, "WAIT  #$i")
            }
            ids.add(b.statement(s))
        }
        b.emit(Opcode._EVAL_INT, poolRoot, 0, SlotAddr.NONE, 3)
        for (k in ids.indices) b.emit(Opcode.WAIT, ids[k], 0, SlotAddr.NONE, k % 97 + 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    private fun writeArtifact(
        dir: Path,
        statements: Int,
    ): Path {
        val f = dir.resolve("program.erbc")
        Files.write(f, ErbcWriter.encode(sampleProgram(statements), ErbcEncodeOptions(version = ErbcFormat.VERSION_2)))
        return f
    }

    // ==================================================================
    // 1. 机制直读（RECON §4）：未 pin 的窗口在关通道后不许回读
    // ==================================================================

    @Test
    fun `未 pin 的段窗口在关通道后不许回读（直接 close 会破惰性解码）`(@TempDir dir: Path) {
        val f = writeArtifact(dir, 16)
        val mapped = MappedErbc.open(f)
        val end = mapped.byteSize
        assertTrue(end > 64, "样本产物太小（$end B），取不到两个不同的窗口位置")
        // 按块换映射的形态（缺省 chunk = 8 MiB）：第一次读只覆盖「读到的位置之后」
        val window = SectionWindow(mapped, 0, end)
        window.u32At(end / 2)
        mapped.close()
        // 回读低位 = 需要再 map 一次 ⇒ 通道已关，必须当场拒绝（不许读到悬空/旧块）
        val e = assertFailsWith<ErbcLoadException>("未 pin 的窗口在关通道后回读必须抛，而不是读到脏数据") {
            window.u32At(0)
        }
        assertTrue(e.message!!.contains("映射已关闭"), "拒绝理由要指名「映射已关闭」，实际：${e.message}")
    }

    @Test
    fun `整段 pin 的窗口在关通道后仍可读（释放的前提）`(@TempDir dir: Path) {
        val f = writeArtifact(dir, 16)
        val mapped = MappedErbc.open(f)
        val end = mapped.byteSize
        val pinned = mapped.mapWindow(0, end)
        val window = SectionWindow(mapped, 0, end, pinned, 0)
        mapped.close()
        assertTrue(mapped.isClosed)
        // 整段都在已建映射里 ⇒ 一个 map 都不再发生（正读/回读/跳读都命中）
        window.u32At(end / 2)
        window.u32At(0)
        window.u32At(end - 4)
        window.readInto(end / 2, 16, ByteArray(16))
    }

    // ==================================================================
    // 2. 集成：Program.releaseMmap 是确定性释放点
    // ==================================================================

    @Test
    fun `惰性装载后 releaseMmap 关掉产物通道，且惰性解码仍可用`(@TempDir dir: Path) {
        val f = writeArtifact(dir, 64)
        val mapped = MappedErbc.open(f)
        val program = ProgramLoader.loadMappedHandle(mapped, null)
        assertTrue(program.holdsLazyStatementPool, "样本必须走惰性档（否则通道在装载返回前就关了，测不到泄漏面）")
        assertFalse(mapped.isClosed, "装载返回时通道由惰性池接管（ProgramLoader.kt:413/419）")
        assertEquals(0, program.materializedStatementCount, "装载期不许物化语句")

        // GameSession.close() 里的同一句调用
        assertTrue(program.releaseMmap(), "串表数据块 ≤ 128 MiB ⇒ 整块 pin 住后必须能关通道")
        assertTrue(mapped.isClosed, "releaseMmap 之后必须真的关掉 FileChannel（CMP-19 的判据）")

        // 关通道之后惰性解码仍必须可用：先取高位（串表靠后），再回取低位（要往回跳）
        val high = program.statement(63)
        assertEquals(63 % 97 + 1, high.line)
        assertEquals("PRINTL 第 63 行", high.raw, "语句原文就在语句段局部串表里，关通道后必须仍能解码")
        val low = program.statement(0)
        assertEquals(1, low.line)
        assertEquals("PRINTL 第 0 行", low.raw, "回取低位 = 串表窗口要往回跳；pin 住才允许关通道")
        // 同一实例（契约 C2）
        assertTrue(program.statement(63) === high, "关通道不改变「同一下标恒返回同一实例」")

        // 幂等：宿主 close 可能被调两次
        assertTrue(program.releaseMmap(), "重复 releaseMmap 必须无害且仍报「已释放」")
        assertTrue(mapped.isClosed)
    }

    // ==================================================================
    // 2b. MEM7 刀 C/刀 D：只读索引骨架必须**真的**离开 Java 堆
    // ==================================================================

    /**
     * 这三刀的收益全部是「装载后少一张条数正比的数组」，而**取值面一条都没变** ——
     * 于是「接不上、静默退回堆上数组」这种空转不会让任何既有用例变红（读出来还是一模一样）。
     * 本用例就把「接上了」这件事本身钉住：
     *
     * - 段 3 / 段 2 / 段 4 的局部串池偏移表：堆上那张 `offsets` 必须为 null，
     *   且替它的 `offsetBlocks`（稀疏分块 u32 表）必须在位（刀 C 空转＝白省 6.4 MiB）；
     * - 段 2 池根表：`MappedExprSource` 不再有 `roots: IntArray`，替它的是分块表（刀 D）；
     * - 换形态之后，**下标越界仍在取数之前判**，文案与旧数组形态逐字相同（不许把越界读吞成 0）。
     */
    @Test
    fun `惰性装载后局部串池偏移表与池根表都不留在堆上`(@TempDir dir: Path) {
        val f = writeArtifact(dir, 64)
        val program = ProgramLoader.loadMappedHandle(MappedErbc.open(f), null)
        val pool = fieldOf(program, "pool")!!
        val lazyStmt = fieldOf(pool, "lazyStatements")!!
        val lazyExpr = fieldOf(pool, "lazyExprs")!!
        val stmtStrings = fieldOf(fieldOf(lazyStmt, "arena")!!, "strings")!!
        val exprStrings = fieldOf(fieldOf(lazyExpr, "arena")!!, "strings")!!
        val fnStrings = fieldOf(program.functions, "strings")!!

        for ((label, table) in listOf("段 3" to stmtStrings, "段 2" to exprStrings, "段 4" to fnStrings)) {
            assertNull(heapOffsets(table), "$label 局部串池的偏移表没换成稀疏分块（刀 C 空转）")
            assertNotNull(fieldOf(table, "offsetBlocks"), "$label 局部串池没建出分块表（刀 C 空转）")
        }
        assertTrue(strCount(stmtStrings) > 0, "样本必须给段 3 留下一张非空局部串池")
        assertTrue(strCount(exprStrings) > 0, "样本必须给段 2 留下一张非空局部串池")
        // 刀 D：池根表只留在产物偏移上
        assertNull(fieldOf(lazyExpr, "roots") as? IntArray, "段 2 池根表还是整张 IntArray（刀 D 空转）")
        assertNotNull(fieldOf(lazyExpr, "roots"), "段 2 池根表没换成 BlockU32s（刀 D 空转）")
        assertTrue((fieldOf(lazyExpr, "poolCount") as Int) > 0, "样本必须有表达式池根")

        // 映射页形态的下标判界必须发生在取数之前，且文案与数组形态同一条
        val table = stmtStrings as StreamStringTable
        val n = table.count
        val e = assertFailsWith<ErbcDecodeException>("越界下标要在取数之前被拒") { table[n] }
        assertTrue(
            e.message!!.contains("字符串表下标越界: $n（共 $n 项）"),
            "越界文案要与数组形态逐字相同，实际：${e.message}",
        )
        val neg = assertFailsWith<ErbcDecodeException>("负下标同样要在取数之前被拒") { table[-1] }
        assertTrue(
            neg.message!!.contains("字符串表下标越界: -1（共 $n 项）"),
            "负下标文案要与数组形态逐字相同，实际：${neg.message}",
        )
        // 取数面本身仍与旧形态一致（原文就在偏移表指向的数据块里）
        assertEquals("PRINTL 第 0 行", program.statement(0).raw)
        assertEquals("PRINTL 第 63 行", program.statement(63).raw)
    }

    // ==================================================================
    // 2c. MEM7b：块是**拷进堆**的 IntArray ⇒ 与映射生命周期解耦（换稀疏块后白捡的安全性）
    // ==================================================================

    /**
     * 刀 C/刀 D 换成稀疏块表以后，多出一条旧数组形态**没有**的性质：一块一旦被灌满，
     * 它的内容就是堆上的一份 `IntArray`，此后取值既不回映射页也不回通道 ——
     * 于是「映射已经不可用」这件事对**已灌出的块**根本不构成失败条件。
     *
     * 本用例故意用**换块窗口**（非整段 pin）来量这件事：那种窗口在 `MappedErbc.close` 之后
     * 连一个字节都取不到（本类第一条用例「未 pin 的段窗口在关通道后不许回读」钉的就是它），
     * 所以「已灌块照旧可读 + 未灌块当场被拒」两半合起来才叫解耦，而不是「整段 pin 本来就可读」。
     *
     * 顺带钉两条形态判据：末块不足一整块时**读得到最后一项、越界项仍是
     * [IndexOutOfBoundsException]**（块表不许把越界读吞成 0，判界仍归调用方）。
     */
    @Test
    fun `稀疏块与映射生命周期解耦（灌出来的块在关通道后照旧可读）`(@TempDir dir: Path) {
        val f = writeArtifact(dir, 4000)
        val raw = Files.readAllBytes(f)
        val mapped = MappedErbc.open(f)
        val end = mapped.byteSize
        val size = end / 4
        val table = BlockU32s(SectionWindow(mapped, 0, end), 0, size)
        val width = table.blockLength(0)
        assertTrue(table.blockCount >= 2, "样本要跨至少两个块才量得出解耦：块位数 ${table.blockCount}（产物 $end B）")

        // 只碰第一块：内容要与文件字节的小端解释逐字相同
        val expectFirst = (0 until width).map { leU32(raw, it * 4) }
        assertEquals(expectFirst, (0 until width).map { table[it] }, "灌出来的块要和产物字节逐字相同")
        assertEquals(1, table.filledBlocks(), "只碰第一块 ⇒ 只灌一块（未取用的块一个字节不占）")

        // 末块不足一整块：最后一项读得到，再往上一格报越界（块表不做友好化，判界归调用方）
        val tail = BlockU32s(SectionWindow(mapped, 0, end), 0, width + 1)
        assertEquals(leU32(raw, width * 4), tail[width], "末块只有一项时也要读到最后一项")
        assertEquals(1, tail.filledBlocks(), "只碰末块 ⇒ 只灌末块那一块（头一块一个字节不占）")
        assertEquals(leU32(raw, 0), tail[0], "同一张表的第一项也要读到")
        assertEquals(2, tail.filledBlocks(), "首块 + 末块各灌一次，多一项都不该多灌")
        val oob = assertFailsWith<IndexOutOfBoundsException>("块内越界不许被吞成 0（判界归调用方）") {
            tail[width + 1]
        }
        assertTrue(
            oob.message!!.contains("Index"),
            "越界要报出数组越界本身（块表与原 IntArray 同形态），实际：${oob.message}",
        )

        mapped.close()
        // 已灌块：纯数组直取，一次映射都不再发生 —— 这就是「拷出来」的那份安全性
        assertEquals(expectFirst, (0 until width).map { table[it] }, "关通道后已灌的块必须照旧逐字可读")
        // 对照：未灌的块在换块窗口上取不到（否则本用例什么也没证明）
        val err = assertFailsWith<ErbcLoadException>("未灌的块在关通道后必须被拒，不许读到脏字节") {
            table[width]
        }
        assertTrue(err.message!!.contains("映射已关闭"), "拒绝理由要指名「映射已关闭」，实际：${err.message}")
    }

    /**
     * 集成面：[Program.releaseMmap] 之后，**已灌过的块**与**第一次才被取用的块**都要照常工作
     * （惰性档的串池偏移表/池根表都读同一张整段 pin，块从 pin 拷出来 ⇒ 通道关掉不影响取值），
     * 且越界仍发生在取数之前、文案与数组形态逐字相同。
     */
    @Test
    fun `块接管后 releaseMmap 照常关通道，取值与越界判界都不变`(@TempDir dir: Path) {
        val f = writeArtifact(dir, 64)
        val mapped = MappedErbc.open(f)
        val program = ProgramLoader.loadMappedHandle(mapped, null)
        val pool = fieldOf(program, "pool")!!
        val stmtStrings = fieldOf(fieldOf(fieldOf(pool, "lazyStatements")!!, "arena")!!, "strings")!!
            as StreamStringTable

        // 取用一轮：段 3 偏移表被灌出块、段 4 函数名被解出来
        val raws = (0 until 64).map { program.statement(it).raw }
        val names = program.functions.indices.map { program.functions[it].name }
        val filled = stmtStrings.offsetBlockTable()?.filledBlocks() ?: 0
        assertTrue(filled > 0, "取用一轮后，段 3 偏移表就该灌出块，实得 $filled 块（刀 C 空转？）")

        assertTrue(program.releaseMmap(), "块接管不改变「串表整块 pin ⇒ 允许关通道」这条前提")
        assertTrue(mapped.isClosed, "releaseMmap 之后必须真的关掉 FileChannel")

        // 已灌块：同一条目再取一遍，值与关通道前逐字相同（不重新回映射页也拿得到偏移）
        assertEquals(raws, (0 until 64).map { program.statement(it).raw })
        assertEquals(names, program.functions.indices.map { program.functions[it].name })
        // 第一次才被取用的面（段 2 池根 + 节点重放）：块在关通道之后才灌，照样从 pin 拷得出来
        val exprCount = program.exprCount
        assertTrue(exprCount > 0, "样本必须留表达式池根")
        val roots = (0 until exprCount).map { program.expr(it) }
        assertEquals(exprCount, roots.size, "关通道后表达式根要仍能按需解码：不许抛、也不许补不满")
        assertTrue(program.statement(63) === program.statement(63), "同下标恒同实例（契约 C2）不因块形态改变")

        // 判界仍在取数之前，文案与数组形态逐字相同
        val n = stmtStrings.count
        val e = assertFailsWith<ErbcDecodeException>("关通道后越界下标仍在取数之前被拒") { stmtStrings[n] }
        assertTrue(
            e.message!!.contains("字符串表下标越界: $n（共 $n 项）"),
            "越界文案要与数组形态逐字相同，实际：${e.message}",
        )
    }

    // ==================================================================
    // 3. 前提本身：串表超过 pin 上限时不许关通道
    // ==================================================================

    @Test
    fun `串表数据块超过 128 MiB 时不满足 pin 前提（此时保持通道）`() {
        assertTrue(canPinStringTable(1), "极小表可以 pin")
        assertTrue(
            canPinStringTable(104_923_457),
            "全量语料语句段局部串表 104,923,457 B（≈100.1 MiB）必须落在可 pin 区间内 —— 生产路径靠这条才敢关通道",
        )
        assertFalse(
            canPinStringTable(MAX_TABLE_WINDOW_BYTES + 1),
            "超过 128 MiB 上限的表不 pin：关通道会让运行期换块撞「映射已关闭」（CMP-19 的前提）",
        )
        assertFalse(canPinStringTable(0), "空表没有数据块，走「无回读」分支，不算 pin")
    }

    // ==================================================================
    // 4. 真实产物（ERATW_ERBC 动态门）：全量串表也能 pin + 关通道
    // ==================================================================

    /**
     * 全量产物上跑一次同一判据：`program.erbc` 的语句段局部串表约 104.9 MiB，
     * 正好压在 [MAX_TABLE_WINDOW_BYTES] 以内 ⇒ [Program.releaseMmap] 必须返回 true，
     * 且关通道之后高位/低位语句都还能按需解码。
     *
     * 这条用例是 CMP-19「>128 MiB 不关、≤128 MiB 关」分界线的**真实读数**：合成样本证明不了
     * 100 MiB 级的 pin，只有真实产物能。没有装置/产物时按 `assumeTrue` 跳过（口径同本仓
     * `LazyStatementDecodeTest` 的真实产物用例）。
     */
    @Test
    fun `真实产物：releaseMmap 关通道后惰性解码仍可用（ERATW_ERBC 动态门）`() {
        val path = System.getenv("ERATW_ERBC")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?.takeIf { Files.isRegularFile(it) }
        org.junit.jupiter.api.Assumptions.assumeTrue(
            path != null,
            "ERATW_ERBC 未指向可读的 program.erbc：跳过真实产物用例",
        )
        val mapped = MappedErbc.open(path!!)
        val program = ProgramLoader.loadMappedHandle(mapped, null)
        assertEquals(0, program.materializedStatementCount, "装载完不许物化语句")
        assertTrue(program.holdsLazyStatementPool, "真实产物必须走惰性档")
        assertTrue(
            program.releaseMmap(),
            "全量语料串表 104,923,457 B ≤ 128 MiB ⇒ pin 必须成功、通道必须能关",
        )
        assertTrue(mapped.isClosed, "真实产物的 FileChannel 必须真的关掉")
        // 高位/低位各取一条：串表靠后与靠前的项都要能解，且同下标恒同实例
        val total = program.statementCount
        val high = program.statement(total - 1)
        assertTrue(program.statement(total - 1) === high, "关通道不改变实例身份（契约 C2）")
        val low = program.statement(0)
        assertTrue(low.line >= -1)
        println("[cmp-19] 真实产物：statements=$total，高位=${high.javaClass.simpleName}@L${high.line}，低位@L${low.line}，通道已关")
    }

    /**
     * MEM9 刀 2（段6 行号表分块）的正面钉：**分块真的接上了、关通道后首次灌块仍正确**。
     *
     * 为什么必须有这条：分块形态与整表形态对任何既有用例都给出同一批行号，静默退回整表
     * 只是让 9.3 MiB 常驻回来、一条用例都不会红。这里钉三件事：装载完一个块都没灌；
     * 相邻两页各取一条恰好灌两页；`releaseMmap()` 关掉产物通道之后，从未灌过的页仍能从
     * 已建映射里批量回读（映射在 JVM 语义里不随通道关闭而失效，这正是敢关通道的前提）。
     * 末了把整表全物化一次，与先前的抽样逐点对账 —— 块下标/换算错位会在这里露馅。
     */
    @Test
    fun `真实产物：段6 行号表走分块，关通道后首次灌块仍正确（ERATW_ERBC 动态门）`() {
        val path = System.getenv("ERATW_ERBC")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?.takeIf { Files.isRegularFile(it) }
        org.junit.jupiter.api.Assumptions.assumeTrue(
            path != null,
            "ERATW_ERBC 未指向可读的 program.erbc：跳过真实产物用例",
        )
        val mapped = MappedErbc.open(path!!)
        val program = ProgramLoader.loadMappedHandle(mapped, null)
        assertTrue(program.lineTableIsPaged, "段6 整段 pin 覆盖得到 ⇒ 必须走分块形态（退回整表就让 9.3 MiB 常驻回来）")
        val total = program.instructionCount
        assertEquals(0, program.lineTableBlockStats().second, "装载完一个块都不许灌")
        val a = 0
        val b = total - 1
        val c = total / 2
        val va = program.lineOf(a)
        val vb = program.lineOf(b)
        assertEquals(2, program.lineTableBlockStats().second, "取两个远端点应恰好灌两页")
        assertTrue(program.releaseMmap(), "真实产物串表 ≤128 MiB ⇒ 通道必须能关")
        assertTrue(mapped.isClosed, "通道必须真的关掉")
        // 关通道之后才第一次取的那一页：必须仍能从已建映射批量回读
        val vc = program.lineOf(c)
        val filledAfter = program.lineTableBlockStats().second
        // 全量物化（逐块灌满）后与先前抽样逐点对账：块下标/换算错位会在这里不一致
        val all = IntArray(total) { program.lineOf(it) }
        assertEquals(all[a], va, "块 0 的取值与全量物化后不一致")
        assertEquals(all[b], vb, "末页的取值与全量物化后不一致")
        assertEquals(all[c], vc, "关通道后才灌的那一页与全量物化后不一致")
        assertEquals(total, all.size)
        println("[mem9] 段6 分块：指令=$total，装载后灌 0 页，关通道后已灌 $filledAfter 页，抽样 L$va/L$vb/L$vc 全对账")
    }

    // ==================================================================
    // 装置反射（只用来斩「刀真的落地了」，不参与任何取值判据）
    // ==================================================================

    /** 读私有字段（含父类链）；取不到返回 null。口径同 `HeapCompositionProbeTest`。 */
    private fun fieldOf(target: Any?, name: String): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                return runCatching {
                    f.isAccessible = true
                    f.get(target)
                }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    /** 串池在堆上的那张偏移表；null = 已改从映射页直取（MEM7 刀 C 生效）。 */
    private fun heapOffsets(table: Any?): IntArray? = fieldOf(table, "offsets") as IntArray?

    private fun strCount(table: Any?): Int = (table as StreamStringTable).count

    /** 文件字节的小端 u32 解释（与 `StreamCursor.u32` / 灌块同一条换算），用作独立对照。 */
    private fun leU32(
        raw: ByteArray,
        at: Int,
    ): Int = (raw[at].toInt() and 0xFF) or
        ((raw[at + 1].toInt() and 0xFF) shl 8) or
        ((raw[at + 2].toInt() and 0xFF) shl 16) or
        ((raw[at + 3].toInt() and 0xFF) shl 24)
}
