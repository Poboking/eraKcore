package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CONFIG-ENCODE（M4-F f-configwire）：`LOADTEXT`/`SAVETEXT` 的扩展名白名单改从
 * [BuiltinHost.configOverrides] 的 `VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT` 一行读
 * （注册 spec: `ConfigData.cs:120-127`；判定 spec: `FunctionMethodCreator.cs:8070-8074` 的
 * LOADTEXT 白名单、`:7987-7991` 的 SAVETEXT 换名分支）。钉四件事：
 *
 * 1. 白名单从配置读：逗号切、去空格、小写比较（本波合同口径；原版只把小写化后的扩展名
 *    `Contains` 原文表项，spec: `FunctionMethodCreator.cs:8072`，语料
 *    `emuera.config:73` 全小写 ⇒ 两种口径无观测差）；
 * 2. 缺省（宿主未接线/键缺失/值为空）仍是 txt-only（spec: `ConfigData.cs:122-127` 的 ["txt"]）；
 * 3. 白名单外扩展名的两侧行为：LOADTEXT 返回 ""（:8070-8074）、SAVETEXT 换 .txt（:7990）；
 * 4. force 参数走存档序号寻址（`sav/` 子目录，`GetSaveDataPathText` :9466-9469），
 *    不进白名单分支（:7973-7978 / :8056-8061）。
 *
 * 末两条是端到端：`RootedBuiltinHost` 从临时目录的 `emuera.config`（BOM + 语料形状键行）
 * 读出的覆盖表真喂到函数层 —— 覆盖 BootHosts → HostMethods 整条接线；
 * 文件缺失/行级解析中止则回落空表（本波合同口径，不抛）。
 */

// ---- 夹具：内存 FileOps + 最小 EvalContext（形状同 FileMethodsTest，名字前缀 Lx 防撞）----

private class LxFileOps : FileOps {
    val files = mutableMapOf<String, String>()

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun readText(path: String): String? = files[path]

    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        files[path] = if (append && files.containsKey(path)) files.getValue(path) + text else text
        return true
    }

    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

private class LxBase : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("LxBase 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

/** 宿主只接本路关心的两项：fileOps + configOverrides。 */
private fun lxHost(ops: FileOps, overrides: Map<String, String>): BuiltinHost = object : BuiltinHost {
    override val fileOps: FileOps = ops
    override val configOverrides: Map<String, String> get() = overrides
}

private fun lxCtx(ops: FileOps, overrides: Map<String, String> = emptyMap()): BuiltinEvalContext {
    val store = VariableStore()
    val base = LxBase()
    base.variables = store
    return BuiltinEvalContext(base, store, lxHost(ops, overrides))
}

private fun lxSv(ctx: BuiltinEvalContext, name: String, vararg args: Value): String =
    (ctx.callFunction(name, args.toList()) as Value.StrV).v

private fun lxIv(ctx: BuiltinEvalContext, name: String, vararg args: Value): Long =
    (ctx.callFunction(name, args.toList()) as Value.IntV).v

private fun lxS(v: String) = Value.StrV(v)
private fun lxI(v: Long) = Value.IntV(v)

/** 配置键的英文标签形（`AConfigItem.EngText` 注册期大写，spec: `AConfigItem.cs:22-23`）。 */
private const val EXT_KEY = "VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT"

private fun lxDeleteTree(root: Path) {
    runCatching {
        Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
            .forEach { Files.deleteIfExists(it) }
    }
}

class LoadTextValidExtensionTest {

    @Test
    fun `白名单从配置读 xml 与 json 放行 写入同扩展名不换名`() {
        val ops = LxFileOps()
        ops.files["save.xml"] = "<a/>"
        ops.files["cfg.json"] = "{\"k\":1}"
        val ctx = lxCtx(ops, mapOf(EXT_KEY to "xml,txt,json"))
        assertEquals("<a/>", lxSv(ctx, "LOADTEXT", lxS("save.xml")))   // 语料 :73 场景（RM_XML_TEMP）
        assertEquals("{\"k\":1}", lxSv(ctx, "LOADTEXT", lxS("cfg.json")))
        // SAVETEXT 目标扩展名在表内 ⇒ 不换名（:7987-7991 的负分支）
        assertEquals(1L, lxIv(ctx, "SAVETEXT", lxS("X"), lxS("out.xml")))
        assertTrue("out.xml" in ops.files)
        assertTrue("out.txt" !in ops.files)
    }

    @Test
    fun `白名单外扩展名 LOADTEXT 返回空 SAVETEXT 换 txt 无扩展名同口径`() {
        val ops = LxFileOps()
        ops.files["pic.png"] = "png"
        val ctx = lxCtx(ops, mapOf(EXT_KEY to "xml,txt,json"))
        assertEquals("", lxSv(ctx, "LOADTEXT", lxS("pic.png")))        // :8070-8074
        assertEquals(1L, lxIv(ctx, "SAVETEXT", lxS("Y"), lxS("pic.png")))
        assertTrue("pic.txt" in ops.files)                             // :7990 Path.ChangeExtension
        // 无扩展名（Path.HasExtension == false ⇒ item = ""）同样被拒；写侧补 .txt
        ops.files["plain"] = "p"
        assertEquals("", lxSv(ctx, "LOADTEXT", lxS("plain")))
        assertEquals(1L, lxIv(ctx, "SAVETEXT", lxS("Z"), lxS("plain2")))
        assertTrue("plain2.txt" in ops.files)
    }

    @Test
    fun `缺省 txt-only 未接线 键缺失或值为空时行为与收口前一致`() {
        val ops = LxFileOps()
        ops.files["a.xml"] = "x"
        ops.files["b.txt"] = "t"
        val ctx = lxCtx(ops) // 空 overrides = 宿主未接线
        assertEquals("", lxSv(ctx, "LOADTEXT", lxS("a.xml")))          // 缺省 ["txt"]（ConfigData.cs:122-127）
        assertEquals("t", lxSv(ctx, "LOADTEXT", lxS("b.txt")))
        assertEquals(1L, lxIv(ctx, "SAVETEXT", lxS("X"), lxS("c.xml")))
        assertTrue("c.txt" in ops.files)
        // 键在但值为空白：原版 TryParse 空串直接 false 不落值（ConfigItem.cs:89-95）⇒ 保留缺省
        val ctxBlank = lxCtx(ops, mapOf(EXT_KEY to "  "))
        assertEquals("", lxSv(ctxBlank, "LOADTEXT", lxS("a.xml")))
    }

    @Test
    fun `键形大小写不敏感 表项去空格并小写比较`() {
        val ops = LxFileOps()
        ops.files["A.XML"] = "up"
        // 键以小写英文、值带空格与大写：键 Trim + 大写化后匹配（ConfigData.cs:262-284），
        // 值逐段 Trim（ConfigItem.cs:215-225）+ 本波合同的小写比较
        val ctx = lxCtx(ops, mapOf("valid extensions for loadtext and savetext" to " XML , TXT , Json "))
        assertEquals("up", lxSv(ctx, "LOADTEXT", lxS("A.XML")))        // 扩展名原文 ToLower（:8072）
        // 日文标签形同命中（ConfigData.cs:120-127 的 Text 形，注册期 ToUpper 后仍是原文）
        val ctxJp = lxCtx(ops, mapOf("LOADTEXTとSAVETEXTで使える拡張子" to "xml"))
        assertEquals("up", lxSv(ctxJp, "LOADTEXT", lxS("A.XML")))
    }

    @Test
    fun `force 参数走序号寻址 sav 子目录 不进白名单分支`() {
        // GetSaveDataPathText（:9466-9469）= {dir}txt{index:00}.txt；默认 UseSaveFolder=false
        // （ConfigData.cs:97）⇒ force=0 根目录 / force≠0 sav/（Config.cs:468-480）
        val ops = LxFileOps()
        val ctx = lxCtx(ops, mapOf(EXT_KEY to "xml")) // 白名单里没有 txt，序号寻址不受它影响
        assertEquals(1L, lxIv(ctx, "SAVETEXT", lxS("S0"), lxI(0)))
        assertEquals(1L, lxIv(ctx, "SAVETEXT", lxS("S1"), lxI(1), lxI(1)))
        assertTrue("txt00.txt" in ops.files)      // :7978 force=0 分支
        assertTrue("sav/txt01.txt" in ops.files)  // :7980 force≠0 分支
        assertEquals("S0", lxSv(ctx, "LOADTEXT", lxI(0)))        // :8061
        assertEquals("S1", lxSv(ctx, "LOADTEXT", lxI(1), lxI(1)))
        // 序号越界（:7973-7976 / :8056-8059）
        assertEquals(0L, lxIv(ctx, "SAVETEXT", lxS("X"), lxI(-1)))
        assertEquals("", lxSv(ctx, "LOADTEXT", lxI(-1)))
    }

    @Test
    fun `RootedBuiltinHost 读 emuera-config 成覆盖表并真喂到函数层`() {
        val root = Files.createTempDirectory("eratw-configwire")
        try {
            // 语料形状（BOM 文件首行 + 英文标签 + 一条未匹配键 + 注释行）：
            // 口径与 EmueraConfigGoldenTest 的语料外部读数同源，缩成 5 行。
            val cfg = "\uFEFFUSE SAV FOLDER:YES\n" +
                "DEFAULT ANSI ENCODING:CHINESE_HANS\n" +
                "VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT:xml,txt,json\n" +
                "ZZZ NOT A CONFIG KEY:1\n" +
                "; 注释行\n"
            Files.write(root.resolve("emuera.config"), cfg.toByteArray(Charsets.UTF_8))
            Files.writeString(root.resolve("data.xml"), "<sprites/>")
            val host = RootedBuiltinHost(root)
            // BOM 首行照样命中（stripBom 只在第 0 行生效，EmueraConfigParser.kt:131；
            // 语料同形带 BOM，EmueraConfigGoldenTest:62-64 有外部读数）
            assertEquals("YES", host.configOverrides["USE SAV FOLDER"])
            assertEquals("CHINESE_HANS", host.configOverrides["DEFAULT ANSI ENCODING"])
            assertEquals(
                "xml,txt,json",
                host.configOverrides["VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT"],
            )
            assertNull(host.configOverrides["ZZZ NOT A CONFIG KEY"]) // 未匹配键不进表
            assertEquals(3, host.configOverrides.size)
            // 函数层：LOADTEXT 直接吃这张表 + 真文件读取（RootedFileOps）
            val store = VariableStore()
            val base = LxBase()
            base.variables = store
            val ctx = BuiltinEvalContext(base, store, host)
            assertEquals("<sprites/>", lxSv(ctx, "LOADTEXT", lxS("data.xml")))
            assertEquals("", lxSv(ctx, "LOADTEXT", lxS("missing.xml"))) // 文件不存在 → ""（:8076-8079）
        } finally {
            lxDeleteTree(root)
        }
    }

    @Test
    fun `RootedBuiltinHost 配置缺失或解析中止都回落空表`() {
        val none = Files.createTempDirectory("eratw-configwire-none")
        val bad = Files.createTempDirectory("eratw-configwire-bad")
        try {
            assertTrue(RootedBuiltinHost(none).configOverrides.isEmpty()) // 文件不存在 → emptyMap
            // 行级解析中止（MAX HISTORY LOG LINES:abc 走 int.TryParse 失败抛 CodeEE，
            // ConfigData.cs:579-586）：本波合同口径整表空（原版是保留已解析前缀，
            // 见 BootHosts.loadConfigOverrides 的 TODO(verify)）
            Files.writeString(
                bad.resolve("emuera.config"),
                "USE SAV FOLDER:YES\nMAX HISTORY LOG LINES:abc\nDEFAULT ANSI ENCODING:CHINESE_HANS\n",
            )
            assertTrue(RootedBuiltinHost(bad).configOverrides.isEmpty())
        } finally {
            lxDeleteTree(none)
            lxDeleteTree(bad)
        }
    }
}