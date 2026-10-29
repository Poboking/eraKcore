package com.eratw.port.perf

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.EngineFunctionPolicy
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.StubTable
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **mem5 定靶/验收探针**：真产物侧车 `FunctionTable` 的逐字段常驻拆解 +
 * 刀 1 的「屏蔽装配不物化」硬闸（只测量 + 断言，`:core-vm` 测试源装置）。
 *
 * 复现（与 mem3 同口径的产物目录）：
 * ```
 * ERATW_ERBC_DIR=<erbc.v1 目录> \
 * ./gradlew --configure-on-demand :core-vm:test --tests "*FuncTableLayoutProbe*" -i
 * ```
 *
 * 报告落 `core-vm/build/mem5-functable-layout.md`。尺寸口径与 mem3 完全同源
 * （[SizeModel]：对象头 12B、压缩引用 4B、String = align8(32+payload)、
 * HashMap 表容量按 0.75 负载反推、节点 32B）。改造前的读数存在
 * `baseline-mem5.log`（main@57148c63：桶合计 35.6，其中 position 串 16.9、
 * nonEventByKey 4.4、FuncMeta 5.9、key 7.4）。
 */
class FuncTableLayoutProbeTest {

    private val sizer = SizeModel(SizeModel.detectRefWidth())

    private fun findErbcDir(): Path? {
        System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return if (Files.isRegularFile(Path.of(it).resolve("program.erbc"))) Path.of(it) else null }
        System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return Path.of(it).parent }
        return null
    }

    @Test
    fun `真产物：FunctionTable 桶逐字段拆解 + 屏蔽装配零物化闸`() {
        val dir = findErbcDir()
        assumeTrue(dir != null && Files.isRegularFile(dir.resolve("program.erbc")), "无 ERATW_ERBC_DIR 产物：跳过")
        val programFile = dir!!.resolve("program.erbc")
        val metaFile = dir.resolve("program.meta")
        assumeTrue(Files.isRegularFile(metaFile), "无 program.meta：跳过")

        // ---- 刀 1 硬闸：装载后 0 物化；屏蔽表装配后仍 0 物化 ----
        val program = ProgramLoader.loadMapped(programFile)
        assertEquals(0, program.materializedFunctionCount, "装载完必须是 0 物化")
        val stub = StubTable.of(program, EngineFunctionPolicy.DEFAULT_DISABLED)
        assertEquals(
            0, program.materializedFunctionCount,
            "StubTable 装配只许走只要名字取用面（物化计数 = ${program.materializedFunctionCount}）",
        )
        assertTrue(stub.names.all { it.isNotEmpty() }, "命中名必须非空（原名值）")
        println("[mem5] 真产物屏蔽命中：${stub.names}")

        // ---- 刀 2 拆解：与改造前同一套分项 ----
        val metadata = BootMetadata.read(metaFile)
        val metas: List<FuncMeta> = metadata.functions
        // 压实前抓 position 原文快照（逐条 String，本装置内临时持有）。
        val rawPositions = metas.map { it.position }
        val table = FunctionTable(metas)

        // 压实无损：读值必须与原文逐字节相同（有前置零 / 无冒号 / 标记串保持原文）。
        var packed = 0
        for (i in metas.indices) {
            assertEquals(rawPositions[i], metas[i].position, "position 读值漂移 @#${i} ${metas[i].key}")
            if (packedSizeOf(metas[i])) packed++
        }

        val seen = IdentityHashMap<Any, Boolean>()
        fun strBytes(s: String?): Long {
            if (s == null) return 0L
            if (seen.put(s, true) != null) return 0L
            return SizeModel.stringBytes(s)
        }

        var keyBytes = 0L
        var nameBytes = 0L
        var posTextBytes = 0L
        val posTextDistinct = HashSet<String>(8192)
        for (m in metas) {
            keyBytes += strBytes(m.key)
            nameBytes += strBytes(m.name)
            posTextBytes += strBytes(fieldOf(m, "posText") as String?)
            (fieldOf(m, "posText") as String?)?.let { posTextDistinct.add(it) }
        }
        val funcMetaClassBytes = sizer.shallowOf(FuncMeta::class.java) * metas.size.toLong()

        fun priv(name: String): Any? {
            val f = FunctionTable::class.java.getDeclaredField(name)
            f.isAccessible = true
            return f.get(table)
        }

        fun hashMapBytes(m: Map<*, *>): Long {
            val n = m.size
            val base = sizer.objectSize(m)
            if (n == 0) return base
            val need = (n * 4 + 2) / 3
            var cap = 16
            while (cap < need && cap < (1 shl 30)) cap = cap shl 1
            return base + 16 + 4L * cap + 32L * n
        }

        val nonEventByKey = priv("nonEventByKey") as Map<*, *>
        val eventMembers = priv("eventMembersByKey") as Map<*, *>
        val eventMember = priv("eventMemberByKey") as Map<*, *>
        val nameKeys = priv("nameKeys") as Map<*, *>
        val byIndex = priv("byIndex") as Array<*>
        val byIndexBytes = 16L + 4L * byIndex.size

        // MEM8 刀 D：下标序稠密时装载序面直接用 byIndex 的只读视图，不再另持一条列表背衬
        // ⇒ 这一行归零；判据不成立时 orderedMetas 非空，按原口径计入。
        val orderedMetas = priv("orderedMetas")
        val metasListBytes = if (orderedMetas == null) 0L
        else sizer.objectSize(orderedMetas as Any) + 16L + 4L * metas.size
        val nonEventMapBytes = hashMapBytes(nonEventByKey)
        val mapsBytes = nonEventMapBytes + hashMapBytes(eventMembers) +
            hashMapBytes(eventMember) + hashMapBytes(nameKeys)
        val total = keyBytes + nameBytes + posTextBytes + funcMetaClassBytes +
            mapsBytes + byIndexBytes + metasListBytes
        val miB = { b: Long -> "%.1f".format(b / 1048576.0) }

        val sb = StringBuilder()
        sb.appendLine("# mem5 FunctionTable 桶逐字段拆解（真产物侧车 metas=${metas.size}，刀 1+刀 2 落地后）")
        sb.appendLine()
        sb.appendLine("| 分项 | 条数/容量 | MiB | 改造前（main@57148c63 实测） |")
        sb.appendLine("|---|---|---:|---|")
        sb.appendLine("| FuncMeta 对象浅尺寸 | ${metas.size} × ${sizer.shallowOf(FuncMeta::class.java)} B | ${miB(funcMetaClassBytes)} | 5.9（56 B/条） |")
        sb.appendLine("| key 串（身份去重） | ${metas.size} 条 | ${miB(keyBytes)} | 7.4 |")
        sb.appendLine("| name 串（身份去重） | | ${miB(nameBytes)} | 0.2 |")
        sb.appendLine("| position 存放体（压实=共享前缀串 / 未压实=原文） | 去重实例 ${posTextDistinct.size} | ${miB(posTextBytes)} | 16.9（110,072 条独立原文串） |")
        sb.appendLine("| nonEventByKey（表+Node，保持原形态） | ${nonEventByKey.size} 项 | ${miB(nonEventMapBytes)} | 4.4 |")
        sb.appendLine("| 事件两张小表 + nameKeys | ${eventMembers.size}+${eventMember.size}+${nameKeys.size} | ${miB(mapsBytes - nonEventMapBytes)} | ~0 |")
        sb.appendLine("| byIndex 引用数组 | ${byIndex.size} 槽 | ${miB(byIndexBytes)} | 0.4 |")
        sb.appendLine("| metas 列表壳 | ${if (orderedMetas == null) 0 else metas.size} | ${miB(metasListBytes)} | 0.4（MEM8 刀 D：稠密下标序时改由 byIndex 视图代役） |")
        sb.appendLine("| **拆解合计** | | **${miB(total)}** | 35.6 |")
        sb.appendLine()
        sb.appendLine("- 压实条数=$packed / ${metas.size}；StubTable 装配物化计数=0（见上闸）。")
        sb.appendLine("- 读法与 mem3 一致：本表是**建模拆解**，与 HeapMeter 稳态差 ±10 MiB 级。")
        println(sb.toString())
        val report = Path.of("build", "mem5-functable-layout.md").toAbsolutePath().normalize()
        Files.createDirectories(report.parent)
        Files.writeString(report, sb.toString())
        assertTrue(metas.isNotEmpty(), "侧车必须有函数元数据")
    }

    private fun packedSizeOf(m: FuncMeta): Boolean {
        // MEM8 刀 B 之后 flags 不再是独立字段（并进 w0 高位），改用公开读面。
        return m.positionPacked
    }

    private fun fieldOf(target: Any, name: String): Any? {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target)
    }
}
