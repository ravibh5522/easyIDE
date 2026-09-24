package dev.easyide.ext.cli

import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.registry.CanonicalJsonException
import dev.easyide.extensions.registry.Hex
import dev.easyide.extensions.registry.IndexEntry
import dev.easyide.extensions.registry.Jcs
import dev.easyide.extensions.registry.JdkEd25519
import dev.easyide.extensions.registry.KeyStatus
import dev.easyide.extensions.registry.PublisherKeys
import dev.easyide.extensions.registry.RegistryFormatException
import dev.easyide.extensions.registry.RegistrySigning
import dev.easyide.extensions.registry.Revocations
import dev.easyide.extensions.registry.SignatureVerifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Index repository layout (sdk-reference "Registry index format"). */
object IndexRepo {
    fun entryPath(publisher: String, name: String, version: String) = "entries/$publisher/$name/$version.json"
    fun publisherPath(publisher: String) = "publishers/$publisher.json"
    fun packagePath(publisher: String, fileName: String) = "packages/$publisher/$fileName"
    const val INDEX = "index.json"
    const val REVOCATIONS = "revocations.json"

    /** Pretty JSON for files people review in pull requests; signatures cover the canonical form, not these bytes. */
    fun write(file: File, doc: JsonElement) = file.writeBytesOrFail((pretty(doc) + "\n").toByteArray())

    fun read(file: File): JsonElement = try {
        Jcs.parse(String(file.readBytesOrFail(), Charsets.UTF_8))
    } catch (e: CanonicalJsonException) {
        throw CliFailure(CliCode.REGISTRY, "${file.path}: ${e.message}", ExitCode.VALIDATION, file.path)
    }

    private fun pretty(e: JsonElement, indent: String = ""): String = when (e) {
        is JsonObject -> if (e.isEmpty()) "{}" else e.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
            "$indent  ${JsonPrimitive(k)}: ${pretty(v, "$indent  ")}"
        }
        is JsonArray -> if (e.isEmpty() || e.all { it is JsonPrimitive }) e.toString() else e.joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${pretty(it, "$indent  ")}" }
        else -> e.toString()
    }
}

/**
 * cli.md sec 5.5: builds and signs the index entry for a package and commits it (and, without
 * `--url`, the package itself) to a branch of the index repository with the author's own git.
 * No host API is called and no token is held; the output is a branch to open a PR from.
 */
object PublishCommand : Command {
    override val name = "publish"
    override val summary = "Sign an index entry and push it to a branch of the registry index repo"
    override val usage = "publish <file.easyext> --index-repo <git url> --key <private.key> [--url <https>] [--package-base <https>] [--branch <b>] [--fork <git url>] [--register-publisher]"

    var git: (CliContext) -> GitRunner = { SystemGit() }
    var clock: Clock = Clock.systemUTC()

    override fun run(args: List<String>, ctx: CliContext): ExitCode {
        val a = Args.parse(args, setOf("index-repo", "key", "url", "package-base", "branch", "fork"), setOf("register-publisher"), 1)
        val archive = ctx.resolve(a.positional(0) ?: usage("publish needs a .easyext file"))
        val config = CliConfig.load(ctx, ctx.cwd)
        val repo = a.value("index-repo") ?: config.indexRepo ?: usage("--index-repo is required (or set indexRepo in the config)")
        val key = Keys.load(ctx, ctx.resolve(a.value("key") ?: config.key ?: usage("--key is required")))

        val v = PackageSource.withTempDir { tmp -> Validator().validateArchive(archive, tmp, strict = true) }
        if (!v.passes(strict = true) || v.descriptor == null) {
            ctx.out.diagnostics(v.diagnostics)
            throw CliFailure(CliCode.STRICT, "the package must pass validate --strict to be published", ExitCode.VALIDATION, archive.path)
        }
        val d = v.descriptor
        if (d.id.publisher != d.id.publisher.lowercase()) usage("publisher must be lower case")
        val bytes = archive.readBytesOrFail()
        val fileName = archive.name
        val url = a.value("url")?.also { if (!it.startsWith("https://")) usage("--url must be https") }
            ?: a.value("package-base")?.trimEnd('/')?.also { if (!it.startsWith("https://")) usage("--package-base must be https") }
                ?.let { "$it/${IndexRepo.packagePath(d.id.publisher, fileName)}" }
            ?: usage("give --url (where the package is hosted) or --package-base (raw URL of the index repo) to commit the package under packages/")

        val entry = RegistrySigning.signEntry(entryJson(d, url, bytes, clock.instant()), key.keyId) { JdkEd25519.sign(key.private, it) }
        val branch = a.value("branch") ?: "publish/${d.id.value}-${d.version}"
        val g = git(ctx)
        return PackageSource.withTempDir { work ->
            val dir = File(work, "index")
            g.run(work, "clone", "--depth", "1", repo, dir.path)
            val entryFile = File(dir, IndexRepo.entryPath(d.id.publisher, d.id.name, d.version.toString()))
            if (entryFile.exists()) throw CliFailure(CliCode.REGISTRY, "${d.id} ${d.version} is already in the index", ExitCode.VALIDATION)
            val pubFile = File(dir, IndexRepo.publisherPath(d.id.publisher))
            if (pubFile.exists()) {
                val keys = try { PublisherKeys.parse(IndexRepo.read(pubFile) as? JsonObject ?: error("not an object")) } catch (e: RegistryFormatException) {
                    throw CliFailure(CliCode.REGISTRY, "${pubFile.name}: ${e.message}", ExitCode.VALIDATION)
                }
                if (keys.key(key.keyId)?.status != KeyStatus.ACTIVE) {
                    throw CliFailure(CliCode.KEY_MISMATCH, "key ${key.keyId} is not an active key of publisher ${d.id.publisher}", ExitCode.VALIDATION)
                }
            } else if (a.flag("register-publisher")) {
                IndexRepo.write(pubFile, JsonObject(mapOf(
                    "publisher" to JsonPrimitive(d.id.publisher),
                    "keys" to JsonArray(listOf(JsonObject(Keys.publicJson(key) + mapOf(
                        "added" to JsonPrimitive(clock.instant().truncatedTo(ChronoUnit.DAYS).toString().substring(0, 10)),
                        "status" to JsonPrimitive("active"),
                    )))),
                    "rotation" to JsonArray(emptyList()),
                )))
            } else {
                throw CliFailure(CliCode.REGISTRY, "publisher ${d.id.publisher} is not registered; add --register-publisher for maintainers to review", ExitCode.VALIDATION)
            }
            IndexRepo.write(entryFile, entry)
            if (a.value("url") == null) File(dir, IndexRepo.packagePath(d.id.publisher, fileName)).writeBytesOrFail(bytes)
            g.run(dir, "checkout", "-b", branch)
            g.run(dir, "add", "-A")
            g.run(dir, "commit", "-m", "Add ${d.id} ${d.version}")
            val remote = a.value("fork")?.also { g.run(dir, "remote", "add", "fork", it) }?.let { "fork" } ?: "origin"
            g.run(dir, "push", remote, "HEAD:refs/heads/$branch")
            ctx.out.put("branch", branch)
            ctx.out.put("entry", IndexRepo.entryPath(d.id.publisher, d.id.name, d.version.toString()))
            ctx.out.put("sha256", Hex.sha256(bytes))
            compareUrl(a.value("fork") ?: repo, branch)?.let { ctx.out.put("pullRequest", it); ctx.out.line("open a pull request: $it") }
                ?: ctx.out.line("pushed branch $branch; open a pull request from it")
            ExitCode.OK
        }
    }

    /** The index entry for [d] minus its signature (sdk-reference `index.json` fields). */
    fun entryJson(d: ExtensionDescriptor, url: String, bytes: ByteArray, publishedAt: Instant): JsonObject {
        fun s(v: String) = JsonPrimitive(v)
        fun arr(v: Iterable<String>) = JsonArray(v.map(::s))
        return JsonObject(buildMap {
            put("id", s(d.id.value)); put("publisher", s(d.id.publisher)); put("name", s(d.id.name)); put("version", s(d.version.toString()))
            put("displayName", s(d.displayName)); d.description?.let { put("description", s(it)) }
            put("categories", arr(d.categories)); put("license", s(d.license ?: usage("license is required to publish")))
            d.memoryBudgetMb?.let { put("memoryBudgetMb", JsonPrimitive(it)) }
            put("engines", JsonObject(mapOf("easyide" to s(d.engines.raw)))); put("scope", s(d.scope.wire))
            put("layers", arr(d.layers.map { it.name }.sorted())); put("capabilities", arr(d.capabilities.items.map { it.id }.sorted()))
            put("url", s(url)); put("size", JsonPrimitive(bytes.size.toLong())); put("sha256", s(Hex.sha256(bytes)))
            put("publishedAt", s(publishedAt.truncatedTo(ChronoUnit.SECONDS).toString()))
        })
    }

    private fun compareUrl(repo: String, branch: String): String? {
        val m = Regex("""^(?:https://|git@)(github\.com|gitlab\.com|codeberg\.org)[/:]([^/]+)/([^/]+?)(?:\.git)?/?$""").find(repo) ?: return null
        val (host, owner, name) = m.destructured
        return when (host) {
            "gitlab.com" -> "https://$host/$owner/$name/-/merge_requests/new?merge_request[source_branch]=$branch"
            else -> "https://$host/$owner/$name/compare/$branch?expand=1"
        }
    }
}

/**
 * Maintainers (cli.md sec 5.5 `registry build`): regenerates `index.json` from every file under `entries/`,
 * verifying each entry against its publisher's active, unrevoked keys, then root-signs
 * `index.json`, every `publishers/<p>.json` and `revocations.json`. Run offline by whoever
 * holds the root key; commit the result.
 */
object RegistryCommand : Command {
    override val name = "registry"
    override val summary = "Maintainers: rebuild and root-sign the index (registry build)"
    override val usage = "registry build [index repo dir] --root-key <private.key> [--min-app <ver>]"

    var clock: Clock = Clock.systemUTC()

    override fun run(args: List<String>, ctx: CliContext): ExitCode {
        val a = Args.parse(args, setOf("root-key", "min-app"), emptySet(), 2)
        if (a.positional(0) != "build") usage("registry has one subcommand: build")
        val dir = ctx.resolve(a.positional(1) ?: ".")
        val root = Keys.load(ctx, ctx.resolve(a.value("root-key") ?: usage("--root-key is required")))
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val sign = { b: ByteArray -> JdkEd25519.sign(root.private, b) }

        val revFile = File(dir, IndexRepo.REVOCATIONS)
        if (!revFile.exists()) IndexRepo.write(revFile, JsonObject(mapOf(
            "schemaVersion" to JsonPrimitive(1), "updatedAt" to JsonPrimitive(now.toString()),
            "keys" to JsonArray(emptyList()), "versions" to JsonArray(emptyList()),
        )))
        val revocations = parse(revFile) { Revocations.parse(it) }
        val publishers = File(dir, "publishers").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
            .associate { f -> parse(f) { PublisherKeys.parse(it) }.let { it.publisher to (it to f) } }

        val verifier = SignatureVerifier(JdkEd25519)
        val entries = ArrayList<JsonObject>()
        val entryFiles = File(dir, "entries").walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.sortedBy { it.path }.toList()
        for (f in entryFiles) {
            val raw = IndexRepo.read(f) as? JsonObject ?: throw CliFailure(CliCode.REGISTRY, "${f.path}: not an object", ExitCode.VALIDATION, f.path)
            val e = parse(f) { IndexEntry.parse(raw) }
            val expected = IndexRepo.entryPath(e.publisher, e.id.name, e.version.toString())
            if (f.relativeTo(dir).invariantSeparatorsPath != expected) {
                throw CliFailure(CliCode.REGISTRY, "${f.path}: entry for ${e.id} ${e.version} belongs at $expected", ExitCode.VALIDATION, f.path)
            }
            val keys = publishers[e.publisher]?.first ?: throw CliFailure(CliCode.REGISTRY, "${e.id}: publisher ${e.publisher} has no publishers/ file", ExitCode.VALIDATION, f.path)
            val key = keys.key(e.signature.keyId)?.takeIf { it.status == KeyStatus.ACTIVE && revocations.keyRevoked(it.keyId) == null }
                ?: throw CliFailure(CliCode.SIGNATURE, "${e.id} ${e.version}: signed by ${e.signature.keyId}, not an active key of ${e.publisher}", ExitCode.VALIDATION, f.path)
            if (!verifier.verifyEntry(raw, key.publicKey)) throw CliFailure(CliCode.SIGNATURE, "${e.id} ${e.version}: entry signature does not verify", ExitCode.VALIDATION, f.path)
            entries += raw
        }
        val index = JsonObject(buildMap {
            put("schemaVersion", JsonPrimitive(1))
            put("generatedAt", JsonPrimitive(now.toString()))
            a.value("min-app")?.let { put("minAppVersion", JsonPrimitive(it)) }
            put("extensions", JsonArray(entries))
        })
        val indexFile = File(dir, IndexRepo.INDEX)
        IndexRepo.write(indexFile, index)
        val signed = listOf(indexFile, revFile) + publishers.values.map { it.second }
        for (f in signed) IndexRepo.write(File(f.path + ".sig"), RegistrySigning.fileSig(IndexRepo.read(f), root.keyId, sign))
        ctx.out.put("entries", entries.size)
        ctx.out.put("rootKeyId", root.keyId)
        ctx.out.put("signed", JsonArray(signed.map { JsonPrimitive(it.relativeTo(dir).invariantSeparatorsPath) }))
        ctx.out.line("index.json: ${entries.size} entries, signed by ${root.keyId}")
        return ExitCode.OK
    }

    private fun <T> parse(f: File, block: (JsonObject) -> T): T = try {
        block(IndexRepo.read(f) as? JsonObject ?: throw RegistryFormatException("not a JSON object"))
    } catch (e: RegistryFormatException) {
        throw CliFailure(CliCode.REGISTRY, "${f.path}: ${e.message}", ExitCode.VALIDATION, f.path)
    }
}
