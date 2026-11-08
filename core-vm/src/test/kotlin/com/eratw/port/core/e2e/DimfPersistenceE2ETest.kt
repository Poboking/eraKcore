package com.eratw.port.core.e2e

import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveFileIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `#DIMF` 浮点变量**存档通道**的端到端验收（worker f4，独立验收，不实现）。
 *
 * 链：ERB 片段写浮点（标量 + 1 维数组）→ [FileSystemOps.saveData] 落盘 → 另建变量池
 * [FileSystemOps.loadData] 读回 → 第二段 ERB 片段按名读浮点、`TOINT(值 * 100)` 见证 →
 * 逐值断言。
 *
 * 为什么这样取证：合并前 [com.eratw.port.core.vars.VariableStore] 还没有公开的浮点读 API，
 * 而验收又必须在基线上**编译通过**。于是把「读回值」折成整型见证，走的是真实玩家路径
 * （表达式读浮点变量 → TOINT），f1（运行期浮点存储）/ f2、f3（#DIMF 存档通道、编译器侧车）
 * 合并后应自然转绿；基线红则如实上报归属。
 *
 * 存档口径取 [SaveConfig.ERATW]（二进制 + 压缩 + `sav/` 目录），与真机 emuera.config 三行
 * 同值；唯一浮点类型标记来源见 `specs/variables.yaml` 的 save_note_extended。
 */
class DimfPersistenceE2ETest {

    private val writeLines = ErbSnippetHarness.lines("dimf_write.erb")
    private val verifyLines = ErbSnippetHarness.lines("dimf_verify.erb")

    /** 全局声明走 ERH 口径（ERB 内的 `#DIMF` 恒为私有），写/读两侧共用同一份。 */
    private val decls = ErbSnippetHarness.declarations(ErbSnippetHarness.lines("dimf_decl.erh"))

    /** 内存存档设备：路径键与生产实现同为相对路径（`sav/save01.sav`）。 */
    private class MemorySaveIo : SaveFileIo {
        val files = LinkedHashMap<String, ByteArray>()

        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun read(path: String): ByteArray? = files[path]?.copyOf()
        override fun write(path: String, bytes: ByteArray): Boolean {
            files[path] = bytes.copyOf()
            return true
        }
        override fun delete(path: String): Boolean {
            files.remove(path)
            return true
        }
    }

    @Test
    fun `DIMF 浮点标量与1D数组 存档读档逐值相等`() {
        val io = MemorySaveIo()
        val cfg = SaveConfig.ERATW

        // 1) 写侧：声明 + 写入（真 ProcessRuntime 执行片段）
        val src = ErbSnippetHarness.buildStore(decls)
        ErbSnippetHarness.run(writeLines, entry = "WRITE", store = src, decls = decls, registerNew = false)

        // 2) 存档
        assertTrue(FileSystemOps(src, io, cfg).saveData(1, "DIMF-E2E"), "SAVEDATA 应成功")
        assertTrue(io.files.isNotEmpty(), "存档设备里应至少落一份文件，实际 ${io.files.keys}")

        // 3) 读档到**另一个**变量池（同一声明面）
        val dst = ErbSnippetHarness.buildStore(decls)
        FileSystemOps(dst, io, cfg).loadData(1)

        // 4) 读回见证：第二段片段读浮点变量并折成整型
        ErbSnippetHarness.run(verifyLines, entry = "VERIFY", store = dst, decls = decls, registerNew = false)

        assertEquals(125L, dst.readInt("A", longArrayOf(0)), "标量 倍率 读回应为 1.25（乘 100）")
        assertEquals(250L, dst.readInt("B", longArrayOf(0)), "数组 样本:0 读回应为 2.5（乘 100）")
        assertEquals(-750L, dst.readInt("C", longArrayOf(0)), "数组 样本:3 读回应为 -7.5（乘 100）")
        assertEquals(1275L, dst.readInt("D", longArrayOf(0)), "数组 样本:4 读回应为 12.75（乘 100）")
    }
}
