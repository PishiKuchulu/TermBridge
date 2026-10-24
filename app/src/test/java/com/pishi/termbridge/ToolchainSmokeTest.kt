package com.pishi.termbridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Toolchain smoke test ONLY. It proves the JVM unit-test task runs
 * and the test classpath resolves — it proves nothing about product
 * behaviour. Product tests arrive in prompts 04+ and are named for
 * the behaviour they cover. Deleting this file is fine once the
 * first real unit test lands; keeping it must never be reported as
 * product coverage.
 */
class ToolchainSmokeTest {
    @Test
    fun toolchainRuns() {
        assertEquals(4, 2 + 2)
    }
}
