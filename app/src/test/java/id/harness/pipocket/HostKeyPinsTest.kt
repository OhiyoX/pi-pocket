package id.harness.pipocket

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class HostKeyPinsTest {
    @Test fun `first key persists and changed key is rejected after restart`() {
        val directory = Files.createTempDirectory("pi-pocket-pins").toFile()
        try {
            val file = directory.resolve("pins.properties")
            assertTrue(HostKeyPins(file).accept("Example.COM", 22, byteArrayOf(1, 2)))
            val restarted = HostKeyPins(file)
            assertTrue(restarted.accept("example.com", 22, byteArrayOf(1, 2)))
            assertFalse(restarted.accept("example.com", 22, byteArrayOf(3)))
            assertTrue(restarted.accept("example.com", 2222, byteArrayOf(3)))
            assertTrue(HostKeyPins(file).accept("example.com", 22, byteArrayOf(1, 2)))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `unreadable pin store fails closed`() {
        val directory = Files.createTempDirectory("pi-pocket-pins").toFile()
        try {
            // A directory cannot be parsed as a trusted pin file.
            assertThrows(Exception::class.java) { HostKeyPins(directory).accept("host", 22, byteArrayOf(1)) }
        } finally { directory.deleteRecursively() }
    }
}
