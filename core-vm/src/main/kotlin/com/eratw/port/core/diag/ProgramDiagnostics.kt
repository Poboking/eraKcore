package com.eratw.port.core.diag

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.StmtInternAudit
import com.eratw.port.core.vars.VariableStore
import java.lang.reflect.Modifier
import java.nio.file.Path

/**
 * Program 级诊断探针（ARCH1-P2a：宿主诊断下沉 core）。
 *
 * ## 来源与接线
 *
 * 原文在 app 宿主 `GameHost.kt:1111-1257`（基线 main@ac0c7726）——core 的指令字布局、
 * 语句 marker 操作码区间、语句池接缝都从那里漏进了 app 层。本对象把两台探针原样搬进
 * core，宿主只留「开关文件判定 + logcat + 落盘」三件事（接线点：`GameHost.kt:754` 的
 * 死循环现场、`:847` 的语句池对账；组长接线）。
 *
 * ## 输出契约（钉死）
 *
 * 两个函数产出的每一行**与 GameHost 旧实现逐字节一致**——真机 logcat 与
 * `pool-audit.txt` / `deadloop-site.txt` 的判读靠这些格式（2026-10-22 死循环事件的
 * 基准读数都是旧格式），改一个空格历史读数就对不上号。唯一例外见
 * [auditStatementPoolAt] 的行窗口参数：窗口值从硬编码变成入参后，行内的
 * `pool-audit #<i>`、`实例3(先物化#<k>)` 随入参走，对同一组入参仍逐字节一致。
 *
 * ## 纯度
 *
 * object 无任何可变状态：每次调用现算现回，入参出参之外不落任何进程级东西。
 * logcat / 文件落盘归宿主（core 禁 Android API）；本对象只依赖 core 公共类型
 * （[Program] / [ProgramLoader] / [Opcode] / 语句与表达式树 / [VariableStore]）。
 */
object ProgramDiagnostics {

    /**
     * 装载后语句池直读对账（执行前）；原 `GameHost.auditStatementPoolAt`（:1111）。
     *
     * 对 `[lo, hi]` 窗口内的每个语句池下标，把**执行用的 [program]** 与**新装载的第二实例**
     * 逐条直读并排打印：双实例一致且形态同错 ⇒ 装载解码确定性错（查解码层）；
     * 第二实例形态正常 ⇒ 执行用的实例在运行期被污染（查执行期写坏）。
     *
     * **第三实例**：再新开一次装载，但**先物化 [thirdInstanceIndex]**（让该语句自己的节点
     * 先当折叠代表）再看它的形态。判读（2026-10-22 真机死循环事件的原始口径，随代码下沉）：
     * 实例3 下标 k 的 dest 与实例2 不同 ⇒ 同一节点在不同物化序下形态不同，即
     * `ArgInterner` 把后物化的节点误折向先物化的代表（sameStructure/shapeOf 判等不完整）；
     * 实例3 的 dest 就是错的（原始解码即错）⇒ 节点表引用错位（解码层），interner 无辜。
     *
     * **行窗口参数化**：原实现硬编码 `140944..140952`（第三实例取 140948）——那是
     * CMP-15 排查 `RM_タイトル画像用意` 的 FOR 区语句下标，属排查遗物。现在窗口与
     * 第三实例下标全由调用方给；GameHost 现状口径 = 窗口 140944..140952、第三实例 140948
     * （接线时由调用点传回，输出与旧实现逐字节一致）。
     *
     * 末尾附带 **shape 探针**（[isAtomTypeCompat]）：VarRef 的字段在当前 JVM 里能反射到吗、
     * 会分成什么类 —— 桌面/ART 分裂的直接证据（accessible=false 的字段在装载期判等里会被
     * 丢弃，丢字段 = 判等不完整 = 跨形态误折叠）。
     *
     * 若 [StmtInternAudit.enabled] 已开（须在装载前设置），折叠审计读数随本对账一起返回
     * 并清空（RED = 关键摘要不同的可疑折叠，死循环同型病）。
     *
     * @param program 执行用的实例（宿主已装载）
     * @param artifact 产物文件路径；本函数会**再** `ProgramLoader.loadMapped` 两次（第二/第三
     *   实例），每次都是一遍完整解码，并各留一份 `Program`（惰性池的通道随之留在进程里，
     *   core 没有 Program 级关闭入口，与旧实现同口径）——调用方必须用开关文件挡住常规启动
     *   （GameHost 的 `pool-audit.on` 一次性开关）。
     * @param lo 对账窗口下界（含）；越界下标按直读失败（null）打印，不抛
     * @param hi 对账窗口上界（含）
     * @param thirdInstanceIndex 第三实例**先物化**的语句池下标
     * @return 逐行输出（宿主逐行进 logcat 后整份落盘；本函数不打印、不写文件）
     */
    fun auditStatementPoolAt(
        program: Program,
        artifact: Path,
        lo: Int,
        hi: Int,
        thirdInstanceIndex: Int,
    ): List<String> {
        val lines = ArrayList<String>()
        // 第二实例：新开一次 loadMapped（惰性窗口，只物化窗口内语句）
        val second = runCatching { ProgramLoader.loadMapped(artifact) }.getOrNull()
        lines += "pool-audit stmtCount=${program.statementCount} second=$second"
        for (i in lo..hi) {
            val s1 = runCatching { program.statement(i) }.getOrNull()
            val s2 = second?.let { runCatching { it.statement(i) }.getOrNull() }
            lines += "pool-audit #$i"
            lines += "  实例1(执行用) @${System.identityHashCode(s1)} ${brief(s1)}"
            lines += "  实例2(新装载) @${System.identityHashCode(s2)} ${brief(s2)}"
            if (s1?.toString() != s2?.toString()) lines += "  ⚠ 双实例不一致！"
        }
        // 第三实例：先物化 thirdInstanceIndex（让该语句自己的节点先当折叠代表），再看它的 dest。
        val third = runCatching { ProgramLoader.loadMapped(artifact) }.getOrNull()
        if (third != null) {
            val s3 = runCatching { third.statement(thirdInstanceIndex) }.getOrNull()
            lines += "pool-audit 实例3(先物化#$thirdInstanceIndex) @${System.identityHashCode(s3)} ${brief(s3)}"
        }
        // shapeOf 复刻：VarRef 的字段在当前 JVM 能反射到吗、分类成什么 —— 桌面/ART 分裂的
        // 直接证据。accessible=false 的字段在 ArgInterner.shapeOf 被静默 continue 跳过，
        // 丢字段 = 判等不完整 = 跨形态误折叠（VarRef(I) ↔ VarRef(_i)）。
        runCatching {
            var cl: Class<*>? = VarRef::class.java
            while (cl != null && cl != Any::class.java) {
                for (f in cl.declaredFields) {
                    // 运期缓存不属于模型字段（见 `VarRef` 的 `@Transient` 段），与
                    // `ArgInterner.buildShape` **同一口径**跳过：本探针的意义是「模型字段
                    // 在桌面/ART 上都能反射到吗」，缓存字段混进来只会让真机 pool-audit
                    // 的输出契约漂移，且会诱导读者以为它们是判等输入。
                    if (Modifier.isTransient(f.modifiers)) continue
                    val acc = runCatching { f.isAccessible = true }.isSuccess
                    val atom = isAtomTypeCompat(f.type)
                    lines += "shape ${cl.simpleName}.${f.name}: type=${f.type.simpleName} " +
                        "accessible=$acc atom=$atom static=${Modifier.isStatic(f.modifiers)}"
                }
                cl = cl.superclass
            }
        }.onFailure { lines += "shape 探针失败: $it" }
        // 折叠审计读数（2026-10-22 复盘方案 #2）：开关开着时，装载期发生的可疑折叠
        // （关键摘要不同 = 判等不完整）随对账一起返回；关着时不动读数。
        if (StmtInternAudit.enabled) lines += StmtInternAudit.drain()
        return lines
    }

    /**
     * 字段分类的探针复刻：原 `GameHost.isAtomTypeCompat`（:1160），逐字保留。
     *
     * **为什么不直接调 `ArgInterner.isAtomFieldType`**（虽然现在同模块可达）：core 的真判据
     * 已按 CMP-14 放宽为 `t.isArray`（`ArgInterner.kt:945-950`，2026-10-23 收口），会把
     * `VarRef.indexArray`（`Expr[]`）分成原子；而本探针的基准读数是 2026-10-22 真机
     * `pool-audit.txt`（旧复刻口径：只有 IntArray/LongArray/DoubleArray/BooleanArray/
     * Array of String 五种数组算原子，`Expr[]` 分进子结点）。换真判据会让那一行
     * `atom=false` 翻成 `true`，破坏「与旧实现逐字节一致」的输出契约 —— 判定式保持
     * 2026-10-22 当时的快照不动，分叉缘由记档于此。
     */
    internal fun isAtomTypeCompat(t: Class<*>): Boolean = t.isPrimitive ||
        t == String::class.java ||
        t.isEnum ||
        Number::class.java.isAssignableFrom(t) ||
        t == java.lang.Boolean::class.java || t == java.lang.Character::class.java ||
        t == IntArray::class.java || t == LongArray::class.java ||
        t == DoubleArray::class.java || t == BooleanArray::class.java ||
        t == Array<String>::class.java

    /** 语句的短形态摘要（真机 logcat 友好）：原 `GameHost.brief`（:1170）逐字下沉。 */
    internal fun brief(s: Statement?): String {
        if (s == null) return "(null)"
        val sb = StringBuilder("${s.javaClass.simpleName}(line=${s.line})")
        if (s is AssignStmt) {
            val sp = s.args as? SpSetArgument
            sb.append(" dest=").append(briefVar(s.dest))
                .append(" op=").append(s.op)
                .append(" spDest=").append(sp?.dest?.let { briefVar(it) })
                .append(" spTerm=").append(sp?.term?.let { briefVar(it) })
        }
        return sb.toString()
    }

    /** Expr 短摘要：VarRef 打 name[下标字面量]，IntLiteral 打值，其余打类名+截断。原 `GameHost.briefVar`（:1184）逐字下沉。 */
    internal fun briefVar(e: Expr?): String = when (e) {
        null -> "(null)"
        is VarRef ->
            e.name + "[" + e.indices.joinToString(",") { idx ->
                (idx as? IntLiteral)?.value?.toString() ?: "?"
            } + "]"
        is IntLiteral -> "Int(${e.value})"
        else -> e.javaClass.simpleName + ":" + e.toString().take(60)
    }

    /**
     * 死循环闸现场的语句级转储；原 `GameHost.logDeadLoopSite`（:1202）下沉，只读。
     *
     * 失败屏只带调用链（[StepLimitExceeded.message]），这里补「撞闸 pc 的指令字 + 语句 AST +
     * 表达式 AST + 字母变量快照」。2026-10-21 真机首跑撞闸（pc=717295，`RM_タイトル画像用意`
     * 的 `FOR I,0,35`）靠这类读数发现设备侧语句池内容疑似与桌面解码分歧——每次真机撞闸
     * 都自动留这份现场，不再靠事后反射猜。
     *
     * 逐条产出：
     * - 首行：撞闸 pc 的四个指令字（`program.op/a/b/c(pc)`）+ 行号 + 函数名 + 步数 + 调用链；
     * - 窗口行：撞闸 pc 前 7 后 3 内的**语句 marker** 逐条转储（真机语句池内容 + 是否发生
     *   「不同语句折叠成同一实例」的直接证据，2026-10-22 凌晨真机复跑加装）。marker 判定
     *   `op !in Opcode.INTERNAL_COUNT..Opcode.MAX_STATEMENT_OPCODE`：29..197 恰为 isa.yaml
     *   的 169 条真实 ERB 语句（specs/erbc-format.md:87 区间权威 = `OpcodeTable.kt:131`
     *   INTERNAL_COUNT + `:547-548` MAX_STATEMENT_OPCODE，契约 C3；`Vm.kt:763` 的语句分派
     *   是同一判据）——原实现的幻数 `op !in 29..197` 按此命名；`a` 还须落在语句池内
     *   （specs/erbc-format.md:306 装载期判界同口径）；
     * - 表达式行：撞闸 pc 是 `_EVAL_INT` 且 `a` 落在表达式池内时，打印池条目本体；
     * - 字母快照：撞闸瞬间 A–Z 的 `[0]` 值（23:44 那次全 0，与「计数器写丢失」假设对账）；
     *   读不到（未定义/越界/字符串变量）按 `?` 打印（旧实现 getOrDefault 哨兵口径）。
     *
     * @param t 现场 throwable；非 [StepLimitExceeded] 返回空表（旧实现的提前返回）
     * @param program 执行中的 Program（null = 会话未建好，返回空表，同旧实现）
     * @param store 变量池（null = 跳过字母快照行）；只做只读 readInt
     * @return 逐行输出（宿主逐行进 logcat 后整份落盘）
     * @throws IndexOutOfBoundsException 等转储途中 Program 状态畸形（如撞闸 pc 越界，取指
     *   即抛）时原样上抛 —— **调用方必须 runCatching 包裹**并在 onFailure 落
     *   「死循环现场转储失败」（旧实现把这层兜底放在函数体内，下沉后随调用点保留，
     *   见 GameHost :754 接线 patch）。
     */
    fun deadLoopSiteLines(t: Throwable, program: Program?, store: VariableStore?): List<String> {
        val step = t as? StepLimitExceeded ?: return emptyList()
        if (program == null) return emptyList()
        val lines = ArrayList<String>()
        val pc = step.pc
        val op = program.op(pc)
        lines += "死循环指令现场 pc=$pc line=${program.lineOf(pc)} fn=${step.funcName} " +
            "steps=${step.steps} words=[$op,${program.a(pc)},${program.b(pc)},${program.c(pc)}]" +
            " 调用链=${step.callChain ?: "?"}"
        // 循环窗口（撞闸 pc 前 7 后 3）逐条语句转储 + 身份哈希：真机语句池内容与
        // 是否发生「不同语句折叠成同一实例」的直接证据（2026-10-22 凌晨真机复跑加装）。
        val lo = (pc - 7).coerceAtLeast(0)
        val hi = (pc + 3).coerceAtMost(program.instructionCount - 1)
        for (p in lo..hi) {
            val o = program.op(p)
            val a = program.a(p)
            if (Opcode.isInternal(o) || o !in Opcode.INTERNAL_COUNT..Opcode.MAX_STATEMENT_OPCODE ||
                a !in 0 until program.statementCount
            ) {
                continue
            }
            val s = program.statement(a)
            val line = buildString {
                append("死循环窗口 p$p op$o a$a@").append(System.identityHashCode(s))
                append(" stmt=$s")
                if (s is AssignStmt) {
                    val sp = s.args as? SpSetArgument
                    append(" | dest=").append(s.dest)
                    append(" op=").append(s.op)
                    append(" spDest=").append(sp?.dest)
                    append(" spTerm=").append(sp?.term)
                    append(" addConst=").append(sp?.addConst)
                }
            }
            lines += line
        }
        if (op == Opcode._EVAL_INT && program.a(pc) in 0 until program.exprCount) {
            lines += "死循环表达式 expr=${program.expr(program.a(pc))}"
        }
        // 字母快照：撞闸瞬间 A–Z 的值（23:44 那次全 0；与「计数器写丢失」假设对账）。
        store?.let { st ->
            val letters = buildString {
                for (c in 'A'..'Z') {
                    val v = runCatching { st.readInt(c.toString(), longArrayOf(0L)) }.getOrDefault(Long.MIN_VALUE)
                    append(c).append(':')
                    if (v == Long.MIN_VALUE) append('?') else append(v)
                    append(';')
                }
            }
            lines += "死循环字母 $letters"
        }
        // 帧局部变量快照（2026-10-25 红 J 取证缺口）：撞闸抛点由 VM 取好随异常带来，
        // 宿主事后读不到（run() 的 finally 已回卷调用栈）。旧宿主/测试宿主不带 ⇒ 不打印，
        // 保持既有读数逐字节不变。
        if (step.frameSnapshot.isNotEmpty()) {
            lines += "死循环帧快照 ${step.frameSnapshot.size}"
            lines += step.frameSnapshot
        }
        return lines
    }
}
