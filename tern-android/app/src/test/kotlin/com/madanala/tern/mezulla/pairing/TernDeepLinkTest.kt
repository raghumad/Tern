package com.madanala.tern.mezulla.pairing

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TernDeepLinkTest {

    @Test
    fun `parses valid tern pair URL`() {
        val link = TernPairLink.parse("tern://p?n=4a312aaa&t=e7f3a1b2c4d5e6f78901a2b3c4d5e6f7")
        assertThat(link).isNotNull()
        assertThat(link!!.nodeIdHex).isEqualTo("4a312aaa")
        assertThat(link.pairingToken).isEqualTo("e7f3a1b2c4d5e6f78901a2b3c4d5e6f7")
    }

    @Test
    fun `nodeNumber converts hex to long`() {
        val link = TernPairLink.parse("tern://p?n=4a312aaa&t=abcdef01")
        assertThat(link!!.nodeNumber).isEqualTo(0x4a312aaaL)
    }

    @Test
    fun `rejects non-tern scheme`() {
        assertThat(TernPairLink.parse("https://p?n=4a312aaa&t=abcdef01")).isNull()
    }

    @Test
    fun `rejects wrong host`() {
        assertThat(TernPairLink.parse("tern://pair?n=4a312aaa&t=abcdef01")).isNull()
    }

    @Test
    fun `rejects missing node ID`() {
        assertThat(TernPairLink.parse("tern://p?t=abcdef01")).isNull()
    }

    @Test
    fun `rejects missing token`() {
        assertThat(TernPairLink.parse("tern://p?n=4a312aaa")).isNull()
    }

    @Test
    fun `rejects non-hex node ID`() {
        assertThat(TernPairLink.parse("tern://p?n=ZZZZZZZZ&t=abcdef01")).isNull()
    }

    @Test
    fun `rejects non-hex token`() {
        assertThat(TernPairLink.parse("tern://p?n=4a312aaa&t=not-hex!!")).isNull()
    }

    @Test
    fun `normalizes to lowercase`() {
        val link = TernPairLink.parse("tern://p?n=4A312AAA&t=E7F3A1B2")
        assertThat(link!!.nodeIdHex).isEqualTo("4a312aaa")
        assertThat(link.pairingToken).isEqualTo("e7f3a1b2")
    }

    @Test
    fun `rejects blank values`() {
        assertThat(TernPairLink.parse("tern://p?n=&t=abcdef01")).isNull()
        assertThat(TernPairLink.parse("tern://p?n=4a312aaa&t=")).isNull()
    }
}
