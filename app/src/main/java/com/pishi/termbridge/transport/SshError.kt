package com.pishi.termbridge.transport

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException

/**
 * User-facing SSH failure types. Messages are FIXED strings — the
 * original exception message is never copied into them, because an
 * exception message could in theory echo user input. Hostname and
 * port are safe to show (they are not secrets); passwords, keys,
 * and passphrases are never in any message here.
 */
sealed class SshError(val userMessage: String) {
    object AuthFailed : SshError("Authentication failed. Check the username and credential.")
    object HostKeyRejected : SshError("Host key was not accepted. Connection stopped.")
    object HostUnreachable : SshError("Could not reach the host. Check the hostname, port, and network.")
    object ConnectionRefused : SshError("Connection refused. The server may not have SSH enabled on this port.")
    object UnknownHost : SshError("Unknown host. Check the hostname spelling.")
    object Timeout : SshError("Connection timed out. The server did not respond in time.")
    object Cancelled : SshError("Connection cancelled.")
    object SecureChannelFailed : SshError(
        "Could not establish a secure channel with the host (key exchange or protocol failure)."
    )
    object NetworkError : SshError("Network error. Check the connection and try again.")
    object Generic : SshError("Connection failed. Please try again.")
}

/** Pure exception-to-error mapping, unit-tested on the JVM. */
object SshErrorMapper {
    fun map(e: Throwable): SshError = when (e) {
        is CancellationException -> SshError.Cancelled
        is UserAuthException -> SshError.AuthFailed
        is TransportException -> when (e.disconnectReason) {
            // Review #2 F-06: map on sshj's typed reason, not on
            // message text. The message is only a fallback when sshj
            // gave no reason at all.
            DisconnectReason.HOST_KEY_NOT_VERIFIABLE -> SshError.HostKeyRejected
            DisconnectReason.KEY_EXCHANGE_FAILED,
            DisconnectReason.PROTOCOL_ERROR,
            DisconnectReason.MAC_ERROR,
            DisconnectReason.COMPRESSION_ERROR,
            DisconnectReason.PROTOCOL_VERSION_NOT_SUPPORTED -> SshError.SecureChannelFailed
            DisconnectReason.UNKNOWN ->
                if (e.message?.contains("host key", ignoreCase = true) == true) {
                    SshError.HostKeyRejected
                } else {
                    SshError.NetworkError
                }
            else -> SshError.NetworkError
        }
        is UnknownHostException -> SshError.UnknownHost
        is ConnectException -> SshError.ConnectionRefused
        is SocketTimeoutException -> SshError.Timeout
        is IOException -> SshError.NetworkError
        else -> SshError.Generic
    }

    /**
     * Secret-free diagnostic for a failure (review #2 F-06): sshj's
     * typed DisconnectReason NAME plus the exception CLASS names along
     * the cause chain (bounded depth). Messages are never read, so
     * nothing user- or server-controlled can enter it, which is why it
     * can be shown while the cause chain itself stays dropped.
     * Example: `KEY_EXCHANGE_FAILED:TransportException>NoSuchAlgorithmException`.
     */
    fun diagnose(e: Throwable): SshDiagnostic {
        val names = mutableListOf<String>()
        val seen = IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = e
        while (current != null && names.size < 4 && seen.put(current, true) == null) {
            names += current.javaClass.simpleName.ifEmpty { "Anonymous" }
            current = current.cause
        }
        val reason = (e as? SSHException)?.disconnectReason?.name
        val chain = names.joinToString(">")
        return SshDiagnostic.create(if (reason != null) "$reason:$chain" else chain)
    }
}

/**
 * A diagnostic code that can only be produced by
 * [SshErrorMapper.diagnose], so free text (and therefore a secret)
 * cannot be attached to a transport exception by accident.
 */
class SshDiagnostic private constructor(val code: String) {
    companion object {
        internal fun create(code: String): SshDiagnostic = SshDiagnostic(code)
    }
}
