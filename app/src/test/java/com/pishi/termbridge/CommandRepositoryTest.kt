package com.pishi.termbridge

import com.pishi.termbridge.data.CommandRepository
import com.pishi.termbridge.data.CommandRepositoryException
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandVariable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CommandRepositoryTest {
    private fun button(hostId: Long = 1L) = CommandButton(
        hostId = hostId,
        label = "Check disk",
        category = "Diagnostics",
        mode = CommandMode.EXECUTE_ONE,
        template = "df -h {{path}}",
        variables = listOf(CommandVariable("path", "/"))
    )

    @Test
    fun saveAndLoadRoundTripWithStepsAndVariables() = runTest {
        val dao = FakeCommandButtonDao()
        val repo = CommandRepository(dao)
        val sequence = CommandButton(
            hostId = 1L,
            label = "Seq",
            mode = CommandMode.SEQUENCE,
            steps = listOf("cd {{dir}}", "ls"),
            variables = listOf(CommandVariable("dir", "/tmp"))
        )
        val saved = repo.saveButton(sequence)
        val loaded = repo.getButton(saved.id)
        assertEquals(listOf("cd {{dir}}", "ls"), loaded?.steps)
        assertEquals(listOf(CommandVariable("dir", "/tmp")), loaded?.variables)
    }

    @Test
    fun buttonsAreScopedPerHostAndOrdered() = runTest {
        val dao = FakeCommandButtonDao()
        val repo = CommandRepository(dao)
        repo.saveButton(button(1L).copy(label = "First"))
        repo.saveButton(button(1L).copy(label = "Second"))
        repo.saveButton(button(2L).copy(label = "Other host"))
        val hostOne = repo.getButtons(1L).first()
        assertEquals(listOf("First", "Second"), hostOne.map { it.label })
        assertEquals(listOf("Other host"), repo.getButtons(2L).first().map { it.label })
    }

    @Test
    fun duplicateCopiesContentWithNewIdAndCopyLabel() = runTest {
        val dao = FakeCommandButtonDao()
        val repo = CommandRepository(dao)
        val saved = repo.saveButton(button())
        val copy = repo.duplicateButton(saved.id)
        assertTrue(copy.id != saved.id)
        assertEquals("Check disk (copy)", copy.label)
        assertEquals(saved.template, copy.template)
        assertEquals(saved.variables, repo.getButton(copy.id)?.variables)
    }

    @Test
    fun deleteRemovesButtonStepsAndVariables() = runTest {
        val dao = FakeCommandButtonDao()
        val repo = CommandRepository(dao)
        val saved = repo.saveButton(
            button().copy(
                mode = CommandMode.SEQUENCE,
                template = "",
                steps = listOf("ls", "pwd")
            )
        )
        repo.deleteButton(saved.id)
        assertNull(repo.getButton(saved.id))
    }

    @Test
    fun moveUpSwapsOrderWithNeighbour() = runTest {
        val dao = FakeCommandButtonDao()
        val repo = CommandRepository(dao)
        val first = repo.saveButton(button().copy(label = "First"))
        val second = repo.saveButton(button().copy(label = "Second"))
        assertTrue(repo.moveButton(second.id, up = true))
        assertEquals(
            listOf("Second", "First"),
            repo.getButtons(1L).first().map { it.label }
        )
        // Moving the first item up is a no-op, not an error, and
        // the order is unchanged.
        assertFalse(repo.moveButton(second.id, up = true))
        assertEquals(
            listOf("Second", "First"),
            repo.getButtons(1L).first().map { it.label }
        )
    }

    @Test
    fun saveRejectsSecretTemplate() = runTest {
        val dao = FakeCommandButtonDao()
        val repo = CommandRepository(dao)
        try {
            repo.saveButton(button().copy(template = "export token=abc123"))
            fail("Expected CommandRepositoryException")
        } catch (e: CommandRepositoryException) {
            assertTrue(e.message!!.contains("Refused"))
            assertFalse(e.message!!.contains("abc123"))
        }
    }

    @Test
    fun saveRejectsMovingButtonToAnotherHost() = runTest {
        val dao = FakeCommandButtonDao()
        val repo = CommandRepository(dao)
        val saved = repo.saveButton(button(1L))
        try {
            repo.saveButton(saved.copy(hostId = 2L))
            fail("Expected CommandRepositoryException")
        } catch (e: CommandRepositoryException) {
            assertTrue(e.message!!.contains("another host"))
        }
    }

    @Test
    fun deletingAHostDeletesItsButtons() = runTest {
        val hostDao = FakeHostDao()
        val commandDao = FakeCommandButtonDao()
        val hostRepo = HostRepository(
            hostDao,
            FakeTrustedHostKeyDao(),
            FakeSecretCrypto(),
            commandButtonDao = commandDao
        )
        val commandRepo = CommandRepository(commandDao)
        val host = hostRepo.saveHost(
            com.pishi.termbridge.domain.HostProfile(
                name = "Home",
                hostname = "192.168.1.10",
                port = 22,
                username = "admin"
            ),
            com.pishi.termbridge.domain.CredentialInput.Password("s3cr3t".toCharArray())
        )
        commandRepo.saveButton(button(host.id))
        assertEquals(1, commandRepo.getButtons(host.id).first().size)
        hostRepo.deleteHost(host.id)
        assertTrue(commandRepo.getButtons(host.id).first().isEmpty())
    }
}
