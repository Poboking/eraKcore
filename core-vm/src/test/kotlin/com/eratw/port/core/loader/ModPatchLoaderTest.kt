package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.loader.ModPatchLoader.PATCH_EXTENSION
import com.eratw.port.core.patch.FunctionMetaRecord
import com.eratw.port.core.patch.PatchArtifacts
import com.eratw.port.core.patch.PatchHeader
import com.eratw.port.core.patch.patchBaseIdentity
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.vars.DimParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * R7-C4 出口判据（CONTRACT-R7-C §2/C4 ①-④）：ModPatchLoader 的装载链单元用例 +
 * GameSession 会话接线的端到端 mini。
 *
 * ## 判据与用例的映射
 *
 * - ① 端到端 mini：`端到端:` 前缀用例（base 产物 + 合法补丁 → 会话起来、补丁函数可解析、
 *   动态名 TRYCALLFORM 命中）
 * - ② I3/I5 负向：`I3 负向:` / `I5 负向:` 前缀用例（坏 sha / 计数不符 ⇒ 拒装载 + 整体回退、
 *   会话照常）
 * - ③ I4：`I4:` 前缀用例（装载后 base 四计数不变）
 * - ④ I1：`I1:` 前缀用例（无 mods 目录 / 空目录 ⇒ 行为与今天逐位一致）
 *
 * ## 夹具口径
 *
 * base 用真编译器（[ErbProgramCompiler]）编小语料（同 `GameSessionTest` 的模式）；
 * 补丁用 [CodeBuilder] 六参基线预置手工发射（波 A 钉子 A1 证过的「独立 builder 续编」
 * 形态，绝对下标窗口与 base 对齐），经 [ErbcWriter.encode] + [PatchArtifacts] 落盘成
 * 补丁产物文件 —— 装载路吃的是**落盘字节**，不是内存对象。
 *
 * R7-D E2 补发：`端到端 补丁 PRINTDATA` 用例改走 main 上的 [PatchCompiler]（C5 导入链
 * 同款真编译器，带基线预置）编 PRINTDATA 补丁，钉 ModPatchLoader.harvestDataSections 的
 * 追加读数（键 = PRINTDATA 语句的绝对语句池下标，与桌面 CorpusCompile.harvestDataSections
 * 同源口径）与 overlay 后的执行面（输出走到控制台 sink）；`hasPatchExtension` 直测钉
 * 扩展名判定的权威口径（大小写不敏感 + 后缀判定 + 主名非空）。
 */
class ModPatchLoaderTest {

    // ------------------------------------------------------------------
    // base 夹具：真编译器编小语料
    // ------------------------------------------------------------------

    private class BaseFixture(
        val program: Program,
        val metadata: com.eratw.port.core.loader.BootMetadata,
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = com.eratw.port.core.stmt.LineParser(
            SymbolTable()
                // CALLFORM 目标名的插值变量（#DIM MODNO，1 维 int——int 读写路径已有
                // GameSessionTest 背书；#DIM 无尺寸归一 1 维，符号表同口径）。
                .declareVariable("MODNO", OperandType.INT, 1),
        )
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "TEST.ERB", i + 1, text))
        }.filterNotNull()
    }

    /**
     * `#DIM`/`#DIMS` 原文 → [com.eratw.port.core.vars.DimSpec]（口径 = `GameSessionTest`
     * 的 privateDim 夹具：splitDeclaration 分词后交给 DimParser）。
     */
    private fun privateDimSpec(raw: String): com.eratw.port.core.vars.DimSpec {
        val split = com.eratw.port.core.proc.SystemVariableBootstrap.splitDeclaration(
            com.eratw.port.core.proc.SystemVariableBootstrap.stripComment(raw),
        )!!
        return DimParser.parse(split.first, split.second, isPrivate = true)
    }

    /**
     * base：`@SYSTEM_TITLE` 里 TRYCALLFORM 补丁函数（R7 的动态名调用场景）+ PRINTL。
     * 目标名**刻意用 `{MODNO}` 插值**（`PATCH_{MODNO}`，MODNO=0 ⇒ 运行期拼出 PATCH_0）：
     * 常量名（含可常量折叠的字面量拼接、单字符串）会被编译器落进「静态函数名」分支做
     * 编译期判名（known 集合没有补丁函数 ⇒ TRY 系「不发射」降级，
     * ErbProgramCompiler.kt:2611-2617）；二元拼接表达式也会被 CALL 家族的目标解析拆成
     * 「目标 + 实参」（funcNameTerm 只吃单个 term）。真实口上的动态名
     * （`TRYCALLFORM M_KOJO_K{NO:ARG}_…`，CONTRACT-R7 §0）正是单个带插值的
     * FuncnameTerm ⇒ 走 `_CALL_BY_NAME` 运行期解析——补丁函数只有这条路可达。
     * 无补丁时 TRYCALLFORM 静默跳过（TRY 前缀语义）⇒ I1 对拍两遍都只有 BASE_DONE。
     */
    private fun compileBase(): BaseFixture {
        val compiler = ErbProgramCompiler()
        val stmts = parse(
            "@SYSTEM_TITLE",
            "#DIM MODNO",
            "MODNO = 0",
            "TRYCALLFORM PATCH_{MODNO}",
            "PRINTL BASE_DONE",
        )
        val fb: FunctionBytecode = compiler.compileFunction(
            FuncSig("SYSTEM_TITLE"),
            stmts,
            setOf("SYSTEM_TITLE"),
        )
        val program = compiler.build("SYSTEM_TITLE")
        val metadata = BootMetadata(
            labels = mapOf("SYSTEM_TITLE" to fb.labels),
            dataSections = emptyMap(),
            globalDims = emptyList(),
            privateDims = listOf(
                // 私有 #DIM 的原文必须与函数体里的声明逐字同键（会话装配第 5 步只按这份
                // 侧车注册私有变量，不解析补丁/ERB 原文）；写错名 = 运行期「未定义变量」。
                BootMetadata.PrivateDim("SYSTEM_TITLE", privateDimSpec("#DIM MODNO")),
            ),
            functions = listOf(
                FuncMeta(
                    index = fb.index,
                    key = "SYSTEM_TITLE",
                    name = "SYSTEM_TITLE",
                    defIndex = 0,
                    position = "TEST.ERB:1",
                ),
            ),
            fileByKey = mapOf("SYSTEM_TITLE" to "TEST.ERB"),
        )
        return BaseFixture(program, metadata)
    }

    // ------------------------------------------------------------------
    // 补丁夹具：基线对齐的独立 builder + 落盘
    // ------------------------------------------------------------------

    /** 与 [base] 的绝对计数对齐的补丁 builder（ProgramBase 六参 → CodeBuilder 六参）。 */
    private fun patchBuilder(base: Program): CodeBuilder = CodeBuilder(
        codeBase = base.instructionCount * Instruction.WORDS,
        statementBase = base.statementCount,
        stringBase = base.stringCount,
        longBase = base.constantCount,
        exprBase = base.exprCount,
        functionBase = base.functions.size,
    )

    /**
     * mini 补丁：`PATCH_0`（PRINTL 字面量 + RET，动态名调用目标）、
     * `PATCH_ARGS(A, B$)`（带形参名的空体函数，FuncMeta 分桶断言用）与
     * `PATCH_LABELED`（带 `$LOOP` 标签，标签追加断言用）。
     * PATCH_0 的名字形态 = 口上选择器 `M_KOJO_K{NO}` 的迷你版（base 用 `CALLFORM PATCH_{N}`、
     * N=0 动态拼出）。
     */
    private fun compilePatch(base: Program): Program {
        val b = patchBuilder(base)
        val helloIdx = b.declareFunction("PATCH_0", b.here(), arity = 0, slotCount = 2)
        // b 的 bit0 = 末尾换行（与 Vm.FLAG_NEWLINE 同值，那边是内部常量）。
        b.emit(Opcode._PRINT_LITERAL, b.string("PATCH_0"), 1, 0, 101)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 102)
        b.declareFunction(
            "PATCH_ARGS", b.here(), arity = 2, slotCount = 4,
            paramNames = listOf("A", "B$"),
        )
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 103)
        val labeledIdx = b.declareFunction("PATCH_LABELED", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 104)
        b.declareLabel(labeledIdx, "LOOP", b.here())
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 105)
        return b.build(helloIdx)
    }

    /** 给 base 带一个 `$标签` 的补丁（标签抽取断言用）：`PATCH_LABELED` 内 `$LOOP`。 */
    private fun compileLabeledPatch(base: Program): Program {
        val b = patchBuilder(base)
        val idx = b.declareFunction("PATCH_LABELED", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 201)
        b.declareLabel(idx, "LOOP", b.here())
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 202)
        return b.build(idx)
    }

    /**
     * 把补丁产物落盘：[ErbcWriter.encode] 出补丁体（v2），[PatchHeader] 的六个 base-* 字段
     * 照 base 读数填（I3 校验通过的正向形态），[mangleHeader] 给负向用例改头。
     */
    private fun writePatch(
        modsDir: Path,
        fileName: String,
        base: Program,
        sha: String,
        patchProgram: Program,
        mangleHeader: (PatchHeader) -> PatchHeader = { it },
    ): Path {
        val body = ErbcWriter.encode(patchProgram)
        val id = base.patchBaseIdentity(sha)
        val header = mangleHeader(
            PatchHeader(
                baseInstructionCount = id.instructionCount,
                baseFunctionCount = id.functionCount,
                baseStatementCount = id.statementCount,
                baseStringCount = id.stringCount,
                baseLongCount = id.longCount,
                baseExprCount = id.exprCount,
                baseSha256 = sha,
                compiler = PatchArtifacts.compilerSummary(),
                patchFunctionCount = patchProgram.functions.size,
                patchStatementCount = patchProgram.statementCount - base.statementCount,
                patchStringCount = patchProgram.stringCount - base.stringCount,
                patchLongCount = patchProgram.constantCount - base.constantCount,
                patchExprCount = patchProgram.exprCount - base.exprCount,
                entryFunction = patchProgram.functions.first().name,
            ),
        )
        val p = modsDir.resolve(fileName)
        Files.write(p, PatchArtifacts.of(header, body).write())
        return p
    }

    // ------------------------------------------------------------------
    // Loader 单元用例
    // ------------------------------------------------------------------

    @Test
    fun `I1 无 mods 目录与空目录返回 base 原样`(@TempDir dir: Path) {
        val base = compileBase()
        // sha 只被 patchBaseIdentity 的形态闸消费（空目录不进装载链），任意 64 位
        // 小写十六进制即可。
        val anySha = "0".repeat(64)

        val missing = ModPatchLoader.load(dir.resolve("nope"), base.program, anySha)
        assertSame(base.program, missing.program, "无目录 = base 原样（同一实例）")
        assertFalse(missing.rolledBack)
        assertTrue(missing.failures.isEmpty() && missing.loadedPatches.isEmpty())

        val emptyDir = Files.createDirectories(dir.resolve("mods"))
        val empty = ModPatchLoader.load(emptyDir, base.program, anySha)
        assertSame(base.program, empty.program, "空目录 = base 原样（同一实例）")
        assertFalse(empty.rolledBack)
        assertTrue(empty.failures.isEmpty() && empty.loadedPatches.isEmpty() && empty.warnings.isEmpty())
    }

    @Test
    fun `合法补丁装载成功且函数表与标签追加`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        val patch = compilePatch(base.program)
        writePatch(modsDir, "kojo$PATCH_EXTENSION", base.program, sha, patch)

        val result = ModPatchLoader.load(modsDir, base.program, sha)

        assertFalse(result.rolledBack, "合法补丁应装载成功: ${result.failures}")
        assertTrue(result.failures.isEmpty())
        assertNotSame(base.program, result.program, "装载成功 = overlay 产物")
        val baseFnCount = base.program.functions.size
        assertEquals(baseFnCount + 3, result.program.functions.size, "补丁函数尾段追加")
        // 标签抽取：键 = 函数键（大写），值 = 标签名 → 绝对 pc（补丁窗口基线后的绝对口径）。
        val labeledTable = result.labels["PATCH_LABELED"]
        assertTrue(labeledTable != null && labeledTable.containsKey("LOOP"), "标签表追加: ${result.labels.keys}")
        val labPc = labeledTable!!.getValue("LOOP")
        assertTrue(
            labPc >= base.program.instructionCount,
            "标签 pc 必须是绝对口径（落在补丁区间），实为 $labPc",
        )
        // 数据节：mini 补丁没有 PRINTDATA ⇒ 空表（追加零条）。
        assertTrue(result.dataSections.isEmpty())
        // I7 清单：读数 = 补丁函数数与补丁段指令数。
        assertEquals(1, result.loadedPatches.size)
        assertEquals("kojo$PATCH_EXTENSION", result.loadedPatches[0].name)
        assertEquals(3, result.loadedPatches[0].functionCount)
        assertEquals(
            patch.instructionCount - base.program.instructionCount,
            result.loadedPatches[0].patchInstructionCount,
            "补丁段指令数 = 补丁绝对计数 − base 计数",
        )
        assertEquals(
            listOf("PATCH_0", "PATCH_ARGS", "PATCH_LABELED"),
            result.loadedPatches[0].functionKeys,
        )
    }

    @Test
    fun `I5 负向 多补丁并存时按原始 base 编的第二个补丁触发整体回退`(@TempDir dir: Path) {
        // v1 多补丁语义（ModPatchLoader 类 KDoc）：补丁身份校验（I3）恒对原始 base，
        // 两个都按原始 base 编的补丁窗口必然重叠 —— 第二个在 ProgramOverlay 的窗口
        // 对齐闸被拒 ⇒ 整体回退（第一个也作废）。这条用例把该语义钉死。
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        writePatch(modsDir, "a$PATCH_EXTENSION", base.program, sha, compilePatch(base.program))
        writePatch(modsDir, "b$PATCH_EXTENSION", base.program, sha, compileLabeledPatch(base.program))

        val result = ModPatchLoader.load(modsDir, base.program, sha)

        assertTrue(result.rolledBack, "第二个补丁窗口对齐必拒 ⇒ 整体回退")
        assertSame(base.program, result.program, "回退 = base 原样（同一实例，禁半装载）")
        assertTrue(result.loadedPatches.isEmpty())
        assertTrue(result.labels.isEmpty() && result.dataSections.isEmpty())
        assertEquals(1, result.failures.size)
        assertTrue(
            result.failures[0].startsWith("b$PATCH_EXTENSION"),
            "清单点名失败的补丁: ${result.failures[0]}",
        )
    }

    @Test
    fun `I3 负向 base sha 不符整体回退且失败清单点名`(@TempDir dir: Path) {
        val base = compileBase()
        val wrongSha = "a".repeat(64)
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        writePatch(modsDir, "kojo$PATCH_EXTENSION", base.program, wrongSha, compilePatch(base.program))

        val result = ModPatchLoader.load(modsDir, base.program, PatchArtifacts.sha256Hex(ByteArray(0)))

        assertTrue(result.rolledBack, "I3 不符必须整体回退")
        assertSame(base.program, result.program, "回退 = base 原样（同一实例）")
        assertTrue(result.loadedPatches.isEmpty())
        assertEquals(1, result.failures.size)
        val msg = result.failures[0]
        assertTrue(msg.startsWith("kojo$PATCH_EXTENSION"), "清单必须带补丁名: $msg")
        assertTrue(msg.contains("base-sha256"), "必须点名不符字段: $msg")
        assertTrue(msg.contains("期望") && msg.contains("实为"), "必须给期望与实际: $msg")
    }

    @Test
    fun `I3 负向 base 计数不符点名该字段`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        writePatch(
            modsDir, "kojo$PATCH_EXTENSION", base.program, sha, compilePatch(base.program),
            mangleHeader = { h ->
                PatchHeader(
                    baseInstructionCount = h.baseInstructionCount,
                    baseFunctionCount = h.baseFunctionCount + 1,
                    baseStatementCount = h.baseStatementCount,
                    baseStringCount = h.baseStringCount,
                    baseLongCount = h.baseLongCount,
                    baseExprCount = h.baseExprCount,
                    baseSha256 = h.baseSha256,
                    compiler = h.compiler,
                    patchFunctionCount = h.patchFunctionCount,
                    patchStatementCount = h.patchStatementCount,
                    patchStringCount = h.patchStringCount,
                    patchLongCount = h.patchLongCount,
                    patchExprCount = h.patchExprCount,
                    entryFunction = h.entryFunction,
                )
            },
        )

        val result = ModPatchLoader.load(modsDir, base.program, sha)

        assertTrue(result.rolledBack)
        assertSame(base.program, result.program)
        val msg = result.failures.single()
        assertTrue(msg.contains("base-functions"), "必须点名计数不符的字段: $msg")
    }

    @Test
    fun `I5 负向 第二个补丁失败整体回退 base 原样`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        writePatch(modsDir, "a$PATCH_EXTENSION", base.program, sha, compilePatch(base.program))
        // b 补丁 = 非法产物（头缺字段）⇒ read 即失败。
        Files.write(modsDir.resolve("b$PATCH_EXTENSION"), "erbc-patch 1\n".toByteArray())

        val result = ModPatchLoader.load(modsDir, base.program, sha)

        assertTrue(result.rolledBack, "I5：任一补丁失败必须整体回退")
        assertSame(base.program, result.program, "回退 = base 原样（同一实例，禁半装载）")
        assertTrue(result.loadedPatches.isEmpty(), "已成功装载的第一个补丁也一并作废")
        assertTrue(result.labels.isEmpty() && result.dataSections.isEmpty())
        assertTrue(result.warnings.isEmpty())
        assertEquals(1, result.failures.size)
        assertTrue(
            result.failures[0].startsWith("b$PATCH_EXTENSION"),
            "清单点名失败的补丁: ${result.failures[0]}",
        )
    }

    @Test
    fun `重名闸 补丁定义 base 已有同名函数被拒`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        // 补丁函数名撞 base 的 SYSTEM_TITLE（大写归一后同键）。
        val b = patchBuilder(base.program)
        val idx = b.declareFunction("system_title", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 301)
        writePatch(
            modsDir, "kojo$PATCH_EXTENSION", base.program, sha, b.build(idx),
        )

        val result = ModPatchLoader.load(modsDir, base.program, sha)

        assertTrue(result.rolledBack, "重名必须拒绝装载（否则后装载者静默遮蔽 base 函数）")
        val msg = result.failures.single()
        assertTrue(msg.contains("system_title"), "必须点名撞名的函数（补丁侧原名）: $msg")
        assertTrue(msg.contains("重名"), ": $msg")
    }

    @Test
    fun `补丁撞补丁同名——重名闸先于窗口闸点名多张不并存`(@TempDir dir: Path) {
        // 结构性事实：每张补丁的窗口都锚定裸 base 产物，第二张接不上 ProgramOverlay 的
        // 窗口对齐闸 ⇒ mods 里多张补丁从来不是合法形态（设备端导入链的正解是全部留档源
        // 并集重编成一张，见 app 侧 ModImportController.UNION_PATCH_NAME）。
        // 本用例钉两件事：① 重名闸在窗口闸之前触发；② 文案指向「多张不并存 + 重新导入」，
        // 而不是误导成「撞了 base 的函数」。
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        for (name in listOf("a$PATCH_EXTENSION", "b$PATCH_EXTENSION")) {
            val b = patchBuilder(base.program)
            val idx = b.declareFunction("DUP_ACROSS", b.here(), arity = 0, slotCount = 1)
            b.emit(Opcode._PRINT_LITERAL, b.string(name), 1, 0, 301)
            b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 302)
            writePatch(modsDir, name, base.program, sha, b.build(idx))
        }

        val result = ModPatchLoader.load(modsDir, base.program, sha)

        assertTrue(result.rolledBack, "多张补丁必回退（I5 全或无）: ${result.failures}")
        val msg = result.failures.single()
        assertTrue(msg.contains("更早装载的补丁"), "必须点出对手是另一张补丁而非 base: $msg")
        assertTrue(msg.contains("重新导入"), "必须给出出路（并集重导入）: $msg")
        assertFalse(msg.contains("与 base 已有函数重名"), "不得误报成撞 base: $msg")
    }

    @Test
    fun `补丁撞补丁不同名——窗口对齐闸拦住第二张`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        for ((file, fn) in listOf("a$PATCH_EXTENSION" to "P_ONE", "b$PATCH_EXTENSION" to "P_TWO")) {
            val b = patchBuilder(base.program)
            val idx = b.declareFunction(fn, b.here(), arity = 0, slotCount = 1)
            b.emit(Opcode._PRINT_LITERAL, b.string(fn), 1, 0, 301)
            b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 302)
            writePatch(modsDir, file, base.program, sha, b.build(idx))
        }

        val result = ModPatchLoader.load(modsDir, base.program, sha)

        assertTrue(result.rolledBack, "第二张接不上窗口 ⇒ 整轮回退")
        val msg = result.failures.single()
        assertFalse(msg.contains("重名"), "不同名不该走重名闸: $msg")
        assertTrue(
            msg.contains("codeBase") || msg.contains("窗口"),
            "失败原因必须是窗口对齐闸（据此确认多张形态结构性不可行）: $msg",
        )
    }

    @Test
    fun `非补丁候选被跳过不报红`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        Files.writeString(modsDir.resolve("readme.txt"), "不是补丁")
        Files.writeString(modsDir.resolve("source.ERB"), "@FOO\nPRINTL X")
        Files.writeString(modsDir.resolve(".nomedia"), "")
        writePatch(modsDir, "kojo$PATCH_EXTENSION", base.program, sha, compilePatch(base.program))

        val result = ModPatchLoader.load(modsDir, base.program, sha)

        assertFalse(result.rolledBack, "非候选文件不许报红: ${result.failures}")
        assertEquals(1, result.loadedPatches.size, "只装载补丁候选")
    }

    @Test
    fun `告警 补丁函数缺形参名时点名函数`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        val b = patchBuilder(base.program)
        val idx = b.declareFunction("PATCH_NOARITYINFO", b.here(), arity = 1, slotCount = 2)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 401)
        writePatch(modsDir, "kojo$PATCH_EXTENSION", base.program, sha, b.build(idx))

        val result = ModPatchLoader.load(modsDir, base.program, sha)

        assertFalse(result.rolledBack, "形参名缺失是告警不是失败: ${result.failures}")
        assertEquals(1, result.warnings.size)
        assertTrue(
            result.warnings[0].contains("PATCH_NOARITYINFO") && result.warnings[0].contains("kojo"),
            "告警必须带补丁名与函数名: ${result.warnings[0]}",
        )
    }

    // ------------------------------------------------------------------
    // GameSession 会话接线（端到端 mini）
    // ------------------------------------------------------------------

    private fun session(
        dir: Path,
        f: BaseFixture,
        modsDir: Path? = null,
        sha: String? = null,
    ): GameSession = GameSession(
        gameRoot = dir,
        program = f.program,
        metadata = f.metadata,
        consoleWidth = 199,
        randSeed = 20260226L,
        modsDirectory = modsDir,
        baseProgramSha256 = sha,
    )

    @Test
    fun `端到端 合法补丁装载后动态名调用命中补丁函数`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        writePatch(modsDir, "kojo$PATCH_EXTENSION", base.program, sha, compilePatch(base.program))
        val baseMaxDefIndex = base.metadata.functions.maxOf { it.defIndex }
        val baseFnCount = base.program.functions.size

        val s = session(dir, base, modsDir, sha)
        try {
            assertTrue(s.loadedPatches.isNotEmpty(), "补丁应已装载: ${s.patchLoadFailures}")
            assertFalse(s.patchRolledBack)
            assertTrue(s.patchLoadFailures.isEmpty())
            assertEquals("PATCH_0", s.loadedPatches[0].functionKeys[0])

            assertTrue(s.begin(BeginType.TITLE), "SYSTEM_TITLE 已登记，begin 应执行它")
            val lines = s.console.loggedLines()
            assertEquals("PATCH_0", lines[0], "TRYCALLFORM 必须命中补丁函数: $lines")
            assertEquals("BASE_DONE", lines[1])

            // FuncMeta 尾段：绝对下标 + 形参名 `$` 后缀分桶 + 装载序恒最后。
            val metas = s.patchFunctionMetas
            assertEquals(3, metas.size)
            assertEquals(baseFnCount, metas[0].index)
            assertEquals("PATCH_0", metas[0].key)
            assertEquals(0, metas[0].argIntCount)
            assertEquals(0, metas[0].argStrCount)
            assertEquals("PATCH_ARGS", metas[1].key)
            assertEquals(1, metas[1].argIntCount, "A 是整型形参")
            assertEquals(1, metas[1].argStrCount, "B\$ 是字符串形参")
            assertEquals("PATCH_LABELED", metas[2].key)
            for (m in metas) {
                assertTrue(
                    m.defIndex > baseMaxDefIndex,
                    "装载序恒为最后（defIndex ${m.defIndex} 必须 > base 最大 $baseMaxDefIndex）",
                )
                assertTrue(
                    m.position.startsWith("mods/"),
                    "I7：position 必须能反查补丁来源: ${m.position}",
                )
            }
        } finally {
            s.close()
        }
    }

    @Test
    fun `I1 无 mods 目录与空 mods 目录会话输出逐位一致`(@TempDir dir: Path) {
        val base = compileBase()
        val emptyMods = Files.createDirectories(dir.resolve("mods"))

        val noMods = session(dir, base)
        val emptyModsSession = session(dir, base, emptyMods, PatchArtifacts.sha256Hex(ByteArray(0)))
        try {
            assertTrue(noMods.begin(BeginType.TITLE))
            assertTrue(emptyModsSession.begin(BeginType.TITLE))
            val expected = noMods.console.loggedLines()
            assertEquals(expected, emptyModsSession.console.loggedLines(), "空 mods 目录 = 与今天逐位一致")
            assertTrue(expected.contains("BASE_DONE"), "TRYCALLFORM 静默跳过: $expected")
            assertFalse(expected.any { it.contains("PATCH_0") })
            // 读数口：两条路都是「无补丁」形态。
            for (s in listOf(noMods, emptyModsSession)) {
                assertTrue(s.loadedPatches.isEmpty())
                assertTrue(s.patchLoadFailures.isEmpty())
                assertTrue(s.patchLoadWarnings.isEmpty())
                assertFalse(s.patchRolledBack)
                assertTrue(s.patchFunctionMetas.isEmpty())
            }
        } finally {
            noMods.close()
            emptyModsSession.close()
        }
    }

    @Test
    fun `I3 负向 坏 sha 会话照常起来且整体回退`(@TempDir dir: Path) {
        val base = compileBase()
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        val realSha = PatchArtifacts.sha256Hex(ByteArray(0))
        writePatch(modsDir, "kojo$PATCH_EXTENSION", base.program, "b".repeat(64), compilePatch(base.program))

        val s = session(dir, base, modsDir, realSha)
        try {
            assertTrue(s.patchRolledBack, "I3 不符必须整体回退")
            assertEquals(1, s.patchLoadFailures.size)
            assertTrue(s.loadedPatches.isEmpty())
            assertTrue(s.patchFunctionMetas.isEmpty())
            // 会话照常：回退 = 无补丁模式，begin 正常执行 base 侧。
            assertTrue(s.begin(BeginType.TITLE))
            val lines = s.console.loggedLines()
            assertEquals(listOf("BASE_DONE"), lines, "TRYCALLFORM 未命中即跳过: $lines")
        } finally {
            s.close()
        }
    }

    @Test
    fun `I4 装载后 base 四计数不变`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        writePatch(modsDir, "kojo$PATCH_EXTENSION", base.program, sha, compilePatch(base.program))
        val beforeInstruction = base.program.instructionCount
        val beforeStatement = base.program.statementCount
        val beforeFunction = base.program.functions.size
        val beforeString = base.program.stringCount

        val s = session(dir, base, modsDir, sha)
        try {
            assertFalse(s.patchRolledBack, "装载应成功: ${s.patchLoadFailures}")
            assertEquals(beforeInstruction, base.program.instructionCount, "base 指令数零漂移")
            assertEquals(beforeStatement, base.program.statementCount, "base 语句池零漂移")
            assertEquals(beforeFunction, base.program.functions.size, "base 函数表零漂移")
            assertEquals(beforeString, base.program.stringCount, "base 字符串池零漂移")
        } finally {
            s.close()
        }
    }

    @Test
    fun `modsDirectory 非空而 sha 缺失在构造期明确拒绝`(@TempDir dir: Path) {
        val base = compileBase()
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        val e = kotlin.runCatching {
            session(dir, base, modsDir, null)
        }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "缺 sha 必须构造期明确失败: $e")
        assertTrue(
            e!!.message!!.contains("baseProgramSha256"),
            "文案要点名缺哪个参数: ${e.message}",
        )
    }

    // ------------------------------------------------------------------
    // R7-D E2 补发：PRINTDATA 正向执行面 + hasPatchExtension 直测
    // ------------------------------------------------------------------

    /** base 六计数 → [PatchCompiler.BaseCounts]（与补丁头 base-* 字段同源口径）。 */
    private fun baseCountsOf(p: Program): PatchCompiler.BaseCounts = PatchCompiler.BaseCounts(
        instructionCount = p.instructionCount,
        functionCount = p.functions.size,
        statementCount = p.statementCount,
        stringCount = p.stringCount,
        longCount = p.constantCount,
        exprCount = p.exprCount,
    )

    /**
     * PRINTDATA mini 补丁源（真编译器 [PatchCompiler] 的输入）：`PATCH_0` 调 `PATCH_DATA`，
     * 后者两条 PRINTDATAL —— 裸 `DATA` 一行与 `DATALIST` 两行。组形态逐条对齐桌面
     * `CorpusCompile.harvestDataSections` 的同源口径（裸 DATA/DATAFORM 各成一组；
     * DATALIST..ENDLIST 内每行 DATA/DATAFORM 归一组）。两组各一组数据 ⇒ 随机选组必中，
     * 执行面输出完全确定。
     */
    private val printDataPatchSource = """
        @PATCH_0
        CALL PATCH_DATA
        RETURN 0

        @PATCH_DATA
        PRINTDATAL
        DATA SOLO_HIT
        ENDDATA
        PRINTDATAL
        DATALIST
        DATAFORM AAA
        DATA BBB
        ENDLIST
        ENDDATA
        PRINTL TAIL
        RETURN 0
    """.trimIndent()

    /**
     * 用 main 上的 [PatchCompiler]（C5 导入链同款，带基线预置）编译 PRINTDATA mini 补丁。
     * 补丁源不引用 base 符号（TRYCALLFORM 是动态名、CALL 目标是补丁内函数）⇒ 裸
     * [SymbolTable] 即可（ARG/RETURN 系统变量走 BuiltinSymbols 兜底）。
     */
    private fun compilePrintDataPatch(base: BaseFixture): Program =
        PatchCompiler(
            metadata = base.metadata,
            baseCounts = baseCountsOf(base.program),
            symbolTable = SymbolTable(),
        ).compile(
            listOf(PatchCompiler.SourceFile("mods/printdata.ERB", printDataPatchSource)),
            "PATCH_0",
        ).program

    @Test
    fun `端到端 补丁 PRINTDATA 数据节追加读数与执行面输出`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        val patch = compilePrintDataPatch(base)
        writePatch(modsDir, "data$PATCH_EXTENSION", base.program, sha, patch)

        // ---- 装载面：dataSections 追加读数（键 = PRINTDATA 语句的绝对语句池下标）----
        val result = ModPatchLoader.load(modsDir, base.program, sha)
        assertFalse(result.rolledBack, "合法补丁应装载成功: ${result.failures}")
        assertTrue(result.failures.isEmpty())
        // overlay 后语句池的补丁段里，PRINTDATA 实例的下标 = 数据节键
        // （ModPatchLoader.harvestDataSections 从 statementBase 起扫；键域与 base 侧车同域）。
        val baseStmtCount = base.program.statementCount
        val expectedKeys = ArrayList<Int>()
        for (i in baseStmtCount until result.program.statementCount) {
            if (result.program.statement(i) is PrintDataStmt) expectedKeys.add(i)
        }
        assertEquals(
            2, expectedKeys.size,
            "夹具必须带两条 PRINTDATA（都在补丁窗口 [baseStmtCount=$baseStmtCount, statementCount) 内）",
        )
        assertEquals(
            expectedKeys, result.dataSections.keys.toList(),
            "键 = PRINTDATA 语句的绝对池下标（升序 = 收割扫描序）",
        )
        // 组形态（CorpusCompile.harvestDataSections 同源口径：裸 DATA 各成一组、
        // DATALIST 内每行归一组；DATA/DATAFORM 的 STR/FORM_STR 参数都折 StrLiteral，
        // ArgBuilders.kt:287/:323 ⇒ dataExpr 的表达式参数分支产出 StrLiteral）。
        val solo = result.dataSections.getValue(expectedKeys[0])
        assertEquals(
            listOf(listOf<Expr>(StrLiteral("SOLO_HIT"))), solo,
            "裸 DATA 一行自成一组",
        )
        val listed = result.dataSections.getValue(expectedKeys[1])
        assertEquals(
            listOf(listOf(StrLiteral("AAA"), StrLiteral("BBB"))), listed,
            "DATALIST 两行归一组（DATAFORM 纯文本折 StrLiteral）",
        )

        // ---- 执行面：overlay 后的 effectiveProgram 跑到该语句，输出走到控制台 sink ----
        // 会话装配（GameSession :882/:889）把补丁数据节合并进 sidecar 后注入
        // ProcessRuntime.DataSectionSource；execPrintDataFlat 按语句池下标直读。
        val s = session(dir, base, modsDir, sha)
        try {
            assertTrue(s.loadedPatches.isNotEmpty(), "补丁应已装载: ${s.patchLoadFailures}")
            assertTrue(s.begin(BeginType.TITLE))
            assertEquals(
                listOf("SOLO_HIT", "AAA", "BBB", "TAIL", "BASE_DONE"),
                s.console.loggedLines(),
                "裸组输出 → DATALIST 组内换行 → PRINTDATAL 末尾换行 → 尾行（TRYCALLFORM 命中补丁链）",
            )
        } finally {
            s.close()
        }
    }

    // R7-E1：补丁私有 #DIM 载体（端到端 + 兼容负向）
    // ------------------------------------------------------------------

    /**
     * 出口判据①（CONTRACT-R7-D §4/E1）：补丁声明私有 `#DIM`（标量 + 数组各一）→
     * [PatchCompiler] 编译出 `PatchResult.privateDims` → [PatchCompiler.headerOf] 写进
     * 补丁头可选段 `private-dims` → 落盘 → [ModPatchLoader.load] 抽取 → [GameSession]
     * 装配第 5 步的 `allPrivateDims` 追加注册 → 补丁函数内的变量读写生效（PRINTFORML
     * 把读值打进控制台断言）。
     *
     * 私有变量的声明原文必须与函数体引用逐字同键（`PATCH_0` 的函数键,大写归一）——
     * 会话装配第 5 步只按头的声明注册,不解析补丁源;写错名 = 运行期「未定义变量」。
     */
    @Test
    fun `端到端 补丁私有 DIM 标量与数组经可选段注册并读写生效`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        val baseCounts = PatchCompiler.BaseCounts(
            instructionCount = base.program.instructionCount,
            functionCount = base.program.functions.size,
            statementCount = base.program.statementCount,
            stringCount = base.program.stringCount,
            longCount = base.program.constantCount,
            exprCount = base.program.exprCount,
        )
        val patchSource = """
            @PATCH_0
            #DIM PCOUNT
            #DIM PTBL, 3
            PCOUNT = 7
            PTBL:1 = 42
            PRINTFORML P={PCOUNT} T={PTBL:1}
            RETURN 0
        """.trimIndent() + "\n"

        // ① 编译：私有 #DIM 的解析结果进 PatchResult.privateDims（C1 的追加面）。
        val result = PatchCompiler(
            base.metadata,
            baseCounts,
            SymbolTable(),   // 私有名由编译器预扫解析（PatchSourceResolver）,无需预声明
        ).compile(
            listOf(PatchCompiler.SourceFile("mods/kojo.ERB", patchSource)),
            "PATCH_0",
        )
        assertEquals(2, result.privateDims.size, "标量 + 数组各一条: ${result.privateDims}")
        assertTrue(
            result.privateDims.all { it.functionKey == "PATCH_0" },
            "函数键 = 补丁函数键: ${result.privateDims.map { it.functionKey }}",
        )

        // ② 写头（R7-E1:privateDims 进可选段）→ 补丁产物落盘（装载路吃落盘字节）。
        val header = PatchCompiler.headerOf(result, baseCounts, sha, entryFunction = "PATCH_0")
        Files.write(
            modsDir.resolve("kojo$PATCH_EXTENSION"),
            PatchArtifacts.of(header, ErbcWriter.encode(result.program)).write(),
        )

        // ③ 装载：ModPatchLoader 抽取可选段 → Result.privateDims。
        val loaded = ModPatchLoader.load(modsDir, base.program, sha)
        assertFalse(loaded.rolledBack, "装载应成功: ${loaded.failures}")
        assertEquals(2, loaded.privateDims.size, "装载链必须抽出头里的私有 #DIM")
        assertEquals("PATCH_0", loaded.privateDims[0].functionKey)

        // ④ 会话：allPrivateDims 追加注册生效 → 补丁函数内的读写经 PRINTFORML 走到控制台。
        val s = session(dir, base, modsDir, sha)
        try {
            assertFalse(s.patchRolledBack, "会话装配不应回退: ${s.patchLoadFailures}")
            assertTrue(s.begin(BeginType.TITLE), "SYSTEM_TITLE 已登记，begin 应执行它")
            val lines = s.console.loggedLines()
            assertEquals(
                "P=7 T=42", lines[0],
                "私有标量写 7、数组 :1 写 42 再读出,必须经 allPrivateDims 注册生效: $lines",
            )
            assertEquals("BASE_DONE", lines[1])
        } finally {
            s.close()
        }
    }

    /**
     * 端到端（4.981 浮点族）：补丁函数的 `#FUNCTIONF` / `#LOCALFSIZE` / `#LOCALSIZE`
     * 声明经 `PatchCompiler` → 头可选段 `function-metas` → 落盘 → [ModPatchLoader] 收割,
     * 元数据逐字段到位;无声明的普通函数不进段（稀疏形态）。
     *
     * 消费面（`GameSession.buildPatchFunctionMetas` 按本表覆盖缺省元数据）属 core/proc
     * 接线,不在本文件面 —— 这里锁的是「落盘 → 装载」这两环不断链。
     */
    @Test
    fun `端到端 补丁函数元数据经可选段收割`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        val baseCounts = PatchCompiler.BaseCounts(
            instructionCount = base.program.instructionCount,
            functionCount = base.program.functions.size,
            statementCount = base.program.statementCount,
            stringCount = base.program.stringCount,
            longCount = base.program.constantCount,
            exprCount = base.program.exprCount,
        )
        val patchSource = """
            @PATCH_0
            #LOCALSIZE 100
            PRINTL M_DONE
            RETURN 0

            @PATCH_FN
            #FUNCTIONF
            #LOCALFSIZE 16
            RETURNF 1.25

            @PATCH_PLAIN
            PRINTL PLAIN_DONE
            RETURN 0
        """.trimIndent() + "\n"
        val result = PatchCompiler(
            base.metadata,
            baseCounts,
            SymbolTable(),
        ).compile(
            listOf(PatchCompiler.SourceFile("mods/kojo.ERB", patchSource)),
            "PATCH_0",
        )
        // 编译面：三个函数的 FuncMeta 里,非缺省的是 PATCH_0（localSize）与 PATCH_FN
        // （isMethod + returnsFloat + floatLocalSize）;PATCH_PLAIN 六项全缺省。
        assertEquals(3, result.funcMetas.size)

        // 写头 + 落盘 + 装载。
        val header = PatchCompiler.headerOf(result, baseCounts, sha, entryFunction = "PATCH_0")
        Files.write(
            modsDir.resolve("kojo$PATCH_EXTENSION"),
            PatchArtifacts.of(header, ErbcWriter.encode(result.program)).write(),
        )
        val loaded = ModPatchLoader.load(modsDir, base.program, sha)
        assertFalse(loaded.rolledBack, "装载应成功: ${loaded.failures}")
        assertEquals(setOf(0, 1), loaded.functionMetas.keys, "只收非缺省函数（PATCH_PLAIN 不进段）")
        val p0 = loaded.functionMetas.getValue(0)
        assertEquals("PATCH_0", p0.name)
        assertFalse(p0.isMethod, "@label 普通函数不带方法位")
        assertEquals(100, p0.localSize, "#LOCALSIZE 必须随产物存活")
        assertEquals(0, p0.floatLocalSize)
        val p1 = loaded.functionMetas.getValue(1)
        assertEquals("PATCH_FN", p1.name)
        assertTrue(p1.isMethod, "#FUNCTIONF 是方法")
        assertTrue(p1.returnsFloat, "#FUNCTIONF 的浮点返回位必须随产物存活（运行期选 _RETF 通道）")
        assertFalse(p1.returnsStr)
        assertEquals(16, p1.floatLocalSize, "#LOCALFSIZE 必须随产物存活（否则退默认 10）")
    }

    /** 出口判据（I1 对侧）：旧头（无 function-metas 段）装载 = 元数据表恒空、零行为差。 */
    @Test
    fun `兼容负向 旧头装载 function-metas 恒空`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        // writePatch 的缺省形 = 旧 15 字段头（不带 function-metas 段）。
        writePatch(modsDir, "kojo$PATCH_EXTENSION", base.program, sha, compilePatch(base.program))
        val result = ModPatchLoader.load(modsDir, base.program, sha)
        assertFalse(result.rolledBack, "旧头应正常装载: ${result.failures}")
        assertTrue(
            result.functionMetas.isEmpty(),
            "旧头（无 function-metas 段）读回必须恒空（装载侧按缺省重建,追加零行为差）",
        )
    }

    /** I6 负向：function-metas 与补丁体对不上号（手改头）= 产物损坏,整体回退并点名。 */
    @Test
    fun `负向 function-metas 与补丁体不符整体回退`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        val patchProgram = compilePatch(base.program)
        writePatch(
            modsDir,
            "kojo$PATCH_EXTENSION",
            base.program,
            sha,
            patchProgram,
        ) { h ->
            PatchHeader(
                baseInstructionCount = h.baseInstructionCount,
                baseFunctionCount = h.baseFunctionCount,
                baseStatementCount = h.baseStatementCount,
                baseStringCount = h.baseStringCount,
                baseLongCount = h.baseLongCount,
                baseExprCount = h.baseExprCount,
                baseSha256 = h.baseSha256,
                compiler = h.compiler,
                patchFunctionCount = h.patchFunctionCount,
                patchStatementCount = h.patchStatementCount,
                patchStringCount = h.patchStringCount,
                patchLongCount = h.patchLongCount,
                patchExprCount = h.patchExprCount,
                entryFunction = h.entryFunction,
                privateDims = h.privateDims,
                functionMetas = listOf(
                    // offset 0 在补丁函数表内,但名字与补丁体（PATCH_0）不符。
                    FunctionMetaRecord(
                        offset = 0, name = "NOT_IN_PATCH", isMethod = true,
                        returnsStr = false, returnsFloat = false,
                        floatLocalSize = 0, localSize = 0, localsSize = 0,
                    ),
                ),
                formatVersion = h.formatVersion,
            )
        }
        val result = ModPatchLoader.load(modsDir, base.program, sha)
        assertTrue(result.rolledBack, "头与补丁体对不上号必须整体回退")
        assertTrue(
            result.failures.single().contains("function-metas"),
            "失败清单必须点名可选段与两把钥匙: ${result.failures}",
        )
        assertTrue(
            result.failures.single().contains("NOT_IN_PATCH"),
            "失败清单必须点名头写的函数名: ${result.failures}",
        )
        assertTrue(result.functionMetas.isEmpty(), "回退时元数据表必须清空（I5 契约）")
    }

    @Test
    fun `hasPatchExtension 大小写变体与长度边界钉死权威口径`() {
        // 大小写不敏感：逐字符 ASCII 折叠（ModPatchLoader.hasPatchExtension），locale 无关。
        assertTrue(ModPatchLoader.hasPatchExtension("kojo.patch"), "小写基准")
        assertTrue(ModPatchLoader.hasPatchExtension("KOJO.PATCH"), "全大写变体")
        assertTrue(ModPatchLoader.hasPatchExtension("Kojo.Patch"), "混合大小写变体")
        assertTrue(ModPatchLoader.hasPatchExtension("a.patch"), "主名非空的最短合法形态")
        // 长度边界：恰为扩展名长度 = 主名空（`.patch` 本身算隐藏文件，扫描口径先行拦掉，
        // KDoc「主名非空」的下界兜底形态）。
        assertFalse(ModPatchLoader.hasPatchExtension(".patch"), "恰为扩展名长度 ⇒ 无主名")
        assertFalse(ModPatchLoader.hasPatchExtension("patch"), "短于扩展名长度")
        // 判定必须是后缀而不是包含（窗口对齐最后 PATCH_EXTENSION.length 个字符）。
        assertFalse(ModPatchLoader.hasPatchExtension("kojo.patchx"), "扩展名不在尾部")
        assertFalse(ModPatchLoader.hasPatchExtension("patch.txt"), "别的扩展名")
    }

    /**
     * 出口判据②（CONTRACT-R7-D §4/E1）：旧 15 字段补丁头（无 `private-dims` 段）装载 =
     * 私有 #DIM 恒空、零行为差（写侧「空表整段省略」的对侧）。既有 16 例不动,这里显式钉。
     */
    @Test
    fun `兼容负向 旧 15 字段头装载私有 DIM 恒空`(@TempDir dir: Path) {
        val base = compileBase()
        val sha = PatchArtifacts.sha256Hex(ByteArray(0))
        val modsDir = Files.createDirectories(dir.resolve("mods"))
        // writePatch 的缺省形 = 旧 15 字段头（不带 private-dims 段）。
        writePatch(modsDir, "kojo$PATCH_EXTENSION", base.program, sha, compilePatch(base.program))

        val result = ModPatchLoader.load(modsDir, base.program, sha)
        assertFalse(result.rolledBack, "旧头应正常装载: ${result.failures}")
        assertEquals(1, result.loadedPatches.size)
        assertTrue(
            result.privateDims.isEmpty(),
            "旧头（无 private-dims 段）读回必须恒空（追加零行为差）",
        )
    }
}