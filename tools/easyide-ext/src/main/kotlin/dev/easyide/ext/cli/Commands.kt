package dev.easyide.ext.cli

import dev.easyide.extensions.AppApi
import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.SemVer
import dev.easyide.extensions.registry.Hex
import dev.easyide.extensions.registry.JdkEd25519
import dev.easyide.extensions.registry.Sig
import dev.easyide.extensions.registry.SignatureVerifier
import dev.easyide.extensions.registry.SignedBytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

interface Command {
    val name: String
    val summary: String
    val usage: String
    fun run(args: List<String>, ctx: CliContext): ExitCode
}

/** Resolves `--engine` (flag, then config, then the API version this CLI was built against). */
private fun engine(flag: String?, config: CliConfig): SemVer {
    val text = flag ?: config.engine ?: return AppApi.VERSION
    return SemVer.parse(text) ?: usage("--engine '$text' is not a SemVer version")
}

private fun reportValidation(ctx: CliContext, v: Validation, strict: Boolean): ExitCode {
    ctx.out.diagnostics(v.diagnostics)
    v.descriptor?.let { d ->
        ctx.out.put("id", d.id.toString())
        ctx.out.put("version", d.version.toString())
        ctx.out.put("scope", d.scope.wire)
        ctx.out.put("layers", JsonArray(d.layers.sorted().map { JsonPrimitive(it.name) }))
        ctx.out.put("capabilities", JsonArray(d.capabilities.items.map { it.id }.sorted().map { JsonPrimitive(it) }))
    }
    ctx.out.put("errors", v.errors)
    ctx.out.put("warnings", v.warnings)
    val ok = v.passes(strict)
    if (ok) ctx.out.line("${v.descriptor?.id ?: "package"} is valid (${v.warnings} warning(s))")
    else if (v.errors == 0) ctx.out.line("--strict: ${v.warnings} warning(s) fail validation")
    else ctx.out.line("${v.errors} error(s), ${v.warnings} warning(s)")
    return if (ok) ExitCode.OK else ExitCode.VALIDATION
}

object ValidateCommand : Command {
    override val name = "validate"
    override val summary = "Check a folder or .easyext exactly as the app would, plus author checks"
    override val usage = "validate [dir or file.easyext] [--strict] [--engine <ver>]"

    override fun run(args: List<String>, ctx: CliContext): ExitCode {
        val a = Args.parse(args, setOf("engine"), setOf("strict"), 1)
        val target = ctx.resolve(a.positional(0) ?: ".")
        if (!target.exists()) ioFailure("not found: ${target.path}", target.path)
        val configDir = if (target.isDirectory) target else ctx.cwd
        val validator = Validator(engine(a.value("engine"), CliConfig.load(ctx, configDir)))
        val strict = a.flag("strict")
        return if (target.isDirectory) {
            reportValidation(ctx, validator.validateFolder(target, strict), strict)
        } else {
            PackageSource.withTempDir { tmp -> reportValidation(ctx, validator.validateArchive(target, tmp, strict), strict) }
        }
    }
}

object PackageCommand : Command {
    override val name = "package"
    override val summary = "Validate, then write a deterministic .easyext and print its sha256"
    override val usage = "package [dir] [--out <file or dir>] [--engine <ver>]"

    override fun run(args: List<String>, ctx: CliContext): ExitCode {
        val a = Args.parse(args, setOf("out", "engine"), emptySet(), 1)
        val dir = ctx.resolve(a.positional(0) ?: ".")
        if (!dir.isDirectory) ioFailure("not a directory: ${dir.path}", dir.path)
        val config = CliConfig.load(ctx, dir)
        val v = Validator(engine(a.value("engine"), config)).validateFolder(dir, strict = false)
        val d = v.descriptor
        if (d == null || v.errors > 0) return reportValidation(ctx, v, strict = false)
        ctx.out.diagnostics(v.diagnostics)
        val bytes = Packager.zip(v.files!!)
        val fileName = Packager.fileName(d.id.publisher, d.id.name, d.version.toString())
        val out = (a.value("out") ?: config.packageOut)?.let { ctx.resolve(it) }?.let { if (it.isDirectory || it.path.endsWith("/")) File(it, fileName) else it }
            ?: File(dir, "dist/$fileName")
        out.writeBytesOrFail(bytes)
        val sha = Hex.sha256(bytes)
        ctx.out.put("file", out.path)
        ctx.out.put("size", bytes.size)
        ctx.out.put("sha256", sha)
        ctx.out.line("wrote ${out.path}")
        ctx.out.line("size   ${bytes.size}")
        ctx.out.line("sha256 $sha")
        return ExitCode.OK
    }
}

object KeygenCommand : Command {
    override val name = "keygen"
    override val summary = "Create an Ed25519 publisher key pair (optionally a rotation record)"
    override val usage = "keygen [--publisher <p>] [--out <dir>] [--rotate --from <old.key>] [--no-passphrase]"

    override fun run(args: List<String>, ctx: CliContext): ExitCode {
        val a = Args.parse(args, setOf("publisher", "out", "from"), setOf("rotate", "no-passphrase"), 0)
        val config = CliConfig.load(ctx, ctx.cwd)
        val publisher = a.value("publisher") ?: config.publisher ?: usage("--publisher is required (or set it in ~/.easyide/config.json)")
        if (!ExtensionId.SEGMENT.matches(publisher)) usage("publisher '$publisher' must match ^${ExtensionId.SEGMENT.pattern}$")
        if (a.flag("rotate") != (a.value("from") != null)) usage("--rotate and --from <old.key> go together")
        val old = a.value("from")?.let { Keys.load(ctx, ctx.resolve(it)) }
        val dir = a.value("out")?.let { ctx.resolve(it) } ?: Keys.defaultDir(ctx)

        val pair = JdkEd25519.generate()
        val key = PublisherKey(pair.private, pair.public)
        val passphrase = if (a.flag("no-passphrase")) null else newPassphrase(ctx)
        val keyFile = File(dir, "$publisher-${key.keyId}.key")
        if (keyFile.exists()) throw CliFailure(CliCode.EXISTS, "refusing to overwrite ${keyFile.path}", ExitCode.VALIDATION, keyFile.path)
        Keys.writePrivate(ctx, dir, keyFile, Keys.privatePem(pair.private, passphrase))
        val pubFile = File(dir, "$publisher-${key.keyId}.pub.json")
        pubFile.writeBytesOrFail((Keys.publicJson(key).toString() + "\n").toByteArray())
        ctx.out.put("keyId", key.keyId)
        ctx.out.put("privateKey", keyFile.path)
        ctx.out.put("publicKey", pubFile.path)
        ctx.out.put("encrypted", passphrase != null)
        ctx.out.line("keyId  ${key.keyId}")
        ctx.out.line("private ${keyFile.path}${if (passphrase == null) " (not encrypted)" else ""}")
        ctx.out.line("public  ${pubFile.path}")

        if (old != null) {
            val sigByOld = JdkEd25519.sign(old.private, SignedBytes.rotation(publisher, old.keyId, key.keyId))
            val record = JsonObject(
                mapOf(
                    "from" to JsonPrimitive(old.keyId), "to" to JsonPrimitive(key.keyId),
                    "sigByOld" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(sigByOld)),
                ),
            )
            val rotFile = File(dir, "$publisher-${key.keyId}.rotation.json")
            rotFile.writeBytesOrFail((record.toString() + "\n").toByteArray())
            ctx.out.put("rotation", rotFile.path)
            ctx.out.line("rotation ${rotFile.path} (add to publishers/$publisher.json \"rotation\")")
        }
        ctx.out.line("Back the private key up offline. Losing it means a key rotation needs the registry maintainers.")
        return ExitCode.OK
    }

    private fun newPassphrase(ctx: CliContext): CharArray? {
        ctx.env[Keys.PASSPHRASE_ENV]?.let { return it.toCharArray() }
        val first = ctx.readSecret("Passphrase (empty for none): ") ?: return null
        if (first.isEmpty()) return null
        val again = ctx.readSecret("Repeat passphrase: ")
        if (again == null || !again.contentEquals(first)) usage("passphrases do not match")
        return first
    }
}

object SignCommand : Command {
    override val name = "sign"
    override val summary = "Write a detached <file>.sig over the exact package bytes"
    override val usage = "sign <file.easyext> [--key <private.key>] [--yes]"

    override fun run(args: List<String>, ctx: CliContext): ExitCode {
        val a = Args.parse(args, setOf("key"), setOf("yes"), 1)
        val file = ctx.resolve(a.positional(0) ?: usage("sign needs a .easyext file"))
        val config = CliConfig.load(ctx, ctx.cwd)
        val keyPath = a.value("key") ?: config.key ?: usage("--key is required (or set \"key\" in the config)")
        val key = Keys.load(ctx, ctx.resolve(keyPath))
        if (config.keyId != null && config.keyId != key.keyId && !a.flag("yes")) {
            throw CliFailure(CliCode.KEY_MISMATCH, "key ${key.keyId} is not the keyId ${config.keyId} in ${CliConfig.PROJECT_FILE} (--yes to sign anyway)", ExitCode.VALIDATION)
        }
        val bytes = file.readBytesOrFail()
        val sig = Sig(key.keyId, Sig.ALG, JdkEd25519.sign(key.private, bytes))
        val sigFile = File(file.path + ".sig")
        sigFile.writeBytesOrFail((sig.toJson().toString() + "\n").toByteArray())
        ctx.out.put("signature", sigFile.path)
        ctx.out.put("keyId", key.keyId)
        ctx.out.put("sha256", Hex.sha256(bytes))
        ctx.out.line("wrote ${sigFile.path} (keyId ${key.keyId})")
        return ExitCode.OK
    }
}

object VerifyCommand : Command {
    override val name = "verify"
    override val summary = "Check a package's .sig against a publisher public key"
    override val usage = "verify <file.easyext> --pub <key.pub.json> [--sig <file.sig>]"

    override fun run(args: List<String>, ctx: CliContext): ExitCode {
        val a = Args.parse(args, setOf("pub", "sig"), emptySet(), 1)
        val file = ctx.resolve(a.positional(0) ?: usage("verify needs a .easyext file"))
        val (keyId, raw) = Keys.loadPublic(ctx.resolve(a.value("pub") ?: usage("--pub <key.pub.json> is required")))
        val sigFile = a.value("sig")?.let { ctx.resolve(it) } ?: File(file.path + ".sig")
        val sigJson = (JsonText.parseStrict(String(sigFile.readBytesOrFail(), Charsets.UTF_8)) as? JsonParse.Ok)?.value
        val sig = Sig.fromJson(sigJson) ?: throw CliFailure(CliCode.SIGNATURE, "not a {keyId, alg: ed25519, sig} file", ExitCode.VALIDATION, sigFile.path)
        val ok = SignatureVerifier(JdkEd25519).verifyPackage(file.readBytesOrFail(), sig, raw)
        ctx.out.put("verified", ok)
        ctx.out.put("keyId", sig.keyId)
        if (!ok) {
            val why = if (sig.keyId != keyId) "signed by ${sig.keyId}, not $keyId" else "signature does not match the package bytes"
            ctx.out.diagnostic(cliError(CliCode.SIGNATURE, why, file.path))
            return ExitCode.VALIDATION
        }
        ctx.out.line("verified: ${file.name} signed by $keyId")
        return ExitCode.OK
    }
}
