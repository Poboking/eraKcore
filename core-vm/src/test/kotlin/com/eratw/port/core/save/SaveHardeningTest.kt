package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.SaveFormatException
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 存档 / 角色档的**失败路径**与**上限**验收（审计修复波，分支 `wave/audit-save`）。
 *
 * 四个面，每条断言都对着 C# 行号：
 * 1. `SAVECHARA` 落盘失败不再静默：原版 `new FileStream` 抛异常（VariableEvaluator.cs:2252-2282）、
 *    由 `handleExceptionInSystemProc` 打印错误行（Process.cs:571-592，调用点 :462-476）；
 * 2. 角色档文件名的非法字符按 PC 口径拒绝（`CheckDatFilename` 的字符集，VariableEvaluator.cs:2104-2116，
 *    真正拦住的是 `FileStream`；Android 的写实现会替他建子目录 ⇒ 必须显式拦）；
 * 3. 二进制存档的声明维度与解压长度有上限（原版靠 .NET OOM 兜底，移植侧落成 [SaveFormatException]）；
 * 4. 文本扩展区的 2D / 3D 段按 `emu_version` 消费（EraDataReader.cs:351 / :415 / :438 / :516）。
 */
class SaveHardeningTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 内存 [SaveFileIo]：可单独打开「写失败 / 读失败」两个开关。 */
    private class MemorySaveIo : SaveFileIo {
        val files = LinkedHashMap<String, ByteArray>()
        var failWrites = false
        var failReads = false

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun read(path: String): ByteArray? = if (failReads) null else files[path]?.copyOf()

        override fun write(path: String, bytes: ByteArray): Boolean {
            if (failWrites) return false
            files[path] = bytes.copyOf()
            return true
        }

        override fun delete(path: String): Boolean = files.remove(path) != null
    }

    private fun binary() = SaveConfig(systemSaveInBinary = true, zipSaveData = true)

    private fun store(): VariableStore = VariableStore()

    private fun VariableStore.addHero(name: String = "アリス"): Int {
        registerTemplate(CharacterTemplate(no = 1, name = name, callname = name, maxbase = mapOf(0 to 10L)))
        return addCharacter(1L)
    }

    // ------------------------------------------------------------------
    // 1. SAVECHARA 落盘失败
    // ------------------------------------------------------------------

    /**
     * 写盘返回 false（磁盘满 / 不可写 / 路径被拒）⇒ `CodeEE`，不再是「脚本以为成功」。
     *
     * 原版 `SaveChara` 只包 `try/finally`、不 `catch`（VariableEvaluator.cs:2252-2282），
     * `FileStream` 的 IO 异常冒泡后由 `handleExceptionInSystemProc` 打两行错误
     * （Process.cs:572-587）；移植侧落成 `CodeEE`（TRYC 也接得住）。
     */
    @Test
    fun `SAVECHARA 落盘失败抛 CodeEE`() {
        val io = MemorySaveIo().apply { failWrites = true }
        val s = store()
        s.addHero()
        val e = assertFailsWith<CodeEE> { FileSystemOps(s, io, binary()).saveChara(listOf(0L), "X", "desc") }
        // 文案 = MES_SAVE_CHARA 模板 + 目标路径（合流 origin/main：写失败要能诊断到落点）
        assertTrue(
            e.message!!.startsWith(FileSystemOps.MES_SAVE_CHARA.substringBefore("{0}")),
            "实为 ${e.message}",
        )
        assertTrue(e.message!!.contains("chara_X.dat"), "错误信息应带目标路径：${e.message}")
        assertTrue(io.files.isEmpty(), "失败时不该留下半成品")
    }

    // ------------------------------------------------------------------
    // 2. 角色档文件名清洗
    // ------------------------------------------------------------------

    /**
     * `Path.GetInvalidFileNameChars()` 全集（控制字符 + `" * / : < > ? \ |`）一律拒绝。
     *
     * 原版 `CheckDatFilename` 的返回值被丢弃（:2251），拦住非法名的是紧随的
     * `new FileStream`（:2258）：PC 上含分隔符的路径必然抛异常；本移植的
     * `RootedSaveFileIo.write` 会替父目录建子目录（SaveFileIo.kt:67），若不拦就会
     * 静默写出 `dat/chara_A/B.dat` —— 兼容性要求两边同样失败。
     */
    @Test
    fun `SAVECHARA 非法文件名抛 CodeEE 且不落盘`() {
        for (bad in listOf("A/B", "A\\B", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\u0001b")) {
            val io = MemorySaveIo()
            val s = store()
            s.addHero()
            val e = assertFailsWith<CodeEE>("SAVECHARA \"$bad\" 应被拒绝（VariableEvaluator.cs:2110）") {
                FileSystemOps(s, io, binary()).saveChara(listOf(0L), bad, "d")
            }
            assertEquals(DatFileName.MES_INVALID, e.message, "文案 = Lang.Error.InvalidFileName")
            assertTrue(io.files.isEmpty(), "非法名不该落任何文件（尤其不该建子目录）")
        }
    }

    /**
     * 空名与 `..` **照原版照写**：`CheckDatFilename` 的返回值被丢弃（:2251），
     * 两者拼出来的 `chara_.dat` / `chara_...dat` 都是合法路径，`FileStream` 会成功。
     * 审计建议里的「拒绝空串 / 拒绝 ..」按 C# 原文驳回，这里把口径钉住。
     */
    @Test
    fun `SAVECHARA 空名与点点照写`() {
        val io = MemorySaveIo()
        val s = store()
        s.addHero()
        val fs = FileSystemOps(s, io, binary())
        fs.saveChara(listOf(0L), "", "d")
        assertTrue(io.exists("dat/chara_.dat"), "空名 ⇒ chara_.dat（合法路径，原版照写）")
        fs.saveChara(listOf(0L), "..", "d")
        assertTrue(io.exists("dat/chara_...dat"), "`..` 夹在前缀与扩展名之间，无法越界")
    }

    /**
     * `LOADCHARA` 的两种结局必须分开（原版本就不一样）：
     * - 不存在（含非法路径 —— `File.Exists` 对非法路径只返回 false，:2289-2292）⇒ 静默、
     *   `RESULT` 保持 0（:2288）；
     * - 文件在但读不出来 ⇒ 原版 `new FileStream` 的异常冒泡（:2295-2329）⇒ `CodeEE`。
     */
    @Test
    fun `LOADCHARA 不存在静默 RESULT 0 读失败抛 CodeEE`() {
        val io = MemorySaveIo()
        val s = store()
        val fs = FileSystemOps(s, io, binary())

        fs.loadChara("NONE")
        assertEquals(0L, s.readInt("RESULT", longArrayOf(0L)), "不存在 ⇒ RESULT 保持 0（:2288-2292）")
        assertEquals(0, s.characters.count)

        fs.loadChara("A/B") // 非法名同「不存在」：原版 File.Exists 也是 false
        assertEquals(0L, s.readInt("RESULT", longArrayOf(0L)), "非法名不抛（LOADCHARA 不调 CheckDatFilename）")

        io.files["dat/chara_BROKEN.dat"] = byteArrayOf(1, 2, 3)
        io.failReads = true
        val e = assertFailsWith<CodeEE> { fs.loadChara("BROKEN") }
        assertEquals(FileSystemOps.MES_LOAD_UNEXPECTED, e.message)
    }

    // ------------------------------------------------------------------
    // 3. 二进制存档的声明上限
    // ------------------------------------------------------------------

    /**
     * 坏档声明的维度不得把读档打穿。
     *
     * 旧口径只判元素总数 `< Int.MAX_VALUE`：`20000 × 20000` 是 4 亿元素、乘积没过上界，
     * 但 `LongArray(4e8)` 要 3.2 GB ⇒ OOM。现在按「元素数 × 元素宽」对 [SaveFormat] 的
     * 单条目预算判（64 MiB），并由累计预算兜住「很多条目各要几十 MB」。
     *
     * 原版这几处是 `new long[num, num2]`（EraBinaryDataReader.cs:186 / :305），
     * 大维度由 .NET 的 OOM 兜底；移植侧要可诊断的异常（`FileSystemOps` 转 `CodeEE`）。
     */
    @Test
    fun `坏档声明超大维度抛 SaveFormatException`() {
        val two = binaryFile(normalPayload { entry(2, "DA", 20_000, 20_000) })
        assertTrue(assertFailsWith<SaveFormatException> { SaveFormat.read(two) }.message!!.contains("Int2D"))

        val one = binaryFile(normalPayload { entry(1, "FLAG", 10_000_000) })
        assertTrue(assertFailsWith<SaveFormatException> { SaveFormat.read(one) }.message!!.contains("Int1D"))

        // 100000³ = 1e15：朴素 Long 相乘不溢出但超预算；再大的维度必须「先比后乘」才不会溢出
        val three = binaryFile(normalPayload { entry(3, "TA", 100_000, 100_000, 100_000) })
        assertTrue(assertFailsWith<SaveFormatException> { SaveFormat.read(three) }.message!!.contains("Int3D"))

        val overflow = binaryFile(normalPayload { entry(3, "TA", 100_000_000, 100_000_000, 100_000_000) })
        assertFailsWith<SaveFormatException> { SaveFormat.read(overflow) }
    }

    /** 上限不能误伤真档：eratw 的系统 3D 数组是 (100,100,100)（specs 的 variables.yaml）。 */
    @Test
    fun `合法维度照读`() {
        val bytes = binaryFile(normalPayload {
            entry(2, "DA", 100, 100)
            // 数组体以 EOF 收尾（writeData(long[,])，EraBinaryDataWriter.cs:225-332，EOF 在 :331）；
            // 全 0 不需逐个写值。
            write(0xFF)
        })
        val d = SaveFormat.read(bytes)
        assertEquals(10_000, (d.variables.single() as SaveEntry.IntArray2).values.size)
    }

    /**
     * GZip 解压上限：原版 `GZipStream` 无长度闸（EraBinaryDataReader.cs:807-815），
     * 一个几百 KB 的压缩炸弹即可打穿进程。生产上限是 256 MiB；这里注入 4 KiB 预算
     * 验证「边读边计数、超限即抛」，并验证上限内的解压结果不变。
     */
    @Test
    fun `gzip 解压超上限抛 SaveFormatException`() {
        val raw = ByteArray(64 * 1024)
        val gz = ByteArrayOutputStream().also { bo -> GZIPOutputStream(bo).use { it.write(raw) } }.toByteArray()

        val e = assertFailsWith<SaveFormatException> { SaveFormat.gunzip(gz, 0, maxBytes = 4096) }
        assertTrue(e.message!!.contains("上限"), e.message)
        assertContentEquals(raw, SaveFormat.gunzip(gz, 0, maxBytes = 1L shl 20))
    }

    // ------------------------------------------------------------------
    // 4. 文本扩展区的 emu_version 段闸
    // ------------------------------------------------------------------

    /**
     * 系统 2D 段自 1708、3D 段自 1729 才进存档：
     * `ReadInt64Array2DExtended` / `ReadStringArray2DExtended` 在 `emu_version < 1708` 时
     * **直接返回空字典、不读一行**（EraDataReader.cs:351 / :415），3D 的两个在 `< 1729` 时
     * 同样（:438 / :516）⇒ 旧档里没有这两段，读侧也不能消费它们的分隔符。
     *
     * 反证也在这条用例里：段数比档位少时必须报错（说明闸门真的按档位消费，不是「一律宽容」）。
     */
    @Test
    fun `文本扩展区按 emu_version 消费 2D 3D 段`() {
        val registry = VariableStore().registry
        fun read(marker: String, sysSegs: Int) = SaveTextFormat.read(
            oldNormalText(marker, sysSegs).toByteArray(Charsets.UTF_8),
            registry,
            SaveFileKind.NORMAL,
        )

        assertEquals(1L, read("__EMUERA_STRAT__", 4).uniqueCode, "1700：只有 4 段系统扩展")
        assertEquals(1L, read("__EMUERA_1708_STRAT__", 6).uniqueCode, "1708：2D 在、3D 不在")
        assertEquals(1L, read("__EMUERA_1729_STRAT__", 8).uniqueCode, "1729：8 段齐全")
        assertEquals(1L, read("__EMUERA_1803_STRAT__", 8).uniqueCode)
        // 1808 还要多读 6 段用户扩展（VariableData.cs:3698-3756）⇒ 8 + 6 = 14 段
        assertEquals(1L, read(SaveTextFormat.EMU_START, 14).uniqueCode)

        assertFailsWith<SaveFormatException>("1700 档第 4 段之后就没有了，少于 4 段必须报错") {
            read("__EMUERA_STRAT__", 3)
        }
        assertFailsWith<SaveFormatException>("1708 档的 2D 段必须存在") {
            read("__EMUERA_1708_STRAT__", 4)
        }
        assertFailsWith<SaveFormatException>("1729 档的 3D 段必须存在") {
            read("__EMUERA_1729_STRAT__", 6)
        }

        // 尾随多出来的段不消费也不报错（原版读完最后一个 Read 就返回，:3630-3637）
        assertEquals("old", read("__EMUERA_STRAT__", 8).text)
    }

    // ------------------------------------------------------------------
    // 装置：手拼存档字节
    // ------------------------------------------------------------------

    /** 按原版文本布局拼一份普通档：4 行头 + 61 行空数组 + 标记行 + [sysSegs] 个空段。 */
    private fun oldNormalText(marker: String, sysSegs: Int): String {
        val sb = StringBuilder()
        fun line(s: String) = sb.append(s).append('\n')
        line("1") // UniqueCode
        line("0") // ScriptVersion
        line("old") // saveDataText
        line("0") // 角色数
        repeat(61) { line(SaveTextFormat.FINISHED) } // 60 Int1D + 1 Str1D（VariableData.cs:3516-3534）
        line(marker)
        repeat(sysSegs) { line(SaveTextFormat.SEPARATOR) }
        return sb.toString()
    }

    /** 16 字节容器头（未压缩分支）+ payload。 */
    private fun binaryFile(payload: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        le64(o, SaveFormat.MAGIC_PLAIN)
        le32(o, SaveFormat.VERSION)
        le32(o, 0) // 校验表条数恒 0（EraBinaryDataWriter.cs:46）
        o.write(payload)
        return o.toByteArray()
    }

    /** Normal 档 payload 的固定前缀：类别、UniqueCode、ScriptVersion、空描述、0 个角色。 */
    private fun normalPayload(body: ByteArrayOutputStream.() -> Unit): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0) // FileType = Normal
        le64(o, 0L) // UniqueCode
        le64(o, 0L) // ScriptVersion
        o.write(0) // saveDataText = ""（7-bit 长度前缀 0）
        le64(o, 0L) // 角色数
        o.body()
        o.write(0xFF) // 变量段 EOF
        return o.toByteArray()
    }

    /**
     * 一个数组条目的**声明头**（EraBinaryDataWriter.cs:83-173）：类型字节 + 键名 + 各维长度。
     * 元素正文故意不写 —— 预算校验发生在分配前，读到声明就该抛。
     */
    private fun ByteArrayOutputStream.entry(tag: Int, key: String, vararg dims: Int) {
        write(tag)
        str(key)
        for (d in dims) le32(this, d)
    }

    /** `BinaryWriter.Write(string)` 的 7-bit 变长字节长度 + UTF-16LE 码元。 */
    private fun ByteArrayOutputStream.str(s: String) {
        var n = s.length * 2
        while (true) {
            val x = n and 0x7F
            n = n ushr 7
            if (n != 0) write(x or 0x80) else {
                write(x)
                break
            }
        }
        for (ch in s) {
            write(ch.code and 0xFF)
            write((ch.code ushr 8) and 0xFF)
        }
    }

    private fun le32(o: ByteArrayOutputStream, v: Int) {
        for (i in 0 until 4) o.write((v ushr (8 * i)) and 0xFF)
    }

    private fun le64(o: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 8) o.write(((v ushr (8 * i)) and 0xFF).toInt())
    }
}
