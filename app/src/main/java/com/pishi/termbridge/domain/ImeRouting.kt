package com.pishi.termbridge.domain

/**
 * Item T-13: how one terminal input-field change is routed.
 * Pure, so the composition rules are unit-tested instead of
 * living only inside a composable.
 */
sealed interface ImeRoute {
    /** The IME is composing: hold the draft, forward nothing. */
    data object HoldDraft : ImeRoute

    /** A committed IME composition: forward as typing (never
     *  through the paste gate — a composition commit is a
     *  keystroke sequence, not a clipboard paste). */
    data class TypeText(val text: String) : ImeRoute

    /** A plain single keystroke (no composition involved). */
    data class TypeChar(val char: Char) : ImeRoute

    /** A non-composition multi-character insert (or one
     *  containing a newline): the caller runs it past the
     *  paste-confirmation gate (security fix 16). */
    data class PasteCandidate(val text: String) : ImeRoute

    /** The field shrank without composition: shell backspaces. */
    data class Backspaces(val count: Int) : ImeRoute

    /** Nothing to forward (e.g. a composition deleted away). */
    data object Nothing : ImeRoute
}

object ImeRouting {
    /**
     * @param oldText the field text before this change (a held
     *  composition draft, or "" in the steady state)
     * @param newText the field text after this change
     * @param wasComposing the previous state held a composition
     * @param isComposing the new state has an active composition
     */
    fun route(
        oldText: String,
        newText: String,
        wasComposing: Boolean,
        isComposing: Boolean
    ): ImeRoute {
        if (isComposing) return ImeRoute.HoldDraft
        if (wasComposing) {
            return if (newText.isEmpty()) {
                ImeRoute.Nothing
            } else {
                ImeRoute.TypeText(newText)
            }
        }
        return when {
            newText.length > oldText.length -> {
                val added = newText.substring(oldText.length)
                if (added.length > 1 || added.any { it == '\n' || it == '\r' }) {
                    ImeRoute.PasteCandidate(added)
                } else {
                    ImeRoute.TypeChar(added[0])
                }
            }
            newText.length < oldText.length ->
                ImeRoute.Backspaces(oldText.length - newText.length)
            else -> ImeRoute.Nothing
        }
    }
}
