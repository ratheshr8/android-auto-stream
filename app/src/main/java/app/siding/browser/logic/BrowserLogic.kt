package app.siding.browser.logic

import java.net.URI
import java.net.URLEncoder

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class Startup { START_PAGE, HOME, LAST_TABS }

enum class SearchEngine(val label: String, val template: String) {
    GOOGLE("Google", "https://www.google.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    BING("Bing", "https://www.bing.com/search?q="),
}

enum class Sheet { MENU, TABS, BOOKMARKS, HISTORY, SETTINGS, NOTES }

data class Link(val title: String, val url: String)

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val trueBlack: Boolean = true,
    val showAddressBar: Boolean = true,
    val desktop: Boolean = false,
    val displayPercent: Int = 100,
    val darkenPages: Boolean = false,
    val search: SearchEngine = SearchEngine.GOOGLE,
    val homeUrl: String = "",
    val startup: Startup = Startup.START_PAGE,
)

data class TabSummary(val id: Long, val title: String, val url: String?)

data class PermissionAsk(val origin: String, val summary: String)

data class Snapshot(
    val tabs: List<TabSummary> = emptyList(),
    val activeId: Long = 0,
    val pageTitle: String = "Siding",
    val pageUrl: String? = null,
    val shelf: Boolean = true,
    val progress: Int = 0,
    val loading: Boolean = false,
    val canBack: Boolean = false,
    val canForward: Boolean = false,
    val secure: Boolean = false,
    val fullscreen: Boolean = false,
    val drivingLock: Boolean = false,
    val findActive: Boolean = false,
    val findLabel: String = "",
    val sheet: Sheet? = null,
    val quickLinks: List<Link> = emptyList(),
    val bookmarks: List<Link> = emptyList(),
    val history: List<Link> = emptyList(),
    val settings: Settings = Settings(),
    val message: String? = null,
    val permission: PermissionAsk? = null,
    val bookmarked: Boolean = false,
    val pageError: String? = null,
    val night: Boolean = true,
    val dark: Boolean = true,
)

object Catalog {
    const val SLOT_COUNT = 8

    val shelf: List<Link> = listOf(
        Link("YouTube", "https://m.youtube.com"),
        Link("Netflix", "https://www.netflix.com"),
        Link("Prime Video", "https://www.primevideo.com"),
        Link("Disney+", "https://www.disneyplus.com"),
        Link("JioHotstar", "https://www.hotstar.com"),
        Link("Sony LIV", "https://www.sonyliv.com"),
        Link("Zee5", "https://www.zee5.com"),
        Link("Crunchyroll", "https://www.crunchyroll.com"),
    )
}

object Display {
    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 150

    fun sanitizePercent(value: Int): Int = value.coerceIn(MIN_PERCENT, MAX_PERCENT)
}

object PlaybackGate {
    fun canPlay(hostResumed: Boolean, uxRestricted: Boolean): Boolean {
        return hostResumed && !uxRestricted
    }
}

object UrlFormatter {
    private val urlInText = Regex("https?://[^\\s<>'\"]+")

    fun toNavigation(raw: String, searchTemplate: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (isBlockedScheme(text)) return null
        if (text.any { it.isWhitespace() } && !text.contains("://")) {
            return search(text, searchTemplate)
        }
        val candidate = when {
            text.startsWith("http://", ignoreCase = true) ||
                text.startsWith("https://", ignoreCase = true) -> text
            text.startsWith("//") -> "https:$text"
            looksLikeHost(text) -> "https://$text"
            else -> return search(text, searchTemplate)
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return search(text, searchTemplate)
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return runCatching { uri.toASCIIString() }.getOrDefault(candidate)
    }

    fun extractFirstUrl(text: String, searchTemplate: String): String? {
        val match = urlInText.find(text) ?: return null
        val cleaned = match.value.trimEnd('.', ',', ')', ']', '>')
        return toNavigation(cleaned, searchTemplate)
    }

    fun desktopUserAgent(original: String): String {
        return original.replace(" Mobile", "")
    }

    fun search(query: String, searchTemplate: String): String {
        return searchTemplate + URLEncoder.encode(query, Charsets.UTF_8.name())
    }

    private fun looksLikeHost(text: String): Boolean {
        if (text.any { it.isWhitespace() }) return false
        val host = text.substringBefore("/").substringBefore("?")
        if (host.equals("localhost", ignoreCase = true) || host.startsWith("localhost:", ignoreCase = true)) {
            return true
        }
        return host.contains('.') && !host.startsWith(".") && !host.endsWith(".")
    }

    private fun isBlockedScheme(text: String): Boolean {
        val colon = text.indexOf(':')
        if (colon <= 0 || text.take(colon).any { it.isWhitespace() }) return false
        val scheme = text.substring(0, colon).lowercase()
        if (scheme == "http" || scheme == "https") return false
        if (scheme.contains('.')) return false
        if (scheme == "localhost") return false
        return scheme.all { it.isLetter() || it == '+' || it == '-' }
    }
}

object LineCodec {
    fun encode(links: List<Link>): String {
        return links.joinToString("\n") { "${esc(it.title)}\t${esc(it.url)}" }
    }

    fun decode(raw: String): List<Link> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            Link(unesc(line.substring(0, tab)), unesc(line.substring(tab + 1)))
        }.toList()
    }

    private fun esc(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    private fun unesc(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    '\\' -> append('\\')
                    'n' -> append('\n')
                    't' -> append('\t')
                    else -> append(value[index + 1])
                }
                index += 2
            } else {
                append(value[index])
                index += 1
            }
        }
    }
}
