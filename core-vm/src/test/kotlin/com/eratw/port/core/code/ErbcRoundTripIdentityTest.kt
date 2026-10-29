package com.eratw.port.core.code

import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.SP_CHARACTER_FEATURE_DISABLED
import com.eratw.port.core.fn.SP_CHARA_CONFIG_IS_OFF
import com.eratw.port.core.fn.spCharaEnabled
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.FakeConsoleSink
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `.erbc` 往返的**语句身份**差分 + SP 门控同源（CONTRACT-M4-I §2 i-erbcround）。
 *
 * ## 被检验的风险
 *
 * 组长 2026-10-20 定位：[StatementAliases] 把 `ADDSPCHARA` 折成
 * `Opcode.ADDCHARA` + variant 1（OpcodeTable.kt:825），`DELCHARA` 折成 variant 2（:826）；
 * 而运行期 SP 门控看的是**语句码**（StatementExecutor.kt:554-562，
 * 口径源 FunctionIdentifier.cs:1344-1397，其中 `:1360-1363` 的 SP 守卫在 `:1368` 的
 * 实参求值循环之前）。怀疑：源码直建语义正确，但经 `.erbc` 写盘读回后语句码退化为
 * `ADDCHARA`，SP 关闭时会静默按普通加角色执行。
 *
 * ## 实测裁定：风险**不成立**（三条独立证据，全部由本文件的用例实测）
 *
 * 1. **落盘的是 code 名，不是折叠后的操作码**：语句记录头逐条写 `s.code.name`
 *    （ErbcWriter.kt:959-972，`out.u32(str(s.code.name))` 在 :968），读回按
 *    `FunctionCode.valueOf(name)` 还原（ErbcReader.kt:1042-1046），并在 :998-1000 逐字校验
 *    「类标签推定的 code」与「记录里的 code 名」是否一致。⇒ 名字本身就是磁盘形态。
 * 2. **VM 分派取的是语句池实例，不是 marker 的操作码**：marker 约定
 *    `op=语句操作码(29..197) | a=语句池下标 | b=0 | c=NONE`（契约 C3，ErbcFormat.kt:87，
 *    发射点 ErbProgramCompiler.kt:316-332），主循环按 `program.statement(a)` 取回语句对象
 *    再交给 sem（Vm.kt:609-619）⇒ SP 门控读到的一定是原 code。
 *    本测试实测：三条语句折叠后的 `op` **同为 101**，但往返回来的 `statement(a).code`
 *    仍是 `ADDCHARA` / `ADDSPCHARA` / `DELCHARA`，行为差分（报错文案 / 角色表读数）
 *    与源码直建逐字相同。
 * 3. **「只有操作码、没有语句池」的产物根本造不出来**：`includeStatements=false` 且语句池
 *    非空时编码期直接抛 [ErbcEncodeException]（ErbcWriter.kt:237-245）；默认恒带语句池
 *    （ErbcFormat.kt:271-272），erbcc 写盘走无参 `encode(program)`
 *    （tools/erbcc/.../compile/ErbcArtifacts.kt:93 → ErbcWriter.kt:129）
 *    ⇒ 折叠位没有任何可被单独观测的路径。
 *
 * ## 判据 2 的结论（variant 位是否足以区分三族）
 *
 * **不足以，而且 variant 压根不落盘**：[StatementAlias.variant] 只是别名表内的序号
 * （OpcodeTable.kt:615-630），`resolve()` 只回操作码（:947-948），marker 的 `b` 恒 0
 * （契约 C3，本测试逐条断言）⇒ 三族在指令流层面完全同码 101。
 * 真正的身份载体是语句池里的 `code` 名。故本路**不改 `.erbc` 二进制格式**（无需加字段）、
 * 不改 alias 表；已生成的 323.7 MB 全量产物不作废。
 *
 * ## 顺带收（i-erbcround 第二条）
 *
 * 函数层 `GETSPCHARA` / `GETCHARA` 与各 CSV 访问器的 SP 门控原先写死字面量，
 * 现与语句层共用 [spCharaEnabled] 判定源；文案按原版分成两条 id
 * （`Lang.cs:2266` 的 `SPCharaConfigIsOff` 与 `:3826` 的 `SPCharacterFeatureDisabled`，
 * 日文源文案相同但 zhs 表 `lang/emuera.zhs.xml:631` / `:632` 是两句不同译文）。
 * 末节断言的就是「同一个判定源 + 两条不同文案 + SP 开启下的 CFlag 读数」。
 *
 * 本文件不吃语料变量（全部读数用假体模板 NO 10 / 20 自给），
 * 故 `ERATW_CORPUS` / `ERATW_GAMEDIR` 缺席时用例数不变。
 */
/**
 * 只喂 configOverrides 的假体宿主（键 = emuera.config 落盘形态）。
 * 组长收口时从测试类内提到文件级：Kotlin 的嵌套类看不到外层类的私有成员，
 * 原写法在 `private class Env` 里引用 `hostWith` 直接 `Unresolved reference`。
 */
private fun hostWith(overrides: Map<String, String>): BuiltinHost = object : BuiltinHost {
    override val configOverrides: Map<String, String> = overrides
}

class ErbcRoundTripIdentityTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private class Env(config: Map<String, String> = emptyMap()) {
        val store = VariableStore()
        val console = FakeConsoleSink()
        val eval = BuiltinEvalContext(TestEvalContext(store), store, hostWith(config))
        val ctx = ExecContext(variables = store, eval = eval, console = console)
        private val executor = StatementExecutor()

        fun run(stmt: Statement) = executor.execute(stmt, ctx)

        fun int(name: String, vararg args: Long): Long =
            (eval.callFunction(name, args.map { Value.IntV(it) }) as Value.IntV).v

        fun str(name: String, vararg args: Value): String =
            (eval.callFunction(name, args.toList()) as Value.StrV).v

        /** 角色表读数快照：`(下标, NO)` 序列 —— 差分比的就是这个。 */
        fun charaSnapshot(): List<Pair<Long, Long>> =
            (0 until store.characters.count.toLong()).map { it to store.readInt("NO", longArrayOf(it)) }
    }

    /** NO 10 / NO 20 两个模板（CSV 号与 NO 同值，查表口径见 StatementExecutor.kt:563-572）。 */
    private fun templates(env: Env): Env {
        env.store.registerTemplate(CharacterTemplate(no = 10, name = "甲", csvNo = 10))
        env.store.registerTemplate(CharacterTemplate(no = 20, name = "乙", csvNo = 20))
        return env
    }

    /** INT_ANY 实参形态（spec: FunctionIdentifier.cs:1352 把三条都钉成 GetArgumentBuilder(INT_ANY)）。 */
    private fun charaArg(vararg nos: Long) =
        ExpressionArrayArgument(nos.map { IntLiteral(it) as com.eratw.port.core.expr.Expr? })

    private fun instr(code: FunctionCode, arg: ExpressionArrayArgument): Statement =
        InstructionStmt(code, arg, 1, code.name)

    /** 语句 marker 的操作码区间（契约 C3：内部指令 0..28、语句 29..197，见 Vm.kt:867）。 */
    private fun isStatementMarker(op: Int): Boolean = op in 29..197

    /** 源码直建：ERB 文本 → 语句 → 编译成 Program。 */
    private fun programFromSource(lines: List<String>): Program {
        val parser = LineParser()
        val stmts = lines.mapIndexed { i, text -> parser.parseCodeLine(text, i + 1) }
        val compiler = ErbProgramCompiler()
        compiler.compileFunction(FuncSig("MAIN", 0), stmts)
        return compiler.build("MAIN")
    }

    /** Program 里所有语句 marker：`(pc, op, 语句码)`。 */
    private fun markers(program: Program): List<Triple<Int, Int, FunctionCode>> =
        (0 until program.instructionCount)
            .filter { isStatementMarker(program.op(it)) }
            .map { Triple(it, program.op(it), program.statement(program.a(it)).code) }

    /**
     * 取 [code] 那条语句的 marker。
     * 编译器会给掉尾函数补一条合成的 `RESULT = 0`（ErbProgramCompiler.kt:228-232），
     * 它同样是语句 marker ⇒ 一律按语句码精确取，不用 `single()`。
     */
    private fun markerOf(program: Program, code: FunctionCode): Triple<Int, Int, FunctionCode> =
        markers(program).first { it.third == code }

    private fun stmtOf(program: Program, code: FunctionCode): Statement =
        program.statement(program.a(markerOf(program, code).first))

    /** 三族语句码（共用操作码 101 的那一族）。 */
    private val charaCodes = setOf(FunctionCode.ADDCHARA, FunctionCode.ADDSPCHARA, FunctionCode.DELCHARA)

    private fun charaMarkers(program: Program): List<Triple<Int, Int, FunctionCode>> =
        markers(program).filter { it.third in charaCodes }

    private fun charaStatements(program: Program): List<Statement> =
        charaMarkers(program).map { program.statement(program.a(it.first)) }

    // ------------------------------------------------------------------
    // 1) 折叠确实存在，但身份由语句池承载：往返后 code 名一字不变
    // ------------------------------------------------------------------

    @Test
    fun `三族共用同一操作码 但语句池里的 code 名往返后不变`() {
        val before = programFromSource(listOf("ADDCHARA 10", "ADDSPCHARA 20", "DELCHARA 0"))
        val after = ErbcReader.decode(ErbcWriter.encode(before))

        // ① 折叠证据：三条 marker 的 op 完全相同（= Opcode.ADDCHARA = 101）
        val opsBefore = charaMarkers(before)
        val opsAfter = charaMarkers(after)
        assertEquals(3, opsBefore.size, "三条语句应各发一条 marker")
        assertEquals(opsBefore.map { it.second }, opsAfter.map { it.second }, "操作码序列不该在往返中变化")
        assertTrue(opsBefore.all { it.second == Opcode.ADDCHARA }, "三族同码：${opsBefore.map { it.second }}")
        assertEquals(101, Opcode.ADDCHARA)
        // 折叠位（variant）在指令里无处可放：契约 C3 钉死 marker 的 `b` 恒 0
        // （ErbProgramCompiler.kt:331 `out.emit(op, out.statement(s3), 0, SlotAddr.NONE, ...)`）
        assertTrue(opsBefore.all { before.b(it.first) == 0 }, "marker 的 b 若不为 0 就说明 variant 被塞进了指令流")
        assertTrue(opsAfter.all { after.b(it.first) == 0 }, "往返后同样没有位置放 variant")

        // ② 身份证据：语句池条数与逐条 code 名完全一致，ADDSPCHARA / DELCHARA 没有退化
        assertEquals(before.statementCount, after.statementCount)
        assertEquals(
            (0 until before.statementCount).map { before.statement(it).code },
            (0 until after.statementCount).map { after.statement(it).code },
            "语句码序列在 `.erbc` 往返后必须逐条相等",
        )
        assertEquals(
            listOf(FunctionCode.ADDCHARA, FunctionCode.ADDSPCHARA, FunctionCode.DELCHARA),
            opsAfter.map { it.third },
            "风险描述里的退化形态（ADDSPCHARA→ADDCHARA）实测并未发生",
        )
        // ③ 往返一次再编码，与首份字节逐字相同（读写两两自证的第三方）
        assertBytesEqual("再编码", ErbcWriter.encode(before), ErbcWriter.encode(after))
    }

    @Test
    fun `映射装载路径与解码器同源 语句码同样不退化`() {
        val before = programFromSource(listOf("ADDSPCHARA 20", "DELCHARA 0"))
        val bytes = ErbcWriter.encode(before)
        val dir = Files.createTempDirectory("erbc-identity")
        val file = dir.resolve("program.erbc")
        try {
            Files.write(file, bytes)
            val viaMapped = ProgramLoader.loadMapped(file) // MappedErbc.toProgram → ProgramLoader.load
            val viaFile = ProgramLoader.loadFile(file)
            assertEquals(
                listOf(FunctionCode.ADDSPCHARA, FunctionCode.DELCHARA),
                charaMarkers(viaMapped).map { it.third },
                "mmap 路径（仍走 ErbcReader.decode 这同一份代码）必须与解码器同口径",
            )
            assertEquals(charaMarkers(before).map { it.third }, charaMarkers(viaFile).map { it.third })
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `没有语句池的产物在编码期就被拒绝 折叠位无从观测`() {
        val program = programFromSource(listOf("ADDSPCHARA 20"))
        val e = assertFailsWith<ErbcEncodeException> {
            ErbcWriter.encode(program, ErbcEncodeOptions(includeStatements = false))
        }
        assertTrue(e.message!!.contains("语句池"), e.message ?: "缺语句池的报错文案")
        // 默认选项恒带语句池（ErbcFormat.kt:271-272）⇒ erbcc 的无参 encode 必带池
        assertTrue(ErbcEncodeOptions().includeStatements)
    }

    // ------------------------------------------------------------------
    // 2) 行为差分：源码直建 vs `.erbc` 往返，SP 关闭 / 开启两条配置各一组
    // ------------------------------------------------------------------

    @Test
    fun `SP 关闭下两条路径报同一句 SP 错误文案`() {
        for (line in listOf("ADDSPCHARA 20", "ADDSPCHARA 999")) {
            val before = programFromSource(listOf(line))
            val after = ErbcReader.decode(ErbcWriter.encode(before))
            val a = templates(Env())
            val b = templates(Env())
            val ea = assertFailsWith<CodeEE> { a.run(stmtOf(before, FunctionCode.ADDSPCHARA)) }
            val eb = assertFailsWith<CodeEE> { b.run(stmtOf(after, FunctionCode.ADDSPCHARA)) }
            // 文案 = Lang.Error.SPCharaConfigIsOff（Lang.cs:2266 → emuera.zhs.xml:631）
            assertEquals(SP_CHARA_CONFIG_IS_OFF, ea.message, line)
            assertEquals(ea.message, eb.message, "往返后的 SP 错误文案必须与源码直建逐字相同：$line")
            // :1360-1363 在 :1368 的求值循环之前 ⇒ NO 999 也报 SP 错而非角色错，且不追加
            assertEquals(0, a.store.characters.count, "SP 错必须在追加之前抛出：$line")
            assertEquals(a.store.characters.count, b.store.characters.count, line)
            assertEquals(a.charaSnapshot(), b.charaSnapshot(), line)
        }
    }

    @Test
    fun `SP 开启下两条路径产生同一角色表读数`() {
        val config = mapOf("ALLOW SP CHARACTERS" to "YES") // ConfigData.cs:106 的 EngText 形
        val before = programFromSource(listOf("ADDCHARA 10", "ADDSPCHARA 20"))
        val after = ErbcReader.decode(ErbcWriter.encode(before))

        val a = templates(Env(config))
        val b = templates(Env(config))
        for (stmt in charaStatements(before)) a.run(stmt)
        for (stmt in charaStatements(after)) b.run(stmt)

        // SP 开启时 ADDCHARA 与 ADDSPCHARA 同路（FunctionIdentifier.cs:1376-1379；
        // GetCharacterTemplate_UseSp 的 sp 形参未被读取 = ConstantData.cs:1230-1238）
        assertEquals(listOf(0L to 10L, 1L to 20L), a.charaSnapshot())
        assertEquals(a.charaSnapshot(), b.charaSnapshot(), "往返后的角色表读数必须逐位相同")
    }

    @Test
    fun `DELCHARA 往返后仍按删除执行 不会被折叠成加角色`() {
        // 折叠形态（OpcodeTable.kt:824-826）让 DELCHARA 与 ADDCHARA 共用操作码 101；
        // 若真按 op 分派，往返后的 `DELCHARA 0` 会变成「再追加一个 NO=10 的角色」。
        val before = programFromSource(listOf("ADDCHARA 10", "DELCHARA 0"))
        val after = ErbcReader.decode(ErbcWriter.encode(before))
        for ((program, label) in listOf(before to "源码直建", after to "erbc 往返")) {
            val env = templates(Env())
            for (stmt in charaStatements(program)) env.run(stmt)
            assertEquals(0, env.store.characters.count, "$label：DELCHARA 0 应把下标 0 删空")
            assertEquals(emptyList(), env.charaSnapshot(), "$label：角色表读数")
        }
    }

    // ------------------------------------------------------------------
    // 3) 顺带收：函数层 SP 门控与语句层同一判定源，两条 Lang id 各归各文案
    // ------------------------------------------------------------------

    @Test
    fun `SP 开关是函数层与语句层的同一判定源`() {
        // 判定源只有 spCharaEnabled 一个：缺省关、语料形 NO 关、YES 开
        assertTrue(!spCharaEnabled(hostWith(emptyMap())))
        assertTrue(!spCharaEnabled(hostWith(mapOf("ALLOW SP CHARACTERS" to "NO"))))
        assertTrue(spCharaEnabled(hostWith(mapOf("ALLOW SP CHARACTERS" to "YES"))))
        // 两条 Lang id 的 zhs 译文不同 ⇒ 不许合并成一个串
        // （Lang.cs:2266 vs :3826；emuera.zhs.xml:631 vs :632）
        assertNotEquals(SP_CHARA_CONFIG_IS_OFF, SP_CHARACTER_FEATURE_DISABLED)
    }

    @Test
    fun `SP 关闭下 GETSPCHARA 报函数层文案 而 GETCHARA 的第二实参被忽略`() {
        val env = templates(Env())
        env.run(instr(FunctionCode.ADDCHARA, charaArg(10)))

        // GETSPCHARA：SP 关闭即抛（FunctionMethodCreator.cs:3166-3169），实参是未定义 NO 也一样
        val e = assertFailsWith<CodeEE> { env.int("GETSPCHARA", 999) }
        assertEquals(SP_CHARACTER_FEATURE_DISABLED, e.message)

        // GETCHARA：SP 关闭走 `return GetChara(no)`（:3128-3131），第二实参连求值都不发生
        // ⇒ 原版形态下 `GETCHARA 10, 1` 不报错，读数与一元形完全相同
        assertEquals(0L, env.int("GETCHARA", 10))
        assertEquals(0L, env.int("GETCHARA", 10, 1))
        assertEquals(-1L, env.int("GETCHARA", 777, 1), "查不到仍按 GetChara 的 -1 口径（VariableEvaluator.cs:1551-1561）")

        // CSV 族：门控 = `!CompatiSPChara && isSp != 0` 才抛
        // （FunctionMethodCreator.cs:3222-3225 / :3255-3258 / :3313-3316 / :3412-3415）
        assertEquals("甲", env.str("CSVNAME", Value.IntV(10)), "一元形不受门控影响")
        assertFailsWith<CodeEE> { env.eval.callFunction("CSVNAME", listOf(Value.IntV(10), Value.IntV(1))) }
        assertEquals(1L, env.int("EXISTCSV", 10))
        assertFailsWith<CodeEE> { env.eval.callFunction("EXISTCSV", listOf(Value.IntV(10), Value.IntV(1))) }
    }

    @Test
    fun `SP 开启下函数层按 CFlag 首命中 与原版同读数`() {
        val env = templates(Env(mapOf("ALLOW SP CHARACTERS" to "YES")))
        env.run(instr(FunctionCode.ADDCHARA, charaArg(10)))
        env.run(instr(FunctionCode.ADDCHARA, charaArg(10))) // 第二个同为 NO=10 的角色
        // SP 身份 = `CFlag[0] != 0`（VariableEvaluator.cs:1563-1573）；把下标 1 标成 SP 角色
        env.store.writeInt("CFLAG", longArrayOf(1, 0), 1L)

        // GetChara_UseSp(10, false)：下标 0 的 CFlag[0]==0 ⇒ 命中 0
        assertEquals(0L, env.int("GETCHARA", 10, 0))
        // GETSPCHARA(10) = GetChara_UseSp(10, true) ⇒ 命中被标成 SP 的下标 1
        assertEquals(1L, env.int("GETSPCHARA", 10))
        // isSp 非 0：先按非 SP 查（:3139-3140），命中即回 0，不触发 :3144 的 SP 回落
        assertEquals(0L, env.int("GETCHARA", 10, 1))
        // 把下标 0 也标成 SP ⇒ 非 SP 查返回 -1，只有 isSp 非 0 时才回落到 SP 命中（:3141-3146）
        env.store.writeInt("CFLAG", longArrayOf(0, 0), 1L)
        assertEquals(-1L, env.int("GETCHARA", 10, 0))
        assertEquals(0L, env.int("GETCHARA", 10, 1), "两级回落：非 SP 未命中后按 SP 查")

        // SP 开启后 CSV 族的 isSp 不再报错，且取值与非 SP 形参一致
        // （GetCharacterTemplate_UseSp 的 sp 形参未被读取 = ConstantData.cs:1230-1238）
        assertEquals(
            env.str("CSVNAME", Value.IntV(10)),
            env.str("CSVNAME", Value.IntV(10), Value.IntV(1)),
            "SP 开启时 isSp 不参与模板筛选 ⇒ 两形读数必须相同",
        )
        assertEquals(1L, env.int("EXISTCSV", 10, 1))
    }

    private fun assertBytesEqual(name: String, expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "$name：字节数")
        for (i in expected.indices) assertEquals(expected[i], actual[i], "$name：偏移 $i")
    }
}
