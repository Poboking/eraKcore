package com.eratw.port.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 审计二轮 L05（配置 / 设置）的判据。
 *
 * 只钉一条真实语义修复：设置页写 [ConfigKey.ReplaceContinuationBR] 时，原版那个文本框是
 * 全部字符串条目里**唯一**走 `TryParse` 的一条（`ConfigDialog.cs:1084`
 * `configData.GetConfigItem(ConfigCode.ReplaceContinuationBR).TryParse(textBox4.Text)`），
 * 其余字符串条目都是 `SetValue(textBox.Text)` 原样写
 * （`ConfigDialog.cs:1081`、`:1082`、`:1095`、`:1111`）。于是这一条的写入口径是
 * `ConfigItem.TryParse` 的字符串分支，而不是「原样存」：
 *   * 空串：`ConfigItem.cs:92-95` 在 `Length == 0` 时直接 `return false`，**值一个字都不动**；
 *   * 非空：`ConfigItem.cs:100` 先 `Trim()`，再在 `:160-164` 整串赋值 —— 所以「只有空白」
 *     的输入会落成空串（这是官方形状，本层不修）。
 *
 * 清空不得落成空串的理由在运行期：`EraStreamReader.cs:179` 把该串（去掉 `"` 后）插入
 * 续行拼接处，空串会让 ERB 行连接失去分隔串。
 */
class ConfigAudit2Test {

    private fun applied(result: SettingsSetResult): SettingsSetResult.Applied {
        assertTrue(result is SettingsSetResult.Applied, "期望写入成功，实际 $result")
        return result as SettingsSetResult.Applied
    }

    private fun rejected(result: SettingsSetResult): SettingsSetResult.Rejected {
        assertTrue(result is SettingsSetResult.Rejected, "期望拒绝写入，实际 $result")
        return result as SettingsSetResult.Rejected
    }

    private fun current(model: SettingsModel, key: ConfigKey): ConfigValue = model.store.itemOf(key).value

    @Test
    fun `ReplaceContinuationBR 清空不改值 只有纯空白才落成空串`() {
        val model = SettingsModel()
        // 缺省值就是 C# 字面量 "\" \""，即「引号 + 空格 + 引号」三个字符（ConfigData.cs:119）。
        assertEquals(ConfigValue.StringValue("\" \""), current(model, ConfigKey.ReplaceContinuationBR))

        // ConfigItem.cs:92-95：空串直接返回，值不动 —— 设置页清空文本框等于什么都没做。
        val empty = rejected(model.set("ReplaceContinuationBR", ""))
        assertEquals(SettingsRejectReason.EMPTY_TEXT, empty.reason, "空串属于「原版不改值」，本层显式拒绝")
        assertEquals("ReplaceContinuationBR", empty.id, "拒绝回执给规范 id")
        assertEquals(ConfigValue.StringValue("\" \""), current(model, ConfigKey.ReplaceContinuationBR))

        // ConfigItem.cs:100 + :160-164：非空先 Trim 再整串赋值。
        val padded = applied(model.set("ReplaceContinuationBR", "  <br>  "))
        assertEquals(ConfigValue.StringValue("<br>"), padded.value)
        assertEquals(ConfigValue.StringValue("<br>"), current(model, ConfigKey.ReplaceContinuationBR))

        // 纯空白：param 非空（过 :92-95 的空判），Trim 之后是空串 —— 官方就这么存，
        // 落盘成 `…:`，下一次读回是 EMPTY_VALUE（值回落到缺省）。
        val blank = applied(model.set("ReplaceContinuationBR", "   "))
        assertEquals(ConfigValue.StringValue(""), blank.value)
        assertEquals(ConfigValue.StringValue(""), current(model, ConfigKey.ReplaceContinuationBR))
    }

    @Test
    fun `ReplaceContinuationBR 的设置页写入与读配置走同一条 TryParse 口径`() {
        // 读侧（emuera.config / _Replace.csv 的行）同样是 TryParse：两端口径必须一致，
        // 否则「文件里能写出来的值」与「设置页能存的值」会分岔。
        val fromFile = ConfigItem(ConfigSpecTable.specOf(ConfigKey.ReplaceContinuationBR)!!)
        assertEquals(TryParseOutcome.NotParsedEmptyValue, fromFile.tryParse("", 1), "空串不改值（ConfigItem.cs:92-95）")
        assertEquals(ConfigValue.StringValue("\" \""), fromFile.value)
        assertEquals(TryParseOutcome.Applied, fromFile.tryParse("  <br>  ", 2))
        assertEquals(ConfigValue.StringValue("<br>"), fromFile.value, "Trim 后整串（ConfigItem.cs:100、:163）")

        val model = SettingsModel()
        applied(model.set("ReplaceContinuationBR", "  <br>  "))
        assertEquals(fromFile.value, current(model, ConfigKey.ReplaceContinuationBR), "设置页与读文件得到同一个值")
    }

    @Test
    fun `其余字符串条目仍按 SetValue 原样写入 不做 Trim`() {
        val model = SettingsModel()

        assertEquals(ConfigValue.StringValue("  .  "), applied(model.set("CBReplaceTags", "  .  ")).value)
        assertEquals(ConfigValue.StringValue("  .  "), current(model, ConfigKey.CBReplaceTags))

        assertEquals(ConfigValue.StringValue(" "), applied(model.set("TextEditor", " ")).value)

        // 空串在原版是合法值（语料 `TEXT EDITOR COMMAND LINE ARGUMENTS:` 就是空行，
        // ConfigData.cs:564-568 直写、不走 TryParse），本层同样接受。
        assertEquals(ConfigValue.StringValue(""), applied(model.set("EditorArgument", "")).value)
        assertEquals(ConfigValue.StringValue(""), current(model, ConfigKey.EditorArgument))
    }
}
