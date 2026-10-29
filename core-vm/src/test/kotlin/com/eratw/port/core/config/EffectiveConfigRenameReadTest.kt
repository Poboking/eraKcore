package com.eratw.port.core.config

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CMP-81：`USE _RENAME.CSV FILE` 的读取入口。
 *
 * 运行期读开关、装载侧照开关行事（原版 `Config.cs:310` 读、`Process.cs:261-274` 依开关装载）。
 * 移植侧本卡只补 [EffectiveConfig.useRenameFile] 读取方法；接线（`CorpusCompile` 无条件
 * 加载、`LogicalLineReader.renameDic` 无运行期调用点）归组长。
 *
 * 键与默认值：`ConfigKey.UseRenameFile`（`ConfigKey.kt:30` = `ConfigCode.cs:6`），
 * 缺省 false（`ConfigSpec.kt:103` = `ConfigData.cs:46`）。
 */
class EffectiveConfigRenameReadTest {

    private fun b(v: Boolean) = ConfigValue.Bool(v)

    @Test
    fun `缺省关闭 开关置位后读到 true`() {
        val store = ConfigStore()
        assertEquals(false, EffectiveConfig.of(store).useRenameFile, "缺省 false（ConfigData.cs:46）")

        store.itemOf(ConfigKey.UseRenameFile).assign(b(true))
        assertEquals(true, EffectiveConfig.of(store).useRenameFile, "Config.cs:310 直接吃条目值")

        store.itemOf(ConfigKey.UseRenameFile).assign(b(false))
        assertEquals(false, EffectiveConfig.of(store).useRenameFile)
    }

    @Test
    fun `忽略大小写开关与改名开关互不串线`() {
        val store = ConfigStore()
        store.itemOf(ConfigKey.UseRenameFile).assign(b(true))
        // IgnoreCase 缺省 true（ConfigData.cs:45），改的是另一个键，不得互相影响。
        assertEquals(true, EffectiveConfig.of(store).ignoreCase)
        store.itemOf(ConfigKey.IgnoreCase).assign(b(false))
        val eff = EffectiveConfig.of(store)
        assertEquals(false, eff.ignoreCase)
        assertEquals(true, eff.useRenameFile, "两个开关各读各的键")
    }
}
