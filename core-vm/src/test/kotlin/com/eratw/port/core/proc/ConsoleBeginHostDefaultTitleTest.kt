package com.eratw.port.core.proc

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.vars.GameBaseData
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CONFIG-WIRE U4：默认标题版面（`Process.cs:2138-2158`，SYSTEM_TITLE 缺失分支）。
 *
 * 逐句对拍原版：
 * 1. `PrintBar` + `NewLine`（`Process.cs:2140-2141`）；
 * 2. 居中打 ScriptTitle（`:2142-2143`）；
 * 3. `ScriptVersion != 0` 才打 `ScriptVersionText`（`:2144-2147`）；
 * 4. ScriptAutherName（`:2148`）、`"(" + ScriptYear + ")"`（`:2149`）、`NewLine`（`:2150`）；
 * 5. ScriptDetail（`:2151`）；
 * 6. 左对齐 + `PrintBar` + `NewLine`（`:2152-2154`）；
 * 7. `[0] TitleMenuString0` / `[1] TitleMenuString1`（`:2155-2157`，REPLACE 组键，`Config.cs:732-733`）。
 *
 * 分隔线、空行口径以 [LineBufferConsoleSink] 的既有实现为准（`PrintBar` 打满
 * `clientWidth` 的 `DRAWLINE文字`；空 `NewLine` 落一条空格行，`EmueraConsole.cs:3375-3385`）。
 */
class ConsoleBeginHostDefaultTitleTest {

    private fun host(
        console: LineBufferConsoleSink,
        gamebase: GameBaseData,
        title0: String = "最初からはじめる",
        title1: String = "ロードしてはじめる",
    ) = ConsoleBeginHost(
        input = QueueInputSink(),
        console = console,
        gamebase = gamebase,
        titleMenuString0 = title0,
        titleMenuString1 = title1,
    )

    @Test
    fun `full layout follows Process cs 2138-2158`() {
        val console = LineBufferConsoleSink(clientWidth = 10)
        host(
            console,
            GameBaseData(
                title = "TITLE",
                version = 12345L,
                author = "AUTHOR",
                year = "2024",
                info = "INFO",
            ),
            title0 = "START",
            title1 = "LOAD",
        ).printDefaultTitle()

        assertEquals(
            listOf(
                "----------", // PrintBar：10 格 "-"
                "TITLE",
                "12.345", // ScriptVersionText（GameBase.cs:40-60）
                "AUTHOR",
                "(2024)",
                " ", // NewLine（空缓冲 → 空格行，EmueraConsole.cs:3375-3385）
                "INFO",
                "----------",
                "[0] START",
                "[1] LOAD",
            ),
            console.loggedLines(),
        )
    }

    @Test
    fun `zero version omits version line`() {
        val console = LineBufferConsoleSink(clientWidth = 6)
        host(console, GameBaseData(title = "T", version = 0L, author = "A", year = "Y")).printDefaultTitle()
        assertEquals(
            listOf("------", "T", "A", "(Y)", " ", "------", "[0] 最初からはじめる", "[1] ロードしてはじめる"),
            console.loggedLines(),
        )
    }

    @Test
    fun `version text formatting matches GameBase getter`() {
        // 12340 %10 == 0 → (12340 %1000 /10) 补 2 位 = 34；年份空时照样打括号（Process.cs:2149）
        val even = LineBufferConsoleSink(clientWidth = 4)
        host(even, GameBaseData(version = 12340L)).printDefaultTitle()
        assertEquals(listOf("----", "12.34", "()", " ", "----", "[0] 最初からはじめる", "[1] ロードしてはじめる"), even.loggedLines())

        // 12000 → 0 补 2 位 = 00；12001 → 1 补 3 位 = 001
        val zeros = LineBufferConsoleSink(clientWidth = 4)
        host(zeros, GameBaseData(version = 12000L)).printDefaultTitle()
        assertEquals(listOf("----", "12.00", "()", " ", "----", "[0] 最初からはじめる", "[1] ロードしてはじめる"), zeros.loggedLines())

        val odd = LineBufferConsoleSink(clientWidth = 4)
        host(odd, GameBaseData(version = 12001L)).printDefaultTitle()
        assertEquals(listOf("----", "12.001", "()", " ", "----", "[0] 最初からはじめる", "[1] ロードしてはじめる"), odd.loggedLines())
    }

    @Test
    fun `menu lines come from injected replace values`() {
        val console = LineBufferConsoleSink(clientWidth = 4)
        host(console, GameBaseData(), title0 = "开始", title1 = "读档").printDefaultTitle()
        assertEquals(listOf("----", "()", " ", "----", "[0] 开始", "[1] 读档"), console.loggedLines())
    }
}
