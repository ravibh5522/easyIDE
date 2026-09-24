package dev.easyide.app.extensions.authoring

import dev.easyide.extensions.authoring.ExtensionTemplates
import java.io.File
import java.io.IOException

sealed interface ScaffoldResult {
    /** [dir] is `<projectRoot>/<name>`; [relativePath] is the same, relative to the project. */
    data class Created(val id: String, val dir: File, val relativePath: String, val files: List<String>) : ScaffoldResult
    data class Refused(val reason: ScaffoldRefusal, val detail: String = "") : ScaffoldResult
}

enum class ScaffoldRefusal { UNKNOWN_TEMPLATE, INVALID_ID, EXISTS, DAMAGED_TEMPLATE, IO }

/**
 * In-app "Create extension" (arch.md sec 12, M5): renders one of the declarative templates the
 * CLI's `init` ships ([ExtensionTemplates], same files, same substitution) into a new folder
 * `<name>/` of a project, which can then be installed from that folder and reloaded with
 * `easyide-ext dev --local`. The WASM templates need a toolchain and generated guest bindings,
 * so they stay CLI-only. Refuses to write into an existing non-empty folder.
 */
object ExtensionScaffold {
    /** APK asset directory the `copyAuthoringTemplates` Gradle task fills. */
    const val ASSET_DIR = "authoring-templates"

    /** [read] returns a stored template file (`<template>/<path>`, [ExtensionTemplates.LISTING_FILE] included). */
    fun create(
        template: String,
        publisher: String,
        name: String,
        displayName: String?,
        year: Int,
        projectRoot: File,
        read: (template: String, path: String) -> String?,
    ): ScaffoldResult {
        if (template !in ExtensionTemplates.DECLARATIVE) return ScaffoldResult.Refused(ScaffoldRefusal.UNKNOWN_TEMPLATE, template)
        val vars = ExtensionTemplates.variables(publisher, name, displayName, year)
            ?: return ScaffoldResult.Refused(ScaffoldRefusal.INVALID_ID, "${publisher.trim()}.${name.trim()}")
        val folder = vars.getValue("name")
        val dir = File(projectRoot, folder)
        if (dir.exists() && (!dir.isDirectory || !dir.list().isNullOrEmpty())) return ScaffoldResult.Refused(ScaffoldRefusal.EXISTS, folder)
        val files = try {
            ExtensionTemplates.render(template, vars) { read(template, it) }
        } catch (e: IllegalStateException) {
            return ScaffoldResult.Refused(ScaffoldRefusal.DAMAGED_TEMPLATE, e.message.orEmpty())
        }
        try {
            files.forEach { f -> File(dir, f.path).apply { parentFile?.mkdirs() }.writeText(f.text) }
        } catch (e: IOException) {
            return ScaffoldResult.Refused(ScaffoldRefusal.IO, e.message ?: e.javaClass.simpleName)
        }
        return ScaffoldResult.Created("${vars.getValue("publisher")}.$folder", dir, folder, files.map { it.path })
    }
}
