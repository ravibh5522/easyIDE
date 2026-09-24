package dev.easyide.app.extensions.wasm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.easyide.extwasm.host.ClipboardPort
import dev.easyide.extwasm.host.SecretPort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The system clipboard, behind an interface so JVM tests fake it. */
interface ClipboardAccess {
    fun read(): String?
    fun write(text: String)
}

/** [ClipboardManager] of the application context; call on the main thread. */
class AndroidClipboard(private val context: Context) : ClipboardAccess {
    private val manager get() = context.getSystemService(ClipboardManager::class.java)

    override fun read(): String? = manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()

    override fun write(text: String) {
        manager?.setPrimaryClip(ClipData.newPlainText(LABEL, text))
    }

    private companion object { const val LABEL = "easyIDE extension" }
}

/** `clipboard.read/write` (capability `clipboard`, checked by the host). The clipboard lives on the main thread. */
class WasmClipboardPort(
    private val clipboard: ClipboardAccess,
    private val main: CoroutineDispatcher = Dispatchers.Main,
) : ClipboardPort {
    override suspend fun read(): String? = withContext(main) { clipboard.read() }
    override suspend fun write(text: String) = withContext(main) { clipboard.write(text) }
}

/**
 * `secrets.get` (capability `secrets.read`). There is no UI to enter an extension secret
 * yet, so nothing is stored and every lookup answers null (lld/wasm-host.md sec 13
 * `ExtensionSecrets` is future work). Git and Claude credentials are never reachable here.
 */
object NoSecrets : SecretPort {
    override suspend fun get(extensionId: String, name: String): String? = null
}
