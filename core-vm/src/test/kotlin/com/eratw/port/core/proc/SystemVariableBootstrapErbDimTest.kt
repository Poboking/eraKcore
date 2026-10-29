package com.eratw.port.core.proc

import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CMP-89：**ERB 函数外 `#DIM`** 必须与原版一样「丢弃 + 留痕」。
 *
 * 原版：ERB 里 `#` 行只在「紧跟 `@` 函数标签之后」的头部块合法；`logicalLine2` 不是
 * `FunctionLabelLine` 时 `ParserMediator.Warn(Lang.Error.InvalidSharp, …, 1)` 后**丢弃**
 * （`reference/emuera-cs/MinorShift.Emuera.Runtime.Script.Loader/ErbLoader.cs:413-423`，
 * 文案 `Lang.cs:2430`）。全局用户变量只从 ERH 来（`Process.cs:309/:313`）。
 *
 * 本移植的缺陷面：`compile/CorpusCompile.kt:295-307` 把 `curKey == null`（= 函数外）的
 * 原文一律塞进 `globalDimDecls`，最终经 [SystemVariableBootstrap.install] 当全局变量注册
 * ⇒ 原版丢弃的行在本侧变成可用变量（静默、无告警）。
 *
 * 红→绿记录：
 * 1. 先把夹具钉在**注册面**（`install(store, [RawDeclaration("#DIM ERB_TOPLEVEL, 5", "x.ERB", 7)])`
 *    期望 `declared == 0`）——修复前实测 `declared=1`、变量真实在册 ⇒ 红，证明缺陷存在；
 * 2. 同一条闸若真落在注册面，会把**合成装置**的 `.ERB` 来源一起拦掉：
 *    `GameSessionTest` / `GotoTitleTest` / `BootMetadataLazyTest` 实测 10 个用例红
 *    （它们拿 `TEST.ERB` / `ERB/main.ERB` 当全局声明来源）；
 * 3. 故闸落在**收集点**（`CorpusCompile:295-307`，归 L03/L15 面）：本路把判据与文案收成
 *    单源 [SystemVariableBootstrap.filterErbFunctionDimDeclarations]，并用本文件的用例
 *    钉住「丢弃 + 留痕 + 名字集」。收集点接线见交付回执。
 *
 * 语料实测：跳过 `[SKIPSTART]` 区后，ERB 函数外 `#DIM/#DIMS` 命中 **0** 条 ⇒ 接线后对
 * 现有语料是零行为差，纯收紧。
 */
class SystemVariableBootstrapErbDimTest {

    private fun newStore() = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))

    private fun decl(raw: String, file: String, line: Int) =
        SystemVariableBootstrap.RawDeclaration(raw, file, line)

    @Test
    fun `ERB 函数外 dim 在收集点丢弃并留痕`() {
        val sink = ArrayList<SystemVariableBootstrap.Failure>()
        val kept = SystemVariableBootstrap.filterErbFunctionDimDeclarations(
            listOf(
                decl("#DIM ERH_OK, 5", "DIM.ERH", 1),
                decl("#DIM ERB_BAD, 5", "fromEN/QOL/Util.ERB", 7),
                decl("#DIMS ERB_BAD_S, 3", "scripts/sub/util.erb", 9),
                decl("#DIM TRIAGE_OK, 5", "TRIAGE", 2),
            ),
            sink,
        )
        println(
            "CMP-89 读数：保留=${kept.map { it.file }} 丢弃=${sink.map { it.file + ":" + it.line }} " +
                "文案=${sink.firstOrNull()?.message}",
        )
        // 名字集：只有 ERH 与无扩展名的合成来源保留（ERB 大小写都算 ERB）
        assertEquals(listOf("DIM.ERH", "TRIAGE"), kept.map { it.file }, "保留集合")
        assertEquals(2, sink.size, "每条丢弃都要留痕（不许静默丢）")
        assertEquals(listOf("fromEN/QOL/Util.ERB", "scripts/sub/util.erb"), sink.map { it.file })
        assertEquals(7, sink[0].line, "留痕保留源行号")
        assertEquals(9, sink[1].line)
        for (f in sink) {
            assertTrue(f.message.contains("InvalidSharp"), "留痕要带原版错误名：${f.message}")
            assertTrue(f.message.contains("ErbLoader.cs:413-423"), "留痕要带 reference 行号：${f.message}")
            assertTrue(f.message.contains("ERH"), "留痕要说明全局只从 ERH 来：${f.message}")
            assertTrue(f.raw.isNotBlank(), "留痕保留原文（定位用）")
        }
    }

    @Test
    fun `过滤后 install 只注册 ERH 来源`() {
        val sink = ArrayList<SystemVariableBootstrap.Failure>()
        val kept = SystemVariableBootstrap.filterErbFunctionDimDeclarations(
            listOf(
                decl("#DIM ERH_OK, 5", "DIM.ERH", 1),
                decl("#DIM ERB_BAD, 5", "fromEN/QOL/Util.ERB", 7),
            ),
            sink,
        )
        val store = newStore()
        val r = SystemVariableBootstrap.install(store, kept, DimParseConfig(systemSaveInBinary = true))
        assertEquals(1, r.declared, "只有 ERH 那条注册")
        assertEquals(0, r.failures.size, "注册面不该再出失败（丢弃已在收集点留痕）")
        assertNotNull(store.userVar("ERH_OK"), "ERH 全局声明照常注册")
        assertNull(store.userVar("ERB_BAD"), "ERB 函数外声明不许在册")
        assertEquals(1, sink.size, "收集点留痕一条")
    }

    @Test
    fun `判据只看扩展名且大小写不敏感`() {
        assertTrue(SystemVariableBootstrap.isDiscardedErbDimSource("a/b.ERB"))
        assertTrue(SystemVariableBootstrap.isDiscardedErbDimSource("a\\b.erb"))
        assertTrue(SystemVariableBootstrap.isDiscardedErbDimSource("x.Erb"))
        assertTrue(!SystemVariableBootstrap.isDiscardedErbDimSource("DIM.ERH"))
        assertTrue(!SystemVariableBootstrap.isDiscardedErbDimSource("TRIAGE"))
        assertTrue(!SystemVariableBootstrap.isDiscardedErbDimSource("a/erb.txt"))
    }
}
