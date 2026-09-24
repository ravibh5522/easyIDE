package dev.easyide.app.lsp

import dev.easyide.lsp.protocol.CompletionItem
import dev.easyide.lsp.protocol.Hover
import dev.easyide.lsp.protocol.Position

/** A completion or hover request as an extension provider sees it: LSP-shaped, guest URI. */
data class ProviderQuery(
    val uri: String,
    val languageId: String,
    val version: Int?,
    val position: Position,
    val triggerCharacter: String? = null,
)

/** One provider's completion items; [source] names it (the extension id) like a server id. */
data class ProvidedCompletions(val source: String, val items: List<CompletionItem>)

/**
 * Completion and hover sources besides the language servers (lld/wasm-host.md sec 11.3):
 * WASM extensions that called `providers.register`. The presenters merge these after the
 * servers' answers (ranking LSP first); a late or failing provider is simply absent.
 */
interface ExtensionProviders {
    suspend fun completion(query: ProviderQuery): List<ProvidedCompletions>
    suspend fun hover(query: ProviderQuery): List<Hover>

    companion object {
        val NONE: ExtensionProviders = object : ExtensionProviders {
            override suspend fun completion(query: ProviderQuery) = emptyList<ProvidedCompletions>()
            override suspend fun hover(query: ProviderQuery) = emptyList<Hover>()
        }
    }
}

/**
 * Where the extension platform plugs its providers into the process-wide [LspRuntime]
 * (registered once by the composition root, like contributed servers); every workspace's
 * presenters read through it.
 */
class ExtensionProviderSlot : ExtensionProviders {
    @Volatile private var delegate: ExtensionProviders = ExtensionProviders.NONE

    fun register(providers: ExtensionProviders) { delegate = providers }

    override suspend fun completion(query: ProviderQuery) = delegate.completion(query)
    override suspend fun hover(query: ProviderQuery) = delegate.hover(query)
}
