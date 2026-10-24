package com.pishi.termbridge.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Prompt 08 execution boundary. The runner knows the three modes
 * and nothing about Compose, Room, or sshj, so mode separation is
 * unit-testable with a fake sender.
 *
 * - insert: sendText only. No Enter is ever sent. This is the
 *   whole guarantee for insert-only: the code path for insert
 *   contains no sendEnter call at all.
 * - execute: sendText followed by sendEnter, exactly once.
 * - sequence: steps submitted in order, one sendText + sendEnter
 *   per step, with [stepDelayMs] between steps. Progress is
 *   reported after each step is sent.
 *
 * Sequence stop/error semantics (documented, not hidden):
 * an interactive PTY shell gives the app no per-step exit status,
 * so a sequence does NOT auto-stop on first error — the shell's
 * own output is the only error surface, and the user reads it in
 * the terminal. Cancellation (Stop) is checked between steps:
 * after Stop, no further step is ever sent. A step that is
 * already submitted cannot be unsent, and a long-running step
 * keeps running on the server until the user interrupts it
 * (Ctrl-C in the terminal) — Stop only stops the app from
 * submitting further steps.
 */
interface TerminalSender {
    fun sendText(text: String)
    fun sendEnter()
}

data class SequenceProgress(val stepIndex: Int, val totalSteps: Int)

class CommandRunner(
    private val sender: TerminalSender,
    private val stepDelayMs: Long = 1000L
) {
    /** Insert-only: text into the terminal input, never submitted. */
    fun insert(prepared: PreparedCommand) {
        require(prepared.button.mode == CommandMode.INSERT_ONLY) {
            "insert() is only for insert-only buttons."
        }
        sender.sendText(prepared.rendered)
    }

    /** Execute-one: one submission, exactly once. */
    fun executeOne(prepared: PreparedCommand) {
        require(prepared.button.mode == CommandMode.EXECUTE_ONE) {
            "executeOne() is only for execute-one buttons."
        }
        sender.sendText(prepared.rendered)
        sender.sendEnter()
    }

    /**
     * Sequence: submit steps in order. Suspends between steps so
     * the caller can cancel; [onProgress] is called after each
     * step is submitted (including the last).
     */
    suspend fun executeSequence(
        prepared: PreparedCommand,
        onProgress: (SequenceProgress) -> Unit = {}
    ) {
        require(prepared.button.mode == CommandMode.SEQUENCE) {
            "executeSequence() is only for sequence buttons."
        }
        val steps = prepared.renderedSteps
        for ((index, step) in steps.withIndex()) {
            if (!currentCoroutineContext().isActive) return
            sender.sendText(step)
            sender.sendEnter()
            onProgress(SequenceProgress(index + 1, steps.size))
            if (index < steps.size - 1) {
                delay(stepDelayMs)
            }
        }
    }
}
