package dev.easyide.extensions.manifest

import dev.easyide.extensions.AppApi
import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.InputSpec
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.contrib.PackageFile
import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonPointer
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.schema.SchemaValidator
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

sealed interface ParseResult {
    val warnings: List<Diagnostic>

    data class Ok(val descriptor: ExtensionDescriptor, override val warnings: List<Diagnostic>) : ParseResult
    data class Invalid(val errors: List<Diagnostic>, override val warnings: List<Diagnostic>) : ParseResult
}

/**
 * [apiVersion] is what `engines.easyide` must contain (the CLI's `--engine`); [builtInCommands]
 * lets references to app commands resolve (unknown ones only warn).
 */
data class ParseOptions(
    val locale: Locale = Locale.ROOT,
    val apiVersion: SemVer = AppApi.VERSION,
    val limits: PackageLimits = PackageLimits.DEFAULT,
    val builtInCommands: Set<String> = emptySet(),
)

/**
 * Turns a package into an [ExtensionDescriptor] or diagnostics (extension-runtime.md sec 2.1).
 * Each phase runs only if earlier phases produced no error; warnings accumulate. Stateless
 * and synchronous; callers run it off the main thread (it reads files).
 */
class ManifestParser(private val schema: SchemaValidator, private val options: ParseOptions = ParseOptions()) {

    fun parse(files: PackageFiles): ParseResult {
        val diags = ArrayList<Diagnostic>()
        fun fail() = ParseResult.Invalid(diags.filter { it.severity == Severity.ERROR }, diags.filter { it.severity == Severity.WARNING })

        // 1. Read
        val text = read(files, diags) ?: return fail()
        // 2. Parse (strict JSON: the manifest must stay valid for VS Code tooling)
        val json = when (val r = JsonText.parseStrict(text)) {
            is JsonParse.Ok -> r.value
            is JsonParse.Error -> {
                diags += Diagnostic.error(DiagnosticCode.JSON_SYNTAX, "", "line ${r.line}, column ${r.column}: ${r.message}")
                return fail()
            }
        }
        // 3. NLS
        val localized = Nls.load(files, options.locale, diags).apply(json, diags)
        // 4. Schema
        diags += schema.validate(localized)
        if (diags.hasErrors()) return fail()
        val root = localized as JsonObject

        // 5. Identity
        val name = root.reqStr("name")
        val id = ExtensionId.of(root.reqStr("publisher"), name)!!
        val version = SemVer.parse(root.reqStr("version"))
        if (version == null) diags += Diagnostic.error(DiagnosticCode.VERSION, "/version", "not a SemVer 2.0 version without build metadata")
        val engineText = root.obj("engines")!!.reqStr("easyide")
        val engines = SemVerRange.parse(engineText)
        when {
            engines == null -> diags += Diagnostic.error(DiagnosticCode.ENGINE_RANGE, "/engines/easyide", "'$engineText' is not a semver range")
            !engines.contains(options.apiVersion) -> diags += Diagnostic.error(
                DiagnosticCode.ENGINE_MISMATCH, "/engines/easyide",
                "requires easyIDE API $engineText; this app provides ${options.apiVersion}",
            )
        }
        if (diags.hasErrors()) return fail()

        // 6-8, 11. Files, cross-references, when-clauses and content, while decoding
        val ctx = DecodeContext(files, VALUE_SCHEMA, name, id)
        val decoded = decode(ctx, root)
        ManifestChecks.crossReferences(ctx, decoded.contributions, decoded.actions, decoded.inputs, options.builtInCommands)
        ManifestChecks.uiReferences(ctx, decoded.contributions)
        ManifestChecks.variableReferences(ctx, decoded.actions, decoded.inputs)
        val wasm = decoded.wasm
        if (wasm != null && files.size(wasm.module.path) > options.limits.wasmModuleBytes) {
            ctx.error(DiagnosticCode.WASM_TOO_LARGE, "/easyide/wasm/module", "module exceeds ${options.limits.wasmModuleBytes} bytes")
        }
        // 9. Capability audit, 10. Scope
        val easyide = root.obj("easyide") ?: JsonObject(emptyMap())
        val caps = ManifestChecks.capabilities(ctx, easyide.strs("capabilities"), decoded.contributions, decoded.actions, wasm != null)
        val scope = ManifestChecks.scope(ctx, easyide.str("scope")?.let(InstallScope::parse), decoded.contributions, decoded.actions, caps)
        if (!ctx.diagnostics.hasErrors()) ContentChecks(ctx).run(decoded.contributions)
        // 12. Unknown / tolerated
        val events = activationEvents(ctx, root)
        root.obj("engines")?.get("vscode")?.let {
            ctx.warn(DiagnosticCode.ENGINES_VSCODE, "/engines/vscode", "engines.vscode is ignored by easyIDE")
        }
        unreferenced(ctx)
        diags += ctx.diagnostics
        if (diags.hasErrors()) return fail()

        val descriptor = ExtensionDescriptor(
            id = id, version = version!!, displayName = root.str("displayName") ?: name, description = root.str("description"),
            license = root.str("license"), engines = engines!!, categories = root.strs("categories"), scope = scope,
            layers = layers(decoded.contributions, decoded.actions.isNotEmpty(), wasm != null), capabilities = caps,
            activationEvents = events, contributes = decoded.contributions, actions = decoded.actions, inputs = decoded.inputs,
            wasm = wasm, memoryBudgetMb = easyide.int("memoryBudgetMb"), icon = decoded.icon, root = files.root,
        )
        return ParseResult.Ok(descriptor, diags.filter { it.severity == Severity.WARNING })
    }

    private class Decoded(
        val contributions: Contributions, val actions: Map<String, Action>,
        val inputs: Map<String, InputSpec>, val wasm: WasmSpec?, val icon: PackageFile?,
    )

    private fun decode(ctx: DecodeContext, root: JsonObject): Decoded {
        val c = root.obj("contributes") ?: JsonObject(emptyMap())
        val e = root.obj("easyide") ?: JsonObject(emptyMap())
        val vd = ViewSchemaDecoder(ctx)
        val cd = ContributesDecoder(ctx, vd)
        val ad = ActionDecoder(ctx)
        val ed = EasyideDecoder(ctx, ad)
        val ud = UiDecoder(ctx, vd)
        val (languages, languageConfigs) = cd.languages(c)
        val containers = cd.viewContainers(c)
        val contributions = Contributions(
            commands = cd.commands(c), menus = cd.menus(c), keybindings = cd.keybindings(c),
            configuration = cd.configuration(c), configurationDefaults = cd.configurationDefaults(c),
            languages = languages, grammars = cd.grammars(c), languageConfigurations = languageConfigs,
            snippets = cd.snippets(c), themes = cd.themes(c), iconThemes = cd.iconThemes(c),
            viewContainers = containers, views = cd.views(c), viewsWelcome = cd.viewsWelcome(c),
            taskDefinitions = cd.taskDefinitions(c), problemMatchers = cd.problemMatchers(c), walkthroughs = cd.walkthroughs(c),
            stages = ed.stages(e), statusBarItems = ed.statusBarItems(e), keyRows = ed.keyRows(e),
            languageServers = ed.languageServers(e), sandbox = ed.sandbox(e), viewData = ed.viewData(e),
            navigation = ud.navigation(e, containers), viewBadges = ud.viewBadges(e), documents = ud.documents(e),
            documentOpeners = ud.documentOpeners(e), layoutPresets = ud.layoutPresets(e),
        )
        val actions = LinkedHashMap<String, Action>()
        e.obj("actions")?.forEach { (cmd, v) -> ad.action(v as JsonObject, JsonPointer.child("/easyide/actions", cmd))?.let { actions[cmd] = it } }
        val wasm = e.obj("wasm")?.let { w ->
            ctx.fileAt(w, "module", "/easyide/wasm")?.let { module ->
                WasmSpec(module, w.int("abi")!!, w.int("memoryMb"), w.objs("providers").map { WasmProvider(it.reqStr("kind"), it.strs("languages")) })
            }
        }
        val icon = root.str("icon")?.let { ctx.file(it, "/icon") }
        return Decoded(contributions, actions, ad.inputs(e.arr("inputs"), "/easyide/inputs"), wasm, icon)
    }

    private fun activationEvents(ctx: DecodeContext, root: JsonObject): List<ActivationEvent> =
        root.strs("activationEvents").mapIndexedNotNull { i, raw ->
            val p = JsonPointer.index("/activationEvents", i)
            if (raw == "*") ctx.warn(DiagnosticCode.ACTIVATION_STAR, p, "'*' is treated as onStartupFinished")
            ActivationEvent.parse(raw) ?: run {
                ctx.warn(DiagnosticCode.ACTIVATION_EVENT, p, "unknown activation event '$raw' (ignored)")
                null
            }
        }.distinct()

    /** Phase 6, second half: files nothing points at are probably a packaging mistake. */
    private fun unreferenced(ctx: DecodeContext) {
        for (path in ctx.files.list()) {
            if (path in ctx.referenced || isConventional(path)) continue
            ctx.diagnostics += Diagnostic.warning(DiagnosticCode.FILE_UNREFERENCED, "", "not referenced from package.json", path)
        }
    }

    private fun isConventional(path: String): Boolean {
        val lower = path.lowercase()
        return lower == MANIFEST_FILE || lower.startsWith("l10n/") || lower.startsWith("test/") ||
            lower.startsWith("media/") || lower.startsWith("bin/") || CONVENTIONAL.any { lower == it || lower.startsWith("$it.") } ||
            lower.startsWith("package.nls")
    }

    private fun layers(c: Contributions, hasActions: Boolean, hasWasm: Boolean): Set<Layer> = buildSet {
        if (c != Contributions.EMPTY || hasActions || !hasWasm) add(Layer.L1)
        if (hasWasm) add(Layer.L2)
    }

    /** I/O boundary: missing, oversized or non-UTF-8 manifests become diagnostics. */
    private fun read(files: PackageFiles, diags: MutableList<Diagnostic>): String? {
        if (!files.exists(MANIFEST_FILE)) {
            diags += Diagnostic.error(DiagnosticCode.MANIFEST_MISSING, "", "package.json not found")
            return null
        }
        val bytes = try { files.read(MANIFEST_FILE) } catch (e: IOException) {
            diags += Diagnostic.error(DiagnosticCode.PACKAGE_IO, "", "cannot read package.json: ${e.message}")
            return null
        }
        if (bytes.size > options.limits.fileBytes) {
            diags += Diagnostic.error(DiagnosticCode.PACKAGE_FILE_TOO_LARGE, "", "package.json exceeds ${options.limits.fileBytes} bytes")
            return null
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try { decoder.decode(ByteBuffer.wrap(bytes)).toString() } catch (e: CharacterCodingException) {
            diags += Diagnostic.error(DiagnosticCode.MANIFEST_ENCODING, "", "package.json is not valid UTF-8")
            return null
        }
        return text.removePrefix("\ufeff")
    }

    companion object {
        /** Files the package layout names without a manifest reference. */
        private val CONVENTIONAL = listOf("readme", "readme.md", "license", "license.md", "license.txt", "changelog.md", "icon.png", "notice", "notice.md")

        /** Validator for contributed setting schemas: unknown properties are errors there. */
        private val VALUE_SCHEMA = SchemaValidator.create(JsonObject(emptyMap()), MANIFEST_FILE, Severity.ERROR)
    }
}
