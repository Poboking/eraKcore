package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.erbcc.compile.CorpusCompile
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DimVarSizes] 的 VARSIZE 尺寸快照读数（M4-M 加派路 `m-dimfold-wire`，起点 = main@e2d969f）。
 *
 * 断言的是**快照本身**的读数与「快照 → 折叠面 → #DIM 解析」这条接线，不是折叠面算得对不对
 * （折叠面由 `DimFoldTest` / `DimFailTriageTest` 钉）：
 * 1. 出厂口径：不读 CSV 时 `PALAM` / `RELATION` = 100（[VariableSizes] 的
 *    `charaInt1D = IntArray(84) { 100 }`，core/vars/VariableSizes.kt:76）。
 * 2. 装载时点口径：读语料 `CSV/VariableSize.csv` 后 `PALAM` = 200、`RELATION` = 200
 *    （语料该文件 :99 / :104 两行）。原版装载序 spec: `Process.cs:292-293`（建 ConstantData
 *    并 LoadData，VariableSize.csv 在此生效）→ `:300-301`（建 IdentifierDictionary）→
 *    `:309/:313`（才开始装 ERH），所以 `VARSIZE` 折出来的必须是 **CSV 改写之后**的值；
 *    取数走 `GetVariableToken(...).GetLength(num)`（spec: FunctionMethodCreator.cs:3443/:3453）。
 * 3. 不入册 / 折不出：REF 用户变量（spec: FunctionMethodCreator.cs:3466-3469 `IsReference`
 *    ⇒ `UniqueRestructure` 返 false）与伪变量（`CHARANUM` 等，`GetLength` 一律抛 CodeEE，
 *    spec: VariableData.cs:827-830；快照里是空 dims ⇒ 折叠面取不到值）。
 *
 * 非空转读数（本路接线回退即红）：最后一条用例把快照经 `withPureStaticDimFolders` 挂上后
 * 解析语料三条 VARSIZE 缺口（G3 / P4 / P5），断言的是**快照里的数**（200）而不是常量 200 ——
 * 把接线改成 `systemSnapshot(null)` 就得到 100 ⇒ 该用例红；把接线整个摘掉 ⇒ 同一条用例
 * 在未注入对照处红（`CodeEE` 不再抛、注册数变 0）。
 */
class DimVarSizesSnapshotTest {

    /** 语料 `CSV` 目录（与装置 `RealCorpusCompile` 用的同一表达式：`<ERB 目录>.parent/CSV`）。 */
    private fun corpusCsvDir(): Path? {
        val corpus = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() }
        Assumptions.assumeTrue(
            corpus != null && File(corpus).isDirectory,
            "未设 ERATW_CORPUS：跳过语料 CSV 口径用例",
        )
        val root = Path.of(corpus!!)
        val csv = root.parent?.resolve("CSV") ?: root
        Assumptions.assumeTrue(
            File(csv.toString()).isDirectory,
            "语料旁没有 CSV 目录：$csv",
        )
        return csv
    }

    private fun parse(raw: String, isPrivate: Boolean, c: DimParseConfig): DimSpec {
        val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw))
            ?: error("不是 #DIM/#DIMS 声明: $raw")
        return DimParser.parse(split.first, split.second, isPrivate, c)
    }

    // ------------------------------------------------------------------
    // 1) 出厂口径
    // ------------------------------------------------------------------

    @Test
    fun 不读csv时快照是出厂尺寸() {
        val snap = DimVarSizes.systemSnapshot(null)
        // VariableSizes.kt:76 charaInt1D = IntArray(84){100}；PALAM/RELATION 都是角色 1 维整数
        assertEquals(listOf(100L), snap["PALAM"]?.toList(), "出厂 PALAM 尺寸")
        assertEquals(listOf(100L), snap["RELATION"]?.toList(), "出厂 RELATION 尺寸")
        // 角色 2 维（CDFLAG）出厂 [1,1]（VariableSizes.kt:82）⇒ 快照保留两个维度
        assertEquals(listOf(1L, 1L), snap["CDFLAG"]?.toList(), "CDFLAG 两维都要进快照")
    }

    @Test
    fun 快照覆盖系统表全部条目且键全大写() {
        val registry = VariableRegistry()
        val snap = DimVarSizes.ofRegistry(registry)
        assertEquals(registry.all().size, snap.size, "系统表条目一个都不许掉")
        for (def in registry.all()) {
            val key = def.name.uppercase()
            val dims = snap[key]
            assertNotNull(dims, "${def.name} 应入册（大写键 $key）")
            assertEquals(def.dims.toList().map { it.toLong() }, dims.toList(), "${def.name} 的 dims 快照")
        }
        // 大写化不是装饰：原版 GetVariableToken 忽略大小写（spec: IdentifierDictionary.cs:429），
        // 折叠面先把实参 uppercase 再查表 ⇒ 表键必须全大写、小写查不到。
        assertNull(snap["palam"], "快照键必须全大写")
    }

    // ------------------------------------------------------------------
    // 2) 装载时点口径（语料 VariableSize.csv）
    // ------------------------------------------------------------------

    @Test
    fun 语料csv口径下palam与relation都是二百() {
        val dir = corpusCsvDir() ?: return
        val warnings = ArrayList<String>()
        val snap = DimVarSizes.systemSnapshot(dir, warnings)
        // 语料 CSV/VariableSize.csv:99 `PALAM,200` / :104 `RELATION,200`
        assertEquals(listOf(200L), snap["PALAM"]?.toList(), "CSV 改写后的 PALAM")
        assertEquals(listOf(200L), snap["RELATION"]?.toList(), "CSV 改写后的 RELATION")
        // 出厂 1000 的全局 1 维整数被 VariableSize.csv 抬过的条目也要跟着变（FLAG 行）
        val flag = snap.getValue("FLAG").toList()
        assertTrue(flag.size == 1 && flag[0] >= 1000L, "FLAG 快照尺寸异常：$flag")
    }

    // ------------------------------------------------------------------
    // 3) 不入册 / 折不出
    // ------------------------------------------------------------------

    @Test
    fun ref变量不入册而普通用户变量按dims入册() {
        val base = DimVarSizes.systemSnapshot(null)
        val ref = DimSpec("REFVAR", isStr = false, dims = intArrayOf(30), isRef = true)
        val plain = DimSpec("USVAR", isStr = false, dims = intArrayOf(42))
        val withUser = DimVarSizes.withUserDecls(base, listOf(ref, plain))
        // spec: FunctionMethodCreator.cs:3466-3469 —— REF 变量 UniqueRestructure 返 false，不入册
        assertNull(withUser["REFVAR"], "REF 变量不应入册")
        assertEquals(listOf(42L), withUser["USVAR"]?.toList(), "非 REF 用户变量按 dims 入册")
        val folder = DimFunctionFolders.pureStatic(withUser)
        assertNull(folder.foldInt("VARSIZE", listOf(DimFoldArg.StrArg("REFVAR"))), "REF 折不出尺寸")
        assertEquals(42L, folder.foldInt("VARSIZE", listOf(DimFoldArg.StrArg("usvar"))), "大小写不敏感")
        // 伪变量入册但 dims 为空 ⇒ 取不到下标 0 ⇒ 折不出（原版 GetLength 抛，VariableData.cs:827-830）
        assertTrue(base.getValue("CHARANUM").isEmpty(), "伪变量 dims 应为空表")
        assertNull(folder.foldInt("VARSIZE", listOf(DimFoldArg.StrArg("CHARANUM"))), "伪变量折不出")
        // 名字表容量之外的维度：VARSIZE("X", 1) 对 1 维变量取不到 ⇒ 不折叠（:3453 GetLength(1) 抛）
        assertNull(folder.foldInt("VARSIZE", listOf(DimFoldArg.StrArg("PALAM"), DimFoldArg.IntArg(1L))))
    }

    @Test
    fun withUserDecls不修改传入的快照() {
        val base = DimVarSizes.systemSnapshot(null)
        val before = base.size
        val merged = DimVarSizes.withUserDecls(base, listOf(DimSpec("NEWVAR", false, intArrayOf(7))))
        assertEquals(before, base.size, "withUserDecls 必须返回新表、不动入参")
        assertNull(base["NEWVAR"])
        assertEquals(listOf(7L), merged["NEWVAR"]?.toList())
    }

    // ------------------------------------------------------------------
    // 3b) 消费点 1（erbcc 侧车）接线自证：合成语料 + 合成 CSV，私有 #DIM 的
    //     VARSIZE 尺寸必须被折进侧车（摘掉接线 ⇒ 这条声明整条不进侧车 ⇒ 红）
    // ------------------------------------------------------------------

    @Test
    fun erbcc侧车的私有dim按快照折出尺寸(@TempDir dir: Path) {
        val corpus = dir.resolve("ERB")
        val csv = dir.resolve("CSV")
        Files.createDirectories(corpus)
        Files.createDirectories(csv)
        // VariableSize.csv 口径：PALAM 抬到 300（语料里是 200，这里用合成值排除巧合）
        Files.write(csv.resolve("VariableSize.csv"), "PALAM,300".toByteArray(Charsets.UTF_8))
        Files.write(
            corpus.resolve("01_main.ERB"),
            "@SYSTEM_TITLE\n#DIM TEMP_VS, VARSIZE(\"PALAM\")\nPRINTL HELLO\n".toByteArray(Charsets.UTF_8),
        )

        val r = CorpusCompile.compile(corpus)
        assertTrue(r.ok, "合成语料必须编译通过；失败 ${r.failures.size} 条: ${r.failures.take(3).map { it.message }}")
        val hit = r.bootMetadata.privateDims.firstOrNull { it.spec.name == "TEMP_VS" }
        assertNotNull(hit, "VARSIZE 尺寸的私有声明必须进侧车（接线被摘掉时这里就找不到）")
        assertEquals(listOf(300), hit.spec.dims.toList(), "侧车里的尺寸 = 合成 CSV 的 PALAM=300")
        // 侧车字段本身不带回调（回调不可序列化，侧车往返用 equals 比）：
        // functionFolder 只挂给这次解析，落进侧车的 DimParseConfig 保持无回调形态。
        assertNull(r.bootMetadata.dimParseConfig.functionFolder, "侧车配置不得带回调（BootMeta 往返用 equals 比）")
    }

    // ------------------------------------------------------------------
    // 4) 端到端：快照挂进 DimParseConfig 后，语料三条 VARSIZE 缺口按快照值折叠
    // ------------------------------------------------------------------

    @Test
    fun 接线后语料三条varsize缺口折出快照值() {
        val dir = corpusCsvDir() ?: return
        val snap = DimVarSizes.systemSnapshot(dir)
        val palam = snap.getValue("PALAM")[0]
        val relation = snap.getValue("RELATION")[0]
        val cfg = DimParseConfig(systemSaveInBinary = true).withPureStaticDimFolders(snap)

        // 对照：同一条声明不挂回调必须仍按现状失败（红线：不许把失败变静默成功）
        val bare = assertFailsWith<CodeEE> {
            parse("#DIM CHARADATA addGemDateRecord, VARSIZE(\"PALAM\")", false, DimParseConfig())
        }
        assertTrue(
            (bare.message ?: "").contains("函数调用"),
            "未注入时应报「含函数调用」，实际：${bare.message}",
        )

        val g3 = parse("#DIM CHARADATA addGemDateRecord, VARSIZE(\"PALAM\")", false, cfg)
        assertEquals(palam, g3.dims[0].toLong(), "G3 尺寸 = 快照 PALAM")
        assertTrue(g3.isCharaData)
        val p4 = parse("#DIM SORT_VALUE,  VARSIZE(\"RELATION\")", true, cfg)
        assertEquals(relation, p4.dims[0].toLong(), "P4 尺寸 = 快照 RELATION")
        val p5 = parse("#DIM CHARA_TABLE, VARSIZE(\"RELATION\")", true, cfg)
        assertEquals(relation, p5.dims[0].toLong(), "P5 尺寸 = 快照 RELATION")

        // 运行期注册同口径：SystemVariableBootstrap.install 的 config.copy 要原样透传 functionFolder
        val store = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))
        val res = SystemVariableBootstrap.install(
            store,
            listOf(
                SystemVariableBootstrap.RawDeclaration("#DIM CHARADATA addGemDateRecord, VARSIZE(\"PALAM\")", "G3", 0),
                SystemVariableBootstrap.RawDeclaration("#DIM 快照自检, VARSIZE(\"PALAM\")", "SELF", 0),
            ),
            cfg,
        )
        assertEquals(0, res.failures.size, "注入快照后这两条全局注册不该失败：${res.failures.map { it.message }}")
        val self = store.userVar("快照自检")
        assertNotNull(self, "带 VARSIZE 尺寸的全局声明应真实在册")
        assertEquals(palam, self.spec.dims[0].toLong(), "运行期注册的尺寸 = 快照 PALAM")
    }
}
