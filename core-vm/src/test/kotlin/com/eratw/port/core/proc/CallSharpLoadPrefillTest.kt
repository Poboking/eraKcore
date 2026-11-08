package com.eratw.port.core.proc

import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.NativeParam
import com.eratw.port.core.fn.nativeMethods
import com.eratw.port.core.fn.prefillSharpLinks
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CSHARP-WIRE C2/C4：CALLSHARP 的**装载趟预填**（`prefillSharpLinks`，core/fn）。
 *
 * 原版相位：联结期 `CALLSHARP_Instruction.SetJumpTo`（FunctionIdentifier.cs:1193-1210）对常量名
 * 查 `PluginManager.HasMethod`，未命中 `:1204` Warn(isError:true) 记行错、命中绑死 CallFunc
 * （:1208），warningList 装载完统一上屏（ParserMediator.cs:143-158）—— **该行不执行也见警告**。
 * 移植侧预填 = 装载趟把同一裁决落进 SharpLinkTable（flat 键 = 语句池下标、对象树键 = 实参对象
 * 身份），宿主装配段在 `wireCorpusNativeMethods` 之后、`drive()` 之前跑（GameHost 装配段）。
 *
 * 本钉四组：
 * 1. source 编译路（eager 池，flat 通道）命中/未命中的预填语义；
 * 2. erbc 装载路（惰性池，对象树通道）的预填语义 —— 关键是**预填物化的实例 = 运行期同一实例**
 *    （惰性池缓存身份契约，ErbcStream 的 statementAt），否则对象树键的 IdentityHashMap 不命中、
 *    预填等于白做；顺带钉「标签字节扫不整池物化」（真机惰性装载的红线）；
 * 3. erbc 惰性池 + flat 通道（分片扁平源）的预填语义；
 * 4. 幂等/不重查：已裁决行重复预填不二次裁决（注册表后长出同名也不复活）。
 *
 * 首执行裁决的兜底语义（不跑预填的会话）由 `CallSharpLinkPhaseTest` 继续钉住；两套语义写
 * 同一张表，本文件不重复那五例。
 */
class CallSharpLoadPrefillTest {

    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "prefill.erb", i + 1, text))
        }.filterNotNull()
    }

    /** 编译一个带若干前置 PRINTL 的 CALLSHARP 探针函数（前置行用于垫高语句池、钉「不整池物化」）。 */
    private fun compile(vararg lines: String): Program {
        val c = ErbProgramCompiler()
        val body = buildList {
            repeat(6) { add("PRINTL filler$it") }
            addAll(lines)
        }
        c.compileFunction(
            FuncSig("SHARP_PREFILL_PROBE", 0),
            parse("@SHARP_PREFILL_PROBE", *body.toTypedArray()),
            setOf(),
            ExprCallTable(),
        )
        return c.build("SHARP_PREFILL_PROBE")
    }

    private fun runtimeWith(program: Program, store: VariableStore): ProcessRuntime =
        ProcessRuntime(
            program,
            store,
            FunctionTable(emptyList()),
            VmConfig(maxStepsPerRun = 100_000),
            consoleSink = LineBufferConsoleSink(clientWidth = 136, printCWidth = 25, maxLines = 900),
        )

    /** 对象树通道（Android 生产默认形态）：关掉 flat 直读（Program 的 internal 开关，同模块测试可及）。 */
    private fun asTreeChannel(program: Program): Program {
        program.flatStatementsEnabled = false
        return program
    }

    /** erbc 装载路：编译产物落盘 → 惰性池装载（[flat] = 扁平通道开关）。 */
    private fun loadAsErbc(dir: Path, program: Program, flat: Boolean): Program {
        val f = dir.resolve("sharp-prefill.erbc")
        Files.write(f, ErbcWriter.encode(program))
        return ProgramLoader.loadMapped(f, flatStatements = flat)
    }

    // -----------------------------------------------------------------
    // 1. source 编译路（eager 池，flat 通道）
    // -----------------------------------------------------------------

    @Test
    fun `source 路径预填命中 绑死后清空注册表该行照常执行`() {
        val store = VariableStore()
        val calls = mutableListOf<Int>()
        nativeMethods(store.aux).register("Ghost") { _: Array<NativeParam> -> calls.add(calls.size) }
        val program = compile("CALLSHARP Ghost(1)")
        assertEquals(
            emptyList(),
            prefillSharpLinks(store.aux, program),
            "命中 = 无装载警告（原版联结期查表命中也不告警，FunctionIdentifier.cs:1206-1209）",
        )
        val rt = runtimeWith(program, store)
        // 预填已把方法本体绑进 SharpLinkTable；此刻清空注册表 —— 首执行若走「现查」必炸，
        // 走预填记录值才通得过（= 原版 CallFunc 联结期绑死，PluginManager.ClearMethods 不拆线）。
        nativeMethods(store.aux).clear()
        rt.execute()
        assertEquals(listOf(0), calls, "首执行消费装载趟的预填结果，不再查注册表")
    }

    @Test
    fun `source 路径预填未命中 返回装载警告且首执行抛记录值`() {
        val store = VariableStore()
        val program = compile("CALLSHARP Ghost(1)")
        val warnings = prefillSharpLinks(store.aux, program)
        assertEquals(
            listOf("No native method Ghost found"),
            warnings,
            "未命中在装载趟就收进警告（FunctionIdentifier.cs:1204 文案原样，ParserMediator.cs:143-158 相位）",
        )
        val rt = runtimeWith(program, store)
        val first = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals("No native method Ghost found", first.message, "首执行抛预填记录值（Process.cs:1017-1019 相位）")
        // 注册表此刻才长出同名：行级错误已预填绑死 —— 原版 IsError 行不因后注册复活。
        nativeMethods(store.aux).register("Ghost") { }
        assertEquals(first.message, assertFailsWith<CodeEE> { rt.execute() }.message, "重执行照记录值重抛，不重查")
    }

    // -----------------------------------------------------------------
    // 2. erbc 装载路（惰性池，对象树通道）：预填物化 = 运行期同一实例
    // -----------------------------------------------------------------

    @Test
    fun `erbc 惰性池预填未命中 只物化候选行且树通道抛记录值`(@TempDir dir: Path) {
        val store = VariableStore()
        val program = loadAsErbc(dir, compile("CALLSHARP Ghost(1)"), flat = false)
        assertTrue(program.holdsLazyStatementPool, "前置：本用例钉的就是惰性池装载路")
        val warnings = prefillSharpLinks(store.aux, program)
        assertEquals(listOf("No native method Ghost found"), warnings, "装载趟警告（记录标签字节扫 → 候选物化 → 裁决）")
        assertEquals(
            1,
            program.materializedStatementCount,
            "扫描只物化 CALLSHARP 候选行（7 条语句的池不整池物化 —— 真机惰性装载的红线）",
        )
        val rt = runtimeWith(program, store)
        val first = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals("No native method Ghost found", first.message)
        assertTrue("filler0" in rt.output.toString(), "前置 PRINTL 已生效：错误只属于 CALLSHARP 那一行")
        nativeMethods(store.aux).register("Ghost") { }
        assertEquals(first.message, assertFailsWith<CodeEE> { rt.execute() }.message, "树通道同样抛预填记录值")
    }

    @Test
    fun `erbc 惰性池预填命中 清空注册表后树通道照常执行`(@TempDir dir: Path) {
        val store = VariableStore()
        val calls = mutableListOf<Int>()
        nativeMethods(store.aux).register("Ghost") { _: Array<NativeParam> -> calls.add(calls.size) }
        val program = loadAsErbc(dir, compile("CALLSHARP Ghost(1)"), flat = false)
        assertEquals(emptyList(), prefillSharpLinks(store.aux, program))
        val rt = runtimeWith(program, store)
        nativeMethods(store.aux).clear()
        rt.execute()
        assertEquals(listOf(0), calls, "预填物化的实例与运行期同身份 ⇒ 对象树键的联结记录命中")
    }

    // -----------------------------------------------------------------
    // 3. erbc 装载路（惰性池 + 分片扁平源，flat 通道）
    // -----------------------------------------------------------------

    @Test
    fun `erbc 惰性池开扁平通道 预填命中后清空注册表照常执行`(@TempDir dir: Path) {
        val store = VariableStore()
        val calls = mutableListOf<Int>()
        nativeMethods(store.aux).register("Ghost") { _: Array<NativeParam> -> calls.add(calls.size) }
        val program = loadAsErbc(dir, compile("CALLSHARP Ghost(1)"), flat = true)
        assertEquals(emptyList(), prefillSharpLinks(store.aux, program))
        val rt = runtimeWith(program, store)
        nativeMethods(store.aux).clear()
        rt.execute()
        assertEquals(listOf(0), calls, "flat 通道行键 = 语句池下标，与预填绑定键同源")
    }

    // -----------------------------------------------------------------
    // 4. 幂等 / 不重查
    // -----------------------------------------------------------------

    @Test
    fun `预填幂等 已裁决行不二次裁决 注册表后长同名也不复活`() {
        val store = VariableStore()
        val program = compile("CALLSHARP Ghost(1)")
        assertEquals(
            listOf("No native method Ghost found"),
            prefillSharpLinks(store.aux, program),
            "第一次预填：未命中收警告",
        )
        nativeMethods(store.aux).register("Ghost") { }
        assertEquals(
            emptyList(),
            prefillSharpLinks(store.aux, program),
            "第二次预填：已裁决行跳过，不因注册表变了重查（= 原版联结期绑死）也不重发警告",
        )
        val rt = runtimeWith(program, store)
        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals("No native method Ghost found", e.message, "执行期照预填记录值走")
    }
}
