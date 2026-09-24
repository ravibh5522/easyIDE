package dev.easyide.sandbox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Sha256Test {

    @get:Rule val temp = TemporaryFolder()

    @Test fun `hashes a file to the FIPS 180-2 abc vector`() {
        val file = temp.newFile().apply { writeText("abc") }

        assertEquals(ABC, Sha256.of(file).hex)
    }

    @Test fun `hashes a file larger than one read buffer`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val file = temp.newFile().apply { writeBytes(bytes) }

        assertEquals(Sha256.fromDigest(Sha256.newDigest().digest(bytes)), Sha256.of(file))
    }

    @Test fun `parse normalises upper case so equal digests compare equal`() {
        assertEquals(Sha256.parse(ABC), Sha256.parse(ABC.uppercase()))
        assertEquals(ABC, Sha256.parse(ABC.uppercase()).hex)
    }

    @Test fun `parse rejects anything but 64 hex characters`() {
        listOf("", ABC.dropLast(1), ABC + "0", "g" + ABC.drop(1), " $ABC", ABC.replaceRange(0, 1, "-"))
            .forEach { raw -> assertThrows(IllegalArgumentException::class.java) { Sha256.parse(raw) } }
    }

    private companion object {
        const val ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
