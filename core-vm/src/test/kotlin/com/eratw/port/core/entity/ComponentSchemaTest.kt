package com.eratw.port.core.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ComponentSchema 契约钉（v6.4-c，GPT 三审 P0-3A）：字段级 EntityRef 声明只许整型、
 * 字段名归一去重、重映射遍历面只含显式声明字段。
 */
class ComponentSchemaTest {

    @Test
    fun entityRefRequiresIntField() {
        assertFailsWith<IllegalArgumentException> {
            ComponentFieldSpec("bestFriendId", ComponentFieldType.STR, isEntityRef = true)
        }
        assertFailsWith<IllegalArgumentException> { ComponentFieldSpec(" ") }
    }

    @Test
    fun duplicateFieldNamesRejectedIgnoreCase() {
        assertFailsWith<IllegalArgumentException> {
            ComponentSchema(
                packageId = "org.era.x",
                name = "Education",
                fields = listOf(ComponentFieldSpec("MentorId"), ComponentFieldSpec("mentorid")),
            )
        }
        assertFailsWith<IllegalArgumentException> { ComponentSchema(packageId = "", name = "C") }
        assertFailsWith<IllegalArgumentException> { ComponentSchema(packageId = "org.era.x", name = "") }
    }

    @Test
    fun entityRefFieldKeysAreNormalizedSubset() {
        val schema = ComponentSchema(
            packageId = "org.era.school",
            name = "Education",
            fields = listOf(
                ComponentFieldSpec("mentorId", ComponentFieldType.INT, isEntityRef = true),
                ComponentFieldSpec("bestFriendId", ComponentFieldType.INT, isEntityRef = true),
                ComponentFieldSpec("nickname", ComponentFieldType.STR),
                ComponentFieldSpec("score"),
            ),
        )
        assertEquals(setOf("MENTORID", "BESTFRIENDID"), schema.normalizedEntityRefFieldKeys())
        // 未声明 EntityRef 的整型字段不重映射（诚实边界：引擎不猜值）。
        assertTrue(schema.normalizedEntityRefFieldKeys().none { it == "SCORE" })
        assertTrue(ComponentSchema("org.era.x", "Plain").normalizedEntityRefFieldKeys().isEmpty())
    }
}
