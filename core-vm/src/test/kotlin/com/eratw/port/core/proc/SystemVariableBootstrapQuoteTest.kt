package com.eratw.port.core.proc

import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SystemVariableBootstrap.stripComment` 的引号/转义口径 **逐字对齐**原版词法器
 * `ReadString`（`reference/emuera-cs/MinorShift.Emuera.Runtime.Script.Parser/LexicalAnalyzer.cs:330-400`）：
 *
 * - 单引号 `'...'` 与双引号 `"..."` 各自为定界符（`StrEndWith.SingleQuotation` / `DoubleQuotation`）；
 * - 字符串内反斜杠 `\` 转义其后一个字符（`:369-394`）；
 * - 原版**没有**双写引号转义语义 ⇒ 连续两个引号不构成转义。
 *
 * 另一条：全局 `REF` 声明对齐 `ErhLoader.cs:262-265`（抛 `NotImplCodeEE` ⇒ Warn level 2 且不注册）。
 */
class SystemVariableBootstrapQuoteTest {

    private fun decl(raw: String, file: String = "T.ERH", line: Int = 1) =
        SystemVariableBootstrap.RawDeclaration(raw, file, line)

    private fun newStore() = VariableStore(VariableRegistry(), VarConfig(systemSaveInBinary = true))

    // ------------------------------------------------------------------
    // stripComment：引号 / 转义
    // ------------------------------------------------------------------

    /** 单引号字符串内的 `;` 保留（原版 `StrEndWith.SingleQuotation`）。 */
    @Test
    fun `单引号字符串内的分号不是注释`() {
        val raw = "#DIMS CONST SQ = 'a;b' ; 尾注"
        assertEquals("#DIMS CONST SQ = 'a;b' ", SystemVariableBootstrap.stripComment(raw))
    }

    /** 单引号字符串后的注释仍被剥离。 */
    @Test
    fun `单引号字符串后的注释被剥离`() {
        val raw = "#DIMS CONST SQ = 'x' ; c"
        assertEquals("#DIMS CONST SQ = 'x' ", SystemVariableBootstrap.stripComment(raw))
    }

    /** 双引号字符串内的 `\"` 是转义引号，不结束字符串，故其后的 `;` 仍属字符串内。 */
    @Test
    fun `双引号内反斜杠转义引号不结束字符串`() {
        val raw = "#DIMS CONST X = \"a\\\"b\" ; c"
        assertEquals("#DIMS CONST X = \"a\\\"b\" ", SystemVariableBootstrap.stripComment(raw))
    }

    /** 未转义的 `"` 结束字符串，之后的 `;` 才被当注释。 */
    @Test
    fun `未转义引号结束字符串后注释被剥离`() {
        val raw = "#DIMS CONST X = \"ab\" ; c"
        assertEquals("#DIMS CONST X = \"ab\" ", SystemVariableBootstrap.stripComment(raw))
    }

    /** 原版无双写引号转义：`""` 各自为定界符，其后的 `;` 是注释。 */
    @Test
    fun `连续双引号不构成转义且注释照剥`() {
        val raw = "#DIMS CONST X = \"\" ; c"
        assertEquals("#DIMS CONST X = \"\" ", SystemVariableBootstrap.stripComment(raw))
    }

    /** 字符串外的 `\` 不是转义符（原版只在 ReadString 内转义）⇒ 其后的 `;` 仍是注释。 */
    @Test
    fun `字符串外反斜杠不抑制注释`() {
        val raw = "#DIM A\\ ; c"
        assertEquals("#DIM A\\ ", SystemVariableBootstrap.stripComment(raw))
    }

    // ------------------------------------------------------------------
    // 全局 REF 声明拒绝
    // ------------------------------------------------------------------

    /** 原版 `ErhLoader.cs:262-265`：全局 REF 声明 Warn level 2 且不注册。 */
    @Test
    fun `全局 REF 声明记失败且不注册`() {
        val store = newStore()
        val result = SystemVariableBootstrap.install(store, listOf(decl("#DIM REF GREFV")))

        assertEquals(0, result.declared, "REF 全局声明不得注册")
        assertEquals(1, result.failures.size)
        assertTrue(
            result.failures.first().message.contains("不能在现版本使用"),
            "文案取原版 CanNotUseFuncCurrentVer：${result.failures.first().message}",
        )
        assertNull(store.userVar("GREFV"), "REF 变量不得进变量池")
    }

    /** REF 拒绝不阻断后续声明（与 install 的「失败不中断」口径一致）。 */
    @Test
    fun `全局 REF 拒绝不阻断后续声明`() {
        val store = newStore()
        val result = SystemVariableBootstrap.install(
            store,
            listOf(
                decl("#DIM REF GREFV"),
                decl("#DIM CONST OK_CONST = 7"),
            ),
        )

        assertEquals(1, result.declared)
        assertEquals(1, result.failures.size)
        assertEquals(7L, store.readInt("OK_CONST", longArrayOf(0L)))
        assertEquals(7L, result.constants["OK_CONST"])
    }
}