package com.eratw.port.core.compile

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * R7 钉子 A1（CONTRACT-R7 §2 波 A）：`CodeBuilder` 能不能「先编 base、再续编 patch」。
 *
 * ## 这个钉子存在的理由
 *
 * R7 的整个波 B 押在一个假设上：补丁可以用**同一个 builder 续编**，从而让补丁的
 * pc / 池下标天然接在 base 之后（绝对下标），装载期零改写。这个假设**没有在代码里验证过**，
 * 而 [CodeBuilder.build] 的实现看起来并不支持它。本测试把三件事实钉死，供波 B 决策：
 *
 * 1. **`build()` 会封死池**：`Program` 构造期调 `ConstantPool.seal()`（`Program.kt:731`），
 *    把 `longIndex` / `stringIndex` / `exprIndex` 置空 ⇒ 之后再 `string()` / `constant()` /
 *    `expr()` 必然炸（`!!` 抛 NPE）。也就是说「续编」在池这一层**物理不通**。
 * 2. **语句池是唯一例外，且与 Program 共享同一实例**：`build()` 把 `pool` 按引用交给
 *    `Program`（`Program.kt:1516` 的 `pool = pool`），`addStatement` 冻结后仍可用
 *    （`ConstantPool` 的 KDoc 明写「ErbcReader 重灌即依赖这一点」）⇒ 续编语句会**反向污染**
 *    已建好的 Program。这一条比第 1 条更危险：它不会报错，只会让 base 的 `statementCount`
 *    悄悄变大。
 * 3. **pc 从 0 起、code 是拷贝**：两个独立 builder 各自的入口 pc 都是 0 ⇒ 拼接必须显式
 *    加 `codeBase` 偏移；而 `build()` 里 `code.copyOf(count * WORDS)` 是深拷贝，所以已建
 *    Program 的指令流不会被续编改写（这一条是**好消息**，也是尾段方案成立的基础）。
 *
 * ## 结论（写进波 B 契约用）
 *
 * 不能复用 base 的 builder。补丁必须新建 builder，且 [CodeBuilder] / `ConstantPool` 需要
 * 支持「预置基线」（`codeBase` + 池 base），否则补丁的 pc 与池下标只能靠装载期遍历改写 ——
 * 那正是 R7 方案里明确要避免的高风险路径。
 *
 * 这三条都是**现状特征化**，不是期望行为。波 B 加了基线预置之后，本测试的 1、2 两条应当
 * 改成「预置后仍可用、且不污染 base」，那时它们才变成正向判据。
 */
class IncrementalCompileParityTest {

    /** 造一个「一条 `_RET` 的最小函数」的 builder，返回 builder 与入口 pc。 */
    private fun minimalBuilder(name: String): Pair<CodeBuilder, Int> {
        val b = CodeBuilder()
        val entry = b.emit(Opcode._RET, 0, 0, 0)
        b.declareFunction(name, entry, arity = 0, slotCount = 1)
        return b to entry
    }

    @Test
    fun `build 之后池被封死，续编常量与字符串必然失败`() {
        val (b, _) = minimalBuilder("BASE")
        b.string("hello")
        b.build(0)

        // seal() 之后 add* 先过 checkWritable()，抛 IllegalStateException（不是 `!!` 的 NPE ——
        // 实测首跑按 NPE 断言被证伪，记在这里免得后人重踩）。
        assertFailsWith<IllegalStateException>("池已封，字符串追加必须失败") { b.string("after") }
        assertFailsWith<IllegalStateException>("池已封，常量追加必须失败") { b.constant(1L) }
    }

    @Test
    fun `语句池不受封印，续编语句会反向污染已建好的 Program`() {
        val (b, _) = minimalBuilder("BASE")
        val p1 = b.build(0)
        val before = p1.statementCount

        // 唯一在冻结后仍可追加的池（ConstantPool 的 KDoc 明写 ErbcReader 重灌依赖它）。
        b.statement(RendStmt(VoidArgument(), 0, "REND"))

        assertEquals(
            before + 1,
            p1.statementCount,
            "语句池按引用共享 ⇒ 续编会改到已建 Program 的读数（这是共享，不是 bug，但波 B 必须知道）",
        )
    }

    @Test
    fun `code 是深拷贝，续编不会改写已建 Program 的指令流`() {
        val (b, _) = minimalBuilder("BASE")
        val p1 = b.build(0)
        assertEquals(1, p1.instructionCount)

        b.emit(Opcode._NOP, 0, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0)
        val p2 = b.build(0)

        assertEquals(1, p1.instructionCount, "已建 Program 的指令数不受续编影响")
        assertEquals(3, p2.instructionCount, "第二次 build 会把续编的指令一并带出")
    }

    @Test
    fun `两个独立 builder 的入口 pc 都从 0 起，拼接必须显式加 codeBase`() {
        val (base, baseEntry) = minimalBuilder("BASE")
        val (patch, patchEntry) = minimalBuilder("PATCH")

        assertEquals(0, baseEntry)
        assertEquals(0, patchEntry, "补丁若原样拼进同一 pc 空间，会与 base 首条指令撞车")

        val baseProgram = base.build(0)
        assertEquals(1, baseProgram.instructionCount)
        assertEquals(0, baseProgram.functions[0].entryPc)
    }
}
