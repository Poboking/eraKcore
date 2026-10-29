package com.eratw.port.render.input

/**
 * 键盘宏表（F1-F12 预填的数据源）—— `MinorShift.Emuera.Runtime.Script/KeyMacro.cs` 的
 * 静态类改成实例（AGENTS.md 铁律 4：禁静态可变全局；安卓侧一份、单测各一份，互不串味）。
 *
 * ## 容量与槽位号（写死）
 * - 组 `MaxGroup = 10`（`KeyMacro.cs:15`）、键 `MaxFkey = 12`（:17）、总槽 `MaxMacro = 120`（:19）；
 * - 槽位号 `FkeyNum + groupNum * 12`（`SetMacro` :144-148 / `GetMacro` :150-153），
 *   即**组优先连续**：组 0 占 0..11，组 1 占 12..23。
 *
 * ## 宏文件名与前缀（两条语言口径）
 * 存盘文件是 `ExeDir + "macro.txt"`（:31），每行 = 名称前缀 + 内容。名称前缀本身是**本地化串**：
 * - 组 0：`Lang.KeyMacro.MacroKeyF` = 「マクロキーF{0}:」（`Lang.cs:4226`），
 *   简中界面为「宏快捷键F{0}:」（参考表 `Properties.lang.emuera-zhs.xml:1000`，
 *   语料实际文件 `eratw-画蛇添足版eratw4.980/lang/emuera.zhs.xml:777`）；
 * - 组 n>0：`Lang.KeyMacro.GMacroKeyF` = 「G{0}:マクロキーF{1}:」（`Lang.cs:4230`），
 *   简中为「G{0}:宏快捷键F{1}:」（参考表 xml:1001、语料 `lang/emuera.zhs.xml:776`）；
 * - 组名默认串：`Lang.KeyMacro.SetMacroGroup` = 「マクログループ{0}に設定」（`Lang.cs:4222`，
 *   简中为「设置为按键宏组{0}」，参考表 xml:999、语料 `lang/emuera.zhs.xml:778`）。
 * 板上口径见 `docs/golden-runbook.md:106`（「宏快捷键F1:内容」「G1:宏快捷键F1:内容」）。
 * 前缀由构造参数注入（[NameScheme]），本层不写死界面语言。
 *
 * ## 官方怪癖（原样复刻，ADR-005）
 * 1. **组名前缀是写死的日文**「グループ」，与界面语言无关：存盘 `WriteLine("グループ" + i + ":" + name)`
 *    （:71），读盘判定也按「グループ」的长度取字符（:105-119）。
 * 2. 读组行有个**长度门槛** `text.Length < 4 + 4` 直接跳过（:107-110），
 *    所以 `グループ0:a`（7 字符）这类合法短组名被无声丢弃，`グループ0:ab`（8 字符）才收。
 * 3. 组行的数字只认 ASCII 数字且紧跟冒号：`text[4] - 48` 落在 0..9 且 `text[5] == ':'`，
 *    否则跳过（:111-115）。
 * 4. 一条行匹配到宏名后 **break**（:130），只写一个槽；匹配按 0..119 的槽序扫（:121-131）。
 * 5. 整段读取包在 `catch { }` 里（:135-137），**任何异常都吞掉**；打不开文件同样静默返回（:92-95）。
 * 6. `ResetNames()` 除了重建名称与组名，还把 120 个槽**清空**（:46），
 *    所以「改名」等于「丢宏」。
 * 7. `SaveMacro()` 在没有改动时直接返回 true 不写盘（:61-64）；写盘异常返回 false（:78-81）。
 *    **落盘成功后也不回置** `isMacroChanged`（:59-87 整段没有一处赋 false）⇒ 一旦改过就永久 dirty。
 * 8. 组行的两条不合格判定是 `continue`（:109、:114），**整条剩下的宏名扫描也不做**；
 *    只有判定全部通过、真的写了组名时才会落到 :121 的宏名扫描。
 *
 * 文件读写本身（编码嗅探 `EraStreamReader`、`Config.Encode`）属宿主绑定，本层只做
 * 「行序列 ↔ 表状态」的纯函数（:89-142 / :59-87 的正文部分）。
 */
class KeyMacroTable(val nameScheme: NameScheme = NameScheme.JAPANESE) {

    /** 名称前缀与默认组名的生成口径（见类文档「宏文件名与前缀」）。 */
    data class NameScheme(
        val macroName: (group: Int, fkey: Int) -> String,
        val defaultGroupName: (group: Int) -> String,
        val groupLinePrefix: String = KeyMacroTable.GROUP_PREFIX,
    ) {
        companion object {
            /** 日文界面默认（`Lang.cs:4226`、`:4230`）。 */
            val JAPANESE = NameScheme(
                macroName = { g, f -> if (g == 0) "マクロキーF${f + 1}:" else "G$g:マクロキーF${f + 1}:" },
                defaultGroupName = { "マクログループ${it}に設定" },
            )

            /**
             * 简体中文界面默认（xml:1000、:1001）。
             * TODO(verify)：`defaultGroupName` 简中原文「设置为按键宏组{0}」（xml:999）是
             * **句子**而不是名词，存盘时会被原样写进 `グループN:` 后面（:71），
             * 读回来即组名；本层照抄该原文，不做「看起来更合理」的改写。
             */
            val SIMPLIFIED_CHINESE = NameScheme(
                macroName = { g, f -> if (g == 0) "宏快捷键F${f + 1}:" else "G$g:宏快捷键F${f + 1}:" },
                defaultGroupName = { "设置为按键宏组$it" },
            )
        }
    }

    private val names: Array<String> = Array(SLOT_COUNT) { i ->
        nameScheme.macroName(i / MAX_FKEY, i % MAX_FKEY)
    }

    private val contents: Array<String> = Array(SLOT_COUNT) { "" }

    private val groupNames: Array<String> = Array(MAX_GROUP) { nameScheme.defaultGroupName(it) }

    /** 存盘用：只有被 [set] 改过才落盘（`isMacroChanged` :27、判定 :61-64）。 */
    var changed: Boolean = false
        private set

    /** 某组某功能键的宏内容（`GetMacro` :150-153）；越界照原版抛下标异常，不做宽容。 */
    fun get(fkey: Int, group: Int): String = contents[slotOf(fkey, group)]

    /** 某组某功能键的名称前缀（存盘与匹配都用它，:75、:123-128）。 */
    fun nameOf(fkey: Int, group: Int): String = names[slotOf(fkey, group)]

    /** 组名（`GetGroupName` :155-158）。 */
    fun groupName(group: Int): String = groupNames[group]

    /**
     * 写宏（`SetMacro` :144-148）。
     * 注意与调用方的配合：F1-F12 记录那一路只在框内非空时才写
     * （`MainWindow.cs:501-504` 的 `IsNullOrEmpty` 守卫），空框按 Shift+F1 是**不改**旧宏的。
     */
    fun set(fkey: Int, group: Int, value: String) {
        contents[slotOf(fkey, group)] = value
        changed = true
    }

    /** 改名并清空全部宏（`ResetNames` :38-57，怪癖 6）。 */
    fun resetNames(scheme: NameScheme = nameScheme) {
        for (g in 0 until MAX_GROUP) {
            groupNames[g] = scheme.defaultGroupName(g)
            for (f in 0 until MAX_FKEY) {
                val slot = f + g * MAX_FKEY
                names[slot] = scheme.macroName(g, f)
                contents[slot] = ""
            }
        }
    }

    /** 表当前状态（组 0 在前），只给单测/绑定层读数用。 */
    fun snapshot(): List<List<String>> = (0 until MAX_GROUP).map { g ->
        (0 until MAX_FKEY).map { f -> contents[f + g * MAX_FKEY] }
    }

    /**
     * 存盘行序列（`SaveMacro` 的正文 :69-76）：先 10 行组名，再 120 行「名称前缀 + 内容」。
     * 落盘编码是 `Config.Encode`（:68），属宿主。
     */
    fun serializeLines(): List<String> {
        val out = ArrayList<String>(MAX_GROUP + SLOT_COUNT)
        for (i in 0 until MAX_GROUP) {
            out.add(groupLinePrefix + i + ":" + groupNames[i])
        }
        for (j in 0 until SLOT_COUNT) {
            out.add(names[j] + contents[j])
        }
        return out
    }

    /**
     * 读入 `macro.txt` 的行序列（`LoadMacroFile` 的正文 :99-133）。
     *
     * 逐条照抄判定顺序：空行/分号行跳过（:101-104）→ 组行（:105-120，含怪癖 2、3、8 的
     * 两条 `continue`）→ 宏名扫描（:121-132，命中即 break）。**不**改判定、**不**加容错。
     * 原版吞异常（:135-137）在文件读取侧，本函数是纯计算，越界仍会抛（调用方自己兜）。
     */
    fun loadLines(lines: List<String>) {
        lines@ for (text in lines) {
            if (text.isEmpty() || text[0] == ';') {
                continue
            }
            if (text.startsWith(groupLinePrefix)) {
                // :107-110 长度门槛：不足 8 字符的组行整条丢掉，且**不**再做宏名扫描。
                if (text.length < groupLinePrefix.length + GROUP_LINE_MIN_TAIL) {
                    continue@lines
                }
                val digit = text[groupLinePrefix.length] - '0'
                // :111-115 数字与冒号判定：不合格同样整条跳过。
                if (digit !in 0 until MAX_GROUP || text[groupLinePrefix.length + 1] != ':') {
                    continue@lines
                }
                groupNames[digit] = text.substring(groupLinePrefix.length + 2)
            }
            // :121-132 写成功组名之后**仍然**继续扫宏名（原版这里没有 continue）。
            for (i in 0 until SLOT_COUNT) {
                if (text.startsWith(names[i])) {
                    contents[i] = text.substring(names[i].length)
                    break
                }
            }
        }
    }

    private fun slotOf(fkey: Int, group: Int): Int = fkey + group * MAX_FKEY

    companion object {
        /** `MaxGroup`（`KeyMacro.cs:15`）。 */
        const val MAX_GROUP: Int = 10

        /** `MaxFkey`（:17）。 */
        const val MAX_FKEY: Int = 12

        /** `MaxMacro`（:19）。 */
        const val SLOT_COUNT: Int = 120

        /** 组行的**写死日文**前缀（:71、:105）。 */
        const val GROUP_PREFIX: String = "グループ"

        /** 组行长度门槛的尾部长度（:107-110 里那个字面量 4）。 */
        const val GROUP_LINE_MIN_TAIL: Int = 4
    }

    private val groupLinePrefix: String = nameScheme.groupLinePrefix
}
