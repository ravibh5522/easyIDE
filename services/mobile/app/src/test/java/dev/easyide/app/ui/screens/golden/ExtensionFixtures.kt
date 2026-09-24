package dev.easyide.app.ui.screens.golden

import dev.easyide.app.extensions.ExtFixtures
import dev.easyide.app.ui.screens.extensions.ExtensionRow
import dev.easyide.app.ui.screens.extensions.ExtensionsUiState
import dev.easyide.app.ui.screens.extensions.InspectorLine
import dev.easyide.extensions.host.ActivationState
import dev.easyide.extensions.host.DisabledReason
import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.host.LoadedExtension
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import java.io.File

/** A realistic Installed list: a registry pack with an update, a local build with a long name, a dev pack switched off, three built-ins. */
internal object ExtensionFixtures {
    private const val SERVER_BODY = """
        "easyide": {
          "capabilities": ["lsp.spawn", "sandbox.install"],
          "languageServers": [
            { "id": "pyright", "languages": ["python"], "command": ["${'$'}{extensionPath}/bin/pyright", "--stdio"] }
          ],
          "sandbox": {
            "install": [ { "id": "tools", "title": "Tools", "run": "npm i -g pyright --prefix ${'$'}{extensionPath}" } ],
            "verify": "pyright-langserver --version"
          }
        }"""

    private fun row(
        publisher: String,
        name: String,
        display: String,
        version: String,
        source: Source,
        description: String,
        enabled: Boolean = true,
        reason: DisabledReason? = null,
        contributions: List<InspectorLine> = emptyList(),
    ): ExtensionRow {
        val manifest = """{ "name": "$name", "publisher": "$publisher", "version": "$version", "displayName": "$display",
            "description": "$description", "license": "Apache-2.0", "engines": { "easyide": "^0.3.0" },
            "categories": ["Programming Languages"], $SERVER_BODY }"""
        val pkg = InstalledPackage(File("/data/extensions/global/$publisher.$name/$version"), InstallScope.GLOBAL, null, source, 1L, setOf("lsp.spawn"), revoked = false, crashDisabled = false)
        return ExtensionRow(
            pkg = pkg,
            loaded = LoadedExtension(pkg, ExtFixtures.descriptor(manifest), emptyList()),
            problem = null,
            activation = ActivationState.ACTIVE.takeIf { enabled },
            disabledReason = reason,
            userEnabled = enabled,
            contributions = contributions,
            shadowed = emptyList(),
        )
    }

    private fun line(ref: String, location: String? = null, hiddenBy: String? = null) =
        InspectorLine(ref, "/contributes/${ref.substringBefore(':')}", hiddenBy, emptyList(), location = location, canMoveUp = location != null, canMoveDown = location != null)

    val docker = row(
        "acme", "docker", "Docker Tools", "2.3.1", Source.REGISTRY, "Build, run and stop containers from the command palette.",
        contributions = listOf(line("command:docker.run"), line("command:docker.stop"), line("menu:editor/title:docker.run", "editor/title"), line("theme:docker-dark")),
    )
    val local = row("acme", "python-lsp", "Python Language Server (local build with a long name)", "0.9.0", Source.SIDELOAD, "A local build.")
    val dev = row("me", "themes", "My Themes", "0.0.1", Source.DEV, "Colour themes under development.", enabled = false, reason = DisabledReason.USER_DISABLED)
    private val builtIns = listOf(
        row("easyide", "python", "Python", "1.0.0", Source.BUILT_IN, "Python language server."),
        row("easyide", "markdown", "Markdown Language Server", "1.0.0", Source.BUILT_IN, "Markdown language server."),
        row("easyide", "project-tasks", "Project Tasks", "1.0.0", Source.BUILT_IN, "Tasks from the project."),
    )

    val state = ExtensionsUiState(rows = listOf(docker, local, dev) + builtIns)
}
