package dev.easyide.sandbox.store

import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.ProjectRecord
import dev.easyide.sandbox.model.SandboxBackend
import dev.easyide.sandbox.model.SandboxEnvironment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand-written JSON mapping for [PersistedState]. Deliberately not generated:
 * it avoids an annotation-processing dependency on a Kotlin version whose KSP
 * and serialization-plugin releases lag behind (see the persistence note in
 * docs/decision/0005-sandbox-environment-sharing-model.md).
 *
 * Unknown enum values decode to a safe default rather than throwing, so a
 * downgrade after a future schema addition degrades instead of wiping state.
 */
internal object PersistedStateJson {

    private const val KEY_VERSION = "version"
    private const val KEY_ENVIRONMENTS = "environments"
    private const val KEY_PROJECTS = "projects"

    private const val KEY_ID = "id"
    private const val KEY_LABEL = "label"
    private const val KEY_BACKEND = "backend"
    private const val KEY_STATE = "state"
    private const val KEY_CREATED_AT = "createdAtEpochMs"
    private const val KEY_LAST_USED_AT = "lastUsedAtEpochMs"
    private const val KEY_FAILURE_REASON = "failureReason"
    private const val KEY_IMAGE_ID = "imageId"

    private const val KEY_NAME = "name"
    private const val KEY_ENVIRONMENT_ID = "environmentId"
    private const val KEY_LAST_OPENED_AT = "lastOpenedAtEpochMs"
    private const val KEY_EXTERNAL_FOLDER_URI = "externalFolderUri"

    /** Bump when the shape changes incompatibly, and handle it in [decode]. */
    const val CURRENT_VERSION = 1

    fun encode(state: PersistedState): String {
        val environments = JSONArray()
        state.environments.forEach { environments.put(encodeEnvironment(it)) }

        val projects = JSONArray()
        state.projects.forEach { projects.put(encodeProject(it)) }

        return JSONObject()
            .put(KEY_VERSION, CURRENT_VERSION)
            .put(KEY_ENVIRONMENTS, environments)
            .put(KEY_PROJECTS, projects)
            .toString()
    }

    fun decode(raw: String): PersistedState {
        if (raw.isBlank()) return PersistedState()
        val root = JSONObject(raw)
        return PersistedState(
            environments = root.optJSONArray(KEY_ENVIRONMENTS).mapObjects(::decodeEnvironment),
            projects = root.optJSONArray(KEY_PROJECTS).mapObjects(::decodeProject),
        )
    }

    private fun encodeEnvironment(environment: SandboxEnvironment) = JSONObject()
        .put(KEY_ID, environment.id)
        .put(KEY_LABEL, environment.label)
        .put(KEY_BACKEND, environment.backend.name)
        .put(KEY_STATE, environment.state.name)
        .put(KEY_CREATED_AT, environment.createdAtEpochMs)
        .put(KEY_LAST_USED_AT, environment.lastUsedAtEpochMs)
        .put(KEY_FAILURE_REASON, environment.failureReason ?: JSONObject.NULL)
        .put(KEY_IMAGE_ID, environment.imageId ?: JSONObject.NULL)

    private fun decodeEnvironment(json: JSONObject) = SandboxEnvironment(
        id = json.getString(KEY_ID),
        label = json.optString(KEY_LABEL),
        backend = enumOrDefault(json.optString(KEY_BACKEND), SandboxBackend.PROOT),
        state = enumOrDefault(json.optString(KEY_STATE), EnvironmentState.NOT_PROVISIONED),
        createdAtEpochMs = json.optLong(KEY_CREATED_AT),
        lastUsedAtEpochMs = json.optLong(KEY_LAST_USED_AT),
        failureReason = json.optStringOrNull(KEY_FAILURE_REASON),
        imageId = json.optStringOrNull(KEY_IMAGE_ID),
    )

    private fun encodeProject(project: ProjectRecord) = JSONObject()
        .put(KEY_ID, project.id)
        .put(KEY_NAME, project.name)
        .put(KEY_ENVIRONMENT_ID, project.environmentId)
        .put(KEY_CREATED_AT, project.createdAtEpochMs)
        .put(KEY_LAST_OPENED_AT, project.lastOpenedAtEpochMs)
        .put(KEY_EXTERNAL_FOLDER_URI, project.externalFolderUri ?: JSONObject.NULL)

    private fun decodeProject(json: JSONObject) = ProjectRecord(
        id = json.getString(KEY_ID),
        name = json.optString(KEY_NAME),
        environmentId = json.optString(KEY_ENVIRONMENT_ID),
        createdAtEpochMs = json.optLong(KEY_CREATED_AT),
        lastOpenedAtEpochMs = json.optLong(KEY_LAST_OPENED_AT),
        externalFolderUri = json.optStringOrNull(KEY_EXTERNAL_FOLDER_URI),
    )

    private inline fun <reified E : Enum<E>> enumOrDefault(raw: String?, fallback: E): E =
        enumValues<E>().find { it.name == raw } ?: fallback

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let(transform)
        }
    }
}
