package com.eratw.port.core.vars

import com.eratw.port.boot.GameRoot981
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.proc.SystemVariableBootstrap
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 簇 1（上报 0.2.39 最大簇「未定义的变量 COM641_AC_カードタイプ付与BIT配列」）
 * 的**真语料**装载闸 —— 起点 = main@e208ced5（worker lane v039）。
 *
 * 上报读数：34 份 / 19 台 / app 0.2.39（构建标识全部是 `4899130-dirty`，即
 * 872b5934 之前的包），抛点 `VariableStore.readInt` ← `VarRef.getIntValue` ←
 * 内建实参求值，ERB 帧链 `F_COM641_CARD_TYPE_ARRAY ← F_COM641_DETAIL_DISP ← COM641`
 * （读点 `COMF641 アビリティカード_関数.ERB:387` 的 `GETBIT(COM641_AC_カードタイプ付与BIT配列:対象カード番号, L_カウント)`）。
 * 上报本身证不了当前 main 修没修（用户没升级），只能拿真语料重跑装载。
 *
 * ## 真实形态（与既有合成用例的差别就是本闸的意义）
 *
 * `DimFoldTest`/`SystemVariableBootstrapTest` 钉的是**单行** `#DIM CONST X = a, b`
 * 拼接形态；4.981 语料的原文是 `{` 换行 `#DIM CONST X =`（**等号后为空**）
 * 再逐行一个元素、`}` 收尾的**行连续块**，声明本身要靠预处理层的块合并才成立。
 * 原文（`ERB/コマンド関連/COMF/外出系/COMF640-641_月虹市場&アビリティカード/`
 * `COMF641 アビリティカード_カードパラメータ.ERH`，见 [erhRel] 常量，实际取真文件）：
 *  - `:225` 单独一行 `{
 *  - `:226` `#DIM CONST COM641_AC_カードタイプ付与BIT配列 =`
 *  - `:227`–`:259` 33 行元素，每行 `1 << COM641_AC_カードIDX_*`（可 `|` 复合），行尾逗号、末元素无逗号
 *  - `:260` 单独一行 `}`
 * 块合并口径 spec: EraStreamReader.cs:130-184（移植侧 `LogicalLineReader.kt:214-252`，
 * 连接串 = `Config.ReplaceContinuationBR` 去引号 = 单空格，EraStreamReader.cs:178-179）。
 *
 * 按位折叠口径 spec: OperatorMethodManager.cs:467-507（单 `&`/`|`/`^` 是按位，注册 :737-739）
 * 与 :523-534（`<<`，注册 :741）；CONST 标识符可折叠 spec: UserDefinedVariableToken.cs:35。
 * 单 `|` 不是逻辑或（逻辑层是 `||`，:375-465 注册 :732-736）—— 4.981 随附原版引擎实测
 * `1 << 1 | 1 << 2` 折 6（emuera.log 取证，2026-11-04，`DimFoldTest` 文件头同源）。
 *
 * 本文件零产品改动：判定 = 真语料装载读数。
 */
class Com641RealErhDimLoadTest {

    private val gameRoot: Path? = GameRoot981.load()
    private val erb: Path? = GameRoot981.erb()

    /** 真语料里的声明文件（4.981 专属；4.980 无 COMF640-641 目录）。 */
    private val erhRel = "コマンド関連/COMF/外出系/COMF640-641_月虹市場&アビリティカード/COMF641 アビリティカード_カードパラメータ.ERH"

    /** 行连续块的变量名（本闸主角）。 */
    private val bitArray = "COM641_AC_カードタイプ付与BIT配列"

    /**
     * `ERH:227-259` 的 33 个元素按原版语义独立算出的期望值
     * （`IDX_ステータス補助=0 / 戦闘補助=1 / 技能補助=2 / うふふ補助=3 / 特殊効果=10`，
     * 即 ERH:209-213 的五行标量 CONST；元素式 = `1 << i` 逐条按位或）。
     * 由独立脚本从真文件原文算出，不经过移植侧解析器 —— 断言的是语义而不是自证。
     */
    private val expectedBits = longArrayOf(
        1024, 6, 6, 7, 7, 1, 1, 2, 2, 1, 1, 1, 1, 1, 1, 1,
        2, 2, 2, 1, 1, 2, 2, 4, 1025, 4, 2, 6, 6, 10, 2, 6, 5,
    )

    private fun productionConfig(): DimParseConfig {
        val csv = gameRoot?.resolve("CSV")
        val varSizes = DimVarSizes.systemSnapshot(csv)
        return DimParseConfig(systemSaveInBinary = true)
            .withPureStaticDimFolders(varSizes = varSizes)
    }

    /** 真文件的逻辑行（含 `{ }` 块合并）里所有函数外 `#DIM` 原文声明。 */
    private fun realDeclarations(): Pair<List<SystemVariableBootstrap.RawDeclaration>, List<SharpSample>> {
        val file = erb!!.resolve(erhRel)
        val lines = LogicalLineReader.readAllLines(Files.readAllBytes(file))
        val reader = LogicalLineReader(lines, erhRel.substringAfterLast('/'))
        val sharp = ArrayList<SharpSample>()
        val decls = ArrayList<SystemVariableBootstrap.RawDeclaration>()
        while (true) {
            val l = reader.readLogicalLine() ?: break
            if (l.kind == LineKind.SHARP) {
                sharp.add(SharpSample(l.lineNo, l.content))
                if (SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(l.content)) != null) {
                    decls.add(SystemVariableBootstrap.RawDeclaration(l.content, erhRel, l.lineNo))
                }
            }
        }
        reader.fileEnd()
        return decls to sharp
    }

    @Test
    fun `真语料 ERH 的块合并把声明还原成一条完整逻辑行`() {
        val root = erb
        Assumptions.assumeTrue(root != null, "缺 4.981 语料根（ERATW_GAMEDIR / ERATW_CORPUS 都没指到 4.981）：跳过")
        val (decls, sharp) = realDeclarations()
        // 该 ERH 的 `#DIM` 全部在函数外（ERH 无 @ 标签），一条不漏
        assertEquals(decls.size, sharp.count { it.content.startsWith("#DIM") }, "SHARP 行都应是 #DIM 声明")
        val target = decls.firstOrNull { it.raw.contains(bitArray) }
        assertNotNull(target, "行连续块合并后必须出现 $bitArray 的声明；实际 SHARP 行 = ${sharp.map { it.content.take(40) }}")
        // 原文 `:226` 的等号后是空的，元素全在后续物理行里 ⇒ 合并串必须同时含 `=`、首个元素的
        // `1 << `、以及最后一个元素（:583 的 `1` 属于另一个块，这里只要求块尾元素 `技能補助`）
        assertTrue(target.raw.startsWith("#DIM CONST $bitArray = "), "合并串应以空等号起头：${target.raw.take(60)}")
        assertTrue("1 << COM641_AC_カードIDX_特殊効果," in target.raw, "首元素（ERH:227）应并入：${target.raw.take(120)}")
        assertTrue("1 << COM641_AC_カードIDX_ステータス補助 | 1 << COM641_AC_カードIDX_技能補助" in target.raw, "末元素（ERH:259）应并入")
        // 块内 33 个元素 ⇒ 逗号 32 个，且不含 `}`（闭合符不进内容，spec: EraStreamReader.cs:152-157）
        assertEquals(32, target.raw.count { it == ',' }, "33 元素 ⇒ 32 个逗号：${target.raw}")
        assertTrue('}' !in target.raw && '{' !in target.raw, "块标记不进逻辑行内容")
        // 逻辑行号 = 闭合 `}` 所在物理行（0 基），spec: EraStreamReader.cs:130-184 + LogicalLineReader.kt:14
        assertEquals(259, target.line, "逻辑行号 = `}`（物理 :260，0 基 259）")
    }

    @Test
    fun `真语料整份 ERH 装载零失败且 BIT 配列逐元素折成按位值`() {
        val root = erb
        Assumptions.assumeTrue(root != null, "缺 4.981 语料根：跳过")
        val (decls, _) = realDeclarations()
        val store = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))
        val result = SystemVariableBootstrap.install(store, decls, productionConfig())
        assertEquals(
            emptyList(),
            result.failures.map { "${it.raw.take(60)} → ${it.message}" },
            "整份 4.981 COMF641 ERH 的全局 #DIM 必须零失败（原版 ErhLoader.cs:249-338 逐条注册）",
        )
        val var0 = store.userVar(bitArray)
        assertNotNull(var0, "BIT 配列必须注册进变量池")
        assertTrue(store.isConst(bitArray), "CONST 修饰必须保留")
        assertEquals(1, var0.spec.dims.size, "一维数组")
        assertEquals(expectedBits.size, var0.spec.dims[0], "长度 = 33 个元素")
        for (i in expectedBits.indices) {
            assertEquals(expectedBits[i], store.readInt(bitArray, longArrayOf(i.toLong())), "$bitArray:$i")
        }
        // 反向对照「块合并是承重的」：把原文首物理行（等号后为空）单独喂装载链，
        // 必然按 InitialValueOnlyConst 方向失败、变量不进册 —— 上报里「未定义的变量」
        // 正是这类声明失踪后的运行期形态（spec: UserDefinedVariableData.cs:406-408）
        val unmerged = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))
        val bare = SystemVariableBootstrap.install(
            unmerged,
            listOf(SystemVariableBootstrap.RawDeclaration("#DIM CONST $bitArray =", erhRel, 225)),
            productionConfig(),
        )
        assertEquals(0, bare.declared, "无块合并时等号后为空 ⇒ 不注册")
        assertNull(unmerged.userVar(bitArray), "失踪变量的运行期形态就是上报的那条 CodeEE")
    }

    /** 一条 SHARP 逻辑行的取样（行号 + 内容），供失败信息回显。 */
    private data class SharpSample(val lineNo: Int, val content: String)
}
