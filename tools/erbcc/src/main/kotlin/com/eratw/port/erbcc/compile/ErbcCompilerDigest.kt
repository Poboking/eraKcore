package com.eratw.port.erbcc.compile

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * 编译器指纹（`compiler-digest`）：把「ERB 源码 → .erbc 字节」这条链的 Kotlin 源文件集
 * 做成一个确定性 SHA-256，写进 `manifest.txt`，供读侧与出包侧反查「这份产物是不是当前
 * 编译器编出来的」。
 *
 * ## 为什么需要它（缺陷面）
 *
 * 既有清单只对账「语料源文件 × 产物自身」：`ErbcArtifacts.manifestText`（`ErbcArtifacts.kt:241`）
 * 记 `erbcc <版本>`、每条 `source <rel> <bytes> <sha256>`、每条 `artifact ...`，`ArtifactFreshness.check`
 * （`ArtifactFreshness.kt:151`）逐条重算源文件摘要。**编译器源码不在任何一方的判据里** —— 改了
 * `ErbProgramCompiler` 而不重编，旧 `.erbc` 与当前语料仍然逐字节一致，整条链（erbcc check、
 * app staging）都会放行。真机红 J 的 `NEXT`/`FOR STEP` 死循环（`1c1c3e0d` 修）就是这样被
 * 旧产物带进包的：语料没变、产物自洽，唯独编译器换了。
 *
 * ## 文件集判据（为什么是 core-vm 整树）
 *
 * 编译链的 Kotlin 依赖是**跨包**的，逐个枚举包必然漏（新增一个包/文件不会自动进集合）：
 * - `compile/`：`ErbProgramCompiler.kt:3-25` 的 import 覆盖 `code`（CodeBuilder/Opcode/
 *   Program/CallArg/SlotAddr/StatementAliases）、`expr`、`fn`（Builtins）；`:26` 起还有整族 `stmt`；
 * - `code/ErbcWriter.kt:3-7`：容器字节的直接写入者，先引 `ErbcFormat` 的标签表；`:8-42` 起是
 *   `expr` / `stmt`；
 * - `tools/erbcc` 侧的驱动 `CorpusCompile.kt:3-43` 还引 `lexer`、`vars`、`data.csv`、
 *   `config`、`proc`、`loader`、`err`。
 *
 * 所以本对象取 **`core-vm/src/main/kotlin` 整棵树**：core-vm 是编译器与装载器的单模块，
 * 任何一处改动都可能进入产物字节；整树哈希的代价只有一次 mb 级读盘 + SHA-256（实测几十
 * 毫秒），远比「漏一个包 ⇒ 判据静默失效」便宜。**宁可多不可漏**。
 *
 * TODO(verify)：`tools/erbcc` 的驱动 `CorpusCompile`/`CorpusScan` 也在链上却不在本集合里
 * （本波范围只到 core-vm）；要覆盖它得把集合改成多根，属组长接线面。
 *
 * ## digest 口径（确定性）
 *
 * 1. 文件集：`core-vm/src/main/kotlin` 下**递归**的全部 `.kt` 普通文件（不含测试源集）；
 * 2. 相对路径 = 相对**仓库根**的路径，`\` 统一成 `/`（清单里的路径口径同 `artifact` 行）；
 * 3. 按相对路径做字典序排序（`String` 自然序，与平台无关）；
 * 4. 逐文件喂 `SHA-256`：`u32be(路径字节数) ‖ 路径 UTF-8 ‖ u32be(文件字节数) ‖ 文件字节`。
 *    长度前缀让「路径/内容任意拼接」不产生歧义；
 * 5. 同一源码树两次计算逐字节相同（无时间戳、无绝对路径、无主机名）。
 *
 * `compiler-sources N` 单独写一行只为人读；判据只认 `compiler-digest`。
 */
object ErbcCompilerDigest {

    /** 编译器源码根（相对仓库根）：core-vm 主源集的整棵树。 */
    const val SOURCE_ROOT_REL: String = "core-vm/src/main/kotlin"

    /**
     * 定位仓库根的标记文件（相对仓库根）。
     *
     * 选它是因为「路径 + 类名」同时钉死了模块与包：只认 `core-vm/src/main/kotlin` 这个目录名
     * 的话，任意上级目录碰巧同名就会误判。
     */
    const val MARKER_REL: String = "core-vm/src/main/kotlin/com/eratw/port/core/compile/ErbProgramCompiler.kt"

    /** 一次指纹计算的结果。 */
    data class Result(
        /** SHA-256 小写十六进制（64 字符）。 */
        val digest: String,
        /** 参与计算的文件数（清单 `compiler-sources` 行）。 */
        val fileCount: Int,
    )

    /**
     * 计算 [repoRoot] 对应的编译器指纹。
     *
     * @throws IllegalStateException 源码树不存在或一个 `.kt` 都没有（不能返回空哈希冒充
     *   「一致」—— 那等于把判据关掉）
     */
    fun compute(repoRoot: Path): Result {
        val root = repoRoot.toAbsolutePath().normalize()
        val srcRoot = root.resolve(SOURCE_ROOT_REL)
        check(Files.isDirectory(srcRoot)) {
            "编译器源码树不存在：$srcRoot（compiler-digest 没有判据；repoRoot=$root）"
        }
        val files = Files.walk(srcRoot).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .map { root.relativize(it).toString().replace('\\', '/') to it }
                .toList()
                .sortedBy { it.first }
        }
        check(files.isNotEmpty()) { "编译器源码树里没有 .kt 文件：$srcRoot（compiler-digest 没有判据）" }

        val sha = MessageDigest.getInstance("SHA-256")
        for ((rel, path) in files) {
            val relBytes = rel.toByteArray(Charsets.UTF_8)
            val bytes = Files.readAllBytes(path)
            sha.update(ByteBuffer.allocate(4).putInt(relBytes.size).array())
            sha.update(relBytes)
            sha.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            sha.update(bytes)
        }
        return Result(hex(sha.digest()), files.size)
    }

    /**
     * 从调用上下文往上找仓库根（含 [MARKER_REL] 的那个目录）；找不到返回 null。
     *
     * 为什么要「往上找」而不是新增一个入参：`ArtifactFreshness.check` 的既有签名只有
     * `outDir` + `corpusRoot`（`ArtifactFreshness.kt:151`），而编译器源码在**仓库内**、
     * 与产物目录和语料根都无固定相对关系。产物目录通常落在仓库的 `build/` 或
     * `tools/erbcc/erbc-out/` 下，`outDir` 的祖先里就有仓库根；CLI 与 Gradle 测试的
     * 工作目录也在仓库内。作为兜底再试 `user.dir` 与本类 class 文件所在位置（测试/IDE
     * 改了工作目录时仍能定位），三处都找不到才返回 null（上层据此报红，不静默放行）。
     *
     * @param hints 优先尝试的起始路径（可以是目录或文件；文件取父目录）
     */
    fun locateRepoRoot(vararg hints: Path): Path? {
        val starts = ArrayList<Path>(hints.size + 2)
        for (hint in hints) starts.add(hint.toAbsolutePath().normalize())
        val userDir = System.getProperty("user.dir")
        if (!userDir.isNullOrEmpty()) runCatching { starts.add(Paths.get(userDir).toAbsolutePath().normalize()) }
        runCatching {
            val location = ErbcCompilerDigest::class.java.protectionDomain.codeSource?.location
            if (location != null) starts.add(Paths.get(location.toURI()).toAbsolutePath().normalize())
        }
        for (start in starts) {
            var dir: Path? = if (Files.isDirectory(start)) start else start.parent
            while (dir != null) {
                if (Files.isRegularFile(dir.resolve(MARKER_REL))) return dir
                dir = dir.parent
            }
        }
        return null
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
