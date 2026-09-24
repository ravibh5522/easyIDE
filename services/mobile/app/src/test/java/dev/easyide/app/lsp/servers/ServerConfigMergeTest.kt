package dev.easyide.app.lsp.servers

import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.session.FeatureFilter
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigMergeTest {

    private val defaults = ServerDefaults(memoryBudgetMb = 400, idleShutdownSec = 600, startupTimeoutSec = 30)
    private val pyright = ServerDeclaration(
        key = "easyide.python/pyright",
        languages = setOf("python"),
        command = listOf("pyright-langserver", "--stdio"),
        settingsSection = "python",
        memoryBudgetMb = 500,
        install = InstallRecipe(listOf(InstallStep("Node", "apt-get install -y nodejs"), InstallStep("pyright", "npm i -g pyright")), "pyright --version"),
        extensionId = "easyide.python",
    )
    private val ruff = ServerDeclaration(
        key = "easyide.python/ruff",
        languages = setOf("python"),
        command = listOf("ruff", "server"),
        features = FeatureFilter(setOf(LspFeature.DIAGNOSTICS, LspFeature.CODE_ACTION, LspFeature.FORMATTING), emptySet()),
    )

    private fun overrides(json: String) = ServerConfigMerge.parseOverrides(Json.parseToJsonElement(json))

    @Test
    fun declaredServersTakeDefaultsForMissingLimits() {
        val r = ServerConfigMerge.merge(listOf(pyright, ruff), emptyMap(), defaults, lspEnabled = true)
        assertEquals(listOf("easyide.python/pyright", "easyide.python/ruff"), r.servers.map { it.config.serverId })
        assertEquals(500, r.servers[0].config.memoryBudgetMb)
        assertEquals(400, r.servers[1].config.memoryBudgetMb)
        assertEquals(30, r.servers[1].config.startupTimeoutSec)
        assertEquals(listOf(".git"), r.servers[1].config.rootMarkers)
        assertTrue(r.servers.all { it.config.enabled })
        assertEquals("easyide.python", r.servers[0].extensionId)
    }

    @Test
    fun userEntryOverridesFieldByFieldAndCanDisable() {
        val o = overrides(
            """{"easyide.python/pyright": {"enabled": false},
                "easyide.python/ruff": {"memoryBudgetMb": 120, "env": {"RUFF_CACHE_DIR": "/tmp/ruff"}, "priority": 5}}""",
        )
        val r = ServerConfigMerge.merge(listOf(pyright, ruff), o, defaults, lspEnabled = true)
        assertFalse(r.servers[0].config.enabled)
        val ruffConfig = r.servers[1].config
        assertEquals(120, ruffConfig.memoryBudgetMb)
        assertEquals(mapOf("RUFF_CACHE_DIR" to "/tmp/ruff"), ruffConfig.env)
        assertEquals(5, ruffConfig.priority)
        assertEquals(listOf("ruff", "server"), ruffConfig.command)
        assertTrue(ruffConfig.features.allows(LspFeature.FORMATTING))
        assertFalse(ruffConfig.features.allows(LspFeature.COMPLETION))
    }

    @Test
    fun keyNobodyDeclaredAddsAServerWithoutAnExtension() {
        val o = overrides(
            """{"my-zig": {"languages": ["zig"], "command": ["zls"], "rootMarkers": ["build.zig"],
                           "initializationOptions": {"enable_inlay_hints": true}, "memoryBudgetMb": 300,
                           "features": {"exclude": ["formatting", "noSuchFeature"]}}}""",
        )
        val r = ServerConfigMerge.merge(emptyList(), o, defaults, lspEnabled = true)
        val zls = r.servers.single()
        assertEquals("my-zig", zls.config.serverId)
        assertEquals(setOf("zig"), zls.config.languages)
        assertEquals(listOf("build.zig"), zls.config.rootMarkers)
        assertEquals(300, zls.config.memoryBudgetMb)
        assertFalse(zls.config.features.allows(LspFeature.FORMATTING))
        assertTrue(zls.config.features.allows(LspFeature.COMPLETION))
        assertNull(zls.install)
        assertNull(zls.extensionId)
    }

    @Test
    fun incompleteUserOnlyEntriesAreRejectedNotGuessed() {
        val o = overrides("""{"easyide.go/gopls": {"enabled": false}, "half": {"command": ["x"]}}""")
        val r = ServerConfigMerge.merge(listOf(pyright), o, defaults, lspEnabled = true)
        assertEquals(listOf("easyide.python/pyright"), r.servers.map { it.config.serverId })
        assertEquals(listOf("easyide.go/gopls", "half"), r.rejected)
    }

    @Test
    fun wrongTypedFieldsAreDroppedOneByOne() {
        val o = overrides("""{"easyide.python/ruff": {"memoryBudgetMb": "lots", "priority": 2, "command": "ruff"}}""")
        val ruffOverride = o.getValue("easyide.python/ruff")
        assertNull(ruffOverride.memoryBudgetMb)
        assertNull(ruffOverride.command)
        assertEquals(2, ruffOverride.priority)
        assertTrue(ServerConfigMerge.parseOverrides(Json.parseToJsonElement("[1]")).isEmpty())
    }

    @Test
    fun lspDisabledDisablesEveryServer() {
        val r = ServerConfigMerge.merge(listOf(pyright, ruff), emptyMap(), defaults, lspEnabled = false)
        assertTrue(r.servers.none { it.config.enabled })
    }

    @Test
    fun duplicateDeclarationsKeepTheFirst() {
        val r = ServerConfigMerge.merge(listOf(pyright, pyright.copy(priority = 9)), emptyMap(), defaults, lspEnabled = true)
        assertEquals(0, r.servers.single().config.priority)
    }

    @Test
    fun installScriptStopsAtTheFirstFailingStep() {
        assertEquals("apt-get install -y nodejs && npm i -g pyright && pyright --version", pyright.install?.script())
    }
}
