package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.perf.ResidentWalker
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.perf.SizeModel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 侧车惰性化单测（M8-BOOTMETA）：[GameSession] 装配完成后**只留**运行期还要读的表。
 *
 * 断言的是 `specs` 之外的**产物口径**（全量语料 `program.meta` 实测）：
 *
 * | 段 | 全量行数 | 处置 |
 * |---|---:|---|
 * | `labelset` | 110,072（其中 900 个非空） | 装配期剪枝成 900 个函数 / 1,623 条标签 |
 * | `label` | 1,623 | 保留（内层表与原表共享实例） |
 * | `file` | 110,072 | 释放（无读者） |
 * | `datasec` | 3,874 | 保留，键 = 语句池下标（波次 C 起直查，无实例反查层） |
 * | `global-dim` / `private-dim` | 7,201 / 19,744 | 装配期全量消费后释放 |
 *
 * 「没有读者」与「必须全量」两类判据都在 [GameSession] 类 KDoc 的「侧车生命周期」一节里；
 * 本文件只把判据钉成可执行断言。可达性用测试侧的对象图遍历器
 * [ResidentWalker]（与 `PoolDecomp` 同一把尺子）判定**身份可达**，不依赖 GC 时机，
 * 因此不会因 `System.gc()` 的抖动而闪红。
 *
 * C# 依据：`ErhLoader.cs:249-338`（装载期一次建池，没有「用到再声明」的口子）、
 * `LabelDictionary.cs:382-389`（标签按当前函数查）、`FunctionIdentifier.cs:4285`
 * （数据节挂在语句实例上）。这三条决定了保留/释放的分界。
 */
class BootMetadataLazyTest {

    // ------------------------------------------------------------------
    // 夹具：真解析器 + 真编译器（同 GameSessionTest 的口径）
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("G_CONST", OperandType.INT, 1)
            .declareVariable("LOC_OK", OperandType.INT, 1)
            .declareVariable("LOC_C", OperandType.INT, 1)
            .declareVariable("RESULT", OperandType.INT, 0),
    )

    /**
     * 编译一段 `SYSTEM_TITLE`：返回 (Program, PRINTDATA 语句的池下标)。
     *
     * 语句池下标是侧车数据节表的键（`BootMetadata.dataSections` 的 KDoc），所以夹具必须
     * 用真编译器产出池，否则 `program.statement(index)` 的身份对不上。
     */
    private fun compile(body: List<String>): Pair<Program, Int> {
        val p = parser()
        val lines = listOf("@SYSTEM_TITLE") + body
        val stmts = lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "TEST.ERB", i + 1, text))
        }
        val compiler = ErbProgramCompiler()
        compiler.compileFunction(FuncSig("SYSTEM_TITLE"), stmts, setOf("SYSTEM_TITLE"))
        val program = compiler.build("SYSTEM_TITLE")
        return program to program.statements().indexOfFirst { it is PrintDataStmt }
    }

    /** 带一条 PRINTDATA 的小语料；`dataIndex` 是它的池下标。 */
    private val bodyWithData = listOf(
        "#DIM LOC_OK",
        "PRINTL HELLO",
        "PRINTDATA",
        "DATA AAA",
        "DATA BBB",
        "ENDDATA",
    )

    private fun privateDim(raw: String): DimSpec {
        val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw))
        assertNotNull(split, "测试数据必须是合法 #DIM 行: $raw")
        return DimParser.parse(split.first, split.second, isPrivate = true)
    }

    private fun titleMeta(
        labels: Map<String, Map<String, Int>> = emptyMap(),
        dataSections: Map<Int, List<List<Expr>>> = emptyMap(),
        globalDims: List<BootMetadata.RawDim> = emptyList(),
        privateDims: List<BootMetadata.PrivateDim> = emptyList(),
        fileByKey: Map<String, String> = emptyMap(),
        extraFunctions: List<FuncMeta> = emptyList(),
    ): BootMetadata = BootMetadata(
        labels = labels,
        dataSections = dataSections,
        globalDims = globalDims,
        privateDims = privateDims,
        functions = listOf(
            FuncMeta(index = 0, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE", defIndex = 0, position = "TEST.ERB:1"),
        ) + extraFunctions,
        fileByKey = fileByKey,
    )

    private fun session(dir: Path, program: Program, metadata: BootMetadata): GameSession =
        GameSession(gameRoot = dir, program = program, metadata = metadata, consoleWidth = 199, randSeed = 20260226L)

    // ------------------------------------------------------------------
    // 1) 装配后侧车对象本身不可达（= 49.2 MB 侧车不再常驻）
    // ------------------------------------------------------------------

    @Test
    fun `装配后侧车对象与无用段都不可从会话到达`(@TempDir dir: Path) {
        val (program, dataIndex) = compile(bodyWithData)
        val meta = titleMeta(
            labels = mapOf(
                "SYSTEM_TITLE" to mapOf("LOOP" to 0),
                "EMPTY_A" to emptyMap(),
                "EMPTY_B" to emptyMap(),
            ),
            dataSections = mapOf(dataIndex to listOf(listOf<Expr>(StrLiteral("AAA")))),
            globalDims = listOf(BootMetadata.RawDim("#DIM CONST G_CONST = 7", "TEST.ERB", 1)),
            privateDims = listOf(BootMetadata.PrivateDim("SYSTEM_TITLE", privateDim("#DIM LOC_OK"))),
            fileByKey = mapOf("SYSTEM_TITLE" to "dir/01_main.ERB", "EMPTY_A" to "dir/02_other.ERB"),
        )
        val s = session(dir, program, meta)
        try {
            val w = ResidentWalker(SizeModel(SizeModel.detectRefWidth()))
            w.walk(s)

            assertFalse(w.isVisited(meta), "会话不得再持有侧车对象（构造参数只在装配期消费）")
            assertFalse(w.isVisited(meta.fileByKey), "fileByKey 没有运行期读者，必须随侧车回收")
            assertFalse(w.isVisited(meta.globalDims), "#DIM 原文已在装配期消费，不得留在会话里")
            assertFalse(w.isVisited(meta.privateDims), "私有 #DIM 声明已在装配期消费，不得留在会话里")
            assertFalse(w.isVisited(meta.labels), "外层标签表必须换成剪枝副本")

            assertTrue(
                w.isVisited(meta.labels.getValue("SYSTEM_TITLE")),
                "非空标签表按实例共享（剪枝只换外层表，不复制标签）",
            )
            assertTrue(w.isVisited(meta.dataSections), "数据节表是 PRINTDATA 的运行期输入，必须保留")

            // 侧车对象里仍被会话可达的部分是 0 字节意义上的「回收量」：这里只验证它为正，
            // 精确读数由交付报告按全量产物给出（单测夹具尺寸无参考价值）。
            assertTrue(w.consume(meta) > 0L, "被释放的侧车段按对象模型应可计量")
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // 2) 标签：剪枝后语义不变
    // ------------------------------------------------------------------

    @Test
    fun `空标签表被剪掉 非空表按实例共享`(@TempDir dir: Path) {
        val (program, _) = compile(listOf("PRINTL HELLO"))
        val meta = titleMeta(
            labels = mapOf(
                "SYSTEM_TITLE" to mapOf("LOOP" to 12, "TAIL" to 40),
                "EMPTY_A" to emptyMap(),
                "EMPTY_B" to emptyMap(),
            ),
        )
        val s = session(dir, program, meta)
        try {
            assertEquals(1, s.sidecar.labelFunctionCount, "3 个 labelset 里只有 1 个非空")
            assertEquals(2, s.sidecar.labelCount)
            // 「内层表按实例共享、不复制」由可达性用例断言（ResidentWalker.isVisited）；
            // 这里只钉数量与查询结果。
            assertEquals(12, s.sidecar.labelPc("SYSTEM_TITLE", "LOOP"))
            assertEquals(40, s.sidecar.labelPc("SYSTEM_TITLE", "TAIL"))
        } finally {
            s.close()
        }
    }

    @Test
    fun `标签解析保持大小写归一与未定义返回 null`(@TempDir dir: Path) {
        val (program, _) = compile(listOf("PRINTL HELLO"))
        val meta = titleMeta(labels = mapOf("SYSTEM_TITLE" to mapOf("LOOP" to 12)))
        val s = session(dir, program, meta)
        try {
            // ProcessRuntime.tryResolveLabel 前置大写（ProcessRuntime.kt:1063），投影层直查
            assertEquals(12, s.runtime.tryResolveLabel("system_title", "loop"), "两侧大小写都归一")
            assertNull(s.runtime.tryResolveLabel("SYSTEM_TITLE", "NOPE"))
            assertNull(s.runtime.tryResolveLabel("EMPTY_A", "LOOP"), "空标签表的函数必须查不到")
            assertEquals(12, s.sidecar.labelPc("SYSTEM_TITLE", "LOOP"), "投影层按已大写键直查")
        } finally {
            s.close()
        }
    }

    @Test
    fun `满量语料外形下外层表从 110072 压到 900`(@TempDir dir: Path) {
        // 与全量产物同形：110,072 个 labelset，其中 900 个非空、合计 1,623 条 label。
        val labels = HashMap<String, Map<String, Int>>(200_000)
        var remaining = 1_623
        for (i in 0 until 110_072) {
            val key = "FUNC_%06d".format(i)
            labels[key] = if (i < 900) {
                val take = if (i < 723) 2 else 1
                remaining -= take
                val table = LinkedHashMap<String, Int>(take * 2)
                repeat(take) { n -> table["LABEL_$n"] = i * 10 + n }
                table
            } else {
                emptyMap()
            }
        }
        assertEquals(0, remaining, "夹具应恰好 900 个函数 / 1,623 条标签")

        val (program, _) = compile(listOf("PRINTL HELLO"))
        val meta = titleMeta(labels = labels)
        val s = session(dir, program, meta)
        try {
            assertEquals(900, s.sidecar.labelFunctionCount, "外层表只剩有标签的函数")
            assertEquals(1_623, s.sidecar.labelCount, "标签一条不少")

            val w = ResidentWalker(SizeModel(SizeModel.detectRefWidth()))
            val retained = w.consume(s.sidecar)
            val dropped = w.consume(labels)
            assertTrue(dropped > retained, "被剪掉的空表（$dropped B）应大于保留的标签表（$retained B）")
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // 3) 数据节：键 = 语句池下标（波次 C 起直查，不再建实例反查层）
    // ------------------------------------------------------------------

    @Test
    fun `数据节按池下标直查 同下标恒同组`(@TempDir dir: Path) {
        val (program, dataIndex) = compile(bodyWithData)
        assertTrue(dataIndex >= 0, "夹具必须产出 PRINTDATA 语句")
        val meta = titleMeta(
            dataSections = mapOf(
                dataIndex to listOf(listOf<Expr>(StrLiteral("AAA")), listOf<Expr>(StrLiteral("BBB"))),
            ),
        )
        val s = session(dir, program, meta)
        try {
            val groups = s.runtime.dataGroupsOf(dataIndex)
            assertNotNull(groups, "PRINTDATA 必须命中侧车数据节")
            assertEquals(2, groups.size)
            assertEquals("AAA", (groups[0][0] as StrLiteral).value)

            // 同下标恒同组：侧车原表就是运行期那张表，重复查询拿回**同一个组实例**。
            assertSame(groups, s.runtime.dataGroupsOf(dataIndex), "同下标必须恒同组（不得重建/换实例）")
            assertSame(groups, s.sidecar.groupsOf(dataIndex), "投影层与注入点查的是同一张表")
            assertNull(s.runtime.dataGroupsOf(dataIndex + 1), "相邻下标没有数据节")
        } finally {
            s.close()
        }
    }

    @Test
    fun `没有数据节时查询恒为 null`(@TempDir dir: Path) {
        val (program, dataIndex) = compile(bodyWithData)
        val s = session(dir, program, titleMeta())
        try {
            assertNull(s.runtime.dataGroupsOf(dataIndex))
        } finally {
            s.close()
        }
    }

    @Test
    fun `越界的语句下标只按它自己的下标命中 不挂到别的语句上`(@TempDir dir: Path) {
        val (program, dataIndex) = compile(bodyWithData)
        val outOfRange = program.statementCount + 5
        val s = session(
            dir,
            program,
            titleMeta(dataSections = mapOf(outOfRange to listOf(listOf<Expr>(StrLiteral("XX"))))),
        )
        try {
            // 直查口径：侧车登记的就是数据节表本身，越界项只能被同一个越界下标命中。
            assertNull(s.runtime.dataGroupsOf(dataIndex), "有效下标不得拿到那条越界项的组")
            assertNotNull(s.runtime.dataGroupsOf(outOfRange), "越界项按自己的下标可命中（池里没有这条语句 ⇒ 无人取值）")
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // 4) 必须全量的两张表：函数索引 / #DIM 注册
    // ------------------------------------------------------------------

    @Test
    fun `函数名索引与事件入口仍在装配期建好`(@TempDir dir: Path) {
        val (program, _) = compile(listOf("PRINTL HELLO"))
        val meta = titleMeta(
            extraFunctions = listOf(
                FuncMeta(index = 0, key = "EVENTFIRST", name = "EVENTFIRST", isEvent = true, defIndex = 1),
            ),
        )
        val s = session(dir, program, meta)
        try {
            // 非事件名走 nonEventByKey：BEGIN TITLE 直接调 SYSTEM_TITLE。
            assertTrue(s.begin(BeginType.TITLE), "SYSTEM_TITLE 必须已登记")
            assertEquals(listOf("HELLO"), s.console.loggedLines())

            // 事件名走 eventMembersByKey：CALL 事件 → CodeEE（ALLOW CALL ON EVENT FUNCTIONS:NO）。
            val e = assertFailsWith<CodeEE> { s.runtime.callFunction("EVENTFIRST") }
            assertTrue(e.message!!.contains("对事件函数"), "事件入口必须已建索引: ${e.message}")

            assertNull(s.runtime.tryCall("NO_SUCH_FUNCTION"), "未登记名字返回 null")
        } finally {
            s.close()
        }
    }

    @Test
    fun `全局与私有 DIM 声明仍在装配期全量注册`(@TempDir dir: Path) {
        val (program, _) = compile(listOf("#DIM LOC_OK", "PRINTL HELLO"))
        val meta = titleMeta(
            globalDims = listOf(
                BootMetadata.RawDim("#DIM CONST G_CONST = 7", "TEST.ERB", 1),
                // 尺寸用上一条 CONST：不动点重试环必须真的跑起来（SystemVariableBootstrap.install）。
                BootMetadata.RawDim("#DIM ARR, G_CONST", "TEST.ERB", 2),
            ),
            privateDims = listOf(
                BootMetadata.PrivateDim("SYSTEM_TITLE", privateDim("#DIM LOC_OK")),
                BootMetadata.PrivateDim("SYSTEM_TITLE", privateDim("#DIM CONST LOC_C = 3")),
            ),
        )
        val s = session(dir, program, meta)
        try {
            assertEquals(2, s.globalDimInstall.declared, "全局声明必须全量注册: ${s.globalDimInstall.failures}")
            assertTrue(s.globalDimInstall.failures.isEmpty(), "${s.globalDimInstall.failures}")
            assertEquals(7L, s.globalDimInstall.constants["G_CONST"])
            assertEquals(2, s.privateDimDeclared, "私有声明必须全量注册: ${s.privateDimFailures}")
            assertTrue(s.privateDimFailures.isEmpty(), "${s.privateDimFailures}")
        } finally {
            s.close()
        }
    }

    @Test
    fun `DataSectionSource 走投影而不是另建一份实例表`(@TempDir dir: Path) {
        val (program, dataIndex) = compile(bodyWithData)
        val meta = titleMeta(dataSections = mapOf(dataIndex to listOf(listOf<Expr>(StrLiteral("AAA")))))
        val s = session(dir, program, meta)
        try {
            val printed = s.runtime.dataGroupsOf(dataIndex)
            assertNotNull(printed)
            assertEquals("AAA", (printed[0][0] as StrLiteral).value, "投影共享侧车的表达式树实例")
        } finally {
            s.close()
        }
    }
}
