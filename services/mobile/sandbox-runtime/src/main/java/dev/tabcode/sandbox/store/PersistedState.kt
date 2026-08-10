package dev.tabcode.sandbox.store

import dev.tabcode.sandbox.model.ProjectRecord
import dev.tabcode.sandbox.model.SandboxEnvironment

/**
 * Everything the sandbox layer persists, as one immutable snapshot. Small
 * enough (tens of rows) that whole-snapshot reads and writes are cheaper than
 * a relational store - see the persistence note in
 * docs/decision/0005-sandbox-environment-sharing-model.md for when that stops
 * being true and this should become Room.
 */
data class PersistedState(
    val environments: List<SandboxEnvironment> = emptyList(),
    val projects: List<ProjectRecord> = emptyList(),
) {
    fun environment(id: String): SandboxEnvironment? = environments.find { it.id == id }

    fun project(id: String): ProjectRecord? = projects.find { it.id == id }

    fun projectsUsing(environmentId: String): List<ProjectRecord> =
        projects.filter { it.environmentId == environmentId }

    fun upsertEnvironment(environment: SandboxEnvironment): PersistedState =
        copy(environments = environments.upsert(environment) { it.id == environment.id })

    fun upsertProject(project: ProjectRecord): PersistedState =
        copy(projects = projects.upsert(project) { it.id == project.id })

    fun removeEnvironment(id: String): PersistedState =
        copy(environments = environments.filterNot { it.id == id })

    fun removeProject(id: String): PersistedState =
        copy(projects = projects.filterNot { it.id == id })
}

private fun <T> List<T>.upsert(value: T, matches: (T) -> Boolean): List<T> {
    val index = indexOfFirst(matches)
    return if (index < 0) this + value else toMutableList().apply { set(index, value) }
}
