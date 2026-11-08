package com.eratw.port.uipreview

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `/state` 的 `divButtons` 面（DIV-WIRE-2 余项④）：JSON 序列化 + 既有键共存。
 *
 * 断言分两层：
 * - 纯函数 [divButtonsJson] / [jsonFloat] / [jsonValueOf]：字段口径、空表、转义、
 *   浮点格式、value 多态（不需要起会话）；
 * - 端到端：mini 语料起真会话 → `/state` 里 `buttons`/`chips` 既有键一个不丢、
 *   `divButtons` 键在且为空表（本基线尚无 div 覆盖块的命中条目；合流 B 路后补
 *   「div 子行按钮出现在 divButtons 里」的第 6 例，见交付 TODO）。
 *
 * 几何口径：`divButtons[]` 是**命中几何**（`ButtonLayer` 建表时按 `cellPx` /
 * `rowHeightPx` 折好的像素矩形，`ConsoleDivPart.cs:137-172`），序列化侧原样搬；
 * 若同帧存在绘制快照键，那是绘制几何（宽 = `width + 2`，CDP :178），两者不许互相折算。
 */
class DivTargetsJsonTest {

    private fun button(
        blockIndex: Int = 3,
        childRow: Int = 1,
        depth: Int = 0,
        colStart: Int = 4,
        colEnd: Int = 8,
        xPx: Float = 21.5f,
        yPx: Float = 63f,
        widthPx: Float = 84f,
        heightPx: Float = 18f,
        value: Any? = 7L,
        label: String = "DIVBTN",
    ): PreviewRenderer.UiDivButton = PreviewRenderer.UiDivButton(
        blockIndex = blockIndex,
        childRow = childRow,
        depth = depth,
        colStart = colStart,
        colEnd = colEnd,
        xPx = xPx,
        yPx = yPx,
        widthPx = widthPx,
        heightPx = heightPx,
        value = value,
        label = label,
    )

    private fun httpGet(port: Int, path: String): Pair<Int, String> {
        val conn = java.net.URL("http://127.0.0.1:$port$path").openConnection() as java.net.HttpURLConnection
        return try {
            val status = conn.responseCode
            val body = (if (status >= 400) conn.errorStream else conn.inputStream)
                ?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
            status to body
        } finally {
            conn.disconnect()
        }
    }

    /** mini 语料：div 子行 0 是文本、子行 1 是数值按钮 7（与 `DIV-WIRE-2` 的命中入口同形）。 */
    private fun erb(vararg lines: String): String =
        "; uipreview divTargets e2e 合成 mini 语料\n" + lines.joinToString("\n") { "\t$it" }

    private val ERB_DIV: String = erb(
        "@SYSTEM_TITLE",
        "HTML_PRINT \"<div xpos='3px' ypos='2px' width='200px' height='100px' " +
            "color='#112233' bcolor='#888888' border='2px' padding='3px,4px'>" +
            "DIVTEXT<br><button value='7'>DIVBTN</button></div>\"",
        "PRINTL TAIL",
        "QUIT",
    )

    // ------------------------------------------------------------------
    // 纯函数层
    // ------------------------------------------------------------------

    @Test
    fun `divButtons JSON 空表为方括号`() {
        assertEquals("[]", divButtonsJson(emptyList()))
    }

    @Test
    fun `divButtons JSON 字段齐与浮点数值格式`() {
        // 命中层几何是 Float（DivHit.xPx 等）：整数浮点去尾（63.0 -> 63）、
        // 小数原样（21.5 -> 21.5），顺序即入参序。
        assertEquals(
            "[{\"blockIndex\":3,\"childRow\":1,\"depth\":0,\"colStart\":4,\"colEnd\":8," +
                "\"x\":21.5,\"y\":63,\"width\":84,\"height\":18,\"value\":7,\"label\":\"DIVBTN\"}]",
            divButtonsJson(listOf(button())),
        )
    }

    @Test
    fun `divButtons JSON 字符串转义与负坐标`() {
        val json = divButtonsJson(
            listOf(
                button(
                    blockIndex = -2,
                    childRow = 0,
                    depth = -1,
                    colStart = 0,
                    colEnd = 2,
                    xPx = -7.5f,
                    yPx = -40f,
                    widthPx = 0f,
                    heightPx = 18f,
                    value = "STR",
                    label = "带\"引号\"\n换行\t制表\\斜杠",
                ),
            ),
        )
        assertEquals(
            "[{\"blockIndex\":-2,\"childRow\":0,\"depth\":-1,\"colStart\":0,\"colEnd\":2," +
                "\"x\":-7.5,\"y\":-40,\"width\":0,\"height\":18,\"value\":\"STR\"," +
                "\"label\":\"带\\\"引号\\\"\\n换行\\t制表\\\\斜杠\"}]",
            json,
            "转义与负坐标: $json",
        )
    }

    @Test
    fun `divButtons JSON value 多态与多条目入参序`() {
        val json = divButtonsJson(
            listOf(
                button(blockIndex = 0, value = null, label = "B0"),
                button(blockIndex = 1, value = "S1", label = "B1"),
                button(blockIndex = 2, value = 9L, label = "B2"),
            ),
        )
        assertTrue(json.startsWith("[{\"blockIndex\":0,"), "入参序即输出序: $json")
        assertTrue(json.contains("\"value\":null"), "null 值写 null 字面量: $json")
        assertTrue(json.contains("\"value\":\"S1\""), "字符串值加引号: $json")
        assertTrue(json.contains("\"value\":9,"), "数值按钮写裸数字: $json")
        assertEquals(3, json.split("\"label\"").size - 1, "三个条目: $json")
        // value 序列化与 state 的 buttons[].value 同口径。
        assertEquals("null", jsonValueOf(null))
        assertEquals("7", jsonValueOf(7L))
        assertEquals("7", jsonValueOf(7))
        assertEquals("\"abc\"", jsonValueOf("abc"))
    }

    @Test
    fun `jsonFloat 整数去尾 小数原样 非有限值写 null`() {
        assertEquals("63", jsonFloat(63f))
        assertEquals("-7", jsonFloat(-7f))
        assertEquals("0", jsonFloat(0f))
        assertEquals("21.5", jsonFloat(21.5f))
        assertEquals("-7.5", jsonFloat(-7.5f))
        assertEquals("null", jsonFloat(Float.NaN))
        assertEquals("null", jsonFloat(Float.POSITIVE_INFINITY))
    }

    // ------------------------------------------------------------------
    // 端到端：既有键共存
    // ------------------------------------------------------------------

    /**
     * `/state` 只加字段不删字段：`buttons` / `chips` 既有键原样在，`divButtons` 键在场。
     * 本基线还没有 div 覆盖块的命中条目（`ConsoleScreen.divTargets()` 的空表），所以是 `[]`；
     * 合流 B 路后同一断言仍成立（只是表可能非空）。
     */
    @Test
    fun `state 保留既有 buttons chips 键并新增 divButtons`(@TempDir tmp: Path) {
        TestRig.start(tmp, MiniTitle.ERB_AUTOQUIT, columns = 60, screenW = 600, screenH = 1200).use { rig ->
            rig.waitUntil("首份 UI 快照发布") { rig.renderer.currentUi != null }
            rig.waitUntil("VM 终局") { rig.renderer.vmEnded }
            val ui = assertNotNull(rig.renderer.currentUi)
            assertEquals(0, ui.divButtons.size, "本帧没有 div 覆盖块，命中表为空表")

            val (status, state) = httpGet(rig.port, "/state")
            assertEquals(200, status, state)
            assertTrue(state.contains("\"buttons\":["), "/state 的既有 buttons 键不得丢: $state")
            assertTrue(state.contains("\"chips\":["), "/state 的既有 chips 键不得丢: $state")
            assertTrue(state.contains("\"divButtons\":["), "/state 应有 divButtons 数组: $state")
            assertTrue(state.contains(",\"divButtons\":[]}"), "空表应为 []: $state")
        }
    }

    /**
     * 命中层 [com.eratw.port.render.button.ButtonLayer.DivHit] → `/state` 快照：Float 像素
     * **原样搬**（不取整、不折算），字段逐个对位。
     */
    @Test
    fun `buildUiDivButtons 原样搬命中几何不取整`() {
        val hit = com.eratw.port.render.button.ButtonLayer.DivHit(
            blockIndex = 3,
            childRow = 1,
            depth = 0,
            colStart = 4,
            colEnd = 8,
            value = 7L,
            label = "DIVBTN",
            xPx = 21.4f,
            yPx = 63.6f,
            widthPx = 83.5f,
            heightPx = 18.0f,
        )

        val out = PreviewRenderer.buildUiDivButtons(listOf(hit))

        assertEquals(1, out.size)
        val b = out[0]
        assertEquals(3, b.blockIndex)
        assertEquals(1, b.childRow)
        assertEquals(0, b.depth)
        assertEquals(4, b.colStart)
        assertEquals(8, b.colEnd)
        // 原样搬：不四舍五入（旧本地线取整到整像素，本波按「px 几何原样搬」）。
        assertEquals(21.4f, b.xPx)
        assertEquals(63.6f, b.yPx)
        assertEquals(83.5f, b.widthPx)
        assertEquals(18.0f, b.heightPx)
        assertEquals(7L, b.value)
        assertEquals("DIVBTN", b.label)
        assertEquals(0, PreviewRenderer.buildUiDivButtons(emptyList()).size, "空表为空表")
    }

    /**
     * 端到端（**真生产路径**，不打桩不手喂）：mini 语料的 `HTML_PRINT <div>` 里带
     * `<button value='7'>`，经 core sink → `DisplayRow.divBlocks` → `ConsoleFrame.draw` 的
     * `screen.updateDivOverlay(...)` 喂帧 → `ButtonLayer.attachDiv` 命中表 →
     * `ConsoleScreen.divTargets()` → `publishUi` → `/state.divButtons[]`。
     *
     * 断言：值 7 / 文案含 DIVBTN / 子行号 1（`<br>` 切行）/ 块号 0（`Plan.all` 首个）/
     * 命中矩形为正；`/state` 里 `divButtons` 数组在场，既有 `buttons` / `chips` 键不丢。
     * **`divs[]` 键**：本 baseline 的 `/state` 没有绘制几何快照键（origin DIV-WIRE 未加，
     * 本路不新造），故只断言既有两键。
     *
     * C# 出处：命中矩形 = `ConsoleDivPart.cs:137-172` 的 TestChildHitbox（块矩形 `:140`
     * ＋ 子行按钮 `:152/:159`）；覆盖层次 = `EmueraConsole.cs:1830` 的 depth 降序。
     */
    @Test
    fun `div 子行按钮经管线出现在 state 的 divButtons 里且既有键不丢`(@TempDir tmp: Path) {
        TestRig.start(tmp, ERB_DIV, columns = 60, screenW = 600, screenH = 1200).use { rig ->
            rig.waitUntil("div 子行按钮发布") {
                rig.renderer.currentUi?.divButtons?.isNotEmpty() == true
            }
            rig.waitUntil("VM 终局") { rig.renderer.vmEnded }

            val ui = assertNotNull(rig.renderer.currentUi)
            val button = ui.divButtons.first { it.value == 7L }
            assertEquals(7L, button.value, "数值按钮回环值")
            assertTrue(button.label.contains("DIVBTN"), "按钮文案: ${button.label}")
            assertEquals(1, button.childRow, "DIVBTN 在第 1 子行（br 切行）")
            assertEquals(0, button.blockIndex, "单块帧的块号是 Plan.all 的 0")
            assertTrue(button.widthPx > 0f && button.heightPx > 0f, "命中矩形尺寸为正")

            val (status, state) = httpGet(rig.port, "/state")
            assertEquals(200, status, state)
            assertTrue(state.contains("\"divButtons\":[{"), "/state 应有 divButtons 数组: $state")
            assertTrue(state.contains("\"blockIndex\":0"), "/state 的 divButtons 应报块号: $state")
            assertTrue(state.contains("\"childRow\":1"), "/state 的 divButtons 应报子行号: $state")
            assertTrue(state.contains("\"value\":7"), "/state 的 divButtons 应报回环值: $state")
            // 既有键不受影响（契约：只加字段）。
            assertTrue(state.contains("\"buttons\":["), "/state 的既有 buttons 键不得丢: $state")
            assertTrue(state.contains("\"chips\":["), "/state 的既有 chips 键不得丢: $state")
        }
    }
}
