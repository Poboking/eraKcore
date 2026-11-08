package com.eratw.port.core.data.save

import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 4.981 `#DIMF` 浮点变量的存档通道（扩展标签 0x04–0x07）。
 *
 * 取证等级 A，全部行号指 `qirchen421/emuera.sk` 分支 `develop-skiasharp`
 * commit `48494165eaf3335f3982b6a7c33bee662b8ba2c1`（2026-08-28，即 specs 里 `fork-src` 的锚点）：
 * - 标签枚举 `EraSaveDataType.Float/FloatArray/FloatArray2D/FloatArray3D = 0x04/0x05/0x06/0x07`
 *   （Runtime/Utils/EraBinaryDataReader.cs:21-34）；
 * - 写侧 `EraBinaryDataWriter.WriteWithKey`（:132-170）：标量 = 裸 8 字节小端 double；
 *   数组 = 定长 Int32 长度/维度 + **逐个直发**的 8 字节 double，
 *   既无整数的变长编码、也无 0 游程标记、**结尾也没有 `0xFF`**，全零元素照写；
 * - 读侧 `VariableData.LoadVariableBinary` 的四个浮点 case（:1486-1554）：
 *   尺寸不符只「读掉再丢」，**不清尾段**；多维按**流位置**落位（窄化的二维会斜铺，ADR-005 原样复刻）；
 * - `default:` 紧随其后抛 `FileEE(AbnormalData)`（:1555-1556）⇒ 未知标签仍是坏档。
 *
 * 本文件不依赖 [VariableStore] 的浮点存储面（路 [f1] 尚未合并），装池一侧走 [FloatSink]
 * 接缝，用记录器观察「写到了哪些格子、哪些没写」——这正是原版容错的可见差异所在。
 */
class SaveFloatVarTest {

    // ------------------------------------------------------------------
    // 夹具：按 C# 写侧形状手工拼档
    // ------------------------------------------------------------------

    private fun le32(v: Int): ByteArray = ByteArray(4) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun le64(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    /** .NET `BinaryWriter.Write(double)` = IEEE-754 binary64 小端 8 字节。 */
    private fun dbl(v: Double): ByteArray = le64(v.toRawBits())

    /** .NET `BinaryWriter.Write(string)`：7-bit **字节数**前缀 + UTF-16LE 码元。 */
    private fun str(s: String): ByteArray {
        val byteLen = s.length * 2
        val prefix = ArrayList<Byte>(2)
        var n = byteLen
        while (true) {
            val x = n and 0x7F
            n = n ushr 7
            if (n != 0) prefix.add((x or 0x80).toByte()) else { prefix.add(x.toByte()); break }
        }
        val body = ByteArray(byteLen)
        for (i in s.indices) {
            val c = s[i].code
            body[2 * i] = (c and 0xFF).toByte()
            body[2 * i + 1] = ((c ushr 8) and 0xFF).toByte()
        }
        return prefix.toByteArray() + body
    }

    private fun cat(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { a, b -> a + b }

    /** 全局档正文（GLOBAL=1；无角色段），尾部两个 `0xFF` = 变量块 EOF + EM 块 EOF。 */
    private fun pcPayload(entries: ByteArray): ByteArray = cat(
        byteArrayOf(SaveFileKind.GLOBAL.ordinal.toByte()), le64(7153L), le64(0L), str(""),
        entries, byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
    )

    /** 同上再加 16 字节容器头（非压缩分支），才能喂给 [SaveFormat.read]。 */
    private fun pcFile(entries: ByteArray): ByteArray =
        cat(le64(SaveFormat.MAGIC_PLAIN), le32(SaveFormat.VERSION), le32(0), pcPayload(entries))

    private fun pcFloatScalar(key: String, v: Double): ByteArray =
        cat(byteArrayOf(0x04), str(key), dbl(v))

    private fun pcFloat1(key: String, vs: DoubleArray): ByteArray =
        cat(byteArrayOf(0x05), str(key), le32(vs.size), *vs.map { dbl(it) }.toTypedArray())

    private fun pcFloat2(key: String, d0: Int, d1: Int, vs: DoubleArray): ByteArray =
        cat(byteArrayOf(0x06), str(key), le32(d0), le32(d1), *vs.map { dbl(it) }.toTypedArray())

    private fun pcFloat3(key: String, d0: Int, d1: Int, d2: Int, vs: DoubleArray): ByteArray =
        cat(byteArrayOf(0x07), str(key), le32(d0), le32(d1), le32(d2), *vs.map { dbl(it) }.toTypedArray())

    private fun dataOf(vararg entries: SaveEntry): SaveData = SaveData(
        fileType = SaveFileKind.GLOBAL, uniqueCode = 7153L, scriptVersion = 0L, text = "",
        variables = entries.toList(),
    )

    /** 逐位比较：`==` 对 NaN 恒 false，浮点往返只认原始位。 */
    private fun assertBits(expected: Double, actual: Double, clue: String) {
        assertEquals(expected.toRawBits(), actual.toRawBits(), clue)
    }

    // ------------------------------------------------------------------
    // 1) 写侧字节 == 原版字节
    // ------------------------------------------------------------------

    /**
     * 本层写出的正文必须与「按 C# 写侧形状手拼」的字节**逐字节相同**。
     *
     * 这是整套用例的地基：只跟自己读回比是循环论证（游程编码也能自洽往返），
     * 而 PC 侧读浮点时按定宽 8 字节直取（EraBinaryDataReader.cs:283-285），
     * 多一个游程标记或少一个终止符都会让整段错位。
     */
    @Test
    fun `浮点写侧逐字节对齐原版形状`() {
        val values = doubleArrayOf(0.0, -0.0, 1.5, -2.25)
        val entries = cat(
            pcFloatScalar("SC", 3.75),
            pcFloat1("A1", values),
            pcFloat2("A2", 2, 3, doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)),
            pcFloat3("A3", 1, 2, 2, doubleArrayOf(7.0, 8.0, 9.0, 10.0)),
        )
        val expected = pcPayload(entries)
        val actual = SaveFormat.encodePayload(
            dataOf(
                SaveEntry.FloatValue("SC", 3.75),
                SaveEntry.FloatArray1("A1", values),
                SaveEntry.FloatArray2("A2", 2, 3, doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)),
                SaveEntry.FloatArray3("A3", 1, 2, 2, doubleArrayOf(7.0, 8.0, 9.0, 10.0)),
            ),
        )
        assertTrue(
            expected.contentEquals(actual),
            "写侧与 PC 形状不一致\n期望 ${expected.joinToString(",")}\n实际 ${actual.joinToString(",")}",
        )
    }

    /**
     * 全零浮点数组**不得**压成游程：整数数组那套 `0xF0 + 计数` 对浮点不存在
     * （写侧每个元素一条 `writer.Write(arr[i])`，EraBinaryDataWriter.cs:144-145）。
     */
    @Test
    fun `全零浮点数组仍逐元素密集落盘`() {
        val payload = SaveFormat.encodePayload(dataOf(SaveEntry.FloatArray1("Z", DoubleArray(40))))
        // 正文收尾必须正好是「40 个 0.0 的 320 个零字节」+ 变量块 EOF + EM 块 EOF：
        // 中间既插不进 0xF0 游程标记，数组自己也不带 0xFF（整数的 int1 两者都有）。
        val tail = cat(ByteArray(40 * 8), byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        assertEquals(
            tail.toList(),
            payload.takeLast(tail.size).toList(),
            "浮点段末尾形状不符（出现游程标记或数组内终止符就说明按整数那套编码了）",
        )
        // 头 18 + 标签 1 + 键名 3 + 长度前缀 4 + 密集 320 + 两个 EOF = 348，一个游程字节都不许多
        assertEquals(348, payload.size, "浮点数组正文长度 = 密集 8 字节/元素，无任何压缩开销")
        // 同长度的整数数组全零会被压成「游程标记 + 计数 + 终止符」三字节 ⇒ 两者确实不同构
        val intPayload = SaveFormat.encodePayload(dataOf(SaveEntry.IntArray1("Z", LongArray(40))))
        assertTrue(intPayload.size * 4 < payload.size, "浮点密集段必须明显大于整数游程段")
    }

    // ------------------------------------------------------------------
    // 2) 读侧还原原版档 + 逐位往返
    // ------------------------------------------------------------------

    /** 位图案电池：覆盖 ±0、普通小数、无法精确表示的二分累加、极值、次正规、±Inf 与带尾数的 NaN。 */
    private val battery = doubleArrayOf(
        0.0, -0.0, 1.5, -2.25, 0.1, 1.0 / 3.0, -1.0 / 3.0,
        Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, 1e-308, 1e308,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Double.NaN, Double.fromBits(0x7FF8DEADBEEFL),
    )

    /** 读原版形状的档：值必须逐个逐位复现。 */
    @Test
    fun `读侧按位还原原版浮点档`() {
        val file = pcFile(pcFloat1("B", battery))
        val e = SaveFormat.read(file).variables.single()
        assertTrue(e is SaveEntry.FloatArray1, "读回应为 FloatArray1，实到 ${e::class.simpleName}")
        val values = e.values
        assertEquals(battery.size, values.size, "长度前缀是 Int32 定长，元素数必须相等")
        for (i in battery.indices) assertBits(battery[i], values[i], "电池第 $i 项")
    }

    /**
     * 存 → 读 → 逐位比对（标量 + 1D/2D/3D）。
     *
     * `-0.0` 与 `0.0`、以及两个 payload 不同的 NaN 在 `==` 下不可区分，
     * 所以断言一律走 [Double.toRawBits]。
     */
    @Test
    fun `存读往返逐位一致`() {
        val src = dataOf(
            SaveEntry.FloatValue("S", -0.0),
            SaveEntry.FloatValue("S2", Double.fromBits(0x7FF8DEADBEEFL)),
            SaveEntry.FloatArray1("V", battery),
            SaveEntry.FloatArray2(
                "M", 4, 5,
                DoubleArray(20) { i -> battery[i % battery.size] * (i + 1) / 7.0 },
            ),
            SaveEntry.FloatArray3("T", 2, 2, 3, DoubleArray(12) { 1e-9 * it }),
        )
        val back = SaveFormat.read(SaveFormat.write(src))

        assertEquals(src.variables.size, back.variables.size, "条目数不得增减")
        for (i in src.variables.indices) {
            val a = src.variables[i]
            val b = back.variables[i]
            assertEquals(a.key, b.key, "第 $i 条的键")
            when (a) {
                is SaveEntry.FloatValue -> {
                    assertTrue(b is SaveEntry.FloatValue, "标量读成 ${b::class.simpleName}")
                    assertBits(a.value, b.value, "${a.key} 标量")
                }
                is SaveEntry.FloatArray1 -> {
                    assertTrue(b is SaveEntry.FloatArray1, "一维读成 ${b::class.simpleName}")
                    assertEquals(a.values.size, b.values.size)
                    for (k in a.values.indices) assertBits(a.values[k], b.values[k], "${a.key}[$k]")
                }
                is SaveEntry.FloatArray2 -> {
                    assertTrue(b is SaveEntry.FloatArray2)
                    assertEquals(a.dim0, b.dim0); assertEquals(a.dim1, b.dim1)
                    for (k in a.values.indices) assertBits(a.values[k], b.values[k], "${a.key}[$k]")
                }
                is SaveEntry.FloatArray3 -> {
                    assertTrue(b is SaveEntry.FloatArray3)
                    assertEquals(a.dim0, b.dim0); assertEquals(a.dim1, b.dim1); assertEquals(a.dim2, b.dim2)
                    for (k in a.values.indices) assertBits(a.values[k], b.values[k], "${a.key}[$k]")
                }
                else -> error("夹具只应放浮点条目")
            }
        }
    }

    /** 未改动的档回写原字节（幂等短路）也必须对含浮点段的档成立。 */
    @Test
    fun `含浮点段的档未改动时逐字节回写`() {
        val bytes = SaveFormat.write(dataOf(SaveEntry.FloatArray1("F", battery)))
        val same = SaveFormat.write(SaveFormat.read(bytes))
        assertTrue(bytes.contentEquals(same), "读回再写出必须与原字节完全相同")
    }

    // ------------------------------------------------------------------
    // 3) 三种边界
    // ------------------------------------------------------------------

    /**
     * 旧档缺浮点段：存档里一个浮点条目都没有时照常读，且**不会**给已登记的浮点变量写任何东西。
     *
     * 原版是逐条带标签的流（EraBinaryDataWriter.cs:106-173），浮点段是「有则读」而非定长尾段，
     * 所以 4.980 时代的档在 4.981 下缺失整段是合法状态；池侧行为对齐
     * `LoadVariableBinary` 的「没读到就不写」。
     */
    @Test
    fun `旧档无浮点段可读且不动浮点变量`() {
        val store = VariableStore()
        store.declareUserVar(float1D("RATEF", 4))
        store.declareUserVar(
            DimSpec("SOMEINT", isStr = false, dims = IntArray(0), isSavedata = true),
        )
        val sink = Recorder(mapOf("RATEF" to intArrayOf(4)))
        val old = dataOf(SaveEntry.IntValue("SOMEINT", 7L))
        val report = SaveFormat.read(SaveFormat.write(old)).applyTo(store, floatSink = sink)
        assertEquals(1, report.applied, "整数条目照常装载")
        assertEquals(0, report.skipped)
        assertEquals(List(4) { Recorder.UNTOUCHED }, sink.cells("RATEF"), "旧档不该给浮点数组落任何东西")
        assertEquals(7L, store.readInt("SOMEINT", longArrayOf()))
    }

    /**
     * 档内比声明长：只搬 `min(文件, 声明)`，多出来的**读掉即丢**，不报错
     * （VariableData.cs:1511-1512 的 `for (i = min; i < len; i++) reader.ReadDouble();`）。
     */
    @Test
    fun `档比声明长时多余元素读掉即丢`() {
        val store = VariableStore()
        store.declareUserVar(float1D("RATEF", 3))
        val sink = Recorder(mapOf("RATEF" to intArrayOf(3)))
        val file = pcFile(pcFloat1("RATEF", doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)))
        val report = SaveFormat.read(file).applyTo(store, floatSink = sink)
        assertEquals(1, report.applied)
        assertEquals(listOf(1.0, 2.0, 3.0), sink.cells("RATEF"), "只取前 3 个，4.0/5.0 丢弃")
    }

    /**
     * 档比声明短：**尾段保持旧值**，不清零。
     *
     * 这是浮点与整数的关键分歧：整数数组走 needInit，把文件长度之后的元素清 0
     * （EraBinaryDataReader.cs:161-166），而浮点那条 case 里一个清零循环都没有
     * （VariableData.cs:1492-1516 只有「写 min 区间 + 丢余量」）⇒ 移植侧照抄，不「顺手对齐」。
     */
    @Test
    fun `档比声明短时尾段保持旧值不清零`() {
        val store = VariableStore()
        store.declareUserVar(float1D("RATEF", 5))
        val sink = Recorder(mapOf("RATEF" to intArrayOf(5)))
        val file = pcFile(pcFloat1("RATEF", doubleArrayOf(1.0, 2.0)))
        SaveFormat.read(file).applyTo(store, floatSink = sink)
        assertEquals(
            listOf(1.0, 2.0, Recorder.UNTOUCHED, Recorder.UNTOUCHED, Recorder.UNTOUCHED),
            sink.cells("RATEF"),
            "后三格必须一次写都没有；若被清成 0.0 就说明把整数的 needInit 错套到浮点了",
        )
    }

    /**
     * 浮点 2D（标签 0x06）读侧：两个定长 Int32 维度 + 行主序密集 double 全部还原。
     *
     * 池侧「斜铺」需要 2D 浮点声明，而 `#DIMF` 只允许 1D（spec: variables.yaml:109，
     * `VariableData.cs:469-474/553-559/585-591`），该形状在声明期即被拒；因此这里锁的是
     * **可达的读侧形状**，不再构造不可达的存储声明（存储层守卫见 `FloatVarRuntimeTest`）。
     */
    @Test
    fun `二维浮点0x06读侧形状`() {
        val e = SaveFormat.read(
            pcFile(pcFloat2("GRID2D", 2, 3, doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))),
        ).variables.single()
        assertIs<SaveEntry.FloatArray2>(e)
        assertEquals(2, e.dim0, "档内第 0 维")
        assertEquals(3, e.dim1, "档内第 1 维")
        assertEquals(
            listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
            e.values.toList(),
            "行主序密集、全零也照写，无游程压缩",
        )
    }

    /** 标签非法（0x08 不在 EraSaveDataType 里）：读侧抛坏档，与 `default:` 的 FileEE 同口径。 */
    @Test
    fun `未知类型字节判坏档`() {
        val file = pcFile(cat(byteArrayOf(0x08), str("X"), dbl(1.0)))
        assertFailsWith<SaveFormatException> { SaveFormat.read(file) }
    }

    /**
     * 变量不认识时浮点段也要**只走标签不建数组**地跳干净（`keepKey` 的 skipValue 路径），
     * 且跳过之后紧跟的条目必须仍然对齐。
     */
    @Test
    fun `不认识的浮点变量跳过后流不错位`() {
        val file = pcFile(
            cat(
                pcFloat1("GONE", battery),
                pcFloat2("GONE2", 2, 2, DoubleArray(4)),
                pcFloatScalar("GONE3", 1.0),
                str0Entry("STILL", "ok"),
            ),
        )
        val kept = SaveFormat.read(file, keepKey = { it == "STILL" }).variables
        assertEquals(1, kept.size, "只留下认识的条目")
        assertEquals("STILL", (kept.single() as SaveEntry.StrValue).key)
        assertEquals("ok", (kept.single() as SaveEntry.StrValue).value, "跳过三个浮点段后光标必须正好落在下一条")
    }

    /** 一条 0 维字符串（标签 16）的原始字节，用来验证跳过后的对齐。 */
    private fun str0Entry(key: String, value: String): ByteArray =
        cat(byteArrayOf(0x10), str(key), str(value))

    // ------------------------------------------------------------------
    // 4) 类型守卫
    // ------------------------------------------------------------------

    /** 整数条目打到浮点声明、浮点条目打到整数声明，都必须整条丢弃而不是写错池。 */
    @Test
    fun `类型不符的浮点条目跳过`() {
        val store = VariableStore()
        store.declareUserVar(float1D("RATEF", 2))
        store.declareUserVar(
            DimSpec("MYCOUNT", isStr = false, dims = intArrayOf(2), isSavedata = true),
        )
        val sink = Recorder(mapOf("RATEF" to intArrayOf(2)))

        val mixed = dataOf(
            SaveEntry.FloatValue("RATEF", 1.0), // 标量条目打到 1 维浮点 ⇒ 维度不符
            SaveEntry.IntValue("RATEF", 5L), // 整数条目打到浮点声明
            SaveEntry.FloatArray1("MYCOUNT", doubleArrayOf(1.0, 2.0)), // 浮点条目打到整数声明
        )
        val report = SaveFormat.read(SaveFormat.write(mixed)).applyTo(store, floatSink = sink)
        assertEquals(0, report.applied, "三条全因类型/维度不符丢弃")
        assertEquals(listOf(Recorder.UNTOUCHED, Recorder.UNTOUCHED), sink.cells("RATEF"))
        assertEquals(0L, store.readInt("MYCOUNT", longArrayOf(0L)), "整数池也不该被浮点条目写过")
    }

    /** 不接浮点面时（路 [f1] 未合并的默认形态）浮点条目按 skipped 计数，而不是静默消失。 */
    @Test
    fun `没有浮点接缝时按跳过计数`() {
        val store = VariableStore()
        store.declareUserVar(float1D("RATEF", 2))
        val report = dataOf(SaveEntry.FloatArray1("RATEF", doubleArrayOf(1.0, 2.0)))
            .let { SaveFormat.read(SaveFormat.write(it)) }
            .applyTo(store)
        assertEquals(0, report.applied)
        assertEquals(1, report.skipped)
    }

    /** 标量浮点（标签 0x04）的读侧：裸 8 字节小端 double，逐位还原。 */
    @Test
    fun `浮点标量0x04逐位还原`() {
        val v = 1.0 / 3.0
        val e = SaveFormat.read(pcFile(pcFloatScalar("SC", v))).variables.single()
        assertEquals("SC", e.key)
        assertIs<SaveEntry.FloatValue>(e)
        assertEquals(v.toRawBits(), e.value.toRawBits(), "0x04 必须是裸 8 字节 double，逐位相等")
    }

    // ------------------------------------------------------------------
    // 声明夹具
    // ------------------------------------------------------------------

    private fun float1D(name: String, n: Int) =
        DimSpec(name, isStr = false, dims = intArrayOf(n), isSavedata = true, isFloat = true)

    private fun float2D(name: String, d0: Int, d1: Int) =
        DimSpec(name, isStr = false, dims = intArrayOf(d0, d1), isSavedata = true, isFloat = true)
}

/**
 * 浮点写回记录器：按声明形状开一格 [UNTOUCHED] 的格子池，把每次 `writeFloat` 落在行主序下标上。
 *
 * 「哪些格子**没有**被写过」就是原版浮点容错的全部内容（不清尾段、丢弃多余、窄化斜铺），
 * 所以这里必须同时记录写值与写坐标，而不是只比对最终数组。
 */
private class Recorder(shapes: Map<String, IntArray>) : FloatSink {
    private val shapeOf = HashMap<String, IntArray>()
    private val grid = HashMap<String, MutableList<Double>>()

    init {
        for ((name, dims) in shapes) {
            var n = 1
            for (d in dims) n *= d
            shapeOf[name] = dims
            grid[name] = MutableList(n) { UNTOUCHED }
        }
    }

    override fun writeFloat(key: String, index: LongArray, value: Double) {
        val dims = shapeOf[key] ?: error("写到了未登记的浮点变量 $key")
        require(index.size == dims.size) { "$key 的下标维数 ${index.size} 与声明的 ${dims.size} 不符" }
        var flat = 0
        var stride = 1
        for (i in dims.indices.reversed()) {
            val v = index[i]
            require(v in 0 until dims[i].toLong()) { "$key 第 $i 维下标 $v 越出 0..${dims[i] - 1}" }
            flat += v.toInt() * stride
            stride *= dims[i]
        }
        grid.getValue(key)[flat] = value
    }

    /** 该变量的全部格子（行主序），未写过的格子保持 [UNTOUCHED]。 */
    fun cells(key: String): List<Double> = grid.getValue(key).toList()

    /** 按位取格子，供 NaN / ±0 这类 `==` 分不开的断言用。 */
    fun bits(key: String, i: Int): Long = grid.getValue(key)[i].toRawBits()

    companion object {
        /** 「一次写都没有」的哨兵值 —— 刻意取一个档里不可能出现的十进制数，避免与 0.0 混淆。 */
        const val UNTOUCHED = -1.2345678901234567E100
    }
}
