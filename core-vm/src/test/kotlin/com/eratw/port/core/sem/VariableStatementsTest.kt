package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.BitArgument
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.stmt.SortOrder
import com.eratw.port.core.stmt.SpArraySortArgument
import com.eratw.port.core.stmt.SpCopyArrayArgument
import com.eratw.port.core.stmt.SpCVarSetArgument
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SpSortcharaArgument
import com.eratw.port.core.stmt.SpSwapCharaArgument
import com.eratw.port.core.stmt.SpSwapVarArgument
import com.eratw.port.core.stmt.SpTimesArgument
import com.eratw.port.core.stmt.SpVarSetArgument
import com.eratw.port.core.stmt.SpVarsizeArgument
import com.eratw.port.core.vars.CharacterTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 变量操作 / 位操作 / 角色库语句执行语义。
 */
class VariableStatementsTest {

    private fun runAssign(env: SemTestEnv, dest: com.eratw.port.core.expr.VarRef, v: Long) {
        env.run(
            AssignStmt(
                dest = dest,
                op = OperatorCode.ASSIGNMENT,
                args = SpSetArgument(iVar("DAY", 0), IntLiteral(v)),
                line = 1,
                raw = "",
            ),
        )
    }

    // ---------------- 位操作 ----------------

    @Test
    fun `SETBIT CLEARBIT INVERTBIT`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.SETBIT, BitArgument(iVar("FLAG", 0), listOf(IntLiteral(3)))))
        assertEquals(8L, env.store.readInt("FLAG", longArrayOf(0)))
        env.run(instr(FunctionCode.INVERTBIT, BitArgument(iVar("FLAG", 0), listOf(IntLiteral(3)))))
        assertEquals(0L, env.store.readInt("FLAG", longArrayOf(0)))
        env.run(instr(FunctionCode.SETBIT, BitArgument(iVar("FLAG", 0), listOf(IntLiteral(1), IntLiteral(2)))))
        assertEquals(6L, env.store.readInt("FLAG", longArrayOf(0)))
        env.run(instr(FunctionCode.CLEARBIT, BitArgument(iVar("FLAG", 0), listOf(IntLiteral(2)))))
        assertEquals(2L, env.store.readInt("FLAG", longArrayOf(0)))
    }

    @Test
    fun `SETBIT 位号超 63 报错`() {
        val env = SemTestEnv()
        val e = assertFailsWith<CodeEE> {
            env.run(instr(FunctionCode.SETBIT, BitArgument(iVar("FLAG", 0), listOf(IntLiteral(64)))))
        }
        assert(e.message!!.contains("超出范围"))
    }

    // ---------------- SWAP ----------------

    @Test
    fun `SWAP 交换整数变量`() {
        val env = SemTestEnv()
        runAssign(env, iVar("DAY", 0), 1)
        runAssign(env, iVar("FLAG", 0), 2)
        env.run(instr(FunctionCode.SWAP, SpSwapVarArgument(iVar("DAY", 0), iVar("FLAG", 0))))
        assertEquals(2L, env.store.readInt("DAY", longArrayOf(0)))
        assertEquals(1L, env.store.readInt("FLAG", longArrayOf(0)))
    }

    @Test
    fun `SWAP 交换字符串变量`() {
        val env = SemTestEnv()
        env.store.writeStr("RESULTS", longArrayOf(0), "a")
        env.store.writeStr("RESULTS", longArrayOf(1), "b")
        env.run(instr(FunctionCode.SWAP, SpSwapVarArgument(sVar("RESULTS", 0), sVar("RESULTS", 1))))
        assertEquals("b", env.store.readStr("RESULTS", longArrayOf(0)))
        assertEquals("a", env.store.readStr("RESULTS", longArrayOf(1)))
    }

    @Test
    fun `SWAP 类型不同报错`() {
        val env = SemTestEnv()
        val e = assertFailsWith<CodeEE> {
            env.run(instr(FunctionCode.SWAP, SpSwapVarArgument(iVar("DAY", 0), sVar("RESULTS", 0))))
        }
        assert(e.message!!.contains("类型不相同"))
    }

    // ---------------- VARSET / VARSIZE / TIMES ----------------

    @Test
    fun `VARSET 全列填充`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.VARSET, SpVarSetArgument(iVar("FLAG", 0), IntLiteral(7), null, null)))
        assertEquals(7L, env.store.readInt("FLAG", longArrayOf(5)))
        assertEquals(7L, env.store.readInt("FLAG", longArrayOf(9999)))
    }

    // ---------------- CVARSET ----------------

    /**
     * 第 2 参是**元素下标**，写到 `[角色, 元素]`；角色区间是半开 `[start, end)`
     * （FunctionIdentifier.cs:1684-1722、VariableEvaluator.cs:317-343）。
     */
    @Test
    fun `CVARSET 写每个角色的指定元素且区间右开`() {
        val env = envWithTemplate()
        addChara(env, 10, 20, 10)
        env.run(
            instr(
                FunctionCode.CVARSET,
                SpCVarSetArgument(
                    iVar("CFLAG"),
                    IntLiteral(5),
                    IntLiteral(7),
                    IntLiteral(0),
                    IntLiteral(2),
                ),
            ),
        )
        assertEquals(7L, env.store.readInt("CFLAG", longArrayOf(0, 5)))
        assertEquals(7L, env.store.readInt("CFLAG", longArrayOf(1, 5)))
        assertEquals(0L, env.store.readInt("CFLAG", longArrayOf(2, 5)), "end 右开，角色 2 不写")
    }

    /** 字符串键下标经 `ConstantData.KeywordToInteger` 转整数（VariableEvaluator.cs:331-336）。 */
    @Test
    fun `CVARSET 字符串键下标`() {
        val env = envWithTemplate()
        addChara(env, 10, 20)
        env.store.registry.csvTable("CFLAG").set(9, "宴会参加")
        env.run(
            instr(
                FunctionCode.CVARSET,
                SpCVarSetArgument(iVar("CFLAG"), StrLiteral("宴会参加"), IntLiteral(1), null, null),
            ),
        )
        assertEquals(1L, env.store.readInt("CFLAG", longArrayOf(0, 9)))
        assertEquals(1L, env.store.readInt("CFLAG", longArrayOf(1, 9)))
    }

    /** `start > end` 先交换（FunctionIdentifier.cs:1709-1714）。 */
    @Test
    fun `CVARSET start 大于 end 时交换`() {
        val env = envWithTemplate()
        addChara(env, 10, 20, 10)
        env.run(
            instr(
                FunctionCode.CVARSET,
                SpCVarSetArgument(
                    iVar("CFLAG"),
                    IntLiteral(3),
                    IntLiteral(4),
                    IntLiteral(2),
                    IntLiteral(0),
                ),
            ),
        )
        assertEquals(4L, env.store.readInt("CFLAG", longArrayOf(0, 3)))
        assertEquals(4L, env.store.readInt("CFLAG", longArrayOf(1, 3)))
        assertEquals(0L, env.store.readInt("CFLAG", longArrayOf(2, 3)))
    }

    /** 0 维角色变量的元素下标被忽略（VariableData.cs:425-429、:510-513）。 */
    @Test
    fun `CVARSET 0 维角色变量忽略元素下标`() {
        val env = envWithTemplate()
        addChara(env, 10, 20)
        env.run(instr(FunctionCode.CVARSET, SpCVarSetArgument(iVar("ISASSI"), IntLiteral(4), IntLiteral(1), null, null)))
        assertEquals(1L, env.store.readInt("ISASSI", longArrayOf(0)))
        assertEquals(1L, env.store.readInt("ISASSI", longArrayOf(1)))
    }

    /** 角色区间与元素下标的越界都抛 CodeEE（FunctionIdentifier.cs:1687-1703 + VariableEvaluator.cs:337-341）。 */
    @Test
    fun `CVARSET 角色区间与元素下标越界报错`() {
        val env = envWithTemplate()
        addChara(env, 10, 20, 10)
        val startErr = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.CVARSET,
                    SpCVarSetArgument(iVar("CFLAG"), IntLiteral(0), IntLiteral(1), IntLiteral(3), null),
                ),
            )
        }
        assertTrue(startErr.message!!.contains("第4参数"), startErr.message!!)

        val endErr = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.CVARSET,
                    SpCVarSetArgument(iVar("CFLAG"), IntLiteral(0), IntLiteral(1), null, IntLiteral(4)),
                ),
            )
        }
        assertTrue(endErr.message!!.contains("第5参数"), endErr.message!!)

        val elemErr = assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.CVARSET,
                    SpCVarSetArgument(
                        iVar("CFLAG"),
                        IntLiteral(env.store.getLength("CFLAG").toLong()),
                        IntLiteral(1),
                        null,
                        null,
                    ),
                ),
            )
        }
        assertTrue(elemErr.message!!.contains("超出数组范围"), elemErr.message!!)
    }

    @Test
    fun `VARSIZE 写 RESULT`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.VARSIZE, SpVarsizeArgument("DAY")))
        assertEquals(1000L, env.store.readInt("RESULT", longArrayOf(0)))
    }

    @Test
    fun `TIMES 浮点乘后截断`() {
        val env = SemTestEnv()
        runAssign(env, iVar("DAY", 3), 7)
        env.run(instr(FunctionCode.TIMES, SpTimesArgument(iVar("DAY", 3), 0.5)))
        assertEquals(3L, env.store.readInt("DAY", longArrayOf(3)))
    }

    /**
     * TIMES 默认路径 = decimal 精确乘（Config.TimesNotRigorousCalculation 出厂 false，
     * spec: ConfigData.cs:111；本游戏 emuera.config「IMITATE BEHAVIOR FOR TIMES:NO」同值）。
     * 首差钉在 |IntValue| > 2^53：`(double)` 转换先失真（53 位尾数），decimal 路径不丢位。
     * spec: FunctionIdentifier.cs:1326-1338（decimal 分支）vs 旧实现的 ：1322-1325 double 分支。
     */
    @Test
    fun `TIMES 默认走 decimal 精确乘 大值不失真`() {
        val env = SemTestEnv()
        runAssign(env, iVar("DAY", 3), 9007199254740993L) // 2^53 + 1
        env.run(instr(FunctionCode.TIMES, SpTimesArgument(iVar("DAY", 3), 1.0)))
        // 旧实现（double 路径）给 9007199254740992（(double)IntValue 本身先舍掉 1）
        assertEquals(9007199254740993L, env.store.readInt("DAY", longArrayOf(3)))
    }

    /** eramaker 模拟路径（:1322-1325）：`(double)IntValue * DoubleValue` 后截断，大值丢位按原样。 */
    @Test
    fun `TIMES eramaker 模拟路径 开关打开走 double`() {
        val env = SemTestEnv()
        env.ctx = com.eratw.port.core.sem.ExecContext(
            variables = env.store,
            eval = TestEvalContext(env.store),
            console = env.console,
            input = env.input,
            config = SemConfig(timesNotRigorousCalculation = true),
        )
        runAssign(env, iVar("DAY", 3), 9007199254740993L)
        env.run(instr(FunctionCode.TIMES, SpTimesArgument(iVar("DAY", 3), 1.0)))
        assertEquals(9007199254740992L, env.store.readInt("DAY", longArrayOf(3)))
    }

    // ---------------- 角色库 ----------------

    private fun envWithTemplate(): SemTestEnv {
        val env = SemTestEnv()
        env.store.registerTemplate(CharacterTemplate(no = 10, name = "甲", csvNo = 10))
        env.store.registerTemplate(CharacterTemplate(no = 20, name = "乙", csvNo = 20))
        return env
    }

    private fun addChara(env: SemTestEnv, vararg nos: Long) =
        env.run(instr(FunctionCode.ADDCHARA, ExpressionArrayArgument(nos.map { IntLiteral(it) })))

    @Test
    fun `ADDCHARA 按模板追加`() {
        val env = envWithTemplate()
        addChara(env, 10, 20)
        assertEquals(2, env.store.characters.count)
        assertEquals(10L, env.store.readInt("NO", longArrayOf(0)))
        assertEquals(20L, env.store.readInt("NO", longArrayOf(1)))
    }

    @Test
    fun `ADDCHARA 未定义 NO 报错`() {
        val env = SemTestEnv()
        assertFailsWith<CodeEE> { addChara(env, 99) }
    }

    @Test
    fun `DELCHARA 删除后左移`() {
        val env = envWithTemplate()
        addChara(env, 10, 20, 10)
        env.run(instr(FunctionCode.DELCHARA, ExpressionArrayArgument(listOf(IntLiteral(1)))))
        assertEquals(2, env.store.characters.count)
        assertEquals(10L, env.store.readInt("NO", longArrayOf(1)))
    }

    @Test
    fun `ADDVOIDCHARA 追加伪角色`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.ADDVOIDCHARA))
        assertEquals(1, env.store.characters.count)
    }

    @Test
    fun `SWAPCHARA 交换位置`() {
        val env = envWithTemplate()
        addChara(env, 10, 20)
        env.run(instr(FunctionCode.SWAPCHARA, SpSwapCharaArgument(IntLiteral(0), IntLiteral(1))))
        assertEquals(20L, env.store.readInt("NO", longArrayOf(0)))
        assertEquals(10L, env.store.readInt("NO", longArrayOf(1)))
    }

    @Test
    fun `COPYCHARA 复制到目标位`() {
        val env = envWithTemplate()
        addChara(env, 10, 10)
        // C# CopyChara(x, y) = CharacterList[x].CopyTo(CharacterList[y])：x = 源，y = 目标
        // （VariableEvaluator.cs:1365；COPYCHARA 语句原样直传 X,Y，FunctionIdentifier.cs:1440-1443）。
        env.store.writeInt("BASE", longArrayOf(0, 0), 555)
        env.run(instr(FunctionCode.COPYCHARA, SpSwapCharaArgument(IntLiteral(0), IntLiteral(1))))
        assertEquals(555L, env.store.readInt("BASE", longArrayOf(1, 0)))
    }

    @Test
    fun `ADDCOPYCHARA 复制源角色追加到末位`() {
        val env = envWithTemplate()
        addChara(env, 10, 20)
        env.store.writeInt("BASE", longArrayOf(0, 0), 555)
        // spec: VariableEvaluator.cs:1368-1376 —— AddCopyChara(x) 的 x 是源下标：
        // CharacterList[x].CopyTo(末位)；参数只有源，目标恒为追加出的新位
        env.run(instr(FunctionCode.ADDCOPYCHARA, ExpressionArrayArgument(listOf(IntLiteral(0)))))
        assertEquals(3, env.store.characters.count)
        assertEquals(10L, env.store.readInt("NO", longArrayOf(2)))
        assertEquals(555L, env.store.readInt("BASE", longArrayOf(2, 0)))
        // 源角色数据不被反向覆盖（旧实现把源/目标写反，会拿空伪角色覆盖 0 号）
        assertEquals(555L, env.store.readInt("BASE", longArrayOf(0, 0)))
        assertEquals(20L, env.store.readInt("NO", longArrayOf(1)))
    }

    @Test
    fun `ADDCOPYCHARA 下标越界报错且不追加角色`() {
        val env = envWithTemplate()
        addChara(env, 10, 20)
        // spec: VariableEvaluator.cs:1370-1373 —— 判界在 AddPseudoCharacter（:1374）之前，
        // 源越界时抛 NotExistFromCopyChara 且列表不增长
        val e = assertFailsWith<CodeEE> {
            env.run(instr(FunctionCode.ADDCOPYCHARA, ExpressionArrayArgument(listOf(IntLiteral(2)))))
        }
        assertEquals("复制的原角色不存在", e.message)
        assertEquals(2, env.store.characters.count)
    }

    @Test
    fun `ADDCOPYCHARA 负数下标报错`() {
        val env = envWithTemplate()
        addChara(env, 10)
        val e = assertFailsWith<CodeEE> {
            env.run(instr(FunctionCode.ADDCOPYCHARA, ExpressionArrayArgument(listOf(IntLiteral(-1)))))
        }
        assertEquals("复制的原角色不存在", e.message)
        assertEquals(1, env.store.characters.count)
    }

    @Test
    fun `ADDCOPYCHARA 复制源角色到追加位`() {
        val env = envWithTemplate()
        addChara(env, 10, 20)
        env.store.writeInt("CFLAG", longArrayOf(0, 5), 42)
        // 原版：AddPseudoCharacter() 后 CharacterList[x].CopyTo(末尾)
        // （VariableEvaluator.cs:1368-1376）——源是参数，目标是新追加位。
        env.run(instr(FunctionCode.ADDCOPYCHARA, ExpressionArrayArgument(listOf(IntLiteral(0)))))
        assertEquals(3, env.store.characters.count)
        assertEquals(10L, env.store.readInt("NO", longArrayOf(2)), "追加位取源角色的 NO")
        assertEquals("甲", env.store.readStr("NAME", longArrayOf(2)), "姓名随 CopyTo 一起复制")
        assertEquals(42L, env.store.readInt("CFLAG", longArrayOf(2, 5)), "CFLAG 随 CopyTo 一起复制")
        assertEquals(10L, env.store.readInt("NO", longArrayOf(0)), "源位不得被新空角色覆盖（旧实现方向取反）")
        assertEquals("甲", env.store.readStr("NAME", longArrayOf(0)))
        assertEquals(42L, env.store.readInt("CFLAG", longArrayOf(0, 5)))
    }

    @Test
    fun `SORTCHARA 按整数键升序`() {
        val env = envWithTemplate()
        addChara(env, 10, 20, 10)
        // MASTER 缺省 0（原版即普通 Int1DVariableToken，VariableData.cs:3028，无 -1 特例），
        // SORTCHARA 语句恒 fixMaster:true（FunctionIdentifier.cs:1487）会把 0 号位钉住
        // （VariableEvaluator.cs:1414-1450）。显式 -1 表示无主人公、参与全量排序。
        env.store.writeInt("MASTER", longArrayOf(0), -1)
        env.store.writeInt("BASE", longArrayOf(0, 0), 30)
        env.store.writeInt("BASE", longArrayOf(1, 0), 10)
        env.store.writeInt("BASE", longArrayOf(2, 0), 20)
        env.run(
            instr(
                FunctionCode.SORTCHARA,
                SpSortcharaArgument(iVar("BASE", 0, 0), SortOrder.ASCENDING),
            ),
        )
        assertEquals(10L, env.store.readInt("BASE", longArrayOf(0, 0)))
        assertEquals(20L, env.store.readInt("BASE", longArrayOf(1, 0)))
        assertEquals(30L, env.store.readInt("BASE", longArrayOf(2, 0)))
    }

    @Test
    fun `SORTCHARA 降序`() {
        val env = envWithTemplate()
        addChara(env, 10, 20, 10)
        env.store.writeInt("BASE", longArrayOf(0, 0), 30)
        env.store.writeInt("BASE", longArrayOf(1, 0), 10)
        env.store.writeInt("BASE", longArrayOf(2, 0), 20)
        env.run(
            instr(
                FunctionCode.SORTCHARA,
                SpSortcharaArgument(iVar("BASE", 0, 0), SortOrder.DESENDING),
            ),
        )
        assertEquals(30L, env.store.readInt("BASE", longArrayOf(0, 0)))
        assertEquals(20L, env.store.readInt("BASE", longArrayOf(1, 0)))
        assertEquals(10L, env.store.readInt("BASE", longArrayOf(2, 0)))
    }

    @Test
    fun `DELALLCHARA 清空`() {
        val env = envWithTemplate()
        addChara(env, 10, 20)
        env.run(instr(FunctionCode.DELALLCHARA))
        assertEquals(0, env.store.characters.count)
    }

    // ---------------- SET 赋值与 ARRAYCOPY ----------------

    @Test
    fun `SET 整数赋值与复合赋值`() {
        val env = SemTestEnv()
        runAssign(env, iVar("DAY", 0), 10)
        assertEquals(10L, env.store.readInt("DAY", longArrayOf(0)))
        // 复合赋值在解析期折成整式 `Term = dest op rhs`（ArgumentParser.cs:1092-1096），
        // 执行层按原版 SET_Instruction 直接取 Term 值落库（FunctionIdentifier.cs:640-657）。
        env.run(
            AssignStmt(
                iVar("DAY", 0),
                OperatorCode.PLUS,
                SpSetArgument(
                    iVar("DAY", 0),
                    com.eratw.port.core.expr.Binary(
                        OperatorCode.PLUS, iVar("DAY", 0), IntLiteral(5), com.eratw.port.core.expr.OperandType.INT,
                    ),
                ),
                1,
                "",
            ),
        )
        assertEquals(15L, env.store.readInt("DAY", longArrayOf(0)))
        env.run(
            AssignStmt(
                iVar("DAY", 0),
                OperatorCode.MULT,
                SpSetArgument(
                    iVar("DAY", 0),
                    com.eratw.port.core.expr.Binary(
                        OperatorCode.MULT, iVar("DAY", 0), IntLiteral(2), com.eratw.port.core.expr.OperandType.INT,
                    ),
                ),
                1,
                "",
            ),
        )
        assertEquals(30L, env.store.readInt("DAY", longArrayOf(0)))
    }

    @Test
    fun `SET 字符串赋值`() {
        val env = SemTestEnv()
        env.run(
            AssignStmt(
                sVar("RESULTS", 0),
                OperatorCode.ASSIGNMENT,
                SpSetArgument(sVar("RESULTS", 0), com.eratw.port.core.expr.StrLiteral("值")),
                1,
                "",
            ),
        )
        assertEquals("值", env.store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun `ARRAYCOPY 第1参数复制到第2参数`() {
        val env = SemTestEnv()
        env.store.writeInt("DAY", longArrayOf(0), 5)
        env.store.writeInt("DAY", longArrayOf(1), 6)
        env.run(
            instr(
                FunctionCode.ARRAYCOPY,
                SpCopyArrayArgument(
                    com.eratw.port.core.expr.StrLiteral("DAY"),
                    com.eratw.port.core.expr.StrLiteral("FLAG"),
                ),
            ),
        )
        assertEquals(5L, env.store.readInt("FLAG", longArrayOf(0)))
        assertEquals(6L, env.store.readInt("FLAG", longArrayOf(1)))
    }

    // ---------------- ARRAYSORT / CVARSET ----------------

    private fun sortArray(env: SemTestEnv, start: Long?, count: Long?) {
        env.run(
            instr(
                FunctionCode.ARRAYSORT,
                SpArraySortArgument(
                    iVar("FLAG", 0),
                    SortOrder.ASCENDING,
                    start?.let { IntLiteral(it) },
                    count?.let { IntLiteral(it) },
                ),
            ),
        )
    }

    private fun cvarSet(env: SemTestEnv, index: Long, value: Long, start: Long?, end: Long?) {
        env.run(
            instr(
                FunctionCode.CVARSET,
                SpCVarSetArgument(
                    iVar("CFLAG", 0, 0),
                    IntLiteral(index),
                    IntLiteral(value),
                    start?.let { IntLiteral(it) },
                    end?.let { IntLiteral(it) },
                ),
            ),
        )
    }

    @Test
    fun `ARRAYSORT 缺省数量排到数组末尾`() {
        val env = SemTestEnv()
        val len = env.store.getLength("FLAG")
        val start = len - 3
        env.store.writeInt("FLAG", longArrayOf(start.toLong()), 3)
        env.store.writeInt("FLAG", longArrayOf((start + 1).toLong()), 1)
        env.store.writeInt("FLAG", longArrayOf((start + 2).toLong()), 2)
        // 第 4 参缺省 ⇒ count = -1 ⇒ SortArray 视作「到数组末尾」（Process.cs:1704-1707）。
        sortArray(env, start = start.toLong(), count = null)
        assertEquals(1L, env.store.readInt("FLAG", longArrayOf(start.toLong())))
        assertEquals(2L, env.store.readInt("FLAG", longArrayOf((start + 1).toLong())))
        assertEquals(3L, env.store.readInt("FLAG", longArrayOf((start + 2).toLong())))
    }

    @Test
    fun `ARRAYSORT 显式数量 0 是空操作`() {
        val env = SemTestEnv()
        env.store.writeInt("FLAG", longArrayOf(0), 3)
        env.store.writeInt("FLAG", longArrayOf(1), 1)
        // 显式 0 在原版于范围检查之前直接 break（Process.cs:1699-1702）：
        // 起始下标越界也不报错、不排序。
        sortArray(env, start = 999, count = 0)
        assertEquals(3L, env.store.readInt("FLAG", longArrayOf(0)))
        assertEquals(1L, env.store.readInt("FLAG", longArrayOf(1)))
    }

    @Test
    fun `ARRAYSORT 显式负数量报错且文案印起始下标`() {
        val env = SemTestEnv()
        val e = assertFailsWith<CodeEE> { sortArray(env, start = 2, count = -1) }
        assertTrue(e.message!!.contains("第4"), "实为 ${e.message}")
        // ADR-005：原版 ArgIsNegative 的 {2} 传的是 num8（start）而不是负的 count（Process.cs:1697）。
        assertTrue(e.message!!.contains("(2)"), "实为 ${e.message}")
    }

    @Test
    fun `CVARSET 结束角色是开区间`() {
        val env = envWithTemplate()
        addChara(env, 10, 20, 10)
        cvarSet(env, index = 2, value = 9, start = 1, end = 3)
        assertEquals(0L, env.store.readInt("CFLAG", longArrayOf(0, 2)), "start 之前不动")
        assertEquals(9L, env.store.readInt("CFLAG", longArrayOf(1, 2)))
        assertEquals(9L, env.store.readInt("CFLAG", longArrayOf(2, 2)), "end 是排他上界")
    }

    @Test
    fun `CVARSET 起止相同不写任何角色`() {
        val env = envWithTemplate()
        addChara(env, 10, 20, 10)
        cvarSet(env, index = 2, value = 9, start = 1, end = 1)
        assertEquals(0L, env.store.readInt("CFLAG", longArrayOf(1, 2)), "循环是 i in start until end")
    }

    @Test
    fun `CVARSET 起止颠倒先交换`() {
        val env = envWithTemplate()
        addChara(env, 10, 20, 10)
        cvarSet(env, index = 2, value = 9, start = 2, end = 1)
        assertEquals(0L, env.store.readInt("CFLAG", longArrayOf(0, 2)))
        assertEquals(9L, env.store.readInt("CFLAG", longArrayOf(1, 2)))
        assertEquals(0L, env.store.readInt("CFLAG", longArrayOf(2, 2)))
    }

    @Test
    fun `CVARSET 范围越界报错`() {
        val env = envWithTemplate()
        addChara(env, 10, 20, 10)
        val e1 = assertFailsWith<CodeEE> { cvarSet(env, index = 2, value = 9, start = 3, end = null) }
        assertTrue(e1.message!!.contains("第4"), "实为 ${e1.message}")
        val e2 = assertFailsWith<CodeEE> { cvarSet(env, index = 2, value = 9, start = 1, end = 4) }
        assertTrue(e2.message!!.contains("第5"), "实为 ${e2.message}")
        // ADR-005：end 越界的消息印的是 start（num2），不是 end（FunctionIdentifier.cs:1700）。
        assertTrue(e2.message!!.contains("(1)"), "实为 ${e2.message}")
    }

    // ---------------- 字符串工具 ----------------

    @Test
    fun `STRLEN 半角按 1 全角按 2`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.STRLEN, com.eratw.port.core.stmt.ExpressionArgument(com.eratw.port.core.expr.StrLiteral("ab"))))
        assertEquals(2L, env.store.readInt("RESULT", longArrayOf(0)))
        env.run(instr(FunctionCode.STRLEN, com.eratw.port.core.stmt.ExpressionArgument(com.eratw.port.core.expr.StrLiteral("あ"))))
        assertEquals(2L, env.store.readInt("RESULT", longArrayOf(0)))
    }

    @Test
    fun `STRLENU 按 Unicode 字符数`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.STRLENU, com.eratw.port.core.stmt.ExpressionArgument(com.eratw.port.core.expr.StrLiteral("あb"))))
        assertEquals(2L, env.store.readInt("RESULT", longArrayOf(0)))
    }

    @Test
    fun `SPLIT 分割写目标数组`() {
        val env = SemTestEnv()
        env.run(
            instr(
                FunctionCode.SPLIT,
                com.eratw.port.core.stmt.SpSplitArgument(
                    targetStr = com.eratw.port.core.expr.StrLiteral("a,b,c"),
                    split = com.eratw.port.core.expr.StrLiteral(","),
                    destVarName = "RESULTS",
                    num = iVar("RESULT", 0),
                ),
            ),
        )
        assertEquals(3L, env.store.readInt("RESULT", longArrayOf(0)))
        assertEquals("a", env.store.readStr("RESULTS", longArrayOf(0)))
        assertEquals("b", env.store.readStr("RESULTS", longArrayOf(1)))
        assertEquals("c", env.store.readStr("RESULTS", longArrayOf(2)))
    }

    @Test
    fun `ENCODETOUNI 写长度与码点`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.ENCODETOUNI, com.eratw.port.core.stmt.ExpressionArgument(com.eratw.port.core.expr.StrLiteral("あB"))))
        // RESULT[0]=字符数，码点从 RESULT[1] 起（spec: VariableEvaluator.cs:1990-1998）
        assertEquals(2L, env.store.readInt("RESULT", longArrayOf(0)))
        assertEquals(0x3042L, env.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(66L, env.store.readInt("RESULT", longArrayOf(2)))
    }
}
