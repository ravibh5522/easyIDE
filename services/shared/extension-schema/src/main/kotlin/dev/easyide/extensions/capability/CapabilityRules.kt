package dev.easyide.extensions.capability

import dev.easyide.extensions.action.Action
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.contrib.StagePlacement
import dev.easyide.extensions.view.ActionTarget

/**
 * The sdk-reference Capability column as code: what each action and each contribution
 * needs, statically. The manifest validator refuses an extension whose declared set does
 * not cover these (R-SEC-08); the runner re-checks per step against the granted set and adds
 * the checks only knowable after substitution (paths, config ownership).
 */
object CapabilityRules {

    /** Static requirement of one action; a sequence is the union of its steps. */
    fun required(action: Action): Set<Capability> = when (action) {
        is Action.RunInTerminal, is Action.RunTask, is Action.SandboxExec -> setOf(Capability.SandboxExec)
        is Action.OpenFile -> setOf(Capability.FsProject(write = false))
        is Action.ApplyEdit -> setOf(Capability.FsProject(write = true))
        is Action.LspRequest -> setOf(Capability.LspRequest)
        is Action.OpenDocument -> setOf(Capability.UiStage)
        is Action.RevealStage -> if (isBuiltInStage(action.stage)) emptySet() else setOf(Capability.UiStage)
        is Action.ShowMessage -> action.actions.mapNotNull { it.action }.flatMapTo(HashSet(), ::required)
        is Action.Sequence -> action.steps.flatMapTo(HashSet(), ::required)
        // No capability of their own: executeCommand runs the target with the target's
        // grants; setConfig/toggleConfig of foreign keys is a run-time check (ui.settings).
        is Action.OpenUrl, is Action.InsertSnippet, is Action.SetConfig, is Action.ToggleConfig,
        is Action.ExecuteCommand, is Action.ShowQuickPick, is Action.ShowInputBox -> emptySet()
    }

    /** Requirements of declarative points (install steps, servers, stages, view data). */
    fun required(c: Contributions): Set<Capability> = buildSet {
        if (c.sandbox?.install?.isNotEmpty() == true) add(Capability.SandboxInstall)
        if (c.languageServers.isNotEmpty()) add(Capability.LspSpawn)
        if (c.stages.isNotEmpty() || c.viewData.isNotEmpty()) add(Capability.UiStage)
        c.viewData.forEach { addAll(required(it.from)) }
        if (usesShellPoints(c)) add(Capability.UiContribute)
        c.views.mapNotNull { it.schema }.plus(c.documents.map { it.body }).forEach { doc ->
            doc.actions.mapNotNull { (it.target as? ActionTarget.Inline)?.action }.forEach { addAll(required(it)) }
        }
    }

    /**
     * What `ui.contribute` grants: the points the shell added. A bare `activitybar` or `panel` container and a view
     * without a schema are the VS Code shapes older packs use, so they need nothing new.
     */
    fun usesShellPoints(c: Contributions): Boolean =
        c.navigation.isNotEmpty() || c.viewBadges.isNotEmpty() || c.documents.isNotEmpty() || c.documentOpeners.isNotEmpty() ||
            c.layoutPresets.isNotEmpty() || c.viewContainers.any { it.extended } || c.views.any { it.schema != null }

    /** `left`, `main`, `right`, `bottom` are the app's own areas; revealing them needs nothing. */
    fun isBuiltInStage(stage: String): Boolean = StagePlacement.parse(stage) != null

    /**
     * Capabilities whose use the validator can see statically, so declaring one unused is
     * worth a warning (R-EXT-06). The others are used by sandbox processes or WASM in ways
     * a manifest does not show.
     */
    val STATICALLY_AUDITED: Set<Capability> = setOf(
        Capability.SandboxExec, Capability.SandboxInstall, Capability.LspSpawn, Capability.LspRequest, Capability.UiStage,
        Capability.UiContribute,
    )
}
