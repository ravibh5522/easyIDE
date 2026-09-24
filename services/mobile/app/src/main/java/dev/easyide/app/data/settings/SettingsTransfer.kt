package dev.easyide.app.data.settings

import android.content.ContentResolver
import android.net.Uri
import dev.easyide.app.ui.commands.KeybindingsFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.time.Instant

enum class ImportMode {
    /** Imported keys win, everything else is kept; keybindings and profiles are merged entry-wise. */
    MERGE,
    /** The default profile's settings and keybindings become the bundle's; bundled profiles overwrite same-named ones. */
    REPLACE,
}

/** What the import sheet shows before anything is written. */
data class ImportPreview(
    val bundle: ImportBundle,
    val settingCount: Int,
    val keybindingCount: Int,
    val profileNames: List<String>,
    /** Bundled profiles that already exist here. */
    val clashes: List<String>,
)

/**
 * Settings export/import through the Storage Access Framework (LLD sec 15).
 * The zip format lives in [SettingsBundle]; this class gathers the stores'
 * current content and applies a validated bundle to them.
 */
class SettingsTransfer(
    private val resolver: ContentResolver,
    private val defaultUser: DataStoreUserLayer,
    private val defaultKeybindings: FileKeybindings,
    private val profiles: ProfileManager,
    private val registry: SettingsRegistry,
    private val appVersion: String,
    /** Installed extensions for the manifest (`{id, version, source, scope}`); the extension runtime supplies these. */
    private val installedExtensions: suspend () -> List<kotlinx.serialization.json.JsonObject>,
    private val io: CoroutineDispatcher,
) {

    suspend fun export(target: Uri): Result<Unit> = runCatching {
        profiles.refresh()
        val content = ExportContent(
            appVersion = appVersion,
            exportedAt = Instant.now().toString(),
            userSettings = defaultUser.doc.first(),
            keybindings = defaultKeybindings.readText(),
            profiles = profiles.profiles.value.associateWith { profiles.profile(it).load().getOrThrow() },
            extensions = installedExtensions(),
        )
        withContext(io) {
            // Boundary: SAF document.
            val out = resolver.openOutputStream(target, WRITE_TRUNCATE) ?: throw IOException("cannot open $target")
            out.use { SettingsBundle.write(it, content) }
        }
    }

    suspend fun preview(source: Uri): Result<ImportPreview> = runCatching {
        val bundle = withContext(io) {
            val input = resolver.openInputStream(source) ?: throw IOException("cannot open $source")
            input.use { SettingsBundle.read(it, registry.state.value) }
        }
        profiles.refresh()
        val existing = profiles.profiles.value.toSet()
        ImportPreview(
            bundle = bundle,
            settingCount = bundle.settings?.let { it.plain.size + it.lang.values.sumOf(Map<String, JsonElement>::size) } ?: 0,
            keybindingCount = bundle.keybindings?.let { KeybindingsFile.parse(it).entries.size } ?: 0,
            profileNames = bundle.profiles.keys.sorted(),
            clashes = bundle.profiles.keys.filter { it in existing }.sorted(),
        )
    }

    /** One DataStore edit for the default settings, then one temp-and-rename per file. */
    suspend fun apply(bundle: ImportBundle, mode: ImportMode): Result<Unit> = runCatching {
        bundle.settings?.let { imported ->
            when (mode) {
                ImportMode.MERGE -> defaultUser.write(edits(imported))
                ImportMode.REPLACE -> defaultUser.replace(imported) { !SettingsPolicy.isAppLevel(it) }
            }.getOrThrow()
        }
        bundle.keybindings?.let { text ->
            val next = when (mode) {
                ImportMode.REPLACE -> text
                ImportMode.MERGE -> JsoncEditor.render(mergeArrays(KeybindingsFile.parse(defaultKeybindings.readText()).array, KeybindingsFile.parse(text).array))
            }
            defaultKeybindings.writeText(next).getOrThrow()
        }
        profiles.refresh()
        val existing = profiles.profiles.value.toSet()
        for ((name, imported) in bundle.profiles) {
            val handle = profiles.profile(name)
            val next = if (mode == ImportMode.MERGE && name in existing) {
                val current = handle.load().getOrThrow()
                current.copy(
                    settings = LayerDoc.fromJson(current.settings).withEdits(edits(LayerDoc.fromJson(imported.settings))).toJson(),
                    keybindings = mergeArrays(current.keybindings, imported.keybindings),
                    enabledExtensions = imported.enabledExtensions ?: current.enabledExtensions,
                    keyRows = imported.keyRows ?: current.keyRows,
                )
            } else {
                imported
            }
            handle.save(next).getOrThrow()
        }
        profiles.refresh()
    }

    private fun edits(doc: LayerDoc): List<SettingEdit> =
        doc.plain.map { (k, v) -> SettingEdit(k, null, v) } +
            doc.lang.flatMap { (l, b) -> b.map { (k, v) -> SettingEdit(k, l, v) } }

    /** Existing entries first, then imported ones not already present, so imported bindings win dispatch. */
    private fun mergeArrays(existing: JsonArray, imported: JsonArray): JsonArray =
        JsonArray(existing + imported.filter { it !in existing })

    private companion object {
        /** "wt" truncates: without it a shorter export would leave the old file's tail behind. */
        const val WRITE_TRUNCATE = "wt"
    }
}
