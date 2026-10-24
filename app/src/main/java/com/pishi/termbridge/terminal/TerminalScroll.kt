package com.pishi.termbridge.terminal

/**
 * Prompt 14 auto-scroll policy for the terminal view, kept pure
 * so it is JVM-testable without Compose.
 *
 * The prompt-06 behaviour scrolled to the bottom on every new
 * output, yanking the view even while the user was reading
 * history. The prompt-14 behaviour is "stick to bottom only
 * when already at bottom":
 *
 * - While the last visible line is the newest line, the view is
 *   pinned: new output auto-scrolls, and the seen count tracks
 *   the total.
 * - Once the user scrolls up, the view is unpinned: new output
 *   does NOT move the view, and [newLinesSince] counts how many
 *   lines arrived since the user left the bottom, so the UI can
 *   offer a "Jump to latest" action with an honest count.
 * - Scrolling back to the bottom re-pins the view.
 *
 * The view itself owns the pinned flag (from LazyListState
 * layout info); these functions only decide, from numbers, what
 * the flag means. Nothing here touches the shell or the parser.
 */

/**
 * Is the view at the bottom, given the last visible item index
 * (null when the list lays out nothing) and the total item count?
 * An empty list counts as "at bottom" (there is nothing to read
 * above); an unknown position counts as NOT at bottom, so the
 * view is never yanked on a guess.
 */
fun isAtBottom(lastVisibleIndex: Int?, totalItems: Int): Boolean {
    if (totalItems <= 0) return true
    if (lastVisibleIndex == null) return false
    return lastVisibleIndex >= totalItems - 1
}

/** Should new output auto-scroll? Only when pinned and non-empty. */
fun shouldAutoScroll(pinnedToBottom: Boolean, totalItems: Int): Boolean =
    pinnedToBottom && totalItems > 0

/**
 * Lines that arrived since the user last saw the bottom.
 * Never negative: a resize or clear that shrinks the list
 * reports zero new lines, not a negative count.
 */
fun newLinesSince(seenCount: Int, totalItems: Int): Int =
    (totalItems - seenCount).coerceAtLeast(0)
