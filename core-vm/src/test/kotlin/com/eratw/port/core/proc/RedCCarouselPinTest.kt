package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * BOARD 红 C 回归钉子：标题 `TINPUT` 超时重进 `LOOP RESULT` 是**正常标题轮播**，
 * 不是吃答案；预排队答案被当场消费。
 *
 * 被撤红的经过（2026-10-22 假红）：真机开局 20s 无操作后标题重播，曾被读成
 * 「TINPUT 把提交的答案吃掉了」。实际形状在 TITLE.ERB：
 * - `:50` `RESULT = 1`（进 DO 前的初值）；
 * - `:54` `DO` … `:84` `TINPUT 20000,2,0,"开始播放DEMO"` … `:86` `SELECTCASE RESULT`
 *   … `:121` `LOOP RESULT`。
 * TINPUT 恒 `HasDefValue = true`（spec: FunctionIdentifier.cs:1022），超时走
 * `endTimer` → `RunEmueraProgram("")` 的空文本收尾（spec: EmueraConsole.cs:1043/1068），
 * 空文本在整数支取 `DefIntValue`（spec: EmueraConsole.cs:1119-1124）⇒ 超时后
 * `RESULT = 2`（默认值），`SELECTCASE` 落 `CASE 2`（空支），`LOOP RESULT` 按
 * `!= 0` 回边（spec: FunctionIdentifier.cs:3435-3440）⇒ 重画标题再来一轮 ——
 * 这就是轮播，与「答案被吃」无关：提交的答案在 TINPUT 出队那一刻就写进 RESULT
 * 并当场决定分支（`CASE 0` 新模式选择 / `CASE 1` 存档槽管理），不丢不剩。
 *
 * 本文件最小复现 `DO … TINPUT … SELECTCASE RESULT … LOOP RESULT` 形状，钉三件事：
 * 1. 超时交付**默认值 2**（不是 0、不是保留旧值、更不是丢弃等待）；
 * 2. 超时后 `LOOP RESULT` 回边重播（标题体再跑），轮播可收尾（终局步正常）；
 * 3. 预排队答案（先 submit 后 begin）被 TINPUT 当场消费：0 → 模式选择、1 → 存档路径。
 *
 * 与语料的唯一偏差：时限按比例缩小（`20000` → `60` 毫秒）—— [GameSession] 的
 * [QueueInputSink] 用墙钟、无注入点，20s×3 轮会把测试拖成一分钟级；时限长短不改变
 * 判据（截止即空文本收尾 → 默认值分支）。
 *
 * 装置抄 [GotoTitleTest]（真解析器 + 真编译器 + 真 [GameSession]，临时目录、不吃语料）。
 */
class RedCCarouselPinTest {

    private class Fixture(
        val program: Program,
        val metadata: BootMetadata,
    )

    private fun parser(): LineParser = LineParser(
        // RESULT 运行期 registry 是 1 维整数数组（SystemLayout.kt:25 int1D → Storage.G_INT1）：
        // 符号表必须同口径声明成 1 维，裸引用才会按「一般 1D：省略→0」补成 RESULT:0
        // （expr/Parser.kt 的省略补全规则）；声明成 0 维会在运行期炸「需要 1 个下标」。
        SymbolTable()
            .declareVariable("RESULT", OperandType.INT, 1)
            .declareVariable("G_PASS", OperandType.INT, 1)
            .declareVariable("G_LAST", OperandType.INT, 1),
    )

    private fun parse(vararg lines: String): List<com.eratw.port.core.stmt.Statement> {
        val p = parser()
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "TITLE.ERB", i + 1, text))
        }.filterNotNull()
    }

    private fun privateDim(raw: String): DimSpec {
        val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw))
            ?: error("测试数据必须是合法 #DIM 行: $raw")
        return DimParser.parse(split.first, split.second, isPrivate = true)
    }

    private fun fixture(body: List<String>): Fixture {
        val compiler = ErbProgramCompiler()
        val stmts = parse("@SYSTEM_TITLE", *body.toTypedArray())
        val fb: FunctionBytecode = compiler.compileFunction(
            FuncSig("SYSTEM_TITLE"),
            stmts,
            setOf("SYSTEM_TITLE"),
        )
        val program = compiler.build("SYSTEM_TITLE")
        val metadata = BootMetadata(
            labels = mapOf("SYSTEM_TITLE" to fb.labels),
            globalDims = listOf("#DIM G_PASS", "#DIM G_LAST").mapIndexed { i, raw ->
                BootMetadata.RawDim(raw, "TITLE.ERB", i + 1)
            },
            privateDims = emptyList(),
            functions = listOf(
                FuncMeta(
                    index = fb.index, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE",
                    defIndex = 0, position = "TITLE.ERB:1",
                ),
            ),
            fileByKey = mapOf("SYSTEM_TITLE" to "TITLE.ERB"),
        )
        return Fixture(program, metadata)
    }

    private fun session(f: Fixture, dir: Path): GameSession = GameSession(
        gameRoot = dir,
        program = f.program,
        metadata = f.metadata,
        consoleWidth = 199,
        randSeed = 20260226L,
    )

    /**
     * TITLE.ERB:50-121 的最小形状。`$ONCEANAIN` 标签与 `CASE 0` 里的
     * `RESETDATA/ADDCHARA/BEGIN FIRST`（`:92-101`）不在本形状内——那条路要跑真开局链，
     * 由 GameBootSmoke 系列覆盖；这里只钉 DO/TINPUT/SELECTCASE/LOOP 四族行为。
     * `IF G_PASS >= 3 → RESULT = 0` 是**测试收敛闸**（语料的对应物是 CASE 0/1 分支
     * 把 RESULT 改写成 0 后 LOOP 退出），语料本身靠真输入收尾。
     */
    private val carouselBody = listOf(
        "RESULT = 1",
        "DO",
        "G_PASS = G_PASS + 1",
        "PRINTFORML TITLE-PASS {G_PASS}",
        "TINPUT 60,2,0,\"超时\"",
        "G_LAST = RESULT",
        "PRINTFORML AFTER-INPUT R={G_LAST}",
        "SELECTCASE RESULT",
        "CASE 0",
        "PRINTL MODE-SELECT",
        "CASE 1",
        "PRINTL SAVE-SLOT",
        "RESULT = 0",
        "CASE 2",
        "PRINTL TIMEOUT-CAROUSEL",
        "IF G_PASS >= 3",
        "RESULT = 0",
        "ENDIF",
        "ENDSELECT",
        "LOOP RESULT",
        "PRINTL TITLE-EXIT",
    )

    private fun read(store: VariableStore, name: String): Long = store.readInt(name, longArrayOf(0))

    @Test
    fun `TINPUT 超时重进 LOOP RESULT 是标题轮播 超时交付默认值`(@TempDir dir: Path) {
        val s = session(fixture(carouselBody), dir)
        try {
            assertTrue(s.begin(BeginType.TITLE), "SYSTEM_TITLE 已登记，begin 应执行它")

            // 三轮轮播：每轮 TINPUT 超时 → RESULT = 默认值 2（EmueraConsole.cs:1119-1124）
            // → CASE 2 打印轮播标记 → LOOP RESULT 回边（FunctionIdentifier.cs:3437-3440）
            // → DO 体重画。读到 R=0 或轮播标记缺席 = 超时被改成丢弃/清零（红 C 假红同款误读）。
            assertEquals(
                listOf(
                    "TITLE-PASS 1", "AFTER-INPUT R=2", "TIMEOUT-CAROUSEL",
                    "TITLE-PASS 2", "AFTER-INPUT R=2", "TIMEOUT-CAROUSEL",
                    "TITLE-PASS 3", "AFTER-INPUT R=2", "TIMEOUT-CAROUSEL",
                    "TITLE-EXIT",
                ),
                s.console.loggedLines(),
                "超时必须交付默认值 2 并重播，不是吃答案",
            )
            assertEquals(3L, read(s.store, "G_PASS"), "DO 体跑满三轮")
            assertEquals(0L, read(s.store, "RESULT"), "收敛闸把 RESULT 改写为 0，LOOP 退出")

            // 轮播收尾后是正常终局步，不是卡死/粘滞（Process.cs:2959-2962 endNormal）
            val step = s.resumeSystem()
            assertEquals(StepOutcome.FAILED, step.outcome)
            assertEquals("endNormal", step.handler)
        } finally {
            s.close()
        }
    }

    @Test
    fun `预排队答案被 TINPUT 当场消费 新局与存档两条路径都前进`(@TempDir dir: Path) {
        // 新局路径：提交 0（TITLE.ERB:87 CASE 0 → 模式选择侧）。先 submit 后 begin：
        // 队列无请求方时提交不丢（QueueInputSink 的口径，GameSessionTest 同款用法）。
        val s0 = session(fixture(carouselBody), dir)
        try {
            assertTrue(s0.inputSink.submitInt(0))
            assertTrue(s0.begin(BeginType.TITLE))
            assertEquals(0, s0.inputSink.pendingCount, "答案被 TINPUT 当场消费，不丢不剩")
            assertEquals(
                listOf("TITLE-PASS 1", "AFTER-INPUT R=0", "MODE-SELECT", "TITLE-EXIT"),
                s0.console.loggedLines(),
                "提交 0 → RESULT=0 → CASE 0 分支，LOOP RESULT 退出",
            )
        } finally {
            s0.close()
        }

        // 存档路径：提交 1（TITLE.ERB:112 CASE 1 → CALL Real_Time_Save(1002, -1) 存档槽管理侧）
        val s1 = session(fixture(carouselBody), dir)
        try {
            assertTrue(s1.inputSink.submitInt(1))
            assertTrue(s1.begin(BeginType.TITLE))
            assertEquals(0, s1.inputSink.pendingCount, "答案被 TINPUT 当场消费，不丢不剩")
            assertEquals(
                listOf("TITLE-PASS 1", "AFTER-INPUT R=1", "SAVE-SLOT", "TITLE-EXIT"),
                s1.console.loggedLines(),
                "提交 1 → RESULT=1 → CASE 1 分支（语料侧进存档槽管理）",
            )
        } finally {
            s1.close()
        }
    }
}
