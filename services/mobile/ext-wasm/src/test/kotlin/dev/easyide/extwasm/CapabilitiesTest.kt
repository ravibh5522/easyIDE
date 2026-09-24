package dev.easyide.extwasm

import dev.easyide.extwasm.host.Cap
import dev.easyide.extwasm.host.CapabilitySet
import dev.easyide.extwasm.host.GuestPaths
import dev.easyide.extwasm.host.HostMatcher
import dev.easyide.extwasm.host.NetGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class CapabilitiesTest {
    @Test fun hostMatcherSuffixAndExact() {
        val m = HostMatcher(listOf("api.example.com", "*.cdn.net"))
        assertTrue(m.matches("api.example.com"))
        assertTrue(m.matches("API.Example.com."))
        assertTrue(m.matches("a.cdn.net"))
        assertTrue(m.matches("a.b.cdn.net"))
        assertFalse(m.matches("cdn.net"))
        assertFalse(m.matches("xcdn.net"))
        assertFalse(m.matches("example.com"))
        assertFalse(m.matches("api.example.com.evil"))
    }

    @Test fun pathNormalisation() {
        assertEquals("/workspace/a", GuestPaths.normalize("/workspace/./b/../a"))
        assertEquals("/etc/passwd", GuestPaths.normalize("/workspace/../etc/passwd"))
        assertEquals("/", GuestPaths.normalize("/../.."))
        assertEquals("/workspace", GuestPaths.normalize("//workspace//"))
        assertNull(GuestPaths.normalize("workspace/a"))
        assertNull(GuestPaths.normalize("/workspace/a\u0000b"))
        assertTrue(GuestPaths.inProject("/workspace"))
        assertFalse(GuestPaths.inProject("/workspaces/x"))
    }

    @Test fun missingCapabilityForPaths() {
        val read = CapabilitySet(listOf(Cap.FS_READ))
        assertNull(GuestPaths.missingFor(read, "/workspace/a", write = false))
        assertEquals(Cap.FS_WRITE, GuestPaths.missingFor(read, "/workspace/a", write = true))
        assertEquals(Cap.FS_OUTSIDE, GuestPaths.missingFor(read, "/tmp/a", write = false))
    }

    @Test fun netGuardRefusesNonPublicAddresses() {
        for (a in listOf("127.0.0.1", "10.1.2.3", "192.168.1.2", "172.16.0.1", "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fe80::1", "fd00::1")) {
            assertFalse(a, NetGuard.isPublic(InetAddress.getByName(a)))
        }
        for (a in listOf("8.8.8.8", "100.128.0.1", "2606:4700:4700::1111")) {
            assertTrue(a, NetGuard.isPublic(InetAddress.getByName(a)))
        }
    }
}
