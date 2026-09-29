package dev.easyide.sandbox.bootstrap

import dev.easyide.sandbox.backend.GuestEnvironment
import java.io.File

/**
 * The guest's accounts, login profile, `sudo` and `passwd`, written into a rootfs on every launch
 * (idempotent, cheap).
 *
 * Interactive terminals run as [GuestEnvironment.DEFAULT_USER]: fake uid 1000 in the root group,
 * home `/root` (shared with language servers and tasks, which run as root). proot only fakes ids,
 * and a fake uid 1000 process can regain uid 0 with `setpriv --reuid=0` (measured on a Xiaomi
 * Pad 6), so `sudo` is a shim that checks the account password and re-runs the command that way.
 * None of this is a security boundary (docs/decision/0002); it makes the terminal behave like a
 * normal Linux user session, including tools that refuse to run as root.
 *
 * `root` and `dev` both start with [DEFAULT_PASSWORD], which the shim prints on every `sudo`
 * until it has been changed with `passwd`. Files are edited directly rather than through
 * `useradd`/`chpasswd`, so this works before any package is installed.
 */
internal object GuestAccounts {

    const val DEFAULT_PASSWORD = "easyide"

    /** SHA-512 crypt of [DEFAULT_PASSWORD]; the shim compares the account's hash with it. */
    const val DEFAULT_PASSWORD_HASH =
        "\$6\$easyideSalt1\$o7I.c85HcAl9OMuHiykaCHehMueAODuozz/ffc2RpNlR9kpy04gFFLTodRQd2vc.gA8a84S.wrGorP4ZHs/am/"

    private const val ROOT = "root"
    private const val HOME = "/root"
    private const val LAST_CHANGE_DAYS = "20000"

    /** Shadow password fields that mean "no usable password yet". */
    private val UNSET = setOf("", "*", "!", "!!")

    fun ensure(rootfs: File) {
        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.isFile) return
        writeIfChanged(passwd, withDefaultUser(passwd.readLines(), passwdLine()))
        installShims(rootfs)
        File(rootfs, "etc/shadow").takeIf { it.isFile }?.let { shadow ->
            writeIfChanged(shadow, withDefaultPasswords(shadow.readLines()))
        }
        // A `dev` group entry from the old uid 1000 account is not needed: the account's group is root.
        File(rootfs, "etc/group").takeIf { it.isFile }?.let { group ->
            writeIfChanged(group, group.readLines().filterNot { it.startsWith("${GuestEnvironment.DEFAULT_USER}:") })
        }
        installLoginProfile(rootfs)
    }

    /** uid 1000, gid 0 (the root group), root's home and a real login shell. */
    private fun passwdLine() =
        "${GuestEnvironment.DEFAULT_USER}:x:${GuestEnvironment.DEFAULT_UID}:${GuestEnvironment.DEFAULT_GID}:${GuestEnvironment.DEFAULT_USER}:$HOME:${GuestEnvironment.LOGIN_SHELL}"

    /** Replaces any earlier `dev` line (older builds wrote a uid 1000 group 1000 one, then a uid 0 alias) and keeps everything else. */
    fun withDefaultUser(lines: List<String>, entry: String): List<String> =
        lines.filterNot { it.startsWith("${GuestEnvironment.DEFAULT_USER}:") } + entry

    /** Sets the default password on `root` and `dev` where none is set; a password the user chose is never touched. */
    fun withDefaultPasswords(lines: List<String>): List<String> {
        val kept = lines.filterNot { it.startsWith("${GuestEnvironment.DEFAULT_USER}:") && shadowHash(it) in UNSET }
        val hasDev = kept.any { it.startsWith("${GuestEnvironment.DEFAULT_USER}:") }
        val devLine = shadowLine(GuestEnvironment.DEFAULT_USER)
        val updated = kept.map { if (it.startsWith("$ROOT:") && shadowHash(it) in UNSET) setHash(it) else it }
        return if (hasDev) updated else listOf(devLine) + updated
    }

    private fun shadowLine(user: String) = "$user:$DEFAULT_PASSWORD_HASH:$LAST_CHANGE_DAYS:0:99999:7:::"

    private fun shadowHash(line: String) = line.split(':').getOrElse(1) { "" }

    private fun setHash(line: String): String {
        val fields = line.split(':').toMutableList()
        fields[1] = DEFAULT_PASSWORD_HASH
        return fields.joinToString(":")
    }

    private fun writeIfChanged(file: File, lines: List<String>) {
        val text = lines.joinToString("\n") + "\n"
        if (file.readText() == text) return
        // Another proot may be reading this file right now (every terminal reads /etc/passwd):
        // rename is atomic, an in-place write is not.
        val temp = File(file.parentFile, "${file.name}.easyide-tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            temp.delete()
            file.writeText(text)
        }
    }

    /**
     * `/etc/profile` resets `PATH` for uid 0, which would drop the per-user bin directories the
     * environment passes in, so a profile.d script puts them back. `~/.profile` and `~/.bashrc`
     * are created from the skeleton when missing so a login bash has the distro's own setup.
     */
    private fun installLoginProfile(rootfs: File) {
        val profileD = File(rootfs, "etc/profile.d").apply { mkdirs() }
        val script = File(profileD, "easyide.sh")
        if (!script.isFile || script.readText() != PROFILE_SCRIPT) script.writeText(PROFILE_SCRIPT)
        val home = File(rootfs, HOME.removePrefix("/")).apply { mkdirs() }
        // Ubuntu's login scripts run `groups` to print a sudo hint, which under Android lists the
        // app's numeric supplementary groups as errors; a .hushlogin skips that and the motd.
        File(home, ".hushlogin").takeIf { !it.exists() }?.createNewFile()
        for (name in listOf(".profile", ".bashrc")) {
            val target = File(home, name)
            val skel = File(rootfs, "etc/skel/$name")
            if (!target.exists() && skel.isFile) skel.copyTo(target)
        }
    }

    private val PROFILE_SCRIPT = """
        # easyIDE: keep per-user tool directories on PATH after /etc/profile resets it.
        for d in ${GuestEnvironment.USER_BIN_DIRS.joinToString(" ")}; do
          case ":${'$'}PATH:" in
            *":${'$'}HOME/${'$'}d:"*) ;;
            *) [ -d "${'$'}HOME/${'$'}d" ] && PATH="${'$'}HOME/${'$'}d:${'$'}PATH" ;;
          esac
        done
        export PATH
        export COLORTERM="${'$'}{COLORTERM:-${GuestEnvironment.DEFAULT_COLORTERM}}"
    """.trimIndent() + "\n"

    /** `passwd` needs a setuid root binary, which proot cannot honour, so it is re-run as uid 0. */
    private fun installShims(rootfs: File) {
        val bin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        val passwd = File(bin, "passwd")
        if (!passwd.isFile || passwd.readText() != PASSWD_SHIM) {
            passwd.writeText(PASSWD_SHIM)
            passwd.setExecutable(true, false)
        }
    }

    internal val PASSWD_SHIM = """
        #!/bin/sh
        # easyIDE: the real passwd needs a setuid root binary, which proot cannot honour.
        [ "${'$'}(id -u)" -eq 0 ] && exec /usr/bin/passwd "${'$'}@"
        [ ${'$'}# -eq 0 ] && set -- "${'$'}(id -un)"
        exec setpriv --reuid=0 --regid=0 --clear-groups -- /usr/bin/passwd "${'$'}@"
    """.trimIndent() + "\n"

    /**
     * `sudo` for a sandbox whose terminal user is a fake uid 1000. On a terminal it asks for the
     * account's password (checked against `/etc/shadow` with perl's `crypt`, in every Ubuntu base)
     * and, while that is still [DEFAULT_PASSWORD], says what it is; a correct password is
     * remembered for 15 minutes. Then the command runs with uid 0 (`setpriv --reuid=0`), or
     * directly when the caller already is root. Without a terminal (scripts, tasks) there is
     * nobody to ask, so it escalates without a prompt.
     */
    val SUDO_SHIM = """
        #!/bin/sh
        DEFAULT_HASH='$DEFAULT_PASSWORD_HASH'
        DEFAULT_PASSWORD='$DEFAULT_PASSWORD'
        USER_NAME=${'$'}(id -un)
        STAMP=/tmp/.easyide-sudo-stamp
        ask=1; shell=0; target=root
        while [ ${'$'}# -gt 0 ]; do
          case "${'$'}1" in
            -n|--non-interactive) ask=0; shift ;;
            -k|-K) rm -f "${'$'}STAMP"; [ ${'$'}# -eq 1 ] && exit 0; shift ;;
            -i|-s) shell=1; shift ;;
            -u) target="${'$'}2"; shift 2 ;;
            -g|-p|-C|-h|-r|-t|-U|-D|-R|-T) shift 2 ;;
            --) shift; break ;;
            -*) shift ;;
            *) break ;;
          esac
        done

        authenticate() {
          [ ${'$'}(id -u) -eq 0 ] && return 0
          [ -t 0 ] && [ -t 2 ] || return 0
          [ -f "${'$'}STAMP" ] && [ -z "${'$'}(find "${'$'}STAMP" -mmin +15 2>/dev/null)" ] && return 0
          hash=${'$'}(awk -F: -v u="${'$'}USER_NAME" '${'$'}1 == u { print ${'$'}2; exit }' /etc/shadow 2>/dev/null)
          case "${'$'}hash" in ''|'!'*|'*'*) return 0 ;; esac
          if [ "${'$'}hash" = "${'$'}DEFAULT_HASH" ]; then
            echo "[sudo] default password: ${'$'}DEFAULT_PASSWORD (unchanged; run \"passwd\" to set your own)" >&2
          fi
          command -v perl >/dev/null 2>&1 || return 0
          if [ ${'$'}ask -eq 0 ]; then echo "sudo: a password is required" >&2; exit 1; fi
          tries=0
          while [ ${'$'}tries -lt 3 ]; do
            printf '[sudo] password for %s: ' "${'$'}USER_NAME" >&2
            stty -echo 2>/dev/null
            IFS= read -r pw </dev/tty
            stty echo 2>/dev/null
            printf '\n' >&2
            if [ "${'$'}(PW="${'$'}pw" perl -e 'print crypt(${'$'}ENV{PW}, ${'$'}ARGV[0])' "${'$'}hash")" = "${'$'}hash" ]; then
              touch "${'$'}STAMP"
              return 0
            fi
            tries=${'$'}((tries + 1))
            echo "Sorry, try again." >&2
          done
          echo "sudo: 3 incorrect password attempts" >&2
          exit 1
        }

        run_as_target() {
          if [ ${'$'}(id -u) -eq 0 ]; then exec "${'$'}@"; fi
          uid=${'$'}(id -u "${'$'}target" 2>/dev/null) || { echo "sudo: unknown user ${'$'}target" >&2; exit 1; }
          gid=${'$'}(id -g "${'$'}target")
          exec setpriv --reuid="${'$'}uid" --regid="${'$'}gid" --clear-groups -- env USER="${'$'}target" LOGNAME="${'$'}target" "${'$'}@"
        }

        authenticate
        if [ ${'$'}# -eq 0 ]; then
          [ ${'$'}shell -eq 1 ] && run_as_target /bin/bash -l
          echo "usage: sudo [-i] [-n] [-k] [-u user] command" >&2
          exit 1
        fi
        [ ${'$'}shell -eq 1 ] && run_as_target /bin/bash -l -c "${'$'}*"
        run_as_target "${'$'}@"
    """.trimIndent() + "\n"
}
