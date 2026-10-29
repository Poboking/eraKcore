package com.eratw.port.core.save

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.proc.RootedFileOps
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CHKDATA 读失败通道（SAVE-14）：槽位存在而 `readFileBytes` 读不出字节 ≠「宿主未接线」。
 *
 * 判据（`VariableEvaluator.cs:2159-2228` 的 `CheckDataByFilename`）：
 * - `File.Exists` 不命中 → `FILENOTFOUND` + `"----"`（:2162-2166）；
 * - 命中后开档/读档抛异常 → catch 折成 `ETC_ERROR`（枚举第 5 项 = 码 4，EraDataState.cs:3-9），
 *   文案 FileEE 给 `FileEE.Message`（:2220-2223）、其余异常给 `Lang.Error.LoadError.Text`
 *   （:2225-2228；zhs 语言包 =「加载时发生错误」，lang/emuera-zhs.xml:329）。
 *
 * 移植侧 `BuiltinHost.readFileBytes` 的 null 通道不带异常原因（`core/proc/BootHosts.kt:378-382`
 * 的吞异常形态），统一取 [SaveDataProbe.MES_LOAD_ERROR]；`fileOps == null` 才是「未接线」，
 * 维持既有 CodeEE 口径（`core/fn/SaveMethods.kt:58-60`）。
 *
 * 落盘一律 [TempDir]；「不可读」在宿主接缝上合成（readFileBytes 覆写回 null），
 * 不依赖平台 ACL —— 与生产失败形态（`Files.readAllBytes` 抛异常被 `runCatching` 吞成 null）等构。
 */
class ChkDataIoErrorTest {

    /** fileOps 接 `RootedFileOps`、`readFileBytes` 走接口缺省（null = 读不出字节）的宿主桩。 */
    private class IoProbeHost(dir: Path) : BuiltinHost {
        override val fileOps: FileOps = RootedFileOps(dir)
    }

    // ------------------------------------------------------------------
    // SaveDataProbe.check 的分支区分
    // ------------------------------------------------------------------

    @Test
    fun `槽位路径上是同名目录时归入读失败走错误码 4 通道`(@TempDir dir: Path) {
        // 合成同名目录：fileOps.exists 命中（Files.exists 不区分目录），readFileBytes 读不出。
        // 已知残差（SaveDataProbe.check KDoc）：原版 File.Exists 对目录回 false ⇒ FILENOTFOUND，
        // 这里钉的是移植通道的现状口径（ETC_ERROR），差异只在直接调 check 的场合出现。
        Files.createDirectories(dir.resolve("sav").resolve("save00.sav"))
        val r = assertNotNull(
            SaveDataProbe.check(IoProbeHost(dir), SaveFileKind.NORMAL, 0L, GameBaseData(), true),
        )
        assertEquals(EraDataState.ETC_ERROR, r.state)
        assertEquals(4L, r.state.code, "ETC_ERROR 是枚举第 5 项 = 码 4（EraDataState.cs:3-9）")
        assertEquals(SaveDataProbe.MES_LOAD_ERROR, r.mes)
        assertEquals("加载时发生错误", r.mes)
    }

    @Test
    fun `槽位存在但读不出字节时同样走错误码 4 通道`(@TempDir dir: Path) {
        // 「不可读槽位」在接缝上合成：真文件让 fileOps.exists 命中，readFileBytes 覆写回 null
        // 模拟 IO 失败（生产形态：BootHosts.kt:378-382 的 runCatching 吞异常）。
        Files.createDirectories(dir.resolve("sav"))
        Files.write(dir.resolve("sav").resolve("save03.sav"), ByteArray(0))
        val host = object : BuiltinHost {
            override val fileOps: FileOps = RootedFileOps(dir)
            override fun readFileBytes(path: String): ByteArray? = null
        }
        val r = assertNotNull(
            SaveDataProbe.check(host, SaveFileKind.NORMAL, 3L, GameBaseData(), true),
        )
        assertEquals(EraDataState.ETC_ERROR, r.state)
        assertEquals(4L, r.state.code)
        assertEquals("加载时发生错误", r.mes)
    }

    @Test
    fun `空槽仍给 FILENOTFOUND 开局扫描路径不回归`(@TempDir dir: Path) {
        // TITLE.ERB:91-95：FOR check_saves, 0, 20 逐槽 CHKDATA，空槽拿 FILENOTFOUND(1) 继续
        // 扫描、命中 OK(0) 才 BREAK —— 槽不存在必须仍是 FILENOTFOUND 而不是错误通道。
        val host = IoProbeHost(dir)
        for (no in 0L..19L) {
            val r = assertNotNull(
                SaveDataProbe.check(host, SaveFileKind.NORMAL, no, GameBaseData(), true),
                "槽 $no",
            )
            assertEquals(EraDataState.FILENOTFOUND, r.state)
            assertEquals(SaveDataProbe.MES_NOT_FOUND, r.mes)
            assertEquals("----", r.mes)
        }
    }

    // ------------------------------------------------------------------
    // CHKDATA 函数层的两端口径
    // ------------------------------------------------------------------

    @Test
    fun `CHKDATA 把读失败折成 4 写 LoadError 文案而不再抛未接线`(@TempDir dir: Path) {
        Files.createDirectories(dir.resolve("sav").resolve("save00.sav"))
        val store = VariableStore()
        val base = object : EvalContext {
            override lateinit var variables: VariableReader
            override fun callFunction(name: String, args: List<Value?>): Value =
                throw CodeEE("ChkDataIoErrorTest 不应被直接调用：$name")
            override fun printSystemLine(message: String) {}
        }
        base.variables = store
        val ctx = BuiltinEvalContext(base, store, IoProbeHost(dir))
        // FunctionMethodCreator.cs:3537 —— CHKDATA 返回 (long)State；:3536 —— RESULTS:0 = DataMes。
        val v = ctx.callFunction("CHKDATA", listOf(Value.IntV(0)))
        assertEquals(4L, (v as Value.IntV).v)
        assertEquals("加载时发生错误", store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun `fileOps 未接线仍维持宿主未接线的 CodeEE 口径`() {
        // SaveDataProbe.check 对裸宿主回 null（唯一剩余的 null 出口）⇒ 函数层抛 CodeEE，
        // 文案不变（HostCapabilityMethodsTest 的既有钉子继续成立）。
        val store = VariableStore()
        val base = object : EvalContext {
            override lateinit var variables: VariableReader
            override fun callFunction(name: String, args: List<Value?>): Value =
                throw CodeEE("ChkDataIoErrorTest 不应被直接调用：$name")
            override fun printSystemLine(message: String) {}
        }
        base.variables = store
        val bare = object : BuiltinHost {}
        val ctx = BuiltinEvalContext(base, store, bare)
        assertNull(SaveDataProbe.check(bare, SaveFileKind.NORMAL, 0L, GameBaseData()))
        val e = assertFailsWith<CodeEE> { ctx.callFunction("CHKDATA", listOf(Value.IntV(0))) }
        assertEquals(true, e.message?.contains("checkSaveData") == true, "实际：${e.message}")
    }
}
