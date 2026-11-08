package com.eratw.port.erbcc.compile

import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.fn.BuiltinMethod
import com.eratw.port.core.fn.Builtins
import com.eratw.port.core.fn.VariadicGroup
import com.eratw.port.core.stmt.MethodArgument
import com.eratw.port.core.stmt.MethodStmt
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * ENGFIX2 B4 任务 A：成对实参约束装载期相位的定向用例。
 *
 * 权威锚：原版 `FunctionMethod.cs:186-193`（`MatchVariadicGroup`，`ArgsNotFitExpr` 在 :191）、
 * 装载期调用点 `ArgumentParser.cs:1119-1127` / `IdentifierDictionary.cs:576-585`、
 * 上屏 `ErbLoader.cs:481-490` + `ParserMediator.cs:120-125`；第二份数组形态的精确个数见
 * `FunctionMethodCreator.cs:2141-2199`。
 *
 * 反向证明：① 临时撤掉 `CorpusCompile` 里 `pairedArgLoadWarning` 钩子 → 端到端两例判红；
 * ② 临时撤掉 `MapDataTableMethods.kt` 两处 `variadicGroup` 声明 → 纯判式与端到端全判红；
 * 恢复后全绿。判据读的是 [Builtins] 注册表里的 [VariadicGroup] 声明，不是方法名白名单。
 */
class PairedArgLoadCheckTest {

    @TempDir
    lateinit var tmp: Path

    private fun stmt(name: String, argc: Int, line: Int = 7): MethodStmt =
        MethodStmt(
            name,
            MethodArgument(FuncCall(name, List(argc) { IntLiteral(1) }, OperandType.INT)),
            line,
            "",
        )

    private fun warn(name: String, argc: Int, users: Set<String> = emptySet()) =
        pairedArgLoadWarning(stmt(name, argc), "PAIR.ERB", users)

    // ------------------------------------------------------------------
    // 1) 纯判式：奇数报、偶数与数组形态放行（Add: 1+2n 或 4；Set: 2+2n 或 5）
    // ------------------------------------------------------------------

    @Test
    fun `DT_ROW_ADD 奇数实参上屏 偶数与四参数组形态放行`() {
        assertNotNull(warn("DT_ROW_ADD", 2), "2 参 = 固定前缀 1 后余 1，不成对")
        assertNotNull(warn("DT_ROW_ADD", 6), "6 参 = 余 5，不成对")
        assertNull(warn("DT_ROW_ADD", 1), "1 参只建行，合法")
        assertNull(warn("DT_ROW_ADD", 3), "1+2×1 合法")
        assertNull(warn("DT_ROW_ADD", 4), "4 参命中第二份数组形态，不能按奇数判红")
        assertNull(warn("DT_ROW_ADD", 5), "1+2×2 合法")
    }

    @Test
    fun `DT_ROW_SET 奇数实参上屏 偶数与五参数组形态放行`() {
        assertNotNull(warn("DT_ROW_SET", 7), "7 参 = 固定前缀 2 后余 5，不成对")
        assertNull(warn("DT_ROW_SET", 4), "2+2×1 合法")
        assertNull(warn("DT_ROW_SET", 5), "5 参命中第二份数组形态")
        assertNull(warn("DT_ROW_SET", 6), "2+2×2 合法")
        assertNull(warn("DT_ROW_SET", 2), "个数下界以下归个数闸，本组不判")
        assertNull(warn("DT_ROW_SET", 3), "个数下界以下归个数闸，本组不判")
    }

    @Test
    fun `用户方法遮蔽同名内建与未声明方法不判`() {
        assertNull(warn("DT_ROW_ADD", 2, setOf("DT_ROW_ADD")), "用户 #FUNCTION 先于内建（IdentifierDictionary.cs:548-565）")
        assertNull(warn("MAP_SIZE", 2), "未声明变参组的方法不判")
    }

    @Test
    fun `方法名大小写变体同样命中成对判据`() {
        // 正向覆盖：Builtins.registry 是大小写不敏感表（Builtins.kt:856，对应
        // FunctionMethodCreator.cs:9040 `methodList = new Dictionary(Config.StrComper)`），
        // 语料里小写 / 混合大小写写法
        // 都命中同一份 VariadicGroup 声明；用户方法遮蔽用归一后的名字比对仍生效。
        assertNotNull(warn("dt_row_add", 2), "小写方法名也应命中成对约束")
        assertNotNull(warn("Dt_Row_Set", 7), "混合大小写也应命中成对约束")
        assertNull(warn("dt_row_add", 2, setOf("DT_ROW_ADD")), "用户方法遮蔽仍生效")
    }

    @Test
    fun `告警行号按一基上屏且带函数与实参数`() {
        val w = assertNotNull(warn("DT_ROW_ADD", 2))
        assertEquals("PAIR.ERB", w.file)
        assertEquals(8, w.line, "Statement.line 是 0 基，LoadWarning 口径 1 基")
        assertTrue(w.message.contains("DT_ROW_ADD"))
        assertTrue(w.message.contains("实参 2 个"))
    }

    // ------------------------------------------------------------------
    // 2) 单一声明源：漏声明 evalRef 的变参组在构造期即失败（判词③「漏声明即漏保护」）
    // ------------------------------------------------------------------

    @Test
    fun `变参组必须随 evalRef 声明 否则构造即失败`() {
        assertFailsWith<IllegalArgumentException> {
            BuiltinMethod("X", OperandType.INT, 1, -1, variadicGroup = VariadicGroup(1, 2, 4, 1))
        }
    }

    @Test
    fun `DT 两族在注册表里都声明了变参组与 evalRef`() {
        for (name in listOf("DT_ROW_ADD", "DT_ROW_SET")) {
            val m = assertNotNull(Builtins.registry[name])
            assertNotNull(m.variadicGroup, "$name 应声明装载期成对判据")
            assertNotNull(m.evalRef, "$name 应保持裸 Expr 实参（needsExprArgs）")
        }
    }

    // ------------------------------------------------------------------
    // 3) 端到端：合成语料经 CorpusCompile.compile 在装载期上屏
    // ------------------------------------------------------------------

    private fun compileWith(body: String): List<String> {
        val root = Files.createDirectories(tmp.resolve("corpus"))
        Files.write(root.resolve("01_PAIR.ERB"), ("@MAIN\n$body\n").toByteArray(Charsets.UTF_8))
        val r = CorpusCompile.compile(root)
        assertTrue(
            r.ok,
            "合成语料应编译成功：failures=" + r.failures.joinToString(" | ") { "${it.file}:${it.line}:${it.message}" },
        )
        return r.warnings.map { it.message }
    }

    @Test
    fun `奇数对在装载期上屏告警`() {
        val messages = compileWith(
            """
            DT_CREATE "T"
            DT_ROW_ADD "T", "c"
            DT_ROW_SET "T", 1, "c", 1, "d", 2, "e"
            """.trimIndent(),
        )
        val paired = messages.filter { it.contains("参数个数不成对") }
        assertEquals(2, paired.size, "两族各一条：实际 $messages")
        assertTrue(paired.any { it.contains("DT_ROW_ADD") })
        assertTrue(paired.any { it.contains("DT_ROW_SET") })
    }

    @Test
    fun `偶数对与数组形态不产生装载期告警`() {
        val messages = compileWith(
            """
            DT_CREATE "T"
            DT_ROW_ADD "T", "c", 1
            DT_ROW_ADD "T", "c", 1, "d", 2
            DT_ROW_SET "T", 1, "c", 1
            DT_ROW_SET "T", 1, "c", 1, "d", 2
            """.trimIndent(),
        )
        assertTrue(
            messages.none { it.contains("参数个数不成对") },
            "合法形态不应误报：实际 $messages",
        )
    }
}
