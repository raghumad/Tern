package com.ternparagliding.spedmo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [SpedmoAuthLink.parse] is the return leg of "Sign in with Spedmo": Spedmo's `apiAuthorise.pg`
 * 302-redirects the system browser to `tern://spedmo-auth?key=…`, which Android hands back to the
 * app. Proves the access key is extracted (and URL-decoded), cancel is recognised, and the matcher
 * never collides with the pairing (`tern://p`) or team-join (`tern://team`) deep links.
 */
class SpedmoAuthLinkTest {

    @Test
    fun `extracts the access key from the redirect`() {
        val r = SpedmoAuthLink.parse("tern://spedmo-auth?key=abc-123")
        assertThat(r).isInstanceOf(SpedmoAuthResult.Success::class.java)
        assertThat((r as SpedmoAuthResult.Success).accessKey).isEqualTo("abc-123")
    }

    @Test
    fun `url-decodes a percent-encoded key`() {
        val r = SpedmoAuthLink.parse("tern://spedmo-auth?key=a%2Bb%20c")
        assertThat((r as SpedmoAuthResult.Success).accessKey).isEqualTo("a+b c")
    }

    @Test
    fun `key survives extra query params in any order`() {
        val r = SpedmoAuthLink.parse("tern://spedmo-auth?state=xyz&key=K9&foo=bar")
        assertThat((r as SpedmoAuthResult.Success).accessKey).isEqualTo("K9")
    }

    @Test
    fun `cancel is recognised`() {
        assertThat(SpedmoAuthLink.parse("tern://spedmo-auth?cancel=true")).isEqualTo(SpedmoAuthResult.Cancelled)
    }

    @Test
    fun `missing or blank key is not a success`() {
        assertThat(SpedmoAuthLink.parse("tern://spedmo-auth")).isNull()
        assertThat(SpedmoAuthLink.parse("tern://spedmo-auth?key=")).isNull()
        assertThat(SpedmoAuthLink.parse("tern://spedmo-auth?other=1")).isNull()
    }

    @Test
    fun `does not match the pairing or team-join links`() {
        assertThat(SpedmoAuthLink.parse("tern://p?n=ab&t=cd")).isNull()
        assertThat(SpedmoAuthLink.parse("tern://team?n=Chelan&k=00ff")).isNull()
        assertThat(SpedmoAuthLink.parse("tern://spedmo-authority?key=K")).isNull() // host must be exact
    }
}
