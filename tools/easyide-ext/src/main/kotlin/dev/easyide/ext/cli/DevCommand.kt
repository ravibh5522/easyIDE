package dev.easyide.ext.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.time.Instant
import java.util.concurrent.TimeUnit

/** The system `adb`, argv form only. An interface so tests assert exact argv (cli.md sec 9). */
interface AdbRunner {
    /** Runs `adb <args>`; stdout. @throws CliFailure (exit 2) when adb fails. */
    fun run(vararg args: String): String
}

class SystemAdb(private val timeoutSec: Long = CliPolicy.ADB_TIMEOUT_SEC) : AdbRunner {
    override fun run(vararg args: String): String {
        val p = try { ProcessBuilder(listOf("adb") + args).redirectErrorStream(true).start() } catch (e: java.io.IOException) {
            ioFailure("cannot run adb (install Android platform-tools and put adb on PATH): ${e.message}")
        }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); ioFailure("adb ${args.firstOrNull()} timed out") }
        if (p.exitValue() != 0) ioFailure("adb ${args.joinToString(" ")} failed: ${out.trim().lines().lastOrNull().orEmpty()}")
        return out
    }
}

/**
 * cli.md sec 5.7 `dev`: package in memory (unsigned) and hand the result to the app, which
 * installs it as a developer extension only while `extensions.developerMode` is on.
 *
 * - adb: push to the app's dev inbox and broadcast `DEV_RELOAD` to its receiver, which only
 *   the shell UID may send (the app declares the `DUMP` permission on it).
 * - `--local` (inside an easyIDE environment): write `<workspace>/.easyide/dev/<id>.json`
 *   naming the extension folder. The project directory is the guest-writable, host-watched
 *   handoff the LLD left open; the app maps the folder back to its host path.
 */
object DevCommand : Command {
    override val name = "dev"
    override val summary = "Install the folder as a developer extension on a device or in this environment"
    override val usage = "dev [dir] [--device <serial>] [--app-id <id>] [--local [--workspace <dir>]] [--watch]"

    const val APP_ID = "dev.easyide.app"
    const val RECEIVER = "dev.easyide.app.extensions.dev.DevReloadReceiver"
    const val ACTION = "dev.easyide.app.action.DEV_RELOAD"
    const val DEFAULT_WORKSPACE = "/workspace"

    var adb: (CliContext) -> AdbRunner = { SystemAdb() }

    override fun run(args: List<String>, ctx: CliContext): ExitCode {
        val a = Args.parse(args, setOf("device", "app-id", "workspace"), setOf("local", "watch"), 1)
        val dir = ctx.resolve(a.positional(0) ?: ".")
        if (!dir.isDirectory) ioFailure("not a directory: ${dir.path}", dir.path)
        val config = CliConfig.load(ctx, dir)
        val deploy: () -> ExitCode = if (a.flag("local")) {
            val workspace = ctx.resolve(a.value("workspace") ?: DEFAULT_WORKSPACE).canonicalFile
            { local(ctx, dir.canonicalFile, workspace) }
        } else {
            val appId = a.value("app-id") ?: APP_ID
            val device = a.value("device") ?: config.device
            val runner = adb(ctx);
            { overAdb(ctx, dir, runner, device, appId) }
        }
        val first = deploy()
        if (!a.flag("watch") || first != ExitCode.OK) return first
        watch(ctx, dir, deploy)
        return ExitCode.OK
    }

    private fun validated(ctx: CliContext, dir: File): Validation? {
        val v = Validator().validateFolder(dir, strict = false)
        if (v.descriptor == null || v.errors > 0) {
            ctx.out.diagnostics(v.diagnostics)
            ctx.out.line("not deployed: fix the errors above")
            return null
        }
        return v
    }

    private fun overAdb(ctx: CliContext, dir: File, adb: AdbRunner, device: String?, appId: String): ExitCode {
        val v = validated(ctx, dir) ?: return ExitCode.VALIDATION
        val id = v.descriptor!!.id.value
        val bytes = Packager.zip(v.files!!)
        val target = "/sdcard/Android/data/$appId/files/dev-inbox/$id.easyext"
        val serial = device?.let { listOf("-s", it) }.orEmpty().toTypedArray()
        PackageSource.withTempDir { tmp ->
            val file = File(tmp, "$id.easyext").apply { writeBytesOrFail(bytes) }
            adb.run(*serial, "push", file.path, target)
        }
        adb.run(*serial, "shell", "am", "broadcast", "-a", ACTION, "-n", "$appId/$RECEIVER", "--es", "id", id)
        ctx.out.put("id", id)
        ctx.out.put("pushed", target)
        ctx.out.line("sent $id to ${device ?: "the connected device"}; check the Extension Log for the result")
        return ExitCode.OK
    }

    private fun local(ctx: CliContext, dir: File, workspace: File): ExitCode {
        val v = validated(ctx, dir) ?: return ExitCode.VALIDATION
        val id = v.descriptor!!.id.value
        val relative = dir.relativeToOrNull(workspace)?.invariantSeparatorsPath
        if (relative == null || relative.startsWith("..")) usage("--local needs the extension inside the workspace (${workspace.path}); it is at ${dir.path}")
        val request = File(workspace, ".easyide/dev/$id.json")
        request.writeBytesOrFail((JsonObject(mapOf(
            "id" to JsonPrimitive(id), "folder" to JsonPrimitive(relative.ifEmpty { "." }),
            "requestedAt" to JsonPrimitive(Instant.now().toString()),
        )).toString() + "\n").toByteArray())
        ctx.out.put("id", id)
        ctx.out.put("request", request.path)
        ctx.out.line("requested a developer install of $id; check the Extension Log")
        return ExitCode.OK
    }

    /** Re-deploys on changes to package content (ignored paths excluded), debounced. Runs until interrupted. */
    private fun watch(ctx: CliContext, dir: File, deploy: () -> ExitCode) {
        val rules = PackageSource.ignoreRules(dir)
        FileSystems.getDefault().newWatchService().use { ws ->
            register(ws, dir.toPath(), dir.toPath(), rules)
            ctx.out.line("watching ${dir.path} (Ctrl+C to stop)")
            while (true) {
                val key = ws.take()
                var relevant = false
                val base = key.watchable() as Path
                for (event in key.pollEvents()) {
                    val child = base.resolve(event.context() as? Path ?: continue)
                    val rel = dir.toPath().relativize(child).joinToString("/")
                    if (rules.ignored(rel) || rules.ignored("$rel/")) continue
                    relevant = true
                    if (child.toFile().isDirectory) register(ws, dir.toPath(), child, rules)
                }
                key.reset()
                if (!relevant) continue
                // Editors write in bursts; wait for quiet before packaging.
                while (true) {
                    val more = ws.poll(CliPolicy.WATCH_DEBOUNCE_MS, TimeUnit.MILLISECONDS) ?: break
                    more.pollEvents(); more.reset()
                }
                deploy()
            }
        }
    }

    private fun register(ws: WatchService, root: Path, dir: Path, rules: IgnoreRules) {
        dir.toFile().walkTopDown().onEnter { d -> d == dir.toFile() || !rules.ignored(root.relativize(d.toPath()).joinToString("/") + "/") }
            .filter { it.isDirectory }.forEach {
                it.toPath().register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
            }
    }
}
