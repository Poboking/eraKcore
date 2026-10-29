package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 文本文件函数族测试（CONTRACT-M3-A §5 硬判据）。
 *
 * 判据：`fixtures/golden/baseline/pc-golden-root.txt:483-486`
 * （IO-TEXTFILE 节：SAVETEXT=1 / LOADTEXT=[GOLDEN-IO-TEST] / EXISTFILE=1 MISS=0）
 * + `fixtures/golden/GOLDEN_08_IO.ERB:23-27` 的语句序列。IO 经内存假 [FileOps]。
 */

/** 内存 FileOps：按原版口径——路径即键，目录用 "dir/" 前缀的存在性模拟。 */
private class FakeFileOps : FileOps {
    val files = mutableMapOf<String, String>()
    val dirs = mutableSetOf<String>()

    override fun exists(path: String): Boolean = files.containsKey(path) || path in dirs

    override fun readText(path: String): String? = files[path]

    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        if (append && files.containsKey(path)) {
            files[path] = files[path] + text
        } else {
            files[path] = text
        }
        return true
    }

    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> {
        if (dir !in dirs) return emptyList()
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val regex = globToRegex(pattern)
        return files.keys
            .filter { it.startsWith(prefix) }
            .filter { recursive || !it.substring(prefix.length).contains("/") } // 非递归=仅直接子项
            .filter { regex.matches(it.substring(prefix.length).substringAfterLast('/')) }
            .sorted()
    }

    /** 原版 `*` 通配（Directory.EnumerateFiles 的 searchPattern 语义，仅 * 无 ?）。 */
    private fun globToRegex(pattern: String): Regex =
        Regex("^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$")
}

private class FoBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("FoBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun foHost(ops: FileOps?) = object : BuiltinHost {
    override val fileOps: FileOps? = ops
}

private fun foCtxFor(ops: FileOps?): BuiltinEvalContext {
    val store = VariableStore()
    val base = FoBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, foHost(ops))
}

private fun foIv(ctx: BuiltinEvalContext, name: String, vararg args: Value): Long =
    (ctx.callFunction(name, args.toList()) as Value.IntV).v

/** evalRef 通道（ENUMFILES 等引用型函数的正规调用路径，对应表达式层 FuncCall）。 */
private fun foIvRef(ctx: BuiltinEvalContext, name: String, vararg args: com.eratw.port.core.expr.Expr?): Long =
    (ctx.callFunctionRef(name, args.toList()) as Value.IntV).v

private fun foSv(ctx: BuiltinEvalContext, name: String, vararg args: Value): String =
    (ctx.callFunction(name, args.toList()) as Value.StrV).v

private fun foS(v: String) = Value.StrV(v)
private fun foI(v: Long) = Value.IntV(v)

class FileMethodsTest {

    // ---- 硬判据：pc-golden-root.txt:483-486 ----

    @Test
    fun `硬判据 SAVETEXT LOADTEXT EXISTFILE 逐行复现 PC 基线`() {
        val ops = FakeFileOps()
        val ctx = foCtxFor(ops)
        // pc-golden-root.txt:483 SAVETEXT=1（GOLDEN_08_IO.ERB:23-24）
        assertEquals(1L, foIv(ctx, "SAVETEXT", foS("GOLDEN-IO-TEST"), foS("GOLDEN_TEST.txt")))
        // :484 LOADTEXT=[GOLDEN-IO-TEST]（GOLDEN_08_IO.ERB:25-26）
        assertEquals("GOLDEN-IO-TEST", foSv(ctx, "LOADTEXT", foS("GOLDEN_TEST.txt")))
        // :485 EXISTFILE=1 MISS=0（GOLDEN_08_IO.ERB:27）
        assertEquals(1L, foIv(ctx, "EXISTFILE", foS("GOLDEN_TEST.txt")))
        assertEquals(0L, foIv(ctx, "EXISTFILE", foS("NO_SUCH_FILE_XYZ.txt")))
    }

    @Test
    fun `存档序号寻址默认落根目录 force 进 sav 子目录`() {
        // GetSaveDataPathText（FunctionMethodCreator.cs:9466-9469）+ 默认配置
        // UseSaveFolder=false（ConfigData.cs:97）⇒ force=0 根目录、force≠0 sav/ 子目录
        val ops = FakeFileOps()
        val ctx = foCtxFor(ops)
        assertEquals(1L, foIv(ctx, "SAVETEXT", foS("ROOT-SAVE"), foI(0)))
        assertEquals("ROOT-SAVE", foSv(ctx, "LOADTEXT", foI(0)))
        assertEquals(1L, foIv(ctx, "SAVETEXT", foS("FORCE-SAVE"), foI(3), foI(1)))
        assertEquals("FORCE-SAVE", foSv(ctx, "LOADTEXT", foI(3), foI(1)))
        assertTrue("txt00.txt" in ops.files)
        assertTrue("sav/txt03.txt" in ops.files)
        // 序号越界：SAVETEXT → 0（:7973-7976），LOADTEXT → ""（:8056-8059）
        assertEquals(0L, foIv(ctx, "SAVETEXT", foS("X"), foI(-1)))
        assertEquals("", foSv(ctx, "LOADTEXT", foI(-1)))
    }

    @Test
    fun `扩展名白名单外 SAVETEXT 换 txt LOADTEXT 返回空`() {
        // :7987-7991 / :8070-8074（Config.ValidExtension 默认 ["txt"]）
        val ops = FakeFileOps()
        val ctx = foCtxFor(ops)
        assertEquals(1L, foIv(ctx, "SAVETEXT", foS("DATA"), foS("out.csv")))
        assertTrue("out.txt" in ops.files)
        assertEquals("", foSv(ctx, "LOADTEXT", foS("out.csv"))) // 白名单外 → ""
        assertEquals("", foSv(ctx, "LOADTEXT", foS("missing.txt"))) // 不存在 → ""
    }

    @Test
    fun `路径安全 GetValidPath 拒绝根路径与上级跳转`() {
        // Utils.cs:473-488：'/ '→'\\'、剔除 "..\"、盘符/UNC 根 → null
        val ops = FakeFileOps()
        val ctx = foCtxFor(ops)
        assertEquals(0L, foIv(ctx, "EXISTFILE", foS("C:/Windows/win.ini"))) // 盘符根 → null → 0
        assertEquals(0L, foIv(ctx, "EXISTFILE", foS("\\\\server\\share\\x.txt"))) // UNC → null → 0
        // "..\\" 被剔除后按普通相对路径处理
        assertEquals(0L, foIv(ctx, "EXISTFILE", foS("..\\x.txt")))
    }

    @Test
    fun `LOADTEXT 剥离 CR`() {
        // :8088 text.Replace("\r", "")
        val ops = FakeFileOps()
        ops.files["crlf.txt"] = "A\r\nB\r\n"
        val ctx = foCtxFor(ops)
        assertEquals("A\nB\n", foSv(ctx, "LOADTEXT", foS("crlf.txt")))
    }

    @Test
    fun `ENUMFILES 写入 RESULTS 并返回个数 目录不存在返回 -1`() {
        // :352-373；GOLDEN_08_IO.ERB:64,66（FNUM1/FNUM2 两参/三参形态）
        val ops = FakeFileOps()
        ops.dirs += "dat"
        ops.files["dat/chara_GOLDEN_01.xml"] = "a"
        ops.files["dat/chara_GOLDEN_02.xml"] = "b"
        ops.files["dat/other.txt"] = "c"
        val ctx = foCtxFor(ops)
        assertEquals(2L, foIvRef(ctx, "ENUMFILES", com.eratw.port.core.expr.StrLiteral("dat"), com.eratw.port.core.expr.StrLiteral("chara_GOLDEN_*")))
        // 默认写 RESULTS（:370）
        val store = (ctx as BuiltinEvalContext).store
        assertEquals("dat/chara_GOLDEN_01.xml", store.readStr("RESULTS", longArrayOf(0)))
        assertEquals("dat/chara_GOLDEN_02.xml", store.readStr("RESULTS", longArrayOf(1)))
        // recursive=0 显式传入同样两参口径
        assertEquals(2L, foIvRef(ctx, "ENUMFILES", com.eratw.port.core.expr.StrLiteral("dat"), com.eratw.port.core.expr.StrLiteral("chara_GOLDEN_*"), com.eratw.port.core.expr.IntLiteral(0)))
        // 目录不存在 → -1（:355-357）
        assertEquals(-1L, foIvRef(ctx, "ENUMFILES", com.eratw.port.core.expr.StrLiteral("no_such_dir")))
    }

    @Test
    fun `ENUMFILES 自定义目标数组 仅写 min(个数,长度)`() {
        val ops = FakeFileOps()
        ops.dirs += "ERB"
        ops.files["ERB/GOLDEN_00.ERB"] = "a"
        ops.files["ERB/GOLDEN_01.ERB"] = "b"
        ops.files["ERB/GOLDEN_02.ERB"] = "c"
        val ctx = foCtxFor(ops)
        // STR 是 1D 字符串数组（SystemLayout str1D）；写前 3 个中前 2 个（长度所限）
        val ref = com.eratw.port.core.expr.VarRef(
            name = "STR", indices = listOf(), isNoArg = true,
            operandType = com.eratw.port.core.expr.OperandType.STR,
        )
        val expr = listOf<com.eratw.port.core.expr.Expr?>(
            com.eratw.port.core.expr.StrLiteral("ERB"),
            com.eratw.port.core.expr.StrLiteral("GOLDEN_*"),
            null,
            ref,
        )
        val n = (ctx.callFunctionRef("ENUMFILES", expr) as Value.IntV).v
        val store = ctx.store
        val len = store.getLength("STR", -1).toLong()
        assertEquals(minOf(3L, len), n)
        assertEquals("ERB/GOLDEN_00.ERB", store.readStr("STR", longArrayOf(0)))
    }

    @Test
    fun `宿主未接线 FileOps 时抛 CodeEE 不静默`() {
        val ctx = foCtxFor(null)
        assertFailsWith<CodeEE> { foIv(ctx, "EXISTFILE", foS("x.txt")) }
        assertFailsWith<CodeEE> { foIv(ctx, "SAVETEXT", foS("x"), foS("x.txt")) }
        assertFailsWith<CodeEE> { foSv(ctx, "LOADTEXT", foS("x.txt")) }
        assertFailsWith<CodeEE> { foIv(ctx, "ENUMFILES", foS(".")) }
    }

    @Test
    fun `注册表核账 文件函数族与GETCOLOR族不再有占位`() {
        for (name in listOf("SAVETEXT", "LOADTEXT", "EXISTFILE", "ENUMFILES")) {
            val m = Builtins.registry[name]
            assertTrue(m != null && (m.eval != null || m.evalRef != null), "$name 应已实现")
        }
        val gc = Builtins.registry["GETCOLOR"]
        assertTrue(gc != null && (gc.eval != null || gc.evalRef != null), "GETCOLOR 应已实现")
    }
}
