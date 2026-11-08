package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * L4 稳定性：宿主 IO 异常不得冒到语句层（原版两条 catch 分支）。
 *
 * | 判据 | 原版权威 | 改前行状 |
 * |---|---|---|
 * | `LOADTEXT` 读/解码异常 ⇒ 空串 | `FunctionMethodCreator.cs:8081-8087`（`try { File.ReadAllText } catch { return "" }`） | `FileOps.readText` 若直接抛，异常穿出内建函数并中止脚本 |
 * | `readText` 给 null ⇒ 空串 | 同上（打不开 = 抛异常，原版同折空串） | 已是空串（本次加断言防回退） |
 * | `CHKCHARADATA` 字节读不出 ⇒ `ETC_ERROR(4)` + `Error.LoadError` | `VariableEvaluator.cs:2226-2229`（`catch (Exception)` → `ETC_ERROR` + `Lang.Error.LoadError`） | 抛 `CodeEE("需要宿主接线…")`，红字上屏并中止本条脚本 |
 * | 成功读取的文本仍剥 BOM / 去 CR | `FunctionMethodCreator.cs:8082`、`:8088` | 本次不动，加断言钉住 |
 *
 * 文案「加载时发生错误」与 `CHKDATA` 共用 `SaveDataProbe.MES_LOAD_ERROR`
 * （`core/save/SaveDataProbe.kt:118`）。
 */
class StabilityL4HostIoTest {

    // ------------------------------------------------------------------
    // 装置（纯 JVM）
    // ------------------------------------------------------------------

    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
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

    /** `readText` 直接抛的宿主：权限 / 坏盘 / 实现缺陷的统一样子。 */
    private class ThrowingReadOps : FileOps {
        override fun exists(path: String): Boolean = true
        override fun readText(path: String): String? = throw IllegalStateException("模拟读盘异常：$path")
        override fun writeText(path: String, text: String, append: Boolean): Boolean = false
        override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
    }

    private class IOHost(
        private val ops: FileOps?,
        private val read: (String) -> ByteArray? = { null },
    ) : BuiltinHost {
        override val fileOps: FileOps? get() = ops
        override fun readFileBytes(path: String): ByteArray? = read(path)
    }

    private fun ctx(store: VariableStore, host: BuiltinHost): BuiltinEvalContext {
        val base = BaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store, host)
    }

    private fun loadText(h: BuiltinEvalContext, path: String): String =
        (h.callFunction("LOADTEXT", listOf(Value.StrV(path))) as Value.StrV).v

    private fun chkChara(store: VariableStore, h: BuiltinEvalContext, name: String): Long =
        (h.callFunction("CHKCHARADATA", listOf(Value.StrV(name))) as Value.IntV).v

    // ------------------------------------------------------------------
    // LOADTEXT
    // ------------------------------------------------------------------

    /** :8081-8087 —— 宿主读盘抛异常时折成空串，异常不穿出内建函数。 */
    @Test
    fun `LOADTEXT 宿主读盘抛异常时返回空串`() {
        val h = ctx(VariableStore(), IOHost(ThrowingReadOps()))
        assertEquals("", loadText(h, "boom.txt"), "原版 catch 把任何读/解码异常都折成空串（:8084-8087）")
    }

    /** :8076-8079 —— 宿主按契约给 null（文件不存在 / 打不开）同样是空串。 */
    @Test
    fun `LOADTEXT 读不到给空串`() {
        val h = ctx(VariableStore(), IOHost(MemOps()))
        assertEquals("", loadText(h, "missing.txt"))
    }

    /** :8082 / :8088 —— 成功分支不变：剥唯一前导 BOM、去掉所有 CR。 */
    @Test
    fun `LOADTEXT 成功时仍剥 BOM 去 CR`() {
        val ops = MemOps()
        ops.files["note.txt"] = "\uFEFFa\r\nb"
        val h = ctx(VariableStore(), IOHost(ops))
        assertEquals("a\nb", loadText(h, "note.txt"))
    }

    // ------------------------------------------------------------------
    // CHKCHARADATA
    // ------------------------------------------------------------------

    /** :2226-2229 —— 文件在、字节读不出（宿主抛）⇒ 码 4 + 「加载时发生错误」，不抛 CodeEE。 */
    @Test
    fun `CHKCHARADATA 宿主读字节抛异常时折成码4`() {
        val ops = MemOps()
        ops.files["dat/chara_BOOM.dat"] = "bin"
        val store = VariableStore()
        val h = ctx(store, IOHost(ops) { throw IllegalStateException("模拟二进制读异常") })
        assertEquals(4L, chkChara(store, h, "BOOM"), "原版 catch (Exception) ⇒ ETC_ERROR（:2226-2229）")
        assertEquals("加载时发生错误", store.readStr("RESULTS", longArrayOf(0)), "Error.LoadError 中文文案")
    }

    /** 同一分支的 null 形态（宿主没装二进制读）：与父类缺省实现同路，码与文案必须一致。 */
    @Test
    fun `CHKCHARADATA 字节读为 null 时折成码4`() {
        val ops = MemOps()
        ops.files["dat/chara_NULL.dat"] = "bin"
        val store = VariableStore()
        val h = ctx(store, IOHost(ops))
        assertEquals(4L, chkChara(store, h, "NULL"))
        assertEquals("加载时发生错误", store.readStr("RESULTS", longArrayOf(0)))
    }
}
