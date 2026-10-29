package com.eratw.port.boot

import com.eratw.port.core.compile.CorpusCompileValidator
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.vars.DimFoldSnapshot
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.vars.withPureStaticDimFolders
import com.eratw.port.erbcc.compile.CorpusCompile
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M11-E 波 `m11e-dimfold-wire` 收口：装载期折叠面（[DimFoldSnapshot]）三处消费点同源。
 *
 * 背景（M4-M `m-dimfold-wire` 欠账）：`DimParseConfig.withPureStaticDimFolders(varSizes)`
 * 早已写好，但三处消费点没有喂同一份快照 ——
 * ① erbcc `CorpusCompile.compile`（侧车 `strConstants` / `privateDims`）三件套齐全；
 * ② boot 装置 `RealCorpusCompile.compile` 只传 varSizes，关键字表与条宽吃默认值；
 * ③ `GameBootSmoke` 交给 `SystemVariableBootstrap.install` 的 cfg 经 ② 透传。
 * 后果：同一行 `#DIM DYNAMIC 行文字数 = STRLENSU(GETLINESTR("─"))`（语料
 * `カラム機能/カラム機能(div).ERB:21`）在侧车折成 99、在装置折成 900（默认 1800 格），
 * `GETNUM(TALENT, …)`（`CHARALIST_DT/TEMP_CHARA_LIST_BY_DT_FUNC.ERB:2027` / `:2063`）在装置侧
 * 直接失败 —— 侧车与装置读数分叉、第二个真源。M11-E 把三处统一到同一份 `DimFoldSnapshot`
 * （varSizes + GETNUM 关键字表 + GETLINESTR 条宽 199；装载时点 spec:
 * Process.cs:292-293 建 ConstantData 并 LoadData → :300-301 建 IdentifierDictionary →
 * :309/:313 才装 ERH）。
 *
 * 本文件用**合成语料**（@TempDir：ERB + 同级 CSV）把三处消费点各跑一遍，断言折出同一份值：
 * ① 侧车 `bootMetadata.privateDims`（erbcc 自建同参快照）；② 装置 `userVarDecls`
 * （`model.dimParseConfig.functionFolder` = 装置的 DimFoldSnapshot）；③ 运行期注册 cfg
 * （GameBootSmoke 同款 `model.dimParseConfig.copy(systemSaveInBinary = true)` 喂
 * `SystemVariableBootstrap.install`）。
 * 合成值刻意不等于语料真值（PALAM,300 vs 语料 `CSV/VariableSize.csv:99` 的 200；TALENT
 * 关键字 0/1 vs 190..197），折错即红、排除「碰巧相等」。语料级读数由 bootSmoke 报告
 * （`ERATW_BOOT=1`）与 `core/compile/DimFailTriageTest` 语料锚点用例负责，本文件不背全量编译。
 *
 * 回归护栏（摘掉/回退接线即红）：
 * - 装置若回退成 `withPureStaticDimFolders(varSizes)`（只传尺寸表），条宽吃默认 1800 ⇒
 *   `LINE_CELLS` 折成 900、`C_REF`（GETNUM 无表）整条解析失败 ⇒ 用例 1 红；
 * - 侧车与装置只要有一边不挂快照，用例 1 的两侧等值断言必有一边红；
 * - `PC_CONSOLE_WIDTH_CELLS` 与 `CorpusCompile.FOLD_CONSOLE_WIDTH_CELLS` 是同一条尺
 *   （PC 基准可绘制宽 1797px / 9px 步进 = 199，`pc-golden-root.txt:689`），单测钉住同值。
 */
class DimFoldWireTest {

    @TempDir
    lateinit var dir: Path

    // ------------------------------------------------------------------
    // 合成语料：两条私有 #DIM（STRLENSU(GETLINESTR) / GETNUM）+ 同级 CSV
    // ------------------------------------------------------------------

    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        Files.write(
            root.resolve("01_dim.ERB"),
            """
            @FN_LINESTR
            #DIM DYNAMIC LINE_CELLS = STRLENSU(GETLINESTR("─"))
            PRINTL OK

            @FN_GETNUM
            #DIM CONST C_REF = GETNUM(TALENT, "KEY_A")
            PRINTL OK
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        // CSV 目录口径与 erbcc / 装置逐字一致：`<ERB>.parent/CSV`
        val csv = root.parent?.resolve("CSV") ?: root
        Files.createDirectories(csv)
        // 装载时点口径：VariableSize.csv 先于 ERH 生效（spec: Process.cs:292-293）
        Files.write(csv.resolve("VariableSize.csv"), "PALAM,300".toByteArray(Charsets.UTF_8))
        // GETNUM 的内置关键字表（TALENT 槽；spec: FunctionMethodCreator.cs:4814-4832）
        Files.write(csv.resolve("TALENT.csv"), "0,KEY_A\n1,KEY_B\n".toByteArray(Charsets.UTF_8))
    }

    /**
     * `RealCorpus` 构造器私有（装置清单外的文件本路不动），反射造一个合成语料句柄。
     * 文件清单口径 = `RealCorpus.load()` 同一个 `CorpusCompileValidator.listCorpusFiles`。
     */
    private fun syntheticCorpus(root: Path): RealCorpus {
        val files = CorpusCompileValidator.listCorpusFiles(root)
        val ctor = RealCorpus::class.java.getDeclaredConstructor(
            Path::class.java,
            List::class.java,
            String::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(root, files, "synthetic:DimFoldWireTest") as RealCorpus
    }

    /** 装置同款入口：剥行内注释 → 切关键字 → DimParser。 */
    private fun parseDim(raw: String, isPrivate: Boolean, c: DimParseConfig): DimSpec {
        val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw))
            ?: error("不是 #DIM/#DIMS 声明: $raw")
        return DimParser.parse(split.first, split.second, isPrivate, c)
    }

    // ------------------------------------------------------------------
    // ① ② 侧车与装置同折：合成语料上两边同一份值
    // ------------------------------------------------------------------

    @Test
    fun `侧车与装置同折：合成语料上私有 DIM 两边同一份值`() {
        val root = dir.resolve("ERB")
        writeCorpus(root)

        // ① erbcc 侧车（CorpusCompile.compile 内部自建同参 DimFoldSnapshot）
        val sidecar = CorpusCompile.compile(root)
        assertTrue(sidecar.ok, "合成语料必须编译通过：${sidecar.failures.take(3).map { it.message }}")
        assertEquals(0, sidecar.dimWarnings.size, "两条私有 #DIM 都应折上：${sidecar.dimWarnings}")
        val sideLine = sidecar.bootMetadata.privateDims.first { it.spec.name == "LINE_CELLS" }
        val sideGetnum = sidecar.bootMetadata.privateDims.first { it.spec.name == "C_REF" }
        // GETLINESTR 199 格 / "─" 2 格每字 ⇒ 99 字；STRLENSU 折 99（语料侧车同值）
        assertEquals(listOf(99L), sideLine.spec.defaultsInt?.toList(), "侧车 LINE_CELLS 应折 99")
        assertEquals(listOf(0L), sideGetnum.spec.defaultsInt?.toList(), "侧车 C_REF 应按 TALENT 表折 0")

        // ② boot 装置（RealCorpusCompile.compile 第 5 步的 DimFoldSnapshot）
        val model = RealCorpusCompile.compile(syntheticCorpus(root))
        assertEquals(0, model.dimParseFailures.size, "装置两条私有 #DIM 都应折上：${model.dimParseFailures}")
        assertNotNull(model.dimParseConfig.functionFolder, "装置 cfg 必须挂折叠面（空转即红）")
        val devLine = model.userVarDecls.first { it.second.name == "LINE_CELLS" }
        val devGetnum = model.userVarDecls.first { it.second.name == "C_REF" }
        // 旧接线（只传 varSizes、条宽吃默认 1800）会在这里折成 900 ⇒ 红
        assertEquals(listOf(99L), devLine.second.defaultsInt?.toList(), "装置 LINE_CELLS 应折 99")
        // 旧接线（无关键字表）会让这条 GETNUM 整条解析失败、进 dimParseFailures ⇒ 红
        assertEquals(listOf(0L), devGetnum.second.defaultsInt?.toList(), "装置 C_REF 应按 TALENT 表折 0")

        // 装置侧快照三件套读数（自证非空转；合成值 300 / {KEY_A:0,KEY_B:1} / 199）
        assertEquals(listOf(300L), model.foldSnapshot.varSizes["PALAM"]?.toList(), "装置快照 PALAM 应吃合成 CSV")
        assertEquals(mapOf("KEY_A" to 0, "KEY_B" to 1), model.foldSnapshot.keywordTables["TALENT"])
        assertEquals(199, model.foldSnapshot.consoleWidth)
    }

    // ------------------------------------------------------------------
    // ③ 运行期注册 cfg：GameBootSmoke 同款喂法，install 零失败且取快照值
    // ------------------------------------------------------------------

    @Test
    fun `运行期注册 cfg 拿同一快照：install 零失败且 VARSIZE 取快照值`() {
        val root = dir.resolve("ERB")
        writeCorpus(root)
        val model = RealCorpusCompile.compile(syntheticCorpus(root))

        // GameBootSmoke.kt 的喂法：model.dimParseConfig.copy(systemSaveInBinary = …)，
        // functionFolder 随 copy 原样透传（install 的不动点环也只 copy 常量表，
        // spec: SystemVariableBootstrap.kt:202）。
        val store = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))
        val install = SystemVariableBootstrap.install(
            store,
            listOf(
                SystemVariableBootstrap.RawDeclaration(
                    "#DIM CHARADATA ADD_GEM, VARSIZE(\"PALAM\")",
                    "DimFoldWireTest",
                    0,
                ),
            ),
            model.dimParseConfig.copy(systemSaveInBinary = true),
        )
        assertEquals(0, install.failures.size, "第三消费点应零失败：${install.failures.map { it.message }}")
        val gem = store.userVar("ADD_GEM")
        assertNotNull(gem, "带 VARSIZE 尺寸的全局声明应真实在册")
        assertEquals(listOf(300), gem.spec.dims.toList(), "注册尺寸 = 合成 CSV 的 PALAM=300（不是出厂 100）")
    }

    // ------------------------------------------------------------------
    // DimFoldSnapshot 单测：三件套整面透传 + 同尺常量 + 出厂退路
    // ------------------------------------------------------------------

    @Test
    fun `DimFoldSnapshot 三件套整面透传且与 erbcc 同尺`() {
        val csv = dir.resolve("CSV")
        Files.createDirectories(csv)
        Files.write(csv.resolve("VariableSize.csv"), "PALAM,300".toByteArray(Charsets.UTF_8))
        val tables = mapOf("TALENT" to mapOf("KEY_A" to 7))
        val snap = DimFoldSnapshot.load(csv, tables, consoleWidth = 199)
        assertEquals(listOf(300L), snap.varSizes["PALAM"]?.toList(), "load 必须吃 VariableSize.csv")
        assertEquals(tables, snap.keywordTables, "关键字表原样透传")
        assertEquals(199, snap.consoleWidth, "条宽原样透传")
        // 同一把尺：erbcc 侧车常量与快照常量同值（pc-golden-root.txt:689 = 1797px / 9px）
        assertEquals(CorpusCompile.FOLD_CONSOLE_WIDTH_CELLS, DimFoldSnapshot.PC_CONSOLE_WIDTH_CELLS)

        // apply 把三件套整面挂上：VARSIZE 取快照值、GETNUM 取快照表、GETLINESTR 取快照宽
        val cfg = snap.apply(DimParseConfig(systemSaveInBinary = true))
        assertEquals(300L, parseDim("#DIM X, VARSIZE(\"PALAM\")", true, cfg).dims[0].toLong())
        assertEquals(7L, parseDim("#DIM CONST G = GETNUM(TALENT, \"KEY_A\")", false, cfg).defaultsInt!![0])
        assertEquals(99L, parseDim("#DIM CONST W = STRLENSU(GETLINESTR(\"─\"))", false, cfg).defaultsInt!![0])

        // 退路：csvDir = null ⇒ 出厂 VariableSizes（PALAM=100），条宽仍是 PC 基准
        val factory = DimFoldSnapshot.load(null)
        assertEquals(listOf(100L), factory.varSizes["PALAM"]?.toList(), "null csvDir = 出厂尺寸")
        assertEquals(DimFoldSnapshot.PC_CONSOLE_WIDTH_CELLS, factory.consoleWidth)
    }

    // ------------------------------------------------------------------
    // 反证钉：旧接线只传 varSizes 时，条宽吃默认 1800 ⇒ 与侧车 99 分叉
    // ------------------------------------------------------------------

    @Test
    fun `旧接线只传 varSizes 时条宽吃默认值致分叉（反证钉）`() {
        val csv = dir.resolve("CSV")
        Files.createDirectories(csv)
        Files.write(csv.resolve("VariableSize.csv"), "PALAM,300".toByteArray(Charsets.UTF_8))
        val snap = DimFoldSnapshot.load(csv)
        // M4-M 首轮接线的原形：只传 varSizes，keywordTables / consoleWidth 吃默认值。
        val varSizesOnly = DimParseConfig(systemSaveInBinary = true)
            .withPureStaticDimFolders(snap.varSizes)
        // 默认 1800 格 / "─" 2 格每字 ⇒ 900 字 —— 与侧车（同一份快照的 199 格 ⇒ 99）分叉，
        // 这正是 DimFoldSnapshot KDoc 要求「三件套整面带过去」的立项依据。
        assertEquals(900L, parseDim("#DIM CONST W = STRLENSU(GETLINESTR(\"─\"))", false, varSizesOnly).defaultsInt!![0])
        // 对照：同一快照经 apply 挂面 ⇒ 99，两侧必须分叉才证明本钉有效。
        assertEquals(99L, parseDim("#DIM CONST W = STRLENSU(GETLINESTR(\"─\"))", false, snap.apply(DimParseConfig())).defaultsInt!![0])
    }
}
