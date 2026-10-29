package com.eratw.port.perf

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.perf.ResidentWalker
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **mem8 归因探针**：把 `HeapCompositionProbe` §1 里 FunctionTable 那枚 19.1 MiB 的粗桶
 * 拆成分根读数（照 §5「vars 面拆分」的写法：独立遍历 + `IdentityHashMap` 身份去重 +
 * 逐类 top5 + 共享实例只算一次），并按同一套语料真值为候选刀口建模投影。
 *
 * ## 为什么还要再加一枚（`FuncTableLayoutProbeTest` 已经拆过一次）
 *
 * 那枚是**逐字段建模**（每条 meta 手工累加），只能回答「串/壳/表各占多少」，
 * 回答不了「Node 壳、`Integer` 箱、事件 ArrayList 这些**容器自身**到底吃多少」，
 * 也没有逐类账单 ⇒ 与 §1 的遍历桶口径对不上账。本探针改走 [ResidentWalker]：
 * 先只 `markVisited` 表壳（不 mark 内容，否则 §1 的归属会被打乱），
 * 再按根独立消费，互斥可加、与 §1 同口径。
 *
 * ## 消费序（决定归属，逐条写死）
 *
 * 1. `key` 串（侧车驻留池已归一，与查询表共用同一实例）
 * 2. `name` 串（与 key 同实例的那 97% 不重复计入）
 * 3. `position` 存放体（压实后的文件前缀串表 / 未压实的原文）
 * 4. `FuncMeta` 对象壳 + `metas` 列表壳与背衬（串已归完 ⇒ 本行只剩浅尺寸）
 * 5. `byIndex` 引用数组本体（Array 引用槽，meta 实例不重复计）
 * 6. `nonEventByKey`：表壳 + 表数组 + Node 壳
 * 7. `eventMembersByKey`（含每键 ArrayList 壳）/ 8. `eventMemberByKey`
 * 9. `nameKeys` 大小写记忆表
 * 10. 其余（FunctionTable 对象壳、未点名字段、意外挂上的对象）
 *
 * ## 复现
 *
 * ```
 * ERATW_ERBC_DIR=<含 program.erbc 的目录> \
 * ./gradlew --configure-on-demand :core-vm:cleanTest :core-vm:test --tests "*FuncTableRootSplitProbe*" -i
 * ```
 *
 * 报告落 `core-vm/build/mem8-functable-attribution.md`（下一次运行即覆盖 ⇒ 当场拷进 `.local/`）。
 * 只测量，`:core-vm` 主源零改动。
 */
class FuncTableRootSplitProbeTest {

    private val sizer = SizeModel(SizeModel.detectRefWidth())

    private fun findErbcDir(): Path? {
        System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return if (Files.isRegularFile(Path.of(it).resolve("program.erbc"))) Path.of(it) else null }
        System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it).parent }
        return null
    }

    @Test
    fun `真产物：FunctionTable 19_1 MiB 分根拆解 + 候选刀口投影`() {
        val dir = findErbcDir()
        assumeTrue(dir != null && Files.isRegularFile(dir.resolve("program.erbc")), "无 ERATW_ERBC_DIR 产物：跳过")
        val metaFile = dir!!.resolve("program.meta")
        assumeTrue(Files.isRegularFile(metaFile), "无 program.meta：跳过")

        val metadata = BootMetadata.read(metaFile)
        val metas: List<FuncMeta> = metadata.functions
        val table = FunctionTable(metas)

        // ---- 反射取私有面（装置宁可少一行也不炸） ----
        fun tf(name: String): Any? = runCatching {
            FunctionTable::class.java.getDeclaredField(name).apply { isAccessible = true }.get(table)
        }.getOrNull()
        fun mf(m: FuncMeta, name: String): Any? = runCatching {
            FuncMeta::class.java.getDeclaredField(name).apply { isAccessible = true }.get(m)
        }.getOrNull()
        fun flagsOf(m: FuncMeta): Int = runCatching {
            val f = FuncMeta::class.java.getDeclaredField("flags").apply { isAccessible = true }
            f.getInt(m)
        }.getOrDefault(0)

        val byIndex = tf("byIndex") as Array<*>
        // 刀 D 之后「装载序」有两种形态：byIndex 的零拷贝视图（loadOrder）或列表字段
        // （orderedMetas，与 loadOrder 同一实例）。装置读 loadOrder，形态由 §2 那行报出。
        val metasList = tf("loadOrder") as List<*>
        val holdsOrderedList = tf("orderedMetas") != null
        // 查询表两种形态都要能读（刀 A 落地前后同一枚探针必须都能跑）。
        val nonEventMap = runCatching { tf("nonEventByKey") }.getOrNull() as Map<*, *>?
        val nonEventSlots = runCatching { tf("nonEventSlots") }.getOrNull() as IntArray?
        val nonEventEntries = nonEventMap?.size
            ?: (nonEventSlots?.count { it != 0 } ?: 0)
        val eventMembersByKey = tf("eventMembersByKey") as Map<*, *>
        val eventMemberByKey = tf("eventMemberByKey") as Map<*, *>
        val nameKeys = tf("nameKeys") as Map<*, *>

        // ---- 分根遍历（单只 walker、身份去重、按 KDoc 的消费序互斥可加） ----
        val w = ResidentWalker(sizer)
        val snap = { w.classTop(900).associate { it.first to (it.second to it.third) } }
        val rows = ArrayList<Triple<String, Long, String>>()
        fun bucket(name: String, vararg roots: Any?): Long {
            val before = snap()
            val t = System.nanoTime()
            var b = 0L
            for (r in roots) b += w.consume(r)
            val after = snap()
            val detail = after.entries
                .map { e ->
                    val (c0, b0) = before[e.key] ?: (0 to 0L)
                    Triple(e.key, e.value.second - b0, e.value.first - c0)
                }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(5)
                .joinToString(" / ") { "${it.first} +${it.third}个=${mib(it.second)}" }
            rows.add(Triple(name, b, "耗时 ${(System.nanoTime() - t) / 1_000_000} ms；逐类：$detail"))
            return b
        }

        // 三串面先归（后归的对象壳就不含串，行与行不重叠）。
        // 传进来的「装根的那个 ArrayList」是装置自己的脚手架，绝不能进账：
        // 先 markVisited，否则每行白背 0.4 MiB（首轮实测就这样多算 1.2 MiB）。
        val keySet = IdentityHashMap<Any, Boolean>()
        val nameSet = IdentityHashMap<Any, Boolean>()
        val posSet = IdentityHashMap<Any, Boolean>()
        for (m in metas) {
            (mf(m, "posText") as String?)?.let { posSet.put(it, true) }
            (mf(m, "key") as String)?.let { keySet.put(it, true) }
            (mf(m, "name") as String)?.let { nameSet.put(it, true) }
        }
        val keyRoots = keySet.keys.toTypedArray()
        val nameRoots = nameSet.keys.toTypedArray()
        val posRoots = posSet.keys.toTypedArray()
        // 逐条作根（不能把装根的集合本身交给 walker：markVisited 会连内容一起挡掉，
        // 不挡又会把脚手架的背衬算进账 —— 两种踩法都实测过）。
        bucket("key 串（侧车驻留池已归一，与查询表共用同一实例）", *keyRoots)
        bucket("name 串（key 之外的书写名；与 key 同实例的那 97% 不重复计入）", *nameRoots)
        bucket("position 存放体（压实=共享文件前缀串 / 未压实=原文）", *posRoots)
        // 串都归完了 ⇒ 这一行只剩 metas 列表壳 + 背衬 + 110,072 个 FuncMeta 浅尺寸
        bucket("FuncMeta 对象壳 + metas 列表壳与背衬（浅尺寸，16 属性 → 位域 + 3 引用 + 8 int）", metasList)
        // 两条槽位表本体
        bucket("byIndex 引用数组本体（Array<FuncMeta?>）", byIndex)
        // 查询表本体（两种形态都只归「表自身」：键值串已归到上面几行）
        bucket(
            if (nonEventMap != null) "非事件查询表：HashMap 档（表壳 + 表数组 + Node 壳）"
            else "非事件查询表：开放寻址档（IntArray 槽本体）",
            nonEventMap ?: nonEventSlots,
        )
        bucket("eventMembersByKey：表 + 每键 ArrayList 壳", eventMembersByKey)
        bucket("eventMemberByKey：表壳 + 表数组 + Node 壳", eventMemberByKey)
        bucket("nameKeys：按名大小写记忆表（键=meta.key 同实例 ⇒ 只剩壳+表+Node+额外驻留的查询串）", nameKeys)
        bucket("其余（FunctionTable 对象壳 / 未点名字段 / 意外挂上的对象）", table)
        val splitTotal = rows.sumOf { it.second }

        // ---- 语料真值普查（候选刀口的设计参量） ----
        var posPackable = 0
        var posPackedNow = 0
        var keyNonLatin1 = 0
        var nameOverride = 0
        var nameOverrideAllCase = true
        var argsOver1Byte = 0
        var localOver16Bit = 0
        var indexOver17Bit = 0
        var defDeltaOther = 0
        var maxDefDelta = 0
        var lineOver16Bit = 0
        var prefixCount = 0
        val prefixes = HashSet<String>(8192)
        for (m in metas) {
            // 压实判据只能在**读值面**上量（posText 里已是前缀串，再 split 必然空）。
            val raw = m.position
            if (flagsOf(m) and 128 != 0) posPackedNow++
            FuncMeta.splitPosition(raw)?.let {
                posPackable++
                prefixes.add(it.first)
                if (it.second > 0xFFFF) lineOver16Bit++
            }
            if (m.key.any { c -> c.code > 0xFF }) keyNonLatin1++
            if (m.name !== m.key) {
                nameOverride++
                if (m.name.uppercase() != m.key) nameOverrideAllCase = false
            }
            if (m.argIntCount > 0xFF || m.argStrCount > 0xFF) argsOver1Byte++
            if (m.localSize > 0xFFFF || m.localsSize > 0xFFFF) localOver16Bit++
            if (m.index > 0x1FFFF) indexOver17Bit++
            val d = m.defIndex - m.index
            if (d < 0 || d > 1) { defDeltaOther++; maxDefDelta = maxOf(maxDefDelta, d) }
        }
        prefixCount = prefixes.size

        // ---- 候选刀口建模（同一套 SizeModel 公式、同一份语料真值） ----
        val n = metas.size.toLong()
        val al: (Long) -> Long = { v -> (v + 7L) and (-8L) }
        fun arrB(bytesPerSlot: Long, len: Long, header: Long = 16L): Long =
            if (len <= 0L) 0L else al(header + bytesPerSlot * len)
        fun capFor(cnt: Int): Int {
            var cap = 16
            val need = (cnt.toLong() * 4L + 2L) / 3L
            while (cap < need && cap < (1 shl 30)) cap = cap shl 1
            return cap
        }
        fun hashB(cnt: Int): Long =
            if (cnt <= 0) 32L else 32L + arrB(4L, capFor(cnt).toLong()) + 32L * cnt
        fun strSetB(ss: Set<*>): Long = ss.filterIsInstance<String>().sumOf { SizeModel.stringBytes(it) }

        val shallow = sizer.shallowOf(FuncMeta::class.java).toLong()
        val shellNow = shallow * n
        val shell48 = 48L * n
        val shell40 = 40L * n
        val shell32 = 32L * n
        // key 串池分域：latin1 且长度 ≤ 255 → 1B/char；其余（非 latin1，含长串）→ 2B/char；
        // 长度 > 255 的串走第三段（1B/char，长度另存），本语料实测条数见 §2。
        var seg1 = 0L
        var seg2 = 0L
        var seg3 = 0L
        var latin1Fast = 0
        for (m in metas) {
            val k = m.key
            val nonLatin = k.any { c -> c.code > 0xFF }
            when {
                !nonLatin && k.length <= 255 -> { seg1 += k.length.toLong(); latin1Fast++ }
                !nonLatin -> seg3 += k.length.toLong()
                else -> seg2 += 2L * k.length
            }
        }
        val keyPool = arrB(1L, seg1) + arrB(2L, seg2) + arrB(1L, seg3)
        val keyIdx = arrB(4L, n) * 3L + arrB(4L, nonEventEntries.toLong())
        val keyStrNow = strSetB(keySet.keys.toSet())
        val nameOverridePool = strSetB(metas.filter { it.name !== it.key }.map { it.name }.toSet())
        val posPacked = arrB(4L, n) + strSetB(prefixes)
        val oa18 = arrB(4L, 1L shl 18)
        val oa17 = arrB(4L, 1L shl 17)
        val mapNow = hashB(nonEventEntries)
        val slotsNow = arrB(4L, (nonEventSlots?.size ?: 0).toLong())
        val indexNow = if (nonEventMap != null) mapNow else slotsNow
        // 刀 D：稠密下标序时不再持列表背衬 ⇒ 这一行归零。
        val metasListNow = if (holdsOrderedList) sizer.objectSize(metasList as Any) + arrB(4L, n) else 0L
        val prefixNow = strSetB(prefixes)
        val nameKeysNow = hashB(nameKeys.size)

        val sb = StringBuilder()
        sb.appendLine("# mem8 FunctionTable 归因拆解（真产物侧车 metas=${group(n)}；只测量，主源零改动）")
        sb.appendLine()
        sb.appendLine("- 产物：`${dir}`（meta ${group(Files.size(metaFile))} B）；尺寸口径 = SizeModel（对象头 12B / 压缩引用 4B / align8）")
        sb.appendLine("- 遍历：ResidentWalker 单只、身份去重、按上面 KDoc 的消费序互斥可加；表壳先 markVisited（不 mark 内容，§1 归属不受影响）")
        sb.appendLine()
        sb.appendLine("## 1. 分根读数（= §1 那枚 FunctionTable 桶的拆解）")
        sb.appendLine()
        sb.appendLine("| 根 | MiB | 逐类新增 top5 |")
        sb.appendLine("|---|---:|---|")
        for ((name, b, note) in rows) sb.appendLine("| $name | ${mib(b)} | $note |")
        sb.appendLine("| **分根合计** | **${mib(splitTotal)}** | 与 HeapCompositionProbe §1 同桶同口径（该桶基线读数 19.1 MiB，main@c7b89109） |")
        sb.appendLine()
        sb.appendLine("## 2. 语料真值普查（候选刀口的设计参量）")
        sb.appendLine()
        sb.appendLine("| 量 | 读数 | 用途 |")
        sb.appendLine("|---|---|---|")
        sb.appendLine("| meta 条数 / byIndex 槽数 | ${group(metas.size)} / ${group(byIndex.size)} | 每条 × 浅尺寸 = 壳账 |")
        sb.appendLine("| 独立 key 串实例（身份去重） | ${group(keySet.size)}（其中非 latin1 ${group(keyNonLatin1)} 条） | 串池分域打包的前提（latin1 腿 1B/char） |")
        sb.appendLine("| key 与 name 同实例的条数 | ${group(n - nameOverride)} / ${group(n)}（name 覆盖 ${group(nameOverride)} 条，全部=纯大小写差 ⇒ $nameOverrideAllCase） | 名覆盖表能否只存「非 key 那条」 |")
        sb.appendLine("| name 独立串合计 | ${mib(nameOverridePool)} MiB | 覆盖表成本 |")
        sb.appendLine("| position 可压实 / 已压实 | ${group(posPackable)} / ${group(posPackedNow)}；前缀去重 ${group(prefixCount)} 个；行号 >16 位 ${group(lineOver16Bit)} 条 | 前缀 id 12 位 + 行号 16 位 装一个 int 的判据 |")
        sb.appendLine("| argInt/argStr > 255 / LOCALSIZE > 16 位 | ${group(argsOver1Byte)} / ${group(localOver16Bit)} | 每条一字节 + 16 位切分的判据 |")
        sb.appendLine("| index > 17 位 / defIndex-index 越 {0,1} | ${group(indexOver17Bit)} / ${group(defDeltaOther)}（最大偏差 ${group(maxDefDelta)}） | 槽位存下标 + 装载序 delta 的判据 |")
        sb.appendLine("| 非事件查询表项数 / 当前档 | ${group(nonEventEntries)} / ${if (nonEventMap != null) "HashMap（表+Node）" else "开放寻址 IntArray(${group(nonEventSlots?.size ?: 0)} 槽)"}；事件项数 ${group(eventMembersByKey.size)} 键·${group(eventMemberByKey.size)} 成员 | 事件两张表按 11 万容量预分配 = 谣言 |")
        sb.appendLine("| nameKeys 记忆表项数（标题页稳态） | ${group(nameKeys.size)}（表+Node ${mib(hashB(nameKeys.size))} MiB） | 装载/装配期按名解析的调用面宽度 |")
        sb.appendLine("| 语料 key 最长字符数 | ${group(metas.maxOf { it.key.length })}（latin1 快路径可表 ${group(latin1Fast)} 条） | 8 位长度 + 慢路径腿 |")
        sb.appendLine()
        sb.appendLine("## 3. 候选刀口投影（同一份语料真值 × SizeModel 公式；**不是实测**，落地后以 §1 复跑为准）")
        sb.appendLine()
        sb.appendLine("| 刀 | 现形态 | 目标形态 | 投影收益 MiB | 热路径风险 |")
        sb.appendLine("|---|---|---|---:|---|")
        sb.appendLine("| A 查询表去 Node | 现档 ${mib(indexNow)}（${if (nonEventMap != null) "HashMap：表 ${mib(mapNow - 32L - 32L * nonEventEntries)} + Node ${mib(32L * nonEventEntries)}" else "开放寻址 IntArray ${group(nonEventSlots?.size ?: 0)} 槽"}） | HashMap 建模 ${mib(mapNow)} ／ 开放寻址建模 ${mib(arrB(4L, (1L shl 18)))}（cap 2^17 ⇒ ${mib(oa17)}） | 落地前 ${mib(mapNow - oa18)}（2^17 ⇒ ${mib(mapNow - oa17)}）；落地后本行只剩「还能不能压 cap」 | 按名解析腿；mem5 判死的是「槽数组 + 第二张 hash 过滤数组」双随机线形态，本档是单数组 |")
        sb.appendLine("| B FuncMeta 浅尺寸 | ${mib(shellNow)}（${shallow} B/条） | 48 B/条 = ${mib(shell48)}；40 B = ${mib(shell40)}；32 B = ${mib(shell32)} | ${mib(shellNow - shell48)} / ${mib(shellNow - shell40)} / ${mib(shellNow - shell32)} | 每条一次 `_CALL` 的字段读；位切分只加 ALU 不加访存 |")
        sb.appendLine("| C key 串池 | ${mib(keyStrNow)}（${group(keySet.size)} 个 String 对象，latin1 快路径可表 ${group(latin1Fast)} 条） | 分域打包（1B/char 段 + 2B/char 段 + 长串段）+ 偏移/hash/装载序/索引四张表 = ${mib(keyPool + keyIdx)} | ${mib(keyStrNow - (keyPool + keyIdx))} | 按名解析要逐字符手工比；`key` 读值要重建串（`assertSame` 身份钉必须靠「同 id 同实例」保住） |")
        sb.appendLine("| D metas 列表字段 | ${mib(metasListNow)}（背衬 + 壳；与 byIndex 重复持有一层引用） | 只留 byIndex，`all()` 按需重建 | ${mib(metasListNow)} | `all()` 只走诊断/E2E |")
        sb.appendLine("| E position 面 | 前缀串表 ${mib(prefixNow)}（${group(prefixCount)} 条）+ 每条 posText 引用与 posLine int（都在壳内） | 前缀 id(12 位) + 行号(16 位) 进槽位表，引用腿整条撤 | ${mib(prefixNow - posPacked)}（前缀表换成 id 表；行号进槽位后壳里省 2 字段） | 只走报错面（sourceFileOf） |")
        sb.appendLine("| F nameKeys 记忆表 | ${mib(nameKeysNow)}（${group(nameKeys.size)} 项；键就是 meta.key 同实例 ⇒ 引用不另占） | — （标题页稳态本来就小；长会话上限 = ${mib(hashB(keySet.size))}，见 §2） | 0 | 撤表 ⇒ 每次按名解析重做一次 ICU 大写（ART 热点，见 [com.eratw.port.core.proc.FunctionTable] 注释） |")
        sb.appendLine()
        sb.appendLine("- 投影口径提醒：**B 与 C 互斥地抢同一批字段位** —— key 打包后每条要多 1 个串池引用 + 1 个装载序 int 才守得住身份钉（同 id 同实例），")
        sb.appendLine("  所以「32 B/条 + key 串池」不成立；两者同取时 B 只能做到 40 B/条（收益 ${mib(shellNow - shell40)}），C 的收益要扣掉那 8 B/条。")
        sb.appendLine("- 三条腿基准见 `FuncTableQueryBenchTest`（混合 / nonEvent / byFunctionIndex）；本探针不落热路径读数。")

        println(sb.toString())
        val report = Path.of("build", "mem8-functable-attribution.md").toAbsolutePath().normalize()
        Files.createDirectories(report.parent)
        Files.writeString(report, sb.toString())

        // ---- 断言：装置自证（拆出来的必须等于整桶） ----
        val whole = ResidentWalker(sizer).consume(table)
        assertEquals(metas.size, byIndex.count { it != null }, "byIndex 非空槽必须 = meta 条数（无重复下标）")
        assertEquals(n, metas.size.toLong(), "metas 列表必须全量持有")
        assertTrue(
            kotlin.math.abs(splitTotal - whole) <= 0.002 * whole + 4096,
            "分根合计 ${mib(splitTotal)} 与整桶 ${mib(whole)} 对不上账 ⇒ 有字段没点到名",
        )
        assertTrue(metas.isNotEmpty())
    }

    private fun mib(bytes: Long): String {
        val neg = bytes < 0
        val abs = if (neg) -bytes else bytes
        val tenths = (abs * 10 + 524_288) / 1_048_576
        return "${if (neg) "-" else ""}${tenths / 10}.${tenths % 10}"
    }

    private fun group(n: Number): String {
        val s = n.toLong().toString()
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(s[i])
        }
        return sb.toString()
    }
}
