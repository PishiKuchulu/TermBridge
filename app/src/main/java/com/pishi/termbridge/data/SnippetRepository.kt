package com.pishi.termbridge.data

import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandValidator
import com.pishi.termbridge.domain.CommandVariable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Global snippets repository (item T-02). Mirrors
 * [CommandRepository]'s rules exactly — same validator (with
 * the secret scan), same sort-order ownership, same atomic
 * child replacement — over the snippet tables. Domain objects
 * are [CommandButton]s whose hostId is [GLOBAL_HOST_ID], so
 * the session's button strip and the command runner treat a
 * snippet exactly like a per-host button; only storage and
 * management are separate.
 */
class SnippetRepository(
    private val dao: SnippetDao,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun getSnippets(): Flow<List<CommandButton>> =
        dao.getAll().map { rows -> rows.map { it.toButton(emptyList(), emptyList()) } }

    suspend fun getSnippetsOnce(): List<CommandButton> =
        dao.getAllOnce().map { row ->
            row.toButton(
                steps = dao.getSteps(row.id).map { it.template },
                variables = dao.getVariables(row.id).map {
                    CommandVariable(it.name, it.defaultValue, it.required)
                }
            )
        }

    suspend fun getSnippet(id: Long): CommandButton? {
        val row = dao.getById(id) ?: return null
        return row.toButton(
            steps = dao.getSteps(id).map { it.template },
            variables = dao.getVariables(id).map {
                CommandVariable(it.name, it.defaultValue, it.required)
            }
        )
    }

    suspend fun saveSnippet(button: CommandButton): CommandButton {
        val issues = CommandValidator.validate(button)
        if (issues.isNotEmpty()) {
            throw CommandRepositoryException(issues.joinToString("; ") { it.message })
        }
        val isNew = button.id == 0L
        val existing = if (isNew) null else dao.getById(button.id)
        if (!isNew && existing == null) {
            throw CommandRepositoryException("Snippet not found.")
        }
        val now = clock()
        val sortOrder = if (isNew) {
            val siblings = dao.getAllOnce()
            (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1
        } else {
            existing!!.sortOrder
        }
        val entity = SnippetEntity(
            id = if (isNew) 0L else button.id,
            label = button.label.trim(),
            category = button.category.trim(),
            sortOrder = sortOrder,
            mode = button.mode.name,
            template = button.template,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        val savedId = if (isNew) dao.insertSnippet(entity) else {
            dao.updateSnippet(entity)
            button.id
        }
        dao.replaceChildren(
            savedId,
            button.steps.mapIndexed { index, step ->
                SnippetStepEntity(snippetId = savedId, sortOrder = index, template = step)
            },
            button.variables.map {
                SnippetVariableEntity(
                    snippetId = savedId,
                    name = it.name,
                    defaultValue = it.defaultValue,
                    required = it.required
                )
            }
        )
        return button.copy(id = savedId, hostId = GLOBAL_HOST_ID, sortOrder = sortOrder)
    }

    suspend fun duplicateSnippet(id: Long): CommandButton {
        val source = getSnippet(id)
            ?: throw CommandRepositoryException("Snippet not found.")
        return saveSnippet(source.copy(id = 0L, label = "${source.label} (copy)"))
    }

    suspend fun deleteSnippet(id: Long) {
        dao.deleteSteps(id)
        dao.deleteVariables(id)
        dao.deleteSnippet(id)
    }

    suspend fun moveSnippet(id: Long, up: Boolean): Boolean {
        val row = dao.getById(id) ?: return false
        val siblings = dao.getAllOnce()
        val index = siblings.indexOfFirst { it.id == id }
        if (index < 0) return false
        val neighbourIndex = if (up) index - 1 else index + 1
        if (neighbourIndex !in siblings.indices) return false
        val neighbour = siblings[neighbourIndex]
        dao.swapSortOrders(
            row.copy(sortOrder = neighbour.sortOrder),
            neighbour.copy(sortOrder = row.sortOrder)
        )
        return true
    }

    private fun SnippetEntity.toButton(
        steps: List<String>,
        variables: List<CommandVariable>
    ): CommandButton = CommandButton(
        id = id,
        hostId = GLOBAL_HOST_ID,
        label = label,
        category = category,
        sortOrder = sortOrder,
        mode = CommandMode.valueOf(mode),
        template = template,
        steps = steps,
        variables = variables
    )
}
