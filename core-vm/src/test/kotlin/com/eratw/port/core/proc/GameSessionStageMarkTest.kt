package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

/**
 * [GameSession] 装配分段埋点的回归测试。
 *
 * 只钉两件事实：
 * 1. 可选的 [GameSession] 构造参数 `stageMark` 在装配过程中收到**全部**段名，且顺序与
 *    代码执行顺序一致（`LoadTrace.mark` 用「距上一段」口径，乱序/漏段都会让读数失真）；
 * 2. 不传 `stageMark` 时既有调用点仍能正常构造（默认参数零改动）。
 *
 * 不在这里断言每段耗时（墙钟读数属于真机装置，单测不稳定），也不触发 `groupsOf`：
 * 数据节的语句实例索引仍是首次查询才建。
 */
class GameSessionStageMarkTest {

    private data class Fixture(val program: Program, val metadata: BootMetadata)

    private fun fixture(): Fixture {
        val parser = LineParser(SymbolTable())
        val lines = listOf(
            LogicalLine(LineKind.FUNCTION_LABEL, "TEST.ERB", 1, "@SYSTEM_TITLE"),
            LogicalLine(LineKind.CODE, "TEST.ERB", 2, "PRINTL HELLO"),
        )
        val statements = lines.mapNotNull { parser.parseLogicalLine(it) }
        val compiler = ErbProgramCompiler()
        val function = compiler.compileFunction(
            FuncSig("SYSTEM_TITLE"),
            statements,
            setOf("SYSTEM_TITLE"),
        )
        val program = compiler.build("SYSTEM_TITLE")
        val metadata = BootMetadata(
            labels = emptyMap(),
            dataSections = emptyMap(),
            globalDims = emptyList(),
            privateDims = emptyList(),
            functions = listOf(
                FuncMeta(
                    index = function.index,
                    key = "SYSTEM_TITLE",
                    name = "SYSTEM_TITLE",
                    defIndex = 0,
                    position = "TEST.ERB:1",
                ),
            ),
            fileByKey = emptyMap(),
        )
        return Fixture(program, metadata)
    }

    @Test
    fun `stageMark 按装配顺序收到全部 session 段`(@TempDir dir: Path) {
        val f = fixture()
        val marks = ArrayList<String>()
        val session = GameSession(
            gameRoot = dir,
            program = f.program,
            metadata = f.metadata,
            consoleWidth = 199,
            randSeed = 20260226L,
            stageMark = { marks += it },
        )
        try {
            assertEquals(
                listOf(
                    "session.csv",
                    "session.config",
                    "session.store",
                    "session.tables",
                    "session.dims",
                    "session.privatedims",
                    "session.keywords",
                    "session.save",
                    "session.hosts",
                    "session.intern",
                    "session.runtime",
                    "session.sidecar",
                    "session.tail",
                ),
                marks,
                "段名/顺序必须与 init 的实际执行边界一一对应",
            )
            assertEquals(marks.size, marks.toSet().size, "同一段名只允许 mark 一次")
            assertNull(session.gameData, "临时目录没有 CSV，应走无数据降级")
        } finally {
            session.close()
        }
    }

    @Test
    fun `缺省 stageMark 时既有构造调用仍可用`(@TempDir dir: Path) {
        val f = fixture()
        val session = GameSession(
            gameRoot = dir,
            program = f.program,
            metadata = f.metadata,
            consoleWidth = 199,
            randSeed = 20260226L,
        )
        try {
            assertNull(session.gameData)
        } finally {
            session.close()
        }
    }
}
