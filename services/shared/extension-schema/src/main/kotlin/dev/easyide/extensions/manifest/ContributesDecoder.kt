package dev.easyide.extensions.manifest

import dev.easyide.extensions.contrib.CommandContribution
import dev.easyide.extensions.contrib.ConfigurationDefault
import dev.easyide.extensions.contrib.ConfigurationProperty
import dev.easyide.extensions.contrib.GrammarContribution
import dev.easyide.extensions.contrib.IconThemeContribution
import dev.easyide.extensions.contrib.KeybindingContribution
import dev.easyide.extensions.contrib.LanguageConfigurationContribution
import dev.easyide.extensions.contrib.LanguageContribution
import dev.easyide.extensions.contrib.MenuIds
import dev.easyide.extensions.contrib.MenuItemContribution
import dev.easyide.extensions.contrib.ProblemMatcherContribution
import dev.easyide.extensions.contrib.SettingScopeName
import dev.easyide.extensions.contrib.SnippetContribution
import dev.easyide.extensions.contrib.TaskDefinitionContribution
import dev.easyide.extensions.contrib.ThemeContribution
import dev.easyide.extensions.contrib.UiTheme
import dev.easyide.extensions.contrib.ContainerPlacement
import dev.easyide.extensions.contrib.UiScope
import dev.easyide.extensions.contrib.ViewContainerContribution
import dev.easyide.extensions.contrib.ViewContainerLocation
import dev.easyide.extensions.contrib.ViewContribution
import dev.easyide.extensions.contrib.ViewWelcomeContribution
import dev.easyide.extensions.contrib.WalkthroughContribution
import dev.easyide.extensions.contrib.WalkthroughStep
import dev.easyide.extensions.json.JsonPointer
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.schema.SchemaValidator
import dev.easyide.extensions.view.ViewLimits
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Decodes the VS Code-shaped `contributes` block (sdk-reference "Contribution points"). */
internal class ContributesDecoder(private val ctx: DecodeContext, private val views: ViewSchemaDecoder) {
    private val base = "/contributes"

    fun commands(c: JsonObject): List<CommandContribution> {
        val raw = c["commands"]
        return raw.objectList().mapIndexed { i, o ->
            val p = listPointer(raw, "$base/commands", i)
            val id = o.reqStr("command")
            ctx.checkPrefix(id, JsonPointer.child(p, "command"), "command")
            CommandContribution(
                id, o.reqStr("title"), o.str("category"), o.str("shortTitle"),
                o.str("icon")?.let { ctx.icon(it, JsonPointer.child(p, "icon")) }, ctx.whenAt(o, "enablement", p),
            )
        }.also { list -> ctx.unique(list.mapIndexed { i, x -> listPointer(raw, "$base/commands", i) to x.command }, "command") }
    }

    fun menus(c: JsonObject): List<MenuItemContribution> {
        val menus = c.obj("menus") ?: return emptyList()
        return menus.flatMap { (menuId, items) ->
            val mp = JsonPointer.child("$base/menus", menuId)
            if (menuId !in MenuIds.ALL) ctx.warn(DiagnosticCode.MENU_UNKNOWN, mp, "unknown menu id '$menuId' (ignored by this version)")
            (items as JsonArray).mapIndexed { i, e ->
                val o = e as JsonObject
                val p = JsonPointer.index(mp, i)
                val group = o.str("group")
                val at = group?.lastIndexOf('@') ?: -1
                MenuItemContribution(
                    menuId, o.reqStr("command"), o.str("alt"), ctx.whenAt(o, "when", p),
                    if (at >= 0) group!!.substring(0, at) else group,
                    if (at >= 0) group!!.substring(at + 1).toDoubleOrNull() else null,
                )
            }
        }
    }

    fun keybindings(c: JsonObject): List<KeybindingContribution> {
        val raw = c["keybindings"]
        return raw.objectList().mapIndexed { i, o ->
            val p = listPointer(raw, "$base/keybindings", i)
            KeybindingContribution(o.reqStr("command"), o.reqStr("key"), o.str("mac"), o.str("linux"), ctx.whenAt(o, "when", p), o["args"])
        }
    }

    fun configuration(c: JsonObject): List<ConfigurationProperty> {
        val raw = c["configuration"]
        val out = ArrayList<ConfigurationProperty>()
        raw.objectList().forEachIndexed { i, section ->
            val sp = listPointer(raw, "$base/configuration", i)
            section.obj("properties")?.forEach { (key, value) ->
                val p = JsonPointer.child(JsonPointer.child(sp, "properties"), key)
                ctx.checkPrefix(key, p, "setting")
                out += property(key, value as JsonObject, section, p)
            }
        }
        ctx.unique(out.map { "$base/configuration" to it.key }, "setting")
        return out
    }

    private fun property(key: String, o: JsonObject, section: JsonObject, p: String): ConfigurationProperty {
        SchemaValidator.unsupportedKeywords(o).filterNot { it.substringAfterLast('/') in PRESENTATION_FIELDS }.forEach {
            ctx.warn(DiagnosticCode.CONTENT_IGNORED, p + it, "schema keyword not validated by easyIDE")
        }
        var default = o["default"]
        if (default != null) {
            val problems = ctx.schema.forSubschema(o).validate(default, JsonPointer.child(p, "default")).filter { it.severity == Severity.ERROR }
            if (problems.isNotEmpty()) {
                ctx.warn(DiagnosticCode.CONTENT_IGNORED, JsonPointer.child(p, "default"), "default does not match its own schema; registered without a default")
                default = null
            }
        }
        return ConfigurationProperty(
            key, section.str("title"), section.int("order"), o, default,
            o.str("scope")?.let(SettingScopeName::parse), o.int("order"),
        )
    }

    fun configurationDefaults(c: JsonObject): List<ConfigurationDefault> {
        val defaults = c.obj("configurationDefaults") ?: return emptyList()
        return defaults.flatMap { (key, value) ->
            val langs = LANG_BLOCK.findAll(key).map { it.groupValues[1] }.toList()
            if (langs.isNotEmpty() && LANG_BLOCK.replace(key, "").isEmpty()) {
                val block = value as? JsonObject ?: return@flatMap emptyList()
                langs.flatMap { lang -> block.map { (k, v) -> ConfigurationDefault(k, lang, v) } }
            } else listOf(ConfigurationDefault(key, null, value))
        }
    }

    fun languages(c: JsonObject): Pair<List<LanguageContribution>, List<LanguageConfigurationContribution>> {
        val configs = ArrayList<LanguageConfigurationContribution>()
        val langs = c.objs("languages").mapIndexed { i, o ->
            val p = JsonPointer.index("$base/languages", i)
            val id = o.reqStr("id")
            val firstLine = o.str("firstLine")?.also { ctx.regex(it, JsonPointer.child(p, "firstLine")) }
            val config = ctx.fileAt(o, "configuration", p)
            if (config != null) configs += LanguageConfigurationContribution(id, config)
            LanguageContribution(
                id, o.strs("aliases"), o.strs("extensions"), o.strs("filenames"), o.strs("filenamePatterns"),
                firstLine, o.strs("mimetypes"), config,
            )
        }
        ctx.unique(langs.mapIndexed { i, l -> JsonPointer.index("$base/languages", i) to l.id }, "language")
        return langs to configs
    }

    fun grammars(c: JsonObject): List<GrammarContribution> = c.objs("grammars").mapIndexedNotNull { i, o ->
        val p = JsonPointer.index("$base/grammars", i)
        val file = ctx.fileAt(o, "path", p) ?: return@mapIndexedNotNull null
        GrammarContribution(
            o.str("language"), o.reqStr("scopeName"), file, stringMap(o.obj("embeddedLanguages")),
            o.strs("injectTo"), stringMap(o.obj("tokenTypes")),
        )
    }.also { list -> ctx.unique(list.map { "$base/grammars" to it.scopeName }, "grammar scopeName") }

    fun snippets(c: JsonObject): List<SnippetContribution> = c.objs("snippets").mapIndexedNotNull { i, o ->
        val file = ctx.fileAt(o, "path", JsonPointer.index("$base/snippets", i)) ?: return@mapIndexedNotNull null
        SnippetContribution(o.str("language"), file)
    }

    fun themes(c: JsonObject): List<ThemeContribution> = c.objs("themes").mapIndexedNotNull { i, o ->
        val file = ctx.fileAt(o, "path", JsonPointer.index("$base/themes", i)) ?: return@mapIndexedNotNull null
        ThemeContribution(o.str("id"), o.reqStr("label"), UiTheme.parse(o.reqStr("uiTheme"))!!, file)
    }.also { list -> ctx.unique(list.map { "$base/themes" to it.label }, "theme label") }

    fun iconThemes(c: JsonObject): List<IconThemeContribution> = c.objs("iconThemes").mapIndexedNotNull { i, o ->
        val file = ctx.fileAt(o, "path", JsonPointer.index("$base/iconThemes", i)) ?: return@mapIndexedNotNull null
        IconThemeContribution(o.reqStr("id"), o.reqStr("label"), file)
    }.also { list -> ctx.unique(list.map { "$base/iconThemes" to it.id }, "icon theme") }

    fun viewContainers(c: JsonObject): List<ViewContainerContribution> {
        val vc = c.obj("viewsContainers") ?: return emptyList()
        val out = ArrayList<ViewContainerContribution>()
        for (location in ViewContainerLocation.entries) {
            vc.objs(location.wire).forEachIndexed { i, o ->
                val p = JsonPointer.index(JsonPointer.child("$base/viewsContainers", location.wire), i)
                container(location, o, p)?.let { out += it }
            }
        }
        ctx.unique(out.map { "$base/viewsContainers" to it.id }, "view container")
        val extended = out.count { it.extended }
        if (extended > ViewLimits.MAX_CONTAINERS_PER_PACK) {
            ctx.error(DiagnosticCode.UI_LIMIT, "$base/viewsContainers", "$extended containers; at most ${ViewLimits.MAX_CONTAINERS_PER_PACK} per pack")
        }
        return out
    }

    /**
     * A bare `activitybar` or `panel` entry is the VS Code shape and keeps its old rules. `sidebar`,
     * `secondarySidebar`, `scope` and `locations` are the shell's: they need `ui.contribute`, a `<publisher>.<name>.`
     * id, a short title and a tintable icon.
     */
    private fun container(location: ViewContainerLocation, o: JsonObject, p: String): ViewContainerContribution? {
        val extended = location == ViewContainerLocation.SIDEBAR || location == ViewContainerLocation.SECONDARY_SIDEBAR ||
            "scope" in o || "locations" in o
        val id = o.reqStr("id")
        val title = o.reqStr("title")
        val iconPointer = JsonPointer.child(p, "icon")
        if (!extended) return ctx.icon(o.reqStr("icon"), iconPointer)?.let { ViewContainerContribution(location, id, title, it) }
        var ok = ctx.uiPrefix(id, JsonPointer.child(p, "id"), "view container")
        if (title.length > ViewLimits.TITLE_MAX) {
            ctx.error(DiagnosticCode.UI_TITLE, JsonPointer.child(p, "title"), "longer than ${ViewLimits.TITLE_MAX} characters")
            ok = false
        }
        val icon = ctx.uiIcon(o.reqStr("icon"), iconPointer)
        val scope = o.str("scope")?.let(UiScope::parse) ?: UiScope.WORKSPACE
        val locations = o.strs("locations").mapNotNull(ContainerPlacement::parse)
        return if (icon != null && ok) ViewContainerContribution(location, id, title, icon, scope, locations, extended = true) else null
    }

    fun views(c: JsonObject): List<ViewContribution> {
        val views = c.obj("views") ?: return emptyList()
        val out = ArrayList<Pair<String, ViewContribution>>()
        for ((container, items) in views) {
            (items as JsonArray).forEachIndexed { i, e ->
                val o = e as JsonObject
                val p = JsonPointer.index(JsonPointer.child("$base/views", container), i)
                ctx.checkPrefix(o.reqStr("id"), JsonPointer.child(p, "id"), "view")
                val schema = ctx.fileAt(o, "schema", p)?.let { this.views.decode(it) }
                out += p to ViewContribution(container, o.reqStr("id"), o.reqStr("name"), ctx.whenAt(o, "when", p), schema)
            }
        }
        ctx.unique(out.map { (p, v) -> p to v.id }, "view")
        val schemaViews = out.count { it.second.schema != null }
        if (schemaViews > ViewLimits.MAX_VIEWS_PER_PACK) {
            ctx.error(DiagnosticCode.UI_LIMIT, "$base/views", "$schemaViews schema views; at most ${ViewLimits.MAX_VIEWS_PER_PACK} per pack")
        }
        return out.map { it.second }
    }

    fun viewsWelcome(c: JsonObject): List<ViewWelcomeContribution> = c.objs("viewsWelcome").mapIndexed { i, o ->
        ViewWelcomeContribution(o.reqStr("view"), o.reqStr("contents"), ctx.whenAt(o, "when", JsonPointer.index("$base/viewsWelcome", i)))
    }

    fun taskDefinitions(c: JsonObject): List<TaskDefinitionContribution> = c.objs("taskDefinitions").mapIndexed { i, o ->
        TaskDefinitionContribution(
            o.reqStr("type"), o.strs("required"), o.obj("properties") ?: JsonObject(emptyMap()),
            ctx.whenAt(o, "when", JsonPointer.index("$base/taskDefinitions", i)),
        )
    }.also { list -> ctx.unique(list.map { "$base/taskDefinitions" to it.type }, "task type") }

    fun problemMatchers(c: JsonObject): List<ProblemMatcherContribution> = c.objs("problemMatchers").mapIndexed { i, o ->
        val p = JsonPointer.index("$base/problemMatchers", i)
        patterns(o["pattern"]).forEach { (rel, re) -> ctx.regex(re, JsonPointer.child(p, "pattern") + rel) }
        ProblemMatcherContribution(o.reqStr("name"), o)
    }.also { list -> ctx.unique(list.map { "$base/problemMatchers" to it.name }, "problem matcher") }

    /** `regexp` strings of a matcher pattern (one object or an array; a string names another matcher's pattern). */
    private fun patterns(e: JsonElement?): List<Pair<String, String>> = when (e) {
        is JsonObject -> listOfNotNull(e.str("regexp")?.let { "/regexp" to it })
        is JsonArray -> e.mapIndexedNotNull { i, x -> (x as? JsonObject)?.str("regexp")?.let { "/$i/regexp" to it } }
        else -> emptyList()
    }

    fun walkthroughs(c: JsonObject): List<WalkthroughContribution> = c.objs("walkthroughs").mapIndexed { i, o ->
        val p = JsonPointer.index("$base/walkthroughs", i)
        val steps = o.objs("steps").mapIndexed { j, s -> step(s, JsonPointer.index(JsonPointer.child(p, "steps"), j)) }
        ctx.unique(steps.mapIndexed { j, s -> JsonPointer.index(JsonPointer.child(p, "steps"), j) to s.id }, "walkthrough step")
        WalkthroughContribution(o.reqStr("id"), o.reqStr("title"), o.reqStr("description"), ctx.whenAt(o, "when", p), steps)
    }.also { list -> ctx.unique(list.map { "$base/walkthroughs" to it.id }, "walkthrough") }

    private fun step(o: JsonObject, p: String): WalkthroughStep {
        val media = o.obj("media")!!
        val mp = JsonPointer.child(p, "media")
        if ("video" in media) ctx.warn(DiagnosticCode.CONTENT_IGNORED, JsonPointer.child(mp, "video"), "video media is not supported and is ignored")
        val image = when (val img = media["image"]) {
            is JsonObject -> img.entries.map { (k, v) -> ctx.file(v.stringOrNull.orEmpty(), JsonPointer.child(JsonPointer.child(mp, "image"), k)) }
                .let { files -> files.firstOrNull { it != null } }
            else -> img?.stringOrNull?.let { ctx.file(it, JsonPointer.child(mp, "image")) }
        }
        return WalkthroughStep(
            o.reqStr("id"), o.reqStr("title"), o.str("description"), ctx.whenAt(o, "when", p),
            image, ctx.fileAt(media, "markdown", mp), ctx.fileAt(media, "svg", mp), o.strs("completionEvents"),
        )
    }

    private fun stringMap(o: JsonObject?): Map<String, String> = o?.mapNotNull { (k, v) -> v.stringOrNull?.let { k to it } }?.toMap() ?: emptyMap()

    companion object {
        private val LANG_BLOCK = Regex("""\[([^\]]+)]""")

        /** VS Code UI fields on a configuration property; not JSON Schema keywords, not warned about. */
        private val PRESENTATION_FIELDS = setOf(
            "scope", "markdownDescription", "deprecationMessage", "markdownDeprecationMessage", "enumDescriptions",
            "markdownEnumDescriptions", "order", "tags", "editPresentation", "ignoreSync", "included",
        )
    }
}
