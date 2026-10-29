package com.eratw.port.core.loader

import com.eratw.port.core.code.CallArg
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.FunctionInfo
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M11-MEM 的装载期收口：
 *
 * 1. **函数表惰性化逐字段等价**：同一份合成 `.erbc` 分别走 `loadFile`（`ErbcReader` 全量）
 *    与 `loadMapped`（[MappedFunctionTable]），函数数 / 每条 FuncMeta / 四个池 / 指令 / 行号
 *    全部相等；装载完 `materializedFunctionCount == 0`，按下标取过几条就是几条。
 * 2. **乱序访问与实例身份**：同一下标恒返回同一 `FunctionInfo`，取过的下标都解出正确字段。
 * 3. **畸形产物**：函数段里四处装载期判界（标志位 / entryPc / 标签计数 / 重名）在两条装载
 *    路径上抛**同一句话**，且惰性档不许把报错推迟到取值。
 * 4. **分段 mark**：容器头 / 段偏移表 / 语句侧车 / 函数表 / 指令流 / 行号表都在序列里按序出现。
 */
class M11LazyFunctionTableTest {

    /**
     * 三函数夹具：`MAIN`（带两个形参名与一个 `$TOP` 标签）、`SUB`（一个形参名 + `$L`）、
     * `PLAIN`（无标签无形参）。表达式池 / 常量池 / 字符串池 / 语句池各至少一条。
     *
     * [withParamNames] = false 时只丢掉段 8（形参名），段 7（调用点实参）照写 —— 这正是
     * 「调用点表非空、形参名段缺失」的组合，`bindsCallArgsPositionally` 的惰性判据只在这里
     * 才真的被走到（`callArgs` 为空时它会短路）。
     */
    private fun fixture(withParamNames: Boolean = true): Program {
        val b = CodeBuilder()
        val names = if (withParamNames) listOf("A", "B$") else emptyList()
        val main = b.declareFunction("MAIN", 0, 2, 4, names)
        val c = b.constant(7L)
        val s = b.string("hello")
        b.emit(Opcode._SET_INT, SlotAddr.local(0), c, 0, 1)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s, 0, 2)
        b.emit(Opcode._EVAL_INT, b.expr(IntLiteral(3L, 2, 2)), SlotAddr.local(2), 0, 3)
        b.emit(
            Opcode.WAIT,
            b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), 4, "WAIT")),
            0,
            SlotAddr.NONE,
            4,
        )
        b.declareLabel(main, "TOP", 0)

        val subPc = b.here()
        val sub = b.declareFunction("SUB", subPc, 1, 2, if (withParamNames) listOf("X") else emptyList())
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 5)
        b.declareLabel(sub, "L", subPc)

        val callPc = b.emit(Opcode._CALL, sub, 0, 0, 6)
        b.declareCallArgs(
            callPc,
            listOf(CallArg(position = 0, bucket = 0, isStr = false, name = "A", present = true)),
        )
        b.declareFunction("PLAIN", b.here(), 0, 0)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        b.noteGlobalSlot(1)
        return b.build(0)
    }

    private fun write(
        dir: Path,
        name: String = "program.erbc",
        program: Program = fixture(),
    ): Path {
        val f = dir.resolve(name)
        Files.write(f, ErbcWriter.encode(program))
        return f
    }

    private fun assertSameProgram(
        eager: Program,
        lazy: Program,
    ) {
        assertEquals(eager.instructionCount, lazy.instructionCount, "指令条数")
        assertContentEquals(eager.code, lazy.code, "code 全量")
        assertContentEquals(eager.lines, lazy.lines, "lines 全量")
        assertEquals(eager.globalSlots, lazy.globalSlots, "全局槽")
        assertEquals(eager.entryFunction, lazy.entryFunction, "入口函数")

        assertEquals(eager.constantCount, lazy.constantCount, "常量池条数")
        for (i in 0 until eager.constantCount) assertEquals(eager.constant(i), lazy.constant(i), "常量 $i")
        assertEquals(eager.stringCount, lazy.stringCount, "字符串池条数")
        for (i in 0 until eager.stringCount) assertEquals(eager.string(i), lazy.string(i), "字符串 $i")
        assertEquals(eager.exprCount, lazy.exprCount, "表达式池条数")
        for (i in 0 until eager.exprCount) assertEquals(eager.expr(i), lazy.expr(i), "表达式 $i")

        assertEquals(eager.statementCount, lazy.statementCount, "语句池条数")
        for (i in 0 until eager.statementCount) {
            val e = eager.statement(i)
            val a = lazy.statement(i)
            assertEquals(e.javaClass, a.javaClass, "语句 $i 类")
            assertEquals(e.code, a.code, "语句 $i FunctionCode")
            assertEquals(e.line, a.line, "语句 $i 行号")
            assertEquals(e.raw, a.raw, "语句 $i 原文")
        }

        assertEquals(eager.functions.size, lazy.functions.size, "函数表长度")
        for (i in 0 until eager.functions.size) {
            val e = eager.functions[i]
            val a = lazy.functions[i]
            assertEquals(e.name, a.name, "函数 $i 名")
            assertEquals(e.entryPc, a.entryPc, "函数 ${e.name} entryPc")
            assertEquals(e.arity, a.arity, "函数 ${e.name} arity")
            assertEquals(e.slotCount, a.slotCount, "函数 ${e.name} slotCount")
            assertEquals(e.labelNames.toList(), a.labelNames.toList(), "函数 ${e.name} 标签名")
            assertEquals(e.labelPcs.toList(), a.labelPcs.toList(), "函数 ${e.name} 标签 pc")
            assertEquals(e.paramNames.toList(), a.paramNames.toList(), "函数 ${e.name} 形参名")
        }
        assertEquals(eager.callArgs, lazy.callArgs, "调用点实参表")
        assertEquals(eager.bindsCallArgsPositionally, lazy.bindsCallArgsPositionally, "逐位绑定判据")
    }

    @Test
    fun `惰性函数表与整池解码逐字段等价且装载后零物化`(@TempDir dir: Path) {
        val f = write(dir)
        val eager = ProgramLoader.loadFile(f)
        val lazy = ProgramLoader.loadMapped(f)

        assertEquals(0, lazy.materializedFunctionCount, "惰性函数表装载后不得物化任何条目")

        assertSameProgram(eager, lazy)
        assertEquals(
            eager.functions.size,
            lazy.materializedFunctionCount,
            "按下标遍历后应恰好物化全表",
        )
    }

    @Test
    fun `惰性函数表乱序取值命中缓存且字段正确`(@TempDir dir: Path) {
        val f = write(dir)
        val eager = ProgramLoader.loadFile(f)
        val lazy = ProgramLoader.loadMapped(f)

        val order = intArrayOf(2, 0, 1, 2, 0, 1)
        val seen = HashMap<Int, FunctionInfo>()
        for (i in order) {
            val actual = lazy.functions[i]
            val expected = eager.functions[i]
            assertEquals(expected.name, actual.name, "函数 $i 名")
            assertEquals(expected.entryPc, actual.entryPc, "函数 $i entryPc")
            assertEquals(expected.arity, actual.arity, "函数 $i arity")
            assertEquals(expected.slotCount, actual.slotCount, "函数 $i slotCount")
            assertEquals(expected.labelNames.toList(), actual.labelNames.toList(), "函数 $i 标签名")
            assertEquals(expected.labelPcs.toList(), actual.labelPcs.toList(), "函数 $i 标签 pc")
            assertEquals(expected.paramNames.toList(), actual.paramNames.toList(), "函数 $i 形参名")
            val first = seen[i]
            if (first != null) assertSame(first, actual, "同一下标必须返回同一实例") else seen[i] = actual
            assertSame(actual, lazy.functions[i], "重复取值命中缓存")
        }
        assertEquals(3, lazy.materializedFunctionCount, "三个下标各解一次")
        assertSame(lazy.functions[1], lazy.function("SUB"), "按名查找命中同一实例")
        assertTrue(lazy.bindsCallArgsPositionally, "v2 产物：形参名与调用点表都在")
    }

    @Test
    fun `越界取值抛 IndexOutOfBounds 且不物化任何条目`(@TempDir dir: Path) {
        val p = ProgramLoader.loadMapped(write(dir))

        assertFailsWith<IndexOutOfBoundsException>("负下标") { p.functions[-1] }
        assertFailsWith<IndexOutOfBoundsException>("上界") { p.functions[p.functions.size] }
        assertEquals(0, p.materializedFunctionCount, "越界取值不得物化任何条目（与语句池同口径）")
    }

    @Test
    fun `无形参名段时逐位绑定判据为假且不物化函数`(@TempDir dir: Path) {
        // 段 7 有、段 8 无：hasAnyParamNames() 的「直接由条数表回答」那条路才真的被走到
        val f = write(dir, "no-param-names.erbc", fixture(withParamNames = false))
        val eager = ProgramLoader.loadFile(f)
        val lazy = ProgramLoader.loadMapped(f)

        assertEquals(0, lazy.materializedFunctionCount, "装载完不得物化")
        assertFalse(eager.bindsCallArgsPositionally, "整池档：没有形参名 ⇒ 判据为假")
        assertFalse(lazy.bindsCallArgsPositionally, "惰性档：由段 8 条数表回答，不物化函数")
        assertEquals(0, lazy.materializedFunctionCount, "读判据不得物化任何函数")
        assertTrue(lazy.callArgs.isNotEmpty(), "夹具必须带调用点表，否则判据短路、用例白跑")
        assertSameProgram(eager, lazy)
    }

    @Test
    fun `函数段畸形产物仍在装载期抛同一句话`(@TempDir dir: Path) {
        val base = ErbcWriter.encode(fixture())
        // 记录 0 = MAIN（24 字节定长头 + 1 条标签 × 8）
        val mutations: List<Pair<String, (ByteArray) -> Unit>> = listOf(
            "标志位非零" to { b -> b.putLe32(firstRecordAt(b) + 16, 1) },
            "entryPc 越界" to { b -> b.putLe32(firstRecordAt(b) + 4, 999) },
            "标签计数越界" to { b -> b.putLe32(firstRecordAt(b) + 20, 1 shl 30) },
            "函数重复声明" to { b ->
                val rec0 = firstRecordAt(b)
                val rec1 = rec0 + 24 + 8 * b.le32(rec0 + 20)
                b.putLe32(rec1, b.le32(rec0))
            },
        )
        for ((i, mutation) in mutations.withIndex()) {
            val bytes = base.copyOf()
            mutation.second(bytes)
            // 每个变异点写独立文件：映射存活期间同名文件在 Windows 上不能再写
            // （`ErbcStreamEquivalenceTest` 的既有口径）。
            val f = dir.resolve("bad-$i.erbc")
            Files.write(f, bytes)
            val eager = outcome { ProgramLoader.loadFile(f) }
            val lazy = outcome { ProgramLoader.loadMapped(f) }
            assertEquals(eager, lazy, "${mutation.first}：两条装载路径必须抛同一句话")
            assertTrue(eager.startsWith("err:"), "${mutation.first}：必须在装载期失败，实为 $eager")
        }
    }

    @Test
    fun `装载分段 mark 覆盖容器头 段偏移表 侧车 函数表 指令流`(@TempDir dir: Path) {
        val f = write(dir)
        val marks = ArrayList<String>()
        ProgramLoader.loadMapped(f) { marks += it }

        assertEquals("program.${LoadStages.HEADER}", marks.first(), "第一段必须是读容器头")
        for (name in listOf(
            LoadStages.HEADER,
            LoadStages.SECTION_TABLE,
            LoadStages.STMT_SIDECAR,
            LoadStages.STRINGS,
            LoadStages.CONSTANTS,
            LoadStages.EXPR_ROOTS,
            LoadStages.STMT_RECORDS,
            LoadStages.FUNCTIONS,
            LoadStages.CODE,
            LoadStages.LINES,
            LoadStages.CALL_ARGS,
            LoadStages.VALIDATE,
            LoadStages.INTERN,
        )) {
            assertTrue(marks.contains("program.$name"), "缺少分段 mark: program.$name（实际 $marks）")
        }
        // 顺序：失败屏的「卡在哪一段」靠的是 mark 的发射序，乱序就等于归因错段
        val order = listOf(
            LoadStages.HEADER,
            LoadStages.SECTION_TABLE,
            LoadStages.STMT_SIDECAR,
            LoadStages.STRINGS,
            LoadStages.CONSTANTS,
            LoadStages.FUNCTIONS,
            LoadStages.CODE,
            LoadStages.LINES,
            LoadStages.VALIDATE,
            LoadStages.INTERN,
        ).map { marks.indexOf("program.$it") }
        assertTrue(order.all { it >= 0 }, "分段 mark 缺失：$marks")
        assertEquals(order.sorted(), order, "分段 mark 的发射顺序必须与 LoadStages 表一致：$marks")
    }

    // ------------------------------------------------------------------
    // 畸形产物用的字节工具：只解析到「段 4 的记录区起点」，不做任何语义判断
    // ------------------------------------------------------------------

    /** 段 4 在文件内的起始偏移（段表第 4 槽，偏移表从 `HEADER_BYTES` 起、u32 一项）。 */
    private fun functionSectionAt(bytes: ByteArray): Int =
        bytes.le32(ErbcFormat.HEADER_BYTES + ErbcFormat.SECTION_FUNCTIONS * 4)

    /**
     * 段 4 里第一条函数记录的起始偏移：段头 12 字节（funcCount/entryFunction/globalSlots）
     * + 段内串池（count、dataLen、(count+1) 个偏移、数据）。
     */
    private fun firstRecordAt(bytes: ByteArray): Int {
        val sec = functionSectionAt(bytes)
        val stringCount = bytes.le32(sec + 12)
        val stringBytes = bytes.le32(sec + 16)
        return sec + 20 + (stringCount + 1) * 4 + stringBytes
    }

    private fun ByteArray.le32(at: Int): Int =
        (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.putLe32(
        at: Int,
        value: Int,
    ) {
        for (k in 0 until 4) this[at + k] = (value ushr (8 * k)).toByte()
    }

    /**
     * 装载结果的可比字符串：成功时**把函数表整表取一遍**再记账（惰性表若把校验推迟到取值，
     * 这里就会炸成与整池档不同的结果），失败时连异常类型一起记。
     */
    private fun outcome(block: () -> Program): String = try {
        val p = block()
        val sb = StringBuilder("ok")
        for (i in 0 until p.functions.size) {
            val f = p.functions[i]
            sb.append(';').append(f.name).append('@').append(f.entryPc).append('/').append(f.labelCount)
        }
        sb.toString()
    } catch (e: Throwable) {
        "err:${e.javaClass.name}:${e.message}"
    }
}
