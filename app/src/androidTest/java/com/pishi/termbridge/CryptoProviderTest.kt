package com.pishi.termbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pishi.termbridge.security.SshCryptoProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review #2 F-01 probe. Needs no network and no fixture: it shows,
 * on a real Android image, whether the algorithms sshj looks up by
 * the provider NAME "BC" (key exchange X25519, Ed25519 host keys)
 * resolve after [SshCryptoProvider.install]. The result map is
 * printed on failure; only exception CLASS names appear in it.
 */
@RunWith(AndroidJUnit4::class)
class CryptoProviderTest {
    @Test
    fun bundledBouncyCastleServesWhatSshjNeeds() {
        SshCryptoProvider.install()
        val result = SshCryptoProvider.probe()
        val failures = result.filterValues { it != "ok" }
        println("CRYPTO_PROBE: $result")
        assertEquals("sshj crypto lookups failing via BC: $failures", emptyMap<String, String>(), failures)
    }

    @Test
    fun installIsIdempotent() {
        SshCryptoProvider.install()
        SshCryptoProvider.install()
        val bc = java.security.Security.getProviders().filter { it.name == "BC" }
        assertEquals(1, bc.size)
    }
}
