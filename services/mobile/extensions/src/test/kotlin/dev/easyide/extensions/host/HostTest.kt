package dev.easyide.extensions.host

import dev.easyide.extensions.FakeSettings
import dev.easyide.extensions.Manifests
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import dev.easyide.extensions.settings.RuntimeScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Crash journal and the six enablement rules. */
class HostTest {
    @get:Rule val tmp = TemporaryFolder()
    private val log = ArrayList<LogEntry>()
    private val a = ExtensionId.parse("acme.a")!!
    private val b = ExtensionId.parse("acme.b")!!

    private fun journal(file: File = File(tmp.root, "extensions/activation-journal.json")) = CrashJournal(file) { log += it }

    @Test fun `two consecutive crashed starts enter safe mode naming the suspects`() {
        val file = File(tmp.root, "j.json")
        journal(file).apply { onStartup(); begin(a, CrashJournal.Phase.ACTIVATE) }          // process dies here
        journal(file).apply {
            assertEquals(StartupVerdict(listOf(a), 1, false), onStartup())
            begin(b, CrashJournal.Phase.REGISTER_CONTRIBUTIONS)                               // dies again
        }
        val third = journal(file).onStartup()
        assertEquals(2, third.consecutive)
        assertTrue(third.enterSafeMode)
        assertEquals(listOf(a, b), third.suspects)
    }

    @Test fun `a clean run to onStartupFinished resets the counter, completed steps leave nothing`() {
        val file = File(tmp.root, "j.json")
        journal(file).apply { onStartup(); begin(a, CrashJournal.Phase.ACTIVATE) }
        journal(file).apply {
            assertEquals(1, onStartup().consecutive)
            begin(b, CrashJournal.Phase.ACTIVATE); end(b)
            markCleanRun()
        }
        assertEquals(StartupVerdict(emptyList(), 0, false), journal(file).onStartup())
    }

    @Test fun `open steps block the reset`() {
        val file = File(tmp.root, "j.json")
        journal(file).apply { onStartup(); begin(a, CrashJournal.Phase.ACTIVATE) }
        journal(file).apply { onStartup(); begin(b, CrashJournal.Phase.ACTIVATE); markCleanRun() }
        assertEquals(2, journal(file).onStartup().consecutive)
    }

    @Test fun `corrupt or unwritable journals never block startup`() {
        val file = File(tmp.root, "j.json").apply { writeText("{ nope") }
        assertEquals(StartupVerdict(emptyList(), 0, false), journal(file).onStartup())
        val blocker = File(tmp.root, "blocker").apply { writeText("file, not a dir") }
        val j = journal(File(blocker, "j.json"))
        assertFalse(j.onStartup().enterSafeMode)
        j.begin(a, CrashJournal.Phase.ACTIVATE)
        j.end(a)
        assertTrue(log.isNotEmpty())
    }

    // ---- enablement truth table (extension-runtime.md sec 4) ----

    private fun descriptor(caps: String = ""): ExtensionDescriptor = Manifests.ok(Manifests.minimal(""""easyide": { "capabilities": [$caps] }""", name = "a"))

    private fun pkg(scope: InstallScope = InstallScope.GLOBAL, env: String? = null, source: Source = Source.REGISTRY,
                    approved: Set<String> = emptySet(), revoked: Boolean = false, crash: Boolean = false) =
        InstalledPackage(tmp.root, scope, env, source, 1, approved, revoked, crash)

    private fun inputs(enabled: Boolean = true, safe: Boolean = false, disabled: Set<ExtensionId> = emptySet(),
                       profile: Set<ExtensionId>? = null, env: String? = "env1") =
        EnablementInputs(RuntimeScope(env, "p"), enabled, safe, disabled, profile)

    @Test fun `enablement rules`() {
        val d = descriptor()
        assertNull(Enablement.check(pkg(), d, inputs()))
        assertEquals(DisabledReason.OTHER_ENVIRONMENT, Enablement.check(pkg(InstallScope.ENVIRONMENT, "env2"), d, inputs()))
        assertNull(Enablement.check(pkg(InstallScope.ENVIRONMENT, "env1"), d, inputs()))
        assertEquals(DisabledReason.EXTENSIONS_OFF, Enablement.check(pkg(), d, inputs(enabled = false)))
        assertEquals(DisabledReason.SAFE_MODE, Enablement.check(pkg(), d, inputs(safe = true)))
        assertNull(Enablement.check(pkg(source = Source.BUILT_IN), d, inputs(enabled = false, safe = true)))
        assertEquals(DisabledReason.USER_DISABLED, Enablement.check(pkg(), d, inputs(disabled = setOf(d.id))))
        assertEquals(DisabledReason.NOT_IN_PROFILE, Enablement.check(pkg(), d, inputs(profile = setOf(b))))
        assertNull(Enablement.check(pkg(InstallScope.ENVIRONMENT, "env1"), d, inputs(profile = setOf(b))))
        assertEquals(DisabledReason.CRASH_DISABLED, Enablement.check(pkg(crash = true), d, inputs()))
        assertEquals(DisabledReason.REVOKED, Enablement.check(pkg(revoked = true), d, inputs()))
    }

    @Test fun `a capability added by an update disables until re-approved (R-API-05)`() {
        val v2 = descriptor("\"fs.project(write)\", \"network(api.example.com)\"")
        assertEquals(DisabledReason.NEEDS_APPROVAL, Enablement.check(pkg(approved = setOf("fs.project(write)")), v2, inputs()))
        val approved = pkg(approved = setOf("fs.project(write)", "network(api.example.com)"))
        assertNull(Enablement.check(approved, v2, inputs()))
        assertEquals(v2.capabilities, Enablement.granted(approved, v2))
        assertTrue(Enablement.granted(pkg(approved = setOf("fs.project(read)")), v2).items.isEmpty())
    }

    @Test fun `inputs come from settings - disabled list and profile allowlist`() {
        val s = FakeSettings().apply {
            set("extensions.disabled", """["Acme.A", "not an id"]""")
            set("extensions.enabled", "\"yes\"")     // wrong type: falls back to the default (true)
            profile = setOf("acme.b")
        }
        val i = EnablementInputs.read(s, RuntimeScope("e", "p"), safeMode = false)
        assertEquals(setOf(a), i.disabled)
        assertTrue(i.extensionsEnabled)
        assertEquals(setOf(b), i.profileAllowlist)
    }
}
