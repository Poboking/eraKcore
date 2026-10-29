package com.eratw.port.erbcc.compile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * ERH 装载拓扑审计（[ErhLoadAudit]）的可执行判据。
 *
 * 前提由 `reference/emuera-cs` 钉死：**PC 没有 `#INCLUDE`/`#INCLUDEFROM`**，ERH 由目录扫描
 * 整体装载（`ErhLoader.cs:42` + `Config.cs:644-706`）。所以这里不测「包含展开/去重/递归保护」，
 * 而是测三条真实推论：
 * 1. `#INCLUDE` 行是**未知预处理指令**（`ErhLoader.cs:109-127` → level 2 中止），
 *    审计必须点名它、且**不得**去解析它指向的路径（不造 PC 没有的语义）；
 * 2. 「同一份 ERH 被装两遍」的真实症状是跨文件 `#DEFINE` / 全局 `#DIM` 撞名
 *    （`IdentifierDictionary.cs:324-327` / `:287-290` → level 2）；
 * 3. 同一物理文件进清单两次同样等价于装两遍。
 */
class ErhLoadAuditTest {

    private fun write(dir: Path, name: String, text: String): Path {
        val p = dir.resolve(name)
        p.parent?.let { Files.createDirectories(it) }
        Files.write(p, text.toByteArray(Charsets.UTF_8))
        return p
    }

    @Test
    fun `干净的 ERH 集合无 finding`(@TempDir dir: Path) {
        val a = write(dir, "A.ERH", "#DEFINE FLAG 1\n#DIM CONST K = 3\n")
        val b = write(dir, "B.ERH", "#DIMS CONST NAME = \"x\"\n")
        assertEquals(emptyList(), ErhLoadAudit.audit(listOf(a, b)))
    }

    @Test
    fun `INCLUDE 行按未知预处理指令报且不展开路径`(@TempDir dir: Path) {
        val a = write(
            dir, "A.ERH",
            """
            #DEFINE FLAG 1
            #INCLUDE 不存在的子目录/OTHER.ERH
            #INCLUDEFROM ..\UP.ERH
            """.trimIndent() + "\n",
        )
        val issues = ErhLoadAudit.audit(listOf(a))
        assertEquals(2, issues.size, "两行都要报: $issues")
        assertEquals(listOf(ErhIssueKind.INCLUDE_DIRECTIVE, ErhIssueKind.INCLUDE_DIRECTIVE), issues.map { it.kind })
        assertEquals(listOf(2, 3), issues.map { it.line }, "行号是 1 基物理行")
        for (i in issues) {
            assertTrue(i.message.contains("不展开"), "文案必须明说不展开: ${i.message}")
            assertTrue(i.message.contains("ErhLoader.cs:109-127"), "必须带 C# 证据: ${i.message}")
        }
        // 关键：目标文件不存在**不该**产生第二条 finding —— 审计不解析包含路径
        assertTrue(issues.none { it.kind == ErhIssueKind.UNREADABLE }, "审计不得去读被包含的目标: $issues")
    }

    @Test
    fun `跨文件宏重名按 level2 冲突报`(@TempDir dir: Path) {
        val a = write(dir, "A.ERH", "#DEFINE DUP 1\n")
        val b = write(dir, "sub/B.ERH", "#DEFINE dup 2\n")
        val issues = ErhLoadAudit.audit(listOf(a, b))
        assertEquals(1, issues.size, "$issues")
        val issue = issues.single()
        assertEquals(ErhIssueKind.DUPLICATE_MACRO, issue.kind)
        assertEquals(1, issue.line, "重名行在 B.ERH 的第 1 行")
        assertTrue(issue.message.contains("A.ERH:1"), "必须指出先到者: ${issue.message}")
        assertTrue(issue.message.contains("IdentifierDictionary.cs:324-327"), "必须带 C# 证据: ${issue.message}")
    }

    @Test
    fun `跨文件全局变量重名按 level2 冲突报`(@TempDir dir: Path) {
        val a = write(dir, "A.ERH", "#DIM SHARED_X\n")
        val b = write(dir, "B.ERH", "#DIM GLOBAL shared_x, 4\n")
        val issues = ErhLoadAudit.audit(listOf(a, b))
        assertEquals(1, issues.size, "$issues")
        val issue = issues.single()
        assertEquals(ErhIssueKind.DUPLICATE_GLOBAL_DIM, issue.kind)
        assertTrue(issue.message.contains("A.ERH:1"), "必须指出先到者: ${issue.message}")
        assertTrue(issue.message.contains("UserDefinedVariableData.cs:277-289"), "必须带 C# 证据: ${issue.message}")
    }

    @Test
    fun `同一物理文件进清单两次被检出`(@TempDir dir: Path) {
        val a = write(dir, "A.ERH", "#DEFINE FLAG 1\n")
        val issues = ErhLoadAudit.audit(listOf(a, a))
        assertEquals(1, issues.size, "$issues")
        assertEquals(ErhIssueKind.DUPLICATE_FILE_IDENTITY, issues.single().kind)
    }

    @Test
    fun `读不出来的 ERH 留痕而不是静默`(@TempDir dir: Path) {
        val missing = dir.resolve("GONE.ERH")
        val issues = ErhLoadAudit.audit(listOf(missing))
        assertEquals(1, issues.size, "$issues")
        assertEquals(ErhIssueKind.UNREADABLE, issues.single().kind)
    }
}
