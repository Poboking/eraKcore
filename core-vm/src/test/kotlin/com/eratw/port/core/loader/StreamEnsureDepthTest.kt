package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CMP-21 的可测判据：惰性节点重建的递归上限 + `StackOverflowError` 的收口。
 *
 * ## 缺陷
 *
 * `MAX_ENSURE_DEPTH = 2048` 在 ART 上**晚于真实栈容量**（每层 ≥3 帧、VM 线程栈更小），
 * 而 `StackOverflowError` 是 `Error`，越过 [ProgramLoader.decodeMapped] 的
 * `RuntimeException`/`IOException` 收口 ⇒ 失败屏裸报 `StackOverflowError`（`BootFailures`
 * 归 OTHER），观众看不到「产物嵌套过深」。
 *
 * ## 修法
 *
 * 1. 上限下调为 ART 安全值并**可配**（`decode(...)`/`ProgramLoader.decodeMapped` 的
 *    `maxEnsureDepth` 形参，默认 [MAX_ENSURE_DEPTH]）；超过上限走
 *    `RecordSource.buildPrefixThrough` 的升序退路，结果逐字段不变；
 * 2. `decodeMapped`/`load` 在**解码与规范化去重**两段都接住 `StackOverflowError`，
 *    包成 [ErbcLoadException]「嵌套过深」⇒ 归 `CORRUPT_ARTIFACT`，失败屏有话说。
 *
 * ## 红判据
 *
 * 4,000 层深的表达式树、装载线程只给 128 KiB 栈：旧实现以裸 `StackOverflowError`
 * 冒到调用方；新实现必须以 [ErbcLoadException]（cause 是 `StackOverflowError`；消息含
 * 「嵌套过深」）收口。编码那一半跑在 64 MiB 栈的线程里，避免测试装置自己先炸。
 */
class StreamEnsureDepthTest {

    /** 深度 [depth] 的二元表达式链：`((((1+1)+1)…)+1)`。 */
    private fun deepProgram(depth: Int): Program {
        var root: Expr = IntLiteral(1L, 1, 1)
        for (i in 0 until depth) {
            root = Binary(OperatorCode.PLUS, root, IntLiteral(1L, 1, i + 2), OperandType.INT, 1, i + 2)
        }
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val poolRoot = b.expr(root)
        val stmt = b.statement(InstructionStmt(FunctionCode.WAIT, ExpressionArgument(root), 1, "WAIT 深树"))
        b.emit(Opcode._EVAL_INT, poolRoot, SlotAddr.NONE, 0, 1)
        b.emit(Opcode.WAIT, stmt, 0, SlotAddr.NONE, 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    /** 在指定栈大小的线程里跑 [block]；返回它抛出的 Throwable（成功返回 null）。 */
    private fun runInThread(
        name: String,
        stackBytes: Long,
        block: () -> Unit,
    ): Throwable? {
        var failure: Throwable? = null
        val t = Thread(
            null,
            {
                try {
                    block()
                } catch (x: Throwable) {
                    failure = x
                }
            },
            name,
            stackBytes,
        )
        t.start()
        t.join()
        return failure
    }

    @Test
    fun `深度上限是 ART 安全值且可配（默认值回归阀）`() {
        assertTrue(
            MAX_ENSURE_DEPTH in 1..512,
            "2048 层 × 每层 ≥3 帧在 ART 上先于上限打穿栈（CMP-21）；默认值必须回到安全区，实际 $MAX_ENSURE_DEPTH",
        )
        assertEquals(256, MAX_ENSURE_DEPTH, "默认值改了就同步改这条钉（避免悄悄回到 2048）")
    }

    @Test
    fun `深表达式树在惰性池下不爆栈：装载与首次物化都走迭代`(@TempDir dir: Path) {
        val depth = 4_000
        val f = dir.resolve("deep.erbc")
        // 编码自己也是递归的：测试装置给它 64 MiB 栈，别让「造夹具」先炸。
        val encodeFailure = runInThread("n5-encode-deep", 64L shl 20) {
            Files.write(f, ErbcWriter.encode(deepProgram(depth)))
        }
        assertNull(encodeFailure, "编码夹具失败（这是装置问题，不是被测语义）：$encodeFailure")

        // M12-EXPRLAZY（2026-10-24）：表达式池改成按需物化后，装载期不再整池递归 ⇒ 4,000 层深树
        // 在装载期不再爆栈。这条风险从「装载」搬到「首次 `expr(k)`」，因此契约随之改成：
        // 装载必须成功，且首次物化必须走 `buildPrefixThrough` 的迭代补链、不得抛 StackOverflowError。
        //
        // T2-64 波次 C 补一条口径：装载链的 FLAT 段（「压扁即弃」）会逐条解码语句，语句引用的
        // 深层表达式树在那一步走 `ArgInterner` 的**递归下降**（与 `statementAt` 同一条路，
        // 节点重建本身已改迭代补链）。因此装载对小栈不再免疫，契约收成两条：
        //  1) 小栈（128 KiB / 1 MiB）上装载若失败，必须是干净收口（[ErbcLoadException]
        //     「嵌套过深」），不许裸 `StackOverflowError` 冒到宿主（CMP-21 的红线本身没变）；
        //  2) 给足栈（8 MiB，≈ ART 主线程挡位）时装载必须成功 —— 4,000 层 > MAX_ENSURE_DEPTH，
        //     所以这条同时钉住「节点重建走 buildPrefixThrough 的迭代补链」。
        // TODO(verify)：真机装载跑在哪个线程、该线程的栈挡位是多少（ART 主线程 ≈8 MiB、
        // 普通 Thread 默认 1 MiB），真语料实测最深表达式远小于 4,000 层，但挡位要落字。
        for ((label, stack) in listOf("128k" to (128L shl 10), "1m" to (1L shl 20))) {
            val smallStack = runInThread("n5-load-deep-$label", stack) {
                ProgramLoader.loadMapped(f)
            }
            if (smallStack != null) {
                val e = assertIs<ErbcLoadException>(smallStack, "$label 小栈上装载失败必须收口成 ErbcLoadException：$smallStack")
                assertTrue(
                    e.message?.contains("嵌套过深") == true,
                    "收口文案必须是「嵌套过深」（归 CORRUPT_ARTIFACT）：${e.message}",
                )
            }
        }
        val loadFailure = runInThread("n5-load-deep-8m", 8L shl 20) {
            ProgramLoader.loadMapped(f)
        }
        assertNull(loadFailure, "给足栈（8 MiB）时装载（含压扁即弃趟）必须成功，实际：$loadFailure")

        // 风险点搬到「首次物化」：把整池摸一遍必须靠迭代补链走完。
        // 装载那一半沿用上面那条口径（8 MiB，FLAT 段会递归规范化语句实参），
        // 物化这一半用 1 MiB（接近 ART 默认线程栈）—— 两条性质分开钉，别混在一条栈里。
        var deep: Program? = null
        val loadForTouch = runInThread("n5-load-touch", 8L shl 20) {
            deep = ProgramLoader.loadMapped(f)
        }
        assertNull(loadForTouch, "物化用例的装载本身必须在给足栈时成功：$loadForTouch")
        val p = requireNotNull(deep)
        val touchFailure = runInThread("n5-touch-deep", 1L shl 20) {
            for (k in 0 until p.exprCount) p.expr(k)
        }
        assertNull(
            touchFailure,
            "深树首次物化必须走迭代补链（buildPrefixThrough），不得爆栈，实际：$touchFailure",
        )
    }

    @Test
    fun `深度上限可配：压到 2 层强制升序退路，再编码仍逐字节相同`(@TempDir dir: Path) {
        val depth = 24
        val bytes = ErbcWriter.encode(deepProgram(depth))
        val f = dir.resolve("limit.erbc")
        Files.write(f, bytes)
        val eager = ProgramLoader.loadMappedEager(f)
        val mapped = MappedErbc.open(f)
        try {
            // 上限 2 < 树深 24 ⇒ lazyEnsure 第二条递归就转 buildPrefixThrough（升序补表）。
            val lazy = ProgramLoader.decodeMapped(mapped, maxEnsureDepth = 2)
            for (i in 0 until lazy.statementCount) lazy.statement(i)
            assertEquals(eager.statement(0).raw, lazy.statement(0).raw)
            // 退路不许改语义：整份 Program 再编码必须逐字节等于原产物（红线）。
            assertContentEquals(
                bytes,
                ErbcWriter.encode(lazy),
                "maxEnsureDepth=2 走升序退路之后，.erbc 再编码必须与原字节相同",
            )
        } finally {
            mapped.close()
        }
    }
}
