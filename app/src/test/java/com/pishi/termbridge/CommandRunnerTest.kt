package com.pishi.termbridge

import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandRunner
import com.pishi.termbridge.domain.CommandTemplate
import com.pishi.termbridge.domain.PreparedCommand
import com.pishi.termbridge.domain.TerminalSender
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake terminal: insert and submit are recorded separately, so
 *  mode separation is observable, not assumed. */
private class FakeTerminalSender : TerminalSender {
    val inserted = mutableListOf<String>()
    val submitted = mutableListOf<String>()
    private var pendingText: String? = null

    override fun sendText(text: String) {
        pendingText = text
        inserted += text
    }

    override fun sendEnter() {
        // Enter submits whatever text was last inserted.
        pendingText?.let { submitted += it }
        pendingText = null
    }
}

class CommandRunnerTest {
    @Test
    fun insertSendsTextButNeverSubmits() {
        val sender = FakeTerminalSender()
        val runner = CommandRunner(sender)
        val button = CommandButton(
            hostId = 1L,
            label = "Insert me",
            mode = CommandMode.INSERT_ONLY,
            template = "ls -la"
        )
        runner.insert(CommandTemplate.prepare(button, emptyMap()))
        assertEquals(listOf("ls -la"), sender.inserted)
        assertTrue("Insert must never submit", sender.submitted.isEmpty())
    }

    @Test
    fun executeOneSubmitsExactlyOnce() {
        val sender = FakeTerminalSender()
        val runner = CommandRunner(sender)
        val button = CommandButton(
            hostId = 1L,
            label = "Run me",
            mode = CommandMode.EXECUTE_ONE,
            template = "df -h"
        )
        runner.executeOne(CommandTemplate.prepare(button, emptyMap()))
        assertEquals(listOf("df -h"), sender.submitted)
    }

    @Test
    fun sequenceSubmitsStepsInOrder() = runTest {
        val sender = FakeTerminalSender()
        val runner = CommandRunner(sender, stepDelayMs = 0L)
        val button = CommandButton(
            hostId = 1L,
            label = "Seq",
            mode = CommandMode.SEQUENCE,
            steps = listOf("cd /tmp", "ls", "pwd")
        )
        val progress = mutableListOf<Int>()
        runner.executeSequence(CommandTemplate.prepare(button, emptyMap())) {
            progress += it.stepIndex
        }
        assertEquals(listOf("cd /tmp", "ls", "pwd"), sender.submitted)
        assertEquals(listOf(1, 2, 3), progress)
    }

    @Test
    fun cancellingASequenceSendsNoFurtherSteps() = runTest {
        val sender = FakeTerminalSender()
        // A long delay between steps: the test cancels while the
        // runner waits, and asserts the third step never arrives.
        val runner = CommandRunner(sender, stepDelayMs = 60_000L)
        val button = CommandButton(
            hostId = 1L,
            label = "Seq",
            mode = CommandMode.SEQUENCE,
            steps = listOf("one", "two", "three")
        )
        val prepared: PreparedCommand = CommandTemplate.prepare(button, emptyMap())
        val job: Job = launch {
            runner.executeSequence(prepared) {}
        }
        // Let the first step submit, then cancel before step two's
        // delay elapses. runTest's virtual clock: advancing time
        // would complete the delay, so cancel first instead.
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertTrue(
            "Cancelled sequence must not submit all steps: ${sender.submitted}",
            sender.submitted.size < 3
        )
        assertEquals(listOf("one"), sender.submitted)
    }

    @Test
    fun runnerRefusesWrongMode() {
        val sender = FakeTerminalSender()
        val runner = CommandRunner(sender)
        val insertButton = CommandButton(
            hostId = 1L,
            label = "Insert",
            mode = CommandMode.INSERT_ONLY,
            template = "ls"
        )
        try {
            runner.executeOne(CommandTemplate.prepare(insertButton, emptyMap()))
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected: mode separation enforced by the runner.
        }
    }
}
