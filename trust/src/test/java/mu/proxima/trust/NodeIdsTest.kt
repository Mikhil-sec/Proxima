package mu.proxima.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MESH builds its multi-hop allow-list on this format (#19), so the expected values below are fixed vectors,
 * not recomputed by the test. If a change to [NodeIds.derive] breaks them it breaks MESH, and that should be a
 * deliberate, announced change rather than a green test suite.
 */
class NodeIdsTest {

    private val signing = "MCowBQYDK2VwAyEAGb9ECWmEzf6FQbrBZ9w7lshQhqowtrbLDFw4rXAxZuE"
    private val encryption = "MCowBQYDK2VuAyEAq2sE2Hk1pVJ9kHhSKqV3VbEWlLLnKvxYyXyQ8ZkR5Ac"

    @Test
    fun `derives the agreed fixed vectors`() {
        // SHA-256 of the UTF-8 bytes, hex, first 8 chars.
        assertEquals("a9e6b58c", NodeIds.derive(NodeIds.publicKey(signing, encryption)))
        assertEquals("2e7336dc", NodeIds.derive("a.b"))
        assertEquals("e3b0c442", NodeIds.derive(""))
    }

    @Test
    fun `ids are 8 lowercase hex characters`() {
        val id = NodeIds.derive(NodeIds.publicKey(signing, encryption))

        assertEquals(8, id.length)
        assertEquals(NodeIds.LENGTH, id.length)
        assertTrue("not lowercase hex: $id", id.matches(Regex("[0-9a-f]{8}")))
    }

    /** The whole point: same key in, same id out, every launch. */
    @Test
    fun `derivation is deterministic and key-dependent`() {
        val key = NodeIds.publicKey(signing, encryption)

        assertEquals(NodeIds.derive(key), NodeIds.derive(key))
        // A one-character change in either half gives a different id.
        assertNotEquals(NodeIds.derive(key), NodeIds.derive(NodeIds.publicKey(signing.dropLast(1) + "X", encryption)))
        assertNotEquals(NodeIds.derive(key), NodeIds.derive(NodeIds.publicKey(signing, encryption.dropLast(1) + "X")))
        // Swapping the halves is a different identity, so the order is part of the contract.
        assertNotEquals(NodeIds.derive(key), NodeIds.derive(NodeIds.publicKey(encryption, signing)))
    }

    @Test
    fun `public key round-trips through split`() {
        val key = NodeIds.publicKey(signing, encryption)

        assertEquals("$signing.$encryption", key)
        assertEquals(signing to encryption, NodeIds.split(key))
    }

    @Test
    fun `split rejects anything that is not two non-empty halves`() {
        assertNull(NodeIds.split("no-separator-here"))
        assertNull(NodeIds.split("three.parts.here"))
        assertNull(NodeIds.split(".onlyencryption"))
        assertNull(NodeIds.split("onlysigning."))
        assertNull(NodeIds.split(""))
    }

    @Test
    fun `key halves must be present and must not contain the separator`() {
        listOf(
            { NodeIds.publicKey("", encryption) },
            { NodeIds.publicKey(signing, "") },
            { NodeIds.publicKey("has.dot", encryption) },
            { NodeIds.publicKey(signing, "has.dot") },
        ).forEach { build ->
            runCatching { build() }.fold(
                onSuccess = { throw AssertionError("expected rejection, got $it") },
                onFailure = { assertTrue("wrong type: $it", it is IllegalArgumentException) },
            )
        }
    }
}
