package com.eratw.port.core.err

import com.eratw.port.core.code.CallStackOverflow
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.code.VmException
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.ConfigMethods
import com.eratw.port.core.fn.FUNC_DEPRECATED_TEMPLATE
import com.eratw.port.core.fn.HtmlStrictParser
import com.eratw.port.core.fn.LangError
import com.eratw.port.core.fn.MOUSESKIP_WARNING_TEXT
import com.eratw.port.core.fn.funcDeprecatedWarning
import com.eratw.port.core.fn.mousePositionValue
import com.eratw.port.core.host.MouseState
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CONTRACT-M7 §2 w7-erraudit：错误体系与 C# 的 `CodeEE` / `Lang.Error` / zhs 译文表对拍。
 *
 * 本波**只新建本文件**，产品代码零改动；所有断言都钉在「现状 + 原文出处」上，
 * 发现口径分歧一律按现状钉桩并留 `TODO(verify)`，不改产品、不改语料、不改 reference。
 *
 * ## 对拍对象
 * - [CodeEE] 类本身对应 `reference/emuera-cs/MinorShift.Emuera.Runtime.Utils/CodeEE.cs:3-13`，
 *   其基类 `ApplicationException` 见同目录 `EmueraException.cs:5-19`（位置字段 `Position`）。
 * - 文案真源是 `Lang.cs` 的 `Lang.Error` 键（`MinorShift.Emuera.Runtime.Utils.EvilMask/Lang.cs`）
 *   与 zhs 译文表 `MinorShift.Emuera.Properties.lang.emuera-zhs.xml`；两条来源**经常不同形**
 *   （日文原文 vs 中文译文，且引号有全角/半角之别），故每个用例都同时列出两侧行号。
 * - 移植侧**没有**「错误码 → 文案」的表：文案是各 `throw CodeEE(...)` 调用点的字面量，
 *   因此对拍必须落到具体抛出点（`core/fn`、`core/vars`、`core/proc`）。`core/err` 全目录
 *   只有 [CodeEE] 一个类，这句话本身就是本路的审计结论之一（下面 [只有一份 CodeEE] 钉住它）。
 *
 * ## 语言口径（本路最重要的两条读数）
 * 1. **内核/契约层保持原版日文原文**：`ConfigMethods.kt:353-392` 明确裁决 4 条 `Lang.Error`
 *    文案不做本地化改写（`LangError` 的 KDoc「不做任何本地化改写」）。zhs 中文形只作对照。
 * 2. **使用者面取 zhs 译文**：`HtmlStrict`（`core/fn/HtmlStrict.kt:20-21`、`:595-692`）与
 *    `VariableStore`（`core/vars/VariableStore.kt:614-615`、`:665-666`）逐字取 zhs 表。
 *    于是同一套错误体系里两种语言并存 —— 这不是笔误，是既有裁决；本文件按现状钉住。
 *
 * ## 未覆盖（见交付报告）
 * - `ProcessRuntime.stampPosition`（`core/proc/ProcessRuntime.kt:832-835`）是 private，
 *   只能由 E2E / bootSmoke 间接覆盖；本文件只钉 [CodeEE] 的字段契约本身。
 * - `core/fn/Builtins.kt` 的实参个数/类型闸（`CheckArgumentType` 对应物）需要 `BuiltinContext`
 *   装置，属 `core/fn` 他路文件面，本文件不复制装置。
 */
class ErrorTextAuditTest {

    // ------------------------------------------------------------------
    // 一、CodeEE 类本身
    // ------------------------------------------------------------------

    @Test
    fun `CodeEE 的 message 保留原文案且位置只走字段`() {
        // 文案取自 LexicalAnalyzer.cs:439 抛的 Lang.Error.UnexpectedEqual（Lang.cs 见下条），
        // 这里只借它验证「message 原样」这一条契约（CodeEE.kt:9-10 的类 KDoc）。
        val e = CodeEE("予期しない代入演算子'='を発見しました(等価比較には'=='を使用してください)")
        assertEquals(
            "予期しない代入演算子'='を発見しました(等価比較には'=='を使用してください)",
            e.message,
            "message 逐字保留，不追加函数/行/pc（CodeEE.kt:9-10）",
        )
        assertEquals(null, e.funcName, "非 VM 场景 funcName 为 null（CodeEE.kt:12）")
        assertEquals(-1, e.line, "非 VM 场景 line = -1（CodeEE.kt:13）")
        assertEquals(-1, e.pc, "非 VM 场景 pc = -1（CodeEE.kt:14）")

        // 位置是结构化字段：带上位置后 message 一个字都不许多。
        val located = CodeEE("同じ文案", "TRAIN", 12, 34)
        assertEquals("同じ文案", located.message, "位置进字段、不进 message（CodeEE.kt:9-10）")
        assertEquals("TRAIN", located.funcName)
        assertEquals(12, located.line)
        assertEquals(34, located.pc)

        // 原版继承链：CodeEE : EmueraException : ApplicationException
        // （CodeEE.cs:3、EmueraException.cs:5）⇒ 裸 catch(Exception) 也兜得住。
        assertTrue(
            Exception::class.java.isAssignableFrom(CodeEE::class.java),
            "CodeEE.kt:25 继承 RuntimeException ⇒ 也是 Exception",
        )
        val asException: Exception = e
        assertSame(e, asException, "CodeEE 可当普通 Exception 传递/重抛，不丢失类型")
    }

    @Test
    fun `只有一份 CodeEE：code 包的抛出点被 err 包 catch 兜住`() {
        // Errors.kt:9 用 typealias 指向本包；若哪天复制出第二份同名类，这条会立刻红。
        val thrown: Throwable = try {
            throw CallStackOverflow(4, 1, 2, "MAIN")
        } catch (t: Throwable) {
            t
        }
        assertTrue(CodeEE::class.java.isInstance(thrown), "core.code 抛的异常必须就是本包的 CodeEE（Errors.kt:4-9）")
        assertTrue(VmException::class.java.isInstance(thrown), "VmException 是 CodeEE 子类（Errors.kt:17-22）")
        assertTrue(CodeEE::class.java.isAssignableFrom(VmException::class.java))
        assertEquals("com.eratw.port.core.err.CodeEE", CodeEE::class.qualifiedName)
    }

    @Test
    fun `VmException 家族位置透传且文案不对应任何 Lang Error 键`() {
        val step = StepLimitExceeded(10_000_000L, 5, 9, "TRAIN")
        assertEquals("执行步数超过上限(10000000)，疑似死循环", step.message, "Errors.kt:30 的成品串")
        assertEquals(5, step.line)
        assertEquals(9, step.pc)
        assertEquals("TRAIN", step.funcName)
        assertSame(step, catchCodeEE { throw step }, "保护闸仍可被 catch(CodeEE) 兜住（CodeEE.kt:16-18）")

        val stack = CallStackOverflow(8, 1, 2, "MAIN")
        assertEquals("调用栈超过上限(8)", stack.message, "Errors.kt:38 的成品串")
        assertSame(stack, catchCodeEE { throw stack })

        // 带调用链时 message 追链（BootFailures.classify 的 OTHER 分支只透传 message，
        // 链不进文案失败屏就看不到循环位置 —— 2026-10-21 真机撞闸时的盲区）。
        val chained = StepLimitExceeded(10_000_000L, 5, 9, "TRAIN", "SYSTEM_TITLE → TRAIN")
        assertEquals("执行步数超过上限(10000000)，疑似死循环 @ SYSTEM_TITLE → TRAIN", chained.message)
        assertEquals("SYSTEM_TITLE → TRAIN", chained.callChain)
        val stacked = CallStackOverflow(8, 1, 2, "MAIN", "A → MAIN")
        assertEquals("调用栈超过上限(8) @ A → MAIN", stacked.message)

        // 分歧钉桩：两条文案在 Lang.Error 里**没有对应键**（Lang.cs 全文无「步数」「调用栈」条目），
        // 原版死循环走的是对话框询问 MessageBox.InfiniteLoop / MessageBox.TooLongLoop
        // （zhs 表 lang.emuera-zhs.xml:970、:971-973），以及配置项
        // UI.ConfigDialog.Environment.InfiniteLoopAlertTime（Lang.cs:314 / zhs 表 :58）。
        // TODO(verify)：原版在这些对话里是「用户选择中断」而非引擎硬抛；移植侧改为必抛 CodeEE，
        // 属 Android 无对话框的必然改造，未被任何 PC 基准读数覆盖。
        assertNotEquals("可能发生了无限循环", step.message, "zhs:970 是消息框文案，不是异常文案")
    }

    // ------------------------------------------------------------------
    // 二、Lang.Error 键 → 成品串（内核层保持日文原文）
    // ------------------------------------------------------------------

    @Test
    fun `ArgIsEmptyString 逐字等于 Lang cs 3902`() {
        // Lang.cs:3902 = `{0}関数: 第{1}引数が空文字列です`；调用点 FunctionMethodCreator.cs:6046。
        // zhs 表 :929 是另一形 `{0}函数: 第{1}个参数是空字符串` —— 移植侧**不用**它。
        val msg = LangError.argIsEmptyString("GETCONFIG", 1)
        assertEquals("GETCONFIG関数: 第1引数が空文字列です", msg)
        assertEquals(-1, msg.indexOf('{'), "占位符必须已全部展开")
        assertEquals(-1, msg.indexOf('}'), "占位符必须已全部展开")
        assertNotEquals("GETCONFIG函数: 第1个参数是空字符串", msg, "zhs:929 的中文形未采用")
        // 引号/空格口径：`関数:` 后是**半角空格**，不是全角。
        assertTrue(msg.startsWith("GETCONFIG関数: "), "半角冒号 + 半角空格（Lang.cs:3902 原样）")
    }

    @Test
    fun `配置名两条文案逐字等于 Lang cs 1742 与 1746`() {
        // Lang.cs:1742 / 调用点 ConfigData.cs:355；zhs 表 :390 = `配置名"{0}"非法`。
        val bad = LangError.invalidConfigName("MoUse")
        assertEquals("文字列\"MoUse\"は適切なコンフィグ名ではありません", bad)
        assertTrue(bad.contains("\"MoUse\""), "键名两侧是**半角**直引号（Lang.cs:1742 原样）")
        assertFalse(bad.contains('“'), "不得变成全角左引号")
        assertFalse(bad.contains('”'), "不得变成全角右引号")
        assertNotEquals("配置名\"MoUse\"非法", bad, "zhs:390 的中文形未采用")

        // Lang.cs:1746 / 调用点 ConfigData.cs:419；zhs 表 :391 = `禁止获取"{0}"的值`。
        val denied = LangError.notAllowGetConfigValue("X")
        assertEquals("コンフィグ文字列\"X\"の値の取得は許可されていません", denied)
        assertTrue(denied.startsWith("コンフィグ文字列\"X\""), "前缀逐字（Lang.cs:1746）")
        assertNotEquals("禁止获取\"X\"的值", denied, "zhs:391 的中文形未采用")
    }

    @Test
    fun `InvalidType 的占位符实参顺序是调用名在前建议函数名在后`() {
        // Lang.cs:3914 = `{0}関数: 型が違います ({1}関数を使用してください)`；
        // 调用点 FunctionMethodCreator.cs:6063（GETCONFIG→GETCONFIGS）与 :6072（反向镜像）。
        // zhs 表 :932 = `{0}函数: 类型错误 (请使用{1}函数)` —— 括号前**没有空格**，与 jp 原文不同形。
        assertEquals(
            "GETCONFIG関数: 型が違います (GETCONFIGS関数を使用してください)",
            LangError.invalidType("GETCONFIG", "GETCONFIGS"),
        )
        assertEquals(
            "GETCONFIGS関数: 型が違います (GETCONFIG関数を使用してください)",
            LangError.invalidType("GETCONFIGS", "GETCONFIG"),
            "镜像方向只交换实参，模板不变（:6072）",
        )
        val msg = LangError.invalidType("A", "B")
        assertTrue(msg.contains(" (B関数"), "`(` 前有半角空格（Lang.cs:3914）")
        assertNotEquals("A函数: 类型错误 (请使用B函数)", msg, "zhs:932 的中文形未采用")
    }

    // ------------------------------------------------------------------
    // 三、未知 key 的回退（GETCONFIG 系列）
    // ------------------------------------------------------------------

    @Test
    fun `GETCONFIG 未知键不回退默认值而是抛 CodeEE 且键串原样回填`() {
        val store = ConfigStore() // 123 条全量表（ConfigSpec.kt:91-98）
        // 未知键：ConfigData.cs:352 的 Instance.GetItem(text) 返回 null → :353-356 抛 InvalidConfigName。
        val e = assertFailsWith<CodeEE> { ConfigMethods.valueInErb(store, "NoSuchKey") }
        assertEquals(
            "文字列\"NoSuchKey\"は適切なコンフィグ名ではありません",
            e.message,
            "键串**原样**回填：不 Trim、不改大小写（字符串来自调用方）",
        )

        // 空串同路：不静默返回默认值，也不吞成 null。
        val empty = assertFailsWith<CodeEE> { ConfigMethods.valueInErb(store, "") }
        assertEquals("文字列\"\"は適切なコンフィグ名ではありません", empty.message)

        // 回退口径的另一半：查的是**表**不是默认值 —— 合法键仍按条目取值。
        val ok = ConfigMethods.valueInErb(store, "ウィンドウ幅")
        assertEquals(760L, (ok as Value.IntV).v, "默认值来自 ConfigData.cs:56 的注册行（ConfigSpec.kt:113）")
    }

    @Test
    fun `GETCONFIG 键先大写化再查表所以小写英文标签也能命中`() {
        val store = ConfigStore()
        // ConfigData.cs:264 / :300 / :332 各自 ToUpperInvariant；
        // GAME 组认英文标签（ConfigData.cs:277），REPLACE/DEBUG 组不认（:298-316、:330-348）。
        val upper = ConfigMethods.valueInErb(store, "WINDOW WIDTH") as Value.IntV
        val lower = ConfigMethods.valueInErb(store, "window width") as Value.IntV
        assertEquals(760L, upper.v, "英文标签 WINDOW WIDTH 命中 WindowX（ConfigSpec.kt:113）")
        assertEquals(upper.v, lower.v, "小写形经 resolveAny 大写化后同一条目（ConfigStore.kt:57-60）")
    }

    // ------------------------------------------------------------------
    // 四、角色表两条文案（zhs 译文面，含官方 bug 复刻）
    // ------------------------------------------------------------------

    @Test
    fun `DELCHARA 单参越界文案逐字等于 zhs 314`() {
        val store = VariableStore() // 0 个角色
        // VariableEvaluator.cs:1245 的 format 实参是 charaNo.ToString() ⇒ 带越界编号。
        val e = assertFailsWith<CodeEE> { store.deleteCharacter(5) }
        assertEquals("试图删除不存在的注册角色(5)", e.message, "zhs 表 :314 原样；括号是**半角**")
        assertNotEquals("存在しない登録キャラクタ(5)を削除しようとしました", e.message, "Lang.cs:1438 的日文形未采用")
        val neg = assertFailsWith<CodeEE> { store.deleteCharacter(-2) }
        assertEquals("试图删除不存在的注册角色(-2)", neg.message, "负数同样原样回填编号")
    }

    @Test
    fun `DELCHARA 数组参越界复刻官方 bug 编号丢失`() {
        val store = VariableStore()
        // 官方 bug（ADR-005 原样复刻）：VariableEvaluator.cs:1259 的 format 实参写的是
        // charaNoList.ToString()（数组对象本身）⇒ .NET 上屏固定串 System.Int64[]。
        // 移植侧写死该等价串（VariableStore.kt:700-703）。
        val e = assertFailsWith<CodeEE> { store.deleteCharacters(longArrayOf(0L, 0L)) }
        assertEquals("试图删除不存在的注册角色(System.Int64[])", e.message)
        assertFalse(e.message!!.contains("(0)"), "编号必须丢失（对照单参版的带编号读数）")
    }

    @Test
    fun `DELCHARA 重复编号文案逐字等于 zhs 315`() {
        val store = VariableStore()
        store.characters.addBare() // 1 个角色，两个下标都合法 → 走到判重分支
        // VariableEvaluator.cs:1264 的 format 实参是 num.ToString() ⇒ 带编号（这条原版没写坏）。
        val e = assertFailsWith<CodeEE> { store.deleteCharacters(longArrayOf(0L, 0L)) }
        assertEquals("相同的注册角色编号(0)被重复指定", e.message, "zhs 表 :315 原样；括号是**半角**")
        assertNotEquals("同一の登録キャラクタ番号(0)が複数回指定されました", e.message, "Lang.cs:1442 的日文形未采用")
    }

    // ------------------------------------------------------------------
    // 五、HTML 文案（zhs 译文面，注意全角/半角引号与未展开的 {0}）
    // ------------------------------------------------------------------

    @Test
    fun `HTML 未知颜色名把 0 占位符原样上屏`() {
        // HtmlManager.cs:1718 直接抛 Lang.Error.InvalidColorName.Text，没有 string.Format
        // ⇒ 上屏就带 `{0}`（官方怪癖，ADR-005 复刻；HtmlStrict.kt:1462）。
        // zhs 表 :451 = `指定的颜色名"{0}"无效`（**半角**直引号）；jp 原文 Lang.cs:1986。
        val e = assertFailsWith<CodeEE> {
            HtmlStrictParser("<div color='notacolor' width='1' height='1'>", { it.length.toLong() }, { it }).length()
        }
        assertEquals("指定的颜色名\"{0}\"无效", e.message)
        assertTrue(e.message!!.contains("{0}"), "占位符必须保持未展开（不许「顺手修好」）")
        assertNotEquals("指定された色名\"{0}\"は無効な色名です", e.message, "Lang.cs:1986 的日文形未采用")
    }

    @Test
    fun `HTML div 属性值用全角引号照抄 zhs 817`() {
        // zhs 表 :817 = `无法解析<{0}>标签的{1}属性的值“{2}”` —— 引号是**全角** U+201C/U+201D；
        // jp 原文 Lang.cs:3450 却是半角 `"{2}"`。译文表的排版差异必须照抄（HtmlStrict.kt:1458-1459）。
        val e = assertFailsWith<CodeEE> {
            HtmlStrictParser("<div size='5' width='1' height='1'>", { it.length.toLong() }, { it }).length()
        }
        assertEquals("无法解析<div>标签的size属性的值\u201C5\u201D", e.message)
        assertTrue(e.message!!.contains('\u201C') && e.message!!.contains('\u201D'), "全角左/右引号")
        assertFalse(e.message!!.contains('"'), "不得混入半角直引号")
    }

    @Test
    fun `HTML 结构错误两条文案逐字等于 zhs 792 与 795`() {
        // zhs 表 :792 = `</p>之后没有文字`（jp Lang.cs:3350 = `</p>の後にテキストがあります`）
        val afterP = assertFailsWith<CodeEE> {
            HtmlStrictParser("<p align='left'>x</p>y", { it.length.toLong() }, { it }).length()
        }
        assertEquals("</p>之后没有文字", afterP.message)

        // zhs 表 :795 = `找不到标签的结束符'>'`（jp Lang.cs:3362 = `タグ終端'>'が見つかりません`）
        // 单引号包着**半角** `>`，两处都是原样。
        val noGt = assertFailsWith<CodeEE> {
            HtmlStrictParser("x<", { it.length.toLong() }, { it }).length()
        }
        assertEquals("找不到标签的结束符'>'", noGt.message)
    }

    // ------------------------------------------------------------------
    // 六、其它抛出点：BEGIN / MOUSE / 词法
    // ------------------------------------------------------------------

    @Test
    fun `BEGIN 未知关键字用 zhs 734 且关键字原样保留大小写`() {
        // zhs 表 :734 = `BEGIN的关键字"{0}"没有定义`（**半角**直引号）；
        // jp 原文 Lang.cs:3118 = `BEGINのキーワード"{0}"は未定義です`；
        // 抛出点 ProcessState.cs:164-166，实参按原样插入（不 Trim、不换大小写）。
        val e = assertFailsWith<CodeEE> { BeginType.fromKeyword("Shop") }
        assertEquals("BEGIN的关键字\"Shop\"没有定义", e.message)

        // 已知关键字照常命中。
        assertEquals(BeginType.SHOP, BeginType.fromKeyword("SHOP"))
        // 移植侧**超集**：`trim()` 前后空白（原版直接吃 ConstStr，会落 default 抛错，
        // FunctionIdentifier.cs:2932）。见 BeginType.kt:47-48 的登记。
        assertEquals(BeginType.SHOP, BeginType.fromKeyword("  SHOP  "))
        // TODO(verify)：这条超集是否会被语料触发 —— 目前语料 23 条 BEGIN 全部是裸大写关键字
        // （BeginType.kt:45-46 实测），故无读数差异；真出现带空白的写法时行为与原版不同。
    }

    @Test
    fun `MOUSESKIP 弃用警告用日文模板且第二个实参是硬编码字面量`() {
        // Lang.cs:3850 = `関数{0}()は推奨されません。代わりに関数{1}()を使用してください`
        // 调用点 FunctionMethodCreator.cs:3637：`string.Format(..., name, "MESSKIP")`。
        // zhs 表 :916 = `不推荐使用函数{0}()。请使用替代函数{1}()` —— 未采用（警告属内核层）。
        assertEquals(
            "関数{0}()は推奨されません。代わりに関数{1}()を使用してください",
            FUNC_DEPRECATED_TEMPLATE,
        )
        assertEquals(
            "関数MOUSESKIP()は推奨されません。代わりに関数MESSKIP()を使用してください",
            MOUSESKIP_WARNING_TEXT,
            "第二实参是硬编码的 MESSKIP，不是查表得到的名字（:3637）",
        )
        assertEquals(MOUSESKIP_WARNING_TEXT, funcDeprecatedWarning("MOUSESKIP"))
        assertNotEquals(
            "不推荐使用函数MOUSESKIP()。请使用替代函数MESSKIP()",
            MOUSESKIP_WARNING_TEXT,
            "zhs:916 的中文形未采用",
        )
    }

    @Test
    fun `MOUSE 非法名字守卫把原版 ExeEE 降级成 CodeEE`() {
        // FunctionMethodCreator.cs:7859 抛的是 ExeEE("異常な名前")（引擎内部错，不被 TRYC 捕获）；
        // 移植侧 core/err 只有 CodeEE，故用之（MouseMethods.kt:86-93 明写这条替代）。
        val e = assertFailsWith<CodeEE> { mousePositionValue("MOUSEZ", MouseState()) }
        assertEquals("異常な名前：MOUSEZ", e.message)
        // 分歧登记：① 原版文案没有名字后缀与全角冒号；② 异常类别从 ExeEE 变 CodeEE。
        // TODO(verify)：原版 ExeEE 的捕获口径（Process 顶层 catch 后是「强制结束」还是「继续」）
        // 未在 reference 里读到明确分支；若差别可观测，需要给 CodeEE 家族补一个 ExeEE 对等物。
        assertTrue(e.message!!.startsWith("異常な名前"), "前缀沿用原版")
    }

    @Test
    fun `词法错误 LexError 不是 CodeEE 与 C 侧抛 CodeEE 不同`() {
        // C# 词法层直接抛 CodeEE：LexicalAnalyzer.cs:273（LineBeginsIllegalCharacter）、
        // :439（UnexpectedEqual）、:507（CanNotRecognizedOp）……
        // 移植侧另立 LexError : RuntimeException（LexError.kt:4-13），**不**继承 CodeEE
        // ⇒ 运行期 catch (CodeEE) 兜不住词法错误（词法只在装载期发生，故目前不可观测）。
        val e = LexError("使用不正确的文字作为行的开始", lineNo = 0)
        assertTrue(
            RuntimeException::class.java.isAssignableFrom(LexError::class.java),
            "LexError : RuntimeException（LexError.kt:13）",
        )
        assertFalse(
            CodeEE::class.java.isAssignableFrom(LexError::class.java),
            "类型上不是 CodeEE（LexError.kt:13）",
        )
        assertEquals(0, e.lineNo, "LexError 行号 0 基（LexError.kt:10-11）")
        assertEquals(-1, e.column)

        // 对照：CodeEE 的 line 是 **1 基**（CodeEE.kt:13「汇编器与 erbcc 都用 1 起」）。
        // TODO(verify)：两套行号基准并存，宿主拼「函数:行」时必须知道手里是哪个异常；
        // 若将来把 LexError 收进 CodeEE 家族，0 基 → 1 基的换算点要一次改净。
        assertEquals(-1, CodeEE("x").line)
    }

    /**
     * 用真实的 `catch (CodeEE)` 取回异常 —— 比 `is` 断言更能证明「兜得住」这条契约
     * （原版 `CodeEE : ApplicationException`，见 CodeEE.cs:3 / EmueraException.cs:5）。
     * 没抛或抛了别的类型都返回 null，由调用方的 assertSame 报红。
     */
    private fun catchCodeEE(block: () -> Unit): CodeEE? =
        try {
            block()
            null
        } catch (e: CodeEE) {
            e
        }
}
