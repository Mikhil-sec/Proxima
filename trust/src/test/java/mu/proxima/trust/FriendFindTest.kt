package mu.proxima.trust

import mu.proxima.core.fake.FakeTrustEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Friend find (#38 item 3): only a paired friend can recognise your beacon, and only on their own phone. */
class FriendFindTest {

    private val anaPhone = SimulatedPhone(KeyRotation.PER_SESSION)
    private val benPhone = SimulatedPhone(KeyRotation.PER_SESSION)
    private val ana = anaPhone.boot()
    private val ben = benPhone.boot()
    private val stranger = SimulatedPhone(KeyRotation.PER_SESSION).boot()
    private val now = anaPhone.nowMs

    /** Both phones showed the same code, the users confirmed it, and each sent the other an invite. */
    private fun pair(a: TinkTrustEngine, aLabel: String, b: TinkTrustEngine, bLabel: String) {
        assertTrue(b.acceptFriend(a.identity.publicKey, a.friendInvite(b.identity.publicKey)!!, aLabel))
        assertTrue(a.acceptFriend(b.identity.publicKey, b.friendInvite(a.identity.publicKey)!!, bLabel))
    }

    @Test
    fun paired_friends_recognise_each_other_and_nobody_else_does() {
        pair(ana, "Ana", ben, "Ben")

        assertEquals("Ana", ben.matchFriend(ana.beaconTag(now), now))
        assertEquals("Ben", ana.matchFriend(ben.beaconTag(now), now))
        assertNull("a stranger cannot recognise Ana", stranger.matchFriend(ana.beaconTag(now), now))
        assertNull("Ben does not mistake a stranger for a friend", ben.matchFriend(stranger.beaconTag(now), now))
    }

    /** Bystanders logging beacons cannot link one window to the next. */
    @Test
    fun the_tag_changes_every_window_and_a_friend_still_matches_across_small_clock_skew() {
        pair(ana, "Ana", ben, "Ben")
        val window = FriendBook.WINDOW_MS

        assertNotEquals(ana.beaconTag(now), ana.beaconTag(now + window))
        assertEquals("Ana", ben.matchFriend(ana.beaconTag(now), now + window))
        assertEquals("Ana", ben.matchFriend(ana.beaconTag(now + window), now))
        assertNull("two windows apart is a stale or replayed tag", ben.matchFriend(ana.beaconTag(now), now + 2 * window + 1))
    }

    @Test
    fun both_phones_show_the_same_six_digit_code_and_a_different_peer_shows_another() {
        val anaSees = ana.pairingCode(ben.identity.publicKey)!!

        assertEquals(anaSees, ben.pairingCode(ana.identity.publicKey))
        assertTrue(anaSees.matches(Regex("\\d{6}")))
        assertNotEquals(anaSees, ana.pairingCode(stranger.identity.publicKey))
        assertNull(ana.pairingCode("not-a-key"))
        assertNull(ana.pairingCode(ana.identity.publicKey))
    }

    /** Ben confirmed the code with Ana, so only Ana's invite may become "Ana". */
    @Test
    fun an_invite_from_anyone_but_the_confirmed_peer_is_refused() {
        val strangersInvite = stranger.friendInvite(ben.identity.publicKey)!!

        assertFalse(ben.acceptFriend(ana.identity.publicKey, strangersInvite, "Ana"))
        assertTrue(ben.friends().isEmpty())
    }

    /** The invite carries Ana's secret, so a relay forwarding it must not be able to use it. */
    @Test
    fun an_invite_is_sealed_to_its_recipient() {
        val invite = ana.friendInvite(ben.identity.publicKey)!!

        assertFalse(stranger.acceptFriend(ana.identity.publicKey, invite, "Ana"))
        assertNull(stranger.open(invite))
    }

    @Test
    fun friends_survive_a_restart_and_the_per_session_identity_change() {
        pair(ana, "Ana", ben, "Ben")

        val benAfterRestart = benPhone.boot()
        val anaAfterRestart = anaPhone.boot()

        assertNotEquals(ben.identity.nodeId, benAfterRestart.identity.nodeId)
        assertEquals(listOf("Ana"), benAfterRestart.friends())
        assertEquals("Ana", benAfterRestart.matchFriend(anaAfterRestart.beaconTag(now), now))
    }

    @Test
    fun a_removed_friend_is_no_longer_recognised() {
        pair(ana, "Ana", ben, "Ben")

        ben.removeFriend("Ana")

        assertNull(ben.matchFriend(ana.beaconTag(now), now))
        assertTrue(ben.friends().isEmpty())
    }

    /** Revoking: after a rotation, nobody holding the old secret recognises you. */
    @Test
    fun rotating_my_secret_revokes_every_friend() {
        pair(ana, "Ana", ben, "Ben")

        ana.rotateFriendSecret()

        assertNull(ben.matchFriend(ana.beaconTag(now), now))
    }

    @Test
    fun labels_are_local_and_must_be_sensible() {
        val invite = ana.friendInvite(ben.identity.publicKey)!!

        assertFalse(ben.acceptFriend(ana.identity.publicKey, invite, "  "))
        assertFalse(ben.acceptFriend(ana.identity.publicKey, invite, "x".repeat(41)))
        assertTrue(ben.acceptFriend(ana.identity.publicKey, invite, " Mum "))
        assertEquals(listOf("Mum"), ben.friends())
    }

    /** What is stored on disk is ciphertext: no label, no secret. */
    @Test
    fun the_friend_book_is_encrypted_at_rest() {
        val store = InMemoryFriendStore()
        val phone = TinkTrustEngine(
            keys = object : KeysetStore {
                private val keysets = mutableMapOf<String, com.google.crypto.tink.KeysetHandle>()
                override fun getOrCreate(name: String, template: com.google.crypto.tink.KeyTemplate) =
                    keysets.getOrPut(name) { com.google.crypto.tink.KeysetHandle.generateNew(template) }
                override fun names() = keysets.keys.toSet()
                override fun delete(name: String) {
                    keysets.remove(name)
                }
            },
            wristbands = object : WristbandStore {
                override fun load() = emptyList<mu.proxima.core.model.WristbandTag>()
                override fun save(tags: List<mu.proxima.core.model.WristbandTag>) = Unit
            },
            friendStore = store,
        )
        assertTrue(phone.acceptFriend(ana.identity.publicKey, ana.friendInvite(phone.identity.publicKey)!!, "Grandma Leela"))

        val blob = String(java.util.Base64.getDecoder().decode(store.load()!!), Charsets.ISO_8859_1)
        assertFalse(blob.contains("Grandma"))
        assertFalse(blob.contains("proxima-friends-v1"))
    }

    /** The simulation fake has the feature off: no tag in beacons, never a match. */
    @Test
    fun the_fake_engine_has_friend_find_off() {
        val fake = FakeTrustEngine()

        assertNull(fake.beaconTag(now))
        assertNull(fake.matchFriend("anything", now))
        assertFalse(fake.acceptFriend("k", "x", "Ana"))
    }
}
