package com.eratw.port.core.patch

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.OverrideSignatureException
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.SymbolConflictException
import com.eratw.port.core.compile.CompileException
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * D-3：kojo 派发覆盖契约（Override + SUPER）的可执行形态（era-arch §16）。
 *
 * ## 契约原型（语料实读，不是设计想象）
 *
 * 4.980 语料里口上只有一个总派发入口 `@KOJO_MESSAGE_SEND(…)`
 * （`ERB/口上・メッセージ関連/KOJO_MESSAGE.ERB:17`），全树 **684 处**调用点全部按名调它
 * （`CALL KOJO_MESSAGE_SEND("EVENT", …)` 等）；函数体按 `ARGS` 的 `SELECTCASE` 分派，
 * 再经 `KOJO_ACTIVE_INFO`（`COMMON_KOJO.ERB:14`）取口上包名，拼
 * `TRYCALLFORM M_KOJO%KOJO_NAME%_<KIND>_K{NO:TARGET}` 落到具体角色行。
 *
 * 因此「子嗣路由」的契约是：**同名覆盖这一个派发函数**，子嗣分支走 Mod 自己的路由，
 * 其余角色 `CALL SUPER` 交还原版 —— 684 个调用点一字节不改，也不替换 TRYCALLFORM。
 *
 * ## 本套件钉什么
 *
 * 1. base 段逐指令不变（vanilla 字节不动），dispatch 把 base 的派发入口导向覆盖者；
 * 2. 覆盖者里的 `CALL SUPER` 被回填成**前一实现 + DIRECT 位**（绕 dispatch 防成环）；
 * 3. `TRYCALL SUPER` 无前实现 ⇒ 整条折 `_NOP`（不写返回槽）；
 * 4. MODERN 未声明覆盖 / 签名不可验证 ⇒ 链接期整组拒绝；
 * 5. base 编译档（`allowSuperCalls=false`）的 `SUPER` 仍是普通函数名（行为零变化）；
 * 6. Mod 编译档拒绝定义 `@SUPER`、拒绝 `JUMP SUPER` / `TRYCCALL SUPER`。
 */
class KojoOverrideContractTest {

    private val sig = "kojo-sig-v1"

    private val sigOf: (String) -> String = { if (it == "KOJO_MESSAGE_SEND") sig else "" }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /**
     * 最小 base：函数 0 = 派发入口（`KOJO_MESSAGE_SEND`），函数 1 = 原版调用点
     * （`CALL <派发入口>`，模拟 684 个按名调用之一）。
     */
    private fun buildBase(): Program {
        val b = CodeBuilder()
        val dispatcher = b.declareFunction("KOJO_MESSAGE_SEND", 0, arity = 2, slotCount = 4)
        check(dispatcher == 0)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val caller = b.declareFunction("VANILLA_CALLER", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._CALL, dispatcher, 0, SlotAddr.NONE, 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 2)
        return b.build(caller)
    }

    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "KOJO_MESSAGE_SEND", argIntCount = 2),
        FuncMeta(index = 1, key = "VANILLA_CALLER"),
    )

    private fun parseErb(vararg lines: String): List<Statement> {
        val parser = LineParser(SymbolTable().declareFunction("SUPER", OperandType.INT))
        return lines.mapIndexedNotNull { i, raw ->
            val text = raw.trimStart()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            parser.parseLogicalLine(LogicalLine(kind, "kojo-contract.erb", i + 1, text))
        }
    }

    /** 零基线模块：函数 0 = [funcName]，体内含 [body]（`CALL SUPER` 由编译器发哨兵）。 */
    private fun module(
        body: Array<String> = arrayOf("CALL SUPER(\"ENCOUNTER\", 1)", "RETURN 1"),
        packageId: String = "kojo.route",
        override: Set<String> = setOf("KOJO_MESSAGE_SEND"),
        funcName: String = "KOJO_MESSAGE_SEND",
    ): LinkableModule {
        val c = ErbProgramCompiler(allowSuperCalls = true)
        c.compileFunction(
            FuncSig(funcName, 2, isMethod = false, paramNames = listOf("ARGS", "ARG:1")),
            parseErb(*body),
            knownFunctions = emptySet(),
        )
        val program = c.build(funcName)
        return object : LinkableModule {
            override val descriptor = ModuleDescriptor(packageId = packageId, overrideFunctions = override)
            override val program = program
            override val functions = listOf(FuncMeta(index = 0, key = funcName))
            override fun signatureHashOf(functionKey: String): String = sigOf(functionKey)
        }
    }

    // ------------------------------------------------------------------
    // 1/2：覆盖 + SUPER
    // ------------------------------------------------------------------

    @Test
    fun `同名覆盖后 base 段逐指令不变而 dispatch 导向覆盖者`() {
        val base = buildBase()
        val linked = ModuleLinker.link(base, baseMetas, listOf(module()), ResolverPolicy.MODERN, sigOf)

        for (pc in 0 until base.instructionCount) {
            assertEquals(base.op(pc), linked.program.op(pc), "base pc=$pc 的 op")
            assertEquals(base.a(pc), linked.program.a(pc), "base pc=$pc 的 a")
            assertEquals(base.b(pc), linked.program.b(pc), "base pc=$pc 的 b")
            assertEquals(base.c(pc), linked.program.c(pc), "base pc=$pc 的 c")
        }

        val catalog = linked.catalog
        assertEquals(2, catalog.dispatch[0], "base 派发入口的 dispatch 必须导向覆盖者（绝对下标 = base 函数数）")
        assertEquals(0, catalog.superChain[2], "覆盖者的前一同名实现 = base 原文")
        assertEquals(-1, catalog.superIndexOf(0), "base 原文没有前实现")
        assertTrue(linked.hasOverrides, "有非恒等 dispatch ⇒ HAS_OVERRIDES 必须为真")
    }

    @Test
    fun `CALL SUPER 回填前一实现并置 DIRECT 位`() {
        val base = buildBase()
        val linked = ModuleLinker.link(base, baseMetas, listOf(module()), ResolverPolicy.MODERN, sigOf)
        val callPc = (base.instructionCount until linked.program.instructionCount)
            .first { linked.program.op(it) == Opcode._CALL }
        assertEquals(0, linked.program.a(callPc), "SUPER 的目标 = base 派发入口的绝对下标")
        assertTrue(
            linked.program.b(callPc) and Opcode.DIRECT_CALL_FLAG != 0,
            "SUPER 必须置 DIRECT（绕过 dispatch，否则二次分派成环）",
        )
        // 对照组：base 自己的调用点不带 DIRECT（照常走 dispatch 到覆盖者）。
        val baseCallPc = (0 until base.instructionCount)
            .first { linked.program.op(it) == Opcode._CALL }
        assertEquals(0, linked.program.b(baseCallPc) and Opcode.DIRECT_CALL_FLAG, "vanilla 调用点不得置 DIRECT")
    }

    @Test
    fun `TRYCALL SUPER 无前实现时整条折 NOP`() {
        val base = buildBase()
        val m = module(
            body = arrayOf("TRYCALL SUPER(\"ENCOUNTER\", 1)", "RETURN 1"),
            packageId = "kojo.try",
            override = emptySet(),
            funcName = "ONLY_HERE",
        )
        // 实参求值指令在 _CALL 之前，先按模块自身指令流定位调用点。
        val modCallPc = (0 until m.program.instructionCount).first { m.program.op(it) == Opcode._CALL }
        val linked = ModuleLinker.link(base, baseMetas, listOf(m), ResolverPolicy.MODERN, sigOf)
        val pc = base.instructionCount + modCallPc
        assertEquals(Opcode._NOP, linked.program.op(pc), "无 super 的 TRYCALL SUPER 必须折 NOP")
        assertEquals(0, linked.program.a(pc), "NOP 不得留下哨兵")
        assertEquals(0, linked.program.b(pc))
        assertEquals(0, linked.program.c(pc))
    }

    // ------------------------------------------------------------------
    // 3：负控（链接期整组拒绝）
    // ------------------------------------------------------------------

    @Test
    fun `MODERN 未声明覆盖即拒`() {
        val ex = assertFailsWith<SymbolConflictException> {
            ModuleLinker.link(
                buildBase(), baseMetas,
                listOf(module(override = emptySet())),
                ResolverPolicy.MODERN, sigOf,
            )
        }
        assertTrue(ex.message!!.contains("KOJO_MESSAGE_SEND"), "冲突报告要点名符号，实际: ${ex.message}")
    }

    @Test
    fun `签名不可验证即拒`() {
        assertFailsWith<OverrideSignatureException> {
            ModuleLinker.link(
                buildBase(), baseMetas,
                listOf(module()),
                ResolverPolicy.MODERN,
                { "" },   // base 侧未导出签名 ⇒ fail-closed
            )
        }
    }

    // ------------------------------------------------------------------
    // 4：编译档隔离
    // ------------------------------------------------------------------

    @Test
    fun `base 编译档的 SUPER 仍是普通函数名`() {
        val c = ErbProgramCompiler()   // allowSuperCalls = false
        val known = setOf("SUPER", "CALLER")
        c.compileFunction(
            FuncSig("SUPER", 1, isMethod = false, paramNames = listOf("ARG")),
            parseErb("RETURN 0"),
            known,
        )
        c.compileFunction(
            FuncSig("CALLER", 0, isMethod = false),
            parseErb("CALL SUPER(1)", "RETURN 0"),
            known,
        )
        val p = c.build("CALLER")
        val callPc = (0 until p.instructionCount).first { p.op(it) == Opcode._CALL }
        assertEquals(0, p.a(callPc), "base 档的 CALL SUPER 解析到普通函数 @SUPER（不是哨兵）")
    }

    @Test
    fun `Mod 编译档拒绝定义 SUPER 且拒绝跳转形态`() {
        val reserved = assertFailsWith<CompileException> {
            ErbProgramCompiler(allowSuperCalls = true).compileFunction(
                FuncSig("SUPER", 1, isMethod = false),
                parseErb("RETURN 0"),
                emptySet(),
            )
        }
        assertTrue(reserved.message!!.contains("保留名"), "实际: ${reserved.message}")

        val jump = assertFailsWith<CompileException> {
            ErbProgramCompiler(allowSuperCalls = true).compileFunction(
                FuncSig("X", 0, isMethod = false),
                parseErb("JUMP SUPER(1)", "RETURN 0"),
                emptySet(),
            )
        }
        assertTrue(jump.message!!.contains("不支持跳转"), "实际: ${jump.message}")
    }
}
