package com.ternparagliding.mezulla.pairing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [TeamLink.fromHex] is how a Spedmo club (name + hex PSK) becomes a Mezulla team (Epic 03 3.9).
 * Proves the hex key decodes to the exact bytes provisioned to the board, and that a club link
 * round-trips through encode/parse unchanged — so the pilot lands on the club's channel.
 */
class TeamLinkTest {

    @Test
    fun `fromHex decodes the club PSK to the exact channel-key bytes`() {
        val team = TeamLink.fromHex("Chelan XC", "00ff10")
        assertThat(team).isNotNull()
        assertThat(team!!.name).isEqualTo("Chelan XC")
        assertThat(team.psk.toList()).containsExactly(0x00.toByte(), 0xff.toByte(), 0x10.toByte()).inOrder()
    }

    @Test
    fun `a club team round-trips through encode then parse`() {
        val original = TeamLink.fromHex("Bir Billing", "a1b2c3d4e5f6")!!
        val parsed = TeamLink.parse(TeamLink.encode(original))
        assertThat(parsed).isEqualTo(original) // value equality (name + key bytes)
    }

    @Test
    fun `fromHex rejects bad input`() {
        assertThat(TeamLink.fromHex("", "00ff")).isNull()        // blank name
        assertThat(TeamLink.fromHex("X", "0g")).isNull()         // non-hex
        assertThat(TeamLink.fromHex("X", "abc")).isNull()        // odd length
    }
}
