package com.pishi.termbridge

import com.pishi.termbridge.domain.ImeRoute
import com.pishi.termbridge.domain.ImeRouting
import org.junit.Assert.assertEquals
import org.junit.Test

/** Item T-13: IME composition routing. */
class ImeRoutingTest {

    @Test
    fun compositionDraftIsHeld() {
        assertEquals(
            ImeRoute.HoldDraft,
            ImeRouting.route("", "n", wasComposing = false, isComposing = true)
        )
        assertEquals(
            ImeRoute.HoldDraft,
            ImeRouting.route("ni", "ni h", wasComposing = true, isComposing = true)
        )
    }

    @Test
    fun compositionCommitIsTypingNotPaste() {
        // A whole word arriving via composition commit must NOT
        // be routed to the paste-confirmation gate.
        assertEquals(
            ImeRoute.TypeText("hello"),
            ImeRouting.route("hel", "hello", wasComposing = true, isComposing = false)
        )
        // A composition deleted to nothing forwards nothing.
        assertEquals(
            ImeRoute.Nothing,
            ImeRouting.route("hel", "", wasComposing = true, isComposing = false)
        )
    }

    @Test
    fun plainTypingAndPasteGateUnchanged() {
        assertEquals(
            ImeRoute.TypeChar('a'),
            ImeRouting.route("", "a", wasComposing = false, isComposing = false)
        )
        assertEquals(
            ImeRoute.PasteCandidate("pasted text"),
            ImeRouting.route("", "pasted text", wasComposing = false, isComposing = false)
        )
        assertEquals(
            ImeRoute.PasteCandidate("\n"),
            ImeRouting.route("", "\n", wasComposing = false, isComposing = false)
        )
    }

    @Test
    fun shrinkageIsBackspaces() {
        assertEquals(
            ImeRoute.Backspaces(2),
            ImeRouting.route("abc", "a", wasComposing = false, isComposing = false)
        )
        assertEquals(
            ImeRoute.Nothing,
            ImeRouting.route("", "", wasComposing = false, isComposing = false)
        )
    }
}
