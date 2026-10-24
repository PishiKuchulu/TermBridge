package com.pishi.termbridge

import com.pishi.termbridge.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 03 navigation contract, testable on the JVM without a
 * device: every destination route is unique, non-blank, and the
 * start destination (Hosts) is in the registered set. This does not
 * prove the NavHost renders — that needs the instrumented test and
 * a device run — it proves the route table itself cannot silently
 * collide two destinations.
 */
class RoutesTest {
    @Test
    fun allRoutesAreUniqueAndNonBlank() {
        assertEquals(Routes.all.size, Routes.all.toSet().size)
        assertTrue(Routes.all.all { it.isNotBlank() })
    }

    @Test
    fun startDestinationIsRegistered() {
        assertTrue(Routes.all.contains(Routes.HOSTS))
    }

    @Test
    fun bottomBarDestinationsAreAllRegistered() {
        assertTrue(Routes.all.containsAll(Routes.bottomBar))
    }
}
