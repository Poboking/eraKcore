package com.eratw.port.core.stmt

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcEncodeOptions
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.loader.ProgramLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.junit.jupiter.api.io.TempDir

/**
 * CMP-15 必红夹具：**表达式池里「同形异 `line`/`col`」的两根必须各自保真**。
 *
 * ## 契约（`Program.internStatementArgs` KDoc 第 1 条，池侧）
 *
 * 种子段把表达式池根灌进 `ArgInterner` 的规范表；`ArgInterner.shapeOf` 对 `Expr` 跳过
 * `line`/`col`（运行期零读者，折叠跨语句必须忽略它们）。于是池里两颗只差行列号的根
 * 被当成同一形态，**第二个根被就地换成第一个根的实例**：
 *
 * - `program.expr(1)` 读回 `IntLiteral(1, 3, 1)` 而不是 `IntLiteral(1, 9, 7)`（值漂移）；
 * - 再编码时段 2 的节点表少一条记录、两个池根指向同一节点（字节漂移）——
 *   `ErbcWriter.RecordSink` 的 memo 键是 `Expr` 的 data class 相等（含行列号）。
 *
 * ## 判据
 *
 * 两条装载路径（整读 `load(bytes)`、映射惰性 `loadMapped(file)`）各一遍：
 * 逐下标 `expr(k)` 的 data class 值不变、两个根不是同一实例、**再编码逐字节回到原容器**。
 *
 * 修法不是把 `line`/`col` 塞进全局掩码（那会把跨语句折叠整个关掉，`PoolExprShareTest`
 * 的钉死行为也一并破掉），而是让**种子段只登记、不替换**：池根身份与池内子树原样保留，
 * 规范表照旧拿到形态代表供语句侧让位。
 */
class StmtArgInternPoolRootTest {

    private val options = ErbcEncodeOptions(version = ErbcFormat.VERSION_2)

    /** 两个只差行列号的池根：`IntLiteral(1, 3, 1)` 与 `IntLiteral(1, 9, 7)`。 */
    private fun twoRootsProgram(): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val k0 = b.expr(IntLiteral(1L, 3, 1))
        val k1 = b.expr(IntLiteral(1L, 9, 7))
        b.emit(Opcode._EVAL_INT, k0, SlotAddr.local(0), 0, 3)
        b.emit(Opcode._EVAL_INT, k1, SlotAddr.local(1), 0, 9)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    private fun assertRootsPreserved(tag: String, p: Program) {
        assertEquals(2, p.exprCount, "$tag 表达式池条数")
        assertEquals(IntLiteral(1L, 3, 1), p.expr(0), "$tag 池根 0 的 data class 值（含 line/col）")
        assertEquals(IntLiteral(1L, 9, 7), p.expr(1), "$tag 池根 1 的 data class 值（含 line/col）")
        assertNotSame(p.expr(0), p.expr(1), "$tag 同形异行列号的两根被归一成同一实例")
        // 幂等：装载后已跑过一次，再跑一次不许再动池根
        val before0 = p.expr(0)
        val before1 = p.expr(1)
        p.internStatementArgs()
        assertSame(before0, p.expr(0), "$tag 二次规范化不得换掉池根 0")
        assertSame(before1, p.expr(1), "$tag 二次规范化不得换掉池根 1")
    }

    @Test
    fun `整读装载后两个同形异行列号的池根各自保真`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(twoRootsProgram(), options)
        val p = ProgramLoader.load(bytes)
        assertRootsPreserved("整读", p)
        assertContentEquals(
            bytes,
            ErbcWriter.encode(p, options),
            "整读装载 → 再编码必须逐字节回到原容器（段 2 池根表与节点表都不得被归一）",
        )
    }

    @Test
    fun `映射惰性装载后两个同形异行列号的池根各自保真`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(twoRootsProgram(), options)
        val file = dir.resolve("pool-roots.erbc")
        Files.write(file, bytes)
        val p = ProgramLoader.loadMapped(file)
        assertRootsPreserved("惰性", p)
        assertContentEquals(
            bytes,
            ErbcWriter.encode(p, options),
            "惰性装载 → 再编码必须逐字节回到原容器（段 2 池根表与节点表都不得被归一）",
        )
    }
}
