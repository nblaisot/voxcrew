package com.nblaisot.voxcrew.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayConfigLinkParserTest {

    @Test
    fun `parse extracts url secret and fingerprint`() {
        val link = RelayConfigLinkParser.parse(
            "voxcrew://relay-config?url=wss%3A%2F%2Fexample.com%3A8443&secret=crew&certSha256=abcd",
        )!!
        assertEquals("wss://example.com:8443", link.url)
        assertEquals("crew", link.secret)
        assertEquals("abcd", link.certSha256)
    }

    @Test
    fun `parse rejects missing secret`() {
        assertNull(RelayConfigLinkParser.parse("voxcrew://relay-config?url=wss://example.com:8443"))
    }

    @Test
    fun `build round-trips through parse`() {
        val built = RelayConfigLinkParser.build(
            url = "wss://mini.example:8443",
            secret = "s3cret",
            certSha256 = "deadbeef",
        )
        val parsed = RelayConfigLinkParser.parse(built)!!
        assertEquals("wss://mini.example:8443", parsed.url)
        assertEquals("s3cret", parsed.secret)
        assertEquals("deadbeef", parsed.certSha256)
    }
}
