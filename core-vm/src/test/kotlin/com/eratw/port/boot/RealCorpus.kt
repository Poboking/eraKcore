package com.eratw.port.boot

import com.eratw.port.core.compile.CorpusCompileValidator
import java.nio.file.Files
import java.nio.file.Path

/**
 * 真实语料定位（测试装置，不进入发布代码）。
 *
 * 口径：
 * 1. `ERATW_CORPUS` 环境变量**显式给出即权威**（指向 `...\ERB` 目录），不再回落；
 * 2. 缺省 = 仓库根（测试 JVM cwd = 模块目录 `core-vm/` 上两级）下的
 *    `eratw-画蛇添足版eratw4.980/ERB`；
 * 3. 本仓库的 worktree 布局里游戏目录常与 `eratw-android/` 同级（worktree 根），
 *    故再补「仓库根上一级」候选；仍找不到返回 null（CI 优雅跳过）。
 *
 * 文件清单复用 [CorpusCompileValidator.listCorpusFiles]：排除混进语料根的
 * `GOLDEN_*.ERB` 采集副本，口径与 `:core-vm:corpusSmoke` 的 3,971 文件基线一致
 * （3,818 ERB + 153 ERH = 3,971）。
 */
class RealCorpus private constructor(
    val root: Path,
    val files: List<Path>,
    /** 发现来源（`ERATW_CORPUS=…` / `auto:<候选>`），写报告用。 */
    val source: String,
) {
    val erbCount: Int = files.count { it.fileName.toString().endsWith(".erb", ignoreCase = true) }
    val erhCount: Int = files.count { it.fileName.toString().endsWith(".erh", ignoreCase = true) }

    override fun toString(): String = "$root（$erbCount ERB + $erhCount ERH，$source）"

    companion object {
        const val ENV = "ERATW_CORPUS"
        private const val GAME_DIR_NAME = "eratw-画蛇添足版eratw4.980"

        fun load(): RealCorpus? {
            val env = System.getenv(ENV)?.trim()?.takeIf { it.isNotEmpty() }
            if (env != null) {
                val p = Path.of(env).toAbsolutePath().normalize()
                return from(p, "$ENV=$env")
                    ?: run {
                        System.err.println("[boot] $ENV 指向的目录不存在或没有 ERB/ERH：$p")
                        null
                    }
            }

            val cwd = Path.of("").toAbsolutePath().normalize()
            val repoRoot = cwd.parent?.parent ?: cwd
            val candidates = LinkedHashSet<Path>()
            // 标准布局：<仓库根>/eratw-画蛇添足版eratw4.980/ERB
            candidates.add(repoRoot.resolve(GAME_DIR_NAME).resolve("ERB"))
            // worktree 布局：游戏目录与 eratw-android/ 同级
            repoRoot.parent?.let { up ->
                candidates.add(up.resolve(GAME_DIR_NAME).resolve("ERB"))
                candidates.add(up.resolve("eratw-安卓移植计划").resolve(GAME_DIR_NAME).resolve("ERB"))
            }
            // 历史主机绝对路径（与 GoldenPipeline.gameCsvDir 同向兜底）
            candidates.add(Path.of("D:/DSH/eratw-安卓移植计划", GAME_DIR_NAME, "ERB"))
            candidates.add(Path.of("C:/DSH/eratw-安卓移植计划", GAME_DIR_NAME, "ERB"))

            for (c in candidates) {
                val norm = c.toAbsolutePath().normalize()
                if (Files.isDirectory(norm)) {
                    from(norm, "auto:$norm")?.let { return it }
                }
            }
            return null
        }

        private fun from(root: Path, source: String): RealCorpus? {
            if (!Files.isDirectory(root)) return null
            val files = runCatching { CorpusCompileValidator.listCorpusFiles(root) }
                .getOrElse {
                    System.err.println("[boot] 语料扫描失败：$root → ${it.message}")
                    return null
                }
            if (files.isEmpty()) return null
            return RealCorpus(root, files, source)
        }
    }
}
