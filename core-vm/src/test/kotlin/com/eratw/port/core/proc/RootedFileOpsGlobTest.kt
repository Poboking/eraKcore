package com.eratw.port.core.proc

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ENUMFILES 的 pattern 通配闸（`FunctionMethodCreator.cs:364` 的
 * `Directory.EnumerateFiles(path, searchPattern, option)`）。
 *
 * Windows 通配：`*` = 任意串、`?` = 单个字符。上一版 `RootedFileOps` 的
 * `globToRegex` 只处理 `*`、把 `?` 当字面量。语料实测 pattern 只有 `*`（缺省）与
 * `*.xml`（`リソース管理/ResourceManager.ERB:46`）⇒ 本修当前零输出差，仅纠正通配语义。
 */
class RootedFileOpsGlobTest {

    private fun tempRoot(): Path = Files.createTempDirectory("eratw-glob")

    private fun deleteTree(root: Path) {
        val all = Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
        for (p in all) Files.deleteIfExists(p)
    }

    @Test
    fun `问号匹配单个字符`() {
        val root = tempRoot()
        try {
            Files.createDirectories(root.resolve("res"))
            for (n in listOf("a1.xml", "a2.xml", "a12.xml", "a.xml")) {
                Files.writeString(root.resolve("res/$n"), "<x/>")
            }
            val ops = RootedFileOps(root)
            assertEquals(
                listOf("res/a1.xml", "res/a2.xml"),
                ops.list("res", "a?.xml", recursive = false),
                "? 是单字符通配（C# Directory.EnumerateFiles）",
            )
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `星号与问号混用`() {
        val root = tempRoot()
        try {
            Files.createDirectories(root.resolve("res"))
            for (n in listOf("img01.png", "img02.png", "img0.png", "img012.png")) {
                Files.writeString(root.resolve("res/$n"), "x")
            }
            val ops = RootedFileOps(root)
            assertEquals(
                listOf("res/img01.png", "res/img02.png"),
                ops.list("res", "img??.png", recursive = false),
            )
            assertTrue(
                ops.list("res", "img*.png", recursive = false).size == 4,
                "`*` 仍匹配任意串（不回归）",
            )
        } finally {
            deleteTree(root)
        }
    }
}