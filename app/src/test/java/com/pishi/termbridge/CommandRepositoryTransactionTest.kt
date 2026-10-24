package com.pishi.termbridge

import com.pishi.termbridge.data.CommandRepository
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandVariable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Prompt 15 regression tests for the prompt-08 sequential-save
 * gap: before prompt 15, saveButton deleted a button's steps and
 * variables and then inserted the new ones in separate calls, so
 * a crash mid-save could leave a button with partial children.
 * Saves now go through the single DAO replaceChildren call, and
 * reorders through the single swapSortOrders call (both Room
 * @Transaction in production).
 *
 * Honesty note: the production atomicity itself is Room's
 * @Transaction guarantee, which cannot execute on the JVM — Room
 * needs an Android runtime. What these tests prove is the
 * repository contract: the repository delegates to exactly one
 * atomic DAO call, and the fake DAO (which emulates the
 * transaction contract with scratch copies) leaves the old
 * children intact when that call fails. A fake that silently
 * re-implemented sequential calls would fail the second test.
 */
class CommandRepositoryTransactionTest {
    private fun sequenceButton() = CommandButton(
        hostId = 1L,
        label = "Seq",
        mode = CommandMode.SEQUENCE,
        steps = listOf("uptime", "df -h"),
        variables = listOf(CommandVariable("dir", "/tmp"))
    )

    @Test
    fun saveReplacesChildrenWhollyOldStepsAndVariablesGone() = runTest {
        val dao = FakeCommandButtonDao()
        val repo = CommandRepository(dao)
        val saved = repo.saveButton(sequenceButton())
        val updated = repo.saveButton(
            sequenceButton().copy(
                id = saved.id,
                steps = listOf("whoami"),
                variables = listOf(
                    CommandVariable("user", "root"),
                    CommandVariable("host", "example")
                )
            )
        )
        val loaded = repo.getButton(updated.id)
        assertEquals(listOf("whoami"), loaded?.steps)
        assertEquals(
            listOf(CommandVariable("user", "root"), CommandVariable("host", "example")),
            loaded?.variables
        )
    }

    @Test
    fun failedReplacementLeavesOldChildrenIntact() = runTest {
        val dao = FakeCommandButtonDao()
        val repo = CommandRepository(dao)
        val saved = repo.saveButton(sequenceButton())
        dao.failNextReplaceChildren = true
        try {
            repo.saveButton(
                sequenceButton().copy(id = saved.id, steps = listOf("whoami"))
            )
            fail("Expected the injected replacement failure to propagate.")
        } catch (_: IllegalStateException) {
            // Expected: the failure must surface, not be swallowed.
        }
        // Regression assertion: the old children survive untouched.
        val loaded = repo.getButton(saved.id)
        assertEquals(listOf("uptime", "df -h"), loaded?.steps)
        assertEquals(listOf(CommandVariable("dir", "/tmp")), loaded?.variables)
    }

    @Test
    fun moveSwapExchangesOrdersWithoutDuplication() = runTest {
        val dao = FakeCommandButtonDao()
        val repo = CommandRepository(dao)
        val first = repo.saveButton(sequenceButton().copy(label = "First"))
        val second = repo.saveButton(sequenceButton().copy(label = "Second"))
        assertEquals(true, repo.moveButton(second.id, up = true))
        val ordered = dao.getForHostOnce(1L)
        assertEquals(listOf(second.id, first.id), ordered.map { it.id })
        // No two buttons share a sort order after the swap.
        assertEquals(2, ordered.map { it.sortOrder }.toSet().size)
    }
}
