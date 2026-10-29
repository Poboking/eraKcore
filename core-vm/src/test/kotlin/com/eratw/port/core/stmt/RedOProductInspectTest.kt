package com.eratw.port.core.stmt

import com.eratw.port.core.code.FunctionInfo
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.loader.ProgramLoader
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 「红 O」产物反汇编取证装置（只读产物，不改任何产品代码）。
 *
 * 问题面：白天房间页点「移动[400]」芯片 → 零输出静默退回菜单。待定性的两说：
 *  (a) `TRYCALLFORM COM_ABLE{SELECTCOM}` 被编译成运行期按名调用，名字算不出来 / 查不到；
 *  (b) `COM_ABLE400` 被正常调用但自身 `RETURN 0`（`COMF400 移動.ERB:309-310` 的
 *      `SIF !TFLAG:100 / RETURN 0`）。
 *
 * 装置读产物三层，供人工判读：
 *  A. **语句池清单**：`TRYCALLFORM` 类的语句条数、`raw`、`args` 具体类，
 *     以及 `raw` 含 `COM_ABLE` 的站点（含 `isConst` / `constStr` / `label` /
 *     `funcNameTerm` 运行时类与 `FormStringTerm.parts`）。
 *  B. **表达式池直取**：从指令流 `_EVAL_STR a=<exprIndex>` 拿到调用点的**名字表达式节点**，
 *     直接打印其 AST —— 不依赖语句记录是否可读，是最硬的证据。
 *  C. **指令流**：`USERCOM` 函数内 `_CALL_BY_NAME` 的 pc / 操作数位域（try/jump/callf/argc），
 *     以及 `COM_ABLE400` 函数体前若干条指令（含第一条 `RETURN 0` 守卫）。
 *
 * 只在 `program.erbc` 存在时跑（与 `StmtOffsetSidecarTest.findErbcDir` 同一套查找顺序）；
 * 缺产物时按 assumeTrue 跳过，不制造假绿。
 *
 * 堆口径：走 [ProgramLoader.loadMapped]（真机同款惰性分段解码）。整池 1,716,636 条
 * 在 3g test worker 内实测可容纳（本装置首次跑通即 30 s 量级）。
 */
class RedOProductInspectTest {

    /** 与 `StmtOffsetSidecarTest` / `LoadTimingProbe` 同一套产物查找顺序。 */
    private fun findErbcDir(): Path? {
        System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it).parent }
        return listOf(
            Path.of("build", "erbc-out", "erbc.v1"),
            Path.of("build", "erbc.v1"),
            Path.of("..", "tools", "erbcc", "build", "erbc-out", "erbc.v1"),
        ).firstOrNull { Files.isRegularFile(it.resolve("program.erbc")) }
    }

    @Test
    fun `红O 取证 —— L109 TRYCALLFORM 的语句 AST 与指令流`() {
        val dir = findErbcDir()
        Assumptions.assumeTrue(dir != null, "未找到 program.erbc（先跑 :tools:erbcc:compileCorpus）")
        val erbc = dir!!.resolve("program.erbc")

        val sb = StringBuilder(1 shl 16)
        fun out(s: String) {
            sb.append(s).append('\n')
            System.err.println(s)
        }

        out("=".repeat(78))
        out("[红O] 产物: ${erbc.toAbsolutePath()}")
        out("[红O] 大小: ${Files.size(erbc)} 字节")
        out("=".repeat(78))

        val program = ProgramLoader.loadMapped(erbc)
        val n = program.statementCount
        out("[红O] 语句池 $n 条；表达式池 ${program.exprCount}；指令数 ${program.instructionCount}；函数 ${program.functions.size}")

        // ==================================================================
        // A) 语句池清单：按 code 直方图（不看 raw），再看 raw 含 COM_ABLE 的站点
        // ==================================================================
        val byCode = LinkedHashMap<String, Int>()
        val tryFormStmts = ArrayList<Int>()
        val comAbleStmts = ArrayList<Int>()

        for (i in 0 until n) {
            val st = program.statement(i)
            val cn = st.code.name
            if (cn.contains("TRY", ignoreCase = true) || cn.contains("CALL", ignoreCase = true)) {
                byCode[cn] = (byCode[cn] ?: 0) + 1
            }
            if (st.code == FunctionCode.TRYCALLFORM) tryFormStmts += i
            if (st.raw.contains("COM_ABLE", ignoreCase = true)) comAbleStmts += i
            if ((i + 1) % 200000 == 0) System.err.println("[红O] …扫描 ${i + 1}/$n")
        }

        out("")
        out("---- A1) 语句池里 code 名含 TRY/CALL 的直方图（全池口径）----")
        byCode.toList().sortedByDescending { it.second }.forEach { (k, v) -> out("[红O]   ${k.padEnd(28)} $v") }

        out("")
        out("---- A2) code == TRYCALLFORM 的语句共 ${tryFormStmts.size} 条 ----")
        for (idx in tryFormStmts.take(12)) dumpStmt(program, idx, ::out)
        if (tryFormStmts.size > 12) out("[红O]   …（只列前 12 条，共 ${tryFormStmts.size} 条）")

        out("")
        out("---- A3) raw 含 `COM_ABLE` 的语句共 ${comAbleStmts.size} 条 ----")
        for (idx in comAbleStmts.take(20)) dumpStmt(program, idx, ::out)
        if (comAbleStmts.size > 20) out("[红O]   …（只列前 20 条，共 ${comAbleStmts.size} 条）")

        // ==================================================================
        // B) 指令流：USERCOM 的 _CALL_BY_NAME + 其前驱 _EVAL_STR 的名字表达式节点
        // ==================================================================
        out("")
        out("---- B1) 全段指令流里 line 100..120 的 _CALL_BY_NAME（含位域与所属函数）----")
        val callSites = dumpCallByNameAtLines(program, 100..120, ::out)

        out("")
        out("---- B2) 上述各站点前驱 `_EVAL_STR a=<exprIndex>` 解出的名字表达式 AST ----")
        for (site in callSites) {
            out("[红O]   pc=${site.pc} L${site.line} owner=${site.owner} exprIndex=${site.exprIndex}")
            val ast = runCatching { program.expr(site.exprIndex) }.getOrNull()
            out("[红O]     AST = ${describeExpr(ast)}")
        }

        // USERCOM 函数整体（含 pc 边界）
        out("")
        out("---- B3) USERCOM 函数体指令（pc 窗口 488890..488920）----")
        val usercom = program.functions.filter { it.name == "USERCOM" }
        out("[红O] name==USERCOM 的函数 ${usercom.size} 个；entryPc=${usercom.map { it.entryPc }}")
        usercom.forEach { f ->
            val r = pcRange(program, f)
            out("[红O]   USERCOM entryPc=${f.entryPc} 区间 ${r.start}..${r.last}（${r.count} 条指令），arity=${f.arity} slotCount=${f.slotCount}")
        }
        val lo = maxOf(0, 488890)
        val hi = minOf(program.instructionCount - 1, 488920)
        for (pc in lo..hi) {
            val op = program.op(pc)
            val nm = Opcode.nameOf(op)
            val extra = if (op == Opcode._EVAL_STR) "  -> expr[${program.a(pc)}]" else ""
            out("[红O]     pc=$pc L${program.lineOf(pc)} ${nm.padEnd(16)} a=${program.a(pc)} b=${program.b(pc)} c=${program.c(pc)}$extra")
        }

        // ==================================================================
        // C) COM_ABLE400：函数记录 + 函数体前段（Q3 的「返回 0 是否来自函数体」）
        // ==================================================================
        out("")
        out("---- C) COM_ABLE400 函数记录与前段指令 ----")
        val comAble = program.functions.filter { it.name.equals("COM_ABLE400", ignoreCase = true) }
        out("[红O] 产物内 name==COM_ABLE400 的函数 ${comAble.size} 个")
        comAble.forEach { f -> out("[红O]   entryPc=${f.entryPc} arity=${f.arity} slotCount=${f.slotCount} labels=${f.labelNames}") }
        for (f in comAble) {
            val r = pcRange(program, f)
            out("[红O]   entryPc=${f.entryPc}..${r.last} 共 ${r.count} 条指令：")
            for (pc in r.start..minOf(r.last, r.start + 35)) {
                out("[红O]     pc=$pc L${program.lineOf(pc)} ${Opcode.nameOf(program.op(pc))} a=${program.a(pc)} b=${program.b(pc)} c=${program.c(pc)}")
            }
        }

        // ==================================================================
        // D) 终局核对：全量 code 直方图（证明 A1 的「0 条」不是探针失效）
        //    + COM_ABLE400 那 9 条 RETURN 语句的原文（Q3 的语句级证据）
        // ==================================================================
        out("")
        out("---- D1) 全池 code 直方图（前 30 名，证明直方图逻辑有效）----")
        val full = HashMap<String, Int>()
        var rawHasTry = 0
        var rawHasCallform = 0
        var rawHasComAble = 0
        for (i in 0 until n) {
            val st = program.statement(i)
            full[st.code.name] = (full[st.code.name] ?: 0) + 1
            val r = st.raw
            if (r.contains("TRY", ignoreCase = true)) rawHasTry++
            if (r.contains("CALLFORM", ignoreCase = true)) rawHasCallform++
            if (r.contains("COM_ABLE", ignoreCase = true)) rawHasComAble++
            if ((i + 1) % 400000 == 0) System.err.println("[红O] …二次扫描 ${i + 1}/$n")
        }
        full.toList().sortedByDescending { it.second }.take(30).forEach { (k, v) -> out("[红O]   ${k.padEnd(28)} $v") }
        out("[红O]   不同 code 种类总数 = ${full.size}")
        out("[红O]   raw 含 \"TRY\"     的语句 = $rawHasTry")
        out("[红O]   raw 含 \"CALLFORM\" 的语句 = $rawHasCallform")
        out("[红O]   raw 含 \"COM_ABLE\" 的语句 = $rawHasComAble")

        out("")
        out("---- D2) COM_ABLE400 的 RETURN 语句原文（语句池下标 104361..104371）----")
        for (idx in 104361..104371) {
            val st = runCatching { program.statement(idx) }.getOrNull() ?: continue
            out("[红O]   [$idx] ${st.javaClass.simpleName}/${st.code.name} L${st.line}  raw=${st.raw.qq()}")
        }

        // 落盘（cwd = core-vm 模块目录）
        val reportPath = Path.of("build", "redO-product-inspect.txt")
        runCatching {
            Files.createDirectories(reportPath.parent)
            Files.write(reportPath, sb.toString().toByteArray(Charsets.UTF_8))
            System.err.println("[红O] 报告已写 $reportPath")
        }

        // 取证装置：三条链路都空 = 该语句根本没进产物，这是重要结论，显式红
        if (tryFormStmts.isEmpty() && comAbleStmts.isEmpty() && callSites.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail<Unit>(
                "语句池与指令流里都找不到 TRYCALLFORM / COM_ABLE 站点 —— 需要人工复核",
            )
        }
    }

    // ----------------------------------------------------------------------
    // 打印：一条语句的完整取证信息
    // ----------------------------------------------------------------------

    private fun dumpStmt(program: Program, idx: Int, out: (String) -> Unit) {
        val st = program.statement(idx)
        out("[红O]   --- 语句池下标 $idx ---")
        out("[红O]     类=${st.javaClass.simpleName}  code=${st.code.name}  line=${st.line}")
        out("[红O]     raw=${st.raw.qq()}")
        val arg = st.args
        out("[红O]     args 类=${arg.javaClass.simpleName}" +
            " isConst=${arg.isConst} constStr=${arg.constStr.qq()} literalStr=${arg.literalStr?.qq()}")
        if (arg is SpCallArgument) {
            out("[红O]     SpCallArgument.label=${arg.label?.qq() ?: "null（⇒ 走动态名分支）"}")
            out("[红O]     funcNameTerm: ${describeExpr(arg.funcNameTerm)}")
            out("[红O]     rowArgs=${arg.rowArgs.map { describeExpr(it) }}")
        }
    }

    // ----------------------------------------------------------------------
    // 指令流工具
    // ----------------------------------------------------------------------

    private class CallSite(val pc: Int, val line: Int, val owner: String, val exprIndex: Int)

    /**
     * 逐 pc 扫全段指令，收集落在 [lines] 上的 `_CALL_BY_NAME` / `_JUMP_BY_NAME`，
     * 打印操作数并顺带读出前驱 `_EVAL_STR` 的表达式节点下标。
     */
    private fun dumpCallByNameAtLines(program: Program, lines: IntRange, out: (String) -> Unit): List<CallSite> {
        val entries = program.functions.map { it.entryPc }.sorted()
        val sites = ArrayList<CallSite>()
        for (pc in 0 until program.instructionCount) {
            val op = program.op(pc)
            if (op != Opcode._CALL_BY_NAME && op != Opcode._JUMP_BY_NAME) continue
            val ln = program.lineOf(pc)
            if (ln !in lines) continue
            val b = program.b(pc)
            val owner = ownerName(program, entries, pc)
            // 前驱：_EVAL_STR a=<exprIndex> b=<nameSlot>
            var exprIndex = -1
            if (pc > 0 && program.op(pc - 1) == Opcode._EVAL_STR) exprIndex = program.a(pc - 1)
            sites += CallSite(pc, ln, owner, exprIndex)
            out("[红O]   pc=$pc L$ln ${Opcode.nameOf(op)} a=${program.a(pc)} b=$b c=${program.c(pc)}" +
                "  {argc=${b and 0x0FFFFFFF} try=${(b ushr 31) and 1} jump=${(b ushr 30) and 1} callf=${(b ushr 29) and 1}}" +
                " owner=$owner")
            if (pc > 0) {
                val p0 = pc - 1
                out("[红O]       ↑ pc=$p0 L${program.lineOf(p0)} ${Opcode.nameOf(program.op(p0))}" +
                    " a=${program.a(p0)} b=${program.b(p0)} c=${program.c(p0)}")
            }
        }
        out("[红O]   （line $lines 上共 ${sites.size} 条按名跳转/调用）")
        return sites
    }

    private fun ownerName(program: Program, sortedEntries: List<Int>, pc: Int): String {
        var lo = 0
        var hi = sortedEntries.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sortedEntries[mid] <= pc) {
                best = mid; lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best < 0) return "<none>"
        val entry = sortedEntries[best]
        return program.functions.firstOrNull { it.entryPc == entry }?.name ?: "<entry=$entry>"
    }

    private class Range(val start: Int, val last: Int) {
        val count: Int get() = last - start + 1
    }

    private fun pcRange(program: Program, f: FunctionInfo): Range {
        val next = program.functions.map { it.entryPc }.filter { it > f.entryPc }.minOrNull()
            ?: program.instructionCount
        return Range(f.entryPc, next - 1)
    }

    // ----------------------------------------------------------------------
    // 打印工具
    // ----------------------------------------------------------------------

    private fun String.qq(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun describeExpr(t: Expr?): String = when (t) {
        null -> "null"
        is StrLiteral -> "StrLiteral(value=${t.value.qq()})  ⇒ 常量名"
        is FormStringTerm -> buildString {
            append("FormStringTerm(line=${t.line}, col=${t.col}, parts=${t.parts.size})  ⇒ 动态表达式")
            t.parts.forEachIndexed { i, p ->
                when (p) {
                    is FormPart.Text -> append("\n        part[$i] FormPart.Text  str=${p.str.qq()}")
                    is FormPart.Embedded ->
                        append("\n        part[$i] FormPart.Embedded conversion=${p.conversion.javaClass.simpleName}(${p.conversion}) ast=${describeExpr(p.ast)}")
                }
            }
        }
        is VarRef -> "VarRef(name=${t.name.qq()}, indices=${t.indices.map { describeExpr(it) }}," +
            " subKey=${t.subKey?.qq() ?: "null"}, type=${t.operandType}, isConst=${t.isConst})"
        else -> "${t.javaClass.simpleName}($t)"
    }
}
