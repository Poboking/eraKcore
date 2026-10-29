package com.eratw.port.core.code

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 操作码表与 `specs/isa.yaml` 的一一对应校验。
 *
 * 这个测试是"OpcodeTable.kt 是生成物"的**唯一可信来源**：它直接解析规约文件，
 * 断言 169 条语句、各 kind 数量、编号连续性、别名可解析。改了生成表却忘了改
 * isa.yaml（或反过来）都会在这里炸。
 */
class OpcodeTableTest {

    private data class IsaEntry(val name: String, val kind: String, val aliases: List<String>)

    private fun specFile(): File {
        val candidates = listOf("../specs/isa.yaml", "specs/isa.yaml", "../../specs/isa.yaml")
        for (c in candidates) {
            val f = File(c)
            if (f.isFile) return f
        }
        error("找不到 specs/isa.yaml（工作目录 ${File(".").absolutePath}）")
    }

    /** 极简 yaml 解析：只认 `- name:` / `kind:` / `aliases:`（含 4 空格续行）。 */
    private fun parseIsa(): List<IsaEntry> {
        val lines = specFile().readLines(Charsets.UTF_8)
        val out = ArrayList<IsaEntry>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!line.startsWith("- name: ")) {
                i++
                continue
            }
            val name = line.removePrefix("- name: ").trim()
            var kind = ""
            var aliases = emptyList<String>()
            var j = i + 1
            while (j < lines.size && !lines[j].startsWith("- name: ") && !lines[j].startsWith("summary:")) {
                val l = lines[j]
                if (l.startsWith("  kind: ")) kind = l.removePrefix("  kind: ").trim()
                if (l.startsWith("  aliases: ")) {
                    val sb = StringBuilder(l.removePrefix("  aliases: "))
                    var k = j + 1
                    while (k < lines.size && lines[k].startsWith("    ")) {
                        sb.append(' ').append(lines[k].trim())
                        k++
                    }
                    aliases = sb.toString().split(',')
                        .map { it.trim() }
                        .filter { Regex("[A-Z][A-Z0-9_]*").matches(it) }
                }
                j++
            }
            out += IsaEntry(name, kind, aliases)
            i = j
        }
        return out
    }

    @Test
    fun `169 条语句全部有操作码`() {
        val isa = parseIsa()
        assertEquals(169, isa.size, "isa.yaml 条目数与 summary.total 不符")

        for (e in isa) {
            val op = Opcode.statementOf(e.name)
            assertNotNull(op, "isa.yaml 的语句 ${e.name} 在 Opcode 表中缺失")
            assertTrue(!Opcode.isInternal(op), "${e.name} 被错编成内部指令")
        }
    }

    @Test
    fun `每条语句的 kind 与编号区间一致`() {
        val isa = parseIsa()
        val expected = mapOf(
            "flow" to 24, "display" to 48, "variable" to 33, "call" to 4, "input" to 15,
            "io" to 13, "data" to 4, "debug" to 2, "other" to 26,
        )
        assertEquals(169, expected.values.sum())

        for ((kind, count) in expected) {
            val actual = isa.count { it.kind == kind }
            assertEquals(count, actual, "kind=$kind 的语句数量与 isa.yaml summary 不符")
        }

        for (e in isa) {
            val op = Opcode.statementOf(e.name)!!
            assertEquals(
                StatementKind.valueOf(e.kind.uppercase()),
                Opcode.kindOf(op),
                "${e.name} 的操作码落在错误的 kind 段",
            )
        }
    }

    @Test
    fun `操作码编号连续无空洞`() {
        val ops = parseIsa().map { Opcode.statementOf(it.name)!! }.sorted()
        assertEquals(169, ops.toSet().size, "不同语句复用了同一个操作码")
        assertEquals((Opcode.INTERNAL_COUNT..Opcode.MAX_STATEMENT_OPCODE).toList(), ops)
        assertEquals(
            244,
            Opcode.NAMES.size,
            "内部指令 29 + 语句 169 + 编译器扩展 4（198..201）+ 栈码 42（202..243）",
        )
        assertEquals(Opcode.MAX_OPCODE, Opcode.NAMES.size - 1, "MAX_OPCODE 必须等于 NAMES 末位")
    }

    @Test
    fun `名字表与常量一一对应`() {
        assertEquals(Opcode.NAMES.size, Opcode.BY_NAME.size, "NAMES 有重名")
        for (i in Opcode.NAMES.indices) {
            assertEquals(i, Opcode.BY_NAME[Opcode.NAMES[i]])
            assertEquals(Opcode.NAMES[i], Opcode.nameOf(i))
        }
        assertTrue(Opcode.NAMES.take(Opcode.INTERNAL_COUNT).all { it.startsWith("_") })
        assertTrue(
            Opcode.NAMES.slice(Opcode.INTERNAL_COUNT..Opcode.MAX_STATEMENT_OPCODE)
                .none { it.startsWith("_") },
            "29..197 是 isa 语句的规范名，不得带下划线",
        )
        assertTrue(
            Opcode.NAMES.slice(Opcode.EXTENSION_OPS).all { it.startsWith("_") },
            "198..201 是编译器扩展指令（下划线前缀）",
        )
    }

    @Test
    fun `编译器扩展指令 198 到 201 名字齐全且不是语句`() {
        assertEquals(
            listOf("_CALL_BY_NAME", "_JUMP_BY_NAME", "_JUMP_DYN", "_RETS"),
            Opcode.NAMES.slice(Opcode.COMPILER_EXTENSION_OPS),
        )
        assertEquals(198..201, Opcode.COMPILER_EXTENSION_OPS)
        for (op in Opcode.COMPILER_EXTENSION_OPS) {
            assertTrue(Opcode.isExtension(op), "op=$op 应判为扩展指令")
            assertTrue(!Opcode.isStackOp(op), "op=$op 不是栈码")
            assertTrue(!Opcode.isInternal(op), "op=$op 不是 VM 内部指令")
            assertEquals(Opcode.NAMES[op], Opcode.nameOf(op), "反汇编不得再打印 <op:$op>")
            assertEquals(op, Opcode.BY_NAME[Opcode.NAMES[op]], "汇编写回需要名字反查")
        }
        assertEquals(null, Opcode.statementOf("_CALL_BY_NAME"), "扩展指令不是 ERB 语句")
        assertEquals(null, Opcode.statementOf("_RETS"))
    }

    /**
     * 栈码组（T2-21）：编号 202..243 连续、42 条、全部下划线前缀、反查可达、判为扩展指令
     * 但**不是** ERB 语句。这条用例是「指令名单冻结」的锁：增删栈码必须同步改
     * `Opcode.NAMES`、`STACK_OPS` 与 `EXTENSION_OPS` 三处，漏一处即红。
     */
    @Test
    fun `栈码组 202 到 243 名字齐全且与编号一一对应`() {
        assertEquals(202..243, Opcode.STACK_OPS, "栈码编号区间漂移")
        assertEquals(42, Opcode.STACK_OPS.count(), "栈码条数应为 42")
        assertEquals(202, Opcode.STACK_OPS.first, "栈码组必须紧接编译器扩展（201）之后")
        assertEquals(Opcode.MAX_OPCODE, Opcode.STACK_OPS.last)
        assertEquals(198..243, Opcode.EXTENSION_OPS, "扩展区 = 编译器扩展 + 栈码组")

        val expectedHead = listOf(
            "_STK_PUSH_IMM", "_STK_PUSH_STR", "_STK_DUP_I", "_STK_DROP", "_STK_DROP_S",
            "_STK_PUSH_VAR_I", "_STK_PUSH_VAR_S", "_STK_PLUS_VAR_I", "_STK_STORE_VAR_I",
            "_STK_STORE_VAR_S",
        )
        assertEquals(expectedHead, Opcode.NAMES.slice(202..211), "栈码组前十条的名字/顺序被改动")

        for (op in Opcode.STACK_OPS) {
            val name = Opcode.NAMES[op]
            assertTrue(name.startsWith("_STK_"), "op=$op 的栈码名必须以 _STK_ 开头：$name")
            assertEquals(op, Opcode.BY_NAME[name], "汇编写回需要名字反查")
            assertEquals(name, Opcode.nameOf(op), "反汇编不得打印 <op:$op>")
            assertTrue(Opcode.isStackOp(op), "op=$op 应判为栈码")
            assertTrue(Opcode.isExtension(op), "栈码属于扩展区（不是 isa 语句）")
            assertTrue(!Opcode.isInternal(op), "op=$op 不是 VM 内部指令")
            assertEquals(null, Opcode.statementOf(name), "栈码不是 ERB 语句")
            assertEquals(null, Opcode.kindOf(op), "栈码不属于任何语句 kind")
        }
    }

    @Test
    fun `isa 别名全部可解析到所属语句`() {
        val isa = parseIsa()
        var checked = 0
        for (e in isa) {
            val expected = Opcode.statementOf(e.name)!!
            for (alias in e.aliases) {
                // FORCE_BEGIN 既是 BEGIN 的别名又是独立语句名：规范名优先（见 OpcodeTable.kt）
                if (alias != e.name && alias in Opcode.BY_NAME) continue
                checked++
                assertEquals(expected, StatementAliases.resolve(alias), "别名 $alias 应解析到 ${e.name}")
            }
        }
        assertTrue(checked >= 300, "解析到的别名太少: $checked")
    }

    @Test
    fun `别名表无重复且规范名优先`() {
        assertEquals(
            StatementAliases.ALL.size,
            StatementAliases.ALL.distinctBy { it.name }.size,
            "别名表存在重名",
        )
        assertEquals(
            Opcode.FORCE_BEGIN,
            StatementAliases.resolve("FORCE_BEGIN"),
            "FORCE_BEGIN 应归给独立语句而非 BEGIN 的别名",
        )
        assertEquals(Opcode.CALL, StatementAliases.resolve("JUMP"), "JUMP 是 CALL 语句的别名")
        assertEquals(Opcode.PRINT, StatementAliases.resolve("PRINTL"))
        assertTrue(StatementAliases.BY_OPCODE[Opcode.PRINT]!!.size >= 60, "PRINT 家族别名应成规模")
    }

    @Test
    fun `未知助记符解析为空`() {
        assertEquals(null, StatementAliases.resolve("NOT_A_STATEMENT"))
        assertEquals(null, Opcode.statementOf("_NOP"))
    }
}
