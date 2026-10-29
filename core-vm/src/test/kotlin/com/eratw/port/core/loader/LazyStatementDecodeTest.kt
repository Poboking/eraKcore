package com.eratw.port.core.loader

import com.eratw.port.core.code.CallArg
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcEncodeOptions
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.FlatStatements
import com.eratw.port.core.code.FlatStmtKey
import com.eratw.port.core.code.SegmentedFlatStmtSource
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.perf.HeapMeter
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.io.TempDir
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 段 3 语句池的**按需惰性解码**（CONTRACT-M10 §1 m10-mem 判据①③）。
 *
 * ## 这一路要打的账
 *
 * 改造前 `ErbcStreamDecoder.decodeProgram` 把段 3 的 1,716,637 条语句记录一次解进堆
 * （perf-report §2b：语句外壳 62.6 MiB + args 子树 + 原文 131.0 MiB），装载完即常驻，
 * 真机 512 MiB 挡位在解码中途 OOM（M8-DEVICE-BOOT 行：`Failed to allocate 19,202,328 B
 * with 14,937,248 free, target footprint 536,870,912`）。现在装载只建索引
 * （记录起始偏移表 + 条目槽），条目等第一次被取用才解。
 *
 * ## 钉死的性质
 *
 * 1. **等价**：惰性档与整池对照档（[ProgramLoader.loadMappedEager]）在同一份字节上解出的
 *    [Program] 逐字段一致，包括按下标升序整池取用时的**语句实例内容**；
 * 2. **`.erbc` 再编码逐字节相同**（红线）：惰性装载的 Program 再编码回原容器字节 ——
 *    写侧只按下标读池（`ErbcWriter.kt:286` 的 `program.statement(i)`），
 *    升序读 = 升序物化 = 与改造前同一份对象图、同一张规范化去重表；
 * 3. **装载不驻留语句树**（T2-64 波次 C 起为「压扁即弃」）：`loadMapped` 返回后
 *    [Program.materializedStatementCount] = 0（压扁趟逐条「解码 → 写入扁平流 → 弃树」，
 *    不进池缓存），而 [Program.statementCount] 已经是段声明的条数（判界要用它）；
 *    扁平池（`Program.flatStmtSource`）在装载返回前就建好，proc 桥不再物化整池；
 * 4. **身份稳定**：同一下标恒返回同一实例（契约 C2 的数据节键）；
 *    越界仍抛 [IndexOutOfBoundsException]；
 * 5. **畸形产物口径**（M-MEM1 2026-10-28 更新，段 3 节点表改无分配边界扫描后）：
 *    **记录区**坏字节仍在**装载期**、仍在**同一个偏移**抛出与整池档同一句话（本夹具不带侧车
 *    ⇒ 索引趟逐条走完整套文法；生产带侧车时由记录区 CRC32 配对 + 12 条抽样回放兜底）；
 *    **节点表**坏字节分两级——标签/串表下标/子下标越界/后序性/记录长度仍由边界扫描在
 *    **同一字节位置**当场抛（skipNode 与 readNode 字节读序逐字节相同）；运算符/变量名的
 *    枚举译码与 String 内容解码**延到物化**（口径同段 2 的 M12-EXPRLAZY）：生产侧车档里
 *    「只坏在节点内容」的产物在首次取到该节点时才报，报错的还是同一句话 ——
 *    差别只在**什么时候发现**，不在**发现什么**。
 * 6. **分段 mark**（判据③）：解码内部的段边界与索引趟 1/8 条带都会回调 [LoadStageMark]，
 *    顺序单调、名字带段号 ⇒ 失败屏的峰值行能说清卡在哪一段。
 *
 * 真实产物用例在 `ERATW_ERBC` 指向 `program.erbc` 时跑，给出「惰性 vs 整池」的
 * 装载分配/常驻/峰值三项对照读数。
 */
class LazyStatementDecodeTest {

    // ==================================================================
    // 样本：语句池要有 args 子树、共享节点、原文，才测得出惰性化省了什么
    // ==================================================================

    /** 覆盖面样本：PRINT / ASSIGN（子树共享）/ 具名形参 / 调用点实参 / 多函数。 */
    private fun sampleProgram(
        statements: Int = 240,
        withCallArgs: Boolean = true,
    ): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 4)
        // v1 容器不承载段 7/8 元数据（ErbcWriter 会拒），v1 用例走不带的那一档
        val params = if (withCallArgs) listOf("LOCAL", "REF_TGT") else emptyList()
        b.declareFunction("callee", 1, 2, 6, paramNames = params)
        val shared = Binary(
            OperatorCode.PLUS,
            IntLiteral(7L, 3, 1),
            VarRef("A", emptyList(), isNoArg = false, operandType = OperandType.INT),
            OperandType.INT,
            3,
            1,
        )
        val poolRoot = b.expr(shared)
        var i = 0
        val stmts = ArrayList<Int>(statements)
        while (i < statements) {
            val line = i % 97 + 1
            val s = when (i % 4) {
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

                2 -> InstructionStmt(FunctionCode.WAIT, VoidArgument(), line, "WAIT  #$i")

                else -> InstructionStmt(FunctionCode.PRINT, ExpressionArgument(shared), line, "PRINT 7+A  #$i")
            }
            stmts.add(b.statement(s))
            i++
        }
        b.emit(Opcode._EVAL_INT, poolRoot, 0, SlotAddr.NONE, 3)
        b.emit(Opcode._CALL, 1, 0, SlotAddr.NONE, 4)
        if (withCallArgs) {
            b.declareCallArgs(
                1,
                listOf(CallArg(position = 0, bucket = 0, isStr = false, name = "A", present = true)),
            )
        }
        for (k in stmts.indices) {
            b.emit(Opcode.WAIT, stmts[k], 0, SlotAddr.NONE, k % 97 + 1)
        }
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    private fun writeArtifact(
        dir: Path,
        name: String,
        program: Program,
        options: ErbcEncodeOptions = ErbcEncodeOptions(version = ErbcFormat.VERSION_2),
    ): Path {
        val f = dir.resolve(name)
        Files.write(f, ErbcWriter.encode(program, options))
        return f
    }

    // ==================================================================
    // 1/3/4. 惰性 == 整池、装载不物化、身份稳定
    // ==================================================================

    @Test
    fun `惰性装载与整池装载逐字段一致，且装载期不物化任何语句`(@TempDir dir: Path) {
        val f = writeArtifact(dir, "lazy-vs-eager.erbc", sampleProgram())
        val viaBytes = ProgramLoader.loadFile(f)
        val eager = ProgramLoader.loadMappedEager(f)
        val lazyLoad = ProgramLoader.loadMapped(f)

        assertEquals(viaBytes.statementCount, lazyLoad.statementCount, "语句池条数（段声明口径）")
        assertEquals(eager.statementCount, lazyLoad.statementCount, "两条装载档的语句池条数")
        assertEquals(0, lazyLoad.materializedStatementCount, "loadMapped 返回后已物化条数必须是 0")
        assertTrue(eager.materializedStatementCount > 0, "对照档整池解码：已物化条数应等于池长")
        assertEquals(eager.statementCount, eager.materializedStatementCount, "对照档的物化条数")

        // 逐条比对（升序取用 = 与改造前同一次序的物化 + 同一张规范化去重表）
        for (i in 0 until lazyLoad.statementCount) {
            val a: Statement = lazyLoad.statement(i)
            val e: Statement = eager.statement(i)
            val v: Statement = viaBytes.statement(i)
            assertSame(a, lazyLoad.statement(i), "语句池 $i：同一下标必须恒返回同一实例")
            assertEquals(v.javaClass, a.javaClass, "语句池 $i 类")
            assertEquals(v.javaClass, e.javaClass, "对照档 语句池 $i 类")
            assertEquals(v.code, a.code, "语句池 $i 的 FunctionCode")
            assertEquals(v.line, a.line, "语句池 $i 行号")
            assertEquals(v.raw, a.raw, "语句池 $i 原文")
            assertEquals(v.args.javaClass, a.args.javaClass, "语句池 $i 的参数根类")
            assertEquals(v.args.isConst, a.args.isConst, "语句池 $i 参数 isConst")
            assertEquals(v.args.constStr, a.args.constStr, "语句池 $i 参数 constStr")
            assertEquals(v.args.constInt, a.args.constInt, "语句池 $i 参数 constInt")
        }
        // 参数子树的**逐字段**证据：整池再编码（写侧会走遍每条语句的 args 树）必须回到原字节
        val original = Files.readAllBytes(f)
        assertArrayEquals(original, ErbcWriter.encode(lazyLoad), "惰性档：整池再编码必须逐字节回到原容器")
        assertArrayEquals(original, ErbcWriter.encode(eager), "对照档：整池再编码必须逐字节回到原容器")
        assertArrayEquals(ErbcWriter.encode(viaBytes), ErbcWriter.encode(lazyLoad), "字节档与惰性档的再编码必须相同")
        assertSame(viaBytes.statement(0).args.javaClass, lazyLoad.statement(0).args.javaClass, "参数树根类")
        assertEquals(viaBytes.statementCount, lazyLoad.materializedStatementCount, "逐条取完后必须全池物化")
        assertEquals(viaBytes.stringCount, lazyLoad.stringCount, "字符串池条数不受语句惰性影响")
        assertEquals(viaBytes.exprCount, lazyLoad.exprCount, "表达式池条数")
        assertArrayEquals(viaBytes.code, lazyLoad.code, "指令流逐字")
        assertArrayEquals(viaBytes.lines, lazyLoad.lines, "行号表逐字")
    }

    @Test
    fun `惰性装载的再编码逐字节回到原容器（红线）`(@TempDir dir: Path) {
        val cases = listOf(
            Triple("v2", ErbcEncodeOptions(version = ErbcFormat.VERSION_2), true),
            // v1 容器不承载段 7/8 元数据：样本得退到「不带调用点实参」那一档
            Triple("v1", ErbcEncodeOptions(version = ErbcFormat.VERSION_1), false),
        )
        for ((name, options, withCallArgs) in cases) {
            val src = dir.resolve("reencode-$name.erbc")
            val original = ErbcWriter.encode(sampleProgram(withCallArgs = withCallArgs), options)
            Files.write(src, original)
            val lazyLoad = ProgramLoader.loadMapped(src)
            assertArrayEquals(
                original,
                ErbcWriter.encode(lazyLoad, options),
                "$name 容器：惰性装载 → 再编码必须逐字节相同",
            )
            // 反证口径：再编码走的是同一批实例（升序物化），不是「第二次装载」的字节
            val again = ProgramLoader.loadMapped(src)
            assertArrayEquals(
                ErbcWriter.encode(lazyLoad, options),
                ErbcWriter.encode(again, options),
                "$name 容器：两次惰性装载的再编码必须相同",
            )
            assertArrayEquals(
                original,
                ErbcWriter.encode(ProgramLoader.loadMappedEager(src), options),
                "$name 容器：对照档再编码也必须逐字节相同",
            )
        }
    }

    @Test
    fun `语句下标越界仍抛 IndexOutOfBounds（与 ArrayList 池同口径）`(@TempDir dir: Path) {
        val f = writeArtifact(dir, "oob.erbc", sampleProgram())
        val p = ProgramLoader.loadMapped(f)
        assertFailsWith<IndexOutOfBoundsException> { p.statement(-1) }
        assertFailsWith<IndexOutOfBoundsException> { p.statement(p.statementCount) }
        assertEquals(0, p.materializedStatementCount, "越界取值不得物化任何条目")
    }

    // ==================================================================
    // 3b. T2-64 波次 C：装载期「压扁即弃」
    // ==================================================================

    @Test
    fun `分片惰性压扁：装载期零建片、首次取用才建、跨片与单条 encode 逐槽相同`(@TempDir dir: Path) {
        val f = writeArtifact(dir, "flat-during-load.erbc", sampleProgram())
        val p = ProgramLoader.loadMapped(f)

        // 1) T2-64 波次 D：装载期**不建**扁平池（分片惰性源，首次执行才建片）
        assertNull(p.flatStmtSource, "装载期不建扁平池（分片惰性源）")
        assertEquals(0, p.materializedStatementCount, "装载期不物化语句树")

        val total = p.statementCount
        assertTrue(total > 0, "夹具必须有语句")
        // 片内 2 条：强制跨片（sampleProgram 有几十条语句）
        val src = p.buildSegmentedFlatSource(segmentSize = 2, maxSegments = 4)
        assertTrue(src.argStreamBase().isEmpty(), "还没建片时参数流为空")

        // 3) 同一下标恒同一实例（数据节键 / Vm L1 备忘 / 探针的身份契约）
        for (i in 0 until total) {
            assertSame(p.statement(i), p.statement(i), "[$i] 同一下标恒同一实例")
        }

        // 4) 逐条对拍（跨片也对）：分片压扁还原出来的语句与原树逐条结构相同。
        //    只比结构键，不比原始槽位 —— 表达式/字符串池下标是**片内**编号，跨片不可比。
        val seg = src as SegmentedFlatStmtSource
        for (start in 0 until total step 2) {
            val decoded = seg.decodeBlock(start)
            assertEquals(minOf(2, total - start), decoded.size, "[$start] 片内条数")
            for ((k, s) in decoded.withIndex()) {
                val orig = p.statement(start + k)
                assertEquals(orig.javaClass, s.javaClass, "[${start + k}] 语句类相同")
                assertEquals(orig.code, s.code, "[${start + k}] code 相同")
                assertEquals(FlatStmtKey.key(orig), FlatStmtKey.key(s), "[${start + k}] 结构键相同")
            }
        }
        for (i in 0 until total) {
            assertSame(p.statement(i), src.statementAt(i), "[$i] 兜底还原必须回到池内同一实例（数据节键）")
        }
        // 片内 2 条 + 上限 4 片 ⇒ 后半段要淘汰；只要读过的语句结果都对即可（LRU 只影响常驻）
        assertEquals(total, p.materializedStatementCount, "逐条取用后缓存条数 = 语句池条数")
    }

    @Test
    fun `整池对照档不在装载期建扁平池`(@TempDir dir: Path) {
        val f = writeArtifact(dir, "eager-no-flat.erbc", sampleProgram())
        val eager = ProgramLoader.loadMappedEager(f)
        assertNull(eager.flatStmtSource, "整池对照档保持改造前形状：装了语句树、不建扁平流")
        // 需要扁平池时仍能在池上建（proc 桥的既有入口不变），但**按片惰性**：首次取用才建第一片
        val src = eager.ensureFlatStmtSource()
        assertSame(src, eager.ensureFlatStmtSource(), "从池内树压出来的一次也幂等")
        assertTrue(src.argStreamBase().isEmpty(), "整池对照档也不在 ensure 时建片")
        val flat = FlatStatements.encode(eager.statements(), emptyList())
        assertEquals(flat.opAt(0), src.opAt(0), "首次取用建片后 op 对得上")
        val local = src.argStreamBase()
        val slots = flat.headAt(1) - flat.headAt(0)
        assertEquals(slots, src.endAt(0) - src.headAt(0), "第 0 条槽数相同")
        for (k in 0 until slots) {
            assertEquals(flat.stream[flat.headAt(0) + k], local[src.headAt(0) + k], "第 $k 槽相同")
        }
    }

    // ==================================================================
    // 5. 畸形产物：索引趟的报错位置/消息与整池档逐字相同
    // ==================================================================

    /** 记录区里的字节变异点（跳过分节头，只动段 3 正文）：与整池档对拍用。 */
    private fun mutate(
        src: ByteArray,
        factor: Int,
        value: Int,
    ): ByteArray {
        val out = src.copyOf()
        // 只在后半段（记录区大概率在那儿）做变异，避开文件头/段表
        val from = src.size / 2
        val at = from + (src.size - from - 1).toLong() * factor / 16L
        out[at.toInt()] = value.toByte()
        return out
    }

    private fun outcome(block: () -> Program): String = try {
        val p = block()
        // 成功也要把「按下标取一遍池」的结果对上：惰性档的报错可能推迟到取用时
        val sb = StringBuilder("ok ")
        for (i in 0 until p.statementCount) {
            val s = p.statement(i)
            sb.append(s.javaClass.simpleName).append('@').append(s.line).append(';')
        }
        sb.toString()
    } catch (e: Throwable) {
        "err:${e.javaClass.name}:${e.message}"
    }

    @Test
    fun `记录区变异：惰性档与整池档的异常类型消息逐字相同`(@TempDir dir: Path) {
        val clean = ErbcWriter.encode(sampleProgram(statements = 300))
        var checked = 0
        var diverged = 0
        val reasons = StringBuilder()
        for (factor in 1..15) {
            val bytes = mutate(clean, factor, 0xC3)
            val f = dir.resolve("mut-$factor.erbc")
            Files.write(f, bytes)
            val eager = outcome { ProgramLoader.loadMappedEager(f) }
            val lazyOut = outcome { ProgramLoader.loadMapped(f) }
            checked++
            if (eager != lazyOut) {
                diverged++
                reasons.append("\n  变异 $factor：整池档=").append(eager.take(300)).append(" 惰性档=").append(lazyOut.take(300))
            }
        }
        assertTrue(checked >= 15, "变异点太少（$checked）：这一路没有真的比对")
        assertEquals(0, diverged, "惰性档与整池档在畸形产物上分歧（两条路径必须同一句话）：$reasons")
    }

    // ==================================================================
    // 6. 分段 mark（判据③）
    // ==================================================================

    @Test
    fun `解码内部的分段 mark 覆盖每个段与索引趟条带`(@TempDir dir: Path) {
        val f = writeArtifact(dir, "marks.erbc", sampleProgram(statements = 300))
        val stages = ArrayList<String>()
        ProgramLoader.loadMapped(f, LoadStageMark { stages.add(it) })

        assertTrue(stages.size > 8, "分段 mark 太少（${stages.size}）：解码内部没有真的打过点")
        // 每个段的收口都在
        for (expected in listOf(
            "program.${LoadStages.STRINGS}",
            "program.${LoadStages.CONSTANTS}",
            "program.${LoadStages.STMT_STRINGS}",
            "program.${LoadStages.STMT_NODES}",
            "program.${LoadStages.FUNCTIONS}",
            "program.${LoadStages.CODE}",
            "program.${LoadStages.LINES}",
            "program.${LoadStages.VALIDATE}",
        )) {
            assertTrue(stages.contains(expected), "缺段 mark：$expected（全部=${stages}）")
        }
        // 索引趟的 1/8 条带 + 引用校验的 1/8 条带：段名带序号，且序号单调
        val blocks = stages.filter { Regex("^program\\..* \\d+/8$").matches(it) }
        assertTrue(blocks.isNotEmpty(), "索引趟/校验趟没有按大块打 mark：$stages")
        val stmtBlocks = blocks.filter { it.contains(LoadStages.STMT_RECORDS) }.map { it.substringAfterLast(' ').substringBefore('/').toInt() }
        assertEquals(stmtBlocks.sorted(), stmtBlocks, "语句索引条带的序号必须单调")
        assertTrue(stmtBlocks.first() == 1 || stmtBlocks.size == 1, "第一条带应从 1/8 开始：$stmtBlocks")
    }

    // ==================================================================
    // 2b. 真实产物：惰性 vs 整池的装载账（ERATW_ERBC 动态门）
    // ==================================================================

    private class AllocMeter private constructor(
        private val bean: com.sun.management.ThreadMXBean,
        private val tid: Long,
    ) {
        fun read(): Long = bean.getThreadAllocatedBytes(tid)

        companion object {
            fun open(): AllocMeter? = runCatching {
                val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
                    ?: return null
                if (!bean.isThreadAllocatedMemorySupported) return null
                bean.isThreadAllocatedMemoryEnabled = true
                val tid = Thread.currentThread().id
                bean.getThreadAllocatedBytes(tid)
                AllocMeter(bean, tid)
            }.getOrNull()
        }
    }

    private fun realArtifact(): Path? {
        val raw = System.getenv("ERATW_ERBC")?.takeIf { it.isNotBlank() } ?: return null
        val p = Path.of(raw)
        val f = if (Files.isDirectory(p)) p.resolve("program.erbc") else p
        return f.takeIf { Files.isRegularFile(it) }
    }

    @Test
    fun `真实产物：压扁即弃后装载不常驻语句树`() {
        val path = realArtifact()
        assumeTrue(path != null, "ERATW_ERBC 未指向可读的 program.erbc：跳过真实产物用例")
        val meter = AllocMeter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数：跳过")
        val m = requireNotNull(meter)
        val fileBytes = Files.size(path!!)

        // ---- 档 1：波次 C 的装载形态（压扁即弃）--------------------------------
        HeapMeter.resetPeaks()
        val beforeFlat = HeapMeter.settled()
        val a0 = m.read()
        val t0 = System.nanoTime()
        val program = ProgramLoader.loadMapped(path)
        val flatMs = (System.nanoTime() - t0) / 1_000_000
        val flatAllocated = m.read() - a0
        val flatPeak = HeapMeter.peak()
        val flatRetained = HeapMeter.settled() - beforeFlat
        val total = program.statementCount
        assertEquals(0, program.materializedStatementCount, "波次 C：装载（含压扁）返回后不得把语句树留在池里")

        // ---- 档 2：同一份 Program 上把语句树整池物化（= 波次 B 的稳态形状）-------
        // 同一个配置、同一份产物，只差「树在不在缓存里」⇒ 下面这份增量就是压扁即弃省下的东西。
        // 别拿 `loadMappedEager` 当对照：那一档连表达式池根也整池物化（多 ~40 MiB），
        // 两处差异混在一起就说不清收益来自哪一刀（2026-10-28 首版读数踩过）。
        System.gc()
        val beforeTree = HeapMeter.settled()
        val a1 = m.read()
        val t1 = System.nanoTime()
        for (i in 0 until total) program.statement(i)
        val treeMs = (System.nanoTime() - t1) / 1_000_000
        val treeAllocated = m.read() - a1
        val treeRetained = HeapMeter.settled() - beforeTree
        assertEquals(total, program.materializedStatementCount, "整池取用后缓存条数 = 语句池条数")

        println(
            "[erbc-lazy] 产物 ${fileBytes / 1_048_576} MiB / 语句池 $total 条：" +
                "波次 C 装载（压扁即弃）分配=${mib(flatAllocated)} 常驻=+${mib(flatRetained)} " +
                "峰值=${mib(flatPeak)} ${flatMs}ms；" +
                "同池整池物化 分配=${mib(treeAllocated)} 常驻=+${mib(treeRetained)} ${treeMs}ms",
        )
        println(
            "[erbc-lazy] 压扁即弃省下的语句树 = ${mib(treeRetained)}" +
                "（= 波次 B 稳态里那一份；整池物化的分配 ${mib(treeAllocated)} 里其余是解完即弃的 churn）",
        )

        // 波次 C 的承诺：装载后语句树不驻留 ⇒ 上面那整池物化的增量就是「被省掉的」那份。
        // 下界取 0.2×产物：语句外壳 + Argument 容器（表达式结点与串表两条档都要留）。
        assertTrue(
            treeRetained > fileBytes / 5,
            "语句树整池物化应至少 +0.2×产物（${mib(fileBytes / 5)}），实测 ${mib(treeRetained)}：" +
                "读数过小说明这份装置没真的把树拖回堆里，闸失去意义",
        )
        // 反向 sanity：压扁即弃后的常驻（扁平流 + 语句引用的表达式结点 + 规范表 + 串表）
        // 不该超过 1.5×产物 —— 超过就说明装载期又把树整池留下了。
        assertTrue(
            flatRetained < fileBytes * 3 / 2,
            "波次 C 装载常驻超过 1.5×产物：${mib(flatRetained)}（疑似退回整池物化）",
        )
        // 段内峰值（HeapMeter）只作参照：它是 total-free 的尖峰，被压扁趟那批「解完即弃」的
        // 语句对象灌满，跟 GC 时机强相关（HeapMeter 的 KDoc 已写明 G1 的 peakUsage 会低估短尖峰）。
        // 装机判据取 post-GC 常驻与「取用 N 条付 N 条」的增量，峰值归属由分段 mark 负责。
        println(
            "[erbc-lazy] 段内峰值只作参照：波次 C 装载 ${mib(flatPeak)}" +
                "（post-GC 常驻才是判据：+${mib(flatRetained)} vs 整池稳态 +${mib(flatRetained + treeRetained)}）",
        )
    }

    @Test
    fun `真实产物：取用 1000 条语句只付 1000 条的内存`() {
        val path = realArtifact()
        assumeTrue(path != null, "ERATW_ERBC 未指向可读的 program.erbc：跳过真实产物用例")
        val meter = AllocMeter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数：跳过")
        val m = requireNotNull(meter)
        val program = ProgramLoader.loadMapped(path!!)
        assertEquals(0, program.materializedStatementCount, "装载返回后不得物化语句")
        val before = HeapMeter.settled()
        val a0 = meter.read()
        // 跳过前 90 万条：证明「按需」不是「按前缀」——取用高下标的语句不必把整池拖出来
        val start = (program.statementCount * 9 / 10).toInt()
        for (i in start until start + 1000) program.statement(i)
        val allocated = meter.read() - a0
        val gained = HeapMeter.settled() - before
        println(
            "[erbc-lazy] 取用下标 $start..${start + 999} 这 1,000 条：分配 +${mib(allocated)} / 常驻 +${mib(gained)}" +
                "（已物化 ${program.materializedStatementCount} / ${program.statementCount}）",
        )
        assertEquals(1000, program.materializedStatementCount, "已物化条数必须正好是取用条数")
        assertTrue(gained < 24L * 1024 * 1024, "1,000 条语句的常驻不该超过 24 MiB，实为 ${mib(gained)}")
    }

    @Test
    fun `真实产物：分段 mark 给出逐段常驻与峰值归属`() {
        val path = realArtifact()
        assumeTrue(path != null, "ERATW_ERBC 未指向可读的 program.erbc：跳过真实产物用例")
        val runtime = Runtime.getRuntime()
        val stages = ArrayList<Pair<String, Long>>()
        ProgramLoader.loadMapped(path!!, LoadStageMark { name ->
            val used = runtime.totalMemory() - runtime.freeMemory()
            stages.add(name to used)
        })
        assertTrue(stages.size > 12, "分段 mark 只有 ${stages.size} 个：解码内部的大块点位没打出来")
        val top = stages.maxByOrNull { it.second }
        println("[erbc-marks] 装载链共 ${stages.size} 个 mark 点，峰值归属 = ${top!!.first} @ ${mib(top.second)}")
        for ((name, used) in stages) println("[erbc-marks]   $name  used=${mib(used)}")
        // 归属必须落在装载**内部**的某一段（不是宿主那条粗粒度的 load.program）：
        // 段名契约 = `LoadStages` 常量 + `program.` 前缀（LoadStageMark 的 KDoc），
        // INTERN（语句实参规范化去重）也是这种内部段 —— 它在 total−free 口径下最容易登顶
        // （种子去重把表达式池根灌进规范表，瞬时垃圾最多），只作归属用、不当装机判据；
        // 装机判据是下面那条「催过 GC 的实时高水位 ≤ estimatedHeapBytes」。
        // 2026-10-24 N8：这里原先红在「INTERN 段名漏了 program. 前缀」，已在生产侧补齐
        // （ProgramLoader.decodeMapped 的 mark 调用），断言本身不放宽。
        assertTrue(top.first.startsWith("program."), "峰值没有归到解码内部的段上：${top.first}")
    }

    /**
     * 预检估算的下界核对（M10-MEM）：**催过 GC 的解码实时高水位不许超过 `estimatedHeapBytes`**。
     *
     * 上面那条 mark 用例读的是 `total − free`，那里面有「索引趟解完即弃」的垃圾，
     * 两次跑能差出 342.6 / 725.5 MiB —— 拿它当预检口径会把估算吹到虚高（旧 2.0× 就是这么来的，
     * 结果 512 MiB 恒判不足、产品永远走失败屏）。本用例每个 mark 点先 `HeapMeter.settled()`
     * （连打 GC 取最小值 = 真的活着的那部分），要的就是 ART 挡位真正要装下的那一下。
     *
     * 口径选择理由见 `ProgramLoader.estimatedHeapBytes` 的表与重标说明：ART 在分配失败前自己会
     * GC，所以它要的是 **live 高水位**（改前高台 = 段 3 eager 节点表；M-MEM1 扫描化后归属挪到
     * 池重灌，本机复测 195.1 → 92.9 MiB），不是 churn、也不是装载后的终值。
     *
     * T2-64 波次 C 起高台挪到装载链的 `语句池压扁即弃` 段（扁平池本体 = 进程稳态），
     * 2026-10-28 本机实测 444.8 MiB（1.416×产物） ⇒ 系数相应从 1.1 抬到 1.45。
     */
    @Test
    fun `真实产物：催过 GC 的解码实时高水位被预检估算包住`() {
        val path = realArtifact()
        assumeTrue(path != null, "ERATW_ERBC 未指向可读的 program.erbc：跳过真实产物用例")
        val fileBytes = Files.size(path!!)
        // 侧车只按字节数参与估算；同级有 program.meta 就用真值，没有就按 0（估算的产物项不受影响）
        val metaPath = path.resolveSibling("program.meta")
        val metaBytes = if (Files.isRegularFile(metaPath)) Files.size(metaPath) else 0L

        val base = HeapMeter.settled()
        val live = ArrayList<Pair<String, Long>>()
        val program = ProgramLoader.loadMapped(path, LoadStageMark { name ->
            live.add(name to HeapMeter.settled() - base)
        })
        val instructions = program.instructionCount
        val top = requireNotNull(live.maxByOrNull { it.second })
        val required = ProgramLoader.estimatedHeapBytes(fileBytes, metaBytes, instructions)
        println(
            "[erbc-live] ${live.size} 个 mark 点的实时高水位（已催 GC）= ${mib(top.second)} @ ${top.first}" +
                "（产物 ${mib(fileBytes)} = ${(1000 * top.second / fileBytes).toInt() / 10.0}×）；" +
                "预检估算 ${mib(required)} ⇒ ${if (top.second <= required) "包住" else "少报"}",
        )
        for ((name, used) in live) println("[erbc-live]   $name live=+${mib(used)}")
        assertTrue(
            top.second <= required,
            "预检少报：解码中途真活着的字节 ${mib(top.second)} 大于估算 ${mib(required)}" +
                " ⇒ 真机会在预检判够之后 OOM（`ProgramLoader.estimatedHeapBytes` 的产物项要往上抬）",
        )
        // 这条是「不许反过来把估算吹虚高」的上界：估算超过 1.6×产物就说明又退回了整池口径
        // （波次 C 之后扁平池本体就必须驻留，1.45× 是实测 1.416× 之上的余量；整池档实测 1.659×）。
        assertTrue(
            required <= fileBytes * 8 / 5,
            "预检估算 ${mib(required)} 超过 1.6×产物（${mib(fileBytes * 8 / 5)}）：" +
                "惰性档的实时高水位口径被回退成整池档",
        )
    }

    private fun mib(bytes: Long): String {
        val tenths = (bytes * 10 + 524_288) / 1_048_576
        return "${tenths / 10}.${tenths % 10} MiB"
    }
}
