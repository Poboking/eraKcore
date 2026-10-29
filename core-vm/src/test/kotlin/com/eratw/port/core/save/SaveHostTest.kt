package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.fn.BuiltinRegistry
import com.eratw.port.core.fn.registerSaveMethods
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M6-SAVE-HOST 的落盘验收：`FileSystemOps` + [SaveCapture] + [SaveTextFormat] 的端到端往返。
 *
 * 覆盖（合同 §2.2 判据 ②）：
 * 空存档、全类型（系统/用户/角色/多维/字符串）、二进制开关两态、全角 + 增补平面字符串、
 * 损坏与截断、读不存在文件、DELDATA、SAVECHARA/LOADCHARA、`registerSaveMethods` 接线。
 *
 * 期望值全部来自 C# 行号（KDoc 在各用例里给出）或本层抓取的对称性，不用实现算出来的值
 * 当「原版读数」。
 */
class SaveHostTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 内存 [SaveFileIo]：路径键与生产实现同为相对路径。 */
    private class MemorySaveIo : SaveFileIo {
        val files = LinkedHashMap<String, ByteArray>()
        val readOnly = HashSet<String>()
        var failWrites = false

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun read(path: String): ByteArray? = files[path]?.copyOf()

        override fun write(path: String, bytes: ByteArray): Boolean {
            if (failWrites) return false
            files[path] = bytes.copyOf()
            return true
        }

        override fun delete(path: String): Boolean {
            files.remove(path)
            return true
        }

        override fun isReadOnly(path: String): Boolean = path in readOnly
    }

    private fun binary() = SaveConfig(systemSaveInBinary = true, zipSaveData = true)
    private fun plain() = SaveConfig(systemSaveInBinary = false)

    private fun dim(body: String, binary: Boolean = false) =
        DimParser.parse("#DIM", body, isPrivate = false, DimParseConfig(systemSaveInBinary = binary))

    private fun dims(body: String, binary: Boolean = false) =
        DimParser.parse("#DIMS", body, isPrivate = false, DimParseConfig(systemSaveInBinary = binary))

    private fun store(): VariableStore = VariableStore()

    private fun VariableStore.addHero(name: String = "アリス"): Int {
        registerTemplate(CharacterTemplate(no = 1, name = name, callname = name, maxbase = mapOf(0 to 10L)))
        return addCharacter(1L)
    }

    private fun idx(vararg v: Long): LongArray = v

    // ------------------------------------------------------------------
    // 1. 空存档
    // ------------------------------------------------------------------

    /** 无角色、全默认值的存档也要能写能读：原版 `SaveTo` 不因空角色失败（VariableEvaluator.cs:2644-2685）。 */
    @Test
    fun `空存档二进制往返`() {
        val io = MemorySaveIo()
        val s = store()
        val fs = FileSystemOps(s, io, binary())

        assertTrue(fs.saveData(0, "empty"), "SAVEDATA 应返回 true")
        assertTrue(io.exists("save00.sav"), "默认 SavDir = ExeDir 根（UseSaveFolder=false，Config.cs:470-481）")

        fs.loadData(0)
        assertEquals(0, s.characters.count)
        assertEquals("empty", s.lastLoadText)
        assertEquals(0L, s.lastLoadNo)
    }

    // ------------------------------------------------------------------
    // 2. 全类型往返（二进制 = eratw 实机路径）
    // ------------------------------------------------------------------

    /**
     * 系统 1D/2D/3D、角色 CFLAG/CSTR/NAME/NICKNAME、用户 #DIM 1D/2D/3D × INT/STR、
     * GLOBAL 与 CHARADATA 用户变量一次全测。
     *
     * 筛选口径：VariableData.cs:3980-3997（`IsSavedata && !IsCharacterData && !IsGlobal`）、
     * CharacterData.cs:513-558（角色块 + Separator 后的用户角色变量）、
     * VariableData.cs:3961-3978（全局档）。
     */
    @Test
    fun `全类型二进制往返`() {
        val io = MemorySaveIo()
        val s = store()
        s.declareUserVar(dim("SAVEDATA UI1, 3", binary = true))
        s.declareUserVar(dims("SAVEDATA US1, 3", binary = true))
        s.declareUserVar(dim("SAVEDATA UI2, 2, 2", binary = true))
        s.declareUserVar(dims("SAVEDATA US2, 2, 2", binary = true))
        s.declareUserVar(dim("GLOBAL SAVEDATA GUI, 2", binary = true))
        s.declareUserVar(dim("CHARADATA SAVEDATA CUI, 2", binary = true))
        s.declareUserVar(dims("CHARADATA SAVEDATA CUS, 2", binary = true))
        s.addHero()
        s.addPseudoCharacter()

        // 系统变量
        s.writeInt("DAY", idx(0), 12)
        s.writeInt("MONEY", idx(7), 999)
        s.writeInt("A", idx(1), -5)
        s.writeInt("TARGET", idx(0), 1)
        // 角色变量（角色 0）
        s.writeInt("CFLAG", idx(0, 3), 7)
        s.writeInt("BASE", idx(0, 0), 42)
        s.writeStr("CSTR", idx(0, 1), "全角　文字")
        s.writeStr("NICKNAME", idx(0), "ニック")
        s.writeInt("CDFLAG", idx(0, 0, 0), 1)
        // 用户变量
        s.writeInt("UI1", idx(2), 33)
        s.writeStr("US1", idx(1), "𠮟る")
        s.writeInt("UI2", idx(1, 1), 44)
        s.writeStr("US2", idx(0, 1), "s2")
        s.writeInt("GUI", idx(1), 55)
        s.writeInt("CUI", idx(0, 1), 66)
        s.writeStr("CUS", idx(0, 0), "cu")

        val fs = FileSystemOps(s, io, binary())
        assertTrue(fs.saveData(3, "セーブ"))
        assertTrue(io.exists("save03.sav"))

        // 打乱现场（含删角色），再读回
        s.writeInt("DAY", idx(0), 0)
        s.writeStr("US1", idx(1), "")
        s.writeInt("CUI", idx(0, 1), 0)
        s.deleteAllCharacters()

        fs.loadData(3)

        assertEquals(12L, s.readInt("DAY", idx(0)))
        assertEquals(999L, s.readInt("MONEY", idx(7)))
        assertEquals(-5L, s.readInt("A", idx(1)))
        assertEquals(2, s.characters.count, "角色数应恢复（NORMAL 装载重建 CharacterList，VariableEvaluator.cs:2629-2636）")
        assertEquals(7L, s.readInt("CFLAG", idx(0, 3)))
        assertEquals(42L, s.readInt("BASE", idx(0, 0)))
        assertEquals("全角　文字", s.readStr("CSTR", idx(0, 1)))
        assertEquals("ニック", s.readStr("NICKNAME", idx(0)))
        assertEquals(1L, s.readInt("CDFLAG", idx(0, 0, 0)))
        assertEquals(33L, s.readInt("UI1", idx(2)))
        assertEquals("𠮟る", s.readStr("US1", idx(1)))
        assertEquals(44L, s.readInt("UI2", idx(1, 1)))
        assertEquals("s2", s.readStr("US2", idx(0, 1)))
        assertEquals(66L, s.readInt("CUI", idx(0, 1)))
        assertEquals("cu", s.readStr("CUS", idx(0, 0)))
        assertEquals("セーブ", s.lastLoadText)
        assertEquals(3L, s.lastLoadNo)
    }

    // ------------------------------------------------------------------
    // 3. 二进制开关两态
    // ------------------------------------------------------------------

    /**
     * `SystemSaveInBinary` 两态：二进制分支写 GZip 魔数容器（EraBinaryDataWriter.cs:35-51），
     * 文本分支写 UTF-8 BOM + `__EMUERA_1808_STRAT__`（EraDataWriter.cs:13/21-25）。
     * 两态都必须 save→load→save 字节稳定。
     */
    @Test
    fun `二进制与文本两态各自字节稳定`() {
        for (config in listOf(binary(), plain())) {
            val io = MemorySaveIo()
            val s = store()
            s.addHero()
            s.writeInt("DAY", idx(0), 5)
            s.writeStr("SAVESTR", idx(0), "セーブ文字")
            s.declareUserVar(dim("SAVEDATA UI, 2"))
            s.writeInt("UI", idx(1), 9)
            val fs = FileSystemOps(s, io, config)

            assertTrue(fs.saveData(1, "desc"))
            val first = io.files.getValue("save01.sav")
            if (config.systemSaveInBinary) {
                assertEquals(SaveFormat.MAGIC_GZIP, readU64(first, 0), "二进制 + ZipSaveData 应命中 GZip 魔数")
            } else {
                assertEquals(0xEF, first[0].toInt() and 0xFF, "文本格式带 UTF-8 BOM")
                assertTrue(String(first, Charsets.UTF_8).contains("__EMUERA_1808_STRAT__"))
            }

            fs.loadData(1)
            assertEquals(5L, s.readInt("DAY", idx(0)))
            assertTrue(fs.saveData(1, "desc"))
            assertTrue(first.contentEquals(io.files.getValue("save01.sav")), "同状态重写应逐字节相同")
        }
    }

    /** 文本格式的 2D/3D 整数扩展段与系统扩展段（RANDDATA/TSTR/CDFLAG）都要能读回。 */
    @Test
    fun `文本格式扩展段往返`() {
        val io = MemorySaveIo()
        val s = store()
        s.addHero()
        s.declareUserVar(dim("SAVEDATA UI2, 2, 2"))
        s.declareUserVar(dim("SAVEDATA UI3, 2, 2, 2"))
        s.writeInt("UI2", idx(1, 1), 7)
        s.writeInt("UI3", idx(1, 0, 1), 8)
        s.writeInt("RANDDATA", idx(0), 123)
        s.writeStr("TSTR", idx(0), "tstr")
        s.writeInt("CFLAG", idx(0, 5), 55)
        s.writeStr("MASTERNAME", idx(0), "主人")

        val fs = FileSystemOps(s, io, plain())
        assertTrue(fs.saveData(2, "text"))

        s.writeInt("UI2", idx(1, 1), 0)
        s.writeInt("UI3", idx(1, 0, 1), 0)
        s.writeInt("RANDDATA", idx(0), 0)
        s.writeStr("TSTR", idx(0), "")
        s.writeStr("MASTERNAME", idx(0), "")

        fs.loadData(2)
        assertEquals(7L, s.readInt("UI2", idx(1, 1)))
        assertEquals(8L, s.readInt("UI3", idx(1, 0, 1)))
        assertEquals(123L, s.readInt("RANDDATA", idx(0)))
        assertEquals("tstr", s.readStr("TSTR", idx(0)))
        assertEquals(55L, s.readInt("CFLAG", idx(0, 5)))
        assertEquals("主人", s.readStr("MASTERNAME", idx(0)))
    }

    // ------------------------------------------------------------------
    // 4. 坏档 / 缺档
    // ------------------------------------------------------------------

    /** 截断：`CheckData` 抛错 → `LOADDATA` 抛 LoadCorruptedData（Process.cs:1853-1856）。 */
    @Test
    fun `截断存档读档失败`() {
        for (config in listOf(binary(), plain())) {
            val io = MemorySaveIo()
            val s = store()
            val fs = FileSystemOps(s, io, config)
            assertTrue(fs.saveData(4, "x"))
            val good = io.files.getValue("save04.sav")
            io.files["save04.sav"] = good.copyOf(good.size / 2)
            assertFailsWith<CodeEE> { fs.loadData(4) }
        }
    }

    /** 完全不是存档的字节同样失败（不静默当空档）。 */
    @Test
    fun `垃圾字节读档失败`() {
        val io = MemorySaveIo()
        val fs = FileSystemOps(store(), io, binary())
        io.files["save01.sav"] = "not a save".toByteArray()
        assertFailsWith<CodeEE> { fs.loadData(1) }
    }

    /** 读不存在的文件：`LOADDATA` 抛错、`LOADGLOBAL` 返回 false、`DELDATA` no-op。 */
    @Test
    fun `读不存在文件`() {
        val io = MemorySaveIo()
        val fs = FileSystemOps(store(), io, binary())
        assertFailsWith<CodeEE> { fs.loadData(9) }
        assertFalse(fs.loadGlobal())
        fs.delData(9) // 不抛
    }

    /** 全局档损坏 → `LOADGLOBAL` 返回 false（VariableEvaluator.cs:2570-2573 catch-all）。 */
    @Test
    fun `损坏全局档返回 false`() {
        val io = MemorySaveIo()
        val fs = FileSystemOps(store(), io, binary())
        io.files["global.sav"] = byteArrayOf(1, 2, 3)
        assertFalse(fs.loadGlobal())
    }

    // ------------------------------------------------------------------
    // 5. 全局档
    // ------------------------------------------------------------------

    /**
     * `SAVEGLOBAL` / `LOADGLOBAL`：GLOBAL(槽 63)/GLOBALS(槽 3) + 用户 `#DIM GLOBAL SAVEDATA`。
     * `LOADGLOBAL` 不碰 LASTLOAD_*（全局装载没有那两行赋值，VariableEvaluator.cs:2513-2589）。
     */
    @Test
    fun `全局档二进制与文本往返且不动 LASTLOAD`() {
        for (config in listOf(binary(), plain())) {
            val io = MemorySaveIo()
            val s = store()
            s.declareUserVar(dim("GLOBAL SAVEDATA GUI, 2"))
            s.writeInt("GLOBAL", idx(3), 77)
            s.writeStr("GLOBALS", idx(2), "全局𠮟")
            s.writeInt("GUI", idx(1), 88)
            s.lastLoadVersion = 123
            s.lastLoadText = "keep"

            val fs = FileSystemOps(s, io, config)
            fs.saveGlobal()
            assertTrue(io.exists("global.sav"))

            s.writeInt("GLOBAL", idx(3), 0)
            s.writeStr("GLOBALS", idx(2), "")
            s.writeInt("GUI", idx(1), 0)

            assertTrue(fs.loadGlobal(), "LOADGLOBAL 应成功")
            assertEquals(77L, s.readInt("GLOBAL", idx(3)))
            assertEquals("全局𠮟", s.readStr("GLOBALS", idx(2)))
            assertEquals(88L, s.readInt("GUI", idx(1)))
            assertEquals(123L, s.lastLoadVersion, "全局装载不动 LASTLOAD_VERSION")
            assertEquals("keep", s.lastLoadText, "全局装载不动 LASTLOAD_TEXT")
        }
    }

    /** eratw 实机口径（`UseSaveFolder`）落 `sav/global.sav`（Config.cs:470-481）。 */
    @Test
    fun `UseSaveFolder 落 sav 子目录`() {
        val io = MemorySaveIo()
        val s = store()
        val fs = FileSystemOps(s, io, SaveConfig.ERATW)
        fs.saveGlobal()
        assertTrue(io.exists("sav/global.sav"))
        assertTrue(fs.saveData(0, "x"))
        assertTrue(io.exists("sav/save00.sav"))
    }

    /**
     * `SAVEGLOBAL`：C# 的 try 从 `CreateSavDir` / `FileStream` 就开始，抓取
     * （`SaveGlobalToStream`）也在 `catch (SystemException)` 里
     * （VariableEvaluator.cs:2476-2509）⇒ 采集期异常必须收敛成
     * `CodeEE(ErrorSavingGlobalData)`，不能原样冒泡。
     */
    @Test
    fun `SAVEGLOBAL 采集异常收敛为全局保存错误`() {
        val io = MemorySaveIo()
        val s = store()
        // 把 map 池槽塞成错误类型：SaveCapture 的 EM 导出在采集期抛 ClassCastException
        s.aux.slot("map") { Any() }
        val e = assertFailsWith<CodeEE> { FileSystemOps(s, io, binary()).saveGlobal() }
        assertEquals(FileSystemOps.MES_SAVE_GLOBAL, e.message)
    }

    /**
     * LOADGLOBAL 的 needInit 尾段清零：原版已知变量一律 `needInit: true` 读入
     * （VariableData.cs:4145-4206），声明长度 ≥ 文件长度时文件内容之后的尾段清零/置 null
     * （EraBinaryDataReader.cs:161-166 一维、:270-277 二维）。
     * LOADGLOBAL 前没有 SetDefaultValue（VariableEvaluator.cs:2513-2589），
     * 所以装载前的旧值是否被清掉在这里可观测。
     */
    @Test
    fun `LOADGLOBAL needInit 尾段清零`() {
        val io = MemorySaveIo()
        val writer = store()
        writer.declareUserVar(dim("GLOBAL SAVEDATA GUI, 4"))
        writer.declareUserVar(dim("GLOBAL SAVEDATA G2, 2, 2"))
        for (i in 0 until 4) writer.writeInt("GUI", idx(i.toLong()), (i + 1).toLong())
        writer.writeInt("G2", idx(0, 0), 11)
        writer.writeInt("G2", idx(1, 1), 22)
        FileSystemOps(writer, io, binary()).saveGlobal()

        val s = store()
        s.declareUserVar(dim("GLOBAL SAVEDATA GUI, 8"))
        s.declareUserVar(dim("GLOBAL SAVEDATA G2, 3, 3"))
        for (i in 0 until 8) s.writeInt("GUI", idx(i.toLong()), 77)
        for (i in 0 until 3) for (j in 0 until 3) s.writeInt("G2", idx(i.toLong(), j.toLong()), 99)

        assertTrue(FileSystemOps(s, io, binary()).loadGlobal())
        for (i in 0 until 4) assertEquals((i + 1).toLong(), s.readInt("GUI", idx(i.toLong())))
        for (i in 4 until 8) {
            assertEquals(0L, s.readInt("GUI", idx(i.toLong())), "GUI[$i] 应被 needInit 清零（EraBinaryDataReader.cs:161-166）")
        }
        assertEquals(11L, s.readInt("G2", idx(0, 0)))
        assertEquals(22L, s.readInt("G2", idx(1, 1)))
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                if ((i == 0 && j == 0) || (i == 1 && j == 1)) continue
                assertEquals(0L, s.readInt("G2", idx(i.toLong(), j.toLong())), "G2[$i,$j] 应被 needInit 清零（:270-277）")
            }
        }
    }

    /**
     * 声明某维**小于**文件：原版走临时数组、只把 min 区域拷回，其余保留旧值
     * （EraBinaryDataReader.cs:114-118 临时数组、:172-173 / :280-291 拷回 min 区域），
     * 不做 needInit 清零。移植侧的清零必须以「各维 store ≥ 各维 file」为前提。
     */
    @Test
    fun `LOADGLOBAL 声明更小时保留旧值不清零`() {
        val io = MemorySaveIo()
        val writer = store()
        writer.declareUserVar(dim("GLOBAL SAVEDATA G2, 2, 3"))
        var v = 1L
        for (i in 0 until 2) {
            for (j in 0 until 3) {
                writer.writeInt("G2", idx(i.toLong(), j.toLong()), v)
                v++
            }
        }
        FileSystemOps(writer, io, binary()).saveGlobal()

        val s = store()
        s.declareUserVar(dim("GLOBAL SAVEDATA G2, 3, 2")) // 第 1 维 2 < 文件 3 → 临时数组分支
        for (i in 0 until 3) {
            for (j in 0 until 2) s.writeInt("G2", idx(i.toLong(), j.toLong()), -7)
        }

        assertTrue(FileSystemOps(s, io, binary()).loadGlobal())
        // min 区域（2×2）拷回文件值（文件行主序 2×3：值 = i*3+j+1）
        for (i in 0 until 2) {
            for (j in 0 until 2) {
                assertEquals(
                    (i * 3 + j + 1).toLong(), s.readInt("G2", idx(i.toLong(), j.toLong())),
                    "G2[$i,$j] 应为文件值",
                )
            }
        }
        // min 区域之外（第 2 行）保留 -7，不清零
        for (j in 0 until 2) {
            assertEquals(
                -7L, s.readInt("G2", idx(2, j.toLong())),
                "G2[2,$j] 旧值应保留（:280-291 只拷回 min 区域）",
            )
        }
    }

    // ------------------------------------------------------------------
    // 6. DELDATA
    // ------------------------------------------------------------------

    @Test
    fun `DELDATA 删除与只读拒绝`() {
        val io = MemorySaveIo()
        val s = store()
        val fs = FileSystemOps(s, io, binary())
        assertTrue(fs.saveData(5, "x"))
        assertTrue(io.exists("save05.sav"))
        fs.delData(5)
        assertFalse(io.exists("save05.sav"))
        fs.delData(5) // 再删不抛

        assertTrue(fs.saveData(6, "y"))
        io.readOnly.add("save06.sav")
        assertFailsWith<CodeEE> { fs.delData(6) }
        assertTrue(io.exists("save06.sav"))
    }

    // ------------------------------------------------------------------
    // 7. 角色档（恒二进制）
    // ------------------------------------------------------------------

    /**
     * `SAVECHARA` / `LOADCHARA`：文件名 `chara_{filename}.dat` 且**会多一层 `.dat`**
     * （getSaveDataPathC，VariableEvaluator.cs:2054-2057；`CheckDatFilename` 返回值被丢弃）。
     */
    @Test
    fun `角色档往返`() {
        val io = MemorySaveIo()
        val s = store()
        s.addHero()
        s.writeInt("CFLAG", idx(0, 2), 31)
        s.writeStr("NAME", idx(0), "角色𠮟")
        val fs = FileSystemOps(s, io, binary())
        fs.saveChara(listOf(0L), "GOLDEN_CHARA.dat", "chara desc")
        assertTrue(io.exists("dat/chara_GOLDEN_CHARA.dat.dat"), "原版坑：文件名自带 .dat 会叠加")

        val target = store()
        target.registerTemplate(CharacterTemplate(no = 1, name = "アリス", callname = "アリス"))
        FileSystemOps(target, io, binary()).loadChara("GOLDEN_CHARA.dat")
        assertEquals(1, target.characters.count)
        assertEquals(31L, target.readInt("CFLAG", idx(0, 2)))
        assertEquals("角色𠮟", target.readStr("NAME", idx(0)))
    }

    /**
     * `SAVECHARA` 写盘失败必须冒错：原版 SaveChara 只有 try/finally、**不 catch**
     * （VariableEvaluator.cs:2252-2283），写失败的 SystemException 会冒泡；
     * 旧实现丢弃 `io.write` 的返回值，失败被静默当成功。
     */
    @Test
    fun `SAVECHARA 写盘失败必须报错`() {
        val io = MemorySaveIo()
        val s = store()
        s.addHero()
        io.failWrites = true
        val e = assertFailsWith<CodeEE> { FileSystemOps(s, io, binary()).saveChara(listOf(0L), "FAIL", "x") }
        assertTrue(
            e.message!!.contains("chara_FAIL.dat"),
            "错误信息应带目标路径（${FileSystemOps.MES_SAVE_CHARA}）：${e.message}",
        )
        assertFalse(io.exists("dat/chara_FAIL.dat"))
    }

    /**
     * `SAVECHARA` 参数校验按原版**逐元素**顺序与消息（FunctionIdentifier.cs:1876-1896、
     * toUInt32inArg :4320-4331；消息 lang/emuera-zhs.xml:913/:915/:524/:525）。
     * 实参序从 3 起算（前两个实参是文件名与描述，:1877-1884）。
     */
    @Test
    fun `SAVECHARA 参数校验按原版逐元素顺序`() {
        val io = MemorySaveIo()
        val s = store()
        s.addHero() // 角色数 1
        val fs = FileSystemOps(s, io, binary())

        // 负值（toUInt32inArg 第一条，FunctionIdentifier.cs:4320-4325）
        assertEquals(
            "SAVECHARA函数: 第3个参数被是负值(-1)",
            assertFailsWith<CodeEE> { fs.saveChara(listOf(-1L), "A", "x") }.message,
        )
        // 超过 Int.MaxValue（:4326-4329）
        assertEquals(
            "SAVECHARA函数: 第3个参数(2147483648)过大",
            assertFailsWith<CodeEE> { fs.saveChara(listOf(Int.MAX_VALUE.toLong() + 1), "A", "x") }.message,
        )
        // 越界消息带实参序号（OoRSavecharaArg，:1884-1887）
        assertEquals(
            "SAVECHARA第3参数的值超出角色登记编号的范围",
            assertFailsWith<CodeEE> { fs.saveChara(listOf(1L), "A", "x") }.message,
        )
        // 重复（DuplicateCharaNo，:1888-1894）
        assertEquals(
            "同一个角色登记编号(0)被多次指定",
            assertFailsWith<CodeEE> { fs.saveChara(listOf(0L, 0L), "A", "x") }.message,
        )
        // 逐元素：第 0 个合法，第 1 个才报错且序号是 4（每元素先过 toUInt32inArg，:1881-1883）
        assertEquals(
            "SAVECHARA函数: 第4个参数被是负值(-1)",
            assertFailsWith<CodeEE> { fs.saveChara(listOf(0L, -1L), "A", "x") }.message,
        )
        // 同一批实参里越界先于重复（元素 0 越界即抛，走不到元素 1 的重复比对，:1884 vs :1888）
        assertEquals(
            "SAVECHARA第3参数的值超出角色登记编号的范围",
            assertFailsWith<CodeEE> { fs.saveChara(listOf(5L, 5L), "A", "x") }.message,
        )
        // 合法调用不受影响
        fs.saveChara(listOf(0L), "OK", "x")
        // 文件名口径 = chara_<name>.dat；「.dat.dat」只会出现在文件名本身带 .dat 时
        assertTrue(io.exists("dat/chara_OK.dat"))
    }

    /**
     * 用户二维角色变量经角色档往返：系统变量后先写 Separator 再逐个写用户变量
     * （CharacterData.cs:546-556），二维数组按行主序扁平下标存取。
     * 注：原版禁止 3 维及以上角色型变量（UserDefinedVariableData.cs:442-444
     * CharaVarCanNotDeclareMoreThan3D），故二维就是用户角色变量的上限。
     */
    @Test
    fun `角色档用户二维变量往返`() {
        val io = MemorySaveIo()
        val s = store()
        s.addHero()
        s.declareUserVar(dim("CHARADATA SAVEDATA CU2, 2, 3", binary = true))
        s.writeInt("CU2", idx(0, 1, 2), 701)
        s.writeInt("CU2", idx(0, 0, 0), 702)
        FileSystemOps(s, io, binary()).saveChara(listOf(0L), "CU2", "x")

        val target = store()
        target.registerTemplate(CharacterTemplate(no = 1, name = "アリス", callname = "アリス"))
        target.declareUserVar(dim("CHARADATA SAVEDATA CU2, 2, 3", binary = true))
        FileSystemOps(target, io, binary()).loadChara("CU2")
        assertEquals(1, target.characters.count)
        assertEquals(701L, target.readInt("CU2", idx(0, 1, 2)))
        assertEquals(702L, target.readInt("CU2", idx(0, 0, 0)))
    }

    // ------------------------------------------------------------------
    // 8. 接线
    // ------------------------------------------------------------------

    /** [registerSaveMethods] 幂等于既有注册：占位可被接管，真实现不被覆盖/撞名。 */
    @Test
    fun `registerSaveMethods 接线幂等`() {
        val r = BuiltinRegistry()
        r.registerUnimplemented("CHKDATA", OperandType.INT, 1, 1)
        registerSaveMethods(r)
        assertFalse(r.isPlaceholder("CHKDATA"), "占位应被真实现接管")
        assertEquals(OperandType.INT, r["CHKDATA"]?.retType)
        assertEquals(OperandType.INT, r["SAVENOS"]?.retType)
        registerSaveMethods(r) // 第二次不应抛「内建函数重名注册」
        assertNotNull(r["CHKDATA"])
    }

    // ------------------------------------------------------------------
    // 9. 真实 PC 存档
    // ------------------------------------------------------------------

    /**
     * 6 个 PC 原版样本（`<worktree>/.workbuddy/sav-backup/sav-20261018-110513/`，只读）经
     * [FileSystemOps.loadData] 整帧还原：角色数 168、UniqueCode 7153、描述尾巴
     * `CH20260326 `（spec/save-format.md §6.2 实测表）。
     *
     * 样本不在时按[假设失败]跳过（不伪造读数）。
     */
    @Test
    fun `真实 PC 存档经 FileSystemOps 装载`() {
        val dir = sequenceOf(
            java.io.File("../../.workbuddy/sav-backup/sav-20261018-110513"),
            java.io.File("../.workbuddy/sav-backup/sav-20261018-110513"),
            java.io.File(".workbuddy/sav-backup/sav-20261018-110513"),
        ).firstOrNull { it.isDirectory }
        org.junit.jupiter.api.Assumptions.assumeTrue(dir != null, "缺少 PC 存档样本目录")

        val bytes = java.io.File(dir, "save00.sav").readBytes()
        val io = MemorySaveIo()
        io.files["sav/save00.sav"] = bytes
        val s = VariableStore(
            registry = com.eratw.port.core.vars.VariableRegistry(
                gameBase = com.eratw.port.core.vars.GameBaseData(gameCode = 7153),
            ),
        )
        FileSystemOps(s, io, SaveConfig.ERATW).loadData(0)

        assertEquals(168, s.characters.count, "样本实测角色数 168")
        assertTrue(s.lastLoadText.endsWith("CH20260326 "), "描述尾巴（含尾空格）: ${s.lastLoadText}")
        assertTrue(s.lastLoadText.startsWith("2026/05/18 14:22:54 "), "时间戳: ${s.lastLoadText}")
        assertEquals(0L, s.lastLoadVersion, "样本 ScriptVersion=0")
    }

    // ------------------------------------------------------------------

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}
