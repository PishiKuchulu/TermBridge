package com.pishi.termbridge.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Audit #3 (A-09): a user-visible message produced by a
 * ViewModel. ViewModels have no resource access, so a message
 * reaches the screen in one of two shapes and the screen
 * resolves it in the active locale:
 *
 *  - [Res]: a string resource plus its format arguments.
 *    Every message a ViewModel originates itself takes this
 *    shape, so the text exists in every locale's strings.xml
 *    and the ViewModel never concatenates display text.
 *  - [Raw]: text that is already final — messages handed up
 *    from the transport / data layers (SFTP and SSH error
 *    text, backup-service error text), which have no resource
 *    access either. They pass through unchanged.
 */
sealed interface UiMessage {
    data class Res(
        @StringRes val resId: Int,
        val args: List<Any> = emptyList()
    ) : UiMessage

    data class Raw(val text: String) : UiMessage

    companion object {
        /** A message the caller originates: resource + args. */
        fun of(@StringRes resId: Int, vararg args: Any): UiMessage =
            Res(resId, args.toList())

        /** A message received ready-made from a lower layer. */
        fun ofText(text: String): UiMessage = Raw(text)
    }
}

/** Display text for this message in the current locale. */
@Composable
fun UiMessage.resolve(): String = when (this) {
    is UiMessage.Res -> stringResource(resId, *args.toTypedArray())
    is UiMessage.Raw -> text
}
