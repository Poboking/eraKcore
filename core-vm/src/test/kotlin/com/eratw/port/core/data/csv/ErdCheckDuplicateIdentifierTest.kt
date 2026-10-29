package com.eratw.port.core.data.csv

import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Config.CheckDuplicateIdentifier` 的两态分叉（SAVE-09b）。
 *
 * 语义：原版 `UserDefinedVariableData.Create` 里判 `Config.UseERD && Config.CheckDuplicateIdentifier`
 * 后调 `ConstantData.isDefinedErd`（`UserDefinedVariableData.cs:269-272` +
 * `ConstantData.cs:795-806`）—— 拿本行**声明名**扫**此前已登记**的 ERD 表，命中即 level 1 告警。
 * 三条照抄的细节：
 *   * 只告警：表照挂、登记照做（`:799-805` 只 `ParserMediator.Warn(..., 1)`）；
 *   * 判据在 `Create()` 内、**本行自己的表装载之前**（`ErhLoader.cs:261` vs `:279`）
 *     ⇒ 声明名撞自己表的关键字**不**告警；
 *   * 逐表各查一次（双层 foreach）⇒ 同名关键字落在两张表里发两条。
 * 语料现值 `emuera.config:71` = YES（出厂缺省 ConfigData.cs:118 为 false），但独立实测
 * （2026-10-24，Haruki）零命中：ERB 子树 `#DIM` 声明 20,141 行 / 去重名 3,572（大写口径），
 * 会被载入的表只有 `CSV/EI_FLAG.csv` 一张（43 个关键字），与全部 `#DIM` 名交集为 0
 * ⇒ 语料上不产生告警（基准读数不变）。
 */
class ErdCheckDuplicateIdentifierTest {

    private fun store(): VariableStore =
        VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))

    private fun dim(body: String, isPrivate: Boolean = false): DimSpec =
        DimParser.parse("#DIM", body, isPrivate)

    private fun gameRoot(files: Map<String, String>): Path {
        val root = Files.createTempDirectory("erd-dup-id-test")
        for ((rel, text) in files) {
            val p = root.resolve(rel)
            p.parent?.createDirectories()
            p.writeText(text)
        }
        return root
    }

    @Test
    fun `开：声明名撞已登记表的关键字发一条告警且表照挂`() {
        val root = gameRoot(mapOf("CSV/M_TFLAG.csv" to "1,FOO\r\n"))
        val s = store()
        s.declareUserVar(dim("M_TFLAG, 10"))
        s.declareUserVar(dim("FOO, 5"))
        val warn = ArrayList<String>()
        UserKeywordTables.install(s, root, warn, checkDuplicateIdentifier = true)

        assertEquals(1, warn.size, warn.toString())
        assertTrue(warn[0].contains("変数名\"FOO\""), warn[0])
        assertTrue(warn[0].contains("ERD変数\"M_TFLAG\""), warn[0])
        // level 1 告警不动表：M_TFLAG 的关键字表照旧挂着（ConstantData.cs:799-805 只 Warn）。
        assertEquals(1L, s.keywordToInteger("M_TFLAG", 0, "FOO"))
    }

    @Test
    fun `关：同形语料不告警`() {
        val root = gameRoot(mapOf("CSV/M_TFLAG.csv" to "1,FOO\r\n"))
        val s = store()
        s.declareUserVar(dim("M_TFLAG, 10"))
        s.declareUserVar(dim("FOO, 5"))
        val warn = ArrayList<String>()
        UserKeywordTables.install(s, root, warn, checkDuplicateIdentifier = false)

        assertTrue(warn.isEmpty(), warn.toString())
        assertEquals(1L, s.keywordToInteger("M_TFLAG", 0, "FOO"))
    }

    @Test
    fun `开：声明名撞自己表的关键字不告警`() {
        // 判据在本行自己的表装载之前（ErhLoader.cs:261 Create → :279 UserDefineLoadData）。
        val root = gameRoot(mapOf("CSV/M_TFLAG.csv" to "1,M_TFLAG\r\n"))
        val s = store()
        s.declareUserVar(dim("M_TFLAG, 10"))
        val warn = ArrayList<String>()
        UserKeywordTables.install(s, root, warn, checkDuplicateIdentifier = true)

        assertTrue(warn.isEmpty(), warn.toString())
        assertEquals(1L, s.keywordToInteger("M_TFLAG", 0, "M_TFLAG"))
    }

    @Test
    fun `开：后声明的表不回溯告警（声明序敏感）`() {
        // FOO 先声明时 M_TFLAG 的表还不存在 ⇒ 原版此刻扫不到，原样复刻。
        val root = gameRoot(mapOf("CSV/M_TFLAG.csv" to "1,FOO\r\n"))
        val s = store()
        s.declareUserVar(dim("FOO, 5"))
        s.declareUserVar(dim("M_TFLAG, 10"))
        val warn = ArrayList<String>()
        UserKeywordTables.install(s, root, warn, checkDuplicateIdentifier = true)

        assertTrue(warn.isEmpty(), warn.toString())
        assertEquals(1L, s.keywordToInteger("M_TFLAG", 0, "FOO"))
    }

    @Test
    fun `开：同名关键字落在两张表里发两条`() {
        val root = gameRoot(
            mapOf(
                "CSV/ERDA.csv" to "1,FOO\r\n",
                "CSV/ERDB.csv" to "1,FOO\r\n",
            ),
        )
        val s = store()
        s.declareUserVar(dim("ERDA, 5"))
        s.declareUserVar(dim("ERDB, 5"))
        s.declareUserVar(dim("FOO, 5"))
        val warn = ArrayList<String>()
        UserKeywordTables.install(s, root, warn, checkDuplicateIdentifier = true)

        assertEquals(2, warn.size, warn.toString())
        assertTrue(warn[0].contains("ERD変数\"ERDA\""), warn[0])
        assertTrue(warn[1].contains("ERD変数\"ERDB\""), warn[1])
    }

    @Test
    fun `开：私有声明也参与判定`() {
        // 原版判据在 Create() 里、`isPrivate` 分支之前（UserDefinedVariableData.cs:269-272），
        // 所以函数内 #DIM 同样查（它自己不挂表，ErhLoader.cs:269-272 只处理 ERH 的声明）。
        val root = gameRoot(mapOf("CSV/M_TFLAG.csv" to "1,FOO\r\n"))
        val s = store()
        s.declareUserVar(dim("M_TFLAG, 10"))
        s.declareUserVar(dim("FOO, 5", isPrivate = true), "F1")
        val warn = ArrayList<String>()
        UserKeywordTables.install(s, root, warn, checkDuplicateIdentifier = true)

        assertEquals(1, warn.size, warn.toString())
        assertTrue(warn[0].contains("変数名\"FOO\""), warn[0])
        assertNull(s.userKeywordTable("FOO", 0), "FOO 没有自己的表")
    }
}
