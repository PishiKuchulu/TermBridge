package com.pishi.termbridge

import com.pishi.termbridge.data.CommandRepositoryException
import com.pishi.termbridge.data.GLOBAL_HOST_ID
import com.pishi.termbridge.data.SnippetRepository
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandVariable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item T-02: the global snippets repository. */
class SnippetRepositoryTest {

    private fun repo() = SnippetRepository(FakeSnippetDao(), clock = { 42L })

    private fun button(label: String, template: String = "uptime") = CommandButton(
        hostId = GLOBAL_HOST_ID,
        label = label,
        mode = CommandMode.EXECUTE_ONE,
        template = template
    )

    @Test
    fun saveAndLoadRoundTripWithChildren() = runTest {
        val repository = repo()
        val saved = repository.saveSnippet(
            button("Uptime").copy(
                // Steps belong to SEQUENCE buttons; an EXECUTE_ONE
                // button carrying steps is rejected by the shared
                // validator (correctly — the modes stay separate).
                mode = CommandMode.SEQUENCE,
                template = "",
                steps = listOf("cd /var/log", "tail -5 syslog"),
                variables = listOf(CommandVariable("lines", "5", false))
            )
        )
        assertTrue(saved.id > 0)
        assertEquals(GLOBAL_HOST_ID, saved.hostId)
        val loaded = repository.getSnippet(saved.id)
        assertEquals(listOf("cd /var/log", "tail -5 syslog"), loaded?.steps)
        assertEquals("lines", loaded?.variables?.first()?.name)
        val listed = repository.getSnippets().first()
        assertEquals(listOf("Uptime"), listed.map { it.label })
    }

    @Test
    fun secretInTemplateIsRejected() = runTest {
        val repository = repo()
        // The shared secret scan must refuse key material in a
        // snippet exactly like in a per-host button.
        val keyBlock = "-----BEGIN " + "OPENSSH" + " PRIVATE KEY-----"
        var thrown = false
        try {
            repository.saveSnippet(button("Bad", template = "echo $keyBlock"))
        } catch (e: CommandRepositoryException) {
            thrown = true
        }
        assertTrue(thrown)
        assertTrue(repository.getSnippetsOnce().isEmpty())
    }

    @Test
    fun blankLabelIsRejected() = runTest {
        val repository = repo()
        var thrown = false
        try {
            repository.saveSnippet(button("  "))
        } catch (e: CommandRepositoryException) {
            thrown = true
        }
        assertTrue(thrown)
        assertTrue(repository.getSnippetsOnce().isEmpty())
    }

    @Test
    fun duplicateAndMoveAndDelete() = runTest {
        val repository = repo()
        val a = repository.saveSnippet(button("A"))
        val b = repository.saveSnippet(button("B"))
        val copy = repository.duplicateSnippet(a.id)
        assertEquals("A (copy)", copy.label)
        assertEquals(listOf("A", "B", "A (copy)"), repository.getSnippetsOnce().map { it.label })
        assertTrue(repository.moveSnippet(b.id, up = true))
        assertEquals(listOf("B", "A", "A (copy)"), repository.getSnippetsOnce().map { it.label })
        repository.deleteSnippet(copy.id)
        assertEquals(listOf("B", "A"), repository.getSnippetsOnce().map { it.label })
    }
}
