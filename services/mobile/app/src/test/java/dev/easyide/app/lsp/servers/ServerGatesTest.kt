package dev.easyide.app.lsp.servers

import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SchemaState
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.layer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerGatesTest {

    private val defaults = ServerDefaults(memoryBudgetMb = 400, idleShutdownSec = 600, startupTimeoutSec = 30)
    private val pyright = ServerDeclaration(
        key = "easyide.python/pyright", languages = setOf("python"), command = listOf("pyright"), extensionId = "easyide.python",
    )
    private val clangd = ServerDeclaration(
        key = "easyide.cpp/clangd", languages = setOf("c", "cpp"), command = listOf("clangd"), extensionId = "easyide.cpp",
    )
    private val custom = """{"my-lua": {"languages": ["lua"], "command": ["lua-language-server"]}}"""

    private fun merged(overrides: String = "{}") = ServerConfigMerge.merge(
        listOf(pyright, clangd), ServerConfigMerge.parseOverrides(Json.parseToJsonElement(overrides)), defaults, lspEnabled = true,
    )

    private fun snapshot(user: String) = SettingsSnapshot(SchemaState.builtInOnly(SettingsSchema.all), listOf(layer(LayerId.USER, user)))

    private fun MergeResult.byKey(key: String) = servers.single { it.config.serverId == key }.config

    @Test
    fun languageBlockDisablesOnlyThatLanguagesServers() {
        val gates = ServerGates.from(snapshot("""{"[python]": {"lsp.enabled": false}}"""), safeMode = false)
        val r = gates.apply(merged())
        assertFalse(r.byKey("easyide.python/pyright").enabled)
        assertTrue(r.byKey("easyide.cpp/clangd").enabled)
    }

    @Test
    fun multiLanguageServerKeepsItsEnabledLanguages() {
        val gates = ServerGates.from(snapshot("""{"[c]": {"lsp.enabled": false}}"""), safeMode = false)
        val clangdConfig = gates.apply(merged()).byKey("easyide.cpp/clangd")
        assertTrue(clangdConfig.enabled)
        assertEquals(setOf("cpp"), clangdConfig.languages)
    }

    @Test
    fun globalOffWithLanguageOnStartsOnlyThatLanguage() {
        val gates = ServerGates.from(snapshot("""{"lsp.enabled": false, "[cpp]": {"lsp.enabled": true}}"""), safeMode = false)
        val r = gates.apply(merged())
        assertFalse(r.byKey("easyide.python/pyright").enabled)
        val clangdConfig = r.byKey("easyide.cpp/clangd")
        assertTrue(clangdConfig.enabled)
        assertEquals(setOf("cpp"), clangdConfig.languages)
    }

    @Test
    fun safeModeDisablesSettingsOnlyServersButNotExtensionOnes() {
        val r = ServerGates(lspEnabled = true, safeMode = true).apply(merged(custom))
        assertFalse(r.byKey("my-lua").enabled)
        assertTrue(r.byKey("easyide.python/pyright").enabled)
        assertTrue(ServerGates(lspEnabled = true).apply(merged(custom)).byKey("my-lua").enabled)
    }

    @Test
    fun unchangedServersAreTheSameInstances() {
        val m = merged()
        val r = ServerGates(lspEnabled = true).apply(m)
        assertTrue(m.servers.zip(r.servers).all { (a, b) -> a === b })
    }

    @Test
    fun registryAppliesSafeModeAndLanguageGates() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val settings = MutableStateFlow(snapshot("""{"lsp.servers": $custom, "[python]": {"lsp.enabled": false}}"""))
        val safe = MutableStateFlow(false)
        val registry = ServerRegistry({ _, _ -> settings }, scope, safe) { }
        registry.register { flowOf(listOf(pyright)) }
        val servers = registry.serversFor("env", "proj")
        scope.advanceUntilIdle()
        assertEquals(mapOf("easyide.python/pyright" to false, "my-lua" to true), servers.value.associate { it.serverId to it.enabled })
        safe.value = true
        scope.advanceUntilIdle()
        assertEquals(mapOf("easyide.python/pyright" to false, "my-lua" to false), servers.value.associate { it.serverId to it.enabled })
        scope.cancel()
    }
}
