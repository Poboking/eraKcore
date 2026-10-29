package com.eratw.port.boot

import com.eratw.port.core.code.FlatStatements
import com.eratw.port.core.code.FlatStmtKey
import com.eratw.port.core.loader.ProgramLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * T2-64 波次 A 真语料全量对拍：装载 `program.erbc` → 语句池全量压成 int 流 →
 * 逐条还原 → 与原件逐条对拍（类 / code / line / [FlatStmtKey] 结构键）。
 *
 * 判据：171.7 万条 0 差异（CONTRACT-T2-64 §6.1）。退出码 0 = 全过；1 = 有差异；
 * 2 = 产物缺失。运行：`gradle :core-vm:flatStmtAudit`（6g 堆，ERATW_* 透传）。
 *
 * 输出全部 ASCII（宿主 PowerShell 按 GBK 解码 stdout，中文会花——历史教训）。
 */
fun main() {
    val path = erbcPath() ?: run {
        System.err.println("[flat-audit] usage: ERATW_ERBC=<program.erbc> or ERATW_ERBC_DIR=<erbc.v1 dir> gradle :core-vm:flatStmtAudit")
        exitProcess(2)
    }
    if (!Files.isRegularFile(path)) {
        System.err.println("[flat-audit] FAIL: erbc not found: $path")
        exitProcess(2)
    }
    val t0 = System.currentTimeMillis()
    // 契约 §7 风险表：惰性档（mmap）与 eager 档要各自跑一遍对拍 —— 惰性档的
    // statement(i) 走 LazyStatementRecords 单条解码路径，与 loadFile 是两套代码。
    val mapped = System.getenv("ERATW_FLAT_MAPPED") == "1"
    System.err.println("[flat-audit] load (" + (if (mapped) "lazy/mmap" else "eager") + "): $path")
    val program = if (mapped) ProgramLoader.loadMapped(path) { } else ProgramLoader.loadFile(path)
    val tLoad = System.currentTimeMillis()
    System.err.println("[flat-audit] loaded in ${tLoad - t0} ms; stmts=${program.statementCount} exprs=${program.exprCount}")

    val statements = ArrayList<com.eratw.port.core.stmt.Statement>(program.statementCount)
    for (i in 0 until program.statementCount) statements.add(program.statement(i))
    val tList = System.currentTimeMillis()
    System.err.println("[flat-audit] statementList materialized in ${tList - tLoad} ms")

    // Program 不转发 exprList（那是 ConstantPool 的读面）；走逐条 expr(i)（eager 档全量已在堆内）。
    val exprPool = ArrayList<com.eratw.port.core.expr.Expr>(program.exprCount)
    for (i in 0 until program.exprCount) exprPool.add(program.expr(i))
    val flat = FlatStatements.encode(statements, exprPool)
    val tEnc = System.currentTimeMillis()
    System.err.println(
        "[flat-audit] encoded in ${tEnc - tList} ms: stream=${flat.stream.size} ints " +
            "(${flat.stream.size * 4L / 1024 / 1024} MB), strings=${flat.strings.size}, " +
            "poolSize=${flat.poolSize}, extraExprs=${flat.extraExprs.size}",
    )

    // 编码侧读面统计（对拍报告的一部分）。真语料实测（2026-10-27）：extraExprs=807,489 /
    // strings=579,948 —— **结构性读数而非漂移**：erbc 段 3 语句流内的 Expr 是内联解码的
    // 独立实例，与段 2 表达式池（poolSize=328,511）不共享身份 ⇒ 语句树 Expr 全走旁挂；
    // 旁表只是多一份引用槽，Expr 本就要驻留（求值必需），Statement/Argument/容器才是
    // 「压扁即弃」的收益面（契约 §1 收益通道 2）。波次 C 装载期可把树内 Expr 按 equals
    // intern 进池消旁挂，是否值得看收益再定。
    var diffs = 0
    val samples = ArrayList<String>(20)
    for (i in statements.indices) {
        val orig = statements[i]
        val back = flat.statementAt(i)
        val bad = orig.javaClass != back.javaClass ||
            orig.code != back.code ||
            orig.line != back.line ||
            FlatStmtKey.key(orig) != FlatStmtKey.key(back)
        if (bad) {
            diffs++
            if (samples.size < 20) {
                samples.add(
                    "[$i] class=${orig.javaClass.simpleName} vs ${back.javaClass.simpleName}" +
                        " code=${orig.code.name} vs ${back.code.name} line=${orig.line} vs ${back.line}" +
                        " keyEq=${FlatStmtKey.key(orig) == FlatStmtKey.key(back)}",
                )
            }
        }
        if ((i + 1) % 300000 == 0) System.err.println("[flat-audit] ...${i + 1}/${statements.size}")
    }
    val tCmp = System.currentTimeMillis()
    if (diffs == 0) {
        System.err.println(
            "[flat-audit] PASS: ${statements.size} statements, 0 diff " +
                "(encode ${tEnc - tList} ms, compare ${tCmp - tEnc} ms, total ${tCmp - t0} ms)",
        )
        exitProcess(0)
    } else {
        System.err.println("[flat-audit] FAIL: $diffs diffs / ${statements.size}")
        for (s in samples) System.err.println("[flat-audit]   $s")
        exitProcess(1)
    }
}

private fun erbcPath(): Path? {
    val direct = System.getenv("ERATW_ERBC")
    if (!direct.isNullOrBlank()) return Path.of(direct)
    val dir = System.getenv("ERATW_ERBC_DIR") ?: return null
    if (dir.isBlank()) return null
    val d = Path.of(dir)
    val p = d.resolve("program.erbc")
    return if (Files.isRegularFile(p)) p else {
        // 目录直给 erbc 文件的情况
        if (Files.isRegularFile(d)) d else null
    }
}
