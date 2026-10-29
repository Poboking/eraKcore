package com.eratw.port.core.data.csv

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SAVE-08：同名多份 ERD + 表内/跨文件重复关键字。
 *
 * 钉的原版步骤（`ConstantData.cs:758-793 UserDefineLoadData` +
 * `ErhLoader.cs:342-388 PrepareERDFileNames`）：
 * 1. 同名（大写基名相同）的 erd/csv **全部**进同一个文件清单，不是先到者胜
 *    （ErhLoader.cs:363-366 / :383-386 是 `erdFileNames[key].Add(text)` 的追加分支）；
 *    读序 = 先 ERB 递归的 erd、后 CSV 顶层的 csv（:348-387 两段循环）⇒ 报错文案的
 *    「文件1/文件2」归属由它决定（用例 `跨文件同关键字报 DuplicateErdKey`）。
 * 2. 一个文件 = 一个 `names[29]` 数组，行首下标写进去、**后者覆盖前者**（:763-766 +
 *    :1796-1802），越界行连数组都进不去（:1791-1795）⇒ 空名行会把同下标的名字抹掉、
 *    越界那份同名不参与重复判定。
 * 3. 按下标升序累积成一张「关键字 → 下标 + 首次出现的文件」字典（:767-781）；
 *    关键字已存在 ⇒ CodeEE `DuplicateErdKey`（:771-773）。文件内（同名落在两个下标）与
 *    跨文件（两份同名表里都有该关键字）都算，下标相同也算 —— 键是关键字不是下标。
 * 4. 抛错时 :792 的 `erdNameToIntDics.Add` 到不了 ⇒ 整张表不登记，字符串下标读该变量
 *    报「不能用字符串指定」而不是「未定义关键字」。
 * 5. 文件循环走完才查变量名（:783-786）⇒ `DuplicateErdKey` 严格先于
 *    `DuplicateVariableDefine`（用例 `重键先报 DuplicateErdKey`）。
 *
 * 夹具变量名一律是 `EKW01`..`EKW12` 这种多字母名，且**表文件基名 = `#DIM` 声明名**一一对应
 * （装载侧按基名挂表，`UserKeywordTables.discover` → `baseKey`）：裸单字母 `A`..`Z` 在原版是
 * 内建 1 维整型系统变量（spec: `VariableData.cs:3040-3065`），移植侧同一份表在
 * `SystemLayout.int1D`（`SystemLayout.kt:41-46`），`VariableStore.declareUserVar` 的名字守卫
 * （`VariableStore.kt:1066`，判据 = `VariableRegistry.def(大写名)`）会先抛「与系统变量重名」，
 * 用例根本走不到这里要钉的重键判定。
 *
 * 移植侧两处**已登记的偏离**（不是本路能改的形态，见交付报告）：
 * - 原版只对 ERH 的 `#DIM` 挂表（dimlines 只收 isPrivate:false 的行，ErhLoader.cs:120-124），
 *   本移植把函数私有 `#DIM` 也挂表，同名多份私有声明共用一张表（`seen` 清单先吸掉，
 *   够不到 :783 的守卫）—— 用例 `同名私有声明共用一张表不误报` 钉这条；
 * - 原版 `ParserMediator.Warn` 的四类行级告警（MissingComma / FirstValueCanNotConvertToInt /
 *   OoRArray / VarKeyAreadyDefined）与 `ProhibitedArrayName` 在 core 侧没有告警通道，
 *   这里静默丢行；数据结果与原版一致，只有告警条数不同。
 */
class UserKeywordTablesDupTest {

    /**
     * 装载并取告警文案。
     *
     * M11-A WIRE-1：原版把每条 `#DIM` 的 ERD 挂载套在 try/catch 里，CodeEE 降级成 level 2
     * 告警并继续装载其它行（spec: ErhLoader.cs:328-332）⇒ `install` 不再向外抛，
     * 官方文案落在告警清单（本侧前缀 `erd名#下标位: `）。这里取回纯文案做逐字断言。
     */
    private fun installWarn(s: VariableStore, root: java.nio.file.Path): String? {
        val w = ArrayList<String>()
        UserKeywordTables.install(s, root, w)
        return w.singleOrNull()?.substringAfter(": ")
    }


    private fun store(): VariableStore =
        VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))

    private fun dim(body: String, isPrivate: Boolean = false): DimSpec =
        DimParser.parse("#DIM", body, isPrivate)

    private fun gameRoot(files: Map<String, String>): Path {
        val root = Files.createTempDirectory("erd-dup")
        for ((rel, text) in files) {
            val p = root.resolve(rel)
            p.parent?.createDirectories()
            p.writeText(text)
        }
        return root
    }

    @Test
    fun `同名两份表全部装载并合并成一张`() {
        val root = gameRoot(
            mapOf(
                "ERB/a/EKW01.erd" to "1,A\r\n",
                "CSV/EKW01.csv" to "2,B\r\n",
            ),
        )
        val group = UserKeywordTables.discover(root).getValue("EKW01")
        assertEquals(2, group.size, "同名多份要全部收进清单（ErhLoader.cs:363-386）")
        assertTrue(group[0].toString().endsWith(".erd", ignoreCase = true), group[0].toString())
        assertTrue(group[1].toString().endsWith(".csv", ignoreCase = true), group[1].toString())

        val s = store()
        s.declareUserVar(dim("EKW01, 10"))
        UserKeywordTables.install(s, root)
        assertEquals(mapOf("A" to 1, "B" to 2), s.userKeywordTable("EKW01", 0))
        assertEquals(2L, s.keywordToInteger("EKW01", 0, "B"), "CSV 那份不再被 erd 挤掉")
    }

    @Test
    fun `跨文件同关键字报 DuplicateErdKey`() {
        val root = gameRoot(
            mapOf(
                "ERB/a/EKW02.erd" to "1,A\r\n",
                "CSV/EKW02.csv" to "2,A\r\n",
            ),
        )
        val group = UserKeywordTables.discover(root).getValue("EKW02")
        val s = store()
        s.declareUserVar(dim("EKW02, 10"))
        val e = installWarn(s, root)
        // 官方文案（Lang.cs:978 / lang.emuera-zhs.xml:199），实参序 = 变量名、关键字、文件1、文件2
        // （调用点 ConstantData.cs:773）；文件1 = 首次定义该关键字的文件 = 先读的 erd。
        assertEquals(
            "变量\"EKW02\"的替换名\"A\"的定义重复。（文件1 - ${group[0]}）（文件2 - ${group[1]}）",
            e,
        )
        // 该条降级为告警 ⇒ :792 的 Add 到不了 ⇒ 整张表不登记 ⇒ 字符串下标报「不能用字符串指定」。
        assertNull(s.userKeywordTable("EKW02", 0))
        assertTrue(
            (assertFailsWith<CodeEE> { s.keywordToInteger("EKW02", 0, "A") }.message ?: "")
                .contains("不能用字符串指定"),
        )
    }

    @Test
    fun `表内同关键字落在两个下标报 DuplicateErdKey`() {
        val root = gameRoot(mapOf("CSV/EKW03.csv" to "1,A\r\n2,A\r\n"))
        val file = UserKeywordTables.discover(root).getValue("EKW03").single()
        val s = store()
        s.declareUserVar(dim("EKW03, 10"))
        val e = installWarn(s, root)
        // 同一份文件内重复：两个文件路径都是它自己（原版 dictionary[path] 与 filepath 同源，:773）。
        assertEquals(
            "变量\"EKW03\"的替换名\"A\"的定义重复。（文件1 - $file）（文件2 - $file）",
            e,
        )
        assertNull(s.userKeywordTable("EKW03", 0))
    }

    @Test
    fun `同关键字同下标跨文件同样算重复`() {
        val root = gameRoot(
            mapOf(
                "ERB/a/EKW04.erd" to "1,A\r\n",
                "CSV/EKW04.csv" to "1,A\r\n",
            ),
        )
        val s = store()
        s.declareUserVar(dim("EKW04, 10"))
        val e = installWarn(s, root)
        assertTrue((e ?: "").contains("替换名\"A\""), e ?: "")
    }

    @Test
    fun `同一下标两行后者覆盖`() {
        val root = gameRoot(mapOf("CSV/EKW05.csv" to "1,A\r\n1,B\r\n"))
        val s = store()
        s.declareUserVar(dim("EKW05, 10"))
        UserKeywordTables.install(s, root)
        // :1796-1802：重复下标只告警，`array[num2] = ...` 无条件写 ⇒ 留 B；A 连出现都没有，
        // 所以不触发 DuplicateErdKey。
        assertEquals(mapOf("B" to 1), s.userKeywordTable("EKW05", 0))
    }

    @Test
    fun `空名行抹掉同下标的名字`() {
        val root = gameRoot(mapOf("CSV/EKW06.csv" to "1,A\r\n1,B\r\n3,C\r\n3,\r\n"))
        val s = store()
        s.declareUserVar(dim("EKW06, 10"))
        UserKeywordTables.install(s, root)
        // 3 号位被空名行覆盖 ⇒ C 消失（原版 array[3] = ""，扫描时 IsNullOrEmpty 跳过，:769）。
        assertEquals(mapOf("B" to 1), s.userKeywordTable("EKW06", 0))
    }

    @Test
    fun `越界行不参与重复判定`() {
        val root = gameRoot(mapOf("CSV/EKW07.csv" to "1,A\r\n99,A\r\n"))
        val s = store()
        s.declareUserVar(dim("EKW07, 10"))
        UserKeywordTables.install(s, root)
        // :1791-1795：99 连数组都进不去 ⇒ 只剩 1 号位一份 A。
        assertEquals(mapOf("A" to 1), s.userKeywordTable("EKW07", 0))
    }

    @Test
    fun `数组长度为零只丢表不报错`() {
        val root = gameRoot(mapOf("CSV/EKW08.csv" to "1,A\r\n2,A\r\n"))
        val group = UserKeywordTables.discover(root).getValue("EKW08")
        // 声明层不允许 `#DIM EKW08, 0`（UserVariables.kt:620-622 的总元素数 1..1000000 校验），
        // 所以这里直接钉装载层：长度 0 ⇒ 一个下标都扫不到 ⇒ 空表、不抛 CodeEE
        // （原版 :1786-1790 的 ProhibitedArrayName 是 level 2 告警 + 终止本文件，不抛）。
        assertEquals(emptyMap<String, Int>(), UserKeywordTables.load(group, "EKW08", 0))
    }

    @Test
    fun `同名私有声明共用一张表不误报`() {
        val root = gameRoot(mapOf("CSV/EKW09.csv" to "1,A\r\n2,B\r\n"))
        val s = store()
        s.declareUserVar(dim("EKW09, 10", isPrivate = true), "F1")
        s.declareUserVar(dim("EKW09, 10", isPrivate = true), "F2")
        UserKeywordTables.install(s, root)
        // 原版只对 ERH 的 #DIM 挂表，私有声明根本走不到 UserDefineLoadData ⇒ 不报变量名重复；
        // 移植侧的 `seen` 清单同样把这条重复登记吸掉（挂载形态限制，见类 KDoc）。
        assertEquals(mapOf("A" to 1, "B" to 2), s.userKeywordTable("EKW09", 0))
    }

    @Test
    fun `同一 erd 变量名挂到两个下标位报 DuplicateVariableDefine`() {
        val root = gameRoot(mapOf("CSV/EKW10.csv" to "1,A\r\n"))
        val s = store()
        // CHARADATA 的下标位整体后移一位 ⇒ 同一个 erd 变量名 `EKW10` 登记两次（原版 :783 的形态）。
        s.declareUserVar(dim("CHARADATA EKW10, 10"))
        s.declareUserVar(dim("EKW10, 10", isPrivate = true), "F1")
        val e = installWarn(s, root)
        // 官方文案：Lang.cs:982 / lang.emuera-zhs.xml:200（调用点 ConstantData.cs:785）。
        assertEquals("变量EKW10的定义重复", e)
    }

    @Test
    fun `重键先报 DuplicateErdKey`() {
        val root = gameRoot(mapOf("CSV/EKW11.csv" to "1,A\r\n5,A\r\n"))
        val s = store()
        // 第一份声明（长度 3）看不到 5 号位的重键，登记成功；
        // 第二份（长度 10）在文件循环里就撞上重键 ⇒ 判定顺序 = :773 先于 :783。
        s.declareUserVar(dim("CHARADATA EKW11, 3"))
        s.declareUserVar(dim("EKW11, 10", isPrivate = true), "F1")
        val e = installWarn(s, root)
        assertTrue((e ?: "").contains("替换名\"A\""), e ?: "")
        assertEquals(mapOf("A" to 1), s.userKeywordTable("EKW11", 1), "第一份的表已按原版登记，不受第二份影响")
    }

    @Test
    fun `多维变量逐维的 erd 名各自成键`() {
        val root = gameRoot(
            mapOf(
                "CSV/EKW12@1.csv" to "1,A\r\n",
                "CSV/EKW12@2.csv" to "1,A\r\n",
            ),
        )
        val s = store()
        s.declareUserVar(dim("EKW12, 10, 10"))
        UserKeywordTables.install(s, root)
        // 原版键是 `NAME@1` / `NAME@2`（ErhLoader.cs:287），两份同名关键字分属两个键 ⇒ 不重复。
        assertEquals(mapOf("A" to 1), s.userKeywordTable("EKW12", 0))
        assertEquals(mapOf("A" to 1), s.userKeywordTable("EKW12", 1))
    }

    /**
     * 隔离性（ErhLoader.cs:328-332 的本体）：一条坏表只降级成自己那一条告警，
     * 其它变量的表照挂、会话照起 —— 这正是 M11-A WIRE-1 补上的形态（改前 install 直接抛，
     * 真机上等价于整局装载失败）。
     */
    @Test
    fun `坏表只停自己一条其余变量照常挂表`() {
        val root = gameRoot(
            mapOf(
                "CSV/EKW13.csv" to "1,A\r\n2,A\r\n",
                "CSV/EKW14.csv" to "1,B\r\n",
            ),
        )
        val s = store()
        s.declareUserVar(dim("EKW13, 10"))
        s.declareUserVar(dim("EKW14, 10"))
        val w = ArrayList<String>()
        UserKeywordTables.install(s, root, w)
        assertEquals(1, w.size, "坏一条、其余照常：$w")
        assertTrue("EKW13" in w.single(), w.single())
        assertNull(s.userKeywordTable("EKW13", 0), "重键的那张不登记")
        assertEquals(mapOf("B" to 1), s.userKeywordTable("EKW14", 0), "坏表不得连累别的变量")
    }
}
