package com.eratw.port.core.loader

import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.SpSetArgument
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真产物 FOR 步进语句的**语义**探针（红 K 取证转正，2026-10-25）。
 *
 * 背景：`FOR X,0,N` 的步进语句是编译器合成的 `X += 1` 常量折态
 * （`SpSetArgument(term=null, isConst=true, addConst=true, constInt=1)`，ErbProgramCompiler
 * 的 `synthAssign`）；执行层按 `IsConst ? ConstInt : Term` 取值（StatementExecutor 的 SET 分支，
 * spec: FunctionIdentifier.cs:647-658）。**字节恒等的往返闸测不出这个字段的丢失**——解码侧
 * 若漏回填 `isConst/constInt`，语句再编码字节不变、但执行成 `X += 0`：FOR 计数器恒 0、
 * 环体无限重跑（真机红 K：GMF_METHOD_RESTORE 的 `FOR LOCAL,0,CHARANUM` 转 5M 步；
 * 桌面 E2E/bootSmoke 走源码直编、内存对象字段完好，全绿看不见）。
 *
 * 本闸对着 `ERATW_ERBC_DIR`（或本地缺省路径）的真产物逐条断言 `[FOR STEP]` 语句的
 * 四件套齐全：term 为 null（常量折态）、isConst 真、constInt 为步长、addConst 真。
 * 产物缺席时跳过（与既有真产物闸同口径）。
 */
class RealArtifactForStepProbeTest {

    @Test
    fun `真产物 FOR 步进语句的常量折态四件套齐全`() {
        val erbcFile = artifactDir()?.resolve("program.erbc")
        assumeTrue(erbcFile != null, "真产物缺席，跳过（设 ERATW_ERBC_DIR 指到 erbc.v1 目录启用）")
        val program = ProgramLoader.loadMapped(erbcFile!!) { }
        val total = program.statementCount
        assertTrue(total > 0, "产物语句池为空")
        var seen = 0
        var constSeen = 0
        val broken = ArrayList<String>()
        for (i in 0 until total) {
            val s = program.statement(i)
            if (!s.raw.contains("[FOR STEP]")) continue
            seen++
            val arg = (s as? AssignStmt)?.args as? SpSetArgument
            if (arg == null) {
                broken.add("#$i 非 SpSetArgument: ${s.javaClass.simpleName} raw=${s.raw}")
                continue
            }
            if (arg.isConst && arg.addConst && arg.term == null) {
                // 常量步长快路径（ArgumentParser.cs:1073-1090 的折叠形态）
                constSeen++
                assertTrue(
                    arg.constInt != 0L,
                    "#$i FOR 步进常量为 0（步长退零 ⇒ 计数器恒 0）：raw=${s.raw}",
                )
            } else if (arg.term != null) {
                // 非常量步长形态（term = Binary 读 FOR 行快照槽）：合法。
            } else {
                broken.add(
                    "#$i 折态破损（term 空且非常量 ⇒ 执行成 X += 0）: isConst=${arg.isConst} " +
                        "addConst=${arg.addConst} constInt=${arg.constInt} raw=${s.raw}",
                )
            }
        }
        assertTrue(seen > 0, "产物里一条 [FOR STEP] 都没扫到（语句池规模 $total）——扫描口径失效")
        assertEquals(
            emptyList(),
            broken.take(8),
            "FOR 步进折态破损 ${broken.size}/$seen 条（首 8 条）",
        )
        assertTrue(constSeen > 0, "常量步长形态一条都没有（语料以常量步长为主，不该为 0）")
    }

    private fun artifactDir(): Path? {
        System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        return listOf(
            Path.of("build", "erbc-out", "erbc.v1"),
            Path.of("tools", "erbcc", "build", "erbc-out", "erbc.v1"),
        ).firstOrNull { Files.isRegularFile(it.resolve("program.erbc")) }
    }
}
