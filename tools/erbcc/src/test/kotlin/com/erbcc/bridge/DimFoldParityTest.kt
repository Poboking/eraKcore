package com.erbcc.bridge

import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.vars.DimFunctionFolders
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimVarSizes
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.vars.withPureStaticDimFolders
import com.eratw.port.erbcc.DimKeyword
import com.eratw.port.erbcc.DimSpec
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CMP-40/CMP-45 必红夹具：**同一条 `#DIM` 的三方对账**。
 *
 * 病灶（RECON-CMP §6）：`#DIM SORT_VALUE, VARSIZE("RELATION")` 这类尺寸声明有**三份互不相干的
 * 实现**，折不出就各自静默丢弃：
 *
 * | 面 | 实现 | 尺寸数据源 | 修复前 |
 * |---|---|---|---|
 * | A 布局面 | [toLocalDim] → `DimSizeEvaluator` | 只有常量表，**没有函数折叠** | 折不出 ⇒ 整条 `null`（**缺**） |
 * | B 侧车面 | `DimParser.parse` + `withPureStaticDimFolders` | `DimVarSizes` 快照 | 200 |
 * | C 运行期面 | `SystemVariableBootstrap.install` + 同一快照 | `DimVarSizes` 快照 | 200 |
 *
 * 语义依据（`VARSIZE` 的装载期折叠）：
 * - `FunctionMethodCreator.cs:3437-3438` 注册 CanRestructure + HasUniqueRestructure；
 * - `:3456-3473` UniqueRestructure（实参 SingleTerm、变量在册且非 REF 才折）；
 * - `:3441-3454` GetIntValue 走 `GetVariableToken(...).GetLength(num)`；
 * - 尺寸表时点 = `Process.cs:292-293`（先装 `VariableSize.csv`）→ `:300-301`
 *   （建 IdentifierDictionary）→ `:309/:313`（才装 ERH），故 `CSV/VariableSize.csv:104
 *   RELATION,200` 之后的 `VARSIZE("RELATION")` 必须折出 **200**，不是出厂 100。
 *
 * 夹具用**合成 CSV**（`RELATION,200`）：既不读语料（可离线跑），又排除「200 与出厂值巧合」——
 * 把接线换成 `systemSnapshot(null)` 就得到 100 ⇒ 本用例红。
 *
 * 负例（CMP-40 的样本不可达）：`ResourceManager.ERB:323 #DIMS L_TIMES, LOCAL` 整段在
 * `:304 [SKIPSTART]` … `:365 [SKIPEND]` 内，语料里根本不会装载；这里保留它做
 * 「三方同判失败」的负例 —— 任何一面把它折出尺寸或静默注册，都说明该面在脑补常量。
 */
class DimFoldParityTest {

    /** 合成 `CSV/VariableSize.csv`：只抬 RELATION（出厂 100 → 200）。 */
    private fun snapshot(dir: Path): Map<String, LongArray> {
        val csv = dir.resolve("CSV")
        Files.createDirectories(csv)
        Files.write(csv.resolve("VariableSize.csv"), "RELATION,200".toByteArray(Charsets.UTF_8))
        return DimVarSizes.systemSnapshot(csv)
    }

    private fun bridgeDim(name: String, dims: List<String>, kws: Set<DimKeyword> = emptySet()) =
        DimSpec(name = name, isStr = false, dims = dims, keywords = kws, initializer = null)

    private fun newStore() = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))

    @Test
    fun `varsize 尺寸三方同值 200`(@TempDir dir: Path) {
        val snap = snapshot(dir)
        val folder = DimFunctionFolders.pureStatic(snap)

        // A 布局面（erbcc dimSpecs → core-vm 局部布局）
        val a = bridgeDim("SORT_VALUE", listOf("VARSIZE(\"RELATION\")")).toLocalDim(emptyMap(), folder)
        // B 侧车面（CorpusCompile 的私有 #DIM 解析 = DimParser + 同一快照）
        val b = DimParser.parse(
            keyword = "#DIM",
            body = "SORT_VALUE, VARSIZE(\"RELATION\")",
            isPrivate = true,
            config = DimParseConfig().withPureStaticDimFolders(snap),
        )
        // C 运行期面（GameSession 的全局 #DIM 安装 = SystemVariableBootstrap.install）
        val store = newStore()
        val c = SystemVariableBootstrap.install(
            store,
            listOf(
                SystemVariableBootstrap.RawDeclaration(
                    "#DIM SORT_VALUE, VARSIZE(\"RELATION\")",
                    "fromEN/QOL/QoL_ShowRelation.ERH",
                    3,
                ),
            ),
            DimParseConfig(systemSaveInBinary = true).withPureStaticDimFolders(snap),
        )
        val cVar = store.userVar("SORT_VALUE")

        println(
            "CMP-40 三方读数：A 布局面=${a?.slots ?: "缺"} / " +
                "B 侧车面=${b.dims.toList()} / C 运行期面=${cVar?.spec?.dims?.toList() ?: "缺"}（期望 200）",
        )

        // 条目名集：三方必须是同一个名字（大写归一后比较）。
        assertNotNull(a, "A 布局面必须收录该声明；当前折不出 VARSIZE ⇒ null（CMP-40 的 A 缺）")
        assertEquals("SORT_VALUE", a.name.uppercase(), "A 条目名")
        assertEquals("SORT_VALUE", b.name.uppercase(), "B 条目名")
        assertNotNull(cVar, "C 运行期面必须注册；失败 ${c.failures.map { it.message }}")
        assertEquals("SORT_VALUE", cVar.spec.name.uppercase(), "C 条目名")

        // 尺寸：三方同值（折叠数与元素槽数同一口径）。
        assertEquals(200, a.slots, "A 布局面的尺寸（= LocalDim.slots，必须不再是恒 1）")
        assertEquals(listOf(200), b.dims.toList(), "B 侧车面的尺寸")
        assertEquals(listOf(200), cVar.spec.dims.toList(), "C 运行期面的尺寸")
    }

    @Test
    fun `跳过样本 L_TIMES 三方同判失败`() {
        // 语料原文：ResourceManager.ERB:323 `#DIMS L_TIMES, LOCAL`（LOCAL 是函数内派生量，
        // 不是常量）。整段在 SKIPSTART 内 ⇒ 不是「要实现」的样本，而是负例。
        // 来源写成 .ERH 是为了把 CMP-89 的「ERB 函数外 #DIM」来源闸隔离掉：本用例只钉
        // 「LOCAL 不是常量 ⇒ 三方都失败」，不掺来源判定。
        val a = bridgeDim("L_TIMES", listOf("LOCAL")).toLocalDim()
        val b = runCatching {
            DimParser.parse("#DIMS", "L_TIMES, LOCAL", isPrivate = true, config = DimParseConfig())
        }
        val store = newStore()
        val c = SystemVariableBootstrap.install(
            store,
            listOf(SystemVariableBootstrap.RawDeclaration("#DIMS L_TIMES, LOCAL", "リソース管理/ResourceManager.ERH", 323)),
            DimParseConfig(),
        )
        println(
            "CMP-40 负例读数：A=${a?.slots ?: "缺"} / B=${b.exceptionOrNull()?.message ?: "折成功（不该）"} / " +
                "C declared=${c.declared} failures=${c.failures.map { it.message }}",
        )
        assertNull(a, "LOCAL 不是常量：布局面不许折出尺寸")
        assertTrue(b.isFailure, "LOCAL 不是常量：侧车面必须解析失败（本波不许脑补默认值）")
        assertEquals(0, c.declared, "LOCAL 不是常量：运行期面必须不注册")
        assertNull(store.userVar("L_TIMES"), "运行期面不许注册 L_TIMES")
    }
}
