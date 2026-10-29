package com.eratw.port.render.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [KeyMacroTable] —— `macro.txt` 的行格式与槽位表（F1-F12 预填的数据源）。
 *
 * ## 期望值出处
 * - 容量与槽位号：`MinorShift.Emuera.Runtime.Script/KeyMacro.cs:15`（MaxGroup 10）、
 *   :17（MaxFkey 12）、:19（MaxMacro 120）、:147/:152（`FkeyNum + groupNum * 12`，组优先连续）。
 * - 名称前缀（本地化串）：`Lang.cs:4226`「マクロキーF{0}:」、:4230「G{0}:マクロキーF{1}:」、
 *   :4222「マクログループ{0}に設定」；简中对照取**语料实际文件**
 *   `eratw-画蛇添足版eratw4.980/lang/emuera.zhs.xml:777`（组零形态）、:776（G 形态）、:778（组名）。
 * - 板上格式口径：`docs/golden-runbook.md:106`（`宏快捷键F1:内容`、`G1:宏快捷键F1:内容`）。
 * - 存盘行序 :69-76（先 10 行组名、再 120 行「名称 + 内容」），组名前缀写死日文 :71。
 * - 读盘判定 :101-104（空行/分号行）、:105-120（组行：:107-110 长度门槛、:111-115 数字与冒号，
 *   两条不合格都是 `continue` ⇒ 宏名扫描也不做）、:121-132（宏名扫描，:130 `break`）。
 * - 改名清槽 :38-57（:46）；置脏只在 SetMacro（:146）；SaveMacro 不回置脏（:59-87）。
 *
 * 语料目录里**没有** `macro.txt`（`eratw-画蛇添足版eratw4.980` 全盘 `find -iname "macro*"` 无命中），
 * 所以样例行按 :69-76 的存盘格式 + 语料 :776-778 的名称串手工拼出，不是实现回读。
 * 基准 `pc-golden-root.txt` 没有宏相关行，本文件全部钉 C# 行号。
 */
class KeyMacroTableTest {

    private val zh = KeyMacroTable.NameScheme.SIMPLIFIED_CHINESE

    @Test
    fun `名称前缀取语言表的两种形态`() {
        val ja = KeyMacroTable()
        assertEquals("マクロキーF1:", ja.nameOf(fkey = 0, group = 0), "Lang.cs:4226")
        assertEquals("マクロキーF12:", ja.nameOf(fkey = 11, group = 0))
        assertEquals("G1:マクロキーF1:", ja.nameOf(fkey = 0, group = 1), "Lang.cs:4230")
        assertEquals("G9:マクロキーF12:", ja.nameOf(fkey = 11, group = 9))

        val cn = KeyMacroTable(zh)
        assertEquals("宏快捷键F1:", cn.nameOf(fkey = 0, group = 0), "语料 lang/emuera.zhs.xml:777")
        assertEquals("G1:宏快捷键F1:", cn.nameOf(fkey = 0, group = 1), "语料 lang/emuera.zhs.xml:776")
    }

    @Test
    fun `组名默认串照抄语言表句子`() {
        assertEquals("マクログループ0に設定", KeyMacroTable().groupName(0), "Lang.cs:4222")
        assertEquals("マクログループ9に設定", KeyMacroTable().groupName(9))
        // 简中原文是**句子**，原版照样写进グループN:后面（:71），读回来即组名
        assertEquals("设置为按键宏组0", KeyMacroTable(zh).groupName(0), "语料 lang/emuera.zhs.xml:778")
    }

    @Test
    fun `槽位号组优先连续`() {
        val table = KeyMacroTable(zh)
        table.set(fkey = 0, group = 1, value = "abc")
        assertEquals("abc", table.get(fkey = 0, group = 1), "FkeyNum + groupNum * 12（:147）")
        assertEquals("", table.get(fkey = 1, group = 0), "邻格不受影响")
        val flat = table.snapshot().flatten()
        assertEquals(120, flat.size, "MaxMacro（:19）")
        assertEquals("abc", flat[12], "组零占 0..11 ⇒ 组一的 F1 落在第 12 格")
        assertEquals(10, table.snapshot().size, "MaxGroup（:15）")
        assertEquals(12, table.snapshot().first().size, "MaxFkey（:17）")
    }

    @Test
    fun `读组零形态的宏行`() {
        val table = KeyMacroTable(zh)
        table.loadLines(listOf("宏快捷键F1:123"))
        assertEquals("123", table.get(fkey = 0, group = 0))
        assertEquals("", table.get(fkey = 1, group = 0), "只有命中的那一格被写")
    }

    @Test
    fun `读GN组形态的宏行`() {
        val table = KeyMacroTable(zh)
        table.loadLines(listOf("G1:宏快捷键F3:xyz"))
        assertEquals("xyz", table.get(fkey = 2, group = 1), "G1: 前缀 ⇒ 组一（:123-129）")
        assertEquals("", table.get(fkey = 2, group = 0), "不得落到组零")
    }

    @Test
    fun `空行与分号行整条跳过`() {
        val table = KeyMacroTable(zh)
        table.set(fkey = 0, group = 0, value = "keep")
        table.loadLines(listOf("", ";comment", "宏快捷键F1:123"))
        assertEquals("123", table.get(fkey = 0, group = 0), ":101-104 之后才轮到匹配；分号行不覆盖")
    }

    @Test
    fun `不匹配任何名称的行静默丢弃`() {
        val table = KeyMacroTable(zh)
        table.loadLines(listOf("随便一行", "  "))
        assertEquals(List(10) { List(12) { "" } }, table.snapshot(), ":121-132 无命中 ⇒ 什么都不写")
    }

    @Test
    fun `组行长度门槛`() {
        val table = KeyMacroTable(zh)
        // 「グループ0:a」= 4 + 3 = 7 字符，不足 :107 的前缀长度 + 4 ⇒ 整条丢掉
        table.loadLines(listOf("グループ0:a"))
        assertEquals("设置为按键宏组0", table.groupName(0), "怪癖 2：合法形状的短组名被无声丢弃")
        // 8 字符起才收
        table.loadLines(listOf("グループ0:ab"))
        assertEquals("ab", table.groupName(0))
    }

    @Test
    fun `组行的数字与冒号判定`() {
        val table = KeyMacroTable(zh)
        table.loadLines(listOf("グループX:abcd", "グループ0xabcd", "グループ10:abcd"))
        assertEquals("设置为按键宏组0", table.groupName(0), "text[4] 不是 ASCII 数字 ⇒ 跳过（:111-115）")
        assertEquals("设置为按键宏组1", table.groupName(1), "「グループ10:」的第六个字符不是冒号 ⇒ 跳过")
        assertEquals("设置为按键宏组9", table.groupName(9))
        table.loadLines(listOf("グループ9:九组"))
        assertEquals("九组", table.groupName(9), "零到九都在域内（num 大于 9 才跳）")
    }

    @Test
    fun `不合格组行不再扫宏名`() {
        // 让某个宏名本身也以「グループ」开头，才能看出 :109/:114 的 continue 跳过了宏名扫描
        val scheme = KeyMacroTable.NameScheme(
            macroName = { _, f -> if (f == 0) "グループ零:" else "G0:宏快捷键F${f + 1}:" },
            defaultGroupName = { "组名$it" },
        )
        val table = KeyMacroTable(scheme)
        table.loadLines(listOf("グループ零:内容"))
        assertEquals("组名0", table.groupName(0), "数字位不合格 ⇒ 不写组名")
        assertEquals("", table.get(fkey = 0, group = 0), ":114 的 continue 让宏名扫描也不做（怪癖 8）")
    }

    @Test
    fun `合格组行之后仍继续扫宏名`() {
        val scheme = KeyMacroTable.NameScheme(
            macroName = { _, f -> if (f == 0) "グループ1:" else "G0:宏快捷键F${f + 1}:" },
            defaultGroupName = { "组名$it" },
        )
        val table = KeyMacroTable(scheme)
        table.loadLines(listOf("グループ1:内容"))
        assertEquals("内容", table.groupName(1), "组行合格 ⇒ 先写组名（:116-119）")
        assertEquals("内容", table.get(fkey = 0, group = 0), "同一条行继续参与宏名扫描（:121，原版这里没有 continue）")
    }

    @Test
    fun `宏名扫描命中即停只写一格`() {
        val scheme = KeyMacroTable.NameScheme(
            macroName = { _, f -> when (f) { 0 -> "KEY:"; 1 -> "KEY:SUB:"; else -> "G0:宏快捷键F${f + 1}:" } },
            defaultGroupName = { "组名$it" },
        )
        val table = KeyMacroTable(scheme)
        table.loadLines(listOf("KEY:SUB:x"))
        assertEquals("SUB:x", table.get(fkey = 0, group = 0), "槽零的名称是槽一名称的前缀 ⇒ 升序扫描先撞槽零")
        assertEquals("", table.get(fkey = 1, group = 0), ":130 的 break ⇒ 不再看后面的槽（怪癖 4）")
    }

    @Test
    fun `改名等于丢宏`() {
        val table = KeyMacroTable(zh)
        table.set(fkey = 3, group = 2, value = "keep?")
        assertEquals("keep?", table.get(fkey = 3, group = 2))
        table.resetNames()
        assertEquals("", table.get(fkey = 3, group = 2), "ResetNames 把 120 格清空（:46，怪癖 6）")
        assertEquals("G2:宏快捷键F4:", table.nameOf(fkey = 3, group = 2), "名称同时按新表重建（:49/:53）")
    }

    @Test
    fun `改动位只由写宏触发`() {
        val table = KeyMacroTable(zh)
        assertEquals(false, table.changed, "isMacroChanged 初值 false（:27）")
        table.loadLines(listOf("宏快捷键F1:123"))
        assertEquals(false, table.changed, "LoadMacroFile 不置脏（:89-142 无一处赋 true）")
        table.resetNames()
        assertEquals(false, table.changed, "ResetNames 也不置脏（:38-57）")
        table.set(fkey = 0, group = 0, value = "x")
        assertEquals(true, table.changed, "SetMacro 置脏（:146）")
    }

    @Test
    fun `存盘行序是先十行组名再一百二十行宏`() {
        val table = KeyMacroTable(zh)
        table.set(fkey = 0, group = 0, value = "123")
        table.set(fkey = 0, group = 1, value = "abc")
        val lines = table.serializeLines()
        assertEquals(130, lines.size, "10 + 120（:69-76）")
        assertEquals("グループ0:设置为按键宏组0", lines[0], "组名前缀写死日文，与界面语言无关（:71，怪癖 1）")
        assertEquals("グループ9:设置为按键宏组9", lines[9])
        assertEquals("宏快捷键F1:123", lines[10], "组零的 F1 紧跟在十行组名之后")
        assertEquals("G1:宏快捷键F1:abc", lines[22], "组一的 F1 落在第 10 + 12 行")
    }

    @Test
    fun `存盘结果读回来表状态不变`() {
        val origin = KeyMacroTable(zh)
        origin.set(fkey = 5, group = 0, value = "ＡＢ１")
        origin.set(fkey = 0, group = 9, value = "z")
        origin.loadLines(listOf("グループ3:自定义组名"))
        val reloaded = KeyMacroTable(zh)
        reloaded.loadLines(origin.serializeLines())
        assertEquals(origin.snapshot(), reloaded.snapshot(), ":69-76 与 :99-133 互为逆运算")
        assertEquals("自定义组名", reloaded.groupName(3))
        assertEquals(10, reloaded.snapshot().size)
    }

    @Test
    fun `空内容的槽照样落盘`() {
        val table = KeyMacroTable(zh)
        table.set(fkey = 1, group = 0, value = "")
        assertEquals("宏快捷键F2:", table.serializeLines()[11], "名称前缀 + 空串 ⇒ 只有前缀一行（:75）")
    }

    @Test
    fun `越界槽号照原版抛`() {
        val table = KeyMacroTable(zh)
        // 原版是裸数组下标（macro[FkeyNum + groupNum * 12]，:147/:152），没有任何夹取
        assertFailsWith<IndexOutOfBoundsException> { table.get(fkey = 0, group = 10) }
        assertFailsWith<IndexOutOfBoundsException> { table.set(fkey = 0, group = 10, value = "x") }
        assertFailsWith<IndexOutOfBoundsException> { table.groupName(10) }
    }
}
