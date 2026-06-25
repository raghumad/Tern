package com.ternparagliding.claims

import com.ternparagliding.mezulla.connection.ble.MeshPacketCodec
import com.ternparagliding.mezulla.connection.ble.Proto
import com.ternparagliding.mezulla.connection.ble.ProtoReader
import com.ternparagliding.mezulla.pairing.Team
import com.ternparagliding.mezulla.pairing.TeamLink
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapState
import com.ternparagliding.redux.mapReducer
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claim-driven tests for **Mezulla teams** — create/share/join a team (= the board's PRIMARY LoRa
 * channel). Two pure pieces are tested: the share-link round-trip, and the structural shape of the
 * `set_team` (admin set_channel) frame. The latter can't be checked on hardware in CI, so the proto
 * field numbers — the thing a board silently ignores if wrong — are pinned here.
 */
class MezullaTeamClaimsTest {

    // -- TeamLink: create / share / join -----------------------------------

    @Test
    fun `a created team round-trips through its share link`() {
        val team = TeamLink.create("Aravis Crew", Random(42))
        assertEquals(TeamLink.PSK_BYTES, team.psk.size)
        val parsed = TeamLink.parse(TeamLink.encode(team))
        assertEquals(team, parsed)
    }

    @Test
    fun `team names with spaces and unicode survive the link`() {
        val team = Team("Bir Billing — équipe 🪂", byteArrayOf(1, 2, 3, 4))
        val parsed = TeamLink.parse(TeamLink.encode(team))!!
        assertEquals("Bir Billing — équipe 🪂", parsed.name)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), parsed.psk)
    }

    @Test
    fun `parse rejects anything that is not a well-formed team link`() {
        assertNull("a pairing link is not a team link", TeamLink.parse("tern://p?n=abcd&t=ef01"))
        assertNull("missing key", TeamLink.parse("tern://team?n=Crew"))
        assertNull("blank name", TeamLink.parse("tern://team?n=&k=ab"))
        assertNull("odd-length hex key", TeamLink.parse("tern://team?n=Crew&k=abc"))
        assertNull("non-hex key", TeamLink.parse("tern://team?n=Crew&k=zz"))
        assertNull("garbage", TeamLink.parse("hello world"))
    }

    // -- ChannelSettings proto round-trip ----------------------------------

    @Test
    fun `channel settings encode and decode preserve name and psk`() {
        val psk = ByteArray(16) { it.toByte() }
        val bytes = MeshPacketCodec.encodeChannelSettings("Team Tern", psk)
        val (name, decodedPsk) = MeshPacketCodec.decodeChannelSettings(bytes)!!
        assertEquals("Team Tern", name)
        assertArrayEquals(psk, decodedPsk)
    }

    // -- set_team (admin set_channel) frame structure ----------------------

    @Test
    fun `set_team frame is an admin set_channel of a PRIMARY channel with our name and psk`() {
        val psk = ByteArray(16) { (it * 7).toByte() }
        val frame = MeshPacketCodec.encodeToRadioSetChannel(
            boardNodeNumber = 0xABCDEF01L, packetId = 5, name = "Crew", psk = psk,
        )

        // ToRadio.packet(1) → MeshPacket.decoded(4) = Data
        val packet = ld(frame, 1)!!
        val data = ld(packet, 4)!!
        // Data.portnum(1) == ADMIN_APP (6), Data.payload(2) = AdminMessage
        assertEquals(6L, varint(data, 1))
        val admin = ld(data, 2)!!
        // AdminMessage.set_channel(33) = Channel
        val channel = ld(admin, 33)!!
        // Channel.index(1)==0, Channel.role(3)==PRIMARY(1), Channel.settings(2)=ChannelSettings
        assertEquals(0L, varint(channel, 1))
        assertEquals("role must be PRIMARY so it's the team everyone talks on", 1L, varint(channel, 3))
        val settings = ld(channel, 2)!!
        val (name, decodedPsk) = MeshPacketCodec.decodeChannelSettings(settings)!!
        assertEquals("Crew", name)
        assertArrayEquals(psk, decodedPsk)
    }

    @Test
    fun `set_team is addressed to the board with from=0 (trusted local phone)`() {
        val frame = MeshPacketCodec.encodeToRadioSetChannel(0x12345678L, 9, "Crew", ByteArray(0))
        val packet = ld(frame, 1)!!
        assertEquals("from=0 → no admin passkey needed", 0L, fixed32(packet, 1))
        assertEquals(0x12345678L, fixed32(packet, 2))
    }

    // -- lifecycle: intent vs applied (the reducer state machine) ----------

    private fun applied(s: MapState): Boolean =
        s.settingsState.teamShareLink != null &&
            s.settingsState.teamShareLink == s.settingsState.teamAppliedLink

    @Test
    fun `creating a team records intent but is not yet applied to a board`() {
        val team = TeamLink.create("Crew", Random(1))
        val s = mapReducer(MapState(), MapAction.SetTeam(team.name, TeamLink.encode(team), "manual"))
        assertEquals("Crew", s.settingsState.teamName)
        assertEquals("manual", s.settingsState.teamSource)
        assertFalse("not on a board until the reconcile step writes it", applied(s))
    }

    @Test
    fun `applying marks the team on-board - only then is it applied`() {
        val link = TeamLink.encode(TeamLink.create("Crew", Random(2)))
        var s = mapReducer(MapState(), MapAction.SetTeam("Crew", link, "manual"))
        s = mapReducer(s, MapAction.SetTeamApplied(link))
        assertTrue(applied(s))
    }

    @Test
    fun `switching teams drops back to pending until the new one is applied`() {
        val a = TeamLink.encode(TeamLink.create("A", Random(3)))
        val b = TeamLink.encode(TeamLink.create("B", Random(4)))
        var s = mapReducer(MapState(), MapAction.SetTeam("A", a, "manual"))
        s = mapReducer(s, MapAction.SetTeamApplied(a))
        assertTrue(applied(s))
        // Switch to B: the old applied marker stays so reconcile notices the change, but B is pending.
        s = mapReducer(s, MapAction.SetTeam("B", b, "manual"))
        assertFalse("new team isn't on the board yet", applied(s))
        assertEquals(a, s.settingsState.teamAppliedLink)
        s = mapReducer(s, MapAction.SetTeamApplied(b))
        assertTrue(applied(s))
    }

    @Test
    fun `leaving clears the team and its applied marker`() {
        val link = TeamLink.encode(TeamLink.create("Crew", Random(5)))
        var s = mapReducer(MapState(), MapAction.SetTeam("Crew", link, "manual"))
        s = mapReducer(s, MapAction.SetTeamApplied(link))
        s = mapReducer(s, MapAction.SetTeam(null, null, null))
        assertNull(s.settingsState.teamName)
        assertNull(s.settingsState.teamShareLink)
        assertNull(s.settingsState.teamAppliedLink)
        assertFalse(applied(s))
    }

    // -- minimal proto field extractors (test-only) ------------------------

    private fun ld(bytes: ByteArray, field: Int): ByteArray? {
        val r = ProtoReader(bytes)
        while (r.hasMore()) {
            val tag = r.readTag(); val f = tag ushr 3; val w = tag and 0x7
            if (f == field && w == Proto.WIRE_LENGTH_DELIMITED) return r.readLengthDelimited()
            r.skipField(w)
        }
        return null
    }

    private fun varint(bytes: ByteArray, field: Int): Long? {
        val r = ProtoReader(bytes)
        while (r.hasMore()) {
            val tag = r.readTag(); val f = tag ushr 3; val w = tag and 0x7
            if (f == field && w == Proto.WIRE_VARINT) return r.readVarint()
            r.skipField(w)
        }
        return null
    }

    private fun fixed32(bytes: ByteArray, field: Int): Long? {
        val r = ProtoReader(bytes)
        while (r.hasMore()) {
            val tag = r.readTag(); val f = tag ushr 3; val w = tag and 0x7
            if (f == field && w == Proto.WIRE_FIXED32) return r.readFixed32()
            r.skipField(w)
        }
        return null
    }
}
