package com.sahay.comms.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SignatureVerifierTest {

    private val verifier = TestVectors.verifier()
    private val reader = TestVectors.reader()

    @Test fun `every case in wire_test_vectors json gets the expected verdict`() {
        assertTrue("vectors not loaded", TestVectors.cases.size >= 8)
        TestVectors.cases.forEach { case ->
            val accepted = reader.read(case.wire) != null
            assertEquals("${case.note} -> ${case.wire}", case.valid, accepted)
        }
    }

    @Test fun `valid signatures verify and tampered ones do not`() {
        val cases = TestVectors.cases.filter { it.wire.length <= 160 && it.wire.startsWith("SH1*") }
        cases.filter { it.valid }.forEach { assertTrue(it.note, verifier.verify(it.wire)) }
        cases.filter { it.note.contains("signature", ignoreCase = true) && !it.valid }
            .forEach { assertFalse(it.note, verifier.verify(it.wire)) }
    }

    @Test fun `changing any single character of a signed wire breaks it`() {
        val wire = TestVectors.firstValid("A")
        val random = kotlin.random.Random(1)
        repeat(500) {
            val chars = wire.toCharArray()
            val at = random.nextInt(chars.size)
            val replacement = "abcXYZ0189_-*".random(random)
            if (replacement == chars[at]) return@repeat
            chars[at] = replacement
            assertNull(String(chars), reader.read(String(chars)))
        }
    }

    @Test fun `non-canonical base64 spellings of a signature are refused`() {
        val wire = TestVectors.firstValid("A")
        // The last character carries 4 unused bits; flipping them keeps the same signature bytes.
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val variants = alphabet.map { wire.dropLast(1) + it }.filter { it != wire }
        assertTrue(variants.none { verifier.verify(it) })
    }

    @Test fun `uses the active pack key when present and the built-in key otherwise`() {
        val wire = TestVectors.firstValid("A")
        val builtIn = "XFgaI5xjK7l1FvSKWASy9TEdO8YSZ6A1r3U8e/JxrHk="    // production key, does not match the vectors

        assertTrue(SignatureVerifier({ TestVectors.publicKeyB64 }, builtIn).verify(wire))
        assertFalse("pack key wins over built-in", SignatureVerifier({ builtIn }, TestVectors.publicKeyB64).verify(wire))
        assertTrue("null pack key", SignatureVerifier({ null }, TestVectors.publicKeyB64).verify(wire))
        assertTrue("empty pack key", SignatureVerifier({ "" }, TestVectors.publicKeyB64).verify(wire))
        assertTrue("blank pack key", SignatureVerifier({ "  " }, TestVectors.publicKeyB64).verify(wire))
        assertFalse("production key rejects test-key signatures", SignatureVerifier({ null }, builtIn).verify(wire))
    }

    @Test fun `follows a pack key change between calls`() {
        var key: String? = null
        val v = SignatureVerifier({ key }, "XFgaI5xjK7l1FvSKWASy9TEdO8YSZ6A1r3U8e/JxrHk=")
        val wire = TestVectors.firstValid("A")
        assertFalse(v.verify(wire))
        key = TestVectors.publicKeyB64
        assertTrue(v.verify(wire))
        key = null
        assertFalse(v.verify(wire))
    }

    @Test fun `broken keys and odd input make verify false instead of throwing`() {
        val wire = TestVectors.firstValid("A")
        assertFalse(SignatureVerifier({ "not base64 !!" }, "").verify(wire))
        assertFalse(SignatureVerifier({ "AAAA" }, "").verify(wire))          // valid base64, wrong length
        assertFalse(SignatureVerifier({ null }, "").verify(wire))            // no key anywhere
        listOf("", "*", "SH1", "SH1*A*", "no stars at all", "a*b*", "SH1*A*x*!!!!").forEach {
            assertFalse(it, verifier.verify(it))
        }
    }

    @Test fun `unsigned types need no signature`() {
        val reader = TestVectors.reader()
        val presence = "SH1*P*12.621,80.194*${TestVectors.NOW_SEC}*anon0000"
        assertNotNull(reader.read(presence))
        assertNotNull(reader.read("  $presence \n"))      // SMS often adds whitespace
    }

    @Test fun `random strings never crash the reader`() {
        val random = Random(99)
        val alphabet = "SH1*AGSRPB0123456789,._-=+/ é"
        repeat(20_000) {
            val text = buildString { repeat(random.nextInt(180)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            reader.read(text)
            reader.read("SH1*A*" + text)
            verifier.verify(text)
        }
    }
}
