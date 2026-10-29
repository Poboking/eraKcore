package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * 宿主能力桩三件套：CHKFONT / CHKDATA / EXISTSOUND。
 *
 * 判据：
 * - `pc-golden-root.txt:279-281` — `FONT=黑体` / `CHKFONT=1`（GOLDEN_02_PRINT.ERB:227-230）
 * - `:489` — `CHKDATA=0 DESC=GOLDEN TEST SAVE`（GOLDEN_08_IO.ERB:33-34）
 * - `:520` — `EXISTSOUND=0`（GOLDEN_09_MISC.ERB:48）
 *
 * 契约要点：宿主未接线时**不得静默**给出"不存在"这种看起来正常的答案 ——
 * CHKDATA 抛 CodeEE；CHKFONT/EXISTSOUND 有语义等价的回落（当前字体名 / 工作目录 sound/）。
 */
private class HcBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("HcBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun hcCtx(host: BuiltinHost): BuiltinEvalContext {
    val store = VariableStore()
    val base = HcBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, host)
}

private fun hcIv(ctx: BuiltinEvalContext, name: String, args: List<Value?>): Long =
    (ctx.callFunction(name, args) as Value.IntV).v

private fun hcStr(s: String): Value = Value.StrV(s)

class HostCapabilityMethodsTest {

    // ------------------------------------------------------------------
    // CHKFONT —— spec: FunctionMethodCreator.cs:3476-3501
    // ------------------------------------------------------------------

    @Test
    fun `CHKFONT 用宿主字体表精确匹配`() {
        // 原版 families[i].Name == strValue（:3493），区分大小写、不做相近替代（:3488-3497）
        val host = object : BuiltinHost {
            override val installedFonts: Set<String> = setOf("黑体", "SimHei")
            override val consoleStyle: ConsoleStyleSnapshot get() = hcSnap(font = "黑体")
        }
        val ctx = hcCtx(host)
        assertEquals(1L, hcIv(ctx, "CHKFONT", listOf(hcStr("黑体"))))
        assertEquals(1L, hcIv(ctx, "CHKFONT", listOf(hcStr("SimHei"))))
        assertEquals(0L, hcIv(ctx, "CHKFONT", listOf(hcStr("simhei"))), "精确比较：大小写不同即未安装")
        assertEquals(0L, hcIv(ctx, "CHKFONT", listOf(hcStr("NoSuchFont_XYZ"))))
    }

    @Test
    fun `CHKFONT 未接线时回落当前字体名`() {
        // installedFonts == null → 当前 consoleStyle.fontName 视为已安装（自证式，见 KDoc）
        val ctx = hcCtx(object : BuiltinHost {
            override val consoleStyle: ConsoleStyleSnapshot get() = hcSnap(font = "黑体")
        })
        // pc-golden-root.txt:279-281：FONT=黑体 / CHKFONT=1
        assertEquals(1L, hcIv(ctx, "CHKFONT", listOf(hcStr("黑体"))))
        assertEquals(0L, hcIv(ctx, "CHKFONT", listOf(hcStr("宋体"))))
    }

    @Test
    fun `CHKFONT 参数个数与原版一致为一个`() {
        val ctx = hcCtx(object : BuiltinHost {})
        assertFailsWith<CodeEE> { ctx.callFunction("CHKFONT", emptyList()) }
        assertFailsWith<CodeEE> { ctx.callFunction("CHKFONT", listOf(hcStr("a"), hcStr("b"))) }
    }

    // ------------------------------------------------------------------
    // CHKDATA —— spec: FunctionMethodCreator.cs:3512-3539
    // ------------------------------------------------------------------

    @Test
    fun `CHKDATA 命中存档槽返回 OK 并写 RESULTS`() {
        // pc-golden-root.txt:489：CHKDATA=0 DESC=GOLDEN TEST SAVE
        val store = VariableStore()
        val base = HcBaseCtx().also { it.variables = store }
        val host = object : BuiltinHost {
            override fun checkSaveData(no: Long, kind: SaveFileKind): SaveDataCheck? =
                if (no == 88L) SaveDataCheck(EraDataState.OK, "GOLDEN TEST SAVE") else null
        }
        val ctx = BuiltinEvalContext(base, store, host)
        assertEquals(0L, (ctx.callFunction("CHKDATA", listOf(Value.IntV(88))) as Value.IntV).v)
        assertEquals("GOLDEN TEST SAVE", store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun `CHKDATA 未存过的槽是 FILENOTFOUND 加四个横线`() {
        // VariableEvaluator.cs:2162-2166：State = FILENOTFOUND、DataMes = "----"
        val store = VariableStore()
        val base = HcBaseCtx().also { it.variables = store }
        val host = object : BuiltinHost {
            override fun checkSaveData(no: Long, kind: SaveFileKind) =
                SaveDataCheck(EraDataState.FILENOTFOUND, "----")
        }
        val ctx = BuiltinEvalContext(base, store, host)
        assertEquals(1L, (ctx.callFunction("CHKDATA", listOf(Value.IntV(3))) as Value.IntV).v)
        assertEquals("----", store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun `CHKDATA 参数越界报错`() {
        // :3527-3534 —— 负值 ArgIsNegative / 超 Int.MAX_VALUE ArgIsTooLarge
        val ctx = hcCtx(object : BuiltinHost {
            override fun checkSaveData(no: Long, kind: SaveFileKind) =
                SaveDataCheck(EraDataState.OK, "")
        })
        assertFailsWith<CodeEE> { ctx.callFunction("CHKDATA", listOf(Value.IntV(-1))) }
        assertFailsWith<CodeEE> {
            ctx.callFunction("CHKDATA", listOf(Value.IntV(Int.MAX_VALUE.toLong() + 1)))
        }
    }

    @Test
    fun `CHKDATA 宿主未接线时报错而不是答不存在`() {
        // 契约：不静默。未接线时若返回 FILENOTFOUND，脚本会以为"没有存档"而不是"引擎缺能力"
        val ctx = hcCtx(object : BuiltinHost {})
        val e = assertFailsWith<CodeEE> { ctx.callFunction("CHKDATA", listOf(Value.IntV(0))) }
        assertEquals(true, e.message?.contains("checkSaveData") == true, "实际：${e.message}")
    }

    // ------------------------------------------------------------------
    // EXISTSOUND —— spec: FunctionMethodCreator.cs:8202-8220
    // ------------------------------------------------------------------

    @Test
    fun `EXISTSOUND 查 sound 目录下的文件`() {
        val dir = kotlin.io.path.createTempDirectory("eratw-existsound").toFile()
        try {
            java.io.File(dir, "hit.mp3").writeText("x")
            val ctx = hcCtx(object : BuiltinHost {
                override val soundDir: java.io.File get() = dir
            })
            // :8214-8218 `File.Exists(Path.GetFullPath(".\\sound\\" + strValue))`
            assertEquals(1L, hcIv(ctx, "EXISTSOUND", listOf(hcStr("hit.mp3"))))
            // Windows 的 File.Exists 大小写不敏感：语料 `PLAYSOUND "bad-apple-audio.MP3"`
            // 对磁盘小写文件，EXISTSOUND 与 PLAYSOUND 必须同判（SoundResourcePaths 单一判定）
            java.io.File(dir, "bgm.wav").writeText("x")
            assertEquals(1L, hcIv(ctx, "EXISTSOUND", listOf(hcStr("BGM.WAV"))))
            // pc-golden-root.txt:520：EXISTSOUND=0（NO_SUCH_SE_XYZ.mp3）
            assertEquals(0L, hcIv(ctx, "EXISTSOUND", listOf(hcStr("NO_SUCH_SE_XYZ.mp3"))))
            // 子目录不递归（File.Exists 只认确切路径）
            assertEquals(0L, hcIv(ctx, "EXISTSOUND", listOf(hcStr("sub/hit.mp3"))))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `EXISTSOUND 未接线时回落工作目录下的 sound`() {
        // 原版就是以工作目录为基准的相对路径；未接入 soundDir 时保持同一语义
        val ctx = hcCtx(object : BuiltinHost {})
        assertEquals(0L, hcIv(ctx, "EXISTSOUND", listOf(hcStr("NO_SUCH_SE_XYZ.mp3"))))
    }

    // ------------------------------------------------------------------
    // 注册面：三件套不再是"未实现"占位
    // ------------------------------------------------------------------

    @Test
    fun `三个函数已从占位表迁出且签名正确`() {
        for (name in listOf("CHKFONT", "CHKDATA", "EXISTSOUND")) {
            val m = Builtins.registry[name]
                ?: error("$name 应已注册")
            assertEquals(1, m.minArgs, "$name 最少参数")
            assertEquals(1, m.maxArgs, "$name 最多参数")
        }
        assertEquals(OperandType.INT, Builtins.registry["CHKFONT"]!!.retType)
        assertEquals(OperandType.INT, Builtins.registry["CHKDATA"]!!.retType)
        assertEquals(OperandType.INT, Builtins.registry["EXISTSOUND"]!!.retType)
        // 占位机制回归：占位已归零（271 项全真实现），语义用本地注册表测。
        // （CHKCHARADATA/FIND_CHARADATA/GETTEXTBOX 均已真实现，活注册表不再有占位名。）
        assertTrue(Builtins.registry.placeholderNames().isEmpty())
        val reg = BuiltinRegistry()
        reg.registerUnimplemented("SCRATCH_UNIMPL_FN", OperandType.INT, 1, 1)
        val e = assertFailsWith<CodeEE> {
            reg["SCRATCH_UNIMPL_FN"]!!.eval!!.invoke(hcCtx(object : BuiltinHost {}), listOf(hcStr("x")))
        }
        assertEquals(true, e.message?.contains("尚未实现") == true, "实际：${e.message}")
    }

    // ------------------------------------------------------------------
    // PRINTCLENGTH / 热键 / 音频查询：同一批"宿主能力"桩
    // ------------------------------------------------------------------

    @Test
    fun `PRINTCLENGTH 默认 25 且可由宿主覆写`() {
        // ConfigData.cs:64 默认 25；pc-golden-root.txt 的 CONFIG-QUERY 段 = 25
        assertEquals(
            25L,
            hcIv(hcCtx(object : BuiltinHost {}), "PRINTCLENGTH", emptyList()),
        )
        assertEquals(
            40L,
            hcIv(hcCtx(object : BuiltinHost {
                override val printCLength: Long get() = 40L
            }), "PRINTCLENGTH", emptyList()),
        )
    }

    @Test
    fun `热键两函数恒返回 0 且参数个数与原版一致`() {
        // :8730 / :8704 —— 两者都 return 0L
        val ctx = hcCtx(object : BuiltinHost {})
        assertEquals(0L, hcIv(ctx, "HOTKEY_STATE_INIT", listOf(Value.IntV(2))))
        // :8713-8720 的 OmitStart=1 = **必需的前导位数**（FunctionMethod.cs:183
        // `arguments.Count >= OmitStart`）⇒ 0 个实参在解析期就报 NotEnoughArgs（:283）
        assertFailsWith<CodeEE> { hcIv(ctx, "HOTKEY_STATE_INIT", emptyList()) }
        assertEquals(0L, hcIv(ctx, "HOTKEY_STATE", listOf(Value.IntV(1), Value.IntV(0))))
        assertEquals(0L, hcIv(ctx, "HOTKEY_STATE", listOf(Value.IntV(1))), "OmitStart=1：可省略第 2 参")
        assertFailsWith<CodeEE> { ctx.callFunction("HOTKEY_STATE", emptyList()) }
    }

    @Test
    fun `ISPLAYINGSOUND 与 ISPLAYINGBGM 在无音频后端时的取值`() {
        // pc-golden-root.txt:520 —— ISPLAYINGBGM=0 ISPLAYINGSOUND=-1
        val ctx = hcCtx(object : BuiltinHost {})
        assertEquals(0L, hcIv(ctx, "ISPLAYINGBGM", emptyList()))
        // soundChannels = 0 ⇒ 任何下标越界 ⇒ -1（:8849-8852）
        assertEquals(-1L, hcIv(ctx, "ISPLAYINGSOUND", listOf(Value.IntV(0))))
        assertEquals(-1L, hcIv(ctx, "ISPLAYINGSOUND", listOf(Value.IntV(-1))))
    }

    @Test
    fun `ISPLAYINGSOUND 在接了两条声道且第 1 条播放时返回 1`() {
        val ctx = hcCtx(object : BuiltinHost {
            override val soundChannels: Int get() = 2
            override fun isPlayingSound(channel: Int): Boolean = channel == 1
        })
        assertEquals(-1L, hcIv(ctx, "ISPLAYINGSOUND", listOf(Value.IntV(0))))
        assertEquals(1L, hcIv(ctx, "ISPLAYINGSOUND", listOf(Value.IntV(1))))
        // 越界仍 -1
        assertEquals(-1L, hcIv(ctx, "ISPLAYINGSOUND", listOf(Value.IntV(2))))
    }

    @Test
    fun `ISPLAYINGSOUND 的实参先按 (int) 截断再判越界`() {
        // :8848 —— `int num = (int)arguments[0].GetIntValue(exm)`：截断发生在 :8849 的越界判定
        // **之前**，故 2^32 落成 0、-2^32 也落成 0。未截断的实现会把两者当越界，直接给 -1。
        val ctx = hcCtx(object : BuiltinHost {
            override val soundChannels: Int get() = 2
            override fun isPlayingSound(channel: Int): Boolean = channel == 0
        })
        assertEquals(0L, hcIv(ctx, "ISPLAYINGSOUND", listOf(Value.IntV(1L shl 32))), "2^32 → 截断成声道 0")
        assertEquals(0L, hcIv(ctx, "ISPLAYINGSOUND", listOf(Value.IntV(-(1L shl 32)))), "-2^32 → 截断成声道 0")
        assertEquals(0L, hcIv(ctx, "ISPLAYINGSOUND", listOf(Value.IntV(0))))
    }

    @Test
    fun `CLEARMEMORY 可调用`() {
        // :8334-8338 —— 返回值是 GC 前后工作集差，依赖运行时状态，无固定期望；只锁"能跑通"
        hcIv(hcCtx(object : BuiltinHost {}), "CLEARMEMORY", emptyList())
    }
}

private fun hcSnap(font: String) = ConsoleStyleSnapshot(
    fore = null, back = null,
    defFore = intArrayOf(192, 192, 192), defBack = intArrayOf(0, 0, 0),
    focus = intArrayOf(255, 255, 0),
    fontStyleMask = 0, fontName = font, alignment = 0, redraw = 0,
)
