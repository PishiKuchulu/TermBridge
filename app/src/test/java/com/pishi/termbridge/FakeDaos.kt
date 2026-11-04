package com.pishi.termbridge

import com.pishi.termbridge.data.CommandButtonDao
import com.pishi.termbridge.data.CommandButtonEntity
import com.pishi.termbridge.data.CommandStepEntity
import com.pishi.termbridge.data.CommandVariableEntity
import com.pishi.termbridge.data.HostDao
import com.pishi.termbridge.data.HostEntity
import com.pishi.termbridge.data.HostEnvVarDao
import com.pishi.termbridge.data.JumpHopDao
import com.pishi.termbridge.data.JumpHopEntity
import com.pishi.termbridge.data.ProxyDao
import com.pishi.termbridge.data.ProxyEntity
import com.pishi.termbridge.data.TunnelDao
import com.pishi.termbridge.data.TunnelEntity
import com.pishi.termbridge.data.HostEnvVarEntity
import com.pishi.termbridge.data.HostSettingsDao
import com.pishi.termbridge.data.HostSettingsEntity
import com.pishi.termbridge.data.TrustedHostKeyDao
import com.pishi.termbridge.data.TrustedHostKeyEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory HostDao for JVM repository tests (not Room, not shipped). */
class FakeHostDao : HostDao {
    private val rows = mutableMapOf<Long, HostEntity>()
    private val state = MutableStateFlow<List<HostEntity>>(emptyList())
    private var nextId = 1L

    private fun emit() {
        state.value = rows.values.sortedBy { it.name.lowercase() }
    }

    override fun getAll(): Flow<List<HostEntity>> = state.map { it }

    override suspend fun getById(id: Long): HostEntity? = rows[id]

    override suspend fun insert(host: HostEntity): Long {
        val id = nextId++
        rows[id] = host.copy(id = id)
        emit()
        return id
    }

    override suspend fun update(host: HostEntity) {
        rows[host.id] = host
        emit()
    }

    override suspend fun deleteById(id: Long) {
        rows.remove(id)
        emit()
    }

    override suspend fun touchLastConnected(id: Long, atMs: Long) {
        rows[id]?.let { rows[id] = it.copy(lastConnectedAt = atMs) }
        emit()
    }

    override suspend fun setFavorite(id: Long, favorite: Boolean) {
        rows[id]?.let { rows[id] = it.copy(favorite = favorite) }
        emit()
    }

    override suspend fun countByKeychainEntryId(entryId: Long): Int =
        rows.values.count { it.keychainEntryId == entryId }

    override suspend fun clearKeychainEntryId(entryId: Long) {
        for ((id, row) in rows) {
            if (row.keychainEntryId == entryId) {
                rows[id] = row.copy(keychainEntryId = null)
            }
        }
        emit()
    }

    /** Test helper: the raw stored row, including its encrypted blob. */
    fun rawRow(id: Long): HostEntity? = rows[id]
}

/** In-memory KeychainDao for JVM repository tests (not Room, not
 *  shipped). The unique label index is NOT emulated — duplicate
 *  rejection is a repository rule and is tested there. */
class FakeKeychainDao : com.pishi.termbridge.data.KeychainDao {
    private val rows = mutableMapOf<Long, com.pishi.termbridge.data.KeychainEntryEntity>()
    private val state = MutableStateFlow<List<com.pishi.termbridge.data.KeychainEntryEntity>>(emptyList())
    private var nextId = 1L

    private fun emit() {
        state.value = rows.values.sortedBy { it.label.lowercase() }
    }

    override fun getAll(): Flow<List<com.pishi.termbridge.data.KeychainEntryEntity>> = state.map { it }

    override suspend fun getById(id: Long): com.pishi.termbridge.data.KeychainEntryEntity? = rows[id]

    override suspend fun getByLabel(label: String): com.pishi.termbridge.data.KeychainEntryEntity? =
        rows.values.firstOrNull { it.label == label }

    override suspend fun insert(entry: com.pishi.termbridge.data.KeychainEntryEntity): Long {
        val id = nextId++
        rows[id] = entry.copy(id = id)
        emit()
        return id
    }

    override suspend fun update(entry: com.pishi.termbridge.data.KeychainEntryEntity) {
        rows[entry.id] = entry
        emit()
    }

    override suspend fun deleteById(id: Long) {
        rows.remove(id)
        emit()
    }

    /** Test helper: the raw stored row, including its encrypted blob. */
    fun rawRow(id: Long): com.pishi.termbridge.data.KeychainEntryEntity? = rows[id]
}

/** In-memory CommandButtonDao for JVM tests (not Room, not shipped).
 *  Foreign-key cascades are emulated in deleteButton/deleteForHost.
 *
 *  Prompt 15: replaceChildren emulates the Room @Transaction
 *  contract — it applies to scratch copies and commits only on
 *  success, so a failure leaves the old children intact. The
 *  production atomicity itself is Room's @Transaction guarantee,
 *  which cannot execute on the JVM; what the JVM test proves is
 *  the repository contract (save goes through the single
 *  replaceChildren call, never four separate calls). Set
 *  [failNextReplaceChildren] to inject a mid-replacement failure.
 */
class FakeCommandButtonDao : CommandButtonDao {
    var failNextReplaceChildren = false
    private val buttons = mutableMapOf<Long, CommandButtonEntity>()
    private val steps = mutableMapOf<Long, MutableList<CommandStepEntity>>()
    private val variables = mutableMapOf<Long, MutableList<CommandVariableEntity>>()
    private val state = MutableStateFlow<List<CommandButtonEntity>>(emptyList())
    private var nextId = 1L
    private var nextChildId = 1L

    private fun emit() {
        state.value = buttons.values.sortedWith(
            compareBy({ it.sortOrder }, { it.id })
        )
    }

    override fun getForHost(hostId: Long): Flow<List<CommandButtonEntity>> =
        state.map { rows -> rows.filter { it.hostId == hostId } }

    override suspend fun getById(id: Long): CommandButtonEntity? = buttons[id]

    override suspend fun getForHostOnce(hostId: Long): List<CommandButtonEntity> =
        buttons.values.filter { it.hostId == hostId }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun getSteps(buttonId: Long): List<CommandStepEntity> =
        (steps[buttonId] ?: emptyList()).sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun getVariables(buttonId: Long): List<CommandVariableEntity> =
        (variables[buttonId] ?: emptyList()).sortedBy { it.id }

    override suspend fun insertButton(button: CommandButtonEntity): Long {
        val id = nextId++
        buttons[id] = button.copy(id = id)
        emit()
        return id
    }

    override suspend fun insertSteps(newSteps: List<CommandStepEntity>) {
        for (step in newSteps) {
            val list = steps.getOrPut(step.buttonId) { mutableListOf() }
            list += step.copy(id = nextChildId++)
        }
    }

    override suspend fun insertVariables(newVariables: List<CommandVariableEntity>) {
        for (variable in newVariables) {
            val list = variables.getOrPut(variable.buttonId) { mutableListOf() }
            list += variable.copy(id = nextChildId++)
        }
    }

    override suspend fun updateButton(button: CommandButtonEntity) {
        buttons[button.id] = button
        emit()
    }

    override suspend fun deleteSteps(buttonId: Long) {
        steps.remove(buttonId)
    }

    override suspend fun deleteVariables(buttonId: Long) {
        variables.remove(buttonId)
    }

    override suspend fun deleteButton(id: Long) {
        steps.remove(id)
        variables.remove(id)
        buttons.remove(id)
        emit()
    }

    override suspend fun deleteForHost(hostId: Long) {
        val ids = buttons.values.filter { it.hostId == hostId }.map { it.id }
        ids.forEach { deleteButton(it) }
    }

    override suspend fun replaceChildren(
        buttonId: Long,
        newStepRows: List<CommandStepEntity>,
        newVariableRows: List<CommandVariableEntity>
    ) {
        if (failNextReplaceChildren) {
            failNextReplaceChildren = false
            throw IllegalStateException("Injected replacement failure.")
        }
        // Build the replacement first; commit only on success, so
        // a throw anywhere above leaves the old children intact.
        val committedSteps = newStepRows.map { it.copy(id = nextChildId++) }
        val committedVariables = newVariableRows.map { it.copy(id = nextChildId++) }
        steps.remove(buttonId)
        variables.remove(buttonId)
        steps[buttonId] = committedSteps.toMutableList()
        variables[buttonId] = committedVariables.toMutableList()
    }

    override suspend fun swapSortOrders(
        first: CommandButtonEntity,
        second: CommandButtonEntity
    ) {
        updateButton(first)
        updateButton(second)
    }
}

/** In-memory HostSettingsDao / HostEnvVarDao for JVM tests (not Room,
 *  not shipped). The unique (hostId, name) index is NOT emulated —
 *  duplicate rejection is a repository rule and is tested there. */
class FakeHostSettingsDao : HostSettingsDao {
    private val rows = mutableMapOf<Long, HostSettingsEntity>()
    private val state = MutableStateFlow<HostSettingsEntity?>(null)

    override fun getForHost(hostId: Long): Flow<HostSettingsEntity?> =
        state.map { if (it?.hostId == hostId) it else null }

    override suspend fun getForHostOnce(hostId: Long): HostSettingsEntity? =
        rows[hostId]

    override suspend fun upsert(settings: HostSettingsEntity) {
        rows[settings.hostId] = settings
        state.value = settings
    }

    override suspend fun deleteForHost(hostId: Long) {
        rows.remove(hostId)
        if (state.value?.hostId == hostId) state.value = null
    }
}

class FakeHostEnvVarDao : HostEnvVarDao {
    private val rows = mutableMapOf<Long, HostEnvVarEntity>()
    private val state = MutableStateFlow<List<HostEnvVarEntity>>(emptyList())
    private var nextId = 1L

    private fun emit() {
        state.value = rows.values.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    }

    override fun getForHost(hostId: Long): Flow<List<HostEnvVarEntity>> =
        state.map { list -> list.filter { it.hostId == hostId } }

    override suspend fun getById(id: Long): HostEnvVarEntity? = rows[id]

    override suspend fun getByName(hostId: Long, name: String): HostEnvVarEntity? =
        rows.values.firstOrNull { it.hostId == hostId && it.name == name }

    override suspend fun getForHostOnce(hostId: Long): List<HostEnvVarEntity> =
        rows.values.filter { it.hostId == hostId }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun insert(variable: HostEnvVarEntity): Long {
        val id = nextId++
        rows[id] = variable.copy(id = id)
        emit()
        return id
    }

    override suspend fun update(variable: HostEnvVarEntity) {
        rows[variable.id] = variable
        emit()
    }

    override suspend fun deleteById(id: Long) {
        rows.remove(id)
        emit()
    }

    override suspend fun deleteForHost(hostId: Long) {
        rows.values.filter { it.hostId == hostId }.map { it.id }.forEach { rows.remove(it) }
        emit()
    }

    /** Test helper: the raw stored row, including its encrypted blob. */
    fun rawRow(id: Long): HostEnvVarEntity? = rows[id]

    fun rawRowsForHost(hostId: Long): List<HostEnvVarEntity> =
        rows.values.filter { it.hostId == hostId }
}

/** In-memory TunnelDao / JumpHopDao / ProxyDao for JVM tests (not
 *  Room, not shipped). The unique (targetHostId, jumpHostId) index
 *  is NOT emulated — duplicate rejection is a repository/validator
 *  rule and is tested there. */
class FakeTunnelDao : TunnelDao {
    private val rows = mutableMapOf<Long, TunnelEntity>()
    private val state = MutableStateFlow<List<TunnelEntity>>(emptyList())
    private var nextId = 1L

    private fun emit() {
        state.value = rows.values.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    }

    override fun getForHost(hostId: Long): Flow<List<TunnelEntity>> =
        state.map { list -> list.filter { it.hostId == hostId } }

    override suspend fun getForHostOnce(hostId: Long): List<TunnelEntity> =
        rows.values.filter { it.hostId == hostId }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun getById(id: Long): TunnelEntity? = rows[id]

    override suspend fun insert(tunnel: TunnelEntity): Long {
        val id = nextId++
        rows[id] = tunnel.copy(id = id)
        emit()
        return id
    }

    override suspend fun update(tunnel: TunnelEntity) {
        rows[tunnel.id] = tunnel
        emit()
    }

    override suspend fun deleteById(id: Long) {
        rows.remove(id)
        emit()
    }

    override suspend fun deleteForHost(hostId: Long) {
        rows.values.filter { it.hostId == hostId }.map { it.id }.forEach { rows.remove(it) }
        emit()
    }
}

class FakeJumpHopDao : JumpHopDao {
    private val rows = mutableMapOf<Long, JumpHopEntity>()
    private val state = MutableStateFlow<List<JumpHopEntity>>(emptyList())
    private var nextId = 1L

    private fun emit() {
        state.value = rows.values.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    }

    override fun getForTarget(targetHostId: Long): Flow<List<JumpHopEntity>> =
        state.map { list -> list.filter { it.targetHostId == targetHostId } }

    override suspend fun getForTargetOnce(targetHostId: Long): List<JumpHopEntity> =
        rows.values.filter { it.targetHostId == targetHostId }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun insert(hop: JumpHopEntity) {
        val id = nextId++
        rows[id] = hop.copy(id = id)
        emit()
    }

    override suspend fun deleteForTarget(targetHostId: Long) {
        rows.values.filter { it.targetHostId == targetHostId }.map { it.id }
            .forEach { rows.remove(it) }
        emit()
    }

    override suspend fun replaceChain(targetHostId: Long, jumpHostIds: List<Long>) {
        deleteForTarget(targetHostId)
        jumpHostIds.forEachIndexed { index, jumpHostId ->
            insert(
                JumpHopEntity(
                    targetHostId = targetHostId,
                    jumpHostId = jumpHostId,
                    sortOrder = index
                )
            )
        }
    }
}

class FakeProxyDao : ProxyDao {
    private val rows = mutableMapOf<Long, ProxyEntity>()
    private val state = MutableStateFlow<ProxyEntity?>(null)

    override suspend fun getForHostOnce(hostId: Long): ProxyEntity? = rows[hostId]

    override fun getForHost(hostId: Long): Flow<ProxyEntity?> =
        state.map { if (it?.hostId == hostId) it else null }

    override suspend fun insert(proxy: ProxyEntity) {
        rows[proxy.hostId] = proxy
        state.value = proxy
    }

    override suspend fun update(proxy: ProxyEntity) {
        rows[proxy.hostId] = proxy
        state.value = proxy
    }

    override suspend fun deleteForHost(hostId: Long) {
        rows.remove(hostId)
        if (state.value?.hostId == hostId) state.value = null
    }
}

/** In-memory TrustedHostKeyDao for JVM tests (not Room, not shipped). */
class FakeTrustedHostKeyDao : TrustedHostKeyDao {
    private val rows = mutableMapOf<String, TrustedHostKeyEntity>()

    override suspend fun getByEndpoint(endpoint: String): TrustedHostKeyEntity? =
        rows[endpoint]

    override suspend fun upsert(record: TrustedHostKeyEntity) {
        rows[record.endpoint] = record
    }

    override suspend fun deleteByEndpoint(endpoint: String) {
        rows.remove(endpoint)
    }

    override fun observeAll(): kotlinx.coroutines.flow.Flow<List<TrustedHostKeyEntity>> =
        kotlinx.coroutines.flow.flowOf(rows.values.sortedBy { it.endpoint })
}

/** In-memory SnippetDao for JVM tests (mirrors FakeCommandButtonDao). */
class FakeSnippetDao : com.pishi.termbridge.data.SnippetDao {
    private val snippets = mutableMapOf<Long, com.pishi.termbridge.data.SnippetEntity>()
    private val steps = mutableMapOf<Long, MutableList<com.pishi.termbridge.data.SnippetStepEntity>>()
    private val variables = mutableMapOf<Long, MutableList<com.pishi.termbridge.data.SnippetVariableEntity>>()
    private val state = MutableStateFlow<List<com.pishi.termbridge.data.SnippetEntity>>(emptyList())
    private var nextId = 1L
    private var nextChildId = 1L

    private fun emit() {
        state.value = snippets.values.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    }

    override fun getAll(): Flow<List<com.pishi.termbridge.data.SnippetEntity>> = state

    override suspend fun getAllOnce(): List<com.pishi.termbridge.data.SnippetEntity> =
        snippets.values.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun getById(id: Long): com.pishi.termbridge.data.SnippetEntity? = snippets[id]

    override suspend fun getSteps(snippetId: Long): List<com.pishi.termbridge.data.SnippetStepEntity> =
        (steps[snippetId] ?: emptyList()).sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun getVariables(snippetId: Long): List<com.pishi.termbridge.data.SnippetVariableEntity> =
        (variables[snippetId] ?: emptyList()).sortedBy { it.id }

    override suspend fun insertSnippet(snippet: com.pishi.termbridge.data.SnippetEntity): Long {
        val id = nextId++
        snippets[id] = snippet.copy(id = id)
        emit()
        return id
    }

    override suspend fun insertSteps(newSteps: List<com.pishi.termbridge.data.SnippetStepEntity>) {
        for (step in newSteps) {
            steps.getOrPut(step.snippetId) { mutableListOf() } += step.copy(id = nextChildId++)
        }
    }

    override suspend fun insertVariables(newVariables: List<com.pishi.termbridge.data.SnippetVariableEntity>) {
        for (variable in newVariables) {
            variables.getOrPut(variable.snippetId) { mutableListOf() } += variable.copy(id = nextChildId++)
        }
    }

    override suspend fun updateSnippet(snippet: com.pishi.termbridge.data.SnippetEntity) {
        snippets[snippet.id] = snippet
        emit()
    }

    override suspend fun deleteSteps(snippetId: Long) {
        steps.remove(snippetId)
    }

    override suspend fun deleteVariables(snippetId: Long) {
        variables.remove(snippetId)
    }

    override suspend fun deleteSnippet(id: Long) {
        steps.remove(id)
        variables.remove(id)
        snippets.remove(id)
        emit()
    }

    override suspend fun replaceChildren(
        snippetId: Long,
        newSteps: List<com.pishi.termbridge.data.SnippetStepEntity>,
        newVariables: List<com.pishi.termbridge.data.SnippetVariableEntity>
    ) {
        val committedSteps = newSteps.map { it.copy(id = nextChildId++) }
        val committedVariables = newVariables.map { it.copy(id = nextChildId++) }
        steps.remove(snippetId)
        variables.remove(snippetId)
        steps[snippetId] = committedSteps.toMutableList()
        variables[snippetId] = committedVariables.toMutableList()
    }

    override suspend fun swapSortOrders(
        first: com.pishi.termbridge.data.SnippetEntity,
        second: com.pishi.termbridge.data.SnippetEntity
    ) {
        updateSnippet(first)
        updateSnippet(second)
    }
}
