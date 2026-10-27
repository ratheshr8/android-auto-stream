package app.siding.browser.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import app.siding.browser.R
import app.siding.browser.car.DrivingGuard
import app.siding.browser.data.BrowserStore
import app.siding.browser.logic.Display
import app.siding.browser.logic.Link
import app.siding.browser.logic.PermissionAsk
import app.siding.browser.logic.PlaybackGate
import app.siding.browser.logic.Settings
import app.siding.browser.logic.Sheet
import app.siding.browser.logic.Snapshot
import app.siding.browser.logic.Startup
import app.siding.browser.logic.TabSummary
import app.siding.browser.logic.ThemeMode
import app.siding.browser.logic.UrlFormatter
import app.siding.browser.media.CabinPlayback
import java.util.Locale

interface BrowserHost {
    fun askRuntime(permissions: List<String>, result: (Boolean) -> Unit)
    fun chooseFile(callback: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams)
    fun setImmersive(enabled: Boolean)
}

class CabinEngine(
    private val activity: Activity,
    private val store: BrowserStore,
    private val host: BrowserHost,
) {
    val pageContainer: FrameLayout = FrameLayout(activity)

    private val handler = Handler(Looper.getMainLooper())
    private val clearMessage = Runnable {
        message = null
        publish()
    }
    private val tabs = mutableListOf<Tab>()
    private var nextId = 1L
    private var activeId = 0L
    private var settings = store.settings()
    private var sheet: Sheet? = null
    private var message: String? = null
    private var permission: PermissionAsk? = null
    private var pending: Pending? = null
    private var findActive = false
    private var findLabel = ""
    private var progress = 0
    private var loading = false
    private var pageError: String? = null
    private var fullscreen = false
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var hostResumed = false
    private var drivingLocked = false
    private var night = readNight()
    private val mediaBridge = MediaBridge()

    private val playback = CabinPlayback(
        activity,
        allowed = { PlaybackGate.canPlay(hostResumed, drivingLocked) },
        onCommand = { command ->
            when (command) {
                CabinPlayback.Command.PLAY -> playActive()
                CabinPlayback.Command.PAUSE -> pauseMedia(mute = false)
            }
        },
    )

    private val drivingGuard = DrivingGuard(activity) { restricted ->
        setLocked(restricted)
    }

    var snapshot by mutableStateOf(Snapshot())
        private set

    init {
        CookieManager.getInstance().setAcceptCookie(true)
        restore()
        drivingGuard.start()
        publish()
    }

    fun onHostResume() {
        hostResumed = true
        if (!drivingLocked) {
            pageContainer.visibility = View.VISIBLE
            resumeAllTimers()
            activeOrNull()?.web?.onResume()
            activeOrNull()?.web?.evaluateJavascript(UNMUTE_JS, null)
            playback.setEnabled(true)
        } else {
            pageContainer.visibility = View.INVISIBLE
            pauseEverything()
        }
        publish()
    }

    fun onHostPause() {
        hostResumed = false
        pauseEverything()
        CookieManager.getInstance().flush()
        persist()
        playback.setEnabled(false)
    }

    fun onConfigurationChanged() {
        night = readNight()
        paintWebBackgrounds()
        publish()
    }

    fun destroy() {
        handler.removeCallbacks(clearMessage)
        drivingGuard.stop()
        playback.release()
        hideFullscreen()
        tabs.toList().forEach { destroyWeb(it.web) }
        tabs.clear()
    }

    fun onBack(exit: () -> Unit) {
        if (drivingLocked) {
            exit()
            return
        }
        if (permission != null) {
            respondPermission(false)
            return
        }
        if (sheet != null) {
            sheet = null
            publish()
            return
        }
        if (findActive) {
            closeFind()
            return
        }
        if (fullscreen) {
            hideFullscreen()
            return
        }
        val tab = activeOrNull() ?: return
        if (tab.showingShelf && tab.url != null) {
            tab.showingShelf = false
            publish()
            return
        }
        if (!tab.showingShelf && tab.web.canGoBack()) {
            tab.web.goBack()
            return
        }
        if (!tab.showingShelf) {
            tab.showingShelf = true
            pageError = null
            publish()
            return
        }
        exit()
    }

    fun submit(raw: String) {
        val url = UrlFormatter.toNavigation(raw, settings.search.template)
        if (url == null) {
            toast("That address can’t be opened")
            return
        }
        load(url)
    }

    fun openExternal(raw: String?) {
        if (raw.isNullOrBlank()) return
        val template = settings.search.template
        val url = UrlFormatter.extractFirstUrl(raw, template) ?: UrlFormatter.toNavigation(raw, template)
        if (url == null) {
            toast("No web link in that share")
            return
        }
        val current = activeOrNull()
        val fresh = current != null && current.url == null && !current.web.canGoBack()
        if (!fresh) {
            if (tabs.size >= MAX_TABS) {
                toast("Close a tab first. Eight are open.")
                return
            }
            val created = createTab()
            tabs.add(created)
            activeId = created.id
        }
        load(url)
    }

    fun home() {
        val tab = activeOrNull() ?: return
        tab.showingShelf = true
        pageError = null
        sheet = null
        publish()
    }

    fun back() = onBack { }
    fun forward() {
        activeOrNull()?.web?.goForward()
    }

    fun reload() {
        val tab = activeOrNull() ?: return
        if (tab.showingShelf) return
        pageError = null
        tab.web.reload()
        publish()
    }

    fun retry() {
        val url = activeOrNull()?.url ?: return
        pageError = null
        active().showingShelf = false
        active().web.loadUrl(url)
        publish()
    }

    fun newTab() {
        if (tabs.size >= MAX_TABS) {
            toast("Close a tab first. Eight are open.")
            return
        }
        val tab = createTab()
        tabs.add(tab)
        activeId = tab.id
        sheet = null
        pageError = null
        attach()
        persist()
        publish()
    }

    fun selectTab(id: Long) {
        if (tabs.none { it.id == id }) return
        activeId = id
        sheet = null
        pageError = null
        attach()
        persist()
        publish()
    }

    fun closeTab(id: Long) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val removed = tabs.removeAt(index)
        destroyWeb(removed.web)
        if (tabs.isEmpty()) tabs.add(createTab())
        if (activeId == id) activeId = tabs[index.coerceAtMost(tabs.lastIndex)].id
        attach()
        persist()
        publish()
    }

    fun openSheet(sheet: Sheet) {
        this.sheet = sheet
        publish()
    }

    fun closeSheet() {
        sheet = null
        publish()
    }

    fun toggleBookmark() {
        val tab = activeOrNull() ?: return
        val url = tab.url ?: return
        if (!url.startsWith("http")) return
        val current = store.bookmarks().toMutableList()
        val existing = current.indexOfFirst { it.url == url }
        if (existing >= 0) {
            current.removeAt(existing)
            toast("Bookmark removed")
        } else {
            current.add(0, Link(tab.title.ifBlank { url }, url))
            toast("Saved to bookmarks")
        }
        store.saveBookmarks(current)
        publish()
    }

    fun removeBookmark(url: String) {
        store.saveBookmarks(store.bookmarks().filter { it.url != url })
        publish()
    }

    fun openHistory(url: String) {
        sheet = null
        load(url)
    }

    fun clearHistory() {
        store.clearHistory()
        toast("History cleared")
        publish()
    }

    fun saveQuickLink(index: Int, title: String, rawUrl: String) {
        val links = store.quickLinks().toMutableList()
        if (index !in links.indices) return
        val url = UrlFormatter.toNavigation(rawUrl, settings.search.template)
        if (url == null) {
            toast("Enter a site such as youtube.com")
            return
        }
        links[index] = Link(title.trim().ifBlank { urlHost(url) }, url)
        store.saveQuickLinks(links)
        toast("Shelf updated")
        publish()
    }

    fun clearQuickLink(index: Int) {
        val links = store.quickLinks().toMutableList()
        if (index !in links.indices) return
        links[index] = Link("Add", "")
        store.saveQuickLinks(links)
        publish()
    }

    fun updateSettings(next: Settings) {
        val previous = settings
        settings = next.copy(
            displayPercent = Display.sanitizePercent(next.displayPercent),
            homeUrl = next.homeUrl.trim(),
        )
        store.save(settings)
        applyChrome(reload = previous.desktop != settings.desktop || previous.darkenPages != settings.darkenPages)
        publish()
    }

    fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        tabs.forEach {
            it.web.clearCache(true)
            it.web.clearHistory()
            it.web.clearFormData()
        }
        store.clearHistory()
        store.clearPermissions()
        toast("Cookies, cache, history, and site permissions cleared")
        publish()
    }

    fun find(query: String) {
        findActive = true
        if (query.isBlank()) {
            findLabel = ""
            activeOrNull()?.web?.clearMatches()
        } else {
            activeOrNull()?.web?.findAllAsync(query)
        }
        publish()
    }

    fun findNext(forward: Boolean) {
        activeOrNull()?.web?.findNext(forward)
    }

    fun closeFind() {
        findActive = false
        findLabel = ""
        activeOrNull()?.web?.clearMatches()
        publish()
    }

    fun respondPermission(allow: Boolean) {
        val ask = pending
        pending = null
        permission = null
        when (ask) {
            is Pending.Web -> {
                if (!allow) {
                    ask.request.deny()
                } else {
                    val hostName = Uri.parse(ask.origin).host
                    if (hostName != null) ask.kinds.forEach { store.allow(it, hostName) }
                    host.askRuntime(ask.runtime) { granted ->
                        if (granted) ask.request.grant(ask.grant) else ask.request.deny()
                    }
                }
            }
            is Pending.Geo -> {
                if (!allow) {
                    ask.callback.invoke(ask.origin, false, false)
                } else {
                    val hostName = Uri.parse(ask.origin).host
                    if (hostName != null) store.allow("location", hostName)
                    host.askRuntime(listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)) { granted ->
                        ask.callback.invoke(ask.origin, granted, false)
                    }
                }
            }
            null -> Unit
        }
        publish()
    }

    fun toast(text: String) {
        message = text
        publish()
        handler.removeCallbacks(clearMessage)
        handler.postDelayed(clearMessage, 3_500)
    }

    fun dismissMessage() {
        message = null
        handler.removeCallbacks(clearMessage)
        publish()
    }

    private fun restore() {
        val saved = store.tabs()
        when {
            settings.startup == Startup.LAST_TABS && saved.isNotEmpty() -> {
                saved.take(MAX_TABS).forEach { link ->
                    val tab = createTab()
                    if (link.url.startsWith("http")) {
                        tab.url = link.url
                        tab.title = link.title.ifBlank { link.url }
                        tab.showingShelf = false
                        tab.web.loadUrl(link.url)
                    }
                    tabs.add(tab)
                }
                activeId = tabs[store.activeIndex().coerceIn(tabs.indices)].id
            }
            settings.startup == Startup.HOME && settings.homeUrl.isNotBlank() -> {
                val tab = createTab()
                tabs.add(tab)
                activeId = tab.id
                val url = UrlFormatter.toNavigation(settings.homeUrl, settings.search.template)
                if (url != null) {
                    tab.url = url
                    tab.showingShelf = false
                    tab.web.loadUrl(url)
                }
            }
            else -> {
                val tab = createTab()
                tabs.add(tab)
                activeId = tab.id
            }
        }
        attach()
    }

    private fun load(url: String) {
        val tab = active()
        tab.showingShelf = false
        tab.url = url
        pageError = null
        loading = true
        progress = 5
        tab.web.loadUrl(url)
        persist()
        publish()
    }

    private fun createTab(): Tab {
        val tab = Tab(id = nextId++, web = createWeb())
        return tab
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWeb(): WebView {
        val web = WebView(activity)
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        web.setBackgroundColor(webColor())
        web.isVerticalScrollBarEnabled = true
        web.isHorizontalScrollBarEnabled = false
        web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val original = web.settings.userAgentString
        web.setTag(R.id.original_ua, original)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            textZoom = settings.displayPercent
            @Suppress("DEPRECATION")
            saveFormData = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            offscreenPreRaster = true
        }
        @Suppress("DEPRECATION")
        web.settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        web.settings.allowUniversalAccessFromFileURLs = false
        if (settings.desktop) {
            web.settings.userAgentString = UrlFormatter.desktopUserAgent(original)
            web.settings.loadWithOverviewMode = false
        }
        applyDark(web)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(mediaBridge, "SidingMedia")
        web.setFindListener { activeMatch, count, _ ->
            if (web === activeOrNull()?.web) {
                findLabel = if (count > 0) "${activeMatch + 1}/$count" else "0"
                publish()
            }
        }
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("http://") || url.startsWith("https://")) {
                val request = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                    setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
                    setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                        guessFileName(url),
                    )
                }
                val manager = activity.getSystemService(android.app.DownloadManager::class.java)
                runCatching { manager?.enqueue(request) }
                    .onSuccess { toast("Download saved to Downloads") }
                    .onFailure { toast("This download couldn’t start") }
            } else {
                toast("That download isn’t a normal web file")
            }
        }
        return web
    }

    private fun applyChrome(reload: Boolean) {
        paintWebBackgrounds()
        tabs.forEach { tab ->
            val original = tab.web.getTag(R.id.original_ua) as? String ?: tab.web.settings.userAgentString
            tab.web.settings.userAgentString = if (settings.desktop) {
                UrlFormatter.desktopUserAgent(original)
            } else {
                original
            }
            tab.web.settings.loadWithOverviewMode = !settings.desktop
            tab.web.settings.textZoom = settings.displayPercent
            applyDark(tab.web)
        }
        if (reload) activeOrNull()?.takeIf { !it.showingShelf }?.web?.reload()
    }

    private fun applyDark(web: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, settings.darkenPages)
        }
    }

    private fun paintWebBackgrounds() {
        val color = webColor()
        pageContainer.setBackgroundColor(color)
        tabs.forEach { it.web.setBackgroundColor(color) }
    }

    private fun webColor(): Int {
        return if (isDark()) Color.parseColor("#10140F") else Color.parseColor("#F6F3EA")
    }

    private fun attach() {
        val tab = activeOrNull() ?: return
        val web = tab.web
        (web.parent as? ViewGroup)?.takeIf { it !== pageContainer }?.removeView(web)
        if (web.parent == null) {
            pageContainer.addView(web)
        }
        for (index in pageContainer.childCount - 1 downTo 0) {
            val child = pageContainer.getChildAt(index)
            if (child is WebView && child !== web) pageContainer.removeViewAt(index)
        }
        tabs.forEach {
            if (it.id == tab.id && hostResumed && !drivingLocked) it.web.onResume() else it.web.onPause()
        }
    }

    private fun resumeAllTimers() {
        tabs.firstOrNull()?.web?.resumeTimers()
    }

    private fun pauseEverything() {
        tabs.firstOrNull()?.web?.pauseTimers()
        tabs.forEach { tab ->
            tab.web.onPause()
            tab.web.evaluateJavascript(PAUSE_AND_MUTE, null)
        }
        playback.showPaused()
    }

    private fun pauseMedia(mute: Boolean) {
        val script = if (mute) PAUSE_AND_MUTE else PAUSE_JS
        tabs.forEach { it.web.evaluateJavascript(script, null) }
        playback.showPaused()
    }

    private fun playActive() {
        if (!PlaybackGate.canPlay(hostResumed, drivingLocked)) {
            pauseMedia(mute = true)
            return
        }
        activeOrNull()?.web?.evaluateJavascript(PLAY_JS, null)
        playback.showPlaying()
    }

    private fun setLocked(locked: Boolean) {
        drivingLocked = locked
        if (locked) {
            sheet = null
            if (pending != null) respondPermission(false)
            hideFullscreen()
            pageContainer.visibility = View.INVISIBLE
            pauseEverything()
            playback.setEnabled(false)
        } else if (hostResumed) {
            pageContainer.visibility = View.VISIBLE
            resumeAllTimers()
            activeOrNull()?.web?.onResume()
            activeOrNull()?.web?.evaluateJavascript(UNMUTE_JS, null)
            playback.setEnabled(true)
        }
        publish()
    }

    private fun hideFullscreen() {
        val view = fullscreenView
        val callback = fullscreenCallback
        if (view == null && callback == null && !fullscreen) return
        fullscreenView = null
        fullscreenCallback = null
        fullscreen = false
        if (view != null) pageContainer.removeView(view)
        host.setImmersive(false)
        callback?.onCustomViewHidden()
    }

    private fun persist() {
        val index = tabs.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        store.saveTabs(tabs.map { Link(it.title, it.url.orEmpty()) }, index)
    }

    private fun publish() {
        val tab = activeOrNull()
        val bookmarks = store.bookmarks()
        val url = tab?.url
        snapshot = Snapshot(
            tabs = tabs.map { TabSummary(it.id, it.title, it.url) },
            activeId = activeId,
            pageTitle = tab?.title ?: "Siding",
            pageUrl = url,
            shelf = tab?.showingShelf != false,
            progress = progress,
            loading = loading && tab?.showingShelf == false,
            canBack = fullscreen || sheet != null || findActive || (tab?.web?.canGoBack() == true) || tab?.showingShelf == false,
            canForward = tab?.web?.canGoForward() == true,
            secure = url?.startsWith("https://") == true,
            fullscreen = fullscreen,
            drivingLock = drivingLocked,
            findActive = findActive,
            findLabel = findLabel,
            sheet = if (drivingLocked) null else sheet,
            quickLinks = store.quickLinks(),
            bookmarks = bookmarks,
            history = store.history(),
            settings = settings,
            message = if (drivingLocked) null else message,
            permission = if (drivingLocked) null else permission,
            bookmarked = url != null && bookmarks.any { it.url == url },
            pageError = if (tab?.showingShelf == false) pageError else null,
            night = night,
            dark = isDark(),
        )
    }

    private fun isDark(): Boolean = when (settings.theme) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> night
    }

    private fun readNight(): Boolean {
        val mode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun active(): Tab = tabs.first { it.id == activeId }

    private fun activeOrNull(): Tab? = tabs.firstOrNull { it.id == activeId }

    private fun tabFor(web: WebView): Tab? = tabs.firstOrNull { it.web === web }

    private fun destroyWeb(web: WebView) {
        (web.parent as? ViewGroup)?.removeView(web)
        web.stopLoading()
        web.webChromeClient = null
        web.destroy()
    }

    private fun guessFileName(url: String): String {
        val last = Uri.parse(url).lastPathSegment?.substringBefore('?')?.takeIf { it.contains('.') }
        if (last != null) return last.take(80)
        return "siding-download"
    }

    private fun urlHost(url: String): String = Uri.parse(url).host?.removePrefix("www.") ?: url

    private inner class MediaBridge {
        @JavascriptInterface
        fun onPlaying() {
            handler.post {
                if (!PlaybackGate.canPlay(hostResumed, drivingLocked)) {
                    pauseMedia(mute = true)
                } else {
                    playback.showPlaying()
                }
            }
        }

        @JavascriptInterface
        fun onPaused() {
            handler.post { playback.showPaused() }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase(Locale.US)
            if (scheme == "http" || scheme == "https" || scheme == "about") return false
            toast("Blocked a non-web link")
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            val tab = tabFor(view) ?: return
            if (url.startsWith("http")) {
                tab.url = url
                tab.showingShelf = false
            }
            pageError = null
            loading = true
            progress = 8
            publish()
        }

        override fun onPageFinished(view: WebView, url: String) {
            val tab = tabFor(view) ?: return
            loading = false
            progress = 100
            if (url.startsWith("http")) {
                tab.url = url
                store.addHistory(Link(tab.title.ifBlank { url }, url))
            }
            view.evaluateJavascript(MEDIA_HOOK, null)
            persist()
            publish()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            val tab = tabFor(view) ?: return
            if (url.startsWith("http") && tab.url != url) {
                tab.url = url
                tab.showingShelf = false
                store.addHistory(Link(tab.title.ifBlank { url }, url))
                persist()
                publish()
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            loading = false
            pageError = error.description?.toString()?.ifBlank { null } ?: "The connection failed"
            publish()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            loading = false
            pageError = "This site’s certificate isn’t trusted, so Siding didn’t open it."
            publish()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val tab = tabFor(view) ?: return true
            val url = tab.url
            val title = tab.title
            val shelf = tab.showingShelf
            destroyWeb(view)
            val replacement = createWeb()
            tab.web = replacement
            tab.title = title
            tab.url = url
            tab.showingShelf = shelf
            if (!shelf && url != null) replacement.loadUrl(url)
            attach()
            toast("The page crashed and was reopened")
            publish()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (view !== activeOrNull()?.web) return
            progress = newProgress
            loading = newProgress in 1..99
            publish()
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            val web = view ?: return
            val tab = tabFor(web) ?: return
            val clean = title?.takeIf { it.isNotBlank() && it != tab.url } ?: return
            tab.title = clean
            store.retitle(tab.url, clean)
            playback.setTitle(clean)
            publish()
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null || drivingLocked) {
                callback?.onCustomViewHidden()
                return
            }
            hideFullscreen()
            fullscreenView = view
            pageContainer.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            fullscreenCallback = callback
            fullscreen = true
            host.setImmersive(true)
            publish()
        }

        override fun onHideCustomView() {
            hideFullscreen()
            publish()
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            if (!isUserGesture || drivingLocked) return false
            if (tabs.size >= MAX_TABS) {
                toast("Close a tab first. Eight are open.")
                return false
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val tab = createTab()
            tabs.add(tab)
            activeId = tab.id
            transport.webView = tab.web
            resultMsg.sendToTarget()
            attach()
            persist()
            publish()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            tabFor(window)?.let { closeTab(it.id) }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val origin = request.origin?.toString().orEmpty()
            if (!origin.startsWith("https://") && !origin.startsWith("http://")) {
                request.deny()
                return
            }
            val resources = request.resources?.toList().orEmpty()
            val protectedMedia = resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            val rest = resources.filter { it != PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (rest.isEmpty()) {
                if (protectedMedia.isNotEmpty()) request.grant(protectedMedia.toTypedArray()) else request.deny()
                return
            }
            val runtime = mutableListOf<String>()
            val kinds = mutableListOf<String>()
            val labels = mutableListOf<String>()
            rest.forEach { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                        runtime += android.Manifest.permission.RECORD_AUDIO
                        kinds += "microphone"
                        labels += "the microphone"
                    }
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                        runtime += android.Manifest.permission.CAMERA
                        kinds += "camera"
                        labels += "the camera"
                    }
                    else -> {
                        request.deny()
                        return
                    }
                }
            }
            val hostName = Uri.parse(origin).host
            if (hostName != null && kinds.all { store.isAllowed(it, hostName) }) {
                val grant = (rest + protectedMedia).toTypedArray()
                host.askRuntime(runtime.distinct()) { granted ->
                    if (granted) request.grant(grant) else request.deny()
                }
                return
            }
            pending = Pending.Web(
                request = request,
                runtime = runtime.distinct(),
                grant = (rest + protectedMedia).toTypedArray(),
                origin = origin,
                kinds = kinds,
            )
            permission = PermissionAsk(
                origin = hostName ?: origin,
                summary = "${hostName ?: "This site"} wants ${labels.joinToString(" and ")}.",
            )
            publish()
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if ((pending as? Pending.Web)?.request === request) {
                pending = null
                permission = null
                publish()
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            val hostName = Uri.parse(origin).host
            if (hostName != null && store.isAllowed("location", hostName)) {
                host.askRuntime(listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)) { granted ->
                    callback.invoke(origin, granted, false)
                }
                return
            }
            pending = Pending.Geo(origin, callback)
            permission = PermissionAsk(
                origin = hostName ?: origin,
                summary = "${hostName ?: "This site"} wants your location.",
            )
            publish()
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            host.chooseFile(filePathCallback, fileChooserParams)
            return true
        }
    }

    private class Tab(
        val id: Long,
        var web: WebView,
        var title: String = "New tab",
        var url: String? = null,
        var showingShelf: Boolean = true,
    )

    private sealed interface Pending {
        class Web(
            val request: PermissionRequest,
            val runtime: List<String>,
            val grant: Array<String>,
            val origin: String,
            val kinds: List<String>,
        ) : Pending

        class Geo(val origin: String, val callback: GeolocationPermissions.Callback) : Pending
    }

    private companion object {
        const val MAX_TABS = 8
        const val PLAY_JS =
            "(function(){var n=document.querySelectorAll('video,audio');for(var i=0;i<n.length;i++){try{n[i].muted=false;n[i].play();}catch(e){}}})();"
        const val PAUSE_JS =
            "(function(){var n=document.querySelectorAll('video,audio');for(var i=0;i<n.length;i++){try{n[i].pause();}catch(e){}}})();"
        const val PAUSE_AND_MUTE =
            "(function(){var n=document.querySelectorAll('video,audio');for(var i=0;i<n.length;i++){try{n[i].pause();n[i].muted=true;}catch(e){}}})();"
        const val UNMUTE_JS =
            "(function(){var n=document.querySelectorAll('video,audio');for(var i=0;i<n.length;i++){try{n[i].muted=false;}catch(e){}}})();"
        const val MEDIA_HOOK = """
            (function(){
              if (window.__sidingMediaHook) return;
              window.__sidingMediaHook = true;
              function kind(target){return target && (target.tagName==='VIDEO'||target.tagName==='AUDIO');}
              document.addEventListener('play', function(e){ if(kind(e.target)&&window.SidingMedia) window.SidingMedia.onPlaying(); }, true);
              document.addEventListener('pause', function(e){ if(kind(e.target)&&window.SidingMedia) window.SidingMedia.onPaused(); }, true);
            })();
        """
    }
}
