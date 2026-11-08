package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * CONFIG-WIRE U1/U2/U4：REPLACE 组初值与标签串的**会话装配链**。
 *
 * 接线前的形状：`GameSession.kt` 构造 [com.eratw.port.core.vars.VarConfig] 与
 * [com.eratw.port.core.sem.SemConfig] 时不喂这八件，它们恒吃 data class 缺省 ⇒
 * 玩家在设置页改「EXPLVの初期値」「お金の単位」等条目**永不生效**（有名字但不可用）。
 * 消费点其实一直在：变量池复位段 `VariableStore.kt:3520-3532` 吃 VarConfig，
 * 语句层 `StatementExecutor.kt` 吃 SemConfig；断的只是装配那一段。
 *
 * 原版链：`ConfigData.cs:159-174` 注册 REPLACE 组 → `Config.cs:719-741` 的
 * `SetReplace`（`Process.cs:240` 按 `UseReplaceFile && !AnalysisMode && File.Exists`
 * 装载 `CSV/_Replace.csv`）→ `VariableData.SetDefaultValue`（`VariableData.cs:3485-3490`
 * 的 CopyTo + Min 截断灌 EXPLV/PALAMLV，`:3501-3504` 灌 PBAND，
 * `CharacterData.cs:166-169` 逐角色灌 RELATION）与 `Config` 静态字段
 * （`Config.cs:199/:201` MoneyLabel/MoneyFirst、`:736` TimeupLabel，
 * `VariableEvaluator.cs:1901` 的 SetDefaultStain 口）。
 *
 * 本类只钉「同一条链进会话」这一面，四条判据：
 * 1. `_Replace.csv` 的四个初值键确实落到变量池（不只是条目表）；
 * 2. 无文件 / `USE _REPLACE.CSV FILE:NO` 时保持出厂缺省（**基准零漂移**）；
 * 3. SemConfig 四件（金额标签/位置、超时提示、污渍初值）同样吃链值；
 * 4. 出厂缺省下这四件与注册默认逐项相等。
 *
 * REPLACE 组的键在 emuera.config 主文件里写不动（`ConfigChainTest` 已钉原版口径），
 * 因此夹具一律走 `CSV/_Replace.csv`，与 `ReplaceWireTest` 同形。
 */
class GameSessionConfigWireInitTest {

    @TempDir
    lateinit var dir: Path

    // ------------------------------------------------------------------
    // 夹具：最小产物 + 合成游戏根
    // ------------------------------------------------------------------

    /** 同 GameSessionSettingsWireTest 的真解析器 + 真编译器口径。 */
    private fun session(root: Path): GameSession {
        val compiler = ErbProgramCompiler()
        val stmts = listOf("@SYSTEM_TITLE", "PRINTL PLAIN").mapIndexed { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            LineParser(SymbolTable()).parseLogicalLine(
                LogicalLine(kind, "TEST.ERB", i + 1, text),
            )
        }.filterNotNull()
        val fb: FunctionBytecode = compiler.compileFunction(
            FuncSig("SYSTEM_TITLE"),
            stmts,
            setOf("SYSTEM_TITLE"),
        )
        val program: Program = compiler.build("SYSTEM_TITLE")
        val metadata = BootMetadata(
            functions = listOf(
                FuncMeta(
                    index = fb.index, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE",
                    defIndex = 0, position = "TEST.ERB:1",
                ),
            ),
        )
        return GameSession(
            gameRoot = root,
            program = program,
            metadata = metadata,
            consoleWidth = 199,
            randSeed = 1L,
        )
    }

    private fun rootWith(emuera: String? = null, replace: String? = null): Path {
        val root = Files.createTempDirectory("eratw-config-wire-init")
        if (emuera != null) {
            Files.writeString(root.resolve("emuera.config"), emuera)
        }
        if (replace != null) {
            val csv = EmueraConfigParser.resolveCsvDir(root)
            Files.createDirectories(csv)
            Files.writeString(csv.resolve("_Replace.csv"), replace)
        }
        return root
    }

    private fun withSession(root: Path, body: (GameSession) -> Unit) {
        val s = session(root)
        try {
            body(s)
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // U1/U2：变量池初值
    // ------------------------------------------------------------------

    @Test
    fun `Replace 的四个初值键经会话装配落到变量池`() {
        val root = rootWith(
            replace = listOf(
                "EXPLVの初期値, 0/1/4/20/50/200/999",
                "PALAMLVの初期値, 0/100/7777/3000/10000",
                "PBANDの初期値, 9",
                "RELATIONの初期値, 7",
            ).joinToString("\n"),
        )
        withSession(root) { s ->
            // 装配面（VarConfig）：条目 → 会话
            assertEquals(
                listOf(0L, 1L, 4L, 20L, 50L, 200L, 999L),
                s.store.config.expLvDef.toList(),
                "Config.cs:719-741 的 SetReplace → GameSession 的 VarConfig.expLvDef",
            )
            assertEquals(
                listOf(0L, 100L, 7777L, 3000L, 10000L),
                s.store.config.palamLvDef.toList(),
            )
            assertEquals(9L, s.store.config.pbandDef)
            assertEquals(7L, s.store.config.relationDef, "CharacterData.cs:166-169 的逐角色整列初值")

            // 消费面（变量池）：VariableData.cs:3485-3490 的 CopyTo+Min 截断
            assertEquals(999L, s.store.readInt("EXPLV", longArrayOf(6)))
            assertEquals(20L, s.store.readInt("EXPLV", longArrayOf(3)))
            assertEquals(7777L, s.store.readInt("PALAMLV", longArrayOf(2)))
            assertEquals(100L, s.store.readInt("PALAMLV", longArrayOf(1)))
            assertEquals(0L, s.store.readInt("PALAMLV", longArrayOf(9)), "只灌前 5 项，后面保持复位后的 0")
            assertEquals(9L, s.store.readInt("PBAND", longArrayOf(0)), "VariableData.cs:3501-3504")
        }
    }

    @Test
    fun `无 Replace 文件时初值保持出厂缺省 基准零漂移`() {
        withSession(rootWith()) { s ->
            assertEquals(0L, s.store.readInt("PALAMLV", longArrayOf(0)))
            assertEquals(100L, s.store.readInt("PALAMLV", longArrayOf(1)))
            assertEquals(250000L, s.store.readInt("PALAMLV", longArrayOf(9)), "ConfigData.cs:172 的 10 项出厂值")
            assertEquals(200L, s.store.readInt("EXPLV", longArrayOf(5)), "ConfigData.cs:171")
            assertEquals(4L, s.store.readInt("PBAND", longArrayOf(0)), "ConfigData.cs:173")
            assertEquals(0L, s.store.config.relationDef, "ConfigData.cs:174")
        }
    }

    @Test
    fun `UseReplaceFile 关闭时初值不被改写`() {
        val root = rootWith(
            emuera = "USE _REPLACE.CSV FILE:NO\n",
            replace = "EXPLVの初期値, 0/1/4/20/50/200/999\nPBANDの初期値, 9\n",
        )
        withSession(root) { s ->
            assertEquals(200L, s.store.readInt("EXPLV", longArrayOf(5)), "Process.cs:240 的守卫不读这一层")
            assertEquals(4L, s.store.readInt("PBAND", longArrayOf(0)))
        }
    }

    // ------------------------------------------------------------------
    // U4：语句层标签串与污渍初值
    // ------------------------------------------------------------------

    @Test
    fun `Replace 的标签串与污渍初值经会话装配进语句层`() {
        val root = rootWith(
            replace = listOf(
                "お金の単位, ￥",
                "単位の位置, 後",
                "時間切れ表示, TIME UP",
                "汚れの初期値, 0/0/2/1/8/0/1/8",
            ).joinToString("\n"),
        )
        withSession(root) { s ->
            val cfg = s.runtime.execContext.config
            assertEquals("￥", cfg.moneyLabel, "Config.cs:199 静态字段；与 store.moneyLabel 同源不留第二把尺")
            assertEquals(false, cfg.moneyFirst, "「後」经 ConfigItem.cs:239-250 的 tryStringToBool ⇒ false")
            assertEquals("TIME UP", cfg.timeupLabel, "Config.cs:736（配置项 111）")
            assertEquals(
                listOf(0L, 0L, 2L, 1L, 8L, 0L, 1L, 8L),
                cfg.stainDefault.toList(),
                "VariableEvaluator.cs:1901 的 SetDefaultStain 口；RESET_STAIN 语句面与 BEGIN TRAIN 面同一条目",
            )
            assertEquals("￥", s.store.moneyLabel, "MONEYSTR 伪变量与商店打印同源")
        }
    }

    @Test
    fun `出厂缺省下语句层四件等于注册默认 基准零漂移`() {
        withSession(rootWith()) { s ->
            val cfg = s.runtime.execContext.config
            assertEquals("$", cfg.moneyLabel, "ConfigData.cs:159")
            assertEquals(true, cfg.moneyFirst, "ConfigData.cs:160")
            assertEquals("時間切れ", cfg.timeupLabel, "ConfigData.cs:170（繁体 時/間）")
            assertEquals(listOf(0L, 0L, 2L, 1L, 8L), cfg.stainDefault.toList(), "ConfigData.cs:169 的 5 项")
        }
    }
}
