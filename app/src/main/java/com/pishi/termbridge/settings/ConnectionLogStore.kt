package com.pishi.termbridge.settings

import android.content.Context
import com.pishi.termbridge.domain.ConnectionLogCodec
import com.pishi.termbridge.domain.ConnectionLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local-only connection history (no network, no terminal output). */
class ConnectionLogStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("termbridge_connection_log", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(ConnectionLogCodec.decode(prefs.getString(KEY, null)))
    val entries: StateFlow<List<ConnectionLogEntry>> = _entries.asStateFlow()

    @Synchronized
    fun record(entry: ConnectionLogEntry) {
        val next = ConnectionLogCodec.prepend(_entries.value, entry)
        prefs.edit().putString(KEY, ConnectionLogCodec.encode(next)).apply()
        _entries.value = next
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).apply()
        _entries.value = emptyList()
    }

    private companion object {
        const val KEY = "entries"
    }
}
