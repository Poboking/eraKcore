package com.eratw.port.erbcc.compile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * CMP-40 布局面必红夹具：`#DIM SORT_VALUE, VARSIZE("RELATION")` 的尺寸必须在
 * **生产编译的** [com.eratw.port.core.compile.DimSpecLayout] 里折成 200。
 *
 * 语料样本 = `fromEN/QOL/QoL_ShowRelation.ERB:3/:4`（`#DIM SORT_VALUE, VARSIZE("RELATION")`），
 * 尺寸表 = `CSV/VariableSize.csv:104 RELATION,200`。合成语料用同一份 CSV 值，且 200 与出厂
 * 100 不同 —— 折成默认值时本用例会抓到。
 *
 * 时点依据（`VARSIZE` 的装载期折叠）：
 * - `Process.cs:292-293` 先装 `VariableSize.csv` → `:300-301` 建 IdentifierDictionary
 *   → `:309/:313` 才装 ERH，所以 `VARSIZE("RELATION")` 在 `#DIM` 声明点必须读出 200；
 * - `FunctionMethodCreator.cs:3437-3438` 注册 CanRestructure + HasUniqueRestructure，
 *   `:3441-3454` 的 `GetIntValue` 走 `GetVariableToken(...).GetLength(num)`。
 *
 * 修复前 `CorpusCompile` 的 `buildFuncDims(allDefs, consts)` **没传折叠器** ⇒ 该尺寸折不出
 * ⇒ 整条私有声明在布局面被丢（`DimSpecLayout.beginFunction` 返回 0 个局部槽）。侧车/运行期
 * 两面早已折出 200（`DimFoldParityTest` 钉着），本用例补的是**生产布局**这一面。
 */
class DimLayoutFoldWireTest {

    @TempDir
    lateinit var dir: Path

    private fun writeCorpus() {
        Files.createDirectories(dir.resolve("ERB"))
        Files.createDirectories(dir.resolve("CSV"))
        // `RELATION,200`：与语料 `VariableSize.csv:104` 同值；出厂缺省是 100。
        Files.write(dir.resolve("CSV/VariableSize.csv"), "RELATION,200".toByteArray(Charsets.UTF_8))
        Files.write(
            dir.resolve("ERB/01_fold.ERB"),
            """
            ; 布局面样本（语料 QoL_ShowRelation.ERB:3/:4 的最小复刻）
            @FOLD_DIM
            #DIM SORT_VALUE, VARSIZE("RELATION")
            PRINTL 1
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun `布局面 VARSIZE 折出 200`() {
        writeCorpus()
        val result = CorpusCompile.compile(dir.resolve("ERB"))
        assertTrue(result.ok, "合成语料必须编译成功: ${result.failures}")
        val layout = assertNotNull(result.dimLayout, "CompileResult 必须带生产布局实例（观测面）")
        val slots = layout.beginFunction("FOLD_DIM", 0)
        println(
            "CMP-40 布局面读数：beginFunction=$slots 局部槽=${layout.currentSlots.size} " +
                "基名=${layout.currentSlots["SORT_VALUE"]} 末槽=${layout.currentSlots["SORT_VALUE:199"]}",
        )
        // 200 个元素槽 = 基名 + SORT_VALUE:1..:199（DimSpecLayout.beginFunction 的槽登记口径）。
        assertEquals(
            200,
            slots,
            "布局面必须折出 200 个局部槽；折叠器缺席 ⇒ 整条声明被丢（0）",
        )
        assertEquals(200, layout.currentSlots.size, "SORT_VALUE 的 200 个元素槽")
        assertEquals(0, layout.currentSlots["SORT_VALUE"], "基名 = 第 0 个元素槽")
        assertEquals(199, layout.currentSlots["SORT_VALUE:199"], "末元素槽")
    }

    @Test
    fun `布局面丢弃必须进 dimWarnings`() {
        Files.createDirectories(dir.resolve("ERB"))
        Files.write(
            dir.resolve("ERB/02_drop.ERB"),
            """
            @DROP_DIM
            #DIM BAD_SIZE, NO_SUCH_CONST_ANYWHERE
            PRINTL 1
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        val result = CorpusCompile.compile(dir.resolve("ERB"))
        assertTrue(result.ok, "丢弃是 level 2 告警语义，不翻转成败: ${result.failures}")
        // 侧车面（collectPrivateDims）早有一条；布局面丢弃必须也留痕（CMP-89「不能静默丢」）。
        val layoutWarn = result.dimWarnings.filter { it.message.contains("布局面") }
        println("CMP-40 布局面丢弃告警：${layoutWarn.map { it.toString() }}")
        assertTrue(
            layoutWarn.any { it.message.contains("BAD_SIZE") },
            "布局面被丢的声明必须进 dimWarnings；实际: ${result.dimWarnings.map { it.message }}",
        )
    }
}
