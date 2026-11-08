package com.eratw.port.core.compile

import com.eratw.port.boot.BootModel
import com.eratw.port.boot.GlobalDimDecl
import com.eratw.port.boot.RealCorpus
import com.eratw.port.boot.RealCorpusCompile
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.proc.loadConfigOverrides
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 上报簇「未定义的变量 COM641_AC_カードタイプ付与BIT配列」（0.2.39 / 46 份 / 28 台）的
 * **生产链**回归钉 —— 起点 = wave/report-bitvar@d518bdef。
 *
 * 与既有两枚钉的分工（都不重复，本钉补的是「出包链」这一层）：
 * - `core/vars/Com641RealErhDimLoadTest`（v039）：拿**单份 ERH 原文**手喂
 *   [SystemVariableBootstrap.install]，钉的是词法块的合并 + 折叠；
 * - `tools/erbcc/…/Com641RealCorpusScanTest`：钉 erbcc 预扫描的符号表登记；
 * - 本钉：走**出包用的同一条编译链**（[RealCorpusCompile.compile] = 全量符号表 +
 *   切片收集），断言产出的 `globalDims` 原文里确实带走了这条合并声明，再把它装进
 *   [VariableStore] 读数。真机 APK 吃的就是这份侧车原文 + 引擎侧的折叠，
 *   两侧任一缺位，表现都是同一个「未定义的变量」（抛点 `VariableStore.readInt`）。
 *
 * 语料原文（4.981 专属；4.980 无 COMF640-641 目录 ⇒ 本钉按假设跳过）：
 * `ERB/コマンド関連/COMF/外出系/COMF640-641_月虹市場&アビリティカード/`
 * `COMF641 アビリティカード_カードパラメータ.ERH`
 *  - 1 基 `:225` 单独一行左花括号开块
 *  - 1 基 `:226` 声明行（等号后为空）
 *  - 1 基 `:227`–`:259` 33 个元素，每行 `1 << COM641_AC_カードIDX_*`，可再按位或复合
 *  - 1 基 `:260` 单独一行右花括号收块
 * 块合并口径 spec: EraStreamReader.cs:130-184（移植侧 `LogicalLineReader.kt:214-252`）。
 * 按位折叠口径 spec: OperatorMethodManager.cs:467-507（单 `&`/`|`/`^` 按位，注册 :737-739）
 * 与 :523-534（`<<`，注册 :741）； CONST 标识符自身可折叠 spec: UserDefinedVariableToken.cs:35。
 * 单竖线**不是**逻辑或（逻辑层是双字符，:375-465 注册 :732-736）—— 修复前本仓把它按逻辑层
 * 折，整条声明报「尺寸/初值表达式有多余内容」整表丢弃（34e307e9 / 872b5934，2026-11-04），
 * 这就是那 46 份上报的因；本钉的第 3 例把这个失败方向钉死。
 *
 * 期望值是**独立算出来的**：用真文件 1 基 :209-213 的五个标量 CONST
 * （ステータス補助 0 / 戦闘補助 1 / 技能補助 2 / うふふ補助 3 / 特殊効果 10）代入
 * :227-259 的元素式，按 Python 原生按位运算求值，不经移植侧解析器（与 v039 的
 * `expectedBits` 逐位对拍一致，两侧独立推导互验）。
 *
 * 门控：`ERATW_CORPUS`（指到 4.981 的 ERB 子树）；缺 env 或语料根没有这份 ERH ⇒ 假设跳过。
 */
class Com641ProductionChainDimLoadPinTest {

    private companion object {
        const val BIT_ARRAY = "COM641_AC_カードタイプ付与BIT配列"
        const val ERH_NAME = "COMF641 アビリティカード_カードパラメータ.ERH"
        const val ERH_DIR = "コマンド関連/COMF/外出系/COMF640-641_月虹市場&アビリティカード"
        const val ERH_REL = "$ERH_DIR/$ERH_NAME"

        /** 合并后的逻辑行号 = 收块行所在物理行（1 基 :260 ⇒ 0 基 259）。 */
        const val MERGED_LOGICAL_LINE = 259

        /** 1 基 :227-259 共 33 个元素，按位独立求值。 */
        val EXPECTED_BITS = longArrayOf(
            1024, 6, 6, 7, 7, 1, 1, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            2, 2, 2, 1, 1, 2, 2, 4, 1025, 4, 2, 6, 6, 10, 2, 6, 5,
        )

        /** 全树只编这一支目录（其余定义仍进符号表），把 40 秒级的全量切片压到本簇规模。 */
        fun isFocusFile(path: Path): Boolean =
            path.toString().replace('\\', '/').contains(ERH_DIR)

        /** 生产链产物：一次编译、四例共用（编译代价只付一遍）。 */
        val model: BootModel by lazy {
            val corpus = RealCorpus.load() ?: error("语料根不可用")
            val gameRoot = corpus.root.parent ?: error("语料根没有父目录：$corpus")
            val overrides = loadConfigOverrides(gameRoot)
            val saveInBinary = SystemVariableBootstrap.systemSaveInBinaryFrom(overrides)
            RealCorpusCompile.compile(
                corpus,
                fileLimit = null,
                systemSaveInBinary = saveInBinary,
                parserConfig = RealCorpusCompile.parserConfigFromOverrides(overrides),
                sliceFilter = ::isFocusFile,
            )
        }

        val bitDecl: GlobalDimDecl by lazy {
            model.globalDimDecls.first { BIT_ARRAY in it.raw }
        }
    }

    /** 4.981 语料在场才判，否则按假设跳过（计入 skipped，不许静默 PASS）。 */
    private fun requireCorpus(): RealCorpus {
        val corpus = RealCorpus.load()
        Assumptions.assumeTrue(corpus != null, "未设 ERATW_CORPUS 或语料根无 ERB/ERH：跳过生产链钉")
        val erh = corpus!!.root.resolve(ERH_REL)
        Assumptions.assumeTrue(
            java.nio.file.Files.isRegularFile(erh),
            "$erh 不在场：当前语料根不是 4.981（4.980 无 COMF640-641 目录），跳过",
        )
        return corpus
    }

    @Test
    fun `生产编译链把行连续块收成一条全局声明带走`() {
        requireCorpus()
        val m = model
        // 全量口径不许被 sliceFilter 悄悄改掉：符号表/全局声明收集仍是整根
        assertTrue(m.stats.corpusFiles > 4000, "语料根规模不对，生产链读数无意义：${m.stats}")
        val sameName = m.globalDimDecls.filter { it.raw.startsWith("#DIM CONST $BIT_ARRAY") }
        assertEquals(1, sameName.size, "$BIT_ARRAY 的全局声明应只有一条，实际 ${sameName.map { "${it.file}:${it.line}" }}")
        val d = bitDecl
        assertEquals(ERH_REL, d.file, "声明来源文件（相对 ERB 根，正斜杠）")
        assertEquals(MERGED_LOGICAL_LINE, d.line, "逻辑行号 = 收块行物理行（0 基）")
        // 收集点必须收到**合并后**的整块：空等号起头 + 首元素 + 末元素，且花括号不进内容
        assertTrue(d.raw.startsWith("#DIM CONST $BIT_ARRAY = "), "合并串应以空等号起头：${d.raw.take(60)}")
        assertTrue("1 << COM641_AC_カードIDX_特殊効果," in d.raw, "首元素（1 基 :227）应并入：${d.raw.take(120)}")
        assertTrue(
            "1 << COM641_AC_カードIDX_ステータス補助 | 1 << COM641_AC_カードIDX_技能補助" in d.raw,
            "末元素（1 基 :259）应并入",
        )
        assertEquals(32, d.raw.count { it == ',' }, "33 元素 ⇒ 32 个逗号：${d.raw}")
        assertTrue('{' !in d.raw && '}' !in d.raw, "块标记不进逻辑行内容")
        assertEquals(
            EXPECTED_BITS.size,
            d.raw.substringAfter("= ").split(',').size,
            "按顶层逗号切分应正好 33 段",
        )
    }

    @Test
    fun `侧车原文装载后按位折叠的三十三元素全部在册`() {
        requireCorpus()
        val m = model
        val store = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))
        val result = SystemVariableBootstrap.install(
            store,
            m.globalDimDecls.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
            m.dimParseConfig.copy(systemSaveInBinary = true),
        )
        // 判据按**声明来源**收窄到本簇那一份 ERH（与 v039 同口径）：同目录的其余 ERH/ERB
        // 若有失败与本题无关，不该由本钉承担；反向也不许把它放宽 —— 这一份必须零失败。
        val erhFailures = result.failures.filter { it.file == ERH_REL }
        assertEquals(
            emptyList(),
            erhFailures.map { "逻辑行 ${it.line}（1 基 :${it.line + 1}）${it.raw.take(60)} → ${it.message}" },
            "$ERH_NAME 的全部全局 #DIM 必须零失败（原版 ErhLoader.cs:249-338 逐条注册）",
        )
        val var0 = store.userVar(BIT_ARRAY)
        assertNotNull(var0, "$BIT_ARRAY 必须随侧车原文注册进变量池 —— 失踪即真机那条「未定义的变量」")
        assertTrue(store.isConst(BIT_ARRAY), "CONST 修饰必须保留（spec: UserDefinedVariableToken.cs:35）")
        assertEquals(1, var0!!.spec.dims.size, "一维数组")
        assertEquals(EXPECTED_BITS.size, var0.spec.dims[0], "长度 = 33 个元素")
        for (i in EXPECTED_BITS.indices) {
            assertEquals(EXPECTED_BITS[i], store.readInt(BIT_ARRAY, longArrayOf(i.toLong())), "$BIT_ARRAY:$i")
        }
    }

    /**
     * 失败方向钉：单竖线是**按位**或，不是逻辑或。
     *
     * 修复前（≤0.2.39 引擎）这一族的两种错值形态分别是「整表丢弃」（上报的那条）与
     * 「折成布尔 1/0」；两者都会让月虹市場的能力卡牌读位失效。这里同时钉：
     *  - 复合元素 1（`1<<戦闘補助 | 1<<技能補助` = 2|4）折 **6**，不是 1；
     *  - 元素 24（`1<<ステータス補助 | 1<<特殊効果` = 1|1024）折 **1025**，不是 1；
     *  - 元素 0（`1<<特殊効果`）折 **1024**，证明位移层同样在册。
     */
    @Test
    fun `单竖线折按位值而非布尔`() {
        requireCorpus()
        val store = installFresh()
        assertEquals(1024L, store.readInt(BIT_ARRAY, longArrayOf(0L)), "1 << 10")
        assertEquals(6L, store.readInt(BIT_ARRAY, longArrayOf(1L)), "1 << 1 | 1 << 2 是按位或 = 6")
        assertEquals(1025L, store.readInt(BIT_ARRAY, longArrayOf(24L)), "1 << 0 | 1 << 10 = 1025")
        assertTrue(store.readInt(BIT_ARRAY, longArrayOf(1L)) != 1L, "逻辑或口径（折成 1）即本簇的失败方向")
        assertTrue(store.readInt(BIT_ARRAY, longArrayOf(24L)) != 1L, "同上")
    }

    /**
     * 反向对照：把**未经块合并**的裸声明（1 基 :226 原样，等号后为空）单独喂同一条装载链，
     * 变量必定不注册 —— 上报里的「未定义的变量」正是这类声明失踪后的运行期形态
     * （spec: UserDefinedVariableData.cs:406-408：CONST 无初值 ⇒ CodeEE ⇒ 不进册）。
     * 这一例证明本钉的断言不是空转：承重的是块合并 + 按位折叠两道。
     */
    @Test
    fun `未合并的裸声明整表失踪`() {
        requireCorpus()
        val store = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))
        val bare = SystemVariableBootstrap.install(
            store,
            listOf(SystemVariableBootstrap.RawDeclaration("#DIM CONST $BIT_ARRAY =", ERH_REL, MERGED_LOGICAL_LINE)),
            model.dimParseConfig.copy(systemSaveInBinary = true),
        )
        assertEquals(0, bare.declared, "等号后为空 ⇒ 不注册")
        assertNull(store.userVar(BIT_ARRAY), "失踪变量的运行期形态就是上报的那条 CodeEE")
    }

    /** 生产链侧车原文 → 全新 [VariableStore]（第 3 例用，与第 2 例同一份输入）。 */
    private fun installFresh(): VariableStore {
        val m = model
        val store = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))
        SystemVariableBootstrap.install(
            store,
            m.globalDimDecls.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
            m.dimParseConfig.copy(systemSaveInBinary = true),
        )
        return store
    }
}
