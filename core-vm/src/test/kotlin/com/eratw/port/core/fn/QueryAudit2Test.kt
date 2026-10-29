package com.eratw.port.core.fn

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveWiring
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 审计二轮 L08（宿主 / 查询类内建）的边界钉。
 *
 * | # | 判据 | 原版权威 |
 * |---|---|---|
 * | Q1 | 序号寻址的 SAVETEXT / LOADTEXT 目录 = `Config.SavDir`（`UseSaveFolder` 真 ⇒ `sav/`），force≠0 恒 `sav/` | `FunctionMethodCreator.cs:7978` / `:8061`、`Config.cs:470-481`、`:9466-9469` |
 * | Q2 | `CHKCHARADATA` 的类别检查只在**二进制容器**里做；文本格式存档直接 UniqueCode/版本两判 | `VariableEvaluator.cs:2176-2194`（文本分支无类别）、`:2196-2201`（二进制分支有） |
 * | Q3 | `GETMETH` / `GETMETHS` 的默认值与转发实参**按需**求值（函数存在时默认值不求；未定义时转发实参不求） | `FunctionMethodCreator.cs:8553-8571` / `:8595-8613` |
 * | Q4 | `RESUMETEXTBOX` 的三个实参**不求值**（resume 分支整段不读 arguments） | `FunctionMethodCreator.cs:3079-3093` |
 * | Q5 | `HOTKEY_STATE_INIT` 的 `OmitStart=1` = 最少 1 个实参（0 个在解析期报 NotEnoughArgs） | `FunctionMethodCreator.cs:8713-8720`、`FunctionMethod.cs:183` / `:283` |
 */
class QueryAudit2Test {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    private fun mkCtx(store: VariableStore, host: BuiltinHost): BuiltinEvalContext {
        val base = BaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store, host)
    }

    private class MemOps : FileOps {
        val files = LinkedHashMap<String, String>()
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun readText(path: String): String? = files[path]
        override fun writeText(path: String, text: String, append: Boolean): Boolean {
            files[path] = if (append) (files[path] ?: "") + text else text
            return true
        }
        override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
    }

    private class QHost(
        private val ops: FileOps? = null,
        private val raw: Map<String, ByteArray> = emptyMap(),
        private val overrides: Map<String, String> = emptyMap(),
        private val funcs: Map<String, UserFunctionKind>? = null,
        private val invoke: ((String, List<Value?>) -> Value?)? = null,
        private val box: TextBoxHost? = null,
    ) : BuiltinHost {
        override val fileOps: FileOps? get() = ops
        override val configOverrides: Map<String, String> get() = overrides
        override val userFunctions: Map<String, UserFunctionKind>? get() = funcs
        override val invokeUserFunction: ((String, List<Value?>) -> Value?)? get() = invoke
        override val textBox: TextBoxHost? get() = box
        override fun readFileBytes(path: String): ByteArray? = raw[path]
    }

    private fun iv(ctx: BuiltinEvalContext, name: String, vararg a: Value?): Long =
        (ctx.callFunction(name, a.toList()) as Value.IntV).v

    private fun sv(ctx: BuiltinEvalContext, name: String, vararg a: Value?): String =
        (ctx.callFunction(name, a.toList()) as Value.StrV).v

    private fun iRef(ctx: BuiltinEvalContext, name: String, vararg a: Expr?): Long =
        (ctx.callFunctionRef(name, a.toList()) as Value.IntV).v

    private fun sRef(ctx: BuiltinEvalContext, name: String, vararg a: Expr?): String =
        (ctx.callFunctionRef(name, a.toList()) as Value.StrV).v

    /** 一被求值就抛「未定义的函数」的哨兵：用来观察求值时机。 */
    private fun boomInt() = FuncCall("BOOM", emptyList(), OperandType.INT)
    private fun boomStr() = FuncCall("BOOM", emptyList(), OperandType.STR)

    // ------------------------------------------------------------------
    // Q1：SAVETEXT / LOADTEXT —— Config.SavDir
    // ------------------------------------------------------------------

    @Test
    fun `Q1 序号寻址走 Config SavDir 而不是恒根目录`() {
        val ops = MemOps()
        val store = VariableStore()
        val ctx = mkCtx(store, QHost(ops = ops))

        // 未装配 SaveWiring、条目表出厂 false（ConfigData.cs:97）⇒ SavDir = ExeDir 根
        assertEquals(1L, iv(ctx, "SAVETEXT", Value.StrV("R"), Value.IntV(7)))
        assertTrue("txt07.txt" in ops.files, "出厂 UseSaveFolder=false ⇒ 根目录：${ops.files.keys}")
        assertEquals("R", sv(ctx, "LOADTEXT", Value.IntV(7)))

        // 会话装配 UseSaveFolder=true（语料 emuera.config:51 = YES）⇒ SavDir = ExeDir\sav\
        SaveWiring.install(store, SaveConfig(useSaveFolder = true), 20)
        assertEquals(1L, iv(ctx, "SAVETEXT", Value.StrV("V"), Value.IntV(5)))
        assertTrue("sav/txt05.txt" in ops.files, "Config.cs:471-477 ⇒ sav/：${ops.files.keys}")
        assertEquals("V", sv(ctx, "LOADTEXT", Value.IntV(5)))

        // force≠0 恒 ForceSavDir（Config.cs:470-475，与 UseSaveFolder 无关）
        assertEquals(1L, iv(ctx, "SAVETEXT", Value.StrV("F"), Value.IntV(2), Value.IntV(1)))
        assertTrue("sav/txt02.txt" in ops.files, "ForceSavDir 恒 sav/：${ops.files.keys}")
    }

    @Test
    fun `Q1 未装配 SaveWiring 时回落到条目表 UseSaveFolder`() {
        val ops = MemOps()
        val ctx = mkCtx(VariableStore(), QHost(ops = ops, overrides = mapOf("USE SAV FOLDER" to "YES")))
        assertEquals(1L, iv(ctx, "SAVETEXT", Value.StrV("Z"), Value.IntV(3)))
        assertTrue("sav/txt03.txt" in ops.files, "条目表 UseSaveFolder=YES ⇒ sav/：${ops.files.keys}")
        assertEquals("Z", sv(ctx, "LOADTEXT", Value.IntV(3)))
    }

    // ------------------------------------------------------------------
    // Q2：CHKCHARADATA 的文本格式分支
    // ------------------------------------------------------------------

    @Test
    fun `Q2 文本格式角色档不做类别检查`() {
        val ops = MemOps()
        val path = "dat/chara_OLD.dat"
        val text = "0\n0\n旧版文本存档\n"
        ops.files[path] = text
        val store = VariableStore()
        val ctx = mkCtx(store, QHost(ops = ops, raw = mapOf(path to text.toByteArray(Charsets.UTF_8))))

        // VariableEvaluator.cs:2176-2194：CreateReader 为 null（文本）⇒ UniqueCode / 版本两判即 OK
        assertEquals(0L, iv(ctx, "CHKCHARADATA", Value.StrV("OLD")))
        assertEquals("旧版文本存档", store.readStr("RESULTS", longArrayOf(0)), ":2213-2215 描述进 RESULTS")
    }

    @Test
    fun `Q2 二进制容器仍按类别拒绝且 CHARVAR 通过`() {
        val ops = MemOps()
        val normalPath = "dat/chara_BIN.dat"
        val charaPath = "dat/chara_OK.dat"
        val raw = mapOf(
            normalPath to SaveFormat.write(SaveData(SaveFileKind.NORMAL, 0L, 0L, "普通档", compressed = false)),
            charaPath to SaveFormat.write(SaveData(SaveFileKind.CHARVAR, 0L, 0L, "角色档", compressed = false)),
        )
        ops.files[normalPath] = "bin"
        ops.files[charaPath] = "bin"
        val store = VariableStore()
        val ctx = mkCtx(store, QHost(ops = ops, raw = raw))

        // :2196-2201 —— 二进制容器的类别不同 ⇒ ETC_ERROR(4)
        // 文案取官方原文（SAVE-05；lang.emuera-zhs.xml:328 `Error.CorruptedSaveData`）
        assertEquals(4L, iv(ctx, "CHKCHARADATA", Value.StrV("BIN")))
        assertEquals("存档数据已损坏", store.readStr("RESULTS", longArrayOf(0)))
        // CHARVAR 通过（:2213-2215）
        assertEquals(0L, iv(ctx, "CHKCHARADATA", Value.StrV("OK")))
        assertEquals("角色档", store.readStr("RESULTS", longArrayOf(0)))
    }

    // ------------------------------------------------------------------
    // Q3：GETMETH / GETMETHS 的惰性求值
    // ------------------------------------------------------------------

    private fun methHost() = QHost(
        funcs = mapOf(
            "M_INT" to UserFunctionKind.INT_METHOD,
            "M_STR" to UserFunctionKind.STRING_METHOD,
            "PLAIN" to UserFunctionKind.PLAIN,
        ),
        invoke = { name, _ ->
            when (name) {
                "M_INT" -> Value.IntV(42L)
                "M_STR" -> Value.StrV("zS")
                else -> null
            }
        },
    )

    @Test
    fun `Q3 GETMETH 的默认值与转发实参按需求值`() {
        val ctx = mkCtx(VariableStore(), methHost())
        // 函数存在 ⇒ 默认值（第 2 实参）不读（:8556-8571）
        assertEquals(42L, iRef(ctx, "GETMETH", StrLiteral("M_INT"), boomInt()))
        // 未定义 ⇒ 只求默认值（:8561-8565）
        assertEquals(-7L, iRef(ctx, "GETMETH", StrLiteral("NO_SUCH"), IntLiteral(-7)))
        // 未定义且默认值表达式自身出错 ⇒ 求值顺序暴露
        assertFailsWith<CodeEE> { iRef(ctx, "GETMETH", StrLiteral("NO_SUCH"), boomInt()) }
        // 未定义 + 有默认值 ⇒ 第 3 参起不读（Skip(2) 只是绑定表达式）
        assertEquals(0L, iRef(ctx, "GETMETH", StrLiteral("NO_SUCH"), IntLiteral(0), boomInt()))
        // 普通 @label ⇒ 类型先抛，默认值也不读（:8566-8569）
        assertFailsWith<CodeEE> { iRef(ctx, "GETMETH", StrLiteral("PLAIN"), boomInt()) }
        // 值路径（测试/直接值调用）读数不变
        assertEquals(42L, iv(ctx, "GETMETH", Value.StrV("M_INT"), Value.IntV(0)))
    }

    @Test
    fun `Q3 GETMETHS 的默认值与转发实参按需求值`() {
        val ctx = mkCtx(VariableStore(), methHost())
        assertEquals("zS", sRef(ctx, "GETMETHS", StrLiteral("M_STR"), boomStr()))
        assertEquals("DEF", sRef(ctx, "GETMETHS", StrLiteral("NO_SUCH"), StrLiteral("DEF")))
        assertFailsWith<CodeEE> { sRef(ctx, "GETMETHS", StrLiteral("NO_SUCH"), boomStr()) }
        assertEquals("0", sRef(ctx, "GETMETHS", StrLiteral("NO_SUCH"), StrLiteral("0"), boomStr()))
        assertFailsWith<CodeEE> { sRef(ctx, "GETMETHS", StrLiteral("PLAIN"), boomStr()) }
        // 数值方法拿到 GETMETHS ⇒ 类型错误（:8608-8611），转发实参同样不读
        assertFailsWith<CodeEE> { sRef(ctx, "GETMETHS", StrLiteral("M_INT"), StrLiteral("D"), boomStr()) }
        // 值路径读数不变
        assertEquals("zS", sv(ctx, "GETMETHS", Value.StrV("M_STR"), Value.StrV("D")))
    }

    // ------------------------------------------------------------------
    // Q4：RESUMETEXTBOX 不求值实参
    // ------------------------------------------------------------------

    private class TrackBox : TextBoxHost {
        override var text: String = ""
            private set
        var resets: Int = 0
            private set
        override fun setText(text: String) {
            this.text = text
        }
        override fun moveTextBox(x: Int, y: Int, width: Int) {}
        override fun resetTextBoxPos() {
            resets++
        }
    }

    @Test
    fun `Q4 RESUMETEXTBOX 的三个实参不求值`() {
        val box = TrackBox()
        val ctx = mkCtx(VariableStore(), QHost(box = box))
        // :3082-3087 —— resume 分支只调 ResetTextBoxPos()，arguments 一个都不读
        assertEquals(1L, iRef(ctx, "RESUMETEXTBOX", boomInt(), boomInt(), boomInt()))
        assertEquals(1, box.resets)
        // 值路径（测试/直接值调用）读数不变
        assertEquals(1L, iv(ctx, "RESUMETEXTBOX", Value.IntV(1), Value.IntV(2), Value.IntV(3)))
        assertEquals(2, box.resets)
    }

    // ------------------------------------------------------------------
    // Q5：HOTKEY_STATE_INIT 的参数个数
    // ------------------------------------------------------------------

    @Test
    fun `Q5 HOTKEY_STATE_INIT 需要恰好一个实参`() {
        val ctx = mkCtx(VariableStore(), QHost())
        assertEquals(0L, iv(ctx, "HOTKEY_STATE_INIT", Value.IntV(2)))
        // FunctionMethod.cs:183 —— OmitStart=1 是必需的前导位数；:283 NotEnoughArgs
        assertFailsWith<CodeEE> { iv(ctx, "HOTKEY_STATE_INIT") }
        // 槽位只有 1 个（:8713-8720）⇒ TooManyFuncArgs（:279）
        assertFailsWith<CodeEE> { iv(ctx, "HOTKEY_STATE_INIT", Value.IntV(1), Value.IntV(0)) }
    }
}
