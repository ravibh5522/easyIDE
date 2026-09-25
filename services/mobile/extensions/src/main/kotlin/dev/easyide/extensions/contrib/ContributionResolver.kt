package dev.easyide.extensions.contrib

import dev.easyide.extensions.contrib.ContributionRef.Kind
import dev.easyide.extensions.manifest.DiagnosticCode
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.SemVer

/** One contribution with its owner, identity and manifest location (for the inspector). */
data class Owned<T>(val owner: Owner, val ref: ContributionRef, val pointer: String, val value: T)

/**
 * An enabled extension as the registry sees it; list order is `EnabledSet` order (earliest installed first).
 * [uiGranted] is whether `ui.contribute` is granted: without it the shell points (navigation, documents, presets,
 * schema views and the shell's container forms) are left out, while everything an older pack contributes stays.
 */
data class RegisteredExtension(val id: ExtensionId, val version: SemVer, val contributions: Contributions, val uiGranted: Boolean = true)

/** A collision resolved by the extension-runtime.md sec 7.1 rules; logged and shown by the inspector. */
data class ContributionConflict(val ref: ContributionRef, val winner: Owner, val loser: Owner, val code: String, val message: String)

/** Every store's content at one registry version: the atomic view of the registry. */
data class ContributionSnapshot(
    val version: Long,
    val commands: List<Owned<CommandContribution>> = emptyList(),
    val menus: List<Owned<MenuItemContribution>> = emptyList(),
    val keybindings: List<Owned<KeybindingContribution>> = emptyList(),
    val configuration: List<Owned<ConfigurationProperty>> = emptyList(),
    val configurationDefaults: List<Owned<ConfigurationDefault>> = emptyList(),
    val languages: List<Owned<LanguageContribution>> = emptyList(),
    val grammars: List<Owned<GrammarContribution>> = emptyList(),
    val languageConfigurations: List<Owned<LanguageConfigurationContribution>> = emptyList(),
    val snippets: List<Owned<SnippetContribution>> = emptyList(),
    val themes: List<Owned<ThemeContribution>> = emptyList(),
    val iconThemes: List<Owned<IconThemeContribution>> = emptyList(),
    val viewContainers: List<Owned<ViewContainerContribution>> = emptyList(),
    val views: List<Owned<ViewContribution>> = emptyList(),
    val viewsWelcome: List<Owned<ViewWelcomeContribution>> = emptyList(),
    val taskDefinitions: List<Owned<TaskDefinitionContribution>> = emptyList(),
    val problemMatchers: List<Owned<ProblemMatcherContribution>> = emptyList(),
    val walkthroughs: List<Owned<WalkthroughContribution>> = emptyList(),
    val stages: List<Owned<StageContribution>> = emptyList(),
    val statusBarItems: List<Owned<StatusBarItemContribution>> = emptyList(),
    val keyRows: List<Owned<KeyRowContribution>> = emptyList(),
    val languageServers: List<Owned<LanguageServerContribution>> = emptyList(),
    val sandbox: List<Owned<SandboxContribution>> = emptyList(),
    val viewData: List<Owned<ViewDataContribution>> = emptyList(),
    val navigation: List<Owned<NavigationContribution>> = emptyList(),
    val viewBadges: List<Owned<ViewBadgeContribution>> = emptyList(),
    val documents: List<Owned<DocumentContribution>> = emptyList(),
    val documentOpeners: List<Owned<DocumentOpenerContribution>> = emptyList(),
    val layoutPresets: List<Owned<LayoutPresetContribution>> = emptyList(),
    val conflicts: List<ContributionConflict> = emptyList(),
)

/**
 * Pure merge of built-ins and enabled extensions into one [ContributionSnapshot], applying
 * the conflict table of extension-runtime.md sec 7.1. Order is the tie-break everywhere:
 * built-ins first, then extensions in `EnabledSet` order, so a new install never displaces
 * an existing winner.
 */
internal object ContributionResolver {

    fun resolve(version: Long, builtIn: Contributions, enabled: List<RegisteredExtension>): ContributionSnapshot {
        val sources = listOf<Pair<Owner, Contributions>>(Owner.BuiltIn to builtIn) + enabled.map { Owner.Ext(it.id) to if (it.uiGranted) it.contributions else it.contributions.withoutShellPoints() }
        val conflicts = ArrayList<ContributionConflict>()

        fun <T> all(kind: ContributionRef.Kind, point: String, pick: (Contributions) -> List<T>, id: (T) -> String, location: (T) -> String? = { null }) =
            sources.flatMap { (owner, c) -> pick(c).mapIndexed { i, v -> Owned(owner, ContributionRef(kind, location(v), id(v)), "$point/$i", v) } }

        /** Earlier wins; a later duplicate is dropped (or kept renamed by [rename]). */
        fun <T> firstWins(items: List<Owned<T>>, key: (Owned<T>) -> String, code: (Owned<T>) -> String = { DiagnosticCode.CONTRIBUTION_SHADOWED },
                          rename: ((Owned<T>) -> Owned<T>)? = null): List<Owned<T>> {
            val winners = LinkedHashMap<String, Owned<T>>()
            val out = ArrayList<Owned<T>>()
            for (item in items) {
                val prior = winners[key(item)]
                if (prior == null) { winners[key(item)] = item; out += item; continue }
                conflicts += ContributionConflict(item.ref, prior.owner, item.owner, code(prior), "${item.ref} from ${item.owner} is shadowed by ${prior.owner}")
                rename?.let { out += it(item) }
            }
            return out
        }

        val commands = firstWins(all(Kind.COMMAND, "/contributes/commands", { it.commands }, { it.command }), { it.value.command },
            code = { if (it.owner == Owner.BuiltIn) DiagnosticCode.COMMAND_SHADOWED else DiagnosticCode.CONTRIBUTION_SHADOWED })
        val menus = sources.flatMap { (owner, c) ->
            c.menus.groupBy { it.menuId }.flatMap { (menuId, items) ->
                items.mapIndexed { i, m -> Owned(owner, ContributionRef(Kind.MENU, menuId, m.command), "/contributes/menus/${escape(menuId)}/$i", m) }
            }
        }
        val configuration = firstWins(sources.flatMap { (owner, c) ->
            c.configuration.map { Owned(owner, ContributionRef(Kind.CONFIGURATION, null, it.key), "/contributes/configuration", it) }
        }, { it.value.key })
        val grammars = grammars(all(Kind.GRAMMAR, "/contributes/grammars", { it.grammars }, { it.scopeName }), conflicts)
        val themes = firstWins(all(Kind.THEME, "/contributes/themes", { it.themes }, { it.label }), { it.value.label }) { loser ->
            val label = "${loser.value.label} (${(loser.owner as? Owner.Ext)?.id?.publisher ?: loser.owner})"
            loser.copy(ref = loser.ref.copy(id = label), value = loser.value.copy(label = label))
        }
        val views = firstWins(sources.flatMap { (owner, c) ->
            c.views.groupBy { it.containerId }.flatMap { (container, items) ->
                items.mapIndexed { i, v -> Owned(owner, ContributionRef(Kind.VIEW, null, v.id), "/contributes/views/${escape(container)}/$i", v) }
            }
        }, { it.value.id })
        val viewOwners = views.associate { it.value.id to it.owner }

        return ContributionSnapshot(
            version = version,
            commands = commands,
            menus = menus,
            keybindings = all(Kind.KEYBINDING, "/contributes/keybindings", { it.keybindings }, { it.command }),
            configuration = configuration,
            configurationDefaults = sources.flatMap { (owner, c) ->
                c.configurationDefaults.map { Owned(owner, ContributionRef(Kind.CONFIGURATION, null, it.key), "/contributes/configurationDefaults", it) }
            },
            languages = mergeLanguages(all(Kind.LANGUAGE, "/contributes/languages", { it.languages }, { it.id })),
            grammars = grammars,
            languageConfigurations = firstWins(
                all(Kind.LANGUAGE_CONFIGURATION, "/contributes/languages", { it.languageConfigurations }, { it.language }), { it.value.language },
            ),
            snippets = all(Kind.SNIPPET, "/contributes/snippets", { it.snippets }, { it.file.path }),
            themes = themes,
            iconThemes = firstWins(all(Kind.ICON_THEME, "/contributes/iconThemes", { it.iconThemes }, { it.id }), { it.value.id }),
            viewContainers = firstWins(all(Kind.VIEW_CONTAINER, "/contributes/viewsContainers", { it.viewContainers }, { it.id }), { it.value.id }),
            views = views,
            viewsWelcome = all(Kind.VIEW_WELCOME, "/contributes/viewsWelcome", { it.viewsWelcome }, { it.view }),
            taskDefinitions = firstWins(all(Kind.TASK_DEFINITION, "/contributes/taskDefinitions", { it.taskDefinitions }, { it.type }), { it.value.type }),
            problemMatchers = firstWins(all(Kind.PROBLEM_MATCHER, "/contributes/problemMatchers", { it.problemMatchers }, { it.name }), { it.value.name }),
            // VS Code scopes walkthrough ids by extension (`publisher.name#id`), so they cannot collide.
            walkthroughs = all(Kind.WALKTHROUGH, "/contributes/walkthroughs", { it.walkthroughs }, { it.id }).map { it.copy(ref = it.ref.copy(id = "${it.owner}#${it.value.id}")) },
            stages = firstWins(all(Kind.STAGE, "/easyide/stages", { it.stages }, { it.id }), { it.value.id }),
            statusBarItems = firstWins(all(Kind.STATUS_BAR, "/easyide/statusBarItems", { it.statusBarItems }, { it.id }), { it.value.id }),
            keyRows = firstWins(all(Kind.KEY_ROW, "/easyide/keyRows", { it.keyRows }, { it.id }), { it.value.id }),
            languageServers = all(Kind.SERVER, "/easyide/languageServers", { it.languageServers }, { it.key }),
            sandbox = sources.mapNotNull { (owner, c) -> c.sandbox?.let { Owned(owner, ContributionRef(Kind.SANDBOX, null, owner.toString()), "/easyide/sandbox", it) } },
            // View data fills a view, so only the owner of the winning view may supply it.
            viewData = sources.flatMap { (owner, c) ->
                c.viewData.mapNotNull { vd ->
                    val ref = ContributionRef(Kind.VIEW_DATA, null, vd.viewId)
                    val viewOwner = viewOwners[vd.viewId]
                    if (viewOwner == owner) Owned(owner, ref, "/easyide/viewData/${escape(vd.viewId)}", vd)
                    else {
                        conflicts += ContributionConflict(ref, viewOwner ?: Owner.BuiltIn, owner, DiagnosticCode.CONTRIBUTION_SHADOWED,
                            "view data for '${vd.viewId}' ignored: the view is not contributed by $owner")
                        null
                    }
                }
            },
            navigation = firstWins(all(Kind.NAVIGATION, "/easyide/navigation", { it.navigation }, { it.id }), { it.value.id }),
            viewBadges = all(Kind.VIEW_BADGE, "/easyide/viewBadge", { it.viewBadges }, { it.nav }),
            documents = firstWins(all(Kind.DOCUMENT, "/easyide/documents", { it.documents }, { it.type }), { it.value.type }),
            documentOpeners = all(Kind.DOCUMENT_OPENER, "/easyide/documentOpeners", { it.documentOpeners }, { "${it.glob}=>${it.type}" }),
            layoutPresets = firstWins(all(Kind.LAYOUT_PRESET, "/easyide/layoutPresets", { it.layoutPresets }, { it.id }), { it.value.id }),
            conflicts = conflicts,
        )
    }

    /**
     * Extension grammars beat bundled ones for the same scope (the point of a language pack;
     * bundled stays the fallback); between extensions the earlier wins.
     */
    private fun grammars(items: List<Owned<GrammarContribution>>, conflicts: MutableList<ContributionConflict>): List<Owned<GrammarContribution>> {
        val (bundled, ext) = items.partition { it.owner == Owner.BuiltIn }
        val winners = LinkedHashMap<String, Owned<GrammarContribution>>()
        for (g in ext) {
            val prior = winners.putIfAbsent(g.value.scopeName, g)
            if (prior != null) conflicts += ContributionConflict(g.ref, prior.owner, g.owner, DiagnosticCode.CONTRIBUTION_SHADOWED, "grammar ${g.value.scopeName} from ${g.owner} is shadowed by ${prior.owner}")
        }
        val keptBundled = bundled.filter { b ->
            val over = winners[b.value.scopeName] ?: return@filter true
            conflicts += ContributionConflict(b.ref, over.owner, Owner.BuiltIn, DiagnosticCode.CONTRIBUTION_SHADOWED, "bundled grammar ${b.value.scopeName} replaced by ${over.owner}")
            false
        }
        return keptBundled + winners.values
    }

    /**
     * Languages merge instead of conflicting: extensions, filenames, patterns, aliases and
     * mimetypes are unioned; `firstLine` and `configuration` come from the earliest source
     * that has one. The merged entry is attributed to the first contributor.
     */
    private fun mergeLanguages(items: List<Owned<LanguageContribution>>): List<Owned<LanguageContribution>> =
        items.groupBy { it.value.id }.map { (_, group) ->
            val first = group.first()
            fun union(pick: (LanguageContribution) -> List<String>) = group.flatMap { pick(it.value) }.distinct()
            first.copy(value = first.value.copy(
                aliases = union { it.aliases }, extensions = union { it.extensions }, filenames = union { it.filenames },
                filenamePatterns = union { it.filenamePatterns }, mimetypes = union { it.mimetypes },
                firstLine = group.firstNotNullOfOrNull { it.value.firstLine },
                configuration = group.firstNotNullOfOrNull { it.value.configuration },
            ))
        }

    private fun escape(key: String) = key.replace("~", "~0").replace("/", "~1")
}
