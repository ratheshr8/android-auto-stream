package app.siding.browser.data

import android.content.SharedPreferences
import app.siding.browser.logic.Catalog
import app.siding.browser.logic.Display
import app.siding.browser.logic.LineCodec
import app.siding.browser.logic.Link
import app.siding.browser.logic.SearchEngine
import app.siding.browser.logic.Settings
import app.siding.browser.logic.Startup
import app.siding.browser.logic.ThemeMode

class BrowserStore(private val prefs: SharedPreferences) {
    fun settings(): Settings {
        val search = runCatching {
            SearchEngine.valueOf(prefs.getString(KEY_SEARCH, null) ?: SearchEngine.GOOGLE.name)
        }.getOrDefault(SearchEngine.GOOGLE)
        val theme = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.DARK.name)
        }.getOrDefault(ThemeMode.DARK)
        val startup = runCatching {
            Startup.valueOf(prefs.getString(KEY_STARTUP, null) ?: Startup.START_PAGE.name)
        }.getOrDefault(Startup.START_PAGE)
        return Settings(
            theme = theme,
            trueBlack = prefs.getBoolean(KEY_TRUE_BLACK, true),
            showAddressBar = prefs.getBoolean(KEY_ADDRESS, true),
            desktop = prefs.getBoolean(KEY_DESKTOP, false),
            displayPercent = Display.sanitizePercent(prefs.getInt(KEY_SCALE, 100)),
            darkenPages = prefs.getBoolean(KEY_DARKEN, false),
            search = search,
            homeUrl = prefs.getString(KEY_HOME, "").orEmpty(),
            startup = startup,
        )
    }

    fun save(settings: Settings) {
        prefs.edit()
            .putString(KEY_THEME, settings.theme.name)
            .putBoolean(KEY_TRUE_BLACK, settings.trueBlack)
            .putBoolean(KEY_ADDRESS, settings.showAddressBar)
            .putBoolean(KEY_DESKTOP, settings.desktop)
            .putInt(KEY_SCALE, Display.sanitizePercent(settings.displayPercent))
            .putBoolean(KEY_DARKEN, settings.darkenPages)
            .putString(KEY_SEARCH, settings.search.name)
            .putString(KEY_HOME, settings.homeUrl)
            .putString(KEY_STARTUP, settings.startup.name)
            .apply()
    }

    fun quickLinks(): List<Link> {
        if (!prefs.contains(KEY_QUICK)) return Catalog.shelf
        return pad(LineCodec.decode(prefs.getString(KEY_QUICK, "").orEmpty()))
    }

    fun saveQuickLinks(links: List<Link>) {
        prefs.edit().putString(KEY_QUICK, LineCodec.encode(pad(links))).apply()
    }

    fun bookmarks(): List<Link> = LineCodec.decode(prefs.getString(KEY_BOOKMARKS, "").orEmpty())

    fun saveBookmarks(links: List<Link>) {
        prefs.edit().putString(KEY_BOOKMARKS, LineCodec.encode(links.take(80))).apply()
    }

    fun history(): List<Link> = LineCodec.decode(prefs.getString(KEY_HISTORY, "").orEmpty())

    fun addHistory(link: Link) {
        if (!link.url.startsWith("http://") && !link.url.startsWith("https://")) return
        val next = listOf(link) + history().filter { it.url != link.url }
        prefs.edit().putString(KEY_HISTORY, LineCodec.encode(next.take(50))).apply()
    }

    fun retitle(url: String?, title: String) {
        if (url.isNullOrBlank() || title.isBlank()) return
        val current = history()
        if (current.isEmpty() || current.first().url != url) return
        val updated = listOf(current.first().copy(title = title)) + current.drop(1)
        prefs.edit().putString(KEY_HISTORY, LineCodec.encode(updated)).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun tabs(): List<Link> = LineCodec.decode(prefs.getString(KEY_TABS, "").orEmpty())

    fun activeIndex(): Int = prefs.getInt(KEY_ACTIVE, 0)

    fun saveTabs(links: List<Link>, activeIndex: Int) {
        prefs.edit()
            .putString(KEY_TABS, LineCodec.encode(links.take(8)))
            .putInt(KEY_ACTIVE, activeIndex.coerceAtLeast(0))
            .apply()
    }

    fun isAllowed(kind: String, host: String): Boolean {
        return prefs.getStringSet(KEY_ALLOWS, emptySet()).orEmpty().contains("$kind|$host")
    }

    fun allow(kind: String, host: String) {
        val next = prefs.getStringSet(KEY_ALLOWS, emptySet()).orEmpty().toMutableSet()
        next.add("$kind|$host")
        prefs.edit().putStringSet(KEY_ALLOWS, next).apply()
    }

    fun clearPermissions() {
        prefs.edit().remove(KEY_ALLOWS).apply()
    }

    private fun pad(links: List<Link>): List<Link> {
        val trimmed = links.take(Catalog.SLOT_COUNT).toMutableList()
        while (trimmed.size < Catalog.SLOT_COUNT) trimmed.add(Link("Add", ""))
        return trimmed
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_TRUE_BLACK = "true_black"
        const val KEY_ADDRESS = "address_bar"
        const val KEY_DESKTOP = "desktop"
        const val KEY_SCALE = "display_percent"
        const val KEY_DARKEN = "darken_pages"
        const val KEY_SEARCH = "search"
        const val KEY_HOME = "home_url"
        const val KEY_STARTUP = "startup"
        const val KEY_QUICK = "quick"
        const val KEY_BOOKMARKS = "bookmarks"
        const val KEY_HISTORY = "history"
        const val KEY_TABS = "tabs"
        const val KEY_ACTIVE = "active_tab"
        const val KEY_ALLOWS = "site_allows"
    }
}
