package dev.easyide.extwasm

import dev.easyide.extwasm.load.WasmModuleLoader
import dev.easyide.extwasm.testing.MapSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModuleLoaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val limits = WasmLimits.resolve(MapSettings())

    private fun load(name: String, loader: WasmModuleLoader = WasmModuleLoader(tmp.newFolder()), l: WasmLimits = limits) {
        val f = Fixtures.file(tmp.root, name)
        loader.load(f, Fixtures.sha256(f.readBytes()), l)
    }

    private fun refused(name: String): String =
        assertThrows(ModuleRejectedException::class.java) { load(name) }.message!!

    @Test fun abiConformanceFixturesAreRefusedWithTheirReason() {
        assertTrue(refused("missing_export"), refused("missing_export").contains("missing export: ext_handle"))
        assertTrue(refused("wrong_signature"), refused("wrong_signature").contains("export ext_handle has type"))
        assertTrue(refused("imports_wasi"), refused("imports_wasi").contains("wasi_snapshot_preview1.fd_write is not allowed"))
        assertTrue(refused("start_fn"), refused("start_fn").contains("start function"))
        assertTrue(refused("bad_initialize"), refused("bad_initialize").contains("_initialize"))
        assertTrue(refused("reserved_export"), refused("reserved_export").contains("reserved prefix"))
        assertTrue(refused("big_memory"), refused("big_memory").contains("initial pages"))
        assertTrue(refused("simd"), refused("simd").contains("SIMD"))
    }

    @Test fun goodModuleLoadsAndClampsMemoryToTheCap() {
        val f = Fixtures.file(tmp.root, "proxy")
        val small = WasmLimits.resolve(MapSettings(mapOf(WasmSetting.MAX_MEMORY_MB.key to 2)))
        val loaded = WasmModuleLoader(tmp.newFolder()).load(f, Fixtures.sha256(f.readBytes()), small)
        assertEquals(Fixtures.sha256(f.readBytes()), loaded.sha256)
        assertEquals(32, loaded.memoryLimits.maximumPages())
    }

    @Test fun manifestMemoryCanOnlyLowerTheCap() {
        assertEquals(16, WasmLimits.resolve(MapSettings(), manifestMemoryMb = 1).maxMemoryPages)
        assertEquals(1024, WasmLimits.resolve(MapSettings(), manifestMemoryMb = 999).maxMemoryPages)
    }

    @Test fun shaMismatchAndSizeLimitAreRefused() {
        val f = Fixtures.file(tmp.root, "proxy")
        val e = assertThrows(ModuleRejectedException::class.java) {
            WasmModuleLoader(tmp.newFolder()).load(f, "00".repeat(32), limits)
        }
        assertTrue(e.message!!.contains("does not match"))
        val tiny = limits.copy(maxModuleBytes = 100)
        val big = assertThrows(ModuleRejectedException::class.java) {
            WasmModuleLoader(tmp.newFolder()).load(f, Fixtures.sha256(f.readBytes()), tiny)
        }
        assertTrue(big.message!!.contains("maxModuleMb"))
    }

    @Test fun diskCacheIsReusedAndATamperedEntryIsRecomputed() {
        val cache = tmp.newFolder()
        val f = Fixtures.file(tmp.root, "proxy")
        val sha = Fixtures.sha256(f.readBytes())
        WasmModuleLoader(cache).load(f, sha, limits)
        val entry = File(cache, "$sha-m1.wasm")
        assertTrue(entry.isFile)
        entry.writeBytes(byteArrayOf(0, 1, 2))
        // A fresh loader (no in-memory cache) must not trust the tampered file.
        WasmModuleLoader(cache).load(f, sha, limits)
        assertTrue(entry.readBytes().size > 3)
    }

    @Test fun pruneKeepsOnlyLiveModules() {
        val cache = tmp.newFolder()
        val f = Fixtures.file(tmp.root, "proxy")
        val sha = Fixtures.sha256(f.readBytes())
        val loader = WasmModuleLoader(cache)
        loader.load(f, sha, limits)
        loader.pruneDiskCache(setOf(sha))
        assertEquals(2, cache.listFiles()!!.size)
        loader.pruneDiskCache(emptySet())
        assertEquals(0, cache.listFiles()!!.size)
    }
}
