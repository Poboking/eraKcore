package com.eratw.port.core.code

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 标签 / 函数键的大小写归一（CMP-54）——**证伪「走设备默认 locale」这一前提**。
 *
 * 板上原判读：键归一用裸 `uppercase()`，而裸 `uppercase()` 读 `Locale.getDefault()` ⇒
 * tr / az 宿主上 `i` 归一成 `İ`、编译机与设备各归一次 ⇒ 跨机 split-brain。
 *
 * 实测依据：Kotlin stdlib 的**无参** `String.uppercase()` 用的就是**不变 locale**
 * （`Locale.ROOT`，等价 C# `OrdinalIgnoreCase` 的 culture-free 口径；stdlib 契约原文
 * “using Unicode mapping rules of the invariant locale”）⇒ 本仓这 8 处调用点
 * （`Vm.kt:589/1001`、`Program.kt:981/985`（编译侧 `CodeBuilder`）、
 * `StatementExecutor.kt:373/804`、`BootMetadata.kt:203/206`）**本来就不随宿主 locale 变**，
 * 编译机与设备必然归出同一个键。
 *
 * 所以本条不是产品缺陷，而是「读数口径」问题：本类把它钉成可执行的不变式 ——
 * ① 无参 `uppercase/lowercase` 的 locale 不变性；② 土耳其 locale 下二者确实不同
 * （证明这个不变式有内容、不是恒真）；③ 编译侧标签键归一大小写无关；
 * ④ 源码面不许出现 `Locale.getDefault()` 这类按宿主 locale 归一的写法
 * （**唯一会真的引入 split-brain 的写法**）。
 */
class LabelKeyLocaleTest {

    /** ① 无参 `uppercase/lowercase` 是 ROOT 口径 ⇒ 宿主 locale 改不动它。 */
    @Test
    fun `无参 uppercase lowercase 是 ROOT 口径 不随宿主 locale 变`() {
        assertEquals("I", "i".uppercase(), "Kotlin 无参 uppercase 用不变 locale（ROOT）")
        assertEquals("i", "I".lowercase(), "Kotlin 无参 lowercase 同理")
        assertEquals("I", "i".uppercase(Locale.ROOT), "与显式 ROOT 逐值相同")
    }

    /** ② 反例：若换成宿主 locale 归一，键就会分叉 —— 这个不变式有内容。 */
    @Test
    fun `土耳其 locale 下的显式归一确实不同 说明不变式不是恒真`() {
        val tr = Locale.forLanguageTag("tr")
        assertNotEquals("i".uppercase(Locale.ROOT), "i".uppercase(tr), "tr 下 i → İ，键会分叉")
        assertNotEquals("I".lowercase(Locale.ROOT), "I".lowercase(tr), "tr 下 I → ı，键会分叉")
    }

    /** ③ 编译侧标签键归一大小写无关：`CodeBuilder.declareLabel`/`labelIndex` 同一套归一。 */
    @Test
    fun `编译侧标签键归一大小写无关`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.declareLabel(main, "index", 1)

        val lower = b.labelIndex(main, "index")
        val upper = b.labelIndex(main, "INDEX")
        assertTrue(lower >= 0, "声明过的标签必须找得到")
        assertEquals(lower, upper, "标签键归一与大小写无关（编译侧与运行期同一套归一）")
    }

    /** ④ 源码面：键归一不许读宿主默认 locale。 */
    @Test
    fun `键归一调用点没有按宿主 locale 归一的写法`() {
        val files = listOf(
            "src/main/kotlin/com/eratw/port/core/code/Vm.kt",
            "src/main/kotlin/com/eratw/port/core/code/Program.kt",
            "src/main/kotlin/com/eratw/port/core/sem/StatementExecutor.kt",
            "src/main/kotlin/com/eratw/port/core/loader/BootMetadata.kt",
            "src/main/kotlin/com/eratw/port/core/compile/LocalLayout.kt",
        )
        val moduleDir = Path.of("").toAbsolutePath()
        var scanned = 0
        for (rel in files) {
            val p = moduleDir.resolve(rel)
            if (!Files.exists(p)) continue
            scanned++
            val text = p.readText()
            assertTrue(
                !text.contains("Locale.getDefault()"),
                "$rel 不得用宿主默认 locale 做键归一（CMP-54：会与编译机分叉）",
            )
        }
        assertTrue(scanned >= 4, "源码扫描面必须真的读到文件（读了 $scanned 个），否则这条断言是空的")
    }
}
