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
 * INTERN-STMT 的**容器级**钉点：装载后的 `.erbc` 再编码必须逐字节回到原容器，
 * 两条路径（整读 [ProgramLoader.loadFile] / 映射惰性 [ProgramLoader.loadMapped]）必须编出同一份字节。
 *
 * ## 为什么要独立一枚小夹具
 *
 * `MappedCodeSourceTest` 的真实产物用例只在有 314 MiB 产物时才跑，判据同样是「两条路径逐字节相同」，
 * 但失守形态（语句侧结点被折进**只差行列号**的池内代表）在整份产物上藏在 1,716,636 条语句里，
 * 定位一次要装两遍 Program。本夹具把那一条形态缩到三条语句：
 *
 * - 池根 `IntLiteral(7, 9, 9)` 与语句参数 `IntLiteral(7, 3, 4)` 同形异行列号；
 * - 段 3 的节点记录按 [com.eratw.port.core.code.ErbcWriter] 的 `RecordSink.memo`
 *   去重，键是 `Expr` 的 data class 相等（**含** `line`/`col`）。
 *
 * 旧口径（`ArgInterner.shapeOf` 跳过 `line`/`col`）把语句侧那颗折进池根 ⇒ 落盘的行列号换成 9/9
 * ⇒ 段 3 少一条节点记录、语句字段指向另一条 ⇒ 再编码从段表起就错位（真实产物上首差偏移 26 =
 * FUNCTION_TABLE 起点）。规约 `specs/erbc-format.md:196-198` 把这种并合点名为缺陷口径，
 * `:414`、表行 `:571` 另外钉了「语句池按身份追加、严禁去重」。
 *
 * 第二枚用例守住折叠**没有被整个关掉**：逐字段全等（含行列号）的语句侧结点照旧共享同一实例
 * （源码直编产物由 `ExprCanonicalizer` 把语句树的行列号归一为 -1，跨语句折叠因此仍然成立）。
 */
class StmtArgInternLineColTest {

    private val options = ErbcEncodeOptions(version = ErbcFormat.VERSION_2)

    /** 池根与语句参数同形异行列号，另有一条与前者逐字段全等的语句参数。 */
    private fun programWithCrossLineCol(): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        b.statement(
            InstructionStmt(
                FunctionCode.PRINT,
                ExpressionArrayArgument(arrayListOf(IntLiteral(7L, 3, 4))),
                41,
                "PRINT 7",
            ),
        )
        b.statement(
            InstructionStmt(
                FunctionCode.PRINT,
                ExpressionArrayArgument(arrayListOf(IntLiteral(7L, 5, 6))),
                42,
                "PRINT 7",
            ),
        )
        b.statement(
            InstructionStmt(
                FunctionCode.PRINT,
                ExpressionArrayArgument(arrayListOf(IntLiteral(7L, 5, 6))),
                43,
                "PRINT 7",
            ),
        )
        val poolIndex = b.expr(IntLiteral(7L, 9, 9))
        b.emit(Opcode._EVAL_INT, poolIndex, SlotAddr.local(0), 0, 9)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    private fun termOf(
        p: Program,
        index: Int,
    ): IntLiteral = (p.statement(index).args as ExpressionArrayArgument).terms[0] as IntLiteral

    @Test
    fun `整读装载的再编码逐字节回到原容器`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(programWithCrossLineCol(), options)
        val file = dir.resolve("cross-linecol.erbc")
        Files.write(file, bytes)

        val p = ProgramLoader.loadFile(file)
        assertEquals(IntLiteral(7L, 3, 4), termOf(p, 0), "语句 0 的参数必须保留自己的行列号")
        assertEquals(IntLiteral(7L, 5, 6), termOf(p, 1), "语句 1 的参数必须保留自己的行列号")
        assertEquals(IntLiteral(7L, 9, 9), p.expr(0), "池根逐字段不变")
        assertContentEquals(
            bytes,
            ErbcWriter.encode(p, options),
            "整读装载 → 再编码必须逐字节回到原容器（同形异行列号的结点不许被折进池内代表）",
        )
    }

    @Test
    fun `两条装载路径的再编码逐字节相同`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(programWithCrossLineCol(), options)
        val full = ProgramLoader.load(bytes)
        val file = dir.resolve("mapped.erbc")
        Files.write(file, bytes)
        val mapped = ProgramLoader.loadMapped(file)
        assertContentEquals(bytes, ErbcWriter.encode(full, options), "整读档 vs 原容器")
        assertContentEquals(bytes, ErbcWriter.encode(mapped, options), "惰性档 vs 原容器")
        assertContentEquals(
            ErbcWriter.encode(full, options),
            ErbcWriter.encode(mapped, options),
            "两条装载路径的再编码必须逐字节相同",
        )
    }

    /** 折叠没被关掉：逐字段全等（含行列号）的语句侧结点仍共享同一实例。 */
    @Test
    fun `逐字段全等的语句侧结点仍然折叠`() {
        val p = programWithCrossLineCol()
        p.internStatementArgs()
        assertSame(termOf(p, 1), termOf(p, 2), "1/2 两条参数逐字段全等，必须折进同一实例")
        assertNotSame(termOf(p, 0), termOf(p, 1), "0 与 1 只差行列号，不再折叠")
        assertNotSame(p.expr(0), termOf(p, 0), "池根与语句侧只差行列号，不再折叠")
    }
}
