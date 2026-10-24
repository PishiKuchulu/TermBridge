package com.pishi.termbridge.transport

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
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
    object NetworkError : SshError("Network error. Check the connection and try again.")
    object Generic : SshError("Connection failed. Please try again.")
}

/** Pure exception-to-error mapping, unit-tested on the JVM. */
object SshErrorMapper {
    fun map(e: Throwable): SshError = when (e) {
        is CancellationException -> SshError.Cancelled
        is UserAuthException -> SshError.AuthFailed
        is TransportException -> {
            if (e.message?.contains("host key", ignoreCase = true) == true) {
                SshError.HostKeyRejected
            } else {
                SshError.NetworkError
            }
        }
        is UnknownHostException -> SshError.UnknownHost
        is ConnectException -> SshError.ConnectionRefused
        is SocketTimeoutException -> SshError.Timeout
        is IOException -> SshError.NetworkError
        else -> SshError.Generic
    }
}
