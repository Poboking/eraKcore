package com.eratw.port.vmdbg

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * `ledger`：bug 台账（CONTRACT-DBG1 路2）。
 *
 * ## 指纹
 *
 * fingerprint = sha256(异常类 + funcName + line + pc + message 归一化)，竖线连接：
 * - 归一化 = 数字段替换为 `#`、空白折叠成单空格并去首尾 —— 同一报错点因计数器值不同
 *   产生的 message 漂移（如「下标 3 越界」vs「下标 4 越界」）折成同一指纹；
 * - **步数 / 耗时不进指纹**（非确定量，进指纹会让两次同语料 run 的 diff 全是噪音）；
 * - 异常类取 `javaClass.simpleName`（sweep.jsonl 的 `cls` 字段），Line/pc 取 CodeEE 的
 *   结构化字段（err/CodeEE.kt:20-25）。
 *
 * ## diff 口径（`ledger diff --base <sweep.jsonl> --head <sweep.jsonl>`）
 *
 * 以**指纹**为最小单位、以**函数（key）是否在另一侧失败过**区分「新增 vs 回归」：
 * - **新增**：指纹在 head 失败、base 没有该指纹失败，且该函数在 base **没有任何失败**
 *   （base 里它通过 / 未被清扫）—— 全新冒出的失败；
 * - **回归**：指纹在 head 失败、base 没有该指纹失败，但该函数在 base **以别的指纹失败过**
 *   （失败形状变了：修 A 冒 B 的函数属于这一类，不宜当「新虫」报）——契约第三句
 *   「base 该指纹未失败 head 失败 = 回归」按函数粒度落地；
 * - **修复**：指纹在 base 失败、head 没有该指纹失败；
 * - **共有**（计数表第四类，非契约三类）：两侧都有该指纹失败（未变）。
 *
 * 同一 jsonl 内同 key 多条记录取**最后一条**（sweep 的占位覆写协议：终态以后写者为准；
 * 见 CmdSweep 的挂起检测段）。多跑 flaky 检测（同指纹跨次时有时无）留 W2 —— TODO(verify)。
 */
object CmdLedger {

    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val sub = a.positional.getOrNull(1) ?: throw CliError("用法: ledger diff --base <sweep.jsonl> --head <sweep.jsonl>")
        if (sub != "diff") throw CliError("无法识别的 ledger 子命令: $sub（目前只有 diff）")
        val base = Path.of(a.value("--base") ?: throw CliError("缺 --base <sweep.jsonl>"))
        val head = Path.of(a.value("--head") ?: throw CliError("缺 --head <sweep.jsonl>"))
        for (p in listOf(base, head)) {
            if (!Files.isRegularFile(p)) throw CliError("sweep.jsonl 不存在: $p")
        }
        val diff = diff(loadTerminal(base), loadTerminal(head))
        render(diff, out)
        return 0
    }

    /**
     * 同 key 取最后一条终态记录（RUNNING 占位行跳过 —— 挂死档也能读）。
     *
     * 文件可带 BOM（PowerShell 5.1 `Out-File -Encoding utf8` 的产物）：[Jsonl.parseLine]
     * 行首容忍 U+FEFF。2026-10-25 修——修前不剥，base 首条失败记录被静默丢弃，diff 把它
     * 错报成「新增」（指纹在 head 有、base 无，且该函数在 base 再无其他失败）。
     */
    fun loadTerminal(jsonl: Path): Map<String, SweptRecord> {
        val byKey = LinkedHashMap<String, SweptRecord>()
        for (line in Files.readAllLines(jsonl, Charsets.UTF_8)) {
            if (line.isBlank()) continue
            val fields = Jsonl.parseLine(line) ?: continue
            if (fields["status"] == "RUNNING") continue
            val rec = SweptRecord.fromFields(fields) ?: continue
            byKey[rec.key] = rec
        }
        return byKey
    }

    /** diff 结果：三个契约类 + 共有，各存「指纹 → 该指纹的 head/base 记录」。 */
    class DiffResult(
        val added: Map<String, SweptRecord>,
        val fixed: Map<String, SweptRecord>,
        val regressed: Map<String, SweptRecord>,
        val common: Map<String, SweptRecord>,
    )

    /** 指纹维度聚类 + 函数维度的新增/回归分流（口径见类 KDoc）。 */
    fun diff(base: Map<String, SweptRecord>, head: Map<String, SweptRecord>): DiffResult {
        val baseFails = failuresByFp(base.values)
        val headFails = failuresByFp(head.values)
        val baseFailedKeys = base.values.filter { it.failed }.mapTo(HashSet()) { it.key }

        val added = LinkedHashMap<String, SweptRecord>()
        val regressed = LinkedHashMap<String, SweptRecord>()
        for ((fp, rec) in headFails) {
            if (fp in baseFails) continue
            if (rec.key in baseFailedKeys) regressed[fp] = rec else added[fp] = rec
        }
        val fixed = baseFails.filterKeys { it !in headFails }
        val common = baseFails.filterKeys { it in headFails }
        return DiffResult(added, fixed, regressed, common)
    }

    private fun failuresByFp(records: Collection<SweptRecord>): Map<String, SweptRecord> =
        records.filter { it.failed }.associateBy {
            Ledger.fingerprint(it.cls, it.fn, it.line, it.pc, it.msg)
        }

    private fun render(d: DiffResult, out: PrintStream) {
        out.println("ledger diff: 新增 ${d.added.size} / 回归 ${d.regressed.size} / 修复 ${d.fixed.size} / 共有 ${d.common.size}")
        out.println()
        fun section(title: String, entries: Map<String, SweptRecord>) {
            out.println("== $title (${entries.size}) ==")
            for ((fp, rec) in entries) {
                out.println("  [${rec.status}] ${rec.cls} ${rec.fn}:${rec.line}:${rec.pc} fp=${fp.take(12)}")
                out.println("    msg=${Ledger.normalizeMessage(rec.msg).take(120)}")
            }
            out.println()
        }
        section("新增 NEW", d.added)
        section("回归 REGRESSED", d.regressed)
        section("修复 FIXED", d.fixed)
        out.println("== 共有 UNCHANGED (${d.common.size}) ==")
    }
}

/** 指纹算法（口径见 CmdLedger KDoc；sweep 的 summary.md 聚类与 ledger diff 共用一份）。 */
object Ledger {

    /** message 归一化：数字段 → `#`，空白折叠为单空格、去首尾。 */
    fun normalizeMessage(m: String): String =
        m.replace(Regex("[0-9]+"), "#").replace(Regex("\\s+"), " ").trim()

    /** sha256(cls | funcName | line | pc | 归一化 message)，小写十六进制。 */
    fun fingerprint(cls: String, funcName: String?, line: Int, pc: Int, message: String): String {
        val material = "$cls|${funcName ?: "-"}|$line|$pc|${normalizeMessage(message)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
