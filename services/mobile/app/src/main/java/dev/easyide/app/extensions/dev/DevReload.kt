package dev.easyide.app.extensions.dev

import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.PackagePaths
import dev.easyide.extensions.manifest.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.nio.file.Files

/** Why a developer install needs the capability sheet instead of reloading silently. */
enum class PromptReason { FIRST_INSTALL, REPLACES_NON_DEV, CAPABILITIES_CHANGED, NEEDS_ENVIRONMENT }

sealed interface DevDecision {
    data class Refuse(val reason: String) : DevDecision

    /** Show the capability sheet; [envId] is the suggested target for an environment pack. */
    data class Prompt(val reason: PromptReason, val envId: String?, val added: Set<String> = emptySet()) : DevDecision

    /** Same capability set as the running developer install: install without asking. */
    data class Silent(val envId: String?) : DevDecision
}

/** A located developer package: the adb inbox archive or a project folder named by a request. */
sealed interface DevSource {
    data class Archive(val id: String, val file: File) : DevSource
    data class Folder(val id: String, val dir: File) : DevSource
}

sealed interface Located {
    data class Found(val source: DevSource) : Located
    data class Refused(val reason: String) : Located
}

/**
 * The pure rules of the developer loop (lld/cli.md sec 5.7, registry-and-install.md sec 8.4),
 * shared by the adb receiver and the `--local` request watcher:
 *
 * - `easyide-ext dev` pushes `<id>.easyext` into [INBOX_DIR] under the app's external files
 *   dir and broadcasts [ACTION] with extra [EXTRA_ID]; `dev --local` writes
 *   `<workspace>/.easyide/dev/<id>.json` = `{id, folder, requestedAt}` with `folder` relative to
 *   the workspace (project) root.
 * - Nothing happens unless `extensions.developerMode` is on (checked by the caller).
 * - Each install gets its own version, [devVersion], so the runtime (which caches a version
 *   directory as immutable and reloads on an `(id, version)` change) picks up every reload.
 * - The capability sheet shows on the first developer install of an id, when it would replace
 *   a registry or sideloaded install, and whenever the declared capabilities differ from what
 *   was approved; identical sets reload silently ([decide]).
 */
object DevReload {
    const val ACTION = "dev.easyide.app.action.DEV_RELOAD"
    const val EXTRA_ID = "id"
    const val INBOX_DIR = "dev-inbox"
    const val ARCHIVE_SUFFIX = ".easyext"

    /** Project-relative directory of `dev --local` requests. */
    const val REQUEST_DIR = ".easyide/dev"
    const val REQUEST_SUFFIX = ".json"
    private const val DEV_TAG = "dev"
    private val json = Json { isLenient = false }

    /** An id exactly as a package id is spelled (lowercase `publisher.name`), else null. */
    fun validId(raw: String?): String? = raw?.let(ExtensionId::parse)?.value?.takeIf { it == raw }

    /** The archive the adb push left for [id] in [inbox]. */
    fun locateInbox(inbox: File, id: String?): Located {
        val valid = validId(id) ?: return Located.Refused("dev reload without a valid extension id (got '${id.orEmpty()}')")
        val file = File(inbox, valid + ARCHIVE_SUFFIX)
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile) return Located.Refused("no ${file.name} in the dev inbox; push it with easyide-ext dev")
        return Located.Found(DevSource.Archive(valid, file))
    }

    /** Request files in a project's [REQUEST_DIR], oldest name first; none when the dir is absent. */
    fun requestFiles(requestDir: File): List<File> =
        requestDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(REQUEST_SUFFIX) }.sortedBy { it.name }

    /**
     * Reads a `dev --local` request [text] from the file named [fileName] and maps its folder
     * into [projectRoot]. Refused: malformed JSON, an id that is not the file's name, a folder
     * that is absolute, uses `..` or resolves (through links) outside the project, or is not a
     * directory.
     */
    fun locateRequest(text: String, fileName: String, projectRoot: File): Located {
        val obj = try { json.parseToJsonElement(text) as? JsonObject } catch (e: IllegalArgumentException) { null }
            ?: return Located.Refused("$fileName is not a dev request object")
        val id = validId((obj["id"] as? JsonPrimitive)?.contentOrNull)
            ?: return Located.Refused("$fileName has no valid id")
        if (fileName != id + REQUEST_SUFFIX) return Located.Refused("$fileName names $id; the file must be $id$REQUEST_SUFFIX")
        val folder = (obj["folder"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Located.Refused("$fileName has no folder")
        val rel = if (folder == "." || folder == "./") "" else PackagePaths.normalize(folder)
            ?: return Located.Refused("$fileName: folder '$folder' must be relative to the project, without '..'")
        val root = projectRoot.canonicalFile
        val dir = File(root, rel).canonicalFile
        if (dir != root && !dir.path.startsWith(root.path + File.separator)) {
            return Located.Refused("$fileName: folder '$folder' leaves the project")
        }
        if (!dir.isDirectory) return Located.Refused("$fileName: folder '$folder' is not a directory in this project")
        return Located.Found(DevSource.Folder(id, dir))
    }

    /** `1.2.0` -> `1.2.0-dev.<stamp>`; `1.2.0-beta.1` -> `1.2.0-beta.1.dev.<stamp>`. */
    fun devVersion(version: String, stamp: Long): String {
        require(stamp >= 0)
        return if ('-' in version) "$version.$DEV_TAG.$stamp" else "$version-$DEV_TAG.$stamp"
    }

    /**
     * [manifest] (package.json text) with its `version` replaced by [devVersion]; null when it
     * is not a JSON object with a string version, which validation then reports as usual.
     */
    fun withDevVersion(manifest: String, stamp: Long): String? {
        val obj = try { json.parseToJsonElement(manifest) as? JsonObject } catch (e: IllegalArgumentException) { null } ?: return null
        val version = (obj["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return JsonObject(obj + ("version" to JsonPrimitive(devVersion(version, stamp)))).toString()
    }

    /**
     * Prompt or reload silently. [installed] is the inventory; [currentEnv] is the open
     * workspace's environment, the default target of an environment pack installed for the
     * first time.
     */
    fun decide(requestedId: String, d: ExtensionDescriptor, installed: List<InstalledPackage>, currentEnv: String?): DevDecision {
        if (d.id.value != requestedId) return DevDecision.Refuse("the package is ${d.id}, not the requested $requestedId")
        val same = installed.filter { it.source != Source.BUILT_IN && it.directory.parentFile?.name == requestedId && it.scope == d.scope }
        val prior = if (d.scope == InstallScope.GLOBAL) same.firstOrNull() else same.firstOrNull { it.envId == currentEnv } ?: same.firstOrNull()
        val envId = if (d.scope == InstallScope.GLOBAL) null else prior?.envId ?: currentEnv
        val declared = d.capabilities.items.mapTo(HashSet()) { it.id }
        return when {
            prior == null -> DevDecision.Prompt(PromptReason.FIRST_INSTALL, envId, declared)
            prior.source != Source.DEV -> DevDecision.Prompt(PromptReason.REPLACES_NON_DEV, envId, declared - prior.approvedCapabilities)
            declared != prior.approvedCapabilities -> DevDecision.Prompt(PromptReason.CAPABILITIES_CHANGED, envId, declared - prior.approvedCapabilities)
            d.scope == InstallScope.ENVIRONMENT && envId == null -> DevDecision.Prompt(PromptReason.NEEDS_ENVIRONMENT, null)
            else -> DevDecision.Silent(envId)
        }
    }
}
