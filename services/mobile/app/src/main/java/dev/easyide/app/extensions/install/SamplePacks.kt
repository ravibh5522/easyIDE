package dev.easyide.app.extensions.install

import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.InputStream

/** A bundled folder of the APK's assets as a [FolderNode], so it stages like any picked folder. */
class AssetFolder(private val assets: AssetTree, private val path: String) : FolderNode {
    override val name: String get() = path.substringAfterLast('/')
    override val isDirectory: Boolean get() = assets.list(path).isNotEmpty()
    override fun children(): List<FolderNode> = assets.list(path).sorted().map { AssetFolder(assets, "$path/$it") }
    override fun open(): InputStream = assets.open(path)
}

/** A sample pack for the list: its id, name and one-line description. */
data class SampleInfo(val id: String, val name: String, val description: String)

/**
 * Sample packs ship in the APK under `assets/extension-samples/<id>/` in the ordinary package layout, next to (not among) the
 * built-in packs: they are never enabled by default. "Install a sample pack" stages one like a picked folder, so it goes through
 * the same validation and capability sheet as anything else, and afterwards it is an ordinary local install the user can disable
 * or remove. I/O throughout: call off the main thread.
 */
class SamplePacks(private val assets: AssetTree) {

    fun list(): List<SampleInfo> = assets.list(ASSET_ROOT).sorted().mapNotNull { id ->
        val manifest = try {
            (JsonText.parseStrict(assets.open("$ASSET_ROOT/$id/package.json").use { it.readBytes().decodeToString() }) as? JsonParse.Ok)?.value as? JsonObject
        } catch (e: IOException) {
            null
        } ?: return@mapNotNull null
        SampleInfo(id, manifest["displayName"]?.stringOrNull ?: id, manifest["description"]?.stringOrNull.orEmpty())
    }

    fun folder(id: String): FolderNode? = if (id in assets.list(ASSET_ROOT)) AssetFolder(assets, "$ASSET_ROOT/$id") else null

    companion object {
        const val ASSET_ROOT = "extension-samples"
    }
}
