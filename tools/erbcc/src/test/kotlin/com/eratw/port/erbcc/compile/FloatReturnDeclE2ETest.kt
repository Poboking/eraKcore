package com.eratw.port.erbcc.compile

import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.expr.Value
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `#FUNCTIONF`（4.981 fork 的浮点返回方法）的**声明面全链**（路次 g7-returnsfloat）。
 *
 * 链路：自建 ERB 声明 → `CorpusCompile.compile`（CorpusScan 识别 FLOAT →
 * `FuncSig.returnsFloat` 编 `_RETF` → `FuncMeta.returnsFloat`）→ 侧车 `encodeText/parse`
 * 往返 → `.erbc` 容器往返 → 装载后调用**返回浮点**。
 *
 * 断言值取 1.5（非整值）：0.0 这类默认值无法区分「真读到浮点返回」与「返回通道断了」。
 *
 * fork 证据：`LogicalLineParser.cs:141-183`（`#FUNCTIONF` → `EraType.Float`），
 * 返回载体与 `_RETF` 的语义见 ReturnfFloatTest 的类 KDoc。
 */
class FloatReturnDeclE2ETest {

    @Test
    fun `FUNCTIONF声明经编译侧车往返后装载调用返回1点5`(@TempDir dir: Path) {
        dir.resolve("MAIN.ERB").writeText(
            """
            @MAIN
            RESULTF = FF()
            @FF
            #FUNCTIONF
            RETURNF 1.5
            """.trimIndent(),
        )

        // 1) 编译：声明面（CorpusScan）→ 字节码（_RETF）→ 侧车（returnsFloat）
        val result = CorpusCompile.compile(dir)
        assertTrue(result.failures.isEmpty(), "编译失败: ${result.failures.joinToString { it.message }}")
        val program = assertNotNull(result.program, "编译产物为空")
        assertTrue(
            (0 until program.instructionCount).count { program.instructionAt(it).op == Opcode._RETF } >= 1,
            "浮点方法必须编出 `_RETF`（声明面 returnsFloat 未传到编译器）",
        )

        val ffMeta = result.bootMetadata.functions.single { it.key == "FF" }
        assertTrue(ffMeta.isMethod, "#FUNCTIONF 必须置方法位")
        assertTrue(ffMeta.returnsFloat, "侧车模型必须带浮点返回位")
        assertTrue(!ffMeta.returnsStr, "浮点为第三种 MethodType，不得误置字符串位")

        val text = result.bootMetadata.encodeText()
        assertTrue(
            Regex("""function \d+ \d+ "FF" "FF" 2 0 0 0 0 0 1 "[^"]*"""").containsMatchIn(text),
            "function 行必须带成对可选原子（floatLocalSize=0, returnsFloat=1）:\n$text",
        )

        // 2) 侧车往返 + `.erbc` 容器往返：装载面拿到的仍是浮点声明
        val loaded = BootMetadata.parse(text)
        val loadedFf = loaded.functions.single { it.key == "FF" }
        assertTrue(loadedFf.returnsFloat, "侧车读回丢了 returnsFloat")
        val roundTrip: Program = ErbcReader.decode(ErbcWriter.encode(program))

        // 3) 装载后调用：`RESULTF = FF()` 走运行期重入通道（帧槽无浮点列、不降级），
        //    返回值必须是 1.5；invokeUserMethod 通道同值。
        val store = VariableStore().apply { addPseudoCharacter() }
        val rt = ProcessRuntime(roundTrip, store, FunctionTable(loaded.functions), VmConfig())
        rt.execute()
        assertEquals(1.5, store.readFloat("RESULTF", LongArray(0)), "浮点方法的表达式位调用必须把 1.5 写进 RESULTF")
        assertEquals(0L, store.readInt("RESULT", longArrayOf(0)), "整数落点 RESULT 不得被浮点方法写入")

        val direct = rt.invokeUserMethod("FF", emptyList())
        assertTrue(direct is Value.FloatV, "装载后面调用的返回类型必须是 FloatV，实际=$direct")
        assertEquals(1.5, (direct as Value.FloatV).v, "非整值断言：0.0 无法证明通道存活")
    }
}
