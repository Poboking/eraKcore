package com.eratw.port.core.perf

import com.eratw.port.boot.BootModel
import com.eratw.port.core.code.Program
import com.eratw.port.core.data.csv.GameData
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.perf.CensusRow
import com.eratw.port.perf.HeapMeter
import com.eratw.port.perf.SizeModel

/** 语句原文账单（编译后、运行期 raw 释放前采样一次；只数 `String.length`，O(1)/条）。 */
internal class RawBill(
    val statements: Int,
    val chars: Long,
    val bytes: Long,
    val latin1: Int,
) {
    val utf16: Int get() = statements - latin1
}

/** 桶级合计（报告表头用）。 */
internal class DecompGroup(val name: String, val bytes: Long, val rows: Int)

/** 单次装载后分解的完整读数。 */
internal class PoolDecompResult(
    val rows: List<CensusRow>,
    val groups: List<DecompGroup>,
    /** 逐类模型账单（前 N），用于与 jcmd 类直方图对拍、解释残差。 */
    val classes: List<Triple<String, Int, Long>>,
    /** 由根集合可达的字节（互斥口径，等于 [rows] 之和）。 */
    val attributed: Long,
    val objects: Long,
    val walkMs: Long,
    /** 分解开始前的 JVM 常驻（`Runtime.total - free`，N 轮 GC 最小值）。 */
    val used: Long,
    /** 空 JVM 基线 S0（同一装置、同一口径）。 */
    val s0: Long,
    /** `used - s0 - attributed`：未归属（java.base 内部结构 / 类加载 / 碎片 / 建模误差）。 */
    val residual: Long,
    val rawBill: RawBill,
    val exprSharedRoots: Int,
    val stringShared: Int,
    val sealProof: String,
    /** 既有 PoolCensus §2 表里「去重索引」三行在 seal 之后已失效，其虚增字节。 */
    val staleIndexBytes: Long,
    /** 遍历结束并释放访问集之后的复读，用于证明测量本身没有污染常驻；由调用方在 measure 返回后写入。 */
    var postWalkUsed: Long = 0L,
) {

    fun logLines(): List<String> {
        val out = ArrayList<String>()
        out += "[perf] 池分解（互斥口径，遍历 ${walkMs}ms / ${objects} 对象）：" +
            "已归属 ${mib(attributed)}，S0 ${mib(s0)}，未归属 ${signed(residual)}" +
            "，合计 = S3 常驻 ${mib(used)}"
        var acc = 0L
        for (g in groups) {
            acc += g.bytes
            out += "[perf]   桶 ${g.name} = ${mib(g.bytes)}（占已归属 ${percent(g.bytes, attributed)}）"
        }
        for (r in rows) {
            out += "[perf]     ${r.group} | ${r.name} | ${r.count} | ${mib(r.bytes)}"
        }
        out += "[perf]   逐类模型账单（前 12）：" + classes.take(12)
            .joinToString("；") { "${it.first.substringAfterLast('.')} ${it.second}/${mib(it.third)}" }
        out += "[perf]   seal 验证：$sealProof"
        out += "[perf]   遍历后复读常驻 ${mib(postWalkUsed)}（分解前 ${mib(used)}）"
        return out
    }

    fun render(): String {
        val sb = StringBuilder()
        sb.appendLine("### 2b. 装载后常驻逐池分解（M6-B PERF-POOL-DECOMP）")
        sb.appendLine()
        sb.appendLine("> 时点：`ProcessRuntime` 建立后、`begin` 之前（= 上表 `S3`），与预算读数同一时点。")
        sb.appendLine("> 口径：对象图**身份去重**遍历（[ResidentWalker] + [SizeModel] 布局模型），先到者拥有共享实例；")
        sb.appendLine("> 因此各行**互斥、可相加**。这是「内存花在哪」的分解，不是「删掉某池能省多少」（后者会重复计共享部分）。")
        sb.appendLine()
        sb.appendLine("| 桶 | 项 | 计数 | 估算 | 占已归属 | 口径 |")
        sb.appendLine("|---|---|---|---:|---:|---|")
        for (r in rows) {
            sb.appendLine(
                "| ${r.group} | ${r.name} | ${r.count} | ${mib(r.bytes)} | " +
                    "${percent(r.bytes, attributed)} | ${r.note} |",
            )
        }
        sb.appendLine("| | **已归属合计** | ${objects} 对象 | **${mib(attributed)}** | 100% | 遍历 ${walkMs} ms |")
        sb.appendLine("| | 空 JVM 基线 S0 | — | **${mib(s0)}** | — | 同一装置开头读数 |")
        sb.appendLine("| | 未归属（残差） | — | **${signed(residual)}** | — | 主要是 String 每条 8B 与 HashMap 的 EntrySet 未计；另有堆内非对象开销 |")
        sb.appendLine("| | **= JVM 实测常驻 S3** | — | **${mib(used)}** | — | `total - free`，${HeapMeter.rounds} 轮 GC 最小值 |")
        sb.appendLine()
        sb.appendLine("**桶小计**：" + groups.joinToString("；") { "${it.name} ${mib(it.bytes)}" })
        sb.appendLine()
        sb.appendLine("**逐类模型账单（前 15）** —— 可与 `jcmd <pid> GC.class_histogram` 逐类对拍：")
        sb.appendLine()
        sb.appendLine("| 类 | 实例 | 估算 |")
        sb.appendLine("|---|---:|---:|")
        for ((name, count, bytes) in classes.take(15)) {
            sb.appendLine("| `${name}` | $count | ${mib(bytes)} |")
        }
        sb.appendLine()
        sb.appendLine(
            if (postWalkUsed > 0) {
                "- 遍历后复读常驻 ${mib(postWalkUsed)}（分解前 ${mib(used)}）——差值即测量装置临时结构的残留，应在噪声内。"
            } else {
                "- 遍历后复读：调用方未提供（测量装置临时结构可能抬高后续读数）。"
            },
        )
        sb.appendLine("- seal 验证：$sealProof")
        sb.appendLine(
            "- 语句原文（编译期存在，`S3` 前由 `ProcessRuntime` 构造期释放）：" +
                "${rawBill.statements} 条 / ${rawBill.chars} 字符 = ${mib(rawBill.bytes)}" +
                "（latin1 ${rawBill.latin1} / UTF-16 ${rawBill.utf16} 条）。这是 `S2` 与 `S3` 的差的主要来源，不在上表内。",
        )
        sb.appendLine(
            "- 共享：`exprs` 池有 ${exprSharedRoots} 个根与语句 `args` 是**同一实例**（已计入语句池）" +
                (if (exprSharedRoots == 0) "——0 表示两套树身份完全不重叠，表达式池是语句树之外的第二份对象图" else "") +
                "；字符串池有 ${stringShared} 条与语句/函数表共享。",
        )
        sb.appendLine(
            "- 既有 §2 表的三行「去重索引」在 `Program.init -> ConstantPool.seal()` 之后**已释放**（`Program.kt:124-128`），" +
                "§2 仍按 48B/条计入，虚增 ${mib(staleIndexBytes)}；本表不计。",
        )
        sb.appendLine()
        sb.appendLine("误差来源（估算，不是 JOL；下表已用 `jcmd` 类直方图定位）：")
        sb.appendLine("- 浅尺寸按 HotSpot 布局建模（12B 头、8 对齐、压缩指针 4B、compact strings），非 `Instrumentation.getObjectSize`；")
        sb.appendLine("- `String`：模型 32B 对象壳；HotSpot 实测 24B 对象 + 16B 数组头 = 40B ⇒ 每条少算 8B（全量 2.23M 条 ≈ 17 MiB）；")
        sb.appendLine("- `java.util.HashMap` 对象模型 40B、实测 48B；每 map 的 `HashMap\u0024EntrySet`（110k 个）未计；")
        sb.appendLine("- `ArrayList` 容量按 `size` 下界估（真实容量最多再大 50%，即最多低估约 2B/项）；")
        sb.appendLine("- `HashMap`/`LinkedHashMap` 表容量按 `tableSizeFor(ceil(n/0.75))` 反推、32/40B 节点；树化桶未单独建模；")
        sb.appendLine("- `java.base` 的 `Path`/`Pattern`/`StringBuilder` 等内部字段不可反射，只计浅尺寸；`Class`/枚举只计浅尺寸；")
        sb.appendLine("- dex/ART 元数据、native 映射不在 Java 堆读数内（真机另计）。")
        sb.appendLine()
        sb.appendLine("复现（本机 64 GB / 6g 堆；语料指到 ERB 子树）：")
        sb.appendLine("```powershell")
        sb.appendLine("\u0024env:JAVA_HOME='D:\\DSH\\eratw-安卓移植计划\\.tooling\\jdk-17.0.20.1+1'")
        sb.appendLine("\u0024env:PATH=\"\u0024env:JAVA_HOME\\bin;\u0024env:PATH\"")
        sb.appendLine("\u0024env:ERATW_CORPUS='D:\\DSH\\eratw-安卓移植计划\\eratw-画蛇添足版eratw4.980\\ERB'")
        sb.appendLine("\u0024env:ERATW_GAMEDIR='D:\\DSH\\eratw-安卓移植计划\\eratw-画蛇添足版eratw4.980'")
        sb.appendLine("cd <worktree>/eratw-android")
        sb.appendLine(".\u005cgradlew.bat --configure-on-demand -Dorg.gradle.jvmargs=-Xmx6g -Pkotlin.daemon.jvmargs=-Xmx6g :core-vm:perfBaseline")
        sb.appendLine("# 类直方图对拍：再设 \u0024env:ERATW_PERF_PAUSE_MS='45000' 跑一次，")
        sb.appendLine("# 在 PAUSE 行拿到 pid 后：jcmd <pid> GC.class_histogram")
        sb.appendLine("```")
        sb.appendLine()
        return sb.toString()
    }
}

/**
 * 全量语料「装载后常驻」逐池分解（M6-B §2.6）。
 *
 * ## 为什么要第二张表
 * 既有 [com.eratw.port.perf.PoolCensus] 给的是「每池独立估算 + 抽样外推」，它自己注明
 * 合计是**上限**（`statements.args` 与 `exprs` 池共享实例会重复计入），且三行
 * 「去重索引」在 MEM-OPT `seal()` 之后已失效。本类不替换它，而是在同一个时点补一张
 * **互斥可加**的表，回答「709 MiB 到底在哪个池」。
 *
 * ## 归属规则（决定表怎么读）
 * 固定顺序遍历：`code/lines` → 常量池 → 语句池（对象/args/raw）→ 表达式池 → 字符串池 →
 * 函数表 → 侧车 BootMetadata → 变量与角色表 → 运行期对象。共享实例归**先到者**：
 * 语句 `args` 与 `exprs` 池共享的 Expr 计入语句池；`VariableStore.registry` 与
 * `GameData.registry` 是同一对象，计入变量表的第一行。交换顺序只改归属，不改总和。
 *
 * ## 复现
 * 由 `:core-vm:perfBaseline`（JavaExec，6g 堆）在 `S3` 读数之后调用；`ERATW_CORPUS`
 * 指到语料的 `ERB` 子树。报告落 `core-vm/build/perf-report.md` §2b。
 *
 * ## 全量实测结论（2026-10-20，4,800,579 指令 / 3,971 文件，S3 ≈ 708.5–709.2 MiB）
 *
 * | 桶 | MiB | 占已归属 668.7 |
 * |---|---:|---:|
 * | ② 语句池（对象 62.8 + args/条件子树 306.5 + 池 6.5） | 375.9 | 56.2% |
 * | ③ 表达式池（独占） | 94.8 | 14.2% |
 * | ① code + lines（IntArray） | 91.6 | 13.7% |
 * | ④ 函数表（functions 17.2 + FunctionTable 33.8 + labelPcs 9.5 + dataSections 0.9 + fileBySigKey 4.8） | 66.3 | 9.9% |
 * | ⑥ 变量与角色表（registry/角色/字符串表/CSV 模板） | 33.7 | 5.0% |
 * | ⑦ 侧车 BootMetadata（#DIM/宏/清单） | 5.4 | 0.8% |
 * | ⑤ 字符串驻留池 + 常量表 | 1.1 | 0.2% |
 * | ⑧ 运行期容器（ProcessRuntime 外壳） | 0.0 | 0.0% |
 *
 * 对账：已归属 668.7 + S0 1.7 + 未归属 38.1–38.8 = **708.5–709.2 MiB**（遍历后复读 708.8–709.5，装置无残留）。
 * 未归属的大头是建模误差：`String` 每条少算 8B（2.21M 条 ≈ 19 MiB）、
 * 每 map 的 `HashMap.EntrySet` 未计（110k）、堆内非对象开销 ≈ 7.7 MiB。
 *
 * 三条与 BOARD 旧读数不同的事实：
 * 1. 语句池是 **171.7 万条 / 375.9 MiB**，不是「122,074 条 / 6.05 MB」；
 *    其中 args/条件/内嵌值子树 306.5 MiB / 966 万对象是最大单项。
 * 2. 函数表段是 **66.3 MiB**，不是「6.07 MB」；仅 `FunctionTable` 的三个查找 HashMap
 *    + `labelPcs` + `fileBySigKey` 就 48.1 MiB（键与 FuncMeta/labelNames 重复）。
 * 3. `code+lines` = 91.6 MiB（占 709.2 的 12.9%）—— mmap 天花板就在这个量级，
 *    单靠 mmap 补不上 309 MiB 缺口。
 *
 * 另一条可直接落刀的发现：`exprs` 池与语句 `args` 的共享根数为 **0**（身份不重叠），
 * 即 94.8 MiB 的表达式池是语句树之外的第二份表达式对象图。
 */
internal object PoolDecomp {

    const val G_CODE = "① code/lines"
    const val G_STMT = "② 语句池"
    const val G_EXPR = "③ 表达式池"
    const val G_FN = "④ 函数表"
    const val G_STR = "⑤ 字符串/常量"
    const val G_VAR = "⑥ 变量与角色"
    const val G_META = "⑦ 侧车元数据"
    const val G_OTHER = "⑧ 其他"

    private const val ARRAY_HEADER = 16L

    /** 程序侧分解结果（语句池先于表达式池遍历，共享实例归语句池）。 */
    internal class ProgramPart(
        val rows: List<CensusRow>,
        val bytes: Long,
        val objects: Long,
        val exprSharedRoots: Int,
        val stringShared: Int,
        val exprRootObjects: Long,
        val stmtArgObjects: Long,
    )

    /** 编译后、运行期前的语句原文账单；必须在 `ProcessRuntime` 构造**之前**调用。 */
    fun rawBill(program: Program): RawBill {
        var chars = 0L
        var bytes = 0L
        var latin1 = 0
        for (i in 0 until program.statementCount) {
            val s = program.statement(i).raw
            chars += s.length
            bytes += SizeModel.stringBytes(s)
            if (s.none { it.code > 0xFF }) latin1++
        }
        return RawBill(program.statementCount, chars, bytes, latin1)
    }

    /** 只做程序侧（不含侧车/变量/运行期），供单测与 [measure] 复用。 */
    fun decomposeProgram(program: Program, sizer: SizeModel): ProgramPart {
        val w = ResidentWalker(sizer)
        val part = programRows(w, program, sizer)
        w.releaseVisited()
        return part
    }

    /**
     * 装载后（S3）全量分解。`used` 必须是**遍历之前**读到的常驻，`s0` 是同一装置的空 JVM 基线。
     */
    fun measure(
        program: Program,
        model: BootModel,
        store: VariableStore,
        gameData: GameData?,
        runtime: ProcessRuntime,
        sizer: SizeModel,
        used: Long,
        s0: Long,
        rawBill: RawBill,
    ): PoolDecompResult {
        val t0 = System.nanoTime()
        val w = ResidentWalker(sizer)
        val rows = ArrayList<CensusRow>()

        val part = programRows(w, program, sizer)
        rows += part.rows

        // ---- ④ 函数表：Program.functions 已在上面的 part 里；这里补附属表 ----
        val tableCount = model.table.all().size
        rows += CensusRow(
            G_FN, "FunctionTable（FuncMeta）", "$tableCount 条", w.consume(model.table),
            "metas 列表 + byIndex/nonEventByKey/eventMembersByKey 三个 HashMap（键串共享处按先到者计）",
        )
        rows += CensusRow(
            G_FN, "BootModel.labelPcs", "${model.labelPcs.size} 个函数", w.consume(model.labelPcs),
            "函数键 → 标签表；键串可能与 FuncMeta.key 共享",
        )
        rows += CensusRow(
            G_FN, "BootModel.dataSections", "${model.dataSections.size} 节", w.consume(model.dataSections),
            "IdentityHashMap；键是语句池实例（已计入语句池），值是 DATA/DATALIST 的 Expr 组",
        )
        rows += CensusRow(
            G_FN, "BootModel.fileBySigKey", "${model.fileBySigKey.size} 条", w.consume(model.fileBySigKey),
            "函数键 → 源文件相对路径（首错定位）",
        )

        // ---- ⑦ 侧车 BootMetadata ----
        rows += CensusRow(
            G_META, "#DIM 全局声明（DimSpec）", "${model.globalVarDecls.size} 条",
            w.consume(model.globalVarDecls), "声明布局元数据；运行期注册消费",
        )
        rows += CensusRow(
            G_META, "#DIM 私有声明（key + DimSpec）", "${model.userVarDecls.size} 条",
            w.consume(model.userVarDecls), "函数键 → 声明；装载链消费",
        )
        rows += CensusRow(
            G_META, "顶层 #DIM 原文（GlobalDimDecl）", "${model.globalDimDecls.size} 条",
            w.consume(model.globalDimDecls), "装载链自采原文，运行期注册才解析（非语句池 raw）",
        )
        rows += CensusRow(
            G_META, "DimParseConfig（宏表 + 尺寸常量）", "宏 ${model.dimParseConfig.macros.size} 条",
            w.consume(model.dimParseConfig), "与运行期注册同源；含 #DEFINE 宏原文",
        )
        rows += CensusRow(
            G_META, "RealCorpus（根 + 文件清单）", "${model.corpus.files.size} 个 Path",
            w.consume(model.corpus), "桌面装置口径；真机由 erbc.v1/manifest 提供，Path 内部字段不可反射",
        )
        val diagBytes = w.consume(model.stats) + w.consume(model.notes) +
            w.consume(model.compileFailures) + w.consume(model.parseFailures) +
            w.consume(model.dimParseFailures) + w.consume(model.buildError)
        rows += CensusRow(
            G_META, "统计与诊断清单（stats/notes/failures）",
            "编译失败 ${model.compileFailures.size} / 解析失败 ${model.parseFailures.size}",
            diagBytes, "装置侧读数；真机 BootMetadata 不保留失败清单（可按 0 计）",
        )
        w.markVisited(model)
        rows += CensusRow(G_META, "BootModel 外壳", "1 个对象", sizer.objectSize(model), "字段已逐项计入上面各行")

        // ---- ⑥ 变量与角色表 ----
        rows += CensusRow(
            G_VAR, "VariableStore（registry/角色表/字符串表/模板）", "1 个 store",
            w.consume(store), "含 gInt*/gStr* 平铺数组、characters、userVars 与 CSV 模板",
        )
        rows += CensusRow(
            G_VAR, "GameData（CSV/.als 产物）", if (gameData == null) "未装载" else "1 份",
            if (gameData == null) 0L else w.consume(gameData),
            "与 store.registry 是同一对象；共享部分已计入上一行（含 .als 名字字典）",
        )

        // ---- ⑧ 运行期对象 ----
        rows += CensusRow(
            G_OTHER, "ProcessRuntime（vm/execContext/console/beginFlow）", "1 个 runtime",
            w.consume(runtime), "含 Vm 帧槽/全局槽、ConsoleBridge、输出镜像、缓存；program/store/table 已计入上面",
        )

        val attributed = rows.sumOf { it.bytes }
        val objects = w.objects
        val walkMs = (System.nanoTime() - t0) / 1_000_000

        val groups = aggregate(rows)
        val residual = used - s0 - attributed
        val stale = (program.constantCount + program.stringCount + program.exprCount) * 48L

        val pool = field(program, "pool")
        val sealProof = "pool.longIndex=${field(pool, "longIndex")}，" +
            "stringIndex=${field(pool, "stringIndex")}，exprIndex=${field(pool, "exprIndex")}"

        w.releaseVisited()

        return PoolDecompResult(
            rows = rows,
            groups = groups,
            classes = w.classTop(25),
            attributed = attributed,
            objects = objects,
            walkMs = walkMs,
            used = used,
            s0 = s0,
            residual = residual,
            rawBill = rawBill,
            exprSharedRoots = part.exprSharedRoots,
            stringShared = part.stringShared,
            sealProof = sealProof,
            staleIndexBytes = stale,
        )
    }

    // ------------------------------------------------------------------
    // 程序侧行
    // ------------------------------------------------------------------

    private fun programRows(w: ResidentWalker, p: Program, sizer: SizeModel): ProgramPart {
        val rows = ArrayList<CensusRow>()
        val o0 = w.objects

        // 取指与行号表都已改成紧凑/映射表示（w8-codeview + w8-codemap）：
        // 这里必须读 `*ResidentBytes`，**不能碰 `p.code`/`p.lines`** —— 它们是冷路径兼容视图，
        // 一读就会把 73.3 MiB 的映射 CODE 物化回 IntArray，污染「遍历后复读」读数。
        val codeBytes = p.codeResidentBytes
        rows += CensusRow(
            G_CODE, "Program.code", "${p.instructionCount} 指令（映射常驻=${codeBytes}）", codeBytes,
            "映射来源 0 B；数组来源 16B 头 + 4B/字（`Program.codeResidentBytes`）",
        )
        val linesBytes = p.lineTableResidentBytes
        rows += CensusRow(
            G_CODE, "Program.lines", "紧凑行表（2B/条）", linesBytes,
            "u16 主表 + 溢出表（`Program.lineTableResidentBytes`）",
        )

        val poolObj = field(p, "pool")
        w.markVisited(p)
        w.markVisited(poolObj)
        var shell = sizer.objectSize(p)
        if (poolObj != null) shell += sizer.objectSize(poolObj)
        rows += CensusRow(
            G_CODE, "Program + ConstantPool 外壳", if (poolObj == null) "1 个对象" else "2 个对象",
            shell, "不含 code/lines，也不含四个池的 ArrayList（见各池行）",
        )

        // ---- ⑤ 常量池 ----
        val longs = field(poolObj, "longs") as? List<*>
        if (longs != null) {
            w.markVisited(longs)
            var b = w.listOwnBytes(longs)
            for (v in longs) b += w.consume(v)
            rows += CensusRow(
                G_STR, "longs 常量池", "${longs.size} 条", b,
                "ArrayList<Long>（装箱 24B/条 + 4B 槽）；`longArray` 每次复制、不常驻",
            )
        }

        // ---- ② 语句池（先于表达式池，共享 Expr 归此处）----
        val stmts = field(poolObj, "statements") as? List<*>
        var stmtOwn = 0L
        var stmtSub = 0L
        var stmtArgObjects = 0L
        if (stmts != null) {
            w.markVisited(stmts)
            rows += CensusRow(
                G_STMT, "statements 池 ArrayList + 背衬", "${stmts.size} 条",
                w.listOwnBytes(stmts), "按身份追加、不去重（`Program.kt:47-58`）；背衬容量按 size 下界估",
            )
            for (i in 0 until p.statementCount) {
                val s = p.statement(i)
                // 运行期 raw 已释放为 ""（同一空串实例）；标记它，避免把空串算进子树
                w.markVisited(s.raw)
                val ob = w.objects
                val deep = w.consume(s)
                val own = sizer.objectSize(s)
                stmtOwn += own
                stmtSub += (deep - own).coerceAtLeast(0L)
                stmtArgObjects += w.objects - ob
            }
            rows += CensusRow(
                G_STMT, "语句对象外壳", "${p.statementCount} 条", stmtOwn,
                "各类 Statement 子类的浅尺寸之和",
            )
            rows += CensusRow(
                G_STMT, "语句 args/条件/内嵌值子树", "$stmtArgObjects 对象", stmtSub,
                "Argument/Expr 实例；与 exprs 池共享的 Expr 归此处（先到者）",
            )
        }

        // ---- ③ 表达式池（独占部分）----
        val exprs = field(poolObj, "exprs") as? List<*>
        var exprBytes = 0L
        var exprShared = 0
        var exprObjects = 0L
        if (exprs != null) {
            w.markVisited(exprs)
            exprBytes += w.listOwnBytes(exprs)
            for (i in 0 until p.exprCount) {
                val e = p.expr(i)
                if (w.isVisited(e)) {
                    exprShared++
                    continue
                }
                val ob = w.objects
                exprBytes += w.consume(e)
                exprObjects += w.objects - ob
            }
            rows += CensusRow(
                G_EXPR, "exprs 根 + 子树（独占）", "${p.exprCount} 根 / $exprObjects 对象", exprBytes,
                "结构去重后的表达式池；$exprShared 个根与语句 args 是同一实例（已在上一桶计）",
            )
        }

        // ---- ⑤ 字符串池 ----
        val strings = field(poolObj, "strings") as? List<*>
        var strBytes = 0L
        var chars = 0L
        var latin1 = 0
        var strShared = 0
        if (strings != null) {
            w.markVisited(strings)
            strBytes += w.listOwnBytes(strings)
            for (i in 0 until p.stringCount) {
                val s = p.string(i)
                if (w.isVisited(s)) {
                    strShared++
                    continue
                }
                strBytes += w.consume(s)
                chars += s.length
                if (s.none { it.code > 0xFF }) latin1++
            }
            rows += CensusRow(
                G_STR, "strings 驻留池", "${p.stringCount} 条 / $chars 字符", strBytes,
                "String 对象 + 底层数组（latin1 $latin1/${p.stringCount}，其余 UTF-16 2B/char）；$strShared 条与语句/函数表共享",
            )
        }

        // ---- 调用点实参表（v2 段 7；ProcessRuntime.onFunctionEnter 逐位绑定）----
        val callArgCount = p.callArgs.values.sumOf { it.size }
        rows += CensusRow(
            G_STMT, "Program.callArgs（调用点实参表）",
            "${p.callArgs.size} 个调用点 / $callArgCount 个 CallArg",
            w.consume(p.callArgs),
            "Map<Int, List<CallArg>>：装箱 Int 键 + 每调用点一个 ArrayList + CallArg 数据类（含 name 串）",
        )

        // ---- ④ Program.functions + 标签 ----
        var fnBytes = 0L
        var labels = 0
        val fns = p.functions
        w.markVisited(fns)
        fnBytes += w.listOwnBytes(fns)
        for (f in fns) {
            fnBytes += w.consume(f)
            labels += f.labelNames.size
        }
        rows += CensusRow(
            G_FN, "Program.functions + 函数内标签", "${fns.size} 函数 / $labels 标签", fnBytes,
            "FunctionInfo + labelNames/labelPcs 平行数组 + 名字串（`Program.kt:7-23`）",
        )

        return ProgramPart(
            rows = rows,
            bytes = rows.sumOf { it.bytes },
            objects = w.objects - o0,
            exprSharedRoots = exprShared,
            stringShared = strShared,
            exprRootObjects = exprObjects,
            stmtArgObjects = stmtArgObjects,
        )
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun aggregate(rows: List<CensusRow>): List<DecompGroup> {
        val order = LinkedHashMap<String, Long>()
        val count = HashMap<String, Int>()
        for (r in rows) {
            order[r.group] = (order[r.group] ?: 0L) + r.bytes
            count[r.group] = (count[r.group] ?: 0) + 1
        }
        return order.map { (k, v) -> DecompGroup(k, v, count[k] ?: 0) }
    }

    /** 读实例字段（含私有 Kotlin 字段）；walk 不到的对象图里只用于取池句柄。 */
    private fun field(target: Any?, name: String): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                return runCatching {
                    f.isAccessible = true
                    f.get(target)
                }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }
}

private fun mib(bytes: Long): String {
    val neg = bytes < 0
    val abs = if (neg) -bytes else bytes
    val tenths = (abs * 10 + 524_288) / 1_048_576
    return "${if (neg) "-" else ""}${tenths / 10}.${tenths % 10} MiB"
}

private fun signed(bytes: Long): String =
    (if (bytes < 0) "-" else "+") + mib(if (bytes < 0) -bytes else bytes)

private fun percent(part: Long, whole: Long): String {
    if (whole <= 0) return "-"
    val tenths = (part * 1000 + whole / 2) / whole
    return "${tenths / 10}.${tenths % 10}%"
}
