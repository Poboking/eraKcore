package com.eratw.port.core.proc

import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ModPatchLoader
import com.eratw.port.core.patch.PatchArtifacts
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 981a-r2 波（任务二）：`GameSession.buildPatchFunctionMetas` 对头可选段
 * `function-metas` 的**消费侧**接线 —— 六项（isMethod/returnsStr/returnsFloat/
 * floatLocalSize/localSize/localsSize）按补丁窗口内 offset 取记录，缺记录走缺省。
 *
 * 接线前补丁 `#FUNCTIONF` 返回恒 0、`#FUNCTIONS` 恒空串、`#LOCALFSIZE` 退默认 10 ——
 * 三个静默错值形态的根源就是重建 FuncMeta 时这六项恒缺省（写入侧
 * `PatchCompiler.headerOf` → `function-metas` 段与装载侧 `ModPatchLoader`
 * harvestFunctionMetas 已由 [com.eratw.port.core.loader.ModPatchLoaderTest] 与
 * [com.eratw.port.core.compile.PatchFloatFaceTest] 钉住，本条锁最后一环：
 * 装载结果 → 会话 FuncMeta）。
 *
 * 夹具自足：base = 单函数 `@SYSTEM_TITLE`（不执行，只读装配后的
 * `patchFunctionMetas`）；补丁 = `#LOCALSIZE` 普通函数 + `#FUNCTIONF`/`#LOCALFSIZE`
 * 方法 + 六项全缺省的普通函数各一，走 PatchCompiler → headerOf → 落盘 → 会话装配。
 */
class PatchFunctionMetasWireTest {

    private class BaseFixture(val program: com.eratw.port.core.code.Program, val metadata: BootMetadata)

    private fun parse(vararg lines: String): List<com.eratw.port.core.stmt.Statement> {
        val p = LineParser(SymbolTable())
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

    private fun compileBase(): BaseFixture {
        val compiler = ErbProgramCompiler()
        val stmts = parse(
            "@SYSTEM_TITLE",
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
            privateDims = emptyList(),
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

    @Test
    fun `六项元数据按 offset 消费 缺记录走缺省`(@TempDir dir: Path) {
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
            #LOCALSSIZE 20
            PRINTL M_DONE
            RETURN 0

            @PATCH_FN
            #FUNCTIONF
            #LOCALFSIZE 16
            RETURNF 1.25

            @PATCH_FN_S
            #FUNCTIONS
            RETURNF ""

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
        // 写头（function-metas 段随 headerOf 落盘）+ 落盘 + 会话装配。
        val header = PatchCompiler.headerOf(result, baseCounts, sha, entryFunction = "PATCH_0")
        Files.write(
            modsDir.resolve("kojo${ModPatchLoader.PATCH_EXTENSION}"),
            PatchArtifacts.of(header, ErbcWriter.encode(result.program)).write(),
        )

        val s = GameSession(
            gameRoot = dir,
            program = base.program,
            metadata = base.metadata,
            consoleWidth = 199,
            randSeed = 20260226L,
            modsDirectory = modsDir,
            baseProgramSha256 = sha,
        )
        try {
            assertFalse(s.patchRolledBack, "会话装配不应回退: ${s.patchLoadFailures}")
            val metas = s.patchFunctionMetas.associateBy { it.key }
            assertEquals(
                setOf("PATCH_0", "PATCH_FN", "PATCH_FN_S", "PATCH_PLAIN"),
                metas.keys,
            )

            // #LOCALSIZE/#LOCALSSIZE 声明经消费侧到位（接线前恒 0 ⇒ 局部帧退注册表默认）。
            val p0 = metas.getValue("PATCH_0")
            assertEquals(100, p0.localSize, "#LOCALSIZE 经会话接线到位")
            assertEquals(20, p0.localsSize, "#LOCALSSIZE 经会话接线到位")
            assertFalse(p0.isMethod)

            // #FUNCTIONF：方法位 + 浮点返回位 + 浮点局部帧长（接线前三态恒缺省
            // ⇒ _RETF 通道选不上、返回恒 0、LOCALF 帧退默认 10）。
            val pfn = metas.getValue("PATCH_FN")
            assertTrue(pfn.isMethod, "#FUNCTIONF 方法位经会话接线到位")
            assertTrue(pfn.returnsFloat, "#FUNCTIONF 浮点返回位经会话接线到位")
            assertFalse(pfn.returnsStr)
            assertEquals(16, pfn.floatLocalSize, "#LOCALFSIZE 经会话接线到位（否则退默认 10）")
            assertEquals(0, pfn.localSize)

            // #FUNCTIONS：方法位 + 字符串返回位（接线前恒缺省 ⇒ 返回恒空串）。
            val pfns = metas.getValue("PATCH_FN_S")
            assertTrue(pfns.isMethod, "#FUNCTIONS 方法位经会话接线到位")
            assertTrue(pfns.returnsStr, "#FUNCTIONS 字符串返回位经会话接线到位")
            assertFalse(pfns.returnsFloat)

            // 六项全缺省的函数不进段 ⇒ 消费侧全部走缺省（与接线前逐位一致）。
            val pplain = metas.getValue("PATCH_PLAIN")
            assertFalse(pplain.isMethod)
            assertFalse(pplain.returnsStr)
            assertFalse(pplain.returnsFloat)
            assertEquals(0, pplain.floatLocalSize)
            assertEquals(0, pplain.localSize)
            assertEquals(0, pplain.localsSize)
        } finally {
            s.close()
        }
    }
}
