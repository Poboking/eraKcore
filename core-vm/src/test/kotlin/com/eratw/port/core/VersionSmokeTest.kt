package com.eratw.port.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionSmokeTest {
    @Test
    fun `version is well formed`() {
        assertTrue(Regex("""\d+\.\d+\.\d+.*""").matches(Version.VERSION))
    }

    @Test
    fun `project name matches`() {
        assertEquals("eratw-android", Version.NAME)
    }
}
