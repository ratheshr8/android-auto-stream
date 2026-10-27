package app.siding.browser.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLogicTest {
    private val google = SearchEngine.GOOGLE.template

    @Test
    fun searchQueryBecomesAWebSearch() {
        val url = UrlFormatter.toNavigation("dune part two", google)
        assertEquals("https://www.google.com/search?q=dune+part+two", url)
    }

    @Test
    fun bareDomainGetsHttps() {
        assertEquals("https://www.youtube.com", UrlFormatter.toNavigation("www.youtube.com", google))
        assertEquals("https://netflix.com/browse", UrlFormatter.toNavigation("netflix.com/browse", google))
    }

    @Test
    fun httpStaysHttp() {
        assertEquals("http://192.168.1.20/", UrlFormatter.toNavigation("http://192.168.1.20/", google))
    }

    @Test
    fun dangerousSchemesAreRejected() {
        assertNull(UrlFormatter.toNavigation("javascript:alert(1)", google))
        assertNull(UrlFormatter.toNavigation("intent://pay#Intent;end", google))
        assertNull(UrlFormatter.toNavigation("file:///sdcard/secret.mp4", google))
        assertNull(UrlFormatter.toNavigation("data:text/html,hi", google))
    }

    @Test
    fun localDevHostIsAllowed() {
        assertEquals("https://localhost:8080", UrlFormatter.toNavigation("localhost:8080", google))
    }

    @Test
    fun shareTextYieldsTheFirstUrl() {
        val url = UrlFormatter.extractFirstUrl("Watch this https://youtu.be/abc123 tonight", google)
        assertEquals("https://youtu.be/abc123", url)
    }

    @Test
    fun desktopAgentDropsMobileAndKeepsTheWebViewToken() {
        val original = "Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        val desktop = UrlFormatter.desktopUserAgent(original)
        assertFalse(desktop.contains("Mobile"))
        assertTrue(desktop.contains("wv"))
    }

    @Test
    fun lineCodecRoundTripsOddTitles() {
        val links = listOf(
            Link("Line\nbreak", "https://example.com/a"),
            Link("Tab\there", "https://example.com/b\\c"),
        )
        assertEquals(links, LineCodec.decode(LineCodec.encode(links)))
    }

    @Test
    fun playbackRequiresAResumedParkedHost() {
        assertFalse(PlaybackGate.canPlay(hostResumed = false, uxRestricted = false))
        assertFalse(PlaybackGate.canPlay(hostResumed = true, uxRestricted = true))
        assertFalse(PlaybackGate.canPlay(hostResumed = false, uxRestricted = true))
        assertTrue(PlaybackGate.canPlay(hostResumed = true, uxRestricted = false))
    }

    @Test
    fun displayPercentStaysInRange() {
        assertEquals(80, Display.sanitizePercent(10))
        assertEquals(150, Display.sanitizePercent(400))
        assertEquals(120, Display.sanitizePercent(120))
    }
}
