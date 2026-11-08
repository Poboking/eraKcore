package com.eratw.port.erbcc.compile

import com.eratw.port.core.code.ErbcFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 编译确定性逐字节对拍（MODERN R4 第一波）。
 *
 * 既有弱判据（`CompileErbcTest.同一语料两次编译` / `BootMetaRoundTripTest` /
 * `ErbcArtifactManifestTest`）只钉「同目录、同参数两次编译」的少数产物。本类把它升级成
 * **全产物集 × 并行档 × 跨绝对根 × 枚举序**四维对拍，并对清单形状加键集合闸：
 *
 * 1. `eratw.erbcc.parallel=false` vs `true/threads=8/chunk=1`（强制每文件一块）四件产物
 *    （`program.erbc` / `program.meta` / `program.stmt.idx` / `manifest.txt`）逐字节相同 ——
 *    钉住「并行预取只加速、不改语义」（`CorpusCompile` 的 futures 按提交序回放，:570-588）；
 * 2. 同参数两次编译四件产物逐字节相同（含此前没被覆盖的 `.meta` / `.stmt.idx`）；
 * 3. 同内容同相对文件名放在**两个不同绝对路径**的根下编译：三件产物必须逐字节相同
 *    （`manifest.txt` 的 `corpus-root` 是既有取证设计，只允许这一行不同）—— 这是「产物是否
 *    泄漏绝对路径」的唯一读数面；
 * 4. 清单形状：首部键集合封闭、无时间/环境量键、`source` 行数与序、`artifact` 三条定序、
 *    `compiler-digest` 为 64 位小写十六进制且两次编译相同；
 * 5. [CorpusCompile.listCorpusFiles] 在创建序相反的同一内容根上返回同一有序清单。
 *
 * 夹具（自建，不读 `ERATW_CORPUS`，无游戏语料也能跑）：9 个 `.ERB`/`.ERH`，覆盖事件同名多定义、
 * `#DIM`/`#DIMS`/`#FUNCTIONS` 方法 + `CALL`、全局 `#DIM CONST`、`#DEFINE` 宏、子目录、以及一个
 * 8 KiB 内含 NUL 的二进制 `bin.erb`（登记但不解析）。文件名选的是一次**词法升序**清单，
 * 落盘时按它的**反序**创建，于是「创建序」与「词法序」互为逆序。
 *
 * 枚举序为什么一定与词法序不同（反向证明 B 的前提，本机 NTFS 实测）：
 * - 大小写：目录枚举按大小写不敏感（`alpha` < `Zulu`），`sortedBy { it.toString() }` 按
 *   UTF-16 码元（`Zulu` < `alpha`）——夹具里 `Zulu.ERB` 与 `bin.erb` 就是这一对；
 * - 目录前缀：`09_sub.ERB`（文件）与 `09_sub/deep.ERB`（子目录内文件）在词法序里
 *   `09_sub.ERB` 在前（`.` < `\\`），遍历序里先下钻子目录（`09_sub` 是 `09_sub.ERB` 的前缀）。
 *
 * 并行开关走 `System.getProperty`（`CorpusCompile.kt:558-562`），同一测试 JVM 里其它测试类
 * 共享进程 —— 每个用例都把三个属性在 `finally` 复原（见 [withParallelProps]）。
 */
class DeterministicCompileTest {

    /**
     * 夹具的 9 个编译输入，**词法升序**（= `sortedBy { it.toString() }` 的结果）。
     * 相对路径一律正斜杠（`CorpusCompile` 的 rel 口径 `:1183`）。
     */
    private val fixtureFiles: List<String> = listOf(
        "01_Core.ERB",
        "02_Train.ERB",
        "03_Methods.ERB",
        "04_globals.ERH",
        "05_str.ERH",
        "09_sub.ERB",
        "09_sub/deep.ERB",
        "Zulu.ERB",
        "bin.erb",
    )

    // ==================================================================
    // 1) 并行开/关对拍
    // ==================================================================

    @Test
    fun `并行预取开与关的四件产物逐字节相同`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeFixture(corpus)

        val serial = withParallelProps(parallel = "false", threads = null, chunk = null) {
            compileTo(corpus, dir.resolve("out-serial"))
        }
        // 每文件一块：块内只有 1 个 future，回放序只剩「块间序」这一维。
        val perFileChunk = withParallelProps(parallel = "true", threads = "8", chunk = "1") {
            compileTo(corpus, dir.resolve("out-parallel-chunk1"))
        }
        // 缺省块大小（256）：9 个文件同块 ⇒ 块内 future 的**提交序回放**这一维才被真正压到。
        // 这一档必须留：`chunk=1` 时「块内按完成序回放」是空操作，抓不到
        // `futures.map { it.get() }`（CorpusCompile.kt:584）被改成乱序回放的回归。
        val multiFileChunk = withParallelProps(parallel = "true", threads = "8", chunk = null) {
            compileTo(corpus, dir.resolve("out-parallel-chunk-default"))
        }

        for (name in ARTIFACTS) {
            assertSameBytes(
                "$name（并行 chunk=1 vs 串行）",
                serial.getValue(name), perFileChunk.getValue(name),
            )
            assertSameBytes(
                "$name（并行缺省 chunk vs 串行）",
                serial.getValue(name), multiFileChunk.getValue(name),
            )
            assertSameBytes(
                "$name（并行 chunk=1 vs 并行缺省 chunk）",
                perFileChunk.getValue(name), multiFileChunk.getValue(name),
            )
        }
    }

    // ==================================================================
    // 2) 同参数两次编译（既有弱判据升级到全产物集）
    // ==================================================================

    @Test
    fun `同参数两次编译的四件产物逐字节相同`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeFixture(corpus)

        val first = compileTo(corpus, dir.resolve("out-1"))
        val second = compileTo(corpus, dir.resolve("out-2"))

        for (name in ARTIFACTS) {
            assertSameBytes("$name（同参数两次编译）", first.getValue(name), second.getValue(name))
        }
    }

    // ==================================================================
    // 3) 跨根对拍（本波关键读数）
    // ==================================================================

    @Test
    fun `同一语料挂在两个绝对根下三件产物逐字节相同且清单只差 corpus-root 一行`(@TempDir dir: Path) {
        val rootA = dir.resolve("root-a").resolve("ERB")
        val rootB = dir.resolve("root-b").resolve("ERB")
        // 同内容、同相对文件名；创建序互为逆序。
        writeFixture(rootA, reverseCreation = false)
        writeFixture(rootB, reverseCreation = true)
        assertTrue(
            rootA.toAbsolutePath().normalize() != rootB.toAbsolutePath().normalize(),
            "两个根必须是不同绝对路径（跨根对拍的前提）",
        )

        val a = compileTo(rootA, dir.resolve("out-a"))
        val b = compileTo(rootB, dir.resolve("out-b"))

        // 关键读数：三件产物里**不得**出现任何一个绝对根路径（否则跨根就不可能逐字节相同）。
        for (name in listOf("program.erbc", "program.meta", "program.stmt.idx")) {
            val leaked = listOf(rootA, rootB)
                .map { it.toAbsolutePath().normalize().toString() }
                .filter { containsBytes(a.getValue(name), it) || containsBytes(b.getValue(name), it) }
            assertTrue(
                leaked.isEmpty(),
                "$name 泄漏了绝对根路径 $leaked（这会让跨根产物不同；首个命中偏移见下）",
            )
            assertSameBytes("$name（跨根 root-a vs root-b）", a.getValue(name), b.getValue(name))
        }

        // manifest：只允许 corpus-root 一行不同（其余行必须逐字节相同）。
        val manifestA = a.getValue("manifest.txt").toString(Charsets.UTF_8)
        val manifestB = b.getValue("manifest.txt").toString(Charsets.UTF_8)
        val rootsA = manifestA.split('\n').filter { it.startsWith("corpus-root ") }
        val rootsB = manifestB.split('\n').filter { it.startsWith("corpus-root ") }
        assertEquals(1, rootsA.size, "清单必须恰有一行 corpus-root：$rootsA")
        assertEquals(1, rootsB.size, "清单必须恰有一行 corpus-root：$rootsB")
        assertEquals("corpus-root ${rootA.toAbsolutePath().normalize()}", rootsA.single())
        assertEquals("corpus-root ${rootB.toAbsolutePath().normalize()}", rootsB.single())
        assertEquals(
            normalizeCorpusRoot(manifestA),
            normalizeCorpusRoot(manifestB),
            "manifest.txt 除 corpus-root 一行外必须逐字节相同（清单里不许有其它的环境量）",
        )
    }

    // ==================================================================
    // 4) 清单形状
    // ==================================================================

    @Test
    fun `清单首部键集合封闭且 source 行序 artifact 表与编译器指纹合规`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeFixture(corpus)

        val first = compileTo(corpus, dir.resolve("out-1"))
        val second = compileTo(corpus, dir.resolve("out-2"))
        val raw = first.getValue("manifest.txt")
        val text = raw.toString(Charsets.UTF_8)
        assertTrue(text.endsWith("\n"), "manifest 必须以 LF 结尾")
        assertTrue(!text.contains("\r"), "manifest 不得含 CR（LF 换行）")

        val lines = text.split('\n').let { if (it.last() == "") it.dropLast(1) else it }
        // 首部 = 第一条 `source ` 行之前的全部非注释行（`source-files` 不以 "source " 开头）。
        val headerLines = lines.takeWhile { !it.startsWith("source ") }.filterNot { it.startsWith("#") }
        val headerKeys = headerLines.map { it.substringBefore(' ') }
        val allowed = setOf(
            "manifest-format", "format-version", "container-magic", "container-version", "erbcc",
            "compiler-digest", "compiler-sources", "build-mode", "corpus-root", "source-files",
            "binary-skipped", "artifacts",
        )
        val unknown = headerKeys.filter { it !in allowed }
        assertTrue(unknown.isEmpty(), "清单首部出现未登记键 $unknown（首部键全表：$headerKeys）")
        // 时间/环境量键闸：大小写不敏感、子串匹配，一次报出全部命中项。
        val forbidden = listOf("date", "time", "timestamp", "host", "user", "built")
        val hits = headerKeys.filter { k -> forbidden.any { k.lowercase().contains(it) } }
        assertTrue(hits.isEmpty(), "清单首部出现时间/环境量键 $hits（首部键全表：$headerKeys）")

        // source 行：条数 = 语料文件数（含二进制 bin.erb），路径严格递增（String 自然序）。
        val sourceLines = lines.filter { it.startsWith("source ") }
        assertEquals(
            fixtureFiles.size, sourceLines.size,
            "source 行数应等于语料文件数（含二进制那一个）；实际 ${sourceLines.size}",
        )
        val sourceRels = sourceLines.map { it.split(' ')[1] }
        assertEquals(fixtureFiles, sourceRels, "source 路径必须按 String 自然序严格递增")
        assertTrue(sourceRels.zipWithNext().all { (x, y) -> x < y }, "source 路径必须严格递增：$sourceRels")
        assertTrue(text.contains("source-files ${fixtureFiles.size}\n"), "缺 source-files 计数或计数不符")
        assertTrue(text.contains("binary-skipped 1\n"), "二进制跳过读数应为 1（bin.erb）：$text")

        // artifact 表：恰好三条，相对路径与顺序固定。
        val artifactRels = lines.filter { it.startsWith("artifact ") }.map { it.split(' ')[1] }
        assertEquals(
            listOf(
                "erbc.v${ErbcFormat.VERSION_1}/program.erbc",
                "erbc.v${ErbcFormat.VERSION_1}/program.meta",
                "erbc.v${ErbcFormat.VERSION_1}/program.stmt.idx",
            ),
            artifactRels,
            "artifact 表必须是 program.erbc → program.meta → program.stmt.idx 三条定序",
        )
        assertTrue(text.contains("artifacts 3\n"), "缺 artifacts 3 计数")

        // compiler-digest：64 位小写十六进制，且两次编译取值相同（只由编译器源码树决定）。
        val digests = lines.filter { it.startsWith("compiler-digest ") }.map { it.substringAfter(' ') }
        assertEquals(1, digests.size, "compiler-digest 行应恰有一条：$digests")
        assertTrue(
            Regex("^[0-9a-f]{64}$").matches(digests.single()),
            "compiler-digest 必须是 64 位小写十六进制：${digests.single()}",
        )
        val secondDigest = second.getValue("manifest.txt").toString(Charsets.UTF_8)
            .split('\n').single { it.startsWith("compiler-digest ") }.substringAfter(' ')
        assertEquals(digests.single(), secondDigest, "同一源码树两次编译的 compiler-digest 必须相同")
    }

    // ==================================================================
    // 5) 枚举确定性
    // ==================================================================

    @Test
    fun `listCorpusFiles 在创建序相反的两个同内容根上返回同一有序清单`(@TempDir dir: Path) {
        val rootA = dir.resolve("enum-a").resolve("ERB")
        val rootB = dir.resolve("enum-b").resolve("ERB")
        writeFixture(rootA, reverseCreation = false)
        writeFixture(rootB, reverseCreation = true)
        // 金标采集副本：两处同形各写一份，必须被排除（`CorpusCompile.kt:372/387`）。
        for (root in listOf(rootA, rootB)) {
            Files.write(
                root.resolve("GOLDEN_probe.ERB"),
                "@GOLDEN_PROBE\nPRINTL NEVER_COMPILED\n".toByteArray(Charsets.UTF_8),
            )
        }

        val listA = CorpusCompile.listCorpusFiles(rootA)
        val listB = CorpusCompile.listCorpusFiles(rootB)
        // 返回的是**规范化绝对路径**，两个根的绝对前缀必然不同 ⇒ 逐项对拍按相对路径做。
        val relA = listA.map { relOf(rootA, it) }
        val relB = listB.map { relOf(rootB, it) }
        assertEquals(relA, relB, "创建序相反的两个同内容根必须返回同一有序清单（逐项 ==）")
        assertEquals(fixtureFiles, relA, "清单内容必须恰为夹具的 9 个文件（GOLDEN_ 副本被排除）")

        assertEquals(listA.sortedBy { it.toString() }, listA, "listCorpusFiles 的结果必须等于自身按路径字符串排序")
        assertEquals(listB.sortedBy { it.toString() }, listB, "listCorpusFiles 的结果必须等于自身按路径字符串排序")
        // 排序键是**完整路径字符串**（`CorpusCompile.kt:390`），与文件系统遍历序无关。
        assertEquals(relA.sorted(), relA, "相对路径序必须与完整路径序同向（同一前缀下等价）")
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 并行档三个开关的属性名（`CorpusCompile.kt:558-562` 的读取点）。 */
    private val parallelKeys = listOf(
        "eratw.erbcc.parallel",
        "eratw.erbcc.parallel.threads",
        "eratw.erbcc.parallel.chunk",
    )

    /**
     * 在给定并行开关下执行 [block]，退出时把三个属性**逐键复原**。
     *
     * `CorpusCompile` 读的是 `System.getProperty`，同一测试 JVM 里其它测试类共享进程；
     * 不复原会把「并行关」泄漏到后续用例（静默降速或改变读数）。
     */
    private fun <T> withParallelProps(parallel: String, threads: String?, chunk: String?, block: () -> T): T {
        val saved = parallelKeys.associateWith { System.getProperty(it) }
        try {
            System.setProperty("eratw.erbcc.parallel", parallel)
            if (threads == null) System.clearProperty("eratw.erbcc.parallel.threads")
            else System.setProperty("eratw.erbcc.parallel.threads", threads)
            if (chunk == null) System.clearProperty("eratw.erbcc.parallel.chunk")
            else System.setProperty("eratw.erbcc.parallel.chunk", chunk)
            return block()
        } finally {
            for ((key, value) in saved) {
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    /**
     * 编译 + 落盘，返回四件产物字节（键 = 文件名，与 [ARTIFACTS] 同序）。
     *
     * 落盘走 [ErbcArtifacts.write]（与 `Main.cmdCompile` 同一条链：容器 → stmt.idx → meta →
     * 清单），`repoRoot` 显式给（`compiler-digest` 的判据来源，不依赖临时目录的祖先里有没有
     * 仓库根）。
     */
    private fun compileTo(corpus: Path, out: Path): Map<String, ByteArray> {
        val result = CorpusCompile.compile(corpus)
        assertTrue(result.ok, "合成语料必须编译通过；失败 ${result.failures.size} 条：${result.failures.take(5)}")
        val program = assertNotNull(result.program)
        val report = ErbcArtifacts.write(
            outDir = out,
            program = program,
            corpusRoot = corpus,
            sourceFiles = result.sourceFiles,
            stats = result.stats,
            bootMetadata = result.bootMetadata,
            repoRoot = repoRoot(),
        )
        val dir = report.artifactPath.parent
        return ARTIFACTS.associateWith { Files.readAllBytes(dir.resolve(it)) }
    }

    /** 仓库根（`compiler-digest` 的定位点；工作目录在仓库内时必然可定位）。 */
    private fun repoRoot(): Path = assertNotNull(
        ErbcCompilerDigest.locateRepoRoot(),
        "定位不到仓库根：compiler-digest 没有判据（测试工作目录应在仓库内）",
    )

    /** 逐字节相等判定；不同则一次报出首个不同偏移 + 长度 + 双方 SHA-256 + 该偏移上下文。 */
    private fun assertSameBytes(what: String, expected: ByteArray, actual: ByteArray) {
        val offset = firstDifference(expected, actual)
        if (offset == null) return
        val context = if (offset < expected.size && offset < actual.size) {
            "期望侧上下文=${snippet(expected, offset)}；实际侧上下文=${snippet(actual, offset)}"
        } else {
            "一侧是另一侧的真前缀（长度 ${expected.size} vs ${actual.size}）"
        }
        throw AssertionError(
            "$what 必须逐字节相同，实际首个不同偏移=$offset（长度 ${expected.size} vs ${actual.size}）；" +
                "sha256 期望=${ErbcArtifacts.sha256Hex(expected)} 实际=${ErbcArtifacts.sha256Hex(actual)}；$context",
        )
    }

    /** 首个不同字节偏移；完全相同时返回 null（长度不同且前缀相同时返回较短长度）。 */
    private fun firstDifference(a: ByteArray, b: ByteArray): Int? {
        val common = minOf(a.size, b.size)
        for (i in 0 until common) if (a[i] != b[i]) return i
        return if (a.size != b.size) common else null
    }

    /** [offset] 附近的可读片段（换行转义，便于在断言消息里看出泄漏字段）。 */
    private fun snippet(bytes: ByteArray, offset: Int, radius: Int = 48): String {
        val from = (offset - radius).coerceAtLeast(0)
        val to = (offset + radius).coerceAtMost(bytes.size)
        return String(bytes, from, to - from, Charsets.UTF_8).replace("\n", "\\n").replace("\r", "\\r")
    }

    /** 字节序列里是否出现 [text] 的 UTF-8 编码（绝对路径泄漏的直接判据）。 */
    private fun containsBytes(haystack: ByteArray, text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_8)
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    /** 清单里 `corpus-root` 一行归一成占位（跨根对拍的唯一允许差异）。 */
    private fun normalizeCorpusRoot(manifest: String): String =
        manifest.split('\n').joinToString("\n") {
            if (it.startsWith("corpus-root ")) "corpus-root <normalized>" else it
        }

    private fun relOf(root: Path, path: Path): String =
        root.toAbsolutePath().normalize().relativize(path).toString().replace('\\', '/')

    /**
     * 落 9 个夹具文件。
     *
     * @param reverseCreation true = 按 [fixtureFiles] 的**反序**创建（词法序与创建序互为逆序）
     */
    private fun writeFixture(root: Path, reverseCreation: Boolean = false) {
        Files.createDirectories(root)
        val order = if (reverseCreation) fixtureFiles.reversed() else fixtureFiles
        for (rel in order) {
            val target = root.resolve(rel)
            target.parent?.let { Files.createDirectories(it) }
            Files.write(target, fixtureBytes(rel))
        }
    }

    /** 夹具文件原子内容（按相对路径分发）。 */
    private fun fixtureBytes(rel: String): ByteArray = when (rel) {
        // 控制流留字节码、数据面进语句池；#DIM/#DIMS 私有变量各一条；GOTO + $标签；宏 MINI_N。
        "01_Core.ERB" -> """
            ; 合成语料：核心面（控制流留字节码，数据面进语句池）
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
            FOR MINI_I, 0, MINI_N
            PRINTFORML I={MINI_I}
            NEXT
            MINI_S '= "payload"
            IF MINI_S == "payload"
            GOTO MINI_TAIL
            ENDIF
            PRINTL UNREACHED
            ${'$'}MINI_TAIL
            PRINTL TAILED
        """.trimIndent().toByteArray(Charsets.UTF_8)

        // 同一个文件里两处同名事件：触发事件入口合成「依序 _CALL」（spec: CalledFunction.cs:228-243）。
        "02_Train.ERB" -> """
            @EVENTTURNEND
            #DIM TAA
            TAA = 7
            PRINTFORML TA={TAA}

            @EVENTTURNEND
            PRINTL TRAIN_B
        """.trimIndent().toByteArray(Charsets.UTF_8)

        // #FUNCTIONS 方法（`#` 行紧随其 `@` 标签行）+ 调用方 CALL。
        "03_Methods.ERB" -> """
            @MINI_ECHO(ARG)
            #FUNCTIONS
            PRINTFORML ECHO={ARG}

            @MINI_RUNNER
            CALL MINI_ECHO(42)
        """.trimIndent().toByteArray(Charsets.UTF_8)

        // 全局常量 + 全局变量：装载期进符号表，解析结果随 program.meta 落盘。
        "04_globals.ERH" -> """
            #DIM CONST G_CONST = 7
            #DIM G_FLAG
        """.trimIndent().toByteArray(Charsets.UTF_8)

        // 字符串常量 + 宏（宏在词法层展开，喂给 01_Core 的 FOR 上界）。
        "05_str.ERH" -> """
            #DIMS CONST G_STRCONST = "golden"
            #DEFINE MINI_N 3
        """.trimIndent().toByteArray(Charsets.UTF_8)

        // 与子目录 09_sub/ 同前缀的文件名（词法序里它排在 09_sub/deep.ERB 之前，遍历序里相反）。
        "09_sub.ERB" -> """
            @MINI_SUBFILE
            PRINTL ROOT_PREFIX_OF_SUBDIR
        """.trimIndent().toByteArray(Charsets.UTF_8)

        // 子目录内的文件：语料递归遍历与相对路径（正斜杠）都要覆盖。
        "09_sub/deep.ERB" -> """
            @MINI_DEEP
            PRINTL DEEP_IN_SUBDIR
        """.trimIndent().toByteArray(Charsets.UTF_8)

        // 大写名（目录枚举按大小写不敏感排在 bin.erb 之后，词法序排在它之前）。
        "Zulu.ERB" -> """
            @MINI_ZULU
            PRINTFORML ZULU={G_CONST}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        // 8 KiB、前 8 KiB 内含 NUL：登记进 source 但不解析（CMP-64 口径），binary-skipped 1。
        "bin.erb" -> ByteArray(8192) { if (it < 3) "BIN"[it].code.toByte() else if (it == 3) 0 else 'X'.code.toByte() }

        else -> throw IllegalArgumentException("未登记的夹具文件：$rel")
    }

    private companion object {
        /** 落盘产物的文件名（顺序 = 落盘序：容器 → meta → stmt.idx → 清单）。 */
        val ARTIFACTS = listOf("program.erbc", "program.meta", "program.stmt.idx", "manifest.txt")
    }
}
