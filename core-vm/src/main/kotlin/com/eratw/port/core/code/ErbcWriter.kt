package com.eratw.port.core.code

import com.eratw.port.core.code.ErbcFormat.ArgTag
import com.eratw.port.core.code.ErbcFormat.ExprTag
import com.eratw.port.core.code.ErbcFormat.NONE_REF
import com.eratw.port.core.code.ErbcFormat.StmtTag
import com.eratw.port.core.code.ErbcFormat.WireEnum
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.NullTerm
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.ArgValueType
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.BitArgument
import com.eratw.port.core.stmt.CaseArgument
import com.eratw.port.core.stmt.CaseElseStmt
import com.eratw.port.core.stmt.CaseExpression
import com.eratw.port.core.stmt.CaseStmt
import com.eratw.port.core.stmt.CatchStmt
import com.eratw.port.core.stmt.CallFStmt
import com.eratw.port.core.stmt.CallSharpStmt
import com.eratw.port.core.stmt.CallStmt
import com.eratw.port.core.stmt.DataListStmt
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.DoStmt
import com.eratw.port.core.stmt.ElseIfStmt
import com.eratw.port.core.stmt.ElseStmt
import com.eratw.port.core.stmt.EndBlockStmt
import com.eratw.port.core.stmt.EndCatchStmt
import com.eratw.port.core.stmt.EndIfStmt
import com.eratw.port.core.stmt.EndSelectStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.ExpressionsArgument
import com.eratw.port.core.stmt.FlowStmt
import com.eratw.port.core.stmt.ForStmt
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.GotoLabelStmt
import com.eratw.port.core.stmt.HtmlPrintArgument
import com.eratw.port.core.stmt.IfStmt
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.IntAsignArgument
import com.eratw.port.core.stmt.JumpStmt
import com.eratw.port.core.stmt.LoopStmt
import com.eratw.port.core.stmt.MethodArgument
import com.eratw.port.core.stmt.MethodStmt
import com.eratw.port.core.stmt.MixedIntegerExprTerm
import com.eratw.port.core.stmt.NextStmt
import com.eratw.port.core.stmt.PrintDataArgument
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.RefArgument
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.RepeatStmt
import com.eratw.port.core.stmt.ReturnFStmt
import com.eratw.port.core.stmt.ReturnStmt
import com.eratw.port.core.stmt.SelectCaseStmt
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.SortOrder
import com.eratw.port.core.stmt.SpArrayControlArgument
import com.eratw.port.core.stmt.SpArrayShiftArgument
import com.eratw.port.core.stmt.SpArraySortArgument
import com.eratw.port.core.stmt.SpBarArgument
import com.eratw.port.core.stmt.SpButtonArgument
import com.eratw.port.core.stmt.SpCallArgument
import com.eratw.port.core.stmt.SpCallFArgument
import com.eratw.port.core.stmt.SpCallSharpArgument
import com.eratw.port.core.stmt.SpColorArgument
import com.eratw.port.core.stmt.SpCopyArrayArgument
import com.eratw.port.core.stmt.SpCVarSetArgument
import com.eratw.port.core.stmt.SpDtColumnOptions
import com.eratw.port.core.stmt.SpForNextArgument
import com.eratw.port.core.stmt.SpGetIntArgument
import com.eratw.port.core.stmt.SpHtmlPrint
import com.eratw.port.core.stmt.SpHtmlSplitArgument
import com.eratw.port.core.stmt.SpInputsArgument
import com.eratw.port.core.stmt.SpPowerArgument
import com.eratw.port.core.stmt.SpPrintImgArgument
import com.eratw.port.core.stmt.SpPrintShapeArgument
import com.eratw.port.core.stmt.SpPrintVArgument
import com.eratw.port.core.stmt.SpSaveDataArgument
import com.eratw.port.core.stmt.SpSaveVarArgument
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SpSetArrayArgument
import com.eratw.port.core.stmt.SpSortcharaArgument
import com.eratw.port.core.stmt.SpSplitArgument
import com.eratw.port.core.stmt.SpSwapCharaArgument
import com.eratw.port.core.stmt.SpSwapVarArgument
import com.eratw.port.core.stmt.SpTimesArgument
import com.eratw.port.core.stmt.SpTInputsArgument
import com.eratw.port.core.stmt.SpVarSetArgument
import com.eratw.port.core.stmt.SpVarsizeArgument
import com.eratw.port.core.stmt.StrAsignArgument
import com.eratw.port.core.stmt.StrDataArgument
import com.eratw.port.core.stmt.StrDoubleArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VarDeclStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.stmt.WendStmt
import com.eratw.port.core.stmt.WhileStmt

/**
 * 编码结果：容器字节 + **段 3 语句记录偏移表**（装载侧车用；无语句池时 [statementRecordOffsets] = null）。
 *
 * 偏移相对**段 3 记录区起点**（`offsets[0] = 0`，单调递增）：正是惰性语句池装载期
 * 需要的那张表。构建期顺手记下，装载期就不必把 171 万条语句重新反序列化一遍
 * （2026-10-23 真机实测省 9.5 s，见 `StmtOffsetSidecar`）。
 */
class ErbcEncoded(
    val bytes: ByteArray,
    val statementRecordOffsets: IntArray?,
    val statementRecordBytes: Int,
)

/**
 * `.erbc` 编码器（v1/v2）：内存 [Program] → 字节容器。规约正文 `specs/erbc-format.md`。
 *
 * 三条不变量（都有单测锁死）：
 * 1. **确定性**：只用「追加序」，任何 `HashMap` 都只做查表、绝不参与迭代顺序
    （见 [ErbcStringTable.intern] 与 [RecordSink.memo]）⇒ 同一 Program 两次编码逐字节相同。
 * 2. **下标保真**：池按 index 原序落盘，装载后按同序重灌，指令里的池下标永不漂移。
 * 3. **不静默降级**：写不出来的东西（关掉必需的池、v1 未定稿的构造）一律
    [ErbcEncodeException] 当场失败，绝不产出「看着对但语义已坏」的文件。
 */
object ErbcWriter {

    /** 全量编码（debug 口径：三段齐全）。 */
    fun encode(program: Program): ByteArray = encode(program, ErbcEncodeOptions())

    /**
     * 按选项编码。
     *
     * @throws ErbcEncodeException 选项声明省略某池、但程序实际需要它（见规约 §6 的自洽规则）
     */
    fun encode(
        program: Program,
        options: ErbcEncodeOptions,
    ): ByteArray = Encoder(program, options).build()

    /**
     * 同 [encode]，但顺带产出段 3 语句记录偏移表（`erbcc` 落 `program.stmt.idx` 侧车用）。
     *
     * 容器字节与 [encode] **逐字节相同**（偏移只记录，不参与写入序）。
     */
    fun encodeIndexed(
        program: Program,
        options: ErbcEncodeOptions = ErbcEncodeOptions(),
    ): ErbcEncoded = Encoder(program, options).buildEncoded()

    // ==================================================================
    // 容器组装
    // ==================================================================

    private class Encoder(
        private val program: Program,
        private val options: ErbcEncodeOptions,
    ) {
        private val version: Int = options.version
        private val sectionCount: Int = ErbcFormat.sectionCountFor(version)
        private val bodies = arrayOfNulls<ErbcSink>(if (sectionCount > 0) sectionCount else 0)

        /** 段 3 每条语句记录在记录区内的相对偏移（[writeStatements] 填；无语句池保持 null）。 */
        private var stmtRecordOffsets: IntArray? = null

        /** 段 3 记录区总字节数（侧车的粗校验位）。 */
        private var stmtRecordBytes: Int = 0

        /** 与 [build] 同一次编码，额外带回语句记录偏移表。 */
        fun buildEncoded(): ErbcEncoded = ErbcEncoded(build(), stmtRecordOffsets, stmtRecordBytes)

        fun build(): ByteArray {
            if (sectionCount < 0) {
                throw ErbcEncodeException(
                    "不支持的容器版本 $version（写侧只产出 v${ErbcFormat.VERSION_1}/v${ErbcFormat.VERSION_2}）",
                )
            }
            if (program.instructionCount == 0) throw ErbcEncodeException("空程序：指令流长度必须 > 0")
            if (program.entryFunction !in program.functions.indices) {
                throw ErbcEncodeException("入口函数下标越界: ${program.entryFunction}")
            }
            // 与 ErbcReader 的行表判界对称：reader 只认「行号表长度 == 指令条数」，
            // 写侧不先拦就会产出自己读不回的产物。
            if (options.includeLines && program.lines.size != program.instructionCount) {
                throw ErbcEncodeException(
                    "行号表长度(${program.lines.size})与指令条数(${program.instructionCount})不符",
                )
            }
            if (version < ErbcFormat.VERSION_2) rejectCallArgMetadataForV1()
            validateCodeRefs()
            writeStrings()
            writeConstants()
            writeExpressions()
            writeStatements()
            writeFunctions()
            writeCode()
            writeLines()
            writeCallArgs()
            writeParamNames()

            var offset = ErbcFormat.firstSectionOffset(sectionCount)
            val offsets = IntArray(sectionCount)
            for (i in 0 until sectionCount) {
                val body = bodies[i]
                if (body != null) {
                    offsets[i] = offset
                    offset = checkedSectionEndOffset(i, offset, body.size)
                }
            }
            val out = ErbcSink(offset)
            out.u8(ErbcFormat.MAGIC_0)
            out.u8(ErbcFormat.MAGIC_1)
            out.u8(ErbcFormat.MAGIC_2)
            out.u8(ErbcFormat.MAGIC_3)
            out.u16(version)
            out.u16(flags())
            out.u16(sectionCount)
            for (i in 0 until sectionCount) out.u32(offsets[i])
            for (i in 0 until sectionCount) bodies[i]?.let { out.put(it) }
            // 段长自校验：头 + 段表 + 各段正文必须恰好等于段表算出的总长。漏写/多写一段时
            // 段表会指向别人的正文 —— 装载侧看不出「格式错」，只会静默读到错内容（CMP-10）。
            if (out.size != offset) {
                throw ErbcEncodeException(
                    "容器总长(${out.size})与段表推算的结束偏移($offset)不符：段正文与段表不一致",
                )
            }
            return out.toByteArray()
        }

        /**
         * v1 容器没有段 7/8：程序若带调用点元数据/形参名，写出来必然丢掉「REF 与具名形参的
         * 逐位绑定」—— 属于规约 §1.6 的静默降级，直接拒写并指名要 v2。
         */
        /**
         * 跨字段不变量（与 ErbcReader 的装载期校验同款）：函数 `arity` 是被调帧 R0..R(arity-1)
         * 的实参落位数，必须 <= 帧槽数 `slotCount`。否则 VM 建帧时实参复制会越出被调帧槽区间
         * （畸形产物）。写侧先拦，避免产出「自己读不回」（ErbcReader 会拒收）的容器。
         */
        private fun validateCodeRefs() {
            for (f in program.functions) {
                if (f.arity > f.slotCount) {
                    throw ErbcEncodeException(
                        "函数 ${f.name} 的 arity(${f.arity}) 大于 slotCount(${f.slotCount})：" +
                            "实参复制会越出被调帧槽区间",
                    )
                }
            }
        }

        private fun rejectCallArgMetadataForV1() {
            val sites = program.callArgs.size
            if (sites > 0) {
                throw ErbcEncodeException(
                    "v1 容器没有调用点实参段：程序带 $sites 个调用点的逐位实参元数据" +
                        "（段 7 是 v2 新增，规约 §5.8）；要用 v2 或先清空 Program.callArgs",
                )
            }
            val named = program.functions.count { it.paramNames.isNotEmpty() }
            if (named > 0) {
                throw ErbcEncodeException(
                    "v1 容器没有形参名段：$named 个函数带头部形参名（段 8 是 v2 新增，规约 §5.9）；" +
                        "要用 v2 或先清空 FunctionInfo.paramNames",
                )
            }
        }

        private fun flags(): Int {
            var f = 0
            if (bodies[ErbcFormat.SECTION_LINES] != null) f = f or ErbcFormat.FLAG_HAS_LINES
            if (bodies[ErbcFormat.SECTION_EXPRESSIONS] != null) f = f or ErbcFormat.FLAG_HAS_EXPRESSIONS
            if (bodies[ErbcFormat.SECTION_STATEMENTS] != null) f = f or ErbcFormat.FLAG_HAS_STATEMENTS
            if (sectionCount > ErbcFormat.SECTION_CALL_ARGS && bodies[ErbcFormat.SECTION_CALL_ARGS] != null) {
                f = f or ErbcFormat.FLAG_HAS_CALL_ARGS
            }
            if (sectionCount > ErbcFormat.SECTION_PARAM_NAMES && bodies[ErbcFormat.SECTION_PARAM_NAMES] != null) {
                f = f or ErbcFormat.FLAG_HAS_PARAM_NAMES
            }
            return f
        }

        private fun writeStrings() {
            val n = program.stringCount
            if (n == 0) return
            val table = ErbcStringTable()
            val out = ErbcSink(64)
            // 逐条重新 intern：内容相同的字符串在源池里本就不可能重复（addString 按值去重），
            // 因此 intern 的追加序 == 原池下标序，下标 1:1 保留。
            for (i in 0 until n) table.intern(program.string(i))
            table.writeTo(out)
            bodies[ErbcFormat.SECTION_STRINGS] = out
        }

        private fun writeConstants() {
            val n = program.constantCount
            if (n == 0) return
            val out = ErbcSink(8 + n * 8)
            out.u32(n)
            out.u32(0) // doubleCount：v1 恒 0（ConstantPool 只有 Long 池；规约 §5.2）
            for (i in 0 until n) out.i64(program.constant(i))
            bodies[ErbcFormat.SECTION_CONSTANTS] = out
        }

        private fun writeExpressions() {
            val n = program.exprCount
            if (n > 0 && !options.includeExpressions) {
                throw ErbcEncodeException("选项声明不带表达式池，但程序有 $n 条表达式（_EVAL_* 会全部失效）")
            }
            if (n == 0 || !options.includeExpressions) return
            val sink = ErbcSink(128)
            val rootIdx = IntArray(n)
            val arena = RecordSink(ErbcStringTable(), sink)
            for (i in 0 until n) rootIdx[i] = arena.intern(program.expr(i))
            val table = ErbcSink(32)
            arena.strings.writeTo(table)
            val out = ErbcSink(8 + table.size + sink.size + 4 * n)
            out.u32(arena.nodeCount)
            out.u32(n)
            out.put(table)
            out.put(sink)
            for (i in 0 until n) out.u32(rootIdx[i])
            bodies[ErbcFormat.SECTION_EXPRESSIONS] = out
        }

        private fun writeStatements() {
            val n = program.statementCount
            if (n > 0 && !options.includeStatements) {
                throw ErbcEncodeException(
                    "选项声明不带语句池，但程序有 $n 条语句（marker 的 a 会全部越界；" +
                        "数据节以 Statement 实例为键，缺池即静默失效）",
                )
            }
            if (n == 0 || !options.includeStatements) return
            val nodes = ErbcSink(128)
            val entries = ErbcSink(128)
            val arena = RecordSink(ErbcStringTable(), nodes)
            val offsets = IntArray(n)
            for (i in 0 until n) {
                // 偏移 = 本条记录写入前的位置（相对记录区起点）：装载期惰性语句池直接用它，
                // 不必再逐条反序列化一遍（2026-10-23 装载提速，见 StmtOffsetSidecar）。
                offsets[i] = entries.size
                arena.writeStatement(entries, program.statement(i))
            }
            stmtRecordOffsets = offsets
            stmtRecordBytes = entries.size
            val table = ErbcSink(32)
            arena.strings.writeTo(table)
            val out = ErbcSink(8 + table.size + nodes.size + entries.size)
            out.u32(arena.nodeCount)
            out.u32(n)
            out.put(table)
            out.put(nodes)
            out.put(entries)
            bodies[ErbcFormat.SECTION_STATEMENTS] = out
        }

        private fun writeFunctions() {
            val funcs = program.functions
            if (funcs.isEmpty()) throw ErbcEncodeException("函数表为空")
            // 读侧 validate 的判界逐条前移：写侧不拦就会产出自己读不回的产物。
            if (program.globalSlots < 0) {
                throw ErbcEncodeException("全局槽数为负: ${program.globalSlots}")
            }
            val instructionCount = program.instructionCount
            val seenNames = HashSet<String>(funcs.size)
            val table = ErbcStringTable()
            val rec = ErbcSink(64)
            for (f in funcs) {
                if (!seenNames.add(f.name)) throw ErbcEncodeException("函数重复声明: ${f.name}")
                if (f.entryPc !in 0 until instructionCount) {
                    throw ErbcEncodeException(
                        "函数 ${f.name} 的 entryPc=${f.entryPc} 越界（指令数 $instructionCount）",
                    )
                }
                if (f.arity < 0 || f.slotCount < 0) {
                    throw ErbcEncodeException(
                        "函数 ${f.name} 的 arity/slotCount 为负: ${f.arity}/${f.slotCount}",
                    )
                }
                if (f.labelNames.size != f.labelPcs.size) {
                    throw ErbcEncodeException("函数 ${f.name} 的标签平行表长度不一致")
                }
                for (i in f.labelPcs.indices) {
                    if (f.labelPcs[i] !in 0 until instructionCount) {
                        throw ErbcEncodeException(
                            "函数 ${f.name} 的标签 \$${f.labelNames[i]} 的 pc=${f.labelPcs[i]} 越界（指令数 $instructionCount）",
                        )
                    }
                }
                val nameIdx = table.intern(f.name)
                rec.u32(nameIdx)
                rec.i32(f.entryPc)
                rec.i32(f.arity)
                rec.i32(f.slotCount)
                rec.u32(0) // 函数标志：v1 恒 0（FunctionInfo 无事件/方法位，规约 §5.5 + ERBC-B）
                rec.u32(f.labelNames.size)
                for (i in 0 until f.labelNames.size) {
                    rec.u32(table.intern(f.labelNames[i]))
                    rec.i32(f.labelPcs[i])
                }
            }
            val tableSink = ErbcSink(32)
            table.writeTo(tableSink)
            val out = ErbcSink(12 + tableSink.size + rec.size)
            out.u32(funcs.size)
            out.u32(program.entryFunction)
            out.u32(program.globalSlots)
            out.put(tableSink)
            out.put(rec)
            bodies[ErbcFormat.SECTION_FUNCTIONS] = out
        }

        private fun writeCode() {
            val out = ErbcSink(4 + program.code.size * 4)
            out.u32(program.instructionCount)
            for (w in program.code) out.u32(w)
            bodies[ErbcFormat.SECTION_CODE] = out
        }

        private fun writeLines() {
            if (!options.includeLines) return
            val out = ErbcSink(8 + program.lines.size * 4)
            out.u32(0) // fileTableCount：v1 恒 0（Program.lines 只有行号，规约 §5.7）
            out.u32(program.lines.size)
            for (l in program.lines) out.i32(l)
            bodies[ErbcFormat.SECTION_LINES] = out
        }

        /**
         * 段 7：调用点实参表（规约 §5.8）。
         *
         * 站点按 **pc 升序**写出（`Program.callArgs` 是 `Map`，迭代序随实现而变；升序是对
         * 「同一 Program 两次编码逐字节相同」的保证）。每个站点内部的实参再按 `position`
         * 升序规范化 —— 原版逐位绑定只认位置，顺序不影响语义，排序只消除字节抖动。
         */
        private fun writeCallArgs() {
            if (version < ErbcFormat.VERSION_2) return
            val map = program.callArgs
            if (map.isEmpty()) return
            val sites = map.entries.sortedBy { it.key }
            val names = ErbcStringTable()
            val rec = ErbcSink(64)
            var totalArgs = 0
            for ((pc, rawArgs) in sites) {
                if (pc < 0 || pc >= program.instructionCount) {
                    throw ErbcEncodeException("调用点 pc=$pc 越界（指令数 ${program.instructionCount}）")
                }
                if (rawArgs.isEmpty()) {
                    throw ErbcEncodeException("调用点 pc=$pc 的实参表为空（v2 只登记非空表，规约 §5.8）")
                }
                val args = rawArgs.sortedBy { it.position }
                var prev = -1
                for (a in args) {
                    if (a.position < 0) {
                        throw ErbcEncodeException("调用点 pc=$pc 的实参位为负: ${a.position}")
                    }
                    if (a.position == prev) {
                        throw ErbcEncodeException("调用点 pc=$pc 的实参位重复: ${a.position}（逐位绑定要求位置唯一）")
                    }
                    if (a.bucket < 0) {
                        throw ErbcEncodeException("调用点 pc=$pc 的实参桶号为负: ${a.bucket}")
                    }
                    prev = a.position
                }
                rec.u32(pc)
                rec.u32(args.size)
                for (a in args) {
                    rec.u32(a.position)
                    rec.u32(a.bucket)
                    var f = 0
                    if (a.isStr) f = f or ErbcFormat.CallArgFlags.IS_STR
                    if (a.present) f = f or ErbcFormat.CallArgFlags.PRESENT
                    rec.u8(f)
                    rec.u32(if (a.name == null) NONE_REF else names.intern(a.name))
                }
                totalArgs += args.size
            }
            val table = ErbcSink(32)
            names.writeTo(table)
            val out = ErbcSink(8 + table.size + rec.size)
            out.u32(sites.size)
            out.u32(totalArgs)
            out.put(table)
            out.put(rec)
            bodies[ErbcFormat.SECTION_CALL_ARGS] = out
        }

        /**
         * 段 8：函数头部形参名表（规约 §5.9）。
         *
         * 记录序 = 函数表记录序（下标即函数下标），`funcCount` 与段 4 逐字相等是装载期的
         * 交叉校验点。全部函数都没有形参名时整段省略（标志位清零 = 显式声明「不带」）。
         */
        private fun writeParamNames() {
            if (version < ErbcFormat.VERSION_2) return
            val funcs = program.functions
            if (funcs.none { it.paramNames.isNotEmpty() }) return
            val names = ErbcStringTable()
            val rec = ErbcSink(64)
            for (f in funcs) {
                rec.u32(f.paramNames.size)
                for (n in f.paramNames) rec.u32(names.intern(n))
            }
            val table = ErbcSink(32)
            names.writeTo(table)
            val out = ErbcSink(8 + table.size + rec.size)
            out.u32(funcs.size)
            out.put(table)
            out.put(rec)
            bodies[ErbcFormat.SECTION_PARAM_NAMES] = out
        }
    }
}

/**
 * 段正文结束偏移的判界 —— [ErbcWriter] 容器组装里**唯一**的偏移累加点（CMP-10）。
 *
 * 段表条目是 u32、装载侧按 `Int` 解释（`ProgramLoader.kt` 的 `header.offsets[i]`、
 * `MappedErbc.offsetOf`），所以：
 *
 * * 结束偏移越过 [Int.MAX_VALUE] ⇒ 「写得出、读不回」的产物（读侧看到负偏移，
 *   或按 u32 解释成一个 4GB 级的区间）；
 * * `offset + bodySize` 用 Int 加法还会**回绕**到一个合法的小偏移上，让后面的段
 *   全部静默错位 —— 比抛异常难查得多；
 * * `bodySize < 0` 只可能来自 [ErbcSink] 的尺寸计数回绕，同样不能写出去。
 *
 * @param sectionId 段号（报错点名，名字取 [ErbcFormat.SECTION_NAMES]）
 * @param offset 本段起始偏移（首段 = [ErbcFormat.firstSectionOffset]）
 * @param bodySize 本段正文字节数（`ErbcSink.size`）
 * @return 下一段的起始偏移（= 本段结束偏移）
 */
internal fun checkedSectionEndOffset(sectionId: Int, offset: Int, bodySize: Int): Int {
    val name = ErbcFormat.SECTION_NAMES.getOrElse(sectionId) { "SECTION$sectionId" }
    if (bodySize < 0) {
        throw ErbcEncodeException("段 $sectionId（$name）正文长度出现负值($bodySize)：编码器尺寸计数已回绕，拒绝写出畸形容器")
    }
    if (offset < 0) {
        throw ErbcEncodeException("段 $sectionId（$name）起始偏移为负($offset)：上一段长度累加已回绕，拒绝写出畸形容器")
    }
    val end = offset.toLong() + bodySize.toLong()
    if (end > Int.MAX_VALUE.toLong()) {
        throw ErbcEncodeException(
            "段 $sectionId（$name）正文结束偏移 $end 超过容器可寻址上限 ${Int.MAX_VALUE}" +
                "（段表是 u32、装载侧按 Int 解释）：必须分片（切片协议见 specs/erbc-format.md 第 7 节）",
        )
    }
    return end.toInt()
}

/**
 * 记录编码器（表达式节点表 + 语句/参数记录）。
 *
 * 一个实例服务**一个段**：字符串池与节点表都是段内局部的，因为
 * 全局字符串池的下标是指令操作数（`_SET_STR` 的 b），往里追加会整体漂移。
 */
private class RecordSink(
    val strings: ErbcStringTable,
    private val nodes: ErbcSink,
) {
    /** 结构相等的子树共享一个节点（[Expr] 是不可变 data class，共享无损语义）。 */
    private val memo = HashMap<Expr, Int>()

    /** 已发出的节点数 == memo 大小；后序发射保证「子节点下标 < 父节点下标」。 */
    val nodeCount: Int get() = memo.size

    fun str(s: String): Int = strings.intern(s)

    fun strOpt(s: String?): Int = if (s == null) NONE_REF else strings.intern(s)

    /**
     * 后序发射一个表达式节点，返回其节点下标。
     *
     * 顺序纪律（读侧的零递归前提）：**先把子树全部 intern**（它们依次追加进 [nodes]），
     * 再给自己分配下标、再把本节点记录追加进 [nodes]。所以「子节点下标 < 父节点下标」
     * 且「子节点记录字节在前」两条同时成立。本节点的记录先落在临时汇里，
     * 就是为了不在子节点之前占位。
     */
    fun intern(e: Expr): Int {
        memo[e]?.let { return it }
        val rec = ErbcSink(16)
        writeNode(rec, e)
        val index = memo.size
        memo[e] = index
        nodes.put(rec)
        return index
    }

    fun internOpt(e: Expr?): Int = if (e == null) NONE_REF else intern(e)

    /** 期望 VarRef 的字段（`SpSetArgument.dest` 之类在类型上就是 VarRef，无需运行时判断）。 */
    fun ref(e: VarRef): Int = intern(e)

    private fun operandTypeWire(t: OperandType): Int =
        if (t == OperandType.INT) WireEnum.OPERAND_INT else WireEnum.OPERAND_STR

    private fun operatorWire(op: com.eratw.port.core.expr.OperatorCode): Int = strings.intern(op.name)

    private fun exprListWire(
        out: ErbcSink,
        list: List<Expr>,
    ) {
        out.u32(list.size)
        for (e in list) out.u32(intern(e))
    }

    private fun exprListOptWire(
        out: ErbcSink,
        list: List<Expr?>,
    ) {
        out.u32(list.size)
        for (e in list) out.u32(internOpt(e))
    }

    private fun mixedList(
        out: ErbcSink,
        param: List<MixedIntegerExprTerm>,
    ) {
        out.u32(param.size)
        for (m in param) {
            out.u32(internOpt(m.num))
            out.bool(m.isPx)
        }
    }

    private fun caseList(
        out: ErbcSink,
        cases: List<CaseExpression>,
    ) {
        out.u32(cases.size)
        for (c in cases) {
            out.u8(
                when (c.caseType) {
                    CaseExpression.CaseType.NORMAL -> WireEnum.CASE_NORMAL
                    CaseExpression.CaseType.TO -> WireEnum.CASE_TO
                    CaseExpression.CaseType.IS -> WireEnum.CASE_IS
                },
            )
            out.u32(internOpt(c.leftTerm))
            out.u32(internOpt(c.rightTerm))
            out.u32(operatorWire(c.operator))
        }
    }

    private fun sortWire(order: SortOrder): Int =
        when (order) {
            SortOrder.UNDEF -> WireEnum.SORT_UNDEF
            SortOrder.ASCENDING -> WireEnum.SORT_ASCENDING
            SortOrder.DESENDING -> WireEnum.SORT_DESCENDING
        }

    /** `Argument` 基类的可变缓存字段（原版解析期常量折叠；spec: Argument.cs:7-11）。 */
    private fun constPreamble(
        out: ErbcSink,
        a: Argument,
    ) {
        val hasPayload = a.isConst || a.constStr.isNotEmpty() || a.constInt != 0L
        var header = 0
        if (a.isConst) header = header or 1
        if (hasPayload) header = header or 2
        out.u8(header)
        if (hasPayload) {
            out.u32(str(a.constStr))
            out.i64(a.constInt)
        }
    }

    private fun record(
        out: ErbcSink,
        tag: Int,
        a: Argument,
        body: (ErbcSink) -> Unit,
    ) {
        out.u8(tag)
        constPreamble(out, a)
        body(out)
    }

    // ------------------------------------------------------------------
    // 表达式节点
    // ------------------------------------------------------------------

    /**
     * 把**本节点**的记录写进 [out]（子树则经 [intern] 追加进 [nodes]，先于本节点记录落盘）。
     *
     * `when (e)` 是 sealed 穷尽分支：新增 [Expr] 实现而不处理会在编译期失败，
     * 不会出现「静默少编码一类节点」的漏。
     */
    private fun writeNode(
        out: ErbcSink,
        e: Expr,
    ) {
        when (e) {
            is IntLiteral -> {
                out.u8(ExprTag.INT_LITERAL)
                out.i64(e.value)
                pos(out, e)
            }

            is StrLiteral -> {
                out.u8(ExprTag.STR_LITERAL)
                out.u32(str(e.value))
                pos(out, e)
            }

            is NullTerm -> {
                out.u8(ExprTag.NULL_TERM)
                out.bool(e.isInt)
                pos(out, e)
            }

            is VarRef -> {
                out.u8(ExprTag.VAR_REF)
                out.u32(str(e.name))
                out.u32(strOpt(e.subKey))
                out.u8(operandTypeWire(e.operandType))
                var flags = 0
                if (e.isNoArg) flags = flags or 1
                if (e.isConst) flags = flags or 2
                out.u8(flags)
                exprListWire(out, e.indices)
                pos(out, e)
            }

            is KeywordIndexArg -> {
                out.u8(ExprTag.KEYWORD_INDEX_ARG)
                out.u32(intern(e.expr))
                out.u32(str(e.varName))
                out.i32(e.dim)
                pos(out, e)
            }

            is FuncCall -> {
                out.u8(ExprTag.FUNC_CALL)
                out.u32(str(e.name))
                out.u8(operandTypeWire(e.operandType))
                exprListOptWire(out, e.args)
                pos(out, e)
            }

            is Unary -> {
                out.u8(ExprTag.UNARY)
                out.u32(operatorWire(e.op))
                out.u32(intern(e.operand))
                pos(out, e)
            }

            is UnaryAfter -> {
                out.u8(ExprTag.UNARY_AFTER)
                out.u32(operatorWire(e.op))
                out.u32(intern(e.operand))
                pos(out, e)
            }

            is Binary -> {
                out.u8(ExprTag.BINARY)
                out.u32(operatorWire(e.op))
                out.u32(intern(e.left))
                out.u32(intern(e.right))
                out.u8(operandTypeWire(e.operandType))
                pos(out, e)
            }

            is Ternary -> {
                out.u8(ExprTag.TERNARY)
                out.u32(intern(e.cond))
                out.u32(intern(e.ifTrue))
                out.u32(intern(e.ifFalse))
                out.u8(operandTypeWire(e.operandType))
                pos(out, e)
            }

            is FormStringTerm -> {
                out.u8(ExprTag.FORM_STRING)
                out.u32(e.parts.size)
                for (p in e.parts) {
                    when (p) {
                        is FormPart.Text -> {
                            out.u8(ErbcFormat.FormPartTag.TEXT)
                            out.u32(str(p.str))
                        }

                        is FormPart.Embedded -> {
                            out.u8(ErbcFormat.FormPartTag.EMBEDDED)
                            out.u32(intern(p.ast))
                            when (val c = p.conversion) {
                                is FormConversion.Curly -> {
                                    out.u8(ErbcFormat.FormConversionTag.CURLY)
                                    out.u32(internOpt(c.width))
                                    out.bool(c.alignLeft)
                                }

                                is FormConversion.Percent -> {
                                    out.u8(ErbcFormat.FormConversionTag.PERCENT)
                                    out.u32(internOpt(c.width))
                                    out.bool(c.alignLeft)
                                }

                                is FormConversion.YenAt -> {
                                    out.u8(ErbcFormat.FormConversionTag.YEN_AT)
                                    out.u32(intern(c.ifTrue))
                                    out.u32(intern(c.ifFalse))
                                }
                            }
                        }
                    }
                }
                pos(out, e)
            }
        }
    }

    /** 行/列是 data class 的组成字段，必须原样往返（否则结构相等去重的口径会变）。 */
    private fun pos(
        out: ErbcSink,
        e: Expr,
    ) {
        out.i32(e.line)
        out.i32(e.col)
    }

    // ------------------------------------------------------------------
    // 参数
    // ------------------------------------------------------------------

    fun writeArgument(
        out: ErbcSink,
        a: Argument,
    ) {
        when (a) {
            is VoidArgument -> record(out, ArgTag.VOID, a) { }
            is ExpressionArgument -> record(out, ArgTag.EXPRESSION, a) { it.u32(internOpt(a.term)) }
            is ExpressionArrayArgument -> record(out, ArgTag.EXPRESSION_ARRAY, a) { exprListOptWire(it, a.terms) }
            is ExpressionsArgument -> record(out, ArgTag.EXPRESSIONS, a) {
                it.u32(a.types.size)
                if (a.types.size != a.terms.size) {
                    throw ErbcEncodeException("ExpressionsArgument 的类型表与项表长度不一致")
                }
                for (i in a.types.indices) {
                    it.u8(
                        when (a.types[i]) {
                            null -> WireEnum.TYPE_NULL
                            ArgValueType.INT -> WireEnum.TYPE_INT
                            ArgValueType.STR -> WireEnum.TYPE_STR
                            ArgValueType.VOID -> WireEnum.TYPE_VOID
                        },
                    )
                    it.u32(internOpt(a.terms[i]))
                }
            }

            is IntAsignArgument -> record(out, ArgTag.INT_ASIGN, a) {
                it.u32(str(a.name))
                intArray(it, a.lengths)
                it.u32(internOpt(a.exp))
            }

            is StrAsignArgument -> record(out, ArgTag.STR_ASIGN, a) {
                it.u32(str(a.name))
                intArray(it, a.lengths)
                it.u32(strOpt(a.value))
            }

            is BitArgument -> record(out, ArgTag.BIT, a) {
                it.u32(ref(a.dest))
                exprListWire(it, a.terms)
            }

            is CaseArgument -> record(out, ArgTag.CASE, a) { caseList(it, a.caseExps) }
            is MethodArgument -> record(out, ArgTag.METHOD, a) { it.u32(internOpt(a.methodTerm)) }
            is PrintDataArgument -> record(out, ArgTag.PRINT_DATA, a) { it.u32(internOpt(a.dest)) }
            is RefArgument -> record(out, ArgTag.REF, a) {
                it.u32(str(a.refName))
                it.u32(strOpt(a.srcName))
                it.u32(internOpt(a.srcTerm))
                it.bool(a.refByName)
            }

            is StrDataArgument -> record(out, ArgTag.STR_DATA, a) { it.u32(internOpt(a.dest)) }
            is SpArrayControlArgument -> record(out, ArgTag.SP_ARRAY_CONTROL, a) {
                it.u32(ref(a.dest))
                it.u32(internOpt(a.num1))
                it.u32(internOpt(a.num2))
            }

            is SpArrayShiftArgument -> record(out, ArgTag.SP_ARRAY_SHIFT, a) {
                it.u32(ref(a.dest))
                it.u32(internOpt(a.num1))
                it.u32(internOpt(a.num2))
                it.u32(internOpt(a.num3))
                it.u32(internOpt(a.num4))
            }

            is SpArraySortArgument -> record(out, ArgTag.SP_ARRAY_SORT, a) {
                it.u32(ref(a.dest))
                it.u8(sortWire(a.order))
                it.u32(internOpt(a.num1))
                it.u32(internOpt(a.num2))
            }

            is SpBarArgument -> record(out, ArgTag.SP_BAR, a) {
                it.u32(internOpt(a.value))
                it.u32(internOpt(a.max))
                it.u32(internOpt(a.length))
            }

            is SpButtonArgument -> record(out, ArgTag.SP_BUTTON, a) {
                it.u32(internOpt(a.printStrTerm))
                it.u32(internOpt(a.buttonWord))
            }

            is SpCVarSetArgument -> record(out, ArgTag.SP_CVAR_SET, a) {
                it.u32(ref(a.dest))
                it.u32(internOpt(a.index))
                it.u32(internOpt(a.term))
                it.u32(internOpt(a.start))
                it.u32(internOpt(a.end))
            }

            is SpCallArgument -> record(out, ArgTag.SP_CALL, a) { callTriple(it, a.funcNameTerm, a.subNames, a.rowArgs) }
            is SpCallFArgument -> record(out, ArgTag.SP_CALLF, a) { callTriple(it, a.funcNameTerm, a.subNames, a.rowArgs) }
            is SpCallSharpArgument -> record(out, ArgTag.SP_CALL_SHARP, a) {
                callTriple(it, a.funcNameTerm, a.subNames, a.rowArgs)
            }

            is SpColorArgument -> record(out, ArgTag.SP_COLOR, a) {
                it.u32(internOpt(a.r))
                it.u32(internOpt(a.g))
                it.u32(internOpt(a.b))
                it.u32(internOpt(a.rgb))
            }

            is SpCopyArrayArgument -> record(out, ArgTag.SP_COPY_ARRAY, a) {
                it.u32(internOpt(a.varName1))
                it.u32(internOpt(a.varName2))
            }

            is SpDtColumnOptions -> record(out, ArgTag.SP_DT_COLUMN_OPTIONS, a) {
                it.u32(internOpt(a.dt))
                it.u32(internOpt(a.column))
                it.u32(a.options.size)
                for (o in a.options) {
                    it.u8(
                        when (o) {
                            SpDtColumnOptions.DTOption.DEFAULT -> WireEnum.DT_DEFAULT
                        },
                    )
                }
                exprListOptWire(it, a.values)
            }

            is SpForNextArgument -> record(out, ArgTag.SP_FOR_NEXT, a) {
                it.u32(ref(a.cnt))
                it.u32(internOpt(a.start))
                it.u32(internOpt(a.end))
                it.u32(internOpt(a.step))
            }

            is SpGetIntArgument -> record(out, ArgTag.SP_GET_INT, a) { it.u32(ref(a.dest)) }
            is SpHtmlPrint -> record(out, ArgTag.SP_HTML_PRINT, a) {
                it.u32(internOpt(a.str))
                it.u32(internOpt(a.opt))
            }

            is SpHtmlSplitArgument -> record(out, ArgTag.SP_HTML_SPLIT, a) {
                it.u32(internOpt(a.targetStr))
                it.u32(str(a.destVarName))
                it.u32(internOpt(a.num))
            }

            is SpInputsArgument -> record(out, ArgTag.SP_INPUTS, a) {
                it.u32(internOpt(a.def))
                it.u32(internOpt(a.mouse))
                it.u32(internOpt(a.canSkip))
            }

            is SpPowerArgument -> record(out, ArgTag.SP_POWER, a) {
                it.u32(ref(a.dest))
                it.u32(internOpt(a.x))
                it.u32(internOpt(a.y))
            }

            is SpPrintImgArgument -> record(out, ArgTag.SP_PRINT_IMG, a) {
                it.u32(internOpt(a.name))
                it.u32(internOpt(a.nameb))
                it.u32(internOpt(a.namem))
                val p = a.param
                if (p == null) it.u32(NONE_REF) else mixedList(it, p)
            }

            is SpPrintShapeArgument -> record(out, ArgTag.SP_PRINT_SHAPE, a) { mixedList(it, a.param) }
            is SpPrintVArgument -> record(out, ArgTag.SP_PRINT_V, a) { exprListWire(it, a.terms) }
            is SpSaveDataArgument -> record(out, ArgTag.SP_SAVE_DATA, a) {
                it.u32(internOpt(a.target))
                it.u32(internOpt(a.strExpression))
            }

            is SpSaveVarArgument -> record(out, ArgTag.SP_SAVE_VAR, a) {
                it.u32(internOpt(a.term))
                it.u32(internOpt(a.savMes))
                stringList(it, a.varNames)
            }

            is SpSetArgument -> record(out, ArgTag.SP_SET, a) {
                it.u32(ref(a.dest))
                it.u32(internOpt(a.term))
                it.bool(a.addConst)
            }

            is SpSetArrayArgument -> record(out, ArgTag.SP_SET_ARRAY, a) {
                it.u32(ref(a.dest))
                exprListOptWire(it, a.terms)
                val ci = a.constIntList
                if (ci == null) it.u32(NONE_REF) else {
                    it.u32(ci.size)
                    for (v in ci) it.i64(v)
                }
                val cs = a.constStrList
                if (cs == null) it.u32(NONE_REF) else {
                    it.u32(cs.size)
                    for (v in cs) it.u32(str(v))
                }
            }

            is SpSortcharaArgument -> record(out, ArgTag.SP_SORTCHARA, a) {
                it.u32(ref(a.sortKey))
                it.u8(sortWire(a.sortOrder))
            }

            is SpSplitArgument -> record(out, ArgTag.SP_SPLIT, a) {
                it.u32(internOpt(a.targetStr))
                it.u32(internOpt(a.split))
                it.u32(str(a.destVarName))
                it.u32(internOpt(a.num))
            }

            is SpSwapCharaArgument -> record(out, ArgTag.SP_SWAP_CHARA, a) {
                it.u32(internOpt(a.x))
                it.u32(internOpt(a.y))
            }

            is SpSwapVarArgument -> record(out, ArgTag.SP_SWAP_VAR, a) {
                it.u32(ref(a.var1))
                it.u32(ref(a.var2))
            }

            is SpTInputsArgument -> record(out, ArgTag.SP_TINPUTS, a) {
                it.u32(internOpt(a.time))
                it.u32(internOpt(a.def))
                it.u32(internOpt(a.disp))
                it.u32(internOpt(a.timeout))
                it.u32(internOpt(a.mouse))
                it.u32(internOpt(a.canSkip))
            }

            is SpTimesArgument -> record(out, ArgTag.SP_TIMES, a) {
                it.u32(ref(a.dest))
                it.f64(a.doubleValue)
            }

            is SpVarSetArgument -> record(out, ArgTag.SP_VAR_SET, a) {
                it.u32(ref(a.dest))
                it.u32(internOpt(a.term))
                it.u32(internOpt(a.start))
                it.u32(internOpt(a.end))
            }

            is SpVarsizeArgument -> record(out, ArgTag.SP_VARSIZE, a) { it.u32(str(a.varName)) }
            is StrDoubleArgument -> record(out, ArgTag.STR_DOUBLE, a) {
                it.u32(internOpt(a.term))
                it.f64(a.doubleValue)
            }

            is HtmlPrintArgument -> record(out, ArgTag.HTML_PRINT_LINE_END, a) {
                it.u32(internOpt(a.term))
                it.u32(internOpt(a.lineEnd))
            }
        }
    }

    private fun callTriple(
        out: ErbcSink,
        nameTerm: Expr?,
        subNames: List<Expr?>,
        rowArgs: List<Expr?>,
    ) {
        out.u32(internOpt(nameTerm))
        exprListOptWire(out, subNames)
        exprListOptWire(out, rowArgs)
    }

    private fun intArray(
        out: ErbcSink,
        v: IntArray,
    ) {
        out.u32(v.size)
        for (x in v) out.i32(x)
    }

    private fun stringList(
        out: ErbcSink,
        v: List<String>,
    ) {
        out.u32(v.size)
        for (x in v) out.u32(str(x))
    }

    // ------------------------------------------------------------------
    // 语句
    // ------------------------------------------------------------------

    /**
     * 语句记录：`u8 类标签 | i32 line | u32 raw | u32 code 名 | 类字段… | 参数记录`。
     *
     * `code` 名对**每一类**都写：派生 code 的类（`AssignStmt` 恒 `SET`、`IfStmt` 由
     * `single` 决定、`PrintStmt` 由 `print.code` 决定）在装载侧要逐字校验回来，
     * 类表与语句身份一旦错位就是数据节事故，必须当场报错而不是静默。
     *
     * **分支顺序即语义**：`Statement` 是 sealed 层级，`PrintStmt`/`DataStmt`/`IfStmt` 等都是
     * [InstructionStmt] 的子类，`when` 取第一个命中分支 ⇒ `is InstructionStmt` 必须排在最后
     * （与编译器 `ErbProgramCompiler.kt:579-603` 的同一纪律：兜底分支在所有具体类之后）。
     */
    fun writeStatement(
        out: ErbcSink,
        s: Statement,
    ) {
        when (s) {
            is PrintStmt -> stmtHead(out, StmtTag.PRINT, s) { o -> printSpec(o, s.print) }
            is PrintDataStmt -> stmtHead(out, StmtTag.PRINT_DATA, s) { }
            is AssignStmt -> stmtHead(out, StmtTag.ASSIGN, s) { o ->
                o.u32(ref(s.dest))
                o.u32(strings.intern(s.op.name))
            }

            is JumpStmt -> stmtHead(out, StmtTag.JUMP, s) { o -> o.u32(strOpt(s.label)) }
            is CallStmt -> stmtHead(out, StmtTag.CALL, s) { o -> o.u32(strOpt(s.label)) }
            is CallFStmt -> stmtHead(out, StmtTag.CALL_F, s) { o -> o.u32(strOpt(s.functionName)) }
            is CallSharpStmt -> stmtHead(out, StmtTag.CALL_SHARP, s) { }
            is MethodStmt -> stmtHead(out, StmtTag.METHOD, s) { o -> o.u32(str(s.methodName)) }
            is VarDeclStmt -> stmtHead(out, StmtTag.VAR_DECL, s) { }
            is IfStmt -> stmtHead(out, StmtTag.IF, s) { o ->
                o.u32(intern(s.condition))
                o.bool(s.single)
            }

            is ElseIfStmt -> stmtHead(out, StmtTag.ELSE_IF, s) { o -> o.u32(intern(s.condition)) }
            is ElseStmt -> stmtHead(out, StmtTag.ELSE, s) { }
            is EndIfStmt -> stmtHead(out, StmtTag.END_IF, s) { }
            is SelectCaseStmt -> stmtHead(out, StmtTag.SELECT_CASE, s) { o -> o.u32(intern(s.target)) }
            is CaseStmt -> stmtHead(out, StmtTag.CASE, s) { o -> caseList(o, s.cases) }
            is CaseElseStmt -> stmtHead(out, StmtTag.CASE_ELSE, s) { }
            is EndSelectStmt -> stmtHead(out, StmtTag.END_SELECT, s) { }
            is ForStmt -> stmtHead(out, StmtTag.FOR, s) { }
            is NextStmt -> stmtHead(out, StmtTag.NEXT, s) { }
            is RepeatStmt -> stmtHead(out, StmtTag.REPEAT, s) { }
            is RendStmt -> stmtHead(out, StmtTag.REND, s) { }
            is WhileStmt -> stmtHead(out, StmtTag.WHILE, s) { o -> o.u32(intern(s.condition)) }
            is WendStmt -> stmtHead(out, StmtTag.WEND, s) { }
            is DoStmt -> stmtHead(out, StmtTag.DO, s) { }
            is LoopStmt -> stmtHead(out, StmtTag.LOOP, s) { o -> o.u32(internOpt(s.condition)) }
            is CatchStmt -> stmtHead(out, StmtTag.CATCH, s) { }
            is EndCatchStmt -> stmtHead(out, StmtTag.END_CATCH, s) { }
            is DataListStmt -> stmtHead(out, StmtTag.DATA_LIST, s) { }
            is EndBlockStmt -> stmtHead(out, StmtTag.END_BLOCK, s) { }
            is FlowStmt -> stmtHead(out, StmtTag.FLOW, s) { }
            is ReturnStmt -> stmtHead(out, StmtTag.RETURN, s) { o -> exprListOptWire(o, s.values) }
            is ReturnFStmt -> stmtHead(out, StmtTag.RETURN_F, s) { o -> o.u32(internOpt(s.value)) }
            is FuncLabelStmt -> stmtHead(out, StmtTag.FUNC_LABEL, s) { o ->
                o.u32(str(s.name))
                stringList(o, s.paramNames)
                o.u8(ArgTag.NONE)
            }

            is GotoLabelStmt -> stmtHead(out, StmtTag.GOTO_LABEL, s) { o ->
                o.u32(str(s.name))
                o.u8(ArgTag.NONE)
            }

            is SharpStmt -> stmtHead(out, StmtTag.SHARP, s) { o ->
                if (s.tokens.isNotEmpty()) {
                    throw ErbcEncodeException(
                        "SharpStmt 携带词法 Token（${s.tokens.size} 个）：v1 未定稿词法层落盘，" +
                            "见 specs/erbc-format.md §9「v1 未覆盖」",
                    )
                }
                o.u32(str(s.keyword))
                o.u32(s.values.size)
                for (v in s.values) o.i64(v)
                o.u32(strOpt(s.name))
                o.bool(s.isString)
                o.u8(ArgTag.NONE)
            }

            is DataStmt -> stmtHead(out, StmtTag.DATA, s) { o -> o.u32(str(s.text)) }
            is InstructionStmt -> stmtHead(out, StmtTag.INSTRUCTION, s) { }
        }
    }

    private fun stmtHead(
        out: ErbcSink,
        tag: Int,
        s: Statement,
        body: (ErbcSink) -> Unit,
    ) {
        out.u8(tag)
        out.i32(s.line)
        out.u32(str(s.raw))
        out.u32(str(s.code.name))
        body(out)
        // 派生 args 的三类在上面自己写了 ArgTag.NONE；其余落真实参数记录。
        if (s !is FuncLabelStmt && s !is GotoLabelStmt && s !is SharpStmt) writeArgument(out, s.args)
    }

    /**
     * PRINT 家族后缀标志。`name` 不落盘：`PrintSpec` 只由 `PrintSpec.parse(code)` 产生
     * （PrintSpec.kt:44-45 `val name = code.name`），装载侧按 code 名回填，避免两源。
     */
    private fun printSpec(
        out: ErbcSink,
        p: PrintSpec,
    ) {
        if (p.name != p.code.name) {
            throw ErbcEncodeException("PrintSpec.name(${p.name}) 与 code.name(${p.code.name}) 不一致，v1 无法表达")
        }
        out.u32(strings.intern(p.argType.name))
        var bits = 0
        if (p.isLineEnd) bits = bits or 0x001
        if (p.isSingleLine) bits = bits or 0x002
        if (p.isPrintV) bits = bits or 0x004
        if (p.isS) bits = bits or 0x008
        if (p.isForms) bits = bits or 0x010
        if (p.isC) bits = bits or 0x020
        if (p.isLC) bits = bits or 0x040
        if (p.isK) bits = bits or 0x080
        if (p.isD) bits = bits or 0x100
        if (p.isN) bits = bits or 0x200
        out.u16(bits)
        out.i32(p.flag)
    }
}
