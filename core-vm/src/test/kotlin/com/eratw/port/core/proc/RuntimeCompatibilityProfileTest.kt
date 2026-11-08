package com.eratw.port.core.proc

import com.eratw.port.core.code.ResolverPolicy
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M1-0：`RuntimeCompatibilityProfile` 的形状与默认值闸（era-arch-v61u.md §9）。
 *
 * 这一批**只落类型**，所以断言只能是「形状对不对、缺省值是不是现行行为」——接线后的
 * 行为对拍归 M1/M2-2。闸的三条主张：
 *
 * 1. **profile 只装枚举**：出现 Boolean/String/List 字段 = 长出自由旋钮，正是反模式；
 * 2. **六个 facet + 版本声明就是全部字段**：加第七个字段必须显式改这里（评审点）；
 * 3. **版本不泄进策略**：4.980/4.981 两档只许差 `corpusVersion`，Modern 只许翻转
 *    `functionResolution` —— 任何「读版本号在消费点分叉」都会先在这里红。
 */
class RuntimeCompatibilityProfileTest {

    /** 全部 facet 名（写法与 §9 表一致；加字段时这里和反射读数一起改）。 */
    private val expectedFields = listOf(
        "corpusVersion",
        "functionResolution",
        "identifierFolding",
        "eventRegistration",
        "lazyLoading",
        "reload",
        "saveLoad",
    )

    @Test
    fun `profile 的字段恰好是六个 facet 加版本声明且全部是枚举`() {
        // 实例字段 = data class 的属性；Companion 的工厂常量是 static 字段，不算 facet。
        val fields = RuntimeCompatibilityProfile::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        assertEquals(
            expectedFields.sorted(),
            fields.map { it.name }.sorted(),
            "字段集合 = §9 的六个 facet + corpusVersion（多一个字段就是多一个旋钮）",
        )
        for (f in fields) {
            assertTrue(
                f.type.isEnum,
                "${f.name} 的类型是 ${f.type.simpleName}：profile 只许装枚举 facet，布尔/字符串/列表会变成自由旋钮",
            )
        }
    }

    @Test
    fun `单值 facet 现状各只有一个实现值`() {
        // 单值不是「没设计」，是**能力标识**：原版/本移植只声明了这一条事实。
        // 加值会让消费方的 exhaustive when 编译期变红 —— 这是有意的，不是要顺手放宽这里。
        assertEquals(1, IdentifierFolding.values().size, "标识符归一只声明了 OrdinalIgnoreCase（Config.cs:33）")
        assertEquals(1, EventRegistrationPolicy.values().size, "事件注册只有四桶（LabelDictionary.cs:90-105）")
        assertEquals(1, LazyLoadingPolicy.values().size, "v1 能力标识 = EAGER_ONLY（§9.1）；懒激活另立值")
        assertEquals(1, ReloadPolicy.values().size, "重载只有重注册（Process.cs:350-358）")
        assertEquals(1, SaveLoadPolicy.values().size, "LOADDATA 只有先复位后解析（VariableEvaluator.cs:2626-2627）")
    }

    @Test
    fun `LEGACY 档逐项等于现行行为`() {
        for (p in listOf(RuntimeCompatibilityProfile.LEGACY_ERATW_4980, RuntimeCompatibilityProfile.LEGACY_ERATW_4981)) {
            assertEquals(ResolverPolicy.LEGACY_LAST_WINS, p.functionResolution, "Legacy 档：$p")
            assertEquals(IdentifierFolding.ORDINAL_IGNORE_CASE, p.identifierFolding, "Legacy 档：$p")
            assertEquals(EventRegistrationPolicy.FOUR_BUCKET, p.eventRegistration, "Legacy 档：$p")
            assertEquals(LazyLoadingPolicy.EAGER_ONLY, p.lazyLoading, "Legacy 档：$p")
            assertEquals(ReloadPolicy.RE_REGISTER, p.reload, "Legacy 档：$p")
            assertEquals(SaveLoadPolicy.RESET_THEN_PARSE, p.saveLoad, "Legacy 档：$p")
        }
    }

    @Test
    fun `语料版本不改变解析策略也不改动其余 facet`() {
        assertNotEquals(
            RuntimeCompatibilityProfile.LEGACY_ERATW_4980,
            RuntimeCompatibilityProfile.LEGACY_ERATW_4981,
            "版本声明必须进相等性（否则缓存指纹/兼容性报告看不出目标语料）",
        )
        assertEquals(
            RuntimeCompatibilityProfile.LEGACY_ERATW_4980.copy(corpusVersion = CorpusVersion.V4_981),
            RuntimeCompatibilityProfile.LEGACY_ERATW_4981,
            "两个 Legacy 档只许差 corpusVersion；要差别的必须先落成具名 facet 并经 ABI 评审",
        )
        for (v in CorpusVersion.values()) {
            val modern = RuntimeCompatibilityProfile.modern(v)
            assertEquals(ResolverPolicy.MODERN, modern.functionResolution, "Modern($v) 的解析策略")
            assertEquals(
                RuntimeCompatibilityProfile(corpusVersion = v, functionResolution = ResolverPolicy.LEGACY_LAST_WINS),
                modern.copy(functionResolution = ResolverPolicy.LEGACY_LAST_WINS),
                "Modern 只许翻转 functionResolution，其余 facet 与 Legacy 同形（$v）",
            )
        }
    }
}
