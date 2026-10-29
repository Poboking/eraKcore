package com.eratw.port.erbcc.compile

import com.eratw.port.core.Version
import com.eratw.port.core.code.CallArgSource
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.FunctionInfo
import com.eratw.port.core.code.Program
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.erbcc.cmdCompile
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.io.TempDir

/**
 * `erbcc compile` 端到端对拍（ERBC-B 任务 1/3 的验收面）。
 *
 * 覆盖：
 * 1. **对拍**：合成小语料 → [CorpusCompile] 内存 Program → [ErbcArtifacts] 落盘 →
 *    [ProgramLoader] 装载，装载出的 Program 与内存编译的 Program 在**指令流、行号表、
 *    四个池、函数表**上逐字段一致（判等刻意不经过 ErbcWriter/Reader 自身的 cycle）；
 * 2. **确定性**：同一语料两次编译，`program.erbc` 与 `manifest.txt` 均逐字节相同；
 * 3. **manifest**：语料根 / 源文件数 / 每条 `相对路径 字节数 SHA-256` / 格式版本；
 * 4. **排除**：`GOLDEN_*` 采集副本与含 NUL 的二进制 `.erb` 都不进编译输入；
 * 5. **失败路径**：编译失败 → 退出码 1 且**不落盘**，stderr 逐条带 `文件:行号`。
 *
 * 断言口径与 core-vm 的 `ErbcRoundTripTest` 对齐：语句/参数类不是 data class，
 * 一律走 [assertGraphEqual] 反射逐字段深比。
 */
class CompileErbcTest {

    // ==================================================================
    // 合成小语料
    // ==================================================================

    /**
     * 五个编译输入 + 两个排除项：
     * - `01_core.ERB`：常量/字符串/表达式/IF-ELSE/FOR/GOTO+标签（控制流留字节码）；
     * - `02_train_a.ERB` / `03_train_b.ERB`：同名事件 `@EVENTTURNEND` 两处定义
     *   （触发事件入口合成 `_CALL`，spec: CalledFunction.cs:228-243）；
     * - `04_methods.ERB`：`#FUNCTIONS` 方法 + `CALL`（Prescan 的 funcTypes 口径）；
     * - `GOLDEN_HACK.ERB`：金标采集副本（非原版语料，`CorpusCompile` 应排除）；
     * - `bin.erb`：前 8KB 含 NUL 的二进制（不解析，但仍登记 manifest，与
     *   测试装置 `CorpusValidator` 同口径：切片阶段跳过，清单不跳）。
     */
    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        fun erb(name: String, text: String) {
            Files.write(root.resolve(name), text.toByteArray(Charsets.UTF_8))
        }
        erb(
            "01_core.ERB",
            """
            ; 合成小语料：核心面（控制流留字节码，数据面进池）
            @MINI_CORE
            #DIM MINI_A
            #DIMS MINI_S
            #DIM MINI_I
            PRINTL MINI_HELLO
            MINI_A = 1 + 2 * 3
            PRINTFORML A={MINI_A}
            IF MINI_A > 5
            	PRINTL BIG
            ELSE
            	PRINTL SMALL
            ENDIF
            FOR MINI_I, 0, 2
            	PRINTFORML I={MINI_I}
            NEXT
            MINI_S '= "payload"
            IF MINI_S == "payload"
            	GOTO MINI_TAIL
            ENDIF
            PRINTL UNREACHED
            ${'$'}MINI_TAIL
            PRINTL TAILED
            """.trimIndent(),
        )
        erb(
            "02_train_a.ERB",
            """
            @EVENTTURNEND
            #DIM TAA
            TAA = 7
            PRINTFORML TA={TAA}
            """.trimIndent(),
        )
        erb(
            "03_train_b.ERB",
            """
            @EVENTTURNEND
            PRINTL TRAIN_B
            """.trimIndent(),
        )
        erb(
            "04_methods.ERB",
            """
            #FUNCTIONS
            @MINI_ECHO(ARG)
            PRINTFORML ECHO={ARG}

            @MINI_RUNNER
            CALL MINI_ECHO(42)
            """.trimIndent(),
        )
        // 排除项 1：金标采集副本（名字以 GOLDEN_ 开头）
        erb("GOLDEN_HACK.ERB", "@GOLDEN_HACK\nPRINTL NEVER_COMPILED\n")
        // 排除项 2：含 NUL 的二进制 .erb（前 8KB 出现 NUL 即按二进制跳过解析）
        Files.write(root.resolve("bin.erb"), byteArrayOf('B'.code.toByte(), 0, 'M'.code.toByte()))
    }

    // ==================================================================
    // 对拍：.erbc 装载出的 Program == 内存编译的 Program
    // ==================================================================

    @Test
    fun `erbc 落盘装载出的 Program 与内存编译逐字段一致`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = assertOk(corpus)

        val report = ErbcArtifacts.write(dir.resolve("out"), result.program!!, corpus, result.sourceFiles, result.stats)
        assertTrue(Files.isRegularFile(report.artifactPath), "program.erbc 未落盘")
        val loaded = ProgramLoader.load(Files.readAllBytes(report.artifactPath), "program.erbc")

        assertProgramEquals("erbc vs 内存", result.program!!, loaded)
    }

    @Test
    fun `装载后再编码与落盘字节逐字相同`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = assertOk(corpus)
        val report = ErbcArtifacts.write(dir.resolve("out"), result.program!!, corpus, result.sourceFiles, result.stats)

        val bytes = Files.readAllBytes(report.artifactPath)
        val loaded = ProgramLoader.load(bytes, "program.erbc")
        assertArrayEquals(bytes, ErbcWriter.encode(loaded), "装载 → 再编码必须规范化回同一份字节")
    }

    /**
     * PARAM_NAMES（v2 段 8）原样往返：头部形参项 `ARG:1` 的**下标不能被剥掉**。
     *
     * 语义依据：原版逐位写的是 `TopLabel.Arg[i]` 这个变量元素
     * （spec: ProcessState.cs:435-452；REF 位 :439-441），裸名字区分不了 `ARG` 与 `ARG:1`。
     * 生产侧 `CorpusCompile` 必须把 `FuncLabelStmt.paramRefs` 喂给 `FuncSig.paramNames`
     * （第 5 实参），否则 `FunctionInfo.paramNames` 为空、运行期退回类型桶绑定。
     */
    @Test
    fun `PARAM_NAMES 段保留头部形参项下标`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        Files.createDirectories(corpus)
        Files.write(
            corpus.resolve("01_paramrefs.ERB"),
            """
            @MINI_PARAMREF(ARG, ARG:1)
            PRINTFORML P={ARG:1}

            @MINI_PARAMREF_CALLER
            CALL MINI_PARAMREF(1, 2)
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        val result = CorpusCompile.compile(corpus)
        assertTrue(result.ok, "合成语料必须编译通过；失败 ${result.failures.size} 条: ${result.failures.take(5)}")
        val program = assertNotNull(result.program)
        val mem = program.functions.first { it.name == "MINI_PARAMREF" }
        assertEquals(
            listOf("ARG", "ARG:1"), mem.paramNames,
            "内存编译的 FunctionInfo.paramNames 必须是头部形参项原文（保留下标）",
        )
        val caller = program.functions.first { it.name == "MINI_PARAMREF_CALLER" }
        assertEquals(emptyList(), caller.paramNames, "无括号形参的函数不得带形参项（不许伪造）")

        val report = ErbcArtifacts.write(dir.resolve("out"), program, corpus, result.sourceFiles, result.stats)
        val bytes = Files.readAllBytes(report.artifactPath)
        // 容器自证：v2 + 段 8 必须真的在文件里（不是装载器凭空补的）
        val header = ErbcReader.probe(bytes)
        assertEquals(ErbcFormat.VERSION_2, header.version, "带形参项的产物必须是 v2 容器")
        assertTrue(header.hasSection(ErbcFormat.SECTION_PARAM_NAMES), "v2 容器必须携带 PARAM_NAMES 段")

        val loaded = ProgramLoader.load(bytes, "program.erbc")
        assertEquals(CallArgSource.ERBC_V2, loaded.callArgSource, "装载来源应为 ERBC_V2")
        assertTrue(loaded.bindsCallArgsPositionally, "段 7/8 都在 ⇒ 逐位绑定可用")
        val fromErbc = loaded.functions.first { it.name == "MINI_PARAMREF" }
        assertEquals(
            listOf("ARG", "ARG:1"), fromErbc.paramNames,
            "装载后 FunctionInfo.paramNames 必须原样保留下标",
        )
        val callerFromErbc = loaded.functions.first { it.name == "MINI_PARAMREF_CALLER" }
        assertEquals(emptyList(), callerFromErbc.paramNames, "装载后无括号形参的函数仍不得带形参项")
        assertArrayEquals(bytes, ErbcWriter.encode(loaded), "带 PARAM_NAMES 的产物装载后必须能逐字节重编码")
    }

    @Test
    fun `同一语料两次编译 产物与清单均逐字节相同`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)

        val r1 = assertOk(corpus)
        val r2 = assertOk(corpus)
        val a = ErbcArtifacts.write(dir.resolve("out1"), r1.program!!, corpus, r1.sourceFiles, r1.stats)
        val b = ErbcArtifacts.write(dir.resolve("out2"), r2.program!!, corpus, r2.sourceFiles, r2.stats)

        val bytes1 = Files.readAllBytes(a.artifactPath)
        val bytes2 = Files.readAllBytes(b.artifactPath)
        assertArrayEquals(bytes1, bytes2, "program.erbc 两次编译必须逐字节相同")
        assertEquals(
            Files.readAllBytes(a.manifestPath).toString(Charsets.UTF_8),
            Files.readAllBytes(b.manifestPath).toString(Charsets.UTF_8),
            "manifest.txt 两次编译必须逐字节相同",
        )
    }

    @Test
    fun `manifest 登记语料根 文件清单 格式版本与规模读数`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = assertOk(corpus)
        val report = ErbcArtifacts.write(dir.resolve("out"), result.program!!, corpus, result.sourceFiles, result.stats)
        val text = Files.readString(report.manifestPath)

        assertTrue(text.contains("format-version ${ErbcFormat.LATEST_VERSION}\n"), "缺格式版本: $text")
        assertTrue(text.contains("container-magic ${ErbcFormat.MAGIC_TEXT}\n"), "缺容器 magic")
        assertTrue(text.contains("erbcc ${Version.VERSION}\n"), "缺引擎版本")
        assertTrue(
            text.contains("corpus-root ${corpus.toAbsolutePath().normalize()}\n"),
            "manifest 必须原样登记语料根",
        )
        assertTrue(text.contains("source-files 5\n"), "源文件数应为 5（bin.erb 登记但不解析）: $text")
        for (name in listOf("01_core.ERB", "02_train_a.ERB", "03_train_b.ERB", "04_methods.ERB", "bin.erb")) {
            assertTrue(text.contains("source $name "), "manifest 缺源文件条目: $name")
        }
        assertFalse(text.contains("GOLDEN_HACK"), "GOLDEN_ 采集副本不得进 manifest")
        // 每条 source 行 = 相对路径 + 字节数 + 64 位小写十六进制 SHA-256
        val sourceLine = text.lineSequence().first { it.startsWith("source 01_core.ERB ") }
        val parts = sourceLine.split(' ')
        assertEquals(4, parts.size, "source 行结构应为 4 列: $sourceLine")
        assertEquals(
            Files.readAllBytes(corpus.resolve("01_core.ERB")).size.toLong(),
            parts[2].toLong(),
            "字节数应与磁盘一致",
        )
        assertTrue(Regex("^[0-9a-f]{64}$").matches(parts[3]), "SHA-256 应为小写十六进制: ${parts[3]}")
        // 规模读数（不等于 0 才有意义）
        val st = result.stats
        assertTrue(st.instructions > 0 && st.statements > 0 && st.functions > 0, "编译规模读数不能为 0")
        assertTrue(text.contains("program instructions ${st.instructions}\n"), "缺指令数读数")
        assertTrue(text.contains("program entry-function ${st.entryFunction}\n"), "缺入口函数读数")
        assertTrue(text.endsWith("\n"), "manifest 应以 LF 结尾")
        assertFalse(text.contains("\r"), "manifest 不得含 CR（LF 换行）")
    }

    @Test
    fun `GOLDEN 副本与含 NUL 的二进制 erb 不进编译输入`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = assertOk(corpus)

        val rels = result.sourceFiles.map { it.relPath }
        assertFalse(rels.any { it.startsWith("GOLDEN_") }, "GOLDEN_ 副本不得登记: $rels")
        assertTrue(rels.contains("bin.erb"), "二进制 .erb 仍是源文件（登记但不解析）: $rels")
        // 编译输入里的字符串池不得出现排除项内容。口径：PRINTL 裸文本以
        // ExpressionArgument(StrLiteral) 挂在**语句池**条目的 args 树上——既不进
        // strings 池（唯一写入点是 _SET_STR 降级，ErbProgramCompiler:487），也不进
        // exprs 池（语句 args 树不经 addExpr）。mini 语料无裸字符串赋值语句，
        // strings 池为 0 条属正常。
        val strings = (0 until result.program!!.stringCount).map { result.program!!.string(it) }
        assertFalse(strings.any { it.contains("NEVER_COMPILED") }, "GOLDEN_HACK 的字符串不应进池")
        val stmts = (0 until result.program!!.statementCount).map { result.program!!.statement(it) }
        assertTrue(
            stmts.any { s ->
                s is com.eratw.port.core.stmt.PrintStmt &&
                    (s.args as? com.eratw.port.core.stmt.ExpressionArgument)
                        ?.term.let { t -> t is com.eratw.port.core.expr.StrLiteral && t.value == "TAILED" }
            },
            "01_core.ERB 的 PRINTL TAILED 应以 StrLiteral 进语句池参数",
        )
        assertTrue(result.stats.slices >= 4, "应有四个正常文件的切片，实为 ${result.stats.slices}")
    }

    // ==================================================================
    // 失败路径：退出码 1 且不落盘
    // ==================================================================

    @Test
    fun `编译失败返回 1 不落盘且失败条目带文件行号`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        Files.createDirectories(corpus)
        Files.write(
            corpus.resolve("01_broken.ERB"),
            """
            @MINI_BROKEN
            IF 1 > 0
            	PRINTL NEVER_END
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        val out = dir.resolve("out")

        val result = CorpusCompile.compile(corpus)
        assertFalse(result.ok, "未闭合 IF 必须判失败")
        val failure = result.failures.first { it.file.endsWith("01_broken.ERB") }
        assertTrue(
            Regex("^01_broken\\.ERB:\\d+ \\[").containsMatchIn(failure.toString()),
            "失败条目必须形如 文件:行号: ${failure}",
        )

        val code = cmdCompile(listOf("--corpus", corpus.toString(), "--out", out.toString()))
        assertEquals(1, code, "编译失败必须返回退出码 1")
        assertFalse(Files.exists(out), "失败不得落盘（out 目录不应被创建）")
    }

    @Test
    fun `编译成功返回 0 并落盘`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val out = dir.resolve("out")

        val code = cmdCompile(listOf("--corpus", corpus.toString(), "--out", out.toString()))
        assertEquals(0, code, "成功必须返回 0")
        assertTrue(Files.isRegularFile(out.resolve("erbc.v1/program.erbc")), "缺 program.erbc")
        assertTrue(Files.isRegularFile(out.resolve("erbc.v1/manifest.txt")), "缺 manifest.txt")
        // 落盘产物应可被装载器原样读回
        val loaded = ProgramLoader.loadFile(out.resolve("erbc.v1/program.erbc"))
        assertTrue(loaded.instructionCount > 0)
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 编译并断言 ok（含 0 失败、program 非空、事件入口已合成）。 */
    private fun assertOk(corpus: Path): CompileResult {
        val r = CorpusCompile.compile(corpus)
        assertTrue(r.ok, "合成语料必须编译通过；失败 ${r.failures.size} 条: ${r.failures.take(5)}")
        assertNotNull(r.program)
        assertTrue(r.stats.eventEntries > 0, "必须有事件入口合成（@EVENTTURNEND 两处定义）")
        return r
    }

    private fun assertProgramEquals(what: String, expected: Program, actual: Program) {
        assertEquals(expected.instructionCount, actual.instructionCount, "$what 指令条数")
        assertArrayEquals(expected.code, actual.code, "$what 指令流逐字")
        assertArrayEquals(expected.lines, actual.lines, "$what 行号表逐字")
        assertEquals(expected.globalSlots, actual.globalSlots, "$what 全局槽")
        assertEquals(expected.entryFunction, actual.entryFunction, "$what 入口函数")

        assertEquals(expected.constantCount, actual.constantCount, "$what 常量池长度")
        for (i in 0 until expected.constantCount) {
            assertEquals(expected.constant(i), actual.constant(i), "$what 常量池下标 $i")
        }
        assertEquals(expected.stringCount, actual.stringCount, "$what 字符串池长度")
        for (i in 0 until expected.stringCount) {
            assertEquals(expected.string(i), actual.string(i), "$what 字符串池下标 $i")
        }
        assertEquals(expected.exprCount, actual.exprCount, "$what 表达式池长度")
        for (i in 0 until expected.exprCount) {
            assertEquals(expected.expr(i), actual.expr(i), "$what 表达式池下标 $i 结构不等")
        }
        assertEquals(expected.statementCount, actual.statementCount, "$what 语句池长度")
        for (i in 0 until expected.statementCount) {
            val e = expected.statement(i)
            val a = actual.statement(i)
            assertSame(e.javaClass, a.javaClass, "$what 语句池下标 $i 类不同")
            assertEquals(e.code, a.code, "$what 语句池下标 $i 的 FunctionCode")
            assertEquals(e.line, a.line, "$what 语句池下标 $i 行号")
            assertEquals(e.raw, a.raw, "$what 语句池下标 $i 原文")
            assertGraphEquals("$what stmt[$i]", e, a)
        }

        assertEquals(expected.functions.size, actual.functions.size, "$what 函数表长度")
        for (i in expected.functions.indices) {
            val e: FunctionInfo = expected.functions[i]
            val a: FunctionInfo = actual.functions[i]
            assertEquals(e.name, a.name, "$what 函数 $i 名字")
            assertEquals(e.entryPc, a.entryPc, "$what 函数 ${e.name} entryPc")
            assertEquals(e.arity, a.arity, "$what 函数 ${e.name} arity")
            assertEquals(e.slotCount, a.slotCount, "$what 函数 ${e.name} slotCount")
            assertEquals(e.paramNames, a.paramNames, "$what 函数 ${e.name} 形参项")
            assertEquals(e.labelNames.toList(), a.labelNames.toList(), "$what 函数 ${e.name} 标签名表")
            assertEquals(e.labelPcs.toList(), a.labelPcs.toList(), "$what 函数 ${e.name} 标签 pc 表")
        }
        // marker 指令的池下标必须指向同类语句（契约 C3：数据面进语句池）
        for (pc in 0 until expected.instructionCount) {
            val op = expected.op(pc)
            if (op !in ErbcFormat.MARKER_OPCODE_MIN..ErbcFormat.MARKER_OPCODE_MAX) continue
            assertEquals(expected.a(pc), actual.a(pc), "$what pc=$pc 的 marker 池下标")
            assertSame(
                expected.statement(expected.a(pc)).javaClass,
                actual.statement(actual.a(pc)).javaClass,
                "$what pc=$pc 的 marker 取回不同类语句",
            )
        }
    }

    /**
     * 反射逐字段深比（同 core-vm `ErbcRoundTripTest.assertGraphEqual` 的口径）：
     * Statement/Argument 不是 data class，`==` 等价于 assertSame，抓不到字段丢失。
     */
    private fun assertGraphEquals(path: String, expected: Any?, actual: Any?) {
        if (expected === actual) return
        if (expected == null || actual == null) {
            assertEquals(expected, actual, "$path（一侧为 null）")
            return
        }
        // 容器类非语义：listOf(x)（SingletonList）与 ArrayList 值相等即可 ——
        // 与 core-vm ErbcRoundTripTest.assertGraphEqual 的 listPair 豁免同口径
        // （VarRef.indices 编译侧单元素走 SingletonList，ErbcReader 重建走 ArrayList）。
        val listPair = expected is List<*> && actual is List<*>
        if (expected.javaClass != actual.javaClass && !listPair) {
            assertEquals(
                expected.javaClass.simpleName,
                actual.javaClass.simpleName,
                "$path 类不同",
            )
            return
        }
        when (expected) {
            is Double -> assertEquals(expected.toRawBits(), (actual as Double).toRawBits(), "$path（Double raw bits）")
            is String, is Boolean, is Char, is Number, is Enum<*> -> assertEquals(expected, actual, path)
            is IntArray -> assertArrayEquals(expected, (actual as IntArray), path)
            is LongArray -> assertArrayEquals(expected, (actual as LongArray), path)
            is DoubleArray -> assertEquals(
                expected.map { it.toRawBits() },
                (actual as DoubleArray).map { it.toRawBits() },
                "$path（DoubleArray raw bits）",
            )

            is Array<*> -> {
                val other = actual as Array<*>
                assertEquals(expected.size, other.size, "$path（数组长度）")
                for (i in expected.indices) assertGraphEquals("$path[$i]", expected[i], other[i])
            }

            is List<*> -> {
                val other = actual as List<*>
                assertEquals(expected.size, other.size, "$path（列表长度）")
                for (i in expected.indices) assertGraphEquals("$path[$i]", expected[i], other[i])
            }

            else -> {
                val fields = instanceFields(expected.javaClass)
                assertTrue(fields.isNotEmpty(), "$path 没有可比对的实例字段：${expected.javaClass}")
                for (f in fields) {
                    f.isAccessible = true
                    assertGraphEquals("$path.${f.name}", f.get(expected), f.get(actual))
                }
            }
        }
    }

    private fun instanceFields(clazz: Class<*>): List<java.lang.reflect.Field> {
        val out = ArrayList<java.lang.reflect.Field>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.isSynthetic) continue
                if (f.name.startsWith("$")) continue
                out.add(f)
            }
            c = c.superclass
        }
        return out
    }
}
