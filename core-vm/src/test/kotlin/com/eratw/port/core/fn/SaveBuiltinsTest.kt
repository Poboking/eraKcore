package com.eratw.port.core.fn

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveDataProbe
import com.eratw.port.core.save.SaveWiring
import com.eratw.port.core.sem.SemTestEnv
import com.eratw.port.core.sem.iLit
import com.eratw.port.core.sem.instr
import com.eratw.port.core.sem.intArg
import com.eratw.port.core.sem.sLit
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SpSaveDataArgument
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * M6-SAVE-WIRE 的验收：**接线**（Builtins 注册点 / `GameSession` 装配 / `SaveWiring`）
 * 与两条存档函数（`CHKDATA` / `SAVENOS`）的 C# 口径。
 *
 * 覆盖：
 * 1. `SAVEDATA` → `LOADDATA` → `LASTLOAD_NO` / `LASTLOAD_TEXT` 与 `RESULT` 的读写面；
 * 2. `SAVEGLOBAL` → `LOADGLOBAL` → `RESULT`（唯一由语句写 RESULT 的存档语句）；
 * 3. `CHKDATA` 三态（OK / FILENOTFOUND / ETC_ERROR）+ `RESULTS` 描述文本；
 * 4. `CHKDATA` / `SAVENOS` 的非法参数与 `UseSaveFolder` 路径口径；
 * 5. 只读存档的写失败（`SAVEDATA` 打印错误行）与 `DELDATA` 的只读拒绝；
 * 6. `GameSession` 装配：`emuera.config` 三项 → [SaveConfig] → `SAVEDATA` 落 `sav/`，
 *    且 `CHKDATA` 从同一份 [SaveWiring] 读 `UseSaveFolder`。
 *
 * 期望值全部带 C# 行号（各用例内注明），不拿实现算出来的值当「原版读数」。
 */
class SaveBuiltinsTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 只做 [EvalContext] 的空壳：函数调用不经过它（走 [BuiltinEvalContext]）。 */
    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    private fun builtinCtx(store: VariableStore, host: BuiltinHost): BuiltinEvalContext {
        val base = BaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store, host)
    }

    private fun iv(v: Value): Long = (v as Value.IntV).v

    private fun binary() = SaveConfig(systemSaveInBinary = true, zipSaveData = true)

    private fun fs(store: VariableStore, root: Path, config: SaveConfig = binary()) =
        FileSystemOps(store, RootedSaveFileIo(root), config)

    private fun idx(vararg v: Long): LongArray = v

    // ------------------------------------------------------------------
    // 1. SAVEDATA → LOADDATA：LASTLOAD_* 与 RESULT
    // ------------------------------------------------------------------

    /**
     * `LOADDATA` 的收尾只有 `varData.LastLoadNo = dataIndex`（VariableEvaluator.cs:2706）
     * 加上装载体内的 `LastLoadVersion` / `LastLoadText`（:2627-2628）；**它不写 RESULT**。
     *
     * RESULT 本人是**可存档变量**（`VariableCode.isSavedata` 对 `0x0A0000` 一维整型组的
     * `vci < 60` 判真，VariableToken.cs:88-154 的位口径），所以它的值随档回填 ——
     * 读档后读到的是**存档里的** 777，而不是被 `SetDefaultValue`（:2625）复位后的 0。
     * 这条正是「LOADDATA 不碰 RESULT」的可观测面：任何写 RESULT 的实现都会把它改掉。
     */
    @Test
    fun `SAVEDATA 再 LOADDATA 恢复变量并写 LASTLOAD 不动 RESULT 语义`(@TempDir root: Path) {
        val env = SemTestEnv()
        env.ctx.systemOps = fs(env.store, root)
        env.store.writeInt("DAY", idx(0), 12)
        env.store.writeInt("RESULT", idx(0), 777)

        env.run(instr(FunctionCode.SAVEDATA, SpSaveDataArgument(iLit(2), sLit("存档描述"))))
        assertTrue(Files.isRegularFile(root.resolve("save02.sav")), "UseSaveFolder=false ⇒ SavDir=ExeDir（Config.cs:478-481）")

        env.store.writeInt("DAY", idx(0), 0)
        env.store.writeInt("RESULT", idx(0), -1)
        env.run(instr(FunctionCode.LOADDATA, intArg(2)))

        assertEquals(12L, env.store.readInt("DAY", idx(0)))
        assertEquals(2L, env.store.lastLoadNo, "VariableEvaluator.cs:2706")
        assertEquals("存档描述", env.store.lastLoadText, "VariableEvaluator.cs:2628")
        assertEquals(777L, env.store.readInt("RESULT", idx(0)), "RESULT 随存档变量回填，LOADDATA 不额外写它")
    }

    // ------------------------------------------------------------------
    // 2. SAVEGLOBAL → LOADGLOBAL：RESULT 1/0、不动 LASTLOAD
    // ------------------------------------------------------------------

    /**
     * `LOADGLOBAL` 是**唯一**自己写 RESULT 的存档语句
     * （FunctionIdentifier.cs:1825-1832：`RESULT = LoadGlobal() ? 1 : 0`），
     * 且全局装载不碰 `LASTLOAD_*`（VariableEvaluator.cs:2513-2589 没有那两行赋值）。
     */
    @Test
    fun `LOADGLOBAL 写 RESULT 且不动 LASTLOAD`(@TempDir root: Path) {
        val env = SemTestEnv()
        env.ctx.systemOps = fs(env.store, root)
        env.store.writeInt("GLOBAL", idx(3), 77)

        env.run(instr(FunctionCode.SAVEGLOBAL))
        assertTrue(Files.isRegularFile(root.resolve("global.sav")), "VariableEvaluator.cs:2018-2021")

        env.store.writeInt("GLOBAL", idx(3), 0)
        env.store.lastLoadText = "keep"
        env.store.lastLoadVersion = 123

        env.run(instr(FunctionCode.LOADGLOBAL))
        assertEquals(77L, env.store.readInt("GLOBAL", idx(3)))
        assertEquals(1L, env.store.readInt("RESULT", idx(0)), "成功写 1（FunctionIdentifier.cs:1827）")
        assertEquals("keep", env.store.lastLoadText, "全局装载不动 LASTLOAD_TEXT")
        assertEquals(123L, env.store.lastLoadVersion, "全局装载不动 LASTLOAD_VERSION")
    }

    /** 没有全局档 ⇒ `RESULT=0`（:1831），不抛。 */
    @Test
    fun `LOADGLOBAL 缺档写 RESULT 0`(@TempDir root: Path) {
        val env = SemTestEnv()
        env.ctx.systemOps = fs(env.store, root)
        env.store.writeInt("RESULT", idx(0), 5)
        env.run(instr(FunctionCode.LOADGLOBAL))
        assertEquals(0L, env.store.readInt("RESULT", idx(0)))
    }

    // ------------------------------------------------------------------
    // 3. CHKDATA 三态
    // ------------------------------------------------------------------

    /**
     * 三个状态码取 `EraDataState` 的声明序（EraDataState.cs:3-10，`FunctionMethodCreator.cs:3537`
     * 直接把 `(long)State` 当返回值）：
     * - `OK=0` + 存档描述文本（VariableEvaluator.cs:2213-2215）；
     * - `FILENOTFOUND=1` + `"----"`（:2162-2166）；
     * - `ETC_ERROR=4` + 损坏文案（:2196-2201）。
     */
    @Test
    fun `CHKDATA 三态与 RESULTS 描述`(@TempDir root: Path) {
        val store = VariableStore()
        val host = RootedBuiltinHost(root)
        val ctx = builtinCtx(store, host)

        // ① 未存过
        assertEquals(1L, iv(ctx.callFunction("CHKDATA", listOf(Value.IntV(9)))))
        assertEquals("----", store.readStr("RESULTS", longArrayOf(0)))

        // ② 正常档
        assertTrue(fs(store, root).saveData(5, "hello"))
        assertEquals(0L, iv(ctx.callFunction("CHKDATA", listOf(Value.IntV(5)))))
        assertEquals("hello", store.readStr("RESULTS", longArrayOf(0)))

        // ③ 垃圾字节（头探测失败）
        Files.write(root.resolve("save07.sav"), byteArrayOf(1, 2, 3))
        assertEquals(4L, iv(ctx.callFunction("CHKDATA", listOf(Value.IntV(7)))))
        // Lang.Error.CorruptedSaveData（lang/emuera.zhs.xml:328），经 SaveDataProbe.MES_CORRUPTED
        assertEquals(SaveDataProbe.MES_CORRUPTED, store.readStr("RESULTS", longArrayOf(0)))
    }

    // ------------------------------------------------------------------
    // 4. 非法参数 / UseSaveFolder / SAVENOS
    // ------------------------------------------------------------------

    /**
     * `CheckdataMethod` 的两条边界（FunctionMethodCreator.cs:3527-3534），
     * 文案取 `Lang.Error.ArgIsNegative` / `ArgIsTooLarge`（lang/emuera.zhs.xml:913 / :915）。
     */
    @Test
    fun `CHKDATA 非法参数回 Lang 原文`(@TempDir root: Path) {
        val ctx = builtinCtx(VariableStore(), RootedBuiltinHost(root))
        val neg = assertFailsWith<CodeEE> { ctx.callFunction("CHKDATA", listOf(Value.IntV(-1))) }
        assertEquals("CHKDATA函数: 第1个参数被是负值(-1)", neg.message)
        val big = assertFailsWith<CodeEE> {
            ctx.callFunction("CHKDATA", listOf(Value.IntV(Int.MAX_VALUE.toLong() + 1)))
        }
        assertEquals("CHKDATA函数: 第1个参数(2147483648)过大", big.message)
    }

    /**
     * `UseSaveFolder` 的两条落点（`SavDir`，Config.cs:471-481）：
     * 打开时只认 `sav/`，关闭时只认根目录 —— 另一侧的同名文件**不算**存在。
     *
     * 夹具口径（组长接线 `checkSaveData` 传 `useSaveFolder` 后必须同步）：`CHKDATA` 先问宿主
     * （`SaveMethods.kt:58-60`），宿主的方向来自**开局三层链**、会话的方向来自 [SaveConfig]，
     * 生产里两者同源（`GameSession` 按同一条链装 `SaveConfig`），所以这里必须真的往临时根写
     * `emuera.config` 再装配宿主，而不是只换 `SaveConfig` —— 否则就是「宿主说根目录、
     * 会话说 sav/」的两真源打架，测的不是原版语义。宿主的方向在构造期读定，切换开关要重建宿主。
     */
    @Test
    fun `CHKDATA 按 UseSaveFolder 决定唯一存档目录`(@TempDir root: Path) {
        Files.writeString(root.resolve("emuera.config"), "USE SAV FOLDER:YES\n")
        val on = VariableStore()
        SaveWiring.install(on, SaveConfig(systemSaveInBinary = true, zipSaveData = true, useSaveFolder = true), 20)
        val onHost = RootedBuiltinHost(root)
        assertTrue(onHost.useSaveFolder, "宿主条目必须与 SaveConfig 同向（生产里两者同源于开局三层链）")
        val onCtx = builtinCtx(on, onHost)
        assertTrue(fs(on, root, SaveConfig.ERATW).saveData(4, "in-sav"))
        assertTrue(Files.isRegularFile(root.resolve("sav/save04.sav")))
        assertEquals(0L, iv(onCtx.callFunction("CHKDATA", listOf(Value.IntV(4)))))
        assertEquals("in-sav", on.readStr("RESULTS", longArrayOf(0)))

        // 把同一份字节放到根目录：在 UseSaveFolder=ON 的会话里应当作「不存在」
        Files.copy(root.resolve("sav/save04.sav"), root.resolve("save03.sav"))
        assertEquals(1L, iv(onCtx.callFunction("CHKDATA", listOf(Value.IntV(3)))))
        assertEquals("----", on.readStr("RESULTS", longArrayOf(0)))

        // UseSaveFolder=OFF 的会话：反过来只看根目录（条目改 NO ⇒ 必须重建宿主才生效）
        Files.writeString(root.resolve("emuera.config"), "USE SAV FOLDER:NO\n")
        val off = VariableStore()
        SaveWiring.install(off, SaveConfig(systemSaveInBinary = true, zipSaveData = true, useSaveFolder = false), 20)
        val offCtx = builtinCtx(off, RootedBuiltinHost(root))
        assertEquals(0L, iv(offCtx.callFunction("CHKDATA", listOf(Value.IntV(3)))))
        assertEquals("in-sav", off.readStr("RESULTS", longArrayOf(0)))
        assertEquals(1L, iv(offCtx.callFunction("CHKDATA", listOf(Value.IntV(4)))))
    }

    /**
     * `SAVENOS()` = `Config.SaveDataNos`（FunctionMethodCreator.cs:3952-3966），
     * 生效值经 `Config.cs:338` 读 + `:441-450` 夹到 20..80。
     * 未装配（纯单测/erbcc 自检）时回落宿主缺省 20（ConfigData.cs:87）。
     */
    @Test
    fun `SAVENOS 读 SaveWiring 的生效值否则回落宿主`(@TempDir root: Path) {
        val wired = VariableStore()
        SaveWiring.install(wired, SaveConfig.ERATW, 80)
        assertEquals(80L, iv(builtinCtx(wired, RootedBuiltinHost(root)).callFunction("SAVENOS", emptyList())))

        val bare = VariableStore()
        assertEquals(20L, iv(builtinCtx(bare, RootedBuiltinHost(root)).callFunction("SAVENOS", emptyList())))
    }

    // ------------------------------------------------------------------
    // 5. 只读存档
    // ------------------------------------------------------------------

    /**
     * `SaveTo` 把一切异常吞成 false（VariableEvaluator.cs:2666-2669），语句层据此打印
     * `UnexpectedErrorInSavedata`（Process.cs:1344-1347；lang/emuera.zhs.xml:713）。
     *
     * 只读口径用真实文件属性（Windows = ReadOnly 位，POSIX = 去写位）：
     * `RootedSaveFileIo.write` 的 `Files.write(TRUNCATE_EXISTING)` 会被拒 → false。
     */
    @Test
    fun `只读存档写失败打印错误行且 DELDATA 被拒`(@TempDir root: Path) {
        val env = SemTestEnv()
        env.ctx.systemOps = fs(env.store, root)
        env.run(instr(FunctionCode.SAVEDATA, SpSaveDataArgument(iLit(1), sLit("a"))))
        val file = root.resolve("save01.sav").toFile()
        assertTrue(file.isFile)

        try {
            assertTrue(file.setWritable(false), "测试装置需要真的把文件置只读")
            env.console.events.clear()
            env.run(instr(FunctionCode.SAVEDATA, SpSaveDataArgument(iLit(1), sLit("b"))))
            assertEquals(
                listOf("printError(SAVEDATA命令在存档时发生了意料之外的错误)"),
                env.console.events,
                "VariableEvaluator.cs:2666-2669 → false → Process.cs:1344-1347",
            )

            // DELDATA：只读 → CodeEE(DelReadOnlyFile)（VariableEvaluator.cs:2710-2721，
            // lang/emuera.zhs.xml:332 的 `{0}` = 路径）
            val e = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.DELDATA, intArg(1))) }
            assertTrue(e.message!!.startsWith("无法删除指定的只读文件"), "实际：${e.message}")
            assertTrue(file.isFile, "只读档不应被删掉")
        } finally {
            file.setWritable(true)
        }
    }

    // ------------------------------------------------------------------
    // 6. GameSession 装配
    // ------------------------------------------------------------------

    /**
     * `emuera.config` 三项（语料 `:51` / `:60` / `:74`）→ [SaveConfig] → `SAVEDATA` 落
     * `sav/save00.sav`；`CHKDATA` 从同一份 [SaveWiring] 读 `UseSaveFolder`，
     * 所以它认的就是刚才那条落盘路径。
     */
    @Test
    fun `GameSession 装配真存档层并把 SAVEDATA 落到 sav 子目录`(@TempDir root: Path) {
        Files.writeString(
            root.resolve("emuera.config"),
            listOf(
                "USE SAV FOLDER:YES",
                "USE THE BINARY FORMAT FOR SAVING DATA:YES",
                "COMPRESS SAVE DATA:YES",
            ).joinToString("\n", postfix = "\n"),
            StandardCharsets.UTF_8,
        )
        val (program, meta) = miniProgram("SAVEDATA 0, \"wire\"")
        val session = GameSession(root, program, meta, consoleWidth = 199, randSeed = 1L)
        try {
            assertTrue(session.saveConfig.systemSaveInBinary, "ConfigData.cs:107")
            assertTrue(session.saveConfig.zipSaveData, "ConfigData.cs:128")
            assertTrue(session.saveConfig.useSaveFolder, "ConfigData.cs:97")
            assertEquals(20, session.saveWiring.saveDataNos, "ConfigData.cs:87 出厂值 20")

            assertTrue(session.begin(BeginType.TITLE), "SYSTEM_TITLE 已登记")
            val file = root.resolve("sav/save00.sav")
            assertTrue(Files.isRegularFile(file), "SAVEDATA 应落 sav/save00.sav（Config.cs:471-481）")

            // 函数层从同一份接线读回：状态 OK + 描述文本
            val ctx = builtinCtx(session.store, session.builtinHost)
            assertEquals(0L, iv(ctx.callFunction("CHKDATA", listOf(Value.IntV(0)))))
            assertEquals("wire", session.store.readStr("RESULTS", longArrayOf(0)))
            assertEquals(20L, iv(ctx.callFunction("SAVENOS", emptyList())))
        } finally {
            session.close()
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 单函数 `SYSTEM_TITLE` 的最小产物（真的走解析器 + 编译器，不手搓字节码）。 */
    private fun miniProgram(vararg body: String): Pair<Program, BootMetadata> {
        val parser = LineParser(SymbolTable().declareVariable("RESULT", OperandType.INT, 0))
        val stmts = (listOf("@SYSTEM_TITLE") + body).mapIndexedNotNull { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            parser.parseLogicalLine(LogicalLine(kind, "T.ERB", i + 1, text))
        }
        val compiler = ErbProgramCompiler()
        val fb = compiler.compileFunction(FuncSig("SYSTEM_TITLE"), stmts, setOf("SYSTEM_TITLE"))
        val program = compiler.build("SYSTEM_TITLE")
        val meta = BootMetadata(
            labels = mapOf("SYSTEM_TITLE" to fb.labels),
            functions = listOf(
                FuncMeta(
                    index = fb.index,
                    key = "SYSTEM_TITLE",
                    name = "SYSTEM_TITLE",
                    defIndex = 0,
                    position = "T.ERB:1",
                ),
            ),
            fileByKey = mapOf("SYSTEM_TITLE" to "T.ERB"),
        )
        return program to meta
    }
}
