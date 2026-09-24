package dev.easyide.ext.cli

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The system `git`, argv form only (never a shell string), so the author's own credentials,
 * SSH agent and identity apply (cli.md sec 5.5). An interface so tests can assert argv.
 */
interface GitRunner {
    /** Runs `git <args>` in [dir]; stdout on success. @throws CliFailure (exit 2) on failure. */
    fun run(dir: File, vararg args: String): String
}

class SystemGit(private val env: Map<String, String> = emptyMap(), private val timeoutSec: Long = CliPolicy.GIT_TIMEOUT_SEC) : GitRunner {
    override fun run(dir: File, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true)
        pb.environment().putAll(env)
        // Never block on a credential prompt: the CLI has no terminal to answer it on.
        pb.environment()["GIT_TERMINAL_PROMPT"] = "0"
        val p = try { pb.start() } catch (e: java.io.IOException) { ioFailure("cannot run git: ${e.message}") }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            ioFailure("git ${args.firstOrNull()} timed out after ${timeoutSec}s")
        }
        if (p.exitValue() != 0) ioFailure("git ${args.joinToString(" ")} failed (${p.exitValue()}): ${out.trim().lines().lastOrNull().orEmpty()}")
        return out
    }
}

/** CLI-only constants (cli.md sec 7). */
object CliPolicy {
    const val GIT_TIMEOUT_SEC = 300L
}
