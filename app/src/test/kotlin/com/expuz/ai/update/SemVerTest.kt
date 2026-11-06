package com.expuz.ai.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    @Test
    fun parsesTagsAndDebugSuffixes() {
        assertEquals(SemVer(1, 0, 0), SemVer.parse("v1.0.0"))
        assertEquals(SemVer(1, 0, 1), SemVer.parse("1.0.1"))
        assertEquals(SemVer(1, 2, 0), SemVer.parse("1.2.0-debug"))
    }

    @Test
    fun comparesSemantically() {
        assertTrue(SemVer.parse("1.10.0") > SemVer.parse("1.9.9"))
        assertTrue(SemVer.parse("2.0.0") > SemVer.parse("1.99.99"))
    }
}
