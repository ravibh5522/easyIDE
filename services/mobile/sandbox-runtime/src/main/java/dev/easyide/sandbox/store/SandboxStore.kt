package dev.easyide.sandbox.store

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import dev.easyide.sandbox.SandboxError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Durable home for sandbox environments and projects. Reads are a [Flow] so the
 * UI re-renders when the runtime mutates state (an environment finishing
 * provisioning, for example) without any manual refresh plumbing.
 */
class SandboxStore(private val dataStore: DataStore<PersistedState>) {

    val state: Flow<PersistedState> = dataStore.data
        .catch { cause ->
            // Boundary: a corrupt or unreadable file must not crash the app.
            // Surfacing empty state lets the user re-create rather than be stuck.
            if (cause is IOException) emit(PersistedState()) else throw cause
        }

    suspend fun current(): PersistedState = state.first()

    /**
     * Applies [transform] atomically. DataStore serializes concurrent writers,
     * so read-modify-write races between the UI and the runtime cannot drop
     * updates the way a naive load/save pair would.
     */
    suspend fun update(transform: (PersistedState) -> PersistedState): PersistedState =
        try {
            dataStore.updateData(transform)
        } catch (cause: IOException) {
            throw SandboxError.StorageFailure("persist sandbox state", cause)
        }

    companion object {
        private const val FILE_NAME = "sandbox-state.json"

        fun create(parentDir: File, scope: CoroutineScope): SandboxStore =
            SandboxStore(
                DataStoreFactory.create(
                    serializer = PersistedStateSerializer,
                    scope = scope,
                    produceFile = { File(parentDir, FILE_NAME) },
                )
            )
    }
}

private object PersistedStateSerializer : Serializer<PersistedState> {

    override val defaultValue = PersistedState()

    override suspend fun readFrom(input: InputStream): PersistedState =
        try {
            PersistedStateJson.decode(input.readBytes().decodeToString())
        } catch (cause: Exception) {
            // DataStore replaces the file when told the content is corrupt,
            // which is the recoverable outcome; rethrowing anything else would
            // leave the store permanently unreadable.
            throw CorruptionException("Unreadable sandbox state", cause)
        }

    override suspend fun writeTo(t: PersistedState, output: OutputStream) {
        output.write(PersistedStateJson.encode(t).encodeToByteArray())
    }
}
