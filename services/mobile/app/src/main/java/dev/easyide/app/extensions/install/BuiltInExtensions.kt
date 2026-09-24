package dev.easyide.app.extensions.install

import android.content.res.AssetManager
import dev.easyide.sandbox.extensions.ExtensionId
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Read access to a tree of bundled files; [AssetManager] in the app, a map in tests. */
interface AssetTree {
    /** Child names of [path]; empty for a file or a missing path. */
    fun list(path: String): List<String>

    /** I/O boundary: throws IOException. */
    fun open(path: String): InputStream

    companion object {
        fun of(assets: AssetManager): AssetTree = object : AssetTree {
            override fun list(path: String): List<String> = assets.list(path)?.toList().orEmpty()
            override fun open(path: String): InputStream = assets.open(path)
        }
    }
}

/**
 * Built-in extensions ship in the APK under `assets/extensions/<id>/` in the ordinary
 * package layout, which is how easyIDE dogfoods the format: they are parsed, validated
 * and registered exactly like an installed pack (Source.BUILT_IN only changes the
 * enablement rules). The runtime reads directories, so each is unpacked to
 * `<root>/<id>/<stamp>/` once per APK build ([stamp] changes with every install or
 * update of the app); stale stamps and dropped ids are deleted.
 *
 * I/O throughout: call off the main thread.
 */
class BuiltInExtensions(private val assets: AssetTree, private val root: File, private val stamp: String) {

    /**
     * The unpacked package directory of every bundled extension. A package that cannot
     * be unpacked is left out (and its partial copy removed); the runtime then simply
     * does not see it.
     */
    fun directories(): List<File> {
        val ids = assets.list(ASSET_ROOT).filter { name -> ExtensionId.parseOrNull(name)?.value == name }
        root.listFiles()?.filter { it.name !in ids }?.forEach { it.deleteRecursively() }
        return ids.sorted().mapNotNull(::unpacked)
    }

    private fun unpacked(id: String): File? {
        val idDir = File(root, id)
        val target = File(idDir, stamp)
        idDir.listFiles()?.filter { it.name != stamp }?.forEach { it.deleteRecursively() }
        if (target.isDirectory) return target
        val tmp = File(idDir, TMP_PREFIX + stamp)
        tmp.deleteRecursively()
        return try {
            copyTree("$ASSET_ROOT/$id", tmp)
            if (!tmp.renameTo(target)) throw IOException("cannot move ${tmp.name} into place")
            target
        } catch (e: IOException) {
            tmp.deleteRecursively()
            null
        }
    }

    private fun copyTree(assetPath: String, dest: File) {
        val children = assets.list(assetPath)
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        if (!dest.isDirectory && !dest.mkdirs()) throw IOException("cannot create ${dest.path}")
        children.forEach { copyTree("$assetPath/$it", File(dest, it)) }
    }

    companion object {
        const val ASSET_ROOT = "extensions"
        private const val TMP_PREFIX = ".tmp-"
    }
}
