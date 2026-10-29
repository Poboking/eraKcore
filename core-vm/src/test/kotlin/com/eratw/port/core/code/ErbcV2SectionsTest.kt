package com.eratw.port.core.code

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `.erbc` v2 段 7 / 段 8（调用点实参表 + 函数头部形参名表）。
 *
 * 规约：`specs/erbc-format.md` §5.8 / §5.9（段布局）、§8（版本与兼容）、
 * `core/code/Program.kt:24-30`（[CallArg] 四个半字段）、`Program.kt:49`（`FunctionInfo.paramNames`）。
 *
 * 本文件锁死四件事：
 * 1. **逐字段往返**：`position / bucket / isStr / name(null 与非 null) / present` 与形参名逐条相等；
 * 2. **旧 v1 产物仍可装载**，且 `Program.callArgSource == ERBC_V1`、逐位绑定标记为退化；
 * 3. **v1 写不下就拒写**（禁止静默降级，规约 §1.6）；
 * 4. **畸形 v2 段当场报错**（pc 越界/未升序、计数撒谎、保留位、函数数对不上）。
 */
class ErbcV2SectionsTest {

    // ==================================================================
    // 1. 逐字段往返
    // ==================================================================

    @Test
    fun `v2 往返后 callArgs 与 paramNames 逐字段相等`() {
        val before = metadataProgram()
        val bytes = ErbcWriter.encode(before)

        val header = ErbcReader.probe(bytes)
        assertEquals(ErbcFormat.VERSION_2, header.version, "写侧默认必须是 v2")
        assertEquals(ErbcFormat.SECTION_COUNT_V2, header.sectionCount)
        assertEquals(9, header.offsets.size)
        assertTrue(header.hasSection(ErbcFormat.SECTION_CALL_ARGS), "段 7 必须写出")
        assertTrue(header.hasSection(ErbcFormat.SECTION_PARAM_NAMES), "段 8 必须写出")
        assertEquals(ErbcFormat.FLAG_HAS_CALL_ARGS, header.flags and ErbcFormat.FLAG_HAS_CALL_ARGS)
        assertEquals(ErbcFormat.FLAG_HAS_PARAM_NAMES, header.flags and ErbcFormat.FLAG_HAS_PARAM_NAMES)

        val after = ErbcReader.decode(bytes)
        assertEquals(CallArgSource.ERBC_V2, after.callArgSource)
        assertEquals(before.callArgs.keys, after.callArgs.keys, "调用点集合必须逐字回来")
        for (pc in before.callArgs.keys) {
            assertEquals(before.callArgsAt(pc), after.callArgsAt(pc), "pc=$pc 的逐位实参元数据")
        }
        for (i in before.functions.indices) {
            assertEquals(
                before.functions[i].paramNames.toList(),
                after.functions[i].paramNames.toList(),
                "函数 ${before.functions[i].name} 的头部形参名",
            )
        }
        assertTrue(after.bindsCallArgsPositionally, "v2 产物装载后逐位绑定必须可用")

        // 规范再编码：装载 → 再编码逐字节相同（pc 升序规范化是幂等的）
        assertContentEquals(bytes, ErbcWriter.encode(after), "v2 规范再编码")
    }

    @Test
    fun `枚举覆盖 null 名字 字符串通道 省略位`() {
        // 三个实参覆盖 CallArg 的三种线值组合：
        //   0:B(int 桶 0, 裸变量名 "SRC", 有实参)
        //   1:B$(str 桶 0, 裸变量名 "SRC$", 有实参)
        //   2:", SRC$" 的省略位（present=false，无名字）
        val before = metadataProgram()
        val after = ErbcReader.decode(ErbcWriter.encode(before))
        val args = requireNotNull(after.callArgsAt(CALL_PC))

        assertEquals(3, args.size)
        assertEquals(CallArg(position = 0, bucket = 0, isStr = false, name = "SRC", present = true), args[0])
        assertEquals(CallArg(position = 1, bucket = 0, isStr = true, name = "SRC$", present = true), args[1])
        assertEquals(CallArg(position = 2, bucket = 0, isStr = false, name = null, present = false), args[2])
    }

    // ==================================================================
    // 2. 旧 v1 产物：仍可装载，但标记为退化
    // ==================================================================

    @Test
    fun `v1 产物装载后标记为 ERBC_V1 且逐位绑定退化`() {
        val plain = plainProgram()
        val v1 = ErbcWriter.encode(plain, ErbcEncodeOptions(version = ErbcFormat.VERSION_1))
        val header = ErbcReader.probe(v1)
        assertEquals(ErbcFormat.VERSION_1, header.version)
        assertEquals(ErbcFormat.SECTION_COUNT_V1, header.sectionCount)
        assertEquals(7, header.offsets.size, "v1 段表只有 7 槽")
        assertEquals(0, header.flags and ErbcFormat.FLAG_HAS_CALL_ARGS)
        assertEquals(0, header.flags and ErbcFormat.FLAG_HAS_PARAM_NAMES)

        val after = ErbcReader.decode(v1)
        assertEquals(CallArgSource.ERBC_V1, after.callArgSource, "v1 产物必须留可查标记")
        assertTrue(after.callArgs.isEmpty(), "v1 容器没有段 7")
        assertTrue(after.functions.all { it.paramNames.isEmpty() }, "v1 容器没有段 8")
        assertFalse(after.bindsCallArgsPositionally, "缺元数据 ⇒ 运行期退回类型桶绑定（不静默绑错）")
        // 指令流/函数表/池照旧逐字装载
        assertEquals(plain.instructionCount, after.instructionCount)
        assertContentEquals(plain.code, after.code)
        assertEquals(plain.functions.map { it.name }, after.functions.map { it.name })
    }

    @Test
    fun `v2 产物声明不带新段时装载成功且标记为退化`() {
        val bytes = ErbcWriter.encode(plainProgram())
        val header = ErbcReader.probe(bytes)
        assertEquals(ErbcFormat.VERSION_2, header.version)
        assertEquals(0, header.offsets[ErbcFormat.SECTION_CALL_ARGS], "无元数据就不写空壳段")
        assertEquals(0, header.offsets[ErbcFormat.SECTION_PARAM_NAMES])
        assertEquals(0, header.flags and ErbcFormat.FLAG_HAS_CALL_ARGS)

        val after = ErbcReader.decode(bytes)
        assertEquals(CallArgSource.ERBC_V2, after.callArgSource)
        assertFalse(after.bindsCallArgsPositionally)
        assertEquals(plainProgram().instructionCount, after.instructionCount)
    }

    @Test
    fun `v1 产物装载后可重新编码为 v2 且指令与函数表不变`() {
        val plain = plainProgram()
        val v1 = ErbcWriter.encode(plain, ErbcEncodeOptions(version = ErbcFormat.VERSION_1))
        val loadedV1 = ErbcReader.decode(v1)
        val upgraded = ErbcWriter.encode(loadedV1) // 默认 = 最新版本
        assertEquals(ErbcFormat.VERSION_2, ErbcReader.probe(upgraded).version, "再编码升到 v2")

        val reloaded = ErbcReader.decode(upgraded)
        assertContentEquals(loadedV1.code, reloaded.code, "指令流不变")
        assertEquals(
            loadedV1.functions.map { it.name to it.entryPc },
            reloaded.functions.map { it.name to it.entryPc },
            "函数表不变",
        )
        // v1 没有元数据可升级：标记仍是「无数据可绑」，不会凭空长出来
        assertEquals(CallArgSource.ERBC_V2, reloaded.callArgSource)
        assertFalse(reloaded.bindsCallArgsPositionally)
    }

    // ==================================================================
    // 3. v1 写不下就拒写（规约 §1.6）
    // ==================================================================

    @Test
    fun `v1 选项下带实参元数据拒写并指名段号`() {
        val e = assertFailsWith<ErbcEncodeException> {
            ErbcWriter.encode(metadataProgram(), ErbcEncodeOptions(version = ErbcFormat.VERSION_1))
        }
        assertTrue(e.message!!.contains("调用点实参段"), e.message)
        assertTrue(e.message!!.contains("§5.8"), e.message)
    }

    @Test
    fun `v1 选项下只有形参名也拒写`() {
        val b = CodeBuilder()
        val main = b.declareFunction("MAIN", 0, 0, 1, paramNames = listOf("A"))
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        val program = b.build(main)
        val e = assertFailsWith<ErbcEncodeException> {
            ErbcWriter.encode(program, ErbcEncodeOptions(version = ErbcFormat.VERSION_1))
        }
        assertTrue(e.message!!.contains("形参名段"), e.message)
        assertTrue(e.message!!.contains("§5.9"), e.message)
    }

    @Test
    fun `未知容器版本在写侧就拒绝`() {
        assertFailsWith<ErbcEncodeException> {
            ErbcWriter.encode(plainProgram(), ErbcEncodeOptions(version = 3))
        }
    }

    // ==================================================================
    // 4. 确定性：Map 迭代序不影响字节
    // ==================================================================

    @Test
    fun `调用点按 pc 升序写出 两次编码逐字节相同`() {
        val before = multiSiteProgram()
        val first = ErbcWriter.encode(before)
        val second = ErbcWriter.encode(before)
        assertContentEquals(first, second, "同一 Program 两次编码必须逐字节相同")

        val after = ErbcReader.decode(first)
        assertEquals(listOf(1, 4, 7), after.callArgs.keys.toList(), "装载后按键升序（与写出序一致）")
    }

    // ==================================================================
    // 5. 畸形 v2 段
    // ==================================================================

    @Test
    fun `段 7 的 pc 越界与未升序当场报错`() {
        val base = ErbcWriter.encode(plainProgram())
        val badPc = appendSection(
            base,
            ErbcFormat.SECTION_CALL_ARGS,
            ErbcFormat.FLAG_HAS_CALL_ARGS,
            callArgsBody(pcs = intArrayOf(99), argsOf = { 1 }),
        )
        val e1 = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(badPc) }
        assertTrue(e1.message!!.contains("pc=99"), e1.message)

        val unsorted = appendSection(
            base,
            ErbcFormat.SECTION_CALL_ARGS,
            ErbcFormat.FLAG_HAS_CALL_ARGS,
            callArgsBody(pcs = intArrayOf(2, 1), argsOf = { 1 }),
        )
        val e2 = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(unsorted) }
        assertTrue(e2.message!!.contains("升序"), e2.message)
    }

    @Test
    fun `段 7 的实参总数撒谎与保留位当场报错`() {
        val base = ErbcWriter.encode(plainProgram())
        val lying = appendSection(
            base,
            ErbcFormat.SECTION_CALL_ARGS,
            ErbcFormat.FLAG_HAS_CALL_ARGS,
            callArgsBody(pcs = intArrayOf(0), argsOf = { 1 }, totalOverride = 5),
        )
        val e1 = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(lying) }
        assertTrue(e1.message!!.contains("实参总数"), e1.message)

        val badFlags = appendSection(
            base,
            ErbcFormat.SECTION_CALL_ARGS,
            ErbcFormat.FLAG_HAS_CALL_ARGS,
            callArgsBody(pcs = intArrayOf(0), argsOf = { 1 }, argFlagsOverride = 0x04),
        )
        val e2 = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(badFlags) }
        assertTrue(e2.message!!.contains("flags"), e2.message)
    }

    @Test
    fun `段 8 的函数个数与函数表不符当场报错`() {
        val base = ErbcWriter.encode(plainProgram())
        val body = hand {
            u32(3) // funcCount：函数表只有 2 个（MAIN / TGT）
            put(handStringTable(listOf("A")))
            u32(1); u32(0)
            u32(0)
        }
        val bad = appendSection(base, ErbcFormat.SECTION_PARAM_NAMES, ErbcFormat.FLAG_HAS_PARAM_NAMES, body)
        val e = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(bad) }
        assertTrue(e.message!!.contains("形参名段函数个数"), e.message)
    }

    @Test
    fun `标志位声明带段但段表偏移为 0 报不一致`() {
        val base = ErbcWriter.encode(plainProgram())
        val bad = base.copyOf()
        writeLe16(bad, 6, readLe16(bad, 6) or ErbcFormat.FLAG_HAS_CALL_ARGS)
        val e = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(bad) }
        assertTrue(e.message!!.contains("不一致"), e.message)
    }

    @Test
    fun `v2 头配 v1 段表槽数拒绝`() {
        val base = ErbcWriter.encode(plainProgram())
        val bad = base.copyOf()
        writeLe16(bad, 8, ErbcFormat.SECTION_COUNT_V1)
        val e = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(bad) }
        assertTrue(e.message!!.contains("段表槽数"), e.message)
    }

    // ==================================================================
    // 样题
    // ==================================================================

    private val CALL_PC = 0

    /** `MAIN` 在 pc 0 调 `TGT(B, B$, <省略>)`，两个函数都带头部形参名。 */
    private fun metadataProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("MAIN", 0, 0, 2, paramNames = listOf("B", "B$"))
        val pc = b.emit(Opcode._CALL, 1, 3, SlotAddr.NONE, 10)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 11)
        val tgt = b.declareFunction("TGT", b.here(), 3, 3, paramNames = listOf("REF_TGT", "ARG:0", "ARGS:1"))
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 20)
        b.emit(Opcode._HALT, 0, 0, 0, 21)
        b.declareCallArgs(
            pc,
            listOf(
                CallArg(position = 0, bucket = 0, isStr = false, name = "SRC", present = true),
                CallArg(position = 1, bucket = 0, isStr = true, name = "SRC$", present = true),
                CallArg(position = 2, bucket = 0, isStr = false, name = null, present = false),
            ),
        )
        assertEquals(0, main)
        assertEquals(1, tgt)
        return b.build(main)
    }

    /** 无任何 v2 元数据的程序（v1/v2 都能写）。 */
    private fun plainProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("MAIN", 0, 0, 2)
        b.emit(Opcode._CALL, 1, 0, SlotAddr.NONE, 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 2)
        b.declareFunction("TGT", b.here(), 0, 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 3)
        b.emit(Opcode._HALT, 0, 0, 0, 4)
        return b.build(main)
    }

    /** 三个调用点（pc 1/4/7），用来验证 pc 升序规范化。 */
    private fun multiSiteProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("MAIN", 0, 0, 2, paramNames = listOf("A"))
        b.emit(Opcode._NOP, 0, 0, 0, 0)
        val p1 = b.emit(Opcode._CALL, 1, 1, SlotAddr.NONE, 1)
        b.emit(Opcode._NOP, 0, 0, 0, 2)
        b.emit(Opcode._NOP, 0, 0, 0, 3)
        val p4 = b.emit(Opcode._CALL, 1, 1, SlotAddr.NONE, 4)
        b.emit(Opcode._NOP, 0, 0, 0, 5)
        b.emit(Opcode._NOP, 0, 0, 0, 6)
        val p7 = b.emit(Opcode._CALL, 1, 1, SlotAddr.NONE, 7)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 8)
        b.declareFunction("TGT", b.here(), 1, 1, paramNames = listOf("A"))
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 9)
        b.emit(Opcode._HALT, 0, 0, 0, 10)
        for (pc in intArrayOf(p1, p4, p7)) {
            b.declareCallArgs(pc, listOf(CallArg(pc % 2, 0, isStr = false, name = null, present = true)))
        }
        return b.build(main)
    }

    // ==================================================================
    // 手写字节工具（只拼段 7/8 的正文，其余段沿用 writer 的合法产物）
    // ==================================================================

    private class Hand {
        private val out = ArrayList<Int>()

        fun u8(v: Int): Hand = apply { out.add(v and 0xFF) }

        fun u32(v: Int): Hand = apply {
            u8(v)
            u8(v ushr 8)
            u8(v ushr 16)
            u8(v ushr 24)
        }

        fun put(bytes: ByteArray): Hand = apply { for (b in bytes) u8(b.toInt()) }

        fun build(): ByteArray = ByteArray(out.size) { out[it].toByte() }
    }

    private fun hand(block: Hand.() -> Unit): ByteArray = Hand().apply(block).build()

    private fun handStringTable(items: List<String>): ByteArray = hand {
        val encoded = items.map { it.toByteArray(Charsets.UTF_8) }
        u32(items.size)
        u32(encoded.sumOf { it.size })
        var acc = 0
        for (e in encoded) {
            u32(acc)
            acc += e.size
        }
        u32(acc)
        for (e in encoded) put(e)
    }

    /**
     * 段 7 正文：`siteCount | totalArgCount | STRING_TABLE | site{pc, argc, arg{...}}`。
     *
     * [argsOf] 给出每个站点的实参个数（实参内容固定为 position 0..n-1、bucket 0、name null）。
     */
    private fun callArgsBody(
        pcs: IntArray,
        argsOf: (Int) -> Int,
        totalOverride: Int? = null,
        argFlagsOverride: Int? = null,
    ): ByteArray {
        val counts = IntArray(pcs.size) { argsOf(it) }
        return hand {
            u32(pcs.size)
            u32(totalOverride ?: counts.sum())
            put(handStringTable(emptyList()))
            for (i in pcs.indices) {
                u32(pcs[i])
                u32(counts[i])
                repeat(counts[i]) { k ->
                    u32(k)
                    u32(0)
                    u8(argFlagsOverride ?: ErbcFormat.CallArgFlags.PRESENT)
                    u32(ErbcFormat.NONE_REF)
                }
            }
        }
    }

    /** 在合法 v2 产物尾部追加一个段，并把段表/标志位指向它。 */
    private fun appendSection(
        base: ByteArray,
        section: Int,
        flag: Int,
        body: ByteArray,
    ): ByteArray {
        val out = base.copyOf(base.size + body.size)
        body.copyInto(out, base.size)
        writeLe32(out, ErbcFormat.HEADER_BYTES + section * 4, base.size)
        writeLe16(out, 6, readLe16(out, 6) or flag)
        return out
    }

    private fun readLe16(src: ByteArray, at: Int): Int =
        (src[at].toInt() and 0xFF) or ((src[at + 1].toInt() and 0xFF) shl 8)

    private fun writeLe16(
        dst: ByteArray,
        at: Int,
        value: Int,
    ) {
        dst[at] = (value and 0xFF).toByte()
        dst[at + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeLe32(
        dst: ByteArray,
        at: Int,
        value: Int,
    ) {
        dst[at] = (value and 0xFF).toByte()
        dst[at + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[at + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
