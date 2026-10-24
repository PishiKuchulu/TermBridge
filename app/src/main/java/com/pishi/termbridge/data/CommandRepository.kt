package com.pishi.termbridge.data

import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandValidator
import com.pishi.termbridge.domain.CommandVariable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A save/validation failure. Messages never contain a secret. */
class CommandRepositoryException(message: String) : Exception(message)

/**
 * Prompt 08 command-button repository. Rules enforced here, not
 * in the UI: validation (including the secret scan) before any
 * write, per-host scoping, sort-order ownership, and the
 * duplicate/reorder/delete semantics.
 *
 * Prompt 15: child replacement on save is a single Room
 * @Transaction (CommandButtonDao.replaceChildren) — the prompt-08
 * sequential delete-then-insert gap is closed, so a crash
 * mid-save cannot leave a button with partial children. The
 * button row itself was never left half-written; now the
 * children are not either. Reordering uses the same pattern
 * (swapSortOrders). Deletion still deletes children then the
 * row: a crash there leaves a row with no children (re-delete
 * fixes it), and the foreign-key cascade is the second net.
 */
class CommandRepository(
    private val dao: CommandButtonDao,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun getButtons(hostId: Long): Flow<List<CommandButton>> =
        dao.getForHost(hostId).map { rows ->
            // Flow rows carry no children; children are loaded on
            // demand by getButton. The list view needs label/mode/
            // order only, which is exactly what the row holds, so
            // listing does not N+1 query the children tables.
            rows.map { it.toButton(emptyList(), emptyList()) }
        }

    suspend fun getButton(id: Long): CommandButton? {
        val row = dao.getById(id) ?: return null
        return row.toButton(
            steps = dao.getSteps(id).map { it.template },
            variables = dao.getVariables(id).map {
                CommandVariable(it.name, it.defaultValue, it.required)
            }
        )
    }

    /** Save a new button (id == 0) or update an existing one. */
    suspend fun saveButton(button: CommandButton): CommandButton {
        val issues = CommandValidator.validate(button)
        if (issues.isNotEmpty()) {
            throw CommandRepositoryException(issues.joinToString("; ") { it.message })
        }
        val isNew = button.id == 0L
        val existing = if (isNew) null else dao.getById(button.id)
        if (!isNew && existing == null) {
            throw CommandRepositoryException("Command button not found.")
        }
        if (existing != null && existing.hostId != button.hostId) {
            throw CommandRepositoryException("A button cannot be moved to another host.")
        }
        val now = clock()
        val sortOrder = if (isNew) {
            val siblings = dao.getForHostOnce(button.hostId)
            (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1
        } else {
            existing!!.sortOrder
        }
        val entity = CommandButtonEntity(
            id = if (isNew) 0L else button.id,
            hostId = button.hostId,
            label = button.label.trim(),
            category = button.category.trim(),
            sortOrder = sortOrder,
            mode = button.mode.name,
            template = button.template,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        val savedId = if (isNew) dao.insertButton(entity) else {
            dao.updateButton(entity)
            button.id
        }
        // Prompt 15: one transaction, not four separate calls.
        dao.replaceChildren(
            savedId,
            button.steps.mapIndexed { index, step ->
                CommandStepEntity(buttonId = savedId, sortOrder = index, template = step)
            },
            button.variables.map {
                CommandVariableEntity(
                    buttonId = savedId,
                    name = it.name,
                    defaultValue = it.defaultValue,
                    required = it.required
                )
            }
        )
        return button.copy(id = savedId, sortOrder = sortOrder)
    }

    /** Duplicate: same mode/template/steps/variables, new id, no
     *  secret concern (buttons never hold secrets), "(copy)" label,
     *  appended at the end of the host's list. */
    suspend fun duplicateButton(id: Long): CommandButton {
        val source = getButton(id)
            ?: throw CommandRepositoryException("Command button not found.")
        return saveButton(
            source.copy(id = 0L, label = "${source.label} (copy)")
        )
    }

    suspend fun deleteButton(id: Long) {
        dao.deleteSteps(id)
        dao.deleteVariables(id)
        dao.deleteButton(id)
    }

    suspend fun deleteForHost(hostId: Long) {
        dao.deleteForHost(hostId)
    }

    /**
     * Move a button one position up or down within its host's list
     * by swapping sortOrder with its neighbour. Returns false when
     * there is no neighbour in that direction (already first/last).
     */
    suspend fun moveButton(id: Long, up: Boolean): Boolean {
        val row = dao.getById(id) ?: return false
        val siblings = dao.getForHostOnce(row.hostId)
        val index = siblings.indexOfFirst { it.id == id }
        if (index < 0) return false
        val neighbourIndex = if (up) index - 1 else index + 1
        if (neighbourIndex !in siblings.indices) return false
        val neighbour = siblings[neighbourIndex]
        // Prompt 15: one transaction, not two separate updates.
        dao.swapSortOrders(
            row.copy(sortOrder = neighbour.sortOrder),
            neighbour.copy(sortOrder = row.sortOrder)
        )
        return true
    }

    private fun CommandButtonEntity.toButton(
        steps: List<String>,
        variables: List<CommandVariable>
    ): CommandButton = CommandButton(
        id = id,
        hostId = hostId,
        label = label,
        category = category,
        sortOrder = sortOrder,
        mode = CommandMode.valueOf(mode),
        template = template,
        steps = steps,
        variables = variables
    )
}
