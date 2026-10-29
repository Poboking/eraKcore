package com.eratw.port.render.grid

import com.eratw.port.render.Grid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * 宽度模型的两条快速通道（W8-RENDERPERF）：TAB 展开的替换串、已展开文本的格数。
 *
 * 它们只许是**同一个口径的另一种写法**，不许变成第二套宽度表：
 * `Grid.cells(s)` 的定义就是 `Grid.cellsExpanded(Grid.expandTabs(s))`，
 * 本测试对每一类码元（ASCII / 全角 / 半角假名 / 代理对 / 控制符 / 含 TAB）逐串断言这条等式。
 * 出处：TAB 展开 spec: `StringMeasure.cs:57-61`；格数口径 spec: `EmueraConsole.cs:3255`
 * 与 `core/host/CellWidth.kt` 的唯一表（见 `Grid` 的 KDoc）。
 */
class GridExpandTabsTest {

    @Test
    fun `TAB展开恒为八个空格`() {
        assertEquals(Grid.TAB_SPACES, 8, "TAB 展开长度是本类的常量口径")
        assertEquals("        ", Grid.expandTabs("\t"))
        assertEquals("a        b", Grid.expandTabs("a\tb"), "只按 8 空格替换，不做制表位对齐")
        assertEquals(10, Grid.cells("\tab"), "8 空格 + 2 个半角字")
    }

    /** 不含 TAB 的串必须原样返回（同一个实例）：这是热路径上「没有 TAB 就不分配」的依据。 */
    @Test
    fun `无TAB时不新建字符串`() {
        val plain = "abc日本語"
        assertSame(plain, Grid.expandTabs(plain))
        val empty = ""
        assertSame(empty, Grid.expandTabs(empty))
    }

    @Test
    fun `已展开文本的格数与直接量逐串一致`() {
        val samples = listOf(
            "",
            " ",
            "abc",
            "日本語",
            "ｱｲｳ",              // 半角假名：各 1 格
            "ａｂｃ",             // 全角英数：各 2 格
            "\uD83D\uDE00x",     // 代理对：逐码元 4 格 + 1
            "\u0000\u007F\u0080\u00A5", // C0 / DEL / U+0080 / 円：单字节档各 1 格
            "\tab",
            "a\tb\tc",
            "\t日\t本\t語\t",
            "",
        )
        for (s in samples) {
            assertEquals(
                Grid.cells(s),
                Grid.cellsExpanded(Grid.expandTabs(s)),
                "串 ${escape(s)} 的两条入口必须同值",
            )
        }
    }

    /** 含 TAB 的串展开后逐码元累加，与 `cells` 的「先展开再量」完全同值。 */
    @Test
    fun `TAB展开后的格数按展开结果计`() {
        assertEquals(8 * Grid.TAB_SPACES, Grid.cells("\t".repeat(8)))
        assertEquals(Grid.cells("\t\t") , Grid.cellsExpanded("                "))
        assertEquals(16, Grid.cells("\t\t"))
    }

    private fun escape(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (ch.code in 0x20..0x7E) sb.append(ch) else sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
        }
        return sb.toString()
    }
}
