package com.eratw.port.core.loader

import com.eratw.port.core.compile.CallDefaults
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.loader.BootMetadata.MethodType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 侧车 `param-defaults` / `method-types` 两段（R7 波 0：设备端增量编译的前置数据）的编解码判据。
 *
 * 这两段存在的理由（缺了就是「同一行 ERB 两种行为」，见 `CONTRACT-R7.md` §1.5）：
 * - `param-defaults`：缺省实参默认值只在**编译期**补位（spec: FunctionLabelLine.cs:49 /
 *   CalledFunction.cs:167），`.erbc` 容器里没有。补丁编译没有全量函数头，读不到它就只能
 *   「省略位不写、形参保留既有值」，而 PC 全量编译会把默认值补上。
 * - `method-types`：`FuncMeta.returnsStr` 只有两态，类型未知与 INT 混同；而
 *   `buildExprCallTable` 要三分桶（误把 `#FUNCTION` 降级成字符串通道会静默读错值）。
 *
 * 兼容口径照 `str-constants` / `load-warnings` 的「尾部可选段」先例：
 * - 段序在 `load-warnings` 之后、`end` 之前；
 * - **空表整段省略**（空表与缺段语义等价，都是「没有该数据」）⇒ 不含本数据的模型文本与
 *   引入本段之前逐字节相同；
 * - 缺段 ⇒ 读回空表（旧侧车经新读侧不炸）；
 * - **不升 `FORMAT_VERSION`**：加可选段是兼容改动，旧读侧遇到未知关键字会在 `next("end")`
 *   处明确报错，不是静默错读（同 `str-constants` / `load-warnings` 两次先例）；
 * - 计数过 `checkCount`（CMP-82 点名 `str-constants` 是唯一没调它的段，本段不复制这个洞）；
 * - 严格互逆、禁静默容错（CMP-83 教训）。
 *
 * **负向用例是硬要求**：只写正向用例的闸等于没闸（`CONTRACT-R7.md` §1.6 的判据全部依赖
 * 「故意做错必须红」，不是「做对必须绿」）。
 */
class BootMetaParamDefaultsTest {

    // ------------------------------------------------------------------
    // 样本
    // ------------------------------------------------------------------

    /** 逐位默认值：int / 无默认值 / str / 无默认值 —— 位序与 null 位都要能原样回来。 */
    private fun defaults(): CallDefaults = linkedMapOf(
        "M_KOJO_K1_1" to listOf(IntLiteral(1L, 0, 0), null, StrLiteral("あ", 0, 0), null),
        "ADD_MONEY" to listOf(null, IntLiteral(-5L, 3, 9)),
        "GREET" to listOf(StrLiteral("", 0, 0)),
    )

    private fun types(): Map<String, MethodType> = linkedMapOf(
        "ADD_MONEY" to MethodType.INT,
        "GET_NAME" to MethodType.STR,
        "MYSTERY" to MethodType.UNKNOWN,
    )

    private fun meta(
        paramDefaults: CallDefaults = emptyMap(),
        methodTypes: Map<String, MethodType> = emptyMap(),
        loadWarnings: List<BootMetadata.LoadWarning>? = null,
    ) = BootMetadata(
        loadWarnings = loadWarnings,
        paramDefaults = paramDefaults,
        methodTypes = methodTypes,
    )

    /** 在 `end` 之前插入若干记录行（造坏档用）。 */
    private fun withTail(extra: List<String>, base: BootMetadata = BootMetadata()): String {
        val lines = base.encodeText().lines().toMutableList()
        val iEnd = lines.indexOf("end")
        assertTrue(iEnd > 0, "基准文本必须有 end 行: $lines")
        lines.addAll(iEnd, extra)
        return lines.joinToString("\n")
    }

    // ------------------------------------------------------------------
    // 1) 往返：位序、null 位、三态
    // ------------------------------------------------------------------

    @Test
    fun `形参默认值经侧车往返保位序与 null 位`() {
        val m = meta(paramDefaults = defaults())
        val text = m.encodeText()
        val back = BootMetadata.parse(text)

        assertEquals(m.paramDefaults, back.paramDefaults, "函数键、逐位默认值、null 位全部原样")
        // 位序必须逐位对齐：`ADD_MONEY` 的第一位是 null（必填），第二位是 -5
        assertEquals(
            listOf(null, IntLiteral(-5L, 3, 9)),
            back.paramDefaults.getValue("ADD_MONEY"),
            "位序 = 形参序，重排即错位",
        )
        assertEquals(text, back.encodeText(), "文本 → 模型 → 文本 逐字节闭环")
    }

    @Test
    fun `方法类型三态经侧车往返`() {
        val m = meta(methodTypes = types())
        val text = m.encodeText()
        val back = BootMetadata.parse(text)

        assertEquals(types(), back.methodTypes, "I/S/U 三态逐键原样")
        assertEquals(MethodType.UNKNOWN, back.methodTypes.getValue("MYSTERY"), "第三态不许被折成 INT/STR")
        assertEquals(text, back.encodeText(), "文本 → 模型 → 文本 逐字节闭环")
    }

    @Test
    fun `两段同时存在时互不干扰且文本闭环`() {
        val m = meta(paramDefaults = defaults(), methodTypes = types())
        val back = BootMetadata.parse(m.encodeText())
        assertEquals(m.paramDefaults, back.paramDefaults)
        assertEquals(m.methodTypes, back.methodTypes)
        assertEquals(m.encodeText(), back.encodeText())
    }

    @Test
    fun `空默认值表写成空列表记号并可原样写回`() {
        // 写出侧不会产 `()`（CallDefaultsCollector.add 的 `if (injectable)` 保证），
        // 但收下后能原样写回 ⇒ 文本往返互逆，不亏。
        val text = withTail(listOf("param-defaults 1", "param-default \"EMPTY_F\" ()"))
        val back = BootMetadata.parse(text)
        assertEquals(listOf<Expr?>(), back.paramDefaults.getValue("EMPTY_F"), "`()` = 空表而非 null 位")
        assertTrue(back.encodeText().contains("param-default \"EMPTY_F\" ()"), "空表能原样写回")
    }

    // ------------------------------------------------------------------
    // 2) 兼容：空表省略整段 / 缺段照样装载
    // ------------------------------------------------------------------

    @Test
    fun `空表整段省略 —— 默认字段不改变输出文本`() {
        // 关键判据：给模型显式塞空表，输出必须与不塞**逐字节相同**。
        // 这条一旦破了，所有既有 golden 文本 / 快照指纹都会无故变动。
        val warnings = listOf(BootMetadata.LoadWarning("A.ERB", 1, "x"))
        val plain = meta(loadWarnings = warnings)
        val explicitEmpty = meta(paramDefaults = emptyMap(), methodTypes = emptyMap(), loadWarnings = warnings)

        val text = plain.encodeText()
        assertEquals(text, explicitEmpty.encodeText(), "空表必须整段省略，不许写 `param-defaults 0`")
        assertFalse(text.contains("param-defaults"), "空表不许留段头: $text")
        assertFalse(text.contains("method-types"), "空表不许留段头: $text")
        assertFalse(text.contains("param-default "), "空表不许留条目: $text")
        assertFalse(text.contains("method-type "), "空表不许留条目: $text")
    }

    @Test
    fun `旧侧车缺段照样装载 —— 两表读成空表而不是炸`() {
        // 真·旧侧车 = 本次改动之前 erbcc 写出的文本：没有这两段。
        val oldText = meta(loadWarnings = listOf(BootMetadata.LoadWarning("A.ERB", 1, "x"))).encodeText()
        assertFalse(oldText.contains("param-defaults"), "前提：这份文本必须是旧形态")
        assertFalse(oldText.contains("method-types"), "前提：这份文本必须是旧形态")

        val back = BootMetadata.parse(oldText)
        assertEquals(emptyMap(), back.paramDefaults, "缺段 = 空表（不是 null、不是异常）")
        assertEquals(emptyMap(), back.methodTypes, "缺段 = 空表")
        assertEquals(1, back.loadWarningsOrEmpty().size, "旧段仍然读得回来")
        assertEquals(oldText, back.encodeText(), "旧文本经新读侧、新写出端仍然逐字节不变")
    }

    @Test
    fun `段位次在 load-warnings 之后 end 之前`() {
        val lines = meta(
            paramDefaults = defaults(),
            methodTypes = types(),
            loadWarnings = listOf(BootMetadata.LoadWarning("A.ERB", 1, "x")),
        ).encodeText().split('\n')

        val iWarn = lines.indexOfFirst { it.startsWith("load-warnings ") }
        val iDef = lines.indexOfFirst { it.startsWith("param-defaults ") }
        val iType = lines.indexOfFirst { it.startsWith("method-types ") }
        val iEnd = lines.indexOf("end")

        assertTrue(iWarn in 0 until iDef, "param-defaults 必须排在 load-warnings 之后: iWarn=$iWarn iDef=$iDef")
        assertTrue(iType > iDef, "method-types 排在 param-defaults 之后: iDef=$iDef iType=$iType")
        assertEquals(lines.size - 2, iEnd, "end 必须是最后一条记录")
        assertTrue(iEnd > iType, "两段都必须在 end 之前")
        // 段头计数 == 条目行数（写出侧自证，读侧另有 checkCount 兜）
        assertEquals(defaults().size, lines.count { it.startsWith("param-default ") })
        assertEquals(types().size, lines.count { it.startsWith("method-type ") })
    }

    @Test
    fun `写出确定性 —— 键排序且两次编码逐字节相同`() {
        // 故意用**逆序**插入，证明输出与插入序无关（Map 按键排序）。
        val reversed = linkedMapOf(
            "Z_LAST" to MethodType.STR,
            "A_FIRST" to MethodType.INT,
        )
        val forward = linkedMapOf(
            "A_FIRST" to MethodType.INT,
            "Z_LAST" to MethodType.STR,
        )
        val a = meta(methodTypes = reversed).encodeText()
        val b = meta(methodTypes = forward).encodeText()
        assertEquals(a, b, "输出只取决于内容，不取决于插入序")
        assertEquals(a, meta(methodTypes = reversed).encodeText(), "同一模型两次编码逐字节相同")
        assertTrue(a.indexOf("A_FIRST") < a.indexOf("Z_LAST"), "按键升序写出")
    }

    // ------------------------------------------------------------------
    // 3) 负向：坏档必须当场拒绝（不是静默容错）
    // ------------------------------------------------------------------

    @Test
    fun `param-defaults 计数超过后续记录数当场拒绝`() {
        // CMP-82：str-constants 是唯一没调 checkCount 的段。这条用例钉住新段没有复制那个洞
        // —— 声明 100 条却只有 1 条，必须在预分配之前失败，而不是先吃掉内存再报 EOF。
        val bad = withTail(listOf("param-defaults 100", "param-default \"F\" ((int 1 0 0))"))
        val e = assertFailsWith<BootMetaFormatException>("计数谎报必须当场拒绝") { BootMetadata.parse(bad) }
        assertTrue(
            e.message!!.contains("param-defaults 计数"),
            "错误必须点名段与判据（可定位，I7）: ${e.message}",
        )
    }

    @Test
    fun `method-types 计数谎报当场拒绝`() {
        val bad = withTail(listOf("method-types 50", "method-type \"F\" I"))
        val e = assertFailsWith<BootMetaFormatException> { BootMetadata.parse(bad) }
        assertTrue(e.message!!.contains("method-types 计数"), "错误必须点名段: ${e.message}")
    }

    @Test
    fun `method-type 类型位只认 I S U`() {
        // 写出侧恒写 I/S/U 之一，其余形态（小写、多字符、数字、缺位）一律拒绝，不猜不折中。
        for (bad in listOf("X", "i", "INT", "0", "IU")) {
            val text = withTail(listOf("method-types 1", "method-type \"F\" $bad"))
            val e = assertFailsWith<BootMetaFormatException>("类型位 `$bad` 必须拒绝") {
                BootMetadata.parse(text)
            }
            assertTrue(
                e.message!!.contains("只认 I/S/U"),
                "错误必须点名合法值域: ${e.message}",
            )
        }
    }

    @Test
    fun `param-default 非字面量默认值当场拒绝`() {
        // 写出侧只可能产 `(int …)` / `(str …)`（LineParser.foldLiteralDefault 只折叠这两类，
        // 且 CallDefaultsCollector 只收 ParamDefault.expr != null）。手改侧车塞进别的形态就等于把
        // 「编译期明确不注入」的位变成「运行期求值」，语义直接跑偏且不报错。
        // 样本用 `(null 0 0 0)`：它正是「没有默认值」最容易被误写成的记号 —— 必须被拒，
        // 而不是被当成 null 位（null 位在格式里只有一个写法：裸 `-`）。
        val bad = withTail(listOf("param-defaults 1", "param-default \"F\" ((null 0 0 0))"))
        val e = assertFailsWith<BootMetaFormatException> { BootMetadata.parse(bad) }
        assertTrue(e.message!!.contains("只认字面量"), "错误必须说清值域: ${e.message}")
        assertTrue(e.message!!.contains("null"), "错误必须点名实际形态: ${e.message}")
    }

    @Test
    fun `param-default 默认值表必须是列表`() {
        val bad = withTail(listOf("param-defaults 1", "param-default \"F\" -"))
        val e = assertFailsWith<BootMetaFormatException> { BootMetadata.parse(bad) }
        assertTrue(e.message!!.contains("必须是列表"), "错误必须说清形态: ${e.message}")
    }

    @Test
    fun `重复函数键当场拒绝 —— 两段都算`() {
        val dupDefaults = withTail(
            listOf(
                "param-defaults 2",
                "param-default \"F\" ((int 1 0 0))",
                "param-default \"F\" ((int 2 0 0))",
            ),
        )
        val e1 = assertFailsWith<BootMetaFormatException> { BootMetadata.parse(dupDefaults) }
        assertTrue(e1.message!!.contains("函数键重复"), "默认值段重复键必须拒绝: ${e1.message}")

        val dupTypes = withTail(
            listOf(
                "method-types 2",
                "method-type \"F\" I",
                "method-type \"F\" S",
            ),
        )
        val e2 = assertFailsWith<BootMetaFormatException> { BootMetadata.parse(dupTypes) }
        assertTrue(e2.message!!.contains("函数键重复"), "类型段重复键必须拒绝: ${e2.message}")
    }

    @Test
    fun `条目行多余内容当场拒绝`() {
        val bad = withTail(listOf("param-defaults 1", "param-default \"F\" ((int 1 0 0)) 多余"))
        val e = assertFailsWith<BootMetaFormatException> { BootMetadata.parse(bad) }
        assertTrue(e.message!!.contains("多余内容"), "多余内容必须拒绝: ${e.message}")
    }

    // ------------------------------------------------------------------
    // 4) 消费口径：已知类型投影 + 未知点名
    // ------------------------------------------------------------------

    @Test
    fun `knownMethodTypes 滤掉未知类型`() {
        // 与 buildExprCallTable 的口径对齐：那个函数的 `methodTypes` 参数是
        // `Map<String, OperandType>`（只有 INT/STR），**键缺失 = 未知**。
        val m = meta(methodTypes = types())
        val known = m.knownMethodTypes()
        assertEquals(mapOf("ADD_MONEY" to OperandType.INT, "GET_NAME" to OperandType.STR), known)
        assertFalse(known.containsKey("MYSTERY"), "U 桶必须靠键缺失表达，不许映射成某个 OperandType")
        assertEquals(emptyMap(), meta().knownMethodTypes(), "空表 ⇒ 空投影")
    }

    @Test
    fun `unknownMethodKeys 能点名未知类型的方法`() {
        // 不变量 I7（可定位）：补丁编译期要能说出「哪个方法类型未知，所以不许降级」。
        val m = meta(methodTypes = types())
        assertEquals(setOf("MYSTERY"), m.unknownMethodKeys())
        assertEquals(emptySet(), meta().unknownMethodKeys())
    }

    // ------------------------------------------------------------------
    // 5) 装配陷阱：withHeader 逐字段转抄，漏一个字段就是静默丢段
    // ------------------------------------------------------------------

    @Test
    fun `withHeader 不丢两段`() {
        // withHeader 是**逐字段转抄**而不是 copy()（BootMetadata 是普通类）。erbcc 落盘前
        // 用它补 program-sha256 —— 漏一个字段，侧车就静默丢掉该段：文本仍然合法、装载仍然
        // 成功，只在补丁编译期才暴露成「没有默认值数据」。这条用例就是那个陷阱的钉子。
        val m = meta(paramDefaults = defaults(), methodTypes = types())
        val withSha = m.withHeader(engineTag = "x", programSha256 = "abc123")

        assertEquals(defaults(), withSha.paramDefaults, "paramDefaults 不许在 withHeader 里丢")
        assertEquals(types(), withSha.methodTypes, "methodTypes 不许在 withHeader 里丢")
        assertEquals("abc123", withSha.programSha256)
        assertEquals("x", withSha.engineTag)
        // 落盘 → 读回 也要带上（这是真实路径：erbcc 先 withHeader 再 encodeBytes）
        val back = BootMetadata.parse(withSha.encodeText())
        assertEquals(defaults(), back.paramDefaults)
        assertEquals(types(), back.methodTypes)
    }

    // ------------------------------------------------------------------
    // 6) 真产物
    //
    // 两个方向各由**一个显式开关**指定，互斥且都不许静默跳过：
    //   ERATW_ERBC_DIR=<含 program.meta 的目录>         → 新产物：两段必须在，且与函数表自洽
    //   ERATW_ERBC_LEGACY_DIR=<改动前产物目录>          → 旧产物：两段必须缺、装得上、读成空表
    // 不用「有段就跑、没段就跳」的单开关：那样一旦 erbcc 回归成不写段，闸会**静默空转**。
    // ------------------------------------------------------------------

    private fun realMetaPath(envName: String, allowFile: Boolean = false): Path? {
        val raw = System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val p = Path.of(raw)
        val dir = when {
            Files.isDirectory(p) -> p
            allowFile && Files.isRegularFile(p) -> p.parent
            else -> return null
        }
        val meta = dir.resolve("program.meta")
        return if (Files.isRegularFile(meta)) meta else null
    }

    @Test
    fun `真产物的两段与函数表自洽`() {
        val metaPath = realMetaPath("ERATW_ERBC_DIR", allowFile = true)
            ?: realMetaPath("ERATW_ERBC", allowFile = true)
        assumeTrue(metaPath != null, "未设置 ERATW_ERBC_DIR（含 program.meta 的目录），跳过真产物用例")
        val m = BootMetadata.read(metaPath!!)

        // ① 全量语料必然两段都有内容（缺省实参 948 量级 / 方法 4216 条）。
        // 空表就说明 CorpusCompile 没把数据喂进来 —— 这是「段存在但恒空」的静默失败面。
        assertTrue(m.paramDefaults.isNotEmpty(), "全量语料必有缺省实参默认值，段不该是空表")
        assertTrue(m.methodTypes.isNotEmpty(), "全量语料必有 #FUNCTION/#FUNCTIONS 方法")

        // ② 跨段自洽：键必须落在函数表内；STR ⟺ returnsStr（两态口径的盲区正是 U 桶）。
        // 这条同时是出口判据⑤的实证：`returnsStr` 是编译器算的，`method-types` 是侧车存的，
        // 两者一致 ⇒ 侧车没有把「类型未知」折成 INT（那正是缺口②）。
        val returnsStrByKey = HashMap<String, Boolean>(m.functions.size * 2)
        for (f in m.functions) returnsStrByKey[f.key.uppercase()] = f.returnsStr
        var ints = 0
        var strs = 0
        var unknowns = 0
        for ((key, t) in m.methodTypes) {
            val up = key.uppercase()
            assertTrue(returnsStrByKey.containsKey(up), "method-types 的键不在函数表内: $key")
            val returnsStr = returnsStrByKey.getValue(up)
            when (t) {
                MethodType.INT -> {
                    assertFalse(returnsStr, "INT 方法的 returnsStr 必须为 false: $key")
                    ints++
                }
                MethodType.STR -> {
                    assertTrue(returnsStr, "STR 方法的 returnsStr 必须为 true: $key")
                    strs++
                }
                MethodType.UNKNOWN -> {
                    // U 桶正是旧两态口径下与 INT 混同的那一批：returnsStr 也是 false。
                    // 侧车显式记 U，才让补丁编译能「点名报错」而不是猜一条通道。
                    assertFalse(returnsStr, "U 方法在旧两态口径下也是 false: $key")
                    unknowns++
                }
            }
        }

        // ③ param-defaults 的键也必须落在函数表内。读侧**不做**这条跨段闸（误杀会让整份侧车
        // 装不上，比缺一个默认值严重），但真产物必须干净 —— 这条用例就是「将来可以加闸」的证据。
        val dangling = m.paramDefaults.keys.filter { it.uppercase() !in returnsStrByKey }
        assertEquals(emptyList<String>(), dangling.take(5), "param-defaults 的键必须都落在函数表内（样例）")

        // ④ 文本闭环（49 MB 量级；`:core-vm:test` 的 worker 是 3g）。
        val text = m.encodeText()
        val back = BootMetadata.parse(text)
        assertEquals(m.paramDefaults, back.paramDefaults, "真产物默认值表逐条往返")
        assertEquals(m.methodTypes, back.methodTypes, "真产物类型表逐条往返")
        assertEquals(text, back.encodeText(), "真产物侧车文本必须闭环")

        println(
            "[r7-w0] 真产物：函数 ${m.functions.size}  param-defaults ${m.paramDefaults.size}  " +
                "method-types I=$ints S=$strs U=$unknowns",
        )
    }

    @Test
    fun `旧侧车真产物照样装载 —— 两段缺失不炸`() {
        // 判据②的真产物版：把 ERATW_ERBC_LEGACY_DIR 指向**改动之前** erbcc 写出的 program.meta
        // （49 MB、368,806 行、无这两段），整份装进来必须是空表而不是异常。
        // 合成用例（`旧侧车缺段照样装载`）只覆盖几十行文本；这里是同一判据在真档上的复核
        // —— 大档上「可选段判定」最容易出的是「读到最后一条记录才炸」，小样本看不出来。
        // 孤儿 env（2026-11-06 记）：ERATW_ERBC_LEGACY_DIR 在全仓 build 脚本里没有值源
        // （env.sh 不导、真产物档 wireRealArtifacts 只接 ERATW_ERBC_DIR/ERATW_ERBC）⇒ 不显式
        // 导出时本用例在任何口径都停在前置假设上。接线缺口进「需组长接线」，不在用例里硬造值。
        val legacyRaw = System.getenv("ERATW_ERBC_LEGACY_DIR")?.trim()?.takeIf { it.isNotEmpty() }
        val metaPath = realMetaPath("ERATW_ERBC_LEGACY_DIR", allowFile = true)
        assumeTrue(
            metaPath != null,
            if (legacyRaw == null)
                "ERATW_ERBC_LEGACY_DIR 未设置（本仓 build 脚本无该变量的值源：env.sh 不导、" +
                    "wireRealArtifacts 也不接，零接线）⇒ 旧侧车真产物用例判不了 —— 需组长接线" +
                    "（指向改动前 erbcc 写出的产物目录，program.meta 必须不含 param-defaults 与 method-types 两段）"
            else
                "ERATW_ERBC_LEGACY_DIR=$legacyRaw 下没有可读的 program.meta：跳过旧侧车真产物用例",
        )
        val m = BootMetadata.read(metaPath!!)
        assertEquals(emptyMap(), m.paramDefaults, "旧侧车缺段 ⇒ 空表")
        assertEquals(emptyMap(), m.methodTypes, "旧侧车缺段 ⇒ 空表")
        // 旧侧车该有的东西一条不少（证明「装得上」不是靠提前返回某条路径实现的）
        assertTrue(m.functions.isNotEmpty(), "旧侧车的函数表照样读得回来")
        assertTrue(m.labels.isNotEmpty(), "旧侧车的标签表照样读得回来")
        println("[r7-w0] 旧侧车：函数 ${m.functions.size}  两段均为空表（符合预期）")
    }
}
