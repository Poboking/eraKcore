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

/**
 * ERD 挂表的 `@n` 边界（合成用例；语料只用到 1 维 `M_TFLAG` 与 2 维 `COLUMNDIV@2` 两种形态）。
 *
 * spec: `ErhLoader.cs:273-313` —— 1 维变量只查裸名 `NAME`，2 维只查 `NAME@1`/`NAME@2`，
 * 3 维只查 `NAME@1..3`，更远的后缀根本不会去查；维度上限 3 由 `UserDefinedVariableData.cs:446`
 * 挡住（移植侧同一处：`UserVariables.kt:610`），所以 `@4` 之类的名字在两侧都不可达。
 */
class UserKeywordTablesBoundaryTest {

    private fun store(): VariableStore =
        VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))

    private fun dim(body: String, isPrivate: Boolean = false): DimSpec =
        DimParser.parse("#DIM", body, isPrivate)

    private fun gameRoot(files: Map<String, String>): Path {
        val root = Files.createTempDirectory("erd-bound")
        for ((rel, text) in files) {
            val p = root.resolve(rel)
            p.parent?.createDirectories()
            p.writeText(text)
        }
        return root
    }

    /** 1 维变量的键是裸名（ErhLoader.cs:275）：`NAME@1` 那份表不参与，变量没有表。 */
    @Test
    fun `一维变量不读同名 at 1 表`() {
        val root = gameRoot(mapOf("CSV/EKB21@1.csv" to "1,A\r\n"))
        val s = store()
        s.declareUserVar(dim("EKB21, 10"))
        UserKeywordTables.install(s, root)
        assertNull(s.userKeywordTable("EKB21", 0))
    }

    /** 2 维变量只查 `@1`/`@2`（ErhLoader.cs:285）：`@3` 那份连读都不读。 */
    @Test
    fun `二维变量忽略 at 3 表`() {
        val root = gameRoot(
            mapOf(
                "CSV/EKB22@2.csv" to "1,B\r\n",
                "CSV/EKB22@3.csv" to "1,C\r\n",
            ),
        )
        val s = store()
        s.declareUserVar(dim("EKB22, 10, 20"))
        UserKeywordTables.install(s, root)
        assertNull(s.userKeywordTable("EKB22", 0), "@1 没有表")
        assertEquals(mapOf("B" to 1), s.userKeywordTable("EKB22", 1))
        assertEquals(1L, s.keywordToInteger("EKB22", 1, "B"))
        assertNull(
            s.userKeywordTable("EKB22", 1)?.get("C"),
            "@3 的键不得混进 @2 的表（原版键名就是 NAME@n，:287）",
        )
    }

    /** CHARADATA 的完整下标位整体后移一位（角色维在前）：`@1`→位 1、`@2`→位 2（ErhLoader.cs:291）。 */
    @Test
    fun `CHARADATA 二维变量两维各后移一位`() {
        val root = gameRoot(
            mapOf(
                "CSV/EKB23@1.csv" to "1,A\r\n",
                "CSV/EKB23@2.csv" to "1,B\r\n",
            ),
        )
        val s = store()
        s.declareUserVar(dim("CHARADATA EKB23, 10, 20"))
        UserKeywordTables.install(s, root)
        assertNull(s.userKeywordTable("EKB23", 0), "角色维上没有表")
        assertEquals(mapOf("A" to 1), s.userKeywordTable("EKB23", 1))
        assertEquals(mapOf("B" to 1), s.userKeywordTable("EKB23", 2))
        assertEquals(1L, s.keywordToInteger("EKB23", 2, "B"))
    }
}
